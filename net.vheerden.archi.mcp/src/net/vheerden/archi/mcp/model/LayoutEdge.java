package net.vheerden.archi.mcp.model;

/**
 * Abstraction for a view connection passed to layout computation.
 *
 * @param connectionId optional unique connection identifier; used by
 *        {@link ElkLayoutEngine} as the ELK edge identifier to avoid
 *        collisions when multiple connections exist between the same
 *        source/target pair. May be null for layout engines that don't
 *        need it (e.g., Zest).
 * @param labelWidth estimated rendered width (px) of this connection's label,
 *        used by {@link ElkLayoutEngine} to reserve between-layer space so
 *        labelled edges stop crowding. {@code 0} when the connection has no
 *        visible label (suppressed or empty), in which case no space is
 *        reserved. Width is the same char-count estimate the crowding detector
 *        uses (see {@link LabelWidthEstimator}).
 */
record LayoutEdge(String sourceViewObjectId, String targetViewObjectId,
		String connectionId, int labelWidth) {

	/**
	 * Back-compat constructor for layout edges that reserve no label space.
	 * Delegates to the canonical 4-arg form with {@code labelWidth = 0}, keeping
	 * the existing call sites (and the Zest path) compiling unchanged.
	 */
	LayoutEdge(String sourceViewObjectId, String targetViewObjectId,
			String connectionId) {
		this(sourceViewObjectId, targetViewObjectId, connectionId, 0);
	}
}
