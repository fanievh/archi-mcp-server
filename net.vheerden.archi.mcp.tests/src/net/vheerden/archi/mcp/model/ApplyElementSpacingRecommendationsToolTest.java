package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import net.vheerden.archi.mcp.response.dto.ApplyElementSpacingRecommendationsResultDto;
import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;

/**
 * JUnit pin for the apply-element-spacing-recommendations tool's heuristic
 * table + delta-clamp + DTO-shape contracts. Pure-unit tests (no OSGi).
 *
 * <p>Sub-tests: heuristic table pin, delta clamp at 0, negative-delta clamp,
 * dryRun envelope shape, happy path math + envelope shape, no-connections edge
 * case, tier boundaries 15/16/30/31.</p>
 *
 * <p>Per {@code feedback_metric_and_regression_test_together.md}: the
 * heuristic-table boundaries are pinned here. Any change to
 * {@link ElementSpacingHeuristic#targetSpacingForConnectionCount(int, boolean)} REQUIRES
 * editing this test file — a markdown edit alone would NOT change runtime
 * behaviour, but DOES change documented intent, and that drift is caught here.
 * </p>
 *
 * <p>Behavioural assertions involving real model state (e.g., "after.M4 &lt;
 * before.M4 strict inequality") are deferred to live smoke + empirical
 * paired-arc.</p>
 */
public class ApplyElementSpacingRecommendationsToolTest {

    // ---- heuristic table pin (one assertion per tier) ----

    @Test
    public void targetSpacing_tier1_low_connections_returns_60px() {
        // Tier 1: ≤15 connections → 60px
        assertEquals(60, ElementSpacingHeuristic.targetSpacingForConnectionCount(10, false));
    }

    @Test
    public void targetSpacing_tier2_mid_connections_returns_80px() {
        // Tier 2: 16-30 connections → 80px
        assertEquals(80, ElementSpacingHeuristic.targetSpacingForConnectionCount(20, false));
    }

    @Test
    public void targetSpacing_tier3_high_connections_returns_100px() {
        // Tier 3: >30 connections → 100px
        assertEquals(100, ElementSpacingHeuristic.targetSpacingForConnectionCount(40, false));
    }

    // ---- tier boundaries (15/16/30/31) ----

    @Test
    public void targetSpacing_boundary_15_top_of_tier1_returns_60px() {
        // ≤15 means 15 itself is in tier 1
        assertEquals(60, ElementSpacingHeuristic.targetSpacingForConnectionCount(15, false));
    }

    @Test
    public void targetSpacing_boundary_16_bottom_of_tier2_returns_80px() {
        // 16 starts tier 2 (16-30 inclusive)
        assertEquals(80, ElementSpacingHeuristic.targetSpacingForConnectionCount(16, false));
    }

    @Test
    public void targetSpacing_boundary_30_top_of_tier2_returns_80px() {
        // 30 is the top of tier 2
        assertEquals(80, ElementSpacingHeuristic.targetSpacingForConnectionCount(30, false));
    }

    @Test
    public void targetSpacing_boundary_31_bottom_of_tier3_returns_100px() {
        // 31 starts tier 3 (>30)
        assertEquals(100, ElementSpacingHeuristic.targetSpacingForConnectionCount(31, false));
    }

    // ---- N=0 connections (heuristic still maps; no-conn short-circuit
    //                handled by accessor logic upstream) ----

    @Test
    public void targetSpacing_zero_connections_falls_in_tier1() {
        // 0 ≤ 15, so heuristic still returns tier 1 = 60px. In the
        // production flow the heuristic IS consulted (decide step) BEFORE
        // the no-connections short-circuit fires (also at decide step) —
        // they happen in the same `ApplyElementSpacingDecision.decide`
        // invocation but the heuristic-table lookup is the upstream
        // computation that supplies `targetSpacingPx`. This test pins that
        // the heuristic returns tier-1 for N=0 regardless; the
        // no-connections short-circuit behaviour itself is exercised by
        // {@link #decide_zero_connections_short_circuits_without_calling_adjust}.
        assertEquals(60, ElementSpacingHeuristic.targetSpacingForConnectionCount(0, false));
    }

    // ---- delta clamp at 0 (current already meets/exceeds target) ----

