package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import net.vheerden.archi.mcp.model.routing.BestOfKRoutingStrategy;
import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.model.routing.RoutingResult;
import net.vheerden.archi.mcp.model.routing.ViewFixture;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Task 3 — never-worse + determinism proofs for
 * {@link BestOfKRoutingStrategy} exercised <b>end-to-end with the REAL
 * {@link RoutingPipeline} and the REAL {@link LayoutQualityAssessor}</b> on the
 * real 30-connection V4 Integration Architecture oracle fixture (NOT synthetic).
 *
 * <p><b>No rollback is needed or tested — best-of-K is monotone by selection.</b>
 * Run&nbsp;0 always uses the {@code null} processing-order override, which makes
 * the pipeline compute its own unchanged {@code buildConnectionRoutingOrder}
 * &mdash; <em>byte-identical to current {@code main}</em>. Selection is
 * {@code argmax} with a strictly-greater replacement rule, so run&nbsp;0 wins
 * every tie. Therefore {@code objective(emitted) &ge; objective(run0) =
 * objective(current main)} for every input, BY CONSTRUCTION. Unlike HPRPS's
 * {@code verifyMetricMonotonicity} rollback model there is nothing to roll back:
 * the current-{@code main} result is permanently in the candidate set and always
 * wins ties. These tests assert that construction on the real substrate.
 *
 * <p>The injected {@link BestOfKRoutingStrategy.CandidateScorer} is the SAME
 * total order the production composition uses (assessor {@code overallRating}
 * ordinal dominant &times;1000 + bounded HPQ&uarr;/M4&darr;/coincSeg&darr;
 * composite tie-break) &mdash; mirrors {@code ArchiModelAccessorImpl.scoreRoutingCandidate}
 * so this end-to-end proof binds the real ship-gate objective.
 *
 * <p>Substrate construction mirrors {@link V4OracleQualityRegressionTest}
 * (group/obstacle split, per-connection groupBoundaries + label-exclude sets,
 * routed+violated merge, AssessmentNode/AssessmentConnection build) so the
 * pipeline + assessor see exactly the production-equivalent inputs.
 */
public class BestOfKRoutingStrategyV4FixtureTest {

    private static final int LIVE_MCP_PERIMETER_MARGIN = 50;

    private ViewFixture fixture;
    private RoutingPipeline pipeline;
    private LayoutQualityAssessor assessor;
    private List<RoutingPipeline.ConnectionEndpoints> endpoints;
    private List<RoutingRect> allObstacles;
    private Map<String, Set<String>> labelExcludeSets;
    private List<AssessmentNode> nodes;

    @Before
    public void setUp() throws IOException {
        fixture = ViewFixture.load("testdata/v4-integration-architecture-oracle-fixture.json");
        pipeline = new RoutingPipeline(
                RoutingPipeline.DEFAULT_BEND_PENALTY,
                RoutingPipeline.DEFAULT_MARGIN,
                RoutingPipeline.DEFAULT_CONGESTION_WEIGHT,
                LIVE_MCP_PERIMETER_MARGIN);
        assessor = new LayoutQualityAssessor();

        endpoints = new ArrayList<>();
        for (RoutingPipeline.ConnectionEndpoints ep : fixture.buildConnectionEndpoints()) {
            List<RoutingRect> filteredObstacles = new ArrayList<>();
            List<RoutingRect> groupBoundaries = new ArrayList<>();
            for (RoutingRect obs : ep.obstacles()) {
                if (isGroupId(obs.id())) {
                    groupBoundaries.add(obs);
                } else {
                    filteredObstacles.add(obs);
                }
            }
            endpoints.add(new RoutingPipeline.ConnectionEndpoints(
                    ep.connectionId(), ep.source(), ep.target(),
                    filteredObstacles, ep.labelText(), ep.textPosition(),
                    groupBoundaries));
        }
        allObstacles = new ArrayList<>();
        for (RoutingRect obs : fixture.buildAllObstacles()) {
            if (!isGroupId(obs.id())) {
                allObstacles.add(obs);
            }
        }
        labelExcludeSets = new LinkedHashMap<>();
        for (RoutingPipeline.ConnectionEndpoints conn : endpoints) {
            Set<String> excludeIds = new HashSet<>();
            excludeIds.add(conn.source().id());
            excludeIds.add(conn.target().id());
            collectAncestors(conn.source().id(), excludeIds);
            collectAncestors(conn.target().id(), excludeIds);
            collectChildren(conn.source().id(), excludeIds);
            collectChildren(conn.target().id(), excludeIds);
            labelExcludeSets.put(conn.connectionId(), excludeIds);
        }
        nodes = new ArrayList<>();
        for (ViewFixture.FixtureElement e : fixture.getElements()) {
            nodes.add(new AssessmentNode(
                    e.id(), e.x(), e.y(), e.w(), e.h(), e.parentId(),
                    !e.isChild(), false, e.name(), 0.0, null, null, 0.0, 0.0, 0.0));
        }
    }

