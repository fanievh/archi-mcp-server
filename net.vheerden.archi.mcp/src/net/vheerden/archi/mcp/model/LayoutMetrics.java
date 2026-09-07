package net.vheerden.archi.mcp.model;

/**
 * Pure-EMF-free immutable snapshot of the layout-quality metrics the
 * {@link SpacingControlLoop} consumes from per-iteration {@code assess-layout}
 * observations.
 *
 * <p>This record is the loop's contract surface with the assess-layout
 * primitive. The accessor's {@link SpacingControlLoop.Callbacks#observeLayout()}
 * closure is responsible for translating
 * {@code AssessLayoutResultDto} into this record so the loop stays
 * EMF-free and pure-unit testable.</p>
 *
 * <p><strong>Field semantics:</strong>
 * <ul>
 *   <li>{@code thresholdsMet} — count of tier-1L narrow thresholds met at this
 *       snapshot (the load-bearing aggregate).</li>
 *   <li>{@code hpq} — Hub Port Quality score (0.0–1.0).</li>
 *   <li>{@code m4} — connectionEdgeCoincidenceCount (per the M4-floor
 *       work; lower is better).</li>
 *   <li>{@code coincidentSegmentCount} — Tier-1 visual severity metric; lower
 *       is better.</li>
 *   <li>{@code boundaryViolations} — sentinel field; lower is better.</li>
 *   <li>{@code vp10} — parallelConnectionGap_V_p10; higher is
 *       better.</li>
 *   <li>{@code edgeCrossings} — total connection crossings on the view; lower
 *       is better; used as secondary tie-break per
 *       {@link SpacingControlLoop} § back-off predicate.</li>
 *   <li>{@code avgSpacingPx} — the view's measured average element spacing
 *       (sourced from {@code AssessLayoutResultDto.averageSpacing()} — an
 *       EXISTING assess read, NOT a new {@code LayoutQualityAssessor} metric).
 *       The
 *       <em>spacing-regime-position axis</em> input for the density-aware
 *       3-state termination discriminator. {@link Double#NaN} = "avgSpacing
 *       unknown / not supplied" — the regime signal is then ABSENT and the
 *       {@link SpacingControlLoop} reduces byte-identically to the
 *       2-state back-off (preserves every pre-existing pin).</li>
 * </ul></p>
 *
 * <p>Pure-record — no behaviour. The loop's back-off + tie-break +
 * density-aware-termination logic lives in {@link SpacingControlLoop}.</p>
 */
public record LayoutMetrics(
        int thresholdsMet,
        double hpq,
        int m4,
        int coincidentSegmentCount,
        int boundaryViolations,
        double vp10,
        int edgeCrossings,
        double avgSpacingPx) {

    /**
     * Backwards-compatible 7-arg constructor — preserve every
     * pre-existing {@code new LayoutMetrics(...)} call site so the
     * 563/0/0/0-class pin baseline stays GREEN.
     *
     * <p>Delegates to the canonical 8-arg form with
     * {@code avgSpacingPx = }{@link Double#NaN} — the sentinel meaning "the
     * spacing-regime-position axis input is not supplied". When BOTH this
     * sentinel AND a null {@link SpacingControlLoop.Request#hubExtent()} are
     * in effect, the density-aware discriminator is inert and the loop
     * behaves exactly as the original 2-state termination did. The production
     * {@code ArchiModelAccessorImpl.toLayoutMetrics(...)} builder uses the
     * canonical 8-arg form to pass the real measured
     * {@code AssessLayoutResultDto.averageSpacing()}.</p>
     */
    public LayoutMetrics(
            int thresholdsMet,
            double hpq,
            int m4,
            int coincidentSegmentCount,
            int boundaryViolations,
            double vp10,
            int edgeCrossings) {
        this(thresholdsMet, hpq, m4, coincidentSegmentCount,
                boundaryViolations, vp10, edgeCrossings, Double.NaN);
    }
}
