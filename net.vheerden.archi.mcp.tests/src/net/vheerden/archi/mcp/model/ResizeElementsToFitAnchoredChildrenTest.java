package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;

/**
 * Pins that an object {@code resize-elements-to-fit} grows takes its anchored children with it.
 *
 * <p>An anchor says "keep me against that object's edge", and the growth that moves the edge is
 * supposed to move the anchored object too. That cascade had exactly one caller —
 * {@code update-view-object}'s prepare — so the identical growth delivered through the other update
 * path silently broke every anchor pointing at the grown object: measured, a target grown to
 * {@code h = 55} left its child at {@code y = 140} where the anchor required {@code 165}, so the
 * child ended up overlapping the target's bottom {@code 15px}.</p>
 *
 * <p>The interesting half is not the missing call but what happens once it is there. A single pass
 * of this tool can mutate one object more than once — sized in pass 1, then shifted down in pass 2 —
 * and each mutation asks the cascade the same question, so a naive wrap leaves several competing
 * absolute repositions for one child in a single compound. A fixture with one mutation per target
 * cannot tell the two designs apart, so {@link #shouldMoveTheAnchoredChildExactlyOnce_whenItsTargetIsMutatedTwiceInOneCall()}
 * COUNTS the commands rather than checking where the child ended up.</p>
 */
public class ResizeElementsToFitAnchoredChildrenTest {

    private static final String SESSION = "resize-anchored-children-session";

    /** {@code ElementSizer} defaults for a short name — no display measurement. */
    private static final int AUTO_H = 55;

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private CommandStack stack;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private Command lastDispatched;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Anchored Children Fixture");
        model.setId("model-anchored-children");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Anchoring");
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
                lastDispatched = command;
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

    // ---- helpers -------------------------------------------------------------------------------

    private IDiagramModelObject live(String id, String actorId, int x, int y, int w, int h,
            IDiagramModelContainer parent) {
        IDiagramModelArchimateObject obj = factory.createDiagramModelArchimateObject();
        obj.setId(id);
        for (Object e : model.getFolder(FolderType.BUSINESS).getElements()) {
            if (((IArchimateElement) e).getId().equals(actorId)) {
                obj.setArchimateElement((IArchimateElement) e);
            }
        }
        obj.setBounds(x, y, w, h);
        parent.getChildren().add(obj);
        return obj;
    }

    private static void anchorTo(IDiagramModelObject child, IDiagramModelObject target,
            String edge, int dx, int dy) {
        child.getFeatures().putString(AnchorResolver.ANCHOR_TARGET_FEATURE, target.getId());
        child.getFeatures().putString(AnchorResolver.ANCHOR_EDGE_FEATURE, edge);
        child.getFeatures().putString(AnchorResolver.ANCHOR_DX_FEATURE, String.valueOf(dx));
        child.getFeatures().putString(AnchorResolver.ANCHOR_DY_FEATURE, String.valueOf(dy));
    }

