package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
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
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.handlers.HandlerRegistrar;
import net.vheerden.archi.mcp.handlers.SearchHandler;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Where a placement landed, asserted on the bytes a caller actually receives.
 *
 * <p>An agent cannot see the canvas, so a placement response is its only account of what now
 * exists. Until this shipped, the account was missing its subject: a nested object's x and y are
 * relative to its immediate parent's top-left corner, and no write response on any path named that
 * parent. Two of the four placement paths were worse than silent — one reported a hardcoded null
 * for every group it nested, and one echoed the caller's own request parameter back under a name
 * that reads as an outcome.</p>
 *
 * <p>Every assertion here runs through the registered tool and reads serialized JSON. The bulk path
 * builds its per-operation map key by key, so a value computed in the projection and never copied
 * across would satisfy any assertion made on the typed result while nothing reached the wire — that
 * is how this family of fields was lost once before.</p>
 */
public class PlacementParentReportingTest {

    private static final String SESSION = "default";

    private IArchimateFactory factory;
    private ArchiModelAccessorImpl accessor;
    private CommandStack stack;
    private MutationDispatcher dispatcher;
    private IArchimateModel model;
    private IArchimateDiagramModel view;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        StubEditorModelManager stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Placement Parent Fixture");
        model.setId("model-placement-parent");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Placement");
        diagrams.getElements().add(view);

        IFolder business = model.getFolder(FolderType.BUSINESS);
        for (int i = 1; i <= 5; i++) {
            IBusinessActor actor = factory.createBusinessActor();
            actor.setId("actor-" + i);
            actor.setName("Actor " + i);
            business.getElements().add(actor);
        }

        stubModelManager.setModels(List.of(model));

