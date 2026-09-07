package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IAssociationRelationship;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.handlers.HandlerRegistrar;
import net.vheerden.archi.mcp.handlers.SearchHandler;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.session.SessionManager;
import net.vheerden.archi.mcp.response.dto.BatchSummaryDto;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;

/**
 * Pins that a folder delete cannot destroy contents that arrived after it was prepared.
 *
 * <p>A folder delete validates emptiness when the request is prepared. On the deferred
 * paths — a queued batch, or a multi-operation bulk request — every operation is prepared
 * before any of them runs, so an operation earlier in the same request can fill a folder
 * that was empty at validation time. Without a check at execution time the folder is then
 * removed with its new contents, by EMF containment, under a flag the caller set to forbid
 * exactly that.</p>
 *
 * <h2>Execution model</h2>
 *
 * <p>These tests drive a real {@link CommandStack} over an <em>ordered</em> compound, because
 * commit ORDER is the property under test. Two deliberate departures from the house idiom:</p>
 *
 * <ul>
 *   <li>The production compound is {@code NonNotifyingCompoundCommand}, whose {@code execute()}
 *       dereferences {@code IEditorModelManager.INSTANCE} and therefore cannot run headless
 *       ({@code ArchiPlugin.getInstance()} is null). The queued children are rebuilt into a
 *       plain GEF {@link CompoundCommand}, which preserves execution order exactly and drops
 *       only ECORE event suppression — irrelevant here.</li>
 *   <li>The {@code executeDecomposed} helper used elsewhere in this package flattens the
 *       compound away, so it cannot show compound-level behaviour at all. It is not used.</li>
 * </ul>
 */
public class BatchDeleteFolderGuardTest {

    private static final String SESSION = "batch-guard-session";

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private CommandStack stack;
    private IArchimateModel model;
    private IFolder business;
    private IFolder target;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Batch Folder Guard Fixture");
        model.setId("model-batch-folder-guard");
        model.setDefaults();
        business = model.getFolder(FolderType.BUSINESS);

        target = factory.createFolder();
        target.setName("Target Subfolder");
        target.setId("folder-target");
        business.getFolders().add(target);

        stubModelManager.setModels(List.of(model));

