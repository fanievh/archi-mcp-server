package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;

/**
 * GEF Command that deletes an ArchiMate view (diagram) from the model (Story 8-4).
 *
 * <p>On execute, captures all children and connections (same pattern as
 * {@link ClearViewCommand}), disconnects connections, clears children, and
 * removes the view from its parent folder. The underlying model elements
 * and relationships are NOT deleted.</p>
 *
 * <p>State is captured once on first {@code execute()} and reused on
 * subsequent {@code redo()} calls.</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class DeleteViewCommand extends Command {

    private final IDiagramModel view;
    private final IFolder viewFolder;
    private final int viewIndex;

    // Captured on first execute for undo/redo
    private List<IDiagramModelObject> capturedChildren;
    private List<IDiagramModelConnection> capturedConnections;
    private List<Integer> capturedIndices;

    /**
     * Creates a command to delete a view from the model.
     *
     * @param view       the view to delete
     * @param viewFolder the folder containing the view
     * @param viewIndex  the view's index in the folder
     */
    public DeleteViewCommand(IDiagramModel view, IFolder viewFolder, int viewIndex) {
        this.view = Objects.requireNonNull(view, "view must not be null");
        this.viewFolder = Objects.requireNonNull(viewFolder, "viewFolder must not be null");
        this.viewIndex = viewIndex;
        setLabel("Delete view: " + view.getName());
    }

    @Override
    public void execute() {
        // Capture state only on first execution; reuse on redo
        if (capturedChildren == null) {
            capturedChildren = new ArrayList<>(view.getChildren());
            capturedIndices = new ArrayList<>();
            for (int i = 0; i < capturedChildren.size(); i++) {
                capturedIndices.add(i);
            }

            Set<IDiagramModelConnection> uniqueConnections = new LinkedHashSet<>();
            for (IDiagramModelObject child : capturedChildren) {
                for (Object conn : child.getSourceConnections()) {
                    if (conn instanceof IDiagramModelConnection dc) {
                        uniqueConnections.add(dc);
                    }
                }
                for (Object conn : child.getTargetConnections()) {
                    if (conn instanceof IDiagramModelConnection dc) {
                        uniqueConnections.add(dc);
                    }
                }
            }
            capturedConnections = new ArrayList<>(uniqueConnections);
        }

        // 1. Disconnect all connections
        for (IDiagramModelConnection conn : capturedConnections) {
            conn.disconnect();
        }

        // 2. Clear all children
        view.getChildren().clear();

        // 3. Remove view from folder
        viewFolder.getElements().remove(view);
    }

    // Default redo() calls execute() — safe because the lazy capture guard
    // (capturedChildren == null) ensures state is only captured once.

    @Override
    public void undo() {
        // 3. Re-add view to folder
        if (viewIndex >= 0 && viewIndex <= viewFolder.getElements().size()) {
            viewFolder.getElements().add(viewIndex, view);
        } else {
            viewFolder.getElements().add(view);
        }

        // 2. Re-add children at original indices
        for (int i = 0; i < capturedChildren.size(); i++) {
            int idx = capturedIndices.get(i);
            IDiagramModelObject child = capturedChildren.get(i);
            if (idx >= 0 && idx <= view.getChildren().size()) {
                view.getChildren().add(idx, child);
            } else {
                view.getChildren().add(child);
            }
        }

        // 1. Reconnect all connections
        for (IDiagramModelConnection conn : capturedConnections) {
            conn.reconnect();
        }
    }

    /** Package-visible for testing. */
    IDiagramModel getView() { return view; }

    /** Package-visible for testing. */
    IFolder getViewFolder() { return viewFolder; }
}
