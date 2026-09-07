package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;
import net.vheerden.archi.mcp.response.dto.AutoLayoutAndRouteResultDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;

/**
 * The vocabulary and the decision table behind {@code auto-layout-and-route}'s quality-target loop.
 *
 * <h2>Why a termination reason exists beside {@code limitingFactor}</h2>
 *
 * <p>The loop has five structurally distinct exits, and two of them demand opposite behaviour from
 * the caller. A run that stopped because the worst metric is one no further layout iteration can
 * move has <em>proved</em> that lever useless; a run that stopped because it used up its iteration
 * budget was merely cut short and may well converge if run again. Both carry a
 * {@code limitingFactor}, and it can be the same factor in each case.</p>
 *
 * <p>So {@code limitingFactor} answers <em>what is the worst metric right now</em>, and
 * {@code terminationReason} answers <em>is there any point trying again</em>. Neither substitutes
 * for the other. Before the reason was reported, the decision lived only in a server log line the
 * caller cannot read, and the response's advice pointed at the very lever the loop had ruled out.
 * The caller is an agent that cannot see the canvas, so the response is its only ground truth.</p>
 *
 * <h2>Vocabulary</h2>
 *
 * <p>The tokens deliberately reuse those already published by {@link SpacingControlLoop} — the
 * project's other control loop — so an agent that has learned one can read the other without a
 * second lookup table. Two of them are literally the same constants.</p>
 *
 * <h2>One source for "would more spacing help?"</h2>
 *
 * <p>This class also owns the per-iteration remediation dispatch that both quality loops run on.
 * The response's advice is derived from that same table rather than restating it, so the advice and
 * the loop cannot drift apart: a factor the loop routes to a spacing increase is exactly a factor
 * the response may suggest raising spacing for.</p>
 */
public final class QualityTargetTermination {

    private QualityTargetTermination() {
    }

    // ------------------------------------------------------------------
    // Termination-reason taxonomy
    // ------------------------------------------------------------------

    /**
     * Exit 2 prefix — the target rating was reached on iteration N. Same token as
     * {@link SpacingControlLoop#REASON_GOAL_REACHED_PREFIX}.
     */
    public static final String REASON_GOAL_REACHED_PREFIX = "goal_reached_at_iteration_";

    /**
     * Exit 1 prefix — the worst metric is one no further layout iteration can move, so the loop
     * stopped before spending another one. The factor is interpolated onto the end, which makes the
     * single token self-sufficient for a caller that reads nothing else.
     */
    public static final String REASON_LIMITING_FACTOR_NOT_REMEDIABLE_PREFIX =
            "limiting_factor_not_remediable_";

    /** Exit 3 prefix — every measured metric passed on iteration N; nothing further is measurable. */
    public static final String REASON_ALL_METRICS_PASS_PREFIX = "all_metrics_pass_at_iteration_";

    /**
     * Exit 4 prefix — successive iterations stopped moving the dominant factor. The lever is
     * exhausted; the view's structure has to change, not the parameters.
     */
    public static final String REASON_PLATEAU_PREFIX = "plateau_at_iteration_";

    /**
     * Exit 5 prefix — the whole iteration budget was consumed. Unlike every other exit this one was
     * cut short rather than concluded, so a further run may still improve the result — <em>unless</em>
     * the run also regressed the view, in which case another run is an invitation to dig the same
     * hole deeper and {@link #describe(String, boolean)} says so instead. Same token as
     * {@link SpacingControlLoop#REASON_BUDGET_EXHAUSTED_PREFIX}.
     */
    public static final String REASON_BUDGET_EXHAUSTED_PREFIX = "budget_exhausted_after_";

    /** Exit 5 suffix. Same token as {@link SpacingControlLoop#REASON_BUDGET_EXHAUSTED_SUFFIX}. */
    public static final String REASON_BUDGET_EXHAUSTED_SUFFIX = "_iterations";

    /**
     * Post-loop amendment — the label-optimization fallback runs <em>after</em> the loop and
     * overwrites the best rating on success. A reason captured at the break can therefore be
     * falsified before it is serialized: a loop that stopped because label overlaps looked
     * irremediable, and was then rescued by the fallback, would otherwise report that stop beside a
     * rating that meets the target. See {@link #amendAfterLabelFallback}.
     */
    public static final String REASON_GOAL_REACHED_AFTER_LABEL_FALLBACK =
            "goal_reached_after_label_fallback";

    /** Exit 2 — the target rating was reached on the given (1-based) iteration. */
    public static String goalReachedAtIteration(int iteration) {
        return REASON_GOAL_REACHED_PREFIX + iteration;
    }

