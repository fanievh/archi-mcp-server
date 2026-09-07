package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Predicate;

import org.junit.Test;

/**
 * JUnit pin for {@link SpacingControlLoop}. Pure-unit tests
 * (no OSGi; no transitive class-loading of {@code ArchiModelAccessorImpl}).
 *
 * <p>Covers: empty trajectory (zero iterations),
 * single-iteration-accept, multi-iteration-accept, aggregate-back-off-revert,
 * budget-exhaustion-accept-last, goal-reached-terminate, structural-
 * impossibility-no-change (upstream-handled — verified via heuristic-already-
 * met branch), heuristic-already-met-no-change. This class delivers 16 @Test
 * methods.</p>
 *
 * <p>Sibling-symmetric with {@link SpacingIterationStepTest} and the
 * three existing single-shot decision-record tests.</p>
 */
public class SpacingControlLoopTest {

    // ------------------------------------------------------------------
    // Test infrastructure — fake mutation command + scripted callbacks
    // ------------------------------------------------------------------

    /**
     * FakeMutationCommand records execute/undo calls so tests can verify
     * the loop's command-stack semantics (finalize-with-reset).
     */
    private static class FakeMutationCommand implements SpacingMutationCommand {
        final int id;
        int executeCallCount = 0;
        int undoCallCount = 0;
        /** Shared global order list — captures undo() invocation order
         *  across multiple FakeMutationCommand instances. */
        private final List<Integer> sharedUndoOrder;

        FakeMutationCommand(int id, List<Integer> sharedUndoOrder) {
            this.id = id;
            this.sharedUndoOrder = sharedUndoOrder;
        }

        @Override
        public void execute() {
            executeCallCount++;
        }

        @Override
        public void undo() {
            undoCallCount++;
            if (sharedUndoOrder != null) {
                sharedUndoOrder.add(id);
            }
        }
    }

    /**
     * ScriptedCallbacks returns canned LayoutMetrics from observeLayout()
     * (one per call, in order). Tests build the script to drive the loop
     * through scenarios.
     */
    private static class ScriptedCallbacks
            implements SpacingControlLoop.Callbacks {
        private final List<LayoutMetrics> observationScript;
        private int observationIndex = 0;
        private final List<FakeMutationCommand> commandsBuilt = new ArrayList<>();
        private final List<Integer> sharedUndoOrder = new ArrayList<>();
        private final IntFunction<Boolean> buildPredicate;
        private final Predicate<LayoutMetrics> goalPredicate;
        private int nextCommandId = 0;

        ScriptedCallbacks(List<LayoutMetrics> script,
                IntFunction<Boolean> buildPredicate,
                Predicate<LayoutMetrics> goalPredicate) {
            this.observationScript = script;
            this.buildPredicate = buildPredicate;
            this.goalPredicate = goalPredicate;
        }

        static ScriptedCallbacks alwaysAccepting(LayoutMetrics... script) {
            return new ScriptedCallbacks(
                    new ArrayList<>(Arrays.asList(script)),
                    delta -> Boolean.TRUE,
                    snapshot -> false);
        }

        @Override
        public SpacingMutationCommand buildMutationCommand(int proposedDeltaPx) {
            if (!buildPredicate.apply(proposedDeltaPx)) {
                return null;
            }
            FakeMutationCommand cmd = new FakeMutationCommand(
                    nextCommandId++, sharedUndoOrder);
            commandsBuilt.add(cmd);
            return cmd;
        }

        @Override
        public LayoutMetrics observeLayout() {
            if (observationIndex >= observationScript.size()) {
                throw new AssertionError(
                        "observeLayout called more times than scripted ("
                                + observationScript.size() + ")");
            }
            return observationScript.get(observationIndex++);
        }

        @Override
        public boolean isGoalReached(LayoutMetrics snapshot) {
            return goalPredicate.test(snapshot);
        }
    }

