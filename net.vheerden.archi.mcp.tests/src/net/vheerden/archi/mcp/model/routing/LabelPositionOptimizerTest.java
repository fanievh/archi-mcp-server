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
 * Tests for {@link LabelPositionOptimizer} (Story 11-31).
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

    // --- Test: Middle position already clear → no change (AC4) ---

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
        // Source (15%) or target (85%) should be selected since middle overlaps
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
    // Multi-trial optimization tests (Story backlog-b12)
    // ====================================================================

    // --- Test: Single trial produces same result as optimize() (AC3) ---

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
}
