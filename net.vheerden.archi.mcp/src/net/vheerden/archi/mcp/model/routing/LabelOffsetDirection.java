package net.vheerden.archi.mcp.model.routing;

import java.util.List;

import net.vheerden.archi.mcp.model.RoutingRect;

/**
 * Compass anchors for a connection label's perpendicular offset.
 *
 * <p>{@code mask} is the diagram-connection model's anchor bitmask (Centre plus eight compass points;
 * cardinals are single bits, diagonals OR-combine two cardinals). {@code dx}/{@code dy} is the
 * screen-space direction (y grows downward) the label moves on each axis; {@link #drawn} gives the
 * distance, which is the renderer's.</p>
 *
 * <p>Pure geometry — no EMF/SWT. The mask is written reflectively elsewhere, and only when the running
 * platform exposes the label-offset feature; on a platform without it the offset path is inert.</p>
 */
enum LabelOffsetDirection {
    NORTH(1, 0, -1),
    SOUTH(4, 0, 1),
    WEST(8, -1, 0),
    EAST(16, 1, 0),
    NORTH_EAST(17, 1, -1),
    NORTH_WEST(9, -1, -1),
    SOUTH_EAST(20, 1, 1),
    SOUTH_WEST(12, -1, 1);

    /** Anchor bitmask written to the connection's label-offset feature. */
    final int mask;
    /** Unit step on the x axis (screen space). */
    final int dx;
    /** Unit step on the y axis (screen space, growing downward). */
    final int dy;

    /**
     * Anchor bitmask of a centred (un-offset) label: the feature default, and what a connection reads as
     * on a platform without the feature. Mirrored from {@code RelativePositionFeature.CENTER} (duplicated
     * due to the model / model.routing boundary; a test holds the two equal). It sets no bit
     * {@link #drawn(RoutingRect, int)} tests, so it moves nothing.
     */
    static final int CENTER = 2;

    LabelOffsetDirection(int mask, int dx, int dy) {
        this.mask = mask;
        this.dx = dx;
        this.dy = dy;
    }

    /**
     * The label as Archi draws it with this anchor applied. draw2d's {@code AbstractLocator.getNewBounds}
     * moves the centred label by half its own size (integer division) plus the locator's gap on each axis
     * the anchor names ({@link LabelBoxEstimate#drawnOffsetLift}, the one rule the assessor moves its label
     * by too), which leaves it that gap clear of its reference point on that side. So the distance depends
     * on the label: a wide label moves far further East or West than North or South. Exact for the
     * renderer's rule; the label size it is applied to is still an estimate.
     */
    RoutingRect drawn(RoutingRect label) {
        return drawn(label, mask);
    }

    /**
     * The label as Archi draws it with any anchor bitmask applied, including one a user set that is not
     * one of the eight constants. draw2d reads the mask one axis at a time: NORTH wins over SOUTH and WEST
     * over EAST when both are set, and an axis with neither bit does not move, so {@link #CENTER} and an
     * unknown mask leave the label where it is. The same rule as {@code LayoutQualityAssessor}'s
     * offset shift, through the same {@link LabelBoxEstimate#drawnOffsetLift}, so the two move a label by
     * the same pixels. The box they move can still differ by up to half a pixel on each axis: this side's
     * is rounded to whole pixels, the assessor's is not.
     */
    static RoutingRect drawn(RoutingRect label, int anchor) {
        int xFactor = xFactor(anchor);
        int yFactor = yFactor(anchor);
        return new RoutingRect(
                label.x() + xFactor * LabelBoxEstimate.drawnOffsetLift(label.width()),
                label.y() + yFactor * LabelBoxEstimate.drawnOffsetLift(label.height()),
                label.width(), label.height(), label.id());
    }

    /**
     * True when Archi draws a Middle label in the same place for both anchors: the same move on each axis. A
     * mask with both bits on an axis draws where its winning bit does, and {@link #CENTER} and an unknown mask
     * draw alike, un-moved.
     */
    static boolean drawsAlike(int a, int b) {
        return xFactor(a) == xFactor(b) && yFactor(a) == yFactor(b);
    }

    /** True when {@code anchor} moves a Middle label at all (it names at least one axis). */
    static boolean moves(int anchor) {
        return xFactor(anchor) != 0 || yFactor(anchor) != 0;
    }

    private static int xFactor(int anchor) {
        return (anchor & WEST.mask) != 0 ? -1 : (anchor & EAST.mask) != 0 ? 1 : 0;
    }

    private static int yFactor(int anchor) {
        return (anchor & NORTH.mask) != 0 ? -1 : (anchor & SOUTH.mask) != 0 ? 1 : 0;
    }

    private static final List<LabelOffsetDirection> PERPENDICULAR_TO_HORIZONTAL = List.of(
            NORTH, SOUTH, WEST, EAST, NORTH_EAST, NORTH_WEST, SOUTH_EAST, SOUTH_WEST);
    private static final List<LabelOffsetDirection> PERPENDICULAR_TO_VERTICAL = List.of(
            WEST, EAST, NORTH, SOUTH, NORTH_EAST, NORTH_WEST, SOUTH_EAST, SOUTH_WEST);

    /**
     * Deterministic candidate order: the cardinals perpendicular to the hosting segment first
     * (a label is best lifted across the line it sits on), then the parallel cardinals, then the
     * diagonals. {@code horizontalSegment} true → try North/South first; false → West/East first.
     */
    static List<LabelOffsetDirection> candidateOrder(boolean horizontalSegment) {
        return horizontalSegment ? PERPENDICULAR_TO_HORIZONTAL : PERPENDICULAR_TO_VERTICAL;
    }
}
