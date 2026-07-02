package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Tests the final {@code spreadCoincidentFacePorts} pass that dissolves coincident same-face terminal
 * ports left by the downstream terminal stages (a source-exit and a target-entry collapsed onto one
 * face-centre port). Drives the real entry point with synthetic routed geometry so the gates —
 * collision, distinguishability floor, perpendicularity, crossing-free, obstacle-safe, hub-degree,
 * and the movable/immovable swap — are pinned deterministically.
 */
public class HubPortCollisionSpreadTest {

    private static final int FLOOR = EdgeAttachmentCalculator.VISUAL_DISTINGUISHABILITY_THRESHOLD; // 12

    // Hub with a tall LEFT face: x=400, y 100..300, LEFT face line x=399, midpoint y=200, span [105,295].
    private static final RoutingRect HUB = new RoutingRect(400, 100, 120, 200, "hub");
    private static final RoutingRect P1 = new RoutingRect(100, 190, 60, 20, "p1"); // centre y=200
    private static final RoutingRect P2 = new RoutingRect(100, 190, 60, 20, "p2"); // centre y=200

    private final RoutingPipeline pipeline = new RoutingPipeline();

    private static AbsoluteBendpointDto bp(int x, int y) {
        return new AbsoluteBendpointDto(x, y);
    }

    private static RoutingPipeline.ConnectionEndpoints conn(String id, RoutingRect s, RoutingRect t) {
        return new RoutingPipeline.ConnectionEndpoints(id, s, t, List.of(), "", 1);
    }

    private static RoutingPipeline.ConnectionEndpoints conn(String id, RoutingRect s, RoutingRect t,
            List<RoutingRect> obstacles) {
        return new RoutingPipeline.ConnectionEndpoints(id, s, t, obstacles, "", 1);
    }

    /** A clean, movable target-entry stub peer -> HUB LEFT at slot y (horizontal stub → perpendicular). */
    private static List<AbsoluteBendpointDto> movableToHubLeft(RoutingRect peer, int slotY) {
        return new ArrayList<>(List.of(bp(peer.x() + peer.width() + 1, slotY), bp(HUB.x() - 1, slotY)));
    }

    /** An immovable target-entry stub peer -> HUB LEFT at slot y: the last segment hugs the face line
     *  (same x as the terminal), so any relocation would leave a face-parallel stub (reverts). */
    private static List<AbsoluteBendpointDto> hugToHubLeft(int slotY) {
        return new ArrayList<>(List.of(bp(HUB.x() - 1, slotY + 100), bp(HUB.x() - 1, slotY)));
    }

    private static int hubLeftTerminalY(Map<String, List<AbsoluteBendpointDto>> routed, String id) {
        List<AbsoluteBendpointDto> p = routed.get(id);
        return p.get(p.size() - 1).y();
    }

    private static int hubLeftTerminalX(Map<String, List<AbsoluteBendpointDto>> routed, String id) {
        List<AbsoluteBendpointDto> p = routed.get(id);
        return p.get(p.size() - 1).x();
    }

    @Test
    public void driver_collidingMovablePair_spreadsToDistinctOnLineSlots() {
        Map<String, List<AbsoluteBendpointDto>> routed = new LinkedHashMap<>();
        routed.put("a", movableToHubLeft(P1, 200));
        routed.put("b", movableToHubLeft(P2, 200));

        pipeline.spreadCoincidentFacePorts(routed, List.of(conn("a", P1, HUB), conn("b", P2, HUB)));

        assertEquals("a stays on the LEFT face line", HUB.x() - 1, hubLeftTerminalX(routed, "a"));
        assertEquals("b stays on the LEFT face line", HUB.x() - 1, hubLeftTerminalX(routed, "b"));
        assertTrue("the two LEFT terminals are now distinct >= the floor apart",
                Math.abs(hubLeftTerminalY(routed, "a") - hubLeftTerminalY(routed, "b")) >= FLOOR);
    }

    @Test
    public void driver_movableCollidesWithHug_movesTheMovableOne() {
        // A clean movable stub (a) collides with a face-hug stub (h) that cannot move. The pass must
        // move the movable one off the hug — even though the hug is the anchor (processed first).
        Map<String, List<AbsoluteBendpointDto>> routed = new LinkedHashMap<>();
        routed.put("h", hugToHubLeft(200));       // immovable, processed first (anchor)
        routed.put("a", movableToHubLeft(P1, 200)); // movable
        List<AbsoluteBendpointDto> hugBefore = new ArrayList<>(routed.get("h"));

        pipeline.spreadCoincidentFacePorts(routed, List.of(conn("h", P1, HUB), conn("a", P2, HUB)));

        assertEquals("the hug is left byte-identical", hugBefore, routed.get("h"));
        assertEquals("the movable terminal stays on the LEFT face line", HUB.x() - 1, hubLeftTerminalX(routed, "a"));
        assertTrue("the movable terminal is spread off the hug",
                Math.abs(hubLeftTerminalY(routed, "a") - 200) >= FLOOR);
    }

