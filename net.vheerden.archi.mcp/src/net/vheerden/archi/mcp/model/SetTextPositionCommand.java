package net.vheerden.archi.mcp.model;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IDiagramModelConnection;

/**
 * GEF Command that changes a connection's textPosition (Story 11-31).
 * Supports undo/redo by capturing old and new positions.
 *
 * <p>Text positions: 0=source (15%), 1=middle (50%), 2=target (85%).</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class SetTextPositionCommand extends Command {

    private final IDiagramModelConnection connection;
    private final int oldTextPosition;
    private final int newTextPosition;

    public SetTextPositionCommand(IDiagramModelConnection connection, int newTextPosition) {
        this.connection = connection;
        this.oldTextPosition = connection.getTextPosition();
        this.newTextPosition = newTextPosition;
        setLabel("Set label position");
    }

    @Override
    public void execute() {
        connection.setTextPosition(newTextPosition);
    }

    @Override
    public void undo() {
        connection.setTextPosition(oldTextPosition);
    }

    /** Package-visible for testing. */
    IDiagramModelConnection getConnection() { return connection; }

    /** Package-visible for testing. */
    int getOldTextPosition() { return oldTextPosition; }

    /** Package-visible for testing. */
    int getNewTextPosition() { return newTextPosition; }
}
