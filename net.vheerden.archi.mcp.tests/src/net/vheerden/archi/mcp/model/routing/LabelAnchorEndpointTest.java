package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.draw2d.ChopboxAnchor;
import org.eclipse.draw2d.ConnectionAnchor;
import org.eclipse.draw2d.ConnectionEndpointLocator;
import org.eclipse.draw2d.Figure;
import org.eclipse.draw2d.PolylineConnection;
import org.eclipse.draw2d.geometry.PointList;
import org.eclipse.draw2d.geometry.Rectangle;
import org.junit.Test;

/**
 * {@link LabelAnchor#endpointLabelBox} against draw2d's own answer: {@code ConnectionEndpointLocator.relocate},
 * the locator Archi 5.10 draws a Source and a Target connection label with
 * ({@code AbstractDiagramConnectionFigure.setLabelLocator}, which leaves its distances at their defaults).
 *
 * <p>The oracle is a {@code PolylineConnection} whose points and anchors are fixed: a {@code ChopboxAnchor} on
 * a plain {@code Figure} owner with set bounds and no parent, so draw2d's translations are no-ops, and a plain
 * {@code Figure} label with a preferred size, so no font is measured. The locator writes a shared static
 * rectangle, so each box is copied from the label as soon as it is placed. Sizes are integers, as draw2d's
 * are, so the oracle is exact.</p>
 *
 * <p>The table covers every face at each end, a non-square owner, a drawn end exactly on the owner's diagonal
 * (a tie goes EAST or WEST), end segments whose slope is under and over 1 (clamped), the slope draw2d sets to 1
 * when the end segment has no extent along the line (zero length, or running along the face), and odd,
 * negative coordinates whose cross-axis sum is fractional and negative, so truncation differs from rounding
 * and from {@code floor}.</p>
 */
public class LabelAnchorEndpointTest {

    /** One case: drawn points {x0, y0, x1, y1, ...}, the end, the owner {x, y, w, h}, the label size, Archi's top-left. */
    private record Row(String name, int[] points, boolean targetEnd, int[] owner, int w, int h, int[] expected) {}

    private static final Row[] ROWS = {
        // The worked example: source (0,0,100,50), target (300,0,100,50), no bendpoints, "serves" at 75 px.
        new Row("Source on EAST", new int[]{100, 25, 300, 25}, false, new int[]{0, 0, 100, 50}, 75, 20,
                new int[]{114, 29}),
        new Row("Target on WEST", new int[]{100, 25, 300, 25}, true, new int[]{300, 0, 100, 50}, 75, 20,
                new int[]{211, 29}),
        new Row("Source on WEST", new int[]{300, 25, 100, 25}, false, new int[]{300, 0, 100, 50}, 75, 20,
                new int[]{211, 29}),
        new Row("Target on EAST", new int[]{301, 27, 101, 27}, true, new int[]{1, 2, 100, 50}, 75, 20,
                new int[]{115, 31}),
        // NORTH and SOUTH swap the axes: the label's height runs along the line, its width to the right.
        new Row("Source on SOUTH", new int[]{50, 50, 50, 300}, false, new int[]{0, 0, 100, 50}, 75, 20,
                new int[]{54, 64}),
        new Row("Source on NORTH", new int[]{50, 300, 50, 50}, false, new int[]{0, 300, 100, 50}, 75, 20,
                new int[]{54, 266}),
        new Row("Target on NORTH", new int[]{51, 51, 51, 301}, true, new int[]{1, 301, 100, 50}, 75, 20,
                new int[]{55, 267}),
        new Row("Target on SOUTH", new int[]{50, 300, 50, 50}, true, new int[]{0, 0, 100, 50}, 75, 20,
                new int[]{54, 64}),
        // Non-square: (180, 40) is steeper from the centre (0.25) than the 200x40 diagonal (0.2), so SOUTH; a
        // square box's 45-degree rule would say EAST.
        new Row("non-square owner", new int[]{180, 40, 180, 200}, false, new int[]{0, 0, 200, 40}, 75, 20,
                new int[]{184, 54}),
        // The corner (100, 50) of (0,0,100,50) lies on the diagonal: EAST, not SOUTH.
        new Row("diagonal tie", new int[]{100, 50, 300, 50}, false, new int[]{0, 0, 100, 50}, 75, 20,
                new int[]{114, 54}),
        // tan 0.3: y = (int) (30 + 4.2 + 4) = 38.
        new Row("diagonal end, |tan| < 1", new int[]{100, 30, 300, 90}, false, new int[]{0, 0, 100, 50}, 75, 20,
                new int[]{114, 38}),
        // tan 4 is clamped to 1: y = 30 + 14 + 4.
        new Row("diagonal end, |tan| > 1", new int[]{100, 30, 150, 230}, false, new int[]{0, 0, 100, 50}, 75, 20,
                new int[]{114, 48}),
        // No extent along the line, so tan = 1 whichever way the segment goes.
        new Row("zero-length end segment", new int[]{100, 25, 100, 25, 300, 25}, false, new int[]{0, 0, 100, 50},
                75, 20, new int[]{114, 43}),
        // The owner says EAST although the segment runs down the face (SOUTH by direction).
        new Row("end segment along the face", new int[]{100, 25, 100, 200, 300, 200}, false,
                new int[]{0, 0, 100, 50}, 75, 20, new int[]{114, 43}),
        // tan -23/70: y sum -71 - 4.6 + 4 = -71.6 → -71 (truncation); rounding and floor give -72.
        new Row("odd, negative, EAST", new int[]{-101, -71, -31, -94}, false, new int[]{-201, -93, 100, 50}, 75, 21,
                new int[]{-87, -71}),
        // WEST: y sum -71 + 4.6 + 4 = -62.4 → -62 (truncation and rounding); floor gives -63.
        new Row("odd, negative, WEST", new int[]{-271, -48, -201, -71}, true, new int[]{-201, -93, 100, 50}, 75, 21,
                new int[]{-290, -62}),
    };

