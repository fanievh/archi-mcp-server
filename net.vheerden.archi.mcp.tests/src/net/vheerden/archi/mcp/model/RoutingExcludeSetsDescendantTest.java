package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.model.routing.RoutingResult;

/**
 * The exclusion rule itself, headless.
 *
 * <p>A connection must not treat its own endpoints' surrounding hierarchy as blocking obstacles.
 * That duty was transitive in one direction only: {@code ancestorIds} walks the whole parent chain
 * upward, while its sibling stopped at direct children going downward. A node two levels inside an
 * endpoint therefore stayed in that endpoint's own obstacle list, and a connection terminating on a
 * populated container was reported unroutable — in default mode no command is emitted for a failed
 * connection, so the route was discarded rather than merely reported.</p>
 *
 * <p>The fixture is the reported shape: a source outside, a target whose <em>centre</em> lies inside
 * its own grandchild. Nodes are constructed directly rather than collected from EMF, which is what
 * keeps this class in the default lane; the four call sites that build these sets are pinned
 * separately over real diagram objects, because a correct rule that a caller does not reach is
 * invisible to a test at this level.</p>
 */
public class RoutingExcludeSetsDescendantTest {

    // Absolute canvas rectangles, all plain elements (isContainer=false, so all obstacle-class).
    private static final AssessmentNode DC = node("dc", 0, 275, 120, 55, null);
    private static final AssessmentNode REGION = node("region", 200, 100, 600, 400, null);
    private static final AssessmentNode AZ = node("az", 250, 150, 500, 300, "region");
    private static final AssessmentNode AURORA = node("aurora", 400, 250, 200, 100, "az");
    /** Unrelated to either endpoint and squarely on the straight line dc -> region. */
    private static final AssessmentNode FOREIGN = node("foreign", 150, 250, 40, 100, null);
    /** A transit target: neither it nor dc is anywhere in the region hierarchy. */
    private static final AssessmentNode FAR = node("far", 900, 280, 120, 55, null);

    private static final List<AssessmentNode> HIERARCHY = List.of(DC, REGION, AZ, AURORA);

    // ------------------------------------------------------------------
    // DEPTH — both levels, on both ends.
    // ------------------------------------------------------------------

    @Test
    public void descendantIds_returnsTheDirectChildAndTheGrandchild() {
        assertEquals("the walk is transitive downward, not one level deep",
                Set.of("az", "aurora"),
                RoutingExcludeSets.descendantIds("region", HIERARCHY));
    }

    @Test
    public void descendantIds_isTransitiveOnTheSourceEndToo() {
        // Same hierarchy, read from the other end: dc is the source and carries its own nesting.
        List<AssessmentNode> nodes = new ArrayList<>(HIERARCHY);
        nodes.add(node("edge", 20, 285, 60, 20, "dc"));
        nodes.add(node("port", 25, 290, 20, 10, "edge"));

        assertEquals("the source's own grandchild is exempt for the same reason the target's is",
                Set.of("edge", "port"),
                RoutingExcludeSets.descendantIds("dc", nodes));
    }

    @Test
    public void obstacleSet_omitsBothLevelsOfBothEndpointsHierarchies() {
        List<AssessmentNode> nodes = new ArrayList<>(HIERARCHY);
        nodes.add(node("edge", 20, 285, 60, 20, "dc"));
        nodes.add(node("port", 25, 290, 20, 10, "edge"));

        Set<String> obstacles = obstacleIds(nodes, "dc", "region");

        assertFalse("the target's direct child is not an obstacle", obstacles.contains("az"));
        assertFalse("nor is the target's grandchild — the reported shape",
                obstacles.contains("aurora"));
        assertFalse("nor the source's direct child", obstacles.contains("edge"));
        assertFalse("nor the source's grandchild", obstacles.contains("port"));
        assertTrue("and the set is empty here, because every remaining node belongs to one of the"
                + " two endpoints: " + obstacles, obstacles.isEmpty());
    }

