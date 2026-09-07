package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.BulkOperationFailure;
import net.vheerden.archi.mcp.response.dto.BulkOperationResult;

/**
 * A note an earlier operation in the same bulk call created must be addressable as an
 * {@code update-view-object} target — the capability {@code bulk-mutate}'s own description
 * advertises by listing {@code add-note-to-view} among the back-referenceable create tools.
 *
 * <p>It was the only create tool in that published list with no back-reference registry, so the
 * container finder answered null (and could not have answered otherwise — it returns
 * {@code IDiagramModelContainer} and a note is not one). The arm fell through to the id path, where
 * a note whose add command has not executed is still detached and cannot be found — so the call
 * failed with a not-found naming a resolved GUID the caller never typed, and, being
 * all-or-nothing, destroyed the note creation along with it.</p>
 *
 * <p><strong>Fixture precondition — do not remove.</strong> Every case here is a plain
 * {@code bulk-mutate} with NO enclosing {@code begin-batch} window. That is what makes the success
 * cases proof of the ROUTE and not merely of the outcome: the id path is structurally incapable of
 * resolving a detached note, so success is only reachable through the direct prepare. Inside a
 * batch window the queued-object argument would supply the note and the id path would succeed too,
 * silently disarming the discriminator.</p>
 *
 * <p><strong>Also covers the residual this fix does NOT close</strong>, on BOTH sites that carry
 * it: a {@code $N.id} naming a model concept rather than something on a view still reports a
 * not-found, and the advice it carries must stay identical between
 * {@code update-view-object} and {@code update-view-connection}. That pair is asserted against
 * each other, not merely each against a substring.</p>
 *
 * <p><strong>Lane independence.</strong> Every update here either supplies an explicit
 * {@code height} or changes neither text nor width, so the note text re-fit never runs. The re-fit
 * needs a real SWT display and swallows its own failure headless, so an assertion that depended on
 * it would read differently in the two lanes. The re-fit's own live pin lives in the display lane
 * ({@code ArchiModelAccessorImplTest}).</p>
 */
public class BulkBackRefNoteTargetTest {

    private static final String SESSION = "bulk-backref-note-target";

    private IArchimateFactory factory;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private CommandStack stack;
    private IArchimateModel model;
    private IArchimateDiagramModel view;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        StubEditorModelManager stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Bulk BackRef Note Target Fixture");
        model.setId("model-bulk-backref-note");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Target");
        diagrams.getElements().add(view);

