package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;
import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;
import net.vheerden.archi.mcp.response.dto.DetectHubElementsResultDto;

/**
 * Inside a batch, every label reader of a mutating call uses the visibility the batch will commit.
 *
 * <p>A batched call reads a model none of the batch's own commands have run on, so a label the
 * batch is about to show still reads as hidden there, and one it is about to hide still reads as
 * shown. The router already decides by the batch's last queued {@code showLabel} (see
 * {@link RoutingHiddenLabelTest}); these cases hold the Label Offset write, the assessments the call
 * takes, the route-normalized baseline and the layout's label spacing to the same rule.</p>
 *
 * <p>Every batch case is checked against two references taken outside a batch, the label shown and
 * the label hidden in the model, and the two must differ: otherwise a change to code both sides
 * share could move them together and still pass.</p>
 *
 * <p>The relationship is named, so the real collector measures its text: this class realizes SWT
 * and is filed with the display-lane classes.</p>
 */
public class BatchLabelVisibilityReadersTest {

    private static final String SESSION = AutoRouteArmFixture.SESSION;
    private static final String VIEW_ID = AutoRouteArmFixture.VIEW_ID;
    private static final String CONN = "conn-a";

    /** 23 characters: the assessor measures it about 258 px wide, so a Middle label reaches box c. */
    private static final String TEXT = "reads the shared ledger";

    private static final int MIDDLE = 1;
    private static final int EAST = 16;
    private static final int CENTER = RelativePositionFeature.CENTER;

    /*
     * The route is the vertical line x=360 from a (y 0..55) to b (y 600..655). Box c sits on the left at
     * the Middle label's height; boxes d and e sit on the right just past each end, where Archi draws a
     * Source and a Target label (draw2d ConnectionEndpointLocator: 14 px beyond the face, to the right of
     * the line). So every along-path position is hit once and the label stays at Middle; shown, the
     * routing pass offsets it East, which clears c. Unrouted, the Middle label is on c.
     */
    private static final int[][] BLOCKED = {
        {300, 0, 120, 55}, {300, 600, 120, 55}, {430, 30, 150, 70}, {150, 290, 150, 75}, {430, 555, 150, 70},
    };
    private static final String[] HOSTS = {"a", "b", "d", "c", "e"};

    /** Two boxes far below the fixture, joined by an unnamed connection: best-of-K needs two. */
    private static final int[][] FAR_PAIR = {{0, 1500, 60, 40}, {400, 1500, 60, 40}};

    // ==================== the Label Offset write ====================

    @Test
    public void queuedShow_writesTheOffset() {
        assertAnchor("a label the batch will show gets the offset it gets when shown", true,
                routeInBatch(false, Boolean.TRUE));
    }

    @Test
    public void queuedHide_writesNoOffset() {
        assertAnchor("a label the batch will hide gets no offset", false, routeInBatch(true, Boolean.FALSE));
    }

    @Test
    public void batchWithNoQueuedVisibility_offsetReadsTheModel() {
        assertAnchor("hidden in the model, nothing queued", false, routeInBatch(false));
        assertAnchor("shown in the model, nothing queued", true, routeInBatch(true));
    }

    @Test
    public void lastQueuedVisibility_decidesTheOffset() {
        assertAnchor("hide then show: offset", true, routeInBatch(false, Boolean.FALSE, Boolean.TRUE));
        assertAnchor("show then hide: none", false, routeInBatch(true, Boolean.TRUE, Boolean.FALSE));
    }

    @Test
    public void queuedShow_isCreditedItsOffsetWhileCandidatesAreScored() throws Exception {
        Set<Integer> shown = scoredAnchors(true, null);
        Set<Integer> hidden = scoredAnchors(false, null);
        assertTrue("control: shown in the model, a candidate is scored with the offset: " + shown,
                shown.contains(EAST));
        assertFalse("control: hidden in the model, none is: " + hidden, hidden.contains(EAST));

        Set<Integer> batched = scoredAnchors(false, Boolean.TRUE);
        assertTrue("a label the batch will show is scored with the offset it will get: " + batched,
                batched.contains(EAST));
        Set<Integer> hiddenByBatch = scoredAnchors(true, Boolean.FALSE);
        assertFalse("a label the batch will hide is scored with none: " + hiddenByBatch, hiddenByBatch.contains(EAST));
    }

