package net.vheerden.archi.mcp.model;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link GroupTopologyOrderer} (Tech Spec 13-2).
 * Pure-geometry tests — no OSGi required.
 */
public class GroupTopologyOrdererTest {

    private GroupTopologyOrderer orderer;

    @Before
    public void setUp() {
        orderer = new GroupTopologyOrderer();
    }

    // ---- Linear ordering tests ----

    @Test
    public void orderLinear_threeGroupsMiddleHeavy_shouldPlaceMiddleBetween() {
        // A↔B heavy (10), B↔C heavy (10), A↔C light (1)
        List<String> groups = List.of("A", "B", "C");
        Map<String, Map<String, Integer>> weights = new HashMap<>();
        weights.put("A", Map.of("B", 10, "C", 1));
        weights.put("B", Map.of("A", 10, "C", 10));
        weights.put("C", Map.of("B", 10, "A", 1));

        List<String> result = orderer.orderLinear(groups, weights);

        assertEquals(3, result.size());
        int bPos = result.indexOf("B");
        assertTrue("B (hub) should be in middle position, got position " + bPos
                + " in order: " + result, bPos == 1);
    }

    @Test
    public void orderLinear_fourGroupChain_shouldPreserveChainOrder() {
        // A→B→C→D chain
        List<String> groups = List.of("A", "B", "C", "D");
        Map<String, Map<String, Integer>> weights = new HashMap<>();
        weights.put("A", Map.of("B", 10));
        weights.put("B", Map.of("A", 10, "C", 10));
        weights.put("C", Map.of("B", 10, "D", 10));
        weights.put("D", Map.of("C", 10));

        List<String> result = orderer.orderLinear(groups, weights);

        assertEquals(4, result.size());
        int aPos = result.indexOf("A");
        int bPos = result.indexOf("B");
        int cPos = result.indexOf("C");
        int dPos = result.indexOf("D");
        assertTrue("A and B should be adjacent in order: " + result,
                Math.abs(aPos - bPos) == 1);
        assertTrue("B and C should be adjacent in order: " + result,
                Math.abs(bPos - cPos) == 1);
        assertTrue("C and D should be adjacent in order: " + result,
                Math.abs(cPos - dPos) == 1);
    }

    @Test
    public void orderLinear_starTopology_shouldPlaceHubAtCenter() {
        List<String> groups = List.of("spoke1", "spoke2", "hub", "spoke3", "spoke4");
        Map<String, Map<String, Integer>> weights = new HashMap<>();
        weights.put("hub", Map.of("spoke1", 10, "spoke2", 10, "spoke3", 10, "spoke4", 10));
        weights.put("spoke1", Map.of("hub", 10));
        weights.put("spoke2", Map.of("hub", 10));
        weights.put("spoke3", Map.of("hub", 10));
        weights.put("spoke4", Map.of("hub", 10));

        List<String> result = orderer.orderLinear(groups, weights);

        assertEquals(5, result.size());
        int hubPos = result.indexOf("hub");
        assertTrue("Hub should be near center, got position " + hubPos + " in order: " + result,
                hubPos >= 1 && hubPos <= 3);
    }

    @Test
    public void orderLinear_noConnections_shouldReturnInputOrder() {
        List<String> groups = List.of("A", "B", "C");
        Map<String, Map<String, Integer>> weights = new HashMap<>();

        List<String> result = orderer.orderLinear(groups, weights);

        assertEquals(List.of("A", "B", "C"), result);
    }

    @Test
    public void orderLinear_singleGroup_shouldReturnSingleGroup() {
        List<String> result = orderer.orderLinear(List.of("only"), Map.of());
        assertEquals(List.of("only"), result);
    }

    @Test
    public void orderLinear_twoGroups_shouldReturnBothGroups() {
        List<String> groups = List.of("A", "B");
        Map<String, Map<String, Integer>> weights = Map.of(
                "A", Map.of("B", 5),
                "B", Map.of("A", 5));

        List<String> result = orderer.orderLinear(groups, weights);

        assertEquals(2, result.size());
        assertTrue(result.contains("A"));
        assertTrue(result.contains("B"));
    }

    @Test
    public void orderLinear_nullInput_shouldReturnEmptyList() {
        List<String> result = orderer.orderLinear(null, null);
        assertTrue(result.isEmpty());
    }

