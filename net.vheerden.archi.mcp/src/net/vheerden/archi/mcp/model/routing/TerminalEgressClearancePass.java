package net.vheerden.archi.mcp.model.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntPredicate;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.model.routing.EdgeAttachmentCalculator.Face;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Corridor-aware terminal-egress clearance.
 *
 * <p><b>The defect (live-geometry pinpoint 2026-05-21).</b> The terminal anchor sits
 * exactly 1px outside the face ({@link TerminalAnchoring#lineCoordinate} = edge&plusmn;1).
 * When the orthogonal router emits a path whose <em>first segment off the terminal runs
 * parallel to that same face</em> (constant on the orthogonal axis, at the face&plusmn;1
 * line), the connection "hugs" its own element edge for a stretch before bending away — the
 * {@code connectionEdgeCoincidence} defect re-discovered by eye. Live
 * evidence (Retail Bank Views D + G) showed 13&ndash;445px of free clearance beside these
 * hugs &mdash; they are <em>router-eliminable</em> on feasible/open views, NOT a topology
 * floor (the floor is real only on tight hub corridors, HH).
 *
 * <p><b>Lineage — this is the re-implementation of a REJECTED v1, with two faults fixed.</b>
 * The v1 ({@code .reference})
 * proved the <em>core transform</em> sound (live existence-proof D M4 1&rarr;0
 * good&rarr;excellent; 3 GREEN unit tests) but was rejected at the HH unit-oracle gate:
 * {@code V_p10} regressed 4.0&rarr;3.4 while M4 stayed 5. Two structural faults, both fixed
 * here:
 * <ol>
 *   <li><b>Fix 1 — validate at pipeline-END, not in-stage.</b> v1 hooked into
 *       {@link HubPerimeterRoutingStage} (its in-stage {@code verifyMetricMonotonicity} ran
 *       at {@code RoutingPipeline:~1216}); the later geometry-mutating stages 4.7p / 4.7p+1 /
 *       4.7q / 4.7r re-processed the inserted bendpoints into the narrow gap the assessor
 *       measures at the END, so the in-stage guard verified a state downstream stages then
 *       changed. This pass runs as the LAST geometry-mutating stage
 *       ({@link #run} is invoked after 4.7r, before 4.8 label positioning), so its
 *       per-proposal validation is, by construction, a FINAL-pipeline-state validation —
 *       nothing downstream re-mutates bendpoints (4.8 only positions labels). A push that
 *       does not net-improve the final M4 (must drop) without regressing V_p10 / HPQ /
 *       Tier-1 is rolled back byte-identical ({@link #netImproves}).</li>
 *   <li><b>Fix 2 — gate on parallel-CONNECTION-gap room, not just element-edge clearance.</b>
 *       v1's room search ({@link #findClearedOrthogonal}) cleared element edges only, so on a
 *       tight hub the cleared coordinate landed close to a neighbouring connection &mdash;
 *       narrowing V_p10. Here a candidate coordinate is accepted only if it also clears every
 *       neighbouring <em>co-axial, span-overlapping connection segment</em> by
 *       {@code requiredConnGap = max(MIN_CONNECTION_GAP_PX, prePassVp10)}
 *       ({@link #collectNeighbouringConnectionSegments}). <b>Soundness (V-axis &mdash; the v1
 *       failure axis).</b> For a vertical hug the new run is a vertical segment and
 *       {@code requiredConnGap} is floored at the view's pre-pass V_p10 (the exact quantity
 *       {@code computeVAxisParallelGapP10} / the HH oracle pin measure), so the new run stays
 *       &ge; pre-pass V_p10 from every co-axial vertical neighbour while every other pairwise
 *       gap is unchanged &mdash; the view's V-axis parallel-gap p10 <b>cannot decrease</b>, so
 *       the v1 V_p10 4.0&rarr;3.4 regression is structurally impossible. <b>H-axis (horizontal
 *       hugs).</b> {@code requiredConnGap} is still derived from V_p10, so a
 *       horizontal run is held &ge; {@code max(MIN_CONNECTION_GAP_PX, ceil(V_p10))} from every
 *       co-axial horizontal neighbour &mdash; i.e. an H-axis gap can never fall below the
 *       {@code MIN_CONNECTION_GAP_PX}=8 floor, but a strict H-axis-p10 non-regression is NOT
 *       separately claimed (no H-axis p10 metric is computed; both the assessor pin and
 *       {@link #netImproves}'s V_p10 are V-axis). A symmetric H-axis floor was deferred here — it
 *       is now IMPLEMENTED by the Option B successor (see the SUPERSEDED note below).
 *       A cheap view-level pre-gate ({@link #PRE_GATE_VP10_PX}) additionally skips the whole
 *       pass when the pre-pass V_p10 is already narrow (the "would have skipped HH wholesale"
 *       filter).
 *       <p><b>SUPERSEDED by Option B (2026-06-29) — the V_p10-monotone invariant was both
 *       wrong-axis and over-strict.</b> On view 1.2 the original guard wrongly gated a HORIZONTAL
 *       hug ({@code e5946477}) on the VERTICAL p10 (238) and, even axis-corrected, rolled the only
 *       available stub back because it reduced a huge-healthy V_p10=238 to a still-healthy ~151 —
 *       the same absolute "no reduction" rule that correctly blocks the v1 4.0&rarr;3.4 danger-zone
 *       regression. Option B replaces both halves: {@code requiredConnGap} is now the flat
 *       {@link #HEALTHY_PARALLEL_GAP_PX} (proposal-time, applied to the axis-correct co-axial
 *       neighbour set), and {@link #netImproves} enforces a per-axis HEALTHY FLOOR on BOTH
 *       {@code vP10} and {@code hP10} ({@link HubPerimeterRoutingStage#computeHAxisParallelGapP10}
 *       is the new H-axis mirror) rather than strict non-regression. The v1 protection is preserved
 *       (a 4.0&rarr;3.4 push stays blocked: 3.4 &lt; 15); healthy-to-healthy reductions on
 *       sparse-but-open views are now permitted. Idempotency holds (a fixed hug no longer drops
 *       M4 → no re-fire), so the floor cannot ratchet a field down across repeated routes.</li>
 * </ol>
 *
 * <p><b>The transform (distinct from {@link TerminalSegmentCorridorMigrator}).</b> The
 * migrator <em>slides the attachment along the face</em> (changes the slot) for size-3
 * L-shapes. This sibling does the orthogonal transform: it <em>lengthens the perpendicular
 * egress stub</em> so the parallel run clears the face by a real margin. It keeps the
 * terminal bendpoint <b>completely unchanged</b> (so
 * {@link TerminalAnchoring#preservesTerminalAnchoring} holds by construction &mdash; and the
 * hub <em>slot</em>, the terminal's parallel coordinate, is preserved, so HPQ cannot regress
 * from the transform; this is strictly stronger than the hand-route
 * that slipped HPQ 1.0&rarr;0.8 by moving the slot) and instead:
 * <ol>
 *   <li>inserts one new bendpoint {@code A'} between the terminal {@code A} and the interior
 *       corner {@code B}, at {@code A}'s parallel coordinate but pushed out to the cleared
 *       orthogonal coordinate; and</li>
 *   <li>moves the corner {@code B} to the same cleared orthogonal coordinate.</li>
 * </ol>
 *
 * <p><b>Slot-awareness.</b> Because the terminal is byte-identical the hub slot is
 * preserved by construction. The only residual risk &mdash; two pushes landing on the same
 * orthogonal coordinate &rarr; coincident parallel runs &mdash; is prevented because
 * {@link #run} processes connections sequentially and re-discovers each hug against the
 * CURRENT (post-applied-predecessors) geometry, so Fix-2's connection-gap check sees an
 * already-pushed predecessor as a neighbour and keeps {@code requiredConnGap} away from it.
 *
 * <p><b>Guards.</b> Per-proposal: (i) the terminal bendpoint is byte-identical (by
 * construction) and re-checked via {@link TerminalAnchoring#preservesTerminalAnchoring};
 * (ii) a Tier-1 self-check ({@link #tier1Clean}: every segment axis-aligned &mdash; no
 * zigzag &mdash; and no segment strictly pierces a foreign element). Because this pass is the
 * LAST geometry stage, that self-check is evaluated at the final geometry, re-verifying
 * Tier-1 at the final state. Per-view: {@link #netImproves} keeps an apply only when it
 * STRICTLY improves on at least one of two signals &mdash; M4 (the &ge;10px edge-coincidence
 * count) OR the off-face parallel-terminal stub count ({@link #computeOffFaceStubCount}, the
 * sub-M4 signal that catches SHORT &lt;10px own-face micro-hugs M4 cannot see) &mdash; and only
 * when M4 does not regress, neither parallel-gap axis p10 drops below
 * {@link #HEALTHY_PARALLEL_GAP_PX} (Option B), and HPQ does not regress. The M4 / V_p10 / H_p10 /
 * HPQ computations REUSE the shipped {@link HubPerimeterRoutingStage} package-private mirror
 * statics ({@link HubPerimeterRoutingStage#computeM4Count},
 * {@link HubPerimeterRoutingStage#computeVAxisParallelGapP10},
 * {@link HubPerimeterRoutingStage#computeHAxisParallelGapP10},
 * {@link HubPerimeterRoutingStage#computeHpq}); the off-face stub count is computed in-pass
 * ({@link #computeOffFaceStubCount}, mirroring {@code LayoutQualityAssessor.OFF_FACE_MIN_STUB_PX})
 * so this pass adds NO new HubPerimeterRoutingStage drift surface.
 *
 * <p><b>MVP scope.</b> One proposal per path (source side preferred, else target side); the
 * hug segment must be the terminal-incident segment and the corner {@code B} must be an
 * interior bendpoint (not the opposite terminal). Pure geometry &mdash; no EMF/SWT/PDE;
 * callable from standard JUnit.
 *
 * <p><b>View G container-nested hub support.</b>
 * Two scope boundaries that made the predecessor a sound no-op on view G's container-nested hub are
 * lifted, both confined to this class:
 * <ol>
 *   <li><b>Ancestor-aware Tier-1.</b> The {@link #tier1Clean} foreign-passthrough check now
 *       runs against the per-connection ANCESTOR-EXCLUDED obstacle set
 *       ({@link RoutingPipeline.ConnectionEndpoints#obstacles()} &mdash; "source/target/ancestors
 *       already excluded"), not the full {@code allObstacles}, so a cleared run may lie inside the
 *       target's/source's own enclosing container (the legitimate corridor wall of a container-nested
 *       hub) without being flagged as a passthrough. A genuine foreign element is still present in
 *       that set, so a real passthrough is still rejected (soundness). Own-face hug detection
 *       ({@link #collectOverlappingEdges} + the edge-clearing in {@link #findClearedOrthogonal})
 *       deliberately stays on {@code allObstacles} (it needs the element's own face). When
 *       {@code conn.obstacles()} is empty (no foreign elements exist) it falls back to
 *       {@code allObstacles} &mdash; a sound over-reject, never a foreign passthrough.</li>
 *   <li><b>Retry past a Tier-1-rejected candidate.</b> {@link #findClearedOrthogonal} now
 *       advances its outward search past a candidate that clears element edges + connection gaps but
 *       fails the per-candidate Tier-1 feasibility, returning the smallest clean coordinate
 *       within the UNCHANGED [{@link #TARGET_EGRESS_CLEARANCE_PX}, {@link #MAX_EGRESS_CLEARANCE_PX}]
 *       cap. Relocation beyond the 64px stub cap stays OUT OF SCOPE (a distinct future lever).</li>
 * </ol>
 *
 * @see TerminalSegmentCorridorMigrator
 * @see HubPerimeterRoutingStage
 * @see TerminalAnchoring
 */
public class TerminalEgressClearancePass {

    /** Mirror of {@code LayoutQualityAssessor.EDGE_COINCIDENCE_TOLERANCE_PX} (3.0). */
    static final double EDGE_COINCIDENCE_TOLERANCE_PX = 3.0;
    /** Mirror of {@code LayoutQualityAssessor.EDGE_COINCIDENCE_MIN_OVERLAP_PX} (10.0). */
    static final double EDGE_COINCIDENCE_MIN_OVERLAP_PX = 10.0;
    /**
     * Mirror of {@code LayoutQualityAssessor.OFF_FACE_MIN_STUB_PX} (8.0) — the perpendicular
     * clearance below which a face-parallel terminal stub counts as an off-face hug. Used by the
     * sub-M4 keep-signal ({@link #computeOffFaceStubCount}): a SHORT (&lt;10px) own-face micro-hug
     * is invisible to M4 (which, like the assessor, requires &ge;10px parallel overlap), so the
     * stub-count drop is what lets {@link #netImproves} keep the fix instead of rolling it back.
     */
    static final double OFF_FACE_MIN_STUB_PX = 8.0;
    /** Mirror of {@code LayoutQualityAssessor.ZIGZAG_AXIS_TOLERANCE_PX} (1.0). */
    static final double ZIGZAG_AXIS_TOLERANCE_PX = 1.0;
    /** Safety margin beyond the M4 tolerance the cleared run must hold (parity with migrator). */
    static final double CLEAR_SAFETY_MARGIN_PX = 2.0;

    /** Smallest egress clearance (px, from the geometric edge) to push the parallel run to. */
    static final int TARGET_EGRESS_CLEARANCE_PX = 8;
    /** Outward search bound (px). Beyond this we degrade to a no-op rather than a long detour. */
    static final int MAX_EGRESS_CLEARANCE_PX = 64;

    /**
     * Absolute connection-gap floor (px), mirroring {@code RoutingPipeline.MIN_CLEARANCE} (8).
     * <p><b>Superseded as the live floor by {@link #HEALTHY_PARALLEL_GAP_PX}</b> (the old
     * {@code max(MIN_CONNECTION_GAP_PX, ceil(V_p10))} formula and its helper were removed). Retained
     * for the historical Fix-2 description in the class Javadoc; not referenced by any current
     * code path.
     */
    static final int MIN_CONNECTION_GAP_PX = 8;

    /**
     * Healthy parallel-connection gap floor (px). The egress push must keep the cleared run at
     * least this far from co-axial neighbour connection segments (proposal-time,
     * {@link #findClearedOrthogonal}), and {@link #netImproves} rejects any push that drops a
     * per-axis parallel-gap p10 below it (final-state). This REPLACES the original "never reduce
     * V_p10" strict-monotone invariant, which (a) over-declined on sparse-but-open views — the only
     * available egress stub can reduce a huge-healthy V_p10 (e.g. 238) to a still-healthy one (e.g.
     * ~151), which the absolute guard forbade exactly as it forbids the real v1 danger-zone
     * 4.0&rarr;3.4 regression — and (b) gated a horizontal hug on the VERTICAL p10 (wrong axis). The
     * floor preserves the v1 protection (a 4.0&rarr;3.4 push stays blocked: 3.4 &lt; 15) while
     * permitting healthy-to-healthy reductions. 15 = the assessor's tightest narrow-gap bucket;
     * revisit if a live render disagrees.
     */
    static final int HEALTHY_PARALLEL_GAP_PX = 15;

    /**
     * View-level pre-gate (px). When the pre-pass V_p10 parallel-connection gap is
     * below this, the whole pass is skipped (the "would have skipped HH wholesale" filter;
     * HH p10 &approx; 4&ndash;6 &lt; 8, open D/G fire) ({@code T_pregate = MIN_CLEARANCE = 8}).
     * Belt-and-braces only &mdash; soundness comes
     * from the connection-gap floor + {@link #netImproves}, so a mis-tuned pre-gate can never cause a
     * regression, only a (logged) wholesale skip.
     */
    static final double PRE_GATE_VP10_PX = 8.0;

    /**
     * A proposed egress-clearance transform: the entire post-transform bendpoint list for one
     * connection's path. {@link #apply} swaps it in; {@link #restore} reverts. Carrying the
     * whole list (paths are short) keeps apply/restore index-shift-proof across the inserted
     * bendpoint.
     */
    public record EgressProposal(String connectionId, int pathIndex,
                                 List<AbsoluteBendpointDto> newPath) {}

    /** Restore token: the pre-transform bendpoint list for {@code pathIndex}. */
    public record Snapshot(int pathIndex, List<AbsoluteBendpointDto> oldPath) {}

    /**
     * Per-run aggregate stats for diagnostics + integration-test verification.
     *
     * @param applied             egress transforms committed (net-improved the final state)
     * @param rolled              transforms applied then rolled back by {@link #netImproves}
     * @param skippedByPreGate    true when the pre-gate skipped the whole pass
     * @param proposalsEvaluated  hugs for which a Tier-1-clean, connection-gap-aware
     *                            proposal was emitted and validated
     */
    public record Result(int applied, int rolled, boolean skippedByPreGate, int proposalsEvaluated) {}

    // =====================================================================
    // Final-state-validated, sequential, slot-aware orchestration.
    // The single production entry point — invoked by RoutingPipeline as the
    // last geometry-mutating stage (after 4.7r, before 4.8 label positioning).
    // =====================================================================

    /**
     * Run the corridor-aware egress clearance over an entire view, mutating {@code paths} in
     * place. Pre-gates on the view's pre-pass V_p10, then walks connections in order
     * — for each, re-discovers the hug against the CURRENT geometry (so an already-pushed
     * predecessor counts as a neighbour: slot-awareness), applies the proposal, and
     * keeps it only if it net-improves the FINAL state, else rolls it back
     * byte-identical.
     *
     * @param connections  index-parallel per-route endpoint records
     * @param paths        index-parallel per-route bendpoint lists (MUTATED in place)
     * @param allObstacles all element rectangles on the view. <b>Contract:</b> must include the
     *                     source/target element rects (consistent with M4 assessor semantics
     *                     where the element is not self-excluded) — the hug is confirmed against the
     *                     element's OWN face edge, so omitting it would silently miss self-face hugs.
     * @return aggregate stats; never null
     */
    public Result run(List<RoutingPipeline.ConnectionEndpoints> connections,
                      List<List<AbsoluteBendpointDto>> paths,
                      List<RoutingRect> allObstacles) {
        if (connections == null || paths == null || connections.size() != paths.size()) {
            return new Result(0, 0, false, 0);
        }
        List<RoutingRect> obstacles = allObstacles != null ? allObstacles : List.of();

        // Cheap view-level pre-gate. Compute the pre-pass V_p10 once (reusing the
        // shipped HubPerimeterRoutingStage mirror — the exact quantity the HH oracle pins).
        Double prePassVp10 = HubPerimeterRoutingStage.computeVAxisParallelGapP10(paths);
        if (prePassVp10 != null && prePassVp10 < PRE_GATE_VP10_PX) {
            return new Result(0, 0, true, 0);
        }
        // Proposal-time connection-gap floor (Option B): keep the cleared run a HEALTHY gap from
        // co-axial neighbours (axis-correct: collectNeighbouringConnectionSegments already selects
        // co-axial neighbours by hug orientation). The final-state per-axis netImproves floor is
        // the soundness backstop. Replaces the old view-wide ceil(V_p10), which was the wrong axis
        // for horizontal hugs and far larger than the [TARGET,MAX] outward search could satisfy.
        int requiredConnGap = HEALTHY_PARALLEL_GAP_PX;

        int applied = 0;
        int rolled = 0;
        int evaluated = 0;
        for (int i = 0; i < connections.size(); i++) {
            RoutingPipeline.ConnectionEndpoints conn = connections.get(i);
            // size < 3: no interior corner exists, so the transform is undefined (cornerIdx
            // would equal the opposite terminal, which tryEgress guards anyway) — skip cheaply.
            if (conn == null || paths.get(i) == null || paths.get(i).size() < 3) continue;

            // Re-discover against CURRENT geometry (sees applied predecessors → slot-aware).
            EgressProposal p = tryEgress(i, conn, paths, obstacles, requiredConnGap, true);
            if (p == null) {
                p = tryEgress(i, conn, paths, obstacles, requiredConnGap, false);
            }
            if (p == null) continue;
            evaluated++;

            // Validate against the FINAL pipeline geometry (this IS the last
            // geometry-mutating stage). Roll back any push that does not net-improve.
            EgressMetrics pre = snapshot(connections, paths, obstacles);
            Snapshot snap = apply(p, paths);
            if (snap == null) continue;
            EgressMetrics post = snapshot(connections, paths, obstacles);
            if (netImproves(pre, post)) {
                applied++;
            } else {
                restore(snap, paths);
                rolled++;
            }
        }
        return new Result(applied, rolled, false, evaluated);
    }

    /**
     * Final-state metrics for the per-proposal net-improve check (carries BOTH axis p10s). The
     * {@code offFaceStubCount} is the sub-M4 keep-signal: a count of face-parallel terminal stubs
     * within {@link #OFF_FACE_MIN_STUB_PX} of their departed face (the assessor's
     * {@code offFaceParallelTerminalCount} oracle, reconstructed against routing geometry). A short
     * own-face micro-hug never registers in {@code m4Count} (the 10px-overlap floor), so this count
     * is what lets {@link #netImproves} keep the lift.
     */
    record EgressMetrics(int m4Count, int offFaceStubCount, Double vP10, Double hP10, double hpq) {}

    /** Whole-view metric snapshot via the shipped HubPerimeterRoutingStage mirror statics. */
    private static EgressMetrics snapshot(
            List<RoutingPipeline.ConnectionEndpoints> connections,
            List<List<AbsoluteBendpointDto>> paths,
            List<RoutingRect> allObstacles) {
        return new EgressMetrics(
                HubPerimeterRoutingStage.computeM4Count(paths, allObstacles),
                computeOffFaceStubCount(connections, paths),
                HubPerimeterRoutingStage.computeVAxisParallelGapP10(paths),
                HubPerimeterRoutingStage.computeHAxisParallelGapP10(paths),
                HubPerimeterRoutingStage.computeHpq(connections, paths));
    }

    /**
     * Net-improvement predicate (Option B + short-run story): keep a proposal when it STRICTLY
     * improves on AT LEAST ONE of (a) M4 (the &ge;10px edge-coincidence count) OR (b) the off-face
     * parallel-terminal stub count ({@link #computeOffFaceStubCount}, the sub-M4 signal — gated so
     * M4 must not regress), AND neither parallel-gap axis regresses BELOW the healthy floor AND HPQ
     * does not regress. The {@code m4Drops} branch is byte-identical to the pre-story strict-M4-drop
     * predicate (the stub-drop branch is purely ADDITIVE — see {@link #netImproves} body).
     * STRICTER than {@link HubPerimeterRoutingStage#verifyMetricMonotonicity} (which only forbids
     * M4 increasing) on M4/HPQ, but the parallel-gap guard is a FLOOR rather than strict
     * non-regression — see {@link #regressesBelowHealthyFloor}. BOTH axes are checked
     * ({@code vP10} and {@code hP10}), so a horizontal-hug push (which inserts vertical segments)
     * and a vertical-hug push (which inserts horizontal segments) are each guarded on the axis
     * they perturb. Tier-1 is protected by the per-proposal {@link #tier1Clean} (run at final
     * geometry) + the byte-identical terminal anchor + outward-only push (so
     * overlap/passThrough/interiorTermination/zigzag cannot be introduced).
     */
    static boolean netImproves(EgressMetrics pre, EgressMetrics post) {
        boolean m4Drops = post.m4Count() < pre.m4Count();                    // the original trigger
        // Sub-M4 keep-signal (short own-face micro-hugs): a <10px own-face hug is invisible to M4,
        // so the original strict-M4-drop gate would roll the fix back. Accept a strict drop in the
        // off-face parallel-terminal stub count instead, PROVIDED M4 does not regress. This is
        // purely ADDITIVE: the m4Drops branch below is byte-identical to the previous predicate, so
        // no currently-kept corpus push changes verdict — only sub-M4 stub-clearing pushes are
        // newly kept (and only when they also clear the floors/HPQ guards).
        boolean stubDrops = post.offFaceStubCount() < pre.offFaceStubCount()
                && post.m4Count() <= pre.m4Count();
        if (!m4Drops && !stubDrops) return false;                            // need a strict improvement
        if (regressesBelowHealthyFloor(pre.vP10(), post.vP10())) return false; // V-axis parallel gap
        if (regressesBelowHealthyFloor(pre.hP10(), post.hP10())) return false; // H-axis parallel gap
        if (post.hpq() < pre.hpq()) return false;                            // HPQ must not regress
        return true;
    }

    /**
     * A parallel-gap p10 "regresses" only if the push drops it BELOW {@link #HEALTHY_PARALLEL_GAP_PX}.
     * A reduction that stays at/above the healthy floor is allowed (e.g. 238&rarr;151 on a sparse
     * open view — the exact case the old strict guard wrongly forbade); a reduction below the floor,
     * or losing measurability when there was a prior co-axial field, is rejected. This preserves the
     * v1 danger-zone protection: a 4.0&rarr;3.4 push stays blocked (3.4 &lt; 15), and an already-tight
     * field (pre &lt; floor) can never be narrowed further.
     */
    private static boolean regressesBelowHealthyFloor(Double pre, Double post) {
        if (pre == null) return false;                 // no prior co-axial field to protect
        if (post == null) return true;                 // lost measurability → reject (prior strictness)
        if (post >= pre) return false;                 // not a reduction
        return post < HEALTHY_PARALLEL_GAP_PX;          // a reduction is allowed only above the floor
    }

    /**
     * The sub-M4 keep-signal: count terminals whose first exterior segment runs PARALLEL to, and
     * within {@link #OFF_FACE_MIN_STUB_PX} of, their departed source/target face — the same
     * per-terminal hug predicate the assessor's {@code describeOffFaceParallelTerminal} uses,
     * applied across the whole view against the routing geometry. (NOTE: this counts PER TERMINAL —
     * both terminals of every connection contribute independently — whereas the assessor's
     * {@code countOffFaceParallelTerminals} is per connection; per-terminal is the right granularity
     * for the keep-signal, since clearing ONE terminal's stub must strictly drop the count even when
     * the connection's other terminal still hugs.) A push that lifts a hug's stub clear of the face
     * strictly drops this count — the signal {@link #netImproves} uses to KEEP a fix that M4 (10px
     * overlap) cannot see.
     */
    private static int computeOffFaceStubCount(
            List<RoutingPipeline.ConnectionEndpoints> connections,
            List<List<AbsoluteBendpointDto>> paths) {
        if (connections == null || paths == null) return 0;
        int count = 0;
        for (int i = 0; i < connections.size() && i < paths.size(); i++) {
            RoutingPipeline.ConnectionEndpoints conn = connections.get(i);
            List<AbsoluteBendpointDto> path = paths.get(i);
            if (conn == null || path == null || path.size() < 2) continue;
            if (isOffFaceParallelStub(path.get(0), path.get(1), conn.source())) count++;
            int n = path.size();
            if (isOffFaceParallelStub(path.get(n - 1), path.get(n - 2), conn.target())) count++;
        }
        return count;
    }

    /**
     * True when the segment {@code term -> next} departs {@code elem}'s face and immediately runs
     * PARALLEL to it within {@link #OFF_FACE_MIN_STUB_PX} perpendicular clearance — i.e. the
     * hugging-exit the assessor's {@code describeOffFaceParallelTerminal} flags. {@code term} is the
     * terminal bendpoint (on the face line); {@code next} is the next path point. Returns false when
     * {@code elem} is null, the terminal is not on a resolvable face, the segment is zero-length, or
     * the first exterior segment is perpendicular (a clean egress).
     */
    private static boolean isOffFaceParallelStub(AbsoluteBendpointDto term,
                                                 AbsoluteBendpointDto next, RoutingRect elem) {
        if (elem == null) return false;
        Face face = TerminalSegmentCorridorMigrator.inferAttachedFace(term, elem);
        if (face == null) return false;
        double dx = Math.abs((double) next.x() - term.x());
        double dy = Math.abs((double) next.y() - term.y());
        if (dx < 1e-9 && dy < 1e-9) return false;           // zero-length — no direction
        boolean horizontalFace = (face == Face.TOP || face == Face.BOTTOM);
        // Parallel to the departed face: near-cardinal along the face's PARALLEL axis (horizontal
        // for TOP/BOTTOM, vertical for LEFT/RIGHT). A perpendicular first segment (a clean egress)
        // fails this and is not counted. Mirrors LayoutQualityAssessor.describeOffFaceParallelTerminal.
        boolean parallel = horizontalFace
                ? (dx > ZIGZAG_AXIS_TOLERANCE_PX && dy <= ZIGZAG_AXIS_TOLERANCE_PX)
                : (dy > ZIGZAG_AXIS_TOLERANCE_PX && dx <= ZIGZAG_AXIS_TOLERANCE_PX);
        if (!parallel) return false;
        double faceLine = switch (face) {
            case TOP -> elem.y();
            case BOTTOM -> elem.y() + elem.height();
            case LEFT -> elem.x();
            case RIGHT -> elem.x() + elem.width();
        };
        double stub = horizontalFace ? Math.abs(term.y() - faceLine) : Math.abs(term.x() - faceLine);
        return stub < OFF_FACE_MIN_STUB_PX;
    }

    // =====================================================================
    // Evaluate / apply / restore — the validated core (no-apply introspection
    // for unit tests; production drives tryEgress directly via run()).
    // =====================================================================

    /**
     * Evaluate every connection for a terminal-egress hug against {@code paths} as given.
     * Emits at most one proposal per path (source side preferred, then target side). Does NOT
     * mutate {@code paths}. The connection-gap floor is the flat {@link #HEALTHY_PARALLEL_GAP_PX}
     * (no longer derived from V_p10). Used by unit tests; production uses {@link #run}.
     */
    public List<EgressProposal> evaluate(
            List<RoutingPipeline.ConnectionEndpoints> connections,
            List<List<AbsoluteBendpointDto>> paths,
            List<RoutingRect> allObstacles) {
        if (connections == null || paths == null || connections.size() != paths.size()) {
            return List.of();
        }
        List<RoutingRect> obstacles = allObstacles != null ? allObstacles : List.of();
        int requiredConnGap = HEALTHY_PARALLEL_GAP_PX;
        List<EgressProposal> proposals = new ArrayList<>();
        for (int i = 0; i < connections.size(); i++) {
            RoutingPipeline.ConnectionEndpoints conn = connections.get(i);
            List<AbsoluteBendpointDto> path = paths.get(i);
            if (conn == null || path == null || path.size() < 3) continue;
            EgressProposal p = tryEgress(i, conn, paths, obstacles, requiredConnGap, true);
            if (p == null) {
                p = tryEgress(i, conn, paths, obstacles, requiredConnGap, false);
            }
            if (p != null) proposals.add(p);
        }
        return proposals;
    }

    /** Swap in the proposed path; return a Snapshot for {@link #restore}. */
    public Snapshot apply(EgressProposal proposal, List<List<AbsoluteBendpointDto>> paths) {
        if (proposal == null || paths == null
                || proposal.pathIndex() < 0 || proposal.pathIndex() >= paths.size()) {
            return null;
        }
        List<AbsoluteBendpointDto> path = paths.get(proposal.pathIndex());
        if (path == null) return null;
        List<AbsoluteBendpointDto> old = new ArrayList<>(path);
        path.clear();
        path.addAll(proposal.newPath());
        return new Snapshot(proposal.pathIndex(), old);
    }

    /** Revert a previously-applied proposal using its snapshot. */
    public void restore(Snapshot snapshot, List<List<AbsoluteBendpointDto>> paths) {
        if (snapshot == null || paths == null
                || snapshot.pathIndex() < 0 || snapshot.pathIndex() >= paths.size()) {
            return;
        }
        List<AbsoluteBendpointDto> path = paths.get(snapshot.pathIndex());
        if (path == null) return;
        path.clear();
        path.addAll(snapshot.oldPath());
    }

    // =====================================================================
    // internals — one (connection, terminal-side) evaluation
    // =====================================================================

    private EgressProposal tryEgress(int pathIndex,
                                     RoutingPipeline.ConnectionEndpoints conn,
                                     List<List<AbsoluteBendpointDto>> allPaths,
                                     List<RoutingRect> obstacles,
                                     int requiredConnGap,
                                     boolean sourceSide) {
        List<AbsoluteBendpointDto> path = allPaths.get(pathIndex);
        if (path == null || path.size() < 3) return null;
        int lastIdx = path.size() - 1;
        int termIdx = sourceSide ? 0 : lastIdx;
        int cornerIdx = sourceSide ? 1 : lastIdx - 1;
        // Corner must be an INTERIOR bendpoint, not the opposite terminal (cannot move that).
        int oppositeTermIdx = sourceSide ? lastIdx : 0;
        if (cornerIdx == oppositeTermIdx) return null;

        RoutingRect elem = sourceSide ? conn.source() : conn.target();
        if (elem == null) return null;
        AbsoluteBendpointDto term = path.get(termIdx);
        AbsoluteBendpointDto corner = path.get(cornerIdx);

        Face face = TerminalSegmentCorridorMigrator.inferAttachedFace(term, elem);
        if (face == null) return null; // not on a face line → EdgeAttachmentCalculator's domain
        TerminalAnchoring anchoring = new TerminalAnchoring(face);
        boolean orthIsY = anchoring.orthogonalAxis() == TerminalAnchoring.Axis.Y;

        // The terminal segment term -> corner must run PARALLEL to the face (constant on the
        // ORTHOGONAL axis): that is the "no egress stub, immediate parallel hug" case.
        int dx = corner.x() - term.x();
        int dy = corner.y() - term.y();
        boolean segHorizontal = Math.abs(dy) <= ZIGZAG_AXIS_TOLERANCE_PX
                && Math.abs(dx) > ZIGZAG_AXIS_TOLERANCE_PX;
        boolean segVertical = Math.abs(dx) <= ZIGZAG_AXIS_TOLERANCE_PX
                && Math.abs(dy) > ZIGZAG_AXIS_TOLERANCE_PX;
        if (!segHorizontal && !segVertical) return null; // diagonal/degenerate
        boolean segConstantIsY = segHorizontal;          // horizontal → constant Y
        // Parallel-to-face means the segment's constant axis IS the orthogonal axis.
        if (segConstantIsY != orthIsY) return null;      // already a perpendicular egress → fine

        int lineCoord = anchoring.lineCoordinate(elem);          // = geometric edge ± 1
        int curOrth = orthIsY ? term.y() : term.x();
        if (curOrth != lineCoord) return null;                   // belt-and-braces: term on face line
        // The face line sits 1px OUTSIDE the element: lineCoordinate == geomEdge + awaySign
        // (RIGHT/BOTTOM away=+1, LEFT/TOP away=-1). Therefore geomEdge = lineCoord - awaySign.
        // (LEFT: x-1-(-1)=x; RIGHT: x+w+1-(+1)=x+w; TOP: y-1-(-1)=y; BOTTOM: y+h+1-(+1)=y+h.)
        int geomEdge = lineCoord - awaySign(face);               // the real element edge
        int away = awaySign(face);

        // Along-face span of the hug (parallel axis) — for the overlap tests.
        int spanLo;
        int spanHi;
        if (orthIsY) { // horizontal hug → parallel axis = X
            spanLo = Math.min(term.x(), corner.x());
            spanHi = Math.max(term.x(), corner.x());
        } else {       // vertical hug → parallel axis = Y
            spanLo = Math.min(term.y(), corner.y());
            spanHi = Math.max(term.y(), corner.y());
        }

        // Edges of elements whose extent overlaps the hug span by >=10px — STILL needed downstream:
        // the cleared run must clear each of these (foreign-edge clearing in findClearedOrthogonal).
        List<Integer> overlappingEdges = collectOverlappingEdges(orthIsY, spanLo, spanHi, obstacles);
        // Confirm the current run actually hugs its OWN face. The terminal sits exactly on its own
        // element's face line (curOrth == lineCoord, asserted above) with the run parallel to that
        // face, and geomEdge = lineCoord - awaySign (|awaySign| == 1), so |curOrth - geomEdge| == 1
        // <= EDGE_COINCIDENCE_TOLERANCE_PX — i.e. this is ALWAYS an own-face parallel hug here, at
        // ANY positive run length. This INCLUDES a SHORT (<10px) run that collectOverlappingEdges'
        // 10px-overlap floor never surfaces (the assessor flags those via its perpendicular
        // OFF_FACE_MIN_STUB_PX signal; we act on them). The relaxation is strictly own-face:
        // collectOverlappingEdges (and its reuse for foreign-edge clearing in findClearedOrthogonal)
        // is UNCHANGED, so a foreign edge still needs >=10px overlap to enter the picture, and corpus
        // byte-identity holds — a sub-10px own-face hug the keep-signal does not act on is rolled
        // back byte-identical. The explicit guard is retained defensively (never taken in practice).
        boolean hugs = Math.abs((double) curOrth - geomEdge) <= EDGE_COINCIDENCE_TOLERANCE_PX;
        if (!hugs) return null;

        // Neighbouring co-axial connection segments the cleared run must stay clear of.
        List<Integer> neighbourConnCoords =
                collectNeighbouringConnectionSegments(orthIsY, spanLo, spanHi, pathIndex, allPaths);

        // The Tier-1 foreign-passthrough check runs against the per-connection
        // ANCESTOR-EXCLUDED obstacle set (conn.obstacles() — "source/target/ancestors already
        // excluded"), NOT the full allObstacles. This lets
        // the cleared run lie inside the target's/source's own ancestor container (the legitimate
        // enclosing element of a container-nested hub) without being flagged as a passthrough — the
        // dominant blocker that made the predecessor a sound no-op on view G. A GENUINE foreign
        // element is still present in conn.obstacles(), so a real passthrough is still rejected
        // (soundness). Own-face hug detection (collectOverlappingEdges + the edge-clearing in
        // findClearedOrthogonal) DELIBERATELY stays on allObstacles — it needs the element's own
        // face (conn.obstacles() excludes source/target). Fallback to allObstacles only when
        // conn.obstacles() is empty: an empty set means there are NO foreign elements on the view
        // (everything is source/target/ancestor/descendant), so the fallback can only over-reject
        // (a sound no-op against an ancestor/own face), never place a foreign passthrough.
        final List<RoutingRect> tier1Obstacles =
                (conn.obstacles() != null && !conn.obstacles().isEmpty()) ? conn.obstacles() : obstacles;

        // Per-candidate terminal-anchor + Tier-1 feasibility. findClearedOrthogonal advances
        // its outward search past a candidate that clears element edges + connection gaps but is
        // Tier-1-rejected (e.g. a genuine foreign element between the face and the corridor),
        // returning the smallest clean coordinate within the unchanged [TARGET, MAX]=[8,64] cap. The
        // predecessor stopped at the first edge/conn-cleared candidate and no-op'd if Tier-1 failed.
        IntPredicate candidateFeasible = cand -> {
            List<AbsoluteBendpointDto> candAfter =
                    buildAfterPath(path, cornerIdx, term, corner, cand, orthIsY, sourceSide);
            return b71Ok(anchoring, elem, candAfter, sourceSide)
                    && tier1Clean(candAfter, conn, tier1Obstacles);
        };

        // Outward search for a cleared orthogonal coordinate (away from the element interior) that
        // clears element edges AND neighbouring connection segments AND the per-candidate
        // terminal-anchor/Tier-1 feasibility.
        Integer newOrth = findClearedOrthogonal(geomEdge, away, overlappingEdges,
                neighbourConnCoords, requiredConnGap, candidateFeasible);
        if (newOrth == null) return null; // no room within range → no-op

        // The feasibility predicate already validated the terminal anchor + Tier-1 for newOrth; rebuild the final path.
        return new EgressProposal(conn.connectionId(), pathIndex,
                buildAfterPath(path, cornerIdx, term, corner, newOrth, orthIsY, sourceSide));
    }

    /**
     * Build the post-transform path for one egress candidate: keep the terminal byte-identical, move
     * the interior corner to {@code newOrth} on the orthogonal axis, and insert
     * {@code A' = (term.parallel, newOrth)} immediately interior-ward of the terminal. Parallel
     * coordinates are unchanged (so the hub slot is preserved → HPQ cannot regress from the
     * transform, and the perimeter-terminal immutability invariant holds by construction). Pure; allocates a fresh list (paths are short, so
     * the per-candidate build the Fix-2 retry needs is cheap).
     */
    private static List<AbsoluteBendpointDto> buildAfterPath(List<AbsoluteBendpointDto> path,
                                                             int cornerIdx, AbsoluteBendpointDto term,
                                                             AbsoluteBendpointDto corner, int newOrth,
                                                             boolean orthIsY, boolean sourceSide) {
        AbsoluteBendpointDto aPrime = orthIsY
                ? new AbsoluteBendpointDto(term.x(), newOrth)
                : new AbsoluteBendpointDto(newOrth, term.y());
        AbsoluteBendpointDto newCorner = orthIsY
                ? new AbsoluteBendpointDto(corner.x(), newOrth)
                : new AbsoluteBendpointDto(newOrth, corner.y());
        List<AbsoluteBendpointDto> after = new ArrayList<>(path);
        after.set(cornerIdx, newCorner);
        // Insert A' immediately interior-ward of the terminal.
        if (sourceSide) {
            after.add(1, aPrime);                 // [term, A', newCorner, ...]
        } else {
            after.add(after.size() - 1, aPrime);  // [..., newCorner, A', term]
        }
        return after;
    }

    /**
     * Guard (i): the terminal bendpoint is unchanged (perimeter-terminal-immutable by construction — the transform never
     * touches the terminal) and the explicit {@link TerminalAnchoring#preservesTerminalAnchoring}
     * contract holds. For a target-side push the path is reversed so the anchoring check sees the
     * terminal as the first point.
     */
    private static boolean b71Ok(TerminalAnchoring anchoring, RoutingRect elem,
                                 List<AbsoluteBendpointDto> after, boolean sourceSide) {
        int[] center = {elem.centerX(), elem.centerY()};
        List<AbsoluteBendpointDto> oriented = after;
        if (!sourceSide) {
            oriented = new ArrayList<>(after);
            Collections.reverse(oriented);
        }
        return TerminalAnchoring.preservesTerminalAnchoring(anchoring, elem, center, oriented);
    }

    /** Away-from-interior sign on the orthogonal axis for each face (perimeter-terminal-immutability line = edge + sign). */
    private static int awaySign(Face face) {
        return switch (face) {
            case BOTTOM, RIGHT -> +1;
            case TOP, LEFT -> -1;
        };
    }

    /**
     * Smallest clearance k in [{@link #TARGET_EGRESS_CLEARANCE_PX}, {@link #MAX_EGRESS_CLEARANCE_PX}]
     * such that {@code geomEdge + away*k} clears every overlapping element edge by
     * {@code EDGE_COINCIDENCE_TOLERANCE_PX + CLEAR_SAFETY_MARGIN_PX} (v1 behaviour) AND every
     * neighbouring co-axial connection-segment coordinate by {@code requiredConnGap} (Fix-2a) AND
     * (when non-null) the per-candidate {@code candidateFeasible} predicate (G-Fix-2:
     * the candidate's perimeter-terminal immutability + Tier-1 feasibility). The loop ascends, so the smallest feasible k is
     * returned; a candidate that fails ONLY the Tier-1 predicate no longer aborts the search (the
     * predecessor returned the first edge/conn-cleared candidate and let the caller no-op when
     * Tier-1 then rejected it). The [8,64] cap is UNCHANGED. Returns the cleared orthogonal
     * coordinate, or {@code null} if none in range (tight geometry → no-op).
     */
    static Integer findClearedOrthogonal(int geomEdge, int away, List<Integer> edges,
                                         List<Integer> connSegCoords, int requiredConnGap,
                                         IntPredicate candidateFeasible) {
        double edgeRequired = EDGE_COINCIDENCE_TOLERANCE_PX + CLEAR_SAFETY_MARGIN_PX;
        for (int k = TARGET_EGRESS_CLEARANCE_PX; k <= MAX_EGRESS_CLEARANCE_PX; k++) {
            int cand = geomEdge + away * k;
            if (!clears(cand, edges, edgeRequired)) continue;
            if (!clears(cand, connSegCoords, requiredConnGap)) continue;            // Fix-2a
            if (candidateFeasible != null && !candidateFeasible.test(cand)) continue; // G-Fix-2
            return cand;
        }
        return null;
    }

    private static boolean clears(int v, List<Integer> coords, double required) {
        for (int c : coords) {
            if (Math.abs((double) v - c) < required) return false;
        }
        return true;
    }

    /**
     * Collect the perpendicular coordinates of every element edge whose extent overlaps the
     * hug's parallel span by at least {@link #EDGE_COINCIDENCE_MIN_OVERLAP_PX}. Mirrors
     * {@code TerminalSegmentCorridorMigrator.collectOverlappingEdges}.
     *
     * @param orthIsY true when the orthogonal axis is Y (horizontal hug → candidate edges are
     *                obstacle top/bottom Y); false → obstacle left/right X
     */
    private static List<Integer> collectOverlappingEdges(boolean orthIsY,
                                                         int spanLo, int spanHi,
                                                         List<RoutingRect> obstacles) {
        List<Integer> edges = new ArrayList<>();
        for (RoutingRect obs : obstacles) {
            if (obs == null) continue;
            int obsLo;
            int obsHi;
            int edgeA;
            int edgeB;
            if (orthIsY) { // horizontal hug: obstacle X-extent vs span; edges = obstacle Y
                obsLo = obs.x();
                obsHi = obs.x() + obs.width();
                edgeA = obs.y();
                edgeB = obs.y() + obs.height();
            } else {       // vertical hug: obstacle Y-extent vs span; edges = obstacle X
                obsLo = obs.y();
                obsHi = obs.y() + obs.height();
                edgeA = obs.x();
                edgeB = obs.x() + obs.width();
            }
            double overlap = Math.min(spanHi, obsHi) - Math.max(spanLo, obsLo);
            if (overlap < EDGE_COINCIDENCE_MIN_OVERLAP_PX) continue;
            edges.add(edgeA);
            edges.add(edgeB);
        }
        return edges;
    }

    /**
     * Fix-2a — collect the orthogonal coordinates of every OTHER connection's CO-AXIAL segment
     * (same orientation as the hug run) whose parallel span overlaps the hug's span by at least
     * {@link #EDGE_COINCIDENCE_MIN_OVERLAP_PX}. The cleared run must keep {@code requiredConnGap}
     * from each of these. Under the Option B floor ({@code requiredConnGap = HEALTHY_PARALLEL_GAP_PX})
     * this keeps the cleared run &ge; the healthy floor from each co-axial neighbour, so the
     * final-state parallel-gap p10 on that axis cannot drop below the floor — re-checked at the
     * whole-view level by {@link #netImproves}. (The old design floored on {@code ceil(V_p10)} and
     * claimed strict V_p10 non-regression; that is no longer the invariant.)
     *
     * @param orthIsY true when the hug run is horizontal (co-axial neighbours are horizontal
     *                segments; their orthogonal coordinate is Y); false → vertical neighbours (X)
     */
    private static List<Integer> collectNeighbouringConnectionSegments(boolean orthIsY,
                                                                       int spanLo, int spanHi,
                                                                       int selfPathIndex,
                                                                       List<List<AbsoluteBendpointDto>> allPaths) {
        List<Integer> coords = new ArrayList<>();
        if (allPaths == null) return coords;
        for (int pi = 0; pi < allPaths.size(); pi++) {
            if (pi == selfPathIndex) continue;
            List<AbsoluteBendpointDto> p = allPaths.get(pi);
            if (p == null || p.size() < 2) continue;
            for (int i = 0; i < p.size() - 1; i++) {
                AbsoluteBendpointDto a = p.get(i);
                AbsoluteBendpointDto b = p.get(i + 1);
                double sdx = Math.abs((double) b.x() - a.x());
                double sdy = Math.abs((double) b.y() - a.y());
                boolean horizontal = sdy <= ZIGZAG_AXIS_TOLERANCE_PX && sdx > ZIGZAG_AXIS_TOLERANCE_PX;
                boolean vertical = sdx <= ZIGZAG_AXIS_TOLERANCE_PX && sdy > ZIGZAG_AXIS_TOLERANCE_PX;
                int segOrth;
                int segLo;
                int segHi;
                if (orthIsY) {
                    if (!horizontal) continue;            // co-axial = horizontal
                    // Round-to-nearest (not truncate): the co-axiality tolerance admits
                    // segments off-axis by up to ZIGZAG_AXIS_TOLERANCE_PX, so a.y()!=b.y()
                    // is possible; float-round matches the sibling stat
                    // HubPerimeterRoutingStage.computeVAxisParallelGapP10's (p+q)/2.0 convention.
                    segOrth = (int) Math.round((a.y() + (double) b.y()) / 2.0); // orthogonal coordinate = Y
                    segLo = Math.min(a.x(), b.x());
                    segHi = Math.max(a.x(), b.x());
                } else {
                    if (!vertical) continue;              // co-axial = vertical
                    segOrth = (int) Math.round((a.x() + (double) b.x()) / 2.0); // orthogonal coordinate = X
                    segLo = Math.min(a.y(), b.y());
                    segHi = Math.max(a.y(), b.y());
                }
                double overlap = Math.min(spanHi, segHi) - Math.max(spanLo, segLo);
                if (overlap < EDGE_COINCIDENCE_MIN_OVERLAP_PX) continue;
                coords.add(segOrth);
            }
        }
        return coords;
    }

    /**
     * Tier-1 self-check: every segment stays axis-aligned (no zigzag/diagonal introduced) and
     * no segment strictly pierces the interior of a foreign element (a segment may legitimately
     * abut its own source/target face). Mirrors the migrator's guard.
     */
    private static boolean tier1Clean(List<AbsoluteBendpointDto> after,
                                      RoutingPipeline.ConnectionEndpoints conn,
                                      List<RoutingRect> obstacles) {
        for (int i = 0; i < after.size() - 1; i++) {
            AbsoluteBendpointDto a = after.get(i);
            AbsoluteBendpointDto b = after.get(i + 1);
            double dx = Math.abs((double) b.x() - a.x());
            double dy = Math.abs((double) b.y() - a.y());
            boolean horizontal = dy <= ZIGZAG_AXIS_TOLERANCE_PX && dx > ZIGZAG_AXIS_TOLERANCE_PX;
            boolean vertical = dx <= ZIGZAG_AXIS_TOLERANCE_PX && dy > ZIGZAG_AXIS_TOLERANCE_PX;
            // A zero-length segment (consecutive identical points) is benign — skip it.
            boolean degenerate = dx <= ZIGZAG_AXIS_TOLERANCE_PX && dy <= ZIGZAG_AXIS_TOLERANCE_PX;
            if (!horizontal && !vertical && !degenerate) return false;
            if (degenerate) continue;
            for (RoutingRect obs : obstacles) {
                if (obs == null) continue;
                if (isOwnEndpoint(obs, conn)) continue;
                if (segmentStrictlyThroughInterior(a, b, horizontal, obs)) return false;
            }
        }
        return true;
    }

    private static boolean isOwnEndpoint(RoutingRect obs, RoutingPipeline.ConnectionEndpoints conn) {
        if (obs.id() == null) return false;
        if (conn.source() != null && obs.id().equals(conn.source().id())) return true;
        return conn.target() != null && obs.id().equals(conn.target().id());
    }

    /** Strict (open-inequality) axis-aligned segment-vs-rect-interior intersection test. */
    private static boolean segmentStrictlyThroughInterior(AbsoluteBendpointDto a,
                                                          AbsoluteBendpointDto b,
                                                          boolean horizontal, RoutingRect obs) {
        int left = obs.x();
        int right = obs.x() + obs.width();
        int top = obs.y();
        int bottom = obs.y() + obs.height();
        if (horizontal) {
            int y = a.y();
            if (y <= top || y >= bottom) return false;          // not inside vertical extent
            int segLo = Math.min(a.x(), b.x());
            int segHi = Math.max(a.x(), b.x());
            return segLo < right && segHi > left;               // overlaps interior X-extent
        } else {
            int x = a.x();
            if (x <= left || x >= right) return false;
            int segLo = Math.min(a.y(), b.y());
            int segHi = Math.max(a.y(), b.y());
            return segLo < bottom && segHi > top;
        }
    }
}
