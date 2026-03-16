package net.vheerden.archi.mcp.response.dto;

/**
 * Result DTO for the layout-within-group tool (Story 9-9, enhanced 11-14, 11-18).
 *
 * @param viewId               the view containing the group
 * @param groupViewObjectId    the group whose children were arranged
 * @param arrangement          the arrangement pattern used (row, column, grid)
 * @param elementsRepositioned number of child elements repositioned
 * @param groupResized         whether the group was resized (autoResize)
 * @param newGroupWidth        new group width if resized, null otherwise
 * @param newGroupHeight       new group height if resized, null otherwise
 * @param overflow             true if child positions exceed current group bounds (when autoResize=false)
 * @param autoWidth            true if element widths were computed from label text
 * @param columnsUsed          number of columns used for grid arrangement, null for row/column
 * @param ancestorsResized     number of ancestor groups resized (recursive auto-resize)
 */
public record LayoutWithinGroupResultDto(
		String viewId,
		String groupViewObjectId,
		String arrangement,
		int elementsRepositioned,
		boolean groupResized,
		Integer newGroupWidth,
		Integer newGroupHeight,
		boolean overflow,
		boolean autoWidth,
		Integer columnsUsed,
		int ancestorsResized) {
}
