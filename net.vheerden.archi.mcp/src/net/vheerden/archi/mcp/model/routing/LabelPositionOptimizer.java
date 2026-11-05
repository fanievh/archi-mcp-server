package net.vheerden.archi.mcp.model.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * <p>After routing and label clearance, this optimizer evaluates every candidate
 * placement of each connection label and selects the one with the lowest score:
 * overlaps with element boxes and already-placed labels, the label sitting on its
 * own endpoint, and the label lying on another connection's drawn line
 * ({@link ForeignLineHits}), where it would read as that line's label. The
 * placements are the three text positions (source=0, middle=1, target=2) and, for a
 * connection whose "Label Offset" ships, the Middle label moved to each of the
 * eight compass anchors ({@link LabelOffsetDirection}). Every placement is scored
 * where Archi draws it, by the same terms. Connections are processed
 * longest-path-first (greedy order) so that labels with the most flexibility
 * are locked in first.</p>
 *
 * <p>Which connections may take an offset is the caller's to say (the platform must
 * expose the feature and the label must be shown): the entry points that take an
 * {@code offsetWritableIds} set offer the anchors only to those ids. The entry points
 * without one offer them to every labelled connection, the pure-geometry case.</p>
 *
 * <p>The other lines are the other entries it is handed, labelled or not. An entry
 * with no label text is a line only: it is never placed and never reported.</p>
 */
public class LabelPositionOptimizer {

    private static final Logger logger = LoggerFactory.getLogger(LabelPositionOptimizer.class);

    /** Inset margin applied to label bounds before overlap checks (synced with LayoutQualityAssessor). */
    static final double LABEL_OVERLAP_INSET = 10.0;
    /** Proximity threshold for near-miss scoring (synced with LayoutQualityAssessor). */
    static final double LABEL_PROXIMITY_THRESHOLD = 5.0;

    /** textPosition for a Middle label (source=0, middle=1, target=2). */
    static final int TEXT_POSITION_MIDDLE = 1;

    /** Number of candidate text positions the greedy pass evaluates per label (source, middle, target). */
    static final int TEXT_POSITION_COUNT = 3;

    /**
     * Own-endpoint overlap fraction at/above which a Middle label is treated as rendered ON its own
     * source/target box. Mirrors {@code LayoutQualityAssessor.LABEL_OWN_ENDPOINT_OVERLAP_FRACTION}, over the
     * same label box ({@link #scoredLabelRect}), so the perpendicular-offset FIX engages exactly where
     * DETECTION flags. The along-path element scoring
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
     * Penalty added to a candidate position's score for each OTHER connection whose drawn line crosses the label
     * ({@link ForeignLineHits}), once per line however many of its segments cross. The full element-overlap
     * weight: a label on a foreign line is a collision, not a near miss, and one line weighs exactly what one
     * box does. It is counted per line, so a position on two lines (2.0) loses to one on a single box (1.0):
     * the optimizer can move a label onto a box to get it off more lines. A connection's own line is never
     * foreign: a Middle label is drawn centred on it.
     */
    static final double FOREIGN_LINE_PENALTY = 1.0;

    /**
     * Result of a multi-trial label optimization pass.
     *
     * @param allPositions     connectionId → chosen textPosition for ALL labeled connections
     * @param changedPositions connectionId → new textPosition (only connections whose position changed)
     * @param offsets          connectionId → "Label Offset" anchor to write: present only for a Middle label
     *                         whose chosen anchor draws it somewhere other than the anchor it holds
     *                         ({@link LabelOffsetDirection#CENTER} for a reset). A label that keeps the
     *                         anchor it holds, or does not end at Middle, is absent.
     * @param totalScore       sum of overlap scores for all labeled connections at their chosen placements,
     *                         each scored where Archi draws it (its anchor applied at Middle)
     * @param unresolvableLabels connectionIds whose label collides at <strong>every</strong> candidate
     *                         placement in the WINNING trial — the per-connection residual the pass used to
     *                         discard. Trial-local by construction: a different processing order locks
     *                         labels in a different sequence, so this reports the trial actually chosen.
     */
    public record MultiTrialResult(Map<String, Integer> allPositions,
                            Map<String, Integer> changedPositions,
                            Map<String, Integer> offsets,
                            double totalScore,
                            Set<String> unresolvableLabels) {

        /** Compact constructor: null-guard the residual so existing callers cannot see a null set. */
        public MultiTrialResult {
            unresolvableLabels = unresolvableLabels != null ? unresolvableLabels : Set.of();
        }

        /** Backward-compatible constructor without the residual (defaults it to empty). */
        public MultiTrialResult(Map<String, Integer> allPositions,
                                Map<String, Integer> changedPositions,
                                Map<String, Integer> offsets,
                                double totalScore) {
            this(allPositions, changedPositions, offsets, totalScore, Set.of());
        }
    }

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
     * @param allObstacles every non-container view object — elements, notes and images alike
     *                     (for overlap scoring)
     * @param connectionExcludeSets per-connection exclude sets (connectionId → set of IDs to skip)
     *                              — source, target, ancestors, descendants; both ancestry
     *                              families are transitive, so a node nested at any depth inside
     *                              an endpoint is skipped, not just its direct children, and both
     *                              are filtered by geometry: a node whose rectangle is fully
     *                              disjoint from the endpoint is not skipped at all
     * @return map of connectionId → new textPosition (only includes changed positions)
     */
    Map<String, Integer> optimize(
            List<RoutingPipeline.ConnectionEndpoints> connections,
            List<List<AbsoluteBendpointDto>> paths,
            List<RoutingRect> allObstacles,
            Map<String, Set<String>> connectionExcludeSets) {
        return optimizeWithResidual(connections, paths, allObstacles, connectionExcludeSets,
                everyLabel(connections)).changedPositions();
    }

