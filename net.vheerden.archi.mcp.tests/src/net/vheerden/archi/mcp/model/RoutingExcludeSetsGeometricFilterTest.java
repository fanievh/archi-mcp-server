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
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * The geometric half of the exclusion rule, headless.
 *
 * <p>Ancestry selects the candidates; geometry decides which of them keep the exemption. The
 * exemption exists because a collision is <em>structurally unavoidable</em>: a connection
 * terminating on an endpoint ends at that endpoint's centre or edge, so anything lying between the
 * endpoint's boundary and that point cannot be routed around. A rectangle that does not touch the
 * endpoint at all can never be in that position — and Archi lets a user drag a nested child clear
 * of its parent, which is the entire reason {@code detectBoundaryViolations} exists. Selecting on
 * {@code parentId} alone therefore exempted a node sitting <em>anywhere on the canvas</em> from the
 * obstacle set of any connection terminating on one of its ancestors, and the router drew straight
 * through it while reporting the route clean.</p>
 *
 * <p>The predicate is strict overlap against the <b>endpoint's</b> rectangle, not containment and
 * not a per-link walk up the chain: a child a few pixels over its parent's edge is still in the way
 * of its own parent's connections, so partial overlap keeps the exemption. That is pinned here
 * beside the fully-disjoint case on purpose — it is the assertion that stops this fix from
 * re-opening the unroutable-container defect.</p>
 *
 * <p>Nodes are constructed directly rather than collected from EMF, which is what keeps this class
 * in the default lane; the call sites that build these sets are pinned separately over real diagram
 * objects.</p>
 */
public class RoutingExcludeSetsGeometricFilterTest {

    // ---- The measured fixture: a grandchild of the source dragged onto the direct path. ----
    private static final AssessmentNode SRC = node("src", 0, 300, 120, 55, null);
    private static final AssessmentNode TGT = node("tgt", 900, 300, 120, 55, null);
    /** A direct child sitting legitimately inside src. */
    private static final AssessmentNode PORT = node("port", 10, 310, 40, 20, "src");
    /** src's GRANDCHILD, dragged to the middle of the canvas — nowhere near src. */
    private static final AssessmentNode DEEP = node("deep", 400, 250, 200, 150, "port");

    private static final List<AssessmentNode> DRIFTED = List.of(SRC, TGT, PORT, DEEP);

    // ------------------------------------------------------------------
    // The defect: a descendant dragged clear of its endpoint was exempt from the obstacle set.
    // ------------------------------------------------------------------

    @Test
    public void obstacleSet_containsADescendantThatIsNowhereNearItsEndpoint() {
        Set<String> obstacles = obstacleIds(DRIFTED, "src", "tgt");

        assertTrue("a grandchild of the source dragged clear of it is a real element in the way,"
                + " not invisible interior content: " + obstacles, obstacles.contains("deep"));
        assertFalse("the direct child that is genuinely inside the source stays exempt",
                obstacles.contains("port"));
        assertEquals("and nothing else changes", Set.of("deep"), obstacles);
    }

    // ------------------------------------------------------------------
    // The consequence, end to end: the router no longer draws through it.
    // ------------------------------------------------------------------

    @Test
    public void routeAllConnections_doesNotDrawThroughADriftedGrandchild() {
        Set<String> obstacleIds = obstacleIds(DRIFTED, "src", "tgt");
        List<RoutingRect> obstacles = new ArrayList<>();
        List<RoutingRect> allObstacles = new ArrayList<>();
        for (AssessmentNode n : DRIFTED) {
            allObstacles.add(rect(n));
            if (obstacleIds.contains(n.id())) {
                obstacles.add(rect(n));
            }
        }

        RoutingPipeline.ConnectionEndpoints conn = new RoutingPipeline.ConnectionEndpoints(
                "conn-1", rect(SRC), rect(TGT), obstacles, "", 0);

        RoutingResult result = new RoutingPipeline()
                .routeAllConnections(List.of(conn), allObstacles);

        // Whether the connection routes or fails is a REPORTED outcome, not an assertion: a failure
        // here would be element_crossing telling the truth about a real element. What is asserted is
        // that no polyline the pipeline produced — routed or preserved-for-force — crosses deep.
        // Measured outcome when this pin was written: it still ROUTES (routed=1, failed=0). Before
        // the filter the path ran straight through deep at y=327; it now detours below it via
        // (60,460)->(960,460). Nothing became unroutable — the router simply saw the element.
        List<AbsoluteBendpointDto> path = result.routed().get("conn-1");
        if (path == null) {
            path = result.violatedRoutes().get("conn-1");
        }
        if (path != null) {
            assertFalse("the produced path passes through the drifted grandchild's rectangle,"
                    + " which is exactly the defect: bendpoints=" + path,
                    pathCrosses(path, SRC, TGT, DEEP));
        }
    }

