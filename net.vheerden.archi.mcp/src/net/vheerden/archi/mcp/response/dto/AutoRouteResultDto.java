package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result DTO for auto-route-connections (Story 9-5, enhanced Story 10-11, 10-21, 10-30, 10-31, 10-32, 11-31, 13-7, backlog-b14, backlog-b15, backlog-b22).
 *
 * @param viewId              the view that was routed
 * @param connectionsRouted   number of connections whose bendpoints were updated
 * @param connectionsFailed   number of connections that could not be routed within constraints
 * @param strategy            the routing strategy used ("orthogonal" or "clear")
 * @param routerTypeSwitched  true if the view's connectionRouterType was switched
 *                            from manhattan to manual (bendpoint) mode
 * @param labelsOptimized     number of connection labels whose position was changed (Story 11-31)
 * @param crossingsBefore     crossing count among targeted connections before routing (backlog-b14); omitted when 0
 * @param crossingsAfter      crossing count among targeted connections after routing (backlog-b14); omitted when 0
 * @param straightLineCrossings straight-line crossing estimate before routing (backlog-b22); omitted when 0
 * @param warnings            non-fatal issues (e.g. invalid connection IDs); omitted when empty
 * @param failed              connections that failed constraint validation; omitted when empty
 * @param recommendations     move recommendations for blocking elements; omitted when empty
 * @param violations          constraint violations for force-mode applied routes; omitted when empty
 * @param nudgedElements      elements automatically nudged by autoNudge (Story 13-7); omitted when empty
 * @param resizedGroups       groups automatically resized to contain nudged elements (backlog-b15); omitted when empty
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
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        int crossingsBefore,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        int crossingsAfter,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        int straightLineCrossings,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<String> warnings,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<FailedConnectionDto> failed,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<MoveRecommendationDto> recommendations,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<RoutingViolationDto> violations,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<NudgedElementDto> nudgedElements,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<ResizedGroupDto> resizedGroups) {

    /** Compact constructor: null-guard list fields. */
    public AutoRouteResultDto {
        warnings = warnings != null ? warnings : List.of();
        failed = failed != null ? failed : List.of();
        recommendations = recommendations != null ? recommendations : List.of();
        violations = violations != null ? violations : List.of();
        nudgedElements = nudgedElements != null ? nudgedElements : List.of();
        resizedGroups = resizedGroups != null ? resizedGroups : List.of();
    }

    /**
     * Convenience constructor without straightLineCrossings (backward compat with 14-param callers).
     */
    public AutoRouteResultDto(String viewId, int connectionsRouted,
            int connectionsFailed, String strategy, boolean routerTypeSwitched,
            int labelsOptimized, int crossingsBefore, int crossingsAfter,
            List<String> warnings, List<FailedConnectionDto> failed,
            List<MoveRecommendationDto> recommendations,
            List<RoutingViolationDto> violations,
            List<NudgedElementDto> nudgedElements,
            List<ResizedGroupDto> resizedGroups) {
        this(viewId, connectionsRouted, connectionsFailed, strategy,
                routerTypeSwitched, labelsOptimized, crossingsBefore, crossingsAfter,
                0, warnings, failed, recommendations, violations, nudgedElements, resizedGroups);
    }

    /**
     * Convenience constructor without resizedGroups (backward compat with 13-param callers).
     */
    public AutoRouteResultDto(String viewId, int connectionsRouted,
            int connectionsFailed, String strategy, boolean routerTypeSwitched,
            int labelsOptimized, int crossingsBefore, int crossingsAfter,
            List<String> warnings, List<FailedConnectionDto> failed,
            List<MoveRecommendationDto> recommendations,
            List<RoutingViolationDto> violations,
            List<NudgedElementDto> nudgedElements) {
        this(viewId, connectionsRouted, connectionsFailed, strategy,
                routerTypeSwitched, labelsOptimized, crossingsBefore, crossingsAfter,
                0, warnings, failed, recommendations, violations, nudgedElements, List.of());
    }

    /**
     * Convenience constructor without crossings (backward compat, with nudgedElements).
     */
    public AutoRouteResultDto(String viewId, int connectionsRouted,
            int connectionsFailed, String strategy, boolean routerTypeSwitched,
            int labelsOptimized, List<String> warnings, List<FailedConnectionDto> failed,
            List<MoveRecommendationDto> recommendations,
            List<RoutingViolationDto> violations,
            List<NudgedElementDto> nudgedElements) {
        this(viewId, connectionsRouted, connectionsFailed, strategy,
                routerTypeSwitched, labelsOptimized, 0, 0,
                0, warnings, failed, recommendations,
                violations, nudgedElements, List.of());
    }

    /**
     * Convenience constructor without crossings or nudgedElements (backward compat).
     */
    public AutoRouteResultDto(String viewId, int connectionsRouted,
            int connectionsFailed, String strategy, boolean routerTypeSwitched,
            int labelsOptimized, List<String> warnings, List<FailedConnectionDto> failed,
            List<MoveRecommendationDto> recommendations,
            List<RoutingViolationDto> violations) {
        this(viewId, connectionsRouted, connectionsFailed, strategy,
                routerTypeSwitched, labelsOptimized, 0, 0,
                0, warnings, failed, recommendations,
                violations, List.of(), List.of());
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
                routerTypeSwitched, 0, 0, 0,
                0, warnings, failed, recommendations, violations,
                List.of(), List.of());
    }

    /**
     * Convenience constructor without violations (backward compat for default mode).
     */
    public AutoRouteResultDto(String viewId, int connectionsRouted,
            int connectionsFailed, String strategy, boolean routerTypeSwitched,
            List<String> warnings, List<FailedConnectionDto> failed,
            List<MoveRecommendationDto> recommendations) {
        this(viewId, connectionsRouted, connectionsFailed, strategy,
                routerTypeSwitched, 0, 0, 0,
                0, warnings, failed, recommendations,
                List.of(), List.of(), List.of());
    }

    /**
     * Convenience constructor without recommendations (backward compat).
     */
    public AutoRouteResultDto(String viewId, int connectionsRouted,
            int connectionsFailed, String strategy, boolean routerTypeSwitched,
            List<String> warnings, List<FailedConnectionDto> failed) {
        this(viewId, connectionsRouted, connectionsFailed, strategy,
                routerTypeSwitched, 0, 0, 0,
                0, warnings, failed, List.of(),
                List.of(), List.of(), List.of());
    }

    /**
     * Convenience constructor without failed connections (backward compat).
     */
    public AutoRouteResultDto(String viewId, int connectionsRouted,
            String strategy, boolean routerTypeSwitched,
            List<String> warnings) {
        this(viewId, connectionsRouted, 0, strategy, routerTypeSwitched,
                0, 0, 0, 0,
                warnings, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /**
     * Convenience constructor without warnings or failed (backward compat).
     */
    public AutoRouteResultDto(String viewId, int connectionsRouted,
            String strategy, boolean routerTypeSwitched) {
        this(viewId, connectionsRouted, 0, strategy, routerTypeSwitched,
                0, 0, 0, 0,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
