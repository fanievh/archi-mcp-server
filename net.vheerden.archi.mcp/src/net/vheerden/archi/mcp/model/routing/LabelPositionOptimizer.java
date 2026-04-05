package net.vheerden.archi.mcp.model.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
public class LabelPositionOptimizer {

    private static final Logger logger = LoggerFactory.getLogger(LabelPositionOptimizer.class);

    /** Inset margin applied to label bounds before overlap checks (synced with LayoutQualityAssessor). */
    static final double LABEL_OVERLAP_INSET = 10.0;
    /** Proximity threshold for near-miss scoring (synced with LayoutQualityAssessor). */
    static final double LABEL_PROXIMITY_THRESHOLD = 5.0;

    /**
     * Result of a multi-trial label optimization pass (Story backlog-b12).
     *
     * @param allPositions     connectionId → chosen textPosition for ALL labeled connections
     * @param changedPositions connectionId → new textPosition (only connections whose position changed)
     * @param totalScore       sum of overlap scores for all labeled connections at their chosen positions
     */
    public record MultiTrialResult(Map<String, Integer> allPositions,
                            Map<String, Integer> changedPositions,
                            double totalScore) {}

    /**
     * Optimizes label positions for all connections with non-empty labels.
     * Returns a map of connectionId → optimal textPosition for connections
     * whose position was changed (i.e., different from the input textPosition).
     *
     * <p>Single-pass deterministic optimization using longest-first ordering.
     * For multi-trial optimization with shuffled orderings, use
     * {@link #optimizeMultiTrial}.</p>
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

        List<int[]> labeledIndices = buildLongestFirstOrder(connections, paths);
        if (labeledIndices.isEmpty()) {
            return Map.of();
        }

        GreedyPassResult result = runGreedyPass(
                labeledIndices, connections, paths, allObstacles, connectionExcludeSets);

        if (!result.changedPositions.isEmpty()) {
            logger.info("Label position optimization: {} labels repositioned out of {} labeled connections",
                    result.changedPositions.size(), labeledIndices.size());
        }

        return result.changedPositions;
    }

    /**
     * Runs multiple greedy optimization trials with different processing orders
     * and returns the result with the lowest total overlap score (Story backlog-b12).
     *
     * <p>Trial 0 uses the deterministic longest-first ordering (same as {@link #optimize}).
     * Trials 1+ shuffle the ordering using the provided {@link Random}. The trial with
     * the lowest total score wins; ties are broken by preferring fewer position changes.</p>
     *
     * @param connections  batch routing input (includes labelText, textPosition)
     * @param paths        corresponding routed paths (same index as connections)
     * @param allObstacles all element rectangles on the view (for overlap scoring)
     * @param connectionExcludeSets per-connection exclude sets (connectionId → set of IDs to skip)
     * @param trials       number of trials to run (must be >= 1)
     * @param rng          random number generator for shuffling (trials 1+)
     * @return best result across all trials
     */
    public MultiTrialResult optimizeMultiTrial(
            List<RoutingPipeline.ConnectionEndpoints> connections,
            List<List<AbsoluteBendpointDto>> paths,
            List<RoutingRect> allObstacles,
            Map<String, Set<String>> connectionExcludeSets,
            int trials, Random rng) {

        if (trials < 1) {
            throw new IllegalArgumentException("trials must be >= 1, got " + trials);
        }

        List<int[]> baseOrder = buildLongestFirstOrder(connections, paths);
        if (baseOrder.isEmpty()) {
            return new MultiTrialResult(Map.of(), Map.of(), 0.0);
        }

        MultiTrialResult bestResult = null;
        double bestTotalScore = Double.MAX_VALUE;
        int bestChangeCount = Integer.MAX_VALUE;

        for (int t = 0; t < trials; t++) {
            List<int[]> order;
            if (t == 0) {
                order = baseOrder;
            } else {
                order = new ArrayList<>(baseOrder);
                Collections.shuffle(order, rng);
            }

            GreedyPassResult passResult = runGreedyPass(
                    order, connections, paths, allObstacles, connectionExcludeSets);
            double totalScore = computeTotalScore(
                    passResult.allPositions, connections, paths,
                    allObstacles, connectionExcludeSets);

            if (totalScore < bestTotalScore
                    || (totalScore == bestTotalScore
                        && passResult.changedPositions.size() < bestChangeCount)) {
                bestResult = new MultiTrialResult(
                        passResult.allPositions, passResult.changedPositions, totalScore);
                bestTotalScore = totalScore;
                bestChangeCount = passResult.changedPositions.size();
            }
        }

        if (bestResult != null && !bestResult.changedPositions.isEmpty()) {
            logger.info("Multi-trial label optimization: {} trials, best score={}, {} labels repositioned",
                    trials, bestTotalScore, bestResult.changedPositions.size());
        }

        return bestResult;
    }

