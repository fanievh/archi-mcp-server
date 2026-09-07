package net.vheerden.archi.mcp.model;

import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;

/**
 * Pure-EMF-free graded intrinsic layout-quality scalar.
 *
 * <p><strong>Why this exists.</strong> The original tool-layer aggregate
 * scored
 * {@code thresholdsMet} as a 4-condition binary-at-zero pseudo-aggregate:
 * {@code (coincSeg==0) + (M4==0) + (boundaryViolations.isEmpty()) +
 * (HPQ>=0.75)}, range [0,4]. Root-cause diagnosis:
 * on the dense gate views M4 stays 3–18 and coincSeg 0–16, so the
 * {@code M4==0} / {@code coincSeg==0} bits are dead weight (≈ always 0). The
 * live signal collapsed to a hypersensitive 2-bit HPQ+boundaryViolations
 * proxy that could not perceive M4 12→5 progress and flipped on a single
 * reroute/inflation transient — the deterministic
 * {@code aggregate_threshold_regressed_at_iteration_0_reverted_to_initial_state}
 * symptom across Sessions 6–10.</p>
 *
 * <p><strong>The ratified design:</strong>
 * replace binary-at-zero with a graded scalar whose intrinsic anchors
 * are <em>reachable and graded along the path the loop actually travels</em>,
 * sourced from the existing owner-ratified, perceptually-calibrated
 * {@code feedback_visual_severity} v3 severity model (the same model behind
 * {@code assess-layout}'s {@code overall} poor/fair/good/excellent rating):
 *
 * <pre>
 * qualityScalar = correctnessBits                 // binary-at-0 real defects [0,3]
 *               + severityTierCreditM4(m4)         // coarse perceptual band  [0,3]
 *               + severityTierCreditCoincSeg(cs)   // coarse perceptual band  [0,3]
 *               + severityTierCreditHpq(hpq)       // perceptual band         [0,3]
 *               // total range [0, 12]
 * </pre></p>
 *
 * <p><strong>Contract preservation:</strong>
 * <ul>
 *   <li><strong>Aggregate back-off, NOT per-metric monotonic:</strong>
 *       {@link SpacingControlLoop#acceptStepDecision} is UNCHANGED — it still
 *       compares this value as a single opaque scalar; only the range widens
 *       [0,4] → [0,12]. (Read that predicate rather than this sentence: it
 *       accepts on a STRICTLY greater scalar and otherwise falls through four
 *       tie-break tiers before defaulting to accept. The {@code >=} shorthand
 *       that used to stand here is not what the body does, and a design draft
 *       built on it will pin the tie-break while believing it pins the accept
 *       rule.) Quinn
 *       adversarial attack A1 (per-metric-monotonic in disguise) FAILS because
 *       components are summed, never compared individually — a step that
 *       improves M4 by 2 bands + HPQ by 2 bands but regresses coincSeg by 1
 *       band scores net +3 → ACCEPT, reproducing the aggregate verdict the
 *       2026-05-13 Arm-6 lesson mandates
 *       ({@code feedback_discipline_rules_aggregate_not_per_metric}).</li>
 *   <li><strong>Intrinsic-only:</strong> band boundaries are
 *       sourced from {@code LayoutQualityAssessor}'s perceptual cut-points
 *       ({@code EDGE_COINCIDENCE_GOOD_MAX=2}/{@code _FAIR_MAX=5};
 *       {@code GOOD_MAX_COINCIDENT=3}/{@code FAIR_MAX_COINCIDENT=8};
 *       {@code HUB_PORT_QUALITY_FAIR/GOOD/PASS=0.5/0.75/0.95}), coarsened so
 *       every M4/coincSeg band spans ≥ 3 consecutive integer values — NOT from
 *       the scenario goal envelope (HH M4≤4 / ST M4≤8). The convenience tool
 *       never receives the strict-narrow empirical goal.</li>
 *   <li><strong>Never backs off / crawls a micro-gradient:</strong>
 *       DEFANGED by coarse bands — noise-level reroute jitter (±1 on M4/coincSeg)
 *       stays in the SAME band → contributes 0 → falls to the existing
 *       intrinsic tie-break; only real progress (e.g. M4 12→5) crosses a band.
 *       Band width IS the noise filter. This is the property
 *       {@code LayoutQualityScalarTest}'s band-width property test pins
 *       mechanically against {@link #M4_BAND_BOUNDARIES} /
 *       {@link #COINCSEG_BAND_BOUNDARIES}.</li>
 * </ul></p>
 *
 * <p><strong>Success-bar reframe (load-bearing):</strong>
 * the aggregate's contract is "do not sabotage the agent — no spurious
 * iteration-0 revert on a state that is actually fine", NOT "be a perfect
 * goal oracle." The agent is the optimizer; the loop is a non-adversarial,
 * honest substrate.</p>
 *
 * <p>This class is the single source of truth for the band
 * arithmetic; {@code LayoutQualityScalarTest}
 * is a coordinated edit against it (coordination contract — same pattern as
 * {@link ElementSpacingHeuristic}).</p>
 */
