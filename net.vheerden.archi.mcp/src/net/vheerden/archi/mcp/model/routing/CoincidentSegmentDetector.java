package net.vheerden.archi.mcp.model.routing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Detects and offsets coincident connection segments.
 * Pure-geometry class — no EMF/SWT dependencies.
 *
 * <p>After edge nudging, multiple connections may still share identical path
 * segments (same start/end coordinates). This class detects such coincident
 * segments and applies small perpendicular offsets to make each connection
 * visually distinguishable.</p>
 *
 * <p>A "coincident" pair of segments shares the same orientation (H/V),
 * the same shared coordinate (within tolerance), and overlapping parallel
 * ranges — meaning they visually overlap on the diagram.</p>
 */
public class CoincidentSegmentDetector {

    private static final Logger logger = LoggerFactory.getLogger(CoincidentSegmentDetector.class);

    /** Default tolerance for matching shared coordinates (px). */
    static final int DEFAULT_COORDINATE_TOLERANCE = 2;

    /** Default perpendicular offset between coincident segments (px). */
    static final int DEFAULT_OFFSET_DELTA = 10;

    /** Minimum parallel overlap length to consider segments coincident (px). */
    static final int MIN_OVERLAP_LENGTH = 5;

    /** Minimum pixel separation between proportionally distributed segments. */
    static final int MIN_SEPARATION = 8;

    /** Maximum gap extent when unbounded on one side (prevents extreme drift). */
    static final int MAX_UNBOUNDED_EXTENT = 100;

    private final int coordinateTolerance;
    private final int offsetDelta;
    private final PathOrderer pathOrderer;

    public CoincidentSegmentDetector() {
        this(DEFAULT_COORDINATE_TOLERANCE, DEFAULT_OFFSET_DELTA, new PathOrderer());
    }

    CoincidentSegmentDetector(PathOrderer pathOrderer) {
        this(DEFAULT_COORDINATE_TOLERANCE, DEFAULT_OFFSET_DELTA, pathOrderer);
    }

    CoincidentSegmentDetector(int coordinateTolerance, int offsetDelta, PathOrderer pathOrderer) {
        this.coordinateTolerance = coordinateTolerance;
        this.offsetDelta = offsetDelta;
        this.pathOrderer = pathOrderer;
    }

    /**
     * A pair of coincident segments from different connections.
     *
     * @param segA first segment
     * @param segB second segment (from a different connection)
     * @param overlapStart start of the parallel overlap range
     * @param overlapEnd   end of the parallel overlap range
     */
    record CoincidentPair(PathOrderer.Segment segA, PathOrderer.Segment segB,
                          int overlapStart, int overlapEnd) {
        int overlapLength() {
            return Math.abs(overlapEnd - overlapStart);
        }
    }

    /**
     * Detects coincident segments across all routed connections.
     *
     * @param connectionIds  connection identifiers
     * @param bendpointLists bendpoint lists per connection
     * @param sourceCenters  source center [x, y] per connection
     * @param targetCenters  target center [x, y] per connection
     * @return list of coincident segment pairs
     */
    public List<CoincidentPair> detect(
            List<String> connectionIds,
            List<List<AbsoluteBendpointDto>> bendpointLists,
            List<int[]> sourceCenters,
            List<int[]> targetCenters) {

        // Extract and group segments using PathOrderer's existing logic
        List<PathOrderer.Segment> allSegments = new ArrayList<>();
        for (int i = 0; i < bendpointLists.size(); i++) {
            List<AbsoluteBendpointDto> bendpoints = bendpointLists.get(i);
            if (bendpoints.size() < 2) {
                continue;
            }
            pathOrderer.extractSegments(i, bendpoints, sourceCenters.get(i),
                    targetCenters.get(i), allSegments);
        }

        if (allSegments.isEmpty()) {
            return List.of();
        }

        Map<String, List<PathOrderer.Segment>> groups = pathOrderer.groupSegments(allSegments);

        List<CoincidentPair> coincidentPairs = new ArrayList<>();
        for (List<PathOrderer.Segment> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            detectCoincidentInGroup(group, coincidentPairs);
        }

        if (!coincidentPairs.isEmpty()) {
            logger.info("Detected {} coincident segment pairs", coincidentPairs.size());
        }

        return coincidentPairs;
    }

    /**
     * Within a corridor group (same orientation + shared coordinate), finds
     * pairs of segments from different connections whose parallel ranges overlap.
     */
    void detectCoincidentInGroup(List<PathOrderer.Segment> group,
                                  List<CoincidentPair> out) {
        for (int i = 0; i < group.size(); i++) {
            for (int j = i + 1; j < group.size(); j++) {
                PathOrderer.Segment segA = group.get(i);
                PathOrderer.Segment segB = group.get(j);

                if (segA.connectionIndex() == segB.connectionIndex()) {
                    continue;
                }

                // Check shared coordinate match (within tolerance — already grouped)
                if (Math.abs(segA.sharedCoordinate() - segB.sharedCoordinate())
                        > coordinateTolerance) {
                    continue;
                }

                // Check parallel range overlap
                int[] overlapRange = computeParallelOverlap(segA, segB);
                if (overlapRange != null && Math.abs(overlapRange[1] - overlapRange[0])
                        >= MIN_OVERLAP_LENGTH) {
                    out.add(new CoincidentPair(segA, segB,
                            overlapRange[0], overlapRange[1]));
                }
            }
        }
    }

    /**
     * Computes the parallel overlap range of two segments (assumed same orientation).
     *
     * @return [overlapStart, overlapEnd] or null if no overlap
     */
    int[] computeParallelOverlap(PathOrderer.Segment a, PathOrderer.Segment b) {
        int aMin, aMax, bMin, bMax;
        if (a.horizontal()) {
            aMin = Math.min(a.x1(), a.x2());
            aMax = Math.max(a.x1(), a.x2());
            bMin = Math.min(b.x1(), b.x2());
            bMax = Math.max(b.x1(), b.x2());
        } else {
            aMin = Math.min(a.y1(), a.y2());
            aMax = Math.max(a.y1(), a.y2());
            bMin = Math.min(b.y1(), b.y2());
            bMax = Math.max(b.y1(), b.y2());
        }

        int overlapStart = Math.max(aMin, bMin);
        int overlapEnd = Math.min(aMax, bMax);

        if (overlapStart < overlapEnd) {
            return new int[]{overlapStart, overlapEnd};
        }
        return null; // No overlap
    }

