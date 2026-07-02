package net.vheerden.archi.mcp.response.dto;

/**
 * Canonical {@link StructuredWarningDto#code} constants.
 *
 * <p>Test fixtures and documentation reference these constants rather than
 * string literals. Codes are stable; once published, never renamed (only
 * deprecated and superseded by a new code). This is part of the MCP response
 * contract.</p>
 */
public final class StructuredWarningCodes {

    private StructuredWarningCodes() {}

    /**
     * {@code auto-route-connections} {@code autoNudge} skipped because sibling
     * elements overlap (Story RoutingPreconditions.AutoRouteStructuredWarning,
     * Row E).
     */
    public static final String AUTO_NUDGE_SKIPPED_SIBLING_OVERLAP =
            "AUTO_NUDGE_SKIPPED_SIBLING_OVERLAP";

    /**
     * {@code auto-route-connections} generated one or more off-face terminal
     * egress lifts and then rolled them back because applying them would narrow a
     * parallel-connection gap below the router's healthy floor. The hug is left in
     * place deliberately: the corridor is too tight for a healthy lift, so the
     * remedy is layout (widen the corridor / increase element spacing), not a
     * routing re-run.
     */
    public static final String EGRESS_LIFT_LAYOUT_BOUND =
            "EGRESS_LIFT_LAYOUT_BOUND";
}
