package net.vheerden.archi.mcp.model;

import java.util.List;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModelComponent;
import com.archimatetool.model.IDiagramModelConnection;

/**
 * GEF Command that removes a connection from a view by disconnecting it
 * from its source and target, together with every connection attached to it.
 *
 * <p>Any connection is accepted, ArchiMate or not, and either end may itself be a
 * connection. As Archi's own delete does, every connection whose end is this connection
 * (and, recursively, every connection on those) is disconnected with it
 * ({@link ViewRemoval#cascade}); left attached, it would be saved pointing at a connection
 * no longer in the model, and the file would fail to reopen. That set is captured on first
 * {@code execute()} and reused on {@code redo()}, as {@link RemoveViewObjectCommand} does.</p>
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
public class RemoveConnectionFromViewCommand extends Command implements RemovesViewObject {

    private final IDiagramModelConnection connection;
    private final IConnectable source;
    private final IConnectable target;

    // Deferred-capture: populated on first execute(), reused on redo()
    private List<IDiagramModelConnection> capturedConnections;

    /**
     * Creates a command to disconnect a connection from its view objects.
     *
     * @param connection the connection to disconnect
     */
    public RemoveConnectionFromViewCommand(IDiagramModelConnection connection) {
        this.connection = connection;
        this.source = connection.getSource();
        this.target = connection.getTarget();
        setLabel("Remove connection from view");
    }

    @Override
    public void execute() {
        if (capturedConnections == null) {
            capturedConnections = ViewRemoval.cascade(connection);
        }
        redo();
    }

    @Override
    public void redo() {
        for (IDiagramModelConnection conn : capturedConnections) {
            conn.disconnect();
        }
        connection.disconnect();
    }

    @Override
    public void undo() {
        // disconnect() preserves the connection's internal source/target fields,
        // so reconnect() can re-add to the source/target connection lists.
        connection.reconnect();
        for (IDiagramModelConnection conn : capturedConnections) {
            conn.reconnect();
        }
    }

    /** Package-visible for testing. */
    IDiagramModelConnection getConnection() { return connection; }

    /** Package-visible for testing. */
    IConnectable getSource() { return source; }

    /** Package-visible for testing. */
    IConnectable getTarget() { return target; }

    /** Package-visible for testing. */
    List<IDiagramModelConnection> getCapturedConnections() { return capturedConnections; }

    @Override
    public IDiagramModelComponent removedViewObject() {
        return connection;
    }
}
