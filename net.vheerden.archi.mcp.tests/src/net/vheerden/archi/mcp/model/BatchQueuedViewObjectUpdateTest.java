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
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.ITextContent;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.ViewObjectDto;

/**
 * Pins that a view object added earlier in an open batch can be named as {@code viewObjectId} by a
 * later {@code update-view-object} in that same batch.
 *
 * <p>Every {@code add-*-to-view} builds a <em>detached</em> object and defers containment to its
 * command's {@code execute()}. Inside a batch that runs at commit, so the update's target resolver —
 * {@code ArchimateModelUtils.getObjectByID}, which walks committed containment only — cannot see it
 * and throws {@code VIEW_OBJECT_NOT_FOUND}. The bulk path bridges the same seam with its
 * back-reference maps; queue mode had no bridge.</p>
 *
 * <h2>Execution model</h2>
 *
 * <p>Same headless idiom as {@code BatchQueuedParentContainerTest} /
 * {@code BatchAnchorTargetStalenessTest}: a real GEF {@link CommandStack} driven over an
 * <em>ordered</em> compound, because queue order is load-bearing (the add must execute before the
 * update). The production compound is {@code NonNotifyingCompoundCommand}, whose {@code execute()}
 * dereferences {@code IEditorModelManager.INSTANCE} and cannot run headless, so queued children are
 * rebuilt into a plain GEF {@link CompoundCommand} — order preserved, only ECORE event suppression
 * dropped. Real-{@code CommandStack} undo through the production compound therefore stays a
 * live-gate observation; what is proven here is the one-unit, ordering and membership property.</p>
 *
 * <p>Every add passes explicit x/y/width/height so no path reaches {@code ElementSizer}'s
 * {@code Display.getDefault().syncExec}, which is what would otherwise force this class onto a
 * display.</p>
 */
public class BatchQueuedViewObjectUpdateTest {

    private static final String SESSION = "batch-queued-update-session";

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private CommandStack stack;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private IBusinessActor actor;
    private Command lastDispatched;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Batch Queued Update Fixture");
        model.setId("model-batch-queued-update");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Updating");
        diagrams.getElements().add(view);

        IFolder business = model.getFolder(FolderType.BUSINESS);
        actor = factory.createBusinessActor();
        actor.setId("actor-1");
        actor.setName("Queued Actor");
        business.getElements().add(actor);

        IBusinessActor peer = factory.createBusinessActor();
        peer.setId("actor-2");
        peer.setName("Peer Actor");
        business.getElements().add(peer);

