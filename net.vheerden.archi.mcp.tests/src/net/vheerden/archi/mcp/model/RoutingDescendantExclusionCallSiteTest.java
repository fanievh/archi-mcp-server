package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

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
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.INode;

import net.vheerden.archi.mcp.response.dto.AddToViewResultDto;
import net.vheerden.archi.mcp.response.dto.AutoConnectResultDto;
import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;
import net.vheerden.archi.mcp.response.dto.FailedConnectionDto;

/**
 * One pin per site that builds a routing or label exclusion set, exercising the <em>caller</em>
 * rather than the helper it calls.
 *
 * <p>The exclusion helpers are transitive upward (every ancestor) and were once one level deep
 * downward (direct children only), so a node nested two levels inside an endpoint stayed in that
 * endpoint's own obstacle list. A helper-level test cannot see this: a correct helper that a call
 * site never reaches is invisible to it. Each test here therefore drives a real entry point over
 * real diagram objects and asserts on a value the caller publishes.</p>
 *
 * <p>The four sites and what each one's exclusion set feeds:</p>
 * <ol>
 *   <li>{@code buildTerminalsOnlyCommands} — the terminals-only <b>obstacle veto</b>. A phantom
 *       obstacle reverts a legitimate rectification and is reported as {@code vetoedByObstacle}.</li>
 *   <li>{@code buildOrthogonalRoutingCommands} — {@code ConnectionEndpoints.obstacles()}, i.e. the
 *       A* router itself. A phantom obstacle makes the connection unroutable, and in default
 *       (non-force) mode an unrouted connection has no command emitted at all: the route is
 *       discarded, not merely reported.</li>
 *   <li>the same method's {@code labelExcludeSets} → {@code LabelPositionOptimizer}. A phantom
 *       obstacle under the label makes the optimizer move a label that was never colliding.</li>
 *   <li>{@link LabelOptimizationPass#compute} — {@code labelExcludeSets} on the standalone label
 *       pass, whose own entry point collects the nodes.</li>
 * </ol>
 *
 * <p>Every fixture nests a <b>grandchild</b> inside an endpoint, because a one-level fix passes the
 * headline and still fails the shape this exists for. Real EMF via
 * {@link IArchimateFactory#eINSTANCE}, wired through the established accessor-test stub manager and
 * synchronous dispatcher. Fixture elements are named, so the real collector measures text — this
 * class realizes SWT and is filed with the display-lane classes.</p>
 */
public class RoutingDescendantExclusionCallSiteTest {

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private IArchimateModel model;
    private IArchimateDiagramModel view;

    /** View object id of the grandchild — two levels inside the connection's target. */
    private String grandchildViewObjectId;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ------------------------------------------------------------------
    // Site 1 — buildTerminalsOnlyCommands: the obstacle veto.
    // ------------------------------------------------------------------

    @Test
    public void terminalsOnlyObstacleVeto_doesNotRevertOverAGrandchildOfItsOwnTarget() {
        String connectionId = buildDiagonalToNestedTargetFixture();

        AutoRouteResultDto dto = accessor.autoRouteConnections(
                "default", "view-001", List.of(connectionId),
                "orthogonal", false, false, 20, 50, "terminals-only").entity();

        assertNotNull(dto);
        assertEquals("a node nested inside the connection's own target is not an unrelated"
                + " element, so the terminals-only obstacle veto must not fire on it",
                0, dto.vetoedByObstacle());
        assertEquals("and the rectification is applied rather than reverted — which also proves"
                + " the fixture proposed one, so the pin is not passing on an already-orthogonal"
                + " no-op", 1, dto.connectionsRouted());
        assertEquals("nothing is left skipped for any reason", 0, dto.connectionsSkipped());
    }

    // ------------------------------------------------------------------
    // Site 2 — buildOrthogonalRoutingCommands: the router obstacle set.
    // ------------------------------------------------------------------

    @Test
    public void orthogonalRouterObstacleSet_routesAConnectionTerminatingOnAPopulatedContainer() {
        String connectionId = buildNestedTargetFixture();

        AutoRouteResultDto dto = accessor.autoRouteConnections(
                "default", "view-001", List.of(connectionId),
                "orthogonal", false, false, 20, 50, null).entity();

        assertNotNull(dto);
        assertEquals("the target's own grandchild is not an obstacle to a connection"
                + " terminating on the target: " + describeFailures(dto),
                0, dto.connectionsFailed());
        assertEquals("and the route is applied, not discarded",
                1, dto.connectionsRouted());
    }