    /**
     * Computes the available perpendicular gap for a corridor by scanning obstacles.
     *
     * <p>For a corridor at shared coordinate C with parallel range [overlapStart, overlapEnd],
     * finds the nearest obstacle boundary on each perpendicular side. Only considers
     * obstacles whose parallel extent overlaps the corridor's parallel range.</p>
     *
     * @param sharedCoordinate the corridor's shared coordinate (y for horizontal, x for vertical)
     * @param horizontal       true if horizontal corridor, false if vertical
     * @param overlapStart     start of the corridor's parallel range
     * @param overlapEnd       end of the corridor's parallel range
     * @param obstacles        every non-container view object — elements, notes and images alike
     * @return gap bounds as [minBound, maxBound], or null if corridor lies inside an obstacle
     */
    int[] computeCorridorGap(int sharedCoordinate, boolean horizontal,
                             int overlapStart, int overlapEnd,
                             List<RoutingRect> obstacles) {
        int nearBound = sharedCoordinate - MAX_UNBOUNDED_EXTENT; // default if no obstacle found
        int farBound = sharedCoordinate + MAX_UNBOUNDED_EXTENT;
        boolean nearFound = false;
        boolean farFound = false;

        for (RoutingRect obs : obstacles) {
            int obsParallelStart, obsParallelEnd, obsPerpStart, obsPerpEnd;
            if (horizontal) {
                // Horizontal corridor: parallel = x-axis, perpendicular = y-axis
                obsParallelStart = obs.x();
                obsParallelEnd = obs.x() + obs.width();
                obsPerpStart = obs.y();
                obsPerpEnd = obs.y() + obs.height();
            } else {
                // Vertical corridor: parallel = y-axis, perpendicular = x-axis
                obsParallelStart = obs.y();
                obsParallelEnd = obs.y() + obs.height();
                obsPerpStart = obs.x();
                obsPerpEnd = obs.x() + obs.width();
            }

            // Skip obstacles whose parallel range doesn't overlap the corridor
            if (obsParallelEnd <= overlapStart || obsParallelStart >= overlapEnd) {
                continue;
            }

            // Check if corridor lies inside this obstacle (gap = 0)
            if (obsPerpStart <= sharedCoordinate && obsPerpEnd >= sharedCoordinate) {
                return null;
            }

            // Obstacle is on the "near" side (lower perpendicular values)
            if (obsPerpEnd <= sharedCoordinate) {
                if (!nearFound || obsPerpEnd > nearBound) {
                    nearBound = obsPerpEnd;
                    nearFound = true;
                }
            }

            // Obstacle is on the "far" side (higher perpendicular values)
            if (obsPerpStart >= sharedCoordinate) {
                if (!farFound || obsPerpStart < farBound) {
                    farBound = obsPerpStart;
                    farFound = true;
                }
            }
        }

        return new int[]{nearBound, farBound};
    }

    /**
     * Computes evenly distributed absolute target coordinates for N segments
     * within a gap of width W, centered on the corridor midpoint.
     *
     * <p>Positions are computed as: gapStart + gapWidth * (i+1) / (N+1) for i in [0, N).
     * If the spacing between segments would be less than {@link #MIN_SEPARATION},
     * returns null to signal that the caller should fall back to fixed-delta stacking.</p>
     *
     * @param gapStart     lower bound of the available gap
     * @param gapEnd       upper bound of the available gap
     * @param segmentCount number of segments to distribute (N)
     * @return array of N absolute target coordinates, or null if gap too narrow
     */
    int[] computeProportionalOffsets(int gapStart, int gapEnd, int segmentCount) {
        int gapWidth = gapEnd - gapStart;
        if (segmentCount <= 0 || gapWidth <= 0) {
            return null;
        }

        // Check minimum separation constraint
        int spacing = gapWidth / (segmentCount + 1);
        if (spacing < MIN_SEPARATION) {
            return null;
        }

        int[] positions = new int[segmentCount];
        for (int i = 0; i < segmentCount; i++) {
            positions[i] = gapStart + gapWidth * (i + 1) / (segmentCount + 1);
        }
        return positions;
    }

    /**
     * Per-connection anchoring context bundle for wrap-site rollback.
     *
     * <p>Built once per {@code applyOffsets} call and looked up by connection
     * index. A {@code null} context (or null anchorings inside) bypasses the
     * predicate check for that connection — the legacy 3-arg overload
     * constructs an empty map, preserving earlier test behaviour.</p>
     */
    public record AnchoringContext(
            RoutingPipeline.ConnectionEndpoints connection,
            int[] sourceCenter, int[] targetCenter,
            TerminalAnchoring sourceAnchoring, TerminalAnchoring targetAnchoring) {}

    /**
     * Applies perpendicular offsets to coincident segments to make them visually
     * distinguishable. Uses corridor-group-first processing with proportional
     * spacing when sufficient gap exists between obstacles.
     *
     * <p>Legacy overload — runs without the terminal-anchoring wrap.
     * Used by unit tests that have no per-connection anchoring context.</p>
     *
     * @param coincidentPairs detected coincident pairs
     * @param bendpointLists  mutable bendpoint lists per connection (modified in place)
     * @param allObstacles    every non-container view object — elements, notes and images alike
     * @return number of segments actually offset
     */
    public int applyOffsets(List<CoincidentPair> coincidentPairs,
                            List<List<AbsoluteBendpointDto>> bendpointLists,
                            List<RoutingRect> allObstacles) {
        return applyOffsets(coincidentPairs, bendpointLists, allObstacles, java.util.Map.of());
    }

