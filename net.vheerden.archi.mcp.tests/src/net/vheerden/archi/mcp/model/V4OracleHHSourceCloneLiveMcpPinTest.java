package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Before;
import org.junit.Test;

import net.vheerden.archi.mcp.model.routing.ViewFixture;
import net.vheerden.archi.mcp.model.routing.EdgeAttachmentCalculator;

/**
 * HH ("Hub Heavy") source-clone pin test — H5 Task 14.2 (closes the
 * verification gap on pin-calibration substrate parity).
 *
 * <p><b>Substrate.</b> {@code testdata/hh-source-clone-fixture.json} —
 * captured from Archi view {@code id-a53740b929ac47b0ac16b360647f2838}
 * ("HH source clone — m4 floor spike 2026-05-13") via
 * {@code mcp__archi__get-view-contents} on 2026-05-14. Element + group
 * positions are absolute canvas coords (child relative-to-parent offsets
 * translated at capture time). Topology + geometry are structurally identical
 * to {@code v4-integration-architecture-oracle-fixture.json} — same group sizes,
 * same child positions, same connection topology — but element IDs differ
 * because the HH source clone uses the current ArchiMate model element IDs
 * whereas the V4 oracle fixture predates a model edit.
 *
 * <p><b>Why this pin exists alongside {@link V4OracleQualityRegressionTest}.</b>
 * The two pins exercise the same pure-JUnit routing pipeline on the same
 * effective geometry but with DIFFERENT element IDs. A regression where
 * routing logic accidentally became ID-coupled (e.g., obstacle filtering
 * cross-checking IDs vs the wrong table, or label-exclude set computation
 * hashing on a stale ID prefix) would fire HH but not V4 (or vice versa).
 * In Task-8 live-MCP measurement (2026-05-13) the HH source clone was the
 * substrate that surfaced the monotonicity violation H5 had to fix
 * (V_p10 6.4 → 5.8 regression under the placeholder verifier) — the pin name
 * preserves the substrate identity for traceability even though the test
 * itself runs pure-JUnit (see substrate-parity-strategy.md).
 *
 * <p><b>Calibration provenance.</b> Per the pin-calibration
 * substrate-parity discipline the floor/ceiling values are
 * anchored at the PURE-JUNIT substrate's pre-H5 empirical actuals,
 * NOT the live-MCP Task 8 measurements. The live-MCP HH actuals (HPQ=0.82,
 * M4=5, V_p10=6.4, M5=1) are a DIFFERENT substrate and DO NOT transfer to
 * pure-JUnit anchoring — the Task 7 precedent surfaced exactly this
 * substrate-parity failure mode for the V4 oracle pin. Initial first-run
 * actuals captured 2026-05-14 by Task 14.3; thresholds anchored at actuals.
 *
 * <p><b>What this pin protects.</b> Future regressions in any routing stage
 * (including H5 Tier-1 monotonicity guard failure) that push HH metrics
 * below the pre-H5 floor. Tier-1 zero-defect contract is asserted directly.
 *
 * <p><b>Rationale anchors:</b>
 * <ul>
 *   <li>The pin-calibration substrate-parity failure mode this pin's
 *       calibration guards against.</li>
 *   <li>Every routing improvement ships with a JUnit pin protecting the new
 *       threshold; HH+ST pins are the substrate-diverse pair.</li>
 *   <li>The Task 8 live-MCP measurement origin and the monotonicity violation
 *       these pins exist to permanently guard against.</li>
 * </ul>
 */
public class V4OracleHHSourceCloneLiveMcpPinTest {

    /**
     * HH source clone pure-JUnit HPQ floor. Captured 2026-05-14 first-run
     * actual = 0.819 (identical to {@link V4OracleQualityRegressionTest} V4
     * oracle pure-JUnit actual — same effective geometry). Floor 0.70 matches
     * V4OracleQualityRegressionTest{@code .HPQ_FLOOR} precedent: ~12 percentage
     * points of headroom for legitimate corridor-diversity variance.
     * The HH+V4 pair is intentionally symmetric — HH catches element-ID-
     * coupling regressions that V4's stale-ID fixture would miss.
     */
    private static final double HH_HPQ_FLOOR = 0.70;

    /**
     * HH source clone pure-JUnit M4 ceiling. Captured 2026-05-14 first-run
     * actual = 5 (identical to V4 oracle pure-JUnit). H5 is a structural no-op
     * on this fixture per the diagnostic finding (25 of 30 routed
     * paths are size-3 L-shapes with terminal-incident segments outside H5
     * scope) — same root cause as V4 oracle. Pin functions as a regression
     * sentinel for any upstream stage changing terminal-segment routing.
     */
    private static final int HH_M4_CEILING = 5;

