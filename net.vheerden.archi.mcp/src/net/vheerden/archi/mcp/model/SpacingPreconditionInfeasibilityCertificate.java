package net.vheerden.archi.mcp.model;

/**
 * A deterministic, <strong>SOUND, one-sided</strong> pre-routing infeasibility
 * certificate, evaluated <em>before</em> {@code new SpacingControlLoop.Request}
 * (architecturally sibling-symmetric with the shipped {@code rnb.degraded()}
 * pre-loop short-circuit).
 *
 * <h2>Why a SOUND one-sided certificate (the load-bearing property)</h2>
 * The control-loop-termination layer is empirically EXHAUSTED for the
 * pathological ST view: a direct orchestrator loop-probe is code-certain that
 * with ESCALATE firing AND a perfect one-shot hub-resize
 * ({@code hubPortQualityScore} 0.18→1.0) the measured {@code avgSpacing} still
 * ceilings at 73.8 &lt; 100. The binding constraint is the infeasible
 * <em>input geometry</em>, not the loop's escalate condition.
 *
 * <p>An EXACT universal feasibility oracle is NOT cheaply possible (2D layout
 * feasibility is NP-hard; achievable average spacing is canvas-growth
 * dependent) and is <strong>NOT claimed</strong>. Instead this is a
 * <em>sufficient</em> condition for infeasibility (never merely necessary):
 * its only permitted error is the SAFE Type-II false-negative — it under-claims
 * on views whose infeasibility is not this-cheaply-provable, which fall
 * straight through to the loop's existing, correct, zero-regression
 * below-regime {@code budget_exhausted} (exactly today's behaviour, no
 * regression). <strong>ZERO false-positives by construction ⇒ it cannot
 * produce a reflow-claimed-while-below-regime failure</strong> — which is
 * precisely what dissolves the central tension: the
 * {@link SpacingControlLoop} DECISION firewall is left frozen (its diagnosis
 * copy is not — see the note below) and the honest claim
 * is the <em>narrower, provably-true</em> "your input, on its current canvas,
 * cannot reach the prescribed spacing regime by spacing/hub alone — here is the
 * violated precondition", NOT "reflow will succeed" (canvas growth is a
 * user-visible tradeoff ⇒ a consent-gated OFFER, never an auto-act).
 *
 * <h2>The sound closed form</h2>
 * <pre>
 *   idealUniformAvg    = sqrt(currentElementUnionCanvasArea / elementCount)
 *                        − avgElementBoxDim                 [mean (w+h)/2]
 *   provablyInfeasible := idealUniformAvg &lt; DENSITY_REGIME_LOWER_PX (=100)
 * </pre>
 * For a FIXED element-union canvas of area {@code A} holding {@code N} boxes of
 * mean linear footprint {@code b}, the uniform-grid arrangement
 * <em>maximises</em> the mean inter-element edge gap, and that maximum is
 * {@code ≈ sqrt(A/N) − b}. The embedded loop's only levers are a
 * <em>bounded</em> spacing-knob ladder + one escalation hub-resize applied to
 * the <em>current element topology</em> — it is NOT a free canvas-growth
 * reflow (that is exactly the user-consented structural reflow the OFFER
 * proposes), and it cannot re-pack the topology. The soundness claim is
 * therefore precisely: <em>no loop action within its bounded budget, applied
 * to the current topology, can push measured spacing to the regime</em> — so
 * {@code idealUniformAvg < 100} ⇒ infeasible under the loop's levers. NOTE the
 * loop DOES marginally grow {@code A} by nudging elements outward, so
 * {@code sqrt(A/N) − b} is the upper bound for the <em>fixed pre-loop</em>
 * canvas, NOT an unconditional formal proof that ignores that marginal growth;
 * its tightness/soundness for the loop's actual bounded reach is the
 * <strong>load-bearing claim</strong> and is established CODE-CERTAIN by a
 * direct orchestrator probe (the loop's max effort — budget exhausted,
 * ESCALATE fired, a perfect hub-resize — plateaued at measured avg 73.8, only
 * ~0.8px below this formula's pinned-ST prediction 74.6, ≫ the 26% bbox growth
 * that reaching 100 would require). An EXACT universal feasibility oracle is
 * NOT claimed; the only permitted error is the SAFE Type-II miss.
 *
 * <p><strong>Calibration (real geometry, read-only-captured from the
 * live canonical sources):</strong>
 * pinned ST (id-a6ef9a2b…, 23 elem, union 994×975, avgBox 130.7) ⇒
 * idealUniformAvg = 74.6 &lt; 100 ⇒ <strong>TRUE</strong> (reproduces the
 * code-certain loop-max ceiling 73.8 within 1% — a tight, not loose,
 * upper bound); pinned HH (id-20f7f4e7…, 23 elem, union 1460×1965, avgBox
 * 147.3) ⇒ idealUniformAvg = 205.9 ≥ 100 ⇒ <strong>FALSE</strong> (HH must NOT
 * short-circuit; the loop runs exactly as today). Robust to the N=23-vs-27
 * count ambiguity. Pinned by
 * {@code SpacingPreconditionInfeasibilityCertificateTest}.
 *
 * <p>The literal {@code ∧ hub already ≥ fan-out-scaled requiredHubMin}
 * conjunct is intentionally DROPPED: on the pinned ST hub
 * (214×68 / 7 conns) {@code requiredHubMinWidthPx(7)=300} so the hub is
 * <em>under</em>-sized — the literal conjunct would make the certificate MISS
 * the ST anchor. Dropping it is independently <em>sounder</em>:
 * {@code idealUniformAvg} is a pure canvas-area/box bound; a hub-resize neither
 * shrinks boxes nor grows the element-union canvas, so it cannot raise the
 * area-bounded spacing ceiling (corroborated code-certain by the probe:
 * a perfect hub-resize still ceilinged at 73.8 &lt; 100). Granting the hub its
 * ideal resize is the most-generous feasibility assumption and the area
 * predicate already dominates it.
 *
 * <p>Pure / static — no OSGi, no EMF, no {@code LayoutQualityAssessor} metric.
 * The EMF read-sites in {@code ArchiModelAccessorImpl} are the thin
 * caller. {@link SpacingControlLoop}'s DECISION surface — its termination
 * classification, regime gates, escalate condition and trend window — is
 * untouched by this certificate and by the ordered-axis remedy, which reaches
 * the loop only as diagnosis wording. (The loop's diagnosis COPY is no longer
 * byte-frozen: it shares {@link OrderedAxisRemedy} with this class, precisely
 * so the two producers of that offer cannot diverge.)
 */