    @Test
    public void orderLinear_emptyWeights_shouldReturnInputOrder() {
        List<String> groups = List.of("X", "Y", "Z");
        List<String> result = orderer.orderLinear(groups, Map.of());
        assertEquals(groups, result);
    }

    // ---- Grid ordering tests ----

    @Test
    public void orderGrid_sixGroupsWith2x3_shouldPlaceConnectedAdjacent() {
        List<String> groups = List.of("A", "B", "C", "D", "E", "F");
        Map<String, Map<String, Integer>> weights = new HashMap<>();
        weights.put("A", Map.of("B", 10));
        weights.put("B", Map.of("A", 10));
        weights.put("C", Map.of("D", 10));
        weights.put("D", Map.of("C", 10));
        weights.put("E", Map.of("F", 10));
        weights.put("F", Map.of("E", 10));

        List<String> result = orderer.orderGrid(groups, weights, 3);

        assertEquals(6, result.size());
        assertTrue(result.containsAll(groups));

        // Verify connected pairs are in adjacent grid cells (row-major, 3 columns)
        // Adjacent means same row ±1 col, or same col ±1 row
        assertGridAdjacent("A", "B", result, 3);
        assertGridAdjacent("C", "D", result, 3);
        assertGridAdjacent("E", "F", result, 3);
    }

    /**
     * Asserts that two groups occupy adjacent cells in a row-major grid.
     * Adjacent = Manhattan distance of 1 (horizontally or vertically adjacent).
     */
    private void assertGridAdjacent(String g1, String g2, List<String> gridOrder, int columns) {
        int idx1 = gridOrder.indexOf(g1);
        int idx2 = gridOrder.indexOf(g2);
        int row1 = idx1 / columns, col1 = idx1 % columns;
        int row2 = idx2 / columns, col2 = idx2 % columns;
        int manhattan = Math.abs(row1 - row2) + Math.abs(col1 - col2);
        assertTrue(g1 + " and " + g2 + " should be grid-adjacent (manhattan=1) but were at ("
                + row1 + "," + col1 + ") and (" + row2 + "," + col2 + ") in order: " + gridOrder,
                manhattan == 1);
    }

    @Test
    public void orderGrid_noConnections_shouldReturnInputOrder() {
        List<String> groups = List.of("A", "B", "C", "D");
        List<String> result = orderer.orderGrid(groups, Map.of(), 2);
        assertEquals(groups, result);
    }

    @Test
    public void orderGrid_singleGroup_shouldReturnSingle() {
        List<String> result = orderer.orderGrid(List.of("only"), Map.of(), 2);
        assertEquals(List.of("only"), result);
    }

    @Test
    public void orderGrid_nullInput_shouldReturnEmptyList() {
        List<String> result = orderer.orderGrid(null, null, 2);
        assertTrue(result.isEmpty());
    }

    // ---- Asymmetric weights (matches production collectConnectionWeights output) ----

    @Test
    public void orderLinear_asymmetricWeights_shouldStillPlaceHubInMiddle() {
        // Production builds asymmetric matrices: only weights[source][target], not both directions.
        // getWeight() symmetrizes by summing both directions.
        List<String> groups = List.of("A", "B", "C");
        Map<String, Map<String, Integer>> weights = new HashMap<>();
        // A→B: 8 connections, B→C: 6 connections, A→C: 1 connection (asymmetric, source-only)
        weights.put("A", Map.of("B", 8, "C", 1));
        weights.put("B", Map.of("C", 6));
        // No reverse entries — mirrors real collectConnectionWeights output

        List<String> result = orderer.orderLinear(groups, weights);

        assertEquals(3, result.size());
        int bPos = result.indexOf("B");
        assertEquals("B should be in the middle with asymmetric weights, order: " + result, 1, bPos);
    }

    // ---- Cost reduction verification ----

    @Test
    public void orderLinear_producerMiddlewareConsumer_middlewareInMiddle() {
        List<String> groups = List.of("producers", "consumers", "middleware");
        Map<String, Map<String, Integer>> weights = new HashMap<>();
        weights.put("middleware", Map.of("producers", 15, "consumers", 15));
        weights.put("producers", Map.of("middleware", 15));
        weights.put("consumers", Map.of("middleware", 15));

        List<String> result = orderer.orderLinear(groups, weights);

        int mwPos = result.indexOf("middleware");
        assertEquals("Middleware should be in the middle, order: " + result, 1, mwPos);
    }
}
