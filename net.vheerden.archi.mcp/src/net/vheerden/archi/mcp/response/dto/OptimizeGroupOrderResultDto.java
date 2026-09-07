package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result DTO for the optimize-group-order tool.
 *
 * @param viewId              the view that was optimized
 * @param crossingsBefore     inter-group edge crossings before optimization
 * @param crossingsAfter      inter-group edge crossings after optimization
 * @param reductionPercent    percentage reduction in crossings
 * @param groupsOptimized     number of groups whose element order changed
 * @param elementsReordered   total number of elements that changed position
 * @param groupDetails        per-group optimization details
 * @param resizedElements     every child whose SIZE this call changed, with the rectangle it
 *                            landed at
 *
 * <p>{@code elementsReordered} counts the children this call re-placed. {@code resizedElements}
 * names the ones whose SIZE it changed, which is a different fact: reordering a group's children
 * re-runs the arrangement, and three things there choose a width other than the child's own — a
 * grid gives every cell the width of the widest element in that group, {@code autoWidth} derives a
 * width from the label, and an explicit {@code elementWidth} or {@code elementHeight} imposes one.
 * The first two are silent by any reading. The third was asked for, and is still reported: the
 * obligation is to say what the model ENDED UP holding, and a requested width the model did not
 * end up holding is exactly the echo that obligation exists to forbid.</p>
 *
 * <p>The list is not filtered by which of the three chose the size. The observation compares the
 * landed rectangle against the one the child effectively had and reports the difference; a filter
 * would have to know WHY a size was chosen, which the observation deliberately does not.</p>
 */
public record OptimizeGroupOrderResultDto(
		String viewId,
		int crossingsBefore,
		int crossingsAfter,
		double reductionPercent,
		int groupsOptimized,
		int elementsReordered,
		List<GroupDetail> groupDetails,
		@JsonInclude(JsonInclude.Include.NON_EMPTY) List<MovedViewObjectDto> resizedElements) {

	/**
	 * Constructor matching the prior 7-field shape. Delegates with an empty
	 * {@code resizedElements}, which is omitted from JSON — so a call that resized nothing
	 * serializes byte-identically to before the field existed.
	 */
	public OptimizeGroupOrderResultDto(
			String viewId, int crossingsBefore, int crossingsAfter, double reductionPercent,
			int groupsOptimized, int elementsReordered, List<GroupDetail> groupDetails) {
		this(viewId, crossingsBefore, crossingsAfter, reductionPercent, groupsOptimized,
				elementsReordered, groupDetails, List.of());
	}

	/** Never null: the canonical constructor normalizes a null list to empty. */
	public OptimizeGroupOrderResultDto {
		resizedElements = resizedElements != null ? resizedElements : List.of();
	}

	/**
	 * Per-group detail of optimization.
	 *
	 * @param groupId           the group's view object ID
	 * @param groupName         the group's display name
	 * @param elementCount      number of elements in the group
	 * @param reordered         whether the element order changed
	 * @param arrangementUsed   the arrangement applied (row/column/grid)
	 * @param arrangementSource how the arrangement was determined (detected/override/fallback)
	 */
	public record GroupDetail(String groupId, String groupName,
							  int elementCount, boolean reordered,
							  String arrangementUsed, String arrangementSource) {
	}
}
