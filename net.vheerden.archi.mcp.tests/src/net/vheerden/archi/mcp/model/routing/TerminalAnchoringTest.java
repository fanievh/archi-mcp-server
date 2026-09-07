package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.model.routing.EdgeAttachmentCalculator.Face;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Unit tests for {@link TerminalAnchoring} — the perimeter-terminal
 * immutability carrier and its axis-agnostic predicate
 * ({@link TerminalAnchoring#preservesTerminalAnchoring}).
 *
 * <p>Covers four worked examples:
 * <ol>
 *   <li>V4 API Gateway → Relationship Manager Portal slot 3/7 — REJECT
 *       (collinear-on-parallel ∧ off-face-line)</li>
 *   <li>Perimeter-distributed slot, off-center on face line — PRESERVE</li>
 *   <li>Centre-face exit — PRESERVE</li>
 *   <li>Interior pass-through (the absolute-degenerate case) — REJECT</li>
 * </ol>
 *
 * <p>This predicate is enforced exclusively at {@code path[0]} / {@code path[last]}
 * mutator sites within {@link PathStraightener} and
 * {@link CoincidentSegmentDetector#applyOffsets}. Stages that legitimately
 * shape terminal bendpoints by contract (4.7h/k/m/o) are outside this
 * predicate's domain by construction.
 */
public class TerminalAnchoringTest {

    // -----------------------------------------------------------------
    // Direct accessor sanity
    // -----------------------------------------------------------------

    @Test
    public void parallelAxis_isYForLeftAndRight() {
        assertTrue(new TerminalAnchoring(Face.LEFT).parallelAxis() == TerminalAnchoring.Axis.Y);
        assertTrue(new TerminalAnchoring(Face.RIGHT).parallelAxis() == TerminalAnchoring.Axis.Y);
    }

    @Test
    public void parallelAxis_isXForTopAndBottom() {
        assertTrue(new TerminalAnchoring(Face.TOP).parallelAxis() == TerminalAnchoring.Axis.X);
        assertTrue(new TerminalAnchoring(Face.BOTTOM).parallelAxis() == TerminalAnchoring.Axis.X);
    }

    @Test
    public void lineCoordinate_isOnePixelOutsideRect() {
        RoutingRect r = new RoutingRect(100, 200, 80, 40, "id");
        // LEFT  → x - 1
        assertTrue(new TerminalAnchoring(Face.LEFT).lineCoordinate(r) == 99);
        // RIGHT → x + width + 1
        assertTrue(new TerminalAnchoring(Face.RIGHT).lineCoordinate(r) == 181);
        // TOP   → y - 1
        assertTrue(new TerminalAnchoring(Face.TOP).lineCoordinate(r) == 199);
        // BOTTOM → y + height + 1
        assertTrue(new TerminalAnchoring(Face.BOTTOM).lineCoordinate(r) == 241);
    }

    // -----------------------------------------------------------------
    // Worked example 1 — V4 API Gateway → Rel Mgr slot 3/7
    // (headline rejection case)
    // -----------------------------------------------------------------

    /**
     * V4 fixture: Relationship Manager
     * Portal source rect at {@code (472, 67, 107, 200)}, source center
     * {@code (525, 167)}. Pre-collapse path[0] = {@code (471, 144)} (LEFT
     * face slot 3/7). Post-collapse path[0] = {@code (525, 167)} — collinear
     * with center on parallel axis Y (both at y=167), but x=525 is NOT on the
     * LEFT face line x=471. The predicate must reject.
     */
    @Test
    public void rejects_v4RelMgrSlot3of7_collapsedToCenter() {
        RoutingRect source = new RoutingRect(472, 67, 107, 200, "rel-mgr");
        int[] center = {525, 167};
        TerminalAnchoring anchoring = new TerminalAnchoring(Face.LEFT);

        // Collapsed shape: bp[0] = (525, 167) = source center
        List<AbsoluteBendpointDto> after = List.of(
                new AbsoluteBendpointDto(525, 167),
                new AbsoluteBendpointDto(144, 559));

        assertFalse(
                "predicate must reject the collapsed centre-collinear off-face shape",
                TerminalAnchoring.preservesTerminalAnchoring(anchoring, source, after));
    }

    /**
     * Pre-collapse legitimate shape from the same fixture: bp[0] is on the
     * LEFT face line (x=471) at the perimeter-distributed slot y=144 (off-center).
     * The predicate must preserve.
     */
    @Test
    public void preserves_v4RelMgrSlot3of7_preCollapse() {
        RoutingRect source = new RoutingRect(472, 67, 107, 200, "rel-mgr");
        int[] center = {525, 167};
        TerminalAnchoring anchoring = new TerminalAnchoring(Face.LEFT);

        List<AbsoluteBendpointDto> before = List.of(
                new AbsoluteBendpointDto(471, 144),
                new AbsoluteBendpointDto(364, 144),
                new AbsoluteBendpointDto(364, 559),
                new AbsoluteBendpointDto(144, 559));

        assertTrue(
                "predicate must preserve the legitimate off-center perimeter slot",
                TerminalAnchoring.preservesTerminalAnchoring(anchoring, source, before));
    }

    // -----------------------------------------------------------------
    // Worked example 2 — perimeter-distributed slot, off-center on face line
    // -----------------------------------------------------------------

    /**
     * Generic perimeter-distributed slot on a TOP face: rect (200, 300, 100, 60),
     * center (250, 330). Slot at x=220, on top face line y=299. Predicate
     * must preserve (off parallel-axis center but on face line).
     */
    @Test
    public void preserves_b9DistributedSlot_onTopFace() {
        RoutingRect source = new RoutingRect(200, 300, 100, 60, "n");
        int[] center = {250, 330};
        TerminalAnchoring anchoring = new TerminalAnchoring(Face.TOP);

        List<AbsoluteBendpointDto> path = List.of(
                new AbsoluteBendpointDto(220, 299),  // off-center, on TOP face line
                new AbsoluteBendpointDto(220, 100));

        assertTrue(TerminalAnchoring.preservesTerminalAnchoring(anchoring, source, path));
    }

    // -----------------------------------------------------------------
    // Worked example 3 — Centre-face exit
    // -----------------------------------------------------------------

    /**
     * Centre-face exit: bp[0] sits at (centerX, faceLine) on the BOTTOM face.
     * Both collinear-on-parallel AND on-face-line ⇒ predicate preserves
     * (the {@code !(collinear ∧ !onFace)} clause).
     */
    @Test
    public void preserves_centreFaceExit_onBottomFace() {
        RoutingRect source = new RoutingRect(200, 300, 100, 60, "n");
        int[] center = {250, 330};
        TerminalAnchoring anchoring = new TerminalAnchoring(Face.BOTTOM);

        List<AbsoluteBendpointDto> path = List.of(
                new AbsoluteBendpointDto(250, 361),  // centerX, BOTTOM face line
                new AbsoluteBendpointDto(250, 500));

        assertTrue(TerminalAnchoring.preservesTerminalAnchoring(anchoring, source, path));
    }

    // -----------------------------------------------------------------
    // Worked example 4 — Interior pass-through (absolute-degenerate)
    // -----------------------------------------------------------------

    /**
     * Interior pass-through: bp[0] is the source center itself. By
     * definition, collinear-on-parallel = TRUE and on-face-line = FALSE
     * (centerY ≠ TOP line y-1 and ≠ BOTTOM line y+h+1 unless the rect is
     * degenerate). Predicate rejects.
     */
    @Test
    public void rejects_interiorPassThrough_atSourceCenter() {
        RoutingRect source = new RoutingRect(200, 300, 100, 60, "n");
        int[] center = {250, 330};
        TerminalAnchoring anchoring = new TerminalAnchoring(Face.LEFT);

        List<AbsoluteBendpointDto> path = List.of(
                new AbsoluteBendpointDto(250, 330),  // source center
                new AbsoluteBendpointDto(0, 330));

        assertFalse(TerminalAnchoring.preservesTerminalAnchoring(anchoring, source, path));
    }

    // -----------------------------------------------------------------
    // Empty-path fast-path
    // -----------------------------------------------------------------

    @Test
    public void emptyPath_returnsTrue() {
        RoutingRect source = new RoutingRect(0, 0, 10, 10, "n");
        assertTrue(TerminalAnchoring.preservesTerminalAnchoring(
                new TerminalAnchoring(Face.LEFT), source, List.of()));
    }

    // -----------------------------------------------------------------
    // preservesEndpoints helper — both terminals
    // -----------------------------------------------------------------

    @Test
    public void preservesEndpoints_passesWhenBothTerminalsLegit() {
        RoutingRect src = new RoutingRect(472, 67, 107, 200, "src");
        RoutingRect tgt = new RoutingRect(100, 500, 88, 118, "tgt");
        int[] sc = {525, 167};
        int[] tc = {144, 559};
        TerminalAnchoring sa = new TerminalAnchoring(Face.LEFT);
        TerminalAnchoring ta = new TerminalAnchoring(Face.RIGHT);

        // Source on LEFT face slot, target on RIGHT face line at center
        List<AbsoluteBendpointDto> path = List.of(
                new AbsoluteBendpointDto(471, 144),  // LEFT face line, off-center: PRESERVE
                new AbsoluteBendpointDto(364, 144),
                new AbsoluteBendpointDto(364, 559),
                new AbsoluteBendpointDto(189, 559)); // tgt RIGHT face line (100+88+1=189), at tc.y: PRESERVE

        assertTrue(TerminalAnchoring.preservesEndpoints(
                sa, src, sc, ta, tgt, tc, path));
    }

    @Test
    public void preservesEndpoints_failsOnTargetCollapse() {
        RoutingRect src = new RoutingRect(472, 67, 107, 200, "src");
        RoutingRect tgt = new RoutingRect(100, 500, 88, 118, "tgt");
        int[] sc = {525, 167};
        int[] tc = {144, 559};
        TerminalAnchoring sa = new TerminalAnchoring(Face.LEFT);
        TerminalAnchoring ta = new TerminalAnchoring(Face.RIGHT);

        // Target end collapsed to center (144, 559), NOT on RIGHT face line (189)
        List<AbsoluteBendpointDto> path = List.of(
                new AbsoluteBendpointDto(471, 144),
                new AbsoluteBendpointDto(144, 559));   // target center, not on face

        assertFalse(TerminalAnchoring.preservesEndpoints(
                sa, src, sc, ta, tgt, tc, path));
    }

    @Test
    public void preservesEndpoints_skipsNullAnchoring() {
        // When source anchoring is null, source side is unchecked — only
        // target side governs the result (legacy 3-arg test compat path).
        RoutingRect tgt = new RoutingRect(100, 500, 88, 118, "tgt");
        int[] tc = {144, 559};
        TerminalAnchoring ta = new TerminalAnchoring(Face.RIGHT);

        List<AbsoluteBendpointDto> path = List.of(
                new AbsoluteBendpointDto(0, 0),         // arbitrary, unchecked
                new AbsoluteBendpointDto(189, 559));    // RIGHT face line at tc.y

        assertTrue(TerminalAnchoring.preservesEndpoints(
                null, null, null, ta, tgt, tc, path));
    }

    @Test
    public void preservesEndpoints_skipsAnEndWhoseCentreIsNull_evenWithAnAnchoring() {
        // The predicate itself no longer reads a centre. preservesEndpoints still takes one per
        // end and still treats a null as "leave this end unchecked" — that is the path legacy
        // wrap-site overloads use, and they must go on skipping. A source terminal well off its
        // face line therefore passes, because the source end is never examined.
        RoutingRect src = new RoutingRect(100, 100, 100, 100, "src");
        TerminalAnchoring sa = new TerminalAnchoring(Face.RIGHT);
        List<AbsoluteBendpointDto> path = List.of(
                new AbsoluteBendpointDto(400, 150),   // nowhere near the RIGHT face line (201)
                new AbsoluteBendpointDto(400, 500));

        assertFalse("Precondition: this terminal would fail the predicate if it were checked",
                TerminalAnchoring.preservesTerminalAnchoring(sa, src, path));

        assertTrue("A null centre must still disarm that end's check",
                TerminalAnchoring.preservesEndpoints(
                        sa, src, null, null, null, null, path));
    }
}
