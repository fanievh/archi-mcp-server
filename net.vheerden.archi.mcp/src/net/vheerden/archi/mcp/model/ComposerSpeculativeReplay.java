package net.vheerden.archi.mcp.model;

import java.util.List;

import org.eclipse.gef.commands.Command;

/**
 * SWT-marshalled
 * speculative replay / reverse-undo of the composer's element-arm accepted
 * commands across the two-arm (element&rarr;group) transition in
 * {@code ArchiModelAccessorImpl.applySpacingRecommendations}.
 *
 * <p><strong>Root cause (captured 2026-05-17 — actual runtime stack trace,
 * NOT a static guess):</strong> the composer speculatively re-executes
 * the element arm's accepted commands so the group arm can observe the
 * post-element state, then undoes them in the {@code finally}. Those accepted
 * commands are raw GEF {@code NonNotifyingCompoundCommand}s (downcast out of
 * the loop's {@code GefSpacingMutationCommand} wrappers). Calling their
 * {@link Command#execute()} / {@link Command#undo()} <em>directly</em> from
 * the reactor scheduler worker thread fires the compound's terminal
 * {@code EditorModelManager.firePropertyChange}; Archi's
 * {@code TreeModelView.doRefreshFromNotifications} listener then calls
 * {@code Display.getCurrent().asyncExec(Runnable)} — but
 * {@code Display.getCurrent()} is {@code null} off the SWT UI thread, so it
 * throws {@link NullPointerException}, which the composer envelope wraps to
 * {@code INTERNAL_ERROR} after the speculative re-execute already advanced
 * state (the partial-commit symptom).
 *
 * <p>This is the SAME threading-contract breach {@link SwtUiThreadDispatcher}
 * was created for: that fix marshalled the loop
 * body's execute/undo (via {@code GefSpacingMutationCommand}); the composer's
 * OWN speculative replay/undo of the <em>unwrapped</em> accepted commands at
 * the two-arm transition was the one path it did not cover. This class
 * routes that replay/undo through the SAME {@link SwtUiThreadDispatcher}
 * boundary — an <em>extension</em> of that marshalling, NOT a
 * re-architecture: the density-aware 2&times;2 discriminator, the 3-state
 * enum, the aggregate-only objective and the loop accept/back-off semantics
 * are all untouched.
 *
 * <p>Pure / static — no EMF model state; pinned by
 * {@code SpacingControlLoopPartialCommitRegressionTest}.
 */
final class ComposerSpeculativeReplay {

    private ComposerSpeculativeReplay() {
        // Utility class — not instantiable.
    }

    /**
     * Re-execute {@code commands} in FORWARD order on the SWT UI thread (the
     * composer's speculative element-arm replay so the group arm observes the
     * post-element state). No-op on a {@code null}/empty list. Any
     * {@link RuntimeException} thrown by a command propagates to the caller
     * via {@link SwtUiThreadDispatcher}'s re-throw contract, so the
     * composer's existing graceful-degradation catch and the
     * envelope {@code logger.error} still see it (the throw is surfaced, not
     * silently swallowed).
     *
     * @param commands the element arm's accepted commands; may be empty/null
     */
    static void replayForward(List<Command> commands) {
        if (commands == null || commands.isEmpty()) {
            return;
        }
        SwtUiThreadDispatcher.runOnUiThread(() -> {
            for (Command c : commands) {
                c.execute();
            }
        });
    }

    /**
     * Undo {@code commands} in REVERSE order on the SWT UI thread (the
     * composer two-arm {@code finally} reset, so the combined compound can be
     * dispatched as ONE undo entry). No-op on a
     * {@code null}/empty list. Same {@link SwtUiThreadDispatcher} re-throw
     * contract as {@link #replayForward(List)}.
     *
     * @param commands the element arm's accepted commands; may be empty/null
     */
    static void undoReverse(List<Command> commands) {
        if (commands == null || commands.isEmpty()) {
            return;
        }
        SwtUiThreadDispatcher.runOnUiThread(() -> {
            for (int i = commands.size() - 1; i >= 0; i--) {
                commands.get(i).undo();
            }
        });
    }
}