    private BestOfKRoutingStrategy.RouteRunner runner() {
        return order -> pipeline.routeAllConnections(endpoints, allObstacles,
                labelExcludeSets, RoutingPipeline.DEFAULT_SNAP_THRESHOLD,
                RoutingPipeline.DEFAULT_ENABLE_CHANNEL_NUDGING, order);
    }

    /** The production ship-gate objective (mirrors ArchiModelAccessorImpl.scoreRoutingCandidate). */
    private BestOfKRoutingStrategy.CandidateScorer scorer() {
        return candidate -> {
            Map<String, List<AbsoluteBendpointDto>> bp = new LinkedHashMap<>();
            bp.putAll(candidate.routed());
            bp.putAll(candidate.violatedRoutes());
            List<AssessmentConnection> conns = new ArrayList<>();
            for (RoutingPipeline.ConnectionEndpoints ep : endpoints) {
                double sx = ep.source().centerX();
                double sy = ep.source().centerY();
                double tx = ep.target().centerX();
                double ty = ep.target().centerY();
                List<double[]> pts = new ArrayList<>();
                pts.add(new double[]{sx, sy});
                List<AbsoluteBendpointDto> rb = bp.get(ep.connectionId());
                if (rb != null) {
                    for (AbsoluteBendpointDto b : rb) {
                        pts.add(new double[]{b.x(), b.y()});
                    }
                }
                pts.add(new double[]{tx, ty});
                conns.add(new AssessmentConnection(ep.connectionId(),
                        ep.source().id(), ep.target().id(), pts,
                        ep.labelText(), ep.textPosition()));
            }
            LayoutAssessmentResult r = assessor.assess(nodes, conns, false);
            double rating = 1000.0 * LayoutQualityAssessor.ratingOrdinal(r.overallRating());
            double hpq = Math.max(0.0, Math.min(1.0, r.hubPortQualityScore()));
            double m4 = Math.min(r.connectionEdgeCoincidenceCount(), 30) / 30.0;
            double coinc = Math.min(r.coincidentSegmentCount(), 30) / 30.0;
            return rating + hpq - m4 - coinc;
        };
    }

    // ---- never-worse-by-construction on the real V4 substrate ----

    @Test
    public void k1_shouldBeByteIdenticalToLegacyPipeline_onV4Oracle() {
        RoutingResult legacy = pipeline.routeAllConnections(
                endpoints, allObstacles, labelExcludeSets);
        RoutingResult best = new BestOfKRoutingStrategy(
                runner(), scorer(), 1, 42L, 1000, 0L).selectBest(endpoints);

        assertEquals("K=1 routed map must be byte-identical to the legacy "
                + "pipeline (the 5-arg overload delegates with null ⇒ same "
                + "buildConnectionRoutingOrder)", legacy.routed(), best.routed());
        assertEquals("K=1 violatedRoutes must be byte-identical to legacy",
                legacy.violatedRoutes(), best.violatedRoutes());
        assertEquals("K=1 failed-count must match legacy",
                legacy.failed().size(), best.failed().size());
    }

