package net.vheerden.archi.mcp.model.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Edge nudger for parallel segment separation (Story 10-7b).
 * Pure-geometry class — no EMF/SWT dependencies.
 *
 * <p>After path ordering identifies shared corridors, this class offsets
 * parallel segments from different connections so they are visually
 * distinguishable. Segments are distributed evenly across the available
 * corridor width, centred on the original shared coordinate.</p>
 *
 * <p>Reuses {@link PathOrderer}'s segment extraction and grouping logic
 * to identify corridors, then computes perpendicular offsets bounded by
 * nearby obstacles.</p>
 */
public class EdgeNudger {

    private static final Logger logger = LoggerFactory.getLogger(EdgeNudger.class);

    static final int DEFAULT_MIN_SPACING = 8;
    static final int DEFAULT_MAX_SPACING = 30;
    static final int DEFAULT_CORRIDOR_SEARCH_RANGE = 500;
    static final int DEFAULT_OBSTACLE_MARGIN = 10;

    /** Corridor boundary information for nudging calculations. */
    record CorridorBounds(int lowerBound, int upperBound) {
        int width() { return upperBound - lowerBound; }
    }

    private final int minSpacing;
    private final int maxSpacing;
    private final int corridorSearchRange;
    private final int obstacleMargin;
    private final PathOrderer pathOrderer;

    public EdgeNudger() {
        this(DEFAULT_MIN_SPACING, DEFAULT_MAX_SPACING, DEFAULT_CORRIDOR_SEARCH_RANGE,
                DEFAULT_OBSTACLE_MARGIN, new PathOrderer());
    }

    EdgeNudger(PathOrderer pathOrderer) {
        this(DEFAULT_MIN_SPACING, DEFAULT_MAX_SPACING, DEFAULT_CORRIDOR_SEARCH_RANGE,
                DEFAULT_OBSTACLE_MARGIN, pathOrderer);
    }

    EdgeNudger(int minSpacing, int maxSpacing, int corridorSearchRange, PathOrderer pathOrderer) {
        this(minSpacing, maxSpacing, corridorSearchRange, DEFAULT_OBSTACLE_MARGIN, pathOrderer);
    }

    EdgeNudger(int minSpacing, int maxSpacing, int corridorSearchRange,
               int obstacleMargin, PathOrderer pathOrderer) {
        this.minSpacing = minSpacing;
        this.maxSpacing = maxSpacing;
        this.corridorSearchRange = corridorSearchRange;
        this.obstacleMargin = obstacleMargin;
        this.pathOrderer = pathOrderer;
    }

    /**
     * Nudges parallel segments apart for visual separation.
     *
     * @param connectionIds  connection identifiers (parallel with bendpointLists)
     * @param bendpointLists mutable bendpoint lists per connection (modified in place and returned)
     * @param sourceCenters  source center [x, y] per connection
     * @param targetCenters  target center [x, y] per connection
     * @param allObstacles   all element rectangles on the view (for corridor width computation)
     * @return nudged bendpoint lists (same list references, modified in place)
     */
    public List<List<AbsoluteBendpointDto>> nudgePaths(
            List<String> connectionIds,
            List<List<AbsoluteBendpointDto>> bendpointLists,
            List<int[]> sourceCenters,
            List<int[]> targetCenters,
            List<RoutingRect> allObstacles) {

        logger.debug("Edge nudging: {} connections, {} obstacles",
                bendpointLists.size(), allObstacles.size());

        if (bendpointLists.size() <= 1) {
            return bendpointLists;
        }

        // 1. Extract segments from all connections (reusing PathOrderer logic)
        List<PathOrderer.Segment> allSegments = new ArrayList<>();
        for (int i = 0; i < bendpointLists.size(); i++) {
            List<AbsoluteBendpointDto> bendpoints = bendpointLists.get(i);
            if (bendpoints.size() < 2) {
                continue;
            }
            pathOrderer.extractSegments(i, bendpoints, sourceCenters.get(i), targetCenters.get(i), allSegments);
        }

        if (allSegments.isEmpty()) {
            return bendpointLists;
        }

        // 2. Group segments by orientation + shared coordinate
        Map<String, List<PathOrderer.Segment>> groups = pathOrderer.groupSegments(allSegments);

        // 3. For each group with >1 segment from different connections, apply nudging
        int nudgedGroups = 0;
        for (Map.Entry<String, List<PathOrderer.Segment>> entry : groups.entrySet()) {
            List<PathOrderer.Segment> group = entry.getValue();
            if (group.size() < 2 || !hasMultipleConnections(group)) {
                continue;
            }

            boolean horizontal = group.get(0).horizontal();
            int sharedCoord = group.get(0).sharedCoordinate();

            // Compute corridor bounds from obstacles
            CorridorBounds bounds = computeCorridorBounds(
                    horizontal, sharedCoord, group, allObstacles);

            // Compute spacing and offsets, clamped to corridor bounds
            nudgeGroup(group, horizontal, sharedCoord, bounds,
                    bendpointLists, sourceCenters, targetCenters);
            nudgedGroups++;
        }

        logger.debug("Edge nudging complete: {} corridor groups nudged", nudgedGroups);
        return bendpointLists;
    }

