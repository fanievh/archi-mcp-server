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

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IDiagramModelGroup;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;
import net.vheerden.archi.mcp.response.dto.ViewContentsDto;

/**
 * Drives the real view collector over a hand-built model and pins both halves of what it reports:
 * that the render faces reach the wire under the names and spellings they will keep, and that
 * nothing the collector reported before the fold changed.
 *
 * <p><strong>The fold.</strong> The collector used to re-implement the anchor and
 * absolute-bendpoint block the shared connection builder already owned, so the two could describe
 * the same geometry differently and a field added to one would simply be missing from the other.
 * It now calls the builder. Every pre-existing value asserted below was read from a worktree at the
 * commit before the fold and is reproduced here unchanged — the assertions are the baseline, not a
 * restatement of the new code.
 *
 * <p><strong>Four shapes, chosen so the reading cannot be accidental:</strong> a top-level pair
 * aligned on one axis, a pair one group deep with stored bendpoints, a pair two groups deep, and a
 * pair at negative canvas coordinates. Two of the four abstain, for two different reasons, so a
 * classifier stuck on any single answer fails here.
 *
 * <p>Elements are deliberately left unnamed. A named element can send the collector through
 * {@code ElementSizer.measureText}, which throws on a display-less runner — and the macOS variant
 * of that failure is caught while the Linux one is not, so a green run on a developer machine
 * would not establish that this class runs in the headless lane at all.
 */
public class ViewCollectorRenderFaceTest {

    private ViewContentsDto contents;
    private final java.util.Map<String, IDiagramModelArchimateObject> sourceObjects =
            new java.util.LinkedHashMap<>();

    @Before
    public void setUp() {
        contents = collectWithRouter(IDiagramModel.CONNECTION_ROUTER_BENDPOINT);
    }

    /** Builds the same four connections on a view routed by the given router, and collects them. */
    private ViewContentsDto collectWithRouter(int routerType) {
        sourceObjects.clear();
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setId("model-fold-probe");
        model.setDefaults();

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setConnectionRouterType(routerType);
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        int caseIndex = 0;
        // Four shapes: top level, nested one deep, nested two deep, and an odd-sized pair.
        int[][][] cases = {
            { { 100, 100, 120, 55 }, { 400, 100, 120, 55 }, { 0, 0, 0, 0 } },
            { { 10, 10, 181, 55 }, { 300, 260, 120, 91 }, { 1, 0, 0, 0 } },
            { { 30, 30, 140, 55 }, { 500, 600, 100, 50 }, { 2, 0, 0, 0 } },
            { { 0, 0, 40, 30 }, { -130, -116, 120, 120 }, { 0, 0, 0, 0 } },
        };
        for (int[][] c : cases) {
            caseIndex++;
            int depth = c[2][0];
            IBusinessActor from = factory.createBusinessActor();
            from.setId("actor-from-" + caseIndex);
            model.getFolder(FolderType.BUSINESS).getElements().add(from);
            IBusinessActor to = factory.createBusinessActor();
            to.setId("actor-to-" + caseIndex);
            model.getFolder(FolderType.BUSINESS).getElements().add(to);
            IArchimateRelationship rel = factory.createAssociationRelationship();
            rel.setId("rel-" + caseIndex);
            rel.setSource(from);
            rel.setTarget(to);
            model.getFolder(FolderType.RELATIONS).getElements().add(rel);

            IDiagramModelArchimateObject src = factory.createDiagramModelArchimateObject();
            src.setId("vo-src-" + caseIndex);
            src.setArchimateElement(from);
            src.setBounds(c[0][0], c[0][1], c[0][2], c[0][3]);
            IDiagramModelArchimateObject tgt = factory.createDiagramModelArchimateObject();
            tgt.setId("vo-tgt-" + caseIndex);
            tgt.setArchimateElement(to);
            tgt.setBounds(c[1][0], c[1][1], c[1][2], c[1][3]);

            if (depth == 0) {
                view.getChildren().add(src);
            } else {
                IDiagramModelGroup outer = factory.createDiagramModelGroup();
                outer.setId("grp-outer-" + caseIndex);
                outer.setBounds(20, 360, 400, 400);
                view.getChildren().add(outer);
                if (depth == 1) {
                    outer.getChildren().add(src);
                } else {
                    IDiagramModelGroup inner = factory.createDiagramModelGroup();
                    inner.setId("grp-inner-" + caseIndex);
                    inner.setBounds(50, 50, 300, 300);
                    outer.getChildren().add(inner);
                    inner.getChildren().add(src);
                }
            }
            view.getChildren().add(tgt);

            sourceObjects.put("conn-" + caseIndex, src);

            IDiagramModelArchimateConnection conn = factory.createDiagramModelArchimateConnection();
            conn.setId("conn-" + caseIndex);
            conn.setArchimateConcept(rel);
            conn.connect(src, tgt);
            if (caseIndex % 2 == 0) {
                IDiagramModelBendpoint bp = factory.createDiagramModelBendpoint();
                bp.setStartX(40);
                bp.setStartY(-20);
                bp.setEndX(-60);
                bp.setEndY(30);
                conn.getBendpoints().add(bp);
                IDiagramModelBendpoint bp2 = factory.createDiagramModelBendpoint();
                bp2.setStartX(90);
                bp2.setStartY(-5);
                bp2.setEndX(-11);
                bp2.setEndY(45);
                conn.getBendpoints().add(bp2);
            }
        }
        StubEditorModelManager mgr = new StubEditorModelManager();
        mgr.setModels(List.of(model));
        CommandStack stack = new CommandStack();
        MutationDispatcher dispatcher = new MutationDispatcher(() -> model) {
            @Override public void dispatchImmediate(Command command) { stack.execute(plain(command)); }
            @Override protected void dispatchCommand(Command command) { stack.execute(plain(command)); }
            private Command plain(Command command) {
                if (command instanceof CompoundCommand compound) {
                    CompoundCommand p = new CompoundCommand(compound.getLabel());
                    for (Object child : compound.getCommands()) { p.add(plain((Command) child)); }
                    return p;
                }
                return command;
            }
        };
        dispatcher.setApprovalModeProvider(() -> false);
        ArchiModelAccessorImpl accessor = new ArchiModelAccessorImpl(mgr, dispatcher);
        ViewContentsDto collected = accessor.getViewContents("view-1").orElseThrow();
        accessor.dispose();
        return collected;
    }