    @Test
    public void driver_spreadAvoidsPreviouslyDistinctSiblingOnSameFace() {
        // a & b collide at y=200; c is already DISTINCT at y=213 (never joins the a/b cluster). b's far
        // peer sits at y=213, so the naive nearest-approach slot would land b exactly on c. The pass must
        // avoid c too — it must not manufacture a NEW collision while resolving the old one.
        RoutingRect bPeer = new RoutingRect(100, 203, 60, 20, "bpeer"); // centre y=213 → approach favours 213
        RoutingRect cPeer = new RoutingRect(100, 203, 60, 20, "cpeer"); // centre y=213
        Map<String, List<AbsoluteBendpointDto>> routed = new LinkedHashMap<>();
        routed.put("a", movableToHubLeft(P1, 200));
        routed.put("b", movableToHubLeft(bPeer, 200));
        routed.put("c", movableToHubLeft(cPeer, 213));
        List<AbsoluteBendpointDto> cBefore = new ArrayList<>(routed.get("c"));

        pipeline.spreadCoincidentFacePorts(routed,
                List.of(conn("a", P1, HUB), conn("b", bPeer, HUB), conn("c", cPeer, HUB)));

        int aY = hubLeftTerminalY(routed, "a");
        int bY = hubLeftTerminalY(routed, "b");
        int cY = hubLeftTerminalY(routed, "c");
        assertEquals("the previously-distinct sibling c is byte-identical", cBefore, routed.get("c"));
        assertTrue("b did not land on the distinct sibling c", Math.abs(bY - cY) >= FLOOR);
        assertTrue("b is still separated from a", Math.abs(bY - aY) >= FLOOR);
    }

    @Test
    public void driver_distinctPair_isByteIdenticalNoOp() {
        Map<String, List<AbsoluteBendpointDto>> routed = new LinkedHashMap<>();
        routed.put("a", movableToHubLeft(P1, 160));
        routed.put("b", movableToHubLeft(P2, 240));
        Map<String, List<AbsoluteBendpointDto>> before = deepCopy(routed);

        pipeline.spreadCoincidentFacePorts(routed, List.of(conn("a", P1, HUB), conn("b", P2, HUB)));

        assertEquals("distinct ports are byte-identical", before, routed);
    }

    @Test
    public void driver_singleConnection_isByteIdenticalNoOp() {
        Map<String, List<AbsoluteBendpointDto>> routed = new LinkedHashMap<>();
        routed.put("a", movableToHubLeft(P1, 200));
        Map<String, List<AbsoluteBendpointDto>> before = deepCopy(routed);

        pipeline.spreadCoincidentFacePorts(routed, List.of(conn("a", P1, HUB)));

        assertEquals("single-connection face is byte-identical", before, routed);
    }

    @Test
    public void driver_faceTooShortForFloor_acceptsCollisionByteIdentical() {
        RoutingRect shortHub = new RoutingRect(400, 100, 120, 20, "shortHub"); // LEFT span [105,115]
        RoutingRect pa = new RoutingRect(100, 100, 60, 20, "pa");
        RoutingRect pb = new RoutingRect(100, 100, 60, 20, "pb");
        Map<String, List<AbsoluteBendpointDto>> routed = new LinkedHashMap<>();
        routed.put("a", new ArrayList<>(List.of(bp(161, 110), bp(shortHub.x() - 1, 110))));
        routed.put("b", new ArrayList<>(List.of(bp(161, 110), bp(shortHub.x() - 1, 110))));
        Map<String, List<AbsoluteBendpointDto>> before = deepCopy(routed);

        pipeline.spreadCoincidentFacePorts(routed, List.of(conn("a", pa, shortHub), conn("b", pb, shortHub)));

        assertEquals("too-short face: collision accepted, byte-identical", before, routed);
    }

