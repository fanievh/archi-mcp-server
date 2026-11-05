package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * An undo or a redo of recorded steps that fails partway leaves the command not applied, as far as it
 * can: the command stack drops an entry whose undo or redo throws from both stacks, so nothing can
 * reach the command again afterwards.
 *
 * <p>An undo reverts every step, latest first, past one that fails. A redo that fails reverts what it
 * made again, the failing step included, latest first. Both rethrow the first failure, an
 * {@code Error} preferred, with each other failure suppressed on it once. A run with no failure keeps
 * the order it always had.</p>
 */
public class RecordedStepsUndoRedoFailureTest {

    private final List<String> log = new ArrayList<>();

    /** A step that logs each write, and throws on a chosen call. */
    private final class Logged implements RecordedSteps.Step {
        private final String name;
        private int applies;
        private RuntimeException applyFailure;
        private Error applyError;
        private Throwable revertFailure;

        Logged(String name) {
            this.name = name;
        }

        /** Throws {@code failure} on every apply after the first, which is the one {@code record} makes. */
        Logged failingRedo(RuntimeException failure) {
            applyFailure = failure;
            return this;
        }

        Logged failingRedo(Error failure) {
            applyError = failure;
            return this;
        }

        Logged failingRevert(Throwable failure) {
            revertFailure = failure;
            return this;
        }

        @Override
        public void apply() {
            applies++;
            log.add("apply " + name);
            if (applies > 1 && applyFailure != null) {
                throw applyFailure;
            }
            if (applies > 1 && applyError != null) {
                throw applyError;
            }
        }

        @Override
        public void revert() {
            log.add("revert " + name);
            if (revertFailure instanceof Error error) {
                throw error;
            }
            if (revertFailure != null) {
                throw (RuntimeException) revertFailure;
            }
        }
    }

    private RecordedSteps record(Logged... steps) {
        RecordedSteps record = new RecordedSteps();
        for (Logged step : steps) {
            record.apply(step);
        }
        log.clear();
        return record;
    }

    // ---- no failure: the order it always had ----------------------------------------------------------

    @Test
    public void shouldUndoLatestFirst_andRedoInOrder_whenNothingFails() {
        RecordedSteps record = record(new Logged("a"), new Logged("b"), new Logged("c"));

        record.undo();
        record.redo();

        assertEquals(List.of("revert c", "revert b", "revert a", "apply a", "apply b", "apply c"), log);
    }

    // ---- undo carries on ------------------------------------------------------------------------------

    @Test
    public void shouldRevertEveryStep_thenThrowTheFailure_whenOneRevertFailsMidList() {
        IllegalStateException failure = new IllegalStateException("b");
        RecordedSteps record = record(new Logged("a"), new Logged("b").failingRevert(failure), new Logged("c"));

        Throwable thrown = undo(record);

        assertSame(failure, thrown);
        assertEquals("a, before the failing step, is reverted too",
                List.of("revert c", "revert b", "revert a"), log);
        assertArrayEquals(new Throwable[0], failure.getSuppressed());
    }

    @Test
    public void shouldThrowTheFirstFailure_withTheLaterOneSuppressed_whenTwoRevertsFail() {
        IllegalStateException first = new IllegalStateException("c");
        IllegalStateException later = new IllegalStateException("a");
        RecordedSteps record = record(new Logged("a").failingRevert(later), new Logged("b"),
                new Logged("c").failingRevert(first));

        Throwable thrown = undo(record);

        assertSame("the failure met first, latest step first", first, thrown);
        assertArrayEquals(new Throwable[] { later }, first.getSuppressed());
        assertEquals(List.of("revert c", "revert b", "revert a"), log);
    }

    @Test
    public void shouldThrowAnError_inPlaceOfAnEarlierException_whenALaterRevertThrowsOne() {
        IllegalStateException exception = new IllegalStateException("c");
        AssertionError error = new AssertionError("a");
        RecordedSteps record = record(new Logged("a").failingRevert(error), new Logged("b"),
                new Logged("c").failingRevert(exception));

        Throwable thrown = undo(record);

        assertSame(error, thrown);
        assertArrayEquals(new Throwable[] { exception }, error.getSuppressed());
    }

    @Test
    public void shouldSuppressAFailureOnce_whenTwoStepsThrowTheSameOne() {
        IllegalStateException shared = new IllegalStateException("shared");
        IllegalStateException first = new IllegalStateException("c");
        RecordedSteps record = record(new Logged("a").failingRevert(shared), new Logged("b").failingRevert(shared),
                new Logged("c").failingRevert(first));

        Throwable thrown = undo(record);

        assertSame(first, thrown);
        assertArrayEquals(new Throwable[] { shared }, first.getSuppressed());
    }

    // ---- redo puts back -------------------------------------------------------------------------------

    @Test
    public void shouldRevertWhatItRemade_failingStepIncluded_thenThrow_whenARedoFailsMidList() {
        IllegalStateException failure = new IllegalStateException("b");
        RecordedSteps record = record(new Logged("a"), new Logged("b").failingRedo(failure), new Logged("c"));
        record.undo();
        log.clear();

        Throwable thrown = redo(record);

        assertSame(failure, thrown);
        assertEquals("c is never made; b and a are reverted, latest first",
                List.of("apply a", "apply b", "revert b", "revert a"), log);
    }

    @Test
    public void shouldThrowTheRedoFailure_withARevertFailureSuppressed_whenPuttingBackAlsoFails() {
        IllegalStateException failure = new IllegalStateException("b");
        IllegalStateException revertFailure = new IllegalStateException("a");
        Logged a = new Logged("a");
        RecordedSteps record = record(a, new Logged("b").failingRedo(failure), new Logged("c"));
        record.undo();
        a.failingRevert(revertFailure);
        log.clear();

        Throwable thrown = redo(record);

        assertSame(failure, thrown);
        assertArrayEquals(new Throwable[] { revertFailure }, failure.getSuppressed());
        assertEquals(List.of("apply a", "apply b", "revert b", "revert a"), log);
    }

    @Test
    public void shouldThrowARevertError_inPlaceOfTheRedoException_whenPuttingBackThrowsOne() {
        IllegalStateException failure = new IllegalStateException("b");
        AssertionError error = new AssertionError("a");
        Logged a = new Logged("a");
        RecordedSteps record = record(a, new Logged("b").failingRedo(failure));
        record.undo();
        a.failingRevert(error);

        Throwable thrown = redo(record);

        assertSame(error, thrown);
        assertArrayEquals(new Throwable[] { failure }, error.getSuppressed());
    }

    @Test
    public void shouldThrowTheRedoError_whenTheRedoItselfThrowsOne() {
        AssertionError error = new AssertionError("b");
        RecordedSteps record = record(new Logged("a"), new Logged("b").failingRedo(error));
        record.undo();
        log.clear();

        Throwable thrown = redo(record);

        assertSame(error, thrown);
        assertEquals(List.of("apply a", "apply b", "revert b", "revert a"), log);
    }

    private static Throwable undo(RecordedSteps record) {
        try {
            record.undo();
        } catch (RuntimeException | Error e) {
            return e;
        }
        fail("the undo did not throw");
        return null;
    }

    private static Throwable redo(RecordedSteps record) {
        try {
            record.redo();
        } catch (RuntimeException | Error e) {
            return e;
        }
        fail("the redo did not throw");
        return null;
    }
}
