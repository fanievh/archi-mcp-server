package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import org.junit.Test;

import net.vheerden.archi.mcp.model.SpacingControlLoop.DensityTerminationState;

/**
 * JUnit pin for the density-aware 3-state termination. Pure-unit (no OSGi;
 * no transitive class-loading of {@code ArchiModelAccessorImpl}).
 *
 * <p>Covers: all four 2×2 cells; the too-early back-off
 * regression case (flat-aggregate + below-regime MUST classify
 * {@code escalate}, NOT the HALT-accept); the escalation cap
 * (≤ {@code MAX_DENSITY_ESCALATION_ITERS}, large step, ~112px mid-band
 * target); the PASS-honest diagnosis content + the no-auto-reflow
 * invariant; the perf-sentinel (escalate unreachable in-regime); the
 * one-shot hub-resize (built/executed exactly once); and the
 * regime-signal-absent ⇒ byte-identical preservation invariant.</p>
 */
public class SpacingControlLoopDensityAwareTerminationTest {

    // ------------------------------------------------------------------
    // Test infrastructure
    // ------------------------------------------------------------------

    private static class FakeCmd implements SpacingMutationCommand {
        final String id;
        int executeCount = 0;
        int undoCount = 0;
        final List<String> sharedOrder;

        FakeCmd(String id, List<String> sharedOrder) {
            this.id = id;
            this.sharedOrder = sharedOrder;
        }

        @Override public void execute() { executeCount++; }
        @Override public void undo() {
            undoCount++;
            if (sharedOrder != null) { sharedOrder.add(id); }
        }
    }

    /**
     * Scripted callbacks: one canned {@link LayoutMetrics} per
     * observeLayout() call, plus an optional one-shot hub-resize command
     * (counts build invocations to pin the one-shot guarantee).
     */
    private static class ScriptedCb implements SpacingControlLoop.Callbacks {
        private final List<LayoutMetrics> script;
        private int idx = 0;
        int buildSpacingCount = 0;
        int buildHubResizeCount = 0;
        final FakeCmd hubResize;
        private final Predicate<LayoutMetrics> goal;

        ScriptedCb(List<LayoutMetrics> script, FakeCmd hubResize,
                Predicate<LayoutMetrics> goal) {
            this.script = script;
            this.hubResize = hubResize;
            this.goal = goal;
        }

        static ScriptedCb of(LayoutMetrics... script) {
            return new ScriptedCb(new ArrayList<>(Arrays.asList(script)),
                    /*hubResize=*/ null, m -> false);
        }

        ScriptedCb withHubResize(FakeCmd hr) {
            return new ScriptedCb(this.script, hr, this.goal);
        }

        @Override
        public SpacingMutationCommand buildMutationCommand(int proposedDeltaPx) {
            buildSpacingCount++;
            return new FakeCmd("s" + buildSpacingCount, null);
        }

        @Override
        public LayoutMetrics observeLayout() {
            if (idx >= script.size()) {
                // Steady-state: keep returning the last scripted snapshot
                // (lets long escalate/budget runs terminate deterministically).
                return script.get(script.size() - 1);
            }
            return script.get(idx++);
        }

        @Override
        public boolean isGoalReached(LayoutMetrics snapshot) {
            return goal.test(snapshot);
        }

        @Override
        public SpacingMutationCommand buildHubResizeCommand() {
            buildHubResizeCount++;
            return hubResize;
        }
    }

    /** 8-arg metrics WITH avgSpacing (regime signal present). */
    private static LayoutMetrics m(int thresholdsMet, double avgSpacingPx) {
        return new LayoutMetrics(thresholdsMet, /*hpq=*/ 0.5, /*m4=*/ 8,
                /*coincSeg=*/ 4, /*boundaryViolations=*/ 0, /*vp10=*/ 5.0,
                /*edgeCrossings=*/ 100, avgSpacingPx);
    }

    /** 7-arg metrics — avgSpacing = NaN (regime signal ABSENT). */
    private static LayoutMetrics mNoRegime(int thresholdsMet) {
        return new LayoutMetrics(thresholdsMet, 0.5, 8, 4, 0, 5.0, 100);
    }

    private static final HubExtent UNDERSIZED_HUB =
            new HubExtent(/*conns=*/ 8, /*w=*/ 214, /*h=*/ 68);   // ST-like
    private static final HubExtent ADEQUATE_HUB =
            new HubExtent(/*conns=*/ 8, /*w=*/ 320, /*h=*/ 260);  // HH-like

    // ==================================================================
    // (A) discriminator — RE-DERIVED for the 3-input signature
    //     (decouple ESCALATE from the stall window).
    //     `classifyDensityTermination(aggregateStalled, belowRegime,
    //      escalationBudgetRemaining)` — all 2×2×2 = 8 cells. The 3
    //     pre-existing 2-arg pins (`classify_climbing_anyRegime_isContinue`
    //     / `classify_stalledBelowRegime_isEscalate` /
    //     `classify_stalledInRegime_isPassHonest`) are re-derived
    //     here to the new signature (the classifier IS in scope). The ONLY
    //     behavioural delta vs the 2-arg table is the
    //     NEW `!stalled & below & budget → ESCALATE` cell (the decoupling);
    //     every other cell is preserved exactly (`DENSITY_TREND_WINDOW`
    //     stays the PASS_HONEST trigger ONLY).
    // ==================================================================

    /** `!stalled & !below` → CONTINUE (in-regime climbing), budget-axis
     *  irrelevant. Re-derived from `classify_climbing_anyRegime_isContinue`
     *  (the `below=false` half). */
    @Test
    public void classify_climbing_inRegime_anyBudget_isContinue() {
        assertEquals(DensityTerminationState.CONTINUE,
                SpacingControlLoop.classifyDensityTermination(
                        /*stalled=*/ false, /*below=*/ false,
                        /*budget=*/ true));
        assertEquals(DensityTerminationState.CONTINUE,
                SpacingControlLoop.classifyDensityTermination(
                        /*stalled=*/ false, /*below=*/ false,
                        /*budget=*/ false));
    }

    /** THE DECOUPLING (NEW cell): `!stalled & below & budget` → ESCALATE.
     *  This is the lever — escalate fires on the
     *  many-small-gains-but-below-measured-regime trajectory BEFORE the
     *  2-step stall window trips. Was CONTINUE under the 2-arg table (the
     *  `classify_climbing_anyRegime_isContinue` `below=true` half) — that
     *  is the prior FAIL root cause #1. */
    @Test
    public void classify_climbing_belowRegime_withBudget_isEscalate_DECOUPLED() {
        assertEquals(DensityTerminationState.ESCALATE,
                SpacingControlLoop.classifyDensityTermination(
                        /*stalled=*/ false, /*below=*/ true,
                        /*budget=*/ true));
    }

    /** No-livelock guard: `!stalled & below & !budget` → CONTINUE (no
     *  escalation budget ⇒ cannot escalate ⇒ fall through to the preserved
     *  terminal; must NOT spin). Preserved from the 2-arg
     *  `!stalled→CONTINUE`. */
    @Test
    public void classify_climbing_belowRegime_noBudget_isContinue_noLivelock() {
        assertEquals(DensityTerminationState.CONTINUE,
                SpacingControlLoop.classifyDensityTermination(
                        /*stalled=*/ false, /*below=*/ true,
                        /*budget=*/ false));
    }

    /** Preserved: `stalled & below` → ESCALATE, budget-axis irrelevant (the
     *  `:933` post-state guard handles the no-budget cap-out — classify
     *  returned ESCALATE here under the 2-arg table too). Re-derived from
     *  `classify_stalledBelowRegime_isEscalate`. */
    @Test
    public void classify_stalledBelowRegime_anyBudget_isEscalate() {
        assertEquals(DensityTerminationState.ESCALATE,
                SpacingControlLoop.classifyDensityTermination(
                        /*stalled=*/ true, /*below=*/ true,
                        /*budget=*/ true));
        assertEquals(DensityTerminationState.ESCALATE,
                SpacingControlLoop.classifyDensityTermination(
                        /*stalled=*/ true, /*below=*/ true,
                        /*budget=*/ false));
    }

    /** Preserved: `stalled & !below` → PASS_HONEST, budget-axis irrelevant
     *  (`DENSITY_TREND_WINDOW` stays the PASS_HONEST trigger ONLY).
     *  Re-derived from `classify_stalledInRegime_isPassHonest`. */
    @Test
    public void classify_stalledInRegime_anyBudget_isPassHonest() {
        assertEquals(DensityTerminationState.PASS_HONEST,
                SpacingControlLoop.classifyDensityTermination(
                        /*stalled=*/ true, /*below=*/ false,
                        /*budget=*/ true));
        assertEquals(DensityTerminationState.PASS_HONEST,
                SpacingControlLoop.classifyDensityTermination(
                        /*stalled=*/ true, /*below=*/ false,
                        /*budget=*/ false));
    }

