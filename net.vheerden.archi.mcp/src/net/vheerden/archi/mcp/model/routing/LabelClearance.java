package net.vheerden.archi.mcp.model.routing;

import java.util.List;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Pure-geometry helper for label clearance in routed connections (Story 10-8).
 * Computes estimated label bounding rectangles along a routed path and checks
 * for overlap with element obstacles.
 *
 * <p>Label size estimation uses Archi's default ~11pt font metrics:
 * ~7px per character width, ~14px character height, with padding.</p>
 */
class LabelClearance {

    // Keep in sync with LayoutQualityAssessor.LABEL_CHAR_WIDTH etc.
    // (duplicated due to architecture boundary: model.routing vs model)
    static final double CHAR_WIDTH = 8.0;
    static final double CHAR_HEIGHT = 14.0;
    static final double PADDING_X = 10.0;
    static final double PADDING_Y = 6.0;

    /**
     * Computes the estimated label bounding rectangle for a routed connection path.
     *
     * @param path         routed bendpoint path (absolute coordinates)
     * @param sourceCenter source element center [x, y]
     * @param targetCenter target element center [x, y]
     * @param labelText    connection label text (may be null or empty)
     * @param textPosition 0=source (15%), 1=middle (50%), 2=target (85%)
     * @return RoutingRect representing the label bounds, or null if no label
     */
    static RoutingRect computeLabelRect(List<AbsoluteBendpointDto> path,
            int[] sourceCenter, int[] targetCenter,
            String labelText, int textPosition) {
        if (labelText == null || labelText.isEmpty()) {
            return null;
        }

        double labelWidth = labelText.length() * CHAR_WIDTH + PADDING_X;
        double labelHeight = CHAR_HEIGHT + PADDING_Y;

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

        // Center label rectangle at the computed point
        int lx = (int) Math.round(cx - labelWidth / 2);
        int ly = (int) Math.round(cy - labelHeight / 2);
        int lw = (int) Math.round(labelWidth);
        int lh = (int) Math.round(labelHeight);

        return new RoutingRect(lx, ly, lw, lh, null);
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
