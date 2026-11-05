package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.AutoConnectResultDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;

/**
 * Pins that a new connection's {@code absoluteBendpoints} are stored relative to where its ends are
 * when the connection is created, not where they were when {@code add-connection-to-view} was
 * prepared.
 *
 * <p>A batch prepares every call before any of them runs, and so does a {@code bulk-mutate}. An
 * earlier operation that moves an end, moves the group holding it, moves the object it is anchored
 * to, or places it inside a group in the same call has therefore not run when the add is prepared,
 * and a conversion done there measures the offsets from the wrong centre: drawn from one end the
 * route passes through the points sent, drawn from the other it is shifted. Each fix pin below
 * asserts on the model after the change has run, against a literal expected store, and each stored
 * the sheared route before the conversion moved into {@link AddConnectionToViewCommand}.</p>
 *
 * <p>{@code auto-connect-view} gives a relationship from an element to itself Archi's default loop
 * through the same command, so a resize queued ahead of it in the batch sizes that loop too.</p>
 *
 * <p>The controls pin what must not change: a call with nothing moving ahead of it, relative
 * bendpoints, an approval re-prepared after a human edit, a rolled-back batch, and the command as
 * every other creation path builds it.</p>
 *
 * <p>Same headless idiom as {@code AbsoluteBendpointsExecuteTimeTest}: a real GEF
 * {@link CommandStack}, queued compounds rebuilt into plain GEF compounds, explicit bounds, and
 * unnamed elements so no label is measured through SWT.</p>
 */
public class AddConnectionAbsoluteBendpointsExecuteTimeTest {

    private static final String SESSION = "add-connection-absolute-bendpoints-session";

    /** Right out of the source, down, and into the target's left face: B at 700,600. */
    private static final List<AbsoluteBendpointDto> SENT =
            List.of(new AbsoluteBendpointDto(610, 30), new AbsoluteBendpointDto(610, 630));

    /** What {@link #SENT} is stored as with B at 700,200, which is also what a call with no move stores. */
    private static final List<BendpointDto> BEFORE_ANY_MOVE =
            List.of(new BendpointDto(150, 0, -150, -200), new BendpointDto(150, 600, -150, 400));

    /** {@link #SENT} stored with the target's centre at 760,630, written out rather than derived. */
    private static final List<BendpointDto> AFTER_B_AT_700_600 =
            List.of(new BendpointDto(150, 0, -150, -600), new BendpointDto(150, 600, -150, 0));

    /** Out of the source's bottom and into the target's left face: A at 400,400. */
    private static final List<AbsoluteBendpointDto> SOURCE_SENT =
            List.of(new AbsoluteBendpointDto(460, 230), new AbsoluteBendpointDto(640, 230));

    /** {@link #SOURCE_SENT} stored with the source's centre at 460,430. */
    private static final List<BendpointDto> AFTER_A_AT_400_400 =
            List.of(new BendpointDto(0, -200, -300, 0), new BendpointDto(180, -200, -120, 0));

    /** Archi's default loop for a 300x200 object: 180 = 300 * 0.6, 120 = 200 * 0.6, stored reversed. */
    private static final List<BendpointDto> LOOP_300_BY_200 = List.of(new BendpointDto(0, 120, 0, 120),
            new BendpointDto(180, 120, 180, 120), new BendpointDto(180, 0, 180, 0));

    /** Archi's default loop for a 120x60 object: 72 and 36 raised to the 100 and 60 floors, stored reversed. */
    private static final List<BendpointDto> LOOP_120_BY_60 = List.of(new BendpointDto(0, 60, 0, 60),
            new BendpointDto(100, 60, 100, 60), new BendpointDto(100, 0, 100, 0));

    /** A loop below and to the right of A at 400,200. */
    private static final List<AbsoluteBendpointDto> SELF_SENT = List.of(new AbsoluteBendpointDto(460, 300),
            new AbsoluteBendpointDto(700, 300), new AbsoluteBendpointDto(700, 200));

    /** {@link #SELF_SENT} stored with A's centre at 460,230. */
    private static final List<BendpointDto> SELF_AFTER_A_AT_400_200 = List.of(new BendpointDto(0, 70, 0, 70),
            new BendpointDto(240, 70, 240, 70), new BendpointDto(240, -30, 240, -30));

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
    private IArchimateRelationship ab;
    private IArchimateRelationship aa;
    private IArchimateRelationship ac;

