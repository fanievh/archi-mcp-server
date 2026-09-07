package net.vheerden.archi.mcp.model;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;

/**
 * A command that records what its subject was called at the moment it destroyed it.
 *
 * <p><strong>Why this exists.</strong> A deletion's report is the only surviving description of an
 * entity that no longer exists. Every other value a bulk call reports can be corrected after
 * dispatch by resolving the entity's id and reading the model; a deletion cannot, because the id
 * stops resolving the instant the delete applies. So the name has to be taken while the entity is
 * still there, and the only code that is running at that moment is the deleting command itself.
 * Without this, a call that renames and then deletes the same entity reports the name from before
 * the rename — a name the entity had already stopped using, and one the caller has no way to
 * falsify.</p>
 *
 * <p><strong>Why not read the detached object afterwards.</strong> An EMF object removed from its
 * container is detached, not destroyed: the command still holds it and {@code getName()} still
 * answers, which makes a post-dispatch read look like a cheaper route to the same value. It is not
 * the same value. In {@code [delete X, rename X]} the delete runs first and detaches X, the rename
 * then writes onto the detached object, and a read afterwards reports the deletion as having
 * destroyed a name the entity never carried while it existed. Only a capture taken inside
 * {@code execute()} is a fact about the moment of destruction.</p>
 *
 * <p><strong>A command that destroyed nothing captures nothing.</strong> Two cases, one rule. A
 * {@link CommitSkippableCommand} can re-check its preconditions and return before touching the
 * model. And a redundant delete — the same subject deleted twice in one call, which prepares
 * cleanly because nothing has executed when both are prepared — runs in full but removes nothing,
 * because an earlier command already took the subject out. Both must answer null here, so the name
 * the operation was prepared with stands.</p>
 *
 * <p>That is why implementations gate the capture on the removal's own outcome rather than simply
 * reading the subject on the way past. The detached-object hazard above is not confined to the
 * {@code [delete, rename]} ordering: in {@code [delete X, rename X, delete X]} the second delete's
 * subject is a detached object that the rename has since relabelled, so a capture taken without
 * the gate would report that delete as having destroyed a name the entity never carried while it
 * existed — the same lie, one ordering over.</p>
 */
interface NameCapturingCommand {

    /**
     * @return the name this command's subject carried when the command executed, or {@code null} if
     *         it has not executed or declined to run. Only meaningful after execution.
     */
    String getNameAtExecute();

    /**
     * The name recorded by the first command under {@code command} that destroyed something.
     *
     * <p>Walks into compounds, because one requested operation is not always one command: a force
     * {@code delete-specialization} is a compound of the clears that detach the profile followed by
     * the removal that destroys it, and only the last of those knows the name. Mirrors
     * {@link CommitSkippableCommand#collectSkipReasons}, which walks the same shapes for the same
     * reason.</p>
     *
     * <p>A folder's cascade is deliberately out of reach here: {@code DeleteFolderCommand} holds the
     * commands that empty it in a field rather than as compound children, so this returns the
     * folder's own name and never one of its contents'.</p>
     *
     * @param command one operation's command, possibly a compound
     * @return the captured name, or null when nothing under it captured one
     */
    static String findNameAtExecute(Command command) {
        if (command instanceof NameCapturingCommand capturing
                && capturing.getNameAtExecute() != null) {
            return capturing.getNameAtExecute();
        }
        if (command instanceof CompoundCommand compound) {
            for (Object child : compound.getCommands()) {
                String found = findNameAtExecute((Command) child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