    // ==================== the assessments the call takes ====================

    @Test
    public void assessmentInACall_judgesTheLabelAsTheBatchLeavesIt() {
        int shown = labelOverlaps(true, false, null);
        int hidden = labelOverlaps(false, false, null);
        assertNotEquals("control: the shown label overlaps, the hidden one does not", shown, hidden);

        assertEquals("a label the batch will show is assessed as shown", shown, labelOverlaps(false, true, Boolean.TRUE));
        assertEquals("a label the batch will hide is assessed as hidden", hidden, labelOverlaps(true, true, Boolean.FALSE));
        assertEquals("nothing queued: the model's value", hidden, labelOverlaps(false, true, null));
        assertEquals("nothing queued: the model's value", shown, labelOverlaps(true, true, null));
    }

    @Test
    public void assessmentOutsideAnAdmittedCall_readsTheModel() {
        // A read-only tool is not admitted, so inside an open batch it still describes the model.
        assertEquals(labelOverlaps(false, false, null), labelOverlaps(false, false, Boolean.TRUE));
    }

    @Test
    public void routeNormalizedBaseline_assessesTheLabelItsRouteUsed() throws Exception {
        assertEquals("control: shown in the model", TEXT, baselineLabelText(true, null));
        assertEquals("control: hidden in the model", "", baselineLabelText(false, null));

        assertEquals("a label the batch will show is assessed with its text", TEXT,
                baselineLabelText(false, Boolean.TRUE));
        assertEquals("a label the batch will hide is assessed as no label", "",
                baselineLabelText(true, Boolean.FALSE));
    }

    // ==================== hub sizing ====================

    /**
     * {@code detect-hub-elements} sizes a hub from the labels Archi shows: a hidden label reserves nothing,
     * so the published {@code maxLabelWidth} and the label term of the width suggestion drop to the widest
     * label still shown. The hub has 7 connections (one over the large-hub count), so the connection-based width is
     * 100 + 15 = 115; its labels are {@link #TEXT} (23 characters, 194 px reserved) and six of "a" (18 px).
     */
    @Test
    public void detectHubElements_sizesTheHubFromTheLabelsItShows() {
        String shown = "Element 'Hub' has 7 connections (large hub: > 6). Consider increasing height to 75px "
                + "(60 + 15 × 1) for horizontal layouts, or width to 294px (100 + longest label 194px) for vertical "
                + "layouts.";
        String hidden = "Element 'Hub' has 7 connections (large hub: > 6). Consider increasing height to 75px "
                + "(60 + 15 × 1) for horizontal layouts, or width to 118px (100 + longest label 18px) for vertical "
                + "layouts.";

        DetectHubElementsResultDto shownResult = detectHub(true, false, null);
        assertEquals(194, shownResult.elements().get(0).maxLabelWidth());
        assertEquals(List.of(shown), shownResult.suggestions());

        DetectHubElementsResultDto hiddenResult = detectHub(false, false, null);
        assertEquals("a hidden label reserves nothing", 18, hiddenResult.elements().get(0).maxLabelWidth());
        assertEquals(List.of(hidden), hiddenResult.suggestions());

        assertEquals("inside an admitted call, a label the batch will hide reserves nothing", 18,
                detectHub(true, true, Boolean.FALSE).elements().get(0).maxLabelWidth());
        assertEquals("and one it will show reserves again", 194,
                detectHub(false, true, Boolean.TRUE).elements().get(0).maxLabelWidth());
        // The read tool is not admitted, so inside an open batch it describes the model, as assess-layout does.
        assertEquals("the read tool in a batch describes the model", 194,
                detectHub(true, false, Boolean.FALSE).elements().get(0).maxLabelWidth());
    }

