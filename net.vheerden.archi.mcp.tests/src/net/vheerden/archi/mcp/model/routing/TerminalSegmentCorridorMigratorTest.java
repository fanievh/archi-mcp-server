package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.model.routing.EdgeAttachmentCalculator.Face;
import net.vheerden.archi.mcp.model.routing.TerminalSegmentCorridorMigrator.MigrationProposal;
import net.vheerden.archi.mcp.model.routing.TerminalSegmentCorridorMigrator.Snapshot;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Unit tests for {@link TerminalSegmentCorridorMigrator} (Axis 3 —
 * terminal-segment corridor migration).
 *
 * <p>Pure-geometry: no EMF, no SWT, no PDE. Hand-constructed size-3 L-shape
 * scenarios exercise the evaluate / apply / restore primitives and the two
 * load-bearing guards (the explicit {@code preservesTerminalAnchoring}
 * contract call + the Tier-1 self-check) without engaging the full routing
 * pipeline.
 *
 * <p><b>Fixture-vs-spike note (CLAUDE.md "no lying").</b> The Task-0 spike §6
 * worked example places its hug element {@code E} at {@code (300,161,120,40)}.
 * Read literally, that rect makes the L's <em>second</em> segment ({@code x=360},
 * {@code y∈[160,250]}) pass through {@code E}'s interior — an incidental artifact
 * the spike text never reasoned about (it only discussed {@code seg0} hugging
 * {@code E}). The migrator's Tier-1 self-check (stricter than the spike sketch)
 * correctly rejects that geometry. {@link #poc_sourceIncidentRightFace_m4OneToZero_b71True()}
 * proves the <em>identical</em> reachability mechanism (terminal-incident seg0
 * hugs a non-hub element edge; migrate the slot away; terminal orthogonal coord stays
 * on the face line; clean L preserved; M4 1&rarr;0) with {@code E} repositioned so
 * the fixture is honestly Tier-1-clean.
 */
public class TerminalSegmentCorridorMigratorTest {

    private static AbsoluteBendpointDto bp(int x, int y) {
        return new AbsoluteBendpointDto(x, y);
    }

    private static List<AbsoluteBendpointDto> path(AbsoluteBendpointDto... bps) {
        return new ArrayList<>(Arrays.asList(bps));
    }

    private static List<List<AbsoluteBendpointDto>> paths(List<AbsoluteBendpointDto> p) {
        List<List<AbsoluteBendpointDto>> list = new ArrayList<>();
        list.add(p);
        return list;
    }

    private static RoutingPipeline.ConnectionEndpoints conn(String id, RoutingRect src,
                                                            RoutingRect tgt) {
        return new RoutingPipeline.ConnectionEndpoints(id, src, tgt, List.of(), null, 0);
    }

    private static List<RoutingPipeline.ConnectionEndpoints> conns(
            RoutingPipeline.ConnectionEndpoints c) {
        List<RoutingPipeline.ConnectionEndpoints> list = new ArrayList<>();
        list.add(c);
        return list;
    }

    // Source hub: RIGHT face line x = 100+80+1 = 181; slot axis Y; face extent y∈[100,300].
    private static final RoutingRect H = new RoutingRect(100, 100, 80, 200, "H");
    // Target element (own-endpoint — excluded from the Tier-1 passthrough scan).
    private static final RoutingRect T = new RoutingRect(340, 250, 80, 50, "T");

    // =====================================================================
    // PoC — the spike §6 reachability mechanism (Tier-1-clean variant)
    // =====================================================================

    @Test
    public void poc_sourceIncidentRightFace_m4OneToZero_b71True() {
        // p0 on H RIGHT face line x=181, slot y=160. seg0 horizontal hugs E.topEdge=161.
        // E repositioned to x∈[200,320] so the L's seg1 at x=360 does NOT cross E.
        RoutingRect e = new RoutingRect(200, 161, 120, 40, "E");
        List<AbsoluteBendpointDto> p = path(bp(181, 160), bp(360, 160), bp(360, 250));
        var paths = paths(p);
        var connections = conns(conn("c-poc", H, T));
        List<RoutingRect> obstacles = List.of(H, e, T);

        // Pre: M4 == 1 (seg0 hugs E.top).
        assertEquals(1, HubPerimeterRoutingStage.computeM4Count(paths, obstacles));

        var mig = new TerminalSegmentCorridorMigrator();
        List<MigrationProposal> proposals = mig.evaluate(connections, paths, obstacles);

        assertEquals(1, proposals.size());
        MigrationProposal pr = proposals.get(0);
        assertEquals("c-poc", pr.connectionId());
        assertEquals(0, pr.terminalBpIdx());
        assertEquals(1, pr.cornerBpIdx());
        assertFalse("RIGHT face slot axis is Y → shiftX must be false", pr.shiftX());
        assertEquals(160, pr.oldSlot());
        assertTrue("new slot must clear E.topEdge=161 by > tolerance",
                Math.abs(pr.newSlot() - 161) > 3);

        mig.apply(pr, paths);

        // Terminal anchoring: terminal orthogonal coord (x) still exactly on the RIGHT face line.
        assertEquals("terminal x stays on RIGHT face line 181", 181, paths.get(0).get(0).x());
        assertTrue(TerminalAnchoring.preservesTerminalAnchoring(
                new TerminalAnchoring(Face.RIGHT), H, paths.get(0)));
        // Post: M4 == 0 (seg0 no longer hugs E).
        assertEquals(0, HubPerimeterRoutingStage.computeM4Count(paths, obstacles));
    }

    // =====================================================================
    // SAFE sub-case across faces
    // =====================================================================

    @Test
    public void safe_topFace_verticalExitSegment_isMigrated() {
        // Source S: TOP face line y = 100-1 = 99; slot axis X; face extent x∈[100,180].
        RoutingRect s = new RoutingRect(100, 100, 80, 60, "S");
        // E.leftEdge x=141; E.y∈[60,140] overlaps seg0's y-span [40,99] by 39 (hug)
        // but does NOT contain the corner's y=40 so the clean L's seg1 (y=40) is clear.
        RoutingRect e = new RoutingRect(141, 60, 60, 80, "E");
        // p0 on TOP face (140,99); seg0 vertical (constant x=140 = slot) hugs E.left=141;
        // seg1 horizontal (constant y=40). Clean orthogonal L.
        List<AbsoluteBendpointDto> p = path(bp(140, 99), bp(140, 40), bp(260, 40));
        var paths = paths(p);
        var connections = conns(conn("c-top", s, T));
        List<RoutingRect> obstacles = List.of(s, e, T);

        var mig = new TerminalSegmentCorridorMigrator();
        var proposals = mig.evaluate(connections, paths, obstacles);

        assertEquals(1, proposals.size());
        MigrationProposal pr = proposals.get(0);
        assertTrue("TOP face slot axis is X → shiftX must be true", pr.shiftX());
        mig.apply(pr, paths);
        // Terminal anchoring: terminal orthogonal coord (y) still on the TOP face line 99.
        assertEquals(99, paths.get(0).get(0).y());
        assertTrue(TerminalAnchoring.preservesTerminalAnchoring(
                new TerminalAnchoring(Face.TOP), s, paths.get(0)));
    }

    @Test
    public void safe_targetIncident_isMigrated_whenSourceSegmentIsClean() {
        // Clean L: source S TOP face (slot axis X) → seg0 vertical (clean, no hug);
        // target U LEFT face (slot axis Y) → seg1 horizontal, hugs E.topEdge=161.
        RoutingRect s = new RoutingRect(140, 300, 80, 60, "S"); // S TOP line y = 300-1 = 299
        RoutingRect u = new RoutingRect(500, 100, 80, 200, "U"); // U LEFT line x = 500-1 = 499
        RoutingRect e = new RoutingRect(300, 161, 160, 40, "E");  // E.topEdge y=161
        // t0(180,299) on S TOP; c(180,160); t1(499,160) on U LEFT.
        // seg0 = t0->c vertical x=180 (clean, hugs nothing); seg1 = c->t1 horizontal
        // y=160 hugs E.top=161. Source-incident yields no proposal → target-incident does.
        List<AbsoluteBendpointDto> p = path(bp(180, 299), bp(180, 160), bp(499, 160));
        var paths = paths(p);
        var connections = conns(conn("c-tgt", s, u));
        List<RoutingRect> obstacles = List.of(s, e, u);

        var mig = new TerminalSegmentCorridorMigrator();
        var proposals = mig.evaluate(connections, paths, obstacles);

        assertEquals(1, proposals.size());
        MigrationProposal pr = proposals.get(0);
        assertEquals("target-incident → terminal bp idx = lastIdx (2)", 2, pr.terminalBpIdx());
        assertEquals(1, pr.cornerBpIdx());
        mig.apply(pr, paths);
        // Terminal anchoring on the TARGET terminal (idx 2): orthogonal coord (x) stays on U LEFT line 499.
        assertEquals(499, paths.get(0).get(2).x());
        var reversed = new ArrayList<>(paths.get(0));
        java.util.Collections.reverse(reversed);
        assertTrue(TerminalAnchoring.preservesTerminalAnchoring(
                new TerminalAnchoring(Face.LEFT), u, reversed));
    }

    // =====================================================================
    // UNSAFE rejection — role boundary preserved, not crossed
    // =====================================================================

    @Test
    public void unsafe_constantAxisEqualsOrthogonalAxis_isRejected() {
        // t0 on H RIGHT face (181,160). seg0 = t0->c is VERTICAL (constant x=181 = the
        // RIGHT face's ORTHOGONAL axis). Shifting its constant coord would drag the
        // terminal off the face line → UNSAFE → migrator must reject (no proposal).
        // The hug element here is immaterial — UNSAFE is rejected before any hug test.
        RoutingRect e = new RoutingRect(120, 100, 60, 300, "E");
        List<AbsoluteBendpointDto> p = path(bp(181, 160), bp(181, 300), bp(360, 300));
        var paths = paths(p);
        var connections = conns(conn("c-unsafe", H, T));
        List<RoutingRect> obstacles = List.of(H, e, T);

        var mig = new TerminalSegmentCorridorMigrator();
        assertTrue("UNSAFE terminal segment must yield no proposal",
                mig.evaluate(connections, paths, obstacles).isEmpty());
    }

    // =====================================================================
    // No-op cases
    // =====================================================================

    @Test
    public void noProposal_whenTerminalSegmentDoesNotHugAnyEdge() {
        // seg0 horizontal at y=160; no element edge within tolerance of y=160.
        RoutingRect e = new RoutingRect(200, 400, 120, 40, "E"); // far from y=160
        List<AbsoluteBendpointDto> p = path(bp(181, 160), bp(360, 160), bp(360, 250));
        var mig = new TerminalSegmentCorridorMigrator();
        assertTrue(mig.evaluate(conns(conn("c", H, T)), paths(p), List.of(H, e, T)).isEmpty());
    }

    @Test
    public void noProposal_whenPathIsNotSizeThree() {
        // size-2 and size-4 paths are out of MVP scope.
        RoutingRect e = new RoutingRect(200, 161, 120, 40, "E");
        var mig = new TerminalSegmentCorridorMigrator();
        assertTrue("size-2 out of scope",
                mig.evaluate(conns(conn("c2", H, T)),
                        paths(path(bp(181, 160), bp(360, 160))), List.of(H, e, T)).isEmpty());
        assertTrue("size-4 out of scope",
                mig.evaluate(conns(conn("c4", H, T)),
                        paths(path(bp(181, 160), bp(360, 160), bp(360, 250), bp(420, 250))),
                        List.of(H, e, T)).isEmpty());
    }

    @Test
    public void noProposal_whenNoClearingSlotWithinFaceExtent() {
        // Tight face: H face extent y∈[100,300]. Stack edges so every slot in the
        // extent is within the safety-clear band of some edge → degrade to no-op.
        List<RoutingRect> obstacles = new ArrayList<>();
        obstacles.add(H);
        obstacles.add(T);
        for (int y = 100; y <= 300; y += 6) {
            obstacles.add(new RoutingRect(200, y, 160, 1, "blk-" + y));
        }
        List<AbsoluteBendpointDto> p = path(bp(181, 160), bp(360, 160), bp(360, 250));
        var mig = new TerminalSegmentCorridorMigrator();
        assertTrue("no clearing slot within the face extent → no proposal",
                mig.evaluate(conns(conn("c", H, T)), paths(p), obstacles).isEmpty());
    }

    @Test
    public void noProposal_whenTerminalNotExactlyOnFaceLine() {
        // Terminal at x=180 (on the rect edge, NOT the face line 181) → not our
        // domain (EdgeAttachmentCalculator owns it) → inferAttachedFace null → no proposal.
        RoutingRect e = new RoutingRect(200, 161, 120, 40, "E");
        List<AbsoluteBendpointDto> p = path(bp(180, 160), bp(360, 160), bp(360, 250));
        var mig = new TerminalSegmentCorridorMigrator();
        assertTrue(mig.evaluate(conns(conn("c", H, T)), paths(p), List.of(H, e, T)).isEmpty());
        assertNull(TerminalSegmentCorridorMigrator.inferAttachedFace(bp(180, 160), H));
        assertEquals(Face.RIGHT,
                TerminalSegmentCorridorMigrator.inferAttachedFace(bp(181, 160), H));
    }

    // =====================================================================
    // Tier-1 self-check (passthrough)
    // =====================================================================

    @Test
    public void tier1_rejectsProposal_whenMigratedLWouldPassThroughObstacleInterior() {
        // seg0 hugs E.top=161. The only clearing slots sit where the resulting seg1
        // (x=360, vertical down to y=250) would pass straight through obstacle B's
        // interior → Tier-1 self-check rejects → no proposal.
        RoutingRect e = new RoutingRect(200, 161, 120, 40, "E");
        RoutingRect b = new RoutingRect(330, 100, 60, 250, "B"); // x∈[330,390] covers seg1 x=360
        List<AbsoluteBendpointDto> p = path(bp(181, 160), bp(360, 160), bp(360, 250));
        var mig = new TerminalSegmentCorridorMigrator();
        assertTrue("Tier-1 passthrough must block the migration",
                mig.evaluate(conns(conn("c", H, T)), paths(p), List.of(H, e, b, T)).isEmpty());
    }

    // =====================================================================
    // apply / restore mechanics
    // =====================================================================

    @Test
    public void apply_shiftsTerminalAndCornerOnSlotAxis_orthogonalUnchanged() {
        RoutingRect e = new RoutingRect(200, 161, 120, 40, "E");
        List<AbsoluteBendpointDto> p = path(bp(181, 160), bp(360, 160), bp(360, 250));
        var paths = paths(p);
        var mig = new TerminalSegmentCorridorMigrator();
        var proposals = mig.evaluate(conns(conn("c", H, T)), paths, List.of(H, e, T));
        assertEquals(1, proposals.size());
        MigrationProposal pr = proposals.get(0);

        Snapshot snap = mig.apply(pr, paths);

        assertNotNull(snap);
        assertEquals("terminal x (orthogonal) unchanged", 181, paths.get(0).get(0).x());
        assertEquals("corner x (along-axis) unchanged", 360, paths.get(0).get(1).x());
        assertEquals("terminal y (slot) == newSlot", pr.newSlot(), paths.get(0).get(0).y());
        assertEquals("corner y (slot) == newSlot", pr.newSlot(), paths.get(0).get(1).y());
        assertEquals("untouched target terminal", bp(360, 250), paths.get(0).get(2));
    }

    @Test
    public void restore_revertsTerminalAndCorner_fromSnapshot() {
        RoutingRect e = new RoutingRect(200, 161, 120, 40, "E");
        List<AbsoluteBendpointDto> p = path(bp(181, 160), bp(360, 160), bp(360, 250));
        var paths = paths(p);
        List<AbsoluteBendpointDto> original = new ArrayList<>(paths.get(0));
        var mig = new TerminalSegmentCorridorMigrator();
        var pr = mig.evaluate(conns(conn("c", H, T)), paths, List.of(H, e, T)).get(0);

        Snapshot snap = mig.apply(pr, paths);
        mig.restore(snap, paths);

        assertEquals(original.get(0), paths.get(0).get(0));
        assertEquals(original.get(1), paths.get(0).get(1));
        assertEquals(original.get(2), paths.get(0).get(2));
    }

    @Test
    public void apply_returnsNull_forNullOrOutOfRangeProposal() {
        var mig = new TerminalSegmentCorridorMigrator();
        assertNull(mig.apply(null, paths(path(bp(0, 0), bp(1, 1)))));
        assertNull(mig.apply(new MigrationProposal("c", 9, 0, 1, false, 0, 5),
                paths(path(bp(0, 0), bp(1, 1), bp(2, 2)))));
    }

    // =====================================================================
    // evaluate-level invariants
    // =====================================================================

    @Test
    public void evaluate_emitsAtMostOneProposalPerConnection_whenBothTerminalSegmentsHug() {
        // Clean L (seg0 horizontal, seg1 vertical) where BOTH terminal segments can
        // hug an edge. The migrator emits exactly ONE proposal (source-incident
        // preferred) so the shared interior corner is never double-moved in one pass.
        RoutingRect u = new RoutingRect(320, 401, 80, 60, "U");   // t1 on U TOP line y=400
        RoutingRect e0 = new RoutingRect(200, 161, 120, 40, "E0"); // seg0 hugs E0.top=161
        RoutingRect e1 = new RoutingRect(361, 200, 120, 100, "E1"); // seg1 hugs E1.left=361
        List<AbsoluteBendpointDto> p = path(bp(181, 160), bp(360, 160), bp(360, 400));
        var mig = new TerminalSegmentCorridorMigrator();
        var proposals = mig.evaluate(conns(conn("c", H, u)), paths(p), List.of(H, e0, e1, u));
        assertEquals(1, proposals.size());
        assertEquals("source-incident preferred", 0, proposals.get(0).terminalBpIdx());
    }

    @Test
    public void evaluate_returnsEmpty_forNullOrMismatchedInputs() {
        var mig = new TerminalSegmentCorridorMigrator();
        assertTrue(mig.evaluate(null, null, null).isEmpty());
        assertTrue(mig.evaluate(conns(conn("c", H, T)), new ArrayList<>(), List.of()).isEmpty());
    }

    @Test
    public void findClearingSlot_picksNearestClearingSlotDeterministically() {
        // edge at 161; required clearance = 3 + 2 = 5. From cur=160 the nearest
        // in-extent slot with |slot-161| >= 5 is 156 (160-4), searched down-before-up.
        Integer slot = TerminalSegmentCorridorMigrator.findClearingSlot(
                160, List.of(161, 201), 100, 300);
        assertNotNull(slot);
        assertTrue("clears edge 161 by >= 5", Math.abs(slot - 161) >= 5);
        assertEquals(Integer.valueOf(156), slot);
        assertNull("no slot clears when extent is fully inside the band",
                TerminalSegmentCorridorMigrator.findClearingSlot(161, List.of(161), 159, 163));
    }
}
