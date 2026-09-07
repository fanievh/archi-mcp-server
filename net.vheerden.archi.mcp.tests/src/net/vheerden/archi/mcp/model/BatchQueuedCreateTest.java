package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.ViewGroupDto;

/**
 * Pins that a <em>model</em> object created earlier in an open batch — a view or an element — can be
 * named by a later operation in that same batch.
 *
 * <p>{@code create-view} and {@code create-element} build their EMF object immediately but defer
 * folder attachment to the command's {@code execute()}, which inside a batch does not run until
 * commit. Between those two moments the object has a real, agent-visible id but hangs off no folder,
 * so {@code ArchimateModelUtils.getObjectByID} — which walks committed containment only — cannot
 * find it and the {@code add-*-to-view} prepares throw {@code VIEW_NOT_FOUND} /
 * {@code ELEMENT_NOT_FOUND}. Queue mode previously bridged this seam for view <em>objects</em>
 * (things an {@code add-*-to-view} creates) but not for the model objects the two create commands
 * make, so an agent could nest inside a same-batch group yet could not build a view from scratch in
 * one atomic window.</p>
 *
 * <h2>Execution model</h2>
 *
 * <p>Same headless idiom as {@code BatchQueuedViewObjectUpdateTest} /
 * {@code BatchQueuedParentContainerTest}: a real GEF {@link CommandStack} driven over an
 * <em>ordered</em> compound, because queue order is load-bearing (the create must execute before the
 * add). The production compound is {@code NonNotifyingCompoundCommand}, whose {@code execute()}
 * dereferences {@code IEditorModelManager.INSTANCE} and cannot run headless, so queued children are
 * rebuilt into a plain GEF {@link CompoundCommand} — order preserved, only ECORE event suppression
 * dropped. Real-{@code CommandStack} undo through the production compound therefore stays a
 * live-gate observation; what is proven here is the one-unit, ordering and membership property.</p>
 *
 * <p>Every add passes explicit x/y/width/height so no path reaches {@code ElementSizer}'s
 * {@code Display.getDefault().syncExec}, which is what would otherwise force this class onto a
 * display.</p>
 */
public class BatchQueuedCreateTest {

    private static final String SESSION = "batch-queued-create-session";
    private static final String MISSING_ID = "no-such-id-anywhere";

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private CommandStack stack;
    private IArchimateModel model;
    private IArchimateDiagramModel liveView;
    private IBusinessActor liveActor;
    private boolean approvalMode;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Batch Queued Create Fixture");
        model.setId("model-batch-queued-create");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        liveView = factory.createArchimateDiagramModel();
        liveView.setId("live-view-1");
        liveView.setName("Already Committed");
        diagrams.getElements().add(liveView);

        IFolder business = model.getFolder(FolderType.BUSINESS);
        liveActor = factory.createBusinessActor();
        liveActor.setId("live-actor-1");
        liveActor.setName("Already Committed Actor");
        business.getElements().add(liveActor);

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
        approvalMode = false;
        dispatcher.setApprovalModeProvider(() -> approvalMode);
        accessor = new ArchiModelAccessorImpl(stubModelManager, dispatcher);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private String createView(String name) {
        return accessor.createView(SESSION, name, null, null, null).entity().id();
    }

    private String createElement(String name) {
        return accessor.createElement(SESSION, "BusinessActor", name, null, null, null, null)
                .entity().id();
    }

    private String group(String viewId, String label, int x, int y, int w, int h) {
        return accessor.addGroupToView(SESSION, viewId, label, x, y, w, h, null, null, null)
                .entity().viewObjectId();
    }

    private String element(String viewId, String elementId, int x, int y, int w, int h) {
        return accessor.addToView(SESSION, viewId, elementId, x, y, w, h,
                false, null, null, null).entity().viewObject().viewObjectId();
    }

    private String note(String viewId, String content, int x, int y, int w, int h) {
        return accessor.addNoteToView(SESSION, viewId, content, null, null, x, y, w, h,
                null, null, null).entity().viewObjectId();
    }

    /** Depth-first search of live containment for a view object id. */
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

    /** The committed view with that id, or null while it is still only queued. */
    private IArchimateDiagramModel committedView(String id) {
        for (Object each : model.getFolder(FolderType.DIAGRAMS).getElements()) {
            if (each instanceof IArchimateDiagramModel view && id.equals(view.getId())) {
                return view;
            }
        }
        return null;
    }