        IArchimateRelationship rel = factory.createAssociationRelationship();
        rel.setId("rel-1");
        rel.setSource(actor);
        rel.setTarget(peer);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);

        stubModelManager.setModels(List.of(model));

        stack = new CommandStack();
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                stack.execute(new AgentAuthoredCompoundCommand(toPlainCompound(command)));
            }
            @Override
            protected void dispatchCommand(Command command) {
                lastDispatched = command;
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
        dispatcher.setApprovalModeProvider(() -> false);
        accessor = new ArchiModelAccessorImpl(stubModelManager, dispatcher);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private String group(String label, int x, int y, int w, int h, String parentId) {
        return accessor.addGroupToView(SESSION, view.getId(), label,
                x, y, w, h, parentId, null, null).entity().viewObjectId();
    }

    private String element(int x, int y, int w, int h, String parentId) {
        return accessor.addToView(SESSION, view.getId(), actor.getId(), x, y, w, h,
                false, parentId, null, null).entity().viewObject().viewObjectId();
    }

    private String note(String content, int x, int y, int w, int h, String parentId) {
        return accessor.addNoteToView(SESSION, view.getId(), content, null, null,
                x, y, w, h, parentId, null, null).entity().viewObjectId();
    }

    private void update(String id, Integer x, Integer y, Integer w, Integer h) {
        accessor.updateViewObject(SESSION, id, x, y, w, h,
                null, null, null, null, null, null, null, null);
    }

    private void updateText(String id, String text) {
        accessor.updateViewObject(SESSION, id, null, null, null, null,
                text, null, null, null, null, null, null, null);
    }

    /**
     * An already-committed element view object, built straight through EMF — the state the model
     * would be in before the batch opened. Uses the second business actor so it never collides
     * with what {@link #element} adds.
     */
    private IDiagramModelArchimateObject liveElement(String id, int x, int y, int w, int h,
            IDiagramModelContainer parent) {
        IDiagramModelArchimateObject obj = factory.createDiagramModelArchimateObject();
        obj.setId(id);
        obj.setArchimateElement((com.archimatetool.model.IArchimateElement)
                model.getFolder(FolderType.BUSINESS).getElements().get(1));
        obj.setBounds(x, y, w, h);
        parent.getChildren().add(obj);
        return obj;
    }

    /** Anchor-only update: no x/y/w/h/text/styling/image/labelExpression, just the four anchor fields. */
    private void anchor(String id, String targetId, String edge, int dx, int dy) {
        accessor.updateViewObject(SESSION, id, null, null, null, null,
                null, null, null, null, targetId, edge, dx, dy);
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

    private static void assertBounds(String what, IDiagramModelObject obj, int x, int y, int w, int h) {
        assertNotNull(what + " must exist after commit", obj);
        assertEquals(what + " x", x, obj.getBounds().getX());
        assertEquals(what + " y", y, obj.getBounds().getY());
        assertEquals(what + " width", w, obj.getBounds().getWidth());
        assertEquals(what + " height", h, obj.getBounds().getHeight());
    }

    /** Asserts the ordinary live-lookup failure — the same one a non-batch caller gets. */
    private void assertViewObjectNotFound(Runnable call, String id) {
        try {
            call.run();
            fail("expected the ordinary live-lookup failure for view object " + id);
        } catch (ModelAccessException e) {
            assertEquals("message must stay the ordinary not-found one",
                    "View object not found: " + id, e.getMessage());
            assertEquals("error code must stay VIEW_OBJECT_NOT_FOUND",
                    ErrorCode.VIEW_OBJECT_NOT_FOUND, e.getErrorCode());
        }
    }

    /** Asserts the ordinary parent live-lookup failure. */
    private void assertParentNotFound(Runnable call, String parentId) {
        try {
            call.run();
            fail("expected the ordinary live-lookup failure for parent " + parentId);
        } catch (ModelAccessException e) {
            assertEquals("message must stay the ordinary not-found one",
                    "Parent view object not found: " + parentId, e.getMessage());
            assertEquals("error code must stay VIEW_OBJECT_NOT_FOUND",
                    ErrorCode.VIEW_OBJECT_NOT_FOUND, e.getErrorCode());
        }
    }

    // ---- the defect ---------------------------------------------------------------------------

    /**
     * The headline case: an element view object added earlier in the batch must be updatable later
     * in that same batch, and the new geometry must land at commit.
     */
    @Test
    public void shouldUpdateQueuedElement_whenItWasAddedEarlierInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "add then update");
        String outer = group("QOuter", 500, 0, 200, 200, null);
        String child = element(20, 20, 100, 100, outer);
        update(child, 150, 150, 120, 120);
        dispatcher.endBatch(SESSION, true);

        IDiagramModelObject childObj = find(view, child);
        assertBounds("queued element after same-batch update", childObj, 150, 150, 120, 120);
        assertSame("the element must still be inside the queued group",
                find(view, outer), childObj.eContainer());
    }

    /**
     * A queued <em>group</em> is updatable too. This is the case the bulk path cannot do — its
     * back-reference scan covers element view objects only — so batch is deliberately a superset.
     */
    @Test
    public void shouldUpdateQueuedGroup_whenItWasAddedEarlierInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "group then resize");
        String outer = group("QOuter", 500, 0, 200, 200, null);
        update(outer, null, null, null, 800);
        dispatcher.endBatch(SESSION, true);

        IDiagramModelObject outerObj = find(view, outer);
        assertBounds("queued group after same-batch resize", outerObj, 500, 0, 200, 800);
    }

    /** The group's label travels through {@code text}, a parameter the bulk Direct path cannot carry. */
    @Test
    public void shouldSetQueuedGroupLabel_whenUpdatedInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "group then relabel");
        String outer = group("QOuter", 500, 0, 200, 200, null);
        updateText(outer, "Renamed In Batch");
        dispatcher.endBatch(SESSION, true);

        IDiagramModelObject outerObj = find(view, outer);
        assertNotNull("group must exist after commit", outerObj);
        assertEquals("label must reflect the same-batch update", "Renamed In Batch", outerObj.getName());
    }

    /** A queued note completes the element/group/note matrix the primary prepare accepts. */
    @Test
    public void shouldUpdateQueuedNote_whenItWasAddedEarlierInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "note then update");
        String n = note("First", 10, 10, 150, 60, null);
        update(n, 40, 40, 200, 90);
        dispatcher.endBatch(SESSION, true);

        assertBounds("queued note after same-batch update", find(view, n), 40, 40, 200, 90);
    }

    /** A queued note's content travels through {@code text} in the same batch. */
    @Test
    public void shouldSetQueuedNoteContent_whenUpdatedInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "note then retitle");
        String n = note("First", 10, 10, 150, 60, null);
        updateText(n, "Second");
        dispatcher.endBatch(SESSION, true);

        IDiagramModelObject noteObj = find(view, n);
        assertNotNull("note must exist after commit", noteObj);
        assertEquals("content must reflect the same-batch update",
                "Second", ((ITextContent) noteObj).getContent());
    }

    /**
     * A <em>second</em> update of the same object in one batch merges its omitted dimensions from
     * the geometry the first update established, not from the pre-batch bounds that update has not
     * written yet. A text-only re-edit therefore leaves the position and size alone.
     *
     * <p>The merge base is still frozen into the command at construction — what changed is what it
     * is frozen from. Reading it off the batch's queued commands makes the earlier write visible
     * without needing a supplied-fields mask to tell an omitted dimension from a restated one.</p>
     *
     * <p>Residual, deliberately not covered here: an <em>anchored</em> object's queued x/y is a
     * prepare-time projection that its command re-resolves at execute against a target that may
     * itself move later in the batch, so for that case the queued value can still differ from what
     * finally lands. Tracked separately.</p>
     *
     * <p>The first update's height is deliberately BELOW the note floor. A note whose text changes
     * with the height omitted is re-fitted to its wrapped content, which would make the height a
     * measurement rather than an inherited value — and a measured height differs between a lane
     * that has an SWT display and one that does not. A sub-floor height cannot be produced by that
     * fit and is left alone, so all four dimensions keep probing the merge base and this reads the
     * same in both lanes.</p>
     */
    @Test
    public void shouldKeepEarlierBounds_whenTheSameQueuedObjectIsUpdatedTwice() throws Exception {
        dispatcher.beginBatch(SESSION, "queued object re-edited twice");
        String n = note("First", 10, 10, 150, 60, null);
        update(n, 40, 40, 200, 50);
        updateText(n, "Second");
        dispatcher.endBatch(SESSION, true);

        IDiagramModelObject noteObj = find(view, n);
        assertEquals("content from the second update lands",
                "Second", ((ITextContent) noteObj).getContent());
        assertBounds("the first update's geometry survives the text-only re-edit",
                noteObj, 40, 40, 200, 50);
    }

    // ---- parent-fit consequence ----------------------------------------------------------------

    /**
     * Pushing a queued child out of its queued parent must grow the parent, matching what the same
     * three operations do through {@code bulk-mutate}.
     */
    @Test
    public void shouldGrowQueuedParent_whenQueuedChildIsPushedOutInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "add, nest, push out");
        String outer = group("QOuter", 500, 0, 200, 200, null);
        String child = element(20, 20, 100, 100, outer);
        update(child, 150, 150, 120, 120);
        dispatcher.endBatch(SESSION, true);

        IDiagramModelObject outerObj = find(view, outer);
        assertBounds("queued parent must fit its pushed-out child", outerObj, 500, 0, 280, 280);
    }

    /** The same three operations through {@code bulk-mutate} — the parity reference. */
    @Test
    public void shouldGrowBatchCreatedParent_whenTheSameThreeOpsRunAsBulk() throws Exception {
        List<BulkOperation> operations = List.of(
                new BulkOperation("add-group-to-view", Map.of(
                        "viewId", view.getId(), "label", "BOuter",
                        "x", 500, "y", 0, "width", 200, "height", 200)),
                new BulkOperation("add-to-view", Map.of(
                        "viewId", view.getId(), "elementId", actor.getId(),
                        "parentViewObjectId", "$0.id",
                        "x", 20, "y", 20, "width", 100, "height", 100)),
                new BulkOperation("update-view-object", Map.of(
                        "viewObjectId", "$1.id",
                        "x", 150, "y", 150, "width", 120, "height", 120)));

        BulkMutationResult result = accessor.executeBulk(SESSION, operations, null, false);

        String outer = result.operations().get(0).entityId();
        String child = result.operations().get(1).entityId();
        IDiagramModelObject outerObj = find(view, outer);
        IDiagramModelObject childObj = find(view, child);
        assertBounds("bulk element after same-batch update", childObj, 150, 150, 120, 120);
        assertBounds("bulk parent must fit its pushed-out child", outerObj, 500, 0, 280, 280);
    }

    /**
     * The three-level parity reference: the same five operations through {@code bulk-mutate}, where
     * every ancestor hop resolves its container from the pass-scoped batch-created-parent map. Both
     * ancestors grow. The outer number is {@code 10 + 280 + 10}, not {@code 280} — the padding term
     * applies once per hop.
     */
    @Test
    public void shouldGrowBatchCreatedGrandparent_whenTheSameFiveOpsRunAsBulk() throws Exception {
        List<BulkOperation> operations = List.of(
                new BulkOperation("add-group-to-view", Map.of(
                        "viewId", view.getId(), "label", "BOuter",
                        "x", 0, "y", 0, "width", 200, "height", 200)),
                new BulkOperation("add-group-to-view", Map.of(
                        "viewId", view.getId(), "label", "BInner",
                        "parentViewObjectId", "$0.id",
                        "x", 10, "y", 10, "width", 100, "height", 100)),
                new BulkOperation("add-to-view", Map.of(
                        "viewId", view.getId(), "elementId", actor.getId(),
                        "parentViewObjectId", "$1.id",
                        "x", 5, "y", 5, "width", 40, "height", 40)),
                new BulkOperation("update-view-object", Map.of(
                        "viewObjectId", "$2.id",
                        "x", 150, "y", 150, "width", 120, "height", 120)));

        BulkMutationResult result = accessor.executeBulk(SESSION, operations, null, false);

        String outer = result.operations().get(0).entityId();
        String inner = result.operations().get(1).entityId();
        String leaf = result.operations().get(2).entityId();
        assertBounds("bulk leaf after same-batch update", find(view, leaf), 150, 150, 120, 120);
        assertBounds("bulk inner fits its pushed-out child", find(view, inner), 10, 10, 280, 280);
        assertBounds("bulk grandparent fits its grown child", find(view, outer), 0, 0, 300, 300);
    }

    /**
     * Growing a queued group grows the queued group that contains it. Each hop of the walk resolves
     * its next ancestor from the containment the batch's own queued commands imply, so a chain of
     * groups none of which is attached yet still fits together at commit — matching what the same
     * five operations do through {@code bulk-mutate}.
     *
     * <p>The outer number is {@code 10 + 280 + 10}: the padding allowance applies once per hop, so
     * the grandparent is wider than the parent it must contain, not equal to it.</p>
     */
    @Test
    public void shouldGrowQueuedGrandparent_whenQueuedChildIsPushedOutInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "three deep then push out");
        String outer = group("QOuter", 0, 0, 200, 200, null);
        String inner = group("QInner", 10, 10, 100, 100, outer);
        String leaf = element(5, 5, 40, 40, inner);
        update(leaf, 150, 150, 120, 120);
        dispatcher.endBatch(SESSION, true);

        assertBounds("leaf lands where it was put", find(view, leaf), 150, 150, 120, 120);
        assertBounds("inner fits its pushed-out child", find(view, inner), 10, 10, 280, 280);
        assertBounds("outer fits the grown inner", find(view, outer), 0, 0, 300, 300);
    }

    /**
     * The walk recurses rather than special-casing the second hop: a four-deep queued chain fits at
     * every level. Each ancestor is one padding allowance wider than the one below it
     * ({@code 280 → 300 → 320}).
     */
    @Test
    public void shouldGrowEveryQueuedAncestor_whenTheChainIsFourDeep() throws Exception {
        dispatcher.beginBatch(SESSION, "four deep then push out");
        String outer = group("QOuter", 0, 0, 200, 200, null);
        String mid = group("QMid", 10, 10, 150, 150, outer);
        String inner = group("QInner", 10, 10, 100, 100, mid);
        String leaf = element(5, 5, 40, 40, inner);
        update(leaf, 150, 150, 120, 120);
        dispatcher.endBatch(SESSION, true);

        assertBounds("inner fits its pushed-out child", find(view, inner), 10, 10, 280, 280);
        assertBounds("mid fits the grown inner", find(view, mid), 10, 10, 300, 300);
        assertBounds("outer fits the grown mid", find(view, outer), 0, 0, 320, 320);
    }

    /**
     * The walk climbs out of the queue and into live containment without noticing the change: the
     * inner group is queued, its container is a group already on the view, and the live one grows.
     *
     * <p>The reverse mixed chain — a group the batch queued containing an object that is already
     * live — is not constructible through the tool surface: nesting is chosen when an object is
     * added, and no tool re-parents an existing view object, so a live object can never come to sit
     * inside a still-queued group.</p>
     */
    @Test
    public void shouldGrowLiveGrandparent_whenTheQueuedChainClimbsIntoIt() throws Exception {
        IDiagramModelGroup liveOuter = factory.createDiagramModelGroup();
        liveOuter.setId("grp-live-outer");
        liveOuter.setName("LiveOuter");
        liveOuter.setBounds(0, 0, 200, 200);
        view.getChildren().add(liveOuter);

        dispatcher.beginBatch(SESSION, "live outer, queued inner, queued leaf");
        String inner = group("QInner", 10, 10, 100, 100, liveOuter.getId());
        String leaf = element(5, 5, 40, 40, inner);
        update(leaf, 150, 150, 120, 120);
        dispatcher.endBatch(SESSION, true);

        assertBounds("queued inner fits its pushed-out child", find(view, inner), 10, 10, 280, 280);
        assertBounds("the live outer group fits the grown queued inner",
                find(view, liveOuter.getId()), 0, 0, 300, 300);
    }

    /**
     * The walk stops at a non-group ancestor. A queued <em>element</em> may legally be a nesting
     * parent, but only groups are auto-fitted — pre-existing behaviour, identical on the bulk path
     * and for live containment, and unchanged by the queue-derived hop.
     */
    @Test
    public void shouldNotGrowQueuedElementAncestor_whenItsQueuedChildGroupGrows() throws Exception {
        dispatcher.beginBatch(SESSION, "element ancestor");
        String outerElement = element(0, 0, 200, 200, null);
        String inner = group("QInner", 10, 10, 100, 100, outerElement);
        String leaf = element(5, 5, 40, 40, inner);
        update(leaf, 150, 150, 120, 120);
        dispatcher.endBatch(SESSION, true);

        assertBounds("the queued group still fits its child", find(view, inner), 10, 10, 280, 280);
        assertBounds("a non-group ancestor is not auto-fitted",
                find(view, outerElement), 0, 0, 200, 200);
    }

    /**
     * Two children of two different queued groups both cascade into the same queued grandparent,
     * and the grandparent ends up big enough for the larger of them.
     *
     * <p>Each update is prepared on its own request, against a model where no earlier command has
     * executed. Unless the geometry the first prepare established is visible to the second, the
     * second measures the shared grandparent against its creation size and emits a competing resize
     * that executes last and wins — silently, since both updates report success.</p>
     *
     * <p>The larger demand comes <em>first</em> deliberately: if the later cascade demanded more in
     * both dimensions it would simply subsume the earlier one and the fixture would pass while the
     * defect was live. See the reverse-order twin below, which is exactly that weaker case.</p>
     */
    @Test
    public void shouldFitBothCascades_whenTwoQueuedSiblingsGrowIntoOneGrandparent() throws Exception {
        dispatcher.beginBatch(SESSION, "two cascades, one grandparent");
        String outer = group("QOuter", 0, 0, 200, 200, null);
        String innerA = group("QInnerA", 10, 10, 100, 100, outer);
        String innerB = group("QInnerB", 10, 10, 100, 100, outer);
        String leafA = element(5, 5, 40, 40, innerA);
        String leafB = element(5, 5, 40, 40, innerB);
        update(leafA, 650, 650, 120, 120);
        update(leafB, 150, 150, 120, 120);
        dispatcher.endBatch(SESSION, true);

        assertBounds("leaf A lands where it was put", find(view, leafA), 650, 650, 120, 120);
        assertBounds("leaf B lands where it was put", find(view, leafB), 150, 150, 120, 120);
        assertBounds("inner A fits its far-pushed child", find(view, innerA), 10, 10, 780, 780);
        assertBounds("inner B fits its child", find(view, innerB), 10, 10, 280, 280);
        assertBounds("the shared grandparent fits the LARGER of the two grown inners",
                find(view, outer), 0, 0, 800, 800);
    }

    /**
     * The same two updates in the opposite order. This one passes even without the fix — the later,
     * bigger cascade subsumes the earlier, smaller one — which is precisely why it cannot stand
     * alone. Kept as the twin of the case above so the pair proves the outcome is order-independent
     * rather than accidentally right for one ordering.
     */
    @Test
    public void shouldFitBothCascades_whenTheTwoQueuedSiblingsGrowInReverseOrder() throws Exception {
        dispatcher.beginBatch(SESSION, "two cascades, reverse order");
        String outer = group("QOuter", 0, 0, 200, 200, null);
        String innerA = group("QInnerA", 10, 10, 100, 100, outer);
        String innerB = group("QInnerB", 10, 10, 100, 100, outer);
        String leafA = element(5, 5, 40, 40, innerA);
        String leafB = element(5, 5, 40, 40, innerB);
        update(leafB, 150, 150, 120, 120);
        update(leafA, 650, 650, 120, 120);
        dispatcher.endBatch(SESSION, true);

        assertBounds("inner A fits its far-pushed child", find(view, innerA), 10, 10, 780, 780);
        assertBounds("inner B fits its child", find(view, innerB), 10, 10, 280, 280);
        assertBounds("the shared grandparent fits the larger grown inner regardless of order",
                find(view, outer), 0, 0, 800, 800);
    }

    /**
     * The parity reference: the same seven operations through {@code bulk-mutate}, where the fit
     * maps are scoped to the whole pass, so the second cascade measures the grandparent against
     * what the first one established and emits no competing resize.
     */
    @Test
    public void shouldFitBothCascades_whenTheSameSevenOpsRunAsBulk() throws Exception {
        List<BulkOperation> operations = List.of(
                new BulkOperation("add-group-to-view", Map.of(
                        "viewId", view.getId(), "label", "BOuter",
                        "x", 0, "y", 0, "width", 200, "height", 200)),
                new BulkOperation("add-group-to-view", Map.of(
                        "viewId", view.getId(), "label", "BInnerA",
                        "parentViewObjectId", "$0.id",
                        "x", 10, "y", 10, "width", 100, "height", 100)),
                new BulkOperation("add-group-to-view", Map.of(
                        "viewId", view.getId(), "label", "BInnerB",
                        "parentViewObjectId", "$0.id",
                        "x", 10, "y", 10, "width", 100, "height", 100)),
                new BulkOperation("add-to-view", Map.of(
                        "viewId", view.getId(), "elementId", actor.getId(),
                        "parentViewObjectId", "$1.id",
                        "x", 5, "y", 5, "width", 40, "height", 40)),
                new BulkOperation("add-to-view", Map.of(
                        "viewId", view.getId(), "elementId", actor.getId(),
                        "parentViewObjectId", "$2.id",
                        "x", 5, "y", 5, "width", 40, "height", 40)),
                new BulkOperation("update-view-object", Map.of(
                        "viewObjectId", "$3.id",
                        "x", 650, "y", 650, "width", 120, "height", 120)),
                new BulkOperation("update-view-object", Map.of(
                        "viewObjectId", "$4.id",
                        "x", 150, "y", 150, "width", 120, "height", 120)));

        BulkMutationResult result = accessor.executeBulk(SESSION, operations, null, false);

        String outer = result.operations().get(0).entityId();
        String innerA = result.operations().get(1).entityId();
        String innerB = result.operations().get(2).entityId();
        String leafA = result.operations().get(3).entityId();
        String leafB = result.operations().get(4).entityId();
        assertBounds("bulk leaf A", find(view, leafA), 650, 650, 120, 120);
        assertBounds("bulk leaf B", find(view, leafB), 150, 150, 120, 120);
        assertBounds("bulk inner A fits its far-pushed child", find(view, innerA), 10, 10, 780, 780);
        assertBounds("bulk inner B fits its child", find(view, innerB), 10, 10, 280, 280);
        assertBounds("bulk grandparent fits the larger grown inner", find(view, outer), 0, 0, 800, 800);
    }

    /**
     * The geometry a queued writer that is <em>not</em> the primary update prepare established is
     * visible to a later cascade in the same batch.
     *
     * <p>A bulk pass running inside an open batch resolves a back-referenced target through the
     * direct prepare, whose same-batch record lives on a thread-local that is discarded when the
     * pass ends. Once the pass is over its geometry survives only as commands waiting in the queue.
     * A later cascade over a group that pass grew must still measure against the grown value, or it
     * emits a competing resize and the batch commits the smaller of the two.</p>
     *
     * <p>This is the case that distinguishes a bounds record kept by the prepare path from one
     * derived from the queue: the spacing tools, the routing overflow passes and the icon-band
     * reservation write group bounds the same way — a queued {@code UpdateViewObjectCommand} that no
     * prepare tail ever records.</p>
     */
    @Test
    public void shouldSeeGeometryFromANonPrimaryWriter_whenABulkPassGrewTheGroupEarlierInTheBatch()
            throws Exception {
        IDiagramModelGroup liveOuter = factory.createDiagramModelGroup();
        liveOuter.setId("grp-live-outer");
        liveOuter.setName("LiveOuter");
        liveOuter.setBounds(0, 0, 200, 200);
        view.getChildren().add(liveOuter);

        IDiagramModelGroup liveInnerB = factory.createDiagramModelGroup();
        liveInnerB.setId("grp-live-inner-b");
        liveInnerB.setName("LiveInnerB");
        liveInnerB.setBounds(10, 10, 100, 100);
        liveOuter.getChildren().add(liveInnerB);

        IDiagramModelArchimateObject liveLeafB = factory.createDiagramModelArchimateObject();
        liveLeafB.setId("obj-live-leaf-b");
        liveLeafB.setArchimateElement(actor);
        liveLeafB.setBounds(5, 5, 40, 40);
        liveInnerB.getChildren().add(liveLeafB);

        dispatcher.beginBatch(SESSION, "bulk writer, then a plain cascade over what it grew");
        accessor.executeBulk(SESSION, List.of(
                new BulkOperation("add-group-to-view", Map.of(
                        "viewId", view.getId(), "label", "BInnerA",
                        "parentViewObjectId", liveOuter.getId(),
                        "x", 10, "y", 10, "width", 100, "height", 100)),
                new BulkOperation("add-to-view", Map.of(
                        "viewId", view.getId(), "elementId", actor.getId(),
                        "parentViewObjectId", "$0.id",
                        "x", 5, "y", 5, "width", 40, "height", 40)),
                new BulkOperation("update-view-object", Map.of(
                        "viewObjectId", "$1.id",
                        "x", 650, "y", 650, "width", 120, "height", 120))), null, false);

        update(liveLeafB.getId(), 150, 150, 120, 120);
        dispatcher.endBatch(SESSION, true);

        assertBounds("the live inner group fits its own pushed-out child",
                find(view, liveInnerB.getId()), 10, 10, 280, 280);
        assertBounds("the shared live group keeps the size the bulk pass's cascade gave it",
                find(view, liveOuter.getId()), 0, 0, 800, 800);
    }

    /**
     * Both cascade resizes ride in the same compound as the update that provoked them, in walk
     * order (child first, then each ancestor outward), and the batch commits as one undo unit.
     *
     * <p>Membership and ordering are what this harness can prove; the real-{@code CommandStack}
     * undo of the production compound stays a live-gate observation, for the reason given in the
     * class header.</p>
     */
    @Test
    public void shouldBundleBothCascadeResizesIntoOneUndoUnit() throws Exception {
        dispatcher.beginBatch(SESSION, "cascade as one unit");
        String outer = group("QOuter", 0, 0, 200, 200, null);
        String inner = group("QInner", 10, 10, 100, 100, outer);
        String leaf = element(5, 5, 40, 40, inner);
        update(leaf, 150, 150, 120, 120);
        dispatcher.endBatch(SESSION, true);

        assertTrue("the batch must dispatch as one compound", lastDispatched instanceof CompoundCommand);
        CompoundCommand batch = (CompoundCommand) lastDispatched;
        assertEquals("four queued operations", 4, batch.getCommands().size());

        Object last = batch.getCommands().get(3);
        assertTrue("the update must itself be a compound carrying its cascade resizes",
                last instanceof CompoundCommand);
        List<?> members = ((CompoundCommand) last).getCommands();
        assertEquals("the update plus one resize per grown ancestor", 3, members.size());

        // Ordering: the provoking update first, then each ancestor outward in walk order.
        assertEquals("the leaf update comes first",
                leaf, ((UpdateViewObjectCommand) members.get(0)).getDiagramObject().getId());
        assertEquals("then the parent it pushed out of",
                inner, ((UpdateViewObjectCommand) members.get(1)).getDiagramObject().getId());
        assertEquals("then that group's own container",
                outer, ((UpdateViewObjectCommand) members.get(2)).getDiagramObject().getId());

        stack.undo();
        assertEquals("one undo must empty the view", 0, view.getChildren().size());
        assertNotNull("ids must have been real before the undo", leaf);
    }

    /**
     * The second cascade emits <em>no</em> command at all for the shared grandparent, rather than a
     * second one that happens to carry the right number.
     *
     * <p>Geometry alone would not catch the difference while the correct value is the earlier one:
     * two competing resizes whose later member won by luck would satisfy the headline assertion and
     * still be wrong the moment a third writer joined. What must hold is that the later prepare's
     * overflow test sees the grown container and declines — one resize per container per batch.</p>
     *
     * <p>The whole batch still commits as one ordered undo unit, adds before updates, and each
     * update still carries the resizes it provoked in its own compound.</p>
     */
    @Test
    public void shouldEmitNoSecondGrandparentResize_whenTheLaterCascadeAlreadyFits() throws Exception {
        dispatcher.beginBatch(SESSION, "one resize per group");
        String outer = group("QOuter", 0, 0, 200, 200, null);
        String innerA = group("QInnerA", 10, 10, 100, 100, outer);
        String innerB = group("QInnerB", 10, 10, 100, 100, outer);
        String leafA = element(5, 5, 40, 40, innerA);
        String leafB = element(5, 5, 40, 40, innerB);
        update(leafA, 650, 650, 120, 120);
        update(leafB, 150, 150, 120, 120);
        dispatcher.endBatch(SESSION, true);

        assertTrue("the batch must dispatch as one compound", lastDispatched instanceof CompoundCommand);
        CompoundCommand batch = (CompoundCommand) lastDispatched;
        assertEquals("seven queued operations, adds first then updates", 7, batch.getCommands().size());

        List<?> first = ((CompoundCommand) batch.getCommands().get(5)).getCommands();
        assertEquals("the first update carries its leaf plus both grown ancestors", 3, first.size());
        assertEquals("the leaf update comes first",
                leafA, ((UpdateViewObjectCommand) first.get(0)).getDiagramObject().getId());
        assertEquals("then the parent it pushed out of",
                innerA, ((UpdateViewObjectCommand) first.get(1)).getDiagramObject().getId());
        assertEquals("then the shared grandparent",
                outer, ((UpdateViewObjectCommand) first.get(2)).getDiagramObject().getId());

        List<?> second = ((CompoundCommand) batch.getCommands().get(6)).getCommands();
        assertEquals("the second update carries its leaf and ONLY its own parent", 2, second.size());
        assertEquals("the leaf update comes first",
                leafB, ((UpdateViewObjectCommand) second.get(0)).getDiagramObject().getId());
        assertEquals("and its parent — not a competing resize of the grandparent",
                innerB, ((UpdateViewObjectCommand) second.get(1)).getDiagramObject().getId());

        stack.undo();
        assertEquals("one undo must empty the view", 0, view.getChildren().size());
    }

    /**
     * The two-cascade fix survives the approval leg. {@code storeAsProposal} re-runs the prepare
     * when the proposal is approved — on a later request — so the queued geometry has to be resolved
     * there too, at approve time, against the queue as it then stands. Approving the first update
     * puts its cascade in the queue, which is what the second one must then measure against.
     */
    @Test
    public void shouldFitBothCascades_whenBothUpdatesAreApprovedInsideOpenBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "two cascades through approval");
        String outer = group("QOuter", 0, 0, 200, 200, null);
        String innerA = group("QInnerA", 10, 10, 100, 100, outer);
        String innerB = group("QInnerB", 10, 10, 100, 100, outer);
        String leafA = element(5, 5, 40, 40, innerA);
        String leafB = element(5, 5, 40, 40, innerB);

        dispatcher.setApprovalModeProvider(() -> true);
        var proposedA = accessor.updateViewObject(SESSION, leafA, 650, 650, 120, 120,
                null, null, null, null, null, null, null, null);
        assertNotNull("the first update must approve",
                dispatcher.approveProposal(SESSION, proposedA.proposalContext().proposalId()));

        var proposedB = accessor.updateViewObject(SESSION, leafB, 150, 150, 120, 120,
                null, null, null, null, null, null, null, null);
        assertNotNull("the second update must approve",
                dispatcher.approveProposal(SESSION, proposedB.proposalContext().proposalId()));

        dispatcher.setApprovalModeProvider(() -> false);
        dispatcher.endBatch(SESSION, true);

        assertBounds("inner A fits its far-pushed child", find(view, innerA), 10, 10, 780, 780);
        assertBounds("inner B fits its child", find(view, innerB), 10, 10, 280, 280);
        assertBounds("the shared grandparent survives both approvals",
                find(view, outer), 0, 0, 800, 800);
    }

    /**
     * The cascade survives the approval leg. {@code storeAsProposal} re-runs the prepare when the
     * proposal is approved — on a later request — so the queue-derived containment has to be
     * resolved there too, not only where the proposal was stored.
     */
    @Test
    public void shouldCascadeToQueuedGrandparent_whenTheUpdateIsApprovedInsideOpenBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "cascade through approval");
        String outer = group("QOuter", 0, 0, 200, 200, null);
        String inner = group("QInner", 10, 10, 100, 100, outer);
        String leaf = element(5, 5, 40, 40, inner);

        dispatcher.setApprovalModeProvider(() -> true);
        var proposed = accessor.updateViewObject(SESSION, leaf, 150, 150, 120, 120,
                null, null, null, null, null, null, null, null);
        assertTrue("the update must be stored as a proposal", proposed.isProposal());

        assertNotNull("the proposal must approve, not reject as stale",
                dispatcher.approveProposal(SESSION, proposed.proposalContext().proposalId()));

        dispatcher.setApprovalModeProvider(() -> false);
        dispatcher.endBatch(SESSION, true);

        assertBounds("inner fits its pushed-out child", find(view, inner), 10, 10, 280, 280);
        assertBounds("outer fits the grown inner after approval", find(view, outer), 0, 0, 300, 300);
    }

    // ---- the queue walk is shared, the accepted kinds are not -----------------------------------

    /**
     * Widening the walk to serve updates must not widen what may be a nesting parent: a queued note
     * is a valid update target and an invalid parent, in the same batch.
     */
    @Test
    public void shouldRejectQueuedNoteAsParent_whileAcceptingItAsUpdateTarget() throws Exception {
        dispatcher.beginBatch(SESSION, "note is target not parent");
        String n = note("Target", 10, 10, 150, 60, null);

        assertParentNotFound(() -> element(5, 5, 40, 40, n), n);
        assertNull("a queued note is not a container", dispatcher.queuedParentContainer(SESSION, n));
        assertNotNull("a queued note is a resolvable update target",
                dispatcher.queuedViewObject(SESSION, n));

        update(n, 40, 40, 200, 90);
        dispatcher.endBatch(SESSION, true);

        assertBounds("the note itself still updates", find(view, n), 40, 40, 200, 90);
    }

    // ---- wrapped commands ----------------------------------------------------------------------

    /**
     * The queued add is routinely wrapped in a compound — here by parent-fill recession, because the
     * group is nested in a live unauthored-fill host. A walk that only inspected top-level queue
     * entries would miss it.
     */
    @Test
    public void shouldResolveQueuedTarget_whenItsAddIsWrappedByFillRecession() throws Exception {
        IDiagramModelGroup liveHost = factory.createDiagramModelGroup();
        liveHost.setId("grp-live-host");
        liveHost.setName("Live Host");
        liveHost.setBounds(0, 0, 900, 900);
        view.getChildren().add(liveHost);

        dispatcher.beginBatch(SESSION, "recede-wrapped add then update");
        String nested = group("Nested", 10, 10, 200, 200, liveHost.getId());
        update(nested, 20, 20, 300, 300);
        dispatcher.endBatch(SESSION, true);

        assertBounds("recede-wrapped queued group after update", find(view, nested), 20, 20, 300, 300);
    }

    /** The other wrap: {@code autoConnect} builds its own compound around the add. */
    @Test
    public void shouldResolveQueuedTarget_whenItsAddIsWrappedByAutoConnect() throws Exception {
        IDiagramModelArchimateObject livePeer = factory.createDiagramModelArchimateObject();
        livePeer.setId("obj-live-peer");
        livePeer.setArchimateElement((com.archimatetool.model.IArchimateElement)
                model.getFolder(FolderType.BUSINESS).getElements().get(1));
        livePeer.setBounds(700, 700, 120, 60);
        view.getChildren().add(livePeer);

        dispatcher.beginBatch(SESSION, "auto-connect-wrapped add then update");
        String child = accessor.addToView(SESSION, view.getId(), actor.getId(), 10, 10, 100, 100,
                true, null, null, null).entity().viewObject().viewObjectId();
        update(child, 300, 300, 140, 140);
        dispatcher.endBatch(SESSION, true);

        assertBounds("auto-connect-wrapped queued object after update", find(view, child), 300, 300, 140, 140);
    }

    // ---- a detached target never throws downstream ----------------------------------------------

    /**
     * The icon-band gate now runs against a detached container whose {@code getChildren()} is empty
     * — a branch that was unreachable before a queued target could be resolved. It must complete
     * without throwing and reserve nothing, there being no child in the corner.
     */
    @Test
    public void shouldNotThrowOnIconBand_whenTargetIsQueuedAndHasNoChildren() throws Exception {
        dispatcher.beginBatch(SESSION, "icon band on a queued container");
        String outer = group("QOuter", 500, 0, 200, 200, null);
        accessor.updateViewObject(SESSION, outer, null, null, null, null, null, null,
                new ImageParams(null, "bottom-left", null), null, null, null, null, null);
        dispatcher.endBatch(SESSION, true);

        assertBounds("no band reserved for an empty corner", find(view, outer), 500, 0, 200, 200);
    }

    /**
     * Anchored-children repositioning reads the target's diagram, which is null while detached, so
     * it returns the base command untouched rather than throwing.
     */
    @Test
    public void shouldNotThrowOnAnchoredChildrenPass_whenTargetIsQueued() throws Exception {
        dispatcher.beginBatch(SESSION, "anchored children pass on a queued target");
        String outer = group("QOuter", 500, 0, 200, 200, null);
        update(outer, 520, 20, 240, 240);
        dispatcher.endBatch(SESSION, true);

        assertBounds("queued group moved without an anchored-children throw",
                find(view, outer), 520, 20, 240, 240);
    }

    // ---- anchoring across the queue seam ------------------------------------------------------
    //
    // An anchor is resolved into a concrete x/y while the request is prepared, by looking the
    // target up inside the anchored object's own diagram. Inside a batch that lookup can fail for
    // a reason that has nothing to do with the request being wrong: either object may still be
    // detached, because every add-*-to-view defers containment to its command's execute(). The
    // prepare therefore leaves the position un-anchored, and the command re-resolves it at
    // execute() — by which point queue order guarantees the adds have run and both objects are
    // attached. These pin all three pairings, plus the two ways the re-resolution declines.

    /**
     * A queued object anchored to a live target lands against that target's edge at commit. The
     * prepare could not resolve it — the object is detached, so it has no diagram to search — but
     * its add executes first, so the command's own execute() can.
     */
    @Test
    public void shouldApplyAnchor_whenTheAnchoredObjectIsStillQueued() throws Exception {
        IDiagramModelObject liveTarget = liveElement("obj-anchor-target", 100, 100, 200, 100, view);

        dispatcher.beginBatch(SESSION, "anchor a queued object");
        String child = element(0, 0, 80, 40, null);
        anchor(child, liveTarget.getId(), "below", 0, 10);
        dispatcher.endBatch(SESSION, true);

        // below => (target.x + dx, target.y + target.height + dy) = (100 + 0, 100 + 100 + 10)
        assertBounds("queued object anchored below a live target", find(view, child), 100, 210, 80, 40);
    }

    /**
     * The mirror pairing: the anchored object is live and the <em>target</em> is queued. The
     * prepare-time lookup walks committed containment, so a detached target is equally invisible
     * to it; the same execute-time resolution covers both.
     */
    @Test
    public void shouldApplyAnchor_whenTheAnchorTargetIsStillQueued() throws Exception {
        IDiagramModelObject liveObject = liveElement("obj-anchored-live", 0, 0, 80, 40, view);

        dispatcher.beginBatch(SESSION, "queue a target, then anchor a live object to it");
        String target = element(300, 300, 200, 100, null);
        anchor(liveObject.getId(), target, "below", 0, 10);
        dispatcher.endBatch(SESSION, true);

        assertBounds("live object anchored below a queued target",
                find(view, liveObject.getId()), 300, 410, 80, 40);
    }

    /**
     * Both queued — the pairing an agent building a whole view in one batch actually hits. A
     * non-default edge with non-zero offsets on both axes, so no coincidence of the requested
     * position can stand in for a real resolution.
     */
    @Test
    public void shouldApplyAnchor_whenBothTheTargetAndTheAnchoredObjectAreQueued() throws Exception {
        dispatcher.beginBatch(SESSION, "anchor one queued object to another");
        String target = element(400, 200, 150, 60, null);
        String child = element(5, 5, 80, 40, null);
        anchor(child, target, "right", 20, 5);
        dispatcher.endBatch(SESSION, true);

        // right => (target.x + target.width + dx, target.y + dy) = (400 + 150 + 20, 200 + 5)
        assertBounds("queued object anchored right of a queued target", find(view, child), 570, 205, 80, 40);
    }

    /**
     * Boundary pin: an anchor target that resolves nowhere at all is still ignored silently, at
     * both prepare and execute. The object commits at its requested position, nothing throws, and
     * all four anchor entries are persisted so a later move of a target that appears afterwards
     * can still drag it.
     */
    @Test
    public void shouldKeepTheRequestedPosition_whenTheAnchorTargetResolvesNowhere() throws Exception {
        dispatcher.beginBatch(SESSION, "anchor a queued object to an id that does not exist");
        String child = element(60, 70, 80, 40, null);
        anchor(child, "no-such-view-object", "below", 0, 10);
        dispatcher.endBatch(SESSION, true);

        IDiagramModelObject childObj = find(view, child);
        assertBounds("an unresolvable anchor leaves the requested position alone",
                childObj, 60, 70, 80, 40);
        assertEquals("the anchor target is recorded even though it resolved nowhere",
                "no-such-view-object",
                childObj.getFeatures().getString(AnchorResolver.ANCHOR_TARGET_FEATURE, null));
        assertEquals("below", childObj.getFeatures().getString(AnchorResolver.ANCHOR_EDGE_FEATURE, null));
        assertEquals("0", childObj.getFeatures().getString(AnchorResolver.ANCHOR_DX_FEATURE, null));
        assertEquals("10", childObj.getFeatures().getString(AnchorResolver.ANCHOR_DY_FEATURE, null));
    }

    /**
     * The last half of the batch/non-batch anchor asymmetry, now closed.
     *
     * <p>Bounds are stored relative to the immediate parent, so a top-level object cannot be
     * resolved against a target nested in a group without accumulating the group's offset. Outside
     * a batch that pairing is rejected up front by the same-space guard, pinned by
     * {@link #shouldThrowCrossSpaceAnchor_whenTheIdenticalRequestIsMadeOutsideABatch()}, which is
     * the reference this symmetry is measured against. Inside a batch it used to be accepted: the
     * prepare-time lookup walks committed containment from the anchored object's diagram, and a
     * queued object has none, so the lookup failed for a reason that had nothing to do with
     * coordinate spaces and the guard never saw the pairing. The request committed unmoved and
     * reported success.</p>
     *
     * <p>The pairing is now reached through the diagram the object's queued add is destined for,
     * so the guard runs and the request is refused with the same message and code as outside a
     * batch. What is deliberately <em>not</em> reached is the position — see
     * {@link #shouldResolveAtExecute_whenAQueuedObjectIsAnchoredWithinItsOwnCoordinateSpace()}.</p>
     */
    @Test
    public void shouldRejectCrossSpaceAnchor_whenAQueuedObjectIsAnchoredAcrossCoordinateSpaces()
            throws Exception {
        IDiagramModelGroup liveGroup = factory.createDiagramModelGroup();
        liveGroup.setId("grp-live-anchor-host");
        liveGroup.setBounds(400, 400, 200, 200);
        view.getChildren().add(liveGroup);
        IDiagramModelObject nested = liveElement("obj-nested-target", 10, 10, 50, 50, liveGroup);

        dispatcher.beginBatch(SESSION, "anchor a top-level queued object to a nested target");
        String child = element(0, 0, 80, 40, null);
        try {
            anchor(child, nested.getId(), "below", 0, 10);
            fail("a queued top-level object anchored to a nested target must be rejected, exactly "
                    + "as the identical request is outside a batch");
        } catch (ModelAccessException e) {
            assertEquals("anchorTarget must share the same parent container as the anchored object",
                    e.getMessage());
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
        dispatcher.endBatch(SESSION, true);

        assertBounds("the rejected anchor left the object at the position it was queued with",
                find(view, child), 0, 0, 80, 40);
        assertNull("and no anchor was recorded, since the request never took effect",
                find(view, child).getFeatures().getString(AnchorResolver.ANCHOR_TARGET_FEATURE, null));
    }

    /**
     * The same rejection when the anchored object sits <em>two</em> hops from its destined diagram.
     *
     * <p>Reaching the pairing means answering "which diagram will this object be in", and a queued
     * object's destined parent may itself be a queued container whose own add has not run either.
     * One hop up finds a container that is still detached and therefore still answers null for its
     * diagram; only climbing the declared containment reaches the view. Stopping at one hop lets
     * the lookup fail for exactly the reason the bridge exists to eliminate — detachment rather
     * than coordinate spaces — so the guard never runs and an anchor no validation approved is
     * persisted on the object.</p>
     */
    @Test
    public void shouldRejectCrossSpaceAnchor_whenTheQueuedObjectIsNestedInAQueuedGroup()
            throws Exception {
        IDiagramModelObject outsideTarget = liveElement("obj-outside-target", 700, 700, 60, 30, view);

        dispatcher.beginBatch(SESSION, "queue a group, queue a child in it, anchor it outward");
        String queuedGroup = group("QOuter", 300, 300, 200, 200, null);
        String nestedChild = element(10, 10, 80, 40, queuedGroup);
        try {
            anchor(nestedChild, outsideTarget.getId(), "below", 0, 10);
            fail("an object destined for a queued group must not be anchored to a target outside "
                    + "that group, however many queued containers stand between it and the view");
        } catch (ModelAccessException e) {
            assertEquals("anchorTarget must share the same parent container as the anchored object",
                    e.getMessage());
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
        dispatcher.endBatch(SESSION, true);

        assertNull("no anchor may be persisted by a request that was refused",
                find(view, nestedChild).getFeatures()
                        .getString(AnchorResolver.ANCHOR_TARGET_FEATURE, null));
    }

    /**
     * The other side of the object-side bridge, and the pin that proves it is validation-only.
     *
     * <p>A queued object anchored to a target in its <em>own</em> coordinate space passes the
     * guard. Reaching the pairing must stop there: the position stays for the object's own command
     * to resolve at {@code execute()}, where containment is real.</p>
     *
     * <p>The discriminating observation is the <em>response</em>, not the committed geometry —
     * both a prepare-resolved and an execute-resolved anchor land the object in the same place,
     * because the command re-resolves the edge outright when it holds no prepare-time snapshot.
     * What differs is what the prepare tells the caller in the meantime. A prepare that resolved
     * the anchor would hand back the anchored position for an object whose containment it cannot
     * see, which is the divergence the deferred-mode projection exists to declare rather than
     * fake. So the queued response must still carry the position the object was queued with, and
     * the model must still end up at the anchored one.</p>
     */
    @Test
    public void shouldResolveAtExecute_whenAQueuedObjectIsAnchoredWithinItsOwnCoordinateSpace()
            throws Exception {
        IDiagramModelObject target = liveElement("obj-same-space-target", 100, 100, 50, 50, view);

        dispatcher.beginBatch(SESSION, "queue an object and anchor it to a top-level target");
        String child = element(0, 0, 80, 40, null);
        ViewObjectDto queuedResponse = accessor.updateViewObject(SESSION, child,
                null, null, null, null, null, null, null, null,
                target.getId(), "below", 0, 10).entity();

        assertEquals("the queued response must not claim an anchored x it cannot yet know",
                0, queuedResponse.x());
        assertEquals("nor an anchored y", 0, queuedResponse.y());

        dispatcher.endBatch(SESSION, true);

        assertBounds("and the object must still land at the position its own command resolves at "
                        + "execute, once containment is real (100, 100 + 50 + 10)",
                find(view, child), 100, 160, 80, 40);
    }

    /**
     * The coordinate-space rule holds through the queued-target bridge, and this is where it gets
     * STRICTER than it was.
     *
     * <p>Resolving a target the batch has queued means the same-space check finally sees a pairing
     * it could not see before: previously the lookup failed for an unrelated reason — the target was
     * detached — so validation never ran and the request committed silently unmoved. Now the target
     * resolves through the batch's own record, its destined container is known, and a genuinely
     * cross-space anchor is rejected up front exactly as the identical request is outside a batch.
     *
     * <p>Both numbers: the live object used to commit at its unchanged {@code 0,0} with the anchor
     * recorded and quietly ineffective; it now never gets that far, and the caller is told why. This
     * narrows the batch/non-batch asymmetry pinned above in one sub-case rather than widening it —
     * a consequence of resolving queued targets, not a change to the validation itself, which is
     * untouched.</p>
     */
    @Test
    public void shouldRejectCrossSpaceAnchor_whenTheQueuedTargetIsDestinedForAnotherContainer()
            throws Exception {
        IDiagramModelObject topLevel = liveElement("obj-live-top", 0, 0, 80, 40, view);

        dispatcher.beginBatch(SESSION, "queue a group and a target inside it");
        String queuedGroup = group("B", 400, 400, 200, 200, null);
        String queuedNested = element(10, 10, 50, 50, queuedGroup);
        try {
            anchor(topLevel.getId(), queuedNested, "below", 0, 10);
            fail("a top-level object anchored to a target destined for a group must be rejected");
        } catch (ModelAccessException e) {
            assertEquals("anchorTarget must share the same parent container as the anchored object",
                    e.getMessage());
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
        dispatcher.endBatch(SESSION, true);

        assertBounds("the rejected request left the object exactly where it was",
                find(view, topLevel.getId()), 0, 0, 80, 40);
    }

    /**
     * The other side of the same bridge: when the queued target IS destined for the anchored
     * object's own coordinate space, the anchor resolves at prepare against the bounds the batch
     * built the target with. Previously the target did not resolve at all and the object stayed put.
     */
    @Test
    public void shouldResolveAgainstAQueuedTarget_whenBothShareACoordinateSpace() throws Exception {
        IDiagramModelObject topLevel = liveElement("obj-live-top-2", 0, 0, 80, 40, view);

        dispatcher.beginBatch(SESSION, "queue a top-level target and anchor a live object to it");
        String queuedTarget = element(100, 100, 50, 50, null);
        anchor(topLevel.getId(), queuedTarget, "below", 0, 10);
        dispatcher.endBatch(SESSION, true);

        assertBounds("the live object lands below the queued target (100 + 50 + 10), "
                        + "no longer stranded at 0,0",
                find(view, topLevel.getId()), 100, 160, 80, 40);
    }

    /** The other half of the asymmetry: outside a batch the identical pairing is rejected up front. */
    @Test
    public void shouldThrowCrossSpaceAnchor_whenTheIdenticalRequestIsMadeOutsideABatch() throws Exception {
        IDiagramModelGroup liveGroup = factory.createDiagramModelGroup();
        liveGroup.setId("grp-live-anchor-host");
        liveGroup.setBounds(400, 400, 200, 200);
        view.getChildren().add(liveGroup);
        IDiagramModelObject nested = liveElement("obj-nested-target", 10, 10, 50, 50, liveGroup);
        IDiagramModelObject topLevel = liveElement("obj-top-level", 0, 0, 80, 40, view);

        try {
            anchor(topLevel.getId(), nested.getId(), "below", 0, 10);
            fail("expected the same-coordinate-space rejection outside a batch");
        } catch (ModelAccessException e) {
            assertEquals("anchorTarget must share the same parent container as the anchored object",
                    e.getMessage());
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
    }

    /**
     * A dependent of an anchored object tracks where that object <em>lands</em>, not where the
     * prepare guessed it would.
     *
     * <p>When an object's bounds change, the commit-time cascade repositions every object anchored
     * to it. That cascade is built while the request is prepared, from the position the prepare
     * computed. The divergence being pinned here arose one step earlier: the prepare resolved
     * {@code T}'s anchor against {@code G}'s bounds <em>as the model held them</em>, even though an
     * earlier command of the same batch had already queued {@code G}'s move — so the projection was
     * wrong before the cascade ever read it, and every dependent inherited the error while
     * {@code T} itself silently recovered by re-resolving at {@code execute()}.</p>
     *
     * <p>Fixed by measuring the anchor target against the batch's queued geometry, which removes the
     * divergence at its source rather than teaching the cascade to run later. Both numbers:
     * {@code T} lands at {@code 400,780} either way; its dependent {@code C} was left at
     * {@code 400,530} — computed from {@code T}'s stale projection {@code 400,480} — and now lands
     * at {@code 400,830}, which is {@code T}'s real bottom plus its own {@code dy}.</p>
     */
    @Test
    public void shouldMoveDependentsToTheLandedPosition_whenTheirTargetIsAnchoredInSameBatch() throws Exception {
        IDiagramModelObject g = liveElement("obj-cascade-target", 400, 400, 150, 60, view);
        IDiagramModelObject t = liveElement("obj-cascade-middle", 100, 100, 80, 40, view);
        IDiagramModelObject c = liveElement("obj-cascade-dependent", 100, 150, 80, 40, view);
        c.getFeatures().putString(AnchorResolver.ANCHOR_TARGET_FEATURE, t.getId());
        c.getFeatures().putString(AnchorResolver.ANCHOR_EDGE_FEATURE, "below");
        c.getFeatures().putString(AnchorResolver.ANCHOR_DX_FEATURE, "0");
        c.getFeatures().putString(AnchorResolver.ANCHOR_DY_FEATURE, "10");

        dispatcher.beginBatch(SESSION, "move a target, then anchor to it, with a dependent in tow");
        update(g.getId(), 400, 700, 150, 60);
        anchor(t.getId(), g.getId(), "below", 0, 20);
        dispatcher.endBatch(SESSION, true);

        assertBounds("the anchored object resolves against the target's queued position",
                find(view, t.getId()), 400, 780, 80, 40);
        assertBounds("its dependent tracks the LANDED position (780 + 40 + 10), not the "
                        + "stale projection 530",
                find(view, c.getId()), 400, 830, 80, 40);
    }

    /**
     * The same property, reached through a <em>detached</em> anchor target instead of a moved one:
     * {@code T} is anchored to an object this batch created, whose add command has not executed, so
     * the committed-containment lookup cannot see it at all.
     *
     * <p>Recorded separately because it is a different bridge — the batch's own record of what it
     * has queued, rather than its record of what it has re-sized — and because before the anchored
     * object resolved at execute it did not move, so its dependents were trivially consistent and
     * this route did not exist. Both numbers: {@code C} was left at its pre-batch {@code 100,150};
     * it now lands at {@code 400,530}, below {@code T}'s own landing at {@code 400,480}.</p>
     */
    @Test
    public void shouldMoveDependents_whenTheirTargetIsAnchoredToAQueuedObject() throws Exception {
        IDiagramModelObject t = liveElement("obj-cascade-middle", 100, 100, 80, 40, view);
        IDiagramModelObject c = liveElement("obj-cascade-dependent", 100, 150, 80, 40, view);
        c.getFeatures().putString(AnchorResolver.ANCHOR_TARGET_FEATURE, t.getId());
        c.getFeatures().putString(AnchorResolver.ANCHOR_EDGE_FEATURE, "below");
        c.getFeatures().putString(AnchorResolver.ANCHOR_DX_FEATURE, "0");
        c.getFeatures().putString(AnchorResolver.ANCHOR_DY_FEATURE, "10");

        dispatcher.beginBatch(SESSION, "anchor an object with dependents to a queued target");
        String queuedTarget = element(400, 400, 150, 60, null);
        anchor(t.getId(), queuedTarget, "below", 0, 20);
        dispatcher.endBatch(SESSION, true);

        assertBounds("the anchored object lands below the queued target",
                find(view, t.getId()), 400, 480, 80, 40);
        assertBounds("its dependent follows it (480 + 40 + 10), no longer left at 100,150",
                find(view, c.getId()), 400, 530, 80, 40);
    }

    /**
     * Boundary pin on a known divergence, recorded with both numbers. A queued operation's success
     * response is built while the request is prepared, so it carries the position the prepare could
     * compute — which for an anchor across the queue seam is the un-anchored one. The model then
     * lands somewhere else when the command resolves the anchor at execute.
     *
     * <p>Deliberately not repaired here: making the queued response report the position that will
     * finally land is a property of every deferred operation, not of anchoring, and is tracked
     * separately. Widening this one DTO would hide the general problem rather than fix it.</p>
     */
    @Test
    public void shouldEchoThePrepareTimePosition_whenAQueuedAnchorResolvesElsewhereAtCommit() throws Exception {
        IDiagramModelObject liveTarget = liveElement("obj-anchor-target", 100, 100, 200, 100, view);

        dispatcher.beginBatch(SESSION, "anchor a queued object and inspect the response");
        String child = element(0, 0, 80, 40, null);
        ViewObjectDto echoed = accessor.updateViewObject(SESSION, child, null, null, null, null,
                null, null, null, null, liveTarget.getId(), "below", 0, 10).entity();
        dispatcher.endBatch(SESSION, true);

        assertEquals("the queued response echoes the prepare-time projection, x", 0, echoed.x());
        assertEquals("the queued response echoes the prepare-time projection, y", 0, echoed.y());
        assertBounds("while the model lands at the resolved edge", find(view, child), 100, 210, 80, 40);
    }

    /**
     * A queued group grows to contain a queued child that its anchor puts beyond the group's edge.
     *
     * <p>The parent-fit cascade runs while the request is prepared, against the position the prepare
     * computes. For a child anchored to a sibling the batch has also queued, that used to be the
     * child's <em>requested</em> position: the target's add command had not executed, so the
     * committed-containment lookup could not find it and the anchor was left unresolved until the
     * child's own command ran at commit. The child therefore landed correctly and its group did not
     * grow — the two disagreed because only one of them had waited.</p>
     *
     * <p>Resolving the target through the batch's own record of what it has queued closes that,
     * without moving the cascade later: both objects' bounds are known at prepare, because the adds
     * that built them set those bounds at construction. Both numbers: {@code QG} stayed at its
     * declared {@code 200} while the child's bottom reached {@code 210}; it now grows to
     * {@code 220}, the child's bottom plus the group padding.</p>
     */
    @Test
    public void shouldGrowTheQueuedGroup_whenAQueuedChildIsAnchoredBeyondIt() throws Exception {
        dispatcher.beginBatch(SESSION, "anchor a queued child inside a queued group");
        String qg = group("QG", 0, 0, 200, 200, null);
        String sibling = element(10, 10, 100, 150, qg);
        String child = element(5, 5, 40, 40, qg);
        anchor(child, sibling, "below", 0, 10);
        dispatcher.endBatch(SESSION, true);

        // below => (10 + 0, 10 + 150 + 10); the child's bottom is therefore 170 + 40 = 210.
        assertBounds("the anchored child lands below its queued sibling",
                find(view, child), 10, 170, 40, 40);
        assertEquals("the queued group grows to contain the anchored child (170 + 40 + 10), "
                        + "no longer stuck at its declared 200",
                220, find(view, qg).getBounds().getHeight());
    }

    // ---- lifetime ------------------------------------------------------------------------------

    /** A rolled-back add must leave nothing addressable. */
    @Test
    public void shouldNotResolveQueuedTarget_whenTheBatchWasRolledBack() throws Exception {
        dispatcher.beginBatch(SESSION, "add then roll back");
        String child = element(20, 20, 100, 100, null);
        dispatcher.endBatch(SESSION, false);

        dispatcher.beginBatch(SESSION, "second batch");
        assertViewObjectNotFound(() -> update(child, 1, 1, 10, 10), child);
        dispatcher.endBatch(SESSION, false);
    }

    /** After a commit the id resolves through the ordinary live path, not the queued one. */
    @Test
    public void shouldResolveThroughLivePath_whenAnEarlierBatchAlreadyCommitted() throws Exception {
        dispatcher.beginBatch(SESSION, "first batch");
        String child = element(20, 20, 100, 100, null);
        dispatcher.endBatch(SESSION, true);

        dispatcher.beginBatch(SESSION, "second batch");
        update(child, 60, 60, 130, 130);
        dispatcher.endBatch(SESSION, true);

        assertBounds("committed object updated by a later batch", find(view, child), 60, 60, 130, 130);
    }

    /** Outside a batch the lookup cannot fire, so a bogus id fails exactly as it does today. */
    @Test
    public void shouldFailIdentically_whenNotInABatch() {
        assertViewObjectNotFound(() -> update("no-such-object", 1, 1, 10, 10), "no-such-object");
        assertNull("no batch context at all", dispatcher.queuedViewObject(SESSION, "any-id"));
        assertNull("null id is never resolvable", dispatcher.queuedViewObject(SESSION, null));
    }

    // ---- approval ------------------------------------------------------------------------------

    /**
     * The approval lambda re-runs the prepare at approval-commit time, so its mode is whatever the
     * session is in <em>then</em>. With no batch open the lookup returns null and the proposal
     * behaves exactly as before.
     */
    @Test
    public void shouldNotResolveQueuedTarget_whenApprovalRunsOutsideBatch() {
        dispatcher.setApprovalModeProvider(() -> true);
        assertViewObjectNotFound(() -> update("no-such-object", 1, 1, 10, 10), "no-such-object");
    }

    /**
     * The other leg, driven end-to-end rather than assumed: with a batch open, a proposal's prepare
     * resolves the queued target both when it is stored and when it is re-run at approve time. The
     * re-run is load-bearing — without it the rebuild falls through to the live lookup and rejects
     * the proposal as stale.
     */
    @Test
    public void shouldUpdateQueuedTarget_whenProposalIsApprovedInsideOpenBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "queue an object");
        String outer = group("QOuter", 500, 0, 200, 200, null);

        dispatcher.setApprovalModeProvider(() -> true);
        var proposed = accessor.updateViewObject(SESSION, outer, null, null, null, 800,
                null, null, null, null, null, null, null, null);

        assertTrue("the update must be stored as a proposal, not rejected", proposed.isProposal());
        assertNotNull("the queued target must still be resolvable under approval mode",
                dispatcher.queuedViewObject(SESSION, outer));

        ApprovalResult approved = dispatcher.approveProposal(
                SESSION, proposed.proposalContext().proposalId());
        assertNotNull("the proposal must approve, not reject as stale", approved);

        dispatcher.setApprovalModeProvider(() -> false);
        dispatcher.endBatch(SESSION, true);

        assertBounds("the approved update must land on the queued object",
                find(view, outer), 500, 0, 200, 800);
    }

    // ---- one undo unit -------------------------------------------------------------------------

    /** The add and the update commit as one ordered compound and undo as a single step. */
    @Test
    public void shouldCommitAddAndUpdateAsOneUndoUnit() throws Exception {
        dispatcher.beginBatch(SESSION, "one unit");
        String outer = group("QOuter", 500, 0, 200, 200, null);
        String child = element(20, 20, 100, 100, outer);
        update(child, 150, 150, 120, 120);
        dispatcher.endBatch(SESSION, true);

        assertNotNull("object present after commit", find(view, child));
        assertTrue("the batch must dispatch as one compound", lastDispatched instanceof CompoundCommand);
        CompoundCommand compound = (CompoundCommand) lastDispatched;
        assertEquals("three queued operations", 3, compound.getCommands().size());

        stack.undo();
        assertEquals("one undo must empty the view", 0, view.getChildren().size());
        assertNotNull("outer id must have been real before the undo", outer);
    }

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