    @Test
    public void descendantIds_isEmptyForSeedsThatSelectNothing() {
        // A seed absent from the list, and a seed that is null, both select nothing rather than
        // throwing — the helper is called with an endpoint id the collector may not have produced a
        // node for, and an exclusion set that blew up there would take the whole routing pass with
        // it. The null-parentId nodes in HIERARCHY are what make the second case non-trivial: the
        // walk must not treat "no parent" as "parented by null" and sweep every top-level node in.
        assertTrue("a seed with no node in the list selects nothing",
                RoutingExcludeSets.descendantIds("no-such-node", HIERARCHY).isEmpty());
        assertTrue("a null seed selects nothing — top-level nodes are not null's children",
                RoutingExcludeSets.descendantIds(null, HIERARCHY).isEmpty());
        assertTrue("an empty node list selects nothing",
                RoutingExcludeSets.descendantIds("region", List.of()).isEmpty());
    }

    @Test
    public void descendantIds_isUnaffectedByADuplicatedNodeEntry() {
        // The collector walks EMF containment and should not produce the same id twice, but the
        // helper takes a plain List and cannot enforce that. A duplicate must not double-count or
        // change the result — the returned Set is the contract.
        List<AssessmentNode> withDuplicate = new ArrayList<>(HIERARCHY);
        withDuplicate.add(AZ);
        withDuplicate.add(AURORA);

        assertEquals("a repeated node entry changes nothing — the result is a set",
                Set.of("az", "aurora"),
                RoutingExcludeSets.descendantIds("region", withDuplicate));
    }

    @Test
    public void descendantIds_terminatesOnACyclicParentGraph() {
        // A hand-built parent cycle: a -> b -> a. A naive recursion would not return.
        List<AssessmentNode> cyclic = List.of(
                node("a", 0, 0, 10, 10, "b"),
                node("b", 0, 0, 10, 10, "a"));

        assertEquals("a fixed-point walk visits each node once and stops",
                Set.of("a", "b"), RoutingExcludeSets.descendantIds("a", cyclic));
    }

    // ------------------------------------------------------------------
    // NEGATIVE — the check is widened, not deleted.
    // ------------------------------------------------------------------

    @Test
    public void obstacleSet_stillContainsAnUnrelatedElementOnTheDirectPath() {
        List<AssessmentNode> nodes = new ArrayList<>(HIERARCHY);
        nodes.add(FOREIGN);

        Set<String> obstacles = obstacleIds(nodes, "dc", "region");

        assertEquals("an element belonging to neither endpoint's hierarchy is still handed to the"
                + " pipeline as an obstacle — the exemption is a widening of the exclusion rule,"
                + " not a removal of the check",
                Set.of("foreign"), obstacles);
    }

    // ------------------------------------------------------------------
    // TRANSIT — the exemption is scoped to the endpoints, not to nesting in general.
    // ------------------------------------------------------------------

    @Test
    public void obstacleSet_retainsANestedHierarchyThatIsNeitherEndpoint() {
        List<AssessmentNode> nodes = new ArrayList<>(HIERARCHY);
        nodes.add(FAR);

        Set<String> obstacles = obstacleIds(nodes, "dc", "far");

        assertEquals("a connection that merely passes through a hierarchy it does not terminate on"
                + " keeps every level of it as an obstacle",
                Set.of("region", "az", "aurora"), obstacles);
    }

    // ------------------------------------------------------------------
    // The behaviour the rule exists for, end to end through the router.
    // ------------------------------------------------------------------

    @Test
    public void routeAllConnections_routesToATargetWhoseCentreLiesInsideItsOwnGrandchild() {
        Set<String> obstacleIds = obstacleIds(HIERARCHY, "dc", "region");
        List<RoutingRect> obstacles = new ArrayList<>();
        for (AssessmentNode n : HIERARCHY) {
            if (obstacleIds.contains(n.id())) {
                obstacles.add(rect(n));
            }
        }

        RoutingPipeline.ConnectionEndpoints conn = new RoutingPipeline.ConnectionEndpoints(
                "conn-1", rect(DC), rect(REGION), obstacles, "", 0);

        List<RoutingRect> allObstacles = new ArrayList<>();
        for (AssessmentNode n : HIERARCHY) {
            allObstacles.add(rect(n));
        }

        RoutingResult result = new RoutingPipeline()
                .routeAllConnections(List.of(conn), allObstacles);

        assertEquals("the connection routes: " + result.failed(), 0, result.failed().size());
        assertEquals("and a route is produced for it", 1, result.routed().size());
    }