    /**
     * Outcome of a single-pass optimization: the position changes, plus the per-connection residual
     * the greedy pass computes and previously discarded.
     *
     * @param changedPositions   connectionId → new textPosition (only connections whose position changed)
     * @param unresolvableLabels connectionIds whose label collides at <strong>every</strong> candidate
     *                           placement — the labels the engine has proven it cannot place anywhere.
     *                           Always a subset of the labeled connections; never truncated.
     * @param offsets            connectionId → "Label Offset" anchor the pick chose, where it differs from
     *                           the one held (see {@link MultiTrialResult#offsets})
     */
    public record LabelOptimizationResult(Map<String, Integer> changedPositions,
                                          Set<String> unresolvableLabels,
                                          Map<String, Integer> offsets) {

        /** Compact constructor: null-guard every field. */
        public LabelOptimizationResult {
            changedPositions = changedPositions != null ? changedPositions : Map.of();
            unresolvableLabels = unresolvableLabels != null ? unresolvableLabels : Set.of();
            offsets = offsets != null ? offsets : Map.of();
        }

        /** Backward-compatible constructor without offsets (defaults them to none). */
        public LabelOptimizationResult(Map<String, Integer> changedPositions, Set<String> unresolvableLabels) {
            this(changedPositions, unresolvableLabels, Map.of());
        }
    }

    /**
     * Optimizes label positions and additionally reports which labels have <strong>no</strong>
     * collision-free position at all — the residual {@link #optimize} drops.
     *
     * <p>Placement-picking behaviour is byte-identical to {@link #optimize}; this entry point only
     * retains more of what the same pass already computed: the residual, and the anchors it chose.</p>
     *
     * <p>The foreign lines a label is scored against are those of the other entries in
     * {@code connections}. The routing pass hands it the batch it routed, so a call that routes only
     * some of a view's connections scores labels against those lines only: a connection outside the
     * batch is not seen, and a label can still be placed on its line.</p>
     *
     * @see #runGreedyPass for the definition of "collides at every candidate position"
     */
    LabelOptimizationResult optimizeWithResidual(
            List<RoutingPipeline.ConnectionEndpoints> connections,
            List<List<AbsoluteBendpointDto>> paths,
            List<RoutingRect> allObstacles,
            Map<String, Set<String>> connectionExcludeSets) {
        return optimizeWithResidual(connections, paths, allObstacles, connectionExcludeSets,
                everyLabel(connections));
    }

    /**
     * {@link #optimizeWithResidual(List, List, List, Map)} offering the eight anchors only to the connections
     * in {@code offsetWritableIds}: every other label picks among the three text positions, its Middle scored
     * at the anchor it holds (what Archi draws).
     */
    LabelOptimizationResult optimizeWithResidual(
            List<RoutingPipeline.ConnectionEndpoints> connections,
            List<List<AbsoluteBendpointDto>> paths,
            List<RoutingRect> allObstacles,
            Map<String, Set<String>> connectionExcludeSets,
            Set<String> offsetWritableIds) {

        List<int[]> labeledIndices = buildLongestFirstOrder(connections, paths);
        if (labeledIndices.isEmpty()) {
            return new LabelOptimizationResult(Map.of(), Set.of());
        }

        GreedyPassResult result = runGreedyPass(
                labeledIndices, connections, paths, allObstacles, connectionExcludeSets,
                drawnLines(connections, paths), offsetWritableIds);

        if (!result.changedPositions.isEmpty()) {
            logger.info("Label position optimization: {} labels repositioned out of {} labeled connections",
                    result.changedPositions.size(), labeledIndices.size());
        }
        if (!result.unresolvableLabels.isEmpty()) {
            logger.info("Label position optimization: {} of {} labeled connections have no collision-free "
                    + "placement among their candidates",
                    result.unresolvableLabels.size(), labeledIndices.size());
        }

        return new LabelOptimizationResult(result.changedPositions, result.unresolvableLabels, result.offsets);
    }

