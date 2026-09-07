package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result DTO for the auto-layout-and-route tool.
 * Supports two modes: "auto" (ELK Layered) and "grouped" (orchestrated Branch 2 workflow).
 *
 * <p>When {@code targetRating} is specified, the tool runs a quality loop that assesses the view
 * after each attempt and tunes whichever lever the worst metric calls for. It stops as soon as any
 * of its exit conditions holds, and {@code terminationReason} names the one that fired — read that
 * field rather than a count kept in prose here, which has to be maintained by hand and has been
 * wrong before. The additional fields ({@code targetRating}, {@code achievedRating},
 * {@code ratingBefore}, {@code iterationsPerformed}, {@code assessmentSummary}) are null when
 * targetRating is not requested, preserving backward compatibility.</p>
 *
 * <p>When {@code targetRating} is not achieved, {@code limitingFactor} identifies
 * the worst-performing quality metric (matching a key from {@code ratingBreakdown})
 * and {@code suggestedRemediation} provides an actionable recommendation.</p>
 *
 * <p>{@code terminationReason} says which condition actually stopped the loop, and
 * is present whenever a quality loop ran — including when the target was met. It is a different
 * question from {@code limitingFactor}: the factor is the worst metric right now, the reason is
 * whether trying again could change it. A run that used up its budget and a run that stopped
 * because its worst metric is irremediable can report the <em>same</em> factor and demand opposite
 * follow-up. It is null only when {@code targetRating} was omitted and no loop ran, so a call that
 * does not ask for iteration serializes exactly as it did before the field existed.</p>
 *
 * <p>{@code ratingBefore} is the overall rating the view held <em>before the call</em>, measured
 * once per call ahead of any mutation. It is present whenever a quality loop ran — including when
 * the target was met and including when the result improved — so its absence never has to be
 * interpreted. Without it {@code achievedRating} is a statement about the outcome and not a
 * comparison against the input, and a caller that had not itself assessed the view beforehand
 * cannot tell a failure to improve from an active regression.</p>
 *
 * <p>{@code structuredWarnings} carries the
 * {@link StructuredWarningCodes#AUTO_LAYOUT_RATING_REGRESSED} disclosure when the committed state
 * is worse than {@code ratingBefore} on the composite the loop itself ranks by. The result stays
 * applied — the warning is the disclosure that lets the caller undo it, and it names every metric
 * that moved, because a same-band regression reports two identical ratings on the wire.</p>
 *
 * <p><strong>{@code terminationReason} is measured, not projected, on every path — including batch
 * and approval mode.</strong> The loop applies each attempt, assesses it and undoes it
 * <em>before</em> anything is queued or proposed, so the reason describes work that genuinely ran.
 * This is unlike geometry, which is unavailable until dispatch and is therefore a projection on
 * those paths. Do not null this field on the deferred paths.</p>
 *
 * <p><strong>{@code resizedElements} is GROUPED MODE ONLY.</strong> The recursive descent stretches
 * a leaf to the grid cell its siblings decided — one wide element setting the width of every cell
 * in its container — and that observation was already computed and then discarded at the call site.
 * The field names each such leaf with the rectangle it landed at. It is distinct from
 * {@code nestedContainersFitted}, which names the CONTAINERS the descent re-fitted: a container the
 * walk descended into is reported there and never here, so the two are disjoint by construction.
 * Flat mode never reaches the descent, so the list is always empty there and omitted from JSON.</p>
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
		String ratingBefore,
		Integer iterationsPerformed,
		AutoLayoutAssessmentSummaryDto assessmentSummary,
		String limitingFactor,
		String suggestedRemediation,
		String terminationReason,
		@JsonInclude(JsonInclude.Include.NON_EMPTY)
		List<MovedViewObjectDto> nestedContainersFitted,
		@JsonInclude(JsonInclude.Include.NON_DEFAULT)
		boolean depthCapHit,
		@JsonInclude(JsonInclude.Include.NON_EMPTY)
		List<HiddenLabelDto> hiddenLabels,
		@JsonInclude(JsonInclude.Include.NON_EMPTY)
		List<StructuredWarningDto> structuredWarnings,
		@JsonInclude(JsonInclude.Include.NON_EMPTY)
		List<MovedViewObjectDto> resizedElements) {

	/**
	 * Returns a copy carrying the given hidden-label list.
	 *
	 * <p>Attached <em>after</em> the write so the immediate path reports visibility re-read from the
	 * model rather than the policy's intent. On the deferred paths nothing has been written yet, so
	 * what travels here is a projection — and the envelope nests the whole entity under
	 * {@code preview}, which is what marks it as not-yet-effective.</p>
	 */
	public AutoLayoutAndRouteResultDto withHiddenLabels(List<HiddenLabelDto> hidden) {
		return new AutoLayoutAndRouteResultDto(viewId, mode, direction, spacing,
				elementsRepositioned, connectionsRouted, routerTypeSwitched, totalOperations,
				groupsArranged, labelsOptimized, labelFallbackTrials, targetRating, achievedRating,
				ratingBefore, iterationsPerformed, assessmentSummary, limitingFactor,
				suggestedRemediation, terminationReason,
				nestedContainersFitted, depthCapHit, hidden, structuredWarnings, resizedElements);
	}

	/**
	 * Returns a copy carrying the given structured warnings, every other field preserved.
	 *
	 * <p>Exists because the rating-regression disclosure is composed <em>before</em> the facade
	 * knows which arm the call will take. {@code buildQualityTargetDto} runs ahead of the approval
	 * gate and ahead of {@code dispatchOrQueue}, so the tail it stamps on — that the state was
	 * applied and that {@code undo} recovers it — is true only on the immediate arm. On the batched
	 * and awaiting-approval arms nothing has been applied, {@code undo} would revert an earlier
	 * command, and the remedy has to be the one that arm actually offers. The measurement itself is
	 * owed on all three arms and is never dropped: {@code bestAssessment} is taken inside the
	 * temporary dispatch window, so the regression is genuinely measured even when the result is
	 * queued. Only the remedy sentence is rescoped.</p>
	 *
	 * <p>Applied to the <em>returned</em> copy only. The proposal keeps the applied-tail DTO for its
	 * rebuild handle, because after a human approves it the applied claim becomes true.</p>
	 */
	public AutoLayoutAndRouteResultDto withStructuredWarnings(List<StructuredWarningDto> warnings) {
		return new AutoLayoutAndRouteResultDto(viewId, mode, direction, spacing,
				elementsRepositioned, connectionsRouted, routerTypeSwitched, totalOperations,
				groupsArranged, labelsOptimized, labelFallbackTrials, targetRating, achievedRating,
				ratingBefore, iterationsPerformed, assessmentSummary, limitingFactor,
				suggestedRemediation, terminationReason,
				nestedContainersFitted, depthCapHit, hiddenLabels, warnings, resizedElements);
	}

	/**
	 * The 17-field shape, before grouped mode descended into nested containers, before the quality
	 * loop reported why it stopped, and before it reported the rating the view had on arrival.
	 * Delegates with a null reason, a null pre-call rating, empty lists and a false cap flag, all of
	 * which are omitted from JSON — so a call that fitted no nested container and ran no quality loop
	 * serializes byte-identically to before those fields existed.
	 */
	public AutoLayoutAndRouteResultDto(
			String viewId, String mode, String direction, int spacing,
			int elementsRepositioned, int connectionsRouted, boolean routerTypeSwitched,
			int totalOperations, int groupsArranged, int labelsOptimized,
			int labelFallbackTrials, String targetRating, String achievedRating,
			Integer iterationsPerformed, AutoLayoutAssessmentSummaryDto assessmentSummary,
			String limitingFactor, String suggestedRemediation) {
		this(viewId, mode, direction, spacing, elementsRepositioned, connectionsRouted,
				routerTypeSwitched, totalOperations, groupsArranged, labelsOptimized,
				labelFallbackTrials, targetRating, achievedRating, null, iterationsPerformed,
				assessmentSummary, limitingFactor, suggestedRemediation, null,
				List.of(), false, List.of(), List.of());
	}

	/** Never null: the canonical constructor normalizes a null list to empty. */
	public AutoLayoutAndRouteResultDto {
		nestedContainersFitted = nestedContainersFitted != null
				? nestedContainersFitted : List.of();
		hiddenLabels = hiddenLabels != null ? hiddenLabels : List.of();
		structuredWarnings = structuredWarnings != null ? structuredWarnings : List.of();
		resizedElements = resizedElements != null ? resizedElements : List.of();
	}

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
	 * The 23-field shape, before grouped mode reported the leaves it stretched. Delegates with an
	 * empty {@code resizedElements}, which is omitted from JSON — so a call that stretched no leaf
	 * serializes byte-identically to before the field existed.
	 */
	public AutoLayoutAndRouteResultDto(
			String viewId, String mode, String direction, int spacing,
			int elementsRepositioned, int connectionsRouted, boolean routerTypeSwitched,
			int totalOperations, int groupsArranged, int labelsOptimized, int labelFallbackTrials,
			String targetRating, String achievedRating, String ratingBefore,
			Integer iterationsPerformed, AutoLayoutAssessmentSummaryDto assessmentSummary,
			String limitingFactor, String suggestedRemediation, String terminationReason,
			List<MovedViewObjectDto> nestedContainersFitted, boolean depthCapHit,
			List<HiddenLabelDto> hiddenLabels, List<StructuredWarningDto> structuredWarnings) {
		this(viewId, mode, direction, spacing, elementsRepositioned, connectionsRouted,
				routerTypeSwitched, totalOperations, groupsArranged, labelsOptimized,
				labelFallbackTrials, targetRating, achievedRating, ratingBefore, iterationsPerformed,
				assessmentSummary, limitingFactor, suggestedRemediation, terminationReason,
				nestedContainersFitted, depthCapHit, hiddenLabels, structuredWarnings, List.of());
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