    // ------------------------------------------------------------------
    // PARTIAL OVERLAP STAYS EXEMPT — side by side with the fully-disjoint case above.
    // ------------------------------------------------------------------

    @Test
    public void obstacleSet_stillOmitsADescendantThatMerelyOVERFLOWSItsEndpoint() {
        // spill hangs 180px past src's right edge and 85px below its bottom — an unambiguous
        // overflow, and a boundary violation under detectBoundaryViolations. It still strictly
        // overlaps src, so it is still between src and the connection's terminus and still exempt.
        // "Any overflow -> obstacle" is the policy this assertion exists to forbid: it would make a
        // populated container unroutable again.
        AssessmentNode spill = node("spill", 100, 340, 200, 100, "src");
        List<AssessmentNode> nodes = List.of(SRC, TGT, spill);

        assertTrue("a descendant that overflows its endpoint but still overlaps it keeps the"
                + " exemption — the predicate is overlap, not containment",
                obstacleIds(nodes, "src", "tgt").isEmpty());

        assertEquals("and the boundary detector does report it, which is what makes this the"
                + " discriminating case rather than a well-formed one",
                1, new LayoutQualityAssessor()
                        .detectBoundaryViolations(nodes, true).violationCount());
    }

    // ------------------------------------------------------------------
    // BOTH HALVES OF THE RULE: ancestors carry the same predicate, on both ends.
    // ------------------------------------------------------------------

    @Test
    public void ancestorIds_dropsAnAncestorThatNoLongerContainsTheEndpoint() {
        // "outer" is the parent of the source endpoint but has been dragged clear of it. A box that
        // does not contain the endpoint is not a box the connection has to cross.
        AssessmentNode outer = node("outer", 600, 20, 150, 90, null);
        AssessmentNode inner = node("inner", 0, 300, 120, 55, "outer");
        Map<String, AssessmentNode> map = nodeMap(List.of(outer, inner, TGT));

        assertEquals("a drifted 'parent' is not structurally unavoidable — it is an obstacle",
                Set.of(), RoutingExcludeSets.ancestorIds("inner", map));
    }

    @Test
    public void ancestorIds_keepsAnAncestorThatStillOverlapsTheEndpoint() {
        AssessmentNode outer = node("outer", 0, 275, 200, 120, null);
        AssessmentNode inner = node("inner", 20, 300, 120, 55, "outer");
        Map<String, AssessmentNode> map = nodeMap(List.of(outer, inner, TGT));

        assertEquals("a container the endpoint really sits in must stay exempt",
                Set.of("outer"), RoutingExcludeSets.ancestorIds("inner", map));
    }

    @Test
    public void obstacleSet_appliesTheAncestorRuleOnBOTHEndsOfAConnection() {
        AssessmentNode srcOuter = node("src-outer", 600, 20, 150, 90, null);
        AssessmentNode srcInner = node("src-inner", 0, 300, 120, 55, "src-outer");
        AssessmentNode tgtOuter = node("tgt-outer", 640, 620, 150, 90, null);
        AssessmentNode tgtInner = node("tgt-inner", 900, 300, 120, 55, "tgt-outer");
        List<AssessmentNode> nodes = List.of(srcOuter, srcInner, tgtOuter, tgtInner);

        assertEquals("both endpoints' drifted 'parents' are obstacles — one guarded half would"
                + " leave the two directions of one rule disagreeing about geometry",
                Set.of("src-outer", "tgt-outer"), obstacleIds(nodes, "src-inner", "tgt-inner"));
    }

