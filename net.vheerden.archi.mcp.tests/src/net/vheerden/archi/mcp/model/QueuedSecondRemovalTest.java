package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
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

import com.archimatetool.editor.model.commands.NonNotifyingCompoundCommand;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelComponent;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IFolder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.handlers.HandlerRegistrar;
import net.vheerden.archi.mcp.handlers.SearchHandler;
import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.server.ApprovalService;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * A batch refuses to queue a removal of a view object, or connection, that an operation it already holds
 * removes: the second would find it gone at commit, or would put it back a second time on Undo. The batch
 * stays open, and the first removal commits and undoes as one entry.
 */
public class QueuedSecondRemovalTest {

    private static final String SESSION = "default";
    private static final String DROP_IT = "Nothing was queued and the batch is still open. The earlier operation "
            + "removes it; drop this call.";

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private final MergeElementsCommandTest.StubEditorModelManager models =
            new MergeElementsCommandTest.StubEditorModelManager();
    private IArchimateModel model;
    private IFolder business;
    private IArchimateDiagramModel viewA;
    private IDiagramModelArchimateObject box1;
    private IDiagramModelArchimateObject box2;
    private IDiagramModelNote note;
    private IDiagramModelGroup group;
    private IDiagramModelArchimateConnection connection;
    private CommandStack stack;
    private boolean approvalMode;
    private MutationDispatcher dispatcher;
    private ArchiModelAccessorImpl accessor;
    private CommandRegistry registry;

    @Before
    public void setUp() {
        model = factory.createArchimateModel();
        model.setId("model-second-removal");
        model.setDefaults();
        business = model.getFolder(FolderType.BUSINESS);
        models.setModels(List.of(model));
        stack = new CommandStack();
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                executeEntry(stack, agentEntry(headless(command)));
            }

