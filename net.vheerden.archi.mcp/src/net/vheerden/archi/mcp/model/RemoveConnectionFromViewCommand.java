package net.vheerden.archi.mcp.model;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModelArchimateConnection;

/**
 * GEF Command that removes a connection from a view by disconnecting it
 * from its source and target view objects (Story 7-8).
 *
 * <p>Captures source and target at construction time for getter access.
 * Note: {@code disconnect()} preserves the connection's internal source/target
 * fields, so {@code reconnect()} can re-add to the source/target lists on undo.
 * Follows the inverse pattern of {@link AddConnectionToViewCommand}.</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class RemoveConnectionFromViewCommand extends Command {

    private final IDiagramModelArchimateConnection connection;
    private final IConnectable source;
    private final IConnectable target;

    /**
     * Creates a command to disconnect a connection from its view objects.
     *
     * @param connection the connection to disconnect
     */
    public RemoveConnectionFromViewCommand(IDiagramModelArchimateConnection connection) {
        this.connection = connection;
        this.source = connection.getSource();
        this.target = connection.getTarget();
        setLabel("Remove connection from view");
    }

    @Override
    public void execute() {
        connection.disconnect();
    }

    @Override
    public void undo() {
        // disconnect() preserves the connection's internal source/target fields,
        // so reconnect() can re-add to the source/target connection lists.
        connection.reconnect();
    }

    // No redo() override needed — default Command.redo() calls execute()

    /** Package-visible for testing. */
    IDiagramModelArchimateConnection getConnection() { return connection; }

    /** Package-visible for testing. */
    IConnectable getSource() { return source; }

    /** Package-visible for testing. */
    IConnectable getTarget() { return target; }
}
