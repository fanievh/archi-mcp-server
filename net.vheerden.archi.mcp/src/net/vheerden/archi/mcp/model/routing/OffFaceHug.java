package net.vheerden.archi.mcp.model.routing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.vheerden.archi.mcp.model.routing.EdgeAttachmentCalculator.Face;

/**
 * The one decision "this terminal hugs the face it departs": the route leaves an element face and
 * its first exterior segment then runs parallel to that same face, closer to it than
 * {@link #MIN_STUB_PX}.
 *
 * <p>Both the layout assessor ({@code offFaceParallelTerminalCount}) and the router (its egress-lift
 * keep-signal and the hug ids it reports when the lift pass is skipped) ask this question, and they
 * must give the same answer on the same terminal, or a caller reads "25 still hug" from one tool and
 * "26" from the other on an unchanged view. So both delegate here, over the same primitive geometry:
 * the terminal bendpoint, the next path point, and the element's rectangle. Pure geometry — no EMF,
 * no SWT.
 *
 * <p>Three rules, each chosen to match what a reader sees in the rendered view:
 * <ol>
 *   <li><b>Face.</b> A terminal within {@link #PERIMETER_TOLERANCE_PX} of a face line (and within
 *       the face's span) is on that face. A terminal more than half a pixel inside the element has no
 *       departure face. Any other terminal resolves to the face the ray from the element's centre to it exits
 *       through, so a terminal a few pixels off its face — the router puts every terminal
 *       {@link EdgeAttachmentCalculator#TERMINAL_FACE_MARGIN} px outside, and a later stage can leave
 *       one a pixel off that line — still has the face Archi draws it leaving. The clearance below
 *       then keeps a far terminal from counting. At a corner, where two faces qualify equally (both
 *       within the band, or the ray leaving exactly through the corner), each is tried: a trunk
 *       lying along the top edge from the top-left corner hugs the top face, whichever face a fixed
 *       order would have picked.</li>
 *   <li><b>Parallel.</b> The first exterior segment is within {@link #PARALLEL_ANGLE_TOLERANCE_DEG}
 *       of a cardinal axis, oriented along the face (horizontal for TOP/BOTTOM, vertical for
 *       LEFT/RIGHT), and runs more than {@link #MIN_PARALLEL_EXTENT_PX} along it. A slightly slanted
 *       trunk running down beside a face still reads as hugging it; a one-pixel jog does not.</li>
 *   <li><b>Clearance.</b> The perpendicular distance from the terminal to the face's edge line is
 *       below {@link #MIN_STUB_PX}.</li>
 * </ol>
 *
 * <p>The caller supplies the drawn geometry: the router its own integer bendpoints (what it writes,
 * and what Archi draws when the stored reconstructions agree); the assessor the bendpoint as Archi's
 * renderer places it, not its half-pixel analysis blend. For a route with a single bendpoint the
 * segment after it heads for the other element, so the caller passes that element's centre as
 * {@code next}.
 */
public final class OffFaceHug {

    /** Clearance (px) below which a face-parallel first segment hugs the face it departs. */
    public static final double MIN_STUB_PX = 8.0;
    /** On-face band (px) around a face line within which a terminal is on that face. */
    public static final double PERIMETER_TOLERANCE_PX = 0.5;
    /** Largest deviation (degrees) from a cardinal axis that still counts as running parallel. */
    public static final double PARALLEL_ANGLE_TOLERANCE_DEG = 5.0;
    /**
     * Along-face extent (px) a first segment must exceed to run parallel at all. A one-pixel jog at
     * the terminal draws as a single pixel; the route a reader sees is the segment after it.
     */
    public static final double MIN_PARALLEL_EXTENT_PX = 1.0;

    private static final double EPS = 1e-9;

    /** A hugging terminal: the face it departs and its perpendicular clearance from that face. */
    public record Hug(Face face, double stub) {}

    private OffFaceHug() {}

    /**
     * Decides whether the terminal at {@code (termX, termY)}, whose next path point is
     * {@code (nextX, nextY)}, hugs a face of the element {@code (x, y, width, height)}.
     *
     * @return the hug (face and clearance), or {@code null} when the terminal is inside the element,
     *         has no resolvable face, the segment is zero-length or not parallel to the face, or the
     *         clearance is at least {@link #MIN_STUB_PX}
     */
    public static Hug evaluate(double termX, double termY, double nextX, double nextY,
                               double x, double y, double width, double height) {
        for (Face face : departureFaces(termX, termY, x, y, width, height)) {
            Hug hug = hugAlong(face, termX, termY, nextX, nextY, x, y, width, height);
            if (hug != null) return hug;
        }
        return null;
    }

