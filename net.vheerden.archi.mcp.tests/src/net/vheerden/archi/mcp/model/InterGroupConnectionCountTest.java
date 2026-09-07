package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IGrouping;
import com.archimatetool.model.INode;

/**
 * The inter-group connection count must see both kinds of top-level container.
 *
 * <p>Two different Archi objects render as a labelled box holding other objects: a native view
 * group ({@link IDiagramModelGroup}) and an ArchiMate {@code Grouping} element, which is an
 * {@link IDiagramModelArchimateObject} carrying an {@link IGrouping} concept. The counter resolved
 * only the first, so a view whose zones are {@code Grouping} elements reported zero connections
 * crossing a zone boundary however many actually did.
 *
 * <p>That count is not a report. Both spacing tools derive {@code isConnected} from it and hand it
 * to {@link GroupSpacingHeuristic}, which selects the unconnected column when it is false — the
 * column that exists because a view with no crossing connections has no corridors to widen. A view
 * with 32 of them was therefore given the narrowest target in the table.
 *
 * <p>The walk itself had never been exercised by a test: the connection graph is reachable only
 * through real EMF containment, and the tools that consume the count commit through a command
 * stack that no automated lane can start. So the assertions here are on the counter and on the
 * heuristic it feeds, not through either tool.
 *
 * <p>Three shapes in one class, because the claim is a parity claim and is only legible as a set:
 * the {@code Grouping} zones that were miscounted, the native groups that were always counted
 * correctly, and the top-level hub whose zero is the right answer.
 */
public class InterGroupConnectionCountTest {

    /** Crossing connections in the two-zone fixtures. Above the heuristic's {@code >30} tier. */
    private static final int CROSSING = 32;

    /** Connections wholly inside one zone. Never counted — there is no boundary to cross. */
    private static final int INTERNAL = 2;

    /** The View A shape's measured count, reproduced here as a native-group control. */
    private static final int NATIVE_CROSSING = 28;

    // ==================== the defect ====================

    @Test
    public void shouldCountConnectionsCrossingTwoGroupingZones_whenZonesAreArchimateGroupings() {
        IArchimateDiagramModel view = groupingZonesFixture(CROSSING, INTERNAL);

        assertEquals("a connection between children of two different Grouping zones crosses a "
                + "top-level container boundary exactly as one between two native groups does",
                CROSSING,
                TopLevelGroupTargets.countInterGroupConnections(view));
    }

    // ==================== the column it selects ====================

    @Test
    public void shouldSelectTheConnectedColumn_whenGroupingZonesAreCrossed() {
        IArchimateDiagramModel view = groupingZonesFixture(CROSSING, INTERNAL);
        int interGroupConnectionCount = TopLevelGroupTargets.countInterGroupConnections(view);
        boolean isConnected = interGroupConnectionCount > 0;
        int connectionCount = CROSSING + INTERNAL;

        // Both the composed tool and its single-arm sibling now derive hasLargeHubs through
        // HubSpacingSignal, so "some element carries more than 6 connections" is the one meaning
        // either of them can have. No element in this fixture carries more than one, so the flag
        // is false for both and there is a single column left to assert. That the two tools cannot
        // diverge here is pinned by HubSpacingSignalDelegationTest, not by re-asserting the same
        // call twice.
        assertEquals("both spacing tools: " + connectionCount + " connections is the >30 tier, "
                + "and the crossed zones select the connected no-large-hubs column",
                120,
                GroupSpacingHeuristic.targetSpacingForConnectionCount(
                        connectionCount, isConnected, false));
    }