    /**
     * Wrap-site overload: applies coincident segment offsets while
     * enforcing the {@link TerminalAnchoring#preservesEndpoints} predicate.
     *
     * <p>Replaces the Mode B {@code touchesPerimeterAnchoredTerminal}
     * filter. When a {@code tryOffset} call mutates segment endpoint at
     * index 0 or {@code path.size() - 1}, the predicate is re-evaluated; on
     * violation, both touched BPs are restored from the per-segment snapshot
     * and the offset is reported as not-applied. The legacy 3-arg overload
     * routes here with an empty {@code anchoringContexts} map, in which case
     * every connection's check is bypassed (no-op wrap).</p>
     *
     * @param anchoringContexts per-connection-index anchoring context; an
     *                          absent or {@code null} value bypasses the wrap
     *                          for that connection
     */
    public int applyOffsets(List<CoincidentPair> coincidentPairs,
                            List<List<AbsoluteBendpointDto>> bendpointLists,
                            List<RoutingRect> allObstacles,
                            Map<Integer, AnchoringContext> anchoringContexts) {
        if (coincidentPairs.isEmpty()) {
            return 0;
        }

        // --- First pass: Collect unique segments per corridor (tolerance-aware) ---
        Map<String, List<PathOrderer.Segment>> corridorGroups = new LinkedHashMap<>();
        Set<String> seenSegments = new HashSet<>();

        for (CoincidentPair pair : coincidentPairs) {
            addToCorridorGroup(corridorGroups, seenSegments, pair.segA());
            addToCorridorGroup(corridorGroups, seenSegments, pair.segB());
        }

        // --- Second & third pass: For each corridor, compute gap and apply offsets ---
        Set<String> alreadyOffset = new HashSet<>();
        int offsetCount = 0;

        for (Map.Entry<String, List<PathOrderer.Segment>> entry : corridorGroups.entrySet()) {
            List<PathOrderer.Segment> segments = entry.getValue();
            if (segments.size() < 2) {
                continue;
            }

            boolean horizontal = segments.get(0).horizontal();

            // Compute overlap range across all segments in this corridor
            int overlapStart = Integer.MAX_VALUE;
            int overlapEnd = Integer.MIN_VALUE;
            int avgSharedCoord = 0;
            for (PathOrderer.Segment seg : segments) {
                int min, max;
                if (horizontal) {
                    min = Math.min(seg.x1(), seg.x2());
                    max = Math.max(seg.x1(), seg.x2());
                } else {
                    min = Math.min(seg.y1(), seg.y2());
                    max = Math.max(seg.y1(), seg.y2());
                }
                overlapStart = Math.min(overlapStart, min);
                overlapEnd = Math.max(overlapEnd, max);
                avgSharedCoord += seg.sharedCoordinate();
            }
            avgSharedCoord /= segments.size();

            // Try proportional spacing
            int[] gap = computeCorridorGap(avgSharedCoord, horizontal,
                    overlapStart, overlapEnd, allObstacles);
            int[] proportionalPositions = (gap != null)
                    ? computeProportionalOffsets(gap[0], gap[1], segments.size())
                    : null;

            if (proportionalPositions != null) {
                // Proportional mode: distribute all segments across the gap
                offsetCount += applyProportionalOffsets(segments, proportionalPositions,
                        horizontal, bendpointLists, allObstacles, alreadyOffset, anchoringContexts);
                logger.debug("Proportional spacing for corridor {}: {} segments across gap [{}, {}]",
                        entry.getKey(), segments.size(), gap[0], gap[1]);
            } else {
                // Fixed-delta fallback: original stacking behavior
                offsetCount += applyFixedDeltaOffsets(segments, horizontal,
                        bendpointLists, allObstacles, alreadyOffset, anchoringContexts);
                logger.debug("Fixed-delta fallback for corridor {}: {} segments",
                        entry.getKey(), segments.size());
            }
        }

        if (offsetCount > 0) {
            logger.info("Applied {} coincident segment offsets", offsetCount);
        }
        return offsetCount;
    }

    /**
     * Adds a segment to the appropriate corridor group using tolerance-aware key matching,
     * consistent with {@link PathOrderer#groupSegments}.
     */
    private void addToCorridorGroup(Map<String, List<PathOrderer.Segment>> corridorGroups,
                                     Set<String> seenSegments, PathOrderer.Segment seg) {
        String segKey = seg.connectionIndex() + ":" + seg.segmentIndex();
        if (!seenSegments.add(segKey)) {
            return; // Already added
        }

        String prefix = seg.horizontal() ? "H:" : "V:";
        int coord = seg.sharedCoordinate();

        // Tolerance-aware key matching (mirrors PathOrderer.findOrCreateGroupKey)
        String matchedKey = null;
        for (String key : corridorGroups.keySet()) {
            if (!key.startsWith(prefix)) continue;
            int groupCoord = Integer.parseInt(key.substring(2));
            if (Math.abs(coord - groupCoord) <= coordinateTolerance) {
                matchedKey = key;
                break;
            }
        }

        String key = (matchedKey != null) ? matchedKey : prefix + coord;
        corridorGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(seg);
    }

    /**
     * Applies proportional spacing: moves each segment to its computed target position.
     * Falls back to fixed-delta for individual segments if proportional position is blocked.
     */
    private int applyProportionalOffsets(List<PathOrderer.Segment> segments,
                                          int[] targetPositions, boolean horizontal,
                                          List<List<AbsoluteBendpointDto>> bendpointLists,
                                          List<RoutingRect> allObstacles,
                                          Set<String> alreadyOffset,
                                          Map<Integer, AnchoringContext> anchoringContexts) {
        int count = 0;
        for (int i = 0; i < segments.size(); i++) {
            PathOrderer.Segment seg = segments.get(i);
            String segKey = seg.connectionIndex() + ":" + seg.segmentIndex();
            if (alreadyOffset.contains(segKey)) {
                continue;
            }

            List<AbsoluteBendpointDto> path = bendpointLists.get(seg.connectionIndex());
            int bpIdx1 = seg.segmentIndex();
            int bpIdx2 = seg.segmentIndex() + 1;
            if (bpIdx1 < 0 || bpIdx2 >= path.size()) {
                continue;
            }

            int currentCoord = seg.sharedCoordinate();
            int targetCoord = targetPositions[i];
            int delta = targetCoord - currentCoord;

            if (delta == 0) {
                continue; // Already at target position
            }

            // Wrap: snapshot the two BPs before mutating so we can roll back
            // on terminal anchoring violation at index 0 / path.size()-1.
            AbsoluteBendpointDto snap1 = path.get(bpIdx1);
            AbsoluteBendpointDto snap2 = path.get(bpIdx2);

            boolean applied = tryOffset(path, bpIdx1, bpIdx2, horizontal, delta, allObstacles);
            boolean usedFallback = false;
            if (!applied) {
                // Proportional position blocked — try fixed-delta fallback for this segment
                int fixedDelta = offsetDelta * (i + 1);
                applied = tryOffset(path, bpIdx1, bpIdx2, horizontal, fixedDelta, allObstacles);
                if (!applied) {
                    applied = tryOffset(path, bpIdx1, bpIdx2, horizontal, -fixedDelta, allObstacles);
                }
                usedFallback = applied;
            }

            if (applied && violatesTerminalAnchoring(
                    seg.connectionIndex(), bpIdx1, bpIdx2, path, snap1, snap2, anchoringContexts)) {
                path.set(bpIdx1, snap1);
                path.set(bpIdx2, snap2);
                applied = false;
                logger.debug("applyOffsets (proportional): rolled back conn[{}] seg[{}] — terminal anchoring violated",
                        seg.connectionIndex(), seg.segmentIndex());
            }

            if (applied) {
                alreadyOffset.add(segKey);
                count++;
                if (usedFallback) {
                    logger.debug("Offset coincident segment (proportional blocked, fixed fallback): conn[{}] seg[{}] {}",
                            seg.connectionIndex(), seg.segmentIndex(),
                            horizontal ? "vertically" : "horizontally");
                } else {
                    logger.debug("Offset coincident segment: conn[{}] seg[{}] to proportional target {}px {}",
                            seg.connectionIndex(), seg.segmentIndex(), targetCoord,
                            horizontal ? "vertically" : "horizontally");
                }
            }
        }
        return count;
    }

