package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
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
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Pins what the core read tools answer when no model is open in Archi: {@code MODEL_NOT_LOADED}
 * with "open a model", never {@code INTERNAL_ERROR} and never a stale-cursor error.
 *
 * <h2>Fixture</h2>
 *
 * <p>The production wiring: the real accessor over a stub model manager with nothing open, a real
 * {@link SessionManager}, every handler registered through {@link HandlerRegistrar}, and calls made
 * through the {@link CommandRegistry} so each one runs inside its model call scope. Handler unit tests
 * with a stub accessor cannot see this: their stubs report a version with no model, and outside a
 * call scope the session layer keys by the empty string instead of abstaining.</p>
 */
public class NoModelOpenReadToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OPEN_A_MODEL = "Open an ArchiMate model in ArchimateTool";

    private StubEditorModelManager models;
    private ArchiModelAccessorImpl accessor;
    private CommandRegistry registry;
    private final McpSyncServerExchange one = exchange("session-one");

    @Before
    public void setUp() {
        models = new StubEditorModelManager();
        accessor = new ArchiModelAccessorImpl(models, null);
        registry = new CommandRegistry();
        SessionManager sessions = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
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

    /** A model holding two actors, two relationships between them and two views. */
    private IArchimateModel seededModel() {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setId("model-a");
        model.setName("Model A");
        model.setDefaults();
        model.setAdapter(CommandStack.class, new CommandStack());
        IBusinessActor first = factory.createBusinessActor();
        first.setId("actor-1");
        first.setName("First actor");
        IBusinessActor second = factory.createBusinessActor();
        second.setName("Second actor");
        model.getFolder(FolderType.BUSINESS).getElements().add(first);
        model.getFolder(FolderType.BUSINESS).getElements().add(second);
        for (String name : List.of("First link", "Second link")) {
            IAssociationRelationship link = factory.createAssociationRelationship();
            link.setName(name);
            link.connect(first, second);
            model.getFolder(FolderType.RELATIONS).getElements().add(link);
        }
        for (String name : List.of("First view", "Second view")) {
            IArchimateDiagramModel view = factory.createArchimateDiagramModel();
            view.setName(name);
            model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
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

    /** The human opens a model in Archi: it joins the open set and becomes active. */
    private void open(IArchimateModel model) {
        models.getModels().add(model);
        models.firePropertyChange(IEditorModelManager.PROPERTY_MODEL_OPENED, null, model);
    }

    /** The human closes a model, announced as Archi announces it: (null, model). */
    private void close(IArchimateModel model) {
        models.getModels().remove(model);
        models.firePropertyChange(IEditorModelManager.PROPERTY_MODEL_REMOVED, null, model);
    }

    private McpSchema.CallToolResult callRaw(McpSyncServerExchange exchange, String tool, Map<String, Object> args) {
        return registry.getToolSpecifications().stream()
                .filter(spec -> tool.equals(spec.tool().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not registered: " + tool))
                .callHandler().apply(exchange, new McpSchema.CallToolRequest(tool, args));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(McpSyncServerExchange exchange, String tool, Map<String, Object> args) {
        McpSchema.CallToolResult result = callRaw(exchange, tool, args);
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

    /** The call is an error that tells the agent to open a model, and nothing else. */
    @SuppressWarnings("unchecked")
    private void assertModelNotLoaded(McpSyncServerExchange exchange, String tool, Map<String, Object> args) {
        McpSchema.CallToolResult result = callRaw(exchange, tool, args);
        Map<String, Object> envelope;
        try {
            envelope = JSON.readValue(((McpSchema.TextContent) result.content().get(0)).text(), Map.class);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        assertEquals(tool + " → " + envelope, "MODEL_NOT_LOADED", errorCode(envelope));
        assertEquals(OPEN_A_MODEL, error(envelope).get("suggestedCorrection"));
        assertTrue(tool + " is flagged as an error", Boolean.TRUE.equals(result.isError()));
    }

    private String cursorFrom(String tool, Map<String, Object> args) {
        Map<String, Object> page = call(one, tool, args);
        assertNull(String.valueOf(page), errorCode(page));
        String cursor = (String) meta(page).get("cursor");
        assertNotNull("a first page of one issues a cursor: " + page, cursor);
        return cursor;
    }

    // ---- every core read tool, no model open, a session on the call ---------------------------

    @Test
    public void shouldReturnModelNotLoaded_whenGetModelInfoIsCalledWithNoModelOpen() {
        assertModelNotLoaded(one, "get-model-info", args());
    }

    @Test
    public void shouldReturnModelNotLoaded_whenGetElementByIdIsCalledWithNoModelOpen() {
        assertModelNotLoaded(one, "get-element", args("id", "some-id"));
    }

    @Test
    public void shouldReturnModelNotLoaded_whenGetElementByIdsIsCalledWithNoModelOpen() {
        assertModelNotLoaded(one, "get-element", args("ids", List.of("some-id", "other-id")));
    }

    @Test
    public void shouldReturnModelNotLoaded_whenGetViewsIsCalledWithNoModelOpen() {
        assertModelNotLoaded(one, "get-views", args());
    }

    @Test
    public void shouldReturnModelNotLoaded_whenGetViewContentsIsCalledWithNoModelOpen() {
        assertModelNotLoaded(one, "get-view-contents", args("viewId", "some-view"));
    }

    @Test
    public void shouldReturnModelNotLoaded_whenSearchElementsIsCalledWithNoModelOpen() {
        assertModelNotLoaded(one, "search-elements", args("query", "customer"));
    }

    @Test
    public void shouldReturnModelNotLoaded_whenSearchRelationshipsIsCalledWithNoModelOpen() {
        assertModelNotLoaded(one, "search-relationships", args("query", "serves"));
    }

    @Test
    public void shouldReturnModelNotLoaded_whenGetRelationshipsIsCalledWithNoModelOpen() {
        assertModelNotLoaded(one, "get-relationships", args("elementId", "some-id"));
    }

    @Test
    public void shouldReturnModelNotLoaded_whenASearchAsksForADryRunOrASummaryWithNoModelOpen() {
        assertModelNotLoaded(one, "search-elements", args("query", "customer", "dryRun", true));
        assertModelNotLoaded(one, "search-elements", args("query", "customer", "format", "summary"));
        assertModelNotLoaded(one, "search-relationships", args("query", "serves", "dryRun", true));
        assertModelNotLoaded(one, "search-relationships", args("query", "serves", "format", "summary"));
    }

    @Test
    public void shouldReturnModelNotLoaded_notACachedAnswer_whenTheSameReadIsRepeatedAfterItsModelCloses() {
        IArchimateModel model = seededModel();
        open(model);
        Map<String, Object> fresh = call(one, "get-element", args("id", "actor-1"));
        assertNull(String.valueOf(fresh), errorCode(fresh));
        Map<String, Object> cached = call(one, "get-element", args("id", "actor-1"));
        assertEquals("the answer is in the session cache: " + cached, Boolean.TRUE, meta(cached).get("cacheHit"));
        close(model);

        assertModelNotLoaded(one, "get-element", args("id", "actor-1"));
        assertModelNotLoaded(one, "get-model-info", args());
    }

    // ---- the default session: a call whose exchange carries no usable session id --------------

    @Test
    public void shouldReturnModelNotLoaded_whenTheCallFallsBackToTheDefaultSession() {
        for (McpSyncServerExchange anonymous : List.of(exchange(null), exchange("  "))) {
            assertModelNotLoaded(anonymous, "get-model-info", args());
            assertModelNotLoaded(anonymous, "search-elements", args("query", "customer"));
        }
    }

    // ---- addressing still refuses a model that is not open ------------------------------------

    @Test
    public void shouldReturnModelNotOpen_whenTheCallNamesAModelAndNoModelIsOpen() {
        Map<String, Object> refused = call(one, "get-model-info", args("modelId", "model-x"));

        assertEquals(String.valueOf(refused), "MODEL_NOT_OPEN", errorCode(refused));
    }

    // ---- pagination cursors issued before the model closed ------------------------------------

    @Test
    public void shouldReturnModelNotLoaded_notInvalidCursor_whenASearchElementsCursorOutlivesItsModel() {
        IArchimateModel model = seededModel();
        open(model);
        String cursor = cursorFrom("search-elements", args("query", "", "limit", 1));
        close(model);

        assertModelNotLoaded(one, "search-elements", args("query", "", "cursor", cursor));
    }

    @Test
    public void shouldReturnModelNotLoaded_notInvalidCursor_whenASearchRelationshipsCursorOutlivesItsModel() {
        IArchimateModel model = seededModel();
        open(model);
        String cursor = cursorFrom("search-relationships", args("query", "", "limit", 1));
        close(model);

        assertModelNotLoaded(one, "search-relationships", args("query", "", "cursor", cursor));
    }

    @Test
    public void shouldReturnModelNotLoaded_notInvalidCursor_whenAGetViewsCursorOutlivesItsModel() {
        IArchimateModel model = seededModel();
        open(model);
        String cursor = cursorFrom("get-views", args("limit", 1));
        close(model);

        assertModelNotLoaded(one, "get-views", args("cursor", cursor));
    }

    @Test
    public void shouldStillReturnInvalidCursor_whenTheOpenModelChangedAfterTheCursorWasIssued() {
        IArchimateModel model = seededModel();
        open(model);
        String cursor = cursorFrom("search-elements", args("query", "", "limit", 1));
        editInArchi(model);

        Map<String, Object> stale = call(one, "search-elements", args("query", "", "cursor", cursor));

        assertEquals(String.valueOf(stale), "INVALID_CURSOR", errorCode(stale));
    }

    // ---- version tracking across a read with no model -----------------------------------------

    @Test
    public void shouldNotFlagAModelChange_whenTheFirstReadAfterOpeningFollowsAReadWithNoModel() {
        assertModelNotLoaded(one, "get-model-info", args());
        IArchimateModel model = seededModel();
        open(model);

        Map<String, Object> first = call(one, "get-model-info", args());
        assertNull(String.valueOf(first), errorCode(first));
        assertNotEquals("the first observation is not a change: " + first,
                Boolean.TRUE, meta(first).get("modelChanged"));

        editInArchi(model);
        Map<String, Object> after = call(one, "get-model-info", args());
        assertNull(String.valueOf(after), errorCode(after));
        assertEquals("a real change is still flagged: " + after, Boolean.TRUE, meta(after).get("modelChanged"));
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
