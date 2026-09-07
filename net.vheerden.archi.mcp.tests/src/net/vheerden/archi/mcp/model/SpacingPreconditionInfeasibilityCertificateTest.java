package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import net.vheerden.archi.mcp.model.SpacingPreconditionInfeasibilityCertificate.Decision;

/**
 * JUnit pin for the SOUND one-sided pre-routing infeasibility certificate.
 * Pure-unit (no OSGi; no transitive class-loading of
 * {@code ArchiModelAccessorImpl} / EMF) — the certificate ARITHMETIC is a
 * pure static, exactly like {@code SpacingControlLoop.classifyDensityTermination};
 * the EMF read-sites are the thin caller ({@code SpacingControlLoop} is
 * byte-frozen — this suite does NOT touch the {@code SpacingControlLoop*}
 * suites).
 *
 * <p><strong>Calibration anchors</strong> — captured READ-ONLY from the live
 * canonical sources,
 * absolute element-union bounds (group-relative child coords resolved):</p>
 * <ul>
 *   <li><strong>ST (RED-by-construction anchor)</strong>
 *       {@code id-a6ef9a2b0f8e48a1815335f4e3907344} "4. Integration
 *       Architecture": 23 elements, element-union bbox 994&times;975 =
 *       969150 px&sup2;, avgElementBoxDim = mean((w+h)/2) = 130.7px ⇒
 *       idealUniformAvg(N=23) = &radic;(969150/23) &minus; 130.7 = 74.57 &lt;
 *       100 ⇒ <em>provablyInfeasible = TRUE</em>. Reproduces the
 *       code-certain probe loop-max ceiling 73.8 (escalation +72px AND a
 *       perfect hub-resize HPQ 0.18&rarr;1.0) within 1% — a tight, sound
 *       upper bound, not a loose one. Robust at N=27 (58.76 &lt; 100).</li>
 *   <li><strong>HH (must be provably FALSE — must NOT short-circuit)</strong>
 *       {@code id-20f7f4e77d324b698bb3fe3f939d5f40} "V4 ... Oracle - route
 *       with v1.3": 23 elements, element-union bbox 1460&times;1965 =
 *       2868900 px&sup2;, avgElementBoxDim = 147.3px ⇒ idealUniformAvg(N=23)
 *       = 205.88 &ge; 100 ⇒ <em>provablyInfeasible = FALSE</em>. Robust at
 *       N=27 (178.67 &ge; 100). Consistent with the offline proof
 *       idealUniformAvg(HH) &ge; measuredAvg(HH)=161.5 &ge; 100.</li>
 * </ul>
 *
 * <p><strong>Soundness property (load-bearing):</strong> the predicate is a
 * <em>sufficient</em> condition for loop-infeasibility (one-sided; zero
 * false-positives by construction). Its only permitted error is the SAFE
 * Type-II miss (under-claim ⇒ fall straight through to the existing,
 * correct, zero-regression below-regime {@code budget_exhausted}). Degenerate
 * inputs NEVER fire (Type-II safe). It therefore cannot produce the
 * reflow-claimed-while-below-regime FAIL; the OFFER's honest claim is
 * the narrower provably-true "input infeasible on its current canvas by
 * spacing/hub alone", NOT "reflow will succeed" (consent-gated, never an
 * auto-act).</p>
 */
public class SpacingPreconditionInfeasibilityCertificateTest {

    private static final int REGIME_LOWER =
            SpacingControlLoop.DENSITY_REGIME_LOWER_PX;            // 100
    private static final int REGIME_EXCELLENT =
            SpacingControlLoop.DENSITY_REGIME_EXCELLENT_PX;        // 124

    // Captured pinned geometry (durable record).
    private static final double ST_AREA = 969150.0;   // 994 x 975
    private static final double ST_AVG_BOX = 130.7;
    private static final double HH_AREA = 2868900.0;  // 1460 x 1965
    private static final double HH_AVG_BOX = 147.3;

    // =================================================================
    // 2.1 — ST-TRUE calibration pin (the RED-by-construction anchor)
    // =================================================================