    /** Exit 1 — no further iteration can move this factor, so the loop stopped spending them. */
    public static String limitingFactorNotRemediable(String limitingFactor) {
        return REASON_LIMITING_FACTOR_NOT_REMEDIABLE_PREFIX + limitingFactor;
    }

    /** Exit 3 — every measured metric passed on the given (1-based) iteration. */
    public static String allMetricsPassAtIteration(int iteration) {
        return REASON_ALL_METRICS_PASS_PREFIX + iteration;
    }

    /** Exit 4 — the dominant factor stopped improving at the given (1-based) iteration. */
    public static String plateauAtIteration(int iteration) {
        return REASON_PLATEAU_PREFIX + iteration;
    }

    /** Exit 5 — the loop ran its whole budget of iterations without concluding. */
    public static String budgetExhaustedAfter(int iterations) {
        return REASON_BUDGET_EXHAUSTED_PREFIX + iterations + REASON_BUDGET_EXHAUSTED_SUFFIX;
    }

    /**
     * Post-loop amendment — an attempt reached the target, but it also regressed a metric of higher
     * priority than the one it improved, so it was discarded in favour of an earlier attempt and is
     * <em>not</em> the result being reported.
     *
     * <p>This is a real state, not a defensive branch. The loop breaks on the rating of the attempt
     * it has just measured, while the response reports the best attempt <em>kept</em>, and those two
     * part company whenever the higher-priority veto fires: a metric whose rating band tolerates a
     * small non-zero count can grow while the overall rating improves, which trips the veto without
     * capping the rating. Reporting "the target was reached" beside a rating that misses it would be
     * self-contradicting on the wire, so the reason names what actually happened.</p>
     */
    public static final String REASON_TARGET_MET_BUT_ATTEMPT_REGRESSED_PREFIX =
            "target_met_but_attempt_regressed_at_iteration_";

    /** The attempt that reached the target regressed a higher-priority metric and was discarded. */
    public static String targetMetButAttemptRegressed(int iteration) {
        return REASON_TARGET_MET_BUT_ATTEMPT_REGRESSED_PREFIX + iteration;
    }

    /**
     * Reconciles a reason captured at a {@code break} against the state that is actually reported.
     *
     * <p>Two things can falsify a reason after the loop has recorded it, and both are handled here
     * so a caller never sees a stop that contradicts the rating beside it.</p>
     *
     * <ol>
     * <li><strong>The label-optimization fallback runs after the loop and overwrites the best
     * rating.</strong> Keyed on the observable that discriminates — whether the target went from
     * missed to met — rather than on which exit was taken, because the fallback triggers on the
     * label-overlap sub-rating and can rescue a run that stopped for any reason.</li>
     * <li><strong>The attempt that met the target may never have become the reported result</strong>
     * — see {@link #REASON_TARGET_MET_BUT_ATTEMPT_REGRESSED_PREFIX}. Any claim that the goal was
     * reached is therefore checked against the rating actually being reported, and downgraded to the
     * truth when the two disagree.</li>
     * </ol>
     *
     * <p>Order matters: a fallback rescue is applied first, so a genuinely rescued run keeps its
     * success reason instead of being downgraded.</p>
     */
    public static String reconcileAfterLoop(
            String reason, boolean targetMetBefore, boolean targetMetAfter) {
        if (!targetMetBefore && targetMetAfter) {
            return REASON_GOAL_REACHED_AFTER_LABEL_FALLBACK;
        }
        if (!targetMetAfter && reason != null
                && reason.startsWith(REASON_GOAL_REACHED_PREFIX)) {
            return targetMetButAttemptRegressed(Integer.parseInt(
                    reason.substring(REASON_GOAL_REACHED_PREFIX.length())));
        }
        return reason;
    }

    /**
     * Renders a reason as a sentence a language model reads without consulting a table.
     *
     * <p>The raw token is machine-stable and is what travels in {@code terminationReason}; this is
     * what the response's guidance says out loud. The wording for a non-remediable stop deliberately
     * avoids naming spacing at all — the whole point of that exit is that the spacing lever was
     * measured and ruled out.</p>
     *
     * @return null when handed null; a readable sentence otherwise, including for a token this
     *         version does not recognise
     */
    public static String describe(String terminationReason) {
        return describe(terminationReason, false);
    }

