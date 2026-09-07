package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
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
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.response.dto.ResizeElementsResultDto;

/**
 * Pins {@code resize-elements-to-fit}'s geometry against the model it is actually measuring.
 *
 * <p>The tool prepares many mutations before any of them executes, and inside a batch nothing
 * executes until commit. Two things followed from that, and both wrote a wrong number while
 * reporting success:</p>
 *
 * <ul>
 *   <li><strong>Across calls.</strong> Each call built its parent-fit map empty, so two calls in one
 *       batch each measured a shared group against its pre-batch size and each emitted an
 *       <em>absolute</em> resize for it. The last one queued won and the first one's growth
 *       vanished.</li>
 *   <li><strong>Within one call.</strong> A leaf is sized in the first pass and may then be shifted
 *       down in the second when it would sit under its parent's label. The shift supplies only a new
 *       y and merged its width and height from {@code getBounds()} — which the first pass's command
 *       has not written yet — so the second command reinstated the pre-resize size.</li>
 * </ul>
 *
 * <p>One pass-scoped map of effective object geometry, seeded from what the unit of work has already
 * established, closes both. It is deliberately NOT the parent-fit map: that one is reported as
 * {@code resizedGroups}, and mixing element geometry into it would name elements as resized groups.
 *
 * <h2>Execution model</h2>
 *
 * <p>Same headless idiom as the other batch classes. Element names are kept short so
 * {@code ElementSizer} short-circuits to its defaults ({@code 120x55}) without measuring text on a
 * display.</p>
 */
public class BatchResizeElementsToFitTest {

    private static final String SESSION = "batch-resize-fit-session";

    /** {@code ElementSizer.DEFAULT_WIDTH} / {@code DEFAULT_HEIGHT} for a short name. */
    private static final int AUTO_W = 120;
    private static final int AUTO_H = 55;

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
        model.setName("Resize Fit Fixture");
        model.setId("model-resize-fit");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Fitting");
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

    private String group(String label, int x, int y, int w, int h) {
        return accessor.addGroupToView(SESSION, view.getId(), label, x, y, w, h, null, null, null)
                .entity().viewObjectId();
    }

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

    // ---- across calls in one batch ---------------------------------------------------------------

    /**
     * Two calls in one batch, each growing the same group in a different direction, accumulate.
     *
     * <p>The first call's child needs the group {@code 400 + 120 + 10 = 530} wide; the second's
     * needs it {@code 300 + 55 + 10 = 365} tall. Measured before the fix: {@code 140x365} — the
     * second call re-measured the group against its pre-batch {@code 100x100}, computed a width of
     * only {@code 10 + 120 + 10}, and its absolute resize overwrote the first call's {@code 530}.
     */
    @Test
    public void shouldAccumulateGroupBounds_whenTwoResizeCallsShareAGroupInOneBatch() throws Exception {
        String g = group("G", 0, 0, 100, 100);
        IDiagramModelContainer gLive = (IDiagramModelContainer) find(view, g);
        IDiagramModelObject childA = live("ba-001", "actor-1", 400, 10, 40, 20, gLive);
        IDiagramModelObject childB = live("bp-001", "actor-2", 10, 300, 40, 20, gLive);

        dispatcher.beginBatch(SESSION, "two resize passes over one group");
        accessor.resizeElementsToFit(SESSION, view.getId(), List.of(childA.getId()));
        accessor.resizeElementsToFit(SESSION, view.getId(), List.of(childB.getId()));
        dispatcher.endBatch(SESSION, true);

        assertEquals("the group carries BOTH calls' requirements, not just the last queued: " + box(find(view, g)),
                "0,0 530x365", box(find(view, g)));
    }

    /**
     * The report names only what this pass actually grew. The second call seeds its fit map with the
     * group the first call already grew, and that seed must not be mistaken for an outcome: the
     * group is wide and tall enough for the second call's child, so the second call resized nothing
     * and must say so.
     */
    @Test
    public void shouldReportOnlyGroupsThisPassGrew_whenTheFitMapIsSeeded() throws Exception {
        String g = group("G", 0, 0, 100, 100);
        IDiagramModelContainer gLive = (IDiagramModelContainer) find(view, g);
        IDiagramModelObject childA = live("ba-002", "actor-1", 400, 10, 40, 20, gLive);
        IDiagramModelObject childB = live("bp-002", "actor-2", 10, 20, 40, 20, gLive);

        dispatcher.beginBatch(SESSION, "grow once, then a call that needs no growth");
        ResizeElementsResultDto first = accessor
                .resizeElementsToFit(SESSION, view.getId(), List.of(childA.getId())).entity();
        ResizeElementsResultDto second = accessor
                .resizeElementsToFit(SESSION, view.getId(), List.of(childB.getId())).entity();
        dispatcher.endBatch(SESSION, true);

        assertEquals("the first call grew the group and says so", 1, first.resizedGroups().size());
        assertEquals("the first call's reported width", 530, first.resizedGroups().get(0).newWidth());
        assertTrue("the second call grew nothing, so it must report no resized group — the seeded "
                        + "entry is knowledge, not an outcome: " + second.resizedGroups(),
                second.resizedGroups().isEmpty());
    }

    // ---- within one call -------------------------------------------------------------------------

