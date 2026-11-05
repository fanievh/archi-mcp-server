package net.vheerden.archi.mcp.model.geometry;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

/**
 * Tests for the consolidated Liang-Barsky line-segment-vs-rectangle
 * intersection algorithm in both int and double overloads, and for the
 * orthogonal-anchor model of where a connection meets its element.
 */
public class GeometryUtilsTest {

    // ==== Integer overload tests ====

    @Test
    public void shouldDetectLineRectIntersection_int() {
        // Diagonal line from (0,0) to (100,100) through rect at (40,40,20,20)
        assertTrue("Diagonal line through rect should intersect",
                GeometryUtils.lineSegmentIntersectsRect(0, 0, 100, 100, 40, 40, 20, 20));
    }

    @Test
    public void shouldNotDetectIntersection_whenLineMissesRect_int() {
        // Horizontal line above the rect
        assertFalse("Line above rect should not intersect",
                GeometryUtils.lineSegmentIntersectsRect(0, 0, 100, 0, 40, 40, 20, 20));
    }

    @Test
    public void shouldDetectIntersection_whenVerticalLineCrossesRect_int() {
        // Vertical line through rect
        assertTrue("Vertical line through rect should intersect",
                GeometryUtils.lineSegmentIntersectsRect(50, 0, 50, 100, 40, 40, 20, 20));
    }

    @Test
    public void shouldNotIntersect_whenLineEndsBeforeRect_int() {
        // Short line that stops before reaching the rect
        assertFalse("Short line should not reach rect",
                GeometryUtils.lineSegmentIntersectsRect(0, 0, 30, 30, 40, 40, 20, 20));
    }

    @Test
    public void shouldDetectHorizontalLineThroughRect_int() {
        assertTrue("Horizontal line through rect should intersect",
                GeometryUtils.lineSegmentIntersectsRect(0, 50, 200, 50, 50, 25, 100, 50));
    }

    @Test
    public void shouldNotIntersect_lineCompletelyAbove_int() {
        assertFalse("Line completely above should not intersect",
                GeometryUtils.lineSegmentIntersectsRect(0, 0, 100, 0, 50, 50, 100, 50));
    }

    @Test
    public void shouldDetect_lineStartingInsideRect_int() {
        // Line starts inside the rect
        assertTrue("Line starting inside rect should intersect",
                GeometryUtils.lineSegmentIntersectsRect(55, 55, 200, 200, 50, 50, 20, 20));
    }

    @Test
    public void shouldDetect_lineFullyInsideRect_int() {
        // Both endpoints inside the rect
        assertTrue("Line fully inside rect should intersect",
                GeometryUtils.lineSegmentIntersectsRect(55, 55, 60, 60, 50, 50, 20, 20));
    }

    // ==== Double overload tests ====

    @Test
    public void shouldDetectLineRectIntersection_double() {
        assertTrue("Diagonal through rect should intersect",
                GeometryUtils.lineSegmentIntersectsRect(0.0, 25.0, 200.0, 25.0,
                        50.0, 0.0, 100.0, 50.0));
    }

    @Test
    public void shouldNotDetectIntersection_whenLineMissesRect_double() {
        assertFalse("Line missing rect should not intersect",
                GeometryUtils.lineSegmentIntersectsRect(0.0, 0.0, 100.0, 0.0,
                        50.0, 50.0, 100.0, 50.0));
    }

    @Test
    public void shouldDetectVerticalLine_double() {
        assertTrue("Vertical line through rect should intersect",
                GeometryUtils.lineSegmentIntersectsRect(75.0, 0.0, 75.0, 100.0,
                        50.0, 25.0, 100.0, 50.0));
    }

    @Test
    public void shouldHandleVerySmallSegment_double() {
        // Near-zero length segment inside rectangle
        assertTrue("Point inside rect should intersect",
                GeometryUtils.lineSegmentIntersectsRect(60.0, 60.0, 60.0 + 1e-12, 60.0 + 1e-12,
                        50.0, 50.0, 20.0, 20.0));
    }

    @Test
    public void shouldNotIntersect_nearMiss_double() {
        // Line just barely below the rectangle
        assertFalse("Near miss below rect should not intersect",
                GeometryUtils.lineSegmentIntersectsRect(0.0, 100.1, 200.0, 100.1,
                        50.0, 50.0, 100.0, 50.0));
    }

    // ==== Orthogonal-anchor model (Archi 5.10.0 OrthogonalAnchor, plain rectangle) ====

    // Element (100,200,400,300) spans x [100..500], y [200..500] throughout this block.

    @Test
    public void orthogonalExitPoint_shouldAnchorAtTheCorner_whenReferenceIsOutsideOnBothAxes() {
        // LEFT|TOP, RIGHT|TOP, LEFT|BOTTOM, RIGHT|BOTTOM: a plain rectangle's corner dimension is
        // 0x0, so each corner arm returns the box corner itself.
        assertPoint(100, 200, GeometryUtils.orthogonalExitPoint(100, 200, 400, 300, 50, 150));
        assertPoint(500, 200, GeometryUtils.orthogonalExitPoint(100, 200, 400, 300, 600, 150));
        assertPoint(100, 500, GeometryUtils.orthogonalExitPoint(100, 200, 400, 300, 50, 600));
        assertPoint(500, 500, GeometryUtils.orthogonalExitPoint(100, 200, 400, 300, 600, 600));
    }

