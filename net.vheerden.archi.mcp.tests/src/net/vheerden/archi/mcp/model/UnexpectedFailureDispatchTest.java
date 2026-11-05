package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.common.notify.impl.AdapterImpl;
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
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.util.ArchimateModelUtils;

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
import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.MergeClusterRequest;
import net.vheerden.archi.mcp.response.dto.MergeElementsResultDto;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * A queued operation that throws as the batch commits fails the commit: every operation queued before it is
 * put back, the batch ends, and end-batch names the operation that failed, without its raw message. A single
 * call that throws says part of it may still be in the model.
 *
 * <p>The dispatcher runs the production stack-entry steps on a real {@link CommandStack}, with Archi's
 * notifying compound rebuilt as a plain one over the same instances, so no {@code Display} is needed.</p>
 */
public class UnexpectedFailureDispatchTest {

    private static final String SESSION = "default";
    private static final String PUT_BACK = "Every operation queued before it was put back, and the batch has ended.";
    private static final String HEDGE = "Part of what it wrote may still be in the model, and Undo will not remove "
            + "it; it does not mark the model as changed either. Check the model in Archi before going on.";
    private static final String MAY_STILL_BE = "Part of this change may still be in the model and Undo will not "
            + "remove it. Re-read the elements with get-element, and the views that showed them, before going on.";
    private static final String BATCH_ENDED = "The batch has ended and its queue was discarded. Nothing it queued "
            + "was applied, except what the message says may still be in the model. Check it with get-model-info and "
            + "get-element before going on, and use begin-batch to start a new batch.";

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private final MergeElementsCommandTest.StubEditorModelManager models =
            new MergeElementsCommandTest.StubEditorModelManager();
    private IArchimateModel model;
    private IFolder business;
    private IArchimateDiagramModel viewA;
    private IDiagramModelArchimateObject box1;
    private IDiagramModelArchimateObject box2;
    private CommandStack stack;
    private Command earlier;
    private MutationDispatcher dispatcher;
    private ArchiModelAccessorImpl accessor;
    private CommandRegistry registry;
    /** Runs on the dispatch path, after the prepare and before the command executes. */
    private Runnable beforeDispatch;

    @Before
    public void setUp() {
        model = factory.createArchimateModel();
        model.setId("model-unexpected-failure");
        model.setDefaults();
        business = model.getFolder(FolderType.BUSINESS);
        models.setModels(List.of(model));
        stack = new CommandStack();
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                runBeforeDispatch();
                executeEntry(stack, agentEntry(headless(command)));
            }