    @Test
    public void provablyInfeasible_isTrue_forPinnedStGeometry_N23() {
        double iua = SpacingPreconditionInfeasibilityCertificate
                .idealUniformAvg(23, ST_AREA, ST_AVG_BOX);
        assertEquals("ST idealUniformAvg(N=23)", 74.57, iua, 0.5);
        assertTrue("ST idealUniformAvg must be strictly below the 100px regime",
                iua < REGIME_LOWER);
        assertTrue("ST geometry MUST be provably infeasible (RED anchor)",
                SpacingPreconditionInfeasibilityCertificate
                        .provablyInfeasible(23, ST_AREA, ST_AVG_BOX));
    }

    @Test
    public void provablyInfeasible_isTrue_forPinnedStGeometry_N27_robust() {
        // Pinned-record "27 elem" = 23 elem + 3 groups + 1 note view-objects;
        // the certificate stays TRUE either way (robust to the count ambiguity).
        double iua = SpacingPreconditionInfeasibilityCertificate
                .idealUniformAvg(27, ST_AREA, ST_AVG_BOX);
        assertEquals("ST idealUniformAvg(N=27)", 58.76, iua, 0.5);
        assertTrue(SpacingPreconditionInfeasibilityCertificate
                .provablyInfeasible(27, ST_AREA, ST_AVG_BOX));
    }

    @Test
    public void evaluate_forPinnedSt_shortCircuitsWithReasonAndConsentGatedOffer() {
        // ST hub 214x68 / 7 conns; measured avg 60.0 (row-776 aggregate).
        Decision d = SpacingPreconditionInfeasibilityCertificate.evaluate(
                23, ST_AREA, ST_AVG_BOX, /*measuredAvgSpacingPx=*/ 60.0,
                /*hubW=*/ 214, /*hubH=*/ 68, /*hubConns=*/ 7);

        assertTrue("ST must short-circuit (loop NOT entered)", d.shortCircuit());
        assertEquals(SpacingPreconditionInfeasibilityCertificate
                        .REASON_DENSITY_PRECONDITION_REFLOW_REQUIRED,
                d.terminationReason());

        String offer = d.reflowOffer();
        assertNotNull("actionable consent-gated OFFER must be present", offer);
        assertFalse(offer.isBlank());
        // Names the violated precondition: measured avg + the prescribed band.
        assertTrue("OFFER names measured avg", offer.contains("60"));
        assertTrue("OFFER names regime band lower",
                offer.contains(String.valueOf(REGIME_LOWER)));
        assertTrue("OFFER names regime band upper",
                offer.contains(String.valueOf(REGIME_EXCELLENT)));
        // Names the hub WxH vs its connection count.
        assertTrue("OFFER names the hub extent", offer.contains("214")
                && offer.contains("68"));
        assertTrue("OFFER names the hub fan-out", offer.contains("7"));
        // Consent-gated, text/affordance ONLY — NEVER auto-reflow
        // (surface+offer+consent boundary, absolute).
        assertTrue("OFFER must be consent-gated (explicit consent language)",
                offer.toLowerCase().contains("consent"));
        assertTrue("OFFER must state the view is preserved unchanged",
                offer.toLowerCase().contains("preserved")
                        || offer.toLowerCase().contains("not auto"));
        // Honest narrower claim — NOT "reflow will succeed".
        assertFalse("OFFER must not over-claim reflow success",
                offer.toLowerCase().contains("reflow will succeed"));
    }

    // =================================================================
    // 2.2 — HH-FALSE calibration pin + soundness-property parametric pins
    // =================================================================

    @Test
    public void provablyInfeasible_isFalse_forPinnedHhGeometry_N23() {
        double iua = SpacingPreconditionInfeasibilityCertificate
                .idealUniformAvg(23, HH_AREA, HH_AVG_BOX);
        assertEquals("HH idealUniformAvg(N=23)", 205.88, iua, 0.5);
        assertTrue("HH idealUniformAvg must be >= the 100px regime",
                iua >= REGIME_LOWER);
        assertFalse("HH (feasible, Arm-A-clearable) MUST NOT short-circuit",
                SpacingPreconditionInfeasibilityCertificate
                        .provablyInfeasible(23, HH_AREA, HH_AVG_BOX));
    }

    @Test
    public void provablyInfeasible_isFalse_forPinnedHhGeometry_N27_robust() {
        double iua = SpacingPreconditionInfeasibilityCertificate
                .idealUniformAvg(27, HH_AREA, HH_AVG_BOX);
        assertEquals("HH idealUniformAvg(N=27)", 178.67, iua, 0.5);
        assertFalse(SpacingPreconditionInfeasibilityCertificate
                .provablyInfeasible(27, HH_AREA, HH_AVG_BOX));
    }

