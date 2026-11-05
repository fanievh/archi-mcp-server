package net.vheerden.archi.mcp.model;

import org.eclipse.gef.commands.Command;

/**
 * Holds a batch operation's place in the queue when the operation has nothing to change: its command
 * contains no command that does anything, as a compound with no children does.
 *
 * <p>A batch commits its whole queue as one compound, and GEF runs a compound only when every part of
 * it can execute. A compound with no children cannot, so queued as it was it would stop the entire
 * commit from running, while the operations around it were reported as committed. This command runs,
 * undoes and redoes as nothing, which is exactly what the operation it stands for would have done, and
 * vouches that it changed nothing, so a batch made only of such operations says so and leaves no undo
 * entry. It keeps the operation's sequence number and description: the caller was already told both.</p>
 */
final class ChangesNothingCommand extends Command implements CommitSkippableCommand {

    ChangesNothingCommand(String label) {
        super(label);
    }

    /** True when {@code command} contains no command that would do anything when run. */
    static boolean holdsNothing(Command command) {
        int[] leaves = { 0 };
        MutationContext.forEachCommand(command, leaf -> leaves[0]++);
        return leaves[0] == 0;
    }

    @Override
    public void execute() {
        // nothing to change
    }

    @Override
    public void undo() {
        // nothing was changed
    }

    @Override
    public void redo() {
        // nothing to replay
    }

    /** Never declined: there was nothing to decline. */
    @Override
    public String getSkipReason() {
        return null;
    }

    @Override
    public boolean changedNothing() {
        return true;
    }
}
