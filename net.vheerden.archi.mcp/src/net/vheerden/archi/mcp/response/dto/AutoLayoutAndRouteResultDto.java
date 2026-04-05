package net.vheerden.archi.mcp.response.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result DTO for the auto-layout-and-route tool (Story 10-29, extended Story 11-31, backlog-b13, backlog-b24).
 * Supports two modes: "auto" (ELK Layered) and "grouped" (orchestrated Branch 2 workflow).
 *
 * <p>When {@code targetRating} is specified (Story 11-16), the tool iterates
 * with increasing spacing until the target quality rating is achieved or
 * max iterations are reached. The additional fields ({@code targetRating},
 * {@code achievedRating}, {@code iterationsPerformed}, {@code assessmentSummary})
 * are null when targetRating is not requested, preserving backward compatibility.</p>
 *
 * <p>When {@code targetRating} is not achieved, {@code limitingFactor} identifies
 * the worst-performing quality metric (matching a key from {@code ratingBreakdown})
 * and {@code suggestedRemediation} provides an actionable recommendation (backlog-b13).</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AutoLayoutAndRouteResultDto(
		String viewId,
		String mode,
		String direction,
		int spacing,
		int elementsRepositioned,
		int connectionsRouted,
		boolean routerTypeSwitched,
		int totalOperations,
		@JsonInclude(JsonInclude.Include.NON_DEFAULT)
		int groupsArranged,
		@JsonInclude(JsonInclude.Include.NON_DEFAULT)
		int labelsOptimized,
		@JsonInclude(JsonInclude.Include.NON_DEFAULT)
		int labelFallbackTrials,
		String targetRating,
		String achievedRating,
		Integer iterationsPerformed,
		AutoLayoutAssessmentSummaryDto assessmentSummary,
		String limitingFactor,
		String suggestedRemediation) {

	/**
	 * Backward-compatible constructor without mode/groupsArranged (15-arg, pre-b24).
	 */
	public AutoLayoutAndRouteResultDto(
			String viewId, String direction, int spacing,
			int elementsRepositioned, int connectionsRouted,
			boolean routerTypeSwitched, int totalOperations,
			int labelsOptimized, int labelFallbackTrials,
			String targetRating, String achievedRating,
			Integer iterationsPerformed,
			AutoLayoutAssessmentSummaryDto assessmentSummary,
			String limitingFactor, String suggestedRemediation) {
		this(viewId, "auto", direction, spacing, elementsRepositioned, connectionsRouted,
				routerTypeSwitched, totalOperations, 0, labelsOptimized, labelFallbackTrials,
				targetRating, achievedRating, iterationsPerformed, assessmentSummary,
				limitingFactor, suggestedRemediation);
	}

	/**
	 * Backward-compatible constructor without mode/groupsArranged/limitingFactor/suggestedRemediation.
	 */
	public AutoLayoutAndRouteResultDto(
			String viewId, String direction, int spacing,
			int elementsRepositioned, int connectionsRouted,
			boolean routerTypeSwitched, int totalOperations,
			int labelsOptimized, int labelFallbackTrials,
			String targetRating, String achievedRating,
			Integer iterationsPerformed,
			AutoLayoutAssessmentSummaryDto assessmentSummary) {
		this(viewId, "auto", direction, spacing, elementsRepositioned, connectionsRouted,
				routerTypeSwitched, totalOperations, 0, labelsOptimized, labelFallbackTrials,
				targetRating, achievedRating, iterationsPerformed, assessmentSummary,
				null, null);
	}

	/**
	 * Backward-compatible constructor without mode/groupsArranged/labelFallbackTrials or limiting factor fields.
	 */
	public AutoLayoutAndRouteResultDto(
			String viewId, String direction, int spacing,
			int elementsRepositioned, int connectionsRouted,
			boolean routerTypeSwitched, int totalOperations,
			int labelsOptimized,
			String targetRating, String achievedRating,
			Integer iterationsPerformed,
			AutoLayoutAssessmentSummaryDto assessmentSummary) {
		this(viewId, "auto", direction, spacing, elementsRepositioned, connectionsRouted,
				routerTypeSwitched, totalOperations, 0, labelsOptimized, 0,
				targetRating, achievedRating, iterationsPerformed, assessmentSummary,
				null, null);
	}

	/**
	 * Backward-compatible constructor without mode/groupsArranged/labelsOptimized/labelFallbackTrials or limiting factor fields.
	 */
	public AutoLayoutAndRouteResultDto(
			String viewId, String direction, int spacing,
			int elementsRepositioned, int connectionsRouted,
			boolean routerTypeSwitched, int totalOperations,
			String targetRating, String achievedRating,
			Integer iterationsPerformed,
			AutoLayoutAssessmentSummaryDto assessmentSummary) {
		this(viewId, "auto", direction, spacing, elementsRepositioned, connectionsRouted,
				routerTypeSwitched, totalOperations, 0, 0, 0,
				targetRating, achievedRating, iterationsPerformed, assessmentSummary,
				null, null);
	}

	/**
	 * Backward-compatible constructor without quality target fields, mode, or groupsArranged.
	 */
	public AutoLayoutAndRouteResultDto(
			String viewId, String direction, int spacing,
			int elementsRepositioned, int connectionsRouted,
			boolean routerTypeSwitched, int totalOperations) {
		this(viewId, "auto", direction, spacing, elementsRepositioned, connectionsRouted,
				routerTypeSwitched, totalOperations, 0, 0, 0, null, null, null, null,
				null, null);
	}
}
