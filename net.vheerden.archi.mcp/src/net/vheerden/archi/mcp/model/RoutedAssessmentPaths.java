package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.model.routing.RoutingResult;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Builds {@link AssessmentConnection}s from routed bendpoints that have not been written to the
 * model yet.
 *
 * <p>Routing decisions have to be judged before they are applied — the best-of-K selector scores
 * candidate routings against the live quality assessor. That needs the path in the shape the
 * assessor expects, {@code [sourceCentre, …bendpoints…, targetCentre]}, which is exactly what
 * {@code AssessmentCollector} produces when it reads bendpoints back OUT of the model, and the label
 * state the connection will hold after apply: the candidate's own text position where its label
 * optimizer changed it, and the Label Offset anchor that same pick chose with it
 * ({@link RoutingResult#labelOffsets}), else the one the connection already holds. Building both the same way on both sides of the write is what
 * stops a pre-apply verdict and a post-apply verdict disagreeing.</p>
 *
 * <p>A hidden label reaches this class with no text (the routing input blanks a label Archi will not
 * draw), so it is scored as no label here, as it is after apply. Three differences remain: the
 * auto-hide label policy is applied to the winner only, after selection, so scoring the hide here would
 * reward a candidate for hiding more labels; connections outside the routed batch are absent here and
 * present after apply; and outside force mode a connection whose route violates a constraint is scored
 * on that route, and with the anchor chosen for it, but keeps its old route and gets no anchor.</p>
 *
 * <p>Purely a projection: no EMF is touched and nothing is mutated. The endpoint geometry comes
 * from the routing input's own rectangles, so the caller does not have to re-resolve it. What needs
 * the model (which connections may receive an offset) is resolved by the caller into
 * {@link LabelInputs}.</p>
 */
final class RoutedAssessmentPaths {

    private RoutedAssessmentPaths() {}

    /**
     * The ids whose offset the apply path would write ({@code LabelOffsetSupport.isOffsetWritable}), which
     * the pipeline's label pick is offered anchors for. An empty id set means the pass writes no offsets,
     * so every connection is judged with the anchor it holds.
     */
    record LabelInputs(Set<String> offsetWritableIds) {

        /** No offsets are written: every connection keeps the anchor it holds. */
        static final LabelInputs HELD_ANCHORS = new LabelInputs(Set.of());
    }

    /**
     * Overlays a candidate routing's bendpoints and label state on the routing batch's endpoints.
     *
     * <p>Merges {@code violatedRoutes} over {@code routed} to match what the apply path commits in
     * force mode, so the shape scored here is the shape that can ship; the anchors are the ones the
     * candidate's label pick chose. A connection the candidate did not route keeps a straight
     * centre-to-centre path.</p>
     *
     * <p>Each path ends at its object's exact centre, as the read-back path does. The rounded
     * integer centre lies half a pixel off the true centre of an odd-sized box, which can put the
     * terminal segment inside its own end and charge a crossing the written route does not draw.</p>
     */
    static List<AssessmentConnection> overlay(
            List<RoutingPipeline.ConnectionEndpoints> batchInput, RoutingResult candidate,
            LabelInputs labels) {
        Map<String, List<AbsoluteBendpointDto>> bendpoints =
                new LinkedHashMap<>(candidate.routed());
        bendpoints.putAll(candidate.violatedRoutes());
        Map<String, Integer> positions = candidate.optimalPositions();
        Map<String, Integer> offsets = candidate.labelOffsets();

        List<AssessmentConnection> connections = new ArrayList<>(batchInput.size());
        for (RoutingPipeline.ConnectionEndpoints ep : batchInput) {
            List<double[]> pathPoints = new ArrayList<>();
            pathPoints.add(new double[]{ep.source().x() + ep.source().width() / 2.0,
                    ep.source().y() + ep.source().height() / 2.0});
            List<AbsoluteBendpointDto> routed = bendpoints.get(ep.connectionId());
            if (routed != null) {
                for (AbsoluteBendpointDto bendpoint : routed) {
                    pathPoints.add(new double[]{bendpoint.x(), bendpoint.y()});
                }
            }
            pathPoints.add(new double[]{ep.target().x() + ep.target().width() / 2.0,
                    ep.target().y() + ep.target().height() / 2.0});
            Integer written = labels.offsetWritableIds().contains(ep.connectionId())
                    ? offsets.get(ep.connectionId()) : null;
            connections.add(new AssessmentConnection(
                    ep.connectionId(), ep.source().id(), ep.target().id(), pathPoints, ep.labelText(),
                    positions.getOrDefault(ep.connectionId(), ep.textPosition()),
                    written != null ? written : ep.relativePosition()));
        }
        return connections;
    }
}