    /**
     * HH source clone pure-JUnit V_p10 floor. Captured 2026-05-14 first-run
     * actual = 4.0 (identical to V4 oracle pure-JUnit). Anchored exactly at
     * actual — no buffer. Future regression of any upstream stage that
     * collapses intra-corridor parallel-V spread on the HH substrate fires
     * this pin.
     *
     * <p><b>Re-anchor 4.0&rarr;2.0 (2026-06-14).</b> Symmetric with
     * {@link V4OracleQualityRegressionTest#VP10_FLOOR} (see its Javadoc for the
     * full bisect narrative). Root cause: {@code c10bb8e} (v1.6 terminal
     * exit-then-return zigzag self-heal) — an accepted, agent-in-loop-gated trade
     * that halves parallel-V spread on the hub-and-spoke substrate while keeping
     * zigzag 0 and all other metrics healthy. The HH and V4 pins are an intentional
     * symmetric pair (HH catches element-ID-coupling V4's stale-ID fixture misses),
     * so both re-anchor together.
     */
    private static final double HH_VP10_FLOOR = 2.0;

    /**
     * HH source clone pure-JUnit M5 ceiling. Captured 2026-05-14 first-run
     * actual = 2 (identical to V4 oracle pure-JUnit). Noise headroom per
     * V4 oracle {@code M5_CEILING} stateless-fixture-parity precedent.
     *
     * <p>Anchored at the shipped terminal face margin of 4, where it is 6; at margin 1 it is 3. It
     * moves with the V4 oracle's — same substrate, same measurement, and a real movement in the
     * routed output rather than a metric artefact. See {@code
     * V4OracleQualityRegressionTest.M5_CEILING} for the margin sweep that shows margin 4 to be a
     * local worst point rather than a trend, and for why a ceiling must be narrowed again when the
     * margin moves back.
     */
    private static final int HH_M5_CEILING = 6;

    /**
     * HH source clone pure-JUnit M1 ceiling.
     *
     * <p>Measured actual at the shipped terminal face margin of 4: <b>3</b>, all three of them
     * corner-zone ends (this fixture is a source clone of the V4 oracle, so the routes and the
     * counted ends are the same; see {@code V4OracleQualityRegressionTest.M1_CEILING} and {@code
     * NonOrthogonalTerminalZoneCensusTest}). At margin 1 it is 1. The ceiling is the value in
     * force before the margin moved: it fails a regression above 5, with 2 of headroom at margin
     * 4 and 4 at margin 1, and does not need to move with the margin.
     */
    private static final int HH_M1_CEILING = 5;


    private ViewFixture fixture;
    private LayoutQualityAssessor assessor;

    @Before
    public void setUp() throws IOException {
        fixture = ViewFixture.load("testdata/hh-source-clone-fixture.json");
        assessor = new LayoutQualityAssessor();
    }

    @Test
    public void hhSourceClone_metricsDiscovery_logsActualsForCalibration() {
        LayoutAssessmentResult result = routeAndAssess();
        System.out.println("[HH] HPQ=" + result.hubPortQualityScore()
                + " M4=" + result.connectionEdgeCoincidenceCount()
                + " V_p10=" + result.vAxisParallelGapP10()
                + " M5=" + result.coincidentSegmentCount()
                + " M1=" + result.nonOrthogonalTerminalCount()
                + " R8=" + result.corridorUtilisationScore()
                + " overlap=" + result.overlapCount()
                + " PT=" + result.connectionPassThroughs().size()
                + " interior=" + result.interiorTerminationCount()
                + " zigzag=" + result.zigzagCount());
    }

    @Test
    public void hhSourceClone_hubPortQuality_atOrAboveFloor() {
        LayoutAssessmentResult result = routeAndAssess();
        double actual = result.hubPortQualityScore();
        assertTrue("HH source clone HPQ regressed below pre-H5 floor "
                + HH_HPQ_FLOOR + ": actual=" + actual
                + " (substrate: pure-JUnit on hh-source-clone-fixture.json).",
                actual >= HH_HPQ_FLOOR);
    }

    @Test
    public void hhSourceClone_connectionEdgeCoincidence_atOrBelowCeiling() {
        LayoutAssessmentResult result = routeAndAssess();
        int actual = result.connectionEdgeCoincidenceCount();
        assertTrue("HH source clone M4 (connectionEdgeCoincidence) regressed "
                + "above pre-H5 ceiling " + HH_M4_CEILING + ": actual=" + actual
                + " (substrate: pure-JUnit on hh-source-clone-fixture.json; "
                + "see feedback_pin_calibration_substrate_parity).",
                actual <= HH_M4_CEILING);
    }