    // ------------------------------------------------------------------
    // THE NO-OP PROOF: zero boundary violations => the sets do not move at all.
    // ------------------------------------------------------------------

    /**
     * Bounds the blast radius of this change exactly, rather than asserting it is small.
     *
     * <p>{@code detectBoundaryViolations} reports a violation whenever a child is not fully inside
     * its immediate parent. Zero violations therefore means every child is fully inside its parent,
     * hence — inductively up the chain — every descendant of an endpoint is fully inside that
     * endpoint and strictly overlaps it, and symmetrically every ancestor strictly overlaps the
     * endpoint. Both families survive the filter untouched, so {@code assess-layout}'s published
     * rating cannot move on a view it was not already reporting as broken.</p>
     *
     * <p><b>Caveat, and it is why the fixture's rectangles are all non-degenerate:</b> the induction
     * needs {@code width > 0} and {@code height > 0}. State the reason precisely, because the
     * obvious phrasing is wrong: it is <em>not</em> that a zero-area rectangle overlaps nothing —
     * measured against the real predicate, a zero-width and even a 0x0 rectangle lying strictly
     * inside another does satisfy {@code rectsOverlap}. The shape that breaks the induction is a
     * degenerate child <b>flush against its parent's edge</b>: {@code rectsOverlap} is a STRICT
     * interior test so it does not overlap, while {@code detectBoundaryViolations} uses {@code >}
     * on that same edge so it is not a violation either. Zero violations would then coexist with a
     * dropped exemption and the sets would move.</p>
     *
     * <p>That shape cannot arise from a real view: {@code AssessmentCollector} skips any child whose
     * bounds are {@code w <= 0 || h <= 0} before a node is ever constructed, logging a warning. So
     * the caveat's precondition is enforced upstream rather than assumed here — but it is enforced
     * <em>there</em>, not in {@code RoutingExcludeSets}, which is why it is written down.</p>
     */
    @Test
    public void theSetsAreUnchangedOnAViewWithZeroBoundaryViolations() {
        List<AssessmentNode> wellFormed = List.of(
                node("region", 200, 100, 600, 400, null),
                node("az", 250, 150, 500, 300, "region"),
                node("aurora", 400, 250, 200, 100, "az"),
                node("dc", 0, 275, 120, 55, null),
                node("edge", 20, 285, 60, 20, "dc"),
                node("far", 900, 280, 120, 55, null));

        assertEquals("the fixture must be well-formed for the proof to say anything",
                0, new LayoutQualityAssessor()
                        .detectBoundaryViolations(wellFormed, true).violationCount());

        Map<String, AssessmentNode> map = nodeMap(wellFormed);
        for (AssessmentNode seed : wellFormed) {
            assertEquals("descendants must be element-for-element what the unfiltered walk"
                    + " returned, seeded at " + seed.id(),
                    unfilteredDescendants(seed.id(), wellFormed),
                    RoutingExcludeSets.descendantIds(seed.id(), wellFormed));
            assertEquals("and so must ancestors, seeded at " + seed.id(),
                    unfilteredAncestors(seed.id(), map),
                    RoutingExcludeSets.ancestorIds(seed.id(), map));
        }
    }

    // ------------------------------------------------------------------
    // FAIL OPEN when the seed has no rectangle to compare against.
    // ------------------------------------------------------------------

