package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.archimatetool.editor.model.commands.NonNotifyingCompoundCommand;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IDiagramModel;

import net.vheerden.archi.mcp.model.routing.LabelPolicy;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;

/**
 * {@code auto-route-connections} with {@code mode: "labels-only"}: one pass that moves only connection labels (text
 * position and Label Offset) over the view's geometry as it stands. It runs only when a caller asks for it.
 *
 * <p>It re-routes nothing and moves nothing: no bendpoint, self-connection loop, router type, bounds or label
 * visibility is written. The pass is {@link LabelOptimizationPass#computeLabelsOnly}, which keeps every label where it
 * is unless the best trial scores strictly lower by the optimizer's own measure. The whole view is in scope.</p>
 *
 * <p>Refused, with nothing stored or queued: a {@code connectionIds} subset (the optimizer would need the labels outside
 * it held fixed but still scored), {@code labelPolicy: "auto-hide-on-collision"} (no routing pass runs to hide a
 * label), a view drawn by Archi's manhattan router (it ignores the stored bendpoints the pass scores labels against),
 * and, inside a batch, a view the batch has already changed in a way the pass reads ({@link QueuedViewGeometry}).
 * The strategy and autoNudge refusals are the facade's, shared with {@code terminals-only}.</p>
 */
final class LabelsOnlyRoute {

    static final String MODE = "labels-only";
    /** The trials the pass runs, as the label fallback does. */
    static final int TRIALS = 10;

    private LabelsOnlyRoute() {
    }

    /**
     * Validates the call and computes the labels-only pass.
     *
     * @param connectionIds the connections the call named, or null/empty for all
     * @throws ModelAccessException {@code INVALID_PARAMETER} for each refusal above
     */
    static AutoRoutePlan prepare(MutationDispatcher dispatcher, String sessionId, IArchimateDiagramModel view,
            String strategy, Collection<String> connectionIds, LabelPolicy labelPolicy) {
        if (connectionIds != null && !connectionIds.isEmpty()) {
            throw new ModelAccessException(
                    "connectionIds cannot be combined with mode '" + MODE + "' — the pass places every label on the"
                            + " view, so it takes no subset",
                    ErrorCode.INVALID_PARAMETER, null, "Omit connectionIds to place every label on the view.", null);
        }
        LabelVisibilityReadback.requireRoutingPass(labelPolicy, MODE + " mode",
                "Omit labelPolicy: " + MODE + " places labels and never hides one. Or omit mode=\"" + MODE
                        + "\" to run the full router, which can hide a label it cannot place.");
        // The batch first: a queued full route or router switch is named as such, not as the router it replaces.
        QueuedViewGeometry.refuseIfChanged(dispatcher, sessionId, view, strategy, MODE, null);
        if (view.getConnectionRouterType() == IDiagramModel.CONNECTION_ROUTER_MANHATTAN) {
            throw new ModelAccessException(
                    "mode '" + MODE + "' cannot place the labels of view '" + view.getId() + "': its lines are drawn"
                            + " by Archi's manhattan router, which ignores stored bendpoints, so the pass cannot see"
                            + " where they run. Nothing was changed.",
                    ErrorCode.INVALID_PARAMETER, null,
                    "Run auto-route-connections without mode first: it stores the routes and switches the view to"
                            + " bendpoint mode. Then run mode '" + MODE + "'.",
                    null);
        }

        LabelOptimizationPass.LabelsOnlyResult result = LabelOptimizationPass.computeLabelsOnly(
                view, TRIALS, dispatcher.queuedLabelVisibilityOfCall());
        NonNotifyingCompoundCommand compound = result.compound();
        // Distinct connections: a position and an offset on one connection count once.
        int labelsOptimized = LabelOffsetSupport.countOptimizedLabels(compound);

        List<String> warnings = new ArrayList<>();
        if (result.labels() == 0) {
            warnings.add("The view has no shown connection label, so there was nothing to place.");
        } else if (result.unchanged()) {
            warnings.add("No label placement scored better than the current one, so every label kept its position"
                    + " and its Label Offset.");
        }
        AutoRouteResultDto dto = new AutoRouteResultDto(view.getId(), 0, 0, strategy, false, labelsOptimized,
                0, 0, 0, 0, 0, 0, 0, 0, warnings, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        Map<String, Object> proposedChanges = new LinkedHashMap<>();
        proposedChanges.put("mode", MODE);
        proposedChanges.put("labelsOptimized", labelsOptimized);
        proposedChanges.put("trials", TRIALS);
        String label = "Auto-route connections (" + MODE + ", " + labelsOptimized + " labels)";
        compound.setLabel(label);
        return new AutoRoutePlan(compound, dto, label, proposedChanges,
                "Label placement computed over the view's current routes and ready for application.");
    }
}
