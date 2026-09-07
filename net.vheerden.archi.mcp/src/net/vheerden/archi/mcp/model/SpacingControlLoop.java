package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pure-EMF-free helper that drives the
 * {@literal observe → decide → back-off} control loop embedded inside the
 * three convenience-tool accessor methods
 * ({@code applyElementSpacingRecommendations} /
 * {@code applyGroupSpacingRecommendations} /
 * {@code applySpacingRecommendations}). Single source of truth for the
 * iteration semantics that close the 1/6 → 6/6 agent-in-loop strict-PASS gap
 * (REGRESSION disposition) 2026-05-15.
 *
 * <p><strong>Architectural contract:</strong>
 * <ol>
 *   <li>Pure-EMF-free helper — no transitive class-loading of
 *       {@code ArchiModelAccessorImpl} required at test time. Pinned by
 *       {@code SpacingControlLoopTest}.</li>
 *   <li>The three existing single-shot decision records
 *       ({@link ApplyElementSpacingDecision} /
 *       {@link ApplyGroupSpacingDecision} /
 *       {@link ApplySpacingDecision}) stay UNCHANGED and act as the ENTRY
 *       GUARD for the loop. Their {@code decide(...)} short-circuit branches
 *       (no groups / no groups with 2+ children / no connections /
 *       heuristic-already-met / dryRun) are handled BEFORE the loop is even
 *       entered — branches (d) + (e) of the termination contract.</li>
 *   <li>Back-off predicate uses aggregate {@link LayoutMetrics#thresholdsMet()}
 *       regression EXCLUSIVELY (aggregate, not per-metric).
 *       Per-metric monotonicity rules are EXPLICITLY FORBIDDEN.</li>
 *   <li>Tie-break ordering when post.thresholdsMet == best.thresholdsMet:
 *       (1) lower M4; (2) lower coincidentSegmentCount; (3) higher HPQ;
 *       (4) lower edgeCrossings. Status-quo preserved when all four are
 *       equal (ACCEPT to keep the loop moving).</li>
 *   <li>Termination contract: exactly ONE branch fires per loop
 *       invocation. The branch list is deliberately not restated here — a
 *       hand-maintained count on this surface has been wrong, as it has on
 *       two others — read the {@code REASON_*} constants below, or the
 *       shipped routing-preconditions checklist, which is the one surface a
 *       live test keeps honest. Some branches are handled by the loop and
 *       some by the accessor's entry-guard short-circuit BEFORE the loop is
 *       called.</li>
 *   <li>Single-undo wrapping: the loop drives speculative
 *       {@link SpacingMutationCommand#execute()} +
 *       {@link SpacingMutationCommand#undo()} per iteration. After the loop
 *       terminates, ALL accepted iterations are undone in reverse order to
 *       restore the model to its pre-loop state. The caller (accessor) is
 *       responsible for building a single
 *       {@code NonNotifyingCompoundCommand} from the accepted-command list
 *       AND pushing that compound through the public command stack — the
 *       stack's {@code execute()} call re-runs each accepted command in
 *       order, landing the model in the post-loop state with exactly ONE
 *       undo entry.</li>
 * </ol></p>
 *
 * <p><strong>Author + date:</strong> 2026-05-15, Task 2 implementation.</p>
 */
public final class SpacingControlLoop {

    private static final Logger logger =
            LoggerFactory.getLogger(SpacingControlLoop.class);

    private SpacingControlLoop() {
        // Utility class — not instantiable
    }

    // ------------------------------------------------------------------
    // Public request / callbacks / result types
    // ------------------------------------------------------------------

    /**
     * Per-invocation request captured for one
     * {@link #iterate(Request, Callbacks)} call.
     *
     * @param initialSpacingPx       caller-measured current spacing baseline
     *                               (most-tight per-group element-spacing or
     *                               inter-group-spacing scan; pixels)
     * @param targetSpacingPx        full-target upper bound (typically
     *                               {@code initialSpacingPx +
     *                               originalDecision.interElementDelta()})
     * @param iterationBudget        maximum iterations the loop may attempt
     *                               (5 element / 5 group / 8 composer
     *                               defaults; caller-tunable in
     *                               {@code [1, 20]} range with handler-level
     *                               validation)
     * @param perIterationStepCapPx  per-iteration step cap; composer
     *                               cap from
     *                               {@link ApplySpacingDecision#ELEMENT_KNEE_LIMIT_PX}
     *                               /
     *                               {@link ApplySpacingDecision#GROUP_KNEE_LIMIT_PX}
     *                               OR {@link Integer#MAX_VALUE} for siblings
     *                               (no cap; the heuristic target is the
     *                               upper bound)
     * @param initialMetrics         pre-loop {@code assess-layout} snapshot;
     *                               serves as iteration-0 best-state baseline
     * @param toolLabel              "element" / "group" / "composer.element"
     *                               / "composer.group" — informational; not
     *                               used in arithmetic; available for future
     *                               telemetry / logging
     * @param hubExtent              the view's dominant-hub descriptor
     *                               captured ONCE pre-loop from the EXISTING
     *                               {@code detectHubElements} read (hub
     *                               sub-signal + diagnosis payload).
     *                               {@code null} = hub sub-signal
     *                               absent — with a {@link Double#NaN}
     *                               {@code initialMetrics.avgSpacingPx()} the
     *                               density-aware discriminator is inert and
     *                               the loop reduces to the 2-state
     *                               back-off (pin preservation).
     * @param viewpointType          the view's viewpoint id, read ONCE
     *                               pre-loop by the caller. DIAGNOSIS WORDING
     *                               ONLY — it never reaches
     *                               {@link #classifyDensityTermination},
     *                               {@link #belowRegime},
     *                               {@link #regimeSignalAvailable}, the
     *                               escalate condition or the trend window,
     *                               so the same views terminate the same way
     *                               whatever it holds. {@code null} /
     *                               unknown ⇒ the pre-existing wording.
     */
    public record Request(
            int initialSpacingPx,
            int targetSpacingPx,
            int iterationBudget,
            int perIterationStepCapPx,
            LayoutMetrics initialMetrics,
            String toolLabel,
            HubExtent hubExtent,
            String viewpointType) {

        public Request {
            Objects.requireNonNull(initialMetrics,
                    "initialMetrics cannot be null");
        }

        /**
         * Backwards-compatible 7-arg constructor — preserve every
         * pre-existing {@code new Request(...)} site so the pin baseline
         * stays GREEN. Delegates with {@code viewpointType = null}: the
         * view class is unknown, so the density-floor diagnosis emits its
         * pre-existing wording (the SAME safe direction the ordered-axis
         * predicate itself takes on an unknown viewpoint).
         */
        public Request(
                int initialSpacingPx,
                int targetSpacingPx,
                int iterationBudget,
                int perIterationStepCapPx,
                LayoutMetrics initialMetrics,
                String toolLabel,
                HubExtent hubExtent) {
            this(initialSpacingPx, targetSpacingPx, iterationBudget,
                    perIterationStepCapPx, initialMetrics, toolLabel,
                    hubExtent, /*viewpointType=*/ null);
        }

        /**
         * Backwards-compatible 6-arg constructor — preserve
         * every pre-existing {@code new Request(...)} pin site so the
         * pin baseline stays GREEN. Delegates with
         * {@code hubExtent = null} (hub sub-signal absent).
         */
        public Request(
                int initialSpacingPx,
                int targetSpacingPx,
                int iterationBudget,
                int perIterationStepCapPx,
                LayoutMetrics initialMetrics,
                String toolLabel) {
            this(initialSpacingPx, targetSpacingPx, iterationBudget,
                    perIterationStepCapPx, initialMetrics, toolLabel,
                    /*hubExtent=*/ null);
        }
    }

    /**
     * Caller-supplied callbacks bridging the loop's pure-EMF-free body to the
     * accessor's EMF/Eclipse-PDE infrastructure.
     */
    public interface Callbacks {

        /**
         * Build a mutation command that will apply the given proposed delta
         * to the in-flight view state. The loop drives
         * {@link SpacingMutationCommand#execute()} + (on regression)
         * {@link SpacingMutationCommand#undo()} on the returned command.
         *
         * @param proposedDeltaPx the delta the loop proposes for this
         *                        iteration step
         * @return a mutation command, or {@code null} when the proposed delta
         *         is not actionable (the loop treats null as a budget-
         *         exhausted-equivalent halt signal)
         */
        SpacingMutationCommand buildMutationCommand(int proposedDeltaPx);

        /**
         * Observe the layout state of the in-flight view (post-Apply of the
         * current iteration). Typically a thin wrapper over
         * {@code ArchiModelAccessor.assessLayout(viewId)} that converts the
         * DTO to {@link LayoutMetrics}.
         *
         * @return non-null snapshot of layout metrics
         */
        LayoutMetrics observeLayout();

        /**
         * Predicate: does the given snapshot satisfy the goal envelope (e.g.,
         * for HH: {@code HPQ ≥ 0.75 AND M4 ≤ 4 AND coincSeg ≤ 2})?
         *
         * <p>Default: always false. Tool-level callers (the accessor methods)
         * pass the default — there is no narrow goal at the tool layer; the
         * loop runs to budget exhaustion or aggregate back-off. Returns true
         * only if a future caller wires up a narrow-goal predicate (out of
         * scope here).</p>
         *
         * @param snapshot post-Apply layout metrics
         * @return true to terminate the loop with
         *         {@code terminationReason = "goal_reached_at_iteration_<N>"}
         */
        default boolean isGoalReached(LayoutMetrics snapshot) {
            return false;
        }

        /**
         * One-shot hub-resize command for the density-escalation path
         * ("Scoped Option B", 2026-05-17). Built + executed
         * EXACTLY ONCE — on the first {@code escalate}-state iteration,
         * BEFORE that iteration's spacing-escalation command so the
         * subsequent {@code computeAdjustViewSpacing} route+assess observes
         * the resized hub. Wrapped (by the accessor closure) in the same
         * SWT-dispatch {@code GefSpacingMutationCommand} adapter as the
         * spacing commands, so it inherits the
         * SWT-marshalling + the partial-commit graceful-degradation guard,
         * and is undone/re-applied by the same single-undo
         * {@code finalizeWithReset} + outer-compound machinery (one
         * undo entry).
         *
         * <p><strong>Default: {@code null}</strong> — no hub-resize available
         * (no large under-sized hub on the view, or the closure does not
         * supply one). Escalation then degrades to spacing-only; the loop is
         * unaffected. Pure-unit pin mocks inherit this default, so the
         * pin baseline is untouched. The 3 accessor
         * closures override it to resize the dominant hub toward the
         * HH-like fan-out regime via the existing {@code UpdateViewObjectCommand}
         * (convenience-tool layer, NOT a routing primitive).</p>
         *
         * @return a one-shot hub-resize mutation command, or {@code null}
         *         when no hub-resize is applicable / available
         */
        default SpacingMutationCommand buildHubResizeCommand() {
            return null;
        }
    }

    /**
     * Loop result.
     *
     * @param iterations        ordered list of all iteration-step records
     *                          (accepted + back-off-final). Backed-off step
     *                          is the LAST element of this list when
     *                          {@code terminationReason} starts with
     *                          {@code "aggregate_threshold_regressed"}.
     * @param acceptedCommands  ordered list of mutation commands the caller
     *                          should wrap in a single
     *                          {@code NonNotifyingCompoundCommand} and push
     *                          through the public command stack. Empty when
     *                          zero iterations were accepted (e.g.,
     *                          heuristic-already-met OR first-iteration
     *                          regression).
     * @param terminationReason taxonomy string (5 branches; (d) +
     *                          (e) are upstream-handled, never seen here)
     * @param finalSpacingPx    cumulative spacing of all accepted iterations
     *                          ({@code initialSpacingPx + sum(accepted
     *                          deltas)})
     */
    public record Result(
            List<SpacingIterationStep> iterations,
            List<SpacingMutationCommand> acceptedCommands,
            String terminationReason,
            int finalSpacingPx,
            String densityDiagnosis) {

        public Result {
            iterations = List.copyOf(iterations);
            acceptedCommands = List.copyOf(acceptedCommands);
            Objects.requireNonNull(terminationReason,
                    "terminationReason cannot be null");
        }

        /**
         * Backwards-compatible 4-arg constructor — preserve
         * every pre-existing {@code new Result(...)} site / pin assertion.
         * Delegates with {@code densityDiagnosis = null} (only
         * the {@code REASON_DENSITY_FLOOR_REFLOW_REQUIRED} PASS-honest
         * branch populates it).
         */
        public Result(
                List<SpacingIterationStep> iterations,
                List<SpacingMutationCommand> acceptedCommands,
                String terminationReason,
                int finalSpacingPx) {
            this(iterations, acceptedCommands, terminationReason,
                    finalSpacingPx, /*densityDiagnosis=*/ null);
        }
    }

    // ------------------------------------------------------------------
    // Termination-reason taxonomy strings
    // ------------------------------------------------------------------

    /** (e) sub-string — heuristic target already met at iteration 0. */
    public static final String REASON_HEURISTIC_ALREADY_MET =
            "heuristic_already_met_no_change";

    /** (a) prefix — goal envelope reached at iteration N (caller goal). */
    public static final String REASON_GOAL_REACHED_PREFIX =
            "goal_reached_at_iteration_";

    /**
     * (b) prefix — iteration budget consumed OR ladder exhausted (target
     * reached but no narrow goal triggered).
     */
    public static final String REASON_BUDGET_EXHAUSTED_PREFIX =
            "budget_exhausted_after_";

    /** (b) suffix — same as prefix. */
    public static final String REASON_BUDGET_EXHAUSTED_SUFFIX =
            "_iterations";

    /** (c) prefix — aggregate thresholdsMet regressed; iteration reverted. */
    public static final String REASON_AGGREGATE_REGRESSED_PREFIX =
            "aggregate_threshold_regressed_at_iteration_";

    /** (c) infix when reverted to a prior accepted iteration. */
    public static final String REASON_REVERTED_TO_ITERATION =
            "_reverted_to_iteration_";

    /** (c) infix when reverted to the loop's initial pre-loop state. */
    public static final String REASON_REVERTED_TO_INITIAL =
            "_reverted_to_initial_state";

    /**
     * NEW in-loop branch (g) — density-floor reflow patch (2026-05-15).
     *
     * <p>Emitted when {@code cmd.execute()} throws RuntimeException mid-
     * iteration. The GEF {@code CompoundCommand.execute()} contract does NOT
     * roll back on partial-throw — if one of the inner commands of the
     * helper's {@code mergedCompound} (typically a route command on post-
     * inflation degenerate geometry)
     * throws, spacing may have been partially applied. The loop body's catch
     * issues a best-effort {@code cmd.undo()} to roll back what DID apply,
     * then unwinds prior accepted iterations via
     * {@link #finalizeWithReset(List, List, String, int)} so the caller's
     * outer dispatch produces a clean compound (the accepted iterations are
     * re-applied via the public command stack as a single undo entry).</p>
     *
     * <p>Root cause: the empirical 0/6 strict-PASS
     * IDENTICAL across pre-patch + post-patch; cmd.execute() partial-
     * throw is the unguarded throw site that propagates to the accessor's
     * outer catch and surfaces as MCP {@code INTERNAL_ERROR}. Neither Fix 1
     * (helper try-finally) nor Fix 2 (closure catch on
     * {@code buildMutationCommand}) protects this path — both guard
     * upstream / downstream of the loop's APPLY step.</p>
     *
     * <p>Pinned by {@code SpacingControlLoopPartialCommitRegressionTest} +
     * {@code SpacingControlLoopTest}.</p>
     */
    public static final String REASON_ITERATION_APPLY_FAILED_PREFIX =
            "iteration_apply_failed_at_iteration_";

    /** (g) infix — "_reverted_after_". */
    public static final String REASON_ITERATION_APPLY_FAILED_AFTER =
            "_reverted_after_";

    /** (g) suffix — "_accepted_iterations". */
    public static final String REASON_ITERATION_APPLY_FAILED_SUFFIX =
            "_accepted_iterations";

    /**
     * NEW pre-loop accessor-layer reason — the route-normalized-baseline
     * guarded form (2026-05-16).
     *
     * <p>Emitted by the accessor's {@code Callbacks}-building closure (NOT by
     * {@link #iterate} — this is a PRE-loop reason, sibling to
     * {@code dry_run_recommendation_not_applied}) when the
     * route-normalized baseline is materially worse than the bare input
     * baseline. A falsification review raised that silently
     * route-normalizing the baseline could
     * swallow a real regression — if the tool's own reroute pass <em>degrades</em>
     * the input, the pre-fix iteration-0 revert accidentally protected the
     * user; route-normalizing removes that accidental safety net. Resolution:
     * <em>guard, don't veto</em> — when the route-normalized baseline scores a
     * strictly lower {@link LayoutMetrics#thresholdsMet()} than the bare
     * baseline, the accessor returns the bare input <strong>untouched</strong>
     * (0 iterations, no mutation) with this {@code terminationReason}, so the
     * safety net is preserved deliberately rather than by accident.</p>
     *
     * <p>Not observed on the gate views (empirically, reroute lifts
     * HPQ 0.18→0.85–0.90) but {@code not observed ≠ impossible}; this branch
     * makes the safety net explicit + agent-visible. Pinned by the
     * ({@code baseline_routeDegraded_returnsBareStateZeroIterations}).</p>
     */
    public static final String REASON_REROUTE_DEGRADED_INPUT_BASELINE =
            "reroute_degraded_input_baseline";

    // ------------------------------------------------------------------
    // Density-aware 3-state termination. Replaces the 2-state (continue /
    // back-off-at-structural-floor) termination with a 3-state {continue,
    // escalate, PASS-honest} discriminator on a 2×2 of aggregate-trend ×
    // spacing-regime-position.
    // Design (2026-05-17): Scoped-Option-B one-shot hub-resize / ceiling-raise
    // + back-off safety net / 3 iters & 112px / 2-step stall window.
    // ------------------------------------------------------------------

    /**
     * PASS-honest terminal — the aggregate stalled while the view is
     * AT/ABOVE the prescribed spacing regime, so more spacing/hub-resize
     * cannot help: a structural reflow is required. The loop NEVER
     * auto-reflows; it reverts any step that does not improve its own
     * step scalar. That scalar is narrower than {@code overallRating} — the
     * accessor compares the two assessments after the loop returns and
     * discloses a rating regression the scalar could not see. This branch
     * emits this reason + an actionable
     * {@link Result#densityDiagnosis()} naming the violated precondition, and
     * the accessor surfaces it for explicit user consent. Distinct from every
     * back-off reason.
     */
    public static final String REASON_DENSITY_FLOOR_REFLOW_REQUIRED =
            "density_floor_reflow_required";

    /**
     * Maximum number of loop iterations that may run in
     * {@code escalate} mode (2026-05-17). An
     * escalate iteration carries the full N×K best-of-K route+assess cost
     * (Given B), so escalation is hard-capped; combined with the large step
     * (escalate converges the spacing knob to {@link #DENSITY_MIDBAND_TARGET_PX}
     * in ≤ this many jumps) it bounds the cost multiplier.
     */
    public static final int MAX_DENSITY_ESCALATION_ITERS = 3;

    /**
     * The mid-band spacing-knob target escalation drives toward
     * (2026-05-17): the midpoint of the
     * {@code project-context.md} Layout-Strategy 100–124px flow-view band.
     * Escalation raises the loop's effective target ceiling to AT LEAST this
     * value so the large step structurally reaches the regime
     * within {@link #MAX_DENSITY_ESCALATION_ITERS} — PASS-honest then only
     * ever fires in-regime (no "reflow-claimed-while-below-regime" FAIL).
     */
    public static final int DENSITY_MIDBAND_TARGET_PX = 112;

    /**
     * Spacing-regime-position lower bound — {@code project-context.md}
     * Layout Strategy: flow views want "100px+ average" spacing ("24px →
     * poor, 124px → excellent"). Measured {@code avgSpacingPx} strictly below
     * this is "below-regime".
     */
    public static final int DENSITY_REGIME_LOWER_PX = 100;

    /**
     * Diagnosis text only — the {@code project-context.md} "excellent"
     * upper anchor of the 100–124px band (names the target band in the
     * actionable diagnosis; not used in any classification arithmetic).
     */
    public static final int DENSITY_REGIME_EXCELLENT_PX = 124;

    /**
     * Aggregate-trend stall window (2026-05-17,
     * the aggregate is "stalled/flat" when the
     * ship-gate {@link LayoutMetrics#thresholdsMet()} aggregate has not
     * strictly increased across this many consecutive attempted iterations.
     * 2 (not 1) per Given A — best-of-K K=12 damps per-iteration routing
     * jitter, so a 2-step no-gain is a trustworthy, jitter-robust stall
     * without over-conservatism.
     */
    public static final int DENSITY_TREND_WINDOW = 2;

    /**
     * Hub sub-signal — a hub with MORE than this many connections is
     * "large" (matches the accessor's existing density-aware default
     * {@code connectionCount() > 6} large-hub gate). Only large hubs are
     * subject to the {@link #hubUnderSizedForFanOut(HubExtent)} regime check.
     */
    public static final int DENSITY_HUB_FANOUT_CONN_THRESHOLD = 6;

    /**
     * Hub sub-signal — HH-like adequate-hub <em>base</em> minimum width:
     * the minimum for the SMALLEST "large" hub (exactly
     * {@link #DENSITY_HUB_FANOUT_CONN_THRESHOLD}+1 connections). The reframe's
     * "HH-like ≥300×250" vs the ST clone's 214×68. This is the base of a
     * <strong>fan-out-scaled</strong> minimum (see
     * {@link #requiredHubMinWidthPx(int)}), NOT a flat exclusive-{@code <}
     * floor — a flat 300×250 mis-classified the HH 300×250 hub
     * with 17 connections as "adequate" ({@code 300<300=false}), so the loop
     * PASS-honested a view Arm-A proved clearable to HPQ 0.97 by resizing
     * that hub to ≈390×325.
     */
    public static final int DENSITY_HUB_MIN_WIDTH_PX = 300;

    /**
     * Hub sub-signal — HH-like adequate-hub <em>base</em> minimum height
     * for the smallest "large" hub (see {@link #DENSITY_HUB_MIN_WIDTH_PX} /
     * {@link #requiredHubMinHeightPx(int)} for the fan-out scaling).
     */
    public static final int DENSITY_HUB_MIN_HEIGHT_PX = 250;

    /**
     * Extra adequate
     * width (px) required PER hub connection above the smallest "large" hub
     * (i.e. per connection beyond {@link #DENSITY_HUB_FANOUT_CONN_THRESHOLD}+1).
     * Calibrated against the hub-heavy reference datapoint: a 300×250 hub is
     * inadequate for 17 connections; ≈390×325 cleared HPQ 0.97 / M4 4 /
     * coincSeg 0. With base 300 and {@code extra(17)=10}, this yields a
     * required minimum width of {@code 300 + 10×10 = 400 ≥ 390} so
     * {@code (300,250,17)} flags under-sized AND the coupled
     * {@code buildDensityHubResizeCommand} resize target is ≥ the
     * Arm-A-proven-clearable extent (predicate↔resize-target consistency —
     * the hidden coupling: fan-out-scale BOTH or it is a no-op loop).
     */
    public static final int DENSITY_HUB_WIDTH_PER_CONN_PX = 10;

    /**
     * Extra adequate
     * height (px) per hub connection above the smallest "large" hub.
     * Calibrated so {@code 250 + 8×extra(17)=330 ≥ 325} (the hub-heavy
     * proven-clearable height); monotone in connection count.
     */
    public static final int DENSITY_HUB_HEIGHT_PER_CONN_PX = 8;

    // ------------------------------------------------------------------
    // Public entry point
    // ------------------------------------------------------------------

    /**
     * Run the control loop. See class-level Javadoc for the contract.
     *
     * <p>Method contract is detailed in the architecture spec
     * ({@code architecture-spec.md} § 1.1.1 + § 1.5 + § 1.6).</p>
     *
     * @param request   per-invocation parameters (non-null)
     * @param callbacks loop callbacks bridging to the accessor's EMF
     *                  infrastructure (non-null)
     * @return loop result with iteration list + accepted-commands list +
     *         termination reason + final spacing
     */
    public static Result iterate(Request request, Callbacks callbacks) {
        Objects.requireNonNull(request, "request cannot be null");
        Objects.requireNonNull(callbacks, "callbacks cannot be null");

        // Pre-loop guard: target already met (defence-in-depth — accessor
        // entry guard normally handles this via existing decide(...) short-
        // circuit, but we also handle here so the loop is self-defending
        // and the pure-unit test can exercise this branch directly).
        if (request.initialSpacingPx() >= request.targetSpacingPx()) {
            return new Result(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    REASON_HEURISTIC_ALREADY_MET,
                    request.initialSpacingPx());
        }

        // Pre-loop guard: zero budget — bail with budget-exhausted-0.
        if (request.iterationBudget() <= 0) {
            return new Result(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    REASON_BUDGET_EXHAUSTED_PREFIX + "0"
                            + REASON_BUDGET_EXHAUSTED_SUFFIX,
                    request.initialSpacingPx());
        }

        List<SpacingIterationStep> iterations = new ArrayList<>();
        List<SpacingMutationCommand> acceptedCommands = new ArrayList<>();

        LayoutMetrics bestState = request.initialMetrics();
        int currentSpacing = request.initialSpacingPx();
        boolean bestStateIsInitial = true;

        // --- Density-aware 3-state termination loop state ---
        // All inert unless a regime signal is present (avgSpacingPx != NaN
        // OR hubExtent != null); when absent the loop is byte-identical to
        // the 2-state back-off (pin preservation).
        int stepsSinceAggregateGain = 0;            // stall-window counter   
        int escalationItersUsed = 0;                // escalate-cap counter
        boolean escalating = false;                 // escalate-mode latch
        boolean hubResizeApplied = false;           // one-shot guard
        int escalationCeilingPx = request.targetSpacingPx(); // raised on
                // escalate to ≥ DENSITY_MIDBAND_TARGET_PX

        for (int stepIndex = 0;
                stepIndex < request.iterationBudget(); stepIndex++) {

            // ----------------------------------------------------------
            // 1. DECIDE — propose next-step delta. The ladder-exhausted
            //    guard runs against escalationCeilingPx — equal to
            //    request.targetSpacingPx() until escalate raises it to
            //    ≥ DENSITY_MIDBAND_TARGET_PX, so the NON-
            //    escalating path is byte-identical to the 2-state form. In
            //    escalate mode the +10 ladder is overridden by a LARGE step
            //    that converges the spacing knob to the raised ceiling in
            //    ≤ MAX_DENSITY_ESCALATION_ITERS jumps (cost-aware).
            // ----------------------------------------------------------
            SpacingIterationDecision decision =
                    SpacingIterationDecision.decideNextStep(
                            stepIndex, currentSpacing,
                            escalationCeilingPx,
                            request.perIterationStepCapPx());

            if (!decision.keepGoing()) {
                // Ladder/ceiling reached. The heuristic targetSpacingPx may
                // ITSELF be below the prescribed regime (the ST-class case:
                // a low hub-aware target reached while avgSpacing is still
                // < 100). Stopping HERE is the too-early stop the density
                // logic must catch: RAISE the effective
                // ceiling to ≥ DENSITY_MIDBAND_TARGET_PX and escalate
                // instead of terminating, so escalate (and PASS-honest)
                // are reachable even when the heuristic target undershoots
                // the regime. Gated so it fires AT MOST once (ceiling not
                // yet raised) and ONLY below-regime with escalation budget
                // remaining; the `escalating` cost is still bounded by the
                // per-iteration MAX_DENSITY_ESCALATION_ITERS seam counter.
                boolean canEscalatePastCeiling =
                        regimeSignalAvailable(bestState, request.hubExtent())
                        && belowRegime(bestState, request.hubExtent())
                        && escalationItersUsed < MAX_DENSITY_ESCALATION_ITERS
                        && escalationCeilingPx < Math.max(
                                request.targetSpacingPx(),
                                DENSITY_MIDBAND_TARGET_PX);
                if (canEscalatePastCeiling) {
                    escalating = true;
                    escalationCeilingPx = Math.max(
                            request.targetSpacingPx(),
                            DENSITY_MIDBAND_TARGET_PX);
                    decision = SpacingIterationDecision.decideNextStep(
                            stepIndex, currentSpacing, escalationCeilingPx,
                            request.perIterationStepCapPx());
                }
                if (!decision.keepGoing()) {
                    // NOT escalating → ladder-exhausted
                    // budget-exhausted terminal (regime absent →
                    // byte-identical). Escalating but the raised ceiling
                    // ALSO has no headroom (knob already ≥ mid-band yet
                    // measured avgSpacing still below regime) → the
                    // Escalation-cap safety net: preserved terminal,
                    // accepted state intact (honest, NOT a false reflow
                    // claim, and non-degrading ON THE STEP SCALAR — the
                    // wider rating comparison is taken by the accessor).
                    return finalizeWithReset(iterations, acceptedCommands,
                            REASON_BUDGET_EXHAUSTED_PREFIX
                                    + acceptedCommands.size()
                                    + REASON_BUDGET_EXHAUSTED_SUFFIX,
                            currentSpacing);
                }
            }

            int proposedDelta;
            if (escalating) {
                int remainingEscalationIters = Math.max(1,
                        MAX_DENSITY_ESCALATION_ITERS - escalationItersUsed);
                int distanceToCeiling = escalationCeilingPx - currentSpacing;
                int largeStep = (int) Math.ceil(
                        (double) distanceToCeiling / remainingEscalationIters);
                proposedDelta = Math.min(
                        Math.min(largeStep, distanceToCeiling),
                        request.perIterationStepCapPx());
                if (proposedDelta <= 0) {
                    // Escalation headroom exhausted (escalation-cap safety
                    // net) — preserved budget-exhausted terminal.
                    return finalizeWithReset(iterations, acceptedCommands,
                            REASON_BUDGET_EXHAUSTED_PREFIX
                                    + acceptedCommands.size()
                                    + REASON_BUDGET_EXHAUSTED_SUFFIX,
                            currentSpacing);
                }
            } else {
                proposedDelta = decision.nextStepDelta();
            }

            // ----------------------------------------------------------
            // 1b. ONE-SHOT hub-resize (Scoped Option
            //     B) — built + executed EXACTLY ONCE, at the top of the
            //     first escalate-mode iteration, BEFORE the spacing
            //     command so the spacing helper's best-of-K route+assess
            //     observes the resized hub. Paired with this iteration's
            //     spacing step (accepted/reverted together); the accessor
            //     wraps it in the SWT-dispatch GefSpacingMutationCommand
            //     so it inherits the partial-commit guard. null
            //     (no large under-sized hub / unavailable) → escalation
            //     degrades to spacing-only (loop unaffected).
            // ----------------------------------------------------------
            SpacingMutationCommand hubResizeCmd = null;
            if (escalating && !hubResizeApplied) {
                hubResizeApplied = true; // one-shot regardless of outcome
                SpacingMutationCommand hr =
                        callbacks.buildHubResizeCommand();
                if (hr != null) {
                    try {
                        hr.execute();
                        hubResizeCmd = hr;
                    } catch (RuntimeException hubFailure) {
                        logger.error(
                                "one-shot hub-resize execute() threw at "
                                + "escalate iteration {} (toolLabel={}); "
                                + "degrading to spacing-only escalation",
                                stepIndex, request.toolLabel(),
                                hubFailure);
                        try {
                            hr.undo();
                        } catch (RuntimeException hubUndoFailure) {
                            logger.error(
                                    "one-shot hub-resize best-effort "
                                    + "undo() also threw at iteration {} "
                                    + "(toolLabel={})",
                                    stepIndex, request.toolLabel(),
                                    hubUndoFailure);
                        }
                        hubResizeCmd = null;
                    }
                }
            }

            // ----------------------------------------------------------
            // 2. BUILD — caller constructs the spacing mutation command
            // ----------------------------------------------------------
            SpacingMutationCommand cmd =
                    callbacks.buildMutationCommand(proposedDelta);
            if (cmd == null) {
                // Caller signalled mutation not actionable. Roll back this
                // iteration's one-shot hub-resize (if any) so no partial
                // hub mutation leaks, then bail with the preserved
                // budget-exhausted-equivalent terminal.
                if (hubResizeCmd != null) {
                    try {
                        hubResizeCmd.undo();
                    } catch (RuntimeException hubUndoFailure) {
                        logger.error("hub-resize rollback after null "
                                + "spacing cmd threw (iter {}, "
                                + "toolLabel={})",
                                stepIndex, request.toolLabel(),
                                hubUndoFailure);
                    }
                }
                return finalizeWithReset(iterations, acceptedCommands,
                        REASON_BUDGET_EXHAUSTED_PREFIX
                                + acceptedCommands.size()
                                + REASON_BUDGET_EXHAUSTED_SUFFIX,
                        currentSpacing);
            }

            // ----------------------------------------------------------
            // 3. APPLY — speculative execute, then observe
            //
            // GRACEFUL-DEGRADATION (density-floor reflow patch,
            // 2026-05-15, root-cause fix for the disposition-C
            // model-bias-unfixable-via-loop-design observed under the
            // 2026-05-15): cmd.execute() invokes the helper's
            // mergedCompound.execute() which is a GEF CompoundCommand. GEF
            // CompoundCommand.execute() iterates inner commands FORWARD and
            // does NOT roll back on partial-throw — any RuntimeException
            // thrown by an inner command (typically a route command NPE/ISE
            // on post-inflation degenerate geometry) leaves the model
            // partially mutated AND propagates the
            // exception. Without this catch, the throw escapes iterate() ,
            // reaches the accessor's outer catch
            // (ArchiModelAccessorImpl.java:7707 region), and is wrapped as
            // MCP `INTERNAL_ERROR` — the exact symptom triad
            // (INTERNAL_ERROR + partial-commit + silent-state-advance)
            // documented across Sessions 6 + 7.
            //
            // Recovery: best-effort cmd.undo() rolls back what DID apply
            // (GEF CompoundCommand.undo() reverses sub-commands; for sub-
            // commands that didn't execute, the default no-op is safe).
            // The failed iteration is recorded in `iterations` for forensic
            // visibility but is NOT added to `acceptedCommands`. Then
            // finalizeWithReset() unwinds the prior accepted commands so
            // the caller's outer compound dispatch sees a clean re-execute
            // path with ONE undo entry.
            //
            // Pinned by `SpacingControlLoopPartialCommitRegressionTest`
            // graceful-degradation cmd.execute()-throws assertion +
            // `SpacingControlLoopTest` new @Test method covering the new
            // termination branch.
            LayoutMetrics preState = bestState;
            LayoutMetrics postState;
            int spacingAfter = currentSpacing + proposedDelta;
            try {
                cmd.execute();
                postState = callbacks.observeLayout();
            } catch (RuntimeException applyFailure) {
                // SWT-marshalling diagnostic patch (
                // 2026-05-15): surface the actual throw site's stack trace
                // to the Archi runtime log so the next re-empirical
                // captures the underlying NPE/ISE for root-cause
                // identification. User-facing behaviour unchanged — sub-
                // agents still observe the graceful-degradation
                // terminationReason taxonomy + best-effort cmd.undo() + no
                // outer-compound commit. The error-level log entry fires
                // EXACTLY once per failed iteration on the Eclipse-
                // application workspace log (`/Users/<owner>/runtime-archi.
                // product/.metadata/.log` — not the IDE workspace log).
                logger.error(
                        "cmd.execute() partial-throw at iteration {} "
                                + "(toolLabel={}, proposedDelta={}, "
                                + "spacingAfter={}, acceptedCount={})",
                        stepIndex,
                        request.toolLabel(),
                        proposedDelta,
                        spacingAfter,
                        acceptedCommands.size(),
                        applyFailure);
                // Best-effort rollback of partially-applied cmd. Suppress
                // any nested exception from undo — the original applyFailure
                // is what we report via terminationReason; the model may be
                // in a partially-inconsistent state but the outer compound
                // is never committed.
                try {
                    cmd.undo();
                } catch (RuntimeException undoFailure) {
                    // Suppressed — best-effort rollback; nested cleanup
                    // failures are non-recoverable at this layer. Caller's
                    // dispatch is bypassed (finalizeWithReset returns
                    // accepted-commands-only; no outer compound built).
                    logger.error(
                            "cmd.undo() best-effort rollback also threw "
                                    + "at iteration {} (toolLabel={})",
                            stepIndex,
                            request.toolLabel(),
                            undoFailure);
                }
                // Also roll back this iteration's one-shot hub-resize (if
                // any) — it is NOT in acceptedCommands yet, so
                // finalizeWithReset would not unwind it. Best-effort;
                // suppress nested failure (consistent with cmd.undo()).
                if (hubResizeCmd != null) {
                    try {
                        hubResizeCmd.undo();
                    } catch (RuntimeException hubUndoFailure) {
                        logger.error(
                                "one-shot hub-resize best-effort undo() "
                                + "threw during apply-failure rollback at "
                                + "iteration {} (toolLabel={})",
                                stepIndex, request.toolLabel(),
                                hubUndoFailure);
                    }
                }
                String terminationReason =
                        REASON_ITERATION_APPLY_FAILED_PREFIX
                                + stepIndex
                                + REASON_ITERATION_APPLY_FAILED_AFTER
                                + acceptedCommands.size()
                                + REASON_ITERATION_APPLY_FAILED_SUFFIX;
                iterations.add(new SpacingIterationStep(
                        stepIndex, proposedDelta, spacingAfter,
                        preState,
                        /*postState=*/ preState, // unknown — model state
                                                  // uncertain after partial
                                                  // throw + best-effort undo
                        bestState.thresholdsMet(),
                        bestState.thresholdsMet(),
                        /*thresholdsMetDelta=*/ 0,
                        /*backedOff=*/ true,
                        terminationReason));
                return finalizeWithReset(iterations, acceptedCommands,
                        terminationReason, currentSpacing);
            }
            int thresholdsMetDelta = postState.thresholdsMet()
                    - bestState.thresholdsMet();

            // ----------------------------------------------------------
            // 4. BACK-OFF predicate — aggregate thresholdsMet + tie-breaks
            //    (single-step ACCEPT rule; UNCHANGED).
            // ----------------------------------------------------------
            boolean accept = acceptStepDecision(postState, bestState);

            // --- Density-aware 3-state trend bookkeeping (
            //     window = DENSITY_TREND_WINDOW = 2). Computed always but
            //     consumed ONLY when a regime signal is present. ---
            boolean aggregateGained =
                    postState.thresholdsMet() > bestState.thresholdsMet();
            if (aggregateGained) {
                stepsSinceAggregateGain = 0;
            } else {
                stepsSinceAggregateGain++;
            }
            boolean aggregateStalled =
                    stepsSinceAggregateGain >= DENSITY_TREND_WINDOW;

            // ----------------------------------------------------------
            // 4b. DENSITY-AWARE 3-STATE DISCRIMINATOR. Active
            //     ONLY when a regime signal is present; otherwise the loop
            //     skips this block entirely and the PRESERVED
            //     2-state §4/§5/§6 below runs byte-identically (the
            //     invariant that keeps every pre-existing pin GREEN).
            // ----------------------------------------------------------
            if (regimeSignalAvailable(postState, request.hubExtent())) {
                boolean below =
                        belowRegime(postState, request.hubExtent());
                // Thread the escalation-budget
                // signal into the (now decoupled) classifier as a pure
                // parameter — `escalationItersUsed` is loop-internal state,
                // NOT read inside the classifier (keeps it 2×2×2-pinnable).
                // This IS the "mandated in-loop below-regime + budget
                // escalate check": it is evaluated EVERY iteration here,
                // inside the SAME `regimeSignalAvailable` master gate as the
                // :882 density block, so the decoupled ESCALATE state is
                // actually reachable at :933 on the budget-exhausting
                // trajectory the probe verbatim took (closing the
                // :1141-before-:607 bypass) — escalate is attempted on
                // every below-regime+budget iteration, so the loop cannot
                // budget-exhaust below-regime with budget remaining without
                // having attempted escalate. The cost cap
                // (MAX_DENSITY_ESCALATION_ITERS, Given B N×K) is NOT
                // loosened. Regime-signal-absent ⇒ this whole block is
                // skipped ⇒ 2-state-byte-identical.
                boolean escalationBudgetRemaining =
                        escalationItersUsed < MAX_DENSITY_ESCALATION_ITERS;
                DensityTerminationState densityState =
                        classifyDensityTermination(aggregateStalled, below,
                                escalationBudgetRemaining);

                if (densityState
                        == DensityTerminationState.PASS_HONEST) {
                    // Stalled AND in-regime → more spacing/hub-resize
                    // cannot help (Given A: best-of-K-optimised, so this
                    // is a trustworthy reflow signal). NEVER auto-reflow:
                    // keep an accept-able step, revert one that degrades
                    // the step scalar (a rating regression the scalar cannot
                    // see is disclosed by the accessor afterwards), emit the
                    // actionable diagnosis, HALT.
                    if (accept) {
                        if (hubResizeCmd != null) {
                            acceptedCommands.add(hubResizeCmd);
                        }
                        acceptedCommands.add(cmd);
                        iterations.add(new SpacingIterationStep(
                                stepIndex, proposedDelta, spacingAfter,
                                preState, postState,
                                bestState.thresholdsMet(),
                                postState.thresholdsMet(),
                                thresholdsMetDelta,
                                /*backedOff=*/ false,
                                /*backOffReason=*/ null));
                        bestState = postState;
                        bestStateIsInitial = false;
                        currentSpacing = spacingAfter;
                    } else {
                        cmd.undo();
                        if (hubResizeCmd != null) {
                            hubResizeCmd.undo();
                        }
                        iterations.add(new SpacingIterationStep(
                                stepIndex, proposedDelta, spacingAfter,
                                preState, postState,
                                bestState.thresholdsMet(),
                                postState.thresholdsMet(),
                                thresholdsMetDelta,
                                /*backedOff=*/ true,
                                REASON_DENSITY_FLOOR_REFLOW_REQUIRED));
                    }
                    return finalizeWithReset(iterations, acceptedCommands,
                            REASON_DENSITY_FLOOR_REFLOW_REQUIRED,
                            currentSpacing,
                            buildDensityDiagnosis(postState,
                                    request.hubExtent(),
                                    request.viewpointType()));
                }

                if (densityState == DensityTerminationState.ESCALATE
                        && escalationItersUsed
                                < MAX_DENSITY_ESCALATION_ITERS) {
                    // Stalled AND below-regime AND escalation budget
                    // remains → DO NOT halt (THE too-early
                    // back-off fix). Keep an accept-able step; revert a
                    // degrading one (best preserved — never degrade).
                    // Enter/continue escalate mode + raise the effective
                    // ceiling to ≥ DENSITY_MIDBAND_TARGET_PX (the escalation
                    // cap) so the large step reaches in-regime within the
                    // cap (PASS-honest then only ever fires in-regime —
                    // no reflow-claimed-while-below-regime FAIL).
                    if (accept) {
                        if (hubResizeCmd != null) {
                            acceptedCommands.add(hubResizeCmd);
                        }
                        acceptedCommands.add(cmd);
                        iterations.add(new SpacingIterationStep(
                                stepIndex, proposedDelta, spacingAfter,
                                preState, postState,
                                bestState.thresholdsMet(),
                                postState.thresholdsMet(),
                                thresholdsMetDelta,
                                /*backedOff=*/ false,
                                /*backOffReason=*/ null));
                        bestState = postState;
                        bestStateIsInitial = false;
                        currentSpacing = spacingAfter;
                    } else {
                        cmd.undo();
                        if (hubResizeCmd != null) {
                            hubResizeCmd.undo();
                        }
                        iterations.add(new SpacingIterationStep(
                                stepIndex, proposedDelta, spacingAfter,
                                preState, postState,
                                bestState.thresholdsMet(),
                                postState.thresholdsMet(),
                                thresholdsMetDelta,
                                /*backedOff=*/ true,
                                "density_escalate_at_iteration_"
                                        + stepIndex));
                    }
                    escalating = true;
                    escalationCeilingPx = Math.max(
                            request.targetSpacingPx(),
                            DENSITY_MIDBAND_TARGET_PX);
                    escalationItersUsed++;
                    stepsSinceAggregateGain = 0; // fresh window for
                            // escalation to show progress before the
                            // next stall classification
                    continue; // <-- DO NOT halt (too-early back-off fix)
                }

                if (densityState == DensityTerminationState.CONTINUE
                        && !accept) {
                    // Climbing window not yet stalled, regime signal
                    // present, this step regressed: revert it (best
                    // preserved) but KEEP ITERATING so escalate /
                    // PASS-honest can be reached — the too-early
                    // back-off fix applied pre-stall too.
                    cmd.undo();
                    if (hubResizeCmd != null) {
                        hubResizeCmd.undo();
                    }
                    iterations.add(new SpacingIterationStep(
                            stepIndex, proposedDelta, spacingAfter,
                            preState, postState,
                            bestState.thresholdsMet(),
                            postState.thresholdsMet(),
                            thresholdsMetDelta,
                            /*backedOff=*/ true,
                            "density_within_window_revert_continue_at_"
                                    + stepIndex));
                    continue;
                }
                // else fall through to the PRESERVED 2-state:
                //   - CONTINUE + accept  → §5 ACCEPT (climbing happy path)
                //   - ESCALATE, cap exhausted, !accept  → §4 back-off
                //     (escalation-cap safety net; preserved terminal,
                //     NOT PASS-honest → no FAIL; no 4th state)
                //   - ESCALATE, cap exhausted, accept  → §5 ACCEPT then
                //     budget-bounded preserved terminal (honest).
            }

            // ----------------------------------------------------------
            // 4 (PRESERVED 2-state). Byte-identical to the 2-state form
            //    when the regime signal is ABSENT (pure-unit: NaN
            //    avgSpacing + null hubExtent → every pin GREEN). Also the
            //    the escalation-cap safety net + the CONTINUE+
            //    accept happy path. The only additive change is the
            //    no-op-when-null hubResizeCmd unwind (hubResizeCmd is
            //    always null unless escalate ran → 2-state unaffected).
            // ----------------------------------------------------------
            if (!accept) {
                // ------------------------------------------------------
                // TERMINAL-REINTERPRETATION,
                // insertion point #1. If this preserved §4 give-up
                // fires while a regime signal is present AND the view is
                // in-regime, it IS the density-floor stall (Given A: the
                // post/baseline are best-of-K-optimised → a give-up
                // in-regime is a trustworthy reflow signal, not routing
                // noise), arriving by a different control-flow path than
                // the :882 2-window stall detector. Relabel it as
                // PASS_HONEST (emit the actionable diagnosis, revert the
                // step that degrades the step scalar), gated
                // EXACTLY like the :882 density block so regime-signal-
                // absent ⇒ 2-state-byte-identical. Byte-mirrors the
                // L888 PASS_HONEST !accept handling (:912-930) WITHOUT
                // touching that branch. NOTE: by the loop's
                // control flow this §4 terminal is reached only via the
                // ESCALATE-cap-exhausted fall-through (⇒ belowRegime==true
                // by construction), so this guard is a defensive,
                // invariant-preserving sibling of insertion point #2
                // (:1081, the active in-regime terminal).
                if (inRegimeWithSignal(postState, request.hubExtent())) {
                    cmd.undo();
                    if (hubResizeCmd != null) {
                        hubResizeCmd.undo();
                    }
                    iterations.add(new SpacingIterationStep(
                            stepIndex, proposedDelta, spacingAfter,
                            preState, postState,
                            bestState.thresholdsMet(),
                            postState.thresholdsMet(),
                            thresholdsMetDelta,
                            /*backedOff=*/ true,
                            REASON_DENSITY_FLOOR_REFLOW_REQUIRED));
                    return finalizeAsDensityPassHonest(iterations,
                            acceptedCommands, currentSpacing,
                            postState, request.hubExtent(),
                            request.viewpointType());
                }
                // REVERT this iteration's command + halt loop.
                cmd.undo();
                if (hubResizeCmd != null) {
                    hubResizeCmd.undo();
                }
                String backOffReason = REASON_AGGREGATE_REGRESSED_PREFIX
                        + stepIndex
                        + (bestStateIsInitial
                                ? REASON_REVERTED_TO_INITIAL
                                : REASON_REVERTED_TO_ITERATION
                                        + (acceptedCommands.size() - 1));
                iterations.add(new SpacingIterationStep(
                        stepIndex, proposedDelta, spacingAfter,
                        preState, postState,
                        bestState.thresholdsMet(),
                        postState.thresholdsMet(),
                        thresholdsMetDelta,
                        /*backedOff=*/ true, backOffReason));
                return finalizeWithReset(iterations, acceptedCommands,
                        backOffReason, currentSpacing);
            }

            // ----------------------------------------------------------
            // 5. ACCEPT — keep cmd (and any paired one-shot hub-resize)
            //    in accepted-list; update state
            // ----------------------------------------------------------
            if (hubResizeCmd != null) {
                acceptedCommands.add(hubResizeCmd);
            }
            acceptedCommands.add(cmd);
            iterations.add(new SpacingIterationStep(
                    stepIndex, proposedDelta, spacingAfter,
                    preState, postState,
                    bestState.thresholdsMet(),
                    postState.thresholdsMet(),
                    thresholdsMetDelta,
                    /*backedOff=*/ false, /*backOffReason=*/ null));
            bestState = postState;
            bestStateIsInitial = false;
            currentSpacing = spacingAfter;

            // ----------------------------------------------------------
            // 6. GOAL-REACHED check (optional caller predicate)
            // ----------------------------------------------------------
            if (callbacks.isGoalReached(postState)) {
                return finalizeWithReset(iterations, acceptedCommands,
                        REASON_GOAL_REACHED_PREFIX + stepIndex,
                        currentSpacing);
            }
        }

        // ------------------------------------------------------------------
        // TERMINAL-REINTERPRETATION, insertion point #2 (the
        // ACTIVE in-regime terminal — the sparse-tree reference pattern). The loop spent
        // its whole iterationBudget without ladder-exhaustion / goal /
        // regression — many-small-gains kept resetting the :882 2-step
        // stall window so PASS_HONEST never classified, yet the view is
        // in-regime. That budget-exhaust-in-regime IS the density-floor
        // stall (Given A: best-of-K-optimised ⇒ trustworthy reflow signal,
        // not routing noise). Relabel it as PASS_HONEST + emit the
        // actionable diagnosis. No step to revert here (budget exhausted on
        // ACCEPTED state — `bestState` is non-degrading ON THE STEP SCALAR
        // by construction. Note the predicate is NOT `>=`: see
        // acceptStepDecision, which accepts on a strictly greater scalar and
        // otherwise falls through four tie-break tiers before defaulting to
        // accept. And the scalar is narrower than `overallRating`, so a run
        // reaching this terminal can still have left the view rated worse —
        // the accessor compares the two assessments and discloses it).
        // Loop-local `postState` is out of
        // scope post-loop; `bestState` IS the loop's final accepted
        // post-state at this terminal. Gated EXACTLY like the :882 density
        // block ⇒ regime-signal-absent ⇒ the preserved
        // `budget_exhausted` fires byte-identically.
        if (inRegimeWithSignal(bestState, request.hubExtent())) {
            return finalizeAsDensityPassHonest(iterations, acceptedCommands,
                    currentSpacing, bestState, request.hubExtent(),
                    request.viewpointType());
        }

        // Budget exhausted (loop ran to iterationBudget without ladder
        // exhaustion or goal-reached or regression).
        return finalizeWithReset(iterations, acceptedCommands,
                REASON_BUDGET_EXHAUSTED_PREFIX
                        + acceptedCommands.size()
                        + REASON_BUDGET_EXHAUSTED_SUFFIX,
                currentSpacing);
    }

    // ------------------------------------------------------------------
    // Internal — back-off predicate (aggregate + tie-break)
    // ------------------------------------------------------------------

    /**
     * Aggregate ACCEPT rule + tie-break ordering.
     *
     * <p>ACCEPT rule: {@code post.thresholdsMet >= best.thresholdsMet}
     * (strictly increasing OR equal). On equality, four-tier tie-break:
     * (1) lower M4; (2) lower coincSeg; (3) higher HPQ; (4) lower
     * edgeCrossings. Status-quo preserved when all four tie-break tiers
     * equal — ACCEPT (loop moves forward; alternative is endless oscillation
     * at a flat-state).</p>
     */
    static boolean acceptStepDecision(LayoutMetrics post, LayoutMetrics best) {
        if (post.thresholdsMet() > best.thresholdsMet()) {
            return true;
        }
        if (post.thresholdsMet() < best.thresholdsMet()) {
            return false;
        }
        // Tie on thresholdsMet — fall through to tie-break tiers.
        if (post.m4() < best.m4()) return true;
        if (post.m4() > best.m4()) return false;

        if (post.coincidentSegmentCount() < best.coincidentSegmentCount()) {
            return true;
        }
        if (post.coincidentSegmentCount() > best.coincidentSegmentCount()) {
            return false;
        }

        if (post.hpq() > best.hpq()) return true;
        if (post.hpq() < best.hpq()) return false;

        if (post.edgeCrossings() < best.edgeCrossings()) return true;
        if (post.edgeCrossings() > best.edgeCrossings()) return false;

        // All four tie-break tiers equal — status-quo preserved; ACCEPT.
        return true;
    }

    // ------------------------------------------------------------------
    // Density-aware 3-state discriminator + axis helpers + the
    // actionable PASS-honest diagnosis builder. All pure static —
    // pinned directly by SpacingControlLoopDensityAwareTerminationTest.
    // ------------------------------------------------------------------

    /** The 3-state termination decision (replaces the 2-state). */
    public enum DensityTerminationState {
        /** Aggregate still climbing — keep iterating (ACCEPT path). */
        CONTINUE,
        /** Stalled + below-regime — escalate spacing/hub (fixes the
         *  too-early back-off bug). */
        ESCALATE,
        /** Stalled + in-regime — surface the actionable reflow-required
         *  diagnosis; NEVER auto-reflow. */
        PASS_HONEST
    }

    /**
     * The single discriminator: classifies a post-iteration state on the
     * 2×2 of aggregate-trend × spacing-regime-position, with the
     * escalation-budget axis added so ESCALATE is DECOUPLED from the
     * aggregate-stall window.
     *
     * <p><strong>Escalation-budget decoupling (the ONLY behavioural delta vs
     * the earlier 2-arg table):</strong> the NEW cell {@code !aggregateStalled &
     * belowRegime & escalationBudgetRemaining → ESCALATE}. Every other cell
     * is preserved EXACTLY. Root cause #1 of the earlier FAIL:
     * on the pathological ST view under the agent-in-loop the trajectory is
     * "many-small-gains" — {@code aggregateGained} keeps being true so the
     * 2-step stall window ({@link #DENSITY_TREND_WINDOW}, reset every gain at
     * the caller) NEVER accumulates, so the earlier {@code !aggregateStalled
     * → CONTINUE} returned CONTINUE forever, the {@code :933} post-state
     * ESCALATE branch was never entered, the loop budget-exhausted
     * BELOW-regime and the already-built insertion #2 (gated on
     * in-regime) stayed dormant (ST PASS-honest 0/3). A below-regime state
     * WITH escalation budget is a trustworthy "escalate the spacing" signal
     * even before the stall window trips (Given A: every iteration is
     * best-of-K-optimised, so below-regime is a genuine corridor-room
     * deficit, not routing jitter) — so escalate on it directly. CONTINUE is
     * kept for in-regime climbing (preserved) AND for the
     * no-escalation-budget case (cannot escalate ⇒ must fall through to the
     * preserved terminal, must NOT livelock).
     * {@link #DENSITY_TREND_WINDOW} is byte-unchanged and remains the
     * PASS_HONEST trigger ONLY.</p>
     *
     * <p>The classifier stays PURE — the budget signal is threaded in as a
     * parameter by the caller ({@code escalationItersUsed <
     * MAX_DENSITY_ESCALATION_ITERS}); it does NOT read mutable loop state —
     * so the (now 2×2×2) table is directly truth-table-pinnable
     * ({@code SpacingControlLoopDensityAwareTerminationTest} §A).</p>
     *
     * @param aggregateStalled trend axis — true iff the ship-gate
     *        {@link LayoutMetrics#thresholdsMet()} aggregate has not strictly
     *        increased across the last {@link #DENSITY_TREND_WINDOW} attempted
     *        iterations (Given A makes this best-of-K-optimised, trustworthy).
     * @param belowRegime regime axis — true iff the view is below the
     *        prescribed spacing/hub regime (see {@link #belowRegime}).
     * @param escalationBudgetRemaining escalation-budget axis — true
     *        iff escalation budget remains (the caller passes
     *        {@code escalationItersUsed < MAX_DENSITY_ESCALATION_ITERS}); the
     *        cost cap itself is NOT loosened (Given B, N×K — byte-unchanged).
     * @return CONTINUE (in-regime climbing, OR below-regime climbing with no
     *         escalation budget) / ESCALATE (below-regime with budget —
     *         decoupled; OR stalled + below-regime — preserved) /
     *         PASS_HONEST (stalled + in-regime — preserved). No 4th state.
     */
    public static DensityTerminationState classifyDensityTermination(
            boolean aggregateStalled, boolean belowRegime,
            boolean escalationBudgetRemaining) {
        if (!aggregateStalled) {
            // Escalation-budget decoupling: escalate on below-regime +
            // budget even before the stall window trips (defeats FAIL
            // root cause #1). In-regime climbing, or below-regime with
            // no escalation budget, stays CONTINUE (preserved
            // !aggregateStalled→CONTINUE; the no-budget case must fall
            // through to the preserved terminal — cannot livelock).
            if (belowRegime && escalationBudgetRemaining) {
                return DensityTerminationState.ESCALATE;
            }
            return DensityTerminationState.CONTINUE;
        }
        return belowRegime
                ? DensityTerminationState.ESCALATE
                : DensityTerminationState.PASS_HONEST;
    }

    /**
     * Regime-signal availability gate. When NEITHER a measured
     * {@code avgSpacingPx} NOR a {@link HubExtent} is supplied, the
     * density-aware discriminator is INERT and the loop reduces
     * byte-identically to the 2-state back-off — the invariant that
     * preserves every pre-existing pin.
     */
    static boolean regimeSignalAvailable(LayoutMetrics post, HubExtent hub) {
        return !Double.isNaN(post.avgSpacingPx()) || hub != null;
    }

    /**
     * Spacing-regime-position axis. Below-regime iff the measured
     * average spacing is strictly below {@link #DENSITY_REGIME_LOWER_PX}
     * (when supplied) OR the dominant hub is under-sized for its fan-out.
     * In-regime iff neither sub-signal trips. Derived ONLY from the existing
     * {@code averageSpacing()} read + the existing {@code detectHubElements}
     * read — NO new {@code LayoutQualityAssessor} metric.
     */
    static boolean belowRegime(LayoutMetrics post, HubExtent hub) {
        boolean avgKnown = !Double.isNaN(post.avgSpacingPx());
        if (avgKnown && post.avgSpacingPx() < DENSITY_REGIME_LOWER_PX) {
            return true;
        }
        return hubUnderSizedForFanOut(hub);
    }

    /**
     * Connections a hub carries ABOVE the smallest "large" hub
     * ({@link #DENSITY_HUB_FANOUT_CONN_THRESHOLD}+1). The fan-out-scaled
     * adequate-hub minimum grows from its HH-like base by this many
     * connection-increments. {@code 0} at (and below) the smallest large hub
     * so the established HH-like base 300×250 is preserved exactly there
     * (zero behaviour change at the anchor; growth ONLY for higher
     * fan-out).
     */
    static int hubFanOutExcess(int connectionCount) {
        return Math.max(0,
                connectionCount - (DENSITY_HUB_FANOUT_CONN_THRESHOLD + 1));
    }

    /**
     * The fan-out-scaled adequate-hub minimum WIDTH for a hub
     * carrying {@code connectionCount} connections: the HH-like base
     * {@link #DENSITY_HUB_MIN_WIDTH_PX} plus
     * {@link #DENSITY_HUB_WIDTH_PER_CONN_PX} per connection beyond the
     * smallest large hub. Monotone non-decreasing in {@code connectionCount}.
     * Pure/static — pinned directly (incl. the exact-{@code (…,17)} headline
     * + curve points) by {@code SpacingControlLoopDensityAwareTerminationTest}.
     * {@link #hubUnderSizedForFanOut(HubExtent)} (the predicate) and
     * {@code ArchiModelAccessorImpl.buildDensityHubResizeCommand} (the resize
     * target) MUST both read THIS — fan-out-scale both or the escalate
     * hub-resize is a no-op loop (the hidden coupling).
     */
    /**
     * The rectangle the one-shot escalate resize must write for a hub sitting at
     * {@code (x, y, w, h)}, or {@code null} when there is nothing to resize.
     *
     * <p>Lives beside {@link #hubUnderSizedForFanOut} and the two floor functions on purpose: the
     * target has to be derived from the SAME connection count the predicate judged, or escalate can
     * flag a hub as under-sized and then resize it to something that is not larger — a no-op loop on
     * the very hub it just diagnosed. Keeping predicate and target in one class makes that
     * consistency a local property instead of an agreement between two files.</p>
     *
     * <p>Each axis takes {@code max} against its own floor, independently. A hub already wider than
     * its width floor but short of its height floor keeps its width: the floors are a minimum the
     * fan-out demands, never a size the resize is entitled to impose downwards.</p>
     *
     * <p>Returns {@code null} rather than a no-op rectangle when neither axis would move. The
     * predicate reads the extent captured for the view's dominant hub while the bounds are the
     * object's own, so the two can disagree; emitting a command that changes nothing would put a
     * rectangle in the response reporting a change that never happened.</p>
     *
     * @return {@code {x, y, newW, newH}} — position carried through untouched — or {@code null}
     */
    static int[] escalateHubResizeRect(HubExtent hub, int x, int y, int w, int h) {
        if (!hubUnderSizedForFanOut(hub)) {
            return null;
        }
        int conns = hub.maxHubConnectionCount();
        int newW = Math.max(w, requiredHubMinWidthPx(conns));
        int newH = Math.max(h, requiredHubMinHeightPx(conns));
        if (newW == w && newH == h) {
            return null;
        }
        return new int[] { x, y, newW, newH };
    }

    static int requiredHubMinWidthPx(int connectionCount) {
        return DENSITY_HUB_MIN_WIDTH_PX
                + DENSITY_HUB_WIDTH_PER_CONN_PX
                        * hubFanOutExcess(connectionCount);
    }

    /**
     * The fan-out-scaled adequate-hub minimum HEIGHT for
     * {@code connectionCount} connections (see
     * {@link #requiredHubMinWidthPx(int)} for the coupling contract).
     */
    static int requiredHubMinHeightPx(int connectionCount) {
        return DENSITY_HUB_MIN_HEIGHT_PX
                + DENSITY_HUB_HEIGHT_PER_CONN_PX
                        * hubFanOutExcess(connectionCount);
    }

    /**
     * Hub sub-signal: a LARGE hub (&gt;
     * {@link #DENSITY_HUB_FANOUT_CONN_THRESHOLD} connections) whose width OR
     * height is below the <strong>fan-out-scaled</strong> adequate minimum
     * for its OWN connection count is under-sized for its fan-out (the ST
     * clone's 214×68 hub on 7+ connections; the HH 300×250 hub
     * on 17 connections — {@code 300 < requiredHubMinWidthPx(17)=400}).
     *
     * <p>Replaced the
     * flat exclusive-{@code <} 300×250 (which evaluated a 300×250/17-conn hub
     * {@code 300<300=false ∧ 250<250=false ⇒ "adequate"} and PASS-honested a
     * clearable HH view) with a fan-out-scaled minimum keyed off
     * {@code maxHubConnectionCount}. The {@code >} fan-out gate, the
     * width-OR-height disjunction, and {@code null}/small-hub semantics are
     * UNCHANGED — calibration only, no 2×2 / enum / objective change.</p>
     */
    static boolean hubUnderSizedForFanOut(HubExtent hub) {
        if (hub == null
                || hub.maxHubConnectionCount()
                        <= DENSITY_HUB_FANOUT_CONN_THRESHOLD) {
            return false;
        }
        int conns = hub.maxHubConnectionCount();
        return hub.hubWidthPx() < requiredHubMinWidthPx(conns)
                || hub.hubHeightPx() < requiredHubMinHeightPx(conns);
    }

    /**
     * Actionable PASS-honest diagnosis — names the violated
     * precondition (measured avgSpacing vs the 100–124px band; hub WxH vs
     * its connection count) and OFFERS a structural reflow as an explicit
     * user-consentable next step. Text/affordance ONLY — the loop never
     * invokes any auto-layout/reflow/RoutingPipeline path.
     * LLM-self-contained.
     */
    static String buildDensityDiagnosis(LayoutMetrics post, HubExtent hub) {
        return buildDensityDiagnosis(post, hub, /*viewpointType=*/ null);
    }

    /**
     * As above, with the OFFERED next step keyed on the view's viewpoint so a
     * view whose element ORDER is load-bearing is never advised to run a
     * connectivity-driven re-layout that would destroy it.
     *
     * <p><strong>The viewpoint keys the REMEDY ONLY — never the decision.</strong>
     * Reaching this method already means the loop classified a PASS-honest
     * density-floor terminal; nothing below can change that classification,
     * the regime gates, or the escalate condition. All that branches is which
     * next step is offered.
     *
     * <p>The order-preserving branch is NOT written here: it is
     * {@link OrderedAxisRemedy}, shared verbatim with the pre-loop
     * infeasibility certificate. Both places offer a structural reflow, for
     * honestly-different reasons, and a view with a load-bearing order can
     * reach EITHER — so the rule has to hold at both or it is not a rule the
     * product has. Copying the branch here instead would re-open exactly that
     * gap.
     *
     * @param viewpointType the view's viewpoint id ({@code null} / unknown ⇒
     *                      byte-identical to the pre-existing diagnosis)
     */
    static String buildDensityDiagnosis(LayoutMetrics post, HubExtent hub,
            String viewpointType) {
        StringBuilder sb = new StringBuilder();
        sb.append("REFLOW REQUIRED: the spacing control loop reached the "
                + "prescribed spacing regime but the ship-gate quality "
                + "aggregate stalled — more spacing/hub-resize cannot improve "
                + "this layout; it needs a structural reflow. ");
        if (!Double.isNaN(post.avgSpacingPx())) {
            sb.append(String.format(
                    "Measured average spacing is %.0fpx (prescribed flow-view "
                    + "band: %d–%dpx). ",
                    post.avgSpacingPx(), DENSITY_REGIME_LOWER_PX,
                    DENSITY_REGIME_EXCELLENT_PX));
        }
        if (hub != null) {
            sb.append(String.format(
                    "The dominant hub is %dx%dpx absorbing %d connections",
                    hub.hubWidthPx(), hub.hubHeightPx(),
                    hub.maxHubConnectionCount()));
            if (hubUnderSizedForFanOut(hub)) {
                int conns = hub.maxHubConnectionCount();
                sb.append(String.format(
                        " — under-sized for that fan-out (HH-like guidance: "
                        + "≥%dx%dpx for %d connections)",
                        requiredHubMinWidthPx(conns),
                        requiredHubMinHeightPx(conns), conns));
            }
            sb.append(". ");
        }
        sb.append("This layout was NOT auto-reflowed (a structural reflow "
                + "moves user-placed elements — an explicit-consent "
                + "boundary). ");
        if (!OrderedAxisRemedy.forbidsReordering(viewpointType)) {
            sb.append("OFFERED next step (requires your consent): re-layout "
                    + "this view with a structural auto-layout, then re-run "
                    + "auto-route-connections.");
        } else {
            // Shared verbatim with the pre-loop certificate's offer. The hub
            // goes across whole: whether enlarging it is a real remedy is the
            // collaborator's call, made with the SAME under-sized predicate
            // the hub sentence above uses, so one message cannot both decline
            // to call a hub under-sized and then tell the reader to enlarge it.
            sb.append(OrderedAxisRemedy.orderPreservingRemedy(
                    viewpointType, hub));
        }
        sb.append(" The current view is preserved "
                + "unchanged (no degraded layout was applied).");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Terminal-reinterpretation helpers. Both are NEW private
    // statics consumed ONLY by the two guarded pre-emptions (the
    // §4 :1027 insertion #1 + the unconditional budget-exhausted :1081
    // insertion #2). They do NOT touch — and are NOT called by — the L888
    // PASS_HONEST branch, the L933 ESCALATE branch, the L987 CONTINUE
    // branch, `classifyDensityTermination`, `DENSITY_TREND_WINDOW`, the
    // `ComposerSpeculativeReplay`/`SwtUiThreadDispatcher` boundary,
    // the fan-out hub sub-signal, or `RouteNormalizedBaseline`
    // (byte-unchanged invariant).
    // ------------------------------------------------------------------

    /**
     * Reinterpretation gate — TRUE iff a regime signal is present
     * AND the view is in-regime. This is the SAME predicate the :882
     * density block uses (`regimeSignalAvailable(...) && !belowRegime(...)`)
     * applied at the two preserved give-up terminals so that, when
     * NO regime signal is present, the guard is inert and those terminals
     * fire byte-identically to the 2-state form (the regime-signal-absent ⇒
     * 2-state-byte-identical invariant; pinned by the regimeAbsent_* pins).
     * {@link #regimeSignalAvailable}/{@link #belowRegime} themselves are
     * UNCHANGED — this only composes them at the new sites.
     */
    private static boolean inRegimeWithSignal(
            LayoutMetrics state, HubExtent hub) {
        return regimeSignalAvailable(state, hub) && !belowRegime(state, hub);
    }

    /**
     * Reinterpretation finalize — terminate the loop on the
     * PASS-honest terminal: emit {@link #REASON_DENSITY_FLOOR_REFLOW_REQUIRED}
     * + the actionable {@link #buildDensityDiagnosis} text, resetting the
     * accepted commands via the existing {@link #finalizeWithReset} 5-arg
     * overload (identical to the call the L888 PASS_HONEST branch makes at
     * :926-930 — shared finalize, NOT a copy of the L888 branch body, which
     * stays byte-unchanged). {@code diagnoseState} is the loop's final
     * post-state at the terminal (loop-local {@code postState} at §4;
     * {@code bestState} at the post-loop budget terminal where the
     * loop-local is out of scope).
     */
    private static Result finalizeAsDensityPassHonest(
            List<SpacingIterationStep> iterations,
            List<SpacingMutationCommand> acceptedCommands,
            int currentSpacing,
            LayoutMetrics diagnoseState,
            HubExtent hub,
            String viewpointType) {
        return finalizeWithReset(iterations, acceptedCommands,
                REASON_DENSITY_FLOOR_REFLOW_REQUIRED, currentSpacing,
                buildDensityDiagnosis(diagnoseState, hub, viewpointType));
    }

    // ------------------------------------------------------------------
    // Internal — finalize with reset (undo accepted commands in reverse
    // order so the caller can push the compound through the stack which
    // will re-run each accepted command's execute() in order, recording
    // ONE undo entry)
    // ------------------------------------------------------------------

    private static Result finalizeWithReset(
            List<SpacingIterationStep> iterations,
            List<SpacingMutationCommand> acceptedCommands,
            String terminationReason,
            int finalSpacing) {
        return finalizeWithReset(iterations, acceptedCommands,
                terminationReason, finalSpacing, /*densityDiagnosis=*/ null);
    }

    /**
     * Overload — carries the actionable density diagnosis on the
     * {@link #REASON_DENSITY_FLOOR_REFLOW_REQUIRED} PASS-honest branch (null
     * on every preserved terminal — the 4-arg form delegates here
     * with null).
     */
    private static Result finalizeWithReset(
            List<SpacingIterationStep> iterations,
            List<SpacingMutationCommand> acceptedCommands,
            String terminationReason,
            int finalSpacing,
            String densityDiagnosis) {

        // Reset model to pre-loop state by undoing accepted commands in
        // reverse. The caller will re-execute via the compound on the
        // public command stack.
        for (int i = acceptedCommands.size() - 1; i >= 0; i--) {
            acceptedCommands.get(i).undo();
        }
        return new Result(iterations, acceptedCommands,
                terminationReason, finalSpacing, densityDiagnosis);
    }
}
