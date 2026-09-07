package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import net.vheerden.archi.mcp.model.AutoRouteArmFixture.Arm;
import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;
import net.vheerden.archi.mcp.response.dto.NudgedElementDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;

/**
 * Byte-exact pins on every message {@code auto-route-connections} publishes when the routing it
 * describes has actually been written to the model.
 *
 * <p><strong>Why whole-string equality rather than the usual fragment assertions.</strong> The
 * sibling suites assert that a message names a count, or a floor, or a tool. Each of those stays
 * green while the sentence around the fragment is rewritten — which is exactly the edit that turns
 * an applied-arm sentence into a deferred-arm one, or the reverse. These six strings are the
 * published applied-arm contract, and the only thing that can hold a contract about a sentence is
 * the sentence.</p>
 *
 * <p>Every expectation here was produced by CALLING the emitter and reading what came back, never
 * by copying a literal out of the emitter's source. A pin copied from the code it guards agrees
 * with that code by construction and would survive the emitter being wrong.</p>
 *
 * <p>Two of the six had no text coverage at all before this suite. {@code AUTO_NUDGE_NET_ZERO} has
 * no prose sibling to pin it indirectly, and {@code AUTO_NUDGE_SKIPPED_SIBLING_OVERLAP} appeared
 * only as filler data in DTO-mechanism tests. The other four re-pin an existing net at whole-string
 * granularity.</p>
 */
public class AutoRouteAppliedArmMessageTest {

    private static final String CONNECTIONS_NOT_FOUND_APPLIED =
            "2 requested connection ID(s) were not found on the view and were skipped: "
            + "bogus-1, bogus-2. The remaining connections were routed normally. Run "
            + "get-view-contents to list the connection IDs that exist on this view.";

    private static final String EGRESS_LIFT_APPLIED =
            "2 off-face terminal hug(s) could not be cleared without narrowing a "
            + "parallel-connection gap below the 15px healthy floor, so the router kept the "
            + "hug(s) in place. This is layout-bound: increase element spacing in the affected "
            + "corridor and re-route — re-routing alone will not clear it.";

    private static final String NET_ZERO_APPLIED =
            "1 element(s) processed by autoNudge ended at their starting position (net "
            + "displacement 0,0) and are therefore not reported in nudgedElements: internal API "
            + "GW (vo-9). The recommended move did not take effect for them, so whatever it was "
            + "meant to unblock may still be unresolved — check the failed array. This response "
            + "does not distinguish a move absorbed by the parent-containment clamp from one a "
            + "later iteration reversed. Re-routing will reproduce the same result: increase the "
            + "spacing around these elements so a recommended move has room to take effect.";

    private static final String CROSSINGS_REGRESSED_APPLIED =
            "Routing increased edge crossings from 6 to 17. The new paths were still applied. "
            + "Crossings alone do not determine layout quality — a routed view can score worse "
            + "here and still read better overall; review the view before deciding, and if you do "
            + "want the previous geometry back, undo reverts the whole routing pass, not just the "
            + "crossings. To straighten diagonal terminals without re-routing connection interiors "
            + "(the usual reason a tidy layout regresses here), re-run auto-route-connections with "
            + "mode 'terminals-only', which declines any rectification that would add crossings.";

    private static final String THROUGH_NOTE_APPLIED =
            "1 applied route(s) pass through a note: connection 'flows to' (conn-1) through note "
            + "'obj-note-a'. A note is not a routing obstacle, so these routes were applied "
            + "through it deliberately rather than failing. These are the crossings THIS CALL "
            + "applied, counted per (connection, note) pair on the same geometry assess-layout "
            + "uses; its connectionThroughNoteCount is a whole-view figure and also counts notes "
            + "crossed by connections this call did not route, plus element images, so it can be "
            + "higher. Move the note(s) clear with update-view-object and re-assess; moving a "
            + "note changes the routes around it, so position notes after routing.";

    private static final String NUDGE_SKIPPED_APPLIED =
            "autoNudge skipped because sibling elements have overlapping bounding boxes. Use "
            + "layout-flat-view or layout-within-group to separate elements first.";

    @Test
    public void connectionsNotFound_appliedArm_isByteIdenticalOnBothSinks() {
        List<String> warnings = new ArrayList<>();
        List<StructuredWarningDto> structured = new ArrayList<>();

        AutoRouteWarnings.emitConnectionsNotFound(List.of("bogus-1", "bogus-2"), DispatchArm.APPLIED, warnings, structured);

        assertEquals(CONNECTIONS_NOT_FOUND_APPLIED, structured.get(0).message());
        // This code is the one deliberate asymmetry: the per-ID free-text lines are a
        // long-standing published surface, so the free text is NOT the structured message here.
        assertEquals(List.of("Connection not found on view: bogus-1",
                "Connection not found on view: bogus-2"), warnings);
    }

