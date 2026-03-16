package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IFolder;

/**
 * GEF Command that deletes a folder from the model (Story 8-4).
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
public class DeleteFolderCommand extends Command {

    private final IFolder folder;
    private final IFolder parentFolder;
    private final int folderIndex;
    private final List<Command> subCommands;

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
        setLabel("Delete folder: " + folder.getName());
    }

    @Override
    public void execute() {
        // 1. Execute all sub-commands (cascade-delete contents)
        for (Command cmd : subCommands) {
            cmd.execute();
        }

        // 2. Remove folder from parent
        parentFolder.getFolders().remove(folder);
    }

    @Override
    public void redo() {
        // 1. Redo all sub-commands
        for (Command cmd : subCommands) {
            cmd.redo();
        }

        // 2. Remove folder from parent
        parentFolder.getFolders().remove(folder);
    }

    @Override
    public void undo() {
        // 2. Re-add folder to parent
        if (folderIndex >= 0 && folderIndex <= parentFolder.getFolders().size()) {
            parentFolder.getFolders().add(folderIndex, folder);
        } else {
            parentFolder.getFolders().add(folder);
        }

        // 1. Undo all sub-commands in reverse order
        for (int i = subCommands.size() - 1; i >= 0; i--) {
            subCommands.get(i).undo();
        }
    }

    /** Package-visible for testing. */
    IFolder getFolder() { return folder; }

    /** Package-visible for testing. */
    IFolder getParentFolder() { return parentFolder; }

    /** Package-visible for testing. Returns an unmodifiable view. */
    List<Command> getSubCommands() { return List.copyOf(subCommands); }
}
