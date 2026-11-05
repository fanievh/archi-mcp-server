package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import net.vheerden.archi.mcp.model.routing.EdgeAttachmentCalculator;
import net.vheerden.archi.mcp.model.routing.EgressOutcome;
import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.model.routing.RoutingResult;
import net.vheerden.archi.mcp.model.routing.TerminalEgressClearancePass;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.NudgedElementDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;

/**
 * Headless coverage for the layout-bound egress-lift surface (auto-route-connections). The routing
 * pass rolls back an off-face lift when applying it would narrow a parallel-connection gap below the
 * healthy floor; this suite pins that the rolled count is carried on {@link RoutingResult} and that
 * {@link AutoRouteWarnings} surfaces it as a coded, machine-parseable warning.
 */
public class AutoRouteWarningsTest {

    @Test
    public void emitEgressLiftLayoutBound_whenRolledPositive_addsCodedWarningNamingCountAndFloor() {
        List<String> warnings = new ArrayList<>();
        List<StructuredWarningDto> structured = new ArrayList<>();

        AutoRouteWarnings.emitEgressLiftLayoutBound(2, DispatchArm.APPLIED, warnings, structured);

        assertEquals("exactly one structured warning is emitted", 1, structured.size());
        assertEquals("carries the stable layout-bound code",
                StructuredWarningCodes.EGRESS_LIFT_LAYOUT_BOUND, structured.get(0).code());
        assertTrue("message names the rolled-back count",
                structured.get(0).message().contains("2"));
        assertTrue("message names the 15px parallel-gap floor",
                structured.get(0).message().contains("15px"));
        assertTrue("remediation points at a spacing lever, not a re-route",
                structured.get(0).remediationTool().contains("spacing"));
        assertEquals("the free-text warning mirrors the structured message", 1, warnings.size());
        assertEquals(structured.get(0).message(), warnings.get(0));
    }

    @Test
    public void emitEgressLiftLayoutBound_whenNoneRolled_emitsNothing() {
        List<String> warnings = new ArrayList<>();
        List<StructuredWarningDto> structured = new ArrayList<>();

        AutoRouteWarnings.emitEgressLiftLayoutBound(0, DispatchArm.APPLIED, warnings, structured);

        assertTrue("no free-text warning when nothing rolled back", warnings.isEmpty());
        assertTrue("no structured warning when nothing rolled back", structured.isEmpty());
    }

    // ==================== pre-gated egress lift ====================

    private static List<StructuredWarningDto> preGated(EgressOutcome outcome, List<String> warnings) {
        List<StructuredWarningDto> structured = new ArrayList<>();
        AutoRouteWarnings.emitEgressOutcome(outcome, DispatchArm.APPLIED, warnings, structured);
        return structured;
    }

    @Test
    public void emitEgressOutcome_preGatedWithHugs_namesCountGapFloorAndEveryHuggingConnection() {
        List<String> warnings = new ArrayList<>();
        List<StructuredWarningDto> structured =
                preGated(new EgressOutcome(0, 7.0, List.of("c-a", "c-b", "c-c")), warnings);

        assertEquals(1, structured.size());
        StructuredWarningDto w = structured.get(0);
        assertEquals(StructuredWarningCodes.EGRESS_LIFT_PRE_GATED, w.code());
        assertTrue(w.message(), w.message().startsWith("3 connection(s) still hug the face they leave"));
        assertTrue(w.message(), w.message().contains("10th-percentile gap 7.0px, below the 8px floor"));
        assertTrue("the gap is dated to the lift pass, not claimed for the routes as written",
                w.message().contains("when it reached that pass, its vertical connection runs sat too close"));
        assertFalse("the gap is not measured on the routes the call writes (a later stage moves ports)",
                w.message().contains("as routed by this call"));
        assertEquals("apply-spacing-recommendations", w.remediationTool());
        assertEquals("every hugging connection, in order", List.of("c-a", "c-b", "c-c"),
                w.remediationViolatorIds());
        assertEquals(List.of(w.message()), warnings);
    }

    @Test
    public void emitEgressOutcome_preGatedWithoutHugs_emitsNothing() {
        List<String> warnings = new ArrayList<>();
        assertTrue(preGated(new EgressOutcome(0, 5.0, List.of()), warnings).isEmpty());
        assertTrue(warnings.isEmpty());
    }