    @Test
    public void aSeedWithNoNodeFailsOPENAndFiltersNothing() {
        // The helper is called with an endpoint id the collector may not have produced a node for,
        // while other nodes still name it as their parent. With no rectangle for the seed there is
        // nothing to compare against, and failing CLOSED would push an endpoint's real content back
        // into the obstacle set on exactly the path that has the least information — re-opening the
        // unroutable-container defect. So: no rectangle, no filter, today's behaviour.
        AssessmentNode orphanChild = node("orphan-child", 400, 250, 200, 150, "ghost");
        AssessmentNode orphanGrandchild = node("orphan-grandchild", 700, 700, 40, 40,
                "orphan-child");
        List<AssessmentNode> nodes = List.of(SRC, TGT, orphanChild, orphanGrandchild);

        assertEquals("nodes naming an absent seed as their parent come back unfiltered",
                Set.of("orphan-child", "orphan-grandchild"),
                RoutingExcludeSets.descendantIds("ghost", nodes));
        assertEquals("and the ancestor half fails open the same way",
                Set.of("ghost"), RoutingExcludeSets.ancestorIds("orphan-child", nodeMap(nodes)));
    }

    @Test
    public void aSeedAbsentFromTheMapReturnsEMPTYAncestorsRatherThanAnUnfilteredWalk() {
        // The asymmetry between the two halves' fail-open, pinned so nobody reads the class note's
        // "no filter" as "ancestors survive a missing seed". descendantIds seeds its walk from the
        // raw id and really does return an unfiltered subtree (the pin above). ancestorIds can only
        // walk upward FROM the seed's node, so an absent seed means the walk never started and the
        // unfiltered result is necessarily empty. Both statements are asserted here together,
        // because it is the contrast that is the fact — either one alone reads as the other's rule.
        AssessmentNode orphanChild = node("orphan-child", 400, 250, 200, 150, "ghost");
        List<AssessmentNode> nodes = List.of(SRC, TGT, orphanChild);

        assertEquals("nothing was collected, so there is nothing to leave unfiltered",
                Set.of(), RoutingExcludeSets.ancestorIds("ghost", nodeMap(nodes)));
        assertEquals("while the descendant half genuinely returns its unfiltered collection",
                Set.of("orphan-child"), RoutingExcludeSets.descendantIds("ghost", nodes));
    }

    // ------------------------------------------------------------------
    // BOTH WALKS TERMINATE ON A CYCLE.
    // ------------------------------------------------------------------

    /**
     * A missing cycle guard HANGS — it never throws, never allocates and never fails — so the
     * timeout is not decoration: without it the RED capture is a stalled harness rather than a
     * measurement. JUnit 4 runs a timed test on its own thread and fails it on expiry.
     */
    @Test(timeout = 2000)
    public void ancestorIds_terminatesOnACyclicParentGraph() {
        List<AssessmentNode> cyclic = List.of(
                node("a", 0, 0, 10, 10, "b"),
                node("b", 0, 0, 10, 10, "a"));

        assertEquals("the result set doubles as the visited set: a node already in it stops the"
                + " walk, matching the descendant half's convention",
                Set.of("a", "b"), RoutingExcludeSets.ancestorIds("a", nodeMap(cyclic)));
    }

    @Test(timeout = 2000)
    public void bothWalksStillTerminateWhenTheFilterREJECTSANodeOnTheCycle() {
        // The trap this pin exists for: if the geometric test is folded into the `add` condition —
        // which reads perfectly naturally — a rejected node never enters the visited set, so `add`
        // returns true again on the next revisit and the guard is defeated on exactly the input it
        // exists for. Traverse into an unfiltered visited set, THEN filter into the result.
        List<AssessmentNode> cyclic = List.of(
                node("a", 0, 0, 10, 10, "b"),
                node("b", 500, 500, 10, 10, "a"));

        assertEquals("b is disjoint from the seed and is filtered out, and the walk still stops",
                Set.of("a"), RoutingExcludeSets.ancestorIds("a", nodeMap(cyclic)));
        assertEquals("same on the descendant side", Set.of("a"),
                RoutingExcludeSets.descendantIds("a", cyclic));
    }

