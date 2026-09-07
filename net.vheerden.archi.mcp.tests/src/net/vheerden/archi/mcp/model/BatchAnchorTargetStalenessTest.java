package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
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
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;

/**
 * Pins that an anchor set inside an open batch resolves against the target's post-batch bounds.
 *
 * <p>{@code update-view-object} resolves an {@code anchorTarget} into a concrete x/y when the
 * request is prepared, reading the target's bounds from the live model. Inside a batch every
 * operation is prepared and queued but nothing runs until the batch commits, so an earlier queued
 * resize or move of that same target has not touched the model yet: the anchored object is frozen
 * against the target's pre-batch bounds and lands at the old edge once the compound executes.</p>
 *
 * <h2>Execution model</h2>
 *
 * <p>These tests drive a real {@link CommandStack} over an <em>ordered</em> compound, because
 * commit ORDER is the property under test. Two deliberate departures from the house idiom, both
 * inherited from {@code BatchDeleteFolderGuardTest}:</p>
 *
 * <ul>
 *   <li>The production compound is {@code NonNotifyingCompoundCommand}, whose {@code execute()}
 *       dereferences {@code IEditorModelManager.INSTANCE} and therefore cannot run headless. The
 *       queued children are rebuilt into a plain GEF {@link CompoundCommand}, which preserves
 *       execution order exactly and drops only ECORE event suppression — irrelevant here.</li>
 *   <li>The {@code executeDecomposed} helper used elsewhere in this package flattens the compound
 *       away, so it cannot show compound-level behaviour at all. It is not used.</li>
 * </ul>
 *
 * <p>The view fixture is built directly through EMF rather than {@code add-to-view}, which is the
 * GEF/SWT path that forces its callers onto a display.</p>
 */
public class BatchAnchorTargetStalenessTest {

    private static final String SESSION = "batch-anchor-session";

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private CommandStack stack;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private IDiagramModelGroup targetGroup;
    private IDiagramModelArchimateObject targetElement;
    private IDiagramModelNote anchored;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Batch Anchor Fixture");
        model.setId("model-batch-anchor");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Anchors");
        diagrams.getElements().add(view);

        targetGroup = factory.createDiagramModelGroup();
        targetGroup.setId("grp-target");
        targetGroup.setName("Target Group");
        targetGroup.setBounds(100, 100, 200, 50);
        view.getChildren().add(targetGroup);

        IBusinessActor actor = factory.createBusinessActor();
        actor.setId("actor-1");
        actor.setName("Target Actor");
        model.getFolder(FolderType.BUSINESS).getElements().add(actor);
        targetElement = factory.createDiagramModelArchimateObject();
        targetElement.setId("elem-target");
        targetElement.setArchimateElement(actor);
        targetElement.setBounds(600, 100, 120, 60);
        view.getChildren().add(targetElement);

        anchored = factory.createDiagramModelNote();
        anchored.setId("note-anchored");
        anchored.setBounds(0, 0, 180, 30);
        view.getChildren().add(anchored);

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


    // ---- helpers ------------------------------------------------------------------------------

    private void resize(String id, Integer x, Integer y, Integer w, Integer h) {
        accessor.updateViewObject(SESSION, id, x, y, w, h,
                null, null, null, null, null, null, null, null);
    }

    private void anchor(String childId, String targetId, String edge, Integer dx, Integer dy) {
        accessor.updateViewObject(SESSION, childId, null, null, null, null,
                null, null, null, null, targetId, edge, dx, dy);
    }

    private static int x(IDiagramModelObject o) { return o.getBounds().getX(); }

    private static int y(IDiagramModelObject o) { return o.getBounds().getY(); }

    // ---- the defect ---------------------------------------------------------------------------

