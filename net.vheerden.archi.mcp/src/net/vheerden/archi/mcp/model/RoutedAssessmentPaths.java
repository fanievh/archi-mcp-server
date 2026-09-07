package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.model.routing.RoutingResult;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Builds {@link AssessmentConnection}s from routed bendpoints that have not been written to the
 * model yet.
 *
 * <p>Routing decisions have to be judged before they are applied — the best-of-K selector scores
 * candidate routings against the live quality assessor, and the note-crossing disclosure names
 * crossings on the same call that creates them. Both need the path in the shape the assessor
 * expects, {@code [sourceCentre, …bendpoints…, targetCentre]}, which is exactly what
 * {@code AssessmentCollector} produces when it reads bendpoints back OUT of the model. Building it
 * the same way on both sides of the write is what stops a pre-apply verdict and a post-apply
 * verdict disagreeing.
 *
 * <p>Purely a projection: no EMF is touched and nothing is mutated. The endpoint geometry comes
 * from the routing input's own rectangles, so the caller does not have to re-resolve it.
 */
final class RoutedAssessmentPaths {

    private RoutedAssessmentPaths() {}

    /**
     * Overlays a candidate routing's bendpoints on the routing batch's endpoints.
     *
     * <p>Merges {@code violatedRoutes} over {@code routed} to match what the apply path commits in
     * force mode, so the shape scored here is the shape that can ship. A connection the candidate
     * did not route keeps a straight centre-to-centre path.
     */
    static List<AssessmentConnection> overlay(
            List<RoutingPipeline.ConnectionEndpoints> batchInput, RoutingResult candidate) {
        Map<String, List<AbsoluteBendpointDto>> bendpoints =
                new LinkedHashMap<>(candidate.routed());
        bendpoints.putAll(candidate.violatedRoutes());

        List<AssessmentConnection> connections = new ArrayList<>(batchInput.size());
        for (RoutingPipeline.ConnectionEndpoints ep : batchInput) {
            List<double[]> pathPoints = new ArrayList<>();
            pathPoints.add(new double[]{ep.source().centerX(), ep.source().centerY()});
            List<AbsoluteBendpointDto> routed = bendpoints.get(ep.connectionId());
            if (routed != null) {
                for (AbsoluteBendpointDto bendpoint : routed) {
                    pathPoints.add(new double[]{bendpoint.x(), bendpoint.y()});
                }
            }
            pathPoints.add(new double[]{ep.target().centerX(), ep.target().centerY()});
            connections.add(new AssessmentConnection(
                    ep.connectionId(), ep.source().id(), ep.target().id(),
                    pathPoints, ep.labelText(), ep.textPosition()));
        }
        return connections;
    }
}