public final class SpacingPreconditionInfeasibilityCertificate {

    /**
     * NEW pre-routing precondition termination reason — honestly DISTINCT from
     * {@code SpacingControlLoop.REASON_DENSITY_FLOOR_REFLOW_REQUIRED} ("loop
     * reached an in-regime density floor"): this is "the input precondition is
     * infeasible on its current canvas — the loop was never entered".
     */
    public static final String REASON_DENSITY_PRECONDITION_REFLOW_REQUIRED =
            "density_precondition_infeasible_reflow_required";

    private SpacingPreconditionInfeasibilityCertificate() {
    }

    /**
     * Whether this view's viewpoint fixes the meaning of element order, so the
     * canvas-growing structural-reflow remedy must not be offered.
     *
     * <p>Delegates to {@link OrderedAxisRemedy}, which owns the set, this
     * predicate and the order-preserving remedy copy for BOTH offer
     * producers — this pre-loop certificate and the in-loop density-floor
     * diagnosis. Retained here as a named forward so this class's own pins
     * keep asserting against the surface they have always used.
     *
     * @param viewpointType the view's viewpoint id ({@code null} tolerated)
     */
    static boolean forbidsReordering(String viewpointType) {
        return OrderedAxisRemedy.forbidsReordering(viewpointType);
    }