    /**
     * Applies fixed-delta stacking offsets (original behavior, used as fallback).
     * Skips the first segment in the group (anchor) and offsets remaining segments.
     */
    private int applyFixedDeltaOffsets(List<PathOrderer.Segment> segments, boolean horizontal,
                                        List<List<AbsoluteBendpointDto>> bendpointLists,
                                        List<RoutingRect> allObstacles,
                                        Set<String> alreadyOffset,
                                        Map<Integer, AnchoringContext> anchoringContexts) {
        int count = 0;
        for (int i = 1; i < segments.size(); i++) {
            PathOrderer.Segment seg = segments.get(i);
            String segKey = seg.connectionIndex() + ":" + seg.segmentIndex();
            if (alreadyOffset.contains(segKey)) {
                continue;
            }

            List<AbsoluteBendpointDto> path = bendpointLists.get(seg.connectionIndex());
            int bpIdx1 = seg.segmentIndex();
            int bpIdx2 = seg.segmentIndex() + 1;
            if (bpIdx1 < 0 || bpIdx2 >= path.size()) {
                continue;
            }

            // Wrap: snapshot the two BPs for rollback on predicate violation.
            AbsoluteBendpointDto snap1 = path.get(bpIdx1);
            AbsoluteBendpointDto snap2 = path.get(bpIdx2);

            int delta = offsetDelta * i;
            boolean applied = tryOffset(path, bpIdx1, bpIdx2, horizontal, delta, allObstacles);
            if (!applied) {
                applied = tryOffset(path, bpIdx1, bpIdx2, horizontal, -delta, allObstacles);
            }

            if (applied && violatesTerminalAnchoring(
                    seg.connectionIndex(), bpIdx1, bpIdx2, path, snap1, snap2, anchoringContexts)) {
                path.set(bpIdx1, snap1);
                path.set(bpIdx2, snap2);
                applied = false;
                logger.debug("applyOffsets (fixed-delta): rolled back conn[{}] seg[{}] — terminal anchoring violated",
                        seg.connectionIndex(), seg.segmentIndex());
            }

            if (applied) {
                alreadyOffset.add(segKey);
                count++;
                logger.debug("Offset coincident segment (fixed): conn[{}] seg[{}] by {}px {}",
                        seg.connectionIndex(), seg.segmentIndex(), delta,
                        horizontal ? "vertically" : "horizontally");
            }
        }
        return count;
    }

    /**
     * Attempts to offset two bendpoints (defining a segment) by delta
     * perpendicular to the segment direction. Returns true if the offset
     * was applied without obstacle collision.
     */
    private boolean tryOffset(List<AbsoluteBendpointDto> path,
                               int bpIdx1, int bpIdx2, boolean horizontal,
                               int delta, List<RoutingRect> obstacles) {
        AbsoluteBendpointDto bp1 = path.get(bpIdx1);
        AbsoluteBendpointDto bp2 = path.get(bpIdx2);

        AbsoluteBendpointDto newBp1, newBp2;
        if (horizontal) {
            // Horizontal segment: offset vertically
            newBp1 = new AbsoluteBendpointDto(bp1.x(), bp1.y() + delta);
            newBp2 = new AbsoluteBendpointDto(bp2.x(), bp2.y() + delta);
        } else {
            // Vertical segment: offset horizontally
            newBp1 = new AbsoluteBendpointDto(bp1.x() + delta, bp1.y());
            newBp2 = new AbsoluteBendpointDto(bp2.x() + delta, bp2.y());
        }

        // Check if shifted segment overlaps any obstacle
        if (segmentOverlapsAnyObstacle(newBp1.x(), newBp1.y(),
                newBp2.x(), newBp2.y(), obstacles)) {
            return false;
        }

        path.set(bpIdx1, newBp1);
        path.set(bpIdx2, newBp2);
        return true;
    }