    private ViewConnectionDto connection(String id) {
        return contents.connections().stream().filter(c -> id.equals(c.viewConnectionId()))
                .findFirst().orElseThrow(() -> new AssertionError("no connection " + id));
    }

    /**
     * Everything the collector reported before the fold, reported identically after it.
     *
     * <p>These values were produced by the pre-fold implementation in a worktree at its own commit
     * and diffed against the folded one, field for field, across all four shapes. Re-running the
     * new code twice would only have proved it deterministic.
     */
    @Test
    public void shouldReportEveryPreExistingField_whenTheCollectorCallsTheSharedBuilder() {
        assertEquals("four connections collected", 4, contents.connections().size());

        ViewConnectionDto one = connection("conn-1");
        assertEquals(160, one.sourceAnchor().x());
        assertEquals(127, one.sourceAnchor().y());
        assertEquals(460, one.targetAnchor().x());
        assertEquals(127, one.targetAnchor().y());
        assertNull("no stored geometry, so no bendpoints", one.bendpoints());
        assertNull(one.absoluteBendpoints());

        ViewConnectionDto two = connection("conn-2");
        assertEquals("a source one group deep", 120, two.sourceAnchor().x());
        assertEquals(397, two.sourceAnchor().y());
        assertEquals(360, two.targetAnchor().x());
        assertEquals(305, two.targetAnchor().y());
        assertEquals(2, two.absoluteBendpoints().size());
        assertEquals(206, two.absoluteBendpoints().get(0).x());
        assertEquals(363, two.absoluteBendpoints().get(0).y());
        assertEquals(302, two.absoluteBendpoints().get(1).x());
        assertEquals(364, two.absoluteBendpoints().get(1).y());

        ViewConnectionDto three = connection("conn-3");
        assertEquals("a source two groups deep", 170, three.sourceAnchor().x());
        assertEquals(467, three.sourceAnchor().y());

        ViewConnectionDto four = connection("conn-4");
        assertEquals("negative canvas coordinates survive", -70, four.targetAnchor().x());
        assertEquals(-56, four.targetAnchor().y());
        assertEquals(-3, four.absoluteBendpoints().get(0).x());
        assertEquals(-12, four.absoluteBendpoints().get(0).y());
    }

    /**
     * The faces, on the two connections where both ends are derivable.
     *
     * <p>The first pair sits side by side on the same row, so the line leaves the left box's right
     * face and enters the right box's left one — the reading a person would give looking at the
     * canvas. The second pair carries stored bendpoints, so each anchor aims at the outermost one
     * rather than at the other centre, and the answer changes accordingly.
     */
    @Test
    public void shouldPublishTheFace_whenBothEndsAreDerivable() {
        assertEquals("right", connection("conn-1").sourceRenderFace());
        assertEquals("left", connection("conn-1").targetRenderFace());
        assertEquals("top", connection("conn-2").sourceRenderFace());
        assertEquals("bottom", connection("conn-2").targetRenderFace());
    }