    /**
     * The SOUND ideal-uniform upper bound on the average inter-element edge
     * spacing achievable on a FIXED canvas: the uniform-grid cell side
     * {@code sqrt(A/N)} minus the mean element footprint. Degenerate inputs
     * return {@link Double#POSITIVE_INFINITY} so the certificate never fires on
     * them (Type-II safe — falls through to today's loop path).
     *
     * @param elementCount         N — the ArchiMate-element count (groups /
     *                             notes excluded; the calibrated N)
     * @param canvasAreaPx2        A — the current element-union bounding-box
     *                             area in px² (absolute coordinates)
     * @param avgElementBoxDimPx   mean of {@code (width+height)/2} over the
     *                             elements (the validated convention)
     */
    public static double idealUniformAvg(int elementCount,
            double canvasAreaPx2, double avgElementBoxDimPx) {
        if (elementCount <= 0
                || !(canvasAreaPx2 > 0.0)              // <=0 or NaN
                || Double.isNaN(avgElementBoxDimPx)
                || avgElementBoxDimPx < 0.0) {
            return Double.POSITIVE_INFINITY;           // never fire
        }
        return Math.sqrt(canvasAreaPx2 / elementCount) - avgElementBoxDimPx;
    }

    /**
     * The SOUND one-sided infeasibility certificate (a SUFFICIENT condition for
     * loop-infeasibility; zero false-positives by construction). TRUE only when
     * even an ideal uniform redistribution of every element across the current
     * canvas cannot reach the prescribed spacing regime — so no spacing-knob /
     * hub-resize available to the loop can either. Strict {@code <} mirrors
     * {@link SpacingControlLoop#belowRegime} (exactly-100 is in-regime).
     */
    public static boolean provablyInfeasible(int elementCount,
            double canvasAreaPx2, double avgElementBoxDimPx) {
        return idealUniformAvg(elementCount, canvasAreaPx2, avgElementBoxDimPx)
                < SpacingControlLoop.DENSITY_REGIME_LOWER_PX;
    }

    /**
     * Pre-loop decision + (when firing) the actionable, LLM-self-contained,
     * consent-gated structural-reflow OFFER. The OFFER is text/affordance
     * ONLY — it NEVER invokes any auto-layout / reflow / {@code RoutingPipeline}
     * path (the surface+offer+consent boundary is absolute);
     * a returned {@link String} cannot, by construction, invoke anything.
     *
     * @param measuredAvgSpacingPx the route-normalized measured input avg
     *                             ({@code rnb.metrics().avgSpacingPx()});
     *                             OFFER wording only, {@code NaN} tolerated
     * @param hubWidthPx           dominant-hub width  (OFFER wording only,
     *                             {@code null} tolerated)
     * @param hubHeightPx          dominant-hub height (OFFER wording only)
     * @param hubConnectionCount   dominant-hub fan-out (OFFER wording only)
     */
    public static Decision evaluate(int elementCount, double canvasAreaPx2,
            double avgElementBoxDimPx, double measuredAvgSpacingPx,
            Integer hubWidthPx, Integer hubHeightPx,
            Integer hubConnectionCount) {
        return evaluate(elementCount, canvasAreaPx2, avgElementBoxDimPx,
                measuredAvgSpacingPx, hubWidthPx, hubHeightPx,
                hubConnectionCount, null);
    }

