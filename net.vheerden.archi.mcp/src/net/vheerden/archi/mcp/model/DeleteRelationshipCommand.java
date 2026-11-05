package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IFolder;

/**
 * GEF Command that deletes an ArchiMate relationship with cascade.
 *
 * <p>Cascade removal order on execute:</p>
 * <ol>
 *   <li>Disconnect all view connections representing this relationship or one attached to it</li>
 *   <li>Disconnect and remove every relationship attached to this one</li>
 *   <li>Disconnect the model relationship from source/target elements</li>
 *   <li>Remove the relationship from its folder</li>
 * </ol>
 *
 * <p>The connected elements are NOT deleted. A relationship attached to this one (an association to
 * it, say), and any attached to that, is deleted with it, as Archi's own delete does: left behind,
 * its end would no longer be in the model. What the delete takes is found when the command first
 * runs, by {@link DeleteCascade}, against the model as the command meets it, so a connection an
 * earlier operation in the same request added — on a view it cloned, created or already held — is
 * removed too. Redo replays what the first run removed. A relationship
 * an earlier operation already removed is not removed a second time, and its undo restores
 * nothing.</p>
 *
 * <p>The cascade found at prepare is kept as a projection: until the first run,
 * {@link #getViewConnectionsToDisconnect()} answers it, for the batch's readers; after it, what the
 * run disconnected.</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class DeleteRelationshipCommand extends Command implements NameCapturingCommand {

    private final IArchimateRelationship relationship;
    private final DeleteCascade.OfRelationship projection;

    /** What the first run removed and where it goes back; {@code null} until it runs. */
    private Removal removal;

    private String nameAtExecute;

    /**
     * Creates a command to delete a relationship and cascade-remove view connections.
     *
     * @param relationship the relationship to delete
     * @param projection   its cascade as found at prepare; answered to the batch's readers until the
     *                     command runs, never removed as such
     */
    DeleteRelationshipCommand(IArchimateRelationship relationship, DeleteCascade.OfRelationship projection) {
        this.relationship = Objects.requireNonNull(relationship, "relationship must not be null");
        this.projection = Objects.requireNonNull(projection, "projection must not be null");
        setLabel("Delete " + relationship.eClass().getName());
    }

    @Override
    public void execute() {
        nameAtExecute = null;
        if (removal == null) {
            removal = Removal.of(relationship);
        }
        if (removal.folder == null) {
            return;
        }

        // 1. Disconnect all view connections
        for (IDiagramModelConnection conn : removal.cascade.viewConnections()) {
            conn.disconnect();
        }

        // 2. Disconnect and remove the relationships attached to this one
        for (DeleteElementCommand.CascadedRelationship rel : removal.cascade.relationships()) {
            rel.relationship().disconnect();
            rel.folder().getElements().remove(rel.relationship());
        }

        // 3. Disconnect model relationship from source/target
        relationship.disconnect();

        // 4. Remove relationship from folder, recording what it was called as it goes. Kept only
        // when the removal removed something — see DeleteElementCommand for why.
        String subjectName = relationship.getName();
        if (removal.folder.getElements().remove(relationship)) {
            nameAtExecute = subjectName;
        }
    }

    @Override
    public void undo() {
        if (removal == null || removal.folder == null) {
            return;
        }
        // 4. Re-add relationship to folder, anchored to its surviving successor
        SiblingUndoAnchor.restore(removal.folder.getElements(), relationship, removal.index,
                removal.successor);

        // 3. Reconnect model relationship
        relationship.connect(removal.source, removal.target);

        // 2. Re-add and reconnect the attached relationships (reverse order preserves indices)
        List<DeleteElementCommand.CascadedRelationship> attached = removal.cascade.relationships();
        for (int i = attached.size() - 1; i >= 0; i--) {
            DeleteElementCommand.CascadedRelationship rel = attached.get(i);
            SiblingUndoAnchor.restore(rel.folder().getElements(), rel.relationship(), rel.indexInFolder(),
                    removal.attachedSuccessors.get(i));
            rel.relationship().connect(rel.source(), rel.target());
        }

        // 1. Reconnect all view connections
        for (IDiagramModelConnection conn : removal.cascade.viewConnections()) {
            conn.reconnect();
        }
    }

    // Default redo() calls execute(), which replays the first run's removal.

    @Override
    public String getNameAtExecute() {
        return nameAtExecute;
    }

    /** Package-visible for testing. */
    IArchimateRelationship getRelationship() { return relationship; }

    /** Package-visible for testing. */
    int getViewConnectionsRemoved() { return getViewConnectionsToDisconnect().size(); }

    /** Package-visible for testing. */
    int getRelationshipsRemoved() { return cascade().relationships().size(); }

    /** Whether {@code relationship} is one this delete removes along with its own relationship. */
    boolean removesRelationship(Object relationship) {
        return cascade().relationships().stream().anyMatch(cascaded -> cascaded.relationship() == relationship);
    }

    /** The view connections this delete disconnects: the projection until it runs, then what it disconnected. */
    List<IDiagramModelConnection> getViewConnectionsToDisconnect() {
        return cascade().viewConnections();
    }

    private DeleteCascade.OfRelationship cascade() {
        return removal == null ? projection : removal.cascade;
    }

    /**
     * What one run removes. Each successor is the sibling that followed the removed relationship when
     * this command met the model, so undo restores each folder as this command found it.
     *
     * @param folder {@code null} when the relationship was in no folder when the command ran: an
     *               earlier operation already removed it, and there is nothing to remove or restore
     */
    private record Removal(IFolder folder, int index, EObject successor, IArchimateConcept source,
            IArchimateConcept target, DeleteCascade.OfRelationship cascade, List<EObject> attachedSuccessors) {

        static Removal of(IArchimateRelationship relationship) {
            if (!(relationship.eContainer() instanceof IFolder folder)) {
                return new Removal(null, -1, null, null, null,
                        new DeleteCascade.OfRelationship(List.of(), List.of(), 0), List.of());
            }
            DeleteCascade.OfRelationship cascade = DeleteCascade.ofRelationship(relationship);
            // A removed relationship that was last in its folder has no successor, so this list can
            // hold null and uses unmodifiableList rather than List.copyOf.
            List<EObject> attachedSuccessors = new ArrayList<>(cascade.relationships().size());
            for (DeleteElementCommand.CascadedRelationship rel : cascade.relationships()) {
                attachedSuccessors.add(SiblingUndoAnchor.successorOf(rel.folder().getElements(), rel.relationship()));
            }
            return new Removal(folder, folder.getElements().indexOf(relationship),
                    SiblingUndoAnchor.successorOf(folder.getElements(), relationship),
                    relationship.getSource(), relationship.getTarget(), cascade,
                    Collections.unmodifiableList(attachedSuccessors));
        }
    }
}
