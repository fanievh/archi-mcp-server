package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result DTO for auto-route-connections.
 *
 * @param viewId              the view that was routed
 * @param connectionsRouted   number of connections whose bendpoints were updated
 * @param connectionsFailed   number of connections that could not be routed within constraints
 * @param strategy            the routing strategy used ("orthogonal" or "clear")
 * @param routerTypeSwitched  true if the view's connectionRouterType was switched
 *                            from manhattan to manual (bendpoint) mode
 * @param labelsOptimized     number of connection labels whose position was changed
 * @param crossingsBefore     crossing count among targeted connections before routing; omitted when 0
 * @param crossingsAfter      crossing count among targeted connections after routing; omitted when 0
 * @param straightLineCrossings straight-line crossing estimate before routing; omitted when 0
 * @param connectionsSkipped  connections not modified (already-orthogonal + obstacle-veto + crossing-veto + interior-veto + zigzag-veto); omitted when 0
 * @param vetoedByObstacle    connections where terminals-only refused a rectification because the new L-bend would cross an unrelated element; omitted when 0
 * @param vetoedByCrossing    connections where terminals-only refused a rectification because the new path would add edge crossings with other connections; omitted when 0
 * @param vetoedByInterior    connections where terminals-only refused a rectification because the new L-bend would terminate strictly inside the source or target element (interior termination, a Tier-1 routing defect); omitted when 0
 * @param vetoedByZigzag      connections where terminals-only refused a rectification because the new L-bend would introduce a zigzag/reversal the original path did not have (a Tier-1 routing defect); omitted when 0
 * @param warnings            non-fatal issues (e.g. invalid connection IDs); omitted when empty
 * @param failed              connections that failed constraint validation; omitted when empty
 * @param recommendations     move recommendations for blocking elements on the advisory path
 *                            (autoNudge=false); omitted when empty. Reserved for advisory mode
 *                            — when autoNudge=true is blocked by sibling
 *                            overlap, recommendations move to {@link #blockedRecommendations}.
 * @param violations          constraint violations for force-mode applied routes; omitted when empty
 * @param nudgedElements      elements automatically nudged by autoNudge; omitted when empty
 * @param resizedGroups       groups automatically resized to contain nudged elements; omitted when empty
 * @param structuredWarnings  machine-parseable counterparts to {@link #warnings} entries that
 *                            carry a stable {@code code} value plus optional remediation
 *                            metadata for deterministic LLM iteration (see
 *                            {@code RoutingPreconditions.AutoRouteStructuredWarning}); omitted
 *                            when empty
 * @param blockedRecommendations recommendations the autoNudge phase would have applied
 *                            automatically but did not, because sibling-element overlap
 *                            blocked the nudge; omitted when empty. When
 *                            populated, the agent must apply these manually (typically
 *                            by resolving the underlying overlap via layout-within-group
 *                            and re-running auto-route-connections).
 * @param hiddenLabels        connections whose label the label policy hid because it had no
 *                            collision-free position, each named by connection ID with a reason;
 *                            omitted when empty. Never a count and never truncated — a caller that
 *                            cannot see the canvas must be able to restore or audit each one
 *                            individually. Empty whenever the policy was not requested.
 * @param nudgeBlockedReason  canonical reason the autoNudge was blocked;
 *                            omitted (null) when nudge was not requested or ran to
 *                            completion. Current value: {@code "sibling_overlap"}.
 *                            See {@link AutoRouteBlockedReasons}.
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
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        int connectionsSkipped,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        int vetoedByObstacle,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        int vetoedByCrossing,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        int vetoedByInterior,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        int vetoedByZigzag,
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
        List<ResizedGroupDto> resizedGroups,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<StructuredWarningDto> structuredWarnings,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<MoveRecommendationDto> blockedRecommendations,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String nudgeBlockedReason,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<HiddenLabelDto> hiddenLabels) {

    /** Compact constructor: null-guard list fields. */
    public AutoRouteResultDto {
        warnings = warnings != null ? warnings : List.of();
        failed = failed != null ? failed : List.of();
        recommendations = recommendations != null ? recommendations : List.of();
        violations = violations != null ? violations : List.of();
        nudgedElements = nudgedElements != null ? nudgedElements : List.of();
        resizedGroups = resizedGroups != null ? resizedGroups : List.of();
        structuredWarnings = structuredWarnings != null ? structuredWarnings : List.of();
        blockedRecommendations = blockedRecommendations != null ? blockedRecommendations : List.of();
        // nudgeBlockedReason may legitimately be null (omitted from JSON); no guard needed.
        hiddenLabels = hiddenLabels != null ? hiddenLabels : List.of();
    }

    /**
     * Returns a copy carrying the given hidden-label list.
     *
     * <p>Exists so the list can be attached <em>after</em> the write, once the model has actually
     * been mutated: on the immediate path the reported set is re-read from the model rather than
     * echoed from what the policy intended to do. On the deferred paths nothing has been written
     * yet, so what travels here is a projection — and the response envelope nests the whole entity
     * under {@code preview} there, which is what marks it as not-yet-effective.</p>
     */
    public AutoRouteResultDto withHiddenLabels(List<HiddenLabelDto> hidden) {
        return new AutoRouteResultDto(viewId, connectionsRouted, connectionsFailed, strategy,
                routerTypeSwitched, labelsOptimized, crossingsBefore, crossingsAfter,
                straightLineCrossings, connectionsSkipped, vetoedByObstacle, vetoedByCrossing,
                vetoedByInterior, vetoedByZigzag, warnings, failed, recommendations, violations,
                nudgedElements, resizedGroups, structuredWarnings, blockedRecommendations,
                nudgeBlockedReason, hidden);
    }

    /**
     * Convenience constructor accepting the canonical pre-14-12 21-arg signature
     * (without {@code blockedRecommendations} / {@code nudgeBlockedReason}).
     * Defaults the two new fields to empty / null; used by pre-14-12 call sites
     * that do not need the new fields (e.g. the terminals-only DTO construction
     * site in {@code ArchiModelAccessorImpl.autoRouteConnections}, which is
     * mutex with autoNudge and therefore cannot observe a blocked nudge).
     */
    public AutoRouteResultDto(String viewId, int connectionsRouted,
            int connectionsFailed, String strategy, boolean routerTypeSwitched,
            int labelsOptimized, int crossingsBefore, int crossingsAfter,
            int straightLineCrossings,
            int connectionsSkipped, int vetoedByObstacle, int vetoedByCrossing,
            int vetoedByInterior, int vetoedByZigzag,
            List<String> warnings, List<FailedConnectionDto> failed,
            List<MoveRecommendationDto> recommendations,
            List<RoutingViolationDto> violations,
            List<NudgedElementDto> nudgedElements,
            List<ResizedGroupDto> resizedGroups,
            List<StructuredWarningDto> structuredWarnings) {
        this(viewId, connectionsRouted, connectionsFailed, strategy, routerTypeSwitched,
                labelsOptimized, crossingsBefore, crossingsAfter, straightLineCrossings,
                connectionsSkipped, vetoedByObstacle, vetoedByCrossing,
                vetoedByInterior, vetoedByZigzag,
                warnings, failed, recommendations, violations,
                nudgedElements, resizedGroups, structuredWarnings,
                List.of(), null, List.of());
    }

    /**
     * Convenience constructor accepting structuredWarnings without the vetoed-counters.
     * Defaults connectionsSkipped and all four vetoed-counters to 0; used by the autoNudge-skip
     * emission path in {@code ArchiModelAccessorImpl.autoRouteConnections}.
     */
    public AutoRouteResultDto(String viewId, int connectionsRouted,
            int connectionsFailed, String strategy, boolean routerTypeSwitched,
            int labelsOptimized, int crossingsBefore, int crossingsAfter,
            int straightLineCrossings,
            List<String> warnings, List<FailedConnectionDto> failed,
            List<MoveRecommendationDto> recommendations,
            List<RoutingViolationDto> violations,
            List<NudgedElementDto> nudgedElements,
            List<ResizedGroupDto> resizedGroups,
            List<StructuredWarningDto> structuredWarnings) {
        this(viewId,
                connectionsRouted,
                connectionsFailed,
                strategy,
                routerTypeSwitched,
                labelsOptimized,
                crossingsBefore,
                crossingsAfter,
                straightLineCrossings,
                0,
                0,
                0,
                0,
                0,
                warnings,
                failed,
                recommendations,
                violations,
                nudgedElements,
                resizedGroups,
                structuredWarnings,
                List.of(),
                null,
                List.of());
    }

    /**
     * Convenience constructor without connectionsSkipped (backward compat with 15-param
     * callers). Defaults connectionsSkipped to 0.
     */
    public AutoRouteResultDto(String viewId, int connectionsRouted,
            int connectionsFailed, String strategy, boolean routerTypeSwitched,
            int labelsOptimized, int crossingsBefore, int crossingsAfter,
            int straightLineCrossings,
            List<String> warnings, List<FailedConnectionDto> failed,
            List<MoveRecommendationDto> recommendations,
            List<RoutingViolationDto> violations,
            List<NudgedElementDto> nudgedElements,
            List<ResizedGroupDto> resizedGroups) {
        this(viewId,
                connectionsRouted,
                connectionsFailed,
                strategy,
                routerTypeSwitched,
                labelsOptimized,
                crossingsBefore,
                crossingsAfter,
                straightLineCrossings,
                0,
                0,
                0,
                0,
                0,
                warnings,
                failed,
                recommendations,
                violations,
                nudgedElements,
                resizedGroups,
                List.of(),
                List.of(),
                null,
                List.of());
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
        this(viewId,
                connectionsRouted,
                connectionsFailed,
                strategy,
                routerTypeSwitched,
                labelsOptimized,
                crossingsBefore,
                crossingsAfter,
                0,
                0,
                0,
                0,
                0,
                0,
                warnings,
                failed,
                recommendations,
                violations,
                nudgedElements,
                resizedGroups,
                List.of(),
                List.of(),
                null,
                List.of());
    }

    /**
     * Convenience constructor without resizedGroups (backward compat for 13-param callers).
     */
    public AutoRouteResultDto(String viewId, int connectionsRouted,
            int connectionsFailed, String strategy, boolean routerTypeSwitched,
            int labelsOptimized, int crossingsBefore, int crossingsAfter,
            List<String> warnings, List<FailedConnectionDto> failed,
            List<MoveRecommendationDto> recommendations,
            List<RoutingViolationDto> violations,
            List<NudgedElementDto> nudgedElements) {
        this(viewId,
                connectionsRouted,
                connectionsFailed,
                strategy,
                routerTypeSwitched,
                labelsOptimized,
                crossingsBefore,
                crossingsAfter,
                0,
                0,
                0,
                0,
                0,
                0,
                warnings,
                failed,
                recommendations,
                violations,
                nudgedElements,
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of());
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
        this(viewId,
                connectionsRouted,
                connectionsFailed,
                strategy,
                routerTypeSwitched,
                labelsOptimized,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                warnings,
                failed,
                recommendations,
                violations,
                nudgedElements,
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of());
    }

    /**
     * Convenience constructor without crossings or nudgedElements (backward compat).
     */
    public AutoRouteResultDto(String viewId, int connectionsRouted,
            int connectionsFailed, String strategy, boolean routerTypeSwitched,
            int labelsOptimized, List<String> warnings, List<FailedConnectionDto> failed,
            List<MoveRecommendationDto> recommendations,
            List<RoutingViolationDto> violations) {
        this(viewId,
                connectionsRouted,
                connectionsFailed,
                strategy,
                routerTypeSwitched,
                labelsOptimized,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                warnings,
                failed,
                recommendations,
                violations,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of());
    }

    /**
     * Convenience constructor without labelsOptimized (backward compat).
     */
    public AutoRouteResultDto(String viewId, int connectionsRouted,
            int connectionsFailed, String strategy, boolean routerTypeSwitched,
            List<String> warnings, List<FailedConnectionDto> failed,
            List<MoveRecommendationDto> recommendations,
            List<RoutingViolationDto> violations) {
        this(viewId,
                connectionsRouted,
                connectionsFailed,
                strategy,
                routerTypeSwitched,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                warnings,
                failed,
                recommendations,
                violations,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of());
    }

    /**
     * Convenience constructor without violations (backward compat for default mode).
     */
    public AutoRouteResultDto(String viewId, int connectionsRouted,
            int connectionsFailed, String strategy, boolean routerTypeSwitched,
            List<String> warnings, List<FailedConnectionDto> failed,
            List<MoveRecommendationDto> recommendations) {
        this(viewId,
                connectionsRouted,
                connectionsFailed,
                strategy,
                routerTypeSwitched,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                warnings,
                failed,
                recommendations,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of());
    }

    /**
     * Convenience constructor without recommendations (backward compat).
     */
    public AutoRouteResultDto(String viewId, int connectionsRouted,
            int connectionsFailed, String strategy, boolean routerTypeSwitched,
            List<String> warnings, List<FailedConnectionDto> failed) {
        this(viewId,
                connectionsRouted,
                connectionsFailed,
                strategy,
                routerTypeSwitched,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                warnings,
                failed,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of());
    }

    /**
     * Convenience constructor without failed connections (backward compat).
     */
    public AutoRouteResultDto(String viewId, int connectionsRouted,
            String strategy, boolean routerTypeSwitched,
            List<String> warnings) {
        this(viewId,
                connectionsRouted,
                0,
                strategy,
                routerTypeSwitched,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                warnings,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of());
    }

    /**
     * Convenience constructor for an early-exit result that carries both warning surfaces.
     * Used where routing aborts before any measurement, but a coded warning has already been
     * raised and must not be dropped on the way out.
     */
    public AutoRouteResultDto(String viewId, int connectionsRouted,
            String strategy, boolean routerTypeSwitched,
            List<String> warnings, List<StructuredWarningDto> structuredWarnings) {
        this(viewId,
                connectionsRouted,
                0,
                strategy,
                routerTypeSwitched,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                warnings,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                structuredWarnings,
                List.of(),
                null,
                List.of());
    }

    /**
     * Convenience constructor without warnings or failed (backward compat).
     */
    public AutoRouteResultDto(String viewId, int connectionsRouted,
            String strategy, boolean routerTypeSwitched) {
        this(viewId,
                connectionsRouted,
                0,
                strategy,
                routerTypeSwitched,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of());
    }
}