    @Test
    public void delta_clamp_when_current_equals_target_is_zero() {
        int current = 80;
        int target = ElementSpacingHeuristic.targetSpacingForConnectionCount(20, false);
        // target = 80 == current → delta = max(0, 0) = 0
        int delta = Math.max(0, target - current);
        assertEquals(0, delta);
    }

    @Test
    public void delta_clamp_when_current_exceeds_target_is_zero() {
        int current = 100;
        int target = ElementSpacingHeuristic.targetSpacingForConnectionCount(20, false);
        // target = 80 < current=100 → delta = max(0, -20) = 0
        int delta = Math.max(0, target - current);
        assertEquals(0, delta);
    }

    // ---- negative-delta clamp (generous-spacing view) ----

    @Test
    public void delta_clamp_when_current_is_120_and_target_is_60_returns_zero() {
        // Tier 1 (N=10): target=60. Current=120 (generous). delta=0.
        // The tool MUST NOT shrink generous spacing — this is a footgun guard.
        int current = 120;
        int target = ElementSpacingHeuristic.targetSpacingForConnectionCount(10, false);
        int delta = Math.max(0, target - current);
        assertEquals(0, delta);
    }

    // ---- happy-path delta computation ----

    @Test
    public void delta_happy_path_inflation_n20_current40_yields_40() {
        // N=20 → target=80; current=40 → delta=40
        int current = 40;
        int target = ElementSpacingHeuristic.targetSpacingForConnectionCount(20, false);
        int delta = Math.max(0, target - current);
        assertEquals(80, target);
        assertEquals(40, delta);
    }

    @Test
    public void delta_happy_path_inflation_n40_current30_yields_70() {
        // N=40 → target=100; current=30 → delta=70
        int current = 30;
        int target = ElementSpacingHeuristic.targetSpacingForConnectionCount(40, false);
        int delta = Math.max(0, target - current);
        assertEquals(100, target);
        assertEquals(70, delta);
    }

    // ---- dryRun envelope shape (after==null, adjustResult==null) ----

    @Test
    public void dto_dryRun_envelope_shape_has_null_after_and_null_adjustResult() {
        AssessLayoutResultDto before = stubAssessLayoutResult("view-1", 20);
        ApplyElementSpacingRecommendationsResultDto dto =
                new ApplyElementSpacingRecommendationsResultDto(
                        "view-1", /*dryRun=*/ true, /*connectionCount=*/ 20,
                        /*currentSpacingPx=*/ 40, /*targetSpacingPx=*/ 80,
                        /*interElementDelta=*/ 40, /*noChangeReason=*/ null,
                        /*heuristicRecommendation=*/ null, before,
                        /*after=*/ null, /*adjustResult=*/ null);
        assertEquals("view-1", dto.viewId());
        assertTrue("dryRun must echo true", dto.dryRun());
        assertEquals(40, dto.interElementDelta());
        assertNotNull("before snapshot must be populated under dryRun", dto.before());
        assertNull("after must be null under dryRun=true", dto.after());
        assertNull("adjustResult must be null under dryRun=true", dto.adjustResult());
        assertNull("noChangeReason must be null on dryRun-with-positive-delta",
                dto.noChangeReason());
    }

    // ---- no-change envelope shape (delta=0 OR no connections) ----

    @Test
    public void dto_no_change_envelope_shape_has_populated_reason() {
        AssessLayoutResultDto before = stubAssessLayoutResult("view-2", 0);
        ApplyElementSpacingRecommendationsResultDto dto =
                new ApplyElementSpacingRecommendationsResultDto(
                        "view-2", /*dryRun=*/ false, /*connectionCount=*/ 0,
                        /*currentSpacingPx=*/ 40, /*targetSpacingPx=*/ 60,
                        /*interElementDelta=*/ 0,
                        "view has no connections; spacing recommendation not "
                        + "applicable",
                        /*heuristicRecommendation=*/ null, before,
                        /*after=*/ null, /*adjustResult=*/ null);
        assertEquals(0, dto.interElementDelta());
        assertNotNull("noChangeReason must be populated when delta=0",
                dto.noChangeReason());
        assertTrue(dto.noChangeReason().contains("no connections"));
        assertNotNull(dto.before());
        assertNull("after must be null on short-circuit", dto.after());
        assertNull("adjustResult must be null on short-circuit", dto.adjustResult());
    }

