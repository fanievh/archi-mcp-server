package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import net.vheerden.archi.mcp.model.AutoRouteArmFixture.Arm;
import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;
import net.vheerden.archi.mcp.response.dto.NudgedElementDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;

/**
 * What each routing disclosure may say when nothing has been written to the model.
 *
 * <p>Three obligations, and they pull against each other, which is why they are asserted together
 * rather than in separate suites.</p>
 *
 * <ol>
 *   <li><strong>No applied-state claim.</strong> A queued or awaiting-approval call has changed
 *       nothing, so a sentence in the past tense about the model is simply false — and false in the
 *       structured field is worse than false in prose, because the structured field is what an
 *       agent triages on without reading anything.</li>
 *   <li><strong>No withdrawn measurement.</strong> The router ran. It counted the crossings, rolled
 *       back the hugs, named the elements. Those facts are established whatever becomes of the
 *       commands, and dropping them along with the tense would delete something legitimately
 *       measured. This is the clause that separates this family from the spacing family, whose
 *       {@code after} snapshot genuinely re-reads an unmutated view and which therefore abstains.
 *       Copying that abstention here would be the wrong fix.</li>
 *   <li><strong>Both sinks carry the same string.</strong> The free-text array is not a legacy
 *       surface to be left behind: it is the same disclosure read by a different kind of caller.
 *       Rescoping one and not the other reopens the contradiction one field along.</li>
 * </ol>
 */
public class AutoRouteDeferredArmMessageTest {

    private static final List<DispatchArm> DEFERRED =
            List.of(DispatchArm.QUEUED, DispatchArm.AWAITING_APPROVAL);

    /**
     * Sentences that assert the model already holds this call's routing. None may appear on a
     * deferred arm. Each is lifted from the applied-arm string it belongs to, so the list cannot
     * drift into banning phrases no emitter ever produced.
     */
    private static final List<String> APPLIED_STATE_CLAIMS = List.of(
            "were routed normally",
            "kept the hug(s) in place",
            "ended at their starting position",
            "did not take effect",
            "were still applied",
            "were applied through it",
            "crossings THIS CALL applied");

    // ==================== the five arm-dependent codes ====================

    @Test
    public void connectionsNotFound_onEveryDeferredArm_dropsTheAppliedClaimAndKeepsTheIds() {
        String applied = notFound(DispatchArm.APPLIED).message();
        for (DispatchArm arm : DEFERRED) {
            StructuredWarningDto warning = notFound(arm);
            assertDeferredTense(arm, warning.message());
            assertEveryFigureSurvives(arm, applied, warning.message());
            assertTrue(arm + " keeps every missing id machine-readable",
                    warning.remediationViolatorIds().containsAll(List.of("bogus-1", "bogus-2")));
        }
    }

    @Test
    public void egressLiftLayoutBound_onEveryDeferredArm_dropsTheAppliedClaimAndKeepsCountAndFloor() {
        String applied = egress(DispatchArm.APPLIED).message();
        for (DispatchArm arm : DEFERRED) {
            List<String> free = new ArrayList<>();
            List<StructuredWarningDto> structured = new ArrayList<>();
            AutoRouteWarnings.emitEgressLiftLayoutBound(2, arm, free, structured);

            assertDeferredTense(arm, structured.get(0).message());
            assertEveryFigureSurvives(arm, applied, structured.get(0).message());
            assertMirrored(arm, structured.get(0).message(), free);
        }
    }

    @Test
    public void netZeroNudge_onEveryDeferredArm_dropsTheAppliedClaimAndKeepsEveryElementName() {
        String applied = netZero(DispatchArm.APPLIED).message();
        for (DispatchArm arm : DEFERRED) {
            List<String> free = new ArrayList<>();
            List<StructuredWarningDto> structured = new ArrayList<>();
            AutoRouteWarnings.emitNetZeroNudge(
                    List.of(new NudgedElementDto("vo-9", "internal API GW", 0, 0)),
                    arm, free, structured);

            assertDeferredTense(arm, structured.get(0).message());
            assertEveryFigureSurvives(arm, applied, structured.get(0).message());
            assertTrue(arm + " still names the element",
                    structured.get(0).message().contains("internal API GW (vo-9)"));
            assertMirrored(arm, structured.get(0).message(), free);
        }
    }

