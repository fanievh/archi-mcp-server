package net.vheerden.archi.mcp.model;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link CrossingMinimizer} — pure geometry barycentric crossing
 * minimization (Story 11-25). No EMF or SWT runtime required.
 */
public class CrossingMinimizerTest {

    private CrossingMinimizer minimizer;

    @Before
    public void setUp() {
        minimizer = new CrossingMinimizer();
    }

    // ---- Task 4.1: Two groups with known optimal ordering ----

    @Test
    public void shouldProduceOptimalOrdering_whenTwoGroupsWithKnownCrossings() {
        // Group A (left): elements A1, A2, A3 stacked vertically
        // Group B (right): elements B1, B2, B3 stacked vertically
        // Connections: A1-B3, A2-B2, A3-B1 (deliberately crossed)
        // Optimal: reorder group B to B3, B2, B1 → zero crossings
        CrossingMinimizer.GroupInfo groupA = new CrossingMinimizer.GroupInfo(
                "gA",
                List.of("A1", "A2", "A3"),
                List.of(new int[]{50, 50}, new int[]{50, 150}, new int[]{50, 250}));

        CrossingMinimizer.GroupInfo groupB = new CrossingMinimizer.GroupInfo(
                "gB",
                List.of("B1", "B2", "B3"),
                List.of(new int[]{300, 50}, new int[]{300, 150}, new int[]{300, 250}));

        List<CrossingMinimizer.InterGroupEdge> edges = List.of(
                new CrossingMinimizer.InterGroupEdge("A1", "gA", "B3", "gB"),
                new CrossingMinimizer.InterGroupEdge("A2", "gA", "B2", "gB"),
                new CrossingMinimizer.InterGroupEdge("A3", "gA", "B1", "gB"));

        CrossingMinimizer.OptimizationResult result =
                minimizer.optimize(List.of(groupA, groupB), edges);

        assertTrue("Crossings should be reduced",
                result.crossingsAfter() <= result.crossingsBefore());
        assertTrue("Should have positive crossings before",
                result.crossingsBefore() > 0);
        // After optimization, crossings should be significantly reduced
        assertTrue("Crossings after should be less than before",
                result.crossingsAfter() < result.crossingsBefore());
    }

    @Test
    public void shouldReorderCorrectly_whenSimpleTwoGroupCross() {
        // Group A: [A1, A2] at y=50, y=150
        // Group B: [B1, B2] at y=50, y=150
        // Connection: A1-B2 and A2-B1 → 1 crossing
        // Optimal: reorder B to [B2, B1] → 0 crossings
        CrossingMinimizer.GroupInfo groupA = new CrossingMinimizer.GroupInfo(
                "gA",
                List.of("A1", "A2"),
                List.of(new int[]{50, 50}, new int[]{50, 150}));

        CrossingMinimizer.GroupInfo groupB = new CrossingMinimizer.GroupInfo(
                "gB",
                List.of("B1", "B2"),
                List.of(new int[]{300, 50}, new int[]{300, 150}));

        List<CrossingMinimizer.InterGroupEdge> edges = List.of(
                new CrossingMinimizer.InterGroupEdge("A1", "gA", "B2", "gB"),
                new CrossingMinimizer.InterGroupEdge("A2", "gA", "B1", "gB"));

        CrossingMinimizer.OptimizationResult result =
                minimizer.optimize(List.of(groupA, groupB), edges);

        assertEquals("Should have 1 crossing before", 1, result.crossingsBefore());
        assertEquals("Should have 0 crossings after", 0, result.crossingsAfter());
        assertFalse("Should have reordered groups", result.reorderedGroups().isEmpty());
    }

    // ---- Task 4.2: Single-element groups — no reordering possible ----

    @Test
    public void shouldReturnUnchanged_whenSingleElementGroups() {
        CrossingMinimizer.GroupInfo groupA = new CrossingMinimizer.GroupInfo(
                "gA",
                List.of("A1"),
                List.of(new int[]{50, 100}));

        CrossingMinimizer.GroupInfo groupB = new CrossingMinimizer.GroupInfo(
                "gB",
                List.of("B1"),
                List.of(new int[]{300, 100}));

        List<CrossingMinimizer.InterGroupEdge> edges = List.of(
                new CrossingMinimizer.InterGroupEdge("A1", "gA", "B1", "gB"));

        CrossingMinimizer.OptimizationResult result =
                minimizer.optimize(List.of(groupA, groupB), edges);

        assertTrue("No groups should be reordered",
                result.reorderedGroups().isEmpty());
        assertEquals(0, result.elementMoves());
    }

    // ---- Task 4.3: No inter-group connections ----

