package net.vheerden.archi.mcp.model.geometry;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for the consolidated Liang-Barsky line-segment-vs-rectangle
 * intersection algorithm in both int and double overloads.
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
}