        stack = new CommandStack();
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                stack.execute(toPlainCompound(command));
            }
            @Override
            protected void dispatchCommand(Command command) {
                stack.execute(toPlainCompound(command));
            }
            private Command toPlainCompound(Command command) {
                if (command instanceof CompoundCommand compound) {
                    CompoundCommand plain = new CompoundCommand(compound.getLabel());
                    for (Object child : compound.getCommands()) {
                        plain.add(toPlainCompound((Command) child));
                    }
                    return plain;
                }
                return command;
            }
        };
        dispatcher.setApprovalModeProvider(() -> false);
        accessor = new ArchiModelAccessorImpl(stubModelManager, dispatcher);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ---- the standalone placement paths --------------------------------------------------------

    /** The container an element was nested into, named in the response that created the nesting. */
    @Test
    public void shouldNameTheContainer_whenAddToViewNestsAnElementInsideAnother() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        String containerId = addTopLevel(registry, "actor-1");

        Map<String, Object> viewObject = viewObjectOf(invokeTool(registry, "add-to-view", Map.of(
                "viewId", view.getId(), "elementId", "actor-2",
                "parentViewObjectId", containerId, "x", 30, "y", 30,
                "width", 60, "height", 30)));

        assertEquals("the response must name the container this placement landed in — without it "
                + "the x and y it reports are relative to nothing the caller can identify",
                containerId, viewObject.get("parentViewObjectId"));
    }

    /**
     * The negative, on the bytes. Absence has to mean "this object sits on the view", not "the
     * field was dropped again", so a top-level placement must omit the key rather than send null —
     * which is also what keeps its response byte-identical to before the field existed.
     */
    @Test
    public void shouldOmitTheContainerEntirely_whenThePlacementIsTopLevelOnTheView()
            throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        String json = json(invokeTool(registry, "add-to-view", Map.of(
                "viewId", view.getId(), "elementId", "actor-1",
                "x", 10, "y", 10, "width", 60, "height", 30)));

        assertFalse("a top-level placement has no container, so the key must be absent rather than "
                + "present-and-null. Response was: " + json,
                json.contains("parentViewObjectId"));
    }

    /**
     * The group path reported a hardcoded null for every group it nested, on the one field whose
     * whole job is to say which container the group went into. Omitted-because-null and
     * omitted-because-top-level are indistinguishable to a client, so the defect read as "this
     * group sits on the view" for a group that did not.
     */
    @Test
    public void shouldNameTheContainer_whenAddGroupToViewNestsAGroupInsideAnother()
            throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        String outerId = (String) resultOf(invokeTool(registry, "add-group-to-view", Map.of(
                "viewId", view.getId(), "label", "Outer",
                "x", 0, "y", 0, "width", 400, "height", 300))).get("viewObjectId");

        Map<String, Object> inner = resultOf(invokeTool(registry, "add-group-to-view", Map.of(
                "viewId", view.getId(), "label", "Inner", "parentViewObjectId", outerId,
                "x", 20, "y", 20, "width", 200, "height", 120)));

        assertEquals("a nested group must name the group it went into", outerId,
                inner.get("parentViewObjectId"));
    }

    /**
     * The note path, which reported whatever the request asked for under a name that reads as an
     * outcome. Standalone, the request and the resolved container are the same object every time —
     * the id the caller writes is the id the lookup resolves — so this asserts the field is served
     * and correct, and it cannot separate a read from an echo.
     *
     * <p><strong>Nothing can, on any surface this server serves.</strong> The two diverge at
     * exactly one site: the bulk arm hands the prepare a resolved container and a null
     * {@code parentViewObjectId} when the parent is a group an earlier operation in the same call
     * created, so the echo answered "top-level" for a note that was nested. On that path the
     * uniform per-operation envelope replaces this DTO, so the wrong value never reached a caller.
     * The correction is therefore preventive, and saying so is the point: a pin claiming to catch
     * it would be green under the very mutation it names.</p>
     */
    @Test
    public void shouldNameTheContainer_whenAddNoteToViewNestsANoteInsideAGroup() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        String holderId = (String) resultOf(invokeTool(registry, "add-group-to-view", Map.of(
                "viewId", view.getId(), "label", "Holder",
                "x", 0, "y", 0, "width", 400, "height", 300))).get("viewObjectId");

        Map<String, Object> note = resultOf(invokeTool(registry, "add-note-to-view", Map.of(
                "viewId", view.getId(), "content", "Nested", "parentViewObjectId", holderId,
                "x", 20, "y", 20, "width", 120, "height", 60)));

        assertEquals("a nested note must name the group it went into", holderId,
                note.get("parentViewObjectId"));
    }

    // ---- the bulk path -------------------------------------------------------------------------

    /**
     * The corpus defect in miniature. Two legal component-in-component nestings were produced by
     * positional back-references that resolved one slot off the caller's intent, nothing rejected
     * them — both are legal ArchiMate — and they surfaced two tool calls later as an unrelated
     * detector's boundary count, with nothing left to attribute them to.
     *
     * <p>The remedy is disclosure, not rejection: the response names the container the object
     * actually landed in, and the caller compares it against the one it meant. So this drives a
     * reference that resolves to the wrong container on purpose and asserts the response says so.
     */
    @Test
    public void shouldNameTheContainerItActuallyLandedIn_whenABackReferenceResolvesToTheWrongOne()
            throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        List<Map<String, Object>> ops = operationsOf(invokeTool(registry, "bulk-mutate", Map.of(
                "operations", List.of(
                        Map.of("tool", "add-to-view", "params", Map.of(
                                "viewId", view.getId(), "elementId", "actor-1",
                                "x", 0, "y", 0, "width", 400, "height", 300)),
                        Map.of("tool", "add-to-view", "params", Map.of(
                                "viewId", view.getId(), "elementId", "actor-2",
                                "x", 500, "y", 0, "width", 400, "height", 300)),
                        // The caller meant operation 1 and wrote operation 0 — the off-by-one that
                        // put two components inside the wrong siblings on the live run.
                        Map.of("tool", "add-to-view", "params", Map.of(
                                "viewId", view.getId(), "elementId", "actor-3",
                                "parentViewObjectId", "$0.id",
                                "x", 30, "y", 30, "width", 60, "height", 30))),
                "description", "a back-reference that resolves to a container the caller did not "
                        + "intend")));

        String landedIn = (String) ops.get(0).get("entityId");
        String intended = (String) ops.get(1).get("entityId");

        assertEquals("the entry must name the container the object is actually in", landedIn,
                ops.get(2).get("parentViewObjectId"));
        assertNotEquals("and the fixture must actually miss the intended container, or this "
                + "reproduces nothing", intended, ops.get(2).get("parentViewObjectId"));
    }

    /** The same, on the bytes: the bulk map is hand-built key by key. */
    @Test
    public void shouldCarryTheContainerOnTheWire_whenABulkPlacementNestsAnElement()
            throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        String containerId = addTopLevel(registry, "actor-1");

        String json = json(invokeTool(registry, "bulk-mutate", Map.of(
                "operations", List.of(Map.of("tool", "add-to-view", "params", Map.of(
                        "viewId", view.getId(), "elementId", "actor-2",
                        "parentViewObjectId", containerId,
                        "x", 30, "y", 30, "width", 60, "height", 30))),
                "description", "nest through bulk")));

        assertTrue("the serialized bulk response must name the container. Response was: " + json,
                json.contains("\"parentViewObjectId\":\"" + containerId + "\""));
    }

    /** And omits it for a top-level operation, so absence keeps meaning "on the view". */
    @Test
    public void shouldOmitTheContainerOnTheWire_whenABulkPlacementIsTopLevel() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        String json = json(invokeTool(registry, "bulk-mutate", Map.of(
                "operations", List.of(Map.of("tool", "add-to-view", "params", Map.of(
                        "viewId", view.getId(), "elementId", "actor-1",
                        "x", 10, "y", 10, "width", 60, "height", 30))),
                "description", "top-level through bulk")));

        assertFalse("nothing contained it, so the key must be absent. Response was: " + json,
                json.contains("parentViewObjectId"));
    }

    /**
     * Nothing is effective in a queued call by construction — the compound is collected for later,
     * so no object has entered any container yet. The projection is skipped wholesale there, which
     * is what keeps the queued response from asserting a containment that has not happened; the
     * batch sibling is what it says instead.
     */
    @Test
    public void shouldClaimNoContainer_whenTheCallIsQueuedIntoABatchRatherThanApplied()
            throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        String containerId = addTopLevel(registry, "actor-1");
        invokeTool(registry, "begin-batch", Map.of("description", "queue a nested placement"));

        Map<String, Object> queued = resultOf(invokeTool(registry, "bulk-mutate", Map.of(
                "operations", List.of(Map.of("tool", "add-to-view", "params", Map.of(
                        "viewId", view.getId(), "elementId", "actor-2",
                        "parentViewObjectId", containerId,
                        "x", 30, "y", 30, "width", 60, "height", 30))),
                "description", "nest inside an open batch")));

        assertTrue("the queued response must declare itself a preview rather than state",
                queued.containsKey("batch") || queued.containsKey("preview"));
        for (Map<String, Object> op : operationsOfResult(queued)) {
            assertNull("a queued operation has placed nothing, so it must claim no container: " + op,
                    op.get("parentViewObjectId"));
        }
    }

    /**
     * The approval half of the same rule, which had been reasoned about rather than pinned.
     *
     * <p>Nothing is effective in a parked call either: {@code executeBulk}'s proposal branch returns
     * above the only post-dispatch pass, so the container is never read there. Batch mode is pinned
     * above; this is its sibling, and a pass that started populating the field at prepare time to
     * "fill the gap" would go red here — which is the point, because that projection is exactly the
     * lie the labelling exists to avoid.</p>
     */
    @Test
    public void shouldClaimNoContainer_whenTheCallIsParkedForApprovalRatherThanApplied()
            throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        String containerId = addTopLevel(registry, "actor-1");
        dispatcher.setApprovalModeProvider(() -> true);

        Map<String, Object> parked = resultOf(invokeTool(registry, "bulk-mutate", Map.of(
                "operations", List.of(Map.of("tool", "add-to-view", "params", Map.of(
                        "viewId", view.getId(), "elementId", "actor-2",
                        "parentViewObjectId", containerId,
                        "x", 30, "y", 30, "width", 60, "height", 30))),
                "description", "nest while awaiting approval")));

        assertTrue("the parked response must declare itself a proposal rather than state",
                parked.containsKey("proposal") || parked.containsKey("preview"));
        for (Map<String, Object> op : operationsOfResult(parked)) {
            assertNull("nothing has been written, so no operation may claim a container: " + op,
                    op.get("parentViewObjectId"));
        }
    }

    /**
     * The read and write surfaces must keep spelling the container one way.
     *
     * <p>Three published surfaces — this DTO's javadoc, the {@code add-to-view} description, and
     * {@code docs/mutation-model.md} — all justify the field's name by saying it is the one
     * {@code get-view-contents} already uses. That is a checkable claim about another type, and
     * nothing checked it: renaming either side would leave three descriptions quietly false while
     * every other test stayed green. Asserted by reflection over both records rather than by
     * eye, so it survives a rename of either.</p>
     */
    @Test
    public void shouldSpellTheContainerTheSameWay_onTheReadAndWritePaths() {
        assertTrue("the read path must publish parentViewObjectId — three shipped descriptions "
                + "justify the write path's spelling by pointing at it",
                hasComponent(net.vheerden.archi.mcp.response.dto.ViewNodeDto.class,
                        "parentViewObjectId"));
        for (Class<?> writeDto : List.of(
                net.vheerden.archi.mcp.response.dto.ViewObjectDto.class,
                net.vheerden.archi.mcp.response.dto.ViewGroupDto.class,
                net.vheerden.archi.mcp.response.dto.ViewNoteDto.class)) {
            assertTrue(writeDto.getSimpleName() + " must spell the container the same way the read "
                    + "path does, or one concept ships under two names",
                    hasComponent(writeDto, "parentViewObjectId"));
        }
    }

    private static boolean hasComponent(Class<?> record, String name) {
        for (java.lang.reflect.RecordComponent c : record.getRecordComponents()) {
            if (c.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    // ---- helpers -------------------------------------------------------------------------------

    private String addTopLevel(CommandRegistry registry, String elementId) throws Exception {
        return (String) viewObjectOf(invokeTool(registry, "add-to-view", Map.of(
                "viewId", view.getId(), "elementId", elementId,
                "x", 0, "y", 0, "width", 400, "height", 300))).get("viewObjectId");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resultOf(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("result");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> viewObjectOf(Map<String, Object> envelope) {
        return (Map<String, Object>) resultOf(envelope).get("viewObject");
    }

    private static List<Map<String, Object>> operationsOf(Map<String, Object> envelope) {
        return operationsOfResult(resultOf(envelope));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> operationsOfResult(Map<String, Object> result) {
        Object ops = result.containsKey("operations") ? result.get("operations")
                : result.get("succeeded");
        return (List<Map<String, Object>>) ops;
    }

    private static String json(Map<String, Object> envelope) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(envelope);
    }

    private CommandRegistry registryOverLiveAccessor() {
        CommandRegistry registry = new CommandRegistry();
        SessionManager sessions =
                new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        HandlerRegistrar.registerAll(accessor, new ResponseFormatter(), registry, sessions);
        return registry;
    }

    private Map<String, Object> invokeTool(CommandRegistry registry, String toolName,
            Map<String, Object> args) throws Exception {
        io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification spec =
                registry.getToolSpecifications().stream()
                        .filter(s -> s.tool().name().equals(toolName))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("Tool not found: " + toolName));
        io.modelcontextprotocol.spec.McpSchema.CallToolResult result = spec.callHandler()
                .apply(null, new io.modelcontextprotocol.spec.McpSchema.CallToolRequest(toolName, args));
        io.modelcontextprotocol.spec.McpSchema.TextContent content =
                (io.modelcontextprotocol.spec.McpSchema.TextContent) result.content().get(0);
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(content.text(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    private static class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final List<PropertyChangeListener> listeners = new ArrayList<>();

        void setModels(List<IArchimateModel> models) { this.models = models; }

        @Override public List<IArchimateModel> getModels() { return models; }
        @Override public void addPropertyChangeListener(PropertyChangeListener listener) {
            listeners.add(listener);
        }
        @Override public void removePropertyChangeListener(PropertyChangeListener listener) {
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
