package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.editor.model.commands.NonNotifyingCompoundCommand;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * What an agent's stack entry does when one of its commands fails as it is undone or redone, on a real
 * GEF {@link CommandStack}.
 *
 * <p>GEF drops an entry whose undo or redo throws from both its stacks, so after the throw the history
 * believes the entry is not applied. A failed undo therefore carries on and reverses every other command
 * in it, latest first; a failed redo puts back what it had redone. Archi's notifying compound still gets
 * its end notice. The failure still reaches the stack, as {@link AgentAuthoredCompoundCommand.StackStepFailed},
 * and the undo and redo tools answer with it, naming the entries done before it, and move the version.
 * An entry with nothing failing undoes and redoes exactly as GEF does.</p>
 */
public class AgentEntryUndoRedoFailureTest {

    private static final String SESSION = "agent-entry-undo-redo-failure";

    private final List<String> log = new ArrayList<>();
    private AgentAuthoredCompoundCommand.Notices archi;
    private IArchimateModel model;
    private CommandStack stack;
    private MutationDispatcher dispatcher;

    /** A command that logs each call and throws on the chosen ones. */
    private final class Logged extends Command {
        private final String name;
        private Throwable undoFailure;
        private Throwable redoFailure;

        Logged(String name) {
            super(name);
            this.name = name;
        }

        Logged failingUndo(Throwable failure) {
            undoFailure = failure;
            return this;
        }

        Logged failingRedo(Throwable failure) {
            redoFailure = failure;
            return this;
        }

        @Override
        public void execute() {
            log.add("execute " + name);
        }

        @Override
        public void undo() {
            log.add("undo " + name);
            throwIfSet(undoFailure);
        }

        @Override
        public void redo() {
            log.add("redo " + name);
            throwIfSet(redoFailure);
        }

