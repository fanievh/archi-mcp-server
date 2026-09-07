package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result DTO for the layout-flat-view tool.
 *
 * @param viewId               the view whose top-level elements were positioned
 * @param arrangement          the arrangement pattern used (row, column, grid)
 * @param elementsRepositioned number of top-level elements repositioned
 * @param childrenRepositioned number of embedded children repositioned within parent elements
 * @param sortBy               the sort criterion used, null if none
 * @param categoryField        the category field used for visual grouping, null if none
 * @param categories           ordered list of category values used, null if no categoryField
 * @param columnsUsed          columns used for grid arrangement, null for row/column
 * @param resizedElements      every object whose SIZE this call changed, with the rectangle it
 *                             landed at
 *
 * <p>{@code elementsRepositioned} and {@code resizedElements} describe different things. The first
 * counts the top-level elements this call placed — moved or not, resized or not. The second names
 * the ones whose size it changed, which is a strictly smaller set and the only one nobody asked
 * for: a parent element grown to contain the children the same call laid out inside it. Before this
 * field the only trace of that growth was a local boolean, which never left the method.</p>
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
		Integer columnsUsed,
		@JsonInclude(JsonInclude.Include.NON_EMPTY) List<MovedViewObjectDto> resizedElements) {

	/**
	 * Constructor matching the prior 8-field shape. Delegates with an empty
	 * {@code resizedElements}, which is omitted from JSON — so a call that resized nothing
	 * serializes byte-identically to before the field existed.
	 */
	public LayoutFlatViewResultDto(
			String viewId, String arrangement, int elementsRepositioned,
			int childrenRepositioned, String sortBy, String categoryField,
			List<String> categories, Integer columnsUsed) {
		this(viewId, arrangement, elementsRepositioned, childrenRepositioned, sortBy,
				categoryField, categories, columnsUsed, List.of());
	}

	/** Never null: the canonical constructor normalizes a null list to empty. */
	public LayoutFlatViewResultDto {
		resizedElements = resizedElements != null ? resizedElements : List.of();
	}
}