    // ==================================================================
    // (B) Regime / hub / signal-availability axis helpers
    // ==================================================================

    @Test
    public void belowRegime_avgSpacingBelow100_true() {
        assertTrue(SpacingControlLoop.belowRegime(m(2, 60.0), null));
    }

    @Test
    public void belowRegime_avgSpacingAtOrAbove100_andHubOk_false() {
        assertFalse(SpacingControlLoop.belowRegime(m(2, 100.0), ADEQUATE_HUB));
        assertFalse(SpacingControlLoop.belowRegime(m(2, 124.0), null));
    }

    @Test
    public void belowRegime_inSpacingButUnderSizedHub_true() {
        // avgSpacing in-band but the hub is under-sized for its fan-out.
        assertTrue(SpacingControlLoop.belowRegime(m(2, 120.0),
                UNDERSIZED_HUB));
    }

    @Test
    public void hubUnderSized_largeFanOutSmallBounds_true() {
        assertTrue(SpacingControlLoop.hubUnderSizedForFanOut(UNDERSIZED_HUB));
    }

    @Test
    public void hubUnderSized_smallFanOut_false() {
        // ≤ DENSITY_HUB_FANOUT_CONN_THRESHOLD connections → not "large".
        assertFalse(SpacingControlLoop.hubUnderSizedForFanOut(
                new HubExtent(/*conns=*/ 6, /*w=*/ 100, /*h=*/ 50)));
    }

    @Test
    public void hubUnderSized_adequateBounds_false() {
        assertFalse(SpacingControlLoop.hubUnderSizedForFanOut(ADEQUATE_HUB));
    }

    @Test
    public void hubUnderSized_nullHub_false() {
        assertFalse(SpacingControlLoop.hubUnderSizedForFanOut(null));
    }

    @Test
    public void regimeSignal_absentWhenNaNAvgAndNullHub() {
        assertFalse(SpacingControlLoop.regimeSignalAvailable(
                mNoRegime(2), null));
    }

    @Test
    public void regimeSignal_presentWhenAvgKnown_orHubKnown() {
        assertTrue(SpacingControlLoop.regimeSignalAvailable(
                m(2, 60.0), null));
        assertTrue(SpacingControlLoop.regimeSignalAvailable(
                mNoRegime(2), UNDERSIZED_HUB));
    }

    // ==================================================================
    // (C) THE too-early back-off regression case
    //     Flat aggregate + below-regime: previously HALTed at iter 0 with
    //     aggregate_threshold_regressed...; now it MUST escalate, NOT halt.
    // ==================================================================

    @Test
    public void tooEarlyBackOff_flatAggregateBelowRegime_escalatesNotHalts() {
        // Every observation: aggregate flat at 2, avgSpacing 60 (< 100).
        SpacingControlLoop.Request req = new SpacingControlLoop.Request(
                /*initialSpacingPx=*/ 40, /*targetSpacingPx=*/ 80,
                /*iterationBudget=*/ 8,
                /*perIterationStepCapPx=*/ Integer.MAX_VALUE,
                /*initialMetrics=*/ m(2, 60.0), "element",
                /*hubExtent=*/ UNDERSIZED_HUB);
        ScriptedCb cb = ScriptedCb.of(
                m(2, 60.0), m(2, 60.0), m(2, 60.0), m(2, 60.0),
                m(2, 60.0), m(2, 60.0), m(2, 60.0), m(2, 60.0));

        SpacingControlLoop.Result r = SpacingControlLoop.iterate(req, cb);

        // It must NOT be the iteration-0 back-off.
        assertFalse("must NOT row-703-halt at iteration 0 "
                + "(the too-early back-off bug)",
                r.terminationReason().equals(
                        SpacingControlLoop
                                .REASON_AGGREGATE_REGRESSED_PREFIX + "0"
                        + SpacingControlLoop.REASON_REVERTED_TO_INITIAL));
        // Escalation must have engaged (one-shot hub-resize hook polled).
        assertTrue("escalation must engage on flat+below-regime",
                cb.buildHubResizeCount >= 1);
    }

    // ==================================================================
    // (D) PASS-honest — stalled + in-regime → reflow-required diagnosis,
    //     NEVER auto-reflow, and no step that degrades the loop's own
    //     step scalar. That scalar is narrower than overallRating — a
    //     rating regression it cannot see is disclosed by the accessor
    //     after the loop returns, not prevented here.
    // ==================================================================

    @Test
    public void passHonest_stalledInRegime_emitsDiagnosis_noDegradedView() {
        // avgSpacing already in-band (112) + adequate hub → in-regime;
        // aggregate never improves → stalls after the 2-step window.
        SpacingControlLoop.Request req = new SpacingControlLoop.Request(
                40, 200, 8, Integer.MAX_VALUE,
                m(2, 112.0), "element", ADEQUATE_HUB);
        ScriptedCb cb = ScriptedCb.of(
                m(2, 112.0), m(2, 112.0), m(2, 112.0), m(2, 112.0));

        SpacingControlLoop.Result r = SpacingControlLoop.iterate(req, cb);

        assertEquals(SpacingControlLoop.REASON_DENSITY_FLOOR_REFLOW_REQUIRED,
                r.terminationReason());
        assertNotNull("actionable diagnosis must be populated",
                r.densityDiagnosis());
        assertTrue(r.densityDiagnosis().contains("REFLOW REQUIRED"));
        assertTrue("diagnosis names measured avgSpacing vs band",
                r.densityDiagnosis().contains("average spacing"));
        assertTrue("diagnosis names hub WxH vs connection count",
                r.densityDiagnosis().contains("hub"));
        assertTrue("explicit no-auto-reflow + consent statement",
                r.densityDiagnosis().contains("NOT auto-reflowed"));
        // Never a SILENTLY-DEGRADED view: no accepted
        // (non-backed-off) iteration may carry a post-state aggregate
        // BELOW the pre-loop initial aggregate (2). Aggregate-neutral
        // accepted steps are fine — "degraded" means strictly worse, and
        // the loop reverts any strictly-regressing step.
        int initialAggregate = 2;
        for (SpacingIterationStep step : r.iterations()) {
            if (!step.backedOff()) {
                assertTrue("accepted step " + step.stepIndex()
                        + " must not degrade below the initial aggregate",
                        step.postStateMetrics().thresholdsMet()
                                >= initialAggregate);
            }
        }
    }

    @Test
    public void passHonest_neverInvokesAnyReflowPath() {
        // The loop's only outward effects are buildMutationCommand /
        // buildHubResizeCommand. There is NO reflow/auto-layout callback —
        // structurally impossible to auto-reflow. Asserted by the
        // Callbacks surface: only spacing + one-shot hub-resize exist.
        SpacingControlLoop.Request req = new SpacingControlLoop.Request(
                40, 200, 5, Integer.MAX_VALUE,
                m(1, 110.0), "element", ADEQUATE_HUB);
        ScriptedCb cb = ScriptedCb.of(m(1, 110.0), m(1, 110.0), m(1, 110.0));
        SpacingControlLoop.Result r = SpacingControlLoop.iterate(req, cb);
        assertEquals(SpacingControlLoop.REASON_DENSITY_FLOOR_REFLOW_REQUIRED,
                r.terminationReason());
        // No hub-resize on PASS-honest (in-regime → escalate never ran).
        assertEquals(0, cb.buildHubResizeCount);
    }

    // ==================================================================
    // (E) escalation cap + large step + below-regime-cap safety net
    // ==================================================================

    @Test
    public void escalation_isIterationCapped_andNotPassHonestWhileBelow() {
        // Permanently flat + permanently below-regime: escalation must be
        // capped at MAX_DENSITY_ESCALATION_ITERS and, if still below-regime
        // at cap-out, must NOT claim reflow-required — safety
        // net = the PRESERVED back-off / budget terminal.
        SpacingControlLoop.Request req = new SpacingControlLoop.Request(
                40, 60, 20, Integer.MAX_VALUE,
                m(2, 55.0), "element", UNDERSIZED_HUB);
        List<LayoutMetrics> flat = new ArrayList<>();
        for (int i = 0; i < 20; i++) { flat.add(m(2, 55.0)); }
        ScriptedCb cb = new ScriptedCb(flat, null, x -> false);

        SpacingControlLoop.Result r = SpacingControlLoop.iterate(req, cb);

        assertFalse("must NOT claim reflow-required while still "
                + "below-regime (the density-regime FAIL condition)",
                SpacingControlLoop.REASON_DENSITY_FLOOR_REFLOW_REQUIRED
                        .equals(r.terminationReason()));
        assertNull("safety-net terminal carries no diagnosis",
                r.densityDiagnosis());
    }