    /**
     * {@code detectHubElements} over a 100 x 60 hub with 7 spokes; with {@code queued} non-null, inside a
     * batch that queued that {@code showLabel} for the {@link #TEXT} connection, and admitted for the session
     * when {@code admitted}.
     */
    private DetectHubElementsResultDto detectHub(boolean shownInModel, boolean admitted, Boolean queued) {
        AutoRouteArmFixture.Ctx c = AutoRouteArmFixture.newView();
        AutoRouteArmFixture.addHost(c, "hub", 400, 400, 100, 60);
        ((com.archimatetool.model.IDiagramModelArchimateObject) c.view.getChildren().get(0))
                .getArchimateElement().setName("Hub");
        for (int i = 0; i < 7; i++) {
            AutoRouteArmFixture.addHost(c, "s" + i, i * 150, 0, 100, 60);
            AutoRouteArmFixture.connect(c, "conn-" + i, "rel-" + i, "obj-hub-n", "obj-s" + i + "-n");
        }
        for (IDiagramModelConnection conn : c.view.getChildren().get(0).getSourceConnections()) {
            boolean wide = "conn-0".equals(conn.getId());
            ((IDiagramModelArchimateConnection) conn).getArchimateRelationship().setName(wide ? TEXT : "a");
            conn.setNameVisible(!wide || shownInModel);
        }
        Harness h = new Harness(c);
        try {
            if (queued != null) {
                h.dispatcher.beginBatch(SESSION, "label visibility then hubs");
                h.accessor.updateViewConnection(SESSION, "conn-0", null, null, null, queued, null);
            }
            h.dispatcher.endCall();
            if (admitted) {
                h.dispatcher.admit(SESSION, "apply-element-spacing-recommendations", c.model);
            }
            return h.accessor.detectHubElements(VIEW_ID);
        } finally {
            h.dispatcher.endCall();
            h.accessor.dispose();
        }
    }

    // ==================== the label fallback pass ====================

    @Test
    public void labelFallbackPass_optimizesTheLabelsTheBatchWillShow() {
        assertNotNull("control: shown in the model, the pass optimizes the label",
                LabelOptimizationPass.compute(build(true).view, 10, null));
        assertNull("control: hidden in the model, it skips it",
                LabelOptimizationPass.compute(build(false).view, 10, null));

        AutoRouteArmFixture.Ctx shownInModel = build(true);
        AutoRouteArmFixture.Ctx shownByBatch = build(false);
        String reference = applied(shownInModel, LabelOptimizationPass.compute(shownInModel.view, 10, null));
        assertEquals("a label the batch will show is optimized as it is when shown in the model", reference,
                applied(shownByBatch, LabelOptimizationPass.compute(shownByBatch.view, 10, Map.of(CONN, true))));
        assertEquals("control: the pass keeps the label at Middle and offsets it East", "position 1, anchor 16",
                reference);
        assertNull("a label the batch will hide is skipped",
                LabelOptimizationPass.compute(build(true).view, 10, Map.of(CONN, false)));
    }

    @Test
    public void labelFallbackInACall_optimizesALabelTheBatchWillShow() throws Exception {
        assertTrue("control: shown in the model, the fallback keeps an offset for it", fallbackOffsets(true, null));
        assertTrue("a label the batch will show gets the same", fallbackOffsets(false, Boolean.TRUE));
        assertFalse("control: hidden in the model, it keeps none", fallbackOffsets(false, null));
        assertFalse("a label the batch will hide gets none", fallbackOffsets(true, Boolean.FALSE));
    }

    @Test
    public void labelFallbackThatDoesNotImprove_keepsNoOffset() throws Exception {
        assertTrue("control: measured against the view as it is, the fallback improves and keeps the offset",
                fallbackOffsets(true, null, false));
        assertFalse("measured against a best layout it cannot beat, it keeps nothing, the offset included",
                fallbackOffsets(true, null, true));
    }

    /**
     * Runs {@code auto-layout-and-route}'s label fallback over the unrouted {@link #BLOCKED} view,
     * admitted for the session; with {@code queued} non-null, inside a batch that queued that
     * {@code showLabel}. The fallback is handed the assessment the call takes of the view, so it fires
     * on the label overlap. Returns whether it merged an offset for {@link #CONN} into the best layout.
     */
    private boolean fallbackOffsets(boolean shownInModel, Boolean queued) throws Exception {
        return fallbackOffsets(shownInModel, queued, false);
    }

