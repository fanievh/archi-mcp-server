package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.vheerden.archi.mcp.model.geometry.GeometryUtils;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Stateless pure-geometry orthogonal (Manhattan) routing for connections (Story 9-5).
 * No EMF imports — operates on {@link RoutingRect} records and returns
 * {@link AbsoluteBendpointDto} lists.
 *
 * <p>Algorithm per connection:</p>
 * <ol>
 *   <li>Axis-aligned: straight line (0 bendpoints)</li>
 *   <li>General case: Z-shape with 2 bendpoints using midpoint</li>
 *   <li>Obstacle avoidance: test both horizontal-first and vertical-first, pick better</li>
 *   <li>If obstacles on both: offset midpoint around the largest obstacle</li>
 * </ol>
 *
 * <p>All coordinates are absolute canvas coordinates. The accessor is responsible
 * for converting to/from Archi's relative offset model.</p>
 *
 * @deprecated Replaced by {@link net.vheerden.archi.mcp.model.routing.RoutingPipeline}
 *             which uses visibility-graph-based routing with A* search (Story 10-6c).
 *             Retained as reference and fallback.
 */
@Deprecated
public class ConnectionRouter {

    /** Margin (px) around obstacles when offsetting routes. */
    private static final int OBSTACLE_MARGIN = 15;

    ConnectionRouter() {
    }

    /**
     * Computes orthogonal routing bendpoints for a connection between source and target,
     * avoiding obstacles where possible.
     *
     * @param source      source element rectangle (absolute canvas coordinates)
     * @param target      target element rectangle (absolute canvas coordinates)
     * @param obstacles   all element rectangles that could obstruct the path;
     *                    caller should exclude source/target and their ancestor groups
     * @return list of absolute bendpoints (0 = straight line, 1 = L-shape, 2 = Z-shape)
     */
    List<AbsoluteBendpointDto> computeOrthogonalRoute(
            RoutingRect source, RoutingRect target, List<RoutingRect> obstacles) {

        int srcCX = source.centerX();
        int srcCY = source.centerY();
        int tgtCX = target.centerX();
        int tgtCY = target.centerY();

        int deltaX = tgtCX - srcCX;
        int deltaY = tgtCY - srcCY;

        // Self-connection or overlapping centers: skip
        if (deltaX == 0 && deltaY == 0) {
            return Collections.emptyList();
        }

        // Axis-aligned: straight line (0 bendpoints)
        int srcHalfW = source.width() / 2;
        int tgtHalfW = target.width() / 2;
        int srcHalfH = source.height() / 2;
        int tgtHalfH = target.height() / 2;

        int thresholdX = Math.min(srcHalfW, tgtHalfW);
        int thresholdY = Math.min(srcHalfH, tgtHalfH);

        if (Math.abs(deltaX) < thresholdX || Math.abs(deltaY) < thresholdY) {
            return Collections.emptyList();
        }

        // General case: Z-shape routing with 2 bendpoints
        // Try both orientations and pick the one with fewer obstacle intersections
        int midX = (srcCX + tgtCX) / 2;
        int midY = (srcCY + tgtCY) / 2;

        // Horizontal-first: src → (midX, srcCY) → (midX, tgtCY) → tgt
        List<AbsoluteBendpointDto> hFirst = List.of(
                new AbsoluteBendpointDto(midX, srcCY),
                new AbsoluteBendpointDto(midX, tgtCY));

        // Vertical-first: src → (srcCX, midY) → (tgtCX, midY) → tgt
        List<AbsoluteBendpointDto> vFirst = List.of(
                new AbsoluteBendpointDto(srcCX, midY),
                new AbsoluteBendpointDto(tgtCX, midY));

        int hObstacles = countObstacleIntersections(srcCX, srcCY, hFirst, tgtCX, tgtCY, obstacles);
        int vObstacles = countObstacleIntersections(srcCX, srcCY, vFirst, tgtCX, tgtCY, obstacles);

        // If both clean or equal, prefer horizontal-first (conventional reading order)
        if (hObstacles <= vObstacles && hObstacles == 0) {
            return hFirst;
        }
        if (vObstacles < hObstacles && vObstacles == 0) {
            return vFirst;
        }

        // Both have obstacles: try offsetting the midpoint around the largest obstacle
        List<AbsoluteBendpointDto> offsetRoute = tryOffsetRoute(
                srcCX, srcCY, tgtCX, tgtCY, obstacles);
        if (offsetRoute != null) {
            return offsetRoute;
        }

        // Fallback: return the orientation with fewer intersections
        return hObstacles <= vObstacles ? hFirst : vFirst;
    }