    @Test
    public void driver_wouldAddCrossing_isNotSpread() {
        // Two movable ports collide at y=200; both would spread toward y=188 (approach y=195), but a
        // wall segment at x=380 spanning y=130..190 crosses the y=188 stub (not the original y=200 one),
        // so both relocations are vetoed and the pair stays byte-identical.
        RoutingRect pa = new RoutingRect(100, 185, 60, 20, "pa"); // centre y=195 → approach favours 188
        RoutingRect pb = new RoutingRect(100, 185, 60, 20, "pb");
        RoutingRect wsrc = new RoutingRect(360, 120, 40, 10, "wsrc");
        RoutingRect wtgt = new RoutingRect(360, 190, 40, 10, "wtgt");
        Map<String, List<AbsoluteBendpointDto>> routed = new LinkedHashMap<>();
        routed.put("a", new ArrayList<>(List.of(bp(161, 200), bp(HUB.x() - 1, 200))));
        routed.put("b", new ArrayList<>(List.of(bp(161, 200), bp(HUB.x() - 1, 200))));
        routed.put("w", new ArrayList<>(List.of(bp(380, 130), bp(380, 190)))); // vertical wall
        Map<String, List<AbsoluteBendpointDto>> before = deepCopy(routed);

        pipeline.spreadCoincidentFacePorts(routed,
                List.of(conn("a", pa, HUB), conn("b", pb, HUB), conn("w", wsrc, wtgt)));

        assertEquals("a/b unchanged — the only spread would add a crossing", before.get("a"), routed.get("a"));
        assertEquals("a/b unchanged — the only spread would add a crossing", before.get("b"), routed.get("b"));
    }

    @Test
    public void driver_isIdempotent_secondPassIsNoOp() {
        Map<String, List<AbsoluteBendpointDto>> routed = new LinkedHashMap<>();
        routed.put("a", movableToHubLeft(P1, 200));
        routed.put("b", movableToHubLeft(P2, 200));
        List<RoutingPipeline.ConnectionEndpoints> conns = List.of(conn("a", P1, HUB), conn("b", P2, HUB));

        pipeline.spreadCoincidentFacePorts(routed, conns);
        Map<String, List<AbsoluteBendpointDto>> afterFirst = deepCopy(routed);
        pipeline.spreadCoincidentFacePorts(routed, conns);

        assertEquals("second pass is a fixpoint", afterFirst, routed);
    }

    @Test
    public void driver_hubDegreeAtOrAboveThreshold_isByteIdenticalNoOp() {
        // A degree-5 element (hub) with a coincident LEFT pair — owned by the hub machinery, left alone.
        Map<String, List<AbsoluteBendpointDto>> routed = new LinkedHashMap<>();
        List<RoutingPipeline.ConnectionEndpoints> conns = new ArrayList<>();
        routed.put("a", movableToHubLeft(P1, 200));
        conns.add(conn("a", P1, HUB));
        routed.put("b", movableToHubLeft(P2, 200));
        conns.add(conn("b", P2, HUB));
        for (int i = 0; i < 3; i++) {
            RoutingRect peer = new RoutingRect(600, 240 + i * 60, 40, 20, "peer" + i);
            routed.put("x" + i, new ArrayList<>(List.of(
                    bp(700 + i * 20, HUB.y() + HUB.height()), bp(700 + i * 20, HUB.y() + HUB.height()))));
            conns.add(conn("x" + i, peer, HUB));
        }
        Map<String, List<AbsoluteBendpointDto>> before = deepCopy(routed);

        pipeline.spreadCoincidentFacePorts(routed, conns);

        assertEquals("degree-5 hub: coincident LEFT pair left byte-identical", before.get("a"), routed.get("a"));
        assertEquals("degree-5 hub: coincident LEFT pair left byte-identical", before.get("b"), routed.get("b"));
    }

    @Test
    public void driver_mixedSourceExitAndTargetEntry_spreadsDistinct() {
        // MD-as-source (a: HUB->peer, LEFT exit) + MD-as-target (b: peer->HUB, LEFT entry), both at the
        // LEFT centre. Grouping keys source and target terminals together, so the pass separates them.
        RoutingRect impl = new RoutingRect(100, 190, 60, 20, "impl");
        Map<String, List<AbsoluteBendpointDto>> routed = new LinkedHashMap<>();
        routed.put("a", new ArrayList<>(List.of(bp(HUB.x() - 1, 200), bp(impl.x() + impl.width() + 1, 200))));
        routed.put("b", movableToHubLeft(P2, 200));

        pipeline.spreadCoincidentFacePorts(routed, List.of(conn("a", HUB, impl), conn("b", P2, HUB)));

        int aY = routed.get("a").get(0).y();      // a: HUB is source → terminal is path[0]
        int bY = hubLeftTerminalY(routed, "b");   // b: HUB is target → terminal is path[last]
        assertEquals("a stays on the LEFT face line", HUB.x() - 1, routed.get("a").get(0).x());
        assertEquals("b stays on the LEFT face line", HUB.x() - 1, hubLeftTerminalX(routed, "b"));
        assertTrue("mixed source/target pair separated", Math.abs(aY - bY) >= FLOOR);
    }