    @Before
    public void setUp() {
        model = factory.createArchimateModel();
        model.setId("model-add-connection-absolute-bendpoints");
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
        ab = relationship("rel-ab", elA, elB);
        aa = relationship("rel-aa", elA, elA);
        ac = relationship("rel-ac", elA, elC);

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

    // ---- fixed: an end moves earlier in the batch ---------------------------------------------------

    @Test
    public void addConnectionToView_shouldStoreTheSentPoints_whenTheBatchMovedTheSourceFirst() throws Exception {
        dispatcher.beginBatch(SESSION, "move the source then add");
        move("vo-a", 400, 400);
        String id = add(ab, "vo-a", "vo-b", SOURCE_SENT);
        dispatcher.endBatch(SESSION, true);
        assertStoredThroughSentPoints(connection(ab), SOURCE_SENT);
        assertEquals(AFTER_A_AT_400_400, ConnectionResponseBuilder.collectBendpoints(connection(ab)));
        assertEquals(id, connection(ab).getId());
    }

    @Test
    public void addConnectionToView_shouldStoreTheSentPoints_whenTheBatchResizedTheTargetFirst() throws Exception {
        dispatcher.beginBatch(SESSION, "widen the target then add");
        accessor.updateViewObject(SESSION, "vo-b", null, null, 320, 60, null, null, null, null, null, null, null, null);
        add(ab, "vo-a", "vo-b", SENT);
        dispatcher.endBatch(SESSION, true);
        assertEquals(320, b.getBounds().getWidth());
        assertStoredThroughSentPoints(connection(ab), SENT);
        assertEquals("the target's centre is 860,230", List.of(new BendpointDto(150, 0, -250, -200),
                new BendpointDto(150, 600, -250, 400)), ConnectionResponseBuilder.collectBendpoints(connection(ab)));
    }

    @Test
    public void addConnectionToView_shouldStoreTheSentPoints_whenTheBatchResizedTheSourceFirst() throws Exception {
        dispatcher.beginBatch(SESSION, "grow the source then add");
        accessor.updateViewObject(SESSION, "vo-a", null, null, 320, 260, null, null, null, null, null, null, null, null);
        add(ab, "vo-a", "vo-b", SENT);
        dispatcher.endBatch(SESSION, true);
        assertEquals(260, a.getBounds().getHeight());
        assertStoredThroughSentPoints(connection(ab), SENT);
        assertEquals("the source's centre is 560,130", List.of(new BendpointDto(50, -100, -150, -200),
                new BendpointDto(50, 500, -150, 400)), ConnectionResponseBuilder.collectBendpoints(connection(ab)));
    }

    @Test
    public void addConnectionToView_shouldStoreTheSentPoints_whenTheBatchMovedTheTargetsGroup() throws Exception {
        nestTargetInGroup();
        dispatcher.beginBatch(SESSION, "move the group then add");
        move("grp", 600, 550);
        add(ab, "vo-a", "vo-b", SENT);
        dispatcher.endBatch(SESSION, true);
        assertEquals("the target itself did not move, its group did", 50, b.getBounds().getY());
        assertStoredThroughSentPoints(connection(ab), SENT);
        assertEquals(AFTER_B_AT_700_600, ConnectionResponseBuilder.collectBendpoints(connection(ab)));
    }

    @Test
    public void addConnectionToView_shouldStoreTheSentPoints_whenTheBatchMovedTheTargetsAnchor() throws Exception {
        object(view, "vo-c", elC, 700, 100, 120, 60);
        accessor.updateViewObject(SESSION, "vo-b", null, null, null, null, null, null, null, null,
                "vo-c", "below", 0, 40);
        assertEquals("anchored below C", 200, b.getBounds().getY());
        dispatcher.beginBatch(SESSION, "move the anchor then add");
        move("vo-c", 700, 500);
        add(ab, "vo-a", "vo-b", SENT);
        dispatcher.endBatch(SESSION, true);
        assertEquals("moved by its anchor", 600, b.getBounds().getY());
        assertStoredThroughSentPoints(connection(ab), SENT);
        assertEquals(AFTER_B_AT_700_600, ConnectionResponseBuilder.collectBendpoints(connection(ab)));
    }

    @Test
    public void addConnectionToView_shouldStoreTheSentPoints_whenTheBatchPlacedTheTargetInAGroup() throws Exception {
        IDiagramModelGroup group = group(600, 550);
        dispatcher.beginBatch(SESSION, "place inside a group then add");
        String placed = accessor.addToView(SESSION, view.getId(), elC.getId(), 100, 50, 120, 60, false,
                group.getId(), null, null).entity().viewObject().viewObjectId();
        add(ac, "vo-a", placed, SENT);
        dispatcher.endBatch(SESSION, true);
        assertEquals("placed at 700,600 on the canvas", group, connection(ac).getTarget().eContainer());
        assertStoredThroughSentPoints(connection(ac), SENT);
        assertEquals(AFTER_B_AT_700_600, ConnectionResponseBuilder.collectBendpoints(connection(ac)));
    }

    // ---- fixed: an end moves earlier in the same bulk-mutate ----------------------------------------

    @Test
    public void bulkMutate_shouldStoreAndReportTheSentPoints_whenAnEarlierOperationMovedTheSource() {
        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("update-view-object", Map.of("viewObjectId", "vo-a", "x", 400, "y", 400), null),
                new BulkOperation("add-connection-to-view", addParams(ab, "vo-a", "vo-b", SOURCE_SENT), null)),
                "move the source then add", false);
        assertTrue(result.allSucceeded());
        assertStoredThroughSentPoints(connection(ab), SOURCE_SENT);
        assertEquals(AFTER_A_AT_400_400, ConnectionResponseBuilder.collectBendpoints(connection(ab)));
        ViewConnectionDto reported = result.operations().get(1).effectiveConnection();
        assertNotNull("the applied bulk entry reports the connection read after the write", reported);
        assertEquals("and that report is the stored route", AFTER_A_AT_400_400, reported.bendpoints());
        assertEquals(SOURCE_SENT, reported.absoluteBendpoints());
    }

    @Test
    public void bulkMutate_shouldStoreTheSentPoints_whenAnEarlierOperationPlacedTheTargetInAGroup() {
        IDiagramModelGroup group = group(600, 550);
        Map<String, Object> place = new LinkedHashMap<>();
        place.put("viewId", view.getId());
        place.put("elementId", elC.getId());
        place.put("x", 100);
        place.put("y", 50);
        place.put("width", 120);
        place.put("height", 60);
        place.put("parentViewObjectId", group.getId());
        // the back-reference is what routes the add through the prepare that takes its ends directly
        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("add-to-view", place, null),
                new BulkOperation("add-connection-to-view", addParams(ac, "vo-a", "$0.id", SENT), null)),
                "place inside a group then add", false);
        assertTrue(String.valueOf(result.failedOperations()), result.allSucceeded());
        assertEquals(group, connection(ac).getTarget().eContainer());
        assertStoredThroughSentPoints(connection(ac), SENT);
        assertEquals(AFTER_B_AT_700_600, ConnectionResponseBuilder.collectBendpoints(connection(ac)));
    }

    // ---- fixed: a self-connection is decided from the object as it is then -------------------------

    @Test
    public void addConnectionToView_shouldSizeTheDefaultLoopFromTheBounds_whenTheBatchResizedTheObjectFirst()
            throws Exception {
        dispatcher.beginBatch(SESSION, "resize then add a self-connection");
        accessor.updateViewObject(SESSION, "vo-a", null, null, 300, 200, null, null, null, null, null, null, null, null);
        add(aa, "vo-a", "vo-a", null);
        dispatcher.endBatch(SESSION, true);
        assertEquals(300, a.getBounds().getWidth());
        assertEquals(LOOP_300_BY_200, ConnectionResponseBuilder.collectBendpoints(connection(aa)));
    }

    @Test
    public void addConnectionToView_shouldStoreTheSentPoints_whenTheBatchMovedTheObjectOfASelfConnection()
            throws Exception {
        dispatcher.beginBatch(SESSION, "move then add a self-connection");
        move("vo-a", 400, 200);
        add(aa, "vo-a", "vo-a", SELF_SENT);
        dispatcher.endBatch(SESSION, true);
        assertStoredThroughSentPoints(connection(aa), SELF_SENT);
        assertEquals(SELF_AFTER_A_AT_400_200, ConnectionResponseBuilder.collectBendpoints(connection(aa)));
    }

    // ---- fixed: auto-connect-view sizes a self-connection's loop from the object as it is then ------

    @Test
    public void autoConnectView_shouldSizeTheDefaultLoopFromTheBounds_whenTheBatchResizedTheObjectFirst()
            throws Exception {
        dispatcher.beginBatch(SESSION, "resize then auto-connect a self-relationship");
        resize("vo-a", 300, 200);
        autoConnect(null, List.of(aa.getId()));
        dispatcher.endBatch(SESSION, true);
        assertEquals(300, a.getBounds().getWidth());
        assertEquals(LOOP_300_BY_200, ConnectionResponseBuilder.collectBendpoints(connection(aa)));
    }

    @Test
    public void autoConnectView_shouldSizeTheLoopAndLeaveThePairUnrouted_whenUnfilteredAfterAQueuedResize()
            throws Exception {
        dispatcher.beginBatch(SESSION, "resize then auto-connect everything");
        resize("vo-a", 300, 200);
        assertEquals(2, autoConnect(null, null).entity().connectionsCreated());
        dispatcher.endBatch(SESSION, true);
        assertEquals(LOOP_300_BY_200, ConnectionResponseBuilder.collectBendpoints(connection(aa)));
        assertTrue("the ordinary pair gets no bendpoints", connection(ab).getBendpoints().isEmpty());
    }

    @Test
    public void autoConnectView_shouldSizeTheLoopOnce_whenFilteredToTheObjectAfterAQueuedResize() throws Exception {
        dispatcher.beginBatch(SESSION, "resize then auto-connect the object");
        resize("vo-a", 300, 200);
        autoConnect(List.of("el-a"), null);
        dispatcher.endBatch(SESSION, true);
        assertEquals("one connection, though the relationship is in both of A's lists", 1,
                a.getSourceConnections().stream().filter(c -> c instanceof IDiagramModelArchimateConnection conn
                        && conn.getArchimateRelationship() == aa).count());
        assertEquals(LOOP_300_BY_200, ConnectionResponseBuilder.collectBendpoints(connection(aa)));
    }

    @Test
    public void autoConnectView_shouldSizeTheDefaultLoopFromTheBounds_whenTheBatchShrankTheObjectFirst()
            throws Exception {
        a.setBounds(400, 0, 300, 200);
        dispatcher.beginBatch(SESSION, "shrink then auto-connect a self-relationship");
        resize("vo-a", 120, 60);
        autoConnect(null, List.of(aa.getId()));
        dispatcher.endBatch(SESSION, true);
        assertEquals(LOOP_120_BY_60, ConnectionResponseBuilder.collectBendpoints(connection(aa)));
    }

    @Test
    public void autoConnectView_shouldRemoveThenReAddTheSameLoop_whenTheBatchIsUndoneAndRedone() throws Exception {
        dispatcher.beginBatch(SESSION, "resize then auto-connect, undone and redone");
        resize("vo-a", 300, 200);
        autoConnect(null, List.of(aa.getId()));
        dispatcher.endBatch(SESSION, true);
        IDiagramModelArchimateConnection conn = connection(aa);

        stack.undo();
        assertFalse("undo removes the connection", a.getSourceConnections().contains(conn));
        assertEquals("and restores the object", 120, a.getBounds().getWidth());
        assertEquals(60, a.getBounds().getHeight());

        stack.redo();
        assertTrue("redo re-adds it", a.getSourceConnections().contains(conn));
        assertEquals(LOOP_300_BY_200, ConnectionResponseBuilder.collectBendpoints(conn));
    }

    // ---- controls: auto-connect-view unchanged ------------------------------------------------------

    @Test
    public void autoConnectView_shouldKeepTheLoopOfTheObjectsOwnSize_whenTheBatchResizedOnlyItsGroup()
            throws Exception {
        IDiagramModelGroup group = group(300, 0);
        view.getChildren().remove(a);
        a.setBounds(100, 0, 120, 60);
        group.getChildren().add(a);
        dispatcher.beginBatch(SESSION, "resize the group then auto-connect");
        resize("grp", 600, 400);
        autoConnect(null, List.of(aa.getId()));
        dispatcher.endBatch(SESSION, true);
        assertEquals(600, group.getBounds().getWidth());
        assertEquals(LOOP_120_BY_60, ConnectionResponseBuilder.collectBendpoints(connection(aa)));
    }

    @Test
    public void autoConnectView_shouldStoreTheLoopItAlwaysStored_whenTheBatchResizesNothing() throws Exception {
        dispatcher.beginBatch(SESSION, "auto-connect only");
        autoConnect(null, List.of(aa.getId()));
        dispatcher.endBatch(SESSION, true);
        assertEquals(LOOP_120_BY_60, ConnectionResponseBuilder.collectBendpoints(connection(aa)));
    }

    @Test
    public void autoConnectView_shouldStoreNothing_whenTheBatchIsRolledBack() throws Exception {
        dispatcher.beginBatch(SESSION, "resize then auto-connect, rolled back");
        resize("vo-a", 300, 200);
        autoConnect(null, List.of(aa.getId()));
        dispatcher.endBatch(SESSION, false);
        assertEquals(120, a.getBounds().getWidth());
        assertTrue(a.getSourceConnections().isEmpty());
    }

    @Test
    public void autoConnectView_shouldSkipTheSelfRelationship_whenItIsAlreadyOnTheView() throws Exception {
        autoConnect(null, List.of(aa.getId()));
        IDiagramModelArchimateConnection existing = connection(aa);
        dispatcher.beginBatch(SESSION, "resize then auto-connect again");
        resize("vo-a", 300, 200);
        AutoConnectResultDto again = autoConnect(null, List.of(aa.getId())).entity();
        dispatcher.endBatch(SESSION, true);
        assertEquals(0, again.connectionsCreated());
        assertEquals(1, again.connectionsSkipped());
        assertSame(existing, connection(aa));
        assertEquals("the existing loop is left as it was drawn", LOOP_120_BY_60,
                ConnectionResponseBuilder.collectBendpoints(existing));
    }

    @Test
    public void autoConnectView_shouldRefuseTheApprovalAsStale_whenAHumanResizedTheObjectAfterTheProposal() {
        approvalRequired = true;
        MutationResult<AutoConnectResultDto> proposed = autoConnect(null, List.of(aa.getId()));
        approvalRequired = false;
        assertTrue(proposed.isProposal());
        // not agent-authored: the stack sees a human resize
        stack.execute(new Command("resize") {
            @Override
            public void execute() {
                a.setBounds(400, 0, 300, 200);
            }
            @Override
            public void undo() {
                a.setBounds(400, 0, 120, 60);
            }
        });
        try {
            dispatcher.approveProposal(SESSION, proposed.proposalContext().proposalId());
            fail("an object the human resized makes the proposal stale");
        } catch (MutationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("you moved 'vo-a'"));
        }
        assertTrue("nothing was added", a.getSourceConnections().isEmpty());
    }

    // ---- fixed: later operations on the queued connection -------------------------------------------

    @Test
    public void addConnectionToView_shouldKeepTheRouteItConverted_whenALaterOperationOnlyStylesIt() throws Exception {
        dispatcher.beginBatch(SESSION, "move, add, style");
        move("vo-b", 700, 600);
        String id = add(ab, "vo-a", "vo-b", SENT);
        accessor.updateViewConnection(SESSION, id, null, null, new StylingParams(null, "#ff0000",
                null, null, null, null, null, null, null, null, null, null, null, null, null, null), null, null);
        dispatcher.endBatch(SESSION, true);
        assertEquals("#ff0000", connection(ab).getLineColor());
        assertEquals(AFTER_B_AT_700_600, ConnectionResponseBuilder.collectBendpoints(connection(ab)));
    }

    @Test
    public void addConnectionToView_shouldLeaveTheLaterUpdatesBendpoints_whenCommittedUndoneAndRedone()
            throws Exception {
        List<BendpointDto> later = List.of(new BendpointDto(1, 2, 3, 4));
        dispatcher.beginBatch(SESSION, "move, add, re-route");
        move("vo-b", 700, 600);
        String id = add(ab, "vo-a", "vo-b", SENT);
        accessor.updateViewConnection(SESSION, id, later, null, null, null, null);
        dispatcher.endBatch(SESSION, true);
        IDiagramModelArchimateConnection conn = connection(ab);
        assertEquals(later, ConnectionResponseBuilder.collectBendpoints(conn));
        stack.undo();
        stack.redo();
        assertEquals("the update ran after the add, so its list is what stays", later,
                ConnectionResponseBuilder.collectBendpoints(conn));
    }

    // ---- undo and redo ------------------------------------------------------------------------------

    @Test
    public void addConnectionToView_shouldRemoveThenReAddTheSameRoute_whenTheBatchIsUndoneAndRedone() throws Exception {
        dispatcher.beginBatch(SESSION, "move then add");
        move("vo-b", 700, 600);
        add(ab, "vo-a", "vo-b", SENT);
        dispatcher.endBatch(SESSION, true);
        IDiagramModelArchimateConnection conn = connection(ab);

        stack.undo();
        assertFalse("undo removes the connection", a.getSourceConnections().contains(conn));
        assertEquals("and restores the target", 200, b.getBounds().getY());

        stack.redo();
        assertTrue("redo re-adds it", a.getSourceConnections().contains(conn));
        assertEquals(600, b.getBounds().getY());
        assertEquals(AFTER_B_AT_700_600, ConnectionResponseBuilder.collectBendpoints(conn));
        assertStoredThroughSentPoints(conn, SENT);
    }

    // ---- the command itself -------------------------------------------------------------------------

    @Test
    public void command_shouldConvertAgainstTheEndsAsTheyStand_whenItRuns() {
        IDiagramModelArchimateConnection conn = newConnection(ab, BEFORE_ANY_MOVE);
        AddConnectionToViewCommand cmd = new AddConnectionToViewCommand(conn, a, b, null, SENT);
        b.setBounds(700, 600, 120, 60);
        cmd.execute();
        assertSame(a, conn.getSource());
        assertSame(b, conn.getTarget());
        assertEquals("the prepared list is replaced, not added to", AFTER_B_AT_700_600,
                ConnectionResponseBuilder.collectBendpoints(conn));
    }

    @Test
    public void command_shouldRedoItsFirstWrite_whenTheRouteAndAnEndChangedWhileItWasUndone() {
        IDiagramModelArchimateConnection conn = newConnection(ab, BEFORE_ANY_MOVE);
        AddConnectionToViewCommand cmd = new AddConnectionToViewCommand(conn, a, b, null, SENT);
        b.setBounds(700, 600, 120, 60);
        cmd.execute();
        cmd.undo();
        assertTrue("undo disconnects", !a.getSourceConnections().contains(conn));
        // what a later command's undo, or anything else, may leave behind
        conn.getBendpoints().clear();
        ConnectionResponseBuilder.applyBendpointsToConnection(conn, List.of(new BendpointDto(1, 2, 3, 4)));
        b.setBounds(700, 900, 120, 60);
        cmd.redo();
        assertTrue("redo reconnects", a.getSourceConnections().contains(conn));
        assertEquals("a redo repeats the first write, it does not convert again", AFTER_B_AT_700_600,
                ConnectionResponseBuilder.collectBendpoints(conn));
    }

    @Test
    public void command_shouldDecideTheDefaultLoop_whenASelfConnectionIsGivenNoBendpoints() {
        IDiagramModelArchimateConnection conn = newConnection(aa, List.of(new BendpointDto(0, 60, 0, 60),
                new BendpointDto(100, 60, 100, 60), new BendpointDto(100, 0, 100, 0)));
        AddConnectionToViewCommand cmd = new AddConnectionToViewCommand(conn, a, a, List.of(), null);
        a.setBounds(400, 0, 300, 200);
        cmd.execute();
        assertEquals(LOOP_300_BY_200, ConnectionResponseBuilder.collectBendpoints(conn));
    }

    @Test
    public void command_shouldLeaveRelativeBendpointsAsPrepared_whenGivenNoCanvasPoints() {
        List<BendpointDto> given = List.of(new BendpointDto(1, 2, 3, 4));
        IDiagramModelArchimateConnection conn = newConnection(ab, given);
        List<IDiagramModelBendpoint> prepared = new ArrayList<>(conn.getBendpoints());
        AddConnectionToViewCommand cmd = new AddConnectionToViewCommand(conn, a, b, given, null);
        b.setBounds(700, 600, 120, 60);
        cmd.execute();
        assertEquals("the very objects the prepare wrote", prepared, conn.getBendpoints());
        assertSame(prepared.get(0), conn.getBendpoints().get(0));

        IDiagramModelArchimateConnection self = newConnection(aa, given);
        List<IDiagramModelBendpoint> selfPrepared = new ArrayList<>(self.getBendpoints());
        new AddConnectionToViewCommand(self, a, a, given, null).execute();
        assertSame("a self-connection given bendpoints keeps them", selfPrepared.get(0), self.getBendpoints().get(0));
        assertEquals(1, self.getBendpoints().size());
    }

    @Test
    public void command_shouldBehaveAsBefore_whenBuiltWithTheThreeArgumentConstructor() {
        IDiagramModelArchimateConnection conn = newConnection(ab, BEFORE_ANY_MOVE);
        List<IDiagramModelBendpoint> prepared = new ArrayList<>(conn.getBendpoints());
        AddConnectionToViewCommand cmd = new AddConnectionToViewCommand(conn, a, b);
        assertSame(conn, cmd.getConnection());
        assertSame(a, cmd.getSource());
        assertSame(b, cmd.getTarget());
        b.setBounds(700, 600, 120, 60);
        cmd.execute();
        assertTrue(a.getSourceConnections().contains(conn));
        assertEquals("the bendpoints are the caller's, untouched", prepared, conn.getBendpoints());
        cmd.undo();
        assertFalse(a.getSourceConnections().contains(conn));
        assertEquals(prepared, conn.getBendpoints());
        cmd.redo();
        assertTrue(a.getSourceConnections().contains(conn));
        assertEquals(prepared, conn.getBendpoints());

        IDiagramModelArchimateConnection self = newConnection(aa, List.of());
        new AddConnectionToViewCommand(self, a, a).execute();
        assertTrue("no loop is decided for a caller that did not ask", self.getBendpoints().isEmpty());
    }

    @Test
    public void command_shouldNotThrow_whenAnEndIsNoLongerOnTheView() {
        IDiagramModelArchimateConnection conn = newConnection(ab, BEFORE_ANY_MOVE);
        AddConnectionToViewCommand cmd = new AddConnectionToViewCommand(conn, a, b, null, SENT);
        view.getChildren().remove(b);
        b.setBounds(700, 600, 120, 60);
        cmd.execute();
        assertEquals("converted against the detached end's own bounds", AFTER_B_AT_700_600,
                ConnectionResponseBuilder.collectBendpoints(conn));
    }

    // ---- controls: unchanged ------------------------------------------------------------------------

    @Test
    public void addConnectionToView_shouldStoreWhatItAlwaysStored_whenAppliedAlone() {
        add(ab, "vo-a", "vo-b", SENT);
        assertEquals(BEFORE_ANY_MOVE, ConnectionResponseBuilder.collectBendpoints(connection(ab)));
        assertStoredThroughSentPoints(connection(ab), SENT);
    }

    @Test
    public void addConnectionToView_shouldStoreWhatItAlwaysStored_whenTheBatchMovesNoEnd() throws Exception {
        dispatcher.beginBatch(SESSION, "add only");
        add(ab, "vo-a", "vo-b", SENT);
        dispatcher.endBatch(SESSION, true);
        assertEquals(BEFORE_ANY_MOVE, ConnectionResponseBuilder.collectBendpoints(connection(ab)));
    }

    @Test
    public void addConnectionToView_shouldStoreRelativeBendpointsAsSent_whenTheBatchMovedTheTargetFirst()
            throws Exception {
        dispatcher.beginBatch(SESSION, "move then add relative");
        move("vo-b", 700, 600);
        accessor.addConnectionToView(SESSION, view.getId(), ab.getId(), "vo-a", "vo-b", BEFORE_ANY_MOVE, null,
                null, null, null);
        dispatcher.endBatch(SESSION, true);
        assertEquals("the caller's own frame, written as sent", BEFORE_ANY_MOVE,
                ConnectionResponseBuilder.collectBendpoints(connection(ab)));
    }

    @Test
    public void addConnectionToView_shouldKeepRelativeBendpoints_onASelfConnectionAfterAQueuedResize()
            throws Exception {
        List<BendpointDto> given = List.of(new BendpointDto(10, 70, 10, 70));
        dispatcher.beginBatch(SESSION, "resize then add a routed self-connection");
        accessor.updateViewObject(SESSION, "vo-a", null, null, 300, 200, null, null, null, null, null, null, null, null);
        accessor.addConnectionToView(SESSION, view.getId(), aa.getId(), "vo-a", "vo-a", given, null,
                null, null, null);
        dispatcher.endBatch(SESSION, true);
        assertEquals(given, ConnectionResponseBuilder.collectBendpoints(connection(aa)));
    }

    @Test
    public void bulkMutate_shouldStoreTheSentPoints_whenAnEarlierOperationPlacedTheTargetAtTopLevel() {
        Map<String, Object> place = new LinkedHashMap<>();
        place.put("viewId", view.getId());
        place.put("elementId", elC.getId());
        place.put("x", 700);
        place.put("y", 600);
        place.put("width", 120);
        place.put("height", 60);
        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("add-to-view", place, null),
                new BulkOperation("add-connection-to-view", addParams(ac, "vo-a", "$0.id", SENT), null)),
                "place then add", false);
        assertTrue(result.allSucceeded());
        assertEquals(AFTER_B_AT_700_600, ConnectionResponseBuilder.collectBendpoints(connection(ac)));
    }

    @Test
    public void addConnectionToView_shouldRefuseTheApprovalAsStale_whenAHumanMovedAnEndAfterTheProposal() {
        approvalRequired = true;
        MutationResult<ViewConnectionDto> proposed = accessor.addConnectionToView(SESSION, view.getId(),
                ab.getId(), "vo-a", "vo-b", null, SENT, null, null, null);
        approvalRequired = false;
        assertTrue(proposed.isProposal());
        // not agent-authored: the stack sees a human drag
        stack.execute(new Command("drag") {
            @Override
            public void execute() {
                b.setBounds(700, 600, 120, 60);
            }
            @Override
            public void undo() {
                b.setBounds(700, 200, 120, 60);
            }
        });
        try {
            dispatcher.approveProposal(SESSION, proposed.proposalContext().proposalId());
            fail("an end the human moved makes the proposal stale");
        } catch (MutationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("you moved 'vo-b'"));
        }
        assertTrue("nothing was added", a.getSourceConnections().isEmpty());
    }

    @Test
    public void addConnectionToView_shouldStoreTheSentPoints_whenAProposalIsApproved() {
        approvalRequired = true;
        MutationResult<ViewConnectionDto> proposed = accessor.addConnectionToView(SESSION, view.getId(),
                ab.getId(), "vo-a", "vo-b", null, SENT, null, null, null);
        approvalRequired = false;
        assertTrue(proposed.isProposal());
        assertTrue("nothing is stored before approval", a.getSourceConnections().isEmpty());
        assertNotNull(dispatcher.approveProposal(SESSION, proposed.proposalContext().proposalId()));
        assertEquals(BEFORE_ANY_MOVE, ConnectionResponseBuilder.collectBendpoints(connection(ab)));
        assertStoredThroughSentPoints(connection(ab), SENT);
    }

    @Test
    public void addConnectionToView_shouldStoreNothing_whenTheBatchIsRolledBack() throws Exception {
        dispatcher.beginBatch(SESSION, "move then add, rolled back");
        move("vo-b", 700, 600);
        add(ab, "vo-a", "vo-b", SENT);
        dispatcher.endBatch(SESSION, false);
        assertEquals(200, b.getBounds().getY());
        assertTrue(a.getSourceConnections().isEmpty());
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /**
     * Each stored bendpoint, reconstructed from the source's centre and from the target's, lands on
     * the point sent. The centres are the production ones, so both sides truncate alike; the literal
     * stores asserted beside each call keep a defect shared with the conversion from cancelling out.
     */
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

    private String add(IArchimateRelationship rel, String source, String target, List<AbsoluteBendpointDto> sent) {
        return accessor.addConnectionToView(SESSION, view.getId(), rel.getId(), source, target, null, sent,
                null, null, null).entity().viewConnectionId();
    }

    private MutationResult<AutoConnectResultDto> autoConnect(List<String> elementIds, List<String> relationshipIds) {
        return accessor.autoConnectView(SESSION, view.getId(), elementIds, null, relationshipIds, null, null);
    }

    private void resize(String id, int width, int height) {
        accessor.updateViewObject(SESSION, id, null, null, width, height, null, null, null, null, null, null, null,
                null);
    }

    private Map<String, Object> addParams(IArchimateRelationship rel, String source, String target,
            List<AbsoluteBendpointDto> sent) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("viewId", view.getId());
        params.put("relationshipId", rel.getId());
        params.put("sourceViewObjectId", source);
        params.put("targetViewObjectId", target);
        List<Map<String, Object>> points = new ArrayList<>();
        for (AbsoluteBendpointDto p : sent) {
            points.add(Map.of("x", p.x(), "y", p.y()));
        }
        params.put("absoluteBendpoints", points);
        return params;
    }

    /** The one connection on the view for {@code rel}, from A, wherever its other end is. */
    private IDiagramModelArchimateConnection connection(IArchimateRelationship rel) {
        for (Object c : a.getSourceConnections()) {
            if (c instanceof IDiagramModelArchimateConnection conn && conn.getArchimateRelationship() == rel) {
                return conn;
            }
        }
        throw new AssertionError("no connection for " + rel.getId() + " from A");
    }

    /** A connection as a prepare leaves it: configured, holding its prepared bendpoints, not connected. */
    private IDiagramModelArchimateConnection newConnection(IArchimateRelationship rel, List<BendpointDto> prepared) {
        IDiagramModelArchimateConnection conn = factory.createDiagramModelArchimateConnection();
        conn.setArchimateRelationship(rel);
        ConnectionResponseBuilder.applyBendpointsToConnection(conn, prepared);
        return conn;
    }

    private IDiagramModelGroup group(int x, int y) {
        IDiagramModelGroup group = factory.createDiagramModelGroup();
        group.setId("grp");
        group.setBounds(x, y, 300, 200);
        view.getChildren().add(group);
        return group;
    }

    /** Moves B into a group at 600,150 so it sits at the same canvas position as before, 700,200. */
    private void nestTargetInGroup() {
        IDiagramModelGroup group = group(600, 150);
        view.getChildren().remove(b);
        b.setBounds(100, 50, 120, 60);
        group.getChildren().add(b);
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
