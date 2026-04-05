package net.vheerden.archi.mcp.model;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

/**
 * Unit tests for ArrangementDetector.detect() — pure geometry,
 * no EMF/OSGi required. (Story backlog-b2)
 */
public class ArrangementDetectionTest {

    @Test
    public void shouldDetectRow_whenAllShareSameY() {
        List<int[]> positions = List.of(
                new int[]{10, 30, 120, 55},
                new int[]{140, 30, 120, 55},
                new int[]{270, 30, 120, 55});

        ArrangementDetector.DetectedArrangement result =
                ArrangementDetector.detect(positions);

        assertEquals("row", result.type());
        assertNull(result.gridColumns());
    }

    @Test
    public void shouldDetectColumn_whenAllShareSameX() {
        List<int[]> positions = List.of(
                new int[]{10, 30, 120, 55},
                new int[]{10, 95, 120, 55},
                new int[]{10, 160, 120, 55});

        ArrangementDetector.DetectedArrangement result =
                ArrangementDetector.detect(positions);

        assertEquals("column", result.type());
        assertNull(result.gridColumns());
    }

    @Test
    public void shouldDetectGrid_whenMultipleRowsAndColumns() {
        // 2x3 grid (3 columns, 2 rows)
        List<int[]> positions = List.of(
                new int[]{10, 30, 120, 55},
                new int[]{140, 30, 120, 55},
                new int[]{270, 30, 120, 55},
                new int[]{10, 95, 120, 55},
                new int[]{140, 95, 120, 55},
                new int[]{270, 95, 120, 55});

        ArrangementDetector.DetectedArrangement result =
                ArrangementDetector.detect(positions);

        assertEquals("grid", result.type());
        assertEquals(Integer.valueOf(3), result.gridColumns());
    }

    @Test
    public void shouldFallbackToColumn_whenSingleElement() {
        List<int[]> positions = List.of(new int[]{10, 30, 120, 55});

        ArrangementDetector.DetectedArrangement result =
                ArrangementDetector.detect(positions);

        assertEquals("column", result.type());
        assertNull(result.gridColumns());
    }

    @Test
    public void shouldFallbackToColumn_whenNullPositions() {
        ArrangementDetector.DetectedArrangement result =
                ArrangementDetector.detect(null);

        assertEquals("column", result.type());
        assertNull(result.gridColumns());
    }

    @Test
    public void shouldFallbackToColumn_whenEmptyPositions() {
        ArrangementDetector.DetectedArrangement result =
                ArrangementDetector.detect(List.of());

        assertEquals("column", result.type());
        assertNull(result.gridColumns());
    }

    @Test
    public void shouldDetectRow_withinTolerance() {
        // Y values differ by 1px (within 2px tolerance)
        List<int[]> positions = List.of(
                new int[]{10, 30, 120, 55},
                new int[]{140, 31, 120, 55},
                new int[]{270, 29, 120, 55});

        ArrangementDetector.DetectedArrangement result =
                ArrangementDetector.detect(positions);

        assertEquals("row", result.type());
    }

    @Test
    public void shouldDetectColumn_withinTolerance() {
        // X values differ by 2px (within 2px tolerance)
        List<int[]> positions = List.of(
                new int[]{10, 30, 120, 55},
                new int[]{12, 95, 120, 55},
                new int[]{10, 160, 120, 55});

        ArrangementDetector.DetectedArrangement result =
                ArrangementDetector.detect(positions);

        assertEquals("column", result.type());
    }

    @Test
    public void shouldDetectGrid_withCorrectColumnCount_2x2() {
        // 2x2 grid (2 columns)
        List<int[]> positions = List.of(
                new int[]{10, 30, 120, 55},
                new int[]{140, 30, 120, 55},
                new int[]{10, 95, 120, 55},
                new int[]{140, 95, 120, 55});

        ArrangementDetector.DetectedArrangement result =
                ArrangementDetector.detect(positions);

        assertEquals("grid", result.type());
        assertEquals(Integer.valueOf(2), result.gridColumns());
    }

    @Test
    public void shouldDetectGrid_withPartialLastRow() {
        // 3 elements in 2 columns — partial last row
        List<int[]> positions = List.of(
                new int[]{10, 30, 120, 55},
                new int[]{140, 30, 120, 55},
                new int[]{10, 95, 120, 55});

        ArrangementDetector.DetectedArrangement result =
                ArrangementDetector.detect(positions);

        assertEquals("grid", result.type());
        assertEquals(Integer.valueOf(2), result.gridColumns());
    }

    @Test
    public void shouldDetectRow_withTwoElements() {
        List<int[]> positions = List.of(
                new int[]{10, 30, 120, 55},
                new int[]{140, 30, 120, 55});

        ArrangementDetector.DetectedArrangement result =
                ArrangementDetector.detect(positions);

        assertEquals("row", result.type());
    }

    @Test
    public void shouldDetectColumn_withTwoElements() {
        List<int[]> positions = List.of(
                new int[]{10, 30, 120, 55},
                new int[]{10, 95, 120, 55});

        ArrangementDetector.DetectedArrangement result =
                ArrangementDetector.detect(positions);

        assertEquals("column", result.type());
    }
}
