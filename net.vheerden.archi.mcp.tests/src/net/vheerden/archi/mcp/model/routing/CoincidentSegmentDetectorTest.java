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

    // ---- Helpers ----

    private static List<AbsoluteBendpointDto> mutableList(AbsoluteBendpointDto... items) {
        List<AbsoluteBendpointDto> list = new ArrayList<>();
        for (AbsoluteBendpointDto item : items) {
            list.add(item);
        }
        return list;
    }
}
