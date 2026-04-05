package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure-geometry topology-aware group orderer (Tech Spec 13-2).
 * Orders groups to minimize long-range inter-group connections by placing
 * heavily-connected groups adjacent to each other.
 *
 * <p>No EMF imports — operates on group IDs and an adjacency weight matrix.
 * Only used by {@link ArchiModelAccessorImpl}.</p>
 *
 * <p>Algorithm (linear): greedy insertion with 2-opt refinement.
 * Place the heaviest-connected pair at center, then greedily insert remaining
 * groups at the position minimizing total weighted distance.</p>
 *
 * <p>Algorithm (grid): BFS outward from heaviest pair. Next group is the
 * unplaced group with highest total weight to already-placed groups, placed
 * in the adjacent empty cell closest to its heaviest neighbor.</p>
 */
class GroupTopologyOrderer {

    /**
     * Orders groups linearly (for column or row arrangement) to minimize
     * weighted edge length between non-adjacent groups.
     *
     * @param groupIds  list of group IDs to order
     * @param weights   adjacency weight matrix: groupId → (groupId → connection count)
     * @return ordered list of group IDs
     */
    List<String> orderLinear(List<String> groupIds, Map<String, Map<String, Integer>> weights) {
        if (groupIds == null || groupIds.size() <= 2) {
            return groupIds == null ? List.of() : new ArrayList<>(groupIds);
        }
        if (weights == null || weights.isEmpty() || !hasAnyConnections(groupIds, weights)) {
            return new ArrayList<>(groupIds);
        }

        // 1. Find the pair with highest weight — place them adjacent at center
        String bestA = null, bestB = null;
        int bestWeight = -1;
        for (int i = 0; i < groupIds.size(); i++) {
            for (int j = i + 1; j < groupIds.size(); j++) {
                int w = getWeight(groupIds.get(i), groupIds.get(j), weights);
                if (w > bestWeight) {
                    bestWeight = w;
                    bestA = groupIds.get(i);
                    bestB = groupIds.get(j);
                }
            }
        }

        // 2. Greedy insertion: insert remaining groups at position minimizing total weighted distance
        List<String> ordered = new ArrayList<>();
        ordered.add(bestA);
        ordered.add(bestB);

        List<String> remaining = new ArrayList<>(groupIds);
        remaining.remove(bestA);
        remaining.remove(bestB);

        while (!remaining.isEmpty()) {
            String bestGroup = null;
            int bestPos = 0;
            long bestCost = Long.MAX_VALUE;

            for (String candidate : remaining) {
                // Try inserting at each position (0 to ordered.size())
                for (int pos = 0; pos <= ordered.size(); pos++) {
                    long cost = computeInsertionCost(candidate, pos, ordered, weights);
                    if (cost < bestCost) {
                        bestCost = cost;
                        bestGroup = candidate;
                        bestPos = pos;
                    }
                }
            }

            ordered.add(bestPos, bestGroup);
            remaining.remove(bestGroup);
        }

        // 3. 2-opt refinement: try swapping pairs to reduce total cost
        ordered = twoOptRefine(ordered, weights);

        return ordered;
    }

