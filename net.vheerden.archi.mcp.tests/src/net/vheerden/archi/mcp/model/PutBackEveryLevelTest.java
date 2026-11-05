package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.model.commands.NonNotifyingCompoundCommand;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;

/**
 * A command that throws anywhere in an agent's dispatch leaves nothing behind that ran before it: the run
 * opens GEF's and Archi's own compounds, at every level, and undoes what completed, latest first. A compound
 * of any other class runs as one command, so the run cannot see inside it. A notifying compound the run
 * opened always gets its end notice, after the put-back, so Archi's views go on showing model changes.
 */
public class PutBackEveryLevelTest {

    private static final String START = "IEditorModelManager.ecore.events.start";
    private static final String END = "IEditorModelManager.ecore.events.end";

    private final List<String> log = new ArrayList<>();
    private AgentAuthoredCompoundCommand.Notices archi;
    private CommandStack stack;
    private Command earlier;

    @Before
    public void setUp() {
        archi = AgentAuthoredCompoundCommand.notices;
        AgentAuthoredCompoundCommand.notices = (source, property) -> log.add(
                (START.equals(property) ? "start " : END.equals(property) ? "end " : property + " ")
                        + source.getLabel());
        stack = new CommandStack();
        MutationDispatcher.executeEntry(stack, new AgentAuthoredCompoundCommand(new Command("Earlier") { }));
        stack.markSaveLocation();
        earlier = stack.getUndoCommand();
    }

    @After
    public void tearDown() {
        AgentAuthoredCompoundCommand.notices = archi;
    }

    // ---- what is put back ----------------------------------------------------------------------------

    @Test
    public void shouldUndoWhatRanAtEveryLevel_latestFirst_whenACommandDeepInTheTreeThrows() {
        for (boolean rollBack : new boolean[] { false, true }) {
            for (Throwable thrown : new Throwable[] { new IllegalStateException("thrown"),
                    new AssertionError("thrown") }) {
                log.clear();
                Command tree = plain("Outer", plain("Left", rec("A"), plain("Inner", rec("B"), throwing(thrown),
                        rec("C"))), rec("D"));

                AgentAuthoredCompoundCommand.Failed failed = failed(new AgentAuthoredCompoundCommand(tree, null,
                        rollBack));

                String arm = thrown + (rollBack ? " in a roll-back entry" : " in a default entry");
                assertEquals(arm, List.of("x A", "x B", "u B", "u A"), log);
                assertSame(arm, thrown, failed.getCause());
                assertEquals(arm, "This change failed unexpectedly. " + HEDGE, failed.getMessage());
                assertNothingPushed();
            }
        }
    }

    @Test
    public void shouldUndoACompletedCompound_asOneCommand_whenALaterSiblingThrows() {
        Command tree = plain("Outer", plain("Done", rec("A"), rec("B")), throwing(new IllegalStateException("thrown")));

        failed(new AgentAuthoredCompoundCommand(tree));

        assertEquals("GEF's compound undoes its own commands in reverse", List.of("x A", "x B", "u B", "u A"), log);
    }

    @Test
    public void shouldCarryOnPuttingBack_andKeepTheFailure_whenAnUndoInTheTreeFails() {
        IllegalStateException undoFailure = new IllegalStateException("undo failed");
        IllegalStateException thrown = new IllegalStateException("thrown");
        Command tree = plain("Outer", rec("A"), plain("Inner", rec("B", undoFailure), throwing(thrown)));

        AgentAuthoredCompoundCommand.Failed failed = failed(new AgentAuthoredCompoundCommand(tree));

        assertEquals("A is undone after B's undo failed", List.of("x A", "x B", "u B", "u A"), log);
        assertSame(thrown, failed.getCause());
        assertArrayEquals("the put-back failure is reachable", new Throwable[] { undoFailure },
                thrown.getSuppressed());
        assertEquals("This change failed unexpectedly, and putting back what ran before the failure also failed, "
                + "so more of it may be in the model too. " + HEDGE, failed.getMessage());
        assertNothingPushed();
    }

    @Test
    public void shouldRunACompoundOfAnotherClass_asOneCommand_andLeaveWhatRanInsideIt() {
        OwnRun own = new OwnRun(rec("A"), rec("B"));
        Command tree = plain("Outer", rec("Z"), own);

        AgentAuthoredCompoundCommand.Failed failed = failed(new AgentAuthoredCompoundCommand(tree));

        assertEquals("Z is put back; the run cannot see what ran inside the other compound",
                List.of("x Z", "x A", "u Z"), log);
        assertSame(OwnRun.THROWN, failed.getCause());
    }

