package net.vheerden.archi.mcp.model;

import java.util.List;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IIdentifier;

/**
 * GEF Command that removes a diagram object from its immediate parent
 * container (which is the view itself for top-level objects, or a nested
 * group / component / node view-object for nested elements), including
 * cascade-disconnection of all attached connections.
 *
 * <p>The caller pre-collects attached connections (both source and target).
 * On execute, all connections are disconnected first, then the element is
 * removed from its parent container. On undo, the element is re-added
 * first, then connections are reconnected. Order matters: the element must
 * be on the view before connections can reference it.</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 *
 * <p><strong>v1.6:</strong> generalised from <em>view</em>-removal
 * to <em>parent</em>-removal to support element-as-container
 * nesting. A SOUND postcondition certificate fires if
 * {@code parent.getChildren().remove(diagramObject)} returns {@code false} —
 * converting a silent no-op into a structured reject with a diagnostic
 * naming the prepared parent, the diagram object, and the actual EMF
 * eContainer at execute time. This is the architectural inverse of the
 * silent-acceptance class, applied at the mutation exit boundary.</p>
 */
public class RemoveFromViewCommand extends Command {

    private final IDiagramModelArchimateObject diagramObject;
    private final IDiagramModelContainer parent;
    private final int originalIndex;
    // The sibling that immediately followed diagramObject in the parent's child
    // list at construction time (null if it was last). undo() prefers to restore
    // the object directly BEFORE this surviving anchor rather than at a stale
    // absolute index — see undo() for why this matters under compound undo.
    private final IDiagramModelObject successorAnchor;
    private final List<IDiagramModelArchimateConnection> attachedConnections;

    /**
     * Creates a command to remove a diagram object from its immediate parent
     * container.
     *
     * <p>For top-level view-objects pass the view itself; for nested
     * view-objects pass the immediate container resolved via
     * {@code findParentContainer}. The captured {@code originalIndex} is the
     * position inside the parent's child list, used by {@code undo()} to
     * restore the object to its original position.</p>
     *
     * <p>Attached connections are stored for cascade disconnect/reconnect.
     * Archi's {@code disconnect()} preserves internal source/target refs,
     * so {@code reconnect()} can restore them without explicit capture.</p>
     *
     * @param diagramObject       the diagram object to remove
     * @param parent              the immediate container holding the object
     *                            (the view itself for top-level placements,
     *                            or a nested group / component / node)
     * @param attachedConnections all connections attached to this object (source + target)
     */
    public RemoveFromViewCommand(IDiagramModelArchimateObject diagramObject,
                                  IDiagramModelContainer parent,
                                  List<IDiagramModelArchimateConnection> attachedConnections) {
        this.diagramObject = diagramObject;
        this.parent = parent;
        this.originalIndex = parent.getChildren().indexOf(diagramObject);
        this.successorAnchor = (originalIndex >= 0
                && originalIndex + 1 < parent.getChildren().size())
                        ? parent.getChildren().get(originalIndex + 1)
                        : null;
        this.attachedConnections = List.copyOf(attachedConnections);
        setLabel("Remove " + diagramObject.getArchimateElement().eClass().getName() + " from view");
    }

    @Override
    public void execute() {
        // SOUND postcondition certificate (Story B, v1.6) runs FIRST so that a
        // certificate failure leaves the model fully intact — no connections
        // get disconnected if the remove can't take effect. If List.remove
        // returns false the prepared parent no longer holds the diagram object
        // at execute time; refuse-to-mutate with a structured diagnostic
        // instead of silently no-op'ing.
        boolean removed = parent.getChildren().remove(diagramObject);
        if (!removed) {
            String parentId = parent instanceof IIdentifier pid ? pid.getId() : "<not-identifiable>";
            String actualContainerId = diagramObject.eContainer() instanceof IIdentifier ic
                    ? ic.getId() : "<not-identifiable>";
            throw new IllegalStateException(
                    "remove-from-view postcondition violated: diagramObject="
                            + diagramObject.getId()
                            + " not found in prepared parent=" + parentId
                            + " at execute time; actual eContainer=" + actualContainerId);
        }
        // Only disconnect connections after the element removal has succeeded.
        // This preserves the refuse-to-mutate contract: if execute() throws,
        // both the parent's child list AND all attached connections are
        // byte-unchanged from their pre-execute state.
        for (IDiagramModelArchimateConnection conn : attachedConnections) {
            conn.disconnect();
        }
    }

    @Override
    public void undo() {
        // Re-add element to its parent, preserving paint order (z-order).
        //
        // A raw absolute index is unsafe when several sibling removals from the
        // same parent are grouped in one compound: the compound undoes its
        // sub-commands in reverse, so a later-listed removal restores its child
        // into a list whose earlier-listed siblings are not yet back — the
        // absolute index then lands in the wrong slot. Restoring directly BEFORE
        // the surviving successor sibling (captured at construction) is robust to
        // that, because nearer-tail siblings are restored first and act as stable
        // anchors. Fall back to the clamped absolute index when the object was
        // last in its parent or the anchor is (transiently) absent — which also
        // makes the single-removal case byte-identical to the original behavior.
        int anchorIndex = (successorAnchor != null)
                ? parent.getChildren().indexOf(successorAnchor) : -1;
        if (anchorIndex >= 0) {
            parent.getChildren().add(anchorIndex, diagramObject);
        } else if (originalIndex >= 0 && originalIndex <= parent.getChildren().size()) {
            parent.getChildren().add(originalIndex, diagramObject);
        } else {
            parent.getChildren().add(diagramObject);
        }
        // Then reconnect all connections.
        // disconnect() preserves internal source/target refs on the connection,
        // so reconnect() can re-add to the source/target connection lists.
        for (IDiagramModelArchimateConnection conn : attachedConnections) {
            conn.reconnect();
        }
    }

    /** Package-visible for testing. */
    IDiagramModelArchimateObject getDiagramObject() { return diagramObject; }

    /** Package-visible for testing. */
    IDiagramModelContainer getParent() { return parent; }

    /** Package-visible for testing. */
    List<IDiagramModelArchimateConnection> getAttachedConnections() {
        return attachedConnections;
    }
}
