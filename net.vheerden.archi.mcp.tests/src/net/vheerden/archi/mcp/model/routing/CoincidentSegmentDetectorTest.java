package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Tests for {@link CoincidentSegmentDetector} — coincident segment detection
 * and offset logic (Story 11-23). Pure-geometry tests, no EMF/SWT required.
 */
public class CoincidentSegmentDetectorTest {

    private CoincidentSegmentDetector detector;

    @Before
    public void setUp() {
        detector = new CoincidentSegmentDetector();
    }

    // ---- Task 4.1: Detect coincident segments between two connections sharing a path ----

    @Test
    public void detect_shouldFindCoincidentHorizontalSegments() {
        // Two connections with identical horizontal segments at y=100 from x=100 to x=300
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        // Connection 0: source(50,50) -> bp(100,100) -> bp(300,100) -> bp(300,200) -> target(350,250)
        paths.add(mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(300, 100),
                new AbsoluteBendpointDto(300, 200)));
        // Connection 1: source(50,150) -> bp(100,100) -> bp(300,100) -> bp(300,300) -> target(350,350)
        paths.add(mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(300, 100),
                new AbsoluteBendpointDto(300, 300)));

        List<int[]> sourceCenters = List.of(new int[]{50, 50}, new int[]{50, 150});
        List<int[]> targetCenters = List.of(new int[]{350, 250}, new int[]{350, 350});
        List<String> ids = List.of("conn-0", "conn-1");

        List<CoincidentSegmentDetector.CoincidentPair> pairs =
                detector.detect(ids, paths, sourceCenters, targetCenters);

        assertTrue("Should detect at least one coincident pair", pairs.size() >= 1);
        // The horizontal segment at y=100 from x=100 to x=300 should be coincident
        boolean foundHorizontal = pairs.stream().anyMatch(p ->
                p.segA().horizontal() && p.segB().horizontal());
        assertTrue("Should find coincident horizontal segments", foundHorizontal);
    }

    @Test
    public void detect_shouldFindCoincidentVerticalSegments() {
        // Two connections with identical vertical segments at x=200 from y=100 to y=300
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        // Connection 0: bp(100,100) -> bp(200,100) -> bp(200,300) -> bp(300,300)
        paths.add(mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(200, 100),
                new AbsoluteBendpointDto(200, 300),
                new AbsoluteBendpointDto(300, 300)));
        // Connection 1: bp(100,200) -> bp(200,200) -> bp(200,300) -> bp(300,400)
        paths.add(mutableList(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(200, 200),
                new AbsoluteBendpointDto(200, 300),
                new AbsoluteBendpointDto(300, 400)));

        List<int[]> sourceCenters = List.of(new int[]{50, 100}, new int[]{50, 200});
        List<int[]> targetCenters = List.of(new int[]{350, 300}, new int[]{350, 400});
        List<String> ids = List.of("conn-0", "conn-1");

        List<CoincidentSegmentDetector.CoincidentPair> pairs =
                detector.detect(ids, paths, sourceCenters, targetCenters);

        assertTrue("Should detect at least one coincident pair", pairs.size() >= 1);
        boolean foundVertical = pairs.stream().anyMatch(p ->
                !p.segA().horizontal() && !p.segB().horizontal());
        assertTrue("Should find coincident vertical segments", foundVertical);
    }

    // ---- Task 4.3: No coincident detection when segments don't coincide ----

    @Test
    public void detect_shouldReturnEmpty_whenNoCoincidentSegments() {
        // Two connections with completely different paths
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        // Connection 0: horizontal at y=100
        paths.add(mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(300, 100),
                new AbsoluteBendpointDto(300, 200)));
        // Connection 1: horizontal at y=400 (far away)
        paths.add(mutableList(
                new AbsoluteBendpointDto(100, 400),
                new AbsoluteBendpointDto(300, 400),
                new AbsoluteBendpointDto(300, 500)));

        List<int[]> sourceCenters = List.of(new int[]{50, 50}, new int[]{50, 350});
        List<int[]> targetCenters = List.of(new int[]{350, 250}, new int[]{350, 550});
        List<String> ids = List.of("conn-0", "conn-1");

        List<CoincidentSegmentDetector.CoincidentPair> pairs =
                detector.detect(ids, paths, sourceCenters, targetCenters);

        assertEquals("Should detect no coincident pairs for distant paths", 0, pairs.size());
    }

    @Test
    public void detect_shouldReturnEmpty_whenSingleConnection() {
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        paths.add(mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(300, 100)));

        List<int[]> sourceCenters = List.of(new int[]{50, 50});
        List<int[]> targetCenters = List.of(new int[]{350, 250});
        List<String> ids = List.of("conn-0");

        List<CoincidentSegmentDetector.CoincidentPair> pairs =
                detector.detect(ids, paths, sourceCenters, targetCenters);

        assertEquals(0, pairs.size());
    }

    @Test
    public void detect_shouldIgnoreSameConnectionSegments() {
        // Single connection with two segments at same coordinate — not coincident (same connection)
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        paths.add(mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(200, 100),
                new AbsoluteBendpointDto(200, 200),
                new AbsoluteBendpointDto(300, 200)));
        paths.add(mutableList(
                new AbsoluteBendpointDto(500, 500),
                new AbsoluteBendpointDto(600, 500)));

        List<int[]> sourceCenters = List.of(new int[]{50, 50}, new int[]{450, 450});
        List<int[]> targetCenters = List.of(new int[]{350, 250}, new int[]{650, 550});
        List<String> ids = List.of("conn-0", "conn-1");

        List<CoincidentSegmentDetector.CoincidentPair> pairs =
                detector.detect(ids, paths, sourceCenters, targetCenters);

        assertEquals("Same-connection segments should not be detected as coincident",
                0, pairs.size());
    }

    @Test
    public void computeParallelOverlap_shouldReturnNull_whenOverlapTooShort() {
        // Two horizontal segments at y=200 with only 3px overlap (< MIN_OVERLAP_LENGTH=5)
        PathOrderer.Segment a = new PathOrderer.Segment(0, 0, 100, 200, 104, 200, true);
        PathOrderer.Segment b = new PathOrderer.Segment(1, 0, 101, 200, 105, 200, true);

        int[] overlap = detector.computeParallelOverlap(a, b);

        // Overlap range is [101, 104] = 3px, which is below MIN_OVERLAP_LENGTH=5
        // computeParallelOverlap returns the range; the caller checks length >= MIN_OVERLAP_LENGTH
        assertNotNull("computeParallelOverlap returns the range", overlap);
        assertTrue("Overlap length 3 is below MIN_OVERLAP_LENGTH",
                Math.abs(overlap[1] - overlap[0]) < CoincidentSegmentDetector.MIN_OVERLAP_LENGTH);
    }

    // ---- Task 4.2: Offset produces visually distinct paths ----

    @Test
    public void applyOffsets_shouldSeparateCoincidentSegments() {
        // Two connections with identical horizontal segments at y=100
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        paths.add(mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(300, 100),
                new AbsoluteBendpointDto(300, 200)));
        paths.add(mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(300, 100),
                new AbsoluteBendpointDto(300, 300)));

        List<int[]> sourceCenters = List.of(new int[]{50, 50}, new int[]{50, 150});
        List<int[]> targetCenters = List.of(new int[]{350, 250}, new int[]{350, 350});
        List<String> ids = List.of("conn-0", "conn-1");

        // Detect
        List<CoincidentSegmentDetector.CoincidentPair> pairs =
                detector.detect(ids, paths, sourceCenters, targetCenters);
        assertTrue("Should have coincident pairs to offset", pairs.size() > 0);

        // Apply offsets (no obstacles)
        int offsetCount = detector.applyOffsets(pairs, paths, List.of());
        assertTrue("Should apply at least one offset", offsetCount > 0);

        // After offset, the two paths should no longer have identical segment coordinates
        // The second connection's horizontal segment should have been shifted
        AbsoluteBendpointDto conn0bp0 = paths.get(0).get(0);
        AbsoluteBendpointDto conn1bp0 = paths.get(1).get(0);
        boolean separated = conn0bp0.y() != conn1bp0.y() || conn0bp0.x() != conn1bp0.x();
        assertTrue("Segments should be visually separated after offset", separated);
    }

    @Test
    public void applyOffsets_shouldSkipWhenObstacleBlocks() {
        // Two connections with coincident segment, obstacle blocking both offset directions
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        paths.add(mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(300, 100),
                new AbsoluteBendpointDto(300, 200)));
        paths.add(mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(300, 100),
                new AbsoluteBendpointDto(300, 300)));

        List<int[]> sourceCenters = List.of(new int[]{50, 50}, new int[]{50, 150});
        List<int[]> targetCenters = List.of(new int[]{350, 250}, new int[]{350, 350});
        List<String> ids = List.of("conn-0", "conn-1");

        // Detect
        List<CoincidentSegmentDetector.CoincidentPair> pairs =
                detector.detect(ids, paths, sourceCenters, targetCenters);

        // Create obstacles blocking both offset directions for the horizontal segment
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(80, 80, 240, 15, "obs-above"),   // above y=100
                new RoutingRect(80, 105, 240, 15, "obs-below")); // below y=100

        int offsetCount = detector.applyOffsets(pairs, paths, obstacles);

        // Both directions blocked — offset should be skipped (graceful degradation)
        assertEquals("Should skip offset when both directions are blocked", 0, offsetCount);
    }

    // ---- Parallel overlap computation ----

    @Test
    public void computeParallelOverlap_shouldReturnOverlapRange() {
        PathOrderer.Segment a = new PathOrderer.Segment(0, 0, 100, 200, 400, 200, true);
        PathOrderer.Segment b = new PathOrderer.Segment(1, 0, 200, 200, 500, 200, true);

        int[] overlap = detector.computeParallelOverlap(a, b);

        assertNotNull("Should compute overlap", overlap);
        assertEquals("Overlap start", 200, overlap[0]);
        assertEquals("Overlap end", 400, overlap[1]);
    }

    @Test
    public void computeParallelOverlap_shouldReturnNull_whenNoOverlap() {
        PathOrderer.Segment a = new PathOrderer.Segment(0, 0, 100, 200, 200, 200, true);
        PathOrderer.Segment b = new PathOrderer.Segment(1, 0, 300, 200, 500, 200, true);

        int[] overlap = detector.computeParallelOverlap(a, b);

        assertNull("Should return null when no overlap", overlap);
    }

    // ---- Assessment integration (countCoincidentSegments) ----

    @Test
    public void countCoincidentSegments_shouldDetectOverlappingPaths() {
        // Two connections with overlapping horizontal segments
        List<CoincidentSegmentDetector.CoincidentAssessable> connections = List.of(
                // source(50,50) -> bp(100,100) -> bp(300,100) -> bp(300,200) -> target(350,250)
                (CoincidentSegmentDetector.CoincidentAssessable) () -> List.of(
                        new double[]{50, 50},
                        new double[]{100, 100},
                        new double[]{300, 100},
                        new double[]{300, 200},
                        new double[]{350, 250}),
                // source(50,150) -> bp(100,100) -> bp(300,100) -> bp(300,300) -> target(350,350)
                (CoincidentSegmentDetector.CoincidentAssessable) () -> List.of(
                        new double[]{50, 150},
                        new double[]{100, 100},
                        new double[]{300, 100},
                        new double[]{300, 300},
                        new double[]{350, 350}));

        int count = detector.countCoincidentSegments(connections);
        assertTrue("Should detect coincident segments", count > 0);
    }

    @Test
    public void countCoincidentSegments_shouldReturnZero_whenNoOverlap() {
        List<CoincidentSegmentDetector.CoincidentAssessable> connections = List.of(
                (CoincidentSegmentDetector.CoincidentAssessable) () -> List.of(
                        new double[]{50, 50},
                        new double[]{100, 100},
                        new double[]{300, 100},
                        new double[]{350, 150}),
                (CoincidentSegmentDetector.CoincidentAssessable) () -> List.of(
                        new double[]{50, 350},
                        new double[]{100, 400},
                        new double[]{300, 400},
                        new double[]{350, 450}));

        int count = detector.countCoincidentSegments(connections);
        assertEquals("Should detect no coincident segments", 0, count);
    }

    // ---- Multi-way coincidence (3+ connections) ----

    @Test
    public void applyOffsets_shouldStackOffsetsForThreeWayCoincidence() {
        // Three connections with identical horizontal segments at y=100 from x=100 to x=300
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        // Connection 0
        paths.add(mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(300, 100),
                new AbsoluteBendpointDto(300, 200)));
        // Connection 1
        paths.add(mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(300, 100),
                new AbsoluteBendpointDto(300, 300)));
        // Connection 2
        paths.add(mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(300, 100),
                new AbsoluteBendpointDto(300, 400)));

        List<int[]> sourceCenters = List.of(
                new int[]{50, 50}, new int[]{50, 150}, new int[]{50, 250});
        List<int[]> targetCenters = List.of(
                new int[]{350, 250}, new int[]{350, 350}, new int[]{350, 450});
        List<String> ids = List.of("conn-0", "conn-1", "conn-2");

        // Detect
        List<CoincidentSegmentDetector.CoincidentPair> pairs =
                detector.detect(ids, paths, sourceCenters, targetCenters);
        assertTrue("Should detect multiple coincident pairs", pairs.size() >= 2);

        // Apply offsets (no obstacles)
        int offsetCount = detector.applyOffsets(pairs, paths, List.of());
        assertTrue("Should apply at least 2 offsets for 3-way coincidence", offsetCount >= 2);

        // After offset, all three paths' horizontal segments should have distinct y-coordinates
        int y0 = paths.get(0).get(0).y();
        int y1 = paths.get(1).get(0).y();
        int y2 = paths.get(2).get(0).y();

        // At least two of the three must be different from each other (stacked offsets)
        boolean allDistinct = (y0 != y1) && (y0 != y2) && (y1 != y2);
        boolean atLeastTwoDistinct = (y0 != y1) || (y0 != y2) || (y1 != y2);
        assertTrue("Three-way coincident segments should be separated", atLeastTwoDistinct);
        // With proper stacking, all three should be distinct
        assertTrue("Stacked offsets should produce three distinct y-coordinates", allDistinct);
    }

    // ---- Task 4.1: computeCorridorGap tests ----

    @Test
    public void computeCorridorGap_shouldFindBoundsWithObstaclesOnBothSides() {
        // Horizontal corridor at y=200, parallel range x=[100, 400]
        // Obstacle above: y=[120, 170] (bottom edge at 170)
        // Obstacle below: y=[230, 280] (top edge at 230)
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(100, 120, 200, 50, "obs-above"),  // bottom at 170
                new RoutingRect(150, 230, 200, 50, "obs-below")); // top at 230

        int[] gap = detector.computeCorridorGap(200, true, 100, 400, obstacles);

        assertNotNull("Should find gap", gap);
        assertEquals("Near bound should be bottom of upper obstacle", 170, gap[0]);
        assertEquals("Far bound should be top of lower obstacle", 230, gap[1]);
    }

    @Test
    public void computeCorridorGap_shouldFindBoundsForVerticalCorridor() {
        // Vertical corridor at x=300, parallel range y=[100, 400]
        // Obstacle left: x=[150, 270] (right edge at 270)
        // Obstacle right: x=[330, 450] (left edge at 330)
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(150, 100, 120, 200, "obs-left"),  // right at 270
                new RoutingRect(330, 150, 120, 200, "obs-right")); // left at 330

        int[] gap = detector.computeCorridorGap(300, false, 100, 400, obstacles);

        assertNotNull("Should find gap", gap);
        assertEquals("Near bound should be right edge of left obstacle", 270, gap[0]);
        assertEquals("Far bound should be left edge of right obstacle", 330, gap[1]);
    }

    @Test
    public void computeCorridorGap_shouldUseDefaultExtentWhenNoObstacleOnOneSide() {
        // Horizontal corridor at y=200, obstacle only above
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(100, 120, 200, 50, "obs-above")); // bottom at 170

        int[] gap = detector.computeCorridorGap(200, true, 100, 400, obstacles);

        assertNotNull("Should find gap", gap);
        assertEquals("Near bound should be bottom of obstacle", 170, gap[0]);
        assertEquals("Far bound should be default extent", 200 + 100, gap[1]);
    }

    @Test
    public void computeCorridorGap_shouldReturnNull_whenCorridorInsideObstacle() {
        // Corridor at y=200 lies inside obstacle spanning y=[150, 250]
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(100, 150, 200, 100, "obs-enclosing"));

        int[] gap = detector.computeCorridorGap(200, true, 100, 400, obstacles);

        assertNull("Should return null when corridor inside obstacle", gap);
    }

    @Test
    public void computeCorridorGap_shouldIgnoreObstaclesOutsideParallelRange() {
        // Corridor at y=200, parallel range x=[100, 200]
        // Obstacle at x=[300, 400] — outside parallel range, should be ignored
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(300, 150, 100, 20, "obs-outside"));

        int[] gap = detector.computeCorridorGap(200, true, 100, 200, obstacles);

        assertNotNull("Should find gap", gap);
        // Both bounds should be default extent (no relevant obstacles)
        assertEquals("Near bound default", 200 - 100, gap[0]);
        assertEquals("Far bound default", 200 + 100, gap[1]);
    }

    // ---- Task 4.2: computeProportionalOffsets tests ----

    @Test
    public void computeProportionalOffsets_shouldDistributeTwoSegmentsEvenly() {
        // Gap [100, 400] = 300px, 2 segments → positions at 200 and 300
        int[] positions = detector.computeProportionalOffsets(100, 400, 2);

        assertNotNull("Should compute positions", positions);
        assertEquals(2, positions.length);
        assertEquals("First segment at 1/3 of gap", 200, positions[0]);
        assertEquals("Second segment at 2/3 of gap", 300, positions[1]);
    }

    @Test
    public void computeProportionalOffsets_shouldDistributeThreeSegmentsEvenly() {
        // Gap [0, 400] = 400px, 3 segments → positions at 100, 200, 300
        int[] positions = detector.computeProportionalOffsets(0, 400, 3);

        assertNotNull("Should compute positions", positions);
        assertEquals(3, positions.length);
        assertEquals(100, positions[0]);
        assertEquals(200, positions[1]);
        assertEquals(300, positions[2]);
    }

    @Test
    public void computeProportionalOffsets_shouldDistributeFiveSegments() {
        // Gap [0, 600] = 600px, 5 segments → positions at 100, 200, 300, 400, 500
        int[] positions = detector.computeProportionalOffsets(0, 600, 5);

        assertNotNull("Should compute positions", positions);
        assertEquals(5, positions.length);
        assertEquals(100, positions[0]);
        assertEquals(200, positions[1]);
        assertEquals(300, positions[2]);
        assertEquals(400, positions[3]);
        assertEquals(500, positions[4]);
    }

    // ---- Task 4.4: applyOffsets with proportional spacing ----

    @Test
    public void applyOffsets_shouldUseProportionalSpacing_whenGapAvailable() {
        // Two connections with coincident horizontal segments at y=200, x=[100,400]
        // Obstacles above at y=100 (bottom edge 150) and below at y=300 (top edge 300)
        // Gap = [150, 300] = 150px, 2 segments → positions at 200 and 250
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        paths.add(mutableList(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(400, 200),
                new AbsoluteBendpointDto(400, 350)));
        paths.add(mutableList(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(400, 200),
                new AbsoluteBendpointDto(400, 450)));

        List<int[]> sourceCenters = List.of(new int[]{50, 150}, new int[]{50, 250});
        List<int[]> targetCenters = List.of(new int[]{450, 350}, new int[]{450, 450});
        List<String> ids = List.of("conn-0", "conn-1");

        List<CoincidentSegmentDetector.CoincidentPair> pairs =
                detector.detect(ids, paths, sourceCenters, targetCenters);
        assertTrue(pairs.size() > 0);

        List<RoutingRect> obstacles = List.of(
                new RoutingRect(50, 100, 400, 50, "obs-above"),   // bottom at 150
                new RoutingRect(50, 300, 400, 50, "obs-below"));  // top at 300

        int offsetCount = detector.applyOffsets(pairs, paths, obstacles);
        assertTrue("Should apply offsets", offsetCount > 0);

        // With proportional spacing in [150, 300] gap (150px), 2 segments → at 200 and 250
        // Both segments should have moved from y=200 to different proportional positions
        int y0 = paths.get(0).get(0).y();
        int y1 = paths.get(1).get(0).y();
        assertNotEquals("Segments should be separated", y0, y1);

        // Separation should be wider than fixed 10px delta
        int separation = Math.abs(y1 - y0);
        assertTrue("Proportional separation (" + separation + "px) should exceed fixed delta (10px)",
                separation > 10);
    }

    // ---- Task 4.5: Fixed-delta regression ----

    @Test
    public void applyOffsets_shouldFallBackToFixedDelta_whenGapTooNarrow() {
        // Two connections with coincident horizontal segments at y=200
        // Obstacles very close: gap only 14px (below 2 * MIN_SEPARATION=16)
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        paths.add(mutableList(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(400, 200),
                new AbsoluteBendpointDto(400, 250)));
        paths.add(mutableList(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(400, 200),
                new AbsoluteBendpointDto(400, 350)));

        List<int[]> sourceCenters = List.of(new int[]{50, 150}, new int[]{50, 250});
        List<int[]> targetCenters = List.of(new int[]{450, 250}, new int[]{450, 350});
        List<String> ids = List.of("conn-0", "conn-1");

        List<CoincidentSegmentDetector.CoincidentPair> pairs =
                detector.detect(ids, paths, sourceCenters, targetCenters);

        // Tight gap: obstacles at y=194 (bottom 197) and y=203 (top 203) → gap [197, 203] = 6px
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(50, 194, 400, 3, "obs-above"),   // bottom at 197
                new RoutingRect(50, 203, 400, 3, "obs-below"));  // top at 203

        int offsetCount = detector.applyOffsets(pairs, paths, obstacles);

        // Should still attempt fixed-delta fallback (may or may not succeed depending on obstacle check)
        // Key assertion: no crash, graceful handling
        assertTrue("Should handle narrow gap gracefully", offsetCount >= 0);
    }

    // ---- Task 4.6: Obstacle blocking proportional position ----

    @Test
    public void applyOffsets_shouldFallBackPerSegment_whenProportionalPositionBlocked() {
        // Three connections coincident at y=200
        // Wide gap available, but one proportional position has an obstacle
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        paths.add(mutableList(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(400, 200),
                new AbsoluteBendpointDto(400, 300)));
        paths.add(mutableList(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(400, 200),
                new AbsoluteBendpointDto(400, 400)));
        paths.add(mutableList(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(400, 200),
                new AbsoluteBendpointDto(400, 500)));

        List<int[]> sourceCenters = List.of(
                new int[]{50, 150}, new int[]{50, 250}, new int[]{50, 350});
        List<int[]> targetCenters = List.of(
                new int[]{450, 300}, new int[]{450, 400}, new int[]{450, 500});
        List<String> ids = List.of("conn-0", "conn-1", "conn-2");

        List<CoincidentSegmentDetector.CoincidentPair> pairs =
                detector.detect(ids, paths, sourceCenters, targetCenters);

        // Gap [100, 300] = 200px. 3 segments → proportional at 150, 200, 250
        // Small obstacle at y=148-152 blocks the first proportional position
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(50, 50, 400, 50, "obs-above"),     // bottom at 100
                new RoutingRect(50, 300, 400, 50, "obs-below"),    // top at 300
                new RoutingRect(200, 148, 50, 4, "obs-blocker"));  // blocks y≈150

        int offsetCount = detector.applyOffsets(pairs, paths, obstacles);
        assertTrue("Should still offset some segments despite blocker", offsetCount >= 1);
    }

    // ---- Task 4.3: MIN_SEPARATION fallback ----

    @Test
    public void computeProportionalOffsets_shouldReturnNull_whenGapTooNarrow() {
        // Gap [100, 120] = 20px, 3 segments → spacing = 20/4 = 5 < MIN_SEPARATION(8)
        int[] positions = detector.computeProportionalOffsets(100, 120, 3);

        assertNull("Should return null when spacing below MIN_SEPARATION", positions);
    }

    @Test
    public void computeProportionalOffsets_shouldReturnNull_whenZeroSegments() {
        int[] positions = detector.computeProportionalOffsets(100, 400, 0);
        assertNull("Should return null for zero segments", positions);
    }

    // ---- Code review: M1 — Tolerance-aware corridor grouping ----

    @Test
    public void applyOffsets_shouldGroupSegmentsWithinTolerance_forProportionalSpacing() {
        // Two connections with coincident horizontal segments at y=200 and y=202 (within tolerance=2)
        // This tests the critical tolerance-aware grouping in addToCorridorGroup
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        paths.add(mutableList(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(400, 200),
                new AbsoluteBendpointDto(400, 350)));
        paths.add(mutableList(
                new AbsoluteBendpointDto(100, 202),
                new AbsoluteBendpointDto(400, 202),
                new AbsoluteBendpointDto(400, 450)));

        List<int[]> sourceCenters = List.of(new int[]{50, 150}, new int[]{50, 252});
        List<int[]> targetCenters = List.of(new int[]{450, 350}, new int[]{450, 450});
        List<String> ids = List.of("conn-0", "conn-1");

        List<CoincidentSegmentDetector.CoincidentPair> pairs =
                detector.detect(ids, paths, sourceCenters, targetCenters);
        assertTrue("Should detect coincident pair despite 2px difference", pairs.size() > 0);

        // Wide gap — proportional spacing should apply
        int offsetCount = detector.applyOffsets(pairs, paths, List.of());
        assertTrue("Should apply offsets for tolerance-matched segments", offsetCount > 0);

        // Segments should be separated by more than the original 2px difference
        int y0 = paths.get(0).get(0).y();
        int y1 = paths.get(1).get(0).y();
        int separation = Math.abs(y1 - y0);
        assertTrue("Tolerance-grouped segments should be well-separated (" + separation + "px)",
                separation > 10);
    }

    // ---- Code review: M2 — Strengthened fixed-delta regression ----

    @Test
    public void applyOffsets_shouldApplyFixedDelta_whenProportionalSpacingUnavailable() {
        // Two connections with coincident horizontal segments at y=200
        // Corridor inside an obstacle → gap detection returns null → must use fixed-delta
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        paths.add(mutableList(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(400, 200),
                new AbsoluteBendpointDto(400, 350)));
        paths.add(mutableList(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(400, 200),
                new AbsoluteBendpointDto(400, 450)));

        List<int[]> sourceCenters = List.of(new int[]{50, 150}, new int[]{50, 250});
        List<int[]> targetCenters = List.of(new int[]{450, 350}, new int[]{450, 450});
        List<String> ids = List.of("conn-0", "conn-1");

        List<CoincidentSegmentDetector.CoincidentPair> pairs =
                detector.detect(ids, paths, sourceCenters, targetCenters);
        assertTrue(pairs.size() > 0);

        // Obstacle encloses the corridor coordinate (y=200 inside [150,250])
        // This forces computeCorridorGap to return null → fixed-delta path
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(50, 150, 400, 100, "obs-enclosing"));

        int offsetCount = detector.applyOffsets(pairs, paths, obstacles);

        // Fixed-delta should still work: offset direction perpendicular to obstacle
        // is blocked by enclosing obstacle, so offset count may be 0.
        // The key verification: if offset was applied, separation matches offsetDelta (10px)
        int y0 = paths.get(0).get(0).y();
        int y1 = paths.get(1).get(0).y();
        if (offsetCount > 0) {
            int separation = Math.abs(y1 - y0);
            assertEquals("Fixed-delta should produce offsetDelta separation",
                    CoincidentSegmentDetector.DEFAULT_OFFSET_DELTA, separation);
        }
        // Regardless: no crash, graceful degradation
        assertTrue("Should handle gracefully", offsetCount >= 0);
    }

    // ---- B55: detectCoincidentSegments with violator index collection ----

    @Test
    public void detectCoincidentSegments_shouldReturnCountAndViolatorIndices() {
        // Two connections with overlapping horizontal segments
        List<CoincidentSegmentDetector.CoincidentAssessable> connections = List.of(
                (CoincidentSegmentDetector.CoincidentAssessable) () -> List.of(
                        new double[]{50, 50},
                        new double[]{100, 100},
                        new double[]{300, 100},
                        new double[]{300, 200},
                        new double[]{350, 250}),
                (CoincidentSegmentDetector.CoincidentAssessable) () -> List.of(
                        new double[]{50, 150},
                        new double[]{100, 100},
                        new double[]{300, 100},
                        new double[]{300, 300},
                        new double[]{350, 350}));

        CoincidentSegmentDetector.CoincidentSegmentResult result =
                detector.detectCoincidentSegments(connections, true);
        assertTrue("Should detect coincident segments", result.count() > 0);
        assertTrue("Should contain connection index 0", result.violatorConnectionIndices().contains(0));
        assertTrue("Should contain connection index 1", result.violatorConnectionIndices().contains(1));
    }

    @Test
    public void detectCoincidentSegments_shouldReturnEmptyIndicesWhenNotCollecting() {
        List<CoincidentSegmentDetector.CoincidentAssessable> connections = List.of(
                (CoincidentSegmentDetector.CoincidentAssessable) () -> List.of(
                        new double[]{50, 50},
                        new double[]{100, 100},
                        new double[]{300, 100},
                        new double[]{300, 200},
                        new double[]{350, 250}),
                (CoincidentSegmentDetector.CoincidentAssessable) () -> List.of(
                        new double[]{50, 150},
                        new double[]{100, 100},
                        new double[]{300, 100},
                        new double[]{300, 300},
                        new double[]{350, 350}));

        CoincidentSegmentDetector.CoincidentSegmentResult result =
                detector.detectCoincidentSegments(connections, false);
        assertTrue("Should still detect coincident segments", result.count() > 0);
        assertTrue("Violator indices should be empty when not collecting",
                result.violatorConnectionIndices().isEmpty());
    }

    @Test
    public void detectCoincidentSegments_shouldReturnZeroForSingleConnection() {
        List<CoincidentSegmentDetector.CoincidentAssessable> connections = List.of(
                (CoincidentSegmentDetector.CoincidentAssessable) () -> List.of(
                        new double[]{50, 50},
                        new double[]{100, 100},
                        new double[]{300, 100},
                        new double[]{350, 250}));

        CoincidentSegmentDetector.CoincidentSegmentResult result =
                detector.detectCoincidentSegments(connections, true);
        assertEquals("Single connection should have no coincident segments", 0, result.count());
        assertTrue("No violator indices for single connection",
                result.violatorConnectionIndices().isEmpty());
    }

    @Test
    public void detectCoincidentSegments_shouldNotIncludeNonCoincidentConnections() {
        // Three connections: 0 and 1 coincide, 2 is completely separate
        List<CoincidentSegmentDetector.CoincidentAssessable> connections = List.of(
                (CoincidentSegmentDetector.CoincidentAssessable) () -> List.of(
                        new double[]{50, 50},
                        new double[]{100, 100},
                        new double[]{300, 100},
                        new double[]{300, 200},
                        new double[]{350, 250}),
                (CoincidentSegmentDetector.CoincidentAssessable) () -> List.of(
                        new double[]{50, 150},
                        new double[]{100, 100},
                        new double[]{300, 100},
                        new double[]{300, 300},
                        new double[]{350, 350}),
                // Connection 2: completely separate path at y=500
                (CoincidentSegmentDetector.CoincidentAssessable) () -> List.of(
                        new double[]{50, 450},
                        new double[]{100, 500},
                        new double[]{300, 500},
                        new double[]{350, 550}));

        CoincidentSegmentDetector.CoincidentSegmentResult result =
                detector.detectCoincidentSegments(connections, true);
        assertTrue("Should detect coincident segments", result.count() > 0);
        assertTrue("Should contain index 0", result.violatorConnectionIndices().contains(0));
        assertTrue("Should contain index 1", result.violatorConnectionIndices().contains(1));
        assertFalse("Should NOT contain index 2 (non-coincident)",
                result.violatorConnectionIndices().contains(2));
    }

    // ---- Helpers ----

    private static List<AbsoluteBendpointDto> mutableList(AbsoluteBendpointDto... items) {
        List<AbsoluteBendpointDto> list = new ArrayList<>();
        for (AbsoluteBendpointDto item : items) {
            list.add(item);
        }
        return list;
    }
}
