package net.vheerden.archi.mcp.model;

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
 *   <li>Disconnect all view connections representing this relationship</li>
 *   <li>Disconnect the model relationship from source/target elements</li>
 *   <li>Remove the relationship from its folder</li>
 * </ol>
 *
 * <p>The connected elements are NOT deleted. All cascade state is discovered
 * at prepare time and passed to the constructor.</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class DeleteRelationshipCommand extends Command implements NameCapturingCommand {

    private final IArchimateRelationship relationship;
    private final IFolder folder;
    private final int indexInFolder;
    private final IArchimateConcept source;
    private final IArchimateConcept target;
    private final List<IDiagramModelConnection> viewConnectionsToDisconnect;

    // Surviving-successor anchor captured at construction (prepare time). undo()
    // restores the relationship directly before this sibling rather than at the
    // stale absolute index, so folder order survives a compound that deletes
    // several relationships from the same folder and undoes them in reverse.
    private final EObject relationshipSuccessor;

    private String nameAtExecute;

    /**
     * Creates a command to delete a relationship and cascade-remove view connections.
     *
     * @param relationship               the relationship to delete
     * @param folder                     the folder containing the relationship
     * @param indexInFolder              the relationship's index in the folder
     * @param source                     the source concept (captured for undo reconnect)
     * @param target                     the target concept (captured for undo reconnect)
     * @param viewConnectionsToDisconnect view connections to cascade-disconnect
     */
    public DeleteRelationshipCommand(IArchimateRelationship relationship,
                                      IFolder folder,
                                      int indexInFolder,
                                      IArchimateConcept source,
                                      IArchimateConcept target,
                                      List<IDiagramModelConnection> viewConnectionsToDisconnect) {
        this.relationship = Objects.requireNonNull(relationship, "relationship must not be null");
        this.folder = Objects.requireNonNull(folder, "folder must not be null");
        this.indexInFolder = indexInFolder;
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.target = Objects.requireNonNull(target, "target must not be null");
        this.viewConnectionsToDisconnect = List.copyOf(viewConnectionsToDisconnect);
        this.relationshipSuccessor = SiblingUndoAnchor.successorOf(folder.getElements(), relationship);
        setLabel("Delete " + relationship.eClass().getName());
    }

    @Override
    public void execute() {
        nameAtExecute = null;

        // 1. Disconnect all view connections
        for (IDiagramModelConnection conn : viewConnectionsToDisconnect) {
            conn.disconnect();
        }

        // 2. Disconnect model relationship from source/target
        relationship.disconnect();

        // 3. Remove relationship from folder, recording what it was called as it goes. Kept only
        // when the removal removed something — see DeleteElementCommand for why a redundant
        // delete must not report the name its detached subject carries by then.
        String subjectName = relationship.getName();
        if (folder.getElements().remove(relationship)) {
            nameAtExecute = subjectName;
        }
    }

    @Override
    public void undo() {
        // 3. Re-add relationship to folder, anchored to its surviving successor
        SiblingUndoAnchor.restore(folder.getElements(), relationship, indexInFolder,
                relationshipSuccessor);

        // 2. Reconnect model relationship
        relationship.connect(source, target);

        // 1. Reconnect all view connections
        for (IDiagramModelConnection conn : viewConnectionsToDisconnect) {
            conn.reconnect();
        }
    }

    // Default redo() calls execute() — safe because all state is in final fields

    @Override
    public String getNameAtExecute() {
        return nameAtExecute;
    }

    /** Package-visible for testing. */
    IArchimateRelationship getRelationship() { return relationship; }

    /** Package-visible for testing. */
    int getViewConnectionsRemoved() { return viewConnectionsToDisconnect.size(); }
}
