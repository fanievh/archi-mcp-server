package net.vheerden.archi.mcp.model;

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
 * V4 Integration Architecture Oracle R8 corridor-utilisation regression test
 * (the 5th JUnit-protected metric on V4 oracle).
 *
 * <p>Pins {@code corridorUtilisationScore} &ge; {@link #R8_FLOOR} on the V4 oracle
 * current-pipeline view by re-routing the V4 oracle fixture on each test run via the
 * routing pipeline (with {@link #LIVE_MCP_PERIMETER_MARGIN}=50, matching the live
 * {@code auto-route-connections} default per the probe-investigation log).
 *
 * <p>This test EXTENDS the v1.4 release-gate floor (HPQ + M5 + M1 pinned)
 * with a fourth pinned threshold (R8 = corridor-utilisation). Together with
 * {@link V4OracleQualityRegressionTest} (HPQ + M5 + M1), the v1.4 release gate now has
 * FOUR JUnit-protected metrics on the V4 oracle current-pipeline view.
 *
 * <p>The R8 floor protects the +125% improvement landed via the
 * divisor-7 width-aware cap relaxation at {@code ChannelNudgingPass.allocateTracks}
 * lines 539-548. Pre-fix V4-oracle R8 spread_ratio was 0.135 (4 hub-to-Producer
 * downlinks bunched in 31 px of a 230 px corridor). Post-fix R8 = 0.304 (downlinks
 * fan out to span 70 px). Floor 0.25 = post-fix 0.304 minus 0.05 stateless-fixture
 * parity buffer (per precedent: JUnit re-routing of the same fixture
 * topology can drift M5 by ±1 unit due to EMF iteration order non-determinism in
 * RoutingPipeline.buildConnectionRoutingOrder tiebreakers; same delta expected for
 * R8) minus 0.005 for corridor-diversity tiebreaker non-determinism.
 *
 * <p><b>Predecessor work closed:</b>
 * <ul>
 *   <li>M1 visible-segment-length guard (closed 2026-04-27).</li>
 *   <li>Coincident-regression bisect (closed 2026-04-28) — named the primary
 *       responsible commit.</li>
 *   <li>Coincident-regression surgical fix (closed 2026-04-28) — Approach 3
 *       stage 4.7q reconciler.</li>
 *   <li>Hub-port-quality regression test (closed 2026-04-29) — pinned HPQ + M5
 *       + M1 floors.</li>
 *   <li>Wide-corridor-utilization fix (closed 2026-04-30) — divisor-7
 *       width-aware cap relaxation; R8 0.135 → 0.304.</li>
 *   <li>This test — pins R8, the 4th release-gate metric.</li>
 * </ul>
 *
 * <p><b>Rationale anchors:</b>
 * <ul>
 *   <li>The metric-and-regression-test-together canonical pattern.</li>
 *   <li>The four-stage release-gate sequence.</li>
 *   <li>R8 corridor-utilisation slot (inserted between R7 port-allocation and
 *       R9 label-overlap; R10 is now edge-crossings).</li>
 *   <li>The silent-failure protocol.</li>
 * </ul>
 */
public class V4OracleCorridorUtilisationRegressionTest {

    /**
     * R8 corridor-utilisation floor (divisor-7-fix closure 2026-04-30 anchor + JUnit-MCP
     * parity buffer).
     *
     * <p>Closure live-MCP actual: R8 = 0.304 (spike § 5.4
     * post-divisor-7-fix measurement). v1.3-baseline R8 = 1.013 (saturated, but with
     * catastrophic HPQ = 0.18 — chasing v1.3's R8 would re-introduce its hub-port
     * quality collapse). V4-manual oracle R8 = 0.565 (the user-perceived target;
     * tracked separately as the aspirational quality bar, NOT this floor).
     *
     * <p>Floor 0.25 = post-fix 0.304 minus 0.05 stateless-fixture parity buffer minus
     * 0.005 corridor-diversity tiebreaker non-determinism. The floor PROTECTS
     * the divisor-7 win against future routing changes that silently re-tighten the
     * width-aware cap formula (e.g., a divisor change from 7 → 8 would bring R8 back
     * down to ~0.20 and fire this assertion).
     */
    private static final double R8_FLOOR = 0.25;

    private ViewFixture fixture;
    private RoutingPipeline pipeline;
    private LayoutQualityAssessor assessor;

    /**
     * Live-MCP {@code auto-route-connections} perimeterMargin handler default. See
     * {@link V4OracleQualityRegressionTest#LIVE_MCP_PERIMETER_MARGIN} for derivation.
     */
    private static final int LIVE_MCP_PERIMETER_MARGIN = 50;

    @Before
    public void setUp() throws IOException {
        fixture = ViewFixture.load("testdata/v4-integration-architecture-oracle-fixture.json");
        pipeline = new RoutingPipeline(
                RoutingPipeline.DEFAULT_BEND_PENALTY,
                RoutingPipeline.DEFAULT_MARGIN,
                RoutingPipeline.DEFAULT_CONGESTION_WEIGHT,
                LIVE_MCP_PERIMETER_MARGIN);
        assessor = new LayoutQualityAssessor();
    }

    @Test
    public void v4OracleCorridorUtilisationScore_atOrAboveStory5DivisorSevenFloor() {
        LayoutAssessmentResult result = routeAndAssess();
        double actual = result.corridorUtilisationScore();
        assertTrue("V4 oracle corridor-utilisation score regressed below Story #5 "
                + "divisor-7 floor " + R8_FLOOR + ": actual=" + actual
                + " (Story #5 closure 2026-04-30 divisor-7 inline-fix produced 0.304 — "
                + "investigate ChannelNudgingPass.allocateTracks lines 539-548 dynamicCap "
                + "formula or upstream A* corridor-diversity tiebreaker)",
                actual >= R8_FLOOR);
    }

    // ---- helpers (mirrors V4OracleQualityRegressionTest.routeAndAssess exactly) ----

    private LayoutAssessmentResult routeAndAssess() {
        // Group/non-group split: groups go to per-connection groupBoundaries
        // (group-wall clearance cost), NOT to the obstacles list (which is non-group-only).
        // See V4OracleQualityRegressionTest.routeAndAssess for the rationale.
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

        // Mirror V4OracleQualityRegressionTest force=true semantics: routed + violated.
        Map<String, List<AbsoluteBendpointDto>> routedBps = new LinkedHashMap<>();
        routedBps.putAll(routingResult.routed());
        routedBps.putAll(routingResult.violatedRoutes());

        System.out.println("[V4OracleCorridorUtilisationRegressionTest] routing yield: "
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
            if (bps == null) continue;
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

        // includeViolatorIds=false: this test only asserts on the scalar
        // corridorUtilisationScore; per-channel CorridorUtilisationDetail records
        // are not inspected (code-review L2 2026-05-03 — avoid building 12 unused
        // detail objects per test run).
        return assessor.assess(nodes, connections, false);
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
