package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IDiagramModelContainer;

/**
 * GEF Command that removes all children and connections from a view (Story 8-0c).
 *
 * <p>On execute, snapshots all children and their indices (for z-order restore),
 * collects all unique connections, disconnects connections, then removes all
 * children. On undo, re-adds children at original indices and reconnects all
 * connections.</p>
 *
 * <p>State is captured once on first {@code execute()} and reused on subsequent
 * {@code redo()} calls, making the command resilient to partial undo failure.</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class ClearViewCommand extends Command {

    private final IDiagramModelContainer view;
    private List<IDiagramModelObject> removedChildren;
    private List<IDiagramModelConnection> removedConnections;
    private List<Integer> originalIndices;

    /**
     * Creates a command to clear all children and connections from a view.
     *
     * @param view the view to clear, must not be null
     */
    public ClearViewCommand(IDiagramModelContainer view) {
        this.view = Objects.requireNonNull(view, "view must not be null");
        setLabel("Clear view");
    }

    @Override
    public void execute() {
        // Capture state only on first execution; reuse on redo
        if (removedChildren == null) {
            // Snapshot children and their indices for z-order restore
            removedChildren = new ArrayList<>(view.getChildren());
            originalIndices = new ArrayList<>();
            for (int i = 0; i < removedChildren.size(); i++) {
                originalIndices.add(i);
            }

            // Collect unique connections from ALL children recursively (including nested
            // elements inside groups). Without recursion, connections on nested elements
            // are not disconnected before their parent groups are removed, leaving orphaned
            // connection references in the EMF model (Story 10-14).
            Set<IDiagramModelConnection> uniqueConnections = new LinkedHashSet<>();
            for (IDiagramModelObject child : removedChildren) {
                collectConnectionsRecursive(child, uniqueConnections);
            }
            removedConnections = new ArrayList<>(uniqueConnections);
        }

        // Disconnect all connections first
        for (IDiagramModelConnection conn : removedConnections) {
            conn.disconnect();
        }

        // Remove all children from view
        view.getChildren().clear();
    }

    @Override
    public void redo() {
        // Disconnect all connections first
        for (IDiagramModelConnection conn : removedConnections) {
            conn.disconnect();
        }

        // Remove all children from view
        view.getChildren().clear();
    }

    @Override
    public void undo() {
        // Re-add children at original indices (preserves z-order)
        for (int i = 0; i < removedChildren.size(); i++) {
            int idx = originalIndices.get(i);
            IDiagramModelObject child = removedChildren.get(i);
            if (idx >= 0 && idx <= view.getChildren().size()) {
                view.getChildren().add(idx, child);
            } else {
                view.getChildren().add(child);
            }
        }

        // Reconnect all connections
        for (IDiagramModelConnection conn : removedConnections) {
            conn.reconnect();
        }
    }

    /**
     * Recursively collects all connections from a view object and its nested children.
     * Groups can contain elements with their own connections; without recursion those
     * connections would be missed and become orphans after the view is cleared.
     */
    private void collectConnectionsRecursive(IDiagramModelObject obj,
                                              Set<IDiagramModelConnection> connections) {
        for (IDiagramModelConnection conn : obj.getSourceConnections()) {
            connections.add(conn);
        }
        for (IDiagramModelConnection conn : obj.getTargetConnections()) {
            connections.add(conn);
        }
        if (obj instanceof IDiagramModelContainer container) {
            for (IDiagramModelObject child : container.getChildren()) {
                collectConnectionsRecursive(child, connections);
            }
        }
    }

    /** Package-visible for testing. */
    IDiagramModelContainer getView() { return view; }

    /** Package-visible for testing. */
    List<IDiagramModelObject> getRemovedChildren() { return removedChildren; }

    /** Package-visible for testing. */
    List<IDiagramModelConnection> getRemovedConnections() { return removedConnections; }

    /** Package-visible for testing. */
    List<Integer> getOriginalIndices() { return originalIndices; }
}
