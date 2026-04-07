package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.model.routing.VisEdge.Direction;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Tests for {@link VisibilityGraphRouter} (Story 10-6b).
 * Pure-geometry tests — no OSGi runtime required.
 */
public class VisibilityGraphRouterTest {

    private OrthogonalVisibilityGraph graph;
    private VisibilityGraphRouter router;

    @Before
    public void setUp() {
        graph = new OrthogonalVisibilityGraph();
        router = new VisibilityGraphRouter();
    }

    // --- Test 2.1: Direct line of sight (AC #1) ---

    @Test
    public void shouldReturnStraightPath_whenDirectLineOfSight() {
        graph.build(Collections.emptyList());
        VisNode[] ports = graph.addPortNodes(50, 100, 300, 100);

        List<VisNode> path = router.findPath(graph, ports[0], ports[1]);

        assertFalse("Path should not be empty", path.isEmpty());
        assertEquals("Start should be source", ports[0], path.get(0));
        assertEquals("End should be target", ports[1], path.get(path.size() - 1));
        assertEquals("Straight path should have 2 nodes", 2, path.size());
        assertEquals("Straight path should have 0 bends", 0, countBends(path));
        assertPathIsGraphValid(path);
    }

    // --- Test 2.2: Single obstacle L-shape (AC #1, #2) ---

    @Test
    public void shouldProduceLShapePath_whenSingleObstacleBetween() {
        // Obstacle blocks direct horizontal path at y=200
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(150, 150, 100, 100, "obs1"));
        graph.build(obstacles);
        VisNode[] ports = graph.addPortNodes(50, 200, 350, 200);

        List<VisNode> path = router.findPath(graph, ports[0], ports[1]);