    @Test
    public void egressLiftLayoutBound_appliedArm_isByteIdenticalOnBothSinks() {
        List<String> warnings = new ArrayList<>();
        List<StructuredWarningDto> structured = new ArrayList<>();

        AutoRouteWarnings.emitEgressLiftLayoutBound(2, DispatchArm.APPLIED, warnings, structured);

        assertEquals(EGRESS_LIFT_APPLIED, structured.get(0).message());
        assertEquals("the free-text sink carries the identical string, not a paraphrase",
                structured.get(0).message(), warnings.get(0));
    }

    @Test
    public void netZeroNudge_appliedArm_isByteIdenticalOnBothSinks() {
        List<String> warnings = new ArrayList<>();
        List<StructuredWarningDto> structured = new ArrayList<>();

        AutoRouteWarnings.emitNetZeroNudge(
                List.of(new NudgedElementDto("vo-9", "internal API GW", 0, 0)),
                DispatchArm.APPLIED, warnings, structured);

        assertEquals(NET_ZERO_APPLIED, structured.get(0).message());
        assertEquals(structured.get(0).message(), warnings.get(0));
    }

    @Test
    public void crossingsRegressed_appliedArm_isByteIdenticalOnBothSinks() {
        List<String> warnings = new ArrayList<>();
        List<StructuredWarningDto> structured = new ArrayList<>();

        RoutingPipeline.appendCrossingWarnings(6, 17, 0, true, DispatchArm.APPLIED, warnings, structured);

        assertEquals(CROSSINGS_REGRESSED_APPLIED, structured.get(0).message());
        assertEquals(structured.get(0).message(), warnings.get(0));
    }

    @Test
    public void connectionThroughNote_appliedArm_isByteIdenticalOnBothSinks() {
        List<String> warnings = new ArrayList<>();
        List<StructuredWarningDto> structured = new ArrayList<>();

        Map<String, List<AbsoluteBendpointDto>> routedPaths = new LinkedHashMap<>();
        routedPaths.put("conn-1", List.of(new AbsoluteBendpointDto(300, 227)));

        AutoRouteWarnings.emitConnectionThroughNote(routedPaths, corridorConnection(),
                corridorNodes(), DispatchArm.APPLIED, warnings, structured);

        assertEquals(THROUGH_NOTE_APPLIED, structured.get(0).message());
        assertEquals(structured.get(0).message(), warnings.get(0));
    }

    @Test
    public void nudgeSkippedSiblingOverlap_appliedArm_isByteIdenticalOnBothSinks() {
        // The only one of the six with no extractable emitter: the message is composed inline on
        // the accessor's autoNudge pre-gate, so the real accessor is the only thing that can
        // produce it. Driving it here rather than restating the literal is what makes this a pin
        // on the shipped string instead of a copy of it.
        AutoRouteResultDto dto = AutoRouteArmFixture.routeWithNudge(
                AutoRouteArmFixture.overlappingSiblings(), Arm.APPLIED);

        StructuredWarningDto warning = AutoRouteArmFixture.structured(dto,
                StructuredWarningCodes.AUTO_NUDGE_SKIPPED_SIBLING_OVERLAP);
        assertNotNull("the fixture must actually trip the sibling-overlap pre-gate", warning);
        assertEquals(NUDGE_SKIPPED_APPLIED, warning.message());
        assertEquals("the free-text sink carries the identical string",
                NUDGE_SKIPPED_APPLIED, dto.warnings().get(dto.warnings().size() - 1));
    }

    // ==================== fixture ====================

    /** Two hosts either side of a corridor, with a note straddling the centre-line at x=300. */
    private static List<AssessmentNode> corridorNodes() {
        return List.of(
                node("src", 100, 200, 100, 55, false),
                node("tgt", 500, 200, 100, 55, false),
                node("obj-note-a", 280, 190, 60, 60, true));
    }

    private static List<AssessmentConnection> corridorConnection() {
        return List.of(new AssessmentConnection("conn-1", "src", "tgt",
                List.of(new double[]{150, 227}, new double[]{300, 227}, new double[]{550, 227}),
                "flows to", 0));
    }

    private static AssessmentNode node(String id, double x, double y, double w, double h,
            boolean isNote) {
        return new AssessmentNode(id, x, y, w, h, null, false, isNote, "", 0.0,
                null, null, 0.0, 0.0, 0.0);
    }
}