    /**
     * Counts how many obstacle rectangles are intersected by the path segments
     * defined by source → bendpoints → target.
     */
    private int countObstacleIntersections(int srcX, int srcY,
            List<AbsoluteBendpointDto> bendpoints, int tgtX, int tgtY,
            List<RoutingRect> obstacles) {

        List<int[]> pathPoints = buildPathPoints(srcX, srcY, bendpoints, tgtX, tgtY);
        int count = 0;

        for (RoutingRect obs : obstacles) {
            for (int i = 0; i < pathPoints.size() - 1; i++) {
                if (lineSegmentIntersectsRect(
                        pathPoints.get(i)[0], pathPoints.get(i)[1],
                        pathPoints.get(i + 1)[0], pathPoints.get(i + 1)[1],
                        obs.x(), obs.y(), obs.width(), obs.height())) {
                    count++;
                    break; // Count each obstacle once
                }
            }
        }
        return count;
    }

    /**
     * Builds a list of [x,y] points representing the complete path:
     * source center → bendpoints → target center.
     */
    private List<int[]> buildPathPoints(int srcX, int srcY,
            List<AbsoluteBendpointDto> bendpoints, int tgtX, int tgtY) {
        List<int[]> points = new ArrayList<>(bendpoints.size() + 2);
        points.add(new int[]{srcX, srcY});
        for (AbsoluteBendpointDto bp : bendpoints) {
            points.add(new int[]{bp.x(), bp.y()});
        }
        points.add(new int[]{tgtX, tgtY});
        return points;
    }

    /**
     * Attempts to find a clean route by offsetting around the largest obstacle
     * on the horizontal-first path. Returns null if no improvement found.
     */
    private List<AbsoluteBendpointDto> tryOffsetRoute(
            int srcCX, int srcCY, int tgtCX, int tgtCY,
            List<RoutingRect> obstacles) {

        int midX = (srcCX + tgtCX) / 2;

        // Find the obstacle closest to the midpoint vertical line
        RoutingRect blockingObs = null;
        int minDist = Integer.MAX_VALUE;

        for (RoutingRect obs : obstacles) {
            // Check if this obstacle is in the vertical corridor of the Z-path
            int obsCX = obs.centerX();
            int dist = Math.abs(obsCX - midX);
            if (dist < minDist && isInVerticalBand(obs, srcCY, tgtCY)) {
                minDist = dist;
                blockingObs = obs;
            }
        }

        if (blockingObs == null) {
            return null;
        }

        // Route around the obstacle: offset midX to obstacle edge + margin
        int leftOffset = blockingObs.x() - OBSTACLE_MARGIN;
        int rightOffset = blockingObs.x() + blockingObs.width() + OBSTACLE_MARGIN;

        // Pick the side closest to the midpoint
        int offsetMidX;
        if (Math.abs(leftOffset - midX) <= Math.abs(rightOffset - midX)) {
            offsetMidX = leftOffset;
        } else {
            offsetMidX = rightOffset;
        }

        List<AbsoluteBendpointDto> offsetRoute = List.of(
                new AbsoluteBendpointDto(offsetMidX, srcCY),
                new AbsoluteBendpointDto(offsetMidX, tgtCY));

        int offsetObstacles = countObstacleIntersections(
                srcCX, srcCY, offsetRoute, tgtCX, tgtCY, obstacles);

        // Only use offset route if it actually reduces intersections
        List<AbsoluteBendpointDto> hFirst = List.of(
                new AbsoluteBendpointDto(midX, srcCY),
                new AbsoluteBendpointDto(midX, tgtCY));
        int originalObstacles = countObstacleIntersections(
                srcCX, srcCY, hFirst, tgtCX, tgtCY, obstacles);

        return offsetObstacles < originalObstacles ? offsetRoute : null;
    }

    /**
     * Checks if an obstacle is in the vertical band between srcY and tgtY.
     */
    private boolean isInVerticalBand(RoutingRect obs, int srcY, int tgtY) {
        int minY = Math.min(srcY, tgtY);
        int maxY = Math.max(srcY, tgtY);
        // Obstacle overlaps the vertical range
        return obs.y() < maxY && obs.y() + obs.height() > minY;
    }

    /**
     * Tests if a line segment intersects an axis-aligned rectangle.
     * Delegates to {@link GeometryUtils#lineSegmentIntersectsRect(int, int, int, int, int, int, int, int)}.
     */
    public static boolean lineSegmentIntersectsRect(int x1, int y1, int x2, int y2,
                                              int rx, int ry, int rw, int rh) {
        return GeometryUtils.lineSegmentIntersectsRect(x1, y1, x2, y2, rx, ry, rw, rh);
    }
}
