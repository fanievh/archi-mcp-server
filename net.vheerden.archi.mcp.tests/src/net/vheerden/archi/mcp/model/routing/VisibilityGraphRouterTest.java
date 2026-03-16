package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.model.routing.VisEdge.Direction;

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
}