    /** Every row is checked and every disagreeing row named, so a failure shows all the rows a change moves. */
    @Test
    public void endpointLabelBox_isDraw2dsEndpointLocation() {
        List<String> wrong = new ArrayList<>();
        for (Row row : ROWS) {
            Rectangle expected = oracle(row);
            double[] actual = box(row);
            if (expected.x != actual[0] || expected.y != actual[1]
                    || expected.width != actual[2] || expected.height != actual[3]) {
                wrong.add(row.name() + ": draw2d " + expected + ", model " + java.util.Arrays.toString(actual));
            }
        }
        assertEquals(List.of(), wrong);
    }

    /**
     * The table's own literals, so a reader can check them without draw2d, and so a change to draw2d's answer
     * shows up as a disagreement between the two tests rather than silently.
     */
    @Test
    public void endpointLabelBox_tableLiterals() {
        List<String> wrong = new ArrayList<>();
        for (Row row : ROWS) {
            double[] actual = box(row);
            if (row.expected()[0] != actual[0] || row.expected()[1] != actual[1]) {
                wrong.add(row.name() + ": expected (" + row.expected()[0] + ", " + row.expected()[1]
                        + "), model (" + actual[0] + ", " + actual[1] + ")");
            }
        }
        assertEquals(List.of(), wrong);
    }

    /**
     * The worked example at the assessor's fractional width (74.8): Source just right of the source box, Target
     * ending 14 px short of the target box, both hanging 4 px below the line. On the 15%/85% rule both boxes
     * were centred on the endpoint boxes themselves.
     */
    @Test
    public void endpointLabelBox_workedExample_atTheEstimatedWidth() {
        List<double[]> drawn = List.of(new double[]{100, 25}, new double[]{300, 25});
        assertArrayEquals(new double[]{114, 29, 74.8, 20},
                LabelAnchor.endpointLabelBox(drawn, false, new double[]{0, 0, 100, 50}, 74.8, 20), 1e-9);
        assertArrayEquals(new double[]{300 - 14 - 74.8, 29, 74.8, 20},
                LabelAnchor.endpointLabelBox(drawn, true, new double[]{300, 0, 100, 50}, 74.8, 20), 1e-9);
    }