    @Test(timeout = 2000)
    public void ancestorIds_terminatesWhenEVERYNodeOnTheCycleIsFilteredOut() {
        // The fixture above is NOT sufficient on its own and this pin exists because a mutation
        // proved it: there, one node of the cycle still overlapped the seed, so it entered the
        // visited set and terminated the walk even with the filter folded into the `add` condition.
        // The trap bites only when the node that would have terminated the walk is itself rejected.
        // Here the seed sits outside a p1 <-> p2 cycle and BOTH cycle members are disjoint from it,
        // so a filter inside `add` never records anything and the walk spins forever.
        List<AssessmentNode> nodes = List.of(
                node("seed", 0, 0, 10, 10, "p1"),
                node("p1", 500, 500, 10, 10, "p2"),
                node("p2", 600, 600, 10, 10, "p1"));

        assertEquals("the traversal records both, the filter then rejects both, and it terminates",
                Set.of(), RoutingExcludeSets.ancestorIds("seed", nodeMap(nodes)));
    }

    // ------------------------------------------------------------------
    // FILTER AFTER TRAVERSAL, NOT DURING.
    // ------------------------------------------------------------------

    @Test
    public void aDriftedIntermediateDoesNotHideItsOwnOverlappingChild() {
        // mid has been dragged clear of src; leaf is mid's child and still sits over src. Pruning
        // during traversal would stop at mid and never reach leaf, so leaf would wrongly become an
        // obstacle to a connection terminating on src — a collision that IS structurally
        // unavoidable. The walk stays complete; only the collected set is filtered.
        AssessmentNode mid = node("mid", 400, 250, 200, 150, "src");
        AssessmentNode leaf = node("leaf", 30, 310, 60, 30, "mid");
        List<AssessmentNode> nodes = List.of(SRC, TGT, mid, leaf);

        assertEquals("the drifted intermediate is an obstacle, its overlapping child is not",
                Set.of("leaf"), RoutingExcludeSets.descendantIds("src", nodes));
        assertEquals("read from the obstacle set, the same statement",
                Set.of("mid"), obstacleIds(nodes, "src", "tgt"));
    }

    @Test
    public void aDriftedIntermediateAncestorDoesNotHideAnOverlappingGrandparent() {
        // The symmetric statement on the ancestor side: the chain is walked to the top, and each
        // ancestor is judged against the ENDPOINT rather than against its own child. Testing
        // per-link ("is each node inside its immediate parent") answers a different question and
        // gives incoherent results on a partially drifted chain.
        AssessmentNode top = node("top", 0, 275, 200, 120, null);
        AssessmentNode middle = node("middle", 600, 20, 150, 90, "top");
        AssessmentNode leaf = node("leaf", 20, 300, 120, 55, "middle");
        Map<String, AssessmentNode> map = nodeMap(List.of(top, middle, leaf));

        assertEquals("the drifted middle drops out, the grandparent the endpoint really sits in"
                + " stays exempt", Set.of("top"), RoutingExcludeSets.ancestorIds("leaf", map));
    }

    // ------------------------------------------------------------------
    // One definition of "ancestors" in this package, not two.
    // ------------------------------------------------------------------

