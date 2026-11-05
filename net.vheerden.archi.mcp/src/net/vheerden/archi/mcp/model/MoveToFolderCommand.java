package net.vheerden.archi.mcp.model;

import java.util.List;
import java.util.Objects;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateModelObject;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.INameable;

/**
 * GEF Command that moves a model object between folders.
 *
 * <p>Supports moving elements, relationships, views, and subfolders.
 * Uses EMF single-containment semantics: adding to target auto-removes
 * from source. Where the object is when the move runs (its folder, index and next sibling) is
 * recorded then, and undo returns it there: an earlier operation in the same request may have
 * moved it, or declined to, since this command was prepared.</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 *
 * <p><strong>Live-tree guards.</strong> The accessor validates three preconditions at prepare
 * time by reading the live folder tree: a folder is not moved into its own subtree (would form a
 * containment cycle), a view stays within the Views hierarchy, and a concept stays under its
 * governing layer. On the deferred paths — a queued batch or a multi-operation bulk request —
 * every operation is prepared before any of them runs, so an earlier move can re-parent the
 * folders and make a valid-at-prepare move invalid by the time it executes. A cycle would silently
 * detach the whole subtree from the model; a mis-filed view or concept leaves the model unsaveable
 * (host Archi's save-time checkIntegrity refuses it). So all three guards are re-checked here at
 * execution time and the move declines rather than corrupts — see {@link CommitSkippableCommand}.</p>
 */
public class MoveToFolderCommand extends Command implements CommitSkippableCommand {

    private final EObject object;
    private final boolean isFolder;
    private final IFolder sourceFolder;
    private final int sourceIndex;
    private final IFolder targetFolder;

    // Captured at construction (prepare time), while the tree is sane and sourceFolder is attached,
    // so the execute-time view/layer re-checks have a stable model reference. Derived lazily at
    // execute instead, the target folder may already be detached by an earlier move and yield null.
    private final IArchimateModel model;

    // Where the object was when the move last ran: the folder, its index there, and the sibling after
    // it. undo() returns it directly before that sibling rather than at the absolute index, so order
    // survives a compound that moves several siblings out of the same folder and undoes them in
    // reverse. Recorded at the move, not at prepare: an earlier operation in the same request can
    // have moved the object, or declined to, so the folder it leaves is only known when it runs.
    private IFolder movedFrom;
    private int movedFromIndex;
    private EObject movedFromSuccessor;

    /** Non-null once {@link #execute()} has declined to run. See {@link CommitSkippableCommand}. */
    private String skipReason;

    /** Whether the move actually happened, so {@link #undo()} reverses only what occurred. */
    private boolean moved;

    /** Set where the last run declined with nothing it wrote left behind. */
    private boolean changedNothing;

    /**
     * Creates a command to move an object between folders.
     *
     * @param object       the EMF object to move (element, relationship, view, or folder)
     * @param isFolder     true if the object is an IFolder (uses getFolders() list)
     * @param sourceFolder the parent folder when the move is prepared, attached to the model
     * @param sourceIndex  the object's index there; undo falls back to it only when the object is not
     *                     found in the folder it leaves
     * @param targetFolder the target parent folder
     */
    public MoveToFolderCommand(EObject object, boolean isFolder,
            IFolder sourceFolder, int sourceIndex, IFolder targetFolder) {
        this.object = Objects.requireNonNull(object, "object must not be null");
        this.isFolder = isFolder;
        this.sourceFolder = Objects.requireNonNull(sourceFolder, "sourceFolder must not be null");
        this.sourceIndex = sourceIndex;
        this.targetFolder = Objects.requireNonNull(targetFolder, "targetFolder must not be null");
        // sourceFolder is attached to the model at construction (prepare time), so this is the
        // model even if a later co-queued move detaches the target before this command executes.
        this.model = sourceFolder.getArchimateModel();

        String name = (object instanceof INameable nameable)
                ? nameable.getName() : object.toString();
        setLabel("Move to folder: " + name);
    }

    @Override
    public void execute() {
        applyMove();
    }

