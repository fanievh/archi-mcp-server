package net.vheerden.archi.mcp.model.routing;

import java.util.List;

import net.vheerden.archi.mcp.model.geometry.GeometryUtils;

/**
 * Whether a connection's drawn line runs through a label box: the one test the label optimizer scores and the
 * layout-quality assessor reports with, so a label the optimizer places clear of other connections' lines is the
 * label the assessor does not flag.
 *
 * <p>The box is shrunk by the same insets as the label-overlap test ({@code min(10, width / 3)} across,
 * {@code min(10, height / 3)} down, about 6.67 px on a 20 px label), so a line must run through the label's text
 * band, not graze its padding. The shrunk box is open: a line lying exactly on its edge does not cross it, as a
 * box touching the inset label does not overlap it. A segment with no length tests as a point, which crosses when
 * it is strictly inside. A line {@link GeometryUtils#drawsNoSegment} says draws nothing (a self-connection with no
 * bendpoints) crosses nothing.</p>
 *
 * <p>Callers count one hit per (label, line) pair however many of the line's segments cross the box, and never
 * pass a connection's own line: a Middle label is drawn centred on it.</p>
 *
 * <p>Pure geometry over doubles; nothing here measures text.</p>
 */
public final class ForeignLineHits {

    /** Inset (px) the label box is shrunk by on each side, capped at a third of each dimension. */
    public static final double LABEL_INSET = 10.0;

    private ForeignLineHits() {
    }

    /**
     * True when any segment of {@code line} has a point strictly inside the label box shrunk by
     * {@link #LABEL_INSET}.
     *
     * @param x      label box left
     * @param y      label box top
     * @param width  label box width
     * @param height label box height
     * @param line   the drawn line's points, in order
     */
    public static boolean crosses(double x, double y, double width, double height, List<double[]> line) {
        double xInset = Math.min(LABEL_INSET, width / 3.0);
        double yInset = Math.min(LABEL_INSET, height / 3.0);
        double left = x + xInset;
        double top = y + yInset;
        double right = x + width - xInset;
        double bottom = y + height - yInset;
        if (!(right > left) || !(bottom > top) || line.size() < 2 || GeometryUtils.drawsNoSegment(line)) {
            return false;
        }
        for (int i = 0; i < line.size() - 1; i++) {
            if (segmentEntersOpenBox(line.get(i), line.get(i + 1), left, top, right, bottom)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Liang–Barsky clip of segment {@code a b} against the closed box, then the midpoint of the clipped part tested
     * against the open box. The box is convex, so a segment with any point strictly inside has the midpoint of its
     * clipped part strictly inside too; a segment that only runs along an edge or touches a corner does not. A
     * segment with no length is its one point.
     */
    static boolean segmentEntersOpenBox(double[] a, double[] b,
            double left, double top, double right, double bottom) {
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {a[0] - left, right - a[0], a[1] - top, bottom - a[1]};
        double tMin = 0.0;
        double tMax = 1.0;
        for (int k = 0; k < 4; k++) {
            if (p[k] == 0) {
                if (q[k] < 0) {
                    return false; // parallel to this edge and outside it
                }
            } else {
                double t = q[k] / p[k];
                if (p[k] < 0) {
                    tMin = Math.max(tMin, t);
                } else {
                    tMax = Math.min(tMax, t);
                }
                if (tMin > tMax) {
                    return false;
                }
            }
        }
        double tMid = (tMin + tMax) / 2;
        double mx = a[0] + dx * tMid;
        double my = a[1] + dy * tMid;
        return mx > left && mx < right && my > top && my < bottom;
    }
}