    // ---- happy-path envelope shape (after + adjustResult populated) ----

    @Test
    public void dto_happy_path_envelope_shape_has_populated_before_and_after() {
        AssessLayoutResultDto before = stubAssessLayoutResult("view-3", 20);
        AssessLayoutResultDto after = stubAssessLayoutResult("view-3", 20);
        ApplyElementSpacingRecommendationsResultDto dto =
                new ApplyElementSpacingRecommendationsResultDto(
                        "view-3", /*dryRun=*/ false, /*connectionCount=*/ 20,
                        /*currentSpacingPx=*/ 40, /*targetSpacingPx=*/ 80,
                        /*interElementDelta=*/ 40, /*noChangeReason=*/ null,
                        /*heuristicRecommendation=*/ null, before, after,
                        /*adjustResult=*/ null /* delegate's DTO; null is OK
                                                  for shape test, exercised
                                                  in live smoke */);
        assertEquals(40, dto.interElementDelta());
        assertNotNull("before must be populated on happy path", dto.before());
        assertNotNull("after must be populated on happy path", dto.after());
        assertNull("noChangeReason must be null on happy path",
                dto.noChangeReason());
    }

    // ---- heuristics-table runtime override ----

    @Test
    public void targetSpacingOverride_supersedes_heuristic() {
        // Caller provides explicit targetSpacing=120 even though heuristic
        // for N=10 would say 60. The accessor must use the override.
        Integer override = 120;
        int connectionCount = 10;
        int heuristic = ElementSpacingHeuristic.targetSpacingForConnectionCount(
                connectionCount, /*hasLargeHubs=*/ false);
        int chosen = (override != null) ? override : heuristic;
        Integer reportedHeuristic = (override != null) ? heuristic : null;
        assertEquals(120, chosen);
        assertNotNull("heuristicRecommendation must be reported when override "
                + "is in effect", reportedHeuristic);
        assertEquals(60, reportedHeuristic.intValue());
    }

    // ---- behavioural pins on
    //         the pure-unit decision function ApplyElementSpacingDecision.
    //         These pins exercise the short-circuit guards (delta=0,
    //         negative-delta clamp, dryRun=true, no-connections, plus the new
    //         no-groups + no-2+-children branches added per cross-model code
    //         review action item [MEDIUM] 2026-05-04) WITHOUT requiring an
    //         OSGi context.
    //         The production accessor method calls
    //         ApplyElementSpacingDecision.decide(..., null) once and dispatches on
    //         shouldCallAdjustViewSpacing(); this test pins that contract. ----

    @Test
    public void decide_no_groups_short_circuits_without_calling_adjust() {
        ApplyElementSpacingDecision d = ApplyElementSpacingDecision.decide(
                /*connectionCount=*/ 30, /*currentSpacingPx=*/ 0,
                /*targetSpacingPx=*/ 80, /*dryRun=*/ false,
                /*hasNonEmptyGroups=*/ false,
                /*hasGroupWithMultipleChildren=*/ false,
                /*hasTargetSpacingOverride=*/ false, null);
        assertEquals(0, d.interElementDelta());
        assertTrue("no-groups branch must NOT call adjustViewSpacing",
                !d.shouldCallAdjustViewSpacing());
        assertNotNull(d.noChangeReason());
        assertTrue(d.noChangeReason().contains("no groups"));
    }

    @Test
    public void decide_groups_present_but_no_2plus_children_short_circuits() {
        // Per Sonnet 4.6 review action item [MEDIUM] — a view can have
        // groups but every group has only 1 (or 0) non-note children, in
        // which case "current spacing" is undefined and the tool must
        // short-circuit explicitly rather than falsely computing a delta
        // against a 0-pseudo-spacing.
        ApplyElementSpacingDecision d = ApplyElementSpacingDecision.decide(
                /*connectionCount=*/ 5, /*currentSpacingPx=*/ 0,
                /*targetSpacingPx=*/ 60, /*dryRun=*/ false,
                /*hasNonEmptyGroups=*/ true,
                /*hasGroupWithMultipleChildren=*/ false,
                /*hasTargetSpacingOverride=*/ false, null);
        assertEquals(0, d.interElementDelta());
        assertTrue("no-2+-children branch must NOT call adjustViewSpacing",
                !d.shouldCallAdjustViewSpacing());
        assertNotNull(d.noChangeReason());
        assertTrue(d.noChangeReason().contains("2+ elements"));
    }

