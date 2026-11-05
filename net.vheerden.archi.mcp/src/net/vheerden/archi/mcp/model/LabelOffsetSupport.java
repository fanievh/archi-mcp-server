package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;

import com.archimatetool.model.IDiagramModelArchimateConnection;

/**
 * Helpers for the perpendicular label-offset write-back, extracted from the label-optimization fallback
 * so the facade stays lean. Pure command plumbing over {@link SetTextRelativePositionCommand} /
 * {@link SetTextPositionCommand}; no routing or assessment logic.
 *
 * <p>Offsets are a newer-platform feature: {@link #buildOffsetCommands} emits a command only where
 * {@link #isOffsetWritable} holds. The assessor scores a Middle label where its offset draws it, so an
 * offset is visible to the metric, and the label fallback keeps an assignment's offsets only with its
 * positions, when the re-assessment improves.</p>
 */
final class LabelOffsetSupport {

    private LabelOffsetSupport() {
        // static-only utility
    }

    /**
     * True when {@link #buildOffsetCommands} may write an anchor to this connection: the platform exposes
     * the label-offset feature and the label is shown, or shown by the batch (a suppressed label renders
     * nothing, so it moves nothing). The one rule both the write and the best-of-K scorer use, so a
     * candidate is never credited with an offset that does not ship.
     *
     * @param queuedVisibility connection id → visibility a queued command will set, or null outside
     *                         batch mode; see {@link LabelVisibilityReadback#isShown}
     */
    static boolean isOffsetWritable(IDiagramModelArchimateConnection conn, Map<String, Boolean> queuedVisibility) {
        return conn != null && LabelVisibilityReadback.isShown(conn, queuedVisibility)
                && RelativePositionFeature.isSupported(conn);
    }

    /**
     * The ids of {@code connections} that {@link #isOffsetWritable} admits, or none when the pass writes
     * no offsets at all ({@code emitOffsets} false, the auto-layout path).
     */
    static Set<String> offsetWritableIds(boolean emitOffsets,
            List<IDiagramModelArchimateConnection> connections, Map<String, Boolean> queuedVisibility) {
        Set<String> ids = new HashSet<>();
        if (emitOffsets) {
            for (IDiagramModelArchimateConnection conn : connections) {
                if (isOffsetWritable(conn, queuedVisibility)) {
                    ids.add(conn.getId());
                }
            }
        }
        return ids;
    }

    /**
     * Builds perpendicular label-offset commands for the connections in {@code offsets}, including a
     * reset to Centre. Writes only where {@link #isOffsetWritable} holds and the anchor differs from the
     * one held.
     */
    static List<Command> buildOffsetCommands(Map<String, Integer> offsets,
            Map<String, IDiagramModelArchimateConnection> connLookup, Map<String, Boolean> queuedVisibility) {
        List<Command> commands = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : offsets.entrySet()) {
            IDiagramModelArchimateConnection conn = connLookup.get(entry.getKey());
            if (isOffsetWritable(conn, queuedVisibility) && RelativePositionFeature.get(conn) != entry.getValue()) {
                commands.add(new SetTextRelativePositionCommand(conn, entry.getValue()));
            }
        }
        return commands;
    }

    /**
     * Builds the label write-back commands for a routing pass: a {@link SetTextPositionCommand} for every
     * connection whose chosen along-path position differs from its current one, followed by the perpendicular
     * {@link #buildOffsetCommands offset commands}. Consolidates the connection-lookup build and the
     * position-write loop here (lifted out of the routing core) so the offset wiring stays lean. When
     * {@code offsets} is empty (the auto-layout path, which keeps its own offset fallback) this emits only
     * the position commands — identical to the prior inline write-back.
     */
    static List<Command> buildLabelCommands(Map<String, Integer> optimalPositions,
            Map<String, Integer> offsets,
            List<IDiagramModelArchimateConnection> connections, Map<String, Boolean> queuedVisibility) {
        return buildLabelCommands(optimalPositions, offsets, connections, queuedVisibility, null);
    }

    /**
     * {@link #buildLabelCommands(Map, Map, List, Map)} restricted to the labels a routing pass writes: a position or an
     * anchor is written only for a connection in {@code written}. A pick is made on the route the pipeline chose; a
     * connection whose route the pass does not apply keeps its old route, and a label the policy hides draws nothing,
     * so neither may be moved by that pick.
     *
     * @param written the connections whose labels may be written, or null for every connection (the labels-only pass,
     *                which applies no route)
     */
    static List<Command> buildLabelCommands(Map<String, Integer> optimalPositions,
            Map<String, Integer> offsets, List<IDiagramModelArchimateConnection> connections,
            Map<String, Boolean> queuedVisibility, Set<String> written) {
        Map<String, IDiagramModelArchimateConnection> connLookup = new LinkedHashMap<>();
        for (IDiagramModelArchimateConnection conn : connections) {
            if (written == null || written.contains(conn.getId())) {
                connLookup.put(conn.getId(), conn);
            }
        }
        List<Command> commands = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : optimalPositions.entrySet()) {
            IDiagramModelArchimateConnection conn = connLookup.get(entry.getKey());
            if (conn != null && conn.getTextPosition() != entry.getValue()) {
                commands.add(new SetTextPositionCommand(conn, entry.getValue()));
            }
        }
        commands.addAll(buildOffsetCommands(offsets, connLookup, queuedVisibility));
        return commands;
    }

    /** Merges every label command from {@code source} into {@code target}: positions and offsets. */
    static void merge(CompoundCommand target, CompoundCommand source) {
        for (Object cmd : source.getCommands()) {
            target.add((Command) cmd);
        }
    }

    /** Count of unique connections touched by any label command (along-path position or offset). */
    static int countOptimizedLabels(CompoundCommand compound) {
        return countOptimizedLabels(compound.getCommands());
    }

    /** Count of unique connections touched by any label command among {@code commands}. */
    static int countOptimizedLabels(List<?> commands) {
        Set<String> ids = new HashSet<>();
        for (Object cmd : commands) {
            if (cmd instanceof SetTextPositionCommand stpc) {
                ids.add(stpc.getConnection().getId());
            } else if (cmd instanceof SetTextRelativePositionCommand strpc) {
                ids.add(strpc.getConnection().getId());
            }
        }
        return ids.size();
    }
}
