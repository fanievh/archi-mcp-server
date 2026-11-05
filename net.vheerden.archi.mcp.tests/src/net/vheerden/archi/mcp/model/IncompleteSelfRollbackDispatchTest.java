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
import java.util.concurrent.atomic.AtomicInteger;

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
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.util.ArchimateModelUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.handlers.HandlerRegistrar;
import net.vheerden.archi.mcp.handlers.SearchHandler;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.ElementTypeChangeRequest;
import net.vheerden.archi.mcp.response.dto.MergeClusterRequest;
import net.vheerden.archi.mcp.response.dto.MergeElementsResultDto;
import net.vheerden.archi.mcp.response.dto.RelationshipTypeChangeRequest;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * A merge or a type change (of an element or a relationship) that fails part-way and cannot put back all of its own writes fails the
 * dispatch it ran in: no undo entry is left, and a batch commit puts back every other queued operation
 * and ends the batch. One that puts back all of its writes is still a skip.
 *
 * <p>The fault is a listener that throws on a write and again on the command's own undo of it, installed
 * after the prepare and before the dispatch. The dispatcher runs the production stack-entry steps on a
 * real {@link CommandStack}, with Archi's notifying compound rebuilt as a plain one over the same
 * instances, so no {@code Display} is needed.</p>
 */
public class IncompleteSelfRollbackDispatchTest {

    private static final String SESSION = "default";
    private static final String COULD_NOT_UNDO = "merge-elements stopped and could not undo all of its own changes";
    private static final String UNDID = "merge-elements stopped and undid its own changes: listener";
    private static final String BATCH_ENDED = "Every other operation in the batch was put back, and the batch has "
            + "ended.";
    private static final String LEFT = "What it had written could not all be put back, so part of it may still be "
            + "in the model, and Undo will not remove it; it does not mark the model as changed either. Check the "
            + "model in Archi before going on.";

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private final MergeElementsCommandTest.StubEditorModelManager models =
            new MergeElementsCommandTest.StubEditorModelManager();
    private IArchimateModel model;
    private IFolder business;
    private IArchimateDiagramModel viewA;
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
        model.setId("model-incomplete-rollback");
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