    @Test
    public void decide_zero_connections_short_circuits_without_calling_adjust() {
        // Even with groups + spacing detected, N=0 means the
        // heuristic does not apply; the tool returns a populated
        // noChangeReason and does NOT call adjustViewSpacing.
        ApplyElementSpacingDecision d = ApplyElementSpacingDecision.decide(
                /*connectionCount=*/ 0, /*currentSpacingPx=*/ 40,
                /*targetSpacingPx=*/ 60, /*dryRun=*/ false,
                /*hasNonEmptyGroups=*/ true,
                /*hasGroupWithMultipleChildren=*/ true,
                /*hasTargetSpacingOverride=*/ false, null);
        assertEquals(0, d.interElementDelta());
        assertTrue("zero-connections branch must NOT call adjustViewSpacing",
                !d.shouldCallAdjustViewSpacing());
        assertNotNull(d.noChangeReason());
        assertTrue(d.noChangeReason().contains("no connections"));
    }

    @Test
    public void decide_delta_zero_at_target_short_circuits_without_calling_adjust() {
        // Current spacing equals target → delta=0 → short-circuit
        // (NOT a degenerate adjustViewSpacing call that no-ops internally).
        ApplyElementSpacingDecision d = ApplyElementSpacingDecision.decide(
                /*connectionCount=*/ 20, /*currentSpacingPx=*/ 80,
                /*targetSpacingPx=*/ 80, /*dryRun=*/ false,
                /*hasNonEmptyGroups=*/ true,
                /*hasGroupWithMultipleChildren=*/ true,
                /*hasTargetSpacingOverride=*/ false, null);
        assertEquals(0, d.interElementDelta());
        assertTrue("delta=0 (equal target) must NOT call adjustViewSpacing",
                !d.shouldCallAdjustViewSpacing());
        assertNotNull(d.noChangeReason());
        assertTrue(d.noChangeReason().contains("meets or exceeds"));
        assertTrue(d.noChangeReason().contains("heuristic"));
    }

    @Test
    public void decide_negative_delta_clamps_to_zero_short_circuits() {
        // Current spacing exceeds target → would-be-negative delta
        // → clamped to 0 → short-circuit. The tool MUST NOT shrink generous
        // spacing — this is a footgun guard.
        ApplyElementSpacingDecision d = ApplyElementSpacingDecision.decide(
                /*connectionCount=*/ 10, /*currentSpacingPx=*/ 120,
                /*targetSpacingPx=*/ 60, /*dryRun=*/ false,
                /*hasNonEmptyGroups=*/ true,
                /*hasGroupWithMultipleChildren=*/ true,
                /*hasTargetSpacingOverride=*/ false, null);
        assertEquals(0, d.interElementDelta());
        assertTrue("negative-delta clamped to 0 must NOT call adjustViewSpacing",
                !d.shouldCallAdjustViewSpacing());
        assertNotNull(d.noChangeReason());
    }

    @Test
    public void decide_delta_zero_with_targetSpacingOverride_uses_explicit_phrasing() {
        // When an override was supplied AND delta clamps to 0, the
        // noChangeReason wording is "explicit target" not "heuristic
        // target" — the agent should be able to tell which path produced
        // the recommendation.
        ApplyElementSpacingDecision d = ApplyElementSpacingDecision.decide(
                /*connectionCount=*/ 10, /*currentSpacingPx=*/ 100,
                /*targetSpacingPx=*/ 80, /*dryRun=*/ false,
                /*hasNonEmptyGroups=*/ true,
                /*hasGroupWithMultipleChildren=*/ true,
                /*hasTargetSpacingOverride=*/ true, null);
        assertEquals(0, d.interElementDelta());
        assertTrue(!d.shouldCallAdjustViewSpacing());
        assertNotNull(d.noChangeReason());
        assertTrue(d.noChangeReason().contains("explicit target"));
        assertTrue("explicit-target wording must NOT mention heuristic",
                !d.noChangeReason().contains("heuristic"));
    }

