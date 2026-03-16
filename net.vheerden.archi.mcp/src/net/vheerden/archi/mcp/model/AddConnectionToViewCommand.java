package net.vheerden.archi.mcp.model;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModelArchimateConnection;

/**
 * GEF Command that connects a diagram connection between two view objects (Story 7-7).
 *
 * <p>The connection must be fully configured (archimate relationship, optional
 * bendpoints) before this command is created. Source and target are captured at
 * construction time because undo's disconnect() clears them, and redo needs
 * to reconnect.</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class AddConnectionToViewCommand extends Command {

    private final IDiagramModelArchimateConnection connection;
    private final IConnectable source;
    private final IConnectable target;

    /**
     * Creates a command to connect a diagram connection between view objects.
     *
     * @param connection the fully-configured connection to connect
     * @param source     the source view object (captured for redo)
     * @param target     the target view object (captured for redo)
     */
    public AddConnectionToViewCommand(IDiagramModelArchimateConnection connection,
                                       IConnectable source, IConnectable target) {
        this.connection = connection;
        this.source = source;
        this.target = target;
        setLabel("Add connection to view");
    }

    @Override
    public void execute() {
        connection.connect(source, target);
    }

    @Override
    public void undo() {
        connection.disconnect();
    }

    @Override
    public void redo() {
        // disconnect() preserves connection's internal source/target fields,
        // and connect() has an early-return guard when they match the arguments.
        // Clear them first so connect() proceeds through the full path.
        connection.setSource(null);
        connection.setTarget(null);
        connection.connect(source, target);
    }

    /**
     * Returns the connection this command manages.
     * Package-visible for testing.
     */
    IDiagramModelArchimateConnection getConnection() {
        return connection;
    }

    /**
     * Returns the source view object.
     * Package-visible for testing.
     */
    IConnectable getSource() {
        return source;
    }

    /**
     * Returns the target view object.
     * Package-visible for testing.
     */
    IConnectable getTarget() {
        return target;
    }
}