public final class LayoutQualityScalar {

    private LayoutQualityScalar() {
        // Utility class — not instantiable.
    }

    // ------------------------------------------------------------------
    // Band boundaries — sourced from LayoutQualityAssessor v3 perceptual
    // cut-points, coarsened so every band spans >= 3 consecutive integers
    // (band-width property: no band <= 2 units wide,
    // so reroute jitter +/-1 cannot cross a band). PUBLIC so the property
    // test asserts against the single source of truth.
    // ------------------------------------------------------------------

    /**
     * M4 ({@code connectionEdgeCoincidenceCount}) credit band upper bounds
     * (inclusive). Credit = 3 for {@code m4 <= 2} (assessor
     * {@code EDGE_COINCIDENCE_GOOD_MAX}); 2 for {@code m4 <= 5} (assessor
     * {@code EDGE_COINCIDENCE_FAIR_MAX}); 1 for {@code m4 <= 9} (dense
     * "poor-but-improving" regime); 0 otherwise. Band widths: [0,2]=3,
     * [3,5]=3, [6,9]=4 — all ≥ 3.
     */
    public static final int[] M4_BAND_BOUNDARIES = {2, 5, 9};

    /**
     * coincidentSegmentCount credit band upper bounds (inclusive). Credit =
     * 3 for {@code cs <= 3} (assessor {@code GOOD_MAX_COINCIDENT}); 2 for
     * {@code cs <= 8} (assessor {@code FAIR_MAX_COINCIDENT}); 1 for
     * {@code cs <= 13} (dense regime); 0 otherwise. Band widths: [0,3]=4,
     * [4,8]=5, [9,13]=5 — all ≥ 3.
     */
    public static final int[] COINCSEG_BAND_BOUNDARIES = {3, 8, 13};

    /**
     * HPQ ({@code hubPortQualityScore}) credit band lower bounds (inclusive),
     * highest-credit first. Credit = 3 for {@code hpq >= 0.95} (assessor
     * {@code HUB_PORT_QUALITY_PASS_THRESHOLD}); 2 for {@code hpq >= 0.75}
     * (assessor {@code HUB_PORT_QUALITY_GOOD_THRESHOLD} — the original
     * binary-at-0.75 anchor, RETAINED); 1 for {@code hpq >= 0.5} (assessor
     * {@code HUB_PORT_QUALITY_FAIR_THRESHOLD}); 0 otherwise. HPQ is a
     * continuous double on a perceptual scale, not subject to the
     * integer-band-width rule.
     *
     * <p>Note: as of 2026-06-14 ({@code hubPortQualityScore}) is the MIN (worst) hub-face
     * quality, not the mean (story g-hub-port-onesided-egress-fanout). These band bounds were
     * originally calibrated under mean semantics; under min an HPQ at or below a boundary yields
     * the same-or-lower credit (stricter, by design — a degraded face is no longer averaged away).
     * The bounds were intentionally left unchanged.</p>
     */
    public static final double[] HPQ_BAND_LOWER_BOUNDS = {0.95, 0.75, 0.50};

