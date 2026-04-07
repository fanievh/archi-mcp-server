package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-geometry calculator for group layout arrangements (Story B30).
 * Computes element positions for row, column, and grid arrangements
 * given pre-resolved element sizes.
 *
 * <p>No EMF imports — operates on simple int arrays.
 * Only used by {@link ArchiModelAccessorImpl}.</p>
 */
class GroupLayoutCalculator {

    /** Average character width in pixels for Archi's ~9pt sans-serif font. */
    static final int AVG_CHAR_WIDTH = 8;
    /** Horizontal padding for element icon space + text margins. */
    static final int HORIZONTAL_PADDING = 30;
    /** Minimum auto-computed width to prevent degenerate sizing. */
    static final int MIN_AUTO_WIDTH = 60;
    /** Default width for elements with null/empty names (Archi's default). */
    static final int DEFAULT_ELEMENT_WIDTH = 120;

    /** Result of grid layout computation, including positions and the actual column count used. */
    record GridLayoutResult(List<int[]> positions, int columnsUsed) {}

    /**
     * Computes auto-width for a single element based on its label text.
     * Returns DEFAULT_ELEMENT_WIDTH for null/empty names, MIN_AUTO_WIDTH floor applied.
     *
     * @param name the element's display name (may be null)
     * @return computed width in pixels
     */
    static int computeAutoWidth(String name) {
        if (name == null || name.isEmpty()) {
            return DEFAULT_ELEMENT_WIDTH;
        }
        int estimatedWidth = (name.length() * AVG_CHAR_WIDTH) + HORIZONTAL_PADDING;
        return Math.max(MIN_AUTO_WIDTH, estimatedWidth);
    }

    /**
     * Computes row arrangement positions (left-to-right).
     *
     * @param elementSizes list of [width, height] per element
     * @param startX       left padding offset
     * @param startY       top offset (padding + label height)
     * @param spacing      gap between adjacent elements in pixels
     * @return list of [x, y, w, h] per element
     */
    static List<int[]> computeRowLayout(List<int[]> elementSizes,
            int startX, int startY, int spacing) {
        List<int[]> positions = new ArrayList<>();
        int currentX = startX;
        for (int[] size : elementSizes) {
            int w = size[0];
            int h = size[1];
            positions.add(new int[]{currentX, startY, w, h});
            currentX += w + spacing;
        }
        return positions;
    }

    /**
     * Computes column arrangement positions (top-to-bottom).
     *
     * @param elementSizes list of [width, height] per element
     * @param startX       left padding offset
     * @param startY       top offset (padding + label height)
     * @param spacing      gap between adjacent elements in pixels
     * @return list of [x, y, w, h] per element
     */
    static List<int[]> computeColumnLayout(List<int[]> elementSizes,
            int startX, int startY, int spacing) {
        List<int[]> positions = new ArrayList<>();
        int currentY = startY;
        for (int[] size : elementSizes) {
            int w = size[0];
            int h = size[1];
            positions.add(new int[]{startX, currentY, w, h});
            currentY += h + spacing;
        }
        return positions;
    }

    /**
     * Computes grid arrangement positions (left-to-right, top-to-bottom).
     * If columns is non-null, uses the specified column count (capped at element count).
     * Otherwise auto-detects from available group width.
     *
     * @param elementSizes list of [width, height] per element
     * @param startX       left padding offset
     * @param startY       top offset (padding + label height)
     * @param spacing      gap between elements in pixels
     * @param padding      group edge padding (for auto column detection)
     * @param groupWidth   current group width (for auto column detection)
     * @param columns      explicit column count (null for auto-detect)
     * @return grid layout result with positions and columns used
     */
    static GridLayoutResult computeGridLayout(List<int[]> elementSizes,
            int startX, int startY, int spacing, int padding, int groupWidth,
            Integer columns) {
        List<int[]> positions = new ArrayList<>();

        // Determine max element dimensions for uniform grid cells
        int maxW = 0;
        int maxH = 0;
        for (int[] size : elementSizes) {
            maxW = Math.max(maxW, size[0]);
            maxH = Math.max(maxH, size[1]);
        }

        // Calculate column count
        int cols;
        if (columns != null) {
            cols = Math.min(columns, elementSizes.size());
        } else {
            int availableWidth = groupWidth - 2 * padding;
            cols = Math.max(1, (availableWidth + spacing) / (maxW + spacing));
        }

        int currentX = startX;
        int currentY = startY;
        int col = 0;

        for (int[] size : elementSizes) {
            int h = size[1];
            positions.add(new int[]{currentX, currentY, maxW, h});

            col++;
            if (col >= cols) {
                col = 0;
                currentX = startX;
                currentY += maxH + spacing;
            } else {
                currentX += maxW + spacing;
            }
        }
        return new GridLayoutResult(positions, cols);
    }

    /**
     * Computes the auto-resize dimensions for a group based on child positions.
     * Label height is already baked into each position's Y coordinate via startY,
     * so it is not needed here.
     *
     * @param positions list of [x, y, w, h] per element
     * @param padding   group edge padding
     * @return [newWidth, newHeight]
     */
    static int[] computeAutoResizeDimensions(List<int[]> positions, int padding) {
        int maxRight = 0;
        int maxBottom = 0;
        for (int[] pos : positions) {
            maxRight = Math.max(maxRight, pos[0] + pos[2]);
            maxBottom = Math.max(maxBottom, pos[1] + pos[3]);
        }
        int newWidth = maxRight + padding;
        int newHeight = maxBottom + padding;
        return new int[]{newWidth, newHeight};
    }

    /**
     * Validates that no groups overlap each other (AABB intersection test).
     * Uses strict overlap semantics consistent with {@code LayoutQualityAssessor.computeOverlaps()}:
     * exact edge touching is NOT an overlap.
     *
     * @param groupRects list of group rects [x, y, w, h]
     * @return true if all gaps are sufficient (no overlaps), false if any overlap exists
     */
    static boolean validateGroupGaps(List<int[]> groupRects) {
        for (int i = 0; i < groupRects.size(); i++) {
            for (int j = i + 1; j < groupRects.size(); j++) {
                if (rectanglesOverlap(groupRects.get(i), groupRects.get(j))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * AABB overlap test: returns true if two rectangles strictly overlap.
     * Exact edge touching (shared boundary) returns false (not an overlap).
     */
    private static boolean rectanglesOverlap(int[] a, int[] b) {
        return a[0] < b[0] + b[2]       // a.left < b.right
            && a[0] + a[2] > b[0]       // a.right > b.left
            && a[1] < b[1] + b[3]       // a.top < b.bottom
            && a[1] + a[3] > b[1];      // a.bottom > b.top
    }
}