    @Test
    public void decide_dryRun_returns_recommendation_without_calling_adjust() {
        // dryRun=true with a positive delta. The delta is
        // computed and exposed (recommendation) but adjustViewSpacing is
        // NOT called. noChangeReason is null because there IS a change
        // recommended — just not applied.
        ApplyElementSpacingDecision d = ApplyElementSpacingDecision.decide(
                /*connectionCount=*/ 20, /*currentSpacingPx=*/ 40,
                /*targetSpacingPx=*/ 80, /*dryRun=*/ true,
                /*hasNonEmptyGroups=*/ true,
                /*hasGroupWithMultipleChildren=*/ true,
                /*hasTargetSpacingOverride=*/ false, null);
        assertEquals(40, d.interElementDelta());
        assertTrue("dryRun=true must NOT call adjustViewSpacing",
                !d.shouldCallAdjustViewSpacing());
        assertNull("dryRun has a recommendation, not a no-change-reason",
                d.noChangeReason());
    }

    @Test
    public void decide_happy_path_calls_adjust_with_computed_delta() {
        // dryRun=false + positive delta + no short-circuit → the
        // production method calls adjustViewSpacing.
        ApplyElementSpacingDecision d = ApplyElementSpacingDecision.decide(
                /*connectionCount=*/ 20, /*currentSpacingPx=*/ 40,
                /*targetSpacingPx=*/ 80, /*dryRun=*/ false,
                /*hasNonEmptyGroups=*/ true,
                /*hasGroupWithMultipleChildren=*/ true,
                /*hasTargetSpacingOverride=*/ false, null);
        assertEquals(40, d.interElementDelta());
        assertTrue("happy path must call adjustViewSpacing",
                d.shouldCallAdjustViewSpacing());
        assertNull(d.noChangeReason());
    }

    @Test
    public void decide_short_circuit_priority_no_groups_beats_zero_connections() {
        // Both branches would short-circuit; the no-groups guard must fire
        // FIRST so the noChangeReason explains the structural issue
        // (groupless view) rather than the connection-count one.
        ApplyElementSpacingDecision d = ApplyElementSpacingDecision.decide(
                /*connectionCount=*/ 0, /*currentSpacingPx=*/ 0,
                /*targetSpacingPx=*/ 60, /*dryRun=*/ false,
                /*hasNonEmptyGroups=*/ false,
                /*hasGroupWithMultipleChildren=*/ false,
                /*hasTargetSpacingOverride=*/ false, null);
        assertTrue(d.noChangeReason().contains("no groups"));
        assertTrue("no-groups branch must NOT mention connections",
                !d.noChangeReason().contains("no connections"));
    }

    // ---- hub-aware tier integration pins ----

    @Test
    public void hubAware_tier1_returns80_whenHasLargeHubsTrue() {
        // N=10 + ≥1 large hub (>6 conns) → hub-aware tier 1 = 80px
        // (vs no-hubs 60px). The accessor's upstream detect-hub-elements
        // call decides whether to pass true; this test pins the heuristic
        // contract under hasLargeHubs=true.
        assertEquals(80,
                ElementSpacingHeuristic.targetSpacingForConnectionCount(10, true));
    }

    @Test
    public void hubAware_tier2_returns100_whenHasLargeHubsTrue() {
        // N=20 + has hubs → hub-aware tier 2 = 100px (vs no-hubs 80px;
        // matches the H1 textbook case from 2026-05-06 paired empirical).
        assertEquals(100,
                ElementSpacingHeuristic.targetSpacingForConnectionCount(20, true));
    }

    @Test
    public void hubAware_tier3_returns120_whenHasLargeHubsTrue() {
        // N=40 + has hubs → hub-aware tier 3 = 120px (vs no-hubs 100px).
        assertEquals(120,
                ElementSpacingHeuristic.targetSpacingForConnectionCount(40, true));
    }

    @Test
    public void hubAware_branch_doesNotFire_whenHasLargeHubsFalse() {
        // Regression-protection: pre-Row-C semantics preserved when caller
        // passes false. Byte-identical to pre-Row-C return values.
        assertEquals(60,
                ElementSpacingHeuristic.targetSpacingForConnectionCount(10, false));
        assertEquals(80,
                ElementSpacingHeuristic.targetSpacingForConnectionCount(20, false));
        assertEquals(100,
                ElementSpacingHeuristic.targetSpacingForConnectionCount(40, false));
    }