    @Test
    public void evaluate_forPinnedHh_doesNotShortCircuit_loopRunsAsToday() {
        Decision d = SpacingPreconditionInfeasibilityCertificate.evaluate(
                23, HH_AREA, HH_AVG_BOX, /*measuredAvgSpacingPx=*/ 161.5,
                /*hubW=*/ 300, /*hubH=*/ 250, /*hubConns=*/ 7);
        assertFalse("HH must NOT short-circuit", d.shortCircuit());
        assertNull(d.terminationReason());
        assertNull(d.reflowOffer());
        assertEquals(Decision.proceed(), d);
    }

    @Test
    public void soundnessProperty_oneSided_everyFiringInputHasIdealUniformBelowRegime() {
        // The one-sided direction: provablyInfeasible==true ⟹
        // idealUniformAvg < REGIME_LOWER (the sufficient-condition direction
        // — the defining soundness property; zero false-positive by
        // construction).
        int[][] grid = {
                {10, 90000}, {15, 250000}, {23, 969150}, {27, 969150},
                {40, 1500000}, {30, 600000}, {12, 120000},
        };
        for (int[] g : grid) {
            int n = g[0];
            double area = g[1];
            for (double box : new double[] {0, 50, 100, 130.7, 200}) {
                boolean fired = SpacingPreconditionInfeasibilityCertificate
                        .provablyInfeasible(n, area, box);
                double iua = SpacingPreconditionInfeasibilityCertificate
                        .idealUniformAvg(n, area, box);
                if (fired) {
                    assertTrue("SOUNDNESS VIOLATION: fired but idealUniformAvg "
                            + "(" + iua + ") not < " + REGIME_LOWER
                            + " for N=" + n + " area=" + area + " box=" + box,
                            iua < REGIME_LOWER);
                }
            }
        }
    }

    @Test
    public void soundness_merelyHardButFeasible_hhLikeSpread_returnsFalse() {
        // A merely-hard-but-feasible HH-like datapoint (idealUniformAvg
        // comfortably >= 100) MUST NOT fire (no false-positive).
        assertFalse(SpacingPreconditionInfeasibilityCertificate
                .provablyInfeasible(23, HH_AREA, HH_AVG_BOX));
        // Tighter-but-still-feasible: idealUniformAvg ~ 110 >= 100.
        double iua = SpacingPreconditionInfeasibilityCertificate
                .idealUniformAvg(20, 360000, 24.0); // sqrt(18000)=134.2 -24 ~110
        assertTrue("constructed feasible datapoint sanity", iua >= REGIME_LOWER);
        assertFalse(SpacingPreconditionInfeasibilityCertificate
                .provablyInfeasible(20, 360000, 24.0));
    }

    @Test
    public void soundness_boundary_exactlyRegimeLower_doesNotFire() {
        // idealUniformAvg exactly == REGIME_LOWER is NOT below-regime
        // (mirrors SpacingControlLoop.belowRegime strict `< 100`). Construct
        // area so sqrt(area/N) - box == 100 exactly: N=1, box=0,
        // area=100^2=10000 ⇒ sqrt(10000/1)=100, iua=100.
        double iua = SpacingPreconditionInfeasibilityCertificate
                .idealUniformAvg(1, 10000.0, 0.0);
        assertEquals(100.0, iua, 1e-9);
        assertFalse("exactly-regime-lower is in-regime — must NOT fire",
                SpacingPreconditionInfeasibilityCertificate
                        .provablyInfeasible(1, 10000.0, 0.0));
    }