    @Test
    public void escalation_oneShotHubResize_builtExactlyOnce() {
        FakeCmd hr = new FakeCmd("hub", null);
        SpacingControlLoop.Request req = new SpacingControlLoop.Request(
                40, 60, 12, Integer.MAX_VALUE,
                m(2, 55.0), "element", UNDERSIZED_HUB);
        List<LayoutMetrics> flat = new ArrayList<>();
        for (int i = 0; i < 12; i++) { flat.add(m(2, 55.0)); }
        ScriptedCb cb = new ScriptedCb(flat, hr, x -> false);

        SpacingControlLoop.iterate(req, cb);

        assertEquals("one-shot: hub-resize hook polled exactly once",
                1, cb.buildHubResizeCount);
        assertTrue("the supplied hub-resize command was executed once",
                hr.executeCount == 1);
    }

    // ==================================================================
    // (F) perf-sentinel — escalate is UNREACHABLE in-regime
    // ==================================================================

    @Test
    public void perfSentinel_inRegimeClimbing_neverEscalates() {
        // In-regime (avgSpacing 120, adequate hub) AND climbing aggregate
        // → CONTINUE every step; escalate must NEVER trigger (no hub-resize
        // poll, no escalate-mode), so no extra N×K cost on normal views.
        SpacingControlLoop.Request req = new SpacingControlLoop.Request(
                40, 120, 5, Integer.MAX_VALUE,
                m(0, 120.0), "element", ADEQUATE_HUB);
        ScriptedCb cb = ScriptedCb.of(
                m(1, 120.0), m(2, 120.0), m(3, 120.0), m(4, 120.0));

        SpacingControlLoop.Result r = SpacingControlLoop.iterate(req, cb);

        assertEquals("escalate must be unreachable in-regime",
                0, cb.buildHubResizeCount);
        assertFalse(SpacingControlLoop.REASON_DENSITY_FLOOR_REFLOW_REQUIRED
                .equals(r.terminationReason()));
    }

    // ==================================================================
    // (G) regime signal ABSENT ⇒ byte-identical
    // ==================================================================

    @Test
    public void regimeAbsent_firstRegression_isRow703BackOff_unchanged() {
        // 7-arg metrics (NaN avg) + null hubExtent → discriminator inert.
        // A first-iteration aggregate regression MUST produce the exact
        // preserved terminationReason (the preserved 2-state behaviour).
        SpacingControlLoop.Request req = new SpacingControlLoop.Request(
                40, 100, 5, Integer.MAX_VALUE,
                mNoRegime(3), "element"); // 6-arg → hubExtent null
        ScriptedCb cb = ScriptedCb.of(mNoRegime(1)); // regresses 3 → 1

        SpacingControlLoop.Result r = SpacingControlLoop.iterate(req, cb);

        assertEquals(
                SpacingControlLoop.REASON_AGGREGATE_REGRESSED_PREFIX + "0"
                        + SpacingControlLoop.REASON_REVERTED_TO_INITIAL,
                r.terminationReason());
        assertNull("row-703 terminals never carry a density diagnosis",
                r.densityDiagnosis());
        assertEquals(0, cb.buildHubResizeCount);
    }

    @Test
    public void regimeAbsent_climbing_acceptsToBudget_unchanged() {
        SpacingControlLoop.Request req = new SpacingControlLoop.Request(
                40, 200, 3, Integer.MAX_VALUE,
                mNoRegime(0), "element");
        ScriptedCb cb = ScriptedCb.of(
                mNoRegime(1), mNoRegime(2), mNoRegime(3));

        SpacingControlLoop.Result r = SpacingControlLoop.iterate(req, cb);

        assertTrue(r.terminationReason().startsWith(
                SpacingControlLoop.REASON_BUDGET_EXHAUSTED_PREFIX));
        assertNull(r.densityDiagnosis());
    }

    // ==================================================================
    // (F) Hub sizing —
    //     fan-out-scaled hub sub-signal calibration + the
    //     predicate↔resize-target consistency invariant.
    //     The defect: a flat exclusive-< 300×250 evaluated the
    //     HH 300×250 hub w/ 17 conns "adequate" (300<300=false) so
    //     the loop PASS-honested a view Arm-A proved clearable (HPQ 0.97).
    // ==================================================================

    /**
     * THE HH false-positive headline pin: a 300×250 hub
     * absorbing 17 connections MUST now flag under-sized for its fan-out
     * (it evaluated "adequate" under the old flat exclusive-{@code <}
     * 300×250 and the loop PASS-honested a clearable HH view).
     */
    @Test
    public void fix2_hub300x250with17conns_isUnderSized_true() {
        assertTrue("row-773 HH false-positive: 300×250/17-conn hub "
                + "must flag under-sized (was false under flat 300×250)",
                SpacingControlLoop.hubUnderSizedForFanOut(
                        new HubExtent(/*conns=*/ 17, /*w=*/ 300,
                                /*h=*/ 250)));
    }

    /**
     * A-HH-R1 calibration anchor: the fan-out-scaled minimum for 17
     * connections must be ≥ the Arm-A-proven-clearable ≈390×325 extent
     * (Arm-A resized the 300×250 hub → ≈390×325 and cleared HPQ 0.97 /
     * M4 4 / coincSeg 0). Exact formula values: 300+10×10=400, 250+8×10=330.
     */
    @Test
    public void fix2_requiredMin17conns_meetsArmAProvenClearableExtent() {
        assertEquals(400, SpacingControlLoop.requiredHubMinWidthPx(17));
        assertEquals(330, SpacingControlLoop.requiredHubMinHeightPx(17));
        assertTrue("17-conn required width ≥ A-HH-R1 proven ≈390",
                SpacingControlLoop.requiredHubMinWidthPx(17) >= 390);
        assertTrue("17-conn required height ≥ A-HH-R1 proven ≈325",
                SpacingControlLoop.requiredHubMinHeightPx(17) >= 325);
    }

    /**
     * The HH-like base 300×250 is preserved EXACTLY at the smallest "large"
     * hub ({@code DENSITY_HUB_FANOUT_CONN_THRESHOLD}+1 = 7 connections):
     * zero behaviour change at the established anchor; growth ONLY
     * for higher fan-out (excess is 0 at and below 7).
     */
    @Test
    public void fix2_baseMinimumPreservedAtSmallestLargeHub() {
        assertEquals(0, SpacingControlLoop.hubFanOutExcess(6));
        assertEquals(0, SpacingControlLoop.hubFanOutExcess(7));
        assertEquals(1, SpacingControlLoop.hubFanOutExcess(8));
        assertEquals(10, SpacingControlLoop.hubFanOutExcess(17));
        assertEquals(SpacingControlLoop.DENSITY_HUB_MIN_WIDTH_PX,
                SpacingControlLoop.requiredHubMinWidthPx(7));
        assertEquals(SpacingControlLoop.DENSITY_HUB_MIN_HEIGHT_PX,
                SpacingControlLoop.requiredHubMinHeightPx(7));
    }

    /**
     * The {@code c=8} fan-out-boundary coverage gap.
     * The fan-out-scaled curve is anchored at
     * {@code DENSITY_HUB_FANOUT_CONN_THRESHOLD+1 = 7} (base 300×250,
     * {@code hubFanOutExcess(7)==0}); {@code c=8} is the FIRST +1-connection
     * increment and was previously only covered transitively. Pin the exact
     * first-step values: {@code requiredHubMinWidthPx(8)==310},
     * {@code requiredHubMinHeightPx(8)==258} (= base + 1×per-conn:
     * 300+10×1, 250+8×1), strictly greater than the {@code c=7} base.
     */
    @Test
    public void fix2_cEquals8_fanOutBoundary_firstIncrementExact() {
        assertEquals("hubFanOutExcess(8) = max(0, 8-(6+1)) = 1",
                1, SpacingControlLoop.hubFanOutExcess(8));
        assertEquals("requiredHubMinWidthPx(8) = 300 + 10×1",
                310, SpacingControlLoop.requiredHubMinWidthPx(8));
        assertEquals("requiredHubMinHeightPx(8) = 250 + 8×1",
                258, SpacingControlLoop.requiredHubMinHeightPx(8));
        // strictly above the c=7 base anchor (the first real increment)
        assertTrue("c=8 width strictly > c=7 base",
                SpacingControlLoop.requiredHubMinWidthPx(8)
                        > SpacingControlLoop.requiredHubMinWidthPx(7));
        assertTrue("c=8 height strictly > c=7 base",
                SpacingControlLoop.requiredHubMinHeightPx(8)
                        > SpacingControlLoop.requiredHubMinHeightPx(7));
    }

