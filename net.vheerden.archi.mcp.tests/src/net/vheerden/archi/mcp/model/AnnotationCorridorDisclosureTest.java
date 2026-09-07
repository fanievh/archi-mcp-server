package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IApplicationComponent;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.EmbeddedViewDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;
import net.vheerden.archi.mcp.response.dto.ViewNoteDto;

/**
 * The disclosure the three annotation-placement tools make when the rectangle they are about to
 * place lands on a route the view already carries.
 *
 * <p>The published ordering guidance already tells a caller to place annotations AFTER routing,
 * and a measured case obeyed it and still took four crossings — because after routing is exactly
 * when there are corridors to land in. So the missing thing was never stronger advice; it was a
 * measurement of THIS rectangle against THESE routes, which only the call itself can take.</p>
 *
 * <p>The warning rides on the response ENTITY rather than in {@code nextSteps}, because approval
 * mode discards next steps wholesale and replaces them with three fixed approval lines — the arm
 * where a human is about to say yes is the one that would have lost it.</p>
 */
public class AnnotationCorridorDisclosureTest {

    private static final String SESSION = "corridor-session";

    /** A rectangle straddling the horizontal route between the two elements. */
    private static final int ON_ROUTE_X = 280;
    private static final int ON_ROUTE_Y = 0;

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private CommandStack stack;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private IArchimateDiagramModel otherView;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Corridor Fixture");
        model.setId("model-corridor");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Routed");
        diagrams.getElements().add(view);
        otherView = factory.createArchimateDiagramModel();
        otherView.setId("view-2");
        otherView.setName("Referenced");
        diagrams.getElements().add(otherView);

        stubModelManager.setModels(List.of(model));

