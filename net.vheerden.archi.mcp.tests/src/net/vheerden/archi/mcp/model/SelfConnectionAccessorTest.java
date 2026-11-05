package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
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

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;

/**
 * Pins that every tool creating a connection from a view object to itself stores the loop Archi's
 * own editor gives one, and reports it as stored: {@code add-connection-to-view} immediately, in a
 * batch, as a proposal and inside {@code bulk-mutate}, and {@code auto-connect-view}. The expected
 * bendpoints come from Archi's palette command ({@link SelfConnectionLoopTest#archiLoop}), never
 * from this server's own output, and the render faces from the pixels of a loop stored in that
 * order on Archi 5.10: it leaves the bottom face and re-enters the right face.
 *
 * <p>Same headless idiom as {@code BatchQueuedViewConnectionUpdateTest}: a real GEF
 * {@link CommandStack}, queued compounds rebuilt into plain GEF compounds, explicit bounds so no
 * path reaches {@code ElementSizer}.
 */
public class SelfConnectionAccessorTest {

    private static final String SESSION = "self-connection-session";
    /** An odd box at negative coordinates, so the loop is read from stored bounds, not rounded. */
    private static final int[] SELF_BOX = {-37, -11, 171, 61};

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private CommandStack stack;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private IArchimateRelationship selfRel;
    private IArchimateRelationship pairRel;
    private IDiagramModelArchimateObject selfObj;
    private IDiagramModelArchimateObject pairA;
    private IDiagramModelArchimateObject pairB;
    private boolean approvalRequired;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();
        model = factory.createArchimateModel();
        model.setId("model-self-connection");
        model.setDefaults();
        view = factory.createArchimateDiagramModel();
        view.setId("view-self");
        view.setName("Self");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        IArchimateElement self = element("el-self");
        IArchimateElement a = element("el-a");
        IArchimateElement b = element("el-b");
        selfRel = relationship("rel-self", self, self);
        pairRel = relationship("rel-pair", a, b);
        selfObj = object("vo-self", self, SELF_BOX);
        // two DIFFERENT objects whose centres coincide: an ordinary pair, never a self-connection
        pairA = object("vo-a", a, new int[] {400, 0, 120, 60});
        pairB = object("vo-b", b, new int[] {400, 0, 120, 60});

        stubModelManager.setModels(List.of(model));
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
        accessor = new ArchiModelAccessorImpl(stubModelManager, dispatcher);
        dispatcher.onModelActive(model);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ---- add-connection-to-view ---------------------------------------------------------------

    @Test
    public void addConnection_shouldStoreArchisLoop_whenASelfConnectionIsGivenNoBendpoints() {
        ViewConnectionDto dto = addSelf(null, null).entity();

        List<BendpointDto> loop = expectedLoop();
        assertEquals(loop, stored(dto.viewConnectionId()));
        assertReportsLoop(dto, loop);
    }

    @Test
    public void addConnection_shouldKeepTheCallersBendpoints_onASelfConnection() {
        List<BendpointDto> supplied = List.of(new BendpointDto(0, -70, 0, -70),
                new BendpointDto(130, -70, 130, -70));
        ViewConnectionDto dto = addSelf(supplied, null).entity();
        assertEquals(supplied, stored(dto.viewConnectionId()));
        assertEquals(supplied, dto.bendpoints());

        // absolute bendpoints are taken as given too, converted off the centre (48, 19)
        accessor.removeFromView(SESSION, view.getId(), dto.viewConnectionId());
        ViewConnectionDto abs = addSelf(null, List.of(new AbsoluteBendpointDto(48, -60))).entity();
        assertEquals(List.of(new BendpointDto(0, -79, 0, -79)), stored(abs.viewConnectionId()));
    }

    @Test
    public void addConnection_shouldNotLoopTwoObjectsWhoseCentresCoincide() {
        ViewConnectionDto dto = accessor.addConnectionToView(SESSION, view.getId(), pairRel.getId(),
                pairA.getId(), pairB.getId(), null, null, null, null, null).entity();
        assertEquals(List.of(), stored(dto.viewConnectionId()));
        assertNull(dto.bendpoints());
    }

