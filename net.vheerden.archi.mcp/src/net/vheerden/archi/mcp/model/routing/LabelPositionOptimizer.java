package net.vheerden.archi.mcp.model.routing;

import java.util.ArrayList;
import java.util.HashMap;
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
 * Greedy label position optimizer for routed connections (Story 11-31).
 * Pure-geometry class — no EMF/SWT dependencies.
 *
 * <p>After routing and label clearance, this optimizer evaluates all 3 possible
 * text positions (source=0, middle=1, target=2) for each connection label and
 * selects the position with the fewest overlaps. Connections are processed
 * longest-path-first (greedy order) so that labels with the most flexibility
 * are locked in first.</p>
 */
class LabelPositionOptimizer {

    private static final Logger logger = LoggerFactory.getLogger(LabelPositionOptimizer.class);

    /** Inset margin applied to label bounds before overlap checks (synced with LayoutQualityAssessor). */
    static final double LABEL_OVERLAP_INSET = 10.0;
    /** Proximity threshold for near-miss scoring (synced with LayoutQualityAssessor). */
    static final double LABEL_PROXIMITY_THRESHOLD = 5.0;

    /**
     * Optimizes label positions for all connections with non-empty labels.
     * Returns a map of connectionId → optimal textPosition for connections
     * whose position was changed (i.e., different from the input textPosition).
     *
     * @param connections  batch routing input (includes labelText, textPosition)
     * @param paths        corresponding routed paths (same index as connections)
     * @param allObstacles all element rectangles on the view (for overlap scoring)
     * @param connectionExcludeSets per-connection exclude sets (connectionId → set of IDs to skip)
     *                              — source, target, ancestors, descendants
     * @return map of connectionId → new textPosition (only includes changed positions)
     */
    Map<String, Integer> optimize(
            List<RoutingPipeline.ConnectionEndpoints> connections,
            List<List<AbsoluteBendpointDto>> paths,
            List<RoutingRect> allObstacles,
            Map<String, Set<String>> connectionExcludeSets) {

        // Build index of connections with labels, paired with their path index
        List<int[]> labeledIndices = new ArrayList<>(); // each entry: [originalIndex, pathLength]
        for (int i = 0; i < connections.size(); i++) {
            RoutingPipeline.ConnectionEndpoints conn = connections.get(i);
            if (conn.labelText() != null && !conn.labelText().isEmpty()) {
                int pathLen = computePathLength(paths.get(i),
                        new int[]{conn.source().centerX(), conn.source().centerY()},
                        new int[]{conn.target().centerX(), conn.target().centerY()});
                labeledIndices.add(new int[]{i, pathLen});
            }
        }

        if (labeledIndices.isEmpty()) {
            return Map.of();
        }

        // Sort by path length descending (longest first = most flexibility)
        labeledIndices.sort((a, b) -> Integer.compare(b[1], a[1]));

        // Filter obstacles to non-group elements (groups are transparent containers)
        // allObstacles already excludes groups in the routing pipeline caller

        // Greedy optimization pass
        Map<String, Integer> changedPositions = new LinkedHashMap<>();
        List<RoutingRect> lockedLabels = new ArrayList<>();

        for (int[] entry : labeledIndices) {
            int idx = entry[0];
            RoutingPipeline.ConnectionEndpoints conn = connections.get(idx);
            List<AbsoluteBendpointDto> path = paths.get(idx);
            int[] sourceCenter = {conn.source().centerX(), conn.source().centerY()};
            int[] targetCenter = {conn.target().centerX(), conn.target().centerY()};
            Set<String> excludeIds = connectionExcludeSets.getOrDefault(
                    conn.connectionId(), Set.of());

            int bestPosition = conn.textPosition();
            double bestScore = Double.MAX_VALUE;

            for (int pos = 0; pos <= 2; pos++) {
                RoutingRect labelRect = LabelClearance.computeLabelRect(
                        path, sourceCenter, targetCenter, conn.labelText(), pos);
                if (labelRect == null) {
                    continue;
                }

                double score = scorePosition(labelRect, allObstacles, excludeIds, lockedLabels);

                // Select lowest score; ties broken by preferring current position
                if (score < bestScore || (score == bestScore && pos == conn.textPosition())) {
                    bestScore = score;
                    bestPosition = pos;
                }
            }

            // Lock the chosen label rect
            RoutingRect chosenRect = LabelClearance.computeLabelRect(
                    path, sourceCenter, targetCenter, conn.labelText(), bestPosition);
            if (chosenRect != null) {
                lockedLabels.add(chosenRect);
            }

            // Only record if position actually changed
            if (bestPosition != conn.textPosition()) {
                changedPositions.put(conn.connectionId(), bestPosition);
                logger.debug("Optimized label position for connection {}: {} -> {}",
                        conn.connectionId(), conn.textPosition(), bestPosition);
            }
        }

        if (!changedPositions.isEmpty()) {
            logger.info("Label position optimization: {} labels repositioned out of {} labeled connections",
                    changedPositions.size(), labeledIndices.size());
        }

        return changedPositions;
    }

