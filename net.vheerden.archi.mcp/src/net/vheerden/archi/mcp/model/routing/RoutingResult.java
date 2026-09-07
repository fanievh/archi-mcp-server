package net.vheerden.archi.mcp.model.routing;

import java.util.List;
import java.util.Map;
import java.util.Set;

import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Composite result from the routing pipeline.
 * Separates successfully routed connections from those that failed constraint validation,
 * includes move recommendations for blocking elements, and label optimization results.
 * Pure-geometry record — no EMF/SWT dependencies.
 *
 * @param routed              map of connectionId to absolute bendpoints for successfully routed connections
 * @param failed              list of connections that could not be routed within constraints
 * @param recommendations     move recommendations for elements blocking failed routes
 * @param violatedRoutes      map of connectionId to absolute bendpoints for connections that failed
 *                            validation but whose routes are preserved for force-mode application
 * @param labelsOptimized     count of labels whose position was changed by the optimizer
 * @param optimalPositions    map of connectionId to optimal textPosition for changed labels
 * @param straightLineCrossings straight-line crossing estimate before routing
 * @param egressRolledBack    count of off-face terminal egress lifts the terminal-clearance pass
 *                            generated then rolled back because applying them would narrow a
 *                            parallel-connection gap below the healthy floor (a layout-bound
 *                            decline — diagnostic only, the routed geometry is unaffected)
 * @param unresolvableLabels  connectionIds whose label has NO collision-free position at any of the
 *                            three candidate positions — the per-connection residual the label optimizer
 *                            computes and previously discarded. Never truncated; empty when every label
 *                            is placeable. Reporting only a count here would be an index into missing data
 *                            (see {@code labelsOptimized}, which is exactly that shape).
 */
public record RoutingResult(Map<String, List<AbsoluteBendpointDto>> routed,
                             List<FailedConnection> failed,
                             List<MoveRecommendation> recommendations,
                             Map<String, List<AbsoluteBendpointDto>> violatedRoutes,
                             int labelsOptimized,
                             Map<String, Integer> optimalPositions,
                             int straightLineCrossings,
                             int egressRolledBack,
                             Set<String> unresolvableLabels) {

    /** Compact constructor: null-guard all fields. */
    public RoutingResult {
        routed = routed != null ? routed : Map.of();
        failed = failed != null ? failed : List.of();
        recommendations = recommendations != null ? recommendations : List.of();
        violatedRoutes = violatedRoutes != null ? violatedRoutes : Map.of();
        optimalPositions = optimalPositions != null ? optimalPositions : Map.of();
        unresolvableLabels = unresolvableLabels != null ? unresolvableLabels : Set.of();
    }

    /** Backward-compatible constructor without unresolvableLabels (defaults it to empty). */
    public RoutingResult(Map<String, List<AbsoluteBendpointDto>> routed,
                         List<FailedConnection> failed,
                         List<MoveRecommendation> recommendations,
                         Map<String, List<AbsoluteBendpointDto>> violatedRoutes,
                         int labelsOptimized,
                         Map<String, Integer> optimalPositions,
                         int straightLineCrossings,
                         int egressRolledBack) {
        this(routed, failed, recommendations, violatedRoutes, labelsOptimized, optimalPositions,
                straightLineCrossings, egressRolledBack, Set.of());
    }

    /** Backward-compatible constructor without egressRolledBack (defaults it to 0). */
    public RoutingResult(Map<String, List<AbsoluteBendpointDto>> routed,
                         List<FailedConnection> failed,
                         List<MoveRecommendation> recommendations,
                         Map<String, List<AbsoluteBendpointDto>> violatedRoutes,
                         int labelsOptimized,
                         Map<String, Integer> optimalPositions,
                         int straightLineCrossings) {
        this(routed, failed, recommendations, violatedRoutes, labelsOptimized, optimalPositions,
                straightLineCrossings, 0, Set.of());
    }

    /** Backward-compatible constructor without straightLineCrossings or egressRolledBack. */
    public RoutingResult(Map<String, List<AbsoluteBendpointDto>> routed,
                         List<FailedConnection> failed,
                         List<MoveRecommendation> recommendations,
                         Map<String, List<AbsoluteBendpointDto>> violatedRoutes,
                         int labelsOptimized,
                         Map<String, Integer> optimalPositions) {
        this(routed, failed, recommendations, violatedRoutes, labelsOptimized, optimalPositions, 0, 0, Set.of());
    }

    /** Backward-compatible constructor without label optimization fields. */
    public RoutingResult(Map<String, List<AbsoluteBendpointDto>> routed,
                         List<FailedConnection> failed,
                         List<MoveRecommendation> recommendations,
                         Map<String, List<AbsoluteBendpointDto>> violatedRoutes) {
        this(routed, failed, recommendations, violatedRoutes, 0, null, 0, 0, Set.of());
    }

    /** Backward-compatible constructor without violatedRoutes or label optimization. */
    public RoutingResult(Map<String, List<AbsoluteBendpointDto>> routed,
                         List<FailedConnection> failed,
                         List<MoveRecommendation> recommendations) {
        this(routed, failed, recommendations, null, 0, null, 0, 0, Set.of());
    }
}