    @Test
    public void shouldSelectTheUnconnectedColumn_whenNothingCrossesAContainerBoundary() {
        // The column a genuine zero selects, driven end to end from the counter rather than from a
        // hardcoded false — otherwise this asserts only the heuristic table, which is already
        // pinned elsewhere, and would pass with the counter deleted.
        IArchimateDiagramModel view = topLevelHubFixture(CROSSING);
        boolean isConnected = TopLevelGroupTargets.countInterGroupConnections(view) > 0;

        assertEquals("nothing crosses a boundary on this view, so there are no corridors to widen "
                + "and the narrowest target in the table is the right answer — this is the column "
                + "the defect wrongly selected for a view with " + CROSSING + " crossings",
                60,
                GroupSpacingHeuristic.targetSpacingForConnectionCount(
                        CROSSING + INTERNAL, isConnected, false));
    }

    // ==================== the two controls ====================

    @Test
    public void shouldCountNativeGroupsExactlyAsBefore_whenZonesAreNativeViewGroups() {
        IArchimateDiagramModel view = nativeGroupsFixture(NATIVE_CROSSING, INTERNAL);

        assertEquals("the native-group path must be unchanged: the widened predicate adds a "
                + "container kind, it does not redefine the one that already resolved",
                NATIVE_CROSSING,
                TopLevelGroupTargets.countInterGroupConnections(view));
    }

    @Test
    public void shouldCountZero_whenEveryConnectionTerminatesOnATopLevelElement() {
        IArchimateDiagramModel view = topLevelHubFixture(CROSSING);

        assertEquals("a top-level element sits directly in the diagram model, so it is in no "
                + "container and a connection to it crosses no boundary — zero is correct here "
                + "and a widened predicate must not turn it into a count",
                0,
                TopLevelGroupTargets.countInterGroupConnections(view));
    }

    @Test
    public void shouldResolveToTheOutermostContainer_whenAContainerIsNestedInsideAContainer() {
        // The walk records the LAST target seen before the diagram model, so a Grouping nested in a
        // native band must resolve to the band. Two elements in two different Groupings inside ONE
        // band therefore cross no top-level boundary. Nothing pinned this before: every other
        // fixture is one level deep, where innermost and outermost are the same object and a walk
        // that returned either would look correct.
        IArchimateModel model = emptyModel();
        IArchimateDiagramModel view = viewOf(model);
        IDiagramModelObject band = addNativeGroup(view, "Band A", "nat-a");
        IDiagramModelObject innerOne = addGroupingZone(model, band, "Zone A", "grp-a");
        IDiagramModelObject innerTwo = addGroupingZone(model, band, "Zone B", "grp-b");
        IDiagramModelObject otherBand = addNativeGroup(view, "Band B", "nat-b");

        connect(addNode(model, innerOne, "Left", "l"), addNode(model, innerTwo, "Right", "r"),
                "conn-within-band");
        connect(addNode(model, innerOne, "Out", "o"), addNode(model, otherBand, "In", "i"),
                "conn-across-bands");

        assertEquals("both endpoints of the first connection resolve to the same outermost "
                + "container, so only the connection that leaves the band is counted",
                1,
                TopLevelGroupTargets.countInterGroupConnections(view));
        assertEquals("and the arrange-groups route must agree on the nested shape too",
                arrangeGroupsInterGroupConnectionCount(view),
                TopLevelGroupTargets.countInterGroupConnections(view));
    }

    // ==================== the second counter, as an oracle ====================

    @Test
    public void shouldAgreeWithTheArrangeGroupsCounter_onTheSameGroupingFixture() {
        IArchimateDiagramModel view = groupingZonesFixture(CROSSING, INTERNAL);

        assertEquals("arrange-groups sums the same named quantity by a different route — it maps "
                + "every descendant of each collected container to that container's id and never "
                + "tests a type. On the shapes the arrangement family produces, one view must not "
                + "hand an agent two values for one field name.",
                arrangeGroupsInterGroupConnectionCount(view),
                TopLevelGroupTargets.countInterGroupConnections(view));
    }

    @Test
    public void shouldAgreeWithTheArrangeGroupsCounter_onTheTopLevelHubFixture() {
        IArchimateDiagramModel view = topLevelHubFixture(CROSSING);

        assertEquals("the two routes must agree on the zero as well: an endpoint that resolves to "
                + "no container is excluded by both, so the negative control is a shared answer "
                + "rather than two coincidences",
                arrangeGroupsInterGroupConnectionCount(view),
                TopLevelGroupTargets.countInterGroupConnections(view));
    }

