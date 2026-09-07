package net.vheerden.archi.mcp.model.routing;

import java.util.ArrayList;
import java.util.List;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Hub-perimeter routing — Axis 1: corridor-CHOICE diversification primitive.
 *
 * <p><b>Architectural commitment (load-bearing):</b> for each connection whose
 * post-A* route has a segment hugging a hub face (within
 * {@link HubFaceConnectionPartitioner#PROXIMITY_TOLERANCE_PX} px), evaluate an
 * <em>alternative</em> perpendicular position that clears the hub face by at least
 * {@link #TARGET_HUB_CLEARANCE_PX} px and accept the alternative only if it fits
 * within a length-cost-premium budget (default
 * {@link #ALTERNATIVE_CORRIDOR_COST_PREMIUM}, relaxed-retry
 * {@link #ALTERNATIVE_CORRIDOR_COST_PREMIUM_MAX}).
 *
 * <p>This is the Axis 1 primitive composed by {@link HubPerimeterRoutingStage};
 * see {@link CorridorSpreadEnforcer} for Axis 2 (intra-corridor SPREAD). The two
 * axes must run simultaneously per the V4 chain mechanism-design synthesis (spike
 * Task 6 — corridor-choice and intra-corridor-spread are independent observable
 * defects on V4 oracle but the same connection often participates in both).
 *
 * <p><b>Algorithm (MVP: simple bendpoint substitution; escalate to partial A*
 * re-search only if simple bendpoint fails coverage):</b>
 * <ol>
 *   <li>For each cell member, determine the hub face coordinate and the
 *       AWAY-from-hub direction (-1 for TOP/LEFT, +1 for BOTTOM/RIGHT).</li>
 *   <li>If the segment is INSIDE the hub bounds (a passthrough defect), skip —
 *       belongs to a different defect class (PathStraightener handles passthrough).</li>
 *   <li>Compute target coord: {@code hubFaceCoord + direction *
 *       TARGET_HUB_CLEARANCE_PX} (i.e., 10px clear of the face on the away side).</li>
 *   <li>Reject if any non-hub obstacle's perpendicular extent (widened by
 *       {@link #NON_HUB_OBSTACLE_CLEARANCE_PX}) contains target coord AND its
 *       parallel extent overlaps the segment's parallel range.</li>
 *   <li>Compute total path length before and after the shift. If
 *       {@code |Δ| / origLen > budget}, reject.</li>
 *   <li>Otherwise emit a proposal. {@link #apply} performs the in-place mutation
 *       and returns a {@link Snapshot}; {@link #restore} reverts using the snapshot
 *       (Tier-1 rollback hook for the orchestrator).</li>
 * </ol>
 *
 * <p><b>Cost-premium gate caveat.</b> Under simple bendpoint-substitution, the
 * shift typically SHORTENS the path (spoke and shifted-segment are on the same
 * side of the hub, so moving the segment toward the spoke shortens the connecting
 * stubs). The cost-premium budget is mainly a guard against pathological geometry
 * (e.g., spoke positioned just inside the shift target range) and is wired here
 * for the orchestrator's Tier-1 retry-with-widened-constraints policy.
 *
 * <p><b>Pure geometry.</b> No EMF, no SWT, no PDE. {@link #apply} mutates the
 * caller-supplied bendpoint lists in place; {@link #restore} reverts. Callable
 * from standard JUnit tests without OSGi.
 *
 * @see HubFaceConnectionPartitioner
 * @see HubPerimeterRoutingStage
 * @see CorridorSpreadEnforcer
 */
public class AlternativeCorridorSelector {

    /** Default cost-premium budget (10% of original path length). */
    public static final double ALTERNATIVE_CORRIDOR_COST_PREMIUM = 0.10;

    /** Relaxed budget for orchestrator retry-with-widened-constraints (30%). */
    public static final double ALTERNATIVE_CORRIDOR_COST_PREMIUM_MAX = 0.30;

    /** Target perpendicular clearance from hub face after shift (px). */
    static final double TARGET_HUB_CLEARANCE_PX = 10.0;

    /** Required clearance from any non-hub obstacle's perpendicular edge (px). */
    static final double NON_HUB_OBSTACLE_CLEARANCE_PX = 10.0;

    /**
     * A proposed perpendicular shift of one segment of one connection.
     *
     * @param connectionId           the connection id
     * @param pathIndex              index into the parent paths list
     * @param segmentStartBpIdx      bp index — segment start (member's recorded segment)
     * @param horizontal             true if the segment is axis-horizontal (perpendicular = y);
     *                               false if vertical (perpendicular = x)
     * @param oldPerpendicularCoord  the segment's current perpendicular coordinate
     * @param newPerpendicularCoord  the proposed perpendicular coordinate after shift
     * @param lengthDeltaRatio       {@code |newLen - origLen| / origLen} of the full path
     */
    public record AlternativeProposal(String connectionId,
                                       int pathIndex,
                                       int segmentStartBpIdx,
                                       boolean horizontal,
                                       int oldPerpendicularCoord,
                                       int newPerpendicularCoord,
                                       double lengthDeltaRatio) {}

    /**
     * Snapshot of pre-apply bendpoint state, used by {@link #restore} for Tier-1
     * rollback.
     */
    public record Snapshot(int pathIndex,
                           int segmentStartBpIdx,
                           AbsoluteBendpointDto oldBp1,
                           AbsoluteBendpointDto oldBp2) {}

    /**
     * Evaluate alternative-corridor proposals for every member of a cell.
     *
     * @param cell                 the (hub, face) cell with its members
     * @param paths                index-parallel per-route bendpoint lists (NOT mutated by this method)
     * @param allObstacles         every non-container view object — elements, notes and images alike
     *                             (including the hub itself)
     * @param costPremiumBudget    fraction of original path length the shift's |Δ| must not exceed
     * @return one proposal per viable member; empty if cell has no members or all are infeasible
     */
    public List<AlternativeProposal> evaluate(
            HubFaceConnectionPartitioner.HubFaceCell cell,
            List<List<AbsoluteBendpointDto>> paths,
            List<RoutingRect> allObstacles,
            double costPremiumBudget) {
        if (cell == null || cell.members().isEmpty() || paths == null) {
            return List.of();
        }
        List<AlternativeProposal> proposals = new ArrayList<>();
        for (HubFaceConnectionPartitioner.CellMember m : cell.members()) {
            AlternativeProposal p = proposeShift(cell.hub(), cell.face(), m,
                    paths, allObstacles, costPremiumBudget);
            if (p != null) proposals.add(p);
        }
        return proposals;
    }

    /**
     * Apply a proposal in place; return a snapshot suitable for {@link #restore}.
     */
    public Snapshot apply(AlternativeProposal proposal, List<List<AbsoluteBendpointDto>> paths) {
        if (proposal == null || paths == null
                || proposal.pathIndex() < 0 || proposal.pathIndex() >= paths.size()) {
            return null;
        }
        List<AbsoluteBendpointDto> path = paths.get(proposal.pathIndex());
        int s = proposal.segmentStartBpIdx();
        if (path == null || s < 0 || s + 1 >= path.size()) return null;
        AbsoluteBendpointDto oldBp1 = path.get(s);
        AbsoluteBendpointDto oldBp2 = path.get(s + 1);
        int newCoord = proposal.newPerpendicularCoord();
        AbsoluteBendpointDto newBp1;
        AbsoluteBendpointDto newBp2;
        if (proposal.horizontal()) {
            newBp1 = new AbsoluteBendpointDto(oldBp1.x(), newCoord);
            newBp2 = new AbsoluteBendpointDto(oldBp2.x(), newCoord);
        } else {
            newBp1 = new AbsoluteBendpointDto(newCoord, oldBp1.y());
            newBp2 = new AbsoluteBendpointDto(newCoord, oldBp2.y());
        }
        path.set(s, newBp1);
        path.set(s + 1, newBp2);
        return new Snapshot(proposal.pathIndex(), s, oldBp1, oldBp2);
    }

    /**
     * Revert a previously-applied proposal using its snapshot.
     */
    public void restore(Snapshot snapshot, List<List<AbsoluteBendpointDto>> paths) {
        if (snapshot == null || paths == null
                || snapshot.pathIndex() < 0 || snapshot.pathIndex() >= paths.size()) {
            return;
        }
        List<AbsoluteBendpointDto> path = paths.get(snapshot.pathIndex());
        if (path == null || snapshot.segmentStartBpIdx() + 1 >= path.size()) return;
        path.set(snapshot.segmentStartBpIdx(), snapshot.oldBp1());
        path.set(snapshot.segmentStartBpIdx() + 1, snapshot.oldBp2());
    }

    // =====================================================================
    // proposeShift internal — one cell-member evaluation
    // =====================================================================

    private AlternativeProposal proposeShift(
            RoutingRect hub,
            EdgeAttachmentCalculator.Face face,
            HubFaceConnectionPartitioner.CellMember member,
            List<List<AbsoluteBendpointDto>> paths,
            List<RoutingRect> allObstacles,
            double budget) {
        if (member.pathIndex() < 0 || member.pathIndex() >= paths.size()) return null;
        List<AbsoluteBendpointDto> path = paths.get(member.pathIndex());
        if (path == null || member.segmentEndBpIdx() >= path.size()
                || member.segmentStartBpIdx() < 0) return null;
        AbsoluteBendpointDto bp1 = path.get(member.segmentStartBpIdx());
        AbsoluteBendpointDto bp2 = path.get(member.segmentEndBpIdx());
        boolean horizontal = bp1.y() == bp2.y();
        int currentCoord = horizontal ? bp1.y() : bp1.x();
        int hubFaceCoord = hubFaceCoord(hub, face);
        int direction = shiftDirection(face);

        // Skip inside-hub segments (passthrough defects belong to PathStraightener).
        if (direction < 0 && currentCoord > hubFaceCoord) return null;
        if (direction > 0 && currentCoord < hubFaceCoord) return null;

        int targetCoord = hubFaceCoord + direction * (int) Math.round(TARGET_HUB_CLEARANCE_PX);

        int segLo;
        int segHi;
        if (horizontal) {
            segLo = Math.min(bp1.x(), bp2.x());
            segHi = Math.max(bp1.x(), bp2.x());
        } else {
            segLo = Math.min(bp1.y(), bp2.y());
            segHi = Math.max(bp1.y(), bp2.y());
        }

        // Obstacle constraint: non-hub obstacles whose perpendicular extent (widened by
        // clearance) contains targetCoord AND whose parallel extent overlaps the
        // segment's range block the proposal.
        for (RoutingRect obs : allObstacles) {
            if (obs == null) continue;
            if (obs.id() != null && obs.id().equals(hub.id())) continue;
            int obsLo;
            int obsHi;
            int obsPerpLo;
            int obsPerpHi;
            if (horizontal) {
                obsLo = obs.x();
                obsHi = obs.x() + obs.width();
                obsPerpLo = obs.y();
                obsPerpHi = obs.y() + obs.height();
            } else {
                obsLo = obs.y();
                obsHi = obs.y() + obs.height();
                obsPerpLo = obs.x();
                obsPerpHi = obs.x() + obs.width();
            }
            int parallelOverlap = Math.min(segHi, obsHi) - Math.max(segLo, obsLo);
            if (parallelOverlap <= 0) continue;
            if (targetCoord >= obsPerpLo - NON_HUB_OBSTACLE_CLEARANCE_PX
                    && targetCoord <= obsPerpHi + NON_HUB_OBSTACLE_CLEARANCE_PX) {
                return null;
            }
        }

        double origLen = pathLength(path);
        if (origLen <= 0) return null;
        double newLen = pathLengthAfterShift(path, member.segmentStartBpIdx(), targetCoord, horizontal);
        double ratio = Math.abs(newLen - origLen) / origLen;
        if (ratio > budget) return null;

        return new AlternativeProposal(member.connectionId(), member.pathIndex(),
                member.segmentStartBpIdx(), horizontal, currentCoord, targetCoord, ratio);
    }

    private static int hubFaceCoord(RoutingRect hub, EdgeAttachmentCalculator.Face face) {
        return switch (face) {
            case TOP -> hub.y();
            case BOTTOM -> hub.y() + hub.height();
            case LEFT -> hub.x();
            case RIGHT -> hub.x() + hub.width();
        };
    }

    private static int shiftDirection(EdgeAttachmentCalculator.Face face) {
        return switch (face) {
            case TOP, LEFT -> -1;
            case BOTTOM, RIGHT -> +1;
        };
    }

    private static double pathLength(List<AbsoluteBendpointDto> path) {
        double sum = 0.0;
        for (int i = 0; i < path.size() - 1; i++) {
            AbsoluteBendpointDto a = path.get(i);
            AbsoluteBendpointDto b = path.get(i + 1);
            sum += Math.hypot((double) b.x() - a.x(), (double) b.y() - a.y());
        }
        return sum;
    }

    /**
     * Compute total path length after shifting bp[shiftStart] and bp[shiftStart+1]
     * perpendicular to {@code newCoord}. Per the orthogonal-routing invariant the
     * pre-segment (bp[shiftStart-1] → bp[shiftStart]) and post-segment
     * (bp[shiftStart+1] → bp[shiftStart+2]) are perpendicular to the shifted segment,
     * so their lengths change by the shift magnitude; the shifted segment itself is
     * unchanged in length.
     */
    private static double pathLengthAfterShift(List<AbsoluteBendpointDto> path,
                                                int shiftStart,
                                                int newCoord,
                                                boolean horizontal) {
        double sum = 0.0;
        for (int i = 0; i < path.size() - 1; i++) {
            AbsoluteBendpointDto a = path.get(i);
            AbsoluteBendpointDto b = path.get(i + 1);
            int ax;
            int ay;
            int bx;
            int by;
            if (i == shiftStart) {
                if (horizontal) {
                    ax = a.x(); ay = newCoord; bx = b.x(); by = newCoord;
                } else {
                    ax = newCoord; ay = a.y(); bx = newCoord; by = b.y();
                }
            } else if (i + 1 == shiftStart) {
                if (horizontal) {
                    ax = a.x(); ay = a.y(); bx = b.x(); by = newCoord;
                } else {
                    ax = a.x(); ay = a.y(); bx = newCoord; by = b.y();
                }
            } else if (i == shiftStart + 1) {
                if (horizontal) {
                    ax = a.x(); ay = newCoord; bx = b.x(); by = b.y();
                } else {
                    ax = newCoord; ay = a.y(); bx = b.x(); by = b.y();
                }
            } else {
                ax = a.x(); ay = a.y(); bx = b.x(); by = b.y();
            }
            sum += Math.hypot((double) bx - ax, (double) by - ay);
        }
        return sum;
    }
}