    @Test
    public void hhSourceClone_vAxisParallelGapP10_atOrAboveFloor() {
        LayoutAssessmentResult result = routeAndAssess();
        Double actual = result.vAxisParallelGapP10();
        assertNotNull("HH source clone V_p10 is null — pipeline produced no "
                + "qualifying parallel V segments (catastrophic regression; "
                + "pre-H5 floor was " + HH_VP10_FLOOR + ").", actual);
        assertTrue("HH source clone V_p10 regressed below pre-H5 floor "
                + HH_VP10_FLOOR + ": actual=" + actual
                + " (substrate: pure-JUnit on hh-source-clone-fixture.json).",
                actual >= HH_VP10_FLOOR);
    }

    @Test
    public void hhSourceClone_tier1ZeroDefect_holds() {
        LayoutAssessmentResult result = routeAndAssess();
        int overlap = result.overlapCount();
        int passThrough = result.connectionPassThroughs().size();
        int interior = result.interiorTerminationCount();
        int zigzag = result.zigzagCount();
        assertEquals("HH source clone Tier-1 overlap regressed above 0: "
                + overlap, 0, overlap);
        assertEquals("HH source clone Tier-1 passThrough regressed above 0: "
                + passThrough, 0, passThrough);
        assertEquals("HH source clone Tier-1 interior-termination regressed "
                + "above 0: " + interior, 0, interior);
        assertEquals("HH source clone Tier-1 zigzag regressed above 0: "
                + zigzag, 0, zigzag);
    }

    @Test
    public void hhSourceClone_allMetrics_combinedPinGate() {
        LayoutAssessmentResult result = routeAndAssess();
        double hpq = result.hubPortQualityScore();
        int m4 = result.connectionEdgeCoincidenceCount();
        Double vp10 = result.vAxisParallelGapP10();
        int m5 = result.coincidentSegmentCount();
        int m1 = result.nonOrthogonalTerminalCount();
        int overlap = result.overlapCount();
        int passThrough = result.connectionPassThroughs().size();
        int interior = result.interiorTerminationCount();
        int zigzag = result.zigzagCount();

        boolean structuralOk = hpq >= HH_HPQ_FLOOR
                && m4 <= HH_M4_CEILING
                && vp10 != null && vp10 >= HH_VP10_FLOOR
                && m5 <= HH_M5_CEILING
                && m1 <= HH_M1_CEILING;
        boolean tier1Ok = overlap == 0 && passThrough == 0
                && interior == 0 && zigzag == 0;

        assertTrue("HH source clone combined pin gate breached: "
                + "HPQ=" + hpq + " (floor " + HH_HPQ_FLOOR + "), "
                + "M4=" + m4 + " (ceiling " + HH_M4_CEILING + "), "
                + "V_p10=" + vp10 + " (floor " + HH_VP10_FLOOR + "), "
                + "M5=" + m5 + " (ceiling " + HH_M5_CEILING + "), "
                + "M1=" + m1 + " (ceiling " + HH_M1_CEILING + "), "
                + "Tier-1 [overlap=" + overlap + ", PT=" + passThrough
                + ", interior=" + interior + ", zigzag=" + zigzag + "].",
                structuralOk && tier1Ok);
    }

    // ---- helpers ----

    private LayoutAssessmentResult routeAndAssess() {
        OracleFixtureRouting.Inputs in = OracleFixtureRouting.of(fixture, "V4OracleHHSourceCloneLiveMcpPinTest").route();
        return assessor.assess(in.nodes(), in.connections(), true /*includeViolatorIds*/);
    }

    /**
     * The gates in this class are calibrated against a specific value of {@code
     * EdgeAttachmentCalculator.TERMINAL_FACE_MARGIN}, and every one of them is a one-sided bound.
     * That combination is silently dangerous: a ceiling anchored for a wider margin still passes at
     * every narrower one, so moving the margin down leaves these gates green while they guard
     * nothing. It is not hypothetical — the margin went 4 to 1 and back during this story, and a
     * mutation run confirmed all of them passed at 1 while HH_M5_CEILING (6) and HH_M1_CEILING (24)
     * were sitting far above the corpus actuals.
     *
     * <p>A Javadoc instruction to re-anchor them is not a mechanism. This is: change the margin and
     * this test reds, naming what has to move with it. It asserts nothing about layout quality and
     * is not a duplicate of the margin's own value pins in {@code EdgeAttachmentCalculatorTest} —
     * those exist so the margin cannot change unnoticed, this one exists so it cannot change
     * without the gates in THIS file being re-measured.
     */
    @Test
    public void gatesInThisClassAreCalibratedForTheShippedTerminalFaceMargin() {
        assertEquals("HH_M5_CEILING (6) in this class was measured at a terminal face margin of 4. "
                        + "The margin has changed, so it is no longer anchored to anything "
                        + "measured: re-run the corpus at the new margin and re-anchor it in the "
                        + "same commit that moved it.",
                4, EdgeAttachmentCalculator.TERMINAL_FACE_MARGIN);
    }
}
