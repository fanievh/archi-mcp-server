package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.response.dto.ElementTypeChangeRequest;

/**
 * What an agent's dispatch leaves on a real GEF {@link CommandStack}: one entry for a command that
 * wrote, and none for one that declined and certified it changed nothing, or for one that declined
 * partway in an entry built to roll back. The entry is built by
 * {@link MutationDispatcher#agentEntry} and executed by {@link MutationDispatcher#executeEntry}, the
 * two steps production runs on the UI thread, so no {@code Display} is needed.
 */
public class AgentAuthoredCompoundCommandTest {

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private IArchimateModel model;
    private MutationDispatcher dispatcher;
    private CommandStack stack;
    private Command earlier;

    @Before
    public void setUp() {
        model = factory.createArchimateModel();
        model.setId("model-agent-entry");
        model.setDefaults();
        dispatcher = new MutationDispatcher(() -> model);
        stack = new CommandStack();
        // One ordinary entry first, saved, so "the stack is as it was" is not read off an empty stack.
        AgentAuthoredCompoundCommand entry = dispatcher.agentEntry(add(model.getFolder(FolderType.BUSINESS),
                "earlier"));
        MutationDispatcher.executeEntry(stack, entry);
        stack.markSaveLocation();
        earlier = stack.getUndoCommand();
        assertSame(entry, earlier);
    }

    @Test
    public void shouldPushNoEntry_whenTheCommandDeclinedBeforeWriting() {
        actor(model.getFolder(FolderType.BUSINESS), "a");
        ChangeElementTypeCommand command = (ChangeElementTypeCommand) new ChangeElementTypeOperations(
                sessionId -> model, () -> null, () -> { }, (source, target, relationship) -> true, type -> 0)
                .prepare(model, List.of(new ElementTypeChangeRequest("a", "BusinessRole")), null).command();
        model.getFolder(FolderType.BUSINESS).getElements().remove(element("a"));
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());

        MutationDispatcher.executeEntry(stack, dispatcher.agentEntry(command));

        assertNotNull("fixture check: the command declined", command.getSkipReason());
        MergeElementsCommandTest.assertSnapshot("nothing written", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
        assertSame("the undo entry is still the earlier one", earlier, stack.getUndoCommand());
        assertEquals(1, stack.getCommands().length);
        assertFalse("the model is not marked changed", stack.isDirty());
    }

    @Test
    public void shouldPushNoEntry_whenAMoveDeclinedBecauseItsTargetWentUnderAnotherLayer() {
        IFolder business = model.getFolder(FolderType.BUSINESS);
        IFolder target = folder(business, "target");
        IArchimateElement mover = actor(business, "mover");
        MoveToFolderCommand move = new MoveToFolderCommand(mover, false, business,
                business.getElements().indexOf(mover), target);
        model.getFolder(FolderType.APPLICATION).getFolders().add(target);
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());

        MutationDispatcher.executeEntry(stack, dispatcher.agentEntry(move));

        assertNotNull("fixture check: the move declined", move.getSkipReason());
        MergeElementsCommandTest.assertSnapshot("nothing written", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
        assertSame("the undo entry is still the earlier one", earlier, stack.getUndoCommand());
        assertEquals(1, stack.getCommands().length);
        assertFalse("the model is not marked changed", stack.isDirty());
    }

    @Test
    public void shouldPushTheEntry_whenAFolderDeleteDeclinedAfterItsContentsWent() {
        IFolder parent = model.getFolder(FolderType.BUSINESS);
        IFolder ops = folder(parent, "ops");
        IArchimateElement gone = actor(ops, "gone");
        IFolder inner = folder(ops, "inner");
        Command removeGone = remove(ops, gone);
        DeleteFolderCommand innerDelete = new DeleteFolderCommand(inner, ops, 0, List.of());
        DeleteFolderCommand delete = new DeleteFolderCommand(ops, parent, parent.getFolders().indexOf(ops),
                List.of(removeGone, innerDelete));
        actor(inner, "late"); // the inner folder now holds something it was not prepared to delete
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());

        MutationDispatcher.executeEntry(stack, dispatcher.agentEntry(delete));

