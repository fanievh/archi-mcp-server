package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import net.vheerden.archi.mcp.response.dto.ApplySpacingRecommendationsResultDto;
import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;

/**
 * JUnit pin for the apply-spacing-recommendations composed convenience tool.
 *
 * <p>Sub-tests: scope-dispatch happy path, scope-dispatch error path,
 * heuristic-table delegation pin, short-circuit composition, dryRun true
 * preserves recommendation w/o mutation, dryRun false → apply path branches,
 * tier boundaries, knee-clamp behavioural pin, knee-clamp constants pin,
 * targetOverride + knee-clamp interaction, pure-unit decision-record
 * tests.</p>
 *
 * <p><strong>Pure-unit tests (no OSGi).</strong> Per
 * {@code feedback_first_principles_routing.md} architectural-separation rule:
 * the load-bearing decision logic lives in {@link ApplySpacingDecision} (a
 * pure record), and these pins exercise it directly. Sibling-symmetric with
 * {@code ApplyElementSpacingRecommendationsToolTest} +
 * {@code ApplyGroupSpacingRecommendationsToolTest} (which take the same
 * pure-unit approach).</p>
 *
 * <p>Behavioural assertions involving real model state (e.g.,
 * "after.M4 &lt; before.M4 strict inequality") are deferred to live smoke +
 * empirical paired-arc.</p>
 *
 * <p>The heuristic-table delegation pin tests that the composed
 * tool's target spacings flow through the existing
 * {@link ElementSpacingHeuristic} + {@link GroupSpacingHeuristic} utilities —
 * any future change to those tables propagates automatically without
 * re-encoding here. Per
 * {@code feedback_metric_and_regression_test_together.md}, the knee-clamp
 * constants {@link ApplySpacingDecision#ELEMENT_KNEE_LIMIT_PX} +
 * {@link ApplySpacingDecision#GROUP_KNEE_LIMIT_PX} ARE pinned here directly
 * — any future revision MUST edit this file.</p>
 */
public class ApplySpacingRecommendationsToolTest {

    // ============================================================
    // Fixture helpers — sensible defaults that the per-test methods
    // override only the relevant inputs of.
    // ============================================================

    /**
     * Calls {@link ApplySpacingDecision#decide} with a fixture in the
     * "comfortable mid-tier non-degenerate" regime: scope=both, N=20,
     * interGroupN=10, currentElement=40, currentGroup=40, dryRun=false,
     * has-groups=yes, multi-children=yes, ≥2 top-level=yes, connected=yes,
     * no-hubs, no-overrides. Per-test methods override only the inputs
     * being exercised.
     */
    private static ApplySpacingDecision decideDefault(String scope,
            int currentElement, int currentGroup, int elementTarget,
            int groupTarget, boolean dryRun,
            boolean hasNonEmptyGroups, boolean hasGroupWithMultipleChildren,
            boolean hasAtLeast2TopLevelGroups, int connectionCount,
            int interGroupConnectionCount, boolean isConnected,
            boolean hasElementOverride, boolean hasGroupOverride) {
        return ApplySpacingDecision.decide(
                scope, connectionCount, interGroupConnectionCount,
                currentElement, currentGroup,
                elementTarget, groupTarget, dryRun,
                hasNonEmptyGroups, hasGroupWithMultipleChildren,
                hasAtLeast2TopLevelGroups, isConnected,
                hasElementOverride, hasGroupOverride, null, null);
    }

    // ============================================================
    // knee-clamp limit constants pinned
    // (any future revision MUST edit this test)
    // ============================================================

    @Test
    public void elementKneeLimitConstant_pinnedAt80px() {
        assertEquals(80, ApplySpacingDecision.ELEMENT_KNEE_LIMIT_PX);
    }

    @Test
    public void groupKneeLimitConstant_pinnedAt100px() {
        assertEquals(100, ApplySpacingDecision.GROUP_KNEE_LIMIT_PX);
    }

    // ============================================================
    // heuristic-table delegation pins
    // (delegation-equivalence, NOT re-encoding the table; future
    //  heuristic edits ripple through this composed tool automatically)
    // ============================================================

    @Test
    public void elementHeuristic_lowConnections_noHubs_delegates_to_60px() {
        // ≤15 conns, no-hubs → 60px per ElementSpacingHeuristic
        assertEquals(60,
                ElementSpacingHeuristic.targetSpacingForConnectionCount(10, false));
    }

