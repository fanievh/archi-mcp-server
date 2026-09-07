package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.model.routing.EdgeAttachmentCalculator.Face;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Executable pin for the terminal-anchoring wrap's <em>rollback policy</em> at
 * the four {@link PathStraightener} wrap sites.
 *
 * <p>Distinct from {@link TerminalAnchoringTest}, which pins the predicate, and
 * from {@code ChopboxAnchorDegeneracyTest}, which asserts the predicate's oracle
 * without ever invoking a mutator. Every test here drives a <strong>mutator</strong>
 * — the wrap-site overload — and asserts what the wrap did with the result. A test
 * that called {@link TerminalAnchoring#preservesEndpoints} directly would be a
 * tautology: it would re-assert the predicate against a hand-written path rather
 * than measure the policy where the policy actually runs.
 *
 * <p>The policy under test: a mutation is rejected on the faceline criterion only
 * when it moves a terminal <em>off a faceline that terminal was on before the
 * mutation</em>. A path that arrives with a terminal already off its faceline is not
 * rejected on that terminal's account — the criterion stops constraining an end whose
 * pre-mutation verdict was already {@code false}, so it is a rule about the predicate
 * flipping and not a guarantee that such a terminal holds its position. The two ends
 * are decided independently, and the structural arm (an augmented path collapsing
 * below four points) rejects unconditionally.
 *
 * <p>All fixtures use the <em>augmented</em> frame that the sole production caller
 * builds: a sentinel source centre at index 0 and a sentinel target centre at the
 * last index, so the real perimeter terminals sit at index 1 and index
 * {@code size - 2}. Fixture elements are deliberately unnamed — nothing here may
 * reach text measurement, so the class stays headless-safe.
 */
public class TerminalAnchoringRollbackPolicyTest {

    /** Source rect: centre (150,150); LEFT 99, RIGHT 201, TOP 99, BOTTOM 201. */
    private static final RoutingRect SRC = new RoutingRect(100, 100, 100, 100, "src");
    /** Target rect: centre (1050,150); LEFT 999, RIGHT 1101, TOP 99, BOTTOM 201. */
    private static final RoutingRect TGT = new RoutingRect(1000, 100, 100, 100, "tgt");

    private static final int[] SRC_CENTRE = {150, 150};
    private static final int[] TGT_CENTRE = {1050, 150};

    private static final TerminalAnchoring SRC_RIGHT = new TerminalAnchoring(Face.RIGHT);
    private static final TerminalAnchoring TGT_TOP = new TerminalAnchoring(Face.TOP);
    private static final TerminalAnchoring TGT_LEFT = new TerminalAnchoring(Face.LEFT);

    private static final int THRESHOLD = 20;

    /** Source terminal 9px beyond the RIGHT faceline (201) — the already-off-face arrival. */
    private static final AbsoluteBendpointDto SRC_OFF_FACE = bp(210, 150);
    /** Source terminal exactly on the RIGHT faceline. */
    private static final AbsoluteBendpointDto SRC_ON_FACE = bp(201, 150);
    /** Target terminal exactly on the TOP faceline (99). */
    private static final AbsoluteBendpointDto TGT_ON_FACE = bp(1050, 99);
    /** Target terminal 9px above the TOP faceline. */
    private static final AbsoluteBendpointDto TGT_OFF_FACE = bp(1050, 90);

    private static AbsoluteBendpointDto bp(int x, int y) {
        return new AbsoluteBendpointDto(x, y);
    }

    private static List<AbsoluteBendpointDto> path(AbsoluteBendpointDto... points) {
        return new ArrayList<>(List.of(points));
    }

    private static AbsoluteBendpointDto sourceTerminal(List<AbsoluteBendpointDto> augmented) {
        return augmented.get(1);
    }

    private static AbsoluteBendpointDto targetTerminal(List<AbsoluteBendpointDto> augmented) {
        return augmented.get(augmented.size() - 2);
    }

    // =====================================================================
    // Already-off-face input COMMITS — one per wrap site.
    // Each is red before the policy change and green after.
    // =====================================================================

    @Test
    public void snapToStraight_shouldCommitInteriorSnap_whenSourceTerminalArrivedOffFace() {
        List<AbsoluteBendpointDto> p = path(
                bp(150, 150),      // sentinel source centre
                SRC_OFF_FACE,      // real source terminal, already off the 201 faceline
                bp(210, 400),      // interior — snaps to x=215
                bp(215, 700),
                bp(900, 700),
                bp(900, 99),
                TGT_ON_FACE,       // real target terminal, on the 99 faceline
                bp(1050, 150));    // sentinel target centre
        List<AbsoluteBendpointDto> before = new ArrayList<>(p);

        PathStraightener.snapToStraight(p, THRESHOLD, List.of(),
                SRC, TGT, SRC_CENTRE, TGT_CENTRE, SRC_RIGHT, TGT_TOP, true);

        assertNotEquals("interior snap must survive: the source terminal was already off its "
                + "faceline on arrival, so nothing was broken by this mutation", before, p);
        assertEquals(bp(215, 400), p.get(2));
        assertEquals("source terminal must be byte-identical", SRC_OFF_FACE, sourceTerminal(p));
        assertEquals("target terminal must be byte-identical", TGT_ON_FACE, targetTerminal(p));
    }

    @Test
    public void eliminateReversals_shouldCommitInteriorCollapse_whenSourceTerminalArrivedOffFace() {
        List<AbsoluteBendpointDto> p = path(
                bp(150, 150),
                SRC_OFF_FACE,
                bp(210, 400),
                bp(600, 400),
                bp(600, 300),
                bp(400, 300),
                bp(400, 700),
                bp(900, 700),
                bp(900, 99),
                TGT_ON_FACE,
                bp(1050, 150));
        List<AbsoluteBendpointDto> before = new ArrayList<>(p);

        PathStraightener.eliminateReversals(p, List.of(),
                SRC, TGT, SRC_CENTRE, TGT_CENTRE, SRC_RIGHT, TGT_TOP, true);

        assertNotEquals("interior reversal collapse must survive an already-off-face arrival",
                before, p);
        assertEquals("source terminal must be byte-identical", SRC_OFF_FACE, sourceTerminal(p));
        assertEquals("target terminal must be byte-identical", TGT_ON_FACE, targetTerminal(p));
    }

    @Test
    public void collapseStaircaseJogs_shouldCommitInteriorJogCollapse_whenSourceTerminalArrivedOffFace() {
        List<AbsoluteBendpointDto> p = path(
                bp(150, 150),
                SRC_OFF_FACE,
                bp(210, 400),
                bp(300, 400),
                bp(300, 500),      // 10px jog between two parallel vertical runs
                bp(310, 500),
                bp(310, 700),
                bp(900, 700),
                bp(900, 99),
                TGT_ON_FACE,
                bp(1050, 150));
        List<AbsoluteBendpointDto> before = new ArrayList<>(p);

        PathStraightener.collapseStaircaseJogs(p, THRESHOLD, List.of(),
                SRC, TGT, SRC_CENTRE, TGT_CENTRE, SRC_RIGHT, TGT_TOP, true);

        assertNotEquals("interior jog collapse must survive an already-off-face arrival",
                before, p);
        assertEquals(bp(310, 400), p.get(3));
        assertEquals("source terminal must be byte-identical", SRC_OFF_FACE, sourceTerminal(p));
        assertEquals("target terminal must be byte-identical", TGT_ON_FACE, targetTerminal(p));
    }

    @Test
    public void collapseBends_shouldCommitInteriorCollinearRemoval_whenSourceTerminalArrivedOffFace() {
        List<AbsoluteBendpointDto> p = path(
                bp(150, 150),
                SRC_OFF_FACE,
                bp(210, 300),
                bp(500, 300),      // collinear interior middle — removed
                bp(700, 300),
                bp(700, 99),
                TGT_ON_FACE,
                bp(1050, 150));
        List<AbsoluteBendpointDto> before = new ArrayList<>(p);

        PathStraightener.collapseBends(p, List.of(),
                SRC, TGT, SRC_CENTRE, TGT_CENTRE, SRC_RIGHT, TGT_TOP, true);

        assertNotEquals("interior collinear removal must survive an already-off-face arrival",
                before, p);
        assertEquals(7, p.size());
        assertEquals("source terminal must be byte-identical", SRC_OFF_FACE, sourceTerminal(p));
        assertEquals("target terminal must be byte-identical", TGT_ON_FACE, targetTerminal(p));
    }

    // =====================================================================
    // The policy still bites — an ON-face terminal moved OFF is still rolled
    // back. Green before and after; these pin that the fix did not widen into
    // a no-op.
    // =====================================================================

    @Test
    public void snapToStraight_shouldRollBack_whenMutationDragsAnOnFaceSourceTerminalOffIt() {
        List<AbsoluteBendpointDto> p = path(
                bp(150, 150),
                SRC_ON_FACE,       // on the 201 faceline
                bp(210, 400),      // 9px away — snaps the terminal itself to x=210
                bp(900, 400),
                bp(900, 99),
                TGT_ON_FACE,
                bp(1050, 150));
        List<AbsoluteBendpointDto> before = new ArrayList<>(p);

        PathStraightener.snapToStraight(p, THRESHOLD, List.of(),
                SRC, TGT, SRC_CENTRE, TGT_CENTRE, SRC_RIGHT, TGT_TOP, true);

        assertEquals("a mutation that drags an on-face source terminal off its faceline "
                + "must still be rolled back", before, p);
    }

    @Test
    public void collapseStaircaseJogs_shouldRollBack_whenMutationDragsAnOnFaceSourceTerminalOffIt() {
        List<AbsoluteBendpointDto> p = path(
                bp(150, 150),
                SRC_ON_FACE,
                bp(201, 140),
                bp(208, 140),      // 7px jog whose collapse relocates the terminal to x=208
                bp(208, 600),
                bp(900, 600),
                bp(900, 99),
                TGT_ON_FACE,
                bp(1050, 150));
        List<AbsoluteBendpointDto> before = new ArrayList<>(p);

        PathStraightener.collapseStaircaseJogs(p, THRESHOLD, List.of(),
                SRC, TGT, SRC_CENTRE, TGT_CENTRE, SRC_RIGHT, TGT_TOP, true);

        assertEquals("a jog collapse that relocates an on-face source terminal must still be "
                + "rolled back", before, p);
    }

    @Test
    public void collapseBends_shouldRollBack_whenMutationRemovesAnOnFaceSourceTerminal() {
        List<AbsoluteBendpointDto> p = path(
                bp(150, 150),
                SRC_ON_FACE,       // collinear with the sentinel and the next point — removed
                bp(400, 150),
                bp(400, 600),
                bp(900, 600),
                bp(900, 99),
                TGT_ON_FACE,
                bp(1050, 150));
        List<AbsoluteBendpointDto> before = new ArrayList<>(p);

        PathStraightener.collapseBends(p, List.of(),
                SRC, TGT, SRC_CENTRE, TGT_CENTRE, SRC_RIGHT, TGT_TOP, true);

        assertEquals("removing an on-face source terminal changes which point is bp0 and must "
                + "still be rolled back", before, p);
    }

    /**
     * The fourth site's counterpart to the three above.
     *
     * <p>{@code eliminateReversalsCore} <strong>cannot</strong> move either terminal in the
     * augmented frame, so the literal shape of the other three tests is unreachable here and
     * this test proves the impossibility instead of asserting a rollback that can never fire.
     * The removal range is {@code [i+1 .. j]} and the L-turn insertion index is {@code i+1};
     * the terminal guard skips every pair with {@code i == 0} or {@code j >= size - 2}, so
     * indices {@code 1} and {@code size - 2} are never removed and never written. The rollback
     * census over the six fixtures corroborates it: this site produced zero mutation-broke
     * rollbacks across 170 connections, the only one being the structural arm.
     *
     * <p>The sweep is deterministic, not randomised, and goes red the moment the terminal guard
     * is dropped — which is precisely the widening this pin exists to catch.
     */
    @Test
    public void eliminateReversals_shouldNeverRelocateEitherTerminal_acrossAnAugmentedReversalFamily() {
        int cases = 0;
        for (int overshoot = 220; overshoot <= 700; overshoot += 60) {
            for (int back = 210; back < overshoot; back += 70) {
                for (int depth = 200; depth <= 800; depth += 150) {
                    List<AbsoluteBendpointDto> p = path(
                            bp(150, 150),
                            SRC_ON_FACE,
                            bp(overshoot, 150),
                            bp(overshoot, depth),
                            bp(back, depth),
                            bp(back, depth + 300),
                            bp(900, depth + 300),
                            bp(900, 99),
                            TGT_ON_FACE,
                            bp(1050, 150));

                    PathStraightener.eliminateReversals(p, List.of(),
                            SRC, TGT, SRC_CENTRE, TGT_CENTRE, SRC_RIGHT, TGT_TOP, true);

                    assertEquals("source terminal relocated by eliminateReversals",
                            SRC_ON_FACE, sourceTerminal(p));
                    assertEquals("target terminal relocated by eliminateReversals",
                            TGT_ON_FACE, targetTerminal(p));
                    cases++;
                }
            }
        }
        assertTrue("sweep must actually execute cases", cases >= 40);
    }

    // =====================================================================
    // The two ends are decided INDEPENDENTLY. Each fixture has the OTHER end
    // already off-face, so a single conjunctive flip check fails both.
    // =====================================================================

    @Test
    public void collapseBends_shouldRollBack_whenTargetIsDraggedOffFace_evenThoughSourceArrivedOffFace() {
        List<AbsoluteBendpointDto> p = path(
                bp(150, 150),
                SRC_OFF_FACE,      // source ALREADY off its faceline
                bp(210, 600),
                bp(1050, 600),
                TGT_ON_FACE,       // target on the 99 faceline — collinear, so it is removed
                bp(1050, 150));
        List<AbsoluteBendpointDto> before = new ArrayList<>(p);

        PathStraightener.collapseBends(p, List.of(),
                SRC, TGT, SRC_CENTRE, TGT_CENTRE, SRC_RIGHT, TGT_TOP, true);

        assertEquals("the target end must be judged on its own: an already-off-face SOURCE must "
                + "not license dragging the TARGET off its faceline", before, p);
    }

    @Test
    public void collapseBends_shouldRollBack_whenSourceIsDraggedOffFace_evenThoughTargetArrivedOffFace() {
        List<AbsoluteBendpointDto> p = path(
                bp(150, 150),
                SRC_ON_FACE,       // collinear with the sentinel and (400,150) — removed
                bp(400, 150),
                bp(400, 600),
                bp(900, 600),
                bp(900, 90),
                TGT_OFF_FACE,      // target ALREADY off its faceline
                bp(1050, 150));
        List<AbsoluteBendpointDto> before = new ArrayList<>(p);

        PathStraightener.collapseBends(p, List.of(),
                SRC, TGT, SRC_CENTRE, TGT_CENTRE, SRC_RIGHT, TGT_TOP, true);

        assertEquals("the source end must be judged on its own: an already-off-face TARGET must "
                + "not license dragging the SOURCE off its faceline", before, p);
    }

    // =====================================================================
    // The unaugmented frame takes the same delta rule.
    // =====================================================================

    /**
     * The wrap-site overloads accept {@code augmented = false}, and this change
     * switched that frame from post-state-only to the same delta rule. No caller
     * in the tree passes {@code false} to a wrap-site overload — production uses
     * the augmented frame and the legacy overloads bypass the wrap entirely — so
     * the frame is currently unreachable. It is pinned anyway: an unreachable
     * branch that silently changed behaviour is exactly the kind that acquires a
     * caller later and surprises whoever adds it.
     *
     * <p>In this frame the terminals are {@code path[0]} and {@code path[last]}
     * with no sentinels, and the structural arm does not apply.
     */
    @Test
    public void snapToStraight_shouldCommitInteriorSnap_whenUnaugmentedAndSourceTerminalArrivedOffFace() {
        List<AbsoluteBendpointDto> p = path(
                SRC_OFF_FACE,      // terminal at index 0 — no sentinel in this frame
                bp(210, 400),
                bp(215, 700),
                bp(900, 700),
                bp(900, 99),
                TGT_ON_FACE);      // terminal at the last index
        List<AbsoluteBendpointDto> before = new ArrayList<>(p);

        PathStraightener.snapToStraight(p, THRESHOLD, List.of(),
                SRC, TGT, SRC_CENTRE, TGT_CENTRE, SRC_RIGHT, TGT_TOP, false);

        assertNotEquals("the unaugmented frame takes the same delta rule", before, p);
        assertEquals(SRC_OFF_FACE, p.get(0));
        assertEquals(TGT_ON_FACE, p.get(p.size() - 1));
    }

    /**
     * The counterpart "the policy still bites" test cannot exist in the
     * unaugmented frame, and this pins why rather than asserting a rollback
     * that can never fire.
     *
     * <p>With no sentinels the terminals sit at index {@code 0} and index
     * {@code last}, and every mutator's loop bounds exclude both:
     * {@code snapToStraightCore} and {@code collapseStaircaseJogsCore} start at
     * {@code i = 1} and write only at {@code i}; {@code collapseBendsCore}
     * removes {@code i + 1} for {@code i >= 0}, so never index 0, and stops at
     * {@code size - 2}; {@code eliminateReversalsCore} removes the range
     * {@code [i+1 .. j]} with {@code j <= size - 2}. No mutation can move either
     * terminal, so no flip is constructible.
     *
     * <p>What the delta rule <em>does</em> change in this frame is the commit
     * direction, which the sibling test above covers: previously every mutation
     * on an off-face path was rejected, and now the interior change survives.
     */
    @Test
    public void allFourMutators_shouldNeverTouchEitherTerminal_inTheUnaugmentedFrame() {
        int cases = 0;
        for (int mid = 300; mid <= 700; mid += 100) {
            for (int depth = 300; depth <= 700; depth += 200) {
                List<AbsoluteBendpointDto> template = path(
                        SRC_ON_FACE,
                        bp(mid, 150),
                        bp(mid, depth),
                        bp(mid + 120, depth),
                        bp(mid + 120, 99),
                        bp(900, 99),
                        TGT_ON_FACE);

                for (int mutator = 0; mutator < 4; mutator++) {
                    List<AbsoluteBendpointDto> p = new ArrayList<>(template);
                    switch (mutator) {
                        case 0 -> PathStraightener.snapToStraight(p, THRESHOLD, List.of(),
                                SRC, TGT, SRC_CENTRE, TGT_CENTRE, SRC_RIGHT, TGT_TOP, false);
                        case 1 -> PathStraightener.eliminateReversals(p, List.of(),
                                SRC, TGT, SRC_CENTRE, TGT_CENTRE, SRC_RIGHT, TGT_TOP, false);
                        case 2 -> PathStraightener.collapseStaircaseJogs(p, THRESHOLD, List.of(),
                                SRC, TGT, SRC_CENTRE, TGT_CENTRE, SRC_RIGHT, TGT_TOP, false);
                        default -> PathStraightener.collapseBends(p, List.of(),
                                SRC, TGT, SRC_CENTRE, TGT_CENTRE, SRC_RIGHT, TGT_TOP, false);
                    }
                    assertEquals("mutator " + mutator + " moved the source terminal",
                            SRC_ON_FACE, p.get(0));
                    assertEquals("mutator " + mutator + " moved the target terminal",
                            TGT_ON_FACE, p.get(p.size() - 1));
                    cases++;
                }
            }
        }
        assertTrue("sweep must actually execute cases", cases >= 40);
    }

    // =====================================================================
    // An already-off-face terminal is pinned at the two sites whose write
    // range can reach a terminal, and only there.
    // =====================================================================

    /**
     * The flip rule stops constraining an end once that end arrived off-face, so
     * without a second arm a mutator could rewrite, displace or delete such a
     * terminal unchallenged. These tests pin the second arm at the two sites that
     * need it.
     *
     * <p>Both fixtures are synthetic. Neither shape occurred anywhere in the six
     * routing fixtures, so a corpus-derived test could not reach them — but the
     * write ranges make them reachable, and a guard whose branch is reachable and
     * untested is the one that rots. Each of these fails without the pin.
     */
    @Test
    public void snapToStraight_shouldRollBack_whenItRelocatesAnAlreadyOffFaceSourceTerminal() {
        List<AbsoluteBendpointDto> p = path(
                bp(150, 150),
                SRC_OFF_FACE,      // off the 201 faceline; snaps 9px to meet the next point
                bp(219, 400),
                bp(900, 400),
                bp(900, 99),
                TGT_ON_FACE,
                bp(1050, 150));
        List<AbsoluteBendpointDto> before = new ArrayList<>(p);

        PathStraightener.snapToStraight(p, THRESHOLD, List.of(),
                SRC, TGT, SRC_CENTRE, TGT_CENTRE, SRC_RIGHT, TGT_TOP, true);

        assertEquals("an already-off-face terminal is not licence to relocate it", before, p);
    }

    /**
     * The target-end mirror, and the more damaging shape of the two: the write
     * lands the terminal exactly on the target centre, which is the degenerate
     * zero-length anchor ray the pipeline keeps a separate stage to repair.
     */
    @Test
    public void snapToStraight_shouldRollBack_whenItRelocatesAnAlreadyOffFaceTargetTerminal() {
        List<AbsoluteBendpointDto> p = path(
                bp(150, 150),
                SRC_ON_FACE,
                bp(600, 150),
                bp(1040, 150),     // target terminal, off the 999 LEFT faceline
                bp(1050, 150));
        List<AbsoluteBendpointDto> before = new ArrayList<>(p);

        PathStraightener.snapToStraight(p, THRESHOLD, List.of(),
                SRC, TGT, SRC_CENTRE, TGT_CENTRE, SRC_RIGHT, TGT_LEFT, true);

        assertEquals("the mutation would have moved the target terminal onto the element centre",
                before, p);
    }

    /**
     * {@code collapseStaircaseJogs} rewrites one point and removes two, so a
     * terminal it removes need not be collinear with anything — unlike
     * {@code collapseBends}. The route genuinely changes shape, which is why this
     * site pins.
     */
    @Test
    public void collapseStaircaseJogs_shouldRollBack_whenItDeletesAnAlreadyOffFaceTargetTerminal() {
        List<AbsoluteBendpointDto> p = path(
                bp(150, 150),
                SRC_ON_FACE,
                bp(1040, 400),
                bp(1040, 90),
                bp(1050, 90),      // target terminal, off the 99 TOP faceline, NOT collinear
                bp(1050, 150));
        List<AbsoluteBendpointDto> before = new ArrayList<>(p);

        PathStraightener.collapseStaircaseJogs(p, THRESHOLD, List.of(),
                SRC, TGT, SRC_CENTRE, TGT_CENTRE, SRC_RIGHT, TGT_TOP, true);

        assertEquals("this collapse would delete the target terminal and reshape the route",
                before, p);
    }

    // =====================================================================
    // collapseBends is deliberately NOT pinned, and this is why.
    // =====================================================================

    /**
     * {@code collapseBends} may remove a terminal, and that is allowed, because it
     * can only ever remove a point that is collinear with its two neighbours.
     * Removing the source terminal forces the window {@code i == 0}, so the triple
     * is (centre sentinel, terminal, next) — the removed point lay on the ray the
     * ChopboxAnchor already draws along, and the rendered polyline is unchanged.
     *
     * <p>This is the load-bearing justification for leaving the site unpinned, so
     * it is asserted rather than left in a comment: the test drives the mutator and
     * checks the drawn polyline, centre included, is identical across the call.
     * Relax the collinearity condition in {@code collapseBendsCore} and this goes
     * red, which is the signal that the site must start pinning.
     *
     * <p>Driven through the legacy overload so the assertion measures the mutator's
     * own precondition rather than the wrap's decision about it.
     */
    @Test
    public void collapseBends_shouldLeaveTheDrawnPolylineIdentical_whenItRemovesACollinearSourceTerminal() {
        AbsoluteBendpointDto centre = bp(150, 150);
        List<AbsoluteBendpointDto> p = path(
                centre,
                SRC_OFF_FACE,      // (210,150) — collinear with the centre and the next point
                bp(400, 150),
                bp(400, 600),
                bp(900, 600),
                bp(900, 99),
                TGT_ON_FACE,
                bp(1050, 150));
        List<AbsoluteBendpointDto> drawnBefore = drawnPolyline(p);

        PathStraightener.collapseBends(p, List.of());

        assertEquals("the source terminal was removed", bp(400, 150), sourceTerminal(p));
        assertEquals("but the drawn polyline is unchanged — the removed point was collinear "
                + "with the element centre, so the anchor ray is the same",
                drawnBefore, drawnPolyline(p));
    }

    @Test
    public void collapseBends_shouldLeaveTheDrawnPolylineIdentical_whenItRemovesACollinearTargetTerminal() {
        List<AbsoluteBendpointDto> p = path(
                bp(150, 150),
                SRC_ON_FACE,
                bp(201, 600),
                bp(700, 600),
                bp(1050, 600),
                bp(1050, 90),      // target terminal — collinear with (1050,600) and the centre
                bp(1050, 150));
        List<AbsoluteBendpointDto> drawnBefore = drawnPolyline(p);

        PathStraightener.collapseBends(p, List.of());

        assertEquals("the drawn polyline is unchanged at the target end too",
                drawnBefore, drawnPolyline(p));
    }

    /**
     * The wrap-side half of the argument. The two tests above prove the removal is
     * render-neutral; this one pins that {@code collapseBends} is actually allowed
     * to perform it — that the site does not pin off-face terminals.
     *
     * <p>Driven through the wrap-site overload, because that is the decision under
     * test. Turn the pin on at this site and the collinear removal is rolled back
     * and this goes red, which is the guard against "tighten everything for
     * symmetry" — the change that would silently cost a legitimate
     * redundant-bendpoint removal.
     */
    @Test
    public void collapseBends_shouldCommitACollinearTerminalRemoval_throughTheWrap() {
        List<AbsoluteBendpointDto> p = path(
                bp(150, 150),
                SRC_OFF_FACE,      // collinear with the centre sentinel and (400,150)
                bp(400, 150),
                bp(400, 600),
                bp(900, 600),
                bp(900, 99),
                TGT_ON_FACE,
                bp(1050, 150));
        List<AbsoluteBendpointDto> before = new ArrayList<>(p);

        PathStraightener.collapseBends(p, List.of(),
                SRC, TGT, SRC_CENTRE, TGT_CENTRE, SRC_RIGHT, TGT_TOP, true);

        assertNotEquals("the wrap must not pin an off-face terminal at this site — the removal "
                + "is render-neutral by construction", before, p);
        assertEquals(bp(400, 150), sourceTerminal(p));
    }

    /**
     * Pins the precondition the whole {@code collapseBends} exemption rests on:
     * removal requires <em>exact</em> collinearity.
     *
     * <p>The terminal here sits 5px off the centre's axis — near-collinear, not
     * collinear — so nothing may be removed. Widen the condition to a tolerance and
     * this terminal starts being deleted while genuinely displacing the drawn route,
     * at which point the site's exemption is no longer sound and it must begin
     * pinning. That is the rot this test exists to catch, and no other test in the
     * suite catches it.
     */
    @Test
    public void collapseBends_shouldNotRemoveANearCollinearTerminal_onlyAnExactlyCollinearOne() {
        List<AbsoluteBendpointDto> p = path(
                bp(150, 150),
                bp(210, 145),      // 5px off the centre's y axis — NOT collinear
                bp(400, 150),
                bp(400, 600),
                bp(900, 600),
                bp(900, 99),
                bp(1050, 99),
                bp(1050, 150));
        List<AbsoluteBendpointDto> before = new ArrayList<>(p);

        PathStraightener.collapseBends(p, List.of());

        assertEquals("only an exactly-collinear point may be removed; a tolerance here would "
                + "delete a terminal and move the drawn route", before, p);
    }

    /**
     * The rendered shape: consecutive duplicate and collinear points removed, since
     * neither is visible on the canvas. Two paths with the same drawn polyline are
     * drawn identically by the renderer.
     */
    private static List<AbsoluteBendpointDto> drawnPolyline(List<AbsoluteBendpointDto> path) {
        List<AbsoluteBendpointDto> out = new ArrayList<>();
        for (AbsoluteBendpointDto pt : path) {
            if (!out.isEmpty() && out.get(out.size() - 1).equals(pt)) {
                continue;
            }
            out.add(pt);
        }
        for (int i = 1; i < out.size() - 1; ) {
            AbsoluteBendpointDto a = out.get(i - 1);
            AbsoluteBendpointDto b = out.get(i);
            AbsoluteBendpointDto c = out.get(i + 1);
            boolean collinear = (a.x() == b.x() && b.x() == c.x())
                    || (a.y() == b.y() && b.y() == c.y());
            if (collinear) {
                out.remove(i);
            } else {
                i++;
            }
        }
        return out;
    }

    // =====================================================================
    // The structural arm is unconditional.
    // =====================================================================

    @Test
    public void collapseBends_shouldRollBack_whenAugmentedPathCollapsesBelowFourPoints_evenWhenBothTerminalsArrivedOffFace() {
        List<AbsoluteBendpointDto> p = path(
                bp(150, 150),
                SRC_OFF_FACE,      // off the 201 RIGHT faceline
                bp(500, 150),
                bp(900, 150),      // target terminal, off the 999 LEFT faceline
                bp(1050, 150));
        List<AbsoluteBendpointDto> before = new ArrayList<>(p);

        PathStraightener.collapseBends(p, List.of(),
                SRC, TGT, SRC_CENTRE, TGT_CENTRE, SRC_RIGHT, TGT_LEFT, true);

        assertEquals("a fully-collinear augmented path collapsing to [sourceCentre, targetCentre] "
                + "wipes both real terminals and must be rejected regardless of the pre-state",
                before, p);
    }
}
