package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.Test;

import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IDiagramModelConnection;

import net.vheerden.archi.mcp.model.routing.LabelPolicy;
import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.model.routing.RoutingResult;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;

/**
 * A connection whose label is hidden ({@code showLabel: false}) is routed as an unlabelled one.
 *
 * <p>Archi draws nothing for a hidden label, so the router must not shift a path to clear it, give
 * it an along-path position, count it in {@code labelsOptimized}, let it push a visible label
 * aside, or score it while choosing a route. Each case compares a hidden label against the same
 * connection with no name at all: equality there proves the hidden text reaches nothing, not just
 * that one counter moved. Each also carries a control showing the same label, so a blank applied
 * regardless of visibility fails too.</p>
 *
 * <p>Inside an open batch the routing pass reads a model none of the batch's own commands have run
 * on, so the visibility it acts on is the batch's last queued {@code showLabel} for the connection,
 * else the model's.</p>
 *
 * <p>The relationships are named, so the real collector measures their text: this class realizes
 * SWT and is filed with the display-lane classes.</p>
 */
public class RoutingHiddenLabelTest {

    private static final String SESSION = AutoRouteArmFixture.SESSION;
    private static final String VIEW_ID = AutoRouteArmFixture.VIEW_ID;
    private static final String CONN = "conn-h";
    private static final String NEIGHBOUR = "conn-v";

    /** 57 characters: the router sizes it 57 * 8 + 10 = 466 px wide, far past every box below. */
    private static final String TEXT = "settles the quarterly ledger with the clearing house now";
    private static final String NEIGHBOUR_TEXT = "carries the nightly batch";

    private static final int SOURCE = 0;
    private static final int MIDDLE = 1;

    /*
     * Five boxes. Unnamed, the connection leaves h0 to the left and drops straight down to h1. Named,
     * its Middle label sits on h3, so the clearance pass moves the path to the left of h3 and down a
     * shorter corridor. The optimizer leaves the label at Middle either way.
     */
    private static final int[][] CLEARANCE = {
        {750, 0, 120, 55}, {400, 350, 120, 40}, {200, 400, 60, 55}, {350, 50, 120, 55}, {750, 250, 120, 55},
    };

    /*
     * Three boxes. A straight vertical line from h0 down to h1, drawn from y 55 to y 600, so Archi
     * centres the Middle label on (560, 327). Named, that label (x 247..873) lies on h2, a box beside the
     * line, so the optimizer moves it to Source (y 107..127, clear of h2).
     */
    private static final int[][] PLACED = {
        {500, 0, 120, 55}, {500, 600, 120, 55}, {700, 310, 60, 40},
    };

    /*
     * Two connections, h0 to h1 and h2 to h3, whose routes do not depend on either label: a 1728 px
     * vertical line at x 930 and a 1440 px horizontal one at y 327. The vertical line passes clear of the
     * second's Middle label (x 640..920), and Archi centres the first's Middle label on (930, 319), 8 px
     * above the horizontal line, so neither label lies on the other's line. With the first unnamed, the
     * second label stays at Middle; with the first named, its label (x 623..1238, y 309..329) is in the
     * way, and the second, the shorter route and so placed after it, moves to Source (x 74..354).
     */
    private static final int[][] NEIGHBOURS = {
        {870, -600, 120, 55}, {870, 1183, 120, 55}, {0, 300, 60, 55}, {1500, 300, 60, 55},
    };

    /*
     * A straight vertical connection, h0 above h1, drawn from y 55 to y 600 at x 360, with two boxes on
     * each label Archi draws: the Middle label centred on (360, 327) (x 47..673, y 317..337), and the
     * Source and Target labels to the right of the line just beyond each end (x 364..990, y 69..89 and
     * y 566..586). So every position lands on two boxes; a straight route has no segment the clearance
     * pass can shift.
     */
    private static final int[][] BLOCKED = {
        {300, 0, 120, 55}, {300, 600, 120, 55},
        {150, 290, 100, 75}, {470, 290, 100, 75},
        {450, 60, 100, 40}, {700, 60, 100, 40},
        {450, 555, 100, 40}, {700, 555, 100, 40},
    };

    /** Two boxes far below every fixture, joined by an unnamed connection: best-of-K needs two. */
    private static final int[][] FAR_PAIR = {{0, 1500, 60, 40}, {400, 1500, 60, 40}};

    // ==================== the route ====================

