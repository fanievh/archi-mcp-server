package net.vheerden.archi.mcp.model;

import java.util.List;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;

/**
 * GEF Command that removes a diagram object from a view, including
 * cascade-disconnection of all attached connections (Story 7-8).
 *
 * <p>The caller pre-collects attached connections (both source and target).
 * On execute, all connections are disconnected first, then the element is
 * removed from the view. On undo, the element is re-added first, then
 * connections are reconnected. Order matters: the element must be on the
 * view before connections can reference it.</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class RemoveFromViewCommand extends Command {

    private final IDiagramModelArchimateObject diagramObject;
    private final IDiagramModelContainer view;
    private final int originalIndex;
    private final List<IDiagramModelArchimateConnection> attachedConnections;

    /**
     * Creates a command to remove a diagram object from a view.
     *
     * <p>Attached connections are stored for cascade disconnect/reconnect.
     * Archi's {@code disconnect()} preserves internal source/target refs,
     * so {@code reconnect()} can restore them without explicit capture.</p>
     *
     * @param diagramObject       the diagram object to remove
     * @param view                the view containing the object
     * @param attachedConnections all connections attached to this object (source + target)
     */
    public RemoveFromViewCommand(IDiagramModelArchimateObject diagramObject,
                                  IDiagramModelContainer view,
                                  List<IDiagramModelArchimateConnection> attachedConnections) {
        this.diagramObject = diagramObject;
        this.view = view;
        this.originalIndex = view.getChildren().indexOf(diagramObject);
        this.attachedConnections = List.copyOf(attachedConnections);
        setLabel("Remove " + diagramObject.getArchimateElement().eClass().getName() + " from view");
    }

    @Override
    public void execute() {
        // Disconnect all connections first
        for (IDiagramModelArchimateConnection conn : attachedConnections) {
            conn.disconnect();
        }
        // Then remove element from view
        view.getChildren().remove(diagramObject);
    }

    @Override
    public void undo() {
        // Re-add element to view at original position (preserves z-order)
        if (originalIndex >= 0 && originalIndex <= view.getChildren().size()) {
            view.getChildren().add(originalIndex, diagramObject);
        } else {
            view.getChildren().add(diagramObject);
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
    IDiagramModelContainer getView() { return view; }

    /** Package-visible for testing. */
    List<IDiagramModelArchimateConnection> getAttachedConnections() {
        return attachedConnections;
    }
}