    @Test
    public void soundness_degenerateInputs_neverFire_typeIISafe() {
        // Zero/negative/NaN inputs MUST NOT fire — Type-II safe fall-through
        // to the existing correct loop path (zero regression).
        assertFalse("elementCount 0", SpacingPreconditionInfeasibilityCertificate
                .provablyInfeasible(0, ST_AREA, ST_AVG_BOX));
        assertFalse("elementCount -1", SpacingPreconditionInfeasibilityCertificate
                .provablyInfeasible(-1, ST_AREA, ST_AVG_BOX));
        assertFalse("area 0", SpacingPreconditionInfeasibilityCertificate
                .provablyInfeasible(23, 0.0, ST_AVG_BOX));
        assertFalse("area negative", SpacingPreconditionInfeasibilityCertificate
                .provablyInfeasible(23, -5.0, ST_AVG_BOX));
        assertFalse("area NaN", SpacingPreconditionInfeasibilityCertificate
                .provablyInfeasible(23, Double.NaN, ST_AVG_BOX));
        assertFalse("box NaN", SpacingPreconditionInfeasibilityCertificate
                .provablyInfeasible(23, ST_AREA, Double.NaN));
        assertFalse("box negative", SpacingPreconditionInfeasibilityCertificate
                .provablyInfeasible(23, ST_AREA, -10.0));
        Decision d = SpacingPreconditionInfeasibilityCertificate.evaluate(
                0, ST_AREA, ST_AVG_BOX, 60.0, 214, 68, 7);
        assertEquals("degenerate ⇒ proceed (loop runs as today)",
                Decision.proceed(), d);
    }

    // =================================================================
    // 2.3 — No-fire invariant pin (byte-identical path when not firing)
    // =================================================================

    @Test
    public void noFire_evaluateDecisionMatchesProvablyInfeasible() {
        // When the certificate does NOT fire, evaluate() returns
        // Decision.proceed() (no reason, no OFFER) ⇒ the caller builds the
        // SAME SpacingControlLoop.Request and enters the loop exactly as
        // today (the precheck is a transparent pass-through). When it DOES
        // fire, shortCircuit() agrees with provablyInfeasible().
        Object[][] cases = {
                {23, HH_AREA, HH_AVG_BOX, 161.5},   // feasible ⇒ proceed
                {23, ST_AREA, ST_AVG_BOX, 60.0},    // infeasible ⇒ short
                {0,  ST_AREA, ST_AVG_BOX, 60.0},    // degenerate ⇒ proceed
        };
        for (Object[] c : cases) {
            int n = (int) c[0];
            double area = (double) c[1];
            double box = (double) c[2];
            double meas = (double) c[3];
            boolean infeasible = SpacingPreconditionInfeasibilityCertificate
                    .provablyInfeasible(n, area, box);
            Decision d = SpacingPreconditionInfeasibilityCertificate.evaluate(
                    n, area, box, meas, 214, 68, 7);
            assertEquals("shortCircuit() must agree with provablyInfeasible()",
                    infeasible, d.shortCircuit());
            if (!d.shortCircuit()) {
                assertEquals("no-fire ⇒ exact pass-through (proceed)",
                        Decision.proceed(), d);
            }
        }
    }

    @Test
    public void noFire_nullHub_isHandled_andDoesNotForceFire() {
        // Hub WxH/conns are OFFER-wording inputs only — null hub must not
        // change the fire decision (it is purely the area predicate) and the
        // OFFER must still be well-formed when it fires without a hub.
        Decision feasible = SpacingPreconditionInfeasibilityCertificate
                .evaluate(23, HH_AREA, HH_AVG_BOX, 161.5, null, null, null);
        assertFalse(feasible.shortCircuit());

        Decision infeasible = SpacingPreconditionInfeasibilityCertificate
                .evaluate(23, ST_AREA, ST_AVG_BOX, 60.0, null, null, null);
        assertTrue(infeasible.shortCircuit());
        assertNotNull(infeasible.reflowOffer());
        assertFalse(infeasible.reflowOffer().isBlank());
    }

    // =================================================================
    // 2.5 — VIEWPOINT-AWARE remedy (the offered next step, NOT the
    //       infeasibility detection). On a viewpoint whose element ORDER
    //       carries meaning (a roadmap's chronological axis), a structural
    //       auto-layout reorders by connectivity and would scramble that
    //       axis — so the reorder offer must not be made there. Detection
    //       is byte-untouched; ONLY the remedy branches.
    // =================================================================

    /**
     * The EXACT remedy tail shipped before the viewpoint branch existed —
     * pinned as a literal so "non-timeline views are byte-identical" is a
     * real byte assertion and not a paraphrase that drifts with the source.
     */
    private static final String LEGACY_REMEDY_TAIL =
            "The control loop was NOT entered (a bounded spacing/hub nudge "
            + "provably cannot lift this input into the regime) and this "
            + "layout was NOT auto-reflowed (a structural reflow grows the "
            + "canvas and moves user-placed elements — an explicit-consent "
            + "boundary). OFFERED next step (requires your consent): "
            + "re-layout this view with a structural auto-layout (which "
            + "grows the canvas), then re-run auto-route-connections. The "
            + "current view is preserved unchanged (no degraded layout was "
            + "applied).";