        stack = new CommandStack();
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                stack.execute(toPlainCompound(command));
            }
            @Override
            protected void dispatchCommand(Command command) {
                stack.execute(toPlainCompound(command));
            }
            private Command toPlainCompound(Command command) {
                if (command instanceof CompoundCommand compound) {
                    CompoundCommand plain = new CompoundCommand(compound.getLabel());
                    for (Object child : compound.getCommands()) {
                        plain.add(toPlainCompound((Command) child));
                    }
                    return plain;
                }
                return command;
            }
        };
        dispatcher.setApprovalModeProvider(() -> false);
        accessor = new ArchiModelAccessorImpl(stubModelManager, dispatcher);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ---- The immediate arm, on all three tools ----

    @Test
    public void addNoteToView_shouldDiscloseTheCrossing_whenTheNoteLandsOnARoute() {
        routedPair();

        ViewNoteDto note = accessor.addNoteToView(SESSION, view.getId(), "Legend",
                null, null, ON_ROUTE_X, ON_ROUTE_Y, 120, 60, null, null, null).entity();

        StructuredWarningDto warning = corridorWarning(note.structuredWarnings());
        assertNotNull("a note dropped on a live route must say so: " + note.structuredWarnings(),
                warning);
        assertTrue("the crossed connection must be named in the message",
                warning.message().contains("conn-r1"));
        assertEquals("the remedy moves the annotation, not the routes",
                "update-view-object", warning.remediationTool());
        assertEquals("the violator ids are the CONNECTIONS — the set the caller cannot otherwise "
                        + "enumerate; the annotation's own id is the subject of the response",
                List.of("conn-r1"), warning.remediationViolatorIds());
    }

    @Test
    public void addNoteToView_shouldNameTheInformationalMetric() {
        // A note is excluded from the assessor's layout node set, so a route crossing it lands in
        // connectionThroughNoteCount and caps at good. Saying which metric is what lets the caller
        // tell a cosmetic crossing from one that will move the rating.
        routedPair();

        StructuredWarningDto warning = corridorWarning(accessor.addNoteToView(SESSION,
                view.getId(), "Legend", null, null, ON_ROUTE_X, ON_ROUTE_Y, 120, 60,
                null, null, null).entity().structuredWarnings());

        assertNotNull(warning);
        assertTrue("a note lands in the informational metric: " + warning.message(),
                warning.message().contains("connectionThroughNoteCount"));
        assertFalse("and NOT in the rated one",
                warning.message().contains("connectionPassThroughs"));
    }

    @Test
    public void addViewReferenceToView_shouldNameTheRatedMetric() {
        // A view-reference is an ordinary layout node to the assessor — it is not a note — so a
        // route crossing it is a rated pass-through that can drive the view to poor. This is the
        // half the note-only guidance never covered, and the more damaging half.
        routedPair();

        EmbeddedViewDto ref = accessor.addViewReferenceToView(SESSION, view.getId(),
                otherView.getId(), ON_ROUTE_X, ON_ROUTE_Y, 120, 60, null, null).entity();

        StructuredWarningDto warning = corridorWarning(ref.structuredWarnings());
        assertNotNull("a view-reference dropped on a live route must say so", warning);
        assertTrue("a view-reference lands in the RATED metric: " + warning.message(),
                warning.message().contains("connectionPassThroughs"));
        assertTrue("and the message must say the rating can move",
                warning.message().contains("RATED"));
        assertEquals(List.of("conn-r1"), warning.remediationViolatorIds());
    }

    @Test
    public void addNoteToView_shouldNameEveryCrossedConnection_untruncated() {
        routedPair();
        addConnection("r2", "a", "b");
        addConnection("r3", "a", "b");

        StructuredWarningDto warning = corridorWarning(accessor.addNoteToView(SESSION,
                view.getId(), "Legend", null, null, ON_ROUTE_X, ON_ROUTE_Y, 120, 60,
                null, null, null).entity().structuredWarnings());

        assertNotNull(warning);
        assertEquals("every crossed connection is named, never a leading subset",
                List.of("conn-r1", "conn-r2", "conn-r3"), warning.remediationViolatorIds());
        assertTrue(warning.message().contains("conn-r1")
                && warning.message().contains("conn-r2")
                && warning.message().contains("conn-r3"));
    }

    // ---- Absent when nothing was crossed ----

    @Test
    public void addNoteToView_shouldSaySilent_whenTheViewHasNoConnections() {
        elements();

        ViewNoteDto note = accessor.addNoteToView(SESSION, view.getId(), "Legend",
                null, null, ON_ROUTE_X, ON_ROUTE_Y, 120, 60, null, null, null).entity();

        assertNull("no connections means nothing to cross, and nothing to say",
                note.structuredWarnings());
    }

    @Test
    public void addNoteToView_shouldSaySilent_whenTheRouteClearsTheRectangle() {
        routedPair();

        ViewNoteDto note = accessor.addNoteToView(SESSION, view.getId(), "Legend",
                null, null, 280, 400, 120, 60, null, null, null).entity();

        assertNull("a route that passes well clear of the rectangle is not a crossing",
                note.structuredWarnings());
    }

    @Test
    public void addNoteToView_shouldSaySilent_whenPlacedOutsideTheContentBounds() {
        routedPair();

        ViewNoteDto note = accessor.addNoteToView(SESSION, view.getId(), "Legend",
                null, null, 4000, 4000, 120, 60, null, null, null).entity();

        assertNull("nothing routes out here", note.structuredWarnings());
    }

    // ---- The rectangle tested is the EFFECTIVE one, not the requested one ----

    @Test
    public void addNoteToView_shouldTestTheResolvedRectangle_notTheRequestedOne() {
        // The caller asks for the on-route coordinates AND for content-relative placement. The
        // placement wins: the note lands below the content, clear of the corridor. A check that
        // read the caller's x/y would warn about a crossing that never happens — the same
        // requested-vs-effective confusion the mutating-tool invariant exists to prevent, on the
        // input side of it rather than the output side.
        routedPair();

        ViewNoteDto note = accessor.addNoteToView(SESSION, view.getId(), "Legend",
                "below-content", 10, ON_ROUTE_X, ON_ROUTE_Y, 120, 60, null, null, null).entity();

        assertTrue("position must have overridden the requested y", note.y() > ON_ROUTE_Y);
        assertNull("the resolved rectangle clears the route, so there is nothing to disclose",
                note.structuredWarnings());
    }

    // ---- The approval arm: the arm that discards nextSteps ----

    @Test
    public void addNoteToView_shouldKeepTheDisclosureInsideTheProposalPreview() throws Exception {
        routedPair();
        dispatcher.setApprovalModeProvider(() -> true);

        MutationResult<ViewNoteDto> result = accessor.addNoteToView(SESSION, view.getId(),
                "Legend", null, null, ON_ROUTE_X, ON_ROUTE_Y, 120, 60, null, null, null);

        assertTrue("this must be the approval arm, not the immediate one", result.isProposal());

        // Read the response the approval path actually BUILDS, not the entity beside it. The
        // proposal formatter replaces nextSteps with three fixed approval lines, so a disclosure
        // delivered as a next step would be gone by this point while every entity-level
        // assertion still passed.
        String json = net.vheerden.archi.mcp.handlers.HandlerUtils.formatProposalResponse(
                        result.entity(), result.proposalContext(), "1",
                        new net.vheerden.archi.mcp.response.ResponseFormatter())
                .content().toString();

        assertTrue("the response must be the proposal shape", json.contains("proposalId"));
        assertTrue("and it must nest the entity under preview", json.contains("preview"));
        assertTrue("the disclosure must survive into the proposal's preview entity: " + json,
                json.contains(StructuredWarningCodes.ANNOTATION_PLACED_IN_ROUTED_CORRIDOR));
        assertTrue("carrying the crossed connection with it", json.contains("conn-r1"));
        // Structural, not a fragile substring: the approval formatter replaces nextSteps with its
        // own three fixed lines, and the disclosure appears exactly ONCE — in the preview entity.
        // A disclosure that had also been pushed into nextSteps would raise that count, and one
        // that lived ONLY in nextSteps would have been discarded here entirely.
        assertTrue("the approval formatter's own next steps must be the ones on the wire",
                json.contains("This change is pending the human's approval and was NOT applied"));
        assertEquals("the disclosure rides the entity once, not the discarded nextSteps", 1,
                json.split(StructuredWarningCodes.ANNOTATION_PLACED_IN_ROUTED_CORRIDOR, -1).length - 1);
    }

    @Test
    public void addNoteToView_awaitingApproval_saysTheAnnotationDoesNotExistAndItsIdWillBeReminted() {
        // The queued arm has said "nothing has been applied" since it shipped; the approval arm
        // said nothing at all, because the detector only ever asked whether a batch was open. The
        // two are NOT the same sentence, and reusing the queued one here would have been the easy
        // wrong answer: a queued write already has its id -- the object is created and the command
        // that attaches it is what waits -- while a proposal is stored and REPLAYED on approval, so
        // the annotation does not exist yet and its id is minted when the human says yes.
        //
        // That matters because the remedy this very warning names, update-view-object, takes an id.
        // A caller told "nothing has been applied" and handed an id will run it, and address
        // nothing -- or something else. Naming the deferral without naming the consequence for the
        // remedy leaves the caller holding a tool it cannot use and no way to find out why.
        routedPair();
        dispatcher.setApprovalModeProvider(() -> true);

        MutationResult<ViewNoteDto> result = accessor.addNoteToView(SESSION, view.getId(),
                "Legend", null, null, ON_ROUTE_X, ON_ROUTE_Y, 120, 60, null, null, null);

        assertTrue("this must be the approval arm", result.isProposal());
        StructuredWarningDto warning = corridorWarning(result.entity().structuredWarnings());
        assertNotNull("silence on the approval arm is not acceptable either: "
                + result.entity().structuredWarnings(), warning);
        assertTrue("it must say nothing has been applied: " + warning.message(),
                warning.message().contains("nothing in this call has been applied yet"));
        assertTrue("and name the arm it is actually on: " + warning.message(),
                warning.message().contains("waiting on the human's decision"));
        assertTrue("and say the annotation does not exist on the view yet: " + warning.message(),
                warning.message().contains("does not exist on the view yet"));
        assertTrue("and that its id is minted on approval, so the remedy cannot be run against it: "
                        + warning.message(),
                warning.message().contains("id is minted when the change is approved")
                        && warning.message().contains("cannot be"));
        assertFalse("it must not borrow the queued arm's end-batch instruction, which names a "
                        + "batch this call is not in: " + warning.message(),
                warning.message().contains("after end-batch when a batch is open"));
    }

    @Test
    public void allThreeAnnotationTools_awaitingApproval_carryTheSameRemintDisclosure() {
        // The other two tools reach the same collaborator, so a fix wired into one of them would
        // leave the other two approval-blind while this suite's note assertions stayed green.
        routedPair();
        dispatcher.setApprovalModeProvider(() -> true);

        StructuredWarningDto viaViewRef = corridorWarning(accessor.addViewReferenceToView(SESSION,
                view.getId(), otherView.getId(), ON_ROUTE_X, ON_ROUTE_Y, 120, 60, null, null)
                .entity().structuredWarnings());
        assertNotNull("the rated view-reference tool must disclose on the approval arm too",
                viaViewRef);
        assertTrue("with the same re-mint clause: " + viaViewRef.message(),
                viaViewRef.message().contains("id is minted when the change is approved"));

        StructuredWarningDto direct = AnnotationCorridorWarning.detect(view, view,
                ON_ROUTE_X, ON_ROUTE_Y, 120, 60, AnnotationCorridorWarning.Kind.IMAGE,
                DispatchArm.AWAITING_APPROVAL);
        assertNotNull("and so must the image kind, whose facade no automated lane can execute",
                direct);
        assertTrue("with the same re-mint clause: " + direct.message(),
                direct.message().contains("id is minted when the change is approved"));
    }

    @Test
    public void theAppliedAndQueuedClauses_areUnchangedByTheApprovalArmBeingAdded() {
        // The queued clause was correct and is shipped; the applied arm adds nothing at all. Adding
        // a third arm is exactly the edit that rewords its two siblings in passing, so both are
        // pinned whole rather than by fragment -- a fragment assertion survives the sentence around
        // it being rewritten, which is the drift being guarded against.
        routedPair();

        StructuredWarningDto applied = AnnotationCorridorWarning.detect(view, view,
                ON_ROUTE_X, ON_ROUTE_Y, 120, 60, AnnotationCorridorWarning.Kind.NOTE,
                DispatchArm.APPLIED);
        StructuredWarningDto queued = AnnotationCorridorWarning.detect(view, view,
                ON_ROUTE_X, ON_ROUTE_Y, 120, 60, AnnotationCorridorWarning.Kind.NOTE,
                DispatchArm.QUEUED);
        assertNotNull(applied);
        assertNotNull(queued);

        assertEquals("the queued clause is appended verbatim to the applied message",
                applied.message()
                        + " Measured against the routes on the view as they stand now: nothing in"
                        + " this call has been applied yet, and a command queued beside it can"
                        + " still move an endpoint before this placement lands, so re-check with"
                        + " assess-layout once the call has actually been applied — after"
                        + " end-batch when a batch is open.",
                queued.message());
        assertEquals("and the remedy tool is a note move on both, because it moves a DIFFERENT "
                        + "object and is not a recovery of this call",
                "update-view-object", applied.remediationTool());
        assertEquals("update-view-object", queued.remediationTool());
    }

    // ---- The batched arm: measured, with its basis stated ----

    @Test
    public void addNoteToView_shouldDiscloseOnTheQueuedArm_withItsBasisStated() {
        // The queued arm emits rather than abstaining, and the reason matters. The spacing family
        // abstains on a queued call because BOTH of its snapshots describe the same unmutated
        // view — it has nothing measured to report. This check has something measured: the routes
        // it reads are real, current model state. What it cannot promise is that a sibling command
        // still in the queue will not move an endpoint first, and that is what it says.
        routedPair();
        dispatcher.beginBatch(SESSION, "queued placement");

        MutationResult<ViewNoteDto> result = accessor.addNoteToView(SESSION, view.getId(),
                "Legend", null, null, ON_ROUTE_X, ON_ROUTE_Y, 120, 60, null, null, null);

        assertTrue("this must be the queued arm", result.isBatched());
        StructuredWarningDto warning = corridorWarning(result.entity().structuredWarnings());
        assertNotNull("silence on the queued arm is not acceptable: "
                + result.entity().structuredWarnings(), warning);
        assertTrue("the queued arm must state what its measurement is against: "
                        + warning.message(),
                warning.message().contains("as they stand now")
                        && warning.message().contains("end-batch"));
    }

    @Test
    public void addNoteToView_shouldNotClaimTheQueuedCaveat_onTheImmediateArm() {
        // The mirror of the pin above. A disclosure stamped identically onto every arm by an
        // emitter that ran before the arm was chosen is the failure mode this repo has already
        // paid for once; the caveat belongs only where it is true.
        routedPair();

        StructuredWarningDto warning = corridorWarning(accessor.addNoteToView(SESSION,
                view.getId(), "Legend", null, null, ON_ROUTE_X, ON_ROUTE_Y, 120, 60,
                null, null, null).entity().structuredWarnings());

        assertNotNull(warning);
        assertFalse("an applied call has no queue ahead of it: " + warning.message(),
                warning.message().contains("end-batch"));
    }

    // ---- The bulk arm: the sibling entry point -----------------------------------------------

    @Test
    public void executeBulk_shouldProjectTheDisclosure_onTheAnnotationOperation() {
        // Exactly two entry points reach the annotation prepares: the single-tool facade above,
        // and this one, which bulk-mutate and begin-batch both run. The measurement is taken in
        // the prepare, so both entry points have it — but the bulk projection reduces every
        // entity to a type and a name, and would have dropped it. A disclosure present on one
        // entry point and absent on its sibling makes the absence uninformative on both.
        routedPair();

        BulkMutationResult result = accessor.executeBulk("default",
                List.of(new net.vheerden.archi.mcp.response.dto.BulkOperation("add-note-to-view",
                        java.util.Map.of("viewId", view.getId(), "content", "Legend",
                                "x", ON_ROUTE_X, "y", ON_ROUTE_Y, "width", 120, "height", 60))),
                null, false);

        assertTrue(result.allSucceeded());
        StructuredWarningDto warning =
                corridorWarning(result.operations().get(0).structuredWarnings());
        assertNotNull("the bulk operation result must carry the same disclosure: "
                + result.operations().get(0).structuredWarnings(), warning);
        assertEquals(List.of("conn-r1"), warning.remediationViolatorIds());
    }

    @Test
    public void executeBulk_shouldCarryNoDisclosure_whenTheAnnotationClearsEveryRoute() {
        routedPair();

        BulkMutationResult result = accessor.executeBulk("default",
                List.of(new net.vheerden.archi.mcp.response.dto.BulkOperation("add-note-to-view",
                        java.util.Map.of("viewId", view.getId(), "content", "Legend",
                                "x", 280, "y", 400, "width", 120, "height", 60))),
                null, false);

        assertTrue(result.allSucceeded());
        assertTrue("an empty list, not a finding",
                result.operations().get(0).structuredWarnings().isEmpty());
    }

    @Test
    public void addViewReferenceToView_shouldKeepTheDisclosureOnTheProposalAndQueuedArms()
            throws Exception {
        // The note has cross-arm proof; the two tools whose crossings are RATED had only an
        // immediate-arm test, which is the thinner proof on the more damaging half. A warning can
        // be built correctly and still be dropped downstream by the arm that reshapes the
        // response — which is the defect the note's approval test exists to catch.
        routedPair();
        dispatcher.setApprovalModeProvider(() -> true);

        MutationResult<EmbeddedViewDto> proposed = accessor.addViewReferenceToView(SESSION,
                view.getId(), otherView.getId(), ON_ROUTE_X, ON_ROUTE_Y, 120, 60, null, null);
        assertTrue("this must be the approval arm", proposed.isProposal());
        String json = net.vheerden.archi.mcp.handlers.HandlerUtils.formatProposalResponse(
                        proposed.entity(), proposed.proposalContext(), "1",
                        new net.vheerden.archi.mcp.response.ResponseFormatter())
                .content().toString();
        assertTrue("the rated disclosure must survive into the proposal preview: " + json,
                json.contains(StructuredWarningCodes.ANNOTATION_PLACED_IN_ROUTED_CORRIDOR)
                        && json.contains("conn-r1"));

        dispatcher.setApprovalModeProvider(() -> false);
        dispatcher.beginBatch(SESSION, "queued view-reference");
        MutationResult<EmbeddedViewDto> queued = accessor.addViewReferenceToView(SESSION,
                view.getId(), otherView.getId(), ON_ROUTE_X, ON_ROUTE_Y, 120, 60, null, null);
        assertTrue("this must be the queued arm", queued.isBatched());
        StructuredWarningDto warning = corridorWarning(queued.entity().structuredWarnings());
        assertNotNull("the queued arm must not go silent on the rated tool either", warning);
        assertTrue("and it must carry the deferred basis: " + warning.message(),
                warning.message().contains("nothing in this call has been applied yet"));
        dispatcher.endBatch(SESSION, false);
    }

    // ---- Deferred state: the two arms whose commands have not run yet ------------------------

    @Test
    public void detect_shouldDeclineToMeasure_whenTheParentIsNotOnTheViewYet() {
        // A batch or a bulk call can name a container it created moments earlier: the accessor
        // resolves that to a REAL object, but one nothing has attached to the view. Its absolute
        // position is therefore unknown here. Treating the unknown as an origin of (0,0) would
        // silently read the annotation's parent-relative coordinates as absolute ones and compare
        // a rectangle from somewhere else entirely against the routes — inventing a crossing or
        // hiding one, under a message claiming the routes were measured.
        routedPair();
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IApplicationComponent concept = f.createApplicationComponent();
        concept.setId("detached");
        concept.setName("Created earlier in this same call");
        model.getFolder(FolderType.APPLICATION).getElements().add(concept);
        IDiagramModelArchimateObject detached = f.createDiagramModelArchimateObject();
        detached.setId("obj-detached");
        detached.setArchimateConcept(concept);
        detached.setBounds(240, -20, 200, 200);
        // deliberately NOT added to view.getChildren()

        // The relative rectangle is chosen so that reading it as ABSOLUTE lands it squarely on the
        // route: without the fix this call returns a warning, which is the wrong answer arrived at
        // by measuring the wrong rectangle. The pin therefore fails when the fix is removed.
        assertNull("an origin that cannot be established must decline, not default to (0,0)",
                AnnotationCorridorWarning.detect(view, detached, 40, 0, 200, 60,
                        AnnotationCorridorWarning.Kind.NOTE, DispatchArm.QUEUED));
    }

    @Test
    public void detect_shouldStillMeasure_whenTheSameRectangleHasAnAttachedParent() {
        // The negative control for the pin above: identical parent bounds and identical relative
        // coordinates, differing only in whether the parent is on the view. Without this, a
        // detect() that simply always returned null would pass the pin above.
        routedPair();
        element("host", "Host", 240, -20, 200, 200);

        assertNotNull("an attached parent resolves, so the measurement is taken",
                AnnotationCorridorWarning.detect(view, find("obj-host"), 40, 0, 200, 60,
                        AnnotationCorridorWarning.Kind.NOTE, DispatchArm.QUEUED));
    }

    @Test
    public void executeBulk_shouldCarryTheDeferredBasis_becauseBulkPreparesBeforeItApplies() {
        // bulk-mutate prepares EVERY operation in its list before dispatching any of them, so an
        // annotation's corridor check runs against a model its own siblings have not touched —
        // structurally the same uncertainty as an open batch. Reading only the batch mode missed
        // this arm entirely and published an unqualified answer.
        routedPair();

        BulkMutationResult result = accessor.executeBulk("default",
                List.of(new net.vheerden.archi.mcp.response.dto.BulkOperation("add-note-to-view",
                        java.util.Map.of("viewId", view.getId(), "content", "Legend",
                                "x", ON_ROUTE_X, "y", ON_ROUTE_Y, "width", 120, "height", 60))),
                null, false);

        StructuredWarningDto warning =
                corridorWarning(result.operations().get(0).structuredWarnings());
        assertNotNull(warning);
        assertTrue("a bulk call has not been applied when the check runs, and must say so: "
                        + warning.message(),
                warning.message().contains("nothing in this call has been applied yet"));
    }

    // ---- The rated metric carves out endpoints; the note metric does not ---------------------

    @Test
    public void detect_shouldNotClaimARatedCrossing_whenNestedInsideAnEndpoint() {
        // connectionPassThroughs skips a connection's own source and target together with their
        // ancestors and descendants, so an annotation nested inside an endpoint is never counted
        // there. Warning about it would promise a rating cost the assessment will not charge.
        endpointHostWithRouteAcross();

        assertNull("a view-reference inside the route's own source is not a rated pass-through",
                AnnotationCorridorWarning.detect(view, find("obj-host"), 700, 0, 200, 80,
                        AnnotationCorridorWarning.Kind.VIEW_REFERENCE, DispatchArm.APPLIED));
        assertNull("and neither is an image",
                AnnotationCorridorWarning.detect(view, find("obj-host"), 700, 0, 200, 80,
                        AnnotationCorridorWarning.Kind.IMAGE, DispatchArm.APPLIED));
    }

    @Test
    public void detect_shouldStillFlagANote_inTheSamePlace() {
        // The discriminating half. connectionThroughNoteCount carves nothing out — "a Note has no
        // source/target, so every connection is tested against every Note's rectangle" — so the
        // identical rectangle IS a crossing for a note. A carve-out applied to all three kinds
        // would silence this one too, and a carve-out applied to none would over-claim above.
        endpointHostWithRouteAcross();

        StructuredWarningDto warning = AnnotationCorridorWarning.detect(view, find("obj-host"),
                700, 0, 200, 80, AnnotationCorridorWarning.Kind.NOTE, DispatchArm.APPLIED);
        assertNotNull("the note metric excludes nothing, so this rectangle is crossed", warning);
        assertEquals(List.of("conn-r1"), warning.remediationViolatorIds());
    }

    /**
     * A host element that is the route's own SOURCE, with the connection running horizontally
     * clear of it, and room for an annotation nested in the host but positioned outside its
     * bounds — which Archi permits and the assessor reports separately as a boundary violation.
     * That is what puts a descendant of an endpoint onto that endpoint's own route.
     */
    private void endpointHostWithRouteAcross() {
        element("host", "Host", 0, 0, 120, 55);
        element("far", "Far", 1400, 0, 120, 55);
        addConnection("r1", "host", "far");
    }

    // ---- The image tool: reachable only below the facade -----------------------------------
    //
    // add-image-to-view validates its imagePath against the model archive before it resolves any
    // geometry, and no automated lane provides an IArchiveManager — the class that covers that
    // validation is excluded from both passes for exactly this reason. So the image arm is pinned
    // where it CAN execute: the measurement itself, and the wiring that reaches it. Stating the
    // gap is the point; a silent omission would leave the rated half of this disclosure untested
    // and looking covered.

    @Test
    public void detect_shouldNameTheRatedMetric_forAnImage() {
        routedPair();

        StructuredWarningDto warning = AnnotationCorridorWarning.detect(view, view,
                ON_ROUTE_X, ON_ROUTE_Y, 120, 60, AnnotationCorridorWarning.Kind.IMAGE, DispatchArm.APPLIED);

        assertNotNull("an image dropped on a live route must say so", warning);
        assertTrue("a standalone image is an ordinary layout node, so a crossing is RATED: "
                        + warning.message(),
                warning.message().contains("connectionPassThroughs")
                        && warning.message().contains("RATED"));
        assertEquals(List.of("conn-r1"), warning.remediationViolatorIds());
        assertEquals("update-view-object", warning.remediationTool());
    }

    @Test
    public void detect_shouldMeasureANestedAnnotationInAbsoluteCoordinates() {
        // A nested annotation's stored bounds are relative to its parent while every route is
        // absolute. Comparing the two frames directly is the bug this pin exists for: the same
        // relative rectangle is on the route under one parent and nowhere near it under another.
        routedPair();
        IDiagramModelArchimateObject host = element("host", "Host", 240, -20, 200, 200);

        assertNotNull("relative (40, 20) inside a parent at (240, -20) is absolute (280, 0)",
                AnnotationCorridorWarning.detect(view, host, 40, 20, 120, 60,
                        AnnotationCorridorWarning.Kind.NOTE, DispatchArm.APPLIED));
        assertNull("the same relative rectangle read as absolute would be at (40, 20), which "
                        + "nothing routes through",
                AnnotationCorridorWarning.detect(view, view, 40, 20, 120, 60,
                        AnnotationCorridorWarning.Kind.NOTE, DispatchArm.APPLIED));
    }

    @Test
    public void allThreeAnnotationPrepares_shouldReachTheSameMeasurement() {
        // The census this story owed: which entry points carry the disclosure. All three prepares
        // call the one collaborator, each with its own severity, so the two tools whose crossings
        // are RATED cannot be the ones nobody wired. The image call is asserted here because its
        // facade cannot be executed in any automated lane — see the note above.
        String facade = readProductionSource("model/ArchiModelAccessorImpl.java");

        assertEquals("add-note-to-view must measure exactly once", 1,
                countOf(facade, "AnnotationCorridorWarning.Kind.NOTE"));
        assertEquals("add-view-reference-to-view must measure exactly once", 1,
                countOf(facade, "AnnotationCorridorWarning.Kind.VIEW_REFERENCE"));
        assertEquals("add-image-to-view must measure exactly once", 1,
                countOf(facade, "AnnotationCorridorWarning.Kind.IMAGE"));
        // Both gates, at all three sites. isQueuedCall alone was what made this family
        // approval-blind: it answers "is this write being held?" and not "who is holding it", so a
        // call awaiting a human's decision looked identical to an immediate one. Counting the
        // composed resolution rather than either gate on its own is what keeps a future edit from
        // dropping one of them and leaving the count intact.
        assertEquals("and every one of them must ask which ARM it is on -- both gates, not just "
                        + "the deferral one", 3,
                countOf(facade, "DispatchArm.of(mutationDispatcher.isApprovalRequired(sessionId), "
                        + "isQueuedCall(sessionId))"));
        assertEquals("bulk reachability is why the deferral gate stays isQueuedCall here rather "
                        + "than the dispatcher's batch-only reading: a bulk call defers without "
                        + "the session ever entering batch mode", 3,
                countOf(facade, "isQueuedCall(sessionId)"));
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    /** Reads a production source file, failing rather than silently covering nothing. */
    private static String readProductionSource(String relativePath) {
        for (String candidate : new String[]{
                "../net.vheerden.archi.mcp/src", "net.vheerden.archi.mcp/src"}) {
            java.nio.file.Path path =
                    java.nio.file.Paths.get(candidate, "net/vheerden/archi/mcp", relativePath);
            if (java.nio.file.Files.isRegularFile(path)) {
                try {
                    return new String(java.nio.file.Files.readAllBytes(path),
                            java.nio.charset.StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        }
        throw new AssertionError("Could not resolve " + relativePath
                + " from " + java.nio.file.Paths.get("").toAbsolutePath()
                + " — this gate cannot silently cover nothing");
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static StructuredWarningDto corridorWarning(List<StructuredWarningDto> warnings) {
        if (warnings == null) {
            return null;
        }
        return warnings.stream()
                .filter(w -> StructuredWarningCodes.ANNOTATION_PLACED_IN_ROUTED_CORRIDOR
                        .equals(w.code()))
                .findFirst().orElse(null);
    }

    /** Two elements whose centres share a row, with nothing connecting them. */
    private void elements() {
        element("a", "A", 0, 0, 120, 55);
        element("b", "B", 600, 0, 120, 55);
    }

    /** The same pair, joined by a straight connection: a horizontal corridor at y = 27. */
    private void routedPair() {
        elements();
        addConnection("r1", "a", "b");
    }

    private IDiagramModelArchimateObject element(String id, String name,
            int x, int y, int w, int h) {
        IApplicationComponent concept = factory.createApplicationComponent();
        concept.setId(id);
        concept.setName(name);
        model.getFolder(FolderType.APPLICATION).getElements().add(concept);

        IDiagramModelArchimateObject obj = factory.createDiagramModelArchimateObject();
        obj.setId("obj-" + id);
        obj.setArchimateConcept(concept);
        obj.setBounds(x, y, w, h);
        view.getChildren().add(obj);
        return obj;
    }

    private void addConnection(String id, String sourceId, String targetId) {
        IDiagramModelArchimateObject source = find("obj-" + sourceId);
        IDiagramModelArchimateObject target = find("obj-" + targetId);
        IArchimateRelationship relationship = factory.createAssociationRelationship();
        relationship.setId(id);
        relationship.setSource(source.getArchimateElement());
        relationship.setTarget(target.getArchimateElement());
        model.getFolder(FolderType.RELATIONS).getElements().add(relationship);

        IDiagramModelArchimateConnection connection =
                factory.createDiagramModelArchimateConnection();
        connection.setId("conn-" + id);
        connection.setArchimateConcept(relationship);
        connection.connect(source, target);
    }

    private IDiagramModelArchimateObject find(String viewObjectId) {
        return view.getChildren().stream()
                .filter(c -> viewObjectId.equals(c.getId()))
                .map(IDiagramModelArchimateObject.class::cast)
                .findFirst().orElseThrow();
    }

    private static class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final List<PropertyChangeListener> listeners = new ArrayList<>();

        void setModels(List<IArchimateModel> models) { this.models = models; }

        @Override public List<IArchimateModel> getModels() { return models; }
        @Override public void addPropertyChangeListener(PropertyChangeListener l) { listeners.add(l); }
        @Override public void removePropertyChangeListener(PropertyChangeListener l) { listeners.remove(l); }
        @Override public IArchimateModel createNewModel() { return null; }
        @Override public void registerModel(IArchimateModel m) {}
        @Override public IArchimateModel openModel(File file) { return null; }
        @Override public void openModel(IArchimateModel m) {}
        @Override public IArchimateModel loadModel(File file) { return null; }
        @Override public IArchimateModel load(File file) throws IOException { return null; }
        @Override public boolean closeModel(IArchimateModel m) throws IOException { return false; }
        @Override public boolean closeModel(IArchimateModel m, boolean askSave) throws IOException { return false; }
        @Override public boolean isModelLoaded(File file) { return false; }
        @Override public boolean isModelDirty(IArchimateModel m) { return false; }
        @Override public boolean saveModel(IArchimateModel m) throws IOException { return false; }
        @Override public boolean saveModelAs(IArchimateModel m) throws IOException { return false; }
        @Override public void saveState() throws IOException {}
        @Override public void firePropertyChange(Object src, String p, Object oldV, Object newV) {}
    }
}
