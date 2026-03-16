package net.vheerden.archi.mcp.model.routing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.vheerden.archi.mcp.model.RoutingRect;

/**
 * Analyzes failed connection routes and recommends element moves to unblock them (Story 10-31).
 * Neighbor-aware: checks for collisions, canvas bounds, and inter-recommendation conflicts (Story 10-33).
 * Pure-geometry class — no EMF/SWT dependencies.
 */
public class RoutingRecommendationEngine {

    static final int MAX_RECOMMENDATIONS = 3;
    static final int MIN_RECOMMENDATION_GAP = 20;

    /**
     * Backwards-compatible overload without neighbor awareness.
     */
    public static List<MoveRecommendation> recommend(
            List<FailedConnection> failures,
            List<RoutingPipeline.ConnectionEndpoints> allConnections) {
        return recommend(failures, allConnections, List.of());
    }

    /**
     * Analyzes failed connections and produces move recommendations for blocking elements.
     * Checks recommendations against neighboring elements to avoid creating overlaps (Story 10-33).
     *
     * <p><b>Heuristic limitation:</b> This method tests the direct diagonal line from source center
     * to target center against obstacles. It may miss blockers that only affect orthogonal paths
     * but not the straight-line path (e.g., an element to the right of the diagonal that the L-shaped
     * orthogonal route must cross). This is a known trade-off — the engine is a best-effort heuristic,
     * not a guarantee. The LLM re-routes after applying any recommended move.</p>
     *
     * @param failures       list of connections that failed routing
     * @param allConnections all connection endpoints (used to find geometry for failed connections)
     * @param allElements    all non-group element bounds on the view for collision checking
     * @return consolidated, prioritized list of move recommendations (max 3)
     */
    public static List<MoveRecommendation> recommend(
            List<FailedConnection> failures,
            List<RoutingPipeline.ConnectionEndpoints> allConnections,
            List<RoutingRect> allElements) {

        if (failures == null || failures.isEmpty()) {
            return List.of();
        }

        // Map connectionId → ConnectionEndpoints for quick lookup
        Map<String, RoutingPipeline.ConnectionEndpoints> endpointsMap = new LinkedHashMap<>();
        for (RoutingPipeline.ConnectionEndpoints ep : allConnections) {
            endpointsMap.put(ep.connectionId(), ep);
        }

        // For each failed connection, find blocking obstacles and compute displacement
        // Key: obstacle ID → consolidated blocking info
        Map<String, BlockingInfo> blockingMap = new LinkedHashMap<>();

        for (FailedConnection fc : failures) {
            RoutingPipeline.ConnectionEndpoints ep = endpointsMap.get(fc.connectionId());
            if (ep == null) {
                continue;
            }

            int srcCX = ep.source().centerX();
            int srcCY = ep.source().centerY();
            int tgtCX = ep.target().centerX();
            int tgtCY = ep.target().centerY();

            // Test direct line (source center → target center) against per-connection obstacles
            for (RoutingRect obs : ep.obstacles()) {
                if (obs.id() == null) {
                    continue;
                }
                if (EdgeAttachmentCalculator.lineSegmentIntersectsRect(
                        srcCX, srcCY, tgtCX, tgtCY,
                        obs.x(), obs.y(), obs.width(), obs.height())) {
                    int[] displacement = computeDisplacement(
                            srcCX, srcCY, tgtCX, tgtCY, obs);

                    BlockingInfo info = blockingMap.computeIfAbsent(obs.id(),
                            id -> new BlockingInfo(obs));
                    info.addBlocking(displacement[0], displacement[1],
                            fc.sourceId(), fc.targetId());
                }
            }
        }

        if (blockingMap.isEmpty()) {
            return List.of();
        }

        // Convert to MoveRecommendation list
        List<MoveRecommendation> recommendations = new ArrayList<>();
        for (Map.Entry<String, BlockingInfo> entry : blockingMap.entrySet()) {
            BlockingInfo info = entry.getValue();
            int dx = info.mergedDx;
            int dy = info.mergedDy;

            // Neighbor collision check (Story 10-33)
            // Exclude only the endpoints of connections this obstacle actually blocks
            if (allElements != null && !allElements.isEmpty()) {
                int[] clamped = clampForNeighborCollisions(
                        info.obstacle, dx, dy, allElements, info.relatedEndpointIds);
                dx = clamped[0];
                dy = clamped[1];
            }

            // Canvas bounds check (Story 10-33)
            int destX = info.obstacle.x() + dx;
            int destY = info.obstacle.y() + dy;
            if (destX < 0) {
                dx = -info.obstacle.x(); // clamp to x=0
            }
            if (destY < 0) {
                dy = -info.obstacle.y(); // clamp to y=0
            }

            // If displacement reduced to zero, omit recommendation
            if (dx == 0 && dy == 0) {
                continue;
            }

            String reason = buildReason(dx, dy, info.count);
            recommendations.add(new MoveRecommendation(
                    entry.getKey(), entry.getKey(),
                    dx, dy, reason, info.count));
        }

        if (recommendations.isEmpty()) {
            return List.of();
        }

        // Inter-recommendation conflict check (Story 10-33)
        resolveInterRecommendationConflicts(recommendations, blockingMap, allElements);

        recommendations.sort((a, b) ->
                Integer.compare(b.connectionsUnblocked(), a.connectionsUnblocked()));

        if (recommendations.size() > MAX_RECOMMENDATIONS) {
            return List.copyOf(recommendations.subList(0, MAX_RECOMMENDATIONS));
        }
        return List.copyOf(recommendations);
    }