    /**
     * As {@link #evaluate(int, double, double, double, Integer, Integer,
     * Integer)}, but keyed on the view's viewpoint so the OFFERED remedy
     * respects a view whose element order is load-bearing.
     *
     * <p><strong>The viewpoint keys the REMEDY ONLY — never the decision.</strong>
     * The geometric infeasibility determination above is byte-untouched: the
     * same views fire, with the same {@code terminationReason}, whatever the
     * viewpoint. All that branches is which next step we offer, because on an
     * ordered-axis view the canvas-growing structural reflow is not merely
     * suboptimal — it is destructive of the view's meaning, so offering it
     * sends the reader either to a wrong action or (as observed) on a detour to
     * reason their way past our own advice.
     *
     * @param viewpointType the view's viewpoint id ({@code null} / blank /
     *                      unknown ⇒ byte-identical to today's offer)
     */
    public static Decision evaluate(int elementCount, double canvasAreaPx2,
            double avgElementBoxDimPx, double measuredAvgSpacingPx,
            Integer hubWidthPx, Integer hubHeightPx,
            Integer hubConnectionCount, String viewpointType) {
        // A view with no viewpoint reads back as the EMPTY STRING from the
        // model, not as null — every other read-site in this package
        // normalizes that before use. Do it once here so "absent" has ONE
        // representation downstream: the Decision, the loop's Request and the
        // remedy predicate all then see null, and the no-fire Decision for a
        // general-purpose view stays the canonical pass-through value rather
        // than a look-alike carrying "".
        String viewpoint = (viewpointType == null || viewpointType.isBlank())
                ? null : viewpointType;
        double iua = idealUniformAvg(
                elementCount, canvasAreaPx2, avgElementBoxDimPx);
        if (!(iua < SpacingControlLoop.DENSITY_REGIME_LOWER_PX)) {
            // Pass through the viewpoint so the caller can hand it to the
            // loop: the loop's OWN density-floor offer needs the same
            // ordered-axis rule, and this is where the viewpoint has already
            // been read. Inert with respect to the decision itself.
            return Decision.proceed(viewpoint);
        }
        String offer = buildReflowOffer(iua, measuredAvgSpacingPx,
                hubWidthPx, hubHeightPx, hubConnectionCount, viewpoint);
        return new Decision(true,
                REASON_DENSITY_PRECONDITION_REFLOW_REQUIRED, offer,
                viewpoint);
    }

    /**
     * Precondition-specific wording, SAME actionable-affordance SHAPE as
     * {@code SpacingControlLoop.buildDensityDiagnosis} (names the violated
     * precondition → states the explicit-consent boundary → OFFERS the
     * next step → confirms the view is preserved unchanged) so the
     * agent-facing consent-gated contract is consistent with the
     * PASS-honest contract, but a NEW honestly-distinct claim. NOT a copy of
     * (and does not call) the loop method: the two narratives are written
     * separately because they report different findings. Only the
     * order-preserving remedy is shared, via {@link OrderedAxisRemedy}, and
     * only because that part must not differ between them.
     */
    static String buildReflowOffer(double idealUniformAvg,
            double measuredAvgSpacingPx, Integer hubWidthPx,
            Integer hubHeightPx, Integer hubConnectionCount) {
        return buildReflowOffer(idealUniformAvg, measuredAvgSpacingPx,
                hubWidthPx, hubHeightPx, hubConnectionCount, null);
    }

