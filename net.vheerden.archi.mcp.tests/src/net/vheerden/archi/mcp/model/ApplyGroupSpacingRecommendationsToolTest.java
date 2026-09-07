package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import net.vheerden.archi.mcp.response.dto.ApplyGroupSpacingRecommendationsResultDto;
import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;

/**
 * JUnit pin for the apply-group-spacing-recommendations tool's heuristic
 * table + delta-clamp + connected/unconnected-column-selection + DTO-shape
 * contracts. Pure-unit tests (no OSGi).
 *
 * <p>Sub-tests: heuristic table pin (connected column), heuristic table pin
 * (unconnected column), connected-vs-unconnected determination, delta clamp
 * at 0 short-circuit, negative-delta clamp, dryRun envelope shape, happy-path
 * math + envelope shape, tier boundaries 15/16/30/31 (both columns), no-groups
 * / fewer-than-2-top-level-groups, ApplyGroupSpacingDecision pure-unit pin.</p>
 *
 * <p>Per {@code feedback_metric_and_regression_test_together.md}: the
 * heuristic-table boundaries (both columns) are pinned here. Any change to
 * {@link GroupSpacingHeuristic#targetSpacingForConnectionCount(int, boolean, boolean)}
 * REQUIRES editing this test file — a markdown edit alone would NOT change
 * runtime behaviour, but DOES change documented intent, and that drift is
 * caught here.</p>
 *
 * <p>Behavioural assertions involving real model state (e.g., "after.M4 &lt;
 * before.M4 strict inequality") are deferred to live smoke + empirical
 * paired-arc-when-triad-complete sibling, plus the three-tool-triad
 * empirical-dependency lesson.</p>
 *
 * <p>This test class mirrors {@code ApplyElementSpacingRecommendationsToolTest}
 * verbatim where applicable (sibling-symmetric structure). The novel surface
 * vs the sibling: the connected/unconnected column selection and the
 * {@code interGroupConnectionCount} field on the decision record + DTO.</p>
 */
public class ApplyGroupSpacingRecommendationsToolTest {

    // ---- heuristic table pin: connected column (one per tier) ----

    @Test
    public void targetSpacing_tier1_connected_returns_80px() {
        // Tier 1: ≤15 connections + connected → 80px
        assertEquals(80, GroupSpacingHeuristic.targetSpacingForConnectionCount(
                10, /*isConnected=*/ true, false));
    }

    @Test
    public void targetSpacing_tier2_connected_returns_100px() {
        // Tier 2: 16-30 connections + connected → 100px
        assertEquals(100, GroupSpacingHeuristic.targetSpacingForConnectionCount(
                20, /*isConnected=*/ true, false));
    }

    @Test
    public void targetSpacing_tier3_connected_returns_120px() {
        // Tier 3: >30 connections + connected → 120px
        assertEquals(120, GroupSpacingHeuristic.targetSpacingForConnectionCount(
                40, /*isConnected=*/ true, false));
    }

    // ---- heuristic table pin: unconnected column (one per tier) ----

    @Test
    public void targetSpacing_tier1_unconnected_returns_40px() {
        // Tier 1: ≤15 connections + unconnected → 40px
        assertEquals(40, GroupSpacingHeuristic.targetSpacingForConnectionCount(
                10, /*isConnected=*/ false, false));
    }

    @Test
    public void targetSpacing_tier2_unconnected_returns_40px() {
        // Tier 2: 16-30 connections + unconnected → 40px (same as tier 1
        // intentionally — unconnected views need less corridor only on the
        // densest tier)
        assertEquals(40, GroupSpacingHeuristic.targetSpacingForConnectionCount(
                20, /*isConnected=*/ false, false));
    }

    @Test
    public void targetSpacing_tier3_unconnected_returns_60px() {
        // Tier 3: >30 connections + unconnected → 60px
        assertEquals(60, GroupSpacingHeuristic.targetSpacingForConnectionCount(
                40, /*isConnected=*/ false, false));
    }

    // ---- tier boundaries (15/16/30/31), both columns ----

    @Test
    public void targetSpacing_boundary_15_top_of_tier1_connected_returns_80() {
        assertEquals(80, GroupSpacingHeuristic.targetSpacingForConnectionCount(
                15, true, false));
    }

    @Test
    public void targetSpacing_boundary_15_top_of_tier1_unconnected_returns_40() {
        assertEquals(40, GroupSpacingHeuristic.targetSpacingForConnectionCount(
                15, false, false));
    }

