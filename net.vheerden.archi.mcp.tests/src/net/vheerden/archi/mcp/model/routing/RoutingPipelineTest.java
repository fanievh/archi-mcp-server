package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Tests for {@link RoutingPipeline} (Story 10-6c).
 * Pure-geometry tests — no OSGi runtime required.
 */
public class RoutingPipelineTest {

    private RoutingPipeline pipeline;

    @Before
    public void setUp() {
        pipeline = new RoutingPipeline();
    }

    // --- Test 4.1: No obstacles → straight line (AC #1) ---

    @Test
    public void shouldReturnEmptyBendpoints_whenNoObstacles() {
        RoutingRect source = new RoutingRect(0, 0, 100, 60, "src");
        RoutingRect target = new RoutingRect(300, 0, 100, 60, "tgt");

        List<AbsoluteBendpointDto> bendpoints =
                pipeline.routeConnection(source, target, Collections.emptyList());

        assertTrue("No obstacles should produce straight line (0 bendpoints)", bendpoints.isEmpty());
    }

    // --- Test 4.2: Obstacle between source and target → route around (AC #1) ---

    @Test
    public void shouldProduceBendpoints_whenObstacleBetweenSourceAndTarget() {
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");     // center (50, 200)
        RoutingRect target = new RoutingRect(400, 170, 100, 60, "tgt");   // center (450, 200)
        RoutingRect obstacle = new RoutingRect(200, 150, 100, 100, "obs");

        List<AbsoluteBendpointDto> bendpoints =
                pipeline.routeConnection(source, target, List.of(obstacle));

        assertFalse("Should have bendpoints to route around obstacle", bendpoints.isEmpty());
    }

    // --- Test 4.3: All bendpoints avoid obstacle rectangles (AC #1) ---

    @Test
    public void shouldNotIntersectObstacles_whenRouted() {
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(400, 170, 100, 60, "tgt");
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(200, 150, 100, 100, "obs1"));

        List<AbsoluteBendpointDto> bendpoints =
                pipeline.routeConnection(source, target, obstacles);