    /**
     * With no owner box draw2d's two-point rule on the end segment decides the face. The locator always has an
     * owner for an element end, so the oracle cannot reach this branch; these boxes are worked from the
     * disassembled formula (draw2d 3.23.100 {@code calculateConnectionLocation(Point, Point)}): EAST or WEST when
     * the segment runs further across than down, else SOUTH or NORTH, a tie going SOUTH or NORTH.
     */
    @Test
    public void endpointLabelBox_noOwner_theEndSegmentDecidesTheFace() {
        // Runs down: SOUTH, to the right of the line and 14 px down it.
        assertArrayEquals(new double[]{104, 39, 75, 20}, LabelAnchor.endpointLabelBox(
                List.of(new double[]{100, 25}, new double[]{100, 200}), false, null, 75, 20), 0.0);
        // Runs right: EAST.
        assertArrayEquals(new double[]{114, 29, 75, 20}, LabelAnchor.endpointLabelBox(
                List.of(new double[]{100, 25}, new double[]{300, 25}), false, null, 75, 20), 0.0);
        // A 45-degree tie, up and left: NORTH, with tan 1 in the swapped axes, so 14 px further up-left.
        assertArrayEquals(new double[]{-10, -34, 75, 20}, LabelAnchor.endpointLabelBox(
                List.of(new double[]{0, 0}, new double[]{-50, -50}), false, null, 75, 20), 0.0);
    }

    /**
     * A Source or Target label hangs from the drawn line's end segment, passing over one with no length for the
     * next one inward; when every segment has none, the end one.
     */
    @Test
    public void endSegmentIndex_isTheEndSegmentSkippingZeroLengthOnes() {
        List<double[]> route = points(new int[]{100, 25, 100, 25, 300, 25, 300, 200, 300, 200});
        assertEquals(1, LabelAnchor.endSegmentIndex(route, false));
        assertEquals(2, LabelAnchor.endSegmentIndex(route, true));
        List<double[]> plain = points(new int[]{0, 0, 40, 0, 40, 90});
        assertEquals(0, LabelAnchor.endSegmentIndex(plain, false));
        assertEquals(1, LabelAnchor.endSegmentIndex(plain, true));
        List<double[]> none = points(new int[]{5, 5, 5, 5, 5, 5});
        assertEquals(0, LabelAnchor.endSegmentIndex(none, false));
        assertEquals(1, LabelAnchor.endSegmentIndex(none, true));
    }

    private static double[] box(Row row) {
        int[] o = row.owner();
        return LabelAnchor.endpointLabelBox(points(row.points()), row.targetEnd(),
                new double[]{o[0], o[1], o[2], o[3]}, row.w(), row.h());
    }

    /** draw2d places the label; its bounds are copied at once (the locator's rectangle is shared). */
    private static Rectangle oracle(Row row) {
        int[] o = row.owner();
        Figure owner = new Figure();
        owner.setBounds(new Rectangle(o[0], o[1], o[2], o[3]));
        ConnectionAnchor anchor = new ChopboxAnchor(owner);
        PointList points = pointList(row.points());
        PolylineConnection connection = new PolylineConnection() {
            @Override
            public PointList getPoints() {
                return points;
            }

            @Override
            public ConnectionAnchor getSourceAnchor() {
                return anchor;
            }

            @Override
            public ConnectionAnchor getTargetAnchor() {
                return anchor;
            }
        };
        Figure label = new Figure();
        label.setPreferredSize(row.w(), row.h());
        new ConnectionEndpointLocator(connection, row.targetEnd()).relocate(label);
        return label.getBounds().getCopy();
    }

    private static List<double[]> points(int[] route) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < route.length; i += 2) {
            out.add(new double[]{route[i], route[i + 1]});
        }
        return out;
    }

    private static PointList pointList(int[] route) {
        PointList out = new PointList();
        for (int i = 0; i < route.length; i += 2) {
            out.addPoint(route[i], route[i + 1]);
        }
        return out;
    }
}