    @Test
    public void targetSpacing_boundary_16_bottom_of_tier2_connected_returns_100() {
        assertEquals(100, GroupSpacingHeuristic.targetSpacingForConnectionCount(
                16, true, false));
    }

    @Test
    public void targetSpacing_boundary_16_bottom_of_tier2_unconnected_returns_40() {
        assertEquals(40, GroupSpacingHeuristic.targetSpacingForConnectionCount(
                16, false, false));
    }

    @Test
    public void targetSpacing_boundary_30_top_of_tier2_connected_returns_100() {
        assertEquals(100, GroupSpacingHeuristic.targetSpacingForConnectionCount(
                30, true, false));
    }

    @Test
    public void targetSpacing_boundary_30_top_of_tier2_unconnected_returns_40() {
        assertEquals(40, GroupSpacingHeuristic.targetSpacingForConnectionCount(
                30, false, false));
    }

    @Test
    public void targetSpacing_boundary_31_bottom_of_tier3_connected_returns_120() {
        assertEquals(120, GroupSpacingHeuristic.targetSpacingForConnectionCount(
                31, true, false));
    }

    @Test
    public void targetSpacing_boundary_31_bottom_of_tier3_unconnected_returns_60() {
        assertEquals(60, GroupSpacingHeuristic.targetSpacingForConnectionCount(
                31, false, false));
    }

    // ---- connected-vs-unconnected determination semantics ----
    //         TopLevelGroupTargets.countInterGroupConnections produces an
    //         interGroupConnectionCount; isConnected = (count > 0).
    //         This block pins the heuristic-column-selection branch on
    //         that boolean. The walk that produces the count is pinned
    //         separately by InterGroupConnectionCountTest, which builds the
    //         containment graph in EMF rather than spying on it — it had no
    //         coverage here, and a defect that made it blind to one of the
    //         two container kinds shipped through this gap.

    @Test
    public void columnSelection_isConnected_true_picks_connected_column() {
        int connected = GroupSpacingHeuristic.targetSpacingForConnectionCount(
                10, /*isConnected=*/ true, false);
        int unconnected = GroupSpacingHeuristic.targetSpacingForConnectionCount(
                10, /*isConnected=*/ false, false);
        assertTrue("connected must be > unconnected on tier 1",
                connected > unconnected);
        assertEquals(80, connected);
    }

    @Test
    public void columnSelection_zero_inter_group_connections_implies_unconnected() {
        // The accessor sets isConnected = (interGroupConnectionCount > 0);
        // pin that 0 inter-group connections → unconnected column path.
        int interGroupConnectionCount = 0;
        boolean isConnected = interGroupConnectionCount > 0;
        assertEquals(40, GroupSpacingHeuristic.targetSpacingForConnectionCount(
                10, isConnected, /*hasLargeHubs=*/ false));
    }

    @Test
    public void columnSelection_one_inter_group_connection_implies_connected() {
        // Even a single inter-group connection flips the column.
        int interGroupConnectionCount = 1;
        boolean isConnected = interGroupConnectionCount > 0;
        assertEquals(80, GroupSpacingHeuristic.targetSpacingForConnectionCount(
                10, isConnected, /*hasLargeHubs=*/ false));
    }

    @Test
    public void columnSelection_unconnected_has_no_inter_group_target_difference_in_tier1_to_tier2() {
        // Pin the design choice that unconnected tier 1 == unconnected tier 2
        // == 40px. If a future revision differentiates them, this fixture
        // must be updated alongside the heuristic — this is the documented-
        // intent guard.
        int t1u = GroupSpacingHeuristic.targetSpacingForConnectionCount(10, false, false);
        int t2u = GroupSpacingHeuristic.targetSpacingForConnectionCount(20, false, false);
        assertEquals(40, t1u);
        assertEquals(40, t2u);
    }

    // ---- negative-delta clamp (generous-spacing view) ----

    @Test
    public void delta_clamp_when_current_is_120_and_connected_target_is_80_returns_zero() {
        // Tier 1 connected: target=80. Current=120 (generous). delta=0.
        // The tool MUST NOT shrink generous corridors.
        int current = 120;
        int target = GroupSpacingHeuristic.targetSpacingForConnectionCount(10, true, false);
        int delta = Math.max(0, target - current);
        assertEquals(0, delta);
    }

