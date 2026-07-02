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
 * HH ("Hub Heavy") source-clone pin test — H5 Task 14.2 (closes the
 * verification gap on pin-calibration substrate parity).
 *
 * <p><b>Substrate.</b> {@code testdata/hh-source-clone-fixture.json} —
 * captured from Archi view {@code id-a53740b929ac47b0ac16b360647f2838}
 * ("HH source clone — m4 floor spike 2026-05-13") via
 * {@code mcp__archi__get-view-contents} on 2026-05-14. Element + group
 * positions are absolute canvas coords (child relative-to-parent offsets
 * translated at capture time). Topology + geometry are structurally identical
 * to {@code v4-integration-architecture-oracle-fixture.json} — same group sizes,
 * same child positions, same connection topology — but element IDs differ
 * because the HH source clone uses the current ArchiMate model element IDs
 * whereas the V4 oracle fixture predates a model edit.
 *
 * <p><b>Why this pin exists alongside {@link V4OracleQualityRegressionTest}.</b>
 * The two pins exercise the same pure-JUnit routing pipeline on the same
 * effective geometry but with DIFFERENT element IDs. A regression where
 * routing logic accidentally became ID-coupled (e.g., obstacle filtering
 * cross-checking IDs vs the wrong table, or label-exclude set computation
 * hashing on a stale ID prefix) would fire HH but not V4 (or vice versa).
 * In Task-8 live-MCP measurement (2026-05-13) the HH source clone was the
 * substrate that surfaced the monotonicity violation H5 had to fix
 * (V_p10 6.4 → 5.8 regression under the placeholder verifier) — the pin name
 * preserves the substrate identity for traceability even though the test
 * itself runs pure-JUnit per Option α (see substrate-parity-strategy.md).
 *
 * <p><b>Calibration provenance.</b> Per the pin-calibration
 * substrate-parity discipline the floor/ceiling values are
 * anchored at the PURE-JUNIT (Option α) substrate's pre-H5 empirical actuals,
 * NOT the live-MCP Task 8 measurements. The live-MCP HH actuals (HPQ=0.82,
 * M4=5, V_p10=6.4, M5=1) are a DIFFERENT substrate and DO NOT transfer to
 * pure-JUnit anchoring — the Task 7 precedent surfaced exactly this
 * substrate-parity failure mode for the V4 oracle pin. Initial first-run
 * actuals captured 2026-05-14 by Task 14.3; thresholds anchored at actuals.
 *
 * <p><b>What this pin protects.</b> Future regressions in any routing stage
 * (including H5 Tier-1 monotonicity guard failure) that push HH metrics
 * below the pre-H5 floor. Tier-1 zero-defect contract is asserted directly.
 *
 * <p><b>Rationale anchors:</b>
 * <ul>
 *   <li>The pin-calibration substrate-parity failure mode this pin's
 *       calibration guards against.</li>
 *   <li>Every routing improvement ships with a JUnit pin protecting the new
 *       threshold; HH+ST pins are the substrate-diverse pair.</li>
 *   <li>The Task 8 live-MCP measurement origin and the monotonicity violation
 *       these pins exist to permanently guard against.</li>
 * </ul>
 */
public class V4OracleHHSourceCloneLiveMcpPinTest {

    /**
     * HH source clone pure-JUnit HPQ floor. Captured 2026-05-14 first-run
     * actual = 0.819 (identical to {@link V4OracleQualityRegressionTest} V4
     * oracle pure-JUnit actual — same effective geometry). Floor 0.70 matches
     * V4OracleQualityRegressionTest{@code .HPQ_FLOOR} precedent: ~12 percentage
     * points of headroom for legitimate corridor-diversity variance.
     * The HH+V4 pair is intentionally symmetric — HH catches element-ID-
     * coupling regressions that V4's stale-ID fixture would miss.
     */
    private static final double HH_HPQ_FLOOR = 0.70;

    /**
     * HH source clone pure-JUnit M4 ceiling. Captured 2026-05-14 first-run
     * actual = 5 (identical to V4 oracle pure-JUnit). H5 is a structural no-op
     * on this fixture per the diagnostic finding (25 of 30 routed
     * paths are size-3 L-shapes with terminal-incident segments outside H5
     * scope) — same root cause as V4 oracle. Pin functions as a regression
     * sentinel for any upstream stage changing terminal-segment routing.
     */
    private static final int HH_M4_CEILING = 5;

    /**
     * HH source clone pure-JUnit V_p10 floor. Captured 2026-05-14 first-run
     * actual = 4.0 (identical to V4 oracle pure-JUnit). Anchored exactly at
     * actual — no buffer. Future regression of any upstream stage that
     * collapses intra-corridor parallel-V spread on the HH substrate fires
     * this pin.
     *
     * <p><b>Re-anchor 4.0&rarr;2.0 (2026-06-14).</b> Symmetric with
     * {@link V4OracleQualityRegressionTest#VP10_FLOOR} (see its Javadoc for the
     * full bisect narrative). Root cause: {@code c10bb8e} (v1.6 terminal
     * exit-then-return zigzag self-heal) — an accepted, agent-in-loop-gated trade
     * that halves parallel-V spread on the hub-and-spoke substrate while keeping
     * zigzag 0 and all other metrics healthy. The HH and V4 pins are an intentional
     * symmetric pair (HH catches element-ID-coupling V4's stale-ID fixture misses),
     * so both re-anchor together.
     */
    private static final double HH_VP10_FLOOR = 2.0;

