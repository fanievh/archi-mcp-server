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

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IAssociationRelationship;
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.INode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.vheerden.archi.mcp.response.dto.MovedViewObjectDto;
import net.vheerden.archi.mcp.response.dto.OptimizeGroupOrderResultDto;

/**
 * Pins what {@code optimize-group-order} says about the children whose SIZE it changed.
 *
 * <h2>The defect</h2>
 *
 * <p>Reordering a group's children re-runs that group's arrangement, and the arrangement writes a
 * full [x, y, w, h] to every child. Three things there choose a width other than the child's own:
 * a grid gives every cell the width of the widest element in the group, {@code autoWidth} derives
 * one from the label, and an explicit {@code elementWidth}/{@code elementHeight} imposes one. The
 * response carried {@code elementsReordered} — a count of children re-placed — and no geometry at
 * all, so an agent that cannot see the canvas could not learn that any of it happened.</p>
 *
 * <h2>All three are reported, and the third is not an exception</h2>
 *
 * <p>The first two are silent by any reading. The third was asked for, which does not discharge the
 * obligation: what must be reported is what the model ENDED UP holding, and a requested width the
 * model did not end up holding is exactly the echo the obligation forbids. The observation compares
 * the landed rectangle against the one the child effectively had, so a requested size that happens
 * to match is correctly absent, and one that lands is correctly named — the right answer for all
 * three without a special case, and without a filter that would have to know WHY a size was
 * chosen.</p>
 *
 * <h2>Lane</h2>
 *
 * <p>Headless. {@code optimizeGroupOrder} runs no assessment pass, no routing pass and no text
 * measurement on the paths driven here, so the fixture is plain EMF and the dispatcher executes
 * decomposed rather than marshalling onto a display.</p>
 */
public class OptimizeGroupOrderResizeReportingTest {

    private static final String SESSION = "optimize-group-order-resize-report-session";
    private static final String VIEW_ID = "view-1";

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private IArchimateModel model;
    private IArchimateDiagramModel view;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Optimize Group Order Resize Reporting Fixture");
        model.setId("model-optimize-group-order-resize-report");
        model.setDefaults();