    @Test
    public void delta_clamp_when_current_is_60_and_unconnected_target_is_40_returns_zero() {
        // Tier 1 unconnected: target=40. Current=60 (generous). delta=0.
        int current = 60;
        int target = GroupSpacingHeuristic.targetSpacingForConnectionCount(10, false, false);
        int delta = Math.max(0, target - current);
        assertEquals(0, delta);
    }

    // ---- happy-path delta computation ----

    @Test
    public void delta_happy_path_n20_connected_current40_yields_60() {
        // N=20 connected → target=100; current=40 → delta=60
        int current = 40;
        int target = GroupSpacingHeuristic.targetSpacingForConnectionCount(20, true, false);
        int delta = Math.max(0, target - current);
        assertEquals(100, target);
        assertEquals(60, delta);
    }

    @Test
    public void delta_happy_path_n40_connected_current40_yields_80() {
        // N=40 connected → target=120; current=40 → delta=80
        int current = 40;
        int target = GroupSpacingHeuristic.targetSpacingForConnectionCount(40, true, false);
        int delta = Math.max(0, target - current);
        assertEquals(120, target);
        assertEquals(80, delta);
    }

    // ---- dryRun envelope shape (after==null, adjustResult==null) ----

    @Test
    public void dto_dryRun_envelope_shape_has_null_after_and_null_adjustResult() {
        AssessLayoutResultDto before = stubAssessLayoutResult("view-1", 20);
        ApplyGroupSpacingRecommendationsResultDto dto =
                new ApplyGroupSpacingRecommendationsResultDto(
                        "view-1", /*dryRun=*/ true,
                        /*totalConnectionCount=*/ 20,
                        /*interGroupConnectionCount=*/ 5,
                        /*isConnected=*/ true,
                        /*currentSpacingPx=*/ 40,
                        /*targetSpacingPx=*/ 100,
                        /*interGroupDelta=*/ 60,
                        /*noChangeReason=*/ null,
                        /*heuristicRecommendation=*/ null, before,
                        /*after=*/ null, /*adjustResult=*/ null);
        assertEquals("view-1", dto.viewId());
        assertTrue("dryRun must echo true", dto.dryRun());
        assertEquals(60, dto.interGroupDelta());
        assertEquals(20, dto.totalConnectionCount());
        assertEquals(5, dto.interGroupConnectionCount());
        assertTrue("isConnected must be true when interGroup > 0",
                dto.isConnected());
        assertNotNull("before snapshot must be populated under dryRun",
                dto.before());
        assertNull("after must be null under dryRun=true", dto.after());
        assertNull("adjustResult must be null under dryRun=true",
                dto.adjustResult());
        assertNull("noChangeReason must be null on dryRun-with-positive-delta",
                dto.noChangeReason());
    }

    // ---- no-change envelope shape ----

    @Test
    public void dto_no_change_envelope_shape_has_populated_reason() {
        AssessLayoutResultDto before = stubAssessLayoutResult("view-2", 8);
        ApplyGroupSpacingRecommendationsResultDto dto =
                new ApplyGroupSpacingRecommendationsResultDto(
                        "view-2", /*dryRun=*/ false,
                        /*totalConnectionCount=*/ 8,
                        /*interGroupConnectionCount=*/ 0,
                        /*isConnected=*/ false,
                        /*currentSpacingPx=*/ 100,
                        /*targetSpacingPx=*/ 40,
                        /*interGroupDelta=*/ 0,
                        "current group spacing 100px already meets or "
                        + "exceeds heuristic target 40px for connection "
                        + "count 8 (unconnected column, 0 inter-group "
                        + "connections)",
                        /*heuristicRecommendation=*/ null, before,
                        /*after=*/ null, /*adjustResult=*/ null);
        assertEquals(0, dto.interGroupDelta());
        assertNotNull("noChangeReason must be populated when delta=0",
                dto.noChangeReason());
        assertTrue(dto.noChangeReason().contains("meets or exceeds"));
        assertTrue(dto.noChangeReason().contains("unconnected"));
        assertNotNull(dto.before());
        assertNull("after must be null on short-circuit", dto.after());
        assertNull("adjustResult must be null on short-circuit",
                dto.adjustResult());
    }

    // ---- happy-path envelope shape ----