    @Test
    public void addConnection_shouldQueueTheLoop_inABatch() {
        dispatcher.beginBatch(SESSION, "self in a batch");
        MutationResult<ViewConnectionDto> result = addSelf(null, null);
        assertTrue(result.isBatched());
        assertEquals(expectedLoop(), result.entity().bendpoints());
        dispatcher.endBatch(SESSION, true);

        assertEquals(expectedLoop(), stored(onlyConnection().getId()));
    }

    @Test
    public void addConnection_shouldProposeAndApplyTheLoop() {
        approvalRequired = true;
        MutationResult<ViewConnectionDto> result = addSelf(null, null);
        assertTrue(result.isProposal());
        assertEquals(expectedLoop(), result.entity().bendpoints());
        approvalRequired = false;

        PreparedMutation<?> rebuilt = dispatcher.getPendingProposals(SESSION).get(0).rebuild().get();
        stack.execute(toPlainCompound(rebuilt.command()));
        assertEquals(expectedLoop(), stored(onlyConnection().getId()));
    }

    @Test
    public void addConnection_shouldStoreTheLoop_insideBulkMutate() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("viewId", view.getId());
        params.put("relationshipId", selfRel.getId());
        params.put("sourceViewObjectId", selfObj.getId());
        params.put("targetViewObjectId", selfObj.getId());
        BulkMutationResult result = accessor.executeBulk(SESSION,
                List.of(new BulkOperation("add-connection-to-view", params)), "bulk self", false);
        assertTrue(String.valueOf(result.failedOperations()), result.allSucceeded());
        assertEquals(expectedLoop(), stored(onlyConnection().getId()));
    }

    /** An object the same call placed is back-referenced, which takes the bulk-only prepare. */
    @Test
    public void addConnection_shouldStoreTheLoop_whenBulkMutateConnectsAnObjectItJustPlaced() {
        Map<String, Object> place = new LinkedHashMap<>();
        place.put("viewId", view.getId());
        place.put("elementId", selfObj.getArchimateElement().getId());
        place.put("x", 600);
        place.put("y", 300);
        place.put("width", SELF_BOX[2]);
        place.put("height", SELF_BOX[3]);
        Map<String, Object> connect = new LinkedHashMap<>();
        connect.put("viewId", view.getId());
        connect.put("relationshipId", selfRel.getId());
        connect.put("sourceViewObjectId", "$0.id");
        connect.put("targetViewObjectId", "$0.id");
        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("add-to-view", place),
                new BulkOperation("add-connection-to-view", connect)), "bulk self, new object", false);
        assertTrue(String.valueOf(result.failedOperations()), result.allSucceeded());
        IDiagramModelArchimateConnection conn = connectionFor(selfRel);
        assertTrue(conn.getSource() != selfObj && conn.getSource() == conn.getTarget());
        assertEquals(expectedLoop(), ConnectionResponseBuilder.collectBendpoints(conn));
    }

    // ---- auto-connect-view --------------------------------------------------------------------

    @Test
    public void autoConnect_shouldStoreArchisLoop_onASelfRelationship_andNoneOnTheCoincidentPair() {
        accessor.autoConnectView(SESSION, view.getId(), null, null, null, null, null);

        IDiagramModelArchimateConnection self = connectionFor(selfRel);
        assertNotNull(self);
        assertEquals(expectedLoop(), ConnectionResponseBuilder.collectBendpoints(self));
        assertEquals(List.of(), ConnectionResponseBuilder.collectBendpoints(connectionFor(pairRel)));
    }

    // ---- auto-route-connections ---------------------------------------------------------------

    @Test
    public void autoRoute_full_shouldKeepAStoredLoop_loopABareOne_andRouteTheCoincidentPair() {
        RouteFixture f = routeFixture();
        AutoRouteResultDto dto = route(null, null);
        f.assertSelfConnectionsHandled(dto);
        assertEquals("only the coincident pair is routed", 1, dto.connectionsRouted());
    }

    @Test
    public void autoRoute_terminalsOnly_shouldKeepAStoredLoop_andLoopABareOne() {
        RouteFixture f = routeFixture();
        AutoRouteResultDto dto = route(null, "terminals-only");
        f.assertSelfConnectionsHandled(dto);
    }

    @Test
    public void autoRoute_clear_shouldKeepAStoredLoop_loopABareOne_andClearThePair() {
        RouteFixture f = routeFixture();
        ConnectionResponseBuilder.applyBendpointsToConnection(f.pair, List.of(new BendpointDto(0, 90, 0, 90)));
        AutoRouteResultDto dto = route("clear", null);
        f.assertSelfConnectionsHandled(dto);
        assertEquals(1, dto.connectionsRouted());
        assertEquals(List.of(), ConnectionResponseBuilder.collectBendpoints(f.pair));
    }

    @Test
    public void autoRoute_shouldApplyTheLoop_whenTheViewHoldsOnlySelfConnections() {
        // every branch, since each reaches its own compound with no routable connection at all
        String[][] arms = {{null, null}, {null, "terminals-only"}, {"clear", null}};
        for (String[] arm : arms) {
            IDiagramModelArchimateConnection bare = rawConnection(selfRel, selfObj);
            AutoRouteResultDto dto = route(arm[0], arm[1]);
            String label = arm[0] + "/" + arm[1];
            assertEquals(label, 0, dto.connectionsRouted());
            assertEquals(label, expectedLoop(), ConnectionResponseBuilder.collectBendpoints(bare));
            assertEquals(label, List.of(bare.getId()), selfWarning(dto).remediationViolatorIds());
            bare.disconnect();
        }
    }

    @Test
    public void autoRoute_approvalCard_shouldCountTheLoopsItWillWrite() {
        // a loop is a bendpoint write, so the human approving the call is told how many
        for (String mode : new String[] {null, "terminals-only"}) {
            RouteFixture f = routeFixture();
            approvalRequired = true;
            MutationResult<AutoRouteResultDto> result = accessor.autoRouteConnections(SESSION, view.getId(),
                    null, null, false, false, RoutingPipeline.DEFAULT_SNAP_THRESHOLD,
                    RoutingPipeline.DEFAULT_PERIMETER_MARGIN, mode);
            approvalRequired = false;
            assertTrue(mode + " must propose", result.isProposal());
            PendingProposal pending = dispatcher.getPendingProposals(SESSION).get(0);
            Map<String, Object> card = pending.proposedChanges();
            assertEquals(mode + ": only the bare one is written, the kept loop is not", 1,
                    card.get("selfConnectionsLooped"));
            assertEquals(mode + ": nothing is written before approval", List.of(),
                    ConnectionResponseBuilder.collectBendpoints(f.bare));
            dispatcher.removeProposal(SESSION, pending.proposalId());
            f.loop.disconnect();
            f.bare.disconnect();
            f.pair.disconnect();
        }
    }

    // ---- auto-route-connections, deferred: the rule is applied to the model the commit sees ----

    /** The three branches, each of which builds its own compound. */
    private static final String[][] BRANCHES = {{null, null}, {null, "terminals-only"}, {"clear", null}};

    /** Bendpoints a caller gives a self-connection, deliberately not Archi's default shape. */
    private static final List<BendpointDto> CALLERS = List.of(new BendpointDto(0, -70, 0, -70),
            new BendpointDto(130, -70, 130, -70));

    @Test
    public void autoRoute_inABatch_shouldKeepBendpointsAnEarlierUpdateGaveABareSelfConnection() {
        for (String[] branch : BRANCHES) {
            String label = branch[0] + "/" + branch[1];
            IDiagramModelArchimateConnection bare = rawConnection(selfRel, selfObj);
            dispatcher.beginBatch(SESSION, "bendpoints, then route");
            accessor.updateViewConnection(SESSION, bare.getId(), CALLERS, null, null, null, null);
            route(branch[0], branch[1]);
            dispatcher.endBatch(SESSION, true);
            assertEquals(label + ": the earlier edit survives the commit", CALLERS,
                    ConnectionResponseBuilder.collectBendpoints(bare));

            stack.undo();
            assertEquals(label + ": undo restores the bare connection", List.of(),
                    ConnectionResponseBuilder.collectBendpoints(bare));
            stack.redo();
            assertEquals(label + ": redo writes the edit, not a loop", CALLERS,
                    ConnectionResponseBuilder.collectBendpoints(bare));
            bare.disconnect();
        }
    }

    @Test
    public void autoRoute_inABatch_shouldLoopASelfConnectionAnEarlierUpdateCleared() {
        for (String[] branch : BRANCHES) {
            String label = branch[0] + "/" + branch[1];
            IDiagramModelArchimateConnection loop = rawConnection(selfRel, selfObj);
            ConnectionResponseBuilder.applyBendpointsToConnection(loop, CALLERS);
            dispatcher.beginBatch(SESSION, "clear, then route");
            accessor.updateViewConnection(SESSION, loop.getId(), List.of(), null, null, null, null);
            String message = selfWarning(route(branch[0], branch[1])).message();
            dispatcher.endBatch(SESSION, true);
            assertTrue(label + ": the response may not promise it stays as stored:\n" + message,
                    !message.contains("left exactly as stored")
                            && message.contains("decided when the open batch is committed"));
            assertEquals(label + ": cleared before the route ran, so it is given the loop", expectedLoop(),
                    ConnectionResponseBuilder.collectBendpoints(loop));

            stack.undo();
            assertEquals(label + ": undoing the batch restores the loop it started with", CALLERS,
                    ConnectionResponseBuilder.collectBendpoints(loop));
            stack.redo();
            assertEquals(label, expectedLoop(), ConnectionResponseBuilder.collectBendpoints(loop));
            loop.disconnect();
        }
    }

    @Test
    public void autoRoute_inABatch_shouldSizeTheLoopFromTheBoundsAnEarlierUpdateGaveTheObject() {
        IDiagramModelArchimateConnection bare = rawConnection(selfRel, selfObj);
        dispatcher.beginBatch(SESSION, "resize, then route");
        accessor.updateViewObject(SESSION, selfObj.getId(), null, null, 201, 111, null, null, null, null);
        route(null, null);
        dispatcher.endBatch(SESSION, true);
        assertEquals(201, selfObj.getBounds().getWidth());
        // (int) 120.6 and (int) 66.6: odd, so a loop read from the old 171 x 61 cannot pass
        assertEquals(SelfConnectionLoopTest.archiLoop(SelfConnectionLoopTest.object(
                new int[] {SELF_BOX[0], SELF_BOX[1], 201, 111})), ConnectionResponseBuilder.collectBendpoints(bare));
    }

    @Test
    public void autoRoute_inABatch_shouldWriteNothingOnASelfConnectionAnEarlierRemoveTookOffTheView() {
        IDiagramModelArchimateConnection bare = rawConnection(selfRel, selfObj);
        dispatcher.beginBatch(SESSION, "remove, then route");
        accessor.removeFromView(SESSION, view.getId(), bare.getId());
        route(null, null);
        dispatcher.endBatch(SESSION, true);
        assertNull("removed by the commit", bare.getDiagramModel());
        assertEquals("nothing written onto a connection no longer on the view", List.of(),
                ConnectionResponseBuilder.collectBendpoints(bare));
    }

    /**
     * A self-connection's loop is decided when the card runs, from the bendpoints it holds then, so
     * applying over a human's edit would be safe; but the card is refused anyway, as it is for any
     * connection whose route a human changed during review. One rule for every tracked route.
     */
    @Test
    public void autoRoute_onApproval_shouldRefuseAsStale_whenTheHumanChangedASelfConnectionsBendpoints() {
        List<BendpointDto> human = List.of(new BendpointDto(0, 80, 0, 80));
        for (String mode : new String[] {null, "terminals-only"}) {
            // a bare one the human gives bendpoints during review
            IDiagramModelArchimateConnection bare = rawConnection(selfRel, selfObj);
            assertRefusedAsStale(mode + ": given bendpoints", bare, mode, human);
            bare.disconnect();

            // a stored loop the human clears during review
            IDiagramModelArchimateConnection loop = rawConnection(selfRel, selfObj);
            ConnectionResponseBuilder.applyBendpointsToConnection(loop, CALLERS);
            assertRefusedAsStale(mode + ": cleared", loop, mode, List.of());
            loop.disconnect();
        }
    }

    @Test
    public void autoRoute_shouldNotSwitchAManhattanView_whenItsOnlySelfConnectionsAreKept() {
        view.setConnectionRouterType(IDiagramModel.CONNECTION_ROUTER_MANHATTAN);
        IDiagramModelArchimateConnection loop = rawConnection(selfRel, selfObj);
        ConnectionResponseBuilder.applyBendpointsToConnection(loop, CALLERS);
        for (String[] branch : BRANCHES) {
            String label = branch[0] + "/" + branch[1];
            stack.flush();
            AutoRouteResultDto applied = route(branch[0], branch[1]);
            assertTrue(label + ": nothing to write, so nothing on the undo stack", !stack.canUndo());
            assertTrue(label, !applied.routerTypeSwitched());

            dispatcher.beginBatch(SESSION, "kept only");
            AutoRouteResultDto queued = route(branch[0], branch[1]);
            dispatcher.endBatch(SESSION, true);
            assertTrue(label, !queued.routerTypeSwitched());
            assertEquals(label + ": a kept-only call never switches the router",
                    IDiagramModel.CONNECTION_ROUTER_MANHATTAN, view.getConnectionRouterType());
            assertEquals(label, CALLERS, ConnectionResponseBuilder.collectBendpoints(loop));
        }
    }

    @Test
    public void autoRoute_approvalCard_shouldSayTheLoopIsDecidedOnApproval() {
        IDiagramModelArchimateConnection loop = rawConnection(selfRel, selfObj);
        ConnectionResponseBuilder.applyBendpointsToConnection(loop, CALLERS);
        for (String mode : new String[] {null, "terminals-only"}) {
            approvalRequired = true;
            MutationResult<AutoRouteResultDto> result = accessor.autoRouteConnections(SESSION, view.getId(),
                    null, null, false, false, RoutingPipeline.DEFAULT_SNAP_THRESHOLD,
                    RoutingPipeline.DEFAULT_PERIMETER_MARGIN, mode);
            approvalRequired = false;
            assertTrue(mode + ": a held loop alone is now proposed, so approval can apply the rule",
                    result.isProposal());
            PendingProposal pending = dispatcher.getPendingProposals(SESSION).get(0);
            Map<String, Object> card = pending.proposedChanges();
            assertNull(mode + ": none held no bendpoints when proposed", card.get("selfConnectionsLooped"));
            assertTrue(mode + ": " + card, String.valueOf(card.get("selfConnectionRule"))
                    .startsWith("Decided on approval from the bendpoints each self-connection holds then"));
            dispatcher.removeProposal(SESSION, pending.proposalId());
        }
    }

    /**
     * Proposes auto-route of {@code conn}, has a human set its bendpoints, then approves: refused with
     * the route message, the human's bendpoints untouched, nothing added to the undo stack.
     */
    private void assertRefusedAsStale(String label, IDiagramModelArchimateConnection conn, String mode,
            List<BendpointDto> human) {
        approvalRequired = true;
        MutationResult<AutoRouteResultDto> result = accessor.autoRouteConnections(SESSION, view.getId(),
                List.of(conn.getId()), null, false, false, RoutingPipeline.DEFAULT_SNAP_THRESHOLD,
                RoutingPipeline.DEFAULT_PERIMETER_MARGIN, mode);
        approvalRequired = false;
        assertTrue(label + " must propose", result.isProposal());
        String proposalId = result.proposalContext().proposalId();
        // not agent-authored: the stack sees a human edit
        stack.execute(new UpdateViewConnectionCommand(conn, ConnectionResponseBuilder.createEmfBendpoints(human)));
        assertEquals(label + ": precondition, the human's edit landed", human,
                ConnectionResponseBuilder.collectBendpoints(conn));
        Command humanEdit = stack.getUndoCommand();
        try {
            dispatcher.approveProposal(SESSION, proposalId);
            throw new AssertionError(label + ": approval must be refused");
        } catch (MutationException e) {
            // unnamed, so named by its ends: the one object at both
            assertEquals(label, ProposalStalenessGuard.routeEditedMessage(
                    "'" + selfObj.getId() + "' \u2192 '" + selfObj.getId() + "'"), e.getMessage());
        }
        assertEquals(label + ": the human's bendpoints are untouched", human,
                ConnectionResponseBuilder.collectBendpoints(conn));
        assertTrue(label + ": nothing added to the undo stack", humanEdit == stack.getUndoCommand());
        dispatcher.removeProposal(SESSION, proposalId);
    }

    /** A stored loop, a self-connection with none, and the coincident pair, on one view. */
    private RouteFixture routeFixture() {
        IArchimateElement loopEl = element("el-loop");
        IDiagramModelArchimateObject loopObj = object("vo-loop", loopEl, new int[] {0, 300, 120, 60});
        IDiagramModelArchimateConnection loop = rawConnection(relationship("rel-loop", loopEl, loopEl), loopObj);
        // a loop a human drew, deliberately not Archi's default shape
        List<BendpointDto> drawn = List.of(new BendpointDto(-90, 0, -90, 0), new BendpointDto(-90, -70, -90, -70),
                new BendpointDto(0, -70, 0, -70));
        ConnectionResponseBuilder.applyBendpointsToConnection(loop, drawn);
        IDiagramModelArchimateConnection bare = rawConnection(selfRel, selfObj);
        IDiagramModelArchimateConnection pair = rawConnection(pairRel, pairA, pairB);
        return new RouteFixture(loop, drawn, bare, pair);
    }

    private record RouteFixture(IDiagramModelArchimateConnection loop, List<BendpointDto> drawn,
            IDiagramModelArchimateConnection bare, IDiagramModelArchimateConnection pair) {
        void assertSelfConnectionsHandled(AutoRouteResultDto dto) {
            assertEquals("a stored loop is kept byte for byte", drawn,
                    ConnectionResponseBuilder.collectBendpoints(loop));
            assertEquals("a bare self-connection gets Archi's loop", expectedLoop(),
                    ConnectionResponseBuilder.collectBendpoints(bare));
            StructuredWarningDto warning = selfWarning(dto);
            assertEquals("kept first, then looped; never the coincident pair",
                    List.of(loop.getId(), bare.getId()), warning.remediationViolatorIds());
            assertTrue(warning.message(), warning.message().contains(
                    "Those not given a loop by this call are left exactly as stored: " + loop.getId() + "."));
            assertTrue(warning.message(), warning.message().endsWith(
                    "these were given Archi's default loop: " + bare.getId() + "."));
            assertTrue(dto.warnings().contains(warning.message()));
        }
    }

    private static StructuredWarningDto selfWarning(AutoRouteResultDto dto) {
        List<StructuredWarningDto> found = dto.structuredWarnings().stream()
                .filter(w -> StructuredWarningCodes.SELF_CONNECTION_NOT_ROUTED.equals(w.code())).toList();
        assertEquals("one entry per call: " + dto.structuredWarnings(), 1, found.size());
        return found.get(0);
    }

    private AutoRouteResultDto route(String strategy, String mode) {
        return accessor.autoRouteConnections(SESSION, view.getId(), null, strategy, false, false,
                RoutingPipeline.DEFAULT_SNAP_THRESHOLD, RoutingPipeline.DEFAULT_PERIMETER_MARGIN, mode).entity();
    }

    private static IDiagramModelArchimateConnection rawConnection(IArchimateRelationship rel,
            IDiagramModelArchimateObject obj) {
        return rawConnection(rel, obj, obj);
    }

    private static IDiagramModelArchimateConnection rawConnection(IArchimateRelationship rel,
            IDiagramModelArchimateObject src, IDiagramModelArchimateObject tgt) {
        IDiagramModelArchimateConnection conn =
                IArchimateFactory.eINSTANCE.createDiagramModelArchimateConnection();
        conn.setArchimateRelationship(rel);
        conn.connect(src, tgt);
        return conn;
    }

    // ---- helpers ------------------------------------------------------------------------------

    private MutationResult<ViewConnectionDto> addSelf(List<BendpointDto> bendpoints,
            List<AbsoluteBendpointDto> absolute) {
        return accessor.addConnectionToView(SESSION, view.getId(), selfRel.getId(),
                selfObj.getId(), selfObj.getId(), bendpoints, absolute, null, null, null);
    }

    private static List<BendpointDto> expectedLoop() {
        return SelfConnectionLoopTest.archiLoop(SelfConnectionLoopTest.object(SELF_BOX));
    }

    /** The loop as the response must report it: relative, absolute off the centre, and its faces. */
    private static void assertReportsLoop(ViewConnectionDto dto, List<BendpointDto> loop) {
        assertEquals(loop, dto.bendpoints());
        // centre of [-37, -11, 171, 61] is (48, 19) in Archi's integer arithmetic
        List<AbsoluteBendpointDto> absolute = loop.stream()
                .map(bp -> new AbsoluteBendpointDto(48 + bp.startX(), 19 + bp.startY())).toList();
        assertEquals(absolute, dto.absoluteBendpoints());
        assertEquals("bottom", dto.sourceRenderFace());
        assertEquals("right", dto.targetRenderFace());
    }

    private List<BendpointDto> stored(String connectionId) {
        for (IDiagramModelObject obj : view.getChildren()) {
            for (Object conn : obj.getSourceConnections()) {
                if (conn instanceof IDiagramModelArchimateConnection c && c.getId().equals(connectionId)) {
                    return ConnectionResponseBuilder.collectBendpoints(c);
                }
            }
        }
        throw new AssertionError("no connection " + connectionId);
    }

    private IDiagramModelArchimateConnection onlyConnection() {
        assertEquals(1, selfObj.getSourceConnections().size());
        return (IDiagramModelArchimateConnection) selfObj.getSourceConnections().get(0);
    }

    private IDiagramModelArchimateConnection connectionFor(IArchimateRelationship rel) {
        for (IDiagramModelObject obj : view.getChildren()) {
            for (Object conn : obj.getSourceConnections()) {
                if (conn instanceof IDiagramModelArchimateConnection c && c.getArchimateRelationship() == rel) {
                    return c;
                }
            }
        }
        return null;
    }

    private IArchimateElement element(String id) {
        IArchimateElement el = factory.createApplicationComponent();
        el.setId(id);
        model.getFolder(FolderType.APPLICATION).getElements().add(el);
        return el;
    }

    private IArchimateRelationship relationship(String id, IArchimateElement src, IArchimateElement tgt) {
        IArchimateRelationship rel = factory.createAssociationRelationship();
        rel.setId(id);
        rel.setSource(src);
        rel.setTarget(tgt);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);
        return rel;
    }

    private IDiagramModelArchimateObject object(String id, IArchimateElement el, int[] b) {
        IDiagramModelArchimateObject obj = factory.createDiagramModelArchimateObject();
        obj.setId(id);
        obj.setArchimateElement(el);
        obj.setBounds(b[0], b[1], b[2], b[3]);
        view.getChildren().add(obj);
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

    private static class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final List<PropertyChangeListener> listeners = new ArrayList<>();

        void setModels(List<IArchimateModel> models) { this.models = models; }

        @Override
        public List<IArchimateModel> getModels() { return models; }
        @Override
        public void addPropertyChangeListener(PropertyChangeListener listener) {
            listeners.add(listener);
        }
        @Override
        public void removePropertyChangeListener(PropertyChangeListener listener) {
            listeners.remove(listener);
        }
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
