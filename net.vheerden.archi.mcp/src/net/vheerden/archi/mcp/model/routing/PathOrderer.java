package net.vheerden.archi.mcp.model.routing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Path orderer for crossing minimization in shared corridors (Story 10-7a).
 * Pure-geometry class — no EMF/SWT dependencies.
 *
 * <p>After all connections in a view are individually routed, this class
 * identifies segments that share the same corridor (horizontal or vertical)
 * and detects unnecessary crossings by comparing perpendicular endpoint
 * positions against segment ordering within each corridor.</p>
 *
 * <p>10-7a computes corridor analysis (segment extraction, grouping, crossing
 * detection). Physical reordering of segments requires spatial separation
 * (nudging), which is story 10-7b's responsibility. Bendpoint lists are
 * returned unmodified.</p>
 *
 * <p>Only analyses connections within shared corridors. Topological crossings
 * (connections that must cross due to source/target topology) are excluded
 * from the crossing detection results.</p>
 */
public class PathOrderer {

    private static final Logger logger = LoggerFactory.getLogger(PathOrderer.class);

    static final int DEFAULT_COORDINATE_TOLERANCE = 2;

    private final int coordinateTolerance;

    public PathOrderer() {
        this(DEFAULT_COORDINATE_TOLERANCE);
    }

    PathOrderer(int coordinateTolerance) {
        this.coordinateTolerance = coordinateTolerance;
    }

    /**
     * A segment extracted from a routed path.
     *
     * @param connectionIndex index into the connections list
     * @param segmentIndex    index of this segment within the connection's full path
     * @param x1              start x
     * @param y1              start y
     * @param x2              end x
     * @param y2              end y
     * @param horizontal      true if horizontal segment, false if vertical
     */
    record Segment(int connectionIndex, int segmentIndex,
                   int x1, int y1, int x2, int y2, boolean horizontal) {

        /** The shared coordinate (y for horizontal, x for vertical). */
        int sharedCoordinate() {
            return horizontal ? y1 : x1;
        }

        /**
         * Midpoint along the parallel axis of this segment.
         * For horizontal segments: x-midpoint. For vertical segments: y-midpoint.
         * Used to determine segment ordering within a corridor.
         */
        int parallelMidpoint() {
            if (horizontal) {
                return (x1 + x2) / 2;
            } else {
                return (y1 + y2) / 2;
            }
        }
    }

    /**
     * Records a detected unnecessary crossing between two segments in a shared corridor.
     *
     * @param connectionIndexA first connection's index
     * @param segmentIndexA    first connection's segment index
     * @param connectionIndexB second connection's index
     * @param segmentIndexB    second connection's segment index
     */
    record CrossingInfo(int connectionIndexA, int segmentIndexA,
                        int connectionIndexB, int segmentIndexB) {}

    /**
     * Analyses paths for crossings in shared corridors and returns unmodified bendpoint lists.
     *
     * <p>Performs corridor analysis (segment extraction, grouping, crossing detection)
     * and logs the results. Bendpoint lists are returned unmodified because physically
     * reordering collinear segments requires spatial separation (nudging), which is
     * story 10-7b's responsibility.</p>
     *
     * @param connectionIds  list of connection identifiers (parallel with bendpointLists)
     * @param bendpointLists list of bendpoint lists (one per connection), in absolute coordinates
     * @param sourceCenters  source center points [x, y] per connection (parallel with bendpointLists)
     * @param targetCenters  target center points [x, y] per connection (parallel with bendpointLists)
     * @return unmodified copies of the input bendpoint lists
     */
    public List<List<AbsoluteBendpointDto>> orderPaths(
            List<String> connectionIds,
            List<List<AbsoluteBendpointDto>> bendpointLists,
            List<int[]> sourceCenters,
            List<int[]> targetCenters) {

        logger.debug("Path ordering: {} connections", bendpointLists.size());

        if (bendpointLists.size() <= 1) {
            return new ArrayList<>(bendpointLists);
        }

        // Detect crossings (physical reordering requires nudging from 10-7b)
        List<CrossingInfo> crossings = detectCrossings(
                connectionIds, bendpointLists, sourceCenters, targetCenters);

        logger.debug("Path ordering analysis: {} crossings detected across {} connections",
                crossings.size(), bendpointLists.size());

        // Return unmodified copies — physical reordering applied by 10-7b nudging
        List<List<AbsoluteBendpointDto>> result = new ArrayList<>();
        for (List<AbsoluteBendpointDto> bps : bendpointLists) {
            result.add(new ArrayList<>(bps));
        }
        return result;
    }

