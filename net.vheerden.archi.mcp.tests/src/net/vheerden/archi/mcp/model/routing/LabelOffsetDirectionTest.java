package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Field;

import org.junit.Test;

import net.vheerden.archi.mcp.model.RoutingRect;

/**
 * The optimizer's copy of Archi's Label Offset rule: a Middle label moves by half its own size (integer
 * division) plus the locator's 5 px gap on each axis the anchor names. The assessor holds the same rule on
 * the other side of the model / model.routing boundary, so the gap is checked against its copy here.
 */
public class LabelOffsetDirectionTest {

    /**
     * The gap and the lift are one rule both sides call ({@link LabelBoxEstimate#drawnOffsetLift}), so there
     * is no second copy to hold equal: this pins the rule itself. The renderer halves its whole-pixel figure
     * size, so an unrounded size is rounded first (85.6 → 86 → 43) and never floored (85.6 / 2 → 42).
     */
    @Test
    public void drawnOffsetLift_halvesTheWholePixelSizePlusTheLocatorsGap() {
        assertEquals(5, LabelBoxEstimate.OFFSET_RENDER_GAP);
        assertEquals(48, LabelBoxEstimate.drawnOffsetLift(85.6));
        assertEquals(48, LabelBoxEstimate.drawnOffsetLift(86));
        assertEquals(47, LabelBoxEstimate.drawnOffsetLift(85));
        assertEquals(53, LabelBoxEstimate.drawnOffsetLift(96.4));
        assertEquals(15, LabelBoxEstimate.drawnOffsetLift(LabelBoxEstimate.HEIGHT));
    }

    @Test
    public void center_matchesTheFeaturesCopy() throws Exception {
        Field featureCenter = Class.forName("net.vheerden.archi.mcp.model.RelativePositionFeature")
                .getDeclaredField("CENTER");
        featureCenter.setAccessible(true);
        assertEquals(featureCenter.getInt(null), LabelOffsetDirection.CENTER);
    }

    @Test
    public void connectionEndpoints_withoutAnAnchor_holdCentre() {
        RoutingRect a = new RoutingRect(0, 0, 10, 10, "a");
        RoutingRect b = new RoutingRect(50, 0, 10, 10, "b");
        assertEquals(LabelOffsetDirection.CENTER, new RoutingPipeline.ConnectionEndpoints(
                "c", a, b, java.util.List.of(), "x", 1).relativePosition());
        assertEquals(LabelOffsetDirection.CENTER, new RoutingPipeline.ConnectionEndpoints(
                "c", a, b, java.util.List.of(), "x", 1, java.util.List.of()).relativePosition());
        assertEquals(17, new RoutingPipeline.ConnectionEndpoints(
                "c", a, b, java.util.List.of(), "x", 1, java.util.List.of(), 17).relativePosition());
    }

    @Test
    public void drawn_movesHalfTheSizeRoundedDownPlusTheGap() {
        // A 75 x 21 box: 75 / 2 = 37 and 21 / 2 = 10 in integer division, so the moves are 42 and 15, the
        // same as the assessor's floor(75 / 2.0) + 5 and floor(21 / 2.0) + 5.
        RoutingRect label = new RoutingRect(100, 200, 75, 21, "c1");

        assertRect(100, 185, LabelOffsetDirection.NORTH.drawn(label));
        assertRect(100, 215, LabelOffsetDirection.SOUTH.drawn(label));
        assertRect(58, 200, LabelOffsetDirection.WEST.drawn(label));
        assertRect(142, 200, LabelOffsetDirection.EAST.drawn(label));
        assertRect(142, 185, LabelOffsetDirection.NORTH_EAST.drawn(label));
        assertRect(58, 215, LabelOffsetDirection.SOUTH_WEST.drawn(label));
    }

    @Test
    public void drawn_keepsSizeAndId() {
        RoutingRect moved = LabelOffsetDirection.NORTH_WEST.drawn(new RoutingRect(0, 0, 74, 20, "c9"));
        assertEquals(74, moved.width());
        assertEquals(20, moved.height());
        assertEquals("c9", moved.id());
    }

    private static void assertRect(int x, int y, RoutingRect r) {
        assertEquals("x", x, r.x());
        assertEquals("y", y, r.y());
        assertEquals("width", 75, r.width());
        assertEquals("height", 21, r.height());
    }
}