    /** The fan-out-scaled minimum is monotone non-decreasing in count. */
    @Test
    public void fix2_requiredMinimumMonotoneInConnectionCount() {
        int prevW = -1;
        int prevH = -1;
        for (int c = 0; c <= 40; c++) {
            int w = SpacingControlLoop.requiredHubMinWidthPx(c);
            int h = SpacingControlLoop.requiredHubMinHeightPx(c);
            assertTrue("width monotone @c=" + c, w >= prevW);
            assertTrue("height monotone @c=" + c, h >= prevH);
            assertTrue("width never below base @c=" + c,
                    w >= SpacingControlLoop.DENSITY_HUB_MIN_WIDTH_PX);
            prevW = w;
            prevH = h;
        }
    }

    /**
     * The predicate↔resize-target CONSISTENCY invariant (the hub-sizing
     * coupling) pinned at the pure-formula level: every hub the predicate
     * flags as under-sized has its fan-out-scaled required minimum strictly
     * exceed the current extent in ≥1 dimension — so the coupled
     * {@code buildDensityHubResizeCommand} target
     * {@code max(w,reqW)×max(h,reqH)} is ALWAYS strictly larger (≠ current)
     * and never returns null. If this held only weakly the escalate
     * hub-resize would be a no-op loop on the very hub it diagnosed.
     */
    @Test
    public void fix2_flaggedHubAlwaysResizesStrictlyLarger_noNoOpLoop() {
        HubExtent[] flagged = {
            new HubExtent(17, 300, 250),  // HH false-positive
            new HubExtent(8, 214, 68),    // ST-clone-like
            new HubExtent(17, 450, 250),  // height-only under-sized
            new HubExtent(20, 300, 600),  // width-only under-sized
            new HubExtent(30, 214, 68),   // very high fan-out
        };
        for (HubExtent h : flagged) {
            assertTrue("precondition: must be flagged " + h,
                    SpacingControlLoop.hubUnderSizedForFanOut(h));
            int c = h.maxHubConnectionCount();
            int reqW = SpacingControlLoop.requiredHubMinWidthPx(c);
            int reqH = SpacingControlLoop.requiredHubMinHeightPx(c);
            int targetW = Math.max(h.hubWidthPx(), reqW);
            int targetH = Math.max(h.hubHeightPx(), reqH);
            assertTrue("resize target must be strictly larger in ≥1 "
                    + "dimension (no no-op loop) for " + h,
                    targetW > h.hubWidthPx() || targetH > h.hubHeightPx());
        }
    }

    /**
     * Regression: {@code >6}-conn small hubs still flag; genuinely-large
     * hubs (≥ the fan-out-scaled minimum for their count) still pass; {@code
     * ≤6}-conn hubs are exempt regardless of size; the fixtures keep
     * their semantics.
     */
    @Test
    public void fix2_regression_smallFlags_largeAndSmallFanOutPass() {
        // small hub, very high fan-out → still flags
        assertTrue(SpacingControlLoop.hubUnderSizedForFanOut(
                new HubExtent(20, 214, 68)));
        // genuinely-large hub for 17 conns (≥ 400×330) → still passes
        assertFalse(SpacingControlLoop.hubUnderSizedForFanOut(
                new HubExtent(17, 500, 400)));
        // exactly at the required minimum for its count → adequate
        assertFalse(SpacingControlLoop.hubUnderSizedForFanOut(
                new HubExtent(17, 400, 330)));
        // ≤6 connections → exempt no matter how tiny
        assertFalse(SpacingControlLoop.hubUnderSizedForFanOut(
                new HubExtent(6, 1, 1)));
        assertFalse(SpacingControlLoop.hubUnderSizedForFanOut(
                new HubExtent(2, 10, 10)));
        // fixtures preserved
        assertTrue(SpacingControlLoop.hubUnderSizedForFanOut(UNDERSIZED_HUB));
        assertFalse(SpacingControlLoop.hubUnderSizedForFanOut(ADEQUATE_HUB));
    }

    /**
     * {@code buildDensityDiagnosis} text stays TRUTHFUL under the
     * fan-out-scaled formula: for the HH 300×250/17-conn hub it must
     * name the fan-out-scaled minimum for THAT hub's count (≥400x330px for
     * 17 connections), NOT the stale flat "≥300x250px for >6 connections".
     */
    @Test
    public void fix2_densityDiagnosisTextTruthfulUnderFanOutFormula() {
        String d = SpacingControlLoop.buildDensityDiagnosis(
                m(2, 161.5), new HubExtent(17, 300, 250));
        assertTrue("names the fan-out-scaled minimum for 17 conns: " + d,
                d.contains("≥400x330px for 17 connections"));
        assertFalse("must NOT carry the stale flat >6-connections text: " + d,
                d.contains("for >6 connections"));
        assertTrue("still reports the actual hub extent: " + d,
                d.contains("300x250px absorbing 17 connections"));
    }

    // ==================================================================
    // (H) TERMINAL-REINTERPRETATION. At the preserved
    //     give-up terminals (§4 `!accept` / unconditional
    //     `budget_exhausted`), when regime-signal-present AND
    //     in-regime, relabel the give-up as PASS_HONEST + emit the
    //     diagnosis (the give-up IS the density-floor stall, mistimed —
    //     Given A makes it trustworthy). Gated EXACTLY like the
    //     density block ⇒ regime-signal-absent ⇒ byte-identical.
    //
    //     Code-flow note: the §4 terminating
    //     `!accept` is reachable ONLY via the ESCALATE-cap-exhausted
    //     fall-through, which by construction has belowRegime==true
    //     (ESCALATE = stalled && below); CONTINUE+!accept is consumed at
    //     the revert-continue step and PASS_HONEST returns at the 2-window
    //     path. So the ACTIVE reinterpretation (RED→GREEN) is insertion
    //     point #2 (the unconditional budget-exhaust terminal); the
    //     §4 guard is implemented per spec (both
    //     terminals, identical predicate) and is correctly dormant there —
    //     pinned below as the no-false-reflow-while-below-regime
    //     invariant. The reinterpretation evaluates `bestState`
    //     (loop-local `postState` is out of scope post-loop — `bestState`
    //     IS the loop's final accepted post-state at that terminal).
    // ==================================================================

    /**
     * RED-by-construction: regime-signal present (hub != null)
     * AND in-regime (avg 112 ≥ 100, adequate hub) AND the aggregate is
     * <em>climbing</em> (thresholdsMet strictly increases every step) so
     * the 2-step stall window NEVER accumulates (aggregateStalled stays
     * false — this is NOT the existing PASS_HONEST 2-window path) AND
     * the run consumes its whole iterationBudget → it would exit via the
     * preserved unconditional `budget_exhausted`. Post terminal-
     * reinterpretation it MUST relabel to REASON_DENSITY_FLOOR_REFLOW_
     * REQUIRED + a non-null diagnosis, with no step that degrades the step
     * scalar. RED
     * pre-fix (the reinterpretation does not exist → `budget_exhausted`).
     */
    @Test
    public void reinterpret_budgetExhaustInRegimeClimbing_isPassHonest_elementArm() {
        SpacingControlLoop.Request req = new SpacingControlLoop.Request(
                /*initialSpacingPx=*/ 40, /*targetSpacingPx=*/ 5000,
                /*iterationBudget=*/ 3,
                /*perIterationStepCapPx=*/ Integer.MAX_VALUE,
                /*initialMetrics=*/ m(0, 112.0), "composer.element",
                /*hubExtent=*/ ADEQUATE_HUB);
        // Strictly-climbing in-regime → every step gains → window resets
        // every step → aggregateStalled never true → PASS_HONEST is
        // UNreachable; the loop runs the full budget to the budget terminal.
        ScriptedCb cb = ScriptedCb.of(
                m(1, 112.0), m(2, 112.0), m(3, 112.0));

        SpacingControlLoop.Result r = SpacingControlLoop.iterate(req, cb);

        assertEquals("would-budget-exhaust in-regime MUST reinterpret as "
                + "PASS-honest (row-775 insertion #2)",
                SpacingControlLoop.REASON_DENSITY_FLOOR_REFLOW_REQUIRED,
                r.terminationReason());
        assertNotNull("actionable diagnosis must be populated",
                r.densityDiagnosis());
        assertTrue(r.densityDiagnosis().contains("REFLOW REQUIRED"));
        assertTrue("explicit no-auto-reflow + consent statement",
                r.densityDiagnosis().contains("NOT auto-reflowed"));
        // Never silently-degraded: no accepted (non-backed-off)
        // step may carry a post aggregate strictly below the pre-loop
        // initial aggregate (0 here).
        for (SpacingIterationStep step : r.iterations()) {
            if (!step.backedOff()) {
                assertTrue("accepted step " + step.stepIndex()
                        + " must not degrade below the initial aggregate",
                        step.postStateMetrics().thresholdsMet() >= 0);
            }
        }
        assertEquals("in-regime: escalate/hub-resize never engaged",
                0, cb.buildHubResizeCount);
    }

