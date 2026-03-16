package net.vheerden.archi.mcp.model;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;

/**
 * GEF Command that adds a diagram object to a view (Story 7-7).
 *
 * <p>The diagram object must be fully configured (archimate element, bounds)
 * before this command is created. The command only handles view placement
 * and removal (undo).</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class AddToViewCommand extends Command {

    private final IDiagramModelArchimateObject diagramObject;
    private final IDiagramModelContainer view;

    /**
     * Creates a command to add a diagram object to a view.
     *
     * @param diagramObject the fully-configured diagram object to add
     * @param view          the target view container
     */
    public AddToViewCommand(IDiagramModelArchimateObject diagramObject,
                            IDiagramModelContainer view) {
        this.diagramObject = diagramObject;
        this.view = view;
        setLabel("Add " + diagramObject.getArchimateElement().eClass().getName() + " to view");
    }

    @Override
    public void execute() {
        view.getChildren().add(diagramObject);
    }

    @Override
    public void undo() {
        view.getChildren().remove(diagramObject);
    }

    /**
     * Returns the diagram object this command will add.
     * Package-visible for testing.
     */
    IDiagramModelArchimateObject getDiagramObject() {
        return diagramObject;
    }

    /**
     * Returns the target view container.
     * Package-visible for testing.
     */
    IDiagramModelContainer getView() {
        return view;
    }
}
