package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import net.vheerden.archi.mcp.model.AutoRouteArmFixture.Arm;
import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;

/**
 * The arm a disclosure was written for must be the arm the call actually took.
 *
 * <p>Every routing disclosure is composed at the moment its finding is measured, which is a hundred
 * lines above the dispatch split. That is only sound because three things hold for this tool:
 * {@code dispatchOrQueue} is exactly {@code mode == BATCH}; nothing between the first emitter and
 * either split changes the session mode — the whole method body is routing computation and command
 * construction, and it calls no batch or approval setter; and {@code auto-route-connections} is not
 * in the bulk-supported tool list, so the bulk disjunct of the queued predicate cannot fire and the
 * two predicates coincide.</p>
 *
 * <p>Left as a comment, that is an argument. Here it is a guard: the real accessor is driven on each
 * arm and the {@link MutationResult} it hands back is checked against the tense of the disclosure
 * that travelled with it. If any of the three facts ever stops being true, this goes red rather than
 * the response quietly acquiring a prediction that was wrong — which is the prepare/execute
 * divergence this project has paid for repeatedly.</p>
 *
 * <p><strong>What this suite does NOT establish, stated so it is not mistaken for more than it
 * is.</strong> Every test here holds the session mode fixed for the whole call. The three facts
 * above are properties of the CODE, and the code is not the only writer: the approval bit is a
 * {@code volatile} flag the human toggles from the UI thread while the routing computation runs on
 * a Jetty thread. A toggle inside that window — seconds, on a large view — can still put an
 * early-composed disclosure on a response that took the other arm, and no fixture that drives one
 * unchanging mode can see it. That is a real remaining gap, deliberately left rather than papered
 * over: closing it means snapshotting the arm once per request and having the dispatch gate consult
 * the snapshot, which reaches every approval-gated tool in the accessor and is not this suite's to
 * assert.</p>
 */
public class AutoRouteArmAgreementTest {

    @Test
    public void appliedCall_returnsNeitherBatchNorProposal_andSaysTheRoutesWereApplied() {
        MutationResult<AutoRouteResultDto> result = drive(Arm.APPLIED);

        assertFalse("an applied call is not batched", result.isBatched());
        assertFalse("nor is it a proposal", result.isProposal());
        String message = noteCrossing(result.entity()).message();
        assertTrue("so the disclosure that travelled with it says the routes were applied:\n"
                + message, message.contains("routes were applied through it"));
        assertFalse("and does not deny it:\n" + message,
                message.contains("Nothing has been applied"));
    }

    @Test
    public void queuedCall_returnsABatchSequenceNumber_andSaysNothingHasBeenApplied() {
        MutationResult<AutoRouteResultDto> result = drive(Arm.QUEUED);

        assertTrue("a call inside an open batch is batched", result.isBatched());
        assertFalse("and is not a proposal", result.isProposal());
        String message = noteCrossing(result.entity()).message();
        assertTrue("so the disclosure must name the batch, not the model:\n" + message,
                message.contains("Nothing has been applied")
                        && message.contains("queued in the open batch"));
    }

    @Test
    public void approvalCall_returnsAProposal_andSaysNothingHasBeenApplied() {
        MutationResult<AutoRouteResultDto> result = drive(Arm.AWAITING_APPROVAL);

        assertTrue("a call under approval mode is a proposal", result.isProposal());
        assertFalse("and is not batched", result.isBatched());
        String message = noteCrossing(result.entity()).message();
        assertTrue("so the disclosure must name the human's decision:\n" + message,
                message.contains("Nothing has been applied")
                        && message.contains("waiting on the human's decision"));
    }

    @Test
    public void approvalWinsOverAnOpenBatch_endToEndThroughTheRealAccessor() {
        // The ordering claim, driven end to end rather than asserted on the dispatcher alone. Both
        // conditions hold, the accessor's approval gate sits above dispatchOrQueue, and so the call
        // proposes -- which means the disclosure must speak of the human's decision and NOT of a
        // batch entry that was never made. A response telling this caller to run end-batch would be
        // pointing at a queue that does not hold its routing.
        AutoRouteArmFixture.Ctx c = AutoRouteArmFixture.oneNoteInCorridor();
        MutationDispatcher dispatcher = AutoRouteArmFixture.newDispatcher(c.model);
        dispatcher.setApprovalModeProvider(() -> true);
        dispatcher.beginBatch(AutoRouteArmFixture.SESSION, "batch under approval mode");
        AutoRouteArmFixture.StubEditorModelManager mgr =
                new AutoRouteArmFixture.StubEditorModelManager();
        mgr.setModels(List.of(c.model));
        ArchiModelAccessorImpl accessor = new ArchiModelAccessorImpl(mgr, dispatcher);

        MutationResult<AutoRouteResultDto> result;
        try {
            result = accessor.autoRouteConnections(AutoRouteArmFixture.SESSION,
                    AutoRouteArmFixture.VIEW_ID, null, "orthogonal", false, false, 0, 0, null);
        } finally {
            accessor.dispose();
        }

        assertEquals("the dispatcher and the accessor must agree on which gate wins",
                DispatchArm.AWAITING_APPROVAL,
                dispatcher.armFor(AutoRouteArmFixture.SESSION));
        assertTrue("the accessor proposes rather than queues", result.isProposal());
        assertFalse("so no batch sequence number is handed back", result.isBatched());

        String message = noteCrossing(result.entity()).message();
        assertTrue("and the disclosure describes the arm the call actually took:\n" + message,
                message.contains("waiting on the human's decision"));
        assertFalse("not the one it merely could have taken:\n" + message,
                message.contains("queued in the open batch"));
    }

    private static MutationResult<AutoRouteResultDto> drive(Arm arm) {
        return AutoRouteArmFixture.driveFor(AutoRouteArmFixture.oneNoteInCorridor(), arm,
                "orthogonal", false, null);
    }

    private static StructuredWarningDto noteCrossing(AutoRouteResultDto dto) {
        StructuredWarningDto warning = AutoRouteArmFixture.structured(dto,
                StructuredWarningCodes.CONNECTION_ROUTED_THROUGH_NOTE);
        assertNotNull("the fixture must actually produce a disclosure to agree about", warning);
        return warning;
    }
}