    /**
     * Arm-symmetry: byte-identical scenario with the group-arm label.
     * Both composer arms run the SAME static {@code iterate} body via the
     * shared {@code runComposerArm} — the single guarded
     * reinterpretation covers both; the arm label is informational only and
     * does NOT branch loop control-flow. Same RED→GREEN as the element-arm
     * pin (the "group-arm interaction").
     */
    @Test
    public void reinterpret_budgetExhaustInRegimeClimbing_isPassHonest_groupArm() {
        SpacingControlLoop.Request req = new SpacingControlLoop.Request(
                40, 5000, 3, Integer.MAX_VALUE,
                m(0, 118.0), "composer.group", ADEQUATE_HUB);
        ScriptedCb cb = ScriptedCb.of(
                m(1, 118.0), m(2, 118.0), m(3, 118.0));

        SpacingControlLoop.Result r = SpacingControlLoop.iterate(req, cb);

        assertEquals("group arm: same iterate body ⇒ same reinterpretation",
                SpacingControlLoop.REASON_DENSITY_FLOOR_REFLOW_REQUIRED,
                r.terminationReason());
        assertNotNull(r.densityDiagnosis());
        assertTrue(r.densityDiagnosis().contains("REFLOW REQUIRED"));
    }

    /**
     * RED-by-construction variant — the budget-terminal reinterpretation
     * also fires
     * when avgSpacing is NaN but a non-null hubExtent supplies the regime
     * signal AND the hub is adequate (not under-sized for its fan-out ⇒
     * !belowRegime). Pins that the predicate uses the hub sub-signal, not
     * only avgSpacing, at the budget terminal. RED pre-fix (budget_exhausted).
     */
    @Test
    public void reinterpret_budgetExhaust_hubOnlyRegimeSignal_isPassHonest() {
        SpacingControlLoop.Request req = new SpacingControlLoop.Request(
                40, 5000, 2, Integer.MAX_VALUE,
                mNoRegime(0), "composer.element", ADEQUATE_HUB);
        ScriptedCb cb = ScriptedCb.of(mNoRegime(1), mNoRegime(2));

        SpacingControlLoop.Result r = SpacingControlLoop.iterate(req, cb);

        assertEquals(SpacingControlLoop.REASON_DENSITY_FLOOR_REFLOW_REQUIRED,
                r.terminationReason());
        assertNotNull(r.densityDiagnosis());
    }

    /**
     * Invariant @ §4 (insertion point #1): a §4-reaching run is
     * by control-flow ALWAYS below-regime (ESCALATE-cap-exhausted ⇒
     * stalled && below). The `!belowRegime` guard MUST therefore withhold
     * reinterpretation at §4 — the run stays the PRESERVED §4
     * back-off with NO density diagnosis (claiming reflow-required while
     * still below-regime is the explicit paired-arc FAIL condition).
     * GREEN pre- AND post-fix (the guarded relabel must NOT change this).
     */
    @Test
    public void reinterpret_section4ReachedBelowRegime_staysRow703_noFalseReflow() {
        // Permanently flat + permanently below-regime + under-sized hub:
        // escalation engages, caps out, then a !accept step trips §4.
        SpacingControlLoop.Request req = new SpacingControlLoop.Request(
                40, 60, 20, Integer.MAX_VALUE,
                m(2, 55.0), "composer.group", UNDERSIZED_HUB);
        List<LayoutMetrics> flat = new ArrayList<>();
        for (int i = 0; i < 20; i++) { flat.add(m(2, 55.0)); }
        ScriptedCb cb = new ScriptedCb(flat, null, x -> false);

        SpacingControlLoop.Result r = SpacingControlLoop.iterate(req, cb);

        assertFalse("MUST NOT claim reflow-required while below-regime "
                + "(the diagnosis FAIL condition) — §4 guard withholds",
                SpacingControlLoop.REASON_DENSITY_FLOOR_REFLOW_REQUIRED
                        .equals(r.terminationReason()));
        assertNull("preserved row-703 terminal carries no diagnosis",
                r.densityDiagnosis());
    }

    /**
     * Task 2.2 — the regime-signal-ABSENT ⇒ byte-identical
     * invariant AT the reinterpretation insertion points. Same
     * would-budget-exhaust climbing trajectory as the insertion #2 pin but
     * with NO regime signal (NaN avg + null hubExtent): the budget-terminal
     * guard is inert ⇒ the terminationReason is the UNCHANGED
     * `budget_exhausted_after_N_iterations`, no diagnosis. (Sibling to the
     * existing regimeAbsent_* pins; ties them to the insertion.)
     */
    @Test
    public void regimeAbsent_wouldBudgetExhaust_staysRow703BudgetExhausted() {
        SpacingControlLoop.Request req = new SpacingControlLoop.Request(
                40, 5000, 3, Integer.MAX_VALUE,
                mNoRegime(0), "element"); // 6-arg ⇒ hubExtent null
        ScriptedCb cb = ScriptedCb.of(
                mNoRegime(1), mNoRegime(2), mNoRegime(3));

        SpacingControlLoop.Result r = SpacingControlLoop.iterate(req, cb);

        assertTrue("regime-absent ⇒ unchanged row-703 budget terminal",
                r.terminationReason().startsWith(
                        SpacingControlLoop.REASON_BUDGET_EXHAUSTED_PREFIX));
        assertFalse(SpacingControlLoop.REASON_DENSITY_FLOOR_REFLOW_REQUIRED
                .equals(r.terminationReason()));
        assertNull("regime-absent terminal never carries a diagnosis",
                r.densityDiagnosis());
    }

    // ==================================================================
    // (I) DECOUPLE ESCALATE FROM THE STALL WINDOW. The
    //     prior FAIL residual: under the agent-in-loop the
    //     loop budget-exhausts BELOW-regime because ESCALATE never fires
    //     on ST's many-small-gains trajectory (aggregateStalled stays
    //     false ⇒ the 2-arg classify returned CONTINUE forever ⇒ the
    //     post-state ESCALATE was never entered; the pre-emptive
    //     escalate was bypassed because the loop budget-exhausts BEFORE
    //     the knob ladder formally exhausts). Captured probe:
    //     element `budget_exhausted_after_4_iterations`
    //     deltas [30,21,21] +72px, avg 60.0→73.8 < 100, ESCALATE never
    //     fires. The decouple lever adds `!stalled & below & budget →
    //     ESCALATE` so escalate fires on that trajectory, the one-shot
    //     hub-resize lifts measured avg into the regime, and the
    //     already-built insertion #2 finally relabels the
    //     in-regime budget-exhaust as PASS_HONEST.
    //
    //     `EscalationSensitiveCb` faithfully models the real system: the
    //     view stays BELOW-regime until the one-shot hub-resize is built
    //     (escalate fired) — only then does the best-of-K-measured
    //     avgSpacing rise into the prescribed band (mirrors the B-ST
    //     escalate-hub-resize behaviour; the divergence
    //     exposed was that escalate never fired at all). The
    //     trajectory is ALWAYS many-small-gains (thresholdsMet strictly
    //     ++ every observe) ⇒ aggregateStalled NEVER accumulates ⇒ this is
    //     NOT the 2-window PASS_HONEST path nor the existing
    //     flat-aggregate `tooEarlyBackOff` ESCALATE path.
    // ==================================================================