    /**
     * As {@link #describe(String)}, but told whether the run left the view worse than it found it.
     *
     * <p>Exactly one exit's advice turns on that. A budget-exhausted run was cut short rather than
     * concluded, so "a further run may still improve the result" is sound — for a run that merely
     * failed to <em>improve</em>. Said to a run that has just measured itself going
     * <em>backwards</em>, it invites the caller to spend another whole budget digging the same hole,
     * and it says it beside a result that is worse than the one the caller started with. Every other
     * exit already tells the caller not to re-run, or that it succeeded, so the flag must leave them
     * untouched — the defect was the advice, not the taxonomy.</p>
     *
     * @param regressed true when the committed state is worse than the pre-call state
     */
    public static String describe(String terminationReason, boolean regressed) {
        if (terminationReason == null) {
            return null;
        }
        if (REASON_GOAL_REACHED_AFTER_LABEL_FALLBACK.equals(terminationReason)) {
            return "The quality loop stopped once the label-optimization fallback lifted the view "
                    + "to the target rating.";
        }
        if (terminationReason.startsWith(REASON_LIMITING_FACTOR_NOT_REMEDIABLE_PREFIX)) {
            String factor = terminationReason.substring(
                    REASON_LIMITING_FACTOR_NOT_REMEDIABLE_PREFIX.length());
            return "The quality loop stopped early because no further layout iteration can move '"
                    + factor + "' — that lever was measured and ruled out, not left untried, so act "
                    + "on the remedy below instead of re-running with different parameters.";
        }
        if (terminationReason.startsWith(REASON_GOAL_REACHED_PREFIX)) {
            return "The quality loop stopped because the target rating was reached on iteration "
                    + terminationReason.substring(REASON_GOAL_REACHED_PREFIX.length()) + ".";
        }
        if (terminationReason.startsWith(REASON_TARGET_MET_BUT_ATTEMPT_REGRESSED_PREFIX)) {
            return "The quality loop stopped on iteration "
                    + terminationReason.substring(
                            REASON_TARGET_MET_BUT_ATTEMPT_REGRESSED_PREFIX.length())
                    + " because an attempt reached the target rating — but that attempt also made a "
                    + "higher-priority metric worse, so it was discarded and the earlier result "
                    + "reported here is the better one overall. Re-running is likely to hit the same "
                    + "trade-off; fix the metric named below first.";
        }
        if (terminationReason.startsWith(REASON_ALL_METRICS_PASS_PREFIX)) {
            return "The quality loop stopped on iteration "
                    + terminationReason.substring(REASON_ALL_METRICS_PASS_PREFIX.length())
                    + " because every measured metric passed — nothing further is measurable.";
        }
        if (terminationReason.startsWith(REASON_PLATEAU_PREFIX)) {
            return "The quality loop stopped on iteration "
                    + terminationReason.substring(REASON_PLATEAU_PREFIX.length())
                    + " because successive attempts stopped improving the dominant metric — that "
                    + "lever is exhausted, so change the view's structure rather than its "
                    + "parameters.";
        }
        if (terminationReason.startsWith(REASON_BUDGET_EXHAUSTED_PREFIX)
                && terminationReason.endsWith(REASON_BUDGET_EXHAUSTED_SUFFIX)) {
            String iterations = terminationReason.substring(
                    REASON_BUDGET_EXHAUSTED_PREFIX.length(),
                    terminationReason.length() - REASON_BUDGET_EXHAUSTED_SUFFIX.length());
            if (regressed) {
                return "The quality loop used its whole budget of " + iterations + " iteration(s) "
                        + "and the state it committed is worse than the one it was handed — so do "
                        + "not simply re-run it. Undo first, then change the view's structure or "
                        + "the parameters before trying again.";
            }
            return "The quality loop used its whole budget of " + iterations + " iteration(s) "
                    + "without concluding — unlike every other stop this one was cut short, so a "
                    + "further run may still improve the result.";
        }
        return "The quality loop reported termination reason '" + terminationReason + "'.";
    }

    // ------------------------------------------------------------------
    // Per-iteration remediation dispatch (both loops run on this)
    // ------------------------------------------------------------------

    private static final String MODE_GROUPED = "grouped";
    private static final String SPACING_INCREASE_SUFFIX = "spacing-increase";

