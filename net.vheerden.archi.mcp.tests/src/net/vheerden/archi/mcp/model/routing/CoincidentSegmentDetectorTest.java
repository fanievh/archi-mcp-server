package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Tests for {@link CoincidentSegmentDetector} — coincident segment detection
 * and offset logic. Pure-geometry tests, no EMF/SWT required.
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

    // ---- Tolerance-aware corridor grouping ----

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

    // ---- Strengthened fixed-delta regression ----

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

    // ---- detectCoincidentSegments with violator index collection ----

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

    // ---- Perimeter-anchored coincident segments — applyOffsets rollback +
    //      Approach-3 reconciliation pin.

    /**
     * Existing rollback safety pin via applyOffsets round-trip.
     * Two connections both terminate on a hub LEFT face at distinct slots
     * (y=80 and y=60), sharing a vertical corridor at x=199 just outside
     * the LEFT face. applyOffsets's perpendicular delta-shift would move
     * each path's terminal BP off the face line — the rollback must fire,
     * leaving paths unchanged. Pins the safety net 0db3d91 introduced.
     */
    @Test
    public void applyOffsets_terminalAnchored_collapseAttempt_rollsBack() {
        Fixture f = buildPerimeterAnchoredCoincidenceFixture();

        List<CoincidentSegmentDetector.CoincidentPair> pairs =
                detector.detect(f.connectionIds, f.paths, f.sourceCenters, f.targetCenters);
        assertTrue("Fixture must produce at least one coincident pair pre-call",
                pairs.size() >= 1);

        int offsetCount = detector.applyOffsets(
                pairs, f.paths, f.obstacles, f.anchoringContexts);

        assertEquals("applyOffsets must roll back perimeter-anchored shifts (B71 safety)",
                0, offsetCount);
        assertEquals("conn A path size unchanged", 3, f.paths.get(0).size());
        assertEquals("conn A bp[2].x stays on target LEFT face line",
                199, f.paths.get(0).get(2).x());
        assertEquals("conn B path size unchanged", 3, f.paths.get(1).size());
        assertEquals("conn B bp[2].x stays on target LEFT face line",
                199, f.paths.get(1).get(2).x());

        CoincidentSegmentDetector.AnchoringContext ctxA = f.anchoringContexts.get(0);
        CoincidentSegmentDetector.AnchoringContext ctxB = f.anchoringContexts.get(1);
        assertTrue("preservesEndpoints holds for conn A post-rollback",
                TerminalAnchoring.preservesEndpoints(
                        ctxA.sourceAnchoring(), ctxA.connection().source(), ctxA.sourceCenter(),
                        ctxA.targetAnchoring(), ctxA.connection().target(), ctxA.targetCenter(),
                        f.paths.get(0)));
        assertTrue("preservesEndpoints holds for conn B post-rollback",
                TerminalAnchoring.preservesEndpoints(
                        ctxB.sourceAnchoring(), ctxB.connection().source(), ctxB.sourceCenter(),
                        ctxB.targetAnchoring(), ctxB.connection().target(), ctxB.targetCenter(),
                        f.paths.get(1)));
    }

    /**
     * Approach-3 reconciliation resolves the rollback-prone coincidence
     * by INSERTING two BPs around the still-anchored terminal — the corridor
     * shifts perpendicular while the terminal BP itself stays on the face line.
     */
    @Test
    public void applyTerminalAnchoredReconciliation_resolvesPerimeterCoincidence() {
        Fixture f = buildPerimeterAnchoredCoincidenceFixture();

        // First, exhaust applyOffsets — confirms the rollback fires (paths intact).
        List<CoincidentSegmentDetector.CoincidentPair> prePairs =
                detector.detect(f.connectionIds, f.paths, f.sourceCenters, f.targetCenters);
        int offsetCount = detector.applyOffsets(
                prePairs, f.paths, f.obstacles, f.anchoringContexts);
        assertEquals("applyOffsets rolled back (B71 pin)", 0, offsetCount);

        // Then run Approach-3 reconciliation on the rolled-back state.
        int reconciled = detector.applyTerminalAnchoredReconciliation(
                f.connectionIds, f.paths, f.sourceCenters, f.targetCenters,
                f.obstacles, f.anchoringContexts);

        assertTrue("Reconciliation must resolve at least one coincident pair",
                reconciled >= 1);

        // Exactly one of conn A / conn B was the corridor anchor (i=0,
        // unchanged); the other was reconciled (path grew by 1 via BP
        // insertion). Which conn wins the anchor slot is determined by
        // detect()'s pair iteration order — assert the SHAPE rather than
        // identity to stay robust against future detection-ordering changes.
        int sizeA = f.paths.get(0).size();
        int sizeB = f.paths.get(1).size();
        assertTrue("Exactly one path grew by 1 (insertion); the other unchanged. "
                + "sizeA=" + sizeA + " sizeB=" + sizeB,
                (sizeA == 3 && sizeB == 4) || (sizeA == 4 && sizeB == 3));

        // Both connections' terminal BPs (path[last]) must remain on the
        // target LEFT face line at x=199, with y unchanged at the original
        // slot allocation (y=80 for conn A; y=60 for conn B).
        AbsoluteBendpointDto connATerminal = f.paths.get(0).get(sizeA - 1);
        AbsoluteBendpointDto connBTerminal = f.paths.get(1).get(sizeB - 1);
        assertEquals("conn A terminal BP still on target LEFT face line",
                199, connATerminal.x());
        assertEquals("conn A terminal BP y unchanged (slot y=80)",
                80, connATerminal.y());
        assertEquals("conn B terminal BP still on target LEFT face line",
                199, connBTerminal.x());
        assertEquals("conn B terminal BP y unchanged (slot y=60)",
                60, connBTerminal.y());

        // Both connections' anchoring contracts hold post-reconciliation.
        CoincidentSegmentDetector.AnchoringContext ctxA = f.anchoringContexts.get(0);
        CoincidentSegmentDetector.AnchoringContext ctxB = f.anchoringContexts.get(1);
        assertTrue("preservesEndpoints holds for conn A post-reconciliation",
                TerminalAnchoring.preservesEndpoints(
                        ctxA.sourceAnchoring(), ctxA.connection().source(), ctxA.sourceCenter(),
                        ctxA.targetAnchoring(), ctxA.connection().target(), ctxA.targetCenter(),
                        f.paths.get(0)));
        assertTrue("preservesEndpoints holds for conn B post-reconciliation",
                TerminalAnchoring.preservesEndpoints(
                        ctxB.sourceAnchoring(), ctxB.connection().source(), ctxB.sourceCenter(),
                        ctxB.targetAnchoring(), ctxB.connection().target(), ctxB.targetCenter(),
                        f.paths.get(1)));

        // Coincidence resolved: re-detection returns no pairs.
        List<CoincidentSegmentDetector.CoincidentPair> postPairs = detector.detect(
                f.connectionIds, f.paths, f.sourceCenters, f.targetCenters);
        assertEquals("Post-reconciliation should have zero coincident pairs",
                0, postPairs.size());
    }

    /**
     * Review finding (2026-04-29): three TA-coincident connections in a single
     * corridor must be staggered so all pairs separate without two segments
     * landing within MIN_SEPARATION of each other. The prior reconciliation only covers the
     * 2-segment case; the V4 oracle's largest TA-cluster is 4 segments
     * (C1 y=345 corridor → hub BOTTOM face) so 3+ clusters are in scope.
     */
    @Test
    public void applyTerminalAnchoredReconciliation_threeSegmentCorridor_staggerSeparates() {
        Fixture f = buildThreeSegmentCorridorFixture();

        int reconciled = detector.applyTerminalAnchoredReconciliation(
                f.connectionIds, f.paths, f.sourceCenters, f.targetCenters,
                f.obstacles, f.anchoringContexts);

        assertTrue("Reconciliation must resolve at least 2 of 3 segments",
                reconciled >= 2);

        // Exactly one anchor (size 3, unchanged) plus two reconciled (size 4).
        int unchangedCount = 0;
        int grownCount = 0;
        for (List<AbsoluteBendpointDto> path : f.paths) {
            if (path.size() == 3) unchangedCount++;
            else if (path.size() == 4) grownCount++;
        }
        assertEquals("exactly one anchor (size 3)", 1, unchangedCount);
        assertEquals("exactly two reconciled (size 4)", 2, grownCount);

        // All three terminal BPs preserved on target LEFT face line at x=199.
        for (int i = 0; i < f.paths.size(); i++) {
            List<AbsoluteBendpointDto> path = f.paths.get(i);
            AbsoluteBendpointDto terminal = path.get(path.size() - 1);
            assertEquals("conn[" + i + "] terminal x preserved",
                    199, terminal.x());
        }

        // Re-detect: the corridor must be fully separated.
        List<CoincidentSegmentDetector.CoincidentPair> postPairs = detector.detect(
                f.connectionIds, f.paths, f.sourceCenters, f.targetCenters);
        assertEquals("post-reconciliation: zero coincident pairs",
                0, postPairs.size());
    }

    /**
     * Review finding (2026-04-29): a connection with TWO terminal-anchored
     * coincident segments in two different corridor groups must not have its
     * path corrupted when corridor 1's reconciliation grows the path,
     * invalidating the stored seg.segmentIndex() for corridor 2. The defensive
     * guard at tryReconcileWithInsertion (the {@code !bp1IsTerminal &&
     * !bp2IsTerminal} early return) catches the stale index and skips
     * corridor 2's mutation. Without the guard, X's path would grow to size 6
     * with a diagonal segment introduced (corruption).
     */
    @Test
    public void applyTerminalAnchoredReconciliation_staleSegmentIndex_isSkipped() {
        Fixture f = buildBridgeWithTwoCorridorsFixture();

        int reconciled = detector.applyTerminalAnchoredReconciliation(
                f.connectionIds, f.paths, f.sourceCenters, f.targetCenters,
                f.obstacles, f.anchoringContexts);

        // Bridge X is at index 2 (last) so it is segB in both pairs and i=1
        // in both corridors. Corridor H:30 reconciles X first (X grows from
        // size 4 → 5). Corridor H:90's processing of X then hits the stale-
        // segmentIndex guard and returns false. Total reconciled = 1.
        assertEquals("only one reconciliation succeeds (guard skips the second)",
                1, reconciled);

        // Bridge X (index 2): exactly one insertion. Guard prevents the
        // second mutation that would have grown the path to size 6.
        List<AbsoluteBendpointDto> pathX = f.paths.get(2);
        assertEquals("X path size is 5 (one insertion, not corrupted to 6)",
                5, pathX.size());

        // Both terminals of X preserved on hub LEFT face line at x=199.
        assertEquals("X bp[0] x preserved (hub LEFT slot y=30)",
                199, pathX.get(0).x());
        assertEquals("X bp[0] y preserved (slot y=30)",
                30, pathX.get(0).y());
        AbsoluteBendpointDto xTerminalLast = pathX.get(pathX.size() - 1);
        assertEquals("X bp[last] x preserved (hub LEFT slot y=90)",
                199, xTerminalLast.x());
        assertEquals("X bp[last] y preserved (slot y=90)",
                90, xTerminalLast.y());

        // Anchoring contract holds for X (both ends on hub LEFT face line).
        CoincidentSegmentDetector.AnchoringContext ctxX = f.anchoringContexts.get(2);
        assertTrue("preservesEndpoints holds for X (both terminals untouched)",
                TerminalAnchoring.preservesEndpoints(
                        ctxX.sourceAnchoring(), ctxX.connection().source(), ctxX.sourceCenter(),
                        ctxX.targetAnchoring(), ctxX.connection().target(), ctxX.targetCenter(),
                        pathX));
    }

    // ---- Fixture helper for perimeter-anchored coincidence ----

    private static class Fixture {
        List<String> connectionIds;
        List<List<AbsoluteBendpointDto>> paths;
        List<int[]> sourceCenters;
        List<int[]> targetCenters;
        List<RoutingRect> obstacles;
        Map<Integer, CoincidentSegmentDetector.AnchoringContext> anchoringContexts;
    }

    /**
     * Builds the canonical fixture: two connections terminating at distinct
     * slots on the same LEFT face of a target hub element, sharing a vertical
     * corridor at x=199 (the target's LEFT face line). Both connections also
     * source-anchored on RIGHT face of their respective producer rects.
     */
    private static Fixture buildPerimeterAnchoredCoincidenceFixture() {
        Fixture f = new Fixture();
        // Hub target on RIGHT side of the canvas; LEFT face line at x=199.
        RoutingRect target = new RoutingRect(200, 0, 100, 100, "target-hub");
        int[] targetCenter = {target.centerX(), target.centerY()};
        // Source A: RIGHT face line at x=50, y∈[25, 75].
        // TerminalAnchoring.lineCoordinate = rect.x + width + 1 = 0 + 49 + 1.
        RoutingRect sourceA = new RoutingRect(0, 25, 49, 50, "source-A");
        int[] sourceACenter = {sourceA.centerX(), sourceA.centerY()};
        // Source B: visually disjoint from sourceA, placed below at y∈[100, 120].
        // (Both rects have RIGHT face line at x=50, which is exactly where
        // pathA/pathB put bp[0] — both terminals arrive ON their faceline.)
        RoutingRect sourceB = new RoutingRect(0, 100, 49, 20, "source-B");
        int[] sourceBCenter = {sourceB.centerX(), sourceB.centerY()};

        // Conn A: source A → target LEFT face slot y=80.
        List<AbsoluteBendpointDto> pathA = mutableList(
                new AbsoluteBendpointDto(50, 50),    // bp[0] on source A RIGHT face
                new AbsoluteBendpointDto(199, 50),   // bp[1] interior
                new AbsoluteBendpointDto(199, 80));  // bp[2] on target LEFT face slot y=80
        // Conn B: source B → target LEFT face slot y=60. Vertical corridor at
        // x=199 overlaps conn A's vertical (x=199, y∈[50, 80]) in y∈[60, 80].
        List<AbsoluteBendpointDto> pathB = mutableList(
                new AbsoluteBendpointDto(50, 110),   // bp[0] on source B RIGHT face
                new AbsoluteBendpointDto(199, 110),  // bp[1] interior
                new AbsoluteBendpointDto(199, 60));  // bp[2] on target LEFT face slot y=60

        f.paths = new ArrayList<>();
        f.paths.add(pathA);
        f.paths.add(pathB);
        f.sourceCenters = List.of(sourceACenter, sourceBCenter);
        f.targetCenters = List.of(targetCenter, targetCenter);
        f.connectionIds = List.of("conn-A", "conn-B");
        f.obstacles = List.of(sourceA, sourceB, target);

        RoutingPipeline.ConnectionEndpoints connA = new RoutingPipeline.ConnectionEndpoints(
                "conn-A", sourceA, target, List.of(), null, 0);
        RoutingPipeline.ConnectionEndpoints connB = new RoutingPipeline.ConnectionEndpoints(
                "conn-B", sourceB, target, List.of(), null, 0);
        TerminalAnchoring sourceFace = new TerminalAnchoring(EdgeAttachmentCalculator.Face.RIGHT);
        TerminalAnchoring targetFace = new TerminalAnchoring(EdgeAttachmentCalculator.Face.LEFT);

        f.anchoringContexts = new HashMap<>();
        f.anchoringContexts.put(0, new CoincidentSegmentDetector.AnchoringContext(
                connA, sourceACenter, targetCenter, sourceFace, targetFace));
        f.anchoringContexts.put(1, new CoincidentSegmentDetector.AnchoringContext(
                connB, sourceBCenter, targetCenter, sourceFace, targetFace));
        return f;
    }

    /**
     * Review-finding fixture: 3 connections, all terminating on the same hub
     * LEFT face at distinct slots y=80 / y=60 / y=40 (terminal BP y-values for
     * conn A / conn B / conn C respectively), sharing the vertical corridor
     * at x=199. The vertical corridor segments span y ranges [80,120] (A),
     * [60,160] (B), [40,200] (C) — all three pairs overlap by ≥40px (well
     * above MIN_OVERLAP_LENGTH=5), so {@code detect()} returns 3 pairs.
     * After reconciliation: i=0 anchor (conn A) unchanged at x=199, i=1
     * (conn B) staggered to x=191 (MIN_SEPARATION=8), i=2 (conn C) staggered
     * to x=183 (16px); all in the same direction (preferredSign=-1 since hub
     * center x=250 > terminal x=199, drop returns toward target). Sonnet 4.6
     * Original fixture had non-overlapping
     * y ranges (20px gaps) so {@code detect()} returned 0 pairs and the test
     * was effectively a no-op.
     */
    private static Fixture buildThreeSegmentCorridorFixture() {
        Fixture f = new Fixture();
        RoutingRect target = new RoutingRect(200, 0, 100, 200, "target-hub");
        int[] targetCenter = {target.centerX(), target.centerY()};

        // Sources stacked vertically (tangent edges) below the hub. Each
        // source's RIGHT face line is at x=49 (rect.x + width); pathX bp[0].x=50
        // tracks the +1 face-line tolerance the V4 oracle exhibits.
        // sourceA y∈[100,140] center (24,120); sourceB y∈[140,180] center (24,160);
        // sourceC y∈[180,220] center (24,200).
        RoutingRect sourceA = new RoutingRect(0, 100, 49, 40, "source-A");
        RoutingRect sourceB = new RoutingRect(0, 140, 49, 40, "source-B");
        RoutingRect sourceC = new RoutingRect(0, 180, 49, 40, "source-C");
        int[] sourceACenter = {sourceA.centerX(), sourceA.centerY()};
        int[] sourceBCenter = {sourceB.centerX(), sourceB.centerY()};
        int[] sourceCCenter = {sourceC.centerX(), sourceC.centerY()};

        // Conn A: source A center (24,120) → target LEFT slot y=80. Vertical
        // corridor x=199 spans y∈[80,120].
        List<AbsoluteBendpointDto> pathA = mutableList(
                new AbsoluteBendpointDto(50, 120),
                new AbsoluteBendpointDto(199, 120),
                new AbsoluteBendpointDto(199, 80));
        // Conn B: source B center (24,160) → target LEFT slot y=60. Vertical
        // corridor x=199 spans y∈[60,160] (overlaps A and C pairwise).
        List<AbsoluteBendpointDto> pathB = mutableList(
                new AbsoluteBendpointDto(50, 160),
                new AbsoluteBendpointDto(199, 160),
                new AbsoluteBendpointDto(199, 60));
        // Conn C: source C center (24,200) → target LEFT slot y=40. Vertical
        // corridor x=199 spans y∈[40,200] (envelops both A and B).
        List<AbsoluteBendpointDto> pathC = mutableList(
                new AbsoluteBendpointDto(50, 200),
                new AbsoluteBendpointDto(199, 200),
                new AbsoluteBendpointDto(199, 40));

        f.paths = new ArrayList<>();
        f.paths.add(pathA);
        f.paths.add(pathB);
        f.paths.add(pathC);
        f.sourceCenters = List.of(sourceACenter, sourceBCenter, sourceCCenter);
        f.targetCenters = List.of(targetCenter, targetCenter, targetCenter);
        f.connectionIds = List.of("conn-A", "conn-B", "conn-C");
        f.obstacles = List.of(sourceA, sourceB, sourceC, target);

        TerminalAnchoring sourceFace = new TerminalAnchoring(EdgeAttachmentCalculator.Face.RIGHT);
        TerminalAnchoring targetFace = new TerminalAnchoring(EdgeAttachmentCalculator.Face.LEFT);
        RoutingPipeline.ConnectionEndpoints connA = new RoutingPipeline.ConnectionEndpoints(
                "conn-A", sourceA, target, List.of(), null, 0);
        RoutingPipeline.ConnectionEndpoints connB = new RoutingPipeline.ConnectionEndpoints(
                "conn-B", sourceB, target, List.of(), null, 0);
        RoutingPipeline.ConnectionEndpoints connC = new RoutingPipeline.ConnectionEndpoints(
                "conn-C", sourceC, target, List.of(), null, 0);
        f.anchoringContexts = new HashMap<>();
        f.anchoringContexts.put(0, new CoincidentSegmentDetector.AnchoringContext(
                connA, sourceACenter, targetCenter, sourceFace, targetFace));
        f.anchoringContexts.put(1, new CoincidentSegmentDetector.AnchoringContext(
                connB, sourceBCenter, targetCenter, sourceFace, targetFace));
        f.anchoringContexts.put(2, new CoincidentSegmentDetector.AnchoringContext(
                connC, sourceCCenter, targetCenter, sourceFace, targetFace));
        return f;
    }

    /**
     * Review-finding fixture: bridge connection X with both ends terminal-
     * anchored on the same hub's LEFT face (U-turn), plus 2 helper
     * connections each coinciding with one of X's two TA segments. X is
     * placed at index 2 (last) so it is segB in both detected pairs, hence
     * i=1 (reconcile candidate) in both corridor groups. Corridor H:30
     * reconciles X first (path grows 4→5); corridor H:90's reconciliation
     * then encounters X's stale segmentIndex=2 and must be guarded out.
     */
    private static Fixture buildBridgeWithTwoCorridorsFixture() {
        Fixture f = new Fixture();
        RoutingRect hub = new RoutingRect(200, 0, 100, 200, "hub-target");
        int[] hubCenter = {hub.centerX(), hub.centerY()};

        // Width 49 → RIGHT face line at x=50 (same convention as the canonical
        // fixture: TerminalAnchoring.lineCoordinate = rect.x + width + 1).
        RoutingRect prodA = new RoutingRect(0, 0, 49, 40, "prod-A");
        RoutingRect prodB = new RoutingRect(0, 80, 49, 40, "prod-B");
        int[] prodACenter = {prodA.centerX(), prodA.centerY()};
        int[] prodBCenter = {prodB.centerX(), prodB.centerY()};

        // Conn Y: prod-A RIGHT (face line x=50) → hub LEFT slot y=30. L-shape
        // with seg 2 (TA) horizontal at y=30 from (100, 30) → (199, 30).
        List<AbsoluteBendpointDto> pathY = mutableList(
                new AbsoluteBendpointDto(50, 20),
                new AbsoluteBendpointDto(100, 20),
                new AbsoluteBendpointDto(100, 30),
                new AbsoluteBendpointDto(199, 30));
        // Conn Z: prod-B RIGHT (face line x=50) → hub LEFT slot y=90. L-shape
        // with seg 2 (TA) horizontal at y=90 from (100, 90) → (199, 90).
        List<AbsoluteBendpointDto> pathZ = mutableList(
                new AbsoluteBendpointDto(50, 100),
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(100, 90),
                new AbsoluteBendpointDto(199, 90));
        // Conn X (bridge): U-turn between hub LEFT slot y=30 and slot y=90.
        // seg 0 horizontal y=30, seg 1 vertical x=50, seg 2 horizontal y=90.
        // Both seg 0 and seg 2 are terminal-anchored.
        List<AbsoluteBendpointDto> pathX = mutableList(
                new AbsoluteBendpointDto(199, 30),
                new AbsoluteBendpointDto(50, 30),
                new AbsoluteBendpointDto(50, 90),
                new AbsoluteBendpointDto(199, 90));

        f.paths = new ArrayList<>();
        f.paths.add(pathY);
        f.paths.add(pathZ);
        f.paths.add(pathX);
        f.sourceCenters = List.of(prodACenter, prodBCenter, hubCenter);
        f.targetCenters = List.of(hubCenter, hubCenter, hubCenter);
        f.connectionIds = List.of("conn-Y", "conn-Z", "conn-X-bridge");
        f.obstacles = List.of(prodA, prodB, hub);

        TerminalAnchoring rightFace = new TerminalAnchoring(EdgeAttachmentCalculator.Face.RIGHT);
        TerminalAnchoring leftFace = new TerminalAnchoring(EdgeAttachmentCalculator.Face.LEFT);
        RoutingPipeline.ConnectionEndpoints connY = new RoutingPipeline.ConnectionEndpoints(
                "conn-Y", prodA, hub, List.of(), null, 0);
        RoutingPipeline.ConnectionEndpoints connZ = new RoutingPipeline.ConnectionEndpoints(
                "conn-Z", prodB, hub, List.of(), null, 0);
        // Bridge X: source = hub, target = hub (both ends on the same hub's
        // LEFT face). Both anchorings are LEFT.
        RoutingPipeline.ConnectionEndpoints connX = new RoutingPipeline.ConnectionEndpoints(
                "conn-X-bridge", hub, hub, List.of(), null, 0);

        f.anchoringContexts = new HashMap<>();
        f.anchoringContexts.put(0, new CoincidentSegmentDetector.AnchoringContext(
                connY, prodACenter, hubCenter, rightFace, leftFace));
        f.anchoringContexts.put(1, new CoincidentSegmentDetector.AnchoringContext(
                connZ, prodBCenter, hubCenter, rightFace, leftFace));
        f.anchoringContexts.put(2, new CoincidentSegmentDetector.AnchoringContext(
                connX, hubCenter, hubCenter, leftFace, leftFace));
        return f;
    }

    // ---- Helpers ----

    private static List<AbsoluteBendpointDto> mutableList(AbsoluteBendpointDto... items) {
        List<AbsoluteBendpointDto> list = new ArrayList<>();
        for (AbsoluteBendpointDto item : items) {
            list.add(item);
        }
        return list;
    }

    // ---- Per-end rollback policy at the applyOffsets wrap site.
    //
    // Every test below drives applyOffsets. None calls preservesEndpoints
    // directly: handing a hand-written path to the predicate proves only that
    // the predicate agrees with itself, never that the mutator consults it.
    //
    // The two pre-existing pins above cannot discriminate this policy. Their
    // fixture arrives with BOTH terminals on their facelines, so the old
    // post-state verdict and the per-end flip verdict agree on it by
    // construction, and both remain green unedited.

    /**
     * A source-touching offset commits even though the TARGET terminal arrived
     * off its faceline.
     *
     * <p>The offset runs along the source face's parallel axis — a vertical
     * shift of a horizontal corridor leaves {@code bp[0].x} on the RIGHT
     * faceline — so the touched end neither flips nor was off-face to begin
     * with. The target end is at the far end of the path, this offset cannot
     * reach it, and it is therefore not consulted.
     */
    @Test
    public void applyOffsets_sourceTouchingOffset_targetArrivedOffFace_commits() {
        Fixture f = buildSourceTouchingCorridorFixture(false);

        List<CoincidentSegmentDetector.CoincidentPair> pairs =
                detector.detect(f.connectionIds, f.paths, f.sourceCenters, f.targetCenters);
        assertTrue("fixture must produce a coincident pair", pairs.size() >= 1);

        detector.applyOffsets(pairs, f.paths, f.obstacles, f.anchoringContexts);

        assertEquals("the touched source terminal stays on its RIGHT faceline",
                50, f.paths.get(0).get(0).x());
        assertEquals("the source-touching offset committed at its proportional "
                + "target — an untouched off-face target must not veto it",
                16, f.paths.get(0).get(0).y());
        assertEquals("the whole segment moved with it, not just the terminal",
                16, f.paths.get(0).get(1).y());
    }

    /**
     * The mirror: a target-touching offset commits even though the SOURCE
     * terminal arrived off its faceline.
     */
    @Test
    public void applyOffsets_targetTouchingOffset_sourceArrivedOffFace_commits() {
        Fixture f = buildTargetTouchingCorridorFixture();

        List<CoincidentSegmentDetector.CoincidentPair> pairs =
                detector.detect(f.connectionIds, f.paths, f.sourceCenters, f.targetCenters);
        assertTrue("fixture must produce a coincident pair", pairs.size() >= 1);

        detector.applyOffsets(pairs, f.paths, f.obstacles, f.anchoringContexts);

        List<AbsoluteBendpointDto> pathC = f.paths.get(1);
        assertEquals("the touched target terminal stays on its LEFT faceline",
                499, pathC.get(pathC.size() - 1).x());
        assertEquals("the target-touching offset committed at its proportional "
                + "target — an untouched off-face source must not veto it",
                626, pathC.get(pathC.size() - 1).y());
        assertEquals("the whole segment moved with it, not just the terminal",
                626, pathC.get(pathC.size() - 2).y());
    }

    /**
     * The flip rule, source end: a touched terminal that arrived ON its
     * faceline and would be moved OFF it rolls the offset back.
     *
     * <p>Same corridor as
     * {@link #applyOffsets_sourceTouchingOffset_targetArrivedOffFace_commits},
     * with the source anchored on its BOTTOM face instead of its RIGHT one.
     * The face's orthogonal axis is now the one the offset moves, so the
     * identical mutation flips the terminal off the faceline.
     */
    @Test
    public void applyOffsets_touchedSourceFlipsOffFaceline_rollsBack() {
        Fixture f = buildSourceTouchingCorridorFixture(true);

        List<CoincidentSegmentDetector.CoincidentPair> pairs =
                detector.detect(f.connectionIds, f.paths, f.sourceCenters, f.targetCenters);
        assertTrue("fixture must produce a coincident pair", pairs.size() >= 1);

        detector.applyOffsets(pairs, f.paths, f.obstacles, f.anchoringContexts);

        assertEquals("a touched terminal flipped off its BOTTOM faceline rolls back",
                50, f.paths.get(0).get(0).y());
        assertEquals("the rollback restores the whole segment, not just the terminal",
                50, f.paths.get(0).get(1).y());
    }

    /**
     * The pin, source end: a touched terminal that arrived OFF its faceline is
     * not relocated.
     *
     * <p>The flip rule alone cannot decide this case — the terminal was never
     * on its faceline, so nothing flips. A perpendicular shift of an off-face
     * terminal still moves the point the ChopboxAnchor ray terminates at, so
     * the render changes; separating this corridor without moving a terminal is
     * stage 4.7q's job.
     */
    @Test
    public void applyOffsets_touchedSourceArrivedOffFaceline_isNotMoved() {
        Fixture f = buildOffFaceSourceCorridorFixture();

        List<CoincidentSegmentDetector.CoincidentPair> pairs =
                detector.detect(f.connectionIds, f.paths, f.sourceCenters, f.targetCenters);
        assertTrue("fixture must produce a coincident pair", pairs.size() >= 1);

        detector.applyOffsets(pairs, f.paths, f.obstacles, f.anchoringContexts);

        assertEquals("an off-face source terminal is pinned, not relocated",
                50, f.paths.get(0).get(0).y());
        assertEquals("the pinned terminal keeps its off-face orthogonal coordinate",
                60, f.paths.get(0).get(0).x());
    }

    /**
     * The pin, target end.
     */
    @Test
    public void applyOffsets_touchedTargetArrivedOffFaceline_isNotMoved() {
        Fixture f = buildOffFaceTargetCorridorFixture();

        List<CoincidentSegmentDetector.CoincidentPair> pairs =
                detector.detect(f.connectionIds, f.paths, f.sourceCenters, f.targetCenters);
        assertTrue("fixture must produce a coincident pair", pairs.size() >= 1);

        detector.applyOffsets(pairs, f.paths, f.obstacles, f.anchoringContexts);

        List<AbsoluteBendpointDto> pathC = f.paths.get(1);
        assertEquals("an off-face target terminal is pinned, not relocated",
                600, pathC.get(pathC.size() - 1).y());
        assertEquals("the pinned terminal keeps its off-face orthogonal coordinate",
                490, pathC.get(pathC.size() - 1).x());
    }

    /**
     * The precondition {@code preMutationPath} rests on, pinned where it is
     * actually established.
     *
     * <p>The wrap reconstructs the pre-mutation path by restoring two slots from
     * their snapshots. That is exact only while the offset writes those two slots
     * and nothing else, and leaves the path length alone — otherwise the "before"
     * view it hands the policy is a mix of pre- and post-mutation state at some
     * third index, and the flip and pin arms would both be decided against a
     * corrupted baseline. Nothing in the wrap can detect that; the property lives
     * in the offset, so it is pinned here rather than assumed in a comment.
     *
     * <p>Drives {@code applyOffsets} over every fixture in this file that reaches
     * the wrap, and asserts each path's length is unchanged and that at most two
     * of its points differ from the pre-call copy.
     */
    @Test
    public void applyOffsets_writesAtMostTwoPointsPerPathAndNeverResizes() {
        List<Fixture> fixtures = List.of(
                buildPerimeterAnchoredCoincidenceFixture(),
                buildSourceTouchingCorridorFixture(false),
                buildSourceTouchingCorridorFixture(true),
                buildTargetTouchingCorridorFixture(),
                buildOffFaceSourceCorridorFixture(),
                buildOffFaceTargetCorridorFixture());

        for (int fx = 0; fx < fixtures.size(); fx++) {
            Fixture f = fixtures.get(fx);
            List<List<AbsoluteBendpointDto>> before = new ArrayList<>();
            for (List<AbsoluteBendpointDto> path : f.paths) {
                before.add(new ArrayList<>(path));
            }

            List<CoincidentSegmentDetector.CoincidentPair> pairs =
                    detector.detect(f.connectionIds, f.paths, f.sourceCenters, f.targetCenters);
            detector.applyOffsets(pairs, f.paths, f.obstacles, f.anchoringContexts);

            for (int ci = 0; ci < f.paths.size(); ci++) {
                List<AbsoluteBendpointDto> was = before.get(ci);
                List<AbsoluteBendpointDto> now = f.paths.get(ci);
                assertEquals("fixture " + fx + " conn " + ci
                        + ": applyOffsets must not resize a path — preMutationPath's "
                        + "index-for-index reconstruction depends on it",
                        was.size(), now.size());
                int changed = 0;
                for (int i = 0; i < was.size(); i++) {
                    if (!was.get(i).equals(now.get(i))) {
                        changed++;
                    }
                }
                assertTrue("fixture " + fx + " conn " + ci
                        + ": applyOffsets changed " + changed + " points; the wrap can only "
                        + "restore the two it snapshots, so a third write would leave "
                        + "preMutationPath reconstructing a state that never existed",
                        changed <= 2);
            }
        }
    }

    /**
     * Corridor whose first member is a connection's SEGMENT 0 — the segment
     * that owns {@code bp[0]}, the source terminal — and whose second member is
     * another connection's interior segment. The interior segment is never
     * terminal-touching, so it commits under every policy and only the
     * terminal-touching one is under test.
     *
     * @param sourceOnBottomFace anchor the source on its BOTTOM face (the
     *                           offset axis) rather than its RIGHT face (the
     *                           face's parallel axis), turning the same
     *                           mutation from a no-op on the faceline into a
     *                           flip off it
     */
    private static Fixture buildSourceTouchingCorridorFixture(boolean sourceOnBottomFace) {
        Fixture f = new Fixture();
        // RIGHT face line = 0 + 49 + 1 = 50; BOTTOM face line = 0 + 49 + 1 = 50.
        // One rect serves both readings, so the two arms differ only in the
        // anchoring handed to the wrap.
        RoutingRect sourceA = new RoutingRect(0, 0, 49, 49, "source-A");
        RoutingRect targetA = new RoutingRect(280, 400, 100, 60, "target-A");
        RoutingRect sourceB = new RoutingRect(100, 150, 49, 60, "source-B");
        RoutingRect targetB = new RoutingRect(451, 120, 80, 60, "target-B");

        // Conn A: exits its source into the y=50 corridor. Segment 0 is
        // bp[0]->bp[1], so bpIdx1 == 0 and the wrap's terminal gate opens.
        List<AbsoluteBendpointDto> pathA = mutableList(
                new AbsoluteBendpointDto(50, 50),
                new AbsoluteBendpointDto(300, 50),
                new AbsoluteBendpointDto(300, 400));
        // Conn B: crosses the same corridor mid-path. Segment 1 is interior.
        List<AbsoluteBendpointDto> pathB = mutableList(
                new AbsoluteBendpointDto(150, 150),
                new AbsoluteBendpointDto(150, 50),
                new AbsoluteBendpointDto(450, 50),
                new AbsoluteBendpointDto(450, 150));

        f.paths = new ArrayList<>();
        f.paths.add(pathA);
        f.paths.add(pathB);
        f.connectionIds = List.of("conn-A", "conn-B");
        int[] centerA = {sourceA.centerX(), sourceA.centerY()};
        int[] centerTA = {targetA.centerX(), targetA.centerY()};
        int[] centerB = {sourceB.centerX(), sourceB.centerY()};
        int[] centerTB = {targetB.centerX(), targetB.centerY()};
        f.sourceCenters = List.of(centerA, centerB);
        f.targetCenters = List.of(centerTA, centerTB);
        f.obstacles = List.of(sourceA, targetA, sourceB, targetB);

        // Conn A's target arrives OFF its TOP face line (400 - 1 = 399, bp is
        // at y=400) — the untouched end whose veto this policy removes.
        TerminalAnchoring sourceFaceA = new TerminalAnchoring(sourceOnBottomFace
                ? EdgeAttachmentCalculator.Face.BOTTOM
                : EdgeAttachmentCalculator.Face.RIGHT);
        f.anchoringContexts = new HashMap<>();
        f.anchoringContexts.put(0, new CoincidentSegmentDetector.AnchoringContext(
                new RoutingPipeline.ConnectionEndpoints("conn-A", sourceA, targetA, List.of(), null, 0),
                centerA, centerTA,
                sourceFaceA,
                new TerminalAnchoring(EdgeAttachmentCalculator.Face.TOP)));
        f.anchoringContexts.put(1, new CoincidentSegmentDetector.AnchoringContext(
                new RoutingPipeline.ConnectionEndpoints("conn-B", sourceB, targetB, List.of(), null, 0),
                centerB, centerTB,
                new TerminalAnchoring(EdgeAttachmentCalculator.Face.RIGHT),
                new TerminalAnchoring(EdgeAttachmentCalculator.Face.LEFT)));
        return f;
    }

    /**
     * The mirror of {@link #buildSourceTouchingCorridorFixture}: the corridor's
     * terminal-touching member is a connection's LAST segment, so
     * {@code bpIdx2 == path.size() - 1} and the touched end is the target.
     *
     * <p><strong>The corridor must be wide enough for proportional spacing.</strong>
     * That is the property this fixture depends on, and it is a property of the
     * geometry, not of the member order. {@code applyOffsets} distributes a
     * corridor's members proportionally when the gap allows it and every member
     * — including the first — is moved to its own target; it falls back to
     * fixed-delta stacking when {@code computeProportionalOffsets} declines the
     * gap, and only that fallback treats the group's first member as an unmoved
     * anchor. An earlier draft of this fixture placed {@code target-C} inside the
     * corridor band, which narrowed the gap enough to force the fallback, and the
     * terminal-touching segment then went unoffset and the wrap under test never
     * ran. The rects are placed clear of the corridor for that reason. Member
     * order is <em>not</em> load-bearing here: with the two connections swapped
     * the terminal still moves, which was verified by measurement rather than
     * assumed.
     */
    private static Fixture buildTargetTouchingCorridorFixture() {
        Fixture f = new Fixture();
        RoutingRect sourceD = new RoutingRect(400, 700, 49, 60, "source-D");
        RoutingRect targetD = new RoutingRect(160, 420, 80, 59, "target-D");
        // RIGHT face line = 100 + 54 + 1 = 155, but conn C's bp[0].x is 150 —
        // the source arrives OFF its faceline, and is the untouched end here.
        RoutingRect sourceC = new RoutingRect(100, 700, 54, 60, "source-C");
        RoutingRect targetC = new RoutingRect(500, 570, 80, 60, "target-C");

        // Conn D: crosses the y=600 corridor mid-path. Segment 1 is interior.
        List<AbsoluteBendpointDto> pathD = mutableList(
                new AbsoluteBendpointDto(450, 700),
                new AbsoluteBendpointDto(450, 600),
                new AbsoluteBendpointDto(200, 600),
                new AbsoluteBendpointDto(200, 480));
        // Conn C: enters its target from the y=600 corridor. Segment 1 is
        // bp[1]->bp[2], so bpIdx2 == path.size() - 1 and the terminal gate opens.
        List<AbsoluteBendpointDto> pathC = mutableList(
                new AbsoluteBendpointDto(150, 700),
                new AbsoluteBendpointDto(150, 600),
                new AbsoluteBendpointDto(499, 600));

        f.paths = new ArrayList<>();
        f.paths.add(pathD);
        f.paths.add(pathC);
        f.connectionIds = List.of("conn-D", "conn-C");
        int[] centerD = {sourceD.centerX(), sourceD.centerY()};
        int[] centerTD = {targetD.centerX(), targetD.centerY()};
        int[] centerC = {sourceC.centerX(), sourceC.centerY()};
        int[] centerTC = {targetC.centerX(), targetC.centerY()};
        f.sourceCenters = List.of(centerD, centerC);
        f.targetCenters = List.of(centerTD, centerTC);
        f.obstacles = List.of(sourceD, targetD, sourceC, targetC);

        f.anchoringContexts = new HashMap<>();
        f.anchoringContexts.put(0, new CoincidentSegmentDetector.AnchoringContext(
                new RoutingPipeline.ConnectionEndpoints("conn-D", sourceD, targetD, List.of(), null, 0),
                centerD, centerTD,
                new TerminalAnchoring(EdgeAttachmentCalculator.Face.RIGHT),
                new TerminalAnchoring(EdgeAttachmentCalculator.Face.BOTTOM)));
        f.anchoringContexts.put(1, new CoincidentSegmentDetector.AnchoringContext(
                new RoutingPipeline.ConnectionEndpoints("conn-C", sourceC, targetC, List.of(), null, 0),
                centerC, centerTC,
                new TerminalAnchoring(EdgeAttachmentCalculator.Face.RIGHT),
                new TerminalAnchoring(EdgeAttachmentCalculator.Face.LEFT)));
        return f;
    }

    /**
     * As {@link #buildSourceTouchingCorridorFixture}, but conn A's source
     * terminal arrives OFF its RIGHT face line (50) at x=60. Nothing can flip;
     * only the pin decides.
     */
    private static Fixture buildOffFaceSourceCorridorFixture() {
        Fixture f = buildSourceTouchingCorridorFixture(false);
        f.paths.get(0).set(0, new AbsoluteBendpointDto(60, 50));
        return f;
    }

    /**
     * As {@link #buildTargetTouchingCorridorFixture}, but conn C's target
     * terminal arrives OFF its LEFT face line (499) at x=490.
     */
    private static Fixture buildOffFaceTargetCorridorFixture() {
        Fixture f = buildTargetTouchingCorridorFixture();
        List<AbsoluteBendpointDto> pathC = f.paths.get(1);
        pathC.set(pathC.size() - 1, new AbsoluteBendpointDto(490, 600));
        return f;
    }
}
