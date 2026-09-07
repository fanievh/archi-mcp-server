package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.gef.commands.Command;
import org.junit.Test;

/**
 * Regression pin for the root-cause fix of the disposition B
 * (REGRESSION) finding captured in the 2026-05-15 empirical run.
 *
 * <p><strong>The bug (pre-fix):</strong> when
 * {@code ArchiModelAccessorImpl.computeAdjustViewSpacing(...)} threw any
 * {@code RuntimeException} between step 7's first
 * {@code mutationDispatcher.dispatchImmediate(spacingCompound)} and step 10's
 * {@code mutationDispatcher.undo(undoCount)}, the temp dispatch was never
 * undone. The model retained the partially-applied spacing (and optionally
 * route) state; {@code versionCounter} had already advanced via the
 * {@code onImmediateDispatchCallback}; subsequent control-loop iterations
 * observed stale {@code currentSpacing} via {@link ApplyElementSpacingDecision}
 * /{@link ApplyGroupSpacingDecision}, which classified the heuristic as
 * already-met and short-circuited with {@code heuristic_already_met_no_change}.
 * The tool surfaced the inner RuntimeException as MCP {@code INTERNAL_ERROR}
 * via the accessor's outer-catch block. All four symptoms were observed
 * deterministically across 6 comparison runs (HH × 3 + ST × 3).</p>
 *
 * <p><strong>The fix:</strong>
 * <ol>
 *   <li><strong>Safety:</strong> wrap steps 7–10 of
 *       {@code computeAdjustViewSpacing} in {@code try-finally} so the
 *       temp-dispatch undo runs even on exception. This eliminates the
 *       partial-commit symptom for BOTH the single-shot {@code adjustViewSpacing}
 *       path AND the control-loop path.</li>
 *   <li><strong>Graceful degradation:</strong> the three 5-arg
 *       control-loop closures ({@code applyElementSpacingRecommendations} +
 *       {@code applyGroupSpacingRecommendations} + composer
 *       {@code runComposerArm}) now {@code catch (RuntimeException)} in
 *       {@code buildMutationCommand} and return {@code null}, which the
 *       loop interprets as "ladder exhausted" per its existing contract
 *       (verified by the pin class
 *       {@link SpacingControlLoopTest#iterate_buildReturnsNullAtIteration0_terminatesBudgetExhausted}
 *       and the multi-iteration accept tests). The tool returns a valid
 *       response with {@code terminationReason =
 *       budget_exhausted_after_N_iterations} preserving any earlier-iteration
 *       accepted progress, instead of propagating as
 *       {@code INTERNAL_ERROR}.</li>
 * </ol></p>
 *
 * <p><strong>What these tests pin:</strong>
 * <ol>
 *   <li>The loop's contract that a {@code Callbacks} which throws
 *       {@code RuntimeException} from {@code buildMutationCommand} causes the
 *       exception to PROPAGATE to the caller. This is intentional — the
 *       caller (accessor closure) is the layer responsible for graceful
 *       degradation. If a future refactor inadvertently
 *       wraps the throw inside the loop, this test will fail loudly.</li>
 *   <li>The loop's contract that a {@code Callbacks} which RETURNS
 *       {@code null} from {@code buildMutationCommand} (the accessor's
 *       graceful-degradation path) causes the loop to
 *       terminate with {@code budget_exhausted_after_N_iterations}
 *       preserving any earlier-iteration accepted commands. This is the
 *       observable behavior the accessor closure relies on.</li>
 *   <li>The graceful-degradation pattern composes correctly across multiple
 *       iterations — a {@code Callbacks} that accepts iteration 0 then
 *       returns null at iteration 1 (simulating a helper throw after
 *       iteration 0 succeeded) preserves iteration 0's accepted command in
 *       the {@code Result.acceptedCommands()} list AND emits
 *       {@code budget_exhausted_after_1_iterations}.</li>
 *   <li>The partial-commit symptom from the empirical is no
 *       longer observable at the loop level: the loop's
 *       {@code finalizeWithReset(...)} contract undoes accepted commands in
 *       reverse order before returning, regardless of how termination was
 *       reached.</li>
 * </ol></p>
 *
 * <p><strong>What these tests do NOT pin:</strong> the {@code try-finally}
 * around steps 7–10 of {@code computeAdjustViewSpacing} itself. That
 * private helper requires real EMF + a {@code MutationDispatcher} bound to
 * a {@code CommandStack}; it lives in the
 * {@code ArchiModelAccessorImpl} class which transitively requires
 * {@code ArchiPlugin.getInstance()} for class-loading (see
 * {@link SpacingControlLoopUndoIntegrationTest} javadoc § "Substrate
 * substitution"). The {@code try-finally} fix is validated end-to-end by
 * the re-fired paired-arc empirical; if the
 * re-empirical disposition resolves to parity OR improvement, the
 * try-finally is empirically validated. If the re-empirical still shows
 * partial-commit symptoms, this test class will be extended with a
 * targeted helper-test using a custom-built {@code MutationDispatcher}
 * stub.</p>
 *
 * <p>Narrow root-cause fix. Sibling-symmetric with
 * existing {@link SpacingControlLoopTest} pin class.</p>
 */
public class SpacingControlLoopPartialCommitRegressionTest {

    private static final int BUDGET_DEFAULT = 5;
    private static final int STEP_CAP_DEFAULT = Integer.MAX_VALUE;

    private static LayoutMetrics metrics(int thresholdsMet, int m4) {
        return new LayoutMetrics(
                /*thresholdsMet=*/ thresholdsMet,
                /*hpq=*/ 0.50,
                /*m4=*/ m4,
                /*coincidentSegmentCount=*/ 5,
                /*boundaryViolations=*/ 0,
                /*vp10=*/ 0.0,
                /*edgeCrossings=*/ 100);
    }

    /**
     * Minimal mutation command that records its execute / undo calls.
     */
    private static class TrackedCmd implements SpacingMutationCommand {
        final int id;
        int executes = 0;
        int undos = 0;
        final List<Integer> sharedExecOrder;
        final List<Integer> sharedUndoOrder;

        TrackedCmd(int id, List<Integer> execOrder, List<Integer> undoOrder) {
            this.id = id;
            this.sharedExecOrder = execOrder;
            this.sharedUndoOrder = undoOrder;
        }

        @Override
        public void execute() {
            executes++;
            if (sharedExecOrder != null) {
                sharedExecOrder.add(id);
            }
        }

        @Override
        public void undo() {
            undos++;
            if (sharedUndoOrder != null) {
                sharedUndoOrder.add(id);
            }
        }
    }

    // ------------------------------------------------------------------
    // Pin 1 — Throw-from-build propagates to caller (closure is the
    //          mandated handler).
    // ------------------------------------------------------------------