    /** The committed element with that id, or null while it is still only queued. */
    private IArchimateElement committedElement(String id) {
        for (Object each : model.getFolder(FolderType.BUSINESS).getElements()) {
            if (each instanceof IArchimateElement element && id.equals(element.getId())) {
                return element;
            }
        }
        return null;
    }

    private void assertViewNotFound(Runnable call, String viewId) {
        try {
            call.run();
            fail("expected the ordinary live-lookup failure for view " + viewId);
        } catch (ModelAccessException e) {
            assertEquals("message must stay the ordinary not-found one",
                    "View not found: " + viewId, e.getMessage());
            assertEquals("error code must stay VIEW_NOT_FOUND",
                    ErrorCode.VIEW_NOT_FOUND, e.getErrorCode());
        }
    }

    private void assertElementNotFound(Runnable call, String elementId) {
        try {
            call.run();
            fail("expected the ordinary live-lookup failure for element " + elementId);
        } catch (ModelAccessException e) {
            assertEquals("message must stay the ordinary not-found one",
                    "Element not found: " + elementId, e.getMessage());
            assertEquals("error code must stay ELEMENT_NOT_FOUND",
                    ErrorCode.ELEMENT_NOT_FOUND, e.getErrorCode());
        }
    }

    // ---- the view half ------------------------------------------------------------------------