    /**
     * Clamps displacement to avoid overlapping neighboring elements.
     * Returns adjusted [dx, dy]. Returns [0, 0] if no safe move exists.
     */
    static int[] clampForNeighborCollisions(RoutingRect element, int dx, int dy,
            List<RoutingRect> allElements, Set<String> excludeIds) {
        int destX = element.x() + dx;
        int destY = element.y() + dy;
        int destW = element.width();
        int destH = element.height();

        for (RoutingRect other : allElements) {
            if (other.id() == null || other.id().equals(element.id())) {
                continue;
            }
            if (excludeIds.contains(other.id())) {
                continue;
            }
            // Skip visual children contained within the element being moved (Story 10-34).
            // Nested ApplicationFunctions inside ApplicationComponents are not independent
            // neighbors — they move with their parent, so they can't block the parent's move.
            if (other.x() >= element.x() && other.y() >= element.y()
                    && other.x() + other.width() <= element.x() + element.width()
                    && other.y() + other.height() <= element.y() + element.height()) {
                continue;
            }

            // Check if destination (expanded by gap) overlaps this neighbor
            if (rectsOverlapWithGap(destX, destY, destW, destH,
                    other.x(), other.y(), other.width(), other.height(), MIN_RECOMMENDATION_GAP)) {
                // Clamp: reduce displacement to stop MIN_RECOMMENDATION_GAP before neighbor
                if (dx != 0 && dy == 0) {
                    // Horizontal move
                    dx = clampAxis(element.x(), element.width(), dx,
                            other.x(), other.width());
                } else if (dy != 0 && dx == 0) {
                    // Vertical move
                    dy = clampAxis(element.y(), element.height(), dy,
                            other.y(), other.height());
                } else {
                    // Diagonal — clamp both axes independently.
                    // This is conservative: clamping one axis alone might resolve the overlap,
                    // but we clamp both for safety. Diagonal occurs only when an obstacle blocks
                    // multiple connections requiring different-axis moves (rare in practice).
                    dx = clampAxis(element.x(), element.width(), dx,
                            other.x(), other.width());
                    dy = clampAxis(element.y(), element.height(), dy,
                            other.y(), other.height());
                }

                // Recompute destination after clamping
                destX = element.x() + dx;
                destY = element.y() + dy;
            }
        }
        return new int[]{dx, dy};
    }

    /**
     * Clamps a single-axis displacement to stop MIN_RECOMMENDATION_GAP before a neighbor.
     */
    private static int clampAxis(int elemPos, int elemSize, int displacement,
            int neighborPos, int neighborSize) {
        if (displacement > 0) {
            // Moving in positive direction: element right/bottom edge must stop before neighbor left/top edge
            int maxDisplacement = neighborPos - MIN_RECOMMENDATION_GAP - (elemPos + elemSize);
            if (maxDisplacement <= 0) {
                return 0;
            }
            return Math.min(displacement, maxDisplacement);
        } else {
            // Moving in negative direction: element left/top edge must stop after neighbor right/bottom edge
            int maxDisplacement = (neighborPos + neighborSize) + MIN_RECOMMENDATION_GAP - elemPos;
            if (maxDisplacement >= 0) {
                return 0;
            }
            return Math.max(displacement, maxDisplacement);
        }
    }

    /**
     * Checks if two rectangles overlap when the first is expanded by gap on all sides.
     */
    static boolean rectsOverlapWithGap(int x1, int y1, int w1, int h1,
            int x2, int y2, int w2, int h2, int gap) {
        return x1 - gap < x2 + w2
                && x1 + w1 + gap > x2
                && y1 - gap < y2 + h2
                && y1 + h1 + gap > y2;
    }