    private static Decision stWithViewpoint(String viewpointType) {
        return SpacingPreconditionInfeasibilityCertificate.evaluate(
                23, ST_AREA, ST_AVG_BOX, /*measuredAvgSpacingPx=*/ 60.0,
                /*hubW=*/ 214, /*hubH=*/ 68, /*hubConns=*/ 7, viewpointType);
    }

    @Test
    public void forbidsReordering_isTrue_onlyForTheOrderedAxisViewpoints() {
        // The set, from Archi's own viewpoints.xml. `migration` holds exactly
        // {Gap, Plateau} — the chronological plateau spine — so it carries
        // the identical harm as implementation_migration.
        assertTrue(SpacingPreconditionInfeasibilityCertificate
                .forbidsReordering("implementation_migration"));
        assertTrue(SpacingPreconditionInfeasibilityCertificate
                .forbidsReordering("migration"));
        // Everything else reorders safely — incl. absent/blank (a
        // general-purpose view; undetectable ⇒ today's behaviour, Type-II safe).
        assertFalse(SpacingPreconditionInfeasibilityCertificate
                .forbidsReordering(null));
        assertFalse(SpacingPreconditionInfeasibilityCertificate
                .forbidsReordering(""));
        assertFalse(SpacingPreconditionInfeasibilityCertificate
                .forbidsReordering("layered"));
        assertFalse(SpacingPreconditionInfeasibilityCertificate
                .forbidsReordering("implementation_deployment"));
        assertFalse(SpacingPreconditionInfeasibilityCertificate
                .forbidsReordering("project"));
        assertFalse(SpacingPreconditionInfeasibilityCertificate
                .forbidsReordering("value_stream"));
    }

    @Test
    public void forbidsReordering_toleratesCaseAndSurroundingWhitespace() {
        assertTrue(SpacingPreconditionInfeasibilityCertificate
                .forbidsReordering("  Implementation_Migration  "));
        assertTrue(SpacingPreconditionInfeasibilityCertificate
                .forbidsReordering("MIGRATION"));
        // Near-misses must NOT fire (no substring matching).
        assertFalse(SpacingPreconditionInfeasibilityCertificate
                .forbidsReordering("migration_planning"));
        assertFalse(SpacingPreconditionInfeasibilityCertificate
                .forbidsReordering("pre_migration"));
    }

    @Test
    public void offer_onOrderedAxisViewpoint_neverOffersAReorderingReflow() {
        for (String vp : new String[] {"implementation_migration",
                "migration"}) {
            String offer = stWithViewpoint(vp).reflowOffer();
            assertNotNull(offer);
            // The bug: this exact reorder offer is what the roadmap modeller
            // had to reason PAST. It must be gone on these viewpoints.
            assertFalse("[" + vp + "] must not offer a structural auto-layout"
                            + " re-layout as the next step",
                    offer.contains("OFFERED next step (requires your consent):"
                            + " re-layout this view with a structural "
                            + "auto-layout"));
            // ...and it must say WHY, so the agent does not simply go and do
            // it anyway via another tool.
            assertTrue("[" + vp + "] must warn that a connectivity reflow "
                            + "would scramble the ordered axis",
                    offer.toLowerCase().contains("reorder")
                            || offer.toLowerCase().contains("order"));
        }
    }

    @Test
    public void offer_onOrderedAxisViewpoint_namesTheAxisPreservingRemedy() {
        String offer = stWithViewpoint("implementation_migration")
                .reflowOffer();
        // The remedy the NovaBank modeller had to find unaided: resize the
        // dominant hub the diagnosis ALREADY names (214x68 absorbing 7).
        assertTrue("must still name the dominant hub extent",
                offer.contains("214") && offer.contains("68"));
        assertTrue("must still name the hub fan-out", offer.contains("7"));
        // Shared offer shape is retained (consent-gated, view preserved).
        assertTrue("must stay consent-gated",
                offer.toLowerCase().contains("consent"));
        assertTrue("must state the view is preserved unchanged",
                offer.toLowerCase().contains("preserved")
                        || offer.toLowerCase().contains("not auto"));
        // Still an OFFER of a next step — rewording must not strip the
        // affordance and leave the modeller at a dead end.
        assertTrue("must still offer an actionable next step",
                offer.contains("OFFERED"));
        assertFalse("must not over-claim success",
                offer.toLowerCase().contains("reflow will succeed"));
    }

