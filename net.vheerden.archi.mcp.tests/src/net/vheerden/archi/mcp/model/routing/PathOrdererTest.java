package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Tests for {@link PathOrderer} (Story 10-7a).
 * Pure-geometry tests — no OSGi runtime required.
 */
public class PathOrdererTest {

    private PathOrderer orderer;

    @Before
    public void setUp() {
        orderer = new PathOrderer();
    }

    // --- Test 4.1: Shared horizontal corridor — crossing detected (AC #1) ---

    @Test
    public void shouldDetectCrossing_whenHorizontalCorridorConnectionsCrossUnnecessarily() {
        // Connection A: source top, target top (avg y = 50)
        //   Bendpoints: (50,200), (350,200) — horizontal segment at y=200, x-midpoint=200
        // Connection B: source bottom, target bottom (avg y = 350)
        //   Bendpoints: (50,200), (250,200) — horizontal segment at y=200, x-midpoint=150
        //
        // Endpoint perpendicular order: A(y=50) < B(y=350) → A first
        // Segment parallel order: B(x=150) < A(x=200) → B first
        // Orders disagree → crossing detected

        List<String> ids = List.of("connA", "connB");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(50, 200),
                new AbsoluteBendpointDto(350, 200))));
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(50, 200),
                new AbsoluteBendpointDto(250, 200))));

        List<int[]> sourceCenters = List.of(new int[]{50, 50}, new int[]{50, 350});
        List<int[]> targetCenters = List.of(new int[]{400, 50}, new int[]{300, 350});

        // Verify crossing is detected
        List<PathOrderer.CrossingInfo> crossings =
                orderer.detectCrossings(ids, bendpointLists, sourceCenters, targetCenters);

        assertEquals("Should detect 1 crossing in shared horizontal corridor", 1, crossings.size());
        PathOrderer.CrossingInfo crossing = crossings.get(0);
        assertEquals(0, crossing.connectionIndexA());
        assertEquals(1, crossing.connectionIndexB());

        // Verify orderPaths returns unmodified bendpoints (same size, same content)
        List<List<AbsoluteBendpointDto>> result =
                orderer.orderPaths(ids, bendpointLists, sourceCenters, targetCenters);
        assertEquals(2, result.size());
        assertBendpointsEqual(bendpointLists.get(0), result.get(0));
        assertBendpointsEqual(bendpointLists.get(1), result.get(1));
    }

    // --- Test 4.2: Shared vertical corridor — crossing detected (AC #1) ---

    @Test
    public void shouldDetectCrossing_whenVerticalCorridorConnectionsCrossUnnecessarily() {
        // Connection A: source left, target left (avg x = 50)
        //   Bendpoints: (200,100), (200,350) — vertical segment at x=200, y-midpoint=225
        // Connection B: source right, target right (avg x = 400)
        //   Bendpoints: (200,50), (200,200) — vertical segment at x=200, y-midpoint=125
        //
        // Endpoint perpendicular order: A(x=50) < B(x=400) → A first
        // Segment parallel order: B(y=125) < A(y=225) → B first
        // Orders disagree → crossing detected

        List<String> ids = List.of("connA", "connB");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(200, 100),
                new AbsoluteBendpointDto(200, 350))));
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(200, 50),
                new AbsoluteBendpointDto(200, 200))));

        List<int[]> sourceCenters = List.of(new int[]{50, 100}, new int[]{400, 50});
        List<int[]> targetCenters = List.of(new int[]{50, 350}, new int[]{400, 200});

        List<PathOrderer.CrossingInfo> crossings =
                orderer.detectCrossings(ids, bendpointLists, sourceCenters, targetCenters);

        assertEquals("Should detect 1 crossing in shared vertical corridor", 1, crossings.size());
        PathOrderer.CrossingInfo crossing = crossings.get(0);
        assertEquals(0, crossing.connectionIndexA());
        assertEquals(1, crossing.connectionIndexB());
    }

    // --- Test 4.3: Topological crossing preserved — different corridors (AC #2) ---

    @Test
    public void shouldNotDetectCrossing_whenDifferentCorridors() {
        // Two connections routed through DIFFERENT corridors — no grouping, no crossing
        // Connection A: horizontal corridor at y=100
        // Connection B: horizontal corridor at y=300

        List<String> ids = List.of("connA", "connB");
        List<AbsoluteBendpointDto> originalA = List.of(
                new AbsoluteBendpointDto(50, 100),
                new AbsoluteBendpointDto(350, 100));
        List<AbsoluteBendpointDto> originalB = List.of(
                new AbsoluteBendpointDto(50, 300),
                new AbsoluteBendpointDto(350, 300));

        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>(originalA));
        bendpointLists.add(new ArrayList<>(originalB));

        List<int[]> sourceCenters = List.of(new int[]{50, 50}, new int[]{50, 350});
        List<int[]> targetCenters = List.of(new int[]{400, 50}, new int[]{400, 350});

        // No crossings in different corridors
        List<PathOrderer.CrossingInfo> crossings =
                orderer.detectCrossings(ids, bendpointLists, sourceCenters, targetCenters);
        assertTrue("Different corridors should have no crossings", crossings.isEmpty());

        // Paths should be unchanged
        List<List<AbsoluteBendpointDto>> result =
                orderer.orderPaths(ids, bendpointLists, sourceCenters, targetCenters);
        assertEquals(2, result.size());
        assertBendpointsEqual(originalA, result.get(0));
        assertBendpointsEqual(originalB, result.get(1));
    }

    // --- Test 4.4: No shared segments — all paths unchanged (AC #3) ---

    @Test
    public void shouldNotChangePaths_whenNoSharedSegments() {
        // Connections with single bendpoint — no intermediate segments to analyse
        List<String> ids = List.of("connA", "connB");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>(List.of(new AbsoluteBendpointDto(200, 100))));
        bendpointLists.add(new ArrayList<>(List.of(new AbsoluteBendpointDto(200, 300))));

        List<int[]> sourceCenters = List.of(new int[]{50, 50}, new int[]{50, 350});
        List<int[]> targetCenters = List.of(new int[]{400, 50}, new int[]{400, 350});

        List<PathOrderer.CrossingInfo> crossings =
                orderer.detectCrossings(ids, bendpointLists, sourceCenters, targetCenters);
        assertTrue("Single-bendpoint connections have no segments to analyse", crossings.isEmpty());

        List<List<AbsoluteBendpointDto>> result =
                orderer.orderPaths(ids, bendpointLists, sourceCenters, targetCenters);
        assertEquals(2, result.size());
        assertEquals(1, result.get(0).size());
        assertEquals(200, result.get(0).get(0).x());
        assertEquals(100, result.get(0).get(0).y());
        assertEquals(1, result.get(1).size());
        assertEquals(200, result.get(1).get(0).x());
        assertEquals(300, result.get(1).get(0).y());
    }

    // --- Test 4.5: Single connection — no-op (AC #3) ---

    @Test
    public void shouldReturnUnchanged_whenSingleConnection() {
        List<String> ids = List.of("conn1");
        List<AbsoluteBendpointDto> original = List.of(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(300, 200));
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>(original));

        List<int[]> sourceCenters = List.of(new int[]{50, 100});
        List<int[]> targetCenters = List.of(new int[]{400, 100});

        List<List<AbsoluteBendpointDto>> result =
                orderer.orderPaths(ids, bendpointLists, sourceCenters, targetCenters);

        assertEquals(1, result.size());
        assertBendpointsEqual(original, result.get(0));
    }

    // --- Test 4.6: Mixed groups — horizontal and vertical groups processed independently (AC #4) ---

    @Test
    public void shouldProcessHorizontalAndVerticalGroupsIndependently() {
        // Three connections:
        // A and B share a horizontal corridor at y=200
        // A and C share a vertical corridor at x=300
        // Verify both groups are detected independently

        List<String> ids = List.of("connA", "connB", "connC");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();

        // A: has both horizontal and vertical segments
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(300, 200),
                new AbsoluteBendpointDto(300, 400))));
        // B: shares horizontal corridor with A at y=200
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(150, 200),
                new AbsoluteBendpointDto(350, 200))));
        // C: shares vertical corridor with A at x=300
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(300, 150),
                new AbsoluteBendpointDto(300, 350))));

        List<int[]> sourceCenters = List.of(
                new int[]{50, 100}, new int[]{50, 300}, new int[]{200, 50});
        List<int[]> targetCenters = List.of(
                new int[]{400, 400}, new int[]{400, 300}, new int[]{400, 400});

        // All three connections should have results
        List<List<AbsoluteBendpointDto>> result =
                orderer.orderPaths(ids, bendpointLists, sourceCenters, targetCenters);
        assertEquals(3, result.size());

        // Verify segment grouping: should have at least 2 groups (H:200 and V:300)
        List<PathOrderer.Segment> allSegments = new ArrayList<>();
        for (int i = 0; i < bendpointLists.size(); i++) {
            if (bendpointLists.get(i).size() >= 2) {
                orderer.extractSegments(i, bendpointLists.get(i),
                        sourceCenters.get(i), targetCenters.get(i), allSegments);
            }
        }
        var groups = orderer.groupSegments(allSegments);
        assertTrue("Should have horizontal group", groups.containsKey("H:200"));
        assertTrue("Should have vertical group", groups.containsKey("V:300"));
    }

    // --- Test: no crossing when endpoint ordering matches segment ordering (AC #2) ---

    @Test
    public void shouldNotDetectCrossing_whenOrderingsAgree() {
        // Connection A: source top (avg y=50), segment x-midpoint=150 (LEFT)
        // Connection B: source bottom (avg y=350), segment x-midpoint=300 (RIGHT)
        // Endpoint order: A first. Segment order: A first. Agree → no crossing.

        List<String> ids = List.of("connA", "connB");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(50, 200),
                new AbsoluteBendpointDto(250, 200))));
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(200, 200),
                new AbsoluteBendpointDto(400, 200))));

        List<int[]> sourceCenters = List.of(new int[]{50, 50}, new int[]{50, 350});
        List<int[]> targetCenters = List.of(new int[]{300, 50}, new int[]{450, 350});

        List<PathOrderer.CrossingInfo> crossings =
                orderer.detectCrossings(ids, bendpointLists, sourceCenters, targetCenters);

        assertTrue("Agreeing orders should produce no crossing", crossings.isEmpty());
    }

    // --- Test: segment extraction ---

    @Test
    public void shouldExtractIntermediateSegments_fromBendpoints() {
        // Connection with 4 bendpoints: src → bp0 → bp1 → bp2 → bp3 → tgt
        // Segments: bp0→bp1 (idx 0), bp1→bp2 (idx 1), bp2→bp3 (idx 2)
        // First (src→bp0) and last (bp3→tgt) are excluded

        PathOrderer o = new PathOrderer();
        List<AbsoluteBendpointDto> bps = List.of(
                new AbsoluteBendpointDto(100, 50),   // bp0
                new AbsoluteBendpointDto(100, 200),  // bp1 — vertical segment bp0→bp1
                new AbsoluteBendpointDto(300, 200),  // bp2 — horizontal segment bp1→bp2
                new AbsoluteBendpointDto(300, 350)); // bp3 — vertical segment bp2→bp3

        int[] src = {50, 50};
        int[] tgt = {350, 350};

        List<PathOrderer.Segment> segments = new ArrayList<>();
        o.extractSegments(0, bps, src, tgt, segments);

        assertEquals(3, segments.size());

        // First segment: bp0→bp1 (vertical at x=100)
        assertFalse(segments.get(0).horizontal());
        assertEquals(100, segments.get(0).x1());
        assertEquals(50, segments.get(0).y1());

        // Second segment: bp1→bp2 (horizontal at y=200)
        assertTrue(segments.get(1).horizontal());
        assertEquals(200, segments.get(1).y1());

        // Third segment: bp2→bp3 (vertical at x=300)
        assertFalse(segments.get(2).horizontal());
        assertEquals(300, segments.get(2).x1());
    }

    // --- Test: segment grouping with tolerance ---

    @Test
    public void shouldGroupSegmentsWithinTolerance() {
        PathOrderer o = new PathOrderer(5); // 5px tolerance
        List<PathOrderer.Segment> segments = List.of(
                new PathOrderer.Segment(0, 0, 50, 200, 300, 200, true),   // H at y=200
                new PathOrderer.Segment(1, 0, 50, 202, 300, 202, true),  // H at y=202 (within 5px)
                new PathOrderer.Segment(2, 0, 50, 210, 300, 210, true)); // H at y=210 (outside 5px from 200)

        var groups = o.groupSegments(segments);

        // Should have 2 groups: {200, 202} and {210}
        assertEquals(2, groups.size());
    }

    // --- Test: empty bendpoint lists ---

    @Test
    public void shouldHandleEmptyBendpoints_gracefully() {
        List<String> ids = List.of("connA", "connB");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>());  // 0 bendpoints
        bendpointLists.add(new ArrayList<>());  // 0 bendpoints

        List<int[]> sourceCenters = List.of(new int[]{50, 50}, new int[]{50, 350});
        List<int[]> targetCenters = List.of(new int[]{400, 50}, new int[]{400, 350});

        List<List<AbsoluteBendpointDto>> result =
                orderer.orderPaths(ids, bendpointLists, sourceCenters, targetCenters);

        assertEquals(2, result.size());
        assertTrue(result.get(0).isEmpty());
        assertTrue(result.get(1).isEmpty());
    }

    // --- Test: crossing detection with endpoints at same position (within tolerance) ---

    @Test
    public void shouldNotDetectCrossing_whenEndpointsAtSamePosition() {
        // Both connections have same avg endpoint position → ambiguous → no crossing
        List<String> ids = List.of("connA", "connB");
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(50, 200),
                new AbsoluteBendpointDto(350, 200))));
        bendpointLists.add(new ArrayList<>(List.of(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(250, 200))));

        // Both connections: avg y ≈ 200 (difference of 1, within tolerance of 2)
        List<int[]> sourceCenters = List.of(new int[]{50, 200}, new int[]{50, 201});
        List<int[]> targetCenters = List.of(new int[]{400, 200}, new int[]{400, 201});

        List<PathOrderer.CrossingInfo> crossings =
                orderer.detectCrossings(ids, bendpointLists, sourceCenters, targetCenters);

        assertTrue("Same-position endpoints should not trigger crossing", crossings.isEmpty());
    }

    // =============== Helper Methods ===============

    private void assertBendpointsEqual(List<AbsoluteBendpointDto> expected,
                                       List<AbsoluteBendpointDto> actual) {
        assertEquals("Bendpoint list size", expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals("Bendpoint[" + i + "].x", expected.get(i).x(), actual.get(i).x());
            assertEquals("Bendpoint[" + i + "].y", expected.get(i).y(), actual.get(i).y());
        }
    }
}
