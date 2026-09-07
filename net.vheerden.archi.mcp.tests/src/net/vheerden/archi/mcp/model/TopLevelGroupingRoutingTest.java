package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IGrouping;
import com.archimatetool.model.INode;

import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;

/**
 * The router must treat an ArchiMate {@code Grouping} as the transparent container it renders as.
 *
 * <p>Three sites built the A* input from the native-group flag alone: two excluded a group from the
 * obstacle list outright and one routed it into the soft {@code groupBoundaries} clearance list
 * instead. A {@code Grouping} fell through all three into {@code obstacles} — a solid, impassable
 * rectangle — so a connection between two zones had to detour around every third zone, where the
 * identical native-group view routed straight through. The source's and target's own ancestor zone
 * is excluded by id, so the defect bit third-party zones only, which is exactly the case the
 * technology and deployment guidance produces at scale.
 *
 * <p>The label-position optimiser built its obstacle list the same way and is fixed with them: it
 * must not work to route a label around a rectangle the assessor no longer counts a label as
 * overlapping.
 *
 * <p>Asserted as parity between two views with byte-identical geometry differing only in container
 * kind, plus an absolute claim on the shape of the route, so the test cannot pass by both runs
 * being wrong in the same way.
 */
public class TopLevelGroupingRoutingTest {

    private static final String SESSION = "routing-session";
    private static final String VIEW_ID = "view-corridor";
    private static final String CONNECTION_ID = "conn-left-right";

    @Test
    public void shouldRouteAcrossAThirdPartyGroupingExactlyAsAcrossANativeGroup() {
        assertEquals("a Grouping between two endpoints is a transparent container, so the route"
                        + " across it must match the route across the equivalent native group",
                routeAndDescribe(Kind.NATIVE), routeAndDescribe(Kind.GROUPING));
    }

    @Test
    public void shouldRouteStraightAcrossAThirdPartyGrouping() {
        // The absolute half of the claim; without it the parity test above would still pass if
        // both kinds detoured. "Straight" is asserted as zero VERTICAL deviation rather than zero
        // bendpoints: the router emits flat terminal bendpoints on a clear horizontal corridor
        // (both endpoints sit at the same height), so a bendpoint count of zero is not what a
        // clear crossing looks like. A detour around a solid rectangle is what moves the route
        // off its own height.
        assertEquals("a transparent container must impose no vertical detour on a clear corridor",
                0, maxVerticalDeviation(Kind.GROUPING));
    }

    @Test
    public void shouldRouteStraightAcrossAThirdPartyNativeGroup() {
        assertEquals("negative control", 0, maxVerticalDeviation(Kind.NATIVE));
    }

    @Test
    public void shouldRouteTheConnectionAtAll() {
        // Guards the two tests above against passing because nothing was routed: an unrouted
        // connection also has no vertical deviation. Asserted per kind rather than as one
        // conjunction, so a failure names which side did not route.
        assertEquals("the Grouping fixture must actually route its one connection",
                1, routedCount(Kind.GROUPING));
        assertEquals("the native fixture must actually route its one connection",
                1, routedCount(Kind.NATIVE));
    }

    // ============ the autoNudge suppression gate ============

    /**
     * Two overlapping zones must not suppress the nudge for one container kind and allow it for
     * the other.
     *
     * <p>{@code autoRouteConnections} skips its nudge pass when {@code OverlapResolver} reports a
     * sibling overlap, because degenerate geometry can crash the re-routing pipeline. That scan
     * excluded native groups only, so two overlapping zones suppressed the nudge on a
     * {@code Grouping} view and not on the identical native-group view — and zones commonly abut
     * or overlap slightly without the elements inside them doing anything degenerate.
     *
     * <p>Observed through the response's structured warning rather than through the nudge itself:
     * the skip publishes {@code AUTO_NUDGE_SKIPPED_SIBLING_OVERLAP}, so the gate's decision is
     * directly readable. Whether the nudge then moves anything is a separate question this fixture
     * does not claim to answer.
     */
    @Test
    public void shouldNotSuppressTheNudgeGate_whenOnlyTwoZonesOverlap() {
        assertEquals("two overlapping transparent containers are not the degenerate sibling"
                        + " geometry the nudge gate exists to avoid",
                "", nudgeSkipWarnings(Kind.GROUPING));
    }

    @Test
    public void shouldNotSuppressTheNudgeGate_whenOnlyTwoNativeGroupsOverlap() {
        assertEquals("negative control", "", nudgeSkipWarnings(Kind.NATIVE));
    }

