package net.vheerden.archi.mcp.model.geometry;

/**
 * Shared geometry utilities for layout quality assessment, routing,
 * and edge attachment calculations.
 *
 * <p>Consolidates the Liang-Barsky line-segment-vs-rectangle intersection
 * algorithm that was previously duplicated across ConnectionRouter,
 * EdgeAttachmentCalculator, and LayoutQualityAssessor.</p>
 */
public final class GeometryUtils {

    private GeometryUtils() {}

    /**
     * Tests if a line segment intersects an axis-aligned rectangle
     * using the Liang-Barsky clipping algorithm (integer precision).
     *
     * @param x1  segment start x
     * @param y1  segment start y
     * @param x2  segment end x
     * @param y2  segment end y
     * @param rx  rectangle left
     * @param ry  rectangle top
     * @param rw  rectangle width
     * @param rh  rectangle height
     * @return true if the segment intersects the rectangle
     */
    public static boolean lineSegmentIntersectsRect(int x1, int y1, int x2, int y2,
                                                     int rx, int ry, int rw, int rh) {
        int dx = x2 - x1;
        int dy = y2 - y1;

        int[] p = {-dx, dx, -dy, dy};
        int[] q = {x1 - rx, rx + rw - x1, y1 - ry, ry + rh - y1};

        double tMin = 0.0;
        double tMax = 1.0;

        for (int i = 0; i < 4; i++) {
            if (Math.abs(p[i]) < 1) {
                if (q[i] < 0) return false;
            } else {
                double t = (double) q[i] / p[i];
                if (p[i] < 0) {
                    tMin = Math.max(tMin, t);
                } else {
                    tMax = Math.min(tMax, t);
                }
                if (tMin > tMax) return false;
            }
        }
        return true;
    }

    /**
     * Tests if a line segment intersects an axis-aligned rectangle
     * using the Liang-Barsky clipping algorithm (double precision).
     *
     * @param x1  segment start x
     * @param y1  segment start y
     * @param x2  segment end x
     * @param y2  segment end y
     * @param rx  rectangle left
     * @param ry  rectangle top
     * @param rw  rectangle width
     * @param rh  rectangle height
     * @return true if the segment intersects the rectangle
     */
    public static boolean lineSegmentIntersectsRect(double x1, double y1, double x2, double y2,
                                                     double rx, double ry, double rw, double rh) {
        double dx = x2 - x1;
        double dy = y2 - y1;

        double[] p = {-dx, dx, -dy, dy};
        double[] q = {x1 - rx, rx + rw - x1, y1 - ry, ry + rh - y1};

        double tMin = 0.0;
        double tMax = 1.0;

        for (int i = 0; i < 4; i++) {
            if (Math.abs(p[i]) < 1e-10) {
                // Parallel to this edge
                if (q[i] < 0) return false; // Outside
            } else {
                double t = q[i] / p[i];
                if (p[i] < 0) {
                    tMin = Math.max(tMin, t);
                } else {
                    tMax = Math.min(tMax, t);
                }
                if (tMin > tMax) return false;
            }
        }
        return true;
    }
}