    /**
     * {@link #fallbackOffsets(boolean, Boolean)}; with {@code unbeatable} the fallback is told the best layout is
     * already rated excellent with a tier-weighted score of 0, which no re-assessment improves on.
     */
    private boolean fallbackOffsets(boolean shownInModel, Boolean queued, boolean unbeatable) throws Exception {
        AutoRouteArmFixture.Ctx c = build(shownInModel);
        requireOffsetFeature(c);
        AutoRouteArmFixture.StubEditorModelManager mgr = new AutoRouteArmFixture.StubEditorModelManager();
        mgr.setModels(List.of(c.model));
        org.eclipse.gef.commands.CommandStack stack = new org.eclipse.gef.commands.CommandStack();
        MutationDispatcher dispatcher = new MutationDispatcher(() -> c.model) {
            @Override
            public void dispatchImmediate(org.eclipse.gef.commands.Command command) {
                stack.execute(plain(command));
            }
            @Override
            protected void dispatchCommand(org.eclipse.gef.commands.Command command) {
                stack.execute(plain(command));
            }
            @Override
            public UndoRedoState undo(int steps) {
                for (int i = 0; i < steps && stack.canUndo(); i++) {
                    stack.undo();
                }
                return null;
            }
            private org.eclipse.gef.commands.Command plain(org.eclipse.gef.commands.Command command) {
                if (command instanceof org.eclipse.gef.commands.CompoundCommand compound) {
                    org.eclipse.gef.commands.CompoundCommand plain =
                            new org.eclipse.gef.commands.CompoundCommand(compound.getLabel());
                    for (Object child : compound.getCommands()) {
                        plain.add(plain((org.eclipse.gef.commands.Command) child));
                    }
                    return plain;
                }
                return command;
            }
        };
        dispatcher.setApprovalModeProvider(() -> false);
        ArchiModelAccessorImpl accessor = new ArchiModelAccessorImpl(mgr, dispatcher);
        try {
            if (queued != null) {
                dispatcher.beginBatch(SESSION, "label visibility then layout");
                accessor.updateViewConnection(SESSION, CONN, null, null, null, queued, null);
            }
            dispatcher.admit(SESSION, "auto-layout-and-route", c.model);
            AssessLayoutResultDto best = accessor.assessLayout(c.view, VIEW_ID, false);
            boolean shownAtCommit = queued != null ? queued : shownInModel;
            assertEquals("the call sees the label overlap only when the label is shown", shownAtCommit ? 1 : 0,
                    best.labelOverlapCount());
            com.archimatetool.editor.model.commands.NonNotifyingCompoundCommand bestCompound =
                    new com.archimatetool.editor.model.commands.NonNotifyingCompoundCommand("best layout");
            bestCompound.add(new SetTextPositionCommand(conn(c), MIDDLE));
            Method fallback = ArchiModelAccessorImpl.class.getDeclaredMethod("executeLabelFallback",
                    com.archimatetool.editor.model.commands.NonNotifyingCompoundCommand.class, String.class,
                    int.class, int.class, AssessLayoutResultDto.class, String.class, String.class,
                    com.archimatetool.model.IArchimateDiagramModel.class,
                    com.archimatetool.model.IArchimateModel.class);
            fallback.setAccessible(true);
            Object result = fallback.invoke(accessor, bestCompound, unbeatable ? "excellent" : best.overallRating(),
                    unbeatable ? 0 : QualityTargetTermination.tierWeightedScore(best), 0, best, "excellent", VIEW_ID,
                    c.view, c.model);
            assertEquals("the trial was undone", CENTER, RelativePositionFeature.get(conn(c)));
            if (result == null) {
                return false;
            }
            for (Object command : bestCompound.getCommands()) {
                if (command instanceof SetTextRelativePositionCommand set && CONN.equals(set.getConnection().getId())) {
                    return true;
                }
            }
            return false;
        } finally {
            dispatcher.endCall();
            accessor.dispose();
        }
    }

