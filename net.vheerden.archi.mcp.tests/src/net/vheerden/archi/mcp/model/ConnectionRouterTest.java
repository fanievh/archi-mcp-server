package net.vheerden.archi.mcp.model;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Pure geometry unit tests for {@link ConnectionRouter} (Story 9-5).
 * No EMF dependencies — tests routing algorithm correctness.
 */
public class ConnectionRouterTest {

    private ConnectionRouter router;

    @Before
    public void setUp() {
        router = new ConnectionRouter();
    }

    // ---- Axis-aligned (straight line) tests ----

    @Test
    public void shouldReturnStraightLine_whenHorizontallyAligned() {
        // Two elements at same Y, offset horizontally
        RoutingRect source = new RoutingRect(100, 200, 120, 55, "src");
        RoutingRect target = new RoutingRect(400, 200, 120, 55, "tgt");

        List<AbsoluteBendpointDto> result = router.computeOrthogonalRoute(
                source, target, Collections.emptyList());

        assertEquals("Horizontally aligned should produce 0 bendpoints", 0, result.size());
    }

    @Test
    public void shouldReturnStraightLine_whenVerticallyAligned() {
        // Two elements at same X, offset vertically
        RoutingRect source = new RoutingRect(200, 100, 120, 55, "src");
        RoutingRect target = new RoutingRect(200, 400, 120, 55, "tgt");

        List<AbsoluteBendpointDto> result = router.computeOrthogonalRoute(
                source, target, Collections.emptyList());

        assertEquals("Vertically aligned should produce 0 bendpoints", 0, result.size());
    }

    @Test
    public void shouldReturnStraightLine_whenNearlyHorizontallyAligned() {
        // deltaY is less than min(srcHalfH, tgtHalfH)
        // srcHalfH = 27, tgtHalfH = 27, threshold = 27
        // deltaY = 20 < 27 → straight line
        RoutingRect source = new RoutingRect(100, 200, 120, 55, "src");
        RoutingRect target = new RoutingRect(400, 220, 120, 55, "tgt");

        List<AbsoluteBendpointDto> result = router.computeOrthogonalRoute(
                source, target, Collections.emptyList());

        assertEquals("Nearly horizontal should produce 0 bendpoints", 0, result.size());
    }

    // ---- Z-shape routing tests ----

    @Test
    public void shouldReturnZShape_whenDiagonal() {
        // Two elements diagonally placed — should produce 2 bendpoints
        RoutingRect source = new RoutingRect(100, 100, 120, 55, "src");
        RoutingRect target = new RoutingRect(400, 400, 120, 55, "tgt");

        List<AbsoluteBendpointDto> result = router.computeOrthogonalRoute(
                source, target, Collections.emptyList());

        assertEquals("Diagonal should produce 2 bendpoints (Z-shape)", 2, result.size());

        // Verify orthogonality: horizontal-first orientation
        // bp1 should share Y with source center, bp2 should share Y with target center
        int srcCY = source.centerY();
        int tgtCY = target.centerY();
        AbsoluteBendpointDto bp1 = result.get(0);
        AbsoluteBendpointDto bp2 = result.get(1);

        // Either horizontal-first (bp1.y=srcCY, bp2.y=tgtCY) or
        // vertical-first (bp1.x=srcCX, bp2.x=tgtCX)
        boolean isHorizontalFirst = bp1.y() == srcCY && bp2.y() == tgtCY;
        boolean isVerticalFirst = bp1.x() == source.centerX() && bp2.x() == target.centerX();
        assertTrue("Bendpoints should form orthogonal Z-shape",
                isHorizontalFirst || isVerticalFirst);
    }

    @Test
    public void shouldReturnZShape_withCorrectMidpoint() {
        // No obstacles: horizontal-first with midpoint-X
        RoutingRect source = new RoutingRect(100, 100, 120, 55, "src");
        RoutingRect target = new RoutingRect(500, 400, 120, 55, "tgt");

        List<AbsoluteBendpointDto> result = router.computeOrthogonalRoute(
                source, target, Collections.emptyList());

        assertEquals(2, result.size());

        int srcCX = source.centerX(); // 160
        int tgtCX = target.centerX(); // 560
        int midX = (srcCX + tgtCX) / 2; // 360

        // Horizontal-first: both bendpoints share the same X (midX)
        assertEquals("Bendpoint 1 X should be midpoint", midX, result.get(0).x());
        assertEquals("Bendpoint 2 X should be midpoint", midX, result.get(1).x());
    }

    // ---- Overlapping / degenerate cases ----

    @Test
    public void shouldReturnStraightLine_whenElementsOverlap() {
        // Elements overlap (same position)
        RoutingRect source = new RoutingRect(200, 200, 120, 55, "src");
        RoutingRect target = new RoutingRect(220, 210, 120, 55, "tgt");

        List<AbsoluteBendpointDto> result = router.computeOrthogonalRoute(
                source, target, Collections.emptyList());

        // deltaX=40, deltaY=10; srcHalfW=60, tgtHalfW=60, threshold=60
        // |deltaX| < threshold → straight line
        assertEquals("Overlapping elements should produce 0 bendpoints", 0, result.size());
    }

    @Test
    public void shouldSkipSelfConnection() {
        // Source and target are identical (same center)
        RoutingRect element = new RoutingRect(200, 200, 120, 55, "same");

        List<AbsoluteBendpointDto> result = router.computeOrthogonalRoute(
                element, element, Collections.emptyList());

        assertEquals("Self-connection should produce 0 bendpoints", 0, result.size());
    }