    /**
     * The shape the two routes were measured to disagree on: two zones drawn inside a host the
     * container predicate declines. The legacy route saw no container at all on this view and
     * summed zero; the surviving route resolves each endpoint to its own zone.
     *
     * <p>Asserted as a NUMBER as well as an agreement — an agreement pin alone is satisfied by
     * both routes answering zero, which is exactly the wrong answer they used to share.</p>
     */
    @Test
    public void shouldCountAcrossZonesNestedInANonTargetHost() {
        IArchimateDiagramModel view = hostedZonesFixture(CROSSING, INTERNAL);

        assertEquals("a connection between children of two zones crosses a zone boundary "
                + "whatever the zones are drawn inside",
                CROSSING, TopLevelGroupTargets.countInterGroupConnections(view));
        assertEquals("the legacy direct-children route reads this view as containerless",
                0, legacyDirectChildrenInterGroupCount(view));
    }

    /**
     * A connection drawn between the two zone BOXES is a connection to a zone, not one running
     * between two of them: a container is not inside itself, so neither endpoint resolves. The
     * complement of the case above, and the pair is what the published sentence about which
     * connections the default-spacing route counts has to describe.
     */
    @Test
    public void shouldNotCountAConnectionBetweenTwoContainerBoxes() {
        IArchimateModel model = emptyModel();
        IArchimateDiagramModel view = viewOf(model);
        IDiagramModelObject zoneA = addGroupingZone(model, view, "Zone A", "grp-a");
        IDiagramModelObject zoneB = addGroupingZone(model, view, "Zone B", "grp-b");
        connect(zoneA, zoneB, "conn-box-box");

        assertEquals("a box-to-box connection terminates ON a zone rather than crossing between "
                + "two, so it is not an inter-container connection",
                0, TopLevelGroupTargets.countInterGroupConnections(view));
        assertEquals("and the legacy route excluded it for the same reason — it mapped a "
                + "container's children and never the container",
                0, legacyDirectChildrenInterGroupCount(view));
    }

    /**
     * Restricting the count to a named subset attributes a connection only when BOTH endpoints
     * resolve inside that subset. Without the restriction {@code arrange-groups} would resolve a
     * default spacing from containers the call is not arranging.
     */
    @Test
    public void shouldCountOnlyWithinTheNamedSubset_whenTheCallIsRestricted() {
        IArchimateDiagramModel view = groupingZonesFixture(CROSSING, INTERNAL);
        IDiagramModelObject zoneA = view.getChildren().get(0);

        assertEquals("with one container named there is no second one to cross to",
                0,
                TopLevelGroupTargets.countInterGroupConnections(view, java.util.List.of(zoneA)));
        assertEquals("with both named the answer is the whole-view one",
                CROSSING,
                TopLevelGroupTargets.countInterGroupConnections(
                        view, TopLevelGroupTargets.collectOutermost(view)));
    }

    /**
     * The scalar count and the adjacency matrix must be two readings of one measurement. Summing
     * every weight has to reproduce the count exactly, or the number a response publishes and the
     * order the arrangement produces are describing different views again.
     */
    @Test
    public void shouldSumTheAdjacencyMatrixToTheScalarCount() {
        for (IArchimateDiagramModel view : java.util.List.of(
                groupingZonesFixture(CROSSING, INTERNAL),
                nativeGroupsFixture(NATIVE_CROSSING, INTERNAL),
                topLevelHubFixture(CROSSING),
                hostedZonesFixture(CROSSING, INTERNAL))) {
            int summed = 0;
            for (Map<String, Integer> perTarget : TopLevelGroupTargets
                    .interContainerWeights(view, TopLevelGroupTargets.collectOutermost(view))
                    .values()) {
                for (int weight : perTarget.values()) {
                    summed += weight;
                }
            }
            assertEquals("the matrix and the count must be one measurement",
                    TopLevelGroupTargets.countInterGroupConnections(view), summed);
        }
    }