    @Test
    public void emitEgressOutcome_passRan_emitsNoPreGatedWarning() {
        List<String> warnings = new ArrayList<>();
        assertTrue(preGated(new EgressOutcome(0, null, List.of()), warnings).isEmpty());
        assertTrue(preGated(EgressOutcome.NONE, warnings).isEmpty());
        assertTrue(preGated(null, warnings).isEmpty());
    }

    @Test
    public void emitEgressOutcome_rolledBack_emitsOnlyTheLayoutBoundCode() {
        List<StructuredWarningDto> structured = preGated(new EgressOutcome(2, null, List.of()), new ArrayList<>());
        assertEquals(1, structured.size());
        assertEquals(StructuredWarningCodes.EGRESS_LIFT_LAYOUT_BOUND, structured.get(0).code());
    }

    /** A gap just under the floor never prints as the floor; zero never prints as -0.0. */
    @Test
    public void emitEgressOutcome_printsTheGapRoundedDown_andNeverNegativeZero() {
        assertTrue(preGated(new EgressOutcome(0, 7.96, List.of("c")), new ArrayList<>()).get(0).message()
                .contains("gap 7.9px"));
        assertTrue(preGated(new EgressOutcome(0, 6.9999999999, List.of("c")), new ArrayList<>()).get(0).message()
                .contains("gap 7.0px"));
        String justUnder = preGated(new EgressOutcome(0, 7.99999995, List.of("c")), new ArrayList<>()).get(0)
                .message();
        assertTrue("a skipped gap a hair under the floor must not print as the floor: " + justUnder,
                justUnder.contains("gap 7.9px, below the 8px floor"));
        assertTrue(preGated(new EgressOutcome(0, 5.6000000000000005, List.of("c")), new ArrayList<>()).get(0).message()
                .contains("gap 5.6px"));
        String zero = preGated(new EgressOutcome(0, -0.0, List.of("c")), new ArrayList<>()).get(0).message();
        assertTrue(zero, zero.contains("gap 0.0px"));
        assertFalse(zero, zero.contains("-0.0"));
    }

    /**
     * The printed gap is the value the pass's pre-gate compared: a real skip on geometry built to
     * measure 7.0 is carried through RoutingResult and emitted, and the message prints 7.0.
     */
    @Test
    public void preGatedWarning_printsTheGapThePassCompared_endToEndFromARealSkip() {
        RoutingRect s = new RoutingRect(100, 100, 80, 60, "S");
        RoutingRect t = new RoutingRect(340, 300, 80, 50, "T");
        int face = EdgeAttachmentCalculator.faceLineCoordinate(s, EdgeAttachmentCalculator.Face.BOTTOM);
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        paths.add(new ArrayList<>(List.of(new AbsoluteBendpointDto(140, face),
                new AbsoluteBendpointDto(360, face), new AbsoluteBendpointDto(360, 300))));
        paths.add(new ArrayList<>(List.of(new AbsoluteBendpointDto(367, face), new AbsoluteBendpointDto(367, 300))));
        TerminalEgressClearancePass.Result result = new TerminalEgressClearancePass().run(
                List.of(new RoutingPipeline.ConnectionEndpoints("c1", s, t, List.of(), null, 0),
                        new RoutingPipeline.ConnectionEndpoints("c2", s, t, List.of(), null, 0)),
                paths, List.of(s, t));
        assertTrue("precondition: the pass was skipped", result.skippedByPreGate());
        RoutingResult routing = new RoutingResult(Map.of(), List.of(), List.of(), Map.of(), 0, Map.of(), 0,
                result.rolled(), null, result.outcome());

        List<StructuredWarningDto> structured = preGated(routing.egress(), new ArrayList<>());

        assertEquals(1, structured.size());
        assertTrue(structured.get(0).message(), structured.get(0).message().contains("gap 7.0px"));
        assertEquals(List.of("c1"), structured.get(0).remediationViolatorIds());
    }

    @Test(expected = IllegalArgumentException.class)
    public void routingResult_refusesAnEgressOutcomeThatDisagreesWithTheRolledCount() {
        new RoutingResult(Map.of(), List.of(), List.of(), Map.of(), 0, Map.of(), 0, 3, null,
                new EgressOutcome(2, null, List.of()));
    }

