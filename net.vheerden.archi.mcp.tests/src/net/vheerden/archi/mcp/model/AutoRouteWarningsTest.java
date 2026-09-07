package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import net.vheerden.archi.mcp.model.routing.RoutingResult;
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