    @Test
    public void kGreaterThan1_objectiveShouldBeGreaterOrEqualToRun0_onV4Oracle() {
        BestOfKRoutingStrategy.CandidateScorer sc = scorer();
        RoutingResult run0 = pipeline.routeAllConnections(
                endpoints, allObstacles, labelExcludeSets);
        RoutingResult best = new BestOfKRoutingStrategy(
                runner(), sc, 12, 42L, 1000, 30_000L).selectBest(endpoints);

        double run0Obj = sc.score(run0);
        double bestObj = sc.score(best);
        assertTrue("never-worse: best-of-K objective " + bestObj
                + " must be >= run-0 (current-main) objective " + run0Obj
                + " on the real V4 oracle substrate, by construction",
                bestObj >= run0Obj);
    }

    // ---- determinism / reproducibility on the real V4 substrate ----

    @Test
    public void sameSeedSameK_shouldEmitByteIdenticalRouting_onV4Oracle() {
        RoutingResult a = new BestOfKRoutingStrategy(
                runner(), scorer(), 10, 31337L, 1000, 30_000L).selectBest(endpoints);
        RoutingResult b = new BestOfKRoutingStrategy(
                runner(), scorer(), 10, 31337L, 1000, 30_000L).selectBest(endpoints);
        assertEquals("same seed + same K ⇒ byte-identical emitted routing "
                + "(reproducibility cornerstone)", a.routed(), b.routed());
        assertEquals(a.violatedRoutes(), b.violatedRoutes());
    }

    @Test
    public void distinctSeeds_shouldExploreDistinctOrderings_onV4Oracle() {
        List<Integer[]> ordersA = new ArrayList<>();
        List<Integer[]> ordersB = new ArrayList<>();
        BestOfKRoutingStrategy.RouteRunner recA = order -> {
            ordersA.add(order == null ? null : order.clone());
            return runner().route(order);
        };
        BestOfKRoutingStrategy.RouteRunner recB = order -> {
            ordersB.add(order == null ? null : order.clone());
            return runner().route(order);
        };
        new BestOfKRoutingStrategy(recA, scorer(), 8, 1L, 1000, 30_000L)
                .selectBest(endpoints);
        new BestOfKRoutingStrategy(recB, scorer(), 8, 999_983L, 1000, 30_000L)
                .selectBest(endpoints);

        boolean anyDiffer = false;
        for (int i = 1; i < ordersA.size() && i < ordersB.size(); i++) {
            if (!java.util.Arrays.equals(ordersA.get(i), ordersB.get(i))) {
                anyDiffer = true;
                break;
            }
        }
        assertTrue("distinct seeds must explore distinct processing orderings "
                + "on the real V4 substrate (the search is real, not a no-op)",
                anyDiffer);
    }

    @Test
    public void neverWorseInvariant_shouldHoldAcrossManySeeds_onV4Oracle() {
        BestOfKRoutingStrategy.CandidateScorer sc = scorer();
        double run0Obj = sc.score(pipeline.routeAllConnections(
                endpoints, allObstacles, labelExcludeSets));
        for (long seed : new long[]{1L, 7L, 42L, 1009L, 65537L, 999_983L}) {
            RoutingResult best = new BestOfKRoutingStrategy(
                    runner(), sc, 8, seed, 1000, 30_000L).selectBest(endpoints);
            assertTrue("never-worse invariant violated at seed " + seed
                    + ": best objective " + sc.score(best) + " < run-0 "
                    + run0Obj, sc.score(best) >= run0Obj);
        }
    }

    @Test
    public void largeViewGuard_shouldReturnRun0_whenThresholdExceeded_onV4Oracle() {
        // 30 connections > threshold 5 ⇒ degrade to K=1 ⇒ exactly run-0.
        RoutingResult legacy = pipeline.routeAllConnections(
                endpoints, allObstacles, labelExcludeSets);
        RoutingResult guarded = new BestOfKRoutingStrategy(
                runner(), scorer(), 12, 42L, /*largeViewThreshold*/ 5, 30_000L)
                .selectBest(endpoints);
        assertEquals("large-view guard must yield exactly the current-main "
                + "result (never-worse preserved under the guard)",
                legacy.routed(), guarded.routed());
    }