    @Test
    public void shouldReturnUnchanged_whenNoInterGroupConnections() {
        CrossingMinimizer.GroupInfo groupA = new CrossingMinimizer.GroupInfo(
                "gA",
                List.of("A1", "A2"),
                List.of(new int[]{50, 50}, new int[]{50, 150}));

        CrossingMinimizer.GroupInfo groupB = new CrossingMinimizer.GroupInfo(
                "gB",
                List.of("B1", "B2"),
                List.of(new int[]{300, 50}, new int[]{300, 150}));

        CrossingMinimizer.OptimizationResult result =
                minimizer.optimize(List.of(groupA, groupB), List.of());

        assertEquals(0, result.crossingsBefore());
        assertEquals(0, result.crossingsAfter());
        assertTrue(result.reorderedGroups().isEmpty());
    }

    // ---- Task 4.4: Crossing count never increases (property test) ----

    @Test
    public void shouldNeverIncreaseCrossings_withRandomInputs() {
        Random rng = new Random(42); // Fixed seed for reproducibility

        for (int trial = 0; trial < 50; trial++) {
            // Generate 2-4 groups with 2-6 elements each
            int numGroups = 2 + rng.nextInt(3);
            List<CrossingMinimizer.GroupInfo> groups = new ArrayList<>();
            List<CrossingMinimizer.InterGroupEdge> edges = new ArrayList<>();

            List<String> allGroupIds = new ArrayList<>();
            List<List<String>> allElementIds = new ArrayList<>();

            for (int g = 0; g < numGroups; g++) {
                String groupId = "g" + g;
                allGroupIds.add(groupId);
                int numElements = 2 + rng.nextInt(5);
                List<String> elemIds = new ArrayList<>();
                List<int[]> centers = new ArrayList<>();

                for (int e = 0; e < numElements; e++) {
                    String elemId = "g" + g + "e" + e;
                    elemIds.add(elemId);
                    centers.add(new int[]{
                            g * 300 + rng.nextInt(200),
                            e * 80 + rng.nextInt(40)});
                }
                groups.add(new CrossingMinimizer.GroupInfo(groupId, elemIds, centers));
                allElementIds.add(elemIds);
            }

            // Generate random inter-group edges
            int numEdges = 3 + rng.nextInt(8);
            for (int i = 0; i < numEdges; i++) {
                int srcGroupIdx = rng.nextInt(numGroups);
                int tgtGroupIdx = rng.nextInt(numGroups);
                if (srcGroupIdx == tgtGroupIdx) {
                    tgtGroupIdx = (tgtGroupIdx + 1) % numGroups;
                }
                String srcElem = allElementIds.get(srcGroupIdx)
                        .get(rng.nextInt(allElementIds.get(srcGroupIdx).size()));
                String tgtElem = allElementIds.get(tgtGroupIdx)
                        .get(rng.nextInt(allElementIds.get(tgtGroupIdx).size()));
                edges.add(new CrossingMinimizer.InterGroupEdge(
                        srcElem, allGroupIds.get(srcGroupIdx),
                        tgtElem, allGroupIds.get(tgtGroupIdx)));
            }

            CrossingMinimizer.OptimizationResult result =
                    minimizer.optimize(groups, edges);

            assertTrue("Trial " + trial + ": crossings should not increase "
                    + "(before=" + result.crossingsBefore()
                    + ", after=" + result.crossingsAfter() + ")",
                    result.crossingsAfter() <= result.crossingsBefore());
        }
    }

    // ---- Task 4.5: Empty/null inputs ----

    @Test
    public void shouldHandleNullGroups() {
        CrossingMinimizer.OptimizationResult result =
                minimizer.optimize(null, List.of());
        assertEquals(0, result.crossingsBefore());
        assertEquals(0, result.crossingsAfter());
        assertTrue(result.reorderedGroups().isEmpty());
    }

    @Test
    public void shouldHandleEmptyGroups() {
        CrossingMinimizer.OptimizationResult result =
                minimizer.optimize(List.of(), List.of());
        assertEquals(0, result.crossingsBefore());
        assertEquals(0, result.crossingsAfter());
        assertTrue(result.reorderedGroups().isEmpty());
    }

    @Test
    public void shouldHandleNullEdges() {
        CrossingMinimizer.GroupInfo group = new CrossingMinimizer.GroupInfo(
                "g1", List.of("e1", "e2"),
                List.of(new int[]{50, 50}, new int[]{50, 150}));

        CrossingMinimizer.OptimizationResult result =
                minimizer.optimize(List.of(group), null);
        assertEquals(0, result.crossingsBefore());
        assertEquals(0, result.crossingsAfter());
    }

    // ---- Crossing count tests ----

