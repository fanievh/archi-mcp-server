package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure-geometry calculator for group layout arrangements.
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
     * Chooses the intra-group arrangement based on element count and inter-group
     * flow direction. For vertical inter-group flow (DOWN/UP), uses element count
     * to avoid tall/narrow groups. For horizontal flow (RIGHT/LEFT), preserves
     * column arrangement (current correct behavior).
     *
     * @param elementCount       number of elements in the group
     * @param interGroupDirection the inter-group flow direction (DOWN, UP, RIGHT, LEFT); null treated as DOWN
     * @return "row", "column", or "grid"
     */
    static String chooseIntraGroupArrangement(int elementCount, String interGroupDirection) {
        if ("RIGHT".equalsIgnoreCase(interGroupDirection)
                || "LEFT".equalsIgnoreCase(interGroupDirection)) {
            return "column";
        }
        // DOWN, UP, or null (default vertical flow)
        if (elementCount <= 3) {
            return "row";
        }
        return "grid";
    }

    /**
     * Computes the number of grid columns for a given element count.
     * Targets 2 rows for 4-8 elements, 3 rows for 9+.
     *
     * @param elementCount number of elements (must be >= 4)
     * @return number of columns
     */
    static int computeGridColumns(int elementCount) {
        if (elementCount < 4) {
            return Math.max(1, elementCount);
        }
        int targetRows = (elementCount <= 8) ? 2 : 3;
        return (int) Math.ceil((double) elementCount / targetRows);
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
        return computeGridLayout(elementSizes, startX, startY, spacing, padding, groupWidth,
                columns, true);
    }

    /**
     * As {@link #computeGridLayout(List, int, int, int, int, int, Integer)}, with control over how
     * a cell's width is chosen.
     *
     * <p>With {@code uniformCellWidth} true — the default every existing caller keeps — every cell
     * takes the width of the widest element anywhere in the grid. That is easy to reason about when
     * a caller lays out one container and reads the result back. It is not when the same grid is
     * applied at several nesting levels in one call: the widest element inflates its siblings,
     * their container fits to the inflated row, and that container then inflates <em>its</em>
     * siblings one level up. A single over-wide grandchild can multiply a top-level band's width.
     *
     * <p>With it false, each column takes the width of the widest element <em>in that column</em>.
     * Columns still line up across rows — column <i>j</i> has one width in every row — so this
     * costs no grid alignment; only the inflation is dropped.
     *
     * <p>Row height is left uniform on BOTH paths — {@code uniformCellWidth} touches
     * {@code columnWidths} alone, while {@code maxH} and the row advance below are unconditional —
     * and that is now a RETAINED behaviour rather than a costless one. The compounding half of the
     * original reasoning still holds: a container's height is the sum of its rows either way. The
     * second half, that no observed defect asked for it, has been falsified. An end-to-end run
     * measured a single-column grid of mixed-height containers coming out close to twice the height
     * of the same content arranged as a column, because every row advances by the tallest element
     * anywhere in the grid regardless of what is in that row. Changing the advance is a behaviour
     * change with its own callers to re-measure and is deliberately not made here; the inflation is
     * documented on the {@code columns} parameter instead, so a caller can see the cost and pick
     * the column arrangement. Do not read this paragraph as a reason to leave the question
     * closed.
     *
     * @param uniformCellWidth true for one width across the whole grid, false for per-column widths
     */
    static GridLayoutResult computeGridLayout(List<int[]> elementSizes,
            int startX, int startY, int spacing, int padding, int groupWidth,
            Integer columns, boolean uniformCellWidth) {
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
            // Auto-detection asks "how many of the widest element fit", which is a question about
            // the whole grid regardless of how individual cells are then sized.
            int availableWidth = groupWidth - 2 * padding;
            cols = Math.max(1, (availableWidth + spacing) / (maxW + spacing));
        }

        int[] columnWidths = new int[Math.max(1, cols)];
        for (int i = 0; i < elementSizes.size(); i++) {
            int c = (cols > 0) ? i % cols : 0;
            columnWidths[c] = uniformCellWidth ? maxW
                    : Math.max(columnWidths[c], elementSizes.get(i)[0]);
        }

        int currentX = startX;
        int currentY = startY;
        int col = 0;

        for (int[] size : elementSizes) {
            int h = size[1];
            int w = columnWidths[col];
            positions.add(new int[]{currentX, currentY, w, h});

            col++;
            if (col >= cols) {
                col = 0;
                currentX = startX;
                currentY += maxH + spacing;
            } else {
                currentX += w + spacing;
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

    // ---- Spacing detection methods ----

    /** Height reserved for group label text in the top area of a group. */
    static final int GROUP_LABEL_HEIGHT = 24;

    /** Default spacing returned when detection is not possible (e.g., single element). */
    static final int DEFAULT_DETECTED_SPACING = 40;

    /** Default padding returned when detection is not possible. */
    static final int DEFAULT_DETECTED_PADDING = 10;

    /**
     * Detects the inter-element spacing from existing child positions within a group.
     * Uses the arrangement type to determine which axis to measure gaps on.
     *
     * @param positions   list of [x, y, w, h] per element (relative to group origin)
     * @param arrangement "row", "column", or "grid"
     * @return detected spacing in pixels (minimum gap between adjacent elements),
     *         or {@link #DEFAULT_DETECTED_SPACING} if detection is not possible
     */
    static int detectSpacingFromPositions(List<int[]> positions, String arrangement) {
        if (positions.size() < 2) {
            return DEFAULT_DETECTED_SPACING;
        }
        if ("row".equals(arrangement)) {
            return detectMinGapOnAxis(positions, true);
        } else if ("column".equals(arrangement)) {
            return detectMinGapOnAxis(positions, false);
        } else {
            // Grid: compute min gap on both axes, return the smaller
            int hGap = detectMinGapOnAxis(positions, true);
            int vGap = detectMinGapOnAxis(positions, false);
            return Math.min(hGap, vGap);
        }
    }

    /**
     * Detects the minimum gap between consecutive elements along one axis.
     *
     * @param positions  list of [x, y, w, h] per element
     * @param horizontal true for x-axis (row), false for y-axis (column)
     * @return minimum gap in pixels, or DEFAULT_DETECTED_SPACING if < 2 elements
     */
    private static int detectMinGapOnAxis(List<int[]> positions, boolean horizontal) {
        int posIdx = horizontal ? 0 : 1;   // x or y
        int sizeIdx = horizontal ? 2 : 3;  // w or h

        List<int[]> sorted = new ArrayList<>(positions);
        sorted.sort(Comparator.comparingInt(a -> a[posIdx]));

        int minGap = Integer.MAX_VALUE;
        for (int i = 0; i < sorted.size() - 1; i++) {
            int rightEdge = sorted.get(i)[posIdx] + sorted.get(i)[sizeIdx];
            int nextLeftEdge = sorted.get(i + 1)[posIdx];
            int gap = nextLeftEdge - rightEdge;
            if (gap >= 0) {
                minGap = Math.min(minGap, gap);
            }
        }
        return minGap == Integer.MAX_VALUE ? DEFAULT_DETECTED_SPACING : Math.max(0, minGap);
    }

    /**
     * Detects the padding between a group's boundary and its child elements.
     * Accounts for {@link #GROUP_LABEL_HEIGHT} on the top edge.
     *
     * @param positions   list of [x, y, w, h] per element (relative to group origin)
     * @param groupWidth  the group's width
     * @param groupHeight the group's height
     * @return detected padding in pixels (minimum of left and adjusted-top padding),
     *         or {@link #DEFAULT_DETECTED_PADDING} if detection is not possible
     */
    static int detectPaddingFromPositions(List<int[]> positions, int groupWidth, int groupHeight) {
        if (positions.isEmpty()) {
            return DEFAULT_DETECTED_PADDING;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxRight = 0;
        int maxBottom = 0;
        for (int[] pos : positions) {
            minX = Math.min(minX, pos[0]);
            minY = Math.min(minY, pos[1]);
            maxRight = Math.max(maxRight, pos[0] + pos[2]);
            maxBottom = Math.max(maxBottom, pos[1] + pos[3]);
        }
        int leftPadding = minX;
        int topPadding = minY - GROUP_LABEL_HEIGHT;
        int rightPadding = groupWidth - maxRight;
        int bottomPadding = groupHeight - maxBottom;
        // Return the minimum positive padding found
        int padding = Integer.MAX_VALUE;
        if (leftPadding >= 0) padding = Math.min(padding, leftPadding);
        if (topPadding >= 0) padding = Math.min(padding, topPadding);
        if (rightPadding >= 0) padding = Math.min(padding, rightPadding);
        if (bottomPadding >= 0) padding = Math.min(padding, bottomPadding);
        return padding == Integer.MAX_VALUE ? DEFAULT_DETECTED_PADDING : padding;
    }

    /**
     * Detects the inter-group spacing from existing top-level group rectangles
     * — the MIN gap between adjacent groups along the dominant axis (most-tight
     * pair wins, aligns with visual-severity hierarchy where edge-coincident
     * routing forms in the tightest inter-group corridor).
     *
     * <p>Dominant axis selection mirrors {@link #computeInterGroupShifts}:
     * compare horizontal spread vs vertical spread; the axis with greater
     * spread is dominant. Groups are sorted along the dominant axis, then
     * the gap between each adjacent pair (right-edge to left-edge for
     * horizontal; bottom-edge to top-edge for vertical) is measured. Returns
     * the minimum across adjacent pairs.</p>
     *
     * <p>This is the inter-group counterpart to
     * {@link #detectSpacingFromPositions(List, String)} — same single-source-
     * of-truth pattern. Both the {@code apply-group-spacing-recommendations}
     * convenience tool AND any future caller that needs current inter-group
     * spacing as a reading should call this utility, not measure independently.</p>
     *
     * @param groupRects list of [x, y, w, h] per top-level group; expected to
     *                   be the bounds of each top-level diagram-model group
     *                   (in the view's coordinate system — absolute positions)
     * @return MIN gap between adjacent groups in pixels along the dominant
     *         axis; returns {@link #DEFAULT_DETECTED_SPACING} when fewer than
     *         2 groups (degenerate input — no inter-group concept exists)
     *         OR when all adjacent pairs along the dominant axis have
     *         negative gaps (full overlap on the dominant axis — sibling-
     *         symmetric with {@link #detectSpacingFromPositions(List, String)},
     *         which uses the same {@code detectMinGapOnAxis} primitive that
     *         skips negative gaps and falls through to DEFAULT). Top-level
     *         group overlap is rare in practice — Archi's UI does not
     *         auto-produce overlapping top-level groups, and the convenience
     *         tool's heuristic relies on valid layouts; the
     *         {@code DEFAULT_DETECTED_SPACING} reading on the degenerate path
     *         is benign because the tool's delta math (clamped to ≥ 0) still
     *         produces a positive delta against any tier target ≥ 40.
     */
    static int detectInterGroupSpacing(List<int[]> groupRects) {
        if (groupRects.size() < 2) {
            return DEFAULT_DETECTED_SPACING;
        }

        // Detect dominant axis (mirrors computeInterGroupShifts)
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int[] gp : groupRects) {
            minX = Math.min(minX, gp[0]);
            maxX = Math.max(maxX, gp[0] + gp[2]);
            minY = Math.min(minY, gp[1]);
            maxY = Math.max(maxY, gp[1] + gp[3]);
        }
        boolean horizontal = (maxX - minX) >= (maxY - minY);

        // Reuse detectMinGapOnAxis — same MIN-gap-along-axis logic as
        // intra-group element spacing. The semantics line up: gap means
        // right-edge-to-left-edge (horizontal) or bottom-edge-to-top-edge
        // (vertical), measured between adjacent pairs sorted along the
        // chosen axis, with negative gaps clamped to 0 (overlapping groups).
        return detectMinGapOnAxis(groupRects, horizontal);
    }

    /**
     * Computes new group positions after adding an inter-group spacing delta.
     * Detects the dominant axis (horizontal or vertical spread) and shifts
     * each group beyond the first by {@code i * interGroupDelta} on that axis.
     *
     * <p>Groups are sorted by position on the dominant axis. The first group
     * stays in place; subsequent groups accumulate the delta.
     *
     * @param groupPositions  list of [x, y, w, h] per group
     * @param interGroupDelta pixels to add between each pair of adjacent groups
     * @return list of [x, y, w, h] with updated positions (dimensions preserved)
     */
    static List<int[]> computeInterGroupShifts(List<int[]> groupPositions, int interGroupDelta) {
        if (groupPositions.size() < 2 || interGroupDelta == 0) {
            // Return copies with unchanged positions
            List<int[]> result = new ArrayList<>();
            for (int[] gp : groupPositions) {
                result.add(new int[]{gp[0], gp[1], gp[2], gp[3]});
            }
            return result;
        }

        // Detect dominant axis: compare spread in x vs y
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int[] gp : groupPositions) {
            minX = Math.min(minX, gp[0]);
            maxX = Math.max(maxX, gp[0] + gp[2]);
            minY = Math.min(minY, gp[1]);
            maxY = Math.max(maxY, gp[1] + gp[3]);
        }
        boolean horizontal = (maxX - minX) >= (maxY - minY);
        int posIdx = horizontal ? 0 : 1;

        // Build index array sorted by dominant axis
        Integer[] indices = new Integer[groupPositions.size()];
        for (int i = 0; i < indices.length; i++) indices[i] = i;
        java.util.Arrays.sort(indices, Comparator.comparingInt(i -> groupPositions.get(i)[posIdx]));

        // Compute shifts: group at index[0] stays, each subsequent shifts by i * delta
        List<int[]> result = new ArrayList<>();
        for (int[] gp : groupPositions) {
            result.add(new int[]{gp[0], gp[1], gp[2], gp[3]});
        }
        for (int rank = 0; rank < indices.length; rank++) {
            int origIdx = indices[rank];
            int shift = rank * interGroupDelta;
            result.get(origIdx)[posIdx] += shift;
        }
        return result;
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