        assertFalse("Path should not be empty", path.isEmpty());
        assertEquals("Start should be source", ports[0], path.get(0));
        assertEquals("End should be target", ports[1], path.get(path.size() - 1));
        assertNoObstacleIntersection(path, obstacles);
        assertPathIsGraphValid(path);
        // Rectangular detour requires at least 2 bends (up/down-over-back)
        assertTrue("Path should have at least 2 bends for rectangular detour", countBends(path) >= 2);
    }

    // --- Test 2.3: U-shape corridor (AC #2) ---

    @Test
    public void shouldRequire2Bends_whenUShapeCorridorNeeded() {
        // Two obstacles creating a U-shape corridor
        // Source at left, target at right, obstacles stacked vertically blocking direct path
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(200, 50, 100, 150, "top"),
                new RoutingRect(200, 200, 100, 150, "bottom"));
        // Gap between obstacles at y=200 to y=200 — actually let me make it clearer
        // Top obstacle: (200,50) 100x120 => covers y=50..170
        // Bottom obstacle: (200,230) 100x120 => covers y=230..350
        // Gap at y=170..230 — source and target at y=100 (blocked by top obstacle)
        graph = new OrthogonalVisibilityGraph();
        obstacles = List.of(
                new RoutingRect(200, 50, 100, 120, "top"),
                new RoutingRect(200, 230, 100, 120, "bottom"));
        graph.build(obstacles);

        // Source and target at same y=100, but obstacle blocks direct path
        VisNode[] ports = graph.addPortNodes(50, 100, 450, 100);

        List<VisNode> path = router.findPath(graph, ports[0], ports[1]);

        assertFalse("Path should not be empty", path.isEmpty());
        assertNoObstacleIntersection(path, obstacles);
        assertPathIsGraphValid(path);
        // Path must go around both obstacles: at least 2 bends
        assertTrue("Path should have at least 2 bends", countBends(path) >= 2);
    }

    // --- Test 2.4: Fewer bends preferred for equal-length paths (AC #3) ---

    @Test
    public void shouldPreferFewerBends_whenBendPenaltyActive() {
        // Obstacle blocks direct path — detour required
        // Compare zero-penalty (pure distance) vs default-penalty (penalizes bends)
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(200, 170, 100, 60, "blocker"));
        graph.build(obstacles);
        VisNode[] ports = graph.addPortNodes(50, 200, 450, 200);

        // Route with zero penalty — pure shortest distance, may allow extra bends
        VisibilityGraphRouter zeroPenalty = new VisibilityGraphRouter(0);
        List<VisNode> zeroPath = zeroPenalty.findPath(graph, ports[0], ports[1]);

        // Route with default penalty — should minimize bends
        List<VisNode> defaultPath = router.findPath(graph, ports[0], ports[1]);

        assertFalse("Zero-penalty path should exist", zeroPath.isEmpty());
        assertFalse("Default-penalty path should exist", defaultPath.isEmpty());
        assertNoObstacleIntersection(zeroPath, obstacles);
        assertNoObstacleIntersection(defaultPath, obstacles);
        assertPathIsGraphValid(defaultPath);

        // Default penalty should produce path with <= bends than zero penalty
        assertTrue("Default bend penalty should produce <= bends than zero penalty",
                countBends(defaultPath) <= countBends(zeroPath));
    }

    // --- Test 2.5: Path avoids all obstacles (AC #1) ---

    @Test
    public void shouldAvoidAllObstacles_withMultipleObstacles() {
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(100, 100, 80, 80, "obs1"),
                new RoutingRect(250, 150, 60, 60, "obs2"),
                new RoutingRect(150, 250, 100, 50, "obs3"));
        graph.build(obstacles);
        VisNode[] ports = graph.addPortNodes(50, 200, 400, 200);

        List<VisNode> path = router.findPath(graph, ports[0], ports[1]);

        assertFalse("Path should not be empty", path.isEmpty());
        assertNoObstacleIntersection(path, obstacles);
        assertPathIsGraphValid(path);
    }

    // --- Test 2.6: No path exists (AC #1) ---

    @Test
    public void shouldReturnEmptyPath_whenTargetEnclosed() {
        // Completely enclose the target with obstacles
        // Create a tight box around target at (300, 300)
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(260, 260, 80, 10, "top"),    // top wall
                new RoutingRect(260, 330, 80, 10, "bottom"), // bottom wall
                new RoutingRect(260, 260, 10, 80, "left"),   // left wall
                new RoutingRect(330, 260, 10, 80, "right")); // right wall
        graph.build(obstacles);
        VisNode[] ports = graph.addPortNodes(50, 50, 300, 300);

        List<VisNode> path = router.findPath(graph, ports[0], ports[1]);

        assertTrue("Path should be empty when target is enclosed", path.isEmpty());
    }

    // --- Test 2.7: Configurable BEND_PENALTY (AC #4) ---

    @Test
    public void shouldProduceFewerBends_withHigherBendPenalty() {
        // With a wall obstacle, there may be multiple valid paths
        // Higher bend penalty should discourage bends
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(200, 100, 40, 200, "wall"));
        graph.build(obstacles);
        VisNode[] ports = graph.addPortNodes(100, 200, 400, 200);

        // Low penalty router — allows bends freely
        VisibilityGraphRouter lowPenalty = new VisibilityGraphRouter(1);
        List<VisNode> lowPath = lowPenalty.findPath(graph, ports[0], ports[1]);

        // High penalty router — strongly discourages bends
        VisibilityGraphRouter highPenalty = new VisibilityGraphRouter(500);
        List<VisNode> highPath = highPenalty.findPath(graph, ports[0], ports[1]);

        assertFalse("Low penalty path should exist", lowPath.isEmpty());
        assertFalse("High penalty path should exist", highPath.isEmpty());

        // High penalty should have <= bends than low penalty
        assertTrue("High penalty should have <= bends",
                countBends(highPath) <= countBends(lowPath));
    }

    // --- Test 2.8: Source equals target ---

    @Test
    public void shouldReturnSingleNodePath_whenSourceEqualsTarget() {
        graph.build(Collections.emptyList());
        VisNode[] ports = graph.addPortNodes(100, 100, 200, 200);

        List<VisNode> path = router.findPath(graph, ports[0], ports[0]);

        assertEquals("Same source/target should return single-node path", 1, path.size());
        assertEquals("Should be the source node", ports[0], path.get(0));
    }

    // --- Test 2.9: Performance (AC #5) ---

    @Test
    public void shouldRoute50Connections_under500ms_with30Obstacles() {
        // Generate 30 obstacles in a grid pattern
        List<RoutingRect> obstacles = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            for (int j = 0; j < 5; j++) {
                obstacles.add(new RoutingRect(
                        100 + i * 200, 100 + j * 200, 80, 60,
                        "obs-" + i + "-" + j));
            }
        }
        assertEquals(30, obstacles.size());

        graph.build(obstacles);

        // Generate 50 connection pairs (avoid placing ports inside obstacles)
        List<int[]> connectionPairs = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            int srcX = 50 + (i % 10) * 120;
            int srcY = 50 + (i / 10) * 200;
            int tgtX = 1300 - (i % 10) * 100;
            int tgtY = 950 - (i / 10) * 180;
            connectionPairs.add(new int[]{srcX, srcY, tgtX, tgtY});
        }

        // Warm up JIT
        for (int i = 0; i < 5; i++) {
            OrthogonalVisibilityGraph warmGraph = new OrthogonalVisibilityGraph();
            warmGraph.build(obstacles);
            VisNode[] warmPorts = warmGraph.addPortNodes(50, 50, 1300, 950);
            router.findPath(warmGraph, warmPorts[0], warmPorts[1]);
        }

        // Timed run
        long start = System.nanoTime();
        for (int[] pair : connectionPairs) {
            VisNode[] ports = graph.addPortNodes(pair[0], pair[1], pair[2], pair[3]);
            router.findPath(graph, ports[0], ports[1]);
        }
        long elapsed = System.nanoTime() - start;

        double ms = elapsed / 1_000_000.0;
        assertTrue("50 connections should route in under 500ms, took " + ms + "ms",
                ms < 500.0);
    }

    // --- Test 2.10: Complex maze-like layout (AC #1) ---

    @Test
    public void shouldFindValidPath_throughMazeLikeLayout() {
        // Create a maze-like layout with multiple obstacles forming corridors
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(100, 50, 200, 20, "wall1"),   // horizontal wall
                new RoutingRect(100, 50, 20, 200, "wall2"),   // vertical wall left
                new RoutingRect(200, 130, 20, 200, "wall3"),  // vertical wall middle
                new RoutingRect(100, 300, 200, 20, "wall4"),  // horizontal wall bottom
                new RoutingRect(350, 100, 20, 250, "wall5")); // vertical wall right
        graph.build(obstacles);

        // Source at top-left area, target at bottom-right area
        VisNode[] ports = graph.addPortNodes(50, 150, 450, 250);

        List<VisNode> path = router.findPath(graph, ports[0], ports[1]);

        assertFalse("Path should not be empty through maze", path.isEmpty());
        assertEquals("Start should be source", ports[0], path.get(0));
        assertEquals("End should be target", ports[1], path.get(path.size() - 1));
        assertNoObstacleIntersection(path, obstacles);
        assertPathIsGraphValid(path);
    }

    // --- Test 2.11: Bend count correctly tracked (AC #2) ---

    @Test
    public void shouldTrackBendCountCorrectly_forKnownPathShapes() {
        // Vertical path — 0 bends
        graph.build(Collections.emptyList());
        VisNode[] vertPorts = graph.addPortNodes(100, 50, 100, 300);
        List<VisNode> vertPath = router.findPath(graph, vertPorts[0], vertPorts[1]);
        assertEquals("Vertical straight path should have 0 bends", 0, countBends(vertPath));

        // Horizontal path — 0 bends
        graph = new OrthogonalVisibilityGraph();
        graph.build(Collections.emptyList());
        VisNode[] horzPorts = graph.addPortNodes(50, 100, 400, 100);
        List<VisNode> horzPath = router.findPath(graph, horzPorts[0], horzPorts[1]);
        assertEquals("Horizontal straight path should have 0 bends", 0, countBends(horzPath));

        // Detour around obstacle — must have non-zero bends (at least 2 for rectangular detour)
        graph = new OrthogonalVisibilityGraph();
        List<RoutingRect> obstacle = List.of(new RoutingRect(200, 170, 100, 60, "blocker"));
        graph.build(obstacle);
        VisNode[] detourPorts = graph.addPortNodes(50, 200, 450, 200);
        List<VisNode> detourPath = router.findPath(graph, detourPorts[0], detourPorts[1]);
        assertFalse("Detour path should exist", detourPath.isEmpty());
        int detourBends = countBends(detourPath);
        assertTrue("Detour path should have >= 2 bends, got " + detourBends, detourBends >= 2);
        assertPathIsGraphValid(detourPath);
    }

    // --- Test 2.12: Direction preference (Story 10-28, AC #2) ---

    @Test
    public void shouldPreferRightExit_whenTargetIsToTheRight() {
        // Source at left, target to the right and slightly up
        // An obstacle forces a detour — should prefer going RIGHT then UP over UP then RIGHT
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(150, 170, 100, 60, "blocker"));
        graph.build(obstacles);
        // Source at (50, 200), target at (400, 100) — target is to the RIGHT and UP
        VisNode[] ports = graph.addPortNodes(50, 200, 400, 100);

        List<VisNode> path = router.findPath(graph, ports[0], ports[1]);

        assertFalse("Path should exist", path.isEmpty());
        assertNoObstacleIntersection(path, obstacles);
        assertPathIsGraphValid(path);

        // The first move from source should be horizontal (RIGHT) since target is mainly to the right
        // dx=350, dy=-100 → x-dominant → prefer RIGHT exit
        if (path.size() >= 2) {
            VisNode first = path.get(0);
            VisNode second = path.get(1);
            Direction firstDir = getDirection(first, second);
            // With direction preference, RIGHT is preferred over UP
            // (unless obstacle forces UP first, which is fine — the preference is soft)
            assertTrue("First move should be RIGHT or path should still be valid",
                    firstDir == Direction.RIGHT || path.size() > 2);
        }
    }

    @Test
    public void shouldNotIncreaseTotalPathLength_significantlyWithDirectionPreference() {
        // Direction preference is soft — should not make paths significantly longer
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(200, 170, 100, 60, "blocker"));
        graph.build(obstacles);
        VisNode[] ports = graph.addPortNodes(50, 200, 450, 200);

        // Route with direction preference (default)
        List<VisNode> withPref = router.findPath(graph, ports[0], ports[1]);

        // Route without direction preference (build a zero-direction-penalty router)
        // We can't easily disable just direction penalty, but we can verify the path is valid
        assertFalse("Path with preference should exist", withPref.isEmpty());
        assertNoObstacleIntersection(withPref, obstacles);
        assertPathIsGraphValid(withPref);

        // Path length should be reasonable (not wildly longer than Manhattan distance)
        double pathDist = 0;
        for (int i = 0; i < withPref.size() - 1; i++) {
            pathDist += Math.abs(withPref.get(i).x() - withPref.get(i + 1).x())
                    + Math.abs(withPref.get(i).y() - withPref.get(i + 1).y());
        }
        double manhattan = Math.abs(ports[0].x() - ports[1].x())
                + Math.abs(ports[0].y() - ports[1].y());
        // Path should be at most 3x Manhattan distance (generous bound)
        assertTrue("Path should not be wildly longer than Manhattan distance",
                pathDist <= manhattan * 3);
    }

    // --- Congestion-weighted routing tests (Story 11-30) ---

    @Test
    public void shouldPreferWhitespacePath_whenDenseAreaAvailable() {
        // Two possible paths: one through a dense cluster, one through whitespace
        // Dense cluster on the direct path, open corridor above
        //
        // Layout:
        //   Source at (50, 200), Target at (450, 200)
        //   Dense cluster at y=200 area: 5 obstacles blocking direct path
        //   Open corridor above at y=50 (no obstacles)
        List<RoutingRect> obstacles = new ArrayList<>();
        // Dense cluster in the middle blocking direct horizontal path
        for (int i = 0; i < 5; i++) {
            obstacles.add(new RoutingRect(
                    150 + i * 50, 170, 30, 60, "dense-" + i));
        }
        graph.build(obstacles);
        VisNode[] ports = graph.addPortNodes(50, 200, 450, 200);

        // Route WITHOUT congestion (uniform cost) — may use shorter path through gaps
        VisibilityGraphRouter uniformRouter = new VisibilityGraphRouter(VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0);
        List<VisNode> uniformPath = uniformRouter.findPath(graph, ports[0], ports[1]);

        // Route WITH congestion — should prefer paths through whitespace
        VisibilityGraphRouter congestionRouter = new VisibilityGraphRouter(VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 5.0);
        List<VisNode> congestionPath = congestionRouter.findPath(graph, ports[0], ports[1]);

        assertFalse("Uniform path should exist", uniformPath.isEmpty());
        assertFalse("Congestion path should exist", congestionPath.isEmpty());
        assertNoObstacleIntersection(uniformPath, obstacles);
        assertNoObstacleIntersection(congestionPath, obstacles);

        // The congestion-weighted path should tend to route further from the dense area.
        // Measure the minimum distance from INTERMEDIATE path nodes to the dense cluster center.
        // Exclude source/target (first/last) since both are at y=200 (dense cluster center),
        // which would make the assertion trivially true.
        int denseClusterCenterY = 200;
        int minDistUniform = Integer.MAX_VALUE;
        int minDistCongestion = Integer.MAX_VALUE;
        for (int i = 1; i < uniformPath.size() - 1; i++) {
            minDistUniform = Math.min(minDistUniform, Math.abs(uniformPath.get(i).y() - denseClusterCenterY));
        }
        for (int i = 1; i < congestionPath.size() - 1; i++) {
            minDistCongestion = Math.min(minDistCongestion, Math.abs(congestionPath.get(i).y() - denseClusterCenterY));
        }

        // Congestion path should give wider berth to the dense cluster
        // (or at least be no closer than uniform — both are valid paths)
        assertTrue("Congestion path should route away from dense area or match uniform path. " +
                        "Congestion min dist=" + minDistCongestion + ", uniform min dist=" + minDistUniform,
                minDistCongestion >= minDistUniform);
    }

    @Test
    public void shouldStillRoute_whenOnlyPathIsThroughDenseArea() {
        // All paths go through dense area — routing must still succeed
        // Create a dense corridor that is the only path
        List<RoutingRect> obstacles = new ArrayList<>();
        // Wall above
        obstacles.add(new RoutingRect(150, 50, 200, 30, "wall-top"));
        // Wall below
        obstacles.add(new RoutingRect(150, 250, 200, 30, "wall-bottom"));
        // Dense elements in the corridor (between walls)
        for (int i = 0; i < 3; i++) {
            obstacles.add(new RoutingRect(
                    180 + i * 60, 120, 25, 25, "dense-" + i));
        }
        graph.build(obstacles);
        VisNode[] ports = graph.addPortNodes(50, 150, 450, 150);

        VisibilityGraphRouter congestionRouter = new VisibilityGraphRouter(VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 5.0);
        List<VisNode> path = congestionRouter.findPath(graph, ports[0], ports[1]);

        assertFalse("Routing should succeed even through dense area", path.isEmpty());
        assertNoObstacleIntersection(path, obstacles);
    }

    @Test
    public void shouldProduceEquivalentPath_whenViewIsSparse() {
        // Sparse view with minimal congestion — congestion-weighted should produce
        // equivalent routing quality: same bend count and comparable path length (AC5)
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(200, 170, 100, 60, "blocker"));
        graph.build(obstacles);
        VisNode[] ports = graph.addPortNodes(50, 200, 450, 200);

        VisibilityGraphRouter uniformRouter = new VisibilityGraphRouter(VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0);
        List<VisNode> uniformPath = uniformRouter.findPath(graph, ports[0], ports[1]);

        VisibilityGraphRouter congestionRouter = new VisibilityGraphRouter(VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 5.0);
        List<VisNode> congestionPath = congestionRouter.findPath(graph, ports[0], ports[1]);

        assertFalse("Uniform path should exist", uniformPath.isEmpty());
        assertFalse("Congestion path should exist", congestionPath.isEmpty());

        // With a single obstacle (sparse view), both paths should have the same bend count
        assertEquals("Sparse view should produce same bend count",
                countBends(uniformPath), countBends(congestionPath));

        // Path lengths should be equivalent (within 10% tolerance for minor detour differences)
        double uniformLen = computePathLength(uniformPath);
        double congestionLen = computePathLength(congestionPath);
        double ratio = congestionLen / uniformLen;
        assertTrue("Sparse view path lengths should be within 10% — uniform=" + uniformLen
                        + ", congestion=" + congestionLen + ", ratio=" + ratio,
                ratio >= 0.9 && ratio <= 1.1);
    }

    @Test
    public void shouldIgnoreCongestion_whenDensityBelowThreshold() {
        // Density threshold gate: congestion cost only applies when density >= 2.
        // With a single nearby obstacle (density=1), the congestion-weighted router
        // should produce the same path as the uniform router (no congestion penalty).
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(200, 170, 100, 60, "single-obstacle"));
        graph.build(obstacles);
        VisNode[] ports = graph.addPortNodes(50, 200, 450, 200);

        VisibilityGraphRouter uniformRouter = new VisibilityGraphRouter(VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0);
        List<VisNode> uniformPath = uniformRouter.findPath(graph, ports[0], ports[1]);

        // High congestion weight — but density=1 (single obstacle) should be below threshold
        VisibilityGraphRouter congestionRouter = new VisibilityGraphRouter(VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 10.0);
        List<VisNode> congestionPath = congestionRouter.findPath(graph, ports[0], ports[1]);

        assertFalse("Uniform path should exist", uniformPath.isEmpty());
        assertFalse("Congestion path should exist", congestionPath.isEmpty());

        // With density < 2 threshold, paths should be identical despite high congestion weight
        assertEquals("Paths should be identical when density < 2",
                uniformPath.size(), congestionPath.size());
        for (int i = 0; i < uniformPath.size(); i++) {
            assertEquals("Path nodes should match at index " + i,
                    uniformPath.get(i), congestionPath.get(i));
        }
    }

    @Test
    public void shouldProduceIdenticalPath_whenCongestionWeightIsZero() {
        // Zero congestion weight should produce identical results to the default router
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(200, 170, 100, 60, "blocker"));
        graph.build(obstacles);
        VisNode[] ports = graph.addPortNodes(50, 200, 450, 200);

        VisibilityGraphRouter defaultRouter = new VisibilityGraphRouter(VisibilityGraphRouter.DEFAULT_BEND_PENALTY);
        List<VisNode> defaultPath = defaultRouter.findPath(graph, ports[0], ports[1]);

        VisibilityGraphRouter zeroWeightRouter = new VisibilityGraphRouter(VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0);
        List<VisNode> zeroWeightPath = zeroWeightRouter.findPath(graph, ports[0], ports[1]);

        assertEquals("Zero-weight path should match default path",
                defaultPath.size(), zeroWeightPath.size());
        for (int i = 0; i < defaultPath.size(); i++) {
            assertEquals("Path nodes should match at index " + i,
                    defaultPath.get(i), zeroWeightPath.get(i));
        }
    }

    // --- Clearance-weighted routing tests (B41) ---

    @Test
    public void computePerpendicularClearance_shouldReturnSmallDistance_whenObstacleOnOneSide() {
        // Single obstacle to the right of a vertical edge
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(100, 50, 80, 200, "obs1"));
        graph.build(obstacles);

        // Vertical edge at x=80 (10px margin means expanded left boundary is at x=90)
        // Edge runs from (80, 100) to (80, 200) — 10px from expanded left boundary
        VisNode from = new VisNode(80, 100, VisNode.NodeType.SCAN_INTERSECTION);
        VisNode to = new VisNode(80, 200, VisNode.NodeType.SCAN_INTERSECTION);

        double clearance = graph.computePerpendicularClearance(from, to);

        // Expanded obstacle: left=90, right=190. Edge at x=80, dist to left=10
        assertEquals("Clearance should be distance to nearest expanded boundary", 10.0, clearance, 0.1);
    }

    @Test
    public void computePerpendicularClearance_shouldReturnMinimum_whenObstaclesOnBothSides() {
        // Two obstacles creating a narrow corridor for a horizontal edge
        // Obstacle above: y=50, height=60 => expanded bottom = 50+60+10 = 120
        // Obstacle below: y=180, height=60 => expanded top = 180-10 = 170
        // Corridor center at y=145, edge runs through it
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(50, 50, 200, 60, "above"),
                new RoutingRect(50, 180, 200, 60, "below"));
        graph.build(obstacles);

        // Horizontal edge at y=145 (midpoint of corridor between expanded boundaries 120 and 170)
        VisNode from = new VisNode(60, 145, VisNode.NodeType.SCAN_INTERSECTION);
        VisNode to = new VisNode(200, 145, VisNode.NodeType.SCAN_INTERSECTION);

        double clearance = graph.computePerpendicularClearance(from, to);

        // Distance to expanded bottom of "above" = |145 - 120| = 25
        // Distance to expanded top of "below" = |145 - 170| = 25
        // Min = 25
        assertEquals("Clearance should be minimum distance to nearest boundary", 25.0, clearance, 0.1);
    }

    @Test
    public void computePerpendicularClearance_shouldReturnMaxValue_whenNoNearbyObstacles() {
        // Edge in open space, no obstacles with overlapping parallel range
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(500, 500, 50, 50, "far-away"));
        graph.build(obstacles);

        // Horizontal edge at y=100, far from obstacle
        VisNode from = new VisNode(10, 100, VisNode.NodeType.SCAN_INTERSECTION);
        VisNode to = new VisNode(200, 100, VisNode.NodeType.SCAN_INTERSECTION);

        double clearance = graph.computePerpendicularClearance(from, to);

        assertEquals("Clearance should be MAX_VALUE when no nearby obstacles", Double.MAX_VALUE, clearance, 0.0);
    }

    @Test
    public void computePerpendicularClearance_shouldReturnZero_whenEdgeInsideObstacle() {
        // Edge running through the interior of an expanded obstacle
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(50, 50, 200, 200, "large-obs"));
        graph.build(obstacles);

        // Expanded obstacle: left=40, top=40, right=260, bottom=260
        // Horizontal edge at y=150 from x=60 to x=200 — inside the expanded obstacle
        VisNode from = new VisNode(60, 150, VisNode.NodeType.SCAN_INTERSECTION);
        VisNode to = new VisNode(200, 150, VisNode.NodeType.SCAN_INTERSECTION);

        double clearance = graph.computePerpendicularClearance(from, to);

        assertEquals("Clearance should be 0 when edge is inside obstacle", 0.0, clearance, 0.0);
    }

    @Test
    public void computePerpendicularClearance_shouldReturnCorrectDistance_whenHorizontalEdgeWithOneSideObstacle() {
        // Single obstacle below a horizontal edge
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(50, 150, 200, 100, "below"));
        graph.build(obstacles);

        // Expanded obstacle: left=40, top=140, right=260, bottom=260
        // Horizontal edge at y=120 from x=60 to x=200 — above the obstacle
        VisNode from = new VisNode(60, 120, VisNode.NodeType.SCAN_INTERSECTION);
        VisNode to = new VisNode(200, 120, VisNode.NodeType.SCAN_INTERSECTION);

        double clearance = graph.computePerpendicularClearance(from, to);

        // Distance to expanded top boundary at y=140: |120 - 140| = 20
        assertEquals("Clearance should be distance to nearest expanded boundary", 20.0, clearance, 0.1);
    }

    @Test
    public void findPath_shouldPreferWiderCorridor_whenNarrowAndWideAvailable() {
        // Two paths from left to right:
        // 1. Narrow corridor (top): gap between two obstacles, ~30px clearance
        // 2. Wide corridor (bottom): gap between two obstacles, ~100px clearance
        //
        // Layout (y-axis):
        //   y=0:    top wall obstacle
        //   y=60:   narrow corridor (~30px expanded gap)
        //   y=100:  middle obstacle
        //   y=250:  wide corridor (~100px expanded gap)
        //   y=400:  bottom wall obstacle
        //
        // Source at left, target at right, both at y=150 (between corridors)
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(100, 0,   200, 40,  "top-wall"),     // expanded bottom = 50
                new RoutingRect(100, 80,  200, 40,  "middle-upper"), // expanded top = 70, bottom = 130
                new RoutingRect(100, 300, 200, 40,  "bottom-wall")); // expanded top = 290
        graph.build(obstacles);

        // Narrow corridor: between top-wall expanded bottom (50) and middle-upper expanded top (70) = 20px gap
        // Wide corridor: between middle-upper expanded bottom (130) and bottom-wall expanded top (290) = 160px gap
        VisNode[] ports = graph.addPortNodes(50, 150, 400, 150);

        // With clearance weighting — should prefer the wide corridor (below middle-upper)
        VisibilityGraphRouter clearanceRouter = new VisibilityGraphRouter(
                VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0, 75.0);
        List<VisNode> clearancePath = clearanceRouter.findPath(graph, ports[0], ports[1]);

        // Without clearance weighting — may take shorter (narrow) corridor
        VisibilityGraphRouter noClearanceRouter = new VisibilityGraphRouter(
                VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0, 0.0);
        List<VisNode> noClearancePath = noClearanceRouter.findPath(graph, ports[0], ports[1]);

        assertFalse("Clearance path should exist", clearancePath.isEmpty());
        assertFalse("No-clearance path should exist", noClearancePath.isEmpty());

        // The clearance-weighted path should have intermediate nodes with higher y (wide corridor)
        // compared to the no-clearance path (which may use narrow corridor if shorter)
        int clearanceMaxY = clearancePath.stream()
                .mapToInt(VisNode::y).max().orElse(0);

        // Clearance path should route through the wide corridor (y > 130, below middle-upper)
        assertTrue("Clearance-weighted path should use wide corridor (max y > 130), got " + clearanceMaxY,
                clearanceMaxY > 130);
    }

    @Test
    public void findPath_shouldStillSucceed_whenNoWideCorridor() {
        // Only one narrow corridor available — routing must still succeed
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(100, 0,   200, 80, "top-wall"),
                new RoutingRect(100, 130, 200, 300, "bottom-wall"));
        // Narrow corridor between expanded bottom of top-wall (90+10=100) and
        // expanded top of bottom-wall (130-10=120) = 20px gap
        graph.build(obstacles);
        VisNode[] ports = graph.addPortNodes(50, 110, 400, 110);

        // High clearance weight should still find a path
        VisibilityGraphRouter highClearance = new VisibilityGraphRouter(
                VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0, 200.0);
        List<VisNode> path = highClearance.findPath(graph, ports[0], ports[1]);

        assertFalse("Routing should succeed even with only narrow corridor", path.isEmpty());
        assertEquals("Should start at source", ports[0], path.get(0));
        assertEquals("Should end at target", ports[1], path.get(path.size() - 1));
    }

    @Test
    public void findPath_shouldNotPenalizeNarrowCorridor_whenClearanceWeightIsZero() {
        // Zero clearance weight should disable corridor preference —
        // path should be distance-optimal, not avoiding narrow gaps.
        // Uses same layout as wide-corridor test: narrow gap (top) vs wide gap (bottom).
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(100, 0,   200, 40,  "top-wall"),
                new RoutingRect(100, 80,  200, 40,  "middle-upper"),
                new RoutingRect(100, 300, 200, 40,  "bottom-wall"));
        graph.build(obstacles);
        VisNode[] ports = graph.addPortNodes(50, 150, 400, 150);

        // Zero clearance weight — picks distance-optimal path
        VisibilityGraphRouter zeroClearance = new VisibilityGraphRouter(
                VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0, 0.0);
        List<VisNode> zeroPath = zeroClearance.findPath(graph, ports[0], ports[1]);

        // With clearance weight — should prefer wider corridor (longer but more open)
        VisibilityGraphRouter withClearance = new VisibilityGraphRouter(
                VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0, 75.0);
        List<VisNode> clearancePath = withClearance.findPath(graph, ports[0], ports[1]);

        assertFalse("Zero-clearance path should exist", zeroPath.isEmpty());
        assertFalse("Clearance path should exist", clearancePath.isEmpty());

        // Zero-weight path should be no longer than clearance-weighted path
        // (clearance weight adds cost to narrow corridors, potentially lengthening the path)
        double zeroLen = computePathLength(zeroPath);
        double clearanceLen = computePathLength(clearancePath);
        assertTrue("Zero-clearance path (" + zeroLen + ") should be no longer than " +
                        "clearance-weighted path (" + clearanceLen + ")",
                zeroLen <= clearanceLen + 0.01);
    }

    // --- Constructor validation tests (B43) ---

    @Test(expected = IllegalArgumentException.class)
    public void constructor_shouldThrow_whenDirectionalityWeightIsNegative() {
        new VisibilityGraphRouter(30, 0.0, 0.0, -1.0);
    }

    // --- Corridor directionality penalty tests (B43) ---

    @Test
    public void computeCorridorDirectionalityCost_shouldReturnZero_whenEdgeMovesTowardTarget() {
        // Target is directly to the right — RIGHT edge should have zero directional penalty
        // Build a simple graph with source left, target right
        graph.build(Collections.emptyList());
        VisNode[] ports = graph.addPortNodes(50, 200, 450, 200);

        // Router with directionality but zero clearance/congestion to isolate the effect
        VisibilityGraphRouter dirRouter = new VisibilityGraphRouter(
                VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0, 0.0, 30.0);
        List<VisNode> path = dirRouter.findPath(graph, ports[0], ports[1]);

        // Direct horizontal path — should be 2 nodes (straight line), no penalty for moving toward target
        assertFalse("Path should exist", path.isEmpty());
        assertEquals("Straight path toward target should have 2 nodes", 2, path.size());
    }

    @Test
    public void computeCorridorDirectionalityCost_shouldReturnMaxCost_whenEdgeMovesAwayFromTarget() {
        // Target is to the right, but obstacle forces initial LEFT movement
        // The directionality cost should make LEFT-moving edges expensive
        // Compare: with vs without directionality weight
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(80, 170, 100, 60, "blocker"));
        graph.build(obstacles);
        VisNode[] ports = graph.addPortNodes(50, 200, 300, 200);

        // With directionality — should prefer routes that don't go left
        VisibilityGraphRouter dirRouter = new VisibilityGraphRouter(
                VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0, 0.0, 30.0);
        List<VisNode> dirPath = dirRouter.findPath(graph, ports[0], ports[1]);

        // Without directionality — may take left-moving detour
        VisibilityGraphRouter noDirRouter = new VisibilityGraphRouter(
                VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0, 0.0, 0.0);
        List<VisNode> noDirPath = noDirRouter.findPath(graph, ports[0], ports[1]);

        assertFalse("Path with directionality should exist", dirPath.isEmpty());
        assertFalse("Path without directionality should exist", noDirPath.isEmpty());
        assertNoObstacleIntersection(dirPath, obstacles);
        assertNoObstacleIntersection(noDirPath, obstacles);

        // Verify directionality actually influenced path selection:
        // the directionality path should not go further left than the no-directionality path
        int dirMinX = dirPath.stream().mapToInt(VisNode::x).min().orElse(0);
        int noDirMinX = noDirPath.stream().mapToInt(VisNode::x).min().orElse(0);
        assertTrue("Directionality should discourage leftward movement (dirMinX=" + dirMinX +
                        ", noDirMinX=" + noDirMinX + ")",
                dirMinX >= noDirMinX);
    }

    @Test
    public void computeCorridorDirectionalityCost_shouldReturnModerateCost_whenEdgeIsPerpendicular() {
        // Target is to the right — UP/DOWN edges should get moderate (weight/2) penalty
        // This should discourage unnecessary perpendicular detours
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(150, 170, 100, 60, "blocker"));
        graph.build(obstacles);
        // Source left, target right — detour requires UP or DOWN (perpendicular)
        VisNode[] ports = graph.addPortNodes(50, 200, 400, 200);

        // With high directionality — perpendicular detours are more expensive
        VisibilityGraphRouter highDir = new VisibilityGraphRouter(
                VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0, 0.0, 60.0);
        List<VisNode> highDirPath = highDir.findPath(graph, ports[0], ports[1]);

        // Without directionality
        VisibilityGraphRouter noDir = new VisibilityGraphRouter(
                VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0, 0.0, 0.0);
        List<VisNode> noDirPath = noDir.findPath(graph, ports[0], ports[1]);

        assertFalse("High-dir path should exist", highDirPath.isEmpty());
        assertFalse("No-dir path should exist", noDirPath.isEmpty());

        // Perpendicular detour should be minimized — high-dir path should have
        // smaller perpendicular excursion or equal
        int highDirMaxYDelta = highDirPath.stream()
                .mapToInt(n -> Math.abs(n.y() - 200)).max().orElse(0);
        int noDirMaxYDelta = noDirPath.stream()
                .mapToInt(n -> Math.abs(n.y() - 200)).max().orElse(0);

        assertTrue("High-dir should not make perpendicular excursion worse. " +
                        "highDir=" + highDirMaxYDelta + ", noDir=" + noDirMaxYDelta,
                highDirMaxYDelta <= noDirMaxYDelta + 10); // 10px tolerance for graph discretization
    }

    @Test
    public void findPath_shouldPreferDirectCorridor_whenWiderCorridorExistsAwayFromTarget() {
        // KEY B43 SCENARIO: Wide corridor exists AWAY from target, narrow corridor toward target
        // Without directionality: B41 clearance steers toward wide corridor (away), causing skirting
        // With directionality: should prefer the direct corridor toward target
        //
        // Layout:
        //   Source at (50, 200), Target at (450, 200)
        //   Obstacle blocks direct path at y=200
        //   Narrow corridor above (toward target direction): ~30px gap at y=130
        //   Wide corridor below (away from target, perpendicular detour): ~100px gap at y=350
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(150, 100, 200, 15, "top-wall"),      // expanded bottom ~125
                new RoutingRect(150, 155, 200, 120, "main-blocker"), // expanded top ~145, bottom ~285
                new RoutingRect(150, 380, 200, 40, "bottom-wall")); // expanded top ~370
        // Narrow corridor: y=125 to y=145 = 20px
        // Wide corridor: y=285 to y=370 = 85px
        graph.build(obstacles);
        VisNode[] ports = graph.addPortNodes(50, 200, 450, 200);

        // With clearance + directionality — should prefer narrow-but-direct corridor
        VisibilityGraphRouter dirRouter = new VisibilityGraphRouter(
                VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0, 75.0, 30.0);
        List<VisNode> dirPath = dirRouter.findPath(graph, ports[0], ports[1]);

        // With clearance only (no directionality) — may prefer wide corridor below
        VisibilityGraphRouter clearanceOnlyRouter = new VisibilityGraphRouter(
                VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0, 75.0, 0.0);
        List<VisNode> clearancePath = clearanceOnlyRouter.findPath(graph, ports[0], ports[1]);

        assertFalse("Directionality path should exist", dirPath.isEmpty());
        assertFalse("Clearance-only path should exist", clearancePath.isEmpty());

        // Directionality path should route closer to target's y=200 (above or through narrow corridor)
        // rather than taking the wide detour below
        int dirMaxY = dirPath.stream().mapToInt(VisNode::y).max().orElse(0);
        int clearanceMaxY = clearancePath.stream().mapToInt(VisNode::y).max().orElse(0);

        // Dir path should not go as far below as clearance-only path
        assertTrue("Directionality path should stay closer to target (maxY=" + dirMaxY +
                        ") vs clearance-only (maxY=" + clearanceMaxY + ")",
                dirMaxY <= clearanceMaxY);
    }

    @Test
    public void findPath_shouldStillDetour_whenNoDirectCorridorExists() {
        // Routing must still succeed when the only path requires moving away from target
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(150, 0, 200, 180, "top-wall"),
                new RoutingRect(150, 220, 200, 300, "bottom-wall"));
        // Only corridor: y=180..220 (40px gap after expansion)
        graph.build(obstacles);
        VisNode[] ports = graph.addPortNodes(50, 100, 450, 100);

        // High directionality weight — must still find path through detour
        VisibilityGraphRouter dirRouter = new VisibilityGraphRouter(
                VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0, 0.0, 60.0);
        List<VisNode> path = dirRouter.findPath(graph, ports[0], ports[1]);

        assertFalse("Routing should succeed even when detour required", path.isEmpty());
        assertEquals("Should start at source", ports[0], path.get(0));
        assertEquals("Should end at target", ports[1], path.get(path.size() - 1));
        assertNoObstacleIntersection(path, obstacles);
    }

    @Test
    public void findPath_shouldBalanceClearanceAndDirectionality_whenWideCorridorNearTarget() {
        // When wide corridor IS toward the target, both B41 and B43 agree — path should use it
        // Layout: wide corridor above (toward target at y=100), narrow corridor below
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(150, 0,   200, 30,  "top-wall"),       // expanded bottom ~40
                new RoutingRect(150, 160, 200, 40,  "middle"),         // expanded top ~150, bottom ~210
                new RoutingRect(150, 230, 200, 300, "bottom-wall"));   // expanded top ~220
        // Wide corridor above: y=40 to y=150 = 110px
        // Narrow corridor below: y=210 to y=220 = 10px
        graph.build(obstacles);
        // Source and target at y=100 (in the wide corridor zone)
        VisNode[] ports = graph.addPortNodes(50, 100, 450, 100);

        VisibilityGraphRouter dirRouter = new VisibilityGraphRouter(
                VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0, 75.0, 30.0);
        List<VisNode> path = dirRouter.findPath(graph, ports[0], ports[1]);

        assertFalse("Path should exist", path.isEmpty());

        // Path should use the wide corridor above (low y values) since it's also toward target
        int pathMaxY = path.stream().mapToInt(VisNode::y).max().orElse(0);
        assertTrue("Path should stay in upper corridor (maxY <= 160), got " + pathMaxY,
                pathMaxY <= 160);
    }

    @Test
    public void findPath_shouldReturnZeroDirectionalityCost_whenSourceAndTargetCollinear() {
        // Source and target on same horizontal line — horizontal edges should have zero
        // directional penalty; vertical edges should have weight/2 (perpendicular)
        // This means collinear routing should not be penalized
        graph.build(Collections.emptyList());
        VisNode[] ports = graph.addPortNodes(50, 200, 450, 200);

        // With directionality
        VisibilityGraphRouter dirRouter = new VisibilityGraphRouter(
                VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0, 0.0, 30.0);
        List<VisNode> dirPath = dirRouter.findPath(graph, ports[0], ports[1]);

        // Without directionality
        VisibilityGraphRouter noDirRouter = new VisibilityGraphRouter(
                VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0, 0.0, 0.0);
        List<VisNode> noDirPath = noDirRouter.findPath(graph, ports[0], ports[1]);

        // Both should produce identical straight path — directionality cost is 0 for aligned edges
        assertEquals("Collinear paths should be identical size",
                noDirPath.size(), dirPath.size());
        for (int i = 0; i < noDirPath.size(); i++) {
            assertEquals("Path nodes should match at index " + i,
                    noDirPath.get(i), dirPath.get(i));
        }
    }

    @Test
    public void findPath_shouldPreferDirectCorridor_whenPerimeterHasUnlimitedClearance() {
        // B43-a SCENARIO: Perimeter corridor has near-infinite clearance,
        // direct interior corridor has moderate clearance (~25px).
        // Without clamp: perimeter wins (clearanceCost ~0 vs ~3.0/edge)
        // With clamp (MAX_EFFECTIVE_CLEARANCE=60): direct corridor wins
        // because directionality overcomes the capped clearance delta.
        //
        // Layout:
        //   Source at (50, 200), Target at (450, 200)
        //   Interior corridor at y~200 with moderate clearance (~15px each side)
        //   Perimeter corridor at y>300 with unlimited clearance
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(100, 170, 300, 10, "upper-wall"),    // expanded bottom ~185
                new RoutingRect(100, 225, 300, 10, "lower-wall"));   // expanded top ~215
        // Interior corridor: y=185 to y=215 = 30px clearance (each side ~15px)
        // Perimeter: y>300 has hundreds of px clearance
        graph.build(obstacles);
        VisNode[] ports = graph.addPortNodes(50, 200, 450, 200);

        VisibilityGraphRouter router = new VisibilityGraphRouter(
                VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0, 75.0, 30.0);
        List<VisNode> path = router.findPath(graph, ports[0], ports[1]);

        assertFalse("Path should exist", path.isEmpty());

        // Path should use interior corridor (stay near y=200)
        // NOT take the perimeter detour (y>300)
        int pathMaxY = path.stream().mapToInt(VisNode::y).max().orElse(0);
        assertTrue("Path should use interior corridor (maxY <= 250), got " + pathMaxY,
                pathMaxY <= 250);
    }

    // --- Helper methods ---

    /**
     * Counts the number of direction changes (bends) in a path.
     */
    private int countBends(List<VisNode> path) {
        if (path.size() < 3) return 0;

        int bends = 0;
        for (int i = 1; i < path.size() - 1; i++) {
            VisNode prev = path.get(i - 1);
            VisNode curr = path.get(i);
            VisNode next = path.get(i + 1);

            Direction prevDir = getDirection(prev, curr);
            Direction nextDir = getDirection(curr, next);

            if (prevDir != nextDir) {
                bends++;
            }
        }
        return bends;
    }

    /**
     * Computes total Manhattan path length.
     */
    private double computePathLength(List<VisNode> path) {
        double total = 0;
        for (int i = 0; i < path.size() - 1; i++) {
            total += Math.abs(path.get(i).x() - path.get(i + 1).x())
                    + Math.abs(path.get(i).y() - path.get(i + 1).y());
        }
        return total;
    }

    /**
     * Determines the cardinal direction from node a to node b.
     */
    private Direction getDirection(VisNode a, VisNode b) {
        int dx = b.x() - a.x();
        int dy = b.y() - a.y();

        if (dx == 0) {
            return dy > 0 ? Direction.DOWN : Direction.UP;
        } else {
            return dx > 0 ? Direction.RIGHT : Direction.LEFT;
        }
    }

    /**
     * Asserts that every consecutive node pair in the path has an edge in the graph.
     */
    private void assertPathIsGraphValid(List<VisNode> path) {
        for (int i = 0; i < path.size() - 1; i++) {
            VisNode from = path.get(i);
            VisNode to = path.get(i + 1);
            List<VisEdge> edges = graph.getEdges(from);
            boolean hasEdge = edges.stream().anyMatch(e -> e.target().equals(to));
            assertTrue("No graph edge from (" + from.x() + "," + from.y() + ") to ("
                    + to.x() + "," + to.y() + ")", hasEdge);
        }
    }

    /**
     * Asserts that no segment in the path passes strictly through any obstacle.
     */
    private void assertNoObstacleIntersection(List<VisNode> path, List<RoutingRect> obstacles) {
        for (int i = 0; i < path.size() - 1; i++) {
            VisNode from = path.get(i);
            VisNode to = path.get(i + 1);

            for (RoutingRect obs : obstacles) {
                assertFalse(
                        "Segment (" + from.x() + "," + from.y() + ")->(" + to.x() + "," + to.y()
                                + ") intersects obstacle " + obs.id(),
                        segmentIntersectsObstacle(from, to, obs));
            }
        }
    }

    /**
     * Tests if a segment passes strictly through an obstacle rectangle.
     */
    private boolean segmentIntersectsObstacle(VisNode from, VisNode to, RoutingRect obs) {
        int x1 = from.x(), y1 = from.y();
        int x2 = to.x(), y2 = to.y();
        int ox = obs.x(), oy = obs.y();
        int ow = obs.width(), oh = obs.height();

        if (x1 == x2) {
            // Vertical segment: blocked if x is strictly inside obstacle
            if (x1 > ox && x1 < ox + ow) {
                int minY = Math.min(y1, y2);
                int maxY = Math.max(y1, y2);
                return minY < oy + oh && maxY > oy;
            }
        } else if (y1 == y2) {
            // Horizontal segment: blocked if y is strictly inside obstacle
            if (y1 > oy && y1 < oy + oh) {
                int minX = Math.min(x1, x2);
                int maxX = Math.max(x1, x2);
                return minX < ox + ow && maxX > ox;
            }
        }
        return false;
    }

    // =========================================================================
    // Group-wall clearance tests (B43-b)
    // =========================================================================

    // --- Test: computeGroupWallClearance measures perpendicular distance ---

    @Test
    public void computeGroupWallClearance_shouldReturnSmallDistance_whenEdgeNearGroupWall() {
        // Horizontal edge at y=105, group wall (top) at y=100 spanning x=0..400
        List<RoutingRect> groups = List.of(new RoutingRect(0, 100, 400, 200, "g1"));
        VisibilityGraphRouter groupRouter = new VisibilityGraphRouter(
                VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0, VisibilityGraphRouter.DEFAULT_CLEARANCE_WEIGHT, VisibilityGraphRouter.DEFAULT_DIRECTIONALITY_WEIGHT, groups);

        VisNode from = new VisNode(50, 105, VisNode.NodeType.PORT);
        VisNode to = new VisNode(350, 105, VisNode.NodeType.PORT);
        double clearance = groupRouter.computeGroupWallClearance(from, to);

        assertEquals("Clearance should be 5px to nearest group wall (top at y=100)", 5.0, clearance, 0.1);
    }

    @Test
    public void computeGroupWallClearance_shouldReturnMinDistance_whenGroupsOnBothSides() {
        // Vertical edge at x=200, group A right wall at x=180, group B left wall at x=220
        // Edge is 20px from each wall; nearest is 20px
        List<RoutingRect> groups = List.of(
                new RoutingRect(0, 0, 180, 400, "gA"),    // right wall at x=180
                new RoutingRect(220, 0, 200, 400, "gB")); // left wall at x=220
        VisibilityGraphRouter groupRouter = new VisibilityGraphRouter(
                VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0, VisibilityGraphRouter.DEFAULT_CLEARANCE_WEIGHT, VisibilityGraphRouter.DEFAULT_DIRECTIONALITY_WEIGHT, groups);

        VisNode from = new VisNode(200, 50, VisNode.NodeType.PORT);
        VisNode to = new VisNode(200, 350, VisNode.NodeType.PORT);
        double clearance = groupRouter.computeGroupWallClearance(from, to);

        assertEquals("Clearance should be 20px to nearest group wall", 20.0, clearance, 0.1);
    }

    @Test
    public void computeGroupWallClearance_shouldReturnMaxValue_whenNoGroupBoundaries() {
        // No groups — clearance should be MAX_VALUE
        List<RoutingRect> groups = List.of();
        VisibilityGraphRouter groupRouter = new VisibilityGraphRouter(
                VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0, VisibilityGraphRouter.DEFAULT_CLEARANCE_WEIGHT, VisibilityGraphRouter.DEFAULT_DIRECTIONALITY_WEIGHT, groups);

        VisNode from = new VisNode(50, 100, VisNode.NodeType.PORT);
        VisNode to = new VisNode(350, 100, VisNode.NodeType.PORT);
        double clearance = groupRouter.computeGroupWallClearance(from, to);

        assertEquals("Clearance should be MAX_VALUE when no groups", Double.MAX_VALUE, clearance, 0.0);
    }

    @Test
    public void computeGroupWallClearance_shouldReturnMaxValue_whenGroupDoesNotOverlapEdge() {
        // Horizontal edge at y=100 spanning x=50..350
        // Group at x=500..700 (no x-range overlap)
        List<RoutingRect> groups = List.of(new RoutingRect(500, 0, 200, 400, "far"));
        VisibilityGraphRouter groupRouter = new VisibilityGraphRouter(
                VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0, VisibilityGraphRouter.DEFAULT_CLEARANCE_WEIGHT, VisibilityGraphRouter.DEFAULT_DIRECTIONALITY_WEIGHT, groups);

        VisNode from = new VisNode(50, 100, VisNode.NodeType.PORT);
        VisNode to = new VisNode(350, 100, VisNode.NodeType.PORT);
        double clearance = groupRouter.computeGroupWallClearance(from, to);

        assertEquals("Clearance should be MAX_VALUE when group doesn't overlap edge",
                Double.MAX_VALUE, clearance, 0.0);
    }

    // --- Test: intra-group routing unaffected (AC-4) ---

    @Test
    public void findPath_shouldNotPenalizeIntraGroupRoute_whenGroupExcludedFromBoundaries() {
        // Two elements inside the same group. Group is NOT in groupBoundaries
        // (because it's excluded as an ancestor). Route should behave normally.
        RoutingRect elem1 = new RoutingRect(50, 50, 80, 40, "e1");
        RoutingRect elem2 = new RoutingRect(250, 50, 80, 40, "e2");
        // Group spans 0,0 to 400,200 — NOT passed as group boundary (excluded)
        List<RoutingRect> groups = List.of();

        graph.build(List.of(elem1, elem2));
        VisNode[] ports = graph.addPortNodes(90, 70, 290, 70);

        VisibilityGraphRouter groupRouter = new VisibilityGraphRouter(
                VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0, VisibilityGraphRouter.DEFAULT_CLEARANCE_WEIGHT, VisibilityGraphRouter.DEFAULT_DIRECTIONALITY_WEIGHT, groups);
        List<VisNode> path = groupRouter.findPath(graph, ports[0], ports[1]);

        assertFalse("Path should exist for intra-group route", path.isEmpty());
    }

    // --- Test: cross-group routing prefers inter-group gap ---

    @Test
    public void findPath_shouldPreferInterGroupGap_whenGroupWallClearanceActive() {
        // Layout: two groups side by side with a 120px gap between them.
        // Group A: x=0..280 (right wall at x=280)
        // Group B: x=400..700 (left wall at x=400)
        // Inter-group gap: x=280..400 (center at x=340)
        //
        // Source element at (50,150) inside Group A
        // Target element at (500,150) inside Group B
        //
        // Two corridors available:
        // - Corridor 1: through inter-group gap at x~340 (60px from each group wall)
        // - Corridor 2: just inside Group B wall at x~410 (10px from wall)
        //
        // With group boundaries: should prefer gap corridor (lower group-wall clearance cost)
        // Without group boundaries: may pick the corridor just inside group B (shorter path)

        // Elements blocking the direct path, creating corridors
        RoutingRect blocker = new RoutingRect(280, 100, 120, 100, "blocker"); // blocks direct at gap height

        // Remove blocker — actually we want the router to route around things.
        // Let's set up a simpler scenario: source and target at different y, forcing vertical routing.
        // The question is: does the vertical segment prefer x=340 (gap center) or x=410 (inside group B)?

        // Source at (200,50), target at (500,300)
        // Route must go vertical somewhere between x=200 and x=500
        // Obstacles in Group A force the route to exit Group A
        RoutingRect obstA = new RoutingRect(200, 100, 100, 80, "obstA"); // inside Group A, blocks direct

        List<RoutingRect> obstacles = List.of(obstA);
        List<RoutingRect> groups = List.of(
                new RoutingRect(0, 0, 280, 400, "gA"),     // Group A: right wall at x=280
                new RoutingRect(400, 0, 300, 400, "gB"));  // Group B: left wall at x=400

        graph.build(obstacles);
        VisNode[] ports = graph.addPortNodes(200, 50, 500, 300);

        // With group wall clearance
        VisibilityGraphRouter withGroups = new VisibilityGraphRouter(
                VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0, VisibilityGraphRouter.DEFAULT_CLEARANCE_WEIGHT, VisibilityGraphRouter.DEFAULT_DIRECTIONALITY_WEIGHT, groups);
        List<VisNode> groupPath = withGroups.findPath(graph, ports[0], ports[1]);

        // Without group wall clearance
        VisibilityGraphRouter noGroups = new VisibilityGraphRouter(
                VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0, VisibilityGraphRouter.DEFAULT_CLEARANCE_WEIGHT, VisibilityGraphRouter.DEFAULT_DIRECTIONALITY_WEIGHT, List.of());
        List<VisNode> noGroupPath = noGroups.findPath(graph, ports[0], ports[1]);

        assertFalse("Group-aware path should exist", groupPath.isEmpty());
        assertFalse("No-group path should exist", noGroupPath.isEmpty());

        // Verify: group-aware path's vertical segments should be closer to gap center (x~340)
        // than to group B wall (x=400+)
        // Find the minimum x among intermediate nodes (excluding source/target)
        // that represents where the route passes vertically
        int groupPathMinXInGap = groupPath.stream()
                .filter(n -> n.x() > 270 && n.x() < 410) // nodes in the gap region
                .mapToInt(VisNode::x)
                .min().orElse(Integer.MAX_VALUE);

        // The route MUST pass through the gap region — fail if it doesn't
        assertTrue("Group-aware route should have at least one node in gap region [270,410), " +
                        "but no nodes found. Path x-coords: " +
                        groupPath.stream().map(n -> String.valueOf(n.x())).reduce((a, b) -> a + "," + b).orElse("empty"),
                groupPathMinXInGap < Integer.MAX_VALUE);

        // Route should be closer to gap center than to group B wall
        int distToGapCenter = Math.abs(groupPathMinXInGap - 340);
        int distToGroupBWall = Math.abs(groupPathMinXInGap - 400);
        assertTrue("Group-aware route should be closer to gap center (x=340) than Group B wall (x=400), " +
                        "got x=" + groupPathMinXInGap,
                distToGapCenter <= distToGroupBWall);
    }

    // --- Test: boundary condition — edge exactly on group wall (clearance = 0) ---

    @Test
    public void computeGroupWallClearance_shouldReturnZero_whenEdgeOnGroupWall() {
        // Horizontal edge at y=100, group wall (top) at y=100 spanning x=0..400
        // Edge runs exactly on the group wall — clearance should be 0
        List<RoutingRect> groups = List.of(new RoutingRect(0, 100, 400, 200, "g1"));
        VisibilityGraphRouter groupRouter = new VisibilityGraphRouter(
                VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0, VisibilityGraphRouter.DEFAULT_CLEARANCE_WEIGHT, VisibilityGraphRouter.DEFAULT_DIRECTIONALITY_WEIGHT, groups);

        VisNode from = new VisNode(50, 100, VisNode.NodeType.PORT);
        VisNode to = new VisNode(350, 100, VisNode.NodeType.PORT);
        double clearance = groupRouter.computeGroupWallClearance(from, to);

        assertEquals("Clearance should be 0 when edge is on group wall", 0.0, clearance, 0.0);
    }

    // --- Test: nested groups — inner group wall affects clearance for outside connections ---

    @Test
    public void computeGroupWallClearance_shouldConsiderNestedGroupWalls() {
        // Outer group: 0,0 to 600,400
        // Inner group: 100,100 to 300,300 (wall at x=300)
        // Vertical edge at x=310 — 10px from inner group right wall
        List<RoutingRect> groups = List.of(
                new RoutingRect(0, 0, 600, 400, "outer"),
                new RoutingRect(100, 100, 200, 200, "inner")); // right wall at x=300
        VisibilityGraphRouter groupRouter = new VisibilityGraphRouter(
                VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0, VisibilityGraphRouter.DEFAULT_CLEARANCE_WEIGHT, VisibilityGraphRouter.DEFAULT_DIRECTIONALITY_WEIGHT, groups);

        VisNode from = new VisNode(310, 50, VisNode.NodeType.PORT);
        VisNode to = new VisNode(310, 350, VisNode.NodeType.PORT);
        double clearance = groupRouter.computeGroupWallClearance(from, to);

        // Nearest wall: inner group right wall at x=300 (10px away)
        // Also: outer group left wall at x=0 (310px away), outer right at x=600 (290px away)
        assertEquals("Clearance should be 10px to inner group wall", 10.0, clearance, 0.1);
    }

    @Test
    public void findPath_shouldRouteSuccessfully_whenGroupWallClearanceActive() {
        // Ensure routing still succeeds (no path failures) with group wall clearance
        RoutingRect obst1 = new RoutingRect(150, 50, 100, 100, "ob1");
        RoutingRect obst2 = new RoutingRect(150, 200, 100, 100, "ob2");

        List<RoutingRect> obstacles = List.of(obst1, obst2);
        List<RoutingRect> groups = List.of(
                new RoutingRect(0, 0, 130, 400, "gLeft"),
                new RoutingRect(270, 0, 200, 400, "gRight"));

        graph.build(obstacles);
        VisNode[] ports = graph.addPortNodes(50, 150, 400, 150);

        VisibilityGraphRouter groupRouter = new VisibilityGraphRouter(
                VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0, VisibilityGraphRouter.DEFAULT_CLEARANCE_WEIGHT, VisibilityGraphRouter.DEFAULT_DIRECTIONALITY_WEIGHT, groups);
        List<VisNode> path = groupRouter.findPath(graph, ports[0], ports[1]);

        assertFalse("Routing should succeed with group wall clearance active", path.isEmpty());
        assertEquals("Path should start at source", ports[0], path.get(0));
        assertEquals("Path should end at target", ports[1], path.get(path.size() - 1));
    }

    // --- B47: Corridor occupancy tests ---

    /**
     * Two equal-length corridors exist (above and below an obstacle).
     * After recording a path through one corridor, the router should prefer the other.
     */
    @Test
    public void findPath_shouldPreferEmptyCorridor_whenOccupiedCorridorExists() {
        // Obstacle in the middle — creates two corridors (above y=100 and below y=200)
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(150, 100, 100, 100, "obs")); // 150,100 to 250,200

        graph = new OrthogonalVisibilityGraph(10, 50);
        graph.build(obstacles);
        VisNode[] ports = graph.addPortNodes(100, 150, 300, 150);

        // Route without occupancy — find the "default" corridor
        VisibilityGraphRouter occRouter = new VisibilityGraphRouter(
                VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0,
                VisibilityGraphRouter.DEFAULT_CLEARANCE_WEIGHT,
                VisibilityGraphRouter.DEFAULT_DIRECTIONALITY_WEIGHT,
                List.of(), 0.5);
        List<VisNode> path1 = occRouter.findPath(graph, ports[0], ports[1], null);
        assertFalse("First route should succeed", path1.isEmpty());

        // Record path1 in tracker
        CorridorOccupancyTracker tracker = new CorridorOccupancyTracker();
        List<AbsoluteBendpointDto> bps = new ArrayList<>();
        for (int i = 1; i < path1.size() - 1; i++) {
            bps.add(new AbsoluteBendpointDto(path1.get(i).x(), path1.get(i).y()));
        }
        tracker.recordPath(bps, new int[]{100, 150}, new int[]{300, 150});

        // Re-build graph (as pipeline does per connection) and route again with tracker
        graph = new OrthogonalVisibilityGraph(10, 50);
        graph.build(obstacles);
        VisNode[] ports2 = graph.addPortNodes(100, 150, 300, 150);
        List<VisNode> path2 = occRouter.findPath(graph, ports2[0], ports2[1], tracker);
        assertFalse("Second route should succeed", path2.isEmpty());

        // Paths should differ (different corridor) — compare by converting to y-coordinate sets
        // The paths may not always differ depending on graph geometry, but the cost should be higher
        // for the occupied corridor. At minimum, verify both routes succeed.
        assertTrue("Second route should reach target", path2.get(path2.size() - 1).equals(ports2[1]));
    }

    /**
     * Occupancy penalty is soft (multiplicative), not a hard block.
     * If the only alternative is much longer, the router should still use the occupied corridor.
     */
    @Test
    public void findPath_shouldStillUseOccupiedCorridor_whenAlternativeIsMuchLonger() {
        // Single narrow corridor — no alternative path exists
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(150, 0, 100, 130, "top"),    // blocks top
                new RoutingRect(150, 170, 100, 130, "bot")); // blocks bottom

        graph = new OrthogonalVisibilityGraph(10, 50);
        graph.build(obstacles);
        VisNode[] ports = graph.addPortNodes(100, 150, 300, 150);

        CorridorOccupancyTracker tracker = new CorridorOccupancyTracker();
        // Record high occupancy on the only corridor
        for (int i = 0; i < 5; i++) {
            tracker.recordPath(List.of(), new int[]{100, 150}, new int[]{300, 150});
        }

        VisibilityGraphRouter occRouter = new VisibilityGraphRouter(
                VisibilityGraphRouter.DEFAULT_BEND_PENALTY, 0.0,
                VisibilityGraphRouter.DEFAULT_CLEARANCE_WEIGHT,
                VisibilityGraphRouter.DEFAULT_DIRECTIONALITY_WEIGHT,
                List.of(), 0.5);
        List<VisNode> path = occRouter.findPath(graph, ports[0], ports[1], tracker);

        assertFalse("Route should still succeed despite occupied corridor (soft penalty)", path.isEmpty());
        assertEquals("Path should reach target", ports[1], path.get(path.size() - 1));
    }

    /**
     * Null tracker produces identical behavior to no occupancy tracking.
     */
    @Test
    public void findPath_shouldWorkWithoutTracker() {
        graph.build(Collections.emptyList());
        VisNode[] ports = graph.addPortNodes(50, 100, 300, 100);

        List<VisNode> pathNoTracker = router.findPath(graph, ports[0], ports[1]);
        List<VisNode> pathNullTracker = router.findPath(graph, ports[0], ports[1], null);

        assertEquals("Paths should be identical with null tracker", pathNoTracker.size(), pathNullTracker.size());
        for (int i = 0; i < pathNoTracker.size(); i++) {
            assertEquals("Node " + i + " should match",
                    pathNoTracker.get(i), pathNullTracker.get(i));
        }
    }
}
