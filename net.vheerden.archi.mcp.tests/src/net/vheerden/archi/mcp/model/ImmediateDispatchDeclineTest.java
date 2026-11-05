package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import com.archimatetool.model.IProfile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.handlers.HandlerRegistrar;
import net.vheerden.archi.mcp.handlers.HandlerUtils;
import net.vheerden.archi.mcp.handlers.SearchHandler;
import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * A single immediate call whose command declines as it writes, because the model moved between the
 * prepare and the write. The call refuses and leaves no undo entry and no new version; what the command
 * wrote before it declined is put back in the same run. A bulk call keeps its partial result.
 *
 * <p>The dispatcher runs the race step and then the production stack-entry steps on a real
 * {@link CommandStack}, so no {@code Display} is needed.</p>
 */
public class ImmediateDispatchDeclineTest {

    private static final String SESSION = "default";

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private final MergeElementsCommandTest.StubEditorModelManager models =
            new MergeElementsCommandTest.StubEditorModelManager();
    private final List<Command> dispatched = new ArrayList<>();
    private IArchimateModel model;
    private CommandStack stack;
    private Command earlier;
    private MutationDispatcher dispatcher;
    private ArchiModelAccessorImpl accessor;
    /** Runs on the dispatch path, after the prepare and before the command executes. */
    private Runnable race;