    /**
     * Wrap-site rollback gate: returns {@code true} iff the offset just applied
     * must be rolled back on the terminal-anchoring criterion.
     *
     * <p>The gate opens only when the offset segment touches a terminal index
     * (0 or {@code path.size() - 1}); a purely interior offset cannot move a
     * terminal and is never checked. When {@code anchoringContexts} is empty or
     * has no entry for {@code connIdx} this returns {@code false} (the legacy
     * 3-arg overload's no-op wrap).
     *
     * <p><strong>Decided per end, and only for an end this offset touched.</strong>
     * {@link TerminalAnchoring#preservesEndpoints} conjoins the two ends, and
     * the gate above establishes only that the segment reaches <em>one</em> of
     * them. Judging that conjunction on the post-state therefore let an end at
     * the far side of the path — one this offset could not reach — veto the
     * offset, most often an end that a stage licensed to shape terminals by
     * contract (the {@code alignTerminalsWithCenter} family,
     * {@link ChannelNudgingPass}) had legitimately moved off its faceline. Each
     * touched end is now decided on its own, against
     * {@link TerminalAnchoringRollbackPolicy} — the same implementation the four
     * {@link PathStraightener} wrap sites use, so all five share one policy and
     * not merely one predicate.
     *
     * <p><em>The per-end restriction below is answer-preserving, not
     * load-bearing, and is kept because it states the policy where a reader
     * looks for it.</em> {@link #tryOffset} writes {@code bpIdx1} and
     * {@code bpIdx2} and nothing else, so an end this offset did not touch is
     * byte-identical before and after — which makes both arms of the policy
     * false for it anyway: it cannot have flipped, and the pin's
     * "arrived off-face and moved" cannot hold for a point that did not move.
     * Evaluating an untouched end would therefore return the same verdict.
     * What actually changed the behaviour is the move from a post-state
     * conjunction to the flip-and-pin arms; deleting these two conjuncts alone
     * changes no outcome, and no test can distinguish that mutation.
     *
     * <p><strong>This site pins.</strong> Its write range is exactly
     * {@code bpIdx1} and {@code bpIdx2}, and the terminal gate above is the
     * statement that those can be terminal indices; nothing else bounds them.
     * So a terminal that arrived off its faceline is not relocated either. The
     * division of labour that makes this right is already in the pipeline:
     * {@link #applyTerminalAnchoredReconciliation} exists to resolve the
     * coincidences this pass declines, and resolves them by inserting a drop
     * bendpoint while leaving the terminal byte-identical. Relocating an
     * off-face terminal here would be this pass doing that stage's job badly.
     *
     * <p><strong>No structural arm.</strong> {@code checkAnchoringWrap}'s
     * {@code after.size() < 4} rejection guards an augmented frame whose
     * sentinels can collapse and take a real terminal with them. Neither hazard
     * exists here: both call sites run outside the augmentation applied at
     * stage 4.7i, so {@code path[0]} and {@code path[size-1]} are the real
     * perimeter terminals, and {@link #tryOffset} writes through
     * {@code path.set} only, leaving the size invariant. A size check here could
     * never fire.
     *
     * @param snap1 {@code path[bpIdx1]} as it was before {@link #tryOffset} ran
     * @param snap2 {@code path[bpIdx2]} as it was before {@link #tryOffset} ran
     */
    private static boolean violatesTerminalAnchoring(
            int connIdx, int bpIdx1, int bpIdx2, List<AbsoluteBendpointDto> path,
            AbsoluteBendpointDto snap1, AbsoluteBendpointDto snap2,
            Map<Integer, AnchoringContext> anchoringContexts) {
        if (anchoringContexts == null || anchoringContexts.isEmpty()) {
            return false;
        }
        // Only terminal-touching segments can move a terminal.
        boolean touchesSource = (bpIdx1 == 0);
        boolean touchesTarget = (bpIdx2 == path.size() - 1);
        if (!touchesSource && !touchesTarget) {
            return false;
        }
        AnchoringContext ctx = anchoringContexts.get(connIdx);
        if (ctx == null) {
            return false;
        }
        List<AbsoluteBendpointDto> before = preMutationPath(path, bpIdx1, bpIdx2, snap1, snap2);
        if (touchesSource && TerminalAnchoringRollbackPolicy.rejects(
                ctx.sourceAnchoring(), ctx.connection().source(), ctx.sourceCenter(),
                before, path, false, true)) {
            return true;
        }
        return touchesTarget && TerminalAnchoringRollbackPolicy.rejects(
                ctx.targetAnchoring(), ctx.connection().target(), ctx.targetCenter(),
                before, path, true, true);
    }

    /**
     * Reconstructs the path as it stood before {@link #tryOffset} ran.
     *
     * <p>Exact rather than approximate: {@code tryOffset} writes through
     * {@code path.set} at {@code bpIdx1} and {@code bpIdx2} and nowhere else,
     * so restoring those two slots from their snapshots reproduces the
     * pre-mutation path point for point. The size is invariant across the
     * mutation, which is also why the before and after terminals occupy the
     * same slots and can be compared by index without re-deriving anything.
     */
    private static List<AbsoluteBendpointDto> preMutationPath(
            List<AbsoluteBendpointDto> path, int bpIdx1, int bpIdx2,
            AbsoluteBendpointDto snap1, AbsoluteBendpointDto snap2) {
        List<AbsoluteBendpointDto> before = new ArrayList<>(path);
        before.set(bpIdx1, snap1);
        before.set(bpIdx2, snap2);
        return before;
    }

    /**
     * Approach-3 reconciliation pass for coincident segments that touch a
     * terminal BP — runs after {@link #applyOffsets} as a downstream stage.
     *
     * <p>The rollback in {@link #applyOffsets} preserves terminal
     * anchoring by reverting any perpendicular delta-shift that would move a
     * terminal BP off its face line. For corridor-perpendicular coincidences
     * (multiple connections terminating at distinct allocated face slots
     * but sharing a common approach corridor — the V4 oracle current-pipeline
     * regression pattern), the simple delta-shift is the wrong mutation
     * shape: the rollback fires correctly but the coincidence persists. This
     * method resolves that residual by <em>inserting</em> two BPs around
     * the existing terminal BP:
     *
     * <ol>
     *   <li>Shift the interior endpoint of the coincident segment perpendicular
     *       to its corridor by {@code delta} px.</li>
     *   <li>Insert a new BP between the shifted interior endpoint and the
     *       still-anchored terminal BP, at the terminal's parallel coordinate
     *       and the shifted perpendicular coordinate — this creates a short
     *       orthogonal "drop" that returns the path to the face line.</li>
     * </ol>
     *
     * <p>Because the terminal BP itself is unchanged, the
     * {@link TerminalAnchoring#preservesEndpoints} predicate continues to
     * hold post-reconciliation. Existing rollback safety semantics in
     * {@code applyOffsets} are entirely preserved — this method ADDS a new
     * resolution path without modifying the existing one.
     *
     * @param connectionIds     parallel array of connection identifiers
     * @param bendpointLists    mutable bendpoint lists per connection (modified in place)
     * @param sourceCenters     source-element center [x,y] per connection
     * @param targetCenters     target-element center [x,y] per connection
     * @param allObstacles      obstacles for collision checks
     * @param anchoringContexts per-connection-index anchoring context;
     *                          {@code null}/empty short-circuits (no-op)
     * @return number of coincident pairs reconciled by interior-BP insertion
     */
    public int applyTerminalAnchoredReconciliation(
            List<String> connectionIds,
            List<List<AbsoluteBendpointDto>> bendpointLists,
            List<int[]> sourceCenters,
            List<int[]> targetCenters,
            List<RoutingRect> allObstacles,
            Map<Integer, AnchoringContext> anchoringContexts) {
        if (anchoringContexts == null || anchoringContexts.isEmpty()) {
            return 0;
        }

        // Re-detect coincident pairs on the post-applyOffsets path state.
        List<CoincidentPair> pairs = detect(
                connectionIds, bendpointLists, sourceCenters, targetCenters);
        if (pairs.isEmpty()) {
            return 0;
        }

        // Group into corridors using the same tolerance-aware key as applyOffsets.
        Map<String, List<PathOrderer.Segment>> corridorGroups = new LinkedHashMap<>();
        Set<String> seenSegments = new HashSet<>();
        for (CoincidentPair pair : pairs) {
            addToCorridorGroup(corridorGroups, seenSegments, pair.segA());
            addToCorridorGroup(corridorGroups, seenSegments, pair.segB());
        }

        int resolvedCount = 0;
        for (Map.Entry<String, List<PathOrderer.Segment>> entry : corridorGroups.entrySet()) {
            List<PathOrderer.Segment> segments = entry.getValue();
            if (segments.size() < 2) {
                continue;
            }

            // Filter to terminal-touching segments only — those are the
            // ones where applyOffsets's rollback fires.
            List<PathOrderer.Segment> terminalAnchored = new ArrayList<>();
            for (PathOrderer.Segment seg : segments) {
                if (touchesAnchoredTerminal(seg, bendpointLists, anchoringContexts)) {
                    terminalAnchored.add(seg);
                }
            }
            if (terminalAnchored.size() < 2) {
                continue;
            }

            boolean horizontal = terminalAnchored.get(0).horizontal();
            // Stagger pos i ≥ 1 by MIN_SEPARATION * i px; leave i==0 at original.
            for (int i = 1; i < terminalAnchored.size(); i++) {
                PathOrderer.Segment seg = terminalAnchored.get(i);
                int magnitude = MIN_SEPARATION * i;
                // Choose the sign direction that makes the drop segment continue
                // in the same direction as the implicit terminal segment (avoids
                // a "drop-then-reverse-back" zigzag at the terminal end). For
                // source-terminal cases the drop is perpendicular to the path
                // direction, so either sign is zigzag-free; default to +1.
                int preferredSign = preferredReconcileSign(
                        seg, horizontal, bendpointLists, anchoringContexts);
                int firstDelta = magnitude * preferredSign;
                boolean reconciled =
                        tryReconcileWithInsertion(seg, horizontal, firstDelta,
                                bendpointLists, allObstacles, anchoringContexts)
                        || tryReconcileWithInsertion(seg, horizontal, -firstDelta,
                                bendpointLists, allObstacles, anchoringContexts);
                if (reconciled) {
                    resolvedCount++;
                }
            }
        }

        if (resolvedCount > 0) {
            logger.info("Applied {} terminal-anchored reconciliation insertions",
                    resolvedCount);
        }
        return resolvedCount;
    }