    @Test
    public void hiddenLabel_routesAsTheUnnamedConnection() {
        Run none = route(CLEARANCE, "", true);
        Run shown = route(CLEARANCE, TEXT, true);
        Run hidden = route(CLEARANCE, TEXT, false);

        assertEquals("a hidden label is routed as no label: " + hidden + " vs " + none,
                none.bendpoints(), hidden.bendpoints());
        assertNotEquals("control: a shown label still moves the route", none.bendpoints(), shown.bendpoints());
        assertEquals("the hidden label keeps its position", MIDDLE, hidden.textPosition());
    }

    // ==================== no position written, none counted ====================

    @Test
    public void hiddenLabel_getsNoPosition_andIsNotCounted_byAutoRoute() {
        AutoRouteArmFixture.Ctx c = placedPair();

        AutoRouteResultDto dto = AutoRouteArmFixture.route(c, AutoRouteArmFixture.Arm.APPLIED);

        assertEquals("the hidden label keeps its position", MIDDLE, conn(c, CONN).getTextPosition());
        assertEquals("control: the shown label in the same view is moved", SOURCE,
                conn(c, NEIGHBOUR).getTextPosition());
        assertEquals("labelsOptimized counts only the shown label", 1, dto.labelsOptimized());
    }

    @Test
    public void hiddenLabel_getsNoPosition_andIsNotCounted_byTheAutoLayoutRoutingPass() {
        AutoRouteArmFixture.Ctx c = placedPair();
        ArchiModelAccessorImpl accessor = newAccessor(c);
        AutoRoutePassResult pass;
        try {
            pass = accessor.computeAutoRoutePass(VIEW_ID, c.view, c.model, LabelPolicy.KEEP);
        } finally {
            accessor.dispose();
        }

        assertNotNull(pass);
        List<String> positioned = textPositionTargets(pass.compound);
        assertFalse("no text-position command for the hidden label: " + positioned, positioned.contains(CONN));
        assertTrue("control: one for the shown label: " + positioned, positioned.contains(NEIGHBOUR));
        assertEquals("labelsOptimized counts only the shown label", 1, pass.labelsOptimized);
        executeLeaves(pass.compound);
        assertEquals("the hidden label keeps its position", MIDDLE, conn(c, CONN).getTextPosition());
        assertEquals(SOURCE, conn(c, NEIGHBOUR).getTextPosition());
    }

    // ==================== no visible label displaced ====================

    @Test
    public void hiddenLabel_displacesNoVisibleLabel() {
        int unnamed = neighbourPosition("", true);
        int hidden = neighbourPosition(TEXT, false);
        int shown = neighbourPosition(TEXT, true);

        assertEquals("with no neighbouring label the visible one stays at Middle", MIDDLE, unnamed);
        assertEquals("a hidden neighbour is no neighbour", unnamed, hidden);
        assertEquals("control: a shown neighbour pushes the visible label to Source", SOURCE, shown);
    }

    // ==================== scored before apply as it is after ====================

