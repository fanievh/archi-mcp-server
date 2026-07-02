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
 * <p>Offsets are a newer-platform, render-only cosmetic: {@link #buildOffsetCommands} emits a command
 * only where the platform exposes the feature and the label is visible, and the metric never sees the
 * offset — so the fallback merges these commands even without a measurable improvement (they are
 * metric-neutral and render-positive).</p>
 */
final class LabelOffsetSupport {

    private LabelOffsetSupport() {
        // static-only utility
    }

    /**
     * Builds perpendicular label-offset commands for the connections in {@code offsets}. Skips a
     * suppressed label (it renders nothing, so it moves nothing) and, on a platform without the
     * label-offset feature, emits nothing (the offset path is inert there).
     */
    static List<Command> buildOffsetCommands(Map<String, Integer> offsets,
            Map<String, IDiagramModelArchimateConnection> connLookup) {
        List<Command> commands = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : offsets.entrySet()) {
            IDiagramModelArchimateConnection conn = connLookup.get(entry.getKey());
            if (conn == null || !conn.isNameVisible()) {
                continue;
            }
            if (RelativePositionFeature.isSupported(conn)
                    && RelativePositionFeature.get(conn) != entry.getValue()) {
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
            List<IDiagramModelArchimateConnection> connections) {
        Map<String, IDiagramModelArchimateConnection> connLookup = new LinkedHashMap<>();
        for (IDiagramModelArchimateConnection conn : connections) {
            connLookup.put(conn.getId(), conn);
        }
        List<Command> commands = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : optimalPositions.entrySet()) {
            IDiagramModelArchimateConnection conn = connLookup.get(entry.getKey());
            if (conn != null && conn.getTextPosition() != entry.getValue()) {
                commands.add(new SetTextPositionCommand(conn, entry.getValue()));
            }
        }
        commands.addAll(buildOffsetCommands(offsets, connLookup));
        return commands;
    }

    /** True when the compound carries at least one label-offset command. */
    static boolean hasOffsetCommand(CompoundCommand compound) {
        for (Object cmd : compound.getCommands()) {
            if (cmd instanceof SetTextRelativePositionCommand) {
                return true;
            }
        }
        return false;
    }

    /**
     * Merges label commands from {@code source} into {@code target}. When {@code onlyOffsets} is true,
     * only the offset commands are merged (the along-path position changes are left behind because they
     * did not improve the metric).
     */
    static void merge(CompoundCommand target, CompoundCommand source, boolean onlyOffsets) {
        for (Object cmd : source.getCommands()) {
            if (!onlyOffsets || cmd instanceof SetTextRelativePositionCommand) {
                target.add((Command) cmd);
            }
        }
    }

    /** Count of unique connections touched by any label command (along-path position or offset). */
    static int countOptimizedLabels(CompoundCommand compound) {
        Set<String> ids = new HashSet<>();
        for (Object cmd : compound.getCommands()) {
            if (cmd instanceof SetTextPositionCommand stpc) {
                ids.add(stpc.getConnection().getId());
            } else if (cmd instanceof SetTextRelativePositionCommand strpc) {
                ids.add(strpc.getConnection().getId());
            }
        }
        return ids.size();
    }
}