    /**
     * Picks the remediation an iteration should attempt, from the worst metric the previous
     * iteration measured.
     *
     * <p>The first iteration and any iteration with no measured limiting factor run the full
     * pipeline. Afterwards the factor selects a targeted lever. The two modes differ on exactly one
     * factor: flat mode has no separate crossing remediation because its layout algorithm does its
     * own crossing reduction, so crossings there are answered with more spacing; grouped mode
     * reorders its groups instead.</p>
     *
     * @param mode           {@code "grouped"} or anything else for flat
     * @param iterationIndex 0-based iteration counter
     * @param limitingFactor worst metric from the previous assessment, or null on the first pass
     */
    public static String remediationTypeFor(
            String mode, int iterationIndex, String limitingFactor) {
        if (iterationIndex == 0 || limitingFactor == null) {
            return "full-pipeline";
        }
        if (MODE_GROUPED.equals(mode)) {
            return switch (limitingFactor) {
                case "overlaps", "spacing", "alignment" -> "spacing-increase";
                case "edgeCrossings" -> "reorder-and-reroute";
                case "passThroughs", "coincidentSegments" -> "reroute-only";
                case "labelOverlaps" -> "early-exit-label";
                case "nonOrthogonalTerminals" -> "early-exit-nonorth";
                default -> "spacing-increase";
            };
        }
        return switch (limitingFactor) {
            case "overlaps", "edgeCrossings", "spacing", "alignment" -> "elk-spacing-increase";
            case "passThroughs", "coincidentSegments" -> "reroute-only";
            case "labelOverlaps" -> "early-exit-label";
            case "nonOrthogonalTerminals" -> "early-exit-nonorth";
            default -> "elk-spacing-increase";
        };
    }

    /** True for the dispatch types that mean "another iteration cannot help — stop now". */
    public static boolean isEarlyExit(String remediationType) {
        return "early-exit-label".equals(remediationType)
                || "early-exit-nonorth".equals(remediationType);
    }

    /**
     * True when raising the spacing parameter is a remedy the loop itself would attempt for this
     * factor. Read straight off {@link #remediationTypeFor} rather than restated, so guidance
     * offered to the caller cannot contradict what the loop actually does.
     */
    public static boolean spacingCanHelp(String mode, String limitingFactor) {
        if (limitingFactor == null) {
            return false;
        }
        return remediationTypeFor(mode, 1, limitingFactor).endsWith(SPACING_INCREASE_SUFFIX);
    }

    // ------------------------------------------------------------------
    // Remediation text
    // ------------------------------------------------------------------

    /**
     * Maps a limiting factor to an actionable remediation string. Covers every key
     * mapped in {@code getMetricCount} except the count-less {@code spacing} +
     * {@code alignment} (which have their own remediation strings).
     */
    public static String getRemediation(String limitingFactor) {
        return switch (limitingFactor) {
            case "labelOverlaps" -> "Re-run auto-route-connections with labelPolicy=auto-hide-on-collision "
                    + "to hide only labels with no collision-free position (each listed by ID in hiddenLabels, "
                    + "reversible via update-view-connection showLabel=true), or set labelPosition by hand";
            case "overlaps" -> "Increase spacing parameter or use layout-within-group "
                    + "to reposition overlapping elements";
            case "edgeCrossings" -> "Run optimize-group-order to reduce inter-group crossings, "
                    + "or reposition hub elements manually";
            case "passThroughs" -> "Reposition elements that connections pass through, "
                    + "or increase spacing to create routing corridors";
            case "spacing" -> "Increase the spacing parameter "
                    + "(current spacing may be too tight for element count)";
            case "alignment" -> "Use layout-within-group with consistent arrangement "
                    + "to improve alignment within groups";
            case "boundaryViolations" -> "Move children outside parent group bounds back inside, "
                    + "or grow the parent group via auto-size";
            case "parentLabelObscured" -> "Move overlapping children away from the parent group's "
                    + "name area, or grow the parent group via auto-size";
            case "offCanvas" -> "Reposition off-canvas elements onto the visible canvas via "
                    + "update-element-position, or run clean-canvas to recompute view bounds";
            case "labelTruncations" -> "Use update-view-connection to set labelPosition "
                    + "(source/middle/target) on truncated labels, or shorten the label text";
            case "interiorTerminations" -> "Re-run auto-route-connections after element repositioning — "
                    + "interior terminations indicate stored bendpoints inside element bounds, "
                    + "requiring face-aware re-routing";
            case "zigzags" -> "Re-run auto-route-connections with a higher iteration target — "
                    + "zigzag patterns indicate PathStraightener.eliminateReversals did not converge "
                    + "for this connection";
            case "connectionEdgeCoincidence" -> "Reposition the coincident-aligned element via "
                    + "update-element-position to break the parallel alignment, or re-run "
                    + "auto-route-connections to choose a different corridor";
            case "hubPortQuality" -> "Run auto-route-connections with port-distribution enabled — "
                    + "multiple connections share a single port slot on a hub face";
            case "coincidentSegments" -> "Re-route the affected connections via auto-route-connections "
                    + "to choose distinct corridors, or reposition source/target elements to break "
                    + "the parallel alignment";
            case "nonOrthogonalTerminals" -> "Re-run auto-route-connections after element repositioning, "
                    + "or use update-element-position to adjust source/target positions so terminal "
                    + "segments approach element edges orthogonally";
            default -> "Review the assess-layout ratingBreakdown for details";
        };
    }