    /**
     * Guards against a second definition of "ancestors" being re-introduced into this package — the
     * symmetric twin of {@code descendantIds_agreesWithTheAssessorsOwnDescendantWalk}.
     *
     * <p><b>What this can and cannot detect, stated precisely.</b> The assessor's walk is now a
     * one-line delegate to the helper, so today this compares a delegate against its own target and
     * cannot fail while that delegation stands — it is a <em>regression guard</em>, not evidence
     * that two independent implementations currently agree. It ran green <em>before</em> the
     * delegation was applied, while the assessor still carried its own separate body, which is the
     * only moment at which two real implementations could be observed agreeing. That run used the
     * WELL-FORMED half of the fixture below and nothing else, because on a drifted node the two
     * bodies were never equal — the geometric filter is precisely what the helper gained and the
     * assessor's copy never had.</p>
     *
     * <p>The drifted half was added afterwards, and it is what gives the pin teeth: with a
     * well-formed fixture alone, re-introducing an independent unfiltered walk in the assessor
     * survives undetected, because filtered and unfiltered agree by construction on a view with no
     * boundary violations. A mutation proved exactly that before this half existed.</p>
     */
    @Test
    public void ancestorIds_agreesWithTheAssessorsOwnAncestorWalk() throws Exception {
        List<AssessmentNode> nodes = List.of(
                // Well-formed: the half that ran green while two real bodies still existed.
                node("region", 200, 100, 600, 400, null),
                node("az", 250, 150, 500, 300, "region"),
                node("aurora", 400, 250, 200, 100, "az"),
                node("dc", 0, 275, 120, 55, null),
                node("far", 900, 280, 120, 55, null),
                // Drifted: the half that discriminates a delegate from a second definition.
                node("escapee", 1200, 1200, 40, 40, "far"),
                node("stranded", 1400, 1400, 40, 40, "escapee"));
        Map<String, AssessmentNode> map = nodeMap(nodes);

        Method assessorWalk = LayoutQualityAssessor.class
                .getDeclaredMethod("getAncestorIds", String.class, Map.class);
        assessorWalk.setAccessible(true);
        LayoutQualityAssessor assessor = new LayoutQualityAssessor();

        for (AssessmentNode seed : nodes) {
            @SuppressWarnings("unchecked")
            Set<String> viaAssessor = (Set<String>) assessorWalk.invoke(assessor, seed.id(), map);
            assertEquals("the assessor and the routing exclusion sets must not hold two different"
                    + " definitions of \"ancestors\", seeded at " + seed.id(),
                    viaAssessor, RoutingExcludeSets.ancestorIds(seed.id(), map));
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
        Map<String, AssessmentNode> map = nodeMap(nodes);
        Set<String> excludeIds = new HashSet<>();
        excludeIds.add(srcId);
        excludeIds.add(tgtId);
        excludeIds.addAll(RoutingExcludeSets.ancestorIds(srcId, map));
        excludeIds.addAll(RoutingExcludeSets.ancestorIds(tgtId, map));
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

    /** The pre-change descendant walk: {@code parentId} only, no rectangle. */
    private static Set<String> unfilteredDescendants(String nodeId, List<AssessmentNode> nodes) {
        Set<String> descendants = new HashSet<>();
        Set<String> frontier = new HashSet<>();
        frontier.add(nodeId);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (AssessmentNode node : nodes) {
                if (node.parentId() != null && frontier.contains(node.parentId())
                        && descendants.add(node.id())) {
                    frontier.add(node.id());
                    changed = true;
                }
            }
        }
        return descendants;
    }

    /** The pre-change ancestor walk: {@code parentId} only, no rectangle. */
    private static Set<String> unfilteredAncestors(String nodeId,
            Map<String, AssessmentNode> nodeMap) {
        Set<String> ancestors = new HashSet<>();
        AssessmentNode node = nodeMap.get(nodeId);
        while (node != null && node.parentId() != null && ancestors.add(node.parentId())) {
            node = nodeMap.get(node.parentId());
        }
        return ancestors;
    }

    /**
     * Segment-samples the drawn polyline (source centre -> bendpoints -> target centre) against a
     * node's rectangle, the way the filing probe measured it.
     */
    private static boolean pathCrosses(List<AbsoluteBendpointDto> bendpoints,
            AssessmentNode src, AssessmentNode tgt, AssessmentNode victim) {
        List<double[]> points = new ArrayList<>();
        points.add(new double[] {src.x() + src.width() / 2, src.y() + src.height() / 2});
        for (AbsoluteBendpointDto bp : bendpoints) {
            points.add(new double[] {bp.x(), bp.y()});
        }
        points.add(new double[] {tgt.x() + tgt.width() / 2, tgt.y() + tgt.height() / 2});

        for (int i = 0; i < points.size() - 1; i++) {
            double[] a = points.get(i);
            double[] b = points.get(i + 1);
            int samples = 400;
            for (int s = 0; s <= samples; s++) {
                double t = (double) s / samples;
                double x = a[0] + (b[0] - a[0]) * t;
                double y = a[1] + (b[1] - a[1]) * t;
                if (x > victim.x() && x < victim.x() + victim.width()
                        && y > victim.y() && y < victim.y() + victim.height()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<String, AssessmentNode> nodeMap(List<AssessmentNode> nodes) {
        Map<String, AssessmentNode> map = new LinkedHashMap<>();
        for (AssessmentNode n : nodes) {
            map.put(n.id(), n);
        }
        return map;
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
