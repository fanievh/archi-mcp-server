package net.vheerden.archi.mcp.model.routing;

import java.util.List;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Terminal-anchoring wrap-site test helper. Collapses the "spy pattern over each of the
 * five wrap sites" into a single source-of-truth oracle that
 * {@link ChopboxAnchorDegeneracyTest} dispatches over the {@link WrapSite}
 * enum — all five wrap sites evaluate the same predicate
 * ({@link TerminalAnchoring#preservesEndpoints}), so the helper can deliver
 * the predicate's verdict uniformly without per-site reflection.
 *
 * <p><strong>This helper covers the predicate only, not the rollback policy.</strong>
 * The {@code site} argument is a dispatch label: the same verdict is returned for
 * every constant, so nothing here discriminates between the wrap sites. That is
 * sound for the predicate — which really is shared — and the policy is now shared
 * too, all five sites rolling back on a per-end true-to-false flip with three of
 * them also pinning a terminal that arrived off-face. <em>Neither fact is
 * observable here.</em> A shared policy is not the same claim as a shared verdict,
 * and this helper never invokes a mutator, so no assertion routed through it can
 * see a rollback at all. The policy is pinned by driving the mutators — in
 * {@code TerminalAnchoringRollbackPolicyTest} for the four
 * {@link PathStraightener} sites, and in {@code CoincidentSegmentDetectorTest} for
 * {@link CoincidentSegmentDetector#applyOffsets}.
 *
 * <p>The predicate is enforced exclusively at
 * {@code path[0]} / {@code path[last]} mutator sites; a hypothetical sixth
 * mutator that joins the rule auto-qualifies through the enum without test
 * scope renegotiation.
 */
public final class TerminalAnchoringAssertion {

    private TerminalAnchoringAssertion() {
        // Static helper — no instances.
    }

    /**
     * Returns the predicate verdict for the (row, wrap-site) pair. The
     * {@code site} parameter is the cartesian dispatch dimension; all five
     * wrap sites enforce the same predicate, so the same oracle answer is
     * returned regardless of {@code site}. Including {@code site} here keeps
     * the call site shape stable for the 5 × 81 = 405 assertion
     * matrix and ensures parameterised test names cite the wrap-site under
     * which each assertion is recorded.
     */
    public static boolean preservesEndpoints(
            WrapSite site,
            TerminalAnchoring sourceAnchoring, RoutingRect source, int[] sourceCenter,
            TerminalAnchoring targetAnchoring, RoutingRect target, int[] targetCenter,
            List<AbsoluteBendpointDto> path) {
        if (site == null) {
            throw new IllegalArgumentException("WrapSite must not be null");
        }
        return TerminalAnchoring.preservesEndpoints(
                sourceAnchoring, source, sourceCenter,
                targetAnchoring, target, targetCenter, path);
    }
}
