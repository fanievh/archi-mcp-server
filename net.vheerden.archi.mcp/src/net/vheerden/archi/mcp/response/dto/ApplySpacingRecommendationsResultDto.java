package net.vheerden.archi.mcp.response.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Result DTO for the {@code apply-spacing-recommendations} composed
 * convenience tool. Bundles BOTH the element-arm and the group-arm of the
 * spacing-recommendation envelope into a single response, with knee-clamp
 * metadata surfaced via the {@code proposedXxxDelta} / {@code interXxxDelta}
 * pair + the {@code xxxKneeClampApplied} booleans.
 *
 * <p>Under {@code dryRun=true} OR both-deltas-zero short-circuit (no
 * mutation), {@code after} and {@code adjustResult} are null; on the
 * both-deltas-zero short-circuit, {@code noChangeReason} is populated.</p>
 *
 * <p>The {@code scope} field echoes the input enum value
 * ({@code "both" | "element" | "group"}) so a caller can confirm which arms
 * were considered. Scope-excluded arms have proposedXxxDelta=0 +
 * interXxxDelta=0 + xxxKneeClampApplied=false uniformly.</p>
 *
 * @param viewId                    echoed input
 * @param scope                     echoed input scope enum value
 *                                  ({@code "both"} default,
 *                                  {@code "element"}, {@code "group"})
 * @param dryRun                    echoed input — under true, the tool
 *                                  computes the recommendation but does NOT
 *                                  mutate (no speculative-mutate-and-undo)
 * @param connectionCount           total connections on the view (single
 *                                  source of truth — {@code assess-layout}
 *                                  connectionCount field)
 * @param interGroupConnectionCount count of connections crossing a top-level
 *                                  container boundary — a native view group
 *                                  and an ArchiMate {@code Grouping} element
 *                                  alike; computed by
 *                                  {@code TopLevelGroupTargets
 *                                  .countInterGroupConnections} (same source
 *                                  as the apply-group sibling tool)
 * @param isConnected               true when
 *                                  {@code interGroupConnectionCount > 0};
 *                                  selects the connected column on the
 *                                  group-spacing heuristic
 * @param hasLargeHubs              true when at least one element on the
 *                                  view has &gt; 6 connections — the
 *                                  large-hub threshold, computed by
 *                                  {@code HubSpacingSignal}. Distinct from
 *                                  the &ge; 5 hub-candidate cut and from the
 *                                  &gt; 12 high-fan-out cut in
 *                                  {@code HubSizingSuggestionBuilder}; both
 *                                  heuristic tables consume this flag
 * @param currentElementSpacingPx   minimum per-group element spacing across
 *                                  all top-level groups (most-tight wins)
 * @param currentGroupSpacingPx     minimum inter-group spacing across
 *                                  adjacent top-level groups (most-tight
 *                                  pair wins)
 * @param elementTargetSpacingPx    heuristic-or-override target element
 *                                  spacing (always populated regardless of
 *                                  scope; {@code proposedElementDelta} and
 *                                  {@code interElementDelta} are 0 when
 *                                  scope excludes the element arm)
 * @param groupTargetSpacingPx      heuristic-or-override target inter-group
 *                                  spacing (always populated regardless of
 *                                  scope; {@code proposedGroupDelta} and
 *                                  {@code interGroupDelta} are 0 when scope
 *                                  excludes the group arm)
 * @param proposedElementDelta      pre-clamp element delta
 *                                  ({@code max(0, target - current)});
 *                                  surfaces the unclamped value when the
 *                                  knee guard fires
 * @param proposedGroupDelta        pre-clamp group delta
 * @param interElementDelta         clamped element delta — applied to
 *                                  {@code adjustViewSpacing}; capped at
 *                                  {@code ApplySpacingDecision
 *                                  .ELEMENT_KNEE_LIMIT_PX}
 * @param interGroupDelta           clamped group delta — applied to
 *                                  {@code adjustViewSpacing}; capped at
 *                                  {@code ApplySpacingDecision
 *                                  .GROUP_KNEE_LIMIT_PX}
 * @param elementKneeClampApplied   true when the element-arm clamp fired
 *                                  ({@code interElementDelta <
 *                                  proposedElementDelta})
 * @param groupKneeClampApplied     true when the group-arm clamp fired
 *                                  ({@code interGroupDelta <
 *                                  proposedGroupDelta})
 * @param noChangeReason            populated when BOTH clamped deltas are
 *                                  0 (short-circuit), naming each in-scope
 *                                  arm's own reason — the structural refusals
 *                                  included; null otherwise
 * @param elementTargetOverride     echoed input — when non-null, the
 *                                  caller-provided override that supplied
 *                                  {@code elementTargetSpacingPx}
 * @param groupTargetOverride       echoed input — when non-null, the
 *                                  caller-provided override that supplied
 *                                  {@code groupTargetSpacingPx}
 * @param before                    assess-layout snapshot captured BEFORE
 *                                  any mutation (always populated)
 * @param after                     assess-layout snapshot captured AFTER
 *                                  the mutation; null under
 *                                  {@code dryRun=true} OR both-deltas-zero
 *                                  short-circuit
 * @param adjustResult              delegate result from
 *                                  {@code adjustViewSpacing}; null under
 *                                  {@code dryRun=true} OR both-deltas-zero
 *                                  short-circuit
 */