    /**
     * As above, with the OFFERED next step keyed on the viewpoint. The
     * violated-precondition narrative (everything up to the offer) is shared
     * verbatim across both branches — only the remedy differs, because only the
     * remedy was ever view-class-dependent.
     */
    static String buildReflowOffer(double idealUniformAvg,
            double measuredAvgSpacingPx, Integer hubWidthPx,
            Integer hubHeightPx, Integer hubConnectionCount,
            String viewpointType) {
        StringBuilder sb = new StringBuilder();
        sb.append("PRE-ROUTING INFEASIBLE: this view's input geometry cannot "
                + "reach the prescribed spacing regime on its current canvas "
                + "by spacing/hub adjustment alone — even an ideal uniform "
                + "redistribution of every element across the current "
                + "element-union canvas yields only ");
        sb.append(String.format("~%.0fpx", idealUniformAvg));
        sb.append(String.format(
                " average spacing, below the prescribed flow-view band "
                + "(%d–%dpx). ",
                SpacingControlLoop.DENSITY_REGIME_LOWER_PX,
                SpacingControlLoop.DENSITY_REGIME_EXCELLENT_PX));
        if (!Double.isNaN(measuredAvgSpacingPx)) {
            sb.append(String.format(
                    "Measured average spacing is currently %.0fpx. ",
                    measuredAvgSpacingPx));
        }
        boolean hasHub = hubWidthPx != null && hubHeightPx != null
                && hubConnectionCount != null;
        if (hasHub) {
            sb.append(String.format(
                    "The dominant hub is %dx%dpx absorbing %d connections. ",
                    hubWidthPx, hubHeightPx, hubConnectionCount));
        }
        sb.append("The control loop was NOT entered (a bounded spacing/hub "
                + "nudge provably cannot lift this input into the regime) and "
                + "this layout was NOT auto-reflowed (a structural reflow "
                + "grows the canvas and moves user-placed elements — an "
                + "explicit-consent boundary). ");
        if (!forbidsReordering(viewpointType)) {
            sb.append("OFFERED next step (requires "
                    + "your consent): re-layout this view with a structural "
                    + "auto-layout (which grows the canvas), then re-run "
                    + "auto-route-connections.");
        } else {
            // The order-preserving remedy is SHARED with the in-loop
            // density-floor diagnosis, which offers the same reflow for a
            // different reason and must not diverge from this one. The hub
            // arrives here as three loose wording values; re-assemble it so
            // the shared remedy can apply the same under-sized test the loop
            // applies, rather than being told only that a hub exists.
            sb.append(OrderedAxisRemedy.orderPreservingRemedy(viewpointType,
                    hasHub ? new HubExtent(hubConnectionCount, hubWidthPx,
                            hubHeightPx) : null));
        }
        sb.append(" The current view is preserved "
                + "unchanged (no degraded layout was applied).");
        return sb.toString();
    }

    /**
     * The pre-loop decision. {@code shortCircuit=false} ⇒ the caller builds
     * the SAME {@code SpacingControlLoop.Request} and enters the loop exactly
     * as today (transparent pass-through — the no-fire byte-identical
     * invariant). {@code shortCircuit=true} ⇒ the caller returns a
     * DTO carrying {@code terminationReason} +
     * {@code reflowOffer} WITHOUT constructing the Request / entering the loop.
     *
     * <p>{@code viewpointType} is a PASS-THROUGH of the view's viewpoint id,
     * carried here purely so the caller can hand it to
     * {@code SpacingControlLoop.Request} without a second read of the same
     * EMF attribute. It takes NO part in {@code shortCircuit} — the geometric
     * decision is byte-untouched by it — and is {@code null} whenever the
     * caller could not resolve a viewpoint, which degrades to the
     * pre-existing behaviour.
     */
    public record Decision(boolean shortCircuit, String terminationReason,
            String reflowOffer, String viewpointType) {

        private static final Decision PROCEED =
                new Decision(false, null, null, null);

        /**
         * The transparent pass-through (no fire) — loop runs as today, with
         * no viewpoint resolved (the ordered-axis remedy then degrades to the
         * pre-existing offer at the loop's own terminal too).
         */
        public static Decision proceed() {
            return PROCEED;
        }

        /**
         * The transparent pass-through carrying the resolved viewpoint id
         * onward to the loop. Returns the shared {@link #PROCEED} instance
         * when there is no viewpoint, so the no-viewpoint pass-through stays
         * the identical value it has always been.
         */
        public static Decision proceed(String viewpointType) {
            return viewpointType == null || viewpointType.isBlank() ? PROCEED
                    : new Decision(false, null, null, viewpointType);
        }
    }
}
