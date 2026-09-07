package net.vheerden.archi.mcp.model;

/**
 * The ONE definition of "this view's element order is load-bearing", and the
 * ONE order-preserving remedy emitted when it is.
 *
 * <h2>Why this is a shared collaborator and not a branch inside one producer</h2>
 * A consent-gated structural-reflow offer is emitted from <strong>two</strong>
 * places, for two honestly-distinct reasons:
 * <ul>
 *   <li>{@link SpacingPreconditionInfeasibilityCertificate} — pre-loop, when
 *       the input geometry provably cannot reach the spacing regime on its
 *       current canvas, so the loop is never entered;</li>
 *   <li>{@link SpacingControlLoop#buildDensityDiagnosis} — in-loop, when the
 *       loop <em>did</em> reach the regime but the quality aggregate stalled
 *       at its density floor.</li>
 * </ul>
 * Those are different views: the first fires on infeasible geometry, the
 * second on feasible geometry. A view whose element order carries meaning can
 * reach <em>either</em>, so a rule honoured at only one of them is not a rule
 * the product has — it is a rule that holds on half the input space. Keeping
 * the predicate and the remedy text here means the two producers cannot
 * diverge by someone editing one copy; the alternative (copying the branch)
 * re-creates exactly the asymmetry this class exists to remove.
 *
 * <p>Pure / static — no OSGi, no EMF, no metric. The viewpoint id arrives as a
 * plain value read by the caller.
 */
final class OrderedAxisRemedy {

    /**
     * The viewpoints on which element ORDER carries meaning, so a
     * connectivity-based structural auto-layout is NOT a legal remedy: its
     * reorder would destroy the very axis the view exists to communicate.
     *
     * <p>Both are ArchiMate/Archi viewpoint ids. {@code implementation_migration}
     * (Implementation and Migration) is the roadmap shape whose primary axis is
     * <em>time</em>, not layer or flow — plateaus and gaps read left-to-right in
     * chronological order. {@code migration} (Migration) admits exactly
     * {@code Gap} + {@code Plateau}: the chronological plateau spine and nothing
     * else, so it carries the identical constraint even more purely.
     *
     * <p>Deliberately NOT included: {@code project} /
     * {@code implementation_deployment} (work-package structure, no mandatory
     * axis) and {@code value_stream} (stage order is sequential by convention,
     * but no fixed-axis guarantee is claimed here). The set is kept MINIMAL and
     * evidence-backed on purpose — a false membership silently degrades a
     * correct, useful reflow offer on views that reorder perfectly well.
     *
     * <p><strong>Also deliberately NOT included — a process-cooperation
     * (swimlane) view.</strong> Its step order <em>is</em> the triggering/flow
     * chain, so a connectivity-driven re-layout re-derives that order rather
     * than scrambling it; the recommended build sequence for that view class
     * prescribes the connectivity-driven group arrangement precisely because
     * of this. Claiming a reflow "would scramble that order" there would be
     * false. What such a view can genuinely lose to a re-layout is its lane
     * membership, not its step sequence — a different harm, whose remedy is
     * the group-preserving layout mode, not withholding this offer.
     *
     * <h2>Known limitation — what a viewpoint id cannot reach</h2>
     * Two ordered-axis view classes are undetectable here <em>by
     * construction</em>, and are NOT claimed as covered:
     * <ul>
     *   <li>A <strong>customer-journey / service-design band</strong>, whose
     *       left-to-right journey order is as load-bearing as a roadmap's
     *       chronology, is built as a general-purpose view with the viewpoint
     *       deliberately omitted — and no journey/service-design id exists in
     *       the ArchiMate viewpoint vocabulary to key on. No addition to this
     *       set can ever reach it.</li>
     *   <li>A <strong>roadmap built with the viewpoint omitted</strong>, which
     *       is a sanctioned way to build one, is likewise invisible here.</li>
     * </ul>
     * Both degrade to the pre-existing offer, which is the SAFE direction:
     * this predicate under-claims rather than misfiring on a view that
     * reorders fine. Reaching them needs a signal other than the viewpoint id
     * (view content, or an explicit caller declaration).
     */
    private static final java.util.Set<String> ORDERED_AXIS_VIEWPOINTS =
            java.util.Set.of("implementation_migration", "migration");

