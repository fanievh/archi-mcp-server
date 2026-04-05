package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result DTO for the layout-flat-view tool (Story 13-6, enhanced B20).
 *
 * @param viewId               the view whose top-level elements were positioned
 * @param arrangement          the arrangement pattern used (row, column, grid)
 * @param elementsRepositioned number of top-level elements repositioned
 * @param childrenRepositioned number of embedded children repositioned within parent elements
 * @param sortBy               the sort criterion used, null if none
 * @param categoryField        the category field used for visual grouping, null if none
 * @param categories           ordered list of category values used, null if no categoryField
 * @param columnsUsed          columns used for grid arrangement, null for row/column
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LayoutFlatViewResultDto(
		String viewId,
		String arrangement,
		int elementsRepositioned,
		int childrenRepositioned,
		String sortBy,
		String categoryField,
		List<String> categories,
		Integer columnsUsed) {
}
