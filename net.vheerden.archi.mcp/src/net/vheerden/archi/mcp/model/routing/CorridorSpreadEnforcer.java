package net.vheerden.archi.mcp.model.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Hub-perimeter routing — Axis 2: intra-corridor SPREAD enforcement primitive.
 *
 * <p><b>Architectural commitment (load-bearing):</b> for each (hub, face) cell with
 * three or more members sharing the same perpendicular coordinate (a zero-spread
 * cluster — the dominant V4-oracle defect class per spike Task 5: "8 of 14 HH
 * high-N corridors have ZERO intra-corridor spread"), enforce a minimum spread
 * between parallel segments via localised track allocation.
 *
 * <p><b>Runs ALONGSIDE {@link ChannelNudgingPass}, NEVER mutates it.</b> Per
 * atomic-swap discipline: {@code ChannelNudgingPass.allocateTracks}'s
 * divisor-7 width-aware cap stays at current calibration; the R8 ≥ 0.25 floor
 * protection stays intact. This enforcer fills the SAME-COORD high-N gap that the
 * divisor-7 cap does NOT enforce.
 *
 * <p><b>TerminalAnchoring exemption.</b> Members whose segment is
 * incident to a terminal bp (segmentStartBpIdx == 0 OR segmentEndBpIdx ==
 * path.size()-1) are skipped — shifting a terminal bp would violate
 * {@code TerminalAnchoring.preservesEndpoints} contract.
 *
 * <p><b>Algorithm:</b>
 * <ol>
 *   <li>If the cell has fewer than {@link #MIN_PARALLEL_COUNT} members: return empty.</li>
 *   <li>Group eligible (non-terminal-incident) members by current perpendicular coord
 *       (within {@link #COORD_TOLERANCE_PX} px).</li>
 *   <li>For each group of size at least {@link #MIN_PARALLEL_COUNT}: compute the
 *       AWAY direction and the available channel width (to the nearest non-hub
 *       obstacle in the away direction, with clearance).</li>
 *   <li>Effective spread = {@code min(MIN_SPREAD_PX, availableChannel / N)}. If
 *       spread is less than 1, skip the group (no room).</li>
 *   <li>Sort group members by their segment's parallel midpoint. Assign successive
 *       positions {@code baseCoord + direction * spread * i} for i = 0..N-1.</li>
 * </ol>
 *
 * <p><b>Pure geometry.</b> No EMF, no SWT, no PDE. {@link #apply} mutates in place;
 * {@link #restore} reverts via {@link Snapshot}.
 *
 * @see HubFaceConnectionPartitioner
 * @see HubPerimeterRoutingStage
 * @see AlternativeCorridorSelector
 */
public class CorridorSpreadEnforcer {

    /** Default minimum perpendicular spread (px) between parallel segments. */
    static final int MIN_SPREAD_PX = 20;

    /** Minimum parallel-segment count at the same coord that triggers spread enforcement. */
    static final int MIN_PARALLEL_COUNT = 3;

    /** Coord-equality tolerance (px) when grouping segments by perpendicular position. */
    static final double COORD_TOLERANCE_PX = 1.0;

    /** Required clearance from any non-hub obstacle's perpendicular edge (px). */
    static final double NON_HUB_OBSTACLE_CLEARANCE_PX = 10.0;

    /** Fallback channel width assumed when no obstacle is in the away direction. */
    private static final int UNBOUNDED_CHANNEL_PX = 1000;

    /**
     * A proposed perpendicular shift of one segment of one connection, emitted as part
     * of a spread-enforcement allocation.
     *
     * @param connectionId           the connection id
     * @param pathIndex              index into the parent paths list
     * @param segmentStartBpIdx      bp index — segment start
     * @param horizontal             true if the segment is horizontal (perpendicular = y);
     *                               false if vertical (perpendicular = x)
     * @param oldPerpendicularCoord  the segment's current perpendicular coordinate
     * @param newPerpendicularCoord  the proposed perpendicular coordinate after spread allocation
     */
    public record SpreadProposal(String connectionId,
                                  int pathIndex,
                                  int segmentStartBpIdx,
                                  boolean horizontal,
                                  int oldPerpendicularCoord,
                                  int newPerpendicularCoord) {}

    /**
     * Snapshot of pre-apply bendpoint state, used by {@link #restore} for Tier-1 rollback.
     */
    public record Snapshot(int pathIndex,
                           int segmentStartBpIdx,
                           AbsoluteBendpointDto oldBp1,
                           AbsoluteBendpointDto oldBp2) {}

    /**
     * Evaluate spread proposals for a cell. Returns one proposal per member of any
     * zero-spread group that triggers spread enforcement.
     *
     * @param cell          the (hub, face) cell with its members
     * @param paths         index-parallel per-route bendpoint lists (NOT mutated by this method)
     * @param allObstacles  every non-container view object — elements, notes and images alike
     *                      (including the hub itself)
     * @return spread proposals; empty if no triggering group exists or channel is too tight
     */
    public List<SpreadProposal> evaluate(
            HubFaceConnectionPartitioner.HubFaceCell cell,
            List<List<AbsoluteBendpointDto>> paths,
            List<RoutingRect> allObstacles) {
        if (cell == null || paths == null) return List.of();
        if (cell.members().size() < MIN_PARALLEL_COUNT) return List.of();

        // Filter out terminal-incident members (TerminalAnchoring exemption).
        List<EligibleMember> eligible = new ArrayList<>();
        for (HubFaceConnectionPartitioner.CellMember m : cell.members()) {
            if (!isEligibleMember(m, paths)) continue;
            EligibleMember em = describeMember(m, paths);
            if (em != null) eligible.add(em);
        }
        if (eligible.size() < MIN_PARALLEL_COUNT) return List.of();

        // Group by current perpendicular coord (1px tolerance, rounded to integer).
        Map<Integer, List<EligibleMember>> groups = new LinkedHashMap<>();
        for (EligibleMember em : eligible) {
            int key = (int) Math.round(em.currentCoord);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(em);
        }

        EdgeAttachmentCalculator.Face face = cell.face();
        RoutingRect hub = cell.hub();
        int direction = shiftDirection(face);

        List<SpreadProposal> proposals = new ArrayList<>();
        for (Map.Entry<Integer, List<EligibleMember>> entry : groups.entrySet()) {
            List<EligibleMember> group = entry.getValue();
            if (group.size() < MIN_PARALLEL_COUNT) continue;

            int baseCoord = entry.getKey();
            int availableChannel = computeAvailableChannel(hub, face, baseCoord,
                    group, allObstacles);
            int spread = Math.min(MIN_SPREAD_PX, availableChannel / group.size());
            if (spread < 1) continue;

            // Sort by parallel midpoint for deterministic assignment.
            group.sort(Comparator.comparingInt(em -> em.parallelMidpoint));

            for (int i = 0; i < group.size(); i++) {
                EligibleMember em = group.get(i);
                int newCoord = baseCoord + direction * spread * i;
                proposals.add(new SpreadProposal(em.member.connectionId(),
                        em.member.pathIndex(), em.member.segmentStartBpIdx(),
                        em.horizontal, em.currentIntCoord, newCoord));
            }
        }
        return proposals;
    }

    /**
     * Apply a proposal in place; return a snapshot suitable for {@link #restore}.
     */
    public Snapshot apply(SpreadProposal proposal, List<List<AbsoluteBendpointDto>> paths) {
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
    // internals
    // =====================================================================

    /** Pre-computed description of one cell member for the spread allocation pass. */
    private static final class EligibleMember {
        final HubFaceConnectionPartitioner.CellMember member;
        final boolean horizontal;
        final double currentCoord;
        final int currentIntCoord;
        final int parallelMidpoint;

        EligibleMember(HubFaceConnectionPartitioner.CellMember member, boolean horizontal,
                       double currentCoord, int parallelMidpoint) {
            this.member = member;
            this.horizontal = horizontal;
            this.currentCoord = currentCoord;
            this.currentIntCoord = (int) Math.round(currentCoord);
            this.parallelMidpoint = parallelMidpoint;
        }
    }

    private static boolean isEligibleMember(
            HubFaceConnectionPartitioner.CellMember m,
            List<List<AbsoluteBendpointDto>> paths) {
        if (m.pathIndex() < 0 || m.pathIndex() >= paths.size()) return false;
        List<AbsoluteBendpointDto> path = paths.get(m.pathIndex());
        if (path == null || path.size() < 2) return false;
        int lastIdx = path.size() - 1;
        // TerminalAnchoring exemption: segment must NOT touch a terminal bp.
        return m.segmentStartBpIdx() > 0 && m.segmentEndBpIdx() < lastIdx;
    }

    private static EligibleMember describeMember(
            HubFaceConnectionPartitioner.CellMember m,
            List<List<AbsoluteBendpointDto>> paths) {
        List<AbsoluteBendpointDto> path = paths.get(m.pathIndex());
        AbsoluteBendpointDto bp1 = path.get(m.segmentStartBpIdx());
        AbsoluteBendpointDto bp2 = path.get(m.segmentEndBpIdx());
        boolean horizontal = bp1.y() == bp2.y();
        double currentCoord = horizontal ? bp1.y() : bp1.x();
        int parallelMidpoint = horizontal
                ? (bp1.x() + bp2.x()) / 2
                : (bp1.y() + bp2.y()) / 2;
        return new EligibleMember(m, horizontal, currentCoord, parallelMidpoint);
    }

    /**
     * Compute the perpendicular channel width available for spread away from the hub
     * face starting at {@code baseCoord}.
     */
    private static int computeAvailableChannel(
            RoutingRect hub,
            EdgeAttachmentCalculator.Face face,
            int baseCoord,
            List<EligibleMember> group,
            List<RoutingRect> allObstacles) {
        if (allObstacles == null || allObstacles.isEmpty()) return UNBOUNDED_CHANNEL_PX;
        int direction = shiftDirection(face);
        boolean horizontal = !group.isEmpty() && group.get(0).horizontal;

        int segLo = Integer.MAX_VALUE;
        int segHi = Integer.MIN_VALUE;
        for (EligibleMember em : group) {
            int mid = em.parallelMidpoint;
            segLo = Math.min(segLo, mid);
            segHi = Math.max(segHi, mid);
        }
        // Pad parallel range by spread reach so we catch obstacles that ANY member would brush.
        segLo -= MIN_SPREAD_PX;
        segHi += MIN_SPREAD_PX;

        int nearestObstacleEdge = direction > 0 ? Integer.MAX_VALUE : Integer.MIN_VALUE;
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
            // Only count obstacles in the away direction from baseCoord.
            if (direction < 0 && obsPerpHi < baseCoord) {
                nearestObstacleEdge = Math.max(nearestObstacleEdge, obsPerpHi);
            } else if (direction > 0 && obsPerpLo > baseCoord) {
                nearestObstacleEdge = Math.min(nearestObstacleEdge, obsPerpLo);
            }
        }
        if (direction < 0 && nearestObstacleEdge == Integer.MIN_VALUE) return UNBOUNDED_CHANNEL_PX;
        if (direction > 0 && nearestObstacleEdge == Integer.MAX_VALUE) return UNBOUNDED_CHANNEL_PX;
        double clear = NON_HUB_OBSTACLE_CLEARANCE_PX;
        int width = direction < 0
                ? (int) (baseCoord - nearestObstacleEdge - clear)
                : (int) (nearestObstacleEdge - baseCoord - clear);
        return Math.max(0, width);
    }

    private static int shiftDirection(EdgeAttachmentCalculator.Face face) {
        return switch (face) {
            case TOP, LEFT -> -1;
            case BOTTOM, RIGHT -> +1;
        };
    }
}
