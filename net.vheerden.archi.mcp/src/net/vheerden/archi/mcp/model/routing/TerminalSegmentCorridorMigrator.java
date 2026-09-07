package net.vheerden.archi.mcp.model.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.model.routing.EdgeAttachmentCalculator.Face;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Axis 3 of the hub-perimeter stage — the terminal-segment corridor-migration primitive.
 *
 * <p><b>Why this exists (the gap hub-perimeter routing cannot reach).</b> The shipped
 * primitives ({@link AlternativeCorridorSelector} / {@link CorridorSpreadEnforcer},
 * orchestrated by {@link HubPerimeterRoutingStage}) operate only on
 * <em>non-terminal-incident</em> segments — {@link HubFaceConnectionPartitioner}'s
 * cell loop bound is {@code for (s = 1; s < lastIdx - 1; s++)}, a correct perimeter-terminal-immutability
 * {@code TerminalAnchoring.preservesEndpoints} invariant for those generic
 * perpendicular-shift primitives. On the v1.4 HH/ST gate views 25 of 30 routed
 * paths are size-3 L-shapes whose <em>both</em> segments are terminal-incident, so
 * the partitioner emits zero cell members and the stage is a documented structural no-op
 * there. The M4 ({@code connectionEdgeCoincidence}) / V_p10 violators that block ST
 * agent-in-loop strict-PASS parity <em>are</em> those excluded terminal-incident
 * segments. This sibling reaches exactly that sub-case.
 *
 * <p><b>The perimeter-terminal immutability reachability key.</b> The perimeter-terminal immutability
 * contract {@link TerminalAnchoring#preservesTerminalAnchoring} is <em>not</em>
 * "a terminal bendpoint may not move" — it is precisely "the terminal bendpoint's
 * <em>orthogonal-axis</em> coordinate must stay exactly on the face line"
 * ({@code bp0Orthogonal == before.lineCoordinate(source)}). It places <b>no
 * constraint</b> on the terminal bendpoint's <em>parallel/slot</em> coordinate
 * (the Javadoc explicitly preserves "legitimate distributed slots"). Sliding a
 * terminal bendpoint <em>along</em> its face — changing only the slot coordinate
 * while the orthogonal coordinate stays pinned to the face line and the slot stays
 * within the face extent — is explicitly preserved behaviour, not a violation.
 *
 * <p><b>SAFE vs UNSAFE (spike §5 — the sibling's own guard logic).</b>
 * <ul>
 *   <li><b>SAFE</b> — the violating terminal segment runs <em>perpendicular</em> to
 *       the anchored face (its constant axis is the terminal's parallel/slot axis).
 *       Migrating shifts {@code {terminal bp slot, adjacent interior corner}} by the
 *       same delta on the slot axis; the orthogonal coordinate is untouched, so
 *       {@code preservesTerminalAnchoring} stays TRUE by construction.</li>
 *   <li><b>UNSAFE</b> — the violating segment's constant axis is the terminal's
 *       <em>orthogonal</em> axis (shifting it would drag the terminal off the face
 *       line). The migrator <b>rejects</b> these and leaves them to
 *       {@code EdgeAttachmentCalculator}'s domain — the role boundary is preserved,
 *       not crossed.</li>
 * </ul>
 *
 * <p><b>Atomic-swap discipline.</b> This is a NEW sibling. It does NOT route
 * through {@link HubFaceConnectionPartitioner}'s non-terminal cell machinery (its
 * {@code s = 1; s < lastIdx - 1} loop bound is <b>not</b> relaxed and stays a
 * correct perimeter-terminal-immutability invariant for the non-terminal primitives). It does its own
 * terminal-incident discovery, calls {@link TerminalAnchoring#preservesTerminalAnchoring}
 * only as a read-only contract guard (no mutation of {@code TerminalAnchoring}),
 * and never re-enters {@code EdgeAttachmentCalculator}, {@code VisibilityGraphRouter},
 * {@code PathStraightener}, {@code CorridorOccupancyTracker},
 * {@code ChannelNudgingPass}, or {@code LayoutQualityAssessor}. Composed into the
 * already-shipped {@link HubPerimeterRoutingStage} (post-EdgeAttachmentCalculator,
 * pre-PathStraightener), wrapped by the existing
 * {@link HubPerimeterRoutingStage#verifyMetricMonotonicity} (M4 / V_p10 / HPQ
 * rollback) — its own {@code preservesTerminalAnchoring} + Tier-1 self-check are
 * the additional per-proposal guards this primitive carries.
 *
 * <p><b>MVP scope (spike §2 "the dominant sub-case on the gate views"): size-3
 * L-shapes only.</b> Path {@code [t0, c, t1]} — exactly one shared interior corner,
 * both segments terminal-incident, a clean orthogonal L. This keeps the Tier-1
 * reasoning airtight (no co-moved corner can introduce a zigzag on a 2-segment
 * path). Larger paths (size 4/5 = 5/30 of gate-view paths) retain interior
 * segments the existing hub-perimeter primitives can already reach and are deliberately out
 * of MVP scope. On tight/saturated faces where no clearing slot exists within the
 * face extent the migrator emits no proposal — i.e. it degrades to a no-op rather
 * than a perimeter-terminal-immutability/role-boundary violation: at worst it empirically behaves
 * like the no-op fallback, and the monotonicity guard handles tight geometry safely — never
 * worse than {@code main}.
 *
 * <p><b>Pure geometry.</b> No EMF, no SWT, no PDE. {@link #apply} mutates the
 * caller-supplied bendpoint lists in place; {@link #restore} reverts via the
 * returned {@link Snapshot}. Callable from standard JUnit tests without OSGi.
 *
 * @see HubPerimeterRoutingStage
 * @see TerminalAnchoring
 * @see AlternativeCorridorSelector
 * @see CorridorSpreadEnforcer
 */
public class TerminalSegmentCorridorMigrator {

    // ----- Per-metric observer constants (lightweight mirror) ------------------
    // Inlined from net.vheerden.archi.mcp.model.LayoutQualityAssessor because the
    // assessor is package-private and elevating its visibility is forbidden (same
    // discipline + drift-acknowledgement as HubPerimeterRoutingStage). The
    // mirror-helper drift guard is the 5-pin contract + the verifier tests.

    /** Mirror of {@code LayoutQualityAssessor.EDGE_COINCIDENCE_TOLERANCE_PX} (3.0). */
    static final double EDGE_COINCIDENCE_TOLERANCE_PX = 3.0;

    /** Mirror of {@code LayoutQualityAssessor.EDGE_COINCIDENCE_MIN_OVERLAP_PX} (10.0). */
    static final double EDGE_COINCIDENCE_MIN_OVERLAP_PX = 10.0;

    /** Mirror of {@code LayoutQualityAssessor.ZIGZAG_AXIS_TOLERANCE_PX} (1.0). */
    static final double ZIGZAG_AXIS_TOLERANCE_PX = 1.0;

    /**
     * Safety margin (px) beyond the M4 tolerance that the migrated slot must clear
     * every overlapping element edge by. Mirrors the spirit of
     * {@link HubFaceConnectionPartitioner#PROXIMITY_TOLERANCE_PX} ("a 4px gap today
     * is a 2px gap tomorrow after a different upstream change") — clearing only to
     * the bare M4 trigger boundary risks an immediate re-trip after a later pass.
     */
    static final double CLEAR_SAFETY_MARGIN_PX = 2.0;

    /**
     * A proposed slot-axis migration of one terminal-incident segment of a size-3
     * L-shape. {@link #apply} shifts BOTH the terminal bendpoint and its adjacent
     * interior corner to {@code newSlot} on the slot axis, keeping the terminal's
     * orthogonal coordinate (the face line) untouched.
     *
     * @param connectionId  the connection id
     * @param pathIndex     index into the parent index-parallel paths list
     * @param terminalBpIdx terminal bendpoint index (0 = source, lastIdx = target)
     * @param cornerBpIdx   adjacent interior corner index (1 = source, lastIdx-1 = target)
     * @param shiftX        true if the slot axis is X (TOP/BOTTOM face), false if Y (LEFT/RIGHT face)
     * @param oldSlot       the terminal segment's current slot-axis coordinate
     * @param newSlot       the proposed slot-axis coordinate after migration
     */
    public record MigrationProposal(String connectionId,
                                    int pathIndex,
                                    int terminalBpIdx,
                                    int cornerBpIdx,
                                    boolean shiftX,
                                    int oldSlot,
                                    int newSlot) {}

    /**
     * Snapshot of pre-apply terminal + corner bendpoint state, used by
     * {@link #restore} for the orchestrator's metric-monotonicity rollback.
     */
    public record Snapshot(int pathIndex,
                           int terminalBpIdx,
                           int cornerBpIdx,
                           AbsoluteBendpointDto oldTerminal,
                           AbsoluteBendpointDto oldCorner) {}

    /**
     * Evaluate terminal-segment migration proposals across all connections. Emits
     * at most ONE proposal per connection (source-incident preferred, then
     * target-incident) so that the shared interior corner of a size-3 L is never
     * double-moved within a single pass.
     *
     * @param connections  index-parallel per-route endpoint records (source/target rects)
     * @param paths        index-parallel per-route bendpoint lists (NOT mutated by this method)
     * @param allObstacles every non-container view object — elements, notes and images alike
     * @return one proposal per viable connection; empty if none qualify
     */
    public List<MigrationProposal> evaluate(
            List<RoutingPipeline.ConnectionEndpoints> connections,
            List<List<AbsoluteBendpointDto>> paths,
            List<RoutingRect> allObstacles) {
        if (connections == null || paths == null || connections.size() != paths.size()) {
            return List.of();
        }
        List<RoutingRect> obstacles = allObstacles != null ? allObstacles : List.of();
        List<MigrationProposal> proposals = new ArrayList<>();
        for (int i = 0; i < connections.size(); i++) {
            RoutingPipeline.ConnectionEndpoints conn = connections.get(i);
            List<AbsoluteBendpointDto> path = paths.get(i);
            // MVP scope: size-3 L-shapes only (spike §2/§6 dominant gate-view sub-case).
            if (conn == null || path == null || path.size() != 3) continue;
            MigrationProposal p = tryTerminal(i, conn, path, obstacles, true);
            if (p == null) {
                p = tryTerminal(i, conn, path, obstacles, false);
            }
            if (p != null) proposals.add(p);
        }
        return proposals;
    }

    /**
     * Apply a proposal in place; return a snapshot suitable for {@link #restore}.
     * Shifts the terminal bendpoint AND its adjacent interior corner to
     * {@code newSlot} on the slot axis; the orthogonal (face-line) coordinate is
     * preserved by construction.
     */
    public Snapshot apply(MigrationProposal proposal, List<List<AbsoluteBendpointDto>> paths) {
        if (proposal == null || paths == null
                || proposal.pathIndex() < 0 || proposal.pathIndex() >= paths.size()) {
            return null;
        }
        List<AbsoluteBendpointDto> path = paths.get(proposal.pathIndex());
        if (path == null) return null;
        int ti = proposal.terminalBpIdx();
        int ci = proposal.cornerBpIdx();
        if (ti < 0 || ti >= path.size() || ci < 0 || ci >= path.size()) return null;
        AbsoluteBendpointDto oldT = path.get(ti);
        AbsoluteBendpointDto oldC = path.get(ci);
        int n = proposal.newSlot();
        AbsoluteBendpointDto newT = proposal.shiftX()
                ? new AbsoluteBendpointDto(n, oldT.y())
                : new AbsoluteBendpointDto(oldT.x(), n);
        AbsoluteBendpointDto newC = proposal.shiftX()
                ? new AbsoluteBendpointDto(n, oldC.y())
                : new AbsoluteBendpointDto(oldC.x(), n);
        path.set(ti, newT);
        path.set(ci, newC);
        return new Snapshot(proposal.pathIndex(), ti, ci, oldT, oldC);
    }

    /**
     * Revert a previously-applied proposal using its snapshot (orchestrator
     * metric-monotonicity rollback hook).
     */
    public void restore(Snapshot snapshot, List<List<AbsoluteBendpointDto>> paths) {
        if (snapshot == null || paths == null
                || snapshot.pathIndex() < 0 || snapshot.pathIndex() >= paths.size()) {
            return;
        }
        List<AbsoluteBendpointDto> path = paths.get(snapshot.pathIndex());
        if (path == null) return;
        if (snapshot.terminalBpIdx() >= 0 && snapshot.terminalBpIdx() < path.size()) {
            path.set(snapshot.terminalBpIdx(), snapshot.oldTerminal());
        }
        if (snapshot.cornerBpIdx() >= 0 && snapshot.cornerBpIdx() < path.size()) {
            path.set(snapshot.cornerBpIdx(), snapshot.oldCorner());
        }
    }

    // =====================================================================
    // internals — one (connection, terminal-side) evaluation
    // =====================================================================

    private MigrationProposal tryTerminal(int pathIndex,
                                          RoutingPipeline.ConnectionEndpoints conn,
                                          List<AbsoluteBendpointDto> path,
                                          List<RoutingRect> allObstacles,
                                          boolean sourceSide) {
        int lastIdx = path.size() - 1;                  // 2 for size-3
        int terminalBpIdx = sourceSide ? 0 : lastIdx;
        int cornerBpIdx = sourceSide ? 1 : lastIdx - 1; // = 1 either way for size-3
        RoutingRect elem = sourceSide ? conn.source() : conn.target();
        if (elem == null) return null;
        AbsoluteBendpointDto term = path.get(terminalBpIdx);
        AbsoluteBendpointDto corner = path.get(cornerBpIdx);

        Face face = inferAttachedFace(term, elem);
        if (face == null) return null; // not exactly on a perimeter-terminal-immutability face line → EdgeAttachmentCalculator's domain
        TerminalAnchoring anchoring = new TerminalAnchoring(face);
        boolean slotIsY = anchoring.parallelAxis() == TerminalAnchoring.Axis.Y;

        // Terminal segment = term -> corner. SAFE iff its CONSTANT axis is the
        // slot/parallel axis (i.e. it is the perpendicular-exit from the face).
        int dxSeg = corner.x() - term.x();
        int dySeg = corner.y() - term.y();
        boolean segHorizontal = Math.abs(dySeg) <= ZIGZAG_AXIS_TOLERANCE_PX
                && Math.abs(dxSeg) > ZIGZAG_AXIS_TOLERANCE_PX;
        boolean segVertical = Math.abs(dxSeg) <= ZIGZAG_AXIS_TOLERANCE_PX
                && Math.abs(dySeg) > ZIGZAG_AXIS_TOLERANCE_PX;
        if (!segHorizontal && !segVertical) return null;       // diagonal / degenerate
        boolean constantAxisIsY = segHorizontal;               // horizontal → constant Y
        if (constantAxisIsY != slotIsY) return null;           // UNSAFE: constant axis == orthogonal axis

        int curSlot = slotIsY ? term.y() : term.x();

        // Along-segment span on the non-slot axis (for parallel-overlap test).
        int segLo;
        int segHi;
        if (slotIsY) {
            segLo = Math.min(term.x(), corner.x());
            segHi = Math.max(term.x(), corner.x());
        } else {
            segLo = Math.min(term.y(), corner.y());
            segHi = Math.max(term.y(), corner.y());
        }

        List<Integer> overlappingEdges = collectOverlappingEdges(slotIsY, segLo, segHi, allObstacles);
        boolean hugs = false;
        for (int e : overlappingEdges) {
            if (Math.abs((double) curSlot - e) <= EDGE_COINCIDENCE_TOLERANCE_PX) {
                hugs = true;
                break;
            }
        }
        if (!hugs) return null; // this terminal segment is not an M4 violator

        // Legitimate slot range = the element's parallel extent on the face.
        int faceLo;
        int faceHi;
        if (slotIsY) {
            faceLo = elem.y();
            faceHi = elem.y() + elem.height();
        } else {
            faceLo = elem.x();
            faceHi = elem.x() + elem.width();
        }

        Integer newSlot = findClearingSlot(curSlot, overlappingEdges, faceLo, faceHi);
        if (newSlot == null) return null; // tight/saturated face → degrade to no-op (spike §2)

        boolean shiftX = !slotIsY;
        List<AbsoluteBendpointDto> after = new ArrayList<>(path);
        after.set(terminalBpIdx, shiftX
                ? new AbsoluteBendpointDto(newSlot, term.y())
                : new AbsoluteBendpointDto(term.x(), newSlot));
        after.set(cornerBpIdx, shiftX
                ? new AbsoluteBendpointDto(newSlot, corner.y())
                : new AbsoluteBendpointDto(corner.x(), newSlot));

        // Guard (i): explicit perimeter-terminal immutability contract — read-only call, no mutation of TerminalAnchoring.
        int[] center = {elem.centerX(), elem.centerY()};
        List<AbsoluteBendpointDto> oriented = after;
        if (!sourceSide) {
            oriented = new ArrayList<>(after);
            Collections.reverse(oriented);
        }
        if (!TerminalAnchoring.preservesTerminalAnchoring(anchoring, elem, oriented)) {
            return null;
        }

        // Guard (ii): Tier-1 self-check — clean orthogonal L preserved + no passthrough introduced.
        if (!tier1Clean(after, conn, allObstacles)) return null;

        return new MigrationProposal(conn.connectionId(), pathIndex,
                terminalBpIdx, cornerBpIdx, shiftX, curSlot, newSlot);
    }

    /**
     * Infer which element face the terminal bendpoint is anchored on, using
     * <em>exact</em> equality to {@link TerminalAnchoring#lineCoordinate} — the
     * same exact {@code ==} test the perimeter-terminal immutability predicate itself applies. A terminal not
     * exactly on a perimeter-terminal-immutability face line is, by definition, not this primitive's domain
     * (it belongs to {@code EdgeAttachmentCalculator}); returning {@code null}
     * preserves that role boundary.
     */
    static Face inferAttachedFace(AbsoluteBendpointDto bp, RoutingRect elem) {
        if (bp == null || elem == null) return null;
        for (Face f : Face.values()) {
            TerminalAnchoring ta = new TerminalAnchoring(f);
            int line = ta.lineCoordinate(elem);
            int orth = (ta.orthogonalAxis() == TerminalAnchoring.Axis.X) ? bp.x() : bp.y();
            if (orth != line) continue;
            int par = (ta.parallelAxis() == TerminalAnchoring.Axis.X) ? bp.x() : bp.y();
            int parLo;
            int parHi;
            if (ta.parallelAxis() == TerminalAnchoring.Axis.X) {
                parLo = elem.x();
                parHi = elem.x() + elem.width();
            } else {
                parLo = elem.y();
                parHi = elem.y() + elem.height();
            }
            if (par >= parLo && par <= parHi) return f;
        }
        return null;
    }

    /**
     * Collect the perpendicular coordinates of every element edge the terminal
     * segment's parallel span overlaps by at least
     * {@link #EDGE_COINCIDENCE_MIN_OVERLAP_PX}. Mirrors
     * {@code HubPerimeterRoutingStage.segmentHugs*Edge} parallel-overlap semantics.
     *
     * @param slotIsY true if the segment is horizontal (slot = Y, candidate edges
     *                are obstacle top/bottom Y); false if vertical (slot = X,
     *                candidate edges are obstacle left/right X)
     * @param segLo   along-axis span low
     * @param segHi   along-axis span high
     */
    private static List<Integer> collectOverlappingEdges(boolean slotIsY,
                                                         int segLo, int segHi,
                                                         List<RoutingRect> allObstacles) {
        List<Integer> edges = new ArrayList<>();
        for (RoutingRect obs : allObstacles) {
            if (obs == null) continue;
            int obsLo;
            int obsHi;
            int edgeA;
            int edgeB;
            if (slotIsY) { // horizontal segment: obstacle X-extent vs seg X-span; edges = Y
                obsLo = obs.x();
                obsHi = obs.x() + obs.width();
                edgeA = obs.y();
                edgeB = obs.y() + obs.height();
            } else {       // vertical segment: obstacle Y-extent vs seg Y-span; edges = X
                obsLo = obs.y();
                obsHi = obs.y() + obs.height();
                edgeA = obs.x();
                edgeB = obs.x() + obs.width();
            }
            double overlap = Math.min(segHi, obsHi) - Math.max(segLo, obsLo);
            if (overlap < EDGE_COINCIDENCE_MIN_OVERLAP_PX) continue;
            edges.add(edgeA);
            edges.add(edgeB);
        }
        return edges;
    }

    /**
     * Find the slot-axis coordinate nearest {@code curSlot}, strictly within the
     * face extent {@code [faceLo, faceHi]}, that clears every overlapping element
     * edge by at least {@code EDGE_COINCIDENCE_TOLERANCE_PX + CLEAR_SAFETY_MARGIN_PX}.
     * Searches symmetrically outward (smallest displacement first, deterministic).
     * Returns {@code null} when no such slot exists within the face extent — the
     * tight/saturated-face case the migrator degrades to a no-op on (spike §2).
     */
    static Integer findClearingSlot(int curSlot, List<Integer> edges,
                                    int faceLo, int faceHi) {
        if (faceHi < faceLo) return null;
        double required = EDGE_COINCIDENCE_TOLERANCE_PX + CLEAR_SAFETY_MARGIN_PX;
        int span = faceHi - faceLo;
        for (int off = 1; off <= span; off++) {
            int down = curSlot - off;
            if (down >= faceLo && down <= faceHi && clears(down, edges, required)) return down;
            int up = curSlot + off;
            if (up >= faceLo && up <= faceHi && clears(up, edges, required)) return up;
        }
        return null;
    }

    private static boolean clears(int v, List<Integer> edges, double required) {
        for (int e : edges) {
            if (Math.abs((double) v - e) < required) return false;
        }
        return true;
    }

    /**
     * Tier-1 self-check (spike §7 guard iv). The orchestrator's
     * {@code verifyMetricMonotonicity} covers M4 / V_p10 / HPQ only; this primitive
     * moves a terminal bendpoint so it must carry its OWN Tier-1 protection:
     * <ol>
     *   <li>both segments of the size-3 L stay axis-aligned (no zigzag introduced); and</li>
     *   <li>no segment strictly passes through the interior of any obstacle that is
     *       not this connection's own source/target element (a terminal segment
     *       legitimately abuts its own element's face — that is not a passthrough).</li>
     * </ol>
     */
    private static boolean tier1Clean(List<AbsoluteBendpointDto> after,
                                      RoutingPipeline.ConnectionEndpoints conn,
                                      List<RoutingRect> allObstacles) {
        for (int i = 0; i < after.size() - 1; i++) {
            AbsoluteBendpointDto a = after.get(i);
            AbsoluteBendpointDto b = after.get(i + 1);
            double dx = Math.abs((double) b.x() - a.x());
            double dy = Math.abs((double) b.y() - a.y());
            boolean horizontal = dy <= ZIGZAG_AXIS_TOLERANCE_PX && dx > ZIGZAG_AXIS_TOLERANCE_PX;
            boolean vertical = dx <= ZIGZAG_AXIS_TOLERANCE_PX && dy > ZIGZAG_AXIS_TOLERANCE_PX;
            if (!horizontal && !vertical) return false; // zigzag/diagonal introduced
            for (RoutingRect obs : allObstacles) {
                if (obs == null) continue;
                if (isOwnEndpoint(obs, conn)) continue;
                if (segmentStrictlyThroughInterior(a, b, horizontal, obs)) return false;
            }
        }
        return true;
    }

    private static boolean isOwnEndpoint(RoutingRect obs, RoutingPipeline.ConnectionEndpoints conn) {
        if (obs.id() == null) return false;
        if (conn.source() != null && obs.id().equals(conn.source().id())) return true;
        return conn.target() != null && obs.id().equals(conn.target().id());
    }

    /**
     * Strict axis-aligned segment-vs-rect-interior test. "Strictly through" uses
     * open inequalities so a segment merely grazing an edge (an M4 concern the
     * monotonicity verifier owns) is NOT counted here as a Tier-1 passthrough.
     */
    private static boolean segmentStrictlyThroughInterior(AbsoluteBendpointDto a,
                                                          AbsoluteBendpointDto b,
                                                          boolean horizontal,
                                                          RoutingRect obs) {
        double left = obs.x();
        double right = obs.x() + obs.width();
        double top = obs.y();
        double bottom = obs.y() + obs.height();
        if (horizontal) {
            double y = (a.y() + b.y()) / 2.0;
            if (y <= top || y >= bottom) return false;
            double lo = Math.min(a.x(), b.x());
            double hi = Math.max(a.x(), b.x());
            return Math.min(hi, right) - Math.max(lo, left) > 0.0
                    && hi > left && lo < right;
        }
        double x = (a.x() + b.x()) / 2.0;
        if (x <= left || x >= right) return false;
        double lo = Math.min(a.y(), b.y());
        double hi = Math.max(a.y(), b.y());
        return Math.min(hi, bottom) - Math.max(lo, top) > 0.0
                && hi > top && lo < bottom;
    }
}