    @Test
    public void elementHeuristic_midConnections_largeHubs_delegates_to_100px() {
        // 16-30 conns, hub-aware tier → 100px per ElementSpacingHeuristic
        assertEquals(100,
                ElementSpacingHeuristic.targetSpacingForConnectionCount(20, true));
    }

    @Test
    public void groupHeuristic_lowConnections_connected_noHubs_delegates_to_80px() {
        // ≤15 conns, connected, no-hubs → 80px per GroupSpacingHeuristic
        assertEquals(80,
                GroupSpacingHeuristic.targetSpacingForConnectionCount(10, true, false));
    }

    @Test
    public void groupHeuristic_midConnections_connected_largeHubs_delegates_to_140px() {
        // 16-30 conns, connected, hub-aware tier → 140px
        assertEquals(140,
                GroupSpacingHeuristic.targetSpacingForConnectionCount(20, true, true));
    }

    // ============================================================
    // tier boundaries (15/16/30/31) on both heuristics
    // ============================================================

    @Test
    public void elementHeuristic_boundary_15_topOfTier1_returns_60px() {
        assertEquals(60,
                ElementSpacingHeuristic.targetSpacingForConnectionCount(15, false));
    }

    @Test
    public void elementHeuristic_boundary_16_bottomOfTier2_returns_80px() {
        assertEquals(80,
                ElementSpacingHeuristic.targetSpacingForConnectionCount(16, false));
    }

    @Test
    public void groupHeuristic_boundary_30_topOfTier2_connected_returns_100px() {
        assertEquals(100,
                GroupSpacingHeuristic.targetSpacingForConnectionCount(30, true, false));
    }

    @Test
    public void groupHeuristic_boundary_31_bottomOfTier3_connected_returns_120px() {
        assertEquals(120,
                GroupSpacingHeuristic.targetSpacingForConnectionCount(31, true, false));
    }

    // ============================================================
    // scope-dispatch error path (invalid scope value)
    // ============================================================

    @Test
    public void decide_invalidScope_throwsIllegalArgumentException() {
        try {
            decideDefault("ALL_OF_THEM", 40, 40, 80, 80,
                    /*dryRun=*/ false, true, true, true, 20, 10, true,
                    false, false);
            fail("Expected IllegalArgumentException for invalid scope");
        } catch (IllegalArgumentException expected) {
            assertTrue("Error message should mention 'scope'",
                    expected.getMessage().contains("scope"));
        }
    }

    @Test
    public void decide_nullScope_throwsIllegalArgumentException() {
        try {
            decideDefault(null, 40, 40, 80, 80,
                    /*dryRun=*/ false, true, true, true, 20, 10, true,
                    false, false);
            fail("Expected IllegalArgumentException for null scope");
        } catch (IllegalArgumentException expected) {
            // pass
        }
    }

    // ============================================================
    // scope-dispatch happy path (3 tests, one per valid scope)
    // ============================================================