    /** Maximum value {@link #qualityScalar} can return (4 dimensions × 3). */
    public static final int MAX_QUALITY_SCALAR = 12;

    // ------------------------------------------------------------------
    // Per-dimension severity-tier credit (intrinsic, perceptual)
    // ------------------------------------------------------------------

    /**
     * Coarse intrinsic perceptual-severity credit for M4
     * ({@code connectionEdgeCoincidenceCount}); higher credit = better
     * (lower M4). See {@link #M4_BAND_BOUNDARIES}.
     */
    public static int severityTierCreditM4(int m4) {
        if (m4 <= M4_BAND_BOUNDARIES[0]) return 3;
        if (m4 <= M4_BAND_BOUNDARIES[1]) return 2;
        if (m4 <= M4_BAND_BOUNDARIES[2]) return 1;
        return 0;
    }

    /**
     * Coarse intrinsic perceptual-severity credit for
     * {@code coincidentSegmentCount}; higher credit = better (lower
     * coincSeg). See {@link #COINCSEG_BAND_BOUNDARIES}.
     */
    public static int severityTierCreditCoincSeg(int coincSeg) {
        if (coincSeg <= COINCSEG_BAND_BOUNDARIES[0]) return 3;
        if (coincSeg <= COINCSEG_BAND_BOUNDARIES[1]) return 2;
        if (coincSeg <= COINCSEG_BAND_BOUNDARIES[2]) return 1;
        return 0;
    }

    /**
     * Perceptual credit for HPQ ({@code hubPortQualityScore}); higher credit
     * = better (higher HPQ). The {@code >= 0.75} → 2 band RETAINS the
     * original binary-at-0.75 anchor. See {@link #HPQ_BAND_LOWER_BOUNDS}.
     */
    public static int severityTierCreditHpq(double hpq) {
        if (hpq >= HPQ_BAND_LOWER_BOUNDS[0]) return 3;
        if (hpq >= HPQ_BAND_LOWER_BOUNDS[1]) return 2;
        if (hpq >= HPQ_BAND_LOWER_BOUNDS[2]) return 1;
        return 0;
    }

    /**
     * Binary-at-zero credit for the three genuine correctness defects
     * (a boundary violation / pass-through / sibling
     * overlap is a genuine correctness defect, NOT a graded-quality
     * dimension — these STAY binary-at-0 deliberately). Range [0, 3].
     *
     * @param boundaryViolations sentinel count; 0 → credit
     * @param passThroughs       the CHARGED cross-element pass-through count; 0 → credit.
     *                           A self-element pass-through is charged at zero and so keeps the
     *                           credit — it is unrated because no re-route or spacing change
     *                           reliably removes one, and a bit the loop cannot win is a bit that
     *                           only rejects steps
     * @param overlaps           sibling overlap count; 0 → credit
     */
    public static int correctnessBits(int boundaryViolations,
            int passThroughs, int overlaps) {
        int bits = 0;
        if (boundaryViolations == 0) bits++;
        if (passThroughs == 0) bits++;
        if (overlaps == 0) bits++;
        return bits;
    }

