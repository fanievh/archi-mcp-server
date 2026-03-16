package net.vheerden.archi.mcp.model.routing;

import java.util.List;
import java.util.Map;

import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Composite result from the routing pipeline (Story 10-30, extended Story 10-31, 10-32, 11-31).
 * Separates successfully routed connections from those that failed constraint validation,
 * includes move recommendations for blocking elements, and label optimization results.
 * Pure-geometry record — no EMF/SWT dependencies.
 *
 * @param routed            map of connectionId to absolute bendpoints for successfully routed connections
 * @param failed            list of connections that could not be routed within constraints
 * @param recommendations   move recommendations for elements blocking failed routes
 * @param violatedRoutes    map of connectionId to absolute bendpoints for connections that failed
 *                          validation but whose routes are preserved for force-mode application
 * @param labelsOptimized   count of labels whose position was changed by the optimizer (Story 11-31)
 * @param optimalPositions  map of connectionId to optimal textPosition for changed labels (Story 11-31)
 */
public record RoutingResult(Map<String, List<AbsoluteBendpointDto>> routed,
                             List<FailedConnection> failed,
                             List<MoveRecommendation> recommendations,
                             Map<String, List<AbsoluteBendpointDto>> violatedRoutes,
                             int labelsOptimized,
                             Map<String, Integer> optimalPositions) {

    /** Compact constructor: null-guard all fields. */
    public RoutingResult {
        routed = routed != null ? routed : Map.of();
        failed = failed != null ? failed : List.of();
        recommendations = recommendations != null ? recommendations : List.of();
        violatedRoutes = violatedRoutes != null ? violatedRoutes : Map.of();
        optimalPositions = optimalPositions != null ? optimalPositions : Map.of();
    }

    /** Backward-compatible constructor without label optimization fields. */
    public RoutingResult(Map<String, List<AbsoluteBendpointDto>> routed,
                         List<FailedConnection> failed,
                         List<MoveRecommendation> recommendations,
                         Map<String, List<AbsoluteBendpointDto>> violatedRoutes) {
        this(routed, failed, recommendations, violatedRoutes, 0, null);
    }

    /** Backward-compatible constructor without violatedRoutes or label optimization. */
    public RoutingResult(Map<String, List<AbsoluteBendpointDto>> routed,
                         List<FailedConnection> failed,
                         List<MoveRecommendation> recommendations) {
        this(routed, failed, recommendations, null, 0, null);
    }
}