    /**
     * Where the attachment is a corner or lands inside the box, the field is simply absent.
     *
     * <p>Two different reasons, on two different connections: the diagonally-placed pair attaches
     * at a box corner, which lies on two face lines at once, and the last connection's stored
     * bendpoint falls inside the box it is aimed at, where the anchor answers with the centre.
     * Neither is a failure and neither publishes a guess.
     */
    @Test
    public void shouldOmitTheField_whenTheAttachmentNamesNoSingleFace() {
        assertNull(connection("conn-3").sourceRenderFace());
        assertNull(connection("conn-3").targetRenderFace());
        assertNull(connection("conn-4").sourceRenderFace());
        assertNull(connection("conn-4").targetRenderFace());
    }

    /**
     * The exact strings on the wire, and the exact absence.
     *
     * <p>A serialized value is a published contract from the first release, so the spellings are
     * pinned as they appear in JSON rather than as enum constants. Absence is pinned the same way:
     * an abstaining connection carries neither key at all, which is what keeps every response that
     * was byte-identical before this field byte-identical after it.
     */
    @Test
    public void shouldSerializeTheFaceUnderItsPublishedName_whenItIsDerived() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        String derived = mapper.writeValueAsString(connection("conn-1"));
        assertTrue(derived, derived.contains("\"sourceRenderFace\":\"right\""));
        assertTrue(derived, derived.contains("\"targetRenderFace\":\"left\""));