    /**
     * Callbacks whose observed avgSpacing is BELOW-regime until the
     * one-shot hub-resize is built (i.e. escalate fired), IN-regime
     * thereafter; thresholdsMet strictly increases every observe
     * (many-small-gains ⇒ aggregateStalled stays false). Faithful
     * pure-unit model of "escalate's hub-resize lifts measured avg into
     * the regime" — the get-to-in-regime half this story supplies.
     */
    private static class EscalationSensitiveCb
            implements SpacingControlLoop.Callbacks {
        int buildSpacingCount = 0;
        int buildHubResizeCount = 0;
        int obs = 0;
        final FakeCmd hubResize;
        final double belowAvg;
        final double inRegimeAvg;

        EscalationSensitiveCb(FakeCmd hubResize, double belowAvg,
                double inRegimeAvg) {
            this.hubResize = hubResize;
            this.belowAvg = belowAvg;
            this.inRegimeAvg = inRegimeAvg;
        }

        @Override
        public SpacingMutationCommand buildMutationCommand(
                int proposedDeltaPx) {
            buildSpacingCount++;
            return new FakeCmd("s" + buildSpacingCount, null);
        }

        @Override
        public LayoutMetrics observeLayout() {
            // Many-small-gains: thresholdsMet strictly ++ every call so
            // aggregateStalled NEVER accumulates (the probe trajectory).
            // Below-regime until the one-shot hub-resize is built (escalate
            // fired — built BEFORE observeLayout() in the
            // same iteration); in-regime thereafter.
            obs++;
            double avg = (buildHubResizeCount >= 1)
                    ? inRegimeAvg : belowAvg;
            return m(obs, avg);
        }

        @Override
        public boolean isGoalReached(LayoutMetrics snapshot) {
            return false; // neutral goal — never §6 short-circuit
        }

        @Override
        public SpacingMutationCommand buildHubResizeCommand() {
            buildHubResizeCount++;
            return hubResize;
        }
    }

    /**
     * THE RED-by-construction reproduction pin (budget-exhaust
     * trajectory = the probe's actual path; element arm). Regime signal
     * present (avg known), below-regime (avg 60 &lt; 100), many-small-gains
     * (aggregateStalled stays false), escalation budget remaining,
     * targetSpacingPx huge so the knob ladder NEVER exhausts ⇒ the run
     * would otherwise exit via the post-loop below-regime
     * `budget_exhausted` (exactly the captured probe trajectory).
     *
     * <p>RED pre-fix by construction: the 2-arg `classifyDensityTermination`
     * returns CONTINUE for `!stalled` regardless of below-regime ⇒
     * ESCALATE never entered ⇒ buildHubResizeCommand never called ⇒
     * observeLayout stays below-regime ⇒ post-loop `inRegimeWithSignal`
     * FALSE ⇒ `budget_exhausted` (NOT
     * `density_floor_reflow_required`). This is the prior
     * FAIL, reproduced in pure-unit + corroborated by the captured
     * probe.</p>
     *
     * <p>GREEN post-fix: the decoupled `!stalled & below & budget →
     * ESCALATE` rule fires ESCALATE ⇒ escalate engages ⇒ one-shot
     * hub-resize built ⇒ measured avg lifts in-regime ⇒ the already-built
     * insertion #2 relabels the in-regime budget-exhaust as
     * `REASON_DENSITY_FLOOR_REFLOW_REQUIRED` + non-null diagnosis, no step
     * that degrades the step scalar.</p>
     */
    @Test
    public void decouple_budgetExhaustBelowRegimeManyGains_reachesEscalateThenPassHonest_elementArm() {
        FakeCmd hr = new FakeCmd("hub", null);
        SpacingControlLoop.Request req = new SpacingControlLoop.Request(
                /*initialSpacingPx=*/ 40, /*targetSpacingPx=*/ 5000,
                /*iterationBudget=*/ 6,
                /*perIterationStepCapPx=*/ Integer.MAX_VALUE,
                /*initialMetrics=*/ m(0, 60.0), "composer.element",
                /*hubExtent=*/ null); // regime axis = avgSpacing only
        EscalationSensitiveCb cb = new EscalationSensitiveCb(
                hr, /*belowAvg=*/ 60.0, /*inRegimeAvg=*/ 120.0);

        SpacingControlLoop.Result r = SpacingControlLoop.iterate(req, cb);

        assertEquals("decoupled ESCALATE must reach in-regime so the "
                + "already-built row-775 insertion #2 relabels the "
                + "budget-exhaust as PASS-honest (RED pre-fix: "
                + "budget_exhausted below-regime — the row-775 Task-8 FAIL)",
                SpacingControlLoop.REASON_DENSITY_FLOOR_REFLOW_REQUIRED,
                r.terminationReason());
        assertNotNull("actionable diagnosis must be populated",
                r.densityDiagnosis());
        assertTrue(r.densityDiagnosis().contains("REFLOW REQUIRED"));
        assertTrue("explicit no-auto-reflow + consent statement",
                r.densityDiagnosis().contains("NOT auto-reflowed"));
        assertTrue("the decouple lever must have ENGAGED escalate (≥1 "
                + "one-shot hub-resize) — distinguishes from row-775's "
                + "in-regime-from-start path where it is 0",
                cb.buildHubResizeCount >= 1);
        // Never silently-degraded: no accepted (non-backed-off)
        // step may carry a post aggregate below the pre-loop initial (0).
        for (SpacingIterationStep step : r.iterations()) {
            if (!step.backedOff()) {
                assertTrue("accepted step " + step.stepIndex()
                        + " must not degrade below the initial aggregate",
                        step.postStateMetrics().thresholdsMet() >= 0);
            }
        }
    }

    /**
     * Arm-symmetry: byte-identical scenario, group-arm label. Both
     * composer arms run the SAME static `iterate` body via the shared
     * `runComposerArm` — the single
     * arm-agnostic decouple change covers both; the arm label is
     * informational only and does NOT branch loop control-flow. Same
     * RED→GREEN as the element-arm pin.
     */
    @Test
    public void decouple_budgetExhaustBelowRegimeManyGains_reachesEscalateThenPassHonest_groupArm() {
        FakeCmd hr = new FakeCmd("hub", null);
        SpacingControlLoop.Request req = new SpacingControlLoop.Request(
                40, 5000, 6, Integer.MAX_VALUE,
                m(0, 55.0), "composer.group", /*hubExtent=*/ null);
        EscalationSensitiveCb cb = new EscalationSensitiveCb(
                hr, /*belowAvg=*/ 55.0, /*inRegimeAvg=*/ 118.0);

        SpacingControlLoop.Result r = SpacingControlLoop.iterate(req, cb);

        assertEquals("group arm: same iterate body ⇒ same decouple "
                + "RED→GREEN reproduction",
                SpacingControlLoop.REASON_DENSITY_FLOOR_REFLOW_REQUIRED,
                r.terminationReason());
        assertNotNull(r.densityDiagnosis());
        assertTrue(r.densityDiagnosis().contains("REFLOW REQUIRED"));
        assertTrue("escalate engaged on the group arm too",
                cb.buildHubResizeCount >= 1);
    }

    /**
     * Escalate is reachable on the KNOB-LADDER-EXHAUST trajectory
     * too (the independent `canEscalatePastCeiling` pre-emptive
     * path), and the decouple fix PRESERVES it. targetSpacingPx is small
     * so the heuristic knob ladder formally exhausts (`!keepGoing`)
     * while below-regime with escalation budget — the pre-emptive path
     * engages escalate (one-shot hub-resize built). This holds GREEN both
     * pre- AND post-fix (the fix does not touch the pre-emptive path;
     * post-fix the decoupled classifier additionally makes escalate
     * reachable EARLIER on this trajectory).
     *
     * <p>The terminal here is the PRESERVED ladder-exhausted
     * `budget_exhausted` (byte-frozen — NOT a reinterpretation
     * insertion site), so the assertion is escalate-REACHABILITY
     * (buildHubResizeCount ≥ 1) + no false reflow-while-below-regime, NOT
     * insertion-#2; that is the "escalate reachable on BOTH
     * trajectories" guard. Covers both composer arms.</p>
     */
    @Test
    public void decouple_knobLadderExhaustBelowRegime_escalateReachable_preservedBothArms() {
        for (String arm : new String[] { "composer.element",
                "composer.group" }) {
            FakeCmd hr = new FakeCmd("hub", null);
            SpacingControlLoop.Request req = new SpacingControlLoop.Request(
                    /*initialSpacingPx=*/ 40, /*targetSpacingPx=*/ 70,
                    /*iterationBudget=*/ 12,
                    /*perIterationStepCapPx=*/ Integer.MAX_VALUE,
                    /*initialMetrics=*/ m(0, 60.0), arm,
                    /*hubExtent=*/ null);
            EscalationSensitiveCb cb = new EscalationSensitiveCb(
                    hr, /*belowAvg=*/ 60.0, /*inRegimeAvg=*/ 120.0);

            SpacingControlLoop.Result r =
                    SpacingControlLoop.iterate(req, cb);

            assertTrue(arm + ": escalate MUST be reachable on the "
                    + "knob-ladder-exhaust trajectory (:607 pre-emptive — "
                    + "preserved by the fix)",
                    cb.buildHubResizeCount >= 1);
            assertFalse(arm + ": must NEVER claim reflow-required while "
                    + "still below-regime (the master-gate and diagnosis FAIL condition)",
                    SpacingControlLoop.REASON_DENSITY_FLOOR_REFLOW_REQUIRED
                            .equals(r.terminationReason())
                            && r.densityDiagnosis() != null
                            && r.densityDiagnosis().contains("60px"));
            assertFalse(arm + ": never the row-703 iteration-0 halt",
                    r.terminationReason().equals(
                            SpacingControlLoop
                                    .REASON_AGGREGATE_REGRESSED_PREFIX + "0"
                            + SpacingControlLoop.REASON_REVERTED_TO_INITIAL));
        }
    }