    private static LayoutMetrics metrics(int thresholdsMet, int m4,
            int coincSeg, int edgeCrossings) {
        // Convenience factory; HPQ/boundaryViolations/vp10 default values.
        return new LayoutMetrics(
                thresholdsMet,
                /*hpq=*/ 0.50,
                m4,
                coincSeg,
                /*boundaryViolations=*/ 0,
                /*vp10=*/ 5.0,
                edgeCrossings);
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    // ---- (1) heuristic-already-met short-circuit ----

    @Test
    public void iterate_initialEqualsTarget_returnsImmediateNoChange() {
        LayoutMetrics initial = metrics(1, 8, 4, 100);
        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                /*initialSpacingPx=*/ 80,
                /*targetSpacingPx=*/ 80,   // already met
                /*iterationBudget=*/ 5,
                /*perIterationStepCapPx=*/ Integer.MAX_VALUE,
                initial,
                "element");
        ScriptedCallbacks cb = ScriptedCallbacks.alwaysAccepting();
        SpacingControlLoop.Result result =
                SpacingControlLoop.iterate(request, cb);

        assertEquals(SpacingControlLoop.REASON_HEURISTIC_ALREADY_MET,
                result.terminationReason());
        assertEquals(0, result.iterations().size());
        assertEquals(0, result.acceptedCommands().size());
        assertEquals(80, result.finalSpacingPx());
        assertEquals("observeLayout should NOT be called when short-circuited",
                0, cb.observationIndex);
    }

    // ---- (2) initial > target short-circuit ----

    @Test
    public void iterate_initialGreaterThanTarget_returnsImmediateNoChange() {
        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                100, 80, 5, Integer.MAX_VALUE,
                metrics(1, 8, 4, 100), "element");
        SpacingControlLoop.Result result = SpacingControlLoop.iterate(
                request, ScriptedCallbacks.alwaysAccepting());