    @Test
    public void crossingsRegressed_onEveryDeferredArm_dropsTheAppliedClaimAndKeepsBothCounts() {
        String applied = crossings(DispatchArm.APPLIED).message();
        for (DispatchArm arm : DEFERRED) {
            List<String> free = new ArrayList<>();
            List<StructuredWarningDto> structured = new ArrayList<>();
            RoutingPipeline.appendCrossingWarnings(6, 17, 0, true, arm, free, structured);

            assertDeferredTense(arm, structured.get(0).message());
            assertEveryFigureSurvives(arm, applied, structured.get(0).message());
            assertMirrored(arm, structured.get(0).message(), free);
        }
    }

    @Test
    public void connectionThroughNote_onEveryDeferredArm_dropsTheAppliedClaimAndKeepsEveryPair() {
        String applied = throughNote(DispatchArm.APPLIED).message();
        for (DispatchArm arm : DEFERRED) {
            List<String> free = new ArrayList<>();
            List<StructuredWarningDto> structured = new ArrayList<>();
            AutoRouteWarnings.emitConnectionThroughNote(corridorPaths(), corridorConnection(),
                    corridorNodes(), arm, free, structured);

            assertDeferredTense(arm, structured.get(0).message());
            assertEveryFigureSurvives(arm, applied, structured.get(0).message());
            assertTrue(arm + " still names the (connection, note) pair",
                    structured.get(0).message()
                            .contains("connection 'flows to' (conn-1) through note 'obj-note-a'"));
            assertMirrored(arm, structured.get(0).message(), free);
        }
    }

    @Test
    public void crossingsRegressed_onEveryDeferredArm_statesTheIncreaseAsMeasuredNotAsAPrediction() {
        // A deferred arm must drop the applied-state claim WITHOUT demoting the measurement that
        // sits beside it. The router ran: it built the paths and counted the crossings on them.
        // "Routing WOULD increase crossings from 6 to 17" reads as a forecast of a number that was
        // in fact measured -- the mirror of the over-claim this family was fixed for, and wrong in
        // the same way. The conditional belongs on the REPLACEMENT, which really has not happened.
        //
        // Nothing caught this when the wording drifted, because every other assertion here is
        // satisfied by either mood: the figures survive, no banned applied-state phrase appears,
        // and "Nothing has been applied" is present. This is the pin for the clause itself.
        for (DispatchArm arm : DEFERRED) {
            String message = crossings(arm).message();

            assertTrue(arm + " must state the crossing increase as the measurement it is:\n"
                    + message, message.contains("increased edge crossings from 6 to 17"));
            assertTrue(arm + " must not present a measured pair as a forecast:\n" + message,
                    !message.contains("would increase"));
            assertTrue(arm + " must attach the conditional to the replacement, which is what has "
                    + "genuinely not happened:\n" + message,
                    message.contains("the geometry it would replace")
                            && message.contains("applying this re-route would leave the view "
                                    + "worse than it is now"));
        }

        // And the applied arm states the same measurement in its own mood: the counts as
        // measured, the replacement as done. This assertion used to pin the sentence that
        // followed those counts -- "this re-route is worse than the geometry it replaced" -- as
        // settled, which it was not: two ints license the arithmetic and never a whole-view
        // verdict. The clause is gone; what the applied arm still owes is the measurement and
        // the applied-state claim, so that is what is pinned here.
        String applied = crossings(DispatchArm.APPLIED).message();
        assertTrue("the applied arm states the increase as measured, and the write as done:\n"
                + applied,
                applied.contains("Routing increased edge crossings from 6 to 17.")
                        && applied.contains("The new paths were still applied."));
        assertTrue("and it no longer turns two counts into a whole-view verdict:\n" + applied,
                !applied.contains("worse than the geometry it replaced"));
    }

    // ==================== the remediation tools ====================

