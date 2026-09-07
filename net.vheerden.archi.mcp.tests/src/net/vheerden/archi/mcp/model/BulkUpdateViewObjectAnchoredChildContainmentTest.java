package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.BulkOperationResult;
import net.vheerden.archi.mcp.response.dto.MovedViewObjectDto;
import net.vheerden.archi.mcp.response.dto.ViewObjectDto;

/**
 * The same containment question on the paths that do not go through the single-tool prepare:
 * {@code bulk-mutate} by id, {@code bulk-mutate} by back-reference, and an open batch.
 *
 * <p>A fix that lands on one call path leaves the agent driving another exactly as blind, and these
 * three do not share a prepare. The by-id branch reaches the primary prepare; the back-reference
 * branch reaches a second one entirely, for an object created earlier in the same call whose add
 * command has not executed; the batch arm defers everything to commit time.</p>
 *
 * <p>As everywhere in this area, the landed position in the model is asserted before anything about
 * containment or the response — the move map's contract is that the caller commits what it records,
 * so a containment-only assertion would pass on a build where nothing moved at all.</p>
 */
public class BulkUpdateViewObjectAnchoredChildContainmentTest {

    private static final String SESSION = "bulk-anchored-containment-session";
    private static final int PADDING = ArchiModelAccessorImpl.DEFAULT_GROUP_PADDING;

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private CommandStack stack;
    private IArchimateModel model;
    private IArchimateDiagramModel view;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Bulk Anchored Containment Fixture");
        model.setId("model-bulk-anchored-containment");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Containment");
        diagrams.getElements().add(view);

        IFolder business = model.getFolder(FolderType.BUSINESS);
        for (int i = 1; i <= 4; i++) {
            IBusinessActor a = factory.createBusinessActor();
            a.setId("actor-" + i);
            a.setName("Actor " + i);
            business.getElements().add(a);
        }

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
        dispatcher.setApprovalModeProvider(() -> false);
        accessor = new ArchiModelAccessorImpl(stubModelManager, dispatcher);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private String add(String actorId, int x, int y, int w, int h, String parentId) {
        return accessor.addToView(SESSION, view.getId(), actorId, x, y, w, h, false, parentId,
                null, null).entity().viewObject().viewObjectId();
    }

    private String group(String label, int x, int y, int w, int h, String parentId) {
        return accessor.addGroupToView(SESSION, view.getId(), label, x, y, w, h, parentId, null, null)
                .entity().viewObjectId();
    }

    private void anchor(String childId, String targetId, String edge, int dx, int dy) {
        accessor.updateViewObject(SESSION, childId, null, null, null, null,
                null, null, null, null, targetId, edge, dx, dy);
    }