    // ------------------------------------------------------------------
    // Did this call leave the view worse than it found it?
    //
    // The comparison itself lives on RatingRegressionDisclosure, which every tool that commits a
    // state it also measured shares. What stays here is this tool's half of it: its name in the
    // sentence, its remedy's cost, and its warning code.
    // ------------------------------------------------------------------

    /** The subject of the disclosure sentence: the tool the caller invoked. */
    private static final String AUTO_LAYOUT_SUBJECT = "auto-layout-and-route";

    /**
     * The tail of the disclosure sentence: that the state was committed regardless, and what
     * recovery costs. One call, because the winning attempt is merged into a single compound
     * command before dispatch — a caller who does not know that may not attempt the undo at all.
     */
    private static final String AUTO_LAYOUT_APPLIED_TAIL =
            "The layout and routes were applied anyway — undo restores the previous state, "
                    + "and it is a single call because the winning attempt is committed as one "
                    + "compound operation.";

    /**
     * True when {@code after} is worse than {@code before} on the same composite the quality loops
     * rank their own attempts by.
     *
     * @see RatingRegressionDisclosure#hasRegressed
     */
    public static boolean hasRatingRegressed(
            AssessLayoutResultDto before, AssessLayoutResultDto after) {
        return RatingRegressionDisclosure.hasRegressed(before, after);
    }

    /**
     * The rating-regression disclosure for a completed quality-target loop: a single-entry list
     * when the committed state is worse than the state the loop was handed, empty otherwise.
     *
     * @see RatingRegressionDisclosure#warnings
     */
    public static List<StructuredWarningDto> ratingRegressionWarnings(
            AssessLayoutResultDto before, AssessLayoutResultDto after) {
        return RatingRegressionDisclosure.warnings(
                StructuredWarningCodes.AUTO_LAYOUT_RATING_REGRESSED,
                AUTO_LAYOUT_SUBJECT, AUTO_LAYOUT_APPLIED_TAIL, before, after);
    }

    /**
     * The tail for the queued arm: nothing has been applied, and the remedy is the batch's.
     *
     * <p>The applied tail is stamped on before the facade knows its arm — {@code
     * buildQualityTargetDto} runs ahead of both the approval gate and {@code dispatchOrQueue} — so
     * a queued run carried "the layout and routes were applied anyway" beside "mutation queued as
     * operation #1". An agent obeying that reverts whatever command actually sits on the stack, or
     * on a clean stack reverts nothing and then re-reads a still-degraded view.</p>
     *
     * <p>The measurement is not withdrawn with the remedy. Unlike the spacing family, whose
     * {@code after} snapshot re-reads an unmutated view when queued and which therefore abstains
     * outright, this loop measures {@code bestAssessment} inside its temporary dispatch window: the
     * regression is genuinely measured here, so suppressing the disclosure would delete a fact that
     * was legitimately established.</p>
     */
    private static final String AUTO_LAYOUT_QUEUED_TAIL =
            "Nothing has been applied: this run is queued in the open batch, so undo is not the "
                    + "remedy here and would revert whichever command is actually on top of the "
                    + "stack. Discard the queued run with end-batch rollback:true, or commit it "
                    + "with end-batch and re-run assess-layout to see what landed.";

    /**
     * The tail for the awaiting-approval arm, where the reader is the human holding the card.
     *
     * <p>Named separately from {@link #AUTO_LAYOUT_QUEUED_TAIL} rather than folded into one
     * deferred tail: the recovery genuinely differs. A queued run is discarded with a tool the
     * agent can call; a proposal is rejected by the person reading the card, and no MCP tool the
     * agent holds is the remedy — which is why this arm names none.</p>
     */
    private static final String AUTO_LAYOUT_AWAITING_APPROVAL_TAIL =
            "Nothing has been applied: this run is waiting on the human's approval, so undo is not "
                    + "the remedy here and would revert whichever command is actually on top of "
                    + "the stack. Reject the proposal to discard it, or approve it and re-run "
                    + "assess-layout to see what landed.";

    /** The recovery a queued caller can actually invoke. */
    private static final String QUEUED_REMEDIATION_TOOL = "end-batch";

    /**
     * No MCP tool recovers an awaiting-approval run — the human decides — so the field is dropped
     * rather than filled with one that does not help.
     */
    private static final String NO_REMEDIATION_TOOL = "";

    /**
     * Writes the rating disclosure onto an approval card's {@code proposedChanges} map.
     *
     * @see RatingRegressionDisclosure#putDisclosure
     */
    public static void putRatingDisclosure(Map<String, Object> proposedChanges,
            String ratingBefore, List<StructuredWarningDto> structuredWarnings) {
        RatingRegressionDisclosure.putDisclosure(proposedChanges,
                StructuredWarningCodes.AUTO_LAYOUT_RATING_REGRESSED,
                ratingBefore, structuredWarnings);
    }