    @Test
    public void shouldNameTheBatchOperation_whenACommandInsideItThrows() {
        TrackedCompoundCommand batch = new TrackedCompoundCommand("Batch");
        batch.add(rec("A"));
        batch.add(plain("Bulk", rec("B"), throwing(new IllegalStateException("thrown")), rec("C")));
        batch.add(rec("D"));
        NonNotifyingCompoundCommand commit = new NonNotifyingCompoundCommand("Batch");
        commit.add(batch);

        AgentAuthoredCompoundCommand.Failed failed = failed(new AgentAuthoredCompoundCommand(commit));

        assertEquals(List.of("start Batch", "x A", "x B", "u B", "u A", "end Batch"), log);
        assertEquals("the second operation of the batch failed", 1, failed.getFailedAt());
    }

    @Test
    public void shouldRunUndoAndRedoAsGefDoes_whenNothingThrows() {
        List<String> direct = new ArrayList<>();
        Command expected = sameTree(direct);
        expected.execute();
        expected.undo();
        expected.redo();

        AgentAuthoredCompoundCommand entry = new AgentAuthoredCompoundCommand(sameTree(log));
        MutationDispatcher.executeEntry(stack, entry);
        stack.undo();
        stack.redo();

        assertEquals(direct, log);
        assertSame(entry, stack.getUndoCommand());
    }

    // ---- Archi's notices ----------------------------------------------------------------------------

    @Test
    public void shouldBracketEachNotifyingCompound_asArchiDoes_whenNothingThrows() {
        Command tree = notifying("Outer", rec("A"), notifying("Inner", rec("B")), rec("C"));

        MutationDispatcher.executeEntry(stack, new AgentAuthoredCompoundCommand(tree));

        assertEquals(List.of("start Outer", "x A", "start Inner", "x B", "end Inner", "x C", "end Outer"), log);
    }

    @Test
    public void shouldEndEveryOpenedNotifyingCompound_innermostFirst_afterThePutBack_whenACommandThrows() {
        Command tree = notifying("Outer", rec("A"), notifying("Inner", rec("B"),
                throwing(new IllegalStateException("thrown")), rec("C")), rec("D"));

        failed(new AgentAuthoredCompoundCommand(tree));

        assertEquals(List.of("start Outer", "x A", "start Inner", "x B", "u B", "u A", "end Inner", "end Outer"),
                log);
    }

    @Test
    public void shouldStillFailWithWhatWasThrown_whenAnEndNoticeThrows() {
        AssertionError noticeFailure = new AssertionError("notice");
        AgentAuthoredCompoundCommand.Notices recording = AgentAuthoredCompoundCommand.notices;
        AgentAuthoredCompoundCommand.notices = (source, property) -> {
            recording.fire(source, property);
            if (END.equals(property)) {
                throw noticeFailure;
            }
        };
        IllegalStateException thrown = new IllegalStateException("thrown");
        Command tree = notifying("Outer", rec("A"), notifying("Inner", throwing(thrown)));

        AgentAuthoredCompoundCommand.Failed failed = failed(new AgentAuthoredCompoundCommand(tree));

        assertSame(thrown, failed.getCause());
        assertEquals("every END is still tried", List.of("start Outer", "x A", "start Inner", "u A", "end Inner",
                "end Outer"), log);
        assertNothingPushed();
    }

    /**
     * The run fires the notices Archi's notifying compound fires and otherwise runs it as GEF's compound does.
     * A version of Archi that gives that compound more behaviour of its own must be read again before the run
     * can stand in for it.
     */
    @Test
    public void shouldFindArchisNotifyingCompound_overridingOnlyExecuteUndoAndRedo() {
        java.util.Set<String> declared = new java.util.TreeSet<>();
        for (java.lang.reflect.Method method : NonNotifyingCompoundCommand.class.getDeclaredMethods()) {
            if (!method.isSynthetic()) {
                declared.add(method.getName());
            }
        }

        assertEquals(java.util.Set.of("execute", "redo", "undo"), declared);
        assertSame(CompoundCommand.class, NonNotifyingCompoundCommand.class.getSuperclass());
    }

    // ---- the failure callback ------------------------------------------------------------------------

    @Test
    public void shouldRunTheFailureCallbackOnce_afterThePutBack_whenACommandThrows() {
        Command tree = plain("Outer", rec("A"), throwing(new IllegalStateException("thrown")));

        failed(new AgentAuthoredCompoundCommand(tree, null, false, () -> log.add("failed")));

        assertEquals(List.of("x A", "u A", "failed"), log);
    }

    @Test
    public void shouldNotRunTheFailureCallback_whenNothingThrows() {
        MutationDispatcher.executeEntry(stack, new AgentAuthoredCompoundCommand(rec("A"), null, false,
                () -> log.add("failed")));

        assertEquals(List.of("x A"), log);
    }