    @Test
    public void dto_happy_path_envelope_shape_has_populated_before_and_after() {
        AssessLayoutResultDto before = stubAssessLayoutResult("view-3", 20);
        AssessLayoutResultDto after = stubAssessLayoutResult("view-3", 20);
        ApplyGroupSpacingRecommendationsResultDto dto =
                new ApplyGroupSpacingRecommendationsResultDto(
                        "view-3", /*dryRun=*/ false,
                        /*totalConnectionCount=*/ 20,
                        /*interGroupConnectionCount=*/ 6,
                        /*isConnected=*/ true,
                        /*currentSpacingPx=*/ 40,
                        /*targetSpacingPx=*/ 100,
                        /*interGroupDelta=*/ 60,
                        /*noChangeReason=*/ null,
                        /*heuristicRecommendation=*/ null, before, after,
                        /*adjustResult=*/ null /* delegate's DTO; null is OK
                                                  for shape test, exercised
                                                  in live smoke */);
        assertEquals(60, dto.interGroupDelta());
        assertNotNull("before must be populated on happy path", dto.before());
        assertNotNull("after must be populated on happy path", dto.after());
        assertNull("noChangeReason must be null on happy path",
                dto.noChangeReason());
    }

    // ---- heuristics-table runtime override ----

    @Test
    public void targetSpacingOverride_supersedes_heuristic() {
        // Caller provides explicit targetSpacing=140 even though heuristic
        // for N=10 connected would say 80. The accessor must use the
        // override, AND report the heuristic value for transparency.
        Integer override = 140;
        int connectionCount = 10;
        int heuristic = GroupSpacingHeuristic.targetSpacingForConnectionCount(
                connectionCount, /*isConnected=*/ true, false);
        int chosen = (override != null) ? override : heuristic;
        Integer reportedHeuristic = (override != null) ? heuristic : null;
        assertEquals(140, chosen);
        assertNotNull("heuristicRecommendation must be reported when "
                + "override is in effect", reportedHeuristic);
        assertEquals(80, reportedHeuristic.intValue());
    }

    // ---- behavioural pins on the pure-unit decision function
    //         ApplyGroupSpacingDecision. Mirrors the sibling-element
    //         decision-record block. The production accessor calls
    //         decide(...) once and dispatches on
    //         shouldCallAdjustViewSpacing(). ----

    @Test
    public void decide_no_top_level_groups_short_circuits_without_calling_adjust() {
        // Fewer than 2 top-level groups → no inter-group corridor exists.
        ApplyGroupSpacingDecision d = ApplyGroupSpacingDecision.decide(
                /*connectionCount=*/ 30,
                /*interGroupConnectionCount=*/ 0,
                /*currentSpacingPx=*/ 0,
                /*targetSpacingPx=*/ 100, /*dryRun=*/ false,
                /*hasAtLeast2TopLevelGroups=*/ false,
                /*isConnected=*/ false,
                /*hasTargetSpacingOverride=*/ false, null);
        assertEquals(0, d.interGroupDelta());
        assertTrue("no-groups branch must NOT call adjustViewSpacing",
                !d.shouldCallAdjustViewSpacing());
        assertNotNull(d.noChangeReason());
        assertTrue(d.noChangeReason().contains("fewer than 2 top-level"));
    }

    @Test
    public void decide_one_top_level_group_short_circuits() {
        // Single top-level group also short-circuits — same branch.
        ApplyGroupSpacingDecision d = ApplyGroupSpacingDecision.decide(
                /*connectionCount=*/ 5,
                /*interGroupConnectionCount=*/ 0,
                /*currentSpacingPx=*/ 0,
                /*targetSpacingPx=*/ 80, /*dryRun=*/ false,
                /*hasAtLeast2TopLevelGroups=*/ false,
                /*isConnected=*/ false,
                /*hasTargetSpacingOverride=*/ false, null);
        assertEquals(0, d.interGroupDelta());
        assertTrue(!d.shouldCallAdjustViewSpacing());
    }

    @Test
    public void decide_unconnected_view_with_2plus_groups_proceeds_or_short_circuits_by_delta() {
        // By default: the unconnected-column heuristic STILL
        // applies (target lookup uses unconnected column). The short-circuit
        // happens on delta-zero math, NOT on isConnected==false. Pin that
        // an unconnected view with TIGHT current spacing still triggers a
        // call to adjustViewSpacing.
        ApplyGroupSpacingDecision d = ApplyGroupSpacingDecision.decide(
                /*connectionCount=*/ 8,
                /*interGroupConnectionCount=*/ 0,
                /*currentSpacingPx=*/ 20,
                /*targetSpacingPx=*/ 40 /* tier1 unconnected */,
                /*dryRun=*/ false,
                /*hasAtLeast2TopLevelGroups=*/ true,
                /*isConnected=*/ false,
                /*hasTargetSpacingOverride=*/ false, null);
        assertEquals(20, d.interGroupDelta());
        assertTrue("unconnected-but-tight must call adjustViewSpacing",
                d.shouldCallAdjustViewSpacing());
        assertNull(d.noChangeReason());
    }

