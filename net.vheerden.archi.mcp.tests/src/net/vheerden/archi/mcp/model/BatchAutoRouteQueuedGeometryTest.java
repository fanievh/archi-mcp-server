package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;
import net.vheerden.archi.mcp.response.dto.BulkOperation;

/**
 * Pins that {@code auto-route-connections} inside an open batch refuses, rather than queues, when
 * that batch has already queued a change to the routing input on the same view.
 *
 * <p>Every prepare inside a batch reads a model none of the batch's earlier commands have touched,
 * and auto-route computes its routes from that model: endpoints and obstacles from
 * {@code getBounds()}, the connection set from what is attached. A route queued after a move is
 * therefore stored at commit for an endpoint that is no longer there, and its two ends stop
 * meeting. The refusal is what this class pins; each refused case below committed exactly that kind
 * of stale result before the refusal existed.</p>
 *
 * <p>The negative controls matter as much as the refusals: a styling-only edit, geometry queued on a
 * different view, a no-op bounds write, a rolled-back batch, and the two shapes the applied
 * sequence produces identically ({@code strategy: "clear"} after a move, and a full route over a
 * queued bendpoint edit) must all still queue. Without them the predicate could become "refuse
 * every auto-route in a batch" and every test here would still pass.</p>
 *
 * <p>Same headless idiom as {@code SelfConnectionAccessorTest}: a real GEF {@link CommandStack},
 * queued compounds rebuilt into plain GEF compounds, explicit bounds so no path reaches
 * {@code ElementSizer}, and unnamed elements so no label is measured through SWT.</p>
 */
public class BatchAutoRouteQueuedGeometryTest {

    private static final String SESSION = "batch-auto-route-geometry-session";

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private IArchimateDiagramModel otherView;
    private MutationDispatcher dispatcher;
    private ArchiModelAccessorImpl accessor;
    private CommandStack stack;
    private boolean approvalRequired;
    private IArchimateElement elC;
    private IDiagramModelArchimateObject a;
    private IDiagramModelArchimateObject b;
    private IDiagramModelArchimateObject obstacle;
    private IDiagramModelArchimateObject elsewhere;
    private IDiagramModelArchimateConnection ab;