        view = factory.createArchimateDiagramModel();
        view.setId(VIEW_ID);
        view.setName("Ordered");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        stubModelManager.setModels(List.of(model));

        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                executeDecomposed(command);
            }
            @Override
            protected void dispatchCommand(Command command) {
                executeDecomposed(command);
            }
            private void executeDecomposed(Command command) {
                if (command instanceof CompoundCommand compound) {
                    for (Object cmd : compound.getCommands()) {
                        executeDecomposed((Command) cmd);
                    }
                } else {
                    command.execute();
                }
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

    // ---- fixture -------------------------------------------------------------------------------

    private IDiagramModelGroup group(String name, int x, int y, int w, int h) {
        IDiagramModelGroup g = factory.createDiagramModelGroup();
        g.setId("grp-" + name);
        g.setName(name);
        g.setBounds(x, y, w, h);
        view.getChildren().add(g);
        return g;
    }

    private IDiagramModelArchimateObject node(IDiagramModelContainer parent, String name,
            int x, int y, int w, int h) {
        INode concept = factory.createNode();
        concept.setId("node-" + name);
        concept.setName(name);
        model.getFolder(FolderType.TECHNOLOGY).getElements().add(concept);

        IDiagramModelArchimateObject obj = factory.createDiagramModelArchimateObject();
        obj.setId("vo-" + name);
        obj.setArchimateConcept(concept);
        obj.setBounds(x, y, w, h);
        parent.getChildren().add(obj);
        return obj;
    }

    /** An inter-group association, so the optimiser has crossings to work on and actually reorders. */
    private void connect(IDiagramModelArchimateObject source, IDiagramModelArchimateObject target,
            String id) {
        IAssociationRelationship rel = factory.createAssociationRelationship();
        rel.setId(id);
        rel.setSource(source.getArchimateElement());
        rel.setTarget(target.getArchimateElement());
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);

        IDiagramModelArchimateConnection conn = factory.createDiagramModelArchimateConnection();
        conn.setId("conn-" + id);
        conn.setArchimateConcept(rel);
        conn.connect(source, target);
    }

    /**
     * Two groups whose children are cross-connected in an order that gives the optimiser something
     * to reduce, so every scenario below reaches the arrangement rather than stopping at a guard.
     * The left group's three children have deliberately DIFFERENT widths.
     */
    private IDiagramModelObject[] crossedFixture() {
        IDiagramModelGroup left = group("Left", 0, 0, 700, 700);
        IDiagramModelGroup right = group("Right", 900, 0, 700, 700);

        IDiagramModelArchimateObject a = node(left, "A", 20, 40, 120, 55);
        IDiagramModelArchimateObject b = node(left, "B", 20, 140, 300, 55);
        IDiagramModelArchimateObject c = node(left, "C", 20, 240, 60, 55);

        IDiagramModelArchimateObject x = node(right, "X", 20, 40, 120, 55);
        IDiagramModelArchimateObject y = node(right, "Y", 20, 140, 120, 55);
        IDiagramModelArchimateObject z = node(right, "Z", 20, 240, 120, 55);

        // Deliberately crossed: A->Z, B->Y, C->X.
        connect(a, z, "rel-az");
        connect(b, y, "rel-by");
        connect(c, x, "rel-cx");
        return new IDiagramModelObject[]{a, b, c, left};
    }

    private static MovedViewObjectDto resizedEntryFor(OptimizeGroupOrderResultDto dto, String id) {
        for (MovedViewObjectDto entry : dto.resizedElements()) {
            if (id.equals(entry.viewObjectId())) {
                return entry;
            }
        }
        return null;
    }

    private static IBounds copy(IBounds b) {
        IBounds c = IArchimateFactory.eINSTANCE.createBounds();
        c.setX(b.getX());
        c.setY(b.getY());
        c.setWidth(b.getWidth());
        c.setHeight(b.getHeight());
        return c;
    }

    private void assertMatchesModel(OptimizeGroupOrderResultDto dto, IDiagramModelObject obj,
            String what) {
        MovedViewObjectDto reported = resizedEntryFor(dto, obj.getId());
        assertNotNull(what + " must be named. resizedElements was: " + dto.resizedElements(),
                reported);
        IBounds actual = obj.getBounds();
        assertEquals(what + ": x", actual.getX(), reported.newX());
        assertEquals(what + ": y", actual.getY(), reported.newY());
        assertEquals(what + ": width", actual.getWidth(), reported.newWidth());
        assertEquals(what + ": height", actual.getHeight(), reported.newHeight());
    }

    // ---- mechanism 1: the grid arm's uniform cell width -------------------------------------------

    /**
     * ONE COLUMN PER CHILD, deliberately. With fewer columns than children a column holds more than
     * one child and takes its own widest member, so a narrow child is widened either way and the
     * pin passes whether the cell width is uniform or per-column — measured: at columns=2 this test
     * stayed GREEN under the very mutation it exists to catch. At columns=3 each child is its own
     * column, so per-column sizing leaves every child its own width and only the UNIFORM cell
     * widens the narrow one.
     */
    @Test
    public void shouldNameAChildTheGridArmWidenedToTheWidestSibling() throws Exception {
        IDiagramModelObject[] f = crossedFixture();
        IDiagramModelObject narrow = f[2];
        int before = narrow.getBounds().getWidth();

        OptimizeGroupOrderResultDto dto = accessor.optimizeGroupOrder(
                SESSION, VIEW_ID, "grid", 40, 10, null, null, false, 3, null).entity();

        assertTrue("fixture guard: the grid arm must actually have widened this child: "
                + before + " -> " + narrow.getBounds().getWidth(),
                narrow.getBounds().getWidth() > before);
        assertMatchesModel(dto, narrow, "the child the grid arm widened");

        String json = new ObjectMapper().writeValueAsString(dto);
        assertTrue("the rectangle must reach the wire. Response was: " + json,
                json.contains("\"resizedElements\""));
    }

    // ---- mechanism 2: autoWidth derives a width from the label ------------------------------------

    @Test
    public void shouldNameAChildAutoWidthResized() {
        IDiagramModelObject[] f = crossedFixture();
        IDiagramModelObject wide = f[1];
        int before = wide.getBounds().getWidth();

        OptimizeGroupOrderResultDto dto = accessor.optimizeGroupOrder(
                SESSION, VIEW_ID, "column", 40, 10, null, null, true, null, null).entity();

        assertTrue("fixture guard: autoWidth must actually have changed this child's width: "
                + before + " -> " + wide.getBounds().getWidth(),
                wide.getBounds().getWidth() != before);
        assertMatchesModel(dto, wide, "the child autoWidth re-sized");
    }

    // ---- mechanism 3: an explicit elementWidth — requested, and still reported ---------------------

    @Test
    public void shouldNameAChildAnExplicitElementWidthResized() {
        IDiagramModelObject[] f = crossedFixture();
        IDiagramModelObject a = f[0];

        OptimizeGroupOrderResultDto dto = accessor.optimizeGroupOrder(
                SESSION, VIEW_ID, "column", 40, 10, 260, null, false, null, null).entity();

        assertEquals("fixture guard: the requested width must actually have landed",
                260, a.getBounds().getWidth());
        assertMatchesModel(dto, a, "the child an explicit elementWidth re-sized");
    }

    /**
     * The other half of mechanism 3, and the reason it is not filtered by provenance: a requested
     * width a child ALREADY had is not a change, so it is absent. The observation compares the
     * landed rectangle against the effective one; it never asks who chose the number.
     */
    @Test
    public void shouldNotNameAChildWhoseRequestedWidthItAlreadyHad() {
        IDiagramModelObject[] f = crossedFixture();
        IDiagramModelObject a = f[0];
        IDiagramModelObject b = f[1];

        // 120 is exactly A's own width and not B's.
        OptimizeGroupOrderResultDto dto = accessor.optimizeGroupOrder(
                SESSION, VIEW_ID, "column", 40, 10, 120, null, false, null, null).entity();

        assertEquals("fixture guard: A really did already have this width",
                120, a.getBounds().getWidth());
        assertNull("a requested width the child already had is not a change. resizedElements was: "
                + dto.resizedElements(), resizedEntryFor(dto, a.getId()));
        assertNotNull("...while the sibling it DID change is named",
                resizedEntryFor(dto, b.getId()));
    }

    // ---- the negative ------------------------------------------------------------------------------

    /**
     * A column arrangement with no overrides and autoWidth off: every child is re-placed and none
     * changes size, so the count is non-zero and the list is empty and absent from the wire.
     */
    @Test
    public void shouldNameNothing_whenChildrenAreReorderedAndNoneChangesSize() throws Exception {
        IDiagramModelObject[] f = crossedFixture();

        OptimizeGroupOrderResultDto dto = accessor.optimizeGroupOrder(
                SESSION, VIEW_ID, "column", 40, 10, null, null, false, null, null).entity();

        assertEquals("fixture guard: child A keeps its own width", 120,
                f[0].getBounds().getWidth());
        assertEquals("fixture guard: child B keeps its own, larger width", 300,
                f[1].getBounds().getWidth());
        assertEquals("fixture guard: child C keeps its own, smaller width", 60,
                f[2].getBounds().getWidth());
        assertTrue("the tool must still report the children it re-placed",
                dto.elementsReordered() > 0);
        assertTrue("a call that resized no child must name none. resizedElements was: "
                + dto.resizedElements(), dto.resizedElements().isEmpty());

        String json = new ObjectMapper().writeValueAsString(dto);
        assertFalse("an empty list must be omitted from the wire, not serialized as []. "
                + "Response was: " + json, json.contains("resizedElements"));
    }

    // ---- the batch frame ---------------------------------------------------------------------------

    /**
     * Inside an open batch the comparison basis must be the size the batch already queued. A child
     * an earlier operation of the same batch widened to the width this pass then requests has not
     * been re-sized BY THIS PASS.
     *
     * <p>Input and observation now measure in the same frame: the pass resolves its sizes from each
     * child's effective rectangle, the same basis the comparison always used. This fixture cannot
     * tell the two apart on its own — the queued width and the requested one coincide at 260 — so
     * the reproduction lives in the pins below, where they differ and the un-overridden axis is
     * what discriminates.</p>
     */
    @Test
    public void shouldNotNameAChildTheBatchHadAlreadyWidenedToTheRequestedWidth() {
        IDiagramModelObject[] f = crossedFixture();
        IDiagramModelObject a = f[0];

        dispatcher.beginBatch(SESSION, "widen a child, then optimise the order");
        accessor.updateViewObject(SESSION, a.getId(), null, null, 260, null,
                null, null, null, null);
        OptimizeGroupOrderResultDto dto = accessor.optimizeGroupOrder(
                SESSION, VIEW_ID, "column", 40, 10, 260, null, false, null, null).entity();
        dispatcher.endBatch(SESSION, true);

        assertNull("the batch had already widened this child to 260, so the pass changed nothing "
                + "about its size and must not claim it. resizedElements was: "
                + dto.resizedElements(), resizedEntryFor(dto, a.getId()));
    }

    /**
     * The input half of the same frame, and the reproduction the batch pin above cannot make: the
     * queued width and the requested one COINCIDE there, so it holds whatever the resolver reads.
     * Here they differ on one axis and only the other is overridden — the caller asks for a width,
     * so the HEIGHT is a fallback, and inside a batch that fallback must be the queued height.
     */
    @Test
    public void shouldKeepTheQueuedHeight_whenTheRequestedWidthDiffersFromTheQueuedOne() {
        IDiagramModelObject[] f = crossedFixture();
        IDiagramModelObject a = f[0];

        dispatcher.beginBatch(SESSION, "resize a child, then optimise the order");
        accessor.updateViewObject(SESSION, a.getId(), null, null, 400, 300,
                null, null, null, null);
        accessor.optimizeGroupOrder(
                SESSION, VIEW_ID, "column", 40, 10, 260, null, false, null, null);
        dispatcher.endBatch(SESSION, true);

        assertEquals("the requested width wins — overriding an axis is not the defect",
                260, a.getBounds().getWidth());
        assertEquals("the height nobody overrode must come from what the batch queued (300), not "
                + "from the 55 the pre-batch bounds still held", 300, a.getBounds().getHeight());
    }

    /**
     * The commonest call shape: no size overrides at all, so BOTH axes are fallbacks and the whole
     * queued rectangle must survive the pass.
     */
    @Test
    public void shouldLayOutAChildAtTheSizeTheBatchQueued_whenNoOverridesAreGiven() {
        IDiagramModelObject[] f = crossedFixture();
        IDiagramModelObject a = f[0];

        dispatcher.beginBatch(SESSION, "resize a child, then optimise the order");
        accessor.updateViewObject(SESSION, a.getId(), null, null, 400, 300,
                null, null, null, null);
        OptimizeGroupOrderResultDto dto = accessor.optimizeGroupOrder(
                SESSION, VIEW_ID, "column", 40, 10, null, null, false, null, null).entity();
        dispatcher.endBatch(SESSION, true);

        assertEquals("the queued width must survive the reorder", 400, a.getBounds().getWidth());
        assertEquals("and the queued height", 300, a.getBounds().getHeight());
        assertNull("the pass no longer changes this child's size, so it must not be named either. "
                + "resizedElements was: " + dto.resizedElements(), resizedEntryFor(dto, a.getId()));
    }

    /**
     * The grid arm's COLUMN COUNT, which is the same defect one argument to the left: with no
     * explicit column count the calculator derives one from the group's own width, so a pre-batch
     * read there chooses the shape of the whole grid from a rectangle the group is about to stop
     * having. This is the second of the two sites that read a container width; the layout tool's
     * own is pinned in its sibling class, and a fix applied to only one of them leaves this one
     * measuring the pre-batch rectangle.
     */
    @Test
    public void shouldDeriveTheGridColumnCountFromTheQueuedGroupWidth() {
        IDiagramModelObject[] f = crossedFixture();
        IDiagramModelObject left = f[3];

        dispatcher.beginBatch(SESSION, "widen the group, then optimise the order as a grid");
        accessor.updateViewObject(SESSION, left.getId(), null, null, 1500, 700,
                null, null, null, null);
        accessor.optimizeGroupOrder(SESSION, VIEW_ID, "grid", 40, 10, null, null, false, null, null);
        dispatcher.endBatch(SESSION, true);

        int firstY = f[0].getBounds().getY();
        for (int i = 1; i < 3; i++) {
            assertEquals("the widened group fits all three children on ONE row; the pre-batch 700 "
                    + "fits two and wraps the third, which is an arrangement this one cannot be "
                    + "confused with", firstY, f[i].getBounds().getY());
        }
    }

    /**
     * The report still describes the model. Whatever the pass DOES land a child at, the rectangle
     * it publishes must equal a fresh read once the batch commits — the fix moves what the sizes
     * ARE and must leave the reporting's agreement with the model intact.
     */
    @Test
    public void shouldStillPublishTheRectangleTheModelHolds_whenTheBatchQueuedADifferentSize() {
        IDiagramModelObject[] f = crossedFixture();
        IDiagramModelObject a = f[0];

        dispatcher.beginBatch(SESSION, "resize a child, then optimise the order at a fixed width");
        accessor.updateViewObject(SESSION, a.getId(), null, null, 400, 300,
                null, null, null, null);
        OptimizeGroupOrderResultDto dto = accessor.optimizeGroupOrder(
                SESSION, VIEW_ID, "column", 40, 10, 260, null, false, null, null).entity();
        dispatcher.endBatch(SESSION, true);

        MovedViewObjectDto reported = resizedEntryFor(dto, a.getId());
        assertNotNull("the requested width differs from the queued one, so this child really is "
                + "re-sized by the pass and must be named. resizedElements was: "
                + dto.resizedElements(), reported);
        IBounds landed = a.getBounds();
        assertEquals("reported x must equal the model's", landed.getX(), reported.newX());
        assertEquals("reported y must equal the model's", landed.getY(), reported.newY());
        assertEquals("reported width must equal the model's",
                landed.getWidth(), reported.newWidth());
        assertEquals("reported height must equal the model's",
                landed.getHeight(), reported.newHeight());
    }

    // ---- the group's own re-fit: ruled, recorded, not widened into ---------------------------------

    /**
     * The group itself is re-fitted to its reordered children, and that write is reported by
     * NOTHING. {@code resizedElements} comes from the child-placement range, so it names children;
     * the group is not one of its own children. {@code groupDetails} carries an id, a name, an
     * element count, a reordered flag and the arrangement — no geometry at all.
     *
     * <p>Ruled: this is a SECOND unreported write, not something the new field already covers. It
     * is a different write range with a different subject, and it is filed rather than folded into
     * this commit. This test records the current behaviour so the filed row's premise cannot rot
     * silently — if the group ever does start being reported, this goes red and the row is
     * revisited rather than quietly left open.</p>
     */
    @Test
    public void shouldNotYetReportTheGroupsOwnRefit_whichIsFiledSeparately() {
        IDiagramModelObject[] f = crossedFixture();
        IDiagramModelObject left = f[3];
        IBounds before = copy(left.getBounds());

        OptimizeGroupOrderResultDto dto = accessor.optimizeGroupOrder(
                SESSION, VIEW_ID, "column", 40, 10, null, null, false, null, null).entity();
        IBounds after = left.getBounds();

        assertTrue("fixture guard: the group must actually have been re-fitted: "
                + before.getWidth() + "x" + before.getHeight() + " -> "
                + after.getWidth() + "x" + after.getHeight(),
                after.getWidth() != before.getWidth() || after.getHeight() != before.getHeight());

        assertNull("the group is not one of its own children, so the child-placement range cannot "
                + "name it. resizedElements was: " + dto.resizedElements(),
                resizedEntryFor(dto, left.getId()));
        assertFalse("and the per-group detail carries no geometry to report it in either",
                dto.groupDetails().isEmpty());
        String detail = dto.groupDetails().toString();
        assertFalse("groupDetails must not be read as carrying the rectangle — it has no width "
                + "field at all. Was: " + detail, detail.contains("width"));
    }

    // ---- stub --------------------------------------------------------------------------------------

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
