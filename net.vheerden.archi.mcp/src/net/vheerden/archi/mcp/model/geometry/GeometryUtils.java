package net.vheerden.archi.mcp.model.geometry;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared geometry utilities for layout quality assessment, routing,
 * and edge attachment calculations.
 *
 * <p>Consolidates the Liang-Barsky line-segment-vs-rectangle intersection
 * algorithm that was previously duplicated across ConnectionRouter,
 * EdgeAttachmentCalculator, and LayoutQualityAssessor.</p>
 *
 * <p>It also owns the <strong>visual pass-through policy</strong> — the perimeter clip plus the
 * inset-and-scan of {@link #clipPathToRectEdges} and {@link #pathPassesThroughRect}. That policy
 * has exactly two consumers and they MUST agree: {@code LayoutQualityAssessor}, which reports
 * {@code connectionThroughNoteCount}, and the routing side, which discloses the same crossing on
 * the call that created it. They cannot be allowed to drift, because a routing tool that says "I
 * crossed a note" beside a metric that says the view is clean is worse than silence — it tells the
 * caller one of its two instruments is wrong without telling it which. The inset is a calibration
 * constant that has already been re-tuned once, so a second local copy would drift rather than
 * might.</p>
 *
 * <p>Note that this policy is deliberately NOT the router's own obstacle geometry, which grows a
 * rectangle outward by its margin instead of shrinking it inward by the inset — a 20px-per-side
 * difference. Anything reported to a caller alongside {@code assess-layout} uses the policy here.</p>
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

    // ==================== the visual pass-through policy ====================

    /**
     * Tests whether a path penetrates the <em>interior</em> of a rectangle, where "interior" means
     * the rectangle shrunk inward by {@code inset} on all four sides.
     *
     * <p>The inset absorbs corner-arc imprecision and the sub-pixel disagreement between callers
     * that carry integer centres and callers that carry double ones, so a route that merely
     * brushes an edge is not reported as passing through it. A rectangle too small to inset — one
     * whose width or height is at most {@code 2 * inset} — can never be a pass-through and returns
     * false here; a border graze is a different question, answered by scanning the un-inset
     * rectangle.
     *
     * @param path  the ordered path points; should already be perimeter-clipped by
     *              {@link #clipPathToRectEdges} when the endpoints are element centres
     * @param inset the inward shrink applied to all four sides
     * @return true if any segment of the path crosses the inset interior
     */
    public static boolean pathPassesThroughRect(List<double[]> path,
                                                double rx, double ry, double rw, double rh,
                                                double inset) {
        double insetW = rw - 2 * inset;
        double insetH = rh - 2 * inset;
        if (insetW <= 0 || insetH <= 0) return false;
        return pathIntersectsRect(path, rx + inset, ry + inset, insetW, insetH);
    }

    /**
     * Tests whether any segment of a path intersects an axis-aligned rectangle, un-inset.
     *
     * <p>Deliberately NOT the complement of {@link #pathPassesThroughRect}: a rectangle too small
     * to inset is caught here while the pass-through test declines it, which is what lets a caller
     * classify one (path, rectangle) pair as either an interior penetration or a border graze and
     * never both.
     */
    public static boolean pathIntersectsRect(List<double[]> path,
                                             double rx, double ry, double rw, double rh) {
        if (rw <= 0 || rh <= 0) return false;
        for (int i = 0; i < path.size() - 1; i++) {
            if (lineSegmentIntersectsRect(
                    path.get(i)[0], path.get(i)[1],
                    path.get(i + 1)[0], path.get(i + 1)[1],
                    rx, ry, rw, rh)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Clips a path's endpoints from element centres to element perimeters.
     *
     * <p>Required before any pass-through test whose path was built as
     * {@code [sourceCentre, …bendpoints…, targetCentre]}: the unclipped first and last segments run
     * through the interiors of their own endpoints, so a visual overlapping an endpoint would be
     * reported as crossed by a segment that is never drawn.
     *
     * <p>Archi uses OrthogonalAnchor (the default), which projects the reference point's
     * coordinate onto the nearest edge — fundamentally different from ChopboxAnchor's
     * ray-intersection approach. BendpointConnectionRouter uses the first bendpoint as the
     * reference for the source anchor and the last bendpoint for the target anchor; with no
     * bendpoints it falls back to the opposite endpoint's centre.
     *
     * @param srcRect {@code [x, y, width, height]} of the source element, or null to leave the
     *                first point alone
     * @param tgtRect {@code [x, y, width, height]} of the target element, or null to leave the
     *                last point alone
     * @return a new list; the input is not modified
     */
    public static List<double[]> clipPathToRectEdges(List<double[]> path,
                                                     double[] srcRect, double[] tgtRect) {
        if (path.size() < 2) return path;

        List<double[]> clipped = new ArrayList<>(path);
        int last = path.size() - 1;

        double[] srcRef = path.size() > 2 ? path.get(1) : path.get(last);
        double[] tgtRef = path.size() > 2 ? path.get(last - 1) : path.get(0);

        if (srcRect != null) {
            double[] exit = orthogonalExitPoint(
                    srcRect[0], srcRect[1], srcRect[2], srcRect[3], srcRef[0], srcRef[1]);
            if (exit != null) {
                clipped.set(0, exit);
            }
        }

        if (tgtRect != null) {
            double[] entry = orthogonalExitPoint(
                    tgtRect[0], tgtRect[1], tgtRect[2], tgtRect[3], tgtRef[0], tgtRef[1]);
            if (entry != null) {
                clipped.set(last, entry);
            }
        }

        return clipped;
    }

    /**
     * Computes the perimeter exit point using Archi's OrthogonalAnchor model.
     *
     * <p>If the reference point's x or y falls within the element bounds, the exit projects that
     * coordinate onto the nearest edge (orthogonal exit). For diagonal references (both x and y
     * outside bounds), falls back to ChopboxAnchor-style ray intersection, since both anchors
     * produce similar results in corner zones.
     */
    public static double[] orthogonalExitPoint(double rx, double ry, double rw, double rh,
                                               double refX, double refY) {
        double left = rx, right = rx + rw, top = ry, bottom = ry + rh;
        double cx = rx + rw / 2, cy = ry + rh / 2;

        boolean xInside = refX >= left && refX <= right;
        boolean yInside = refY >= top && refY <= bottom;

        if (xInside && !yInside) {
            // Reference directly above or below — exit from top/bottom edge at ref.x
            return new double[]{refX, refY < top ? top : bottom};
        } else if (!xInside && yInside) {
            // Reference directly left or right — exit from left/right edge at ref.y
            return new double[]{refX < left ? left : right, refY};
        } else if (!xInside) {
            // Diagonal — use ray intersection from center toward reference (ChopboxAnchor fallback)
            return rectExitPoint(cx, cy, refX, refY, rx, ry, rw, rh);
        }
        // Reference inside element — return center (degenerate case)
        return new double[]{cx, cy};
    }

    /**
     * Finds where a ray from (x1,y1) toward (x2,y2) exits the given rectangle.
     * Assumes (x1,y1) is inside the rectangle. Returns the exit point,
     * or null if the ray is degenerate (zero length).
     * Used as the fallback for diagonal OrthogonalAnchor zones.
     */
    public static double[] rectExitPoint(double x1, double y1, double x2, double y2,
                                         double rx, double ry, double rw, double rh) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        if (Math.abs(dx) < 1e-10 && Math.abs(dy) < 1e-10) return null;

        double tExit = Double.MAX_VALUE;

        if (Math.abs(dx) > 1e-10) {
            // Right edge
            double t = (rx + rw - x1) / dx;
            if (t > 1e-10) {
                double yAt = y1 + t * dy;
                if (yAt >= ry && yAt <= ry + rh && t < tExit) tExit = t;
            }
            // Left edge
            t = (rx - x1) / dx;
            if (t > 1e-10) {
                double yAt = y1 + t * dy;
                if (yAt >= ry && yAt <= ry + rh && t < tExit) tExit = t;
            }
        }
        if (Math.abs(dy) > 1e-10) {
            // Bottom edge
            double t = (ry + rh - y1) / dy;
            if (t > 1e-10) {
                double xAt = x1 + t * dx;
                if (xAt >= rx && xAt <= rx + rw && t < tExit) tExit = t;
            }
            // Top edge
            t = (ry - y1) / dy;
            if (t > 1e-10) {
                double xAt = x1 + t * dx;
                if (xAt >= rx && xAt <= rx + rw && t < tExit) tExit = t;
            }
        }

        if (tExit == Double.MAX_VALUE) return null;
        return new double[]{x1 + tExit * dx, y1 + tExit * dy};
    }
}
