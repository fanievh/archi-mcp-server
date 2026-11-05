package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * The label optimizer scores each candidate with the box the layout-quality assessor measures: for every
 * label length, path shape and text position below, the optimizer's scored rect is the assessor's
 * {@code estimateLabelBounds} rounded to whole pixels. Without this, a label the optimizer picks as clear
 * can still be counted as overlapping by {@code assess-layout} right after routing.
 *
 * <p>A Middle label is centred, by both, where Archi draws it: {@link LabelAnchor#middlePoint} of the drawn
 * line. The shapes include lopsided routes, whose point half way along lies on another segment, and a line
 * with no bendpoints between unequal boxes offset on one axis, whose drawn ends are not the centres.</p>
 *
 * <p>A Source or Target label is, by both, the box {@link LabelAnchor#endpointLabelBox} puts just beyond that
 * end of the drawn line. The shapes reach every face: EAST and WEST on a straight line, NORTH and SOUTH on a
 * vertical one, a non-square box, and a diagonal line with no bendpoints.</p>
 *
 * <p>Odd lengths are deliberate: a parity check over one length can pass because the rounding happens to
 * line up. The assessor is package-private in {@code model}, so it is reached by name, as
 * {@link LabelOffsetDirectionTest} reaches its gap.</p>
 */
public class LabelBoxParityTest {

    private static final int[] LENGTHS = {1, 3, 8, 17, 40};

    @Test
    public void straightPath_scoredRectIsTheAssessorsBox() throws Exception {
        assertParity("straight", box(50, 25), List.of(), box(450, 25));
    }

    @Test
    public void lShapedPath_scoredRectIsTheAssessorsBox() throws Exception {
        // Legs of 250 and 213 px: the Source and Target points fall mid-leg at uneven coordinates, and the
        // Middle label sits on the corner.
        assertParity("L-shaped", box(50, 40), List.of(new AbsoluteBendpointDto(300, 40)), box(300, 253));

        // Both centre a Middle label on the corner, not 231.5 px along the line (on its first leg).
        double[] centre = assessorCentre(box(50, 40), List.of(new AbsoluteBendpointDto(300, 40)), box(300, 253),
                "x".repeat(17), 1);
        assertEquals(300.0, centre[0], 0.0);
        assertEquals(40.0, centre[1], 0.0);
        assertEquals(new RoutingRect(203, 30, 194, 20, null), LabelPositionOptimizer.scoredLabelRect(
                List.of(new AbsoluteBendpointDto(300, 40)), box(50, 40), box(300, 253), "x".repeat(17), 1));
    }

    @Test
    public void everyFace_scoredRectIsTheAssessorsBox() throws Exception {
        // Vertical: Source on SOUTH, Target on NORTH, and the reverse.
        assertParity("vertical, down", box(50, 25), List.of(), box(50, 425));
        assertParity("vertical, up", box(50, 425), List.of(), box(50, 25));
        // Straight, right to left: Source on WEST, Target on EAST.
        assertParity("straight, leftward", box(450, 25), List.of(), box(50, 25));
        // A 200 x 40 source whose drawn end is on its bottom face near the corner.
        assertParity("non-square", new RoutingRect(0, 0, 200, 40, "s"), bendpoints(180, 200),
                box(400, 200));
        // No bendpoints, boxes apart on both axes: a diagonal line, the end segment's tan under 1.
        assertParity("diagonal", box(50, 25), List.of(), box(450, 125));
    }

    @Test
    public void bendAtTheMiddle_scoredRectIsTheAssessorsBox() throws Exception {
        // Two 200 px legs: the 50% point is the bend itself.
        assertParity("bend at 50%", box(0, 0), List.of(new AbsoluteBendpointDto(200, 0)), box(200, 200));
    }

    @Test
    public void lopsidedRoutes_scoredRectIsTheAssessorsBox() throws Exception {
        // The bendpoint table of LabelAnchorTest, 3 to 7 points, between 100 x 60 boxes on its ends.
        assertParity("3 points", box(0, 0), bendpoints(300, 0), box(300, 20));
        assertParity("4 points", box(0, 0), bendpoints(20, 0, 20, 301), box(500, 301));
        assertParity("5 points", box(5, 7), bendpoints(5, -93, -201, -93, -201, 40), box(60, 40));
        assertParity("6 points", box(0, 0), bendpoints(0, -17, -33, -17, -60, -140, -400, -140),
                box(-400, -900));
        assertParity("7 points", box(0, 0), bendpoints(40, 0, 40, 33, 71, 33, 71, 90, 300, 90), box(300, 95));
    }

    @Test
    public void noBendpoints_unequalBoxesOffsetOnOneAxis_scoredRectIsTheAssessorsBox() throws Exception {
        // Source 0..100 x 0..60, target 400..600 x 20..110: they overlap on y 20..60, so Archi draws a
        // straight line at y 40 from x 100 to x 400, and the Middle label centres on (250, 40), not on the
        // centres' midpoint (275, 47.5).
        RoutingRect source = new RoutingRect(0, 0, 100, 60, "s");
        RoutingRect target = new RoutingRect(400, 20, 200, 90, "t");
        assertParity("no bendpoints, offset boxes", source, List.of(), target);

        double[] centre = assessorCentre(source, List.of(), target, "x".repeat(17), 1);
        assertEquals(250.0, centre[0], 0.0);
        assertEquals(40.0, centre[1], 0.0);
    }

    /**
     * Source and Target labels are where Archi draws them, no longer centred 15% or 85% along the centre path
     * (that put the 15% box on the source box itself on the straight line, at x 13). The rects for a
     * 17-character label (193.6 px), worked from draw2d's {@code ConnectionEndpointLocator} rule: 14 px out
     * along the line from its drawn end and 4 px across it.
     */
    @Test
    public void sourceAndTargetRects_areWhereArchiDrawsThem() {
        String label = "x".repeat(17);
        // Drawn (100, 25) to (400, 25): Source on EAST at (114, 29); Target on WEST ending 14 px short, x 192.4.
        assertRect(114, 29, LabelPositionOptimizer.scoredLabelRect(List.of(), box(50, 25), box(450, 25), label, 0));
        assertRect(192, 29, LabelPositionOptimizer.scoredLabelRect(List.of(), box(50, 25), box(450, 25), label, 2));
        // L: Source on EAST at (100, 40); Target on the target's top face (300, 223), NORTH: 14 px above it
        // (y 223 - 14 - 20) and 4 px right of the line.
        List<AbsoluteBendpointDto> l = bendpoints(300, 40);
        assertRect(114, 44, LabelPositionOptimizer.scoredLabelRect(l, box(50, 40), box(300, 253), label, 0));
        assertRect(304, 189, LabelPositionOptimizer.scoredLabelRect(l, box(50, 40), box(300, 253), label, 2));
        // Five points: Source leaves the top face at (5, -23), NORTH; Target enters the left face at (10, 40),
        // WEST, ending at x 10 - 14 = -4.
        List<AbsoluteBendpointDto> five = bendpoints(5, -93, -201, -93, -201, 40);
        assertRect(9, -57, LabelPositionOptimizer.scoredLabelRect(five, box(5, 7), box(60, 40), label, 0));
        assertRect(-198, 44, LabelPositionOptimizer.scoredLabelRect(five, box(5, 7), box(60, 40), label, 2));
    }

    /** Middle rects are the ones the optimizer scored before Source and Target were modelled. */
    @Test
    public void middleRects_areUnchanged() {
        String label = "x".repeat(17);
        assertRect(153, 15, LabelPositionOptimizer.scoredLabelRect(List.of(), box(50, 25), box(450, 25), label, 1));
        assertRect(203, 30, LabelPositionOptimizer.scoredLabelRect(bendpoints(300, 40), box(50, 40),
                box(300, 253), label, 1));
        assertRect(-298, -103, LabelPositionOptimizer.scoredLabelRect(bendpoints(5, -93, -201, -93, -201, 40),
                box(5, 7), box(60, 40), label, 1));
    }

    private static void assertRect(int x, int y, RoutingRect rect) {
        assertEquals(new RoutingRect(x, y, 194, 20, null), rect);
    }

    private void assertParity(String shape, RoutingRect source, List<AbsoluteBendpointDto> bendpoints,
            RoutingRect target) throws Exception {
        for (int length : LENGTHS) {
            String label = "x".repeat(length);
            for (int textPosition = 0; textPosition <= 2; textPosition++) {
                RoutingRect scored = LabelPositionOptimizer.scoredLabelRect(
                        bendpoints, source, target, label, textPosition);
                double[] assessed = assessorBounds(source, bendpoints, target, label, textPosition);
                String at = shape + ", length " + length + ", textPosition " + textPosition;
                assertNotNull(at, scored);
                assertEquals(at + ": x", Math.round(assessed[0]), scored.x());
                assertEquals(at + ": y", Math.round(assessed[1]), scored.y());
                assertEquals(at + ": width", Math.round(assessed[2]), scored.width());
                assertEquals(at + ": height", Math.round(assessed[3]), scored.height());
            }
            List<double[]> drawn = LabelAnchor.drawnPoints(
                    centrePath(source, bendpoints, target), rect(source), rect(target));
            for (int textPosition : new int[]{0, 2}) {
                double[] archi = LabelAnchor.endpointLabelBox(drawn, textPosition == 2,
                        rect(textPosition == 2 ? target : source), LabelBoxEstimate.renderedWidth(label),
                        LabelBoxEstimate.HEIGHT);
                double[] assessed = assessorBounds(source, bendpoints, target, label, textPosition);
                String at = shape + ", length " + length + ", textPosition " + textPosition;
                assertEquals(at + ": Archi's x", archi[0], assessed[0], 1e-9);
                assertEquals(at + ": Archi's y", archi[1], assessed[1], 1e-9);
            }
            double[] anchor = LabelAnchor.middlePoint(drawn);
            double[] centre = assessorCentre(source, bendpoints, target, label, 1);
            String at = shape + ", length " + length + ", Middle";
            assertEquals(at + ": centre x", anchor[0], centre[0], 1e-9);
            assertEquals(at + ": centre y", anchor[1], centre[1], 1e-9);
        }
    }

    /** A 100 x 60 box centred on (cx, cy). */
    private static RoutingRect box(int cx, int cy) {
        return new RoutingRect(cx - 50, cy - 30, 100, 60, null);
    }

    private static List<AbsoluteBendpointDto> bendpoints(int... xy) {
        List<AbsoluteBendpointDto> out = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) {
            out.add(new AbsoluteBendpointDto(xy[i], xy[i + 1]));
        }
        return out;
    }

    private static List<double[]> centrePath(RoutingRect source, List<AbsoluteBendpointDto> bendpoints,
            RoutingRect target) {
        List<double[]> path = new ArrayList<>();
        path.add(new double[]{source.centerX(), source.centerY()});
        for (AbsoluteBendpointDto bp : bendpoints) {
            path.add(new double[]{bp.x(), bp.y()});
        }
        path.add(new double[]{target.centerX(), target.centerY()});
        return path;
    }

    private static double[] rect(RoutingRect r) {
        return new double[]{r.x(), r.y(), r.width(), r.height()};
    }

    private static double[] assessorCentre(RoutingRect source, List<AbsoluteBendpointDto> bendpoints,
            RoutingRect target, String label, int textPosition) throws Exception {
        double[] b = assessorBounds(source, bendpoints, target, label, textPosition);
        return new double[]{b[0] + b[2] / 2, b[1] + b[3] / 2};
    }

    /**
     * The assessor's {@code estimateLabelBounds} for the same path (source centre, bendpoints, target centre)
     * and endpoint boxes.
     */
    private static double[] assessorBounds(RoutingRect source, List<AbsoluteBendpointDto> bendpoints,
            RoutingRect target, String label, int textPosition) throws Exception {
        Class<?> connectionClass = Class.forName("net.vheerden.archi.mcp.model.AssessmentConnection");
        Constructor<?> connectionCtor = connectionClass.getDeclaredConstructor(
                String.class, String.class, String.class, List.class, String.class, int.class);
        connectionCtor.setAccessible(true);
        Object connection = connectionCtor.newInstance("c1", "s", "t", centrePath(source, bendpoints, target),
                label, textPosition);

        Class<?> nodeClass = Class.forName("net.vheerden.archi.mcp.model.AssessmentNode");
        Class<?> assessorClass = Class.forName("net.vheerden.archi.mcp.model.LayoutQualityAssessor");
        Constructor<?> assessorCtor = assessorClass.getDeclaredConstructor();
        assessorCtor.setAccessible(true);
        Method estimate = assessorClass.getDeclaredMethod("estimateLabelBounds", connectionClass, nodeClass,
                nodeClass);
        estimate.setAccessible(true);
        Object bounds = estimate.invoke(assessorCtor.newInstance(), connection, node(nodeClass, "s", source),
                node(nodeClass, "t", target));
        assertNotNull(bounds);

        double[] out = new double[4];
        String[] fields = {"x", "y", "width", "height"};
        for (int i = 0; i < fields.length; i++) {
            Method accessor = bounds.getClass().getDeclaredMethod(fields[i]);
            accessor.setAccessible(true);
            out[i] = (double) accessor.invoke(bounds);
        }
        return out;
    }

    /** A plain {@code AssessmentNode} over the rect, through its 15-argument constructor. */
    private static Object node(Class<?> nodeClass, String id, RoutingRect r) throws Exception {
        Constructor<?> ctor = nodeClass.getDeclaredConstructor(String.class, double.class, double.class,
                double.class, double.class, String.class, boolean.class, boolean.class, String.class,
                double.class, String.class, String.class, double.class, double.class, double.class);
        ctor.setAccessible(true);
        return ctor.newInstance(id, (double) r.x(), (double) r.y(), (double) r.width(), (double) r.height(),
                null, false, false, null, 0.0, null, null, 0.0, 0.0, 0.0);
    }
}
