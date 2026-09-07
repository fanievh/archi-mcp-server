package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pure-unit JUnit pins for the density-aware default-resolution decision
 * function.
 *
 * <p>Mirrors the convenience-tool sibling's
 * {@code ApplyElementSpacingRecommendationsToolTest} discipline: heuristic
 * tier pin + trigger-threshold pin + branch-coverage pins on the pure-unit
 * decision record, all runnable without an OSGi context (no
 * {@link ArchiModelAccessorImpl} class-loading required).</p>
 *
 * <p>Coverage map:</p>
 * <ul>
 *   <li>Heuristic-tier pin (3 assertions on
 *       {@link ElementSpacingHeuristic#targetSpacingForConnectionCount(int, boolean)}).</li>
 *   <li>Trigger-threshold pin (4 boundary assertions on
 *       {@link AdjustViewSpacingDefaultResolutionDecision#decide}).</li>
 *   <li>Omitted-vs-explicit-zero behavioural distinction (paired
 *       fixtures sharing identical view state).</li>
 *   <li>Trigger-fires happy path.</li>
 *   <li>Trigger-fires-but-already-meets-target.</li>
 *   <li>No-trigger no-fire.</li>
 *   <li>No-connections short-circuit.</li>
 *   <li>Decision-record-style pin (this entire class IS the
 *       deliverable — pure-unit branch coverage on
 *       {@link AdjustViewSpacingDefaultResolutionDecision}).</li>
 *   <li>Heuristic-cross-class consistency (one assertion shared
 *       with {@code ApplyElementSpacingRecommendationsToolTest} fixtures).</li>
 * </ul>
 *
 * <p>NO {@code @Ignore} and NO {@code Assume.*} runtime skips; every test
 * MUST run green at HEAD.</p>
 */
public class AdjustViewSpacingDefaultResolutionTest {

    // -------- Heuristic-tier pin --------
    // Cross-class duplication with ApplyElementSpacingRecommendationsToolTest
    // is intentional defence-in-depth per the Heuristic-table
    // reuse note: editing the boundaries here requires updates across
    // THREE pin classes (markdown + utility + this test + sibling test).

    @Test
    public void heuristic_le15_returns60() {
        assertEquals(60, ElementSpacingHeuristic.targetSpacingForConnectionCount(10, false));
    }

    @Test
    public void heuristic_16to30_returns80() {
        assertEquals(80, ElementSpacingHeuristic.targetSpacingForConnectionCount(20, false));
    }

    @Test
    public void heuristic_above30_returns100() {
        assertEquals(100, ElementSpacingHeuristic.targetSpacingForConnectionCount(40, false));
    }

    // -------- Trigger-threshold boundary pin --------
    // Advisory-placeholder thresholds: coincSeg > 2 OR
    // connectionEdgeCoincidence > 4. Boundary cases protect against drift.

    @Test
    public void trigger_coincSeg_atBoundary_2_doesNotFire() {
        AdjustViewSpacingDefaultResolutionDecision d =
                AdjustViewSpacingDefaultResolutionDecision.decide(
                        /*callerProvidedDelta=*/ null,
                        /*coincidentSegmentCount=*/ 2,
                        /*connectionEdgeCoincidenceCount=*/ 0,
                        /*connectionCount=*/ 20,
                        /*currentSpacingPx=*/ 40,
                        /*hasGroups=*/ true,
                        /*hasGroupWithMultipleChildren=*/ true,
                        /*hasLargeHubs=*/ false);
        assertFalse("coincSeg=2 must NOT trip trigger (boundary)", d.fired());
        assertEquals(0, d.resolvedDelta());
        assertNull("no-trigger no-fire => reason null", d.reason());
        assertEquals(AdjustViewSpacingDefaultResolutionDecision.TriggerMetric.NONE,
                d.triggerMetric());
    }

    @Test
    public void trigger_coincSeg_above_3_fires() {
        AdjustViewSpacingDefaultResolutionDecision d =
                AdjustViewSpacingDefaultResolutionDecision.decide(
                        /*callerProvidedDelta=*/ null,
                        /*coincidentSegmentCount=*/ 3,
                        /*connectionEdgeCoincidenceCount=*/ 0,
                        /*connectionCount=*/ 20,
                        /*currentSpacingPx=*/ 40,
                        /*hasGroups=*/ true,
                        /*hasGroupWithMultipleChildren=*/ true,
                        /*hasLargeHubs=*/ false);
        assertTrue("coincSeg=3 (>2) MUST trip trigger", d.fired());
        assertEquals(AdjustViewSpacingDefaultResolutionDecision.TriggerMetric.COINCIDENT_SEGMENTS,
                d.triggerMetric());
        assertEquals(3, d.triggerValue());
    }

    @Test
    public void trigger_edgeCoincidence_atBoundary_4_doesNotFire() {
        AdjustViewSpacingDefaultResolutionDecision d =
                AdjustViewSpacingDefaultResolutionDecision.decide(
                        /*callerProvidedDelta=*/ null,
                        /*coincidentSegmentCount=*/ 0,
                        /*connectionEdgeCoincidenceCount=*/ 4,
                        /*connectionCount=*/ 20,
                        /*currentSpacingPx=*/ 40,
                        /*hasGroups=*/ true,
                        /*hasGroupWithMultipleChildren=*/ true,
                        /*hasLargeHubs=*/ false);
        assertFalse("edgeCoincidence=4 must NOT trip trigger (boundary)", d.fired());
        assertNull(d.reason());
    }

    @Test
    public void trigger_edgeCoincidence_above_5_fires() {
        AdjustViewSpacingDefaultResolutionDecision d =
                AdjustViewSpacingDefaultResolutionDecision.decide(
                        /*callerProvidedDelta=*/ null,
                        /*coincidentSegmentCount=*/ 0,
                        /*connectionEdgeCoincidenceCount=*/ 5,
                        /*connectionCount=*/ 20,
                        /*currentSpacingPx=*/ 40,
                        /*hasGroups=*/ true,
                        /*hasGroupWithMultipleChildren=*/ true,
                        /*hasLargeHubs=*/ false);
        assertTrue("edgeCoincidence=5 (>4) MUST trip trigger", d.fired());
        assertEquals(AdjustViewSpacingDefaultResolutionDecision.TriggerMetric.EDGE_COINCIDENCE,
                d.triggerMetric());
        assertEquals(5, d.triggerValue());
    }

    // -------- Omitted-vs-explicit-zero behavioural distinction --------
    // Paired fixtures sharing identical view state; only the caller-provided
    // delta differs (null vs 0). Backwards-compat preservation pin.

    @Test
    public void fixtureA_callerOmitted_triggerFires_resolvesNonZero() {
        AdjustViewSpacingDefaultResolutionDecision d =
                AdjustViewSpacingDefaultResolutionDecision.decide(
                        /*callerProvidedDelta=*/ null,   // OMITTED
                        /*coincidentSegmentCount=*/ 11,
                        /*connectionEdgeCoincidenceCount=*/ 0,
                        /*connectionCount=*/ 30,
                        /*currentSpacingPx=*/ 40,
                        /*hasGroups=*/ true,
                        /*hasGroupWithMultipleChildren=*/ true,
                        /*hasLargeHubs=*/ false);
        assertTrue("omitted + trigger => fired", d.fired());
        assertEquals("delta = max(0, 80 - 40) = 40", 40, d.resolvedDelta());
        assertNotNull("default-resolution fired => reason populated", d.reason());
    }

    @Test
    public void fixtureB_callerExplicitZero_sameViewState_doesNotFire() {
        AdjustViewSpacingDefaultResolutionDecision d =
                AdjustViewSpacingDefaultResolutionDecision.decide(
                        /*callerProvidedDelta=*/ 0,      // EXPLICIT ZERO
                        /*coincidentSegmentCount=*/ 11,  // same trigger state
                        /*connectionEdgeCoincidenceCount=*/ 0,
                        /*connectionCount=*/ 30,
                        /*currentSpacingPx=*/ 40,
                        /*hasGroups=*/ true,
                        /*hasGroupWithMultipleChildren=*/ true,
                        /*hasLargeHubs=*/ false);
        assertFalse("explicit 0 must NOT fire default-resolution", d.fired());
        assertEquals("explicit 0 passes through unchanged", 0, d.resolvedDelta());
        assertNull("explicit 0 => reason null (no default-resolution semantics)",
                d.reason());
    }

    @Test
    public void callerExplicitNonZero_passesThroughUnchanged() {
        AdjustViewSpacingDefaultResolutionDecision d =
                AdjustViewSpacingDefaultResolutionDecision.decide(
                        /*callerProvidedDelta=*/ 17,
                        /*coincidentSegmentCount=*/ 11,
                        /*connectionEdgeCoincidenceCount=*/ 0,
                        /*connectionCount=*/ 30,
                        /*currentSpacingPx=*/ 40,
                        /*hasGroups=*/ true,
                        /*hasGroupWithMultipleChildren=*/ true,
                        /*hasLargeHubs=*/ false);
        assertFalse("caller-provided value => no default-resolution", d.fired());
        assertEquals("caller's value passes through", 17, d.resolvedDelta());
        assertNull(d.reason());
    }

    // -------- Trigger-fires happy path --------
    // V4-class spacing-tight scenario: coincSeg=11, connections=30, current=40.
    // Heuristic for connectionCount=30 => target=80 => delta = max(0, 80-40) = 40.

    @Test
    public void triggerFiresHappyPath_v4ClassFixture() {
        AdjustViewSpacingDefaultResolutionDecision d =
                AdjustViewSpacingDefaultResolutionDecision.decide(
                        /*callerProvidedDelta=*/ null,
                        /*coincidentSegmentCount=*/ 11,
                        /*connectionEdgeCoincidenceCount=*/ 0,
                        /*connectionCount=*/ 30,
                        /*currentSpacingPx=*/ 40,
                        /*hasGroups=*/ true,
                        /*hasGroupWithMultipleChildren=*/ true,
                        /*hasLargeHubs=*/ false);
        assertTrue(d.fired());
        assertEquals("expected delta", 40, d.resolvedDelta());
        assertEquals(AdjustViewSpacingDefaultResolutionDecision.TriggerMetric.COINCIDENT_SEGMENTS,
                d.triggerMetric());
        assertEquals(11, d.triggerValue());
        // Reason content: must mention BOTH the trigger value AND the heuristic
        // computation.
        assertNotNull(d.reason());
        assertTrue("reason mentions trigger value 11",
                d.reason().contains("11"));
        assertTrue("reason mentions trigger threshold (>2)",
                d.reason().contains("> 2"));
        assertTrue("reason mentions coincidentSegmentCount label",
                d.reason().contains("coincidentSegmentCount"));
        assertTrue("reason mentions heuristic target 80",
                d.reason().contains("target=80"));
        assertTrue("reason mentions current spacing 40",
                d.reason().contains("currentSpacingPx=40"));
        assertTrue("reason mentions delta 40",
                d.reason().contains("delta=40"));
        assertTrue("reason mentions connectionCount 30",
                d.reason().contains("connectionCount=30"));
    }

    @Test
    public void edgeCoincidenceTriggerHappyPath() {
        // edge-coincidence = 5 (>4) trigger fires.
        AdjustViewSpacingDefaultResolutionDecision d =
                AdjustViewSpacingDefaultResolutionDecision.decide(
                        /*callerProvidedDelta=*/ null,
                        /*coincidentSegmentCount=*/ 0,
                        /*connectionEdgeCoincidenceCount=*/ 5,
                        /*connectionCount=*/ 20,
                        /*currentSpacingPx=*/ 40,
                        /*hasGroups=*/ true,
                        /*hasGroupWithMultipleChildren=*/ true,
                        /*hasLargeHubs=*/ false);
        assertTrue(d.fired());
        assertEquals("delta = max(0, 80 - 40) = 40 for connectionCount=20",
                40, d.resolvedDelta());
        assertEquals(AdjustViewSpacingDefaultResolutionDecision.TriggerMetric.EDGE_COINCIDENCE,
                d.triggerMetric());
        assertTrue(d.reason().contains("connectionEdgeCoincidenceCount"));
        assertTrue(d.reason().contains("> 4"));
    }

    // -------- Trigger-fires but already-meets-target --------
    // Trigger fires but heuristic returns delta=0 because current already
    // meets/exceeds target. fired=true (transparency: the gate fired) but
    // delta=0 (the all-zero short-circuit at the call site folds this into
    // a no-mutation response).

    @Test
    public void triggerFires_alreadyMeetsTarget_deltaZero() {
        AdjustViewSpacingDefaultResolutionDecision d =
                AdjustViewSpacingDefaultResolutionDecision.decide(
                        /*callerProvidedDelta=*/ null,
                        /*coincidentSegmentCount=*/ 11,
                        /*connectionEdgeCoincidenceCount=*/ 0,
                        /*connectionCount=*/ 30,
                        /*currentSpacingPx=*/ 80,    // already meets target
                        /*hasGroups=*/ true,
                        /*hasGroupWithMultipleChildren=*/ true,
                        /*hasLargeHubs=*/ false);
        assertTrue("trigger fired (transparency)", d.fired());
        assertEquals("clamp-non-negative => delta=0", 0, d.resolvedDelta());
        assertNotNull("reason populated even though delta=0", d.reason());
        assertTrue(d.reason().contains("delta=0"));
    }

    @Test
    public void triggerFires_currentExceedsTarget_clampsToZero() {
        AdjustViewSpacingDefaultResolutionDecision d =
                AdjustViewSpacingDefaultResolutionDecision.decide(
                        /*callerProvidedDelta=*/ null,
                        /*coincidentSegmentCount=*/ 11,
                        /*connectionEdgeCoincidenceCount=*/ 0,
                        /*connectionCount=*/ 30,
                        /*currentSpacingPx=*/ 120,   // exceeds target (80)
                        /*hasGroups=*/ true,
                        /*hasGroupWithMultipleChildren=*/ true,
                        /*hasLargeHubs=*/ false);
        assertTrue(d.fired());
        assertEquals("max(0, 80-120) = 0", 0, d.resolvedDelta());
    }

    // -------- No-trigger no-fire --------
    // Clean view: zero coincSeg + zero edge-coincidence + groups + connections
    // present. No fire, no reason — existing zero-delta short-circuit takes
    // over at the call site.

    @Test
    public void cleanView_noTrigger_noFire() {
        AdjustViewSpacingDefaultResolutionDecision d =
                AdjustViewSpacingDefaultResolutionDecision.decide(
                        /*callerProvidedDelta=*/ null,
                        /*coincidentSegmentCount=*/ 0,
                        /*connectionEdgeCoincidenceCount=*/ 0,
                        /*connectionCount=*/ 10,
                        /*currentSpacingPx=*/ 60,
                        /*hasGroups=*/ true,
                        /*hasGroupWithMultipleChildren=*/ true,
                        /*hasLargeHubs=*/ false);
        assertFalse(d.fired());
        assertEquals(0, d.resolvedDelta());
        assertNull("clean view => no informational reason", d.reason());
        assertEquals(AdjustViewSpacingDefaultResolutionDecision.TriggerMetric.NONE,
                d.triggerMetric());
    }

    // -------- No-connections short-circuit --------
    // Heuristic is connection-count-driven; zero connections => default
    // undefined. No fire, but informational reason populated for transparency.

    @Test
    public void noConnections_doesNotFire_reasonInformational() {
        AdjustViewSpacingDefaultResolutionDecision d =
                AdjustViewSpacingDefaultResolutionDecision.decide(
                        /*callerProvidedDelta=*/ null,
                        /*coincidentSegmentCount=*/ 0,
                        /*connectionEdgeCoincidenceCount=*/ 0,
                        /*connectionCount=*/ 0,           // no connections
                        /*currentSpacingPx=*/ 60,
                        /*hasGroups=*/ true,
                        /*hasGroupWithMultipleChildren=*/ true,
                        /*hasLargeHubs=*/ false);
        assertFalse(d.fired());
        assertEquals(0, d.resolvedDelta());
        assertNotNull("zero-connections => informational reason populated",
                d.reason());
        assertTrue(d.reason().contains("no connections"));
    }

    // -------- Defence-in-depth no-fire branches --------

    @Test
    public void noGroups_doesNotFire() {
        AdjustViewSpacingDefaultResolutionDecision d =
                AdjustViewSpacingDefaultResolutionDecision.decide(
                        /*callerProvidedDelta=*/ null,
                        /*coincidentSegmentCount=*/ 11,
                        /*connectionEdgeCoincidenceCount=*/ 0,
                        /*connectionCount=*/ 30,
                        /*currentSpacingPx=*/ 0,
                        /*hasGroups=*/ false,           // flat view
                        /*hasGroupWithMultipleChildren=*/ false,
                        /*hasLargeHubs=*/ false);
        assertFalse(d.fired());
        assertEquals(0, d.resolvedDelta());
        assertNull(d.reason());
    }

    @Test
    public void groupsWithoutMultipleChildren_doesNotFire() {
        AdjustViewSpacingDefaultResolutionDecision d =
                AdjustViewSpacingDefaultResolutionDecision.decide(
                        /*callerProvidedDelta=*/ null,
                        /*coincidentSegmentCount=*/ 11,
                        /*connectionEdgeCoincidenceCount=*/ 0,
                        /*connectionCount=*/ 30,
                        /*currentSpacingPx=*/ 0,
                        /*hasGroups=*/ true,
                        /*hasGroupWithMultipleChildren=*/ false,
                        /*hasLargeHubs=*/ false);
        assertFalse(d.fired());
        assertEquals(0, d.resolvedDelta());
    }

    // -------- Heuristic-cross-class consistency --------
    // ONE assertion verifying that BOTH ApplyElementSpacingDecision (sibling)
    // and AdjustViewSpacingDefaultResolutionDecision compute the
    // SAME interElementDelta + SAME targetSpacingPx for an identical fixture.
    // This is the multi-class tripwire — change either decision function's
    // heuristic-resolution logic and this test fails until they agree again.

    @Test
    public void heuristicCrossClassConsistency() {
        // Shared fixture: connectionCount=20, currentSpacing=40 => target=80,
        // delta=40. Both classes should arrive at the same numbers.
        int connectionCount = 20;
        int currentSpacingPx = 40;
        int expectedTarget = ElementSpacingHeuristic
                .targetSpacingForConnectionCount(connectionCount, false);
        int expectedDelta = Math.max(0, expectedTarget - currentSpacingPx);

        // Sibling decision function (ApplyElementSpacingDecision)
        ApplyElementSpacingDecision siblingDecision =
                ApplyElementSpacingDecision.decide(
                        connectionCount, currentSpacingPx, expectedTarget,
                        /*dryRun=*/ false,
                        /*hasNonEmptyGroups=*/ true,
                        /*hasGroupWithMultipleChildren=*/ true,
                        /*hasTargetSpacingOverride=*/ false, null);

        // This class's decision function (default-resolution path with
        // trigger fired so the heuristic computation actually runs).
        AdjustViewSpacingDefaultResolutionDecision thisDecision =
                AdjustViewSpacingDefaultResolutionDecision.decide(
                        /*callerProvidedDelta=*/ null,
                        /*coincidentSegmentCount=*/ 11,   // trigger
                        /*connectionEdgeCoincidenceCount=*/ 0,
                        connectionCount,
                        currentSpacingPx,
                        /*hasGroups=*/ true,
                        /*hasGroupWithMultipleChildren=*/ true,
                        /*hasLargeHubs=*/ false);

        assertEquals(expectedTarget, 80);
        assertEquals(expectedDelta, 40);
        assertEquals("sibling decision agrees on delta",
                expectedDelta, siblingDecision.interElementDelta());
        assertEquals("this story's decision agrees on delta",
                expectedDelta, thisDecision.resolvedDelta());
        assertEquals("both decision functions => same numerical delta",
                siblingDecision.interElementDelta(), thisDecision.resolvedDelta());
    }

    // -------- Reason string format stability pin (soft) --------
    // The reason string for the canonical fixture matches the documented
    // regex. Soft pin — protects against accidental drift while
    // allowing intentional revision (which would require updating this test).

    @Test
    public void reasonStringFormat_canonicalFixture() {
        AdjustViewSpacingDefaultResolutionDecision d =
                AdjustViewSpacingDefaultResolutionDecision.decide(
                        /*callerProvidedDelta=*/ null,
                        /*coincidentSegmentCount=*/ 11,
                        /*connectionEdgeCoincidenceCount=*/ 0,
                        /*connectionCount=*/ 30,
                        /*currentSpacingPx=*/ 40,
                        /*hasGroups=*/ true,
                        /*hasGroupWithMultipleChildren=*/ true,
                        /*hasLargeHubs=*/ false);
        // Format: "interElementDelta omitted; <metric>=<v> > <thr> trigger; "
        //         "heuristic for connectionCount=<n> => target=<t>; "
        //         "currentSpacingPx=<c> => delta=<d>"
        String regex = "interElementDelta omitted; coincidentSegmentCount=\\d+"
                + " > 2 trigger; heuristic for connectionCount=\\d+ "
                + "=> target=\\d+; currentSpacingPx=\\d+ => delta=\\d+";
        assertTrue("reason matches documented format pattern: <" + d.reason() + ">",
                d.reason().matches(regex));
    }

    // -------- Hub-aware tier integration --------
    // The decision record stays pure-unit — hasLargeHubs is a CALLER-PROVIDED
    // input, not derived inside the record. These pins verify the caller-
    // selected branch lands the hub-aware tier (or doesn't, when hasLargeHubs
    // is false).

    @Test
    public void hubAware_fires_whenCallerPassesHasLargeHubsTrue() {
        // Trigger fired (coincSeg=11>2), caller signals large hub present.
        // Heuristic must return hub-aware tier 2 (N=20 → 100px) instead of
        // no-hubs tier 2 (80px). Delta = max(0, 100 - 40) = 60 (vs 40).
        AdjustViewSpacingDefaultResolutionDecision d =
                AdjustViewSpacingDefaultResolutionDecision.decide(
                        /*callerProvidedDelta=*/ null,
                        /*coincidentSegmentCount=*/ 11,
                        /*connectionEdgeCoincidenceCount=*/ 0,
                        /*connectionCount=*/ 20,
                        /*currentSpacingPx=*/ 40,
                        /*hasGroups=*/ true,
                        /*hasGroupWithMultipleChildren=*/ true,
                        /*hasLargeHubs=*/ true);
        assertTrue("trigger fires regardless of hub-aware flag", d.fired());
        // Hub-aware tier 2 = 100px, currentSpacing=40, delta=60.
        assertEquals("hub-aware tier 2 delta", 60, d.resolvedDelta());
        assertNotNull(d.reason());
        // Reason mentions target=100 (hub-aware), not 80 (no-hubs).
        assertTrue("reason must reflect hub-aware tier target=100: <"
                + d.reason() + ">",
                d.reason().contains("target=100"));
    }

    @Test
    public void hubAware_doesNotFire_whenCallerPassesHasLargeHubsFalse() {
        // Identical trigger context; caller signals NO large hubs. Heuristic
        // returns no-hubs tier 2 (N=20 → 80px). Delta = max(0, 80 - 40) = 40.
        // Pre-Row-C semantics preserved exactly when hasLargeHubs=false.
        AdjustViewSpacingDefaultResolutionDecision d =
                AdjustViewSpacingDefaultResolutionDecision.decide(
                        /*callerProvidedDelta=*/ null,
                        /*coincidentSegmentCount=*/ 11,
                        /*connectionEdgeCoincidenceCount=*/ 0,
                        /*connectionCount=*/ 20,
                        /*currentSpacingPx=*/ 40,
                        /*hasGroups=*/ true,
                        /*hasGroupWithMultipleChildren=*/ true,
                        /*hasLargeHubs=*/ false);
        assertTrue(d.fired());
        // No-hubs tier 2 = 80px, delta=40 (back-compat: identical to pre-Row-C).
        assertEquals("no-hubs tier 2 delta (back-compat)",
                40, d.resolvedDelta());
        assertTrue("reason must reflect no-hubs tier target=80: <"
                + d.reason() + ">",
                d.reason().contains("target=80"));
    }

    @Test
    public void hubAware_tier1_landsAt80_notDefault60() {
        // Edge: tier 1 connection count + hub present → hub-aware = 80px
        // (vs no-hubs 60px). Tier 1 is where the smallest hub-aware tier
        // delta lands.
        AdjustViewSpacingDefaultResolutionDecision d =
                AdjustViewSpacingDefaultResolutionDecision.decide(
                        /*callerProvidedDelta=*/ null,
                        /*coincidentSegmentCount=*/ 5,
                        /*connectionEdgeCoincidenceCount=*/ 0,
                        /*connectionCount=*/ 10,
                        /*currentSpacingPx=*/ 30,
                        /*hasGroups=*/ true,
                        /*hasGroupWithMultipleChildren=*/ true,
                        /*hasLargeHubs=*/ true);
        assertTrue(d.fired());
        // Hub-aware tier 1 = 80px, currentSpacing=30 → delta=50.
        assertEquals(50, d.resolvedDelta());
        assertTrue(d.reason().contains("target=80"));
    }
}