    /**
     * A leaf that the same call both re-sizes and shifts down keeps the size.
     *
     * <p>The first pass sizes the leaf to {@code 120x55}; the second finds it sitting above its
     * parent's label area and shifts it to {@code y = 25}, supplying only the new y. That second
     * command merged its width and height from the model, where the first pass's command had not
     * run, so it wrote back the pre-resize {@code 40x20} — and executed last, which is what the
     * model kept. Measured before the fix: {@code 10,25 40x20}, while the response reported the
     * leaf resized to {@code 120x55}. Needs no batch: one call is a unit of work of its own.</p>
     */
    @Test
    public void shouldKeepTheResize_whenTheSameCallAlsoShiftsTheLeafDown() {
        IDiagramModelObject parent = live("p-001", "actor-1", 0, 0, 60, 30, view);
        live("l-001", "actor-2", 10, 10, 40, 20, (IDiagramModelContainer) parent);

        accessor.resizeElementsToFit(SESSION, view.getId(), List.of(parent.getId(), "l-001"));

        assertEquals("the leaf keeps the size pass 1 gave it and takes the shifted y: "
                        + box(find(view, "l-001")),
                "10,25 " + AUTO_W + "x" + AUTO_H, box(find(view, "l-001")));
    }

    // ---- outside a batch --------------------------------------------------------------------------

    /**
     * Non-regression: with no batch open and no bulk pass, the seed is empty and a single call
     * measures exactly what it measured before.
     */
    @Test
    public void shouldMeasureAgainstLiveBounds_whenNoBatchIsOpen() {
        String g = group("G", 0, 0, 100, 100);
        IDiagramModelContainer gLive = (IDiagramModelContainer) find(view, g);
        IDiagramModelObject childA = live("ba-003", "actor-1", 400, 10, 40, 20, gLive);

        accessor.resizeElementsToFit(SESSION, view.getId(), List.of(childA.getId()));

        assertEquals("immediate mode grows the group from its live bounds", "0,0 530x100",
                box(find(view, g)));
    }

    // ---- approval arm: a DECLARED limitation, measured ---------------------------------------------

    /**
     * Declared limit, measured rather than assumed: an approved {@code resize-elements-to-fit}
     * applies the geometry its prepare computed, however much later it is approved.
     *
     * <p>Unlike {@code update-view-object}, whose proposal stores a lambda that re-runs the whole
     * prepare — and therefore re-reads the queue at approval time — this tool stores an
     * already-built compound. Seeding the prepare, which is what the two defects above needed, does
     * nothing for that arm: the seed is read when the compound is built, not when it is applied.</p>
     *
     * <p>Measured: the group is {@code 100x100} when the proposal is stored, so the compound carries
     * an absolute resize to {@code 530x100}. Growing the group to {@code 900x900} in the meantime is
     * then overwritten by the approval, which lands {@code 530x100} — a shrink, because the compound
     * remembers a grow-only decision taken against a size the group no longer has.</p>
     *
     * <p>Not repaired here: re-running this prepare at approval time means extracting the whole
     * two-pass computation behind a supplier, which is a change to how the tool is structured rather
     * than to what it measures. Asserted as it stands so nothing above reads as coverage of it.</p>
     */
    @Test
    public void shouldApplyPrepareTimeGeometry_whenAnApprovedResizeLandsLater() throws Exception {
        String g = group("G", 0, 0, 100, 100);
        IDiagramModelContainer gLive = (IDiagramModelContainer) find(view, g);
        IDiagramModelObject childA = live("ba-004", "actor-1", 400, 10, 40, 20, gLive);

        dispatcher.setApprovalModeProvider(() -> true);
        var result = accessor.resizeElementsToFit(SESSION, view.getId(), List.of(childA.getId()));
        assertNotNull("approval mode must produce a proposal, not execute", result.proposalContext());
        assertEquals("nothing is applied while the proposal waits", "0,0 100x100", box(find(view, g)));

        find(view, g).setBounds(0, 0, 900, 900);
        dispatcher.approveProposal(SESSION, result.proposalContext().proposalId());

        assertEquals("the approved compound applies the geometry measured at prepare time, "
                        + "overwriting the 900x900 the group had grown to in the meantime",
                "0,0 530x100", box(find(view, g)));
    }

    /**
     * The card must say which kind of gate it is. The test above shows this proposal applies exactly what
     * was measured at prepare time; a re-preparing proposal instead recomputes against the model as it
     * stands when the human clicks. Both render identically in the dock, so without the statement the
     * human cannot tell whether Approve means "do this" or "work out what to do now".
     */
    @Test
    public void shouldNameTheReviewedOrRejectGate_onTheProposalCard() {
        String g = group("G", 0, 0, 100, 100);
        IDiagramModelContainer gLive = (IDiagramModelContainer) find(view, g);
        IDiagramModelObject childA = live("ba-005", "actor-1", 400, 10, 40, 20, gLive);

        dispatcher.setApprovalModeProvider(() -> true);
        accessor.resizeElementsToFit(SESSION, view.getId(), List.of(childA.getId()));

        assertEquals("Element resize ready for application."
                        + " Approving applies exactly this reviewed result; it is not recomputed.",
                dispatcher.getPendingProposalDtos(SESSION).get(0).validationSummary());
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