    @Test
    public void remediationTool_variesByArm_onlyForTheCodeWhoseToolRecoversThisCall() {
        // The rule, and it is narrower than it looks: a remediationTool varies by arm exactly when
        // the tool it names recovers THIS call. Only undo does. The other four name a lookup or a
        // layout corrective -- things that are just as applicable to a routing that has not landed
        // yet -- so repointing them per arm would publish a worse remedy AND break four deliberate
        // exact-value pins for nothing.
        for (DispatchArm arm : DispatchArm.values()) {
            assertEquals(arm + ": a lookup is a lookup on every arm",
                    "get-view-contents", notFound(arm).remediationTool());
            assertEquals(arm + ": a corridor widening is a layout corrective, not a recovery",
                    "apply-spacing-recommendations", egress(arm).remediationTool());
            assertEquals(arm + ": a spacing lever is a layout corrective, not a recovery",
                    "apply-spacing-recommendations", netZero(arm).remediationTool());
            assertEquals(arm + ": moving a note moves a different, already-existing object",
                    "update-view-object", throughNote(arm).remediationTool());
        }

        assertEquals("an applied re-route is reverted with undo",
                "undo", crossings(DispatchArm.APPLIED).remediationTool());
        assertEquals("a queued one is not on the command stack at all -- undo pops somebody else's",
                "end-batch", crossings(DispatchArm.QUEUED).remediationTool());
        assertEquals("no MCP tool recovers a proposal, so the field names none",
                "", crossings(DispatchArm.AWAITING_APPROVAL).remediationTool());
    }

    // ==================== the code that is CORRECTLY arm-blind ====================

    @Test
    public void nudgeSkippedSiblingOverlap_isIdenticalOnAllThreeArms_besideACodeThatIsNot() {
        // The skip is a decision the router took while COMPUTING, on geometry that already exists
        // on the view. It happened, in the past, on every arm -- so writing a deferred tense for it
        // would be a false statement, and rescoping it "because the others were" is the trap.
        //
        // An identity assertion alone would also pass on an inert harness that drove one arm three
        // times, so the same three responses are checked for a code that MUST differ.
        Map<Arm, StructuredWarningDto> skips = new LinkedHashMap<>();
        Map<Arm, StructuredWarningDto> notes = new LinkedHashMap<>();
        for (Arm arm : Arm.values()) {
            AutoRouteResultDto dto = AutoRouteArmFixture.routeWithNudge(
                    AutoRouteArmFixture.overlappingSiblingsWithNoteInCorridor(), arm);
            skips.put(arm, AutoRouteArmFixture.structured(dto,
                    StructuredWarningCodes.AUTO_NUDGE_SKIPPED_SIBLING_OVERLAP));
            notes.put(arm, AutoRouteArmFixture.structured(dto,
                    StructuredWarningCodes.CONNECTION_ROUTED_THROUGH_NOTE));
            assertNotNull(arm + " must trip the sibling-overlap pre-gate", skips.get(arm));
            assertNotNull(arm + " must also cross the note -- the positive control", notes.get(arm));
        }

        assertEquals("a compute-time skip reads identically whatever happens to the commands",
                skips.get(Arm.APPLIED).message(), skips.get(Arm.QUEUED).message());
        assertEquals("and identically again on the awaiting-approval arm",
                skips.get(Arm.APPLIED).message(), skips.get(Arm.AWAITING_APPROVAL).message());
        assertEquals("its remedy is a layout corrective, so it does not move either",
                skips.get(Arm.APPLIED).remediationTool(),
                skips.get(Arm.AWAITING_APPROVAL).remediationTool());

        assertNotEquals("POSITIVE CONTROL: the harness really did drive three different arms",
                notes.get(Arm.APPLIED).message(), notes.get(Arm.QUEUED).message());
        assertNotEquals("POSITIVE CONTROL: and the third is distinct from the second",
                notes.get(Arm.QUEUED).message(), notes.get(Arm.AWAITING_APPROVAL).message());
    }

    // ==================== a routing failure is not a deferral ====================

    @Test
    public void connectionsNotFound_whenRoutingThenFailed_saysSoInsteadOfClaimingTheRestWereRouted() {
        // Arm-INDEPENDENT, and equally false on an applied call: the degenerate-geometry early
        // return carries a connection-not-found entry raised a hundred lines earlier, whose message
        // said "The remaining connections were routed normally" beside a failure notice about the
        // very same call.
        List<String> free = new ArrayList<>();
        List<StructuredWarningDto> structured = new ArrayList<>();
        AutoRouteWarnings.emitConnectionsNotFound(List.of("bogus-1"), DispatchArm.APPLIED,
                free, structured);

        AutoRouteWarnings.emitRoutingFailedDegenerateGeometry(free, structured);

        String message = structured.get(0).message();
        assertTrue("the miss itself survives -- it was measured against the view",
                message.contains("bogus-1"));
        assertTrue("and the response now says what actually became of the rest",
                message.contains("Routing then failed on the remaining connections, so none of "
                        + "them were routed either."));
        for (String claim : APPLIED_STATE_CLAIMS) {
            assertTrue("a failed routing may not claim: " + claim, !message.contains(claim));
        }
        assertEquals("the per-ID free-text line is still true and is left exactly as it was",
                "Connection not found on view: bogus-1", free.get(0));
    }

