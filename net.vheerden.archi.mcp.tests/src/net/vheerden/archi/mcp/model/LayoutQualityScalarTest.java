package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import net.vheerden.archi.mcp.handlers.ViewPlacementHandlerTest;
import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;

/**
 * JUnit pin for {@link LayoutQualityScalar} — graded scalar (2026-05-16).
 *
 * <p>Pure-unit (no OSGi, no EMF). The single source of truth for the band
 * arithmetic is {@code LayoutQualityScalar}.</p>
 *
 * <p>Covers the band-arithmetic pins:
 * <ul>
 *   <li>band-width property: no M4/coincSeg band ≤ 2
 *       units wide (reroute jitter ±1 cannot cross a band → coarse bands are
 *       the noise filter).</li>
 *   <li>per-metric-monotonic-in-disguise FAILS:
 *       the 2026-05-13 lesson reproduced (M4↑ + HPQ↑ but coincSeg↓ →
 *       net positive scalar → genuinely aggregate, not per-metric).</li>
 *   <li>graded 0→N: the scalar perceives M4 12→5
 *       progress the old binary-at-0 aggregate could not.</li>
 *   <li>qualityScalar range [0,12]; correctnessBits binary-at-0; HPQ 0.75
 *       anchor RETAINED.</li>
 * </ul></p>
 */
public class LayoutQualityScalarTest {

    // ------------------------------------------------------------------
    // Band-width property test.
    //
    // "no M4/coincSeg band <= 2 units wide": there is NO x where the credit
    // changes at x->x+1 AND again at x+1->x+2 (that would mean a band of
    // width <= 2 sitting between the two transitions). Asserting this over
    // a generous range mechanically pins "aggregate, not per-metric-
    // monotonic in disguise" — it goes red if a future refactor narrows a
    // band so reroute jitter (+/-1) could cross it.
    // ------------------------------------------------------------------

    @Test
    public void t4_m4Bands_noBandNarrowerThanThreeUnits() {
        for (int x = 0; x <= 60; x++) {
            int c0 = LayoutQualityScalar.severityTierCreditM4(x);
            int c1 = LayoutQualityScalar.severityTierCreditM4(x + 1);
            int c2 = LayoutQualityScalar.severityTierCreditM4(x + 2);
            assertTrue(
                "M4 band <= 2 units wide at x=" + x
                    + " (credits " + c0 + "," + c1 + "," + c2 + ")",
                !(c0 != c1 && c1 != c2));
        }
    }

    @Test
    public void t4_coincSegBands_noBandNarrowerThanThreeUnits() {
        for (int x = 0; x <= 60; x++) {
            int c0 = LayoutQualityScalar.severityTierCreditCoincSeg(x);
            int c1 = LayoutQualityScalar.severityTierCreditCoincSeg(x + 1);
            int c2 = LayoutQualityScalar.severityTierCreditCoincSeg(x + 2);
            assertTrue(
                "coincSeg band <= 2 units wide at x=" + x
                    + " (credits " + c0 + "," + c1 + "," + c2 + ")",
                !(c0 != c1 && c1 != c2));
        }
    }

    @Test
    public void t4_bandBoundaryConstants_matchCreditFunctionTransitions() {
        // The public band-boundary constants ARE the credit-function
        // transition points (single source of truth for the property test).
        assertEquals(3, LayoutQualityScalar.severityTierCreditM4(
                LayoutQualityScalar.M4_BAND_BOUNDARIES[0]));
        assertEquals(2, LayoutQualityScalar.severityTierCreditM4(
                LayoutQualityScalar.M4_BAND_BOUNDARIES[0] + 1));
        assertEquals(2, LayoutQualityScalar.severityTierCreditM4(
                LayoutQualityScalar.M4_BAND_BOUNDARIES[1]));
        assertEquals(1, LayoutQualityScalar.severityTierCreditM4(
                LayoutQualityScalar.M4_BAND_BOUNDARIES[1] + 1));
        assertEquals(1, LayoutQualityScalar.severityTierCreditM4(
                LayoutQualityScalar.M4_BAND_BOUNDARIES[2]));
        assertEquals(0, LayoutQualityScalar.severityTierCreditM4(
                LayoutQualityScalar.M4_BAND_BOUNDARIES[2] + 1));

        assertEquals(3, LayoutQualityScalar.severityTierCreditCoincSeg(
                LayoutQualityScalar.COINCSEG_BAND_BOUNDARIES[0]));
        assertEquals(2, LayoutQualityScalar.severityTierCreditCoincSeg(
                LayoutQualityScalar.COINCSEG_BAND_BOUNDARIES[0] + 1));
        assertEquals(2, LayoutQualityScalar.severityTierCreditCoincSeg(
                LayoutQualityScalar.COINCSEG_BAND_BOUNDARIES[1]));
        assertEquals(1, LayoutQualityScalar.severityTierCreditCoincSeg(
                LayoutQualityScalar.COINCSEG_BAND_BOUNDARIES[1] + 1));
        // Pin the third coincSeg
        // boundary transition explicitly (was only covered by the width
        // sweep — a wrong cut-point at [2] that preserved band width would
        // have slipped through).
        assertEquals(1, LayoutQualityScalar.severityTierCreditCoincSeg(
                LayoutQualityScalar.COINCSEG_BAND_BOUNDARIES[2]));
        assertEquals(0, LayoutQualityScalar.severityTierCreditCoincSeg(
                LayoutQualityScalar.COINCSEG_BAND_BOUNDARIES[2] + 1));
    }

