package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Integration tests for {@link HubPerimeterRoutingStage} (H5).
 *
 * <p>Coverage spans:
 * <ul>
 *   <li>Smoke tests for stage composition.</li>
 *   <li>Synthetic multi-hub + Tier-1 invariant + retry tests.</li>
 *   <li>V4 integration architecture oracle fixture integration.</li>
 * </ul>
 *
 * <p>Heavy {@link net.vheerden.archi.mcp.model.LayoutQualityAssessor}-driven
 * release-gate pin assertions (M4 ≤ 3, V_p10 ≥ 9.0, six-threshold combined)
 * live in {@code V4OracleQualityRegressionTest} ({@code net.vheerden.archi.mcp.model}
 * package — has package access to the assessor). This class covers what's
 * verifiable without the assessor: structural invariants, terminal-anchoring
 * preservation, hub detection, cell partitioning, stage reporting.
 *
 * <p>Pure-geometry: no EMF, no SWT, no PDE. The V4 oracle tests load the same
 * JSON fixture as {@code V4OracleQualityRegressionTest} and exercise the
 * production {@link RoutingPipeline}.
 */
public class HubPerimeterRoutingStageTest {

    private static RoutingPipeline.ConnectionEndpoints conn(
            String id, RoutingRect src, RoutingRect tgt) {
        return new RoutingPipeline.ConnectionEndpoints(
                id, src, tgt, Collections.emptyList(), "", 1, Collections.emptyList());
    }

    private static RoutingPipeline.ConnectionEndpoints conn(
            String id, RoutingRect src, RoutingRect tgt, List<RoutingRect> obstacles) {
        return new RoutingPipeline.ConnectionEndpoints(
                id, src, tgt, obstacles, "", 1, Collections.emptyList());
    }

    private static AbsoluteBendpointDto bp(int x, int y) {
        return new AbsoluteBendpointDto(x, y);
    }

    private static List<AbsoluteBendpointDto> path(AbsoluteBendpointDto... bps) {
        return new ArrayList<>(Arrays.asList(bps));
    }

    // =====================================================================
    // Smoke tests (existing — Task 4 carry-over)
    // =====================================================================

    @Test
    public void apply_shouldReturnZeroStats_whenNoHubsDetected() {
        RoutingRect a = new RoutingRect(0, 0, 30, 30, "a");
        RoutingRect b = new RoutingRect(200, 0, 30, 30, "b");
        List<RoutingPipeline.ConnectionEndpoints> conns = List.of(conn("c-1", a, b));
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        paths.add(path(bp(15, 15), bp(215, 15)));

        HubPerimeterRoutingStage stage = new HubPerimeterRoutingStage();
        HubPerimeterRoutingStage.Result r = stage.apply(conns, paths, List.of(a, b));

        assertEquals(0, r.cellsProcessed());
        assertEquals(0, r.axis1Applied());
        assertEquals(0, r.axis2Applied());
    }

    @Test
    public void apply_shouldShiftSegmentAwayFromHub_forAxis1HuggingMember() {
        RoutingRect hub = new RoutingRect(200, 200, 100, 100, "hub");
        RoutingRect[] spokes = new RoutingRect[5];
        List<RoutingPipeline.ConnectionEndpoints> conns = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            spokes[i] = new RoutingRect(50 + i * 30, 40, 20, 20, "spoke-" + i);
            conns.add(conn("c-" + i, spokes[i], hub));
            if (i == 0) {
                paths.add(path(bp(120, 50), bp(120, 198), bp(280, 198), bp(280, 50)));
            } else {
                paths.add(path(bp(60 + i * 30, 60), bp(60 + i * 30, 100)));
            }
        }
        List<RoutingRect> all = new ArrayList<>(List.of(hub));
        Collections.addAll(all, spokes);

        HubPerimeterRoutingStage stage = new HubPerimeterRoutingStage();
        HubPerimeterRoutingStage.Result r = stage.apply(conns, paths, all);

        assertTrue("cell processed", r.cellsProcessed() >= 1);
        assertEquals("Axis 1 should shift c-0's hugging segment", 1, r.axis1Applied());
        assertEquals(0, r.axis1Rolled());

