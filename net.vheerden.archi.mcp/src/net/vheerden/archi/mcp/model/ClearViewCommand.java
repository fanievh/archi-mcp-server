package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IDiagramModelContainer;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.ClearViewResultDto;

/**
 * GEF Command that removes all children and connections from a view.
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

    /**
     * Prepares a clear-view mutation without dispatching: finds the view, counts what the clear will
     * remove for the response, and builds the command. Shared by the single tool and the
     * {@code bulk-mutate} operation.
     *
     * <p>Both read the view as the work staged ahead of this call will leave it: a view an earlier
     * operation in the call created, or the open batch queued, resolves, and the counts are of what will
     * be on the view when the clear runs. The command itself still removes whatever is there when it
     * runs, so it needs no staged answer.</p>
     *
     * @param staged       the work staged ahead of this call
     * @param createdViews the views created earlier in this bulk call, or null outside one
     * @throws ModelAccessException {@code VIEW_NOT_FOUND} when no view has {@code viewId}
     */
    static PreparedMutation<ClearViewResultDto> prepare(StagedViewState staged, IArchimateModel model,
            String viewId, Map<Integer, IArchimateDiagramModel> createdViews) {
        IArchimateDiagramModel view = staged.viewOrNull(model, viewId, createdViews);
        if (view == null) {
            throw new ModelAccessException(
                    "View not found: " + CallerText.bare(viewId),
                    ErrorCode.VIEW_NOT_FOUND,
                    null,
                    "Use get-views to find valid view IDs",
                    null);
        }

        // Count top-level objects (groups are removed as single units; nested
        // elements cascade with their parent). Connection count below is recursive
        // because ClearViewCommand explicitly disconnects nested connections.
        List<IDiagramModelObject> topLevel = staged.topLevelOn(view);
        int archimateCount = 0;
        for (IDiagramModelObject child : topLevel) {
            if (child instanceof IDiagramModelArchimateObject) {
                archimateCount++;
            }
        }
        int nonArchimateCount = topLevel.size() - archimateCount;

        ClearViewResultDto dto = new ClearViewResultDto(
                viewId, view.getName(), topLevel.size(), staged.connectionsOn(view).size(),
                nonArchimateCount);
        return new PreparedMutation<>(new ClearViewCommand(view), dto, viewId);
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
            // connection references in the EMF model.
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
