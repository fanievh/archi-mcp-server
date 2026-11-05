package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * The foreign-line test both the label optimizer and the layout-quality assessor read: a line crosses a label when
 * it has a point strictly inside the label box shrunk by {@code min(10, w/3)} across and {@code min(10, h/3)} down.
 *
 * <p>The main box is {@code (100, 200, 90 x 20)}: its open inset box runs x 110..180 and y 206.67..213.33. Every
 * row is checked and each disagreeing row is named, so one wrong row does not hide the others.</p>
 */
public class ForeignLineHitsTest {

    private record Row(String name, double[] box, List<double[]> line, boolean expected) {}

    private static final double[] BOX = {100, 200, 90, 20};

    @Test
    public void table_everyRowAgreesWithTheInsetRule() {
        List<Row> rows = List.of(
                new Row("horizontal through the middle", BOX, line(0, 210, 300, 210), true),
                // In the box's top padding (6.67 px deep), 1 px outside the text band: the raw box would count it.
                new Row("horizontal in the top padding, y 206", BOX, line(0, 206, 300, 206), false),
                new Row("horizontal in the bottom padding, y 214", BOX, line(0, 214, 300, 214), false),
                // The same boundary rule down: y 206.67 and 213.33 are the inset top and bottom edges exactly.
                new Row("horizontal on the inset top edge", BOX, line(0, 200 + 20 / 3.0, 300, 200 + 20 / 3.0), false),
                new Row("horizontal just inside the inset top edge, y 207", BOX, line(0, 207, 300, 207), true),
                new Row("horizontal on the inset bottom edge", BOX, line(0, 220 - 20 / 3.0, 300, 220 - 20 / 3.0),
                        false),
                new Row("horizontal just inside the inset bottom edge, y 213", BOX, line(0, 213, 300, 213), true),
                new Row("vertical through it", BOX, line(150, 0, 150, 400), true),
                // Boundary rule: the inset box is open. x 110 is its left edge exactly.
                new Row("vertical on the inset left edge, x 110", BOX, line(110, 0, 110, 400), false),
                new Row("vertical just inside the inset left edge, x 110.5", BOX, line(110.5, 0, 110.5, 400), true),
                new Row("vertical in the left padding, x 105", BOX, line(105, 0, 105, 400), false),
                // x + y = 320 enters the inset box near its top-left corner (110, 206.67); x + y = 315 passes
                // outside that corner while still crossing the raw box.
                new Row("diagonal across the inset corner", BOX, line(100, 220, 120, 200), true),
                new Row("diagonal outside the inset corner", BOX, line(100, 215, 120, 195), false),
                new Row("stub ending inside", BOX, line(150, 400, 150, 210), true),
                new Row("stub ending in the padding", BOX, line(150, 400, 150, 215), false),
                new Row("line running round the box", BOX,
                        points(90, 190, 200, 190, 200, 230, 90, 230), false),
                new Row("second segment crosses", BOX, points(0, 100, 150, 100, 150, 400), true),
                // A self-connection with no bendpoints: both drawn ends on one point. Archi draws no segment.
                new Row("draws no segment, point inside", BOX, points(150, 210, 150, 210), false),
                new Row("single point", BOX, List.of(new double[]{150, 210}), false),
                // Odd and negative: box (-71, -33, 61 x 19), inset 10 across and 6.33 down: x -61..-20,
                // y -26.67..-20.67.
                new Row("negative, horizontal through", new double[]{-71, -33, 61, 19}, line(-200, -24, 0, -24), true),
                new Row("negative, horizontal in the top padding", new double[]{-71, -33, 61, 19},
                        line(-200, -27, 0, -27), false),
                new Row("negative, vertical on the inset edge", new double[]{-71, -33, 61, 19},
                        line(-61, -100, -61, 100), false),
                new Row("negative, vertical just inside", new double[]{-71, -33, 61, 19},
                        line(-60.5, -100, -60.5, 100), true),
                // A narrow box: the inset is a third of the width, 7 px of a 21 px box.
                new Row("narrow box, vertical through the centre", new double[]{0, 0, 21, 20}, line(10.5, -50, 10.5, 50),
                        true),
                new Row("narrow box, vertical on the inset edge", new double[]{0, 0, 21, 20}, line(7, -50, 7, 50), false),
                new Row("no width", new double[]{0, 0, 0, 20}, line(0, -50, 0, 50), false));

        List<String> mismatches = new ArrayList<>();
        for (Row r : rows) {
            boolean actual = ForeignLineHits.crosses(r.box[0], r.box[1], r.box[2], r.box[3], r.line);
            if (actual != r.expected) {
                mismatches.add(r.name + ": expected " + r.expected + " but was " + actual);
            }
        }
        assertEquals("rows that disagree: " + mismatches, List.of(), mismatches);
    }

    /** The optimizer's foreign-line box is the box its label-overlap test shrinks, so the two move together. */
    @Test
    public void insetIsTheOptimizersLabelOverlapInset() {
        assertEquals(LabelPositionOptimizer.LABEL_OVERLAP_INSET, ForeignLineHits.LABEL_INSET, 0.0);
    }

    @Test
    public void zeroLengthSegment_isTestedAsItsPoint_strictlyInsideTheInsetBox() {
        // Not reachable through crosses() on its own (any line through this point has a segment with length that
        // reaches it too), so the segment test is pinned directly.
        assertTrue(ForeignLineHits.segmentEntersOpenBox(new double[]{150, 210}, new double[]{150, 210},
                110, 206.5, 180, 213.5));
        assertFalse("in the padding", ForeignLineHits.segmentEntersOpenBox(new double[]{150, 205},
                new double[]{150, 205}, 110, 206.5, 180, 213.5));
        assertFalse("on the edge", ForeignLineHits.segmentEntersOpenBox(new double[]{110, 210},
                new double[]{110, 210}, 110, 206.5, 180, 213.5));
    }

    private static List<double[]> line(double x1, double y1, double x2, double y2) {
        return points(x1, y1, x2, y2);
    }

    private static List<double[]> points(double... xy) {
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) {
            pts.add(new double[]{xy[i], xy[i + 1]});
        }
        return pts;
    }
}
