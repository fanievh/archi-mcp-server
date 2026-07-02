package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import net.vheerden.archi.mcp.model.routing.RoutingResult;
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

        AutoRouteWarnings.emitEgressLiftLayoutBound(2, warnings, structured);

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

        AutoRouteWarnings.emitEgressLiftLayoutBound(0, warnings, structured);

        assertTrue("no free-text warning when nothing rolled back", warnings.isEmpty());
        assertTrue("no structured warning when nothing rolled back", structured.isEmpty());
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
}
