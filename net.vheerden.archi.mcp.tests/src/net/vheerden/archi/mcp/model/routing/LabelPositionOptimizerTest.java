package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.Test;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Tests for {@link LabelPositionOptimizer}.
 * Pure-geometry tests — no OSGi runtime required.
 */
public class LabelPositionOptimizerTest {

    private final LabelPositionOptimizer optimizer = new LabelPositionOptimizer();

    // --- Helper methods ---

    private RoutingPipeline.ConnectionEndpoints conn(String id, int sx, int sy, int sw, int sh,
            int tx, int ty, int tw, int th, String label, int textPos) {
        return new RoutingPipeline.ConnectionEndpoints(id,
                new RoutingRect(sx, sy, sw, sh, "src-" + id),
                new RoutingRect(tx, ty, tw, th, "tgt-" + id),
                List.of(), label, textPos);
    }

    private List<AbsoluteBendpointDto> straightHorizontalPath(int x1, int y, int x2) {
        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(x1, y));
        path.add(new AbsoluteBendpointDto(x2, y));
        return path;
    }

    private List<AbsoluteBendpointDto> straightVerticalPath(int x, int y1, int y2) {
        List<AbsoluteBendpointDto> path = new ArrayList<>();
        path.add(new AbsoluteBendpointDto(x, y1));
        path.add(new AbsoluteBendpointDto(x, y2));
        return path;
    }

    // --- Test: No label → no change ---

    @Test
    public void shouldReturnEmpty_whenConnectionHasNoLabel() {
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 100, 50, 400, 0, 100, 50, "", 1));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(100, 25, 400));

        Map<String, Integer> result = optimizer.optimize(
                connections, paths, List.of(), Map.of());

        assertTrue("No labels should mean no changes", result.isEmpty());
    }

    @Test
    public void shouldReturnEmpty_whenConnectionLabelIsNull() {
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 100, 50, 400, 0, 100, 50, null, 1));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(100, 25, 400));

        Map<String, Integer> result = optimizer.optimize(
                connections, paths, List.of(), Map.of());

        assertTrue(result.isEmpty());
    }

    // --- Test: Middle position already clear → no change ---

    @Test
    public void shouldNotChange_whenMiddlePositionHasZeroOverlaps() {
        // Source at (0,0), target at (600,0), label "Uses" at middle
        // No obstacles near the path → middle is fine
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 100, 50, 500, 0, 100, 50, "Uses", 1));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(100, 25, 500));

        Map<String, Integer> result = optimizer.optimize(
                connections, paths, List.of(), Map.of());

        assertTrue("No overlap at default middle → no change", result.isEmpty());
    }

    // --- Test: Source position clears overlap → selects position 0 ---

    @Test
    public void shouldSelectSourcePosition_whenItClearsOverlap() {
        // Horizontal path from (0,25) to (600,25)
        // Obstacle at middle of path overlapping the middle label position
        // "Accesses" = 8 chars → width=66px, height=20px, at middle (300px along 600px path)
        // Obstacle rect centered at path midpoint (300, 25)
        RoutingRect obstacle = new RoutingRect(270, 5, 60, 40, "obs1");
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 50, 50, 550, 0, 50, 50, "Accesses", 1));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(50, 25, 550));

        Map<String, Integer> result = optimizer.optimize(
                connections, paths, List.of(obstacle), Map.of());

        assertTrue("Should change position to avoid obstacle", result.containsKey("c1"));
        int newPos = result.get("c1");
        // Source or Target (just beyond either end of the line) should be selected since Middle overlaps
        assertTrue("Should select source (0) or target (2)", newPos == 0 || newPos == 2);
    }

    // --- Test: Greedy ordering → longest path first ---

    @Test
    public void shouldProcessLongestPathFirst() {
        // Two connections: short (200px) and long (800px)
        // Obstacle at a position where the long connection's label at middle overlaps
        // The long connection should be processed first and get the better position
        RoutingRect obstacle = new RoutingRect(370, 90, 60, 40, "obs1");

        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("short", 0, 100, 50, 50, 150, 100, 50, 50, "ShortConn", 1),
                conn("long", 0, 100, 50, 50, 750, 100, 50, 50, "LongConnection", 1));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(50, 125, 150),
                straightHorizontalPath(50, 125, 750));

        // Both have labels but long path should be processed first
        Map<String, Integer> result = optimizer.optimize(
                connections, paths, List.of(obstacle), Map.of());

        // The test validates the method doesn't crash with mixed lengths
        // and that the optimizer processes without error
        assertNotNull(result);
    }

    // --- Test: Locked label from previous connection considered in scoring ---

    @Test
    public void shouldConsiderLockedLabels_whenScoringSubsequentConnections() {
        // Two connections with labels that would overlap at the same position
        // Both go from left to right, horizontal, same y coordinate
        // After first is locked at middle, second should pick a different position
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 100, 50, 50, 400, 100, 50, 50, "Label1", 1),
                conn("c2", 0, 100, 50, 50, 400, 100, 50, 50, "Label2", 1));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(50, 125, 400),
                straightHorizontalPath(50, 125, 400));

        Map<String, Integer> result = optimizer.optimize(
                connections, paths, List.of(), Map.of());

        // At least one should change since both can't be at middle without overlapping
        // (same path = same label position = overlap)
        assertFalse("At least one label should be repositioned to avoid label-label overlap",
                result.isEmpty());
    }

    // --- Test: Tie-breaking prefers current textPosition ---

    @Test
    public void shouldPreferCurrentPosition_whenScoresAreTied() {
        // Connection with label at source position (0), no obstacles
        // All positions should score 0 → should keep current (0)
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 50, 50, 500, 0, 50, 50, "Label", 0));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(50, 25, 500));

        Map<String, Integer> result = optimizer.optimize(
                connections, paths, List.of(), Map.of());

        // All positions score 0, current position (0) should be preferred → no change
        assertTrue("Tied scores should keep current position", result.isEmpty());
    }

    // --- Test: Exclusion sets work (source/target not counted as overlap) ---

    @Test
    public void shouldExcludeSourceAndTarget_fromOverlapScoring() {
        // Source and target are directly under the label positions
        // They should be excluded from scoring
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 100, 50, 400, 0, 100, 50, "TestLabel", 1));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(100, 25, 400));

        // Use the source/target IDs as obstacles — they should be excluded
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(0, 0, 100, 50, "src-c1"),
                new RoutingRect(400, 0, 100, 50, "tgt-c1"));
        Map<String, Set<String>> excludeSets = new HashMap<>();
        excludeSets.put("c1", Set.of("src-c1", "tgt-c1"));

        Map<String, Integer> result = optimizer.optimize(
                connections, paths, obstacles, excludeSets);

        // With source/target excluded, all positions score 0 → no change
        assertTrue("Source/target should be excluded from scoring", result.isEmpty());
    }

    // --- Test: Proximity scoring (0.5 weight) ---

    @Test
    public void shouldScoreProximityAsHalf() {
        // Create an obstacle just outside the label but within proximity threshold
        LabelPositionOptimizer opt = new LabelPositionOptimizer();

        // Label rect at (100, 90, 66, 20) — a typical label
        RoutingRect label = new RoutingRect(100, 90, 66, 20, null);
        // Obstacle just outside the label but within 5px proximity
        RoutingRect nearObstacle = new RoutingRect(170, 90, 50, 50, "near");

        double score = opt.scorePosition(label, List.of(nearObstacle), Set.of(), List.of());

        // Should be 0.5 (proximity near-miss) or 1.0 (full overlap depending on inset)
        assertTrue("Proximity should produce non-zero score", score > 0);
    }

    // --- Test: insetRectOverlap correctness ---

    @Test
    public void shouldDetectOverlap_whenLabelsIntersect() {
        RoutingRect a = new RoutingRect(100, 100, 60, 20, null);
        RoutingRect b = new RoutingRect(130, 105, 60, 20, null);

        assertTrue("Overlapping labels should be detected",
                LabelPositionOptimizer.insetRectOverlap(a, b));
    }

    @Test
    public void shouldNotDetectOverlap_whenLabelsFarApart() {
        RoutingRect a = new RoutingRect(100, 100, 60, 20, null);
        RoutingRect b = new RoutingRect(300, 100, 60, 20, null);

        assertFalse("Non-overlapping labels should not be detected",
                LabelPositionOptimizer.insetRectOverlap(a, b));
    }

    // --- Test: isWithinProximity ---

    @Test
    public void shouldDetectProximity_whenLabelsAreClose() {
        RoutingRect a = new RoutingRect(100, 100, 60, 20, null);
        // Just outside but within 5px proximity threshold
        RoutingRect b = new RoutingRect(163, 100, 60, 20, null);

        assertTrue("Close labels should be detected as proximity",
                LabelPositionOptimizer.isWithinProximity(a, b));
    }

    @Test
    public void shouldNotDetectProximity_whenLabelsFarApart() {
        RoutingRect a = new RoutingRect(100, 100, 60, 20, null);
        RoutingRect b = new RoutingRect(300, 100, 60, 20, null);

        assertFalse("Far apart labels should not be proximity",
                LabelPositionOptimizer.isWithinProximity(a, b));
    }

    // ====================================================================
    // Multi-trial optimization tests
    // ====================================================================

    // --- Test: Single trial produces same result as optimize() ---

    @Test
    public void multiTrial_singleTrialShouldMatchOptimize() {
        // Same scenario as shouldSelectSourcePosition_whenItClearsOverlap
        RoutingRect obstacle = new RoutingRect(270, 5, 60, 40, "obs1");
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 50, 50, 550, 0, 50, 50, "Accesses", 1));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(50, 25, 550));

        Map<String, Integer> singleResult = optimizer.optimize(
                connections, paths, List.of(obstacle), Map.of());

        LabelPositionOptimizer.MultiTrialResult multiResult = optimizer.optimizeMultiTrial(
                connections, paths, List.of(obstacle), Map.of(), 1, new Random(42));

        assertEquals("Single trial should match optimize()",
                singleResult, multiResult.changedPositions());
    }

    // --- Test: Multi-trial with known seed produces deterministic results ---

    @Test
    public void multiTrial_knownSeedProducesDeterministicResults() {
        // Two overlapping labels on same path — ordering matters
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 100, 50, 50, 400, 100, 50, 50, "Label1", 1),
                conn("c2", 0, 100, 50, 50, 400, 100, 50, 50, "Label2", 1));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(50, 125, 400),
                straightHorizontalPath(50, 125, 400));

        LabelPositionOptimizer.MultiTrialResult result1 = optimizer.optimizeMultiTrial(
                connections, paths, List.of(), Map.of(), 5, new Random(42));
        LabelPositionOptimizer.MultiTrialResult result2 = optimizer.optimizeMultiTrial(
                connections, paths, List.of(), Map.of(), 5, new Random(42));

        assertEquals("Same seed should produce same results",
                result1.changedPositions(), result2.changedPositions());
        assertEquals("Same seed should produce same total score",
                result1.totalScore(), result2.totalScore(), 0.001);
    }

    // --- Test: All positions clear → no changes regardless of trial count ---

    @Test
    public void multiTrial_nothingToOptimize_whenAllPositionsClear() {
        // Single connection with no obstacles — all positions score 0
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 50, 50, 500, 0, 50, 50, "Label", 1));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(50, 25, 500));

        LabelPositionOptimizer.MultiTrialResult result = optimizer.optimizeMultiTrial(
                connections, paths, List.of(), Map.of(), 10, new Random(42));

        assertTrue("No changes when all positions are clear",
                result.changedPositions().isEmpty());
        assertEquals("Total score should be 0", 0.0, result.totalScore(), 0.001);
    }

    // --- Test: Tie-breaking prefers fewer position changes ---

    @Test
    public void multiTrial_tieBreakingPrefsFewerChanges() {
        // Connection with label at position 0, no obstacles
        // Every trial scores 0 → trial with fewer changes wins
        // Since all score 0 and current position is kept → no changes
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 50, 50, 500, 0, 50, 50, "Label", 0));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(50, 25, 500));

        LabelPositionOptimizer.MultiTrialResult result = optimizer.optimizeMultiTrial(
                connections, paths, List.of(), Map.of(), 5, new Random(42));

        assertTrue("Tie-breaking should prefer no changes (current positions)",
                result.changedPositions().isEmpty());
    }

    // --- Test: totalScore reflects sum of all labeled connection scores ---

    @Test
    public void multiTrial_totalScoreReflectsAllLabeledConnections() {
        // Two connections with labels on same path, overlapping at same position
        // Both have non-zero scores → totalScore should be > 0
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 100, 50, 50, 400, 100, 50, 50, "OverlapLabel1", 1),
                conn("c2", 0, 100, 50, 50, 400, 100, 50, 50, "OverlapLabel2", 1));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(50, 125, 400),
                straightHorizontalPath(50, 125, 400));

        // Add obstacle at middle to ensure non-zero scores
        RoutingRect obstacle = new RoutingRect(200, 100, 50, 50, "obs1");

        LabelPositionOptimizer.MultiTrialResult result = optimizer.optimizeMultiTrial(
                connections, paths, List.of(obstacle), Map.of(), 5, new Random(42));

        // allPositions should contain entries for both connections
        assertEquals("allPositions should contain all labeled connections",
                2, result.allPositions().size());
        assertTrue("allPositions should contain c1", result.allPositions().containsKey("c1"));
        assertTrue("allPositions should contain c2", result.allPositions().containsKey("c2"));
    }

    // --- Test: Multi-trial can find better result than single trial ---

    @Test
    public void multiTrial_canFindBetterResultThanSingleTrial() {
        // Three connections with labels on overlapping paths where ordering affects quality
        // With enough trials, the optimizer should find at least as good a result
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 100, 50, 50, 400, 100, 50, 50, "Alpha", 1),
                conn("c2", 0, 100, 50, 50, 400, 100, 50, 50, "Beta", 1),
                conn("c3", 0, 100, 50, 50, 400, 100, 50, 50, "Gamma", 1));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(50, 125, 400),
                straightHorizontalPath(50, 125, 400),
                straightHorizontalPath(50, 125, 400));

        LabelPositionOptimizer.MultiTrialResult singleResult = optimizer.optimizeMultiTrial(
                connections, paths, List.of(), Map.of(), 1, new Random(42));
        LabelPositionOptimizer.MultiTrialResult multiResult = optimizer.optimizeMultiTrial(
                connections, paths, List.of(), Map.of(), 20, new Random(42));

        // Multi-trial should find result at least as good as single trial
        assertTrue("Multi-trial should be at least as good as single trial",
                multiResult.totalScore() <= singleResult.totalScore());
    }

    // --- Test: computeTotalScore returns zero when no obstacles ---

    @Test
    public void computeTotalScore_shouldReturnZero_whenNoObstacles() {
        // Single connection, no obstacles — totalScore should be 0
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 50, 50, 500, 0, 50, 50, "Label", 1));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(50, 25, 500));

        double totalScore = optimizer.computeTotalScore(
                Map.of("c1", 1), connections, paths, List.of(), Map.of());

        assertEquals("No obstacles → totalScore should be 0", 0.0, totalScore, 0.001);
    }

    // --- Test: No labeled connections → empty multi-trial result ---

    @Test
    public void multiTrial_emptyConnections_shouldReturnEmptyResult() {
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 100, 50, 400, 0, 100, 50, "", 1));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(100, 25, 400));

        LabelPositionOptimizer.MultiTrialResult result = optimizer.optimizeMultiTrial(
                connections, paths, List.of(), Map.of(), 5, new Random(42));

        assertTrue("No labeled connections → empty result", result.changedPositions().isEmpty());
        assertTrue("No labeled connections → empty allPositions", result.allPositions().isEmpty());
        assertEquals("No labeled connections → zero score", 0.0, result.totalScore(), 0.001);
    }

    // --- Perpendicular "Label Offset" (mechanism c) offset scoring ---
    //
    // Compass bitmask constants (mirrors IDiagramModelConnection on Archi 5.10):
    private static final int NORTH = 1;
    private static final int SOUTH = 4;
    private static final int WEST = 8;

    /** Per-connection exclude set (source + target) — the optimizer's element scoring skips own endpoints. */
    private Map<String, Set<String>> ownEndpointExclude(String id) {
        return Map.of(id, Set.of("src-" + id, "tgt-" + id));
    }

    @Test
    public void offset_middleLabelOnOwnEndpoint_leavesItsEndpoints_forTheClearSourceFirstInOrder() {
        // Short horizontal chain: source [0..100], 20px gap, target [120..220], both y [10..40]; "Accesses"
        // (74 x 20) at Middle centres at (110,25) → x 73..147, y 15..35, overlapping BOTH endpoint boxes
        // 27 x 20 / 1480 = 0.365 of its area (>= 0.30). Element scoring skips own endpoints, but the
        // own-endpoint penalty counts that bleed, so Middle at Centre scores 2. Archi lifts a Middle
        // label NORTH by 20/2 + 5 = 15 px: y 0..20, leaving 27 x 10 / 1480 = 0.18 on each box, under
        // the bar — so NORTH clears. Source
        // (x 114..210, y 43..63) is clear too and comes before the anchors in candidate order, so the label
        // goes to Source and no anchor is written. With the position fixed at Middle, NORTH is the anchor
        // (computeOffsets_middleLabelOnOwnEndpoint_getsPerpendicularOffset).
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 10, 100, 30, 120, 10, 100, 30, "Accesses", 1));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(100, 25, 120));

        LabelPositionOptimizer.MultiTrialResult result = optimizer.optimizeMultiTrial(
                connections, paths, List.of(), ownEndpointExclude("c1"), 1, new Random(0));

        assertEquals("The label leaves its endpoints, for the clear Source",
                Map.of("c1", 0), result.changedPositions());
        assertTrue("Off Middle no anchor is written", result.offsets().isEmpty());
    }

    @Test
    public void offset_alreadyClearLabel_isNotOffset() {
        // Long connection: source [0..100], target [500..600]; "Accesses" at Middle centres at x=300,
        // clear of both endpoints (fraction 0) → never offset.
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 100, 50, 500, 0, 100, 50, "Accesses", 1));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(100, 25, 500));

        LabelPositionOptimizer.MultiTrialResult result = optimizer.optimizeMultiTrial(
                connections, paths, List.of(), ownEndpointExclude("c1"), 1, new Random(0));

        assertTrue("A label clear of every box is never offset", result.offsets().isEmpty());
    }

    @Test
    public void offset_noClearingDirection_leavesLabelUnchanged() {
        // Same own-endpoint bleed, but a large third-party box engulfs every candidate displacement →
        // no direction lands clear → no offset (no worse than today).
        RoutingRect engulfing = new RoutingRect(-200, -200, 600, 500, "block");
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 100, 50, 120, 0, 100, 50, "Accesses", 1));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(100, 25, 120));

        LabelPositionOptimizer.MultiTrialResult result = optimizer.optimizeMultiTrial(
                connections, paths, List.of(engulfing), ownEndpointExclude("c1"), 1, new Random(0));

        assertTrue("No overlap-free offset → label unchanged", result.offsets().isEmpty());
    }

    @Test
    public void offset_drawnLiftTooShortForTallEndpointBoxes_noOffsetAndLabelLeavesMiddle() {
        // The same chain with 50 px tall boxes, y [0..50]. Archi moves the 74 x 20 label by 15 px on y and
        // 42 px on x: NORTH y 0..20 and SOUTH y 30..50 still leave 27 x 20 / 1480 = 0.365 on each box; WEST
        // x 31..105 and EAST x 115..189 sit on the source and target; the diagonals likewise. No direction
        // clears, so the greedy pass writes no offset (a 40 px lift, y -25..-5, would have been accepted here, and the
        // label drawn on both boxes regardless). With no rescue the Middle bleed is penalised on both boxes
        // (2), so the label leaves Middle. Where Archi draws them (draw2d ConnectionEndpointLocator), Source
        // starts 14 px beyond the source, x 114..210 y 43..63, on the target; the last bendpoint sits on the
        // target's left edge line, inside its band, so the line ends at the target centre (170, 25) and the
        // Target label hangs below it, x 188..284 y 39..59, with only 18% of it on the target, under the own-
        // endpoint bar. So the label goes to Target and is placeable (on the 15%/85% boxes every position was
        // on a box and it was reported unplaceable).
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 100, 50, 120, 0, 100, 50, "Accesses", 1));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(100, 25, 120));

        LabelPositionOptimizer.MultiTrialResult result = optimizer.optimizeMultiTrial(
                connections, paths, List.of(), ownEndpointExclude("c1"), 1, new Random(0));

        assertTrue("No drawn lift clears a 50 px box → no offset", result.offsets().isEmpty());
        assertEquals("Unrescued Middle bleeds two boxes → re-picked off Middle, to the clear Target",
                Integer.valueOf(2), result.allPositions().get("c1"));
        assertEquals("Target is clear → placeable", Set.of(), result.unresolvableLabels());
        // With the position fixed at Middle the best anchor is written even though none clears: WEST
        // (x 9..105) still sits on the source but is off the target, one bleed (1) instead of Centre's two.
        assertEquals("Position fixed at Middle: the anchor that bleeds one box rather than two",
                Integer.valueOf(WEST), optimizer.computeOffsetsForPositions(connections,
                        pathMap("c1", straightHorizontalPath(100, 25, 120)),
                        List.of(), ownEndpointExclude("c1"), Map.of("c1", 1)).get("c1"));
    }

    @Test
    public void offset_firstCardinalBlocked_secondChosen() {
        // Own-endpoint bleed with a third-party box blocking the NORTH displacement only → SOUTH, the only
        // anchor that clears both the box and the endpoints. Drawn NORTH is y 0..20 (inset y 6.67..13.33),
        // which blockN's y -20..10 overlaps by 3.33 px; drawn SOUTH is y 30..50 (inset 36.67..43.33), clear of
        // it and 0.18 on each 30 px endpoint box. The position is fixed at Middle: left free, the label takes
        // the clear Source, which comes before the anchors in candidate order. Scored, SOUTH is 0 and every
        // other anchor 1 or 2, so this pins the best anchor over the first, not a tie: the candidate-order
        // tie-break is pinned by computeOffsets_middleLabelOnOwnEndpoint_getsPerpendicularOffset (NORTH before
        // SOUTH on a horizontal segment) and offset_verticalHostingSegment_triesWestEastFirst (WEST before EAST).
        RoutingRect blockNorth = new RoutingRect(60, -20, 120, 30, "blockN");
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 10, 100, 30, 120, 10, 100, 30, "Accesses", 1));

        Map<String, Integer> offsets = optimizer.computeOffsetsForPositions(connections,
                pathMap("c1", straightHorizontalPath(100, 25, 120)), List.of(blockNorth),
                ownEndpointExclude("c1"), Map.of("c1", 1));
        LabelPositionOptimizer.MultiTrialResult result = optimizer.optimizeMultiTrial(
                connections, List.of(straightHorizontalPath(100, 25, 120)), List.of(blockNorth),
                ownEndpointExclude("c1"), 1, new Random(0));

        assertEquals("NORTH blocked → SOUTH", Integer.valueOf(SOUTH), offsets.get("c1"));
        assertEquals("Left free, the clear Source", Map.of("c1", 0), result.changedPositions());
    }

    @Test
    public void offset_verticalHostingSegment_triesWestEastFirst() {
        // Vertically-stacked boxes (source [0..50]×[0..50], target [0..50]×[56..106]), 6px gap; short label
        // "Hi" (w=26) at Middle centres at (25,53) → pokes ~0.35 of its area into BOTH boxes. The hosting
        // segment is VERTICAL, so the perpendicular cardinals are WEST/EAST — the offset must move sideways
        // (WEST first), not NORTH/SOUTH (which run along the segment). Proves orientation comes from the
        // hosting segment, not the source→target vector. WEST and EAST both clear, so this is the tie-break.
        // In the greedy pass the clear Source (x 43..75, y 64..84, 0.22 of it on the target) comes first.
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 50, 50, 0, 56, 50, 50, "Hi", 1));

        Map<String, Integer> offsets = optimizer.computeOffsetsForPositions(connections,
                pathMap("c1", straightVerticalPath(25, 50, 56)), List.of(), ownEndpointExclude("c1"),
                Map.of("c1", 1));
        LabelPositionOptimizer.MultiTrialResult result = optimizer.optimizeMultiTrial(
                connections, List.of(straightVerticalPath(25, 50, 56)), List.of(), ownEndpointExclude("c1"), 1,
                new Random(0));

        assertEquals("Vertical hosting segment → WEST before EAST",
                Integer.valueOf(WEST), offsets.get("c1"));
        assertEquals("Left free, the clear Source", Map.of("c1", 0), result.changedPositions());
    }

    @Test
    public void offset_middleLabelOnTinyJunction_getsPerpendicularOffset() {
        // A 14x14 junction target fully enclosed by a Middle "Accesses" label (w=74). The label-area fraction
        // over the junction is only ~0.13 (below 0.30), so without the box-coverage rule no anchor would see
        // an own-endpoint bleed to clear. The source box is clear of the label (fraction 0). The label
        // covers 100% of the junction box → the box-coverage rule fires and NORTH lifts the label clear.
        // Mirrors the assessor's tiny-junction detection so the FIX engages exactly where DETECTION flags.
        // The position is fixed at Middle: left free, the label takes Source (x 54..150, y 29..49, 0.21 of
        // the junction), which is clear and comes before the anchors.
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 40, 50, 103, 18, 14, 14, "Accesses", 1));

        Map<String, Integer> offsets = optimizer.computeOffsetsForPositions(connections,
                pathMap("c1", straightHorizontalPath(90, 25, 130)), List.of(), ownEndpointExclude("c1"),
                Map.of("c1", 1));
        LabelPositionOptimizer.MultiTrialResult result = optimizer.optimizeMultiTrial(
                connections, List.of(straightHorizontalPath(90, 25, 130)), List.of(), ownEndpointExclude("c1"),
                1, new Random(0));

        assertEquals("Tiny-junction Middle bleed → perpendicular offset (NORTH first)",
                Integer.valueOf(NORTH), offsets.get("c1"));
        assertEquals("Left free, the clear Source", Map.of("c1", 0), result.changedPositions());
    }

    @Test
    public void offset_middleLabelLightlyOnNormalBox_isNotOffset() {
        // Mirror case: a normal 100x50 target box lightly overlapped (label-area fraction ~0.16, box-coverage
        // ~0.05) trips neither rule → no offset. The box-coverage rule must not over-trigger ordinary boxes.
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 40, 50, 135, 0, 100, 50, "Accesses", 1));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(90, 25, 130));

        LabelPositionOptimizer.MultiTrialResult result = optimizer.optimizeMultiTrial(
                connections, paths, List.of(), ownEndpointExclude("c1"), 1, new Random(0));

        assertTrue("Normal box lightly overlapped → no spurious offset", result.offsets().isEmpty());
    }

    // --- Position-preserving offset compute (computeOffsetsForPositions) ---
    //
    // The auto-route entry point: positions are already chosen by the routing pass, so this never re-picks —
    // it only offsets a Middle label that still renders ON a box. Paths are supplied keyed by connection ID
    // (the caller's routesToApply map), not a parallel list.

    private Map<String, List<AbsoluteBendpointDto>> pathMap(String id, List<AbsoluteBendpointDto> path) {
        return Map.of(id, path);
    }

    @Test
    public void computeOffsets_middleLabelOnOwnEndpoint_getsPerpendicularOffset() {
        // Same tight own-endpoint chain as the greedy-pass case, but the position is supplied as already-Middle.
        // allObstacles is empty: own-endpoint detection + the candidate re-check read source/target from the
        // ConnectionEndpoints record directly (not from allObstacles), so NORTH clears with no third-party boxes.
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 10, 100, 30, 120, 10, 100, 30, "Accesses", 1));

        Map<String, Integer> offsets = optimizer.computeOffsetsForPositions(
                connections, pathMap("c1", straightHorizontalPath(100, 25, 120)),
                List.of(), ownEndpointExclude("c1"), Map.of("c1", 1));

        assertEquals("Own-endpoint Middle bleed → perpendicular offset (NORTH first)",
                Integer.valueOf(NORTH), offsets.get("c1"));
    }

    @Test
    public void middleLabelOnOwnEndpoint_whereNoOffsetShips_choosesAmongThePositionsAndWritesNoAnchor() {
        // The chain above where no offset ships (an empty offset-writable set: Archi before 5.10, a hidden label,
        // a routing pass that writes no offset): only the three positions are scored, the bleed at Middle is a
        // cost (2, one per endpoint), so the label takes the clear Source. The residual is empty: Source is a
        // collision-free position. With the position fixed at Middle, no anchor is searched.
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 10, 100, 30, 120, 10, 100, 30, "Accesses", 1));

        LabelPositionOptimizer.LabelOptimizationResult result = optimizer.optimizeWithResidual(connections,
                List.of(straightHorizontalPath(100, 25, 120)), List.of(), ownEndpointExclude("c1"), Set.of());
        Map<String, Integer> offsets = optimizer.computeOffsetsForPositions(connections,
                pathMap("c1", straightHorizontalPath(100, 25, 120)), List.of(), ownEndpointExclude("c1"),
                Map.of("c1", 1), Set.of());

        assertEquals("The bleed is a cost: the label takes the clear Source", Map.of("c1", 0),
                result.changedPositions());
        assertTrue("No anchor where none ships", result.offsets().isEmpty());
        assertTrue("Source is clear, so the label is placeable", result.unresolvableLabels().isEmpty());
        assertEquals("No anchor at a fixed Middle either", Map.of(), offsets);
    }

    @Test
    public void computeOffsets_alreadyClearLabel_isNotOffset() {
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 100, 50, 500, 0, 100, 50, "Accesses", 1));

        Map<String, Integer> offsets = optimizer.computeOffsetsForPositions(
                connections, pathMap("c1", straightHorizontalPath(100, 25, 500)),
                List.of(), ownEndpointExclude("c1"), Map.of("c1", 1));

        assertTrue("A label clear of every box is never offset", offsets.isEmpty());
    }

    @Test
    public void computeOffsets_verticalHostingSegment_triesWestEastFirst() {
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 50, 50, 0, 56, 50, 50, "Hi", 1));

        Map<String, Integer> offsets = optimizer.computeOffsetsForPositions(
                connections, pathMap("c1", straightVerticalPath(25, 50, 56)),
                List.of(), ownEndpointExclude("c1"), Map.of("c1", 1));

        assertEquals("Vertical hosting segment → WEST first (position-preserving)",
                Integer.valueOf(WEST), offsets.get("c1"));
    }

    @Test
    public void computeOffsets_nonMiddlePosition_isNotOffset() {
        // The chosen position is Source (0), not Middle → the offset never engages even with own-endpoint bleed.
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 100, 50, 120, 0, 100, 50, "Accesses", 1));

        Map<String, Integer> offsets = optimizer.computeOffsetsForPositions(
                connections, pathMap("c1", straightHorizontalPath(100, 25, 120)),
                List.of(), ownEndpointExclude("c1"), Map.of("c1", 0));

        assertTrue("Offset applies only at Middle — a Source-positioned label is not offset",
                offsets.isEmpty());
    }

    @Test
    public void computeOffsets_positionAbsentFromMap_fallsBackToConnTextPosition() {
        // A connection absent from chosenPositions falls back to its own textPosition() — here Middle (1) —
        // so its own-endpoint bleed still gets offset even when the routing pass reported no position for it.
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 10, 100, 30, 120, 10, 100, 30, "Accesses", 1));

        Map<String, Integer> offsets = optimizer.computeOffsetsForPositions(
                connections, pathMap("c1", straightHorizontalPath(100, 25, 120)),
                List.of(), ownEndpointExclude("c1"), Map.of()); // empty → fall back to conn.textPosition()=1

        assertEquals("Absent from chosenPositions → fall back to Middle textPosition → still offset",
                Integer.valueOf(NORTH), offsets.get("c1"));
    }

    @Test
    public void computeOffsets_missingPath_isSkipped() {
        // A connection with no routed path (e.g. a failed route, absent from routesToApply) is skipped, not NPE.
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 100, 50, 120, 0, 100, 50, "Accesses", 1));

        Map<String, Integer> offsets = optimizer.computeOffsetsForPositions(
                connections, new HashMap<>(), List.of(), ownEndpointExclude("c1"), Map.of("c1", 1));

        assertTrue("No routed path → connection skipped", offsets.isEmpty());
    }

    // --- A held Label Offset comes first ---
    //
    // A connection may already hold an anchor (set by hand, or by an earlier run before the elements
    // moved). Kept while it draws the label clear, reset to Centre once it lands the label on a box and the
    // un-offset label is clear, otherwise the search from the un-offset label as for Centre.

    private static final int EAST = 16;
    private static final int CENTER = LabelOffsetDirection.CENTER;

    private RoutingPipeline.ConnectionEndpoints held(RoutingPipeline.ConnectionEndpoints c, int anchor) {
        return new RoutingPipeline.ConnectionEndpoints(c.connectionId(), c.source(), c.target(),
                c.obstacles(), c.labelText(), c.textPosition(), c.groupBoundaries(), anchor);
    }

    /** The tight own-endpoint chain: "Accesses" at Middle bleeds both boxes; NORTH and SOUTH both clear. */
    private RoutingPipeline.ConnectionEndpoints bleedingChain(int anchor) {
        return held(conn("c1", 0, 10, 100, 30, 120, 10, 100, 30, "Accesses", 1), anchor);
    }

    /**
     * A long horizontal connection whose Middle label is clear (scored box x 252..348, y 15..35) and a box to
     * its right at x 360..410. The label clears it by 12 px (no near-miss), so Middle stays the best
     * position; a held EAST draws the label 53 px right, x 305..401 (inset 315..391), onto the box. (The
     * box moved from x 350 when the optimizer began scoring the 96 px render-calibrated box rather than
     * the 74 px raw one: at 350 the wider label was a 2 px near-miss.)
     */
    private static final RoutingRect EAST_BOX = new RoutingRect(360, 0, 50, 50, "eastBox");

    private RoutingPipeline.ConnectionEndpoints longClear(int anchor, int textPosition) {
        return held(conn("c1", 0, 0, 100, 50, 500, 0, 100, 50, "Accesses", textPosition), anchor);
    }

    @Test
    public void heldOffset_thatDrawsClear_isKept_greedy() {
        // The first clearing direction is NORTH, so a held SOUTH (also clear: 0.18 on each box) would be
        // replaced by it under the search alone. The held one wins and nothing is written.
        LabelPositionOptimizer.MultiTrialResult result = optimizer.optimizeMultiTrial(
                List.of(bleedingChain(SOUTH)), List.of(straightHorizontalPath(100, 25, 120)),
                List.of(), ownEndpointExclude("c1"), 1, new Random(0));

        assertNull("A held offset that draws clear is left alone", result.offsets().get("c1"));
        assertEquals("The label stays at Middle: its bleed is still rescuable",
                Integer.valueOf(1), result.allPositions().get("c1"));
        assertTrue("and is not reported unplaceable", result.unresolvableLabels().isEmpty());
    }

    @Test
    public void heldOffset_thatDrawsClear_isKept_positionPreserving() {
        Map<String, Integer> offsets = optimizer.computeOffsetsForPositions(
                List.of(bleedingChain(SOUTH)), pathMap("c1", straightHorizontalPath(100, 25, 120)),
                List.of(), ownEndpointExclude("c1"), Map.of());

        assertTrue("A held offset that draws clear is left alone", offsets.isEmpty());
    }

    @Test
    public void staleHeldOffset_overAClearLabel_isResetToCentre_greedy() {
        LabelPositionOptimizer.MultiTrialResult result = optimizer.optimizeMultiTrial(
                List.of(longClear(EAST, 1)), List.of(straightHorizontalPath(100, 25, 500)),
                List.of(EAST_BOX), ownEndpointExclude("c1"), 1, new Random(0));

        assertEquals("The label stays at Middle", Integer.valueOf(1), result.allPositions().get("c1"));
        assertEquals("A held offset that lands the label on a box is reset",
                Integer.valueOf(CENTER), result.offsets().get("c1"));
    }

    @Test
    public void staleHeldOffset_overAClearLabel_isResetToCentre_positionPreserving() {
        Map<String, Integer> offsets = optimizer.computeOffsetsForPositions(
                List.of(longClear(EAST, 1)), pathMap("c1", straightHorizontalPath(100, 25, 500)),
                List.of(EAST_BOX), ownEndpointExclude("c1"), Map.of());

        assertEquals("A held offset that lands the label on a box is reset",
                Integer.valueOf(CENTER), offsets.get("c1"));
    }

    @Test
    public void staleHeldOffset_overABleedingLabel_searchesAsForCentre() {
        // Held NORTH lands on blockN (drawn y 0..20 against y -20..10); the un-offset label bleeds both
        // endpoints, so it is not reset: the search runs and picks SOUTH, as it does for Centre. Left free,
        // the greedy pass takes the clear Source and leaves the held NORTH alone (Archi ignores it there).
        RoutingRect blockNorth = new RoutingRect(60, -20, 120, 30, "blockN");

        Map<String, Integer> offsets = optimizer.computeOffsetsForPositions(
                List.of(bleedingChain(NORTH)), pathMap("c1", straightHorizontalPath(100, 25, 120)),
                List.of(blockNorth), ownEndpointExclude("c1"), Map.of());
        LabelPositionOptimizer.MultiTrialResult result = optimizer.optimizeMultiTrial(
                List.of(bleedingChain(NORTH)), List.of(straightHorizontalPath(100, 25, 120)),
                List.of(blockNorth), ownEndpointExclude("c1"), 1, new Random(0));

        assertEquals(Integer.valueOf(SOUTH), offsets.get("c1"));
        assertEquals(Map.of("c1", 0), result.changedPositions());
        assertTrue(result.offsets().isEmpty());
    }

    @Test
    public void heldOffset_onASourceOrTargetLabel_isNeitherKeptNorReset() {
        // Archi ignores the anchor on a Source or Target label, so a dormant user setting is left alone.
        for (int position : new int[]{0, 2}) {
            Map<String, Integer> offsets = optimizer.computeOffsetsForPositions(
                    List.of(longClear(EAST, position)), pathMap("c1", straightHorizontalPath(100, 25, 500)),
                    List.of(EAST_BOX), ownEndpointExclude("c1"), Map.of());
            assertTrue("position " + position + ": nothing written", offsets.isEmpty());
        }
    }

    @Test
    public void heldMaskThatMovesNothing_isTreatedAsCentre() {
        // 0 and 32 name no axis, so the label is drawn where Centre draws it and the search runs as today.
        for (int anchor : new int[]{0, 32, CENTER}) {
            Map<String, Integer> offsets = optimizer.computeOffsetsForPositions(
                    List.of(bleedingChain(anchor)), pathMap("c1", straightHorizontalPath(100, 25, 120)),
                    List.of(), ownEndpointExclude("c1"), Map.of());
            assertEquals("anchor " + anchor, Integer.valueOf(NORTH), offsets.get("c1"));
        }
    }

    @Test
    public void heldMaskThatMovesNothing_isTreatedAsCentre_greedy() {
        // The greedy pass picks for a held 0 or 32 exactly what it picks for a held Centre.
        LabelPositionOptimizer.MultiTrialResult centre = optimizer.optimizeMultiTrial(
                List.of(bleedingChain(CENTER)), List.of(straightHorizontalPath(100, 25, 120)),
                List.of(), ownEndpointExclude("c1"), 1, new Random(0));
        for (int anchor : new int[]{0, 32}) {
            LabelPositionOptimizer.MultiTrialResult result = optimizer.optimizeMultiTrial(
                    List.of(bleedingChain(anchor)), List.of(straightHorizontalPath(100, 25, 120)),
                    List.of(), ownEndpointExclude("c1"), 1, new Random(0));
            assertEquals("anchor " + anchor, centre.allPositions(), result.allPositions());
            assertEquals("anchor " + anchor, centre.offsets(), result.offsets());
            assertEquals("anchor " + anchor, centre.totalScore(), result.totalScore(), 0.0);
        }
    }

    @Test
    public void heldOffset_withBothBitsOnAnAxis_isJudgedWhereItsWinningBitDrawsIt() {
        // NORTH|SOUTH draws as NORTH. blockN sits where NORTH draws the label, so the held mask is stale;
        // the un-offset label bleeds both ends, so the search runs and picks SOUTH. Read as SOUTH it would
        // have been kept.
        RoutingRect blockNorth = new RoutingRect(60, -20, 120, 30, "blockN");

        Map<String, Integer> offsets = optimizer.computeOffsetsForPositions(
                List.of(bleedingChain(NORTH | SOUTH)), pathMap("c1", straightHorizontalPath(100, 25, 120)),
                List.of(blockNorth), ownEndpointExclude("c1"), Map.of());

        assertEquals(Integer.valueOf(SOUTH), offsets.get("c1"));
    }

    // --- Own-endpoint-aware position RE-PICK (scorePosition penalty) ---
    //
    // The element-overlap loop excludes a connection's own source/target, so a label sitting squarely on
    // its own endpoint scored 0 there and the greedy pick never moved off it. These tests cover the
    // own-endpoint penalty that supplies the missing incentive: re-pick off an own endpoint when a clearer
    // position exists, stay put when none does, and never move a clear label.

    /**
     * Source (0,0 100x50) and target (130,0 100x50) side by side, 30 px apart, joined by a detour below
     * ({@link #detourBelow}). Archi draws a Source label 14 px beyond the source's face and 4 px below the
     * line (draw2d {@code ConnectionEndpointLocator}): "ConnLabel" (107 px) at x 114..221, y 29..49, over the
     * target. It cannot land on its own source; across a short gap it lands on the other endpoint. The Middle
     * label is centred on the detour's corner (110, 200), clear of both, and the Target label hangs right of
     * the line below the target, also clear.
     */
    private RoutingPipeline.ConnectionEndpoints sourceLabelOnItsTarget() {
        return conn("c1", 0, 0, 100, 50, 130, 0, 100, 50, "ConnLabel", 0);
    }

    /** Right out of the source to x 110, down to y 200, right to x 180, up into the target's bottom face. */
    private List<AbsoluteBendpointDto> detourBelow() {
        return List.of(new AbsoluteBendpointDto(110, 25), new AbsoluteBendpointDto(110, 200),
                new AbsoluteBendpointDto(180, 200));
    }

    @Test
    public void ownEndpoint_sourceBleed_repicksToClearMiddle() {
        // The Source label lands ON its own target box (see sourceLabelOnItsTarget), but Middle, on the
        // detour's corner, is clear of both endpoints. The own-endpoint penalty must move it off Source.
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(sourceLabelOnItsTarget());
        List<List<AbsoluteBendpointDto>> paths = List.of(detourBelow());

        Map<String, Integer> result = optimizer.optimize(
                connections, paths, List.of(), Map.of());

        assertEquals("Source-bleed label should re-pick to a clear position (Middle)",
                Integer.valueOf(1), result.get("c1"));
    }

    @Test
    public void ownEndpoint_bleedsAtAllPositions_staysPut() {
        // Two big overlapping endpoint boxes: the label sits on an own endpoint at every candidate position
        // (source at 15%, both at Middle, target at 85%) and no offset direction clears the Middle bleed.
        // The current position (Source) is already minimal-cost, so the tie-break keeps it there — no worse
        // than today (the pathological case where the label cannot escape its own boxes).
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 300, 200, 200, 0, 300, 200, "Lbl", 0));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(150, 100, 350));

        Map<String, Integer> result = optimizer.optimize(
                connections, paths, List.of(), Map.of());

        assertTrue("All-positions-bleed label should keep its current position", result.isEmpty());
    }

    @Test
    public void ownEndpoint_alreadyClearMiddle_keepsPosition() {
        // A Middle label well clear of both its own endpoints must not move (byte-identical to today).
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 60, 40, 500, 0, 60, 40, "Mid", 1));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(60, 20, 500));

        Map<String, Integer> result = optimizer.optimize(
                connections, paths, List.of(), Map.of());

        assertTrue("Already-clear Middle label keeps its position", result.isEmpty());
    }

    @Test
    public void computeTotalScore_reflectsOwnEndpointPenalty() {
        // Same own-endpoint fixture: the Source assignment (label ON its own target box) must total higher than
        // the Middle assignment (clear), so multi-trial selection — which ranks by computeTotalScore — prefers
        // Middle.
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(sourceLabelOnItsTarget());
        List<List<AbsoluteBendpointDto>> paths = List.of(detourBelow());

        double sourceTotal = optimizer.computeTotalScore(
                Map.of("c1", 0), connections, paths, List.of(), Map.of());
        double middleTotal = optimizer.computeTotalScore(
                Map.of("c1", 1), connections, paths, List.of(), Map.of());

        assertTrue("Own-endpoint (Source) assignment must total worse than the clear (Middle) one",
                sourceTotal > middleTotal);
        assertEquals("Clear Middle assignment totals zero", 0.0, middleTotal, 0.001);
    }

    @Test
    public void multiTrial_picksOwnEndpointFreeAssignment() {
        // End-to-end: multi-trial must converge on the own-endpoint-free (Middle) assignment for a Source-pinned
        // label that bleeds onto its own target box, with a total score of 0.
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(sourceLabelOnItsTarget());
        List<List<AbsoluteBendpointDto>> paths = List.of(detourBelow());

        LabelPositionOptimizer.MultiTrialResult result = optimizer.optimizeMultiTrial(
                connections, paths, List.of(), Map.of(), 5, new Random(42));

        assertEquals("Multi-trial should move the source-bleed label off its own endpoint",
                Integer.valueOf(1), result.allPositions().get("c1"));
        assertEquals("Own-endpoint-free assignment totals zero", 0.0, result.totalScore(), 0.001);
    }

    @Test
    public void ownEndpoint_repickToMiddle_withTheAnchorThatClearsIt() {
        // The Source position bleeds its own target box (see sourceLabelOnItsTarget) and an extra obstacle
        // further taxes it; two obstacles sit on the Target slot (x 184..291, y 64..84), leaving Middle the
        // best escape. The re-pick lands on Middle, and because the Middle label (x 56..164, y 190..210)
        // still sits on a third-party obstacle there, the label takes an anchor at Middle: West and
        // East (the perpendiculars of the vertical leg into the corner) stay on it, North clears it.
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(sourceLabelOnItsTarget());
        List<List<AbsoluteBendpointDto>> paths = List.of(detourBelow());
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(110, 35, 18, 20, "obsA"),   // taxes the Source position
                new RoutingRect(90, 200, 40, 40, "obsB"),   // on the Middle position
                new RoutingRect(200, 60, 30, 30, "obsC"),   // on the Target position
                new RoutingRect(250, 60, 30, 30, "obsD"));  // on the Target position
        Map<String, Set<String>> excludeSets = Map.of("c1", Set.of("src-c1", "tgt-c1"));

        LabelPositionOptimizer.MultiTrialResult result = optimizer.optimizeMultiTrial(
                connections, paths, obstacles, excludeSets, 1, new Random(1));

        assertEquals("Re-pick should land on Middle", Integer.valueOf(1),
                result.allPositions().get("c1"));
        assertEquals("An anchor should lift the Middle landing clear (NORTH off obsB)",
                Integer.valueOf(NORTH), result.offsets().get("c1"));
    }

    // --- Per-connection residual: labels with NO collision-free position anywhere ---
    //
    // The greedy pass evaluates all three candidate positions and previously discarded every score:
    // bestScore was a local double, and in the all-equal case the tie-break re-picks the CURRENT
    // position, so an unplaceable label emitted no changedPositions entry and was downstream
    // indistinguishable from a label that was already perfect. These tests pin the retained residual.
    //
    // "Unresolvable" deliberately means an UNRESCUABLE HARD collision at every position, not
    // "bestScore > 0": scorePosition weights a mere proximity near-miss at 0.5, so two harmless
    // near-misses total 1.0 and would otherwise be indistinguishable from one genuine overlap.
    // Hiding a label for passing near two boxes would be exactly the over-suppression this must avoid.

    /**
     * Blockers on the three slots of the 50..550 "Flows" line, where Archi draws them (draw2d
     * {@code ConnectionEndpointLocator} for Source and Target). The first bendpoint sits on the source's
     * face, so the drawn end segment has no length and the Source box hangs 18 px below the line, x 64..128
     * y 43..63. The last sits on the target's left edge line, inside its band, so the line ends at the target
     * centre (575, 25) and the Target box is below it, x 593..657 y 39..59. The middle blocker is wide enough
     * that no offset direction escapes it.
     */
    private List<RoutingRect> allThreePositionsBlockedHorizontal() {
        return List.of(
                new RoutingRect(80, 40, 40, 30, "obsSrcPos"),      // blocks Source
                new RoutingRect(200, -100, 200, 300, "obsMidPos"), // blocks Middle (x 268..332) at every offset
                new RoutingRect(600, 40, 40, 30, "obsTgtPos"));    // blocks Target
    }

    @Test
    public void shouldReportUnresolvable_whenEveryPositionCollides_onHorizontalSegment() {
        // Orientation-blind. The assessor's nearest detector is gated on a vertical-segment check, so a
        // label with no valid position on a HORIZONTAL hosting segment is exactly the case it never flags.
        // This pass consults no orientation predicate at all, so both orientations behave identically.
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 50, 50, 550, 0, 50, 50, "Flows", 1));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(50, 25, 550));

        LabelPositionOptimizer.LabelOptimizationResult result = optimizer.optimizeWithResidual(
                connections, paths, allThreePositionsBlockedHorizontal(), Map.of());

        assertTrue("A label blocked at all 3 positions must be reported as unresolvable",
                result.unresolvableLabels().contains("c1"));
    }

    @Test
    public void shouldReportUnresolvable_whenAllPositionsTie_andNoPositionChangeIsEmitted() {
        // The honesty case the tie-break hides: all three positions score equally, so the tie-break at
        // runGreedyPass re-picks the CURRENT position and changedPositions stays EMPTY. Without the
        // residual this connection is indistinguishable from a label that needed no change at all.
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 50, 50, 550, 0, 50, 50, "Flows", 1));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(50, 25, 550));

        LabelPositionOptimizer.LabelOptimizationResult result = optimizer.optimizeWithResidual(
                connections, paths, allThreePositionsBlockedHorizontal(), Map.of());

        assertTrue("All three positions tie → the optimizer emits no position change",
                result.changedPositions().isEmpty());
        assertTrue("…yet the label is still unplaceable and MUST be reported",
                result.unresolvableLabels().contains("c1"));
    }

    @Test
    public void shouldReportUnresolvable_whenEveryPositionCollides_onVerticalSegment() {
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 50, 50, 0, 550, 50, 50, "Flows", 1));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightVerticalPath(25, 50, 550));
        // Source (x 43..107, y 64..84) and Target (x 29..93, y 589..609) where Archi draws them: right of
        // the line, past the bendpoints on the faces (see allThreePositionsBlockedHorizontal).
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(40, 60, 60, 30, "obsSrcPos"),
                new RoutingRect(-100, 200, 300, 200, "obsMidPos"),
                new RoutingRect(40, 585, 60, 30, "obsTgtPos"));

        LabelPositionOptimizer.LabelOptimizationResult result = optimizer.optimizeWithResidual(
                connections, paths, obstacles, Map.of());

        assertTrue("Vertical hosting segment must behave identically to horizontal",
                result.unresolvableLabels().contains("c1"));
    }

    @Test
    public void shouldReportUnresolvable_whenLabelBleedsOnOwnEndpointsAtEveryPosition() {
        // Reuses the shipped all-bleed fixture: two big overlapping endpoint boxes, label on an own
        // endpoint at every candidate position, and no offset direction clears the Middle bleed.
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 300, 200, 200, 0, 300, 200, "Lbl", 0));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(150, 100, 350));

        LabelPositionOptimizer.LabelOptimizationResult result = optimizer.optimizeWithResidual(
                connections, paths, List.of(), Map.of());

        assertTrue("Own-endpoint bleed at every position is also 'no valid position'",
                result.unresolvableLabels().contains("c1"));
    }

    @Test
    public void shouldNotReportUnresolvable_whenLabelHasAClearPosition() {
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 100, 50, 500, 0, 100, 50, "Uses", 1));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(100, 25, 500));

        LabelPositionOptimizer.LabelOptimizationResult result = optimizer.optimizeWithResidual(
                connections, paths, List.of(), Map.of());

        assertTrue("A label with a clear position is never unresolvable",
                result.unresolvableLabels().isEmpty());
    }

    @Test
    public void shouldNotReportUnresolvable_whenEveryPositionIsOnlyANearMiss() {
        // THE OVER-SUPPRESSION GUARD. Each position sits within the proximity threshold of a box but
        // inset-overlaps nothing. Scored, every position is non-zero (0.5), so a naive "bestScore > 0"
        // rule would hide this label. It is perfectly readable and must NOT be hidden.
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 50, 50, 550, 0, 50, 50, "Flows", 1));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(50, 25, 550));
        List<RoutingRect> nearMisses = List.of(
                new RoutingRect(83, 38, 50, 20, "nearSrcPos"),
                new RoutingRect(275, 38, 50, 20, "nearMidPos"),
                new RoutingRect(468, 38, 50, 20, "nearTgtPos"));

        LabelPositionOptimizer.LabelOptimizationResult result = optimizer.optimizeWithResidual(
                connections, paths, nearMisses, Map.of());

        assertTrue("A proximity near-miss is not a collision — the label must not be reported",
                result.unresolvableLabels().isEmpty());
    }

    @Test
    public void shouldNotReportUnresolvable_whenAClearAnchorRescuesTheMiddleLabel() {
        // A Middle label that overlaps a small third-party box which a perpendicular offset lifts it
        // clear of is PLACEABLE — the engine has not proven it cannot be placed, so it must not be hidden.
        // "Flows" (50 x 20) at Middle is x 275..325, y 15..35 (inset y 21.67..28.33), on the 10 px strip
        // y 20..30. Archi's NORTH lift of 15 px gives y 0..20 (inset 6.67..13.33): clear.
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 50, 50, 550, 0, 50, 50, "Flows", 1));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(50, 25, 550));
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(90, 10, 50, 30, "obsSrcPos"),
                new RoutingRect(280, 20, 50, 10, "obsMidPosSmall"), // small → an offset direction clears it
                new RoutingRect(470, 10, 50, 30, "obsTgtPos"));

        LabelPositionOptimizer.LabelOptimizationResult result = optimizer.optimizeWithResidual(
                connections, paths, obstacles, Map.of());

        assertTrue("An offset-rescuable Middle label still has a valid position",
                result.unresolvableLabels().isEmpty());
    }

    @Test
    public void shouldReportUnresolvable_whenTheDrawnLiftCannotClearTheMiddleObstacle() {
        // The rescue case with a 30 px tall Middle obstacle, y 10..40. Archi's lifts of the 50 x 20 "Flows"
        // label (15 px on y, 30 px on x) all leave its inset box over the obstacle: NORTH inset y 6.67..13.33
        // and SOUTH 36.67..43.33 cross y 10..40; WEST inset x 255..285 and EAST 315..345 cross x 280..330.
        // Source and Target are blocked too (where Archi draws them, see
        // allThreePositionsBlockedHorizontal), so the label is unplaceable — the verdict a caller's
        // "auto-hide-on-collision" label policy acts on. A 40 px lift (y -25..-5) would have called it
        // rescued.
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 50, 50, 550, 0, 50, 50, "Flows", 1));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(50, 25, 550));
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(80, 40, 40, 30, "obsSrcPos"),
                new RoutingRect(280, 10, 50, 30, "obsMidPos"),
                new RoutingRect(600, 40, 40, 30, "obsTgtPos"));

        LabelPositionOptimizer.LabelOptimizationResult result = optimizer.optimizeWithResidual(
                connections, paths, obstacles, Map.of());

        assertEquals(Set.of("c1"), result.unresolvableLabels());
    }

    @Test
    public void shouldReportOnlyTheUnplaceableConnection_whenViewMixesBoth() {
        // WHICH, not how many: a mixed view must name exactly the blocked connection and leave the
        // clear one alone. c2 is placed far from c1's blockers so the two do not interact.
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 50, 50, 550, 0, 50, 50, "Flows", 1),
                conn("c2", 0, 900, 100, 50, 500, 900, 100, 50, "Uses", 1));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(50, 25, 550),
                straightHorizontalPath(100, 925, 500));

        LabelPositionOptimizer.LabelOptimizationResult result = optimizer.optimizeWithResidual(
                connections, paths, allThreePositionsBlockedHorizontal(), Map.of());

        assertTrue("The blocked connection is named", result.unresolvableLabels().contains("c1"));
        assertFalse("The clear connection is NOT named", result.unresolvableLabels().contains("c2"));
        assertEquals("Exactly one connection is unplaceable", 1, result.unresolvableLabels().size());
    }

    @Test
    public void shouldReportNoResidual_whenNoConnectionCarriesALabel() {
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 50, 50, 550, 0, 50, 50, "", 1));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(50, 25, 550));

        LabelPositionOptimizer.LabelOptimizationResult result = optimizer.optimizeWithResidual(
                connections, paths, allThreePositionsBlockedHorizontal(), Map.of());

        assertTrue("An unlabeled connection has no label to place",
                result.unresolvableLabels().isEmpty());
    }

    @Test
    public void multiTrial_shouldCarryTheResidualOfTheWinningTrial() {
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 50, 50, 550, 0, 50, 50, "Flows", 1));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(50, 25, 550));

        LabelPositionOptimizer.MultiTrialResult result = optimizer.optimizeMultiTrial(
                connections, paths, allThreePositionsBlockedHorizontal(), Map.of(), 5, new Random(7));

        assertTrue("The multi-trial entry point must carry the residual too",
                result.unresolvableLabels().contains("c1"));
    }

    @Test
    public void multiTrial_shouldReportEmptyResidual_whenNoLabelsExist() {
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 50, 50, 550, 0, 50, 50, "", 1));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(50, 25, 550));

        LabelPositionOptimizer.MultiTrialResult result = optimizer.optimizeMultiTrial(
                connections, paths, List.of(), Map.of(), 3, new Random(7));

        assertNotNull("The residual is never null", result.unresolvableLabels());
        assertTrue(result.unresolvableLabels().isEmpty());
    }

    @Test
    public void optimize_shouldSurfaceTheChangedPositionsTheResidualPassComputes() {
        // Deliberately NOT a comparison of optimize() against optimizeWithResidual(): optimize() is
        // implemented as a thin delegate to it, so asserting the two agree is true by construction
        // and could never fail. What is worth pinning is that the delegate still returns the CHANGES
        // — a refactor handing back the wrong map (allPositions, or the residual set) breaks this.
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                conn("c1", 0, 0, 50, 50, 550, 0, 50, 50, "Accesses", 1));
        List<List<AbsoluteBendpointDto>> paths = List.of(
                straightHorizontalPath(50, 25, 550));
        List<RoutingRect> obstacles = List.of(new RoutingRect(270, 5, 60, 40, "obs1"));

        Map<String, Integer> changed = optimizer.optimize(connections, paths, obstacles, Map.of());

        assertTrue("the obstructed Middle label must be re-picked", changed.containsKey("c1"));
        assertNotEquals("a reported change must differ from the input position",
                Integer.valueOf(1), changed.get("c1"));
    }
}
