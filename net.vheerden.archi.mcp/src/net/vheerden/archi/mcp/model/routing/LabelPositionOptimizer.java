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
 * Greedy label position optimizer for routed connections.
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

    /** textPosition for a Middle label (source=0, middle=1, target=2). */
    static final int TEXT_POSITION_MIDDLE = 1;

    /**
     * Own-endpoint overlap fraction at/above which a Middle label is treated as rendered ON its own
     * source/target box. Mirrors {@code LayoutQualityAssessor.LABEL_OWN_ENDPOINT_OVERLAP_FRACTION} so the
     * perpendicular-offset FIX engages exactly where DETECTION flags. The along-path element scoring
     * ({@link #scorePosition}) deliberately EXCLUDES a connection's own endpoints (a label always grazes
     * the box it attaches to), which masks own-endpoint bleed — this asymmetric fraction is the gap the
     * offset pass closes.
     */
    static final double LABEL_OWN_ENDPOINT_OVERLAP_FRACTION = 0.30;

    /**
     * Box-coverage companion to {@link #LABEL_OWN_ENDPOINT_OVERLAP_FRACTION}: the fraction of an ENDPOINT
     * BOX's area that must sit under the label before the label is treated as rendered ON that box. Mirrors
     * {@code LayoutQualityAssessor.LABEL_OWN_ENDPOINT_BOX_COVERAGE_FRACTION} so the perpendicular-offset FIX
     * engages exactly where DETECTION flags. The label-area fraction is structurally unreachable for a
     * genuinely tiny endpoint box (an ArchiMate Junction at its ~14x14 default) — a label can fully enclose it
     * yet cover only ~0.13 of the much larger label's own area — so the two rules are OR'd ({@link
     * #onOwnEndpointBox}). Self-limiting to small boxes: a label far smaller than a normal element box can
     * never cover this fraction of it, so a normal endpoint is never spuriously offset.
     */
    static final double LABEL_OWN_ENDPOINT_BOX_COVERAGE_FRACTION = 0.6;

    /**
     * Penalty added to a candidate position's score for EACH own source/target box the label is rendered
     * ON ({@link #onOwnEndpointBox}). The element-overlap loop in
     * {@link #scorePosition} deliberately EXCLUDES a connection's own endpoints (a label always grazes the
     * box it attaches to), so a label sitting squarely on its own source/target otherwise scores 0 there and
     * the greedy pick has no incentive to move off it. This penalty supplies that incentive.
     *
     * <p>Sized to the full element-overlap weight (1.0): an own-endpoint bleed outranks a minor proximity
     * near-miss (0.5) so the optimizer re-picks toward a clearer position — yet a genuinely clear position
     * (score 0) always wins, and a label that bleeds at all three positions stays put (equal scores → the
     * tie-break keeps the current position). It is never larger than a real element overlap, so the optimizer
     * never moves a label ONTO a third-party box to escape its own endpoint.</p>
     */
    static final double LABEL_OWN_ENDPOINT_PENALTY = 1.0;

    /**
     * Geometry-scoring displacement (px) used ONLY to rank candidate offset directions. The connection
     * "Label Offset" feature is a compass DIRECTION, not a distance — the renderer fixes the magnitude
     * at a platform-defined offset; this lets the pure-geometry scorer decide which direction lifts the
     * label clear of a box.
     * Sized to clear a typical label off a box edge; sufficiency of the rendered offset is a live-gate
     * confirmation, not a headless guarantee.
     */
    static final double OFFSET_SCORING_DISTANCE = 40.0;

    /**
     * Result of a multi-trial label optimization pass.
     *
     * @param allPositions     connectionId → chosen textPosition for ALL labeled connections
     * @param changedPositions connectionId → new textPosition (only connections whose position changed)
     * @param offsets          connectionId → perpendicular "Label Offset" compass bitmask, populated only
     *                         for Middle labels that still overlap an element after the along-path pick
     *                         (own-endpoint bleed via {@link #onOwnEndpointBox} or a third
     *                         party). Metric-neutral: offsets never affect {@code totalScore}.
     * @param totalScore       sum of overlap scores for all labeled connections at their chosen positions
     */
    public record MultiTrialResult(Map<String, Integer> allPositions,
                            Map<String, Integer> changedPositions,
                            Map<String, Integer> offsets,
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
     * and returns the result with the lowest total overlap score.
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
            return new MultiTrialResult(Map.of(), Map.of(), Map.of(), 0.0);
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
                        passResult.allPositions, passResult.changedPositions,
                        passResult.offsets, totalScore);
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
                                     Map<String, Integer> changedPositions,
                                     Map<String, Integer> offsets) {}

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
        Map<String, Integer> offsets = new LinkedHashMap<>();
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
            boolean horizontalHost = hostingSegmentHorizontal(path, sourceCenter, targetCenter);

            for (int pos = 0; pos <= 2; pos++) {
                RoutingRect labelRect = LabelClearance.computeLabelRect(
                        path, sourceCenter, targetCenter, conn.labelText(), pos);
                if (labelRect == null) {
                    continue;
                }

                double score = scorePosition(labelRect, allObstacles, excludeIds, lockedLabels)
                        + effectiveOwnEndpointPenalty(labelRect, conn.source(), conn.target(),
                                pos == TEXT_POSITION_MIDDLE, allObstacles, excludeIds, horizontalHost);

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

            // Perpendicular "Label Offset" last resort (Middle-only): when the best along-path Middle
            // pick still renders the label ON an element box, score the 8 compass offsets and keep the
            // first that lifts it clear. This is the channel the discrete textPosition pick lacks; it is
            // metric-neutral (the overlap score / labelOverlap metric does not see relativePosition), so
            // it is collected separately and applied via SetTextRelativePositionCommand.
            if (bestPosition == TEXT_POSITION_MIDDLE && chosenRect != null) {
                Integer offsetMask = computeOffsetForConnection(
                        conn, path, chosenRect, sourceCenter, targetCenter, allObstacles, excludeIds);
                if (offsetMask != null) {
                    offsets.put(conn.connectionId(), offsetMask);
                    logger.debug("Label offset for connection {} (Middle on element): mask={}",
                            conn.connectionId(), offsetMask);
                }
            }
        }

        return new GreedyPassResult(allPositions, changedPositions, offsets);
    }

    /**
     * Computes the perpendicular "Label Offset" compass bitmask for a single Middle-positioned connection,
     * or {@code null} when the label is already clear or no direction clears it. This is the per-connection
     * offset block shared by {@link #runGreedyPass} (offsets discovered during the greedy along-path pass)
     * and {@link #computeOffsetsForPositions} (offsets for an already-chosen position assignment supplied by
     * a routing pass). Pure geometry — {@link #hostingSegmentHorizontal} picks the perpendicular orientation,
     * {@link #chooseOffset} scores the candidates.
     */
    private Integer computeOffsetForConnection(
            RoutingPipeline.ConnectionEndpoints conn,
            List<AbsoluteBendpointDto> path,
            RoutingRect chosenRect,
            int[] sourceCenter, int[] targetCenter,
            List<RoutingRect> allObstacles, Set<String> excludeIds) {
        boolean horizontalHost = hostingSegmentHorizontal(path, sourceCenter, targetCenter);
        return chooseOffset(
                chosenRect, conn.source(), conn.target(), allObstacles, excludeIds, horizontalHost);
    }

    /**
     * Computes perpendicular "Label Offset" compass bitmasks for an already-chosen position assignment —
     * the position-PRESERVING entry point used after a routing pass has fixed each connection's textPosition
     * (e.g. {@code autoRouteConnections}). Unlike {@link #optimizeMultiTrial}, this never re-picks positions:
     * it offsets only a label whose chosen position is Middle and that still renders ON a box (own-endpoint
     * bleed via {@link #onOwnEndpointBox} or a third party). A connection absent from
     * {@code chosenPositions} falls back to its current {@code textPosition()}. Connections with no routed
     * path (e.g. failed routes) are skipped.
     *
     * <p>Metric-neutral and pure geometry — returns the bitmask map only; the EMF write happens via
     * {@code SetTextRelativePositionCommand}.</p>
     *
     * @param connections          batch routing inputs (includes labelText, source/target rects)
     * @param pathsByConnectionId  routed absolute paths keyed by connection ID (e.g. {@code routesToApply})
     * @param allObstacles         all element rectangles on the view (for overlap scoring)
     * @param connectionExcludeSets per-connection exclude sets (source, target, ancestors, descendants)
     * @param chosenPositions      connectionId → chosen textPosition from the routing pass
     * @return connectionId → offset bitmask, only for Middle labels still on a box that a direction clears
     */
    public Map<String, Integer> computeOffsetsForPositions(
            List<RoutingPipeline.ConnectionEndpoints> connections,
            Map<String, List<AbsoluteBendpointDto>> pathsByConnectionId,
            List<RoutingRect> allObstacles,
            Map<String, Set<String>> connectionExcludeSets,
            Map<String, Integer> chosenPositions) {

        Map<String, Integer> offsets = new LinkedHashMap<>();
        for (RoutingPipeline.ConnectionEndpoints conn : connections) {
            String labelText = conn.labelText();
            if (labelText == null || labelText.isEmpty()) {
                continue; // unlabeled connections move nothing
            }
            int position = chosenPositions.getOrDefault(conn.connectionId(), conn.textPosition());
            if (position != TEXT_POSITION_MIDDLE) {
                continue; // offset applies only at Middle (source/target offset render UNVERIFIED)
            }
            List<AbsoluteBendpointDto> path = pathsByConnectionId.get(conn.connectionId());
            if (path == null) {
                continue; // no routed path for this connection (e.g. failed route)
            }
            int[] sourceCenter = {conn.source().centerX(), conn.source().centerY()};
            int[] targetCenter = {conn.target().centerX(), conn.target().centerY()};
            RoutingRect chosenRect = LabelClearance.computeLabelRect(
                    path, sourceCenter, targetCenter, labelText, position);
            if (chosenRect == null) {
                continue;
            }
            Set<String> excludeIds = connectionExcludeSets.getOrDefault(
                    conn.connectionId(), Set.of());
            Integer offsetMask = computeOffsetForConnection(
                    conn, path, chosenRect, sourceCenter, targetCenter, allObstacles, excludeIds);
            if (offsetMask != null) {
                offsets.put(conn.connectionId(), offsetMask);
                logger.debug("Label offset for connection {} (Middle on element, position-preserving): mask={}",
                        conn.connectionId(), offsetMask);
            }
        }
        return offsets;
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
        List<RoutingRect> allSources = new ArrayList<>();
        List<RoutingRect> allTargets = new ArrayList<>();
        List<Boolean> allIsMiddle = new ArrayList<>();
        List<Boolean> allHorizontalHost = new ArrayList<>();
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
                allSources.add(conn.source());
                allTargets.add(conn.target());
                allIsMiddle.add(assignedPos == TEXT_POSITION_MIDDLE);
                allHorizontalHost.add(hostingSegmentHorizontal(path, sourceCenter, targetCenter));
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

            // Penalize sitting on the connection's OWN source/target — mirrors the greedy pass so the
            // multi-trial total ranks assignments consistently with the per-connection pick.
            totalScore += effectiveOwnEndpointPenalty(labelRect, allSources.get(i), allTargets.get(i),
                    allIsMiddle.get(i), allObstacles, allExcludeIds.get(i), allHorizontalHost.get(i));

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
     *
     * <p>The connection's own source/target are EXCLUDED (a label always grazes the box it attaches to);
     * the own-endpoint bleed signal is supplied separately by {@link #effectiveOwnEndpointPenalty}, added
     * by the callers ({@link #runGreedyPass}, {@link #computeTotalScore}) so both rank positions the same.</p>
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
     * Effective penalty for a label rendered ON its own source/target box at a candidate position — the
     * own-endpoint detection rule ({@link #onOwnEndpointBox}) that {@link #scorePosition}'s
     * element loop deliberately excludes, so without this signal a label sitting squarely on its own endpoint
     * scores 0 there and the greedy pick never moves off it. Adds {@link #LABEL_OWN_ENDPOINT_PENALTY} per bled
     * endpoint, pushing the optimizer toward a clearer position — or toward Middle, the only position the
     * connection "Label Offset" finisher can rescue.
     *
     * <p>That asymmetry is the crux: a Source/Target bleed is UNRECOVERABLE (the offset feature applies only
     * at Middle), so it is always penalised. A Middle bleed is WAIVED when {@link #chooseOffset} finds a
     * direction that lifts the label clear — the optimizer then keeps the label at Middle and the finisher
     * does its job (matching the shipped offset behaviour). When no direction clears (e.g. the all-collide
     * pathological case), the Middle penalty stands, so the optimizer does not abandon a less-bleeding
     * Source/Target position for an unrescuable Middle.</p>
     *
     * <p>A {@code null} box contributes nothing ({@link #onOwnEndpointBox} returns {@code false}).</p>
     */
    private double effectiveOwnEndpointPenalty(RoutingRect labelRect, RoutingRect source, RoutingRect target,
            boolean isMiddle, List<RoutingRect> obstacles, Set<String> excludeIds, boolean horizontalHost) {
        double penalty = 0;
        if (onOwnEndpointBox(labelRect, source)) {
            penalty += LABEL_OWN_ENDPOINT_PENALTY;
        }
        if (onOwnEndpointBox(labelRect, target)) {
            penalty += LABEL_OWN_ENDPOINT_PENALTY;
        }
        // penalty > 0 means own-endpoint bleed is already confirmed, so chooseOffset's own-endpoint branch
        // is the live one here (its third-party branch is irrelevant — never reached with penalty == 0).
        if (penalty > 0 && isMiddle
                && chooseOffset(labelRect, source, target, obstacles, excludeIds, horizontalHost) != null) {
            return 0; // the Middle-only offset finisher will lift it clear — keep the label at Middle
        }
        return penalty;
    }

    /**
     * Picks a perpendicular "Label Offset" compass direction that lifts a Middle label clear of the
     * element box it renders on, or {@code null} if the label is not on a box or no direction clears it.
     *
     * <p>"On a box" = {@link #onOwnEndpointBox} for either endpoint (label-area ≥
     * {@link #LABEL_OWN_ENDPOINT_OVERLAP_FRACTION} OR box-coverage ≥
     * {@link #LABEL_OWN_ENDPOINT_BOX_COVERAGE_FRACTION}, mirroring detection) OR an inset overlap with a
     * non-excluded (third-party) element. A candidate clears when the displaced label is off the box for
     * BOTH endpoints AND inset-overlaps no non-excluded element. Directions are tried
     * perpendicular-to-segment first, deterministically ({@link LabelOffsetDirection#candidateOrder}) —
     * {@code horizontalSegment} is the orientation of the segment HOSTING the Middle label (not the overall
     * source→target vector), so a Z-shaped orthogonal route gets the correct perpendicular tried first.</p>
     *
     * <p>Pure geometry — returns the bitmask only; the EMF write happens via SetTextRelativePositionCommand.</p>
     */
    private Integer chooseOffset(RoutingRect label, RoutingRect source, RoutingRect target,
            List<RoutingRect> obstacles, Set<String> excludeIds, boolean horizontalSegment) {

        boolean onOwnEndpoint = onOwnEndpointBox(label, source) || onOwnEndpointBox(label, target);
        boolean onThirdParty = overlapsNonExcluded(label, obstacles, excludeIds);
        if (!onOwnEndpoint && !onThirdParty) {
            return null; // already clear of every box at its along-path position — never offset
        }

        for (LabelOffsetDirection dir : LabelOffsetDirection.candidateOrder(horizontalSegment)) {
            RoutingRect moved = new RoutingRect(
                    (int) Math.round(label.x() + dir.dx * OFFSET_SCORING_DISTANCE),
                    (int) Math.round(label.y() + dir.dy * OFFSET_SCORING_DISTANCE),
                    label.width(), label.height(), label.id());
            if (onOwnEndpointBox(moved, source) || onOwnEndpointBox(moved, target)) {
                continue; // still on an endpoint box
            }
            if (overlapsNonExcluded(moved, obstacles, excludeIds)) {
                continue; // would land on a third-party element
            }
            return dir.mask;
        }
        return null; // no direction clears — leave the label unchanged (no worse than today)
    }

    /**
     * Orientation of the path segment HOSTING a Middle label — the segment containing the 50%-length point,
     * located by the same walk as {@link LabelClearance#computeLabelRect} and the assessor's hosting-segment
     * detection. Returns true when that segment is more horizontal than vertical. Using the hosting segment
     * (not the overall source→target vector) means a Z-shaped orthogonal route whose middle leg runs
     * perpendicular to its overall direction still gets the correct cardinal tried first.
     */
    private static boolean hostingSegmentHorizontal(List<AbsoluteBendpointDto> path,
            int[] sourceCenter, int[] targetCenter) {
        int n = path.size() + 2;
        int[] xs = new int[n];
        int[] ys = new int[n];
        xs[0] = sourceCenter[0];
        ys[0] = sourceCenter[1];
        for (int i = 0; i < path.size(); i++) {
            xs[i + 1] = path.get(i).x();
            ys[i + 1] = path.get(i).y();
        }
        xs[n - 1] = targetCenter[0];
        ys[n - 1] = targetCenter[1];

        double total = 0;
        for (int i = 0; i < n - 1; i++) {
            double dx = xs[i + 1] - xs[i];
            double dy = ys[i + 1] - ys[i];
            total += Math.sqrt(dx * dx + dy * dy);
        }
        double targetDist = total * 0.5;
        double accumulated = 0;
        for (int i = 0; i < n - 1; i++) {
            double dx = xs[i + 1] - xs[i];
            double dy = ys[i + 1] - ys[i];
            double segLen = Math.sqrt(dx * dx + dy * dy);
            if (accumulated + segLen >= targetDist || i == n - 2) {
                return Math.abs(dx) >= Math.abs(dy);
            }
            accumulated += segLen;
        }
        return true;
    }

    /** True if the label inset-overlaps any non-excluded (third-party) obstacle. */
    private static boolean overlapsNonExcluded(RoutingRect label, List<RoutingRect> obstacles,
            Set<String> excludeIds) {
        for (RoutingRect obs : obstacles) {
            if (obs.id() != null && excludeIds.contains(obs.id())) {
                continue;
            }
            if (insetRectOverlap(label, obs)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fraction (0..1) of the label's area overlapping the given box — the tolerant own-endpoint measure,
     * mirroring {@code LayoutQualityAssessor.ownEndpointOverlapFraction}. A label naturally grazes the box
     * it attaches to, so a small overlap is benign; only a substantial fraction means the text is rendered
     * on the body. Null box returns 0.
     */
    static double ownEndpointOverlapFraction(RoutingRect label, RoutingRect box) {
        if (box == null) {
            return 0.0;
        }
        double labelArea = (double) label.width() * label.height();
        if (labelArea <= 0) {
            return 0.0;
        }
        double ox = Math.min(label.x() + label.width(), box.x() + box.width())
                - Math.max(label.x(), box.x());
        double oy = Math.min(label.y() + label.height(), box.y() + box.height())
                - Math.max(label.y(), box.y());
        if (ox <= 0 || oy <= 0) {
            return 0.0;
        }
        return (ox * oy) / labelArea;
    }

    /**
     * Fraction (0..1) of the BOX's area overlapping the given label — the box-normalised companion to
     * {@link #ownEndpointOverlapFraction}, mirroring {@code LayoutQualityAssessor.ownEndpointBoxCoverageFraction}.
     * Where the label-area measure shrinks toward 0 as the box shrinks (missing a tiny junction fully under the
     * label), this one rises toward 1. Null box returns 0.
     */
    static double ownEndpointBoxCoverageFraction(RoutingRect label, RoutingRect box) {
        if (box == null) {
            return 0.0;
        }
        double boxArea = (double) box.width() * box.height();
        if (boxArea <= 0) {
            return 0.0;
        }
        double ox = Math.min(label.x() + label.width(), box.x() + box.width())
                - Math.max(label.x(), box.x());
        double oy = Math.min(label.y() + label.height(), box.y() + box.height())
                - Math.max(label.y(), box.y());
        if (ox <= 0 || oy <= 0) {
            return 0.0;
        }
        return (ox * oy) / boxArea;
    }

    /**
     * True when the label is rendered ON its own endpoint {@code box}: EITHER a substantial fraction of the
     * LABEL sits over the box ({@link #LABEL_OWN_ENDPOINT_OVERLAP_FRACTION}) OR a substantial fraction of the
     * (tiny) BOX sits under the label ({@link #LABEL_OWN_ENDPOINT_BOX_COVERAGE_FRACTION}). The OR closes the
     * tiny-junction gap the label-area rule alone misses; mirrors the assessor's detection so the FIX engages
     * exactly where it flags. A {@code null} box contributes nothing (both fractions return 0).
     */
    static boolean onOwnEndpointBox(RoutingRect label, RoutingRect box) {
        return ownEndpointOverlapFraction(label, box) >= LABEL_OWN_ENDPOINT_OVERLAP_FRACTION
                || ownEndpointBoxCoverageFraction(label, box) >= LABEL_OWN_ENDPOINT_BOX_COVERAGE_FRACTION;
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