        viewA = view("view-a");
        IArchimateElement t = actor("t");
        IArchimateElement s = actor("s");
        place(viewA, s, "box-1");
        place(viewA, s, "box-2");
        place(viewA, t, "box-t");
    }

    @After
    public void tearDown() {
        accessor.dispose();
    }

    // ---- a batch commit -------------------------------------------------------------------------

    @Test
    public void shouldFailTheCommit_putBackTheRest_andEndTheBatch_whenAQueuedMergeCouldNotUndoItsOwnWrites()
            throws Exception {
        dispatcher.beginBatch(SESSION, "create then merge");
        String made = ((ElementDto) accessor.createElement(SESSION, "BusinessActor", "Made", null, null, null, null)
                .entity()).id();
        String description = queueMerge("t", "s");
        faultOnViewA(true);
        String version = accessor.getModelVersion();

        Map<?, ?> error = endBatchError();

        assertEquals(ErrorCode.MUTATION_FAILED.name(), error.get("code"));
        String message = (String) error.get("message");
        assertTrue(message, message.contains(description + " — " + COULD_NOT_UNDO + ": listener; undoing then "
                + "failed with: listener."));
        assertTrue(message, message.endsWith(BATCH_ENDED + " " + LEFT));
        assertEquals("The batch has ended and its queue was discarded. Nothing it queued was applied, except what "
                + "the message says may still be in the model. Check it with get-model-info and get-element before "
                + "going on, and use begin-batch to start a new batch.", error.get("suggestedCorrection"));
        assertNotEquals("the model may have changed: a new version", version, accessor.getModelVersion());
        assertNull("the create was put back", ArchimateModelUtils.getObjectByID(model, made));
        assertSame("the undo entry is still the earlier one", earlier, stack.getUndoCommand());
        assertEquals(1, stack.getCommands().length);
        assertFalse("the model is not marked changed", stack.isDirty());
        assertEquals("the batch has ended", OperationalMode.GUI_ATTACHED, dispatcher.getMode(SESSION));

        invoke("begin-batch", Map.of("modelId", model.getId()));
        accessor.createElement(SESSION, "BusinessActor", "Again", null, null, null, null);
        Map<?, ?> result = (Map<?, ?>) invoke("end-batch", Map.of()).get("result");
        assertEquals("a new batch on the same session commits: " + result, 1, result.get("operationCount"));
        assertEquals(2, stack.getCommands().length);
    }

    @Test
    public void shouldPutBackAMergeQueuedAfterTheOneThatCouldNotUndoItsOwnWrites() throws Exception {
        IArchimateDiagramModel viewB = view("view-b");
        IArchimateElement u = component("u");
        IArchimateElement v = component("v");
        IArchimateElement w = component("w");
        IArchimateRelationship vw = association("v-w", v, w);
        place(viewB, u, "box-u");
        place(viewB, v, "box-v");
        dispatcher.beginBatch(SESSION, "two merges");
        queueMerge("t", "s");
        queueMerge("u", "v");
        faultOnViewA(true);

        endBatchError();

        assertSame("u is still there", u, ArchimateModelUtils.getObjectByID(model, "u"));
        assertSame("v was put back", v, ArchimateModelUtils.getObjectByID(model, "v"));
        assertSame("its relationship is on it again", v, vw.getSource());
        assertSame(earlier, stack.getUndoCommand());
        assertEquals(OperationalMode.GUI_ATTACHED, dispatcher.getMode(SESSION));
    }

    @Test
    public void shouldCommitTheRest_andReportASkip_whenAQueuedMergeUndidAllOfItsOwnWrites() throws Exception {
        dispatcher.beginBatch(SESSION, "create then merge");
        String made = ((ElementDto) accessor.createElement(SESSION, "BusinessActor", "Made", null, null, null, null)
                .entity()).id();
        String description = queueMerge("t", "s");
        faultOnViewA(false);

        Map<String, Object> response = invoke("end-batch", Map.of());

        Map<?, ?> result = (Map<?, ?>) response.get("result");
        assertEquals(List.of(description + " — " + UNDID), result.get("skippedOperations"));
        assertNotNull("the create ran", ArchimateModelUtils.getObjectByID(model, made));
        assertEquals("one new entry", 2, stack.getCommands().length);
        assertTrue(stack.isDirty());
    }

    @Test
    public void shouldKeepTheBatchOpen_andSayRetry_whenTheCommitFailsBeforeAnythingRan() throws Exception {
        dispatcher.beginBatch(SESSION, "create then merge");
        accessor.createElement(SESSION, "BusinessActor", "Made", null, null, null, null);
        beforeDispatch = () -> {
            throw new net.vheerden.archi.mcp.model.exceptions.MutationException("CommandStack not available");
        };

        Map<?, ?> error = endBatchError();

        assertEquals("Batch commit failed: CommandStack not available", error.get("message"));
        assertEquals("The batch is still active. Use end-batch to retry or end-batch with rollback=true to discard.",
                error.get("suggestedCorrection"));
        assertEquals(OperationalMode.BATCH, dispatcher.getMode(SESSION));
    }

    // ---- an immediate call ----------------------------------------------------------------------

    @Test
    public void shouldRefuseAnImmediateMerge_withoutSayingNothingChanged_whenItCouldNotUndoItsOwnWrites() {
        beforeDispatch = () -> faultOnViewA(true);
        String version = accessor.getModelVersion();

        ModelAccessException e = refusal(() -> accessor.mergeElements(SESSION, List.of(cluster("t", "s")), null,
                null, false, false));

        assertEquals(ErrorCode.MUTATION_FAILED, e.getErrorCode());
        assertTrue(e.getMessage(), e.getMessage().startsWith(COULD_NOT_UNDO + ": listener"));
        assertTrue(e.getSuggestedCorrection(), e.getSuggestedCorrection().contains("may still be in the model"));
        assertFalse(e.getSuggestedCorrection(), e.getSuggestedCorrection().contains("Nothing was changed"));
        assertSame("the undo entry is still the earlier one", earlier, stack.getUndoCommand());
        assertEquals(1, stack.getCommands().length);
        assertNotEquals("the model may have changed: a new version", version, accessor.getModelVersion());
    }

    @Test
    public void shouldStillSayNothingChanged_whenAnImmediateMergeUndidAllOfItsOwnWrites() {
        beforeDispatch = () -> faultOnViewA(false);
        String version = accessor.getModelVersion();

        ModelAccessException e = refusal(() -> accessor.mergeElements(SESSION, List.of(cluster("t", "s")), null,
                null, false, false));

        assertEquals(UNDID, e.getMessage());
        assertTrue(e.getSuggestedCorrection(), e.getSuggestedCorrection().startsWith("Nothing was changed."));
        assertSame(earlier, stack.getUndoCommand());
        assertEquals(version, accessor.getModelVersion());
    }

    @Test
    public void shouldRefuseAnImmediateTypeChange_withoutSayingNothingChanged_whenItCouldNotUndoItsOwnWrites() {
        IArchimateElement a = actor("a");
        association("a-t", a, (IArchimateElement) ArchimateModelUtils.getObjectByID(model, "t"));
        place(viewA, a, "box-a");
        AtomicInteger bumps = new AtomicInteger();
        ChangeElementTypeOperations operations = new ChangeElementTypeOperations(
                sessionId -> dispatcher.admit(sessionId, "change-element-type", model), () -> dispatcher,
                bumps::incrementAndGet, (source, target, relationship) -> true, type -> 0);
        beforeDispatch = () -> business.eAdapters().add(new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                if (n.getOldValue() == a && n.getEventType() == Notification.REMOVE
                        || n.getNewValue() == a && n.getEventType() == Notification.ADD) {
                    throw new IllegalStateException("listener");
                }
            }
        });

        ModelAccessException e = refusal(() -> operations.changeElementType(SESSION,
                List.of(new ElementTypeChangeRequest("a", "BusinessRole")), null));

        assertEquals(ErrorCode.MUTATION_FAILED, e.getErrorCode());
        assertTrue(e.getMessage(), e.getMessage().startsWith(
                "change-element-type stopped and could not undo all of its own changes: listener"));
        assertTrue(e.getSuggestedCorrection(), e.getSuggestedCorrection().contains("may still be in the model"));
        assertFalse(e.getSuggestedCorrection(), e.getSuggestedCorrection().contains("Nothing was changed"));
        assertSame("the undo entry is still the earlier one", earlier, stack.getUndoCommand());
        assertEquals(1, stack.getCommands().length);
        assertEquals("the model may have changed: a new version", 1, bumps.get());
    }

    @Test
    public void shouldStillSayNothingChanged_whenAnImmediateTypeChangeUndidAllOfItsOwnWrites() {
        IArchimateElement a = actor("a");
        AtomicInteger bumps = new AtomicInteger();
        ChangeElementTypeOperations operations = new ChangeElementTypeOperations(
                sessionId -> dispatcher.admit(sessionId, "change-element-type", model), () -> dispatcher,
                bumps::incrementAndGet, (source, target, relationship) -> true, type -> 0);
        beforeDispatch = () -> business.eAdapters().add(new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                if (n.getEventType() == Notification.REMOVE && n.getOldValue() == a) {
                    throw new IllegalStateException("listener");
                }
            }
        });

        ModelAccessException e = refusal(() -> operations.changeElementType(SESSION,
                List.of(new ElementTypeChangeRequest("a", "BusinessRole")), null));

        assertEquals("change-element-type stopped and undid its own changes: listener", e.getMessage());
        assertEquals("Nothing was changed. Re-read the elements with get-element and call change-element-type "
                + "again; if it stops the same way, the change cannot be made as asked.", e.getSuggestedCorrection());
        assertSame(earlier, stack.getUndoCommand());
        assertEquals("a decline that wrote nothing is no new version", 0, bumps.get());
    }

    @Test
    public void shouldRefuseAnImmediateRelationshipTypeChange_withoutSayingNothingChanged_whenItCouldNotUndoItsOwnWrites() {
        IArchimateRelationship old = association("a-t", actor("a"),
                (IArchimateElement) ArchimateModelUtils.getObjectByID(model, "t"));
        AtomicInteger bumps = new AtomicInteger();
        ChangeRelationshipTypeOperations operations = relationshipOperations(bumps);
        beforeDispatch = () -> model.getFolder(FolderType.RELATIONS).eAdapters().add(new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                if (n.getOldValue() == old && n.getEventType() == Notification.REMOVE
                        || n.getNewValue() == old && n.getEventType() == Notification.ADD) {
                    throw new IllegalStateException("listener");
                }
            }
        });

        ModelAccessException e = refusal(() -> operations.changeRelationshipType(SESSION,
                List.of(new RelationshipTypeChangeRequest("a-t", "TriggeringRelationship"))));

        assertEquals(ErrorCode.MUTATION_FAILED, e.getErrorCode());
        assertTrue(e.getMessage(), e.getMessage().startsWith(
                "change-relationship-type stopped and could not undo all of its own changes: listener"));
        assertTrue(e.getSuggestedCorrection(), e.getSuggestedCorrection().contains("may still be in the model"));
        assertFalse(e.getSuggestedCorrection(), e.getSuggestedCorrection().contains("Nothing was changed"));
        assertSame("the undo entry is still the earlier one", earlier, stack.getUndoCommand());
        assertEquals(1, stack.getCommands().length);
        assertEquals("the model may have changed: a new version", 1, bumps.get());
    }

    @Test
    public void shouldStillSayNothingChanged_whenAnImmediateRelationshipTypeChangeUndidAllOfItsOwnWrites() {
        IArchimateRelationship old = association("a-t", actor("a"),
                (IArchimateElement) ArchimateModelUtils.getObjectByID(model, "t"));
        AtomicInteger bumps = new AtomicInteger();
        ChangeRelationshipTypeOperations operations = relationshipOperations(bumps);
        beforeDispatch = () -> model.getFolder(FolderType.RELATIONS).eAdapters().add(new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                if (n.getEventType() == Notification.REMOVE && n.getOldValue() == old) {
                    throw new IllegalStateException("listener");
                }
            }
        });

        ModelAccessException e = refusal(() -> operations.changeRelationshipType(SESSION,
                List.of(new RelationshipTypeChangeRequest("a-t", "TriggeringRelationship"))));

        assertEquals("change-relationship-type stopped and undid its own changes: listener", e.getMessage());
        assertEquals("Nothing was changed. Re-read the relationships with get-relationships and call "
                + "change-relationship-type again; if it stops the same way, the change cannot be made as asked.",
                e.getSuggestedCorrection());
        assertSame(earlier, stack.getUndoCommand());
        assertSame("the old relationship is back", old,
                ArchimateModelUtils.getObjectByID(model, "a-t"));
        assertEquals("a decline that wrote nothing is no new version", 0, bumps.get());
    }

    private ChangeRelationshipTypeOperations relationshipOperations(AtomicInteger bumps) {
        return new ChangeRelationshipTypeOperations(
                sessionId -> dispatcher.admit(sessionId, "change-relationship-type", model), () -> dispatcher,
                bumps::incrementAndGet, (source, target, relationship) -> true, (source, target, type) -> true);
    }

    // ---- helpers --------------------------------------------------------------------------------

    private void runBeforeDispatch() {
        Runnable run = beforeDispatch;
        beforeDispatch = null;
        if (run != null) {
            run.run();
        }
    }

    /** Queues a merge of {@code source} into {@code target}, returning the description the batch keeps. */
    private String queueMerge(String target, String source) {
        MutationResult<MergeElementsResultDto> queued = accessor.mergeElements(SESSION,
                List.of(cluster(target, source)), null, null, false, false);
        assertNotNull("fixture check: queued", queued.batchSequenceNumber());
        return MergeElementsOperations.describe(queued.entity());
    }

    /**
     * Throws on the second box taken out of view-a and, when {@code onReAdd}, on every later re-add of that
     * box, so the merge's own undo of it fails too.
     */
    private void faultOnViewA(boolean onReAdd) {
        viewA.eAdapters().add(new AdapterImpl() {
            private int removes;
            private Object held;

            @Override
            public void notifyChanged(Notification n) {
                if (n.getEventType() == Notification.REMOVE && ++removes == 2) {
                    held = n.getOldValue();
                    throw new IllegalStateException("listener");
                }
                if (onReAdd && n.getEventType() == Notification.ADD && held != null && n.getNewValue() == held) {
                    throw new IllegalStateException("listener");
                }
            }
        });
    }

    private static ModelAccessException refusal(Runnable call) {
        try {
            call.run();
        } catch (ModelAccessException e) {
            return e;
        }
        fail("the call must be refused");
        return null;
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

    private static MergeClusterRequest cluster(String targetId, String... sourceIds) {
        return new MergeClusterRequest(targetId, List.of(sourceIds));
    }

    private IArchimateDiagramModel view(String id) {
        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId(id);
        view.setName(id);
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        return view;
    }

    private IArchimateElement actor(String id) {
        IArchimateElement element = factory.createBusinessActor();
        element.setId(id);
        element.setName(id);
        business.getElements().add(element);
        return element;
    }

    private IArchimateElement component(String id) {
        IArchimateElement element = factory.createApplicationComponent();
        element.setId(id);
        element.setName(id);
        model.getFolder(FolderType.APPLICATION).getElements().add(element);
        return element;
    }

    private IArchimateRelationship association(String id, IArchimateElement source, IArchimateElement target) {
        IArchimateRelationship relationship = factory.createAssociationRelationship();
        relationship.setId(id);
        relationship.connect(source, target);
        model.getFolder(FolderType.RELATIONS).getElements().add(relationship);
        return relationship;
    }

    private IDiagramModelArchimateObject place(IArchimateDiagramModel view, IArchimateElement element, String id) {
        IDiagramModelArchimateObject box = factory.createDiagramModelArchimateObject();
        box.setId(id);
        box.setArchimateElement(element);
        box.setBounds(0, 0, 120, 55);
        view.getChildren().add(box);
        return box;
    }
}