    @Test
    public void shouldCountCrossingsCorrectly_whenTwoEdgesCross() {
        CrossingMinimizer.GroupInfo groupA = new CrossingMinimizer.GroupInfo(
                "gA",
                List.of("A1", "A2"),
                List.of(new int[]{0, 0}, new int[]{0, 100}));

        CrossingMinimizer.GroupInfo groupB = new CrossingMinimizer.GroupInfo(
                "gB",
                List.of("B1", "B2"),
                List.of(new int[]{200, 0}, new int[]{200, 100}));

        // A1-B2 and A2-B1 cross each other
        List<CrossingMinimizer.InterGroupEdge> edges = List.of(
                new CrossingMinimizer.InterGroupEdge("A1", "gA", "B2", "gB"),
                new CrossingMinimizer.InterGroupEdge("A2", "gA", "B1", "gB"));

        int crossings = minimizer.countStraightLineCrossings(
                List.of(groupA, groupB), edges);
        assertEquals(1, crossings);
    }

    @Test
    public void shouldCountZeroCrossings_whenParallelEdges() {
        CrossingMinimizer.GroupInfo groupA = new CrossingMinimizer.GroupInfo(
                "gA",
                List.of("A1", "A2"),
                List.of(new int[]{0, 0}, new int[]{0, 100}));

        CrossingMinimizer.GroupInfo groupB = new CrossingMinimizer.GroupInfo(
                "gB",
                List.of("B1", "B2"),
                List.of(new int[]{200, 0}, new int[]{200, 100}));

        // A1-B1 and A2-B2 are parallel (no crossing)
        List<CrossingMinimizer.InterGroupEdge> edges = List.of(
                new CrossingMinimizer.InterGroupEdge("A1", "gA", "B1", "gB"),
                new CrossingMinimizer.InterGroupEdge("A2", "gA", "B2", "gB"));

        int crossings = minimizer.countStraightLineCrossings(
                List.of(groupA, groupB), edges);
        assertEquals(0, crossings);
    }

    // ---- Segment intersection tests ----

    @Test
    public void shouldDetectIntersection_whenSegmentsCross() {
        assertTrue(CrossingMinimizer.segmentsIntersect(
                0, 0, 100, 100, 100, 0, 0, 100));
    }

    @Test
    public void shouldNotDetectIntersection_whenSegmentsParallel() {
        assertFalse(CrossingMinimizer.segmentsIntersect(
                0, 0, 100, 0, 0, 50, 100, 50));
    }

    @Test
    public void shouldNotDetectIntersection_whenSegmentsShareEndpoint() {
        assertFalse(CrossingMinimizer.segmentsIntersect(
                0, 0, 50, 50, 50, 50, 100, 0));
    }

    // ---- Three-group optimization ----

    @Test
    public void shouldOptimize_whenThreeGroupsWithCrossings() {
        CrossingMinimizer.GroupInfo groupA = new CrossingMinimizer.GroupInfo(
                "gA",
                List.of("A1", "A2", "A3"),
                List.of(new int[]{50, 50}, new int[]{50, 150}, new int[]{50, 250}));

        CrossingMinimizer.GroupInfo groupB = new CrossingMinimizer.GroupInfo(
                "gB",
                List.of("B1", "B2", "B3"),
                List.of(new int[]{250, 50}, new int[]{250, 150}, new int[]{250, 250}));

        CrossingMinimizer.GroupInfo groupC = new CrossingMinimizer.GroupInfo(
                "gC",
                List.of("C1", "C2"),
                List.of(new int[]{450, 100}, new int[]{450, 200}));

        List<CrossingMinimizer.InterGroupEdge> edges = List.of(
                // Crossed connections A→B
                new CrossingMinimizer.InterGroupEdge("A1", "gA", "B3", "gB"),
                new CrossingMinimizer.InterGroupEdge("A3", "gA", "B1", "gB"),
                // B→C connections
                new CrossingMinimizer.InterGroupEdge("B1", "gB", "C2", "gC"),
                new CrossingMinimizer.InterGroupEdge("B3", "gB", "C1", "gC"));

        CrossingMinimizer.OptimizationResult result =
                minimizer.optimize(List.of(groupA, groupB, groupC), edges);

        assertTrue("Crossings should not increase",
                result.crossingsAfter() <= result.crossingsBefore());
    }

    @Test
    public void shouldReportCorrectElementMoves() {
        // Simple case: 2 groups, 2 elements each, 1 crossing
        CrossingMinimizer.GroupInfo groupA = new CrossingMinimizer.GroupInfo(
                "gA",
                List.of("A1", "A2"),
                List.of(new int[]{50, 50}, new int[]{50, 150}));

        CrossingMinimizer.GroupInfo groupB = new CrossingMinimizer.GroupInfo(
                "gB",
                List.of("B1", "B2"),
                List.of(new int[]{300, 50}, new int[]{300, 150}));

        List<CrossingMinimizer.InterGroupEdge> edges = List.of(
                new CrossingMinimizer.InterGroupEdge("A1", "gA", "B2", "gB"),
                new CrossingMinimizer.InterGroupEdge("A2", "gA", "B1", "gB"));

        CrossingMinimizer.OptimizationResult result =
                minimizer.optimize(List.of(groupA, groupB), edges);

        assertTrue("Should have element moves", result.elementMoves() > 0);
    }
}