    /**
     * Returns the preferred sign (+1 or -1) for the perpendicular shift delta,
     * chosen so the inserted drop segment continues in the same direction as
     * the implicit terminal segment (source.center → bp[0] for source-terminal
     * cases, or bp[last] → target.center for target-terminal cases) —
     * avoiding the "drop then reverse back" zigzag pattern that the assessor's
     * zigzag detector flags.
     *
     * <p>The unified formula {@code delta_sign = sign(terminalBp - anchor.center)}
     * on the perpendicular axis works for both ends: at the source end the
     * drop direction (bp[0] → dropBp) must align with the incoming direction
     * (source → bp[0]); at the target end the drop direction (dropBp → bp[last])
     * must align with the outgoing direction (bp[last] → target.center). Both
     * algebraically reduce to the same expression.
     *
     * <p>Falls back to +1 when no usable anchor center is available.
     */
    private static int preferredReconcileSign(
            PathOrderer.Segment seg, boolean horizontal,
            List<List<AbsoluteBendpointDto>> bendpointLists,
            Map<Integer, AnchoringContext> anchoringContexts) {
        int connIdx = seg.connectionIndex();
        List<AbsoluteBendpointDto> path = bendpointLists.get(connIdx);
        int bpIdx1 = seg.segmentIndex();
        int bpIdx2 = bpIdx1 + 1;
        if (bpIdx1 < 0 || bpIdx2 >= path.size()) {
            return 1;
        }
        boolean bp1IsTerminal = (bpIdx1 == 0);
        boolean bp2IsTerminal = (bpIdx2 == path.size() - 1);
        AnchoringContext ctx = anchoringContexts.get(connIdx);
        if (ctx == null) {
            return 1;
        }

        AbsoluteBendpointDto terminalBp;
        int[] anchorCenter;
        if (bp1IsTerminal) {
            terminalBp = path.get(bpIdx1);
            anchorCenter = ctx.sourceCenter();
        } else if (bp2IsTerminal) {
            terminalBp = path.get(bpIdx2);
            anchorCenter = ctx.targetCenter();
        } else {
            // Should never happen — touchesAnchoredTerminal already filtered.
            return 1;
        }
        if (anchorCenter == null) {
            // Unified algebraic argument requires a usable anchor center;
            // without it the +1 fallback can produce a zigzag at the terminal
            // (the very pattern this heuristic was added to prevent). Log so
            // the silent fall-through is observable rather than hidden.
            logger.warn("preferredReconcileSign: null anchorCenter for conn[{}] "
                    + "seg[{}] (bp1IsTerminal={}); falling back to +1 — drop "
                    + "direction may produce a zigzag",
                    connIdx, seg.segmentIndex(), bp1IsTerminal);
            return 1;
        }

        int diff;
        if (horizontal) {
            diff = terminalBp.y() - anchorCenter[1];
        } else {
            diff = terminalBp.x() - anchorCenter[0];
        }
        if (diff == 0) {
            return 1;
        }
        return diff > 0 ? 1 : -1;
    }

    /**
     * True iff the segment touches a terminal BP (path[0] or path[last]) AND the
     * connection has an anchoring context — i.e., this is a candidate for
     * Approach-3 reconciliation (rollback-prone in {@link #applyOffsets}).
     */
    private static boolean touchesAnchoredTerminal(
            PathOrderer.Segment seg, List<List<AbsoluteBendpointDto>> bendpointLists,
            Map<Integer, AnchoringContext> anchoringContexts) {
        int connIdx = seg.connectionIndex();
        if (anchoringContexts.get(connIdx) == null) {
            return false;
        }
        List<AbsoluteBendpointDto> path = bendpointLists.get(connIdx);
        int bpIdx1 = seg.segmentIndex();
        int bpIdx2 = bpIdx1 + 1;
        if (bpIdx1 < 0 || bpIdx2 >= path.size()) {
            return false;
        }
        return bpIdx1 == 0 || bpIdx2 == path.size() - 1;
    }