    // ------------------------------------------------------------------
    // HPQ — 0.75 anchor RETAINED.
    // ------------------------------------------------------------------

    @Test
    public void hpq_075AnchorRetained_andPerceptualBands() {
        assertEquals("HPQ >= 0.95 -> 3", 3,
                LayoutQualityScalar.severityTierCreditHpq(0.95));
        assertEquals("HPQ >= 0.95 -> 3 (above)", 3,
                LayoutQualityScalar.severityTierCreditHpq(1.00));
        assertEquals("HPQ >= 0.75 -> 2 (the RETAINED original anchor)", 2,
                LayoutQualityScalar.severityTierCreditHpq(0.75));
        assertEquals("HPQ just below 0.75 -> 1", 1,
                LayoutQualityScalar.severityTierCreditHpq(0.74));
        assertEquals("HPQ >= 0.5 -> 1", 1,
                LayoutQualityScalar.severityTierCreditHpq(0.50));
        assertEquals("HPQ < 0.5 -> 0", 0,
                LayoutQualityScalar.severityTierCreditHpq(0.49));
        assertEquals("HPQ 0.0 -> 0", 0,
                LayoutQualityScalar.severityTierCreditHpq(0.0));
    }

    // ------------------------------------------------------------------
    // correctnessBits — binary-at-0, range [0,3].
    // ------------------------------------------------------------------

    @Test
    public void correctnessBits_binaryAtZero_rangeZeroToThree() {
        assertEquals("all clean -> 3", 3,
                LayoutQualityScalar.correctnessBits(0, 0, 0));
        assertEquals("one defect -> 2", 2,
                LayoutQualityScalar.correctnessBits(1, 0, 0));
        assertEquals("two defects -> 1", 1,
                LayoutQualityScalar.correctnessBits(1, 2, 0));
        assertEquals("three defects -> 0", 0,
                LayoutQualityScalar.correctnessBits(5, 1, 3));
        assertEquals("magnitude irrelevant — any >0 is one lost bit", 2,
                LayoutQualityScalar.correctnessBits(0, 0, 99));
    }

    // ------------------------------------------------------------------
    // qualityScalar — range [0, MAX_QUALITY_SCALAR=12].
    // ------------------------------------------------------------------

    @Test
    public void qualityScalar_perfectState_isMaxTwelve() {
        int s = LayoutQualityScalar.qualityScalar(
                /*bv=*/ 0, /*pt=*/ 0, /*ov=*/ 0,
                /*m4=*/ 0, /*cs=*/ 0, /*hpq=*/ 1.0);
        assertEquals(LayoutQualityScalar.MAX_QUALITY_SCALAR, s);
        assertEquals(12, s);
    }

    @Test
    public void qualityScalar_worstState_isZero() {
        int s = LayoutQualityScalar.qualityScalar(
                /*bv=*/ 3, /*pt=*/ 4, /*ov=*/ 5,
                /*m4=*/ 18, /*cs=*/ 16, /*hpq=*/ 0.18);
        assertEquals(0, s);
    }

