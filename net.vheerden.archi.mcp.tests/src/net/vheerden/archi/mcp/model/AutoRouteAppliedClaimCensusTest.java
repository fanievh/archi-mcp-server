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
            StructuredWarningCodes.AUTO_NUDGE_NET_ZERO, "ended at their starting position",
            StructuredWarningCodes.CONNECTION_ROUTED_THROUGH_NOTE, "were applied through it",
            StructuredWarningCodes.AUTO_ROUTE_CROSSINGS_REGRESSED, "were still applied");

    /**
     * The one code that is CORRECTLY arm-blind. The sibling-overlap skip is a decision the router
     * took while computing, on geometry that already exists on the view, so it happened on every
     * arm and a deferred tense for it would be false. It is in the census as an explicit exemption
     * rather than absent from it: a code missing from a census is indistinguishable from one nobody
     * has looked at yet.
     */
    private static final String ARM_BLIND_BY_DESIGN =
            StructuredWarningCodes.AUTO_NUDGE_SKIPPED_SIBLING_OVERLAP;

    @Test
    public void everyAppliedStateClaim_appearsOnTheAppliedArmAndOnNoOther() {
        Map<String, Map<DispatchArm, String>> census = census();

        assertEquals("every routing code must be in the census, exemption included",
                APPLIED_CLAIM_BY_CODE.size() + 1, census.size());

        for (Map.Entry<String, Map<DispatchArm, String>> row : census.entrySet()) {
            String code = row.getKey();
            Map<DispatchArm, String> byArm = row.getValue();
            assertEquals(code + " must be exercised on all three arms, or the row proves nothing",
                    3, byArm.size());

            if (ARM_BLIND_BY_DESIGN.equals(code)) {
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
            if (ARM_BLIND_BY_DESIGN.equals(row.getKey())) {
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

    /** Calls every emitter on every arm and returns what they produced. */
    private static Map<String, Map<DispatchArm, String>> census() {
        Map<String, Function<DispatchArm, String>> emitters = new LinkedHashMap<>();
        emitters.put(StructuredWarningCodes.CONNECTION_NOT_FOUND,
                arm -> AutoRouteWarningsProbe.connectionsNotFound(arm).structured().message());
        emitters.put(StructuredWarningCodes.EGRESS_LIFT_LAYOUT_BOUND,
                arm -> AutoRouteWarningsProbe.egressLiftLayoutBound(arm).structured().message());
        emitters.put(StructuredWarningCodes.AUTO_NUDGE_NET_ZERO,
                arm -> AutoRouteWarningsProbe.netZeroNudge(arm).structured().message());
        emitters.put(StructuredWarningCodes.CONNECTION_ROUTED_THROUGH_NOTE,
                arm -> AutoRouteWarningsProbe.connectionThroughNote(arm).structured().message());
        emitters.put(StructuredWarningCodes.AUTO_ROUTE_CROSSINGS_REGRESSED,
                arm -> AutoRouteWarningsProbe.crossingsRegressed(arm).structured().message());
        // No extractable emitter: composed inline on the accessor's autoNudge pre-gate, so the real
        // accessor is the only thing that can produce it.
        emitters.put(ARM_BLIND_BY_DESIGN, AutoRouteAppliedClaimCensusTest::overlapSkipMessage);

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
