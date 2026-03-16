package net.vheerden.archi.mcp.model;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelNote;

/**
 * GEF Command that adds a text note to a view (Story 8-6).
 *
 * <p>The note must be fully configured (content, bounds) before this
 * command is created. The command only handles view placement and
 * removal (undo).</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class AddNoteToViewCommand extends Command {

    private final IDiagramModelNote note;
    private final IDiagramModelContainer parent;

    /**
     * Creates a command to add a note to a view or parent group.
     *
     * @param note   the fully-configured note to add
     * @param parent the target container (view or parent group)
     */
    public AddNoteToViewCommand(IDiagramModelNote note, IDiagramModelContainer parent) {
        this.note = note;
        this.parent = parent;
        setLabel("Add note to view");
    }

    @Override
    public void execute() {
        parent.getChildren().add(note);
    }

    @Override
    public void undo() {
        parent.getChildren().remove(note);
    }

    /** Package-visible for testing. */
    IDiagramModelNote getNote() {
        return note;
    }

    /** Package-visible for testing. */
    IDiagramModelContainer getParent() {
        return parent;
    }
}
