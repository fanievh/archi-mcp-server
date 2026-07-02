package net.vheerden.archi.mcp.model;

import java.util.List;

import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;

/**
 * Assembles the structured/free-text warnings the {@code auto-route-connections} response
 * surfaces. Kept out of the accessor facade so the facade's line-count ratchet is unaffected
 * by warning-message wording.
 */
final class AutoRouteWarnings {

    private AutoRouteWarnings() {}

    /** MCP tool the caller should run to widen a layout-bound corridor before re-routing. */
    private static final String SPACING_REMEDY_TOOL = "apply-spacing-recommendations";

    /**
     * Emits the layout-bound egress-lift warning when the terminal-clearance pass rolled back at
     * least one off-face lift. The router generated the lift(s) and then declined them because
     * applying them would narrow a parallel-connection gap below its healthy floor — a correct,
     * layout-bound decline. This surfaces that otherwise-silent decision so the caller widens the
     * corridor (the only remedy) instead of re-running the router (a no-op). Appends both a
     * machine-parseable {@link StructuredWarningDto} and a mirrored free-text line for back-compat.
     * No-op when {@code egressRolledBack <= 0}.
     *
     * @param egressRolledBack  rolled-back off-face egress-lift count from the routing result
     * @param warnings          the free-text warnings accumulator (mutated)
     * @param structuredWarnings the structured warnings accumulator (mutated)
     */
    static void emitEgressLiftLayoutBound(int egressRolledBack, List<String> warnings,
            List<StructuredWarningDto> structuredWarnings) {
        if (egressRolledBack <= 0) {
            return;
        }
        String message = egressRolledBack + " off-face terminal hug(s) could not be cleared without "
                + "narrowing a parallel-connection gap below the 15px healthy floor, so the router "
                + "kept the hug(s) in place. This is layout-bound: increase element spacing in the "
                + "affected corridor and re-route — re-routing alone will not clear it.";
        warnings.add(message);
        structuredWarnings.add(new StructuredWarningDto(
                StructuredWarningCodes.EGRESS_LIFT_LAYOUT_BOUND,
                message,
                SPACING_REMEDY_TOOL,
                List.of()));
    }
}