    @Test
    public void hiddenLabel_isScoredBeforeApplyAsItIsAfter() throws Exception {
        AutoRouteArmFixture.Ctx c = build(BLOCKED, TEXT, false);
        for (int i = 0; i < FAR_PAIR.length; i++) {
            int[] b = FAR_PAIR[i];
            AutoRouteArmFixture.addHost(c, "f" + i, b[0], b[1], b[2], b[3]);
        }
        connect(c, NEIGHBOUR, "f0", "f1", "", true);
        int textBefore = conn(c, CONN).getTextPosition();
        List<AssessmentNode> nodesBefore = AssessmentCollector.collectAssessmentNodes(c.view);

        RecordingAssessor recorder = new RecordingAssessor();
        ArchiModelAccessorImpl accessor = newAccessor(c);
        try {
            Field field = ArchiModelAccessorImpl.class.getDeclaredField("layoutQualityAssessor");
            field.setAccessible(true);
            field.set(accessor, recorder);
            accessor.autoRouteConnections(SESSION, VIEW_ID, null, "orthogonal", false, false, 0, 0, null);
        } finally {
            accessor.dispose();
        }

        // What best-of-K scored: the label text every candidate carried for this connection.
        assertFalse("best-of-K scored at least one candidate", recorder.scoredText.isEmpty());
        assertEquals("every candidate is scored with no label for the hidden connection: " + recorder.scoredText,
                Set.of(""), new HashSet<>(recorder.scoredText));

        // The applied route projected the way the scorer projects a candidate, with the label text the
        // scorer saw, against assess-layout's reading of the result.
        List<AssessmentNode> nodes = AssessmentCollector.collectAssessmentNodes(c.view);
        List<AssessmentConnection> collected = AssessmentCollector.collectAssessmentConnections(c.view, nodes);
        LayoutQualityAssessor assessor = new LayoutQualityAssessor();
        LayoutAssessmentResult post = assessor.assess(nodes, collected, true);
        Map<String, List<AbsoluteBendpointDto>> routed = new LinkedHashMap<>();
        List<RoutingPipeline.ConnectionEndpoints> batchInput = new ArrayList<>();
        for (AssessmentConnection applied : collected) {
            IDiagramModelArchimateConnection conn = conn(c, applied.id());
            List<AbsoluteBendpointDto> bendpoints = new ArrayList<>();
            for (int i = 1; i < applied.pathPoints().size() - 1; i++) {
                double[] p = applied.pathPoints().get(i);
                bendpoints.add(new AbsoluteBendpointDto((int) Math.round(p[0]), (int) Math.round(p[1])));
            }
            routed.put(applied.id(), bendpoints);
            String scored = CONN.equals(applied.id()) ? recorder.scoredText.get(0) : "";
            int textPosition = CONN.equals(applied.id()) ? textBefore : conn.getTextPosition();
            batchInput.add(new RoutingPipeline.ConnectionEndpoints(applied.id(),
                    rect(nodesBefore, conn.getSource().getId()), rect(nodesBefore, conn.getTarget().getId()),
                    List.of(), scored, textPosition, List.of(), RelativePositionFeature.get(conn)));
        }
        RoutingResult winner = new RoutingResult(routed, List.of(), List.of(), Map.of(), 0, Map.of(), 0);
        RoutedAssessmentPaths.LabelInputs labels = new RoutedAssessmentPaths.LabelInputs(Set.of());
        LayoutAssessmentResult pre = assessor.assess(nodes,
                RoutedAssessmentPaths.overlay(batchInput, winner, labels), true);

        assertEquals("pre-apply label findings must equal post-apply ones", post.labelOverlaps(), pre.labelOverlaps());
        assertEquals(0, post.labelOverlapCount());
    }

    /** Records the label text of {@link #CONN} in every candidate the best-of-K scorer assesses. */
    private static final class RecordingAssessor extends LayoutQualityAssessor {
        final List<String> scoredText = new ArrayList<>();

        @Override
        LayoutAssessmentResult assess(List<AssessmentNode> nodes, List<AssessmentConnection> connections,
                boolean includeViolatorIds) {
            if (!includeViolatorIds) {
                for (AssessmentConnection ac : connections) {
                    if (CONN.equals(ac.id())) {
                        scoredText.add(ac.labelText());
                    }
                }
            }
            return super.assess(nodes, connections, includeViolatorIds);
        }
    }

    // ==================== inside a batch ====================

    @Test
    public void queuedShow_isRoutedAsLabelled() {
        assertRoutedAs("a label the batch will show is routed as shown", true, routeInBatch(false, Boolean.TRUE));
    }

    @Test
    public void queuedHide_isRoutedAsUnlabelled() {
        assertRoutedAs("a label the batch will hide is routed as no label", false, routeInBatch(true, Boolean.FALSE));
    }

    @Test
    public void lastQueuedVisibility_wins() {
        assertRoutedAs("hide then show: shown", true, routeInBatch(false, Boolean.FALSE, Boolean.TRUE));
        assertRoutedAs("show then hide: hidden", false, routeInBatch(true, Boolean.TRUE, Boolean.FALSE));
    }

    @Test
    public void batchWithNoQueuedVisibility_readsTheModel() {
        assertRoutedAs("hidden in the model, nothing queued", false, routeInBatch(false));
        assertRoutedAs("shown in the model, nothing queued", true, routeInBatch(true));
    }

    /**
     * {@code batched} took the labelled route when {@code labelled}, else the unnamed one, and not the
     * other: the two references differ on {@link #CLEARANCE}, so a case cannot pass by both collapsing.
     */
    private void assertRoutedAs(String message, boolean labelled, Run batched) {
        List<String> none = route(CLEARANCE, "", true).bendpoints();
        List<String> shown = route(CLEARANCE, TEXT, true).bendpoints();
        assertNotEquals("the shown and unnamed routes differ", none, shown);
        assertEquals(message, labelled ? shown : none, batched.bendpoints());
    }