    /**
     * Computes corridor boundaries for a segment group by finding nearest
     * obstacles on both sides perpendicular to the corridor.
     */
    CorridorBounds computeCorridorBounds(boolean horizontal, int sharedCoord,
                             List<PathOrderer.Segment> group, List<RoutingRect> obstacles) {
        // Determine the parallel range of the group (union of all segment extents)
        int parallelMin = Integer.MAX_VALUE;
        int parallelMax = Integer.MIN_VALUE;
        for (PathOrderer.Segment seg : group) {
            if (horizontal) {
                parallelMin = Math.min(parallelMin, Math.min(seg.x1(), seg.x2()));
                parallelMax = Math.max(parallelMax, Math.max(seg.x1(), seg.x2()));
            } else {
                parallelMin = Math.min(parallelMin, Math.min(seg.y1(), seg.y2()));
                parallelMax = Math.max(parallelMax, Math.max(seg.y1(), seg.y2()));
            }
        }

        // Find nearest obstacle edges on both sides
        int nearestBefore = sharedCoord - corridorSearchRange; // default: far away
        int nearestAfter = sharedCoord + corridorSearchRange;

        for (RoutingRect obs : obstacles) {
            if (horizontal) {
                // Horizontal corridor at y=sharedCoord: check obstacles whose x-range overlaps
                int obsLeft = obs.x();
                int obsRight = obs.x() + obs.width();
                if (obsRight <= parallelMin || obsLeft >= parallelMax) {
                    continue; // No parallel overlap — obstacle outside segment range
                }
                int obsTop = obs.y();
                int obsBottom = obs.y() + obs.height();
                if (obsBottom <= sharedCoord && obsBottom > nearestBefore) {
                    nearestBefore = obsBottom; // obstacle entirely above
                } else if (obsTop >= sharedCoord && obsTop < nearestAfter) {
                    nearestAfter = obsTop; // obstacle entirely below
                } else if (obsTop < sharedCoord && obsBottom > sharedCoord) {
                    // Obstacle straddles the shared coordinate — creates negative-width corridor.
                    // nudgeGroup() detects this and skips nudging (segments stay at sharedCoord).
                    nearestBefore = Math.max(nearestBefore, obsBottom);
                    nearestAfter = Math.min(nearestAfter, obsTop);
                }
            } else {
                // Vertical corridor at x=sharedCoord: check obstacles whose y-range overlaps
                int obsTop = obs.y();
                int obsBottom = obs.y() + obs.height();
                if (obsBottom <= parallelMin || obsTop >= parallelMax) {
                    continue; // No parallel overlap
                }
                int obsLeft = obs.x();
                int obsRight = obs.x() + obs.width();
                if (obsRight <= sharedCoord && obsRight > nearestBefore) {
                    nearestBefore = obsRight; // obstacle entirely to the left
                } else if (obsLeft >= sharedCoord && obsLeft < nearestAfter) {
                    nearestAfter = obsLeft; // obstacle entirely to the right
                } else if (obsLeft < sharedCoord && obsRight > sharedCoord) {
                    // Obstacle straddles the shared coordinate — creates negative-width corridor.
                    // nudgeGroup() detects this and skips nudging (segments stay at sharedCoord).
                    nearestBefore = Math.max(nearestBefore, obsRight);
                    nearestAfter = Math.min(nearestAfter, obsLeft);
                }
            }
        }

        // Apply obstacle margin: shrink usable corridor to maintain the same clearance
        // that the A* visibility graph router uses (Story 10-25, Pattern 1 fix).
        // Without this, nudging can shift segments into the expanded obstacle zone.
        return new CorridorBounds(nearestBefore + obstacleMargin, nearestAfter - obstacleMargin);
    }