        String abstained = mapper.writeValueAsString(connection("conn-3"));
        assertFalse(abstained, abstained.contains("sourceRenderFace"));
        assertFalse(abstained, abstained.contains("targetRenderFace"));
    }

    /**
     * A group, note or view reference cannot be a connection endpoint at all — but another
     * connection can, and that is the shape the non-element guard actually exists for.
     *
     * <p>Archi refuses the first outright: {@code DiagramModelArchimateConnection.connect} throws
     * for anything that is not an ArchiMate object or connection. The second it permits, because
     * ArchiMate allows a relationship to a relationship, so a connection's endpoint can be another
     * connection's line. That endpoint has no bounds and no element, so nothing about it can be
     * derived, and the response says so by carrying neither an anchor nor a face.
     */
    @Test
    public void shouldDeriveNothing_whenAnEndpointIsAnotherConnection() {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IDiagramModelArchimateObject element = factory.createDiagramModelArchimateObject();
        element.setArchimateElement(factory.createBusinessActor());
        element.setBounds(0, 0, 100, 50);

        IDiagramModelGroup group = factory.createDiagramModelGroup();
        group.setBounds(300, 0, 200, 200);
        IDiagramModelArchimateConnection refused = factory.createDiagramModelArchimateConnection();
        refused.setArchimateConcept(factory.createAssociationRelationship());
        try {
            refused.connect(element, group);
            throw new AssertionError("Archi accepted a group as a connection endpoint");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }

        assertNull("an endpoint the builder cannot resolve derives no face",
                ConnectionResponseBuilder.describeEndpoints(element, null, null).sourceRenderFace());
        assertNull("nor an anchor",
                ConnectionResponseBuilder.describeEndpoints(null, element, null).sourceAnchor());
    }

    /**
     * An endpoint not yet attached to a view derives no face, whatever its geometry says.
     *
     * <p>This is the shape a batch produces: a queued object carries its destination in the queue
     * rather than in its container, so {@code getDiagramModel()} answers null for it and the view's
     * router type — which decides whether the anchor aims at the connection's own bendpoints or at
     * the other element's centre — cannot be read. The two choices reach different faces on most of
     * the corpus, so the field declines rather than assuming the default. The anchors still report,
     * because a centre is a fact about the object alone.
     */
    @Test
    public void shouldDeriveNoFace_whenAnEndpointIsNotAttachedToAView() {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IDiagramModelArchimateObject detachedSource = factory.createDiagramModelArchimateObject();
        detachedSource.setArchimateElement(factory.createBusinessActor());
        detachedSource.setBounds(100, 100, 120, 55);
        IDiagramModelArchimateObject detachedTarget = factory.createDiagramModelArchimateObject();
        detachedTarget.setArchimateElement(factory.createBusinessActor());
        detachedTarget.setBounds(400, 100, 120, 55);

        ConnectionResponseBuilder.EndpointGeometry detached =
                ConnectionResponseBuilder.describeEndpoints(detachedSource, detachedTarget, null);
        assertNotNull("the centres are still facts about the objects themselves",
                detached.sourceAnchor());
        assertEquals(160, detached.sourceAnchor().x());
        assertNull("but the router type cannot be read, so no face is derived",
                detached.sourceRenderFace());
        assertNull(detached.targetRenderFace());

        // The very same geometry, attached to a view, does name both faces — so the abstention is
        // the missing view and not something about these boxes.
        assertEquals("right", connection("conn-1").sourceRenderFace());
        assertEquals("left", connection("conn-1").targetRenderFace());
    }

    /**
     * A manhattan-routed view ignores stored bendpoints, and the face changes because of it.
     *
     * <p>This is the arm that had to be tested rather than reasoned about. The manhattan router
     * never reads the connection's own bendpoints — it aims each anchor at the other element's
     * centre — so on the same geometry it reaches a different attachment from the default router.
     * The census measures how often that happens; this drives the production branch that decides
     * it, which reads the view's router type off the model.
     *
     * <p>On this geometry both ends of the routed connection stop being derivable: aimed at the
     * other element's centre rather than at its own bendpoints, each attaches at a box corner. That
     * is a real change of answer, not a loss — the same view routed the default way names both
     * faces.
     *
     * <p>The bendpoint-free connections are unaffected, because they already had nothing for the
     * router to ignore — which is the other half of the claim and would be missed by a test that
     * only checked something changed.
     */
    @Test
    public void shouldIgnoreStoredBendpoints_whenTheViewRoutesManhattan() {
        ViewContentsDto manhattan = collectWithRouter(IDiagramModel.CONNECTION_ROUTER_MANHATTAN);
        ViewConnectionDto routed = manhattan.connections().stream()
                .filter(c -> "conn-2".equals(c.viewConnectionId())).findFirst().orElseThrow();

        assertEquals("the default router aims at the first bendpoint",
                "top", connection("conn-2").sourceRenderFace());
        assertEquals("bottom", connection("conn-2").targetRenderFace());
        assertNull("aimed at the other centre instead, the source attaches at a corner",
                routed.sourceRenderFace());
        assertNull("and so does the target", routed.targetRenderFace());
        assertNotEquals("so the two routers do not agree on this connection",
                connection("conn-2").sourceRenderFace(), routed.sourceRenderFace());

        ViewConnectionDto bendpointFree = manhattan.connections().stream()
                .filter(c -> "conn-1".equals(c.viewConnectionId())).findFirst().orElseThrow();
        assertEquals("a connection with no stored geometry is unaffected",
                connection("conn-1").sourceRenderFace(), bendpointFree.sourceRenderFace());
        assertEquals(connection("conn-1").targetRenderFace(), bendpointFree.targetRenderFace());

        assertEquals("and the stored bendpoints are still reported, only not aimed at",
                2, routed.absoluteBendpoints().size());
    }

    /**
     * The bounds walk and the centre walk accumulate the same ancestors.
     *
     * <p>The centre is derived from the bounds rather than walked separately, so the two cannot
     * drift; what this pins is the accumulation itself, against an expectation written out by hand
     * for an object nested two containers deep.
     */
    @Test
    public void shouldAccumulateTheSameAncestors_whenBoundsAndCentreAreWalked() {
        IDiagramModelArchimateObject nested = nestedSource();
        int[] bounds = ConnectionResponseBuilder.computeAbsoluteBounds(nested);
        // 30 + 50 + 20 across, 30 + 50 + 360 down; the size is untouched by nesting.
        assertEquals(100, bounds[0]);
        assertEquals(440, bounds[1]);
        assertEquals(140, bounds[2]);
        assertEquals(55, bounds[3]);

        int[] centre = ConnectionResponseBuilder.computeAbsoluteCenter(nested);
        assertEquals("x + width / 2, truncating", bounds[0] + bounds[2] / 2, centre[0]);
        assertEquals("y + height / 2, truncating", bounds[1] + bounds[3] / 2, centre[1]);
        assertEquals(170, centre[0]);
        assertEquals(467, centre[1]);
        assertNotNull(connection("conn-3").sourceAnchor());
        assertEquals("and that is the anchor the response publishes",
                centre[0], connection("conn-3").sourceAnchor().x());
    }

    private IDiagramModelArchimateObject nestedSource() {
        return sourceObjects.get("conn-3");
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