    /** Runs the pass's commands on {@code c}; returns the label state {@link #CONN} is left in. */
    private static String applied(AutoRouteArmFixture.Ctx c, LabelOptimizationPass.Result result) {
        assertNotNull("the pass found something to change", result);
        for (Object command : result.compound.getCommands()) {
            ((org.eclipse.gef.commands.Command) command).execute();
        }
        return "position " + conn(c).getTextPosition() + ", anchor " + RelativePositionFeature.get(conn(c));
    }

    // ==================== the layout's label spacing ====================

    @Test
    public void layoutEdges_reserveTheLabelAsTheBatchLeavesIt() throws Exception {
        int shown = edgeLabelWidth(true, null);
        assertTrue("control: a shown label reserves width", shown > 0);
        assertEquals("control: a hidden label reserves none", 0, edgeLabelWidth(false, null));

        assertEquals("a label the batch will show reserves its width", shown, edgeLabelWidth(false, Boolean.TRUE));
        assertEquals("a label the batch will hide reserves none", 0, edgeLabelWidth(true, Boolean.FALSE));
    }

    // ==================== driving ====================

    /** {@code batched} holds the anchor the label gets when shown if {@code offset}, else the one when hidden. */
    private void assertAnchor(String message, boolean offset, int batched) {
        int shown = routeOutside(true);
        int hidden = routeOutside(false);
        assertEquals("control: shown, the pass offsets the label East", EAST, shown);
        assertEquals("control: hidden, the pass leaves it at Centre", CENTER, hidden);
        assertEquals(message, offset ? shown : hidden, batched);
    }

    /** Routes {@link #BLOCKED} outside a batch; returns the anchor the label holds after. */
    private int routeOutside(boolean shown) {
        AutoRouteArmFixture.Ctx c = build(shown);
        requireOffsetFeature(c);
        AutoRouteArmFixture.route(c, AutoRouteArmFixture.Arm.APPLIED);
        return RelativePositionFeature.get(conn(c));
    }

    /**
     * Opens a batch on {@link #BLOCKED} with the label {@code shownInModel}, queues one
     * {@code update-view-connection showLabel} per entry of {@code queued}, queues the route, commits.
     * Returns the anchor the label holds after the commit.
     */
    private int routeInBatch(boolean shownInModel, Boolean... queued) {
        AutoRouteArmFixture.Ctx c = build(shownInModel);
        requireOffsetFeature(c);
        Harness h = new Harness(c);
        try {
            h.dispatcher.beginBatch(SESSION, "label visibility then route");
            for (Boolean show : queued) {
                h.accessor.updateViewConnection(SESSION, CONN, null, null, null, show, null);
            }
            MutationResult<AutoRouteResultDto> result = h.accessor.autoRouteConnections(SESSION, VIEW_ID, null,
                    "orthogonal", false, false, 0, 0, null);
            assertTrue("the route is queued, not refused", result.isBatched());
            assertEquals("nothing applied before commit", CENTER, RelativePositionFeature.get(conn(c)));
            h.dispatcher.endBatch(SESSION, true);
        } finally {
            h.accessor.dispose();
        }
        assertEquals("the queued visibility was committed", queued.length == 0 ? shownInModel
                : queued[queued.length - 1], conn(c).isNameVisible());
        return RelativePositionFeature.get(conn(c));
    }

    /**
     * The anchors best-of-K scored {@link #CONN} with while routing {@link #BLOCKED} plus
     * {@link #FAR_PAIR}; with {@code queued} non-null, inside a batch that queued that {@code showLabel}.
     */
    private Set<Integer> scoredAnchors(boolean shownInModel, Boolean queued) throws Exception {
        AutoRouteArmFixture.Ctx c = build(shownInModel);
        requireOffsetFeature(c);
        for (int i = 0; i < FAR_PAIR.length; i++) {
            int[] b = FAR_PAIR[i];
            AutoRouteArmFixture.addHost(c, "f" + i, b[0], b[1], b[2], b[3]);
        }
        AutoRouteArmFixture.connect(c, "conn-f", "rel-f", "obj-f0-n", "obj-f1-n");
        Harness h = new Harness(c);
        RecordingAssessor recorder = h.record();
        try {
            if (queued != null) {
                h.dispatcher.beginBatch(SESSION, "label visibility then route");
                h.accessor.updateViewConnection(SESSION, CONN, null, null, null, queued, null);
            }
            h.accessor.autoRouteConnections(SESSION, VIEW_ID, null, "orthogonal", false, false, 0, 0, null);
        } finally {
            h.accessor.dispose();
        }
        assertFalse("best-of-K scored at least one candidate", recorder.scoredAnchors.isEmpty());
        return new HashSet<>(recorder.scoredAnchors);
    }