    @Test
    public void autoLayoutRoutingPass_inABatch_readsTheQueuedVisibility() {
        List<String> none = passRoute(false, null);
        List<String> shown = passRoute(true, null);
        assertNotEquals("the shown and unnamed routes differ", none, shown);

        assertEquals("a label the batch will show is routed as shown", shown, passRoute(false, Boolean.TRUE));
        assertEquals("a label the batch will hide is routed as no label", none, passRoute(true, Boolean.FALSE));
    }

    @Test
    public void queuedVisibilityOfCall_isTheAdmittedSessionsOpenBatch() {
        AutoRouteArmFixture.Ctx c = build(CLEARANCE, TEXT, false);
        AutoRouteArmFixture.StubEditorModelManager mgr = new AutoRouteArmFixture.StubEditorModelManager();
        mgr.setModels(List.of(c.model));
        MutationDispatcher dispatcher = AutoRouteArmFixture.newDispatcher(c.model);
        dispatcher.setApprovalModeProvider(() -> false);
        ArchiModelAccessorImpl accessor = new ArchiModelAccessorImpl(mgr, dispatcher);
        try {
            dispatcher.endCall();
            assertNull("no call admitted on this thread", dispatcher.queuedLabelVisibilityOfCall());
            dispatcher.admit(SESSION, "probe", c.model);
            assertNull("an admitted call with no open batch", dispatcher.queuedLabelVisibilityOfCall());

            dispatcher.beginBatch(SESSION, "show then probe");
            accessor.updateViewConnection(SESSION, CONN, null, null, null, Boolean.TRUE, null);
            dispatcher.admit(SESSION, "probe", c.model);
            assertEquals("the open batch's queued visibility", Map.of(CONN, Boolean.TRUE),
                    dispatcher.queuedLabelVisibilityOfCall());
            dispatcher.endCall();
            assertNull("gone once the call ends", dispatcher.queuedLabelVisibilityOfCall());
        } finally {
            accessor.dispose();
        }
    }

    @Test
    public void labelPolicy_canHideALabelTheBatchWillShow() {
        // On BLOCKED every position of the label lands on two boxes and no offset clears it, so the
        // policy hides it when it is shown. A label the batch is about to show is shown at commit.
        assertEquals("control: shown in the model, the policy hides it", List.of(CONN), policyHides(true));
        assertEquals("shown by a queued showLabel, the policy hides it too", List.of(CONN), policyHides(false));
    }

    @Test
    public void labelPolicy_writesNoLabelOffsetForALabelItHides() {
        // On BLOCKED every placement collides, anchors included, and the pick still ranks them: the policy hides
        // the label, and the anchor that ranked best among colliding placements is not written for it.
        AutoRouteArmFixture.Ctx c = build(BLOCKED, TEXT, true);
        ArchiModelAccessorImpl accessor = newAccessor(c);
        try {
            AutoRouteResultDto dto = accessor.autoRouteConnections(SESSION, VIEW_ID, null, "orthogonal", false,
                    false, 0, 0, null, true, "auto-hide-on-collision").entity();
            List<String> hidden = new ArrayList<>();
            dto.hiddenLabels().forEach(h -> hidden.add(h.connectionId()));
            assertEquals(List.of(CONN), hidden);
            assertEquals("the hidden label keeps the anchor it held", RelativePositionFeature.CENTER,
                    RelativePositionFeature.get(conn(c, CONN)));
        } finally {
            accessor.dispose();
        }
    }

    // ==================== driving ====================

    private record Run(List<String> bendpoints, int textPosition, int labelsOptimized) {}

    private Run route(int[][] boxes, String name, boolean visible) {
        AutoRouteArmFixture.Ctx c = build(boxes, name, visible);
        AutoRouteResultDto dto = AutoRouteArmFixture.route(c, AutoRouteArmFixture.Arm.APPLIED);
        return run(c, dto.labelsOptimized());
    }