        assertNotNull("fixture check: the delete declined", delete.getSkipReason());
        assertTrue("fixture check: the contents went first", !ops.getElements().contains(gone));
        AgentAuthoredCompoundCommand pushed = (AgentAuthoredCompoundCommand) stack.getUndoCommand();
        assertSame("what ran stays undoable", delete, pushed.getDelegate());
        stack.undo();
        MergeElementsCommandTest.assertSnapshot("one undo restores it", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
    }

    @Test
    public void shouldPushNoEntry_whenEveryCommandInACompoundDeclinedBeforeWriting() {
        CompoundCommand command = new CompoundCommand("Two moves");
        MoveToFolderCommand first = declinedMove("first");
        MoveToFolderCommand second = declinedMove("second");
        command.add(first);
        command.add(second);
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());

        MutationDispatcher.executeEntry(stack, dispatcher.agentEntry(command));

        assertNotNull("fixture check: the first move declined", first.getSkipReason());
        assertNotNull("fixture check: the second move declined", second.getSkipReason());
        MergeElementsCommandTest.assertSnapshot("nothing written", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
        assertSame("the undo entry is still the earlier one", earlier, stack.getUndoCommand());
        assertEquals(1, stack.getCommands().length);
        assertFalse("the model is not marked changed", stack.isDirty());
    }

    @Test
    public void shouldPushTheEntry_whenOneCommandInACompoundRanAndTheOtherDeclined() {
        CompoundCommand command = new CompoundCommand("Add then move");
        command.add(add(model.getFolder(FolderType.BUSINESS), "ran"));
        MoveToFolderCommand move = declinedMove("mover");
        command.add(move);

        MutationDispatcher.executeEntry(stack, dispatcher.agentEntry(command));

        assertNotNull("fixture check: the move declined", move.getSkipReason());
        assertSame(command, ((AgentAuthoredCompoundCommand) stack.getUndoCommand()).getDelegate());
        assertEquals(2, stack.getCommands().length);
        assertTrue(stack.isDirty());
        assertNotNull("the write stays", element("ran"));
    }

    @Test
    public void shouldPushTheEntry_whenTheCommandRan() {
        Command command = add(model.getFolder(FolderType.BUSINESS), "ran");

        MutationDispatcher.executeEntry(stack, dispatcher.agentEntry(command));

        assertSame(command, ((AgentAuthoredCompoundCommand) stack.getUndoCommand()).getDelegate());
        assertEquals(2, stack.getCommands().length);
        assertTrue(stack.isDirty());
    }

    @Test
    public void shouldPushNoEntry_andUndoTheWrite_whenARollBackEntryDeclinedAfterWriting() {
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());

        MutationDispatcher.executeEntry(stack, new AgentAuthoredCompoundCommand(writeThenDecline("written"), null,
                true));

        MergeElementsCommandTest.assertSnapshot("the write is undone", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
        assertSame("the undo entry is still the earlier one", earlier, stack.getUndoCommand());
        assertEquals(1, stack.getCommands().length);
        assertFalse("the model is not marked changed", stack.isDirty());
    }

    @Test
    public void shouldPushTheEntry_andKeepTheWrite_whenADefaultEntryDeclinedAfterWriting() {
        Command command = writeThenDecline("written");

        MutationDispatcher.executeEntry(stack, dispatcher.agentEntry(command));

        assertSame(command, ((AgentAuthoredCompoundCommand) stack.getUndoCommand()).getDelegate());
        assertNotNull("the write stays", element("written"));
        assertTrue(stack.isDirty());
    }

    @Test
    public void shouldThrowToTheCaller_andPushNothing_whenTheRollBackFails() {
        CompoundCommand command = new CompoundCommand("Write then decline");
        command.add(new Command("Write") {
            @Override
            public void execute() {
                actor(model.getFolder(FolderType.BUSINESS), "written");
            }

            @Override
            public void undo() {
                throw new IllegalStateException("undo failed");
            }
        });
        command.add(new DecliningCommand());

        try {
            MutationDispatcher.executeEntry(stack, new AgentAuthoredCompoundCommand(command, null, true));
            fail("a failed roll-back must reach the caller");
        } catch (MutationException e) {
            assertTrue(e.getCause() instanceof IllegalStateException);
        }

        assertSame("nothing pushed", earlier, stack.getUndoCommand());
        assertEquals(1, stack.getCommands().length);
    }