    private static Map<String, Object> op(Object... kv) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            params.put((String) kv[i], kv[i + 1]);
        }
        return params;
    }

    private static IDiagramModelObject find(IDiagramModelContainer container, String id) {
        for (IDiagramModelObject child : container.getChildren()) {
            if (id.equals(child.getId())) {
                return child;
            }
            if (child instanceof IDiagramModelContainer nested) {
                IDiagramModelObject hit = find(nested, id);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private void assertContained(String childId, String parentId, String why) {
        IBounds child = find(view, childId).getBounds();
        IBounds parent = find(view, parentId).getBounds();
        assertFalse(why + " — child (" + child.getX() + "," + child.getY() + ") "
                        + child.getWidth() + "x" + child.getHeight()
                        + " is outside parent " + parent.getWidth() + "x" + parent.getHeight()
                        + " (padding " + PADDING + ")",
                ParentFitCascade.childExceedsParentBounds(
                        child.getX(), child.getY(), child.getWidth(), child.getHeight(),
                        parent.getWidth(), parent.getHeight(), PADDING));
    }

    // ---- bulk-mutate, by id ----------------------------------------------------------------------

    /**
     * The by-id branch of {@code bulk-mutate}. It reaches the primary prepare, so the containment
     * outcome must match the single-tool call exactly — the same request delivered through a
     * different door cannot mean a different geometry.
     */
    @Test
    public void shouldContainTheDisplacedChild_whenBulkUpdatesAnAnchorTargetById() {
        String outerId = group("Outer", 0, 0, 400, 300, null);
        String innerId = group("Inner", 10, 10, 300, 200, outerId);
        String targetId = add("actor-1", 30, 30, 60, 30, innerId);
        String childId = add("actor-2", 30, 70, 60, 55, innerId);
        anchor(childId, targetId, "below", 0, 10);

        int childYBefore = find(view, childId).getBounds().getY();
        BulkMutationResult bulk = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("update-view-object", op(
                        "viewObjectId", targetId, "height", 500))),
                "bulk grow an anchor target", false);

        IBounds child = find(view, childId).getBounds();
        assertTrue("fixture must actually displace the child: " + childYBefore + " -> "
                + child.getY(), child.getY() != childYBefore);
        assertEquals("fixture guard: the child lands where the single-tool call puts it",
                540, child.getY());

        assertContained(childId, innerId, "the bulk by-id branch must fit the group around the "
                + "child it displaced");
        assertContained(innerId, outerId, "and cascade that growth to the grandparent");

        BulkOperationResult result = bulk.operations().get(0);
        assertFalse("the per-operation result must name the groups it grew",
                result.resizedAncestors().isEmpty());
        for (MovedViewObjectDto grown : result.resizedAncestors()) {
            assertEquals("the model must hold the height reported for " + grown.name(),
                    find(view, grown.viewObjectId()).getBounds().getHeight(), grown.newHeight());
        }
    }

    // ---- bulk-mutate, by back-reference ----------------------------------------------------------

    /**
     * The back-reference branch, which reaches the second prepare.
     *
     * <p>The fit it performs is the target's own. Whether it can ever also owe a fit for a
     * displaced child is settled by the boundary pin below.</p>
     */
    @Test
    public void shouldFitTheGroup_whenBulkUpdatesAnObjectItCreatedInTheSameCall() {
        String outerId = group("Outer", 0, 0, 400, 300, null);
        String innerId = group("Inner", 10, 10, 300, 200, outerId);
        int innerHeightBefore = find(view, innerId).getBounds().getHeight();

        BulkMutationResult bulk = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("add-to-view", op(
                        "viewId", view.getId(), "elementId", "actor-1",
                        "parentViewObjectId", innerId,
                        "x", 30, "y", 30, "width", 60, "height", 30)),
                new BulkOperation("update-view-object", op(
                        "viewObjectId", "$0.id", "height", 500))),
                "bulk create then grow", false);

        assertEquals("both operations must have run, or nothing below measures this branch: "
                + bulk.operations(), 2, bulk.operations().size());

        String targetId = bulk.operations().get(0).entityId();
        assertEquals("fixture guard: the back-referenced target really was grown", 500,
                find(view, targetId).getBounds().getHeight());
        assertTrue("fixture must actually grow the inner group: " + innerHeightBefore + " -> "
                + find(view, innerId).getBounds().getHeight(),
                find(view, innerId).getBounds().getHeight() > innerHeightBefore);

        assertContained(targetId, innerId, "the back-reference branch must fit the group around "
                + "the object it grew");
        assertContained(innerId, outerId, "and cascade that growth to the grandparent");
    }

    /**
     * Why the back-reference branch can never owe a fit for a <em>displaced</em> child, recorded as
     * a boundary rather than left as an untested branch.
     *
     * <p>One fact closes it, and it is the one the test below actually exercises:
     * {@code bulk-mutate}'s {@code update-view-object} extracts no anchor parameters at all, so an
     * anchor cannot be declared through this tool. The back-reference branch is entered only for an
     * object created earlier in the <em>same</em> call, which nothing outside that call can already
     * be anchored to.</p>
     *
     * <p>This argument once rested on a second leg as well — that a bulk call does not read an
     * enclosing batch's queue at all. That is no longer true: a nested bulk now resolves ids the
     * enclosing batch has queued, and the queued-anchor vocabulary is passed to this prepare along
     * with the rest. The boundary survives regardless, because it never depended on that leg: with
     * no anchor parameters on the surface there is nothing to declare an anchor <em>with</em>,
     * whatever the queue happens to hold. The leg was removed rather than repaired, so the recorded
     * reason matches the reason the test proves.</p>
     *
     * <p>So the map this branch hands to the fit is empty on every reachable input. The wiring is
     * still present and still correct — it costs nothing and it removes the asymmetry between the
     * two prepares — but the coverage claim stops here honestly instead of resting on a fixture
     * that cannot be built. If anchor parameters are ever added to the bulk surface, this test is
     * the one that will fail and say so.</p>
     */
    @Test
    public void shouldRejectAnAnchorDeclaration_soTheBackReferenceBranchHasNoDisplacedChildren() {
        String innerId = group("Inner", 10, 10, 300, 200, null);
        String childId = add("actor-2", 30, 70, 60, 55, innerId);

        try {
            accessor.executeBulk(SESSION, List.of(
                    new BulkOperation("update-view-object", op(
                            "viewObjectId", childId, "anchorTarget", childId,
                            "anchorEdge", "below", "anchorDy", 10))),
                    "declare an anchor through bulk", false);
            throw new AssertionError("bulk-mutate accepted an anchor declaration. The "
                    + "back-reference branch can now have displaced children, and this boundary "
                    + "must be replaced by a real containment pin.");
        } catch (ModelAccessException expected) {
            assertTrue("the rejection must be the no-recognised-field one, which is what proves "
                            + "the anchor parameters were not read: " + expected.getMessage(),
                    expected.getMessage().contains("At least one of"));
        }
    }

    // ---- open batch ------------------------------------------------------------------------------

    /**
     * The batch arm: the same update queued in a {@code begin-batch} and committed at the end.
     * Nothing executes until commit, so the fit is computed entirely against pending geometry —
     * which is exactly the case in which measuring the wrong rectangle is hardest to notice.
     */
    @Test
    public void shouldContainTheDisplacedChild_whenTheUpdateIsQueuedInABatch() {
        String outerId = group("Outer", 0, 0, 400, 300, null);
        String innerId = group("Inner", 10, 10, 300, 200, outerId);
        String targetId = add("actor-1", 30, 30, 60, 30, innerId);
        String childId = add("actor-2", 30, 70, 60, 55, innerId);
        anchor(childId, targetId, "below", 0, 10);

        int childYBefore = find(view, childId).getBounds().getY();

        dispatcher.beginBatch(SESSION, "queue a grow of an anchor target");
        ViewObjectDto queued = accessor.updateViewObject(SESSION, targetId,
                null, null, null, 500, null, null, null, null).entity();
        dispatcher.endBatch(SESSION, true);

        IBounds child = find(view, childId).getBounds();
        assertTrue("fixture must actually displace the child once the batch commits: "
                + childYBefore + " -> " + child.getY(), child.getY() != childYBefore);
        assertEquals("fixture guard: the child lands where the immediate call puts it",
                540, child.getY());

        assertContained(childId, innerId, "a queued update must fit the group around the child it "
                + "displaces, exactly as the immediate one does");
        assertContained(innerId, outerId, "and cascade that growth to the grandparent");

        assertFalse("the queued projection must still name the groups it will grow",
                queued.resizedAncestors().isEmpty());
        for (MovedViewObjectDto grown : queued.resizedAncestors()) {
            assertEquals("the committed model must hold the height the preview projected for "
                            + grown.name(),
                    find(view, grown.viewObjectId()).getBounds().getHeight(), grown.newHeight());
        }
    }

    /**
     * The same-batch queued-anchor route: the anchor is <em>declared</em> by one queued command and
     * <em>read</em> by a later prepare in the same batch, before the declaring command has run.
     *
     * <p>The persisted features still say whatever was true before the batch, so this route exists
     * only because the prepare consults the batch's queued declarations. It is the one where the fit
     * has the least ground truth available and the most opportunity to measure the wrong rectangle,
     * which is why it is pinned separately from the batch arm above.</p>
     */
    @Test
    public void shouldContainTheDisplacedChild_whenTheAnchorItselfWasDeclaredInTheSameBatch() {
        String outerId = group("Outer", 0, 0, 400, 300, null);
        String innerId = group("Inner", 10, 10, 300, 200, outerId);
        String targetId = add("actor-1", 30, 30, 60, 30, innerId);
        String childId = add("actor-2", 30, 70, 60, 55, innerId);

        int childYBefore = find(view, childId).getBounds().getY();

        dispatcher.beginBatch(SESSION, "declare an anchor, then grow its target");
        accessor.updateViewObject(SESSION, childId, null, null, null, null,
                null, null, null, null, targetId, "below", 0, 10);
        accessor.updateViewObject(SESSION, targetId, null, null, null, 500,
                null, null, null, null);
        dispatcher.endBatch(SESSION, true);

        IBounds child = find(view, childId).getBounds();
        assertTrue("the anchor declared earlier in this batch must be honoured by the later "
                        + "prepare, or nothing below measures this route: " + childYBefore + " -> "
                        + child.getY(), child.getY() != childYBefore);
        assertEquals("fixture guard: the child lands below the grown target", 540, child.getY());

        assertContained(childId, innerId, "a fit computed from a queued anchor declaration must "
                + "still see where the child lands");
        assertContained(innerId, outerId, "and cascade that growth to the grandparent");
    }

    /**
     * The approval arm. Nothing is effective while a proposal waits, so the response there can only
     * honestly be a projection — the invariant is discharged structurally by the {@code preview}
     * label rather than by value. What must hold is that approving it applies the same geometry the
     * projection described, containment included.
     */
    @Test
    public void shouldContainTheDisplacedChild_whenTheUpdateGoesThroughTheApprovalCard() {
        String outerId = group("Outer", 0, 0, 400, 300, null);
        String innerId = group("Inner", 10, 10, 300, 200, outerId);
        String targetId = add("actor-1", 30, 30, 60, 30, innerId);
        String childId = add("actor-2", 30, 70, 60, 55, innerId);
        anchor(childId, targetId, "below", 0, 10);

        int childYBefore = find(view, childId).getBounds().getY();
        int innerBefore = find(view, innerId).getBounds().getHeight();

        dispatcher.setApprovalModeProvider(() -> true);
        var proposed = accessor.updateViewObject(SESSION, targetId, null, null, null, 500,
                null, null, null, null);
        assertNotNull("approval mode must produce a proposal, not execute",
                proposed.proposalContext());
        assertEquals("nothing may be effective while the proposal waits", innerBefore,
                find(view, innerId).getBounds().getHeight());
        assertEquals("and the child must not have moved either", childYBefore,
                find(view, childId).getBounds().getY());

        dispatcher.approveProposal(SESSION, proposed.proposalContext().proposalId());

        IBounds child = find(view, childId).getBounds();
        assertTrue("approving must apply the displacement: " + childYBefore + " -> "
                + child.getY(), child.getY() != childYBefore);
        assertContained(childId, innerId, "the approved compound must carry the fit around the "
                + "displaced child, not only around the object the caller named");
        assertContained(innerId, outerId, "and cascade that growth to the grandparent");

        for (MovedViewObjectDto grown : proposed.entity().resizedAncestors()) {
            assertEquals("what the card projected for " + grown.name() + " is what approving "
                            + "applies",
                    find(view, grown.viewObjectId()).getBounds().getHeight(), grown.newHeight());
        }
    }

    private static class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final List<PropertyChangeListener> listeners = new ArrayList<>();

        void setModels(List<IArchimateModel> models) { this.models = models; }

        @Override public List<IArchimateModel> getModels() { return models; }
        @Override public void addPropertyChangeListener(PropertyChangeListener l) { listeners.add(l); }
        @Override public void removePropertyChangeListener(PropertyChangeListener l) { listeners.remove(l); }
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