    @Test
    public void shouldStillFailWithWhatWasThrown_whenTheFailureCallbackThrows() {
        IllegalStateException thrown = new IllegalStateException("thrown");

        AgentAuthoredCompoundCommand.Failed failed = failed(new AgentAuthoredCompoundCommand(throwing(thrown), null,
                false, () -> {
                    throw new IllegalStateException("callback");
                }));

        assertSame(thrown, failed.getCause());
        assertNothingPushed();
    }

    @Test
    public void shouldRunTheImmediateCallback_inTheCallersModelScope_whenTheEntryRunsOnAnotherThread()
            throws Exception {
        IArchimateModel model = IArchimateFactory.eINSTANCE.createArchimateModel();
        MutationDispatcher dispatcher = new MutationDispatcher(() -> model);
        AtomicBoolean inScope = new AtomicBoolean();
        dispatcher.setOnImmediateDispatchCallback(() -> inScope.set(dispatcher.selection().inScope()));
        dispatcher.selection().enter("default", null);
        AgentAuthoredCompoundCommand entry;
        try {
            entry = dispatcher.agentEntry(throwing(new IllegalStateException("thrown")));
        } finally {
            dispatcher.selection().exit();
        }
        Thread ui = new Thread(() -> {
            try {
                MutationDispatcher.executeEntry(new CommandStack(), entry);
            } catch (AgentAuthoredCompoundCommand.Failed expected) {
                // the failure itself is pinned elsewhere
            }
        });
        ui.start();
        ui.join();

        assertTrue("the version is moved for the model the call acts on", inScope.get());
        assertFalse("the scope does not outlive the callback", dispatcher.selection().inScope());
    }

    // ---- fixtures -----------------------------------------------------------------------------------

    private static final String HEDGE = "Part of what it wrote may still be in the model, and Undo will not remove "
            + "it; it does not mark the model as changed either. Check the model in Archi before going on.";

    private Command sameTree(List<String> into) {
        TrackedCompoundCommand tracked = new TrackedCompoundCommand("Tracked");
        tracked.add(new Rec("D", into, null));
        tracked.add(new Rec("E", into, null));
        CompoundCommand inner = new CompoundCommand("Inner");
        inner.add(new Rec("B", into, null));
        inner.add(new Rec("C", into, null));
        CompoundCommand outer = new CompoundCommand("Outer");
        outer.add(new Rec("A", into, null));
        outer.add(inner);
        outer.add(tracked);
        return outer;
    }

    private AgentAuthoredCompoundCommand.Failed failed(AgentAuthoredCompoundCommand entry) {
        try {
            MutationDispatcher.executeEntry(stack, entry);
        } catch (AgentAuthoredCompoundCommand.Failed failed) {
            return failed;
        }
        throw new AssertionError("a command that throws must fail the dispatch");
    }

    private void assertNothingPushed() {
        assertSame("the undo entry is still the earlier one", earlier, stack.getUndoCommand());
        assertEquals(1, stack.getCommands().length);
        assertFalse("the model is not marked changed", stack.isDirty());
    }

    private static CompoundCommand plain(String label, Command... children) {
        CompoundCommand compound = new CompoundCommand(label);
        for (Command child : children) {
            compound.add(child);
        }
        return compound;
    }

    private static NonNotifyingCompoundCommand notifying(String label, Command... children) {
        NonNotifyingCompoundCommand compound = new NonNotifyingCompoundCommand(label);
        for (Command child : children) {
            compound.add(child);
        }
        return compound;
    }

    private Rec rec(String name) {
        return new Rec(name, log, null);
    }

    private Rec rec(String name, RuntimeException undoFailure) {
        return new Rec(name, log, undoFailure);
    }

    private static Command throwing(Throwable thrown) {
        return new Command("Throw") {
            @Override
            public void execute() {
                if (thrown instanceof Error error) {
                    throw error;
                }
                throw (RuntimeException) thrown;
            }
        };
    }

    /** Records each run, undo and redo by name; an undo can fail. */
    private static final class Rec extends Command {
        private final List<String> into;
        private final RuntimeException undoFailure;

        Rec(String name, List<String> into, RuntimeException undoFailure) {
            super(name);
            this.into = into;
            this.undoFailure = undoFailure;
        }

        @Override
        public void execute() {
            into.add("x " + getLabel());
        }

        @Override
        public void undo() {
            into.add("u " + getLabel());
            if (undoFailure != null) {
                throw undoFailure;
            }
        }

        @Override
        public void redo() {
            into.add("r " + getLabel());
        }
    }

    /** A compound with a run of its own: runs its first command, then throws. Opened, it would not throw. */
    private static final class OwnRun extends CompoundCommand {
        static final IllegalStateException THROWN = new IllegalStateException("own run");

        OwnRun(Command... children) {
            super("Own run");
            for (Command child : children) {
                add(child);
            }
        }

        @Override
        public void execute() {
            ((Command) getCommands().get(0)).execute();
            throw THROWN;
        }
    }
}
