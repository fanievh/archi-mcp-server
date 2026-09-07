package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

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
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.response.dto.BulkOperation;

/**
 * Pins the icon-band reservation's grandparent cascade against containers the same unit of work
 * created but has not attached yet.
 *
 * <p>Dropping a child into the corner where its container carries an icon grows that container by a
 * reserved band, and a container that grows must still fit inside its own group. That second step is
 * a <em>separate entry</em> into the parent-fit cascade from the one {@code update-view-object}
 * uses, and it resolved the grandparent from live EMF containment alone. A container an open batch
 * or a bulk pass created is detached until its own add command executes — which is exactly when the
 * reservation is computed — so {@code eContainer()} answered null, the cascade never ran, and the
 * container grew straight through its group's edge.</p>
 *
 * <p>Both units of work are covered because they reach the gate through different prepares and
 * carry their pending containment in different maps: an open batch through the queue, a bulk pass
 * through its own pass-scoped record.</p>
 *
 * <h2>Execution model</h2>
 *
 * <p>Same headless idiom as the other batch classes: a real GEF {@link CommandStack} driven over an
 * ordered compound, with the production {@code NonNotifyingCompoundCommand} rebuilt as a plain
 * {@link CompoundCommand} because its {@code execute()} dereferences
 * {@code IEditorModelManager.INSTANCE}. Every add passes explicit bounds so nothing reaches
 * {@code ElementSizer}'s display-bound measurement.</p>
 */
public class IconBandGrandparentCascadeTest {

    private static final String SESSION = "icon-band-cascade-session";

    /** Bottom-left is one of the two corners the reservation fires for. */
    private static final String CORNER = "bottom-left";

    /** {@code ImageHelper.ICON_BAND_HEIGHT} — one reserved band. */
    private static final int BAND = 24;

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
        model.setName("Icon Band Cascade Fixture");
        model.setId("model-icon-band-cascade");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Reserving");
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

    // ---- helpers -------------------------------------------------------------------------------

    private String group(String label, int x, int y, int w, int h, String parentId) {
        return accessor.addGroupToView(SESSION, view.getId(), label, x, y, w, h, parentId, null, null)
                .entity().viewObjectId();
    }