    /**
     * Detects unnecessary crossings in shared corridors across all connections.
     * Package-visible for testing and for consumption by 10-7b nudging.
     *
     * @return list of detected crossings (each crossing identifies two segments from different connections)
     */
    List<CrossingInfo> detectCrossings(
            List<String> connectionIds,
            List<List<AbsoluteBendpointDto>> bendpointLists,
            List<int[]> sourceCenters,
            List<int[]> targetCenters) {

        // 1. Extract segments from all connections
        List<Segment> allSegments = new ArrayList<>();
        for (int i = 0; i < bendpointLists.size(); i++) {
            List<AbsoluteBendpointDto> bendpoints = bendpointLists.get(i);
            if (bendpoints.size() < 2) {
                continue;
            }
            extractSegments(i, bendpoints, sourceCenters.get(i), targetCenters.get(i), allSegments);
        }

        if (allSegments.isEmpty()) {
            return List.of();
        }

        // 2. Group segments by orientation + shared coordinate (within tolerance)
        Map<String, List<Segment>> groups = groupSegments(allSegments);

        // 3. For each group with >1 segment from different connections, detect crossings
        List<CrossingInfo> crossings = new ArrayList<>();
        for (List<Segment> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            if (!hasMultipleConnections(group)) {
                continue;
            }
            detectGroupCrossings(group, sourceCenters, targetCenters, crossings);
        }

        return crossings;
    }

