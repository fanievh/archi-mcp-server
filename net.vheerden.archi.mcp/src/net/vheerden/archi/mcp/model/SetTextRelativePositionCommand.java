package net.vheerden.archi.mcp.model;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IDiagramModelConnection;

/**
 * GEF Command that changes a connection's label-offset anchor via the reflective
 * {@link RelativePositionFeature}. On a target platform without the label-offset feature the command is
 * a silent no-op (the reflective getter and setter do nothing), so the same compiled bundle behaves
 * correctly on both the older and newer platforms.
 *
 * <p>Text positions move a label ALONG the path (see {@link SetTextPositionCommand}); this command
 * moves it PERPENDICULAR to the line via the offset anchor — the complementary degree of freedom.</p>
 *
 * <p>The anchor undo restores is read in {@link #execute()}, from the connection as it stands when the
 * change runs, and re-armed by every {@code undo()}, for the reasons {@link SetTextPositionCommand}
 * gives.</p>
 *
 * <p><strong>CRITICAL:</strong> execute via {@code CommandStack.execute()} through
 * {@link MutationDispatcher}; a direct {@code execute()} bypasses undo tracking.</p>
 */
public class SetTextRelativePositionCommand extends Command {

    private final IDiagramModelConnection connection;
    private final int newRelativePosition;
    /** Whether execute has taken the snapshot undo restores; undo clears it (re-arms). */
    private boolean snapshotTaken;
    private int oldRelativePosition;

    public SetTextRelativePositionCommand(IDiagramModelConnection connection, int newRelativePosition) {
        this.connection = connection;
        this.newRelativePosition = newRelativePosition;
        setLabel("Set label offset");
    }

    @Override
    public void execute() {
        if (!snapshotTaken) {
            oldRelativePosition = RelativePositionFeature.get(connection);
            snapshotTaken = true;
        }
        RelativePositionFeature.set(connection, newRelativePosition);
    }

    @Override
    public void undo() {
        if (!snapshotTaken) {
            return; // nothing was written, so there is nothing to restore
        }
        RelativePositionFeature.set(connection, oldRelativePosition);
        snapshotTaken = false;
    }

    /** The connection this command changes; read by the batch readers. */
    IDiagramModelConnection getConnection() { return connection; }

    /** The anchor the last execute met, or 0 before the first execute. Package-visible for testing. */
    int getOldRelativePosition() { return oldRelativePosition; }

    /** Package-visible for testing. */
    int getNewRelativePosition() { return newRelativePosition; }
}
