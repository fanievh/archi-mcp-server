package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 * Pure-unit regression test for {@link SwtUiThreadDispatcher} — pins the
 * targeted fix for the {@code iteration_apply_failed_at_iteration_0}
 * SWT-threading bug.
 *
 * <p><strong>Substrate note:</strong> the test relies on the null-Display
 * fallback path in {@link SwtUiThreadDispatcher#runOnUiThread(Runnable)}.
 * Running the test outside an SWT-initialised JVM (pure-unit / no Eclipse
 * UI) means {@link org.eclipse.swt.widgets.Display#getDefault()} could
 * either return {@code null} (no SWT thread bound) or instantiate a default
 * Display on the calling thread. Both branches are exercised — the test
 * asserts the action runs exactly once and propagates RuntimeException
 * regardless of which branch fires. This matches the project convention
 * (sibling-symmetric with {@link SpacingControlLoopUndoIntegrationTest}'s
 * substrate-substitution decision: pin the EXECUTION CONTRACT, not
 * the SWT marshalling internals which require full PDE Plug-in Test
 * substrate the project doesn't ship).
 */
public class SwtUiThreadDispatcherTest {

    /**
     * Action runs exactly once on the calling thread (or marshalled to UI
     * thread via syncExec, depending on whether SWT Display is available in
     * the test JVM).
     */
    @Test
    public void runOnUiThread_runsActionExactlyOnce() {
        AtomicInteger callCount = new AtomicInteger(0);

        SwtUiThreadDispatcher.runOnUiThread(callCount::incrementAndGet);

        assertEquals("action should run exactly once", 1, callCount.get());
    }

    /**
     * Action's RuntimeException propagates to caller (the contract relied on
     * by SpacingControlLoop.iterate's catch block).
     */
    @Test
    public void runOnUiThread_runtimeExceptionPropagates() {
        RuntimeException expected = new RuntimeException("simulated cmd.execute() failure");

        try {
            SwtUiThreadDispatcher.runOnUiThread(() -> {
                throw expected;
            });
            fail("expected RuntimeException to propagate");
        } catch (RuntimeException actual) {
            assertSame("propagated exception must be the original instance",
                    expected, actual);
        }
    }

    /**
     * Action's NullPointerException propagates (subclass of RuntimeException)
     * — directly reproduces the
     * {@code Display.getCurrent().asyncExec(...)} NPE class (root-cause
     * forensic anchor).
     */
    @Test
    public void runOnUiThread_nullPointerExceptionPropagates() {
        NullPointerException expected = new NullPointerException(
                "Cannot invoke \"Display.asyncExec(Runnable)\" because \"Display.getCurrent()\" is null");

        try {
            SwtUiThreadDispatcher.runOnUiThread(() -> {
                throw expected;
            });
            fail("expected NullPointerException to propagate");
        } catch (NullPointerException actual) {
            assertSame("propagated NPE must be the original instance",
                    expected, actual);
        }
    }

    /**
     * Null action argument rejected with NPE (defensive contract on a public
     * static helper — caller bugs surface immediately rather than corrupting
     * downstream syncExec).
     */
    @Test
    public void runOnUiThread_nullActionThrowsNpe() {
        try {
            SwtUiThreadDispatcher.runOnUiThread(null);
            fail("expected NullPointerException for null action");
        } catch (NullPointerException ex) {
            assertNotNull(ex.getMessage());
            assertTrue("NPE message should identify the null parameter",
                    ex.getMessage().contains("action"));
        }
    }

    /**
     * Action captures and reads-back caller-thread-mutable state correctly
     * (verifies the syncExec marshalling preserves visibility semantics — if
     * SWT path fires, the AtomicReference is the visibility primitive; if
     * null-Display direct path fires, same-thread visibility is trivial).
     */
    @Test
    public void runOnUiThread_actionReadsAndWritesAtomicReference() {
        AtomicReference<String> captured = new AtomicReference<>("before");

        SwtUiThreadDispatcher.runOnUiThread(() -> captured.set("after"));

        assertEquals("after", captured.get());
    }

    /**
     * Multiple sequential calls to runOnUiThread accumulate side-effects
     * correctly — pins the contract for SpacingControlLoop's per-iteration
     * speculative execute + observe + back-off cycle.
     */
    @Test
    public void runOnUiThread_multipleSequentialCallsAccumulateSideEffects() {
        AtomicInteger counter = new AtomicInteger(0);

        SwtUiThreadDispatcher.runOnUiThread(counter::incrementAndGet);
        SwtUiThreadDispatcher.runOnUiThread(counter::incrementAndGet);
        SwtUiThreadDispatcher.runOnUiThread(counter::incrementAndGet);

        assertEquals("three sequential calls accumulate three increments",
                3, counter.get());
    }

    /**
     * Exception in one call does NOT prevent subsequent calls from running
     * — pins the resilience contract for SpacingControlLoop's best-effort
     * cmd.undo() in the catch block (the undo call must still
     * execute even if execute threw).
     */
    @Test
    public void runOnUiThread_exceptionInOneCallDoesNotPoisonSubsequent() {
        AtomicInteger postExceptionCount = new AtomicInteger(0);

        try {
            SwtUiThreadDispatcher.runOnUiThread(() -> {
                throw new RuntimeException("first call fails");
            });
            fail("expected RuntimeException");
        } catch (RuntimeException ex) {
            // expected
        }

        SwtUiThreadDispatcher.runOnUiThread(postExceptionCount::incrementAndGet);
        SwtUiThreadDispatcher.runOnUiThread(postExceptionCount::incrementAndGet);

        assertEquals("subsequent calls run normally after a thrown exception",
                2, postExceptionCount.get());
    }

    // ==================================================================
    // The SWT-thread-boundary capture is widened from RuntimeException to
    // Throwable: an off-UI-thread Error MUST be re-thrown (same instance),
    // NOT silently swallowed by syncExec. A swallowed boundary Error is
    // exactly the failure class the marshalling arc was created to surface. The
    // RuntimeException path stays byte-preserved (asserted by the
    // runtimeException/NPE pins above — they must remain GREEN).
    // ==================================================================

    /**
     * An {@link AssertionError} (an {@link Error}, NOT a RuntimeException)
     * thrown inside the marshalled action propagates to the caller as the
     * SAME instance — previously it was silently swallowed (the old
     * {@code catch (RuntimeException ex)} did not match Error, so syncExec
     * returned normally and the loss was invisible).
     */
    @Test
    public void runOnUiThread_errorPropagatesNotSwallowed_row775AC7a() {
        AssertionError expected =
                new AssertionError("simulated off-UI-thread boundary Error");

        try {
            SwtUiThreadDispatcher.runOnUiThread(() -> {
                throw expected;
            });
            fail("expected the AssertionError to propagate, not be "
                    + "silently swallowed (row-775 case (a))");
        } catch (AssertionError actual) {
            assertSame("propagated Error must be the original instance",
                    expected, actual);
        }
    }

    /**
     * A non-Assertion {@link Error} subclass (the LinkageError family — the
     * {@code Display.getCurrent()}-null arc's sibling failure class)
     * likewise propagates as the same instance.
     */
    @Test
    public void runOnUiThread_linkageErrorPropagates_row775AC7a() {
        Error expected = new NoClassDefFoundError(
                "simulated off-UI-thread LinkageError");

        try {
            SwtUiThreadDispatcher.runOnUiThread(() -> {
                throw expected;
            });
            fail("expected the Error to propagate (row-775 case (a))");
        } catch (Error actual) {
            assertSame("propagated Error must be the original instance",
                    expected, actual);
        }
    }

    /**
     * Regression guard — the RuntimeException re-throw is still the SAME
     * instance after the Throwable widening (NOT wrapped). This pins the
     * "RuntimeException path byte-preserved" invariant explicitly (sibling
     * to {@code runOnUiThread_runtimeExceptionPropagates}).
     */
    @Test
    public void runOnUiThread_runtimeExceptionStillSameInstanceAfterWiden() {
        IllegalStateException expected =
                new IllegalStateException("byte-preserved RTE path");

        try {
            SwtUiThreadDispatcher.runOnUiThread(() -> {
                throw expected;
            });
            fail("expected RuntimeException to propagate");
        } catch (RuntimeException actual) {
            assertSame("RTE path must NOT wrap — same instance re-thrown",
                    expected, actual);
        }
    }
}
