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
 *   <li>Disconnect all view connections (visual connections on diagrams)</li>
 *   <li>Remove all view objects (diagram representations of the element)</li>
 *   <li>Disconnect and remove all model relationships involving this element</li>
 *   <li>Remove the element from its folder</li>
 * </ol>
 *
 * <p>All cascade state is discovered at prepare time by the accessor and passed
 * to the constructor. The command does not perform any model traversal itself.
 * Relationships must be sorted by descending folder index within each folder
 * so that removal does not shift indices of not-yet-removed items.</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class DeleteElementCommand extends Command {

    private final IArchimateElement element;
    private final IFolder elementFolder;
    private final int elementIndex;
    private final List<CascadedRelationship> cascadedRelationships;
    private final List<CascadedViewReference> cascadedViewReferences;
    private final List<IDiagramModelConnection> viewConnectionsToDisconnect;

    // Successor anchors captured at construction (prepare time, when every folder
    // and container still holds its full original membership). undo() restores
    // each removed object directly BEFORE its surviving successor rather than at a
    // stale absolute index. This keeps paint order (view z-order) AND model-tree
    // folder order exact when SEVERAL delete commands are grouped in one compound:
    // such a compound undoes its members in reverse, so a later member restores
    // into a list whose earlier members are not yet back — an absolute index then
    // lands in the wrong slot, whereas the surviving successor is a stable anchor.
    //
    // Exactness guarantee: order is restored exactly whenever each removed item's
    // captured successor is a SURVIVOR or is restored before the item (always true
    // for single deletes, for the intra-command cascade — sorted descending and
    // restored ascending — and for a compound whose same-container removals are
    // listed in ascending index order, which is what the discovery walk and a
    // top-to-bottom bulk request produce). Residual: a run of 3+ co-deleted
    // siblings listed in a non-monotonic order (e.g. indices [2,0,1]) can leave a
    // middle item whose only anchor is another not-yet-restored co-deleted sibling;
    // the absolute-index fallback then mis-places it among the collapsed survivors.
    // Membership/connection integrity is always preserved; only paint order can
    // drift in that rare case. A fully order-independent fix would require the
    // compound itself to restore same-container removals in global index order.
    private final EObject elementSuccessor;
    private final List<EObject> relationshipSuccessors;
    private final List<IDiagramModelObject> viewReferenceSuccessors;

    /**
     * Creates a command to delete an element and cascade-remove all dependencies.
     *
     * <p>The caller (accessor prepare method) is responsible for discovering
     * all cascade targets and passing them here. Lists are defensively copied.</p>
     *
     * @param element                    the element to delete
     * @param elementFolder              the folder containing the element
     * @param elementIndex               the element's index in the folder
     * @param cascadedRelationships      relationships to cascade-delete (sorted by descending index per folder)
     * @param cascadedViewReferences     view objects to remove (sorted by descending index per container)
     * @param viewConnectionsToDisconnect unique view connections to disconnect
     */
    public DeleteElementCommand(IArchimateElement element,
                                 IFolder elementFolder,
                                 int elementIndex,
                                 List<CascadedRelationship> cascadedRelationships,
                                 List<CascadedViewReference> cascadedViewReferences,
                                 List<IDiagramModelConnection> viewConnectionsToDisconnect) {
        this.element = Objects.requireNonNull(element, "element must not be null");
        this.elementFolder = Objects.requireNonNull(elementFolder, "elementFolder must not be null");
        this.elementIndex = elementIndex;
        this.cascadedRelationships = List.copyOf(cascadedRelationships);
        this.cascadedViewReferences = List.copyOf(cascadedViewReferences);
        this.viewConnectionsToDisconnect = List.copyOf(viewConnectionsToDisconnect);
        // Capture surviving-successor anchors NOW, while the model is still intact.
        this.elementSuccessor = successorOf(elementFolder.getElements(), element);
        List<EObject> relSuccessors = new ArrayList<>(this.cascadedRelationships.size());
        for (CascadedRelationship rel : this.cascadedRelationships) {
            relSuccessors.add(successorOf(rel.folder().getElements(), rel.relationship()));
        }
        // Note: successor lists may legitimately contain null (a removed item
        // that was last in its container has no successor anchor), so they use
        // unmodifiableList rather than List.copyOf, which rejects null elements.
        this.relationshipSuccessors = Collections.unmodifiableList(relSuccessors);
        List<IDiagramModelObject> viewSuccessors =
                new ArrayList<>(this.cascadedViewReferences.size());
        for (CascadedViewReference ref : this.cascadedViewReferences) {
            viewSuccessors.add(successorOf(ref.container().getChildren(), ref.viewObject()));
        }
        this.viewReferenceSuccessors = Collections.unmodifiableList(viewSuccessors);
        setLabel("Delete " + element.eClass().getName() + ": " + element.getName());
    }

    @Override
    public void execute() {
        // 1. Disconnect all view connections
        for (IDiagramModelConnection conn : viewConnectionsToDisconnect) {
            conn.disconnect();
        }

        // 2. Remove all view objects from their containers
        for (CascadedViewReference ref : cascadedViewReferences) {
            ref.container.getChildren().remove(ref.viewObject);
        }

        // 3. Disconnect and remove all relationships
        for (CascadedRelationship rel : cascadedRelationships) {
            rel.relationship.disconnect();
            rel.folder.getElements().remove(rel.relationship);
        }

        // 4. Remove element from folder
        elementFolder.getElements().remove(element);
    }

    @Override
    public void undo() {
        // 4. Re-add element to folder
        safeAddElement(elementFolder, elementIndex, element, elementSuccessor);

        // 3. Re-add and reconnect relationships (reverse order preserves indices)
        for (int i = cascadedRelationships.size() - 1; i >= 0; i--) {
            CascadedRelationship rel = cascadedRelationships.get(i);
            safeAddElement(rel.folder, rel.indexInFolder, rel.relationship,
                    relationshipSuccessors.get(i));
            rel.relationship.connect(rel.source, rel.target);
        }

        // 2. Re-add view objects (reverse order preserves indices)
        for (int i = cascadedViewReferences.size() - 1; i >= 0; i--) {
            CascadedViewReference ref = cascadedViewReferences.get(i);
            safeAddChild(ref.container, ref.indexInContainer, ref.viewObject,
                    viewReferenceSuccessors.get(i));
        }

        // 1. Reconnect all view connections
        for (IDiagramModelConnection conn : viewConnectionsToDisconnect) {
            conn.reconnect();
        }
    }

    // Default redo() calls execute() — safe because all state is in final fields

    /** The item that immediately followed {@code item} in {@code list}, or
     *  {@code null} if it was last / not present. Used to capture a stable
     *  re-insertion anchor at construction time. Relies on EMF default identity
     *  equality for {@code indexOf} (the model types here do not override
     *  {@code equals}), so a value-equal twin cannot shadow the real anchor. */
    private static <T> T successorOf(List<T> list, T item) {
        int i = list.indexOf(item);
        return (i >= 0 && i + 1 < list.size()) ? list.get(i + 1) : null;
    }

    private static void safeAddElement(IFolder folder, int index, EObject element,
                                       EObject successorAnchor) {
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
    int getRelationshipsRemoved() { return cascadedRelationships.size(); }

    /** Package-visible for testing. */
    int getViewReferencesRemoved() { return cascadedViewReferences.size(); }

    /** Package-visible for testing. */
    int getViewConnectionsRemoved() { return viewConnectionsToDisconnect.size(); }

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