    /**
     * Scores a candidate label position against elements and locked labels.
     * Full overlap = 1.0, proximity near-miss = 0.5.
     */
    double scorePosition(RoutingRect labelRect, List<RoutingRect> obstacles,
                          Set<String> excludeIds, List<RoutingRect> lockedLabels) {
        double score = 0;

        // Score against elements (with exclusions)
        for (RoutingRect obs : obstacles) {
            if (obs.id() != null && excludeIds.contains(obs.id())) {
                continue;
            }
            if (insetRectOverlap(labelRect, obs)) {
                score += 1.0;
            } else if (isWithinProximity(labelRect, obs)) {
                score += 0.5;
            }
        }

        // Score against locked labels from previously-optimized connections
        for (RoutingRect locked : lockedLabels) {
            if (insetRectOverlap(labelRect, locked)) {
                score += 1.0;
            } else if (isWithinProximity(labelRect, locked)) {
                score += 0.5;
            }
        }

        return score;
    }

    /**
     * Checks if a label's inset bounding box overlaps another rectangle.
     * Same logic as LayoutQualityAssessor.insetRectOverlap().
     */
    static boolean insetRectOverlap(RoutingRect label, RoutingRect other) {
        double xInset = Math.min(LABEL_OVERLAP_INSET, label.width() / 3.0);
        double yInset = Math.min(LABEL_OVERLAP_INSET, label.height() / 3.0);
        double lx = label.x() + xInset;
        double ly = label.y() + yInset;
        double lw = label.width() - 2 * xInset;
        double lh = label.height() - 2 * yInset;
        if (lw <= 0 || lh <= 0) return false;
        return lx < other.x() + other.width() && lx + lw > other.x()
                && ly < other.y() + other.height() && ly + lh > other.y();
    }

    /**
     * Checks if a label's bounding box is within proximity threshold of another rectangle
     * without actually overlapping (after inset). Same logic as LayoutQualityAssessor.isWithinProximity().
     */
    static boolean isWithinProximity(RoutingRect label, RoutingRect other) {
        double ex = other.x() - LABEL_PROXIMITY_THRESHOLD;
        double ey = other.y() - LABEL_PROXIMITY_THRESHOLD;
        double ew = other.width() + 2 * LABEL_PROXIMITY_THRESHOLD;
        double eh = other.height() + 2 * LABEL_PROXIMITY_THRESHOLD;

        return label.x() < ex + ew && label.x() + label.width() > ex
                && label.y() < ey + eh && label.y() + label.height() > ey;
    }

    /**
     * Computes total path length (source center → bendpoints → target center).
     */
    private int computePathLength(List<AbsoluteBendpointDto> path,
                                   int[] sourceCenter, int[] targetCenter) {
        double totalLength = 0;

        int prevX = sourceCenter[0];
        int prevY = sourceCenter[1];

        for (AbsoluteBendpointDto bp : path) {
            double dx = bp.x() - prevX;
            double dy = bp.y() - prevY;
            totalLength += Math.sqrt(dx * dx + dy * dy);
            prevX = bp.x();
            prevY = bp.y();
        }

        double dx = targetCenter[0] - prevX;
        double dy = targetCenter[1] - prevY;
        totalLength += Math.sqrt(dx * dx + dy * dy);

        return (int) Math.round(totalLength);
    }
}