            @Override
            protected void dispatchCommand(Command command) {
                executeEntry(stack, agentEntry(headless(command)));
            }
        };
        dispatcher.setApprovalModeProvider(() -> approvalMode);
        accessor = new ArchiModelAccessorImpl(models, dispatcher);
        dispatcher.onModelActive(model);
        registry = new CommandRegistry();
        HandlerRegistrar.registerAll(accessor, new ResponseFormatter(), registry,
                new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS));

        viewA = factory.createArchimateDiagramModel();
        viewA.setId("view-a");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(viewA);
        IArchimateElement s = actor("s");
        IArchimateElement t = actor("t");
        box1 = place(s, "box-1");
        box2 = place(t, "box-2");
        note = factory.createDiagramModelNote();
        note.setId("note-1");
        viewA.getChildren().add(note);
        group = factory.createDiagramModelGroup();
        group.setId("group-1");
        viewA.getChildren().add(group);
        IArchimateRelationship relationship = factory.createAssociationRelationship();
        relationship.setId("rel-1");
        relationship.connect(s, t);
        model.getFolder(FolderType.RELATIONS).getElements().add(relationship);
        connection = factory.createDiagramModelArchimateConnection();
        connection.setId("conn-1");
        connection.setArchimateRelationship(relationship);
        connection.connect(box1, box2);
    }

    @After
    public void tearDown() {
        accessor.dispose();
    }

    @Test
    public void shouldRefuseTheSecondRemoval_andCommitTheFirst_whenAnElementIsRemovedTwice() throws Exception {
        assertSecondRefused(box1);
    }

    @Test
    public void shouldRefuseTheSecondRemoval_andUndoOnce_whenANoteIsRemovedTwice() throws Exception {
        assertSecondRefused(note);
    }

    @Test
    public void shouldRefuseTheSecondRemoval_andUndoOnce_whenAGroupIsRemovedTwice() throws Exception {
        assertSecondRefused(group);
    }

    @Test
    public void shouldRefuseTheSecondRemoval_andUndoOnce_whenAConnectionIsRemovedTwice() throws Exception {
        assertSecondRefused(connection);
    }

    @Test
    public void shouldQueueBoth_whenTheBatchRemovesTwoDifferentObjects() throws Exception {
        invoke("begin-batch", Map.of("modelId", model.getId()));
        invoke("remove-from-view", remove("note-1"));
        invoke("remove-from-view", remove("group-1"));

        Map<?, ?> result = (Map<?, ?>) invoke("end-batch", Map.of()).get("result");

        assertEquals(2, result.get("operationCount"));
        assertFalse(viewA.getChildren().contains(note));
        assertFalse(viewA.getChildren().contains(group));
    }

    @Test
    public void shouldRefuseTheApproval_andKeepTheCard_whenAnApprovedRemovalTakesOffWhatTheBatchRemoves() {
        dispatcher.beginBatch(SESSION, "remove twice");
        approvalMode = true;
        MutationResult<?> first = accessor.removeFromView(SESSION, "view-a", "note-1");
        MutationResult<?> second = accessor.removeFromView(SESSION, "view-a", "note-1");
        ApprovalService approvals = new ApprovalService(dispatcher);
        approvals.approve(SESSION, first.proposalContext().proposalId());

        try {
            approvals.approve(SESSION, second.proposalContext().proposalId());
            fail("approving a second removal of the note must be refused");
        } catch (MutationException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("View object note-1 is already removed by "
                    + "operation #1 of this batch"));
            assertTrue(e.getMessage(), e.getMessage().endsWith("Reject this proposal: an operation already queued "
                    + "in the agent's batch takes that object off the view when the batch commits."));
        }

        assertEquals("the card is kept", 1, dispatcher.getPendingProposalDtos(SESSION).size());
        assertEquals("the batch is still open", OperationalMode.BATCH, dispatcher.getMode(SESSION));
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private void assertSecondRefused(IDiagramModelComponent object) throws Exception {
        String id = object.getId();
        invoke("begin-batch", Map.of("modelId", model.getId()));
        invoke("remove-from-view", remove(id));

        McpSchema.CallToolResult second = call("remove-from-view", remove(id));

        String text = ((McpSchema.TextContent) second.content().get(0)).text();
        assertTrue("the second removal must be refused: " + text, Boolean.TRUE.equals(second.isError()));
        Map<?, ?> error = (Map<?, ?>) parse(text).get("error");
        assertEquals(ErrorCode.VIEW_OBJECT_NOT_FOUND.name(), error.get("code"));
        String message = (String) error.get("message");
        assertEquals("View object " + id + " is already removed by operation #1 of this batch (Remove from view: "
                + id + ").", message);
        assertEquals(DROP_IT, error.get("suggestedCorrection"));
        assertEquals("the batch is still open", OperationalMode.BATCH, dispatcher.getMode(SESSION));

        Map<?, ?> result = (Map<?, ?>) invoke("end-batch", Map.of()).get("result");
        assertEquals(1, result.get("operationCount"));
        assertFalse("removed", isOnTheView(object));
        assertEquals("one undo entry", 1, stack.getCommands().length);

        stack.undo();

        assertTrue("Undo puts it back", isOnTheView(object));
        if (object != connection) {
            assertEquals("once", 1, viewA.getChildren().stream().filter(c -> c == object).count());
        }
        assertEquals("one connection between the boxes", 1, box1.getSourceConnections().size());
        assertEquals(1, box2.getTargetConnections().size());
        assertSame(connection, box1.getSourceConnections().get(0));
        assertFalse("the model is as it was saved", stack.isDirty());
    }

    private boolean isOnTheView(IDiagramModelComponent object) {
        return object == connection ? box1.getSourceConnections().contains(connection)
                : viewA.getChildren().contains(object);
    }

    private static Map<String, Object> remove(String id) {
        return Map.of("viewId", "view-a", "viewObjectId", id);
    }

    private Map<String, Object> invoke(String toolName, Map<String, Object> args) throws Exception {
        McpSchema.CallToolResult result = call(toolName, args);
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertFalse(text, Boolean.TRUE.equals(result.isError()));
        return parse(text);
    }

    private McpSchema.CallToolResult call(String toolName, Map<String, Object> args) {
        McpServerFeatures.SyncToolSpecification spec = registry.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not found: " + toolName));
        return spec.callHandler().apply(null, new McpSchema.CallToolRequest(toolName, args));
    }

    private static Map<String, Object> parse(String text) throws Exception {
        return new ObjectMapper().readValue(text, new TypeReference<Map<String, Object>>() { });
    }

    /**
     * Archi's notifying compound needs the editor's model manager, which the headless lane lacks: run its
     * children, the same instances, in a plain compound.
     */
    private static Command headless(Command command) {
        if (command.getClass() != NonNotifyingCompoundCommand.class) {
            return command;
        }
        CompoundCommand plain = new CompoundCommand(command.getLabel());
        for (Object child : ((CompoundCommand) command).getCommands()) {
            plain.add(headless((Command) child));
        }
        return plain;
    }

    private IArchimateElement actor(String id) {
        IArchimateElement element = factory.createBusinessActor();
        element.setId(id);
        element.setName(id);
        business.getElements().add(element);
        return element;
    }

    private IDiagramModelArchimateObject place(IArchimateElement element, String id) {
        IDiagramModelArchimateObject box = factory.createDiagramModelArchimateObject();
        box.setId(id);
        box.setArchimateElement(element);
        box.setBounds(0, 0, 120, 55);
        viewA.getChildren().add(box);
        return box;
    }
}
