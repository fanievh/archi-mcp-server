package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.response.dto.BatchSummaryDto;
import net.vheerden.archi.mcp.handlers.HandlerRegistrar;
import net.vheerden.archi.mcp.handlers.SearchHandler;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * What a bulk operation changed that nobody asked it to change, asserted on the bytes.
 *
 * <p>This defect had three layers and each fix left the next one standing. The projection dropped
 * both lists; then a copier defaulted one of them away; then the handler, which builds each
 * operation's response map key by key, simply never put either into it. The first two were pinned
 * on the typed result object, so both pins passed while nothing reached the wire — the value was
 * computed, attached, refreshed against live containment, and then discarded one layer above every
 * assertion. Only a round trip through the registered tool can tell "computed" from "sent", which
 * is why every assertion here is on serialized JSON.</p>
 */
public class BulkCollateralReportingOnTheWireTest {

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
        model.setName("Bulk Collateral Wire Fixture");
        model.setId("model-bulk-collateral-wire");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Wire");
        diagrams.getElements().add(view);

        IFolder business = model.getFolder(FolderType.BUSINESS);
        for (int i = 1; i <= 3; i++) {
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

    /** The displaced object, on the wire, for a bulk operation that actually executed. */
    @Test
    public void shouldNameTheDisplacedObjectOnTheWire_whenADispatchedBulkUpdateMovesIt()
            throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        String targetId = place("actor-1", 100, 100, 60, 30);
        String childId = place("actor-2", 100, 140, 60, 30);
        invokeTool(registry, "update-view-object", Map.of(
                "viewObjectId", childId, "anchorTarget", targetId,
                "anchorEdge", "below", "anchorDy", 10));
        int before = find(view, childId).getBounds().getY();

        String json = json(invokeTool(registry, "bulk-mutate", Map.of(
                "operations", List.of(Map.of("tool", "update-view-object",
                        "params", Map.of("viewObjectId", targetId, "height", 200))),
                "description", "grow an anchor target through bulk")));

        int after = find(view, childId).getBounds().getY();
        assertTrue("fixture must actually displace the anchored child, or this proves nothing: "
                + before + " -> " + after, after != before);

        assertTrue("the serialized bulk response must name the displaced object. Response was: "
                + json, json.contains("\"movedObjects\""));
        assertTrue("and must carry the id it displaced. Response was: " + json,
                json.contains(childId));
        assertTrue("and where it landed (" + after + "). Response was: " + json,
                json.contains("\"newY\":" + after));
    }

    /** The grown ancestor, on the wire, for the same path. */
    @Test
    public void shouldNameTheGrownAncestorOnTheWire_whenADispatchedBulkUpdateGrowsIt()
            throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        String groupId = accessor.addGroupToView(SESSION, view.getId(), "Holder",
                0, 0, 200, 200, null, null, null).entity().viewObjectId();
        String childId = accessor.addToView(SESSION, view.getId(), "actor-1",
                10, 10, 120, 55, false, groupId, null, null)
                .entity().viewObject().viewObjectId();

        String json = json(invokeTool(registry, "bulk-mutate", Map.of(
                "operations", List.of(Map.of("tool", "update-view-object",
                        "params", Map.of("viewObjectId", childId, "height", 300))),
                "description", "outgrow the group through bulk")));

