package net.vheerden.archi.mcp.model.routing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
     * Applies perpendicular offsets to coincident segments to make them visually
     * distinguishable. For each coincident pair, shifts one segment by the offset
     * delta. Checks for obstacle collisions and skips offset if blocked.
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

        // Track which connection+segment pairs have been offset, and per-corridor
        // ordinal counters so multi-way coincidence (3+ connections) gets stacked offsets
        Set<String> alreadyOffset = new HashSet<>();
        Map<String, Integer> corridorOrdinals = new HashMap<>();
        int offsetCount = 0;

        for (CoincidentPair pair : coincidentPairs) {
            PathOrderer.Segment segB = pair.segB();
            String key = segB.connectionIndex() + ":" + segB.segmentIndex();

            if (alreadyOffset.contains(key)) {
                continue;
            }

            boolean horizontal = segB.horizontal();
            List<AbsoluteBendpointDto> path = bendpointLists.get(segB.connectionIndex());
            int bpIdx1 = segB.segmentIndex();
            int bpIdx2 = segB.segmentIndex() + 1;

            if (bpIdx1 < 0 || bpIdx2 >= path.size()) {
                continue;
            }

            // Stacking ordinal: increment per corridor so 3+ connections get 1*delta, 2*delta, etc.
            String corridorKey = horizontal + ":" + segB.sharedCoordinate();
            int ordinal = corridorOrdinals.merge(corridorKey, 1, Integer::sum);

            int delta = offsetDelta * ordinal;
            boolean applied = tryOffset(path, bpIdx1, bpIdx2, horizontal, delta, allObstacles);
            if (!applied) {
                applied = tryOffset(path, bpIdx1, bpIdx2, horizontal, -delta, allObstacles);
            }

            if (applied) {
                alreadyOffset.add(key);
                offsetCount++;
                logger.debug("Offset coincident segment: conn[{}] seg[{}] by {}px (ordinal {}) {}",
                        segB.connectionIndex(), segB.segmentIndex(), delta, ordinal,
                        horizontal ? "vertically" : "horizontally");
            } else {
                // Roll back ordinal if offset wasn't applied
                corridorOrdinals.merge(corridorKey, -1, Integer::sum);
                logger.debug("Skipped offset for conn[{}] seg[{}] — both directions blocked",
                        segB.connectionIndex(), segB.segmentIndex());
            }
        }

        if (offsetCount > 0) {
            logger.info("Applied {} coincident segment offsets", offsetCount);
        }
        return offsetCount;
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