    // ------------------------------------------------------------------
    // Site 3 — buildOrthogonalRoutingCommands: labelExcludeSets.
    // ------------------------------------------------------------------

    @Test
    public void orthogonalLabelExcludeSets_doNotCountAGrandchildOfAnEndpointAsALabelObstacle() {
        String connectionId = buildLabelOverGrandchildFixture();

        AutoRouteResultDto dto = accessor.autoRouteConnections(
                "default", "view-001", List.of(connectionId),
                "orthogonal", false, false, 20, 50, null).entity();

        assertNotNull(dto);
        assertEquals("the only element under the label is the source's own grandchild, which the"
                + " label exclusion set covers, so there is no collision to optimize away",
                0, dto.labelsOptimized());
    }

    // ------------------------------------------------------------------
    // Site 4 — LabelOptimizationPass.compute: labelExcludeSets on the standalone pass.
    // ------------------------------------------------------------------

    @Test
    public void labelOptimizationPass_doesNotCountAGrandchildOfAnEndpointAsALabelObstacle() {
        buildLabelOverGrandchildFixture();

        LabelOptimizationPass.Result result = LabelOptimizationPass.compute(view, 8);

        assertNull("the standalone label pass builds its exclusion sets from the same helper;"
                + " with only the source's own grandchild under the label there is nothing to"
                + " move, so the pass must report no improvement",
                result);
    }

    // ------------------------------------------------------------------
    // Fixtures.
    // ------------------------------------------------------------------

    /**
     * The reported shape: source outside, target a populated container whose centre lies inside a
     * grandchild. Absolute canvas rectangles —
     * source (0, 275, 120x55); target (200, 100, 600x400), centre (500, 300);
     * child (250, 150, 500x300); grandchild (400, 250, 200x100), which contains (500, 300).
     */
    private String buildNestedTargetFixture() {
        buildModelAndView("descendant-obstacle-nested-target");

        INode dc = createNode("node-dc", "Direct Connect");
        INode region = createNode("node-region", "Cloud Region");
        INode az = createNode("node-az", "Availability Zone");
        INode aurora = createNode("node-aurora", "Database Cluster");

        IArchimateRelationship assoc = factory.createAssociationRelationship();
        assoc.setId("rel-dc-region");
        assoc.setName("connects");
        assoc.connect(dc, region);
        model.getFolder(FolderType.RELATIONS).getElements().add(assoc);

        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        place("node-dc", 0, 275, 120, 55, null);
        String regionId = place("node-region", 200, 100, 600, 400, null);
        String azId = place("node-az", 50, 50, 500, 300, regionId);
        grandchildViewObjectId = place("node-aurora", 150, 100, 200, 100, azId);

        return connectAndReturnConnectionId();
    }

    /**
     * The same nested target, approached diagonally from below so terminals-only actually proposes
     * a rectification instead of finding the terminals already orthogonal. The rectified L-bend's
     * final segment runs up into the target and crosses the grandchild on the way.
     */
    private String buildDiagonalToNestedTargetFixture() {
        buildModelAndView("descendant-obstacle-diagonal-target");

        INode dc = createNode("node-dc", "Direct Connect");
        INode region = createNode("node-region", "Cloud Region");
        INode az = createNode("node-az", "Availability Zone");
        INode aurora = createNode("node-aurora", "Database Cluster");

        IArchimateRelationship assoc = factory.createAssociationRelationship();
        assoc.setId("rel-dc-region");
        assoc.setName("connects");
        assoc.connect(dc, region);
        model.getFolder(FolderType.RELATIONS).getElements().add(assoc);

        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        place("node-dc", 0, 600, 120, 55, null);
        String regionId = place("node-region", 200, 100, 600, 400, null);
        String azId = place("node-az", 50, 50, 500, 300, regionId);
        grandchildViewObjectId = place("node-aurora", 150, 100, 200, 100, azId);

        return connectAndReturnConnectionId();
    }