    /**
     * Builds the labeled connection indices sorted by path length descending (longest first).
     */
    private List<int[]> buildLongestFirstOrder(
            List<RoutingPipeline.ConnectionEndpoints> connections,
            List<List<AbsoluteBendpointDto>> paths) {

        List<int[]> labeledIndices = new ArrayList<>();
        for (int i = 0; i < connections.size(); i++) {
            RoutingPipeline.ConnectionEndpoints conn = connections.get(i);
            if (conn.labelText() != null && !conn.labelText().isEmpty()) {
                int pathLen = computePathLength(paths.get(i),
                        new int[]{conn.source().centerX(), conn.source().centerY()},
                        new int[]{conn.target().centerX(), conn.target().centerY()});
                labeledIndices.add(new int[]{i, pathLen});
            }
        }
        labeledIndices.sort((a, b) -> Integer.compare(b[1], a[1]));
        return labeledIndices;
    }

    /**
     * Internal result of a single greedy pass — includes both changed and all positions.
     */
    private record GreedyPassResult(Map<String, Integer> allPositions,
                                     Map<String, Integer> changedPositions) {}

    /**
     * Runs a single greedy optimization pass in the given index order.
     * Returns both changed positions and all positions (for total score computation).
     */
    private GreedyPassResult runGreedyPass(
            List<int[]> labeledIndices,
            List<RoutingPipeline.ConnectionEndpoints> connections,
            List<List<AbsoluteBendpointDto>> paths,
            List<RoutingRect> allObstacles,
            Map<String, Set<String>> connectionExcludeSets) {

        Map<String, Integer> changedPositions = new LinkedHashMap<>();
        Map<String, Integer> allPositions = new LinkedHashMap<>();
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

            allPositions.put(conn.connectionId(), bestPosition);
            if (bestPosition != conn.textPosition()) {
                changedPositions.put(conn.connectionId(), bestPosition);
                logger.debug("Optimized label position for connection {}: {} -> {}",
                        conn.connectionId(), conn.textPosition(), bestPosition);
            }
        }

        return new GreedyPassResult(allPositions, changedPositions);
    }

    /**
     * Computes the total overlap score for a complete position assignment.
     * Evaluates every labeled connection at its assigned position against all
     * obstacles and all other assigned labels.
     */
    double computeTotalScore(
            Map<String, Integer> allPositions,
            List<RoutingPipeline.ConnectionEndpoints> connections,
            List<List<AbsoluteBendpointDto>> paths,
            List<RoutingRect> allObstacles,
            Map<String, Set<String>> connectionExcludeSets) {

        // Build label rects for all assigned positions
        List<RoutingRect> allLabelRects = new ArrayList<>();
        List<Set<String>> allExcludeIds = new ArrayList<>();
        for (int i = 0; i < connections.size(); i++) {
            RoutingPipeline.ConnectionEndpoints conn = connections.get(i);
            Integer assignedPos = allPositions.get(conn.connectionId());
            if (assignedPos == null) {
                continue;
            }
            List<AbsoluteBendpointDto> path = paths.get(i);
            int[] sourceCenter = {conn.source().centerX(), conn.source().centerY()};
            int[] targetCenter = {conn.target().centerX(), conn.target().centerY()};
            RoutingRect labelRect = LabelClearance.computeLabelRect(
                    path, sourceCenter, targetCenter, conn.labelText(), assignedPos);
            if (labelRect != null) {
                allLabelRects.add(labelRect);
                allExcludeIds.add(connectionExcludeSets.getOrDefault(
                        conn.connectionId(), Set.of()));
            }
        }

        // Score each label against obstacles and all OTHER labels
        double totalScore = 0;
        for (int i = 0; i < allLabelRects.size(); i++) {
            RoutingRect labelRect = allLabelRects.get(i);
            Set<String> excludeIds = allExcludeIds.get(i);

            // Score against obstacles
            for (RoutingRect obs : allObstacles) {
                if (obs.id() != null && excludeIds.contains(obs.id())) {
                    continue;
                }
                if (insetRectOverlap(labelRect, obs)) {
                    totalScore += 1.0;
                } else if (isWithinProximity(labelRect, obs)) {
                    totalScore += 0.5;
                }
            }

            // Score against other labels (each pair counted once per label)
            for (int j = i + 1; j < allLabelRects.size(); j++) {
                RoutingRect other = allLabelRects.get(j);
                if (insetRectOverlap(labelRect, other)) {
                    totalScore += 2.0; // both labels score 1.0 each
                } else if (isWithinProximity(labelRect, other)) {
                    totalScore += 1.0; // both labels score 0.5 each
                }
            }
        }

        return totalScore;
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
