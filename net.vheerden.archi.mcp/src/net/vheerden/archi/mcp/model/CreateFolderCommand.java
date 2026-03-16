package net.vheerden.archi.mcp.model;

import java.util.Objects;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IFolder;

/**
 * GEF Command that adds a new folder to a parent folder (Story 8-5).
 *
 * <p>The folder must be fully configured (name, documentation, properties)
 * before this command is created. The command only handles placement in the
 * parent folder and removal (undo).</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class CreateFolderCommand extends Command {

    private final IFolder newFolder;
    private final IFolder parentFolder;

    /**
     * Creates a command to add a folder to a parent folder.
     *
     * @param newFolder    the fully-configured folder to add
     * @param parentFolder the parent folder to add it under
     */
    public CreateFolderCommand(IFolder newFolder, IFolder parentFolder) {
        this.newFolder = Objects.requireNonNull(newFolder, "newFolder must not be null");
        this.parentFolder = Objects.requireNonNull(parentFolder, "parentFolder must not be null");
        setLabel("Create folder: " + newFolder.getName());
    }

    @Override
    public void execute() {
        parentFolder.getFolders().add(newFolder);
    }

    @Override
    public void redo() {
        parentFolder.getFolders().add(newFolder);
    }

    @Override
    public void undo() {
        parentFolder.getFolders().remove(newFolder);
    }

    /** Package-visible for testing. */
    IFolder getNewFolder() {
        return newFolder;
    }

    /** Package-visible for testing. */
    IFolder getParentFolder() {
        return parentFolder;
    }
}