    /**
     * The graded intrinsic quality scalar consumed (opaquely) by
     * {@link SpacingControlLoop#acceptStepDecision} as
     * {@link LayoutMetrics#thresholdsMet()}. Range [0,
     * {@value #MAX_QUALITY_SCALAR}]. Higher is better.
     *
     * @param boundaryViolations sentinel count
     * @param passThroughs       the CHARGED cross-element pass-through count
     *                           ({@code crossElementPassThroughCount}), never the size of the
     *                           {@code connectionPassThroughs} description list — that list is
     *                           capped at ten entries and also names the self-element
     *                           pass-throughs the rating does not charge
     * @param overlaps           sibling overlap count
     * @param m4                 connectionEdgeCoincidenceCount
     * @param coincSeg           coincidentSegmentCount
     * @param hpq                hubPortQualityScore (0.0–1.0)
     */
    public static int qualityScalar(int boundaryViolations, int passThroughs,
            int overlaps, int m4, int coincSeg, double hpq) {
        return correctnessBits(boundaryViolations, passThroughs, overlaps)
                + severityTierCreditM4(m4)
                + severityTierCreditCoincSeg(coincSeg)
                + severityTierCreditHpq(hpq);
    }

    /**
     * Converts an {@link AssessLayoutResultDto} into the pure-EMF-free
     * {@link LayoutMetrics} snapshot the {@link SpacingControlLoop} consumes.
     *
     * <p><strong>Graded quality scalar (2026-05-16).</strong>
     * The {@code thresholdsMet} aggregate was the
     * 4-condition binary-at-zero pseudo-aggregate
     * {@code (coincSeg==0) +
     * (M4==0) + (boundaryViolations.isEmpty()) + (HPQ>=0.75)}, range [0,4].
     * Root-cause diagnosis: on dense gate views the {@code M4==0} /
     * {@code coincSeg==0} bits are dead weight (M4 stays 3–18), collapsing the
     * signal to a hypersensitive 2-bit proxy → deterministic iteration-0 revert
     * across Sessions 6–10. It is now the graded intrinsic
     * {@link LayoutQualityScalar#qualityScalar} (range [0,
     * {@value LayoutQualityScalar#MAX_QUALITY_SCALAR}]) per the
     * ratified design
     * (§ 3.2). The back-off predicate
     * {@link SpacingControlLoop#acceptStepDecision} is UNCHANGED — it still
     * compares this value as a single opaque scalar; only the range widens
     * [0,4] → [0,12], preserving the aggregate-not-per-metric discipline.</p>
     */
    static LayoutMetrics toLayoutMetrics(AssessLayoutResultDto a) {
        // The two correctness reads below deliberately differ, and the asymmetry is not an
        // oversight. A boundary violation is a homogeneous population, so its capped description
        // list and its uncapped count agree at every value a binary-at-zero read can see — a cap
        // truncates, it never empties. The pass-through description list does NOT agree: it also
        // names the self-element pass-throughs the rating charges at zero, so its size is non-zero
        // on a view with no charged crossing at all, and the bit would be forfeited for geometry
        // no spacing lever can move.
        int boundaryViolationsCount = (a.boundaryViolations() == null)
                ? 0 : a.boundaryViolations().size();
        int chargedPassThroughCount = a.crossElementPassThroughCount();
        int thresholdsMet = LayoutQualityScalar.qualityScalar(
                boundaryViolationsCount,
                chargedPassThroughCount,
                a.overlapCount(),
                a.connectionEdgeCoincidenceCount(),
                a.coincidentSegmentCount(),
                a.hubPortQualityScore());
        double vp10 = (a.vAxisParallelGapP10() == null)
                ? 0.0 : a.vAxisParallelGapP10();
        return new LayoutMetrics(
                thresholdsMet,
                a.hubPortQualityScore(),
                a.connectionEdgeCoincidenceCount(),
                a.coincidentSegmentCount(),
                boundaryViolationsCount,
                vp10,
                a.edgeCrossingCount(),
                // The spacing-regime-position axis input, sourced
                // from the EXISTING assess read (NOT a new
                // LayoutQualityAssessor metric). Canonical 8-arg form — every
                // OTHER `new LayoutMetrics(...)` site keeps the 7-arg
                // delegating ctor (avgSpacingPx = NaN → density discriminator
                // inert → pin baseline preserved).
                a.averageSpacing());
    }
}
