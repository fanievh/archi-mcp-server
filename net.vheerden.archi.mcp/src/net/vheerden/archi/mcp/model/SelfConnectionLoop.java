package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.gef.commands.Command;

import com.archimatetool.model.IBounds;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;

/**
 * The loop Archi gives a connection from a diagram object to itself.
 *
 * <p>A self-connection with no bendpoints has both ends on the object's centre, so the line Archi
 * draws for it has no length: an undecorated one draws nothing at all, a decorated one only its
 * decoration at the centre. Archi's own editor never leaves one like that.
 * {@code CreateDiagramConnectionCommand.execute()} always adds three bendpoints to a new
 * self-connection, and {@code ReconnectDiagramConnectionCommand.execute()} adds them to a
 * reconnected one only when it has none. So the rule is: a self-connection with no bendpoints gets
 * the default loop, and one with bendpoints keeps them. Every tool here that creates or reroutes a
 * self-connection applies that rule through this class, so a connection made through this server
 * looks the way the same connection drawn in Archi does.
 *
 * <p>The loop is Archi's {@code createBendPointsForCircularConnectionCommand} ({@code release_5.10.0}):
 * offsets {@code (w', 0)}, {@code (w', h')}, {@code (0, h')} from the centre, where
 * {@code w' = (int) max(100, width * 0.6)} is read from the source's stored width and
 * {@code h' = (int) max(60, height * 0.6)} from the target's stored height. Archi first replaces a
 * stored {@code -1} (default size) with 100 or 60; that step is not repeated here because it
 * cannot change the result: {@code -1 * 0.6} is below either floor, which is the value the
 * replacement produces. Each of its three bendpoint commands
 * inserts at index 0, so the bendpoints are <em>stored</em> in the reverse order:
 * {@code (0, h')}, {@code (w', h')}, {@code (w', 0)}. Drawn, the loop leaves the bottom face, runs
 * below and to the right of the object, and re-enters through the right face. Start and end offsets
 * are equal because a self-connection's two reference centres are the same point.
 *
 * <p>"Self" means the same view object, never two objects whose centres happen to coincide: those
 * are an ordinary pair and are routed as one.
 */
final class SelfConnectionLoop {

    /** Archi's floor for the loop's width. */
    private static final int MIN_WIDTH = 100;
    /** Archi's floor for the loop's height. */
    private static final int MIN_HEIGHT = 60;
    private static final double SCALE = 0.6;

    private SelfConnectionLoop() {}

    /** True when both ends are the same diagram object. */
    static boolean isSelfConnection(IConnectable source, IConnectable target) {
        return source != null && source == target;
    }

    /** Archi's default loop for a self-connection on an object with these stored bounds. */
    static List<BendpointDto> defaultLoop(int storedWidth, int storedHeight) {
        int w = (int) Math.max(MIN_WIDTH, storedWidth * SCALE);
        int h = (int) Math.max(MIN_HEIGHT, storedHeight * SCALE);
        return List.of(new BendpointDto(0, h, 0, h), new BendpointDto(w, h, w, h),
                new BendpointDto(w, 0, w, 0));
    }

    /**
     * How far the loops of {@code object}'s self-connections reach past its box, when the box is
     * {@code width} by {@code height}: {left, top, right, bottom}, each zero or more. A loop
     * stored with bendpoints is measured as stored; an ArchiMate self-connection stored without
     * any is measured as the default loop a routing pass gives it at that size. All zeros when
     * the object has no self-connection, which leaves every other layout unchanged.
     */
    static int[] reach(IDiagramModelObject object, int width, int height) {
        int[] reach = new int[4];
        for (IDiagramModelConnection conn : object.getSourceConnections()) {
            if (conn.getTarget() != object) {
                continue;
            }
            List<BendpointDto> loop = new ArrayList<>();
            for (IDiagramModelBendpoint bp : conn.getBendpoints()) {
                loop.add(new BendpointDto(bp.getStartX(), bp.getStartY(), bp.getEndX(), bp.getEndY()));
            }
            if (loop.isEmpty() && conn instanceof IDiagramModelArchimateConnection) {
                loop = defaultLoop(width, height);
            }
            for (BendpointDto bp : loop) {
                // bendpoints are offsets from the box's centre; a self-connection's two are equal
                reach[0] = Math.max(reach[0], -bp.startX() - width / 2);
                reach[1] = Math.max(reach[1], -bp.startY() - height / 2);
                reach[2] = Math.max(reach[2], bp.startX() - width / 2);
                reach[3] = Math.max(reach[3], bp.startY() - height / 2);
            }
        }
        return reach;
    }

    /** Archi's default loop for a self-connection on {@code object}, from its stored bounds. */
    static List<BendpointDto> defaultLoop(IDiagramModelObject object) {
        IBounds bounds = object.getBounds();
        return defaultLoop(bounds.getWidth(), bounds.getHeight());
    }