    // ---- Task 4: non-duplicative end-to-end integration ----

    /**
     * Task 4 integration @Test — deliberately NOT duplicating the Task-3
     * objective-scalar / determinism tests. Asserts the <em>structural
     * consequence</em> of the rating-dominant objective on the real composed
     * path: the emitted best-of-K routing, re-assessed end-to-end by the real
     * {@link LayoutQualityAssessor}, (a) preserves the Tier-1 zero-defect
     * contract relative to run-0 (overlap / passThrough / interiorTermination /
     * zigzag never regress above the current-{@code main} run-0 counts — the
     * objective's {@code overallRating}-dominance makes a Tier-1 regression
     * un-selectable by construction), (b) has an {@code overallRating} ordinal
     * &ge; run-0, and (c) is reproducible. Reuses
     * {@code v4-integration-architecture-oracle-fixture.json} sizing.
     */
    @Test
    public void task4_integration_emittedBestOfK_preservesTier1AndRating_onV4Oracle() {
        RoutingResult run0 = pipeline.routeAllConnections(
                endpoints, allObstacles, labelExcludeSets);
        BestOfKRoutingStrategy strategy = new BestOfKRoutingStrategy(
                runner(), scorer(), 12, 20_260_516L, 1000, 30_000L);
        RoutingResult best = strategy.selectBest(endpoints);
        RoutingResult bestAgain = strategy.selectBest(endpoints);

        LayoutAssessmentResult r0 = assessResult(run0);
        LayoutAssessmentResult rb = assessResult(best);

        assertTrue("Tier-1 overlap regressed: best=" + rb.overlapCount()
                + " run0=" + r0.overlapCount(),
                rb.overlapCount() <= r0.overlapCount());
        assertTrue("Tier-1 passThrough regressed: best="
                + rb.connectionPassThroughs().size() + " run0="
                + r0.connectionPassThroughs().size(),
                rb.connectionPassThroughs().size() <= r0.connectionPassThroughs().size());
        assertTrue("Tier-1 interiorTermination regressed: best="
                + rb.interiorTerminationCount() + " run0="
                + r0.interiorTerminationCount(),
                rb.interiorTerminationCount() <= r0.interiorTerminationCount());
        assertTrue("Tier-1 zigzag regressed: best=" + rb.zigzagCount()
                + " run0=" + r0.zigzagCount(),
                rb.zigzagCount() <= r0.zigzagCount());
        assertTrue("emitted overallRating ordinal must be >= run-0 "
                + "(rating-dominant objective): best=" + rb.overallRating()
                + " run0=" + r0.overallRating(),
                LayoutQualityAssessor.ratingOrdinal(rb.overallRating())
                        >= LayoutQualityAssessor.ratingOrdinal(r0.overallRating()));
        assertEquals("composed best-of-K must be reproducible end-to-end",
                best.routed(), bestAgain.routed());
    }

    /**
     * Task 5.1 MEASUREMENT probe (not a pin) — captures the emitted best-of-K
     * M4 / V_p10 / coincSeg / rating vs run-0 on the V4 pure-JUnit substrate so
     * the Pin-1/Pin-2 re-raise decision is data-driven, never assumed (per
     * anti-disaster #6 + the HPRPS no-aspirational-lying discipline). Prints the
     * actuals; asserts only never-worse (already guaranteed) so it is a probe,
     * not a brittle value pin.
     */
    @Test
    public void task5_measure_bestOfK_vs_run0_onV4PureJUnitSubstrate() {
        RoutingResult run0 = pipeline.routeAllConnections(
                endpoints, allObstacles, labelExcludeSets);
        RoutingResult best = new BestOfKRoutingStrategy(
                runner(), scorer(), 12, 42L, 1000, 60_000L).selectBest(endpoints);
        LayoutAssessmentResult r0 = assessResult(run0);
        LayoutAssessmentResult rb = assessResult(best);
        System.out.println("[Task5-measure] V4 pure-JUnit substrate — "
                + "run-0:  M4=" + r0.connectionEdgeCoincidenceCount()
                + " V_p10=" + r0.vAxisParallelGapP10()
                + " coincSeg=" + r0.coincidentSegmentCount()
                + " HPQ=" + r0.hubPortQualityScore()
                + " rating=" + r0.overallRating());
        System.out.println("[Task5-measure] V4 pure-JUnit substrate — "
                + "best-K12: M4=" + rb.connectionEdgeCoincidenceCount()
                + " V_p10=" + rb.vAxisParallelGapP10()
                + " coincSeg=" + rb.coincidentSegmentCount()
                + " HPQ=" + rb.hubPortQualityScore()
                + " rating=" + rb.overallRating());
        assertTrue("emitted rating ordinal must be >= run-0 (never-worse)",
                LayoutQualityAssessor.ratingOrdinal(rb.overallRating())
                        >= LayoutQualityAssessor.ratingOrdinal(r0.overallRating()));
    }

