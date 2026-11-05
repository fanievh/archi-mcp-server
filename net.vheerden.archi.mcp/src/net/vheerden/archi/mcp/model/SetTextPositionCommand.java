package net.vheerden.archi.mcp.model;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IDiagramModelConnection;

/**
 * GEF Command that changes a connection's textPosition.
 *
 * <p>Text positions: 0=source, 1=middle, 2=target. Archi draws a Source or Target label just beyond that
 * end of the line, and a Middle label on the line's middle point.</p>
 *
 * <p>The position undo restores is read in {@link #execute()}, from the connection as it stands when the
 * change runs, not when the command is built: a proposal runs when it is approved and a batched change
 * when the batch commits, and a snapshot taken earlier would make Undo throw away whatever ran in
 * between. The command is armed when built and again by every {@code undo()}, and an armed execute
 * takes the snapshot, so a real run after a speculative execute and undo reads the state it meets; a
 * second execute with no undo between keeps the snapshot it has.</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class SetTextPositionCommand extends Command {

    private final IDiagramModelConnection connection;
    private final int newTextPosition;
    /** Whether execute has taken the snapshot undo restores; undo clears it (re-arms). */
    private boolean snapshotTaken;
    private int oldTextPosition;

    public SetTextPositionCommand(IDiagramModelConnection connection, int newTextPosition) {
        this.connection = connection;
        this.newTextPosition = newTextPosition;
        setLabel("Set label position");
    }

    @Override
    public void execute() {
        if (!snapshotTaken) {
            oldTextPosition = connection.getTextPosition();
            snapshotTaken = true;
        }
        connection.setTextPosition(newTextPosition);
    }

    @Override
    public void undo() {
        if (!snapshotTaken) {
            return; // nothing was written, so there is nothing to restore
        }
        connection.setTextPosition(oldTextPosition);
        snapshotTaken = false;
    }

    /** The connection this command changes; read by the batch readers. */
    IDiagramModelConnection getConnection() { return connection; }

    /** The position the last execute met, or 0 before the first execute. Package-visible for testing. */
    int getOldTextPosition() { return oldTextPosition; }

    /** Package-visible for testing. */
    int getNewTextPosition() { return newTextPosition; }
}
