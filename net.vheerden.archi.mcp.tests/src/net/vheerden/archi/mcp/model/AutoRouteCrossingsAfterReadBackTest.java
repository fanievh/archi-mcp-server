package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import net.vheerden.archi.mcp.model.CapturedViewHarness.Ctx;
import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;

/**
 * {@code auto-route-connections} publishes {@code crossingsAfter} for the routes it commits. The next call reads those
 * routes back from the model and publishes what it reads as {@code crossingsBefore}. On a view nothing touched between
 * the two calls, the two figures describe the same geometry and must be the same number.
 *
 * <p>They were not: the after-count joined the router's integer bendpoints to the element centres, while the model
 * stores each bendpoint relative to an integer centre and the read adds it back to a fractional centre, then blends the
 * source- and target-anchored points by the bendpoint's weight along the list. Routes sharing a track or a port slot
 * touch at a whole-pixel coordinate; the read shears one of them by a fraction of a pixel and the touch becomes a
 * counted crossing (or the reverse). The views below are captured layouts routed headless, every object unnamed.</p>
 */
public class AutoRouteCrossingsAfterReadBackTest {

    private static final List<String> VIEWS = List.of(
            "estate-interaction-topology", "meridian-a", "meridian-b", "meridian-c", "meridian-d",
            "meridian-e", "meridian-f", "meridian-g", "meridian-h", "meridian-i", "meridian-j", "meridian-k");

    @Test
    public void shouldPublishAsCrossingsAfterWhatTheNextCallReadsAsCrossingsBefore_onEveryCapturedView()
            throws Exception {
        List<String> mismatches = new ArrayList<>();
        for (String view : VIEWS) {
            Ctx c = load(view);
            AutoRouteResultDto first = CapturedViewHarness.route(c);
            AutoRouteResultDto second = CapturedViewHarness.route(c);
            if (first.crossingsAfter() != second.crossingsBefore()) {
                mismatches.add(view + ": published " + first.crossingsAfter() + ", read back "
                        + second.crossingsBefore());
            }
        }
        assertEquals(List.of(), mismatches);
    }

    @Test
    public void shouldPublishWhatTheNextCallReads_whenAutoNudgeMovedAnElement() throws Exception {
        Ctx c = load("meridian-g");

        AutoRouteResultDto nudged = CapturedViewHarness.routeWith(c, true);
        AutoRouteResultDto next = CapturedViewHarness.route(c);

        assertFalse("precondition: the call nudged an element", nudged.nudgedElements().isEmpty());
        assertEquals(next.crossingsBefore(), nudged.crossingsAfter());
    }

    @Test
    public void shouldNotReportARegression_whenReRoutingAViewNothingTouched() throws Exception {
        // The first call routes the bare layout; the second re-routes what the first committed. It reproduces the
        // same routes, so it neither raises nor lowers the crossings it reads.
        Ctx c = load("meridian-a");
        CapturedViewHarness.route(c);

        AutoRouteResultDto again = CapturedViewHarness.route(c);

        assertEquals(again.crossingsBefore(), again.crossingsAfter());
        assertTrue("no regression warning: " + again.structuredWarnings(),
                codes(again).stream().noneMatch(StructuredWarningCodes.AUTO_ROUTE_CROSSINGS_REGRESSED::equals));
    }

    @Test
    public void shouldWarnOfInflation_whenTheRoutesAsReadExceedTheStraightLineThreshold() throws Exception {
        // 79 straight-line crossings put the inflation threshold at 118.5. The committed routes read back as 124
        // (123 until the self-hug correction stopped writing a corner into a connection's own target: the target-side
        // correction it then makes on that connection runs one more segment across the view).
        Ctx c = load("estate-interaction-topology");

        AutoRouteResultDto dto = CapturedViewHarness.route(c);

        assertEquals(79, dto.straightLineCrossings());
        assertEquals(124, dto.crossingsAfter());
        assertTrue("the inflation warning names the figure the view holds: " + dto.warnings(),
                dto.warnings().stream().anyMatch(w -> w.startsWith("Routing produced 124 crossings vs 79")));
    }

    private static List<String> codes(AutoRouteResultDto dto) {
        List<String> codes = new ArrayList<>();
        if (dto.structuredWarnings() != null) {
            for (StructuredWarningDto w : dto.structuredWarnings()) {
                codes.add(w.code());
            }
        }
        return codes;
    }

    private static Ctx load(String view) throws Exception {
        return CapturedViewHarness.fromFixture("testdata/" + view + "-typed-fixture.json");
    }
}
