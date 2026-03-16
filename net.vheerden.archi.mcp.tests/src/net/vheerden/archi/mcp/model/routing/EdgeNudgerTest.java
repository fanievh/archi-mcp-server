package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Tests for {@link EdgeNudger} (Story 10-7b).
 * Pure-geometry tests — no OSGi runtime required.
 */
public class EdgeNudgerTest {

    private EdgeNudger nudger;

    @Before
    public void setUp() {
        nudger = new EdgeNudger();
    }

    // --- Test 4.1: Two parallel horizontal connections nudged apart (AC #1) ---

    @Test
    public void shouldNudgeApart_whenTwoParallelHorizontalConnections() {
        // Two connections sharing a horizontal corridor at y=200
        // Connection 0: (50,200) → (350,200) — horizontal segment
        // Connection 1: (100,200) → (300,200) — horizontal segment
        List<String> ids = List.of("c0", "c1");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(50, 200),
                new AbsoluteBendpointDto(350, 200))));
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(300, 200))));

        List<int[]> sourceCenters = List.of(new int[]{50, 50}, new int[]{100, 50});
        List<int[]> targetCenters = List.of(new int[]{350, 350}, new int[]{300, 350});

        // Obstacles far away (corridor is wide)
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(0, 0, 500, 10, "top"),
                new RoutingRect(0, 390, 500, 10, "bottom"));

        nudger.nudgePaths(ids, bendpointLists, sourceCenters, targetCenters, obstacles);

        // After nudging, the two connections should have different y-coordinates
        int y0_bp0 = bendpointLists.get(0).get(0).y();
        int y0_bp1 = bendpointLists.get(0).get(1).y();
        int y1_bp0 = bendpointLists.get(1).get(0).y();
        int y1_bp1 = bendpointLists.get(1).get(1).y();

        // Both bendpoints within a connection should share the same y (still horizontal)
        assertEquals("Connection 0 segment should remain horizontal", y0_bp0, y0_bp1);
        assertEquals("Connection 1 segment should remain horizontal", y1_bp0, y1_bp1);

        // The two connections should be separated
        int separation = Math.abs(y0_bp0 - y1_bp0);
        assertTrue("Parallel connections should be separated by at least MIN_SPACING (8px), got " + separation,
                separation >= EdgeNudger.DEFAULT_MIN_SPACING);
    }

    // --- Test 4.2: Two parallel vertical connections nudged apart (AC #1) ---

    @Test
    public void shouldNudgeApart_whenTwoParallelVerticalConnections() {
        // Two connections sharing a vertical corridor at x=200
        List<String> ids = List.of("c0", "c1");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(200, 50),
                new AbsoluteBendpointDto(200, 350))));
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(200, 100),
                new AbsoluteBendpointDto(200, 300))));

        List<int[]> sourceCenters = List.of(new int[]{50, 50}, new int[]{50, 100});
        List<int[]> targetCenters = List.of(new int[]{350, 350}, new int[]{350, 300});

        List<RoutingRect> obstacles = List.of(
                new RoutingRect(0, 0, 10, 400, "left"),
                new RoutingRect(390, 0, 10, 400, "right"));

        nudger.nudgePaths(ids, bendpointLists, sourceCenters, targetCenters, obstacles);

        int x0_bp0 = bendpointLists.get(0).get(0).x();
        int x0_bp1 = bendpointLists.get(0).get(1).x();
        int x1_bp0 = bendpointLists.get(1).get(0).x();
        int x1_bp1 = bendpointLists.get(1).get(1).x();

        assertEquals("Connection 0 segment should remain vertical", x0_bp0, x0_bp1);
        assertEquals("Connection 1 segment should remain vertical", x1_bp0, x1_bp1);

        int separation = Math.abs(x0_bp0 - x1_bp0);
        assertTrue("Parallel connections should be separated by at least MIN_SPACING (8px), got " + separation,
                separation >= EdgeNudger.DEFAULT_MIN_SPACING);
    }

    // --- Test 4.3: Three connections in a corridor — evenly distributed (AC #1) ---

    @Test
    public void shouldDistributeEvenly_whenThreeConnectionsInCorridor() {
        List<String> ids = List.of("c0", "c1", "c2");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        // Three horizontal segments all at y=200
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(50, 200),
                new AbsoluteBendpointDto(350, 200))));
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(300, 200))));
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(150, 200),
                new AbsoluteBendpointDto(250, 200))));

        List<int[]> sourceCenters = List.of(
                new int[]{50, 50}, new int[]{100, 50}, new int[]{150, 50});
        List<int[]> targetCenters = List.of(
                new int[]{350, 350}, new int[]{300, 350}, new int[]{250, 350});

        List<RoutingRect> obstacles = List.of(
                new RoutingRect(0, 0, 500, 10, "top"),
                new RoutingRect(0, 390, 500, 10, "bottom"));

        nudger.nudgePaths(ids, bendpointLists, sourceCenters, targetCenters, obstacles);

        // Get y-coordinates of the three connections after nudging
        int y0 = bendpointLists.get(0).get(0).y();
        int y1 = bendpointLists.get(1).get(0).y();
        int y2 = bendpointLists.get(2).get(0).y();

        // All three should be different
        assertNotEquals("Connection 0 and 1 should have different y", y0, y1);
        assertNotEquals("Connection 1 and 2 should have different y", y1, y2);
        assertNotEquals("Connection 0 and 2 should have different y", y0, y2);

        // Sort to check even distribution
        int[] sorted = {y0, y1, y2};
        java.util.Arrays.sort(sorted);
        int gap1 = sorted[1] - sorted[0];
        int gap2 = sorted[2] - sorted[1];

        // Gaps should be equal (within rounding tolerance of 1px)
        assertTrue("Connections should be evenly distributed, gaps: " + gap1 + " and " + gap2,
                Math.abs(gap1 - gap2) <= 1);
    }

    // --- Test 4.4: Narrow corridor clamps spacing (AC #2) ---

    @Test
    public void shouldClampSpacing_whenCorridorIsNarrow() {
        // Two connections in a narrow corridor (40px between obstacles, 20px usable after margin)
        List<String> ids = List.of("c0", "c1");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(50, 200),
                new AbsoluteBendpointDto(350, 200))));
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(300, 200))));

        List<int[]> sourceCenters = List.of(new int[]{50, 50}, new int[]{100, 50});
        List<int[]> targetCenters = List.of(new int[]{350, 350}, new int[]{300, 350});

        // Narrow corridor: obstacles at y=180 (bottom edge) and y=220 (top edge)
        // Raw gap = 40px, with 10px margin each side = 20px usable
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(0, 160, 400, 20, "above"),   // bottom edge at y=180
                new RoutingRect(0, 220, 400, 20, "below"));  // top edge at y=220

        nudger.nudgePaths(ids, bendpointLists, sourceCenters, targetCenters, obstacles);

        int y0 = bendpointLists.get(0).get(0).y();
        int y1 = bendpointLists.get(1).get(0).y();

        // Segments should still be separated but within corridor bounds
        int separation = Math.abs(y0 - y1);
        assertTrue("Segments should be separated even in narrow corridor, got " + separation,
                separation >= 1);

        // Neither segment should be inside an obstacle or within obstacle margin
        for (List<AbsoluteBendpointDto> bps : bendpointLists) {
            for (AbsoluteBendpointDto bp : bps) {
                assertTrue("Bendpoint y=" + bp.y() + " should be >= 190 (margin from top obstacle)",
                        bp.y() >= 190);
                assertTrue("Bendpoint y=" + bp.y() + " should be <= 210 (margin from bottom obstacle)",
                        bp.y() <= 210);
            }
        }
    }

    // --- Test 4.5: Single connection in corridor — centred, no offset (AC #3) ---

    @Test
    public void shouldNotNudge_whenSingleConnectionInCorridor() {
        List<String> ids = List.of("c0");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(50, 200),
                new AbsoluteBendpointDto(350, 200))));

        List<int[]> sourceCenters = List.of(new int[]{50, 50});
        List<int[]> targetCenters = List.of(new int[]{350, 350});
        List<RoutingRect> obstacles = List.of();

        nudger.nudgePaths(ids, bendpointLists, sourceCenters, targetCenters, obstacles);

        // Single connection — should be unchanged
        assertEquals(200, bendpointLists.get(0).get(0).y());
        assertEquals(200, bendpointLists.get(0).get(1).y());
    }

    // --- Test 4.6: No shared segments — all paths unchanged (AC #1) ---

    @Test
    public void shouldNotNudge_whenNoSharedSegments() {
        // Two connections in DIFFERENT corridors (y=100 and y=300)
        List<String> ids = List.of("c0", "c1");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(50, 100),
                new AbsoluteBendpointDto(350, 100))));
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(50, 300),
                new AbsoluteBendpointDto(350, 300))));

        List<int[]> sourceCenters = List.of(new int[]{50, 50}, new int[]{50, 350});
        List<int[]> targetCenters = List.of(new int[]{350, 50}, new int[]{350, 350});
        List<RoutingRect> obstacles = List.of();

        nudger.nudgePaths(ids, bendpointLists, sourceCenters, targetCenters, obstacles);

        // Both should be unchanged (different corridors → no nudging)
        assertEquals(100, bendpointLists.get(0).get(0).y());
        assertEquals(100, bendpointLists.get(0).get(1).y());
        assertEquals(300, bendpointLists.get(1).get(0).y());
        assertEquals(300, bendpointLists.get(1).get(1).y());
    }

    // --- Test 4.7: Empty/single-bendpoint connections — skipped (AC #1) ---

    @Test
    public void shouldSkipConnections_whenEmptyOrSingleBendpoint() {
        List<String> ids = List.of("c0", "c1");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>());  // 0 bendpoints
        bendpointLists.add(new ArrayList<>(List.of(new AbsoluteBendpointDto(200, 100))));  // 1 bendpoint

        List<int[]> sourceCenters = List.of(new int[]{50, 50}, new int[]{50, 100});
        List<int[]> targetCenters = List.of(new int[]{350, 50}, new int[]{350, 100});
        List<RoutingRect> obstacles = List.of();

        // Should not throw, should return unchanged
        List<List<AbsoluteBendpointDto>> result =
                nudger.nudgePaths(ids, bendpointLists, sourceCenters, targetCenters, obstacles);

        assertEquals(2, result.size());
        assertTrue(result.get(0).isEmpty());
        assertEquals(1, result.get(1).size());
    }

    // --- Test 4.8: Mixed horizontal and vertical groups processed independently (AC #1) ---

    @Test
    public void shouldProcessHorizontalAndVerticalGroupsIndependently() {
        // Connection A: L-shaped path with horizontal at y=200 and vertical at x=300
        // Connection B: horizontal at y=200 (shares corridor with A's horizontal)
        // Connection C: vertical at x=300 (shares corridor with A's vertical)
        List<String> ids = List.of("cA", "cB", "cC");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();

        // A: (100,200) → (300,200) → (300,400) — L-shaped
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(300, 200),
                new AbsoluteBendpointDto(300, 400))));
        // B: (150,200) → (250,200) — horizontal at y=200
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(150, 200),
                new AbsoluteBendpointDto(250, 200))));
        // C: (300,150) → (300,350) — vertical at x=300
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(300, 150),
                new AbsoluteBendpointDto(300, 350))));

        List<int[]> sourceCenters = List.of(
                new int[]{50, 100}, new int[]{50, 300}, new int[]{200, 50});
        List<int[]> targetCenters = List.of(
                new int[]{400, 500}, new int[]{300, 300}, new int[]{400, 450});

        List<RoutingRect> obstacles = List.of(
                new RoutingRect(0, 0, 500, 10, "top"),
                new RoutingRect(0, 490, 500, 10, "bottom"),
                new RoutingRect(0, 0, 10, 500, "left"),
                new RoutingRect(490, 0, 10, 500, "right"));

        nudger.nudgePaths(ids, bendpointLists, sourceCenters, targetCenters, obstacles);

        // Horizontal group (A's first segment + B): y values should differ
        int yA_h = bendpointLists.get(0).get(0).y(); // A's first bendpoint y
        int yB_h = bendpointLists.get(1).get(0).y(); // B's first bendpoint y
        assertNotEquals("Horizontal group should be nudged apart", yA_h, yB_h);

        // Vertical group (A's second segment + C): x values should differ
        int xA_v = bendpointLists.get(0).get(1).x(); // A's second bendpoint x (was x=300)
        int xC_v = bendpointLists.get(2).get(0).x(); // C's first bendpoint x
        assertNotEquals("Vertical group should be nudged apart", xA_v, xC_v);
    }

    // --- Test 4.9: Obstacle boundary enforcement (AC #4) ---

    @Test
    public void shouldRespectObstacleBoundaries_whenNudging() {
        // Two connections in a corridor with obstacles on both sides
        List<String> ids = List.of("c0", "c1");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(50, 200),
                new AbsoluteBendpointDto(350, 200))));
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(300, 200))));

        List<int[]> sourceCenters = List.of(new int[]{50, 50}, new int[]{100, 50});
        List<int[]> targetCenters = List.of(new int[]{350, 350}, new int[]{300, 350});

        // Obstacles define a corridor from y=150 to y=250
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(0, 50, 400, 100, "above"),   // bottom edge at y=150
                new RoutingRect(0, 250, 400, 100, "below")); // top edge at y=250

        nudger.nudgePaths(ids, bendpointLists, sourceCenters, targetCenters, obstacles);

        // All bendpoints should be within the corridor (y between 150 and 250)
        for (List<AbsoluteBendpointDto> bps : bendpointLists) {
            for (AbsoluteBendpointDto bp : bps) {
                assertTrue("Bendpoint y=" + bp.y() + " should be >= 150 (corridor lower bound)",
                        bp.y() >= 150);
                assertTrue("Bendpoint y=" + bp.y() + " should be <= 250 (corridor upper bound)",
                        bp.y() <= 250);
            }
        }

        // And they should still be separated
        int y0 = bendpointLists.get(0).get(0).y();
        int y1 = bendpointLists.get(1).get(0).y();
        assertNotEquals("Connections should still be separated", y0, y1);
    }

    // --- Test: corridor width computation ---

    @Test
    public void shouldComputeCorridorBounds_fromNearestObstacles() {
        PathOrderer orderer = new PathOrderer();
        EdgeNudger n = new EdgeNudger(8, 30, 500, orderer);

        // Horizontal corridor at y=200, obstacles at y=150 (bottom) and y=250 (top)
        List<PathOrderer.Segment> group = List.of(
                new PathOrderer.Segment(0, 0, 50, 200, 350, 200, true));

        List<RoutingRect> obstacles = List.of(
                new RoutingRect(0, 50, 400, 100, "above"),   // bottom edge at y=150
                new RoutingRect(0, 250, 400, 100, "below")); // top edge at y=250

        EdgeNudger.CorridorBounds bounds = n.computeCorridorBounds(true, 200, group, obstacles);
        // With 10px obstacle margin: lower = 150 + 10 = 160, upper = 250 - 10 = 240
        assertEquals("Corridor lower bound should be 160 (150 + margin)", 160, bounds.lowerBound());
        assertEquals("Corridor upper bound should be 240 (250 - margin)", 240, bounds.upperBound());
        assertEquals("Corridor width should be 240 - 160 = 80", 80, bounds.width());
    }

    @Test
    public void shouldUseSearchRange_whenNoObstaclesNearby() {
        PathOrderer orderer = new PathOrderer();
        EdgeNudger n = new EdgeNudger(8, 30, 500, orderer);

        List<PathOrderer.Segment> group = List.of(
                new PathOrderer.Segment(0, 0, 50, 200, 350, 200, true));

        // No obstacles — search range defaults used, margin applied on each side
        EdgeNudger.CorridorBounds bounds = n.computeCorridorBounds(true, 200, group, List.of());
        assertEquals("No obstacles → corridor width = 2 * searchRange - 2 * margin = 980", 980, bounds.width());
    }

    // --- Story 10-19a: Perpendicular obstacle detection tests ---

    @Test
    public void shouldClampCorridor_whenObstacleStraddlesSharedCoord() {
        // AC #1: Obstacle at (200, 85, 30, 30) straddles y=100 corridor
        PathOrderer orderer = new PathOrderer();
        EdgeNudger n = new EdgeNudger(8, 30, 500, orderer);

        List<PathOrderer.Segment> group = List.of(
                new PathOrderer.Segment(0, 0, 50, 100, 350, 100, true));

        // Obstacle at y=[85,115] straddles sharedCoord=100, x=[200,230] overlaps parallel range
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(200, 85, 30, 30, "straddler"));

        EdgeNudger.CorridorBounds bounds = n.computeCorridorBounds(true, 100, group, obstacles);

        // Straddling obstacle should clamp corridor:
        // nearestBefore = obsBottom=115, nearestAfter = obsTop=85
        // Corridor blocked (negative width)
        assertTrue("Corridor should be blocked (negative or zero width) by straddling obstacle",
                bounds.width() <= 0);
    }

    @Test
    public void shouldNotNudgeIntoObstacle_whenObstacleStraddlesHorizontalCorridor() {
        // AC #1: 3 parallel horizontal connections at y=100, obstacle at (200, 85, 30, 30)
        // Corridor blocked by straddler → segments stay at original y=100 (no nudging)
        List<String> ids = List.of("c0", "c1", "c2");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(50, 100),
                new AbsoluteBendpointDto(350, 100))));
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(300, 100))));
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(150, 100),
                new AbsoluteBendpointDto(250, 100))));

        List<int[]> sourceCenters = List.of(
                new int[]{50, 50}, new int[]{100, 50}, new int[]{150, 50});
        List<int[]> targetCenters = List.of(
                new int[]{350, 150}, new int[]{300, 150}, new int[]{250, 150});

        // Obstacle straddles the corridor at y=100
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(200, 85, 30, 30, "straddler"));

        nudger.nudgePaths(ids, bendpointLists, sourceCenters, targetCenters, obstacles);

        // Corridor blocked → all segments stay at original y=100 (no nudging applied)
        for (int c = 0; c < bendpointLists.size(); c++) {
            for (AbsoluteBendpointDto bp : bendpointLists.get(c)) {
                assertEquals("Connection " + c + " should stay at original y=100 (blocked corridor)",
                        100, bp.y());
            }
        }
    }

    @Test
    public void shouldClampCorridor_whenObstacleBarelyTouchesParallelExtent() {
        // Task 2.1: Obstacle x-range barely overlaps segment x-range
        PathOrderer orderer = new PathOrderer();
        EdgeNudger n = new EdgeNudger(8, 30, 500, orderer);

        // Segments span x=[100,300]
        List<PathOrderer.Segment> group = List.of(
                new PathOrderer.Segment(0, 0, 100, 200, 300, 200, true));

        // Obstacle at x=[295,325] — barely overlaps segment end
        // At y=[180,220] — straddles sharedCoord=200
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(295, 180, 30, 40, "edge-toucher"));

        EdgeNudger.CorridorBounds bounds = n.computeCorridorBounds(true, 200, group, obstacles);

        // Obstacle overlaps parallel range (295 < 300) and straddles → should block
        assertTrue("Barely-touching obstacle should clamp corridor",
                bounds.width() <= 0);
    }

    @Test
    public void shouldClampCorridor_whenObstacleCompletelyBlocksOneSide() {
        // Task 2.2: Perpendicular obstacle completely blocking corridor on one side
        PathOrderer orderer = new PathOrderer();
        EdgeNudger n = new EdgeNudger(8, 30, 500, orderer);

        List<PathOrderer.Segment> group = List.of(
                new PathOrderer.Segment(0, 0, 50, 200, 350, 200, true));

        // Large obstacle above: y=[50,190] (bottom edge at 190, just above corridor)
        // Plus straddling obstacle at y=[195,210]
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(0, 50, 400, 140, "big-above"),      // bottom edge at y=190
                new RoutingRect(100, 195, 200, 15, "straddler"));   // straddles y=200

        EdgeNudger.CorridorBounds bounds = n.computeCorridorBounds(true, 200, group, obstacles);

        // big-above sets nearestBefore=190 (obstacle entirely above)
        // straddler sets nearestBefore=max(190,210)=210, nearestAfter=min(700,195)=195
        // Corridor: [210, 195] → blocked
        assertTrue("Corridor should be blocked by straddling obstacle",
                bounds.width() <= 0);
    }

    @Test
    public void shouldRespectNarrowCorridorBetweenLargeElements() {
        // AC #3: Narrow corridor between two large elements with multiple parallel connections
        List<String> ids = List.of("c0", "c1", "c2");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        // Three horizontal connections at y=200 in the gap between two elements
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(50, 200),
                new AbsoluteBendpointDto(350, 200))));
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(300, 200))));
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(150, 200),
                new AbsoluteBendpointDto(250, 200))));

        List<int[]> sourceCenters = List.of(
                new int[]{50, 50}, new int[]{100, 50}, new int[]{150, 50});
        List<int[]> targetCenters = List.of(
                new int[]{350, 350}, new int[]{300, 350}, new int[]{250, 350});

        // Two large elements defining a narrow corridor at y=200:
        // Top element: y=[0,185] (bottom edge 185, 15px above corridor)
        // Bottom element: y=[215,400] (top edge 215, 15px below corridor)
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(0, 0, 400, 185, "top-element"),
                new RoutingRect(0, 215, 400, 185, "bottom-element"));

        nudger.nudgePaths(ids, bendpointLists, sourceCenters, targetCenters, obstacles);

        // All segments should remain within corridor bounds [185, 215]
        for (int c = 0; c < bendpointLists.size(); c++) {
            for (AbsoluteBendpointDto bp : bendpointLists.get(c)) {
                assertTrue("Connection " + c + " bendpoint y=" + bp.y()
                                + " should be >= 185 (top element bottom edge)",
                        bp.y() >= 185);
                assertTrue("Connection " + c + " bendpoint y=" + bp.y()
                                + " should be <= 215 (bottom element top edge)",
                        bp.y() <= 215);
            }
        }
    }

    @Test
    public void shouldClampToStraddler_whenAdjacentObstacleNearby() {
        // Straddling obstacle + adjacent obstacle — corridor blocked (negative width).
        // nudgeGroup() skips nudging, so segments stay at sharedCoord (no obstacle penetration).
        PathOrderer orderer = new PathOrderer();
        EdgeNudger n = new EdgeNudger(8, 30, 500, orderer);

        List<PathOrderer.Segment> group = List.of(
                new PathOrderer.Segment(0, 0, 50, 200, 350, 200, true));

        // Straddling obstacle at y=[190,210], plus adjacent obstacle at y=[215,300]
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(100, 190, 200, 20, "straddler"),
                new RoutingRect(100, 215, 200, 85, "adjacent-below"));

        EdgeNudger.CorridorBounds bounds = n.computeCorridorBounds(true, 200, group, obstacles);

        // Corridor should be blocked by straddler
        assertTrue("Corridor should be blocked by straddling obstacle",
                bounds.width() <= 0);
        // Segments will be clamped to obsBottom=210 + margin=10 = 220
        assertEquals("Lower bound should be straddler bottom edge + margin", 220, bounds.lowerBound());
    }

    @Test
    public void shouldClampVerticalCorridor_whenObstacleStraddlesSharedCoord() {
        // Vertical corridor version of the straddling test
        PathOrderer orderer = new PathOrderer();
        EdgeNudger n = new EdgeNudger(8, 30, 500, orderer);

        // Vertical segment at x=200
        List<PathOrderer.Segment> group = List.of(
                new PathOrderer.Segment(0, 0, 200, 50, 200, 350, false));

        // Obstacle at x=[185,215] straddles sharedCoord=200
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(185, 150, 30, 50, "v-straddler"));

        EdgeNudger.CorridorBounds bounds = n.computeCorridorBounds(false, 200, group, obstacles);

        assertTrue("Vertical corridor should be blocked by straddling obstacle",
                bounds.width() <= 0);
    }

    // --- Story 10-23: Straddling + adjacent obstacle tests ---

    @Test
    public void shouldNotNudgeIntoAdjacentObstacle_whenStraddlerBlocksCorridor() {
        // AC #1, Task 3.1: Straddling obstacle + adjacent obstacle near far edge
        // Segments must NOT land inside adjacent obstacle
        List<String> ids = List.of("c0", "c1");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(50, 200),
                new AbsoluteBendpointDto(350, 200))));
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(300, 200))));

        List<int[]> sourceCenters = List.of(new int[]{50, 50}, new int[]{100, 50});
        List<int[]> targetCenters = List.of(new int[]{350, 350}, new int[]{300, 350});

        // Straddler at y=[190,210] blocks corridor at y=200
        // Adjacent obstacle at y=[208,260] — overlaps where segments would be clamped
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(100, 190, 200, 20, "straddler"),
                new RoutingRect(100, 208, 200, 52, "adjacent-below"));

        nudger.nudgePaths(ids, bendpointLists, sourceCenters, targetCenters, obstacles);

        // Corridor blocked → segments stay at sharedCoord=200 (inside straddler, but NOT
        // pushed into adjacent obstacle which was the original bug)
        for (int c = 0; c < bendpointLists.size(); c++) {
            for (AbsoluteBendpointDto bp : bendpointLists.get(c)) {
                assertEquals("Connection " + c + " should stay at y=200 (blocked corridor, no nudging)",
                        200, bp.y());
            }
        }
    }

    @Test
    public void shouldTightenLowerBound_whenPerpendicularObstacleNearCorridorEdge() {
        // AC #2, Task 3.2: Perpendicular obstacle near corridor edge tightens bounds
        PathOrderer orderer = new PathOrderer();
        EdgeNudger n = new EdgeNudger(8, 30, 500, orderer);

        List<PathOrderer.Segment> group = List.of(
                new PathOrderer.Segment(0, 0, 50, 200, 350, 200, true));

        // Obstacle above at y=[0,180] sets nearestBefore=180
        // Perpendicular obstacle at y=[175,185] has bottom edge at 185, further tightening nearestBefore
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(0, 0, 400, 180, "above"),       // bottom edge at y=180
                new RoutingRect(200, 175, 50, 10, "perp-near")); // bottom edge at y=185, tightens further

        EdgeNudger.CorridorBounds bounds = n.computeCorridorBounds(true, 200, group, obstacles);

        // Perpendicular obstacle's bottom edge (185) is closer to sharedCoord than "above" (180)
        assertTrue("Lower bound should be >= 185 from perpendicular obstacle",
                bounds.lowerBound() >= 185);
    }

    @Test
    public void shouldCenterOnSharedCoord_whenCorridorCompletelyBlocked() {
        // AC #1, Task 3.3: Multiple straddling obstacles — corridor completely blocked
        List<String> ids = List.of("c0", "c1", "c2");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(50, 200),
                new AbsoluteBendpointDto(350, 200))));
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(300, 200))));
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(150, 200),
                new AbsoluteBendpointDto(250, 200))));

        List<int[]> sourceCenters = List.of(
                new int[]{50, 50}, new int[]{100, 50}, new int[]{150, 50});
        List<int[]> targetCenters = List.of(
                new int[]{350, 350}, new int[]{300, 350}, new int[]{250, 350});

        // Two straddling obstacles completely block the corridor
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(80, 185, 100, 30, "straddler1"),
                new RoutingRect(220, 190, 100, 20, "straddler2"));

        nudger.nudgePaths(ids, bendpointLists, sourceCenters, targetCenters, obstacles);

        // All segments should remain at original sharedCoord=200 (no nudging)
        for (int c = 0; c < bendpointLists.size(); c++) {
            for (AbsoluteBendpointDto bp : bendpointLists.get(c)) {
                assertEquals("Connection " + c + " should stay at y=200 (corridor blocked)",
                        200, bp.y());
            }
        }
    }

    @Test
    public void shouldCenterOnSharedCoord_whenVerticalCorridorBlocked() {
        // AC #1, Task 3.4: Vertical corridor equivalent
        List<String> ids = List.of("c0", "c1");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(200, 50),
                new AbsoluteBendpointDto(200, 350))));
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(200, 100),
                new AbsoluteBendpointDto(200, 300))));

        List<int[]> sourceCenters = List.of(new int[]{50, 50}, new int[]{50, 100});
        List<int[]> targetCenters = List.of(new int[]{350, 350}, new int[]{350, 300});

        // Straddling obstacle at x=[190,210] blocks vertical corridor at x=200
        // Adjacent obstacle at x=[208,280]
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(190, 100, 20, 200, "v-straddler"),
                new RoutingRect(208, 100, 72, 200, "v-adjacent"));

        nudger.nudgePaths(ids, bendpointLists, sourceCenters, targetCenters, obstacles);

        // All segments should stay at x=200 (blocked corridor, no nudging)
        for (int c = 0; c < bendpointLists.size(); c++) {
            for (AbsoluteBendpointDto bp : bendpointLists.get(c)) {
                assertEquals("Connection " + c + " should stay at x=200 (corridor blocked)",
                        200, bp.x());
            }
        }
    }

    // --- Story 10-25: Margin-aware corridor bounds (AC #2) ---

    @Test
    public void shouldRespectObstacleMargin_whenComputingCorridorBounds() {
        // Horizontal corridor at y=200, obstacle below at y=[250,350]
        // Raw corridor would allow nudging up to y=250.
        // With 10px margin, corridor should stop at y=240 (250-10).
        PathOrderer.Segment seg = new PathOrderer.Segment(
                0, 0, 100, 200, 400, 200, true);
        List<PathOrderer.Segment> group = List.of(seg);

        // Obstacle above at y=[50,100], obstacle below at y=[250,350]
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(100, 50, 200, 50, "above"),    // bottom edge at y=100
                new RoutingRect(100, 250, 200, 100, "below")); // top edge at y=250

        EdgeNudger.CorridorBounds bounds =
                nudger.computeCorridorBounds(true, 200, group, obstacles);

        // With margin=10: lower bound = 100 + 10 = 110, upper bound = 250 - 10 = 240
        assertTrue("Lower bound should respect margin (>= 110), got " + bounds.lowerBound(),
                bounds.lowerBound() >= 110);
        assertTrue("Upper bound should respect margin (<= 240), got " + bounds.upperBound(),
                bounds.upperBound() <= 240);
        assertTrue("Corridor should have positive width", bounds.width() > 0);
    }

    @Test
    public void shouldNotNudgeIntoObstacleMargin_whenNarrowGap() {
        // Two obstacles with a narrow gap — nudged segments must stay within margin-adjusted bounds
        // Top obstacle bottom at y=150, bottom obstacle top at y=200
        // Gap is only 50px. With 10px margin on each side, usable corridor is 30px.
        List<String> ids = List.of("c0", "c1");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(100, 175),
                new AbsoluteBendpointDto(400, 175))));
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(100, 175),
                new AbsoluteBendpointDto(400, 175))));

        List<int[]> sourceCenters = List.of(new int[]{50, 175}, new int[]{50, 175});
        List<int[]> targetCenters = List.of(new int[]{450, 175}, new int[]{450, 175});

        // Narrow gap between obstacles
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(50, 50, 400, 100, "top"),       // bottom at y=150
                new RoutingRect(50, 200, 400, 100, "bottom"));  // top at y=200

        nudger.nudgePaths(ids, bendpointLists, sourceCenters, targetCenters, obstacles);

        // All nudged bendpoints should respect obstacle margins:
        // y must be >= 160 (150 + 10) and <= 190 (200 - 10)
        for (int c = 0; c < bendpointLists.size(); c++) {
            for (AbsoluteBendpointDto bp : bendpointLists.get(c)) {
                assertTrue("Connection " + c + " BP y=" + bp.y()
                        + " should be >= 160 (margin from top obstacle)",
                        bp.y() >= 160);
                assertTrue("Connection " + c + " BP y=" + bp.y()
                        + " should be <= 190 (margin from bottom obstacle)",
                        bp.y() <= 190);
            }
        }
    }
}
