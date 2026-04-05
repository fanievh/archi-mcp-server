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
 * Detects and offsets coincident connection segments (Story 11-23).
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
     * @param obstacles        all element rectangles on the view
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
     * Applies perpendicular offsets to coincident segments to make them visually
     * distinguishable. Uses corridor-group-first processing with proportional
     * spacing when sufficient gap exists between obstacles.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>First pass: Collect all unique segments per corridor using tolerance-aware grouping</li>
     *   <li>Second pass: For each corridor group, compute gap and proportional positions</li>
     *   <li>Third pass: Apply offsets with obstacle validation, falling back to fixed-delta per-segment</li>
     * </ol>
     *
     * @param coincidentPairs detected coincident pairs
     * @param bendpointLists  mutable bendpoint lists per connection (modified in place)
     * @param allObstacles    all element rectangles on the view
     * @return number of segments actually offset
     */
    public int applyOffsets(List<CoincidentPair> coincidentPairs,
                            List<List<AbsoluteBendpointDto>> bendpointLists,
                            List<RoutingRect> allObstacles) {
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
                        horizontal, bendpointLists, allObstacles, alreadyOffset);
                logger.debug("Proportional spacing for corridor {}: {} segments across gap [{}, {}]",
                        entry.getKey(), segments.size(), gap[0], gap[1]);
            } else {
                // Fixed-delta fallback: original stacking behavior
                offsetCount += applyFixedDeltaOffsets(segments, horizontal,
                        bendpointLists, allObstacles, alreadyOffset);
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
                                          Set<String> alreadyOffset) {
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
                                        Set<String> alreadyOffset) {
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

            int delta = offsetDelta * i;
            boolean applied = tryOffset(path, bpIdx1, bpIdx2, horizontal, delta, allObstacles);
            if (!applied) {
                applied = tryOffset(path, bpIdx1, bpIdx2, horizontal, -delta, allObstacles);
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
     * Counts coincident segments for layout quality assessment (AC3).
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

    /**
     * Interface for objects that provide path points for coincident assessment.
     */
    public interface CoincidentAssessable {
        List<double[]> pathPoints();
    }
}