    private String element(String actorId, int x, int y, int w, int h, String parentId,
            ImageParams img) {
        return accessor.addToView(SESSION, view.getId(), actorId, x, y, w, h, false, parentId,
                null, img).entity().viewObject().viewObjectId();
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

    private static int height(IDiagramModelObject o) {
        return o.getBounds().getHeight();
    }

    private static Map<String, Object> op(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // ---- queue mode ----------------------------------------------------------------------------

    /**
     * The headline: an icon-bearing container the batch created grows for its band, and the group
     * the batch also created grows to keep containing it.
     *
     * <p>Geometry: the outer group is declared {@code 340} tall and the container {@code 300} tall
     * at {@code y = 10}, so before the reservation the container fits with the {@code 10} padding
     * exactly. The corner child makes the reservation fire, taking the container to {@code 324} and
     * its required extent to {@code 10 + 324 + 10 = 344}. Measured before the fix: the container
     * reached {@code 324} while the group stayed at its declared {@code 340}.</p>
     */
    @Test
    public void shouldGrowTheQueuedGrandparent_whenAQueuedParentReservesAnIconBand() throws Exception {
        dispatcher.beginBatch(SESSION, "queued group, queued icon-bearing parent, corner child");
        String outer = group("QOutermost", 0, 0, 340, 340, null);
        String parent = element("actor-1", 10, 10, 300, 300, outer,
                new ImageParams(null, CORNER, null));
        element("actor-2", 0, 270, 50, 30, parent, null);
        dispatcher.endBatch(SESSION, true);

        assertEquals("the icon band grew the queued parent by one band (300 + 24)",
                300 + BAND, height(find(view, parent)));
        assertEquals("the queued grandparent grew to keep containing it (10 + 324 + 10), "
                        + "no longer stranded at its declared 340",
                344, height(find(view, outer)));
    }

    /**
     * The reservation is computed against what the batch has already decided for the container, not
     * against the size the model still holds. Without this the band is reserved on a stale height
     * and the resulting absolute resize silently reverts the batch's own earlier growth.
     */
    @Test
    public void shouldReserveAgainstTheQueuedHeight_whenTheBatchAlreadyResizedTheParent() throws Exception {
        dispatcher.beginBatch(SESSION, "resize the icon-bearing parent, then fill its corner");
        String parent = element("actor-1", 0, 0, 300, 300, null,
                new ImageParams(null, CORNER, null));
        accessor.updateViewObject(SESSION, parent, null, null, null, 400,
                null, null, null, null, null, null, null, null);
        element("actor-2", 0, 370, 50, 30, parent, null);
        dispatcher.endBatch(SESSION, true);

        assertEquals("the band is reserved on top of the queued 400, not the created 300",
                400 + BAND, height(find(view, parent)));
    }

    /** A container with no group above it still reserves its band — the cascade simply has nothing to do. */
    @Test
    public void shouldReserveWithoutCascading_whenTheQueuedParentHasNoGroupAbove() throws Exception {
        dispatcher.beginBatch(SESSION, "icon-bearing parent at the top level");
        String parent = element("actor-1", 0, 0, 300, 300, null,
                new ImageParams(null, CORNER, null));
        element("actor-2", 0, 270, 50, 30, parent, null);
        dispatcher.endBatch(SESSION, true);

        assertEquals("the reservation still fires", 300 + BAND, height(find(view, parent)));
    }

    // ---- bulk mode -----------------------------------------------------------------------------

    /**
     * The same property on the bulk path, which reaches the gate through a different prepare and
     * carries its pending containment in a pass-scoped map rather than the queue. Both the group and
     * the icon-bearing container are created inside the pass, so both are detached when the
     * reservation is computed.
     */
    @Test
    public void shouldGrowTheBulkCreatedGrandparent_whenABulkCreatedParentReservesAnIconBand() {
        accessor.executeBulk(SESSION, List.of(
                new BulkOperation("add-group-to-view", op(
                        "viewId", view.getId(), "label", "BOutermost",
                        "x", 0, "y", 0, "width", 340, "height", 340)),
                new BulkOperation("create-element", op(
                        "name", "Parent", "type", "BusinessActor")),
                new BulkOperation("add-to-view", op(
                        "viewId", view.getId(), "elementId", "$1.id",
                        "parentViewObjectId", "$0.id",
                        "x", 10, "y", 10, "width", 300, "height", 300,
                        "imagePosition", CORNER)),
                new BulkOperation("create-element", op(
                        "name", "Corner", "type", "BusinessActor")),
                new BulkOperation("add-to-view", op(
                        "viewId", view.getId(), "elementId", "$3.id",
                        "parentViewObjectId", "$2.id",
                        "x", 0, "y", 270, "width", 50, "height", 30))),
                "bulk icon-band cascade", false);

        IDiagramModelObject outer = view.getChildren().get(0);
        assertNotNull("the bulk pass created the outer group", outer);
        IDiagramModelObject parent = ((IDiagramModelContainer) outer).getChildren().get(0);
        assertEquals("the icon band grew the bulk-created parent by one band",
                300 + BAND, height(parent));
        assertEquals("the bulk-created grandparent grew to keep containing it (10 + 324 + 10)",
                344, height(outer));
    }

    // ---- outside any unit of work --------------------------------------------------------------

    /**
     * Non-regression: with nothing queued and no bulk pass open, every pending map is null and the
     * gate reads exactly the live containment and live bounds it always read.
     */
    @Test
    public void shouldCascadeThroughLiveContainment_whenNoUnitOfWorkIsOpen() {
        String outer = group("LiveOutermost", 0, 0, 340, 340, null);
        String parent = element("actor-1", 10, 10, 300, 300, outer,
                new ImageParams(null, CORNER, null));
        element("actor-2", 0, 270, 50, 30, parent, null);

        assertEquals("the reservation fires immediately", 300 + BAND, height(find(view, parent)));
        assertEquals("and cascades through live containment", 344, height(find(view, outer)));
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