    /**
     * Writes the rating disclosure onto an approval card, with the remedy the card's reader has.
     *
     * <p>The card is shown to a human <em>before</em> they approve. Carrying the applied tail there
     * describes a change that has not happened and prescribes {@code undo} for it. Both quality
     * loops build their card from the same pre-arm DTO, so both call this.</p>
     *
     * <p>Recomposed from the two assessments rather than edited out of the composed string: the
     * evidence half then comes from the same code on every arm and cannot drift from it.</p>
     */
    public static void putProposedRatingDisclosure(Map<String, Object> proposedChanges,
            String ratingBefore, AssessLayoutResultDto before, AssessLayoutResultDto after) {
        RatingRegressionDisclosure.putDisclosure(proposedChanges,
                StructuredWarningCodes.AUTO_LAYOUT_RATING_REGRESSED, ratingBefore,
                RatingRegressionDisclosure.warnings(
                        StructuredWarningCodes.AUTO_LAYOUT_RATING_REGRESSED, AUTO_LAYOUT_SUBJECT,
                        AUTO_LAYOUT_AWAITING_APPROVAL_TAIL, NO_REMEDIATION_TOOL, before, after));
    }

    /**
     * The result DTO with its regression remedy rescoped to the queued arm, or unchanged when the
     * call was dispatched immediately.
     *
     * <p>{@code batchSequenceNumber} is the arm: {@code null} means {@code dispatchOrQueue}
     * dispatched, which is the one arm on which the applied tail is true — and it is left exactly
     * as it was, byte for byte, because it is the common case and the disclosure earns its keep
     * there.</p>
     */
    public static AutoLayoutAndRouteResultDto rescopeIfQueued(AutoLayoutAndRouteResultDto dto,
            Integer batchSequenceNumber, AssessLayoutResultDto before, AssessLayoutResultDto after) {
        if (batchSequenceNumber == null) {
            return dto;
        }
        return rescope(dto, AUTO_LAYOUT_QUEUED_TAIL, QUEUED_REMEDIATION_TOOL, before, after);
    }

    /** The result DTO with its regression remedy rescoped to the awaiting-approval arm. */
    public static AutoLayoutAndRouteResultDto rescopeAsProposed(AutoLayoutAndRouteResultDto dto,
            AssessLayoutResultDto before, AssessLayoutResultDto after) {
        return rescope(dto, AUTO_LAYOUT_AWAITING_APPROVAL_TAIL, NO_REMEDIATION_TOOL, before, after);
    }

    /**
     * Swaps the rating-regression entry for one carrying {@code tail} and {@code remediationTool},
     * leaving every other warning where it was.
     *
     * <p>Replaces in place so position is preserved, and appends when no entry carried the code —
     * a rescope must never be able to end with fewer disclosures than it started with.</p>
     */
    private static AutoLayoutAndRouteResultDto rescope(AutoLayoutAndRouteResultDto dto,
            String tail, String remediationTool,
            AssessLayoutResultDto before, AssessLayoutResultDto after) {
        List<StructuredWarningDto> replacement = RatingRegressionDisclosure.warnings(
                StructuredWarningCodes.AUTO_LAYOUT_RATING_REGRESSED, AUTO_LAYOUT_SUBJECT,
                tail, remediationTool, before, after);
        if (replacement.isEmpty()) {
            // Nothing regressed on the pair the caller vouched for, so the improved-run contract
            // is no entry at all — and today that is also what the DTO carries, because both call
            // sites hand this method the very pair that built it, four lines earlier.
            //
            // It is not enough to rely on that. If the two ever disagree — a fallback reassigning
            // bestAssessment between the build and the return, a third call site added later —
            // returning `dto` untouched would re-ship the APPLIED tail, with remediationTool
            // "undo", on an arm where nothing has been applied. That is precisely the defect this
            // method exists to remove, reintroduced silently and on the exact path that cannot
            // afford it. So drop the contradicted entry rather than pass it through: on a deferred
            // arm the fail-safe direction is to say nothing, never to claim a mutation happened.
            return dropRatingRegression(dto);
        }
        List<StructuredWarningDto> rescoped = new ArrayList<>();
        boolean swapped = false;
        for (StructuredWarningDto warning : dto.structuredWarnings() == null
                ? List.<StructuredWarningDto>of() : dto.structuredWarnings()) {
            boolean isRating = StructuredWarningCodes.AUTO_LAYOUT_RATING_REGRESSED
                    .equals(warning.code());
            rescoped.add(isRating ? replacement.get(0) : warning);
            swapped |= isRating;
        }
        if (!swapped) {
            rescoped.addAll(replacement);
        }
        return dto.withStructuredWarnings(List.copyOf(rescoped));
    }