    @Test
    public void orthogonalExitPoint_shouldTreatTheRightAndBottomEdgeLinesAsOutside() {
        // Bands are half-open: ref.x == 500 (x + width) is RIGHT, y 350 is within → right face
        // at ref.y. Likewise ref.y == 500 (y + height) is BOTTOM → bottom face at ref.x.
        assertPoint(500, 350, GeometryUtils.orthogonalExitPoint(100, 200, 400, 300, 500, 350));
        assertPoint(300, 500, GeometryUtils.orthogonalExitPoint(100, 200, 400, 300, 300, 500));
        // On both lines at once: RIGHT|BOTTOM → the corner.
        assertPoint(500, 500, GeometryUtils.orthogonalExitPoint(100, 200, 400, 300, 500, 500));
    }

    @Test
    public void orthogonalExitPoint_shouldTreatTheLeftAndTopEdgeLinesAsWithin() {
        // ref.x == 100 (x) is not < x, so it is within; with y within too the reference is
        // inside on both axes and 5.10.0 returns the box centre.
        assertPoint(300, 350, GeometryUtils.orthogonalExitPoint(100, 200, 400, 300, 100, 350));
        assertPoint(300, 350, GeometryUtils.orthogonalExitPoint(100, 200, 400, 300, 300, 200));
        // Left edge line, above the box: x within, y TOP → top face at ref.x.
        assertPoint(100, 200, GeometryUtils.orthogonalExitPoint(100, 200, 400, 300, 100, 150));
    }

    @Test
    public void clipPathToRectEdges_shouldJoinAnAlignedZeroBendpointPairStraight() {
        // Source [0..100]x[0..100], target [200..300]x[30..130], no bendpoints: each end's
        // reference is the other's centre, which lies in that box's centre region, so Archi
        // replaces it with the overlap midpoint per axis. y overlap [30..100] → 65; x spans do not
        // overlap → x midpoint (200 + 100) / 2 = 150, in the gap. Source: x RIGHT, y within →
        // right face (100, 65). Target: x LEFT, y within → left face (200, 65). Drawn horizontal.
        List<double[]> clipped = GeometryUtils.clipPathToRectEdges(
                List.of(new double[]{50, 50}, new double[]{250, 80}),
                new double[]{0, 0, 100, 100}, new double[]{200, 30, 100, 100});
        assertPoint(100, 65, clipped.get(0));
        assertPoint(200, 65, clipped.get(1));
    }

    @Test
    public void clipPathToRectEdges_shouldJoinADisjointZeroBendpointPairCornerToCorner() {
        // Source [0..100]x[0..100], target [200..300]x[300..350]: no overlap on either axis.
        // Replaced reference for both ends: x (200 + 100) / 2 = 150, y (300 + 100) / 2 = 200 —
        // in the gap. Source: RIGHT|BOTTOM → corner (100, 100). Target: LEFT|TOP → (200, 300).
        List<double[]> clipped = GeometryUtils.clipPathToRectEdges(
                List.of(new double[]{50, 50}, new double[]{250, 325}),
                new double[]{0, 0, 100, 100}, new double[]{200, 300, 100, 50});
        assertPoint(100, 100, clipped.get(0));
        assertPoint(200, 300, clipped.get(1));
    }

    @Test
    public void clipPathToRectEdges_shouldNotReplaceTheReference_whenTheOppositeRectIsUnknown() {
        // Same aligned pair, target unresolved: the source end keeps the target centre (250, 80)
        // as its reference → x RIGHT, y within → right face at ref.y (100, 80). Last point left.
        List<double[]> clipped = GeometryUtils.clipPathToRectEdges(
                List.of(new double[]{50, 50}, new double[]{250, 80}),
                new double[]{0, 0, 100, 100}, null);
        assertPoint(100, 80, clipped.get(0));
        assertPoint(250, 80, clipped.get(1));
    }

    @Test
    public void clipPathToRectEdges_shouldKeepABendpointReference_outsideTheRemoteCentreRegion() {
        // Routed: the reference is the bendpoint (199, 70), far from the target's centre region,
        // so it is not replaced. Target [200..500]x[100..150]: x 199 LEFT, y 70 TOP → corner
        // (200, 100).
        List<double[]> clipped = GeometryUtils.clipPathToRectEdges(
                List.of(new double[]{140, 70}, new double[]{199, 70}, new double[]{350, 125}),
                new double[]{100, 50, 80, 40}, new double[]{200, 100, 300, 50});
        assertPoint(180, 70, clipped.get(0));
        assertPoint(200, 100, clipped.get(2));
    }

    private static void assertPoint(double x, double y, double[] actual) {
        assertNotNull(actual);
        assertEquals("x", x, actual[0], 1e-9);
        assertEquals("y", y, actual[1], 1e-9);
    }

    @Test
    public void drawsNoSegment_shouldBeTrueOnlyWhenEveryPointIsTheSamePoint() {
        assertTrue(GeometryUtils.drawsNoSegment(List.of()));
        assertTrue(GeometryUtils.drawsNoSegment(List.of(new double[]{3, 4})));
        assertTrue(GeometryUtils.drawsNoSegment(List.of(new double[]{360.5, 130.5},
                new double[]{360.5, 130.5}, new double[]{360.5, 130.5})));
        // a vertical, a horizontal and a closed path all draw a line
        assertFalse(GeometryUtils.drawsNoSegment(List.of(new double[]{160, 160}, new double[]{160, 190})));
        assertFalse(GeometryUtils.drawsNoSegment(List.of(new double[]{160, 130}, new double[]{220, 130})));
        assertFalse(GeometryUtils.drawsNoSegment(List.of(new double[]{0, 0}, new double[]{0, 5},
                new double[]{0, 0})));
    }
}