    /** The nudge-skip warning codes raised for the overlapping-zones fixture, or "" if none. */
    private String nudgeSkipWarnings(Kind kind) {
        Ctx c = overlappingZonesFixture(kind);
        StubEditorModelManager mgr = new StubEditorModelManager();
        mgr.setModels(List.of(c.model));
        ArchiModelAccessorImpl accessor = newAccessor(mgr, c.model);
        try {
            List<String> codes = new ArrayList<>();
            for (StructuredWarningDto w : accessor.autoRouteConnections(
                    SESSION, VIEW_ID, null, "orthogonal", false,
                    true, 0, 0, null).entity().structuredWarnings()) {
                if (StructuredWarningCodes.AUTO_NUDGE_SKIPPED_SIBLING_OVERLAP.equals(w.code())) {
                    codes.add(w.code() + w.remediationViolatorIds());
                }
            }
            return String.join(",", codes);
        } finally {
            accessor.dispose();
        }
    }

    /**
     * Two top-level zones whose rectangles overlap, with their hosts placed so that nothing else
     * on the view overlaps anything — the container rectangles are the only overlapping pair, so
     * the gate's verdict is attributable to them alone.
     */
    private Ctx overlappingZonesFixture(Kind kind) {
        Ctx c = newView();
        IDiagramModelObject a = addZone(c, kind, "Zone A", "za", 0, 0, 300, 200);
        addHost(c, a, "Left Host", "hl", 10, 100, 120, 55);
        IDiagramModelObject b = addZone(c, kind, "Zone B", "zb", 250, 0, 300, 200);
        addHost(c, b, "Right Host", "hr", 160, 100, 120, 55);
        connect(c, "obj-hl-n", "obj-hr-n");
        return c;
    }

    // ==================== driving ====================

    private enum Kind { NATIVE, GROUPING }

    /** Routes the fixture and returns the resulting bendpoint sequence as a comparable string. */
    private String routeAndDescribe(Kind kind) {
        Ctx c = corridorFixture(kind);
        route(c);
        IDiagramModelConnection conn = findConnection(c.view);
        StringBuilder sb = new StringBuilder();
        for (IDiagramModelBendpoint bp : conn.getBendpoints()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append('(').append(bp.getStartX()).append(',').append(bp.getStartY())
                    .append("->").append(bp.getEndX()).append(',').append(bp.getEndY()).append(')');
        }
        return sb.toString();
    }

    /**
     * The largest vertical offset any bendpoint imposes on the route, relative to the endpoints
     * the connection joins. Zero means the line never leaves its own height — the shape of a
     * crossing that met no obstruction.
     */
    private int maxVerticalDeviation(Kind kind) {
        Ctx c = corridorFixture(kind);
        route(c);
        int worst = 0;
        for (IDiagramModelBendpoint bp : findConnection(c.view).getBendpoints()) {
            worst = Math.max(worst, Math.max(Math.abs(bp.getStartY()), Math.abs(bp.getEndY())));
        }
        return worst;
    }

    private int routedCount(Kind kind) {
        Ctx c = corridorFixture(kind);
        return route(c);
    }

    private int route(Ctx c) {
        StubEditorModelManager mgr = new StubEditorModelManager();
        mgr.setModels(List.of(c.model));
        ArchiModelAccessorImpl accessor = newAccessor(mgr, c.model);
        try {
            return accessor.autoRouteConnections(SESSION, VIEW_ID, null, "orthogonal", false,
                    false, 0, 0, null).entity().connectionsRouted();
        } finally {
            accessor.dispose();
        }
    }

    // ==================== fixture ====================

    /**
     * Three tall zones in a row. The outer two each host an element; the middle zone's only child
     * sits well below the line joining them, so the straight route crosses the middle zone's
     * rectangle and nothing else. That isolates the container's own transparency as the single
     * thing under test.
     */
    private Ctx corridorFixture(Kind kind) {
        Ctx c = newView();
        IDiagramModelObject left = addZone(c, kind, "Left Zone", "zl", 0, 0, 220, 400);
        addHost(c, left, "Left Host", "hl", 20, 180, 120, 55);

        IDiagramModelObject middle = addZone(c, kind, "Middle Zone", "zm", 400, 0, 220, 400);
        addHost(c, middle, "Middle Host", "hm", 20, 320, 120, 55);

        IDiagramModelObject right = addZone(c, kind, "Right Zone", "zr", 800, 0, 220, 400);
        addHost(c, right, "Right Host", "hr", 20, 180, 120, 55);

        connect(c, "obj-hl-n", "obj-hr-n");
        return c;
    }

    private static final class Ctx {
        IArchimateModel model;
        IArchimateDiagramModel view;
    }

