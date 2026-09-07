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
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.INode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.vheerden.archi.mcp.response.dto.LayoutFlatViewResultDto;
import net.vheerden.archi.mcp.response.dto.MovedViewObjectDto;

/**
 * Pins what {@code layout-flat-view} says about the elements whose SIZE it changed.
 *
 * <h2>The defect</h2>
 *
 * <p>With {@code autoLayoutChildren} on, the pass lays out the children embedded inside a top-level
 * element and then grows that element if its children no longer fit. The growth was recorded in a
 * local boolean, {@code anyParentResized}, used to decide whether to re-run the top-level
 * arrangement and then discarded. The response carried {@code elementsRepositioned} and
 * {@code childrenRepositioned} — two counts of objects PLACED, moved or not and resized or not —
 * so an agent that cannot see the canvas had no way to learn that a parent it never named had
 * changed shape.</p>
 *
 * <h2>One write range applies and one cannot</h2>
 *
 * <p>The top-level loop is the reachable one, and only through that parent-growth branch: all three
 * flat position calculators write each element's own {@code bounds.getWidth()/getHeight()}, and the
 * flat grid is uniform in cell SPACING only. Both in-place recomputations that run after a parent
 * grows write {@code pos[0]} and {@code pos[1]} in every arm and never assign {@code pos[2]} or
 * {@code pos[3]} — checked arm by arm, not assumed from their Javadoc.</p>
 *
 * <p>OUTSIDE A BATCH the embedded-children loop cannot produce a differing size at all: its
 * positions come from a column layout whose sizes are resolved with a null width, a null height and
 * autoWidth false, so every element of the pair is fixed at the call site and each child is written
 * back the size it already had. That is pinned below as an impossibility rather than asserted in
 * prose, and the range is routed through the same observation so the claim is enforced
 * structurally: if any of those three literals ever changes, the field reports it instead of
 * staying silent.</p>
 *
 * <p><strong>Inside a batch the impossibility holds for the same reason, once the input agrees
 * with the observation.</strong> Both ranges now resolve their sizes from the child's effective
 * rectangle, so a child an earlier operation of the same batch re-sized is laid out at THAT size
 * and is therefore not reported as changed either. It was the input that was stale, never the
 * report: the observation always measured against the queue, which is what made the disagreement
 * visible in the first place. Comparing the report against live bounds instead would have hidden
 * it, and that is still exactly what must not be done.</p>
 *
 * <p>The top-level range carries the wider consequence and is pinned for it: the flat grid derives
 * its cell SPACING from the largest element it measures, so what the pass reads there decides where
 * every sibling lands, not merely how big one element is.</p>
 *
 * <h2>Execution model, and why this class is headless</h2>
 *
 * <p>{@code layoutFlatView} realizes no SWT or GEF: it runs no assessment pass, no routing pass and
 * no text measurement — its child path resolves sizes with autoWidth false, so the label-width
 * helper is never reached. The fixture is therefore built from plain EMF rather than through the
 * placement tools, and the dispatcher is overridden so nothing marshals onto a display. Its
 * sibling class for the spacing tool needs one only because that tool routes and assesses.</p>
 */
public class LayoutFlatViewResizeReportingTest {

