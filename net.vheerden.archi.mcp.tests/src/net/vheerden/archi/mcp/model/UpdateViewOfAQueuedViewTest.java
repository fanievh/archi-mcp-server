package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.response.dto.BatchSummaryDto;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.ViewDto;

/**
 * Pins that {@code update-view} can address a view the same request created: by back-reference inside
 * one {@code bulk-mutate} call, and by id inside a batch.
 *
 * <p>Every other view-addressing operation already found such a view through the request's own
 * record of what it made; {@code update-view} looked in the model alone, where a created view is not
 * until the request commits, and answered {@code VIEW_NOT_FOUND}. The update's undo snapshot is taken
 * from the view as created, so undoing the request removes the view and redo brings it back
 * updated.</p>
 */
public class UpdateViewOfAQueuedViewTest {

    private static final String SESSION = "update-view-queued-view-session";

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private IArchimateModel model;
    private MutationDispatcher dispatcher;
    private ArchiModelAccessorImpl accessor;
    private CommandStack stack;
    private boolean approvalRequired;

    @Before
    public void setUp() {
        model = factory.createArchimateModel();
        model.setId("model-update-view-queued-view");
        model.setDefaults();
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

    @Test
    public void bulk_shouldUpdateTheViewTheCallCreated_andPlaceSixGroupsOnIt() {
        List<BulkOperation> ops = new ArrayList<>();
        ops.add(new BulkOperation("create-view", Map.of("name", "Created")));
        ops.add(new BulkOperation("update-view", updates("$0.id")));
        for (int i = 0; i < 6; i++) {
            ops.add(new BulkOperation("add-group-to-view", Map.of("viewId", "$0.id", "label", "",
                    "x", i * 200, "y", 0, "width", 150, "height", 100)));
        }

        BulkMutationResult result = accessor.executeBulk(SESSION, ops, "create, update, group", false, null);

        assertTrue(result.allSucceeded());
        IArchimateDiagramModel view = view(result.operations().get(0).entityId());
        assertUpdated(view);
        assertEquals(6, view.getChildren().stream().filter(IDiagramModelGroup.class::isInstance).count());
        assertUndoRemovesTheView_andRedoBringsItBackUpdated(view.getId());
    }

    @Test
    public void batch_shouldUpdateTheViewAnEarlierOperationCreated() throws Exception {
        dispatcher.beginBatch(SESSION, "create then update");
        String id = accessor.createView(SESSION, "Created", null, null, null).entity().id();
        accessor.updateView(SESSION, id, "Renamed", "layered", "doc", Map.of("k", "v"), "manhattan");
        dispatcher.endBatch(SESSION, true);

        assertUpdated(view(id));
        assertUndoRemovesTheView_andRedoBringsItBackUpdated(id);
    }

    @Test
    public void batch_shouldDeclineTheUpdate_whenTheViewItNamesWasNeverCreated() throws Exception {
        String doomed = accessor.createFolder(SESSION, model.getFolder(FolderType.DIAGRAMS).getId(), "Doomed",
                null, null).entity().id();
        dispatcher.beginBatch(SESSION, "delete a folder, create a view in it, update the view");
        accessor.deleteFolder(SESSION, doomed, false);
        String id = accessor.createView(SESSION, "InDoomed", null, doomed, null).entity().id();
        accessor.updateView(SESSION, id, "Renamed", null, null, null, null);

        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertNull(ArchimateModelUtils.getObjectByID(model, id));
        List<String> skipped = summary.skippedOperations();
        assertTrue("skipped: " + skipped, skipped != null && skipped.stream().anyMatch(line -> line.contains(
                "The view 'InDoomed' was not updated: it is not in the model.")));
    }

    @Test
    public void bulkInABatch_shouldUpdateTheViewAnEarlierBatchOperationCreated() throws Exception {
        dispatcher.beginBatch(SESSION, "create, then update in a bulk call");
        String id = accessor.createView(SESSION, "Created", null, null, null).entity().id();
        BulkMutationResult result = accessor.executeBulk(SESSION,
                List.of(new BulkOperation("update-view", updates(id))), "update", false, null);
        assertTrue(result.allSucceeded());
        dispatcher.endBatch(SESSION, true);

        assertUpdated(view(id));
    }

    @Test
    public void approvalInABatch_shouldUpdateTheViewAnEarlierBatchOperationCreated() throws Exception {
        dispatcher.beginBatch(SESSION, "create, then propose an update");
        String id = accessor.createView(SESSION, "Created", null, null, null).entity().id();
        approvalRequired = true;
        MutationResult<ViewDto> proposed = accessor.updateView(SESSION, id, "Renamed", "layered", "doc",
                Map.of("k", "v"), "manhattan");
        approvalRequired = false;
        assertTrue(proposed.isProposal());

        dispatcher.approveProposal(SESSION, proposed.proposalContext().proposalId());
        dispatcher.endBatch(SESSION, true);

        assertUpdated(view(id));
    }

    @Test
    public void updateView_shouldStillAnswerViewNotFound_whenTheIdNamesNoView() {
        dispatcher.beginBatch(SESSION, "a bogus id in a batch");
        accessor.createView(SESSION, "Created", null, null, null);
        for (boolean inBatch : new boolean[] { true, false }) {
            if (!inBatch) {
                dispatcher.endBatch(SESSION, false);
            }
            try {
                accessor.updateView(SESSION, "no-such-view", "Renamed", null, null, null, null);
                fail("a bogus id must be refused");
            } catch (ModelAccessException refusal) {
                assertEquals(ErrorCode.VIEW_NOT_FOUND, refusal.getErrorCode());
                assertEquals("View not found: no-such-view", refusal.getMessage());
                assertEquals("Use get-views to find the correct view ID", refusal.getSuggestedCorrection());
            }
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    private static Map<String, Object> updates(String viewId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("viewId", viewId);
        params.put("name", "Renamed");
        params.put("viewpoint", "layered");
        params.put("documentation", "doc");
        params.put("properties", Map.of("k", "v"));
        params.put("connectionRouterType", "manhattan");
        return params;
    }

    private static void assertUpdated(IArchimateDiagramModel view) {
        assertEquals("Renamed", view.getName());
        assertEquals("layered", view.getViewpoint());
        assertEquals("doc", view.getDocumentation());
        assertEquals(IDiagramModel.CONNECTION_ROUTER_MANHATTAN, view.getConnectionRouterType());
        assertEquals(1, view.getProperties().size());
        assertEquals("k", view.getProperties().get(0).getKey());
        assertEquals("v", view.getProperties().get(0).getValue());
    }

    private void assertUndoRemovesTheView_andRedoBringsItBackUpdated(String id) {
        int viewsBefore = model.getFolder(FolderType.DIAGRAMS).getElements().size();
        stack.undo();
        assertNull("undo removes the view the request created", ArchimateModelUtils.getObjectByID(model, id));
        assertEquals(viewsBefore - 1, model.getFolder(FolderType.DIAGRAMS).getElements().size());
        stack.redo();
        assertUpdated(view(id));
    }

    private IArchimateDiagramModel view(String id) {
        EObject found = ArchimateModelUtils.getObjectByID(model, id);
        assertTrue("the view is in the model: " + id, found instanceof IArchimateDiagramModel);
        return (IArchimateDiagramModel) found;
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
