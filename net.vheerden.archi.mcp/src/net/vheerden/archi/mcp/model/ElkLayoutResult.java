package net.vheerden.archi.mcp.model;

import java.util.List;
import java.util.Map;

import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.ViewPositionSpec;

/**
 * Result of ELK layout computation containing element positions and connection routes.
 * Pure-geometry record — no EMF/SWT dependencies.
 */
record ElkLayoutResult(
		List<ViewPositionSpec> positions,
		Map<String, List<AbsoluteBendpointDto>> connectionBendpoints,
		int elementsRepositioned,
		int connectionsRouted) {

	ElkLayoutResult {
		if (positions == null) {
			throw new IllegalArgumentException("positions must not be null");
		}
		if (connectionBendpoints == null) {
			throw new IllegalArgumentException("connectionBendpoints must not be null");
		}
	}
}
