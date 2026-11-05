package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.BatchSummaryDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.LayoutEntryFailure;
import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionSpec;
import net.vheerden.archi.mcp.response.dto.ViewPositionSpec;

/**
 * Pins that a connection's {@code absoluteBendpoints} are stored relative to where its ends are when
 * the change is applied, not where they were when the call was prepared.
 *
 * <p>Archi stores each bendpoint as an offset from the source's centre and one from the target's.
 * Every entry point prepares all of its commands before any of them runs, so when the same
 * {@code apply-positions} call, an earlier operation of a {@code bulk-mutate}, or an earlier queued
 * operation of a batch moves an end, a conversion done at prepare measures that end's offsets from
 * its old centre. The two reconstructions of the stored route then stop meeting: drawn from the
 * source the line passes through the points sent, drawn from the target it is shifted by however
 * far the end moved. Each fix pin below asserts on the model after the change has run, never on
 * the prepare-time response, and each stored that sheared route before the conversion moved into
 * the command.</p>
 *
 * <p>The controls pin what must not change: relative bendpoints are the caller's own frame and are
 * stored as sent, a call that moves no end stores what it always stored, a fit that grows a
 * parent group without moving its origin leaves the conversion alone, and a rolled-back batch
 * stores nothing.</p>
 *
 * <p>Same headless idiom as {@code BatchAutoRouteQueuedGeometryTest}: a real GEF
 * {@link CommandStack}, queued compounds rebuilt into plain GEF compounds, explicit bounds, and
 * unnamed elements so no label is measured through SWT.</p>
 */
public class AbsoluteBendpointsExecuteTimeTest {

    private static final String SESSION = "absolute-bendpoints-execute-time-session";

    /** Down from the source's right half, across, and into the target's top: B at 700,600. */
    private static final List<AbsoluteBendpointDto> SENT =
            List.of(new AbsoluteBendpointDto(610, 30), new AbsoluteBendpointDto(610, 630));

    /** What the baseline stored for {@link #SENT} with B at 700,200, which is also what a no-move call stores. */
    private static final List<BendpointDto> BEFORE_ANY_MOVE =
            List.of(new BendpointDto(150, 0, -150, -200), new BendpointDto(150, 600, -150, 400));

    /**
     * {@link #SENT} stored for B at 700,600, written out rather than derived, so a defect shared by
     * the conversion and the centre helper the assertions use cannot cancel out.
     */
    private static final List<BendpointDto> AFTER_B_AT_700_600 =
            List.of(new BendpointDto(150, 0, -150, -600), new BendpointDto(150, 600, -150, 0));

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private MutationDispatcher dispatcher;
    private ArchiModelAccessorImpl accessor;
    private CommandStack stack;
    private boolean approvalRequired;
    private IArchimateElement elC;
    private IDiagramModelArchimateObject a;
    private IDiagramModelArchimateObject b;
    private IDiagramModelArchimateConnection ab;