    @Test
    public void routingResult_defaultsTheEgressOutcomeToTheRolledCount() {
        RoutingResult r = new RoutingResult(Map.of(), List.of(), List.of(), Map.of(), 0, Map.of(), 0, 3, null);
        assertEquals(3, r.egress().rolledBack());
        assertFalse(r.egress().preGated());
    }

    @Test
    public void emitConnectionsNotFound_whenIdsMissing_addsCodedWarningCarryingViolatorIds() {
        List<String> warnings = new ArrayList<>();
        List<StructuredWarningDto> structured = new ArrayList<>();

        AutoRouteWarnings.emitConnectionsNotFound(List.of("bogus-1", "bogus-2"), DispatchArm.APPLIED, warnings, structured);

        assertEquals("one aggregate structured warning regardless of how many IDs missed",
                1, structured.size());
        assertEquals("carries the stable connection-not-found code",
                StructuredWarningCodes.CONNECTION_NOT_FOUND, structured.get(0).code());
        assertEquals("every missing ID is machine-readable, not just free text",
                List.of("bogus-1", "bogus-2"), structured.get(0).remediationViolatorIds());
        assertEquals("remediation points at the tool that lists real IDs, not the one that missed",
                "get-view-contents", structured.get(0).remediationTool());
        assertEquals("the per-ID free-text surface is preserved verbatim, one line per ID",
                List.of("Connection not found on view: bogus-1",
                        "Connection not found on view: bogus-2"),
                warnings);
    }

    @Test
    public void emitConnectionsNotFound_whenNothingMissing_emitsNothing() {
        List<String> warnings = new ArrayList<>();
        List<StructuredWarningDto> structured = new ArrayList<>();

        AutoRouteWarnings.emitConnectionsNotFound(List.of(), DispatchArm.APPLIED, warnings, structured);

        assertTrue("no free-text warning when every ID resolved", warnings.isEmpty());
        assertTrue("no structured warning when every ID resolved", structured.isEmpty());
    }

    @Test
    public void emitConnectionsNotFound_doesNotDisturbUnrelatedWarnings() {
        // The connection-not-found code must be the ONLY thing that identifies this condition —
        // an unrelated warning already in the list must not gain or lose a code because of it.
        List<String> warnings = new ArrayList<>(List.of("Routing produced 17 crossings vs 6."));
        List<StructuredWarningDto> structured = new ArrayList<>();

        AutoRouteWarnings.emitConnectionsNotFound(List.of(), DispatchArm.APPLIED, warnings, structured);

        assertEquals("an unrelated free-text warning is left untouched", 1, warnings.size());
        assertTrue("and it does not acquire a connection-not-found code", structured.isEmpty());
    }

    @Test
    public void routingResult_egressRolledBack_defaultsToZeroInBackCompatConstructors() {
        assertEquals("3-arg ctor defaults egressRolledBack to 0",
                0, new RoutingResult(Map.of(), List.of(), List.of()).egressRolledBack());
        assertEquals("4-arg ctor defaults egressRolledBack to 0",
                0, new RoutingResult(Map.of(), List.of(), List.of(), Map.of()).egressRolledBack());
        assertEquals("6-arg ctor defaults egressRolledBack to 0",
                0, new RoutingResult(Map.of(), List.of(), List.of(), Map.of(), 0, Map.of())
                        .egressRolledBack());
    }

    @Test
    public void routingResult_canonicalConstructor_carriesEgressRolledBack_diagnosticOnly() {
        RoutingResult r = new RoutingResult(Map.of(), List.of(), List.of(), Map.of(), 0, Map.of(), 0, 4);
        assertEquals("canonical ctor carries the rolled-back count", 4, r.egressRolledBack());
        assertTrue("the diagnostic field does not disturb the routed geometry", r.routed().isEmpty());
        assertFalse("straightLineCrossings and egressRolledBack are distinct fields",
                r.straightLineCrossings() == 4);
    }

