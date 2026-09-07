package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;

/**
 * Pins <em>why</em> the parent-fit cascade's queued-containment fallback is not needed at every
 * entry into it.
 *
 * <p>Six passes drive that cascade. Two of them prepare a mutation for an object the caller named,
 * which inside a batch may be an object the batch created and not yet attached — those must be able
 * to resolve containment the queue only declares, and do. The other passes enumerate their
 * candidates by walking the <em>live</em> view, and the reason they need no such fallback is a
 * property of the model rather than of the passes: nothing re-parents an already-attached view
 * object, so a live object's whole ancestor chain is live too, and a walk that starts from live
 * containment can never reach a detached container.</p>
 *
 * <p>That is a claim about reachability, and reachability claims decay silently — a future tool that
 * re-parents a live object, or a pass that starts enumerating from the queue, would make the missing
 * fallback a defect without changing a line in these passes. The premise is therefore asserted here
 * rather than left in a comment: an object an open batch has queued is invisible to a live-view
 * walk, and remains exactly as the batch declared it.</p>
 *
 * <p>Both tools are genuinely reachable inside a batch — each ends in the shared
 * {@code dispatchOrQueue}, so an open batch queues their compound rather than executing it. Being
 * reachable is what makes the question worth answering; the answer is that they see nothing the
 * queue owns.</p>
 */
public class CascadeGateQueuedContainmentTest {

    private static final String SESSION = "cascade-gate-reachability-session";

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
        model.setName("Cascade Gate Fixture");
        model.setId("model-cascade-gate");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Gating");
        diagrams.getElements().add(view);

        IFolder business = model.getFolder(FolderType.BUSINESS);
        for (int i = 1; i <= 2; i++) {
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

    private static String box(IDiagramModelObject o) {
        return o.getBounds().getX() + "," + o.getBounds().getY() + " "
                + o.getBounds().getWidth() + "x" + o.getBounds().getHeight();
    }

    /**
     * The premise, on the spacing pass, stated by the pass itself: with a group and a child sitting
     * in the batch's queue, the tool declines because the view it enumerates has no group with
     * children. It is not that the pass sees the queued group and skips it — it cannot see it at
     * all, which is exactly why a queued-containment fallback would have nothing to answer.
     */
    @Test
    public void shouldNotSeeQueuedObjects_whenAdjustViewSpacingRunsInsideABatch() throws Exception {
        dispatcher.beginBatch(SESSION, "queue a group, then adjust spacing in the same batch");
        String qg = accessor.addGroupToView(SESSION, view.getId(), "QG", 0, 0, 200, 200,
                null, null, null).entity().viewObjectId();
        String child = accessor.addToView(SESSION, view.getId(), "actor-1",
                10, 10, 60, 30, false, qg, null, null).entity().viewObject().viewObjectId();

        assertNull("nothing the batch queued is attached while the batch is open",
                find(view, qg));

        try {
            accessor.adjustViewSpacing(SESSION, view.getId(), 20, 20, 20, true);
            fail("the spacing pass must not find the queued group");
        } catch (ModelAccessException e) {
            assertEquals("adjust-view-spacing requires a view with groups. "
                    + "This view has no groups with children.", e.getMessage());
        }
        dispatcher.endBatch(SESSION, true);

        assertEquals("the queued group commits exactly as declared", "0,0 200x200",
                box(find(view, qg)));
        assertEquals("and so does its queued child", "10,10 60x30", box(find(view, child)));
    }

    /** The same premise on the routing pass, which drives the cascade from two more call sites. */
    @Test
    public void shouldNotSeeQueuedObjects_whenAutoRouteRunsInsideABatch() throws Exception {
        dispatcher.beginBatch(SESSION, "queue a group, then auto-route in the same batch");
        String qg = accessor.addGroupToView(SESSION, view.getId(), "QG", 0, 0, 200, 200,
                null, null, null).entity().viewObjectId();
        String child = accessor.addToView(SESSION, view.getId(), "actor-1",
                10, 10, 60, 30, false, qg, null, null).entity().viewObject().viewObjectId();

        accessor.autoRouteConnections(SESSION, view.getId(), null, "orthogonal", true,
                true, 10, 10, "full");
        dispatcher.endBatch(SESSION, true);

        assertEquals("the queued group commits exactly as declared — the routing pass never saw it",
                "0,0 200x200", box(find(view, qg)));
        assertEquals("and neither did its queued child", "10,10 60x30", box(find(view, child)));
    }

    /**
     * The other half of the premise: an already-attached object's container is attached too, so the
     * live walk these passes perform always has a real container to fit against. Stated as the
     * property that would have to break for the missing fallback to become a defect — no tool
     * re-parents a live view object into a container the batch has only queued.
     */
    @Test
    public void shouldKeepLiveObjectsInLiveContainers_whenABatchQueuesAGroupBeside() throws Exception {
        String liveGroup = accessor.addGroupToView(SESSION, view.getId(), "LiveG",
                0, 0, 300, 300, null, null, null).entity().viewObjectId();
        String liveChild = accessor.addToView(SESSION, view.getId(), "actor-1",
                10, 10, 60, 30, false, liveGroup, null, null).entity().viewObject().viewObjectId();

        dispatcher.beginBatch(SESSION, "queue a group beside the live one");
        accessor.addGroupToView(SESSION, view.getId(), "QG", 400, 400, 200, 200,
                null, null, null);
        dispatcher.endBatch(SESSION, true);

        IDiagramModelObject child = find(view, liveChild);
        assertNotNull("the live child is still attached", child);
        assertEquals("its container is the live group, never the queued one",
                liveGroup, ((IDiagramModelObject) child.eContainer()).getId());
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