    /**
     * {@code labelOverlapCount} of the unrouted {@link #BLOCKED} view, assessed the way a call
     * assesses its view: with {@code admitted}, while a mutating call is admitted for the session, and
     * with {@code queued} non-null, inside a batch that queued that {@code showLabel}.
     */
    private int labelOverlaps(boolean shownInModel, boolean admitted, Boolean queued) {
        AutoRouteArmFixture.Ctx c = build(shownInModel);
        Harness h = new Harness(c);
        try {
            if (queued != null) {
                h.dispatcher.beginBatch(SESSION, "label visibility then assess");
                h.accessor.updateViewConnection(SESSION, CONN, null, null, null, queued, null);
            }
            h.dispatcher.endCall();
            if (admitted) {
                h.dispatcher.admit(SESSION, "auto-layout-and-route", c.model);
            }
            return h.accessor.assessLayout(c.view, VIEW_ID, false).labelOverlapCount();
        } finally {
            h.dispatcher.endCall();
            h.accessor.dispose();
        }
    }

    /**
     * The label text the route-normalized baseline's own assessment saw for {@link #CONN}, admitted
     * for the session as the spacing tools admit their call; with {@code queued} non-null, inside a
     * batch that queued that {@code showLabel}.
     */
    private String baselineLabelText(boolean shownInModel, Boolean queued) throws Exception {
        AutoRouteArmFixture.Ctx c = build(shownInModel);
        Harness h = new Harness(c);
        try {
            AssessLayoutResultDto bare = h.accessor.assessLayout(VIEW_ID);
            RecordingAssessor recorder = h.record();
            if (queued != null) {
                h.dispatcher.beginBatch(SESSION, "label visibility then spacing");
                h.accessor.updateViewConnection(SESSION, CONN, null, null, null, queued, null);
            }
            h.dispatcher.admit(SESSION, "apply-element-spacing-recommendations", c.model);
            Method baseline = ArchiModelAccessorImpl.class.getDeclaredMethod("routeNormalizedBaseline",
                    String.class, com.archimatetool.model.IArchimateModel.class, AssessLayoutResultDto.class);
            baseline.setAccessible(true);
            assertNotNull(baseline.invoke(h.accessor, VIEW_ID, c.model, bare));
            // The baseline assesses a copy of the view, so a text recorded for the id proves the copy kept it.
            assertFalse("the baseline's assessment saw the connection by its id", recorder.assessedText.isEmpty());
            assertEquals("the live view was not routed", 0, conn(c).getBendpoints().size());
            return recorder.assessedText.get(recorder.assessedText.size() - 1);
        } finally {
            h.dispatcher.endCall();
            h.accessor.dispose();
        }
    }

    /**
     * The label width the layout reserves for {@link #CONN}'s edge, collected while a mutating call is
     * admitted for the session; with {@code queued} non-null, inside a batch that queued that {@code showLabel}.
     */
    private int edgeLabelWidth(boolean shownInModel, Boolean queued) throws Exception {
        AutoRouteArmFixture.Ctx c = build(shownInModel);
        Harness h = new Harness(c);
        try {
            if (queued != null) {
                h.dispatcher.beginBatch(SESSION, "label visibility then layout");
                h.accessor.updateViewConnection(SESSION, CONN, null, null, null, queued, null);
            }
            h.dispatcher.admit(SESSION, "auto-layout-and-route", c.model);
            List<LayoutNode> nodes = new ArrayList<>();
            for (IDiagramModelObject child : c.view.getChildren()) {
                nodes.add(new LayoutNode(child.getId(), 0, 0, 0, 0, null));
            }
            Method collect = ArchiModelAccessorImpl.class.getDeclaredMethod("collectLayoutEdgesRecursive",
                    com.archimatetool.model.IArchimateDiagramModel.class, List.class);
            collect.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<LayoutEdge> edges = (List<LayoutEdge>) collect.invoke(h.accessor, c.view, nodes);
            for (LayoutEdge edge : edges) {
                if (CONN.equals(edge.connectionId())) {
                    return edge.labelWidth();
                }
            }
            throw new AssertionError("no layout edge for " + CONN + ": " + edges);
        } finally {
            h.dispatcher.endCall();
            h.accessor.dispose();
        }
    }

