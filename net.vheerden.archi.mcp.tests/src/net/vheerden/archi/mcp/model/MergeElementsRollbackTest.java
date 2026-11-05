package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.common.notify.Notifier;
import org.eclipse.emf.common.notify.impl.AdapterImpl;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.util.EContentAdapter;
import org.eclipse.gef.commands.Command;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.IProperties;
import com.archimatetool.model.IProperty;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.MergeClusterRequest;

/**
 * What {@code merge-elements} leaves behind when a write fails part-way: the model as it was before
 * the call, including the write that failed, and a reason that says whether the undo succeeded.
 *
 * <p>Faults are injected the way an open editor would raise them, by a listener that throws on a
 * notification. EMF notifies after it has written, so the write that throws has already been made.
 * The sweeps throw on each notification of a full merge in turn, on a fresh model each time, and
 * list every one that is not undone rather than stopping at the first.</p>
 */
public class MergeElementsRollbackTest {

    private static final String UNDONE = "merge-elements stopped and undid its own changes: ";

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private IArchimateModel model;

    // ---- every part-made state -----------------------------------------------------------------

    /**
     * Boxes, relationship ends, a dropped self-relationship with its connections, documentation,
     * union properties, the merged-from record and the duplicates' removal.
     */
    @Test
    public void shouldUndoTheWriteThatFailed_whenAListenerThrowsPartWayThroughAnyStep() {
        sweep(this::buildMerge, MergeElementsOperations.options(null, null, true, false),
                List.of("ADD box-w.children box-s1", "SET r1.source t", "REMOVE box-s1.sourceConnections conn-self",
                        ".elements self", "SET t.documentation", "ADD t.properties", ".elements s1"),
                cluster("t", "s1", "s2"), cluster("u", "v"));
    }

    /**
     * The folds of parallel relationships: a survivor's name and documentation filled, a connection
     * re-pointed, connections that hung on a removed one put on new ends, and the removals.
     */
    @Test
    public void shouldUndoTheWriteThatFailed_whenAListenerThrowsPartWayThroughAnyFold() {
        sweep(this::buildFolds, MergeElementsOperations.options(null, null, false, true),
                List.of("SET to-x.name serves", "MOVE box-s1.sourceConnections conn-s1-x",
                        "SET conn-on-twin.target conn-s1-x", "SET note-link.target conn-s1-x",
                        "REMOVE box-s1.sourceConnections conn-twin", ".elements s1-x-twin", ".elements s2"),
                cluster("t", "s1", "s2"));
    }

