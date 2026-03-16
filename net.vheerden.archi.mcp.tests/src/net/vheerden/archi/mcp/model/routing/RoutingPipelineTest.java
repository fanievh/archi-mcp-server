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
                    VisNode sourcePort, VisNode targetPort) {
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

    /**
     * Tests if an orthogonal segment passes strictly through an obstacle rectangle.
     */
    private boolean segmentIntersectsObstacle(int x1, int y1, int x2, int y2, RoutingRect obs) {
        int ox = obs.x(), oy = obs.y();
        int ow = obs.width(), oh = obs.height();

        if (x1 == x2) {
            // Vertical segment
            if (x1 > ox && x1 < ox + ow) {
                int minY = Math.min(y1, y2);
                int maxY = Math.max(y1, y2);
                return minY < oy + oh && maxY > oy;
            }
        } else if (y1 == y2) {
            // Horizontal segment
            if (y1 > oy && y1 < oy + oh) {
                int minX = Math.min(x1, x2);
                int maxX = Math.max(x1, x2);
                return minX < ox + ow && maxX > ox;
            }
        }
        return false;
    }
}