    @Test
    public void buildMutationCommand_throwsRuntimeException_propagatesToCaller() {
        // GIVEN: a Callbacks whose buildMutationCommand throws on the FIRST
        // invocation, simulating a helper RuntimeException (e.g., NPE in
        // routing pipeline) WITHOUT the closure's catch.
        RuntimeException expected = new RuntimeException(
                "simulated helper failure");
        SpacingControlLoop.Callbacks throwingCallbacks =
                new SpacingControlLoop.Callbacks() {
            @Override
            public SpacingMutationCommand buildMutationCommand(int delta) {
                throw expected;
            }

            @Override
            public LayoutMetrics observeLayout() {
                throw new AssertionError(
                        "observeLayout should not be called after "
                        + "buildMutationCommand threw");
            }
        };

        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                /*initialSpacingPx=*/ 50,
                /*targetSpacingPx=*/ 100,
                /*iterationBudget=*/ BUDGET_DEFAULT,
                /*perIterationStepCapPx=*/ STEP_CAP_DEFAULT,
                /*initialMetrics=*/ metrics(/*thresholdsMet=*/ 0, /*m4=*/ 12),
                /*toolLabel=*/ "regression-test");

        // WHEN
        try {
            SpacingControlLoop.iterate(request, throwingCallbacks);
            fail("Expected RuntimeException to propagate so caller's "
                    + "closure can handle it");
        } catch (RuntimeException actual) {
            // THEN: the exception is propagated AS-IS (no wrapping).
            // The accessor's 5-arg closures catch this layer
            // and convert it to a null return
            // (= "ladder exhausted" signal).
            assertSame("loop must NOT swallow RuntimeException — closure "
                    + "is the mandated handler",
                    expected, actual);
        }
    }

    // ------------------------------------------------------------------
    // Pin 2 — Build-returns-null at iteration 0 terminates with
    //          budget_exhausted_after_0_iterations (the closure's
    //          graceful-degradation outcome when helper throws on first
    //          iteration).
    // ------------------------------------------------------------------

    @Test
    public void buildMutationCommand_returnsNullAtIteration0_terminatesBudgetExhausted0() {
        // GIVEN: a Callbacks whose buildMutationCommand returns null on the
        // FIRST invocation, simulating the closure catching RuntimeException
        // on iteration 0 (helper threw after the try-finally fix).
        AtomicInteger buildCount = new AtomicInteger(0);
        SpacingControlLoop.Callbacks gracefulCallbacks =
                new SpacingControlLoop.Callbacks() {
            @Override
            public SpacingMutationCommand buildMutationCommand(int delta) {
                buildCount.incrementAndGet();
                return null;
            }

            @Override
            public LayoutMetrics observeLayout() {
                throw new AssertionError(
                        "observeLayout should not be called when build "
                        + "returned null");
            }
        };

        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                /*initialSpacingPx=*/ 50,
                /*targetSpacingPx=*/ 100,
                /*iterationBudget=*/ BUDGET_DEFAULT,
                /*perIterationStepCapPx=*/ STEP_CAP_DEFAULT,
                /*initialMetrics=*/ metrics(/*thresholdsMet=*/ 0, /*m4=*/ 12),
                /*toolLabel=*/ "regression-test");

        // WHEN
        SpacingControlLoop.Result result =
                SpacingControlLoop.iterate(request, gracefulCallbacks);

        // THEN: loop terminates gracefully with no accepted iterations.
        assertEquals("build invoked exactly once before termination",
                1, buildCount.get());
        assertTrue("no iterations recorded",
                result.iterations().isEmpty());
        assertTrue("no accepted commands",
                result.acceptedCommands().isEmpty());
        assertEquals("terminationReason = budget_exhausted_after_0_iterations",
                "budget_exhausted_after_0_iterations",
                result.terminationReason());
        assertEquals("finalSpacingPx preserved at initial",
                50, result.finalSpacingPx());
    }

    // ------------------------------------------------------------------
    // Pin 3 — Build returns null at iteration N>0 preserves
    //          earlier-iteration accepted progress + terminates with
    //          budget_exhausted_after_N_iterations.
    // ------------------------------------------------------------------

    @Test
    public void buildMutationCommand_returnsNullAtIteration1_preservesIteration0Accepted() {
        // GIVEN: a Callbacks that accepts iteration 0 (returns a tracked
        // command, observation script shows improvement) then returns null
        // at iteration 1 (simulating closure catching RuntimeException after
        // first iteration succeeded).
        List<Integer> sharedExecOrder = new ArrayList<>();
        List<Integer> sharedUndoOrder = new ArrayList<>();
        TrackedCmd iter0Cmd = new TrackedCmd(0, sharedExecOrder, sharedUndoOrder);

        SpacingControlLoop.Callbacks degradeAtIter1 =
                new SpacingControlLoop.Callbacks() {
            int buildCount = 0;
            int observeCount = 0;

            @Override
            public SpacingMutationCommand buildMutationCommand(int delta) {
                if (buildCount++ == 0) {
                    return iter0Cmd;
                }
                // Simulate closure catching RuntimeException at iteration 1
                return null;
            }

            @Override
            public LayoutMetrics observeLayout() {
                observeCount++;
                // iteration 0's post-state: thresholdsMet improved 0 → 1.
                return metrics(/*thresholdsMet=*/ 1, /*m4=*/ 8);
            }
        };

        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                /*initialSpacingPx=*/ 50,
                /*targetSpacingPx=*/ 200,
                /*iterationBudget=*/ BUDGET_DEFAULT,
                /*perIterationStepCapPx=*/ STEP_CAP_DEFAULT,
                /*initialMetrics=*/ metrics(/*thresholdsMet=*/ 0, /*m4=*/ 12),
                /*toolLabel=*/ "regression-test");

        // WHEN
        SpacingControlLoop.Result result =
                SpacingControlLoop.iterate(request, degradeAtIter1);

        // THEN: iteration 0's command is preserved in acceptedCommands;
        // loop reports budget_exhausted_after_1_iterations.
        assertEquals("one iteration recorded (iteration 0)",
                1, result.iterations().size());
        assertEquals("one accepted command (iteration 0)",
                1, result.acceptedCommands().size());
        assertSame("iteration 0's exact command instance preserved",
                iter0Cmd, result.acceptedCommands().get(0));
        assertEquals("terminationReason = budget_exhausted_after_1_iterations",
                "budget_exhausted_after_1_iterations",
                result.terminationReason());
        // Iteration 0's command was executed once (loop's APPLY step) then
        // undone once (finalizeWithReset). The caller (accessor) re-executes
        // it via the outer NonNotifyingCompoundCommand on the public command
        // stack — that happens OUTSIDE the loop body so it's not counted
        // here. Per the single-undo contract documented in the
        // SpacingControlLoop class javadoc.
        assertEquals("iteration 0 executed once by loop's APPLY",
                1, iter0Cmd.executes);
        assertEquals("iteration 0 undone once by finalizeWithReset",
                1, iter0Cmd.undos);
    }

    // ------------------------------------------------------------------
    // Pin 4 — Build returns null at iteration 2 preserves iterations 0+1
    //          in REVERSE-ORDER undo for re-execute-via-outer-compound.
    // ------------------------------------------------------------------

    @Test
    public void buildMutationCommand_returnsNullAtIteration2_acceptedCommandsUndoneInReverseOrder() {
        // GIVEN: a Callbacks that accepts iterations 0 and 1 (each shows
        // thresholdsMet improvement) then returns null at iteration 2.
        List<Integer> sharedExecOrder = new ArrayList<>();
        List<Integer> sharedUndoOrder = new ArrayList<>();
        TrackedCmd iter0Cmd = new TrackedCmd(0, sharedExecOrder, sharedUndoOrder);
        TrackedCmd iter1Cmd = new TrackedCmd(1, sharedExecOrder, sharedUndoOrder);

        SpacingControlLoop.Callbacks degradeAtIter2 =
                new SpacingControlLoop.Callbacks() {
            int buildCount = 0;
            int observeCount = 0;
            final LayoutMetrics[] script = {
                    metrics(/*thresholdsMet=*/ 1, /*m4=*/ 8),  // post-iter-0
                    metrics(/*thresholdsMet=*/ 2, /*m4=*/ 6),  // post-iter-1
            };

            @Override
            public SpacingMutationCommand buildMutationCommand(int delta) {
                int idx = buildCount++;
                if (idx == 0) return iter0Cmd;
                if (idx == 1) return iter1Cmd;
                return null; // graceful degradation at iteration 2
            }

            @Override
            public LayoutMetrics observeLayout() {
                return script[observeCount++];
            }
        };

        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                /*initialSpacingPx=*/ 50,
                /*targetSpacingPx=*/ 300,
                /*iterationBudget=*/ BUDGET_DEFAULT,
                /*perIterationStepCapPx=*/ STEP_CAP_DEFAULT,
                /*initialMetrics=*/ metrics(/*thresholdsMet=*/ 0, /*m4=*/ 12),
                /*toolLabel=*/ "regression-test");

        // WHEN
        SpacingControlLoop.Result result =
                SpacingControlLoop.iterate(request, degradeAtIter2);

        // THEN: two iterations recorded; termination = budget_exhausted_after_2_iterations.
        assertEquals("two iterations recorded", 2, result.iterations().size());
        assertEquals("two accepted commands",
                2, result.acceptedCommands().size());
        assertEquals("terminationReason = budget_exhausted_after_2_iterations",
                "budget_exhausted_after_2_iterations",
                result.terminationReason());

        // Execute order: iter-0 then iter-1.
        assertEquals("execute order: 0, 1",
                List.of(0, 1), sharedExecOrder);
        // Undo order: iter-1 then iter-0 (REVERSE) per
        // SpacingControlLoop.finalizeWithReset contract — caller's outer
        // NonNotifyingCompoundCommand re-executes them in forward order via
        // the public command stack (single-undo).
        assertEquals("undo order: 1, 0 (reverse for compound re-execution)",
                List.of(1, 0), sharedUndoOrder);
    }

    // ------------------------------------------------------------------
    // Pin 5 — Graceful-degradation wrapper pattern: wrap a throwing
    //          callback in a catch-RuntimeException-return-null wrapper
    //          and verify the loop's behavior matches the
    //          buildReturnsNull-at-iteration-0 path.
    // ------------------------------------------------------------------

    @Test
    public void closurePattern_wrappingThrowingCallback_terminatesGracefully() {
        // GIVEN: a "raw" callback that throws RuntimeException on
        // buildMutationCommand (simulates the pre-fix helper failure).
        SpacingControlLoop.Callbacks rawThrowingCallback =
                new SpacingControlLoop.Callbacks() {
            @Override
            public SpacingMutationCommand buildMutationCommand(int delta) {
                throw new RuntimeException("simulated helper failure");
            }

            @Override
            public LayoutMetrics observeLayout() {
                throw new AssertionError("unreachable");
            }
        };

        // Wrap it in the closure pattern: catch RuntimeException +
        // return null. This is the structural shape of the patches in
        // ArchiModelAccessorImpl.applyElementSpacingRecommendations 5-arg /
        // applyGroupSpacingRecommendations 5-arg / runComposerArm.
        SpacingControlLoop.Callbacks gracefulWrapper =
                new SpacingControlLoop.Callbacks() {
            @Override
            public SpacingMutationCommand buildMutationCommand(int delta) {
                try {
                    return rawThrowingCallback.buildMutationCommand(delta);
                } catch (RuntimeException e) {
                    return null; // graceful-degradation pattern
                }
            }

            @Override
            public LayoutMetrics observeLayout() {
                return rawThrowingCallback.observeLayout();
            }
        };

        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                /*initialSpacingPx=*/ 50,
                /*targetSpacingPx=*/ 100,
                /*iterationBudget=*/ BUDGET_DEFAULT,
                /*perIterationStepCapPx=*/ STEP_CAP_DEFAULT,
                /*initialMetrics=*/ metrics(/*thresholdsMet=*/ 0, /*m4=*/ 12),
                /*toolLabel=*/ "regression-test");

        // WHEN: invoking the loop with the wrapped (graceful) callback
        // should NOT propagate.
        SpacingControlLoop.Result result;
        try {
            result = SpacingControlLoop.iterate(request, gracefulWrapper);
        } catch (RuntimeException unexpected) {
            fail("the closure pattern should swallow the inner "
                    + "RuntimeException; loop should NOT see it. "
                    + "Got: " + unexpected.getMessage());
            return; // unreachable; satisfies compiler
        }

        // THEN: same outcome as a callback that returned null on iteration 0.
        assertEquals("terminationReason = budget_exhausted_after_0_iterations",
                "budget_exhausted_after_0_iterations",
                result.terminationReason());
        assertTrue("no iterations recorded",
                result.iterations().isEmpty());
        assertTrue("no accepted commands",
                result.acceptedCommands().isEmpty());
        assertEquals("finalSpacingPx preserved at initial",
                50, result.finalSpacingPx());
    }

    // ------------------------------------------------------------------
    // Pin 6 — root-cause fix:
    //          cmd.execute() RuntimeException recovered via best-effort
    //          cmd.undo() + new iteration_apply_failed termination branch.
    //
    // Pre-fix bug: SpacingControlLoop.iterate() called cmd.execute()
    // UNGUARDED. The helper's mergedCompound is a GEF
    // CompoundCommand which does NOT roll back on partial-throw (when a
    // contained route command NPE/ISEs on post-inflation degenerate
    // geometry). The RuntimeException escaped
    // iterate(), reached the accessor's outer catch, and surfaced as MCP
    // INTERNAL_ERROR — the exact symptom triad observed repeatedly.
    //
    // Fix: cmd.execute() + observeLayout() wrapped in try-catch
    // with best-effort cmd.undo() + new
    // REASON_ITERATION_APPLY_FAILED_PREFIX terminationReason taxonomy
    // branch. Earlier accepted iterations preserved via finalizeWithReset
    // for the caller's outer compound to re-dispatch as a single undo.
    // ------------------------------------------------------------------

    /**
     * Mutation command whose {@code execute()} throws a configurable
     * RuntimeException on a specific invocation count. Used to simulate the
     * GEF CompoundCommand partial-throw behavior (route command NPE mid-
     * compound execution). Records {@code undos} so the test can assert
     * best-effort rollback was attempted.
     */
    private static class ExecuteThrowingCmd implements SpacingMutationCommand {
        final RuntimeException throwOnExecute;
        int executes = 0;
        int undos = 0;
        final List<Integer> sharedExecOrder;
        final List<Integer> sharedUndoOrder;
        final int id;

        ExecuteThrowingCmd(int id, RuntimeException throwOnExecute,
                List<Integer> execOrder, List<Integer> undoOrder) {
            this.id = id;
            this.throwOnExecute = throwOnExecute;
            this.sharedExecOrder = execOrder;
            this.sharedUndoOrder = undoOrder;
        }

        @Override
        public void execute() {
            executes++;
            if (sharedExecOrder != null) {
                sharedExecOrder.add(id);
            }
            throw throwOnExecute;
        }

        @Override
        public void undo() {
            undos++;
            if (sharedUndoOrder != null) {
                sharedUndoOrder.add(id);
            }
        }
    }

    @Test
    public void cmdExecuteThrowsRuntimeException_terminatesGracefullyAndPreservesPriorAcceptedCommands() {
        // GIVEN: a Callbacks that accepts iterations 0 + 1 then returns a
        // cmd at iteration 2 whose execute() throws RuntimeException
        // (simulating GEF CompoundCommand partial-throw on a route command
        // NPE mid-application — the symptom triad's actual throw site).
        List<Integer> sharedExecOrder = new ArrayList<>();
        List<Integer> sharedUndoOrder = new ArrayList<>();
        TrackedCmd iter0Cmd = new TrackedCmd(0, sharedExecOrder, sharedUndoOrder);
        TrackedCmd iter1Cmd = new TrackedCmd(1, sharedExecOrder, sharedUndoOrder);
        RuntimeException simulatedRouteThrow = new RuntimeException(
                "simulated route command partial-throw on iteration 2");
        ExecuteThrowingCmd iter2Cmd = new ExecuteThrowingCmd(
                2, simulatedRouteThrow, sharedExecOrder, sharedUndoOrder);

        SpacingControlLoop.Callbacks executeThrowsAtIter2 =
                new SpacingControlLoop.Callbacks() {
            int buildCount = 0;
            int observeCount = 0;
            final LayoutMetrics[] script = {
                    metrics(/*thresholdsMet=*/ 1, /*m4=*/ 8),  // post-iter-0
                    metrics(/*thresholdsMet=*/ 2, /*m4=*/ 6),  // post-iter-1
            };

            @Override
            public SpacingMutationCommand buildMutationCommand(int delta) {
                int idx = buildCount++;
                if (idx == 0) return iter0Cmd;
                if (idx == 1) return iter1Cmd;
                return iter2Cmd; // execute() will throw
            }

            @Override
            public LayoutMetrics observeLayout() {
                if (observeCount >= script.length) {
                    throw new AssertionError(
                            "observeLayout should not be called after "
                            + "cmd.execute() threw at iteration "
                            + observeCount);
                }
                return script[observeCount++];
            }
        };

        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                /*initialSpacingPx=*/ 50,
                /*targetSpacingPx=*/ 300,
                /*iterationBudget=*/ BUDGET_DEFAULT,
                /*perIterationStepCapPx=*/ STEP_CAP_DEFAULT,
                /*initialMetrics=*/ metrics(/*thresholdsMet=*/ 0, /*m4=*/ 12),
                /*toolLabel=*/ "regression-test");

        // WHEN: invoking the loop with a throwing cmd should NOT propagate
        // the RuntimeException — the patch's try-catch around
        // cmd.execute() recovers gracefully.
        SpacingControlLoop.Result result;
        try {
            result = SpacingControlLoop.iterate(request, executeThrowsAtIter2);
        } catch (RuntimeException unexpected) {
            fail("the partial-commit patch should swallow cmd.execute() RuntimeException "
                    + "via best-effort cmd.undo() + new terminationReason "
                    + "branch. Loop should NOT propagate. Got: "
                    + unexpected.getMessage());
            return; // unreachable; satisfies compiler
        }

        // THEN: terminationReason is the NEW taxonomy string.
        assertEquals("terminationReason = iteration_apply_failed_at_iteration_2_reverted_after_2_accepted_iterations",
                "iteration_apply_failed_at_iteration_2_reverted_after_2_accepted_iterations",
                result.terminationReason());

        // Three iteration records: iter 0 + iter 1 (accepted) + iter 2 (failed
        // backed-off).
        assertEquals("three iterations recorded (iter 0 + 1 accepted, iter 2 failed)",
                3, result.iterations().size());
        assertEquals("iteration 2 is the failed step",
                2, result.iterations().get(2).stepIndex());
        assertTrue("iteration 2 marked backedOff",
                result.iterations().get(2).backedOff());

        // Two accepted commands preserved (iter 0 + iter 1).
        assertEquals("two accepted commands preserved",
                2, result.acceptedCommands().size());
        assertSame("iter 0's exact command instance preserved",
                iter0Cmd, result.acceptedCommands().get(0));
        assertSame("iter 1's exact command instance preserved",
                iter1Cmd, result.acceptedCommands().get(1));

        // Best-effort rollback of the throwing iter-2 cmd.
        assertEquals("iter 2's cmd.execute() invoked once before throw",
                1, iter2Cmd.executes);
        assertEquals("iter 2's cmd.undo() invoked once (best-effort rollback)",
                1, iter2Cmd.undos);

        // Execute order: 0, 1, 2 (iter 2 throws AFTER recording the execute).
        assertEquals("execute order: 0, 1, 2 (iter 2 throws mid-call)",
                List.of(0, 1, 2), sharedExecOrder);

        // Undo order: 2 (best-effort), then 1, 0 (finalizeWithReset reverse).
        // The patch first undoes the throwing iter-2 cmd via the
        // catch's best-effort cmd.undo(); then finalizeWithReset undoes
        // accepted iterations 1, 0 in reverse order so the caller's outer
        // compound re-executes them via the public command stack
        // (single-undo).
        assertEquals("undo order: 2 (failed iter best-effort), then 1, 0 (reverse)",
                List.of(2, 1, 0), sharedUndoOrder);
    }

    @Test
    public void cmdExecuteThrowsAtIteration0_terminatesWithZeroAccepted() {
        // GIVEN: a Callbacks where iteration 0's cmd.execute() throws
        // immediately (no prior accepted iterations to preserve).
        List<Integer> sharedExecOrder = new ArrayList<>();
        List<Integer> sharedUndoOrder = new ArrayList<>();
        RuntimeException simulatedRouteThrow = new RuntimeException(
                "simulated immediate failure on iteration 0");
        ExecuteThrowingCmd iter0Cmd = new ExecuteThrowingCmd(
                0, simulatedRouteThrow, sharedExecOrder, sharedUndoOrder);

        SpacingControlLoop.Callbacks executeThrowsAtIter0 =
                new SpacingControlLoop.Callbacks() {
            @Override
            public SpacingMutationCommand buildMutationCommand(int delta) {
                return iter0Cmd;
            }

            @Override
            public LayoutMetrics observeLayout() {
                throw new AssertionError(
                        "observeLayout should not be called after cmd.execute() threw");
            }
        };

        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                /*initialSpacingPx=*/ 50,
                /*targetSpacingPx=*/ 200,
                /*iterationBudget=*/ BUDGET_DEFAULT,
                /*perIterationStepCapPx=*/ STEP_CAP_DEFAULT,
                /*initialMetrics=*/ metrics(/*thresholdsMet=*/ 0, /*m4=*/ 12),
                /*toolLabel=*/ "regression-test");

        // WHEN
        SpacingControlLoop.Result result =
                SpacingControlLoop.iterate(request, executeThrowsAtIter0);

        // THEN: terminationReason names iteration 0 + zero accepted.
        assertEquals("terminationReason names iter 0 + 0 accepted",
                "iteration_apply_failed_at_iteration_0_reverted_after_0_accepted_iterations",
                result.terminationReason());
        assertEquals("one iteration recorded (the failed step)",
                1, result.iterations().size());
        assertTrue("the iteration is marked backedOff",
                result.iterations().get(0).backedOff());
        assertTrue("no accepted commands",
                result.acceptedCommands().isEmpty());
        assertEquals("iter 0's cmd.execute() invoked once",
                1, iter0Cmd.executes);
        assertEquals("iter 0's cmd.undo() invoked once (best-effort rollback)",
                1, iter0Cmd.undos);
    }

    @Test
    public void cmdExecuteThrows_undoAlsoThrows_terminationReasonStillEmitted() {
        // GIVEN: a pathological cmd whose execute() throws AND whose undo()
        // also throws (double-fault). The partial-commit patch's nested
        // try-catch around cmd.undo() must suppress the secondary failure
        // so the loop still emits a clean Result + terminationReason.
        RuntimeException executeThrow = new RuntimeException(
                "primary failure during execute");
        RuntimeException undoThrow = new IllegalStateException(
                "secondary failure during undo");
        SpacingMutationCommand doubleFaultCmd = new SpacingMutationCommand() {
            @Override
            public void execute() {
                throw executeThrow;
            }

            @Override
            public void undo() {
                throw undoThrow;
            }
        };

        SpacingControlLoop.Callbacks doubleFaultCallbacks =
                new SpacingControlLoop.Callbacks() {
            @Override
            public SpacingMutationCommand buildMutationCommand(int delta) {
                return doubleFaultCmd;
            }

            @Override
            public LayoutMetrics observeLayout() {
                throw new AssertionError("unreachable");
            }
        };

        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                /*initialSpacingPx=*/ 50,
                /*targetSpacingPx=*/ 100,
                /*iterationBudget=*/ BUDGET_DEFAULT,
                /*perIterationStepCapPx=*/ STEP_CAP_DEFAULT,
                /*initialMetrics=*/ metrics(/*thresholdsMet=*/ 0, /*m4=*/ 12),
                /*toolLabel=*/ "regression-test");

        // WHEN: even with the secondary undo throw, the loop's nested
        // try-catch must keep the loop's caller from seeing either
        // exception. The model may be in a partially-inconsistent state at
        // this point but the contract is: no outer compound is built from
        // the failed cmd, so nothing is committed.
        SpacingControlLoop.Result result;
        try {
            result = SpacingControlLoop.iterate(request, doubleFaultCallbacks);
        } catch (RuntimeException unexpected) {
            fail("Loop must suppress double-fault (execute + undo both throw); "
                    + "caller should see a clean Result with the "
                    + "iteration_apply_failed terminationReason. Got: "
                    + unexpected.getMessage());
            return;
        }

        // THEN
        assertEquals("terminationReason names iter 0 + 0 accepted "
                + "(undo failure suppressed)",
                "iteration_apply_failed_at_iteration_0_reverted_after_0_accepted_iterations",
                result.terminationReason());
        assertTrue("no accepted commands",
                result.acceptedCommands().isEmpty());
    }

    // ==================================================================
    // Route-normalized baseline + graded scalar. Pins T1/T2/T3/T5.
    //
    // SUBSTRATE NOTE (sibling-symmetric with this class's javadoc § "What
    // these tests do NOT pin" + SpacingControlLoopUndoIntegrationTest
    // + SwtUiThreadDispatcherTest substrate decisions):
    // the EMF route-normalization internals (computeAutoRoutePass +
    // mutationDispatcher temp-dispatch/undo inside the private accessor
    // method ArchiModelAccessorImpl.routeNormalizedBaseline) require the
    // full PDE Plug-in Test substrate the project does not ship
    // (ArchiPlugin.getInstance() class-loading chain). These tests pin the
    // CONTRACT both must satisfy at the loop + scalar level —
    // exactly what changes the deterministic
    // aggregate_threshold_regressed_at_iteration_0 symptom. The end-to-end
    // EMF behaviour is validated by the re-fired paired-arc empirical.
    // ==================================================================

    /**
     * Builds a {@link LayoutMetrics} whose {@code thresholdsMet} is the REAL
     * graded scalar ({@link LayoutQualityScalar#qualityScalar}) — i.e.
     * exactly what {@code ArchiModelAccessorImpl.toLayoutMetrics} now
     * produces — so these pins exercise the shipped scalar, not a hand-typed
     * proxy.
     */
    private static LayoutMetrics scalarMetrics(int boundaryViolations,
            int passThroughs, int overlaps, int m4, int coincSeg,
            double hpq, int edgeCrossings) {
        int scalar = LayoutQualityScalar.qualityScalar(
                boundaryViolations, passThroughs, overlaps, m4, coincSeg, hpq);
        return new LayoutMetrics(scalar, hpq, m4, coincSeg,
                boundaryViolations, /*vp10=*/ 0.0, edgeCrossings);
    }

    // ------------------------------------------------------------------
    // T1 — baseline_routeNormalized_noNetMutationLeaked (load-bearing).
    //
    // The one undo-leak away from corrupting the user's model on EVERY
    // call. The route-normalized baseline pass must leak ZERO commands
    // into the loop's accepted-commands list: the loop's acceptedCommands
    // size must equal exactly the number of accepted iterations, with no
    // phantom baseline-capture command. (The accessor's
    // routeNormalizedBaseline dispatches+undoes the temp route via
    // mutationDispatcher and never returns a command into the loop — the
    // EMF temp-undo is validated end-to-end by the re-fired empirical.)
    // ------------------------------------------------------------------

    @Test
    public void t1_baseline_routeNormalized_noNetMutationLeaked() {
        List<Integer> execOrder = new ArrayList<>();
        List<Integer> undoOrder = new ArrayList<>();
        TrackedCmd iter0 = new TrackedCmd(0, execOrder, undoOrder);
        TrackedCmd iter1 = new TrackedCmd(1, execOrder, undoOrder);

        // Route-normalized baseline — same routing basis as the
        // per-step postStates below. Two accepted iterations.
        LayoutMetrics routeNormBaseline =
                scalarMetrics(0, 0, 0, 9, 6, 0.60, 150);

        SpacingControlLoop.Callbacks cb = new SpacingControlLoop.Callbacks() {
            int build = 0;
            final LayoutMetrics[] post = {
                    scalarMetrics(0, 0, 0, 7, 5, 0.70, 140), // iter0 better
                    scalarMetrics(0, 0, 0, 5, 4, 0.78, 135), // iter1 better
            };
            int obs = 0;

            @Override
            public SpacingMutationCommand buildMutationCommand(int d) {
                int i = build++;
                if (i == 0) return iter0;
                if (i == 1) return iter1;
                return null; // ladder/graceful stop after 2 accepts
            }

            @Override
            public LayoutMetrics observeLayout() {
                return post[obs++];
            }
        };

        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                /*initialSpacingPx=*/ 50, /*targetSpacingPx=*/ 300,
                /*iterationBudget=*/ BUDGET_DEFAULT,
                /*perIterationStepCapPx=*/ STEP_CAP_DEFAULT,
                /*initialMetrics=*/ routeNormBaseline,
                /*toolLabel=*/ "t1-route-normalized");

        SpacingControlLoop.Result result =
                SpacingControlLoop.iterate(request, cb);

        // ZERO leak: accepted-commands == accepted-iteration count exactly.
        long acceptedIterations = result.iterations().stream()
                .filter(s -> !s.backedOff()).count();
        assertEquals("no phantom command from baseline capture",
                acceptedIterations, result.acceptedCommands().size());
        assertEquals("exactly the 2 iteration commands, nothing else",
                2, result.acceptedCommands().size());
        assertSame(iter0, result.acceptedCommands().get(0));
        assertSame(iter1, result.acceptedCommands().get(1));
    }

    // ------------------------------------------------------------------
    // T2 — baseline_routeDegraded_returnsBareStateZeroIterations.
    //
    // The guarded-form safety net. The decision rule
    // is routeNorm.thresholdsMet() < bare.thresholdsMet() (strictly worse on
    // the graded scalar = a dropped perceptual band or correctness bit =
    // materially worse). When it fires the accessor returns the bare input
    // untouched (0 iterations) with terminationReason
    // reroute_degraded_input_baseline — the loop is never entered. Pinned:
    // the arithmetic rule on the shipped scalar + the additive
    // taxonomy constant. (The accessor early-return is EMF; validated
    // end-to-end by the empirical.)
    // ------------------------------------------------------------------

    @Test
    public void t2_baseline_routeDegraded_decisionRuleAndTaxonomyConstant() {
        // Bare post-hub-resize input (un-rerouted): HPQ lifted by hub resize.
        LayoutMetrics bare =
                scalarMetrics(0, 0, 0, /*m4=*/ 7, /*cs=*/ 4, /*hpq=*/ 0.86, 150);
        // The tool's own reroute DEGRADES it (introduces a boundary
        // violation + drops HPQ a band) — the "not observed but not
        // impossible" case.
        LayoutMetrics routeDegraded =
                scalarMetrics(/*bv=*/ 1, 0, 0, /*m4=*/ 8, /*cs=*/ 6,
                        /*hpq=*/ 0.60, 165);

        assertTrue("route-degraded scores strictly worse on the graded "
                + "scalar -> guard MUST fire (bare=" + bare.thresholdsMet()
                + " routeNorm=" + routeDegraded.thresholdsMet() + ")",
                routeDegraded.thresholdsMet() < bare.thresholdsMet());

        // A route pass that HOLDS or improves must NOT trip the guard.
        LayoutMetrics routeOk =
                scalarMetrics(0, 0, 0, /*m4=*/ 6, /*cs=*/ 4, /*hpq=*/ 0.86, 148);
        assertFalse("route-consistent must NOT trip the degraded guard",
                routeOk.thresholdsMet() < bare.thresholdsMet());

        // Additive pre-loop taxonomy constant.
        assertEquals("reroute_degraded_input_baseline",
                SpacingControlLoop.REASON_REROUTE_DEGRADED_INPUT_BASELINE);
    }

    // ------------------------------------------------------------------
    // T3 — baseline_routeConsistent_loopProceedsFromRerouted.
    //
    // Happy path: when the baseline is route-normalized (same routing basis
    // as the per-step postStates), the first rerouted step does NOT
    // spuriously regress vs the baseline -> the loop PROCEEDS (>=1 accepted
    // iteration), it does NOT terminate with
    // aggregate_threshold_regressed_at_iteration_0.
    // ------------------------------------------------------------------

    @Test
    public void t3_baseline_routeConsistent_loopProceedsFromRerouted() {
        // Route-normalized baseline + first step on the SAME basis, slightly
        // improved (the realistic post-hub-resize -> +30px spacing effect).
        LayoutMetrics routeNormBaseline =
                scalarMetrics(0, 0, 0, /*m4=*/ 9, /*cs=*/ 6, /*hpq=*/ 0.60, 150);

        SpacingControlLoop.Callbacks cb = new SpacingControlLoop.Callbacks() {
            int build = 0;

            @Override
            public SpacingMutationCommand buildMutationCommand(int d) {
                if (build++ == 0) {
                    return new TrackedCmd(0, null, null);
                }
                return null; // single accept then graceful stop
            }

            @Override
            public LayoutMetrics observeLayout() {
                // post-iter-0, same routing basis, genuinely improved.
                return scalarMetrics(0, 0, 0, 6, 4, 0.78, 140);
            }
        };

        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                50, 300, BUDGET_DEFAULT, STEP_CAP_DEFAULT,
                routeNormBaseline, "t3-route-consistent");

        SpacingControlLoop.Result result =
                SpacingControlLoop.iterate(request, cb);

        assertEquals("loop proceeded — iteration 0 accepted",
                1, result.acceptedCommands().size());
        assertFalse("did NOT spuriously revert at iteration 0",
                result.terminationReason()
                        .startsWith("aggregate_threshold_regressed_"
                                + "at_iteration_0"));
        assertTrue("terminated via graceful budget-exhausted after the "
                + "accepted iteration",
                result.terminationReason()
                        .startsWith("budget_exhausted_after_"));
    }

    // ------------------------------------------------------------------
    // T5 — postHubResize_doesNotRevertAtIteration0.
    //
    // THE falsifiable encoding that the deterministic symptom
    // is DEAD — the test that would have caught this earlier. With
    // a route-normalized same-basis baseline + the graded scalar,
    // a realistic post-hub-resize HH/ST snapshot stepped +30px must NOT
    // yield aggregate_threshold_regressed_at_iteration_0. The companion
    // assertion documents WHY it used to fail: the OLD un-rerouted bare
    // baseline scored a luckier binary-at-0 aggregate than ANY freshly-
    // rerouted step.
    // ------------------------------------------------------------------

    @Test
    public void t5_postHubResize_HH_doesNotRevertAtIteration0() {
        assertNoIteration0Revert(
                /*scenario=*/ "HH",
                // post-hub-resize HH, route-normalized baseline.
                scalarMetrics(0, 0, 0, /*m4=*/ 7, /*cs=*/ 5, /*hpq=*/ 0.82, 160),
                // first +30px step, SAME routing basis, marginally improved.
                scalarMetrics(0, 0, 0, /*m4=*/ 5, /*cs=*/ 4, /*hpq=*/ 0.85, 152));
    }

    @Test
    public void t5_postHubResize_ST_doesNotRevertAtIteration0() {
        assertNoIteration0Revert(
                /*scenario=*/ "ST",
                scalarMetrics(0, 0, 0, /*m4=*/ 9, /*cs=*/ 6, /*hpq=*/ 0.60, 164),
                scalarMetrics(0, 0, 0, /*m4=*/ 7, /*cs=*/ 4, /*hpq=*/ 0.86, 151));
    }

    @Test
    public void t5_preFixCondition_documentsWhyItUsedToRevert() {
        // The route-normalized-baseline + graded-scalar mechanism: the OLD
        // baseline was bare/un-rerouted with the OLD binary-at-0 aggregate.
        // On post-hub-resize geometry the hub resize lifts HPQ>=0.75 so the
        // un-rerouted baseline scored the OLD aggregate = 1-2; the first
        // freshly-rerouted step perturbed HPQ or added a boundary violation
        // -> OLD aggregate = baseline-1 -> strict regression -> REVERT @0.
        int oldBareBaselineAgg =
                /*coincSeg==0?*/ 0 + /*M4==0?*/ 0
                + /*boundaryViolations.isEmpty()?*/ 1
                + /*HPQ>=0.75? (hub resize lifted it)*/ 1; // = 2
        int oldFirstReroutedStepAgg =
                0 + 0 + /*reroute introduced a boundary violation*/ 0
                + /*reroute perturbed HPQ below 0.75*/ 0; // = 0
        assertTrue("PRE-FIX: old binary-at-0 aggregate strictly regressed "
                + "on the first rerouted step -> deterministic iteration-0 "
                + "revert (the Sessions 6-10 symptom)",
                oldFirstReroutedStepAgg < oldBareBaselineAgg);

        // POST-FIX: the same physical step, scored by the graded scalar on
        // a route-normalized (same-basis) baseline, does NOT strictly
        // regress — the loop survives iteration 0.
        LayoutMetrics postFixBaseline =
                scalarMetrics(0, 0, 0, 9, 6, 0.60, 164);
        LayoutMetrics postFixStep0 =
                scalarMetrics(0, 0, 0, 7, 4, 0.86, 151);
        assertTrue("POST-FIX: graded scalar on same-basis baseline does "
                + "NOT regress (" + postFixBaseline.thresholdsMet() + " -> "
                + postFixStep0.thresholdsMet() + ")",
                postFixStep0.thresholdsMet()
                        >= postFixBaseline.thresholdsMet());
    }

    private void assertNoIteration0Revert(String scenario,
            LayoutMetrics routeNormBaseline, LayoutMetrics step0PostState) {
        SpacingControlLoop.Callbacks cb = new SpacingControlLoop.Callbacks() {
            int build = 0;

            @Override
            public SpacingMutationCommand buildMutationCommand(int d) {
                if (build++ == 0) {
                    return new TrackedCmd(0, null, null);
                }
                return null;
            }

            @Override
            public LayoutMetrics observeLayout() {
                return step0PostState;
            }
        };

        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                50, 300, BUDGET_DEFAULT, STEP_CAP_DEFAULT,
                routeNormBaseline, "t5-" + scenario);

        SpacingControlLoop.Result result =
                SpacingControlLoop.iterate(request, cb);

        assertFalse(scenario + ": the Sessions 6-10 deterministic "
                + "aggregate_threshold_regressed_at_iteration_0 symptom "
                + "must be DEAD (terminationReason="
                + result.terminationReason() + ")",
                result.terminationReason()
                        .startsWith("aggregate_threshold_regressed_"
                                + "at_iteration_0"));
        assertTrue(scenario + ": iteration 0 accepted (loop survives)",
                result.acceptedCommands().size() >= 1);
    }

    // ------------------------------------------------------------------
    // T15 — Density-aware-termination preservation pin.
    //
    // The shipped LayoutQualityScalar (scalarMetrics(...)) uses the 7-arg
    // LayoutMetrics ctor → avgSpacingPx = NaN; this class's Requests pass
    // no hubExtent → null. So the density-aware discriminator is INERT and
    // the partial-commit / route-normalized contract is
    // byte-identical. This pin makes that explicit AND asserts the new
    // Result.densityDiagnosis() field never leaks onto a preserved
    // terminal.
    // ------------------------------------------------------------------

    @Test
    public void densityAware_regimeAbsent_decisionA13ContractPreserved() {
        // Climbing scalar with the SHIPPED qualityScalar (NaN avg) — the
        // loop must accept to budget as before, with NO density
        // diagnosis surfaced.
        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                /*initialSpacingPx=*/ 50, /*targetSpacingPx=*/ 300,
                BUDGET_DEFAULT, STEP_CAP_DEFAULT,
                scalarMetrics(0, 0, 0, /*m4=*/ 12, /*coincSeg=*/ 10,
                        /*hpq=*/ 0.40, /*edgeCrossings=*/ 150),
                "t15-regime-absent");
        final List<LayoutMetrics> obs = new ArrayList<>();
        obs.add(scalarMetrics(0, 0, 0, 9, 8, 0.55, 140));
        obs.add(scalarMetrics(0, 0, 0, 6, 5, 0.70, 130));
        obs.add(scalarMetrics(0, 0, 0, 4, 3, 0.80, 120));
        SpacingControlLoop.Callbacks cb = new SpacingControlLoop.Callbacks() {
            private int i = 0;
            @Override public SpacingMutationCommand buildMutationCommand(
                    int proposedDeltaPx) {
                return new SpacingMutationCommand() {
                    @Override public void execute() { }
                    @Override public void undo() { }
                };
            }
            @Override public LayoutMetrics observeLayout() {
                return obs.get(Math.min(i++, obs.size() - 1));
            }
        };

        SpacingControlLoop.Result result =
                SpacingControlLoop.iterate(request, cb);

        assertTrue("regime absent → row-703 budget-exhausted preserved",
                result.terminationReason().startsWith(
                        SpacingControlLoop.REASON_BUDGET_EXHAUSTED_PREFIX));
        assertNull("density diagnosis must NOT leak onto a preserved "
                + "row-703 terminal", result.densityDiagnosis());
    }

    // ==================================================================
    // FIX-1 — The composer two-arm transition's speculative
    //         element-arm replay + finally-undo of the UNWRAPPED accepted
    //         commands was the one path the SwtUiThreadDispatcher
    //         marshalling did NOT cover. ACTUAL captured stack trace
    //         (2026-05-17, runtime log — NOT a static guess):
    //
    //   java.lang.NullPointerException: Cannot invoke
    //     "org.eclipse.swt.widgets.Display.asyncExec(Runnable)" because the
    //     return value of "Display.getCurrent()" is null
    //     at ...TreeModelView.doRefreshFromNotifications(TreeModelView.java:895)
    //     at ...AbstractModelView.propertyChange / TreeModelView.propertyChange
    //     at ...PropertyChangeSupport.firePropertyChange
    //     at ...EditorModelManager.firePropertyChange(EditorModelManager:748)
    //     at ...NonNotifyingCompoundCommand.execute(NonNotifyingCompoundCommand:48)
    //     at ...ArchiModelAccessorImpl.applySpacingRecommendations(:8286)  <- raw c.execute()
    //     ... reactor.core.scheduler.SchedulerTask ... (NOT the SWT UI thread)
    //
    //   ComposerSpeculativeReplay routes that replay/undo through the SAME
    //   SwtUiThreadDispatcher boundary via ComposerSpeculativeReplay
    //   (extension, not re-architecture — 2×2 / 3-state enum /
    //   aggregate objective / loop accept-back-off semantics untouched).
    //   SUBSTRATE: the composer is EMF/OSGi (not pure-unit-instantiable);
    //   per this class's SUBSTRATE NOTE these pins pin the CONTRACT the fix
    //   must satisfy at the unit level; the end-to-end "no INTERNAL_ERROR /
    //   no partial-commit on ST" is validated by the re-fired
    //   paired-arc empirical.
    // ==================================================================

    /** Records execute/undo order across a shared list (GEF Command). */
    private static final class TrackedGefCmd extends Command {
        private final String id;
        private final List<String> order;
        TrackedGefCmd(String id, List<String> order) {
            this.id = id;
            this.order = order;
        }
        @Override public void execute() { order.add("x:" + id); }
        @Override public void undo() { order.add("u:" + id); }
    }

    /** Models the captured throw-site: a NonNotifyingCompoundCommand whose
     *  execute() fires the off-UI-thread TreeModelView NPE. */
    private static final class NpeOnExecuteGefCmd extends Command {
        @Override public void execute() {
            throw new NullPointerException("simulated TreeModelView."
                    + "doRefreshFromNotifications Display.getCurrent() NPE "
                    + "(captured 2026-05-17)");
        }
    }

    @Test
    public void fix1_speculativeReplay_forwardThenReverseUndo_order() {
        List<String> order = new ArrayList<>();
        List<Command> cmds = Arrays.asList(
                new TrackedGefCmd("0", order),
                new TrackedGefCmd("1", order),
                new TrackedGefCmd("2", order));

        ComposerSpeculativeReplay.replayForward(cmds);
        ComposerSpeculativeReplay.undoReverse(cmds);

        // Forward replay 0,1,2 then reverse undo 2,1,0 — the composer
        // two-arm hand-off semantics are PRESERVED, only the
        // dispatch boundary changed.
        assertEquals(Arrays.asList(
                "x:0", "x:1", "x:2", "u:2", "u:1", "u:0"), order);
    }

    @Test
    public void fix1_speculativeReplay_nullAndEmpty_areNoOp() {
        // The composer's `if (size > 0)` guard collapses into the helper —
        // empty/null must be a clean no-op (no NPE, nothing dispatched).
        ComposerSpeculativeReplay.replayForward(null);
        ComposerSpeculativeReplay.replayForward(new ArrayList<>());
        ComposerSpeculativeReplay.undoReverse(null);
        ComposerSpeculativeReplay.undoReverse(new ArrayList<>());
    }

    @Test
    public void fix1_speculativeReplay_runtimeExceptionPropagates_notSwallowed() {
        // THE falsifiable contract: a command whose execute() reproduces the
        // captured off-UI-thread NPE MUST propagate out of the marshalled
        // helper (SwtUiThreadDispatcher's documented re-throw contract) so
        // the composer's graceful-degradation catch + the
        // envelope logger.error STILL see it. If a regression made the
        // helper swallow it, the partial-commit would go silent again —
        // this pin fails first.
        List<Command> cmds = Arrays.asList(new NpeOnExecuteGefCmd());
        try {
            ComposerSpeculativeReplay.replayForward(cmds);
            fail("the simulated TreeModelView NPE must propagate through "
                    + "the SWT-marshalled helper (re-throw contract), not "
                    + "be swallowed");
        } catch (NullPointerException expected) {
            assertTrue("propagated NPE is the captured throw-site model",
                    expected.getMessage() != null
                            && expected.getMessage().contains(
                                    "TreeModelView"));
        }
    }

    @Test
    public void fix1_throwSiteCondition_documentsCapturedRootCause() {
        // t5_preFixCondition-style documentary pin (the codebase's accepted
        // EMF-validation split: contract pinned here, end-to-end via the
        // re-empirical). PRE-FIX: the composer ran a RAW
        //   for (Command c : elementArm.acceptedCommands) c.execute();
        // (+ a reverse finally-undo) directly on the reactor worker thread
        // → NonNotifyingCompoundCommand.firePropertyChange →
        // TreeModelView.doRefreshFromNotifications → Display.getCurrent()
        // (null off-UI) .asyncExec → NPE → envelope INTERNAL_ERROR AFTER
        // the speculative re-execute advanced state (partial-commit). The
        // throw was NON-deterministic because the speculative block only
        // runs when the element arm accepted >=1 iteration AND the group
        // arm fires (interGroupDelta>0) — best-of-K / escalate-path
        // dependent. POST-FIX: both loops are delegated to
        // ComposerSpeculativeReplay, which routes them through the SAME
        // SwtUiThreadDispatcher boundary the loop body already
        // uses. This pin is RED pre-fix by construction (the helper did not
        // exist) and GREEN post-fix.
        // Strengthened from the prior boolean-tautology
        // (`assertTrue(true)`-style) to REAL falsifiable
        // structural content — the captured-root-cause invariant (a raw
        // command `.execute()` off the UI thread IS the breach) is now
        // pinned by exercising the actual ComposerSpeculativeReplay →
        // SwtUiThreadDispatcher wire and asserting its re-throw contract
        // for BOTH the captured-NPE class AND Error.

        // (1) The captured throw site: a replayed command whose execute()
        // throws the exact NPE class (TreeModelView.doRefreshFromNotifications
        // → Display.getCurrent()-null, captured 2026-05-17) MUST propagate
        // as the SAME instance through replayForward — i.e. the speculative
        // replay routes through the SwtUiThreadDispatcher same-instance
        // re-throw boundary, not swallowed/wrapped. Falsifiable.
        NullPointerException capturedNpe = new NullPointerException(
                "Cannot invoke \"Display.asyncExec(Runnable)\" because "
                + "\"Display.getCurrent()\" is null");
        List<Command> npeCmd = Arrays.asList(new Command() {
            @Override public void execute() { throw capturedNpe; }
        });
        try {
            ComposerSpeculativeReplay.replayForward(npeCmd);
            fail("the captured-NPE-class throw must propagate through "
                    + "ComposerSpeculativeReplay (not be swallowed)");
        } catch (NullPointerException actual) {
            assertSame("replayForward must re-throw the SAME captured-NPE "
                    + "instance via the SwtUiThreadDispatcher boundary",
                    capturedNpe, actual);
        }

        // (2) Error tie-in — the genuinely new falsifiable
        // content: an Error (the LinkageError sibling of the captured-NPE
        // arc) thrown by a replayed command MUST also propagate as the
        // SAME instance. Previously the SwtUiThreadDispatcher caught only
        // RuntimeException, so under a real SWT Display this Error would be
        // silently swallowed by syncExec — exactly the invisible-loss
        // failure class the marshalling arc exists to prevent. FAILS if the
        // speculative replay is reverted to a raw loop bypassing the
        // boundary, or if the Error-propagation widening regresses.
        Error boundaryError = new NoClassDefFoundError(
                "simulated off-UI-thread LinkageError at the composer "
                + "speculative-replay boundary");
        List<Command> errCmd = Arrays.asList(new Command() {
            @Override public void execute() { throw boundaryError; }
        });
        try {
            ComposerSpeculativeReplay.replayForward(errCmd);
            fail("row-775 case (a): a boundary Error must propagate through "
                    + "ComposerSpeculativeReplay, not be swallowed");
        } catch (Error actual) {
            assertSame("replayForward must re-throw the SAME Error instance "
                    + "via the case (a) widened SwtUiThreadDispatcher",
                    boundaryError, actual);
        }

        // (3) Structural no-op anchor retained: the fix's pure surface
        // exists and its empty contract holds (a revert to the raw inline
        // loop deletes ComposerSpeculativeReplay → this class fails to
        // compile — compile-time coupling to the post-fix wire).
        ComposerSpeculativeReplay.replayForward(new ArrayList<>());
        ComposerSpeculativeReplay.undoReverse(new ArrayList<>());
    }
}