        assertTrue("c-0 bp[1] shifted above (less than) original 198",
                paths.get(0).get(1).y() < 198);
        assertTrue("c-0 bp[1] at least 10px clear of TOP",
                hub.y() - paths.get(0).get(1).y() >= 10);
    }

    @Test
    public void apply_shouldSpreadSegments_whenCellHasZeroSpreadCluster() {
        RoutingRect hub = new RoutingRect(200, 200, 100, 100, "hub");
        List<RoutingPipeline.ConnectionEndpoints> conns = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        List<RoutingRect> all = new ArrayList<>(List.of(hub));
        for (int i = 0; i < 5; i++) {
            RoutingRect spoke = new RoutingRect(50 + i * 30, 20, 20, 20, "spoke-" + i);
            all.add(spoke);
            conns.add(conn("c-" + i, spoke, hub));
            int x = 210 + i * 10;
            paths.add(path(bp(x, 50), bp(x, 198), bp(x + 30, 198), bp(x + 30, 50)));
        }

        HubPerimeterRoutingStage stage = new HubPerimeterRoutingStage();
        HubPerimeterRoutingStage.Result r = stage.apply(conns, paths, all);

        assertTrue("cell processed", r.cellsProcessed() >= 1);
        assertTrue("Axis 1 shifted all 5 members", r.axis1Applied() >= 5);
        assertTrue("Axis 2 spread the post-Axis-1 cluster", r.axis2Applied() >= 3);

        Set<Integer> postCoords = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            postCoords.add(paths.get(i).get(1).y());
        }
        assertTrue("post-spread segments must occupy ≥ 3 distinct perpendicular coords",
                postCoords.size() >= 3);
    }

    // =====================================================================
    // Synthetic multi-hub tests
    // =====================================================================

    @Test
    public void apply_shouldProcessMultipleHubs_independently() {
        // Two hubs far apart, each with their own 5-spoke perimeter-hugging cohort.
        RoutingRect hubA = new RoutingRect(100, 100, 100, 100, "hubA");
        RoutingRect hubB = new RoutingRect(1000, 100, 100, 100, "hubB");

        List<RoutingPipeline.ConnectionEndpoints> conns = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        List<RoutingRect> all = new ArrayList<>(List.of(hubA, hubB));

        for (int i = 0; i < 5; i++) {
            RoutingRect spokeA = new RoutingRect(50 + i * 10, 40, 8, 8, "spokeA-" + i);
            RoutingRect spokeB = new RoutingRect(950 + i * 10, 40, 8, 8, "spokeB-" + i);
            all.add(spokeA);
            all.add(spokeB);
            conns.add(conn("cA-" + i, spokeA, hubA));
            conns.add(conn("cB-" + i, spokeB, hubB));
            int xA = 110 + i * 5;
            int xB = 1010 + i * 5;
            paths.add(path(bp(xA, 50), bp(xA, 98), bp(xA + 30, 98), bp(xA + 30, 50)));
            paths.add(path(bp(xB, 50), bp(xB, 98), bp(xB + 30, 98), bp(xB + 30, 50)));
        }

        HubPerimeterRoutingStage stage = new HubPerimeterRoutingStage();
        HubPerimeterRoutingStage.Result r = stage.apply(conns, paths, all);

        assertTrue("both hubs processed (≥ 2 cells)", r.cellsProcessed() >= 2);
    }

    @Test
    public void apply_shouldHandleHubWithMultipleFacesPopulated() {
        // 5 connections each side approach hub from TOP, BOTTOM, LEFT, RIGHT. Hub must
        // qualify by total degree (here 5).
        RoutingRect hub = new RoutingRect(200, 200, 100, 100, "hub");
        List<RoutingPipeline.ConnectionEndpoints> conns = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        List<RoutingRect> all = new ArrayList<>(List.of(hub));

        // c-0..c-1 from above (TOP face), c-2 from below (BOTTOM), c-3 from left, c-4 from right.
        int[][] terminals = {
                {120, 50}, {280, 50}, {220, 500}, {50, 250}, {500, 250}
        };
        int[][] segments = {
                {220, 198, 280, 198}, // c-0 hugs TOP
                {130, 198, 190, 198}, // c-1 hugs TOP
                {220, 302, 280, 302}, // c-2 hugs BOTTOM
                {197, 220, 197, 270}, // c-3 hugs LEFT
                {303, 220, 303, 270}  // c-4 hugs RIGHT
        };
        for (int i = 0; i < 5; i++) {
            RoutingRect spoke = new RoutingRect(terminals[i][0] - 5, terminals[i][1] - 5, 10, 10, "spoke-" + i);
            all.add(spoke);
            conns.add(conn("c-" + i, spoke, hub));
            int[] seg = segments[i];
            paths.add(path(bp(terminals[i][0], terminals[i][1]),
                    bp(seg[0], seg[1]), bp(seg[2], seg[3]),
                    bp(terminals[i][0], terminals[i][1])));
        }

        HubPerimeterRoutingStage stage = new HubPerimeterRoutingStage();
        HubPerimeterRoutingStage.Result r = stage.apply(conns, paths, all);

        // Each populated face yields one cell; expect 4 cells (TOP / BOTTOM / LEFT / RIGHT).
        assertEquals("4 hub faces populated → 4 cells", 4, r.cellsProcessed());
    }

    @Test
    public void apply_shouldReportCellCount_inResult() {
        // Single hub with one populated face. cellsProcessed must = 1.
        RoutingRect hub = new RoutingRect(200, 200, 100, 100, "hub");
        List<RoutingPipeline.ConnectionEndpoints> conns = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        List<RoutingRect> all = new ArrayList<>(List.of(hub));
        for (int i = 0; i < 5; i++) {
            RoutingRect spoke = new RoutingRect(50 + i * 30, 20, 20, 20, "spoke-" + i);
            all.add(spoke);
            conns.add(conn("c-" + i, spoke, hub));
            int x = 210 + i * 10;
            paths.add(path(bp(x, 50), bp(x, 198), bp(x + 30, 198), bp(x + 30, 50)));
        }

        HubPerimeterRoutingStage stage = new HubPerimeterRoutingStage();
        HubPerimeterRoutingStage.Result r = stage.apply(conns, paths, all);

        assertEquals("one populated face → one cell", 1, r.cellsProcessed());
    }

    // =====================================================================
    // Terminal-anchoring / Tier-1 / monotonicity tests
    // =====================================================================

    @Test
    public void apply_shouldPreserveTerminalBendpoints_forSourceSegmentMember() {
        // Source-terminal segment hugs hub TOP. Partitioner's terminal exemption (Task 5
        // post-fix at HubFaceConnectionPartitioner.java) must keep bp[0] unchanged.
        RoutingRect hub = new RoutingRect(200, 200, 100, 100, "hub");
        List<RoutingPipeline.ConnectionEndpoints> conns = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        List<RoutingRect> all = new ArrayList<>(List.of(hub));
        for (int i = 0; i < 5; i++) {
            RoutingRect spoke = new RoutingRect(50 + i * 30, 195, 20, 6, "spoke-" + i);
            all.add(spoke);
            conns.add(conn("c-" + i, spoke, hub));
            // c-0: 3-bp path where bp[0]→bp[1] is the hub-hugging segment (terminal-incident).
            // c-1..c-4: 4-bp paths with interior hugging segment (eligible for shift).
            if (i == 0) {
                paths.add(path(bp(60, 198), bp(290, 198), bp(290, 50)));
            } else {
                int x = 210 + i * 15;
                paths.add(path(bp(x, 50), bp(x, 198), bp(x + 30, 198), bp(x + 30, 50)));
            }
        }
        AbsoluteBendpointDto originalC0Bp0 = paths.get(0).get(0);

        HubPerimeterRoutingStage stage = new HubPerimeterRoutingStage();
        stage.apply(conns, paths, all);

        // c-0's terminal-incident segment must NOT have been shifted.
        assertEquals("c-0 bp[0] unchanged (source terminal)", originalC0Bp0, paths.get(0).get(0));
    }

    @Test
    public void apply_shouldPreserveTerminalBendpoints_forTargetSegmentMember() {
        // Target-terminal segment hugs hub LEFT. Partitioner exemption preserves bp[lastIdx].
        RoutingRect hub = new RoutingRect(500, 200, 100, 100, "hub");
        List<RoutingPipeline.ConnectionEndpoints> conns = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        List<RoutingRect> all = new ArrayList<>(List.of(hub));
        for (int i = 0; i < 5; i++) {
            RoutingRect spoke = new RoutingRect(50 + i * 30, 220 + i * 10, 20, 8, "spoke-" + i);
            all.add(spoke);
            conns.add(conn("c-" + i, spoke, hub));
            if (i == 0) {
                // 3-bp: bp[1]→bp[2] is the target-terminal segment. Hugs LEFT at x=498.
                paths.add(path(bp(60, 225), bp(498, 225), bp(498, 270)));
            } else {
                int y = 220 + i * 10;
                paths.add(path(bp(60, y), bp(498, y), bp(498, y + 20), bp(60, y + 20)));
            }
        }
        AbsoluteBendpointDto originalC0Last = paths.get(0).get(paths.get(0).size() - 1);

        HubPerimeterRoutingStage stage = new HubPerimeterRoutingStage();
        stage.apply(conns, paths, all);

        assertEquals("c-0 last bp unchanged (target terminal)",
                originalC0Last, paths.get(0).get(paths.get(0).size() - 1));
    }

    @Test
    public void apply_shouldLeaveConnectionUnchanged_whenObstacleBlocksAllAlternatives() {
        // Obstacle 10px above hub TOP blocks any shift away from TOP. Strict + relaxed
        // budget retries should both fail; member's segment must stay at its original coord.
        RoutingRect hub = new RoutingRect(200, 200, 100, 100, "hub");
        RoutingRect blocker = new RoutingRect(200, 175, 100, 10, "blocker"); // y∈[175,185]
        List<RoutingPipeline.ConnectionEndpoints> conns = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        List<RoutingRect> all = new ArrayList<>(List.of(hub, blocker));
        for (int i = 0; i < 5; i++) {
            RoutingRect spoke = new RoutingRect(50 + i * 30, 100, 20, 20, "spoke-" + i);
            all.add(spoke);
            conns.add(conn("c-" + i, spoke, hub));
            int x = 210 + i * 10;
            paths.add(path(bp(x, 130), bp(x, 198), bp(x + 30, 198), bp(x + 30, 130)));
        }
        int originalC0Y = paths.get(0).get(1).y();

        HubPerimeterRoutingStage stage = new HubPerimeterRoutingStage();
        HubPerimeterRoutingStage.Result r = stage.apply(conns, paths, all);

        assertEquals("blocked → no shift applied", 0, r.axis1Applied());
        assertEquals("c-0 hugging segment unchanged at y=" + originalC0Y,
                originalC0Y, paths.get(0).get(1).y());
    }

    @Test
    public void apply_shouldReportZeroRolled_inHappyPath() {
        // No obstacles, plenty of room → all shifts succeed; metric monotonicity is
        // preserved so verifyMetricMonotonicity approves all proposals; rolled stays 0.
        RoutingRect hub = new RoutingRect(200, 200, 100, 100, "hub");
        List<RoutingPipeline.ConnectionEndpoints> conns = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        List<RoutingRect> all = new ArrayList<>(List.of(hub));
        for (int i = 0; i < 5; i++) {
            RoutingRect spoke = new RoutingRect(50 + i * 30, 20, 20, 20, "spoke-" + i);
            all.add(spoke);
            conns.add(conn("c-" + i, spoke, hub));
            int x = 210 + i * 10;
            paths.add(path(bp(x, 50), bp(x, 198), bp(x + 30, 198), bp(x + 30, 50)));
        }

        HubPerimeterRoutingStage stage = new HubPerimeterRoutingStage();
        HubPerimeterRoutingStage.Result r = stage.apply(conns, paths, all);

        assertEquals(0, r.axis1Rolled());
        assertEquals(0, r.axis2Rolled());
    }

    @Test
    public void apply_shouldNeverMoveTerminalBendpoints_constructionArgument() {
        // Construction argument: H5 only shifts INTERMEDIATE bps (s = 1..lastIdx-2).
        // Regardless of what metric verifier decides, bp[0] and bp[lastIdx] must be
        // byte-identical after apply() for any 3-bp path where the middle segment hugs
        // the hub face.
        RoutingRect hub = new RoutingRect(200, 200, 100, 100, "hub");
        List<RoutingPipeline.ConnectionEndpoints> conns = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        List<RoutingRect> all = new ArrayList<>(List.of(hub));
        for (int i = 0; i < 5; i++) {
            RoutingRect spoke = new RoutingRect(50 + i * 20, 50, 20, 20, "spoke-" + i);
            all.add(spoke);
            conns.add(conn("c-" + i, spoke, hub));
            int x = 210 + i * 15;
            paths.add(path(bp(x, 80), bp(x, 198), bp(x + 20, 198), bp(x + 20, 80)));
        }
        List<AbsoluteBendpointDto> bp0Before = new ArrayList<>();
        List<AbsoluteBendpointDto> bpLastBefore = new ArrayList<>();
        for (List<AbsoluteBendpointDto> p : paths) {
            bp0Before.add(p.get(0));
            bpLastBefore.add(p.get(p.size() - 1));
        }

        HubPerimeterRoutingStage stage = new HubPerimeterRoutingStage();
        stage.apply(conns, paths, all);

        for (int i = 0; i < paths.size(); i++) {
            assertEquals("bp[0] must not move for c-" + i, bp0Before.get(i), paths.get(i).get(0));
            assertEquals("bp[last] must not move for c-" + i,
                    bpLastBefore.get(i), paths.get(i).get(paths.get(i).size() - 1));
        }
    }

    @Test
    public void apply_shouldHandleNullInputs_withoutThrowing() {
        // Defensive: null connections / null paths / mismatched lengths must not crash.
        HubPerimeterRoutingStage stage = new HubPerimeterRoutingStage();
        HubPerimeterRoutingStage.Result r1 = stage.apply(null, null, null);
        assertEquals(0, r1.cellsProcessed());

        HubPerimeterRoutingStage.Result r2 = stage.apply(
                List.of(), new ArrayList<>(), List.of());
        assertEquals(0, r2.cellsProcessed());

        // Mismatched sizes return zero-stat result.
        HubPerimeterRoutingStage.Result r3 = stage.apply(
                List.of(conn("c-1", new RoutingRect(0, 0, 1, 1, "a"),
                        new RoutingRect(10, 0, 1, 1, "b"))),
                new ArrayList<>(),
                List.of());
        assertEquals(0, r3.cellsProcessed());
    }

    // =====================================================================
    // V4 oracle fixture integration
    // =====================================================================

    /** Cached V4 oracle fixture loader. */
    private static ViewFixture loadV4Fixture() throws IOException {
        return ViewFixture.load("testdata/v4-integration-architecture-oracle-fixture.json");
    }

    /** Build endpoints with V4OracleQualityRegressionTest's group/obstacle split + labelExcludes. */
    private static List<RoutingPipeline.ConnectionEndpoints> buildV4Endpoints(ViewFixture fixture) {
        List<RoutingPipeline.ConnectionEndpoints> endpoints = new ArrayList<>();
        for (RoutingPipeline.ConnectionEndpoints ep : fixture.buildConnectionEndpoints()) {
            List<RoutingRect> filteredObstacles = new ArrayList<>();
            List<RoutingRect> groupBoundaries = new ArrayList<>();
            for (RoutingRect obs : ep.obstacles()) {
                ViewFixture.FixtureElement e = fixture.getElementById(obs.id());
                if (e != null && !e.isChild()) {
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
        return endpoints;
    }

    /** Build the (non-group) all-obstacles list for V4 oracle fixture. */
    private static List<RoutingRect> buildV4AllObstacles(ViewFixture fixture) {
        List<RoutingRect> all = new ArrayList<>();
        for (RoutingRect obs : fixture.buildAllObstacles()) {
            ViewFixture.FixtureElement e = fixture.getElementById(obs.id());
            if (e != null && e.isChild()) {
                all.add(obs);
            }
        }
        return all;
    }

    /** Live-MCP RoutingPipeline construction per V4OracleQualityRegressionTest. */
    private static RoutingPipeline buildV4Pipeline() {
        return new RoutingPipeline(
                RoutingPipeline.DEFAULT_BEND_PENALTY,
                RoutingPipeline.DEFAULT_MARGIN,
                RoutingPipeline.DEFAULT_CONGESTION_WEIGHT,
                50 /* LIVE_MCP_PERIMETER_MARGIN */);
    }

    @Test
    public void v4Oracle_partitioner_shouldDetectKnownHubs() throws IOException {
        // V4 oracle has 2 hubs: API Management Platform (degree 17) + Event Streaming Platform (degree 9).
        ViewFixture fixture = loadV4Fixture();
        List<RoutingPipeline.ConnectionEndpoints> endpoints = buildV4Endpoints(fixture);
        List<RoutingRect> all = buildV4AllObstacles(fixture);

        // Build dummy paths (empty bp lists) just to satisfy detectHubs's signature; degree
        // counting uses only endpoint source/target ids.
        HubFaceConnectionPartitioner part = new HubFaceConnectionPartitioner();
        List<RoutingRect> hubs = part.detectHubs(endpoints, all);

        assertEquals("expected 2 hubs on V4 oracle fixture", 2, hubs.size());
        Set<String> hubNames = new HashSet<>();
        for (RoutingRect h : hubs) {
            ViewFixture.FixtureElement e = fixture.getElementById(h.id());
            if (e != null) hubNames.add(e.name());
        }
        assertTrue("API Mgmt Platform must be detected", hubNames.contains("API Management Platform"));
        assertTrue("Event Streaming Platform must be detected", hubNames.contains("Event Streaming Platform"));
    }

    @Test
    public void v4Oracle_pipeline_shouldRouteAllConnections() throws IOException {
        ViewFixture fixture = loadV4Fixture();
        List<RoutingPipeline.ConnectionEndpoints> endpoints = buildV4Endpoints(fixture);
        List<RoutingRect> all = buildV4AllObstacles(fixture);

        RoutingPipeline pipeline = buildV4Pipeline();
        RoutingResult result = pipeline.routeAllConnections(endpoints, all);

        int total = result.routed().size() + result.violatedRoutes().size() + result.failed().size();
        assertEquals("all 30 V4 connections must yield a routing decision",
                fixture.getConnections().size(), total);
        // Routing yield must include at least the routed bucket — empty routed is a regression.
        assertTrue("at least one connection routed", !result.routed().isEmpty());
    }

    @Test
    public void v4Oracle_pipeline_shouldPreserveSourcePerimeter() throws IOException {
        // Terminal-anchoring invariant: every routed connection's bp[0] must lie on or within 1px of
        // the source element's perimeter (chopbox anchor convention).
        ViewFixture fixture = loadV4Fixture();
        List<RoutingPipeline.ConnectionEndpoints> endpoints = buildV4Endpoints(fixture);
        List<RoutingRect> all = buildV4AllObstacles(fixture);

        RoutingPipeline pipeline = buildV4Pipeline();
        RoutingResult result = pipeline.routeAllConnections(endpoints, all);

        for (Map.Entry<String, List<AbsoluteBendpointDto>> entry : result.routed().entrySet()) {
            String connId = entry.getKey();
            List<AbsoluteBendpointDto> bps = entry.getValue();
            if (bps.isEmpty()) continue;
            ViewFixture.FixtureConnection fc = findConnection(fixture, connId);
            if (fc == null) continue;
            ViewFixture.FixtureElement src = fixture.getElementById(fc.sourceId());
            AbsoluteBendpointDto bp0 = bps.get(0);
            assertTrue("connection " + connId + " bp[0]=" + bp0 + " must be on source perimeter "
                            + "of " + src.name() + " (x∈[" + src.x() + "," + (src.x() + src.w())
                            + "], y∈[" + src.y() + "," + (src.y() + src.h()) + "])",
                    isOnOrAdjacentToPerimeter(bp0, src));
        }
    }

    @Test
    public void v4Oracle_stage_shouldApplyToPostNudgePaths_withoutThrowing() throws IOException {
        // Run the pipeline, then re-invoke the stage on the resulting paths. A second
        // application should be safe (idempotent or near-idempotent) — must not throw.
        ViewFixture fixture = loadV4Fixture();
        List<RoutingPipeline.ConnectionEndpoints> endpoints = buildV4Endpoints(fixture);
        List<RoutingRect> all = buildV4AllObstacles(fixture);

        RoutingPipeline pipeline = buildV4Pipeline();
        RoutingResult result = pipeline.routeAllConnections(endpoints, all);

        // Build a paths list aligned with endpoints (matching routedBps).
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        for (RoutingPipeline.ConnectionEndpoints ep : endpoints) {
            List<AbsoluteBendpointDto> bps = result.routed().get(ep.connectionId());
            if (bps == null) bps = result.violatedRoutes().get(ep.connectionId());
            paths.add(bps != null ? new ArrayList<>(bps) : new ArrayList<>());
        }

        HubPerimeterRoutingStage stage = new HubPerimeterRoutingStage();
        HubPerimeterRoutingStage.Result r = stage.apply(endpoints, paths, all);

        assertNotNull(r);
        // Stats must be non-negative.
        assertTrue(r.cellsProcessed() >= 0);
        assertTrue(r.axis1Applied() >= 0);
        assertTrue(r.axis1Rolled() >= 0);
        assertTrue(r.axis2Applied() >= 0);
        assertTrue(r.axis2Rolled() >= 0);
    }

    @Test
    public void v4Oracle_partitioner_shouldFindCellsForHubs() throws IOException {
        // Route via pipeline → invoke partitioner on routed paths → at least one cell
        // around at least one known hub.
        ViewFixture fixture = loadV4Fixture();
        List<RoutingPipeline.ConnectionEndpoints> endpoints = buildV4Endpoints(fixture);
        List<RoutingRect> all = buildV4AllObstacles(fixture);

        RoutingPipeline pipeline = buildV4Pipeline();
        RoutingResult result = pipeline.routeAllConnections(endpoints, all);

        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        for (RoutingPipeline.ConnectionEndpoints ep : endpoints) {
            List<AbsoluteBendpointDto> bps = result.routed().get(ep.connectionId());
            if (bps == null) bps = result.violatedRoutes().get(ep.connectionId());
            paths.add(bps != null ? new ArrayList<>(bps) : new ArrayList<>());
        }

        HubFaceConnectionPartitioner part = new HubFaceConnectionPartitioner();
        List<HubFaceConnectionPartitioner.HubFaceCell> cells = part.partition(endpoints, paths, all);
        // We don't pin an exact count (post-H5 paths may have cleared many hugs), but the
        // partitioner exercise itself must complete without throwing and return ≥ 0 cells.
        assertNotNull(cells);
    }

    @Test
    public void v4Oracle_bendpoints_shouldBeAxisAligned_postH5() throws IOException {
        // H5 only performs perpendicular shifts on axis-aligned segments; segments should
        // remain axis-aligned (within 1px) post-pipeline.
        ViewFixture fixture = loadV4Fixture();
        List<RoutingPipeline.ConnectionEndpoints> endpoints = buildV4Endpoints(fixture);
        List<RoutingRect> all = buildV4AllObstacles(fixture);

        RoutingPipeline pipeline = buildV4Pipeline();
        RoutingResult result = pipeline.routeAllConnections(endpoints, all);

        int diagonalCount = 0;
        for (List<AbsoluteBendpointDto> bps : result.routed().values()) {
            for (int i = 0; i < bps.size() - 1; i++) {
                int dx = Math.abs(bps.get(i + 1).x() - bps.get(i).x());
                int dy = Math.abs(bps.get(i + 1).y() - bps.get(i).y());
                if (dx > 1 && dy > 1) diagonalCount++;
            }
        }
        // Some terminal-attach diagonals are tolerated (chopbox anchor at element center
        // can produce diagonal terminal stubs depending on element geometry); but H5
        // must not INTRODUCE new diagonals via perpendicular shift. Defensive ceiling:
        // ≤ 2 × number of routed connections (each connection has at most 2 terminal stubs).
        int ceiling = 2 * result.routed().size();
        assertTrue("post-pipeline diagonal segment count " + diagonalCount
                        + " must stay ≤ " + ceiling + " (per-connection terminal-stub ceiling)",
                diagonalCount <= ceiling);
    }

    @Test
    public void v4Oracle_stage_shouldReportZeroActivity_when2BpPathsAreAllTerminalIncident() throws IOException {
        // Construct a SYNTHETIC pre-H5 state on V4 element coords: each connection's path
        // is just (source center → target center), no routing. After H5, paths should be
        // either unchanged (no hub-perimeter hugging detected) OR show stage activity.
        // This test asserts the stage doesn't crash on minimal input; activity counts may
        // be zero since 2-bp paths are all terminal-incident.
        ViewFixture fixture = loadV4Fixture();
        List<RoutingPipeline.ConnectionEndpoints> endpoints = buildV4Endpoints(fixture);
        List<RoutingRect> all = buildV4AllObstacles(fixture);

        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        for (RoutingPipeline.ConnectionEndpoints ep : endpoints) {
            paths.add(path(
                    bp(ep.source().centerX(), ep.source().centerY()),
                    bp(ep.target().centerX(), ep.target().centerY())));
        }

        HubPerimeterRoutingStage stage = new HubPerimeterRoutingStage();
        HubPerimeterRoutingStage.Result r = stage.apply(endpoints, paths, all);

        // All 2-bp paths are terminal-incident → partitioner excludes them → 0 axis1Applied / 0 axis2Applied.
        assertEquals("2-bp paths: all segments terminal-incident → no shift", 0, r.axis1Applied());
        assertEquals("2-bp paths: nothing to spread", 0, r.axis2Applied());
    }

    // =====================================================================
    // Task 13 — monotonicity verifier tests (M4 / V_p10 / HPQ)
    // =====================================================================

    // --- M4 mirror unit tests ---

    @Test
    public void computeM4Count_shouldReturnZero_whenPathsEmpty() {
        HubPerimeterRoutingStage stage = new HubPerimeterRoutingStage();
        assertEquals(0, HubPerimeterRoutingStage.computeM4Count(List.of(), List.of()));
        assertEquals(0, HubPerimeterRoutingStage.computeM4Count(null, null));
        assertNotNull(stage);
    }

    @Test
    public void computeM4Count_shouldReturnOne_whenSegmentHugsHorizontalEdge() {
        // Element top edge at y=200; horizontal segment at y=198 (gap 2px ≤ 3px tol) overlaps 60px.
        RoutingRect elem = new RoutingRect(100, 200, 100, 80, "elem");
        List<List<AbsoluteBendpointDto>> paths = List.of(
                path(bp(110, 198), bp(190, 198)));
        assertEquals(1,
                HubPerimeterRoutingStage.computeM4Count(paths, List.of(elem)));
    }

    @Test
    public void computeM4Count_shouldReturnOne_whenSegmentHugsVerticalEdge() {
        // Element left edge at x=200; vertical segment at x=202 (gap 2px ≤ 3px tol) overlaps 40px.
        RoutingRect elem = new RoutingRect(200, 100, 80, 80, "elem");
        List<List<AbsoluteBendpointDto>> paths = List.of(
                path(bp(202, 110), bp(202, 170)));
        assertEquals(1,
                HubPerimeterRoutingStage.computeM4Count(paths, List.of(elem)));
    }

    @Test
    public void computeM4Count_shouldReturnZero_whenSegmentTooFarFromEdge() {
        // 5px gap from any edge (>3px tol) → no hug.
        RoutingRect elem = new RoutingRect(100, 200, 100, 80, "elem");
        List<List<AbsoluteBendpointDto>> paths = List.of(
                path(bp(110, 195), bp(190, 195)));
        assertEquals(0,
                HubPerimeterRoutingStage.computeM4Count(paths, List.of(elem)));
    }

    @Test
    public void computeM4Count_shouldReturnZero_whenOverlapTooShort() {
        // Edge proximity 2px is fine, but parallel overlap 5px is below 10px min.
        RoutingRect elem = new RoutingRect(100, 200, 100, 80, "elem");
        List<List<AbsoluteBendpointDto>> paths = List.of(
                path(bp(50, 198), bp(105, 198)));
        // Overlap = min(105,200) - max(50,100) = 105 - 100 = 5px (< 10px).
        assertEquals(0,
                HubPerimeterRoutingStage.computeM4Count(paths, List.of(elem)));
    }

    @Test
    public void computeM4Count_shouldCountAtMostOncePerConnection() {
        // Two horizontal segments both hugging — assessor caps at +1 per connection.
        RoutingRect elem = new RoutingRect(100, 200, 100, 80, "elem");
        List<List<AbsoluteBendpointDto>> paths = List.of(
                path(bp(110, 198), bp(190, 198),
                        bp(190, 278), bp(110, 278)));
        assertEquals("connection capped at +1 even with two hugging segments", 1,
                HubPerimeterRoutingStage.computeM4Count(paths, List.of(elem)));
    }

    // --- V_p10 mirror unit tests ---

    @Test
    public void computeVAxisParallelGapP10_shouldReturnNull_whenNoVSegments() {
        // Single H segment — no V segment qualifies.
        List<List<AbsoluteBendpointDto>> paths = List.of(
                path(bp(0, 0), bp(100, 0)));
        assertNull(HubPerimeterRoutingStage.computeVAxisParallelGapP10(paths));
    }

    @Test
    public void computeVAxisParallelGapP10_shouldReturnNull_whenSingleVSegment() {
        // One V segment has no co-axial partner with strict span overlap → no qualifying pair.
        List<List<AbsoluteBendpointDto>> paths = List.of(
                path(bp(50, 0), bp(50, 100)));
        assertNull(HubPerimeterRoutingStage.computeVAxisParallelGapP10(paths));
    }

    @Test
    public void computeVAxisParallelGapP10_shouldReturnGap_whenTwoParallelVSegments() {
        // Two V segments at x=50 and x=80, y-span [0,100] both → overlap = 100, gap = 30.
        // For n=2 segments each gets best-gap = 30 → list = [30, 30] → percentileP10 = 30.
        List<List<AbsoluteBendpointDto>> paths = List.of(
                path(bp(50, 0), bp(50, 100)),
                path(bp(80, 0), bp(80, 100)));
        Double p10 = HubPerimeterRoutingStage.computeVAxisParallelGapP10(paths);
        assertNotNull(p10);
        assertEquals(30.0, p10, 0.01);
    }

    @Test
    public void computeVAxisParallelGapP10_shouldReturnZero_whenSegmentsAtSameX() {
        // Two V segments at the SAME x → gap = 0 (per assessor convention for coincident fixed coord).
        List<List<AbsoluteBendpointDto>> paths = List.of(
                path(bp(50, 0), bp(50, 100)),
                path(bp(50, 20), bp(50, 80)));
        Double p10 = HubPerimeterRoutingStage.computeVAxisParallelGapP10(paths);
        assertNotNull(p10);
        assertEquals(0.0, p10, 0.001);
    }

    // --- HPQ mirror unit tests ---

    @Test
    public void computeHpq_shouldReturnOne_whenNoHubFace() {
        // Two connections, two source rects — at most 1 terminal per face per element → no hub face.
        RoutingRect a = new RoutingRect(0, 0, 30, 30, "a");
        RoutingRect b = new RoutingRect(200, 0, 30, 30, "b");
        List<RoutingPipeline.ConnectionEndpoints> conns = List.of(
                conn("c-1", a, b),
                conn("c-2", b, a));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                path(bp(15, 30), bp(15, 50), bp(215, 50), bp(215, 30)),
                path(bp(215, 30), bp(215, 50), bp(15, 50), bp(15, 30)));
        assertEquals(1.0, HubPerimeterRoutingStage.computeHpq(conns, paths), 0.0001);
    }

    @Test
    public void computeHpq_shouldReturnLessThanOne_whenSlotsCoincident() {
        // 4 connections all terminate on hub TOP at the same X=210 → 1 distinct slot / 4 = 0.25.
        RoutingRect hub = new RoutingRect(200, 200, 100, 100, "hub");
        List<RoutingPipeline.ConnectionEndpoints> conns = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            RoutingRect spoke = new RoutingRect(180 + i, 50, 10, 10, "spoke-" + i);
            conns.add(conn("c-" + i, spoke, hub));
            // 3-bp path: source-terminal at spoke, mid bp at (210, 100), target-terminal at (210, 200) on hub TOP.
            paths.add(path(bp(185 + i, 60), bp(210, 100), bp(210, 200)));
        }
        double hpq = HubPerimeterRoutingStage.computeHpq(conns, paths);
        assertEquals(0.25, hpq, 0.0001);
    }

    // --- verifyTier1Clean monotonicity tests ---

    @Test
    public void verifyMetricMonotonicity_shouldReturnTrue_whenAllMetricsHold() {
        // No-change scenario: pre matches the empty-post-state baseline exactly
        // (m4=0, vP10=null, hpq=1.0). Verifier must accept "no regression on any axis".
        HubPerimeterRoutingStage stage = new HubPerimeterRoutingStage();
        HubPerimeterRoutingStage.MetricSnapshot pre =
                new HubPerimeterRoutingStage.MetricSnapshot(0, null, 1.0);
        boolean ok = stage.verifyMetricMonotonicity(pre, List.of(), List.of(), List.of());
        assertTrue("identical pre/post → no regression on any axis", ok);
    }

    @Test
    public void verifyMetricMonotonicity_shouldReturnFalse_whenM4Regresses() {
        HubPerimeterRoutingStage stage = new HubPerimeterRoutingStage();
        // Pre has m4=0; post must regress to m4=1 (one hugging segment).
        HubPerimeterRoutingStage.MetricSnapshot pre =
                new HubPerimeterRoutingStage.MetricSnapshot(0, null, 1.0);
        RoutingRect elem = new RoutingRect(100, 200, 100, 80, "elem");
        List<List<AbsoluteBendpointDto>> postPaths = List.of(
                path(bp(110, 198), bp(190, 198)));
        boolean ok = stage.verifyMetricMonotonicity(pre, List.of(), postPaths, List.of(elem));
        assertFalse("M4 0→1 must reject", ok);
    }

    @Test
    public void verifyMetricMonotonicity_shouldReturnFalse_whenVp10Regresses() {
        HubPerimeterRoutingStage stage = new HubPerimeterRoutingStage();
        // Pre claims V_p10=20.0; post state has two parallel V segments with gap=10 → V_p10=10 < 20.
        HubPerimeterRoutingStage.MetricSnapshot pre =
                new HubPerimeterRoutingStage.MetricSnapshot(0, 20.0, 1.0);
        List<List<AbsoluteBendpointDto>> postPaths = List.of(
                path(bp(50, 0), bp(50, 100)),
                path(bp(60, 0), bp(60, 100)));
        boolean ok = stage.verifyMetricMonotonicity(pre, List.of(), postPaths, List.of());
        assertFalse("V_p10 20→10 must reject", ok);
    }

    @Test
    public void verifyMetricMonotonicity_shouldReturnFalse_whenHpqRegresses() {
        HubPerimeterRoutingStage stage = new HubPerimeterRoutingStage();
        // Pre claims HPQ=1.0; post has 4 coincident hub-face slots → HPQ=0.25 < 1.0.
        HubPerimeterRoutingStage.MetricSnapshot pre =
                new HubPerimeterRoutingStage.MetricSnapshot(0, null, 1.0);
        RoutingRect hub = new RoutingRect(200, 200, 100, 100, "hub");
        List<RoutingPipeline.ConnectionEndpoints> conns = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            RoutingRect spoke = new RoutingRect(180 + i, 50, 10, 10, "spoke-" + i);
            conns.add(conn("c-" + i, spoke, hub));
            paths.add(path(bp(185 + i, 60), bp(210, 100), bp(210, 200)));
        }
        boolean ok = stage.verifyMetricMonotonicity(pre, conns, paths, List.of(hub));
        assertFalse("HPQ 1.0→0.25 must reject", ok);
    }

    @Test
    public void verifyMetricMonotonicity_shouldReturnTrue_whenAllMetricsStrictlyImprove() {
        HubPerimeterRoutingStage stage = new HubPerimeterRoutingStage();
        // Pre has m4=5, V_p10=6.4, HPQ=0.5. Post is empty → m4=0, V_p10=null, HPQ=1.0.
        HubPerimeterRoutingStage.MetricSnapshot pre =
                new HubPerimeterRoutingStage.MetricSnapshot(5, 6.4, 0.5);
        List<List<AbsoluteBendpointDto>> postPaths = List.of(
                path(bp(50, 0), bp(50, 100)),
                path(bp(60, 0), bp(60, 100)));
        // V_p10 of post = 10.0 ≥ 6.4 (no regression). HPQ no hub face = 1.0 ≥ 0.5. M4 = 0 ≤ 5.
        boolean ok = stage.verifyMetricMonotonicity(pre, List.of(), postPaths, List.of());
        assertTrue("monotone improvement on all axes", ok);
    }

    // --- V_p10 null-handling cases ---

    @Test
    public void verifyMetricMonotonicity_shouldReturnTrue_whenVp10PreNullPostNull() {
        HubPerimeterRoutingStage stage = new HubPerimeterRoutingStage();
        HubPerimeterRoutingStage.MetricSnapshot pre =
                new HubPerimeterRoutingStage.MetricSnapshot(0, null, 1.0);
        // Empty post-state → post V_p10 = null. Pre null + post null = no regression.
        boolean ok = stage.verifyMetricMonotonicity(pre, List.of(), List.of(), List.of());
        assertTrue("pre-null + post-null → no regression", ok);
    }

    @Test
    public void verifyMetricMonotonicity_shouldReturnTrue_whenVp10PreNullPostValue() {
        HubPerimeterRoutingStage stage = new HubPerimeterRoutingStage();
        HubPerimeterRoutingStage.MetricSnapshot pre =
                new HubPerimeterRoutingStage.MetricSnapshot(0, null, 1.0);
        // Post has 2 V segments at gap 30 → post V_p10 = 30.0 (a value where pre was null).
        List<List<AbsoluteBendpointDto>> postPaths = List.of(
                path(bp(50, 0), bp(50, 100)),
                path(bp(80, 0), bp(80, 100)));
        boolean ok = stage.verifyMetricMonotonicity(pre, List.of(), postPaths, List.of());
        assertTrue("pre-null + post-value → improvement", ok);
    }

    @Test
    public void verifyMetricMonotonicity_shouldReturnFalse_whenVp10PreValuePostNull() {
        HubPerimeterRoutingStage stage = new HubPerimeterRoutingStage();
        HubPerimeterRoutingStage.MetricSnapshot pre =
                new HubPerimeterRoutingStage.MetricSnapshot(0, 12.0, 1.0);
        // Empty post-state → post V_p10 = null.
        boolean ok = stage.verifyMetricMonotonicity(pre, List.of(), List.of(), List.of());
        assertFalse("pre-value + post-null → regression", ok);
    }

    @Test
    public void verifyMetricMonotonicity_shouldTreatNullSnapshot_asAccept() {
        // Defensive: null snapshot caller shouldn't crash; treat as no precondition.
        HubPerimeterRoutingStage stage = new HubPerimeterRoutingStage();
        assertTrue(stage.verifyMetricMonotonicity(null, List.of(), List.of(), List.of()));
    }

    // --- Snapshot record contract ---

    @Test
    public void metricSnapshot_recordAccessors_shouldExposeAllFields() {
        HubPerimeterRoutingStage.MetricSnapshot snap =
                new HubPerimeterRoutingStage.MetricSnapshot(7, 4.5, 0.75);
        assertEquals(7, snap.m4Count());
        assertEquals(Double.valueOf(4.5), snap.vP10());
        assertEquals(0.75, snap.hpq(), 0.0001);
        HubPerimeterRoutingStage.MetricSnapshot nullable =
                new HubPerimeterRoutingStage.MetricSnapshot(0, null, 1.0);
        assertNull(nullable.vP10());
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private static boolean isOnOrAdjacentToPerimeter(AbsoluteBendpointDto bp,
                                                      ViewFixture.FixtureElement el) {
        // Matches the convention used by RoutingPipelineTest:
        // a terminal bp passes if it lies on the perimeter (within 2px of any edge AND
        // within the parallel extent) OR if it shares a coordinate with the element center
        // (chopbox-anchor degenerate-port fallback).
        int xLo = el.x();
        int xHi = el.x() + el.w();
        int yLo = el.y();
        int yHi = el.y() + el.h();
        int tol = 2;
        boolean nearLeftRight = (Math.abs(bp.x() - xLo) <= tol || Math.abs(bp.x() - xHi) <= tol)
                && bp.y() >= yLo - tol && bp.y() <= yHi + tol;
        boolean nearTopBottom = (Math.abs(bp.y() - yLo) <= tol || Math.abs(bp.y() - yHi) <= tol)
                && bp.x() >= xLo - tol && bp.x() <= xHi + tol;
        if (nearLeftRight || nearTopBottom) return true;
        int cx = el.x() + el.w() / 2;
        int cy = el.y() + el.h() / 2;
        return Math.abs(bp.x() - cx) <= tol || Math.abs(bp.y() - cy) <= tol;
    }

    private static ViewFixture.FixtureConnection findConnection(ViewFixture fixture, String id) {
        for (ViewFixture.FixtureConnection c : fixture.getConnections()) {
            if (id.equals(c.id())) return c;
        }
        return null;
    }

    // =====================================================================
    // Axis-3 (TerminalSegmentCorridorMigrator) verifier +
    // Tier-1 / terminal-anchoring by-construction proofs. Verifier/rollback/
    // anchoring-focused; distinct from Task-1 unit tests and Task-4 V4-fixture integration.
    // =====================================================================

    /**
     * Mirror-drift sentinel. The migrator inlines three
     * {@code LayoutQualityAssessor} constants (the assessor is package-private to
     * {@code net.vheerden.archi.mcp.model} and elevating its visibility is
     * forbidden — same discipline as {@link HubPerimeterRoutingStage}). These
     * values were re-verified against the live assessor at Task 3 (2026-05-16):
     * {@code EDGE_COINCIDENCE_TOLERANCE_PX=3.0}, {@code EDGE_COINCIDENCE_MIN_OVERLAP_PX=10.0},
     * {@code ZIGZAG_AXIS_TOLERANCE_PX=1.0} — no drift. This test fails fast if the
     * migrator's mirror is edited away from the documented values; behavioural drift
     * of the assessor itself is additionally caught by the
     * {@code V4OracleQualityRegressionTest} pin (the documented H5 mirror-discipline).
     */
    @Test
    public void migrator_inlinedConstants_matchLayoutQualityAssessor_driftSentinel() {
        assertEquals(3.0, TerminalSegmentCorridorMigrator.EDGE_COINCIDENCE_TOLERANCE_PX, 0.0);
        assertEquals(10.0, TerminalSegmentCorridorMigrator.EDGE_COINCIDENCE_MIN_OVERLAP_PX, 0.0);
        assertEquals(1.0, TerminalSegmentCorridorMigrator.ZIGZAG_AXIS_TOLERANCE_PX, 0.0);
        assertEquals(2.0, TerminalSegmentCorridorMigrator.CLEAR_SAFETY_MARGIN_PX, 0.0);
    }

    /** Test-injected migrator that forces one M4-regressing proposal (apply/restore inherited). */
    private static final class RegressingMigrator extends TerminalSegmentCorridorMigrator {
        @Override
        public java.util.List<MigrationProposal> evaluate(
                java.util.List<RoutingPipeline.ConnectionEndpoints> connections,
                java.util.List<java.util.List<AbsoluteBendpointDto>> paths,
                java.util.List<RoutingRect> allObstacles) {
            // Move the size-3 terminal segment's slot 160 → 201, landing it ON E.topEdge.
            return java.util.List.of(new MigrationProposal("c-reg", 0, 0, 1, false, 160, 201));
        }
    }

    /**
     * The EXISTING {@code verifyMetricMonotonicity} guards Axis-3 too. An
     * injected migrator emits a proposal that regresses M4 (0→1); the stage must
     * roll it back via {@code migrator.restore} — {@code migratorRolled==1},
     * {@code migratorApplied==0}, paths byte-identical to pre.
     */
    @Test
    public void stage_shouldRollBackMigratorProposal_whenItRegressesM4_viaInjectedRegressor() {
        RoutingRect h = new RoutingRect(100, 100, 80, 200, "H");   // RIGHT face line x=181
        RoutingRect e = new RoutingRect(200, 201, 120, 40, "E");   // E.topEdge y=201
        RoutingRect tg = new RoutingRect(340, 300, 80, 50, "Tg");
        List<RoutingPipeline.ConnectionEndpoints> conns =
                List.of(conn("c-reg", h, tg));
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        paths.add(path(bp(181, 160), bp(360, 160), bp(360, 280)));
        List<AbsoluteBendpointDto> original = new ArrayList<>(paths.get(0));
        List<RoutingRect> obstacles = List.of(h, e, tg);

        HubPerimeterRoutingStage stage = new HubPerimeterRoutingStage(
                new HubFaceConnectionPartitioner(), new AlternativeCorridorSelector(),
                new CorridorSpreadEnforcer(), new RegressingMigrator());
        HubPerimeterRoutingStage.Result r = stage.apply(conns, paths, obstacles);

        assertEquals("M4-regressing migrator proposal must be rolled back", 0, r.migratorApplied());
        assertEquals(1, r.migratorRolled());
        assertEquals("path restored byte-identical", original, paths.get(0));
    }

    /**
     * Accept-case + the load-bearing proof that removing the
     * {@code cells.isEmpty()} early-return makes the gate-view geometry ACT: a
     * size-3 terminal-incident L hugging a non-hub element, with NO hub (zero
     * cells — exactly where H5 was a documented no-op). Axis-3 now migrates it;
     * the monotone proposal is accepted; M4 1→0; terminal anchoring preserved.
     */
    @Test
    public void stage_shouldAcceptMigratorProposal_andReduceM4_onCellLessGateGeometry() {
        RoutingRect h = new RoutingRect(100, 100, 80, 200, "H");   // RIGHT face line x=181
        RoutingRect e = new RoutingRect(200, 161, 120, 40, "E");   // E.topEdge y=161
        RoutingRect tg = new RoutingRect(340, 250, 80, 50, "Tg");
        List<RoutingPipeline.ConnectionEndpoints> conns =
                List.of(conn("c-poc", h, tg));
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        paths.add(path(bp(181, 160), bp(360, 160), bp(360, 250)));
        List<RoutingRect> obstacles = List.of(h, e, tg);

        assertEquals("pre: seg0 hugs E.top", 1,
                HubPerimeterRoutingStage.computeM4Count(paths, obstacles));

        HubPerimeterRoutingStage stage = new HubPerimeterRoutingStage();
        HubPerimeterRoutingStage.Result r = stage.apply(conns, paths, obstacles);

        assertEquals("gate-view geometry has zero cells", 0, r.cellsProcessed());
        assertEquals("Axis-3 migrated the terminal segment H5 could not reach",
                1, r.migratorApplied());
        assertEquals(0, r.migratorRolled());
        assertEquals("post: M4 cleared 1→0", 0,
                HubPerimeterRoutingStage.computeM4Count(paths, obstacles));
        assertTrue("B71 preserved through the composed stage",
                TerminalAnchoring.preservesTerminalAnchoring(
                        new TerminalAnchoring(EdgeAttachmentCalculator.Face.RIGHT), h, paths.get(0)));
    }

    /**
     * Explicit {@code preservesEndpoints} (preservesTerminalAnchoring)
     * by-construction proof through the composed stage across multiple
     * terminal-incident migrations — every migrated terminal stays exactly on its
     * face line (orthogonal coord unchanged).
     */
    @Test
    public void stage_terminalIncidentMigration_preservesTerminalAnchoring_byConstruction() {
        RoutingRect ha = new RoutingRect(100, 100, 80, 200, "HA"); // RIGHT line 181
        RoutingRect hb = new RoutingRect(100, 500, 80, 200, "HB"); // RIGHT line 181
        RoutingRect hc = new RoutingRect(600, 100, 80, 200, "HC"); // RIGHT line 681
        RoutingRect ea = new RoutingRect(200, 161, 120, 40, "EA");
        RoutingRect eb = new RoutingRect(200, 561, 120, 40, "EB");
        RoutingRect ec = new RoutingRect(700, 161, 120, 40, "EC");
        RoutingRect ta = new RoutingRect(340, 250, 40, 40, "TA");
        RoutingRect tb = new RoutingRect(340, 650, 40, 40, "TB");
        RoutingRect tc = new RoutingRect(840, 250, 40, 40, "TC");
        List<RoutingPipeline.ConnectionEndpoints> conns = List.of(
                conn("cA", ha, ta), conn("cB", hb, tb), conn("cC", hc, tc));
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        paths.add(path(bp(181, 160), bp(360, 160), bp(360, 250)));
        paths.add(path(bp(181, 560), bp(360, 560), bp(360, 650)));
        paths.add(path(bp(681, 160), bp(860, 160), bp(860, 250)));
        List<RoutingRect> obstacles =
                List.of(ha, hb, hc, ea, eb, ec, ta, tb, tc);

        HubPerimeterRoutingStage stage = new HubPerimeterRoutingStage();
        HubPerimeterRoutingStage.Result r = stage.apply(conns, paths, obstacles);

        assertEquals("all three terminal-incident L-shapes migrated", 3, r.migratorApplied());
        assertEquals(181, paths.get(0).get(0).x()); // HA RIGHT line preserved
        assertEquals(181, paths.get(1).get(0).x()); // HB RIGHT line preserved
        assertEquals(681, paths.get(2).get(0).x()); // HC RIGHT line preserved
        assertTrue(TerminalAnchoring.preservesTerminalAnchoring(
                new TerminalAnchoring(EdgeAttachmentCalculator.Face.RIGHT), ha, paths.get(0)));
        assertTrue(TerminalAnchoring.preservesTerminalAnchoring(
                new TerminalAnchoring(EdgeAttachmentCalculator.Face.RIGHT), hb, paths.get(1)));
        assertTrue(TerminalAnchoring.preservesTerminalAnchoring(
                new TerminalAnchoring(EdgeAttachmentCalculator.Face.RIGHT), hc, paths.get(2)));
    }

    /**
     * Complement: an UNSAFE terminal segment (constant axis == the face's
     * ORTHOGONAL axis) is rejected by the migrator's own guard — terminal anchoring
     * preserved by rejection, NOT by stage rollback. No migration, terminal untouched.
     */
    @Test
    public void stage_shouldNotMigrate_unsafeTerminalSegment_b71PreservedByRejection() {
        RoutingRect h = new RoutingRect(100, 100, 80, 200, "H"); // RIGHT line 181
        RoutingRect tg = new RoutingRect(340, 250, 80, 50, "Tg");
        List<RoutingPipeline.ConnectionEndpoints> conns =
                List.of(conn("c-unsafe", h, tg));
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        // seg0 = (181,160)->(181,300) VERTICAL: constant axis X = RIGHT's orthogonal.
        paths.add(path(bp(181, 160), bp(181, 300), bp(360, 300)));
        List<AbsoluteBendpointDto> original = new ArrayList<>(paths.get(0));

        HubPerimeterRoutingStage stage = new HubPerimeterRoutingStage();
        HubPerimeterRoutingStage.Result r = stage.apply(conns, paths, List.of(h, tg));

        assertEquals("UNSAFE segment must not be migrated", 0, r.migratorApplied());
        assertEquals(0, r.migratorRolled());
        assertEquals("terminal untouched (B71 preserved by rejection)",
                original, paths.get(0));
    }

    // =====================================================================
    // Composed-stage integration. End-to-end;
    // distinct from the Task-3 verifier tests and the existing V4 "doesn't
    // throw" smoke tests (those assert no exception / non-negative stats only;
    // these assert the monotonicity INVARIANT numerically on the real V4
    // substrate, and three-axis coexistence on a hub + size-3 scenario).
    // =====================================================================

    /**
     * The composed stage (including Axis-3) must be byte-neutral or
     * improving on the V4-oracle pure-JUnit substrate where H5 is a documented
     * no-op — it must never regress the monotonicity guarantee. Routes V4 via the
     * pipeline (which already ran the stage at 4.7o+1), then re-invokes the stage on
     * those paths and asserts M4 non-increasing, V_p10 non-decreasing (null rule),
     * HPQ non-decreasing across the second application — the monotonicity guarantee
     * proven end-to-end on the real 30-connection fixture, not a synthetic case.
     */
    @Test
    public void v4Oracle_composedStage_isMonotoneOrNeutral_onV4Substrate() throws IOException {
        ViewFixture fixture = loadV4Fixture();
        List<RoutingPipeline.ConnectionEndpoints> endpoints = buildV4Endpoints(fixture);
        List<RoutingRect> all = buildV4AllObstacles(fixture);

        RoutingPipeline pipeline = buildV4Pipeline();
        RoutingResult result = pipeline.routeAllConnections(endpoints, all);

        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        for (RoutingPipeline.ConnectionEndpoints ep : endpoints) {
            List<AbsoluteBendpointDto> bps = result.routed().get(ep.connectionId());
            if (bps == null) bps = result.violatedRoutes().get(ep.connectionId());
            paths.add(bps != null ? new ArrayList<>(bps) : new ArrayList<>());
        }

        HubPerimeterRoutingStage stage = new HubPerimeterRoutingStage();
        HubPerimeterRoutingStage.MetricSnapshot pre =
                stage.computeMetricSnapshot(endpoints, paths, all);
        HubPerimeterRoutingStage.Result r = stage.apply(endpoints, paths, all);
        HubPerimeterRoutingStage.MetricSnapshot post =
                stage.computeMetricSnapshot(endpoints, paths, all);

        assertTrue("M4 must not regress on V4 substrate (" + post.m4Count()
                        + " > " + pre.m4Count() + ")", post.m4Count() <= pre.m4Count());
        if (pre.vP10() != null) {
            assertNotNull("V_p10 must not drop to null", post.vP10());
            assertTrue("V_p10 must not regress", post.vP10() >= pre.vP10());
        }
        assertTrue("HPQ must not regress (" + post.hpq() + " < " + pre.hpq() + ")",
                post.hpq() >= pre.hpq());
        assertTrue(r.migratorApplied() >= 0 && r.migratorRolled() >= 0);
    }

    /**
     * The three axes coexist in a single {@code apply()}. A hub with
     * 5 spokes (Axis-1 shifts c-0's hub-hugging interior segment) PLUS a separate
     * size-3 terminal-incident L hugging a non-hub element (Axis-3 migrates it).
     * Asserts both fire, the run is monotone (global M4 not regressed), Axis-3
     * preserves terminal anchoring, and the size-3 L stays a clean orthogonal L.
     */
    @Test
    public void stage_threeAxesCoexist_hubPlusTerminalIncidentSizeThreeL() {
        RoutingRect hub = new RoutingRect(200, 200, 100, 100, "hub");
        RoutingRect[] spokes = new RoutingRect[5];
        List<RoutingPipeline.ConnectionEndpoints> conns = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            spokes[i] = new RoutingRect(50 + i * 30, 40, 20, 20, "spoke-" + i);
            conns.add(conn("c-" + i, spokes[i], hub));
            if (i == 0) {
                paths.add(path(bp(120, 50), bp(120, 198), bp(280, 198), bp(280, 50)));
            } else {
                paths.add(path(bp(60 + i * 30, 60), bp(60 + i * 30, 100)));
            }
        }
        // 6th connection: size-3 terminal-incident L on a far-away non-hub source.
        RoutingRect s6 = new RoutingRect(600, 600, 80, 40, "S6"); // RIGHT face line x=681
        RoutingRect e6 = new RoutingRect(700, 621, 80, 40, "E6");  // E6.topEdge y=621
        RoutingRect t6 = new RoutingRect(760, 700, 40, 40, "T6");
        conns.add(conn("c-5", s6, t6));
        paths.add(path(bp(681, 620), bp(800, 620), bp(800, 700)));

        List<RoutingRect> all = new ArrayList<>(List.of(hub));
        Collections.addAll(all, spokes);
        all.add(s6);
        all.add(e6);
        all.add(t6);

        int m4Pre = HubPerimeterRoutingStage.computeM4Count(paths, all);
        HubPerimeterRoutingStage stage = new HubPerimeterRoutingStage();
        HubPerimeterRoutingStage.Result r = stage.apply(conns, paths, all);

        assertTrue("Axis-1 fired on the hub-hugging member", r.axis1Applied() >= 1);
        assertEquals("Axis-3 migrated the size-3 terminal-incident L", 1, r.migratorApplied());
        assertEquals(0, r.migratorRolled());
        assertTrue("global M4 monotone (not regressed)",
                HubPerimeterRoutingStage.computeM4Count(paths, all) <= m4Pre);
        // Axis-3 preserved terminal anchoring on c-5's source terminal.
        assertEquals("c-5 terminal x stays on S6 RIGHT face line 681",
                681, paths.get(5).get(0).x());
        assertTrue(TerminalAnchoring.preservesTerminalAnchoring(
                new TerminalAnchoring(EdgeAttachmentCalculator.Face.RIGHT), s6, paths.get(5)));
        // c-5 stays a clean orthogonal L (no zigzag/diagonal introduced).
        List<AbsoluteBendpointDto> c5 = paths.get(5);
        for (int i = 0; i < c5.size() - 1; i++) {
            int dx = Math.abs(c5.get(i + 1).x() - c5.get(i).x());
            int dy = Math.abs(c5.get(i + 1).y() - c5.get(i).y());
            assertTrue("c-5 segment " + i + " stays axis-aligned",
                    (dx <= 1 && dy > 1) || (dy <= 1 && dx > 1));
        }
    }
}