    @Test
    public void shouldUndoTheEntry_andThrow_andPushNothing_whenADefaultEntryLeftChangesItCouldNotUndo() {
        LeavingCommand command = new LeavingCommand(false);

        AgentAuthoredCompoundCommand.ChangesLeft left = changesLeft(dispatcher.agentEntry(command));

        assertEquals("its undo ran once", 1, command.undos);
        assertEquals(List.of(LeavingCommand.REASON), left.getReasons());
        assertEquals(LeavingCommand.REASON + ". What it had written could not all be put back, so part of it may "
                + "still be in the model, and Undo will not remove it; it does not mark the model as changed "
                + "either. Check the model in Archi before going on.",
                left.getMessage());
        assertNothingPushed();
    }

    @Test
    public void shouldUndoTheEntry_andThrow_andPushNothing_whenARollBackEntryLeftChangesItCouldNotUndo() {
        LeavingCommand command = new LeavingCommand(false);

        AgentAuthoredCompoundCommand.ChangesLeft left = changesLeft(new AgentAuthoredCompoundCommand(command, null,
                true));

        assertEquals("its undo ran once", 1, command.undos);
        assertEquals(List.of(LeavingCommand.REASON), left.getReasons());
        assertTrue(left.getMessage(), left.getMessage().startsWith(LeavingCommand.REASON + ". What it had written "
                + "could not all be put back"));
        assertNothingPushed();
    }

    @Test
    public void shouldUndoEveryPartOfTheEntry_whenOnePartLeftChangesItCouldNotUndo() {
        CompoundCommand command = new CompoundCommand("Add then leave");
        command.add(add(model.getFolder(FolderType.BUSINESS), "ran"));
        LeavingCommand leaving = new LeavingCommand(false);
        command.add(leaving);

        AgentAuthoredCompoundCommand.ChangesLeft left = changesLeft(dispatcher.agentEntry(command));

        assertEquals(List.of(LeavingCommand.REASON), left.getReasons());
        assertEquals("its undo ran once", 1, leaving.undos);
        assertNull("the write before it is put back", element("ran"));
        assertNothingPushed();
    }

    @Test
    public void shouldStillThrowChangesLeft_andCarryTheUndoFailure_whenPuttingBackTheEntryFails() {
        LeavingCommand command = new LeavingCommand(true);

        AgentAuthoredCompoundCommand.ChangesLeft left = changesLeft(dispatcher.agentEntry(command));

        assertEquals("its undo ran once", 1, command.undos);
        assertTrue("the undo failure is reachable", left.getCause() instanceof IllegalStateException);
        assertEquals(LeavingCommand.REASON + ". Putting back the rest of this change also failed, so more of it may "
                + "be in the model too. What it had written could not all be put back, so part of it may still be "
                + "in the model, and Undo will not remove it; it does not mark the model as changed either. Check "
                + "the model in Archi before going on.",
                left.getMessage());
        assertNothingPushed();
    }

    @Test
    public void shouldSayTheOtherOperationsMayBeInTheModelToo_whenPuttingBackABatchFailed() {
        LeavingCommand command = new LeavingCommand(true);
        AgentAuthoredCompoundCommand.ChangesLeft left = changesLeft(dispatcher.agentEntry(command));

        AgentAuthoredCompoundCommand.ChangesLeft ended = left.inBatch(List.of("Merge — " + LeavingCommand.REASON));

        assertEquals("Merge — " + LeavingCommand.REASON + ". Putting back the other operations in the batch also "
                + "failed, so some of them may be in the model too, and the batch has ended. What it had written "
                + "could not all be put back, so part of it may still be in the model, and Undo will not remove "
                + "it; it does not mark the model as changed either. Check the model in Archi before going on.",
                ended.getMessage());
        assertEquals(List.of(LeavingCommand.REASON), ended.getReasons());
        assertSame("the entry's failure is kept", left, ended.getCause());
    }

    @Test
    public void shouldPutBackWhatRanBefore_andFail_andPushNothing_whenABatchCommandThrowsARuntimeException() {
        assertPutBackWhatRanBefore(() -> new IllegalStateException("thrown"), false);
    }

    @Test
    public void shouldPutBackWhatRanBefore_andFail_andPushNothing_whenABatchCommandThrowsAnError() {
        assertPutBackWhatRanBefore(() -> new AssertionError("thrown"), false);
    }

