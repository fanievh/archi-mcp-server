package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
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
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.ISketchModelSticky;
import com.archimatetool.model.util.ArchimateModelUtils;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.handlers.HandlerRegistrar;
import net.vheerden.archi.mcp.handlers.SearchHandler;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.ViewDto;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * A batch commit in which every queued operation declined writes nothing, so it leaves no undo entry and
 * does not mark the model changed, and {@code end-batch} says nothing was applied instead of telling the
 * agent the changes can be undone. A commit where anything ran keeps its one entry and its wording.
 *
 * <p>The model is moved under the batch directly, as an edit in Archi between {@code begin-batch} and
 * {@code end-batch} would, but off the stack, so the stack's dirty flag reads only what the commit did.
 * The dispatcher runs the production stack-entry steps on a real {@link CommandStack}, with Archi's
 * notifying compound rebuilt as a plain one over the same instances, so no {@code Display} is needed.</p>
 */
public class AllDeclinedBatchCommitTest {

    private static final String SESSION = "default";
    private static final String NOTHING_APPLIED = "Nothing was applied (see skippedOperations), so there is "
            + "nothing to undo.";
    private static final String APPLIED_AS_ONE = "Changes applied as single undoable operation";
    private static final String UNDO_IN_ARCHI = "All changes can be undone with Ctrl+Z in ArchimateTool";

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private final MergeElementsCommandTest.StubEditorModelManager models =
            new MergeElementsCommandTest.StubEditorModelManager();
    private IArchimateModel model;
    private IFolder business;
    private CommandStack stack;
    private Command earlier;
    private MutationDispatcher dispatcher;
    private ArchiModelAccessorImpl accessor;
    private CommandRegistry registry;

