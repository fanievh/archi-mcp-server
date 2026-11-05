package net.vheerden.archi.mcp.model.routing;

import java.util.List;

import net.vheerden.archi.mcp.model.geometry.GeometryUtils;

/**
 * Where Archi draws a connection label: for a Middle label the reference point the box is centred on, for a
 * Source or Target label the box itself, and in each case the segment of the drawn line that hosts it. The
 * one place those rules live; the layout-quality assessor and the label optimizer both read them.
 *
 * <p>Archi 5.10 ({@code AbstractDiagramConnectionFigure.setLabelLocator}) draws a Source label and a
 * Target label with draw2d's {@code ConnectionEndpointLocator}, with its default distances and no label
 * offset, and every other text position with {@code ConnectionLocator} at {@code MIDDLE}. The endpoint
 * locator (draw2d 3.23, {@code relocate}) puts the label just beyond that end of the drawn line, outside
 * the endpoint's box: {@link #endpointLabelBox}. A Source or Target label hangs from the drawn line's first
 * or last segment, passing over one with no length for the next one inward ({@link #endSegmentIndex}).</p>
 *
 * <p>For a Middle label, {@code ConnectionLocator.getLocation(PointList)} (draw2d 3.23) takes the
 * <em>index</em> midpoint of the drawn polyline, not the point half way along its length: the middle
 * point itself for an odd point count, and for an even count the midpoint of the middle segment, each
 * half-difference truncated toward zero by {@code int} division. With an odd number of bendpoints the
 * label is therefore centred on a corner. {@code AbstractLocator.relocate} then centres the label's
 * preferred size on that point.</p>
 *
 * <p>The hosting segment is the middle segment for an even point count; for an odd count, where the
 * reference point is a corner shared by two segments, it is the segment that ends at that corner. A
 * segment with no length is passed over for the nearest one before it ({@link #hostingSegmentIndex}).</p>
 *
 * <p>Pure geometry; the drawn ends come from the shared orthogonal-anchor model
 * ({@link GeometryUtils#clipPathToRectEdges}). They decide a Middle point only on a line with no bendpoints,
 * and a Source or Target box always.</p>
 */
public final class LabelAnchor {

    /** draw2d {@code ConnectionEndpointLocator}'s default distance (px) along the line from its end. */
    public static final int U_DISTANCE = 14;

    /** draw2d {@code ConnectionEndpointLocator}'s default distance (px) across the line. */
    public static final int V_DISTANCE = 4;

    /** The quadrant codes draw2d's {@code ConnectionEndpointLocator.calculateConnectionLocation} returns. */
    private static final int SOUTH = 1;
    private static final int EAST = 2;
    private static final int NORTH = 3;
    private static final int WEST = 4;

    private LabelAnchor() {
    }

    /**
     * The line Archi draws: the centre path (source centre, bendpoints, target centre) with each end moved
     * to where the orthogonal anchor attaches it. A null rect leaves that end at its centre.
     *
     * @param sourceRect {@code [x, y, width, height]} of the source element, or null
     * @param targetRect {@code [x, y, width, height]} of the target element, or null
     */
    public static List<double[]> drawnPoints(List<double[]> centrePath, double[] sourceRect,
            double[] targetRect) {
        return GeometryUtils.clipPathToRectEdges(centrePath, sourceRect, targetRect);
    }

    /**
     * draw2d's {@code ConnectionLocator} {@code MIDDLE} location on the drawn points: the middle point for
     * an odd count, else {@code p[j] + (p[i] - p[j]) / 2} per axis with {@code i = size / 2},
     * {@code j = i - 1}. draw2d's points are integers and its halving is {@code int} division; on these
     * double points the half-difference is truncated toward zero with a {@code (long)} cast, which agrees
     * with it on integer coordinates.
     *
     * @param drawnPoints at least two points, as {@link #drawnPoints} builds them
     * @return a new {@code [x, y]}
     */
    public static double[] middlePoint(List<double[]> drawnPoints) {
        int n = drawnPoints.size();
        if (n % 2 == 1) {
            double[] p = drawnPoints.get((n - 1) / 2);
            return new double[]{p[0], p[1]};
        }
        double[] pj = drawnPoints.get(n / 2 - 1);
        double[] pi = drawnPoints.get(n / 2);
        return new double[]{pj[0] + (long) ((pi[0] - pj[0]) / 2),
                            pj[1] + (long) ((pi[1] - pj[1]) / 2)};
    }

    /**
     * Index of the segment hosting a Middle label, segment {@code k} running from point {@code k} to point
     * {@code k + 1}: the middle segment for an even point count, and for an odd count the segment that ends
     * at the middle point.
     *
     * @param pointCount at least 2
     */
    public static int middleSegmentIndex(int pointCount) {
        return pointCount / 2 - 1;
    }

    /**
     * Index of the segment a Middle label hangs from: the {@link #middleSegmentIndex} one, unless it has no
     * length, which tells nothing about the room the label has (a drawn end on a bendpoint that sits on the
     * element's face, or a repeated bendpoint). Then the nearest segment with a length before it, leading
     * into the label's point, and failing that the nearest one after it. When every segment has no length,
     * the middle one.
     *
     * @param drawnPoints at least two points, as {@link #drawnPoints} builds them
     */
    public static int hostingSegmentIndex(List<double[]> drawnPoints) {
        int middle = middleSegmentIndex(drawnPoints.size());
        for (int k = middle; k >= 0; k--) {
            if (hasLength(drawnPoints, k)) {
                return k;
            }
        }
        for (int k = middle + 1; k < drawnPoints.size() - 1; k++) {
            if (hasLength(drawnPoints, k)) {
                return k;
            }
        }
        return middle;
    }