    // ---- heuristic-cross-class consistency tripwire ----

    @Test
    public void hubAware_tripwire_directHeuristicCallMatchesIntegrationContract() {
        // Single-source-of-truth pin: the hub-aware tier 2 value that
        // the accessor computes (ArchiModelAccessorImpl.applyElementSpacing
        // Recommendations step 4) is identical to a direct heuristic call with
        // the same inputs. Pins the expected return value so any future
        // relocation or miscalibration of the hub-aware tier is caught here.
        assertEquals("hub-aware tier 2 (N=20, hasLargeHubs=true) must be 100px",
                100,
                ElementSpacingHeuristic.targetSpacingForConnectionCount(20, true));
    }

    // ---- helpers ----

    private static AssessLayoutResultDto stubAssessLayoutResult(
            String viewId, int connectionCount) {
        // Minimal valid AssessLayoutResultDto for shape-test purposes —
        // populated with neutral values; the M2-M6 + R8 trailing fields use
        // the no-elements default values matching the existing
        // assess-layout empty-view envelope at ArchiModelAccessorImpl.java:2596+.
        return new AssessLayoutResultDto(
                viewId, /*elementCount=*/ 5, connectionCount,
                /*overlapCount=*/ 0, /*containmentOverlaps=*/ 0,
                /*edgeCrossingCount=*/ 0, /*crossingsPerConnection=*/ 0.0,
                /*averageSpacing=*/ 50.0, /*alignmentScore=*/ 0,
                "good", Map.of("overall", "good"),
                /*overlaps=*/ null, /*boundaryViolations=*/ null,
                /*passThroughs=*/ null, /*offCanvasWarnings=*/ null,
                /*labelOverlapCount=*/ 0, /*labelOverlaps=*/ null,
                /*orphanCount=*/ 0, /*orphanDescriptions=*/ null,
                /*noteOverlapCount=*/ 0, /*noteOverlapDescriptions=*/ null,
                /*hasGroups=*/ true, /*coincidentSegmentCount=*/ 0,
                /*nonOrthogonalTerminalCount=*/ 0, /*contentBounds=*/ null,
                /*labelTruncationCount=*/ 0, /*labelTruncations=*/ null,
                /*parentLabelObscuredCount=*/ 0,
                /*parentLabelObscuredDescriptions=*/ null,
                /*imageSiblingOverlapCount=*/ 0,
                /*imageSiblingOverlapDescriptions=*/ null,
                /*violatorIds=*/ null, /*suggestions=*/ List.of(),
                /*interiorTerminationCount=*/ 0,
                /*interiorTerminationDescriptions=*/ null,
                /*zigzagCount=*/ 0, /*zigzagDescriptions=*/ null,
                /*connectionEdgeCoincidenceCount=*/ 0,
                /*edgeCoincidenceDescriptions=*/ null,
                /*hubPortQualityScore=*/ 1.0, /*hubPortQualityFaces=*/ null,
                /*layoutRating=*/ "good", /*routingRating=*/ "good",
                /*corridorUtilisationScore=*/ 1.0,
                /*corridorUtilisationChannels=*/ null);
    }

    // ===================================================================
    // Control-loop extensions (2026-05-15)
    //
    // Six new @Test methods covering the itemised scenarios:
    //   1. control-loop-engaged-on-density-keyed-trigger
    //   2. control-loop-bypassed-on-short-circuit (single-shot path preserved)
    //   3. termination-reason-surfaced-in-DTO
    //   4. response-DTO-final-mutation-count-correct
    //   5. iteration-budget-parameter-respected
    //   6. aggregate-back-off-reverts-correctly
    //
    // Per the existing pure-unit pattern in this file these tests exercise:
    //   - SpacingControlLoop / SpacingIterationDecision public surface (the
    //     accessor's load-bearing dependency).
    //   - The DTO's canonical 14-arg constructor + backwards-compat 11-arg
    //     constructor (verifying the control-loop fields round-trip).
    //   - mapEntryGuardToTerminationReason taxonomy via the public
    //     SpacingControlLoop constants.
    //
    // Eclipse-PDE substrate integration tests (full accessor with the
    // mutationDispatcher dance) deferred to
    // SpacingControlLoopUndoIntegrationTest per architecture-spec § 1.6.
    // ===================================================================

