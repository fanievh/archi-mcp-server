package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.Test;

import net.vheerden.archi.mcp.model.AutoRouteArmFixture.Arm;
import net.vheerden.archi.mcp.response.dto.AutoLayoutAndRouteResultDto;
import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;

/**
 * A census over every routing code on every arm, rather than a hand-written list of cases.
 *
 * <p>The enumerated suites are the reason five of these six codes drifted while four were guarded:
 * each one covers the cases somebody thought to write down, so a code nobody listed is silently
 * outside the denominator. {@code auto-layout-and-route} has a census for exactly this reason and
 * auto-route did not.</p>
 *
 * <p>The rule is one sentence: <strong>a message may assert that this call's routing is in the
 * model only on the arm where it is.</strong> It is enforced by CALLING the six emitters over the
 * whole (code × arm) grid and reading what they return — never by comparing against a committed
 * list of strings, which would only prove the list agrees with itself.</p>
 *
 * <p>The grid is closed at both ends. Every code must appear (so an emitter cannot leave the
 * denominator by producing nothing), and every code must be exercised on all three arms (so a
 * one-armed harness cannot pass by never asking the awkward question).</p>
 */
public class AutoRouteAppliedClaimCensusTest {

    /**
     * Phrases that assert this call's routing is already in the model.
     *
     * <p>Each is a fragment of an applied-arm message an emitter really produces, and the census
     * proves that by requiring every one of them to be FOUND on the applied arm. A banned phrase no
     * emitter ever writes bans nothing; a list that is only ever checked negatively cannot tell the
     * difference.</p>
     */
    private static final Map<String, String> APPLIED_CLAIM_BY_CODE = Map.of(
            StructuredWarningCodes.CONNECTION_NOT_FOUND, "were routed normally",
            StructuredWarningCodes.EGRESS_LIFT_LAYOUT_BOUND, "kept the hug(s) in place",
            StructuredWarningCodes.EGRESS_LIFT_PRE_GATED, "left the hug(s) in place",
            StructuredWarningCodes.AUTO_NUDGE_NET_ZERO, "ended at their starting position",
            StructuredWarningCodes.CONNECTION_ROUTED_THROUGH_NOTE, "were applied through it",
            StructuredWarningCodes.AUTO_ROUTE_CROSSINGS_REGRESSED, "were still applied",
            StructuredWarningCodes.SELF_CONNECTION_NOT_ROUTED, "were given Archi's default loop");

    /**
     * The codes that are CORRECTLY arm-blind. The sibling-overlap skip is a decision the router
     * took while computing, on geometry that already exists on the view, so it happened on every
     * arm and a deferred tense for it would be false. A move recommendation withheld because the
     * parent would absorb it is the same kind of decision: it describes a recommendation that was
     * not offered, never a move that was or was not made. They are in the census as explicit
     * exemptions rather than absent from it: a code missing from a census is indistinguishable from
     * one nobody has looked at yet.
     */
    private static final String ARM_BLIND_BY_DESIGN =
            StructuredWarningCodes.AUTO_NUDGE_SKIPPED_SIBLING_OVERLAP;

    private static final java.util.Set<String> ARM_BLIND_CODES = java.util.Set.of(
            ARM_BLIND_BY_DESIGN, StructuredWarningCodes.MOVE_RECOMMENDATION_WITHHELD_BY_PARENT);