    /**
     * The bendpoints a new connection is stored with: the caller's, converted from absolute
     * coordinates when given that way, or Archi's default loop for a self-connection given none.
     */
    static List<BendpointDto> creationBendpoints(List<BendpointDto> bendpoints,
            List<AbsoluteBendpointDto> absoluteBendpoints,
            IDiagramModelArchimateObject source, IDiagramModelArchimateObject target) {
        if (absoluteBendpoints != null && !absoluteBendpoints.isEmpty()) {
            return ConnectionResponseBuilder.convertAbsoluteToRelative(absoluteBendpoints, source, target);
        }
        if ((bendpoints == null || bendpoints.isEmpty()) && isSelfConnection(source, target)) {
            return defaultLoop(source);
        }
        return bendpoints;
    }

    /** Gives a new, still bendpoint-free connection the default loop when it is a self-connection. */
    static IDiagramModelArchimateConnection withDefaultLoop(IDiagramModelArchimateConnection conn,
            IDiagramModelArchimateObject source, IDiagramModelArchimateObject target) {
        if (isSelfConnection(source, target) && conn.getBendpoints().isEmpty()) {
            ConnectionResponseBuilder.applyBendpointsToConnection(conn, defaultLoop(source));
        }
        return conn;
    }

    /**
     * The connections a routing pass should route, and the commands that apply Archi's rule to
     * each of the others.
     *
     * @param routable     the targets that are not self-connections, in their original order
     * @param loopCommands one {@link LoopCommand} per ArchiMate self-connection stored without
     *                     bendpoints and, on a deferred arm, per one stored with them too
     * @param loopedIds    the self-connections that held no bendpoints when the call was prepared,
     *                     in order: the ones the commands loop unless something runs before them
     */
    record RouteSplit(List<IDiagramModelConnection> routable, List<Command> loopCommands,
            List<String> loopedIds) {

        /** The loop commands followed by {@code routeCommands}, in a new mutable list. */
        List<Command> loopCommandsThen(List<Command> routeCommands) {
            List<Command> commands = new ArrayList<>(loopCommands);
            commands.addAll(routeCommands);
            return commands;
        }

        /**
         * True when the call writes a route or a loop as the model reads now, which is what earns a
         * view the switch to bendpoint routing. A command kept for a self-connection that holds
         * bendpoints does not: it writes nothing unless an earlier change clears them, so a call
         * with only those would otherwise switch a view whose drawing it does not change.
         *
         * <p>On a deferred arm this is the reading taken at the call, like the rest of the
         * response: a held loop an earlier change clears is looped when the compound runs without
         * switching a Manhattan view, so it stays undrawn there, as a loop Archi's palette draws
         * on such a view does; and a bare one an earlier change gives bendpoints still switches it.
         */
        boolean loopsOrRoutes(Collection<?> routes) {
            return !routes.isEmpty() || !loopedIds.isEmpty();
        }

        /**
         * How many self-connections this call gives a loop, for the approval card, or null when
         * none: a loop is a bendpoint write, so a human approving the call is told about it.
         */
        Integer loopedCount() {
            return loopedIds.isEmpty() ? null : loopedIds.size();
        }

        /**
         * The approval card's statement that the loop is decided when the human approves, or null
         * when the call holds no self-connection command.
         */
        String cardRule() {
            return loopCommands.isEmpty() ? null : "Decided on approval from the bendpoints each "
                    + "self-connection holds then: one holding none is given Archi's default loop, "
                    + "one holding some keeps them. selfConnectionsLooped counts those that held none "
                    + "when this was proposed.";
        }
    }

    /**
     * Gives one self-connection Archi's default loop when it runs, if it is then still on its view,
     * still a self-connection, and holds no bendpoints; otherwise it changes nothing.
     *
     * <p>The decision is made here and not when the call was prepared, because on the deferred
     * paths the two see different models: a batch prepares every call before any of them runs, and
     * an approved proposal runs after a human may have edited the view. An earlier change in the
     * same batch, or the human's edit, may have given the connection bendpoints, which this must not
     * overwrite, or cleared them, which leaves it drawing nothing until this gives it the loop. That
     * is Archi's own reconnect rule, applied to the model as it is. The loop is sized from the
     * object's bounds at the same moment, so a resize queued before it counts too.</p>
     *
     * <p>The bendpoints it replaces are captured when it runs, so undo restores what was there then,
     * not what the prepare saw. When it wrote nothing, undo and redo do nothing. {@code canExecute}
     * stays true: a refusal there would make the stack reject the whole compound.</p>
     */
    static final class LoopCommand extends Command {

        private final IDiagramModelArchimateConnection connection;
        private List<IDiagramModelBendpoint> before;
        /** The bendpoints written, or null when the last execute wrote nothing. */
        private List<IDiagramModelBendpoint> loop;