    private OrderedAxisRemedy() {
    }

    /**
     * Whether this view's viewpoint fixes the meaning of element order, so the
     * canvas-growing structural-reflow remedy must not be offered.
     *
     * <p>An absent / blank viewpoint (a general-purpose view) returns
     * {@code false} — the honest answer is "unknown, so behave exactly as
     * today". See the known-limitation note above: that degrades to the
     * pre-existing offer, it never misfires on a view that reorders fine.
     *
     * @param viewpointType the view's viewpoint id ({@code null} tolerated)
     */
    static boolean forbidsReordering(String viewpointType) {
        return viewpointType != null && ORDERED_AXIS_VIEWPOINTS.contains(
                viewpointType.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * The order-preserving remedy, emitted verbatim by BOTH offer producers.
     * States why the order is load-bearing, declines the reordering re-layout
     * with its reason, and offers what to do instead.
     *
     * <p>Callers own the surrounding narrative (which precondition was
     * violated, and the closing "the current view is preserved unchanged"),
     * because that part differs honestly between the pre-loop and in-loop
     * cases. Only the remedy was ever view-class-dependent, so only the
     * remedy is shared.
     *
     * <p>Rendered ONLY when {@link #forbidsReordering} is true, so the
     * interpolated viewpoint id is always a real, non-blank value — there is
     * no absent-id case that could emit an empty parenthetical.
     *
     * @param viewpointType the view's viewpoint id; must be in the set
     * @param dominantHub   the hub the caller already named, or {@code null}
     *                      if it named none. Remedy (1) — enlarge that hub —
     *                      is offered ONLY when the hub is actually
     *                      under-sized for its connection fan-out. Telling an
     *                      agent to enlarge a hub that already meets the
     *                      fan-out minimum is advice it cannot act on: it
     *                      would enlarge, re-measure, find nothing improved,
     *                      and have no way to tell that the remedy was never
     *                      applicable. When enlarging is not a real remedy
     *                      there is exactly ONE remedy and the copy stays
     *                      singular — promising "steps ... both of which"
     *                      while emitting one item reads as truncated output.
     */
    static String orderPreservingRemedy(String viewpointType,
            HubExtent dominantHub) {
        // The hub sentence is what remedy (1) refers to, but merely HAVING a
        // hub does not make enlarging it a remedy — the hub has to be the
        // thing that is wrong. This is the same predicate the diagnosis uses
        // to decide whether to call a hub under-sized, so the two cannot
        // disagree within one message.
        boolean hubEnlargementIsActionable =
                SpacingControlLoop.hubUnderSizedForFanOut(dominantHub);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "This view's viewpoint (%s) makes the order in which its "
                + "elements are placed meaningful, so a structural "
                + "auto-layout is NOT offered here: it re-places elements "
                + "by connectivity and would scramble that order. ",
                viewpointType.trim()));
        if (hubEnlargementIsActionable) {
            sb.append("OFFERED next steps (each requires your consent), "
                    + "both of which preserve the existing element order: "
                    + "(1) enlarge the dominant hub identified above, in "
                    + "place, so its connections have room to attach and "
                    + "separate; (2) ");
        } else {
            sb.append("OFFERED next step (requires your consent), which "
                    + "preserves the existing element order: ");
        }
        sb.append("grow the view along its ordered axis and re-space the "
                + "elements in their current sequence — extend the axis, "
                + "do not re-sort it. Then re-run "
                + "auto-route-connections.");
        return sb.toString();
    }
}