    private static Hug hugAlong(Face face, double termX, double termY, double nextX, double nextY,
                                double x, double y, double width, double height) {
        double dx = Math.abs(nextX - termX);
        double dy = Math.abs(nextY - termY);
        if (dx < EPS && dy < EPS) return null;
        double angleDeg = Math.toDegrees(Math.atan2(dy, dx));
        if (Math.min(angleDeg, 90.0 - angleDeg) > PARALLEL_ANGLE_TOLERANCE_DEG) return null;
        boolean horizontalFace = face == Face.TOP || face == Face.BOTTOM;
        if (horizontalFace ? dx < dy : dy < dx) return null;
        if ((horizontalFace ? dx : dy) <= MIN_PARALLEL_EXTENT_PX) return null;

        double faceLine = switch (face) {
            case TOP -> y;
            case BOTTOM -> y + height;
            case LEFT -> x;
            case RIGHT -> x + width;
        };
        double stub = horizontalFace ? Math.abs(termY - faceLine) : Math.abs(termX - faceLine);
        return stub < MIN_STUB_PX ? new Hug(face, stub) : null;
    }

    /**
     * The faces a terminal departs, in the order LEFT, RIGHT, TOP, BOTTOM: those whose line it lies
     * on within the band (two at a corner); else none when it is more than the band inside; else the face the
     * centre-to-terminal ray exits through first (two when it exits exactly through a corner; none
     * only when the terminal coincides with the centre).
     */
    static List<Face> departureFaces(double px, double py, double x, double y,
                                               double width, double height) {
        double tol = PERIMETER_TOLERANCE_PX;
        double left = x;
        double right = x + width;
        double top = y;
        double bottom = y + height;
        List<Face> faces = new ArrayList<>(2);
        boolean withinHorizontal = px >= left - tol && px <= right + tol;
        boolean withinVertical = py >= top - tol && py <= bottom + tol;
        if (Math.abs(px - left) <= tol && withinVertical) faces.add(Face.LEFT);
        if (Math.abs(px - right) <= tol && withinVertical) faces.add(Face.RIGHT);
        if (Math.abs(py - top) <= tol && withinHorizontal) faces.add(Face.TOP);
        if (Math.abs(py - bottom) <= tol && withinHorizontal) faces.add(Face.BOTTOM);
        if (!faces.isEmpty()) return faces;
        if (px > left + tol && px < right - tol && py > top + tol && py < bottom - tol) return faces;

        double cx = x + width / 2.0;
        double cy = y + height / 2.0;
        double dx = px - cx;
        double dy = py - cy;
        if (Math.abs(dx) < EPS && Math.abs(dy) < EPS) return faces;
        double[] exitT = new double[Face.values().length];
        Arrays.fill(exitT, Double.POSITIVE_INFINITY);
        if (dx < -EPS) exitT[Face.LEFT.ordinal()] = exitIfOnSpan((left - cx) / dx, cy, dy, top, bottom);
        if (dx > EPS) exitT[Face.RIGHT.ordinal()] = exitIfOnSpan((right - cx) / dx, cy, dy, top, bottom);
        if (dy < -EPS) exitT[Face.TOP.ordinal()] = exitIfOnSpan((top - cy) / dy, cx, dx, left, right);
        if (dy > EPS) exitT[Face.BOTTOM.ordinal()] = exitIfOnSpan((bottom - cy) / dy, cx, dx, left, right);
        double best = Double.POSITIVE_INFINITY;
        for (double t : exitT) best = Math.min(best, t);
        if (best == Double.POSITIVE_INFINITY) return faces;
        for (Face f : new Face[]{Face.LEFT, Face.RIGHT, Face.TOP, Face.BOTTOM}) {
            if (exitT[f.ordinal()] <= best + EPS) faces.add(f);
        }
        return faces;
    }

    /** The ray parameter {@code t} when it is positive and the crossing lies on the face's span. */
    private static double exitIfOnSpan(double t, double origin, double delta, double lo, double hi) {
        double cross = origin + t * delta;
        double tol = PERIMETER_TOLERANCE_PX;
        return t > 0 && cross >= lo - tol && cross <= hi + tol ? t : Double.POSITIVE_INFINITY;
    }
}