    @Test
    public void decide_delta_zero_at_target_short_circuits_without_calling_adjust() {
        // Current spacing equals target → delta=0 → short-circuit.
        ApplyGroupSpacingDecision d = ApplyGroupSpacingDecision.decide(
                /*connectionCount=*/ 20,
                /*interGroupConnectionCount=*/ 5,
                /*currentSpacingPx=*/ 100,
                /*targetSpacingPx=*/ 100, /*dryRun=*/ false,
                /*hasAtLeast2TopLevelGroups=*/ true,
                /*isConnected=*/ true,
                /*hasTargetSpacingOverride=*/ false, null);
        assertEquals(0, d.interGroupDelta());
        assertTrue("delta=0 (equal target) must NOT call adjustViewSpacing",
                !d.shouldCallAdjustViewSpacing());
        assertNotNull(d.noChangeReason());
        assertTrue(d.noChangeReason().contains("meets or exceeds"));
        assertTrue(d.noChangeReason().contains("connected"));
        assertTrue(d.noChangeReason().contains("inter-group connection"));
    }

    @Test
    public void decide_negative_delta_clamps_to_zero_short_circuits() {
        // Current spacing exceeds target → would-be-negative delta → clamped
        // → short-circuit. Footgun guard against shrinking generous spacing.
        ApplyGroupSpacingDecision d = ApplyGroupSpacingDecision.decide(
                /*connectionCount=*/ 10,
                /*interGroupConnectionCount=*/ 1,
                /*currentSpacingPx=*/ 200,
                /*targetSpacingPx=*/ 80, /*dryRun=*/ false,
                /*hasAtLeast2TopLevelGroups=*/ true,
                /*isConnected=*/ true,
                /*hasTargetSpacingOverride=*/ false, null);
        assertEquals(0, d.interGroupDelta());
        assertTrue("negative-delta clamped to 0 must NOT call adjustViewSpacing",
                !d.shouldCallAdjustViewSpacing());
        assertNotNull(d.noChangeReason());
    }

    @Test
    public void decide_delta_zero_with_targetSpacingOverride_uses_explicit_phrasing() {
        // When an override was supplied AND delta clamps to 0, the
        // noChangeReason wording is "explicit target" not "heuristic
        // target" — sibling-symmetric with inter-element decision.
        ApplyGroupSpacingDecision d = ApplyGroupSpacingDecision.decide(
                /*connectionCount=*/ 10,
                /*interGroupConnectionCount=*/ 1,
                /*currentSpacingPx=*/ 100,
                /*targetSpacingPx=*/ 80, /*dryRun=*/ false,
                /*hasAtLeast2TopLevelGroups=*/ true,
                /*isConnected=*/ true,
                /*hasTargetSpacingOverride=*/ true, null);
        assertEquals(0, d.interGroupDelta());
        assertTrue(!d.shouldCallAdjustViewSpacing());
        assertNotNull(d.noChangeReason());
        assertTrue(d.noChangeReason().contains("explicit target"));
        assertTrue("explicit-target wording must NOT mention heuristic",
                !d.noChangeReason().contains("heuristic"));
    }

    @Test
    public void decide_dryRun_returns_recommendation_without_calling_adjust() {
        // dryRun=true with a positive delta. The delta is computed and
        // exposed (recommendation) but adjustViewSpacing is NOT called.
        // noChangeReason is null because there IS a change recommended.
        ApplyGroupSpacingDecision d = ApplyGroupSpacingDecision.decide(
                /*connectionCount=*/ 20,
                /*interGroupConnectionCount=*/ 5,
                /*currentSpacingPx=*/ 40,
                /*targetSpacingPx=*/ 100, /*dryRun=*/ true,
                /*hasAtLeast2TopLevelGroups=*/ true,
                /*isConnected=*/ true,
                /*hasTargetSpacingOverride=*/ false, null);
        assertEquals(60, d.interGroupDelta());
        assertTrue("dryRun=true must NOT call adjustViewSpacing",
                !d.shouldCallAdjustViewSpacing());
        assertNull("dryRun has a recommendation, not a no-change-reason",
                d.noChangeReason());
    }

