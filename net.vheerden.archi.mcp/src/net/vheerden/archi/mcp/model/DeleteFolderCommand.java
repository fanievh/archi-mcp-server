package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IFolder;

/**
 * GEF Command that deletes a folder from the model.
 *
 * <p>For empty folders, simply removes the folder from its parent. For
 * non-empty folders with {@code force=true}, executes a list of sub-commands
 * (DeleteElementCommand, DeleteRelationshipCommand, DeleteViewCommand, and
 * recursive DeleteFolderCommand) depth-first before removing the folder.</p>
 *
 * <p>Sub-commands are built at prepare time by the accessor and passed to the
 * constructor. The command only orchestrates their execution order.</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class DeleteFolderCommand extends Command
        implements CommitSkippableCommand, NameCapturingCommand {

    private final IFolder folder;
    private final IFolder parentFolder;
    private final int folderIndex;
    private final List<Command> subCommands;

    // Surviving-successor anchor captured at construction (prepare time). undo()
    // re-inserts the folder directly before this sibling rather than at the stale
    // absolute index, so the parent's subfolder order survives a compound that
    // deletes several sibling folders and undoes them in reverse.
    private final IFolder folderSuccessor;

    /**
     * The direct children the folder held when this command was prepared — by object
     * identity, so it does not depend on EMF equality. See {@link #describeUnpreparedContents()}.
     */
    private final Set<Object> preparedChildren;

    /** Non-null once {@link #execute()} has declined to run. See {@link CommitSkippableCommand}. */
    private String skipReason;

    private String nameAtExecute;

    /** Whether the sub-commands actually ran, so {@link #undo()} reverses only what happened. */
    private boolean subCommandsRan;

    /** Whether this folder was actually detached from its parent. */
    private boolean folderRemoved;

    /**
     * Creates a command to delete a folder.
     *
     * <p>For empty folders, {@code subCommands} should be an empty list.
     * For force-delete, the accessor builds the list of sub-commands that
     * delete all contained elements, relationships, views, and subfolders.</p>
     *
     * @param folder       the folder to delete
     * @param parentFolder the parent folder containing this folder
     * @param folderIndex  the folder's index in the parent's subfolder list
     * @param subCommands  commands to execute before removing the folder (for force-delete)
     */
    public DeleteFolderCommand(IFolder folder, IFolder parentFolder,
                                int folderIndex, List<Command> subCommands) {
        this.folder = Objects.requireNonNull(folder, "folder must not be null");
        this.parentFolder = Objects.requireNonNull(parentFolder, "parentFolder must not be null");
        this.folderIndex = folderIndex;
        this.subCommands = new ArrayList<>(subCommands);
        this.folderSuccessor = SiblingUndoAnchor.successorOf(parentFolder.getFolders(), folder);
        this.preparedChildren = Collections.newSetFromMap(new IdentityHashMap<>());
        this.preparedChildren.addAll(folder.getElements());
        this.preparedChildren.addAll(folder.getFolders());
        setLabel("Delete folder: " + folder.getName());
    }

    @Override
    public void execute() {
        subCommandsRan = false;
        folderRemoved = false;
        // Cleared alongside the two flags, and set below only past both declines AND only if the
        // removal actually removed something, so a run that refuses — or that finds the folder
        // already gone — reports no name at all. {@code folderRemoved} deliberately keeps its
        // existing unconditional assignment: it governs undo, and narrowing it here would change
        // reversal behaviour this change has no business touching.
        nameAtExecute = null;

        skipReason = describeUnpreparedContents();
        if (skipReason != null) {
            return;
        }

        // 1. Execute all sub-commands (cascade-delete contents)
        for (Command cmd : subCommands) {
            cmd.execute();
        }
        subCommandsRan = true;

        // 2. Remove folder from parent — unless something inside it declined to go
        skipReason = describeDeclinedSubCommands();
        if (skipReason != null) {
            return;
        }
        String subjectName = folder.getName();
        if (parentFolder.getFolders().remove(folder)) {
            nameAtExecute = subjectName;
        }
        folderRemoved = true;
    }

    @Override
    public void redo() {
        subCommandsRan = false;
        folderRemoved = false;
        // Kept in step with execute() above. This class is the only delete command that overrides
        // redo() rather than inheriting the default that re-runs execute(), so the capture has to
        // be written twice or the field would mean "the name at the last run" in one path and "the
        // name at the first run" in the other. No response is ever built from a redo — the bulk
        // result is projected once, straight after the first dispatch — so this changes nothing
        // that ships; it keeps the field's meaning single.
        nameAtExecute = null;

        skipReason = describeUnpreparedContents();
        if (skipReason != null) {
            return;
        }

        // 1. Redo all sub-commands
        for (Command cmd : subCommands) {
            cmd.redo();
        }
        subCommandsRan = true;

        // 2. Remove folder from parent — unless something inside it declined to go
        skipReason = describeDeclinedSubCommands();
        if (skipReason != null) {
            return;
        }
        String subjectName = folder.getName();
        if (parentFolder.getFolders().remove(folder)) {
            nameAtExecute = subjectName;
        }
        folderRemoved = true;
    }

    @Override
    public void undo() {
        // Reverse exactly what ran. A decline can happen either before the sub-commands
        // (nothing to reverse) or after them (they must still be reversed), so the two
        // steps are tracked separately rather than inferred from skipReason.
        if (folderRemoved) {
            SiblingUndoAnchor.restore(parentFolder.getFolders(), folder, folderIndex,
                    folderSuccessor);
        }

        if (subCommandsRan) {
            for (int i = subCommands.size() - 1; i >= 0; i--) {
                subCommands.get(i).undo();
            }
        }
    }

    @Override
    public String getSkipReason() {
        return skipReason;
    }

    @Override
    public String getNameAtExecute() {
        return nameAtExecute;
    }

    /**
     * Re-checks, at execution time, that this delete is still the delete that was authorised.
     *
     * <p>{@link #preparedChildren} records exactly which direct children the folder held when
     * the request was prepared, and therefore what this delete was permitted to destroy —
     * nothing at all for a plain delete of an empty folder, and the full set for a cascade.
     * Anything the folder holds now that is absent from that set arrived afterwards, from an
     * earlier operation in the same batch or bulk request. The containment removal below would
     * destroy it silently, and because no sub-command was ever built for it, its removal would
     * not be undoable either.</p>
     *
     * <p>The comparison is by object identity against the captured set, not by counting
     * sub-commands: the sub-command list is flattened across the whole folder tree, and a
     * relationship already claimed by a contained element's cascade contributes no command at
     * all, so its size bears no fixed relation to the number of direct children.</p>
     *
     * <p>Losing children between prepare and execute is safe and deliberately permitted —
     * deleting less than was authorised destroys nothing unexpected.</p>
     *
     * @return a plain-language reason to decline, or {@code null} when the delete is safe
     */
    private String describeUnpreparedContents() {
        int unprepared = 0;
        for (Object element : folder.getElements()) {
            if (!preparedChildren.contains(element)) {
                unprepared++;
            }
        }
        for (IFolder subfolder : folder.getFolders()) {
            if (!preparedChildren.contains(subfolder)) {
                unprepared++;
            }
        }
        if (unprepared == 0) {
            return null;
        }
        return "Folder '" + folder.getName() + "' was not empty when the changes were applied: "
                + unprepared + " item(s) were added to it by an earlier operation in the same "
                + "request. The folder and its contents were left in place so nothing is lost. "
                + "Delete it in a separate request, or use force: true there so the cascade "
                + "covers everything the folder holds by then.";
    }

    /**
     * Re-checks whether anything inside this folder declined to be deleted.
     *
     * <p>A cascade is built as a <em>flat</em> list: a nested folder anywhere in the tree
     * contributes its own {@code DeleteFolderCommand} as a direct sibling in this command's
     * sub-command list, each carrying its own view of what it was authorised to remove. When
     * one of those declines it leaves itself attached to its parent — so detaching <em>this</em>
     * folder would carry it, and the content it just protected, out of the model by containment.
     * Its refusal would accomplish nothing.</p>
     *
     * <p>So a decline anywhere below propagates up: this folder stays too. Because the list is
     * flat, checking direct sub-commands catches a decline at any depth. The sub-commands that
     * did run are left applied — they removed only what was authorised — and {@link #undo()}
     * still reverses them.</p>
     *
     * @return a plain-language reason to decline, or {@code null} when nothing below declined
     */
    private String describeDeclinedSubCommands() {
        for (Command cmd : subCommands) {
            if (cmd instanceof CommitSkippableCommand skippable) {
                String nested = skippable.getSkipReason();
                if (nested != null) {
                    return "Folder '" + folder.getName() + "' was left in place because a folder "
                            + "inside it could not be deleted, and removing it would have taken "
                            + "that folder's contents with it. " + nested;
                }
            }
        }
        return null;
    }

    /** Package-visible for testing. */
    IFolder getFolder() { return folder; }

    /** Package-visible for testing. */
    IFolder getParentFolder() { return parentFolder; }

    /** Package-visible for testing. Returns an unmodifiable view. */
    List<Command> getSubCommands() { return List.copyOf(subCommands); }
}
