package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;

/**
 * GEF Command that removes a generic diagram object (group or note) from a view (Story 8-6).
 *
 * <p>Unlike {@link RemoveFromViewCommand} which is specific to ArchiMate element objects,
 * this command handles non-ArchiMate view objects (groups and notes). When a group contains
 * nested elements that have connections, those connections are cascade-disconnected on
 * execute and reconnected on undo (following the {@link ClearViewCommand} pattern).</p>
 *
 * <p>State is captured once on first {@code execute()} and reused on subsequent
 * {@code redo()} calls, making the command resilient to partial undo failure.</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class RemoveViewObjectCommand extends Command {

    private final IDiagramModelObject diagramObject;
    private final IDiagramModelContainer parent;
    private final int originalIndex;

    // Deferred-capture: populated on first execute(), reused on redo()
    private List<IDiagramModelConnection> capturedConnections;

    /**
     * Creates a command to remove a generic view object from its parent container.
     *
     * @param diagramObject the diagram object to remove (group or note)
     * @param parent        the parent container (view or group) containing the object
     */
    public RemoveViewObjectCommand(IDiagramModelObject diagramObject,
                                    IDiagramModelContainer parent) {
        this.diagramObject = diagramObject;
        this.parent = parent;
        this.originalIndex = parent.getChildren().indexOf(diagramObject);
        setLabel("Remove " + diagramObject.eClass().getName() + " from view");
    }

    @Override
    public void execute() {
        // Capture connections only on first execution; reuse on redo
        if (capturedConnections == null) {
            Set<IDiagramModelConnection> uniqueConnections = new LinkedHashSet<>();
            collectConnections(diagramObject, uniqueConnections);
            capturedConnections = new ArrayList<>(uniqueConnections);
        }

        // Disconnect all connections first
        for (IDiagramModelConnection conn : capturedConnections) {
            conn.disconnect();
        }

        // Remove from parent
        parent.getChildren().remove(diagramObject);
    }

    @Override
    public void redo() {
        // Disconnect all connections
        for (IDiagramModelConnection conn : capturedConnections) {
            conn.disconnect();
        }

        // Remove from parent
        parent.getChildren().remove(diagramObject);
    }

    @Override
    public void undo() {
        // Re-add at original index
        if (originalIndex >= 0 && originalIndex <= parent.getChildren().size()) {
            parent.getChildren().add(originalIndex, diagramObject);
        } else {
            parent.getChildren().add(diagramObject);
        }

        // Reconnect all connections
        for (IDiagramModelConnection conn : capturedConnections) {
            conn.reconnect();
        }
    }

    /**
     * Recursively collects all connections from a diagram object and its descendants.
     */
    private void collectConnections(IDiagramModelObject obj,
                                     Set<IDiagramModelConnection> connections) {
        for (Object conn : obj.getSourceConnections()) {
            if (conn instanceof IDiagramModelConnection dc) {
                connections.add(dc);
            }
        }
        for (Object conn : obj.getTargetConnections()) {
            if (conn instanceof IDiagramModelConnection dc) {
                connections.add(dc);
            }
        }
        if (obj instanceof IDiagramModelContainer container) {
            for (IDiagramModelObject child : container.getChildren()) {
                collectConnections(child, connections);
            }
        }
    }

    /** Package-visible for testing. */
    IDiagramModelObject getDiagramObject() { return diagramObject; }

    /** Package-visible for testing. */
    IDiagramModelContainer getParent() { return parent; }

    /** Package-visible for testing. */
    List<IDiagramModelConnection> getCapturedConnections() { return capturedConnections; }
}