    /**
     * Reconciles a terminal-anchored coincident segment by shifting the
     * interior endpoint perpendicular and inserting a new BP that drops back
     * to the face line. Returns {@code false} (path unchanged) if the
     * inserted path collides with any obstacle, or if both endpoints are
     * terminals (2-BP path).
     *
     * <p><strong>This is the one terminal-anchoring check in the family that is
     * still a whole-path conjunction, and that is deliberate.</strong> The
     * guard below captures {@link TerminalAnchoring#preservesEndpoints} — which
     * answers for both ends at once — before and after the insertion, where the
     * five wrap sites instead decide each end on its own via
     * {@link TerminalAnchoringRollbackPolicy}.
     *
     * <p>The conjunction is sound here <em>only</em> because this method never
     * moves a terminal bendpoint. It shifts the interior endpoint and inserts a
     * drop bendpoint beside the terminal, leaving the terminal itself
     * byte-identical. The verdict must therefore be invariant across the
     * insertion, and a change of verdict means a logic bug — a stale segment
     * index, or an insertion that reached further than intended. The check is
     * <em>defensive</em>, not a policy: it is why the rollback logs at warning
     * level. A per-end split would buy nothing, because neither end can move.
     *
     * <p>The distinction matters because the wrap sites' conjunction was
     * removed for the opposite reason: there a mutation <em>can</em> move one
     * terminal, so conjoining the ends let the end it never touched decide the
     * outcome. If this method is ever changed to relocate a terminal, this
     * guard stops being defensive and must convert to the shared policy.
     */
    private boolean tryReconcileWithInsertion(
            PathOrderer.Segment seg, boolean horizontal, int delta,
            List<List<AbsoluteBendpointDto>> bendpointLists,
            List<RoutingRect> allObstacles,
            Map<Integer, AnchoringContext> anchoringContexts) {
        int connIdx = seg.connectionIndex();
        List<AbsoluteBendpointDto> path = bendpointLists.get(connIdx);
        int bpIdx1 = seg.segmentIndex();
        int bpIdx2 = bpIdx1 + 1;
        if (bpIdx1 < 0 || bpIdx2 >= path.size()) {
            return false;
        }

        boolean bp1IsTerminal = (bpIdx1 == 0);
        boolean bp2IsTerminal = (bpIdx2 == path.size() - 1);
        if (bp1IsTerminal && bp2IsTerminal) {
            // 2-BP path — no interior to insert into.
            return false;
        }
        if (!bp1IsTerminal && !bp2IsTerminal) {
            // Stored seg.segmentIndex() may be stale: a prior corridor group's
            // reconciliation could have grown this connection's path via
            // insertion, shifting bp indices. The seg was classified as
            // terminal-anchored at touchesAnchoredTerminal time; if it is no
            // longer terminal-anchored now, skip — operating on stale indices
            // would silently treat an interior BP as a terminal and corrupt
            // the path. (Latent at V4 oracle's topology; defensive guard.)
            return false;
        }

        AbsoluteBendpointDto bp1 = path.get(bpIdx1);
        AbsoluteBendpointDto bp2 = path.get(bpIdx2);
        AbsoluteBendpointDto terminalBp = bp1IsTerminal ? bp1 : bp2;
        AbsoluteBendpointDto interiorBp = bp1IsTerminal ? bp2 : bp1;

        AbsoluteBendpointDto newInterior;
        AbsoluteBendpointDto dropBp;
        if (horizontal) {
            // Horizontal segment shifts in y.
            newInterior = new AbsoluteBendpointDto(interiorBp.x(),
                    interiorBp.y() + delta);
            dropBp = new AbsoluteBendpointDto(terminalBp.x(),
                    terminalBp.y() + delta);
        } else {
            // Vertical segment shifts in x.
            newInterior = new AbsoluteBendpointDto(interiorBp.x() + delta,
                    interiorBp.y());
            dropBp = new AbsoluteBendpointDto(terminalBp.x() + delta,
                    terminalBp.y());
        }

        // Collision-check the moved corridor and the new drop segment.
        if (segmentOverlapsAnyObstacle(newInterior.x(), newInterior.y(),
                dropBp.x(), dropBp.y(), allObstacles)) {
            return false;
        }
        if (segmentOverlapsAnyObstacle(dropBp.x(), dropBp.y(),
                terminalBp.x(), terminalBp.y(), allObstacles)) {
            return false;
        }

        // Capture the predicate result PRE-mutation. The defensive check
        // below compares pre vs post: rollback fires only if our insertion
        // FLIPPED the predicate from true to false. Without this comparison,
        // paths whose predicate was already failing pre-mutation (e.g.,
        // bp[last] off the target face line because the route was already
        // simplified to a corridor-elbow terminal) would always trigger
        // rollback, even though our insertion preserves the terminal BP
        // verbatim. Since we never touch the terminal BP, the predicate
        // result must be invariant — so a flip indicates a logic bug.
        AnchoringContext ctx = anchoringContexts.get(connIdx);
        boolean preservesBefore = (ctx == null) || TerminalAnchoring.preservesEndpoints(
                ctx.sourceAnchoring(), ctx.connection().source(), ctx.sourceCenter(),
                ctx.targetAnchoring(), ctx.connection().target(), ctx.targetCenter(),
                path);

        // Apply: replace interior endpoint with newInterior; insert dropBp
        // adjacent to the terminal. Insert position depends on which end is
        // the terminal.
        if (bp1IsTerminal) {
            // path: [bp[0]=terminal, bp[1]=interior, ...]
            // After: [bp[0]=terminal, dropBp, newInterior, ...]
            path.set(bpIdx2, newInterior);
            path.add(bpIdx1 + 1, dropBp);
        } else {
            // bp2IsTerminal — path: [..., bp[bpIdx1]=interior, bp[bpIdx2]=terminal]
            // After: [..., newInterior, dropBp, terminal]
            path.set(bpIdx1, newInterior);
            path.add(bpIdx2, dropBp);
        }

        // Defensive flip-check: terminalBp itself was untouched, so the
        // predicate result MUST be invariant. If pre was true and post is
        // false, our insertion has somehow broken the contract — roll back.
        boolean preservesAfter = (ctx == null) || TerminalAnchoring.preservesEndpoints(
                ctx.sourceAnchoring(), ctx.connection().source(), ctx.sourceCenter(),
                ctx.targetAnchoring(), ctx.connection().target(), ctx.targetCenter(),
                path);
        if (preservesBefore && !preservesAfter) {
            if (bp1IsTerminal) {
                path.remove(bpIdx1 + 1);
                path.set(bpIdx2, interiorBp);
            } else {
                path.remove(bpIdx2);
                path.set(bpIdx1, interiorBp);
            }
            logger.warn("applyTerminalAnchoredReconciliation: defensive rollback "
                    + "fired conn[{}] seg[{}] (unexpected — terminal BP untouched)",
                    connIdx, seg.segmentIndex());
            return false;
        }

        logger.debug("Reconciled coincident segment via insertion: conn[{}] seg[{}] "
                + "delta={}px {}",
                connIdx, seg.segmentIndex(), delta,
                horizontal ? "vertically" : "horizontally");
        return true;
    }