    @Test
    public void shouldPutBackWhatRanBefore_whenTheBatchSitsInsideACompoundOfItsOwn() {
        assertPutBackWhatRanBefore(() -> new IllegalStateException("thrown"), true);
        assertPutBackWhatRanBefore(() -> new AssertionError("thrown"), true);
    }

    @Test
    public void shouldFailWithTheSingleCallText_andUndoNothing_whenACommandWithNoBatchAroundItThrows() {
        for (boolean rollBack : new boolean[] { false, true }) {
            for (Throwable thrown : new Throwable[] { new IllegalStateException("thrown"),
                    new AssertionError("thrown") }) {
                ThrowingCommand command = new ThrowingCommand(thrown);

                AgentAuthoredCompoundCommand.Failed failed = failed(new AgentAuthoredCompoundCommand(command, null,
                        rollBack));

                assertSame(thrown, failed.getCause());
                assertEquals("This change failed unexpectedly. " + HEDGE, failed.getMessage());
                assertEquals("nothing is undone: " + thrown, 0, command.undos);
                assertNothingPushed();
            }
        }
    }

    @Test
    public void shouldStillFail_andKeepThePutBackFailure_whenPuttingBackWhatRanBeforeFails() {
        IllegalStateException undoFailure = new IllegalStateException("undo failed");
        IllegalStateException thrown = new IllegalStateException("thrown");
        TrackedCompoundCommand batch = new TrackedCompoundCommand("Batch");
        batch.add(new Command("Write") {
            @Override
            public void undo() {
                throw undoFailure;
            }
        });
        batch.add(new ThrowingCommand(thrown));

        AgentAuthoredCompoundCommand.Failed failed = failed(dispatcher.agentEntry(batch));

        assertSame(thrown, failed.getCause());
        assertArrayEquals("the put-back failure is reachable", new Throwable[] { undoFailure },
                thrown.getSuppressed());
        assertEquals("This change failed unexpectedly, and putting back what ran before the failure also failed, "
                + "so more of it may be in the model too. " + HEDGE, failed.getMessage());
        assertNothingPushed();
    }

    @Test
    public void shouldStillThrowChangesLeft_carryingTheError_whenPuttingBackTheEntryThrowsAnError() {
        AssertionError undoFailure = new AssertionError("undo failed");
        CompoundCommand command = new CompoundCommand("Leave");
        command.add(new LeavingCommand(false));
        command.add(new Command("Write") {
            @Override
            public void undo() {
                throw undoFailure;
            }
        });

        AgentAuthoredCompoundCommand.ChangesLeft left = changesLeft(dispatcher.agentEntry(command));

        assertSame(undoFailure, left.getCause());
        assertTrue(left.getMessage(), left.getMessage().contains("Putting back the rest of this change also failed"));
        assertNothingPushed();
    }

    private static final String HEDGE = "Part of what it wrote may still be in the model, and Undo will not remove "
            + "it; it does not mark the model as changed either. Check the model in Archi before going on.";

