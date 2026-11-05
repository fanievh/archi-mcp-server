package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.ecore.util.EContentAdapter;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IIdentifier;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import net.vheerden.archi.mcp.handlers.HandlerRegistrar;
import net.vheerden.archi.mcp.handlers.SearchHandler;
import net.vheerden.archi.mcp.model.MutationDispatcher.UndoRedoState;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Pins per-call model addressing end to end, through the tools as production wires them: a call names
 * the model it acts on with {@code modelId}, a change that names none while several are open is
 * refused, and every response names the model it acted on and how that was decided.
 *
 * <h2>Fixture</h2>
 *
 * <p>Two real models, each with its own {@code CommandStack}, two sessions, the real registry,
 * handlers, accessor and dispatcher. Only the UI-thread hop is replaced: dispatch resolves its stack
 * through the dispatcher's own guarded lookup, exactly as production does on the UI thread.</p>
 *
 * <p><strong>Assert the stack, not the mutation.</strong> A queued command's write lands in the model
 * it was prepared against whatever happens; what addressing changes is which stack records it. So
 * every write here is checked on both stacks.</p>
 */
public class ModelAddressingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private StubEditorModelManager models;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private CommandRegistry registry;
    private SessionManager sessions;
    private IArchimateModel modelA;
    private IArchimateModel modelB;
    private CommandStack stackA;
    private CommandStack stackB;
    private boolean approvalMode;
    private final McpSyncServerExchange one = exchange("session-one");
    private final McpSyncServerExchange two = exchange("session-two");

    @Before
    public void setUp() throws Exception {
        modelA = newModel("model-a", "Model A");
        modelB = newModel("model-b", "Model B");
        stackA = stackOf(modelA);
        stackB = stackOf(modelB);
        models = new StubEditorModelManager();
        forwardContentEvents(modelA);
        forwardContentEvents(modelB);
        models.getModels().add(modelA);
        build();
    }

    private void build() throws Exception {
        Field active = ArchiModelAccessorImpl.class.getDeclaredField("activeModel");
        active.setAccessible(true);
        dispatcher = new MutationDispatcher(() -> activeOf(active)) {
            @Override
            public void dispatchImmediate(Command command) {
                dispatchAsProduction(command);
            }
            @Override
            protected void dispatchCommand(Command command) {
                dispatchAsProduction(command);
            }
            @Override
            public UndoRedoState undo(int steps) {
                return stackOperation(steps, true).get();
            }
            @Override
            public UndoRedoState redo(int steps) {
                return stackOperation(steps, false).get();
            }
        };
        approvalMode = false;
        dispatcher.setApprovalModeProvider(() -> approvalMode);
        accessor = new ArchiModelAccessorImpl(models, dispatcher);
        registry = new CommandRegistry();
        sessions = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        accessor.addModelChangeListener(sessions);
        HandlerRegistrar.registerAll(accessor, new ResponseFormatter(), registry, sessions);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ---- fixture helpers ----------------------------------------------------------------------

    private static IArchimateModel newModel(String id, String name) {
        IArchimateModel model = IArchimateFactory.eINSTANCE.createArchimateModel();
        model.setId(id);
        model.setName(name);
        model.setDefaults();
        model.setAdapter(CommandStack.class, new CommandStack());
        return model;
    }

    /** As Archi's model manager does: every content change in the model is announced as an ECORE event. */
    private void forwardContentEvents(IArchimateModel model) {
        model.eAdapters().add(new EContentAdapter() {
            @Override
            public void notifyChanged(Notification notification) {
                super.notifyChanged(notification);
                if (!notification.isTouch()) {
                    models.firePropertyChange(IEditorModelManager.PROPERTY_ECORE_EVENT, null, notification);
                }
            }
        });
    }

    private static CommandStack stackOf(IArchimateModel model) {
        return (CommandStack) model.getAdapter(CommandStack.class);
    }

    private static McpSyncServerExchange exchange(String sessionId) {
        return new McpSyncServerExchange(null) {
            @Override
            public String sessionId() {
                return sessionId;
            }
        };
    }

    private IArchimateModel activeOf(Field active) {
        try {
            return accessor == null ? null : (IArchimateModel) active.get(accessor);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private IArchimateModel guiActive() throws Exception {
        Field active = ArchiModelAccessorImpl.class.getDeclaredField("activeModel");
        active.setAccessible(true);
        return (IArchimateModel) active.get(accessor);
    }

    /** What production does on the UI thread: take the stack through the dispatcher's guarded lookup. */
    private void dispatchAsProduction(Command command) {
        IArchimateModel active;
        try {
            active = guiActive();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        IArchimateModel resolved = dispatcher.selection().peekModelForCall(active);
        CommandStack stack = dispatcher.guardedStack(resolved, dispatcher.currentDispatchGuard());
        stack.execute(dispatcher.agentEntry(toPlainCompound(command)));
    }

    private static Command toPlainCompound(Command command) {
        if (command instanceof CompoundCommand compound) {
            CompoundCommand plain = new CompoundCommand(compound.getLabel());
            for (Object child : compound.getCommands()) {
                plain.add(toPlainCompound((Command) child));
            }
            return plain;
        }
        return command;
    }

    /** The human opens a model in Archi: it joins the open set and becomes active. */
    private void open(IArchimateModel model) {
        if (!models.getModels().contains(model)) {
            models.getModels().add(model);
        }
        models.firePropertyChange(IEditorModelManager.PROPERTY_MODEL_OPENED, null, model);
    }

    private void create(IArchimateModel model) {
        if (!models.getModels().contains(model)) {
            models.getModels().add(model);
        }
        models.firePropertyChange(IEditorModelManager.PROPERTY_MODEL_CREATED, null, model);
    }

    /** The human closes a model, announced as Archi announces it: (null, model). */
    private void close(IArchimateModel model) {
        models.getModels().remove(model);
        models.firePropertyChange(IEditorModelManager.PROPERTY_MODEL_REMOVED, null, model);
    }

    /** Both models open, A active in Archi. */
    private void bothOpenWithAActive() {
        open(modelB);
        open(modelA);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(McpSyncServerExchange exchange, String tool, Map<String, Object> args) {
        McpSchema.CallToolResult result = registry.getToolSpecifications().stream()
                .filter(spec -> tool.equals(spec.tool().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not registered: " + tool))
                .callHandler().apply(exchange, new McpSchema.CallToolRequest(tool, args));
        try {
            return JSON.readValue(((McpSchema.TextContent) result.content().get(0)).text(), Map.class);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static Map<String, Object> args(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private Map<String, Object> createActor(McpSyncServerExchange exchange, String name, String modelId) {
        return modelId == null
                ? call(exchange, "create-element", args("type", "BusinessActor", "name", name))
                : call(exchange, "create-element", args("type", "BusinessActor", "name", name, "modelId", modelId));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> meta(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("_meta");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> modelMeta(Map<String, Object> envelope) {
        return (Map<String, Object>) meta(envelope).get("model");
    }

    @SuppressWarnings("unchecked")
    private static String errorCode(Map<String, Object> envelope) {
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        return error == null ? null : (String) error.get("code");
    }

    @SuppressWarnings("unchecked")
    private static String errorText(Map<String, Object> envelope) {
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        return error == null ? "" : error.get("message") + " " + error.get("details") + " "
                + error.get("suggestedCorrection");
    }

    private static void assertModel(Map<String, Object> envelope, String id, String name, String resolution) {
        Map<String, Object> model = modelMeta(envelope);
        assertNotNull("the response names the model it acted on: " + envelope, model);
        assertEquals(id, model.get("id"));
        assertEquals(name, model.get("name"));
        assertEquals(resolution, model.get("resolution"));
    }

    private static int countActors(IArchimateModel model) {
        return model.getFolder(FolderType.BUSINESS).getElements().size();
    }

    private static boolean holds(IArchimateModel model, String id) {
        for (Object each : model.getFolder(FolderType.BUSINESS).getElements()) {
            if (each instanceof IIdentifier identified && id.equals(identified.getId())) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static String createdId(Map<String, Object> envelope) {
        Map<String, Object> result = (Map<String, Object>) envelope.get("result");
        Object id = result.get("id");
        if (id == null && result.get("preview") instanceof Map<?, ?> preview) {
            id = preview.get("id");
        }
        return (String) id;
    }

    // ---- sole model: the single-model user meets nothing new ----------------------------------

    @Test
    public void shouldResolveTheOnlyModel_andSaySo_whenOneModelIsOpenAndTheCallNamesNone() {
        Map<String, Object> created = createActor(one, "Solo", null);

        assertNull(String.valueOf(created), errorCode(created));
        assertEquals(1, stackA.getCommands().length);
        assertModel(created, "model-a", "Model A", ModelSelection.SOLE_MODEL);
    }

    // ---- addressed ----------------------------------------------------------------------------

    @Test
    public void shouldWriteToTheAddressedModelsStack_whileAnotherModelIsActiveInArchi() throws Exception {
        bothOpenWithAActive();
        assertSame(modelA, guiActive());

        Map<String, Object> created = createActor(one, "Into B", "model-b");

        assertNull(String.valueOf(created), errorCode(created));
        String id = createdId(created);
        assertTrue("the element lives in B", holds(modelB, id));
        assertEquals("B's stack records it", 1, stackB.getCommands().length);
        assertEquals("A is untouched", 0, stackA.getCommands().length);
        assertModel(created, "model-b", "Model B", ModelSelection.ADDRESSED);
    }

    @Test
    public void shouldReadOneModelAndWriteAnother_inConsecutiveCalls() {
        bothOpenWithAActive();
        String inA = createdId(createActor(one, "In A", "model-a"));

        Map<String, Object> read = call(one, "get-element", args("id", inA, "modelId", "model-a"));
        Map<String, Object> written = createActor(one, "In B", "model-b");

        assertNull(String.valueOf(read), errorCode(read));
        assertModel(read, "model-a", "Model A", ModelSelection.ADDRESSED);
        assertModel(written, "model-b", "Model B", ModelSelection.ADDRESSED);
        assertTrue(holds(modelA, inA));
        assertTrue(holds(modelB, createdId(written)));
        Map<String, Object> wrongModel = call(one, "get-element", args("id", inA, "modelId", "model-b"));
        assertNotNull("an id from A is not found when B is addressed", errorCode(wrongModel));
    }

    // ---- ambiguity ----------------------------------------------------------------------------

    @Test
    public void shouldRefuseAChangeListingTheCandidates_whenTwoModelsAreOpenAndTheCallNamesNone() {
        bothOpenWithAActive();

        Map<String, Object> refused = createActor(one, "Coin flip", null);

        assertEquals("MODEL_AMBIGUOUS", errorCode(refused));
        String text = errorText(refused);
        assertTrue(text, text.contains("model-a") && text.contains("Model A"));
        assertTrue(text, text.contains("model-b") && text.contains("Model B"));
        assertEquals("nothing was created in A", 0, countActors(modelA));
        assertEquals("nothing was created in B", 0, countActors(modelB));
        assertNull("a refused change names no model as acted on", modelMeta(refused));
    }

    @Test
    public void shouldFollowTheActiveModelForARead_labelledSessionDefault_whenTwoAreOpen() {
        bothOpenWithAActive();

        Map<String, Object> info = call(one, "get-model-info", args());

        assertNull(String.valueOf(info), errorCode(info));
        assertModel(info, "model-a", "Model A", ModelSelection.SESSION_DEFAULT);
    }

    // ---- address refusals ---------------------------------------------------------------------

    @Test
    public void shouldRefuseNamingIt_whenTheAddressedModelIsNotOpen() {
        bothOpenWithAActive();

        Map<String, Object> refused = createActor(one, "Nowhere", "model-zzz");

        assertEquals("MODEL_NOT_OPEN", errorCode(refused));
        assertTrue(errorText(refused).contains("model-zzz"));
        assertEquals(0, countActors(modelA) + countActors(modelB));
    }

    @Test
    public void shouldRefuseListingTheFiles_whenTheAddressedIdIsOpenTwice_evenIfTheCopyWasNeverActive()
            throws Exception {
        // Two copies of one file, open before the server starts; the second is never made active.
        tearDown();
        IArchimateModel copy = newModel("model-a", "Model A");
        modelA.setFile(new File("/work/original.archimate"));
        copy.setFile(new File("/work/copy.archimate"));
        models = new StubEditorModelManager();
        models.getModels().add(modelA);
        models.getModels().add(copy);
        build();

        Map<String, Object> refused = createActor(one, "Which", "model-a");

        assertEquals("MODEL_COPIES_AMBIGUOUS", errorCode(refused));
        String text = errorText(refused);
        assertTrue(text, text.contains("/work/original.archimate") && text.contains("/work/copy.archimate"));
        assertEquals(0, countActors(modelA) + countActors(copy));
    }

    @Test
    public void shouldFailTheNextAddressedCallNamingIt_whenTheModelIsClosed_leavingTheOtherUsable() {
        bothOpenWithAActive();
        createActor(one, "Before close", "model-b");

        close(modelB);
        Map<String, Object> refused = createActor(one, "After close", "model-b");
        Map<String, Object> intoA = createActor(one, "Still fine", "model-a");

        assertEquals("MODEL_NOT_OPEN", errorCode(refused));
        assertTrue(errorText(refused).contains("model-b"));
        assertNull(String.valueOf(intoA), errorCode(intoA));
        assertModel(intoA, "model-a", "Model A", ModelSelection.ADDRESSED);
    }

    // ---- the session default: GUI events never overwrite a choice -------------------------------

    @Test
    public void shouldKeepTheSessionsChosenModel_whenTheHumanOpensOrCreatesAnother() {
        bothOpenWithAActive();
        dispatcher.selection().choose("session-one", "model-b");
        IArchimateModel modelC = newModel("model-c", "Model C");
        IArchimateModel modelD = newModel("model-d", "Model D");

        open(modelC);
        Map<String, Object> afterOpen = createActor(one, "After open", null);
        create(modelD);
        Map<String, Object> afterCreate = createActor(one, "After create", null);

        assertModel(afterOpen, "model-b", "Model B", ModelSelection.SESSION_DEFAULT);
        assertModel(afterCreate, "model-b", "Model B", ModelSelection.SESSION_DEFAULT);
        assertEquals(2, stackB.getCommands().length);
        assertEquals(0, stackOf(modelC).getCommands().length + stackOf(modelD).getCommands().length);
    }

    @Test
    public void shouldNotRepointAChosenSession_whenItsModelCloses_andResolveTheReopenedFile() {
        bothOpenWithAActive();
        dispatcher.selection().choose("session-one", "model-b");

        close(modelB);
        Map<String, Object> refused = createActor(one, "Gone", null);
        IArchimateModel reopened = newModel("model-b", "Model B");
        open(reopened);
        open(modelA);   // the human makes A active again
        Map<String, Object> resolved = createActor(one, "Back", null);

        assertEquals("MODEL_NOT_OPEN", errorCode(refused));
        assertTrue(errorText(refused).contains("model-b"));
        assertEquals("the close never re-pointed the session at A", 0, stackA.getCommands().length);
        assertModel(resolved, "model-b", "Model B", ModelSelection.SESSION_DEFAULT);
        assertEquals(1, stackOf(reopened).getCommands().length);
    }

    // ---- batches ------------------------------------------------------------------------------

    @Test
    public void shouldRefuseBeginBatchListingTheModels_whenItNamesNone() {
        bothOpenWithAActive();

        Map<String, Object> refused = call(one, "begin-batch", args("description", "work"));

        assertEquals("INVALID_PARAMETER", errorCode(refused));
        assertTrue(errorText(refused).contains("model-a") && errorText(refused).contains("model-b"));
        assertEquals("no batch was opened", OperationalMode.GUI_ATTACHED, dispatcher.getMode("session-one"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReportTheBoundModel_beforeAnythingIsQueued() {
        bothOpenWithAActive();

        call(one, "begin-batch", args("modelId", "model-b"));
        Map<String, Object> status = call(one, "get-batch-status", args());

        Map<String, Object> result = (Map<String, Object>) status.get("result");
        assertEquals("model-b", result.get("boundModelId"));
        assertEquals("Model B", result.get("boundModelName"));
    }

    @Test
    public void shouldQueueIntoTheBatchsModel_andCommitOntoItsStack_whileAnotherModelIsActive() {
        bothOpenWithAActive();
        call(one, "begin-batch", args("modelId", "model-b"));

        Map<String, Object> omitted = createActor(one, "Omitted", null);
        Map<String, Object> same = createActor(one, "Same", "model-b");
        Map<String, Object> other = createActor(one, "Other", "model-a");
        Map<String, Object> committed = call(one, "end-batch", args());

        assertNull(String.valueOf(omitted), errorCode(omitted));
        assertModel(omitted, "model-b", "Model B", ModelSelection.SESSION_DEFAULT);
        assertNull(String.valueOf(same), errorCode(same));
        assertModel(same, "model-b", "Model B", ModelSelection.ADDRESSED);
        assertEquals("BATCH_MODEL_MISMATCH", errorCode(other));
        String text = errorText(other);
        assertTrue(text, text.contains("model-a") && text.contains("model-b"));
        assertTrue("the refusal names both remedies", text.contains("end-batch") && text.contains("modelId"));
        assertNull(String.valueOf(committed), errorCode(committed));
        assertEquals("the commit is one entry on B's stack", 1, stackB.getCommands().length);
        assertEquals(0, stackA.getCommands().length);
        assertEquals(2, countActors(modelB));
        Object entry = stackB.getCommands()[0];
        assertTrue(entry instanceof AgentAuthoredCompoundCommand);
        assertOwnedBySession((AgentAuthoredCompoundCommand) entry, "session-one");
    }

    // ---- approvals ----------------------------------------------------------------------------

    @Test
    public void shouldApproveAnAddressedProposalOntoItsOwnModelsStack_whileAnotherModelIsActive()
            throws Exception {
        bothOpenWithAActive();
        approvalMode = true;

        Map<String, Object> proposed = createActor(one, "Proposed into B", "model-b");
        assertModel(proposed, "model-b", "Model B", ModelSelection.ADDRESSED);
        String proposalId = dispatcher.getPendingProposalDtos("session-one").get(0).proposalId();

        dispatcher.approveProposal("session-one", proposalId);

        assertEquals("the approved change is recorded on B's stack", 1, stackB.getCommands().length);
        assertEquals(0, stackA.getCommands().length);
        assertEquals(1, countActors(modelB));
        assertOwnedBySession((AgentAuthoredCompoundCommand) stackB.getCommands()[0], "session-one");
    }

    /** A batch-commit or approved entry carries a fresh owner of its own, holding the session it was for. */
    private static void assertOwnedBySession(AgentAuthoredCompoundCommand entry, String sessionId) {
        assertTrue("the entry carries an owner: " + entry.getOwner(),
                entry.getOwner() instanceof MutationDispatcher.CallStamp);
        assertEquals(sessionId, ((MutationDispatcher.CallStamp) entry.getOwner()).sessionId);
    }

    @Test
    public void shouldRollBackTheBatch_evenWhenEndBatchCarriesTheClosedModelsId() {
        bothOpenWithAActive();
        call(one, "begin-batch", args("modelId", "model-b"));
        createActor(one, "Queued", null);
        close(modelB);

        Map<String, Object> rolledBack = call(one, "end-batch", args("rollback", true, "modelId", "model-b"));

        assertNull("a batch must always be discardable: " + rolledBack, errorCode(rolledBack));
        assertEquals(OperationalMode.GUI_ATTACHED, dispatcher.getMode("session-one"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldListTheModels_evenWhenTheCallCarriesAStaleModelId() {
        bothOpenWithAActive();

        Map<String, Object> listed = call(one, "get-models", args("modelId", "model-zzz"));

        assertNull(String.valueOf(listed), errorCode(listed));
        assertEquals(2, ((List<Object>) listed.get("result")).size());
    }

    @Test
    public void shouldRefuseAModelIdThatIsNotANonBlankString() {
        bothOpenWithAActive();

        Map<String, Object> number = call(one, "create-element",
                args("type", "BusinessActor", "name", "Numbered", "modelId", 42));
        Map<String, Object> blank = call(one, "create-element",
                args("type", "BusinessActor", "name", "Blank", "modelId", "  "));

        assertEquals("INVALID_PARAMETER", errorCode(number));
        assertEquals("INVALID_PARAMETER", errorCode(blank));
        assertEquals(0, countActors(modelA) + countActors(modelB));
    }

    @Test
    public void shouldRefuseToBindABatch_toAnInstanceClosedAfterTheCallResolvedIt() {
        bothOpenWithAActive();
        dispatcher.selection().enter("session-one", "model-b");
        try {
            assertSame(modelB, dispatcher.selection().peekModelForCall(modelA));
            close(modelB);
            try {
                dispatcher.beginBatch("session-one", "late", null);
                org.junit.Assert.fail("binding to a closed instance must be refused");
            } catch (ModelAccessException e) {
                assertEquals(net.vheerden.archi.mcp.response.ErrorCode.BOUND_MODEL_CLOSED, e.getErrorCode());
            }
        } finally {
            dispatcher.selection().exit();
        }
        assertEquals(OperationalMode.GUI_ATTACHED, dispatcher.getMode("session-one"));
    }

    // ---- the catalogue ------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    @Test
    public void shouldListTheOpenModels_markingTheCallersOwn() {
        bothOpenWithAActive();

        Map<String, Object> listed = call(one, "get-models", args());

        List<Map<String, Object>> result = (List<Map<String, Object>>) listed.get("result");
        assertEquals(2, result.size());
        Map<String, Object> first = result.get(0);
        Map<String, Object> second = result.get(1);
        assertEquals(Boolean.TRUE, "model-a".equals(first.get("id")) ? first.get("sessionActive")
                : second.get("sessionActive"));
        assertNull("get-models acts on no single model", modelMeta(listed));
    }

    @Test
    public void shouldDeclareModelIdOnEveryAddressableTool_andRequireItOnBeginBatch() {
        List<String> missing = new ArrayList<>();
        for (var spec : registry.getToolSpecifications()) {
            String name = spec.tool().name();
            Map<String, Object> properties = spec.tool().inputSchema().properties();
            boolean declares = properties != null && properties.containsKey("modelId");
            boolean expected = !List.of("get-guidance", "get-session-filters", "set-session-filter",
                    "get-batch-status", "end-batch", "list-pending-approvals", "get-models",
                    "detect-hub-elements", "export-view", "get-specialization-usage").contains(name);
            if (declares != expected) {
                missing.add(name + (expected ? " (missing)" : " (unexpected)"));
            }
        }
        assertTrue("modelId declaration: " + missing, missing.isEmpty());
        assertTrue(registry.getToolSpecifications().stream()
                .filter(spec -> "begin-batch".equals(spec.tool().name()))
                .findFirst().orElseThrow().tool().inputSchema().required().contains("modelId"));
    }

    // ---- per-model versions -------------------------------------------------------------------

    /** The version an agent reads for {@code modelId}, as a tool call addressing it reads it. */
    private long version(String modelId) {
        dispatcher.selection().enter("probe", modelId);
        try {
            return Long.parseLong(accessor.getModelVersion());
        } finally {
            dispatcher.selection().exit();
        }
    }

    private static Command humanRename(IArchimateModel model, String to) {
        Object element = model.getFolder(FolderType.BUSINESS).getElements().get(0);
        com.archimatetool.model.INameable named = (com.archimatetool.model.INameable) element;
        return new Command("human rename") {
            private String from;
            @Override
            public void execute() {
                from = named.getName();
                named.setName(to);
            }
            @Override
            public void undo() {
                named.setName(from);
            }
        };
    }

    @Test
    public void shouldMoveOnlyTheChangedModelsVersion_whenAToolWritesToIt_inBothDirections() {
        bothOpenWithAActive();
        long a0 = version("model-a");
        long b0 = version("model-b");

        createActor(one, "Into A", "model-a");
        long a1 = version("model-a");
        assertEquals("a write to A does not move B", b0, version("model-b"));
        assertTrue(a1 > a0);

        createActor(one, "Into B", "model-b");
        assertEquals("a write to B does not move A", a1, version("model-a"));
        assertTrue(version("model-b") > b0);
    }

    @Test
    public void shouldMoveOnlyTheEditedModelsVersion_whenTheHumanEditsIt_inBothDirections() {
        bothOpenWithAActive();
        createActor(one, "In A", "model-a");
        createActor(one, "In B", "model-b");
        long a0 = version("model-a");
        long b0 = version("model-b");

        stackA.execute(humanRename(modelA, "Renamed in A"));
        long a1 = version("model-a");
        assertTrue(a1 > a0);
        assertEquals("a human edit in A does not move B", b0, version("model-b"));

        stackB.execute(humanRename(modelB, "Renamed in B"));
        assertEquals("a human edit in B does not move A", a1, version("model-a"));
        assertTrue(version("model-b") > b0);
    }

    // ---- caches keyed by model instance ---------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<String> viewNames(Map<String, Object> envelope) {
        List<String> names = new ArrayList<>();
        Object result = envelope.get("result");
        List<Map<String, Object>> views = result instanceof Map<?, ?> map
                ? (List<Map<String, Object>>) ((Map<String, Object>) map).get("views")
                : (List<Map<String, Object>>) result;
        for (Map<String, Object> view : views) {
            names.add((String) view.get("name"));
        }
        return names;
    }

    @Test
    public void shouldAnswerEachModelsReadFromItsOwnModel_whenOneSessionReadsTwo() {
        bothOpenWithAActive();
        call(one, "create-view", args("name", "View of A", "modelId", "model-a"));
        call(one, "create-view", args("name", "View of B", "modelId", "model-b"));

        Map<String, Object> fromA = call(one, "get-views", args("modelId", "model-a"));
        Map<String, Object> fromB = call(one, "get-views", args("modelId", "model-b"));

        assertTrue(String.valueOf(fromA), viewNames(fromA).contains("View of A"));
        assertTrue(String.valueOf(fromB), viewNames(fromB).contains("View of B"));
        assertFalse("B's read is not served A's cached answer", viewNames(fromB).contains("View of A"));
        assertNull(meta(fromB).get("cacheHit"));
        assertEquals(Boolean.TRUE, meta(call(one, "get-views", args("modelId", "model-a"))).get("cacheHit"));
    }

    @Test
    public void shouldNotServeOneCopysCachedAnswerForAnother_whenTwoSameIdCopiesAreOpen() throws Exception {
        tearDown();
        IArchimateModel copy = newModel("model-a", "Model A");
        models = new StubEditorModelManager();
        models.getModels().add(modelA);
        models.getModels().add(copy);
        build();
        com.archimatetool.model.IArchimateDiagramModel onCopy =
                IArchimateFactory.eINSTANCE.createArchimateDiagramModel();
        onCopy.setName("Only on the copy");
        copy.getFolder(FolderType.DIAGRAMS).getElements().add(onCopy);

        Map<String, Object> first = call(one, "get-views", args());   // follows the active copy: modelA
        open(copy);                                                  // the human switches to the copy
        Map<String, Object> second = call(one, "get-views", args());

        assertFalse(viewNames(first).contains("Only on the copy"));
        assertTrue("the copy's read is its own: " + second, viewNames(second).contains("Only on the copy"));
    }

    // ---- no session reconfigures another --------------------------------------------------------

    @SuppressWarnings("unchecked")
    @Test
    public void shouldKeepSessionOnesFiltersAndCache_whenModelsOpenAreCreatedAndClose() {
        call(one, "set-session-filter", args("type", "BusinessActor"));
        call(one, "get-model-info", args("modelId", "model-a"));
        IArchimateModel modelC = newModel("model-c", "Model C");

        open(modelB);                               // a model opens
        create(modelC);                             // File > New
        close(modelC);                              // a model closes
        call(two, "get-model-info", args("modelId", "model-b"));

        Map<String, Object> filters = (Map<String, Object>) ((Map<String, Object>)
                call(one, "get-session-filters", args()).get("result")).get("activeFilters");
        assertEquals("session one's filter survived", "BusinessActor", filters.get("type"));
        Map<String, Object> again = call(one, "get-model-info", args("modelId", "model-a"));
        assertEquals("session one's cached read of A survived", Boolean.TRUE, meta(again).get("cacheHit"));
    }

    // ---- undo and redo ------------------------------------------------------------------------

    private Map<String, Object> undo(McpSyncServerExchange exchange, String modelId) {
        return modelId == null ? call(exchange, "undo", args()) : call(exchange, "undo", args("modelId", modelId));
    }

    @Test
    public void shouldUndoAsBefore_whenOneSessionWorksOnTheOnlyModel() {
        createActor(one, "Mine", null);

        Map<String, Object> undone = undo(one, null);

        assertNull(String.valueOf(undone), errorCode(undone));
        assertEquals(0, countActors(modelA));
        assertModel(undone, "model-a", "Model A", ModelSelection.SOLE_MODEL);
    }

    @Test
    public void shouldUndoOnTheAddressedModelsStack_whileAnotherModelIsActive() {
        bothOpenWithAActive();
        createActor(one, "In B", "model-b");

        Map<String, Object> undone = undo(one, "model-b");

        assertNull(String.valueOf(undone), errorCode(undone));
        assertEquals(0, countActors(modelB));
        assertTrue(stackB.canRedo());
    }

    @Test
    public void shouldRefuseAnUndoThatNamesNoModel_whenTwoAreOpen() {
        bothOpenWithAActive();
        createActor(one, "In A", "model-a");

        Map<String, Object> refused = undo(one, null);

        assertEquals("MODEL_AMBIGUOUS", errorCode(refused));
        assertEquals(1, countActors(modelA));
    }

    @Test
    public void shouldRefuseUndoAndRedoPoppingNothing_whileAnotherSessionWorksOnTheModel() {
        bothOpenWithAActive();
        createActor(one, "One's change", "model-a");
        call(two, "get-model-info", args("modelId", "model-a"));   // session two is working on A
        int depth = stackA.getCommands().length;

        Map<String, Object> refusedUndo = undo(one, "model-a");
        Map<String, Object> refusedRedo = call(one, "redo", args("modelId", "model-a"));

        assertEquals("MODEL_SHARED", errorCode(refusedUndo));
        assertTrue(errorText(refusedUndo).contains("model-a") && errorText(refusedUndo).contains("shared"));
        assertEquals("MODEL_SHARED", errorCode(refusedRedo));
        assertEquals("nothing was popped", depth, stackA.getCommands().length);
        assertTrue(stackA.canUndo());
        assertEquals(1, countActors(modelA));
    }

    @Test
    public void shouldAllowTheUndoAgain_onceTheOtherSessionHasMovedToAnotherModel() {
        bothOpenWithAActive();
        createActor(one, "One's change", "model-a");
        call(two, "get-model-info", args("modelId", "model-a"));
        assertEquals("MODEL_SHARED", errorCode(undo(one, "model-a")));

        call(two, "get-model-info", args("modelId", "model-b"));   // session two has moved to B
        Map<String, Object> undone = undo(one, "model-a");

        assertNull(String.valueOf(undone), errorCode(undone));
        assertEquals(0, countActors(modelA));
    }

    // ---- shared stub --------------------------------------------------------------------------

    private static final class StubEditorModelManager implements IEditorModelManager {
        private final List<IArchimateModel> open = new ArrayList<>();
        private final List<PropertyChangeListener> listeners = new ArrayList<>();

        @Override
        public List<IArchimateModel> getModels() {
            return open;
        }

        @Override
        public void addPropertyChangeListener(PropertyChangeListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removePropertyChangeListener(PropertyChangeListener listener) {
            listeners.remove(listener);
        }

        void firePropertyChange(String propertyName, Object oldValue, Object newValue) {
            PropertyChangeEvent evt = new PropertyChangeEvent(this, propertyName, oldValue, newValue);
            for (PropertyChangeListener listener : new ArrayList<>(listeners)) {
                listener.propertyChange(evt);
            }
        }

        @Override public IArchimateModel createNewModel() { return null; }
        @Override public void registerModel(IArchimateModel model) {}
        @Override public IArchimateModel openModel(File file) { return null; }
        @Override public void openModel(IArchimateModel model) {}
        @Override public IArchimateModel loadModel(File file) { return null; }
        @Override public IArchimateModel load(File file) throws IOException { return null; }
        @Override public boolean closeModel(IArchimateModel model) throws IOException { return false; }
        @Override public boolean closeModel(IArchimateModel model, boolean askSave) throws IOException { return false; }
        @Override public boolean isModelLoaded(File file) { return false; }
        @Override public boolean isModelDirty(IArchimateModel model) { return false; }
        @Override public boolean saveModel(IArchimateModel model) throws IOException { return false; }
        @Override public boolean saveModelAs(IArchimateModel model) throws IOException { return false; }
        @Override public void saveState() throws IOException {}
        @Override public void firePropertyChange(Object source, String prop, Object oldValue, Object newValue) {}
    }
}
