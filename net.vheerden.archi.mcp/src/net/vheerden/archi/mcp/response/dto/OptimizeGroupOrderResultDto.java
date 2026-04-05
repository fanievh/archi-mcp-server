package net.vheerden.archi.mcp.response.dto;

import java.util.List;

/**
 * Result DTO for the optimize-group-order tool (Story 11-25).
 *
 * @param viewId              the view that was optimized
 * @param crossingsBefore     inter-group edge crossings before optimization
 * @param crossingsAfter      inter-group edge crossings after optimization
 * @param reductionPercent    percentage reduction in crossings
 * @param groupsOptimized     number of groups whose element order changed
 * @param elementsReordered   total number of elements that changed position
 * @param groupDetails        per-group optimization details
 */
public record OptimizeGroupOrderResultDto(
		String viewId,
		int crossingsBefore,
		int crossingsAfter,
		double reductionPercent,
		int groupsOptimized,
		int elementsReordered,
		List<GroupDetail> groupDetails) {

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
