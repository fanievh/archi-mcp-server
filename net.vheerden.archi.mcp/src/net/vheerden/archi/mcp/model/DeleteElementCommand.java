package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;

import org.eclipse.emf.ecore.EObject;

/**
 * GEF Command that deletes an ArchiMate element with full cascade.
 *
 * <p>Cascade removal order on execute:</p>
 * <ol>
 *   <li>Disconnect all view connections (visual connections on diagrams), and every connection
 *       attached to one of them or to a view object below</li>
 *   <li>Remove all view objects (diagram representations of the element)</li>
 *   <li>Disconnect and remove all model relationships involving this element, and every
 *       relationship attached to one of those, transitively</li>
 *   <li>Remove the element from its folder</li>
 * </ol>
 *
 * <p>What the delete takes with it is found when the command first runs, by {@link DeleteCascade},
 * against the model exactly as the command meets it. Inside a batch or a bulk call an earlier
 * operation can have cloned a view that draws the element, placed it on a view, or connected a
 * relationship to it; none of those were in the model when the delete was prepared, and each is
 * removed with it. Redo replays what the first run removed and finds nothing again. An element an
 * earlier operation already removed is not removed a second time, and its undo restores nothing.</p>
 *
 * <p>The cascade found at prepare is kept as a projection: until the first run,
 * {@link #getViewObjectsRemoved()} and {@link #getViewConnectionsToDisconnect()} answer it, for the
 * batch's readers that ask a queued delete what it will take off a view; after it, they answer what
 * the run removed.</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class DeleteElementCommand extends Command implements NameCapturingCommand {

    private final IArchimateElement element;
    private final DeleteCascade.OfElement projection;

    /** What the first run removed and where each piece goes back; {@code null} until it runs. */
    private Removal removal;

    private String nameAtExecute;

    /**
     * Creates a command to delete an element and cascade-remove all dependencies.
     *
     * @param element    the element to delete
     * @param projection its cascade as found at prepare; answered to the batch's readers until the
     *                   command runs, never removed as such
     */
    DeleteElementCommand(IArchimateElement element, DeleteCascade.OfElement projection) {
        this.element = Objects.requireNonNull(element, "element must not be null");
        this.projection = Objects.requireNonNull(projection, "projection must not be null");
        setLabel("Delete " + element.eClass().getName() + ": " + element.getName());
    }

    @Override
    public void execute() {
        nameAtExecute = null;
        if (removal == null) {
            removal = Removal.of(element);
        }
        if (removal.folder == null) {
            return;
        }

        // 1. Disconnect all view connections
        for (IDiagramModelConnection conn : removal.cascade.viewConnections()) {
            conn.disconnect();
        }

        // 2. Remove all view objects from their containers
        for (CascadedViewReference ref : removal.cascade.viewObjects()) {
            ref.container.getChildren().remove(ref.viewObject);
        }

        // 3. Disconnect and remove all relationships
        for (CascadedRelationship rel : removal.cascade.relationships()) {
            rel.relationship.disconnect();
            rel.folder.getElements().remove(rel.relationship);
        }

        // 4. Remove element from folder, recording what it was called as it goes. Read here rather
        // than on the way in: an earlier command in the same compound may have renamed it.
        String subjectName = element.getName();
        if (removal.folder.getElements().remove(element)) {
            nameAtExecute = subjectName;
        }
    }

    @Override
    public void undo() {
        if (removal == null || removal.folder == null) {
            return;
        }
        // 4. Re-add element to folder
        safeAddElement(removal.folder, removal.index, element, removal.successor);

        // 3. Re-add and reconnect relationships (reverse order preserves indices)
        List<CascadedRelationship> relationships = removal.cascade.relationships();
        for (int i = relationships.size() - 1; i >= 0; i--) {
            CascadedRelationship rel = relationships.get(i);
            safeAddElement(rel.folder, rel.indexInFolder, rel.relationship,
                    removal.relationshipSuccessors.get(i));
            rel.relationship.connect(rel.source, rel.target);
        }

        // 2. Re-add view objects (reverse order preserves indices)
        List<CascadedViewReference> viewObjects = removal.cascade.viewObjects();
        for (int i = viewObjects.size() - 1; i >= 0; i--) {
            CascadedViewReference ref = viewObjects.get(i);
            safeAddChild(ref.container, ref.indexInContainer, ref.viewObject,
                    removal.viewObjectSuccessors.get(i));
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

    /** The item that immediately followed {@code item} in {@code list}, or
     *  {@code null} if it was last / not present. Used to capture a stable
     *  re-insertion anchor just before the removal. Relies on EMF default identity
     *  equality for {@code indexOf} (the model types here do not override
     *  {@code equals}), so a value-equal twin cannot shadow the real anchor. */
    private static <T> T successorOf(List<T> list, T item) {
        int i = list.indexOf(item);
        return (i >= 0 && i + 1 < list.size()) ? list.get(i + 1) : null;
    }

    private static void safeAddElement(IFolder folder, int index, EObject element,
                                       EObject successorAnchor) {
        // Already restored → nothing to do. A delete restores only what its own run removed, so a
        // second delete of the same element (queued twice, or overlapping the delete of a folder
        // that holds it) restores nothing; this stays as the net, since re-adding into an EMF
        // containment list, which forbids duplicates, would throw. See SiblingUndoAnchor.restore
        // for the shared-helper twin.
        if (folder.getElements().contains(element)) {
            return;
        }
        int anchorIndex = (successorAnchor != null)
                ? folder.getElements().indexOf(successorAnchor) : -1;
        if (anchorIndex >= 0) {
            folder.getElements().add(anchorIndex, element);
        } else if (index >= 0 && index <= folder.getElements().size()) {
            folder.getElements().add(index, element);
        } else {
            folder.getElements().add(element);
        }
    }

    private static void safeAddChild(IDiagramModelContainer container, int index,
                                      IDiagramModelObject child,
                                      IDiagramModelObject successorAnchor) {
        // Already restored → nothing to do (see safeAddElement): the unique containment list
        // would reject a second add of the same view child.
        if (container.getChildren().contains(child)) {
            return;
        }
        int anchorIndex = (successorAnchor != null)
                ? container.getChildren().indexOf(successorAnchor) : -1;
        if (anchorIndex >= 0) {
            container.getChildren().add(anchorIndex, child);
        } else if (index >= 0 && index <= container.getChildren().size()) {
            container.getChildren().add(index, child);
        } else {
            container.getChildren().add(child);
        }
    }

    /** Package-visible for testing. */
    IArchimateElement getElement() { return element; }

    /** Package-visible for testing. */
    int getRelationshipsRemoved() { return cascade().relationships().size(); }

    /** Whether {@code relationship} is one this delete removes along with the element. */
    boolean removesRelationship(Object relationship) {
        return cascade().relationships().stream().anyMatch(cascaded -> cascaded.relationship() == relationship);
    }

    /** Package-visible for testing. */
    int getViewReferencesRemoved() { return cascade().viewObjects().size(); }

    /** Package-visible for testing. */
    int getViewConnectionsRemoved() { return cascade().viewConnections().size(); }

    /**
     * The view objects this delete takes off their diagrams, read by the batch's queue readers: the
     * projection until the command runs, then what it removed.
     */
    List<IDiagramModelObject> getViewObjectsRemoved() {
        return cascade().viewObjects().stream().<IDiagramModelObject>map(CascadedViewReference::viewObject).toList();
    }

    /** The view connections this delete disconnects: the projection until it runs, then what it disconnected. */
    List<IDiagramModelConnection> getViewConnectionsToDisconnect() { return cascade().viewConnections(); }

    private DeleteCascade.OfElement cascade() {
        return removal == null ? projection : removal.cascade;
    }

    /**
     * What one run removes, with the anchors undo restores each piece by. Every anchor is the item
     * that followed the removed one when this command met the model, so undo puts the model back as
     * this command found it, which is what a compound undoing its members in reverse needs: each
     * member's undo meets the model as that member left it. The absolute index stays as the
     * fallback for an anchor this command removed too.
     *
     * @param folder {@code null} when the element was in no folder when the command ran: an
     *               earlier operation already removed it, and there is nothing to remove or restore
     */
    private record Removal(IFolder folder, int index, EObject successor, DeleteCascade.OfElement cascade,
            List<EObject> relationshipSuccessors, List<IDiagramModelObject> viewObjectSuccessors) {

        static Removal of(IArchimateElement element) {
            if (!(element.eContainer() instanceof IFolder folder)) {
                return new Removal(null, -1, null, new DeleteCascade.OfElement(List.of(), List.of(), List.of(), 0),
                        List.of(), List.of());
            }
            DeleteCascade.OfElement cascade = DeleteCascade.ofElement(element);
            // A removed item that was last in its container has no successor, so these lists can
            // hold null and use unmodifiableList rather than List.copyOf.
            List<EObject> relationshipSuccessors = new ArrayList<>(cascade.relationships().size());
            for (CascadedRelationship rel : cascade.relationships()) {
                relationshipSuccessors.add(successorOf(rel.folder().getElements(), rel.relationship()));
            }
            List<IDiagramModelObject> viewObjectSuccessors = new ArrayList<>(cascade.viewObjects().size());
            for (CascadedViewReference ref : cascade.viewObjects()) {
                viewObjectSuccessors.add(successorOf(ref.container().getChildren(), ref.viewObject()));
            }
            return new Removal(folder, folder.getElements().indexOf(element),
                    successorOf(folder.getElements(), element), cascade,
                    Collections.unmodifiableList(relationshipSuccessors),
                    Collections.unmodifiableList(viewObjectSuccessors));
        }
    }

    /**
     * Holds cascade state for a relationship that will be removed when
     * deleting the element.
     */
    record CascadedRelationship(
        IArchimateRelationship relationship,
        IFolder folder,
        int indexInFolder,
        IArchimateConcept source,
        IArchimateConcept target
    ) {}

    /**
     * Holds cascade state for a view object (diagram representation)
     * that will be removed when deleting the element.
     */
    record CascadedViewReference(
        IDiagramModelArchimateObject viewObject,
        IDiagramModelContainer container,
        int indexInContainer
    ) {}
}
