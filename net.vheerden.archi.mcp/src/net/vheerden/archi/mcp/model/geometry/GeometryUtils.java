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
 *
 * <p>It also owns the <strong>connection-end anchor model</strong>,
 * {@link #orthogonalExitPoint(double, double, double, double, double, double, double[])}: where
 * Archi 5.10.0's {@code OrthogonalAnchor} attaches a connection to a plain rectangle — the box
 * corner for an end off a corner, and for a connection with no bendpoints the midpoint of the two
 * boxes' overlap. Every assessor measurement of a drawn connection end starts there: the
 * diagonal-terminal count, pass-through, note/image crossings and grazes, and the corridor
 * warnings. Rounded figures — whose arc moves both an end off a corner and an end beside a face
 * near an edge — Archi 5.8/5.9's 1 px inset of the right and bottom anchors and the post-5.10.0
 * inside-reference snap are not modelled; the method says how each differs.</p>
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
     * True when a path draws no line: every point is the same point, or there are fewer than two.
     *
     * <p>Meant for a perimeter-clipped path. A self-connection stored without bendpoints clips to
     * its element's centre at both ends, and Archi draws no segment for it — nothing at all for an
     * undecorated connection, only its decoration at that point for a decorated one. A path like
     * that cannot cross or graze anything, so the route-versus-visual detectors skip it instead of
     * testing its single point as if it were a line.
     */
    public static boolean drawsNoSegment(List<double[]> path) {
        for (int i = 1; i < path.size(); i++) {
            if (path.get(i)[0] != path.get(0)[0] || path.get(i)[1] != path.get(0)[1]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tests whether a path penetrates the <em>interior</em> of a rectangle, where "interior" means
     * the rectangle shrunk inward by {@code inset} on all four sides.
     *
     * <p>The inset also leaves room for the rounded-corner arc the perimeter clip does not model,
     * which moves an end up to half the corner dimension in from the box edge (see
     * {@link #orthogonalExitPoint(double, double, double, double, double, double, double[])}): the
     * 10 px pass-through inset covers the 20 px arc, not a service pill's or a Value ellipse's. It
     * absorbs the sub-pixel disagreement between callers that carry integer centres and callers that carry
     * double ones, so a route that merely brushes an edge is not reported as passing through it.
     * A rectangle too small to inset — one whose width or height is at most {@code 2 * inset} —
     * can never be a pass-through and returns false here; a border graze is a different
     * question, answered by scanning the un-inset rectangle.
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
     * <p>Each end is placed where Archi's {@code OrthogonalAnchor} places it — see
     * {@link #orthogonalExitPoint(double, double, double, double, double, double, double[])}. The
     * reference is the first bendpoint for the source end and the last for the target end; with no
     * bendpoints it is the opposite element's centre, which the anchor then replaces with the
     * midpoint of the two boxes' overlap, so two boxes overlapping on one axis are joined by a
     * straight line. Each end is given the other end's rect for that replacement.
     *
     * @param srcRect {@code [x, y, width, height]} of the source element, or null to leave the
     *                first point alone (and to skip the replacement at the target end)
     * @param tgtRect {@code [x, y, width, height]} of the target element, or null to leave the
     *                last point alone (and to skip the replacement at the source end)
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
            clipped.set(0, orthogonalExitPoint(
                    srcRect[0], srcRect[1], srcRect[2], srcRect[3], srcRef[0], srcRef[1], tgtRect));
        }

        if (tgtRect != null) {
            clipped.set(last, orthogonalExitPoint(
                    tgtRect[0], tgtRect[1], tgtRect[2], tgtRect[3], tgtRef[0], tgtRef[1], srcRect));
        }

        return clipped;
    }

    /**
     * The anchor point with no remote figure to test the reference against, so the reference is
     * never replaced.
     *
     * @see #orthogonalExitPoint(double, double, double, double, double, double, double[])
     */
    public static double[] orthogonalExitPoint(double rx, double ry, double rw, double rh,
                                               double refX, double refY) {
        return orthogonalExitPoint(rx, ry, rw, rh, refX, refY, null);
    }

    /**
     * Where Archi 5.10.0's {@code OrthogonalAnchor} attaches a connection to a plain rectangle.
     *
     * <ol>
     *   <li><b>Reference replacement.</b> If the reference lies in the remote element's centre
     *       region — its box shrunk by {@code (size - 5) / 2} per axis on integer division, so five
     *       pixels across on an odd dimension and six on an even one — it is replaced, per axis, by
     *       the midpoint of {@code [max(remote.x, own.x), min(remote.right, own.right)]}. A
     *       zero-bendpoint reference is the remote centre, so this always fires for one.</li>
     *   <li><b>Bands</b>, half-open per axis: {@code ref.x < x} is left, {@code ref.x >= x + width}
     *       is right, anything else is within; the same on y. A reference on the left or top edge
     *       line is therefore within, and one on the right or bottom edge line is outside.</li>
     *   <li><b>Arm.</b> Outside on one axis only: that face, at the reference's coordinate on the
     *       other axis. Outside on both: the box corner on the reference's side. Within on both:
     *       the box centre.</li>
     * </ol>
     *
     * <p>Not modelled: a rounded figure — a non-zero corner dimension, the default figure of most
     * Services, Processes, Functions, Interactions and Events, a service drawn as a pill, a Value
     * drawn as an ellipse — anchors on its arc in two places. An end outside on both axes meets the
     * arc's 45-degree point rather than the box corner: 3 px in on each axis for the 20 px arc, 8 px
     * for a 55 px-tall pill, 18 px across and 8 px down for a 120x55 ellipse. And the bands narrow
     * by half the corner dimension at each edge, so an end outside on one axis but within that half
     * of an edge on the other keeps its coordinate — the stub stays at right angles — yet meets the
     * arc, up to half the corner dimension in from the face line (10 px for the 20 px arc); on a
     * pill almost every horizontal approach lands there, and on an ellipse almost every approach
     * from any side. A Junction anchors on an ellipse. The inputs here carry no figure type. The replaced reference
     * is computed in Archi's integer arithmetic; the centre arm is not, so on an odd dimension it
     * sits half a pixel past Archi's truncated centre. Archi 5.8 and 5.9 put right and bottom
     * anchors 1 px further in ({@code x + width - 1}), removed by upstream {@code 24c7c7c3}; after 5.10.0
     * upstream {@code 9af1c7ea} snaps a reference that is within on both axes to an edge instead of
     * returning the centre.
     *
     * <p>Width and height are non-negative. A zero size is anchored as Archi anchors it: nothing
     * is within on that axis, so the centre arm cannot be reached there. A remote under 5 px on an
     * axis has its centre region grown on that axis, as draw2d's shrink grows it — 4 px across at
     * size 0. A negative size is outside the contract; the result is finite but means nothing.
     *
     * <p>Both rects are in whole pixels, as Archi's bounds are and as every caller passes them; a
     * fractional rect is truncated toward zero by the replacement test and the midpoint. The
     * reference may be fractional — a weighted bendpoint — where Archi anchors the integer point
     * draw2d keeps, {@code floor(ref + 1e-9)}. On whole-pixel rects every band and replacement test
     * answers the same for a reference and its floor, so the arm and the face are Archi's — except
     * within 1e-9 below an integer, which draw2d rounds up and this does not; a {@code double}
     * weighted blend can land there, a few ulps short of a whole pixel. A face arm keeps the
     * unrounded coordinate it copies, less than 1 px from Archi's along the stub, never across it;
     * the centre arm keeps its half pixel, as above. Non-finite input is outside the contract: a
     * point is returned without throwing, and its coordinates mean nothing.
     *
     * @param remoteRect {@code [x, y, width, height]} of the element at the other end, or null to
     *                   skip the reference replacement
     */
    public static double[] orthogonalExitPoint(double rx, double ry, double rw, double rh,
                                               double refX, double refY, double[] remoteRect) {
        if (remoteRect != null && isInRemoteCentre(remoteRect, refX, refY)) {
            refX = overlapMidpoint(remoteRect[0], remoteRect[2], rx, rw);
            refY = overlapMidpoint(remoteRect[1], remoteRect[3], ry, rh);
        }

        boolean left = refX < rx;
        boolean right = refX >= rx + rw;
        boolean above = refY < ry;
        boolean below = refY >= ry + rh;

        if (!left && !right && !above && !below) {
            return new double[]{rx + rw / 2, ry + rh / 2};
        }
        return new double[]{left ? rx : right ? rx + rw : refX,
                            above ? ry : below ? ry + rh : refY};
    }

    /**
     * The replaced reference on one axis, in Archi's integer arithmetic: the coordinates truncated
     * as {@link #isInRemoteCentre} truncates them, the sum halved toward zero. A midpoint kept in
     * doubles would sit half a pixel off on an odd sum and, below zero, fall in the next band.
     */
    private static double overlapMidpoint(double remoteStart, double remoteSize,
                                          double ownStart, double ownSize) {
        long start = Math.max((long) remoteStart, (long) ownStart);
        long end = Math.min((long) remoteStart + (long) remoteSize,
                            (long) ownStart + (long) ownSize);
        return (start + end) / 2;
    }

    /**
     * Whether {@code OrthogonalAnchor} treats the reference as the remote figure's centre: the
     * remote box shrunk toward its centre by {@code (size - 5) / 2} per axis on integer division,
     * the shrink clamped so the box neither inverts nor moves past its centre, then a half-open
     * containment test. A box under 5 px has a negative shrink and so grows, as draw2d's
     * {@code Rectangle.shrink} does.
     */
    private static boolean isInRemoteCentre(double[] remote, double refX, double refY) {
        long x = (long) remote[0];
        long y = (long) remote[1];
        long w = (long) remote[2];
        long h = (long) remote[3];
        long shrinkH = (w - 5) / 2;
        long shrinkV = (h - 5) / 2;
        long nx = Math.min(x + w / 2, x + shrinkH);
        long nw = Math.max(0, w - 2 * shrinkH);
        long ny = Math.min(y + h / 2, y + shrinkV);
        long nh = Math.max(0, h - 2 * shrinkV);
        return refX >= nx && refX < nx + nw && refY >= ny && refY < ny + nh;
    }

    /**
     * One axis of a stored bendpoint as Archi's renderer draws it. draw2d's
     * {@code RelativeBendpoint} adds each stored offset to its anchor's whole-pixel reference
     * point ({@code x + width / 2} on integer division), blends the two reconstructions at the
     * {@code float} weight {@code (index + 1) / ((float) count + 1)}, widened to {@code double}, with
     * every product in {@code double} (read off draw2d 3.23's bytecode), and
     * {@code PrecisionPoint} keeps {@code (int) Math.floor(d + 1e-9)} — a floor, not a truncation,
     * so a negative half pixel goes down. This differs from the layout assessor's analysis blend
     * (untruncated half-pixel centres, a {@code double} weight, no rounding) by up to a pixel on an
     * odd-sized element; use it where a decision must match the pixels a reader sees.
     *
     * @param storedFromSource the offset stored against the source anchor on this axis
     * @param storedFromTarget the offset stored against the target anchor on this axis
     * @param sourceOrigin     the source element's absolute origin on this axis
     * @param sourceSize       the source element's size on this axis
     * @param targetOrigin     the target element's absolute origin on this axis
     * @param targetSize       the target element's size on this axis
     * @param index            the bendpoint's index in the connection's list
     * @param count            how many bendpoints the connection stores
     */
    public static int drawnBendpointCoordinate(int storedFromSource, int storedFromTarget,
                                               int sourceOrigin, int sourceSize,
                                               int targetOrigin, int targetSize,
                                               int index, int count) {
        int sourceRef = sourceOrigin + sourceSize / 2;
        int targetRef = targetOrigin + targetSize / 2;
        // The weight is STORED as a float, but every product is taken in double: a float times an int
        // would round the product itself and can floor an agreeing pair of reconstructions a pixel low.
        double weight = (index + 1) / ((float) count + 1);
        double blended = ((double) sourceRef + storedFromSource) * (1.0 - weight)
                + weight * ((double) targetRef + storedFromTarget);
        return (int) Math.floor(blended + 1e-9);
    }
}