    private static IDiagramModelObject find(IDiagramModelContainer c, String id) {
        for (IDiagramModelObject child : c.getChildren()) {
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

    /** Every leaf command in the dispatched compound that writes {@code targetId}'s bounds. */
    private static int boundsWritesFor(Command command, String targetId) {
        if (command instanceof CompoundCommand compound) {
            int n = 0;
            for (Object child : compound.getCommands()) {
                n += boundsWritesFor((Command) child, targetId);
            }
            return n;
        }
        if (command instanceof UpdateViewObjectCommand update) {
            IDiagramModelObject obj = update.getDiagramObject();
            return (obj != null && targetId.equals(obj.getId())) ? 1 : 0;
        }
        return 0;
    }

    // ---- the missing cascade ---------------------------------------------------------------------

    /**
     * The headline. A pre-existing element is grown by this tool, and the object anchored below it
     * follows. Measured before the fix: the target reached {@code h = 55} and the child stayed at
     * {@code y = 140}, overlapping the target's bottom by {@code 15px}, while the identical growth
     * applied through {@code update-view-object} moved it correctly.
     */
    @Test
    public void shouldRepositionAnchoredChild_whenResizeElementsToFitGrowsItsTarget() {
        IDiagramModelObject target = live("t-001", "actor-1", 100, 100, 60, 30, view);
        IDiagramModelObject child = live("c-001", "actor-2", 100, 140, 60, 30, view);
        anchorTo(child, target, "below", 0, 10);

        accessor.resizeElementsToFit(SESSION, view.getId(), List.of(target.getId()));

        assertEquals("the target grew to the auto-size height", AUTO_H,
                target.getBounds().getHeight());
        assertEquals("the anchored child follows to 100 + 55 + 10, no longer stranded at 140",
                165, child.getBounds().getY());
    }

    /**
     * The double-move guard, stated by COUNTING. A leaf that this call both re-sizes and shifts down
     * is mutated twice, and each mutation asks the cascade where the object anchored to it belongs.
     * A per-mutation wrap would put two absolute repositions for that one child into a single
     * compound; only the last would survive, and only by accident of ordering.
     *
     * <p>A fixture whose target is mutated once passes either way, so the count is the assertion and
     * the landing position is the corroboration.</p>
     */
    @Test
    public void shouldMoveTheAnchoredChildExactlyOnce_whenItsTargetIsMutatedTwiceInOneCall() {
        IDiagramModelObject parent = live("p-001", "actor-1", 0, 0, 60, 30, view);
        IDiagramModelObject leaf = live("l-001", "actor-2", 10, 10, 40, 20,
                (IDiagramModelContainer) parent);
        IDiagramModelObject child = live("c-002", "actor-3", 10, 40, 60, 30,
                (IDiagramModelContainer) parent);
        anchorTo(child, leaf, "below", 0, 5);

        accessor.resizeElementsToFit(SESSION, view.getId(), List.of(parent.getId(), leaf.getId()));

        assertNotNull("the pass dispatched a compound", lastDispatched);
        assertEquals("the leaf is mutated twice in this one call — sized, then shifted",
                2, boundsWritesFor(lastDispatched, leaf.getId()));
        assertEquals("but the object anchored to it is repositioned exactly ONCE",
                1, boundsWritesFor(lastDispatched, child.getId()));
        assertEquals("and that one move uses the leaf's FINAL position (25 + 55 + 5)",
                85, child.getBounds().getY());
    }

    // ---- containment: the group must end up around the child this pass displaced ------------------

    /**
     * The same defect this tool's sibling had, on this tool. Growing an element repositions whatever
     * is anchored to it, and until now the group around them was fitted only to the element the
     * pass re-sized — so the child it displaced could be left hanging outside the group holding it.
     *
     * <p>This path is structurally the easy one and structurally the different one: the caller owns
     * the fit and move maps and assembles the compound after every prepare has run, so a resize
     * added late is still committed. That is exactly why it has to be pinned separately — a fix that
     * works here proves nothing about the two prepares that wrap their own commands, and vice
     * versa.</p>
     *
     * <p>Geometry is derived from the variable under test: the group is sized so the child fits
     * exactly before the call ({@code 70 + 40 + 10 == 120}) and cannot fit after it, so the
     * assertion cannot pass for the wrong reason.</p>
     */
    @Test
    public void shouldFitTheGroupAroundTheDisplacedChild_whenResizeElementsToFitGrowsItsTarget() {
        IDiagramModelGroup inner = factory.createDiagramModelGroup();
        inner.setId("g-fit-001");
        inner.setName("Inner");
        inner.setBounds(10, 10, 300, 120);
        view.getChildren().add(inner);

        IDiagramModelObject target = live("t-fit-001", "actor-1", 30, 30, 60, 30, inner);
        IDiagramModelObject child = live("c-fit-001", "actor-2", 30, 70, 60, 40, inner);
        anchorTo(child, target, "below", 0, 10);

        assertFalse("fixture guard: the child fits its group exactly BEFORE the call, so any "
                        + "overflow afterwards is this pass's doing",
                ParentFitCascade.childExceedsParentBounds(30, 70, 60, 40,
                        inner.getBounds().getWidth(), inner.getBounds().getHeight(),
                        ArchiModelAccessorImpl.DEFAULT_GROUP_PADDING));

        int childYBefore = child.getBounds().getY();
        accessor.resizeElementsToFit(SESSION, view.getId(), List.of(target.getId()));

        assertEquals("the target grew to the auto-size height", AUTO_H,
                target.getBounds().getHeight());
        assertEquals("the anchored child follows to 30 + 55 + 10", 95, child.getBounds().getY());
        assertTrue("fixture must actually displace the child: " + childYBefore + " -> "
                + child.getBounds().getY(), child.getBounds().getY() != childYBefore);

        assertFalse("the group must grow around the child this pass displaced, not only around "
                        + "the element it re-sized — child y=" + child.getBounds().getY() + " h="
                        + child.getBounds().getHeight() + " in a group of height "
                        + inner.getBounds().getHeight(),
                ParentFitCascade.childExceedsParentBounds(
                        child.getBounds().getX(), child.getBounds().getY(),
                        child.getBounds().getWidth(), child.getBounds().getHeight(),
                        inner.getBounds().getWidth(), inner.getBounds().getHeight(),
                        ArchiModelAccessorImpl.DEFAULT_GROUP_PADDING));
    }

    /**
     * The fit provoked by a displaced child must be CARRIED by the compound, not merely reported.
     * The caller assembles the compound after every prepare, so a late map entry is committed here
     * where it would not be on the wrapping prepares — asserted rather than assumed, by counting
     * the bounds writes the dispatched tree makes for the group.
     */
    @Test
    public void shouldCommitTheGroupResize_whenADisplacedChildProvokedIt() {
        IDiagramModelGroup inner = factory.createDiagramModelGroup();
        inner.setId("g-fit-002");
        inner.setName("Inner");
        inner.setBounds(10, 10, 300, 120);
        view.getChildren().add(inner);

        IDiagramModelObject target = live("t-fit-002", "actor-1", 30, 30, 60, 30, inner);
        IDiagramModelObject child = live("c-fit-002", "actor-2", 30, 70, 60, 40, inner);
        anchorTo(child, target, "below", 0, 10);

        accessor.resizeElementsToFit(SESSION, view.getId(), List.of(target.getId()));

        assertNotNull("the pass dispatched a compound", lastDispatched);
        assertEquals("the group resize the displaced child provoked must be IN the dispatched "
                        + "compound, or the model never takes the height the caller is told about",
                1, boundsWritesFor(lastDispatched, inner.getId()));
        assertEquals("and the group must hold exactly the fitted height (95 + 40 + 10)",
                145, inner.getBounds().getHeight());
    }

    // ---- guards the shared cascade already had, preserved on this path ---------------------------

    /**
     * The cascade refuses to resolve across coordinate spaces, because bounds are stored relative to
     * the immediate parent. Reaching it from a second call site must not weaken that: a child in a
     * group is left alone when its anchor target sits at the top level.
     */
    @Test
    public void shouldNotMoveAnAnchoredChildInAnotherCoordinateSpace() {
        IDiagramModelObject target = live("t-002", "actor-1", 100, 100, 60, 30, view);
        IDiagramModelGroup group = factory.createDiagramModelGroup();
        group.setId("grp-1");
        group.setBounds(400, 400, 200, 200);
        view.getChildren().add(group);
        IDiagramModelObject nested = live("c-003", "actor-2", 10, 10, 60, 30, group);
        anchorTo(nested, target, "below", 0, 10);

        accessor.resizeElementsToFit(SESSION, view.getId(), List.of(target.getId()));

        assertEquals("the target grew", AUTO_H, target.getBounds().getHeight());
        assertEquals("a child in another coordinate space is left where it is",
                10, nested.getBounds().getY());
    }

    /** Nothing anchored to the grown object means nothing extra in the compound. */
    @Test
    public void shouldEmitNoExtraMoves_whenNothingIsAnchoredToTheGrownObject() {
        IDiagramModelObject target = live("t-003", "actor-1", 100, 100, 60, 30, view);
        IDiagramModelObject bystander = live("b-001", "actor-2", 100, 300, 60, 30, view);

        accessor.resizeElementsToFit(SESSION, view.getId(), List.of(target.getId()));

        assertEquals("the bystander is untouched", 300, bystander.getBounds().getY());
        assertEquals("and no command was emitted for it",
                0, boundsWritesFor(lastDispatched, bystander.getId()));
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