    @Test
    public void emitNetZeroNudge_whenAnElementEndedWhereItStarted_addsCodedWarningNamingIt() {
        List<String> warnings = new ArrayList<>();
        List<StructuredWarningDto> structured = new ArrayList<>();

        AutoRouteWarnings.emitNetZeroNudge(
                List.of(new NudgedElementDto("vo-9", "internal API GW", 0, 0)),
                DispatchArm.APPLIED, warnings, structured);

        assertEquals("cardinality is one entry per call, however many elements netted to zero",
                1, structured.size());
        assertEquals("carries the stable net-zero code",
                StructuredWarningCodes.AUTO_NUDGE_NET_ZERO, structured.get(0).code());
        assertTrue("message names the element",
                structured.get(0).message().contains("internal API GW"));
        assertTrue("message names the element id",
                structured.get(0).message().contains("vo-9"));
        assertEquals("the ids are carried structurally so callers need not parse the prose",
                List.of("vo-9"), structured.get(0).remediationViolatorIds());
        assertEquals("the free-text warning mirrors the structured message verbatim",
                1, warnings.size());
        assertEquals(structured.get(0).message(), warnings.get(0));
    }

    @Test
    public void emitNetZeroNudge_remediationIsALayoutLeverNotTheEmittingTool() {
        List<String> warnings = new ArrayList<>();
        List<StructuredWarningDto> structured = new ArrayList<>();

        AutoRouteWarnings.emitNetZeroNudge(
                List.of(new NudgedElementDto("vo-9", "El", 0, 0)),
                DispatchArm.APPLIED, warnings, structured);

        assertFalse("re-running the tool that reported the net-zero reproduces it",
                "auto-route-connections".equals(structured.get(0).remediationTool()));
        // Pinned to the EXACT published name, not a substring. README, CHANGELOG,
        // docs/routing-pipeline.md and the shipped routing-preconditions checklist all name this
        // one tool, and an agent keys off it verbatim. A substring check passes when the emitter is
        // repointed at any other tool with "spacing" in its name — adjust-view-spacing and
        // apply-group-spacing-recommendations both qualify — while every one of those surfaces
        // silently becomes a lie. The emitter's own constant is private, so the literal here is the
        // only thing that can hold the published contract.
        assertEquals("remediation names the exact tool every published surface promises",
                "apply-spacing-recommendations", structured.get(0).remediationTool());
    }

    @Test
    public void emitNetZeroNudge_statesTheObservationAndNotAnUnverifiedMechanism() {
        List<String> warnings = new ArrayList<>();
        List<StructuredWarningDto> structured = new ArrayList<>();

        AutoRouteWarnings.emitNetZeroNudge(
                List.of(new NudgedElementDto("vo-9", "El", 0, 0)),
                DispatchArm.APPLIED, warnings, structured);

        String message = structured.get(0).message();
        assertTrue("states the verified outcome: the element ended where it started",
                message.contains("starting position"));
        assertTrue("declares that the two producers of a zero are not distinguished",
                message.contains("does not distinguish"));
    }

    @Test
    public void emitNetZeroNudge_whenSeveralNettedToZero_namesEveryOneInOneEntry() {
        List<String> warnings = new ArrayList<>();
        List<StructuredWarningDto> structured = new ArrayList<>();

        AutoRouteWarnings.emitNetZeroNudge(
                List.of(new NudgedElementDto("vo-1", "Alpha", 0, 0),
                        new NudgedElementDto("vo-2", "Beta", 0, 0)),
                DispatchArm.APPLIED, warnings, structured);

        assertEquals("still one entry", 1, structured.size());
        assertTrue(structured.get(0).message().contains("Alpha"));
        assertTrue(structured.get(0).message().contains("Beta"));
        assertEquals("every id is carried, never truncated",
                List.of("vo-1", "vo-2"), structured.get(0).remediationViolatorIds());
    }

    @Test
    public void emitNetZeroNudge_whenEveryElementMoved_emitsNothing() {
        List<String> warnings = new ArrayList<>();
        List<StructuredWarningDto> structured = new ArrayList<>();

        AutoRouteWarnings.emitNetZeroNudge(List.of(), DispatchArm.APPLIED, warnings, structured);

        assertTrue("no free-text warning when nothing netted to zero", warnings.isEmpty());
        assertTrue("no structured warning when nothing netted to zero", structured.isEmpty());
    }
}