    /**
     * Opens a batch on {@link #CLEARANCE} with the label {@code visibleInModel}, queues one
     * {@code update-view-connection showLabel} per entry of {@code queued}, queues the route, commits.
     */
    private Run routeInBatch(boolean visibleInModel, Boolean... queued) {
        AutoRouteArmFixture.Ctx c = build(CLEARANCE, TEXT, visibleInModel);
        AutoRouteArmFixture.StubEditorModelManager mgr = new AutoRouteArmFixture.StubEditorModelManager();
        mgr.setModels(List.of(c.model));
        MutationDispatcher dispatcher = AutoRouteArmFixture.newDispatcher(c.model);
        dispatcher.setApprovalModeProvider(() -> false);
        ArchiModelAccessorImpl accessor = new ArchiModelAccessorImpl(mgr, dispatcher);
        try {
            dispatcher.beginBatch(SESSION, "label visibility then route");
            for (Boolean show : queued) {
                accessor.updateViewConnection(SESSION, CONN, null, null, null, show, null);
            }
            MutationResult<AutoRouteResultDto> result = accessor.autoRouteConnections(SESSION, VIEW_ID, null,
                    "orthogonal", false, false, 0, 0, null);
            assertTrue("the route is queued, not refused", result.isBatched());
            assertTrue("nothing applied before commit", conn(c, CONN).getBendpoints().isEmpty());
            dispatcher.endBatch(SESSION, true);
        } finally {
            accessor.dispose();
        }
        return run(c, -1);
    }

    /**
     * The routing pass of {@code auto-layout-and-route} over {@link #CLEARANCE}, admitted for the
     * session as that tool admits its call; with {@code queued} non-null, inside a batch that has
     * queued that {@code showLabel}. Returns the route the pass builds for {@link #CONN}.
     */
    private List<String> passRoute(boolean visibleInModel, Boolean queued) {
        AutoRouteArmFixture.Ctx c = build(CLEARANCE, TEXT, visibleInModel);
        AutoRouteArmFixture.StubEditorModelManager mgr = new AutoRouteArmFixture.StubEditorModelManager();
        mgr.setModels(List.of(c.model));
        MutationDispatcher dispatcher = AutoRouteArmFixture.newDispatcher(c.model);
        dispatcher.setApprovalModeProvider(() -> false);
        ArchiModelAccessorImpl accessor = new ArchiModelAccessorImpl(mgr, dispatcher);
        try {
            if (queued != null) {
                dispatcher.beginBatch(SESSION, "label visibility then layout");
                accessor.updateViewConnection(SESSION, CONN, null, null, null, queued, null);
            }
            dispatcher.admit(SESSION, "auto-layout-and-route", c.model);
            AutoRoutePassResult pass =
                    accessor.computeAutoRoutePass(VIEW_ID, c.view, c.model, LabelPolicy.KEEP);
            assertNotNull(pass);
            executeLeaves(pass.compound);
        } finally {
            dispatcher.endCall();
            accessor.dispose();
        }
        return run(c, -1).bendpoints();
    }

    /** The labels {@code auto-hide-on-collision} hides on {@link #BLOCKED}; inside a batch when the model hides it. */
    private List<String> policyHides(boolean visibleInModel) {
        AutoRouteArmFixture.Ctx c = build(BLOCKED, TEXT, visibleInModel);
        AutoRouteArmFixture.StubEditorModelManager mgr = new AutoRouteArmFixture.StubEditorModelManager();
        mgr.setModels(List.of(c.model));
        MutationDispatcher dispatcher = AutoRouteArmFixture.newDispatcher(c.model);
        dispatcher.setApprovalModeProvider(() -> false);
        ArchiModelAccessorImpl accessor = new ArchiModelAccessorImpl(mgr, dispatcher);
        try {
            if (!visibleInModel) {
                dispatcher.beginBatch(SESSION, "show then route");
                accessor.updateViewConnection(SESSION, CONN, null, null, null, Boolean.TRUE, null);
            }
            AutoRouteResultDto dto = accessor.autoRouteConnections(SESSION, VIEW_ID, null, "orthogonal", false,
                    false, 0, 0, null, true, "auto-hide-on-collision").entity();
            List<String> ids = new ArrayList<>();
            dto.hiddenLabels().forEach(h -> ids.add(h.connectionId()));
            return ids;
        } finally {
            accessor.dispose();
        }
    }

    private int neighbourPosition(String firstName, boolean firstVisible) {
        AutoRouteArmFixture.Ctx c = AutoRouteArmFixture.newView();
        addBoxes(c, NEIGHBOURS);
        connect(c, CONN, "h0", "h1", firstName, firstVisible);
        connect(c, NEIGHBOUR, "h2", "h3", NEIGHBOUR_TEXT, true);
        AutoRouteArmFixture.route(c, AutoRouteArmFixture.Arm.APPLIED);
        return conn(c, NEIGHBOUR).getTextPosition();
    }

