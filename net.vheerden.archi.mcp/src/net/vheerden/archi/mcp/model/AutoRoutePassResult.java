package net.vheerden.archi.mcp.model;

import java.util.List;

import com.archimatetool.editor.model.commands.NonNotifyingCompoundCommand;

/**
 * One routing pass of the layout and spacing loops: the compound it would apply and what it did.
 *
 * <p>{@code selfConnectionIds} names the self-connections the pass took out of routing. The
 * orthogonal router turns a self-connection into a stub beside its object, so the pass leaves each
 * one its loop instead, and gives Archi's default loop to one that holds no bendpoints when the
 * compound runs. A loop runs several passes and keeps one, so the call names these once, from the
 * pass it keeps.</p>
 */
final class AutoRoutePassResult {
    final NonNotifyingCompoundCommand compound;
    final int routedCount;
    final int labelsOptimized;
    final boolean routerTypeSwitched;
    /** Connections whose label this pass hid because it had no placeable position. */
    final List<String> hiddenLabelIds;
    /** The self-connections this pass did not route, each left with its loop. */
    final List<String> selfConnectionIds;

    AutoRoutePassResult(NonNotifyingCompoundCommand compound, int routedCount,
            int labelsOptimized, boolean routerTypeSwitched, List<String> hiddenLabelIds,
            List<String> selfConnectionIds) {
        this.compound = compound;
        this.routedCount = routedCount;
        this.labelsOptimized = labelsOptimized;
        this.routerTypeSwitched = routerTypeSwitched;
        this.hiddenLabelIds = (hiddenLabelIds != null) ? hiddenLabelIds : List.of();
        this.selfConnectionIds = (selfConnectionIds != null) ? selfConnectionIds : List.of();
    }

    /** The self-connections {@code pass} did not route, or none when there was no pass. */
    static List<String> selfConnectionIdsOf(AutoRoutePassResult pass) {
        return pass != null ? pass.selfConnectionIds : List.of();
    }
}