        assertEquals(SpacingControlLoop.REASON_HEURISTIC_ALREADY_MET,
                result.terminationReason());
        assertEquals(100, result.finalSpacingPx());
    }

    // ---- (3) zero budget → budget-exhausted-0 ----

    @Test
    public void iterate_zeroBudget_returnsBudgetExhaustedZero() {
        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                40, 100, 0, Integer.MAX_VALUE,
                metrics(1, 8, 4, 100), "element");
        SpacingControlLoop.Result result = SpacingControlLoop.iterate(
                request, ScriptedCallbacks.alwaysAccepting());

        assertEquals("budget_exhausted_after_0_iterations",
                result.terminationReason());
        assertEquals(0, result.iterations().size());
    }

    // ---- (4) single-iteration-accept reaches target → ladder exhausted ----

    @Test
    public void iterate_singleAccept_targetReached_terminatesBudgetExhausted() {
        // gap=20 — iteration 0 ladder proposes min(30, 20) = 20; accepted;
        // currentSpacing reaches target → next iteration's decideNextStep
        // returns keepGoing=false; loop reports budget-exhausted-after-1.
        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                40, 60, 5, Integer.MAX_VALUE,
                metrics(0, 12, 5, 152), "element");
        // Improvement: thresholdsMet 0 → 1 (M4 8→4)
        ScriptedCallbacks cb = ScriptedCallbacks.alwaysAccepting(
                metrics(1, 4, 2, 140));
        SpacingControlLoop.Result result =
                SpacingControlLoop.iterate(request, cb);

        assertEquals("budget_exhausted_after_1_iterations",
                result.terminationReason());
        assertEquals(1, result.iterations().size());
        assertEquals(1, result.acceptedCommands().size());
        assertEquals(20, result.iterations().get(0).deltaApplied());
        assertEquals(60, result.finalSpacingPx());
    }

    // ---- (5) multi-iteration-accept (3 accepts, budget=3) ----

    @Test
    public void iterate_threeAccepts_budgetExhausted_keepsAllAccepted() {
        // gap=120; budget=3; ladder produces 30 + 40 + 50 = 120 cumulative.
        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                40, 160, 3, Integer.MAX_VALUE,
                metrics(0, 12, 5, 152), "element");
        ScriptedCallbacks cb = ScriptedCallbacks.alwaysAccepting(
                metrics(1, 8, 4, 140),
                metrics(2, 4, 2, 130),
                metrics(2, 4, 2, 130));
        SpacingControlLoop.Result result =
                SpacingControlLoop.iterate(request, cb);

        assertEquals("budget_exhausted_after_3_iterations",
                result.terminationReason());
        assertEquals(3, result.acceptedCommands().size());
        assertEquals(30, result.iterations().get(0).deltaApplied());
        assertEquals(40, result.iterations().get(1).deltaApplied());
        assertEquals(50, result.iterations().get(2).deltaApplied());
        assertEquals(160, result.finalSpacingPx());
    }

    // ---- (6) aggregate back-off at iteration 1 (after one accepted) ----

    @Test
    public void iterate_backOffAtIteration1_revertsAndHalts() {
        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                40, 200, 5, Integer.MAX_VALUE,
                metrics(0, 12, 5, 152), "group");
        // iter 0 accepted (thresholdsMet 0 → 1); iter 1 regresses (1 → 0)
        ScriptedCallbacks cb = ScriptedCallbacks.alwaysAccepting(
                metrics(1, 8, 4, 140),   // accept
                metrics(0, 9, 6, 150));  // regress — should be reverted
        SpacingControlLoop.Result result =
                SpacingControlLoop.iterate(request, cb);

        assertEquals(
                "aggregate_threshold_regressed_at_iteration_1"
                        + "_reverted_to_iteration_0",
                result.terminationReason());
        assertEquals(2, result.iterations().size());      // both attempts recorded
        assertEquals(1, result.acceptedCommands().size()); // only iter 0 accepted
        assertFalse(result.iterations().get(0).backedOff());
        assertTrue(result.iterations().get(1).backedOff());
        assertNotNull(result.iterations().get(1).backOffReason());
        assertEquals(70, result.finalSpacingPx());        // 40 + 30 (only iter 0)
        // iter 1's command should have been built + executed + undone:
        FakeMutationCommand iter1Cmd = cb.commandsBuilt.get(1);
        assertEquals(1, iter1Cmd.executeCallCount);
        assertEquals(1, iter1Cmd.undoCallCount);
    }

    // ---- (7) aggregate back-off at iteration 0 (regress from initial) ----

    @Test
    public void iterate_backOffAtIteration0_revertsToInitialState() {
        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                40, 160, 5, Integer.MAX_VALUE,
                metrics(2, 4, 2, 100), "group");
        // iter 0 regresses (thresholdsMet 2 → 0)
        ScriptedCallbacks cb = ScriptedCallbacks.alwaysAccepting(
                metrics(0, 12, 5, 152));
        SpacingControlLoop.Result result =
                SpacingControlLoop.iterate(request, cb);

        assertEquals(
                "aggregate_threshold_regressed_at_iteration_0"
                        + "_reverted_to_initial_state",
                result.terminationReason());
        assertEquals(1, result.iterations().size());
        assertEquals(0, result.acceptedCommands().size());
        assertTrue(result.iterations().get(0).backedOff());
        assertEquals(40, result.finalSpacingPx());  // unchanged
    }

    // ---- (8) budget exhausted exactly at budget=5 ----

    @Test
    public void iterate_budgetExhaustedAtBudgetLimit_keepsAllFiveAccepted() {
        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                40, 400, 5, Integer.MAX_VALUE,    // gap=360 — won't run out
                metrics(0, 12, 5, 152), "element");
        ScriptedCallbacks cb = ScriptedCallbacks.alwaysAccepting(
                metrics(1, 8, 4, 140),
                metrics(2, 4, 2, 130),
                metrics(2, 4, 2, 125),
                metrics(2, 4, 2, 120),
                metrics(2, 4, 2, 115));
        SpacingControlLoop.Result result =
                SpacingControlLoop.iterate(request, cb);

        assertEquals("budget_exhausted_after_5_iterations",
                result.terminationReason());
        assertEquals(5, result.acceptedCommands().size());
        // Ladder: 30, 40, 50, 60, 70 = 250 cumulative → final = 40 + 250 = 290
        assertEquals(290, result.finalSpacingPx());
    }

    // ---- (9) goal-reached predicate terminates ----

    @Test
    public void iterate_goalReachedAfterIteration1_terminatesGoalReached() {
        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                40, 400, 5, Integer.MAX_VALUE,
                metrics(0, 12, 5, 152), "element");
        // iter 0 accept (m4=8 still > goal 4); iter 1 accept (m4=4 hits goal)
        Predicate<LayoutMetrics> goal = snap -> snap.m4() <= 4;
        ScriptedCallbacks cb = new ScriptedCallbacks(
                new ArrayList<>(Arrays.asList(
                        metrics(1, 8, 4, 140),
                        metrics(2, 4, 2, 130))),
                delta -> Boolean.TRUE,
                goal);
        SpacingControlLoop.Result result =
                SpacingControlLoop.iterate(request, cb);

        assertEquals("goal_reached_at_iteration_1",
                result.terminationReason());
        assertEquals(2, result.acceptedCommands().size());
    }

    // ---- (10) buildMutationCommand returns null → budget-exhausted ----

    @Test
    public void iterate_buildReturnsNullAtIteration0_terminatesBudgetExhausted() {
        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                40, 100, 5, Integer.MAX_VALUE,
                metrics(0, 12, 5, 152), "element");
        ScriptedCallbacks cb = new ScriptedCallbacks(
                new ArrayList<>(), delta -> Boolean.FALSE, snap -> false);
        SpacingControlLoop.Result result =
                SpacingControlLoop.iterate(request, cb);

        assertEquals("budget_exhausted_after_0_iterations",
                result.terminationReason());
        assertEquals(0, result.acceptedCommands().size());
    }

    // ---- (11) per-iteration step cap clamps to cap value (composer variant) ----

    @Test
    public void iterate_perIterationStepCap_clampsAtCap() {
        // Cap = 20; iteration 0 ladder proposes 30; cap clamps to 20.
        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                40, 200, 3, /*perIterationStepCapPx=*/ 20,
                metrics(0, 12, 5, 152), "composer.element");
        ScriptedCallbacks cb = ScriptedCallbacks.alwaysAccepting(
                metrics(1, 8, 4, 140),
                metrics(2, 4, 2, 130),
                metrics(2, 4, 2, 125));
        SpacingControlLoop.Result result =
                SpacingControlLoop.iterate(request, cb);

        // All three iterations clamped to 20
        for (SpacingIterationStep step : result.iterations()) {
            assertEquals(
                    "step " + step.stepIndex() + " should be capped at 20",
                    20, step.deltaApplied());
        }
        assertEquals(3 * 20, result.finalSpacingPx() - 40);
    }

    // ---- (12) tie-break — equal thresholdsMet, lower M4 accepts ----

    @Test
    public void iterate_tieBreakLowerM4_acceptsImprovement() {
        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                40, 200, 2, Integer.MAX_VALUE,
                metrics(1, 8, 4, 100), "element");
        // iter 0: thresholdsMet same (1) but m4 lower (4 < 8) → ACCEPT
        // iter 1: thresholdsMet same (1) but m4 lower again (2 < 4) → ACCEPT
        ScriptedCallbacks cb = ScriptedCallbacks.alwaysAccepting(
                metrics(1, 4, 4, 100),
                metrics(1, 2, 4, 100));
        SpacingControlLoop.Result result =
                SpacingControlLoop.iterate(request, cb);

        assertEquals("budget_exhausted_after_2_iterations",
                result.terminationReason());
        assertEquals(2, result.acceptedCommands().size());
    }

    // ---- (13) tie-break — fully tied, status quo ACCEPTed ----

    @Test
    public void iterate_tieBreakAllEqual_acceptsStatusQuo() {
        LayoutMetrics flat = metrics(1, 8, 4, 100);
        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                40, 100, 1, Integer.MAX_VALUE, flat, "element");
        // iter 0: all metrics identical → ACCEPT per status-quo rule
        ScriptedCallbacks cb = ScriptedCallbacks.alwaysAccepting(flat);
        SpacingControlLoop.Result result =
                SpacingControlLoop.iterate(request, cb);

        assertEquals("budget_exhausted_after_1_iterations",
                result.terminationReason());
        assertEquals(1, result.acceptedCommands().size());
        assertFalse(result.iterations().get(0).backedOff());
    }

    // ---- (14) finalize-with-reset undoes accepted commands in reverse order ----

    @Test
    public void iterate_acceptedCommands_undoneInReverseOrder_forCompoundReExec() {
        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                40, 400, 3, Integer.MAX_VALUE,
                metrics(0, 12, 5, 152), "element");
        ScriptedCallbacks cb = ScriptedCallbacks.alwaysAccepting(
                metrics(1, 8, 4, 140),
                metrics(2, 4, 2, 130),
                metrics(2, 4, 2, 125));
        SpacingControlLoop.Result result =
                SpacingControlLoop.iterate(request, cb);

        assertEquals(3, result.acceptedCommands().size());
        // Each accepted command should have execute=1 + undo=1 (loop's
        // finalize-with-reset undoes them so caller's compound re-executes).
        for (int i = 0; i < cb.commandsBuilt.size(); i++) {
            FakeMutationCommand cmd = cb.commandsBuilt.get(i);
            assertEquals("cmd[" + i + "].executeCallCount",
                    1, cmd.executeCallCount);
            assertEquals("cmd[" + i + "].undoCallCount",
                    1, cmd.undoCallCount);
        }
        // The shared sharedUndoOrder list should be [2, 1, 0] (reverse).
        assertEquals(Arrays.asList(2, 1, 0), cb.sharedUndoOrder);
    }

    // ---- (15) iteration-step record captures pre/post states correctly ----

    @Test
    public void iterate_iterationStepRecord_capturesPreAndPostStatesCorrectly() {
        LayoutMetrics initial = metrics(0, 12, 5, 152);
        LayoutMetrics after0 = metrics(1, 8, 4, 140);
        LayoutMetrics after1 = metrics(2, 4, 2, 130);
        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                40, 200, 2, Integer.MAX_VALUE, initial, "element");
        ScriptedCallbacks cb = ScriptedCallbacks.alwaysAccepting(after0, after1);
        SpacingControlLoop.Result result =
                SpacingControlLoop.iterate(request, cb);

        SpacingIterationStep step0 = result.iterations().get(0);
        assertEquals(initial, step0.preStateMetrics());
        assertEquals(after0, step0.postStateMetrics());
        assertEquals(0, step0.thresholdsMetBefore());
        assertEquals(1, step0.thresholdsMetAfter());
        assertEquals(1, step0.thresholdsMetDelta());
        assertFalse(step0.backedOff());
        assertNull(step0.backOffReason());

        SpacingIterationStep step1 = result.iterations().get(1);
        // step1's pre-state is step0's accepted post-state
        assertEquals(after0, step1.preStateMetrics());
        assertEquals(after1, step1.postStateMetrics());
    }

    // ---- (16) null arguments → NPE ----

    @Test
    public void iterate_nullRequest_throwsNpe() {
        try {
            SpacingControlLoop.iterate(null, ScriptedCallbacks.alwaysAccepting());
            fail("Expected NPE for null request");
        } catch (NullPointerException expected) {
            assertTrue(expected.getMessage().contains("request"));
        }
    }

    @Test
    public void iterate_nullCallbacks_throwsNpe() {
        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                40, 100, 5, Integer.MAX_VALUE,
                metrics(0, 12, 5, 152), "element");
        try {
            SpacingControlLoop.iterate(request, null);
            fail("Expected NPE for null callbacks");
        } catch (NullPointerException expected) {
            assertTrue(expected.getMessage().contains("callbacks"));
        }
    }

    // ------------------------------------------------------------------
    // cmd.execute() partial-throw recovery + new
    // REASON_ITERATION_APPLY_FAILED_PREFIX terminationReason
    // branch. Sibling pin to SpacingControlLoopPartialCommitRegressionTest
    // tests 6/7/8; verifies the pin coverage extends across the new
    // graceful-degradation branch.
    // ------------------------------------------------------------------

    @Test
    public void iterate_cmdExecuteThrowsAtIteration1_terminatesIterationApplyFailedBranch() {
        // GIVEN: iteration 0 accepts; iteration 1's cmd.execute() throws.
        SpacingControlLoop.Callbacks executeThrowsAtIter1 =
                new SpacingControlLoop.Callbacks() {
            int buildCount = 0;
            int observeCount = 0;
            final FakeMutationCommand iter0Cmd =
                    new FakeMutationCommand(0, new ArrayList<>());

            @Override
            public SpacingMutationCommand buildMutationCommand(int delta) {
                if (buildCount++ == 0) {
                    return iter0Cmd;
                }
                // iteration 1: return cmd whose execute() throws.
                return new SpacingMutationCommand() {
                    @Override
                    public void execute() {
                        throw new RuntimeException("simulated route NPE");
                    }

                    @Override
                    public void undo() {
                        // No-op best-effort rollback.
                    }
                };
            }

            @Override
            public LayoutMetrics observeLayout() {
                // Only called for iteration 0 (iter 1 throws before observe).
                if (observeCount++ == 0) {
                    return metrics(/*thresholdsMet=*/ 1, /*m4=*/ 8,
                            /*coincSeg=*/ 5, /*edgeCrossings=*/ 100);
                }
                throw new AssertionError(
                        "observeLayout should not be called after iter 1 throw");
            }
        };

        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                /*initialSpacingPx=*/ 40,
                /*targetSpacingPx=*/ 200,
                /*iterationBudget=*/ 5,
                /*perIterationStepCapPx=*/ Integer.MAX_VALUE,
                /*initialMetrics=*/ metrics(/*thresholdsMet=*/ 0, /*m4=*/ 12,
                        /*coincSeg=*/ 5, /*edgeCrossings=*/ 152),
                /*toolLabel=*/ "element");

        // WHEN
        SpacingControlLoop.Result result;
        try {
            result = SpacingControlLoop.iterate(request, executeThrowsAtIter1);
        } catch (RuntimeException unexpected) {
            fail("the partial-commit patch should recover from cmd.execute() throw. "
                    + "Got: " + unexpected.getMessage());
            return;
        }

        // THEN
        assertEquals("terminationReason names iter 1 + 1 accepted",
                "iteration_apply_failed_at_iteration_1_reverted_after_1_accepted_iterations",
                result.terminationReason());
        assertEquals("one accepted command (iter 0)",
                1, result.acceptedCommands().size());
        // 2 iteration records: iter 0 (accepted) + iter 1 (failed backed-off)
        assertEquals("two iteration records",
                2, result.iterations().size());
        assertTrue("iter 1 marked backedOff",
                result.iterations().get(1).backedOff());
    }

    // ---- (17) Density-aware-termination preservation pin ----

    /**
     * Regression-preservation pin: every test in this class uses the 7-arg
     * {@code metrics(...)} helper (avgSpacingPx = NaN) + the 6-arg
     * {@code Request} (hubExtent = null) → the density-aware discriminator
     * is INERT and the loop is byte-identical to the 2-state
     * back-off. This pin makes that invariant explicit on the canonical
     * first-iteration-regression branch and asserts the new
     * {@code densityDiagnosis} field stays null on every preserved
     * terminal.
     */
    @Test
    public void densityAware_regimeSignalAbsent_row703BackOffPreserved() {
        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                /*initialSpacingPx=*/ 40,
                /*targetSpacingPx=*/ 100,
                /*iterationBudget=*/ 5,
                /*perIterationStepCapPx=*/ Integer.MAX_VALUE,
                /*initialMetrics=*/ metrics(/*thresholdsMet=*/ 3, /*m4=*/ 8,
                        /*coincSeg=*/ 4, /*edgeCrossings=*/ 100),
                /*toolLabel=*/ "element");
        // Single observation regressing 3 → 1.
        ScriptedCallbacks cb = ScriptedCallbacks.alwaysAccepting(
                metrics(/*thresholdsMet=*/ 1, 9, 6, 110));

        SpacingControlLoop.Result result =
                SpacingControlLoop.iterate(request, cb);

        assertEquals(
                SpacingControlLoop.REASON_AGGREGATE_REGRESSED_PREFIX + "0"
                        + SpacingControlLoop.REASON_REVERTED_TO_INITIAL,
                result.terminationReason());
        assertNull("row-703 preserved terminal never carries a density "
                + "diagnosis", result.densityDiagnosis());
    }
}
