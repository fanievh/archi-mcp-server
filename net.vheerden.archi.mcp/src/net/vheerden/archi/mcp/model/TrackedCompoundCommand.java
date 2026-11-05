package net.vheerden.archi.mcp.model;

import java.util.List;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;

/**
 * A plain GEF compound that knows how far its last run got, so the commands that completed before one
 * threw can be undone on their own. A batch commit holds its queued commands in one.
 *
 * <p>GEF's {@link CompoundCommand#execute()} stops at a command that throws and undoes nothing, and the
 * stack then pushes no entry, so the commands before it would stay applied with no undo step. Run on its
 * own, it puts them back through {@link #undoCompleted()}. Inside an agent's entry,
 * {@link AgentAuthoredCompoundCommand} opens it and runs its commands itself, at every level, and names the
 * operation that failed by its index in this compound. The command that threw is not undone: its
 * {@code undo()} was written for a run that completed.</p>
 *
 * <p>On its own, a run that completes undoes and redoes exactly as {@link CompoundCommand} does. Inside an
 * agent's entry, the entry opens it for the undo and the redo too, so an undo carries on past a command that
 * fails and a redo that fails puts back what it had redone (see {@link AgentAuthoredCompoundCommand}); with
 * nothing failing, that is again what {@link CompoundCommand} does. It is a plain compound, not Archi's
 * notifying one, so it nests inside that one and leaves its notices to it.</p>
 */
final class TrackedCompoundCommand extends CompoundCommand {

    /** How many commands of the last run returned normally. */
    private int completed;

    /** The index of the command that threw in the last run, or -1. */
    private int failedAt = -1;

    TrackedCompoundCommand(String label) {
        super(label);
    }

    /**
     * Runs each command in order, counting those that complete. A command that throws stops the run, and
     * what it threw is rethrown as the same instance.
     */
    @Override
    public void execute() {
        completed = 0;
        failedAt = -1;
        List<?> commands = getCommands();
        for (int i = 0; i < commands.size(); i++) {
            try {
                ((Command) commands.get(i)).execute();
            } catch (RuntimeException | Error e) {
                failedAt = i;
                throw e;
            }
            completed++;
        }
    }

    /** The index of the command that threw in the last run, or -1 when none did. */
    int getFailedAt() {
        return failedAt;
    }

    /**
     * Undoes the commands that completed in the last run, latest first, carrying on past one whose undo
     * fails.
     *
     * @return the first failure, with every later one suppressed on it, or null when all were undone
     */
    Throwable undoCompleted() {
        List<?> commands = getCommands();
        Throwable first = null;
        for (int i = completed - 1; i >= 0; i--) {
            try {
                ((Command) commands.get(i)).undo();
            } catch (RuntimeException | Error e) {
                if (first == null) {
                    first = e;
                } else if (e != first) {
                    first.addSuppressed(e);
                }
            }
        }
        completed = 0;
        return first;
    }
}