    private static final String SESSION = "layout-flat-view-resize-report-session";

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
        model.setName("Flat View Resize Reporting Fixture");
        model.setId("model-flat-view-resize-report");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Flat");
        diagrams.getElements().add(view);

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
            @Override
            public UndoRedoState undo(int steps) {
                for (int i = 0; i < steps && stack.canUndo(); i++) {
                    stack.undo();
                }
                return null;
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

    /** A named Node placed directly on the view at an explicit rectangle. */
    private IDiagramModelArchimateObject topLevel(String name, int x, int y, int w, int h) {
        INode element = factory.createNode();
        element.setId("node-" + name);
        element.setName(name);
        model.getFolder(FolderType.TECHNOLOGY).getElements().add(element);

        IDiagramModelArchimateObject obj = factory.createDiagramModelArchimateObject();
        obj.setId("vo-" + name);
        obj.setArchimateElement(element);
        obj.setBounds(x, y, w, h);
        view.getChildren().add(obj);
        return obj;
    }

    /** A named Node nested inside {@code parent} at an explicit relative rectangle. */
    private IDiagramModelArchimateObject child(IDiagramModelContainer parent, String name,
            int x, int y, int w, int h) {
        INode element = factory.createNode();
        element.setId("node-" + name);
        element.setName(name);
        model.getFolder(FolderType.TECHNOLOGY).getElements().add(element);

        IDiagramModelArchimateObject obj = factory.createDiagramModelArchimateObject();
        obj.setId("vo-" + name);
        obj.setArchimateElement(element);
        obj.setBounds(x, y, w, h);
        parent.getChildren().add(obj);
        return obj;
    }

    private LayoutFlatViewResultDto layout(String arrangement, boolean autoLayoutChildren) {
        return accessor.layoutFlatView(SESSION, view.getId(), arrangement,
                null, null, null, null, null, autoLayoutChildren).entity();
    }

    private static MovedViewObjectDto resizedEntryFor(LayoutFlatViewResultDto dto, String id) {
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

    // ---- the apply: a parent grown to contain its children ---------------------------------------

    /**
     * The reachable branch. A parent far too small for the children it holds is grown to fit them,
     * and the caller asked only for a flat arrangement of the top level.
     */
    @Test
    public void shouldNameAParentItGrewToContainItsChildren() throws Exception {
        IDiagramModelArchimateObject parent = topLevel("Parent", 0, 0, 140, 60);
        child(parent, "A", 10, 30, 120, 55);
        child(parent, "B", 10, 100, 120, 55);
        child(parent, "C", 10, 170, 120, 55);
        topLevel("Loner", 400, 0, 140, 60);

        IBounds before = copy(parent.getBounds());
        LayoutFlatViewResultDto dto = layout("row", true);
        IBounds after = parent.getBounds();

        assertTrue("fixture guard: the parent must actually have grown, or this proves nothing: "
                + before.getWidth() + "x" + before.getHeight() + " -> "
                + after.getWidth() + "x" + after.getHeight(),
                after.getWidth() > before.getWidth() || after.getHeight() > before.getHeight());

        MovedViewObjectDto reported = resizedEntryFor(dto, parent.getId());
        assertNotNull("a parent this call grew to contain its own children must be named — the "
                + "caller asked for a flat arrangement, not for this element to change size, and "
                + "the only trace used to be a local boolean. resizedElements was: "
                + dto.resizedElements(), reported);
        assertEquals("the reported width must be the LANDED one", after.getWidth(),
                reported.newWidth());
        assertEquals("and the landed height", after.getHeight(), reported.newHeight());
        assertEquals("and the landed x", after.getX(), reported.newX());
        assertEquals("and the landed y", after.getY(), reported.newY());

        String json = new ObjectMapper().writeValueAsString(dto);
        assertTrue("the rectangle must reach the wire, not just the Java object. Response was: "
                + json, json.contains("\"resizedElements\""));
        assertTrue("the wire must carry the landed height. Response was: " + json,
                json.contains("\"newHeight\":" + after.getHeight()));
    }

    /**
     * The sibling that only moves is not named. Both elements are placed and only one changes size,
     * so a pass that reported the whole placed list would pass the assertion above and fail this.
     */
    @Test
    public void shouldNotNameATopLevelElementThatOnlyMoved() {
        IDiagramModelArchimateObject parent = topLevel("Parent", 0, 0, 140, 60);
        child(parent, "A", 10, 30, 120, 55);
        child(parent, "B", 10, 100, 120, 55);
        IDiagramModelArchimateObject loner = topLevel("Loner", 900, 0, 140, 60);

        int lonerXBefore = loner.getBounds().getX();
        LayoutFlatViewResultDto dto = layout("row", true);

        assertTrue("fixture guard: the sibling must actually have moved",
                loner.getBounds().getX() != lonerXBefore);
        assertEquals("fixture guard: and must have kept its size",
                140, loner.getBounds().getWidth());
        assertNull("an element that only moved must not be named. resizedElements was: "
                + dto.resizedElements(), resizedEntryFor(dto, loner.getId()));
        assertNotNull("...while the grown parent still is",
                resizedEntryFor(dto, parent.getId()));
    }

    /**
     * The negative. Nothing embedded, so nothing can grow: every element is repositioned, none
     * changes size, and the empty list is omitted from the wire rather than serialized as
     * {@code []}.
     */
    @Test
    public void shouldNameNothing_whenEveryElementMovesAndNoneChangesSize() throws Exception {
        IDiagramModelArchimateObject first = topLevel("First", 500, 300, 140, 60);
        IDiagramModelArchimateObject second = topLevel("Second", 900, 700, 200, 90);

        LayoutFlatViewResultDto dto = layout("row", true);

        assertEquals("fixture guard: the first keeps its own width", 140,
                first.getBounds().getWidth());
        assertEquals("fixture guard: the second keeps its own, different width", 200,
                second.getBounds().getWidth());
        assertTrue("the tool still counts the elements it placed", dto.elementsRepositioned() > 0);
        assertTrue("a call that resized nothing must name nothing. resizedElements was: "
                + dto.resizedElements(), dto.resizedElements().isEmpty());

        String json = new ObjectMapper().writeValueAsString(dto);
        assertFalse("an empty list must be omitted from the wire, not serialized as []. "
                + "Response was: " + json, json.contains("resizedElements"));
    }

    /**
     * The grid arm is uniform in cell SPACING, not cell SIZE — its own comment says so and the code
     * matches. Three differently-sized elements laid out in a grid keep all three sizes, so nothing
     * is named. Without this the apply above could be read as "grids resize things", which is the
     * belief that made the sibling tools' defect look grid-only.
     */
    @Test
    public void shouldNameNothing_whenTheFlatGridArmSpacesButDoesNotResize() {
        IDiagramModelArchimateObject narrow = topLevel("Narrow", 0, 0, 140, 60);
        IDiagramModelArchimateObject wide = topLevel("Wide", 400, 0, 420, 60);
        IDiagramModelArchimateObject tall = topLevel("Tall", 0, 400, 140, 300);

        LayoutFlatViewResultDto dto = layout("grid", true);

        assertEquals("the narrow element keeps its own width", 140,
                narrow.getBounds().getWidth());
        assertEquals("the wide element keeps its own width", 420, wide.getBounds().getWidth());
        assertEquals("the tall element keeps its own height", 300, tall.getBounds().getHeight());
        assertTrue("the flat grid uses uniform cell SPACING, not uniform cell SIZE, so it resizes "
                + "nothing. resizedElements was: " + dto.resizedElements(),
                dto.resizedElements().isEmpty());
    }

    // ---- the decline: the embedded-children range cannot resize ----------------------------------

    /**
     * The impossibility, pinned rather than asserted. The embedded children are laid out and
     * written back, and every one keeps the size it had, because the column layout that produced
     * their positions resolves sizes with a null width, a null height and autoWidth false.
     *
     * <p>Mutation-tested: change any one of those three literals at the call site and the children
     * land at a different size, the range reports them, and this test goes red. The claim is
     * therefore enforced by the same observation that reports the apply, not by a comment.</p>
     */
    @Test
    public void shouldNameNoEmbeddedChild_becauseTheChildRangeCannotChangeASize() {
        IDiagramModelArchimateObject parent = topLevel("Parent", 0, 0, 900, 700);
        IDiagramModelObject a = child(parent, "A", 10, 30, 120, 55);
        IDiagramModelObject b = child(parent, "B", 10, 100, 260, 90);
        IDiagramModelObject c = child(parent, "C", 10, 200, 60, 40);

        LayoutFlatViewResultDto dto = layout("row", true);

        assertEquals("fixture guard: the children must actually have been laid out",
                3, dto.childrenRepositioned());
        assertEquals("child A keeps its own width", 120, a.getBounds().getWidth());
        assertEquals("child B keeps its own, larger width", 260, b.getBounds().getWidth());
        assertEquals("child C keeps its own, smaller width", 60, c.getBounds().getWidth());
        assertEquals("child B keeps its own height", 90, b.getBounds().getHeight());

        assertNull("the embedded-children range cannot change a size, so no child may be named. "
                + "resizedElements was: " + dto.resizedElements(),
                resizedEntryFor(dto, a.getId()));
        assertNull(resizedEntryFor(dto, b.getId()));
        assertNull(resizedEntryFor(dto, c.getId()));
        assertTrue("the parent is roomy enough that it does not grow either, so the whole list is "
                + "empty on this fixture. resizedElements was: " + dto.resizedElements(),
                dto.resizedElements().isEmpty());
    }

    /**
     * The bound on the decline, inside an open batch. The embedded-children range resolves its
     * sizes with a null width, a null height and autoWidth false, so every axis is a fallback — and
     * inside a batch a fallback must read what the batch queued, not the pre-batch bounds. A child
     * an earlier operation of the same unit of work widened therefore keeps that width, and since
     * the pass no longer changes its size it is not named either.
     */
    @Test
    public void shouldKeepAnEmbeddedChildAtTheSizeTheBatchQueued() {
        IDiagramModelArchimateObject parent = topLevel("Parent", 0, 0, 900, 700);
        IDiagramModelObject a = child(parent, "A", 10, 30, 120, 55);
        child(parent, "B", 10, 100, 120, 55);

        dispatcher.beginBatch(SESSION, "widen a child, then lay the view out");
        accessor.updateViewObject(SESSION, a.getId(), null, null, 400, null,
                null, null, null, null);
        LayoutFlatViewResultDto dto = layout("row", true);
        dispatcher.endBatch(SESSION, true);

        assertEquals("the width the batch queued must survive the layout that follows it in the "
                + "same unit of work", 400, a.getBounds().getWidth());
        assertNull("and with the size unchanged BY THIS PASS the child must not be named — the "
                + "input now measures against the same queue the report always did. "
                + "resizedElements was: " + dto.resizedElements(), resizedEntryFor(dto, a.getId()));
    }

    /**
     * The report still describes the model, on the queue-aware path. The other batch pins here
     * assert that a queued size SURVIVES and is therefore NOT named, so none of them can show the
     * published rectangle still agreeing with the model once the fix is in. A parent grown to
     * contain a child the batch just widened is re-sized by this pass, so it is named — and the
     * rectangle it is named at must be the one the model ends up holding.
     */
    @Test
    public void shouldStillPublishTheRectangleTheModelHolds_whenTheBatchWidenedAChild() {
        IDiagramModelArchimateObject parent = topLevel("Parent", 0, 0, 140, 60);
        IDiagramModelObject a = child(parent, "A", 10, 30, 120, 55);

        dispatcher.beginBatch(SESSION, "widen a child, then lay the view out");
        accessor.updateViewObject(SESSION, a.getId(), null, null, 400, 300,
                null, null, null, null);
        LayoutFlatViewResultDto dto = layout("row", true);
        dispatcher.endBatch(SESSION, true);

        assertEquals("fixture guard: the queued width must reach the child, or the parent had no "
                + "reason to grow and this pin proves nothing", 400, a.getBounds().getWidth());

        MovedViewObjectDto reported = resizedEntryFor(dto, parent.getId());
        assertNotNull("the parent was grown to contain a child the batch widened, so it must be "
                + "named. resizedElements was: " + dto.resizedElements(), reported);

        IBounds landed = parent.getBounds();
        assertEquals("reported x must equal the model's", landed.getX(), reported.newX());
        assertEquals("reported y must equal the model's", landed.getY(), reported.newY());
        assertEquals("reported width must equal the model's",
                landed.getWidth(), reported.newWidth());
        assertEquals("reported height must equal the model's",
                landed.getHeight(), reported.newHeight());
    }

    /**
     * The row computer, which the grid pin below does not reach: the three flat computers are
     * separate loops with separate reads, and a fix applied to one of them leaves the others
     * measuring pre-batch bounds. The next element starts past the width of the one before it, so
     * the queued 400 puts this sibling at 450 where a live read puts it at 170.
     */
    @Test
    public void shouldSpaceTheTopLevelRowFromTheSizeTheBatchQueued() {
        IDiagramModelArchimateObject a = topLevel("A", 0, 0, 120, 55);
        IDiagramModelArchimateObject b = topLevel("B", 200, 0, 120, 55);

        dispatcher.beginBatch(SESSION, "resize an element, then lay the view out as a row");
        accessor.updateViewObject(SESSION, a.getId(), null, null, 400, 300,
                null, null, null, null);
        accessor.layoutFlatView(SESSION, view.getId(), "row", 40, 10, null, null, null, false);
        dispatcher.endBatch(SESSION, true);

        assertEquals("the queued width must survive", 400, a.getBounds().getWidth());
        assertEquals("and the sibling must start past THAT width, not past the pre-batch 120",
                450, b.getBounds().getX());
    }

    /**
     * The column computer, the third of the three. Here it is the HEIGHT that advances the cursor,
     * so the queued 300 puts this sibling at 350 where a live read puts it at 105.
     */
    @Test
    public void shouldSpaceTheTopLevelColumnFromTheSizeTheBatchQueued() {
        IDiagramModelArchimateObject a = topLevel("A", 0, 0, 120, 55);
        IDiagramModelArchimateObject b = topLevel("B", 0, 200, 120, 55);

        dispatcher.beginBatch(SESSION, "resize an element, then lay the view out as a column");
        accessor.updateViewObject(SESSION, a.getId(), null, null, 400, 300,
                null, null, null, null);
        accessor.layoutFlatView(SESSION, view.getId(), "column", 40, 10, null, null, null, false);
        dispatcher.endBatch(SESSION, true);

        assertEquals("the queued height must survive", 300, a.getBounds().getHeight());
        assertEquals("and the sibling must start below THAT height, not below the pre-batch 55",
                350, b.getBounds().getY());
    }

    /**
     * The top-level range, which has the wider blast radius: the flat grid takes its cell SPACING
     * from the largest element it measures, so honouring the queue there moves every sibling too.
     * With padding 10, spacing 40 and two columns, three 120x55 elements land at (10,10), (170,10)
     * and (10,105); once the queued 400x300 is the basis they land at (10,10), (450,10) and
     * (10,350). The pin asserts a SIBLING's position for that reason, not only the resized
     * element's size.
     */
    @Test
    public void shouldSpaceTheTopLevelGridFromTheSizeTheBatchQueued() {
        IDiagramModelArchimateObject a = topLevel("A", 0, 0, 120, 55);
        IDiagramModelArchimateObject b = topLevel("B", 200, 0, 120, 55);
        IDiagramModelArchimateObject c = topLevel("C", 400, 0, 120, 55);

        dispatcher.beginBatch(SESSION, "resize an element, then lay the view out as a grid");
        accessor.updateViewObject(SESSION, a.getId(), null, null, 400, 300,
                null, null, null, null);
        accessor.layoutFlatView(SESSION, view.getId(), "grid", 40, 10, null, null, 2, false);
        dispatcher.endBatch(SESSION, true);

        assertEquals("the queued width must survive", 400, a.getBounds().getWidth());
        assertEquals("and the queued height", 300, a.getBounds().getHeight());

        assertEquals("the next column starts past the widest element the pass MEASURED, and inside "
                + "a batch that is the queued 400 — a pre-batch read puts this sibling at 170",
                450, b.getBounds().getX());
        assertEquals("and the second row starts past the tallest, the queued 300 — a pre-batch "
                + "read puts this sibling at 105", 350, c.getBounds().getY());
    }

    // ---- the batch frame -------------------------------------------------------------------------

    /**
     * Inside an open batch the comparison basis must be what the batch has already queued, not the
     * pre-batch bounds. A parent an earlier operation of the same batch already grew to the size
     * this pass would land it at has not been resized BY THIS PASS, and naming it would be a
     * fabricated outcome.
     */
    @Test
    public void shouldNotNameAParentTheBatchHadAlreadyGrownToTheSameSize() {
        IDiagramModelArchimateObject parent = topLevel("Parent", 0, 0, 140, 60);
        child(parent, "A", 10, 30, 120, 55);
        child(parent, "B", 10, 100, 120, 55);
        child(parent, "C", 10, 170, 120, 55);

        // What the pass alone would land the parent at.
        LayoutFlatViewResultDto probe = layout("row", true);
        MovedViewObjectDto grown = resizedEntryFor(probe, parent.getId());
        assertNotNull("fixture guard: the pass must grow the parent outside a batch", grown);
        dispatcher.undo(1);

        dispatcher.beginBatch(SESSION, "size the parent, then lay the view out");
        accessor.updateViewObject(SESSION, parent.getId(), null, null,
                grown.newWidth(), grown.newHeight(), null, null, null, null);
        LayoutFlatViewResultDto dto = layout("row", true);
        dispatcher.endBatch(SESSION, true);

        assertNull("the batch had already sized this parent to the rectangle the pass computes, so "
                + "the pass changed nothing about its size and must not claim it. "
                + "resizedElements was: " + dto.resizedElements(),
                resizedEntryFor(dto, parent.getId()));
    }

    // ---- stub ------------------------------------------------------------------------------------

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
