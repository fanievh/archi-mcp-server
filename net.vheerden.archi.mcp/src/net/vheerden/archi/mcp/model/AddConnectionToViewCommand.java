package net.vheerden.archi.mcp.model;

import java.util.List;
import java.util.function.Supplier;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelBendpoint;

import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;

/**
 * GEF Command that connects a diagram connection between two view objects.
 *
 * <p>The connection must be fully configured (archimate relationship, optional
 * bendpoints) before this command is created. Source and target are captured at
 * construction time because undo's disconnect() clears them, and redo needs
 * to reconnect.</p>
 *
 * <p><strong>Built with the caller's bendpoints, it decides what the new connection is stored with
 * when it runs.</strong> Archi stores each bendpoint as two offsets, one from each end's centre, so
 * a canvas point means something only against where the ends are; and Archi's default loop for a
 * self-connection given no bendpoints is sized from the object's bounds. A batch or a bulk call
 * prepares every operation before any of them runs, so an end that an earlier operation moves,
 * resizes, carries in a group, moves through its anchor, or places inside a group is not where the
 * prepare read it. By the time this command runs, every command ahead of it in the same compound or
 * batch has, so it converts the caller's canvas points, or sizes the loop, against the ends as they
 * stand then, and replaces the list the prepare wrote. The ends are the command's own: the
 * connection has none until {@code connect()} runs, which is after this. The first decision is
 * kept, so a redo writes exactly what the first execute wrote. Relative bendpoints are the caller's
 * own frame and are left as the prepare wrote them.</p>
 *
 * <p><strong>CRITICAL:</strong> This command MUST be executed via
 * {@code CommandStack.execute()} through {@link MutationDispatcher}.
 * Direct invocation of {@code execute()} bypasses undo tracking.</p>
 */
public class AddConnectionToViewCommand extends Command {

    private final IDiagramModelArchimateConnection connection;
    private final IConnectable source;
    private final IConnectable target;
    /** Decides the bendpoints from the ends as they stand; null when the caller's are final. */
    private final Supplier<List<BendpointDto>> decide;
    /** What the first execute decided; null until then. */
    private List<IDiagramModelBendpoint> decided;

    /**
     * Creates a command to connect a diagram connection between view objects.
     *
     * @param connection the fully-configured connection to connect
     * @param source     the source view object (captured for redo)
     * @param target     the target view object (captured for redo)
     */
    public AddConnectionToViewCommand(IDiagramModelArchimateConnection connection,
                                       IConnectable source, IConnectable target) {
        this(connection, source, target, null);
    }

    /**
     * Creates a command that also decides the new connection's bendpoints when it runs, from the
     * caller's lists (see the class comment).
     *
     * @param connection         the configured connection, holding the bendpoints the prepare
     *                           decided, which are replaced when this decides again
     * @param source             the source view object
     * @param target             the target view object
     * @param bendpoints         the caller's bendpoints in Archi's relative form, or null
     * @param absoluteBendpoints the caller's canvas points, or null or empty when none were given
     */
    public AddConnectionToViewCommand(IDiagramModelArchimateConnection connection,
                                       IDiagramModelArchimateObject source,
                                       IDiagramModelArchimateObject target,
                                       List<BendpointDto> bendpoints,
                                       List<AbsoluteBendpointDto> absoluteBendpoints) {
        this(connection, source, target, decidesAtExecute(bendpoints, absoluteBendpoints, source, target)
                ? decision(bendpoints, absoluteBendpoints, source, target) : null);
    }

    private AddConnectionToViewCommand(IDiagramModelArchimateConnection connection,
                                        IConnectable source, IConnectable target,
                                        Supplier<List<BendpointDto>> decide) {
        this.connection = connection;
        this.source = source;
        this.target = target;
        this.decide = decide;
        setLabel("Add connection to view");
    }

    /** Canvas points depend on where the ends are, and so does the loop of a self-connection given none. */
    private static boolean decidesAtExecute(List<BendpointDto> bendpoints,
            List<AbsoluteBendpointDto> absoluteBendpoints,
            IDiagramModelArchimateObject source, IDiagramModelArchimateObject target) {
        return (absoluteBendpoints != null && !absoluteBendpoints.isEmpty())
                || ((bendpoints == null || bendpoints.isEmpty())
                        && SelfConnectionLoop.isSelfConnection(source, target));
    }

    /** The same rule the prepare applies, over copies of the caller's lists, read when called. */
    private static Supplier<List<BendpointDto>> decision(List<BendpointDto> bendpoints,
            List<AbsoluteBendpointDto> absoluteBendpoints,
            IDiagramModelArchimateObject source, IDiagramModelArchimateObject target) {
        List<BendpointDto> relative = bendpoints == null ? null : List.copyOf(bendpoints);
        List<AbsoluteBendpointDto> absolute = absoluteBendpoints == null ? null : List.copyOf(absoluteBendpoints);
        return () -> SelfConnectionLoop.creationBendpoints(relative, absolute, source, target);
    }

    @Override
    public void execute() {
        writeDecidedBendpoints();
        connection.connect(source, target);
    }

    @Override
    public void undo() {
        connection.disconnect();
    }

    @Override
    public void redo() {
        writeDecidedBendpoints();
        // disconnect() preserves connection's internal source/target fields,
        // and connect() has an early-return guard when they match the arguments.
        // Clear them first so connect() proceeds through the full path.
        connection.setSource(null);
        connection.setTarget(null);
        connection.connect(source, target);
    }

    /** Replaces the connection's bendpoints with the ones decided on the first execute, when this decides them. */
    private void writeDecidedBendpoints() {
        if (decide == null) {
            return;
        }
        if (decided == null) {
            decided = ConnectionResponseBuilder.createEmfBendpoints(decide.get());
        }
        connection.getBendpoints().clear();
        connection.getBendpoints().addAll(decided);
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