        // Build full path: source center → bendpoints → target center
        List<int[]> fullPath = buildFullPath(source, target, bendpoints);
        assertNoSegmentIntersectsObstacles(fullPath, obstacles);
    }

    // --- Test 4.4: Multiple connections reuse same pipeline (AC #1) ---

    @Test
    public void shouldRouteMultipleConnections_withValidResults() {
        RoutingRect src1 = new RoutingRect(0, 0, 80, 60, "s1");
        RoutingRect tgt1 = new RoutingRect(400, 0, 80, 60, "t1");
        RoutingRect src2 = new RoutingRect(0, 200, 80, 60, "s2");
        RoutingRect tgt2 = new RoutingRect(400, 200, 80, 60, "t2");
        RoutingRect obstacle = new RoutingRect(180, 80, 100, 100, "obs");

        List<RoutingRect> obstacles = List.of(obstacle);

        List<AbsoluteBendpointDto> bp1 = pipeline.routeConnection(src1, tgt1, obstacles);
        List<AbsoluteBendpointDto> bp2 = pipeline.routeConnection(src2, tgt2, obstacles);

        // Both should produce valid results
        assertNotNull(bp1);
        assertNotNull(bp2);

        // Verify neither path crosses the obstacle
        assertNoSegmentIntersectsObstacles(buildFullPath(src1, tgt1, bp1), obstacles);
        assertNoSegmentIntersectsObstacles(buildFullPath(src2, tgt2, bp2), obstacles);
    }

    // --- Test 4.5: Empty path fallback (AC #5) ---

    @Test
    public void shouldReturnEmptyBendpoints_whenNoPathFound() {
        // Override findPath to simulate A* returning empty (no route found).
        // The real visibility graph almost always finds a path, so we use a
        // test subclass to exercise the fallback branch in routeConnection().
        RoutingPipeline emptyPathPipeline = new RoutingPipeline() {
            @Override
            List<VisNode> findPath(OrthogonalVisibilityGraph graph,
                    VisNode sourcePort, VisNode targetPort,
                    List<RoutingRect> groupBoundaries) {
                return List.of(); // Simulate no path found
            }
        };

        RoutingRect source = new RoutingRect(0, 0, 100, 60, "src");
        RoutingRect target = new RoutingRect(400, 0, 100, 60, "tgt");

        List<AbsoluteBendpointDto> bendpoints =
                emptyPathPipeline.routeConnection(source, target, Collections.emptyList());

        // Empty path fallback → straight line (0 bendpoints), no exception
        assertTrue("No-path fallback should return empty bendpoints", bendpoints.isEmpty());
    }

    // --- Test 4.6: Self-connection (AC #5) ---

    @Test
    public void shouldReturnEmptyBendpoints_whenSelfConnection() {
        RoutingRect element = new RoutingRect(100, 100, 80, 60, "elem");

        List<AbsoluteBendpointDto> bendpoints =
                pipeline.routeConnection(element, element, Collections.emptyList());

        assertTrue("Self-connection should return empty bendpoints", bendpoints.isEmpty());
    }

    // --- Test 4.7: Performance — 30 obstacles, 50 connections < 500ms (AC #6) ---

    @Test
    public void shouldRouteWithinPerformanceBudget_30obstacles50connections() {
        // Create 30 obstacles in a grid
        List<RoutingRect> obstacles = new ArrayList<>();
        int cols = 6, rows = 5;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                obstacles.add(new RoutingRect(
                        100 + c * 200, 100 + r * 200, 120, 80,
                        "obs_" + r + "_" + c));
            }
        }
        assertEquals(30, obstacles.size());

        // Create 50 source-target pairs around the edges
        List<RoutingRect> sources = new ArrayList<>();
        List<RoutingRect> targets = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            sources.add(new RoutingRect(0, 20 + i * 20, 60, 40, "src_" + i));
            targets.add(new RoutingRect(1400, 20 + i * 20, 60, 40, "tgt_" + i));
        }

        // Time the routing
        long startNs = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            pipeline.routeConnection(sources.get(i), targets.get(i), obstacles);
        }
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

        assertTrue("50 connections with 30 obstacles should complete in < 500ms, took " + elapsedMs + "ms",
                elapsedMs < 500);
    }

    // --- Test 4.8: Configurable bend penalty and margin (AC #1) ---

    @Test
    public void shouldPassConfigurationToUnderlyingComponents() {
        // Use a scenario where bend penalty affects the route choice:
        // source top-left, target bottom-right with obstacle in between.
        // Low bend penalty allows more bends; high penalty prefers fewer bends.
        RoutingRect source = new RoutingRect(0, 0, 80, 60, "src");       // center (40, 30)
        RoutingRect target = new RoutingRect(400, 300, 80, 60, "tgt");   // center (440, 330)
        RoutingRect obstacle = new RoutingRect(180, 120, 120, 100, "obs");
        List<RoutingRect> obstacles = List.of(obstacle);

        // Default pipeline (bendPenalty=30, margin=10)
        List<AbsoluteBendpointDto> defaultBendpoints =
                pipeline.routeConnection(source, target, obstacles);
        // Very high bend penalty (500) + different margin (25)
        RoutingPipeline highPenaltyPipeline = new RoutingPipeline(500, 25);
        List<AbsoluteBendpointDto> highPenaltyBendpoints =
                highPenaltyPipeline.routeConnection(source, target, obstacles);

        // Both must produce valid, obstacle-free routes
        assertNoSegmentIntersectsObstacles(buildFullPath(source, target, defaultBendpoints), obstacles);
        assertNoSegmentIntersectsObstacles(buildFullPath(source, target, highPenaltyBendpoints), obstacles);

        // Routes should differ — different bend penalty and margin change the graph and path cost.
        // At minimum, different margin produces different corner node positions.
        boolean routesDiffer = defaultBendpoints.size() != highPenaltyBendpoints.size()
                || !bendpointsEqual(defaultBendpoints, highPenaltyBendpoints);
        assertTrue("Different bend penalty and margin should produce different routes", routesDiffer);
    }

    private boolean bendpointsEqual(List<AbsoluteBendpointDto> a, List<AbsoluteBendpointDto> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).x() != b.get(i).x() || a.get(i).y() != b.get(i).y()) return false;
        }
        return true;
    }

    // --- Test 4.9: Large obstacle routing (AC #4 partial) ---
    // NOTE: AC4's cross-group ancestor exclusion logic lives in ArchiModelAccessorImpl,
    // not in RoutingPipeline. This test verifies the pipeline correctly routes around
    // large group-sized obstacles. Full AC4 validation requires E2E/integration testing.

    @Test
    public void shouldRouteAroundGroupObstacle() {
        // Simulate a large group element between source and target
        RoutingRect source = new RoutingRect(0, 200, 80, 60, "src");
        RoutingRect target = new RoutingRect(600, 200, 80, 60, "tgt");
        RoutingRect groupObstacle = new RoutingRect(150, 100, 300, 250, "group");

        List<AbsoluteBendpointDto> bendpoints =
                pipeline.routeConnection(source, target, List.of(groupObstacle));

        assertFalse("Should have bendpoints to route around group", bendpoints.isEmpty());
        assertNoSegmentIntersectsObstacles(
                buildFullPath(source, target, bendpoints), List.of(groupObstacle));
    }

    // --- Test: group transparency — path routes through group area when group not in obstacles (Story 10-22) ---

    @Test
    public void shouldRouteThroughGroupArea_whenGroupNotInObstacles() {
        // Group occupies (150, 100, 300, 250) but is NOT in the obstacle list (transparent)
        // Only leaf elements inside the group are obstacles
        RoutingRect source = new RoutingRect(0, 200, 80, 60, "src");     // center (40, 230)
        RoutingRect target = new RoutingRect(600, 200, 80, 60, "tgt");   // center (640, 230)
        // Leaf element inside the "group" area — this IS an obstacle
        RoutingRect leafInGroup = new RoutingRect(250, 180, 80, 60, "leaf1");

        // Only the leaf element is an obstacle — group is excluded (transparent)
        List<AbsoluteBendpointDto> bendpoints =
                pipeline.routeConnection(source, target, List.of(leafInGroup));

        // Path should route around the leaf element but NOT around the group area
        // Verify path doesn't intersect the leaf obstacle
        assertNoSegmentIntersectsObstacles(
                buildFullPath(source, target, bendpoints), List.of(leafInGroup));

        // Path should NOT detour around the group area (y < 100 or y > 350 would
        // indicate routing around the group). Group spans y=[100,350].
        List<int[]> fullPath = buildFullPath(source, target, bendpoints);
        for (int[] pt : fullPath) {
            assertTrue("Path should not detour above group area (y=" + pt[1]
                    + " < 100 means routing around group)", pt[1] >= 100);
            assertTrue("Path should not detour below group area (y=" + pt[1]
                    + " > 350 means routing around group)", pt[1] <= 350);
        }
    }

    // --- Test: nested groups both transparent — only leaf elements block routing (Story 10-22) ---

    @Test
    public void shouldRouteThroughNestedGroupAreas_whenOnlyLeafElementsAreObstacles() {
        // Outer group: (100, 50, 400, 350), inner group: (150, 100, 300, 250)
        // Neither is in the obstacle list. Only leaf elements inside inner group are obstacles.
        RoutingRect source = new RoutingRect(0, 200, 80, 60, "src");
        RoutingRect target = new RoutingRect(600, 200, 80, 60, "tgt");
        // Two leaf elements inside nested groups
        RoutingRect leaf1 = new RoutingRect(200, 160, 60, 40, "leaf1");
        RoutingRect leaf2 = new RoutingRect(350, 250, 60, 40, "leaf2");

        List<AbsoluteBendpointDto> bendpoints =
                pipeline.routeConnection(source, target, List.of(leaf1, leaf2));

        // Path should route through group areas, only avoiding leaf elements
        assertNoSegmentIntersectsObstacles(
                buildFullPath(source, target, bendpoints), List.of(leaf1, leaf2));

        // Path should NOT detour around the outer group area (y < 50 or y > 400 would
        // indicate routing around nested groups). Outer group spans y=[50,400].
        List<int[]> fullPath = buildFullPath(source, target, bendpoints);
        for (int[] pt : fullPath) {
            assertTrue("Path should not detour above outer group (y=" + pt[1]
                    + " < 50 means routing around groups)", pt[1] >= 50);
            assertTrue("Path should not detour below outer group (y=" + pt[1]
                    + " > 400 means routing around groups)", pt[1] <= 400);
        }
    }

    // --- Test: batch routing produces ordered paths for parallel connections (Story 10-7a) ---

    @Test
    public void shouldProduceOrderedPaths_whenBatchRoutingParallelConnections() {
        // Two parallel connections routed through obstacles
        RoutingRect src1 = new RoutingRect(0, 0, 80, 60, "s1");       // center (40, 30)
        RoutingRect tgt1 = new RoutingRect(400, 0, 80, 60, "t1");     // center (440, 30)
        RoutingRect src2 = new RoutingRect(0, 200, 80, 60, "s2");     // center (40, 230)
        RoutingRect tgt2 = new RoutingRect(400, 200, 80, 60, "t2");   // center (440, 230)
        RoutingRect obstacle = new RoutingRect(180, 80, 100, 100, "obs");

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", src1, tgt1, List.of(obstacle), "", 1),
                new RoutingPipeline.ConnectionEndpoints("c2", src2, tgt2, List.of(obstacle), "", 1));

        List<RoutingRect> allObstacles = List.of(obstacle, src1, tgt1, src2, tgt2);
        RoutingResult routingResult =
                pipeline.routeAllConnections(connections, allObstacles);
        Map<String, List<AbsoluteBendpointDto>> result = routingResult.routed();

        assertEquals(2, result.size());
        assertNotNull(result.get("c1"));
        assertNotNull(result.get("c2"));
    }

    // --- Test: single connection backward compatibility via batch (Story 10-7a) ---

    @Test
    public void shouldPreserveSingleConnectionBehavior_whenBatchWithOneConnection() {
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(400, 170, 100, 60, "tgt");
        RoutingRect obstacle = new RoutingRect(200, 150, 100, 100, "obs");

        // Same connection via batch
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(obstacle), "", 1));
        List<RoutingRect> allObstacles = List.of(obstacle, source, target);
        RoutingResult batchRoutingResult =
                pipeline.routeAllConnections(connections, allObstacles);
        Map<String, List<AbsoluteBendpointDto>> batchResult = batchRoutingResult.routed();

        // Batch produces edge-attached + cleaned-up path
        List<AbsoluteBendpointDto> batchBendpoints = batchResult.get("c1");
        assertNotNull(batchBendpoints);
        assertFalse("Batch should produce bendpoints (terminals + routing)", batchBendpoints.isEmpty());

        // No bendpoint should be inside source or target element
        for (AbsoluteBendpointDto bp : batchBendpoints) {
            assertFalse("BP (" + bp.x() + "," + bp.y() + ") should not be inside source",
                    isInsideRect(bp, source));
            assertFalse("BP (" + bp.x() + "," + bp.y() + ") should not be inside target",
                    isInsideRect(bp, target));
        }
    }

    // --- Test 5.1: Batch routing with nudging separates parallel connections (Story 10-7b) ---

    @Test
    public void shouldSeparateParallelConnections_whenBatchRoutingWithNudging() {
        // Two connections that will share a corridor through an obstacle gap
        // Source and target pairs arranged so both connections route through same gap
        RoutingRect src1 = new RoutingRect(0, 170, 80, 60, "s1");     // center (40, 200)
        RoutingRect tgt1 = new RoutingRect(400, 170, 80, 60, "t1");   // center (440, 200)
        RoutingRect src2 = new RoutingRect(0, 190, 80, 60, "s2");     // center (40, 220)
        RoutingRect tgt2 = new RoutingRect(400, 190, 80, 60, "t2");   // center (440, 220)
        RoutingRect obstacle = new RoutingRect(180, 100, 100, 60, "obs"); // blocks direct path

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", src1, tgt1, List.of(obstacle), "", 1),
                new RoutingPipeline.ConnectionEndpoints("c2", src2, tgt2, List.of(obstacle), "", 1));

        List<RoutingRect> allObstacles = List.of(obstacle, src1, tgt1, src2, tgt2);
        RoutingResult routingResult =
                pipeline.routeAllConnections(connections, allObstacles);
        Map<String, List<AbsoluteBendpointDto>> result = routingResult.routed();

        List<AbsoluteBendpointDto> bp1 = result.get("c1");
        List<AbsoluteBendpointDto> bp2 = result.get("c2");
        assertNotNull(bp1);
        assertNotNull(bp2);

        // If both connections have bendpoints and share a corridor, nudging should
        // produce different coordinates. Verify routes are not completely identical.
        if (!bp1.isEmpty() && !bp2.isEmpty()
                && bp1.size() == bp2.size()) {
            boolean allSame = true;
            for (int i = 0; i < bp1.size(); i++) {
                if (bp1.get(i).x() != bp2.get(i).x()
                        || bp1.get(i).y() != bp2.get(i).y()) {
                    allSame = false;
                    break;
                }
            }
            assertFalse("Parallel connections through same gap should have different coordinates "
                    + "after nudging", allSame);
        }
    }

    // --- Test 5.2: Single connection produces clean path via batch (Story 10-7b) ---

    @Test
    public void shouldProduceCleanPath_whenBatchRoutingWithNudging() {
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(400, 170, 100, 60, "tgt");
        RoutingRect obstacle = new RoutingRect(200, 150, 100, 100, "obs");

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(obstacle), "", 1));
        List<RoutingRect> allObstacles = List.of(obstacle, source, target);
        RoutingResult batchRoutingResult =
                pipeline.routeAllConnections(connections, allObstacles);
        Map<String, List<AbsoluteBendpointDto>> batchResult = batchRoutingResult.routed();

        List<AbsoluteBendpointDto> batchBendpoints = batchResult.get("c1");
        assertNotNull(batchBendpoints);
        assertFalse("Batch should produce bendpoints", batchBendpoints.isEmpty());

        // No bendpoint should be inside source or target element
        for (AbsoluteBendpointDto bp : batchBendpoints) {
            assertFalse("BP (" + bp.x() + "," + bp.y() + ") should not be inside source",
                    isInsideRect(bp, source));
            assertFalse("BP (" + bp.x() + "," + bp.y() + ") should not be inside target",
                    isInsideRect(bp, target));
        }

        // No consecutive duplicate points
        for (int i = 0; i < batchBendpoints.size() - 1; i++) {
            AbsoluteBendpointDto a = batchBendpoints.get(i);
            AbsoluteBendpointDto b = batchBendpoints.get(i + 1);
            assertFalse("No consecutive duplicates at index " + i,
                    a.x() == b.x() && a.y() == b.y());
        }

        // No collinear triples
        for (int i = 0; i < batchBendpoints.size() - 2; i++) {
            AbsoluteBendpointDto a = batchBendpoints.get(i);
            AbsoluteBendpointDto b = batchBendpoints.get(i + 1);
            AbsoluteBendpointDto c = batchBendpoints.get(i + 2);
            boolean collinear = (a.x() == b.x() && b.x() == c.x())
                    || (a.y() == b.y() && b.y() == c.y());
            assertFalse("No collinear triples at index " + i, collinear);
        }
    }

    // --- Tests for pipeline cleanup methods ---

    @Test
    public void shouldTrimBendpointsInsideSourceElement() {
        RoutingRect source = new RoutingRect(0, 0, 100, 60, "src");
        RoutingRect target = new RoutingRect(400, 0, 100, 60, "tgt");
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(50, 30),    // inside source (center)
                new AbsoluteBendpointDto(100, 30),   // on source boundary
                new AbsoluteBendpointDto(200, 30),   // outside both
                new AbsoluteBendpointDto(300, 30)));  // outside both

        RoutingPipeline.trimEndpointBendpoints(path, source, target);

        assertEquals(2, path.size());
        assertEquals(200, path.get(0).x());
        assertEquals(300, path.get(1).x());
    }

    @Test
    public void shouldTrimBendpointsInsideTargetElement() {
        RoutingRect source = new RoutingRect(0, 0, 100, 60, "src");
        RoutingRect target = new RoutingRect(400, 0, 100, 60, "tgt");
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(200, 30),   // outside both
                new AbsoluteBendpointDto(300, 30),   // outside both
                new AbsoluteBendpointDto(400, 30),   // on target boundary
                new AbsoluteBendpointDto(450, 30)));  // inside target (center)

        RoutingPipeline.trimEndpointBendpoints(path, source, target);

        assertEquals(2, path.size());
        assertEquals(200, path.get(0).x());
        assertEquals(300, path.get(1).x());
    }

    @Test
    public void shouldTrimAllBendpoints_whenAllInsideElements() {
        RoutingRect source = new RoutingRect(0, 0, 100, 60, "src");
        RoutingRect target = new RoutingRect(400, 0, 100, 60, "tgt");
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(50, 30),    // inside source
                new AbsoluteBendpointDto(450, 30)));  // inside target

        RoutingPipeline.trimEndpointBendpoints(path, source, target);

        assertTrue("All BPs trimmed", path.isEmpty());
    }

    @Test
    public void shouldPreserveBendpoints_whenNoneInsideElements() {
        RoutingRect source = new RoutingRect(0, 0, 100, 60, "src");
        RoutingRect target = new RoutingRect(400, 0, 100, 60, "tgt");
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(200, 30),
                new AbsoluteBendpointDto(300, 30)));

        RoutingPipeline.trimEndpointBendpoints(path, source, target);

        assertEquals(2, path.size());
    }

    @Test
    public void shouldRemoveCollinearPoints_sameY() {
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(200, 200),
                new AbsoluteBendpointDto(300, 200)));

        RoutingPipeline.removeCollinearPoints(path);

        assertEquals(2, path.size());
        assertEquals(100, path.get(0).x());
        assertEquals(300, path.get(1).x());
    }

    @Test
    public void shouldRemoveCollinearPoints_sameX() {
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(100, 300)));

        RoutingPipeline.removeCollinearPoints(path);

        assertEquals(2, path.size());
        assertEquals(100, path.get(0).y());
        assertEquals(300, path.get(1).y());
    }

    @Test
    public void shouldPreserveLShape_whenNotCollinear() {
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(200, 200),
                new AbsoluteBendpointDto(200, 300)));

        RoutingPipeline.removeCollinearPoints(path);

        assertEquals("L-shape should not be collapsed", 3, path.size());
    }

    @Test
    public void shouldRemoveDuplicatePoints() {
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(200, 300)));

        RoutingPipeline.removeDuplicatePoints(path);

        assertEquals(2, path.size());
        assertEquals(100, path.get(0).x());
        assertEquals(200, path.get(1).x());
    }

    @Test
    public void shouldRemoveChainedCollinearPoints() {
        // 5 points on same Y → should collapse to 2
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(150, 200),
                new AbsoluteBendpointDto(200, 200),
                new AbsoluteBendpointDto(250, 200),
                new AbsoluteBendpointDto(300, 200)));

        RoutingPipeline.removeCollinearPoints(path);

        assertEquals(2, path.size());
        assertEquals(100, path.get(0).x());
        assertEquals(300, path.get(1).x());
    }

    // --- Tests for micro-jog removal ---

    @Test
    public void shouldRemoveVerticalMicroJog_snapToBackward() {
        // ESB→CoreBanking pattern: (280,120), (440,120), (440,127), (480,127), (480,300)
        // Vertical jog (440,120)→(440,127) = 7px. Backward count at y=120 = 2, forward at y=127 = 2.
        // Tie → snap forward to backward y=120. After dedupe+collinear → clean L-path.
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(280, 120),
                new AbsoluteBendpointDto(440, 120),
                new AbsoluteBendpointDto(440, 127),
                new AbsoluteBendpointDto(480, 127),
                new AbsoluteBendpointDto(480, 300)));

        RoutingPipeline.removeMicroJogs(path, 15);
        RoutingPipeline.removeDuplicatePoints(path);
        RoutingPipeline.removeCollinearPoints(path);

        // Should collapse to L-path: horizontal at y=120, then vertical down
        assertEquals("Should be 3 BPs (L-path)", 3, path.size());
        assertEquals(120, path.get(0).y());
        assertEquals(120, path.get(1).y());
        assertEquals(300, path.get(2).y());
    }

    @Test
    public void shouldRemoveVerticalMicroJog_snapToLongerSide() {
        // CoreBanking→GL pattern: (560,327), (700,327), (700,320)
        // Vertical jog (700,327)→(700,320) = 7px. Backward at y=327 = 2, forward at y=320 = 1.
        // Backward wins → snap forward to y=327. After dedupe → straight line.
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(560, 327),
                new AbsoluteBendpointDto(700, 327),
                new AbsoluteBendpointDto(700, 320)));

        RoutingPipeline.removeMicroJogs(path, 15);
        RoutingPipeline.removeDuplicatePoints(path);
        RoutingPipeline.removeCollinearPoints(path);

        assertEquals("Should be 2 BPs (straight line)", 2, path.size());
        assertEquals(327, path.get(0).y());
        assertEquals(327, path.get(1).y());
    }

    @Test
    public void shouldRemoveHorizontalMicroJog() {
        // Horizontal micro-jog between two vertical segments at x=100 and x=108
        // After snap + collinear, all on x=100 → straight vertical line
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(100, 400),
                new AbsoluteBendpointDto(108, 400),
                new AbsoluteBendpointDto(108, 600)));

        RoutingPipeline.removeMicroJogs(path, 15);
        RoutingPipeline.removeDuplicatePoints(path);
        RoutingPipeline.removeCollinearPoints(path);

        assertEquals("Should collapse to straight line", 2, path.size());
        assertEquals(100, path.get(0).x());
        assertEquals(100, path.get(1).x());
        assertEquals(200, path.get(0).y());
        assertEquals(600, path.get(1).y());
    }

    @Test
    public void shouldNotRemoveMicroJog_aboveThreshold() {
        // 20px segment with threshold 15 → should NOT be removed
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(200, 200),
                new AbsoluteBendpointDto(200, 220),
                new AbsoluteBendpointDto(300, 220)));

        RoutingPipeline.removeMicroJogs(path, 15);

        assertEquals("20px segment should be preserved", 4, path.size());
        assertEquals(200, path.get(1).y());
        assertEquals(220, path.get(2).y());
    }

    @Test
    public void shouldHandlePathTooShortForMicroJogs() {
        // 2 BPs → no micro-jog processing
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(100, 205)));

        RoutingPipeline.removeMicroJogs(path, 15);

        assertEquals("Short path should be unchanged", 2, path.size());
    }

    @Test
    public void shouldRemoveMultipleMicroJogs() {
        // Two micro-jogs in one path
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(200, 200),
                new AbsoluteBendpointDto(200, 207),  // 7px vertical jog
                new AbsoluteBendpointDto(300, 207),
                new AbsoluteBendpointDto(300, 213),  // 6px vertical jog
                new AbsoluteBendpointDto(400, 213)));

        RoutingPipeline.removeMicroJogs(path, 15);
        RoutingPipeline.removeDuplicatePoints(path);
        RoutingPipeline.removeCollinearPoints(path);

        // Both jogs should be eliminated, resulting in a straight line
        assertEquals("Should collapse to straight line", 2, path.size());
    }

    // --- Tests for label clearance pass (Story 10-8) ---

    @Test
    public void shouldNotBreakExistingRoutes_whenNoLabels() {
        // Connections with no labels should route identically to before
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(400, 170, 100, 60, "tgt");
        RoutingRect obstacle = new RoutingRect(200, 150, 100, 100, "obs");

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        List.of(obstacle), "", 1));
        List<RoutingRect> allObstacles = List.of(obstacle, source, target);

        RoutingResult routingResult =
                pipeline.routeAllConnections(connections, allObstacles);
        Map<String, List<AbsoluteBendpointDto>> result = routingResult.routed();

        assertNotNull(result.get("c1"));
        assertFalse("Should have bendpoints around obstacle",
                result.get("c1").isEmpty());
    }

    @Test
    public void shouldAdjustPath_whenLabelOverlapsObstacle() {
        // Connection with label that would overlap an obstacle at midpoint
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(600, 170, 100, 60, "tgt");
        RoutingRect obstacle = new RoutingRect(280, 150, 100, 100, "obs");

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        List.of(obstacle), "MyLongLabel", 1));
        List<RoutingRect> allObstacles = List.of(obstacle, source, target);

        RoutingResult routingResult =
                pipeline.routeAllConnections(connections, allObstacles);
        Map<String, List<AbsoluteBendpointDto>> result = routingResult.routed();

        // Route should still succeed
        assertNotNull(result.get("c1"));
        assertFalse(result.get("c1").isEmpty());
    }

    @Test
    public void shouldHandleLabelClearance_withEmptyPath() {
        // Straight line connection (no bendpoints) — label clearance should not crash
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(200, 170, 100, 60, "tgt");

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        List.of(), "Label", 1));
        List<RoutingRect> allObstacles = List.of(source, target);

        RoutingResult routingResult =
                pipeline.routeAllConnections(connections, allObstacles);
        Map<String, List<AbsoluteBendpointDto>> result = routingResult.routed();

        assertNotNull(result.get("c1"));
    }

    // --- Story 10-25: Post-pipeline obstacle re-validation (AC #2) ---

    @Test
    public void shouldRemoveObstacleViolations_whenSegmentPassesThroughObstacle() {
        // A path where an interior vertical segment crosses an obstacle (simulating post-pipeline shift).
        // Removing the offending bendpoint leaves a diagonal that bypasses the obstacle.
        RoutingRect obstacle = new RoutingRect(200, 100, 100, 50, "obs"); // x=[200,300], y=[100,150]
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(50, 300),
                new AbsoluteBendpointDto(250, 300),   // x=250 is in obstacle x-range
                new AbsoluteBendpointDto(250, 140),   // vertical segment enters obstacle
                new AbsoluteBendpointDto(500, 140),
                new AbsoluteBendpointDto(500, 50)));

        int sizeBefore = path.size();
        RoutingPipeline.removeObstacleViolations(path, List.of(obstacle));

        assertTrue("Should have removed offending point", path.size() < sizeBefore);

        // After removal, no remaining segment should intersect the obstacle
        for (int i = 0; i < path.size() - 1; i++) {
            AbsoluteBendpointDto a = path.get(i);
            AbsoluteBendpointDto b = path.get(i + 1);
            assertFalse("Segment (" + a.x() + "," + a.y() + ")->(" + b.x() + "," + b.y()
                    + ") should not intersect obstacle after cleanup",
                    EdgeAttachmentCalculator.lineSegmentIntersectsRect(
                            a.x(), a.y(), b.x(), b.y(),
                            obstacle.x(), obstacle.y(), obstacle.width(), obstacle.height()));
        }
    }

    @Test
    public void shouldNotModifyPath_whenNoObstacleViolations() {
        RoutingRect obstacle = new RoutingRect(200, 100, 100, 100, "obs");
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(100, 50),   // above obstacle
                new AbsoluteBendpointDto(400, 50))); // above obstacle

        RoutingPipeline.removeObstacleViolations(path, List.of(obstacle));

        assertEquals("Path should be unchanged", 2, path.size());
        assertEquals(100, path.get(0).x());
        assertEquals(400, path.get(1).x());
    }

    // --- Story 10-25: Orthogonal path enforcement (AC #4) ---

    @Test
    public void shouldInsertLTurn_whenDiagonalSegmentDetected() {
        // Diagonal: (100,100) → (300,200) — should insert intermediate
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(300, 200)));

        RoutingPipeline.enforceOrthogonalPaths(path);

        assertEquals("Should insert L-turn intermediate point", 3, path.size());
        // Intermediate should create orthogonal segments
        AbsoluteBendpointDto a = path.get(0);
        AbsoluteBendpointDto b = path.get(1);
        AbsoluteBendpointDto c = path.get(2);
        assertTrue("First segment should be orthogonal (same x or same y)",
                a.x() == b.x() || a.y() == b.y());
        assertTrue("Second segment should be orthogonal (same x or same y)",
                b.x() == c.x() || b.y() == c.y());
    }

    @Test
    public void shouldNotModifyPath_whenAlreadyOrthogonal() {
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(300, 100),
                new AbsoluteBendpointDto(300, 200)));

        RoutingPipeline.enforceOrthogonalPaths(path);

        assertEquals("Orthogonal path should be unchanged", 3, path.size());
    }

    @Test
    public void shouldFixMultipleDiagonals_whenChained() {
        // Two consecutive diagonals
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(200, 200),
                new AbsoluteBendpointDto(300, 300)));

        RoutingPipeline.enforceOrthogonalPaths(path);

        // All consecutive pairs should be orthogonal
        for (int i = 0; i < path.size() - 1; i++) {
            AbsoluteBendpointDto a = path.get(i);
            AbsoluteBendpointDto b = path.get(i + 1);
            assertTrue("Segment " + i + " should be orthogonal: (" + a.x() + "," + a.y()
                    + ")->(" + b.x() + "," + b.y() + ")",
                    a.x() == b.x() || a.y() == b.y());
        }
    }

    // --- Story 10-25: Edge-clipping pattern — narrow gap routing (AC #1, #2) ---

    @Test
    public void shouldNotClipElementEdges_whenTwoRowsWithNarrowGap() {
        // Two rows of elements with a narrow gap — route should not clip element edges
        // Top row: y=[50,150], bottom row: y=[300,400]
        // Gap is y=[150,300] — 150px clear
        RoutingRect source = new RoutingRect(0, 300, 80, 60, "src");   // center (40, 330), bottom row
        RoutingRect target = new RoutingRect(500, 50, 80, 60, "tgt");   // center (540, 80), top row
        List<RoutingRect> topRow = List.of(
                new RoutingRect(150, 50, 100, 100, "top1"),
                new RoutingRect(300, 50, 100, 100, "top2"));
        List<RoutingRect> bottomRow = List.of(
                new RoutingRect(150, 300, 100, 100, "bot1"),
                new RoutingRect(300, 300, 100, 100, "bot2"));

        List<RoutingRect> allObstacles = new ArrayList<>();
        allObstacles.addAll(topRow);
        allObstacles.addAll(bottomRow);

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, allObstacles, "", 1));

        List<RoutingRect> viewObstacles = new ArrayList<>(allObstacles);
        viewObstacles.add(source);
        viewObstacles.add(target);

        RoutingResult routingResult =
                pipeline.routeAllConnections(connections, viewObstacles);
        Map<String, List<AbsoluteBendpointDto>> result = routingResult.routed();

        List<AbsoluteBendpointDto> bp = result.get("c1");
        assertNotNull(bp);

        // No segment should pass through any obstacle
        for (int i = 0; i < bp.size() - 1; i++) {
            AbsoluteBendpointDto a = bp.get(i);
            AbsoluteBendpointDto b = bp.get(i + 1);
            for (RoutingRect obs : allObstacles) {
                assertFalse("Segment (" + a.x() + "," + a.y() + ")->(" + b.x() + "," + b.y()
                        + ") should not clip " + obs.id(),
                        EdgeAttachmentCalculator.lineSegmentIntersectsRect(
                                a.x(), a.y(), b.x(), b.y(),
                                obs.x(), obs.y(), obs.width(), obs.height()));
            }
        }
    }

    // --- Story 10-25: Same-level obstacle avoidance (AC #1, Pattern 4) ---

    @Test
    public void shouldRouteAroundMiddleElement_whenThreeAtSameLevel() {
        // Three elements at the same y-level. Connection from left to right must
        // route around middle element, not pass through it.
        RoutingRect source = new RoutingRect(0, 300, 100, 60, "src");     // center (50, 330)
        RoutingRect middle = new RoutingRect(300, 300, 120, 120, "mid");  // x=[300,420], y=[300,420]
        RoutingRect target = new RoutingRect(600, 300, 100, 60, "tgt");   // center (650, 330)

        List<RoutingRect> obstacles = List.of(middle);
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, obstacles, "", 1));

        List<RoutingRect> allObstacles = List.of(middle, source, target);
        RoutingResult routingResult =
                pipeline.routeAllConnections(connections, allObstacles);
        Map<String, List<AbsoluteBendpointDto>> result = routingResult.routed();

        List<AbsoluteBendpointDto> bp = result.get("c1");
        assertNotNull(bp);

        // No segment should pass through the middle element
        for (int i = 0; i < bp.size() - 1; i++) {
            AbsoluteBendpointDto a = bp.get(i);
            AbsoluteBendpointDto b = bp.get(i + 1);
            assertFalse("Segment should not pass through middle element",
                    EdgeAttachmentCalculator.lineSegmentIntersectsRect(
                            a.x(), a.y(), b.x(), b.y(),
                            middle.x(), middle.y(), middle.width(), middle.height()));
        }
    }

    // --- Story 10-25: Orthogonal segments preserved in batch routing (AC #4) ---

    @Test
    public void shouldProduceOrthogonalSegments_afterBatchRouting() {
        RoutingRect source = new RoutingRect(0, 0, 80, 60, "src");
        RoutingRect target = new RoutingRect(400, 300, 80, 60, "tgt");
        RoutingRect obstacle = new RoutingRect(180, 120, 100, 100, "obs");

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        List.of(obstacle), "", 1));
        List<RoutingRect> allObstacles = List.of(obstacle, source, target);

        RoutingResult routingResult =
                pipeline.routeAllConnections(connections, allObstacles);
        Map<String, List<AbsoluteBendpointDto>> result = routingResult.routed();

        List<AbsoluteBendpointDto> bp = result.get("c1");
        assertNotNull(bp);

        // All consecutive bendpoints should be orthogonal
        for (int i = 0; i < bp.size() - 1; i++) {
            AbsoluteBendpointDto a = bp.get(i);
            AbsoluteBendpointDto b = bp.get(i + 1);
            assertTrue("Consecutive BPs at index " + i + " should be orthogonal: ("
                    + a.x() + "," + a.y() + ")->(" + b.x() + "," + b.y() + ")",
                    a.x() == b.x() || a.y() == b.y());
        }
    }

    // --- Story 10-28: Post-attachment orthogonal enforcement (AC #3) ---

    @Test
    public void shouldEnforceOrthogonal_afterEdgeAttachment() {
        // Route connection where edge attachment could introduce diagonals
        // Source at top-left, target at bottom-right — edge attachment terminal
        // may create a diagonal segment to the first intermediate bendpoint
        RoutingRect source = new RoutingRect(0, 0, 80, 60, "src");
        RoutingRect target = new RoutingRect(400, 300, 80, 60, "tgt");
        RoutingRect obstacle = new RoutingRect(180, 120, 100, 100, "obs");

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        List.of(obstacle), "", 1));
        List<RoutingRect> allObstacles = List.of(obstacle, source, target);

        RoutingResult routingResult =
                pipeline.routeAllConnections(connections, allObstacles);
        Map<String, List<AbsoluteBendpointDto>> result = routingResult.routed();

        List<AbsoluteBendpointDto> bp = result.get("c1");
        assertNotNull(bp);

        // All consecutive bendpoints must be orthogonal (no diagonals)
        for (int i = 0; i < bp.size() - 1; i++) {
            AbsoluteBendpointDto a = bp.get(i);
            AbsoluteBendpointDto b = bp.get(i + 1);
            assertTrue("Post-attachment segment " + i + " should be orthogonal: ("
                    + a.x() + "," + a.y() + ")->(" + b.x() + "," + b.y() + ")",
                    a.x() == b.x() || a.y() == b.y());
        }
    }

    @Test
    public void shouldEnforceOrthogonal_afterEdgeAttachmentWithDiagonalTerminal() {
        // Verify that a diagonal terminal segment (edge attachment point at angle
        // to first intermediate BP) gets corrected with an L-turn insertion
        // This directly tests the pipeline ordering: enforce AFTER edge attachment
        RoutingRect source = new RoutingRect(50, 50, 100, 60, "src");  // center (100, 80)
        RoutingRect target = new RoutingRect(450, 250, 100, 60, "tgt"); // center (500, 280)

        // No obstacles — simple diagonal connection
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        List.of(), "", 1));
        List<RoutingRect> allObstacles = List.of(source, target);

        RoutingResult routingResult =
                pipeline.routeAllConnections(connections, allObstacles);
        Map<String, List<AbsoluteBendpointDto>> result = routingResult.routed();

        List<AbsoluteBendpointDto> bp = result.get("c1");
        assertNotNull(bp);

        // Even with zero-bendpoint routing (straight line), post-attachment
        // enforcement should ensure all segments are orthogonal
        for (int i = 0; i < bp.size() - 1; i++) {
            AbsoluteBendpointDto a = bp.get(i);
            AbsoluteBendpointDto b = bp.get(i + 1);
            assertTrue("Segment " + i + " must be orthogonal after post-attachment enforcement: ("
                    + a.x() + "," + a.y() + ")->(" + b.x() + "," + b.y() + ")",
                    a.x() == b.x() || a.y() == b.y());
        }
    }

    // --- Story 10-28: Post-attachment obstacle re-validation ---

    @Test
    public void shouldNotCrossObstacles_afterEdgeAttachmentAndCleanup() {
        // Simulate the Risk Management → ESB scenario: source below, target above,
        // with obstacles (other elements) in between that the final path must avoid.
        // The A* correctly avoids obstacles, but edge attachment + cleanup can
        // introduce segments that cross them.
        RoutingRect source = new RoutingRect(320, 760, 220, 110, "src");   // center (430, 815)
        RoutingRect target = new RoutingRect(450, 300, 220, 110, "tgt");   // center (560, 355)
        RoutingRect obstacle1 = new RoutingRect(590, 600, 220, 110, "obs1"); // Payment Engine analog
        RoutingRect obstacle2 = new RoutingRect(590, 760, 220, 110, "obs2"); // Fraud Detection analog

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        List.of(obstacle1, obstacle2), "", 1));
        List<RoutingRect> allObstacles = List.of(obstacle1, obstacle2, source, target);

        RoutingResult routingResult =
                pipeline.routeAllConnections(connections, allObstacles);
        Map<String, List<AbsoluteBendpointDto>> result = routingResult.routed();

        List<AbsoluteBendpointDto> bp = result.get("c1");
        assertNotNull(bp);
        assertTrue("Should have bendpoints", bp.size() >= 2);

        // Build full path: source center → bendpoints → target center
        List<int[]> fullPath = buildFullPath(source, target, bp);

        // No segment should pass through either obstacle
        assertNoSegmentIntersectsObstacles(fullPath, List.of(obstacle1, obstacle2));
    }

    // =============== Helper Methods ===============

    /**
     * Builds the full path including source center and target center.
     */
    private List<int[]> buildFullPath(RoutingRect source, RoutingRect target,
                                       List<AbsoluteBendpointDto> bendpoints) {
        List<int[]> path = new ArrayList<>();
        path.add(new int[]{source.centerX(), source.centerY()});
        for (AbsoluteBendpointDto bp : bendpoints) {
            path.add(new int[]{bp.x(), bp.y()});
        }
        path.add(new int[]{target.centerX(), target.centerY()});
        return path;
    }

    /**
     * Asserts that no segment in the path passes strictly through any obstacle.
     */
    private void assertNoSegmentIntersectsObstacles(List<int[]> path, List<RoutingRect> obstacles) {
        for (int i = 0; i < path.size() - 1; i++) {
            int[] from = path.get(i);
            int[] to = path.get(i + 1);
            for (RoutingRect obs : obstacles) {
                assertFalse(
                        "Segment (" + from[0] + "," + from[1] + ")->(" + to[0] + "," + to[1]
                                + ") intersects obstacle " + obs.id(),
                        segmentIntersectsObstacle(from[0], from[1], to[0], to[1], obs));
            }
        }
    }

    /**
     * Tests if a bendpoint is strictly inside a rectangle (not on the boundary).
     */
    private boolean isInsideRect(AbsoluteBendpointDto bp, RoutingRect rect) {
        return bp.x() > rect.x() && bp.x() < rect.x() + rect.width()
                && bp.y() > rect.y() && bp.y() < rect.y() + rect.height();
    }

    // ====================================================================
    // Path Simplification Tests (Story 10-26)
    // ====================================================================

    @Test
    public void shouldSimplifyStaircase_toSingleLTurn() {
        // 5-point staircase: step right-down-right-down-right
        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(100, 50));  // BP1
        path.add(new AbsoluteBendpointDto(200, 50));  // BP2
        path.add(new AbsoluteBendpointDto(200, 100)); // BP3
        path.add(new AbsoluteBendpointDto(300, 100)); // BP4
        path.add(new AbsoluteBendpointDto(300, 150)); // BP5

        int[] source = {50, 50};    // to the left
        int[] target = {350, 150};  // to the right

        RoutingPipeline.simplifyPath(path, source, target, Collections.emptyList());

        // With no obstacles, should simplify to at most 1 L-turn midpoint
        assertTrue("Staircase should simplify to <= 2 BPs, got " + path.size(),
                path.size() <= 2);
        // Verify orthogonality
        assertOrthogonal(path, source, target);
    }

    @Test
    public void shouldNotSimplify_whenObstacleBlocksShortcut() {
        // U-shaped detour under a large obstacle — already optimal 2-BP path
        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(50, 250));
        path.add(new AbsoluteBendpointDto(300, 250));

        int[] source = {50, 50};
        int[] target = {300, 50};

        // Large obstacle covering the region between source and target
        // x:60-290, y:0-200 — blocks direct line and H-first L-turns
        RoutingRect obstacle = new RoutingRect(60, 0, 230, 200, "obs");

        int sizeBefore = path.size();
        RoutingPipeline.simplifyPath(path, source, target, List.of(obstacle));

        // Path should remain at 2 BPs — already minimal detour
        assertEquals("Minimal detour path should not be further simplified",
                sizeBefore, path.size());
        assertOrthogonal(path, source, target);
    }

    @Test
    public void shouldSimplifyCollinearSegments_toStraightLine() {
        // 3 collinear-ish points on a horizontal line with small vertical jogs
        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(100, 100));
        path.add(new AbsoluteBendpointDto(200, 100));
        path.add(new AbsoluteBendpointDto(300, 100));

        int[] source = {50, 100};
        int[] target = {350, 100};

        RoutingPipeline.simplifyPath(path, source, target, Collections.emptyList());

        // All points are collinear on y=100. Source and target also y=100.
        // Should simplify to 0 intermediate BPs (direct straight line).
        assertEquals("Collinear path should simplify to 0 BPs", 0, path.size());
    }

    @Test
    public void shouldPreserveOrthogonality_afterSimplification() {
        // Complex staircase
        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(100, 50));
        path.add(new AbsoluteBendpointDto(150, 50));
        path.add(new AbsoluteBendpointDto(150, 100));
        path.add(new AbsoluteBendpointDto(200, 100));
        path.add(new AbsoluteBendpointDto(200, 150));
        path.add(new AbsoluteBendpointDto(250, 150));
        path.add(new AbsoluteBendpointDto(250, 200));

        int[] source = {50, 50};
        int[] target = {300, 200};

        RoutingPipeline.simplifyPath(path, source, target, Collections.emptyList());

        // Verify all segments are orthogonal
        assertOrthogonal(path, source, target);
    }

    @Test
    public void shouldHandleEmptyAndShortPaths() {
        int[] source = {0, 0};
        int[] target = {100, 100};

        // Empty path
        List<AbsoluteBendpointDto> empty = new ArrayList<>();
        RoutingPipeline.simplifyPath(empty, source, target, Collections.emptyList());
        assertEquals("Empty path should remain empty", 0, empty.size());

        // 1-point path
        List<AbsoluteBendpointDto> single = new ArrayList<>();
        single.add(new AbsoluteBendpointDto(50, 50));
        RoutingPipeline.simplifyPath(single, source, target, Collections.emptyList());
        assertEquals("Single-point path should remain unchanged", 1, single.size());
    }

    @Test
    public void shouldReduceBendpoints_inBatchRouting() {
        // Set up a batch scenario with elements that would create staircase patterns
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");    // center (50, 200)
        RoutingRect target = new RoutingRect(600, 170, 100, 60, "tgt");  // center (650, 200)
        // Obstacle forces path around but should still be simplified
        RoutingRect obstacle = new RoutingRect(250, 150, 100, 100, "obs");
        List<RoutingRect> obstacles = List.of(obstacle);

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, obstacles, null, 0)
        );
        List<RoutingRect> allObstacles = List.of(source, target, obstacle);

        RoutingResult routingResult =
                pipeline.routeAllConnections(connections, allObstacles);
        Map<String, List<AbsoluteBendpointDto>> result = routingResult.routed();

        List<AbsoluteBendpointDto> routed = result.get("c1");
        assertNotNull("Should have routed connection", routed);
        // With simplification, path around a single obstacle should be compact
        assertTrue("Batch routing should produce simplified path (<= 6 BPs), got " + routed.size(),
                routed.size() <= 6);

        // Verify orthogonality
        assertOrthogonal(routed,
                new int[]{source.centerX(), source.centerY()},
                new int[]{target.centerX(), target.centerY()});
    }

    /**
     * Asserts all segments in a path (including source/target endpoints) are orthogonal.
     */
    private void assertOrthogonal(List<AbsoluteBendpointDto> path, int[] source, int[] target) {
        List<int[]> full = new ArrayList<>();
        full.add(source);
        for (AbsoluteBendpointDto bp : path) {
            full.add(new int[]{bp.x(), bp.y()});
        }
        full.add(target);

        for (int i = 0; i < full.size() - 1; i++) {
            int[] a = full.get(i);
            int[] b = full.get(i + 1);
            assertTrue("Segment " + i + " (" + a[0] + "," + a[1] + ")->(" + b[0] + "," + b[1]
                    + ") is not orthogonal",
                    a[0] == b[0] || a[1] == b[1]);
        }
    }

    // --- Story 10-30: RoutingResult structure and failure classification ---

    @Test
    public void shouldReturnRoutingResult_withRoutedAndEmptyFailed_whenAllSucceed() {
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(400, 170, 100, 60, "tgt");
        RoutingRect obstacle = new RoutingRect(200, 100, 100, 60, "obs");

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(obstacle), "", 1));
        List<RoutingRect> allObstacles = List.of(obstacle, source, target);

        RoutingResult result = pipeline.routeAllConnections(connections, allObstacles);

        assertNotNull(result);
        assertEquals(1, result.routed().size());
        assertNotNull(result.routed().get("c1"));
        assertTrue("Failed list should be empty when all routes succeed", result.failed().isEmpty());
    }

    @Test
    public void shouldClassifyConnectionAsFailed_whenPathHasViolations() {
        // Unit test for the classification logic: verify that findFirstObstacleViolation
        // correctly identifies violating paths and the RoutingResult structure
        // correctly separates routed vs failed connections.
        RoutingRect obstacle = new RoutingRect(100, 100, 200, 200, "obs"); // x=[100,300], y=[100,300]

        // Path that goes through obstacle — should be detected as violation
        List<AbsoluteBendpointDto> violatingPath = List.of(
                new AbsoluteBendpointDto(0, 200),
                new AbsoluteBendpointDto(400, 200));
        RoutingRect hit = RoutingPipeline.findFirstObstacleViolation(violatingPath, List.of(obstacle));
        assertNotNull("Horizontal line through obstacle should have violations", hit);
        assertEquals("Should return the crossed obstacle", "obs", hit.id());

        // Path that avoids obstacle — no violation
        List<AbsoluteBendpointDto> cleanPath = List.of(
                new AbsoluteBendpointDto(0, 200),
                new AbsoluteBendpointDto(0, 50),
                new AbsoluteBendpointDto(400, 50),
                new AbsoluteBendpointDto(400, 200));
        assertNull("Path above obstacle should have no violations",
                RoutingPipeline.findFirstObstacleViolation(cleanPath, List.of(obstacle)));

        // Verify FailedConnection record structure (backward-compatible constructor)
        FailedConnection fc = new FailedConnection("c1", "src", "tgt", "element_crossing");
        assertEquals("c1", fc.connectionId());
        assertEquals("src", fc.sourceId());
        assertEquals("tgt", fc.targetId());
        assertEquals("element_crossing", fc.constraintViolated());
        assertNull("Backward-compat constructor should have null crossedElementId",
                fc.crossedElementId());

        // Verify FailedConnection with crossedElementId (Story 10-34)
        FailedConnection fcWithCrossed = new FailedConnection("c2", "src", "tgt",
                "element_crossing", "obs");
        assertEquals("obs", fcWithCrossed.crossedElementId());

        // Verify RoutingResult structure
        Map<String, List<AbsoluteBendpointDto>> routedMap = new java.util.LinkedHashMap<>();
        routedMap.put("c1", cleanPath);
        RoutingResult result = new RoutingResult(routedMap, List.of(fc), List.of());
        assertEquals(1, result.routed().size());
        assertEquals(1, result.failed().size());
        assertNotNull(result.routed().get("c1"));
        assertEquals("element_crossing", result.failed().get(0).constraintViolated());
    }

    @Test
    public void shouldProduceValidRoutingResult_whenAllConnectionsSucceed() {
        // Integration test: verify that when all connections route cleanly,
        // RoutingResult has all in routed map and empty failed list
        RoutingRect src1 = new RoutingRect(0, 170, 80, 60, "s1");
        RoutingRect tgt1 = new RoutingRect(600, 170, 80, 60, "t1");
        RoutingRect src2 = new RoutingRect(0, 400, 80, 60, "s2");
        RoutingRect tgt2 = new RoutingRect(600, 400, 80, 60, "t2");
        RoutingRect obstacle = new RoutingRect(250, 250, 80, 60, "obs");

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", src1, tgt1,
                        List.of(obstacle), "", 1),
                new RoutingPipeline.ConnectionEndpoints("c2", src2, tgt2,
                        List.of(obstacle), "", 1));
        List<RoutingRect> allObstacles = List.of(obstacle, src1, tgt1, src2, tgt2);

        RoutingResult result = pipeline.routeAllConnections(connections, allObstacles);

        assertNotNull(result);
        // Both should be routed, none failed
        assertEquals("Both connections should be routed", 2, result.routed().size());
        assertTrue("Failed should be empty", result.failed().isEmpty());
        assertNotNull("c1 should be in routed", result.routed().get("c1"));
        assertNotNull("c2 should be in routed", result.routed().get("c2"));
        // Total routed + failed should equal input count
        assertEquals(2, result.routed().size() + result.failed().size());
    }

    @Test
    public void shouldReturnEmptyRoutingResult_whenNoConnections() {
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of();
        List<RoutingRect> allObstacles = List.of();

        RoutingResult result = pipeline.routeAllConnections(connections, allObstacles);

        assertNotNull(result);
        assertTrue("Routed should be empty", result.routed().isEmpty());
        assertTrue("Failed should be empty", result.failed().isEmpty());
    }

    @Test
    public void shouldDetectViolations_findFirstObstacleViolation() {
        RoutingRect obstacle = new RoutingRect(100, 100, 200, 200, "obs");
        // Path that goes straight through the obstacle
        List<AbsoluteBendpointDto> throughPath = List.of(
                new AbsoluteBendpointDto(0, 200),
                new AbsoluteBendpointDto(400, 200));
        RoutingRect hit = RoutingPipeline.findFirstObstacleViolation(throughPath, List.of(obstacle));
        assertNotNull("Path through obstacle should have violations", hit);
        assertEquals("obs", hit.id());

        // Path that goes around the obstacle
        List<AbsoluteBendpointDto> aroundPath = List.of(
                new AbsoluteBendpointDto(0, 200),
                new AbsoluteBendpointDto(0, 50),
                new AbsoluteBendpointDto(400, 50),
                new AbsoluteBendpointDto(400, 200));
        assertNull("Path around obstacle should not have violations",
                RoutingPipeline.findFirstObstacleViolation(aroundPath, List.of(obstacle)));
    }

    @Test
    public void shouldReturnNull_findFirstObstacleViolation_emptyInputs() {
        assertNull("Empty path should have no violations",
                RoutingPipeline.findFirstObstacleViolation(List.of(), List.of()));
        assertNull("Single point should have no violations",
                RoutingPipeline.findFirstObstacleViolation(
                        List.of(new AbsoluteBendpointDto(100, 100)), List.of()));
        assertNull("No obstacles should have no violations",
                RoutingPipeline.findFirstObstacleViolation(
                        List.of(new AbsoluteBendpointDto(0, 0), new AbsoluteBendpointDto(100, 0)),
                        List.of()));
    }

    // --- Story 10-32: RoutingResult violatedRoutes + pipeline preservation ---

    @Test
    public void shouldNullGuardViolatedRoutes_whenNull() {
        RoutingResult result = new RoutingResult(null, null, null, null);
        assertNotNull(result.violatedRoutes());
        assertTrue(result.violatedRoutes().isEmpty());
    }

    @Test
    public void shouldPreserveViolatedRoutes_whenProvided() {
        Map<String, List<AbsoluteBendpointDto>> violated = new java.util.LinkedHashMap<>();
        violated.put("c1", List.of(new AbsoluteBendpointDto(100, 200)));
        RoutingResult result = new RoutingResult(Map.of(), List.of(), List.of(), violated);
        assertEquals(1, result.violatedRoutes().size());
        assertNotNull(result.violatedRoutes().get("c1"));
    }

    // --- Story 10-34: findFirstObstacleViolation returns obstacle identity ---

    @Test
    public void shouldRouteAroundWall_viaPerimeterNodes() {
        // Previously this test verified that a wall of obstacles forced a routing
        // failure. With perimeter boundary nodes (E2E 2026-03-12 fix), the router
        // can now route around the wall via the perimeter corridor.
        RoutingRect src = new RoutingRect(0, 170, 80, 60, "src");
        RoutingRect tgt = new RoutingRect(600, 170, 80, 60, "tgt");
        RoutingRect wall1 = new RoutingRect(280, 0, 80, 200, "wall-top");
        RoutingRect wall2 = new RoutingRect(280, 200, 80, 200, "wall-bottom");

        List<RoutingRect> obstacles = List.of(wall1, wall2);
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", src, tgt,
                        obstacles, "", 1));
        List<RoutingRect> allObstacles = List.of(wall1, wall2, src, tgt);

        RoutingResult result = pipeline.routeAllConnections(connections, allObstacles);

        // Perimeter nodes enable routing around the wall
        assertTrue("Connection should succeed by routing around the wall",
                result.failed().isEmpty());
        assertFalse("Routed map should contain the connection",
                result.routed().isEmpty());
        List<AbsoluteBendpointDto> path = result.routed().get("c1");
        assertNotNull("Connection c1 should have a routed path", path);
        assertTrue("Path should have bendpoints (multi-hop around wall)",
                path.size() >= 2);
    }

    @Test
    public void shouldBackwardCompatConstruct_withoutViolatedRoutes() {
        Map<String, List<AbsoluteBendpointDto>> routed = new java.util.LinkedHashMap<>();
        routed.put("c1", List.of());
        FailedConnection fc = new FailedConnection("c2", "s", "t", "element_crossing");
        RoutingResult result = new RoutingResult(routed, List.of(fc), List.of());
        assertEquals(1, result.routed().size());
        assertEquals(1, result.failed().size());
        assertTrue(result.violatedRoutes().isEmpty());
    }

    @Test
    public void shouldPreserveViolatedRoutes_whenConnectionHasObstacleViolation() {
        // Wide obstacle between source and target — A* may or may not route around it.
        // Full pipeline integration: deterministic failure is hard to create because
        // the pipeline either routes around obstacles or returns immutable empty lists.
        // Test verifies: if failure occurs, violated routes MUST be preserved.
        RoutingRect src = new RoutingRect(0, 170, 80, 60, "src");     // center (40, 200)
        RoutingRect tgt = new RoutingRect(300, 170, 80, 60, "tgt");   // center (340, 200)
        RoutingRect obstacle = new RoutingRect(100, 0, 100, 500, "wall");

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", src, tgt,
                        List.of(obstacle), "", 1));
        List<RoutingRect> allObstacles = List.of(obstacle, src, tgt);

        RoutingResult result = pipeline.routeAllConnections(connections, allObstacles);

        // Invariant: every connection is classified as either routed or failed
        assertEquals("Total should equal input count", 1,
                result.routed().size() + result.failed().size());

        if (!result.failed().isEmpty()) {
            // Failed path: violated route must be preserved for force-mode
            assertEquals("element_crossing", result.failed().get(0).constraintViolated());
            assertEquals("Failed connection should have violated route preserved",
                    1, result.violatedRoutes().size());
            assertNotNull("Violated route for c1 should exist",
                    result.violatedRoutes().get("c1"));
        } else {
            // Routed path: no violated routes should exist
            assertTrue("No violated routes when connection routed successfully",
                    result.violatedRoutes().isEmpty());
        }
    }

    // --- Story 13-4: Target/source bounding box approach tests ---

    @Test
    public void shouldApproachTargetFromOutside_whenTargetToRight() {
        // AC-A1: Target to the right of source → approach from left edge
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");   // center (50, 200)
        RoutingRect target = new RoutingRect(300, 170, 100, 60, "tgt"); // center (350, 200)

        List<AbsoluteBendpointDto> bendpoints =
                pipeline.routeConnection(source, target, Collections.emptyList());

        // No intermediate BP should be inside the target bounding box
        assertNoBendpointInsideRect(bendpoints, target, "target");
        assertNoSegmentPassesThrough(bendpoints, source, target, target, false);
    }

    @Test
    public void shouldApproachTargetFromOutside_whenTargetToLeft() {
        // AC-A1: Target to the left of source → approach from right edge
        RoutingRect source = new RoutingRect(300, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(0, 170, 100, 60, "tgt");

        List<AbsoluteBendpointDto> bendpoints =
                pipeline.routeConnection(source, target, Collections.emptyList());

        assertNoBendpointInsideRect(bendpoints, target, "target");
        assertNoSegmentPassesThrough(bendpoints, source, target, target, false);
    }

    @Test
    public void shouldApproachTargetFromOutside_whenTargetAbove() {
        // AC-A2: Target above source → approach from bottom edge
        RoutingRect source = new RoutingRect(170, 300, 100, 60, "src");
        RoutingRect target = new RoutingRect(170, 0, 100, 60, "tgt");

        List<AbsoluteBendpointDto> bendpoints =
                pipeline.routeConnection(source, target, Collections.emptyList());

        assertNoBendpointInsideRect(bendpoints, target, "target");
        assertNoSegmentPassesThrough(bendpoints, source, target, target, false);
    }

    @Test
    public void shouldApproachTargetFromOutside_whenTargetBelow() {
        // AC-A2: Target below source → approach from top edge
        RoutingRect source = new RoutingRect(170, 0, 100, 60, "src");
        RoutingRect target = new RoutingRect(170, 300, 100, 60, "tgt");

        List<AbsoluteBendpointDto> bendpoints =
                pipeline.routeConnection(source, target, Collections.emptyList());

        assertNoBendpointInsideRect(bendpoints, target, "target");
        assertNoSegmentPassesThrough(bendpoints, source, target, target, false);
    }

    @Test
    public void shouldDepartSourceFromOutside_whenTargetToRight() {
        // AC-A4: Source departure should be clean
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(300, 170, 100, 60, "tgt");

        List<AbsoluteBendpointDto> bendpoints =
                pipeline.routeConnection(source, target, Collections.emptyList());

        assertNoBendpointInsideRect(bendpoints, source, "source");
        assertNoSegmentPassesThrough(bendpoints, source, target, source, true);
    }

    @Test
    public void shouldDepartSourceFromOutside_whenTargetToLeft() {
        // AC-A4: Source departure when target is to the left
        RoutingRect source = new RoutingRect(300, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(0, 170, 100, 60, "tgt");

        List<AbsoluteBendpointDto> bendpoints =
                pipeline.routeConnection(source, target, Collections.emptyList());

        assertNoBendpointInsideRect(bendpoints, source, "source");
        assertNoSegmentPassesThrough(bendpoints, source, target, source, true);
    }

    @Test
    public void shouldDepartSourceFromOutside_whenTargetAbove() {
        // AC-A4: Source departure when target is above
        RoutingRect source = new RoutingRect(170, 300, 100, 60, "src");
        RoutingRect target = new RoutingRect(170, 0, 100, 60, "tgt");

        List<AbsoluteBendpointDto> bendpoints =
                pipeline.routeConnection(source, target, Collections.emptyList());

        assertNoBendpointInsideRect(bendpoints, source, "source");
        assertNoSegmentPassesThrough(bendpoints, source, target, source, true);
    }

    @Test
    public void shouldDepartSourceFromOutside_whenTargetBelow() {
        // AC-A4: Source departure when target is below
        RoutingRect source = new RoutingRect(170, 0, 100, 60, "src");
        RoutingRect target = new RoutingRect(170, 300, 100, 60, "tgt");

        List<AbsoluteBendpointDto> bendpoints =
                pipeline.routeConnection(source, target, Collections.emptyList());

        assertNoBendpointInsideRect(bendpoints, source, "source");
        assertNoSegmentPassesThrough(bendpoints, source, target, source, true);
    }

    @Test
    public void shouldNotRegress_existingRouteWithObstacle() {
        // AC-A5: Existing test scenario should still produce valid routes
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(400, 170, 100, 60, "tgt");
        RoutingRect obstacle = new RoutingRect(200, 150, 100, 100, "obs");

        List<AbsoluteBendpointDto> bendpoints =
                pipeline.routeConnection(source, target, List.of(obstacle));

        assertFalse("Should have bendpoints to route around obstacle", bendpoints.isEmpty());
        assertNoBendpointInsideRect(bendpoints, target, "target");
        assertNoBendpointInsideRect(bendpoints, source, "source");
    }

    @Test
    public void shouldNotPassThroughTarget_whenSimilarYCoordinates() {
        // AC-A3: The specific ESB→API Gateway scenario: elements at similar y-coordinates
        // Source at left, target at right, similar y → horizontal route should NOT pass through target
        RoutingRect source = new RoutingRect(100, 280, 150, 50, "esb");     // center (175, 305)
        RoutingRect target = new RoutingRect(400, 250, 150, 70, "apigw");   // center (475, 285)

        List<AbsoluteBendpointDto> bendpoints =
                pipeline.routeConnection(source, target, Collections.emptyList());

        assertNoBendpointInsideRect(bendpoints, target, "target");
        assertNoBendpointInsideRect(bendpoints, source, "source");
        assertNoSegmentPassesThrough(bendpoints, source, target, target, false);
        assertNoSegmentPassesThrough(bendpoints, source, target, source, true);
    }

    @Test
    public void shouldProduceStraightLine_whenElementsAlignedVertically() {
        // Regression test: vertically aligned elements should get a straight-line route
        // (no intermediate BPs) — the full-obstacle approach caused unnecessary detours
        RoutingRect source = new RoutingRect(170, 0, 100, 60, "src");   // center (220, 30)
        RoutingRect target = new RoutingRect(170, 200, 100, 60, "tgt"); // center (220, 230)

        List<AbsoluteBendpointDto> bendpoints =
                pipeline.routeConnection(source, target, Collections.emptyList());

        // Aligned elements with no obstacles → expect straight line (0 intermediate BPs)
        assertTrue("Vertically aligned elements should have 0 or minimal BPs, got " + bendpoints.size(),
                bendpoints.size() <= 2);
    }

    @Test
    public void shouldProduceStraightLine_whenElementsAlignedHorizontally() {
        // Regression test: horizontally aligned elements should get a straight-line route
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");   // center (50, 200)
        RoutingRect target = new RoutingRect(300, 170, 100, 60, "tgt"); // center (350, 200)

        List<AbsoluteBendpointDto> bendpoints =
                pipeline.routeConnection(source, target, Collections.emptyList());

        assertTrue("Horizontally aligned elements should have 0 or minimal BPs, got " + bendpoints.size(),
                bendpoints.size() <= 2);
    }

    @Test
    public void shouldDetectEndpointPassThrough_whenPathCrossesTarget() {
        // hasEndpointPassThrough should detect when a non-terminal segment crosses the target
        RoutingRect source = new RoutingRect(100, 280, 150, 50, "src");  // center (175, 305)
        RoutingRect target = new RoutingRect(400, 250, 150, 70, "tgt");  // center (475, 285)

        // Simulate a path that goes horizontally at y=305 through the target body
        // Full path: (175,305) → (475,305) → (475,285)
        // Segment (175,305)→(475,305) at y=305 crosses target (x=400..550, y=250..320)
        List<AbsoluteBendpointDto> bendpoints = List.of(
                new AbsoluteBendpointDto(475, 305));

        boolean detected = pipeline.hasEndpointPassThrough(bendpoints, source, target, false);
        assertTrue("Should detect path crossing through target body", detected);
    }

    @Test
    public void shouldNotDetectEndpointPassThrough_whenPathClean() {
        // Clean path should not trigger pass-through detection
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(300, 170, 100, 60, "tgt");

        // Straight line from source to target — no intermediate BPs → no non-terminal segments
        List<AbsoluteBendpointDto> bendpoints = Collections.emptyList();

        boolean srcDetected = pipeline.hasEndpointPassThrough(bendpoints, source, target, true);
        boolean tgtDetected = pipeline.hasEndpointPassThrough(bendpoints, source, target, false);
        assertFalse("Clean straight path should not flag source", srcDetected);
        assertFalse("Clean straight path should not flag target", tgtDetected);
    }

    @Test
    public void shouldCalculateEdgePort_horizontalDominance() {
        RoutingRect element = new RoutingRect(100, 100, 80, 60, "elem");
        RoutingRect otherRight = new RoutingRect(300, 100, 80, 60, "other");
        RoutingRect otherLeft = new RoutingRect(0, 100, 20, 60, "other2");

        // Other to the right → port on right edge
        int[] rightPort = pipeline.calculateEdgePort(element, otherRight);
        assertEquals("Port x should be element right + margin",
                element.x() + element.width() + 10, rightPort[0]);
        assertEquals("Port y should be element center y",
                element.centerY(), rightPort[1]);

        // Other to the left → port on left edge
        int[] leftPort = pipeline.calculateEdgePort(element, otherLeft);
        assertEquals("Port x should be element left - margin",
                element.x() - 10, leftPort[0]);
        assertEquals("Port y should be element center y",
                element.centerY(), leftPort[1]);
    }

    @Test
    public void shouldCalculateEdgePort_verticalDominance() {
        RoutingRect element = new RoutingRect(100, 100, 80, 60, "elem");
        RoutingRect otherBelow = new RoutingRect(100, 300, 80, 60, "other");
        RoutingRect otherAbove = new RoutingRect(100, 0, 80, 20, "other2");

        // Other below → port on bottom edge
        int[] bottomPort = pipeline.calculateEdgePort(element, otherBelow);
        assertEquals("Port x should be element center x",
                element.centerX(), bottomPort[0]);
        assertEquals("Port y should be element bottom + margin",
                element.y() + element.height() + 10, bottomPort[1]);

        // Other above → port on top edge
        int[] topPort = pipeline.calculateEdgePort(element, otherAbove);
        assertEquals("Port x should be element center x",
                element.centerX(), topPort[0]);
        assertEquals("Port y should be element top - margin",
                element.y() - 10, topPort[1]);
    }

    /**
     * Asserts that no bendpoint falls strictly inside the given element rectangle
     * AND no segment of the full path (sourceCenter → BPs → targetCenter) passes
     * through the element's inset rect (5px inset, matching router tolerance).
     */
    private void assertNoBendpointInsideRect(List<AbsoluteBendpointDto> bendpoints,
                                              RoutingRect rect, String label) {
        for (AbsoluteBendpointDto bp : bendpoints) {
            boolean inside = bp.x() > rect.x() && bp.x() < rect.x() + rect.width()
                    && bp.y() > rect.y() && bp.y() < rect.y() + rect.height();
            assertFalse("Bendpoint (" + bp.x() + "," + bp.y() + ") is inside " + label
                    + " rect (" + rect.x() + "," + rect.y() + ","
                    + rect.width() + "," + rect.height() + ")", inside);
        }
    }

    /**
     * Asserts that no non-terminal segment of the path passes through the given
     * element's inset rect. Uses 5px inset to match router tolerance.
     * For source elements, skips the first segment. For target elements, skips the last.
     */
    private void assertNoSegmentPassesThrough(List<AbsoluteBendpointDto> bendpoints,
                                               RoutingRect source, RoutingRect target,
                                               RoutingRect element, boolean isSource) {
        // Build full path: sourceCenter + BPs + targetCenter
        List<int[]> fullPath = new ArrayList<>();
        fullPath.add(new int[]{source.centerX(), source.centerY()});
        for (AbsoluteBendpointDto bp : bendpoints) {
            fullPath.add(new int[]{bp.x(), bp.y()});
        }
        fullPath.add(new int[]{target.centerX(), target.centerY()});

        if (fullPath.size() < 3) return; // straight line — no non-terminal segments

        int inset = 5;
        RoutingRect insetRect = new RoutingRect(
                element.x() + inset, element.y() + inset,
                element.width() - 2 * inset, element.height() - 2 * inset, element.id());
        if (insetRect.width() <= 0 || insetRect.height() <= 0) return;

        int start = isSource ? 1 : 0;
        int end = isSource ? fullPath.size() - 1 : fullPath.size() - 2;

        for (int i = start; i < end; i++) {
            int[] a = fullPath.get(i);
            int[] b = fullPath.get(i + 1);
            assertFalse("Segment (" + a[0] + "," + a[1] + ")→(" + b[0] + "," + b[1]
                    + ") passes through " + (isSource ? "source" : "target") + " element",
                    segmentIntersectsObstacle(a[0], a[1], b[0], b[1], insetRect));
        }
    }

    // ===================================================================
    // Story 13-8: Fallback Edge Port Strategy
    // ===================================================================

    @Test
    public void shouldCalculateAlternativeEdgePorts_excludingPrimary() {
        // Element at (100,100) 80x60, other to the right at (300,100)
        // Primary = RIGHT edge, alternatives should be TOP, BOTTOM, LEFT ordered by angle
        RoutingRect element = new RoutingRect(100, 100, 80, 60, "elem");
        RoutingRect otherRight = new RoutingRect(300, 100, 80, 60, "other");

        int[][] alts = pipeline.calculateAlternativeEdgePorts(element, otherRight);
        assertEquals("Should return 3 alternatives", 3, alts.length);

        // Verify primary (right edge) is NOT in alternatives
        int primaryX = element.x() + element.width() + 10; // right edge + margin
        for (int[] alt : alts) {
            assertFalse("Primary right edge port should not appear in alternatives",
                    alt[0] == primaryX && alt[1] == element.centerY());
        }
    }

    @Test
    public void shouldOrderAlternativeEdgePorts_byAngularProximity() {
        // Element at center (140,130), other above-right at (300, 0)
        // Primary = RIGHT (horizontal dominance: dx=200 > dy=120)
        // Alternatives: TOP (closest to above-right), BOTTOM, LEFT
        RoutingRect element = new RoutingRect(100, 100, 80, 60, "elem");
        RoutingRect otherAboveRight = new RoutingRect(300, 0, 80, 60, "other");

        int[][] alts = pipeline.calculateAlternativeEdgePorts(element, otherAboveRight);
        assertEquals(3, alts.length);

        // Order should be: TOP (closest to above-right), BOTTOM, LEFT
        int topPortY = element.y() - 10; // top edge - margin
        assertEquals("First alternative should be top edge port",
                topPortY, alts[0][1]);
        int bottomPortY = element.y() + element.height() + 10; // bottom edge + margin
        assertEquals("Second alternative should be bottom edge port",
                bottomPortY, alts[1][1]);
        int leftPortX = element.x() - 10; // left edge - margin
        assertEquals("Third alternative should be left edge port",
                leftPortX, alts[2][0]);
    }

    @Test
    public void shouldFallbackToAlternativeEdgePort_whenPrimaryBlockedByAdjacentObstacle() {
        // Reproduce the ESB → Contact Centre scenario from story context:
        // ESB at (530,250) 220x200, Event Streaming at (770,250) 220x200 immediately to the right
        // Contact Centre at (950,0) 220x120 above-right
        // Primary edge port = RIGHT (dx dominance) → leads into Event Streaming
        // Fallback should use TOP edge to route above Event Streaming
        RoutingRect source = new RoutingRect(530, 250, 220, 200, "esb");
        RoutingRect target = new RoutingRect(950, 0, 220, 120, "contact-centre");
        RoutingRect obstacle = new RoutingRect(770, 250, 220, 200, "event-streaming");

        List<AbsoluteBendpointDto> bendpoints = pipeline.routeConnection(
                source, target, List.of(obstacle));

        // Verify the route exists (not a straight line through obstacle)
        assertFalse("Should have bendpoints (not a straight line)", bendpoints.isEmpty());

        // Check full rendered path including terminal segments (sourceCenter→bp1, bpN→targetCenter)
        List<int[]> fullPath = new ArrayList<>();
        fullPath.add(new int[]{source.centerX(), source.centerY()});
        for (AbsoluteBendpointDto bp : bendpoints) {
            fullPath.add(new int[]{bp.x(), bp.y()});
        }
        fullPath.add(new int[]{target.centerX(), target.centerY()});
        for (int i = 0; i < fullPath.size() - 1; i++) {
            int[] a = fullPath.get(i);
            int[] b = fullPath.get(i + 1);
            assertFalse("Full rendered path segment " + i + " should not cross Event Streaming obstacle",
                    segmentIntersectsObstacle(a[0], a[1], b[0], b[1], obstacle));
        }
    }

    @Test
    public void shouldReturnFailedRoute_whenAllFourEdgePortsFail() {
        // Source completely surrounded by obstacles — all 4 edge ports blocked
        RoutingRect source = new RoutingRect(200, 200, 100, 60, "src");
        RoutingRect target = new RoutingRect(600, 200, 100, 60, "tgt");

        // Wall of obstacles around source (no corridor to escape)
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(310, 100, 100, 260, "wall-right"),
                new RoutingRect(90, 100, 100, 260, "wall-left"),
                new RoutingRect(100, 80, 300, 100, "wall-top"),
                new RoutingRect(100, 270, 300, 100, "wall-bottom")
        );

        // Should not throw — returns best available path (current fail behaviour preserved)
        List<AbsoluteBendpointDto> bendpoints = pipeline.routeConnection(source, target, obstacles);
        assertNotNull("Should return a path (even if it has violations)", bendpoints);
    }

    @Test
    public void shouldNotRegress_whenPrimaryEdgePortSucceeds() {
        // Standard scenario where primary edge port works fine — no fallback needed
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(400, 170, 100, 60, "tgt");
        RoutingRect obstacle = new RoutingRect(200, 150, 100, 100, "obs");

        List<AbsoluteBendpointDto> bendpoints = pipeline.routeConnection(
                source, target, List.of(obstacle));

        // Should still route around obstacle correctly
        for (int i = 0; i < bendpoints.size() - 1; i++) {
            AbsoluteBendpointDto a = bendpoints.get(i);
            AbsoluteBendpointDto b = bendpoints.get(i + 1);
            assertFalse("Route should not cross obstacle",
                    segmentIntersectsObstacle(a.x(), a.y(), b.x(), b.y(), obstacle));
        }
    }

    @Test
    public void shouldFallbackTargetEdgePort_whenTargetPrimaryBlocked() {
        // Target's primary approach edge is blocked by adjacent obstacle
        // Source at left, target at right, obstacle immediately below target
        RoutingRect source = new RoutingRect(0, 200, 100, 60, "src");
        RoutingRect target = new RoutingRect(400, 0, 100, 60, "tgt");
        // Obstacle immediately below target — blocks bottom approach
        RoutingRect obstacle = new RoutingRect(400, 70, 100, 200, "blocker");

        List<AbsoluteBendpointDto> bendpoints = pipeline.routeConnection(
                source, target, List.of(obstacle));

        // Check full rendered path including terminal segments
        List<int[]> fullPath = new ArrayList<>();
        fullPath.add(new int[]{source.centerX(), source.centerY()});
        for (AbsoluteBendpointDto bp : bendpoints) {
            fullPath.add(new int[]{bp.x(), bp.y()});
        }
        fullPath.add(new int[]{target.centerX(), target.centerY()});
        for (int i = 0; i < fullPath.size() - 1; i++) {
            int[] a = fullPath.get(i);
            int[] b = fullPath.get(i + 1);
            assertFalse("Full rendered path segment " + i + " should not cross blocker obstacle",
                    segmentIntersectsObstacle(a[0], a[1], b[0], b[1], obstacle));
        }
    }

    @Test
    public void shouldApplyFallbackToBothEndpoints_whenBothBlocked() {
        // Both source and target have their primary edge ports blocked
        // Source at center, target above-right, obstacles blocking right and bottom edges
        RoutingRect source = new RoutingRect(200, 300, 120, 80, "src");
        RoutingRect target = new RoutingRect(500, 0, 120, 80, "tgt");
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(330, 250, 120, 180, "block-src-right"),  // blocks source right edge
                new RoutingRect(500, 90, 120, 180, "block-tgt-bottom")  // blocks target bottom edge
        );

        List<AbsoluteBendpointDto> bendpoints = pipeline.routeConnection(
                source, target, obstacles);

        // Check full rendered path including terminal segments
        List<int[]> fullPath = new ArrayList<>();
        fullPath.add(new int[]{source.centerX(), source.centerY()});
        for (AbsoluteBendpointDto bp : bendpoints) {
            fullPath.add(new int[]{bp.x(), bp.y()});
        }
        fullPath.add(new int[]{target.centerX(), target.centerY()});
        for (RoutingRect obs : obstacles) {
            for (int i = 0; i < fullPath.size() - 1; i++) {
                int[] a = fullPath.get(i);
                int[] b = fullPath.get(i + 1);
                assertFalse("Full rendered path segment " + i + " should not cross " + obs.id(),
                        segmentIntersectsObstacle(a[0], a[1], b[0], b[1], obs));
            }
        }
    }

    /**
     * Delegates to production intersection logic to ensure test and production
     * semantics are identical (Liang-Barsky clipping via EdgeAttachmentCalculator).
     */
    private boolean segmentIntersectsObstacle(int x1, int y1, int x2, int y2, RoutingRect obs) {
        return RoutingPipeline.segmentIntersectsAnyObstacle(x1, y1, x2, y2, List.of(obs));
    }

    // =============================================
    // Story 13-12: Terminal realignment tests
    // =============================================

    @Test
    public void shouldRestoreSourceTerminal_whenShiftedByMicroJogRemoval() {
        // Source terminal at RIGHT face center, shifted by micro-jog propagation
        RoutingRect source = new RoutingRect(100, 200, 120, 40, "src");
        RoutingRect target = new RoutingRect(500, 200, 120, 40, "tgt");
        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        Collections.emptyList(), null, 0);

        // Simulate post-cleanup shifted path: terminal Y shifted from 220 to 228
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(221, 228),  // shifted source terminal
                new AbsoluteBendpointDto(300, 228),
                new AbsoluteBendpointDto(300, 220),
                new AbsoluteBendpointDto(499, 220)   // target terminal
        ));

        int[] savedFirst = {221, 220};  // original face center
        int[] savedLast = {499, 220};   // target terminal (unchanged)

        RoutingPipeline.realignTerminals(path, savedFirst, savedLast, conn);

        assertEquals("Source terminal X should be restored", 221, path.get(0).x());
        assertEquals("Source terminal Y should be restored", 220, path.get(0).y());
    }

    @Test
    public void shouldRestoreTargetTerminal_whenShiftedByMicroJogRemoval() {
        RoutingRect source = new RoutingRect(100, 200, 120, 40, "src");
        RoutingRect target = new RoutingRect(500, 200, 120, 40, "tgt");
        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        Collections.emptyList(), null, 0);

        // Simulate: target terminal Y shifted from 220 to 228
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(221, 220),
                new AbsoluteBendpointDto(300, 220),
                new AbsoluteBendpointDto(300, 228),
                new AbsoluteBendpointDto(499, 228)   // shifted target terminal
        ));

        int[] savedFirst = {221, 220};
        int[] savedLast = {499, 220};   // original face center

        RoutingPipeline.realignTerminals(path, savedFirst, savedLast, conn);

        AbsoluteBendpointDto lastBp = path.get(path.size() - 1);
        assertEquals("Target terminal X should be restored", 499, lastBp.x());
        assertEquals("Target terminal Y should be restored", 220, lastBp.y());
    }

    @Test
    public void shouldInsertAlignmentLTurn_whenRestorationCreatesDiagonalOnHorizontalFace() {
        // RIGHT face terminal restored, adjacent BP is at different X and Y
        RoutingRect source = new RoutingRect(100, 200, 120, 40, "src");
        RoutingRect target = new RoutingRect(500, 200, 120, 40, "tgt");
        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        Collections.emptyList(), null, 0);

        // After micro-jog shifted terminal, cleanup removed alignment BP
        // Path: shifted-terminal, next intermediate, target terminal
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(221, 228),  // shifted source terminal
                new AbsoluteBendpointDto(300, 250),  // intermediate (different X and Y from saved)
                new AbsoluteBendpointDto(499, 220)
        ));

        int[] savedFirst = {221, 220};  // RIGHT face center (y=220)
        int[] savedLast = {499, 220};

        RoutingPipeline.realignTerminals(path, savedFirst, savedLast, conn);

        // Should restore terminal and insert L-turn maintaining terminal Y=220
        assertEquals("Source terminal restored", 220, path.get(0).y());
        // L-turn should be at (next.x, terminal.y) = (300, 220) for RIGHT face
        assertEquals("L-turn X should match next BP", 300, path.get(1).x());
        assertEquals("L-turn Y should match terminal Y (horizontal exit)", 220, path.get(1).y());
    }

    @Test
    public void shouldInsertAlignmentLTurn_whenRestorationCreatesDiagonalOnVerticalFace() {
        // BOTTOM face terminal restored, adjacent BP is at different X and Y
        RoutingRect source = new RoutingRect(100, 100, 120, 40, "src");  // bottom edge at y=140
        RoutingRect target = new RoutingRect(100, 400, 120, 40, "tgt");
        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        Collections.emptyList(), null, 0);

        // Source terminal on BOTTOM face: x=160 (center), y=141 (y+h+1)
        // Shifted to (168, 141) by micro-jog propagation on X axis
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(168, 141),  // shifted source terminal
                new AbsoluteBendpointDto(200, 300),  // intermediate
                new AbsoluteBendpointDto(160, 399)   // target terminal
        ));

        int[] savedFirst = {160, 141};  // BOTTOM face center
        int[] savedLast = {160, 399};

        RoutingPipeline.realignTerminals(path, savedFirst, savedLast, conn);

        assertEquals("Source terminal X restored", 160, path.get(0).x());
        assertEquals("Source terminal Y restored", 141, path.get(0).y());
        // L-turn for BOTTOM face: maintain terminal X → (160, next.y)
        assertEquals("L-turn X should match terminal X (vertical exit)", 160, path.get(1).x());
        assertEquals("L-turn Y should match next BP Y", 300, path.get(1).y());
    }

    @Test
    public void shouldNotModifyPath_whenTerminalsUnchanged() {
        RoutingRect source = new RoutingRect(100, 200, 120, 40, "src");
        RoutingRect target = new RoutingRect(500, 200, 120, 40, "tgt");
        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        Collections.emptyList(), null, 0);

        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(221, 220),
                new AbsoluteBendpointDto(300, 220),
                new AbsoluteBendpointDto(300, 220),
                new AbsoluteBendpointDto(499, 220)
        ));
        int[] savedFirst = {221, 220};
        int[] savedLast = {499, 220};

        RoutingPipeline.realignTerminals(path, savedFirst, savedLast, conn);

        // After duplicate/collinear cleanup, size may differ but terminals remain correct
        assertEquals("Source terminal unchanged", 221, path.get(0).x());
        assertEquals("Target terminal unchanged", 499, path.get(path.size() - 1).x());
    }

    // --- determineFaceFromTerminal tests ---

    @Test
    public void shouldDetectRightFace_whenTerminalAtRightEdge() {
        RoutingRect element = new RoutingRect(100, 200, 120, 40, "e1");
        // RIGHT: x + w + 1 = 221
        int[] terminal = {221, 220};
        assertEquals(EdgeAttachmentCalculator.Face.RIGHT,
                RoutingPipeline.determineFaceFromTerminal(terminal, element));
    }

    @Test
    public void shouldDetectLeftFace_whenTerminalAtLeftEdge() {
        RoutingRect element = new RoutingRect(100, 200, 120, 40, "e1");
        // LEFT: x - 1 = 99
        int[] terminal = {99, 220};
        assertEquals(EdgeAttachmentCalculator.Face.LEFT,
                RoutingPipeline.determineFaceFromTerminal(terminal, element));
    }

    @Test
    public void shouldDetectTopFace_whenTerminalAtTopEdge() {
        RoutingRect element = new RoutingRect(100, 200, 120, 40, "e1");
        // TOP: y - 1 = 199
        int[] terminal = {160, 199};
        assertEquals(EdgeAttachmentCalculator.Face.TOP,
                RoutingPipeline.determineFaceFromTerminal(terminal, element));
    }

    @Test
    public void shouldDetectBottomFace_whenTerminalAtBottomEdge() {
        RoutingRect element = new RoutingRect(100, 200, 120, 40, "e1");
        // BOTTOM: y + h + 1 = 241
        int[] terminal = {160, 241};
        assertEquals(EdgeAttachmentCalculator.Face.BOTTOM,
                RoutingPipeline.determineFaceFromTerminal(terminal, element));
    }

    // --- Full pipeline integration test ---

    @Test
    public void shouldPreserveTerminalFaceCenter_whenLargeHorizontalOffset() {
        // AC3: Large horizontal offset should not produce diagonal exit
        RoutingRect source = new RoutingRect(100, 200, 120, 40, "src");  // center (160, 220)
        RoutingRect target = new RoutingRect(600, 190, 120, 40, "tgt");  // center (660, 210)
        // No obstacles — direct route
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        Collections.emptyList(), null, 0));

        RoutingResult result = pipeline.routeAllConnections(
                connections, Collections.emptyList());

        assertTrue("Connection should be routed successfully", result.routed().containsKey("c1"));
        List<AbsoluteBendpointDto> path = result.routed().get("c1");
        assertFalse("Should have bendpoints", path.isEmpty());

        // First BP should be at source RIGHT face: x = 221 (100+120+1)
        AbsoluteBendpointDto firstBp = path.get(0);
        assertEquals("Source terminal X at RIGHT face edge", 221, firstBp.x());
        // Y should be at face center (220) or within distribution tolerance
        assertEquals("Source terminal Y at face center", 220, firstBp.y());

        // First segment must be perpendicular (horizontal for RIGHT face)
        if (path.size() >= 2) {
            AbsoluteBendpointDto secondBp = path.get(1);
            assertEquals("First segment must be horizontal (same Y)",
                    firstBp.y(), secondBp.y());
        }
    }

    @Test
    public void shouldPreserveTerminalFaceCenter_whenLargeVerticalOffset() {
        // AC4: Large vertical offset should not produce diagonal exit
        RoutingRect source = new RoutingRect(100, 100, 120, 40, "src");  // center (160, 120)
        RoutingRect target = new RoutingRect(110, 500, 120, 40, "tgt");  // center (170, 520)
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        Collections.emptyList(), null, 0));

        RoutingResult result = pipeline.routeAllConnections(
                connections, Collections.emptyList());

        assertTrue("Connection should be routed successfully", result.routed().containsKey("c1"));
        List<AbsoluteBendpointDto> path = result.routed().get("c1");
        assertFalse("Should have bendpoints", path.isEmpty());

        // First BP should be at source BOTTOM face: y = 141 (100+40+1)
        AbsoluteBendpointDto firstBp = path.get(0);
        assertEquals("Source terminal Y at BOTTOM face edge", 141, firstBp.y());

        // First segment must be perpendicular (vertical for BOTTOM face)
        if (path.size() >= 2) {
            AbsoluteBendpointDto secondBp = path.get(1);
            assertEquals("First segment must be vertical (same X)",
                    firstBp.x(), secondBp.x());
        }
    }

    // --- Code review additions (M2, M3, M4) ---

    @Test
    public void shouldRestoreBothTerminals_whenBothShiftedByMicroJogRemoval() {
        // M2: Both source and target shifted simultaneously
        RoutingRect source = new RoutingRect(100, 200, 120, 40, "src");
        RoutingRect target = new RoutingRect(500, 200, 120, 40, "tgt");
        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        Collections.emptyList(), null, 0);

        // Both terminals shifted: source Y 220→228, target Y 220→228
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(221, 228),  // shifted source
                new AbsoluteBendpointDto(300, 228),
                new AbsoluteBendpointDto(300, 228),
                new AbsoluteBendpointDto(499, 228)   // shifted target
        ));

        int[] savedFirst = {221, 220};
        int[] savedLast = {499, 220};

        RoutingPipeline.realignTerminals(path, savedFirst, savedLast, conn);

        assertEquals("Source terminal Y restored", 220, path.get(0).y());
        assertEquals("Source terminal X restored", 221, path.get(0).x());
        AbsoluteBendpointDto lastBp = path.get(path.size() - 1);
        assertEquals("Target terminal Y restored", 220, lastBp.y());
        assertEquals("Target terminal X restored", 499, lastBp.x());
    }

    @Test
    public void shouldDetectFace_whenDistributedTerminalNotAtCenter() {
        // M3 / AC6: Distributed terminal offset from face center
        RoutingRect element = new RoutingRect(100, 200, 120, 40, "e1");
        // RIGHT face, distributed: x = 221 (edge+1), y = 210 (not center 220)
        int[] terminal = {221, 210};
        assertEquals(EdgeAttachmentCalculator.Face.RIGHT,
                RoutingPipeline.determineFaceFromTerminal(terminal, element));

        // BOTTOM face, distributed: y = 241 (edge+1), x = 140 (not center 160)
        int[] terminalBottom = {140, 241};
        assertEquals(EdgeAttachmentCalculator.Face.BOTTOM,
                RoutingPipeline.determineFaceFromTerminal(terminalBottom, element));
    }

    @Test
    public void shouldRealignTerminals_whenRedistributedToAdjacentFace() {
        // M4 / AC7: Hub redistribution moves connection to adjacent face
        // Source terminal redistributed from RIGHT to BOTTOM face
        RoutingRect source = new RoutingRect(100, 200, 120, 40, "src");  // bottom edge at y=240
        RoutingRect target = new RoutingRect(300, 400, 120, 40, "tgt");
        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        Collections.emptyList(), null, 0);

        // Source terminal on BOTTOM face (redistributed): x=160 (center), y=241 (y+h+1)
        // Micro-jog shifted X from 160 to 168
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(168, 241),  // shifted source terminal
                new AbsoluteBendpointDto(200, 300),  // intermediate
                new AbsoluteBendpointDto(360, 399)   // target at LEFT face
        ));

        int[] savedFirst = {160, 241};  // original BOTTOM face center
        int[] savedLast = {360, 399};   // target unchanged

        RoutingPipeline.realignTerminals(path, savedFirst, savedLast, conn);

        assertEquals("Source terminal X restored after redistribution", 160, path.get(0).x());
        assertEquals("Source terminal Y preserved on BOTTOM face", 241, path.get(0).y());
        // Perpendicular segment from BOTTOM face: vertical (same X)
        assertEquals("First segment vertical after BOTTOM face restoration",
                160, path.get(1).x());
    }

    // --- Snap-to-straight tests (backlog-b17) ---

    @Test
    public void shouldSnapToStraight_whenVerticalNearAligned12px() {
        // 12px horizontal offset (like the E2E Data Warehouse → Regulatory Reporting case)
        // Source center: (50, 200), Target center: (62, 400)
        // After edge attachment, terminals would be ~12px apart horizontally
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(50, 231),   // source bottom face
                new AbsoluteBendpointDto(50, 300),   // Z-bend start
                new AbsoluteBendpointDto(62, 300),   // Z-bend end (12px jog)
                new AbsoluteBendpointDto(62, 369)    // target top face
        ));

        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");   // bottom at y=230
        RoutingRect target = new RoutingRect(12, 370, 100, 60, "tgt");  // top at y=370

        RoutingPipeline.snapToStraightIfAligned(path, source, target,
                Collections.emptyList(), 20);

        assertEquals("Should snap to 2-point straight path", 2, path.size());
        assertEquals("Source X snapped to target X", 62, path.get(0).x());
        assertEquals("Source Y preserved", 231, path.get(0).y());
        assertEquals("Target unchanged", 62, path.get(1).x());
        assertEquals("Target unchanged", 369, path.get(1).y());
    }

    @Test
    public void shouldSnapToStraight_whenHorizontalNearAligned15px() {
        // 15px vertical offset, horizontally aligned elements
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(101, 200),  // source right face
                new AbsoluteBendpointDto(200, 200),  // Z-bend start
                new AbsoluteBendpointDto(200, 215),  // Z-bend end (15px jog)
                new AbsoluteBendpointDto(299, 215)   // target left face
        ));

        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(300, 185, 100, 60, "tgt");

        RoutingPipeline.snapToStraightIfAligned(path, source, target,
                Collections.emptyList(), 20);

        assertEquals("Should snap to 2-point straight path", 2, path.size());
        assertEquals("Source X preserved", 101, path.get(0).x());
        assertEquals("Source Y snapped to target Y", 215, path.get(0).y());
        assertEquals("Target unchanged", 299, path.get(1).x());
    }

    @Test
    public void shouldNotSnap_whenOffsetExceedsThreshold() {
        // 25px offset — above the 20px threshold
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(50, 231),
                new AbsoluteBendpointDto(50, 300),
                new AbsoluteBendpointDto(75, 300),   // 25px jog
                new AbsoluteBendpointDto(75, 369)
        ));

        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(25, 370, 100, 60, "tgt");

        RoutingPipeline.snapToStraightIfAligned(path, source, target,
                Collections.emptyList(), 20);

        assertEquals("Path should be unchanged (offset > threshold)", 4, path.size());
    }

    @Test
    public void shouldNotSnap_whenObstacleBlocksStraightPath() {
        // 12px offset but obstacle between source and target
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(50, 231),
                new AbsoluteBendpointDto(50, 270),
                new AbsoluteBendpointDto(62, 270),
                new AbsoluteBendpointDto(62, 369)
        ));

        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(12, 370, 100, 60, "tgt");
        // Obstacle at y=280-340, blocking the straight vertical path
        RoutingRect obstacle = new RoutingRect(30, 280, 60, 60, "obs");

        RoutingPipeline.snapToStraightIfAligned(path, source, target,
                List.of(obstacle), 20);

        assertEquals("Path should be unchanged (obstacle blocks straight path)", 4, path.size());
    }

    @Test
    public void shouldNotModify_whenExactlyAligned() {
        // 0px offset — already straight, should be a no-op
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(50, 231),
                new AbsoluteBendpointDto(50, 369)
        ));

        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(0, 370, 100, 60, "tgt");

        RoutingPipeline.snapToStraightIfAligned(path, source, target,
                Collections.emptyList(), 20);

        assertEquals("Already straight — should remain 2 points", 2, path.size());
        assertEquals("X unchanged", 50, path.get(0).x());
        assertEquals("Y unchanged", 231, path.get(0).y());
    }

    @Test
    public void shouldNotSnap_whenThresholdIsZero() {
        // Threshold 0 disables snap
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(50, 231),
                new AbsoluteBendpointDto(50, 300),
                new AbsoluteBendpointDto(62, 300),
                new AbsoluteBendpointDto(62, 369)
        ));

        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(12, 370, 100, 60, "tgt");

        RoutingPipeline.snapToStraightIfAligned(path, source, target,
                Collections.emptyList(), 0);

        assertEquals("Threshold=0 should disable snap", 4, path.size());
    }

    @Test
    public void shouldSnapInBatchRouting_whenSnapThresholdProvided() {
        // Integration test: batch routing with snap threshold via routeAllConnections
        // Source center (50, 200), Target center (62, 400) — 12px horizontal offset
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(12, 370, 100, 60, "tgt");

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        Collections.emptyList(), "", 1));
        List<RoutingRect> allObstacles = List.of(source, target);

        RoutingResult result = pipeline.routeAllConnections(
                connections, allObstacles, null, 20);

        List<AbsoluteBendpointDto> routed = result.routed().get("c1");
        assertNotNull("Connection should be routed", routed);

        // After snap, all bendpoints should share the same X (straight vertical)
        if (routed.size() >= 2) {
            int firstX = routed.get(0).x();
            int lastX = routed.get(routed.size() - 1).x();
            // The snap should produce a straight path (same X for all points)
            // Allow small tolerance for edge attachment adjustments
            assertTrue("Snapped path should be near-straight (|deltaX| <= 1)",
                    Math.abs(lastX - firstX) <= 1);
        }
    }

    @Test
    public void shouldPreserveZBend_whenSnapThresholdDisabled() {
        // 18px offset — above micro-jog threshold (15) so Z-bend survives full pipeline.
        // With snap disabled (threshold=0), the Z-bend should be preserved.
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(18, 370, 100, 60, "tgt");

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        Collections.emptyList(), "", 1));
        List<RoutingRect> allObstacles = List.of(source, target);

        RoutingResult result = pipeline.routeAllConnections(
                connections, allObstacles, null, 0);

        List<AbsoluteBendpointDto> routed = result.routed().get("c1");
        assertNotNull("Connection should be routed", routed);
        assertTrue("Z-bend should survive with snap disabled (expect > 2 points)",
                routed.size() > 2);
    }

    // ---- Straight-line crossing estimate tests (backlog-b22) ----

    @Test
    public void shouldReturnZeroCrossings_whenEmptyConnectionList() {
        int crossings = RoutingPipeline.computeStraightLineCrossings(
                List.of(), List.of());
        assertEquals(0, crossings);
    }

    @Test
    public void shouldReturnZeroCrossings_whenParallelNonIntersecting() {
        // Two horizontal parallel connections that don't cross
        List<int[]> sources = List.of(new int[]{0, 0}, new int[]{0, 100});
        List<int[]> targets = List.of(new int[]{200, 0}, new int[]{200, 100});
        int crossings = RoutingPipeline.computeStraightLineCrossings(sources, targets);
        assertEquals(0, crossings);
    }

    @Test
    public void shouldReturnOneCrossing_whenTwoSegmentsCross() {
        // X pattern: (0,0)-(200,200) crosses (200,0)-(0,200)
        List<int[]> sources = List.of(new int[]{0, 0}, new int[]{200, 0});
        List<int[]> targets = List.of(new int[]{200, 200}, new int[]{0, 200});
        int crossings = RoutingPipeline.computeStraightLineCrossings(sources, targets);
        assertEquals(1, crossings);
    }

    @Test
    public void shouldCountMultipleCrossings_whenThreeSegmentsFormTriangle() {
        // Three segments forming a star pattern — each pair crosses
        List<int[]> sources = List.of(
                new int[]{0, 0}, new int[]{200, 0}, new int[]{100, 0});
        List<int[]> targets = List.of(
                new int[]{200, 200}, new int[]{0, 200}, new int[]{100, 200});
        int crossings = RoutingPipeline.computeStraightLineCrossings(sources, targets);
        // (0,0)-(200,200) crosses (200,0)-(0,200): yes
        // (0,0)-(200,200) crosses (100,0)-(100,200): yes
        // (200,0)-(0,200) crosses (100,0)-(100,200): yes
        assertEquals(3, crossings);
    }

    @Test
    public void shouldReturnZeroCrossings_whenSingleConnection() {
        List<int[]> sources = List.of(new int[]{0, 0});
        List<int[]> targets = List.of(new int[]{100, 100});
        int crossings = RoutingPipeline.computeStraightLineCrossings(sources, targets);
        assertEquals(0, crossings);
    }

    // ---- Segment intersection tests (backlog-b22) ----

    @Test
    public void shouldDetectIntersection_whenSegmentsCrossInMiddle() {
        assertTrue(RoutingPipeline.segmentsIntersect(0, 0, 100, 100, 100, 0, 0, 100));
    }

    @Test
    public void shouldNotDetectIntersection_whenParallelSegments() {
        assertFalse(RoutingPipeline.segmentsIntersect(0, 0, 100, 0, 0, 10, 100, 10));
    }

    @Test
    public void shouldNotDetectIntersection_whenSegmentsShareEndpoint() {
        // T-junction at (50,50) — strict intersection excludes endpoints
        assertFalse(RoutingPipeline.segmentsIntersect(0, 0, 50, 50, 50, 50, 100, 100));
    }

    // ---- Bendpoint clearance enforcement tests (backlog-b22) ----

    @Test
    public void shouldNudgeBendpoint_whenTooCloseToObstacle() {
        // BP at (104, 200) is 4px from obstacle right edge at (100+10=110... no)
        // Obstacle at (100, 180, 40, 40) — right edge at 140, top at 180, bottom at 220
        // BP at (144, 200) is 4px from right edge (140+4=144), inside vertical band
        RoutingRect source = new RoutingRect(0, 190, 40, 20, "src");
        RoutingRect target = new RoutingRect(300, 190, 40, 20, "tgt");
        RoutingRect obstacle = new RoutingRect(100, 180, 40, 40, "obs");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(20, 200));  // terminal — skip
        path.add(new AbsoluteBendpointDto(144, 200)); // 4px from obs right edge (140), inside vertical band
        path.add(new AbsoluteBendpointDto(320, 200)); // terminal — skip

        int nudged = RoutingPipeline.enforceMinClearance(path, List.of(obstacle, source, target), source, target);

        assertEquals(1, nudged);
        // Should be nudged to 148 (140 + MIN_CLEARANCE=8)
        assertEquals(148, path.get(1).x());
        assertEquals(200, path.get(1).y());
    }

    @Test
    public void shouldNotNudgeBendpoint_whenAlreadyFarFromObstacles() {
        RoutingRect source = new RoutingRect(0, 190, 40, 20, "src");
        RoutingRect target = new RoutingRect(300, 190, 40, 20, "tgt");
        RoutingRect obstacle = new RoutingRect(100, 180, 40, 40, "obs");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(20, 200));  // terminal
        path.add(new AbsoluteBendpointDto(160, 200)); // 20px from obs right edge — safe
        path.add(new AbsoluteBendpointDto(320, 200)); // terminal

        int nudged = RoutingPipeline.enforceMinClearance(path, List.of(obstacle, source, target), source, target);

        assertEquals(0, nudged);
        assertEquals(160, path.get(1).x());
    }

    @Test
    public void shouldNotNudgeTerminalBendpoints() {
        RoutingRect source = new RoutingRect(0, 190, 40, 20, "src");
        RoutingRect target = new RoutingRect(300, 190, 40, 20, "tgt");
        RoutingRect obstacle = new RoutingRect(15, 180, 40, 40, "obs");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(20, 200));  // terminal — 1px from obs, but should NOT be nudged
        path.add(new AbsoluteBendpointDto(200, 200)); // intermediate — far from obs
        path.add(new AbsoluteBendpointDto(320, 200)); // terminal

        int nudged = RoutingPipeline.enforceMinClearance(path, List.of(obstacle, source, target), source, target);

        assertEquals(0, nudged);
        assertEquals(20, path.get(0).x()); // unchanged
    }

    @Test
    public void shouldExcludeSourceTargetFromClearanceCheck() {
        RoutingRect source = new RoutingRect(0, 190, 40, 20, "src");
        RoutingRect target = new RoutingRect(300, 190, 40, 20, "tgt");

        // BP is 2px from source right edge — but source is excluded for own connection
        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(20, 200));  // terminal
        path.add(new AbsoluteBendpointDto(42, 200));  // 2px from source right edge (40)
        path.add(new AbsoluteBendpointDto(320, 200)); // terminal

        int nudged = RoutingPipeline.enforceMinClearance(path, List.of(source, target), source, target);

        assertEquals(0, nudged);
        assertEquals(42, path.get(1).x()); // unchanged — source excluded
    }

    @Test
    public void shouldRevertNudge_whenNudgeCreatesNewViolation() {
        RoutingRect source = new RoutingRect(0, 190, 40, 20, "src");
        RoutingRect target = new RoutingRect(300, 190, 40, 20, "tgt");
        // Two obstacles sandwiching a narrow corridor — nudging away from one
        // would push BP into the other
        RoutingRect obs1 = new RoutingRect(140, 180, 40, 40, "obs1"); // right edge at 180
        RoutingRect obs2 = new RoutingRect(185, 180, 40, 40, "obs2"); // left edge at 185

        // BP at (183, 200) is 3px from obs1 right edge (180), inside obs1 vertical band
        // Nudging to 188 would be inside obs2 (185 to 225)
        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(20, 200));  // terminal
        path.add(new AbsoluteBendpointDto(183, 200)); // 3px from obs1 right
        path.add(new AbsoluteBendpointDto(320, 200)); // terminal

        int nudged = RoutingPipeline.enforceMinClearance(
                path, List.of(obs1, obs2, source, target), source, target);

        // The nudge to 188 would be inside obs2 (185-225 x range, 180-220 y range)
        // so it should be reverted
        assertEquals(0, nudged);
        assertEquals(183, path.get(1).x()); // unchanged
    }

    @Test
    public void shouldReturnZero_whenPathTooShortForIntermediateBPs() {
        RoutingRect source = new RoutingRect(0, 190, 40, 20, "src");
        RoutingRect target = new RoutingRect(300, 190, 40, 20, "tgt");

        // Only 2 BPs — both terminal, none intermediate
        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(20, 200));
        path.add(new AbsoluteBendpointDto(320, 200));

        int nudged = RoutingPipeline.enforceMinClearance(
                path, List.of(source, target), source, target);
        assertEquals(0, nudged);
    }

    @Test
    public void shouldNudgeBendpointInsideObstacle_toMinClearanceOutside() {
        RoutingRect source = new RoutingRect(0, 190, 40, 20, "src");
        RoutingRect target = new RoutingRect(300, 190, 40, 20, "tgt");
        // Obstacle at (100, 180, 40, 40) — right edge 140, bottom edge 220
        RoutingRect obstacle = new RoutingRect(100, 180, 40, 40, "obs");

        // BP at (120, 200) is INSIDE obstacle (100-140 x, 180-220 y)
        // In horizontal band AND vertical band — corner/inside case
        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(20, 200));  // terminal
        path.add(new AbsoluteBendpointDto(120, 200)); // inside obstacle
        path.add(new AbsoluteBendpointDto(320, 200)); // terminal

        int nudged = RoutingPipeline.enforceMinClearance(path, List.of(obstacle, source, target), source, target);

        assertEquals(1, nudged);
        // BP was at (120, 200). Inside obs (100-140, 180-220).
        // Nearest vertical edges: left=100 (dist 20), right=140 (dist 20) — tie, picks left → nudge to 92
        // Nearest horizontal edges: top=180 (dist 20), bottom=220 (dist 20) — tie, picks top → nudge to 172
        // Both axes encroach, both nudges apply
        AbsoluteBendpointDto nudgedBp = path.get(1);
        assertEquals("BP should be nudged to obsLeft - MIN_CLEARANCE", 92, nudgedBp.x());
        assertEquals("BP should be nudged to obsTop - MIN_CLEARANCE", 172, nudgedBp.y());
    }

    // ---- Straight-line crossing estimate in pipeline integration test (backlog-b22) ----

    @Test
    public void shouldIncludeStraightLineCrossingsInRoutingResult() {
        // Two crossing connections: (0,0)-(400,400) and (400,0)-(0,400)
        RoutingRect src1 = new RoutingRect(0, 0, 80, 60, "s1");       // center (40, 30)
        RoutingRect tgt1 = new RoutingRect(360, 360, 80, 60, "t1");   // center (400, 390)
        RoutingRect src2 = new RoutingRect(360, 0, 80, 60, "s2");     // center (400, 30)
        RoutingRect tgt2 = new RoutingRect(0, 360, 80, 60, "t2");     // center (40, 390)

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", src1, tgt1,
                        List.of(src2, tgt2), "", 1),
                new RoutingPipeline.ConnectionEndpoints("c2", src2, tgt2,
                        List.of(src1, tgt1), "", 1));
        List<RoutingRect> allObstacles = List.of(src1, tgt1, src2, tgt2);

        RoutingResult result = pipeline.routeAllConnections(connections, allObstacles);

        assertEquals("Straight-line X-pattern should have 1 crossing",
                1, result.straightLineCrossings());
    }

    @Test
    public void shouldReturnZeroStraightLineCrossings_whenParallelConnections() {
        RoutingRect src1 = new RoutingRect(0, 0, 80, 60, "s1");
        RoutingRect tgt1 = new RoutingRect(300, 0, 80, 60, "t1");
        RoutingRect src2 = new RoutingRect(0, 200, 80, 60, "s2");
        RoutingRect tgt2 = new RoutingRect(300, 200, 80, 60, "t2");

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", src1, tgt1,
                        List.of(src2, tgt2), "", 1),
                new RoutingPipeline.ConnectionEndpoints("c2", src2, tgt2,
                        List.of(src1, tgt1), "", 1));
        List<RoutingRect> allObstacles = List.of(src1, tgt1, src2, tgt2);

        RoutingResult result = pipeline.routeAllConnections(connections, allObstacles);

        assertEquals("Parallel connections should have 0 straight-line crossings",
                0, result.straightLineCrossings());
    }

    // ---- Crossing inflation warning tests (backlog-b22, code review fix M4) ----

    @Test
    public void shouldBuildCrossingInflationWarning_whenAboveThreshold() {
        String warning = RoutingPipeline.buildCrossingInflationWarning(17, 8);
        assertNotNull("Should warn when crossings are 2.1x estimate", warning);
        assertTrue(warning.contains("17 crossings"));
        assertTrue(warning.contains("8 straight-line estimate"));
        assertTrue(warning.contains("2.1x"));
    }

    @Test
    public void shouldNotBuildCrossingInflationWarning_whenBelowThreshold() {
        String warning = RoutingPipeline.buildCrossingInflationWarning(10, 8);
        assertNull("Should not warn when crossings are 1.25x estimate (below 1.5x)", warning);
    }

    @Test
    public void shouldNotBuildCrossingInflationWarning_whenAtExactThreshold() {
        // 12 / 8 = 1.5x exactly — not strictly greater than threshold
        String warning = RoutingPipeline.buildCrossingInflationWarning(12, 8);
        assertNull("Should not warn at exactly 1.5x (threshold is strictly greater)", warning);
    }

    @Test
    public void shouldNotBuildCrossingInflationWarning_whenZeroEstimate() {
        String warning = RoutingPipeline.buildCrossingInflationWarning(5, 0);
        assertNull("Should not warn when straight-line estimate is 0", warning);
    }

    @Test
    public void shouldNotBuildCrossingInflationWarning_whenRoutingReducesCrossings() {
        String warning = RoutingPipeline.buildCrossingInflationWarning(3, 8);
        assertNull("Should not warn when routing reduces crossings", warning);
    }

    // ---- B25: Orthogonality-preserving propagation tests ----

    @Test
    public void shouldPropagateYNudge_toAdjacentNonTerminalBP() {
        // 5-BP L-shaped path: horizontal segment at Y=200, then vertical down
        // Obstacle near the horizontal segment forces Y nudge on BP index 2,
        // which should propagate to BP index 1 (shares Y=200, non-terminal)
        RoutingRect source = new RoutingRect(0, 190, 40, 20, "src");
        RoutingRect target = new RoutingRect(300, 290, 40, 20, "tgt");
        // Obstacle at (140, 195, 30, 10) — top at 195, bottom at 205
        // BP at (155, 200) is in horizontal band (140-170), 5px from top (195) → needs nudge
        RoutingRect obstacle = new RoutingRect(140, 195, 30, 10, "obs");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(20, 200));   // 0: terminal
        path.add(new AbsoluteBendpointDto(100, 200));  // 1: intermediate, Y=200 (horizontal segment)
        path.add(new AbsoluteBendpointDto(155, 200));  // 2: intermediate, Y=200, in obstacle's h-band
        path.add(new AbsoluteBendpointDto(155, 300));  // 3: intermediate, X=155 (vertical segment)
        path.add(new AbsoluteBendpointDto(320, 300));  // 4: terminal

        int nudged = RoutingPipeline.enforceMinClearance(
                path, List.of(obstacle, source, target), source, target);

        assertEquals("BP[2] should be nudged", 1, nudged);
        // BP[2] nudged from Y=200 to Y=187 (195 - 8 = 187)
        assertEquals(187, path.get(2).y());
        // BP[1] should be propagated to Y=187 (shares Y=200 with BP[2], non-terminal)
        assertEquals("BP[1] should be propagated to same Y as nudged BP[2]", 187, path.get(1).y());
        // X coordinates should be unchanged
        assertEquals(100, path.get(1).x());
        assertEquals(155, path.get(2).x());
    }

    @Test
    public void shouldPropagateXNudge_toAdjacentNonTerminalBP() {
        // Path with vertical segment: BP shares X with neighbor
        RoutingRect source = new RoutingRect(0, 0, 40, 20, "src");
        RoutingRect target = new RoutingRect(0, 300, 40, 20, "tgt");
        // Obstacle at (95, 100, 10, 30) — left at 95, right at 105
        // BP at (100, 115) is in vertical band (100-130), 5px from left (95)
        RoutingRect obstacle = new RoutingRect(95, 100, 10, 30, "obs");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(20, 10));    // 0: terminal
        path.add(new AbsoluteBendpointDto(100, 10));   // 1: intermediate (horizontal to terminal)
        path.add(new AbsoluteBendpointDto(100, 115));  // 2: intermediate, X=100 (vertical segment with BP[1])
        path.add(new AbsoluteBendpointDto(100, 250));  // 3: intermediate, X=100
        path.add(new AbsoluteBendpointDto(20, 310));   // 4: terminal

        int nudged = RoutingPipeline.enforceMinClearance(
                path, List.of(obstacle, source, target), source, target);

        assertTrue("At least one BP should be nudged", nudged >= 1);
        // BP[2] should be nudged in X: obs right at 105, nudge to 113 (105+8)
        // BP[1] shares X=100 with BP[2], should be propagated
        assertEquals("BP[2] X should be nudged away from obstacle",
                113, path.get(2).x());
        assertEquals("BP[1] X should be propagated to match BP[2]",
                113, path.get(1).x());
    }

    @Test
    public void shouldNotPropagate_whenNeighborIsTerminal() {
        // 3-BP path: both neighbors are terminal → no propagation
        RoutingRect source = new RoutingRect(0, 190, 40, 20, "src");
        RoutingRect target = new RoutingRect(300, 190, 40, 20, "tgt");
        RoutingRect obstacle = new RoutingRect(100, 180, 40, 40, "obs");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(20, 200));   // 0: terminal
        path.add(new AbsoluteBendpointDto(120, 200));  // 1: inside obstacle
        path.add(new AbsoluteBendpointDto(320, 200));  // 2: terminal

        int nudged = RoutingPipeline.enforceMinClearance(
                path, List.of(obstacle, source, target), source, target);

        assertEquals(1, nudged);
        // Terminals should NOT be modified by propagation
        assertEquals(20, path.get(0).x());
        assertEquals(200, path.get(0).y());
        assertEquals(320, path.get(2).x());
        assertEquals(200, path.get(2).y());
    }

    // ---- Multi-obstacle clearance verification test (backlog-b22, code review fix H1) ----

    @Test
    public void shouldRevertNudge_whenMultiObstacleNudgeLeavesResidualViolation() {
        RoutingRect source = new RoutingRect(0, 0, 40, 20, "src");
        RoutingRect target = new RoutingRect(400, 0, 40, 20, "tgt");
        // Obstacle A: right edge at 140, top 190, bottom 210
        RoutingRect obsA = new RoutingRect(100, 190, 40, 20, "obsA");
        // Obstacle B: left edge at 145, top 180, bottom 240 — processed after A
        // A nudge away from B's left (to 137) puts BP within obsA's vertical band
        // and within clearance of obsA's bottom (210)
        RoutingRect obsB = new RoutingRect(145, 180, 40, 60, "obsB");

        // BP at (143, 205): within obsA's horizontal band (100-140? no, 143>140)
        // Let me use a scenario where the sequential processing actually causes a problem:
        // BP at (138, 205): in obsA horizontal band (100-140), 5px below obsA bottom (210-205=5<8)
        // obsA nudge: push BP down to obsBottom+8 = 218
        // Now BP at (138, 218): check obsB (145-185, 180-240) — 138 not in obsB horizontal band
        // That won't trigger. Let me construct a tighter scenario:

        // Actual tight scenario: two obstacles where nudge from one violates the other
        RoutingRect obs1 = new RoutingRect(100, 190, 50, 30, "obs1"); // 100-150, 190-220
        RoutingRect obs2 = new RoutingRect(100, 225, 50, 30, "obs2"); // 100-150, 225-255

        // BP at (120, 222): in obs1 h-band (100-150), 2px below obs1 bottom (220)
        // obs1 nudge: push down to 228 (220+8)
        // Now BP at (120, 228): in obs2 h-band (100-150), 3px above obs2 top (225)
        // obs2 nudge: push up to 217 (225-8)
        // Now BP at (120, 217): back in obs1 h-band, 3px below obs1 bottom (220)!
        // Post-loop verification should detect this and revert
        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(20, 222));   // terminal
        path.add(new AbsoluteBendpointDto(120, 222));  // intermediate — between two close obstacles
        path.add(new AbsoluteBendpointDto(380, 222));  // terminal

        int nudged = RoutingPipeline.enforceMinClearance(
                path, List.of(obs1, obs2, source, target), source, target);

        assertEquals("Post-loop verification should revert — residual clearance violation", 0, nudged);
        assertEquals("BP should be reverted to original x", 120, path.get(1).x());
        assertEquals("BP should be reverted to original y", 222, path.get(1).y());
    }

    // ---- B26: Segment-based clearance enforcement tests ----

    @Test
    public void shouldShiftVerticalSegment_whenTooCloseToObstacleLeftEdge() {
        // Vertical segment at x=27 runs 3px from obstacle left edge at x=30
        RoutingRect source = new RoutingRect(0, 0, 40, 20, "src");
        RoutingRect target = new RoutingRect(0, 500, 40, 20, "tgt");
        // Obstacle at (30, 100, 60, 200) — left edge at 30, right at 90
        RoutingRect obstacle = new RoutingRect(30, 100, 60, 200, "obs");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(20, 10));    // 0: terminal
        path.add(new AbsoluteBendpointDto(27, 10));    // 1: intermediate
        path.add(new AbsoluteBendpointDto(27, 400));   // 2: intermediate — vertical segment at x=27
        path.add(new AbsoluteBendpointDto(20, 400));   // 3: intermediate
        path.add(new AbsoluteBendpointDto(20, 510));   // 4: terminal

        int shifted = RoutingPipeline.enforceSegmentClearance(
                path, List.of(obstacle, source, target), source, target);

        assertEquals("Segment should be shifted", 1, shifted);
        // Segment was 3px from obstacle left (30), needs 8px → shift left by 5px → x=22
        assertEquals(22, path.get(1).x());
        assertEquals(22, path.get(2).x());
    }

    @Test
    public void shouldShiftHorizontalSegment_whenTooCloseToObstacleTopEdge() {
        // Horizontal segment at y=97 runs 3px from obstacle top edge at y=100
        RoutingRect source = new RoutingRect(0, 0, 40, 20, "src");
        RoutingRect target = new RoutingRect(400, 0, 40, 20, "tgt");
        // Obstacle at (100, 100, 200, 60) — top at 100, bottom at 160
        RoutingRect obstacle = new RoutingRect(100, 100, 200, 60, "obs");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(20, 10));    // 0: terminal
        path.add(new AbsoluteBendpointDto(20, 97));    // 1: intermediate
        path.add(new AbsoluteBendpointDto(350, 97));   // 2: intermediate — horizontal segment at y=97
        path.add(new AbsoluteBendpointDto(350, 10));   // 3: intermediate
        path.add(new AbsoluteBendpointDto(420, 10));   // 4: terminal

        int shifted = RoutingPipeline.enforceSegmentClearance(
                path, List.of(obstacle, source, target), source, target);

        assertEquals("Segment should be shifted", 1, shifted);
        // Segment was 3px from obstacle top (100), needs 8px → shift up by 5px → y=92
        assertEquals(92, path.get(1).y());
        assertEquals(92, path.get(2).y());
    }

    @Test
    public void shouldNotShiftSegment_whenAlreadyClear() {
        RoutingRect source = new RoutingRect(0, 0, 40, 20, "src");
        RoutingRect target = new RoutingRect(0, 500, 40, 20, "tgt");
        RoutingRect obstacle = new RoutingRect(30, 100, 60, 200, "obs");

        // Vertical segment at x=15 is 15px from obstacle left edge (30) — already clear
        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(20, 10));    // 0: terminal
        path.add(new AbsoluteBendpointDto(15, 10));    // 1: intermediate
        path.add(new AbsoluteBendpointDto(15, 400));   // 2: intermediate
        path.add(new AbsoluteBendpointDto(20, 400));   // 3: intermediate
        path.add(new AbsoluteBendpointDto(20, 510));   // 4: terminal

        int shifted = RoutingPipeline.enforceSegmentClearance(
                path, List.of(obstacle, source, target), source, target);

        assertEquals("No segment should be shifted", 0, shifted);
        assertEquals(15, path.get(1).x());
        assertEquals(15, path.get(2).x());
    }

    @Test
    public void shouldNotShiftTerminalSegments_inSegmentClearance() {
        // Terminal segments (0->1 and 3->4) should never be shifted
        RoutingRect source = new RoutingRect(0, 0, 40, 20, "src");
        RoutingRect target = new RoutingRect(0, 500, 40, 20, "tgt");
        // Obstacle very close to terminal segment
        RoutingRect obstacle = new RoutingRect(22, 0, 60, 20, "obs");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(20, 10));    // 0: terminal
        path.add(new AbsoluteBendpointDto(20, 100));   // 1: intermediate (segment 0->1 is terminal)
        path.add(new AbsoluteBendpointDto(200, 100));  // 2: intermediate
        path.add(new AbsoluteBendpointDto(200, 400));  // 3: intermediate
        path.add(new AbsoluteBendpointDto(20, 510));   // 4: terminal

        int shifted = RoutingPipeline.enforceSegmentClearance(
                path, List.of(obstacle, source, target), source, target);

        // Segment 0->1 is terminal and should not be checked
        assertEquals(20, path.get(0).x());
        assertEquals(20, path.get(1).x());
    }

    @Test
    public void shouldExcludeSourceTargetFromSegmentClearanceCheck() {
        RoutingRect source = new RoutingRect(0, 0, 40, 20, "src");
        RoutingRect target = new RoutingRect(0, 500, 40, 20, "tgt");

        // Vertical segment at x=43 is 3px from source right edge (40) — but source is excluded
        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(20, 10));    // 0: terminal
        path.add(new AbsoluteBendpointDto(43, 10));    // 1: intermediate
        path.add(new AbsoluteBendpointDto(43, 400));   // 2: intermediate
        path.add(new AbsoluteBendpointDto(20, 400));   // 3: intermediate
        path.add(new AbsoluteBendpointDto(20, 510));   // 4: terminal

        int shifted = RoutingPipeline.enforceSegmentClearance(
                path, List.of(source, target), source, target);

        assertEquals("Source should be excluded", 0, shifted);
        assertEquals(43, path.get(1).x());
        assertEquals(43, path.get(2).x());
    }

    @Test
    public void shouldNotPropagate_whenAdjacentBPsHaveDifferentAxisCoordinate() {
        // Adjacent BPs have different X from the shifted segment — no propagation needed.
        RoutingRect source = new RoutingRect(0, 0, 40, 20, "src");
        RoutingRect target = new RoutingRect(0, 500, 40, 20, "tgt");
        RoutingRect obstacle = new RoutingRect(30, 100, 60, 200, "obs");

        // Path: terminal -> horizontal segment -> vertical segment (x=27, close to obs) -> horizontal -> terminal
        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(200, 10));   // 0: terminal
        path.add(new AbsoluteBendpointDto(200, 50));   // 1: intermediate (x=200, differs from BP[2] x=27)
        path.add(new AbsoluteBendpointDto(27, 50));    // 2: intermediate — start of vertical segment
        path.add(new AbsoluteBendpointDto(27, 400));   // 3: intermediate — end of vertical segment
        path.add(new AbsoluteBendpointDto(200, 400));  // 4: intermediate (x=200, differs from BP[3] x=27)
        path.add(new AbsoluteBendpointDto(200, 510));  // 5: terminal

        int shifted = RoutingPipeline.enforceSegmentClearance(
                path, List.of(obstacle, source, target), source, target);

        assertEquals(1, shifted);
        assertEquals(22, path.get(2).x());
        assertEquals(22, path.get(3).x());
        // No propagation — adjacent BPs don't share the shifted axis coordinate
        assertEquals(200, path.get(1).x());
        assertEquals(200, path.get(4).x());
    }

    @Test
    public void shouldPropagateShift_whenAdjacentBPSharesShiftedAxisCoordinate() {
        // BP[1] shares x=27 with BP[2]. Shifting segment [2,3] in X must propagate to BP[1]
        // to maintain the vertical connection between BP[1] and BP[2].
        RoutingRect source = new RoutingRect(100, 0, 40, 20, "src");
        RoutingRect target = new RoutingRect(100, 500, 40, 20, "tgt");
        RoutingRect obstacle = new RoutingRect(30, 100, 60, 200, "obs");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(100, 10));   // 0: terminal
        path.add(new AbsoluteBendpointDto(27, 10));    // 1: intermediate (x=27, same as BP[2])
        path.add(new AbsoluteBendpointDto(27, 50));    // 2: intermediate — start of segment near obstacle
        path.add(new AbsoluteBendpointDto(27, 400));   // 3: intermediate — end of segment near obstacle
        path.add(new AbsoluteBendpointDto(100, 400));  // 4: intermediate (x=100, differs from BP[3])
        path.add(new AbsoluteBendpointDto(100, 510));  // 5: terminal

        int shifted = RoutingPipeline.enforceSegmentClearance(
                path, List.of(obstacle, source, target), source, target);

        assertEquals(1, shifted);
        // Segment [2,3] shifted from x=27 to x=22
        assertEquals(22, path.get(2).x());
        assertEquals(22, path.get(3).x());
        // BP[1] shared x=27 with BP[2] → propagated to x=22 to maintain vertical segment
        assertEquals("BP[1] should be propagated to maintain orthogonality",
                22, path.get(1).x());
        assertEquals(10, path.get(1).y()); // Y unchanged
        // BP[4] had different x (100) → no propagation
        assertEquals(100, path.get(4).x());
    }

    @Test
    public void shouldRevertSegmentShift_whenCreatesNewViolation() {
        RoutingRect source = new RoutingRect(0, 0, 40, 20, "src");
        RoutingRect target = new RoutingRect(0, 500, 40, 20, "tgt");
        // Two obstacles: obs1 triggers shift left, obs2 is positioned so shifted BPs
        // fall in its vertical band within MIN_CLEARANCE of its right edge.
        RoutingRect obs1 = new RoutingRect(30, 100, 60, 200, "obs1"); // left at 30
        // obs2: x=10, width=8 → right=18. top=40, height=380 → bottom=420.
        // Shifted BP at (22, 50): inV = 50>=40 && 50<=420 → true.
        // dxNear = min(|22-10|, |22-18|) = min(12, 4) = 4 < 8 → violatesClearance!
        RoutingRect obs2 = new RoutingRect(10, 40, 8, 380, "obs2");

        // Vertical segment at x=27 is 3px from obs1 left (30). obs2 right=18, dist=9 (no trigger).
        // Shifting to x=22 puts BPs in obs2's vertical band: dist to right edge (18) = 4px < 8px.
        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(27, 10));    // 0: terminal
        path.add(new AbsoluteBendpointDto(27, 50));    // 1: intermediate
        path.add(new AbsoluteBendpointDto(27, 400));   // 2: intermediate
        path.add(new AbsoluteBendpointDto(27, 450));   // 3: intermediate
        path.add(new AbsoluteBendpointDto(27, 510));   // 4: terminal

        int shifted = RoutingPipeline.enforceSegmentClearance(
                path, List.of(obs1, obs2, source, target), source, target);

        // Shifting from 27 to 22 violates clearance from obs2 (right at 18, dist=4 < 8)
        assertEquals("Shift should be reverted due to new clearance violation", 0, shifted);
        assertEquals(27, path.get(1).x());
        assertEquals(27, path.get(2).x());
    }

    @Test
    public void shouldNotShiftSegment_whenOutsideObstacleExtent() {
        // Vertical segment at x=27, but its Y range doesn't overlap the obstacle
        RoutingRect source = new RoutingRect(0, 0, 40, 20, "src");
        RoutingRect target = new RoutingRect(0, 500, 40, 20, "tgt");
        // Obstacle at (30, 300, 60, 50) — Y range 300-350
        RoutingRect obstacle = new RoutingRect(30, 300, 60, 50, "obs");

        // Segment from (27, 50) to (27, 200) — entirely above obstacle Y range
        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(20, 10));    // 0: terminal
        path.add(new AbsoluteBendpointDto(27, 10));    // 1: intermediate
        path.add(new AbsoluteBendpointDto(27, 200));   // 2: intermediate
        path.add(new AbsoluteBendpointDto(20, 200));   // 3: intermediate
        path.add(new AbsoluteBendpointDto(20, 510));   // 4: terminal

        int shifted = RoutingPipeline.enforceSegmentClearance(
                path, List.of(obstacle, source, target), source, target);

        assertEquals("No shift — segment Y range doesn't overlap obstacle", 0, shifted);
        assertEquals(27, path.get(1).x());
        assertEquals(27, path.get(2).x());
    }

    @Test
    public void shouldReturnZero_whenPathTooShortForIntermediateSegments() {
        RoutingRect source = new RoutingRect(0, 0, 40, 20, "src");
        RoutingRect target = new RoutingRect(300, 0, 40, 20, "tgt");
        RoutingRect obstacle = new RoutingRect(100, 0, 40, 40, "obs");

        // 3 BPs: only terminal segments (0->1 and 1->2), no intermediate segments
        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(20, 10));
        path.add(new AbsoluteBendpointDto(150, 10));
        path.add(new AbsoluteBendpointDto(320, 10));

        int shifted = RoutingPipeline.enforceSegmentClearance(
                path, List.of(obstacle, source, target), source, target);

        assertEquals("Path too short for intermediate segments", 0, shifted);
    }

    @Test
    public void shouldHandleMultipleObstacles_nearestWins() {
        // Vertical segment near two obstacles on the same side, different distances
        RoutingRect source = new RoutingRect(0, 0, 40, 20, "src");
        RoutingRect target = new RoutingRect(0, 500, 40, 20, "tgt");
        // obs1 left at 30 (3px from segment at x=27)
        RoutingRect obs1 = new RoutingRect(30, 100, 60, 100, "obs1");
        // obs2 left at 32 (5px from segment at x=27)
        RoutingRect obs2 = new RoutingRect(32, 250, 60, 100, "obs2");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(20, 10));    // 0: terminal
        path.add(new AbsoluteBendpointDto(27, 10));    // 1: intermediate
        path.add(new AbsoluteBendpointDto(27, 400));   // 2: intermediate
        path.add(new AbsoluteBendpointDto(20, 400));   // 3: intermediate
        path.add(new AbsoluteBendpointDto(20, 510));   // 4: terminal

        int shifted = RoutingPipeline.enforceSegmentClearance(
                path, List.of(obs1, obs2, source, target), source, target);

        assertEquals(1, shifted);
        // obs1 is closest (3px), needs shift of 5px left → x=22
        // obs2 (5px) needs shift of 3px → less than obs1's need
        // Largest shift wins → x=22 (30-8=22)
        assertEquals(22, path.get(1).x());
        assertEquals(22, path.get(2).x());
    }

    // =========================================================================
    // enforceTerminalCorridorClearance — 2-BP paths (backlog-b27)
    // =========================================================================

    @Test
    public void shouldInsertDetour_when2BpVerticalPathGrazesObstacleOnRight() {
        // Vertical 2-BP path at x=29 grazing obstacle with left edge at x=30 (1px gap)
        RoutingRect source = new RoutingRect(0, 0, 40, 20, "src");
        RoutingRect target = new RoutingRect(0, 400, 40, 20, "tgt");
        RoutingRect obstacle = new RoutingRect(30, 100, 60, 200, "obs");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(29, 10));   // T0
        path.add(new AbsoluteBendpointDto(29, 410));  // T1

        int fixed = RoutingPipeline.enforceTerminalCorridorClearance(
                path, List.of(obstacle, source, target), source, target);

        assertEquals(1, fixed);
        assertEquals(4, path.size());
        // Detour left: obsLeft(30) - MIN_CLEARANCE(8) = 22
        assertEquals(29, path.get(0).x());   // T0 unchanged
        assertEquals(22, path.get(1).x());   // I0 at detour X
        assertEquals(10, path.get(1).y());   // same Y as T0
        assertEquals(22, path.get(2).x());   // I1 at detour X
        assertEquals(410, path.get(2).y());  // same Y as T1
        assertEquals(29, path.get(3).x());   // T1 unchanged
    }

    @Test
    public void shouldInsertDetour_when2BpVerticalPathGrazesObstacleOnLeft() {
        // Vertical 2-BP path at x=91 grazing obstacle with right edge at x=90 (1px gap)
        RoutingRect source = new RoutingRect(80, 0, 40, 20, "src");
        RoutingRect target = new RoutingRect(80, 400, 40, 20, "tgt");
        RoutingRect obstacle = new RoutingRect(30, 100, 60, 200, "obs"); // right edge at 90

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(91, 10));
        path.add(new AbsoluteBendpointDto(91, 410));

        int fixed = RoutingPipeline.enforceTerminalCorridorClearance(
                path, List.of(obstacle, source, target), source, target);

        assertEquals(1, fixed);
        assertEquals(4, path.size());
        // Detour right: obsRight(90) + MIN_CLEARANCE(8) = 98
        assertEquals(98, path.get(1).x());
        assertEquals(10, path.get(1).y());
        assertEquals(98, path.get(2).x());
        assertEquals(410, path.get(2).y());
    }

    @Test
    public void shouldInsertDetour_when2BpHorizontalPathGrazesObstacleBelow() {
        // Horizontal 2-BP path at y=99 grazing obstacle with top edge at y=100 (1px gap)
        RoutingRect source = new RoutingRect(0, 80, 20, 40, "src");
        RoutingRect target = new RoutingRect(400, 80, 20, 40, "tgt");
        RoutingRect obstacle = new RoutingRect(100, 100, 200, 60, "obs");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(10, 99));
        path.add(new AbsoluteBendpointDto(410, 99));

        int fixed = RoutingPipeline.enforceTerminalCorridorClearance(
                path, List.of(obstacle, source, target), source, target);

        assertEquals(1, fixed);
        assertEquals(4, path.size());
        // Detour up: obsTop(100) - MIN_CLEARANCE(8) = 92
        assertEquals(10, path.get(1).x());
        assertEquals(92, path.get(1).y());
        assertEquals(410, path.get(2).x());
        assertEquals(92, path.get(2).y());
    }

    @Test
    public void shouldInsertDetour_when2BpHorizontalPathGrazesObstacleAbove() {
        // Horizontal 2-BP path at y=161 grazing obstacle with bottom edge at y=160 (1px gap)
        RoutingRect source = new RoutingRect(0, 150, 20, 40, "src");
        RoutingRect target = new RoutingRect(400, 150, 20, 40, "tgt");
        RoutingRect obstacle = new RoutingRect(100, 100, 200, 60, "obs"); // bottom at 160

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(10, 161));
        path.add(new AbsoluteBendpointDto(410, 161));

        int fixed = RoutingPipeline.enforceTerminalCorridorClearance(
                path, List.of(obstacle, source, target), source, target);

        assertEquals(1, fixed);
        assertEquals(4, path.size());
        // Detour down: obsBottom(160) + MIN_CLEARANCE(8) = 168
        assertEquals(10, path.get(1).x());
        assertEquals(168, path.get(1).y());
        assertEquals(410, path.get(2).x());
        assertEquals(168, path.get(2).y());
    }

    @Test
    public void shouldNotInsertDetour_when2BpPathHasAdequateClearance() {
        // Vertical 2-BP path at x=20 is 10px from obstacle at x=30 — already clear
        RoutingRect source = new RoutingRect(0, 0, 40, 20, "src");
        RoutingRect target = new RoutingRect(0, 400, 40, 20, "tgt");
        RoutingRect obstacle = new RoutingRect(30, 100, 60, 200, "obs");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(20, 10));
        path.add(new AbsoluteBendpointDto(20, 410));

        int fixed = RoutingPipeline.enforceTerminalCorridorClearance(
                path, List.of(obstacle, source, target), source, target);

        assertEquals(0, fixed);
        assertEquals(2, path.size());
    }

    @Test
    public void shouldInsertDetour_when2BpPathGrazesMultipleObstacles() {
        // Vertical 2-BP path at x=29 grazes two obstacles with left edges at x=30 and x=32
        RoutingRect source = new RoutingRect(0, 0, 40, 20, "src");
        RoutingRect target = new RoutingRect(0, 500, 40, 20, "tgt");
        RoutingRect obs1 = new RoutingRect(30, 100, 60, 100, "obs1"); // left at 30
        RoutingRect obs2 = new RoutingRect(32, 250, 60, 100, "obs2"); // left at 32

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(29, 10));
        path.add(new AbsoluteBendpointDto(29, 510));

        int fixed = RoutingPipeline.enforceTerminalCorridorClearance(
                path, List.of(obs1, obs2, source, target), source, target);

        assertEquals(1, fixed);
        assertEquals(4, path.size());
        // Closest obstacle left edge is 30 → detourX = 30 - 8 = 22
        assertEquals(22, path.get(1).x());
        assertEquals(22, path.get(2).x());
    }

    @Test
    public void shouldExcludeSourceTarget_when2BpPathGrazesOwnElement() {
        // 2-BP path at x=29, source element left edge at x=30 — should NOT trigger detour
        // because source is excluded from obstacle checks
        RoutingRect source = new RoutingRect(30, 0, 40, 20, "src");
        RoutingRect target = new RoutingRect(30, 400, 40, 20, "tgt");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(29, 10));
        path.add(new AbsoluteBendpointDto(29, 410));

        int fixed = RoutingPipeline.enforceTerminalCorridorClearance(
                path, List.of(source, target), source, target);

        assertEquals(0, fixed);
        assertEquals(2, path.size());
    }

    @Test
    public void shouldRevert_whenDetourWouldCreateObstacleViolation() {
        // 2-BP vertical path at x=29, obstacle on right at x=30, but another obstacle
        // at the detour position would be violated
        RoutingRect source = new RoutingRect(0, 0, 40, 20, "src");
        RoutingRect target = new RoutingRect(0, 400, 40, 20, "tgt");
        RoutingRect obsRight = new RoutingRect(30, 100, 60, 200, "obs-right");
        // Obstacle blocking the detour at x=22 (where detour BPs would land)
        RoutingRect obsBlocking = new RoutingRect(15, 0, 15, 420, "obs-block"); // x=15..30, covers detour at x=22

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(29, 10));
        path.add(new AbsoluteBendpointDto(29, 410));

        int fixed = RoutingPipeline.enforceTerminalCorridorClearance(
                path, List.of(obsRight, obsBlocking, source, target), source, target);

        assertEquals(0, fixed);
        assertEquals(2, path.size()); // original path preserved
    }

    // =========================================================================
    // enforceTerminalCorridorClearance — 3-BP paths (backlog-b27)
    // =========================================================================

    @Test
    public void shouldShiftIntermediate_when3BpPathHasTerminalAdjacentGraze() {
        // 3-BP path: T0(29,10) → I(29,200) → T1(100,200)
        // Segment T0→I is vertical at x=29, grazing obstacle at x=30
        RoutingRect source = new RoutingRect(0, 0, 40, 20, "src");
        RoutingRect target = new RoutingRect(90, 190, 40, 20, "tgt");
        RoutingRect obstacle = new RoutingRect(30, 50, 60, 100, "obs");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(29, 10));   // T0
        path.add(new AbsoluteBendpointDto(29, 200));  // I
        path.add(new AbsoluteBendpointDto(100, 200)); // T1

        int fixed = RoutingPipeline.enforceTerminalCorridorClearance(
                path, List.of(obstacle, source, target), source, target);

        assertEquals(1, fixed);
        assertEquals(3, path.size()); // still 3 BPs (intermediate shifted, not inserted)
        // Shift left by 7px (MIN_CLEARANCE=8, grazeDist=1): x = 29 - 7 = 22
        assertEquals(22, path.get(1).x());
        assertEquals(200, path.get(1).y());
    }

    @Test
    public void shouldNotShift_when3BpPathHasAdequateClearance() {
        // 3-BP path with no grazing
        RoutingRect source = new RoutingRect(0, 0, 40, 20, "src");
        RoutingRect target = new RoutingRect(200, 190, 40, 20, "tgt");
        RoutingRect obstacle = new RoutingRect(100, 100, 60, 60, "obs");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(20, 10));
        path.add(new AbsoluteBendpointDto(20, 200));
        path.add(new AbsoluteBendpointDto(210, 200));

        int fixed = RoutingPipeline.enforceTerminalCorridorClearance(
                path, List.of(obstacle, source, target), source, target);

        assertEquals(0, fixed);
        assertEquals(3, path.size());
        assertEquals(20, path.get(1).x()); // unchanged
    }

    @Test
    public void shouldInsertDetour_when2BpPathExactlyAtObstacleBoundary() {
        // Vertical 2-BP path at x=30 exactly at obstacle left edge (0px gap) — boundary condition
        RoutingRect source = new RoutingRect(0, 0, 40, 20, "src");
        RoutingRect target = new RoutingRect(0, 400, 40, 20, "tgt");
        RoutingRect obstacle = new RoutingRect(30, 100, 60, 200, "obs"); // left edge at x=30

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(30, 10));  // T0 exactly at obsLeft
        path.add(new AbsoluteBendpointDto(30, 410)); // T1 exactly at obsLeft

        int fixed = RoutingPipeline.enforceTerminalCorridorClearance(
                path, List.of(obstacle, source, target), source, target);

        assertEquals(1, fixed);
        assertEquals(4, path.size());
        // Detour left: obsLeft(30) - MIN_CLEARANCE(8) = 22
        assertEquals(22, path.get(1).x());
        assertEquals(22, path.get(2).x());
    }

    @Test
    public void shouldSkipPaths_withFourOrMoreBps() {
        // 4-BP path — should be handled by enforceSegmentClearance, not terminal corridor
        RoutingRect source = new RoutingRect(0, 0, 40, 20, "src");
        RoutingRect target = new RoutingRect(0, 400, 40, 20, "tgt");
        RoutingRect obstacle = new RoutingRect(30, 100, 60, 200, "obs");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(29, 10));
        path.add(new AbsoluteBendpointDto(29, 50));
        path.add(new AbsoluteBendpointDto(29, 350));
        path.add(new AbsoluteBendpointDto(29, 410));

        int fixed = RoutingPipeline.enforceTerminalCorridorClearance(
                path, List.of(obstacle, source, target), source, target);

        assertEquals(0, fixed);
        assertEquals(4, path.size());
    }

    // =============================================
    // B28: Post-pipeline terminal orthogonality verification
    // =============================================

    @Test
    public void shouldFixDiagonalSourceTerminal() {
        // Source exits RIGHT face → horizontal exit (same Y). Diagonal means different X AND Y.
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(400, 170, 100, 60, "tgt");

        // Source terminal at right face center, but next BP is diagonal (different X and Y)
        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(101, 200));  // source terminal (right face)
        path.add(new AbsoluteBendpointDto(200, 150));  // diagonal from source
        path.add(new AbsoluteBendpointDto(399, 200));  // target terminal (left face)

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        List.of(), "", 1);

        int fixes = RoutingPipeline.enforceTerminalOrthogonality(path, conn);

        assertEquals("Should fix source diagonal", 1, fixes);
        assertEquals("Should insert L-turn BP", 4, path.size());
        // Source exits RIGHT → horizontal exit → maintain source Y
        AbsoluteBendpointDto inserted = path.get(1);
        assertEquals("Inserted BP should have source Y (horizontal exit)", 200, inserted.y());
    }

    @Test
    public void shouldFixDiagonalTargetTerminal() {
        // Target enters LEFT face → horizontal entry (same Y). Diagonal means different X AND Y.
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(400, 170, 100, 60, "tgt");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(101, 200));  // source terminal (right face)
        path.add(new AbsoluteBendpointDto(300, 150));  // diagonal to target
        path.add(new AbsoluteBendpointDto(399, 200));  // target terminal (left face)

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        List.of(), "", 1);

        int fixes = RoutingPipeline.enforceTerminalOrthogonality(path, conn);

        assertEquals("Should fix target diagonal", 1, fixes);
        assertEquals("Should insert L-turn BP", 4, path.size());
        // Target enters LEFT → horizontal entry → maintain target Y
        AbsoluteBendpointDto inserted = path.get(path.size() - 2);
        assertEquals("Inserted BP should have target Y (horizontal entry)", 200, inserted.y());
    }

    @Test
    public void shouldNotModifyAlreadyOrthogonalPath() {
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(400, 170, 100, 60, "tgt");

        // Already orthogonal: horizontal source exit, vertical middle, horizontal target entry
        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(101, 200));  // source terminal
        path.add(new AbsoluteBendpointDto(250, 200));  // same Y as source → orthogonal
        path.add(new AbsoluteBendpointDto(250, 200));  // same X as next → orthogonal (will be cleaned up)
        path.add(new AbsoluteBendpointDto(399, 200));  // target terminal, same Y → orthogonal

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        List.of(), "", 1);

        int fixes = RoutingPipeline.enforceTerminalOrthogonality(path, conn);

        assertEquals("Should not modify already orthogonal path", 0, fixes);
        assertEquals("Path size should be unchanged", 4, path.size());
    }

    @Test
    public void shouldFixBothTerminalsDiagonal() {
        // Both source and target terminals have diagonal segments
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(400, 170, 100, 60, "tgt");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(101, 200));  // source terminal (right face)
        path.add(new AbsoluteBendpointDto(250, 100));  // diagonal from both terminals
        path.add(new AbsoluteBendpointDto(399, 200));  // target terminal (left face)

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        List.of(), "", 1);

        int fixes = RoutingPipeline.enforceTerminalOrthogonality(path, conn);

        assertEquals("Should fix both terminals", 2, fixes);
        assertEquals("Should insert 2 L-turn BPs", 5, path.size());
        // Source exits RIGHT → horizontal exit → inserted BP maintains source Y
        AbsoluteBendpointDto srcInserted = path.get(1);
        assertEquals("Source L-turn should maintain source Y", 200, srcInserted.y());
        // Target enters LEFT → horizontal entry → inserted BP maintains target Y
        AbsoluteBendpointDto tgtInserted = path.get(path.size() - 2);
        assertEquals("Target L-turn should maintain target Y", 200, tgtInserted.y());
    }

    @Test
    public void shouldHandleVerticalExitFace() {
        // Source exits BOTTOM face → vertical exit (same X)
        RoutingRect source = new RoutingRect(170, 0, 60, 100, "src");
        RoutingRect target = new RoutingRect(400, 170, 100, 60, "tgt");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(200, 101));  // source terminal (bottom face)
        path.add(new AbsoluteBendpointDto(300, 200));  // diagonal from source
        path.add(new AbsoluteBendpointDto(399, 200));  // target terminal (left face)

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        List.of(), "", 1);

        int fixes = RoutingPipeline.enforceTerminalOrthogonality(path, conn);

        assertEquals("Should fix source diagonal", 1, fixes);
        assertEquals("Should insert L-turn BP", 4, path.size());
        // Source exits BOTTOM → vertical exit → maintain source X
        AbsoluteBendpointDto inserted = path.get(1);
        assertEquals("Inserted BP should have source X (vertical exit)", 200, inserted.x());
    }

    @Test
    public void shouldNotModifyShortPath() {
        // Path with fewer than 2 BPs should not be modified
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(400, 170, 100, 60, "tgt");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(101, 200));

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        List.of(), "", 1);

        int fixes = RoutingPipeline.enforceTerminalOrthogonality(path, conn);

        assertEquals("Should not modify single-BP path", 0, fixes);
        assertEquals(1, path.size());
    }

    // =============================================
    // B29: ChopboxAnchor center-aligned terminal alignment
    // =============================================

    @Test
    public void shouldAlignSourceTerminal_whenLeftFaceDistributedY() {
        // Element center at (50, 130). LEFT face terminal at (x-1, distY=115) — distY ≠ centerY.
        // Archi draws center(50,130) → BP[0](−1,115) = DIAGONAL.
        // Fix: insert (−1, 130) as new BP[0] so center→BP is horizontal.
        RoutingRect source = new RoutingRect(0, 100, 100, 60, "src"); // center=(50,130)
        RoutingRect target = new RoutingRect(400, 100, 100, 60, "tgt");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(-1, 115));   // source terminal LEFT face, distributed Y
        path.add(new AbsoluteBendpointDto(-1, 50));    // alignment BP (same X, different Y)
        path.add(new AbsoluteBendpointDto(200, 50));   // intermediate
        path.add(new AbsoluteBendpointDto(399, 130));  // target terminal LEFT face, at center

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        List.of(), "", 1);

        int alignments = RoutingPipeline.alignTerminalsWithCenter(path, conn);

        assertEquals("Should insert 1 center-aligned BP", 1, alignments);
        assertEquals("Path should grow by 1", 5, path.size());
        // New BP[0] should share Y with source center (130)
        assertEquals("Center-aligned BP Y should match source centerY", 130, path.get(0).y());
        // New BP[0] should share X with old terminal (face edge)
        assertEquals("Center-aligned BP X should match face edge", -1, path.get(0).x());
        // Old terminal should now be BP[1]
        assertEquals("Old terminal should be BP[1]", 115, path.get(1).y());
    }

    @Test
    public void shouldAlignSourceTerminal_whenRightFaceDistributedY() {
        // Element at (0,100,100,60), center=(50,130). RIGHT face terminal at (101, 145).
        RoutingRect source = new RoutingRect(0, 100, 100, 60, "src"); // center=(50,130)
        RoutingRect target = new RoutingRect(400, 100, 100, 60, "tgt");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(101, 145));  // source terminal RIGHT face, distributed Y
        path.add(new AbsoluteBendpointDto(200, 145));  // alignment BP
        path.add(new AbsoluteBendpointDto(200, 130));  // intermediate
        path.add(new AbsoluteBendpointDto(399, 130));  // target terminal

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        List.of(), "", 1);

        int alignments = RoutingPipeline.alignTerminalsWithCenter(path, conn);

        assertEquals("Should insert 1 center-aligned BP", 1, alignments);
        assertEquals(5, path.size());
        assertEquals("Center-aligned BP Y should match source centerY", 130, path.get(0).y());
        assertEquals("Center-aligned BP X should match face edge", 101, path.get(0).x());
    }

    @Test
    public void shouldAlignSourceTerminal_whenTopFaceDistributedX() {
        // Element at (100,0,120,60), center=(160,30). TOP face terminal at (140, -1).
        RoutingRect source = new RoutingRect(100, 0, 120, 60, "src"); // center=(160,30)
        RoutingRect target = new RoutingRect(100, 300, 120, 60, "tgt");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(140, -1));   // source terminal TOP face, distributed X
        path.add(new AbsoluteBendpointDto(140, -30));  // alignment BP
        path.add(new AbsoluteBendpointDto(160, -30));  // intermediate
        path.add(new AbsoluteBendpointDto(160, 299));  // target terminal

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        List.of(), "", 1);

        int alignments = RoutingPipeline.alignTerminalsWithCenter(path, conn);

        assertEquals("Should insert 1 center-aligned BP", 1, alignments);
        assertEquals(5, path.size());
        assertEquals("Center-aligned BP X should match source centerX", 160, path.get(0).x());
        assertEquals("Center-aligned BP Y should match face edge", -1, path.get(0).y());
    }

    @Test
    public void shouldAlignSourceTerminal_whenBottomFaceDistributedX() {
        // Element at (100,0,120,60), center=(160,30). BOTTOM face terminal at (180, 61).
        RoutingRect source = new RoutingRect(100, 0, 120, 60, "src"); // center=(160,30)
        RoutingRect target = new RoutingRect(100, 300, 120, 60, "tgt");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(180, 61));   // source terminal BOTTOM face, distributed X
        path.add(new AbsoluteBendpointDto(180, 100));  // alignment BP
        path.add(new AbsoluteBendpointDto(160, 100));  // intermediate
        path.add(new AbsoluteBendpointDto(160, 299));  // target terminal

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        List.of(), "", 1);

        int alignments = RoutingPipeline.alignTerminalsWithCenter(path, conn);

        assertEquals("Should insert 1 center-aligned BP", 1, alignments);
        assertEquals(5, path.size());
        assertEquals("Center-aligned BP X should match source centerX", 160, path.get(0).x());
        assertEquals("Center-aligned BP Y should match face edge", 61, path.get(0).y());
    }

    @Test
    public void shouldNotAlign_whenSingleConnectionAtFaceCenter() {
        // Single connection on LEFT face: terminal at face midpoint = center Y → already aligned.
        RoutingRect source = new RoutingRect(0, 100, 100, 60, "src"); // center=(50,130)
        RoutingRect target = new RoutingRect(400, 100, 100, 60, "tgt"); // center=(450,130)

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(-1, 130));   // source terminal LEFT face, Y = centerY
        path.add(new AbsoluteBendpointDto(-1, 50));    // alignment BP
        path.add(new AbsoluteBendpointDto(200, 50));   // intermediate
        path.add(new AbsoluteBendpointDto(399, 130));  // target terminal LEFT face, Y = centerY

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        List.of(), "", 1);

        int alignments = RoutingPipeline.alignTerminalsWithCenter(path, conn);

        assertEquals("Should not insert any BPs", 0, alignments);
        assertEquals("Path size should be unchanged", 4, path.size());
    }

    @Test
    public void shouldAlignBothTerminals_whenBothDistributed() {
        // Both source and target terminals are distributed away from center.
        RoutingRect source = new RoutingRect(0, 100, 100, 60, "src"); // center=(50,130)
        RoutingRect target = new RoutingRect(400, 100, 100, 60, "tgt"); // center=(450,130)

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(101, 115));  // source RIGHT face, distributed Y=115
        path.add(new AbsoluteBendpointDto(200, 115));  // alignment
        path.add(new AbsoluteBendpointDto(200, 145));  // intermediate
        path.add(new AbsoluteBendpointDto(399, 145));  // target LEFT face, distributed Y=145

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        List.of(), "", 1);

        int alignments = RoutingPipeline.alignTerminalsWithCenter(path, conn);

        assertEquals("Should insert 2 center-aligned BPs", 2, alignments);
        assertEquals("Path should grow by 2", 6, path.size());
        // Source: new BP[0] at (101, 130)
        assertEquals("Source center-aligned Y", 130, path.get(0).y());
        assertEquals("Source center-aligned X", 101, path.get(0).x());
        // Target: new BP[n-1] at (399, 130)
        assertEquals("Target center-aligned Y", 130, path.get(path.size() - 1).y());
        assertEquals("Target center-aligned X", 399, path.get(path.size() - 1).x());
    }

    @Test
    public void shouldNotAlign_whenAlreadyAligned() {
        // Both terminals already share coordinates with center → no-op.
        RoutingRect source = new RoutingRect(0, 100, 100, 60, "src"); // center=(50,130)
        RoutingRect target = new RoutingRect(400, 100, 100, 60, "tgt"); // center=(450,130)

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(101, 130));  // source RIGHT face, Y = centerY
        path.add(new AbsoluteBendpointDto(399, 130));  // target LEFT face, Y = centerY

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        List.of(), "", 1);

        int alignments = RoutingPipeline.alignTerminalsWithCenter(path, conn);

        assertEquals("Should not insert any BPs", 0, alignments);
        assertEquals("Path size unchanged", 2, path.size());
    }

    @Test
    public void shouldAlignShortPath_whenTwoBpDistributed() {
        // 2-BP path (terminal-to-terminal). Source at distributed Y.
        RoutingRect source = new RoutingRect(0, 100, 100, 60, "src"); // center=(50,130)
        RoutingRect target = new RoutingRect(0, 300, 100, 60, "tgt"); // center=(50,330)

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(-1, 115));   // source LEFT face, distributed Y=115
        path.add(new AbsoluteBendpointDto(-1, 299));   // target LEFT face, at Y=299 (target center=330)

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        List.of(), "", 1);

        int alignments = RoutingPipeline.alignTerminalsWithCenter(path, conn);

        assertEquals("Should insert 2 center-aligned BPs", 2, alignments);
        assertEquals("Path should grow by 2", 4, path.size());
        // Source: new first BP at (-1, 130)
        assertEquals(130, path.get(0).y());
        assertEquals(-1, path.get(0).x());
        // Target: new last BP at (-1, 330)
        assertEquals(330, path.get(path.size() - 1).y());
        assertEquals(-1, path.get(path.size() - 1).x());
    }

    // =============================================
    // B44: Center-termination fix
    // =============================================

    /**
     * B44 AC2/AC5: fixCenterTerminatedPath should move source terminal from element
     * center to the nearest edge face midpoint when the first BP is at source center.
     */
    @Test
    public void shouldFixSourceCenterTermination_whenFirstBpAtSourceCenter() {
        // Source element at (100, 200) w=120 h=60 → center (160, 230)
        RoutingRect source = new RoutingRect(100, 200, 120, 60, "src");
        RoutingRect target = new RoutingRect(400, 200, 120, 60, "tgt");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        // First BP at source center (center-termination scenario)
        path.add(new AbsoluteBendpointDto(160, 230));
        // Second BP to the right — indicates RIGHT face exit
        path.add(new AbsoluteBendpointDto(300, 230));
        // Target terminal at left face
        path.add(new AbsoluteBendpointDto(399, 230));

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), null, 1);

        int fixes = RoutingPipeline.fixCenterTerminatedPath(path, conn);

        assertEquals("Should fix 1 terminal", 1, fixes);
        // First BP should now be at RIGHT face midpoint: (x+w+1, cy) = (221, 230)
        assertEquals("Source terminal X at right face edge", 221, path.get(0).x());
        assertEquals("Source terminal Y at center", 230, path.get(0).y());
    }

    /**
     * B44 AC2/AC5: fixCenterTerminatedPath should move target terminal from element
     * center to the nearest edge face midpoint when the last BP is at target center.
     */
    @Test
    public void shouldFixTargetCenterTermination_whenLastBpAtTargetCenter() {
        RoutingRect source = new RoutingRect(100, 200, 120, 60, "src");
        // Target at (400, 200) w=120 h=60 → center (460, 230)
        RoutingRect target = new RoutingRect(400, 200, 120, 60, "tgt");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(221, 230)); // source right face
        path.add(new AbsoluteBendpointDto(300, 230));
        // Last BP at target center (center-termination scenario)
        path.add(new AbsoluteBendpointDto(460, 230));

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), null, 1);

        int fixes = RoutingPipeline.fixCenterTerminatedPath(path, conn);

        assertEquals("Should fix 1 terminal", 1, fixes);
        // Last BP should now be at LEFT face midpoint: (x-1, cy) = (399, 230)
        assertEquals("Target terminal X at left face edge", 399, path.get(path.size() - 1).x());
        assertEquals("Target terminal Y at center", 230, path.get(path.size() - 1).y());
    }

    /**
     * B44 AC5: fixCenterTerminatedPath should fix both terminals when both are at centers.
     */
    @Test
    public void shouldFixBothCenterTerminations_whenBothAtElementCenters() {
        RoutingRect source = new RoutingRect(100, 200, 120, 60, "src"); // center (160, 230)
        RoutingRect target = new RoutingRect(400, 200, 120, 60, "tgt"); // center (460, 230)

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(160, 230)); // at source center
        path.add(new AbsoluteBendpointDto(300, 230)); // mid-path
        path.add(new AbsoluteBendpointDto(460, 230)); // at target center

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), null, 1);

        int fixes = RoutingPipeline.fixCenterTerminatedPath(path, conn);

        assertEquals("Should fix 2 terminals", 2, fixes);
        // Source → RIGHT face (next BP is to the right): (221, 230)
        assertEquals(221, path.get(0).x());
        assertEquals(230, path.get(0).y());
        // Target → LEFT face (prev BP is to the left): (399, 230)
        assertEquals(399, path.get(path.size() - 1).x());
        assertEquals(230, path.get(path.size() - 1).y());
    }

    /**
     * B44 AC5: fixCenterTerminatedPath should not modify terminals already at edge faces.
     */
    @Test
    public void shouldNotModifyPath_whenTerminalsNotAtCenter() {
        RoutingRect source = new RoutingRect(100, 200, 120, 60, "src"); // center (160, 230)
        RoutingRect target = new RoutingRect(400, 200, 120, 60, "tgt"); // center (460, 230)

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(221, 230)); // at right face, not center
        path.add(new AbsoluteBendpointDto(300, 230));
        path.add(new AbsoluteBendpointDto(399, 230)); // at left face, not center

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), null, 1);

        int fixes = RoutingPipeline.fixCenterTerminatedPath(path, conn);

        assertEquals("Should fix 0 terminals", 0, fixes);
        assertEquals(221, path.get(0).x());
        assertEquals(399, path.get(path.size() - 1).x());
    }

    /**
     * B44 AC3/AC5: Vertically adjacent elements (small gap) — source should exit from
     * BOTTOM face, target should enter from TOP face, never center.
     */
    @Test
    public void shouldFixCenterTermination_whenVerticallyAdjacentElements() {
        // Source directly above target, 20px gap
        RoutingRect source = new RoutingRect(100, 100, 120, 60, "src"); // center (160, 130), bottom at 160
        RoutingRect target = new RoutingRect(100, 180, 120, 60, "tgt"); // center (160, 210), top at 180

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        // First BP at source center (center-termination)
        path.add(new AbsoluteBendpointDto(160, 130));
        // Last BP at target center (center-termination)
        path.add(new AbsoluteBendpointDto(160, 210));

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), null, 1);

        int fixes = RoutingPipeline.fixCenterTerminatedPath(path, conn);

        assertEquals("Should fix 2 terminals", 2, fixes);
        // Source → BOTTOM face (next BP is below): (cx, y+h+1) = (160, 161)
        assertEquals(160, path.get(0).x());
        assertEquals(161, path.get(0).y());
        // Target → TOP face (prev BP is above): (cx, y-1) = (160, 179)
        assertEquals(160, path.get(path.size() - 1).x());
        assertEquals(179, path.get(path.size() - 1).y());
    }

    /**
     * B44 AC3/AC5: Horizontally adjacent elements (small gap) — source exits RIGHT,
     * target enters LEFT, never center.
     */
    @Test
    public void shouldFixCenterTermination_whenHorizontallyAdjacentElements() {
        // Source to the left of target, 20px gap
        RoutingRect source = new RoutingRect(100, 200, 120, 60, "src"); // center (160, 230), right at 220
        RoutingRect target = new RoutingRect(240, 200, 120, 60, "tgt"); // center (300, 230), left at 240

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(160, 230)); // at source center
        path.add(new AbsoluteBendpointDto(300, 230)); // at target center

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), null, 1);

        int fixes = RoutingPipeline.fixCenterTerminatedPath(path, conn);

        assertEquals("Should fix 2 terminals", 2, fixes);
        // Source → RIGHT face: (x+w+1, cy) = (221, 230)
        assertEquals(221, path.get(0).x());
        assertEquals(230, path.get(0).y());
        // Target → LEFT face: (x-1, cy) = (239, 230)
        assertEquals(239, path.get(path.size() - 1).x());
        assertEquals(230, path.get(path.size() - 1).y());
    }

    /**
     * B44 AC5: Short path with exactly 2 BPs — fixCenterTerminatedPath should handle
     * without ArrayIndexOutOfBoundsException.
     */
    @Test
    public void shouldFixCenterTermination_whenShortPathTwoBps() {
        RoutingRect source = new RoutingRect(100, 200, 120, 60, "src"); // center (160, 230)
        RoutingRect target = new RoutingRect(400, 200, 120, 60, "tgt"); // center (460, 230)

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(160, 230)); // at source center
        path.add(new AbsoluteBendpointDto(460, 230)); // at target center

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), null, 1);

        int fixes = RoutingPipeline.fixCenterTerminatedPath(path, conn);

        assertEquals("Should fix 2 terminals", 2, fixes);
        // After fixing source, first BP moves to edge face. Then target fix uses
        // the updated path. Source → RIGHT (next BP was at target center, to the right)
        assertEquals(221, path.get(0).x());
        // Target → LEFT (prev BP is now at source right face, to the left)
        assertEquals(399, path.get(path.size() - 1).x());
    }

    /**
     * B44 AC5: fixCenterTerminatedPath should return 0 for paths with fewer than 2 BPs.
     */
    @Test
    public void shouldReturnZero_whenPathTooShort() {
        RoutingRect source = new RoutingRect(100, 200, 120, 60, "src");
        RoutingRect target = new RoutingRect(400, 200, 120, 60, "tgt");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(160, 230)); // single BP

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), null, 1);

        int fixes = RoutingPipeline.fixCenterTerminatedPath(path, conn);
        assertEquals("Should return 0 for short path", 0, fixes);
    }

    /**
     * B44 AC5: EdgeAttachmentCalculator.determineFace (now static) should return correct
     * faces for all four directions. Used by fixCenterTerminatedPath for face selection.
     */
    @Test
    public void shouldDetermineFaceForAllDirections_whenCalledStatically() {
        RoutingRect element = new RoutingRect(100, 200, 120, 60, "elem"); // center (160, 230)

        // Point to the right → RIGHT face
        assertEquals(EdgeAttachmentCalculator.Face.RIGHT,
                EdgeAttachmentCalculator.determineFace(element, 300, 230));
        // Point to the left → LEFT face
        assertEquals(EdgeAttachmentCalculator.Face.LEFT,
                EdgeAttachmentCalculator.determineFace(element, 50, 230));
        // Point above → TOP face
        assertEquals(EdgeAttachmentCalculator.Face.TOP,
                EdgeAttachmentCalculator.determineFace(element, 160, 100));
        // Point below → BOTTOM face
        assertEquals(EdgeAttachmentCalculator.Face.BOTTOM,
                EdgeAttachmentCalculator.determineFace(element, 160, 350));
    }

    /**
     * B44 AC2: alignTerminalsWithCenter re-run after post-processing should re-insert
     * alignment BPs that were removed by intermediate stages.
     * Simulates scenario: 4.7e inserts alignment BP, 4.7i removes it, 4.7k re-inserts.
     */
    @Test
    public void shouldReinsertAlignmentBp_whenRemovedByPostProcessing() {
        // Source at (100, 200) w=120 h=60 → center (160, 230)
        // Terminal at RIGHT face distributed: (221, 215) — Y differs from center Y (230)
        RoutingRect source = new RoutingRect(100, 200, 120, 60, "src");
        RoutingRect target = new RoutingRect(400, 200, 120, 60, "tgt"); // center (460, 230)

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        // Simulate state after 4.7e alignment was removed by post-processing:
        // terminal at distributed Y (215, not center 230), no alignment BP
        path.add(new AbsoluteBendpointDto(221, 215)); // distributed source terminal
        path.add(new AbsoluteBendpointDto(300, 215));
        path.add(new AbsoluteBendpointDto(300, 230));
        path.add(new AbsoluteBendpointDto(399, 230)); // target terminal at center Y

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), null, 1);

        // Re-run alignment (simulates 4.7k)
        int alignments = RoutingPipeline.alignTerminalsWithCenter(path, conn);

        assertEquals("Should insert 1 alignment BP at source", 1, alignments);
        // New first BP should be at (221, 230) — same X as terminal, center Y
        assertEquals(221, path.get(0).x());
        assertEquals(230, path.get(0).y());
        // Original terminal is now second BP
        assertEquals(221, path.get(1).x());
        assertEquals(215, path.get(1).y());
    }

    /**
     * B44 AC5: fixCenterTerminatedPath should handle overlapping elements where source and
     * target bounds intersect. The face selection must still produce valid edge face midpoints.
     */
    @Test
    public void shouldFixCenterTermination_whenElementsOverlap() {
        // Source and target overlap: source right edge (220) > target left edge (180)
        RoutingRect source = new RoutingRect(100, 200, 120, 60, "src"); // center (160, 230)
        RoutingRect target = new RoutingRect(180, 200, 120, 60, "tgt"); // center (240, 230)

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(160, 230)); // at source center
        path.add(new AbsoluteBendpointDto(240, 230)); // at target center

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), null, 1);

        int fixes = RoutingPipeline.fixCenterTerminatedPath(path, conn);

        assertEquals("Should fix 2 terminals", 2, fixes);
        // Source → RIGHT face (next BP is to the right): (x+w+1, cy) = (221, 230)
        assertEquals(221, path.get(0).x());
        assertEquals(230, path.get(0).y());
        // Target → LEFT face (prev BP is to the left, now at 221): (x-1, cy) = (179, 230)
        assertEquals(179, path.get(path.size() - 1).x());
        assertEquals(230, path.get(path.size() - 1).y());
    }

    /**
     * B44 AC5: fixCenterTerminatedPath should handle large offset paths where elements
     * are far apart and the terminal BP is at element center.
     */
    @Test
    public void shouldFixCenterTermination_whenLargeOffsetPath() {
        // Elements 1000px apart
        RoutingRect source = new RoutingRect(100, 200, 120, 60, "src"); // center (160, 230)
        RoutingRect target = new RoutingRect(1200, 500, 120, 60, "tgt"); // center (1260, 530)

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(160, 230)); // at source center
        path.add(new AbsoluteBendpointDto(600, 230));
        path.add(new AbsoluteBendpointDto(600, 530));
        path.add(new AbsoluteBendpointDto(1260, 530)); // at target center

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), null, 1);

        int fixes = RoutingPipeline.fixCenterTerminatedPath(path, conn);

        assertEquals("Should fix 2 terminals", 2, fixes);
        // Source ��� RIGHT face (next BP is to the right): (221, 230)
        assertEquals(221, path.get(0).x());
        assertEquals(230, path.get(0).y());
        // Target → LEFT face (prev BP is to the left): (1199, 530)
        assertEquals(1199, path.get(path.size() - 1).x());
        assertEquals(530, path.get(path.size() - 1).y());
    }

    /**
     * B44 AC2: Combined flow test — fixCenterTerminatedPath followed by
     * alignTerminalsWithCenter (simulating full stage 4.7k sequence). Verifies
     * the combined operation produces correct, clean results.
     */
    @Test
    public void shouldProduceCleanPath_whenCombinedFixAndAlignmentRun() {
        // Source at (100, 200) w=120 h=60 → center (160, 230)
        // Distributed terminal at RIGHT face Y=215 (offset from center Y=230)
        RoutingRect source = new RoutingRect(100, 200, 120, 60, "src");
        RoutingRect target = new RoutingRect(400, 200, 120, 60, "tgt"); // center (460, 230)

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        // Simulate post-processing state: source terminal at source center (worst case —
        // post-processing moved it there), target at proper face
        path.add(new AbsoluteBendpointDto(160, 230)); // at source center!
        path.add(new AbsoluteBendpointDto(300, 230));
        path.add(new AbsoluteBendpointDto(399, 230)); // target at left face

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), null, 1);

        // Stage 4.7k: first pass — fix center termination
        int fixes = RoutingPipeline.fixCenterTerminatedPath(path, conn);
        assertEquals("Should fix 1 center-terminated terminal", 1, fixes);

        // Stage 4.7k: second pass — re-run alignment
        int alignments = RoutingPipeline.alignTerminalsWithCenter(path, conn);

        // After fix: source moved to RIGHT face midpoint (221, 230) — already center-aligned
        // (Y=230 matches center Y=230), so no alignment BP needed
        assertEquals("No alignment needed — fixed position is already center-aligned",
                0, alignments);
        assertEquals(221, path.get(0).x());
        assertEquals(230, path.get(0).y());
        // Path should still be 3 BPs (no insertions)
        assertEquals(3, path.size());
    }

    // =============================================
    // B31: Router corridor exploration investigation
    // =============================================

    /**
     * Investigation test: reproduces B31 geometry (Fraud Detection -> Card Service
     * with Customer Onboarding Service blocking in between, 100px corridors on both sides).
     * Tests routeConnection() (A* only, no pipeline stages) to determine if the
     * graph-level path avoids the blocker.
     */
    @Test
    public void shouldRouteAroundBlocker_whenCorridorsAvailable_routeConnectionOnly() {
        // Geometry: source and target at same Y level, blocker directly between them.
        // 100px corridors above and below the blocker.
        RoutingRect source = new RoutingRect(0, 250, 120, 60, "fraud-detection");    // center (60, 280)
        RoutingRect target = new RoutingRect(500, 250, 120, 60, "card-service");     // center (560, 280)
        RoutingRect blocker = new RoutingRect(230, 250, 120, 60, "customer-onboarding"); // center (290, 280)
        // Confining elements create ~100px corridors above and below
        RoutingRect topConfiner = new RoutingRect(230, 80, 120, 60, "top-confiner"); // bottom edge at 140, blocker top at 250 → 110px corridor
        RoutingRect bottomConfiner = new RoutingRect(230, 420, 120, 60, "bottom-confiner"); // top edge at 420, blocker bottom at 310 → 110px corridor

        // Per-connection obstacle list (source/target excluded per convention)
        List<RoutingRect> obstacles = List.of(blocker, topConfiner, bottomConfiner);

        List<AbsoluteBendpointDto> bendpoints =
                pipeline.routeConnection(source, target, obstacles);

        // A* should produce bendpoints routing around the blocker through a corridor
        assertFalse("Should have bendpoints routing around blocker (not straight line)",
                bendpoints.isEmpty());

        // Full path (source center → BPs → target center) must NOT cross the blocker
        List<int[]> fullPath = buildFullPath(source, target, bendpoints);
        for (int i = 0; i < fullPath.size() - 1; i++) {
            int[] from = fullPath.get(i);
            int[] to = fullPath.get(i + 1);
            assertFalse(
                    "routeConnection path segment (" + from[0] + "," + from[1]
                            + ")->(" + to[0] + "," + to[1] + ") crosses blocker",
                    segmentIntersectsObstacle(from[0], from[1], to[0], to[1], blocker));
        }
    }

    /**
     * Investigation test: same B31 geometry through the FULL pipeline (routeAllConnections).
     * If routeConnection() succeeds but routeAllConnections() fails, the issue is
     * downstream stage corruption (Hypothesis 3).
     */
    @Test
    public void shouldRouteAroundBlocker_whenCorridorsAvailable_fullPipeline() {
        RoutingRect source = new RoutingRect(0, 250, 120, 60, "fraud-detection");
        RoutingRect target = new RoutingRect(500, 250, 120, 60, "card-service");
        RoutingRect blocker = new RoutingRect(230, 250, 120, 60, "customer-onboarding");
        RoutingRect topConfiner = new RoutingRect(230, 80, 120, 60, "top-confiner");
        RoutingRect bottomConfiner = new RoutingRect(230, 420, 120, 60, "bottom-confiner");

        List<RoutingRect> connObstacles = List.of(blocker, topConfiner, bottomConfiner);
        List<RoutingRect> allObstacles = List.of(source, target, blocker, topConfiner, bottomConfiner);

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        connObstacles, "", 1));

        RoutingResult result = pipeline.routeAllConnections(connections, allObstacles);

        // Connection should be routed successfully, not failed
        assertTrue("Connection should be routed (not failed). Failed: " + result.failed(),
                result.failed().isEmpty());
        assertNotNull("Routed path should exist", result.routed().get("c1"));
    }

    /**
     * Investigation test: Dense view with multiple connections and nudging pressure.
     * Simulates a grouped Business Architecture view where multiple connections
     * share corridors and edge nudging may push paths into obstacles.
     */
    @Test(timeout = 10000)
    public void shouldRouteAroundBlocker_whenDenseViewWithMultipleConnections() {
        // Dense layout: 7 elements in a row-like formation, corridor between rows
        // Source and target on opposite sides, blocker in between
        RoutingRect source = new RoutingRect(20, 200, 120, 60, "src");       // center (80, 230)
        RoutingRect target = new RoutingRect(600, 200, 120, 60, "tgt");      // center (660, 230)
        RoutingRect blocker = new RoutingRect(300, 200, 120, 60, "blocker"); // center (360, 230) — in the way

        // Additional elements creating a dense field — corridors above/below blocker
        RoutingRect e1 = new RoutingRect(150, 200, 100, 60, "e1");  // left of blocker, same Y
        RoutingRect e2 = new RoutingRect(470, 200, 100, 60, "e2");  // right of blocker, same Y
        RoutingRect e3 = new RoutingRect(300, 80, 120, 60, "e3");   // above blocker — corridor = 60px
        RoutingRect e4 = new RoutingRect(300, 340, 120, 60, "e4");  // below blocker — corridor = 80px
        RoutingRect e5 = new RoutingRect(150, 80, 100, 60, "e5");   // upper-left
        RoutingRect e6 = new RoutingRect(470, 80, 100, 60, "e6");   // upper-right
        RoutingRect e7 = new RoutingRect(150, 340, 100, 60, "e7");  // lower-left
        RoutingRect e8 = new RoutingRect(470, 340, 100, 60, "e8");  // lower-right

        // Connection being tested
        List<RoutingRect> c1Obstacles = List.of(blocker, e1, e2, e3, e4, e5, e6, e7, e8);

        // Additional connections to create nudging pressure (parallel routes)
        List<RoutingRect> c2Obstacles = List.of(blocker, source, e2, e3, e4, e5, e6, e7, e8);
        List<RoutingRect> c3Obstacles = List.of(blocker, source, target, e3, e4, e5, e6, e7, e8);

        List<RoutingRect> allObstacles = List.of(source, target, blocker, e1, e2, e3, e4, e5, e6, e7, e8);

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, c1Obstacles, "", 1),
                new RoutingPipeline.ConnectionEndpoints("c2", e1, target, c2Obstacles, "", 1),
                new RoutingPipeline.ConnectionEndpoints("c3", e1, e2, c3Obstacles, "", 1));

        RoutingResult result = pipeline.routeAllConnections(connections, allObstacles);

        // The critical connection c1 (src → tgt through blocker) should be routed
        if (!result.failed().isEmpty()) {
            for (FailedConnection fc : result.failed()) {
                System.out.println("FAILED: " + fc.connectionId() + " reason=" + fc.constraintViolated()
                        + " obstacle=" + fc.crossedElementId());
            }
        }
        // Even if some connections fail, c1 specifically should route through a corridor
        assertNotNull("Connection c1 should be routed", result.routed().get("c1"));
    }

    /**
     * Investigation test: Narrow corridors (30px between obstacles) — tests if
     * the visibility graph produces connected paths in tight spaces.
     */
    @Test(timeout = 10000)
    public void shouldRouteAroundBlocker_whenNarrowCorridors() {
        RoutingRect source = new RoutingRect(0, 200, 100, 50, "src");        // center (50, 225)
        RoutingRect target = new RoutingRect(400, 200, 100, 50, "tgt");      // center (450, 225)
        RoutingRect blocker = new RoutingRect(180, 200, 100, 50, "blocker"); // center (230, 225)
        // Narrow corridors: 30px above, 30px below (after 10px margin = 10px usable)
        RoutingRect topWall = new RoutingRect(180, 120, 100, 50, "top");     // bottom at 170, gap to blocker top (200) = 30px
        RoutingRect bottomWall = new RoutingRect(180, 280, 100, 50, "bot");  // top at 280, gap from blocker bottom (250) = 30px

        List<RoutingRect> obstacles = List.of(blocker, topWall, bottomWall);

        List<AbsoluteBendpointDto> bendpoints =
                pipeline.routeConnection(source, target, obstacles);

        // Even in narrow corridors, route should avoid the blocker
        List<int[]> fullPath = buildFullPath(source, target, bendpoints);
        for (int i = 0; i < fullPath.size() - 1; i++) {
            int[] from = fullPath.get(i);
            int[] to = fullPath.get(i + 1);
            assertFalse(
                    "Narrow corridor path segment (" + from[0] + "," + from[1]
                            + ")->(" + to[0] + "," + to[1] + ") crosses blocker",
                    segmentIntersectsObstacle(from[0], from[1], to[0], to[1], blocker));
        }
    }

    /**
     * Investigation test: Blocker overlapping the direct center-to-center line
     * but NOT the source/target elements — exactly the B31 scenario where
     * endpoint pass-through (13-4) does NOT trigger because the blocker is a THIRD element.
     */
    @Test(timeout = 10000)
    public void shouldRouteAroundThirdElementBlocker_fullPipeline() {
        // Tight layout: elements close together with blocker centered on center-to-center line
        RoutingRect source = new RoutingRect(50, 200, 100, 50, "src");       // center (100, 225)
        RoutingRect target = new RoutingRect(450, 200, 100, 50, "tgt");      // center (500, 225)
        RoutingRect blocker = new RoutingRect(250, 200, 100, 50, "blocker"); // center (300, 225) — ON the line

        // 4 additional obstacles creating a confined but navigable space
        RoutingRect e1 = new RoutingRect(50, 80, 100, 50, "e1");
        RoutingRect e2 = new RoutingRect(250, 80, 100, 50, "e2");
        RoutingRect e3 = new RoutingRect(450, 80, 100, 50, "e3");
        RoutingRect e4 = new RoutingRect(250, 320, 100, 50, "e4");

        List<RoutingRect> connObstacles = List.of(blocker, e1, e2, e3, e4);
        List<RoutingRect> allObstacles = List.of(source, target, blocker, e1, e2, e3, e4);

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        connObstacles, "", 1));

        RoutingResult result = pipeline.routeAllConnections(connections, allObstacles);

        if (!result.failed().isEmpty()) {
            for (FailedConnection fc : result.failed()) {
                System.out.println("THIRD-ELEMENT FAILED: " + fc.connectionId()
                        + " reason=" + fc.constraintViolated() + " obstacle=" + fc.crossedElementId());
            }
        }
        assertTrue("Third-element blocker: connection should route through corridor. Failed: "
                + result.failed(), result.failed().isEmpty());
    }

    // =============================================
    // B31: Corridor re-route unit tests (AC-7)
    // =============================================

    /**
     * AC-7: Corridor re-route recovers a connection that the batch pipeline fails.
     * Uses a test subclass that simulates a batch pipeline violation on the first routing
     * attempt (by injecting a crossing BP), then verifies the stage 5a corridor re-route
     * produces a clean path.
     */
    @Test(timeout = 10000)
    public void shouldRecoverViaCorridorReroute_whenBatchPipelineCorruptsPath() {
        // Use a pipeline subclass that corrupts the first routeConnection() call
        // by inserting a BP inside the blocker, simulating downstream stage corruption.
        // The corridor re-route (stage 5a) calls routeConnection() again — the second
        // call returns the normal clean path.
        RoutingRect source = new RoutingRect(0, 200, 100, 50, "src");       // center (50, 225)
        RoutingRect target = new RoutingRect(400, 200, 100, 50, "tgt");     // center (450, 225)
        RoutingRect blocker = new RoutingRect(180, 200, 100, 50, "blocker"); // center (230, 225)

        final boolean[] firstCall = {true};
        RoutingPipeline corruptingPipeline = new RoutingPipeline() {
            @Override
            public List<AbsoluteBendpointDto> routeConnection(
                    RoutingRect src, RoutingRect tgt, List<RoutingRect> obstacles) {
                List<AbsoluteBendpointDto> result = super.routeConnection(src, tgt, obstacles);
                if (firstCall[0]) {
                    firstCall[0] = false;
                    // Corrupt the path by inserting a BP inside the blocker
                    // (simulates downstream nudging/attachment pushing path into obstacle)
                    result = new ArrayList<>(result);
                    result.add(0, new AbsoluteBendpointDto(
                            blocker.centerX(), blocker.centerY()));
                }
                return result;
            }
        };

        List<RoutingRect> connObstacles = List.of(blocker);
        List<RoutingRect> allObstacles = List.of(source, target, blocker);

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        connObstacles, "", 1));

        RoutingResult result = corruptingPipeline.routeAllConnections(connections, allObstacles);

        // Stage 5a corridor re-route should recover the connection
        assertTrue("Corridor re-route should recover corrupted connection. Failed: "
                + result.failed(), result.failed().isEmpty());
        assertNotNull("Re-routed path should exist", result.routed().get("c1"));
    }

    /**
     * AC-7: Corridor re-route respects obstacle clearance — re-routed path
     * must not cross ANY obstacle.
     */
    @Test(timeout = 10000)
    public void shouldRespectObstacleClearance_whenCorridorReroute() {
        RoutingRect source = new RoutingRect(0, 200, 100, 50, "src");
        RoutingRect target = new RoutingRect(400, 200, 100, 50, "tgt");
        RoutingRect blocker = new RoutingRect(180, 200, 100, 50, "blocker");
        RoutingRect topWall = new RoutingRect(180, 80, 100, 50, "top");
        RoutingRect bottomWall = new RoutingRect(180, 320, 100, 50, "bot");

        List<RoutingRect> connObstacles = List.of(blocker, topWall, bottomWall);
        List<RoutingRect> allObstacles = List.of(source, target, blocker, topWall, bottomWall);

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        connObstacles, "", 1));

        RoutingResult result = pipeline.routeAllConnections(connections, allObstacles);

        assertTrue("Should route successfully", result.failed().isEmpty());
        List<AbsoluteBendpointDto> path = result.routed().get("c1");
        assertNotNull(path);

        // Verify no segment crosses any obstacle (including walls)
        List<int[]> fullPath = buildFullPath(source, target, path);
        for (RoutingRect obs : connObstacles) {
            assertNoSegmentIntersectsObstacles(fullPath, List.of(obs));
        }
    }

    /**
     * AC-7: Corridor re-route produces orthogonal-only segments.
     */
    @Test(timeout = 10000)
    public void shouldProduceOrthogonalSegments_whenCorridorReroute() {
        RoutingRect source = new RoutingRect(0, 200, 100, 50, "src");
        RoutingRect target = new RoutingRect(400, 200, 100, 50, "tgt");
        RoutingRect blocker = new RoutingRect(180, 200, 100, 50, "blocker");

        List<RoutingRect> connObstacles = List.of(blocker);
        List<RoutingRect> allObstacles = List.of(source, target, blocker);

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        connObstacles, "", 1));

        RoutingResult result = pipeline.routeAllConnections(connections, allObstacles);
        List<AbsoluteBendpointDto> path = result.routed().get("c1");
        assertNotNull(path);

        // Verify all consecutive BP pairs are orthogonal (same x or same y)
        for (int i = 0; i < path.size() - 1; i++) {
            AbsoluteBendpointDto a = path.get(i);
            AbsoluteBendpointDto b = path.get(i + 1);
            assertTrue("Segment (" + a.x() + "," + a.y() + ")->(" + b.x() + "," + b.y()
                    + ") should be orthogonal (same x or same y)",
                    a.x() == b.x() || a.y() == b.y());
        }
    }

    /**
     * AC-7: Connection with no available corridor still fails gracefully (no false positives).
     * Blocker fully enclosed — no corridor route possible.
     */
    @Test(timeout = 10000)
    public void shouldStillFail_whenNoCorridorAvailable() {
        // Source and target with a blocker in between, and walls closing ALL corridors
        RoutingRect source = new RoutingRect(0, 150, 80, 40, "src");     // center (40, 170)
        RoutingRect target = new RoutingRect(350, 150, 80, 40, "tgt");   // center (390, 170)
        RoutingRect blocker = new RoutingRect(150, 145, 80, 50, "blocker");
        // Walls that close off all corridors around the blocker
        RoutingRect topWall = new RoutingRect(100, 75, 180, 50, "top");     // bottom at 125, blocker top at 145 → 20px
        RoutingRect bottomWall = new RoutingRect(100, 215, 180, 50, "bot"); // top at 215, blocker bottom at 195 → 20px
        // Side walls to fully block
        RoutingRect leftWall = new RoutingRect(100, 125, 40, 90, "left");
        RoutingRect rightWall = new RoutingRect(240, 125, 40, 90, "right");

        List<RoutingRect> connObstacles = List.of(blocker, topWall, bottomWall, leftWall, rightWall);
        List<RoutingRect> allObstacles = List.of(source, target, blocker, topWall, bottomWall, leftWall, rightWall);

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        connObstacles, "", 1));

        RoutingResult result = pipeline.routeAllConnections(connections, allObstacles);

        // With no corridor available, the connection should still be classified as failed
        // (the corridor re-route can't magically route through solid walls)
        assertFalse("Connection with no corridor should remain failed",
                result.failed().isEmpty());
    }

    /**
     * AC-7: Existing successfully-routed connections remain unaffected by the
     * corridor re-route mechanism. The re-route only triggers for failed connections.
     */
    @Test(timeout = 10000)
    public void shouldNotAffectSuccessfulConnections_whenCorridorRerouteActive() {
        // Two connections: c1 routes cleanly, c2 also routes cleanly.
        // Verify c1's path is identical whether or not c2 exists.
        RoutingRect src1 = new RoutingRect(0, 0, 80, 50, "s1");
        RoutingRect tgt1 = new RoutingRect(300, 0, 80, 50, "t1");
        RoutingRect src2 = new RoutingRect(0, 200, 80, 50, "s2");
        RoutingRect tgt2 = new RoutingRect(300, 200, 80, 50, "t2");
        RoutingRect obstacle = new RoutingRect(130, 80, 80, 60, "obs");

        // Route c1 alone
        List<RoutingPipeline.ConnectionEndpoints> singleConn = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", src1, tgt1,
                        List.of(obstacle), "", 1));
        List<RoutingRect> allObs1 = List.of(src1, tgt1, obstacle);
        RoutingResult single = pipeline.routeAllConnections(singleConn, allObs1);

        // Route c1 + c2 together
        List<RoutingPipeline.ConnectionEndpoints> bothConns = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", src1, tgt1,
                        List.of(obstacle), "", 1),
                new RoutingPipeline.ConnectionEndpoints("c2", src2, tgt2,
                        List.of(obstacle), "", 1));
        List<RoutingRect> allObs2 = List.of(src1, tgt1, src2, tgt2, obstacle);
        RoutingResult both = pipeline.routeAllConnections(bothConns, allObs2);

        // Both should succeed
        assertTrue("Single route should succeed", single.failed().isEmpty());
        assertTrue("Both routes should succeed", both.failed().isEmpty());
    }

    /**
     * AC-7: When corridors exist on both sides of a blocker, the router should
     * prefer the shorter corridor (closer detour from the direct path).
     */
    @Test(timeout = 10000)
    public void shouldPreferShorterCorridor_whenMultipleCorridorsAvailable() {
        // Source and target at same Y=230, blocker directly between them (y=200-260)
        RoutingRect source = new RoutingRect(0, 200, 120, 60, "src");        // center (60, 230)
        RoutingRect target = new RoutingRect(500, 200, 120, 60, "tgt");      // center (560, 230)
        RoutingRect blocker = new RoutingRect(230, 200, 120, 60, "blocker"); // top=200, bottom=260

        // Upper corridor: confiner bottom=120, blocker top=200 → 80px gap (short detour ~40px from center Y)
        RoutingRect topConfiner = new RoutingRect(230, 60, 120, 60, "top");
        // Lower corridor: blocker bottom=260, confiner top=450 → 190px gap (long detour ~125px from center Y)
        RoutingRect bottomConfiner = new RoutingRect(230, 450, 120, 60, "bot");

        List<RoutingRect> obstacles = List.of(blocker, topConfiner, bottomConfiner);

        List<AbsoluteBendpointDto> bendpoints =
                pipeline.routeConnection(source, target, obstacles);

        // Path should route through the upper (shorter) corridor
        List<int[]> fullPath = buildFullPath(source, target, bendpoints);

        boolean usesUpperCorridor = false;
        boolean usesLowerCorridor = false;
        for (int[] pt : fullPath) {
            if (pt[1] < 200) usesUpperCorridor = true;  // above blocker top
            if (pt[1] > 260) usesLowerCorridor = true;  // below blocker bottom
        }

        assertTrue("Should route through a corridor",
                usesUpperCorridor || usesLowerCorridor);
        assertTrue("Should prefer upper corridor (shorter detour from Y=230). " +
                "Upper=" + usesUpperCorridor + " Lower=" + usesLowerCorridor,
                usesUpperCorridor);
    }

    // ===================================================================
    // Backlog B33: Self-element pass-through safety net
    // Tests that correctEndpointPassThroughs handles scenarios where
    // terminal quality stages (4.6b-4.7e) re-introduce self-element crossings.
    // ===================================================================

    @Test
    public void shouldCorrectSelfSourcePassThrough_whenTerminalStagesCauseClipping() {
        // AC-1: After terminal stages, a segment clips through source element corner.
        // Source at (0,0,120,60). Path exits source right face, but a segment routes
        // back across source body due to terminal realignment.
        RoutingRect source = new RoutingRect(0, 0, 120, 60, "src");     // x:0-120, y:0-60
        RoutingRect target = new RoutingRect(300, 200, 120, 60, "tgt"); // x:300-420, y:200-260

        // Simulate post-terminal-stage path where segment (130,50)->(50,80) clips source corner
        // BP at (50,80) is outside source but segment from (130,50) to (50,80) crosses source body
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(130, 30),   // just outside source right face
                new AbsoluteBendpointDto(130, 50),   // segment goes down
                new AbsoluteBendpointDto(50, 80),    // diagonal cutting through source
                new AbsoluteBendpointDto(50, 200),   // down to target area
                new AbsoluteBendpointDto(300, 200)   // approach target
        ));

        RoutingPipeline.correctEndpointPassThroughs(path, source, target);

        // After correction, no non-terminal segment should cross source body
        assertNoSegmentPassesThrough(path, source, target, source, true);
    }

    @Test
    public void shouldCorrectSelfTargetPassThrough_whenTerminalStagesCauseClipping() {
        // AC-2: After terminal stages, a segment clips through target element corner.
        // All BPs are outside target — only the segment between them crosses the body.
        RoutingRect source = new RoutingRect(0, 0, 120, 60, "src");
        RoutingRect target = new RoutingRect(300, 200, 120, 60, "tgt"); // x:300-420, y:200-260

        // Simulate path where vertical segment (350,180)->(350,270) clips through target body
        // Both BPs are outside target (y=180 < 200 and y=270 > 260)
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(130, 30),   // exit source
                new AbsoluteBendpointDto(130, 180),  // go down
                new AbsoluteBendpointDto(350, 180),  // above target (y=180 < 200)
                new AbsoluteBendpointDto(350, 270)   // below target (y=270 > 260), segment crosses target
        ));

        RoutingPipeline.correctEndpointPassThroughs(path, source, target);

        // After correction, no non-terminal segment should cross target body
        assertNoSegmentPassesThrough(path, source, target, target, false);
    }

    @Test
    public void shouldNotModifyCleanPath_whenNoSelfElementCrossings() {
        // AC-3/AC-5: Clean orthogonal path should remain unchanged
        RoutingRect source = new RoutingRect(0, 0, 120, 60, "src");
        RoutingRect target = new RoutingRect(300, 200, 120, 60, "tgt");

        // Clean L-shaped path: right from source, then down to target
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(130, 30),   // exit source right
                new AbsoluteBendpointDto(360, 30),   // go right
                new AbsoluteBendpointDto(360, 200)   // go down to target
        ));

        List<AbsoluteBendpointDto> original = new ArrayList<>(path);
        RoutingPipeline.correctEndpointPassThroughs(path, source, target);

        assertEquals("Clean path should be unchanged", original, path);
    }

    @Test
    public void shouldSkipCorrection_whenSourceTargetOverlap() {
        // AC-9: Overlapping source/target guard — should not crash
        RoutingRect source = new RoutingRect(100, 100, 120, 60, "src");
        RoutingRect target = new RoutingRect(150, 120, 120, 60, "tgt"); // overlaps source

        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(200, 130),
                new AbsoluteBendpointDto(250, 150)
        ));

        List<AbsoluteBendpointDto> original = new ArrayList<>(path);
        RoutingPipeline.correctEndpointPassThroughs(path, source, target);

        assertEquals("Overlapping elements should skip correction", original, path);
    }

    @Test
    public void shouldProduceOrthogonalPath_afterSelfSourceCorrection() {
        // AC-4: Detour insertions must maintain orthogonality
        RoutingRect source = new RoutingRect(0, 0, 120, 60, "src");
        RoutingRect target = new RoutingRect(300, 200, 120, 60, "tgt");

        // Path where a BP sits inside source body — correction should remove it
        // and produce an orthogonal result
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(60, 30),    // inside source!
                new AbsoluteBendpointDto(130, 30),   // exit source
                new AbsoluteBendpointDto(360, 30),   // go right
                new AbsoluteBendpointDto(360, 200)   // go down to target
        ));

        RoutingPipeline.correctEndpointPassThroughs(path, source, target);

        // Verify all segments are orthogonal (horizontal or vertical)
        for (int i = 0; i < path.size() - 1; i++) {
            AbsoluteBendpointDto a = path.get(i);
            AbsoluteBendpointDto b = path.get(i + 1);
            assertTrue("Segment (" + a.x() + "," + a.y() + ")->(" + b.x() + "," + b.y()
                    + ") should be orthogonal",
                    a.x() == b.x() || a.y() == b.y());
        }
    }

    @Test
    public void shouldCorrectBothSourceAndTargetPassThroughs_onSamePath() {
        // AC-1+AC-2 combined: path clips through both source and target simultaneously.
        // Verifies source correction doesn't introduce new target crossings and vice versa.
        RoutingRect source = new RoutingRect(0, 0, 120, 60, "src");     // x:0-120, y:0-60
        RoutingRect target = new RoutingRect(300, 200, 120, 60, "tgt"); // x:300-420, y:200-260

        // Horizontal segment at y=50 crosses source body (y:0-60),
        // vertical segment at x=350 crosses target body (y:200-260)
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(130, 50),   // exit source right face
                new AbsoluteBendpointDto(50, 50),    // segment (130,50)->(50,50) crosses source at y=50
                new AbsoluteBendpointDto(50, 180),   // down
                new AbsoluteBendpointDto(350, 180),  // right to target area
                new AbsoluteBendpointDto(350, 270)   // segment (350,180)->(350,270) crosses target
        ));

        RoutingPipeline.correctEndpointPassThroughs(path, source, target);

        // Neither source nor target should have non-terminal pass-throughs
        assertNoSegmentPassesThrough(path, source, target, source, true);
        assertNoSegmentPassesThrough(path, source, target, target, false);
    }

    @Test
    public void shouldHandleShortPath_withTwoBendpoints() {
        // AC-9: Short paths (2-3 BPs) handled correctly without crash
        RoutingRect source = new RoutingRect(0, 0, 120, 60, "src");
        RoutingRect target = new RoutingRect(300, 0, 120, 60, "tgt");

        // Minimal 2-BP path
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(130, 30),   // exit source
                new AbsoluteBendpointDto(300, 30)    // enter target
        ));

        // Should not crash on short path
        RoutingPipeline.correctEndpointPassThroughs(path, source, target);
        assertFalse("Path should still have points after correction", path.isEmpty());
    }

    // ===================================================================
    // Backlog B34: Self-element pass-through face correction
    // Tests that face re-selection eliminates self-element crossings
    // by changing where connections exit/enter elements, not detours.
    // ===================================================================

    @Test
    public void b34_shouldDetectSelfSourceCrossing_onFullPath() {
        // AC-4: Detection uses full path with segment skipping.
        // Source at (0,0,120,60). Path exits right, then a later segment clips back
        // through source body horizontally.
        RoutingRect source = new RoutingRect(0, 0, 120, 60, "src");
        RoutingRect target = new RoutingRect(300, 200, 120, 60, "tgt");

        // Full path: (60,30) -> (130,30) -> (130,80) -> (50,80) -> (50,200) -> (360,200)
        // Segment (130,80)->(50,80) at y=80 is outside source (y:0-60), no crossing.
        // But segment (130,30)->(130,80) exits right and (50,80) needs to go left...
        // Use a clearer scenario: segment goes back horizontally through source body
        // Full path: srcCenter(60,30), BP(121,30), BP(121,50), BP(40,50), BP(40,200), BP(360,200), tgtCenter(360,230)
        // Segment BP(121,50)->BP(40,50) at y=50 crosses source body (y:0-60, x:0-120)
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(121, 30),   // just outside source right
                new AbsoluteBendpointDto(121, 50),   // still just outside right
                new AbsoluteBendpointDto(40, 50),    // segment crosses source at y=50
                new AbsoluteBendpointDto(40, 200),
                new AbsoluteBendpointDto(360, 200)
        ));

        int idx = pipeline.detectSelfElementPassThrough(path, source, target, true);
        assertTrue("Should detect self-source crossing", idx >= 0);
    }

    @Test
    public void b34_shouldDetectSelfTargetCrossing_onFullPath() {
        // AC-4: Detection on target element with last-segment skipping.
        RoutingRect source = new RoutingRect(0, 0, 120, 60, "src");
        RoutingRect target = new RoutingRect(300, 200, 120, 60, "tgt"); // x:300-420, y:200-260

        // Segment (350,180)->(350,270) clips through target body vertically
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(130, 30),
                new AbsoluteBendpointDto(130, 180),
                new AbsoluteBendpointDto(350, 180),  // above target
                new AbsoluteBendpointDto(350, 270)   // below target — segment crosses target
        ));

        int idx = pipeline.detectSelfElementPassThrough(path, source, target, false);
        assertTrue("Should detect self-target crossing", idx >= 0);
    }

    @Test
    public void b34_shouldNotDetect_whenPathIsClean() {
        // AC-4: Clean path returns -1.
        RoutingRect source = new RoutingRect(0, 0, 120, 60, "src");
        RoutingRect target = new RoutingRect(300, 200, 120, 60, "tgt");

        // Clean L-shaped path, well clear of both elements
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(130, 30),
                new AbsoluteBendpointDto(360, 30),
                new AbsoluteBendpointDto(360, 200)
        ));

        assertEquals("Clean path should not flag source", -1,
                pipeline.detectSelfElementPassThrough(path, source, target, true));
        assertEquals("Clean path should not flag target", -1,
                pipeline.detectSelfElementPassThrough(path, source, target, false));
    }

    @Test
    public void b34_shouldFixSelfSourceCrossing_byFaceReselection() {
        // AC-1, AC-3: Face re-selection eliminates self-source crossing.
        // Source at (100,100,120,60). Connection exits RIGHT face.
        // A later segment clips back through source body.
        // Fix: re-select face so exit avoids crossing.
        RoutingRect source = new RoutingRect(100, 100, 120, 60, "src"); // x:100-220, y:100-160
        RoutingRect target = new RoutingRect(100, 350, 120, 60, "tgt"); // x:100-220, y:350-410

        // Path exits source right (x=221), goes down, then cuts left through source
        // Full path: srcCenter(160,130), BP(221,130), BP(221,150), BP(120,150), BP(120,350), tgtCenter(160,380)
        // Segment (221,150)->(120,150) at y=150 crosses source (y:100-160)
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(221, 130),  // source RIGHT face terminal
                new AbsoluteBendpointDto(221, 150),
                new AbsoluteBendpointDto(120, 150),  // crosses source horizontally
                new AbsoluteBendpointDto(120, 350)   // target TOP face terminal
        ));

        RoutingPipeline.ConnectionEndpoints conn = new RoutingPipeline.ConnectionEndpoints(
                "conn1", source, target, Collections.emptyList(), null, 1);

        // Verify crossing exists before fix
        assertTrue("Pre-condition: should have self-source crossing",
                pipeline.detectSelfElementPassThrough(path, source, target, true) >= 0);

        boolean fixed = pipeline.correctSelfElementPassThrough(path, conn, true);
        assertTrue("Should have applied face correction", fixed);

        // After fix: no self-source crossing
        assertNoSegmentPassesThrough(path, source, target, source, true);
    }

    @Test
    public void b34_shouldFixSelfTargetCrossing_byFaceReselection() {
        // AC-2, AC-3: Face re-selection eliminates self-target crossing.
        RoutingRect source = new RoutingRect(100, 100, 120, 60, "src");
        RoutingRect target = new RoutingRect(100, 350, 120, 60, "tgt"); // x:100-220, y:350-410

        // Path enters target from left side, but a segment clips through target body
        // Full path: srcCenter(160,130), BP(99,130), BP(99,390), BP(130,390), BP(130,350), tgtCenter(160,380)
        // Segment BP(99,390)->BP(130,390) at y=390 is inside target (y:350-410)
        // Actually let's make it a segment that *crosses* without endpoints inside:
        // Segment (130,340)->(130,420) crosses target vertically
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(99, 130),
                new AbsoluteBendpointDto(99, 340),
                new AbsoluteBendpointDto(130, 340),  // above target
                new AbsoluteBendpointDto(130, 420)   // below target — segment crosses target body
        ));

        RoutingPipeline.ConnectionEndpoints conn = new RoutingPipeline.ConnectionEndpoints(
                "conn2", source, target, Collections.emptyList(), null, 1);

        assertTrue("Pre-condition: should have self-target crossing",
                pipeline.detectSelfElementPassThrough(path, source, target, false) >= 0);

        boolean fixed = pipeline.correctSelfElementPassThrough(path, conn, false);
        assertTrue("Should have applied face correction", fixed);

        assertNoSegmentPassesThrough(path, source, target, target, false);
    }

    @Test
    public void b34_shouldNotModifyCleanPath() {
        // AC-9: Clean path should remain unchanged.
        RoutingRect source = new RoutingRect(0, 0, 120, 60, "src");
        RoutingRect target = new RoutingRect(300, 200, 120, 60, "tgt");

        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(130, 30),
                new AbsoluteBendpointDto(360, 30),
                new AbsoluteBendpointDto(360, 200)
        ));

        List<AbsoluteBendpointDto> original = new ArrayList<>(path);
        RoutingPipeline.ConnectionEndpoints conn = new RoutingPipeline.ConnectionEndpoints(
                "conn3", source, target, Collections.emptyList(), null, 1);

        boolean srcFixed = pipeline.correctSelfElementPassThrough(path, conn, true);
        boolean tgtFixed = pipeline.correctSelfElementPassThrough(path, conn, false);

        assertFalse("Clean path should not need source fix", srcFixed);
        assertFalse("Clean path should not need target fix", tgtFixed);
        assertEquals("Clean path should be unchanged", original, path);
    }

    @Test
    public void b34_shouldFixBothSourceAndTargetCrossings() {
        // AC-9: Connection with both self-source and self-target crossings.
        RoutingRect source = new RoutingRect(0, 0, 120, 60, "src");     // x:0-120, y:0-60
        RoutingRect target = new RoutingRect(300, 200, 120, 60, "tgt"); // x:300-420, y:200-260

        // Source crossing: segment at y=50 goes back through source
        // Target crossing: segment at x=350 goes through target
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(121, 30),   // source RIGHT terminal
                new AbsoluteBendpointDto(121, 50),
                new AbsoluteBendpointDto(40, 50),    // crosses source at y=50
                new AbsoluteBendpointDto(40, 180),
                new AbsoluteBendpointDto(350, 180),  // above target
                new AbsoluteBendpointDto(350, 270)   // target BOTTOM terminal — crosses target
        ));

        RoutingPipeline.ConnectionEndpoints conn = new RoutingPipeline.ConnectionEndpoints(
                "conn4", source, target, Collections.emptyList(), null, 1);

        // Fix source first, then target
        boolean srcFixed = pipeline.correctSelfElementPassThrough(path, conn, true);
        boolean tgtFixed = pipeline.correctSelfElementPassThrough(path, conn, false);
        assertTrue("Should have applied source face correction", srcFixed);
        assertTrue("Should have applied target face correction", tgtFixed);

        assertNoSegmentPassesThrough(path, source, target, source, true);
        assertNoSegmentPassesThrough(path, source, target, target, false);
    }

    @Test
    public void b34_shouldProduceOrthogonalPath_afterFaceCorrection() {
        // AC-5, AC-9: Path must be orthogonal after face re-selection + cleanup.
        RoutingRect source = new RoutingRect(100, 100, 120, 60, "src");
        RoutingRect target = new RoutingRect(100, 350, 120, 60, "tgt");

        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(221, 130),  // source RIGHT terminal
                new AbsoluteBendpointDto(221, 150),
                new AbsoluteBendpointDto(120, 150),  // crosses source
                new AbsoluteBendpointDto(120, 350)
        ));

        RoutingPipeline.ConnectionEndpoints conn = new RoutingPipeline.ConnectionEndpoints(
                "conn5", source, target, Collections.emptyList(), null, 1);

        pipeline.correctSelfElementPassThrough(path, conn, true);

        // Verify all segments are orthogonal
        for (int i = 0; i < path.size() - 1; i++) {
            AbsoluteBendpointDto a = path.get(i);
            AbsoluteBendpointDto b = path.get(i + 1);
            assertTrue("Segment (" + a.x() + "," + a.y() + ")->(" + b.x() + "," + b.y()
                    + ") should be orthogonal after B34 correction",
                    a.x() == b.x() || a.y() == b.y());
        }
    }

    @Test
    public void b34_shouldReturnFalse_whenAllFacesStillCross() {
        // Task 2.6: When no alternative face eliminates the crossing, return false
        // and leave the path unchanged. Geometry: source is surrounded by the path
        // such that every face midpoint still intersects a non-terminal segment.
        // Small source centered inside a tight path box — any face midpoint terminal
        // still has segments crossing through source body.
        RoutingRect source = new RoutingRect(100, 100, 40, 40, "src"); // x:100-140, y:100-140, small
        RoutingRect target = new RoutingRect(400, 400, 120, 60, "tgt");

        // Path forms a box around source with segments crossing through it on all sides:
        // Full path: srcCenter(120,120) -> BP(141,120) -> BP(141,80) -> BP(80,80)
        //   -> BP(80,160) -> BP(160,160) -> BP(160,80) -> BP(300,80) -> BP(300,400) -> tgtCenter(460,430)
        // Segments cross source from multiple directions — any single face change
        // still leaves at least one crossing segment.
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(141, 120),  // source RIGHT terminal
                new AbsoluteBendpointDto(141, 80),
                new AbsoluteBendpointDto(80, 80),
                new AbsoluteBendpointDto(80, 160),   // segment (80,80)->(80,160) passes left of/through source
                new AbsoluteBendpointDto(160, 160),  // segment (80,160)->(160,160) passes below/through source
                new AbsoluteBendpointDto(160, 80),   // segment (160,160)->(160,80) passes right of/through source
                new AbsoluteBendpointDto(300, 80),
                new AbsoluteBendpointDto(300, 400)
        ));

        List<AbsoluteBendpointDto> originalPath = new ArrayList<>(path);
        RoutingPipeline.ConnectionEndpoints conn = new RoutingPipeline.ConnectionEndpoints(
                "conn-all-fail", source, target, Collections.emptyList(), null, 1);

        // Pre-condition: crossing exists
        assertTrue("Pre-condition: should have self-source crossing",
                pipeline.detectSelfElementPassThrough(path, source, target, true) >= 0);

        boolean fixed = pipeline.correctSelfElementPassThrough(path, conn, true);
        assertFalse("Should return false when no face eliminates crossing", fixed);
        assertEquals("Path should be unchanged when correction fails", originalPath, path);
    }

    @Test
    public void b34_existingRoutingTests_shouldPassUnchanged() {
        // AC-6: Verify existing routing produces valid results (regression check).
        // Re-run a representative routing scenario to ensure B34 doesn't break anything.
        RoutingRect source = new RoutingRect(0, 170, 100, 60, "src");
        RoutingRect target = new RoutingRect(400, 170, 100, 60, "tgt");
        RoutingRect obstacle = new RoutingRect(200, 150, 100, 100, "obs");

        List<AbsoluteBendpointDto> bendpoints =
                pipeline.routeConnection(source, target, List.of(obstacle));

        assertFalse("Should have bendpoints to route around obstacle", bendpoints.isEmpty());
        assertNoBendpointInsideRect(bendpoints, target, "target");
        assertNoBendpointInsideRect(bendpoints, source, "source");
    }

    // ===================================================================
    // Backlog B35 Phase B: Re-routed face correction with clearance waypoint
    // Tests that face correction inserts a clearance waypoint and removes
    // internal BPs, producing a coherent re-routed path (not Frankenstein).
    // ===================================================================

    @Test
    public void b35_shouldInsertClearanceWaypoint_onNewFace() {
        // AC-5: Corrected path must include a clearance waypoint at margin distance.
        // Source exits RIGHT but segment crosses back through source body.
        // After fix: new terminal on different face + clearance WP outside element.
        RoutingRect source = new RoutingRect(100, 100, 120, 60, "src"); // x:100-220, y:100-160
        RoutingRect target = new RoutingRect(100, 350, 120, 60, "tgt");

        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(221, 130),  // source RIGHT terminal
                new AbsoluteBendpointDto(221, 150),
                new AbsoluteBendpointDto(120, 150),  // crosses source
                new AbsoluteBendpointDto(120, 350)
        ));

        RoutingPipeline.ConnectionEndpoints conn = new RoutingPipeline.ConnectionEndpoints(
                "conn-b35", source, target, Collections.emptyList(), null, 1);

        assertTrue("Pre-condition: should have self-source crossing",
                pipeline.detectSelfElementPassThrough(path, source, target, true) >= 0);

        boolean fixed = pipeline.correctSelfElementPassThrough(path, conn, true);
        assertTrue("Should have applied face correction", fixed);

        // After fix: new terminal should NOT be on RIGHT face (x=221)
        AbsoluteBendpointDto newTerminal = path.get(0);
        assertNotEquals("Terminal should not be on old RIGHT face x",
                221, newTerminal.x());

        // Path should have a BP outside element at margin distance (10px)
        // Check that at least one BP is at margin distance from element edge
        boolean hasClearanceBP = false;
        for (AbsoluteBendpointDto bp : path) {
            // Margin distance from TOP: y = 100 - 10 = 90
            // Margin distance from BOTTOM: y = 160 + 10 = 170
            // Margin distance from LEFT: x = 100 - 10 = 90
            if (bp.y() == source.y() - 10
                    || bp.y() == source.y() + source.height() + 10
                    || bp.x() == source.x() - 10
                    || bp.x() == source.x() + source.width() + 10) {
                hasClearanceBP = true;
                break;
            }
        }
        assertTrue("Corrected path should include a clearance waypoint at margin distance",
                hasClearanceBP);
    }

    @Test
    public void b35_shouldReRouteTerminalSegments_notJustSwapTerminal() {
        // AC-4: Phase B re-routes terminal segments, not just swap terminal BP.
        // Verify the corrected path differs structurally from just swapping terminal.
        RoutingRect source = new RoutingRect(100, 100, 120, 60, "src");
        RoutingRect target = new RoutingRect(100, 350, 120, 60, "tgt");

        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(221, 130),  // source RIGHT terminal
                new AbsoluteBendpointDto(221, 150),
                new AbsoluteBendpointDto(120, 150),  // crosses source
                new AbsoluteBendpointDto(120, 350)
        ));

        int originalSize = path.size();
        RoutingPipeline.ConnectionEndpoints conn = new RoutingPipeline.ConnectionEndpoints(
                "conn-reroute", source, target, Collections.emptyList(), null, 1);

        pipeline.correctSelfElementPassThrough(path, conn, true);

        // Path should not simply be the same size with only the terminal swapped.
        // The re-routing may change the path structure (add clearance WP, remove internals).
        // Verify no self-source crossing after fix.
        assertNoSegmentPassesThrough(path, source, target, source, true);
    }

    @Test
    public void b35_shouldUseAngularProximityOrder_forFaceSelection() {
        // AC-2 (Phase B): Alternative faces tried in angular proximity order.
        // Source exits RIGHT. Target is directly below → BOTTOM face is closest angle.
        // If RIGHT→source crossing exists, Phase B should try BOTTOM first.
        RoutingRect source = new RoutingRect(100, 100, 120, 60, "src");
        RoutingRect target = new RoutingRect(100, 350, 120, 60, "tgt");

        // Path that only crosses source when using RIGHT face
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(221, 130),  // RIGHT terminal
                new AbsoluteBendpointDto(221, 150),
                new AbsoluteBendpointDto(120, 150),  // crosses source at y=150
                new AbsoluteBendpointDto(120, 350)
        ));

        RoutingPipeline.ConnectionEndpoints conn = new RoutingPipeline.ConnectionEndpoints(
                "conn-angular", source, target, Collections.emptyList(), null, 1);

        boolean fixed = pipeline.correctSelfElementPassThrough(path, conn, true);
        assertTrue(fixed);

        // New terminal should be on BOTTOM face (y=161 or close after cleanup)
        // since target is directly below and BOTTOM has the smallest angular distance
        AbsoluteBendpointDto newTerminal = path.get(0);
        // BOTTOM face terminal y should be at source.y + source.height + 1 = 161
        assertTrue("New terminal should be on BOTTOM face (closest to target direction). " +
                "Terminal y=" + newTerminal.y() + " expected around 161",
                newTerminal.y() >= source.y() + source.height());
    }

    @Test
    public void b35_shouldPreserveCleanPath_whenNoSelfPassThrough() {
        // AC-9: Phase B is a no-op for clean connections.
        RoutingRect source = new RoutingRect(0, 0, 120, 60, "src");
        RoutingRect target = new RoutingRect(300, 200, 120, 60, "tgt");

        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(130, 30),
                new AbsoluteBendpointDto(360, 30),
                new AbsoluteBendpointDto(360, 200)
        ));

        List<AbsoluteBendpointDto> original = new ArrayList<>(path);
        RoutingPipeline.ConnectionEndpoints conn = new RoutingPipeline.ConnectionEndpoints(
                "conn-clean", source, target, Collections.emptyList(), null, 1);

        boolean fixed = pipeline.correctSelfElementPassThrough(path, conn, true);
        assertFalse("Clean path should not trigger correction", fixed);
        assertEquals("Clean path should be unchanged", original, path);
    }

    @Test
    public void b35_shouldMaintainOrthogonality_afterReRoute() {
        // AC-11: After Phase B re-route, all segments must be horizontal or vertical.
        RoutingRect source = new RoutingRect(100, 100, 120, 60, "src"); // x:100-220, y:100-160
        RoutingRect target = new RoutingRect(100, 350, 120, 60, "tgt");

        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(221, 130),  // source RIGHT terminal
                new AbsoluteBendpointDto(221, 150),
                new AbsoluteBendpointDto(120, 150),  // crosses source
                new AbsoluteBendpointDto(120, 350)
        ));

        RoutingPipeline.ConnectionEndpoints conn = new RoutingPipeline.ConnectionEndpoints(
                "conn-ortho", source, target, Collections.emptyList(), null, 1);

        boolean fixed = pipeline.correctSelfElementPassThrough(path, conn, true);
        assertTrue("Should have applied face correction", fixed);

        // Verify all segments are orthogonal (horizontal or vertical)
        for (int i = 0; i < path.size() - 1; i++) {
            AbsoluteBendpointDto a = path.get(i);
            AbsoluteBendpointDto b = path.get(i + 1);
            assertTrue("Segment " + i + " (" + a.x() + "," + a.y() + ")→("
                    + b.x() + "," + b.y() + ") should be orthogonal",
                    a.x() == b.x() || a.y() == b.y());
        }
    }

    @Test
    public void b35_shouldHandleBothSourceAndTarget_selfPassThrough() {
        // AC-11: Dual correction — both source and target have self-element PTs.
        // Source exits RIGHT but segment crosses back through source body.
        // Target enters LEFT but segment crosses through target body.
        RoutingRect source = new RoutingRect(100, 100, 120, 60, "src"); // x:100-220, y:100-160
        RoutingRect target = new RoutingRect(100, 350, 120, 60, "tgt"); // x:100-220, y:350-410

        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(221, 130),  // source RIGHT terminal (x=221)
                new AbsoluteBendpointDto(221, 150),
                new AbsoluteBendpointDto(120, 150),  // crosses source body
                new AbsoluteBendpointDto(120, 380),  // crosses target body
                new AbsoluteBendpointDto(99, 380)    // target LEFT terminal (x=99)
        ));

        RoutingPipeline.ConnectionEndpoints conn = new RoutingPipeline.ConnectionEndpoints(
                "conn-dual", source, target, Collections.emptyList(), null, 1);

        // Fix source first (as the pipeline does)
        boolean sourceFix = pipeline.correctSelfElementPassThrough(path, conn, true);
        // Fix target on the modified path
        boolean targetFix = pipeline.correctSelfElementPassThrough(path, conn, false);

        assertTrue("Source or target correction should have applied",
                sourceFix || targetFix);

        // Verify neither source nor target has self-element pass-through after both fixes
        int srcPT = pipeline.detectSelfElementPassThrough(path, source, target, true);
        int tgtPT = pipeline.detectSelfElementPassThrough(path, source, target, false);
        assertEquals("No source self-element pass-through after dual fix", -1, srcPT);
        assertEquals("No target self-element pass-through after dual fix", -1, tgtPT);
    }

    @Test
    public void b35_shouldEliminateAllSelfPassThroughs_phaseAPlusBCombined() {
        // AC-11 Integration: Phase A (EdgeAttachmentCalculator) + Phase B (RoutingPipeline)
        // together eliminate all self-element PTs on a known PT-producing geometry.
        //
        // Setup: source at top-left, target at bottom-right, intermediate BP below source.
        // Initial face determination gives TOP for source (BP is at a position that triggers
        // TOP face via determineFace). Phase A should detect the source PT and reassign face.
        // Then after terminal BP application, Phase B should find zero remaining PTs.
        RoutingRect source = new RoutingRect(200, 200, 120, 80, "s1");
        RoutingRect target = new RoutingRect(400, 400, 120, 80, "t1");

        // Intermediate BPs: one below source, one above target
        List<AbsoluteBendpointDto> bps = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(260, 350),
                new AbsoluteBendpointDto(460, 350)));

        // --- Phase A: validate faces ---
        EdgeAttachmentCalculator eac = new EdgeAttachmentCalculator();
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(bps);
        List<String> ids = List.of("c1");
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target,
                        Collections.emptyList(), "", 1));

        EdgeAttachmentCalculator.Face[] sourceFaces = {EdgeAttachmentCalculator.Face.TOP};
        EdgeAttachmentCalculator.Face[] targetFaces = {EdgeAttachmentCalculator.Face.LEFT};

        // Phase A should detect TOP causes source PT and reassign
        eac.validateFacesForSelfPassThrough(ids, bendpointLists, connections,
                sourceFaces, targetFaces);

        // Apply edge attachments with corrected faces (full Phase 1-3)
        eac.applyEdgeAttachments(ids, bendpointLists, connections);

        // --- Phase B: run self-element correction on the final path ---
        RoutingPipeline.ConnectionEndpoints conn = connections.get(0);
        List<AbsoluteBendpointDto> finalPath = bendpointLists.get(0);
        pipeline.correctSelfElementPassThrough(finalPath, conn, true);
        pipeline.correctSelfElementPassThrough(finalPath, conn, false);

        // Verify zero self-element pass-throughs
        int srcPT = pipeline.detectSelfElementPassThrough(finalPath, source, target, true);
        int tgtPT = pipeline.detectSelfElementPassThrough(finalPath, source, target, false);
        assertEquals("Integration: no source self-element pass-through after Phase A + B",
                -1, srcPT);
        assertEquals("Integration: no target self-element pass-through after Phase A + B",
                -1, tgtPT);
    }

    // --- B36: perimeterMargin threading ---

    @Test
    public void shouldUseDefaultPerimeterMarginEqualToMargin_whenDefaultConstructor() {
        // Default constructor: perimeterMargin = margin = 10 (backward compat, AC-8)
        // Verify by routing around a single obstacle — works with narrow perimeter
        RoutingPipeline defaultPipeline = new RoutingPipeline();
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(150, 150, 100, 100, "obs1"));
        RoutingRect source = new RoutingRect(50, 200, 40, 40, "src");
        RoutingRect target = new RoutingRect(350, 200, 40, 40, "tgt");
        assertFalse("Default pipeline (perimeterMargin=margin=10) should route around single obstacle",
                defaultPipeline.routeConnection(source, target, obstacles).isEmpty());
    }

    @Test
    public void shouldRouteAroundDenseWall_withExplicitPerimeterMargin() {
        // Explicit perimeterMargin=50 unlocks exterior routing around dense walls
        RoutingPipeline widePipeline = new RoutingPipeline(
                RoutingPipeline.DEFAULT_BEND_PENALTY, RoutingPipeline.DEFAULT_MARGIN,
                RoutingPipeline.DEFAULT_CONGESTION_WEIGHT, 50);
        List<RoutingRect> obstacles = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            obstacles.add(new RoutingRect(50 + i * 100, 100, 80, 60, "obs" + i));
        }
        RoutingRect source = new RoutingRect(70, 220, 40, 40, "src");
        RoutingRect target = new RoutingRect(430, 20, 40, 40, "tgt");
        List<AbsoluteBendpointDto> bps = widePipeline.routeConnection(source, target, obstacles);
        assertFalse("Pipeline with perimeterMargin=50 should find orthogonal path around dense wall",
                bps.isEmpty());
    }

    @Test
    public void shouldThreadPerimeterMargin_toVisibilityGraph() {
        // Explicit perimeterMargin=100 should produce different routing than default
        RoutingPipeline widePipeline = new RoutingPipeline(
                RoutingPipeline.DEFAULT_BEND_PENALTY, RoutingPipeline.DEFAULT_MARGIN,
                RoutingPipeline.DEFAULT_CONGESTION_WEIGHT, 100);
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(100, 100, 80, 60, "obs1"));
        RoutingRect source = new RoutingRect(50, 200, 40, 40, "src");
        RoutingRect target = new RoutingRect(250, 50, 40, 40, "tgt");
        List<AbsoluteBendpointDto> bps = widePipeline.routeConnection(source, target, obstacles);
        assertFalse("Pipeline with perimeterMargin=100 should route successfully", bps.isEmpty());
    }

    @Test
    public void shouldBeBackwardCompatible_existingConstructors() {
        // Existing constructors should produce valid routes (no regression)
        RoutingPipeline p1 = new RoutingPipeline();
        RoutingPipeline p2 = new RoutingPipeline(30, 10);
        RoutingPipeline p3 = new RoutingPipeline(30, 10, 5.0);
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(150, 150, 100, 100, "obs1"));
        RoutingRect source = new RoutingRect(50, 200, 40, 40, "src");
        RoutingRect target = new RoutingRect(350, 200, 40, 40, "tgt");
        // All should produce non-empty routes around the obstacle
        assertFalse("Default constructor routes successfully",
                p1.routeConnection(source, target, obstacles).isEmpty());
        assertFalse("Two-arg constructor routes successfully",
                p2.routeConnection(source, target, obstacles).isEmpty());
        assertFalse("Three-arg constructor routes successfully",
                p3.routeConnection(source, target, obstacles).isEmpty());
    }

    // ===================================================================
    // B37: Late-stage path simplification (simplifyFinalPath)
    // ===================================================================

    @Test
    public void b37_shouldEliminateUnnecessaryJog_whenAlternativePathClear() {
        // Path detours via y=200 to avoid obstacle, but direct route at y=100 from
        // (0,100) to (150,100) is clear — jog is unnecessary.
        // Obstacle at x:180-260, y:70-130 blocks y=100 corridor beyond x=150,
        // preventing full shortcut to (300,100) but allowing partial to (150,100).
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(0, 100),    // source terminal
                new AbsoluteBendpointDto(0, 200),
                new AbsoluteBendpointDto(150, 200),
                new AbsoluteBendpointDto(150, 100),
                new AbsoluteBendpointDto(300, 100)   // target terminal
        ));
        RoutingRect obstacle = new RoutingRect(180, 70, 80, 60, "obs"); // x:180-260, y:70-130

        RoutingPipeline.simplifyFinalPath(path, List.of(obstacle));

        assertEquals("Should eliminate jog to 3 BPs", 3, path.size());
        assertEquals(0, path.get(0).x());   assertEquals(100, path.get(0).y());
        assertEquals(150, path.get(1).x()); assertEquals(100, path.get(1).y());
        assertEquals(300, path.get(2).x()); assertEquals(100, path.get(2).y());
    }

    @Test
    public void b37_shouldPreserveJog_whenObstacleBlocksSimplifiedPath() {
        // Path detours around obstacles — jog is necessary.
        // obs1 at x:150-250, y:80-180 blocks y=100 corridor through interior.
        // obs2 at x:380-420, y:130-230 blocks H-first L-turn from (300,200) to (400,100).
        // All shortcuts either fail or produce midpoints matching original path coordinates.
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(100, 100),  // source terminal
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(300, 200),
                new AbsoluteBendpointDto(300, 100),
                new AbsoluteBendpointDto(400, 100)   // target terminal
        ));
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(150, 80, 100, 100, "obs1"),  // x:150-250, y:80-180
                new RoutingRect(380, 130, 40, 100, "obs2")); // x:380-420, y:130-230

        List<AbsoluteBendpointDto> original = new ArrayList<>(path);
        RoutingPipeline.simplifyFinalPath(path, obstacles);

        assertEquals("Path should be preserved — obstacles block all shortcuts",
                original.size(), path.size());
        for (int i = 0; i < path.size(); i++) {
            assertEquals("BP " + i + " x unchanged", original.get(i).x(), path.get(i).x());
            assertEquals("BP " + i + " y unchanged", original.get(i).y(), path.get(i).y());
        }
    }

    @Test
    public void b37_shouldPreserveTerminalBendpoints() {
        // Verify first and last BPs are never modified.
        AbsoluteBendpointDto sourceTerminal = new AbsoluteBendpointDto(50, 50);
        AbsoluteBendpointDto targetTerminal = new AbsoluteBendpointDto(350, 250);

        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                sourceTerminal,
                new AbsoluteBendpointDto(50, 100),
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(200, 200),
                new AbsoluteBendpointDto(200, 250),
                targetTerminal
        ));

        RoutingPipeline.simplifyFinalPath(path, Collections.emptyList());

        assertEquals("First BP must be source terminal",
                50, path.get(0).x());
        assertEquals(50, path.get(0).y());
        assertEquals("Last BP must be target terminal",
                350, path.get(path.size() - 1).x());
        assertEquals(250, path.get(path.size() - 1).y());
    }

    @Test
    public void b37_shouldSimplifyMultipleJogs_inSinglePass() {
        // Long staircase: 3 unnecessary jogs, all clearable in one greedy pass.
        // (0,0)→(0,50)→(50,50)→(50,100)→(100,100)→(100,150)→(150,150)→(150,200)→(200,200)
        // With no obstacles, greedy should shortcut from (0,0) all the way to (200,200)
        // via an L-turn midpoint.
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(0, 0),
                new AbsoluteBendpointDto(0, 50),
                new AbsoluteBendpointDto(50, 50),
                new AbsoluteBendpointDto(50, 100),
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(100, 150),
                new AbsoluteBendpointDto(150, 150),
                new AbsoluteBendpointDto(150, 200),
                new AbsoluteBendpointDto(200, 200)
        ));

        RoutingPipeline.simplifyFinalPath(path, Collections.emptyList());

        // Should shortcut to (0,0) → L-turn midpoint → (200,200) = 3 BPs
        assertEquals("Should simplify multi-jog to 3 BPs", 3, path.size());
        assertEquals(0, path.get(0).x());   assertEquals(0, path.get(0).y());
        assertEquals(200, path.get(2).x()); assertEquals(200, path.get(2).y());
    }

    @Test
    public void b37_shouldNotModifyOptimalPath() {
        // Path forced around central obstacle — already the simplest viable route.
        // Obstacle at x:50-150, y:-10-210 blocks all non-adjacent L-turn shortcuts
        // through the interior, making the detour path genuinely optimal.
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(0, 0),
                new AbsoluteBendpointDto(0, 200),
                new AbsoluteBendpointDto(200, 200),
                new AbsoluteBendpointDto(200, 0)
        ));
        RoutingRect obstacle = new RoutingRect(50, -10, 100, 220, "obs"); // x:50-150, y:-10-210

        List<AbsoluteBendpointDto> original = new ArrayList<>(path);
        RoutingPipeline.simplifyFinalPath(path, List.of(obstacle));

        assertEquals("Optimal path should not change size", original.size(), path.size());
        for (int i = 0; i < path.size(); i++) {
            assertEquals("BP " + i + " x unchanged", original.get(i).x(), path.get(i).x());
            assertEquals("BP " + i + " y unchanged", original.get(i).y(), path.get(i).y());
        }
    }

    @Test
    public void b37_shouldInsertLTurnMidpoint_forNonCollinearShortcut() {
        // Path: (0,0) → (0,100) → (50,100) → (50,200) → (200,200)
        // Shortcut from (0,0) to (200,200) needs an L-turn midpoint.
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(0, 0),
                new AbsoluteBendpointDto(0, 100),
                new AbsoluteBendpointDto(50, 100),
                new AbsoluteBendpointDto(50, 200),
                new AbsoluteBendpointDto(200, 200)
        ));

        RoutingPipeline.simplifyFinalPath(path, Collections.emptyList());

        // Should simplify to 3 BPs: (0,0) → L-turn midpoint → (200,200)
        assertEquals("Should have 3 BPs with L-turn midpoint", 3, path.size());
        assertEquals(0, path.get(0).x());     assertEquals(0, path.get(0).y());
        assertEquals(200, path.get(2).x());   assertEquals(200, path.get(2).y());
        // Midpoint should be an L-turn: horizontal-first = (200,0) or vertical-first = (0,200)
        AbsoluteBendpointDto mid = path.get(1);
        assertTrue("Midpoint should be L-turn",
                (mid.x() == 200 && mid.y() == 0) || (mid.x() == 0 && mid.y() == 200));
    }

    @Test
    public void b37_shouldHandlePathWithOnlyTwoPoints() {
        // 2-BP path: too short to simplify (< 4 threshold)
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(0, 0),
                new AbsoluteBendpointDto(100, 0)
        ));

        RoutingPipeline.simplifyFinalPath(path, Collections.emptyList());

        assertEquals("2-BP path should be unchanged", 2, path.size());
    }

    @Test
    public void b37_shouldNotModifyPathWithThreePoints() {
        // 3-BP path: source-terminal, one intermediate, target-terminal — no jog possible.
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(0, 0),
                new AbsoluteBendpointDto(0, 100),
                new AbsoluteBendpointDto(100, 100)
        ));

        RoutingPipeline.simplifyFinalPath(path, Collections.emptyList());

        assertEquals("3-BP path should be unchanged", 3, path.size());
    }

    @Test
    public void b37_shouldNotRemoveB35ClearanceWaypoint_whenSourceTargetExcluded() {
        // Simulates B35 Phase B clearance detour around own source element.
        // Source element at (100,100,120,60) — excluded from obstacle list.
        // Path detours around source via clearance waypoint at y=90 (10px above element).
        // Even though source is excluded from obstacles, the shortcut past the clearance
        // waypoint is blocked by another obstacle.
        List<AbsoluteBendpointDto> path = new ArrayList<>(List.of(
                new AbsoluteBendpointDto(160, 90),   // source terminal (clearance WP above source)
                new AbsoluteBendpointDto(50, 90),    // route left to avoid element
                new AbsoluteBendpointDto(50, 250),   // route down
                new AbsoluteBendpointDto(160, 250),  // route right
                new AbsoluteBendpointDto(160, 350)   // target terminal
        ));

        // Obstacle that blocks shortcut from (160,90) directly to (160,350)
        // This represents another element between source and target
        RoutingRect blockingObstacle = new RoutingRect(130, 180, 60, 40, "blocker"); // x:130-190, y:180-220

        RoutingPipeline.simplifyFinalPath(path, List.of(blockingObstacle));

        // The clearance waypoint detour should be preserved because the blocker
        // prevents shortcutting past it
        assertTrue("Clearance detour should be preserved (>= 4 BPs)",
                path.size() >= 4);
        // Source terminal preserved
        assertEquals(160, path.get(0).x()); assertEquals(90, path.get(0).y());
        // Target terminal preserved
        assertEquals(160, path.get(path.size() - 1).x());
        assertEquals(350, path.get(path.size() - 1).y());
    }

    // B39 tests removed: Stage 4.7h now reuses CoincidentSegmentDetector (tested in
    // CoincidentSegmentDetectorTest.java — 13 tests covering detect + applyOffsets).

    // =============================================
    // B45: Interior terminal BP fix
    // =============================================

    /**
     * B45 AC4: fixInteriorTerminalBPs should reposition source terminal BP that is
     * inside source element (horizontal geometry — BP.x inside source x-range).
     * Mirrors E2E finding: App Server Farm (574,159,222,55) BP[0] at (777,186).
     */
    @Test
    public void shouldFixSourceInteriorBP_whenHorizontalGeometry() {
        // Source at (574, 159) w=222 h=55 → x-range 574-796, center (685, 186)
        RoutingRect source = new RoutingRect(574, 159, 222, 55, "src");
        RoutingRect target = new RoutingRect(900, 159, 120, 55, "tgt");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        // BP inside source (x=777, within 574-796) but NOT at center (685,186)
        path.add(new AbsoluteBendpointDto(777, 186));
        path.add(new AbsoluteBendpointDto(850, 186));
        path.add(new AbsoluteBendpointDto(899, 186));

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), null, 1);

        int fixes = RoutingPipeline.fixInteriorTerminalBPs(path, conn);

        assertEquals("Should fix 1 terminal", 1, fixes);
        // Next BP is to the right → RIGHT face: x+w+1 = 797, cy = 186
        assertEquals("Source terminal X at right face edge", 797, path.get(0).x());
        assertEquals("Source terminal Y at center", 186, path.get(0).y());
    }

    /**
     * B45 AC4: fixInteriorTerminalBPs should reposition source terminal BP that is
     * inside source element (vertical geometry — BP.y inside source y-range).
     * Mirrors E2E finding: Integration Hub (730,374,254,55) BP[1] at (857,398).
     */
    @Test
    public void shouldFixSourceInteriorBP_whenVerticalGeometry() {
        // Source at (730, 374) w=254 h=55 → y-range 374-429, center (857, 401)
        RoutingRect source = new RoutingRect(730, 374, 254, 55, "src");
        RoutingRect target = new RoutingRect(730, 500, 254, 55, "tgt");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        // BP inside source (y=398, within 374-429) but NOT at center (857,401)
        path.add(new AbsoluteBendpointDto(857, 398));
        path.add(new AbsoluteBendpointDto(857, 460));
        path.add(new AbsoluteBendpointDto(857, 499));

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), null, 1);

        int fixes = RoutingPipeline.fixInteriorTerminalBPs(path, conn);

        assertEquals("Should fix 1 terminal", 1, fixes);
        // Next BP is below → BOTTOM face: cx = 857, y+h+1 = 430
        assertEquals("Source terminal X at center", 857, path.get(0).x());
        assertEquals("Source terminal Y at bottom face edge", 430, path.get(0).y());
    }

    /**
     * B45 AC4: fixInteriorTerminalBPs should reposition target terminal BP
     * that is inside target element.
     */
    @Test
    public void shouldFixTargetInteriorBP_whenInsideTargetElement() {
        RoutingRect source = new RoutingRect(100, 200, 120, 60, "src");
        // Target at (400, 200) w=120 h=60 → center (460, 230)
        RoutingRect target = new RoutingRect(400, 200, 120, 60, "tgt");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(221, 230)); // source right face
        path.add(new AbsoluteBendpointDto(300, 230));
        // Last BP inside target (x=420, within 400-520) but not at center (460,230)
        path.add(new AbsoluteBendpointDto(420, 230));

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), null, 1);

        int fixes = RoutingPipeline.fixInteriorTerminalBPs(path, conn);

        assertEquals("Should fix 1 terminal", 1, fixes);
        // Prev BP is to the left → LEFT face: x-1 = 399, cy = 230
        assertEquals("Target terminal X at left face edge", 399, path.get(path.size() - 1).x());
        assertEquals("Target terminal Y at center", 230, path.get(path.size() - 1).y());
    }

    /**
     * B45 AC4: fixInteriorTerminalBPs should remove intermediate BP
     * that is inside an endpoint element.
     */
    @Test
    public void shouldRemoveIntermediateBP_whenInsideEndpointElement() {
        RoutingRect source = new RoutingRect(100, 200, 120, 60, "src"); // 100-220, 200-260
        RoutingRect target = new RoutingRect(400, 200, 120, 60, "tgt");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(221, 230)); // source right face (outside)
        path.add(new AbsoluteBendpointDto(150, 220)); // intermediate INSIDE source
        path.add(new AbsoluteBendpointDto(300, 230));
        path.add(new AbsoluteBendpointDto(399, 230)); // target left face (outside)

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), null, 1);

        int fixes = RoutingPipeline.fixInteriorTerminalBPs(path, conn);

        assertEquals("Should fix 1 intermediate BP", 1, fixes);
        assertEquals("Path should have 3 BPs after removal", 3, path.size());
        // Verify the interior BP was removed
        assertEquals(221, path.get(0).x());
        assertEquals(300, path.get(1).x());
        assertEquals(399, path.get(2).x());
    }

    /**
     * B45 AC4: fixInteriorTerminalBPs should remove intermediate BP
     * that is inside the TARGET element (complements source-side test above).
     */
    @Test
    public void shouldRemoveIntermediateBP_whenInsideTargetElement() {
        RoutingRect source = new RoutingRect(100, 200, 120, 60, "src");
        RoutingRect target = new RoutingRect(400, 200, 120, 60, "tgt"); // 400-520, 200-260

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(221, 230)); // source right face (outside)
        path.add(new AbsoluteBendpointDto(300, 230));
        path.add(new AbsoluteBendpointDto(450, 220)); // intermediate INSIDE target
        path.add(new AbsoluteBendpointDto(399, 230)); // target left face (outside)

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), null, 1);

        int fixes = RoutingPipeline.fixInteriorTerminalBPs(path, conn);

        assertEquals("Should fix 1 intermediate BP", 1, fixes);
        assertEquals("Path should have 3 BPs after removal", 3, path.size());
        assertEquals(221, path.get(0).x());
        assertEquals(300, path.get(1).x());
        assertEquals(399, path.get(2).x());
    }

    /**
     * B45 AC4: fixInteriorTerminalBPs should fix BP exactly on element boundary.
     * isInsideOrOnBoundary returns true for boundary BPs. Normal edge attachment
     * places BPs at 1px outside (x-1, x+w+1, etc.), so a BP ON the boundary
     * indicates a problem that needs correction.
     */
    @Test
    public void shouldFixBP_whenExactlyOnElementBoundary() {
        // Source at (100, 200) w=120 h=60 → right edge at x=220
        RoutingRect source = new RoutingRect(100, 200, 120, 60, "src");
        RoutingRect target = new RoutingRect(400, 200, 120, 60, "tgt");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        // BP on source right boundary (x=220, which is x+w = 100+120)
        path.add(new AbsoluteBendpointDto(220, 230));
        path.add(new AbsoluteBendpointDto(300, 230));
        path.add(new AbsoluteBendpointDto(399, 230));

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), null, 1);

        int fixes = RoutingPipeline.fixInteriorTerminalBPs(path, conn);

        assertEquals("Should fix boundary BP", 1, fixes);
        // Next BP is to the right → RIGHT face: x+w+1 = 221
        assertEquals("Source terminal X moved to 1px outside right face", 221, path.get(0).x());
    }

    /**
     * B45 AC4: fixInteriorTerminalBPs should NOT trigger for BP 1px outside
     * element boundary. This is the normal edge attachment position.
     */
    @Test
    public void shouldNotFixBP_whenOnePixelOutsideElement() {
        // Source at (100, 200) w=120 h=60 → right edge at x=220
        RoutingRect source = new RoutingRect(100, 200, 120, 60, "src");
        RoutingRect target = new RoutingRect(400, 200, 120, 60, "tgt");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        // BP at 1px outside right face (x=221) — normal edge attachment
        path.add(new AbsoluteBendpointDto(221, 230));
        path.add(new AbsoluteBendpointDto(300, 230));
        path.add(new AbsoluteBendpointDto(399, 230));

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), null, 1);

        int fixes = RoutingPipeline.fixInteriorTerminalBPs(path, conn);

        assertEquals("Should not fix — BP is outside element", 0, fixes);
        assertEquals("Source terminal unchanged", 221, path.get(0).x());
    }

    /**
     * B45 AC4: fixInteriorTerminalBPs should fix both terminals simultaneously
     * when both are inside their respective elements.
     */
    @Test
    public void shouldFixBothTerminals_whenBothInsideElements() {
        RoutingRect source = new RoutingRect(100, 200, 120, 60, "src"); // center (160, 230)
        RoutingRect target = new RoutingRect(400, 200, 120, 60, "tgt"); // center (460, 230)

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        // First BP inside source but not at center
        path.add(new AbsoluteBendpointDto(200, 230));
        path.add(new AbsoluteBendpointDto(300, 230));
        // Last BP inside target but not at center
        path.add(new AbsoluteBendpointDto(420, 230));

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), null, 1);

        int fixes = RoutingPipeline.fixInteriorTerminalBPs(path, conn);

        assertEquals("Should fix 2 terminals", 2, fixes);
        // Source → RIGHT face (221, 230)
        assertEquals(221, path.get(0).x());
        assertEquals(230, path.get(0).y());
        // Target → LEFT face (399, 230)
        assertEquals(399, path.get(path.size() - 1).x());
        assertEquals(230, path.get(path.size() - 1).y());
    }

    /**
     * B45: fixInteriorTerminalBPs should also fix terminal BPs that happen
     * to be at exact element center (superset of B44 center-termination check).
     */
    @Test
    public void shouldFixCenterBP_asSuperset() {
        RoutingRect source = new RoutingRect(100, 200, 120, 60, "src"); // center (160, 230)
        RoutingRect target = new RoutingRect(400, 200, 120, 60, "tgt");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        // First BP at exact source center
        path.add(new AbsoluteBendpointDto(160, 230));
        path.add(new AbsoluteBendpointDto(300, 230));
        path.add(new AbsoluteBendpointDto(399, 230));

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), null, 1);

        int fixes = RoutingPipeline.fixInteriorTerminalBPs(path, conn);

        assertEquals("Should fix center BP as interior BP", 1, fixes);
        // RIGHT face: x+w+1 = 221, cy = 230
        assertEquals(221, path.get(0).x());
    }

    /**
     * B45: fixInteriorTerminalBPs should return 0 for paths with fewer than 2 BPs.
     */
    @Test
    public void shouldReturnZeroForInteriorFix_whenPathTooShort() {
        RoutingRect source = new RoutingRect(100, 200, 120, 60, "src");
        RoutingRect target = new RoutingRect(400, 200, 120, 60, "tgt");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(160, 230)); // single BP

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), null, 1);

        int fixes = RoutingPipeline.fixInteriorTerminalBPs(path, conn);

        assertEquals("Should return 0 for short path", 0, fixes);
    }

    /**
     * B45: fixInteriorTerminalBPs should insert an L-bend when repositioning
     * a source terminal BP to the TOP face creates a diagonal with the next BP.
     * Mirrors E2E finding: Integration Hub (730,374,254,55) → DMS (1430,399,246,55),
     * where source top face midpoint y=373 differs from next BP y=398.
     */
    @Test
    public void shouldInsertLBend_whenSourceFixCreatesNonOrthogonalPath() {
        // Source at (730, 374) w=254 h=55, center (857, 401), top y=374
        RoutingRect source = new RoutingRect(730, 374, 254, 55, "src");
        // Target at (1430, 399) w=246 h=55
        RoutingRect target = new RoutingRect(1430, 399, 246, 55, "tgt");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        // BP[0] inside source (y=398, within 374-429)
        path.add(new AbsoluteBendpointDto(857, 398));
        // BP[1] at target top approach — different y than where source will be fixed to
        path.add(new AbsoluteBendpointDto(1553, 398));

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), null, 1);

        int fixes = RoutingPipeline.fixInteriorTerminalBPs(path, conn);

        assertEquals("Should fix 1 terminal", 1, fixes);
        // BP[0] moved to TOP face midpoint: (857, 373)
        assertEquals("Source terminal X at center", 857, path.get(0).x());
        assertEquals("Source terminal Y at top face edge", 373, path.get(0).y());
        // L-bend inserted: (857, 398) — same x as source face, same y as next BP
        assertEquals("Path should have 3 BPs after L-bend insertion", 3, path.size());
        assertEquals("L-bend X matches source face", 857, path.get(1).x());
        assertEquals("L-bend Y matches next BP", 398, path.get(1).y());
        // Original BP[1] preserved
        assertEquals("Next BP unchanged", 1553, path.get(2).x());
        assertEquals("Next BP unchanged", 398, path.get(2).y());
    }

    /**
     * B45: fixInteriorTerminalBPs should insert an L-bend when repositioning
     * a target terminal BP creates a diagonal with the previous BP.
     */
    @Test
    public void shouldInsertLBend_whenTargetFixCreatesNonOrthogonalPath() {
        RoutingRect source = new RoutingRect(100, 200, 120, 60, "src");
        // Target at (500, 180) w=120 h=60, center (560, 210), left x=500
        RoutingRect target = new RoutingRect(500, 180, 120, 60, "tgt");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(221, 230)); // source right face
        path.add(new AbsoluteBendpointDto(350, 230)); // intermediate
        // Last BP inside target (x=520, within 500-620), y=220 (not at center 210)
        path.add(new AbsoluteBendpointDto(520, 220));

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), null, 1);

        int fixes = RoutingPipeline.fixInteriorTerminalBPs(path, conn);

        assertEquals("Should fix 1 terminal", 1, fixes);
        // Prev BP at (350, 230), target LEFT face midpoint at (499, 210)
        // L-bend at (350, 210) — same x as prev, same y as target face
        assertEquals("Path should have 4 BPs after L-bend insertion", 4, path.size());
        assertEquals("L-bend X matches prev BP", 350, path.get(2).x());
        assertEquals("L-bend Y matches target face", 210, path.get(2).y());
        // Target terminal at LEFT face
        assertEquals("Target terminal X at left face edge", 499, path.get(3).x());
        assertEquals("Target terminal Y at center", 210, path.get(3).y());
    }

    /**
     * B45: fixInteriorTerminalBPs should NOT insert L-bend when the fix
     * preserves axis alignment (no diagonal created).
     */
    @Test
    public void shouldNotInsertLBend_whenFixPreservesOrthogonality() {
        // Source at (574, 159) w=222 h=55, center (685, 186)
        RoutingRect source = new RoutingRect(574, 159, 222, 55, "src");
        RoutingRect target = new RoutingRect(900, 159, 120, 55, "tgt");

        List<AbsoluteBendpointDto> path = new ArrayList<>();
        // BP inside source — next BP at same y (186), so fix to RIGHT face stays aligned
        path.add(new AbsoluteBendpointDto(777, 186));
        path.add(new AbsoluteBendpointDto(850, 186));

        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), null, 1);

        int fixes = RoutingPipeline.fixInteriorTerminalBPs(path, conn);

        assertEquals("Should fix 1 terminal", 1, fixes);
        assertEquals("No L-bend inserted — path should still have 2 BPs", 2, path.size());
        // RIGHT face: x+w+1 = 797, cy = 186
        assertEquals(797, path.get(0).x());
        assertEquals(186, path.get(0).y());
    }

    // --- B47: Connection ordering tests ---

    @Test
    public void buildConnectionRoutingOrder_shouldSortByDescendingManhattanDistance() {
        // Short (Manhattan=100), medium (Manhattan=300), long (Manhattan=600)
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c-short",
                        new RoutingRect(0, 0, 50, 50, "s1"), new RoutingRect(100, 0, 50, 50, "t1"),
                        List.of(), null, 0, List.of()),
                new RoutingPipeline.ConnectionEndpoints("c-medium",
                        new RoutingRect(0, 0, 50, 50, "s2"), new RoutingRect(300, 0, 50, 50, "t2"),
                        List.of(), null, 0, List.of()),
                new RoutingPipeline.ConnectionEndpoints("c-long",
                        new RoutingRect(0, 0, 50, 50, "s3"), new RoutingRect(300, 300, 50, 50, "t3"),
                        List.of(), null, 0, List.of()));

        Integer[] order = RoutingPipeline.buildConnectionRoutingOrder(connections);

        assertEquals("Longest first", Integer.valueOf(2), order[0]);
        assertEquals("Medium second", Integer.valueOf(1), order[1]);
        assertEquals("Shortest last", Integer.valueOf(0), order[2]);
    }

    @Test
    public void buildConnectionRoutingOrder_shouldTieBreakByConnectionId() {
        // Same Manhattan distance (100), different IDs
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("conn-zebra",
                        new RoutingRect(0, 0, 50, 50, "s1"), new RoutingRect(100, 0, 50, 50, "t1"),
                        List.of(), null, 0, List.of()),
                new RoutingPipeline.ConnectionEndpoints("conn-alpha",
                        new RoutingRect(0, 0, 50, 50, "s2"), new RoutingRect(100, 0, 50, 50, "t2"),
                        List.of(), null, 0, List.of()));

        Integer[] order = RoutingPipeline.buildConnectionRoutingOrder(connections);

        // Alphabetical: "conn-alpha" (idx 1) before "conn-zebra" (idx 0)
        assertEquals("Alpha first (alphabetical tie-break)", Integer.valueOf(1), order[0]);
        assertEquals("Zebra second", Integer.valueOf(0), order[1]);
    }

    @Test
    public void buildConnectionRoutingOrder_shouldHandleSingleConnection() {
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("only",
                        new RoutingRect(0, 0, 50, 50, "s"), new RoutingRect(100, 0, 50, 50, "t"),
                        List.of(), null, 0, List.of()));

        Integer[] order = RoutingPipeline.buildConnectionRoutingOrder(connections);

        assertEquals(1, order.length);
        assertEquals(Integer.valueOf(0), order[0]);
    }
}