        IFolder business = model.getFolder(FolderType.BUSINESS);
        IBusinessActor actor = factory.createBusinessActor();
        actor.setId("actor-1");
        actor.setName("Target Actor");
        business.getElements().add(actor);

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
        // The class javadoc's fixture precondition, ENFORCED rather than left in prose. A future
        // edit that wraps any case here in a begin-batch window would let the id path resolve the
        // note through the queue as well, so every route pin in this class would keep passing while
        // no longer discriminating. A precondition only a reader can check is one a reader can miss.
        assertNull("no test in this class may open a begin-batch window — see the class javadoc",
                dispatcher.getBatchStatus(SESSION).batchStarted());
        if (accessor != null) {
            accessor.dispose();
        }
    }

    /**
     * The capability itself — the exact two-operation shape the defect was reproduced with, in a
     * live Archi, before this was filed. Asserted against a fresh read of the model rather than
     * against the response, so an echo could not pass it.
     */
    @Test
    public void shouldRetitleTheNote_whenTheTargetIsABackReferenceToASameCallAddNote() {
        BulkMutationResult bulk = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("add-note-to-view", op(
                        "viewId", view.getId(), "content", "First",
                        "x", 10, "y", 10, "width", 220, "height", 60)),
                new BulkOperation("update-view-object", op(
                        "viewObjectId", "$0.id",
                        "text", "Second, a much longer body than the first one was",
                        "height", 140))),
                "create a note then retitle it by back-reference", false);

        assertTrue("both operations must succeed", bulk.allSucceeded());
        String noteId = bulk.operations().get(0).entityId();
        IDiagramModelObject live = find(view, noteId);
        assertNotNull("the note must exist in the model", live);
        assertTrue("the object the id names must be a note", live instanceof IDiagramModelNote);
        assertEquals("the model must hold the new content, not the old one",
                "Second, a much longer body than the first one was",
                ((IDiagramModelNote) live).getContent());
        assertEquals("and the geometry alongside it", 140, live.getBounds().getHeight());
    }

    /**
     * Geometry alone, with no text, is the other half of the target role: a note created earlier in
     * the call must be movable and resizable by the same {@code $N.id}.
     */
    @Test
    public void shouldMoveAndResizeTheNote_whenOnlyGeometryIsSuppliedOnABackReference() {
        BulkMutationResult bulk = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("add-note-to-view", op(
                        "viewId", view.getId(), "content", "Placed",
                        "x", 0, "y", 0, "width", 180, "height", 90)),
                new BulkOperation("update-view-object", op(
                        "viewObjectId", "$0.id", "x", 60, "y", 70, "height", 150))),
                "create a note then move it by back-reference", false);

        assertTrue("both operations must succeed", bulk.allSucceeded());
        IDiagramModelObject live = find(view, bulk.operations().get(0).entityId());
        assertNotNull("the note must exist in the model", live);
        assertEquals("x must be the one the second operation asked for", 60, live.getBounds().getX());
        assertEquals("y must be the one the second operation asked for", 70, live.getBounds().getY());
        assertEquals("height must be the one the second operation asked for",
                150, live.getBounds().getHeight());
    }

    /**
     * The parent-fit cascade must reach a back-referenced NOTE exactly as it reaches a
     * back-referenced group or element. The cascade resolves the container from
     * {@code eContainer()} and, when that is null because the add command has not executed,
     * from the pending-parent map — so a prepare that never records its parent leaves a
     * same-call note with no container to grow, silently, while the identical operation on a
     * group target cascades correctly.
     *
     * <p>This case only became reachable when a back-referenced note started resolving at all;
     * before that every such update failed not-found, so the gap could never be exercised.</p>
     */
    @Test
    public void shouldGrowTheParentGroup_whenABackReferencedNoteIsResizedPastIt() {
        BulkMutationResult bulk = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("add-group-to-view", op(
                        "viewId", view.getId(), "label", "Holder",
                        "x", 0, "y", 0, "width", 300, "height", 300)),
                new BulkOperation("add-note-to-view", op(
                        "viewId", view.getId(), "content", "Nested",
                        "parentViewObjectId", "$0.id",
                        "x", 10, "y", 10, "width", 120, "height", 60)),
                new BulkOperation("update-view-object", op(
                        "viewObjectId", "$1.id", "height", 500))),
                "grow a same-call note past its same-call group", false);

        assertTrue("all three operations must succeed", bulk.allSucceeded());
        BulkOperationResult update = bulk.operations().get(2);
        assertFalse("growing the note past its group must name the group it grew: " + update,
                update.resizedAncestors().isEmpty());
        assertEquals("and it must name THAT group", bulk.operations().get(0).entityId(),
                update.resizedAncestors().get(0).viewObjectId());
        IDiagramModelObject group = find(view, bulk.operations().get(0).entityId());
        assertNotNull("the group must exist in the model", group);
        assertTrue("the group must actually hold a height that contains the grown note, not the "
                + "300 it was created with: got " + group.getBounds().getHeight(),
                group.getBounds().getHeight() > 300);
    }

    /**
     * The negative half of the ruling, and existing correct behaviour this change must not regress:
     * a note is addressable as a TARGET and remains permanently unusable as a PARENT. That
     * permanent no is precisely why a second, note-typed lookup may sit beside the container finder
     * without re-opening the drift the two-finder split once caused for groups — there is no
     * "works as a parent, fails as a target" asymmetry available to a note.
     */
    @Test
    public void shouldStillRejectTheSameBackReference_whenUsedAsAParentViewObjectId() {
        try {
            accessor.executeBulk(SESSION, List.of(
                    new BulkOperation("add-note-to-view", op(
                            "viewId", view.getId(), "content", "Not a container",
                            "x", 0, "y", 0, "width", 300, "height", 200)),
                    new BulkOperation("add-to-view", op(
                            "viewId", view.getId(), "elementId", "actor-1",
                            "parentViewObjectId", "$0.id",
                            "x", 10, "y", 10, "width", 120, "height", 55))),
                    "a note as a parent", false);
            throw new AssertionError("expected a note to be rejected as a parentViewObjectId");
        } catch (ModelAccessException e) {
            // MEASURED, not assumed. The rejection arrives as a not-found rather than as the
            // group-or-element message, because a note this call created is never in the container
            // registry the parent lookup reads and is still detached from the view, so parent
            // resolution misses it before it can ever reach the type check. Routing it to the
            // type check would mean making the container finder answer for notes — the widening
            // that would re-open the drift the single container finder exists to prevent. The
            // rejection is the invariant; the message is pinned as-is so a later change to either
            // has to be deliberate.
            assertTrue("the failure must name the operation that used the note as a parent: "
                    + e.getMessage(), e.getMessage().startsWith("Operation 1 (add-to-view):"));
            assertTrue("the message must say the parent could not be used: " + e.getMessage(),
                    e.getMessage().contains("Parent view object not found:"));
            assertEquals("BULK_VALIDATION_FAILED", e.getErrorCode().name());
            assertTrue("and the batch must have rolled back wholly", view.getChildren().isEmpty());
        }
    }

    /**
     * Atomicity: a batch whose note update legitimately fails must still roll back wholly, so the
     * note creation goes with it. The failure is a negative width — a deterministic, display-free
     * rejection from the dimension guard, reached only after the note has been resolved through the
     * new lookup, so this exercises the fixed path rather than avoiding it.
     */
    @Test
    public void shouldRollBackTheNoteCreation_whenTheBackReferencedUpdateFails() {
        try {
            accessor.executeBulk(SESSION, List.of(
                    new BulkOperation("add-note-to-view", op(
                            "viewId", view.getId(), "content", "Doomed",
                            "x", 0, "y", 0, "width", 200, "height", 100)),
                    new BulkOperation("update-view-object", op(
                            "viewObjectId", "$0.id", "width", -5))),
                    "an invalid update on a back-referenced note", false);
            throw new AssertionError("expected a negative width to be rejected");
        } catch (ModelAccessException e) {
            assertTrue("the failure must name the operation that caused it: " + e.getMessage(),
                    e.getMessage().startsWith("Operation 1 (update-view-object)"));
            // The reason matters as much as the failure. Before the note was addressable this
            // threw a not-found from the id path, so a bare "it failed" assertion passed for a
            // reason that has nothing to do with atomicity.
            assertTrue("the batch must die on the DIMENSION guard, which is only reachable once the "
                    + "note resolves — not on a not-found: " + e.getMessage(),
                    e.getMessage().contains("width must be positive"));
        }
        assertTrue("no note may survive a rolled-back batch: " + view.getChildren(),
                view.getChildren().isEmpty());
    }

    /**
     * The same failure under {@code continueOnError} is reported per-operation instead of killing
     * the batch, and the note the first operation created still commits.
     */
    @Test
    public void shouldReportOnlyTheFailedOperation_whenContinueOnErrorIsSet() {
        BulkMutationResult bulk = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("add-note-to-view", op(
                        "viewId", view.getId(), "content", "Survivor",
                        "x", 0, "y", 0, "width", 200, "height", 100)),
                new BulkOperation("update-view-object", op(
                        "viewObjectId", "$0.id", "width", -5))),
                "continue past an invalid update", true);

        assertFalse("the batch must not report wholesale success", bulk.allSucceeded());
        assertEquals("exactly one operation must be reported failed",
                1, bulk.failedOperations().size());
        BulkOperationFailure failure = bulk.failedOperations().get(0);
        assertEquals("the failure must name operation 1", 1, failure.index());
        assertEquals("update-view-object", failure.tool());
        // As above: the reason has to be the dimension guard, not the not-found that used to fire
        // before the note was addressable at all.
        assertEquals("INVALID_PARAMETER", failure.errorCode());
        assertTrue("the per-operation failure must state the dimension it rejected: "
                + failure.message(), failure.message().contains("width must be positive"));
        assertEquals("the note creation must still have committed", 1, view.getChildren().size());
        assertNotNull("and it must be the note",
                find(view, bulk.operations().get(0).entityId()));
    }

    /**
     * The residual the fix above does NOT close, pinned so the diagnostic stays honest about it: a
     * {@code $N.id} naming a same-call ELEMENT is a legal back-reference (create-element is a
     * create tool) but is not a view object, so it still lands on the id path with a resolved GUID
     * in the message — a string the caller never typed. The advice must therefore not send them to
     * {@code get-view-contents} as their only route, because for a same-call id that tool cannot
     * answer: the object does not exist yet.
     */
    @Test
    public void shouldAdviseTheBackReferenceCase_whenAnElementIdIsUsedAsAViewObjectId() {
        assertAdviceCoversTheBackReferenceCase(adviceFromRejected(
                new BulkOperation("update-view-object", op("viewObjectId", "$0.id", "width", 200))));
    }

    /**
     * The SIBLING site, which carries a byte-identical copy of that advice.
     * {@code update-view-connection} has the same shape of residual for the same reason — a
     * {@code create-relationship} result is a model concept with no view connection until an
     * {@code add-connection-to-view} places it — and its not-found string is a second, separate
     * literal in the source. Fixing one and leaving the other is this repo's most repeated failure,
     * so the sibling gets its own pin rather than riding on the first one's.
     */
    @Test
    public void shouldAdviseTheBackReferenceCase_whenAnElementIdIsUsedAsAViewConnectionId() {
        assertAdviceCoversTheBackReferenceCase(adviceFromRejected(
                new BulkOperation("update-view-connection", op("viewConnectionId", "$0.id"))));
    }

    /**
     * The invariant the two pins above cannot state on their own: the sites must not DRIFT. Each
     * one asserted alone would stay green while somebody improved one string and left the other
     * behind, which is exactly how the two came to disagree in the first place. Comparing them to
     * each other is the only assertion that fails on that.
     */
    @Test
    public void shouldGiveIdenticalAdvice_onBothViewTargetNotFoundSites() {
        assertEquals("both not-found sites must carry the SAME advice — the string is duplicated in "
                        + "source, so nothing but this pin stops one being improved and its sibling "
                        + "left behind",
                adviceFromRejected(new BulkOperation("update-view-object",
                        op("viewObjectId", "$0.id", "width", 200))),
                adviceFromRejected(new BulkOperation("update-view-connection",
                        op("viewConnectionId", "$0.id"))));
    }

    /**
     * Runs {@code create-element} followed by the given operation, which must name {@code $0.id} as
     * a view target, and returns the advice the resulting not-found carries.
     */
    private String adviceFromRejected(BulkOperation viewTargetOp) {
        try {
            accessor.executeBulk(SESSION, List.of(
                    new BulkOperation("create-element", op(
                            "type", "BusinessActor", "name", "Fresh")),
                    viewTargetOp),
                    "a model-concept id used as a view target", false);
            throw new AssertionError(
                    "expected a model-concept id to be rejected by " + viewTargetOp.tool());
        } catch (ModelAccessException e) {
            assertTrue("the failure must still be a not-found: " + e.getMessage(),
                    e.getMessage().contains("View object not found"));
            String advice = e.getSuggestedCorrection();
            assertNotNull("a not-found must carry advice", advice);
            return advice;
        }
    }

    /**
     * A bare {@code contains("$N.id")} would pass on any string that merely mentions the token,
     * which is weaker than what these tests' names claim. Pin what the advice has to actually TELL
     * a caller who typed a back-reference: the token it is about, WHY get-view-contents cannot
     * answer for it, and which operation to reference instead — both remedies, since an element
     * and a relationship are placed by different tools.
     */
    private static void assertAdviceCoversTheBackReferenceCase(String advice) {
        assertTrue("the advice must name the back-reference form it is about: " + advice,
                advice.contains("$N.id"));
        assertTrue("the advice must say WHY get-view-contents cannot answer here — the concept "
                + "has no view object yet: " + advice, advice.contains("no view object"));
        assertTrue("the advice must name the remedy for an element: " + advice,
                advice.contains("add-to-view"));
        assertTrue("the advice must name the remedy for a relationship: " + advice,
                advice.contains("add-connection-to-view"));
    }

    private static Map<String, Object> op(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
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