    /** Records what the assessor is handed for {@link #CONN}: candidate anchors, and every assessment's text. */
    private static final class RecordingAssessor extends LayoutQualityAssessor {
        final List<Integer> scoredAnchors = new ArrayList<>();
        final List<String> assessedText = new ArrayList<>();

        // The four-argument form: every assessment funnels through it (assess-layout calls it with
        // the view's viewpoint verdict; the three-argument form delegates to it).
        @Override
        LayoutAssessmentResult assess(List<AssessmentNode> nodes, List<AssessmentConnection> connections,
                boolean includeViolatorIds, ViewpointConformance.Verdict viewpoint) {
            for (AssessmentConnection ac : connections) {
                if (CONN.equals(ac.id())) {
                    assessedText.add(ac.labelText());
                    if (!includeViolatorIds) {
                        scoredAnchors.add(ac.relativePosition());
                    }
                }
            }
            return super.assess(nodes, connections, includeViolatorIds, viewpoint);
        }
    }

    private static final class Harness {
        final MutationDispatcher dispatcher;
        final ArchiModelAccessorImpl accessor;

        Harness(AutoRouteArmFixture.Ctx c) {
            AutoRouteArmFixture.StubEditorModelManager mgr = new AutoRouteArmFixture.StubEditorModelManager();
            mgr.setModels(List.of(c.model));
            dispatcher = AutoRouteArmFixture.newDispatcher(c.model);
            dispatcher.setApprovalModeProvider(() -> false);
            accessor = new ArchiModelAccessorImpl(mgr, dispatcher);
        }

        RecordingAssessor record() throws Exception {
            RecordingAssessor recorder = new RecordingAssessor();
            Field field = ArchiModelAccessorImpl.class.getDeclaredField("layoutQualityAssessor");
            field.setAccessible(true);
            field.set(accessor, recorder);
            return recorder;
        }
    }

    // ==================== fixtures ====================

    private static AutoRouteArmFixture.Ctx build(boolean shown) {
        AutoRouteArmFixture.Ctx c = AutoRouteArmFixture.newView();
        for (int i = 0; i < BLOCKED.length; i++) {
            int[] b = BLOCKED[i];
            AutoRouteArmFixture.addHost(c, HOSTS[i], b[0], b[1], b[2], b[3]);
        }
        AutoRouteArmFixture.connect(c, CONN, "rel-a", "obj-a-n", "obj-b-n");
        IDiagramModelArchimateConnection conn = conn(c);
        conn.getArchimateRelationship().setName(TEXT);
        conn.setNameVisible(shown);
        conn.setTextPosition(MIDDLE);
        RelativePositionFeature.set(conn, CENTER);
        return c;
    }

    private static void requireOffsetFeature(AutoRouteArmFixture.Ctx c) {
        assumeTrue("Label-offset feature is only present on newer platforms",
                RelativePositionFeature.isSupported(conn(c)));
    }

    private static IDiagramModelArchimateConnection conn(AutoRouteArmFixture.Ctx c) {
        for (IDiagramModelObject child : c.view.getChildren()) {
            for (IDiagramModelConnection conn : child.getSourceConnections()) {
                if (CONN.equals(conn.getId())) {
                    return (IDiagramModelArchimateConnection) conn;
                }
            }
        }
        throw new AssertionError("no connection " + CONN);
    }
}
