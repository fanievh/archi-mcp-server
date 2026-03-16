package net.vheerden.archi.mcp.response.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result DTO for the auto-layout-and-route tool (Story 10-29, extended Story 11-31).
 * ELK Layered computes both element positions and connection routes.
 *
 * <p>When {@code targetRating} is specified (Story 11-16), the tool iterates
 * with increasing spacing until the target quality rating is achieved or
 * max iterations are reached. The additional fields ({@code targetRating},
 * {@code achievedRating}, {@code iterationsPerformed}, {@code assessmentSummary})
 * are null when targetRating is not requested, preserving backward compatibility.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AutoLayoutAndRouteResultDto(
		String viewId,
		String direction,
		int spacing,
		int elementsRepositioned,
		int connectionsRouted,
		boolean routerTypeSwitched,
		int totalOperations,
		@JsonInclude(JsonInclude.Include.NON_DEFAULT)
		int labelsOptimized,
		String targetRating,
		String achievedRating,
		Integer iterationsPerformed,
		AutoLayoutAssessmentSummaryDto assessmentSummary) {

	/**
	 * Backward-compatible constructor without labelsOptimized.
	 */
	public AutoLayoutAndRouteResultDto(
			String viewId, String direction, int spacing,
			int elementsRepositioned, int connectionsRouted,
			boolean routerTypeSwitched, int totalOperations,
			String targetRating, String achievedRating,
			Integer iterationsPerformed,
			AutoLayoutAssessmentSummaryDto assessmentSummary) {
		this(viewId, direction, spacing, elementsRepositioned, connectionsRouted,
				routerTypeSwitched, totalOperations, 0, targetRating, achievedRating,
				iterationsPerformed, assessmentSummary);
	}

	/**
	 * Backward-compatible constructor without quality target fields or labelsOptimized.
	 */
	public AutoLayoutAndRouteResultDto(
			String viewId, String direction, int spacing,
			int elementsRepositioned, int connectionsRouted,
			boolean routerTypeSwitched, int totalOperations) {
		this(viewId, direction, spacing, elementsRepositioned, connectionsRouted,
				routerTypeSwitched, totalOperations, 0, null, null, null, null);
	}
}