    @Test
    public void everyAppliedStateClaim_appearsOnTheAppliedArmAndOnNoOther() {
        Map<String, Map<DispatchArm, String>> census = census();

        assertEquals("every routing code must be in the census, exemptions included",
                APPLIED_CLAIM_BY_CODE.size() + ARM_BLIND_CODES.size(), census.size());

        for (Map.Entry<String, Map<DispatchArm, String>> row : census.entrySet()) {
            String code = row.getKey();
            Map<DispatchArm, String> byArm = row.getValue();
            assertEquals(code + " must be exercised on all three arms, or the row proves nothing",
                    3, byArm.size());

            if (ARM_BLIND_CODES.contains(code)) {
                assertEquals(code + " is a compute-time decision and reads the same on every arm",
                        byArm.get(DispatchArm.APPLIED), byArm.get(DispatchArm.QUEUED));
                assertEquals(code + " on the third arm too",
                        byArm.get(DispatchArm.APPLIED), byArm.get(DispatchArm.AWAITING_APPROVAL));
                continue;
            }

            String claim = APPLIED_CLAIM_BY_CODE.get(code);
            assertTrue("no claim registered for " + code + " — a new code must be classified here, "
                    + "not left out of the denominator", claim != null);
            assertTrue(code + " must actually make its applied-state claim on the applied arm, or "
                    + "the ban below bans a phrase nothing writes:\n"
                    + byArm.get(DispatchArm.APPLIED),
                    byArm.get(DispatchArm.APPLIED).contains(claim));

            for (DispatchArm arm : List.of(DispatchArm.QUEUED, DispatchArm.AWAITING_APPROVAL)) {
                assertTrue(code + " asserts applied state on " + arm + ", where this call's routing "
                        + "is not in the model:\n" + byArm.get(arm),
                        !byArm.get(arm).contains(claim));
                assertTrue(code + " on " + arm + " must say plainly that nothing has been applied:\n"
                        + byArm.get(arm),
                        byArm.get(arm).contains("Nothing has been applied"));
            }
        }
    }

    @Test
    public void noRoutingMessage_onADeferredArm_usesTheAppliedTenseOfAnyOtherCode() {
        // Cross-code, and the half a per-code rule cannot see: a message rescoped by copying a
        // sibling's arm clause can drop its OWN applied claim while picking up somebody else's.
        Map<String, Map<DispatchArm, String>> census = census();
        for (Map.Entry<String, Map<DispatchArm, String>> row : census.entrySet()) {
            if (ARM_BLIND_CODES.contains(row.getKey())) {
                continue;
            }
            for (DispatchArm arm : List.of(DispatchArm.QUEUED, DispatchArm.AWAITING_APPROVAL)) {
                String message = row.getValue().get(arm);
                for (Map.Entry<String, String> claim : APPLIED_CLAIM_BY_CODE.entrySet()) {
                    assertTrue(row.getKey() + " on " + arm + " carries " + claim.getKey()
                            + "'s applied-state claim \"" + claim.getValue() + "\":\n" + message,
                            !message.contains(claim.getValue()));
                }
            }
        }
    }

    /**
     * The layout tools name their self-connections in one wording on every arm, so it must make no
     * arm's claim: not the applied arm's "given Archi's default loop", which on a deferred arm has
     * not happened, and not "left exactly as stored", which in flat mode would describe the loop
     * the layout drew, not the caller's.
     */
    @Test
    public void layoutSelfConnectionWording_claimsNoArmsState_andIsAbsentWithNoSelfConnection() {
        AutoLayoutAndRouteResultDto dto = new AutoLayoutAndRouteResultDto("v", "DOWN", 50, 0, 0, false, 0);
        assertTrue(AutoRouteWarnings.withSelfConnectionsKeptByLayout(dto, List.of()) == dto);

        List<StructuredWarningDto> warnings =
                AutoRouteWarnings.withSelfConnectionsKeptByLayout(dto, List.of("c1", "c2")).structuredWarnings();
        assertEquals(1, warnings.size());
        assertEquals(StructuredWarningCodes.SELF_CONNECTION_NOT_ROUTED, warnings.get(0).code());
        assertEquals(List.of("c1", "c2"), warnings.get(0).remediationViolatorIds());
        String message = warnings.get(0).message();
        for (String claim : APPLIED_CLAIM_BY_CODE.values()) {
            assertTrue("carries the applied claim \"" + claim + "\": " + message, !message.contains(claim));
        }
        assertTrue(message, !message.contains("as stored"));
        assertTrue(message, message.startsWith("2 self-connection(s) were not routed; each keeps its loop."));
    }