        int grown = find(view, groupId).getBounds().getHeight();
        assertTrue("fixture must actually grow the group; it ended at " + grown, grown > 200);
        assertTrue("the serialized bulk response must name the grown ancestor. Response was: "
                + json, json.contains("\"resizedAncestors\""));
        assertTrue("and carry the height it ended at (" + grown + "). Response was: " + json,
                json.contains("\"newHeight\":" + grown));
    }

    /**
     * The negative. Absence has to mean "nothing was displaced", not "the field was dropped again"
     * — so an operation that displaced nothing must omit it rather than report an empty list.
     */
    @Test
    public void shouldOmitBothFieldsOnTheWire_whenTheOperationTouchedNothingItWasNotAsked()
            throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        String loneId = place("actor-1", 10, 10, 60, 30);

        String json = json(invokeTool(registry, "bulk-mutate", Map.of(
                "operations", List.of(Map.of("tool", "update-view-object",
                        "params", Map.of("viewObjectId", loneId, "height", 40))),
                "description", "a top-level object with nothing anchored to it")));

        assertFalse("nothing was displaced, so the field must be absent rather than empty. "
                + "Response was: " + json, json.contains("\"movedObjects\""));
        assertFalse("nothing was grown, so the field must be absent rather than empty. "
                + "Response was: " + json, json.contains("\"resizedAncestors\""));
        assertTrue("the operation itself must still be reported. Response was: " + json,
                json.contains("\"effectiveBounds\""));
    }

    /**
     * The renamed entity's name, on the wire. The handler builds each operation's map key by key
     * and gates this one on the value being non-null, so a name that is computed after dispatch
     * still has one layer left to cross — the layer where the displaced-object list was computed,
     * attached, refreshed and then never copied into the response.
     */
    @Test
    public void shouldCarryTheRenamedNameOnTheWire_whenABulkOperationRenamesAnEntity()
            throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        @SuppressWarnings("unchecked")
        Map<String, Object> op = (Map<String, Object>) ((List<?>) ((Map<?, ?>) invokeTool(registry,
                "bulk-mutate", Map.of(
                        "operations", List.of(Map.of("tool", "update-element",
                                "params", Map.of("id", "actor-1", "name", "Renamed Actor"))),
                        "description", "rename on the wire")).get("result"))
                .get("operations")).get(0);

        assertEquals("the wire must carry the name the model holds", "Renamed Actor",
                op.get("entityName"));
    }

    /**
     * A deletion's name is the one value in the whole response that cannot be checked by re-reading
     * the model, because the entity it describes is gone. That makes the round trip to the bytes
     * the only proof that matters here: a name corrected on the typed result and then dropped one
     * layer up would leave the wire saying exactly what it said before, and nothing downstream
     * could ever contradict it.
     *
     * <p>Both fields are asserted because the handler builds them from the same corrected result by
     * different routes — {@code entityName} straight off the envelope, {@code deletion} as a nested
     * object — so one arriving is no evidence about the other.</p>
     */
    @Test
    public void shouldCarryTheDestroyedNameOnTheWire_whenABulkDeleteFollowsARenameOfTheSameEntity()
            throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        @SuppressWarnings("unchecked")
        Map<String, Object> op = (Map<String, Object>) ((List<?>) ((Map<?, ?>) invokeTool(registry,
                "bulk-mutate", Map.of(
                        "operations", List.of(
                                Map.of("tool", "update-element",
                                        "params", Map.of("id", "actor-1", "name", "Renamed Actor")),
                                Map.of("tool", "delete-element",
                                        "params", Map.of("elementId", "actor-1"))),
                        "description", "rename then delete on the wire")).get("result"))
                .get("operations")).get(1);

        assertEquals("the wire must name the element as it was when it was destroyed",
                "Renamed Actor", op.get("entityName"));
        assertTrue("the cascade report must reach the wire at all. Operation was: " + op,
                op.get("deletion") instanceof Map);
        assertEquals("and it must carry the same name, not the one from before the rename",
                "Renamed Actor", ((Map<?, ?>) op.get("deletion")).get("name"));
    }

    /**
     * A cleared name is non-null, so the handler's gate admits it and it must arrive
     * present-and-empty — the same thing the standalone tool reports. Absent would read as
     * "unchanged" to a client that omits null fields, which is the opposite of what happened.
     */
    @Test
    public void shouldCarryAClearedNameAsPresentAndEmptyOnTheWire_whenABulkOperationClearsIt()
            throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        com.archimatetool.model.IAssociationRelationship rel =
                factory.createAssociationRelationship();
        rel.setId("rel-wire-1");
        rel.setName("Serves");
        rel.connect(
                (IBusinessActor) com.archimatetool.model.util.ArchimateModelUtils
                        .getObjectByID(model, "actor-1"),
                (IBusinessActor) com.archimatetool.model.util.ArchimateModelUtils
                        .getObjectByID(model, "actor-2"));
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);

        @SuppressWarnings("unchecked")
        Map<String, Object> op = (Map<String, Object>) ((List<?>) ((Map<?, ?>) invokeTool(registry,
                "bulk-mutate", Map.of(
                        "operations", List.of(Map.of("tool", "update-relationship",
                                "params", Map.of("id", "rel-wire-1", "name", ""))),
                        "description", "clear a name on the wire")).get("result"))
                .get("operations")).get(0);

        assertEquals("the model really was cleared", "", rel.getName());
        assertTrue("a cleared name must be present on the wire, not omitted",
                op.containsKey("entityName"));
        assertEquals("and it must be the empty string, not the name it used to have",
                "", op.get("entityName"));
    }

    /**
     * Back-compatibility on the bytes. A bulk call that changed no extra object must serialize
     * exactly as it did before either field existed, so adding them cannot have moved anything an
     * existing client reads.
     */
    @Test
    public void shouldSerializeIdentically_whenNoCollateralChangeOccurred() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        String loneId = place("actor-1", 10, 10, 60, 30);

        @SuppressWarnings("unchecked")
        Map<String, Object> op = (Map<String, Object>) ((List<?>) ((Map<?, ?>) invokeTool(registry,
                "bulk-mutate", Map.of(
                        "operations", List.of(Map.of("tool", "update-view-object",
                                "params", Map.of("viewObjectId", loneId, "height", 40))),
                        "description", "no collateral change")).get("result"))
                .get("operations")).get(0);

        assertEquals("the per-operation shape must be exactly the pre-existing key set",
                List.of("index", "tool", "action", "entityId", "entityType", "entityName",
                        "effectiveBounds"),
                List.copyOf(op.keySet()));
    }

    /**
     * Queued into an open batch, nothing has executed — so a growth the prepare computed has not
     * happened yet, and the object it names may not even exist. Reporting it would be the same lie
     * this field was added to end, moved to a mode where it is harder to notice.
     */
    @Test
    public void shouldNotReportCollateralChangeOnTheWire_whenTheBulkCallWasOnlyQueued()
            throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        String groupId = accessor.addGroupToView(SESSION, view.getId(), "Holder",
                0, 0, 200, 200, null, null, null).entity().viewObjectId();
        String childId = accessor.addToView(SESSION, view.getId(), "actor-1",
                10, 10, 120, 55, false, groupId, null, null)
                .entity().viewObject().viewObjectId();
        int heightBefore = find(view, groupId).getBounds().getHeight();

        dispatcher.beginBatch(SESSION, "queue a growth that has not happened");
        String json = json(invokeTool(registry, "bulk-mutate", Map.of(
                "operations", List.of(Map.of("tool", "update-view-object",
                        "params", Map.of("viewObjectId", childId, "height", 300))),
                "description", "queued, not dispatched")));

        assertEquals("fixture guard: nothing may have executed while the batch is open",
                heightBefore, find(view, groupId).getBounds().getHeight());
        assertFalse("a queued call must not name a container it has not grown. Response was: "
                + json, json.contains("\"resizedAncestors\""));
        assertFalse("nor an object it has not moved. Response was: " + json,
                json.contains("\"movedObjects\""));
        assertTrue("it must still declare itself as queued. Response was: " + json,
                json.contains("\"batch\""));
    }

    /** The same, parked awaiting a human. Nothing has executed here either. */
    @Test
    public void shouldNotReportCollateralChangeOnTheWire_whenTheBulkCallIsAwaitingApproval()
            throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        String groupId = accessor.addGroupToView(SESSION, view.getId(), "Holder",
                0, 0, 200, 200, null, null, null).entity().viewObjectId();
        String childId = accessor.addToView(SESSION, view.getId(), "actor-1",
                10, 10, 120, 55, false, groupId, null, null)
                .entity().viewObject().viewObjectId();
        int heightBefore = find(view, groupId).getBounds().getHeight();

        dispatcher.setApprovalModeProvider(() -> true);
        String json = json(invokeTool(registry, "bulk-mutate", Map.of(
                "operations", List.of(Map.of("tool", "update-view-object",
                        "params", Map.of("viewObjectId", childId, "height", 300))),
                "description", "awaiting a human")));

        assertEquals("fixture guard: nothing may have executed while the proposal is pending",
                heightBefore, find(view, groupId).getBounds().getHeight());
        assertFalse("a proposal must not name a container it has not grown. Response was: "
                + json, json.contains("\"resizedAncestors\""));
        assertFalse("nor an object it has not moved. Response was: " + json,
                json.contains("\"movedObjects\""));
        assertTrue("it must still declare itself as a proposal. Response was: " + json,
                json.contains("\"proposal\""));
    }

    private String place(String elementId, int x, int y, int w, int h) {
        return accessor.addToView(SESSION, view.getId(), elementId, x, y, w, h,
                false, null, null, null).entity().viewObject().viewObjectId();
    }

    /**
     * The verdict, not the geometry. A bulk call nested inside an open batch queues its compound
     * and runs nothing, then reads its skip reasons off that unexecuted compound — so they are
     * necessarily empty and {@code allSucceeded} computes true for operations that have not yet had
     * the chance to decline. Here the enclosing batch goes on to report the very decline the bulk
     * response already called a success.
     *
     * <p>Asserted on the serialized envelope rather than the result object, because this map is
     * assembled field by field: a DTO-level assertion passes while the wire says something else.</p>
     */
    @Test
    public void shouldNotClaimEverySucceeded_whenTheBulkCallWasOnlyQueued() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        IFolder target = IArchimateFactory.eINSTANCE.createFolder();
        target.setName("Doomed");
        target.setId("folder-doomed");
        model.getFolder(FolderType.BUSINESS).getFolders().add(target);

        dispatcher.beginBatch(SESSION, "fill the folder, then queue a bulk that deletes it");
        accessor.createElement(SESSION, "BusinessActor", "Occupant", null, null,
                target.getId(), null);
        String queued = json(invokeTool(registry, "bulk-mutate", Map.of(
                "operations", List.of(Map.of("tool", "delete-folder",
                        "params", Map.of("folderId", target.getId()))),
                "description", "queued, not dispatched")));

        assertFalse("a queued bulk has executed nothing, so it must not hand back a verdict on "
                + "whether every operation succeeded. Response was: " + queued,
                queued.contains("\"allSucceeded\""));
        assertTrue("it must still declare itself as queued. Response was: " + queued,
                queued.contains("\"batch\""));

        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);
        assertEquals("fixture guard: the operation the bulk called successful must actually "
                + "decline at commit, or this pins nothing", 1,
                summary.skippedOperations() == null ? 0 : summary.skippedOperations().size());
    }

    /** The same verdict, parked awaiting a human. Nothing has executed here either. */
    @Test
    public void shouldNotClaimEverySucceeded_whenTheBulkCallIsAwaitingApproval() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        dispatcher.setApprovalModeProvider(() -> true);

        String parked = json(invokeTool(registry, "bulk-mutate", Map.of(
                "operations", List.of(Map.of("tool", "create-element",
                        "params", Map.of("type", "BusinessActor", "name", "Pending"))),
                "description", "awaiting a human")));

        assertFalse("a proposal has executed nothing, so it must not report that every operation "
                + "succeeded. Response was: " + parked, parked.contains("\"allSucceeded\""));
        assertTrue("it must still declare itself as a proposal. Response was: " + parked,
                parked.contains("\"proposal\""));
    }

    private static String json(Map<String, Object> envelope) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(envelope);
    }

    private static IDiagramModelObject find(IDiagramModelContainer container, String id) {
        for (Object child : container.getChildren()) {
            IDiagramModelObject obj = (IDiagramModelObject) child;
            if (id.equals(obj.getId())) {
                return obj;
            }
            if (obj instanceof IDiagramModelContainer nested) {
                IDiagramModelObject hit = find(nested, id);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
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
