package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result DTO for auto-route-connections (Story 9-5, enhanced Story 10-11, 10-21, 10-30, 10-31, 10-32, 11-31).
 *
 * @param viewId              the view that was routed
 * @param connectionsRouted   number of connections whose bendpoints were updated
 * @param connectionsFailed   number of connections that could not be routed within constraints
 * @param strategy            the routing strategy used ("orthogonal" or "clear")
 * @param routerTypeSwitched  true if the view's connectionRouterType was switched
 *                            from manhattan to manual (bendpoint) mode
 * @param labelsOptimized     number of connection labels whose position was changed (Story 11-31)
 * @param warnings            non-fatal issues (e.g. invalid connection IDs); omitted when empty
 * @param failed              connections that failed constraint validation; omitted when empty
 * @param recommendations     move recommendations for blocking elements; omitted when empty
 * @param violations          constraint violations for force-mode applied routes; omitted when empty
 */
public record AutoRouteResultDto(
        String viewId,
        int connectionsRouted,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        int connectionsFailed,
        String strategy,
        boolean routerTypeSwitched,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        int labelsOptimized,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<String> warnings,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<FailedConnectionDto> failed,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<MoveRecommendationDto> recommendations,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<RoutingViolationDto> violations) {

    /** Compact constructor: null-guard list fields. */
    public AutoRouteResultDto {
        warnings = warnings != null ? warnings : List.of();
        failed = failed != null ? failed : List.of();
        recommendations = recommendations != null ? recommendations : List.of();
        violations = violations != null ? violations : List.of();
    }

    /**
     * Convenience constructor without labelsOptimized (backward compat).
     */
    public AutoRouteResultDto(String viewId, int connectionsRouted,
            int connectionsFailed, String strategy, boolean routerTypeSwitched,
            List<String> warnings, List<FailedConnectionDto> failed,
            List<MoveRecommendationDto> recommendations,
            List<RoutingViolationDto> violations) {
        this(viewId, connectionsRouted, connectionsFailed, strategy,
                routerTypeSwitched, 0, warnings, failed, recommendations, violations);
    }

    /**
     * Convenience constructor without violations (backward compat for default mode).
     */
    public AutoRouteResultDto(String viewId, int connectionsRouted,
            int connectionsFailed, String strategy, boolean routerTypeSwitched,
            List<String> warnings, List<FailedConnectionDto> failed,
            List<MoveRecommendationDto> recommendations) {
        this(viewId, connectionsRouted, connectionsFailed, strategy,
                routerTypeSwitched, 0, warnings, failed, recommendations, List.of());
    }

    /**
     * Convenience constructor without recommendations (backward compat).
     */
    public AutoRouteResultDto(String viewId, int connectionsRouted,
            int connectionsFailed, String strategy, boolean routerTypeSwitched,
            List<String> warnings, List<FailedConnectionDto> failed) {
        this(viewId, connectionsRouted, connectionsFailed, strategy,
                routerTypeSwitched, 0, warnings, failed, List.of(), List.of());
    }

    /**
     * Convenience constructor without failed connections (backward compat).
     */
    public AutoRouteResultDto(String viewId, int connectionsRouted,
            String strategy, boolean routerTypeSwitched,
            List<String> warnings) {
        this(viewId, connectionsRouted, 0, strategy, routerTypeSwitched,
                0, warnings, List.of(), List.of(), List.of());
    }

    /**
     * Convenience constructor without warnings or failed (backward compat).
     */
    public AutoRouteResultDto(String viewId, int connectionsRouted,
            String strategy, boolean routerTypeSwitched) {
        this(viewId, connectionsRouted, 0, strategy, routerTypeSwitched,
                0, List.of(), List.of(), List.of(), List.of());
    }
}