    /** Two zones inside a {@code Node} host, wired the same way the top-level fixtures are. */
    private IArchimateDiagramModel hostedZonesFixture(int crossing, int internal) {
        IArchimateModel model = emptyModel();
        IArchimateDiagramModel view = viewOf(model);
        IDiagramModelObject host = addNode(model, view, "Cloud Region", "host-cr");
        IDiagramModelObject zoneA =
                addGroupingZone(model, (IDiagramModelContainer) host, "Zone A", "grp-a");
        IDiagramModelObject zoneB =
                addGroupingZone(model, (IDiagramModelContainer) host, "Zone B", "grp-b");
        wire(model, zoneA, zoneB, crossing, internal);
        return view;
    }

    /**
     * The count the pre-widening {@code arrange-groups} route produced: every descendant of the
     * view's own container children flattened into a lookup table, then summed. Kept as a
     * measurement of the route that was removed, so a claim about what it used to answer is read
     * from a run rather than from memory.
     */
    private int legacyDirectChildrenInterGroupCount(IArchimateDiagramModel view) {
        return TopLevelGroupTargets.countInterGroupConnections(
                view, TopLevelGroupTargets.collect(view));
    }


    /**
     * The count scoped to the view's own container children — the population
     * {@code arrange-groups} arranged before it learned to see past a host.
     *
     * <p>The two used to be separate implementations and the agreements below were checking that
     * they had not drifted. They are now one measurement over two container sets, and the
     * agreements check something sharper: that narrowing the set to the view's own children
     * changes no answer on a view that holds nothing deeper. It is not a tautology — the hosted
     * fixture above shows the two sets giving different numbers on the shape where they differ.</p>
     */
    private int arrangeGroupsInterGroupConnectionCount(IArchimateDiagramModel view) {
        return legacyDirectChildrenInterGroupCount(view);
    }

    // ==================== fixtures ====================

    /** Two ArchiMate {@code Grouping} zones, each holding its own nested hosts. */
    private IArchimateDiagramModel groupingZonesFixture(int crossing, int internal) {
        IArchimateModel model = emptyModel();
        IArchimateDiagramModel view = viewOf(model);
        IDiagramModelObject zoneA = addGroupingZone(model, view, "Zone A", "grp-a");
        IDiagramModelObject zoneB = addGroupingZone(model, view, "Zone B", "grp-b");
        wire(model, zoneA, zoneB, crossing, internal);
        return view;
    }

    /** Two native view groups, each holding its own nested hosts — the shape that always worked. */
    private IArchimateDiagramModel nativeGroupsFixture(int crossing, int internal) {
        IArchimateModel model = emptyModel();
        IArchimateDiagramModel view = viewOf(model);
        IDiagramModelObject bandA = addNativeGroup(view, "Band A", "nat-a");
        IDiagramModelObject bandB = addNativeGroup(view, "Band B", "nat-b");
        wire(model, bandA, bandB, crossing, internal);
        return view;
    }

    /**
     * Two zones whose every connection terminates on a hub drawn at the view's own top level, so
     * one endpoint of each resolves to no container at all.
     */
    private IArchimateDiagramModel topLevelHubFixture(int connections) {
        IArchimateModel model = emptyModel();
        IArchimateDiagramModel view = viewOf(model);
        IDiagramModelObject zoneA = addGroupingZone(model, view, "Zone A", "grp-a");
        IDiagramModelObject zoneB = addGroupingZone(model, view, "Zone B", "grp-b");
        IDiagramModelObject hub = addNode(model, view, "Shared Bus", "hub");

        for (int i = 0; i < connections; i++) {
            IDiagramModelObject zone = (i % 2 == 0) ? zoneA : zoneB;
            connect(addNode(model, zone, "Host " + i, "h" + i), hub, "conn-hub-" + i);
        }
        return view;
    }