    /**
     * HH source clone pure-JUnit M5 ceiling. Captured 2026-05-14 first-run
     * actual = 2 (identical to V4 oracle pure-JUnit). +1 noise headroom per
     * V4 oracle {@code M5_CEILING} stateless-fixture-parity precedent.
     */
    private static final int HH_M5_CEILING = 3;

    /**
     * HH source clone pure-JUnit M1 ceiling. Captured 2026-05-14 first-run
     * actual = 1. +4 headroom matches V4 oracle {@code M1_CEILING} precedent
     * (visible-segment-length guard tolerance for minor terminal-segment drift).
     */
    private static final int HH_M1_CEILING = 5;

    /** Live-MCP perimeter margin per {@link V4OracleQualityRegressionTest}. */
    private static final int LIVE_MCP_PERIMETER_MARGIN = 50;

    private ViewFixture fixture;
    private RoutingPipeline pipeline;
    private LayoutQualityAssessor assessor;

    @Before
    public void setUp() throws IOException {
        fixture = ViewFixture.load("testdata/hh-source-clone-fixture.json");
        pipeline = new RoutingPipeline(
                RoutingPipeline.DEFAULT_BEND_PENALTY,
                RoutingPipeline.DEFAULT_MARGIN,
                RoutingPipeline.DEFAULT_CONGESTION_WEIGHT,
                LIVE_MCP_PERIMETER_MARGIN);
        assessor = new LayoutQualityAssessor();
    }

    @Test
    public void hhSourceClone_metricsDiscovery_logsActualsForCalibration() {
        LayoutAssessmentResult result = routeAndAssess();
        System.out.println("[HH] HPQ=" + result.hubPortQualityScore()
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
    public void hhSourceClone_hubPortQuality_atOrAboveFloor() {
        LayoutAssessmentResult result = routeAndAssess();
        double actual = result.hubPortQualityScore();
        assertTrue("HH source clone HPQ regressed below pre-H5 floor "
                + HH_HPQ_FLOOR + ": actual=" + actual
                + " (substrate: pure-JUnit on hh-source-clone-fixture.json).",
                actual >= HH_HPQ_FLOOR);
    }

    @Test
    public void hhSourceClone_connectionEdgeCoincidence_atOrBelowCeiling() {
        LayoutAssessmentResult result = routeAndAssess();
        int actual = result.connectionEdgeCoincidenceCount();
        assertTrue("HH source clone M4 (connectionEdgeCoincidence) regressed "
                + "above pre-H5 ceiling " + HH_M4_CEILING + ": actual=" + actual
                + " (substrate: pure-JUnit on hh-source-clone-fixture.json; "
                + "see feedback_pin_calibration_substrate_parity).",
                actual <= HH_M4_CEILING);
    }

    @Test
    public void hhSourceClone_vAxisParallelGapP10_atOrAboveFloor() {
        LayoutAssessmentResult result = routeAndAssess();
        Double actual = result.vAxisParallelGapP10();
        assertNotNull("HH source clone V_p10 is null — pipeline produced no "
                + "qualifying parallel V segments (catastrophic regression; "
                + "pre-H5 floor was " + HH_VP10_FLOOR + ").", actual);
        assertTrue("HH source clone V_p10 regressed below pre-H5 floor "
                + HH_VP10_FLOOR + ": actual=" + actual
                + " (substrate: pure-JUnit on hh-source-clone-fixture.json).",
                actual >= HH_VP10_FLOOR);
    }

    @Test
    public void hhSourceClone_tier1ZeroDefect_holds() {
        LayoutAssessmentResult result = routeAndAssess();
        int overlap = result.overlapCount();
        int passThrough = result.connectionPassThroughs().size();
        int interior = result.interiorTerminationCount();
        int zigzag = result.zigzagCount();
        assertEquals("HH source clone Tier-1 overlap regressed above 0: "
                + overlap, 0, overlap);
        assertEquals("HH source clone Tier-1 passThrough regressed above 0: "
                + passThrough, 0, passThrough);
        assertEquals("HH source clone Tier-1 interior-termination regressed "
                + "above 0: " + interior, 0, interior);
        assertEquals("HH source clone Tier-1 zigzag regressed above 0: "
                + zigzag, 0, zigzag);
    }

    @Test
    public void hhSourceClone_allMetrics_combinedPinGate() {
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

        boolean structuralOk = hpq >= HH_HPQ_FLOOR
                && m4 <= HH_M4_CEILING
                && vp10 != null && vp10 >= HH_VP10_FLOOR
                && m5 <= HH_M5_CEILING
                && m1 <= HH_M1_CEILING;
        boolean tier1Ok = overlap == 0 && passThrough == 0
                && interior == 0 && zigzag == 0;

        assertTrue("HH source clone combined pin gate breached: "
                + "HPQ=" + hpq + " (floor " + HH_HPQ_FLOOR + "), "
                + "M4=" + m4 + " (ceiling " + HH_M4_CEILING + "), "
                + "V_p10=" + vp10 + " (floor " + HH_VP10_FLOOR + "), "
                + "M5=" + m5 + " (ceiling " + HH_M5_CEILING + "), "
                + "M1=" + m1 + " (ceiling " + HH_M1_CEILING + "), "
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

        System.out.println("[V4OracleHHSourceCloneLiveMcpPinTest] routing yield: "
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
