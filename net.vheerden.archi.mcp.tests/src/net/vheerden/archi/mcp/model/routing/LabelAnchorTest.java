package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.draw2d.ConnectionLocator;
import org.eclipse.draw2d.geometry.Point;
import org.eclipse.draw2d.geometry.PointList;
import org.junit.Test;

/**
 * {@link LabelAnchor#middlePoint} against draw2d's own answer: {@code ConnectionLocator.getLocation(PointList)}
 * with the {@code MIDDLE} alignment, the locator Archi 5.10 draws a Middle connection label with
 * ({@code AbstractDiagramConnectionFigure.setLabelLocator}). The method is protected, so a subclass reaches
 * it; the {@code MIDDLE} branch never reads the connection, which is left null.
 *
 * <p>The table covers 2 to 7 points. Its differences are odd and negative on both axes, because an even,
 * positive grid cannot tell truncation from rounding, and two of its routes are lopsided, so the point half
 * way along the path lies on a different segment from the index midpoint.</p>
 */
public class LabelAnchorTest {

    /** draw2d's {@code ConnectionLocator}, opened up for its protected {@code getLocation}. */
    private static final class Oracle extends ConnectionLocator {
        Oracle() {
            super(null, ConnectionLocator.MIDDLE);
        }

        Point middle(PointList points) {
            return getLocation(points).getCopy(); // getLocation answers a shared singleton
        }
    }

    /** {x0, y0, x1, y1, ...}: one route per row, 2 to 7 points. */
    private static final int[][] ROUTES = {
        // 2 points, odd positive differences (5, 13): truncation lands on 2 and 6.
        {10, 10, 15, 23},
        // 2 points, odd negative differences (-13, -9): truncation toward zero, -6 and -4.
        {100, 50, 87, 41},
        // 3 points, lopsided: the middle point is the corner (300, 0); half the length is 160 px along the
        // first leg.
        {0, 0, 300, 0, 300, 20},
        // 4 points, lopsided: the middle segment runs (20, 0) to (20, 301), odd; half the length lies on the
        // last leg.
        {0, 0, 20, 0, 20, 301, 500, 301},
        // 5 points, negative coordinates: the middle point is the corner (-201, -93).
        {5, 7, 5, -93, -201, -93, -201, 40, 60, 40},
        // 6 points, a middle segment with odd negative differences on both axes (-27, -123).
        {0, 0, 0, -17, -33, -17, -60, -140, -400, -140, -400, -900},
        // 7 points: the middle point is (71, 33).
        {0, 0, 40, 0, 40, 33, 71, 33, 71, 90, 300, 90, 300, 95},
    };

    @Test
    public void middlePoint_isDraw2dsMiddleLocation() {
        Oracle oracle = new Oracle();
        for (int[] route : ROUTES) {
            Point expected = oracle.middle(pointList(route));
            double[] actual = LabelAnchor.middlePoint(points(route));
            String at = (route.length / 2) + " points " + java.util.Arrays.toString(route);
            assertEquals(at + ": x", expected.x, actual[0], 0.0);
            assertEquals(at + ": y", expected.y, actual[1], 0.0);
        }
    }

    /**
     * The table's own literals, so a reader can check them without draw2d, and so a change to draw2d's answer
     * shows up as a disagreement between the two tests rather than silently.
     */
    @Test
    public void middlePoint_tableLiterals() {
        double[][] expected = {{12, 16}, {94, 46}, {300, 0}, {20, 150}, {-201, -93}, {-46, -78}, {71, 33}};
        for (int r = 0; r < ROUTES.length; r++) {
            assertArrayEquals(java.util.Arrays.toString(ROUTES[r]), expected[r],
                    LabelAnchor.middlePoint(points(ROUTES[r])), 0.0);
        }
    }

    /** Half a pixel is truncated toward zero on a double point, as draw2d's int division truncates it. */
    @Test
    public void middlePoint_truncatesAFractionalHalfDifferenceTowardZero() {
        assertArrayEquals(new double[]{3.5, -2.5},
                LabelAnchor.middlePoint(List.of(new double[]{0.5, 0.5}, new double[]{7.5, -6.5})), 0.0);
    }

    /**
     * The hosting segment is the middle segment for an even point count, and for an odd count the segment
     * that ends at the middle point (the incoming one).
     */
    @Test
    public void middleSegmentIndex_isTheMiddleSegmentOrTheOneEndingAtTheMiddlePoint() {
        assertEquals(0, LabelAnchor.middleSegmentIndex(2));
        assertEquals(0, LabelAnchor.middleSegmentIndex(3));
        assertEquals(1, LabelAnchor.middleSegmentIndex(4));
        assertEquals(1, LabelAnchor.middleSegmentIndex(5));
        assertEquals(2, LabelAnchor.middleSegmentIndex(6));
        assertEquals(2, LabelAnchor.middleSegmentIndex(7));
        for (int[] route : ROUTES) {
            List<double[]> p = points(route);
            int n = p.size();
            if (n % 2 == 1) {
                assertArrayEquals(p.get(LabelAnchor.middleSegmentIndex(n) + 1), LabelAnchor.middlePoint(p), 0.0);
            }
        }
    }

    /**
     * A segment of zero length tells nothing about the room a label has, so the hosting segment steps back to
     * the nearest segment of some length leading into the anchor, and forward only when none does.
     */
    @Test
    public void hostingSegmentIndex_skipsZeroLengthSegments() {
        // 5 points, the corner (80, 0) reached through a repeated point: the incoming leg is the 80 px one.
        assertEquals(0, LabelAnchor.hostingSegmentIndex(points(new int[]{0, 0, 80, 0, 80, 0, 80, 300, 200, 300})));
        // 3 points, the drawn end on the bendpoint: nothing leads in, so the leg out of the corner.
        assertEquals(1, LabelAnchor.hostingSegmentIndex(points(new int[]{50, 60, 50, 60, 400, 60})));
        // No zero-length segment: the middle segment index.
        assertEquals(1, LabelAnchor.hostingSegmentIndex(points(new int[]{0, 0, 20, 0, 20, 301, 500, 301})));
        // Every segment of zero length: the middle segment index, as there is nothing better.
        assertEquals(0, LabelAnchor.hostingSegmentIndex(points(new int[]{5, 5, 5, 5})));
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