    /**
     * Runs multiple greedy optimization trials with different processing orders
     * and returns the result with the lowest total overlap score.
     *
     * <p>Trial 0 uses the deterministic longest-first ordering (same as {@link #optimize}).
     * Trials 1+ shuffle the ordering using the provided {@link Random}. The trial with
     * the lowest total score wins; ties are broken by preferring fewer label changes (positions and anchors).</p>
     *
     * @param connections  batch routing input (includes labelText, textPosition)
     * @param paths        corresponding routed paths (same index as connections)
     * @param allObstacles every non-container view object — elements, notes and images alike
     *                     (for overlap scoring)
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
        return optimizeMultiTrial(connections, paths, allObstacles, connectionExcludeSets, trials, rng,
                everyLabel(connections));
    }

    /**
     * {@link #optimizeMultiTrial(List, List, List, Map, int, Random)} offering the eight anchors only to the
     * connections in {@code offsetWritableIds} (see {@link #optimizeWithResidual(List, List, List, Map, Set)}).
     */
    public MultiTrialResult optimizeMultiTrial(
            List<RoutingPipeline.ConnectionEndpoints> connections,
            List<List<AbsoluteBendpointDto>> paths,
            List<RoutingRect> allObstacles,
            Map<String, Set<String>> connectionExcludeSets,
            int trials, Random rng, Set<String> offsetWritableIds) {

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
        List<List<double[]>> lines = drawnLines(connections, paths);

        for (int t = 0; t < trials; t++) {
            List<int[]> order;
            if (t == 0) {
                order = baseOrder;
            } else {
                order = new ArrayList<>(baseOrder);
                Collections.shuffle(order, rng);
            }

            GreedyPassResult passResult = runGreedyPass(
                    order, connections, paths, allObstacles, connectionExcludeSets, lines, offsetWritableIds);
            double totalScore = computeTotalScore(
                    passResult.allPositions, passResult.offsets, connections, paths,
                    allObstacles, connectionExcludeSets, lines);
            int changeCount = passResult.changedPositions.size() + passResult.offsets.size();

            if (totalScore < bestTotalScore
                    || (totalScore == bestTotalScore && changeCount < bestChangeCount)) {
                bestResult = new MultiTrialResult(
                        passResult.allPositions, passResult.changedPositions,
                        passResult.offsets, totalScore, passResult.unresolvableLabels);
                bestTotalScore = totalScore;
                bestChangeCount = changeCount;
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
                                     Map<String, Integer> offsets,
                                     Set<String> unresolvableLabels) {}

    /**
     * The label box this optimizer scores a candidate position with: every pick, the locked rect, the
     * total score and the offset search read it. It is the box the layout-quality assessor measures
     * ({@link LabelClearance#computeRenderedLabelRect}), so a label chosen here as clear is not then
     * counted as overlapping. Each label is placed where Archi draws it ({@link LabelAnchor}). Null
     * when there is no label or the path is under 1 px.
     */
    static RoutingRect scoredLabelRect(List<AbsoluteBendpointDto> path,
            RoutingRect source, RoutingRect target, String labelText, int textPosition) {
        return LabelClearance.computeRenderedLabelRect(
                path, source, target, labelText, textPosition);
    }

    /**
     * One candidate placement of a label: a text position and, at Middle, the "Label Offset" anchor, with the
     * box Archi draws it in. At Source or Target the anchor is the one the connection holds, which Archi ignores
     * there.
     */
    private record Placement(int position, int anchor, RoutingRect rect) {}

    /**
     * The label box at a placement, where Archi draws it: {@link #scoredLabelRect} at the text position, moved
     * by {@code anchor} ({@link LabelOffsetDirection#drawn(RoutingRect, int)}) when the position is a Middle
     * one (any but 0 and 2, as the assessor reads it). Null when there is no box.
     */
    private static RoutingRect placedLabelRect(List<AbsoluteBendpointDto> path, RoutingPipeline.ConnectionEndpoints conn,
            int position, int anchor) {
        RoutingRect rect = scoredLabelRect(path, conn.source(), conn.target(), conn.labelText(), position);
        if (rect == null || position == 0 || position == 2) {
            return rect;
        }
        return LabelOffsetDirection.drawn(rect, anchor);
    }

    /**
     * The placements a label is chosen among, in tie-break order: Source, Middle at Centre, Target, then (only
     * when {@code offsetShips}) the Middle label at each of the eight compass anchors in
     * {@link LabelOffsetDirection#candidateOrder} for its hosting segment. A label whose offset does not ship
     * has one Middle placement, at the anchor it holds, because that is where Archi draws it. With
     * {@code onlyPosition} 0..2 the set is cut to that text position (a caller that fixed the position).
     * A placement with no box (a path under 1 px) is left out.
     *
     * <p>No placement is scored against container borders or a group's title band: the scorer counts boxes
     * that are obstacles, labels already placed and other lines, for an anchored placement as for the three
     * text positions.</p>
     */
    private static List<Placement> candidatePlacements(RoutingPipeline.ConnectionEndpoints conn,
            List<AbsoluteBendpointDto> path, boolean offsetShips, int onlyPosition) {
        List<Placement> placements = new ArrayList<>(TEXT_POSITION_COUNT + 8);
        int held = conn.relativePosition();
        for (int pos = 0; pos < TEXT_POSITION_COUNT; pos++) {
            if (onlyPosition >= 0 && pos != onlyPosition) {
                continue;
            }
            int anchor = pos == TEXT_POSITION_MIDDLE && offsetShips ? LabelOffsetDirection.CENTER : held;
            RoutingRect rect = placedLabelRect(path, conn, pos, anchor);
            if (rect != null) {
                placements.add(new Placement(pos, anchor, rect));
            }
        }
        boolean middleOffered = onlyPosition < 0 || onlyPosition == TEXT_POSITION_MIDDLE;
        if (offsetShips && middleOffered) {
            RoutingRect middle = scoredLabelRect(path, conn.source(), conn.target(), conn.labelText(),
                    TEXT_POSITION_MIDDLE);
            if (middle != null) {
                boolean horizontalHost = hostingSegmentHorizontal(path, conn.source(), conn.target());
                for (LabelOffsetDirection dir : LabelOffsetDirection.candidateOrder(horizontalHost)) {
                    placements.add(new Placement(TEXT_POSITION_MIDDLE, dir.mask, dir.drawn(middle)));
                }
            }
        }
        return placements;
    }

    /**
     * True when {@code placement} is the one the label holds: its text position and, at Middle, an anchor
     * Archi draws in the same place as the one held (a held mask with both bits on an axis draws where its
     * winning bit does). The tie-break keeps it.
     */
    private static boolean isHeld(Placement placement, RoutingPipeline.ConnectionEndpoints conn) {
        return placement.position() == conn.textPosition()
                && (placement.position() != TEXT_POSITION_MIDDLE
                        || LabelOffsetDirection.drawsAlike(placement.anchor(), conn.relativePosition()));
    }

    /**
     * The score of a label box: {@link #scorePosition} (boxes and the labels it is scored against),
     * {@link #ownEndpointPenalty} and {@link #foreignLinePenalty}. The one score every placement is ranked by,
     * along the path or offset.
     */
    private double placementScore(RoutingRect rect, RoutingPipeline.ConnectionEndpoints conn, int ownIndex,
            List<RoutingRect> allObstacles, Set<String> excludeIds, List<RoutingRect> otherLabels,
            List<List<double[]>> lines) {
        return scorePosition(rect, allObstacles, excludeIds, otherLabels)
                + ownEndpointPenalty(rect, conn.source(), conn.target())
                + foreignLinePenalty(rect, ownIndex, lines);
    }

    /**
     * Runs a single greedy optimization pass in the given index order.
     * Returns both changed positions and all positions (for total score computation).
     *
     * <p>Each label is scored at every placement {@link #candidatePlacements} offers it, on the box Archi draws
     * for it, and the lowest score wins; ties keep the placement the label holds, then its Middle position at Centre
     * ({@link #tieRank}), and among other equal ones the first in candidate order wins. There is no separate offset step: an anchor is a placement, so it is
     * chosen against the labels already placed and other connections' lines like any other.</p>
     */
    private GreedyPassResult runGreedyPass(
            List<int[]> labeledIndices,
            List<RoutingPipeline.ConnectionEndpoints> connections,
            List<List<AbsoluteBendpointDto>> paths,
            List<RoutingRect> allObstacles,
            Map<String, Set<String>> connectionExcludeSets,
            List<List<double[]>> lines,
            Set<String> offsetWritableIds) {

        Map<String, Integer> changedPositions = new LinkedHashMap<>();
        Map<String, Integer> allPositions = new LinkedHashMap<>();
        Map<String, Integer> offsets = new LinkedHashMap<>();
        Set<String> unresolvableLabels = new LinkedHashSet<>();
        List<RoutingRect> lockedLabels = new ArrayList<>();

        for (int[] entry : labeledIndices) {
            int idx = entry[0];
            RoutingPipeline.ConnectionEndpoints conn = connections.get(idx);
            List<AbsoluteBendpointDto> path = paths.get(idx);
            Set<String> excludeIds = connectionExcludeSets.getOrDefault(
                    conn.connectionId(), Set.of());
            boolean offsetShips = offsetWritableIds.contains(conn.connectionId());

            Placement best = null;
            double bestScore = Double.MAX_VALUE;
            int bestRank = Integer.MAX_VALUE;
            int alongPath = 0;
            int blocked = 0;
            List<Placement> placements = candidatePlacements(conn, path, offsetShips, -1);
            for (Placement placement : placements) {
                if (!isOffsetPlacement(placement, offsetShips)) {
                    alongPath++;
                }
                if (hasHardCollision(placement.rect(), conn, allObstacles, excludeIds, lockedLabels)) {
                    blocked++;
                }
                double score = placementScore(placement.rect(), conn, idx, allObstacles, excludeIds, lockedLabels,
                        lines);
                int rank = tieRank(placement, conn);
                if (score < bestScore || (score == bestScore && rank < bestRank)) {
                    bestScore = score;
                    bestRank = rank;
                    best = placement;
                }
            }

            // The residual this pass used to discard. Reported only when all three text positions had a box and
            // EVERY placement evaluated carries a hard collision — see hasHardCollision for why this is not
            // "bestScore > 0". A placement whose box could not be computed proves nothing.
            if (alongPath == TEXT_POSITION_COUNT && blocked == placements.size()) {
                unresolvableLabels.add(conn.connectionId());
            }

            int bestPosition = best != null ? best.position() : conn.textPosition();
            if (best != null) {
                lockedLabels.add(best.rect());
            }

            allPositions.put(conn.connectionId(), bestPosition);
            if (bestPosition != conn.textPosition()) {
                changedPositions.put(conn.connectionId(), bestPosition);
                logger.debug("Optimized label position for connection {}: {} -> {}",
                        conn.connectionId(), conn.textPosition(), bestPosition);
            }

            // The anchor is written only at Middle, and only where it moves the label from where the held one
            // draws it (Centre for a reset). Off Middle the held anchor is left alone: Archi ignores it there.
            if (best != null && offsetShips && bestPosition == TEXT_POSITION_MIDDLE
                    && !LabelOffsetDirection.drawsAlike(best.anchor(), conn.relativePosition())) {
                offsets.put(conn.connectionId(), best.anchor());
                logger.debug("Label offset for connection {}: mask={}", conn.connectionId(), best.anchor());
            }
        }

        return new GreedyPassResult(allPositions, changedPositions, offsets, unresolvableLabels);
    }

    /**
     * How strongly a tie keeps {@code placement}: 0 for the placement the label holds ({@link #isHeld}), 1 for
     * its held Middle position at Centre (the reset of a held anchor), 2 for every other placement. Among equal
     * scores the lowest rank wins, and among equal ranks the first in candidate order: so a held placement that
     * ties the best is kept, a held anchor that scores worse than the best is reset to Centre when Centre ties
     * the best (rather than the label moved to Source or Target), and a new anchor still comes after the three
     * text positions.
     */
    private static int tieRank(Placement placement, RoutingPipeline.ConnectionEndpoints conn) {
        if (isHeld(placement, conn)) {
            return 0;
        }
        return placement.position() == conn.textPosition() && placement.anchor() == LabelOffsetDirection.CENTER
                ? 1 : 2;
    }

    /** True for one of the eight anchored Middle placements, as opposed to a text position's own placement. */
    private static boolean isOffsetPlacement(Placement placement, boolean offsetShips) {
        return offsetShips && placement.position() == TEXT_POSITION_MIDDLE
                && placement.anchor() != LabelOffsetDirection.CENTER;
    }

    /**
     * True when a label box carries a collision the engine counts as hard — the per-placement predicate behind
     * the unresolvable-label residual: an inset overlap with a third-party box, the label on its own endpoint
     * ({@link #onOwnEndpointBox}), or an inset overlap with a label already placed.
     *
     * <p><strong>Why this is not {@code score > 0}.</strong> {@link #scorePosition} deliberately mixes two
     * different weights: a genuine inset overlap contributes {@code 1.0}, but a mere <em>proximity
     * near-miss</em> contributes {@code 0.5}. Two harmless near-misses therefore total {@code 1.0} and are
     * numerically indistinguishable from one real overlap. A residual keyed on the score would hide a label
     * for passing within {@link #LABEL_PROXIMITY_THRESHOLD} of two boxes — precisely the over-suppression
     * the label family forbids. This predicate counts <em>hard</em> collisions only: a proximity near-miss
     * is not a collision.</p>
     *
     * <p><strong>Why a foreign line does not block.</strong> A label lying on another connection's line is
     * scored ({@link #FOREIGN_LINE_PENALTY}) but is not a collision here: the label is still legible, and
     * reporting it would let the hide-unplaceable-labels policy hide it.</p>
     *
     * <p>Each placement is judged on its own drawn box, an anchored Middle one included, so a label one anchor
     * lifts clear is placeable and one every anchor lands on a box or a placed label is not. Where no offset
     * ships, only the three text positions are judged.</p>
     */
    private static boolean hasHardCollision(RoutingRect labelRect, RoutingPipeline.ConnectionEndpoints conn,
            List<RoutingRect> allObstacles, Set<String> excludeIds, List<RoutingRect> placedLabels) {
        if (overlapsNonExcluded(labelRect, allObstacles, excludeIds)
                || onOwnEndpointBox(labelRect, conn.source()) || onOwnEndpointBox(labelRect, conn.target())) {
            return true;
        }
        for (RoutingRect placed : placedLabels) {
            if (insetRectOverlap(labelRect, placed)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The "Label Offset" anchor that scores best for each Middle label at a position the caller has already
     * fixed — the position-PRESERVING entry point, which never re-picks a text position. Each Middle label
     * whose offset ships (every labelled one here; see the overload) is scored at Centre and at the eight
     * anchors by the terms the greedy pass ranks placements with, against the other labels at their fixed
     * positions: those already decided at their chosen anchor, the rest at the anchor they hold. Ties keep the
     * anchor held, so a held offset that draws the label clear stays, and one that now lands it on a box loses
     * to Centre when Centre is clear (the reset). A connection absent from {@code chosenPositions} keeps its
     * current {@code textPosition()}; one with no routed path (e.g. a failed route) is skipped and is not a
     * label the others are scored against.
     *
     * <p>Pure geometry — returns the bitmask map only; the EMF write happens via
     * {@code SetTextRelativePositionCommand}.</p>
     *
     * @param connections          batch routing inputs (includes labelText, source/target rects)
     * @param pathsByConnectionId  routed absolute paths keyed by connection ID (e.g. {@code routesToApply})
     * @param allObstacles         every non-container view object — elements, notes and images alike
     *                             (for overlap scoring)
     * @param connectionExcludeSets per-connection exclude sets (source, target, ancestors,
     *                              descendants — both ancestry families transitive, and both
     *                              filtered to those that still overlap the endpoint)
     * @param chosenPositions      connectionId → chosen textPosition from the routing pass
     * @return connectionId → anchor bitmask to write, only for a Middle label whose best anchor draws it
     *         somewhere other than the one it holds ({@link LabelOffsetDirection#CENTER} for a reset)
     */
    public Map<String, Integer> computeOffsetsForPositions(
            List<RoutingPipeline.ConnectionEndpoints> connections,
            Map<String, List<AbsoluteBendpointDto>> pathsByConnectionId,
            List<RoutingRect> allObstacles,
            Map<String, Set<String>> connectionExcludeSets,
            Map<String, Integer> chosenPositions) {
        return computeOffsetsForPositions(connections, pathsByConnectionId, allObstacles, connectionExcludeSets,
                chosenPositions, everyLabel(connections));
    }

    /**
     * {@link #computeOffsetsForPositions(List, Map, List, Map, Map)} searching anchors only for the
     * connections in {@code offsetWritableIds}; every other label is scored where it is drawn, at the anchor it
     * holds.
     */
    public Map<String, Integer> computeOffsetsForPositions(
            List<RoutingPipeline.ConnectionEndpoints> connections,
            Map<String, List<AbsoluteBendpointDto>> pathsByConnectionId,
            List<RoutingRect> allObstacles,
            Map<String, Set<String>> connectionExcludeSets,
            Map<String, Integer> chosenPositions,
            Set<String> offsetWritableIds) {

        List<RoutingPipeline.ConnectionEndpoints> routed = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        for (RoutingPipeline.ConnectionEndpoints conn : connections) {
            List<AbsoluteBendpointDto> path = pathsByConnectionId.get(conn.connectionId());
            if (path != null) {
                routed.add(conn);
                paths.add(path);
            }
        }
        List<List<double[]>> lines = drawnLines(routed, paths);
        int[] positions = new int[routed.size()];
        // each label's drawn box, built once and replaced only when its anchor changes below
        RoutingRect[] drawn = new RoutingRect[routed.size()];
        for (int i = 0; i < routed.size(); i++) {
            RoutingPipeline.ConnectionEndpoints conn = routed.get(i);
            positions[i] = chosenPositions.getOrDefault(conn.connectionId(), conn.textPosition());
            drawn[i] = placedLabelRect(paths.get(i), conn, positions[i], conn.relativePosition());
        }

        Map<String, Integer> offsets = new LinkedHashMap<>();
        for (int[] entry : buildLongestFirstOrder(routed, paths)) {
            int idx = entry[0];
            RoutingPipeline.ConnectionEndpoints conn = routed.get(idx);
            if (positions[idx] != TEXT_POSITION_MIDDLE || !offsetWritableIds.contains(conn.connectionId())) {
                continue; // an offset applies only at Middle, and only where it ships
            }
            List<RoutingRect> others = new ArrayList<>();
            for (int j = 0; j < routed.size(); j++) {
                if (j != idx && drawn[j] != null) {
                    others.add(drawn[j]);
                }
            }
            Set<String> excludeIds = connectionExcludeSets.getOrDefault(conn.connectionId(), Set.of());
            Placement best = null;
            double bestScore = Double.MAX_VALUE;
            for (Placement placement : candidatePlacements(conn, paths.get(idx), true, TEXT_POSITION_MIDDLE)) {
                double score = placementScore(placement.rect(), conn, idx, allObstacles, excludeIds, others, lines);
                if (score < bestScore || (score == bestScore
                        && LabelOffsetDirection.drawsAlike(placement.anchor(), conn.relativePosition()))) {
                    bestScore = score;
                    best = placement;
                }
            }
            if (best != null && !LabelOffsetDirection.drawsAlike(best.anchor(), conn.relativePosition())) {
                drawn[idx] = placedLabelRect(paths.get(idx), conn, TEXT_POSITION_MIDDLE, best.anchor());
                offsets.put(conn.connectionId(), best.anchor());
                logger.debug("Label offset for connection {} (position-preserving): mask={}",
                        conn.connectionId(), best.anchor());
            }
        }
        return offsets;
    }

    /**
     * The total score of a given position assignment, by the measure {@link #optimizeMultiTrial} ranks its trials
     * with ({@link MultiTrialResult#totalScore}), so the two compare directly. The multi-trial search never puts the
     * assignment the labels already hold among its candidates, so its best trial can score higher than the labels as
     * they stand, or the same with labels moved; a caller that must not make a view worse by this measure scores the
     * current positions here and keeps them unless the best trial is strictly lower.
     *
     * <p>Each entry with a label and a position in {@code positions} is scored where Archi draws it, at the anchor
     * it holds ({@code relativePosition()}), against the obstacles, the other assigned labels, its own endpoints and
     * every other entry's drawn line, as {@link #computeTotalScore} does. An entry without a label is a line
     * only.</p>
     *
     * @param positions connectionId → the textPosition to score that connection's label at
     */
    public double scoreAssignment(List<RoutingPipeline.ConnectionEndpoints> connections,
            List<List<AbsoluteBendpointDto>> paths, List<RoutingRect> allObstacles,
            Map<String, Set<String>> connectionExcludeSets, Map<String, Integer> positions) {
        return scoreAssignment(connections, paths, allObstacles, connectionExcludeSets, positions, Map.of());
    }

    /**
     * {@link #scoreAssignment(List, List, List, Map, Map)} with the anchors in {@code offsets} (as
     * {@link MultiTrialResult#offsets} holds them) in place of the ones held, so an assignment with offsets is
     * compared like with like.
     */
    public double scoreAssignment(List<RoutingPipeline.ConnectionEndpoints> connections,
            List<List<AbsoluteBendpointDto>> paths, List<RoutingRect> allObstacles,
            Map<String, Set<String>> connectionExcludeSets, Map<String, Integer> positions,
            Map<String, Integer> offsets) {
        return computeTotalScore(positions, offsets, connections, paths, allObstacles, connectionExcludeSets,
                drawnLines(connections, paths));
    }

    /**
     * Computes the total overlap score for a complete position assignment, each label at the anchor it holds.
     * Evaluates every labeled connection at its assigned position against all
     * obstacles, all other assigned labels, its own endpoints and every other
     * entry's drawn line, so trials rank the way the greedy pick scores.
     */
    double computeTotalScore(
            Map<String, Integer> allPositions,
            List<RoutingPipeline.ConnectionEndpoints> connections,
            List<List<AbsoluteBendpointDto>> paths,
            List<RoutingRect> allObstacles,
            Map<String, Set<String>> connectionExcludeSets) {
        return computeTotalScore(allPositions, Map.of(), connections, paths, allObstacles, connectionExcludeSets,
                drawnLines(connections, paths));
    }

    /**
     * The total score with each label drawn at its anchor: the one in {@code offsets}, else the one it holds. The
     * anchor applies only at a Middle position, as Archi draws it.
     */
    private double computeTotalScore(
            Map<String, Integer> allPositions,
            Map<String, Integer> offsets,
            List<RoutingPipeline.ConnectionEndpoints> connections,
            List<List<AbsoluteBendpointDto>> paths,
            List<RoutingRect> allObstacles,
            Map<String, Set<String>> connectionExcludeSets,
            List<List<double[]>> lines) {

        // Build label rects for all assigned positions
        List<Integer> allIndices = new ArrayList<>();
        List<RoutingRect> allLabelRects = new ArrayList<>();
        List<Set<String>> allExcludeIds = new ArrayList<>();
        for (int i = 0; i < connections.size(); i++) {
            RoutingPipeline.ConnectionEndpoints conn = connections.get(i);
            Integer assignedPos = allPositions.get(conn.connectionId());
            if (assignedPos == null) {
                continue;
            }
            RoutingRect labelRect = placedLabelRect(paths.get(i), conn, assignedPos,
                    offsets.getOrDefault(conn.connectionId(), conn.relativePosition()));
            if (labelRect != null) {
                allIndices.add(i);
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
            RoutingPipeline.ConnectionEndpoints conn = connections.get(allIndices.get(i));

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
            totalScore += ownEndpointPenalty(labelRect, conn.source(), conn.target());

            // Penalize lying on another connection's line — the same term the greedy pick adds.
            totalScore += foreignLinePenalty(labelRect, allIndices.get(i), lines);

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
     * Scores a candidate label box against elements and the labels it is placed among.
     * Full overlap = 1.0, proximity near-miss = 0.5.
     *
     * <p>The connection's own source/target are EXCLUDED (a label always grazes the box it attaches to);
     * the own-endpoint bleed signal is supplied separately by {@link #ownEndpointPenalty}, added
     * by the callers ({@link #placementScore}, {@link #computeTotalScore}) so both rank placements the same.
     * The same callers add {@link #FOREIGN_LINE_PENALTY} for each other connection's line the label lies on:
     * this method scores boxes only, and needs the index of the label's own entry to skip its own line.</p>
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
     * Penalty for a label box rendered ON its own source/target box — the own-endpoint detection rule
     * ({@link #onOwnEndpointBox}) that {@link #scorePosition}'s element loop deliberately excludes, so without
     * this signal a label sitting squarely on its own endpoint scores 0 there and the pick never moves off it.
     * Adds {@link #LABEL_OWN_ENDPOINT_PENALTY} per bled endpoint, at every placement alike: an anchored Middle
     * placement that lifts the label off its endpoint wins on its own score, so a bleeding placement is never
     * waived in the belief that a later step will lift it.
     *
     * <p>A {@code null} box contributes nothing ({@link #onOwnEndpointBox} returns {@code false}).</p>
     */
    private static double ownEndpointPenalty(RoutingRect labelRect, RoutingRect source, RoutingRect target) {
        double penalty = 0;
        if (onOwnEndpointBox(labelRect, source)) {
            penalty += LABEL_OWN_ENDPOINT_PENALTY;
        }
        if (onOwnEndpointBox(labelRect, target)) {
            penalty += LABEL_OWN_ENDPOINT_PENALTY;
        }
        return penalty;
    }

    /** The ids of every labelled entry: the offset set of the entry points that are not handed one. */
    private static Set<String> everyLabel(List<RoutingPipeline.ConnectionEndpoints> connections) {
        Set<String> ids = new LinkedHashSet<>();
        for (RoutingPipeline.ConnectionEndpoints conn : connections) {
            if (conn.labelText() != null && !conn.labelText().isEmpty()) {
                ids.add(conn.connectionId());
            }
        }
        return ids;
    }

    /**
     * Orientation of the segment HOSTING a Middle label — the drawn line's segment
     * {@link LabelAnchor#hostingSegmentIndex} names, the one {@link #scoredLabelRect} centres the label on or
     * ends at, passing over a segment with no length. Returns true when that segment is more horizontal than vertical. Using the hosting segment
     * (not the overall source→target vector) means a Z-shaped orthogonal route whose middle leg runs
     * perpendicular to its overall direction still gets the correct cardinal tried first.
     */
    private static boolean hostingSegmentHorizontal(List<AbsoluteBendpointDto> path,
            RoutingRect source, RoutingRect target) {
        List<double[]> drawn = drawnLine(path, source, target);
        int k = LabelAnchor.hostingSegmentIndex(drawn);
        double dx = drawn.get(k + 1)[0] - drawn.get(k)[0];
        double dy = drawn.get(k + 1)[1] - drawn.get(k)[1];
        return Math.abs(dx) >= Math.abs(dy);
    }

    /**
     * The line Archi draws for an entry: the centre path with each end moved to where the orthogonal anchor
     * attaches it ({@link LabelAnchor#drawnPoints}). The one notion of the drawn line this optimizer reads, for the
     * segment hosting a label and for the lines a label must stay off.
     */
    private static List<double[]> drawnLine(List<AbsoluteBendpointDto> path, RoutingRect source, RoutingRect target) {
        return LabelAnchor.drawnPoints(
                LabelClearance.centrePath(path, new int[]{source.centerX(), source.centerY()},
                        new int[]{target.centerX(), target.centerY()}),
                LabelClearance.rect(source), LabelClearance.rect(target));
    }

    /** {@link #drawnLine} of every entry, labelled or not, by entry index. */
    private static List<List<double[]>> drawnLines(List<RoutingPipeline.ConnectionEndpoints> connections,
            List<List<AbsoluteBendpointDto>> paths) {
        List<List<double[]>> lines = new ArrayList<>(connections.size());
        for (int i = 0; i < connections.size(); i++) {
            RoutingPipeline.ConnectionEndpoints conn = connections.get(i);
            lines.add(drawnLine(paths.get(i), conn.source(), conn.target()));
        }
        return lines;
    }

    /**
     * {@link #FOREIGN_LINE_PENALTY} for each other entry whose drawn line crosses the label
     * ({@link ForeignLineHits#crosses}); entry {@code ownIndex}'s own line is skipped.
     */
    private static double foreignLinePenalty(RoutingRect label, int ownIndex, List<List<double[]>> lines) {
        double penalty = 0;
        for (int j = 0; j < lines.size(); j++) {
            if (j != ownIndex
                    && ForeignLineHits.crosses(label.x(), label.y(), label.width(), label.height(), lines.get(j))) {
                penalty += FOREIGN_LINE_PENALTY;
            }
        }
        return penalty;
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