    /**
     * Checks if a segment group contains segments from more than one connection.
     */
    private boolean hasMultipleConnections(List<Segment> group) {
        int firstConn = group.get(0).connectionIndex();
        for (int i = 1; i < group.size(); i++) {
            if (group.get(i).connectionIndex() != firstConn) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extracts horizontal and vertical segments from a connection's full path.
     * The full path is: sourceCenter → bendpoint[0] → ... → bendpoint[n-1] → targetCenter.
     * Only intermediate segments (between bendpoints) are extracted, as those are
     * the segments that can be reordered.
     */
    void extractSegments(int connectionIndex, List<AbsoluteBendpointDto> bendpoints,
                         int[] sourceCenter, int[] targetCenter, List<Segment> out) {
        // Build full path points
        List<int[]> points = new ArrayList<>();
        points.add(sourceCenter);
        for (AbsoluteBendpointDto bp : bendpoints) {
            points.add(new int[]{bp.x(), bp.y()});
        }
        points.add(targetCenter);

        // Extract segments between consecutive bendpoints only (indices 1..n in points list)
        // We skip the first segment (source→bp[0]) and last segment (bp[n-1]→target)
        // because reordering those would change which connection enters/exits from where.
        for (int i = 1; i < points.size() - 2; i++) {
            int[] p1 = points.get(i);
            int[] p2 = points.get(i + 1);

            boolean horizontal = (p1[1] == p2[1]);
            boolean vertical = (p1[0] == p2[0]);

            if (horizontal || vertical) {
                // segmentIndex maps to the bendpoint index (i-1 in the bendpoints list)
                out.add(new Segment(connectionIndex, i - 1, p1[0], p1[1], p2[0], p2[1], horizontal));
            }
            // Diagonal segments (should not occur with orthogonal routing) are skipped
        }
    }

    /**
     * Groups segments by orientation and shared coordinate (within tolerance).
     * Returns groups keyed by "H:coord" or "V:coord" where coord is the
     * representative (lowest) shared coordinate in the group.
     */
    Map<String, List<Segment>> groupSegments(List<Segment> segments) {
        // Sort segments by orientation then shared coordinate for efficient grouping
        List<Segment> sorted = new ArrayList<>(segments);
        sorted.sort((a, b) -> {
            int orientCmp = Boolean.compare(a.horizontal(), b.horizontal());
            if (orientCmp != 0) return orientCmp;
            return Integer.compare(a.sharedCoordinate(), b.sharedCoordinate());
        });

        Map<String, List<Segment>> groups = new LinkedHashMap<>();
        for (Segment seg : sorted) {
            String key = findOrCreateGroupKey(groups, seg);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(seg);
        }

        return groups;
    }

    /**
     * Finds an existing group key for this segment (within tolerance) or creates a new one.
     */
    private String findOrCreateGroupKey(Map<String, List<Segment>> groups, Segment seg) {
        String prefix = seg.horizontal() ? "H:" : "V:";
        int coord = seg.sharedCoordinate();

        for (String key : groups.keySet()) {
            if (!key.startsWith(prefix)) continue;
            int groupCoord = Integer.parseInt(key.substring(2));
            if (Math.abs(coord - groupCoord) <= coordinateTolerance) {
                return key;
            }
        }

        return prefix + coord;
    }

    /**
     * Detects crossings within a single corridor group by comparing pairs of segments
     * from different connections.
     */
    void detectGroupCrossings(List<Segment> group,
                              List<int[]> sourceCenters, List<int[]> targetCenters,
                              List<CrossingInfo> out) {
        for (int i = 0; i < group.size(); i++) {
            for (int j = i + 1; j < group.size(); j++) {
                Segment segA = group.get(i);
                Segment segB = group.get(j);

                if (segA.connectionIndex() == segB.connectionIndex()) {
                    continue;
                }

                if (segmentsCrossUnnecessarily(segA, segB, sourceCenters, targetCenters)) {
                    out.add(new CrossingInfo(
                            segA.connectionIndex(), segA.segmentIndex(),
                            segB.connectionIndex(), segB.segmentIndex()));
                    logger.debug("Crossing detected: conn[{}] seg[{}] x conn[{}] seg[{}]",
                            segA.connectionIndex(), segA.segmentIndex(),
                            segB.connectionIndex(), segB.segmentIndex());
                }
            }
        }
    }

    /**
     * Determines if two segments in the same corridor cross unnecessarily.
     * An unnecessary crossing occurs when the perpendicular ordering of
     * connection endpoints disagrees with the parallel ordering of their
     * segments within the corridor.
     *
     * <p>For horizontal corridors: compares the y-position ordering of
     * connection endpoints (perpendicular) against the x-midpoint ordering
     * of segments (parallel). If the connection with higher endpoints has
     * a segment positioned further left, the paths cross.</p>
     *
     * <p>For vertical corridors: compares the x-position ordering of
     * connection endpoints (perpendicular) against the y-midpoint ordering
     * of segments (parallel).</p>
     */
    boolean segmentsCrossUnnecessarily(Segment segA, Segment segB,
                                       List<int[]> sourceCenters, List<int[]> targetCenters) {
        int connA = segA.connectionIndex();
        int connB = segB.connectionIndex();

        double endpointOrderA, endpointOrderB;
        if (segA.horizontal()) {
            // Horizontal corridor: perpendicular order by y-position of connection endpoints
            endpointOrderA = (sourceCenters.get(connA)[1] + targetCenters.get(connA)[1]) / 2.0;
            endpointOrderB = (sourceCenters.get(connB)[1] + targetCenters.get(connB)[1]) / 2.0;
        } else {
            // Vertical corridor: perpendicular order by x-position of connection endpoints
            endpointOrderA = (sourceCenters.get(connA)[0] + targetCenters.get(connA)[0]) / 2.0;
            endpointOrderB = (sourceCenters.get(connB)[0] + targetCenters.get(connB)[0]) / 2.0;
        }

        // Parallel ordering of segments within the corridor
        double segOrderA = segA.parallelMidpoint();
        double segOrderB = segB.parallelMidpoint();

        // Only flag as crossing if endpoints differ significantly
        if (Math.abs(endpointOrderA - endpointOrderB) < coordinateTolerance) {
            return false;
        }

        // If perpendicular endpoint order and parallel segment order disagree, they cross
        boolean endpointAFirst = endpointOrderA < endpointOrderB;
        boolean segmentAFirst = segOrderA < segOrderB;

        return endpointAFirst != segmentAFirst;
    }
}
