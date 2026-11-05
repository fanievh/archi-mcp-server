package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.ecore.util.EContentAdapter;
import org.eclipse.gef.commands.CommandStack;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IAssociationRelationship;
import com.archimatetool.model.IBusinessActor;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import net.vheerden.archi.mcp.handlers.HandlerRegistrar;
import net.vheerden.archi.mcp.handlers.SearchHandler;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.PaginationCursor;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Pins that a pagination cursor pages the model instance it was issued on, and no other: a cursor
 * carried to a call that reads a different model, or a later instance of the same model, is refused
 * naming both, never answered with the other model's page cut at the cursor's offset.
 *
 * <h2>Fixture</h2>
 *
 * <p>The production wiring: the real accessor over a stub model manager, a real {@link SessionManager},
 * every handler registered through {@link HandlerRegistrar}, and calls made through the
 * {@link CommandRegistry} so each one runs inside its model call scope. Each activation in Archi advances
 * the activated model's version, so two seeded models that nobody has edited, each activated once, read
 * the same version, and a version comparison alone cannot tell them apart.</p>
 */
public class CursorModelIdentityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private StubEditorModelManager models;
    private ArchiModelAccessorImpl accessor;
    private CommandRegistry registry;
    private final McpSyncServerExchange one = exchange("session-one");

    private IArchimateModel modelA;
    private IArchimateModel modelB;

    @Before
    public void setUp() {
        models = new StubEditorModelManager();
        accessor = new ArchiModelAccessorImpl(models, null);
        registry = new CommandRegistry();
        SessionManager sessions = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        accessor.addModelChangeListener(sessions);
        HandlerRegistrar.registerAll(accessor, new ResponseFormatter(), registry, sessions);
        modelA = seededModel("model-a", "Model A");
        modelB = seededModel("model-b", "Model B");
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ---- fixture helpers ----------------------------------------------------------------------

    /** A model holding two actors, two relationships between them and two views. */
    private IArchimateModel seededModel(String id, String name) {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setId(id);
        model.setName(name);
        model.setDefaults();
        model.setAdapter(CommandStack.class, new CommandStack());
        IBusinessActor first = factory.createBusinessActor();
        first.setName(name + " first actor");
        IBusinessActor second = factory.createBusinessActor();
        second.setName(name + " second actor");
        model.getFolder(FolderType.BUSINESS).getElements().add(first);
        model.getFolder(FolderType.BUSINESS).getElements().add(second);
        for (String link : List.of("first link", "second link")) {
            IAssociationRelationship relationship = factory.createAssociationRelationship();
            relationship.setName(name + " " + link);
            relationship.connect(first, second);
            model.getFolder(FolderType.RELATIONS).getElements().add(relationship);
        }
        for (String view : List.of("first view", "second view")) {
            IArchimateDiagramModel diagram = factory.createArchimateDiagramModel();
            diagram.setName(name + " " + view);
            model.getFolder(FolderType.DIAGRAMS).getElements().add(diagram);
        }
        forwardContentEvents(model);
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

    /** The human edits the model in Archi, which moves its version. */
    private static void editInArchi(IArchimateModel model) {
        IBusinessActor added = IArchimateFactory.eINSTANCE.createBusinessActor();
        added.setName("Added in Archi");
        model.getFolder(FolderType.BUSINESS).getElements().add(added);
    }

    private static McpSyncServerExchange exchange(String sessionId) {
        return new McpSyncServerExchange(null) {
            @Override
            public String sessionId() {
                return sessionId;
            }
        };
    }

    /** The human opens a model in Archi (or switches to one already open): it becomes active. */
    private void open(IArchimateModel model) {
        if (!models.getModels().contains(model)) {
            models.getModels().add(model);
        }
        models.firePropertyChange(IEditorModelManager.PROPERTY_MODEL_OPENED, null, model);
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

    /** The version an agent reads for {@code modelId}, as a tool call addressing it reads it. */
    private String version(String modelId) {
        accessor.getMutationDispatcher().selection().enter("probe", modelId);
        try {
            return accessor.getModelVersion();
        } finally {
            accessor.getMutationDispatcher().selection().exit();
        }
    }

    /** The instance key a tool call addressing {@code modelId} reads. */
    private String instanceKey(String modelId) {
        accessor.getMutationDispatcher().selection().enter("probe", modelId);
        try {
            return accessor.getMutationDispatcher().selection().cacheKey();
        } finally {
            accessor.getMutationDispatcher().selection().exit();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String tool, Map<String, Object> args) {
        McpSchema.CallToolResult result = registry.getToolSpecifications().stream()
                .filter(spec -> tool.equals(spec.tool().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not registered: " + tool))
                .callHandler().apply(one, new McpSchema.CallToolRequest(tool, args));
        try {
            Map<String, Object> envelope = JSON.readValue(
                    ((McpSchema.TextContent) result.content().get(0)).text(), Map.class);
            assertEquals("isError agrees with the envelope: " + envelope,
                    envelope.containsKey("error"), Boolean.TRUE.equals(result.isError()));
            return envelope;
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> error(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("error");
    }

    private static String errorCode(Map<String, Object> envelope) {
        Map<String, Object> error = error(envelope);
        return error == null ? null : (String) error.get("code");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> meta(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("_meta");
    }

    private String cursorFrom(String tool, Map<String, Object> args) {
        Map<String, Object> page = call(tool, args);
        assertNull(String.valueOf(page), errorCode(page));
        String cursor = (String) meta(page).get("cursor");
        assertNotNull("a first page of one issues a cursor: " + page, cursor);
        return cursor;
    }

    /** The call is refused as a cursor from another model, and the message names both. */
    private static void assertRefusedNaming(Map<String, Object> envelope, String... named) {
        assertEquals(String.valueOf(envelope), "INVALID_CURSOR", errorCode(envelope));
        String message = (String) error(envelope).get("message");
        for (String text : named) {
            assertTrue("message names " + text + ": " + message, message.contains(text));
        }
    }

    // ---- a cursor carried to a call that addresses another model ------------------------------

    @Test
    public void shouldRefuseTheCursor_whenASearchElementsCursorFromOneModelIsSentAddressingAnother() {
        bothOpenWithAActive();
        assertEquals("both unedited models read the same version", version("model-a"), version("model-b"));
        String cursor = cursorFrom("search-elements", args("modelId", "model-a", "query", "", "limit", 1));

        Map<String, Object> next = call("search-elements",
                args("modelId", "model-b", "query", "", "cursor", cursor));

        assertRefusedNaming(next, "model-a", "model-b", "Model A", "Model B");
        assertTrue(String.valueOf(next), String.valueOf(error(next).get("suggestedCorrection"))
                .startsWith("Pass modelId \"model-a\" with the cursor"));
    }

    @Test
    public void shouldRefuseTheCursor_whenASearchRelationshipsCursorFromOneModelIsSentAddressingAnother() {
        bothOpenWithAActive();
        assertEquals("both unedited models read the same version", version("model-a"), version("model-b"));
        String cursor = cursorFrom("search-relationships", args("modelId", "model-a", "query", "", "limit", 1));

        Map<String, Object> next = call("search-relationships",
                args("modelId", "model-b", "query", "", "cursor", cursor));

        assertRefusedNaming(next, "model-a", "model-b", "Model A", "Model B");
    }

    @Test
    public void shouldRefuseTheCursor_whenAGetViewsCursorFromOneModelIsSentAddressingAnother() {
        bothOpenWithAActive();
        assertEquals("both unedited models read the same version", version("model-a"), version("model-b"));
        String cursor = cursorFrom("get-views", args("modelId", "model-a", "limit", 1));

        Map<String, Object> next = call("get-views", args("modelId", "model-b", "cursor", cursor));

        assertRefusedNaming(next, "model-a", "model-b", "Model A", "Model B");
    }

    // ---- a cursor carried across a switch of the model active in Archi ------------------------

    @Test
    public void shouldRefuseTheCursor_whenTheModelActiveInArchiChangesBetweenPagesOfAnUnaddressedSearch() {
        // Each activation advances the activated model's version, so A is activated once more than B
        // before page 1: the switch to B then brings B level with the version the cursor carries.
        open(modelA);
        bothOpenWithAActive();
        String cursor = cursorFrom("search-elements", args("query", "", "limit", 1));
        open(modelB);
        assertEquals("the switch leaves both models on the same version", version("model-a"), version("model-b"));

        Map<String, Object> next = call("search-elements", args("query", "", "cursor", cursor));

        assertRefusedNaming(next, "model-a", "model-b", "Model A", "Model B");
    }

    // ---- a cursor carried to a later instance of the same model -------------------------------

    @Test
    public void shouldRefuseTheCursor_whenItsModelWasClosedAndANewInstanceWithTheSameIdOpened() {
        open(modelA);
        String issuedAt = version("model-a");
        String cursor = cursorFrom("search-elements", args("modelId", "model-a", "query", "", "limit", 1));
        close(modelA);
        IArchimateModel reopened = seededModel("model-a", "Model A");
        open(reopened);
        assertEquals("the new instance's own counter reaches the version the cursor carries",
                issuedAt, version("model-a"));

        Map<String, Object> next = call("search-elements",
                args("modelId", "model-a", "query", "", "cursor", cursor));

        assertRefusedNaming(next, "model-a", "another open instance");
    }

    // ---- what a production cursor carries --------------------------------------------------------

    private static final List<String> PAGED_TOOLS = List.of("search-elements", "search-relationships", "get-views");

    private static Map<String, Object> firstPageArgs(String tool, Object... extra) {
        Map<String, Object> args = args(extra);
        if (!"get-views".equals(tool)) {
            args.put("query", "");
        }
        args.put("limit", 1);
        return args;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldStampTheModelInstanceTheCallRead_whenAPagedToolIssuesACursor() throws Exception {
        bothOpenWithAActive();
        for (String tool : PAGED_TOOLS) {
            Map<String, Object> page = call(tool, firstPageArgs(tool, "modelId", "model-b"));
            Map<String, Object> model = (Map<String, Object>) meta(page).get("model");

            PaginationCursor.CursorData cursor = PaginationCursor.decode((String) meta(page).get("cursor"));

            assertEquals(tool, 2, cursor.version());
            assertEquals(tool, model.get("id"), cursor.modelId());
            assertEquals(tool, model.get("name"), cursor.modelName());
            assertEquals(tool, instanceKey("model-b"), cursor.modelKey());
            assertNotNull(tool, cursor.modelKey());
        }
    }

    // ---- the same model: the cursor is still honoured, and still refused once the model changes ----

    @Test
    @SuppressWarnings("unchecked")
    public void shouldContinueFromTheCursorsOffset_whenTheSameModelIsPagedAndNothingChanged() throws Exception {
        bothOpenWithAActive();
        for (String tool : PAGED_TOOLS) {
            Map<String, Object> first = call(tool, firstPageArgs(tool, "modelId", "model-a"));
            String cursor = (String) meta(first).get("cursor");
            assertEquals(tool, 1, PaginationCursor.decode(cursor).offset());

            Map<String, Object> nextArgs = args("modelId", "model-a", "cursor", cursor);
            if (!"get-views".equals(tool)) {
                nextArgs.put("query", "");
            }
            Map<String, Object> second = call(tool, nextArgs);

            assertNull(tool + " → " + second, errorCode(second));
            List<Map<String, Object>> firstRows = (List<Map<String, Object>>) first.get("result");
            List<Map<String, Object>> secondRows = (List<Map<String, Object>>) second.get("result");
            assertEquals(tool, 1, secondRows.size());
            assertTrue(tool + " page 2 is A's: " + secondRows,
                    String.valueOf(secondRows.get(0).get("name")).startsWith("Model A "));
            assertTrue(tool + " page 2 is not page 1 again",
                    !firstRows.get(0).get("id").equals(secondRows.get(0).get("id")));
        }
    }

    @Test
    public void shouldStillReportTheModelAsModified_whenTheSameModelChangedAfterTheCursorWasIssued() {
        bothOpenWithAActive();
        for (String tool : PAGED_TOOLS) {
            String cursor = cursorFrom(tool, firstPageArgs(tool, "modelId", "model-a"));
            editInArchi(modelA);

            Map<String, Object> nextArgs = args("modelId", "model-a", "cursor", cursor);
            if (!"get-views".equals(tool)) {
                nextArgs.put("query", "");
            }
            Map<String, Object> next = call(tool, nextArgs);

            assertEquals(tool + " → " + next, "INVALID_CURSOR", errorCode(next));
            assertEquals(tool, "The model has been modified since this cursor was created",
                    error(next).get("message"));
        }
    }

    // ---- what the refusal tells the agent to do --------------------------------------------------

    @Test
    public void shouldNotAdviseAddressingTheCursorsModel_whenThatModelIsNoLongerOpen() {
        bothOpenWithAActive();
        String cursor = cursorFrom("search-elements", args("modelId", "model-a", "query", "", "limit", 1));
        close(modelA);

        Map<String, Object> next = call("search-elements",
                args("modelId", "model-b", "query", "", "cursor", cursor));

        assertRefusedNaming(next, "model-a", "model-b", "Model A", "Model B", "no longer open");
        String suggestion = String.valueOf(error(next).get("suggestedCorrection"));
        assertTrue("addressing a closed model only earns another refusal: " + suggestion,
                !suggestion.contains("modelId \"model-a\""));
    }

    @Test
    public void shouldNameTheCursorsModelWithoutAnId_whenTheCursorCarriesAKeyButNoId() {
        open(modelA);
        String cursor = PaginationCursor.encode(version("model-a"),
                new PaginationCursor.CursorModel(null, "Model A", "999"), 1, 1, 2,
                Map.of("query", ""));

        Map<String, Object> next = call("search-elements",
                args("modelId", "model-a", "query", "", "cursor", cursor));

        assertRefusedNaming(next, "Model A");
        assertTrue(String.valueOf(next), !String.valueOf(error(next).get("message")).contains("null"));
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
