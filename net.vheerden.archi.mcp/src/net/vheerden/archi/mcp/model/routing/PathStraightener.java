package net.vheerden.archi.mcp.model.routing;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Post-routing path straightening (backlog-b42).
 * Pure-geometry class — no EMF/SWT dependencies.
 *
 * <p>Cleans up routed paths after all pipeline stages by applying three
 * complementary transformations:
 * <ol>
 *   <li>{@link #snapToStraight} — snaps near-aligned consecutive points</li>
 *   <li>{@link #eliminateReversals} — collapses direction reversal patterns</li>
 *   <li>{@link #collapseBends} — removes redundant intermediate bendpoints</li>
 * </ol>
 *
 * <p>Complements Stage 4.7g (simplifyFinalPath) which does greedy forward
 * shortcutting but misses near-aligned snaps, reversal patterns, and
 * zigzag collapses.</p>
 */
public class PathStraightener {

    private static final Logger logger = LoggerFactory.getLogger(PathStraightener.class);

    private PathStraightener() {
        // Static utility class
    }

    /**
     * Snaps near-aligned consecutive points to eliminate small jogs.
     *
     * <p>For each pair of consecutive points, if the delta in one axis is
     * within the threshold and smaller than the delta in the other axis,
     * snaps the smaller delta to zero. Validates that snapped segments
     * remain obstacle-free before committing the snap.</p>
     *
     * <p>Does NOT handle terminal-to-terminal snapping (that's Stage 4.4).
     * Only processes paths with 3+ points (at least one intermediate BP).</p>
     *
     * @param path      mutable list of bendpoints (modified in place)
     * @param threshold maximum pixel delta to snap (e.g., 20)
     * @param obstacles list of element rectangles to avoid
     */
    public static void snapToStraight(List<AbsoluteBendpointDto> path, int threshold,
            List<RoutingRect> obstacles) {
        if (path.size() < 3) {
            return; // No intermediate bendpoints — Stage 4.4 handles terminal-only
        }

        int snapped = 0;
        // Process interior points only (preserve terminal anchors at index 0 and last)
        for (int i = 1; i < path.size() - 1; i++) {
            AbsoluteBendpointDto prev = path.get(i - 1);
            AbsoluteBendpointDto curr = path.get(i);
            AbsoluteBendpointDto next = path.get(i + 1);

            // Check alignment with predecessor
            int dx = Math.abs(curr.x() - prev.x());
            int dy = Math.abs(curr.y() - prev.y());

            AbsoluteBendpointDto candidate = null;
            if (dx > 0 && dx <= threshold && dy > dx) {
                candidate = new AbsoluteBendpointDto(prev.x(), curr.y());
            } else if (dy > 0 && dy <= threshold && dx > dy) {
                candidate = new AbsoluteBendpointDto(curr.x(), prev.y());
            }

            // If no snap from predecessor, check alignment with successor.
            // Only snaps when prev and next share the same axis — meaning this
            // is a kink in an otherwise straight run, not an L-turn corner.
            // Example: (1165,131)→(640,131)→(640,119) — prev and curr share Y=131,
            // curr and next share X=640 with 12px Y jog → snap Y to straighten.
            if (candidate == null) {
                int dxNext = Math.abs(curr.x() - next.x());
                int dyNext = Math.abs(curr.y() - next.y());

                // Only snap to successor when it straightens a segment without
                // removing an L-turn corner. Check that the resulting segment
                // from prev to snapped point maintains the same primary direction.
                if (dxNext > 0 && dxNext <= threshold && dyNext > dxNext) {
                    candidate = new AbsoluteBendpointDto(next.x(), curr.y());
                } else if (dyNext > 0 && dyNext <= threshold && dxNext > dyNext) {
                    candidate = new AbsoluteBendpointDto(curr.x(), next.y());
                } else if (dxNext == 0 && dyNext > 0 && dyNext <= threshold
                        && prev.y() != curr.y()) {
                    // Same X, small Y jog — only snap if prev→curr is NOT horizontal
                    // (horizontal prev→curr + vertical curr→next = valid L-turn, don't snap)
                    candidate = new AbsoluteBendpointDto(curr.x(), next.y());
                } else if (dyNext == 0 && dxNext > 0 && dxNext <= threshold
                        && prev.x() != curr.x()) {
                    // Same Y, small X jog — only snap if prev→curr is NOT vertical
                    candidate = new AbsoluteBendpointDto(next.x(), curr.y());
                }
            }

            if (candidate != null) {
                // Validate: new segments (prev→candidate and candidate→next) must be obstacle-free
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

    /**
     * Eliminates direction reversal patterns (overshoot-then-doubleback).
     *
     * <p>Detects segments where the path moves away from the eventual
     * direction, then reverses back. If the direct path from the start
     * of the reversal to the end is obstacle-free, collapses the
     * reversal to a direct connection.</p>
     *
     * <p>Only collapses reversals where the intermediate points are NOT
     * terminal anchors (first/last points are preserved).</p>
     *
     * @param path      mutable list of bendpoints (modified in place)
     * @param obstacles list of element rectangles to avoid
     */
    public static void eliminateReversals(List<AbsoluteBendpointDto> path,
            List<RoutingRect> obstacles) {
        if (path.size() < 4) {
            return; // Need at least 4 points for a reversal pattern
        }

        boolean changed = true;
        int iterations = 0;
        int maxIterations = path.size(); // Safety bound

        while (changed && iterations < maxIterations) {
            changed = false;
            iterations++;

            // Scan for the largest reversal first (outermost)
            // Terminal anchors protected by guard at i==0 && j+1==last (line below)
            for (int i = 0; i < path.size() - 3; i++) {
                // Try to find farthest reversal partner for segment starting at i
                for (int j = path.size() - 2; j > i; j--) {
                    // Don't collapse if it would remove terminal anchors
                    if (i == 0 && j + 1 == path.size() - 1) {
                        continue; // Would remove everything between terminals
                    }

                    if (isReversal(path, i, j)) {
                        AbsoluteBendpointDto start = path.get(i);
                        AbsoluteBendpointDto end = path.get(j + 1);

                        if (start.x() != end.x() && start.y() != end.y()) {
                            // Need L-turn — try both orientations
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
                            // Collinear — direct connection
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
                if (changed) break; // Restart scan after modification
            }
        }
    }

    /**
     * Collapses redundant intermediate bendpoints where a direct straight-line
     * connection is obstacle-free.
     *
     * <p>Only removes collinear intermediate points (where all three points
     * share a coordinate on the same axis). Does NOT attempt L-turn replacement
     * to avoid disrupting intentional routing geometry.</p>
     *
     * @param path      mutable list of bendpoints (modified in place)
     * @param obstacles list of element rectangles to avoid
     */
    public static void collapseBends(List<AbsoluteBendpointDto> path,
            List<RoutingRect> obstacles) {
        if (path.size() < 4) {
            return; // Need at least 4 points — 3-point paths are already minimal L-turns
        }

        boolean changed = true;
        int totalRemoved = 0;
        int maxIterations = path.size(); // Safety bound

        while (changed && maxIterations-- > 0) {
            changed = false;
            for (int i = 0; i < path.size() - 2; i++) {
                int midIdx = i + 1;

                AbsoluteBendpointDto a = path.get(i);
                AbsoluteBendpointDto mid = path.get(midIdx);
                AbsoluteBendpointDto c = path.get(i + 2);

                // Only collapse if a→mid→c forms a straight line (collinear)
                // and the direct a→c is obstacle-free
                boolean collinearX = (a.x() == mid.x() && mid.x() == c.x());
                boolean collinearY = (a.y() == mid.y() && mid.y() == c.y());

                if ((collinearX || collinearY)
                        && !RoutingPipeline.segmentIntersectsAnyObstacle(
                                a.x(), a.y(), c.x(), c.y(), obstacles)) {
                    path.remove(midIdx);
                    totalRemoved++;
                    changed = true;
                    break; // Restart iteration
                }
            }
        }

        if (totalRemoved > 0) {
            logger.debug("Bend collapse: removed {} redundant collinear bendpoints", totalRemoved);
        }
    }

    /**
     * Collapses staircase jog patterns where two parallel segments are connected
     * by a small perpendicular step within the snap threshold.
     *
     * <p>Detects patterns like:
     * <pre>
     * a ──horizontal── b
     *                  │ (small jog ≤ threshold)
     *                  c ──horizontal── d
     * </pre>
     * and the vertical equivalent. Shifts point {@code a} to align with
     * {@code d}'s axis, removing points {@code b} and {@code c}.</p>
     *
     * <p>Requires source/target centers at index 0 and last (prepended by
     * the pipeline). Skips index 0 to preserve the source terminal.</p>
     *
     * @param path      mutable list of bendpoints (modified in place)
     * @param threshold maximum pixel jog to collapse (e.g., 20)
     * @param obstacles list of element rectangles to avoid
     */
    public static void collapseStaircaseJogs(List<AbsoluteBendpointDto> path, int threshold,
            List<RoutingRect> obstacles) {
        if (path.size() < 4) {
            return;
        }

        boolean changed = true;
        int totalCollapsed = 0;
        int maxIterations = path.size();

        while (changed && maxIterations-- > 0) {
            changed = false;
            // Start at i=1 to protect source terminal at index 0
            for (int i = 1; i < path.size() - 3; i++) {
                AbsoluteBendpointDto a = path.get(i);
                AbsoluteBendpointDto b = path.get(i + 1);
                AbsoluteBendpointDto c = path.get(i + 2);
                AbsoluteBendpointDto d = path.get(i + 3);

                AbsoluteBendpointDto newA = null;

                // Pattern 1: horizontal-vertical(jog)-horizontal
                if (a.y() == b.y() && b.x() == c.x() && c.y() == d.y()) {
                    int jog = Math.abs(a.y() - d.y());
                    if (jog > 0 && jog <= threshold) {
                        newA = new AbsoluteBendpointDto(a.x(), d.y());
                    }
                }
                // Pattern 2: vertical-horizontal(jog)-vertical
                else if (a.x() == b.x() && b.y() == c.y() && c.x() == d.x()) {
                    int jog = Math.abs(a.x() - d.x());
                    if (jog > 0 && jog <= threshold) {
                        newA = new AbsoluteBendpointDto(d.x(), a.y());
                    }
                }

                if (newA != null) {
                    // Validate: prev→newA and newA→d must be obstacle-free
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

    /**
     * Checks if segment[i] and segment[j] form a direction reversal.
     * A reversal means moving in one direction then later reversing on the same axis.
     */
    private static boolean isReversal(List<AbsoluteBendpointDto> path, int i, int j) {
        AbsoluteBendpointDto a1 = path.get(i);
        AbsoluteBendpointDto a2 = path.get(i + 1);
        AbsoluteBendpointDto b1 = path.get(j);
        AbsoluteBendpointDto b2 = path.get(j + 1);

        // Check horizontal reversal: segment i goes left/right, segment j goes opposite
        int dxA = a2.x() - a1.x();
        int dxB = b2.x() - b1.x();
        if (dxA != 0 && dxB != 0 && Integer.signum(dxA) == -Integer.signum(dxB)) {
            return true;
        }

        // Check vertical reversal: segment i goes up/down, segment j goes opposite
        int dyA = a2.y() - a1.y();
        int dyB = b2.y() - b1.y();
        if (dyA != 0 && dyB != 0 && Integer.signum(dyA) == -Integer.signum(dyB)) {
            return true;
        }

        return false;
    }

    /**
     * Attempts to create an L-turn midpoint between start and end.
     * Tries horizontal-first, then vertical-first. Returns null if both blocked.
     */
    private static AbsoluteBendpointDto tryLTurn(AbsoluteBendpointDto start,
            AbsoluteBendpointDto end, List<RoutingRect> obstacles) {
        // Horizontal-first: (start.x, start.y) → (end.x, start.y) → (end.x, end.y)
        AbsoluteBendpointDto hMid = new AbsoluteBendpointDto(end.x(), start.y());
        if (!RoutingPipeline.segmentIntersectsAnyObstacle(
                start.x(), start.y(), hMid.x(), hMid.y(), obstacles)
                && !RoutingPipeline.segmentIntersectsAnyObstacle(
                        hMid.x(), hMid.y(), end.x(), end.y(), obstacles)) {
            return hMid;
        }
        // Vertical-first: (start.x, start.y) → (start.x, end.y) → (end.x, end.y)
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