        private void throwIfSet(Throwable failure) {
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure != null) {
                throw (RuntimeException) failure;
            }
        }
    }

    @Before
    public void setUp() {
        archi = AgentAuthoredCompoundCommand.notices;
        AgentAuthoredCompoundCommand.notices = (source, property) -> log.add(
                (IEditorModelManager.PROPERTY_ECORE_EVENTS_START.equals(property) ? "START " : "END ")
                        + source.getLabel());
        model = IArchimateFactory.eINSTANCE.createArchimateModel();
        model.setDefaults();
        stack = new CommandStack();
        model.setAdapter(CommandStack.class, stack);
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                MutationDispatcher.executeEntry(guardedStack(model, currentDispatchGuard()), agentEntry(command));
            }

            @Override
            protected void dispatchCommand(Command command) {
                dispatchImmediate(command);
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
    }

    @After
    public void tearDown() {
        AgentAuthoredCompoundCommand.notices = archi;
    }

    // ---- an undo that fails carries on ----------------------------------------------------------------

    @Test
    public void shouldLeaveTheEntryInNeitherStack_andAnswerWhatFailed_whenAnImmediateEntrysUndoThrows() {
        AgentAuthoredCompoundCommand first = push(new Logged("first"));
        IllegalStateException failure = new IllegalStateException("internal detail");
        push(new Logged("bad").failingUndo(failure));
        log.clear();

        AgentAuthoredCompoundCommand.StackStepFailed thrown = undoFails();

        assertSame("what was thrown is the cause", failure, thrown.getCause());
        assertSame("the entry below is the undo top", first, stack.getUndoCommand());
        assertNull("the failing entry is not on Redo", stack.getRedoCommand());
        assertEquals(1, stack.getCommands().length);
        assertEquals("Undoing 'bad' failed unexpectedly, and it is no longer on Undo or Redo. Everything else in "
                + "it was reversed, but part of it may still be in the model.", thrown.getMessage());
        assertEquals("Part of this change may still be in the model and Undo will not remove it. Re-read the "
                + "elements with get-element, and the views that showed them, before going on.",
                thrown.getSuggestedCorrection());
        assertFalse("never the internal message", thrown.getMessage().contains("internal detail"));
    }

    @Test
    public void shouldUndoEveryOtherOperation_latestFirst_whenAQueuedOperationsUndoThrows() {
        TrackedCompoundCommand batch = batch(new Logged("q1"), new Logged("q2").failingUndo(new IllegalStateException()),
                new Logged("q3"));
        push(batch);
        log.clear();

        undoFails();

        assertEquals("q1, below the one that failed, is undone too", List.of("undo q3", "undo q2", "undo q1"), log);
        assertNull(stack.getUndoCommand());
        assertNull(stack.getRedoCommand());
    }

    @Test
    public void shouldSayMoreMayBeLeft_andPreferAnError_whenTwoOperationsUndoThrows() {
        IllegalStateException exception = new IllegalStateException("q3");
        AssertionError error = new AssertionError("q1");
        push(batch(new Logged("q1").failingUndo(error), new Logged("q2"), new Logged("q3").failingUndo(exception)));
        log.clear();

        AgentAuthoredCompoundCommand.StackStepFailed thrown = undoFails();

        assertEquals(List.of("undo q3", "undo q2", "undo q1"), log);
        assertSame("the Error is the cause", error, thrown.getCause());
        assertArrayEquals(new Throwable[] { exception }, error.getSuppressed());
        assertEquals("Undoing 'batch' failed unexpectedly, and it is no longer on Undo or Redo. Reversing the "
                + "rest of it also failed, so more of it may still be in the model.", thrown.getMessage());
    }

    @Test
    public void shouldSayMoreMayBeLeft_whenTheOneCommandThatFailedCarriesOnPastFailuresOfItsOwn() {
        IllegalStateException failure = new IllegalStateException("first step");
        failure.addSuppressed(new IllegalStateException("a later step"));
        push(batch(new Logged("q1"), new Logged("q2").failingUndo(failure)));
        log.clear();

        AgentAuthoredCompoundCommand.StackStepFailed thrown = undoFails();

        assertEquals("one command failed, but more than one of its own steps did", List.of("undo q2", "undo q1"), log);
        assertEquals("Undoing 'batch' failed unexpectedly, and it is no longer on Undo or Redo. Reversing the "
                + "rest of it also failed, so more of it may still be in the model.", thrown.getMessage());
    }

    @Test
    public void shouldFireArchisEndNotice_afterAnUndoThatFails() {
        NonNotifyingCompoundCommand notifying = new NonNotifyingCompoundCommand("archi");
        notifying.add(batch(new Logged("q1"), new Logged("q2").failingUndo(new IllegalStateException()),
                new Logged("q3")));
        push(notifying);
        log.clear();

        undoFails();

        assertEquals(List.of("START archi", "undo q3", "undo q2", "undo q1", "END archi"),
                log);
    }

    // ---- a redo that fails puts back --------------------------------------------------------------------

    @Test
    public void shouldPutBackWhatItRedid_andLeaveTheEntryInNeitherStack_whenAQueuedOperationsRedoThrows() {
        push(batch(new Logged("q1"), new Logged("q2").failingRedo(new IllegalStateException()), new Logged("q3")));
        stack.undo();
        log.clear();

        AgentAuthoredCompoundCommand.StackStepFailed thrown = redoFails();

        assertEquals("q3 is never redone; q1 is put back; q2, which failed, is not undone",
                List.of("redo q1", "redo q2", "undo q1"), log);
        assertNull(stack.getUndoCommand());
        assertNull(stack.getRedoCommand());
        assertEquals("Redoing 'batch' failed unexpectedly, and it is no longer on Undo or Redo. What it had "
                + "redone was put back, but part of it may still be in the model.", thrown.getMessage());
    }

    @Test
    public void shouldSayMoreMayBeLeft_whenPuttingBackARedoAlsoFails() {
        Logged q1 = new Logged("q1");
        IllegalStateException failure = new IllegalStateException("q2");
        IllegalStateException putBack = new IllegalStateException("q1");
        push(batch(q1, new Logged("q2").failingRedo(failure)));
        stack.undo();
        q1.failingUndo(putBack);

        AgentAuthoredCompoundCommand.StackStepFailed thrown = redoFails();

        assertSame(failure, thrown.getCause());
        assertArrayEquals(new Throwable[] { putBack }, failure.getSuppressed());
        assertEquals("Redoing 'batch' failed unexpectedly, and it is no longer on Undo or Redo. Putting back what "
                + "it had redone also failed, so more of it may be in the model.", thrown.getMessage());
    }

    @Test
    public void shouldSayMoreMayBeLeft_whenTheRedoThatFailedCouldNotPutItselfBack() {
        IllegalStateException failure = new IllegalStateException("a step");
        failure.addSuppressed(new IllegalStateException("putting that step back"));
        push(batch(new Logged("q1"), new Logged("q2").failingRedo(failure)));
        stack.undo();
        log.clear();

        AgentAuthoredCompoundCommand.StackStepFailed thrown = redoFails();

        assertEquals("q1 is put back", List.of("redo q1", "redo q2", "undo q1"), log);
        assertEquals("Redoing 'batch' failed unexpectedly, and it is no longer on Undo or Redo. Putting back what "
                + "it had redone also failed, so more of it may be in the model.", thrown.getMessage());
    }

    @Test
    public void shouldFireArchisEndNotice_afterARedoThatFails() {
        NonNotifyingCompoundCommand notifying = new NonNotifyingCompoundCommand("archi");
        notifying.add(batch(new Logged("q1"), new Logged("q2").failingRedo(new IllegalStateException()),
                new Logged("q3")));
        push(notifying);
        stack.undo();
        log.clear();

        redoFails();

        assertEquals(List.of("START archi", "redo q1", "redo q2", "undo q1", "END archi"),
                log);
    }

    // ---- nothing fails: exactly what GEF does ------------------------------------------------------------

    @Test
    public void shouldUndoAndRedoAsGefDoes_whenNothingFails_overArchisNotifyingCompound() {
        NonNotifyingCompoundCommand notifying = new NonNotifyingCompoundCommand("archi");
        notifying.add(batch(new Logged("q1"), new Logged("q2"), new Logged("q3")));
        AgentAuthoredCompoundCommand entry = push(notifying);
        log.clear();

        stack.undo();
        assertSame("GEF moves the entry to Redo", entry, stack.getRedoCommand());
        stack.redo();

        assertSame("and back to Undo", entry, stack.getUndoCommand());
        assertEquals(List.of("START archi", "undo q3", "undo q2", "undo q1", "END archi",
                "START archi", "redo q1", "redo q2", "redo q3", "END archi"), log);
    }

    @Test
    public void shouldUndoAndRedoAsGefDoes_whenNothingFails_overABatchCompound() {
        AgentAuthoredCompoundCommand entry = push(batch(new Logged("q1"), new Logged("q2")));
        log.clear();

        stack.undo();
        stack.redo();

        assertSame(entry, stack.getUndoCommand());
        assertEquals(List.of("undo q2", "undo q1", "redo q1", "redo q2"), log);
    }

    // ---- the loops over the stack keep what they did -------------------------------------------------

    @Test
    public void shouldNameTheEntriesUndoneBeforeTheFailure_whenAnUndoOfSeveralStepsFails() {
        AgentAuthoredCompoundCommand e1 = push(new Logged("e1"));
        push(new Logged("e2").failingUndo(new IllegalStateException()));
        AgentAuthoredCompoundCommand e3 = push(new Logged("e3"));
        log.clear();

        try {
            MutationDispatcher.scopedUndo(stack, 3);
            fail("the undo did not fail");
        } catch (AgentAuthoredCompoundCommand.StackStepFailed thrown) {
            assertEquals(List.of("e3"), thrown.getDoneBefore());
            assertTrue(thrown.getMessage(), thrown.getMessage().startsWith("Undid 'e3'. Undoing 'e2' failed "
                    + "unexpectedly, and it is no longer on Undo or Redo."));
        }
        assertEquals("e1 is not reached", List.of("undo e3", "undo e2"), log);
        assertSame(e1, stack.getUndoCommand());
        assertSame(e3, stack.getRedoCommand());
    }

    @Test
    public void shouldNameTheEntriesRedoneBeforeTheFailure_whenARedoOfSeveralStepsFails() {
        push(new Logged("e1"));
        push(new Logged("e2").failingRedo(new IllegalStateException()));
        push(new Logged("e3"));
        MutationDispatcher.scopedUndo(stack, 3);
        log.clear();

        try {
            MutationDispatcher.scopedRedo(stack, 3);
            fail("the redo did not fail");
        } catch (AgentAuthoredCompoundCommand.StackStepFailed thrown) {
            assertEquals(List.of("e1"), thrown.getDoneBefore());
            assertTrue(thrown.getMessage(), thrown.getMessage().startsWith("Redid 'e1'. Redoing 'e2' failed "));
        }
        assertEquals(List.of("redo e1", "redo e2"), log);
    }

    @Test
    public void shouldFailTheCall_namingItsOwnEntriesUndone_whenACallUndoingItsTrialEntriesMeetsAFailure() {
        ArchiModelAccessorImpl accessor = accessor();
        dispatcher.admit(SESSION, "trial", model);
        try {
            dispatcher.dispatchImmediate(new Logged("t1"));
            dispatcher.dispatchImmediate(new Logged("t2").failingUndo(new IllegalStateException()));
            dispatcher.dispatchImmediate(new Logged("t3"));
            log.clear();

            dispatcher.undo(3);
            fail("the call's own undo did not fail");
        } catch (AgentAuthoredCompoundCommand.StackStepFailed thrown) {
            assertEquals(List.of("t3"), thrown.getDoneBefore());
            assertFalse("never a clean revert", thrown.getMessage().contains("Nothing was changed"));
        } finally {
            dispatcher.endCall();
            accessor.dispose();
        }
        assertEquals(List.of("undo t3", "undo t2"), log);
    }

    // ---- the undo and redo tools ------------------------------------------------------------------------

    @Test
    public void shouldAnswerMutationFailed_withTheEntriesUndone_andMoveTheVersion_whenTheUndoToolMeetsAFailure() {
        ArchiModelAccessorImpl accessor = accessor();
        try {
            push(new Logged("e1"));
            push(new Logged("e2").failingUndo(new IllegalStateException()));
            push(new Logged("e3"));
            String version = accessor.getModelVersion();

            try {
                accessor.undo(SESSION, 3);
                fail("the undo tool did not fail");
            } catch (ModelAccessException refused) {
                assertEquals(ErrorCode.MUTATION_FAILED, refused.getErrorCode());
                assertEquals("Undid 'e3'. Undoing 'e2' failed unexpectedly, and it is no longer on Undo or Redo. "
                        + "Everything else in it was reversed, but part of it may still be in the model.",
                        refused.getMessage());
                assertEquals("Part of this change may still be in the model and Undo will not remove it. Re-read "
                        + "the elements with get-element, and the views that showed them, before going on.",
                        refused.getSuggestedCorrection());
            }
            assertNotEquals("the version moves", version, accessor.getModelVersion());
        } finally {
            accessor.dispose();
        }
    }

    @Test
    public void shouldAnswerMutationFailed_andMoveTheVersion_whenTheRedoToolMeetsAFailure() {
        ArchiModelAccessorImpl accessor = accessor();
        try {
            push(new Logged("e1").failingRedo(new IllegalStateException()));
            stack.undo();
            String version = accessor.getModelVersion();

            try {
                accessor.redo(SESSION, 1);
                fail("the redo tool did not fail");
            } catch (ModelAccessException refused) {
                assertEquals(ErrorCode.MUTATION_FAILED, refused.getErrorCode());
                assertEquals("Redoing 'e1' failed unexpectedly, and it is no longer on Undo or Redo. What it had "
                        + "redone was put back, but part of it may still be in the model.", refused.getMessage());
            }
            assertNotEquals("the version moves", version, accessor.getModelVersion());
        } finally {
            accessor.dispose();
        }
    }

    @Test
    public void shouldAnswerAsBefore_whenTheUndoToolMeetsNoFailure() {
        ArchiModelAccessorImpl accessor = accessor();
        try {
            push(new Logged("e1"));
            String version = accessor.getModelVersion();

            assertEquals(List.of("e1"), accessor.undo(SESSION, 1).labels());

            assertNotEquals(version, accessor.getModelVersion());
        } finally {
            accessor.dispose();
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private AgentAuthoredCompoundCommand push(Command command) {
        AgentAuthoredCompoundCommand entry = new AgentAuthoredCompoundCommand(command);
        MutationDispatcher.executeEntry(stack, entry);
        assertSame("fixture check: the entry is on the stack", entry, stack.getUndoCommand());
        return entry;
    }

    private static TrackedCompoundCommand batch(Command... commands) {
        TrackedCompoundCommand batch = new TrackedCompoundCommand("batch");
        for (Command command : commands) {
            batch.add(command);
        }
        return batch;
    }

    private AgentAuthoredCompoundCommand.StackStepFailed undoFails() {
        try {
            stack.undo();
        } catch (AgentAuthoredCompoundCommand.StackStepFailed thrown) {
            return thrown;
        }
        fail("the undo did not fail");
        return null;
    }

    private AgentAuthoredCompoundCommand.StackStepFailed redoFails() {
        try {
            stack.redo();
        } catch (AgentAuthoredCompoundCommand.StackStepFailed thrown) {
            return thrown;
        }
        fail("the redo did not fail");
        return null;
    }

    private ArchiModelAccessorImpl accessor() {
        AutoRouteArmFixture.StubEditorModelManager models = new AutoRouteArmFixture.StubEditorModelManager();
        models.setModels(List.of(model));
        dispatcher.setApprovalModeProvider(() -> false);
        ArchiModelAccessorImpl accessor = new ArchiModelAccessorImpl(models, dispatcher);
        dispatcher.onModelActive(model);
        return accessor;
    }
}
