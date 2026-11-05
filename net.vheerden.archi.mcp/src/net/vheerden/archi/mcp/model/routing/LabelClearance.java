package net.vheerden.archi.mcp.model.routing;

import java.util.ArrayList;
import java.util.List;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Pure-geometry helper for label clearance in routed connections.
 * Computes estimated label bounding rectangles along a routed path and checks
 * for overlap with element obstacles.
 *
 * <p>The label size comes from {@link LabelBoxEstimate}. Two entry points name which
 * width a caller wants: {@link #computeRenderedLabelRect} for a consumer that
 * <b>scores</b> a label (the label optimizer, which must score the box the assessor
 * measures), and {@link #computeReservedLabelRect} for one that <b>reserves</b> space
 * by moving geometry (the router's label-clearance path shift). They also differ in where the box
 * is: the scored box is where Archi draws the label ({@link LabelAnchor}), the reserved box is
 * centred on a point a fixed fraction along the centre path.</p>
 */
class LabelClearance {

    /**
     * The label box a label is scored with: {@link LabelBoxEstimate#renderedWidth}, the box the
     * layout-quality assessor measures, where Archi draws the label on the drawn line, whose ends come from
     * the two endpoint rects. A Middle label (any {@code textPosition} other than 0 or 2) is centred on
     * {@link LabelAnchor#middlePoint}; a Source or Target label is the box
     * {@link LabelAnchor#endpointLabelBox} puts just beyond that end of the line. Each field is rounded to
     * a pixel.
     *
     * @param path         routed bendpoint path (absolute coordinates)
     * @param source       the source element's rect
     * @param target       the target element's rect
     * @param labelText    connection label text (may be null or empty)
     * @param textPosition 0=source, 2=target, anything else middle
     * @return RoutingRect representing the label bounds, or null if no label
     */
    static RoutingRect computeRenderedLabelRect(List<AbsoluteBendpointDto> path,
            RoutingRect source, RoutingRect target,
            String labelText, int textPosition) {
        if (labelText == null || labelText.isEmpty()) {
            return null;
        }
        int[] sourceCenter = {source.centerX(), source.centerY()};
        int[] targetCenter = {target.centerX(), target.centerY()};
        double labelWidth = LabelBoxEstimate.renderedWidth(labelText);
        if (centrePathLength(path, sourceCenter, targetCenter) < 1.0) {
            return null;
        }
        List<double[]> drawn = LabelAnchor.drawnPoints(
                centrePath(path, sourceCenter, targetCenter), rect(source), rect(target));
        if (textPosition == 0 || textPosition == 2) {
            boolean targetEnd = textPosition == 2;
            return cornerBox(LabelAnchor.endpointLabelBox(drawn, targetEnd, rect(targetEnd ? target : source),
                    labelWidth, LabelBoxEstimate.HEIGHT));
        }
        double[] anchor = LabelAnchor.middlePoint(drawn);
        return boxAt(anchor[0], anchor[1], labelWidth);
    }

    /**
     * The label box a label reserves: {@link LabelBoxEstimate#reservedWidth}, without the render
     * factor. For a consumer that moves geometry to make room for the label.
     *
     * <p>Every text position is centred on the point its fraction names along the centre path (15%
     * Source, 85% Target, else 50%), not where Archi draws the label ({@link LabelAnchor}): Archi draws
     * a Middle label at the drawn line's index midpoint and a Source or Target label just beyond that end
     * of the line. Route geometry is kept as it was; the label optimizer, which runs after the consumer
     * that reserves, scores the drawn box and has the last word on the slot.</p>
     *
     * @param path         routed bendpoint path (absolute coordinates)
     * @param sourceCenter source element center [x, y]
     * @param targetCenter target element center [x, y]
     * @param labelText    connection label text (may be null or empty)
     * @param textPosition 0=source (15%), 2=target (85%), anything else 50% along the centre path
     * @return RoutingRect representing the label bounds, or null if no label
     */
    static RoutingRect computeReservedLabelRect(List<AbsoluteBendpointDto> path,
            int[] sourceCenter, int[] targetCenter,
            String labelText, int textPosition) {
        if (labelText == null || labelText.isEmpty()) {
            return null;
        }
        return labelRectAt(path, sourceCenter, targetCenter, textPosition,
                LabelBoxEstimate.reservedWidth(labelText));
    }

    /**
     * A label box of the given width, one line high, centred on the point the {@code textPosition}
     * fraction (15% Source, 85% Target, else 50%) names along the length of the full centre path
     * (source centre, bendpoints, target centre); null for a path shorter than 1 px. The reserved box only
     * ({@link #computeReservedLabelRect}); it is not where Archi draws the label.
     */
    private static RoutingRect labelRectAt(List<AbsoluteBendpointDto> path,
            int[] sourceCenter, int[] targetCenter, int textPosition, double labelWidth) {
        // Build full path: source center → bendpoints → target center
        int fullPathSize = path.size() + 2;
        int[] xCoords = new int[fullPathSize];
        int[] yCoords = new int[fullPathSize];

        xCoords[0] = sourceCenter[0];
        yCoords[0] = sourceCenter[1];
        for (int i = 0; i < path.size(); i++) {
            xCoords[i + 1] = path.get(i).x();
            yCoords[i + 1] = path.get(i).y();
        }
        xCoords[fullPathSize - 1] = targetCenter[0];
        yCoords[fullPathSize - 1] = targetCenter[1];

        // Compute total path length
        double totalLength = 0;
        for (int i = 0; i < fullPathSize - 1; i++) {
            double dx = xCoords[i + 1] - xCoords[i];
            double dy = yCoords[i + 1] - yCoords[i];
            totalLength += Math.sqrt(dx * dx + dy * dy);
        }

        if (totalLength < 1.0) {
            return null;
        }

        double fraction;
        switch (textPosition) {
            case 0:  fraction = 0.15; break;
            case 2:  fraction = 0.85; break;
            default: fraction = 0.50; break;
        }

        double targetDist = totalLength * fraction;

        // Walk path to find point at targetDist
        double accumulated = 0;
        double cx = xCoords[0];
        double cy = yCoords[0];

        for (int i = 0; i < fullPathSize - 1; i++) {
            double dx = xCoords[i + 1] - xCoords[i];
            double dy = yCoords[i + 1] - yCoords[i];
            double segLen = Math.sqrt(dx * dx + dy * dy);
            if (accumulated + segLen >= targetDist) {
                double remaining = targetDist - accumulated;
                double t = (segLen > 0) ? remaining / segLen : 0;
                cx = xCoords[i] + dx * t;
                cy = yCoords[i] + dy * t;
                break;
            }
            accumulated += segLen;
        }

        return boxAt(cx, cy, labelWidth);
    }

    /** A label box of the given width, one line high, centred on (cx, cy); each field rounded to a pixel. */
    private static RoutingRect boxAt(double cx, double cy, double labelWidth) {
        double labelHeight = LabelBoxEstimate.HEIGHT;
        int lx = (int) Math.round(cx - labelWidth / 2);
        int ly = (int) Math.round(cy - labelHeight / 2);
        int lw = (int) Math.round(labelWidth);
        int lh = (int) Math.round(labelHeight);

        return new RoutingRect(lx, ly, lw, lh, null);
    }

    /** A label box from {@code [x, y, width, height]} (top-left), each field rounded to a pixel. */
    private static RoutingRect cornerBox(double[] box) {
        return new RoutingRect((int) Math.round(box[0]), (int) Math.round(box[1]),
                (int) Math.round(box[2]), (int) Math.round(box[3]), null);
    }

    /** The centre path: source centre, bendpoints, target centre. */
    static List<double[]> centrePath(List<AbsoluteBendpointDto> path, int[] sourceCenter, int[] targetCenter) {
        List<double[]> points = new ArrayList<>(path.size() + 2);
        points.add(new double[]{sourceCenter[0], sourceCenter[1]});
        for (AbsoluteBendpointDto bp : path) {
            points.add(new double[]{bp.x(), bp.y()});
        }
        points.add(new double[]{targetCenter[0], targetCenter[1]});
        return points;
    }

    /** Length of the centre path; a label on a path under 1 px is not placed. */
    private static double centrePathLength(List<AbsoluteBendpointDto> path, int[] sourceCenter,
            int[] targetCenter) {
        List<double[]> points = centrePath(path, sourceCenter, targetCenter);
        double total = 0;
        for (int i = 0; i < points.size() - 1; i++) {
            double dx = points.get(i + 1)[0] - points.get(i)[0];
            double dy = points.get(i + 1)[1] - points.get(i)[1];
            total += Math.sqrt(dx * dx + dy * dy);
        }
        return total;
    }

    /** {@code [x, y, width, height]} for the shared anchor model. */
    static double[] rect(RoutingRect r) {
        return new double[]{r.x(), r.y(), r.width(), r.height()};
    }

    /**
     * Checks if a label rectangle overlaps any obstacle.
     */
    static boolean overlapsAnyObstacle(RoutingRect labelRect, List<RoutingRect> obstacles) {
        if (labelRect == null) {
            return false;
        }
        for (RoutingRect obs : obstacles) {
            if (rectsOverlap(labelRect, obs)) {
                return true;
            }
        }
        return false;
    }

    private static boolean rectsOverlap(RoutingRect a, RoutingRect b) {
        return a.x() < b.x() + b.width()
                && a.x() + a.width() > b.x()
                && a.y() < b.y() + b.height()
                && a.y() + a.height() > b.y();
    }
}