    @Before
    public void setUp() {
        model = factory.createArchimateModel();
        model.setId("model-batch-auto-route-geometry");
        model.setDefaults();
        view = view("view-routed");
        otherView = view("view-other");
        IArchimateElement elA = element("el-a");
        IArchimateElement elB = element("el-b");
        elC = element("el-c");
        a = object(view, "vo-a", elA, 400, 0, 120, 60);
        b = object(view, "vo-b", elB, 700, 200, 120, 60);
        // off every route, so it changes nothing until a test moves it or removes it
        obstacle = object(view, "vo-obstacle", elC, 0, 500, 60, 60);
        elsewhere = object(otherView, "vo-elsewhere", elA, 0, 0, 120, 60);
        ab = factory.createDiagramModelArchimateConnection();
        ab.setId("conn-ab");
        ab.setArchimateRelationship(relationship("rel-ab", elA, elB));
        ab.connect(a, b);
        relationship("rel-ac", elA, elC);

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

    // ---- refused: kind (i), queued bounds of an object on the view ---------------------------------

    @Test
    public void autoRoute_shouldRefuse_whenTheBatchMovedAnEndpoint() throws Exception {
        dispatcher.beginBatch(SESSION, "move then route");
        move("vo-b", 700, 600);
        List<String> queuedBefore = queued();

        ModelAccessException refusal = refused(null, null, null);
        assertTrue(refusal.getMessage(), refusal.getMessage().contains(
                "routes a view as it was before the batch began"));
        assertTrue(refusal.getMessage(), refusal.getMessage().contains("moved or resized: vo-b"));
        assertTrue(refusal.getSuggestedCorrection(), refusal.getSuggestedCorrection().contains("end-batch"));
        assertTrue(refusal.getSuggestedCorrection(),
                refusal.getSuggestedCorrection().contains("then call auto-route-connections"));
        assertEquals("a refusal queues nothing, not even a router-type switch", queuedBefore, queued());
        assertEquals("the batch stays open", OperationalMode.BATCH, dispatcher.getMode(SESSION));

        dispatcher.endBatch(SESSION, true);
        assertEquals("the earlier move commits unchanged", 600, b.getBounds().getY());
        assertTrue("no stale route was stored", ab.getBendpoints().isEmpty());
    }

    @Test
    public void autoRoute_shouldRefuseTerminalsOnly_whenTheBatchMovedAnEndpoint() throws Exception {
        diagonalRoute();
        List<BendpointDto> before = ConnectionResponseBuilder.collectBendpoints(ab);
        dispatcher.beginBatch(SESSION, "move then rectify");
        move("vo-b", 700, 600);

        assertTrue(refused("terminals-only", null, null).getMessage().contains("moved or resized: vo-b"));
        dispatcher.endBatch(SESSION, true);
        assertEquals(600, b.getBounds().getY());
        assertEquals("terminals-only stored nothing", before, ConnectionResponseBuilder.collectBendpoints(ab));
    }

    @Test
    public void autoRoute_shouldRefuse_whenTheBatchMovedANonEndpointIntoTheRoute() {
        dispatcher.beginBatch(SESSION, "move an obstacle");
        accessor.updateViewObject(SESSION, "vo-obstacle", 560, 60, 100, 100,
                null, null, null, null, null, null, null, null);
        assertTrue(refused(null, null, null).getMessage().contains("moved or resized: vo-obstacle"));
    }

    @Test
    public void autoRoute_shouldRefuse_whenABulkPassInTheBatchMovedAnEndpoint() {
        dispatcher.beginBatch(SESSION, "bulk move");
        accessor.executeBulk(SESSION, List.of(new BulkOperation("update-view-object",
                Map.of("viewObjectId", "vo-b", "x", 700, "y", 600), null)), "bulk move", false);
        assertTrue(refused(null, null, null).getMessage().contains("moved or resized: vo-b"));
    }

    // ---- refused: kind (ii), an object the batch adds to the view -----------------------------------

    @Test
    public void autoRoute_shouldRefuse_whenTheBatchAddedAnObjectToTheView() {
        dispatcher.beginBatch(SESSION, "add then route");
        String added = accessor.addToView(SESSION, view.getId(), "el-c", 560, 60, 100, 100,
                false, null, null, null).entity().viewObject().viewObjectId();
        assertTrue(refused(null, null, null).getMessage().contains("added: " + added));
    }

    // ---- refused: kind (iii), a connection the batch adds to the view -------------------------------

    @Test
    public void autoRoute_shouldRefuse_whenTheBatchAddedAConnectionToTheView() {
        IDiagramModelArchimateObject c = object(view, "vo-c", elC, 400, 400, 120, 60);
        dispatcher.beginBatch(SESSION, "connect then route");
        String added = accessor.addConnectionToView(SESSION, view.getId(), "rel-ac", "vo-a", c.getId(),
                null, null, null, null, null).entity().viewConnectionId();
        for (String strategy : new String[] { null, "clear" }) {
            ModelAccessException refusal = refused(null, strategy, null);
            assertTrue(strategy + ": " + refusal.getMessage(),
                    refusal.getMessage().contains("connections added: " + added));
        }
        assertTrue("named, it is refused rather than reported not found",
                refused(null, null, List.of(added)).getMessage().contains("connections added: " + added));
    }

    // ---- refused: kind (iv), a removal from the view ------------------------------------------------

    @Test
    public void autoRoute_shouldRefuse_whenTheBatchRemovedAnObjectFromTheView() {
        dispatcher.beginBatch(SESSION, "remove then route");
        accessor.removeFromView(SESSION, view.getId(), "vo-obstacle");
        assertTrue(refused(null, null, null).getMessage().contains("removed: vo-obstacle"));
    }

    @Test
    public void autoRoute_shouldRefuse_whenTheBatchRemovedAConnectionFromTheView() {
        dispatcher.beginBatch(SESSION, "remove a connection");
        accessor.removeFromView(SESSION, view.getId(), "conn-ab");
        assertTrue(refused(null, null, null).getMessage().contains("removed: conn-ab"));
    }

    @Test
    public void autoRoute_shouldRefuse_whenTheBatchDeletedAnElementDrawnOnTheView() {
        dispatcher.beginBatch(SESSION, "delete a concept");
        accessor.deleteElement(SESSION, "el-c");
        assertTrue(refused(null, null, null).getMessage().contains("removed: vo-obstacle"));
    }

    @Test
    public void autoRoute_shouldRefuse_whenTheBatchDeletedARelationshipDrawnOnTheView() {
        dispatcher.beginBatch(SESSION, "delete a relationship");
        accessor.deleteRelationship(SESSION, "rel-ab");
        assertTrue(refused(null, null, null).getMessage().contains("removed: conn-ab"));
    }

    @Test
    public void autoRoute_shouldRefuse_whenTheBatchRemovedAGroupFromTheView() {
        IDiagramModelGroup group = factory.createDiagramModelGroup();
        group.setId("vo-group");
        group.setName("G");
        group.setBounds(0, 700, 200, 100);
        view.getChildren().add(group);
        dispatcher.beginBatch(SESSION, "remove a group");
        accessor.removeFromView(SESSION, view.getId(), "vo-group");
        assertTrue(refused(null, null, null).getMessage().contains("removed: vo-group"));
    }

    @Test
    public void autoRoute_shouldRefuse_whenTheBatchClearedTheView() {
        dispatcher.beginBatch(SESSION, "clear then route");
        accessor.clearView(SESSION, view.getId());
        assertTrue(refused(null, null, null).getMessage().contains("removed: vo-a, vo-b, vo-obstacle"));
    }

    @Test
    public void autoRoute_shouldRefuse_whenTheBatchDeletedTheView() {
        dispatcher.beginBatch(SESSION, "delete then route");
        accessor.deleteView(SESSION, view.getId());
        assertTrue(refused(null, null, null).getMessage().contains("removed: vo-a, vo-b, vo-obstacle"));
    }

    @Test
    public void autoRoute_shouldRefuse_whenTheBatchDeletedAViewThatAPlaceholderOnTheViewReferences() {
        IDiagramModelReference placeholder = factory.createDiagramModelReference();
        placeholder.setId("vo-placeholder");
        placeholder.setReferencedModel(otherView);
        placeholder.setBounds(0, 700, 120, 60);
        view.getChildren().add(placeholder);
        dispatcher.beginBatch(SESSION, "delete the referenced view");
        accessor.deleteView(SESSION, otherView.getId());
        assertTrue(refused(null, null, null).getMessage().contains("removed: vo-placeholder"));
    }

    @Test
    public void autoRoute_shouldRefuse_whenAForcedFolderDeleteInTheBatchRemovesAnObjectOnTheView() {
        IFolder folder = factory.createFolder();
        folder.setId("folder-nested");
        folder.setName("nested");
        model.getFolder(FolderType.APPLICATION).getFolders().add(folder);
        IArchimateElement nested = factory.createApplicationComponent();
        nested.setId("el-nested");
        folder.getElements().add(nested);
        object(view, "vo-nested", nested, 0, 700, 60, 60);
        dispatcher.beginBatch(SESSION, "force-delete a folder");
        accessor.deleteFolder(SESSION, "folder-nested", true);
        assertTrue(refused(null, null, null).getMessage().contains("removed: vo-nested"));
    }

    // ---- connections whose source is another connection ----------------------------------------------

    @Test
    public void autoRoute_shouldRefuse_whenTheOnlyTargetIsAConnectionFromAConnection() {
        IDiagramModelArchimateConnection onConnection = factory.createDiagramModelArchimateConnection();
        onConnection.setId("conn-on-ab");
        onConnection.setArchimateRelationship(relationship("rel-on-ab", ab.getArchimateRelationship(), elC));
        onConnection.connect(ab, obstacle);
        dispatcher.beginBatch(SESSION, "move then route the connection on a connection");
        move("vo-obstacle", 0, 600);
        assertTrue(refused(null, null, List.of("conn-on-ab")).getMessage().contains("moved or resized: vo-obstacle"));
    }

    @Test
    public void changes_shouldNameAnAddedConnectionWhoseSourceIsAConnection() {
        IDiagramModelArchimateConnection onConnection = factory.createDiagramModelArchimateConnection();
        onConnection.setId("conn-on-ab");
        onConnection.setArchimateRelationship(relationship("rel-on-ab", ab.getArchimateRelationship(), elC));
        // an end that resolves to no diagram, so each case is placed on the view by one end alone
        IDiagramModelArchimateObject detached = factory.createDiagramModelArchimateObject();
        detached.setId("vo-detached");
        Command fromConnection = new AddConnectionToViewCommand(onConnection, ab, detached);
        assertEquals("a connection source resolves to the diagram it is drawn on",
                Map.of(QueuedViewGeometry.CONNECTIONS_ADDED, List.of("conn-on-ab")),
                QueuedViewGeometry.changes(view, QueuedViewGeometry.Form.FULL, Map.of(), Map.of(),
                        List.of(fromConnection), null));
        Command toConnection = new AddConnectionToViewCommand(onConnection, detached, ab);
        assertEquals("the target end places it on the view when the source does not",
                Map.of(QueuedViewGeometry.CONNECTIONS_ADDED, List.of("conn-on-ab")),
                QueuedViewGeometry.changes(view, QueuedViewGeometry.Form.FULL, Map.of(), Map.of(),
                        List.of(toConnection), null));
    }

    // ---- kind (v): a queued bendpoint edit, terminals-only only --------------------------------------

    @Test
    public void autoRoute_shouldRefuseTerminalsOnly_whenTheBatchEditedATargetsBendpoints() throws Exception {
        diagonalRoute();
        dispatcher.beginBatch(SESSION, "edit then rectify");
        accessor.updateViewConnection(SESSION, "conn-ab", List.of(new BendpointDto(-60, 400, -360, 200)),
                null, null, null, null);
        assertTrue(refused("terminals-only", null, null).getMessage()
                .contains("connection bendpoints edited: conn-ab"));
    }

    @Test
    public void autoRoute_shouldStillRouteFully_whenTheBatchEditedATargetsBendpoints() throws Exception {
        dispatcher.beginBatch(SESSION, "edit then route");
        accessor.updateViewConnection(SESSION, "conn-ab", List.of(new BendpointDto(-60, 400, -360, 200)),
                null, null, null, null);
        assertQueues(null, null);
    }

    // ---- controls: not refused ----------------------------------------------------------------------

    @Test
    public void autoRoute_shouldStillQueue_whenTheBatchOnlyRestyledAnObjectOnTheView() throws Exception {
        dispatcher.beginBatch(SESSION, "restyle then route");
        accessor.updateViewObject(SESSION, "vo-b", null, null, null, null, null,
                new StylingParams("#ff0000", null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null),
                null, null, null, null, null, null);
        assertQueues(null, null);
        dispatcher.endBatch(SESSION, true);
        assertFalse("the route committed", ab.getBendpoints().isEmpty());
    }

    @Test
    public void autoRoute_shouldStillQueue_whenTheBatchWroteBoundsThatDidNotChange() {
        dispatcher.beginBatch(SESSION, "no-op move");
        move("vo-b", 700, 200);
        assertQueues(null, null);
    }

    @Test
    public void autoRoute_shouldStillQueue_whenTheBatchChangedGeometryOnAnotherView() {
        dispatcher.beginBatch(SESSION, "move elsewhere");
        move(elsewhere.getId(), 300, 300);
        assertQueues(null, null);
    }

    @Test
    public void autoRoute_shouldStillQueue_whenTheGeometryChangeWasRolledBack() throws Exception {
        dispatcher.beginBatch(SESSION, "rolled back");
        move("vo-b", 700, 600);
        dispatcher.endBatch(SESSION, false);
        dispatcher.beginBatch(SESSION, "fresh");
        assertQueues(null, null);
    }

    @Test
    public void autoRoute_shouldStillClear_whenTheBatchMovedAnEndpointOrRemovedAnObject() throws Exception {
        dispatcher.beginBatch(SESSION, "move then clear");
        move("vo-b", 700, 600);
        accessor.removeFromView(SESSION, view.getId(), "vo-obstacle");
        assertQueues(null, "clear");
        dispatcher.endBatch(SESSION, true);
        assertEquals(600, b.getBounds().getY());
    }

    @Test
    public void autoRoute_shouldStillQueue_whenTheBatchAddedAndRemovedOnlyOnAnotherView() {
        IDiagramModelArchimateObject far = object(otherView, "vo-far", elC, 400, 400, 120, 60);
        dispatcher.beginBatch(SESSION, "work elsewhere");
        accessor.addToView(SESSION, otherView.getId(), "el-c", 300, 300, 100, 100, false, null, null, null);
        accessor.addConnectionToView(SESSION, otherView.getId(), "rel-ac", elsewhere.getId(), far.getId(),
                null, null, null, null, null);
        accessor.removeFromView(SESSION, otherView.getId(), elsewhere.getId());
        assertQueues(null, null);
    }

    @Test
    public void autoRoute_shouldStillQueue_whenTheEditedOrAddedConnectionIsNotOneBeingRouted() throws Exception {
        diagonalRoute();
        IDiagramModelArchimateObject c = object(view, "vo-c", elC, 400, 400, 120, 60);
        dispatcher.beginBatch(SESSION, "connection work on another connection");
        String added = accessor.addConnectionToView(SESSION, view.getId(), "rel-ac", "vo-a", c.getId(),
                null, null, null, null, null).entity().viewConnectionId();
        accessor.updateViewConnection(SESSION, added, List.of(new BendpointDto(0, 50, 0, -50)),
                null, null, null, null);
        int before = queued().size();
        // terminals-only first: a clear of conn-ab is itself a queued bendpoint write it would refuse
        assertTrue(route("terminals-only", null, List.of("conn-ab")).isBatched());
        assertTrue(route(null, "clear", List.of("conn-ab")).isBatched());
        assertEquals(before + 2, queued().size());
    }

    @Test
    public void autoRoute_shouldStillQueue_whenEveryTargetIsASelfConnection() {
        IDiagramModelArchimateObject self = object(view, "vo-self", elC, 0, 300, 120, 60);
        IDiagramModelArchimateConnection loop = factory.createDiagramModelArchimateConnection();
        loop.setId("conn-self");
        loop.setArchimateRelationship(relationship("rel-self", elC, elC));
        loop.connect(self, self);
        dispatcher.beginBatch(SESSION, "resize then loop");
        accessor.updateViewObject(SESSION, "vo-self", 0, 300, 200, 90, null, null, null, null,
                null, null, null, null);
        move("vo-b", 700, 600);
        int before = queued().size();
        // the loop is sized when its command runs, so a queued resize cannot make it stale
        assertTrue(route(null, null, List.of("conn-self")).isBatched());
        assertEquals(before + 1, queued().size());
        assertTrue("with a real pair among the targets it is refused",
                refused(null, null, List.of("conn-self", "conn-ab")).getMessage().contains("vo-b"));
    }

    // ---- what the refusal names ---------------------------------------------------------------------

    @Test
    public void autoRoute_shouldNameEveryKindOnce_inAFixedOrder() {
        IDiagramModelArchimateObject c = object(view, "vo-c", elC, 400, 400, 120, 60);
        dispatcher.beginBatch(SESSION, "every kind");
        accessor.removeFromView(SESSION, view.getId(), "vo-obstacle");
        String connection = accessor.addConnectionToView(SESSION, view.getId(), "rel-ac", "vo-a", c.getId(),
                null, null, null, null, null).entity().viewConnectionId();
        String added = accessor.addToView(SESSION, view.getId(), "el-c", 900, 600, 100, 100,
                false, null, null, null).entity().viewObject().viewObjectId();
        move("vo-b", 700, 600);
        move("vo-b", 700, 650);

        String message = refused(null, null, null).getMessage();
        assertTrue(message, message.contains("view '" + view.getId() + "'"));
        assertTrue(message, message.contains("— moved or resized: vo-b; added: " + added
                + "; connections added: " + connection + "; removed: vo-obstacle. "));
    }

    @Test
    public void autoRoute_shouldNameAtMostTenIdsPerKind_whenMoreChanged() {
        IArchimateElement el = element("el-many");
        for (int i = 0; i < 12; i++) {
            object(view, "vo-many-" + i, el, 0, 1000 + i * 100, 60, 60);
        }
        dispatcher.beginBatch(SESSION, "many moves");
        for (int i = 0; i < 12; i++) {
            move("vo-many-" + i, 2000, 1000 + i * 100);
        }
        String message = refused(null, null, null).getMessage();
        assertTrue(message, message.contains("vo-many-9 (and 2 more)."));
        assertFalse(message, message.contains("vo-many-10"));
    }

    @Test
    public void autoRoute_shouldRefuse_whenTheBatchAddedAnObjectInsideAGroupItAlsoAdded() {
        dispatcher.beginBatch(SESSION, "nested add");
        String group = accessor.addGroupToView(SESSION, view.getId(), "G", 540, 40, 200, 200, null,
                null, null).entity().viewObjectId();
        String child = accessor.addToView(SESSION, view.getId(), "el-c", 20, 20, 100, 100, false, group,
                null, null).entity().viewObject().viewObjectId();
        assertTrue(refused(null, null, null).getMessage().contains("added: " + group + ", " + child));
    }

    // ---- outside a batch nothing changes ------------------------------------------------------------

    @Test
    public void autoRoute_shouldRouteOnTheAppliedArm_whenAnEndpointWasJustMoved() {
        move("vo-b", 700, 600);
        MutationResult<AutoRouteResultDto> result = route(null, null, null);
        assertFalse(result.isBatched());
        assertFalse(result.isProposal());
        assertEquals(1, result.entity().connectionsRouted());
        assertFalse(ab.getBendpoints().isEmpty());
    }

    @Test
    public void autoRoute_shouldStillPropose_whenApprovalIsOnAndAnEndpointWasJustMoved() {
        move("vo-b", 700, 600);
        approvalRequired = true;
        MutationResult<AutoRouteResultDto> result = route(null, null, null);
        approvalRequired = false;
        assertTrue(result.isProposal());
        assertEquals(1, dispatcher.getPendingProposals(SESSION).size());
    }

    // ---- a terminals-only call that changes nothing never empties a batch ----------------------------

    @Test
    public void autoRoute_shouldNotDropTheBatch_whenTerminalsOnlyChangesNothing() throws Exception {
        route(null, null, null); // already orthogonal, so terminals-only has nothing to rectify
        dispatcher.beginBatch(SESSION, "restyle then rectify");
        accessor.updateViewObject(SESSION, "vo-b", null, null, null, null, null,
                new StylingParams("#ff0000", null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null),
                null, null, null, null, null, null);
        List<String> queuedBefore = queued();
        AutoRouteResultDto dto = route("terminals-only", null, null).entity();
        assertEquals(0, dto.connectionsRouted());
        assertEquals("an empty compound is never queued", queuedBefore, queued());

        dispatcher.endBatch(SESSION, true);
        assertEquals("the earlier operation still commits", "#ff0000", b.getFillColor());
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private MutationResult<AutoRouteResultDto> route(String mode, String strategy, List<String> ids) {
        return accessor.autoRouteConnections(SESSION, view.getId(), ids, strategy, false, false,
                RoutingPipeline.DEFAULT_SNAP_THRESHOLD, RoutingPipeline.DEFAULT_PERIMETER_MARGIN, mode);
    }

    private ModelAccessException refused(String mode, String strategy, List<String> ids) {
        List<String> before = queued();
        try {
            route(mode, strategy, ids);
        } catch (ModelAccessException e) {
            assertEquals(e.getMessage(), ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertNotNull(e.getSuggestedCorrection());
            assertEquals("a refusal leaves the queue unchanged", before, queued());
            return e;
        }
        fail("expected auto-route (mode=" + mode + ", strategy=" + strategy
                + ") to be refused inside this batch");
        return null;
    }

    private void assertQueues(String mode, String strategy) {
        int before = queued().size();
        MutationResult<AutoRouteResultDto> result = route(mode, strategy, null);
        assertTrue("queued, not applied", result.isBatched());
        assertEquals("one more queued operation", before + 1, queued().size());
    }

    private List<String> queued() {
        return dispatcher.getBatchStatus(SESSION).queuedDescriptions();
    }

    private void move(String id, int x, int y) {
        accessor.updateViewObject(SESSION, id, x, y, null, null, null, null, null, null, null, null, null, null);
    }

    /** One bendpoint below both ends, so both terminal segments leave their elements diagonally. */
    private void diagonalRoute() {
        IDiagramModelBendpoint bp = factory.createDiagramModelBendpoint();
        bp.setStartX(150);
        bp.setStartY(300);
        bp.setEndX(-150);
        bp.setEndY(100);
        ab.getBendpoints().add(bp);
    }

    private IArchimateDiagramModel view(String id) {
        IArchimateDiagramModel v = factory.createArchimateDiagramModel();
        v.setId(id);
        v.setConnectionRouterType(IDiagramModel.CONNECTION_ROUTER_BENDPOINT);
        model.getFolder(FolderType.DIAGRAMS).getElements().add(v);
        return v;
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