    @Before
    public void setUp() {
        model = factory.createArchimateModel();
        model.setId("model-immediate-decline");
        model.setDefaults();
        models.setModels(List.of(model));
        stack = new CommandStack();
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                if (race != null) {
                    race.run();
                }
                dispatched.add(command);
                executeEntry(stack, agentEntry(headless(command)));
            }
        };
        dispatcher.setApprovalModeProvider(() -> false);
        accessor = new ArchiModelAccessorImpl(models, dispatcher);
        // One ordinary entry first, saved, so "the stack is as it was" is not read off an empty stack.
        IArchimateElement first = actor(model.getFolder(FolderType.BUSINESS), "earlier");
        model.getFolder(FolderType.BUSINESS).getElements().remove(first);
        MutationDispatcher.executeEntry(stack, dispatcher.agentEntry(new Command("Add earlier") {
            @Override
            public void execute() {
                model.getFolder(FolderType.BUSINESS).getElements().add(first);
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
    public void shouldRefuseTheMove_andLeaveNoEntryOrVersion_whenItsTargetWentUnderAnotherLayer() {
        IFolder business = model.getFolder(FolderType.BUSINESS);
        IFolder target = folder(business, "target");
        actor(business, "mover");
        List<Map<String, String>> raced = new ArrayList<>();
        race = () -> {
            model.getFolder(FolderType.APPLICATION).getFolders().add(target);
            raced.add(MergeElementsCommandTest.snapshot(model, Set.of()));
        };
        String version = accessor.getModelVersion();

        try {
            accessor.moveToFolder(SESSION, "mover", "target");
            fail("a move that changed nothing must not report success");
        } catch (ModelAccessException e) {
            String reason = ((MoveToFolderCommand) dispatched.get(0)).getSkipReason();
            assertNotNull("fixture check: the move declined", reason);
            assertEquals(ErrorCode.MUTATION_FAILED, e.getErrorCode());
            assertEquals(reason, e.getMessage());
            assertEquals("Nothing was changed. Read the model again and repeat the call if it is still wanted.",
                    e.getSuggestedCorrection());
        }

        assertEquals("fixture check: the race ran", 1, raced.size());
        MergeElementsCommandTest.assertSnapshot("nothing written after the race", raced.get(0),
                MergeElementsCommandTest.snapshot(model, Set.of()));
        assertSame("the undo entry is still the earlier one", earlier, stack.getUndoCommand());
        assertFalse("the model is not marked changed", stack.isDirty());
        assertEquals("no new version", version, accessor.getModelVersion());
    }

    @Test
    public void shouldRefuseTheCreate_andLeaveNoEntryOrVersion_whenItsParentFolderWent() {
        IFolder business = model.getFolder(FolderType.BUSINESS);
        IFolder ops = folder(business, "ops");
        race = () -> business.getFolders().remove(ops);
        String version = accessor.getModelVersion();

        try {
            accessor.createFolder(SESSION, "ops", "Late", null, null);
            fail("a create that changed nothing must not report success");
        } catch (ModelAccessException e) {
            String reason = ((RequireAttachedContainerCommand) dispatched.get(0)).getSkipReason();
            assertNotNull("fixture check: the guard declined", reason);
            assertEquals(ErrorCode.MUTATION_FAILED, e.getErrorCode());
            assertEquals(reason, e.getMessage());
        }

        assertTrue("no folder was created", ops.getFolders().isEmpty());
        assertSame("the undo entry is still the earlier one", earlier, stack.getUndoCommand());
        assertFalse("the model is not marked changed", stack.isDirty());
        assertEquals("no new version", version, accessor.getModelVersion());
    }

    @Test
    public void shouldMoveAndBumpTheVersionOnce_whenNothingRaces() {
        IFolder business = model.getFolder(FolderType.BUSINESS);
        IFolder target = folder(business, "target");
        IArchimateElement mover = actor(business, "mover");
        String version = accessor.getModelVersion();

        accessor.moveToFolder(SESSION, "mover", "target");

        assertSame(target, mover.eContainer());
        assertEquals("one new entry", 2, stack.getCommands().length);
        assertTrue(stack.isDirty());
        assertEquals("one new version", Long.parseLong(version) + 1, Long.parseLong(accessor.getModelVersion()));
    }

    /**
     * A force delete whose subfolder gains content as it runs removes the folder's own contents and then
     * declines. What it removed is put back in the same run, and the call refuses.
     */
    @Test
    public void shouldRefuseTheForceDelete_andPutBackWhatItRemoved_whenASubfolderGainedContent() {
        IFolder business = model.getFolder(FolderType.BUSINESS);
        IFolder ops = folder(business, "ops");
        IArchimateElement gone = actor(ops, "gone");
        IFolder inner = folder(ops, "inner");
        List<Map<String, String>> raced = new ArrayList<>();
        race = () -> {
            actor(inner, "late");
            raced.add(MergeElementsCommandTest.snapshot(model, Set.of()));
        };
        String version = accessor.getModelVersion();

        try {
            accessor.deleteFolder(SESSION, "ops", true);
            fail("a delete that stopped partway must not report success");
        } catch (ModelAccessException e) {
            assertRefusedWithEveryReason(e);
        }

        assertNotNull("fixture check: the delete declined",
                ((DeleteFolderCommand) dispatched.get(0)).getSkipReason());
        assertSame("what it removed is back", ops, gone.eContainer());
        assertSame(business, ops.eContainer());
        assertSame(ops, inner.eContainer());
        assertPutBack(raced, version);
    }

    @Test
    public void shouldRefuseTheForceSpecializationDelete_andPutBackEveryUsage_whenAUsageGainedAnotherProfile() {
        IFolder business = model.getFolder(FolderType.BUSINESS);
        IArchimateElement first = actor(business, "first");
        IArchimateElement second = actor(business, "second");
        IProfile vip = profile("VIP");
        IProfile other = profile("Other");
        first.getProfiles().add(vip);
        second.getProfiles().add(vip);
        List<Map<String, String>> raced = new ArrayList<>();
        race = () -> {
            first.getProfiles().add(other);
            raced.add(MergeElementsCommandTest.snapshot(model, Set.of()));
        };
        String version = accessor.getModelVersion();

        try {
            accessor.deleteSpecialization(SESSION, "VIP", "BusinessActor", true);
            fail("a delete that stopped partway must not report success");
        } catch (ModelAccessException e) {
            assertRefusedWithEveryReason(e);
        }

        assertEquals("both concepts still carry it", List.of(vip, other), first.getProfiles());
        assertEquals(List.of(vip), second.getProfiles());
        assertTrue("the specialization is still in the model", model.getProfiles().contains(vip));
        assertPutBack(raced, version);
    }

    @Test
    public void shouldRefuseTheClone_andLeaveNoNewView_whenTheSourceWentAfterThePrepare() {
        IFolder views = model.getFolder(FolderType.DIAGRAMS);
        IArchimateDiagramModel source = factory.createArchimateDiagramModel();
        source.setId("source");
        source.setName("Source");
        views.getElements().add(source);
        List<Map<String, String>> raced = new ArrayList<>();
        race = () -> {
            views.getElements().remove(source);
            raced.add(MergeElementsCommandTest.snapshot(model, Set.of()));
        };
        String version = accessor.getModelVersion();

        try {
            accessor.cloneView(SESSION, "source", "Copy", null);
            fail("a clone that copied nothing must not report success");
        } catch (ModelAccessException e) {
            assertRefusedWithEveryReason(e);
        }

        assertTrue("no new view", views.getElements().isEmpty());
        assertPutBack(raced, version);
    }

    /** The clone is declined before its create attaches it, so the roll-back has nothing to take back. */
    @Test
    public void shouldNeverAttachTheClone_whenTheSourceWentAfterThePrepare() {
        IFolder views = model.getFolder(FolderType.DIAGRAMS);
        IArchimateDiagramModel source = factory.createArchimateDiagramModel();
        source.setId("source");
        source.setName("Source");
        views.getElements().add(source);
        List<Integer> events = new ArrayList<>();
        race = () -> {
            views.getElements().remove(source);
            views.eAdapters().add(new AdapterImpl() {
                @Override
                public void notifyChanged(Notification n) {
                    events.add(n.getEventType());
                }
            });
        };

        try {
            accessor.cloneView(SESSION, "source", "Copy", null);
            fail("a clone that copied nothing must not report success");
        } catch (ModelAccessException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("it was to be copied from was removed from the model"));
        }

        assertEquals("nothing was added to the folder, or taken back from it", List.of(), events);
    }

    /**
     * A bulk call keeps its partial result: the operations that applied stay applied, and the one that
     * declined is reported per operation.
     */
    @Test
    public void shouldKeepTheRestOfABulkCall_andReportTheDeclinedOperation() {
        IFolder business = model.getFolder(FolderType.BUSINESS);
        IFolder target = folder(business, "target");

        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("delete-folder", Map.of("folderId", "target", "force", Boolean.FALSE)),
                new BulkOperation("create-element", Map.of(
                        "type", "BusinessActor", "name", "Orphan", "folderId", "target"))),
                "delete then create", false);

        assertFalse("the delete applied", business.getFolders().contains(target));
        assertEquals(1, result.skippedOperations().size());
        assertTrue(result.skippedOperations().get(0).contains("Orphan"));
        assertEquals("one new entry", 2, stack.getCommands().length);
    }

    /**
     * A bulk call in which every operation declined keeps its success answer and its per-operation
     * reasons, but leaves no entry and no new version, and says nothing was applied.
     */
    @Test
    public void shouldLeaveNoEntryOrVersion_andSayNothingWasApplied_whenEveryBulkOperationDeclined()
            throws Exception {
        IFolder business = model.getFolder(FolderType.BUSINESS);
        IFolder a = folder(business, "folder-a");
        IFolder b = folder(business, "folder-b");
        race = () -> {
            actor(a, "in-a");
            actor(b, "in-b");
        };
        String version = accessor.getModelVersion();

        Map<String, Object> response = bulkOnTheWire(List.of(
                Map.of("tool", "delete-folder", "params", Map.of("folderId", "folder-a", "force", false)),
                Map.of("tool", "delete-folder", "params", Map.of("folderId", "folder-b", "force", false))));

        Map<?, ?> result = (Map<?, ?>) response.get("result");
        assertEquals("both declined: " + result, 2, ((List<?>) result.get("skippedOperations")).size());
        assertEquals(Boolean.TRUE, result.get("nothingApplied"));
        assertEquals(Boolean.FALSE, result.get("modelChanged"));
        assertSame(business, a.eContainer());
        assertSame(business, b.eContainer());
        assertSame("the undo entry is still the earlier one", earlier, stack.getUndoCommand());
        assertEquals(1, stack.getCommands().length);
        assertFalse("the model is not marked changed", stack.isDirty());
        assertEquals("no new version", version, accessor.getModelVersion());
        List<?> nextSteps = (List<?>) response.get("nextSteps");
        assertTrue(nextSteps.toString(), nextSteps.contains(
                "Nothing was applied (see skippedOperations), so there is nothing to undo."));
        assertFalse(nextSteps.toString(), nextSteps.toString().contains("can be undone"));
    }

    @Test
    public void shouldKeepTheEntryAndBumpTheVersion_whenOneBulkOperationRanAndTheOtherDeclined()
            throws Exception {
        IFolder business = model.getFolder(FolderType.BUSINESS);
        IFolder a = folder(business, "folder-a");
        IFolder b = folder(business, "folder-b");
        race = () -> actor(a, "in-a");
        String version = accessor.getModelVersion();

        Map<String, Object> response = bulkOnTheWire(List.of(
                Map.of("tool", "delete-folder", "params", Map.of("folderId", "folder-a", "force", false)),
                Map.of("tool", "delete-folder", "params", Map.of("folderId", "folder-b", "force", false))));

        Map<?, ?> result = (Map<?, ?>) response.get("result");
        assertEquals("fixture check: one declined: " + result, 1,
                ((List<?>) result.get("skippedOperations")).size());
        assertFalse(result.toString(), result.containsKey("nothingApplied"));
        assertEquals(Boolean.TRUE, result.get("modelChanged"));
        assertFalse("the other delete applied", business.getFolders().contains(b));
        assertEquals("one new entry", 2, stack.getCommands().length);
        assertTrue(stack.isDirty());
        assertEquals("one new version", Long.parseLong(version) + 1, Long.parseLong(accessor.getModelVersion()));
        assertTrue(response.get("nextSteps").toString(),
                response.get("nextSteps").toString().contains("can be undone as a single unit"));
    }

    /**
     * A revert that fails cannot vouch for anything, so the call ends in an error that says the model may be
     * partly changed, and pushes nothing.
     */
    @Test
    public void shouldFailSayingTheModelMayBePartlyChanged_whenTheRevertThrows() {
        CompoundCommand command = new CompoundCommand("Write then decline");
        command.add(new Command("Write") {
            @Override
            public void execute() {
                actor(model.getFolder(FolderType.BUSINESS), "written");
            }

            @Override
            public void undo() {
                throw new IllegalStateException("revert failed");
            }
        });
        command.add(new AgentAuthoredCompoundCommandTest.DecliningCommand());

        try {
            dispatcher.dispatchImmediateOrDecline(command);
            fail("a change that could not be put back must not report success");
        } catch (MutationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("may be in the model"));
            assertFalse(e.getMessage().contains("Nothing was changed"));
            assertTrue("the revert's own failure is kept", e.getCause() instanceof IllegalStateException);
            String sent = ((McpSchema.TextContent) HandlerUtils.buildMutationError(new ResponseFormatter(), e)
                    .content().get(0)).text();
            assertTrue(sent, sent.contains("\"code\":\"MUTATION_FAILED\""));
            assertTrue(sent, sent.contains("\"message\":\"Mutation failed: This change stopped partway"));
        }

        assertSame("nothing pushed", earlier, stack.getUndoCommand());
        assertEquals(1, stack.getCommands().length);
    }

    @Test
    public void shouldBuildAnEntryThatKeepsItsPartialWrite_onceTheCallReturned() {
        dispatcher.dispatchImmediateOrDecline(new Command("Nothing") { });

        assertKeepsItsPartialWrite(dispatcher.agentEntry(writeThenDecline("after-return")), "after-return");
    }

    @Test
    public void shouldBuildAnEntryThatKeepsItsPartialWrite_onceTheCallThrew() {
        race = () -> {
            throw new IllegalStateException("race");
        };
        try {
            dispatcher.dispatchImmediateOrDecline(new Command("Nothing") { });
            fail("fixture check: the dispatch throws");
        } catch (IllegalStateException expected) {
            // the flag must still be cleared
        }
        race = null;

        assertKeepsItsPartialWrite(dispatcher.agentEntry(writeThenDecline("after-throw")), "after-throw");
    }

    private void assertKeepsItsPartialWrite(AgentAuthoredCompoundCommand entry, String written) {
        MutationDispatcher.executeEntry(stack, entry);

        assertSame("pushed as before", entry, stack.getUndoCommand());
        assertNotNull("the write stays", element(written));
    }

    private Command writeThenDecline(String id) {
        CompoundCommand command = new CompoundCommand("Write then decline");
        command.add(new Command("Write " + id) {
            private IArchimateElement written;

            @Override
            public void execute() {
                written = actor(model.getFolder(FolderType.BUSINESS), id);
            }

            @Override
            public void undo() {
                model.getFolder(FolderType.BUSINESS).getElements().remove(written);
            }
        });
        command.add(new AgentAuthoredCompoundCommandTest.DecliningCommand());
        return command;
    }

    private Map<String, Object> bulkOnTheWire(List<Map<String, Object>> operations) throws Exception {
        CommandRegistry registry = new CommandRegistry();
        HandlerRegistrar.registerAll(accessor, new ResponseFormatter(), registry,
                new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS));
        McpServerFeatures.SyncToolSpecification spec = registry.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals("bulk-mutate"))
                .findFirst()
                .orElseThrow();
        McpSchema.CallToolResult result = spec.callHandler().apply(null, new McpSchema.CallToolRequest(
                "bulk-mutate", Map.of("operations", operations, "description", "two deletes")));
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertFalse(text, Boolean.TRUE.equals(result.isError()));
        return new ObjectMapper().readValue(text, new TypeReference<Map<String, Object>>() { });
    }

    private void assertRefusedWithEveryReason(ModelAccessException e) {
        List<String> reasons = CommitSkippableCommand.collectSkipReasons(dispatched.get(0));
        assertFalse("fixture check: something declined", reasons.isEmpty());
        assertEquals(ErrorCode.MUTATION_FAILED, e.getErrorCode());
        assertEquals(String.join("; ", reasons), e.getMessage());
        assertEquals("Nothing was changed. Read the model again and repeat the call if it is still wanted.",
                e.getSuggestedCorrection());
    }

    private void assertPutBack(List<Map<String, String>> raced, String version) {
        assertEquals("fixture check: the race ran", 1, raced.size());
        MergeElementsCommandTest.assertSnapshot("the model is as the race left it", raced.get(0),
                MergeElementsCommandTest.snapshot(model, Set.of()));
        assertSame("the undo entry is still the earlier one", earlier, stack.getUndoCommand());
        assertEquals(1, stack.getCommands().length);
        assertFalse("the model is not marked changed", stack.isDirty());
        assertEquals("no new version", version, accessor.getModelVersion());
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

    private IProfile profile(String name) {
        IProfile profile = factory.createProfile();
        profile.setName(name);
        profile.setConceptType("BusinessActor");
        model.getProfiles().add(profile);
        return profile;
    }

    private IArchimateElement element(String id) {
        return (IArchimateElement) com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, id);
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