    // ------------------------------------------------------------------
    // One definition of "descendants" in this package, not two.
    // ------------------------------------------------------------------

    /**
     * Guards against a second definition of "descendants" being re-introduced into this package.
     *
     * <p><b>What this can and cannot detect, stated precisely.</b> The assessor's walk is now a
     * one-line delegate to the helper, so today this compares a delegate against its own target and
     * cannot fail while that delegation stands — it is a <em>regression guard</em>, not evidence
     * that two independent implementations currently agree. It earned the right to be here in two
     * steps that a reader cannot see from the source alone, so they are recorded here: it ran green
     * <em>before</em> the delegation was applied, while the assessor still carried its own separate
     * body, which is the only moment at which two real implementations could be observed agreeing;
     * and it was then proved to discriminate by restoring a divergent direct-children-only body in
     * the assessor, against which it fails with {@code expected:<[az]> but was:<[az, aurora]>}.
     * Re-introduce a second walk here and this goes red — which is the whole of its job.</p>
     */
    @Test
    public void descendantIds_agreesWithTheAssessorsOwnDescendantWalk() throws Exception {
        List<AssessmentNode> nodes = new ArrayList<>(HIERARCHY);
        nodes.add(FOREIGN);
        nodes.add(FAR);

        Method assessorWalk = LayoutQualityAssessor.class
                .getDeclaredMethod("getDescendantIds", String.class, List.class);
        assessorWalk.setAccessible(true);
        LayoutQualityAssessor assessor = new LayoutQualityAssessor();

        for (AssessmentNode seed : nodes) {
            @SuppressWarnings("unchecked")
            Set<String> viaAssessor = (Set<String>) assessorWalk.invoke(assessor, seed.id(), nodes);
            assertEquals("the assessor and the routing exclusion sets must not hold two different"
                    + " definitions of \"descendants\" — that disagreement is what this story fixed,"
                    + " seeded at " + seed.id(),
                    viaAssessor, RoutingExcludeSets.descendantIds(seed.id(), nodes));
        }
    }

    // ------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------

    /**
     * Builds the obstacle-id set the way {@code buildOrthogonalRoutingCommands} builds it: the two
     * endpoints, their ancestors and their descendants are excluded, and containers and notes never
     * reach the list at all (every node here is a plain element, so neither applies).
     */
    private static Set<String> obstacleIds(List<AssessmentNode> nodes, String srcId, String tgtId) {
        Map<String, AssessmentNode> nodeMap = new LinkedHashMap<>();
        for (AssessmentNode n : nodes) {
            nodeMap.put(n.id(), n);
        }
        Set<String> excludeIds = new HashSet<>();
        excludeIds.add(srcId);
        excludeIds.add(tgtId);
        excludeIds.addAll(RoutingExcludeSets.ancestorIds(srcId, nodeMap));
        excludeIds.addAll(RoutingExcludeSets.ancestorIds(tgtId, nodeMap));
        excludeIds.addAll(RoutingExcludeSets.descendantIds(srcId, nodes));
        excludeIds.addAll(RoutingExcludeSets.descendantIds(tgtId, nodes));

        Set<String> obstacles = new HashSet<>();
        for (AssessmentNode n : nodes) {
            if (!excludeIds.contains(n.id())) {
                obstacles.add(n.id());
            }
        }
        return obstacles;
    }

    private static RoutingRect rect(AssessmentNode n) {
        return new RoutingRect((int) n.x(), (int) n.y(),
                (int) n.width(), (int) n.height(), n.id());
    }

    private static AssessmentNode node(String id, double x, double y, double w, double h,
            String parentId) {
        return new AssessmentNode(id, x, y, w, h, parentId, false, false,
                null, 0.0, null, null, 0.0, 0.0, 0.0);
    }
}