    /**
     * A label fixture: a long horizontal connection between two far-apart elements, with a
     * grandchild of the SOURCE parked where the label renders. Nothing else is near the label, so
     * the optimizer's only possible reason to move it is the grandchild.
     *
     * <p><b>The geometry here is load-bearing and its margin is thin — measure before you edit
     * it.</b> Source centre is (450, 400) and target centre (1160, 402), so the straight
     * source-to-target midpoint is x≈805, which is just <em>past</em> the grandchild's right edge at
     * x=800. What puts the grandchild under the label is the label <em>rectangle</em>, whose width
     * comes from the relationship name — deliberately long for exactly that reason. Shorten that
     * name, or move any of these four boxes, and the label can clear the grandchild entirely, at
     * which point both label pins pass for the trivial reason that there is nothing under the label
     * at all and stop discriminating the defect they exist for.
     *
     * <p>That this fixture <em>does</em> discriminate is measured, not assumed: against the
     * pre-fix code the two pins it feeds failed with {@code labelsOptimized} = 1 and a non-null
     * {@code LabelOptimizationPass.Result}, and both fail again whenever the exclusion walk is
     * reverted to direct-children-only.</p>
     */
    private String buildLabelOverGrandchildFixture() {
        buildModelAndView("descendant-obstacle-label");

        INode host = createNode("node-host", "Hosting Node");
        INode inner = createNode("node-inner", "Inner Node");
        INode deep = createNode("node-deep", "Deep Node");
        INode remote = createNode("node-remote", "Remote Node");

        IArchimateRelationship assoc = factory.createAssociationRelationship();
        assoc.setId("rel-host-remote");
        assoc.setName("a deliberately long relationship label");
        assoc.connect(host, remote);
        model.getFolder(FolderType.RELATIONS).getElements().add(assoc);

        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Source spans the left half; its grandchild sits on the right edge, under the midpoint of
        // the source-centre -> target-centre segment.
        String hostId = place("node-host", 0, 200, 900, 400, null);
        String innerId = place("node-inner", 50, 50, 800, 300, hostId);
        grandchildViewObjectId = place("node-deep", 550, 100, 200, 100, innerId);
        place("node-remote", 1100, 375, 120, 55, null);

        return connectAndReturnConnectionId();
    }

    private void buildModelAndView(String modelId) {
        model = factory.createArchimateModel();
        model.setName(modelId);
        model.setId(modelId);
        model.setDefaults();

        view = factory.createArchimateDiagramModel();
        view.setId("view-001");
        view.setName("Routing View");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
    }

    private INode createNode(String id, String name) {
        INode node = factory.createNode();
        node.setId(id);
        node.setName(name);
        model.getFolder(FolderType.TECHNOLOGY).getElements().add(node);
        return node;
    }

    /** Places one element and returns the view object id the accessor actually created. */
    private String place(String elementId, int x, int y, int w, int h, String parentViewObjectId) {
        AddToViewResultDto added = accessor.addToView("default", "view-001", elementId,
                x, y, w, h, false, parentViewObjectId, null, null).entity();
        assertNotNull("fixture placement must succeed for " + elementId, added);
        return added.viewObject().viewObjectId();
    }

    private String connectAndReturnConnectionId() {
        AutoConnectResultDto connected = accessor.autoConnectView(
                "default", "view-001", null, null, null, null, null).entity();
        assertEquals("fixture must yield exactly one drawn connection",
                1, connected.connectionsCreated());
        String connectionId = findFirstConnectionId(view);
        assertNotNull("fixture must expose a real connection id to target", connectionId);
        return connectionId;
    }

    private String findFirstConnectionId(IArchimateDiagramModel diagram) {
        return findFirstConnectionId(diagram.getChildren());
    }

    private String findFirstConnectionId(List<?> children) {
        for (Object child : children) {
            if (child instanceof IDiagramModelObject dmo) {
                for (IDiagramModelConnection conn : dmo.getSourceConnections()) {
                    return conn.getId();
                }
            }
            if (child instanceof IDiagramModelContainer container) {
                String nested = findFirstConnectionId(container.getChildren());
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private String describeFailures(AutoRouteResultDto dto) {
        StringBuilder sb = new StringBuilder("failed=[");
        for (FailedConnectionDto f : dto.failed()) {
            sb.append(f.constraintViolated())
              .append(" crossedElementId=").append(f.crossedElementId())
              .append(" (grandchild view object is ").append(grandchildViewObjectId).append(") ");
        }
        return sb.append(']').toString();
    }

    // ------------------------------------------------------------------
    // Test plumbing — mirror of the established accessor-test pattern.
    // ------------------------------------------------------------------

    private ArchiModelAccessorImpl createAccessorWithTestDispatcher(IArchimateModel testModel) {
        MutationDispatcher testDispatcher = new MutationDispatcher(() -> testModel) {
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
        return new ArchiModelAccessorImpl(stubModelManager, testDispatcher);
    }

    private static class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final List<PropertyChangeListener> listeners = new ArrayList<>();

        void setModels(List<IArchimateModel> models) {
            this.models = models;
        }

        @Override
        public List<IArchimateModel> getModels() { return models; }

        @Override
        public void addPropertyChangeListener(PropertyChangeListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removePropertyChangeListener(PropertyChangeListener listener) {
            listeners.remove(listener);
        }

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