    /**
     * {@code crossing} connections between children of the two containers and {@code internal}
     * connections wholly inside the first, so a route that counts every connection rather than
     * every crossing one is distinguishable from a correct one.
     */
    private void wire(IArchimateModel model, IDiagramModelObject left, IDiagramModelObject right,
            int crossing, int internal) {
        for (int i = 0; i < crossing; i++) {
            connect(addNode(model, left, "Left " + i, "l" + i),
                    addNode(model, right, "Right " + i, "r" + i),
                    "conn-cross-" + i);
        }
        for (int i = 0; i < internal; i++) {
            connect(addNode(model, left, "Inner A" + i, "ia" + i),
                    addNode(model, left, "Inner B" + i, "ib" + i),
                    "conn-internal-" + i);
        }
    }

    private IArchimateModel emptyModel() {
        IArchimateModel model = IArchimateFactory.eINSTANCE.createArchimateModel();
        model.setName("Inter-Group Connection Count Test");
        model.setId("model-igcc");
        model.setDefaults();
        return model;
    }

    private IArchimateDiagramModel viewOf(IArchimateModel model) {
        IArchimateDiagramModel view =
                IArchimateFactory.eINSTANCE.createArchimateDiagramModel();
        view.setId("view-igcc");
        view.setName("Zones View");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        return view;
    }

    /** An ArchiMate {@code Grouping} element placed on the view. */
    private IDiagramModelObject addGroupingZone(IArchimateModel model,
            IDiagramModelObject parent, String name, String id) {
        return addGroupingZone(model, (IDiagramModelContainer) parent, name, id);
    }

    private IDiagramModelObject addGroupingZone(IArchimateModel model,
            IDiagramModelContainer view, String name, String id) {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IGrouping concept = factory.createGrouping();
        concept.setId(id);
        concept.setName(name);
        model.getFolder(FolderType.OTHER).getElements().add(concept);

        IDiagramModelArchimateObject object = factory.createDiagramModelArchimateObject();
        object.setId("obj-" + id);
        object.setArchimateConcept(concept);
        object.setBounds(0, 0, 400, 300);
        view.getChildren().add(object);
        return object;
    }

    /** A native view group placed on the view. */
    private IDiagramModelObject addNativeGroup(
            IArchimateDiagramModel view, String name, String id) {
        IDiagramModelGroup group = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        group.setId("obj-" + id);
        group.setName(name);
        group.setBounds(0, 0, 400, 300);
        view.getChildren().add(group);
        return group;
    }

    /** A node drawn inside {@code parent}, which may be a container or the view itself. */
    private IDiagramModelObject addNode(IArchimateModel model, IDiagramModelObject parent,
            String name, String id) {
        return addNode(model, (IDiagramModelContainer) parent, name, id);
    }

    private IDiagramModelObject addNode(IArchimateModel model, IDiagramModelContainer parent,
            String name, String id) {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        INode concept = factory.createNode();
        concept.setId(id);
        concept.setName(name);
        model.getFolder(FolderType.TECHNOLOGY).getElements().add(concept);

        IDiagramModelArchimateObject object = factory.createDiagramModelArchimateObject();
        object.setId("obj-" + id);
        object.setArchimateConcept(concept);
        object.setBounds(10, 10, 120, 55);
        parent.getChildren().add(object);
        return object;
    }

    /**
     * A plain diagram connection rather than one wrapping an ArchiMate relationship: relationship
     * validation calls into Archi's static matrix, which needs an OSGi context this fixture does
     * not have, and the counter reads only the view's connection graph.
     */
    private void connect(IDiagramModelObject source, IDiagramModelObject target, String id) {
        IDiagramModelConnection connection =
                IArchimateFactory.eINSTANCE.createDiagramModelConnection();
        connection.setId(id);
        connection.connect(source, target);
    }
}