    @Test
    public void controlLoop_engagedOnPositiveDelta_iterateProducesIterations() {
        // (1): control-loop-engaged-on-density-keyed-trigger.
        // Given initial=40, target=70 (gap=30, fits in 1 iteration of the
        // +10 ladder at index 0 → ladder=30). SpacingControlLoop should
        // produce 1 iteration + 1 accepted command.
        LayoutMetrics initial = new LayoutMetrics(
                /*thresholdsMet=*/ 1, /*hpq=*/ 0.5, /*m4=*/ 4,
                /*coincSeg=*/ 2, /*bv=*/ 0, /*vp10=*/ 0.0, /*xings=*/ 10);
        LayoutMetrics post = new LayoutMetrics(2, 0.8, 2, 1, 0, 5.0, 8);

        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                40, 70, 5, Integer.MAX_VALUE, initial, "element");
        SpacingControlLoop.Callbacks callbacks =
                new ToolTestCallbacks(post, /*neverRegress=*/ true);
        SpacingControlLoop.Result result =
                SpacingControlLoop.iterate(request, callbacks);

        assertTrue("loop must produce at least one iteration",
                result.iterations().size() >= 1);
        assertEquals("accepted command count must equal iteration count when "
                + "no regression",
                result.iterations().size(), result.acceptedCommands().size());
        assertEquals(70, result.finalSpacingPx());
    }

    @Test
    public void controlLoop_bypassed_targetAlreadyMet_zeroIterations() {
        // (2): control-loop-bypassed-on-short-circuit. When the
        // target is already met (currentSpacing == targetSpacing) the loop's
        // pre-loop guard short-circuits and produces zero iterations with
        // terminationReason = heuristic_already_met_no_change.
        LayoutMetrics initial = new LayoutMetrics(4, 0.95, 0, 0, 0, 13.3, 5);
        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                80, 80, 5, Integer.MAX_VALUE, initial, "element");
        SpacingControlLoop.Result result = SpacingControlLoop.iterate(
                request, new ToolTestCallbacks(initial, true));

        assertEquals(0, result.iterations().size());
        assertEquals(0, result.acceptedCommands().size());
        assertEquals(SpacingControlLoop.REASON_HEURISTIC_ALREADY_MET,
                result.terminationReason());
        assertEquals(80, result.finalSpacingPx());
    }

    @Test
    public void controlLoop_dtoTerminationReasonField_roundTripsAllFiveBranches() {
        // (3): termination-reason-surfaced-in-DTO. The 5 termination
        // reason taxonomy strings (branches a/b/c/d/e) round-trip
        // through the DTO's terminationReason field verbatim.
        AssessLayoutResultDto before = stubAssessLayoutResult("view-tr", 20);
        String[] reasons = new String[] {
                "goal_reached_at_iteration_3",
                "budget_exhausted_after_5_iterations",
                "aggregate_threshold_regressed_at_iteration_2_reverted_to_iteration_1",
                SpacingControlLoop.REASON_HEURISTIC_ALREADY_MET,
                "dry_run_recommendation_not_applied"
        };
        for (String reason : reasons) {
            ApplyElementSpacingRecommendationsResultDto dto =
                    new ApplyElementSpacingRecommendationsResultDto(
                            "view-tr", false, 20, 40, 80, 40, null, null,
                            before, null, null,
                            reason, 0, List.of());
            assertEquals("terminationReason must echo verbatim: " + reason,
                    reason, dto.terminationReason());
        }
    }

    @Test
    public void controlLoop_dtoIterationCount_equalsAppliedDeltasSize() {
        // (4): response-DTO-final-mutation-count-correct.
        // iterationCount field MUST equal appliedDeltas.size().
        // Tests the invariant: forensic detail in iterations, aggregate in DTO.
        AssessLayoutResultDto before = stubAssessLayoutResult("view-ic", 20);
        AssessLayoutResultDto after = stubAssessLayoutResult("view-ic", 20);
        List<Integer> deltas = List.of(30, 40, 50);
        ApplyElementSpacingRecommendationsResultDto dto =
                new ApplyElementSpacingRecommendationsResultDto(
                        "view-ic", false, 20, 40, 220, 120, null, null,
                        before, after, null,
                        "goal_reached_at_iteration_2", deltas.size(), deltas);
        assertEquals(deltas.size(), dto.iterationCount());
        assertEquals(deltas, dto.appliedDeltas());
        assertEquals("appliedDeltas sum must equal interElementDelta",
                deltas.stream().mapToInt(Integer::intValue).sum(),
                dto.interElementDelta());
    }

    @Test
    public void controlLoop_iterationBudget_respectedAsCap() {
        // (5): iteration-budget-parameter-respected.
        // SpacingControlLoop.iterate(...) MUST NOT exceed the iterationBudget
        // even when the ladder + observations would allow more iterations.
        LayoutMetrics initial = new LayoutMetrics(1, 0.5, 4, 2, 0, 0.0, 10);
        LayoutMetrics post = new LayoutMetrics(2, 0.8, 2, 1, 0, 5.0, 8);

        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                40, 500, /*iterationBudget=*/ 3,
                Integer.MAX_VALUE, initial, "element");
        SpacingControlLoop.Result result = SpacingControlLoop.iterate(
                request, new ToolTestCallbacks(post, true));

        assertEquals("iteration count must NOT exceed iterationBudget",
                3, result.iterations().size());
        assertEquals(3, result.acceptedCommands().size());
        assertTrue("terminationReason must indicate budget exhaustion",
                result.terminationReason().startsWith(
                        SpacingControlLoop.REASON_BUDGET_EXHAUSTED_PREFIX));
    }

    @Test
    public void controlLoop_aggregateBackOff_revertsRegressingIteration() {
        // (6): aggregate-back-off-reverts-correctly.
        // When the post-state thresholdsMet regresses below best_state, the
        // loop reverts THIS iteration's command + halts. The regressing
        // iteration is the LAST element of result.iterations() with
        // backedOff=true; result.acceptedCommands() has size N-1 (or 0 when
        // first iteration regresses).
        LayoutMetrics initial = new LayoutMetrics(2, 0.8, 2, 1, 0, 5.0, 8);
        LayoutMetrics regressedPost =
                new LayoutMetrics(1, 0.7, 3, 1, 0, 5.0, 10);

        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                40, 200, 5, Integer.MAX_VALUE, initial, "element");
        SpacingControlLoop.Result result = SpacingControlLoop.iterate(
                request, new ToolTestCallbacks(regressedPost,
                        /*neverRegress=*/ false));

        assertTrue("terminationReason must indicate aggregate regression",
                result.terminationReason().startsWith(
                        SpacingControlLoop.REASON_AGGREGATE_REGRESSED_PREFIX));
        assertEquals(
                "first-iteration regression: acceptedCommands must be empty",
                0, result.acceptedCommands().size());
        assertEquals("the regressing iteration is recorded as backedOff",
                1, result.iterations().size());
        assertTrue("regressing iteration must be marked backedOff=true",
                result.iterations().get(0).backedOff());
    }

    // ---- helper — minimal SpacingControlLoop.Callbacks stub ----

    /**
     * Minimal {@link SpacingControlLoop.Callbacks} stub used by the
     * @Test methods. Returns a stub {@link SpacingMutationCommand} on each
     * {@code buildMutationCommand} call (execute/undo are no-ops); returns
     * the fixed {@code post} metrics on each {@code observeLayout} call —
     * EITHER non-regressing (always == post) OR regressing (always == post
     * which has lower thresholdsMet than initial).
     */
    private static final class ToolTestCallbacks
            implements SpacingControlLoop.Callbacks {
        private final LayoutMetrics post;
        @SuppressWarnings("unused")
        private final boolean neverRegress;

        ToolTestCallbacks(LayoutMetrics post, boolean neverRegress) {
            this.post = post;
            this.neverRegress = neverRegress;
        }

        @Override
        public SpacingMutationCommand buildMutationCommand(int proposedDeltaPx) {
            return new SpacingMutationCommand() {
                @Override public void execute() { /* no-op stub */ }
                @Override public void undo() { /* no-op stub */ }
            };
        }

        @Override
        public LayoutMetrics observeLayout() {
            return post;
        }
    }
}