    @Before
    public void setUp() {
        model = factory.createArchimateModel();
        model.setId("model-all-declined-commit");
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
        dispatcher.setApprovalModeProvider(() -> false);
        accessor = new ArchiModelAccessorImpl(models, dispatcher);
        dispatcher.onModelActive(model);
        registry = new CommandRegistry();
        HandlerRegistrar.registerAll(accessor, new ResponseFormatter(), registry,
                new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS));
        // One ordinary entry first, saved, so "the stack is as it was" is not read off an empty stack.
        IArchimateElement first = actor(business, "earlier");
        business.getElements().remove(first);
        MutationDispatcher.executeEntry(stack, dispatcher.agentEntry(new Command("Add earlier") {
            @Override
            public void execute() {
                business.getElements().add(first);
            }
        }));
        stack.markSaveLocation();
        earlier = stack.getUndoCommand();
    }

    @After
    public void tearDown() {
        accessor.dispose();
    }

    @Test
    public void shouldLeaveNoEntry_andSayNothingWasApplied_whenEveryQueuedOperationDeclined() throws Exception {
        IFolder a = folder(business, "folder-a");
        IFolder b = folder(business, "folder-b");
        dispatcher.beginBatch(SESSION, "two deletes");
        accessor.deleteFolder(SESSION, "folder-a", false);
        accessor.deleteFolder(SESSION, "folder-b", false);
        IArchimateElement inA = actor(a, "in-a");
        IArchimateElement inB = actor(b, "in-b");

        Map<String, Object> response = invoke("end-batch", Map.of());

        Map<?, ?> result = (Map<?, ?>) response.get("result");
        assertEquals("both declined: " + result, 2, ((List<?>) result.get("skippedOperations")).size());
        assertEquals(Boolean.TRUE, result.get("nothingApplied"));
        assertSame(business, a.eContainer());
        assertSame(business, b.eContainer());
        assertSame(a, inA.eContainer());
        assertSame(b, inB.eContainer());
        assertSame("the undo entry is still the earlier one", earlier, stack.getUndoCommand());
        assertEquals(1, stack.getCommands().length);
        assertFalse("the model is not marked changed", stack.isDirty());
        List<?> nextSteps = (List<?>) response.get("nextSteps");
        assertTrue(nextSteps.toString(), nextSteps.contains(NOTHING_APPLIED));
        assertFalse(nextSteps.toString(), nextSteps.contains(APPLIED_AS_ONE));
        assertFalse(nextSteps.toString(), nextSteps.contains(UNDO_IN_ARCHI));
    }

    @Test
    public void shouldKeepTheEntryAndItsWording_whenOneQueuedOperationRanAndTheOtherDeclined() throws Exception {
        IFolder a = folder(business, "folder-a");
        dispatcher.beginBatch(SESSION, "a delete and a create");
        accessor.deleteFolder(SESSION, "folder-a", false);
        accessor.createElement(SESSION, "BusinessActor", "Made", null, null, null, null);
        actor(a, "in-a");

        Map<String, Object> response = invoke("end-batch", Map.of());

        Map<?, ?> result = (Map<?, ?>) response.get("result");
        assertEquals("fixture check: the delete declined: " + result, 1,
                ((List<?>) result.get("skippedOperations")).size());
        assertFalse("absent when anything ran: " + result, result.containsKey("nothingApplied"));
        assertSame(business, a.eContainer());
        assertEquals("one new entry", 2, stack.getCommands().length);
        assertTrue(stack.isDirty());
        assertEquals(List.of(APPLIED_AS_ONE, "Use get-model-info to verify model state", UNDO_IN_ARCHI),
                response.get("nextSteps"));
    }

    /**
     * A force delete that removed the folder's contents and then declined, because a subfolder gained
     * content, is one skipped operation out of one, but it wrote. It keeps its entry.
     */
    @Test
    public void shouldKeepTheEntry_whenTheOnlyOperationIsAForceDeleteThatDeclinedAfterItsContentsWent()
            throws Exception {
        IFolder ops = folder(business, "ops");
        IArchimateElement gone = actor(ops, "gone");
        IFolder inner = folder(ops, "inner");
        dispatcher.beginBatch(SESSION, "force delete");
        accessor.deleteFolder(SESSION, "ops", true);
        actor(inner, "late");

        Map<String, Object> response = invoke("end-batch", Map.of());

        Map<?, ?> result = (Map<?, ?>) response.get("result");
        assertEquals("fixture check: the delete declined: " + result, 1,
                ((List<?>) result.get("skippedOperations")).size());
        assertEquals("fixture check: one operation", 1, result.get("operationCount"));
        assertNull("fixture check: its contents went", gone.eContainer());
        assertFalse("it wrote: " + result, result.containsKey("nothingApplied"));
        assertEquals("one new entry", 2, stack.getCommands().length);
        assertTrue(stack.isDirty());
        assertEquals(APPLIED_AS_ONE, ((List<?>) response.get("nextSteps")).get(0));
    }

    /**
     * A clone into a folder removed under the batch: the create declines, so the copy has nothing to copy
     * into, and the pair wrote nothing. One reason, the create's.
     */
    @Test
    public void shouldLeaveNoEntry_whenTheOnlyOperationIsACloneIntoAFolderRemovedUnderTheBatch() throws Exception {
        IFolder views = model.getFolder(FolderType.DIAGRAMS);
        IFolder target = folder(views, "folder-views");
        source(views);
        dispatcher.beginBatch(SESSION, "clone");
        String cloneId = ((ViewDto) accessor.cloneView(SESSION, "view-source", "Copy", "folder-views")
                .entity()).id();
        views.getFolders().remove(target);

        Map<String, Object> response = invoke("end-batch", Map.of());

        Map<?, ?> result = (Map<?, ?>) response.get("result");
        List<?> skipped = (List<?>) result.get("skippedOperations");
        assertEquals("the create's reason only: " + skipped, 1, skipped.size());
        assertTrue(skipped.toString(), skipped.get(0).toString().contains("'Copy' was not created"));
        assertEquals(Boolean.TRUE, result.get("nothingApplied"));
        assertNull("no clone", ArchimateModelUtils.getObjectByID(model, cloneId));
        assertSame("the undo entry is still the earlier one", earlier, stack.getUndoCommand());
        assertEquals(1, stack.getCommands().length);
        assertFalse("the model is not marked changed", stack.isDirty());
    }

    /**
     * A clone queued after a folder delete that runs: the delete wrote, so the commit keeps its entry, and
     * the copy, which had nothing to copy into, vouches for itself with no reason of its own.
     */
    @Test
    public void shouldKeepTheEntry_andTheCopyVouches_whenAFolderDeleteRanBeforeACloneIntoIt() throws Exception {
        IFolder views = model.getFolder(FolderType.DIAGRAMS);
        folder(views, "folder-views");
        source(views);
        dispatcher.beginBatch(SESSION, "delete then clone");
        accessor.deleteFolder(SESSION, "folder-views", false);
        accessor.cloneView(SESSION, "view-source", "Copy", "folder-views");
        CloneViewContentsCommand copy = queuedCopy();

        Map<String, Object> response = invoke("end-batch", Map.of());

        Map<?, ?> result = (Map<?, ?>) response.get("result");
        assertEquals("fixture check: the clone declined: " + result, 1,
                ((List<?>) result.get("skippedOperations")).size());
        assertFalse(result.toString(), result.containsKey("nothingApplied"));
        assertEquals("one new entry", 2, stack.getCommands().length);
        assertTrue(stack.isDirty());
        assertTrue("the copy had nothing to copy into", copy.changedNothing());
        assertNull("the create gives the one reason", copy.getSkipReason());
    }

    /**
     * A clone whose source is removed in Archi before the batch commits: the create is declined with the
     * copy's reason before it attaches the clone, so the pair writes nothing, not an attach and a detach.
     */
    @Test
    public void shouldLeaveNoEntry_andNeverAttachTheClone_whenTheOnlyOperationIsACloneWhoseSourceWentUnderTheBatch()
            throws Exception {
        IFolder views = model.getFolder(FolderType.DIAGRAMS);
        IArchimateDiagramModel source = source(views);
        dispatcher.beginBatch(SESSION, "clone");
        String cloneId = ((ViewDto) accessor.cloneView(SESSION, "view-source", "Copy", null).entity()).id();
        views.getElements().remove(source);
        List<String> seen = watch(views, cloneId);

        Map<String, Object> response = invoke("end-batch", Map.of());

        assertNothingApplied(response, "'Copy' was not created: the view 'Source' it was to be copied from was "
                + "removed from the model " + CommitSkippableCommand.CHANGED_SINCE_PREPARED
                + ", so there was nothing left to copy.");
        assertEquals("the clone was never attached", List.of(), seen);
        assertNull("no clone", ArchimateModelUtils.getObjectByID(model, cloneId));
    }

    @Test
    public void shouldLeaveNoEntry_andNeverAttachTheClone_whenTheSourceGainedAnObjectACloneCannotCopy()
            throws Exception {
        IFolder views = model.getFolder(FolderType.DIAGRAMS);
        IArchimateDiagramModel source = source(views);
        dispatcher.beginBatch(SESSION, "clone");
        String cloneId = ((ViewDto) accessor.cloneView(SESSION, "view-source", "Copy", null).entity()).id();
        ISketchModelSticky sticky = factory.createSketchModelSticky();
        sticky.setId("sticky");
        source.getChildren().add(sticky);
        List<String> seen = watch(views, cloneId);

        Map<String, Object> response = invoke("end-batch", Map.of());

        assertNothingApplied(response, "'Copy' was not created: the view 'Source' held a view object of a type "
                + "a clone cannot copy (SketchModelSticky) by the time the clone was made.");
        assertEquals("the clone was never attached", List.of(), seen);
        assertNull("no clone", ArchimateModelUtils.getObjectByID(model, cloneId));
    }

    /** A delete queued ahead of the clone ran, so the commit wrote: it keeps its entry. */
    @Test
    public void shouldKeepTheEntry_whenAQueuedDeleteOfTheSourceRanBeforeTheClone() throws Exception {
        source(model.getFolder(FolderType.DIAGRAMS));
        dispatcher.beginBatch(SESSION, "delete then clone");
        accessor.deleteView(SESSION, "view-source");
        String cloneId = ((ViewDto) accessor.cloneView(SESSION, "view-source", "Copy", null).entity()).id();

        Map<String, Object> response = invoke("end-batch", Map.of());

        Map<?, ?> result = (Map<?, ?>) response.get("result");
        List<?> skipped = (List<?>) result.get("skippedOperations");
        assertEquals("fixture check: the clone declined: " + result, 1, skipped.size());
        assertTrue(skipped.toString(), skipped.get(0).toString().contains("'Copy' was not created: the view 'Source' "
                + "it was to be copied from was removed from the model"));
        assertNull("the delete ran", ArchimateModelUtils.getObjectByID(model, "view-source"));
        assertNull("no clone", ArchimateModelUtils.getObjectByID(model, cloneId));
        assertFalse(result.toString(), result.containsKey("nothingApplied"));
        assertEquals("one new entry", 2, stack.getCommands().length);
        assertTrue(stack.isDirty());
        assertEquals(APPLIED_AS_ONE, ((List<?>) response.get("nextSteps")).get(0));
    }

    private void assertNothingApplied(Map<String, Object> response, String reason) {
        Map<?, ?> result = (Map<?, ?>) response.get("result");
        List<?> skipped = (List<?>) result.get("skippedOperations");
        assertEquals("one reason: " + skipped, 1, skipped.size());
        assertTrue(skipped.toString(), skipped.get(0).toString().contains(reason));
        assertEquals(result.toString(), Boolean.TRUE, result.get("nothingApplied"));
        assertSame("the undo entry is still the earlier one", earlier, stack.getUndoCommand());
        assertEquals(1, stack.getCommands().length);
        assertFalse("the model is not marked changed", stack.isDirty());
        List<?> nextSteps = (List<?>) response.get("nextSteps");
        assertTrue(nextSteps.toString(), nextSteps.contains(NOTHING_APPLIED));
        assertFalse(nextSteps.toString(), nextSteps.contains(APPLIED_AS_ONE));
        assertFalse(nextSteps.toString(), nextSteps.contains(UNDO_IN_ARCHI));
    }

    /** Records every add and remove of the view {@code id} in {@code folder}. */
    static List<String> watch(IFolder folder, String id) {
        List<String> seen = new ArrayList<>();
        folder.eAdapters().add(new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                if (n.getEventType() == Notification.ADD && n.getNewValue() instanceof IIdentifier added
                        && id.equals(added.getId())) {
                    seen.add("ADD");
                } else if (n.getEventType() == Notification.REMOVE && n.getOldValue() instanceof IIdentifier gone
                        && id.equals(gone.getId())) {
                    seen.add("REMOVE");
                }
            }
        });
        return seen;
    }

    private CloneViewContentsCommand queuedCopy() {
        for (Command queued : dispatcher.queuedLeafCommands(SESSION)) {
            if (queued instanceof CloneViewContentsCommand copy) {
                return copy;
            }
        }
        throw new AssertionError("fixture check: no clone queued");
    }

    private Map<String, Object> invoke(String toolName, Map<String, Object> args) throws Exception {
        McpServerFeatures.SyncToolSpecification spec = registry.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not found: " + toolName));
        McpSchema.CallToolResult result = spec.callHandler()
                .apply(null, new McpSchema.CallToolRequest(toolName, args));
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertFalse(text, Boolean.TRUE.equals(result.isError()));
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(text,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
    }

    /**
     * Archi's notifying compound needs the editor's model manager, which the headless lane lacks: run its
     * children, the same instances, in a plain compound. The batch's tracker is rebuilt as a tracker, so a queued
     * notifying compound inside it is reached too.
     */
    private static Command headless(Command command) {
        if (command instanceof TrackedCompoundCommand tracked) {
            TrackedCompoundCommand rebuilt = new TrackedCompoundCommand(tracked.getLabel());
            for (Object child : tracked.getCommands()) {
                rebuilt.add(headless((Command) child));
            }
            return rebuilt;
        }
        if (command.getClass() != NonNotifyingCompoundCommand.class) {
            return command;
        }
        CompoundCommand plain = new CompoundCommand(command.getLabel());
        for (Object child : ((CompoundCommand) command).getCommands()) {
            plain.add(headless((Command) child));
        }
        return plain;
    }

    private IArchimateDiagramModel source(IFolder views) {
        IArchimateDiagramModel source = factory.createArchimateDiagramModel();
        source.setId("view-source");
        source.setName("Source");
        views.getElements().add(source);
        return source;
    }

    private IFolder folder(IFolder parent, String id) {
        IFolder folder = factory.createFolder();
        folder.setId(id);
        folder.setName(id);
        parent.getFolders().add(folder);
        return folder;
    }

    private IArchimateElement actor(IFolder folder, String id) {
        IArchimateElement element = factory.createBusinessActor();
        element.setId(id);
        element.setName(id);
        folder.getElements().add(element);
        return element;
    }
}