    private Ctx newView() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        Ctx c = new Ctx();
        c.model = f.createArchimateModel();
        c.model.setName("Corridor");
        c.model.setId("model-corridor");
        c.model.setDefaults();
        c.view = f.createArchimateDiagramModel();
        c.view.setId(VIEW_ID);
        c.view.setName("Corridor");
        c.model.getFolder(FolderType.DIAGRAMS).getElements().add(c.view);
        return c;
    }

    private IDiagramModelObject addZone(Ctx c, Kind kind, String name, String id,
            int x, int y, int w, int h) {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IDiagramModelObject zone;
        if (kind == Kind.NATIVE) {
            IDiagramModelGroup g = f.createDiagramModelGroup();
            g.setId("obj-" + id);
            g.setName(name);
            zone = g;
        } else {
            IGrouping concept = f.createGrouping();
            concept.setId(id);
            concept.setName(name);
            c.model.getFolder(FolderType.OTHER).getElements().add(concept);
            IDiagramModelArchimateObject o = f.createDiagramModelArchimateObject();
            o.setId("obj-" + id);
            o.setArchimateConcept(concept);
            zone = o;
        }
        zone.setBounds(x, y, w, h);
        c.view.getChildren().add(zone);
        return zone;
    }

    private void addHost(Ctx c, IDiagramModelObject parent, String name, String id,
            int x, int y, int w, int h) {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        INode node = f.createNode();
        node.setId(id + "-n");
        node.setName(name);
        c.model.getFolder(FolderType.TECHNOLOGY).getElements().add(node);
        IDiagramModelArchimateObject obj = f.createDiagramModelArchimateObject();
        obj.setId("obj-" + id + "-n");
        obj.setArchimateConcept(node);
        obj.setBounds(x, y, w, h);
        ((IDiagramModelContainer) parent).getChildren().add(obj);
    }

    /**
     * Built directly rather than through {@code createRelationship}: that path consults Archi's
     * static validity matrix, which needs an OSGi context this fixture does not have, while the
     * obstacle-list construction under test reads only the view's geometry and connection graph.
     */
    private void connect(Ctx c, String sourceObjId, String targetObjId) {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IDiagramModelArchimateObject src = findObject(c.view, sourceObjId);
        IDiagramModelArchimateObject tgt = findObject(c.view, targetObjId);
        IArchimateRelationship rel = f.createAssociationRelationship();
        rel.setId("rel-left-right");
        rel.setName("reaches");
        rel.setSource(src.getArchimateConcept());
        rel.setTarget(tgt.getArchimateConcept());
        c.model.getFolder(FolderType.RELATIONS).getElements().add(rel);

        IDiagramModelArchimateConnection conn = f.createDiagramModelArchimateConnection();
        conn.setId(CONNECTION_ID);
        conn.setArchimateRelationship(rel);
        conn.connect(src, tgt);
    }

    private IDiagramModelArchimateObject findObject(IDiagramModelContainer container, String id) {
        IDiagramModelArchimateObject found = searchObject(container, id);
        if (found == null) {
            throw new AssertionError("no view object with id " + id);
        }
        return found;
    }

    /** Null-returning inner walk — a container that does not hold the id is a miss, not a failure. */
    private IDiagramModelArchimateObject searchObject(IDiagramModelContainer container, String id) {
        for (IDiagramModelObject child : container.getChildren()) {
            if (id.equals(child.getId()) && child instanceof IDiagramModelArchimateObject o) {
                return o;
            }
            if (child instanceof IDiagramModelContainer nested) {
                IDiagramModelArchimateObject found = searchObject(nested, id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private IDiagramModelConnection findConnection(IDiagramModelContainer container) {
        for (IDiagramModelObject child : container.getChildren()) {
            for (IDiagramModelConnection conn : child.getSourceConnections()) {
                if (CONNECTION_ID.equals(conn.getId())) {
                    return conn;
                }
            }
            if (child instanceof IDiagramModelContainer nested) {
                IDiagramModelConnection found = findConnection(nested);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // ==================== harness ====================

    private ArchiModelAccessorImpl newAccessor(StubEditorModelManager mgr, IArchimateModel target) {
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

    /** Minimal {@link IEditorModelManager} — only model registration and listener plumbing. */
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

        @SuppressWarnings("unused")
        void firePropertyChange(String propertyName, Object oldValue, Object newValue) {
            PropertyChangeEvent evt = new PropertyChangeEvent(this, propertyName, oldValue, newValue);
            for (PropertyChangeListener listener : new ArrayList<>(listeners)) {
                listener.propertyChange(evt);
            }
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