    /**
     * draw2d's {@code ConnectionEndpointLocator.relocate} on the drawn points: the box Archi draws a Source
     * label ({@code targetEnd} false) or a Target label in, as {@code [x, y, width, height]}, top-left.
     *
     * <p>The end point is the drawn line's first point (Target: its last), the reference point the one next
     * to it. The face the label goes out from comes from the endpoint's box, not from the end segment: a
     * point straight above or below the box centre ({@code x + width / 2}, {@code int} division as in
     * draw2d's {@code Rectangle.getCenter}) is NORTH or SOUTH; otherwise it is EAST or WEST when its slope
     * from the centre is no steeper than the box diagonal's, a point exactly on the diagonal included, and
     * NORTH or SOUTH when steeper. With no box (an end the caller has no rect for) draw2d's two-point rule
     * on the end segment decides instead: EAST or WEST when it runs further across than down, a tie going
     * NORTH or SOUTH.</p>
     *
     * <p>On a NORTH or SOUTH face the axes are swapped, so the label's height runs along the line. The label
     * then starts {@link #U_DISTANCE} out along the line from the end (on WEST and NORTH it ends there), and
     * sits {@link #V_DISTANCE} plus {@code 14 * tan} across it: below an EAST or WEST end, to the right of a
     * NORTH or SOUTH one. {@code tan} is the end segment's slope in the swapped axes, clamped to [-1, 1], and
     * 1 when the segment has no extent along the line (draw2d's {@code calculateTan}, so a zero-length end
     * segment and one running along the face both count as 1). draw2d's points are integers and its
     * cross-axis sum is cast to {@code int}; here the sum is truncated toward zero with a {@code (long)}
     * cast, which agrees with it on integer coordinates, and the along-line coordinate is exact on them.</p>
     *
     * <p>The box is corner-anchored, not centred, so an error in the estimated label size moves only its far
     * edge. For an end attached to another connection Archi's box is that connection's figure, which is not
     * modelled; pass null and the end-segment rule applies.</p>
     *
     * @param drawnPoints at least two points, as {@link #drawnPoints} builds them
     * @param targetEnd   true for a Target label, false for a Source label
     * @param ownerRect   {@code [x, y, width, height]} of that end's element, or null
     * @return a new {@code [x, y, width, height]}
     */
    public static double[] endpointLabelBox(List<double[]> drawnPoints, boolean targetEnd, double[] ownerRect,
            double width, double height) {
        int n = drawnPoints.size();
        double[] start = drawnPoints.get(targetEnd ? n - 1 : 0);
        double[] ref = drawnPoints.get(targetEnd ? n - 2 : 1);
        int face = ownerRect != null ? faceOfBox(start, ownerRect) : faceOfSegment(start, ref);
        boolean swap = face == SOUTH || face == NORTH;
        int sign = (face == NORTH || face == WEST) ? -1 : 1;
        double sx = swap ? start[1] : start[0];
        double sy = swap ? start[0] : start[1];
        double rx = swap ? ref[1] : ref[0];
        double ry = swap ? ref[0] : ref[1];
        double along = swap ? height : width;
        double tan = rx == sx ? 1.0 : Math.max(-1.0, Math.min(1.0, (ry - sy) / (rx - sx)));
        double x = sx + U_DISTANCE * sign + (sign < 0 ? -along : 0);
        double y = (long) (sy + sign * U_DISTANCE * tan + V_DISTANCE);
        return swap ? new double[]{y, x, width, height} : new double[]{x, y, width, height};
    }

    /** draw2d's three-point {@code calculateConnectionLocation}: the face of the box the point is on. */
    private static int faceOfBox(double[] p, double[] box) {
        double cx = box[0] + (long) (box[2] / 2);
        double cy = box[1] + (long) (box[3] / 2);
        if (p[0] == cx) {
            return p[1] < cy ? NORTH : SOUTH;
        }
        double diagonal = (box[1] - cy) / (box[0] - cx);
        double slope = (p[1] - cy) / (p[0] - cx);
        if (Math.abs(slope) <= Math.abs(diagonal)) {
            return p[0] < cx ? WEST : EAST;
        }
        return p[1] < cy ? NORTH : SOUTH;
    }

    /** draw2d's two-point {@code calculateConnectionLocation}: the way the end segment leaves the end. */
    private static int faceOfSegment(double[] start, double[] ref) {
        if (Math.abs(ref[0] - start[0]) > Math.abs(ref[1] - start[1])) {
            return ref[0] > start[0] ? EAST : WEST;
        }
        return ref[1] > start[1] ? SOUTH : NORTH;
    }

    /**
     * Index of the segment a Source or Target label hangs from: the drawn line's first segment (Target: its
     * last), unless it has no length, then the nearest one inward that has. When none has, the end one.
     *
     * @param drawnPoints at least two points, as {@link #drawnPoints} builds them
     */
    public static int endSegmentIndex(List<double[]> drawnPoints, boolean targetEnd) {
        int last = drawnPoints.size() - 2;
        if (targetEnd) {
            for (int k = last; k >= 0; k--) {
                if (hasLength(drawnPoints, k)) {
                    return k;
                }
            }
            return last;
        }
        for (int k = 0; k <= last; k++) {
            if (hasLength(drawnPoints, k)) {
                return k;
            }
        }
        return 0;
    }

    private static boolean hasLength(List<double[]> points, int k) {
        double[] a = points.get(k);
        double[] b = points.get(k + 1);
        return a[0] != b[0] || a[1] != b[1];
    }
}
