package net.vheerden.archi.mcp.model.routing;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Post-routing path straightening.
 * Pure-geometry class — no EMF/SWT dependencies.
 *
 * <p>Cleans up routed paths after all pipeline stages by applying four
 * complementary transformations:
 * <ol>
 *   <li>{@link #snapToStraight} — snaps near-aligned consecutive points</li>
 *   <li>{@link #eliminateReversals} — collapses direction reversal patterns</li>
 *   <li>{@link #collapseStaircaseJogs} — eliminates small jogs between parallel runs</li>
 *   <li>{@link #collapseBends} — removes redundant intermediate bendpoints</li>
 * </ol>
 *
 * <h2>Perimeter-terminal immutability wrap</h2>
 *
 * <p>Each of the four mutators above is one of the five "wrap sites" governed by
 * {@link TerminalAnchoring#preservesTerminalAnchoring}. The new overloads accept
 * source/target {@link TerminalAnchoring} pairs and snapshot the path on entry,
 * run the mutation, then compare the snapshot against the result. The legacy
 * overloads (without anchorings) bypass the wrap and are preserved for legacy
 * callers — primarily {@link PathStraightener}'s own unit tests. The legacy
 * {@code containsPerimeterBP} guards previously living inside
 * {@link #eliminateReversals} and {@link #collapseBends} are removed by this
 * rewrite — the predicate-based wrap supersedes them.
 *
 * <p><strong>The five wrap sites share the predicate and the rollback
 * policy.</strong> All five roll back on a <em>delta</em>: only a mutation that
 * moves a terminal off a faceline that terminal was on beforehand is rejected,
 * and the two ends are decided independently. The policy itself lives once, in
 * {@link TerminalAnchoringRollbackPolicy}; this class contributes the augmented
 * frame and the structural arm that go with it — see
 * {@link #checkAnchoringWrap}. The fifth site,
 * {@link CoincidentSegmentDetector#applyOffsets}, judged the post-state alone
 * until it was converted to the same policy; it applies the off-face pin as
 * well, its write range reaching a terminal with nothing else bounding it, and
 * it needs no structural arm because its frame is unaugmented and its mutation
 * size-invariant.
 *
 * @see TerminalAnchoring#preservesEndpoints
 */
public class PathStraightener {

    private static final Logger logger = LoggerFactory.getLogger(PathStraightener.class);

    private PathStraightener() {
        // Static utility class
    }

    // ---------------------------------------------------------------------
    // snapToStraight
    // ---------------------------------------------------------------------

    /**
     * Snaps near-aligned consecutive points to eliminate small jogs.
     *
     * <p>Legacy overload — runs the mutation without the wrap. Used by unit
     * tests that have no terminal anchoring context.</p>
     */
    public static void snapToStraight(List<AbsoluteBendpointDto> path, int threshold,
            List<RoutingRect> obstacles) {
        snapToStraightCore(path, threshold, obstacles);
    }

    /**
     * Wrap-site overload: runs {@link #snapToStraight} with terminal
     * anchoring rollback. On predicate violation at either terminal, the
     * path is rolled back to its pre-mutation snapshot.
     *
     * @param augmented when {@code true}, indices {@code 0} and
     *                  {@code path.size() - 1} are treated as sentinel
     *                  source/target centers (as in the temporary path used
     *                  by {@code RoutingPipeline} stage 4.7i) — the predicate
     *                  is evaluated against {@code path[1]} and
     *                  {@code path[size - 2]}, the real perimeter terminals
     */
    public static void snapToStraight(List<AbsoluteBendpointDto> path, int threshold,
            List<RoutingRect> obstacles,
            RoutingRect source, RoutingRect target,
            int[] sourceCenter, int[] targetCenter,
            TerminalAnchoring sourceAnchoring, TerminalAnchoring targetAnchoring,
            boolean augmented) {
        List<AbsoluteBendpointDto> snapshot = new ArrayList<>(path);
        snapToStraightCore(path, threshold, obstacles);
        if (!checkAnchoringWrap(snapshot, path, augmented,
                sourceAnchoring, source, sourceCenter,
                targetAnchoring, target, targetCenter, true)) {
            path.clear();
            path.addAll(snapshot);
            logger.debug("snapToStraight: rolled back — terminal anchoring violated");
        }
    }

    private static void snapToStraightCore(List<AbsoluteBendpointDto> path, int threshold,
            List<RoutingRect> obstacles) {
        if (path.size() < 3) {
            return;
        }

        int snapped = 0;
        for (int i = 1; i < path.size() - 1; i++) {
            AbsoluteBendpointDto prev = path.get(i - 1);
            AbsoluteBendpointDto curr = path.get(i);
            AbsoluteBendpointDto next = path.get(i + 1);

            int dx = Math.abs(curr.x() - prev.x());
            int dy = Math.abs(curr.y() - prev.y());

            AbsoluteBendpointDto candidate = null;
            if (dx > 0 && dx <= threshold && dy > dx) {
                candidate = new AbsoluteBendpointDto(prev.x(), curr.y());
            } else if (dy > 0 && dy <= threshold && dx > dy) {
                candidate = new AbsoluteBendpointDto(curr.x(), prev.y());
            }

            if (candidate == null) {
                int dxNext = Math.abs(curr.x() - next.x());
                int dyNext = Math.abs(curr.y() - next.y());

                if (dxNext > 0 && dxNext <= threshold && dyNext > dxNext) {
                    candidate = new AbsoluteBendpointDto(next.x(), curr.y());
                } else if (dyNext > 0 && dyNext <= threshold && dxNext > dyNext) {
                    candidate = new AbsoluteBendpointDto(curr.x(), next.y());
                } else if (dxNext == 0 && dyNext > 0 && dyNext <= threshold
                        && prev.y() != curr.y()) {
                    candidate = new AbsoluteBendpointDto(curr.x(), next.y());
                } else if (dyNext == 0 && dxNext > 0 && dxNext <= threshold
                        && prev.x() != curr.x()) {
                    candidate = new AbsoluteBendpointDto(next.x(), curr.y());
                }
            }

            if (candidate != null) {
                if (!RoutingPipeline.segmentIntersectsAnyObstacle(
                        prev.x(), prev.y(), candidate.x(), candidate.y(), obstacles)
                        && !RoutingPipeline.segmentIntersectsAnyObstacle(
                                candidate.x(), candidate.y(), next.x(), next.y(), obstacles)) {
                    path.set(i, candidate);
                    snapped++;
                }
            }
        }

        if (snapped > 0) {
            logger.debug("Snap-to-straight: snapped {} points within {}px threshold", snapped, threshold);
        }
    }

    // ---------------------------------------------------------------------
    // eliminateReversals
    // ---------------------------------------------------------------------

    /**
     * Eliminates direction reversal patterns (overshoot-then-doubleback).
     *
     * <p>Legacy overload — runs the mutation without the wrap.</p>
     */
    public static void eliminateReversals(List<AbsoluteBendpointDto> path,
            List<RoutingRect> obstacles) {
        eliminateReversalsCore(path, obstacles);
    }

    /**
     * Wrap-site overload. Replaces the perimeter-terminal guard with
     * the {@link TerminalAnchoring} predicate snapshot/rollback pattern.
     *
     * @param augmented see {@link #snapToStraight(List, int, List, RoutingRect,
     *                  RoutingRect, int[], int[], TerminalAnchoring,
     *                  TerminalAnchoring, boolean)}
     */
    public static void eliminateReversals(List<AbsoluteBendpointDto> path,
            List<RoutingRect> obstacles,
            RoutingRect source, RoutingRect target,
            int[] sourceCenter, int[] targetCenter,
            TerminalAnchoring sourceAnchoring, TerminalAnchoring targetAnchoring,
            boolean augmented) {
        List<AbsoluteBendpointDto> snapshot = new ArrayList<>(path);
        eliminateReversalsCore(path, obstacles, augmented);
        if (!checkAnchoringWrap(snapshot, path, augmented,
                sourceAnchoring, source, sourceCenter,
                targetAnchoring, target, targetCenter, false)) {
            path.clear();
            path.addAll(snapshot);
            logger.debug("eliminateReversals: rolled back — terminal anchoring violated");
        }
    }

    private static void eliminateReversalsCore(List<AbsoluteBendpointDto> path,
            List<RoutingRect> obstacles) {
        eliminateReversalsCore(path, obstacles, false);
    }

    /**
     * Core reversal-elimination scan.
     *
     * <p>Exit-then-return terminal zigzag: when {@code protectTerminals} is true the
     * path is the stage-4.7i <em>augmented</em> frame — sentinel source/target centers occupy
     * index {@code 0} and {@code size - 1}, so the real terminal anchors sit at index {@code 1}
     * and {@code size - 2}. A collapse removes the range {@code [i+1 .. j]}; if that range
     * covers a terminal anchor ({@code i == 0} → index 1; {@code j >= size - 2} → the target
     * anchor) the wrap ({@link #eliminateReversals(List, List, RoutingRect, RoutingRect,
     * int[], int[], TerminalAnchoring, TerminalAnchoring, boolean)}) would roll the entire pass
     * back, so the otherwise-valid narrower interior collapse never commits and an
     * exit-then-return overshoot at a terminal survives. Skipping anchor-deleting pairs confines
     * the scan to interior collapses — anchors may still be collapse <em>endpoints</em> but are
     * never <em>removed</em> — so the overshoot body collapses while both terminals stay
     * byte-identical. The guard only blocks collapses that today always trip the rollback to
     * net no-change, so every currently-kept collapse is unaffected (byte-identical preservation).
     * The legacy {@code (path, obstacles)} overload passes {@code false} — unchanged behaviour.
     */
    private static void eliminateReversalsCore(List<AbsoluteBendpointDto> path,
            List<RoutingRect> obstacles, boolean protectTerminals) {
        if (path.size() < 4) {
            return;
        }

        boolean changed = true;
        int iterations = 0;
        int maxIterations = path.size();

        while (changed && iterations < maxIterations) {
            changed = false;
            iterations++;

            for (int i = 0; i < path.size() - 3; i++) {
                for (int j = path.size() - 2; j > i; j--) {
                    if (i == 0 && j + 1 == path.size() - 1) {
                        continue;
                    }
                    // never collapse across a terminal anchor in the augmented frame
                    // (anchors at index 1 / size-2 of the CURRENT path — size is live,
                    // re-read each guard evaluation, not entry-snapped) — see Javadoc.
                    if (protectTerminals && (i == 0 || j >= path.size() - 2)) {
                        continue;
                    }

                    if (isReversal(path, i, j)) {
                        AbsoluteBendpointDto start = path.get(i);
                        AbsoluteBendpointDto end = path.get(j + 1);

                        if (start.x() != end.x() && start.y() != end.y()) {
                            AbsoluteBendpointDto mid = tryLTurn(start, end, obstacles);
                            if (mid != null) {
                                for (int k = j; k > i; k--) {
                                    path.remove(k);
                                }
                                path.add(i + 1, mid);
                                logger.debug("Reversal elimination: collapsed with L-turn at ({},{})",
                                        mid.x(), mid.y());
                                changed = true;
                                break;
                            }
                        } else {
                            if (!RoutingPipeline.segmentIntersectsAnyObstacle(
                                    start.x(), start.y(), end.x(), end.y(), obstacles)) {
                                int removed = j - i;
                                for (int k = j; k > i; k--) {
                                    path.remove(k);
                                }
                                logger.debug("Reversal elimination: collapsed {} intermediate points (direct)",
                                        removed);
                                changed = true;
                                break;
                            }
                        }
                    }
                }
                if (changed) break;
            }
        }
    }

    // ---------------------------------------------------------------------
    // collapseBends
    // ---------------------------------------------------------------------

    /**
     * Collapses redundant intermediate bendpoints where a direct straight-line
     * connection is obstacle-free.
     *
     * <p>Legacy overload — runs the mutation without the wrap.</p>
     */
    public static void collapseBends(List<AbsoluteBendpointDto> path,
            List<RoutingRect> obstacles) {
        collapseBendsCore(path, obstacles);
    }

    /**
     * Wrap-site overload. Replaces the Mode A perimeter-midpoint
     * guard with the {@link TerminalAnchoring} predicate snapshot/rollback
     * pattern. This is the primary site for the fully-collinear hub-center
     * slot scenario described below.
     *
     * @param augmented see {@link #snapToStraight(List, int, List, RoutingRect,
     *                  RoutingRect, int[], int[], TerminalAnchoring,
     *                  TerminalAnchoring, boolean)}
     */
    public static void collapseBends(List<AbsoluteBendpointDto> path,
            List<RoutingRect> obstacles,
            RoutingRect source, RoutingRect target,
            int[] sourceCenter, int[] targetCenter,
            TerminalAnchoring sourceAnchoring, TerminalAnchoring targetAnchoring,
            boolean augmented) {
        List<AbsoluteBendpointDto> snapshot = new ArrayList<>(path);
        collapseBendsCore(path, obstacles);
        if (!checkAnchoringWrap(snapshot, path, augmented,
                sourceAnchoring, source, sourceCenter,
                targetAnchoring, target, targetCenter, false)) {
            path.clear();
            path.addAll(snapshot);
            logger.debug("collapseBends: rolled back — terminal anchoring violated");
        }
    }

    private static void collapseBendsCore(List<AbsoluteBendpointDto> path,
            List<RoutingRect> obstacles) {
        if (path.size() < 4) {
            return;
        }

        boolean changed = true;
        int totalRemoved = 0;
        int maxIterations = path.size();

        while (changed && maxIterations-- > 0) {
            changed = false;
            for (int i = 0; i < path.size() - 2; i++) {
                int midIdx = i + 1;

                AbsoluteBendpointDto a = path.get(i);
                AbsoluteBendpointDto mid = path.get(midIdx);
                AbsoluteBendpointDto c = path.get(i + 2);

                boolean collinearX = (a.x() == mid.x() && mid.x() == c.x());
                boolean collinearY = (a.y() == mid.y() && mid.y() == c.y());

                if ((collinearX || collinearY)
                        && !RoutingPipeline.segmentIntersectsAnyObstacle(
                                a.x(), a.y(), c.x(), c.y(), obstacles)) {
                    path.remove(midIdx);
                    totalRemoved++;
                    changed = true;
                    break;
                }
            }
        }

        if (totalRemoved > 0) {
            logger.debug("Bend collapse: removed {} redundant collinear bendpoints", totalRemoved);
        }
    }

    // ---------------------------------------------------------------------
    // collapseStaircaseJogs
    // ---------------------------------------------------------------------

    /**
     * Collapses staircase jog patterns where two parallel segments are connected
     * by a small perpendicular step within the snap threshold.
     *
     * <p>Legacy overload — runs the mutation without the wrap.</p>
     */
    public static void collapseStaircaseJogs(List<AbsoluteBendpointDto> path, int threshold,
            List<RoutingRect> obstacles) {
        collapseStaircaseJogsCore(path, threshold, obstacles);
    }

    /**
     * Wrap-site overload: runs {@link #collapseStaircaseJogs} with
     * terminal anchoring rollback.
     *
     * @param augmented see {@link #snapToStraight(List, int, List, RoutingRect,
     *                  RoutingRect, int[], int[], TerminalAnchoring,
     *                  TerminalAnchoring, boolean)}
     */
    public static void collapseStaircaseJogs(List<AbsoluteBendpointDto> path, int threshold,
            List<RoutingRect> obstacles,
            RoutingRect source, RoutingRect target,
            int[] sourceCenter, int[] targetCenter,
            TerminalAnchoring sourceAnchoring, TerminalAnchoring targetAnchoring,
            boolean augmented) {
        List<AbsoluteBendpointDto> snapshot = new ArrayList<>(path);
        collapseStaircaseJogsCore(path, threshold, obstacles);
        if (!checkAnchoringWrap(snapshot, path, augmented,
                sourceAnchoring, source, sourceCenter,
                targetAnchoring, target, targetCenter, true)) {
            path.clear();
            path.addAll(snapshot);
            logger.debug("collapseStaircaseJogs: rolled back — terminal anchoring violated");
        }
    }

    private static void collapseStaircaseJogsCore(List<AbsoluteBendpointDto> path, int threshold,
            List<RoutingRect> obstacles) {
        if (path.size() < 4) {
            return;
        }

        boolean changed = true;
        int totalCollapsed = 0;
        int maxIterations = path.size();

        while (changed && maxIterations-- > 0) {
            changed = false;
            for (int i = 1; i < path.size() - 3; i++) {
                AbsoluteBendpointDto a = path.get(i);
                AbsoluteBendpointDto b = path.get(i + 1);
                AbsoluteBendpointDto c = path.get(i + 2);
                AbsoluteBendpointDto d = path.get(i + 3);

                AbsoluteBendpointDto newA = null;

                if (a.y() == b.y() && b.x() == c.x() && c.y() == d.y()) {
                    int jog = Math.abs(a.y() - d.y());
                    if (jog > 0 && jog <= threshold) {
                        newA = new AbsoluteBendpointDto(a.x(), d.y());
                    }
                } else if (a.x() == b.x() && b.y() == c.y() && c.x() == d.x()) {
                    int jog = Math.abs(a.x() - d.x());
                    if (jog > 0 && jog <= threshold) {
                        newA = new AbsoluteBendpointDto(d.x(), a.y());
                    }
                }

                if (newA != null) {
                    AbsoluteBendpointDto prev = path.get(i - 1);
                    if (!RoutingPipeline.segmentIntersectsAnyObstacle(
                            prev.x(), prev.y(), newA.x(), newA.y(), obstacles)
                            && !RoutingPipeline.segmentIntersectsAnyObstacle(
                                    newA.x(), newA.y(), d.x(), d.y(), obstacles)) {
                        path.set(i, newA);
                        path.remove(i + 2);
                        path.remove(i + 1);
                        totalCollapsed++;
                        changed = true;
                        break;
                    }
                }
            }
        }

        if (totalCollapsed > 0) {
            logger.debug("Staircase jog collapse: eliminated {} jogs within {}px threshold",
                    totalCollapsed, threshold);
        }
    }

    // ---------------------------------------------------------------------
    // shared helpers
    // ---------------------------------------------------------------------

    /**
     * Wrap helper. Returns {@code true} when the mutation may be kept.
     *
     * <h3>The faceline criterion is a delta, not a post-condition</h3>
     *
     * <p>A mutation is rejected on the faceline criterion only when it moves a
     * terminal <em>off a faceline that terminal was on before the mutation</em>.
     * A path that arrives with a terminal already off its faceline is not
     * rejected on that terminal's account. Judging the post-state alone would
     * reject every mutation on such a path, discarding straightening the
     * mutation never broke — and the four mutators here run on exactly the
     * congested paths that most need them.
     *
     * <p>The flip rule alone would say nothing more about an end once its
     * pre-mutation verdict is {@code false}: it compares faceline membership
     * of whatever point occupies the terminal index, not whether that point is
     * still the <em>same</em> point. So a mutator could rewrite an
     * already-off-face terminal, push it further off, or delete it and let the
     * next interior point inherit the slot, with no flip to observe. That is
     * what {@code pinsOffFaceTerminals} exists to prevent, and it is applied
     * <strong>per mutator</strong> because the four differ in whether they can
     * reach a terminal at all.
     *
     * <h3>Why only two of the four sites pin</h3>
     *
     * <p>Terminals sit at index {@code 1} and {@code size - 2} of the augmented
     * path. Whether a mutator can touch them is decided by its write range, not
     * by policy:
     *
     * <ul>
     *   <li>{@link #snapToStraightCore} writes {@code path.set(i, …)} over
     *       {@code i} in {@code [1, size-2]} — <strong>both terminals are in
     *       range</strong>, and it can move one onto the element centre, which is
     *       the degenerate zero-length-anchor shape. <strong>Pins.</strong></li>
     *   <li>{@link #collapseStaircaseJogsCore} writes {@code i} in
     *       {@code [1, size-4]} and removes {@code i+1}, {@code i+2} in
     *       {@code [2, size-2]} — <strong>both terminals are in range</strong>,
     *       and because it rewrites one point and removes two, a removed terminal
     *       need not be collinear with anything: the drawn route genuinely
     *       changes. <strong>Pins.</strong></li>
     *   <li>{@link #collapseBendsCore} removes {@code i+1} only when
     *       {@code path[i]}, {@code path[i+1]}, {@code path[i+2]} are collinear.
     *       Removing a terminal forces {@code i == 0}, so the triple is
     *       (centre sentinel, terminal, next) — a deleted terminal is therefore
     *       collinear with the element centre by construction, and the
     *       ChopboxAnchor ray is unchanged. Render-neutral in every case, not
     *       merely in the ones observed. <strong>Does not pin</strong>, and
     *       pinning it would reject a genuine redundant-bendpoint removal.</li>
     *   <li>{@link #eliminateReversalsCore} is bounded by its own
     *       {@code protectTerminals} guard, which skips every pair with
     *       {@code i == 0} or {@code j >= size - 2}, confining its removals and
     *       its L-turn insertion to {@code [2, size-3]}. It cannot reach a
     *       terminal. <strong>Does not pin</strong>; the flag would be dead
     *       code.</li>
     * </ul>
     *
     * <p>The asymmetry is therefore derived, not chosen: pin where the write
     * range reaches a terminal and nothing else bounds it. If
     * {@code collapseBendsCore}'s collinearity precondition is ever relaxed, or
     * {@code protectTerminals} removed, the corresponding site must start
     * pinning — both properties are pinned by test so the change fails loudly
     * rather than silently widening this wrap.
     *
     * <p>The structural arm below stays unconditional and independent of all of
     * the above.
     *
     * <p>The flip rule and the pin are not implemented here. They live in
     * {@link TerminalAnchoringRollbackPolicy}, shared with
     * {@link CoincidentSegmentDetector#applyOffsets} so that the five wrap
     * sites cannot drift apart on the one thing they used to disagree about.
     * What remains site-specific is above and below this paragraph: which
     * mutators pin, and the structural arm that only an augmented frame needs.
     *
     * <p>{@link CoincidentSegmentDetector#applyTerminalAnchoredReconciliation}
     * still compares the predicate over the <em>whole path</em>, before against
     * after, rather than per end. <strong>The load differs, which is why it is
     * left alone.</strong> There the terminal bendpoint is provably untouched —
     * that stage resolves a coincidence by inserting a drop bendpoint — so the
     * verdict must be invariant and a flip signals a logic bug; the check is
     * defensive, and its rollback logs a warning saying so. Here the mutators
     * can and do relocate terminals, so the flip <em>is</em> the policy: it is
     * the only thing standing between a straightening pass and a terminal
     * dragged off its perimeter.
     *
     * <h3>The two ends are decided independently</h3>
     *
     * <p>{@link TerminalAnchoring#preservesEndpoints} conjoins the two ends, so
     * once the source verdict is {@code false} no target verdict can change the
     * answer. Comparing that conjunction before and after would therefore leave
     * the target end <em>unprotected</em> on any path whose source arrived
     * off-face: both readings would be {@code false}, no flip would be seen, and
     * a mutation dragging the target off its faceline would commit unchallenged.
     * Each end is evaluated on its own instead, reusing the predicate's
     * null-per-end idiom rather than changing the predicate.
     *
     * <h3>The structural arm is unconditional</h3>
     *
     * <p>When {@code augmented} is true, the criterion is evaluated against an
     * interior view that excludes index 0 and {@code path.size() - 1} (the
     * temporary source/target center sentinels prepended/appended by
     * {@code RoutingPipeline} stage 4.7i). An augmented path that enters a wrap
     * site is always ≥ 4 BPs (sentinel + realSrc + realTgt + sentinel), so any
     * post-mutation size below 4 means at least one real terminal has been
     * collapsed out of the interior — REJECT, whatever the pre-state was. This
     * is the load-bearing replacement for the legacy {@code containsPerimeterBP}
     * guard that used to live inside the core mutators: it catches the
     * slot-at-hub-center class where a fully-collinear augmented path trivially
     * collapses to {@code [sourceCenter, targetCenter]} under
     * {@link #collapseBendsCore}, wiping the perimeter terminals and leaving an
     * empty BP list after stage 4.7i strips the sentinels. A terminal that was
     * already off its faceline is still a terminal; losing it is not licensed by
     * the delta rule.
     *
     * @param before the pre-mutation path, as snapshotted on wrap-site entry
     * @param after  the live path, post-mutation
     */

    private static boolean checkAnchoringWrap(
            List<AbsoluteBendpointDto> before, List<AbsoluteBendpointDto> after, boolean augmented,
            TerminalAnchoring sourceAnchoring, RoutingRect source, int[] sourceCenter,
            TerminalAnchoring targetAnchoring, RoutingRect target, int[] targetCenter,
            boolean pinsOffFaceTerminals) {
        if (augmented && after.size() < 4) {
            return false;
        }
        List<AbsoluteBendpointDto> beforeView = interiorView(before, augmented);
        List<AbsoluteBendpointDto> afterView = interiorView(after, augmented);

        return !TerminalAnchoringRollbackPolicy.rejects(sourceAnchoring, source, sourceCenter,
                        beforeView, afterView, false, pinsOffFaceTerminals)
                && !TerminalAnchoringRollbackPolicy.rejects(targetAnchoring, target, targetCenter,
                        beforeView, afterView, true, pinsOffFaceTerminals);
    }

    /**
     * The view the faceline criterion is evaluated against: the whole path when
     * unaugmented, otherwise the interior between the two sentinels.
     */
    private static List<AbsoluteBendpointDto> interiorView(
            List<AbsoluteBendpointDto> path, boolean augmented) {
        if (!augmented) {
            // Copied rather than aliased: the unaugmented view would otherwise
            // hand out the wrap site's own entry snapshot, the list the
            // rollback restores from. Nothing mutates it today; copying keeps
            // it that way without relying on every future reader to notice.
            return new ArrayList<>(path);
        }
        if (path.size() < 4) {
            return List.of();
        }
        return new ArrayList<>(path.subList(1, path.size() - 1));
    }

    private static boolean isReversal(List<AbsoluteBendpointDto> path, int i, int j) {
        AbsoluteBendpointDto a1 = path.get(i);
        AbsoluteBendpointDto a2 = path.get(i + 1);
        AbsoluteBendpointDto b1 = path.get(j);
        AbsoluteBendpointDto b2 = path.get(j + 1);

        int dxA = a2.x() - a1.x();
        int dxB = b2.x() - b1.x();
        if (dxA != 0 && dxB != 0 && Integer.signum(dxA) == -Integer.signum(dxB)) {
            return true;
        }

        int dyA = a2.y() - a1.y();
        int dyB = b2.y() - b1.y();
        if (dyA != 0 && dyB != 0 && Integer.signum(dyA) == -Integer.signum(dyB)) {
            return true;
        }

        return false;
    }

    private static AbsoluteBendpointDto tryLTurn(AbsoluteBendpointDto start,
            AbsoluteBendpointDto end, List<RoutingRect> obstacles) {
        AbsoluteBendpointDto hMid = new AbsoluteBendpointDto(end.x(), start.y());
        if (!RoutingPipeline.segmentIntersectsAnyObstacle(
                start.x(), start.y(), hMid.x(), hMid.y(), obstacles)
                && !RoutingPipeline.segmentIntersectsAnyObstacle(
                        hMid.x(), hMid.y(), end.x(), end.y(), obstacles)) {
            return hMid;
        }
        AbsoluteBendpointDto vMid = new AbsoluteBendpointDto(start.x(), end.y());
        if (!RoutingPipeline.segmentIntersectsAnyObstacle(
                start.x(), start.y(), vMid.x(), vMid.y(), obstacles)
                && !RoutingPipeline.segmentIntersectsAnyObstacle(
                        vMid.x(), vMid.y(), end.x(), end.y(), obstacles)) {
            return vMid;
        }
        return null;
    }
}