    /**
     * Task 2.3 — the regime-signal-ABSENT ⇒ byte-identical
     * invariant for the decouple scenario. SAME
     * many-small-gains would-budget-exhaust trajectory as the
     * reproduction pin but with NO regime signal (NaN avg + null
     * hubExtent): the density block (and therefore the new
     * decoupled `classifyDensityTermination` rule) is unreachable ⇒ the
     * loop terminates byte-identically
     * (`budget_exhausted_after_N`, no diagnosis, NO ESCALATE / no
     * hub-resize). Proves the new ESCALATE reachability is gated by the
     * SAME `regimeSignalAvailable` master gate as the density block.
     */
    @Test
    public void decouple_regimeAbsentManyGains_staysRow703ByteIdentical_noEscalate() {
        FakeCmd hr = new FakeCmd("hub", null);
        SpacingControlLoop.Request req = new SpacingControlLoop.Request(
                40, 5000, 6, Integer.MAX_VALUE,
                mNoRegime(0), "composer.element"); // 6-arg ⇒ hubExtent null
        EscalationSensitiveCb cb = new EscalationSensitiveCb(
                hr, /*belowAvg=*/ Double.NaN, /*inRegimeAvg=*/ Double.NaN) {
            @Override
            public LayoutMetrics observeLayout() {
                obs++;
                return mNoRegime(obs); // NaN avg every observe
            }
        };

        SpacingControlLoop.Result r = SpacingControlLoop.iterate(req, cb);

        assertTrue("regime-absent ⇒ unchanged row-703 budget terminal",
                r.terminationReason().startsWith(
                        SpacingControlLoop.REASON_BUDGET_EXHAUSTED_PREFIX));
        assertFalse("the decoupled ESCALATE rule is unreachable when "
                + "regime-signal-absent (master-gate invariant)",
                SpacingControlLoop.REASON_DENSITY_FLOOR_REFLOW_REQUIRED
                        .equals(r.terminationReason()));
        assertNull("regime-absent terminal never carries a diagnosis",
                r.densityDiagnosis());
        assertEquals("regime-absent ⇒ escalate never engages (no "
                + "one-shot hub-resize)", 0, cb.buildHubResizeCount);
    }

    // ==================================================================
    // (I) ORDERED-AXIS REMEDY AT THE IN-LOOP PRODUCER.
    //
    //     The pre-loop infeasibility certificate already withholds the
    //     connectivity-reordering re-layout on a view whose element order is
    //     load-bearing. That rule was wired to ONE of the two places that
    //     emit the offer: this one — the PASS-honest density floor — had no
    //     viewpoint at all, so a view whose geometry is FEASIBLE (the
    //     certificate does not fire, the loop runs to its floor) still got
    //     the reorder offer. These pins close that half.
    //
    //     The viewpoint keys the REMEDY ONLY, never the decision: the
    //     termination classification, the regime gates and the escalate
    //     condition are pinned identical across every viewpoint value.
    // ==================================================================

    /**
     * The exact remedy tail this diagnosis shipped before it learned about
     * ordered axes — a literal, so "outside the set the text is unchanged"
     * is a byte assertion rather than a paraphrase that drifts with the
     * source.
     */
    private static final String LOOP_LEGACY_TAIL =
            "This layout was NOT auto-reflowed (a structural reflow moves "
            + "user-placed elements — an explicit-consent boundary). "
            + "OFFERED next step (requires your consent): re-layout this "
            + "view with a structural auto-layout, then re-run "
            + "auto-route-connections. The current view is preserved "
            + "unchanged (no degraded layout was applied).";

    /** The (D) PASS-honest fixture, parameterised by viewpoint. */
    private static SpacingControlLoop.Result passHonestRun(
            String viewpointType) {
        SpacingControlLoop.Request req = new SpacingControlLoop.Request(
                40, 200, 8, Integer.MAX_VALUE,
                m(2, 112.0), "element", ADEQUATE_HUB, viewpointType);
        return SpacingControlLoop.iterate(req, ScriptedCb.of(
                m(2, 112.0), m(2, 112.0), m(2, 112.0), m(2, 112.0)));
    }

    @Test
    public void passHonest_onOrderedAxisView_neverOffersAReorderingReflow() {
        for (String vp : new String[] {"implementation_migration",
                "migration"}) {
            String d = passHonestRun(vp).densityDiagnosis();
            assertNotNull(d);
            assertFalse("[" + vp + "] the in-loop offer must not send a "
                            + "roadmap through a connectivity re-layout: " + d,
                    d.contains("OFFERED next step (requires your consent): "
                            + "re-layout this view with a structural "
                            + "auto-layout"));
            assertTrue("[" + vp + "] must offer the order-preserving remedy "
                            + "instead: " + d,
                    d.contains("grow the view along its ordered axis and "
                            + "re-space the elements in their current "
                            + "sequence"));
            assertTrue("[" + vp + "] must say WHY, so the agent does not go "
                            + "and reorder anyway via another tool: " + d,
                    d.contains("makes the order in which its elements are "
                            + "placed meaningful"));
            // The shared offer SHAPE survives at this producer too.
            assertTrue("[" + vp + "] stays consent-gated: " + d,
                    d.toLowerCase().contains("consent"));
            assertTrue("[" + vp + "] still states the view is preserved: " + d,
                    d.contains("The current view is preserved unchanged "
                            + "(no degraded layout was applied)."));
            assertTrue("[" + vp + "] still names the violated precondition: "
                            + d, d.contains("REFLOW REQUIRED"));
            assertFalse("[" + vp + "] no null leakage into user-facing copy: "
                            + d, d.contains("null"));
            assertFalse("[" + vp + "] no empty parenthetical: " + d,
                    d.contains("()"));
        }
    }

    /**
     * With no hub captured there is exactly ONE remedy, so the copy must stay
     * singular. A plural intro delivering a single item reads as truncated
     * output to the agent consuming it.
     */
    @Test
    public void passHonest_onOrderedAxisView_isWellFormed_whenHubIsAbsent() {
        // hubExtent null ⇒ no hub sentence ⇒ remedy (1) has nothing to refer
        // to. Regime signal still present via the in-band avgSpacing.
        SpacingControlLoop.Request req = new SpacingControlLoop.Request(
                40, 200, 8, Integer.MAX_VALUE,
                m(2, 112.0), "element", /*hubExtent=*/ null,
                "implementation_migration");
        String d = SpacingControlLoop.iterate(req, ScriptedCb.of(
                m(2, 112.0), m(2, 112.0), m(2, 112.0), m(2, 112.0)))
                .densityDiagnosis();
        assertNotNull(d);
        assertFalse("must not dangle a (2) it never emits: " + d,
                d.contains("(2)"));
        assertFalse("must not promise 'both' remedies and deliver one: " + d,
                d.toLowerCase().contains("both"));
        assertTrue("single-remedy copy must stay singular: " + d,
                d.contains("OFFERED next step (requires your consent), which "
                        + "preserves the existing element order:"));
        assertTrue("the one remedy that survives is the order-preserving one: "
                        + d, d.contains("grow the view along its ordered "
                        + "axis"));
    }

