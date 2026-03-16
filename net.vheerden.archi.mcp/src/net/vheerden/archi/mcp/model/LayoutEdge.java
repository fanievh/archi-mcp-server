package net.vheerden.archi.mcp.model;

/**
 * Abstraction for a view connection passed to layout computation.
 *
 * @param connectionId optional unique connection identifier; used by
 *        {@link ElkLayoutEngine} as the ELK edge identifier to avoid
 *        collisions when multiple connections exist between the same
 *        source/target pair. May be null for layout engines that don't
 *        need it (e.g., Zest).
 */
record LayoutEdge(String sourceViewObjectId, String targetViewObjectId,
		String connectionId) {}
