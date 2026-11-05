package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The writes a command makes, each recorded with exactly what reversing it needs, so the command
 * can be undone, redone and rolled back without reading the model again.
 *
 * <p>Each step is recorded before it is made, so that a step which fails after writing part of
 * itself (an editor listener throwing on the notification, say) is reverted with the rest. Every
 * revert therefore tolerates a step that was only partly made.</p>
 *
 * <p>An undo or a redo that fails partway leaves the command not applied, as far as it can. The
 * command stack drops an entry whose undo or redo throws from both its stacks, so after the throw
 * nothing can reach this command again, and the stack goes on as if it had never been applied. An
 * undo therefore reverts every step past one that fails, and a redo that fails reverts what it had
 * made again, the failing step included. Both then rethrow the first failure, preferring an
 * {@code Error}, with every other failure suppressed on it once.</p>
 */
final class RecordedSteps {

    /** One recorded write, with exactly what reversing it needs. */
    interface Step {
        void apply();

        void revert();
    }

    private final List<Step> steps = new ArrayList<>();

    /** Forgets every recorded step. */
    void clear() {
        steps.clear();
    }

    /** Records the step, then makes it. */
    void apply(Step step) {
        steps.add(step);
        step.apply();
    }

    /**
     * Reverses every recorded step, latest first, carrying on past one that fails; then throws the
     * first failure, preferring an {@code Error}, with every later one suppressed on it once.
     */
    void undo() {
        Throwable first = revertFrom(steps.size() - 1, new ArrayList<>());
        if (first != null) {
            throw unchecked(first);
        }
    }

    /**
     * Makes every recorded step again, in the order it was first made. When one fails, reverts it
     * and every step made before it in this redo, latest first, carrying on past a revert that fails;
     * then throws the step's failure, unless a revert threw an {@code Error}, which is thrown in its
     * place. Every other failure is suppressed on the one thrown, once.
     */
    void redo() {
        for (int i = 0; i < steps.size(); i++) {
            try {
                steps.get(i).apply();
            } catch (RuntimeException | Error e) {
                throw unchecked(preferError(e, revertFrom(i, new ArrayList<>(List.of(e)))));
            }
        }
    }

    /**
     * Reverts every recorded step, latest first, carrying on past one that fails; returns the first
     * failure, preferring an {@code Error}, with every later one suppressed on it once. A failure met
     * again, {@code writeFailure} included, is not suppressed a second time.
     */
    Throwable rollBack(Throwable writeFailure) {
        return revertFrom(steps.size() - 1, new ArrayList<>(List.of(writeFailure)));
    }

    /**
     * Reverts the steps from {@code last} down to the first, carrying on past one that fails; returns
     * the first failure, preferring an {@code Error}, with every later one suppressed on it once. A
     * failure already in {@code met} is not suppressed a second time.
     */
    private Throwable revertFrom(int last, List<Throwable> met) {
        Throwable first = null;
        for (int i = last; i >= 0; i--) {
            try {
                steps.get(i).revert();
            } catch (RuntimeException | Error e) {
                boolean again = isAmong(e, met);
                met.add(e);
                if (first == null) {
                    first = e;
                } else if (e instanceof Error && !(first instanceof Error)) {
                    suppress(e, first);
                    first = e;
                } else if (!again) {
                    suppress(first, e);
                }
            }
        }
        return first;
    }

    /**
     * Which of a write's failure and the failure of reverting it reaches the caller: the write's,
     * unless only the revert's is an {@code Error}. The other is suppressed on it.
     */
    static Throwable preferError(Throwable writeFailure, Throwable revertFailure) {
        if (revertFailure instanceof Error && !(writeFailure instanceof Error)) {
            suppress(revertFailure, writeFailure);
            return revertFailure;
        }
        suppress(writeFailure, revertFailure);
        return writeFailure;
    }

    /** {@code failure}, which is unchecked, typed so a {@code throw} accepts it. */
    private static RuntimeException unchecked(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        return (RuntimeException) failure;
    }

    /**
     * Adds {@code other} to {@code on}'s suppressed list, unless it is absent, {@code on} itself or
     * already listed there.
     */
    static void suppress(Throwable on, Throwable other) {
        if (other != null && other != on && !isAmong(other, List.of(on.getSuppressed()))) {
            on.addSuppressed(other);
        }
    }

    /** Whether {@code failure} is one of {@code failures}, by identity. */
    private static boolean isAmong(Throwable failure, List<Throwable> failures) {
        for (Throwable each : failures) {
            if (each == failure) {
                return true;
            }
        }
        return false;
    }

    /** A failure as a skip reason prints it: its message, or its class when it has none. */
    static String reason(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