    /**
     * Outside the reorder-forbidding set — including {@code null} and the
     * empty string — the emitted text is byte-identical to what shipped
     * before the viewpoint reached this producer.
     */
    @Test
    public void passHonest_onNonOrderedViews_isByteIdenticalToLegacy() {
        String legacySevenArg = SpacingControlLoop.buildDensityDiagnosis(
                m(2, 112.0), ADEQUATE_HUB);
        assertTrue("the retained 2-arg overload must still emit the shipped "
                        + "remedy verbatim: " + legacySevenArg,
                legacySevenArg.endsWith(LOOP_LEGACY_TAIL));

        for (String vp : new String[] {null, "", "layered", "motivation",
                "business_process_cooperation", "implementation_deployment",
                "project", "value_stream", "technology_usage",
                "service_design", "customer_journey"}) {
            assertEquals("[" + String.valueOf(vp) + "] diagnosis must be "
                            + "byte-identical to the shipped text",
                    legacySevenArg,
                    SpacingControlLoop.buildDensityDiagnosis(
                            m(2, 112.0), ADEQUATE_HUB, vp));
            assertEquals("[" + String.valueOf(vp) + "] and byte-identical "
                            + "through a real loop run too",
                    legacySevenArg, passHonestRun(vp).densityDiagnosis());
        }
    }

    /**
     * The decision surface is untouched: the same views terminate the same
     * way, with the same reason, the same iteration count and the same
     * escalation behaviour, whatever the viewpoint. Only the remedy branches.
     */
    @Test
    public void terminationClassification_isUntouchedByViewpoint() {
        SpacingControlLoop.Result baseline = passHonestRun(null);
        for (String vp : new String[] {"implementation_migration",
                "migration", "layered", "business_process_cooperation", ""}) {
            SpacingControlLoop.Result r = passHonestRun(vp);
            assertEquals("[" + vp + "] termination reason must not move",
                    baseline.terminationReason(), r.terminationReason());
            assertEquals("[" + vp + "] iteration count must not move",
                    baseline.iterations().size(), r.iterations().size());
            assertEquals("[" + vp + "] final spacing must not move",
                    baseline.finalSpacingPx(), r.finalSpacingPx());
        }

        // ...and a BELOW-regime view must not be made to reach the
        // PASS-honest floor by a viewpoint: the escalate/give-up path is
        // classified on geometry alone.
        for (String vp : new String[] {null, "implementation_migration",
                "migration", "business_process_cooperation"}) {
            FakeCmd hr = new FakeCmd("hub", null);
            SpacingControlLoop.Request req = new SpacingControlLoop.Request(
                    40, 5000, 6, Integer.MAX_VALUE,
                    mNoRegime(0), "composer.element", /*hubExtent=*/ null, vp);
            SpacingControlLoop.Result r = SpacingControlLoop.iterate(req,
                    new EscalationSensitiveCb(hr, Double.NaN, Double.NaN) {
                        @Override
                        public LayoutMetrics observeLayout() {
                            obs++;
                            return mNoRegime(obs);
                        }
                    });
            assertTrue("[" + String.valueOf(vp) + "] regime-absent terminal "
                            + "must stay the budget terminal",
                    r.terminationReason().startsWith(
                            SpacingControlLoop.REASON_BUDGET_EXHAUSTED_PREFIX));
            assertNull("[" + String.valueOf(vp) + "] and carry no diagnosis",
                    r.densityDiagnosis());
        }
    }

    // ---- escalateHubResizeRect: the one-shot escalate resize's own arithmetic ------------------
    //
    // This is the rectangle the escalate step writes to the dominant hub. It used to live inline in
    // the accessor, between a captureHubExtent call and an EMF bounds read, so nothing could execute
    // it: the only way to reach it was to drive a real view through the control loop, which needs a
    // started Eclipse application. Extracted here it is ordinary arithmetic, and the invariant it
    // carries — that a hub the PREDICATE flags as under-sized always resizes to a STRICTLY larger
    // target — is now checkable in the same class as the predicate rather than across a boundary.

    @Test
    public void escalateHubResizeRect_shouldReturnNull_whenThePredicateDoesNotFlagTheHub() {
        assertNull("no resize may be proposed for a hub the predicate considers adequate",
                SpacingControlLoop.escalateHubResizeRect(ADEQUATE_HUB, 10, 20, 400, 300));
        assertNull("nor for a fan-out below the large-hub threshold",
                SpacingControlLoop.escalateHubResizeRect(
                        new HubExtent(/*conns=*/ 6, /*w=*/ 100, /*h=*/ 50), 0, 0, 100, 50));
        assertNull("nor when there is no hub at all",
                SpacingControlLoop.escalateHubResizeRect(null, 0, 0, 100, 50));
    }

    @Test
    public void escalateHubResizeRect_shouldRaiseBothAxesToTheFanOutFloors_andKeepThePosition() {
        // 7 connections ⇒ hubFanOutExcess(7) = 0 ⇒ the floors are the bare 300 x 250 bases.
        int[] rect = SpacingControlLoop.escalateHubResizeRect(
                new HubExtent(/*conns=*/ 7, /*w=*/ 140, /*h=*/ 80), /*x=*/ 10, /*y=*/ 20, 140, 80);

        assertNotNull(rect);
        assertEquals("x is carried outright — this is a resize, not a move", 10, rect[0]);
        assertEquals(20, rect[1]);
        assertEquals(SpacingControlLoop.requiredHubMinWidthPx(7), rect[2]);
        assertEquals(SpacingControlLoop.requiredHubMinHeightPx(7), rect[3]);
        assertEquals("and those floors are 300x250 at this fan-out", 300, rect[2]);
        assertEquals(250, rect[3]);
    }

    @Test
    public void escalateHubResizeRect_shouldScaleTheFloorsWithTheFanOutExcess() {
        // 17 connections ⇒ excess 10 ⇒ 300 + 10*10 = 400 wide, 250 + 8*10 = 330 high.
        int[] rect = SpacingControlLoop.escalateHubResizeRect(
                new HubExtent(/*conns=*/ 17, /*w=*/ 140, /*h=*/ 80), 0, 0, 140, 80);

        assertNotNull(rect);
        assertEquals(400, rect[2]);
        assertEquals(330, rect[3]);
        assertEquals("the target must track the SAME connection count the predicate used",
                SpacingControlLoop.requiredHubMinWidthPx(17), rect[2]);
        assertEquals(SpacingControlLoop.requiredHubMinHeightPx(17), rect[3]);
    }

    @Test
    public void escalateHubResizeRect_shouldNeverShrinkAnAxisThatAlreadyExceedsItsFloor() {
        // Under-sized on HEIGHT only: the predicate fires, but the width must be left alone rather
        // than pulled down to the floor. A min() here instead of a max() would silently narrow a
        // hub the caller never asked to touch.
        int[] rect = SpacingControlLoop.escalateHubResizeRect(
                new HubExtent(/*conns=*/ 7, /*w=*/ 900, /*h=*/ 80), 0, 0, 900, 80);

        assertNotNull(rect);
        assertEquals("the already-wide axis is preserved, not clamped to the floor", 900, rect[2]);
        assertEquals(250, rect[3]);
    }

    @Test
    public void escalateHubResizeRect_shouldReturnNull_whenNeitherAxisWouldActuallyChange() {
        // The predicate reads the extent captured for the VIEW's dominant hub; the bounds passed in
        // are the object's own. They can disagree — a stale or differently-measured extent flags a
        // hub whose actual box is already at both floors. Proposing a no-op resize there would put
        // a command in the dispatched compound that changes nothing and a rectangle in the response
        // that reports a change that never happened.
        assertNull(SpacingControlLoop.escalateHubResizeRect(
                new HubExtent(/*conns=*/ 7, /*w=*/ 140, /*h=*/ 80), 5, 6, 300, 250));
    }

    @Test
    public void escalateHubResizeRect_shouldAlwaysGrowAtLeastOneAxis_whenItProposesAnything() {
        // The predicate-to-target consistency invariant, asserted as a property rather than at a
        // point: across the whole fan-out range and a spread of starting boxes, every rectangle this
        // returns is strictly larger on at least one axis and smaller on neither. If that ever fails
        // the escalate step can degrade into a no-op loop on the very hub it just diagnosed.
        for (int conns = 7; conns <= 30; conns++) {
            for (int w : new int[] { 40, 140, 300, 640, 1200 }) {
                for (int h : new int[] { 30, 80, 250, 400 }) {
                    HubExtent hub = new HubExtent(conns, w, h);
                    int[] rect = SpacingControlLoop.escalateHubResizeRect(hub, 7, 9, w, h);
                    if (rect == null) {
                        continue;
                    }
                    String at = "conns=" + conns + " box=" + w + "x" + h;
                    assertTrue(at + ": width must never shrink", rect[2] >= w);
                    assertTrue(at + ": height must never shrink", rect[3] >= h);
                    assertTrue(at + ": at least one axis must actually grow",
                            rect[2] > w || rect[3] > h);
                    assertEquals(at + ": position is untouched", 7, rect[0]);
                    assertEquals(at + ": position is untouched", 9, rect[1]);
                }
            }
        }
    }
}