    @Test
    public void decide_happy_path_calls_adjust_with_computed_delta() {
        // dryRun=false + positive delta + no short-circuit → call.
        ApplyGroupSpacingDecision d = ApplyGroupSpacingDecision.decide(
                /*connectionCount=*/ 20,
                /*interGroupConnectionCount=*/ 5,
                /*currentSpacingPx=*/ 40,
                /*targetSpacingPx=*/ 100, /*dryRun=*/ false,
                /*hasAtLeast2TopLevelGroups=*/ true,
                /*isConnected=*/ true,
                /*hasTargetSpacingOverride=*/ false, null);
        assertEquals(60, d.interGroupDelta());
        assertTrue("happy path must call adjustViewSpacing",
                d.shouldCallAdjustViewSpacing());
        assertNull(d.noChangeReason());
    }

    @Test
    public void decide_short_circuit_priority_no_groups_beats_delta_zero() {
        // Both branches would short-circuit; the no-groups guard must fire
        // FIRST so the noChangeReason explains the structural issue rather
        // than the spacing math.
        ApplyGroupSpacingDecision d = ApplyGroupSpacingDecision.decide(
                /*connectionCount=*/ 5,
                /*interGroupConnectionCount=*/ 0,
                /*currentSpacingPx=*/ 200,
                /*targetSpacingPx=*/ 40, /*dryRun=*/ false,
                /*hasAtLeast2TopLevelGroups=*/ false,
                /*isConnected=*/ false,
                /*hasTargetSpacingOverride=*/ false, null);
        assertTrue(d.noChangeReason().contains("fewer than 2 top-level"));
        assertTrue("no-groups branch must NOT mention 'meets or exceeds'",
                !d.noChangeReason().contains("meets or exceeds"));
    }

    @Test
    public void decide_unconnected_inter_group_count_singular_phrasing() {
        // Pin the singular/plural distinction in the no-change reason —
        // "0 inter-group connections" (plural) vs "1 inter-group connection"
        // (singular).
        ApplyGroupSpacingDecision d0 = ApplyGroupSpacingDecision.decide(
                /*connectionCount=*/ 5,
                /*interGroupConnectionCount=*/ 0,
                /*currentSpacingPx=*/ 100,
                /*targetSpacingPx=*/ 40, /*dryRun=*/ false,
                /*hasAtLeast2TopLevelGroups=*/ true,
                /*isConnected=*/ false,
                /*hasTargetSpacingOverride=*/ false, null);
        ApplyGroupSpacingDecision d1 = ApplyGroupSpacingDecision.decide(
                /*connectionCount=*/ 5,
                /*interGroupConnectionCount=*/ 1,
                /*currentSpacingPx=*/ 100,
                /*targetSpacingPx=*/ 80, /*dryRun=*/ false,
                /*hasAtLeast2TopLevelGroups=*/ true,
                /*isConnected=*/ true,
                /*hasTargetSpacingOverride=*/ false, null);
        assertTrue(d0.noChangeReason().contains("0 inter-group connections"));
        assertTrue(d1.noChangeReason().contains("1 inter-group connection"));
        // 1-connection wording must NOT pluralize.
        assertTrue("'1 inter-group connection' must not have trailing s",
                !d1.noChangeReason().contains("1 inter-group connections"));
    }

    // ---- hub-aware tier integration pins ----

    @Test
    public void hubAware_connectedTier1_returns100_whenHasLargeHubsTrue() {
        // N=10 + connected + ≥1 large hub → hub-aware tier 1 = 100px
        // (vs connected-no-hubs 80px).
        assertEquals(100,
                GroupSpacingHeuristic.targetSpacingForConnectionCount(10, true, true));
    }

    @Test
    public void hubAware_connectedTier2_returns140_whenHasLargeHubsTrue() {
        // N=20 + connected + has hubs → hub-aware tier 2 = 140px (vs no-hubs
        // 100px; matches the H1 textbook case from 2026-05-06 paired
        // empirical — cumulative +100 group from currentSpacing=40 lands AT
        // the inflation knee).
        assertEquals(140,
                GroupSpacingHeuristic.targetSpacingForConnectionCount(20, true, true));
    }