    // ---- Obstacle avoidance tests ----

    @Test
    public void shouldPreferCleanOrientation_whenOneHasObstacles() {
        // Place obstacle that blocks horizontal-first but not vertical-first
        RoutingRect source = new RoutingRect(100, 100, 120, 55, "src");
        RoutingRect target = new RoutingRect(400, 400, 120, 55, "tgt");

        // Obstacle at the midpoint of the horizontal-first path
        int midX = (source.centerX() + target.centerX()) / 2; // 310
        RoutingRect obstacle = new RoutingRect(midX - 30, 200, 60, 60, "obs");

        List<AbsoluteBendpointDto> result = router.computeOrthogonalRoute(
                source, target, List.of(obstacle));

        assertEquals("Should still produce 2 bendpoints", 2, result.size());
        // Should prefer vertical-first since horizontal-first hits obstacle
        // Vertical-first: bp1.x = srcCX, bp2.x = tgtCX
        int srcCX = source.centerX();
        int tgtCX = target.centerX();
        boolean isVerticalFirst = result.get(0).x() == srcCX && result.get(1).x() == tgtCX;
        assertTrue("Should choose vertical-first to avoid obstacle", isVerticalFirst);
    }

    @Test
    public void shouldAvoidObstacle_whenBothOrientationsBlocked() {
        // Place obstacle at the midpoint that blocks both orientations
        RoutingRect source = new RoutingRect(100, 100, 120, 55, "src");
        RoutingRect target = new RoutingRect(400, 400, 120, 55, "tgt");

        // Large obstacle covering the midpoint area
        int midX = (source.centerX() + target.centerX()) / 2;
        int midY = (source.centerY() + target.centerY()) / 2;
        RoutingRect obstacle = new RoutingRect(midX - 60, midY - 100, 120, 200, "obs");

        List<AbsoluteBendpointDto> result = router.computeOrthogonalRoute(
                source, target, List.of(obstacle));

        assertEquals("Should produce 2 bendpoints", 2, result.size());

        // Verify the computed path does not intersect the obstacle
        // Build full path: source center → bp1 → bp2 → target center
        int srcCX = source.centerX();
        int srcCY = source.centerY();
        int tgtCX = target.centerX();
        int tgtCY = target.centerY();
        int[][] segments = {
                {srcCX, srcCY, result.get(0).x(), result.get(0).y()},
                {result.get(0).x(), result.get(0).y(), result.get(1).x(), result.get(1).y()},
                {result.get(1).x(), result.get(1).y(), tgtCX, tgtCY}
        };
        for (int[] seg : segments) {
            assertFalse("Routed path segment should not intersect obstacle",
                    ConnectionRouter.lineSegmentIntersectsRect(
                            seg[0], seg[1], seg[2], seg[3],
                            obstacle.x(), obstacle.y(), obstacle.width(), obstacle.height()));
        }
    }

    @Test
    public void shouldHandleNoObstacles() {
        RoutingRect source = new RoutingRect(100, 100, 120, 55, "src");
        RoutingRect target = new RoutingRect(400, 400, 120, 55, "tgt");

        List<AbsoluteBendpointDto> result = router.computeOrthogonalRoute(
                source, target, Collections.emptyList());

        assertEquals("No obstacles should produce clean Z-shape", 2, result.size());
    }

    // ---- Multiple connections (verify statelessness) ----

    @Test
    public void shouldRouteMultipleConnectionsIndependently() {
        RoutingRect a = new RoutingRect(100, 100, 120, 55, "a");
        RoutingRect b = new RoutingRect(400, 400, 120, 55, "b");
        RoutingRect c = new RoutingRect(400, 100, 120, 55, "c");

        List<AbsoluteBendpointDto> ab = router.computeOrthogonalRoute(
                a, b, Collections.emptyList());
        List<AbsoluteBendpointDto> ac = router.computeOrthogonalRoute(
                a, c, Collections.emptyList());

        assertEquals("A→B diagonal should produce Z-shape", 2, ab.size());
        assertEquals("A→C horizontal should produce straight line", 0, ac.size());
    }

    // ---- lineSegmentIntersectsRect tests ----

    @Test
    public void shouldDetectLineRectIntersection() {
        // Line from (0,0) to (100,100) through rect at (40,40,20,20)
        assertTrue("Diagonal line through rect should intersect",
                ConnectionRouter.lineSegmentIntersectsRect(0, 0, 100, 100, 40, 40, 20, 20));
    }

    @Test
    public void shouldNotDetectIntersection_whenLineMissesRect() {
        // Horizontal line above the rect
        assertFalse("Line above rect should not intersect",
                ConnectionRouter.lineSegmentIntersectsRect(0, 0, 100, 0, 40, 40, 20, 20));
    }

    @Test
    public void shouldDetectIntersection_whenVerticalLineCrossesRect() {
        // Vertical line through rect
        assertTrue("Vertical line through rect should intersect",
                ConnectionRouter.lineSegmentIntersectsRect(50, 0, 50, 100, 40, 40, 20, 20));
    }

    @Test
    public void shouldNotIntersect_whenLineEndsBeforeRect() {
        // Short line that stops before reaching the rect
        assertFalse("Short line should not reach rect",
                ConnectionRouter.lineSegmentIntersectsRect(0, 0, 30, 30, 40, 40, 20, 20));
    }
}