    /**
     * The DTO with any rating-regression entry removed, used only when the assessments handed to a
     * rescope contradict the one the DTO already carries.
     *
     * <p>Returns the DTO itself when there is nothing to drop, so the common path allocates
     * nothing and the improved-run contract — no entry, no empty list where none existed — is
     * preserved exactly.</p>
     */
    private static AutoLayoutAndRouteResultDto dropRatingRegression(
            AutoLayoutAndRouteResultDto dto) {
        List<StructuredWarningDto> warnings = dto.structuredWarnings();
        if (warnings == null || warnings.stream().noneMatch(
                w -> StructuredWarningCodes.AUTO_LAYOUT_RATING_REGRESSED.equals(w.code()))) {
            return dto;
        }
        return dto.withStructuredWarnings(warnings.stream()
                .filter(w -> !StructuredWarningCodes.AUTO_LAYOUT_RATING_REGRESSED.equals(w.code()))
                .toList());
    }

    // ------------------------------------------------------------------
    // Assessment readers the quality loops rank by
    // ------------------------------------------------------------------

    /**
     * Finds the worst-performing metric from the rating breakdown.
     * Skips "overall" and "pass" ratings. Tie-breaks by count from the assessment.
     * When counts are equal (e.g. spacing and alignment both have count 0),
     * the first metric in iteration order wins (LinkedHashMap from LayoutQualityAssessor).
     */
    public static String findLimitingFactor(AssessLayoutResultDto assessment) {
        Map<String, String> breakdown = assessment.ratingBreakdown();
        String worstMetric = null;
        int worstOrdinal = Integer.MAX_VALUE;
        int worstCount = -1;

        for (Map.Entry<String, String> entry : breakdown.entrySet()) {
            String metric = entry.getKey();
            String rating = entry.getValue();

            // Skip the aggregate "overall" entry and "pass" (not-applicable) metrics
            if ("overall".equals(metric) || "pass".equals(rating)) {
                continue;
            }

            int ordinal = LayoutQualityAssessor.ratingOrdinal(rating);
            int count = getMetricCount(metric, assessment);

            if (ordinal < worstOrdinal || (ordinal == worstOrdinal && count > worstCount)) {
                worstOrdinal = ordinal;
                worstCount = count;
                worstMetric = metric;
            }
        }
        return worstMetric;
    }

    /**
     * Computes a tier-weighted quality score reflecting the M6 two-dimensional severity
     * hierarchy (layout-tier × routing-tier — see {@code LayoutQualityAssessor.computeLayoutTier}
     * + {@code computeRoutingTier}). Lower is better.
     * <p>Layout tier 1L: overlaps ×10, boundaryViolations ×10, parentLabelObscured ×6.
     * Routing tier 1R: passThroughs ×8, interiorTerminations ×8, zigzags ×8, coincidentSegments ×6.
     * <p>passThroughs and boundaryViolations are weighed on the CHARGED counts —
     * {@code crossElementPassThroughCount} and {@code boundaryViolationCount} — not on the
     * sizes of their description lists. Both lists are capped at ten entries, and the
     * pass-through list also names the self-element pass-throughs the rating charges at zero,
     * so ranking candidates on it both stops separating them past ten and spends eight points
     * an iteration on geometry none of the loop's levers can move.
     * Routing tier 2R: nonOrthogonalTerminals ×3, connectionEdgeCoincidence ×3,
     * hubPortQuality (binary &lt; FAIR threshold) ×2, labelOverlaps ×2, labelTruncations ×2.
     * Routing tier 3R: edgeCrossings ×1.
     * <p>Layout tier 2L (offCanvas, averageSpacing) and 3L (alignmentScore) do not contribute —
     * informational/cosmetic metrics covered by the rating ordinal at the iteration callsite.
     */
    public static int tierWeightedScore(AssessLayoutResultDto a) {
        int chargedPtCount = a.crossElementPassThroughCount();
        int boundaryCount = a.boundaryViolationCount();
        int hubPortLowQuality = a.hubPortQualityScore() < LayoutQualityAssessor.HUB_PORT_QUALITY_FAIR_THRESHOLD ? 1 : 0;
        return (a.overlapCount() * 10)
             + (boundaryCount * 10)
             + (a.parentLabelObscuredCount() * 6)
             + (chargedPtCount * 8)
             + (a.interiorTerminationCount() * 8)
             + (a.zigzagCount() * 8)
             + (a.coincidentSegmentCount() * 6)
             + (a.nonOrthogonalTerminalCount() * 3)
             + (a.connectionEdgeCoincidenceCount() * 3)
             + (hubPortLowQuality * 2)
             + (a.labelOverlapCount() * 2)
             + (a.labelTruncationCount() * 2)
             + (a.edgeCrossingCount() * 1);
    }

