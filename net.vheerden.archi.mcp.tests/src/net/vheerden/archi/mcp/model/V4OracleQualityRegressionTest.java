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
 * V4 Integration Architecture Oracle quality regression test
 * (4th of 4 in the v1.4 recovery sequence — closes the v1.4 release gate).
 *
 * <p>Pins six thresholds capturing the v1.4 routing-quality contract by
 * re-routing the V4 oracle fixture on each test run via the routing pipeline
 * (stages 4.7h applyOffsets + 4.7p+1 applyOffsets + 4.7q
 * applyTerminalAnchoredReconciliation + 4.7r final interior-BP safety net
 * + 4.7o+1 H5 HubPerimeterRoutingStage):
 *
 * <ol>
 *   <li>{@code hubPortQualityScore} &ge; {@link #HPQ_FLOOR}
 *       (current actual: 0.78; v1.3 catastrophic baseline: 0.18).</li>
 *   <li>{@code coincidentSegmentCount} &le; {@link #M5_CEILING}
 *       (current actual: 2; manual oracle: 1; v1.3: 1).</li>
 *   <li>{@code nonOrthogonalTerminalCount} &le; {@link #M1_CEILING}
 *       (current actual: 3, all corner-zone ends Archi draws diagonal).</li>
 *   <li>{@code corridorUtilisationScore} &ge; {@link #R8_FLOOR}
 *       (current actual: 0.30 after the divisor-7 cap relaxation; pre-fix
 *       baseline: 0.135). Added 2026-05-03.</li>
 *   <li>{@code connectionEdgeCoincidenceCount} (M4) &le; {@link #M4_CEILING}
 *       — H5 hub-perimeter-routing-stage Pin 1. Added 2026-05-13
 *       PM late; threshold anchored 2026-05-13 PM late per the diagnostic
 *       (M4_CEILING = 5 = V4-oracle-JUnit pre-H5 empirical actual; see
 *       {@link #M4_CEILING} Javadoc).</li>
 *   <li>{@code vAxisParallelGapP10} (V_p10) &ge; {@link #VP10_FLOOR}
 *       — H5 hub-perimeter-routing-stage Pin 2. Added 2026-05-13
 *       PM late; threshold anchored 2026-05-13 PM late per the diagnostic
 *       (VP10_FLOOR = 4.0 = V4-oracle-JUnit pre-H5 empirical actual; see
 *       {@link #VP10_FLOOR} Javadoc).</li>
 * </ol>
 *
 * <p>The combined release-gate method has been renamed from
 * {@code v4OracleAllFourThresholds_pinReleaseGate} to
 * {@code v4OracleAllSixThresholds_pinReleaseGate} and now
 * additionally asserts the Tier-1 zero-defect contract (overlap=0,
 * passThrough=0, interior=0, zigzag=0).
 *
 * <p><b>Calibration amendment (2026-05-13 PM late triage).</b>
 * The original gate set M4_CEILING=3 and VP10_FLOOR=9.0 as conservative
 * algorithmic-improvement targets based on the implicit assumption that V4
 * oracle pure-JUnit pipeline-routed M4/V_p10 ≤ HH source clone live-MCP
 * M4/V_p10. That assumption was never tested before the pin was written.
 * A diagnostic spike on the V4 oracle pure-JUnit substrate (RoutingPipeline.java
 * stage 4.7o+1 disabled vs enabled — pre/post-H5 comparison) revealed
 * byte-identical metric values across both states: H5 is a complete no-op on
 * this fixture because 25 of 30 routed paths are size-3 L-shapes whose
 * terminal-incident segments are outside H5's operative scope per the
 * {@code preservesEndpoints} guard. The conservative targets were aspirational
 * and not achievable from H5's scope alone on this measurement substrate.
 * Constants were re-anchored to the no-regression floor (M4_CEILING=5,
 * VP10_FLOOR=4.0 = empirically measured V4-oracle-JUnit pre-H5 actuals); pins
 * function as regression sentinels + a Tier-1 monotonicity guarantee.
 *
 * <p>The "lock the door behind us" mechanism the V4 falsification chain
 * never had — wins were repeatedly lost
 * because nothing protected them. The contract is satisfied implicitly via
 * metric-shape (Strategy A): if a future refactor silently disables stage 4.7q,
 * M5 jumps to ~12 (the pre-Approach-3 baseline) and the assertion fires loudly.
 *
 * <p><b>Predecessor work closed:</b>
 * <ul>
 *   <li>M1 visible-segment-length guard (closed 2026-04-27) — the original
 *       {@link #M1_CEILING} = 5 anchor. M1 has since moved to the stub Archi
 *       draws; see the constant's Javadoc for the current actual.</li>
 *   <li>Coincident-regression bisect (closed 2026-04-28) — named commit
 *       0db3d91 (Mode B perimeter-terminal guard) as primary responsible
 *       commit.</li>
 *   <li>Coincident-regression surgical fix (closed 2026-04-28) — Approach 3
 *       stage 4.7q reconciler; the floor calibration anchor for
 *       {@link #HPQ_FLOOR}, {@link #M5_CEILING}, {@link #M1_CEILING}.</li>
 *   <li>Hub-port-quality regression test (this — closes the v1.4 four-stage
 *       recovery sequence).</li>
 *   <li>Wide-corridor-utilization regression test (added 2026-05-03) — pins R8
 *       corridor-utilisation as the 4th release-gate metric. The combined
 *       {@link #v4OracleAllSixThresholds_pinReleaseGate} method below is
 *       amended in place.</li>
 * </ul>
 *
 * <p><b>Rationale anchors:</b>
 * <ul>
 *   <li>The canonical "ship metric + regression test together" pattern; this
 *       test is that pattern's reference implementation for the v1.4 release
 *       gate.</li>
 *   <li>The release-gate framing; v1.4 ships when routing is consistently
 *       visually better than v1.3, NOT under a date forcing function.</li>
 *   <li>R6 coincident, R7 hub-port allocation tiers (the perceptual basis for
 *       the threshold choices).</li>
 *   <li>The silent-failure protocol; raw JUnit splits required at every test
 *       run (do NOT trust Eclipse-MCP {@code get_console_output} which returns
 *       empty for JUnit launches).</li>
 * </ul>
 */
public class V4OracleQualityRegressionTest {

    /**
     * Hub-port-quality floor (v1.4 release-gate commitment).
     * v1.3 baseline 0.18 (catastrophic — 1 face slot for 7 connections on the
     * Event Streaming Platform hub). Manual oracle ceiling 0.87. Approach-3
     * post-fix 0.78. Floor 0.70 gives 8 percentage points of headroom for
     * legitimate corridor-diversity variance while staying well above
     * v1.3 — protects the 4.3x improvement.
     */
    private static final double HPQ_FLOOR = 0.70;

    /**
     * Coincident-segment ceiling (Approach 3 stage 4.7q anchor +
     * 2026-04-29 JUnit-MCP parity calibration).
     *
     * <p>Approach-3 closure live-MCP actual: 2 (architectural caveats —
     * two INTERIOR + one cross-cluster anchor pair, sibling scope). Manual
     * oracle: 1. v1.3: 1.
     *
     * <p><b>Why 3 not 2 (amended 2026-04-29 with user sign-off):</b>
     * Live-MCP {@code auto-route-connections} produces M5 = 2 on the V4
     * oracle. This pure-JUnit re-routing of the same fixture topology
     * with byte-identical pipeline configuration (perimeterMargin=50,
     * snapThreshold=20, enableChannelNudging=true, group/obstacle split,
     * per-connection groupBoundaries + ancestor/child exclusions +
     * labelExcludeSets) reliably produces M5 = 3. The +1 delta is a
     * stateless-fixture artefact: EMF iteration order of connections /
     * AssessmentNodes (which seeds {@code buildConnectionRoutingOrder}
     * tiebreakers) is not reproducible from a JSON fixture. The protection
     * the test provides is preserved — pre-Approach-3 baseline was M5 = 12,
     * any future regression above this ceiling fires this assertion. Tightening to 2
     * would make the test red on day one without protecting against any
     * actual routing regression.
     *
     * <p><strong>This ceiling is a function of {@code
     * EdgeAttachmentCalculator.TERMINAL_FACE_MARGIN} and is anchored at the shipped margin of 4,
     * where it is 6; at margin 1 it is 3.</strong> Unlike the M1 ceiling below this is a REAL
     * movement in the routed output — the assessor's model is not implicated. Measured across
     * margins 1, 2, 3, 4, 6 and 8 the count runs 3, 3, 4, 6, 3, 3, so margin 4 is a local worst
     * point on this corpus rather than a trend. Widening the margin further is not available as a
     * remedy — 8 breaks the strict {@code
     * TERMINAL_FACE_MARGIN < LayoutQualityAssessor.OFF_FACE_MIN_STUB_PX} tripwire that keeps the
     * off-face detector from declaring complete coverage while reporting nothing.
     *
     * <p><strong>Move this in the same commit as the margin, in BOTH directions.</strong> A ceiling
     * widened for a larger margin still passes at every smaller one, so a revert leaves it slack
     * with the whole suite green and nothing reporting that the sentinel has stopped guarding
     * anything. That happened: the margin went 4 to 1 and back, and this ceiling had to be restored
     * by hand both times because no test could see it.
     *
     * <p>The two coincident segments are worth an investigation of their own; this ceiling records
     * where the corpus actually sits and keeps the sentinel live above it.
     */
    private static final int M5_CEILING = 6;

    /**
     * Non-orthogonal-terminal ceiling. Manual oracle: 1 (the geometry-forced #12
     * APIM&rarr;CorpBank diagonal).
     *
     * <p>Measured actual at the shipped terminal face margin of 4: <b>3</b>. M1 tests the stub Archi
     * draws — its {@code OrthogonalAnchor} projects a terminal bendpoint that lies within a face's
     * extent onto that face, so the stub is perpendicular whatever the element centre's offset —
     * and all three counted ends are corner-zone ends, where the stub is diagonal under the
     * assessor's anchor model ({@code id-a65887a0…} target 30.8&deg;/66 px, {@code id-123d02ef…}
     * target 28.7&deg;/15 px, {@code id-b250a38f…} source 27.3&deg;/11 px). That model anchors a
     * corner-zone end on a ray from the element centre, where Archi anchors a plain rectangle at
     * the corner itself, so the angles are the model's, not a measurement of the drawn canvas.
     * {@code NonOrthogonalTerminalZoneCensusTest} classifies every end on this fixture and
     * re-measures the figure.
     *
     * <p>The ceiling is the value in force before the margin moved to 4. It fails a regression
     * above 5, which leaves 2 of headroom over the actual of 3; at margin 1 the actual is 1
     * (the router puts the last two of those ends in a face zone there), so the headroom there
     * is 4. The count no longer depends on the margin's relation to {@code
     * LayoutQualityAssessor.VISIBLE_DIAGONAL_MIN_PX} — the earlier centre-to-bendpoint model read 24
     * here, 28 of its 31 ends being face-zone stubs Archi draws perpendicular — so this ceiling is
     * no longer one of the gates the margin pin below guards.
     */
    private static final int M1_CEILING = 5;

    /**
     * R8 corridor-utilisation floor (divisor-7-fix closure 2026-04-30 anchor + JUnit-MCP
     * parity buffer). Post-divisor-7-fix actual: 0.304. Floor 0.25 = actual
     * minus 0.05 stateless-fixture parity buffer (per precedent) minus 0.005
     * corridor-diversity tiebreaker non-determinism. See
     * {@link V4OracleCorridorUtilisationRegressionTest} for the dedicated R8 pin and
     * its threshold derivation; this constant is duplicated here only for the
     * combined {@link #v4OracleAllFourThresholds_pinReleaseGate} assertion.
     */
    private static final double R8_FLOOR = 0.25;

    /**
     * M4 (connectionEdgeCoincidence) ceiling — H5 hub-perimeter-routing-stage
     * Pin 1. Anchored at V4-oracle-JUnit-pure-re-routing
     * pre-H5 empirical actual = 5 per the diagnostic-spike adjudication
     * 2026-05-13 PM late.
     *
     * <p><b>Calibration history.</b> The original gate set this constant
     * to 3 (conservative target) based on the assumption that V4 oracle pure-
     * JUnit pipeline-routed M4 would be ≤ HH source clone live-MCP M4 = 5
     * (Task-3 re-anchor). That assumption was {@em never empirically tested}
     * before the pin was written — the baseline measured HH source
     * clone live-MCP, NOT V4 oracle pure-JUnit re-routing. The
     * triage 2026-05-13 PM late surfaced this as a NEW failure mode worth
     * remembering: pin-calibration substrate parity —
     * pin thresholds MUST be anchored on pre-implementation measurement of
     * the EXACT substrate the pin will run on, not an implicit "B ≤ A"
     * assumption between substrates.
     *
     * <p><b>Diagnostic finding.</b> H5 stage 4.7o+1 is byte-identical no-op on
     * V4 oracle pure-JUnit re-routing: 25 of 30 routed paths are size-3
     * L-shapes whose both segments are terminal-incident. The partitioner
     * correctly skips these per the {@code preservesEndpoints} exemption (Task
     * 5 architectural fix); 0 cell members → H5 has zero operable segments on
     * this fixture. The M4 violators ARE the terminal-incident segments,
     * owned by {@code EdgeAttachmentCalculator} + {@code TerminalAnchoring}
     * (the spike's H2 refutation forbids touching). V4 manual gold's
     * M4=1 is achieved by manual terminal-bp movement — a human capability
     * H5 cannot acquire without violating atomic-swap discipline.
     *
     * <p><b>What this pin protects.</b> Future regressions that move V4
     * oracle pure-JUnit M4 above 5 (e.g., a routing change that introduces
     * NEW terminal-incident edge coincidences). Tier-1 zero-defect contract
     * in {@code v4OracleAllSixThresholds_pinReleaseGate} provides the
     * complementary "no defect introduction" guarantee. The v1.4 release gate
     * is the agent-in-loop strict-PASS > 0/18, NOT this pin — this pin is a
     * regression sentinel, not an algorithmic-improvement assertion.
     *
     * <p><b>Axis-3 update (2026-05-16).</b>
     * The {@code TerminalSegmentCorridorMigrator} (Axis-3) is now
     * composed into {@code HubPerimeterRoutingStage} at pipeline stage 4.7o+1 — it
     * DOES reach the terminal-incident size-3 L-shapes H5 excludes. Measured on
     * THIS V4-oracle pure-JUnit substrate with Axis-3 active (Task-5 measurement,
     * 2026-05-16): M4 = <b>5</b> (byte-identical to the pre-Axis-3 anchor) and
     * V_p10 = 4.0 (see {@link #VP10_FLOOR}). Axis-3 is therefore <em>also</em>
     * byte-neutral on this fixture: after the full pipeline routes V4, its
     * remaining 5 M4 contributors are not SAFE size-3 terminal-incident
     * corridor-migration candidates (or the monotonicity guard rolls back any
     * that would regress V_p10/HPQ). Per CLAUDE.md "no lying" + the
     * pin-calibration substrate-parity discipline + the H5 {@code 3→5} lesson,
     * this ceiling is therefore <b>NOT</b> re-raised toward the
     * conservative target (3) — there is no measured V4-substrate uplift to
     * honestly claim. Axis-3's uplift target is the size-3-dominated HH/ST GATE
     * substrate, measured by the Task-7 structural-only + Task-8 agent-in-loop
     * paired-arc empiricals (the ship-gate),
     * NOT this pure-JUnit pin. The pin stays a regression sentinel.
     *
     * <p><b>best-of-K update (2026-05-16).</b> The best-of-K
     * multi-start outer wrapper ({@code BestOfKRoutingStrategy}) is a
     * <em>production-composition</em> lever wired ONLY at
     * {@code ArchiModelAccessorImpl} — it is deliberately NOT active in this
     * pure-JUnit pin substrate (which routes a single deterministic
     * {@code pipeline.routeAllConnections} by design, as a pipeline regression
     * sentinel). MEASURED on this exact V4 substrate (Task-5 probe
     * {@code BestOfKRoutingStrategyV4FixtureTest.task5_measure_...}, K=12,
     * 2026-05-16): single-run M4=<b>5</b> vs emitted best-of-K M4=<b>6</b>
     * (V_p10 4.0&rarr;3.0, but HPQ 0.82&rarr;0.90 + coincSeg 2&rarr;0; rating
     * tied {@code fair} so the never-worse aggregate objective correctly trades
     * M4/V_p10 for the higher HPQ/coincSeg composite). best-of-K therefore
     * delivers <b>no M4 uplift on this substrate</b> — per CLAUDE.md "no lying"
     * + the pin-calibration substrate-parity discipline + the Axis-3 {@code 3&rarr;5}
     * lesson, this ceiling is <b>NOT</b> re-raised (there is no honest
     * V4-substrate M4 improvement to claim; switching the pin to route via
     * best-of-K would regress it 5&rarr;6 and is explicitly out of scope —
     * best-of-K's uplift target is the GATE substrate via the production
     * composition, measured by the Task-7 structural + Task-8 agent-in-loop
     * paired-arc, the ship-gate).
     * The pin stays a single-run regression sentinel.
     */
    private static final int M4_CEILING = 5;

    /**
     * V_p10 (vAxisParallelGapP10) floor — H5 hub-perimeter-routing-stage
     * Pin 2. Anchored at V4-oracle-JUnit-pure-re-routing
     * pre-H5 empirical actual = 4.0 per the diagnostic-spike adjudication
     * 2026-05-13 PM late.
     *
     * <p>The original gate set this constant to 9.0 (conservative target)
     * based on the assumption that V4 oracle pure-JUnit pipeline-routed V_p10
     * would be ≥ HH source clone live-MCP V_p10 = 6.4 (Task-3 re-anchor) AND
     * that the H5 Axis 2 spread enforcer would close the gap toward V4 manual
     * gold V_p10 = 13.30. Both assumptions were untested on V4 oracle pure-
     * JUnit re-routing before the pin was written — see {@link #M4_CEILING}
     * Javadoc for the diagnostic-spike narrative and the pin-calibration
     * substrate-parity discipline.
     *
     * <p>The existing 13.30 ± 0.5 fixture pin in
     * {@link ParallelConnectionGapMetricTest} (line 206) remains UNCHANGED:
     * "measurement substrate not algorithmic target"
     * — that is the perception-calibration anchor on V4 manual gold (human-
     * routed view), NOT the algorithmic floor on V4 oracle pipeline-routed.
     * This constant is the algorithmic no-regression floor for the latter.
     *
     * <p><b>Axis-3 update (2026-05-16).</b> With Axis-3
     * ({@code TerminalSegmentCorridorMigrator}) composed at stage 4.7o+1, the
     * Task-5 measurement on this V4-oracle pure-JUnit substrate gives V_p10 =
     * <b>4.0</b> — byte-identical to this anchor. Per the
     * {@link #M4_CEILING} Axis-3 narrative, this floor is therefore NOT re-raised
     * toward the row-722 conservative target (9.0): no measured V4-substrate
     * uplift exists to honestly claim. The floor stays a regression sentinel;
     * Axis-3's uplift is proven on the GATE substrate (Task 7/8), not here.
     *
     * <p><b>best-of-K update (2026-05-16, row 762).</b> Measured on this V4
     * pure-JUnit substrate (Task-5 probe, K=12): emitted best-of-K V_p10 =
     * <b>3.0</b> vs single-run 4.0 — best-of-K's never-worse aggregate
     * objective trades V_p10 down for a higher HPQ/coincSeg composite at a
     * tied {@code fair} rating. There is therefore <b>no V_p10 uplift</b> on
     * this substrate to honestly claim; the floor is NOT re-raised and the pin
     * substrate is NOT switched to best-of-K (would regress it 4.0&rarr;3.0).
     * best-of-K is a production-composition lever whose uplift target is the
     * GATE substrate (Task 7/8 agent-in-loop ship-gate), not this single-run
     * regression sentinel. See {@link #M4_CEILING} best-of-K narrative.
     *
     * <p><b>Re-anchor 4.0&rarr;2.0 (2026-06-14).</b> The headless harness
     * surfaced this pin (it was outside the legacy 49-class AllPluginTestsRunner
     * gate, so it had never auto-run and silently drifted). Bisect of the routing
     * commits after the 2026-05-16 anchor isolated the drop to a SINGLE commit:
     * {@code c10bb8e} "fix(v1.6): auto-route self-heals exit-then-return terminal
     * zigzag". The {@code PathStraightener.protectTerminals} guard lets
     * interior reversal collapses commit that previously forced a full rollback;
     * on the hub-and-spoke V4 fixture this straightens terminal-incident paths and
     * halves intra-corridor parallel-V spread (V_p10 4.0&rarr;2.0). The drop is an
     * ACCEPTED trade, not a regression: the change eliminates a real exit-then-return
     * zigzag defect (zigzag stays 0), every other V4 metric is healthy (HPQ=0.861, M4=5,
     * M5=3, M1=1, R8=0.469, Tier-1 all 0), and it shipped in v1.6 under a passing
     * agent-in-loop gate (zero confirmed bugs) — the real ship-gate.
     * V_p10 was already far below the V4 manual gold 13.30; this is a regression
     * sentinel re-anchored to the new no-regression floor.
     */
    private static final double VP10_FLOOR = 2.0;

    private ViewFixture fixture;
    private LayoutQualityAssessor assessor;

    @Before
    public void setUp() throws IOException {
        fixture = ViewFixture.load("testdata/v4-integration-architecture-oracle-fixture.json");
        assessor = new LayoutQualityAssessor();
    }

    @Test
    public void v4OracleHubPortQuality_atOrAboveReleaseGateFloor() {
        LayoutAssessmentResult result = routeAndAssess();
        double actual = result.hubPortQualityScore();
        assertTrue("V4 oracle hub-port-quality regressed below v1.4 release-gate "
                + "floor " + HPQ_FLOOR + ": actual=" + actual
                + " (Story #3 closure 2026-04-28 floor was 0.78 — investigate "
                + "B70-a Mode B guard or stage 4.7q reconciler integrity)",
                actual >= HPQ_FLOOR);
    }

    @Test
    public void v4OracleCoincidentSegments_atOrBelowApproach3Ceiling() {
        LayoutAssessmentResult result = routeAndAssess();
        int actual = result.coincidentSegmentCount();
        assertTrue("V4 oracle coincident-segment count regressed above Story #3 "
                + "Approach 3 ceiling " + M5_CEILING + ": actual=" + actual
                + " (Story #3 closure 2026-04-28 actual was 2 — investigate "
                + "stage 4.7q applyTerminalAnchoredReconciliation or B70-a Mode B rollback)",
                actual <= M5_CEILING);
    }

    @Test
    public void v4OracleNonOrthogonalTerminals_atOrBelowStoryOriginalCeiling() {
        LayoutAssessmentResult result = routeAndAssess();
        int actual = result.nonOrthogonalTerminalCount();
        assertTrue("V4 oracle non-orthogonal-terminal count regressed above "
                + "ceiling " + M1_CEILING + ": actual=" + actual
                + " (measured actual is 3, all corner-zone ends — run "
                + "NonOrthogonalTerminalZoneCensusTest to see which ends are counted and why)",
                actual <= M1_CEILING);
    }

    /**
     * H5 Pin 1 — M4 (connectionEdgeCoincidence) regression
     * sentinel anchored at V4-oracle-JUnit pre-H5 empirical actual = 5.
     *
     * <p>Failure indicates a regression that pushed M4 above the no-regression
     * floor (e.g., a routing-pipeline change introducing NEW terminal-incident
     * edge coincidences). NOT an algorithmic-improvement assertion — per the
     * diagnostic-spike finding (2026-05-13 PM late, see
     * {@link #M4_CEILING} Javadoc), H5 is structurally a no-op on this
     * fixture (25 of 30 routed paths are size-3 L-shapes; terminal-incident
     * segments outside H5's operative scope). Investigation
     * starting points: terminal-segment routing changes (EdgeAttachmentCalculator,
     * TerminalAnchoring), the Axis-3 {@code TerminalSegmentCorridorMigrator}
     * (Axis-3, composed at 4.7o+1 2026-05-16 — measured byte-neutral on this V4
     * substrate; see {@link #M4_CEILING} Axis-3 narrative), or upstream stages
     * that introduce new face-hugging before stage 4.7o+1.
     */
    @Test
    public void v4OracleConnectionEdgeCoincidence_atOrBelowHubPerimeterCeiling() {
        LayoutAssessmentResult result = routeAndAssess();
        int actual = result.connectionEdgeCoincidenceCount();
        assertTrue("V4 oracle connectionEdgeCoincidence (M4) regressed above the hub-perimeter "
                + "no-regression floor " + M4_CEILING
                + ": actual=" + actual + " (V4-oracle-JUnit pre-H5 empirical "
                + "anchor; per the diagnostic of 2026-05-13 PM late H5 is a "
                + "structural no-op on this fixture — terminal-incident segments "
                + "are outside H5 scope. Investigate terminal-segment routing "
                + "changes: EdgeAttachmentCalculator, TerminalAnchoring, or "
                + "upstream stages introducing new face-hugging before stage 4.7o+1).",
                actual <= M4_CEILING);
    }

    /**
     * H5 Pin 2 — V_p10 (vAxisParallelGapP10) regression sentinel.
     * Originally anchored at the V4-oracle-JUnit pre-H5 empirical actual = 4.0;
     * re-anchored 2026-06-14 to 2.0 (see {@link #VP10_FLOOR} Javadoc —
     * {@code c10bb8e}).
     *
     * <p>Failure indicates a regression that pushed V_p10 below the no-regression
     * floor (e.g., a routing-pipeline change that collapses intra-corridor
     * spread on V4 oracle). NOT an algorithmic-improvement assertion — per the
     * diagnostic-spike finding (2026-05-13 PM late, see
     * {@link #VP10_FLOOR} Javadoc), H5 is structurally a no-op on this fixture.
     * The V4 manual gold V_p10 = 13.30 perception-anchor is pinned separately
     * in {@link ParallelConnectionGapMetricTest} line 206 (unchanged).
     *
     * <p>{@code vAxisParallelGapP10()} returns a boxed {@link Double} that is
     * {@code null} when no qualifying parallel V segments exist (per
     * {@link ParallelConnectionGapMetricTest} fixture: empty connections /
     * non-overlapping parallel V — see line 580). A null value here would
     * mean the V4 oracle produced no parallel V structure (catastrophic), so
     * the null-guard is preserved.
     */
    @Test
    public void v4OracleVAxisParallelGapP10_atOrAboveHubPerimeterFloor() {
        LayoutAssessmentResult result = routeAndAssess();
        Double actual = result.vAxisParallelGapP10();
        assertNotNull("V4 oracle vAxisParallelGapP10 (V_p10) is null — pipeline "
                + "produced no qualifying parallel V segments on the V4 oracle "
                + "fixture (catastrophic regression; pre-H5 empirical actual = "
                + VP10_FLOOR + "). Investigate upstream routing changes that "
                + "may have eliminated all parallel V structure.", actual);
        assertTrue("V4 oracle V_p10 regressed below no-regression floor "
                + VP10_FLOOR + ": actual=" + actual + " (V4-oracle-JUnit pre-H5 "
                + "empirical anchor; V4 manual gold = 13.30 ± 0.5 pinned "
                + "separately in ParallelConnectionGapMetricTest line 206. Per "
                + "the diagnostic of 2026-05-13 PM late H5 is a structural "
                + "no-op on this fixture — investigate upstream routing changes "
                + "that collapsed intra-corridor spread before stage 4.7o+1).",
                actual >= VP10_FLOOR);
    }

    /**
     * H5 Pin 4 — combined six-threshold release gate on the V4
     * oracle fixture. Extends the prior four-threshold gate
     * ({@code v4OracleAllFourThresholds_pinReleaseGate}, renamed)
     * with M4 + V_p10 + Tier-1 zero-defect contract (overlap=0,
     * passThrough=0, interior=0, zigzag=0).
     *
     * <p><b>Calibration framing.</b> M4_CEILING=5 was anchored at the
     * V4-oracle-JUnit pre-H5 empirical actual per the diagnostic-spike
     * adjudication 2026-05-13 PM late; VP10_FLOOR was re-anchored 2026-06-14 from
     * 4.0 to 2.0 ({@code c10bb8e} — see {@link #VP10_FLOOR} Javadoc). The
     * combined release gate functions as a regression sentinel + Tier-1 monotonicity
     * guarantee — NOT an algorithmic-improvement assertion. See
     * {@link #M4_CEILING} + {@link #VP10_FLOOR} Javadocs for the full
     * diagnostic narrative and the pin-calibration substrate-parity discipline.
     *
     * <p>The Tier-1 zero-defect contract is the monotonicity guarantee:
     * the H5 stage MUST NEVER introduce overlap / passThrough / interior-
     * termination / zigzag defects — if any of these regress above 0, the
     * stage's rollback hook (HubPerimeterRoutingStage.verifyTier1Clean +
     * primitives' apply/restore API) failed to fire correctly.
     */
    @Test
    public void v4OracleAllSixThresholds_pinReleaseGate() {
        LayoutAssessmentResult result = routeAndAssess();
        double hpq = result.hubPortQualityScore();
        int m5 = result.coincidentSegmentCount();
        int m1 = result.nonOrthogonalTerminalCount();
        double r8 = result.corridorUtilisationScore();
        int m4 = result.connectionEdgeCoincidenceCount();
        Double vp10 = result.vAxisParallelGapP10();

        // Tier-1 zero-defect contract
        int overlap = result.overlapCount();
        int passThrough = result.connectionPassThroughs().size();
        int interior = result.interiorTerminationCount();
        int zigzag = result.zigzagCount();

        boolean structuralOk = hpq >= HPQ_FLOOR && m5 <= M5_CEILING
                && m1 <= M1_CEILING && r8 >= R8_FLOOR
                && m4 <= M4_CEILING
                && vp10 != null && vp10 >= VP10_FLOOR;
        boolean tier1Ok = overlap == 0 && passThrough == 0
                && interior == 0 && zigzag == 0;

        assertTrue("V4 oracle release-gate regression (H5 six-threshold pin): "
                + "HPQ=" + hpq + " (floor " + HPQ_FLOOR + "), "
                + "M5=" + m5 + " (ceiling " + M5_CEILING + "), "
                + "M1=" + m1 + " (ceiling " + M1_CEILING + "), "
                + "R8=" + r8 + " (floor " + R8_FLOOR + "), "
                + "M4=" + m4 + " (ceiling " + M4_CEILING + "), "
                + "V_p10=" + vp10 + " (floor " + VP10_FLOOR + "), "
                + "Tier-1 [overlap=" + overlap + ", passThrough=" + passThrough
                + ", interior=" + interior + ", zigzag=" + zigzag + "] "
                + "(all must be 0). One or more thresholds breached.",
                structuralOk && tier1Ok);
    }

    // ---- helpers ----

    private LayoutAssessmentResult routeAndAssess() {
        OracleFixtureRouting.Inputs in = OracleFixtureRouting.of(fixture, "V4OracleQualityRegressionTest").route();
        return assessor.assess(in.nodes(), in.connections(), true /*includeViolatorIds*/);
    }

    /**
     * The gates in this class are calibrated against a specific value of {@code
     * EdgeAttachmentCalculator.TERMINAL_FACE_MARGIN}, and every one of them is a one-sided bound.
     * That combination is silently dangerous: a ceiling anchored for a wider margin still passes at
     * every narrower one, so moving the margin down leaves these gates green while they guard
     * nothing. It is not hypothetical — the margin went 4 to 1 and back during this story, and a
     * mutation run confirmed all of them passed at 1 while M5_CEILING (6) and M1_CEILING (24)
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
        assertEquals("M5_CEILING (6) in this class was measured at a terminal face margin of 4. "
                        + "The margin has changed, so it is no longer anchored to anything "
                        + "measured: re-run the corpus at the new margin and re-anchor it in the "
                        + "same commit that moved it.",
                4, EdgeAttachmentCalculator.TERMINAL_FACE_MARGIN);
    }
}
