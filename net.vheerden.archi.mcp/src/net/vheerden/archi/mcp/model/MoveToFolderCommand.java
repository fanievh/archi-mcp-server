package net.vheerden.archi.mcp.model;

import java.util.Objects;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IFolder;
import com.archimatetool.model.INameable;

/**
 * GEF Command that moves a model object between folders (Story 8-5).
 *
 * <p>Supports moving elements, relationships, views, and subfolders.
 * Uses EMF single-containment semantics: adding to target auto-removes
 * from source. The source index is captured at construction time for
 * proper undo restoration.</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class MoveToFolderCommand extends Command {

    private final EObject object;
    private final boolean isFolder;
    private final IFolder sourceFolder;
    private final int sourceIndex;
    private final IFolder targetFolder;

    /**
     * Creates a command to move an object between folders.
     *
     * @param object       the EMF object to move (element, relationship, view, or folder)
     * @param isFolder     true if the object is an IFolder (uses getFolders() list)
     * @param sourceFolder the current parent folder
     * @param sourceIndex  the object's index in the source folder's list
     * @param targetFolder the target parent folder
     */
    public MoveToFolderCommand(EObject object, boolean isFolder,
            IFolder sourceFolder, int sourceIndex, IFolder targetFolder) {
        this.object = Objects.requireNonNull(object, "object must not be null");
        this.isFolder = isFolder;
        this.sourceFolder = Objects.requireNonNull(sourceFolder, "sourceFolder must not be null");
        this.sourceIndex = sourceIndex;
        this.targetFolder = Objects.requireNonNull(targetFolder, "targetFolder must not be null");

        String name = (object instanceof INameable nameable)
                ? nameable.getName() : object.toString();
        setLabel("Move to folder: " + name);
    }

    @Override
    public void execute() {
        moveToTarget();
    }

    @Override
    public void redo() {
        moveToTarget();
    }

    @Override
    public void undo() {
        // Move back to source folder at original index.
        // EMF single-containment auto-removes from target when added to source
        // (same semantics as moveToTarget).
        if (isFolder) {
            int safeIndex = Math.min(sourceIndex, sourceFolder.getFolders().size());
            sourceFolder.getFolders().add(safeIndex, (IFolder) object);
        } else {
            int safeIndex = Math.min(sourceIndex, sourceFolder.getElements().size());
            sourceFolder.getElements().add(safeIndex, object);
        }
    }

    private void moveToTarget() {
        if (isFolder) {
            // EMF single-containment: add to target auto-removes from source
            targetFolder.getFolders().add((IFolder) object);
        } else {
            // EMF single-containment: add to target auto-removes from source
            targetFolder.getElements().add(object);
        }
    }

    /** Package-visible for testing. */
    EObject getObject() {
        return object;
    }

    /** Package-visible for testing. */
    boolean isFolder() {
        return isFolder;
    }

    /** Package-visible for testing. */
    IFolder getSourceFolder() {
        return sourceFolder;
    }

    /** Package-visible for testing. */
    IFolder getTargetFolder() {
        return targetFolder;
    }
}