    @Test
    public void driver_sourceExitVsTargetHug_atFaceCentre_resolves() {
        // The organic mixed-direction collision: a HUB-as-source clean exit (multi-bend stub, terminal
        // path[0]) collides with a HUB-as-target face-hug entry (vertical stub on the face line, terminal
        // path[last]) at the LEFT centre. The source exit is movable; the target hug is not — the pass
        // moves the source exit.
        RoutingRect impl = new RoutingRect(100, 190, 60, 20, "impl");
        Map<String, List<AbsoluteBendpointDto>> routed = new LinkedHashMap<>();
        // HUB source exit (movable, horizontal stub): terminal is path[0].
        routed.put("src", new ArrayList<>(List.of(
                bp(HUB.x() - 1, 200), bp(HUB.x() - 62, 200), bp(HUB.x() - 62, 180),
                bp(impl.x() + impl.width() + 1, 180))));
        // HUB target entry (hug, vertical stub on the face line): terminal is path[last].
        routed.put("tgt", hugToHubLeft(200));
        List<AbsoluteBendpointDto> hugBefore = new ArrayList<>(routed.get("tgt"));

        pipeline.spreadCoincidentFacePorts(routed, List.of(conn("src", HUB, impl), conn("tgt", P2, HUB)));

        int srcY = routed.get("src").get(0).y();                 // HUB source → path[0]
        int tgtY = hubLeftTerminalY(routed, "tgt");              // HUB target → path[last]
        assertEquals("the hug is left byte-identical", hugBefore, routed.get("tgt"));
        assertEquals("source terminal stays on the LEFT face line", HUB.x() - 1, routed.get("src").get(0).x());
        assertTrue("the LEFT-face pair is now distinct >= the floor apart", Math.abs(srcY - tgtY) >= FLOOR);
        assertNoCollinearTriple("the relocation leaves no redundant (collinear) bendpoint", routed.get("src"));
    }

    /** Asserts no interior point is collinear with both neighbours (a redundant bendpoint). */
    private static void assertNoCollinearTriple(String msg, List<AbsoluteBendpointDto> path) {
        for (int i = 1; i < path.size() - 1; i++) {
            AbsoluteBendpointDto a = path.get(i - 1);
            AbsoluteBendpointDto b = path.get(i);
            AbsoluteBendpointDto c = path.get(i + 1);
            long cross = (long) (b.x() - a.x()) * (c.y() - a.y()) - (long) (b.y() - a.y()) * (c.x() - a.x());
            assertTrue(msg + " (index " + i + ")", cross != 0);
        }
    }

    private static Map<String, List<AbsoluteBendpointDto>> deepCopy(
            Map<String, List<AbsoluteBendpointDto>> in) {
        Map<String, List<AbsoluteBendpointDto>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<AbsoluteBendpointDto>> e : in.entrySet()) {
            out.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return out;
    }

    // ---- chooseFreeSlot ----

    @Test
    public void chooseFreeSlot_picksFeasiblePointNearestApproach() {
        Double slot = RoutingPipeline.chooseFreeSlot(List.of(200.0), 105, 295, 220, FLOOR);
        assertNotNull(slot);
        assertEquals(220.0, slot, 1e-9);
        assertTrue("respects the floor", Math.abs(slot - 200.0) >= FLOOR);
    }

    @Test
    public void chooseFreeSlot_placesBetweenTwoOccupiedSlots() {
        Double slot = RoutingPipeline.chooseFreeSlot(List.of(200.0, 250.0), 105, 295, 225, FLOOR);
        assertNotNull(slot);
        assertEquals(225.0, slot, 1e-9);
    }

    @Test
    public void chooseFreeSlot_returnsNull_whenNoSlotClearsFloor() {
        assertNull(RoutingPipeline.chooseFreeSlot(List.of(110.0), 105, 115, 110, FLOOR));
    }

    // ---- countOrthogonalCrossings ----

    @Test
    public void countOrthogonalCrossings_countsInteriorHxVcrossing() {
        List<AbsoluteBendpointDto> h = List.of(bp(0, 50), bp(100, 50));
        List<AbsoluteBendpointDto> v = List.of(bp(50, 0), bp(50, 100));
        assertEquals(1, RoutingPipeline.countOrthogonalCrossings(h, v));
    }

    @Test
    public void countOrthogonalCrossings_ignoresTouchingAtEndpoint() {
        List<AbsoluteBendpointDto> h = List.of(bp(0, 50), bp(100, 50));
        List<AbsoluteBendpointDto> v = List.of(bp(50, 50), bp(50, 100));
        assertEquals(0, RoutingPipeline.countOrthogonalCrossings(h, v));
    }

    @Test
    public void countOrthogonalCrossings_ignoresParallelSegments() {
        List<AbsoluteBendpointDto> a = List.of(bp(0, 50), bp(100, 50));
        List<AbsoluteBendpointDto> b = List.of(bp(0, 70), bp(100, 70));
        assertEquals(0, RoutingPipeline.countOrthogonalCrossings(a, b));
    }
}
