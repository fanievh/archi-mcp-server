package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Tests for {@link CorridorOccupancyTracker} — corridor occupancy tracking
 * for inter-connection aware A* routing (B47). Pure-geometry tests, no EMF/SWT required.
 */
public class CorridorOccupancyTrackerTest {

    private CorridorOccupancyTracker tracker;

    @Before
    public void setUp() {
        tracker = new CorridorOccupancyTracker();
    }

    // --- recordPath tests ---

    @Test
    public void recordPath_shouldIncrementCorridorOccupancy() {
        // Path: (100,100) → (200,100) → (200,300) — one H:100 and one V:200 segment
        List<AbsoluteBendpointDto> bendpoints = List.of(new AbsoluteBendpointDto(200, 100));
        int[] source = {100, 100};
        int[] target = {200, 300};

        tracker.recordPath(bendpoints, source, target);

        Map<String, Integer> occupancy = tracker.getCorridorOccupancy();
        assertEquals("H:100 corridor should have occupancy 1", Integer.valueOf(1), occupancy.get("H:100"));
        assertEquals("V:200 corridor should have occupancy 1", Integer.valueOf(1), occupancy.get("V:200"));
    }

    @Test
    public void recordPath_shouldGroupWithinTolerance() {
        // First path through H:150
        List<AbsoluteBendpointDto> bp1 = List.of(new AbsoluteBendpointDto(200, 150));
        tracker.recordPath(bp1, new int[]{100, 150}, new int[]{200, 300});

        // Second path through H:151 — within tolerance of 2px, should share H:150
        List<AbsoluteBendpointDto> bp2 = List.of(new AbsoluteBendpointDto(300, 151));
        tracker.recordPath(bp2, new int[]{100, 151}, new int[]{300, 400});

        Map<String, Integer> occupancy = tracker.getCorridorOccupancy();
        assertEquals("H:150 corridor should have occupancy 2 (grouped)", Integer.valueOf(2), occupancy.get("H:150"));
        assertNull("H:151 should not exist as separate key", occupancy.get("H:151"));
    }

    @Test
    public void recordPath_shouldSeparateBeyondTolerance() {
        // First path through H:150
        List<AbsoluteBendpointDto> bp1 = List.of(new AbsoluteBendpointDto(200, 150));
        tracker.recordPath(bp1, new int[]{100, 150}, new int[]{200, 300});

        // Second path through H:155 — beyond tolerance of 2px, should be separate
        List<AbsoluteBendpointDto> bp2 = List.of(new AbsoluteBendpointDto(300, 155));
        tracker.recordPath(bp2, new int[]{100, 155}, new int[]{300, 400});

        Map<String, Integer> occupancy = tracker.getCorridorOccupancy();
        assertEquals("H:150 corridor should have occupancy 1", Integer.valueOf(1), occupancy.get("H:150"));
        assertEquals("H:155 corridor should have occupancy 1", Integer.valueOf(1), occupancy.get("H:155"));
    }

    @Test
    public void recordPath_shouldHandleHorizontalAndVertical() {
        // L-shaped path: (100,100) → (300,100) → (300,400) — H:100 and V:300
        List<AbsoluteBendpointDto> bendpoints = List.of(new AbsoluteBendpointDto(300, 100));
        tracker.recordPath(bendpoints, new int[]{100, 100}, new int[]{300, 400});

        Map<String, Integer> occupancy = tracker.getCorridorOccupancy();
        assertTrue("Should have H:100 corridor", occupancy.containsKey("H:100"));
        assertTrue("Should have V:300 corridor", occupancy.containsKey("V:300"));
        assertEquals(Integer.valueOf(1), occupancy.get("H:100"));
        assertEquals(Integer.valueOf(1), occupancy.get("V:300"));
    }

    @Test
    public void recordPath_shouldIgnoreDiagonalSegments() {
        // Diagonal path: (100,100) → (200,200) — no axis-aligned segments
        List<AbsoluteBendpointDto> bendpoints = new ArrayList<>();
        tracker.recordPath(bendpoints, new int[]{100, 100}, new int[]{200, 200});

        Map<String, Integer> occupancy = tracker.getCorridorOccupancy();
        assertTrue("Diagonal segments should produce no corridor occupancy", occupancy.isEmpty());
    }

    // --- getOccupancy tests ---

    @Test
    public void getOccupancy_shouldReturnZeroForEmptyTracker() {
        assertEquals(0, tracker.getOccupancy(100, 200, 300, 200));
    }

    @Test
    public void getOccupancy_shouldReturnCountForOccupiedCorridor() {
        // Record 3 paths through H:200
        for (int i = 0; i < 3; i++) {
            List<AbsoluteBendpointDto> bp = List.of(new AbsoluteBendpointDto(300 + i * 10, 200));
            tracker.recordPath(bp, new int[]{100 + i * 10, 200}, new int[]{300 + i * 10, 400});
        }

        assertEquals("H:200 corridor should have occupancy 3", 3, tracker.getOccupancy(100, 200, 500, 200));
    }

    @Test
    public void getOccupancy_shouldReturnZeroForUnusedCorridor() {
        // Record path through H:200
        List<AbsoluteBendpointDto> bp = List.of(new AbsoluteBendpointDto(300, 200));
        tracker.recordPath(bp, new int[]{100, 200}, new int[]{300, 400});

        assertEquals("V:300 corridor should not affect H:500 query", 0, tracker.getOccupancy(100, 500, 300, 500));
    }

    // --- Multiple paths accumulation ---

    @Test
    public void multiplePaths_shouldAccumulateCorrectly() {
        // 5 paths through overlapping corridors
        for (int i = 0; i < 5; i++) {
            // All paths share H:200 corridor, diverge on vertical
            List<AbsoluteBendpointDto> bp = List.of(new AbsoluteBendpointDto(200 + i * 50, 200));
            tracker.recordPath(bp, new int[]{100, 200}, new int[]{200 + i * 50, 500});
        }

        assertEquals("H:200 shared by all 5 paths", 5, tracker.getOccupancy(100, 200, 500, 200));
        // Each vertical corridor used once (different x coordinates, spaced >2px apart)
        assertEquals("V:200 used once", 1, tracker.getOccupancy(200, 200, 200, 500));
        assertEquals("V:250 used once", 1, tracker.getOccupancy(250, 200, 250, 500));
        assertEquals("V:300 used once", 1, tracker.getOccupancy(300, 200, 300, 500));
    }

    // --- getRecordedPathCount ---

    @Test
    public void getRecordedPathCount_shouldTrackTotalPaths() {
        assertEquals(0, tracker.getRecordedPathCount());

        tracker.recordPath(List.of(), new int[]{0, 0}, new int[]{100, 0});
        assertEquals(1, tracker.getRecordedPathCount());

        tracker.recordPath(List.of(), new int[]{0, 0}, new int[]{0, 100});
        assertEquals(2, tracker.getRecordedPathCount());

        tracker.recordPath(List.of(), new int[]{0, 0}, new int[]{100, 100});
        assertEquals(3, tracker.getRecordedPathCount());
    }
}