        LoopCommand(IDiagramModelArchimateConnection connection) {
            this.connection = connection;
        }

        IDiagramModelArchimateConnection getConnection() {
            return connection;
        }

        @Override
        public void execute() {
            loop = null;
            if (connection.getDiagramModel() != null
                    && isSelfConnection(connection.getSource(), connection.getTarget())
                    && connection.getBendpoints().isEmpty()
                    && connection.getSource() instanceof IDiagramModelObject object) {
                before = new ArrayList<>(connection.getBendpoints());
                loop = ConnectionResponseBuilder.createEmfBendpoints(defaultLoop(object));
                write(loop);
            }
        }

        @Override
        public void undo() {
            if (loop != null) {
                write(before);
            }
        }

        @Override
        public void redo() {
            if (loop != null) {
                write(loop);
            }
        }

        private void write(List<IDiagramModelBendpoint> bendpoints) {
            connection.getBendpoints().clear();
            connection.getBendpoints().addAll(bendpoints);
        }
    }

    /**
     * Takes the self-connections out of a routing pass and says so.
     *
     * <p>The orthogonal router aims each end at the other end's centre, so for a self-connection
     * it produces one point beside the object, a stub, and replaces whatever loop was stored. None
     * is routed instead. One that has bendpoints keeps them unchanged, which is Archi's rule on
     * reconnect. An ArchiMate one with none gets Archi's default loop, under every strategy: it
     * draws nothing without one, so leaving it, or clearing it, to no bendpoints is never what a
     * routing pass is for. Any other self-connection is left as stored: one that is not an
     * ArchiMate connection, between notes say, is not given a loop even with no bendpoints,
     * because the command that writes one edits ArchiMate connections only. Every self-connection taken
     * out is named in one warning, worded for {@code arm}, so the caller can tell a kept loop from
     * a new one.</p>
     *
     * <p>The rule is applied by a {@link LoopCommand} when the compound runs. On the applied arm
     * that is straight after this prepare, so only the ones holding no bendpoints get one: a
     * command for one holding bendpoints would do nothing, and on a call with nothing else to do
     * would put an empty entry on the undo stack. On a deferred arm every ArchiMate
     * self-connection gets one, because what it holds by then is not knowable here.</p>
     */
    static RouteSplit splitForRouting(List<IDiagramModelConnection> targets, DispatchArm arm,
            List<String> warnings, List<StructuredWarningDto> structuredWarnings) {
        List<String> kept = new ArrayList<>();
        RouteSplit split = split(targets, arm != DispatchArm.APPLIED, kept);
        AutoRouteWarnings.emitSelfConnectionsNotRouted(kept, split.loopedIds(), arm, warnings,
                structuredWarnings);
        return split;
    }

    /**
     * Takes the self-connections out of a routing pass run inside a layout call, naming nothing.
     *
     * <p>The rule is {@link #splitForRouting}'s. The layout call runs its pass several times,
     * applying and undoing each, and names the self-connections once, from the pass it keeps; so
     * every ArchiMate self-connection gets a {@link LoopCommand}, which reads what the connection
     * holds each time it runs. {@code selfConnectionIds} receives every self-connection taken out,
     * in view order.</p>
     */
    static RouteSplit splitForLayoutPass(List<IDiagramModelConnection> targets,
            List<String> selfConnectionIds) {
        List<String> kept = new ArrayList<>();
        RouteSplit split = split(targets, true, kept);
        for (IDiagramModelConnection conn : targets) {
            if (kept.contains(conn.getId()) || split.loopedIds().contains(conn.getId())) {
                selfConnectionIds.add(conn.getId());
            }
        }
        return split;
    }

    /**
     * The split itself: a {@link LoopCommand} for each ArchiMate self-connection holding no
     * bendpoints, and for each holding some too when {@code everyLoopable}; {@code kept} receives
     * the self-connections not due a loop as read now.
     */
    private static RouteSplit split(List<IDiagramModelConnection> targets, boolean everyLoopable,
            List<String> kept) {
        List<IDiagramModelConnection> routable = new ArrayList<>();
        List<Command> loopCommands = new ArrayList<>();
        List<String> looped = new ArrayList<>();
        for (IDiagramModelConnection conn : targets) {
            if (!isSelfConnection(conn.getSource(), conn.getTarget())) {
                routable.add(conn);
                continue;
            }
            boolean loopable = conn instanceof IDiagramModelArchimateConnection
                    && conn.getSource() instanceof IDiagramModelObject;
            boolean bare = loopable && conn.getBendpoints().isEmpty();
            if (bare || (loopable && everyLoopable)) {
                loopCommands.add(new LoopCommand((IDiagramModelArchimateConnection) conn));
            }
            (bare ? looped : kept).add(conn.getId());
        }
        return new RouteSplit(routable, loopCommands, looped);
    }
}