    /**
     * @param reached a fragment of one notification each kind of step sends, so that a fixture
     *        which stops producing a step fails here rather than leaving it unswept
     */
    private void sweep(Runnable fixture, MergeElementsPlan.Options options, List<String> reached,
            MergeClusterRequest... clusters) {
        List<String> seen = notifications(fixture, options, clusters);
        List<String> unreached = reached.stream()
                .filter(fragment -> seen.stream().noneMatch(each -> each.contains(fragment))).toList();
        assertEquals("precondition: the merge sends these notifications; it sent " + seen, List.of(), unreached);
        List<String> wrong = new ArrayList<>();
        for (int k = 1; k <= seen.size(); k++) {
            newModel();
            fixture.run();
            Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());
            Map<String, String> ends = transientLists();
            MergeElementsCommand command = command(options, clusters);
            Counting failing = new Counting(k);
            model.eAdapters().add(failing);
            try {
                command.execute();
            } finally {
                failing.detach(model);
            }
            List<String> problems = new ArrayList<>();
            if (!failing.fired) {
                problems.add("the listener never threw");
            }
            if (command.getResult() != null) {
                problems.add("a result was kept");
            }
            if (!(UNDONE + "IllegalStateException").equals(command.getSkipReason())) {
                problems.add("reason " + command.getSkipReason());
            }
            Map<String, String> after = MergeElementsCommandTest.snapshot(model, Set.of());
            if (!before.equals(after)) {
                problems.add("model differs: " + differences(before, after));
            }
            if (!ends.equals(transientLists())) {
                problems.add("relationship or diagram reference lists differ: "
                        + differences(ends, transientLists()));
            }
            command.undo();
            if (!after.equals(MergeElementsCommandTest.snapshot(model, Set.of()))) {
                problems.add("the stack's undo then changed the model");
            }
            if (!problems.isEmpty()) {
                wrong.add("#" + k + " " + seen.get(k - 1) + ": " + String.join("; ", problems));
            }
        }
        assertTrue(wrong.size() + " of " + seen.size() + " notifications left the model changed:\n  "
                + String.join("\n  ", wrong), wrong.isEmpty());
    }

    /** Every notification a clean run of the merge sends, described. */
    private List<String> notifications(Runnable fixture, MergeElementsPlan.Options options,
            MergeClusterRequest... clusters) {
        newModel();
        fixture.run();
        MergeElementsCommand command = command(options, clusters);
        Counting counting = new Counting(0);
        model.eAdapters().add(counting);
        try {
            command.execute();
        } finally {
            counting.detach(model);
        }
        assertNull(command.getSkipReason());
        return counting.seen;
    }

    /**
     * Sees every notification in the model, including those of objects added while it listens, and
     * throws once, on the {@code k}-th; zero never throws. It keeps listening to an object taken out
     * of the model, so that a connection's own writes between leaving its ends and coming back are
     * seen too.
     */
    private static final class Counting extends EContentAdapter {
        private final int k;
        private final List<String> seen = new ArrayList<>();
        private boolean fired;
        private boolean detaching;

        void detach(IArchimateModel model) {
            detaching = true;
            model.eAdapters().remove(this);
        }

        @Override
        protected void removeAdapter(Notifier notifier) {
            if (detaching) {
                super.removeAdapter(notifier);
            }
        }

        Counting(int k) {
            this.k = k;
        }

        @Override
        public void notifyChanged(Notification n) {
            super.notifyChanged(n);
            if (n.isTouch()) {
                return;
            }
            seen.add(describe(n));
            if (seen.size() == k && !fired) {
                fired = true;
                throw new IllegalStateException();
            }
        }
    }

    // ---- a revert that fails, an Error, an immediate call --------------------------------------

    /**
     * A revert that fails does not stop the ones recorded before it, and the reason says the merge
     * could not be fully undone instead of claiming it was.
     */
    @Test
    public void shouldRunEveryRevert_whenOneOfThemFails_andSaySo() {
        newModel();
        IArchimateDiagramModel view = view("view-a");
        IArchimateElement t = actor("t");
        IArchimateElement s = actor("s");
        IDiagramModelArchimateObject first = place(view, s, "box-1");
        IDiagramModelArchimateObject second = place(view, s, "box-2");
        place(view, t, "box-t");
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());
        // Throws on the second box taken out of the view, and on every later re-add of that box.
        AdapterImpl failing = new AdapterImpl() {
            private int removes;
            private Object held;

            @Override
            public void notifyChanged(Notification n) {
                if (n.getEventType() == Notification.REMOVE && ++removes == 2) {
                    held = n.getOldValue();
                    throw new IllegalStateException("listener");
                }
                if (n.getEventType() == Notification.ADD && held != null && n.getNewValue() == held) {
                    throw new IllegalStateException("listener");
                }
            }
        };
        view.eAdapters().add(failing);
        MergeElementsCommand command = command(MergeElementsOperations.options(null, null, false, false),
                cluster("t", "s"));

        try {
            command.execute();
        } finally {
            view.eAdapters().remove(failing);
        }

        assertNull(command.getResult());
        assertEquals("merge-elements stopped and could not undo all of its own changes: listener; "
                + "undoing then failed with: listener", command.getSkipReason());
        assertSame("the box moved first is back, although a later revert failed", s, first.getArchimateElement());
        assertSame(s, second.getArchimateElement());
        assertEquals(List.of(first, second), view.getChildren().subList(0, 2));
        MergeElementsCommandTest.assertSnapshot("every write is undone", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
    }

    /** Undoing its own writes in full counts as changing nothing; a partial undo does not. */
    @Test
    public void shouldSayItChangedNothing_whenItUndidItsOwnWrites_butNotWhenItCouldNotUndoAll() {
        newModel();
        buildMerge();
        MergeElementsCommand undone = command(MergeElementsOperations.options(null, null, true, false),
                cluster("t", "s1", "s2"), cluster("u", "v"));
        Counting first = new Counting(1);
        model.eAdapters().add(first);
        try {
            undone.execute();
        } finally {
            first.detach(model);
        }
        assertEquals(UNDONE + "IllegalStateException", undone.getSkipReason());
        assertTrue(undone.changedNothing());

        newModel();
        IArchimateDiagramModel view = view("view-a");
        IArchimateElement t = actor("t");
        IArchimateElement s = actor("s");
        place(view, s, "box-1");
        place(view, s, "box-2");
        place(view, t, "box-t");
        AdapterImpl failing = new AdapterImpl() {
            private int removes;
            private Object held;

            @Override
            public void notifyChanged(Notification n) {
                if (n.getEventType() == Notification.REMOVE && ++removes == 2) {
                    held = n.getOldValue();
                    throw new IllegalStateException("listener");
                }
                if (n.getEventType() == Notification.ADD && held != null && n.getNewValue() == held) {
                    throw new IllegalStateException("listener");
                }
            }
        };
        view.eAdapters().add(failing);
        MergeElementsCommand partial = command(MergeElementsOperations.options(null, null, false, false),
                cluster("t", "s"));
        try {
            partial.execute();
        } finally {
            view.eAdapters().remove(failing);
        }
        assertTrue(partial.getSkipReason(), partial.getSkipReason().startsWith(
                "merge-elements stopped and could not undo all"));
        assertFalse(partial.changedNothing());
    }

    /**
     * Only a partial undo leaves changes it could not undo: a full undo, a decline before any write
     * and a run that wrote do not, and each run starts from a clean answer.
     */
    @Test
    public void shouldSayItLeftChanges_onlyWhenItCouldNotUndoAllOfItsOwnWrites() {
        newModel();
        IArchimateDiagramModel view = view("view-a");
        IArchimateElement t = actor("t");
        IArchimateElement s = actor("s");
        place(view, s, "box-1");
        place(view, s, "box-2");
        place(view, t, "box-t");
        MergeElementsCommand command = command(MergeElementsOperations.options(null, null, false, false),
                cluster("t", "s"));
        assertFalse("not before it has run", command.leftChangesItCouldNotUndo());

        AdapterImpl failing = faultOnSecondRemove(true);
        view.eAdapters().add(failing);
        try {
            command.execute();
        } finally {
            view.eAdapters().remove(failing);
        }
        assertTrue(command.getSkipReason(), command.getSkipReason().startsWith(
                "merge-elements stopped and could not undo all"));
        assertTrue("a partial undo", command.leftChangesItCouldNotUndo());

        command.execute();
        assertNull("fixture check: the second run wrote", command.getSkipReason());
        assertFalse("a later run starts from a clean answer", command.leftChangesItCouldNotUndo());

        newModel();
        view = view("view-a");
        t = actor("t");
        s = actor("s");
        place(view, s, "box-1");
        place(view, s, "box-2");
        place(view, t, "box-t");
        MergeElementsCommand undone = command(MergeElementsOperations.options(null, null, false, false),
                cluster("t", "s"));
        AdapterImpl once = faultOnSecondRemove(false);
        view.eAdapters().add(once);
        try {
            undone.execute();
        } finally {
            view.eAdapters().remove(once);
        }
        assertTrue(undone.getSkipReason(), undone.getSkipReason().startsWith(UNDONE));
        assertFalse("a full undo", undone.leftChangesItCouldNotUndo());

        MergeElementsCommand declined = command(MergeElementsOperations.options(null, null, false, false),
                cluster("t", "s"));
        model.getFolder(FolderType.BUSINESS).getElements().remove(s);
        declined.execute();
        assertTrue(declined.getSkipReason(), declined.getSkipReason().startsWith("merge-elements wrote nothing"));
        assertFalse("a decline before any write", declined.leftChangesItCouldNotUndo());
    }

    /**
     * Throws on the second remove from the view it listens to and, when {@code onReAdd}, on every later
     * re-add of that object, so that the merge's own undo of it fails too.
     */
    private static AdapterImpl faultOnSecondRemove(boolean onReAdd) {
        return new AdapterImpl() {
            private int removes;
            private Object held;

            @Override
            public void notifyChanged(Notification n) {
                if (n.getEventType() == Notification.REMOVE && ++removes == 2) {
                    held = n.getOldValue();
                    throw new IllegalStateException("listener");
                }
                if (onReAdd && n.getEventType() == Notification.ADD && held != null && n.getNewValue() == held) {
                    throw new IllegalStateException("listener");
                }
            }
        };
    }

    /** An Error part-way is not swallowed, but what was written is undone before it goes on. */
    @Test
    public void shouldUndoItsOwnWrites_andRethrow_whenAWriteFailsWithAnError() {
        newModel();
        buildMerge();
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());
        Map<String, String> ends = transientLists();
        IFolder business = model.getFolder(FolderType.BUSINESS);
        Error listener = new Error("listener");
        AdapterImpl failing = new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                if (n.getEventType() == Notification.REMOVE && n.getOldValue() instanceof IArchimateElement) {
                    throw listener;
                }
            }
        };
        business.eAdapters().add(failing);
        MergeElementsCommand command = command(MergeElementsOperations.options(null, null, true, false),
                cluster("t", "s1", "s2"), cluster("u", "v"));

        try {
            command.execute();
            fail("the Error must reach the caller");
        } catch (Error e) {
            assertSame(listener, e);
        } finally {
            business.eAdapters().remove(failing);
        }

        assertNull(command.getResult());
        MergeElementsCommandTest.assertSnapshot("rolled back", before, MergeElementsCommandTest.snapshot(model, Set.of()));
        assertEquals(ends, transientLists());
    }

    /**
     * An Error from a revert does not stop the reverts recorded before it, and reaches the caller
     * with the failure that started the rollback suppressed on it.
     */
    @Test
    public void shouldRunEveryRevert_andRethrow_whenARevertFailsWithAnError() {
        newModel();
        buildMerge();
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());
        Map<String, String> ends = transientLists();
        IFolder business = model.getFolder(FolderType.BUSINESS);
        IllegalStateException write = new IllegalStateException("write");
        Error revert = new Error("revert");
        AdapterImpl failing = new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                if (n.getEventType() == Notification.REMOVE && n.getOldValue() instanceof IArchimateElement) {
                    throw write;
                }
                if (n.getEventType() == Notification.ADD && n.getNewValue() instanceof IArchimateElement) {
                    throw revert;
                }
            }
        };
        business.eAdapters().add(failing);
        MergeElementsCommand command = command(MergeElementsOperations.options(null, null, true, false),
                cluster("t", "s1", "s2"), cluster("u", "v"));

        try {
            command.execute();
            fail("the Error must reach the caller");
        } catch (Error e) {
            assertSame(revert, e);
            assertEquals(List.of(write), List.of(e.getSuppressed()));
        } finally {
            business.eAdapters().remove(failing);
        }

        assertNull(command.getResult());
        MergeElementsCommandTest.assertSnapshot("every revert ran", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
        assertEquals(ends, transientLists());
        command.undo();
        MergeElementsCommandTest.assertSnapshot("the stack's undo is then a no-op", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
    }

    /**
     * When a revert fails with an exception and an earlier-recorded one then fails with an Error, the
     * Error reaches the caller, with the revert's exception and then the write's suppressed on it.
     */
    @Test
    public void shouldRethrowTheRevertsError_withEarlierFailuresSuppressed_whenARevertFailsWithAnErrorAfterAnother() {
        IllegalStateException write = new IllegalStateException("write");
        IllegalStateException revert = new IllegalStateException("revert");
        Error lastRevert = new Error("last revert");

        Error e = mergeWhileTwoRevertsFail(write, revert, lastRevert);

        assertSame(lastRevert, e);
        assertEquals(List.of(revert, write), List.of(e.getSuppressed()));
        assertEquals(0, revert.getSuppressed().length);
        assertEquals(0, write.getSuppressed().length);
    }

    /**
     * When a revert fails with an Error and an earlier-recorded one then fails with an exception, the
     * first Error stays the one that reaches the caller, with the later exception and then the write's
     * suppressed on it.
     */
    @Test
    public void shouldRethrowTheRevertsError_withLaterFailuresSuppressed_whenARevertFailsWithAnExceptionAfterIt() {
        IllegalStateException write = new IllegalStateException("write");
        Error revert = new Error("revert");
        IllegalStateException lastRevert = new IllegalStateException("last revert");

        Error e = mergeWhileTwoRevertsFail(write, revert, lastRevert);

        assertSame(revert, e);
        assertEquals(List.of(lastRevert, write), List.of(e.getSuppressed()));
        assertEquals(0, lastRevert.getSuppressed().length);
        assertEquals(0, write.getSuppressed().length);
    }

    /**
     * When two reverts fail with an Error, the first one to fail reaches the caller, with the second
     * and then the write's exception suppressed on it.
     */
    @Test
    public void shouldRethrowTheFirstRevertsError_withTheSecondSuppressed_whenTwoRevertsFailWithAnError() {
        IllegalStateException write = new IllegalStateException("write");
        Error revert = new Error("revert");
        Error lastRevert = new Error("last revert");

        Error e = mergeWhileTwoRevertsFail(write, revert, lastRevert);

        assertSame(revert, e);
        assertEquals(List.of(lastRevert, write), List.of(e.getSuppressed()));
        assertEquals(0, lastRevert.getSuppressed().length);
        assertEquals(0, write.getSuppressed().length);
    }

    /**
     * A listener that throws one Error instance on a write and again on its revert gets that same
     * instance back, not a failure to suppress an exception on itself.
     */
    @Test
    public void shouldRethrowTheSameError_whenTheWriteAndItsRevertFailWithIt() {
        newModel();
        buildMerge();
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());
        IFolder business = model.getFolder(FolderType.BUSINESS);
        Error listener = new Error("listener");
        AdapterImpl failing = new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                if (n.getOldValue() instanceof IArchimateElement && n.getEventType() == Notification.REMOVE
                        || n.getNewValue() instanceof IArchimateElement && n.getEventType() == Notification.ADD) {
                    throw listener;
                }
            }
        };
        business.eAdapters().add(failing);
        MergeElementsCommand command = command(MergeElementsOperations.options(null, null, true, false),
                cluster("t", "s1", "s2"), cluster("u", "v"));

        try {
            command.execute();
            fail("the Error must reach the caller");
        } catch (Error e) {
            assertSame(listener, e);
            assertEquals(0, e.getSuppressed().length);
        } finally {
            business.eAdapters().remove(failing);
        }

        MergeElementsCommandTest.assertSnapshot("every revert ran", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
    }

    /**
     * A listener that throws the write's exception again from a revert, before a later revert fails
     * with an Error, finds that exception suppressed on the Error once.
     */
    @Test
    public void shouldSuppressTheWritesFailureOnce_whenARevertRethrowsIt_andALaterRevertFailsWithAnError() {
        IllegalStateException write = new IllegalStateException("write");
        Error lastRevert = new Error("last revert");

        Error e = mergeWhileRevertsFail(write, write, lastRevert);

        assertSame(lastRevert, e);
        assertEquals(List.of(write), List.of(e.getSuppressed()));
        assertEquals(0, write.getSuppressed().length);
    }

    /**
     * When the write fails with an Error, a revert with another Error and a later revert with the
     * write's Error again, the write's Error reaches the caller with the revert's suppressed on it,
     * and the revert's Error does not hold the write's in turn.
     */
    @Test
    public void shouldNotSuppressTheThrownErrorOnTheRevertsError_whenALaterRevertRethrowsTheWritesError() {
        Error write = new Error("write");
        Error revert = new Error("revert");

        Error e = mergeWhileRevertsFail(write, revert, write);

        assertSame(write, e);
        assertEquals(List.of(revert), List.of(e.getSuppressed()));
        assertEquals(0, revert.getSuppressed().length);
    }

    /**
     * A revert's exception that a listener throws again from a later revert stays suppressed only
     * where it first landed, on the failure of the revert before it.
     */
    @Test
    public void shouldSuppressARevertsFailureOnce_whenALaterRevertRethrowsIt() {
        IllegalStateException write = new IllegalStateException("write");
        IllegalStateException first = new IllegalStateException("first revert");
        IllegalStateException second = new IllegalStateException("second revert");
        Error error = new Error("error");

        Error e = mergeWhileRevertsFail(write, first, second, error, second);

        assertSame(error, e);
        assertEquals(List.of(first, write), List.of(e.getSuppressed()));
        assertEquals(List.of(second), List.of(first.getSuppressed()));
        assertEquals(0, second.getSuppressed().length);
        assertEquals(0, write.getSuppressed().length);
    }

    /**
     * A revert that fails with the write's own exception, and nothing else, still counts as a failed
     * undo: the merge says it could not undo all of its changes.
     */
    @Test
    public void shouldSayItCouldNotUndoAll_whenTheOnlyRevertFailureIsTheWritesExceptionAgain() {
        newModel();
        buildMerge();
        IFolder business = model.getFolder(FolderType.BUSINESS);
        IllegalStateException write = new IllegalStateException("write");
        List<Object> removed = new ArrayList<>();
        List<Object> reAddsThrown = new ArrayList<>();
        AdapterImpl failing = new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                if (n.getEventType() == Notification.REMOVE && n.getOldValue() instanceof IArchimateElement) {
                    removed.add(n.getOldValue());
                    if (removed.size() == 2) {
                        throw write;
                    }
                }
                if (n.getEventType() == Notification.ADD && removed.size() == 2
                        && n.getNewValue() == removed.get(1)) {
                    reAddsThrown.add(n.getNewValue());
                    throw write;
                }
            }
        };
        business.eAdapters().add(failing);
        MergeElementsCommand command = command(MergeElementsOperations.options(null, null, true, false),
                cluster("t", "s1", "s2"), cluster("u", "v"));

        try {
            command.execute();
        } finally {
            business.eAdapters().remove(failing);
        }

        assertEquals("fixture check: the last re-add threw the write's exception",
                List.of(removed.get(1)), reAddsThrown);
        assertEquals("merge-elements stopped and could not undo all of its own changes: write"
                + "; undoing then failed with: write", command.getSkipReason());
        assertTrue(command.leftChangesItCouldNotUndo());
        assertFalse(command.changedNothing());
        assertNull(command.getResult());
        assertEquals(0, write.getSuppressed().length);
    }

    /** On an immediate call the honest reason reaches the caller as a failed mutation. */
    @Test
    public void shouldAnswerMutationFailed_withTheReason_andLeaveTheModelUnchanged_whenAnImmediateMergeFails() {
        newModel();
        buildMerge();
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());
        Map<String, String> ends = transientLists();
        IFolder business = model.getFolder(FolderType.BUSINESS);
        AdapterImpl failing = new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                if (n.getEventType() == Notification.REMOVE && n.getOldValue() instanceof IArchimateElement) {
                    throw new IllegalStateException();
                }
            }
        };
        business.eAdapters().add(failing);
        MergeElementsCommandTest.StubEditorModelManager models = new MergeElementsCommandTest.StubEditorModelManager();
        models.setModels(List.of(model));
        ArchiModelAccessorImpl accessor = createAccessor(models);
        ModelAccessException refused = null;
        try {
            accessor.mergeElements("rollback-session", List.of(cluster("t", "s1", "s2"), cluster("u", "v")),
                    null, null, true, false);
        } catch (ModelAccessException e) {
            refused = e;
        } finally {
            business.eAdapters().remove(failing);
            accessor.dispose();
        }

        assertTrue("the call must fail", refused != null);
        assertEquals(ErrorCode.MUTATION_FAILED, refused.getErrorCode());
        assertEquals(UNDONE + "IllegalStateException", refused.getMessage());
        MergeElementsCommandTest.assertSnapshot("rolled back", before, MergeElementsCommandTest.snapshot(model, Set.of()));
        assertEquals(ends, transientLists());
    }

    // ---- fixtures ------------------------------------------------------------------------------

    /**
     * Merges {@link #buildMerge()}'s clusters while a Business-folder listener fails the removal of the
     * second duplicate with {@code write}, then its re-add (the first revert to run) with
     * {@code revert} and the first duplicate's re-add (a later revert) with {@code lastRevert}. Checks
     * the listener fired where intended and every revert ran, and returns the Error the caller got.
     */
    private Error mergeWhileTwoRevertsFail(IllegalStateException write, Throwable revert, Throwable lastRevert) {
        return mergeWhileRevertsFail(write, revert, lastRevert);
    }

    /**
     * Merges {@link #buildMerge()}'s clusters, with one Business duplicate of {@code t} per revert
     * ({@code s3} and on added as needed), while a Business-folder listener fails the last duplicate's
     * removal with {@code write} and then each re-add, latest first, with the next of {@code reverts}.
     * Checks the listener fired where intended and every revert ran, and returns the Error the caller
     * got.
     */
    private Error mergeWhileRevertsFail(Throwable write, Throwable... reverts) {
        int duplicates = reverts.length;
        newModel();
        buildMerge();
        String[] sources = new String[duplicates];
        for (int i = 0; i < duplicates; i++) {
            sources[i] = "s" + (i + 1);
            if (i >= 2) {
                actor(sources[i]);
            }
        }
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());
        Map<String, String> ends = transientLists();
        IFolder business = model.getFolder(FolderType.BUSINESS);
        List<Object> removed = new ArrayList<>();
        List<Object> reAddsThrown = new ArrayList<>();
        AdapterImpl failing = new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                if (n.getEventType() == Notification.REMOVE && n.getOldValue() instanceof IArchimateElement) {
                    removed.add(n.getOldValue());
                    if (removed.size() == duplicates) {
                        throw unchecked(write);
                    }
                }
                if (n.getEventType() == Notification.ADD && removed.size() == duplicates
                        && removed.contains(n.getNewValue()) && reAddsThrown.size() < duplicates) {
                    reAddsThrown.add(n.getNewValue());
                    throw unchecked(reverts[reAddsThrown.size() - 1]);
                }
            }
        };
        business.eAdapters().add(failing);
        MergeElementsCommand command = command(MergeElementsOperations.options(null, null, true, false),
                cluster("t", sources), cluster("u", "v"));

        Error caught;
        try {
            command.execute();
            throw new IllegalStateException("the Error must reach the caller");
        } catch (Error e) {
            caught = e;
        } finally {
            business.eAdapters().remove(failing);
        }

        assertEquals("fixture check: every duplicate removed", duplicates, removed.size());
        List<Object> latestFirst = new ArrayList<>(removed);
        Collections.reverse(latestFirst);
        assertEquals("fixture check: every re-add failed, latest first", latestFirst, reAddsThrown);
        assertNull(command.getResult());
        MergeElementsCommandTest.assertSnapshot("every revert ran", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
        assertEquals(ends, transientLists());
        return caught;
    }

    /** {@code failure} as something a listener can throw: it is a RuntimeException or an Error. */
    private static RuntimeException unchecked(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        return (RuntimeException) failure;
    }

    /** Two clusters across two views, as in the merge's exact-undo case. */
    private void buildMerge() {
        IArchimateDiagramModel viewA = view("view-a");
        IArchimateDiagramModel viewB = view("view-b");
        IArchimateElement t = actor("t");
        IArchimateElement s1 = actor("s1");
        IArchimateElement s2 = actor("s2");
        IArchimateElement u = component("u");
        IArchimateElement v = component("v");
        IArchimateElement w = component("w");
        t.setDocumentation("target");
        s1.setDocumentation("one");
        property(t, "tier", "gold");
        property(s1, "tier", "silver");
        property(s1, "region", "emea");
        property(s2, "owner", "ann");
        IArchimateRelationship r1 = association("r1", s1, w);
        association("r2", w, s2);
        IArchimateRelationship self = association("self", s1, t);
        IArchimateRelationship onSelf = association("on-self", w, self);
        IArchimateRelationship across = association("across", s2, v);
        association("parallel", t, w);
        IDiagramModelArchimateObject group = place(viewA, w, "box-w");
        IDiagramModelArchimateObject boxS1 = place(group, s1, "box-s1");
        place(boxS1, u, "box-u-in-s1");
        IDiagramModelArchimateObject boxT = place(viewA, t, "box-t");
        IDiagramModelArchimateObject boxW2 = place(viewA, w, "box-w2");
        bendpoint(connect(r1, boxS1, boxW2, "conn-r1"), 7, 8);
        IDiagramModelArchimateConnection cs = connect(self, boxS1, boxT, "conn-self");
        bendpoint(cs, 1, 1);
        connect(onSelf, boxW2, cs, "conn-on-self");
        IDiagramModelArchimateObject boxV = place(viewB, v, "box-v");
        IDiagramModelArchimateObject boxS2 = place(viewB, s2, "box-s2");
        place(viewB, u, "box-u");
        connect(across, boxS2, boxV, "conn-across");
    }

    /**
     * Folds of every kind in one cluster: a connection re-pointed and one removed, a relationship
     * and two connections that hang on a removed one, a pair kept for its name, and an attachment
     * between two folded relationships.
     */
    private void buildFolds() {
        IArchimateDiagramModel viewA = view("view-a");
        IArchimateDiagramModel viewB = view("view-b");
        IArchimateElement t = actor("t");
        IArchimateElement s1 = actor("s1");
        IArchimateElement s2 = actor("s2");
        IArchimateElement x = component("x");
        IArchimateElement y = component("y");
        IArchimateElement z = component("z");
        IArchimateRelationship toX = association("to-x", t, x);
        IArchimateRelationship s1x = association("s1-x", s1, x);
        s1x.setName("serves");
        s1x.setDocumentation("docs");
        property(s1x, "k", "v");
        IArchimateRelationship s1xTwin = association("s1-x-twin", s1, x);
        IArchimateRelationship s2x = association("s2-x", s2, x);
        s2x.setName("differs");
        IArchimateRelationship s1z = association("s1-z", s1, z);
        association("to-z", t, z);
        IArchimateRelationship onTwin = association("on-twin", y, s1xTwin);
        association("on-survivor", y, toX);
        association("between", s1x, s1z);

        IDiagramModelArchimateObject boxS1 = place(viewA, s1, "box-s1");
        IDiagramModelArchimateObject boxX = place(viewA, x, "box-x");
        IDiagramModelArchimateObject boxY = place(viewA, y, "box-y");
        bendpoint(connect(s1x, boxS1, boxX, "conn-s1-x"), 5, 6);
        IDiagramModelArchimateConnection twin = connect(s1xTwin, boxS1, boxX, "conn-twin");
        connect(onTwin, boxY, twin, "conn-on-twin");
        IDiagramModelNote note = factory.createDiagramModelNote();
        note.setId("note");
        viewA.getChildren().add(note);
        IDiagramModelConnection noteLink = factory.createDiagramModelConnection();
        noteLink.setId("note-link");
        noteLink.connect(note, twin);
        connect(s2x, place(viewB, s2, "box-s2"), place(viewB, x, "box-x2"), "conn-s2-x");
        connect(s1z, place(viewB, s1, "box-s1-b"), place(viewB, z, "box-z"), "conn-s1-z");
    }

    // ---- helpers -------------------------------------------------------------------------------

    private void newModel() {
        model = factory.createArchimateModel();
        model.setId("model-rollback");
        model.setDefaults();
    }

    private MergeElementsCommand command(MergeElementsPlan.Options options, MergeClusterRequest... clusters) {
        return (MergeElementsCommand) MergeElementsOperations.prepare(model, List.of(clusters), options).command();
    }

    /**
     * Each concept's source and target relationships and the diagram components that show it, as
     * sets of ids. None of these is saved, so the structural snapshot cannot see a relationship left
     * off its ends' lists or a box still registered on the element it no longer shows.
     */
    private Map<String, String> transientLists() {
        Map<String, String> out = new TreeMap<>();
        model.eAllContents().forEachRemaining(each -> {
            if (each instanceof IArchimateConcept concept) {
                out.put(concept.getId(), ids(concept.getSourceRelationships()) + " / "
                        + ids(concept.getTargetRelationships()) + " / "
                        + ids(concept.getReferencingDiagramComponents()));
            }
        });
        return out;
    }

    private static Set<String> ids(List<? extends IIdentifier> objects) {
        Set<String> out = new TreeSet<>();
        objects.forEach(each -> out.add(each.getId()));
        return out;
    }

    private static String differences(Map<String, String> expected, Map<String, String> actual) {
        Set<String> keys = new TreeSet<>(expected.keySet());
        keys.addAll(actual.keySet());
        List<String> out = new ArrayList<>();
        for (String key : keys) {
            if (!java.util.Objects.equals(expected.get(key), actual.get(key))) {
                out.add(key);
            }
        }
        return out.toString();
    }

    private static String describe(Notification n) {
        String feature = n.getFeature() instanceof EStructuralFeature f ? f.getName() : String.valueOf(n.getFeature());
        String kind = switch (n.getEventType()) {
            case Notification.ADD -> "ADD";
            case Notification.REMOVE -> "REMOVE";
            case Notification.SET -> "SET";
            case Notification.MOVE -> "MOVE";
            default -> "event " + n.getEventType();
        };
        Object value = n.getEventType() == Notification.REMOVE ? n.getOldValue() : n.getNewValue();
        return kind + " " + name(n.getNotifier()) + "." + feature + " " + name(value);
    }

    private static String name(Object object) {
        if (object instanceof IIdentifier identified && identified.getId() != null) {
            return identified.getId();
        }
        if (object instanceof Notifier notifier) {
            return notifier.getClass().getSimpleName();
        }
        return String.valueOf(object);
    }

    private IArchimateDiagramModel view(String id) {
        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId(id);
        view.setName(id);
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        return view;
    }

    private IArchimateElement actor(String id) {
        IArchimateElement element = factory.createBusinessActor();
        element.setId(id);
        element.setName("Customer");
        model.getFolder(FolderType.BUSINESS).getElements().add(element);
        return element;
    }

    private IArchimateElement component(String id) {
        IArchimateElement element = factory.createApplicationComponent();
        element.setId(id);
        element.setName(id);
        model.getFolder(FolderType.APPLICATION).getElements().add(element);
        return element;
    }

    private IArchimateRelationship association(String id, IArchimateConcept source, IArchimateConcept target) {
        IArchimateRelationship relationship = factory.createAssociationRelationship();
        relationship.setId(id);
        relationship.connect(source, target);
        model.getFolder(FolderType.RELATIONS).getElements().add(relationship);
        return relationship;
    }

    private IProperty property(IProperties owner, String key, String value) {
        IProperty property = factory.createProperty();
        property.setKey(key);
        property.setValue(value);
        owner.getProperties().add(property);
        return property;
    }

    private IDiagramModelArchimateObject place(IDiagramModelContainer parent, IArchimateElement element, String id) {
        IDiagramModelArchimateObject box = factory.createDiagramModelArchimateObject();
        box.setId(id);
        box.setArchimateElement(element);
        box.setBounds(0, 0, 120, 55);
        parent.getChildren().add(box);
        return box;
    }

    private IDiagramModelArchimateConnection connect(IArchimateRelationship relationship, IConnectable source,
            IConnectable target, String id) {
        IDiagramModelArchimateConnection connection = factory.createDiagramModelArchimateConnection();
        connection.setId(id);
        connection.setArchimateRelationship(relationship);
        connection.connect(source, target);
        return connection;
    }

    private IDiagramModelConnection bendpoint(IDiagramModelConnection connection, int x, int y) {
        IDiagramModelBendpoint bendpoint = factory.createDiagramModelBendpoint();
        bendpoint.setStartX(x);
        bendpoint.setStartY(y);
        connection.getBendpoints().add(bendpoint);
        return connection;
    }

    private static MergeClusterRequest cluster(String targetId, String... sourceIds) {
        return new MergeClusterRequest(targetId, List.of(sourceIds));
    }

    private ArchiModelAccessorImpl createAccessor(MergeElementsCommandTest.StubEditorModelManager models) {
        IArchimateModel testModel = model;
        MutationDispatcher dispatcher = new MutationDispatcher(() -> testModel) {
            @Override
            public void dispatchImmediate(Command command) {
                command.execute();
            }

            @Override
            protected void dispatchCommand(Command command) {
                command.execute();
            }
        };
        dispatcher.setApprovalModeProvider(() -> false);
        return new ArchiModelAccessorImpl(models, dispatcher);
    }
}
