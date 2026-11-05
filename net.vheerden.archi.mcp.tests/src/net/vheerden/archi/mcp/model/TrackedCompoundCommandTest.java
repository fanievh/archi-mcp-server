package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.Test;

/**
 * The batch compound that knows how far it got: a command that throws stops the run, the commands before
 * it can be undone on their own, and a run that completes undoes and redoes exactly as GEF's compound does.
 */
public class TrackedCompoundCommandTest {

    private final List<String> log = new ArrayList<>();

    @Test
    public void shouldRethrowTheSameInstance_andNotRunTheRest_whenACommandThrows() {
        IllegalStateException failure = new IllegalStateException("second");
        TrackedCompoundCommand tracked = tracked(new Recording("a"), new Recording("b", failure, null),
                new Recording("c"));

        assertSame(failure, executeExpectingThrow(tracked));

        assertEquals(List.of("execute a", "execute b"), log);
        assertEquals("the second command threw", 1, tracked.getFailedAt());
    }

    @Test
    public void shouldRethrowAnError_asTheSameInstance() {
        AssertionError failure = new AssertionError("second");
        TrackedCompoundCommand tracked = tracked(new Recording("a"), new Recording("b", failure, null),
                new Recording("c"));

        assertSame(failure, executeExpectingThrow(tracked));

        assertEquals(1, tracked.getFailedAt());
    }

    @Test
    public void shouldUndoOnlyTheCommandsThatCompleted_latestFirst() {
        TrackedCompoundCommand tracked = tracked(new Recording("a"), new Recording("b"),
                new Recording("c", new IllegalStateException("third"), null), new Recording("d"));
        executeExpectingThrow(tracked);
        log.clear();

        assertNull("nothing failed while putting back", tracked.undoCompleted());

        assertEquals("the thrower and the command after it are not undone", List.of("undo b", "undo a"), log);
    }

    @Test
    public void shouldCarryOnPastAFailingUndo_andReturnTheFirstFailure() {
        IllegalStateException bUndo = new IllegalStateException("undo b");
        IllegalStateException aUndo = new IllegalStateException("undo a");
        TrackedCompoundCommand tracked = tracked(new Recording("a", null, aUndo), new Recording("b", null, bUndo),
                new Recording("c", new IllegalStateException("third"), null), new Recording("d"));
        executeExpectingThrow(tracked);
        log.clear();

        Throwable failure = tracked.undoCompleted();

        assertEquals("a is still undone after b's undo failed", List.of("undo b", "undo a"), log);
        assertSame("the first failure, latest first", bUndo, failure);
        assertArrayEquals("the later one is kept on it", new Throwable[] { aUndo }, failure.getSuppressed());
    }

    @Test
    public void shouldNotSuppressAFailureOnItself_whenTwoUndosThrowTheSameInstance() {
        IllegalStateException shared = new IllegalStateException("shared");
        TrackedCompoundCommand tracked = tracked(new Recording("a", null, shared), new Recording("b", null, shared),
                new Recording("c", new IllegalStateException("third"), null));
        executeExpectingThrow(tracked);

        Throwable failure = tracked.undoCompleted();

        assertSame(shared, failure);
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test
    public void shouldUndoAndRedoInTheSameOrderAsAPlainCompound_whenEveryCommandRan() {
        TrackedCompoundCommand tracked = tracked(new Recording("a"), new Recording("b"), new Recording("c"));
        tracked.execute();
        tracked.undo();
        tracked.redo();
        List<String> trackedOrder = List.copyOf(log);
        log.clear();
        CompoundCommand plain = new CompoundCommand("plain");
        plain.add(new Recording("a"));
        plain.add(new Recording("b"));
        plain.add(new Recording("c"));

        plain.execute();
        plain.undo();
        plain.redo();

        assertEquals(log, trackedOrder);
        assertEquals("no command threw", -1, tracked.getFailedAt());
    }

    private TrackedCompoundCommand tracked(Command... commands) {
        TrackedCompoundCommand tracked = new TrackedCompoundCommand("Batch");
        for (Command command : commands) {
            tracked.add(command);
        }
        return tracked;
    }

    private static Throwable executeExpectingThrow(Command command) {
        try {
            command.execute();
        } catch (RuntimeException | Error e) {
            return e;
        }
        fail("the run must stop at the throwing command");
        return null;
    }

    /** Logs each call; throws {@code onExecute} or {@code onUndo} when set. */
    private final class Recording extends Command {
        private final String name;
        private final Throwable onExecute;
        private final RuntimeException onUndo;

        Recording(String name) {
            this(name, null, null);
        }

        Recording(String name, Throwable onExecute, RuntimeException onUndo) {
            super(name);
            this.name = name;
            this.onExecute = onExecute;
            this.onUndo = onUndo;
        }

        @Override
        public void execute() {
            log.add("execute " + name);
            if (onExecute instanceof RuntimeException e) {
                throw e;
            }
            if (onExecute instanceof Error e) {
                throw e;
            }
        }

        @Override
        public void undo() {
            log.add("undo " + name);
            if (onUndo != null) {
                throw onUndo;
            }
        }

        @Override
        public void redo() {
            log.add("redo " + name);
        }
    }
}
