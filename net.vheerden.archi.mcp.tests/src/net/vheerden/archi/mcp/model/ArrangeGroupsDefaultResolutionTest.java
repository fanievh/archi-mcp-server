package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pure-unit JUnit pins for the density-aware default-resolution decision
 * function for inter-group spacing.
 *
 * <p>Mirrors the inter-element sibling's
 * {@link AdjustViewSpacingDefaultResolutionTest} discipline and the
 * convenience-tool sibling's {@code ApplyGroupSpacingRecommendationsToolTest}
 * heuristic pins: heuristic-tier pin + trigger-condition pin + branch-coverage
 * pins on the pure-unit decision record, all runnable without an OSGi context
 * (no {@link ArchiModelAccessorImpl} class-loading required).</p>
 *
 * <p>Coverage map (sub-tests):</p>
 * <ul>
 *   <li>heuristic-tier pin (6 assertions on
 *       {@link GroupSpacingHeuristic#targetSpacingForConnectionCount(int, boolean, boolean)};
 *       3 connected + 3 unconnected boundary cases).</li>
 *   <li>trigger-condition pin (4 assertions covering
 *       all isConnected/connections branches).</li>
 *   <li>null-vs-explicit-zero-vs-explicit-non-zero behavioural
 *       distinction (3 paired fixtures sharing identical view state).</li>
 *   <li>trigger-fires happy path.</li>
 *   <li>single-group + 0-group short-circuits.</li>
 *   <li>unconnected-view no-fire.</li>
 *   <li>zero-connections degenerate.</li>
 *   <li>decision-record-style pin (this entire class IS the
 *       deliverable — pure-unit branch coverage on
 *       {@link ArrangeGroupsDefaultResolutionDecision}).</li>
 *   <li>heuristic-cross-class consistency (one assertion shared
 *       with {@code ApplyGroupSpacingRecommendationsToolTest} fixtures via
 *       {@link GroupSpacingHeuristic} single source of truth).</li>
 *   <li>reason-format pin (regex match on canonical fixture's
 *       {@code defaultResolutionReason}).</li>
 * </ul>
 *
 * <p>NO {@code @Ignore} and NO {@code Assume.*} runtime skips; every test
 * MUST run green at HEAD.</p>
 */
public class ArrangeGroupsDefaultResolutionTest {

    // -------- Heuristic-tier pin --------
    // Cross-class duplication with ApplyGroupSpacingRecommendationsToolTest
    // is intentional defence-in-depth per the Heuristic-table reuse note:
    // editing the boundaries here requires updates across FOUR pin classes
    // (markdown + utility + this test + sibling test).

    @Test
    public void heuristic_connected_le15_returns80() {
        assertEquals(80, GroupSpacingHeuristic
                .targetSpacingForConnectionCount(10, true, false));
    }

    @Test
    public void heuristic_connected_16to30_returns100() {
        assertEquals(100, GroupSpacingHeuristic
                .targetSpacingForConnectionCount(20, true, false));
    }

    @Test
    public void heuristic_connected_above30_returns120() {
        assertEquals(120, GroupSpacingHeuristic
                .targetSpacingForConnectionCount(40, true, false));
    }

    @Test
    public void heuristic_unconnected_le15_returns40() {
        assertEquals(40, GroupSpacingHeuristic
                .targetSpacingForConnectionCount(10, false, false));
    }

    @Test
    public void heuristic_unconnected_16to30_returns40() {
        assertEquals(40, GroupSpacingHeuristic
                .targetSpacingForConnectionCount(20, false, false));
    }

    @Test
    public void heuristic_unconnected_above30_returns60() {
        assertEquals(60, GroupSpacingHeuristic
                .targetSpacingForConnectionCount(40, false, false));
    }

    // -------- Trigger-condition boundary pin --------
    // Trigger fires when isConnected == true. Boundary cases protect against
    // drift between this decision function and the documented behaviour.

    @Test
    public void trigger_isConnectedFalse_doesNotFire() {
        ArrangeGroupsDefaultResolutionDecision d =
                ArrangeGroupsDefaultResolutionDecision.decide(
                        /*callerProvidedSpacing=*/ null,
                        /*connectionCount=*/ 20,
                        /*interGroupConnectionCount=*/ 0,
                        /*isConnected=*/ false,
                        /*hasAtLeast2TopLevelGroups=*/ true,
                        /*hasLargeHubs=*/ false);
        assertFalse("isConnected=false MUST NOT fire (Model B)", d.fired());
        assertEquals(40, d.resolvedSpacing());
        assertEquals(ArrangeGroupsDefaultResolutionDecision.TriggerCondition.NONE,
                d.triggerCondition());
        assertNotNull("informational reason populated for transparency",
                d.reason());
        // The reason is read by an agent that cannot see the canvas, and this branch fires on a
        // view that may hold MANY connections — the sample above has 20 — none of which run
        // directly between the arranged containers. Measured 2026-08-14: a run took the older
        // wording ("view has no inter-group connections") to mean the view had no connections at
        // all, on a view with 33. So the reason must not be sayable as "no connections", and must
        // say what was actually counted.
        String reason = d.reason();
        assertTrue("must say connections between the CONTAINERS are what is missing, not "
                        + "connections generally. Was: " + reason,
                reason.contains("crosses between the arranged containers"));
        // The clause naming which connections count used to have both cases the wrong way round:
        // it said a connection between two containers' nested children was NOT counted and
        // implied a connection running directly between the containers was. Measured on two
        // fixtures — two zones with one child each and one child-to-child connection, and the
        // same two zones with one box-to-box connection — the route counts the first and not the
        // second. A caller reading the old sentence would have drawn a connection that could not
        // move this branch and left alone the one that would.
        assertTrue("must say what IS counted: a connection joining objects inside two different "
                        + "containers. Was: " + reason,
                reason.contains("objects inside two different containers"));
        assertTrue("must say what is NOT: a connection drawn between the container boxes "
                        + "themselves. Was: " + reason,
                reason.contains("between two container boxes is not counted"));
    }

    @Test
    public void trigger_isConnectedTrue_fires() {
        ArrangeGroupsDefaultResolutionDecision d =
                ArrangeGroupsDefaultResolutionDecision.decide(
                        /*callerProvidedSpacing=*/ null,
                        /*connectionCount=*/ 20,
                        /*interGroupConnectionCount=*/ 12,
                        /*isConnected=*/ true,
                        /*hasAtLeast2TopLevelGroups=*/ true,
                        /*hasLargeHubs=*/ false);
        assertTrue("isConnected=true (>0 connections) MUST fire", d.fired());
        assertEquals(ArrangeGroupsDefaultResolutionDecision.TriggerCondition.IS_CONNECTED,
                d.triggerCondition());
        assertEquals(12, d.triggerValue());
    }

    @Test
    public void trigger_isConnectedTrue_zeroConnectionsDegenerate_doesNotFire() {
        // Degenerate: caller passes isConnected=true but connectionCount=0.
        // Should hit the zero-connections short-circuit BEFORE the isConnected
        // check (per branch order: zero-connections wins).
        ArrangeGroupsDefaultResolutionDecision d =
                ArrangeGroupsDefaultResolutionDecision.decide(
                        /*callerProvidedSpacing=*/ null,
                        /*connectionCount=*/ 0,
                        /*interGroupConnectionCount=*/ 0,
                        /*isConnected=*/ true,
                        /*hasAtLeast2TopLevelGroups=*/ true,
                        /*hasLargeHubs=*/ false);
        assertFalse("zero-connections degenerate => no fire", d.fired());
        assertEquals(40, d.resolvedSpacing());
        assertNotNull(d.reason());
        assertTrue(d.reason().contains("no connections"));
    }

    @Test
    public void trigger_isConnectedFalse_unconnectedView_doesNotFire() {
        // Common case: connected=false despite connections existing
        // (intra-group only). Must take the unconnected-view no-fire branch.
        ArrangeGroupsDefaultResolutionDecision d =
                ArrangeGroupsDefaultResolutionDecision.decide(
                        /*callerProvidedSpacing=*/ null,
                        /*connectionCount=*/ 8,
                        /*interGroupConnectionCount=*/ 0,
                        /*isConnected=*/ false,
                        /*hasAtLeast2TopLevelGroups=*/ true,
                        /*hasLargeHubs=*/ false);
        assertFalse(d.fired());
        assertEquals(40, d.resolvedSpacing());
        assertEquals(ArrangeGroupsDefaultResolutionDecision.TriggerCondition.NONE,
                d.triggerCondition());
    }

    // -------- null-vs-explicit-zero-vs-explicit-non-zero --------
    // Paired fixtures sharing identical view state; only callerProvidedSpacing
    // differs (null vs 0 vs 40). Backwards-compat preservation pin.

    @Test
    public void fixtureA_callerOmitted_triggerFires_resolvesNonZero() {
        ArrangeGroupsDefaultResolutionDecision d =
                ArrangeGroupsDefaultResolutionDecision.decide(
                        /*callerProvidedSpacing=*/ null,   // OMITTED
                        /*connectionCount=*/ 24,
                        /*interGroupConnectionCount=*/ 12,
                        /*isConnected=*/ true,
                        /*hasAtLeast2TopLevelGroups=*/ true,
                        /*hasLargeHubs=*/ false);
        assertTrue("omitted + connected => fired", d.fired());
        assertEquals("connected 16-30 tier => target=100",
                100, d.resolvedSpacing());
        assertNotNull("default-resolution fired => reason populated",
                d.reason());
    }

    @Test
    public void fixtureB_callerExplicitZero_sameViewState_doesNotFire() {
        ArrangeGroupsDefaultResolutionDecision d =
                ArrangeGroupsDefaultResolutionDecision.decide(
                        /*callerProvidedSpacing=*/ 0,      // EXPLICIT ZERO
                        /*connectionCount=*/ 24,           // same view state
                        /*interGroupConnectionCount=*/ 12,
                        /*isConnected=*/ true,
                        /*hasAtLeast2TopLevelGroups=*/ true,
                        /*hasLargeHubs=*/ false);
        assertFalse("explicit 0 must NOT fire default-resolution", d.fired());
        assertEquals("explicit 0 passes through unchanged",
                0, d.resolvedSpacing());
        assertNull("explicit 0 => reason null (no default-resolution)",
                d.reason());
    }

    @Test
    public void fixtureC_callerExplicit40_sameViewState_doesNotFire() {
        // Fixture C: caller passes the static-default value (40) explicitly.
        // Must be preserved as 40, NOT silently overridden to the heuristic value.
        ArrangeGroupsDefaultResolutionDecision d =
                ArrangeGroupsDefaultResolutionDecision.decide(
                        /*callerProvidedSpacing=*/ 40,     // EXPLICIT 40
                        /*connectionCount=*/ 24,
                        /*interGroupConnectionCount=*/ 12,
                        /*isConnected=*/ true,
                        /*hasAtLeast2TopLevelGroups=*/ true,
                        /*hasLargeHubs=*/ false);
        assertFalse("explicit 40 must NOT fire default-resolution", d.fired());
        assertEquals("explicit 40 preserved as 40, not silently overridden",
                40, d.resolvedSpacing());
        assertNull(d.reason());
    }

    @Test
    public void callerExplicitArbitraryValue_passesThroughUnchanged() {
        ArrangeGroupsDefaultResolutionDecision d =
                ArrangeGroupsDefaultResolutionDecision.decide(
                        /*callerProvidedSpacing=*/ 137,
                        /*connectionCount=*/ 24,
                        /*interGroupConnectionCount=*/ 12,
                        /*isConnected=*/ true,
                        /*hasAtLeast2TopLevelGroups=*/ true,
                        /*hasLargeHubs=*/ false);
        assertFalse(d.fired());
        assertEquals("caller's value passes through", 137, d.resolvedSpacing());
        assertNull(d.reason());
    }

    // -------- Trigger-fires happy path --------
    // Fixture: connected view, connectionCount=24 (16-30 tier),
    // 12 inter-group connections. Heuristic => target=100 px.

    @Test
    public void triggerFiresHappyPath_connected16to30Tier() {
        ArrangeGroupsDefaultResolutionDecision d =
                ArrangeGroupsDefaultResolutionDecision.decide(
                        /*callerProvidedSpacing=*/ null,
                        /*connectionCount=*/ 24,
                        /*interGroupConnectionCount=*/ 12,
                        /*isConnected=*/ true,
                        /*hasAtLeast2TopLevelGroups=*/ true,
                        /*hasLargeHubs=*/ false);
        assertTrue(d.fired());
        assertEquals("expected target=100 px (connected 16-30 tier)",
                100, d.resolvedSpacing());
        assertEquals(ArrangeGroupsDefaultResolutionDecision.TriggerCondition.IS_CONNECTED,
                d.triggerCondition());
        assertEquals(12, d.triggerValue());
        // Reason content:
        assertNotNull(d.reason());
        assertTrue("reason mentions isConnected=true",
                d.reason().contains("isConnected=true"));
        assertTrue("reason mentions inter-group connection count 12",
                d.reason().contains("12 inter-group"));
        assertTrue("reason mentions heuristic computation target=100",
                d.reason().contains("target=100"));
        assertTrue("reason mentions connectionCount=24",
                d.reason().contains("connectionCount=24"));
        assertTrue("reason mentions connected column",
                d.reason().contains("connected column"));
    }

    @Test
    public void triggerFires_connectedLe15Tier_target80() {
        // Connected ≤15 tier
        ArrangeGroupsDefaultResolutionDecision d =
                ArrangeGroupsDefaultResolutionDecision.decide(
                        /*callerProvidedSpacing=*/ null,
                        /*connectionCount=*/ 12,
                        /*interGroupConnectionCount=*/ 6,
                        /*isConnected=*/ true,
                        /*hasAtLeast2TopLevelGroups=*/ true,
                        /*hasLargeHubs=*/ false);
        assertTrue(d.fired());
        assertEquals(80, d.resolvedSpacing());
    }

    @Test
    public void triggerFires_connectedAbove30Tier_target120() {
        // Connected >30 tier
        ArrangeGroupsDefaultResolutionDecision d =
                ArrangeGroupsDefaultResolutionDecision.decide(
                        /*callerProvidedSpacing=*/ null,
                        /*connectionCount=*/ 40,
                        /*interGroupConnectionCount=*/ 30,
                        /*isConnected=*/ true,
                        /*hasAtLeast2TopLevelGroups=*/ true,
                        /*hasLargeHubs=*/ false);
        assertTrue(d.fired());
        assertEquals(120, d.resolvedSpacing());
    }

    // -------- Single-group + 0-group degenerate --------

    @Test
    public void lessThan2TopLevelGroups_doesNotFire() {
        ArrangeGroupsDefaultResolutionDecision d =
                ArrangeGroupsDefaultResolutionDecision.decide(
                        /*callerProvidedSpacing=*/ null,
                        /*connectionCount=*/ 24,
                        /*interGroupConnectionCount=*/ 12,
                        /*isConnected=*/ true,
                        /*hasAtLeast2TopLevelGroups=*/ false,
                        /*hasLargeHubs=*/ false);   // single group
        assertFalse("fewer than 2 groups => no inter-group corridor", d.fired());
        assertEquals(40, d.resolvedSpacing());
        assertNotNull(d.reason());
        assertTrue(d.reason().contains("fewer than 2 top-level groups"));
    }

    // -------- No-trigger no-fire (legacy 40 short-circuit) --------
    // Unconnected view: omitted spacing falls through to the legacy
    // DEFAULT_ARRANGE_GROUPS_SPACING (40). Reason populated for transparency.

    @Test
    public void unconnectedView_noFire_legacyShortCircuit() {
        ArrangeGroupsDefaultResolutionDecision d =
                ArrangeGroupsDefaultResolutionDecision.decide(
                        /*callerProvidedSpacing=*/ null,
                        /*connectionCount=*/ 6,             // intra-group only
                        /*interGroupConnectionCount=*/ 0,
                        /*isConnected=*/ false,
                        /*hasAtLeast2TopLevelGroups=*/ true,
                        /*hasLargeHubs=*/ false);
        assertFalse(d.fired());
        assertEquals("legacy 40 default short-circuit", 40, d.resolvedSpacing());
        assertEquals(ArrangeGroupsDefaultResolutionDecision.TriggerCondition.NONE,
                d.triggerCondition());
        assertNotNull("informational reason for transparency", d.reason());
    }

    // -------- Connected + zero connections degenerate --------

    @Test
    public void zeroConnections_degenerateDoesNotFire() {
        // No connections AT ALL on the view (rare but valid: layout-only view).
        ArrangeGroupsDefaultResolutionDecision d =
                ArrangeGroupsDefaultResolutionDecision.decide(
                        /*callerProvidedSpacing=*/ null,
                        /*connectionCount=*/ 0,
                        /*interGroupConnectionCount=*/ 0,
                        /*isConnected=*/ false,
                        /*hasAtLeast2TopLevelGroups=*/ true,
                        /*hasLargeHubs=*/ false);
        assertFalse(d.fired());
        assertEquals(40, d.resolvedSpacing());
        assertNotNull(d.reason());
        assertTrue("zero-connections branch fires before isConnected branch",
                d.reason().contains("no connections"));
    }

    // -------- Heuristic-cross-class consistency --------
    // ONE assertion verifying that the heuristic value computed by THIS
    // decision function MATCHES the value that the convenience-tool
    // sibling's GroupSpacingHeuristic would return for the same inputs.
    // Multi-class tripwire: change either class's heuristic-resolution
    // logic and this test fails until they agree again.

    @Test
    public void heuristicCrossClassConsistency() {
        // Shared fixture: connectionCount=20, connected. GroupSpacingHeuristic
        // returns 100; this story's decision function MUST resolve to 100.
        int connectionCount = 20;
        int expectedTargetFromHeuristic = GroupSpacingHeuristic
                .targetSpacingForConnectionCount(connectionCount, true, false);
        assertEquals(100, expectedTargetFromHeuristic);

        ArrangeGroupsDefaultResolutionDecision thisDecision =
                ArrangeGroupsDefaultResolutionDecision.decide(
                        /*callerProvidedSpacing=*/ null,
                        connectionCount,
                        /*interGroupConnectionCount=*/ 12,
                        /*isConnected=*/ true,
                        /*hasAtLeast2TopLevelGroups=*/ true,
                        /*hasLargeHubs=*/ false);

        assertTrue(thisDecision.fired());
        assertEquals("decision function MUST agree with heuristic single source",
                expectedTargetFromHeuristic, thisDecision.resolvedSpacing());
    }

    // -------- Reason-format pin (canonical fixture) --------
    // Soft pin protects against accidental drift in the human-readable
    // transparency string. Intentional revision requires updating the regex.

    @Test
    public void reasonStringFormat_canonicalFixture() {
        ArrangeGroupsDefaultResolutionDecision d =
                ArrangeGroupsDefaultResolutionDecision.decide(
                        /*callerProvidedSpacing=*/ null,
                        /*connectionCount=*/ 24,
                        /*interGroupConnectionCount=*/ 12,
                        /*isConnected=*/ true,
                        /*hasAtLeast2TopLevelGroups=*/ true,
                        /*hasLargeHubs=*/ false);
        // Format: "spacing omitted; isConnected=true with <n> inter-group "
        //         "connection<s>; heuristic for connectionCount=<c> => "
        //         "target=<t> px (connected column)"
        String regex = "spacing omitted; isConnected=true with \\d+ "
                + "inter-group connection(s)?; heuristic for "
                + "connectionCount=\\d+ => target=\\d+ px \\(connected column\\)";
        assertTrue("reason matches documented format pattern: <"
                + d.reason() + ">", d.reason().matches(regex));
    }

    @Test
    public void reasonStringFormat_singularConnection() {
        ArrangeGroupsDefaultResolutionDecision d =
                ArrangeGroupsDefaultResolutionDecision.decide(
                        /*callerProvidedSpacing=*/ null,
                        /*connectionCount=*/ 5,
                        /*interGroupConnectionCount=*/ 1,    // singular
                        /*isConnected=*/ true,
                        /*hasAtLeast2TopLevelGroups=*/ true,
                        /*hasLargeHubs=*/ false);
        assertTrue(d.fired());
        // 1 inter-group connection (singular form, no "s")
        assertTrue(d.reason().contains("1 inter-group connection;"));
        assertFalse(d.reason().contains("1 inter-group connections;"));
    }

    // -------- Sanity: caller-provided always trumps any structural state --------

    @Test
    public void callerProvided_trumpsAllStructuralState() {
        // Even with no groups and no connections — caller-provided wins.
        ArrangeGroupsDefaultResolutionDecision d =
                ArrangeGroupsDefaultResolutionDecision.decide(
                        /*callerProvidedSpacing=*/ 200,
                        /*connectionCount=*/ 0,
                        /*interGroupConnectionCount=*/ 0,
                        /*isConnected=*/ false,
                        /*hasAtLeast2TopLevelGroups=*/ false,
                        /*hasLargeHubs=*/ false);
        assertFalse(d.fired());
        assertEquals(200, d.resolvedSpacing());
        assertNull(d.reason());
        assertEquals(ArrangeGroupsDefaultResolutionDecision.TriggerCondition.NONE,
                d.triggerCondition());
    }

    // -------- hub-aware tier integration --------
    // The decision record stays pure-unit — hasLargeHubs is a CALLER-PROVIDED
    // input. These pins verify the caller-selected branch lands the hub-aware
    // connected tier (or doesn't, when hasLargeHubs is false).

    @Test
    public void hubAware_fires_whenCallerPassesHasLargeHubsTrue() {
        // Connected + has large hub → hub-aware tier 2 (N=20 → 140px)
        // instead of no-hubs tier 2 (100px).
        ArrangeGroupsDefaultResolutionDecision d =
                ArrangeGroupsDefaultResolutionDecision.decide(
                        /*callerProvidedSpacing=*/ null,
                        /*connectionCount=*/ 20,
                        /*interGroupConnectionCount=*/ 8,
                        /*isConnected=*/ true,
                        /*hasAtLeast2TopLevelGroups=*/ true,
                        /*hasLargeHubs=*/ true);
        assertTrue(d.fired());
        // Hub-aware connected tier 2 = 140px.
        assertEquals("hub-aware connected tier 2", 140, d.resolvedSpacing());
        assertNotNull(d.reason());
        assertTrue("reason must reflect hub-aware tier target=140: <"
                + d.reason() + ">",
                d.reason().contains("target=140 px"));
    }

    @Test
    public void hubAware_doesNotFire_whenCallerPassesHasLargeHubsFalse() {
        // Identical context; caller signals NO large hubs. Heuristic returns
        // no-hubs connected tier 2 (100px). Pre-Row-C semantics preserved.
        ArrangeGroupsDefaultResolutionDecision d =
                ArrangeGroupsDefaultResolutionDecision.decide(
                        /*callerProvidedSpacing=*/ null,
                        /*connectionCount=*/ 20,
                        /*interGroupConnectionCount=*/ 8,
                        /*isConnected=*/ true,
                        /*hasAtLeast2TopLevelGroups=*/ true,
                        /*hasLargeHubs=*/ false);
        assertTrue(d.fired());
        // Connected no-hubs tier 2 = 100px (back-compat: identical to
        // pre-Row-C behaviour).
        assertEquals("connected no-hubs tier 2 (back-compat)",
                100, d.resolvedSpacing());
        assertTrue("reason must reflect no-hubs tier target=100: <"
                + d.reason() + ">",
                d.reason().contains("target=100 px"));
    }

    @Test
    public void hubAware_unconnected_doesNotFire_evenWhenHasLargeHubsTrue() {
        // Unconnected views always short-circuit to the static
        // DEFAULT_ARRANGE_GROUPS_SPACING (40) before reaching the heuristic
        // call — no hub-aware behaviour needed. Sibling-symmetric with the
        // GroupSpacingHeuristic invariant: hub-aware tier doesn't fire on
        // unconnected because there are no inter-group corridors.
        ArrangeGroupsDefaultResolutionDecision d =
                ArrangeGroupsDefaultResolutionDecision.decide(
                        /*callerProvidedSpacing=*/ null,
                        /*connectionCount=*/ 20,
                        /*interGroupConnectionCount=*/ 0,
                        /*isConnected=*/ false,
                        /*hasAtLeast2TopLevelGroups=*/ true,
                        /*hasLargeHubs=*/ true);
        assertFalse("unconnected branch fires before hub-aware logic",
                d.fired());
        assertEquals(40, d.resolvedSpacing());
    }
}
