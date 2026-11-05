package net.vheerden.archi.mcp.model.routing;

import java.util.List;

/**
 * What the terminal-egress clearance pass did about off-face hugs, as the caller needs to hear it.
 * Pure geometry facts — no EMF/SWT dependencies.
 *
 * <p>Exactly one of two things can be reported. Either the pass ran and rolled back
 * {@code rolledBack} lifts that would have crowded a parallel connection (a layout-bound decline),
 * or its view-level pre-gate skipped it wholesale because the routes it was handed sit closer
 * together than {@link TerminalEgressClearancePass#PRE_GATE_VP10_PX}. A skip evaluates no lift, so
 * it can roll none back; the constructor refuses the combination rather than let a caller be told
 * both.</p>
 *
 * @param rolledBack           lifts generated then rolled back; 0 on a pre-gate skip
 * @param preGateGapP10        the 10th-percentile vertical parallel-connection gap the pre-gate
 *                             compared (on the routes as they stood when the pass ran), present
 *                             only when the gate skipped the pass; finite; {@code null} when the
 *                             pass ran
 * @param huggingConnectionIds on a pre-gate skip, the connections with at least one terminal still
 *                             hugging the face it departs, in the routes the pipeline returns (the
 *                             router's own per-terminal predicate, collapsed to connections, in
 *                             routing order); empty otherwise
 */
public record EgressOutcome(int rolledBack, Double preGateGapP10, List<String> huggingConnectionIds) {

    /** The pre-gate floor (px) a skip's gap fell below — {@link TerminalEgressClearancePass#PRE_GATE_VP10_PX}. */
    public static final double PRE_GATE_FLOOR_PX = TerminalEgressClearancePass.PRE_GATE_VP10_PX;

    /** Nothing rolled back and no skip — the outcome of a view with nothing to route. */
    public static final EgressOutcome NONE = new EgressOutcome(0, null, List.of());

    /** Validates the either/or shape and copies the id list. */
    public EgressOutcome {
        huggingConnectionIds = huggingConnectionIds != null ? List.copyOf(huggingConnectionIds) : List.of();
        if (preGateGapP10 != null && !Double.isFinite(preGateGapP10)) {
            throw new IllegalArgumentException("a pre-gate gap is a measured distance: " + preGateGapP10);
        }
        if (preGateGapP10 != null && rolledBack != 0) {
            throw new IllegalArgumentException("a pre-gate skip evaluates no lift, so it rolls none back");
        }
        if (preGateGapP10 == null && !huggingConnectionIds.isEmpty()) {
            throw new IllegalArgumentException("hugging connections are reported only for a pre-gate skip");
        }
    }

    /** True when the view-level pre-gate skipped the whole pass. */
    public boolean preGated() {
        return preGateGapP10 != null;
    }
}