    static boolean segmentOverlapsAnyObstacle(
            int x1, int y1, int x2, int y2, List<RoutingRect> obstacles) {
        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2);
        int maxY = Math.max(y1, y2);
        int segW = Math.max(1, maxX - minX);
        int segH = Math.max(1, maxY - minY);
        for (RoutingRect obs : obstacles) {
            if (minX < obs.x() + obs.width() && minX + segW > obs.x()
                    && minY < obs.y() + obs.height() && minY + segH > obs.y()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Counts coincident segments for layout quality assessment.
     * Operates on AssessmentConnection pathPoints (double[] format).
     *
     * @param connections assessment connections with full path points
     * @return number of coincident segment pairs detected
     */
    public int countCoincidentSegments(
            List<? extends CoincidentAssessable> connections) {
        if (connections.size() < 2) {
            return 0;
        }

        // Extract segments from all connections
        List<PathOrderer.Segment> allSegments = new ArrayList<>();
        for (int connIdx = 0; connIdx < connections.size(); connIdx++) {
            CoincidentAssessable conn = connections.get(connIdx);
            List<double[]> points = conn.pathPoints();
            if (points.size() < 3) {
                continue; // Need at least source + 1 BP + target for intermediate segments
            }

            // Extract intermediate segments (skip first and last — source/target terminals)
            for (int i = 1; i < points.size() - 2; i++) {
                double[] p1 = points.get(i);
                double[] p2 = points.get(i + 1);
                int x1 = (int) Math.round(p1[0]);
                int y1 = (int) Math.round(p1[1]);
                int x2 = (int) Math.round(p2[0]);
                int y2 = (int) Math.round(p2[1]);

                boolean horizontal = (y1 == y2);
                boolean vertical = (x1 == x2);
                if (horizontal || vertical) {
                    allSegments.add(new PathOrderer.Segment(
                            connIdx, i - 1, x1, y1, x2, y2, horizontal));
                }
            }
        }

        if (allSegments.isEmpty()) {
            return 0;
        }

        Map<String, List<PathOrderer.Segment>> groups = pathOrderer.groupSegments(allSegments);

        int count = 0;
        List<CoincidentPair> pairs = new ArrayList<>();
        for (List<PathOrderer.Segment> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            int before = pairs.size();
            detectCoincidentInGroup(group, pairs);
            count += (pairs.size() - before);
        }

        return count;
    }

    /** Result of coincident segment detection with optional violator connection indices. */
    public record CoincidentSegmentResult(int count, Set<Integer> violatorConnectionIndices) {}

    /**
     * Counts coincident segments and optionally collects the connection indices involved.
     *
     * @param connections assessment connections with full path points
     * @param collectViolatorIndices if true, collects connection indices involved in coincident segments
     * @return count and optional violator connection indices
     */
    public CoincidentSegmentResult detectCoincidentSegments(
            List<? extends CoincidentAssessable> connections, boolean collectViolatorIndices) {
        if (connections.size() < 2) {
            return new CoincidentSegmentResult(0, Set.of());
        }

        List<PathOrderer.Segment> allSegments = new ArrayList<>();
        for (int connIdx = 0; connIdx < connections.size(); connIdx++) {
            CoincidentAssessable conn = connections.get(connIdx);
            List<double[]> points = conn.pathPoints();
            if (points.size() < 3) {
                continue;
            }
            for (int i = 1; i < points.size() - 2; i++) {
                double[] p1 = points.get(i);
                double[] p2 = points.get(i + 1);
                int x1 = (int) Math.round(p1[0]);
                int y1 = (int) Math.round(p1[1]);
                int x2 = (int) Math.round(p2[0]);
                int y2 = (int) Math.round(p2[1]);
                boolean horizontal = (y1 == y2);
                boolean vertical = (x1 == x2);
                if (horizontal || vertical) {
                    allSegments.add(new PathOrderer.Segment(
                            connIdx, i - 1, x1, y1, x2, y2, horizontal));
                }
            }
        }

        if (allSegments.isEmpty()) {
            return new CoincidentSegmentResult(0, Set.of());
        }

        Map<String, List<PathOrderer.Segment>> groups = pathOrderer.groupSegments(allSegments);

        int count = 0;
        List<CoincidentPair> pairs = new ArrayList<>();
        Set<Integer> violatorIndices = collectViolatorIndices ? new java.util.HashSet<>() : Set.of();
        for (List<PathOrderer.Segment> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            int before = pairs.size();
            detectCoincidentInGroup(group, pairs);
            if (collectViolatorIndices) {
                for (int i = before; i < pairs.size(); i++) {
                    CoincidentPair pair = pairs.get(i);
                    violatorIndices.add(pair.segA().connectionIndex());
                    violatorIndices.add(pair.segB().connectionIndex());
                }
            }
            count += (pairs.size() - before);
        }

        return new CoincidentSegmentResult(count, violatorIndices);
    }

    /**
     * Diagnostic variant of {@link #detectCoincidentSegments}: returns the raw
     * {@link CoincidentPair} list so external tooling in this package (e.g.
     * {@link CoincidentSegmentDiagnostic}) can inspect per-segment geometry.
     * Package-private — not for production routing use outside this package.
     */
    List<CoincidentPair> detectPairs(List<? extends CoincidentAssessable> connections) {
        if (connections.size() < 2) {
            return List.of();
        }

        List<PathOrderer.Segment> allSegments = new ArrayList<>();
        for (int connIdx = 0; connIdx < connections.size(); connIdx++) {
            CoincidentAssessable conn = connections.get(connIdx);
            List<double[]> points = conn.pathPoints();
            if (points.size() < 3) {
                continue;
            }
            for (int i = 1; i < points.size() - 2; i++) {
                double[] p1 = points.get(i);
                double[] p2 = points.get(i + 1);
                int x1 = (int) Math.round(p1[0]);
                int y1 = (int) Math.round(p1[1]);
                int x2 = (int) Math.round(p2[0]);
                int y2 = (int) Math.round(p2[1]);
                boolean horizontal = (y1 == y2);
                boolean vertical = (x1 == x2);
                if (horizontal || vertical) {
                    allSegments.add(new PathOrderer.Segment(
                            connIdx, i - 1, x1, y1, x2, y2, horizontal));
                }
            }
        }

        if (allSegments.isEmpty()) {
            return List.of();
        }

        Map<String, List<PathOrderer.Segment>> groups = pathOrderer.groupSegments(allSegments);

        List<CoincidentPair> pairs = new ArrayList<>();
        for (List<PathOrderer.Segment> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            detectCoincidentInGroup(group, pairs);
        }
        return pairs;
    }

    /**
     * Interface for objects that provide path points for coincident assessment.
     */
    public interface CoincidentAssessable {
        List<double[]> pathPoints();
    }
}