    /**
     * Resolves conflicts between recommendations whose destination rectangles overlap.
     * Keeps the recommendation with higher connectionsUnblocked, removes the other.
     */
    static void resolveInterRecommendationConflicts(
            List<MoveRecommendation> recommendations,
            Map<String, BlockingInfo> blockingMap,
            List<RoutingRect> allElements) {
        if (recommendations.size() < 2) {
            return;
        }

        Set<Integer> toRemove = new HashSet<>();
        for (int i = 0; i < recommendations.size(); i++) {
            if (toRemove.contains(i)) continue;
            MoveRecommendation recA = recommendations.get(i);
            BlockingInfo infoA = blockingMap.get(recA.elementId());
            if (infoA == null) continue;
            int destAx = infoA.obstacle.x() + recA.dx();
            int destAy = infoA.obstacle.y() + recA.dy();

            for (int j = i + 1; j < recommendations.size(); j++) {
                if (toRemove.contains(j)) continue;
                MoveRecommendation recB = recommendations.get(j);
                BlockingInfo infoB = blockingMap.get(recB.elementId());
                if (infoB == null) continue;
                int destBx = infoB.obstacle.x() + recB.dx();
                int destBy = infoB.obstacle.y() + recB.dy();

                if (rectsOverlapWithGap(destAx, destAy, infoA.obstacle.width(), infoA.obstacle.height(),
                        destBx, destBy, infoB.obstacle.width(), infoB.obstacle.height(),
                        MIN_RECOMMENDATION_GAP)) {
                    // Conflict: keep higher-impact, remove lower-impact
                    if (recA.connectionsUnblocked() >= recB.connectionsUnblocked()) {
                        toRemove.add(j);
                    } else {
                        toRemove.add(i);
                        break; // i is removed, no need to check further pairs with i
                    }
                }
            }
        }

        if (!toRemove.isEmpty()) {
            // Remove in reverse index order to avoid shifting
            List<Integer> sorted = new ArrayList<>(toRemove);
            sorted.sort((a, b) -> Integer.compare(b, a));
            for (int idx : sorted) {
                recommendations.remove(idx);
            }
        }
    }

    /**
     * Computes the minimum axis-aligned displacement to move an obstacle clear of the
     * direct line between source and target centers.
     *
     * <p>For more horizontal lines, moves the obstacle vertically.
     * For more vertical lines, moves horizontally.
     * Direction is away from the line.</p>
     */
    static int[] computeDisplacement(int srcCX, int srcCY,
            int tgtCX, int tgtCY, RoutingRect obs) {
        int lineDx = Math.abs(tgtCX - srcCX);
        int lineDy = Math.abs(tgtCY - srcCY);
        int margin = RoutingPipeline.DEFAULT_MARGIN;
        int obsCX = obs.centerX();
        int obsCY = obs.centerY();

        if (lineDx >= lineDy) {
            // More horizontal line → move obstacle vertically
            // Interpolate line Y at obstacle center X
            int lineYAtObs;
            if (tgtCX == srcCX) {
                lineYAtObs = (srcCY + tgtCY) / 2;
            } else {
                lineYAtObs = srcCY + (tgtCY - srcCY) * (obsCX - srcCX) / (tgtCX - srcCX);
            }
            // Displacement: half obstacle height + margin clears the obstacle from the center line.
            // This is a heuristic minimum — the LLM re-routes after applying the move.
            int displacement = obs.height() / 2 + margin;
            // Direction: move obstacle away from the line
            int dy = (obsCY >= lineYAtObs) ? displacement : -displacement;
            return new int[]{0, dy};
        } else {
            // More vertical line → move obstacle horizontally
            // Interpolate line X at obstacle center Y
            int lineXAtObs;
            if (tgtCY == srcCY) {
                lineXAtObs = (srcCX + tgtCX) / 2;
            } else {
                lineXAtObs = srcCX + (tgtCX - srcCX) * (obsCY - srcCY) / (tgtCY - srcCY);
            }
            int displacement = obs.width() / 2 + margin;
            int dx = (obsCX >= lineXAtObs) ? displacement : -displacement;
            return new int[]{dx, 0};
        }
    }

    private static String buildReason(int dx, int dy, int count) {
        String direction;
        if (dx == 0 && dy == 0) {
            direction = "reposition";
        } else if (dx == 0 && dy < 0) {
            direction = "up " + Math.abs(dy) + "px";
        } else if (dx == 0 && dy > 0) {
            direction = "down " + dy + "px";
        } else if (dy == 0 && dx < 0) {
            direction = "left " + Math.abs(dx) + "px";
        } else if (dy == 0 && dx > 0) {
            direction = "right " + dx + "px";
        } else {
            direction = "dx=" + dx + ", dy=" + dy;
        }
        return "Move " + direction + " to clear corridor (blocks "
                + count + " connection" + (count > 1 ? "s" : "") + ")";
    }

    /**
     * Tracks consolidated blocking info for a single obstacle across multiple failed connections.
     */
    static class BlockingInfo {
        final RoutingRect obstacle;
        final Set<String> relatedEndpointIds = new HashSet<>();
        int mergedDx;
        int mergedDy;
        int count;

        BlockingInfo(RoutingRect obstacle) {
            this.obstacle = obstacle;
        }

        void addBlocking(int dx, int dy, String sourceId, String targetId) {
            // Merge: take max absolute value per axis (covers worst case).
            // Note: if connections need opposite directions (e.g., one needs +dy, another -dy),
            // the larger absolute value wins. This is a trade-off — the recommendation may not
            // help all connections, but the LLM re-routes after applying the move.
            if (Math.abs(dx) > Math.abs(mergedDx)) {
                mergedDx = dx;
            }
            if (Math.abs(dy) > Math.abs(mergedDy)) {
                mergedDy = dy;
            }
            if (sourceId != null) relatedEndpointIds.add(sourceId);
            if (targetId != null) relatedEndpointIds.add(targetId);
            count++;
        }
    }
}