    /**
     * Applies nudging offsets to a group of parallel segments.
     * Distributes segments evenly across the corridor, centred on the shared coordinate,
     * with clamping to corridor bounds to prevent obstacle penetration.
     */
    void nudgeGroup(List<PathOrderer.Segment> group, boolean horizontal,
                    int sharedCoord, CorridorBounds bounds,
                    List<List<AbsoluteBendpointDto>> bendpointLists,
                    List<int[]> sourceCenters, List<int[]> targetCenters) {
        int count = group.size();
        int corridorWidth = bounds.width();

        // Corridor blocked by straddling obstacle — skip nudging entirely
        if (corridorWidth <= 0) {
            logger.debug("Corridor blocked (width={}): skipping nudge for {} segments at {}={}",
                    corridorWidth, count, horizontal ? "y" : "x", sharedCoord);
            return;
        }

        // Sort by perpendicular endpoint position for crossing-consistent ordering
        List<PathOrderer.Segment> sorted = new ArrayList<>(group);
        sorted.sort((a, b) -> {
            double perpA = perpendicularEndpointPosition(a, horizontal, sourceCenters, targetCenters);
            double perpB = perpendicularEndpointPosition(b, horizontal, sourceCenters, targetCenters);
            int cmp = Double.compare(perpA, perpB);
            return cmp != 0 ? cmp : Integer.compare(a.parallelMidpoint(), b.parallelMidpoint());
        });

        // Compute spacing
        double rawSpacing = (double) corridorWidth / (count + 1);
        double spacing;
        if (corridorWidth < minSpacing * (count - 1)) {
            spacing = Math.max(1, rawSpacing);
            logger.debug("Narrow corridor: spacing reduced to {}px for {} segments in {}px corridor",
                    spacing, count, corridorWidth);
        } else {
            spacing = Math.min(maxSpacing, Math.max(minSpacing, rawSpacing));
        }

        // Centre the group around the original shared coordinate
        double actualTotalSpan = spacing * (count - 1);
        double startOffset = sharedCoord - actualTotalSpan / 2.0;

        // Apply offsets to each segment's bendpoints
        for (int i = 0; i < sorted.size(); i++) {
            PathOrderer.Segment seg = sorted.get(i);
            int newCoord = (int) Math.round(startOffset + i * spacing);

            // Clamp to corridor bounds to prevent obstacle penetration
            newCoord = Math.max(bounds.lowerBound(), Math.min(bounds.upperBound(), newCoord));

            int delta = newCoord - sharedCoord;

            if (delta == 0) {
                continue; // No offset needed
            }

            // Update the two bendpoints that define this segment
            List<AbsoluteBendpointDto> bendpoints = bendpointLists.get(seg.connectionIndex());
            int bpIdx1 = seg.segmentIndex();
            int bpIdx2 = seg.segmentIndex() + 1;

            if (bpIdx1 >= 0 && bpIdx1 < bendpoints.size()) {
                AbsoluteBendpointDto bp = bendpoints.get(bpIdx1);
                if (horizontal) {
                    bendpoints.set(bpIdx1, new AbsoluteBendpointDto(bp.x(), bp.y() + delta));
                } else {
                    bendpoints.set(bpIdx1, new AbsoluteBendpointDto(bp.x() + delta, bp.y()));
                }
            }
            if (bpIdx2 >= 0 && bpIdx2 < bendpoints.size()) {
                AbsoluteBendpointDto bp = bendpoints.get(bpIdx2);
                if (horizontal) {
                    bendpoints.set(bpIdx2, new AbsoluteBendpointDto(bp.x(), bp.y() + delta));
                } else {
                    bendpoints.set(bpIdx2, new AbsoluteBendpointDto(bp.x() + delta, bp.y()));
                }
            }

            logger.debug("Nudged conn[{}] seg[{}]: {} offset {} (new coord={})",
                    seg.connectionIndex(), seg.segmentIndex(),
                    horizontal ? "y" : "x", delta, newCoord);
        }
    }

    /**
     * Computes the perpendicular endpoint position for a segment's connection.
     * Used for sorting segments in a corridor to align with crossing minimization.
     */
    private double perpendicularEndpointPosition(PathOrderer.Segment seg, boolean horizontal,
                                                  List<int[]> sourceCenters, List<int[]> targetCenters) {
        int conn = seg.connectionIndex();
        if (horizontal) {
            // Horizontal corridor: perpendicular order by y-position of connection endpoints
            return (sourceCenters.get(conn)[1] + targetCenters.get(conn)[1]) / 2.0;
        } else {
            // Vertical corridor: perpendicular order by x-position of connection endpoints
            return (sourceCenters.get(conn)[0] + targetCenters.get(conn)[0]) / 2.0;
        }
    }

    /**
     * Checks if a segment group contains segments from more than one connection.
     */
    private boolean hasMultipleConnections(List<PathOrderer.Segment> group) {
        int firstConn = group.get(0).connectionIndex();
        for (int i = 1; i < group.size(); i++) {
            if (group.get(i).connectionIndex() != firstConn) {
                return true;
            }
        }
        return false;
    }
}