            @Override
            protected void dispatchCommand(Command command) {
                runBeforeDispatch();
                executeEntry(stack, agentEntry(headless(command)));
            }
        };
        dispatcher.setApprovalModeProvider(() -> false);
        accessor = new ArchiModelAccessorImpl(models, dispatcher);
        dispatcher.onModelActive(model);
        registry = new CommandRegistry();
        HandlerRegistrar.registerAll(accessor, new ResponseFormatter(), registry,
                new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS));
        // One ordinary entry first, saved, so "the stack is as it was" is not read off an empty stack.
        IArchimateElement first = actor("earlier");
        business.getElements().remove(first);
        MutationDispatcher.executeEntry(stack, dispatcher.agentEntry(new Command("Add earlier") {
            @Override
            public void execute() {
                business.getElements().add(first);
            }
        }));
        stack.markSaveLocation();
        earlier = stack.getUndoCommand();

        viewA = factory.createArchimateDiagramModel();
        viewA.setId("view-a");
        viewA.setName("view-a");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(viewA);
        IArchimateElement t = actor("t");
        IArchimateElement s = actor("s");
        box1 = place(s, "box-1");
        box2 = place(s, "box-2");
        place(t, "box-t");
    }

    @After
    public void tearDown() {
        accessor.dispose();
    }

    // ---- a batch commit -------------------------------------------------------------------------

    @Test
    public void shouldPutBackWhatRanBefore_andEndTheBatch_whenAQueuedRemoveThrowsAtCommit() throws Exception {
        dispatcher.beginBatch(SESSION, "create then remove two boxes");
        String made = createQueued("Made");
        accessor.removeFromView(SESSION, "view-a", "box-1");
        accessor.removeFromView(SESSION, "view-a", "box-2");
        viewA.eAdapters().add(new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                if (n.getEventType() == Notification.REMOVE && n.getOldValue() == box2) {
                    throw new IllegalStateException("listener");
                }
            }
        });
        String version = accessor.getModelVersion();

        Map<?, ?> error = endBatchError();

        assertEquals(ErrorCode.MUTATION_FAILED.name(), error.get("code"));
        String message = (String) error.get("message");
        assertEquals("Batch commit failed: Remove from view: box-2 failed unexpectedly. " + PUT_BACK + " " + HEDGE,
                message);
        assertFalse("no raw message: " + message, message.contains("listener"));
        assertEquals(BATCH_ENDED, error.get("suggestedCorrection"));
        assertNull("the create was put back", ArchimateModelUtils.getObjectByID(model, made));
        assertEquals("box-1 is back on the view, once", 1, viewA.getChildren().stream().filter(b -> b == box1)
                .count());
        assertSame("the undo entry is still the earlier one", earlier, stack.getUndoCommand());
        assertEquals(1, stack.getCommands().length);
        assertFalse("the model is not marked changed", stack.isDirty());
        assertNotEquals("the model may have changed: a new version", version, accessor.getModelVersion());
        assertBatchEndedAndANewOneCommits();
    }

    @Test
    public void shouldPutBackWhatRanBefore_andEndTheBatch_whenAQueuedCommandThrowsAnErrorAtCommit() {
        dispatcher.beginBatch(SESSION, "create then remove two boxes");
        String made = createQueued("Made");
        accessor.removeFromView(SESSION, "view-a", "box-1");
        accessor.removeFromView(SESSION, "view-a", "box-2");
        AssertionError thrown = new AssertionError("listener");
        viewA.eAdapters().add(new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                if (n.getEventType() == Notification.REMOVE && n.getOldValue() == box2) {
                    throw thrown;
                }
            }
        });
        String version = accessor.getModelVersion();

        MutationException e = endBatchThrows();

        assertTrue(e.getMessage(), e.getMessage().startsWith("Remove from view: box-2 failed unexpectedly. "
                + PUT_BACK + " " + HEDGE));
        assertFalse("no raw message: " + e.getMessage(), e.getMessage().contains("listener"));
        assertTrue("the cause chain reaches the Error", causes(e).contains(thrown));
        assertNull("the create was put back", ArchimateModelUtils.getObjectByID(model, made));
        assertTrue("box-1 is back on the view", viewA.getChildren().contains(box1));
        assertFalse("the failing remove is not undone: what it wrote stays, as the message says",
                viewA.getChildren().contains(box2));
        assertSame(earlier, stack.getUndoCommand());
        assertFalse(stack.isDirty());
        assertNotEquals(version, accessor.getModelVersion());
        assertEquals("the batch has ended", OperationalMode.GUI_ATTACHED, dispatcher.getMode(SESSION));
    }

    @Test
    public void shouldPutBackWhatRanBefore_andNameTheMerge_whenAQueuedMergeRethrowsAnError() throws Exception {
        dispatcher.beginBatch(SESSION, "create then merge");
        String made = createQueued("Made");
        MutationResult<MergeElementsResultDto> queued = accessor.mergeElements(SESSION,
                List.of(new MergeClusterRequest("t", List.of("s"))), null, null, false, false);
        String description = MergeElementsOperations.describe(queued.entity());
        String version = accessor.getModelVersion();
        viewA.eAdapters().add(new AdapterImpl() {
            private int removes;

            @Override
            public void notifyChanged(Notification n) {
                if (n.getEventType() == Notification.REMOVE && ++removes == 1) {
                    throw new AssertionError("listener");
                }
            }
        });

        Map<?, ?> error = endBatchError();

        String message = (String) error.get("message");
        assertEquals("Batch commit failed: " + description + " failed unexpectedly. " + PUT_BACK + " " + HEDGE,
                message);
        assertEquals(BATCH_ENDED, error.get("suggestedCorrection"));
        assertNull("the create was put back", ArchimateModelUtils.getObjectByID(model, made));
        assertNotNull("the merge undid its own write", ArchimateModelUtils.getObjectByID(model, "s"));
        assertTrue(viewA.getChildren().contains(box1));
        assertSame(earlier, stack.getUndoCommand());
        assertFalse("the model is not marked changed", stack.isDirty());
        assertNotEquals("the model may have changed: a new version", version, accessor.getModelVersion());
        assertBatchEndedAndANewOneCommits();
    }

    // ---- an immediate call ----------------------------------------------------------------------

    @Test
    public void shouldSayPartMayStillBeInTheModel_andPushNothing_whenAnImmediateRemoveThrows() throws Exception {
        beforeDispatch = () -> viewA.getChildren().remove(box1);
        String version = accessor.getModelVersion();

        McpSchema.CallToolResult result = call("remove-from-view", Map.of("viewId", "view-a",
                "viewObjectId", "box-1"));

        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue("the call must fail: " + text, Boolean.TRUE.equals(result.isError()));
        Map<?, ?> error = (Map<?, ?>) parse(text).get("error");
        assertEquals(ErrorCode.MUTATION_FAILED.name(), error.get("code"));
        assertEquals("Mutation failed: This change failed unexpectedly. " + HEDGE, error.get("message"));
        assertFalse(text, text.contains("postcondition"));
        assertSame("the undo entry is still the earlier one", earlier, stack.getUndoCommand());
        assertEquals(1, stack.getCommands().length);
        assertEquals("a retry could apply part of it twice", MAY_STILL_BE, error.get("suggestedCorrection"));
        assertNotEquals("part of it may still be in the model: a new version", version, accessor.getModelVersion());
    }

    @Test
    public void shouldPutBackEveryOperationThatRan_whenABulkCallThrowsPartway() throws Exception {
        String version = accessor.getModelVersion();

        McpSchema.CallToolResult result = call("bulk-mutate", Map.of("operations", List.of(
                createOp("X"), removeOp("box-1"), removeOp("box-1"))));

        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue("the call must fail: " + text, Boolean.TRUE.equals(result.isError()));
        Map<?, ?> error = (Map<?, ?>) parse(text).get("error");
        assertEquals(ErrorCode.MUTATION_FAILED.name(), error.get("code"));
        assertEquals("Mutation failed: This change failed unexpectedly. " + HEDGE, error.get("message"));
        assertEquals(MAY_STILL_BE, error.get("suggestedCorrection"));
        assertFalse(text, text.contains("postcondition"));
        assertTrue("the create was put back", named("X").isEmpty());
        assertTrue("box-1 is back on the view", viewA.getChildren().contains(box1));
        assertSame("the undo entry is still the earlier one", earlier, stack.getUndoCommand());
        assertEquals(1, stack.getCommands().length);
        assertFalse("the model is not marked changed", stack.isDirty());
        assertNotEquals("part of it may still be in the model: a new version", version, accessor.getModelVersion());
    }

    @Test
    public void shouldPutBackTheBulkOperationsThatRan_andWhatWasQueuedBefore_whenABulkThrowsAtCommit()
            throws Exception {
        invoke("begin-batch", Map.of("modelId", model.getId()));
        String made = createQueued("Made");
        invoke("bulk-mutate", Map.of("operations", List.of(createOp("X"), removeOp("box-1"), removeOp("box-1"))));
        // The bulk's own compound is Archi's notifying one inside the batch: record its notices.
        List<String> notices = new java.util.ArrayList<>();
        AgentAuthoredCompoundCommand.Notices archi = AgentAuthoredCompoundCommand.notices;
        AgentAuthoredCompoundCommand.notices = (source, property) -> notices.add(property + " " + source.getLabel());

        Map<?, ?> error;
        try {
            error = endBatchError();
        } finally {
            AgentAuthoredCompoundCommand.notices = archi;
        }

        assertEquals("the bulk ran, and its views were told it ended", List.of(
                "IEditorModelManager.ecore.events.start Bulk mutation (3 operations)",
                "IEditorModelManager.ecore.events.end Bulk mutation (3 operations)"), notices);

        assertEquals("Batch commit failed: Bulk mutation (3 operations) failed unexpectedly. " + PUT_BACK + " "
                + HEDGE, error.get("message"));
        assertEquals(BATCH_ENDED, error.get("suggestedCorrection"));
        assertNull("the create queued before it was put back", ArchimateModelUtils.getObjectByID(model, made));
        assertTrue("the bulk's own create was put back", named("X").isEmpty());
        assertTrue("box-1 is back on the view", viewA.getChildren().contains(box1));
        assertSame(earlier, stack.getUndoCommand());
        assertFalse(stack.isDirty());
        assertBatchEndedAndANewOneCommits();
    }

    // ---- helpers --------------------------------------------------------------------------------

    private void assertBatchEndedAndANewOneCommits() throws Exception {
        assertEquals("the batch has ended", OperationalMode.GUI_ATTACHED, dispatcher.getMode(SESSION));
        invoke("begin-batch", Map.of("modelId", model.getId()));
        accessor.createElement(SESSION, "BusinessActor", "Again", null, null, null, null);
        Map<?, ?> result = (Map<?, ?>) invoke("end-batch", Map.of()).get("result");
        assertEquals("a new batch on the same session commits: " + result, 1, result.get("operationCount"));
        assertEquals(2, stack.getCommands().length);
    }

    private static Map<String, Object> createOp(String name) {
        return Map.of("tool", "create-element", "params", Map.of("type", "BusinessActor", "name", name));
    }

    private static Map<String, Object> removeOp(String viewObjectId) {
        return Map.of("tool", "remove-from-view", "params", Map.of("viewId", "view-a", "viewObjectId", viewObjectId));
    }

    private List<?> named(String name) {
        return business.getElements().stream()
                .filter(e -> name.equals(((com.archimatetool.model.INameable) e).getName())).toList();
    }

    private String createQueued(String name) {
        return ((ElementDto) accessor.createElement(SESSION, "BusinessActor", name, null, null, null, null)
                .entity()).id();
    }

    private MutationException endBatchThrows() {
        try {
            dispatcher.endBatch(SESSION, true);
        } catch (MutationException e) {
            return e;
        }
        fail("the commit must fail");
        return null;
    }

    private static List<Throwable> causes(Throwable e) {
        List<Throwable> chain = new java.util.ArrayList<>();
        for (Throwable t = e; t != null && !chain.contains(t); t = t.getCause()) {
            chain.add(t);
        }
        return chain;
    }

    private void runBeforeDispatch() {
        Runnable run = beforeDispatch;
        beforeDispatch = null;
        if (run != null) {
            run.run();
        }
    }

    private Map<?, ?> endBatchError() throws Exception {
        McpSchema.CallToolResult result = call("end-batch", Map.of());
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue("the commit must fail: " + text, Boolean.TRUE.equals(result.isError()));
        return (Map<?, ?>) parse(text).get("error");
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
