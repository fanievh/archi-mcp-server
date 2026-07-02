package net.vheerden.archi.mcp.model;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IDiagramModelConnection;

/**
 * GEF Command that changes a connection's label-offset anchor via the reflective
 * {@link RelativePositionFeature}. Captures the old anchor for undo/redo. On a target platform without
 * the label-offset feature the command is a silent no-op (the reflective setter does nothing), so the
 * same compiled bundle behaves correctly on both the older and newer platforms.
 *
 * <p>Text positions move a label ALONG the path (see {@link SetTextPositionCommand}); this command
 * moves it PERPENDICULAR to the line via the offset anchor — the complementary degree of freedom.</p>
 *
 * <p><strong>CRITICAL:</strong> execute via {@code CommandStack.execute()} through
 * {@link MutationDispatcher}; a direct {@code execute()} bypasses undo tracking.</p>
 */
public class SetTextRelativePositionCommand extends Command {

    private final IDiagramModelConnection connection;
    private final int oldRelativePosition;
    private final int newRelativePosition;

    public SetTextRelativePositionCommand(IDiagramModelConnection connection, int newRelativePosition) {
        this.connection = connection;
        this.oldRelativePosition = RelativePositionFeature.get(connection);
        this.newRelativePosition = newRelativePosition;
        setLabel("Set label offset");
    }

    @Override
    public void execute() {
        RelativePositionFeature.set(connection, newRelativePosition);
    }

    @Override
    public void undo() {
        RelativePositionFeature.set(connection, oldRelativePosition);
    }

    /** Package-visible for testing. */
    IDiagramModelConnection getConnection() { return connection; }

    /** Package-visible for testing. */
    int getOldRelativePosition() { return oldRelativePosition; }

    /** Package-visible for testing. */
    int getNewRelativePosition() { return newRelativePosition; }
}