    // ==================== assertions ====================

    private static void assertDeferredTense(DispatchArm arm, String message) {
        for (String claim : APPLIED_STATE_CLAIMS) {
            assertTrue(arm + " must not assert applied state: \"" + claim + "\" in\n" + message,
                    !message.contains(claim));
        }
        assertTrue(arm + " must say plainly that nothing has been applied:\n" + message,
                message.contains("Nothing has been applied"));
    }

    /**
     * Requires every figure the applied-arm message states to appear in the deferred one.
     *
     * <p>Figures are taken FROM the applied string rather than from a list written here, so a
     * measurement added to an emitter later is covered without anyone remembering to extend this
     * test — and a measurement quietly dropped on a deferred arm cannot pass by being absent from
     * both sides.</p>
     */
    private static void assertEveryFigureSurvives(DispatchArm arm, String applied,
            String deferred) {
        Set<String> figures = new LinkedHashSet<>();
        Matcher numbers = Pattern.compile("\\d+(?:px)?").matcher(applied);
        while (numbers.find()) {
            figures.add(numbers.group());
        }
        Matcher quoted = Pattern.compile("'[^']+'").matcher(applied);
        while (quoted.find()) {
            figures.add(quoted.group());
        }
        assertTrue("the applied message must state something measurable, or this proves nothing",
                figures.size() > 0);
        for (String figure : figures) {
            assertTrue(arm + " withdrew the measured figure " + figure + " from\n" + deferred,
                    deferred.contains(figure));
        }
    }

    private static void assertMirrored(DispatchArm arm, String structured, List<String> free) {
        assertEquals(arm + " must write the rescoped string to the free-text sink too, "
                + "not leave it holding the applied-arm one", structured, free.get(0));
    }

    // ==================== emitter drivers ====================

    private static StructuredWarningDto notFound(DispatchArm arm) {
        List<StructuredWarningDto> structured = new ArrayList<>();
        AutoRouteWarnings.emitConnectionsNotFound(List.of("bogus-1", "bogus-2"), arm,
                new ArrayList<>(), structured);
        return structured.get(0);
    }

    private static StructuredWarningDto egress(DispatchArm arm) {
        List<StructuredWarningDto> structured = new ArrayList<>();
        AutoRouteWarnings.emitEgressLiftLayoutBound(2, arm, new ArrayList<>(), structured);
        return structured.get(0);
    }

    private static StructuredWarningDto netZero(DispatchArm arm) {
        List<StructuredWarningDto> structured = new ArrayList<>();
        AutoRouteWarnings.emitNetZeroNudge(
                List.of(new NudgedElementDto("vo-9", "internal API GW", 0, 0)), arm,
                new ArrayList<>(), structured);
        return structured.get(0);
    }

    private static StructuredWarningDto crossings(DispatchArm arm) {
        List<StructuredWarningDto> structured = new ArrayList<>();
        RoutingPipeline.appendCrossingWarnings(6, 17, 0, true, arm, new ArrayList<>(), structured);
        return structured.get(0);
    }

    private static StructuredWarningDto throughNote(DispatchArm arm) {
        List<StructuredWarningDto> structured = new ArrayList<>();
        AutoRouteWarnings.emitConnectionThroughNote(corridorPaths(), corridorConnection(),
                corridorNodes(), arm, new ArrayList<>(), structured);
        return structured.get(0);
    }

    // ==================== fixture ====================

    private static Map<String, List<AbsoluteBendpointDto>> corridorPaths() {
        Map<String, List<AbsoluteBendpointDto>> paths = new LinkedHashMap<>();
        paths.put("conn-1", List.of(new AbsoluteBendpointDto(300, 227)));
        return paths;
    }

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