    /**
     * The headline case: the target is grown earlier in the same batch, so the anchored note must
     * land at the grown bottom (100 + 300 + 10), not the pre-batch one (100 + 50 + 10).
     */
    @Test
    public void shouldAnchorToPostBatchBottom_whenTargetIsResizedEarlierInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "resize then anchor");
        resize(targetGroup.getId(), null, null, null, 300);
        anchor(anchored.getId(), targetGroup.getId(), "below", 0, 10);
        dispatcher.endBatch(SESSION, true);

        assertEquals("the target really did grow", 300, targetGroup.getBounds().getHeight());
        assertEquals("anchored note must follow the target's post-batch bottom",
                410, y(anchored));
        assertEquals("x tracks the target's unchanged left edge", 100, x(anchored));
    }

    /** Not only height: a target moved earlier in the batch must carry the anchored child with it. */
    @Test
    public void shouldAnchorToPostBatchOrigin_whenTargetIsMovedEarlierInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "move then anchor");
        resize(targetGroup.getId(), 500, 600, null, null);
        anchor(anchored.getId(), targetGroup.getId(), "below", 0, 10);
        dispatcher.endBatch(SESSION, true);

        assertEquals("x must track the target's new left edge", 500, x(anchored));
        assertEquals("y must track the target's new bottom (600 + 50 + 10)", 660, y(anchored));
    }

    // ---- all four edges -----------------------------------------------------------------------

    @Test
    public void shouldAnchorRightOfPostBatchWidth_whenTargetIsWidenedEarlierInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "widen then anchor right");
        resize(targetGroup.getId(), null, null, 400, null);
        anchor(anchored.getId(), targetGroup.getId(), "right", 15, 0);
        dispatcher.endBatch(SESSION, true);

        assertEquals("x = target.x + new width + dx", 515, x(anchored));
        assertEquals("y = target.y + dy", 100, y(anchored));
    }

    @Test
    public void shouldAnchorAbovePostBatchOrigin_whenTargetIsMovedEarlierInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "move then anchor above");
        resize(targetGroup.getId(), 100, 700, null, null);
        anchor(anchored.getId(), targetGroup.getId(), "above", 0, 10);
        dispatcher.endBatch(SESSION, true);

        assertEquals("x = target.x + dx", 100, x(anchored));
        assertEquals("y = target.y - childHeight - dy = 700 - 30 - 10", 660, y(anchored));
    }

    @Test
    public void shouldAnchorLeftOfPostBatchOrigin_whenTargetIsMovedEarlierInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "move then anchor left");
        resize(targetGroup.getId(), 900, 400, null, null);
        anchor(anchored.getId(), targetGroup.getId(), "left", 5, 0);
        dispatcher.endBatch(SESSION, true);

        assertEquals("x = target.x - childWidth - dx = 900 - 180 - 5", 715, x(anchored));
        assertEquals("y = target.y + dy", 400, y(anchored));
    }

    /** The target being an ArchiMate element rather than a group must make no difference. */
    @Test
    public void shouldAnchorToPostBatchBottom_whenTargetIsAnArchimateElement() throws Exception {
        dispatcher.beginBatch(SESSION, "resize element then anchor");
        resize(targetElement.getId(), null, null, null, 200);
        anchor(anchored.getId(), targetElement.getId(), "below", 0, 8);
        dispatcher.endBatch(SESSION, true);

        assertEquals("x tracks the element's left edge", 600, x(anchored));
        assertEquals("y = 100 + 200 + 8", 308, y(anchored));
    }

    // ---- interaction with the icon-band container grow -----------------------------------------

    /**
     * An anchor-setting call may also carry an {@code imagePosition}, and a container whose
     * children already occupy the requested icon corner is grown by a reserved band. That grow
     * happens after the anchor position has been computed, so the two disagree about the object's
     * height: the prepared position was resolved against the pre-grow height, the re-resolution
     * uses the height the command actually writes.
     *
     * <p>The re-resolution deliberately uses the final height. It is the height the object ends up
     * with, so an {@code above} anchor really does clear the target by the requested gap; using the
     * pre-grow height would overlap the target by exactly the reserved band.</p>
     */
    @Test
    public void shouldReresolveAgainstTheGrownHeight_whenTheAnchorCallAlsoGrowsAnIconBand() throws Exception {
        IBusinessActor hostActor = factory.createBusinessActor();
        hostActor.setId("actor-host");
        hostActor.setName("Host");
        model.getFolder(FolderType.BUSINESS).getElements().add(hostActor);
        IDiagramModelArchimateObject host = factory.createDiagramModelArchimateObject();
        host.setId("elem-host");
        host.setArchimateElement(hostActor);
        host.setBounds(0, 0, 180, 100);
        view.getChildren().add(host);

        // A child sitting in the bottom-left corner is what makes the band fire.
        IBusinessActor innerActor = factory.createBusinessActor();
        innerActor.setId("actor-inner");
        innerActor.setName("Inner");
        model.getFolder(FolderType.BUSINESS).getElements().add(innerActor);
        IDiagramModelArchimateObject inner = factory.createDiagramModelArchimateObject();
        inner.setId("elem-inner");
        inner.setArchimateElement(innerActor);
        inner.setBounds(0, 80, 50, 20);
        host.getChildren().add(inner);

        dispatcher.beginBatch(SESSION, "move target then anchor above with an icon");
        resize(targetGroup.getId(), 100, 700, null, null);
        accessor.updateViewObject(SESSION, host.getId(), null, null, null, null,
                null, null, new ImageParams(null, "bottom-left", null), null,
                targetGroup.getId(), "above", 0, 10);
        dispatcher.endBatch(SESSION, true);

        assertEquals("the icon band grew the host by one reserved band (100 + 24)",
                124, host.getBounds().getHeight());
        assertEquals("y = target.y - grown height - dy = 700 - 124 - 10",
                566, y(host));
        assertEquals("x = target.x + dx", 100, x(host));
    }

    // ---- ordering: the reverse order is covered too ---------------------------------------------

    /**
     * Anchor first, resize the target second — which is not the same mechanism as the other order.
     *
     * <p>The anchored object's command executes before the target's, so at the moment it runs the
     * target genuinely still holds its pre-batch bounds and its own execute-time re-resolution
     * correctly reproduces the prepare-time position. Nothing about that is fixable from the
     * anchored object's side. What repairs it is the other side: the target's prepare runs the
     * commit-time cascade that moves everything anchored to it, and that cascade now reads the
     * anchors the batch has <em>declared</em> as well as the ones the model already holds. The
     * anchor is written to the model by the anchored object's own command at {@code execute()}, so
     * while the batch is open it exists only in the queue.</p>
     *
     * <p>Was a known-limitation pin asserting {@code y == 160} — the pre-batch bottom, because the
     * cascade saw no anchored children at all. Now {@code 410}: {@code 100 + 300 + 10}, the same
     * answer the other ordering gives, so reversing the two calls inside a batch is finally
     * equivalent to reversing them outside one.</p>
     */
    @Test
    public void shouldFollowTheTarget_whenTargetIsResizedAfterTheAnchorInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "anchor then resize");
        anchor(anchored.getId(), targetGroup.getId(), "below", 0, 10);
        resize(targetGroup.getId(), null, null, null, 300);
        dispatcher.endBatch(SESSION, true);

        assertEquals("the target grew", 300, targetGroup.getBounds().getHeight());
        assertEquals("the anchored object follows the target's grown bottom (100 + 300 + 10), "
                        + "no longer stranded at the pre-batch 160",
                410, y(anchored));
    }

    // ---- immediate mode is untouched ----------------------------------------------------------

    @Test
    public void shouldResolveAnchorAgainstLiveBounds_whenNoBatchIsOpen() {
        anchor(anchored.getId(), targetGroup.getId(), "below", 0, 10);

        assertEquals(100, x(anchored));
        assertEquals("outside a batch nothing is pending, so the live bottom is the answer",
                160, y(anchored));
    }

    @Test
    public void shouldResolveAnchorAgainstLiveBounds_whenTargetWasResizedByAnEarlierImmediateCall() {
        resize(targetGroup.getId(), null, null, null, 300);
        anchor(anchored.getId(), targetGroup.getId(), "below", 0, 10);

        assertEquals("an executed resize is already live at prepare time", 410, y(anchored));
    }

    // ---- undo / redo --------------------------------------------------------------------------

    @Test
    public void shouldRestoreBothObjects_whenTheCommittedBatchIsUndone() throws Exception {
        dispatcher.beginBatch(SESSION, "resize then anchor");
        resize(targetGroup.getId(), null, null, null, 300);
        anchor(anchored.getId(), targetGroup.getId(), "below", 0, 10);
        dispatcher.endBatch(SESSION, true);

        assertTrue(stack.canUndo());
        stack.undo();

        assertEquals("target height restored", 50, targetGroup.getBounds().getHeight());
        assertEquals("anchored note x restored", 0, x(anchored));
        assertEquals("anchored note y restored", 0, y(anchored));
    }

    @Test
    public void shouldRelandAtTheCorrectedPosition_whenTheUndoneBatchIsRedone() throws Exception {
        dispatcher.beginBatch(SESSION, "resize then anchor");
        resize(targetGroup.getId(), null, null, null, 300);
        anchor(anchored.getId(), targetGroup.getId(), "below", 0, 10);
        dispatcher.endBatch(SESSION, true);

        stack.undo();
        assertTrue(stack.canRedo());
        stack.redo();

        assertEquals("target grew again", 300, targetGroup.getBounds().getHeight());
        assertEquals("redo must re-land the corrected position, not the stale one",
                410, y(anchored));
    }


    /**
     * A later partial re-edit of an object anchored earlier in the same batch keeps the anchored
     * position, not a projection of it.
     *
     * <p>Anchoring inside a batch used to be a two-part mechanism, and the two parts disagreed. The
     * prepare projected a position from the target's bounds <em>as the model still held them</em>,
     * ignoring the resize an earlier command of the same batch had already queued for that target;
     * the anchored object's own command then re-resolved at {@code execute()} and landed somewhere
     * else. Only the projection was recorded on the queued command, so a third operation inherited
     * the projection as its merge base — and that third command carries no anchor of its own, so
     * nothing re-resolved it and it wrote the wrong position verbatim.</p>
     *
     * <p>The repair is at the first part, not the third: the prepare now measures the anchor target
     * against what the batch has queued for it, so the projection is the landing. Nothing is
     * re-resolved twice, and {@code execute()}'s own re-resolution is untouched — it simply finds
     * the target already where the prepare predicted and declines to move anything.</p>
     *
     * <p>Both numbers, over the two behaviours this pin has now outlived: the note first reverted to
     * its creation bounds {@code (0, 0)}, losing the anchor entirely; it then kept {@code x = 100}
     * with {@code y = 160}, the pre-resize bottom. It now holds {@code y = 410} —
     * {@code 100 + 300 + 10} — which is what the anchor asked for.</p>
     */
    @Test
    public void shouldKeepTheAnchoredPosition_whenTheAnchoredObjectIsPartlyReeditedLater()
            throws Exception {
        dispatcher.beginBatch(SESSION, "resize, anchor, then partially re-edit the anchored note");
        resize(targetGroup.getId(), null, null, null, 300);
        anchor(anchored.getId(), targetGroup.getId(), "below", 0, 10);
        resize(anchored.getId(), null, null, 250, null);
        dispatcher.endBatch(SESSION, true);

        assertEquals("the re-edit itself lands", 250, anchored.getBounds().getWidth());
        assertEquals("height is inherited from the anchor op, not reverted to creation",
                30, anchored.getBounds().getHeight());
        assertEquals("x is the target's left edge — correct, and no longer the creation value",
                100, x(anchored));
        assertEquals("y is the anchored bottom (100 + 300 + 10), no longer the pre-resize "
                + "projection 160 nor the creation value 0",
                410, y(anchored));
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