    /**
     * Returns true if the current assessment has regressed on any Tier-1 metric
     * compared to the best assessment. Any Tier-1 regression vetoes the iteration
     * regardless of improvements in lower tiers.
     * <p>Tier-1L (layout): overlaps, boundaryViolations, parentLabelObscured.
     * Tier-1R (routing): passThroughs, interiorTerminations, zigzags, coincidentSegments.
     * <p>The pass-through comparison reads the CHARGED count
     * ({@code crossElementPassThroughCount}), as the boundary-violation comparison beside it
     * reads {@code boundaryViolationCount}. Comparing description-list sizes instead would miss
     * every regression past the ten-entry cap and would veto a candidate whose only new
     * pass-throughs are self-element — unrated geometry that no further iteration could have
     * avoided.
     * Mirrors {@code LayoutQualityAssessor.computeLayoutTier}
     * /{@code computeRoutingTier} Tier-1 classification.
     */
    public static boolean hasTier1Regression(AssessLayoutResultDto current, AssessLayoutResultDto best) {
        if (best == null) {
            return false; // No baseline to regress against (first iteration)
        }
        int currentPt = current.crossElementPassThroughCount();
        int bestPt = best.crossElementPassThroughCount();
        int currentBoundary = current.boundaryViolationCount();
        int bestBoundary = best.boundaryViolationCount();
        return current.overlapCount() > best.overlapCount()
            || currentBoundary > bestBoundary
            || current.parentLabelObscuredCount() > best.parentLabelObscuredCount()
            || currentPt > bestPt
            || current.interiorTerminationCount() > best.interiorTerminationCount()
            || current.zigzagCount() > best.zigzagCount()
            || current.coincidentSegmentCount() > best.coincidentSegmentCount();
    }

    /**
     * Returns the count associated with a breakdown metric for tie-breaking in
     * {@code findLimitingFactor}. Maps each key emitted by
     * {@code LayoutQualityAssessor.computeRatingWithBreakdown} ({@code LayoutQualityAssessor.java:739-879})
     * to its assessment field accessor.
     * <p>Mapped keys (14): overlaps, edgeCrossings, labelOverlaps, passThroughs,
     * coincidentSegments, nonOrthogonalTerminals, boundaryViolations, parentLabelObscured,
     * offCanvas, labelTruncations, interiorTerminations, zigzags,
     * connectionEdgeCoincidence, hubPortQuality (binary low-quality flag).
     * <p>Default branch: {@code spacing} + {@code alignment} (informational with no integer count).
     * <p>Two keys map to a charged tally rather than to the same-named description list:
     * {@code passThroughs} reads {@code crossElementPassThroughCount} and
     * {@code boundaryViolations} reads {@code boundaryViolationCount}. Both lists are capped at
     * ten entries, and the pass-through list also names the self-element pass-throughs the rating
     * charges at zero — so a count taken from either list is neither the number the view was
     * marked down for nor a number that keeps growing past ten. This count is the tie-break in
     * {@code findLimitingFactor} and the value the published rating-regression clause carries.
     */
    public static int getMetricCount(String metric, AssessLayoutResultDto assessment) {
        return switch (metric) {
            case "overlaps" -> assessment.overlapCount();
            case "edgeCrossings" -> assessment.edgeCrossingCount();
            case "labelOverlaps" -> assessment.labelOverlapCount();
            case "passThroughs" -> assessment.crossElementPassThroughCount();
            case "coincidentSegments" -> assessment.coincidentSegmentCount();
            case "nonOrthogonalTerminals" -> assessment.nonOrthogonalTerminalCount();
            case "boundaryViolations" -> assessment.boundaryViolationCount();
            case "parentLabelObscured" -> assessment.parentLabelObscuredCount();
            case "offCanvas" -> assessment.offCanvasWarnings() != null
                    ? assessment.offCanvasWarnings().size() : 0;
            case "labelTruncations" -> assessment.labelTruncationCount();
            case "interiorTerminations" -> assessment.interiorTerminationCount();
            case "zigzags" -> assessment.zigzagCount();
            case "connectionEdgeCoincidence" -> assessment.connectionEdgeCoincidenceCount();
            case "hubPortQuality" -> assessment.hubPortQualityScore() < LayoutQualityAssessor.HUB_PORT_QUALITY_FAIR_THRESHOLD ? 1 : 0;
            default -> 0; // spacing, alignment — no direct count
        };
    }
}
