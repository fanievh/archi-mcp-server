package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.model.routing.VisNode.NodeType;

/**
 * Tests for {@link OrthogonalVisibilityGraph} (Story 10-6a).
 * Pure-geometry tests — no OSGi runtime required.
 */
public class OrthogonalVisibilityGraphTest {

    private OrthogonalVisibilityGraph graph;

    @Before
    public void setUp() {
        graph = new OrthogonalVisibilityGraph();
    }

    // --- AC #1: Corner points, visibility edges, no edge through obstacles ---

    @Test
    public void shouldProduceMultiHopPath_whenObstacleBetweenPorts() {
        // Single obstacle between source and target ports
        // Source at (50, 200), Target at (350, 200), Obstacle at (150, 150, 100x100)
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(150, 150, 100, 100, "obs1"));
        graph.build(obstacles);
        VisNode[] ports = graph.addPortNodes(50, 200, 350, 200);

        // Should have a path from source to target (multi-hop around obstacle)
        assertTrue("Path should exist from source to target",
                pathExists(ports[0], ports[1]));

        // The direct horizontal line y=200 passes through the obstacle (150-250 x, 150-250 y),
        // so the path must go around
        List<VisNode> path = findPath(ports[0], ports[1]);
        assertNotNull("BFS path should be found", path);
        assertTrue("Path should have intermediate nodes (multi-hop)",
                path.size() > 2);
    }

    @Test
    public void shouldProduceDirectEdge_whenNoObstacleBetweenPorts() {
        // No obstacles, ports on same horizontal line
        List<RoutingRect> obstacles = Collections.emptyList();
        graph.build(obstacles);
        VisNode[] ports = graph.addPortNodes(50, 100, 300, 100);

        // Direct edge should exist between source and target (same y, no obstacles)
        assertTrue("Direct edge should exist from source to target",
                hasDirectEdge(ports[0], ports[1]));
    }

    @Test
    public void shouldHaveCorrectMarginOffset_forCornerPoints() {
        // Obstacle at (100, 100, 80x60)
        // With default margin of 10, expanded corners should be at:
        // (90, 90), (190, 90), (90, 170), (190, 170)
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(100, 100, 80, 60, "obs1"));
        graph.build(obstacles);

        Set<VisNode> nodes = graph.getNodes();

        assertTrue("Should have corner at (90, 90)",
                containsNodeAt(nodes, 90, 90));
        assertTrue("Should have corner at (190, 90)",
                containsNodeAt(nodes, 190, 90));
        assertTrue("Should have corner at (90, 170)",
                containsNodeAt(nodes, 90, 170));
        assertTrue("Should have corner at (190, 170)",
                containsNodeAt(nodes, 190, 170));
    }

    @Test
    public void shouldNotHaveEdgeThroughObstacle() {
        // Multiple obstacles
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(100, 100, 80, 80, "obs1"),
                new RoutingRect(300, 200, 60, 60, "obs2"));
        graph.build(obstacles);

        Map<VisNode, List<VisEdge>> adj = graph.getAdjacency();

        // Verify no edge passes through any obstacle
        for (Map.Entry<VisNode, List<VisEdge>> entry : adj.entrySet()) {
            VisNode from = entry.getKey();
            for (VisEdge edge : entry.getValue()) {
                VisNode to = edge.target();
                assertFalse(
                        "Edge from (" + from.x() + "," + from.y() + ") to ("
                                + to.x() + "," + to.y() + ") passes through obstacle",
                        segmentPassesThroughObstacle(from, to, obstacles));
            }
        }
    }

    // --- AC #2: Performance ---

    @Test
    public void shouldConstructIn100ms_with30Obstacles() {
        // Generate 30 obstacles in a grid pattern
        List<RoutingRect> obstacles = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            for (int j = 0; j < 5; j++) {
                obstacles.add(new RoutingRect(
                        100 + i * 150, 100 + j * 150, 80, 60,
                        "obs-" + i + "-" + j));
            }
        }
        assertEquals(30, obstacles.size());

        // Warm up JIT
        OrthogonalVisibilityGraph warmup = new OrthogonalVisibilityGraph();
        warmup.build(obstacles);

        // Timed run
        long start = System.nanoTime();
        OrthogonalVisibilityGraph timed = new OrthogonalVisibilityGraph();
        timed.build(obstacles);
        long elapsed = System.nanoTime() - start;

        double ms = elapsed / 1_000_000.0;
        assertTrue("Construction should complete in under 100ms, took " + ms + "ms",
                ms < 100.0);
    }

    // --- AC #3: Multi-hop path around obstacle ---

    @Test
    public void shouldFindMultiHopPath_whenNoDirectLineOfSight() {
        // Large obstacle completely blocking direct horizontal/vertical sight
        // Source at (50, 200), Target at (450, 200)
        // Wall obstacle from (180, 50) to (280, 350) — blocks all straight paths
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(180, 50, 100, 300, "wall"));
        graph.build(obstacles);
        VisNode[] ports = graph.addPortNodes(50, 200, 450, 200);

        List<VisNode> path = findPath(ports[0], ports[1]);
        assertNotNull("Path should exist around the wall", path);
        assertTrue("Path should be multi-hop (at least 3 nodes)",
                path.size() >= 3);
    }

    // --- Additional edge cases ---

    @Test
    public void shouldProduceMinimalGraph_withEmptyObstacleList() {
        graph.build(Collections.emptyList());
        VisNode[] ports = graph.addPortNodes(100, 100, 300, 100);

        // With no obstacles, graph should have just the port nodes
        // and a direct edge between them (same y)
        assertTrue("Graph should contain source port",
                graph.getNodes().contains(ports[0]));
        assertTrue("Graph should contain target port",
                graph.getNodes().contains(ports[1]));
        assertTrue("Direct edge should exist",
                hasDirectEdge(ports[0], ports[1]));
    }

    @Test
    public void shouldHandleOverlappingObstacles_withoutDuplicateNodes() {
        // Two overlapping obstacles — their expanded corners might coincide
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(100, 100, 80, 80, "obs1"),
                new RoutingRect(110, 110, 80, 80, "obs2")); // overlaps obs1
        graph.build(obstacles);

        // Verify no duplicate nodes at the same (x, y) coordinates
        Set<VisNode> nodes = graph.getNodes();
        assertFalse("Graph should have nodes", nodes.isEmpty());

        Set<String> coords = new HashSet<>();
        for (VisNode n : nodes) {
            assertTrue("Duplicate coordinate: (" + n.x() + "," + n.y() + ")",
                    coords.add(n.x() + "," + n.y()));
        }
    }

    @Test
    public void shouldBeReusable_forMultiplePortPairs() {
        // Build once with obstacles, query with multiple port pairs
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(200, 100, 80, 80, "obs1"));
        graph.build(obstacles);

        // First connection: ports above and below obstacle
        VisNode[] ports1 = graph.addPortNodes(50, 50, 350, 50);
        assertTrue("First port pair should have path",
                pathExists(ports1[0], ports1[1]));

        // Second connection: ports left and right of obstacle
        VisNode[] ports2 = graph.addPortNodes(50, 150, 350, 150);
        assertTrue("Second port pair should have path",
                pathExists(ports2[0], ports2[1]));

        // Both connections should still work
        assertTrue("First pair still connected after adding second",
                pathExists(ports1[0], ports1[1]));
    }

    // --- Boundary condition tests (Story 10-19c) ---

    @Test
    public void shouldBlockSegment_whenGrazingObstacleEdge_inclusiveMode() {
        // Obstacle at (100, 100, 80x60), expanded by 10px to (90, 90) - (190, 170)
        // Vertical segment at x=90 (exactly on expanded left boundary)
        OrthogonalVisibilityGraph inclusiveGraph = new OrthogonalVisibilityGraph(OrthogonalVisibilityGraph.DEFAULT_MARGIN, true);

        List<RoutingRect> obstacles = List.of(
                new RoutingRect(100, 100, 80, 60, "obs1"));
        inclusiveGraph.build(obstacles);

        // In inclusive mode, a segment at x=90 (left boundary of expanded rect)
        // should be blocked. Port at (90, 50) to (90, 200) — grazes left edge.
        // With inclusive boundaries, no edge should exist through the boundary.
        VisNode[] ports = inclusiveGraph.addPortNodes(90, 50, 90, 200);

        // The segment at x=90 passes along the expanded obstacle's left boundary.
        // In inclusive mode, this should be blocked — so no direct edge.
        assertFalse("Segment grazing obstacle edge should be blocked in inclusive mode",
                hasDirectEdge(inclusiveGraph, ports[0], ports[1]));
    }

    @Test
    public void shouldAllowSegment_whenGrazingObstacleEdge_strictMode() {
        // Same setup but strict mode (default) — segment at boundary should be allowed
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(100, 100, 80, 60, "obs1"));
        graph.build(obstacles);

        // Expanded obstacle: (90, 90) - (190, 170)
        // Segment at x=90 (left boundary) from y=50 to y=200
        VisNode[] ports = graph.addPortNodes(90, 50, 90, 200);

        // In strict mode, segment ON the boundary is NOT blocked
        // The port at (90, 50) should connect down through/past the boundary
        // Note: (90,90) and (90,170) are corner nodes, so there should be
        // edges connecting the port down to these corners
        assertTrue("Segment grazing obstacle edge should be allowed in strict mode",
                pathExists(graph, ports[0], ports[1]));
    }

    @Test
    public void shouldBlockSegment_throughNarrowGap_inclusiveMode() {
        // Two elements 2px apart: element A at (100, 100, 80x60), element B at (182, 100, 80x60)
        // Gap between A and B: x=180 to x=182 (2px)
        // Expanded A: right = 100+80+10 = 190
        // Expanded B: left = 182-10 = 172
        // Expanded rects OVERLAP horizontally (172 < 190), so ANY vertical segment
        // in the gap (x=172..190) should be blocked by at least one expanded rect.
        OrthogonalVisibilityGraph inclusiveGraph = new OrthogonalVisibilityGraph(OrthogonalVisibilityGraph.DEFAULT_MARGIN, true);

        List<RoutingRect> obstacles = List.of(
                new RoutingRect(100, 100, 80, 60, "obsA"),
                new RoutingRect(182, 100, 80, 60, "obsB"));
        inclusiveGraph.build(obstacles);

        // Try to route through the 2px gap at x=181 (midpoint of gap)
        // Expanded A right=190, expanded B left=172
        // x=181 is inside both expanded rects' horizontal extent
        VisNode[] ports = inclusiveGraph.addPortNodes(181, 50, 181, 200);

        // In inclusive mode, no path should exist through the narrow gap
        assertFalse("Should block segment through 2px gap in inclusive mode",
                hasDirectEdge(inclusiveGraph, ports[0], ports[1]));
    }

    @Test
    public void shouldAllowSegment_throughNarrowGap_strictMode() {
        // Same setup as above but strict mode
        // With strict boundaries, segments exactly at expanded boundary coords
        // are allowed, so routing through overlapping expanded rects may still work
        // at boundary coordinates
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(100, 100, 80, 60, "obsA"),
                new RoutingRect(182, 100, 80, 60, "obsB"));
        graph.build(obstacles);

        // Port through the gap area — a path should exist (possibly multi-hop)
        VisNode[] ports = graph.addPortNodes(181, 50, 181, 200);

        // In strict mode, paths through or around should be possible
        assertTrue("Should find path in strict mode with narrow gap",
                pathExists(graph, ports[0], ports[1]));
    }

    @Test
    public void shouldFindPath_withInclusiveMode_singleObstacle_viaPerimeter() {
        // Previously inclusive mode pruned corner nodes that sit ON expanded
        // obstacle boundaries, disconnecting the graph. Perimeter boundary nodes
        // (added in E2E 2026-03-12 fix) provide alternate routing paths.
        OrthogonalVisibilityGraph inclusiveGraph = new OrthogonalVisibilityGraph(OrthogonalVisibilityGraph.DEFAULT_MARGIN, true);

        List<RoutingRect> obstacles = List.of(
                new RoutingRect(150, 150, 100, 100, "obs1"));
        inclusiveGraph.build(obstacles);
        VisNode[] ports = inclusiveGraph.addPortNodes(50, 200, 350, 200);

        // Perimeter nodes now provide routing paths even in inclusive mode
        assertTrue("Perimeter nodes enable routing in inclusive mode",
                pathExists(inclusiveGraph, ports[0], ports[1]));
    }

    @Test
    public void shouldFindPathAroundWall_withInclusiveMode_viaPerimeter() {
        // Previously inclusive mode broke wall-avoidance routing due to
        // corner-pruning. Perimeter boundary nodes now provide alternate paths.
        OrthogonalVisibilityGraph inclusiveGraph = new OrthogonalVisibilityGraph(OrthogonalVisibilityGraph.DEFAULT_MARGIN, true);

        List<RoutingRect> obstacles = List.of(
                new RoutingRect(180, 50, 100, 300, "wall"));
        inclusiveGraph.build(obstacles);
        VisNode[] ports = inclusiveGraph.addPortNodes(50, 200, 450, 200);

        // Perimeter nodes now provide routing paths even in inclusive mode
        List<VisNode> path = findPath(inclusiveGraph, ports[0], ports[1]);
        assertNotNull("Perimeter nodes enable wall-avoidance in inclusive mode", path);
        assertTrue("Path should be multi-hop", path.size() >= 3);
    }

    @Test
    public void shouldHandleDenseLayout_withInclusiveMode() {
        // Dense grid with 150px spacing still works — corners aren't shared
        // between expanded obstacles at this spacing.
        OrthogonalVisibilityGraph inclusiveGraph = new OrthogonalVisibilityGraph(OrthogonalVisibilityGraph.DEFAULT_MARGIN, true);

        List<RoutingRect> obstacles = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            for (int j = 0; j < 5; j++) {
                obstacles.add(new RoutingRect(
                        100 + i * 150, 100 + j * 150, 80, 60,
                        "obs-" + i + "-" + j));
            }
        }
        inclusiveGraph.build(obstacles);

        // Route from top-left to bottom-right
        VisNode[] ports = inclusiveGraph.addPortNodes(50, 50, 1000, 800);
        assertTrue("Path should exist through dense grid in inclusive mode",
                pathExists(inclusiveGraph, ports[0], ports[1]));
    }

    // --- Perimeter routing tests (E2E 2026-03-12 finding) ---

    @Test
    public void shouldRouteAroundOutside_whenDenseFieldBetweenPorts() {
        // Simulates the CRM→Contact Centre scenario: source at bottom-left,
        // target at top-right, with a dense field of elements in between.
        // Without perimeter nodes, A* can't find a path around the outside.
        List<RoutingRect> obstacles = new ArrayList<>();
        // Create a 3x3 grid of obstacles filling the space between source and target
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                obstacles.add(new RoutingRect(
                        150 + i * 150, 150 + j * 150, 100, 80,
                        "obs-" + i + "-" + j));
            }
        }
        graph.build(obstacles);

        // Source below-left of grid, target above-right of grid
        VisNode[] ports = graph.addPortNodes(50, 500, 600, 50);

        assertTrue("Should find path around the outside of the dense obstacle field",
                pathExists(ports[0], ports[1]));
    }

    @Test
    public void shouldRouteAroundOutside_whenSourceAndTargetOnOppositeSides() {
        // Source far left, target far right, wall of obstacles in between
        List<RoutingRect> obstacles = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            obstacles.add(new RoutingRect(250, 50 + i * 100, 100, 60, "wall-" + i));
        }
        graph.build(obstacles);

        VisNode[] ports = graph.addPortNodes(50, 250, 500, 250);

        List<VisNode> path = findPath(ports[0], ports[1]);
        assertNotNull("Should find path around the wall via perimeter", path);
        assertTrue("Path should be multi-hop", path.size() >= 3);
    }

    // --- Edge density computation tests (Story 11-30) ---

    @Test
    public void shouldReturnZeroDensity_whenNoNearbyObstacles() {
        // Obstacle far from the edge midpoint
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(500, 500, 80, 60, "far"));
        graph.build(obstacles);

        // Edge from (50, 50) to (100, 50) — midpoint at (75, 50), far from obstacle at (500, 500)
        VisNode from = new VisNode(50, 50, NodeType.PORT);
        VisNode to = new VisNode(100, 50, NodeType.PORT);

        assertEquals("Density should be 0 with no nearby obstacles",
                0, graph.computeEdgeDensity(from, to));
    }

    @Test
    public void shouldReturnOneDensity_whenOneObstacleNearby() {
        // Single obstacle near the edge midpoint
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(80, 80, 40, 30, "near"));
        graph.build(obstacles);

        // Edge from (50, 100) to (150, 100) — midpoint at (100, 100)
        // Expanded obstacle: (70, 70) to (130, 120) — overlaps congestion region around (100, 100)
        VisNode from = new VisNode(50, 100, NodeType.PORT);
        VisNode to = new VisNode(150, 100, NodeType.PORT);

        assertEquals("Density should be 1 with one nearby obstacle",
                1, graph.computeEdgeDensity(from, to));
    }

    @Test
    public void shouldReturnHighDensity_whenDenseClusterNearby() {
        // Create a dense cluster of obstacles near the edge midpoint
        List<RoutingRect> obstacles = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            obstacles.add(new RoutingRect(
                    80 + i * 20, 80, 15, 15, "dense-" + i));
        }
        graph.build(obstacles);

        // Edge from (50, 90) to (250, 90) — midpoint at (150, 90)
        VisNode from = new VisNode(50, 90, NodeType.PORT);
        VisNode to = new VisNode(250, 90, NodeType.PORT);

        int density = graph.computeEdgeDensity(from, to);
        assertTrue("Density should be >= 3 for dense cluster, got " + density,
                density >= 3);
    }

    @Test
    public void shouldCountCorrectly_whenEdgeMidpointOnObstacleBoundary() {
        // Obstacle boundary aligns exactly with edge midpoint
        // Expanded obstacle at (90, 90) to (170, 150) for a RoutingRect at (100, 100, 60, 40)
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(100, 100, 60, 40, "boundary"));
        graph.build(obstacles);

        // Edge midpoint at (130, 120) — exactly inside expanded obstacle
        VisNode from = new VisNode(100, 120, NodeType.PORT);
        VisNode to = new VisNode(160, 120, NodeType.PORT);

        assertEquals("Density should be 1 when midpoint is on/inside obstacle boundary",
                1, graph.computeEdgeDensity(from, to));
    }

    // --- Helper methods ---

    private boolean pathExists(VisNode from, VisNode to) {
        return pathExists(graph, from, to);
    }

    private boolean pathExists(OrthogonalVisibilityGraph g, VisNode from, VisNode to) {
        return findPath(g, from, to) != null;
    }

    private boolean hasDirectEdge(VisNode from, VisNode to) {
        return hasDirectEdge(graph, from, to);
    }

    private boolean hasDirectEdge(OrthogonalVisibilityGraph g, VisNode from, VisNode to) {
        return g.getEdges(from).stream().anyMatch(e -> e.target().equals(to));
    }

    private List<VisNode> findPath(VisNode from, VisNode to) {
        return findPath(graph, from, to);
    }

    private List<VisNode> findPath(OrthogonalVisibilityGraph g, VisNode from, VisNode to) {
        Queue<VisNode> queue = new LinkedList<>();
        Map<VisNode, VisNode> parent = new java.util.HashMap<>();
        Set<VisNode> visited = new HashSet<>();

        queue.add(from);
        visited.add(from);
        parent.put(from, null);

        while (!queue.isEmpty()) {
            VisNode current = queue.poll();
            if (current.equals(to)) {
                List<VisNode> path = new ArrayList<>();
                VisNode node = to;
                while (node != null) {
                    path.add(0, node);
                    node = parent.get(node);
                }
                return path;
            }
            for (VisEdge edge : g.getEdges(current)) {
                if (!visited.contains(edge.target())) {
                    visited.add(edge.target());
                    parent.put(edge.target(), current);
                    queue.add(edge.target());
                }
            }
        }
        return null;
    }

    private boolean containsNodeAt(Set<VisNode> nodes, int x, int y) {
        return nodes.stream().anyMatch(n -> n.x() == x && n.y() == y);
    }

    /**
     * Tests if a segment between two nodes passes strictly through any obstacle
     * (using the original, non-expanded rectangles).
     */
    private boolean segmentPassesThroughObstacle(VisNode from, VisNode to,
                                                  List<RoutingRect> obstacles) {
        int x1 = from.x(), y1 = from.y();
        int x2 = to.x(), y2 = to.y();

        for (RoutingRect obs : obstacles) {
            int ox = obs.x(), oy = obs.y();
            int ow = obs.width(), oh = obs.height();

            if (x1 == x2) {
                // Vertical segment
                if (x1 > ox && x1 < ox + ow) {
                    int minY = Math.min(y1, y2);
                    int maxY = Math.max(y1, y2);
                    if (minY < oy + oh && maxY > oy) {
                        return true;
                    }
                }
            } else if (y1 == y2) {
                // Horizontal segment
                if (y1 > oy && y1 < oy + oh) {
                    int minX = Math.min(x1, x2);
                    int maxX = Math.max(x1, x2);
                    if (minX < ox + ow && maxX > ox) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
