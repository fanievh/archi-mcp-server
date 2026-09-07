package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;
import net.vheerden.archi.mcp.response.dto.AutoLayoutAndRouteResultDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;

/**
 * The rating-regression disclosure driven through the <strong>real</strong> quality loop.
 *
 * <p>The pure decision is pinned headlessly beside {@code QualityTargetTermination}. What can only
 * be checked here is that the loop actually takes the pre-call measurement, that it takes it before
 * it mutates anything, and that what reaches the DTO agrees with the model.</p>
 *
 * <p>The negative arm is deliberately <em>not</em> a stub that could never warn. It drives the same
 * code as the positive arm and asserts the emitted warning against the predicate applied to the two
 * ratings the run itself reports — so a loop that warned on an improvement, or stayed silent on a
 * regression, fails here either way.</p>
 */
public class AutoLayoutAndRouteRatingRegressionTest {

    private static final String SESSION = "auto-layout-rating-regression-session";
    private static final String VIEW_ID = "view-regression-1";

    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private MutationDispatcher dispatcher;

    @Before
    public void setUp() {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Rating Regression Fixture");
        model.setId("model-rating-regression");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId(VIEW_ID);
        view.setName("Flow");
        diagrams.getElements().add(view);

        // Elements and relationships here are all left UNNAMED, and that is load-bearing rather than
        // lazy. AssessmentCollector measures a label through SWT to pre-compute its width, and on a
        // display-less Linux CI runner that call throws SWTError: No more handles
        // [gtk_init_check() failed]. SWTError extends Error, not Exception, so the collector's
        // catch (Exception e) does not catch it — while the same call on macOS throws SWTException,
        // which IS caught. A named fixture therefore passes locally and errors in CI, which is
        // exactly what this class did on its first landing. The measurement is guarded by
        // `name != null && !name.isEmpty()`, so an unnamed element skips it entirely. Nothing
        // asserted here reads a name: every assertion reads a rating, a warning code or a count.
        IFolder business = model.getFolder(FolderType.BUSINESS);
        for (int i = 1; i <= 4; i++) {
            IBusinessActor a = factory.createBusinessActor();
            a.setId("actor-" + i);
            business.getElements().add(a);
        }
        addRelationship(factory, "rel-1", "actor-1", "actor-2");
        addRelationship(factory, "rel-2", "actor-2", "actor-3");
        addRelationship(factory, "rel-3", "actor-1", "actor-4");

        stubModelManager.setModels(List.of(model));

        CommandStack stack = new CommandStack();
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                stack.execute(toPlainCompound(command));
            }
            @Override
            protected void dispatchCommand(Command command) {
                stack.execute(toPlainCompound(command));
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

        // Deliberately overlapped on arrival, so the view the loop is HANDED rates materially
        // differently from anything the loop produces. A fixture whose before and after ratings
        // coincide cannot tell a measurement taken before the first attempt from one taken after
        // it — the test would pass against the very implementation it exists to forbid.
        accessor.addToView(SESSION, VIEW_ID, "actor-1", 10, 10, 120, 55, false, null, null, null);
        accessor.addToView(SESSION, VIEW_ID, "actor-2", 20, 20, 120, 55, false, null, null, null);
        accessor.addToView(SESSION, VIEW_ID, "actor-3", 30, 30, 120, 55, false, null, null, null);
        accessor.addToView(SESSION, VIEW_ID, "actor-4", 40, 40, 120, 55, false, null, null, null);
        accessor.autoConnectView(SESSION, VIEW_ID, null, null, null, null, null);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    @Test
    public void shouldReportThePreCallRating_measuredAgainstTheViewAsItStood() {
        AssessLayoutResultDto before = accessor.assessLayout(VIEW_ID);

        AutoLayoutAndRouteResultDto dto = runLoop("excellent");

        assertNotNull("a quality-loop run must carry the pre-call rating", dto.ratingBefore());
        assertEquals("the reported pre-call rating must be the one the view actually held",
                before.overallRating(), dto.ratingBefore());
        assertNotEquals("fixture guard: with the two states rating alike, this test could not tell "
                        + "a pre-loop measurement from a post-loop one",
                dto.achievedRating(), dto.ratingBefore());
    }

    @Test
    public void shouldReportThePreCallRating_evenWhenTheTargetWasMet() {
        // Present on every quality-loop path, so a caller never has to interpret its absence — and
        // because meeting a target is "at least as good as", not "better than before".
        AssessLayoutResultDto before = accessor.assessLayout(VIEW_ID);

        AutoLayoutAndRouteResultDto dto = runLoop("fair");

        assertEquals(before.overallRating(), dto.ratingBefore());
    }

    @Test
    public void shouldNotMutateTheViewBeforeMeasuringIt() {
        // The measurement is worthless if it is taken after the first attempt has been applied:
        // the loop would then be comparing itself against itself. Assessing the untouched view
        // twice and comparing against what the run reports is what pins the ordering.
        AssessLayoutResultDto first = accessor.assessLayout(VIEW_ID);
        AssessLayoutResultDto second = accessor.assessLayout(VIEW_ID);
        assertEquals("guard: assessing twice without mutating must be stable",
                first.overallRating(), second.overallRating());

        assertEquals("the pre-call rating must describe the view before any attempt was applied",
                first.overallRating(), runLoop("excellent").ratingBefore());
    }

    @Test
    public void shouldEmitTheRegressionWarningExactlyWhenTheRunActuallyRegressed() {
        // The negative arm runs the SAME code as the positive one. Asserting "no warning" against a
        // stub that could never warn proves nothing; asserting the emitted warning against the
        // predicate applied to the run's own two ratings fails in both directions.
        AssessLayoutResultDto before = accessor.assessLayout(VIEW_ID);

        AutoLayoutAndRouteResultDto dto = runLoop("excellent");

        AssessLayoutResultDto after = accessor.assessLayout(VIEW_ID);
        assertEquals("achievedRating must describe the state the call left applied",
                after.overallRating(), dto.achievedRating());
        assertEquals("the warning must fire exactly when the committed state is worse",
                QualityTargetTermination.hasRatingRegressed(before, after),
                hasRegressionWarning(dto));
    }

    @Test
    public void shouldStaySilentOnARunThatImproved_andSayWhichWayItWent() {
        // The negative control, made to assert something. Its predecessor put the assertions inside
        // a loop over structuredWarnings, so on this fixture — which the loop IMPROVES — the body
        // never ran and the test passed having checked nothing at all. The shape of the warning is
        // pinned exhaustively and non-vacuously beside the pure decision; what belongs HERE is that
        // the real loop stays silent when it improved, and that it demonstrably did improve.
        AssessLayoutResultDto before = accessor.assessLayout(VIEW_ID);

        AutoLayoutAndRouteResultDto dto = runLoop("excellent");

        AssessLayoutResultDto after = accessor.assessLayout(VIEW_ID);
        assertTrue("fixture premise: this run must genuinely improve the view, or the silence below "
                        + "proves nothing — " + dto.ratingBefore() + " -> " + dto.achievedRating(),
                LayoutQualityAssessor.ratingOrdinal(after.overallRating())
                        > LayoutQualityAssessor.ratingOrdinal(before.overallRating()));
        assertTrue("a run that improved must emit no rating-regression warning: "
                        + dto.structuredWarnings(),
                dto.structuredWarnings().stream().noneMatch(
                        w -> StructuredWarningCodes.AUTO_LAYOUT_RATING_REGRESSED.equals(w.code())));
    }

    @Test
    public void shouldOmitThePreCallRatingWhenNoQualityLoopRan() {
        // The single-pass path never assesses, so there is nothing to report and nothing that would
        // read it. Adding a measurement there would be a new cost on the tool's cheapest path.
        AutoLayoutAndRouteResultDto dto = accessor
                .autoLayoutAndRoute(SESSION, VIEW_ID, "flat", "DOWN", 50, null, null).entity();

        assertNull("a single-pass call assesses nothing, so it reports no pre-call rating",
                dto.ratingBefore());
        assertTrue("and emits no rating-regression warning",
                dto.structuredWarnings().isEmpty());
    }

    // ------------------------------------------------------------------
    // Which arm the remedy is true of — driven through the real loops
    //
    // The disclosure is composed by buildQualityTargetDto, which runs BEFORE the facade picks
    // between immediate dispatch, the batch queue and the approval gate, so the applied tail was
    // stamped onto all three. What can only be checked HERE is that each loop hands the rescope
    // the arm it actually took. Both loops are driven separately: they build their own DTO and
    // their own card, and a fix present in one and absent in the other is the failure mode this
    // repo keeps paying for.
    // ------------------------------------------------------------------

    /** The immediate arm's tail, committed rather than re-derived from the constant it pins. */
    private static final String APPLIED_TAIL =
            "The layout and routes were applied anyway — undo restores the previous state, "
                    + "and it is a single call because the winning attempt is committed as one "
                    + "compound operation.";

    @Test
    public void flatLoop_immediateArm_shouldKeepTheAppliedTailAndUndoVerbatim() {
        StructuredWarningDto warning = regressionOf(runFlatRegressing());

        assertTrue("the common path's tail must not drift: " + warning.message(),
                warning.message().endsWith(APPLIED_TAIL));
        assertEquals("undo", warning.remediationTool());
    }

    @Test
    public void groupedLoop_immediateArm_shouldKeepTheAppliedTailAndUndoVerbatim() {
        StructuredWarningDto warning = regressionOf(runGroupedRegressing());

        assertTrue("the common path's tail must not drift: " + warning.message(),
                warning.message().endsWith(APPLIED_TAIL));
        assertEquals("undo", warning.remediationTool());
    }

    @Test
    public void flatLoop_queuedArm_shouldNotClaimTheRunWasAppliedAndShouldNameTheBatchRemedy() {
        // The fixture is laid down BEFORE the arm is opened: with a batch already open every
        // addToView would queue too, and the loop would be handed an empty view.
        String viewId = cleanFlatView();
        dispatcher.beginBatch(SESSION, "arm-b");
        MutationResult<AutoLayoutAndRouteResultDto> result = runFlat(viewId, 50, "fair");

        assertTrue("fixture premise: this call must actually be queued", result.isBatched());
        assertQueuedArm(regressionOf(result.entity()));
    }

    @Test
    public void groupedLoop_queuedArm_shouldNotClaimTheRunWasAppliedAndShouldNameTheBatchRemedy() {
        String viewId = cleanGroupedView();
        dispatcher.beginBatch(SESSION, "arm-b");
        MutationResult<AutoLayoutAndRouteResultDto> result = runGrouped(viewId, 20, "good");

        assertTrue("fixture premise: this call must actually be queued", result.isBatched());
        assertQueuedArm(regressionOf(result.entity()));
    }

    @Test
    public void flatLoop_approvalArm_shouldPutTheEvidenceOnTheCardWithoutTheAppliedClaim() {
        // Same ordering rule as the queued arm: approval mode would propose the fixture too.
        String viewId = cleanFlatView();
        dispatcher.setApprovalModeProvider(() -> true);
        MutationResult<AutoLayoutAndRouteResultDto> result = runFlat(viewId, 50, "fair");

        assertTrue("fixture premise: this call must actually be proposed", result.isProposal());
        assertProposedCard("excellent");
        assertProposedArm(regressionOf(result.entity()));
    }

    @Test
    public void groupedLoop_approvalArm_shouldPutTheEvidenceOnTheCardWithoutTheAppliedClaim() {
        String viewId = cleanGroupedView();
        dispatcher.setApprovalModeProvider(() -> true);
        MutationResult<AutoLayoutAndRouteResultDto> result = runGrouped(viewId, 20, "good");

        assertTrue("fixture premise: this call must actually be proposed", result.isProposal());
        assertProposedCard("excellent");
        assertProposedArm(regressionOf(result.entity()));
    }

    // ---- arm assertions, shared so the two loops are held to the same bar ----

    private static void assertQueuedArm(StructuredWarningDto warning) {
        assertFalse("a queued run must not say it was applied: " + warning.message(),
                warning.message().contains("applied anyway"));
        assertFalse("nor that undo restores it: " + warning.message(),
                warning.message().contains("undo restores the previous state"));
        assertTrue("it must say so outright: " + warning.message(),
                warning.message().contains("Nothing has been applied"));
        assertEquals("an arm that only says 'not undo' leaves the caller with nothing",
                "end-batch", warning.remediationTool());
        assertTrue("and the prose must name the discard: " + warning.message(),
                warning.message().contains("end-batch rollback:true"));
        assertMeasurementSurvived(warning);
    }

    private static void assertProposedArm(StructuredWarningDto warning) {
        assertFalse("a proposal must not say it was applied: " + warning.message(),
                warning.message().contains("applied anyway"));
        assertFalse("nor that undo restores it: " + warning.message(),
                warning.message().contains("undo restores the previous state"));
        assertTrue("it must say so outright: " + warning.message(),
                warning.message().contains("Nothing has been applied"));
        assertEquals("no MCP tool recovers a proposal, so the field must drop out",
                "", warning.remediationTool());
        assertMeasurementSurvived(warning);
    }

    /**
     * The rescope must not become a suppression.
     *
     * <p>Unlike the spacing family — whose control loop has already undone every accepted command,
     * so its {@code after} snapshot re-reads an unmutated view — this loop measures its best
     * attempt inside its own temporary dispatch window, before the queue-or-propose decision. The
     * regression here is genuinely measured on every arm, so dropping the disclosure would delete a
     * fact that was legitimately established. Only the remedy was ever arm-specific.</p>
     */
    private static void assertMeasurementSurvived(StructuredWarningDto warning) {
        assertEquals(StructuredWarningCodes.AUTO_LAYOUT_RATING_REGRESSED, warning.code());
        assertTrue("both ratings must survive the rescope: " + warning.message(),
                warning.message().contains("dropped from 'excellent' to "));
        assertTrue("and the evidence behind them: " + warning.message(),
                warning.message().contains("Worsened: "));
    }

    /** The one channel {@code list-pending-approvals} exposes, read back off the dispatcher. */
    private void assertProposedCard(String expectedRatingBefore) {
        Map<String, Object> card = dispatcher.getPendingProposals(SESSION).stream()
                .filter(p -> "auto-layout-and-route".equals(p.tool()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("no auto-layout-and-route proposal stored"))
                .proposedChanges();

        assertEquals("the pre-call rating is untouched by this story",
                expectedRatingBefore, card.get("ratingBefore"));
        String regression = String.valueOf(card.get("ratingRegression"));
        assertTrue("the card must still carry the evidence, not a bare flag: " + regression,
                regression.contains("Worsened: "));
        assertFalse("but must not describe an unapproved change as applied: " + regression,
                regression.contains("applied anyway"));
        assertFalse("nor prescribe undo to the human reading it: " + regression,
                regression.contains("undo restores the previous state"));
    }

    // ---- fixtures that genuinely regress ----
    //
    // Both are hand-laid to 'excellent' and then handed a target the loop can satisfy with
    // something worse, which is the shipped "meeting a target is AT LEAST AS GOOD AS" case: the
    // loop keeps the best attempt it PRODUCED and never compares it against the view it started
    // from. Measured, not assumed — a fixture whose before and after rate alike cannot
    // discriminate, and one that never regresses makes every assertion above vacuous.

    private AutoLayoutAndRouteResultDto runFlatRegressing() {
        return runFlat(cleanFlatView(), 50, "fair").entity();
    }

    private AutoLayoutAndRouteResultDto runGroupedRegressing() {
        return runGrouped(cleanGroupedView(), 20, "good").entity();
    }

    private MutationResult<AutoLayoutAndRouteResultDto> runFlat(
            String viewId, int spacing, String targetRating) {
        return accessor.autoLayoutAndRoute(SESSION, viewId, "flat", "DOWN", spacing,
                targetRating, null);
    }

    private MutationResult<AutoLayoutAndRouteResultDto> runGrouped(
            String viewId, int spacing, String targetRating) {
        return accessor.autoLayoutAndRoute(SESSION, viewId, "grouped", "DOWN", spacing,
                targetRating, null);
    }

    /** Four actors on orthogonal axes, generously spaced: rates 'excellent' before the call. */
    private String cleanFlatView() {
        String viewId = newView("view-flat-clean");
        place(viewId, "actor-1", 100, 100, null);
        place(viewId, "actor-2", 100, 340, null);
        place(viewId, "actor-3", 100, 580, null);
        place(viewId, "actor-4", 420, 100, null);
        return connectAndRequireExcellent(viewId);
    }

    /** The same four actors in two groups, still 'excellent' before the call. */
    private String cleanGroupedView() {
        String viewId = newView("view-grouped-clean");
        String groupA = accessor.addGroupToView(SESSION, viewId, "A", 60, 60, 260, 400,
                null, null, null).entity().viewObjectId();
        String groupB = accessor.addGroupToView(SESSION, viewId, "B", 460, 60, 260, 400,
                null, null, null).entity().viewObjectId();
        place(viewId, "actor-1", 40, 60, groupA);
        place(viewId, "actor-2", 40, 280, groupA);
        place(viewId, "actor-3", 40, 280, groupB);
        place(viewId, "actor-4", 40, 60, groupB);
        return connectAndRequireExcellent(viewId);
    }

    private String newView(String viewId) {
        IArchimateDiagramModel created = IArchimateFactory.eINSTANCE.createArchimateDiagramModel();
        created.setId(viewId);
        model.getFolder(FolderType.DIAGRAMS).getElements().add(created);
        return viewId;
    }

    private void place(String viewId, String elementId, int x, int y, String parentViewObjectId) {
        accessor.addToView(SESSION, viewId, elementId, x, y, 120, 55, false,
                parentViewObjectId, null, null);
    }

    private String connectAndRequireExcellent(String viewId) {
        accessor.autoConnectView(SESSION, viewId, null, null, null, null, null);
        assertEquals("fixture premise: the view must rate 'excellent' BEFORE the call, or the "
                        + "loop has nothing to regress from and every assertion is vacuous",
                "excellent", accessor.assessLayout(viewId).overallRating());
        return viewId;
    }

    private static StructuredWarningDto regressionOf(AutoLayoutAndRouteResultDto dto) {
        return dto.structuredWarnings().stream()
                .filter(w -> StructuredWarningCodes.AUTO_LAYOUT_RATING_REGRESSED.equals(w.code()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("fixture premise: this run must actually "
                        + "regress — " + dto.ratingBefore() + " -> " + dto.achievedRating()));
    }

    // ---- helpers ----

    private AutoLayoutAndRouteResultDto runLoop(String targetRating) {
        AutoLayoutAndRouteResultDto dto = accessor
                .autoLayoutAndRoute(SESSION, VIEW_ID, "flat", "DOWN", 50, targetRating, null)
                .entity();
        assertNotNull("the loop must return a result", dto);
        return dto;
    }

    private static boolean hasRegressionWarning(AutoLayoutAndRouteResultDto dto) {
        return dto.structuredWarnings().stream().anyMatch(
                w -> StructuredWarningCodes.AUTO_LAYOUT_RATING_REGRESSED.equals(w.code()));
    }

    private void addRelationship(
            IArchimateFactory factory, String id, String sourceId, String targetId) {
        IArchimateRelationship rel = factory.createAssociationRelationship();
        rel.setId(id);
        // Unnamed for the same reason as the elements: a connection label reaches the same SWT
        // text measurement.
        rel.setSource(findActor(sourceId));
        rel.setTarget(findActor(targetId));
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);
    }

    private IBusinessActor findActor(String id) {
        return model.getFolder(FolderType.BUSINESS).getElements().stream()
                .filter(IBusinessActor.class::isInstance)
                .map(IBusinessActor.class::cast)
                .filter(a -> id.equals(a.getId())).findFirst().orElseThrow();
    }
    /** Minimal in-memory model manager — the local idiom the sibling accessor tests use. */
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
