package net.vheerden.archi.mcp.model.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * H5 story — orchestration stage owning hub-perimeter routing quality as a 2-axis
 * simultaneous property (corridor-CHOICE diversification + intra-corridor SPREAD
 * enforcement).
 *
 * <p><b>Architectural commitment (load-bearing):</b> routing quality is a global
 * property of the route set, not a local property of individual edges or connections.
 * This stage extends that commitment from {@link ChannelNudgingPass} (channel-global
 * nudging) to a NEW orchestration owning <em>hub-perimeter</em> quality. The 2-axis
 * defect was discovered analytically in the predecessor scoping spike after the H1-H4
 * single-axis hypotheses were falsified or refuted; H5 is the
 * coordinated abstraction combining the design insights.
 *
 * <p><b>Insertion point:</b> {@link RoutingPipeline#routeAllConnections} between
 * {@link ChannelNudgingPass} and {@link PathStraightener}. Single constructor field +
 * one invocation line. All upstream stages and {@code LayoutQualityAssessor}
 * are untouched per atomic-swap discipline.
 *
 * <p><b>Composition (sibling-API discipline):</b>
 * <ul>
 *   <li>{@link HubFaceConnectionPartitioner} — assembles per-(hub, face) cell route-sets.
 *       Local degree-counter for hub detection.</li>
 *   <li>{@link AlternativeCorridorSelector} — Axis 1 corridor-CHOICE: shifts segments
 *       AWAY from hub-face proximity within a length-cost-premium budget.</li>
 *   <li>{@link CorridorSpreadEnforcer} — Axis 2 intra-corridor SPREAD: distributes
 *       same-coord clusters via localised track allocation. Runs ALONGSIDE
 *       {@code ChannelNudgingPass}, NEVER mutates it.</li>
 * </ul>
 *
 * <p><b>Two-pass orchestration:</b> for each cell, Axis 1 runs first (clearing M4
 * face-hugging), then Axis 2 reads the now-mutated paths and spreads any remaining
 * zero-spread clusters. The orchestrator never re-partitions — original cell membership
 * carries through both passes.
 *
 * <p><b>Metric-monotonicity verifier.</b> Each proposal
 * is wrapped by {@link #verifyMetricMonotonicity}, a lightweight before/after monotonicity
 * check on M4 (connection-edge-coincidence count), V_p10 (V-axis parallel-connection-gap
 * 10th percentile) and HPQ (hub-port-quality aggregate). Tier-1 defects (overlap,
 * passThrough, interiorTermination, zigzag) are protected by construction — H5 only
 * shifts intermediate bendpoints (loop bound {@code s = 1..lastIdx-2} in
 * {@link HubFaceConnectionPartitioner#partition}), never moves terminal endpoints.
 * The verifier mirrors the relevant
 * subset of {@code LayoutQualityAssessor} logic locally — the assessor stays
 * untouched and its visibility cannot be elevated, so its operative constants
 * ({@code EDGE_COINCIDENCE_*}, {@code PARALLEL_GAP_AXIS_TOLERANCE_PX},
 * {@code M5_FACE_GUARD_MIN_CONNECTIONS}, {@code HUB_PORT_SLOT_TOLERANCE_PX},
 * {@code PERIMETER_TOLERANCE_PX}, {@code ZIGZAG_AXIS_TOLERANCE_PX}) are inlined here
 * with cross-reference Javadocs. The mirror-discipline tradeoff (potential drift) is
 * accepted to preserve the atomic-swap envelope.
 *
 * <p><b>Retry policy:</b> if Axis 1 produces no viable proposal at the strict
 * budget {@link AlternativeCorridorSelector#ALTERNATIVE_CORRIDOR_COST_PREMIUM}, the
 * orchestrator retries at {@link AlternativeCorridorSelector#ALTERNATIVE_CORRIDOR_COST_PREMIUM_MAX}.
 * If the relaxed retry also fails OR the verifier rolls back the apply, the connection
 * is left unchanged (monotonicity).
 *
 * <p><b>Pure geometry.</b> No EMF, no SWT, no PDE. Mutates caller-supplied bendpoint
 * lists in place.
 */
public class HubPerimeterRoutingStage {

    // ----- Per-metric observer constants (lightweight mirror) ---------
    // Inlined from net.vheerden.archi.mcp.model.LayoutQualityAssessor because the
    // assessor is package-private and elevating its visibility is forbidden. Drift
    // risk acknowledged; tradeoff
    // accepted to preserve the atomic-swap envelope.

    /** Mirror of {@code LayoutQualityAssessor.EDGE_COINCIDENCE_TOLERANCE_PX} (3.0). */
    private static final double EDGE_COINCIDENCE_TOLERANCE_PX = 3.0;

    /** Mirror of {@code LayoutQualityAssessor.EDGE_COINCIDENCE_MIN_OVERLAP_PX} (10.0). */
    private static final double EDGE_COINCIDENCE_MIN_OVERLAP_PX = 10.0;

    /** Mirror of {@code LayoutQualityAssessor.ZIGZAG_AXIS_TOLERANCE_PX} (1.0). */
    private static final double ZIGZAG_AXIS_TOLERANCE_PX = 1.0;

    /** Mirror of {@code LayoutQualityAssessor.PARALLEL_GAP_AXIS_TOLERANCE_PX} (2.0). */
    private static final double PARALLEL_GAP_AXIS_TOLERANCE_PX = 2.0;

    /** Mirror of {@code LayoutQualityAssessor.PERIMETER_TOLERANCE_PX} (0.5). */
    private static final double PERIMETER_TOLERANCE_PX = 0.5;

    /** Mirror of {@code LayoutQualityAssessor.M5_FACE_GUARD_MIN_CONNECTIONS} (4). */
    private static final int M5_FACE_GUARD_MIN_CONNECTIONS = 4;

    /** Mirror of {@code LayoutQualityAssessor.HUB_PORT_SLOT_TOLERANCE_PX} (1.0). */
    private static final double HUB_PORT_SLOT_TOLERANCE_PX = 1.0;

    private final HubFaceConnectionPartitioner partitioner;
    private final AlternativeCorridorSelector selector;
    private final CorridorSpreadEnforcer enforcer;

    /**
     * HPRPS Track-A: the terminal-segment corridor-migration sibling. Composed as a third
     * independent axis (see {@link #apply}). It reaches exactly the
     * terminal-incident size-3 L-shapes that {@link HubFaceConnectionPartitioner}
     * correctly excludes (its {@code s=1; s<lastIdx-1} bound is NOT relaxed) and on
     * which H5 is a documented structural no-op — the dominant gate-view geometry.
     */
    private final TerminalSegmentCorridorMigrator migrator;

    public HubPerimeterRoutingStage() {
        this(new HubFaceConnectionPartitioner(),
                new AlternativeCorridorSelector(),
                new CorridorSpreadEnforcer(),
                new TerminalSegmentCorridorMigrator());
    }

    /**
     * Backwards-compatible 3-arg test-injection constructor (pre-HPRPS). Delegates
     * to the 4-arg constructor with a default {@link TerminalSegmentCorridorMigrator}
     * so existing H5 test injection sites remain source-compatible.
     */
    HubPerimeterRoutingStage(HubFaceConnectionPartitioner partitioner,
                              AlternativeCorridorSelector selector,
                              CorridorSpreadEnforcer enforcer) {
        this(partitioner, selector, enforcer, new TerminalSegmentCorridorMigrator());
    }

    /** Constructor for test injection (HPRPS Track-A — includes the migrator). */
    HubPerimeterRoutingStage(HubFaceConnectionPartitioner partitioner,
                              AlternativeCorridorSelector selector,
                              CorridorSpreadEnforcer enforcer,
                              TerminalSegmentCorridorMigrator migrator) {
        this.partitioner = partitioner;
        this.selector = selector;
        this.enforcer = enforcer;
        this.migrator = migrator;
    }

    /**
     * Per-run aggregate stats for diagnostics + integration-test verification.
     *
     * @param cellsProcessed   number of (hub, face) cells partitioned
     * @param axis1Applied     Axis 1 corridor-choice corrections committed
     * @param axis1Rolled      Axis 1 corrections rolled back by the metric-monotonicity verifier
     * @param axis2Applied     Axis 2 spread corrections committed
     * @param axis2Rolled      Axis 2 corrections rolled back by the metric-monotonicity verifier
     * @param migratorApplied  HPRPS Track-A terminal-segment migrations committed
     * @param migratorRolled   HPRPS Track-A migrations rolled back by the metric-monotonicity verifier
     */
    public record Result(int cellsProcessed,
                         int axis1Applied,
                         int axis1Rolled,
                         int axis2Applied,
                         int axis2Rolled,
                         int migratorApplied,
                         int migratorRolled) {}

    /**
     * Pre-apply snapshot of M4 / V_p10 / HPQ consumed by {@link #verifyMetricMonotonicity}.
     *
     * @param m4Count   connectionEdgeCoincidenceCount mirror
     * @param vP10      V-axis parallelConnectionGap 10th percentile (boxed; {@code null}
     *                  when no qualifying V segment pairs exist)
     * @param hpq       hubPortQuality aggregate score (1.0 when no hub face exists)
     */
    public record MetricSnapshot(int m4Count, Double vP10, double hpq) {}

    /**
     * Apply the H5 algorithm + the HPRPS Track-A third axis: partition into cells,
     * run Axis 1 (corridor-CHOICE) with strict-then-relaxed retry, then Axis 2
     * (intra-corridor SPREAD), then Axis 3 ({@link TerminalSegmentCorridorMigrator}
     * — terminal-segment corridor migration), all with Tier-1 ceiling-preserved
     * rollback hooks.
     *
     * <p><b>HPRPS wiring note (load-bearing).</b> Axis 3 runs over ALL connections
     * independently of the partitioner cells and <b>regardless of whether any cell
     * exists</b>. The pre-HPRPS early-return on {@code cells.isEmpty()} was removed
     * precisely because the v1.4 HH/ST gate views produce zero cells (25/30 paths
     * are size-3 L-shapes whose terminal-incident segments the partitioner correctly
     * excludes) — that early-return is exactly what made H5 a structural no-op there.
     * Axis 3 is the reach into that geometry. Axis 1/2 (on empty cells the loop is a
     * natural no-op) still run first on hub-rich views; Axis 3 then reads the
     * now-mutated paths (third pass; never re-partitions) and its
     * {@link #verifyMetricMonotonicity} {@code pre}-snapshot captures the
     * post-Axis-1/2 state so Axis 3 can never regress what Axis 1/2 achieved.
     *
     * @param connections   index-parallel per-route endpoint records
     * @param paths         index-parallel per-route bendpoint lists (MUTATED in place)
     * @param allObstacles  all element rectangles on the view
     * @return aggregate stats for diagnostics; never null
     */
    public Result apply(List<RoutingPipeline.ConnectionEndpoints> connections,
                        List<List<AbsoluteBendpointDto>> paths,
                        List<RoutingRect> allObstacles) {
        if (connections == null || paths == null || connections.size() != paths.size()) {
            return new Result(0, 0, 0, 0, 0, 0, 0);
        }
        if (allObstacles == null) allObstacles = List.of();

        List<HubFaceConnectionPartitioner.HubFaceCell> cells =
                partitioner.partition(connections, paths, allObstacles);

        int axis1Applied = 0;
        int axis1Rolled = 0;
        int axis2Applied = 0;
        int axis2Rolled = 0;

        for (HubFaceConnectionPartitioner.HubFaceCell cell : cells) {
            // Axis 1: strict budget first, fall back to relaxed budget for unresolved members.
            List<AlternativeCorridorSelector.AlternativeProposal> axis1 = selector.evaluate(cell,
                    paths, allObstacles, AlternativeCorridorSelector.ALTERNATIVE_CORRIDOR_COST_PREMIUM);
            if (axis1.isEmpty()) {
                axis1 = selector.evaluate(cell, paths, allObstacles,
                        AlternativeCorridorSelector.ALTERNATIVE_CORRIDOR_COST_PREMIUM_MAX);
            }
            for (AlternativeCorridorSelector.AlternativeProposal p : axis1) {
                MetricSnapshot pre = computeMetricSnapshot(connections, paths, allObstacles);
                AlternativeCorridorSelector.Snapshot snap = selector.apply(p, paths);
                if (snap == null) continue;
                if (verifyMetricMonotonicity(pre, connections, paths, allObstacles)) {
                    axis1Applied++;
                } else {
                    selector.restore(snap, paths);
                    axis1Rolled++;
                }
            }

            // Axis 2: spread reads the NOW-MUTATED path state per the two-pass orchestration.
            List<CorridorSpreadEnforcer.SpreadProposal> axis2 = enforcer.evaluate(cell,
                    paths, allObstacles);
            for (CorridorSpreadEnforcer.SpreadProposal p : axis2) {
                MetricSnapshot pre = computeMetricSnapshot(connections, paths, allObstacles);
                CorridorSpreadEnforcer.Snapshot snap = enforcer.apply(p, paths);
                if (snap == null) continue;
                if (verifyMetricMonotonicity(pre, connections, paths, allObstacles)) {
                    axis2Applied++;
                } else {
                    enforcer.restore(snap, paths);
                    axis2Rolled++;
                }
            }
        }

        // Axis 3 (HPRPS Track-A): terminal-segment corridor migration. Runs over ALL
        // connections (its own terminal-incident discovery; does NOT route through the
        // partitioner cells and does NOT relax the partitioner loop bound) and reads
        // the post-Axis-1/2 path state. Each proposal is independently safe + Tier-1
        // -clean by construction (the migrator's own preservesTerminalAnchoring + Tier-1
        // self-checks gate emission); it is ADDITIONALLY wrapped by the SAME
        // verifyMetricMonotonicity (M4 / V_p10 / HPQ rollback).
        int migratorApplied = 0;
        int migratorRolled = 0;
        List<TerminalSegmentCorridorMigrator.MigrationProposal> migrations =
                migrator.evaluate(connections, paths, allObstacles);
        for (TerminalSegmentCorridorMigrator.MigrationProposal p : migrations) {
            MetricSnapshot pre = computeMetricSnapshot(connections, paths, allObstacles);
            TerminalSegmentCorridorMigrator.Snapshot snap = migrator.apply(p, paths);
            if (snap == null) continue;
            if (verifyMetricMonotonicity(pre, connections, paths, allObstacles)) {
                migratorApplied++;
            } else {
                migrator.restore(snap, paths);
                migratorRolled++;
            }
        }

        return new Result(cells.size(), axis1Applied, axis1Rolled, axis2Applied,
                axis2Rolled, migratorApplied, migratorRolled);
    }

    /**
     * Lightweight per-metric observer. Mirrors the subset of
     * {@code LayoutQualityAssessor} computations needed for monotonicity verification.
     * Allocation-bounded and dependency-free of the assessor itself.
     *
     * <p>Package-private for direct test access.
     */
    MetricSnapshot computeMetricSnapshot(
            List<RoutingPipeline.ConnectionEndpoints> connections,
            List<List<AbsoluteBendpointDto>> paths,
            List<RoutingRect> allObstacles) {
        return new MetricSnapshot(
                computeM4Count(paths, allObstacles),
                computeVAxisParallelGapP10(paths),
                computeHpq(connections, paths));
    }

    /**
     * Verify monotonicity on M4 / V_p10 / HPQ. Returns {@code true}
     * when no metric has regressed against {@code pre}; returns {@code false} otherwise
     * so the caller rolls the proposal back.
     *
     * <p><b>Scope:</b> covers M4, V_p10, HPQ only. Tier-1 defects (overlap, passThrough,
     * interiorTermination, zigzag) are protected by construction (see class Javadoc).
     *
     * <p>V_p10 null-handling:
     * <ul>
     *   <li>pre {@code null} + post {@code null} → no regression ({@code true})</li>
     *   <li>pre {@code null} + post value → improvement ({@code true})</li>
     *   <li>pre value + post {@code null} → regression ({@code false})</li>
     *   <li>pre value + post value: monotone when {@code post >= pre}</li>
     * </ul>
     *
     * <p>Package-private for direct test access.
     */
    boolean verifyMetricMonotonicity(MetricSnapshot pre,
                              List<RoutingPipeline.ConnectionEndpoints> connections,
                              List<List<AbsoluteBendpointDto>> paths,
                              List<RoutingRect> allObstacles) {
        if (pre == null) return true;
        MetricSnapshot post = computeMetricSnapshot(connections, paths, allObstacles);
        if (post.m4Count() > pre.m4Count()) return false;
        if (pre.vP10() != null) {
            if (post.vP10() == null || post.vP10() < pre.vP10()) return false;
        }
        if (post.hpq() < pre.hpq()) return false;
        return true;
    }

    // ---- M4: connection-edge-coincidence count -------------------------------

    /**
     * Mirrors {@code LayoutQualityAssessor.countConnectionEdgeCoincidence}. A connection
     * contributes at most {@code +1} to the count once any of its axis-aligned segments
     * "hugs" an element edge — segment perpendicular coord within
     * {@link #EDGE_COINCIDENCE_TOLERANCE_PX}px of an edge AND parallel-overlap with the
     * edge of at least {@link #EDGE_COINCIDENCE_MIN_OVERLAP_PX}px. Scans the full
     * obstacle list (including the connection's own source/target faces, matching
     * assessor semantics post-Story-M4.RemoveSelfExclusion 2026-04-27).
     */
    static int computeM4Count(List<List<AbsoluteBendpointDto>> paths,
                              List<RoutingRect> allObstacles) {
        if (paths == null || allObstacles == null) return 0;
        int count = 0;
        for (List<AbsoluteBendpointDto> path : paths) {
            if (path == null || path.size() < 2) continue;
            boolean flagged = false;
            for (int i = 0; i < path.size() - 1 && !flagged; i++) {
                AbsoluteBendpointDto s = path.get(i);
                AbsoluteBendpointDto e = path.get(i + 1);
                double sx = s.x(), sy = s.y(), ex = e.x(), ey = e.y();
                boolean horizontal = Math.abs(sy - ey) <= ZIGZAG_AXIS_TOLERANCE_PX
                        && Math.abs(sx - ex) > ZIGZAG_AXIS_TOLERANCE_PX;
                boolean vertical = Math.abs(sx - ex) <= ZIGZAG_AXIS_TOLERANCE_PX
                        && Math.abs(sy - ey) > ZIGZAG_AXIS_TOLERANCE_PX;
                if (!horizontal && !vertical) continue;
                for (RoutingRect elem : allObstacles) {
                    if (horizontal && segmentHugsHorizontalEdge(sx, sy, ex, ey, elem)) {
                        count++;
                        flagged = true;
                        break;
                    }
                    if (vertical && segmentHugsVerticalEdge(sx, sy, ex, ey, elem)) {
                        count++;
                        flagged = true;
                        break;
                    }
                }
            }
        }
        return count;
    }

    private static boolean segmentHugsHorizontalEdge(double sx, double sy, double ex, double ey,
                                                      RoutingRect elem) {
        double y = (sy + ey) / 2.0;
        double topEdge = elem.y();
        double bottomEdge = elem.y() + elem.height();
        boolean nearEdge = Math.abs(y - topEdge) <= EDGE_COINCIDENCE_TOLERANCE_PX
                || Math.abs(y - bottomEdge) <= EDGE_COINCIDENCE_TOLERANCE_PX;
        if (!nearEdge) return false;
        double segLeft = Math.min(sx, ex);
        double segRight = Math.max(sx, ex);
        double overlap = Math.min(segRight, elem.x() + elem.width()) - Math.max(segLeft, elem.x());
        return overlap >= EDGE_COINCIDENCE_MIN_OVERLAP_PX;
    }

    private static boolean segmentHugsVerticalEdge(double sx, double sy, double ex, double ey,
                                                    RoutingRect elem) {
        double x = (sx + ex) / 2.0;
        double leftEdge = elem.x();
        double rightEdge = elem.x() + elem.width();
        boolean nearEdge = Math.abs(x - leftEdge) <= EDGE_COINCIDENCE_TOLERANCE_PX
                || Math.abs(x - rightEdge) <= EDGE_COINCIDENCE_TOLERANCE_PX;
        if (!nearEdge) return false;
        double segTop = Math.min(sy, ey);
        double segBottom = Math.max(sy, ey);
        double overlap = Math.min(segBottom, elem.y() + elem.height()) - Math.max(segTop, elem.y());
        return overlap >= EDGE_COINCIDENCE_MIN_OVERLAP_PX;
    }

    // ---- V_p10: V-axis parallel-connection-gap 10th percentile ---------------

    /**
     * Mirrors the V-axis branch of {@code LayoutQualityAssessor.computeParallelConnectionGap}.
     * Extracts V segments ({@code |Δx| < tol AND |Δy| >= tol}), pairs each with the
     * nearest co-axial parallel segment having strict span overlap, and returns the
     * 10th-percentile gap via linear interpolation. Returns {@code null} when no
     * qualifying V segment pairs exist.
     */
    static Double computeVAxisParallelGapP10(List<List<AbsoluteBendpointDto>> paths) {
        if (paths == null) return null;
        // Per-segment record: fixedCoord (X), spanLow (Y), spanHigh (Y).
        List<double[]> vSegs = new ArrayList<>();
        for (List<AbsoluteBendpointDto> path : paths) {
            if (path == null || path.size() < 2) continue;
            for (int i = 0; i < path.size() - 1; i++) {
                AbsoluteBendpointDto p = path.get(i);
                AbsoluteBendpointDto q = path.get(i + 1);
                double dx = Math.abs(q.x() - p.x());
                double dy = Math.abs(q.y() - p.y());
                if (dx < PARALLEL_GAP_AXIS_TOLERANCE_PX && dy < PARALLEL_GAP_AXIS_TOLERANCE_PX) continue;
                if (dx < PARALLEL_GAP_AXIS_TOLERANCE_PX && dy >= PARALLEL_GAP_AXIS_TOLERANCE_PX) {
                    double fixedX = (p.x() + q.x()) / 2.0;
                    double spanLow = Math.min(p.y(), q.y());
                    double spanHigh = Math.max(p.y(), q.y());
                    vSegs.add(new double[] {fixedX, spanLow, spanHigh});
                }
                // diagonal + H pairs are out of scope for V-axis monotonicity check.
            }
        }
        if (vSegs.isEmpty()) return null;

        List<Double> gaps = new ArrayList<>();
        int n = vSegs.size();
        for (int i = 0; i < n; i++) {
            double[] s = vSegs.get(i);
            Double bestGap = null;
            for (int j = 0; j < n; j++) {
                if (j == i) continue;
                double[] s2 = vSegs.get(j);
                double overlap = Math.min(s[2], s2[2]) - Math.max(s[1], s2[1]);
                if (overlap <= 0.0) continue;
                double gap = (s[0] == s2[0]) ? 0.0 : Math.abs(s[0] - s2[0]);
                if (bestGap == null || gap < bestGap) bestGap = gap;
            }
            if (bestGap != null) gaps.add(bestGap);
        }
        if (gaps.isEmpty()) return null;
        return percentileP10(gaps);
    }

    /**
     * H-axis mirror of {@link #computeVAxisParallelGapP10}: nearest-neighbour parallel-gap p10
     * over HORIZONTAL connection segments (segments constant on Y), with the gap measured on the
     * Y axis. Used by {@link TerminalEgressClearancePass} (Option B, 2026-06-29) to gate a
     * horizontal-hug egress push on the correct (horizontal) parallel field — the V-axis stat
     * is the wrong axis for a horizontal hug. Same overlap/percentile convention as the V-axis
     * stat, so it adds no new assessor-mirror drift surface.
     */
    static Double computeHAxisParallelGapP10(List<List<AbsoluteBendpointDto>> paths) {
        if (paths == null) return null;
        // Per-segment record: fixedCoord (Y), spanLow (X), spanHigh (X).
        List<double[]> hSegs = new ArrayList<>();
        for (List<AbsoluteBendpointDto> path : paths) {
            if (path == null || path.size() < 2) continue;
            for (int i = 0; i < path.size() - 1; i++) {
                AbsoluteBendpointDto p = path.get(i);
                AbsoluteBendpointDto q = path.get(i + 1);
                double dx = Math.abs(q.x() - p.x());
                double dy = Math.abs(q.y() - p.y());
                if (dx < PARALLEL_GAP_AXIS_TOLERANCE_PX && dy < PARALLEL_GAP_AXIS_TOLERANCE_PX) continue;
                if (dy < PARALLEL_GAP_AXIS_TOLERANCE_PX && dx >= PARALLEL_GAP_AXIS_TOLERANCE_PX) {
                    double fixedY = (p.y() + q.y()) / 2.0;
                    double spanLow = Math.min(p.x(), q.x());
                    double spanHigh = Math.max(p.x(), q.x());
                    hSegs.add(new double[] {fixedY, spanLow, spanHigh});
                }
                // diagonal + V pairs are out of scope for the H-axis check.
            }
        }
        if (hSegs.isEmpty()) return null;

        List<Double> gaps = new ArrayList<>();
        int n = hSegs.size();
        for (int i = 0; i < n; i++) {
            double[] s = hSegs.get(i);
            Double bestGap = null;
            for (int j = 0; j < n; j++) {
                if (j == i) continue;
                double[] s2 = hSegs.get(j);
                double overlap = Math.min(s[2], s2[2]) - Math.max(s[1], s2[1]);
                if (overlap <= 0.0) continue;
                double gap = (s[0] == s2[0]) ? 0.0 : Math.abs(s[0] - s2[0]);
                if (bestGap == null || gap < bestGap) bestGap = gap;
            }
            if (bestGap != null) gaps.add(bestGap);
        }
        if (gaps.isEmpty()) return null;
        return percentileP10(gaps);
    }

    private static double percentileP10(List<Double> values) {
        List<Double> xs = new ArrayList<>(values);
        Collections.sort(xs);
        int n = xs.size();
        if (n == 1) return xs.get(0);
        double k = (n - 1) * 0.10;
        int f = (int) Math.floor(k);
        int c = Math.min(f + 1, n - 1);
        return xs.get(f) + (xs.get(c) - xs.get(f)) * (k - f);
    }

    // ---- HPQ: hub-port-quality aggregate -------------------------------------

    /**
     * Mirrors {@code LayoutQualityAssessor.computeHubPortQuality} (M5). For each
     * connection, attributes its source/target bendpoint to a face of the corresponding
     * element via {@link #inferTerminalSlot}. For multi-bp paths (size &gt; 2), uses the
     * interior bendpoint adjacent to the terminal (index 1 for source, {@code lastIdx-1}
     * for target) rather than the actual terminal endpoint — this makes the HPQ signal
     * sensitive to Axis 1 perpendicular shifts, which move those interior bps. For 2-bp
     * paths, uses the terminal bps directly.
     * Faces with at least {@link #M5_FACE_GUARD_MIN_CONNECTIONS} attributed terminals are
     * "hub faces"; per-face quality is {@code distinctSlots / connectionsOnFace} where
     * slot equality is within {@link #HUB_PORT_SLOT_TOLERANCE_PX}. Aggregate is the mean
     * of per-hub-face qualities; returns {@code 1.0} when no hub face exists.
     */
    static double computeHpq(List<RoutingPipeline.ConnectionEndpoints> connections,
                             List<List<AbsoluteBendpointDto>> paths) {
        if (connections == null || paths == null) return 1.0;
        Map<String, Map<Face, List<Double>>> facesByElement = new HashMap<>();
        int n = Math.min(connections.size(), paths.size());
        for (int idx = 0; idx < n; idx++) {
            RoutingPipeline.ConnectionEndpoints conn = connections.get(idx);
            List<AbsoluteBendpointDto> path = paths.get(idx);
            if (conn == null || path == null || path.size() < 2) continue;
            AbsoluteBendpointDto srcBp = path.size() == 2 ? path.get(0) : path.get(1);
            AbsoluteBendpointDto tgtBp = path.size() == 2 ? path.get(path.size() - 1)
                    : path.get(path.size() - 2);
            recordTerminal(facesByElement, conn.source(), srcBp);
            recordTerminal(facesByElement, conn.target(), tgtBp);
        }
        List<Double> hubFaceQualities = new ArrayList<>();
        for (Map<Face, List<Double>> facesMap : facesByElement.values()) {
            for (List<Double> slots : facesMap.values()) {
                if (slots.size() < M5_FACE_GUARD_MIN_CONNECTIONS) continue;
                int distinct = countDistinctSlots(slots);
                hubFaceQualities.add((double) distinct / slots.size());
            }
        }
        if (hubFaceQualities.isEmpty()) return 1.0;
        double sum = 0.0;
        for (double q : hubFaceQualities) sum += q;
        return sum / hubFaceQualities.size();
    }

    private enum Face {LEFT, RIGHT, TOP, BOTTOM}

    private record FaceSlot(Face face, double slot) {}

    private static void recordTerminal(Map<String, Map<Face, List<Double>>> facesByElement,
                                        RoutingRect elem, AbsoluteBendpointDto terminalBp) {
        if (elem == null || terminalBp == null) return;
        FaceSlot fs = inferTerminalSlot(terminalBp, elem);
        if (fs == null) return;
        Map<Face, List<Double>> faces = facesByElement.computeIfAbsent(elem.id(), k -> new HashMap<>());
        List<Double> slots = faces.computeIfAbsent(fs.face(), k -> new ArrayList<>());
        slots.add(fs.slot());
    }

    private static FaceSlot inferTerminalSlot(AbsoluteBendpointDto bp, RoutingRect elem) {
        Face face = inferFace(bp, elem);
        if (face != null) {
            double slot = (face == Face.LEFT || face == Face.RIGHT) ? bp.y() : bp.x();
            return new FaceSlot(face, slot);
        }
        if (isStrictlyInside(bp, elem)) return null;
        return clipSegmentToFace(elem, bp);
    }

    private static Face inferFace(AbsoluteBendpointDto bp, RoutingRect elem) {
        double tol = PERIMETER_TOLERANCE_PX;
        double left = elem.x();
        double right = elem.x() + elem.width();
        double top = elem.y();
        double bottom = elem.y() + elem.height();
        boolean onLeft = Math.abs(bp.x() - left) <= tol;
        boolean onRight = Math.abs(bp.x() - right) <= tol;
        boolean onTop = Math.abs(bp.y() - top) <= tol;
        boolean onBottom = Math.abs(bp.y() - bottom) <= tol;
        boolean withinHorizontal = bp.x() >= left - tol && bp.x() <= right + tol;
        boolean withinVertical = bp.y() >= top - tol && bp.y() <= bottom + tol;
        if (onLeft && withinVertical) return Face.LEFT;
        if (onRight && withinVertical) return Face.RIGHT;
        if (onTop && withinHorizontal) return Face.TOP;
        if (onBottom && withinHorizontal) return Face.BOTTOM;
        return null;
    }

    private static boolean isStrictlyInside(AbsoluteBendpointDto bp, RoutingRect elem) {
        if (elem == null) return false;
        double tol = PERIMETER_TOLERANCE_PX;
        return bp.x() > elem.x() + tol
                && bp.x() < elem.x() + elem.width() - tol
                && bp.y() > elem.y() + tol
                && bp.y() < elem.y() + elem.height() - tol;
    }

    private static FaceSlot clipSegmentToFace(RoutingRect elem, AbsoluteBendpointDto bp) {
        double cx = elem.x() + elem.width() / 2.0;
        double cy = elem.y() + elem.height() / 2.0;
        double dx = bp.x() - cx;
        double dy = bp.y() - cy;
        if (Math.abs(dx) < 1e-9 && Math.abs(dy) < 1e-9) return null;
        double left = elem.x();
        double right = elem.x() + elem.width();
        double top = elem.y();
        double bottom = elem.y() + elem.height();
        Face bestFace = null;
        double bestT = Double.POSITIVE_INFINITY;
        double bestSlot = 0.0;
        if (dx < -1e-9) {
            double t = (left - cx) / dx;
            double y = cy + t * dy;
            if (t > 0 && t < bestT && y >= top - PERIMETER_TOLERANCE_PX
                    && y <= bottom + PERIMETER_TOLERANCE_PX) {
                bestT = t; bestFace = Face.LEFT; bestSlot = y;
            }
        }
        if (dx > 1e-9) {
            double t = (right - cx) / dx;
            double y = cy + t * dy;
            if (t > 0 && t < bestT && y >= top - PERIMETER_TOLERANCE_PX
                    && y <= bottom + PERIMETER_TOLERANCE_PX) {
                bestT = t; bestFace = Face.RIGHT; bestSlot = y;
            }
        }
        if (dy < -1e-9) {
            double t = (top - cy) / dy;
            double x = cx + t * dx;
            if (t > 0 && t < bestT && x >= left - PERIMETER_TOLERANCE_PX
                    && x <= right + PERIMETER_TOLERANCE_PX) {
                bestT = t; bestFace = Face.TOP; bestSlot = x;
            }
        }
        if (dy > 1e-9) {
            double t = (bottom - cy) / dy;
            double x = cx + t * dx;
            if (t > 0 && t < bestT && x >= left - PERIMETER_TOLERANCE_PX
                    && x <= right + PERIMETER_TOLERANCE_PX) {
                bestT = t; bestFace = Face.BOTTOM; bestSlot = x;
            }
        }
        return bestFace == null ? null : new FaceSlot(bestFace, bestSlot);
    }

    private static int countDistinctSlots(List<Double> slots) {
        List<Double> sorted = new ArrayList<>(slots);
        sorted.sort(Double::compare);
        int distinct = 0;
        Double last = null;
        for (Double s : sorted) {
            if (last == null || Math.abs(s - last) > HUB_PORT_SLOT_TOLERANCE_PX) {
                distinct++;
                last = s;
            }
        }
        return distinct;
    }
}
