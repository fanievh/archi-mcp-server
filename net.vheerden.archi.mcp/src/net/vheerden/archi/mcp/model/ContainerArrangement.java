package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;

import com.archimatetool.model.IBounds;
import com.archimatetool.model.IDiagramModelObject;

/**
 * Where a run of containers goes when they are laid out in a row, a column or a grid.
 *
 * <p>Pure geometry over the containers' current sizes: no model is read beyond each container's
 * bounds and nothing is written, so the same computation can be run against the view's coordinate
 * space or against a container's own without either knowing which it is. That is the whole reason
 * it lives apart from its caller — {@code arrange-groups} lays out the view's own containers in
 * canvas coordinates and the containers inside a host in coordinates relative to that host, and
 * two copies of this arithmetic would be two arrangements that could drift apart.
 *
 * <p>The origin is a parameter for the same reason. A container drawn at the view's top level
 * starts at the canvas origin; one drawn inside a host starts at that host's own content origin,
 * and the numbers written for it are relative to the host rather than to the canvas.
 */
final class ContainerArrangement {

    private ContainerArrangement() {}

    /** The origin every arrangement starts from, in whichever space it is being computed. */
    static final int ORIGIN = 20;

    /** The canvas width the grid's automatic column count is estimated against. */
    private static final int ESTIMATED_CANVAS_WIDTH = 1200;

    /**
     * One arrangement's result.
     *
     * @param positions the {@code [x, y]} each container moves to, in the same order as the input
     *     and in the coordinate space the origin was given in
     * @param layoutWidth the width the arrangement occupies, measured from the same origin
     * @param layoutHeight the height the arrangement occupies, measured from the same origin
     * @param columnsUsed the grid's column count, null for a row or a column arrangement
     */
    record Placement(List<int[]> positions, int layoutWidth, int layoutHeight,
            Integer columnsUsed) {}

    /**
     * Lays {@code containers} out from {@code (originX, originY)} in the requested arrangement.
     *
     * @param arrangement one of {@code row}, {@code column} or {@code grid}, already normalized
     * @param columns the caller's grid column count, or null to estimate one
     * @param spacing the gap between adjacent containers
     * @param laneSizes per-gap widths that override {@code spacing} where the standalone lane has
     *     something to place; empty when the lane did not run, which is the ordinary case
     * @throws IllegalArgumentException if {@code arrangement} is none of the three
     */
    static Placement compute(List<IDiagramModelObject> containers, String arrangement,
            Integer columns, int spacing, List<Integer> laneSizes, int originX, int originY) {
        if (containers.isEmpty()) {
            // Nothing to lay out. Answered here rather than inside each arrangement because the
            // grid's automatic column count divides by the widest container, which is zero when
            // there is none: with spacing 0 — a legal argument — that is a division by zero, and
            // the column count it would otherwise report is 0, which reads as "the grid resolved
            // to zero columns" rather than "no grid was computed". A row or column arrangement
            // already returned exactly these bounds for an empty list, so this changes neither.
            return new Placement(List.of(), originX, originY, null);
        }
        switch (arrangement) {
        case "row":
            return row(containers, spacing, laneSizes, originX, originY);
        case "column":
            return column(containers, spacing, laneSizes, originX, originY);
        case "grid":
            return grid(containers, columns, spacing, originX, originY);
        default:
            throw new IllegalArgumentException("Unexpected arrangement: " + arrangement);
        }
    }

    private static Placement row(List<IDiagramModelObject> containers, int spacing,
            List<Integer> laneSizes, int originX, int originY) {
        List<int[]> positions = new ArrayList<>();
        int curX = originX;
        int maxH = 0;
        int n = containers.size();
        for (int i = 0; i < n; i++) {
            IBounds b = containers.get(i).getBounds();
            positions.add(new int[]{curX, originY});
            curX += b.getWidth();
            if (i < n - 1) {
                curX += gapAfter(i, spacing, laneSizes);
            }
            maxH = Math.max(maxH, b.getHeight());
        }
        return new Placement(positions, curX, originY + maxH, null);
    }

    private static Placement column(List<IDiagramModelObject> containers, int spacing,
            List<Integer> laneSizes, int originX, int originY) {
        List<int[]> positions = new ArrayList<>();
        int curY = originY;
        int maxW = 0;
        int n = containers.size();
        for (int i = 0; i < n; i++) {
            IBounds b = containers.get(i).getBounds();
            positions.add(new int[]{originX, curY});
            curY += b.getHeight();
            if (i < n - 1) {
                curY += gapAfter(i, spacing, laneSizes);
            }
            maxW = Math.max(maxW, b.getWidth());
        }
        return new Placement(positions, originX + maxW, curY, null);
    }

    private static Placement grid(List<IDiagramModelObject> containers, Integer columns,
            int spacing, int originX, int originY) {
        List<int[]> positions = new ArrayList<>();
        int cols = (columns != null) ? columns : estimateColumns(containers, spacing);
        int curX = originX;
        int curY = originY;
        int colIdx = 0;
        int rowMaxH = 0;
        int layoutWidth = 0;
        for (int i = 0; i < containers.size(); i++) {
            IBounds b = containers.get(i).getBounds();
            positions.add(new int[]{curX, curY});
            rowMaxH = Math.max(rowMaxH, b.getHeight());
            layoutWidth = Math.max(layoutWidth, curX + b.getWidth());
            colIdx++;
            if (colIdx >= cols && i < containers.size() - 1) {
                curX = originX;
                curY += rowMaxH + spacing;
                colIdx = 0;
                rowMaxH = 0;
            } else {
                curX += b.getWidth() + spacing;
            }
        }
        return new Placement(positions, layoutWidth, curY + rowMaxH, cols);
    }

    /** How many columns the widest container allows across an estimated canvas, at least one. */
    private static int estimateColumns(List<IDiagramModelObject> containers, int spacing) {
        int widest = 0;
        for (IDiagramModelObject container : containers) {
            widest = Math.max(widest, container.getBounds().getWidth());
        }
        int cols = Math.max(1, (ESTIMATED_CANVAS_WIDTH + spacing) / (widest + spacing));
        // Never more columns than there are containers to put in them.
        return Math.min(cols, containers.size());
    }

    /**
     * The gap after container {@code i}: the lane's width where the standalone lane reserved one,
     * and the resolved spacing everywhere else. A lane size of zero means the lane assigned that
     * gap nothing, which is the ordinary spacing case rather than a request for no gap at all.
     */
    private static int gapAfter(int i, int spacing, List<Integer> laneSizes) {
        int laneSize = (i < laneSizes.size()) ? laneSizes.get(i) : 0;
        return (laneSize > 0) ? laneSize : spacing;
    }
}