    @Override
    public void redo() {
        applyMove();
    }

    @Override
    public void undo() {
        // Reverse only what actually happened. A declined move changed nothing, so there is
        // nothing to restore — restoring would wrongly insert the object into a folder it
        // never left.
        if (!moved) {
            return;
        }
        // Move back to the folder it left, at the slot it left.
        // EMF single-containment auto-removes from target when added to source
        // (same semantics as moveToTarget).
        if (isFolder) {
            SiblingUndoAnchor.restore(movedFrom.getFolders(), (IFolder) object,
                    movedFromIndex, movedFromSuccessor);
        } else {
            SiblingUndoAnchor.restore(movedFrom.getElements(), object,
                    movedFromIndex, movedFromSuccessor);
        }
    }

    @Override
    public String getSkipReason() {
        return skipReason;
    }

    /** True when the last run declined: every guard is checked before the move writes. */
    @Override
    public boolean changedNothing() {
        return changedNothing;
    }

    private void applyMove() {
        moved = false;
        changedNothing = false;
        skipReason = describeSkip();
        if (skipReason != null) {
            changedNothing = true;
            return;
        }
        recordWhereItIs();
        moveToTarget();
        moved = true;
    }

    /** Records the folder the object is in now, its index there and the sibling after it, for undo. */
    private void recordWhereItIs() {
        movedFrom = object.eContainer() instanceof IFolder container ? container : sourceFolder;
        List<?> siblings = isFolder ? movedFrom.getFolders() : movedFrom.getElements();
        int index = siblings.indexOf(object);
        movedFromIndex = index >= 0 ? index : sourceIndex;
        movedFromSuccessor = (EObject) SiblingUndoAnchor.successorOf(siblings, object);
    }

    /**
     * Re-checks, at execution time, every precondition that reads the live folder tree and could
     * have gone stale since this command was prepared — an earlier operation in the same batch or
     * bulk request may have re-parented the folders. Returns the first reason to decline, or
     * {@code null} when the move is safe.
     */
    private String describeSkip() {
        String reason = describeDetachedObject();
        if (reason == null) {
            reason = describeDetachedTarget();
        }
        if (reason == null) {
            reason = describeCircularMove();
        }
        if (reason == null) {
            reason = describeViewHierarchyViolation();
        }
        if (reason == null) {
            reason = describeLayerMismatch();
        }
        return reason;
    }

    /**
     * Re-checks, at execution time, that the object itself is still in the model. An earlier operation
     * in the same request can have removed it, or the folder it was in; moving it then would put back
     * into the model what that operation removed. Declining leaves it removed.
     *
     * @return a plain-language reason to decline, or {@code null} when the object is in the model
     */
    private String describeDetachedObject() {
        if (!(object instanceof IArchimateModelObject inModel) || inModel.getArchimateModel() == model) {
            return null;
        }
        return "'" + displayObjectName() + "' was not moved into '" + displayName(targetFolder)
                + "': it is no longer in the model. It was removed " + CHANGED_SINCE_PREPARED
                + ", so it was left removed and nothing else was affected.";
    }

    /**
     * Re-checks, at execution time, that the target folder is in the model.
     *
     * <p>It can have left since this command was prepared: an earlier operation in the same batch or
     * bulk request deleted it, or it was to be created by an earlier operation that declined. Adding
     * the object into a folder the model does not hold would take the object, and everything under a
     * moved folder, out of the model with it. Declining leaves it where it is.</p>
     *
     * @return a plain-language reason to decline, or {@code null} when the target is in the model
     */
    private String describeDetachedTarget() {
        if (targetFolder.getArchimateModel() == model) {
            return null;
        }
        return "'" + displayObjectName() + "' was not moved into '" + displayName(targetFolder)
                + "': that folder is not in the model. It was removed " + CHANGED_SINCE_PREPARED
                + ", or the operation that was to create it did not. Moving into it would have taken '"
                + displayObjectName() + "' out of the model as well, so it was left where it was and "
                + "nothing else was affected.";
    }