/**
 * <p><strong>Control-loop fields:</strong> the composer runs TWO coordinated control loops per
 * Option A from architecture-spec § 1.7 (element-arm first, then group-arm).
 * Each arm reports its own termination/trajectory via per-arm fields:
 * {@code elementTerminationReason} + {@code elementIterationCount} +
 * {@code elementAppliedDeltas} (and the group-arm sibling triple). The
 * per-arm fields make the two trajectories independently parseable by the
 * LLM agent (e.g., "element arm reached goal at iteration 2, group arm hit
 * budget exhaustion after 4 iterations"). When the scope excludes an arm
 * OR the entry guard short-circuits that arm, the arm's
 * {@code terminationReason} is null + {@code iterationCount} is 0 +
 * {@code appliedDeltas} is empty.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplySpacingRecommendationsResultDto(
        String viewId,
        String scope,
        boolean dryRun,
        int connectionCount,
        int interGroupConnectionCount,
        boolean isConnected,
        boolean hasLargeHubs,
        int currentElementSpacingPx,
        int currentGroupSpacingPx,
        int elementTargetSpacingPx,
        int groupTargetSpacingPx,
        int proposedElementDelta,
        int proposedGroupDelta,
        int interElementDelta,
        int interGroupDelta,
        boolean elementKneeClampApplied,
        boolean groupKneeClampApplied,
        String noChangeReason,
        Integer elementTargetOverride,
        Integer groupTargetOverride,
        AssessLayoutResultDto before,
        AssessLayoutResultDto after,
        AdjustViewSpacingResultDto adjustResult,
        // Control-loop per-arm fields. Appended; backwards-compat.
        String elementTerminationReason,
        int elementIterationCount,
        List<Integer> elementAppliedDeltas,
        String groupTerminationReason,
        int groupIterationCount,
        List<Integer> groupAppliedDeltas,
        // Density-aware-termination per-arm fields.
        // Each arm's actionable PASS-honest reflow diagnosis; null on every
        // non-PASS-honest path. Appended; backwards-compat preserved.
        String elementDensityFloorDiagnosis,
        String groupDensityFloorDiagnosis,
        /**
         * The rating-regression disclosure: one entry when this call committed a view whose
         * overall quality is worse than the view it was handed, empty otherwise.
         *
         * <p>One entry for the call, not one per arm. Both arms' commands are spliced into a
         * single compound and dispatched once, so there is exactly one committed state to compare
         * against the one pre-call state.</p>
         *
         * <p>Empty is the ordinary case and is omitted from the wire entirely, so a clean run
         * serializes exactly as it did before this field existed.</p>
         *
         * <p><strong>Empty does not certify a clean result on a queued call.</strong> When a batch
         * is open the accepted commands are queued rather than executed and both arms have already
         * reset the model, so {@code after} re-reads the unmutated view and the comparison is
         * structurally blind. {@code nextSteps} says so in words on that path rather than leaving
         * the silence to be read as "nothing regressed".</p>
         */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<StructuredWarningDto> structuredWarnings) {

    /**
     * Backwards-compatible constructor for every call site that predates the rating-regression
     * disclosure — the pre-loop short-circuit, dry-run and no-change paths, which take no
     * comparison and therefore have nothing to disclose.
     *
     * <p>Delegates with an empty warning list, which {@code NON_EMPTY} omits, so these paths
     * serialize byte-identically to before.</p>
     */
    public ApplySpacingRecommendationsResultDto(
            String viewId,
            String scope,
            boolean dryRun,
            int connectionCount,
            int interGroupConnectionCount,
            boolean isConnected,
            boolean hasLargeHubs,
            int currentElementSpacingPx,
            int currentGroupSpacingPx,
            int elementTargetSpacingPx,
            int groupTargetSpacingPx,
            int proposedElementDelta,
            int proposedGroupDelta,
            int interElementDelta,
            int interGroupDelta,
            boolean elementKneeClampApplied,
            boolean groupKneeClampApplied,
            String noChangeReason,
            Integer elementTargetOverride,
            Integer groupTargetOverride,
            AssessLayoutResultDto before,
            AssessLayoutResultDto after,
            AdjustViewSpacingResultDto adjustResult,
            String elementTerminationReason,
            int elementIterationCount,
            List<Integer> elementAppliedDeltas,
            String groupTerminationReason,
            int groupIterationCount,
            List<Integer> groupAppliedDeltas,
            String elementDensityFloorDiagnosis,
            String groupDensityFloorDiagnosis) {
        this(viewId, scope, dryRun, connectionCount, interGroupConnectionCount,
                isConnected, hasLargeHubs, currentElementSpacingPx,
                currentGroupSpacingPx, elementTargetSpacingPx,
                groupTargetSpacingPx, proposedElementDelta, proposedGroupDelta,
                interElementDelta, interGroupDelta, elementKneeClampApplied,
                groupKneeClampApplied, noChangeReason, elementTargetOverride,
                groupTargetOverride, before, after, adjustResult,
                elementTerminationReason, elementIterationCount,
                elementAppliedDeltas, groupTerminationReason,
                groupIterationCount, groupAppliedDeltas,
                elementDensityFloorDiagnosis, groupDensityFloorDiagnosis,
                /*structuredWarnings=*/ List.of());
    }

    /**
     * Backwards-compatible 29-arg constructor — preserves every
     * pre-existing 29-arg call site; delegates with both per-arm
     * {@code densityFloorDiagnosis = null}). Only the composer PASS-honest
     * path forwards the real per-arm diagnoses via the 31-arg form.
     */
    public ApplySpacingRecommendationsResultDto(
            String viewId,
            String scope,
            boolean dryRun,
            int connectionCount,
            int interGroupConnectionCount,
            boolean isConnected,
            boolean hasLargeHubs,
            int currentElementSpacingPx,
            int currentGroupSpacingPx,
            int elementTargetSpacingPx,
            int groupTargetSpacingPx,
            int proposedElementDelta,
            int proposedGroupDelta,
            int interElementDelta,
            int interGroupDelta,
            boolean elementKneeClampApplied,
            boolean groupKneeClampApplied,
            String noChangeReason,
            Integer elementTargetOverride,
            Integer groupTargetOverride,
            AssessLayoutResultDto before,
            AssessLayoutResultDto after,
            AdjustViewSpacingResultDto adjustResult,
            String elementTerminationReason,
            int elementIterationCount,
            List<Integer> elementAppliedDeltas,
            String groupTerminationReason,
            int groupIterationCount,
            List<Integer> groupAppliedDeltas) {
        this(viewId, scope, dryRun, connectionCount, interGroupConnectionCount,
                isConnected, hasLargeHubs, currentElementSpacingPx,
                currentGroupSpacingPx, elementTargetSpacingPx,
                groupTargetSpacingPx, proposedElementDelta, proposedGroupDelta,
                interElementDelta, interGroupDelta, elementKneeClampApplied,
                groupKneeClampApplied, noChangeReason, elementTargetOverride,
                groupTargetOverride, before, after, adjustResult,
                elementTerminationReason, elementIterationCount,
                elementAppliedDeltas, groupTerminationReason,
                groupIterationCount, groupAppliedDeltas,
                /*elementDensityFloorDiagnosis=*/ null,
                /*groupDensityFloorDiagnosis=*/ null);
    }

    /**
     * Backwards-compatible 23-arg constructor. Pre-Task-3 callers construct without the six
     * per-arm control-loop fields; delegating constructor populates with
     * neutral defaults ({@code null / 0 / List.of()} for both arms). The
     * Task 3 accessor refactor will switch to the canonical 29-arg form.
     */
    public ApplySpacingRecommendationsResultDto(
            String viewId,
            String scope,
            boolean dryRun,
            int connectionCount,
            int interGroupConnectionCount,
            boolean isConnected,
            boolean hasLargeHubs,
            int currentElementSpacingPx,
            int currentGroupSpacingPx,
            int elementTargetSpacingPx,
            int groupTargetSpacingPx,
            int proposedElementDelta,
            int proposedGroupDelta,
            int interElementDelta,
            int interGroupDelta,
            boolean elementKneeClampApplied,
            boolean groupKneeClampApplied,
            String noChangeReason,
            Integer elementTargetOverride,
            Integer groupTargetOverride,
            AssessLayoutResultDto before,
            AssessLayoutResultDto after,
            AdjustViewSpacingResultDto adjustResult) {
        this(viewId, scope, dryRun, connectionCount, interGroupConnectionCount,
                isConnected, hasLargeHubs, currentElementSpacingPx,
                currentGroupSpacingPx, elementTargetSpacingPx,
                groupTargetSpacingPx, proposedElementDelta, proposedGroupDelta,
                interElementDelta, interGroupDelta, elementKneeClampApplied,
                groupKneeClampApplied, noChangeReason, elementTargetOverride,
                groupTargetOverride, before, after, adjustResult,
                /*elementTerminationReason=*/ null,
                /*elementIterationCount=*/ 0,
                /*elementAppliedDeltas=*/ List.of(),
                /*groupTerminationReason=*/ null,
                /*groupIterationCount=*/ 0,
                /*groupAppliedDeltas=*/ List.of());
    }
}