    /**
     * Orders groups for grid placement to minimize weighted distance
     * between connected groups.
     *
     * @param groupIds  list of group IDs to order
     * @param weights   adjacency weight matrix
     * @param columns   number of grid columns
     * @return ordered list of group IDs in row-major grid order
     */
    List<String> orderGrid(List<String> groupIds, Map<String, Map<String, Integer>> weights, int columns) {
        if (groupIds == null || groupIds.size() <= 2) {
            return groupIds == null ? List.of() : new ArrayList<>(groupIds);
        }
        if (weights == null || weights.isEmpty() || !hasAnyConnections(groupIds, weights)) {
            return new ArrayList<>(groupIds);
        }

        int rows = (int) Math.ceil((double) groupIds.size() / columns);
        String[][] grid = new String[rows][columns];
        boolean[][] occupied = new boolean[rows][columns];

        // 1. Find heaviest pair and place at grid center
        String bestA = null, bestB = null;
        int bestWeight = -1;
        for (int i = 0; i < groupIds.size(); i++) {
            for (int j = i + 1; j < groupIds.size(); j++) {
                int w = getWeight(groupIds.get(i), groupIds.get(j), weights);
                if (w > bestWeight) {
                    bestWeight = w;
                    bestA = groupIds.get(i);
                    bestB = groupIds.get(j);
                }
            }
        }

        int centerRow = rows / 2;
        int centerCol = columns / 2;
        // Place bestA at center, bestB adjacent
        grid[centerRow][centerCol] = bestA;
        occupied[centerRow][centerCol] = true;

        // Find best adjacent cell for bestB
        int[] adjCell = findBestAdjacentCell(centerRow, centerCol, grid, occupied, rows, columns);
        if (adjCell != null) {
            grid[adjCell[0]][adjCell[1]] = bestB;
            occupied[adjCell[0]][adjCell[1]] = true;
        } else {
            // Fallback: find any empty cell
            int[] empty = findNearestEmptyCell(centerRow, centerCol, occupied, rows, columns);
            grid[empty[0]][empty[1]] = bestB;
            occupied[empty[0]][empty[1]] = true;
        }

        // 2. BFS outward: place remaining groups
        Set<String> placed = new HashSet<>();
        placed.add(bestA);
        placed.add(bestB);
        List<String> remaining = new ArrayList<>(groupIds);
        remaining.remove(bestA);
        remaining.remove(bestB);

        while (!remaining.isEmpty()) {
            // Find unplaced group with highest total weight to already-placed groups
            String nextGroup = null;
            int maxTotalWeight = -1;
            for (String candidate : remaining) {
                int totalWeight = 0;
                for (String p : placed) {
                    totalWeight += getWeight(candidate, p, weights);
                }
                if (totalWeight > maxTotalWeight) {
                    maxTotalWeight = totalWeight;
                    nextGroup = candidate;
                }
            }

            // Find the placed group it's most connected to
            String heaviestNeighbor = null;
            int heaviestWeight = -1;
            for (String p : placed) {
                int w = getWeight(nextGroup, p, weights);
                if (w > heaviestWeight) {
                    heaviestWeight = w;
                    heaviestNeighbor = p;
                }
            }

            // Find position of heaviest neighbor and place adjacent
            int[] neighborPos = findInGrid(heaviestNeighbor, grid, rows, columns);
            if (neighborPos != null) {
                int[] cell = findBestAdjacentCell(neighborPos[0], neighborPos[1], grid, occupied, rows, columns);
                if (cell != null) {
                    grid[cell[0]][cell[1]] = nextGroup;
                    occupied[cell[0]][cell[1]] = true;
                } else {
                    // No adjacent cell available, find nearest empty
                    int[] empty = findNearestEmptyCell(neighborPos[0], neighborPos[1], occupied, rows, columns);
                    if (empty != null) {
                        grid[empty[0]][empty[1]] = nextGroup;
                        occupied[empty[0]][empty[1]] = true;
                    }
                }
            }

            placed.add(nextGroup);
            remaining.remove(nextGroup);
        }

        // 3. Convert grid to row-major order
        List<String> result = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                if (grid[r][c] != null) {
                    result.add(grid[r][c]);
                }
            }
        }

        // Any groups not placed in grid (shouldn't happen) get appended
        for (String gid : groupIds) {
            if (!result.contains(gid)) {
                result.add(gid);
            }
        }

        return result;
    }

    // ---- Private helpers ----

    private boolean hasAnyConnections(List<String> groupIds, Map<String, Map<String, Integer>> weights) {
        for (String a : groupIds) {
            Map<String, Integer> neighbors = weights.get(a);
            if (neighbors != null) {
                for (String b : groupIds) {
                    if (!a.equals(b) && neighbors.getOrDefault(b, 0) > 0) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private int getWeight(String a, String b, Map<String, Map<String, Integer>> weights) {
        int w = 0;
        Map<String, Integer> aNeighbors = weights.get(a);
        if (aNeighbors != null) {
            w += aNeighbors.getOrDefault(b, 0);
        }
        Map<String, Integer> bNeighbors = weights.get(b);
        if (bNeighbors != null) {
            w += bNeighbors.getOrDefault(a, 0);
        }
        return w;
    }

    private long computeInsertionCost(String candidate, int pos, List<String> ordered,
                                       Map<String, Map<String, Integer>> weights) {
        // Cost = sum of weight(candidate, existing) * distance(pos, existingPos)
        long cost = 0;
        for (int i = 0; i < ordered.size(); i++) {
            int adjustedI = (i >= pos) ? i + 1 : i;
            int distance = Math.abs(pos - adjustedI);
            cost += (long) getWeight(candidate, ordered.get(i), weights) * distance;
        }
        return cost;
    }

    private long computeTotalCost(List<String> order, Map<String, Map<String, Integer>> weights) {
        long cost = 0;
        for (int i = 0; i < order.size(); i++) {
            for (int j = i + 1; j < order.size(); j++) {
                cost += (long) getWeight(order.get(i), order.get(j), weights) * (j - i);
            }
        }
        return cost;
    }

    private List<String> twoOptRefine(List<String> order, Map<String, Map<String, Integer>> weights) {
        List<String> best = new ArrayList<>(order);
        long bestCost = computeTotalCost(best, weights);
        boolean improved = true;
        int maxIterations = order.size() * 2;
        int iteration = 0;

        while (improved && iteration < maxIterations) {
            improved = false;
            iteration++;
            for (int i = 0; i < best.size() - 1; i++) {
                for (int j = i + 1; j < best.size(); j++) {
                    // Try swapping i and j
                    List<String> candidate = new ArrayList<>(best);
                    candidate.set(i, best.get(j));
                    candidate.set(j, best.get(i));
                    long candidateCost = computeTotalCost(candidate, weights);
                    if (candidateCost < bestCost) {
                        best = candidate;
                        bestCost = candidateCost;
                        improved = true;
                    }
                }
            }
        }
        return best;
    }

    private static final int[][] ADJACENT_DELTAS = {
        {-1, 0}, {1, 0}, {0, -1}, {0, 1}  // up, down, left, right
    };

    private int[] findBestAdjacentCell(int row, int col, String[][] grid,
                                        boolean[][] occupied, int rows, int columns) {
        for (int[] d : ADJACENT_DELTAS) {
            int r = row + d[0];
            int c = col + d[1];
            if (r >= 0 && r < rows && c >= 0 && c < columns && !occupied[r][c]) {
                return new int[]{r, c};
            }
        }
        return null;
    }

    private int[] findNearestEmptyCell(int row, int col, boolean[][] occupied, int rows, int columns) {
        int bestDist = Integer.MAX_VALUE;
        int[] best = null;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                if (!occupied[r][c]) {
                    int dist = Math.abs(r - row) + Math.abs(c - col);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = new int[]{r, c};
                    }
                }
            }
        }
        return best;
    }

    private int[] findInGrid(String groupId, String[][] grid, int rows, int columns) {
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                if (groupId.equals(grid[r][c])) {
                    return new int[]{r, c};
                }
            }
        }
        return null;
    }
}
