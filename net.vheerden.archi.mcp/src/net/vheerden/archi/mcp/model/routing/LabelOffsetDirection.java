package net.vheerden.archi.mcp.model.routing;

import java.util.List;

/**
 * Compass anchors for a connection label's perpendicular offset.
 *
 * <p>{@code mask} is the diagram-connection model's anchor bitmask (Centre plus eight compass points;
 * cardinals are single bits, diagonals OR-combine two cardinals). {@code dx}/{@code dy} is the
 * screen-space unit step (y grows downward) used to model how far the label shifts when scoring which
 * direction lifts it clear of an overlapping element.</p>
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

    LabelOffsetDirection(int mask, int dx, int dy) {
        this.mask = mask;
        this.dx = dx;
        this.dy = dy;
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
