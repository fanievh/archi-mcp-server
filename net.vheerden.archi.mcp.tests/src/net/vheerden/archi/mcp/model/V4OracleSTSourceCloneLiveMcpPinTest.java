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

import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.model.routing.RoutingResult;
import net.vheerden.archi.mcp.model.routing.ViewFixture;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * ST ("Spread Tight") source-clone pin test — H5 Task 14.3 (closes the
 * verification gap on pin-calibration substrate parity).
 *
 * <p><b>Substrate.</b> {@code testdata/st-source-clone-fixture.json} —
 * captured from Archi view {@code id-e60f3843fb2b4f4489ed948bcc7c166c}
 * ("ST source clone — m4 floor spike 2026-05-13") via
 * {@code mcp__archi__get-view-contents} on 2026-05-14. Same topology as HH
 * source clone but with TIGHTER group widths (Producers 242x922 vs 322x1422
 * on HH, Integration 274x626 vs 400x1589, Consumers 258x1019 vs 338x2089) and
 * a SMALLER API Management Platform hub (214x68 vs 300x250 on HH). The
 * smaller hub forces higher M4 (edge coincidence — more connections cram
 * onto fewer hub-perimeter slots) and lower V_p10 (less corridor spread room).
 * This is the load-bearing reason for an ST pin alongside HH: ST exercises a
 * geometrically harder routing case where the H5 algorithm has more work to
 * do and the monotonicity verifier is more likely to be tested.
 *
 * <p><b>Calibration provenance.</b> Per the pin-calibration
 * substrate-parity discipline the floor/ceiling values are
 * anchored at the PURE-JUNIT (Option α) substrate's pre-H5 empirical actuals,
 * NOT the live-MCP Task 8 measurements. The live-MCP ST actuals (HPQ=0.60,
 * M4=7, V_p10=1.0, M5=9) are a DIFFERENT substrate and DO NOT transfer to
 * pure-JUnit anchoring — the Task 7 precedent surfaced exactly this
 * substrate-parity failure mode for the V4 oracle pin. Initial first-run
 * actuals captured 2026-05-14 by Task 14.3; thresholds anchored at actuals.
 * <b>HPQ re-anchored 2026-06-18</b> (v1.7 routing-pin re-anchor): floor
 * 0.40&rarr;0.08 to track the HPQ actual 0.4565&rarr;0.1428 caused by the v1.7
 * mean&rarr;min(worst-face) aggregation change (commit 2e45716, #5), NOT a routing
 * regression; all structural + Tier-1 pins unchanged and still holding
 * (M4=10, V_p10=3.0, M5=8, M1=4, Tier-1=0). See {@link #ST_HPQ_FLOOR}.
 *
 * <p><b>What this pin protects.</b> Future regressions in any routing stage
 * (including H5 Tier-1 monotonicity guard failure on the geometrically
 * harder ST substrate) that push ST metrics below the pre-H5 floor. Task 8's
 * live-MCP measurement showed ST M4 regressed 7→8 under the placeholder
 * verifier — this pin's ceiling will catch any future regression of that
 * type on the pure-JUnit substrate.
 *
 * <p><b>Memory anchors:</b>
 * <ul>
 *   <li>{@code feedback_pin_calibration_substrate_parity.md}</li>
 *   <li>{@code feedback_metric_and_regression_test_together.md}</li>
 *   <li>{@code h5-implementation-2026-05-13/structural-results.md}</li>
 * </ul>
 */
public class V4OracleSTSourceCloneLiveMcpPinTest {

    /**
     * ST source clone pure-JUnit HPQ floor. RE-ANCHORED 2026-06-18 (v1.7
     * routing-pin re-anchor): current actual = 0.1428, down from the 2026-05-14
     * first-run actual of 0.4565. The drop is the direct consequence of the v1.7
     * HPQ aggregation change from MEAN to MIN (worst face) — commit 2e45716, the #5
     * worst-face fix (2026-06-14) — NOT a routing regression. The mean-era actual
     * 0.4565 averaged ST's one degraded hub face against its healthy ones; the
     * min-era actual 0.1428 reports that worst face honestly. ST's pathologically
     * small 214x68 API Management Platform hub (far fewer perimeter slots than HH/V4's
     * 300x250) is exactly the topology where one face degrades and min &lt;&lt; mean.
     * This is a metric-definition re-anchor, NOT masking a structural regression:
     * every Tier-1 defect metric is clean (overlap/passThrough/interior/zigzag = 0)
     * and every structural pin still holds (M4=10 at ceiling, V_p10=3.0 above floor,
     * M5=8, M1=4); corridorUtilisation R8=0.437 corroborates the routing is otherwise
     * sound. Floor 0.08 = actual 0.1428 minus the ~0.06 stateless-fixture parity
     * buffer per V4 oracle pin precedent. The pin encodes the no-regression contract
     * on ST, NOT a release-gate quality target (those live on V4 oracle and HH); the
     * router half of fixing one-sided egress (vs the #5 detection half that shipped)
     * is the deferred Lever-A/B backlog track.
     */
    private static final double ST_HPQ_FLOOR = 0.08;

    /**
     * ST source clone pure-JUnit M4 ceiling. Captured 2026-05-14 first-run
     * actual = 10. Anchored exactly at actual — no buffer. ST is the
     * geometrically hardest substrate in the H5 pin matrix: 7 connections
     * fan out from a 214x68 API Management Platform hub plus more inbound
     * from Consumers, all crowding onto 4 faces of limited extent. M4=10
     * IS the structural floor. Future regression that pushes M4 ≥ 11 fires
     * this pin (most likely root cause: H5 verifyMetricMonotonicity guard
     * failed to reject an Axis-1 or Axis-2 mutation that introduced new
     * face-hugging — exactly the failure mode Task 8 caught on the
     * placeholder verifier).
     */
    private static final int ST_M4_CEILING = 10;

    /**
     * ST source clone pure-JUnit V_p10 floor. Captured 2026-05-14 first-run
     * actual = 3.0. Floor anchored at {@code actual - 0.5} stateless-fixture
     * non-determinism buffer per {@link V4OracleQualityRegressionTest}
     * {@code R8_FLOOR} precedent (added 2026-05-03). Loose
     * enough to absorb tiebreaker variance, tight enough to catch a real
     * routing-pipeline change collapsing intra-corridor spread on ST.
     */
    private static final double ST_VP10_FLOOR = 2.5;

    /** ST source clone pure-JUnit M5 ceiling (anchored at pre-H5 actual + noise headroom). */
    private static final int ST_M5_CEILING = 12;

    /** ST source clone pure-JUnit M1 ceiling (visible-segment-length guard headroom). */
    private static final int ST_M1_CEILING = 5;

    /** Live-MCP perimeter margin per {@link V4OracleQualityRegressionTest}. */
    private static final int LIVE_MCP_PERIMETER_MARGIN = 50;

    private ViewFixture fixture;
    private RoutingPipeline pipeline;
    private LayoutQualityAssessor assessor;

    @Before
    public void setUp() throws IOException {
        fixture = ViewFixture.load("testdata/st-source-clone-fixture.json");
        pipeline = new RoutingPipeline(
                RoutingPipeline.DEFAULT_BEND_PENALTY,
                RoutingPipeline.DEFAULT_MARGIN,
                RoutingPipeline.DEFAULT_CONGESTION_WEIGHT,
                LIVE_MCP_PERIMETER_MARGIN);
        assessor = new LayoutQualityAssessor();
    }

    @Test
    public void stSourceClone_metricsDiscovery_logsActualsForCalibration() {
        LayoutAssessmentResult result = routeAndAssess();
        System.out.println("[ST] HPQ=" + result.hubPortQualityScore()
                + " M4=" + result.connectionEdgeCoincidenceCount()
                + " V_p10=" + result.vAxisParallelGapP10()
                + " M5=" + result.coincidentSegmentCount()
                + " M1=" + result.nonOrthogonalTerminalCount()
                + " R8=" + result.corridorUtilisationScore()
                + " overlap=" + result.overlapCount()
                + " PT=" + result.connectionPassThroughs().size()
                + " interior=" + result.interiorTerminationCount()
                + " zigzag=" + result.zigzagCount());
    }

    @Test
    public void stSourceClone_hubPortQuality_atOrAboveFloor() {
        LayoutAssessmentResult result = routeAndAssess();
        double actual = result.hubPortQualityScore();
        assertTrue("ST source clone HPQ regressed below pre-H5 floor "
                + ST_HPQ_FLOOR + ": actual=" + actual
                + " (substrate: pure-JUnit on st-source-clone-fixture.json).",
                actual >= ST_HPQ_FLOOR);
    }

    @Test
    public void stSourceClone_connectionEdgeCoincidence_atOrBelowCeiling() {
        LayoutAssessmentResult result = routeAndAssess();
        int actual = result.connectionEdgeCoincidenceCount();
        assertTrue("ST source clone M4 (connectionEdgeCoincidence) regressed "
                + "above pre-H5 ceiling " + ST_M4_CEILING + ": actual=" + actual
                + " (substrate: pure-JUnit on st-source-clone-fixture.json; "
                + "ST hub is 214x68 vs HH 300x250 → harder case for H5).",
                actual <= ST_M4_CEILING);
    }

    @Test
    public void stSourceClone_vAxisParallelGapP10_atOrAboveFloor() {
        LayoutAssessmentResult result = routeAndAssess();
        Double actual = result.vAxisParallelGapP10();
        // ST has very narrow corridors — V_p10 may legitimately be null on this substrate;
        // when non-null, enforces ST_VP10_FLOOR = 2.5 (actual 3.0 - 0.5 nondeterminism buffer).
        if (actual != null) {
            assertTrue("ST source clone V_p10 regressed below pre-H5 floor "
                    + ST_VP10_FLOOR + ": actual=" + actual
                    + " (substrate: pure-JUnit on st-source-clone-fixture.json).",
                    actual >= ST_VP10_FLOOR);
        }
        // null is allowed for ST — the assertion only fires on non-null
        // regression below floor. Discovery test captures the actual.
    }

    @Test
    public void stSourceClone_tier1ZeroDefect_holds() {
        LayoutAssessmentResult result = routeAndAssess();
        int overlap = result.overlapCount();
        int passThrough = result.connectionPassThroughs().size();
        int interior = result.interiorTerminationCount();
        int zigzag = result.zigzagCount();
        assertEquals("ST source clone Tier-1 overlap regressed above 0: "
                + overlap, 0, overlap);
        assertEquals("ST source clone Tier-1 passThrough regressed above 0: "
                + passThrough, 0, passThrough);
        assertEquals("ST source clone Tier-1 interior-termination regressed "
                + "above 0: " + interior, 0, interior);
        assertEquals("ST source clone Tier-1 zigzag regressed above 0: "
                + zigzag, 0, zigzag);
    }

    @Test
    public void stSourceClone_allMetrics_combinedPinGate() {
        LayoutAssessmentResult result = routeAndAssess();
        double hpq = result.hubPortQualityScore();
        int m4 = result.connectionEdgeCoincidenceCount();
        Double vp10 = result.vAxisParallelGapP10();
        int m5 = result.coincidentSegmentCount();
        int m1 = result.nonOrthogonalTerminalCount();
        int overlap = result.overlapCount();
        int passThrough = result.connectionPassThroughs().size();
        int interior = result.interiorTerminationCount();
        int zigzag = result.zigzagCount();

        boolean structuralOk = hpq >= ST_HPQ_FLOOR
                && m4 <= ST_M4_CEILING
                && (vp10 == null || vp10 >= ST_VP10_FLOOR)
                && m5 <= ST_M5_CEILING
                && m1 <= ST_M1_CEILING;
        boolean tier1Ok = overlap == 0 && passThrough == 0
                && interior == 0 && zigzag == 0;

        assertTrue("ST source clone combined pin gate breached: "
                + "HPQ=" + hpq + " (floor " + ST_HPQ_FLOOR + "), "
                + "M4=" + m4 + " (ceiling " + ST_M4_CEILING + "), "
                + "V_p10=" + vp10 + " (floor " + ST_VP10_FLOOR + ", null allowed), "
                + "M5=" + m5 + " (ceiling " + ST_M5_CEILING + "), "
                + "M1=" + m1 + " (ceiling " + ST_M1_CEILING + "), "
                + "Tier-1 [overlap=" + overlap + ", PT=" + passThrough
                + ", interior=" + interior + ", zigzag=" + zigzag + "].",
                structuralOk && tier1Ok);
    }

    // ---- helpers (copied from V4OracleQualityRegressionTest; same semantics) ----

    private LayoutAssessmentResult routeAndAssess() {
        List<RoutingPipeline.ConnectionEndpoints> endpoints = new ArrayList<>();
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
        List<RoutingRect> allObstacles = new ArrayList<>();
        for (RoutingRect obs : fixture.buildAllObstacles()) {
            if (!isGroupId(obs.id())) {
                allObstacles.add(obs);
            }
        }
        Map<String, Set<String>> labelExcludeSets = new LinkedHashMap<>();
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

        RoutingResult routingResult = pipeline.routeAllConnections(
                endpoints, allObstacles, labelExcludeSets);

        Map<String, List<AbsoluteBendpointDto>> routedBps = new LinkedHashMap<>();
        routedBps.putAll(routingResult.routed());
        routedBps.putAll(routingResult.violatedRoutes());

        System.out.println("[V4OracleSTSourceCloneLiveMcpPinTest] routing yield: "
                + routingResult.routed().size() + " routed + "
                + routingResult.violatedRoutes().size() + " violated + "
                + routingResult.failed().size() + " failed = "
                + (routingResult.routed().size()
                        + routingResult.violatedRoutes().size()
                        + routingResult.failed().size())
                + " of " + fixture.getConnections().size() + " total");

        List<AssessmentNode> nodes = new ArrayList<>();
        for (ViewFixture.FixtureElement e : fixture.getElements()) {
            boolean isGroup = !e.isChild();
            nodes.add(new AssessmentNode(
                    e.id(),
                    e.x(), e.y(), e.w(), e.h(),
                    e.parentId(),
                    isGroup,
                    false,
                    e.name(),
                    0.0,
                    null,
                    null, 0.0, 0.0, 0.0));
        }

        List<AssessmentConnection> connections = new ArrayList<>();
        for (ViewFixture.FixtureConnection c : fixture.getConnections()) {
            List<AbsoluteBendpointDto> bps = routedBps.get(c.id());
            if (bps == null) {
                continue;
            }
            ViewFixture.FixtureElement src = fixture.getElementById(c.sourceId());
            ViewFixture.FixtureElement tgt = fixture.getElementById(c.targetId());
            assertNotNull("Fixture connection " + c.id()
                    + " references unknown sourceId=" + c.sourceId(), src);
            assertNotNull("Fixture connection " + c.id()
                    + " references unknown targetId=" + c.targetId(), tgt);

            List<double[]> pathPoints = new ArrayList<>();
            pathPoints.add(new double[] {src.x() + src.w() / 2.0, src.y() + src.h() / 2.0});
            for (AbsoluteBendpointDto bp : bps) {
                pathPoints.add(new double[] {bp.x(), bp.y()});
            }
            pathPoints.add(new double[] {tgt.x() + tgt.w() / 2.0, tgt.y() + tgt.h() / 2.0});

            connections.add(new AssessmentConnection(
                    c.id(), c.sourceId(), c.targetId(),
                    pathPoints, c.label(), 1));
        }

        return assessor.assess(nodes, connections, true);
    }

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
