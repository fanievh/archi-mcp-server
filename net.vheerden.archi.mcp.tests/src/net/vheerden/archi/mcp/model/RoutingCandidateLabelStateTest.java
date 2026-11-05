package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.Test;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.INode;

import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.model.routing.RoutingResult;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * The label state a routing candidate is judged with before apply must be the label state the
 * connection holds after apply: its text position and its Label Offset.
 *
 * <p>Each case routes a real view through {@code auto-route-connections}, then scores the applied
 * route twice: once the way the best-of-K scorer projects a candidate before anything is written
 * ({@link RoutedAssessmentPaths#overlay}), and once the way {@code assess-layout} reads the model
 * back ({@link AssessmentCollector}). The two label findings must be the same.</p>
 *
 * <p>The relationship is named, so the real collector measures its text: this class realizes SWT
 * and is filed with the display-lane classes. The Label Offset feature is present only on newer
 * platforms, so every case is assumption-skipped where it is absent.</p>
 */
public class RoutingCandidateLabelStateTest {

    private static final String SESSION = "label-state-session";
    private static final String VIEW_ID = "view-label-state";
    private static final String CONN = "conn-a";

    private static final int NORTH = 1;
    private static final int SOUTH = 4;
    private static final int WEST = 8;
    private static final int EAST = 16;
    private static final int CENTER = RelativePositionFeature.CENTER;

    /*
     * Geometry shared by every case. The route is the vertical line x=360 from y=27.5 to y=627.5, so
     * the Middle label is centred at (360, 327.5). Archi draws the Source and Target labels to the right
     * of the line just past each end (draw2d ConnectionEndpointLocator: 14 px along it from the drawn
     * end, 4 px across), starting at x 364. "reads the shared ledger" is 23 characters: the optimizer sizes it 23*8+10 = 194 px
     * wide (half 97, so an East or West offset draws it 102 px across), the assessor measures it
     * 258 px wide with SWT (half 129, so 134 px across). Both sizes are 20 px tall. A box counts as hit
     * only through a 10 px inset (6.7 px vertically), so every box below clears or hits both
     * estimates by at least 20 px.
     */

    @Test
    public void staleOffset_isResetToCentre_andScoredAtCentreBeforeApply() {
        // Box on the right, x 500..620. Un-offset label: optimizer x 263..457, assessor 231..489 (both
        // clear by more than 10). Held East: optimizer 365..559, assessor 365..623 (both on the box).
        Ctx c = vertical();
        addHost(c, "c", 500, 290, 120, 80);
        connect(c, EAST);

        Measured m = routeAndMeasure(c);

        assertEquals("pre-apply label findings must equal post-apply ones: " + m, m.post(), m.pre());
        assertEquals("the stale offset is reset to Centre: " + m, CENTER, m.anchorAfter());
        assertEquals("and the label is then clear: " + m, 0, m.postCount());
    }

    @Test
    public void workingOffset_isKept_andScoredWhereItDrawsBeforeApply() {
        // Boxes on the Source, Middle and Target labels (see blocked), so every along-path position
        // scores one hit and the label stays at Middle. Un-offset Middle label: on box c.
        // The first clearing direction on a vertical host is East; the held South-East also clears
        // (optimizer 365..559 x 332.5..352.5; assessor 365..623). A re-run must leave it alone.
        Ctx c = blocked();
        connect(c, SOUTH | EAST);

        Measured m = routeAndMeasure(c);

        assertEquals("pre-apply label findings must equal post-apply ones: " + m, m.post(), m.pre());
        assertEquals("the working held offset is kept: " + m, SOUTH | EAST, m.anchorAfter());
        assertEquals("the label stays at Middle: " + m, 1, m.textPositionAfter());
        assertEquals("and draws clear: " + m, 0, m.postCount());
    }

    @Test
    public void newOffset_isScoredBeforeApply() {
        // Same blocked positions, no held offset: the pass writes the first clearing direction, East.
        Ctx c = blocked();
        connect(c, CENTER);

        Measured m = routeAndMeasure(c);

        assertEquals("pre-apply label findings must equal post-apply ones: " + m, m.post(), m.pre());
        assertEquals("the pass offsets the label East: " + m, EAST, m.anchorAfter());
        assertEquals("and it draws clear: " + m, 0, m.postCount());
    }

    @Test
    public void movedTextPosition_isScoredBeforeApply() {
        // Box on the right, x 440..560: the un-offset Middle label is on it under both estimates
        // (optimizer inset right 447, assessor 479), the Source label (drawn just below a, to the right of the line) is clear, so the
        // optimizer moves the label to Source.
        Ctx c = vertical();
        addHost(c, "c", 440, 290, 120, 80);
        connect(c, CENTER);

        Measured m = routeAndMeasure(c);

        assertEquals("pre-apply label findings must equal post-apply ones: " + m, m.post(), m.pre());
        assertEquals("the optimizer moves the label to Source: " + m, 0, m.textPositionAfter());
        assertEquals("where it is clear: " + m, 0, m.postCount());
    }

    /**
     * Two parallel vertical connections, x 360 and x 500, whose Middle labels overlap. Boxes sit on the second
     * one's Source and Target labels, so its best placement is an anchor. The pick places the labels longest
     * first (equal here, so the first connection first): the first stays at Centre and the second goes East,
     * clear of it. Recomputing the anchors afterwards at the chosen positions would instead offset the first
     * West (it is searched first, against the second still at Centre) and leave the second at Centre, an
     * assignment no candidate was scored with. The anchors written are the ones every scored candidate carried.
     */
    @Test
    public void theAnchorsWritten_areTheAnchorsThePickScored() throws Exception {
        Ctx c = vertical();
        addHost(c, "a2", 440, 0, 120, 55);
        addHost(c, "b2", 440, 600, 120, 55);
        addHost(c, "d2", 580, 60, 120, 40);
        addHost(c, "e2", 580, 560, 120, 40);
        connect(c, CONN, "obj-a", "obj-b", CENTER);
        connect(c, "conn-b", "obj-a2", "obj-b2", CENTER);
        assumeTrue("Label-offset feature is only present on newer platforms",
                RelativePositionFeature.isSupported(connection(c)));

        AnchorRecorder recorder = new AnchorRecorder();
        StubEditorModelManager mgr = new StubEditorModelManager();
        mgr.setModels(List.of(c.model));
        ArchiModelAccessorImpl accessor = newAccessor(c.model, mgr);
        try {
            java.lang.reflect.Field field = ArchiModelAccessorImpl.class.getDeclaredField("layoutQualityAssessor");
            field.setAccessible(true);
            field.set(accessor, recorder);
            accessor.autoRouteConnections(SESSION, VIEW_ID, null, "orthogonal", false, false, 0, 0, null).entity();
        } finally {
            accessor.dispose();
        }
        Map<String, Integer> written = new LinkedHashMap<>();
        written.put(CONN, RelativePositionFeature.get(connection(c)));
        written.put("conn-b", RelativePositionFeature.get(
                (IDiagramModelArchimateConnection) find(c, "obj-a2").getSourceConnections().get(0)));

        assertEquals("the pick: the first label at Centre, the second East", Map.of(CONN, CENTER, "conn-b", EAST),
                written);
        assertEquals("every candidate was scored with the anchors written: " + recorder.scored,
                Set.of(written), new HashSet<>(recorder.scored));
    }

    /** Records the anchor each labelled connection carries in every candidate the best-of-K scorer assesses. */
    private static final class AnchorRecorder extends LayoutQualityAssessor {
        final List<Map<String, Integer>> scored = new ArrayList<>();

        @Override
        LayoutAssessmentResult assess(List<AssessmentNode> nodes, List<AssessmentConnection> connections,
                boolean includeViolatorIds) {
            if (!includeViolatorIds) {
                Map<String, Integer> anchors = new LinkedHashMap<>();
                for (AssessmentConnection ac : connections) {
                    anchors.put(ac.id(), ac.relativePosition());
                }
                scored.add(anchors);
            }
            return super.assess(nodes, connections, includeViolatorIds);
        }
    }

    // ==================== measuring ====================

    private record Measured(int preCount, List<String> pre, int postCount, List<String> post,
            int textPositionBefore, int textPositionAfter, int anchorBefore, int anchorAfter, String box) {}

    private Measured routeAndMeasure(Ctx c) {
        IDiagramModelArchimateConnection conn = connection(c);
        assumeTrue("Label-offset feature is only present on newer platforms",
                RelativePositionFeature.isSupported(conn));
        int textBefore = conn.getTextPosition();
        int anchorBefore = RelativePositionFeature.get(conn);

        // The batch input as the router saw it: positions and anchors read BEFORE the write.
        List<AssessmentNode> nodesBefore = AssessmentCollector.collectAssessmentNodes(c.view);
        List<RoutingPipeline.ConnectionEndpoints> batchInput = List.of(new RoutingPipeline.ConnectionEndpoints(
                CONN, rect(nodesBefore, conn.getSource().getId()), rect(nodesBefore, conn.getTarget().getId()),
                List.of(), conn.getArchimateRelationship().getName(), textBefore, List.of(), anchorBefore));

        route(c);

        List<AssessmentNode> nodes = AssessmentCollector.collectAssessmentNodes(c.view);
        List<AssessmentConnection> collected = AssessmentCollector.collectAssessmentConnections(c.view, nodes);
        LayoutQualityAssessor assessor = new LayoutQualityAssessor();
        LayoutAssessmentResult post = assessor.assess(nodes, collected, true);

        // The winning candidate as the scorer saw it: its applied path, and its changed text position.
        AssessmentConnection applied = collected.get(0);
        List<AbsoluteBendpointDto> bendpoints = new ArrayList<>();
        for (int i = 1; i < applied.pathPoints().size() - 1; i++) {
            double[] p = applied.pathPoints().get(i);
            bendpoints.add(new AbsoluteBendpointDto((int) Math.round(p[0]), (int) Math.round(p[1])));
        }
        Map<String, Integer> optimalPositions = conn.getTextPosition() != textBefore
                ? Map.of(CONN, conn.getTextPosition()) : Map.of();
        // The anchor the winner's label pick chose is the one the apply path wrote.
        Map<String, Integer> labelOffsets = RelativePositionFeature.get(conn) != anchorBefore
                ? Map.of(CONN, RelativePositionFeature.get(conn)) : Map.of();
        RoutingResult winner = new RoutingResult(Map.of(CONN, bendpoints), List.of(), List.of(), Map.of(),
                optimalPositions.size(), optimalPositions, 0, 0, Set.of(), null, labelOffsets);
        // This visible label on a supporting platform is writable.
        RoutedAssessmentPaths.LabelInputs labels = new RoutedAssessmentPaths.LabelInputs(Set.of(CONN));
        LayoutAssessmentResult pre = assessor.assess(nodes,
                RoutedAssessmentPaths.overlay(batchInput, winner, labels), true);

        LayoutQualityAssessor.LabelBounds box = assessor.drawnLabelBounds(applied);
        return new Measured(pre.labelOverlapCount(), pre.labelOverlaps(), post.labelOverlapCount(),
                post.labelOverlaps(), textBefore, conn.getTextPosition(), anchorBefore,
                RelativePositionFeature.get(conn), String.valueOf(box) + " path=" + describe(applied));
    }

    private static String describe(AssessmentConnection c) {
        StringBuilder sb = new StringBuilder();
        for (double[] p : c.pathPoints()) {
            sb.append('(').append(p[0]).append(',').append(p[1]).append(')');
        }
        return sb.toString();
    }

    private static RoutingRect rect(List<AssessmentNode> nodes, String id) {
        for (AssessmentNode n : nodes) {
            if (n.id().equals(id)) {
                return new RoutingRect((int) n.x(), (int) n.y(), (int) n.width(), (int) n.height(), n.id());
            }
        }
        throw new AssertionError("no node " + id);
    }

    private void route(Ctx c) {
        StubEditorModelManager mgr = new StubEditorModelManager();
        mgr.setModels(List.of(c.model));
        ArchiModelAccessorImpl accessor = newAccessor(c.model, mgr);
        try {
            accessor.autoRouteConnections(SESSION, VIEW_ID, null, "orthogonal", false,
                    false, 0, 0, null).entity();
        } finally {
            accessor.dispose();
        }
    }

    // ==================== fixtures ====================

    private static final class Ctx {
        IArchimateModel model;
        IArchimateDiagramModel view;
    }

    /** Source above target on one vertical line, 545 px apart: a Middle label sits at y≈327. */
    private Ctx vertical() {
        Ctx c = newView();
        addHost(c, "a", 300, 0, 120, 55);
        addHost(c, "b", 300, 600, 120, 55);
        return c;
    }

    /**
     * {@link #vertical} plus three boxes: c on the left, x 150..300 y 290..365, on the Middle label
     * (optimizer inset left 273, assessor 241); d and e on the right, x 430..580, across each end
     * (y 30..100 and 555..625), on the Source and Target labels. Each along-path position is hit once,
     * so the label stays at Middle. A West offset stays on c; East and every East diagonal clear it.
     */
    private Ctx blocked() {
        Ctx c = vertical();
        addHost(c, "d", 430, 30, 150, 70);
        addHost(c, "c", 150, 290, 150, 75);
        addHost(c, "e", 430, 555, 150, 70);
        return c;
    }

    private Ctx newView() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        Ctx c = new Ctx();
        c.model = f.createArchimateModel();
        c.model.setName("LabelState");
        c.model.setId("model-label-state");
        c.model.setDefaults();
        c.view = f.createArchimateDiagramModel();
        c.view.setId(VIEW_ID);
        c.view.setName("LabelState");
        c.model.getFolder(FolderType.DIAGRAMS).getElements().add(c.view);
        return c;
    }

    private void addHost(Ctx c, String id, int x, int y, int w, int h) {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        INode node = f.createNode();
        node.setId(id + "-n");
        c.model.getFolder(FolderType.TECHNOLOGY).getElements().add(node);
        IDiagramModelArchimateObject obj = f.createDiagramModelArchimateObject();
        obj.setId("obj-" + id);
        obj.setArchimateConcept(node);
        obj.setBounds(x, y, w, h);
        c.view.getChildren().add(obj);
    }

    /** Connects host a to host b with a named Middle label holding {@code anchor}. */
    private void connect(Ctx c, int anchor) {
        connect(c, CONN, "obj-a", "obj-b", anchor);
    }

    /** Connects {@code from} to {@code to} as {@code id} with a named Middle label holding {@code anchor}. */
    private void connect(Ctx c, String id, String from, String to, int anchor) {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IDiagramModelArchimateObject src = find(c, from);
        IDiagramModelArchimateObject tgt = find(c, to);
        IArchimateRelationship rel = f.createAssociationRelationship();
        rel.setId("rel-" + id);
        rel.setName("reads the shared ledger");
        rel.setSource(src.getArchimateConcept());
        rel.setTarget(tgt.getArchimateConcept());
        c.model.getFolder(FolderType.RELATIONS).getElements().add(rel);
        IDiagramModelArchimateConnection conn = f.createDiagramModelArchimateConnection();
        conn.setId(id);
        conn.setArchimateRelationship(rel);
        conn.connect(src, tgt);
        conn.setTextPosition(1);
        RelativePositionFeature.set(conn, anchor);
    }

    private IDiagramModelArchimateObject find(Ctx c, String id) {
        for (IDiagramModelObject child : c.view.getChildren()) {
            if (id.equals(child.getId()) && child instanceof IDiagramModelArchimateObject o) {
                return o;
            }
        }
        throw new AssertionError("no view object with id " + id);
    }

    private IDiagramModelArchimateConnection connection(Ctx c) {
        return (IDiagramModelArchimateConnection) find(c, "obj-a").getSourceConnections().get(0);
    }

    // ==================== harness ====================

    private ArchiModelAccessorImpl newAccessor(IArchimateModel target, StubEditorModelManager mgr) {
        MutationDispatcher testDispatcher = new MutationDispatcher(() -> target) {
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
        testDispatcher.setApprovalModeProvider(() -> false);
        return new ArchiModelAccessorImpl(mgr, testDispatcher);
    }

    private static class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final Set<PropertyChangeListener> listeners = new LinkedHashSet<>();

        void setModels(List<IArchimateModel> models) {
            this.models = models;
        }

        @Override
        public List<IArchimateModel> getModels() {
            return models;
        }

        @Override
        public void addPropertyChangeListener(PropertyChangeListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removePropertyChangeListener(PropertyChangeListener listener) {
            listeners.remove(listener);
        }

        @Override public IArchimateModel createNewModel() { return null; }
        @Override public void registerModel(IArchimateModel model) {}
        @Override public IArchimateModel openModel(File file) { return null; }
        @Override public void openModel(IArchimateModel model) {}
        @Override public IArchimateModel loadModel(File file) { return null; }
        @Override public IArchimateModel load(File file) throws IOException { return null; }
        @Override public boolean closeModel(IArchimateModel model) throws IOException { return false; }
        @Override public boolean closeModel(IArchimateModel model, boolean askSave) throws IOException { return false; }
        @Override public boolean isModelLoaded(File file) { return false; }
        @Override public boolean isModelDirty(IArchimateModel model) { return false; }
        @Override public boolean saveModel(IArchimateModel model) throws IOException { return false; }
        @Override public boolean saveModelAs(IArchimateModel model) throws IOException { return false; }
        @Override public void saveState() throws IOException {}
        @Override public void firePropertyChange(Object source, String prop, Object oldValue, Object newValue) {}
    }
}