    private Run run(AutoRouteArmFixture.Ctx c, int labelsOptimized) {
        IDiagramModelArchimateConnection conn = conn(c, CONN);
        List<String> bendpoints = new ArrayList<>();
        for (IDiagramModelBendpoint bp : conn.getBendpoints()) {
            bendpoints.add(bp.getStartX() + "," + bp.getStartY() + ";" + bp.getEndX() + "," + bp.getEndY());
        }
        assertFalse("the connection was routed", bendpoints.isEmpty());
        return new Run(bendpoints, conn.getTextPosition(), labelsOptimized);
    }

    private static List<String> textPositionTargets(Command command) {
        List<String> ids = new ArrayList<>();
        if (command instanceof SetTextPositionCommand set) {
            ids.add(set.getConnection().getId());
        } else if (command instanceof CompoundCommand compound) {
            for (Object child : compound.getCommands()) {
                ids.addAll(textPositionTargets((Command) child));
            }
        }
        return ids;
    }

    /** Runs each leaf on its own, as the test dispatcher does: Archi's compound needs the workbench. */
    private static void executeLeaves(Command command) {
        if (command instanceof CompoundCommand compound) {
            for (Object child : compound.getCommands()) {
                executeLeaves((Command) child);
            }
        } else {
            command.execute();
        }
    }

    // ==================== fixtures ====================

    private AutoRouteArmFixture.Ctx build(int[][] boxes, String name, boolean visible) {
        AutoRouteArmFixture.Ctx c = AutoRouteArmFixture.newView();
        addBoxes(c, boxes);
        connect(c, CONN, "h0", "h1", name, visible);
        return c;
    }

    /**
     * {@link #PLACED} twice, the copy 1000 px below: the first copy's label hidden, the second's
     * shown. Side by side the two 466 px labels reach each other; stacked they do not.
     */
    private AutoRouteArmFixture.Ctx placedPair() {
        AutoRouteArmFixture.Ctx c = AutoRouteArmFixture.newView();
        addBoxes(c, PLACED);
        for (int i = 0; i < PLACED.length; i++) {
            int[] b = PLACED[i];
            AutoRouteArmFixture.addHost(c, "k" + i, b[0], b[1] + 1000, b[2], b[3]);
        }
        connect(c, CONN, "h0", "h1", TEXT, false);
        connect(c, NEIGHBOUR, "k0", "k1", TEXT, true);
        return c;
    }

    private static void addBoxes(AutoRouteArmFixture.Ctx c, int[][] boxes) {
        for (int i = 0; i < boxes.length; i++) {
            int[] b = boxes[i];
            AutoRouteArmFixture.addHost(c, "h" + i, b[0], b[1], b[2], b[3]);
        }
    }

    private static void connect(AutoRouteArmFixture.Ctx c, String connId, String from, String to,
            String name, boolean visible) {
        AutoRouteArmFixture.connect(c, connId, "rel-" + connId, "obj-" + from + "-n", "obj-" + to + "-n");
        IDiagramModelArchimateConnection conn = conn(c, connId);
        conn.getArchimateRelationship().setName(name);
        conn.setNameVisible(visible);
        conn.setTextPosition(MIDDLE);
    }

    private static IDiagramModelArchimateConnection conn(AutoRouteArmFixture.Ctx c, String id) {
        for (var child : c.view.getChildren()) {
            for (IDiagramModelConnection conn : child.getSourceConnections()) {
                if (id.equals(conn.getId())) {
                    return (IDiagramModelArchimateConnection) conn;
                }
            }
        }
        throw new AssertionError("no connection " + id);
    }

    private static RoutingRect rect(List<AssessmentNode> nodes, String id) {
        for (AssessmentNode n : nodes) {
            if (n.id().equals(id)) {
                return new RoutingRect((int) n.x(), (int) n.y(), (int) n.width(), (int) n.height(), n.id());
            }
        }
        throw new AssertionError("no node " + id);
    }

    private static ArchiModelAccessorImpl newAccessor(AutoRouteArmFixture.Ctx c) {
        AutoRouteArmFixture.StubEditorModelManager mgr = new AutoRouteArmFixture.StubEditorModelManager();
        mgr.setModels(List.of(c.model));
        MutationDispatcher dispatcher = AutoRouteArmFixture.newDispatcher(c.model);
        dispatcher.setApprovalModeProvider(() -> false);
        return new ArchiModelAccessorImpl(mgr, dispatcher);
    }
}
