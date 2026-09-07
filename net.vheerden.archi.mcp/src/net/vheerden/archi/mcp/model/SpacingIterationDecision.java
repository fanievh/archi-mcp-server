package net.vheerden.archi.mcp.model;

/**
 * Pure-EMF-free decision for ONE iteration step of
 * {@link SpacingControlLoop#iterate}. Sibling-symmetric with the existing
 * single-shot decision records ({@link ApplyElementSpacingDecision} /
 * {@link ApplyGroupSpacingDecision} / {@link ApplySpacingDecision}) — same
 * architectural-separation rule (decision logic lives outside the EMF-bound
 * accessor for pure-unit testability).
 *
 * <p><strong>SIBLING-REPLACE:</strong> the three single-shot decision records
 * remain UNCHANGED + continue to act as the ENTRY GUARD for the control loop.
 * This record carries the NEW per-iteration-step decision semantics; the three
 * semantic mismatches with the single-shot role are cardinality /
 * input-dimensions / output-semantics.</p>
 *
 * <p><strong>Ladder design:</strong> monotone +10/step
 * (30, 40, 50, 60, 70, …) starting at iteration 0. Rationale: manual
 * agents iterated 30-60-80-100-120px deltas with {@code assess-layout}
 * observation between steps. The ladder starts small to maximize observation
 * density on the inflation knee's lower-velocity regime. Three alternative
 * ladders (constant / geometric / halving-toward-target) were considered and
 * rejected.</p>
 *
 * <p><strong>Per-iteration step cap</strong> (composer-only):
 * when called from the composer path, the cap is
 * {@link ApplySpacingDecision#ELEMENT_KNEE_LIMIT_PX} (80) or
 * {@link ApplySpacingDecision#GROUP_KNEE_LIMIT_PX} (100); the constants
 * (originally per-call clamp limits) are re-purposed as per-iteration step
 * caps. Sibling tools pass
 * {@link Integer#MAX_VALUE} (no cap; the heuristic target is the upper
 * bound).</p>
 *
 * @param nextStepDelta   the proposed delta in pixels for THIS iteration step
 *                        (NOT cumulative; the loop adds this to the running
 *                        current-spacing baseline). 0 when the ladder is
 *                        exhausted (target reached or per-iteration cap hits
 *                        zero).
 * @param keepGoing       true when the loop should proceed with this delta;
 *                        false when the ladder is exhausted (current spacing
 *                        already meets or exceeds the target).
 * @param backOffReason   reserved for future use when this record carries a
 *                        decision-time back-off signal (e.g., proactive halt
 *                        before observation); currently always null because
 *                        the back-off predicate runs in
 *                        {@link SpacingControlLoop} POST-observation, not at
 *                        decision time. Kept in the record shape for forward-
 *                        compatibility.
 */
public record SpacingIterationDecision(
        int nextStepDelta,
        boolean keepGoing,
        String backOffReason) {

    /**
     * Computes the next-step decision from the iteration index + the
     * current/target spacing baselines.
     *
     * <p>Ladder semantics:
     * <pre>
     *   remainingToTarget = targetSpacingPx - currentSpacingPx
     *                       (always &gt;= 0 because the loop short-circuits
     *                       upstream when &lt;= 0 via the heuristic-already-met
     *                       branch of the existing decision-record entry
     *                       guard)
     *   ladderDelta       = 30 + stepIndex * 10
     *   nextStepDelta     = min(ladderDelta, remainingToTarget, perIterationStepCapPx)
     * </pre></p>
     *
     * <p>When {@code currentSpacingPx &gt;= targetSpacingPx}, returns
     * {@code (0, false, null)} — the loop treats this as ladder-exhausted +
     * terminates with the appropriate {@code terminationReason}.
     * When {@code perIterationStepCapPx &lt;= 0}, returns
     * {@code (0, false, null)} (degenerate cap).</p>
     *
     * @param stepIndex             0-based iteration index within the loop
     * @param currentSpacingPx      spacing baseline BEFORE this iteration's
     *                              Apply (cumulative state of prior accepted
     *                              iterations + the loop's initial baseline)
     * @param targetSpacingPx       full-target upper bound (typically
     *                              {@code initialSpacingPx +
     *                              originalDecision.delta} where
     *                              {@code originalDecision} is the entry-guard
     *                              decision-record value)
     * @param perIterationStepCapPx per-iteration step cap; pass
     *                              {@link Integer#MAX_VALUE} for siblings
     *                              (no cap), {@link ApplySpacingDecision#ELEMENT_KNEE_LIMIT_PX}
     *                              or {@link ApplySpacingDecision#GROUP_KNEE_LIMIT_PX}
     *                              for composer paths
     * @return the iteration-step decision
     */
    public static SpacingIterationDecision decideNextStep(
            int stepIndex,
            int currentSpacingPx,
            int targetSpacingPx,
            int perIterationStepCapPx) {

        int remainingToTarget = targetSpacingPx - currentSpacingPx;
        if (remainingToTarget <= 0) {
            return new SpacingIterationDecision(0, false, null);
        }
        if (perIterationStepCapPx <= 0) {
            return new SpacingIterationDecision(0, false, null);
        }
        int ladderDelta = 30 + stepIndex * 10;
        int proposedDelta = Math.min(
                Math.min(ladderDelta, remainingToTarget),
                perIterationStepCapPx);
        if (proposedDelta <= 0) {
            return new SpacingIterationDecision(0, false, null);
        }
        return new SpacingIterationDecision(proposedDelta, true, null);
    }
}
