package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.model.routing.TerminalEgressClearancePass;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * The off-face hug remedy: which branch the description takes (defer to auto-route, layout-bound, or
 * the router's view-wide pre-gate), what it names as the bound, and the move it prints.
 *
 * <p>The branch and the printed move are pinned against the router itself: every geometry here is
 * built once as assessor input and converted, the same way the routing caller builds its input, into
 * input for {@link TerminalEgressClearancePass}. {@code evaluate} is the oracle for the per-hug
 * search (it skips the view-wide pre-gate that {@code run} applies, so the two are tested separately:
 * {@code run(...).skippedByPreGate()} is the oracle for the pre-gate). Neither reproduces the
 * router's final whole-view keep/roll-back check, which the assessor also cannot see.</p>
 *
 * <p>Oracle geometries put the terminal exactly on the router's face line (face ±
 * {@code TERMINAL_FACE_MARGIN} = 4px) with a proper L-shaped route, or {@code tryEgress} returns null
 * before the search and the oracle would be green for the wrong reason;
 * {@link #oracleFixturesReachTheSearch} proves each one reaches it.</p>
 */
public class OffFaceCorridorShortfallTest {

    private final LayoutQualityAssessor assessor = new LayoutQualityAssessor();
    private final TerminalEgressClearancePass router = new TerminalEgressClearancePass();

    private static final String DEFERRED = "run auto-route-connections to lift it clear";
    private static final String LAYOUT_BOUND = "no lift the router can make clears it";
    private static final String PRE_GATED = "the router skips its lift pass on this view";

    // ---------------------------------------------------------------- fixtures

    enum Side { TOP, BOTTOM, LEFT, RIGHT }

    /** One view: nodes (layout and notes together), connections; the hug is connection "c1". */
    record Fixture(List<AssessmentNode> nodes, List<AssessmentConnection> connections, Side face) {
        Fixture with(AssessmentNode n) {
            List<AssessmentNode> ns = new ArrayList<>(nodes);
            ns.add(n);
            return new Fixture(ns, connections, face);
        }

        Fixture with(AssessmentConnection c) {
            List<AssessmentConnection> cs = new ArrayList<>(connections);
            cs.add(c);
            return new Fixture(nodes, cs, face);
        }
    }

    static AssessmentNode node(String id, double x, double y, double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, false, false, null, 0.0, null, null, 0.0, 0.0, 0.0);
    }

    static AssessmentNode named(String id, String name, String parentId, double x, double y, double w,
            double h) {
        return new AssessmentNode(id, x, y, w, h, parentId, false, false, name, 0.0, null, null, 0.0, 0.0,
                0.0);
    }

    static AssessmentNode group(String id, String name, double x, double y, double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, true, false, name, 0.0, null, null, 0.0, 0.0, 0.0);
    }

    static AssessmentConnection conn(String id, String src, String tgt, double[]... pts) {
        return new AssessmentConnection(id, src, tgt, List.of(pts), "", 1);
    }

    static double[] p(double x, double y) {
        return new double[] {x, y};
    }

    /**
     * A router-shaped hug on {@code face} of "src": the terminal sits 4px outside the face, the trunk
     * runs parallel to the face for 177px (TOP/BOTTOM) or 100px (LEFT/RIGHT), then turns
     * perpendicular to a terminal on "tgt". Nothing else on the view.
     */
    static Fixture hug(Side face) {
        return switch (face) {
            case BOTTOM -> new Fixture(List.of(node("src", 400, 50, 100, 60), node("tgt", 620, 300, 60, 50)),
                    List.of(conn("c1", "src", "tgt", p(450, 80), p(473, 114), p(650, 114), p(650, 296),
                            p(650, 325))), face);
            case TOP -> new Fixture(List.of(node("src", 400, 300, 100, 60), node("tgt", 620, 60, 60, 50)),
                    List.of(conn("c1", "src", "tgt", p(450, 330), p(473, 296), p(650, 296), p(650, 114),
                            p(650, 85))), face);
            case LEFT -> new Fixture(List.of(node("src", 400, 100, 100, 80), node("tgt", 200, 220, 60, 60)),
                    List.of(conn("c1", "src", "tgt", p(450, 140), p(396, 150), p(396, 250), p(264, 250),
                            p(230, 250))), face);
            case RIGHT -> new Fixture(List.of(node("src", 100, 100, 100, 80), node("tgt", 340, 220, 60, 60)),
                    List.of(conn("c1", "src", "tgt", p(150, 140), p(204, 150), p(204, 250), p(336, 250),
                            p(370, 250))), face);
        };
    }

    /**
     * A {@code depth}-deep, 80px-long block whose near edge is {@code d} px out from the departed face,
     * overlapping the trunk's span.
     */
    static AssessmentNode blockAt(Side face, String id, String name, String parentId, double d,
            double depth) {
        return switch (face) {
            case BOTTOM -> named(id, name, parentId, 500, 110 + d, 80, depth);
            case TOP -> named(id, name, parentId, 500, 300 - d - depth, 80, depth);
            case LEFT -> named(id, name, parentId, 400 - d - depth, 170, depth, 60);
            case RIGHT -> named(id, name, parentId, 200 + d, 170, depth, 60);
        };
    }

    static AssessmentNode blockAt(Side face, String id, double d, double depth) {
        return blockAt(face, id, null, null, d, depth);
    }

    /**
     * A neighbouring connection carrying one run parallel to a BOTTOM hug, {@code d} px below the face,
     * between endpoint boxes far off to the right (so neither end is ever a hug or an obstacle).
     */
    static Fixture withRunBelow(Fixture f, String id, double d) {
        double y = 110 + d;
        return f.with(node(id + "-s", 1500, 0, 40, 40)).with(node(id + "-t", 1600, 0, 40, 40))
                .with(conn(id, id + "-s", id + "-t", p(1520, 20), p(500, y), p(600, y), p(1620, 20)));
    }

    /** Vertical neighbour runs at {@code x0}, {@code x0+gap}, … far from the hug: the pre-gate's input. */
    static Fixture withVerticalRuns(Fixture f, double x0, double gap, int count) {
        Fixture out = f;
        for (int i = 0; i < count; i++) {
            String id = "v" + i;
            double x = x0 + i * gap;
            out = out.with(node(id + "-s", x - 20, -200, 10, 10)).with(node(id + "-t", x - 20, 1200, 10, 10))
                    .with(conn(id, id + "-s", id + "-t", p(x - 15, -195), p(x, 900), p(x, 1000),
                            p(x - 15, 1205)));
        }
        return out;
    }

    // ---------------------------------------------------------------- assessor side

    private List<String> descriptionsOf(Fixture f) {
        List<AssessmentNode> layout = new ArrayList<>();
        List<AssessmentNode> notes = new ArrayList<>();
        for (AssessmentNode n : f.nodes()) {
            (n.isNote() ? notes : layout).add(n);
        }
        return assessor.countOffFaceParallelTerminals(f.connections(), layout, notes, true).descriptions();
    }

    private String hugDescription(Fixture f) {
        List<String> descs = descriptionsOf(f);
        assertEquals("the fixture must produce exactly one hug description: " + descs, 1, descs.size());
        assertTrue("the hug is c1: " + descs.get(0), descs.get(0).startsWith("Connection 'c1' "));
        return descs.get(0);
    }

    private static String branchOf(String desc) {
        boolean deferred = desc.contains(DEFERRED);
        boolean layout = desc.contains(LAYOUT_BOUND);
        boolean preGated = desc.contains(PRE_GATED);
        assertEquals("exactly one branch per description: " + desc, 1,
                (deferred ? 1 : 0) + (layout ? 1 : 0) + (preGated ? 1 : 0));
        return deferred ? "deferred" : layout ? "layout" : "pre-gated";
    }

    private static final Pattern MOVE = Pattern.compile(
            "so move (it|'[^']*'(?: \\([^)]*\\))?) at least (\\d+)px (up|down|left|right)");
    private static final Pattern OWN_MOVE = Pattern.compile(
            "(?:, or|so) move this element at least (\\d+)px (up|down|left|right)");

    // ---------------------------------------------------------------- router side

    /**
     * The routing input the auto-route caller would build from the same view: every non-container
     * object is an edge obstacle; each connection's pass-through set drops notes, containers, its two
     * ends and their enclosing/enclosed objects; each path is the stored route without the centres.
     */
    private record RouterInput(List<RoutingPipeline.ConnectionEndpoints> endpoints,
            List<List<AbsoluteBendpointDto>> paths, List<RoutingRect> allObstacles) {}

    private static RoutingRect rect(AssessmentNode n) {
        return new RoutingRect((int) n.x(), (int) n.y(), (int) n.width(), (int) n.height(), n.id());
    }

    /** An end's rectangle; a 1px stand-in at the path's end point when a fixture leaves the end out. */
    private static RoutingRect endRect(AssessmentNode n, AssessmentConnection c, int pointIdx) {
        if (n != null) return rect(n);
        double[] q = c.pathPoints().get(pointIdx);
        return new RoutingRect((int) q[0], (int) q[1], 1, 1, null);
    }

    private static RouterInput routerInput(Fixture f) {
        Map<String, AssessmentNode> byId = new LinkedHashMap<>();
        for (AssessmentNode n : f.nodes()) byId.put(n.id(), n);
        List<RoutingRect> all = new ArrayList<>();
        for (AssessmentNode n : f.nodes()) {
            if (!n.isContainer()) all.add(rect(n));
        }
        List<RoutingPipeline.ConnectionEndpoints> eps = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        for (AssessmentConnection c : f.connections()) {
            Set<String> exclude = new HashSet<>();
            for (String end : List.of(c.sourceNodeId(), c.targetNodeId())) {
                exclude.add(end);
                exclude.addAll(RoutingExcludeSets.ancestorIds(end, byId));
                exclude.addAll(RoutingExcludeSets.descendantIds(end, f.nodes()));
            }
            List<RoutingRect> obstacles = new ArrayList<>();
            for (AssessmentNode n : f.nodes()) {
                if (!n.isNote() && !n.isContainer() && !exclude.contains(n.id())) obstacles.add(rect(n));
            }
            eps.add(new RoutingPipeline.ConnectionEndpoints(c.id(), endRect(byId.get(c.sourceNodeId()), c, 0),
                    endRect(byId.get(c.targetNodeId()), c, c.pathPoints().size() - 1), obstacles, "", 0));
            List<AbsoluteBendpointDto> route = new ArrayList<>();
            List<double[]> pts = c.pathPoints();
            for (int i = 1; i < pts.size() - 1; i++) {
                route.add(new AbsoluteBendpointDto((int) pts.get(i)[0], (int) pts.get(i)[1]));
            }
            paths.add(route);
        }
        return new RouterInput(eps, paths, all);
    }

    /** True when the router's per-hug search proposes a lift for c1 (pre-gate not applied). */
    private boolean routerLifts(Fixture f) {
        RouterInput in = routerInput(f);
        return router.evaluate(in.endpoints(), in.paths(), in.allObstacles()).stream()
                .anyMatch(pr -> pr.connectionId().equals("c1"));
    }

    private boolean routerPreGates(Fixture f) {
        RouterInput in = routerInput(f);
        List<List<AbsoluteBendpointDto>> copy = new ArrayList<>();
        for (List<AbsoluteBendpointDto> path : in.paths()) copy.add(new ArrayList<>(path));
        return router.run(in.endpoints(), copy, in.allObstacles()).skippedByPreGate();
    }

    /** Moves {@code id} (and whatever it encloses) {@code delta} px along the face's normal, {@code sign} outward. */
    private static Fixture shifted(Fixture f, String id, int delta, int sign, boolean ridesOnFace) {
        boolean vertical = f.face() == Side.TOP || f.face() == Side.BOTTOM;
        int away = (f.face() == Side.BOTTOM || f.face() == Side.RIGHT) ? 1 : -1;
        double s = sign * away * delta;
        Set<String> moved = new HashSet<>(RoutingExcludeSets.descendantIds(id, f.nodes()));
        moved.add(id);
        List<AssessmentNode> ns = new ArrayList<>();
        for (AssessmentNode n : f.nodes()) {
            if (!moved.contains(n.id())) {
                ns.add(n);
                continue;
            }
            ns.add(new AssessmentNode(n.id(), n.x() + (vertical ? 0 : s), n.y() + (vertical ? s : 0),
                    n.width(), n.height(), n.parentId(), n.isGroup(), n.isNote(), n.name(), 0.0, null, null,
                    0.0, 0.0, 0.0));
        }
        List<AssessmentConnection> cs = new ArrayList<>();
        for (AssessmentConnection c : f.connections()) {
            if (!ridesOnFace || !c.id().equals("c1")) {
                cs.add(c);
                continue;
            }
            // The hug's terminal and trunk ride on the moved face; its centre moves with the element.
            List<double[]> pts = new ArrayList<>();
            for (int i = 0; i < c.pathPoints().size(); i++) {
                double[] q = c.pathPoints().get(i).clone();
                if (i <= 2) q[vertical ? 1 : 0] += s;
                pts.add(q);
            }
            cs.add(new AssessmentConnection(c.id(), c.sourceNodeId(), c.targetNodeId(), pts, "", 1));
        }
        return new Fixture(ns, cs, f.face());
    }

    // ---------------------------------------------------------------- the oracle

    /** The geometries the branch oracle walks, by name. */
    private static Map<String, Fixture> oracleGeometries() {
        Map<String, Fixture> g = new LinkedHashMap<>();
        Fixture b = hug(Side.BOTTOM);
        g.put("thin element 9px out (jumpable)", b.with(blockAt(Side.BOTTOM, "blk", 9, 40)));
        g.put("neighbour run 9px out, nothing else", withRunBelow(b, "n1", 9));
        g.put("element 12px out, too deep to jump", b.with(blockAt(Side.BOTTOM, "blk", 12, 100)));
        g.put("element 13px out, too deep to jump", b.with(blockAt(Side.BOTTOM, "blk", 13, 100)));
        g.put("element 14px out (was a false 'cannot')", b.with(blockAt(Side.BOTTOM, "blk", 14, 40)));
        g.put("element 15px out", b.with(blockAt(Side.BOTTOM, "blk", 15, 40)));
        g.put("element 21px out", b.with(blockAt(Side.BOTTOM, "blk", 21, 40)));
        g.put("element flush on the face (jumpable)", b.with(blockAt(Side.BOTTOM, "blk", 0, 40)));
        g.put("element 9px out, too deep to jump", b.with(blockAt(Side.BOTTOM, "blk", 9, 100)));
        g.put("LEFT face, element 9px out, 91px deep", hug(Side.LEFT).with(blockAt(Side.LEFT, "blk", 9, 91)));
        g.put("both sides: run 3px out (inner side of k=8) + element 20px out",
                withRunBelow(b, "n1", 3).with(blockAt(Side.BOTTOM, "blk", 20, 100)));
        g.put("runs only, at 9/30/50px", withRunBelow(withRunBelow(withRunBelow(b, "n1", 9), "n2", 30), "n3", 50));
        g.put("stacked: two deep elements side by side, 9px out",
                b.with(named("blkA", null, null, 480, 119, 80, 100)).with(named("blkB", null, null, 570, 119, 70, 100)));
        for (Side s : Side.values()) {
            g.put(s + " face, element 9px out, too deep to jump", hug(s).with(blockAt(s, "blk", 9, 100)));
            g.put(s + " face, element flush on the face, too deep to jump", hug(s).with(blockAt(s, "blk", 0, 100)));
        }
        g.put("row neighbour wholly on the element's side, edge on the face line, + element 9px out",
                b.with(node("row", 520, 30, 60, 80)).with(blockAt(Side.BOTTOM, "blk", 9, 100)));
        g.put("fractional element 9.9px out, too deep to jump", b.with(named("blk", null, null, 500.6, 119.9,
                80, 100)));
        g.put("element beside the trunk's corner pierced by the route, nothing within reach", pierced());
        g.put("sliver overlapping the span by 5px, 5px out, + element 9px out", sliverAndBlock());
        return g;
    }

    @Test
    public void oracleFixturesReachTheSearch() {
        // A fixture whose terminal is off the router's face line never reaches findClearedOrthogonal:
        // tryEgress returns null and the oracle would read "no lift" for the wrong reason. With every
        // obstacle removed, each bare hug must be liftable by the router.
        for (Side s : Side.values()) {
            assertTrue("the bare " + s + " hug reaches the router's search and lifts", routerLifts(hug(s)));
            assertEquals("and the assessor agrees it is liftable", "deferred", branchOf(hugDescription(hug(s))));
        }
    }

    @Test
    public void branchAgreesWithTheRoutersOwnSearch_onEveryOracleGeometry() {
        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<String, Fixture> e : oracleGeometries().entrySet()) {
            String desc = hugDescription(e.getValue());
            String branch = branchOf(desc);
            boolean lifts = routerLifts(e.getValue());
            if (lifts != branch.equals("deferred")) {
                mismatches.add(e.getKey() + ": router lifts=" + lifts + ", assessor=" + branch + " :: " + desc);
            }
        }
        assertTrue("assessor branch disagrees with the router on:\n" + String.join("\n", mismatches),
                mismatches.isEmpty());
    }

    @Test
    public void everyPrintedMoveIsTheRoutersSmallest_onEveryOracleGeometry() {
        List<String> problems = new ArrayList<>();
        int printed = 0;
        for (Map.Entry<String, Fixture> e : oracleGeometries().entrySet()) {
            Fixture f = e.getValue();
            String desc = hugDescription(f);
            Matcher m = MOVE.matcher(desc);
            if (m.find()) {
                printed++;
                String id = m.group(1).equals("it") ? boundNodeId(desc) : idOf(m.group(1));
                int delta = Integer.parseInt(m.group(2));
                if (!routerLifts(shifted(f, id, delta, +1, false))) {
                    problems.add(e.getKey() + ": moving " + id + " " + delta + "px does not let the router lift");
                }
                if (delta > 1 && routerLifts(shifted(f, id, delta - 1, +1, false))) {
                    problems.add(e.getKey() + ": moving " + id + " " + (delta - 1) + "px already lets it lift");
                }
            }
            Matcher own = OWN_MOVE.matcher(desc);
            if (own.find()) {
                printed++;
                int delta = Integer.parseInt(own.group(1));
                if (!routerLifts(shifted(f, "src", delta, -1, true))) {
                    problems.add(e.getKey() + ": moving the hug's element " + delta + "px does not let it lift");
                }
                if (delta > 1 && routerLifts(shifted(f, "src", delta - 1, -1, true))) {
                    problems.add(e.getKey() + ": moving the hug's element " + (delta - 1) + "px already lifts");
                }
            }
        }
        assertTrue(String.join("\n", problems), problems.isEmpty());
        assertTrue("the oracle must actually check printed moves (found " + printed + ")", printed >= 8);
    }

    private static String idOf(String label) {
        Matcher m = Pattern.compile("\\(([^)]*)\\)$").matcher(label);
        return m.find() ? m.group(1) : label.substring(1, label.length() - 1);
    }

    private static String boundNodeId(String desc) {
        Matcher m = Pattern.compile("bounded by ('[^']*'(?: \\([^)]*\\))?) [0-9.]+px").matcher(desc);
        assertTrue("a move of 'it' must follow a named node bound: " + desc, m.find());
        return idOf(m.group(1));
    }

    // ---------------------------------------------------------------- the view-wide pre-gate

    @Test
    public void preGate_crowdedVerticalRuns_skipTheLift_evenWhereTheSearchWouldFindOne() {
        // The hug alone is liftable (element 21px out). Three vertical runs 5px apart elsewhere on the
        // view drop the 10th-percentile vertical gap to 5px, under the router's 8px pre-gate, so the
        // router never runs its lift pass on this view.
        Fixture f = withVerticalRuns(hug(Side.BOTTOM).with(blockAt(Side.BOTTOM, "blk", 21, 40)), 1000, 5, 3);
        assertTrue("oracle: the per-hug search alone would lift it", routerLifts(f));
        assertTrue("oracle: the router's pre-gate skips the whole pass", routerPreGates(f));
        String desc = hugDescription(f);
        assertEquals("the assessor reports the pre-gate, not a re-route: " + desc, "pre-gated", branchOf(desc));
        assertTrue("it states the measured gap and the floor: " + desc,
                desc.contains("(10th-percentile gap 5.0px, below 8px)"));
        assertTrue("it still names the nearest bound: " + desc,
                desc.contains("the corridor beside it is bounded by 'blk' 21.0px from the face"));
        assertTrue("and prescribes spreading the runs: " + desc,
                desc.contains("spread the parallel connection runs apart"));
        assertFalse("no move is printed under the pre-gate: " + desc, MOVE.matcher(desc).find());
    }

    @Test
    public void preGate_singleVarOff_runs10pxApart_restoreTheSearchBranch() {
        Fixture f = withVerticalRuns(hug(Side.BOTTOM).with(blockAt(Side.BOTTOM, "blk", 21, 40)), 1000, 10, 3);
        assertFalse("oracle: 10px apart passes the router's pre-gate", routerPreGates(f));
        assertEquals("so the search branch applies again", "deferred", branchOf(hugDescription(f)));
    }

    @Test
    public void preGate_atExactlyTheFloor_doesNotSkip() {
        Fixture f = withVerticalRuns(hug(Side.BOTTOM).with(blockAt(Side.BOTTOM, "blk", 21, 40)), 1000, 8, 3);
        assertFalse("oracle: a gap of exactly 8px is not below the pre-gate", routerPreGates(f));
        assertEquals("the assessor agrees", "deferred", branchOf(hugDescription(f)));
    }

    // ---------------------------------------------------------------- what the text says

    @Test
    public void layoutBound_namesTheBlockingElementItsDistanceAndTheMove() {
        Fixture f = hug(Side.BOTTOM).with(blockAt(Side.BOTTOM, "blk", "Kafka", null, 9, 100));
        String desc = hugDescription(f);
        assertTrue(desc, desc.endsWith("; no lift the router can make clears it — the corridor beside it is "
                + "bounded by 'Kafka' (blk) 9.0px from the face, so move it at least 4px down, or move this "
                + "element at least 4px up and re-route, then re-assess — the move is a lower bound, not a "
                + "promise."));
    }

    @Test
    public void elementJustUnderTheOldFifteenPxProxy_isNotReportedAsUnclearable() {
        // 13px out: the router lifts the run 8px (5px clear of the element's edge). The old proxy
        // said "re-routing cannot clear it" for anything under 15px.
        Fixture f = hug(Side.BOTTOM).with(blockAt(Side.BOTTOM, "blk", 13, 100));
        assertTrue("oracle", routerLifts(f));
        String desc = hugDescription(f);
        assertEquals(desc, "deferred", branchOf(desc));
        assertFalse(desc, desc.contains("cannot clear"));
    }

    @Test
    public void runsOnly_noOtherElementMoveOpensIt_soTheOwnElementMoveIsPrinted() {
        // No box bounds the corridor, so no other element's move can open it; moving the hug's own
        // element 14px off the runs can (the first run is then 23px out, 15px past an 8px lift).
        Fixture f = withRunBelow(withRunBelow(withRunBelow(hug(Side.BOTTOM), "n1", 9), "n2", 30), "n3", 50);
        assertFalse("oracle: no lift", routerLifts(f));
        assertTrue("oracle: moving the element 14px up lets it lift", routerLifts(shifted(f, "src", 14, -1, true)));
        assertFalse("oracle: 13px does not", routerLifts(shifted(f, "src", 13, -1, true)));
        String desc = hugDescription(f);
        assertTrue(desc, desc.endsWith("; no lift the router can make clears it — the corridor beside it is "
                + "bounded by connection 'n1' (n1-s → n1-t) 9.0px from the face, and moving any other one "
                + "element will not open it, so move this element at least 14px up and re-route, then "
                + "re-assess — the move is a lower bound, not a promise."));
    }

    @Test
    public void noBoundWithinReach_andNoMove_printsTheBareWidenClause() {
        Fixture f = pierced();
        assertFalse("oracle: no lift", routerLifts(f));
        String desc = hugDescription(f);
        assertTrue(desc, desc.endsWith("; no lift the router can make clears it — widen the corridor (increase "
                + "element spacing on the crowded side) and re-route."));
        assertFalse(desc, desc.contains("bounded by"));
    }

    /**
     * A BOTTOM hug whose route, after the trunk's corner, runs straight down through a small element
     * 90px below the face. Every lifted route keeps that segment, so the router finds no lift; the
     * element overlaps the trunk's span by only 5px and its nearer edge is beyond the search's reach,
     * so nothing bounds the corridor and no single move is found.
     */
    private static Fixture pierced() {
        return hug(Side.BOTTOM).with(node("pin", 645, 200, 10, 20));
    }

    @Test
    public void stackedElements_noSingleOtherMoveOpensIt_soOnlyTheOwnMoveIsPrinted() {
        Fixture f = hug(Side.BOTTOM).with(named("blkA", null, null, 480, 119, 80, 100))
                .with(named("blkB", null, null, 570, 119, 70, 100));
        assertFalse("oracle: no lift", routerLifts(f));
        for (String id : List.of("blkA", "blkB")) {
            for (int delta = 1; delta <= LayoutQualityAssessor.EGRESS_MAX_LIFT_PX; delta++) {
                assertFalse("oracle: moving " + id + " alone by " + delta + "px never opens it",
                        routerLifts(shifted(f, id, delta, +1, false)));
            }
        }
        String desc = hugDescription(f);
        assertTrue(desc, desc.contains("bounded by 'blkA' 9.0px from the face, and moving any other one element "
                + "will not open it, so move this element at least 4px up"));
        assertFalse("no other element's move is printed: " + desc, MOVE.matcher(desc).find());
    }

    @Test
    public void directionWords_onAllFourFaces() {
        Map<Side, String[]> words = Map.of(Side.TOP, new String[] {"up", "down"},
                Side.BOTTOM, new String[] {"down", "up"}, Side.LEFT, new String[] {"left", "right"},
                Side.RIGHT, new String[] {"right", "left"});
        for (Side s : Side.values()) {
            String desc = hugDescription(hug(s).with(blockAt(s, "blk", 9, 100)));
            assertTrue(s + ": " + desc, desc.contains("departs the " + s + " face"));
            assertTrue(s + ": " + desc, desc.contains("so move it at least 4px " + words.get(s)[0]
                    + ", or move this element at least 4px " + words.get(s)[1] + " and re-route"));
        }
    }

    @Test
    public void nestedBlockingElement_addsTheContainerClause() {
        Fixture f = hug(Side.BOTTOM).with(group("zone", "AZ eu-west-2b", 450, 115, 300, 250))
                .with(blockAt(Side.BOTTOM, "blk", "Kafka", "zone", 9, 100));
        String desc = hugDescription(f);
        assertTrue(desc, desc.contains("so move it at least 4px down (it sits inside 'AZ eu-west-2b' (zone) — "
                + "that container may need to grow to take the move), or move this element at least 4px up "
                + "and re-route"));
    }

    @Test
    public void topLevelBlockingElement_hasNoContainerClause() {
        String desc = hugDescription(hug(Side.BOTTOM).with(blockAt(Side.BOTTOM, "blk", "Kafka", null, 9, 100)));
        assertFalse(desc, desc.contains("it sits inside"));
    }

    @Test
    public void unnamedElement_isNamedByItsIdAlone() {
        String desc = hugDescription(hug(Side.BOTTOM).with(blockAt(Side.BOTTOM, "blk", 9, 100)));
        assertTrue(desc, desc.contains("bounded by 'blk' 9.0px from the face"));
    }

    @Test
    public void aNoteBoundsTheCorridorToo_becauseTheRouterClearsNotes() {
        // The router's edge-clearance set includes notes. A deep note 9px out blocks every lift the same
        // way an element does; the assessor must see it although notes are not layout nodes.
        AssessmentNode note = new AssessmentNode("memo", 500, 119, 80, 100, null, false, true, null, 0.0, null,
                null, 0.0, 0.0, 0.0);
        Fixture f = hug(Side.BOTTOM).with(note);
        // The router's pass-through set drops notes, but its edge set keeps them: edges at 9 and 109.
        assertEquals("oracle and assessor agree on a note-bounded corridor", routerLifts(f),
                branchOf(hugDescription(f)).equals("deferred"));
        assertTrue(hugDescription(f), hugDescription(f).contains("'memo'"));
    }

    @Test
    public void flushBound_onAnOutwardNegativeFace_printsZeroNotNegativeZero() {
        for (Side s : List.of(Side.TOP, Side.LEFT)) {
            String desc = hugDescription(hug(s).with(blockAt(s, "blk", 0, 100)));
            assertTrue(s + ": " + desc, desc.contains("bounded by 'blk' 0.0px from the face"));
            assertFalse(s + ": " + desc, desc.contains("-0.0px"));
        }
    }

    @Test
    public void neighbourWhollyOnTheElementsSide_isNotNamedAsTheBound() {
        // A box in the same row as the hug's element, its bottom edge on the departed face line, lies
        // entirely on the element's side: a lift never moves into it. The bound is the block 9px out.
        Fixture f = hug(Side.BOTTOM).with(node("row", 520, 30, 60, 80)).with(blockAt(Side.BOTTOM, "blk", 9, 100));
        String desc = hugDescription(f);
        assertTrue(desc, desc.contains("bounded by 'blk' 9.0px from the face, so move it at least 4px down"));
        assertFalse(desc, desc.contains("'row'"));
    }

    @Test
    public void preGate_appliesToAnUnmodellableRouteToo() {
        // The pre-gate is view-wide: on a view whose vertical runs are under 8px apart the router lifts
        // nothing, so even a single-bendpoint hug (which the per-hug search cannot model) is reported
        // as pre-gated rather than sent to auto-route.
        Fixture f = withVerticalRuns(new Fixture(List.of(node("src", 400, 50, 100, 59), node("tgt", 620, 85, 60, 50)),
                List.of(conn("c1", "src", "tgt", p(450, 79), p(473, 110), p(650, 110))), Side.BOTTOM), 1000, 5, 3);
        assertTrue("oracle: the router's pre-gate skips the pass", routerPreGates(f));
        String desc = hugDescription(f);
        assertEquals(desc, "pre-gated", branchOf(desc));
        assertFalse("no bound is fabricated for an unmodellable route: " + desc, desc.contains("bounded by"));
    }

    // ---------------------------------------------------------------- invariants

    /**
     * The eight remedy fixtures as they stood before the remedy was re-derived (1px stub, trunk end is
     * the last bendpoint). Whatever the remedy text now says about each, the hug decision and
     * everything rated from it must be exactly what it was: one hug, violator c1, the routing entry
     * capped at fair, coverage checked, the same prefix sentence, and the spread clause on exactly the
     * contested-face fixture.
     */
    private static Map<String, Fixture> legacyRemedyFixtures() {
        Map<String, Fixture> g = new LinkedHashMap<>();
        AssessmentConnection bottomHug = conn("c1", "src", "tgt", p(450, 79), p(473, 110), p(650, 110), p(650, 325));
        Fixture base = new Fixture(List.of(node("src", 400, 50, 100, 59), node("tgt", 620, 300, 60, 50)),
                List.of(bottomHug), Side.BOTTOM);
        g.put("obstacle 9px", base.with(node("block", 500, 118, 80, 40)));
        g.put("neighbour run 9px", base.with(conn("n2", "s2", "t2", p(500, 118), p(600, 118))));
        g.put("wide corridor 21px", base.with(node("block", 500, 130, 80, 40)));
        g.put("exactly 15px", base.with(node("block", 500, 124, 80, 40)));
        g.put("14px", base.with(node("block", 500, 123, 80, 40)));
        g.put("flush 0px", base.with(node("block", 500, 109, 80, 40)));
        AssessmentConnection leftHug = conn("c1", "src", "tgt", p(450, 140), p(398, 150), p(398, 250), p(402, 425));
        Fixture left = new Fixture(List.of(node("src", 400, 100, 100, 80), node("tgt", 372, 400, 60, 50),
                node("block", 300, 200, 91, 40)), List.of(leftHug), Side.LEFT);
        g.put("LEFT face 9px", left);
        g.put("contested LEFT face", left.with(conn("c2", "src", "far", p(450, 120), p(399, 120), p(350, 120),
                p(300, 120))));
        return g;
    }

    private static final String BOTTOM_PREFIX = "Connection 'c1' src → tgt: source terminal departs the BOTTOM "
            + "face then runs parallel to it (1.0px clearance, below 8px) — the route hugs the face it just exited; ";
    private static final String LEFT_PREFIX = "Connection 'c1' src → tgt: source terminal departs the LEFT "
            + "face then runs parallel to it (2.0px clearance, below 8px) — the route hugs the face it just exited; ";
    private static final String SPREAD = " (this face carries several connections, so widening alone will just "
            + "re-crowd it — spread them across the element's other faces, or give the element more room so its "
            + "face ports separate)";

    @Test
    public void remedyRederivation_leavesTheHugDecisionAndItsRatingUntouched() {
        for (Map.Entry<String, Fixture> e : legacyRemedyFixtures().entrySet()) {
            Fixture f = e.getValue();
            LayoutAssessmentResult r = assessor.assess(f.nodes(), f.connections(), true);
            String k = e.getKey();
            assertEquals(k + ": count", 1, r.offFaceParallelTerminalCount());
            assertEquals(k + ": violators", Set.of("c1"), r.violatorIds().get("offFaceParallelTerminals"));
            assertEquals(k + ": breakdown", "fair", r.ratingBreakdown().get("offFaceParallelTerminals"));
            assertEquals(k + ": coverage", LayoutQualityAssessor.COVERAGE_CHECKED,
                    r.coverage().get("offFaceParallelTerminals"));
            assertEquals(k + ": one description", 1, r.offFaceParallelTerminalDescriptions().size());
            String desc = r.offFaceParallelTerminalDescriptions().get(0);
            String prefix = f.face() == Side.LEFT ? LEFT_PREFIX : BOTTOM_PREFIX;
            assertTrue(k + ": prefix byte-identical: " + desc, desc.startsWith(prefix));
            assertEquals(k + ": spread clause iff the face is contested: " + desc,
                    k.startsWith("contested"), desc.contains(SPREAD));
        }
    }

    @Test
    public void legacyFixtures_areNotRouterShaped_soEveryOneDefers() {
        // None of these routes is one the router's lift pass acts on (the terminal is 1-2px off the
        // face, not on the router's face line, and the trunk's far end is the last bendpoint), so
        // re-routing replaces the route rather than lifting it and the assessor has no basis for
        // "cannot": every one defers, whatever lies beside it.
        for (Map.Entry<String, Fixture> e : legacyRemedyFixtures().entrySet()) {
            Fixture f = e.getValue();
            assertFalse(e.getKey() + ": oracle — the router does not act on this route", routerLifts(f));
            String desc = assessor.assess(f.nodes(), f.connections(), true).offFaceParallelTerminalDescriptions()
                    .get(0);
            assertEquals(e.getKey() + ": " + desc, "deferred", branchOf(desc));
        }
    }

    @Test
    public void unmodellableRoute_fallsBackToTheDeferredClause_neverAFabricatedBound() {
        // A 3-point path (centre, one bendpoint, centre) has no trunk end the router could move; the
        // assessor has no basis for "cannot", so it defers and names nothing.
        Fixture f = new Fixture(List.of(node("src", 400, 50, 100, 59), node("tgt", 620, 85, 60, 50),
                node("block", 500, 118, 80, 100)),
                List.of(conn("c1", "src", "tgt", p(450, 79), p(473, 110), p(650, 110))), Side.BOTTOM);
        List<String> descs = descriptionsOf(f);
        assertEquals(descs.toString(), 1, descs.size());
        assertEquals(descs.get(0), "deferred", branchOf(descs.get(0)));
        assertFalse(descs.get(0), descs.get(0).contains("bounded by"));
    }

    /**
     * A 2px-deep sliver 5px below a BOTTOM hug that overlaps the trunk's span by only 5px, nearer than a
     * deep block 9px out. The router's edge rule never reads a rectangle overlapping by under 10px, and
     * no lifted route touches the sliver, so it bounds nothing.
     */
    private static Fixture sliverAndBlock() {
        return hug(Side.BOTTOM).with(node("sliver", 645, 115, 10, 2)).with(blockAt(Side.BOTTOM, "blk", 9, 100));
    }

    @Test
    public void rectangleOverlappingTheSpanByUnderTenPx_isNotNamedAsTheBound() {
        Fixture f = sliverAndBlock();
        assertFalse("oracle: no lift", routerLifts(f));
        assertTrue("oracle: moving the block 4px opens it although the sliver stays",
                routerLifts(shifted(f, "blk", 4, +1, false)));
        String desc = hugDescription(f);
        assertTrue(desc, desc.contains("bounded by 'blk' 9.0px from the face, so move it at least 4px down"));
        assertFalse(desc, desc.contains("'sliver'"));
    }

    // ---------------------------------------------------------------- routes the router does not lift

    @Test
    public void terminalOffTheRoutersFaceLine_defersAndNamesNothing() {
        // The router acts only on a terminal exactly TERMINAL_FACE_MARGIN (4px) outside the face. At
        // any other offset the deep block 9px out would read as layout-bound if the search ran from
        // the face anyway; the router never runs it, so the assessor must not claim a bound.
        for (int offset : new int[] {0, 1, 2, 3, 5, 6, 7}) {
            double y = 110 + offset;
            Fixture f = new Fixture(List.of(node("src", 400, 50, 100, 60), node("tgt", 620, 300, 60, 50),
                    blockAt(Side.BOTTOM, "blk", 9, 100)),
                    List.of(conn("c1", "src", "tgt", p(450, 80), p(473, y), p(650, y), p(650, 296), p(650, 325))),
                    Side.BOTTOM);
            assertFalse(offset + "px: oracle — the router does not act on it", routerLifts(f));
            String desc = hugDescription(f);
            assertEquals(offset + "px: " + desc, "deferred", branchOf(desc));
            assertFalse(offset + "px: " + desc, desc.contains("bounded by"));
        }
        Fixture onLine = hug(Side.BOTTOM).with(blockAt(Side.BOTTOM, "blk", 9, 100));
        assertEquals("single-variable control: 4px out is the router's own shape", "layout",
                branchOf(hugDescription(onLine)));
    }

    @Test
    public void routeWithNoInteriorCorner_defersAndNamesNothing() {
        // Two bendpoints: the trunk's far end is the other terminal, which the router will not move.
        Fixture f = new Fixture(List.of(node("src", 400, 50, 100, 60), node("tgt", 620, 118, 60, 50),
                blockAt(Side.BOTTOM, "blk", 9, 100)),
                List.of(conn("c1", "src", "tgt", p(450, 80), p(473, 114), p(616, 114), p(650, 143))), Side.BOTTOM);
        assertFalse("oracle — the router does not act on it", routerLifts(f));
        List<String> descs = descriptionsOf(f);
        String desc = descs.stream().filter(d -> d.contains("source terminal")).findFirst().orElseThrow();
        assertEquals(desc, "deferred", branchOf(desc));
        assertFalse(desc, desc.contains("bounded by"));
    }

    @Test
    public void trunkOfOnePxAlongTheFace_isNoHugAndSoNamesNothing() {
        // A terminal segment that runs only 1px along the face is a jog, not a run: what a reader sees
        // is the long segment after it, leaving the face perpendicularly. So it is no hug at all —
        // the same answer the router gives — and there is no remedy to word. The route then runs down
        // through a small element, so a lift search run on it anyway would find no lift and would
        // wrongly report the corridor layout-bound.
        Fixture f = new Fixture(List.of(node("src", 400, 50, 100, 60), node("tgt", 420, 300, 60, 50),
                node("pin", 446, 200, 10, 20)),
                List.of(conn("c1", "src", "tgt", p(450, 80), p(450, 114), p(451, 114), p(451, 296), p(451, 325))),
                Side.BOTTOM);
        assertFalse("oracle — the router does not act on it", routerLifts(f));
        assertEquals("a 1px jog at the terminal is not a hug", List.of(), descriptionsOf(f));
    }

    // ---------------------------------------------------------------- one end per pass

    /** A route hugging src's BOTTOM face at its source end and tgt's TOP face at its target end. */
    private static Fixture bothEndsHug(boolean sourceHugs) {
        List<AssessmentNode> nodes = List.of(node("src", 400, 50, 100, 60), node("tgt", 620, 300, 60, 50));
        AssessmentConnection c = sourceHugs
                ? conn("c1", "src", "tgt", p(450, 80), p(473, 114), p(560, 114), p(560, 296), p(650, 296),
                        p(650, 325))
                : conn("c1", "src", "tgt", p(450, 80), p(473, 114), p(473, 200), p(560, 200), p(560, 296),
                        p(650, 296), p(650, 325));
        return new Fixture(nodes, List.of(c), Side.BOTTOM);
    }

    private static String targetDescription(List<String> descs) {
        return descs.stream().filter(d -> d.contains("target terminal")).findFirst().orElseThrow();
    }

    @Test
    public void targetEnd_isNotLiftedInThePassThatLiftsTheSourceEnd() {
        Fixture f = bothEndsHug(true);
        RouterInput in = routerInput(f);
        List<List<AbsoluteBendpointDto>> after = new ArrayList<>();
        for (List<AbsoluteBendpointDto> path : in.paths()) after.add(new ArrayList<>(path));
        router.run(in.endpoints(), after, in.allObstacles());
        List<AbsoluteBendpointDto> route = after.get(0);
        AbsoluteBendpointDto last = route.get(route.size() - 1);
        AbsoluteBendpointDto beforeLast = route.get(route.size() - 2);
        assertEquals("oracle: the target end still hugs after the pass", 296, last.y());
        assertEquals("oracle: the target end still hugs after the pass", 296, beforeLast.y());
        List<String> descs = descriptionsOf(f);
        assertEquals(descs.toString(), 2, descs.size());
        String target = targetDescription(descs);
        assertTrue(target, target.endsWith("; re-routing will not lift this end in the same pass: the router lifts "
                + "at most one end of a connection per pass, taking the source end first, and this connection's "
                + "source end hugs its face too — run auto-route-connections, then re-assess this end."));
    }

    /**
     * The fixture's connections as the collector hands them over when both ends are odd-sized along y:
     * the analysis blend sits exactly half a pixel below every drawn bendpoint (both centres carry the
     * half pixel draw2d's whole-pixel reference points drop), and the drawn points are the integer route.
     * With {@code keepDrawn} false the drawn points are withheld, so only the analysis blend is left.
     */
    private static Fixture halfPixelAnalysis(Fixture f, boolean keepDrawn) {
        List<AssessmentConnection> cs = new ArrayList<>();
        for (AssessmentConnection c : f.connections()) {
            List<double[]> pts = c.pathPoints();
            List<double[]> analysis = new ArrayList<>();
            List<double[]> drawn = new ArrayList<>();
            for (int i = 0; i < pts.size(); i++) {
                double[] q = pts.get(i);
                boolean bend = i > 0 && i < pts.size() - 1;
                analysis.add(bend ? p(q[0], q[1] + 0.5) : q);
                if (bend) drawn.add(q);
            }
            cs.add(new AssessmentConnection(c.id(), c.sourceNodeId(), c.targetNodeId(), analysis,
                    c.labelText(), c.textPosition(), c.relativePosition(), c.anchorDriftX(),
                    c.anchorDriftY(), keepDrawn ? drawn : null));
        }
        return new Fixture(f.nodes(), cs, f.face());
    }

    /**
     * The remedy reads the route the verdict reads. The router lifts the terminal it drew, 4 px out on
     * its margin line; on the analysis blend that terminal is 4.5 px out, which the lift's shape check
     * rejects, so a remedy built on the blend would fall back to "re-route to lift it" for a hug the
     * router has already found it cannot lift.
     */
    @Test
    public void remedy_readsTheDrawnRoute_notTheHalfPixelAnalysisBlend() {
        Fixture f = hug(Side.BOTTOM).with(blockAt(Side.BOTTOM, "blk", "Kafka", null, 9, 100));
        String whole = hugDescription(f);
        assertTrue("precondition: the whole-pixel route is layout-bound: " + whole, whole.contains(LAYOUT_BOUND));
        String blendOnly = hugDescription(halfPixelAnalysis(f, false));
        assertTrue("precondition: on the blend alone the remedy defers: " + blendOnly, blendOnly.contains(DEFERRED));
        assertEquals(whole, hugDescription(halfPixelAnalysis(f, true)));
    }

    /** The "source end is lifted first" check reads the drawn route and the face the verdict found. */
    @Test
    public void targetEnd_sourceLiftedFirst_readsTheDrawnRoute() {
        Fixture f = bothEndsHug(true);
        String whole = targetDescription(descriptionsOf(f));
        assertTrue("precondition: " + whole, whole.contains("lifts at most one end of a connection per pass"));
        String blendOnly = targetDescription(descriptionsOf(halfPixelAnalysis(f, false)));
        assertTrue("precondition: on the blend alone the target end defers: " + blendOnly,
                blendOnly.contains(DEFERRED));
        assertEquals(whole, targetDescription(descriptionsOf(halfPixelAnalysis(f, true))));
    }

    @Test
    public void targetEnd_singleVarOff_sourceExitsCleanly_defersToAutoRoute() {
        Fixture f = bothEndsHug(false);
        assertTrue("oracle: the router lifts the target end", routerLifts(f));
        List<String> descs = descriptionsOf(f);
        assertEquals("only the target end hugs: " + descs, 1, descs.size());
        assertEquals(descs.get(0), "deferred", branchOf(targetDescription(descs)));
    }

    // ---------------------------------------------------------------- robustness

    @Test
    public void nullIds_neverBreakTheCountOrTheRating() {
        // A neighbour run on a connection without an id, and a deep block without an id, both beside
        // the hug: the remedy must still be built and the hug still counted.
        Fixture runs = withRunBelow(withRunBelow(hug(Side.BOTTOM), "n2", 30), "n3", 50)
                .with(conn(null, "n1-s", "n1-t", p(1520, 20), p(500, 119), p(600, 119), p(1620, 20)))
                .with(node("n1-s", 1500, 0, 40, 40)).with(node("n1-t", 1600, 0, 40, 40));
        Fixture box = hug(Side.BOTTOM).with(node(null, 500, 119, 80, 100));
        for (Fixture f : List.of(runs, box)) {
            List<AssessmentNode> layout = new ArrayList<>();
            for (AssessmentNode n : f.nodes()) layout.add(n);
            LayoutQualityAssessor.OffFaceParallelTerminalResult r =
                    assessor.countOffFaceParallelTerminals(f.connections(), layout, List.of(), true);
            assertEquals(r.descriptions().toString(), 1, r.count());
            assertTrue(r.violatorIds().contains("c1"));
            assertEquals(1, r.descriptions().size());
        }
    }

    @Test
    public void fractionalBounds_areReadAsTheRouterReadsThem() {
        // The routing caller truncates each bound to whole px, so a block at y=119.9 is 9px out to it.
        String desc = hugDescription(hug(Side.BOTTOM).with(named("blk", null, null, 500.6, 119.9, 80, 100)));
        assertTrue(desc, desc.contains("bounded by 'blk' 9.0px from the face, so move it at least 4px down"));
    }

    @Test
    public void mirrorConstants_matchTheRoutersOwn() throws Exception {
        assertEquals(routerInt("TARGET_EGRESS_CLEARANCE_PX"), LayoutQualityAssessor.EGRESS_MIN_LIFT_PX);
        assertEquals(routerInt("MAX_EGRESS_CLEARANCE_PX"), LayoutQualityAssessor.EGRESS_MAX_LIFT_PX);
        assertEquals(routerDouble("EDGE_COINCIDENCE_TOLERANCE_PX") + routerDouble("CLEAR_SAFETY_MARGIN_PX"),
                LayoutQualityAssessor.EGRESS_EDGE_CLEARANCE_PX, 0.0);
        assertEquals(routerDouble("EDGE_COINCIDENCE_MIN_OVERLAP_PX"), LayoutQualityAssessor.EGRESS_MIN_OVERLAP_PX,
                0.0);
        assertEquals(routerDouble("ZIGZAG_AXIS_TOLERANCE_PX"), LayoutQualityAssessor.EGRESS_AXIS_TOLERANCE_PX, 0.0);
        assertEquals(routerDouble("PRE_GATE_VP10_PX"), LayoutQualityAssessor.EGRESS_PRE_GATE_GAP_PX, 0.0);
        assertEquals(routerInt("HEALTHY_PARALLEL_GAP_PX"), LayoutQualityAssessor.HEALTHY_PARALLEL_GAP_PX, 0.0);
    }

    private static int routerInt(String name) throws Exception {
        java.lang.reflect.Field f = TerminalEgressClearancePass.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(null);
    }

    private static double routerDouble(String name) throws Exception {
        java.lang.reflect.Field f = TerminalEgressClearancePass.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getDouble(null);
    }

    @Test
    public void everyOracleGeometry_ratesTheHugTheSameWhateverTheRemedySays() {
        for (Map.Entry<String, Fixture> e : oracleGeometries().entrySet()) {
            Fixture f = e.getValue();
            List<AssessmentNode> layout = new ArrayList<>();
            for (AssessmentNode n : f.nodes()) {
                if (!n.isNote()) layout.add(n);
            }
            LayoutAssessmentResult r = assessor.assess(layout, f.connections(), true);
            String k = e.getKey();
            assertEquals(k + ": count", 1, r.offFaceParallelTerminalCount());
            assertEquals(k + ": violators", Set.of("c1"), r.violatorIds().get("offFaceParallelTerminals"));
            assertEquals(k + ": breakdown", "fair", r.ratingBreakdown().get("offFaceParallelTerminals"));
            assertEquals(k + ": coverage", LayoutQualityAssessor.COVERAGE_CHECKED,
                    r.coverage().get("offFaceParallelTerminals"));
        }
    }
}