    /** Calls every emitter on every arm and returns what they produced. */
    private static Map<String, Map<DispatchArm, String>> census() {
        Map<String, Function<DispatchArm, String>> emitters = new LinkedHashMap<>();
        emitters.put(StructuredWarningCodes.CONNECTION_NOT_FOUND,
                arm -> AutoRouteWarningsProbe.connectionsNotFound(arm).structured().message());
        emitters.put(StructuredWarningCodes.EGRESS_LIFT_LAYOUT_BOUND,
                arm -> AutoRouteWarningsProbe.egressLiftLayoutBound(arm).structured().message());
        emitters.put(StructuredWarningCodes.EGRESS_LIFT_PRE_GATED,
                arm -> AutoRouteWarningsProbe.egressLiftPreGated(arm).structured().message());
        emitters.put(StructuredWarningCodes.AUTO_NUDGE_NET_ZERO,
                arm -> AutoRouteWarningsProbe.netZeroNudge(arm).structured().message());
        emitters.put(StructuredWarningCodes.CONNECTION_ROUTED_THROUGH_NOTE,
                arm -> AutoRouteWarningsProbe.connectionThroughNote(arm).structured().message());
        emitters.put(StructuredWarningCodes.AUTO_ROUTE_CROSSINGS_REGRESSED,
                arm -> AutoRouteWarningsProbe.crossingsRegressed(arm).structured().message());
        emitters.put(StructuredWarningCodes.SELF_CONNECTION_NOT_ROUTED,
                arm -> AutoRouteWarningsProbe.selfConnectionsNotRouted(arm).structured().message());
        // No extractable emitter: composed inline on the accessor's autoNudge pre-gate, so the real
        // accessor is the only thing that can produce it.
        emitters.put(ARM_BLIND_BY_DESIGN, AutoRouteAppliedClaimCensusTest::overlapSkipMessage);
        // Takes no arm at all: the screen decides from geometry before anything is dispatched.
        emitters.put(StructuredWarningCodes.MOVE_RECOMMENDATION_WITHHELD_BY_PARENT,
                arm -> withheldMoveMessage());

        Map<String, Map<DispatchArm, String>> census = new LinkedHashMap<>();
        for (Map.Entry<String, Function<DispatchArm, String>> emitter : emitters.entrySet()) {
            Map<DispatchArm, String> byArm = new LinkedHashMap<>();
            for (DispatchArm arm : DispatchArm.values()) {
                byArm.put(arm, emitter.getValue().apply(arm));
            }
            census.put(emitter.getKey(), byArm);
        }
        return census;
    }

    private static String withheldMoveMessage() {
        List<String> warnings = new ArrayList<>();
        List<StructuredWarningDto> structured = new ArrayList<>();
        AutoRouteWarnings.emitRecommendationsWithheld(List.of(new NudgeRecommendationScreen.Withheld(
                "e1", "Step", "g1", "Lane",
                java.util.EnumSet.of(NudgeRecommendationScreen.Floor.LEFT_PADDING))),
                warnings, structured);
        assertEquals(1, structured.size());
        return structured.get(0).message();
    }

    private static String overlapSkipMessage(DispatchArm arm) {
        AutoRouteResultDto dto = AutoRouteArmFixture.routeWithNudge(
                AutoRouteArmFixture.overlappingSiblings(), armFixture(arm));
        List<StructuredWarningDto> found = new ArrayList<>();
        for (StructuredWarningDto w : dto.structuredWarnings()) {
            if (ARM_BLIND_BY_DESIGN.equals(w.code())) {
                found.add(w);
            }
        }
        assertEquals("the fixture must trip the sibling-overlap pre-gate on " + arm,
                1, found.size());
        return found.get(0).message();
    }

    private static Arm armFixture(DispatchArm arm) {
        return switch (arm) {
            case APPLIED -> Arm.APPLIED;
            case QUEUED -> Arm.QUEUED;
            case AWAITING_APPROVAL -> Arm.AWAITING_APPROVAL;
        };
    }
}
