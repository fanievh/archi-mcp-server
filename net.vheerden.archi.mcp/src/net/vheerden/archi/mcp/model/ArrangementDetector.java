package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-geometry utility for detecting arrangement patterns from element positions.
 * No EMF/Archi dependencies — safe for standard JUnit testing.
 * (Story backlog-b2)
 */
class ArrangementDetector {

    /** Result of arrangement detection from existing child positions. */
    record DetectedArrangement(String type, Integer gridColumns) {}

    private static final int TOLERANCE = 2;

    /**
     * Detects the arrangement pattern (row/column/grid) from existing child positions.
     * Uses a 2px tolerance for rounding variance from auto-width calculations.
     *
     * @param positions list of [x, y, w, h] arrays for each child element
     * @return detected arrangement type and grid column count (if grid)
     */
    static DetectedArrangement detect(List<int[]> positions) {
        if (positions == null || positions.size() <= 1) {
            return new DetectedArrangement("column", null);
        }

        // Collect distinct X and Y values (within tolerance)
        List<Integer> distinctX = new ArrayList<>();
        List<Integer> distinctY = new ArrayList<>();

        for (int[] pos : positions) {
            addIfDistinct(distinctX, pos[0], TOLERANCE);
            addIfDistinct(distinctY, pos[1], TOLERANCE);
        }

        // Row: all children share same Y (single distinct Y, multiple distinct X)
        if (distinctY.size() == 1 && distinctX.size() > 1) {
            return new DetectedArrangement("row", null);
        }

        // Column: all children share same X (single distinct X, multiple distinct Y)
        if (distinctX.size() == 1 && distinctY.size() > 1) {
            return new DetectedArrangement("column", null);
        }

        // Grid: multiple distinct X AND multiple distinct Y
        if (distinctX.size() > 1 && distinctY.size() > 1) {
            return new DetectedArrangement("grid", distinctX.size());
        }

        // Fallback
        return new DetectedArrangement("column", null);
    }

    /** Adds value to the list if no existing entry is within tolerance. */
    private static void addIfDistinct(List<Integer> values, int value, int tolerance) {
        for (int existing : values) {
            if (Math.abs(existing - value) <= tolerance) {
                return;
            }
        }
        values.add(value);
    }
}
