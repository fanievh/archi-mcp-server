package net.vheerden.archi.mcp.model;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;

/**
 * GEF Command that adds a visual group to a view (Story 8-6).
 *
 * <p>The group must be fully configured (label, bounds) before this
 * command is created. The command only handles view placement and
 * removal (undo).</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class AddGroupToViewCommand extends Command {

    private final IDiagramModelGroup group;
    private final IDiagramModelContainer parent;

    /**
     * Creates a command to add a group to a view or parent group.
     *
     * @param group  the fully-configured group to add
     * @param parent the target container (view or parent group)
     */
    public AddGroupToViewCommand(IDiagramModelGroup group, IDiagramModelContainer parent) {
        this.group = group;
        this.parent = parent;
        setLabel("Add group '" + group.getName() + "' to view");
    }

    @Override
    public void execute() {
        parent.getChildren().add(group);
    }

    @Override
    public void undo() {
        parent.getChildren().remove(group);
    }

    /** Package-visible for testing. */
    IDiagramModelGroup getGroup() {
        return group;
    }

    /** Package-visible for testing. */
    IDiagramModelContainer getParent() {
        return parent;
    }
}