    /**
     * The reported case: {@code create-view} hands back a real id, and an {@code add-group-to-view}
     * naming that id later in the same batch used to throw {@code VIEW_NOT_FOUND}.
     */
    @Test
    public void shouldHostQueuedGroup_whenTheViewWasCreatedEarlierInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "create view then add group");
        String viewId = createView("Built In Batch");
        assertNull("the created view must still be uncommitted mid-batch", committedView(viewId));
        String groupId = group(viewId, "Queued Group", 10, 20, 200, 120);
        dispatcher.endBatch(SESSION, true);

        IArchimateDiagramModel view = committedView(viewId);
        assertNotNull("the batch-created view must exist after commit", view);
        IDiagramModelObject group = find(view, groupId);
        assertNotNull("the group must have landed on the batch-created view", group);
        assertEquals("group x", 10, group.getBounds().getX());
        assertEquals("group y", 20, group.getBounds().getY());
    }

    /** A live element placed on a same-batch-created view — the view half without the element half. */
    @Test
    public void shouldHostQueuedElementAdd_whenTheViewWasCreatedEarlierInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "create view then add live element");
        String viewId = createView("Built In Batch");
        String objectId = element(viewId, liveActor.getId(), 30, 40, 120, 55);
        dispatcher.endBatch(SESSION, true);

        IArchimateDiagramModel view = committedView(viewId);
        assertNotNull("the batch-created view must exist after commit", view);
        assertNotNull("the element must have landed on the batch-created view", find(view, objectId));
    }

    /** The third add named by the acceptance criteria, completing group/element/note. */
    @Test
    public void shouldHostQueuedNote_whenTheViewWasCreatedEarlierInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "create view then add note");
        String viewId = createView("Built In Batch");
        String noteId = note(viewId, "Queued note", 5, 5, 180, 80);
        dispatcher.endBatch(SESSION, true);

        IArchimateDiagramModel view = committedView(viewId);
        assertNotNull("the batch-created view must exist after commit", view);
        assertNotNull("the note must have landed on the batch-created view", find(view, noteId));
    }

    // ---- the element half ---------------------------------------------------------------------

    /**
     * The second reported case: {@code create-element} hands back a real id, and an
     * {@code add-to-view} naming it later in the same batch used to throw {@code ELEMENT_NOT_FOUND}.
     */
    @Test
    public void shouldAddQueuedElementToLiveView_whenTheElementWasCreatedEarlierInSameBatch()
            throws Exception {
        dispatcher.beginBatch(SESSION, "create element then add it");
        String elementId = createElement("Built In Batch");
        assertNull("the created element must still be uncommitted mid-batch",
                committedElement(elementId));
        String objectId = element(liveView.getId(), elementId, 60, 70, 140, 60);
        dispatcher.endBatch(SESSION, true);

        assertNotNull("the batch-created element must exist after commit",
                committedElement(elementId));
        IDiagramModelObject placed = find(liveView, objectId);
        assertNotNull("the batch-created element must have landed on the live view", placed);
        assertSame("the view object must reference the batch-created element",
                committedElement(elementId),
                ((IDiagramModelArchimateObject) placed).getArchimateElement());
    }

    // ---- the headline workflow ----------------------------------------------------------------

    /**
     * The workflow the two halves jointly blocked: build both the element and the view inside one
     * batch, then place one on the other — all in a single undo unit.
     */
    @Test
    public void shouldBuildAViewFromScratch_whenElementAndViewAreBothCreatedInSameBatch()
            throws Exception {
        dispatcher.beginBatch(SESSION, "element, view, place");
        String elementId = createElement("From Scratch Actor");
        String viewId = createView("From Scratch View");
        String objectId = element(viewId, elementId, 15, 25, 120, 55);
        dispatcher.endBatch(SESSION, true);

        IArchimateDiagramModel view = committedView(viewId);
        assertNotNull("the batch-created view must exist after commit", view);
        IDiagramModelObject placed = find(view, objectId);
        assertNotNull("the batch-created element must sit on the batch-created view", placed);
        assertSame("the view object must reference the batch-created element",
                committedElement(elementId),
                ((IDiagramModelArchimateObject) placed).getArchimateElement());
    }

    /** One batch is one undo unit: a single undo must remove the view, the element and the add. */
    @Test
    public void shouldUndoTheWholeBatchAtOnce_whenTheViewWasBuiltFromScratch() throws Exception {
        dispatcher.beginBatch(SESSION, "element, view, place");
        String elementId = createElement("From Scratch Actor");
        String viewId = createView("From Scratch View");
        element(viewId, elementId, 15, 25, 120, 55);
        dispatcher.endBatch(SESSION, true);

        assertTrue("the batch must be undoable as one unit", stack.canUndo());
        stack.undo();

        assertNull("one undo must remove the batch-created view", committedView(viewId));
        assertNull("one undo must remove the batch-created element", committedElement(elementId));
    }

    // ---- lifetime -----------------------------------------------------------------------------

    /**
     * A rolled-back create must stop being addressable: the ids resolve nowhere afterwards, and the
     * next batch starts clean rather than inheriting the abandoned queue.
     */
    @Test
    public void shouldResolveNowhere_whenTheBatchThatCreatedThemWasRolledBack() throws Exception {
        dispatcher.beginBatch(SESSION, "create then roll back");
        String elementId = createElement("Rolled Back Actor");
        String viewId = createView("Rolled Back View");
        dispatcher.endBatch(SESSION, false);

        assertNull("a rolled-back view must not be committed", committedView(viewId));
        assertNull("a rolled-back element must not be committed", committedElement(elementId));

        dispatcher.beginBatch(SESSION, "next batch must start clean");
        assertViewNotFound(() -> group(viewId, "Orphan", 0, 0, 100, 100), viewId);
        assertElementNotFound(
                () -> element(liveView.getId(), elementId, 0, 0, 100, 100), elementId);
        dispatcher.endBatch(SESSION, false);
    }

    /** Outside any batch the queued lookups cannot fire, so a committed id is the only resolvable one. */
    @Test
    public void shouldNotResolveQueuedCreates_afterTheBatchHasCommitted() throws Exception {
        dispatcher.beginBatch(SESSION, "create and commit");
        String viewId = createView("Committed View");
        dispatcher.endBatch(SESSION, true);

        // Committed, so the ordinary live lookup finds it with no batch open at all.
        String groupId = group(viewId, "Post-commit group", 0, 0, 100, 100);
        assertNotNull("a committed view resolves live, outside batch mode",
                find(committedView(viewId), groupId));
    }

    // ---- the negative pins: unresolvable ids must fail identically -----------------------------

    /** An id no queued command creates must throw exactly as it does today — inside a batch. */
    @Test
    public void shouldThrowTheOrdinaryViewNotFound_whenTheIdIsUnknownInsideABatch() throws Exception {
        dispatcher.beginBatch(SESSION, "unknown view id inside batch");
        createView("A Different View");
        assertViewNotFound(() -> group(MISSING_ID, "Nope", 0, 0, 100, 100), MISSING_ID);
        dispatcher.endBatch(SESSION, false);
    }

    /** The same id, with no batch open, must produce the identical message and code. */
    @Test
    public void shouldThrowTheOrdinaryViewNotFound_whenTheIdIsUnknownOutsideABatch() {
        assertViewNotFound(() -> group(MISSING_ID, "Nope", 0, 0, 100, 100), MISSING_ID);
    }

    /** An element id no queued command creates must throw exactly as it does today — inside a batch. */
    @Test
    public void shouldThrowTheOrdinaryElementNotFound_whenTheIdIsUnknownInsideABatch()
            throws Exception {
        dispatcher.beginBatch(SESSION, "unknown element id inside batch");
        createElement("A Different Actor");
        assertElementNotFound(
                () -> element(liveView.getId(), MISSING_ID, 0, 0, 100, 100), MISSING_ID);
        dispatcher.endBatch(SESSION, false);
    }

    /** The same id, with no batch open, must produce the identical message and code. */
    @Test
    public void shouldThrowTheOrdinaryElementNotFound_whenTheIdIsUnknownOutsideABatch() {
        assertElementNotFound(
                () -> element(liveView.getId(), MISSING_ID, 0, 0, 100, 100), MISSING_ID);
    }

    /**
     * A queued <em>element</em> id is not a view and a queued <em>view</em> id is not an element, so
     * naming each in the other's slot must still take the ordinary not-found path. This is what
     * keeps the two new lookups typed rather than a single untyped id resolver.
     */
    @Test
    public void shouldNotCrossResolve_whenAQueuedCreateIsNamedInTheWrongSlot() throws Exception {
        dispatcher.beginBatch(SESSION, "cross-slot");
        String elementId = createElement("Not A View");
        String viewId = createView("Not An Element");
        assertViewNotFound(() -> group(elementId, "Nope", 0, 0, 100, 100), elementId);
        assertElementNotFound(
                () -> element(liveView.getId(), viewId, 0, 0, 100, 100), viewId);
        dispatcher.endBatch(SESSION, false);
    }


    // ---- the approval path and the paths the queue walk must not disturb ---------------------

    /**
     * The approval gate re-runs the prepare from a stored lambda at <em>approve</em> time, not at
     * propose time, so the queued-view lookup has to still resolve then. This is the one path where
     * "the lookup is evaluated at the entry site" is not the whole story, and it is what makes the
     * additive claim true for approval mode as well as for direct dispatch.
     */
    @Test
    public void shouldResolveQueuedView_whenTheAddIsApprovedLaterInTheSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "approval inside batch");
        String viewId = createView("Approved Host");

        approvalMode = true;
        MutationResult<ViewGroupDto> proposed = accessor.addGroupToView(SESSION, viewId, "Proposed",
                10, 10, 100, 100, null, null, null);
        assertNotNull("approval mode must yield a proposal, not a queued command",
                proposed.proposalContext());

        approvalMode = false;
        dispatcher.approveProposal(SESSION, proposed.proposalContext().proposalId());
        dispatcher.endBatch(SESSION, true);

        IArchimateDiagramModel view = committedView(viewId);
        assertNotNull("the batch-created host view must exist after commit", view);
        assertEquals("the approved group must have landed on it", 1, view.getChildren().size());
    }

    /**
     * A pending proposal deliberately keeps the session's context alive past {@code end-batch}, so
     * the context outlives the batch that queued the create. The queue is still cleared and the mode
     * still leaves {@code BATCH}, so a rolled-back created view must not remain addressable through
     * the surviving context.
     */
    @Test
    public void shouldNotResolveQueuedView_whenAPendingProposalOutlivesTheBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "create, then leave a proposal pending");
        String viewId = createView("Doomed View");

        approvalMode = true;
        accessor.addGroupToView(SESSION, liveView.getId(), "Pending",
                0, 0, 100, 100, null, null, null);
        approvalMode = false;

        dispatcher.endBatch(SESSION, false);

        assertViewNotFound(() -> group(viewId, "Orphan", 0, 0, 100, 100), viewId);
    }

    /**
     * Auto-connect scans the element's relationships, and a batch-created element is detached with
     * none — the branch every other test here skips by passing {@code autoConnect=false}.
     */
    @Test
    public void shouldAddQueuedElement_whenAutoConnectIsRequested() throws Exception {
        dispatcher.beginBatch(SESSION, "autoConnect on a queued element");
        String elementId = createElement("Auto Actor");
        String objectId = accessor.addToView(SESSION, liveView.getId(), elementId,
                10, 10, 120, 55, true, null, null, null)
                .entity().viewObject().viewObjectId();
        dispatcher.endBatch(SESSION, true);

        assertNotNull("the batch-created element must exist after commit",
                committedElement(elementId));
        assertNotNull("and must have landed on the live view", find(liveView, objectId));
    }

    // ---- harness ------------------------------------------------------------------------------

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