    /**
     * Task 5 PERF-SENTINEL pin — K=default(12) best-of-K on the
     * gate-sized (30-connection) real V4 oracle substrate must complete within
     * an asserted wall-clock bound (no per-run latency blow-up on normal
     * views). The bound is deliberately generous (catches a pathological
     * O(K&middot;...) blow-up, not micro-jitter). Complemented by
     * {@link #largeViewGuard_shouldReturnRun0_whenThresholdExceeded_onV4Oracle}
     * (the large-view degrade-to-K=1 guard) — together these are the
     * contract: bounded cost on normal views + a hard guard above a
     * connection-count threshold.
     */
    @Test
    public void task5_perfSentinel_K12_onGateSizedV4_withinWallClockBound() {
        long startNanos = System.nanoTime();
        RoutingResult best = new BestOfKRoutingStrategy(
                runner(), scorer(), 12, 42L, 1000, 60_000L).selectBest(endpoints);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        assertNotNull(best);
        assertTrue("AC-15 perf-sentinel: K=12 best-of-K on the 30-connection V4 "
                + "oracle took " + elapsedMs + "ms — exceeds the 45000ms "
                + "wall-clock sentinel bound (investigate a pipeline-cost or "
                + "scorer-cost regression; K-multiplier blow-up)",
                elapsedMs < 45_000L);
    }

    private LayoutAssessmentResult assessResult(RoutingResult candidate) {
        Map<String, List<AbsoluteBendpointDto>> bp = new LinkedHashMap<>();
        bp.putAll(candidate.routed());
        bp.putAll(candidate.violatedRoutes());
        List<AssessmentConnection> conns = new ArrayList<>();
        for (RoutingPipeline.ConnectionEndpoints ep : endpoints) {
            List<double[]> pts = new ArrayList<>();
            pts.add(new double[]{ep.source().centerX(), ep.source().centerY()});
            List<AbsoluteBendpointDto> rb = bp.get(ep.connectionId());
            if (rb != null) {
                for (AbsoluteBendpointDto b : rb) {
                    pts.add(new double[]{b.x(), b.y()});
                }
            }
            pts.add(new double[]{ep.target().centerX(), ep.target().centerY()});
            conns.add(new AssessmentConnection(ep.connectionId(),
                    ep.source().id(), ep.target().id(), pts,
                    ep.labelText(), ep.textPosition()));
        }
        return assessor.assess(nodes, conns, true);
    }

    // ---- helpers (mirror V4OracleQualityRegressionTest) ----

    private boolean isGroupId(String id) {
        ViewFixture.FixtureElement e = fixture.getElementById(id);
        return e != null && !e.isChild();
    }

    private void collectAncestors(String id, Set<String> into) {
        ViewFixture.FixtureElement e = fixture.getElementById(id);
        while (e != null && e.parentId() != null) {
            into.add(e.parentId());
            e = fixture.getElementById(e.parentId());
        }
    }

    private void collectChildren(String parentId, Set<String> into) {
        for (ViewFixture.FixtureElement e : fixture.getElements()) {
            if (parentId.equals(e.parentId())) {
                into.add(e.id());
            }
        }
    }
}