    @Test
    public void qualityScalar_alwaysWithinRange() {
        for (int m4 = 0; m4 <= 30; m4 += 3) {
            for (int cs = 0; cs <= 30; cs += 3) {
                for (int d = 0; d <= 3; d++) {
                    double hpq = d * 0.3;
                    int s = LayoutQualityScalar.qualityScalar(
                            d, d, d, m4, cs, hpq);
                    assertTrue("scalar >= 0 (m4=" + m4 + " cs=" + cs + ")",
                            s >= 0);
                    assertTrue("scalar <= 12 (m4=" + m4 + " cs=" + cs + ")",
                            s <= LayoutQualityScalar.MAX_QUALITY_SCALAR);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Adversarial attack — "per-metric-monotonic in disguise?"
    //
    // The canonical 2026-05-13 lesson: a step that improves M4 + HPQ
    // into PASS but regresses coincSeg must be classified ACCEPT by the
    // AGGREGATE rule (overall improvement) even though a per-metric rule
    // would reject it (coincSeg got worse). It FAILS because qualityScalar
    // SUMS the dimensions — it never compares them individually.
    // ------------------------------------------------------------------

    @Test
    public void quinnA1_arm6Lesson_m4UpHpqUpCoincSegDown_isNetAccept() {
        // pre-step: post-hub-resize, dense. M4=12 (band 0), coincSeg=3
        // (band 3), HPQ=0.50 (band 1), no correctness defects.
        int pre = LayoutQualityScalar.qualityScalar(
                /*bv=*/ 0, /*pt=*/ 0, /*ov=*/ 0,
                /*m4=*/ 12, /*cs=*/ 3, /*hpq=*/ 0.50);
        // post-step: M4 12->5 (band 0->2, +2), HPQ 0.50->0.78 (band 1->2,
        // +1), coincSeg 3->9 (band 3->1, -2). Per-metric rule would STOP
        // (coincSeg regressed). Aggregate net = +2 +1 -2 = +1 > 0.
        int post = LayoutQualityScalar.qualityScalar(
                /*bv=*/ 0, /*pt=*/ 0, /*ov=*/ 0,
                /*m4=*/ 5, /*cs=*/ 9, /*hpq=*/ 0.78);
        assertTrue(
            "Arm-6 lesson: aggregate must see this as improvement "
                + "(pre=" + pre + " post=" + post + ") — "
                + "SpacingControlLoop.acceptStepDecision ACCEPTs on "
                + "post.thresholdsMet >= best.thresholdsMet",
            post > pre);
    }

    @Test
    public void quinnA1_genuinelyAggregate_notComponentwise() {
        // A step that worsens TWO dimensions but improves one enough to net
        // positive must still score net positive — proves the scalar is the
        // SUM, never a per-dimension veto.
        int pre = LayoutQualityScalar.qualityScalar(
                0, 0, 0, /*m4=*/ 10, /*cs=*/ 9, /*hpq=*/ 0.50);
        int post = LayoutQualityScalar.qualityScalar(
                0, 0, 0, /*m4=*/ 2, /*cs=*/ 14, /*hpq=*/ 0.49);
        // M4 10->2: band 0->3 (+3). coincSeg 9->14: band 1->0 (-1).
        // HPQ 0.50->0.49: band 1->0 (-1). Net = +3 -1 -1 = +1.
        assertTrue("net aggregate positive despite two regressions",
                post > pre);
    }

    // ------------------------------------------------------------------
    // Graded 0->N fix. The graded scalar PERCEIVES M4 12->5
    // progress the OLD binary-at-0 aggregate could not (M4==0 bit was dead
    // weight on dense views — both 12 and 5 scored the bit as 0).
    // ------------------------------------------------------------------

    @Test
    public void t6_gradedScalar_perceivesM4ProgressOldBinaryAtZeroCouldNot() {
        // Old binary-at-0 contribution from M4: (M4==0)?1:0. On dense views
        // M4 stays 3-18 so the old M4 bit is 0 at BOTH 12 and 5 (flat —
        // the bug). The graded credit is NOT flat.
        int oldM4BitAt12 = (12 == 0) ? 1 : 0;
        int oldM4BitAt5 = (5 == 0) ? 1 : 0;
        assertEquals("old binary-at-0 M4 bit was FLAT 12 vs 5 (the bug)",
                oldM4BitAt12, oldM4BitAt5);

        int gradedAt12 = LayoutQualityScalar.severityTierCreditM4(12);
        int gradedAt5 = LayoutQualityScalar.severityTierCreditM4(5);
        assertTrue("graded scalar PERCEIVES M4 12->5 progress (fix)",
                gradedAt5 > gradedAt12);
    }

    @Test
    public void t6_gradedScalar_monotoneAlongRealisticImprovementTrajectory() {
        // Realistic post-hub-resize -> agent-improved trajectory (empirical
        // magnitudes). The scalar must increase monotonically
        // as the view genuinely improves — the loop can now climb it.
        int sBad = LayoutQualityScalar.qualityScalar(
                0, 0, 0, /*m4=*/ 16, /*cs=*/ 11, /*hpq=*/ 0.18); // ST source
        int sMid = LayoutQualityScalar.qualityScalar(
                0, 0, 0, /*m4=*/ 9, /*cs=*/ 6, /*hpq=*/ 0.60);
        int sGood = LayoutQualityScalar.qualityScalar(
                0, 0, 0, /*m4=*/ 5, /*cs=*/ 3, /*hpq=*/ 0.86); // ST passing
        assertTrue("monotone bad->mid (" + sBad + "->" + sMid + ")",
                sMid > sBad);
        assertTrue("monotone mid->good (" + sMid + "->" + sGood + ")",
                sGood > sMid);
    }

    // ------------------------------------------------------------------
    // The quantity the correctness bit is taken over.
    //
    // The bit stays binary-at-zero — only the number it is taken over moves.
    // connectionPassThroughs is a capped, cross-plus-self DESCRIPTION list;
    // crossElementPassThroughCount is the uncapped cross-element tally the
    // rating charges. The ten-entry cap is inert against a binary-at-zero
    // read (truncating fifteen to ten never turns a non-zero into a zero),
    // but the self-element half is not: a view whose pass-throughs are all
    // self-element has a non-empty list and a charged count of zero, and the
    // step scalar it feeds is compared FIRST by the spacing loop's back-off
    // predicate, so that one bit decides the step outright.
    //
    // There is deliberately NO past-the-cap case here, unlike the counting
    // readers. A cap truncates, it never empties, so a fixture holding a
    // capped list beside a charged tally above ten reads non-zero under both
    // quantities and the bit is the same either way. Such a pin would be green
    // under the very mutation it would exist to catch — the defect class these
    // pins were written to close — so the under-count direction is covered at
    // the three counting sites and asserted here only where it can discriminate.
    // ------------------------------------------------------------------

    @Test
    public void toLayoutMetrics_shouldKeepTheCorrectnessBit_whenEveryPassThroughIsSelfElement() {
        AssessLayoutResultDto clean = passThroughFixture(List.of(), 0);
        AssessLayoutResultDto selfOnly = passThroughFixture(
                List.of("Connection 'c-0' routes through its own target element 'e-0'",
                        "Connection 'c-1' routes through its own source element 'e-1'",
                        "Connection 'c-2' routes through its own target element 'e-2'"),
                0);

        assertEquals("a view carrying only unrated pass-throughs must not forfeit the bit — no "
                        + "spacing lever can remove one, so the step is rejected for a defect the "
                        + "loop could never have avoided",
                LayoutQualityScalar.toLayoutMetrics(clean).thresholdsMet(),
                LayoutQualityScalar.toLayoutMetrics(selfOnly).thresholdsMet());
    }

    @Test
    public void toLayoutMetrics_shouldDropTheCorrectnessBit_whenTheChargedCountIsNonZero() {
        // The other direction, and the shape that proves which of the two the read is sourced
        // from: no description list at all beside a charged tally that is not zero.
        AssessLayoutResultDto clean = passThroughFixture(List.of(), 0);
        AssessLayoutResultDto charged = passThroughFixture(null, 4);

        assertEquals("a charged crossing costs the bit however the description list was built",
                LayoutQualityScalar.toLayoutMetrics(clean).thresholdsMet() - 1,
                LayoutQualityScalar.toLayoutMetrics(charged).thresholdsMet());
    }

    /**
     * A defect-free assessment carrying exactly the two pass-through components, set apart from
     * each other. Rebuilt through the widest constructor: every back-compat form defaults the
     * charged tally to zero, so a fixture that does not go through the widest form is green
     * without ever reaching the value it claims to test.
     */
    private static AssessLayoutResultDto passThroughFixture(
            List<String> descriptions, int charged) {
        AssessLayoutResultDto base = new AssessLayoutResultDto(
                "v-1", 5, 3,
                0, 0, 0, 0.0,
                50.0, 80, "good", Map.of(),
                List.of(), List.of(), List.of(), List.of(),
                0, List.of(), 0, List.of(),
                0, List.of(), false, 0, 0, null,
                0, List.of(), 0, List.of(),
                0, List.of(), null, List.of(),
                0, List.of(), 0, List.of(),
                0, List.of(), 1.0, List.of(),
                "good", "good",
                1.0, List.of());
        return ViewPlacementHandlerTest.withComponent(
                ViewPlacementHandlerTest.withComponent(
                        base, "connectionPassThroughs", descriptions),
                "crossElementPassThroughCount", charged);
    }
}