    /**
     * Re-checks, at execution time, that moving this folder into the target would not create a
     * containment cycle.
     *
     * <p>The target folder may have descended below the folder being moved since this command was
     * prepared, because an earlier operation in the same batch or bulk request re-parented the
     * folders. Adding the folder into a target that now sits inside it would form a cycle, and EMF
     * single-containment would silently pull the folder and everything under it out of the model —
     * unreachable from the root, unsaved, lost on reload. Declining leaves the folder where it is
     * so nothing is lost.</p>
     *
     * <p>Only folder moves can create such a cycle; element, relationship and view moves cannot,
     * and always return {@code null} here.</p>
     *
     * @return a plain-language reason to decline, or {@code null} when the move is safe
     */
    private String describeCircularMove() {
        if (isFolder && FolderOperations.isOrDescendsFrom(targetFolder, (IFolder) object)) {
            return "Folder '" + displayName((IFolder) object) + "' was not moved into '"
                    + displayName(targetFolder) + "': that target is now inside the folder being "
                    + "moved, because the folders were re-parented " + CHANGED_SINCE_PREPARED
                    + ". Completing the move would have detached the folder and its "
                    + "contents from the model, so it was left in place and nothing is lost.";
        }
        return null;
    }

    /**
     * Re-checks, at execution time, that moving this view (diagram) into the target would not land
     * it outside the Views (DIAGRAMS) hierarchy.
     *
     * <p>The target folder may have been re-parented out of the Views hierarchy since this command
     * was prepared, because an earlier operation in the same request moved it. A view filed outside
     * Views leaves the model unsaveable — host Archi's save-time checkIntegrity refuses it — so the
     * move declines and the view stays where it legitimately is. Only view moves can violate this;
     * every other move returns {@code null} here.</p>
     *
     * @return a plain-language reason to decline, or {@code null} when the move is safe
     */
    private String describeViewHierarchyViolation() {
        if (model == null || !(object instanceof IDiagramModel)) {
            return null;
        }
        if (!FolderOperations.isViewTargetOutsideDiagrams(model, targetFolder)) {
            return null;
        }
        return "View '" + displayObjectName() + "' was not moved into '" + displayName(targetFolder)
                + "': that folder is now outside the Views hierarchy, because it was re-parented "
                + CHANGED_SINCE_PREPARED + ". A view filed outside the Views folder leaves "
                + "the model unable to be saved, so it was left in place.";
    }

    /**
     * Re-checks, at execution time, that moving this concept (element or relationship) into the
     * target would not mis-file it under a folder whose ArchiMate layer differs from the concept's
     * governing layer.
     *
     * <p>The target folder may have been re-rooted under a different layer since this command was
     * prepared, because an earlier operation in the same request moved it. A concept under the
     * wrong layer folder leaves the model unsaveable — host Archi's save-time checkIntegrity
     * refuses it — so the move declines and the concept stays where it legitimately is. Only
     * concept moves can violate this; every other move returns {@code null} here.</p>
     *
     * @return a plain-language reason to decline, or {@code null} when the move is safe
     */
    private String describeLayerMismatch() {
        if (model == null || !(object instanceof IArchimateConcept concept)) {
            return null;
        }
        if (!FolderOperations.hasLayerMismatch(model, concept, targetFolder)) {
            return null;
        }
        return "'" + displayObjectName() + "' was not moved into '" + displayName(targetFolder)
                + "': that folder is now under a different ArchiMate layer than this object "
                + "belongs to, because it was re-parented " + CHANGED_SINCE_PREPARED + ". A "
                + "concept filed under the wrong layer leaves the model unable to be saved, so it "
                + "was left in place.";
    }

    /** A never-null folder name for the agent-facing skip reason. */
    private static String displayName(IFolder folder) {
        String name = folder.getName();
        return (name == null || name.isEmpty()) ? "(unnamed folder)" : name;
    }

    /** A never-null name for the moved object in the agent-facing skip reason. */
    private String displayObjectName() {
        String name = (object instanceof INameable nameable) ? nameable.getName() : null;
        return (name == null || name.isEmpty()) ? "(unnamed)" : name;
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