        stack = new CommandStack();
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                stack.execute(new AgentAuthoredCompoundCommand(toPlainCompound(command)));
            }
            @Override
            protected void dispatchCommand(Command command) {
                stack.execute(new AgentAuthoredCompoundCommand(toPlainCompound(command)));
            }
            private Command toPlainCompound(Command command) {
                // The attachment guard is itself a compound, and flattening it would drop the very
                // re-check under test. Rebuild it through its own factory so the guard survives
                // while its contents are still made headless-safe.
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

    // ---- the defect: contents that arrived after preparation ----

    @Test
    public void shouldNotDestroyElement_whenCreatedEarlierInSameBatchWithoutForce() throws Exception {
        dispatcher.beginBatch(SESSION, "create then delete");
        accessor.createElement(SESSION, "BusinessActor", "Victim", null, null, target.getId(), null);
        accessor.deleteFolder(SESSION, target.getId(), false);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertTrue("The folder must survive once an earlier operation filled it",
                business.getFolders().contains(target));
        assertEquals("The element created earlier in the batch must survive",
                1, target.getElements().size());

        assertNotNull("A skipped delete must be reported, not silently dropped",
                summary.skippedOperations());
        assertEquals(1, summary.skippedOperations().size());
        assertTrue("The reason must name the folder and stay actionable",
                summary.skippedOperations().get(0).contains("Target Subfolder"));
    }

    /**
     * The force variant. A cascade is built from the folder's contents at preparation time,
     * so anything added afterwards has no sub-command: it would be destroyed by containment
     * with nothing to undo it. That makes this case strictly worse than the no-force one.
     */
    @Test
    public void shouldNotDestroyElement_whenCreatedEarlierInSameBatchWithForce() throws Exception {
        IBusinessActor existing = factory.createBusinessActor();
        existing.setName("Pre-existing");
        existing.setId("elem-pre");
        target.getElements().add(existing);

        dispatcher.beginBatch(SESSION, "create then force-delete");
        accessor.createElement(SESSION, "BusinessActor", "Latecomer", null, null, target.getId(), null);
        accessor.deleteFolder(SESSION, target.getId(), true);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertTrue("The folder must survive", business.getFolders().contains(target));
        assertEquals("Both the prepared and the unprepared element must survive",
                2, target.getElements().size());
        assertNotNull(summary.skippedOperations());
    }

    /** The batch must remain a single undoable entry — the reason a skip is used over a throw. */
    @Test
    public void shouldKeepBatchUndoable_whenADeleteIsSkipped() throws Exception {
        dispatcher.beginBatch(SESSION, "create then delete");
        accessor.createElement(SESSION, "BusinessActor", "Victim", null, null, target.getId(), null);
        accessor.deleteFolder(SESSION, target.getId(), false);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        // Assert the skip actually happened first. Without this the rest of the test passes
        // against an unfixed build, where the batch is equally undoable — it just undoes a
        // destructive commit instead of a safe one.
        assertNotNull("This test is only meaningful once a delete has been skipped",
                summary.skippedOperations());

        assertTrue("Work that did run must stay undoable", stack.canUndo());
        stack.undo();
        assertEquals("Undo must reverse the create", 0, target.getElements().size());
        assertTrue("Undo must not resurrect a folder that was never removed",
                business.getFolders().contains(target));
    }

    // ---- the mirror order: a create into a folder an earlier operation deleted ----
    //
    // The tests above are the case where the DELETE is the broken operation: it was prepared
    // against an empty folder and something filled it. Reverse the order and the delete becomes
    // entirely legitimate — the folder really is empty at its turn — while the CREATE is the one
    // that goes wrong, resolving a folder that is about to leave the model and adding to it
    // afterwards. No prepare-time check on either operation can see that, because nothing is
    // false when either is prepared.

    @Test
    public void shouldNotCreateElement_whenTheTargetFolderWasDeletedEarlierInTheSameBatch()
            throws Exception {
        dispatcher.beginBatch(SESSION, "delete then create");
        accessor.deleteFolder(SESSION, target.getId(), false);
        String elementId = accessor.createElement(SESSION, "BusinessActor", "Orphan", null, null,
                target.getId(), null).entity().id();
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertFalse("The delete was legitimate at its own turn and must still happen",
                business.getFolders().contains(target));
        assertNull("The element must be out of the model entirely, not sitting in a folder that "
                        + "was detached from it",
                ArchimateModelUtils.getObjectByID(model, elementId));
        assertEquals("Nothing may be added to the detached folder either",
                0, target.getElements().size());

        assertNotNull("A create that did not happen must be reported, not silently dropped",
                summary.skippedOperations());
        assertEquals(1, summary.skippedOperations().size());
        assertTrue("The reason must name what was not created",
                summary.skippedOperations().get(0).contains("Orphan"));
        assertTrue("The reason must name the folder that went away",
                summary.skippedOperations().get(0).contains("Target Subfolder"));
    }

    /**
     * The specialization compound must decline WHOLE.
     *
     * <p>{@code create-element} with a specialization prepares a two-child compound whose first
     * child adds the new profile to {@code model.getProfiles()}. A guard on the create alone would
     * leave that profile behind — a specialization in the model with no users, created by an
     * element-creation that never happened, and nothing to undo it because the batch commits. A
     * test that only asserted the element's absence would pass on that leaking version.</p>
     */
    @Test
    public void shouldLeaveProfilesUntouched_whenASpecializedCreateIsDeclined() throws Exception {
        int profilesBefore = model.getProfiles().size();

        dispatcher.beginBatch(SESSION, "delete then create specialized");
        accessor.deleteFolder(SESSION, target.getId(), false);
        accessor.createElement(SESSION, "BusinessActor", "OrphanSpec", null, null,
                target.getId(), "Golden Record");
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals("A declined create must leave no specialization behind",
                profilesBefore, model.getProfiles().size());
        assertEquals("The whole compound declines as one operation, not once per child",
                1, summary.skippedOperations().size());
    }

    /** The batch stays one undoable entry, which is the reason a decline is used over a throw. */
    @Test
    public void shouldKeepBatchUndoable_whenACreateIsSkipped() throws Exception {
        dispatcher.beginBatch(SESSION, "delete then create");
        accessor.deleteFolder(SESSION, target.getId(), false);
        accessor.createElement(SESSION, "BusinessActor", "Orphan", null, null,
                target.getId(), null);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertNotNull("This test is only meaningful once a create has been skipped",
                summary.skippedOperations());
        assertTrue("Work that did run must stay undoable", stack.canUndo());
        stack.undo();
        assertTrue("Undo must put the deleted folder back",
                business.getFolders().contains(target));
        assertEquals("Undo must not resurrect an element that was never created",
                0, target.getElements().size());
    }

    @Test
    public void shouldNotCreateElement_whenBulkMutateDeletesTheFolderFirst() {
        List<BulkOperation> ops = List.of(
                new BulkOperation("delete-folder", Map.of(
                        "folderId", target.getId(), "force", Boolean.FALSE)),
                new BulkOperation("create-element", Map.of(
                        "type", "BusinessActor", "name", "BulkOrphan", "folderId", target.getId())));

        BulkMutationResult result = accessor.executeBulk(SESSION, ops, "delete then create", false);

        assertFalse("The delete was legitimate and must still happen",
                business.getFolders().contains(target));
        assertEquals("Nothing may be added to the detached folder",
                0, target.getElements().size());

        // The per-operation entry still reports a created id — those entries are built before the
        // changes are applied, and suppressing the id would hide the divergence the label exists
        // to declare. These two fields are the authority.
        assertFalse("A skipped operation must clear allSucceeded", result.allSucceeded());
        assertEquals(1, result.skippedOperations().size());
        assertTrue("The reason must name what was not created",
                result.skippedOperations().get(0).contains("BulkOrphan"));
    }

    // ---- the same two facts, on the bytes ----
    //
    // Both handlers build their response maps key by key, so a value that exists on the typed
    // result proves nothing about what an agent receives. skippedOperations is the authority the
    // tool descriptions point at, which makes a round trip through the registered tool the only
    // assertion that can tell "computed" from "sent".

    @Test
    public void shouldNameTheSkippedCreateOnTheWire_whenTheBatchIsCommitted() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        invokeTool(registry, "begin-batch", Map.of("description", "delete then create"));
        invokeTool(registry, "delete-folder", Map.of("folderId", target.getId(), "force", false));
        invokeTool(registry, "create-element", Map.of(
                "type", "BusinessActor", "name", "WireOrphan", "folderId", target.getId()));
        String json = json(invokeTool(registry, "end-batch", Map.of("commit", true)));

        assertFalse("the delete was legitimate and must still happen",
                business.getFolders().contains(target));
        assertEquals("nothing may be added to the detached folder", 0, target.getElements().size());
        assertTrue("the serialized end-batch response must name the skipped operation. "
                + "Response was: " + json, json.contains("\"skippedOperations\""));
        assertTrue("and name what was not created. Response was: " + json,
                json.contains("WireOrphan"));
    }

    @Test
    public void shouldNameTheSkippedCreateOnTheWire_whenBulkMutateDeletesTheFolderFirst()
            throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        String json = json(invokeTool(registry, "bulk-mutate", Map.of(
                "operations", List.of(
                        Map.of("tool", "delete-folder",
                                "params", Map.of("folderId", target.getId(), "force", false)),
                        Map.of("tool", "create-element",
                                "params", Map.of("type", "BusinessActor", "name", "WireBulkOrphan",
                                        "folderId", target.getId()))),
                "description", "delete then create")));

        assertEquals("nothing may be added to the detached folder", 0, target.getElements().size());
        assertTrue("the serialized bulk response must name the skipped operation. Response was: "
                + json, json.contains("\"skippedOperations\""));
        assertTrue("and name what was not created. Response was: " + json,
                json.contains("WireBulkOrphan"));
        assertTrue("a skipped operation must clear allSucceeded on the wire too. Response was: "
                + json, json.contains("\"allSucceeded\":false"));
    }

    // ---- bulk-mutate reaches the same defect with no batch open ----

    @Test
    public void shouldNotDestroyElement_whenBulkMutateCreatesThenDeletesFolder() {
        List<BulkOperation> ops = List.of(
                new BulkOperation("create-element", Map.of(
                        "type", "BusinessActor", "name", "BulkVictim", "folderId", target.getId())),
                new BulkOperation("delete-folder", Map.of(
                        "folderId", target.getId(), "force", Boolean.FALSE)));

        BulkMutationResult result = accessor.executeBulk(SESSION, ops, "bulk create then delete", false);

        assertTrue("bulk-mutate prepares every operation before running any, so it reaches "
                        + "the same defect without a batch being open",
                business.getFolders().contains(target));
        assertEquals(1, target.getElements().size());

        // Protecting the model is not enough. Per-operation entries are built before the
        // changes are applied, so op1 still reports action "deleted" for a folder that still
        // exists — without these two fields the caller is told a delete happened that did not.
        assertFalse("A skipped operation must clear allSucceeded", result.allSucceeded());
        assertEquals(1, result.skippedOperations().size());
        assertTrue("The reason must name the folder",
                result.skippedOperations().get(0).contains("Target Subfolder"));
    }

    /**
     * The nested case. A cascade is built flat, so a subfolder anywhere in the tree contributes
     * its own delete command as a sibling. If that inner delete declines it leaves itself
     * attached, and detaching the outer folder would carry it — and the content it just
     * protected — out of the model by containment, making the refusal worthless.
     */
    @Test
    public void shouldNotDestroyNestedSubfolderContent_whenAddedEarlierInSameBatch() throws Exception {
        IFolder nested = factory.createFolder();
        nested.setName("Nested Subfolder");
        nested.setId("folder-nested");
        target.getFolders().add(nested);

        IBusinessActor doomed = factory.createBusinessActor();
        doomed.setName("Authorised Casualty");
        doomed.setId("elem-doomed");
        target.getElements().add(doomed);

        dispatcher.beginBatch(SESSION, "create into nested then force-delete parent");
        accessor.createElement(SESSION, "BusinessActor", "NestedVictim", null, null,
                nested.getId(), null);
        accessor.deleteFolder(SESSION, target.getId(), true);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertTrue("The outer folder must stay: removing it would orphan the nested subfolder",
                business.getFolders().contains(target));
        assertTrue("The nested subfolder must stay attached to the outer folder",
                target.getFolders().contains(nested));
        assertEquals("The element created into the nested subfolder must survive",
                1, nested.getElements().size());

        assertNotNull("A decline below must be reported, not swallowed",
                summary.skippedOperations());
        assertEquals(1, summary.skippedOperations().size());
        assertTrue("The report must name the nested folder as the cause",
                summary.skippedOperations().get(0).contains("Nested Subfolder"));
    }

    /** A decline after the sub-commands ran must still reverse those sub-commands. */
    @Test
    public void shouldUndoSubCommands_whenDeclinedAfterTheyRan() throws Exception {
        IFolder nested = factory.createFolder();
        nested.setName("Nested Subfolder");
        nested.setId("folder-nested");
        target.getFolders().add(nested);

        IBusinessActor doomed = factory.createBusinessActor();
        doomed.setName("Authorised Casualty");
        doomed.setId("elem-doomed");
        target.getElements().add(doomed);

        dispatcher.beginBatch(SESSION, "create into nested then force-delete parent");
        accessor.createElement(SESSION, "BusinessActor", "NestedVictim", null, null,
                nested.getId(), null);
        accessor.deleteFolder(SESSION, target.getId(), true);
        dispatcher.endBatch(SESSION, true);

        // The cascade deleted the authorised element before the decline; undo must restore it.
        assertEquals("The authorised delete ran before the decline", 0, target.getElements().size());
        assertTrue(stack.canUndo());
        stack.undo();
        assertEquals("Undo must reverse the sub-commands that ran before the decline",
                1, target.getElements().size());
        assertTrue("Undo must not double-add a folder that was never removed",
                business.getFolders().contains(target));
        assertEquals("Undo must not leave a duplicate of the outer folder",
                1, business.getFolders().stream().filter(f -> f == target).count());
    }

    // ---- non-regression: legitimate deletes must be untouched ----

    @Test
    public void shouldStillDeleteEmptyFolder_whenNothingWasAdded() throws Exception {
        dispatcher.beginBatch(SESSION, "plain delete");
        accessor.deleteFolder(SESSION, target.getId(), false);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertFalse("An untouched empty folder must still be deleted",
                business.getFolders().contains(target));
        assertNull("Nothing was skipped, so the field is omitted", summary.skippedOperations());
    }

    @Test
    public void shouldStillCascade_whenForceDeletingAnUnchangedNonEmptyFolder() throws Exception {
        IBusinessActor actor = factory.createBusinessActor();
        actor.setName("Contained");
        actor.setId("elem-contained");
        target.getElements().add(actor);

        dispatcher.beginBatch(SESSION, "force delete");
        accessor.deleteFolder(SESSION, target.getId(), true);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertFalse("An unchanged folder must still force-delete",
                business.getFolders().contains(target));
        assertNull(summary.skippedOperations());
    }

    /**
     * Guards the check itself against a tempting but wrong implementation.
     *
     * <p>Comparing the number of sub-commands against the number of direct children looks
     * equivalent and is not: the sub-command list is flattened across the whole folder tree,
     * and a relationship already claimed by a contained element's cascade contributes no
     * command at all. Here the folder holds three children but yields two sub-commands, so a
     * count-based check would refuse a delete that is entirely legitimate.</p>
     */
    @Test
    public void shouldStillCascade_whenAContainedRelationshipIsClaimedByItsElement() throws Exception {
        IBusinessActor source = factory.createBusinessActor();
        source.setName("Source");
        source.setId("elem-source");
        IBusinessActor sink = factory.createBusinessActor();
        sink.setName("Sink");
        sink.setId("elem-sink");
        IAssociationRelationship rel = factory.createAssociationRelationship();
        rel.setId("rel-contained");
        rel.setSource(source);
        rel.setTarget(sink);

        target.getElements().add(source);
        target.getElements().add(sink);
        target.getElements().add(rel);

        dispatcher.beginBatch(SESSION, "force delete with claimed relationship");
        accessor.deleteFolder(SESSION, target.getId(), true);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertFalse("Three children but two sub-commands is legitimate, not a conflict",
                business.getFolders().contains(target));
        assertNull("A count-based check would wrongly report a skip here",
                summary.skippedOperations());
    }

    /** Losing children between prepare and execute destroys nothing, so it must be allowed. */
    @Test
    public void shouldStillDelete_whenAnEarlierOperationEmptiedTheFolder() throws Exception {
        IBusinessActor actor = factory.createBusinessActor();
        actor.setName("Doomed");
        actor.setId("elem-doomed");
        target.getElements().add(actor);

        dispatcher.beginBatch(SESSION, "delete element then folder");
        accessor.deleteElement(SESSION, actor.getId());
        accessor.deleteFolder(SESSION, target.getId(), true);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertFalse("Deleting less than was prepared is safe",
                business.getFolders().contains(target));
        assertNull(summary.skippedOperations());
    }

    // ---- the immediate path is unaffected ----

    /**
     * The attachment re-check is inert outside a deferred path: a folder resolved from the live
     * model is attached by definition, so an ordinary create behaves exactly as before, and the
     * prepare-time rejections that run before it is even built are untouched.
     */
    @Test
    public void shouldCreateNormally_onTheImmediatePath() {
        String id = accessor.createElement(SESSION, "BusinessActor", "Ordinary", null, null,
                target.getId(), null).entity().id();

        assertEquals("an ordinary create still lands in its folder", 1, target.getElements().size());
        assertNotNull("and is reachable in the model",
                ArchimateModelUtils.getObjectByID(model, id));

        try {
            accessor.createElement(SESSION, "BusinessActor", "Nowhere", null, null,
                    "folder-does-not-exist", null);
            fail("Expected the prepare-time folder lookup to reject an unknown folder");
        } catch (ModelAccessException e) {
            assertEquals("the prepare-time rejection must still fire, ahead of any guard",
                    ErrorCode.FOLDER_NOT_FOUND, e.getErrorCode());
        }

        try {
            accessor.createElement(SESSION, "BusinessActor", "Misfiled", null, null,
                    model.getFolder(FolderType.DIAGRAMS).getId(), null);
            fail("Expected the prepare-time layer check to reject a mismatched folder");
        } catch (ModelAccessException e) {
            assertEquals("the layer rejection must still fire, ahead of any guard",
                    ErrorCode.FOLDER_LAYER_MISMATCH, e.getErrorCode());
        }
    }

    @Test
    public void shouldStillRejectNonEmptyFolder_onTheImmediatePath() {
        accessor.createElement(SESSION, "BusinessActor", "Bystander", null, null, target.getId(), null);
        try {
            accessor.deleteFolder(SESSION, target.getId(), false);
            fail("Expected the prepare-time guard to reject a non-empty folder");
        } catch (RuntimeException expected) {
            assertNotNull(expected.getMessage());
        }
        assertTrue(business.getFolders().contains(target));
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

    private static String json(Map<String, Object> envelope) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(envelope);
    }

    private static class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final List<PropertyChangeListener> listeners = new ArrayList<>();

        void setModels(List<IArchimateModel> models) { this.models = models; }

        @Override
        public List<IArchimateModel> getModels() { return models; }
        @Override
        public void addPropertyChangeListener(PropertyChangeListener listener) {
            listeners.add(listener);
        }
        @Override
        public void removePropertyChangeListener(PropertyChangeListener listener) {
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