    @Test
    public void decide_scopeBoth_appliesBothArms() {
        // current 40 / target 80 → both deltas 40 (under knee)
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_BOTH,
                40, 40, 80, 80,
                /*dryRun=*/ false, true, true, true, 20, 10, true,
                false, false);
        assertTrue(d.shouldCallAdjustViewSpacing());
        assertEquals(40, d.interElementDelta());
        assertEquals(40, d.interGroupDelta());
        assertNull(d.noChangeReason());
    }

    @Test
    public void decide_scopeElement_appliesElementArmOnly() {
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_ELEMENT,
                40, 40, 80, 80,
                /*dryRun=*/ false, true, true, true, 20, 10, true,
                false, false);
        assertTrue(d.shouldCallAdjustViewSpacing());
        assertEquals(40, d.interElementDelta());
        // group arm out of scope → 0
        assertEquals(0, d.interGroupDelta());
        assertEquals(0, d.proposedGroupDelta());
        assertFalse(d.groupKneeClampApplied());
    }

    @Test
    public void decide_scopeGroup_appliesGroupArmOnly() {
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_GROUP,
                40, 40, 80, 80,
                /*dryRun=*/ false, true, true, true, 20, 10, true,
                false, false);
        assertTrue(d.shouldCallAdjustViewSpacing());
        // element arm out of scope → 0
        assertEquals(0, d.interElementDelta());
        assertEquals(0, d.proposedElementDelta());
        assertFalse(d.elementKneeClampApplied());
        assertEquals(40, d.interGroupDelta());
    }

    // ============================================================
    // short-circuit composition (6 enumerated cases)
    // ============================================================

    @Test
    public void decide_bothZero_noGroups_shortCircuits() {
        // scope=both, view has NO non-empty groups → element-arm blocked;
        // also fewer-than-2-top-level → group-arm blocked
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_BOTH,
                40, 40, 80, 80, false,
                /*hasNonEmptyGroups=*/ false,
                /*hasGroupWithMultipleChildren=*/ false,
                /*hasAtLeast2TopLevelGroups=*/ false,
                20, 10, true, false, false);
        assertFalse(d.shouldCallAdjustViewSpacing());
        assertEquals(0, d.interElementDelta());
        assertEquals(0, d.interGroupDelta());
        assertNotNull(d.noChangeReason());
        assertTrue(d.noChangeReason().contains("element"));
        assertTrue(d.noChangeReason().contains("group"));
    }

    @Test
    public void decide_bothZero_fewerThan2TopLevelGroups_shortCircuits() {
        // 1 non-empty group with 2+ children but only 1 top-level →
        // element heuristic already met (current 80 ≥ target 60, delta=0)
        // + group structurally impossible (fewer than 2 top-level groups).
        // scope=both → composeNoChangeReason concatenates BOTH arm reasons.
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_BOTH,
                80, 40, 60, 80, false,
                true, true,
                /*hasAtLeast2TopLevelGroups=*/ false,
                10, 0, false, false, false);
        assertFalse(d.shouldCallAdjustViewSpacing());
        assertNotNull(d.noChangeReason());
        // group-arm reason (structural impossibility)
        assertTrue(d.noChangeReason().contains("group"));
        // element-arm reason (heuristic already met) — both are concatenated
        assertTrue(d.noChangeReason().contains("already meets"));
    }

    @Test
    public void decide_bothZero_noConnections_shortCircuits() {
        // scope=both, connectionCount=0 → element-arm blocked
        // (no-connections); group-arm with 2+ groups + currentSpacing
        // already meeting unconnected-tier target (40) → group delta=0
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_BOTH,
                40, 40, 60, 40, false,
                true, true, true,
                /*connectionCount=*/ 0,
                /*interGroupConnectionCount=*/ 0,
                /*isConnected=*/ false,
                false, false);
        assertFalse(d.shouldCallAdjustViewSpacing());
        assertNotNull(d.noChangeReason());
        assertTrue(d.noChangeReason().contains("no connections"));
    }

    @Test
    public void decide_bothZero_noChildrenWithMultipleSiblings_shortCircuits() {
        // scope=both, groups exist but none has 2+ children →
        // element arm blocked; group arm with 2+ groups + delta=0
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_BOTH,
                40, 40, 60, 40, false,
                /*hasNonEmptyGroups=*/ true,
                /*hasGroupWithMultipleChildren=*/ false,
                true,
                20, 10, true, false, false);
        assertFalse(d.shouldCallAdjustViewSpacing());
        assertNotNull(d.noChangeReason());
        assertTrue(d.noChangeReason().contains("2+ elements"));
    }

    @Test
    public void decide_bothZero_heuristicAlreadyMetOnBothArms_shortCircuits() {
        // scope=both, current=80 element / 100 group already at heuristic
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_BOTH,
                80, 100, 80, 100, false,
                true, true, true,
                20, 10, true, false, false);
        assertFalse(d.shouldCallAdjustViewSpacing());
        assertEquals(0, d.proposedElementDelta());
        assertEquals(0, d.proposedGroupDelta());
        assertNotNull(d.noChangeReason());
        assertTrue(d.noChangeReason().contains("already meets"));
    }

    @Test
    public void decide_elementZero_groupNonZero_appliesGroupOnly() {
        // scope=both. element delta=0 (already met) BUT group delta>0
        // → still calls adjustViewSpacing (one arm non-zero)
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_BOTH,
                80, 40, 80, 100, false,
                true, true, true,
                20, 10, true, false, false);
        assertTrue(d.shouldCallAdjustViewSpacing());
        assertEquals(0, d.interElementDelta());
        assertEquals(60, d.interGroupDelta());
        assertNull(d.noChangeReason());
    }

    @Test
    public void decide_groupZero_elementNonZero_appliesElementOnly() {
        // scope=both. group delta=0 (already met) BUT element delta>0
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_BOTH,
                40, 100, 80, 100, false,
                true, true, true,
                20, 10, true, false, false);
        assertTrue(d.shouldCallAdjustViewSpacing());
        assertEquals(40, d.interElementDelta());
        assertEquals(0, d.interGroupDelta());
        assertNull(d.noChangeReason());
    }

    // ============================================================
    // dryRun=true preserves recommendation w/o mutation
    // ============================================================

    @Test
    public void decide_dryRun_scopeBoth_doesNotCallAdjust() {
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_BOTH,
                40, 40, 80, 80,
                /*dryRun=*/ true, true, true, true, 20, 10, true,
                false, false);
        assertFalse(d.shouldCallAdjustViewSpacing());
        // recommendation still computed and surfaced
        assertEquals(40, d.interElementDelta());
        assertEquals(40, d.interGroupDelta());
        // dryRun is not a "no change recommended" — noChangeReason null
        assertNull(d.noChangeReason());
    }

    @Test
    public void decide_dryRun_scopeElement_doesNotCallAdjust() {
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_ELEMENT,
                40, 40, 80, 80,
                /*dryRun=*/ true, true, true, true, 20, 10, true,
                false, false);
        assertFalse(d.shouldCallAdjustViewSpacing());
        assertEquals(40, d.interElementDelta());
        assertEquals(0, d.interGroupDelta());
        assertNull(d.noChangeReason());
    }

    @Test
    public void decide_dryRun_scopeGroup_doesNotCallAdjust() {
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_GROUP,
                40, 40, 80, 80,
                /*dryRun=*/ true, true, true, true, 20, 10, true,
                false, false);
        assertFalse(d.shouldCallAdjustViewSpacing());
        assertEquals(0, d.interElementDelta());
        assertEquals(40, d.interGroupDelta());
        assertNull(d.noChangeReason());
    }

    // ============================================================
    // dryRun=false happy path (apply path branches)
    // ============================================================

    @Test
    public void decide_apply_scopeBoth_setsBothDeltas() {
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_BOTH,
                40, 40, 80, 80,
                /*dryRun=*/ false, true, true, true, 20, 10, true,
                false, false);
        assertTrue(d.shouldCallAdjustViewSpacing());
        assertEquals(40, d.interElementDelta());
        assertEquals(40, d.interGroupDelta());
    }

    @Test
    public void decide_apply_scopeElement_setsOnlyElementDelta() {
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_ELEMENT,
                40, 40, 80, 80,
                /*dryRun=*/ false, true, true, true, 20, 10, true,
                false, false);
        assertTrue(d.shouldCallAdjustViewSpacing());
        assertEquals(40, d.interElementDelta());
        assertEquals(0, d.interGroupDelta());
    }

    @Test
    public void decide_apply_scopeGroup_setsOnlyGroupDelta() {
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_GROUP,
                40, 40, 80, 80,
                /*dryRun=*/ false, true, true, true, 20, 10, true,
                false, false);
        assertTrue(d.shouldCallAdjustViewSpacing());
        assertEquals(0, d.interElementDelta());
        assertEquals(40, d.interGroupDelta());
    }

    // ============================================================
    // knee-clamp behavioural pin
    // ============================================================

    @Test
    public void decide_elementClampFires_when_proposedExceedsLimit() {
        // current=10, target=120 → proposed=110 > KNEE_LIMIT=80 → clamp
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_BOTH,
                10, 40, 120, 60, false,
                true, true, true, 40, 20, true, false, false);
        assertEquals(110, d.proposedElementDelta());
        assertEquals(80, d.interElementDelta());
        assertTrue(d.elementKneeClampApplied());
    }

    @Test
    public void decide_elementClampDoesNotFire_when_proposedUnderLimit() {
        // current=20, target=80 → proposed=60 ≤ KNEE_LIMIT=80 → no clamp
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_BOTH,
                20, 100, 80, 100, false,
                true, true, true, 20, 10, true, false, false);
        assertEquals(60, d.proposedElementDelta());
        assertEquals(60, d.interElementDelta());
        assertFalse(d.elementKneeClampApplied());
    }

    @Test
    public void decide_groupClampFires_when_proposedExceedsLimit() {
        // current=40, target=160 → proposed=120 > KNEE_LIMIT=100 → clamp
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_BOTH,
                100, 40, 100, 160, false,
                true, true, true, 40, 20, true, false, false);
        assertEquals(120, d.proposedGroupDelta());
        assertEquals(100, d.interGroupDelta());
        assertTrue(d.groupKneeClampApplied());
    }

    @Test
    public void decide_groupClampDoesNotFire_when_proposedUnderLimit() {
        // current=40, target=120 → proposed=80 ≤ KNEE_LIMIT=100 → no clamp
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_BOTH,
                80, 40, 80, 120, false,
                true, true, true, 40, 20, true, false, false);
        assertEquals(80, d.proposedGroupDelta());
        assertEquals(80, d.interGroupDelta());
        assertFalse(d.groupKneeClampApplied());
    }

    @Test
    public void decide_bothClampsFire_simultaneously() {
        // current=10 element / 10 group, targets very high → both clamp
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_BOTH,
                10, 10, 120, 160, false,
                true, true, true, 40, 20, true, false, false);
        assertTrue(d.elementKneeClampApplied());
        assertTrue(d.groupKneeClampApplied());
        assertEquals(80, d.interElementDelta());
        assertEquals(100, d.interGroupDelta());
    }

    @Test
    public void decide_neitherClampFires_comfortableStartingState() {
        // current already close to target on both arms
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_BOTH,
                60, 80, 80, 100, false,
                true, true, true, 20, 10, true, false, false);
        assertFalse(d.elementKneeClampApplied());
        assertFalse(d.groupKneeClampApplied());
        assertEquals(20, d.interElementDelta());
        assertEquals(20, d.interGroupDelta());
    }

    // ============================================================
    // targetOverride parameters (knee-clamp still applies)
    // ============================================================

    @Test
    public void decide_elementOverride_kneeClampStillApplies() {
        // override target=300 from current=10 → proposed=290, clamp to 80
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_ELEMENT,
                10, 40, 300, 80, false,
                true, true, true, 20, 10, true,
                /*hasElementOverride=*/ true, false);
        assertEquals(290, d.proposedElementDelta());
        assertEquals(80, d.interElementDelta());
        assertTrue(d.elementKneeClampApplied());
        assertTrue("Knee guard cannot be bypassed via override",
                d.interElementDelta() <= ApplySpacingDecision.ELEMENT_KNEE_LIMIT_PX);
    }

    @Test
    public void decide_groupOverride_kneeClampStillApplies() {
        // override target=500 from current=40 → proposed=460, clamp to 100
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_GROUP,
                80, 40, 80, 500, false,
                true, true, true, 20, 10, true,
                false, /*hasGroupOverride=*/ true);
        assertEquals(460, d.proposedGroupDelta());
        assertEquals(100, d.interGroupDelta());
        assertTrue(d.groupKneeClampApplied());
    }

    // ============================================================
    // pure-unit decision-record tests (covering branches not
    // captured above): no-change reason wording on per-arm shorts
    // ============================================================

    @Test
    public void decide_elementOnly_noGroups_blockReasonContainsFlatViews() {
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_ELEMENT,
                40, 40, 80, 80, false,
                /*hasNonEmptyGroups=*/ false, false, false,
                20, 10, true, false, false);
        assertFalse(d.shouldCallAdjustViewSpacing());
        assertNotNull(d.noChangeReason());
        assertTrue(d.noChangeReason().contains("flat views"));
    }

    @Test
    public void decide_elementOnly_noMultiChildren_blockReasonContains2Plus() {
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_ELEMENT,
                40, 40, 80, 80, false,
                true,
                /*hasGroupWithMultipleChildren=*/ false,
                true, 20, 10, true, false, false);
        assertFalse(d.shouldCallAdjustViewSpacing());
        assertTrue(d.noChangeReason().contains("2+ elements"));
    }

    @Test
    public void decide_groupOnly_fewerThan2Groups_blockReasonContainsCorridor() {
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_GROUP,
                40, 40, 80, 80, false,
                true, true,
                /*hasAtLeast2TopLevelGroups=*/ false,
                20, 10, true, false, false);
        assertFalse(d.shouldCallAdjustViewSpacing());
        assertTrue(d.noChangeReason().contains("between-group corridor"));
    }

    @Test
    public void decide_groupOnly_alreadyMet_unconnectedColumnLabelled() {
        // unconnected (interGroupN=0) + current=40 already at heuristic 40
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_GROUP,
                40, 40, 80, 40, false,
                true, true, true,
                20, /*interGroupN=*/ 0, /*isConnected=*/ false,
                false, false);
        assertFalse(d.shouldCallAdjustViewSpacing());
        assertTrue(d.noChangeReason().contains("unconnected"));
    }

    @Test
    public void decide_groupOnly_alreadyMet_connectedColumnLabelled() {
        // connected + current=80 already at heuristic 80
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_GROUP,
                40, 80, 80, 80, false,
                true, true, true,
                20, 10, true,
                false, false);
        assertFalse(d.shouldCallAdjustViewSpacing());
        assertTrue(d.noChangeReason().contains("connected"));
    }

    @Test
    public void decide_elementOnly_overrideMet_reasonMentionsExplicitTarget() {
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_ELEMENT,
                100, 40, 80, 80, false,
                true, true, true, 20, 10, true,
                /*hasElementOverride=*/ true, false);
        assertFalse(d.shouldCallAdjustViewSpacing());
        assertTrue(d.noChangeReason().contains("explicit target"));
    }

    @Test
    public void decide_elementOnly_heuristicMet_reasonMentionsHeuristicTarget() {
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_ELEMENT,
                100, 40, 80, 80, false,
                true, true, true, 20, 10, true,
                /*hasElementOverride=*/ false, false);
        assertFalse(d.shouldCallAdjustViewSpacing());
        assertTrue(d.noChangeReason().contains("heuristic target"));
    }

    @Test
    public void decide_apply_proposedEqualsClamped_whenWithinLimit() {
        // proposed=20 (well under 80) → interElementDelta=20 = proposed
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_BOTH,
                60, 80, 80, 100, false,
                true, true, true, 20, 10, true, false, false);
        assertEquals(d.proposedElementDelta(), d.interElementDelta());
        assertEquals(d.proposedGroupDelta(), d.interGroupDelta());
    }

    @Test
    public void decide_apply_currentExceedsTarget_proposedIsZero() {
        // current > target → max(0, target - current) = 0
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_BOTH,
                120, 200, 80, 100, false,
                true, true, true, 20, 10, true, false, false);
        // Both arms compute proposed=0 → both-zero short-circuit fires
        assertFalse(d.shouldCallAdjustViewSpacing());
        assertEquals(0, d.proposedElementDelta());
        assertEquals(0, d.proposedGroupDelta());
    }

    @Test
    public void decide_scopeElement_groupArmFieldsAreNeutral() {
        // scope=element → group arm should not contribute to reason or
        // clamp metadata regardless of group inputs
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_ELEMENT,
                40, /*currentGroup huge*/ 5,
                80, /*groupTarget huge*/ 500,
                false, true, true, true,
                20, 10, true, false, false);
        // element arm applies normally
        assertTrue(d.shouldCallAdjustViewSpacing());
        // group arm fully neutralised
        assertEquals(0, d.interGroupDelta());
        assertEquals(0, d.proposedGroupDelta());
        assertFalse(d.groupKneeClampApplied());
    }

    @Test
    public void decide_scopeGroup_elementArmFieldsAreNeutral() {
        // scope=group → element arm should not contribute
        ApplySpacingDecision d = decideDefault(
                ApplySpacingDecision.SCOPE_GROUP,
                /*currentElement huge*/ 5, 80,
                /*elementTarget huge*/ 500, 100,
                false, true, true, true,
                20, 10, true, false, false);
        assertTrue(d.shouldCallAdjustViewSpacing());
        assertEquals(0, d.interElementDelta());
        assertEquals(0, d.proposedElementDelta());
        assertFalse(d.elementKneeClampApplied());
    }

    @Test
    public void decide_scopeConstants_areCanonicalValues() {
        assertEquals("both", ApplySpacingDecision.SCOPE_BOTH);
        assertEquals("element", ApplySpacingDecision.SCOPE_ELEMENT);
        assertEquals("group", ApplySpacingDecision.SCOPE_GROUP);
    }

    // ===================================================================
    // Control-loop extensions (2026-05-15). Composer-arm variant.
    //
    // The composer runs TWO control loops per architecture-spec § 1.7
    // Option A (element-arm first, then group-arm). Each arm reports its
    // own per-arm trio (terminationReason + iterationCount + appliedDeltas)
    // in the response DTO.
    // ===================================================================

    @Test
    public void controlLoop_composerArm_element_engagedOnPositiveDelta_iterates() {
        // (1) composer: element-arm engaged with positive delta.
        // Composer uses ELEMENT_KNEE_LIMIT_PX = 80 as the per-iteration cap.
        LayoutMetrics initial = new LayoutMetrics(
                1, 0.5, 4, 2, 0, 0.0, 10);
        LayoutMetrics post = new LayoutMetrics(
                2, 0.8, 2, 1, 0, 5.0, 8);

        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                40, 100, /*budget=*/ 4,
                ApplySpacingDecision.ELEMENT_KNEE_LIMIT_PX,
                initial, "composer.element");
        SpacingControlLoop.Result result = SpacingControlLoop.iterate(
                request, new ComposerToolTestCallbacks(post, true));

        assertTrue(result.iterations().size() >= 1);
        assertTrue("iteration step deltas must respect ELEMENT_KNEE_LIMIT_PX "
                + "cap (≤80)",
                result.iterations().stream()
                        .map(net.vheerden.archi.mcp.model.SpacingIterationStep::deltaApplied)
                        .allMatch(d -> d <= ApplySpacingDecision.ELEMENT_KNEE_LIMIT_PX));
    }

    @Test
    public void controlLoop_composerArm_group_engagedWithGroupKneeLimit() {
        // (1b) composer: group-arm engaged with GROUP_KNEE_LIMIT_PX cap.
        LayoutMetrics initial = new LayoutMetrics(1, 0.5, 8, 4, 0, 0.0, 20);
        LayoutMetrics post = new LayoutMetrics(2, 0.8, 4, 2, 0, 8.0, 15);

        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                40, 200, /*budget=*/ 4,
                ApplySpacingDecision.GROUP_KNEE_LIMIT_PX,
                initial, "composer.group");
        SpacingControlLoop.Result result = SpacingControlLoop.iterate(
                request, new ComposerToolTestCallbacks(post, true));

        assertTrue(result.iterations().size() >= 1);
        assertTrue("iteration step deltas must respect GROUP_KNEE_LIMIT_PX "
                + "cap (≤100)",
                result.iterations().stream()
                        .map(net.vheerden.archi.mcp.model.SpacingIterationStep::deltaApplied)
                        .allMatch(d -> d <= ApplySpacingDecision.GROUP_KNEE_LIMIT_PX));
    }

    @Test
    public void controlLoop_composerDto_perArmTerminationReasons_independentlyParseable() {
        // (3) composer: per-arm terminationReason fields carry the
        // 5-branch taxonomy independently for element + group arms.
        AssessLayoutResultDto before = composerStubAssessment("view-c1", 20);
        AssessLayoutResultDto after = composerStubAssessment("view-c1", 20);
        ApplySpacingRecommendationsResultDto dto =
                new ApplySpacingRecommendationsResultDto(
                        "view-c1", "both", false, 20, 5, true, false,
                        40, 40, 100, 120, 60, 80, 60, 80, false, false,
                        null, null, null, before, after, null,
                        "goal_reached_at_iteration_2", 2, List.of(30, 30),
                        "budget_exhausted_after_4_iterations", 4,
                        List.of(30, 40, 50, 60));
        assertEquals("goal_reached_at_iteration_2",
                dto.elementTerminationReason());
        assertEquals("budget_exhausted_after_4_iterations",
                dto.groupTerminationReason());
        assertEquals(2, dto.elementIterationCount());
        assertEquals(4, dto.groupIterationCount());
        assertEquals(List.of(30, 30), dto.elementAppliedDeltas());
        assertEquals(List.of(30, 40, 50, 60), dto.groupAppliedDeltas());
    }

    @Test
    public void controlLoop_composerDto_perArmInvariants_iterationCountEqualsDeltasSize() {
        // (4) composer: per-arm iterationCount == appliedDeltas.size().
        AssessLayoutResultDto before = composerStubAssessment("view-c2", 20);
        AssessLayoutResultDto after = composerStubAssessment("view-c2", 20);
        // Element-arm: 3 iterations summing to 120 (initial=40, target=160).
        // Group-arm: 1 iteration of 30 (initial=60, target=90).
        List<Integer> elementDeltas = List.of(30, 40, 50);
        List<Integer> groupDeltas = List.of(30);
        ApplySpacingRecommendationsResultDto dto =
                new ApplySpacingRecommendationsResultDto(
                        "view-c2", "both", false, 20, 5, true, false,
                        /*currentElementSpacingPx=*/ 40,
                        /*currentGroupSpacingPx=*/ 60,
                        /*elementTargetSpacingPx=*/ 160,
                        /*groupTargetSpacingPx=*/ 90,
                        /*proposedElementDelta=*/ 120,
                        /*proposedGroupDelta=*/ 30,
                        /*interElementDelta=*/ 120,
                        /*interGroupDelta=*/ 30,
                        false, false,
                        null, null, null, before, after, null,
                        "goal_reached_at_iteration_2",
                        elementDeltas.size(), elementDeltas,
                        "goal_reached_at_iteration_0",
                        groupDeltas.size(), groupDeltas);
        assertEquals(elementDeltas.size(), dto.elementIterationCount());
        assertEquals(groupDeltas.size(), dto.groupIterationCount());
        assertEquals("element sum invariant",
                elementDeltas.stream().mapToInt(Integer::intValue).sum(),
                dto.interElementDelta());
        assertEquals("group sum invariant",
                groupDeltas.stream().mapToInt(Integer::intValue).sum(),
                dto.interGroupDelta());
    }

    @Test
    public void controlLoop_composerArm_iterationBudget_respectedAsCap() {
        // (5) composer: a single arm's budget cap is honoured.
        LayoutMetrics initial = new LayoutMetrics(1, 0.5, 4, 2, 0, 0.0, 10);
        LayoutMetrics post = new LayoutMetrics(2, 0.8, 2, 1, 0, 5.0, 8);

        // Composer default budget=8 split 4+4; one arm with budget=4 must
        // not exceed 4 iterations even if the ladder + observations permit.
        SpacingControlLoop.Request request = new SpacingControlLoop.Request(
                40, 1000, /*budget=*/ 4,
                ApplySpacingDecision.ELEMENT_KNEE_LIMIT_PX,
                initial, "composer.element");
        SpacingControlLoop.Result result = SpacingControlLoop.iterate(
                request, new ComposerToolTestCallbacks(post, true));

        assertEquals(4, result.iterations().size());
        assertEquals(4, result.acceptedCommands().size());
        assertTrue(result.terminationReason().startsWith(
                SpacingControlLoop.REASON_BUDGET_EXHAUSTED_PREFIX));
    }

    @Test
    public void controlLoop_composerDto_shortCircuit_perArmFieldsNeutral() {
        // (2) composer: on short-circuit (e.g., both deltas zero OR
        // dryRun), the per-arm DTO fields populate with neutral
        // terminationReason + 0 iterationCount + empty appliedDeltas.
        AssessLayoutResultDto before = composerStubAssessment("view-c3", 20);
        ApplySpacingRecommendationsResultDto dto =
                new ApplySpacingRecommendationsResultDto(
                        "view-c3", "both", /*dryRun=*/ true, 20, 5, true, false,
                        40, 80, 80, 120, 40, 40, 40, 40, false, false,
                        "preview only", null, null, before, null, null,
                        "dry_run_recommendation_not_applied", 0, List.of(),
                        "dry_run_recommendation_not_applied", 0, List.of());
        assertTrue(dto.dryRun());
        assertEquals("dry_run_recommendation_not_applied",
                dto.elementTerminationReason());
        assertEquals("dry_run_recommendation_not_applied",
                dto.groupTerminationReason());
        assertEquals(0, dto.elementIterationCount());
        assertEquals(0, dto.groupIterationCount());
        assertTrue(dto.elementAppliedDeltas().isEmpty());
        assertTrue(dto.groupAppliedDeltas().isEmpty());
        assertNull("adjustResult null on dryRun short-circuit",
                dto.adjustResult());
    }

    // ---- helpers — composer variant ----

    private static final class ComposerToolTestCallbacks
            implements SpacingControlLoop.Callbacks {
        private final LayoutMetrics post;
        @SuppressWarnings("unused")
        private final boolean neverRegress;

        ComposerToolTestCallbacks(LayoutMetrics post, boolean neverRegress) {
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

    private static AssessLayoutResultDto composerStubAssessment(
            String viewId, int connectionCount) {
        return new AssessLayoutResultDto(
                viewId, 5, connectionCount, 0, 0, 0, 0.0, 50.0, 0, "good",
                Map.of("overall", "good"), null, null, null, null, 0, null,
                0, null, 0, null, true, 0, 0, null, 0, null, 0, null, 0, null,
                null, List.of(), 0, null, 0, null, 0, null, 1.0, null,
                "good", "good", 1.0, null);
    }
}