    @Before
    public void setUp() {
        model = factory.createArchimateModel();
        model.setId("model-absolute-bendpoints-execute-time");
        model.setDefaults();
        view = factory.createArchimateDiagramModel();
        view.setId("view-absolute");
        view.setConnectionRouterType(IDiagramModel.CONNECTION_ROUTER_BENDPOINT);
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        IArchimateElement elA = element("el-a");
        IArchimateElement elB = element("el-b");
        elC = element("el-c");
        a = object(view, "vo-a", elA, 400, 0, 120, 60);
        b = object(view, "vo-b", elB, 700, 200, 120, 60);
        ab = factory.createDiagramModelArchimateConnection();
        ab.setId("conn-ab");
        ab.setArchimateRelationship(relationship("rel-ab", elA, elB));
        ab.connect(a, b);

        AutoRouteArmFixture.StubEditorModelManager mgr = new AutoRouteArmFixture.StubEditorModelManager();
        mgr.setModels(List.of(model));
        stack = new CommandStack();
        model.setAdapter(CommandStack.class, stack);
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                stack.execute(new AgentAuthoredCompoundCommand(toPlainCompound(command)));
            }
            @Override
            protected void dispatchCommand(Command command) {
                stack.execute(new AgentAuthoredCompoundCommand(toPlainCompound(command)));
            }
        };
        dispatcher.setApprovalModeProvider(() -> approvalRequired);
        accessor = new ArchiModelAccessorImpl(mgr, dispatcher);
        dispatcher.onModelActive(model);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ---- fixed: the end moves in the same call ------------------------------------------------------

    @Test
    public void applyPositions_shouldStoreTheSentPoints_whenTheSameCallMovesTheTarget() {
        layout(List.of(position("vo-b", 700, 600)));
        assertEquals(600, b.getBounds().getY());
        assertStoredThroughSentPoints(SENT);
        assertEquals(AFTER_B_AT_700_600, ConnectionResponseBuilder.collectBendpoints(ab));
    }

    @Test
    public void applyPositions_shouldStoreTheSentPoints_whenTheSameCallMovesTheSource() {
        List<AbsoluteBendpointDto> sent =
                List.of(new AbsoluteBendpointDto(460, 230), new AbsoluteBendpointDto(640, 230));
        accessor.applyViewLayout(SESSION, view.getId(), List.of(position("vo-a", 400, 400)),
                List.of(new ViewConnectionSpec("conn-ab", null, sent)), null);
        assertEquals(400, a.getBounds().getY());
        assertStoredThroughSentPoints(sent);
    }

    @Test
    public void bulkMutate_shouldStoreAndReportTheSentPoints_whenAnEarlierOperationMovesTheTarget() {
        List<Map<String, Object>> points = new ArrayList<>();
        for (AbsoluteBendpointDto p : SENT) {
            points.add(Map.of("x", p.x(), "y", p.y()));
        }
        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("update-view-object", Map.of("viewObjectId", "vo-b", "x", 700, "y", 600), null),
                new BulkOperation("update-view-connection",
                        Map.of("viewConnectionId", "conn-ab", "absoluteBendpoints", points), null)),
                "move then route", false);
        assertTrue(result.allSucceeded());
        assertStoredThroughSentPoints(SENT);
        ViewConnectionDto reported = result.operations().get(1).effectiveConnection();
        assertNotNull("the applied bulk entry reports the connection read after the write", reported);
        assertEquals("and that report is the stored route", SENT, reported.absoluteBendpoints());
    }

    @Test
    public void applyPositions_shouldStoreTheSentPoints_whenTheCallMovesTheGroupHoldingTheTarget() {
        nestTargetInGroup();

        layout(List.of(position("grp", 600, 550)));
        assertEquals("the target itself has no entry, it moves with its group", 50, b.getBounds().getY());
        assertStoredThroughSentPoints(SENT);
        assertEquals("B's absolute centre is 760,630 again", AFTER_B_AT_700_600,
                ConnectionResponseBuilder.collectBendpoints(ab));
    }

    @Test
    public void applyPositions_shouldStoreTheSentPoints_whenTheCallsFitMovesTheOriginOfTheTargetsGroup() {
        IDiagramModelGroup group = nestTargetInGroup();
        object(group, "vo-d", elC, 10, 10, 60, 40);
        // up and to the left of the group, so the fit grows it that way and its origin moves
        layout(List.of(position("vo-d", -80, -40)));
        assertTrue("the fit moved the group's origin: " + group.getBounds(),
                group.getBounds().getX() < 600 && group.getBounds().getY() < 150);
        assertEquals("without rebasing the target", 100, b.getBounds().getX());
        assertStoredThroughSentPoints(SENT);
    }

    @Test
    public void bulkMutate_shouldStoreTheSentPoints_whenAnEarlierOperationMovesTheTargetsGroup() {
        IDiagramModelGroup group = nestTargetInGroup();
        List<Map<String, Object>> points = new ArrayList<>();
        for (AbsoluteBendpointDto p : SENT) {
            points.add(Map.of("x", p.x(), "y", p.y()));
        }
        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("update-view-object", Map.of("viewObjectId", "grp", "x", 600, "y", 550), null),
                new BulkOperation("update-view-connection",
                        Map.of("viewConnectionId", "conn-ab", "absoluteBendpoints", points), null)),
                "move the group then route", false);
        assertTrue(result.allSucceeded());
        assertEquals(550, group.getBounds().getY());
        assertStoredThroughSentPoints(SENT);
        assertEquals(AFTER_B_AT_700_600, ConnectionResponseBuilder.collectBendpoints(ab));
    }

    @Test
    public void applyPositions_shouldStoreTheSentPoints_whenTheTargetMovesAsAnAnchoredChild() {
        object(view, "vo-c", elC, 700, 100, 120, 60);
        accessor.updateViewObject(SESSION, "vo-b", null, null, null, null, null, null, null, null,
                "vo-c", "below", 0, 40);
        assertEquals("anchored below C", 200, b.getBounds().getY());

        layout(List.of(position("vo-c", 700, 500)));
        assertEquals("moved by its anchor, not by an entry", 600, b.getBounds().getY());
        assertStoredThroughSentPoints(SENT);
    }

    // ---- fixed: the end moves earlier in the batch --------------------------------------------------

    @Test
    public void applyPositions_shouldStoreTheSentPoints_whenTheBatchMovedTheTargetFirst() throws Exception {
        dispatcher.beginBatch(SESSION, "move then apply");
        move("vo-b", 700, 600);
        layout(null);
        dispatcher.endBatch(SESSION, true);
        assertStoredThroughSentPoints(SENT);
    }

    @Test
    public void updateViewConnection_shouldStoreTheSentPoints_whenTheBatchMovedTheTargetFirst() throws Exception {
        dispatcher.beginBatch(SESSION, "move then update");
        move("vo-b", 700, 600);
        MutationResult<ViewConnectionDto> queued =
                accessor.updateViewConnection(SESSION, "conn-ab", null, SENT, null, null, null);
        assertTrue(queued.isBatched());
        dispatcher.endBatch(SESSION, true);
        assertStoredThroughSentPoints(SENT);
    }

    // ---- fixed: approval, with a human edit between proposal and approval ---------------------------

    /**
     * The sent points are converted against the ends when the card runs, so applying it after the human
     * moved an end would still meet them; but the card is refused anyway, as it is for any connection
     * whose end a human moved during review. One rule for every tracked connection.
     */
    @Test
    public void applyPositions_shouldRefuseAsStale_whenAHumanMovedTheSourceBeforeApproval() {
        approvalRequired = true;
        MutationResult<?> proposed = accessor.applyViewLayout(SESSION, view.getId(),
                List.of(position("vo-b", 700, 600)), List.of(new ViewConnectionSpec("conn-ab", null, SENT)), null);
        approvalRequired = false;
        assertTrue(proposed.isProposal());
        // not agent-authored: the stack sees a human drag
        stack.execute(new Command("drag") {
            @Override
            public void execute() {
                a.setBounds(400, 100, 120, 60);
            }
            @Override
            public void undo() {
                a.setBounds(400, 0, 120, 60);
            }
        });
        List<BendpointDto> before = ConnectionResponseBuilder.collectBendpoints(ab);
        try {
            dispatcher.approveProposal(SESSION, proposed.proposalContext().proposalId());
            fail("a card over a moved end must be refused");
        } catch (MutationException e) {
            assertEquals(ProposalStalenessGuard.endMovedMessage("vo-a", "'vo-a' \u2192 'vo-b'"), e.getMessage());
        }
        assertEquals("the human's drag stands", 100, a.getBounds().getY());
        assertEquals("nothing was written", before, ConnectionResponseBuilder.collectBendpoints(ab));
        assertEquals("the target was not moved", 200, b.getBounds().getY());
    }

    // ---- undo and redo ------------------------------------------------------------------------------

    @Test
    public void applyPositions_shouldRestoreThenRewriteTheSameRoute_whenUndoneAndRedone() {
        List<BendpointDto> pre = List.of(new BendpointDto(10, 20, -290, -180));
        ConnectionResponseBuilder.applyBendpointsToConnection(ab, pre);
        layout(List.of(position("vo-b", 700, 600)));
        List<BendpointDto> applied = ConnectionResponseBuilder.collectBendpoints(ab);
        assertStoredThroughSentPoints(SENT);

        stack.undo();
        assertEquals("undo restores the route held before the call", pre,
                ConnectionResponseBuilder.collectBendpoints(ab));
        assertEquals("and the target's bounds", 200, b.getBounds().getY());

        stack.redo();
        assertEquals("redo stores what the call stored", applied, ConnectionResponseBuilder.collectBendpoints(ab));
        assertStoredThroughSentPoints(SENT);
    }

    @Test
    public void updateViewConnection_shouldRedoWhatItFirstWrote_whenAnEndMovedWhileItWasUndone() {
        accessor.updateViewConnection(SESSION, "conn-ab", null, SENT, null, null, null);
        assertEquals(BEFORE_ANY_MOVE, ConnectionResponseBuilder.collectBendpoints(ab));
        stack.undo();
        // outside the stack, so the redo stays available
        b.setBounds(700, 600, 120, 60);
        stack.redo();
        assertEquals("a redo repeats the first write, it does not convert again", BEFORE_ANY_MOVE,
                ConnectionResponseBuilder.collectBendpoints(ab));
    }

    // ---- the command still writes something defined when it cannot convert -------------------------

    @Test
    public void updateViewConnection_shouldDeclineInTheBatch_whenTheConnectionWasRemovedBeforeItRuns() throws Exception {
        List<BendpointDto> before = ConnectionResponseBuilder.collectBendpoints(ab);
        dispatcher.beginBatch(SESSION, "remove then update");
        move("vo-b", 700, 600);
        accessor.removeFromView(SESSION, view.getId(), "conn-ab");
        accessor.updateViewConnection(SESSION, "conn-ab", null, SENT, null, null, null);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);
        assertFalse("the commit ran rather than rolling back", summary.rolledBack());
        assertEquals("only the update declined", 1, summary.skippedOperations().size());
        assertTrue(summary.skippedOperations().get(0),
                summary.skippedOperations().get(0).contains("was not updated"));
        assertEquals("the rest of the batch committed", 600, b.getBounds().getY());
        assertFalse("the connection is off the view", a.getSourceConnections().contains(ab));
        assertEquals("the off-view connection was left as it was", before,
                ConnectionResponseBuilder.collectBendpoints(ab));
    }

    @Test
    public void command_shouldWriteThePreparedList_whenItsEndsAreGoneAtExecute() {
        List<BendpointDto> prepared = List.of(new BendpointDto(1, 2, 3, 4));
        UpdateViewConnectionCommand cmd = new UpdateViewConnectionCommand(ab,
                ConnectionResponseBuilder.createEmfBendpoints(prepared), SENT, null, null, null);
        ab.disconnect();
        cmd.execute();
        assertEquals(prepared, ConnectionResponseBuilder.collectBendpoints(ab));
    }

    @Test
    public void command_shouldKeepItsRelativeMode_whenBuiltWithoutCanvasPoints() {
        List<BendpointDto> given = List.of(new BendpointDto(1, 2, 3, 4));
        for (List<AbsoluteBendpointDto> none : java.util.Arrays.asList(null, List.<AbsoluteBendpointDto>of())) {
            UpdateViewConnectionCommand cmd = new UpdateViewConnectionCommand(ab,
                    ConnectionResponseBuilder.createEmfBendpoints(given), none, null, null, null);
            assertNull(cmd.getAbsoluteBendpoints());
            assertTrue(cmd.hasBendpointChange());
            b.setBounds(700, 600, 120, 60);
            cmd.execute();
            assertEquals("relative bendpoints are written as given", given,
                    ConnectionResponseBuilder.collectBendpoints(ab));
            b.setBounds(700, 200, 120, 60);
        }
        UpdateViewConnectionCommand noChange = new UpdateViewConnectionCommand(ab, null, SENT, null, null, null);
        assertFalse("canvas points do not turn a no-change command into a write", noChange.hasBendpointChange());
        assertNull(noChange.getAbsoluteBendpoints());
    }

    // ---- the queued-geometry refusal still sees an absolute edit as a bendpoint edit ----------------

    @Test
    public void autoRouteTerminalsOnly_shouldRefuse_whenTheBatchEditedBendpointsAsCanvasPoints() throws Exception {
        dispatcher.beginBatch(SESSION, "absolute edit then rectify");
        accessor.updateViewConnection(SESSION, "conn-ab", null, SENT, null, null, null);
        try {
            accessor.autoRouteConnections(SESSION, view.getId(), null, null, false, false,
                    RoutingPipeline.DEFAULT_SNAP_THRESHOLD, RoutingPipeline.DEFAULT_PERIMETER_MARGIN,
                    "terminals-only");
            fail("terminals-only must refuse after a queued bendpoint edit");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue(e.getMessage(), e.getMessage().contains("connection bendpoints edited: conn-ab"));
        } finally {
            dispatcher.endBatch(SESSION, false);
        }
    }

    // ---- fixed: add-connection-to-view converts when the connection is created ---------------------

    @Test
    public void addConnectionToView_shouldStoreTheSentPoints_whenTheBatchMovedTheTargetFirst() throws Exception {
        IArchimateRelationship second = relationship("rel-ab2", a.getArchimateElement(), b.getArchimateElement());
        dispatcher.beginBatch(SESSION, "move then add");
        move("vo-b", 700, 600);
        String id = accessor.addConnectionToView(SESSION, view.getId(), second.getId(), "vo-a", "vo-b",
                null, SENT, null, null, null).entity().viewConnectionId();
        dispatcher.endBatch(SESSION, true);
        assertEquals("converted against B at 700,600, where it is when the connection is created", AFTER_B_AT_700_600,
                ConnectionResponseBuilder.collectBendpoints(connection(id)));
    }

    @Test
    public void addConnectionToView_shouldStoreTheSentPoints_whenAnEarlierBulkOperationMovedTheTarget() {
        IArchimateRelationship second = relationship("rel-ab2", a.getArchimateElement(), b.getArchimateElement());
        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("update-view-object", Map.of("viewObjectId", "vo-b", "x", 700, "y", 600), null),
                new BulkOperation("add-connection-to-view", Map.of("viewId", view.getId(),
                        "relationshipId", second.getId(), "sourceViewObjectId", "vo-a", "targetViewObjectId", "vo-b",
                        "absoluteBendpoints", List.of(Map.of("x", 610, "y", 30), Map.of("x", 610, "y", 630))), null)),
                "move then add", false);
        assertTrue(result.allSucceeded());
        assertEquals(AFTER_B_AT_700_600,
                ConnectionResponseBuilder.collectBendpoints(connection(result.operations().get(1).entityId())));
    }

    @Test
    public void bulkMutate_shouldRefuseCanvasPoints_forAConnectionAnEarlierOperationAdded() {
        IArchimateRelationship second = relationship("rel-ab2", a.getArchimateElement(), b.getArchimateElement());
        int before = a.getSourceConnections().size();
        try {
            BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                    new BulkOperation("add-connection-to-view", Map.of("viewId", view.getId(),
                            "relationshipId", second.getId(), "sourceViewObjectId", "vo-a", "targetViewObjectId", "vo-b"), null),
                    new BulkOperation("update-view-connection", Map.of("viewConnectionId", "$0.id",
                            "absoluteBendpoints", List.of(Map.of("x", 610, "y", 30))), null)),
                    "add then route", false);
            fail("expected the canvas points to be refused, got " + result);
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BULK_VALIDATION_FAILED, e.getErrorCode());
            assertNotConnectedYet("Operation 1 (update-view-connection): ", e.getMessage());
            assertEquals(NOT_CONNECTED_YET_SUGGESTION, e.getSuggestedCorrection());
        }
        assertEquals("nothing was added", before, a.getSourceConnections().size());
    }

    // ---- the refusal names its cause ----------------------------------------------------------------

    /*
     * A connection the batch or bulk-mutate added has no ends until its add runs, so the refusal
     * says that, not that an end is the wrong kind of object. The strings are written out here,
     * never read from production, so the pins cannot agree with whatever wording ships.
     */

    private static final String NOT_CONNECTED_YET_SUGGESTION =
            "Give the route as absoluteBendpoints on the add-connection-to-view that adds it, use "
                    + "relative bendpoints (startX/startY/endX/endY), or update the connection once it is "
                    + "committed: after end-batch, or after the bulk-mutate returns when no batch is open";

    private static final String WRONG_KIND_OF_END =
            "Cannot use absoluteBendpoints: connection endpoints are not ArchiMate view objects";

    private static final String WRONG_KIND_OF_END_SUGGESTION =
            "Use relative bendpoints (startX/startY/endX/endY) instead";

    private static String notConnectedYet(String connectionId) {
        return "Cannot use absoluteBendpoints: connection '" + connectionId + "' was added earlier in this "
                + "batch or bulk-mutate and is not connected to its ends until that is committed";
    }

    /** For a connection whose generated id the caller never saw, because the call that added it was refused. */
    private static void assertNotConnectedYet(String prefix, String message) {
        String id = "id-[0-9a-f]{32}";
        assertTrue("got: " + message, message.matches(Pattern.quote(prefix)
                + Pattern.quote("Cannot use absoluteBendpoints: connection '") + id
                + Pattern.quote("' was added earlier in this batch or bulk-mutate and is not "
                        + "connected to its ends until that is committed")));
    }

    private ModelAccessException refusal(Runnable call) {
        try {
            call.run();
        } catch (ModelAccessException e) {
            return e;
        }
        throw new AssertionError("expected the canvas points to be refused");
    }

    private String addConnection(IArchimateRelationship rel, String source, String target) {
        return accessor.addConnectionToView(SESSION, view.getId(), rel.getId(), source, target,
                null, null, null, null, null).entity().viewConnectionId();
    }

    private int queued() {
        return dispatcher.getBatchStatus(SESSION).queuedCount();
    }

    @Test
    public void bulkMutate_shouldNameTheUnconnectedConnection_whenItUpdatesOneTheOpenBatchAdded() throws Exception {
        dispatcher.beginBatch(SESSION, "add, then route it through a bulk");
        try {
            String id = addConnection(relationship("rel-ab2", a.getArchimateElement(), b.getArchimateElement()),
                    "vo-a", "vo-b");
            int before = queued();
            ModelAccessException e = refusal(() -> accessor.executeBulk(SESSION, List.of(
                    new BulkOperation("update-view-connection", Map.of("viewConnectionId", id,
                            "absoluteBendpoints", List.of(Map.of("x", 610, "y", 30))), null)),
                    "route the queued connection", false));
            assertEquals(ErrorCode.BULK_VALIDATION_FAILED, e.getErrorCode());
            assertEquals("Operation 0 (update-view-connection): " + notConnectedYet(id), e.getMessage());
            assertEquals(NOT_CONNECTED_YET_SUGGESTION, e.getSuggestedCorrection());
            assertEquals("nothing was queued", before, queued());
        } finally {
            dispatcher.endBatch(SESSION, false);
        }
    }

    @Test
    public void bulkMutate_shouldNameTheUnconnectedConnection_whenAnEarlierOperationAddedItInsideABatch()
            throws Exception {
        IArchimateRelationship second = relationship("rel-ab2", a.getArchimateElement(), b.getArchimateElement());
        dispatcher.beginBatch(SESSION, "add then route, in one bulk");
        try {
            ModelAccessException e = refusal(() -> accessor.executeBulk(SESSION, List.of(
                    new BulkOperation("add-connection-to-view", Map.of("viewId", view.getId(),
                            "relationshipId", second.getId(), "sourceViewObjectId", "vo-a", "targetViewObjectId", "vo-b"), null),
                    new BulkOperation("update-view-connection", Map.of("viewConnectionId", "$0.id",
                            "absoluteBendpoints", List.of(Map.of("x", 610, "y", 30))), null)),
                    "add then route", false));
            assertEquals(ErrorCode.BULK_VALIDATION_FAILED, e.getErrorCode());
            assertNotConnectedYet("Operation 1 (update-view-connection): ", e.getMessage());
            assertEquals(NOT_CONNECTED_YET_SUGGESTION, e.getSuggestedCorrection());
            assertEquals("nothing was queued", 0, queued());
        } finally {
            dispatcher.endBatch(SESSION, false);
        }
    }

    @Test
    public void updateViewConnection_shouldNameTheUnconnectedConnection_whenTheBatchAddedASelfConnection()
            throws Exception {
        dispatcher.beginBatch(SESSION, "loop, then route it");
        try {
            String id = addConnection(relationship("rel-aa", a.getArchimateElement(), a.getArchimateElement()),
                    "vo-a", "vo-a");
            int before = queued();
            ModelAccessException e = refusal(() -> accessor.updateViewConnection(SESSION, id, null,
                    List.of(new AbsoluteBendpointDto(610, 30)), null, null, null));
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertEquals(notConnectedYet(id), e.getMessage());
            assertEquals(NOT_CONNECTED_YET_SUGGESTION, e.getSuggestedCorrection());
            assertNull(e.getDetails());
            assertEquals("nothing was queued", before, queued());
        } finally {
            dispatcher.endBatch(SESSION, false);
        }
    }

    @Test
    public void applyPositions_shouldNameTheUnconnectedConnection_whenItRoutesOneTheOpenBatchAdded() throws Exception {
        dispatcher.beginBatch(SESSION, "add, then lay out");
        try {
            String id = addConnection(relationship("rel-ab2", a.getArchimateElement(), b.getArchimateElement()),
                    "vo-a", "vo-b");
            int before = queued();
            ModelAccessException e = refusal(() -> accessor.applyViewLayout(SESSION, view.getId(),
                    List.of(position("vo-b", 700, 600)),
                    List.of(new ViewConnectionSpec(id, null, List.of(new AbsoluteBendpointDto(610, 30))),
                            new ViewConnectionSpec("conn-ab", null, SENT)), null));
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertEquals("Connection entry [0] (viewConnectionId='" + id + "'): " + notConnectedYet(id),
                    e.getMessage());
            assertNull("this refusal keeps an entry's suggestion on its row", e.getSuggestedCorrection());
            List<LayoutEntryFailure> rows =
                    ((LayoutValidationException) e).getFailures();
            assertEquals("only the queued connection's entry failed: " + rows, 1, rows.size());
            assertEquals(NOT_CONNECTED_YET_SUGGESTION, rows.get(0).suggestedCorrection());
            assertEquals("nothing was queued", before, queued());
            assertEquals("the whole call was refused", 200, b.getBounds().getY());
        } finally {
            dispatcher.endBatch(SESSION, false);
        }
    }

    @Test
    public void updateViewConnection_shouldKeepTheWrongKindOfEndAnswer_whenTheSourceIsAConnection() {
        IDiagramModelArchimateObject c = object(view, "vo-c", elC, 100, 400, 120, 60);
        IDiagramModelArchimateConnection toConnection = factory.createDiagramModelArchimateConnection();
        toConnection.setId("conn-c-ab");
        toConnection.setArchimateRelationship(relationship("rel-c-ab", elC, ab.getArchimateRelationship()));
        toConnection.connect(c, ab);

        ModelAccessException e = refusal(() -> accessor.updateViewConnection(SESSION, "conn-c-ab", null,
                List.of(new AbsoluteBendpointDto(610, 30)), null, null, null));
        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertEquals(WRONG_KIND_OF_END, e.getMessage());
        assertEquals(WRONG_KIND_OF_END_SUGGESTION, e.getSuggestedCorrection());
    }

    @Test
    public void updateViewConnection_shouldKeepTheWrongKindOfEndAnswer_whenTheTargetIsAConnection() {
        IDiagramModelArchimateObject c = object(view, "vo-c", elC, 100, 400, 120, 60);
        IDiagramModelArchimateConnection fromConnection = factory.createDiagramModelArchimateConnection();
        fromConnection.setId("conn-ab-c");
        fromConnection.setArchimateRelationship(relationship("rel-ab-c", ab.getArchimateRelationship(), elC));
        fromConnection.connect(ab, c);

        ModelAccessException e = refusal(() -> accessor.updateViewConnection(SESSION, "conn-ab-c", null,
                List.of(new AbsoluteBendpointDto(610, 30)), null, null, null));
        assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        assertEquals(WRONG_KIND_OF_END, e.getMessage());
        assertEquals(WRONG_KIND_OF_END_SUGGESTION, e.getSuggestedCorrection());
    }

    // The remedies the refusal names: once the connection is committed, the same points convert.

    @Test
    public void updateViewConnection_shouldConvertTheSentPoints_onceTheBatchThatAddedTheConnectionCommits()
            throws Exception {
        dispatcher.beginBatch(SESSION, "add, commit, then route");
        String id = addConnection(relationship("rel-ab2", a.getArchimateElement(), b.getArchimateElement()),
                "vo-a", "vo-b");
        dispatcher.endBatch(SESSION, true);

        accessor.updateViewConnection(SESSION, id, null, SENT, null, null, null);
        assertStoredThroughSentPoints(connection(id), SENT);
    }

    @Test
    public void updateViewConnection_shouldConvertTheSentPoints_afterTheBulkThatAddedTheConnectionReturns() {
        BulkMutationResult added = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("add-connection-to-view", Map.of("viewId", view.getId(),
                        "relationshipId", relationship("rel-ab2", a.getArchimateElement(), b.getArchimateElement()).getId(),
                        "sourceViewObjectId", "vo-a", "targetViewObjectId", "vo-b"), null)),
                "add", false);
        String id = added.operations().get(0).entityId();

        accessor.updateViewConnection(SESSION, id, null, SENT, null, null, null);
        assertStoredThroughSentPoints(connection(id), SENT);
    }

    /** Inside a batch the bulk only queues its add, so its return is not the commit. */
    @Test
    public void updateViewConnection_shouldStillRefuse_afterABulkInsideTheOpenBatchReturns() throws Exception {
        dispatcher.beginBatch(SESSION, "bulk add inside a batch, then route");
        try {
            BulkMutationResult added = accessor.executeBulk(SESSION, List.of(
                    new BulkOperation("add-connection-to-view", Map.of("viewId", view.getId(),
                            "relationshipId", relationship("rel-ab2", a.getArchimateElement(), b.getArchimateElement()).getId(),
                            "sourceViewObjectId", "vo-a", "targetViewObjectId", "vo-b"), null)),
                    "add", false);
            String id = added.operations().get(0).entityId();
            assertEquals("the bulk's add is queued, not applied", 1, queued());

            ModelAccessException e = refusal(() -> accessor.updateViewConnection(SESSION, id, null, SENT,
                    null, null, null));
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertEquals(notConnectedYet(id), e.getMessage());
            assertEquals(NOT_CONNECTED_YET_SUGGESTION, e.getSuggestedCorrection());
        } finally {
            dispatcher.endBatch(SESSION, false);
        }
    }

    // ---- controls: unchanged ------------------------------------------------------------------------

    @Test
    public void applyPositions_shouldStoreWhatItAlwaysStored_whenNoEndMoves() {
        layout(null);
        assertEquals(BEFORE_ANY_MOVE, ConnectionResponseBuilder.collectBendpoints(ab));
    }

    @Test
    public void applyPositions_shouldStoreRelativeBendpointsAsSent_whenTheSameCallMovesTheTarget() {
        List<BendpointDto> sent = List.of(new BendpointDto(150, 0, -150, -200), new BendpointDto(150, 600, -150, 400));
        accessor.applyViewLayout(SESSION, view.getId(), List.of(position("vo-b", 700, 600)),
                List.of(new ViewConnectionSpec("conn-ab", sent, null)), null);
        assertEquals("the caller's own frame, written as sent", sent, ConnectionResponseBuilder.collectBendpoints(ab));
    }

    @Test
    public void applyPositions_shouldStoreWhatItAlwaysStored_whenTheCallOnlyRefitsTheTargetsGroup() {
        IDiagramModelGroup group = nestTargetInGroup();
        object(group, "vo-d", elC, 10, 10, 60, 40);

        layout(List.of(position("vo-d", 250, 180)));
        assertEquals("kept its origin", 600, group.getBounds().getX());
        assertEquals(150, group.getBounds().getY());
        assertEquals("grew right around D", 320, group.getBounds().getWidth());
        assertEquals("and down", 230, group.getBounds().getHeight());
        assertEquals(BEFORE_ANY_MOVE, ConnectionResponseBuilder.collectBendpoints(ab));
    }

    @Test
    public void updateViewConnection_shouldLeaveBendpointsAlone_whenOnlyStylingChanges() throws Exception {
        List<BendpointDto> held = List.of(new BendpointDto(1, 2, 3, 4));
        ConnectionResponseBuilder.applyBendpointsToConnection(ab, held);
        dispatcher.beginBatch(SESSION, "move then style");
        move("vo-b", 700, 600);
        accessor.updateViewConnection(SESSION, "conn-ab", null, null, new StylingParams(null, "#ff0000",
                null, null, null, null, null, null, null, null, null, null, null, null, null, null), null, null);
        dispatcher.endBatch(SESSION, true);
        assertEquals("#ff0000", ab.getLineColor());
        assertEquals(held, ConnectionResponseBuilder.collectBendpoints(ab));
    }

    @Test
    public void applyPositions_shouldStoreNothing_whenTheBatchIsRolledBack() throws Exception {
        dispatcher.beginBatch(SESSION, "move then apply, rolled back");
        move("vo-b", 700, 600);
        layout(null);
        dispatcher.endBatch(SESSION, false);
        assertEquals(200, b.getBounds().getY());
        assertTrue(ab.getBendpoints().isEmpty());
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /**
     * Each stored bendpoint, reconstructed from the source's centre and from the target's, lands on
     * the point sent. The centres are the production ones, so both sides truncate alike.
     */
    private void assertStoredThroughSentPoints(List<AbsoluteBendpointDto> sent) {
        assertStoredThroughSentPoints(ab, sent);
    }

    private static void assertStoredThroughSentPoints(IDiagramModelArchimateConnection conn,
            List<AbsoluteBendpointDto> sent) {
        List<BendpointDto> stored = ConnectionResponseBuilder.collectBendpoints(conn);
        assertEquals("one stored bendpoint per point sent: " + stored, sent.size(), stored.size());
        int[] source = ConnectionResponseBuilder.computeAbsoluteCenter((IDiagramModelObject) conn.getSource());
        int[] target = ConnectionResponseBuilder.computeAbsoluteCenter((IDiagramModelObject) conn.getTarget());
        for (int i = 0; i < sent.size(); i++) {
            BendpointDto bp = stored.get(i);
            AbsoluteBendpointDto want = sent.get(i);
            assertEquals("bendpoint " + i + " from the source: " + stored,
                    want, new AbsoluteBendpointDto(source[0] + bp.startX(), source[1] + bp.startY()));
            assertEquals("bendpoint " + i + " from the target: " + stored,
                    want, new AbsoluteBendpointDto(target[0] + bp.endX(), target[1] + bp.endY()));
        }
    }

    /** Moves B into a group at 600,150 so it sits at the same canvas position as before, 700,200. */
    private IDiagramModelGroup nestTargetInGroup() {
        IDiagramModelGroup group = factory.createDiagramModelGroup();
        group.setId("grp");
        group.setBounds(600, 150, 300, 200);
        view.getChildren().add(group);
        view.getChildren().remove(b);
        b.setBounds(100, 50, 120, 60);
        group.getChildren().add(b);
        return group;
    }

    private IDiagramModelArchimateConnection connection(String id) {
        for (Object c : a.getSourceConnections()) {
            if (c instanceof IDiagramModelArchimateConnection conn && conn.getId().equals(id)) {
                return conn;
            }
        }
        throw new AssertionError("no connection " + id + " from A");
    }

    private void layout(List<ViewPositionSpec> positions) {
        accessor.applyViewLayout(SESSION, view.getId(), positions,
                List.of(new ViewConnectionSpec("conn-ab", null, SENT)), null);
    }

    private static ViewPositionSpec position(String id, int x, int y) {
        return new ViewPositionSpec(id, x, y, null, null);
    }

    private void move(String id, int x, int y) {
        accessor.updateViewObject(SESSION, id, x, y, null, null, null, null, null, null, null, null, null, null);
    }

    private IArchimateElement element(String id) {
        IArchimateElement el = factory.createApplicationComponent();
        el.setId(id);
        model.getFolder(FolderType.APPLICATION).getElements().add(el);
        return el;
    }

    private IArchimateRelationship relationship(String id, IArchimateConcept src, IArchimateConcept tgt) {
        IArchimateRelationship rel = factory.createAssociationRelationship();
        rel.setId(id);
        rel.setSource(src);
        rel.setTarget(tgt);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);
        return rel;
    }

    private IDiagramModelArchimateObject object(IDiagramModelContainer parent, String id,
            IArchimateElement el, int x, int y, int w, int h) {
        IDiagramModelArchimateObject obj = factory.createDiagramModelArchimateObject();
        obj.setId(id);
        obj.setArchimateElement(el);
        obj.setBounds(x, y, w, h);
        parent.getChildren().add(obj);
        return obj;
    }

    private static Command toPlainCompound(Command command) {
        if (command instanceof RequireAttachedContainerCommand guard) {
            return guard.withGuarded(toPlainCompound(guard.getGuarded()));
        }
        if (command instanceof CompoundCommand compound) {
            CompoundCommand plain = new CompoundCommand(compound.getLabel());
            for (Object child : compound.getCommands()) {
                plain.add(toPlainCompound((Command) child));
            }
            return plain;
        }
        return command;
    }
}