    @Test
    public void offer_onOrderedAxisViewpoint_isWellFormed_whenHubIsAbsent() {
        // Hub is OFFER-wording only and may be null; the axis-preserving
        // branch must not NPE or emit a dangling "resize the null hub".
        Decision d = SpacingPreconditionInfeasibilityCertificate.evaluate(
                23, ST_AREA, ST_AVG_BOX, 60.0, null, null, null,
                "implementation_migration");
        assertTrue(d.shortCircuit());
        String offer = d.reflowOffer();
        assertNotNull(offer);
        assertFalse(offer.isBlank());
        assertFalse("no null leakage into user-facing copy",
                offer.contains("null"));
        // The hub sentence is what remedy (1) refers to — with no hub there is
        // exactly ONE remedy, so the copy must not promise two and deliver one
        // (that reads as truncated output to the agent consuming it).
        assertFalse("must not dangle a (2) it never emits",
                offer.contains("(2)"));
        assertFalse("must not promise 'both' remedies when only one is offered",
                offer.toLowerCase().contains("both"));
        assertTrue("single-remedy copy must stay singular",
                offer.contains("OFFERED next step (requires your consent)"));
        // The one remedy that survives is still the order-preserving one.
        assertTrue("must still offer the axis-preserving grow",
                offer.contains("grow the view along its ordered axis"));
    }

    @Test
    public void offer_onOrderedAxisViewpoint_isCoherentlyPlural_whenHubIsPresent() {
        // Mirror of the hub-absent pin: with a hub there ARE two remedies, so
        // the plural intro must agree with an actually-emitted (1) and (2).
        String offer = stWithViewpoint("implementation_migration")
                .reflowOffer();
        assertTrue("must emit remedy (1)", offer.contains("(1)"));
        assertTrue("must emit remedy (2)", offer.contains("(2)"));
        assertTrue("plural intro must agree with the two emitted remedies",
                offer.contains("OFFERED next steps (each requires your "
                        + "consent), both of which preserve the existing "
                        + "element order:"));
    }

    @Test
    public void offer_onNonOrderedViewpoints_isByteIdenticalToLegacy() {
        // Zero behaviour change outside the reorder-forbidding set.
        String legacySevenArg = SpacingPreconditionInfeasibilityCertificate
                .evaluate(23, ST_AREA, ST_AVG_BOX, 60.0, 214, 68, 7)
                .reflowOffer();
        assertTrue("the retained 7-arg overload must still emit the shipped "
                        + "remedy verbatim",
                legacySevenArg.endsWith(LEGACY_REMEDY_TAIL));

        for (String vp : new String[] {null, "", "layered", "motivation",
                "implementation_deployment", "project", "value_stream",
                "technology_usage"}) {
            assertEquals("[" + String.valueOf(vp) + "] offer must be "
                            + "byte-identical to the shipped text",
                    legacySevenArg, stWithViewpoint(vp).reflowOffer());
        }
    }

    @Test
    public void detection_isUntouchedByViewpoint() {
        // The geometric infeasibility determination is unchanged — the
        // viewpoint keys the REMEDY only, never whether we fire.
        for (String vp : new String[] {null, "implementation_migration",
                "migration", "layered"}) {
            // ST fires regardless...
            assertTrue("[" + String.valueOf(vp) + "] ST must still "
                            + "short-circuit",
                    stWithViewpoint(vp).shortCircuit());
            assertEquals(SpacingPreconditionInfeasibilityCertificate
                            .REASON_DENSITY_PRECONDITION_REFLOW_REQUIRED,
                    stWithViewpoint(vp).terminationReason());
            // ...and feasible HH must NEVER be made to fire by a viewpoint.
            Decision hh = SpacingPreconditionInfeasibilityCertificate.evaluate(
                    23, HH_AREA, HH_AVG_BOX, 161.5, null, null, null, vp);
            assertFalse("[" + String.valueOf(vp) + "] HH must not "
                    + "short-circuit", hh.shortCircuit());
            assertNull(hh.reflowOffer());
        }
    }
}
