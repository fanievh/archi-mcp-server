package net.vheerden.archi.mcp.model;

import org.eclipse.swt.widgets.Display;

/**
 * Marshals work onto the SWT UI thread, with a fall-back direct-invoke path
 * for environments where no SWT {@link Display} is available (e.g., pure-unit
 * test substrate without OSGi/Eclipse startup).
 *
 * <p><strong>Why this exists (targeted fix,
 * 2026-05-15):</strong> the {@link SpacingControlLoop#iterate} body calls
 * caller-supplied {@link SpacingMutationCommand#execute()} /
 * {@link SpacingMutationCommand#undo()} directly from the reactor scheduler
 * worker thread (background). For mutations that wrap Archi
 * {@code NonNotifyingCompoundCommand}s, that compound's terminal
 * {@code firePropertyChange} fires from the calling thread; Archi's
 * {@code TreeModelView.doRefreshFromNotifications} listener then attempts
 * {@code Display.getCurrent().asyncExec(Runnable)} — but
 * {@code Display.getCurrent()} returns {@code null} off the UI thread,
 * producing a {@link NullPointerException}.
 * The fix routes the execute/undo calls through {@link Display#syncExec}
 * (which blocks the reactor thread until the Runnable completes on the UI
 * thread) so the property-change tail fires from the UI thread, satisfying
 * the Archi listener's threading contract.
 *
 * <p><strong>Exception propagation:</strong> any {@link Throwable} thrown
 * inside the Runnable is captured and re-thrown from the calling thread.
 * The {@link RuntimeException} path is byte-preserved (the SAME instance is
 * re-thrown), keeping the existing partial-throw catch semantics
 * in {@link SpacingControlLoop} unchanged. A later change
 * widened the capture from {@code RuntimeException} to
 * {@code Throwable} so an off-UI-thread {@link Error} (e.g.
 * {@link AssertionError}/{@link LinkageError}) raised inside the marshalled
 * action is NOT silently swallowed by {@code Display.syncExec} — it is
 * re-thrown from the calling thread, preserving the
 * captured-root-cause discipline (a swallowed boundary {@code Error} is
 * exactly the failure class that arc was created to surface). This matches
 * SWT's own {@code Display.syncExec} re-throw contract but is implemented
 * explicitly here to avoid coupling to SWT-version-specific behaviour.
 *
 * <p><strong>Null-Display fallback:</strong> if {@link Display#getDefault()}
 * returns {@code null} (degenerate path — should never occur under Archi
 * runtime; preserved only for pure-unit test substrate compatibility), the
 * Runnable is invoked directly on the calling thread. This branch is the
 * primary unit-testable surface of this class.
 *
 * <p>Pinned by {@code SwtUiThreadDispatcherTest}.
 */
final class SwtUiThreadDispatcher {

    private SwtUiThreadDispatcher() {
        // Utility class — not instantiable.
    }

    /**
     * Run {@code action} on the SWT UI thread (or directly on the calling
     * thread if no SWT {@link Display} is available).
     *
     * <p>Blocks the calling thread until {@code action} completes. Any
     * {@link Throwable} thrown inside {@code action} is re-thrown from the
     * calling thread (the {@link RuntimeException} path re-throws the SAME
     * instance — byte-preserved; {@link Error} is likewise propagated per
     * boundary contract).
     *
     * @param action work to run; non-null
     */
    static void runOnUiThread(Runnable action) {
        if (action == null) {
            throw new NullPointerException("action");
        }
        Display display = Display.getDefault();
        if (display == null) {
            action.run();
            return;
        }
        // capture Throwable (not just RuntimeException)
        // across the SWT-thread boundary so an off-UI-thread Error is not
        // silently swallowed by syncExec. RuntimeException re-throw is
        // byte-preserved (same instance); Error is re-thrown; a checked
        // Throwable (impossible from Runnable.run() — it declares no
        // checked exceptions) is defensively wrapped so this method's
        // no-`throws` contract is preserved.
        Throwable[] caught = new Throwable[1];
        display.syncExec(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                caught[0] = t;
            }
        });
        Throwable t = caught[0];
        if (t != null) {
            if (t instanceof RuntimeException re) {
                throw re;
            }
            if (t instanceof Error err) {
                throw err;
            }
            throw new RuntimeException(t);
        }
    }
}