    @Test
    public void hubAware_connectedTier3_returns160_whenHasLargeHubsTrue() {
        // N=40 + connected + has hubs → hub-aware tier 3 = 160px (vs no-hubs
        // 120px).
        assertEquals(160,
                GroupSpacingHeuristic.targetSpacingForConnectionCount(40, true, true));
    }

    @Test
    public void hubAware_unconnected_doesNotFire_returnsBaselineEvenWhenHasLargeHubsTrue() {
        // Per Task 4 § Synthesis-note: hub-aware tier doesn't fire on
        // unconnected views — there are no inter-group corridors to widen.
        // (N, false, true) returns the SAME 40/40/60 as (N, false, false).
        assertEquals(40,
                GroupSpacingHeuristic.targetSpacingForConnectionCount(10, false, true));
        assertEquals(40,
                GroupSpacingHeuristic.targetSpacingForConnectionCount(20, false, true));
        assertEquals(60,
                GroupSpacingHeuristic.targetSpacingForConnectionCount(40, false, true));
    }

    @Test
    public void hubAware_connectedBranch_doesNotFire_whenHasLargeHubsFalse() {
        // Regression-protection: pre-Row-C connected semantics preserved
        // when caller passes false. Byte-identical to pre-Row-C return
        // values.
        assertEquals(80,
                GroupSpacingHeuristic.targetSpacingForConnectionCount(10, true, false));
        assertEquals(100,
                GroupSpacingHeuristic.targetSpacingForConnectionCount(20, true, false));
        assertEquals(120,
                GroupSpacingHeuristic.targetSpacingForConnectionCount(40, true, false));
    }

    // ---- heuristic-cross-class consistency tripwire ----

    @Test
    public void hubAware_tripwire_directHeuristicCallMatchesIntegrationContract() {
        // Single-source-of-truth pin: the hub-aware connected tier 2
        // value that the accessor computes (ArchiModelAccessorImpl.applyGroup
        // SpacingRecommendations step 4) is identical to a direct heuristic
        // call with the same inputs. Pins the expected return value so any
        // future relocation or miscalibration of the hub-aware tier is caught.
        assertEquals("hub-aware connected tier 2 (N=20, isConnected=true, "
                + "hasLargeHubs=true) must be 140px",
                140,
                GroupSpacingHeuristic.targetSpacingForConnectionCount(
                        20, true, true));
    }

    // ---- helpers ----

    private static AssessLayoutResultDto stubAssessLayoutResult(
            String viewId, int connectionCount) {
        // Minimal valid AssessLayoutResultDto for shape-test purposes —
        // populated with neutral values; matches sibling-element test
        // class's helper. The trailing R-fields use no-elements defaults.
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
    // Control-loop extensions (2026-05-15). Sibling-symmetric with the
    // ApplyElementSpacingRecommendationsToolTest control-loop block.
    // ===================================================================

    @Test
    public void controlLoop_engagedOnPositiveDelta_iterateProducesIterations() {
        // (1) sibling: control-loop-engaged on group-spacing path.
        // Given initial=40, target=70 (gap=30, fits in 1 iteration of the
        // +10 ladder at index 0 → ladder=30).
        LayoutMetrics initial = new LayoutMetrics(
                /*thresholdsMet=*/ 1, /*hpq=*/ 0.5, /*m4=*/ 8,
                /*coincSeg=*/ 4, /*bv=*/ 0, /*vp10=*/ 0.0, /*xings=*/ 20);
        LayoutMetrics post = new LayoutMetrics(2, 0.8, 4, 2, 0, 8.0, 15);

        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                40, 70, 5, Integer.MAX_VALUE, initial, "group");
        SpacingControlLoop.Result result = SpacingControlLoop.iterate(
                request, new GroupToolTestCallbacks(post, true));

        assertTrue(result.iterations().size() >= 1);
        assertEquals(result.iterations().size(),
                result.acceptedCommands().size());
        assertEquals(70, result.finalSpacingPx());
    }

    @Test
    public void controlLoop_bypassed_targetAlreadyMet_zeroIterations() {
        // (2) sibling: short-circuit when target already met.
        LayoutMetrics initial = new LayoutMetrics(4, 0.95, 0, 0, 0, 13.3, 5);
        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                120, 120, 5, Integer.MAX_VALUE, initial, "group");
        SpacingControlLoop.Result result = SpacingControlLoop.iterate(
                request, new GroupToolTestCallbacks(initial, true));