    /**
     * A batch of {@code [add A, throw, add C]}, run in a default and in a roll-back entry: A is put back, C never
     * ran, the failure carries what was thrown, and nothing is pushed.
     */
    private void assertPutBackWhatRanBefore(java.util.function.Supplier<Throwable> throwing, boolean nested) {
        for (boolean rollBack : new boolean[] { false, true }) {
            Throwable thrown = throwing.get();
            TrackedCompoundCommand batch = new TrackedCompoundCommand("Batch");
            batch.add(add(model.getFolder(FolderType.BUSINESS), "a"));
            batch.add(new ThrowingCommand(thrown));
            batch.add(add(model.getFolder(FolderType.BUSINESS), "c"));
            Command delegate = batch;
            if (nested) {
                CompoundCommand outer = new CompoundCommand("Batch");
                outer.add(batch);
                delegate = outer;
            }
            Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());

            AgentAuthoredCompoundCommand.Failed failed = failed(new AgentAuthoredCompoundCommand(delegate, null,
                    rollBack));

            String arm = thrown + (rollBack ? " in a roll-back entry" : " in a default entry");
            assertSame(arm, thrown, failed.getCause());
            assertEquals(arm, 1, failed.getFailedAt());
            assertNull(arm + ": A is put back", element("a"));
            assertNull(arm + ": C never ran", element("c"));
            MergeElementsCommandTest.assertSnapshot(arm, before, MergeElementsCommandTest.snapshot(model, Set.of()));
            assertEquals(arm, "This change failed unexpectedly. " + HEDGE, failed.getMessage());
            assertNothingPushed();
        }
    }

    private AgentAuthoredCompoundCommand.Failed failed(AgentAuthoredCompoundCommand entry) {
        try {
            MutationDispatcher.executeEntry(stack, entry);
        } catch (AgentAuthoredCompoundCommand.Failed failed) {
            return failed;
        }
        throw new AssertionError("a command that throws must fail the dispatch");
    }

    /** Throws {@code thrown} as it runs; counts its undos. */
    private static final class ThrowingCommand extends Command {
        private final Throwable thrown;
        int undos;

        ThrowingCommand(Throwable thrown) {
            super("Throw");
            this.thrown = thrown;
        }

        @Override
        public void execute() {
            if (thrown instanceof Error error) {
                throw error;
            }
            throw (RuntimeException) thrown;
        }

        @Override
        public void undo() {
            undos++;
        }
    }

    private AgentAuthoredCompoundCommand.ChangesLeft changesLeft(AgentAuthoredCompoundCommand entry) {
        try {
            MutationDispatcher.executeEntry(stack, entry);
        } catch (AgentAuthoredCompoundCommand.ChangesLeft left) {
            return left;
        }
        throw new AssertionError("a command that left changes it could not undo must fail the dispatch");
    }

    private void assertNothingPushed() {
        assertSame("the undo entry is still the earlier one", earlier, stack.getUndoCommand());
        assertEquals(1, stack.getCommands().length);
        assertFalse("the model is not marked changed", stack.isDirty());
    }

    /** Declines as it runs and reports that part of what it wrote is still in the model; counts its undos. */
    static final class LeavingCommand extends Command implements CommitSkippableCommand {
        static final String REASON = "stopped and could not undo all of its own changes";
        private final boolean undoFails;
        private String skipReason;
        private boolean left;
        int undos;

        LeavingCommand(boolean undoFails) {
            super("Leave");
            this.undoFails = undoFails;
        }

        @Override
        public void execute() {
            skipReason = REASON;
            left = true;
        }

        @Override
        public void undo() {
            undos++;
            if (undoFails) {
                throw new IllegalStateException("undo failed");
            }
        }

        @Override
        public String getSkipReason() {
            return skipReason;
        }

        @Override
        public boolean leftChangesItCouldNotUndo() {
            return left;
        }
    }

    /** A move prepared into a folder that has since gone under another layer, so it declines as it runs. */
    private MoveToFolderCommand declinedMove(String id) {
        IFolder business = model.getFolder(FolderType.BUSINESS);
        IFolder target = folder(business, "target-" + id);
        IArchimateElement mover = actor(business, id);
        MoveToFolderCommand move = new MoveToFolderCommand(mover, false, business,
                business.getElements().indexOf(mover), target);
        model.getFolder(FolderType.APPLICATION).getFolders().add(target);
        return move;
    }

    private Command writeThenDecline(String id) {
        CompoundCommand command = new CompoundCommand("Write then decline");
        command.add(add(model.getFolder(FolderType.BUSINESS), id));
        command.add(new DecliningCommand());
        return command;
    }

    /** Declines after running, without vouching that nothing was written. */
    static final class DecliningCommand extends Command implements CommitSkippableCommand {
        private String skipReason;

        DecliningCommand() {
            super("Decline");
        }

        @Override
        public void execute() {
            skipReason = "declined after the write";
        }

        @Override
        public String getSkipReason() {
            return skipReason;
        }
    }

    private Command add(IFolder folder, String id) {
        IArchimateElement element = factory.createBusinessActor();
        element.setId(id);
        return new Command("Add " + id) {
            @Override
            public void execute() {
                folder.getElements().add(element);
            }

            @Override
            public void undo() {
                folder.getElements().remove(element);
            }
        };
    }

    private static Command remove(IFolder folder, IArchimateElement element) {
        int index = folder.getElements().indexOf(element);
        return new Command("Remove " + element.getId()) {
            @Override
            public void execute() {
                folder.getElements().remove(element);
            }

            @Override
            public void undo() {
                folder.getElements().add(index, element);
            }
        };
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

    private IArchimateElement element(String id) {
        return (IArchimateElement) com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, id);
    }
}
