package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;

/**
 * Pins that {@code auto-route-connections} finds a view an earlier operation in the same batch
 * created, rather than answering that the view does not exist.
 *
 * <p>A created view is detached until the batch commits, so a lookup in the model alone cannot find
 * it. Once found through the batch queue, the answer is whatever is true of the view at that point in
 * the batch: with no connection on it there is nothing to route and nothing is queued; with a
 * connection the batch queued onto it, the route would be computed for a view the batch has not built
 * yet, and the call is refused naming that connection, in every mode. Before the fix every one of these
 * answered {@code VIEW_NOT_FOUND}.</p>
 */
public class BatchAutoRouteOnAQueuedViewTest {

    private static final String SESSION = "batch-auto-route-queued-view-session";
    private static final List<String> MODES = List.of("full", "terminals-only", "labels-only");

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private IArchimateModel model;
    private MutationDispatcher dispatcher;
    private ArchiModelAccessorImpl accessor;
    private CommandStack stack;

    @Before
    public void setUp() {
        model = factory.createArchimateModel();
        model.setId("model-batch-auto-route-queued-view");
        model.setDefaults();
        IArchimateElement elA = element("el-a");
        IArchimateElement elB = element("el-b");
        IArchimateRelationship ab = factory.createAssociationRelationship();
        ab.setId("rel-ab");
        ab.setSource(elA);
        ab.setTarget(elB);
        model.getFolder(FolderType.RELATIONS).getElements().add(ab);

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
        dispatcher.setApprovalModeProvider(() -> false);
        accessor = new ArchiModelAccessorImpl(mgr, dispatcher);
        dispatcher.onModelActive(model);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    @Test
    public void autoRoute_shouldRouteNothingAndQueueNothing_whenTheQueuedViewIsEmpty() {
        List<String> wrong = new ArrayList<>();
        for (String mode : MODES) {
            fresh();
            dispatcher.beginBatch(SESSION, "create then route");
            String view = createView();
            int queued = queued();
            AutoRouteResultDto routed = route(view, mode);
            check(wrong, mode + ": nothing routed", 0, routed.connectionsRouted());
            check(wrong, mode + ": nothing queued", queued, queued());
            check(wrong, mode + ": the view id is the queued view's", view, routed.viewId());
            dispatcher.endBatch(SESSION, false);
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    public void autoRoute_shouldRouteNothingAndQueueNothing_whenTheQueuedViewHoldsObjectsButNoConnection()
            throws Exception {
        List<String> wrong = new ArrayList<>();
        for (String mode : MODES) {
            fresh();
            dispatcher.beginBatch(SESSION, "create, place, route");
            String view = createView();
            place(view, "el-a", 0, 0);
            place(view, "el-b", 400, 300);
            int queued = queued();
            check(wrong, mode + ": nothing routed", 0, route(view, mode).connectionsRouted());
            check(wrong, mode + ": nothing queued", queued, queued());
            dispatcher.endBatch(SESSION, true);
            check(wrong, mode + ": the batch committed the view with both objects", 2,
                    committedView(view).getChildren().size());
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    public void autoRoute_shouldRefuseNamingTheQueuedConnection_whenTheBatchConnectedObjectsOnTheQueuedView() {
        List<String> wrong = new ArrayList<>();
        for (String mode : MODES) {
            fresh();
            dispatcher.beginBatch(SESSION, "create, place, connect, route");
            String view = createView();
            String a = place(view, "el-a", 0, 0);
            String b = place(view, "el-b", 400, 300);
            String connection = accessor.addConnectionToView(SESSION, view, "rel-ab", a, b, null, null, null, null,
                    null).entity().viewConnectionId();
            int queued = queued();
            try {
                route(view, mode);
                wrong.add(mode + ": answered instead of refusing");
            } catch (ModelAccessException refusal) {
                check(wrong, mode + ": code", ErrorCode.INVALID_PARAMETER, refusal.getErrorCode());
                if (!refusal.getMessage().contains("connections added: " + connection)) {
                    wrong.add(mode + ": refusal does not name the queued connection: " + refusal.getMessage());
                }
            }
            check(wrong, mode + ": nothing queued", queued, queued());
            check(wrong, mode + ": the batch is still open", OperationalMode.BATCH, dispatcher.getMode(SESSION));
            dispatcher.endBatch(SESSION, false);
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    public void autoRoute_shouldStillAnswerViewNotFound_withTheSuggestion_whenTheIdNamesNoView() {
        dispatcher.beginBatch(SESSION, "a bogus id in a batch");
        createView();
        for (boolean inBatch : new boolean[] { true, false }) {
            if (!inBatch) {
                dispatcher.endBatch(SESSION, false);
            }
            try {
                route("no-such-view", "full");
                fail("a bogus id must be refused");
            } catch (ModelAccessException refusal) {
                assertEquals(ErrorCode.VIEW_NOT_FOUND, refusal.getErrorCode());
                assertEquals("View not found: no-such-view", refusal.getMessage());
                assertEquals("Use get-views to find valid view IDs", refusal.getSuggestedCorrection());
            }
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    private void fresh() {
        tearDown();
        setUp();
    }

    private String createView() {
        return accessor.createView(SESSION, "Created", null, null, "manual").entity().id();
    }

    private String place(String view, String element, int x, int y) {
        return accessor.addToView(SESSION, view, element, x, y, 120, 60, false, null, null, null).entity()
                .viewObject().viewObjectId();
    }

    private AutoRouteResultDto route(String view, String mode) {
        return accessor.autoRouteConnections(SESSION, view, null, null, false, false,
                RoutingPipeline.DEFAULT_SNAP_THRESHOLD, RoutingPipeline.DEFAULT_PERIMETER_MARGIN, mode).entity();
    }

    private int queued() {
        return dispatcher.getBatchStatus(SESSION).queuedCount();
    }

    private IArchimateDiagramModel committedView(String id) {
        EObject found = ArchimateModelUtils.getObjectByID(model, id);
        assertNotNull("the view is in the model: " + id, found);
        assertTrue(found instanceof IArchimateDiagramModel);
        return (IArchimateDiagramModel) found;
    }

    private static void check(List<String> wrong, String what, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            wrong.add(what + ": expected " + expected + " but was " + actual);
        }
    }

    private IArchimateElement element(String id) {
        IArchimateElement el = factory.createApplicationComponent();
        el.setId(id);
        model.getFolder(FolderType.APPLICATION).getElements().add(el);
        return el;
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