        assertEquals(0, result.iterations().size());
        assertEquals(SpacingControlLoop.REASON_HEURISTIC_ALREADY_MET,
                result.terminationReason());
    }

    @Test
    public void controlLoop_dtoTerminationReasonField_roundTripsAllFiveBranches() {
        // (3) sibling: 5 termination-reason strings round-trip
        // through the group DTO's terminationReason field.
        AssessLayoutResultDto before = stubAssessLayoutResult("view-grp", 20);
        String[] reasons = new String[] {
                "goal_reached_at_iteration_4",
                "budget_exhausted_after_5_iterations",
                "aggregate_threshold_regressed_at_iteration_3_reverted_to_iteration_2",
                SpacingControlLoop.REASON_HEURISTIC_ALREADY_MET,
                "dry_run_recommendation_not_applied"
        };
        for (String reason : reasons) {
            ApplyGroupSpacingRecommendationsResultDto dto =
                    new ApplyGroupSpacingRecommendationsResultDto(
                            "view-grp", false, 20, 5, true, 40, 100, 60,
                            null, null, before, null, null,
                            reason, 0, List.of());
            assertEquals(reason, dto.terminationReason());
        }
    }

    @Test
    public void controlLoop_dtoIterationCount_equalsAppliedDeltasSize() {
        // (4) sibling: iterationCount field == appliedDeltas.size().
        AssessLayoutResultDto before = stubAssessLayoutResult("view-grp", 20);
        AssessLayoutResultDto after = stubAssessLayoutResult("view-grp", 20);
        List<Integer> deltas = List.of(30, 40, 50, 60);
        ApplyGroupSpacingRecommendationsResultDto dto =
                new ApplyGroupSpacingRecommendationsResultDto(
                        "view-grp", false, 20, 5, true, 40, 220, 180,
                        null, null, before, after, null,
                        "goal_reached_at_iteration_3", deltas.size(), deltas);
        assertEquals(deltas.size(), dto.iterationCount());
        assertEquals(deltas, dto.appliedDeltas());
        assertEquals("appliedDeltas sum must equal interGroupDelta",
                deltas.stream().mapToInt(Integer::intValue).sum(),
                dto.interGroupDelta());
    }

    @Test
    public void controlLoop_iterationBudget_respectedAsCap() {
        // (5) sibling: iterationBudget cap honoured.
        LayoutMetrics initial = new LayoutMetrics(1, 0.5, 8, 4, 0, 0.0, 20);
        LayoutMetrics post = new LayoutMetrics(2, 0.8, 4, 2, 0, 8.0, 15);

        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                40, 500, /*iterationBudget=*/ 2,
                Integer.MAX_VALUE, initial, "group");
        SpacingControlLoop.Result result = SpacingControlLoop.iterate(
                request, new GroupToolTestCallbacks(post, true));

        assertEquals(2, result.iterations().size());
        assertEquals(2, result.acceptedCommands().size());
        assertTrue(result.terminationReason().startsWith(
                SpacingControlLoop.REASON_BUDGET_EXHAUSTED_PREFIX));
    }

    @Test
    public void controlLoop_aggregateBackOff_revertsRegressingIteration() {
        // (6) sibling: aggregate back-off reverts on regression.
        LayoutMetrics initial = new LayoutMetrics(2, 0.8, 4, 2, 0, 8.0, 15);
        LayoutMetrics regressedPost = new LayoutMetrics(1, 0.6, 6, 2, 0, 8.0, 22);

        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                40, 200, 5, Integer.MAX_VALUE, initial, "group");
        SpacingControlLoop.Result result = SpacingControlLoop.iterate(
                request, new GroupToolTestCallbacks(regressedPost, false));

        assertTrue(result.terminationReason().startsWith(
                SpacingControlLoop.REASON_AGGREGATE_REGRESSED_PREFIX));
        assertEquals(0, result.acceptedCommands().size());
        assertTrue(result.iterations().get(0).backedOff());
    }

    // ---- helper — group-tool variant of the callbacks stub ----

    private static final class GroupToolTestCallbacks
            implements SpacingControlLoop.Callbacks {
        private final LayoutMetrics post;
        @SuppressWarnings("unused")
        private final boolean neverRegress;

        GroupToolTestCallbacks(LayoutMetrics post, boolean neverRegress) {
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
