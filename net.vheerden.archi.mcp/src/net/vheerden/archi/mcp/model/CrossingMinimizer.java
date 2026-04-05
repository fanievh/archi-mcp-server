package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-geometry two-phase crossing minimizer for grouped views (Story 11-25, backlog-b6).
 * Reorders elements within groups to minimize inter-group edge crossings.
 *
 * <p>No EMF imports — operates on simple coordinate arrays and string IDs.
 * Only used by {@link ArchiModelAccessorImpl}.</p>
 *
 * <p>Algorithm: two-phase crossing minimization.
 * Phase 1 (barycentric): standard 2-layer heuristic — elements sorted by
 * average position index of connected elements in other groups.
 * Phase 2 (adjacent-swap): greedy local search that tries adjacent-element
 * swaps to escape barycentric fixed points on multi-group topologies
 * (backlog-b6).</p>
 */
class CrossingMinimizer {

    /** Maximum iterations before stopping (barycentric typically converges in 3-5). */
    static final int MAX_ITERATIONS = 10;

    /**
     * Maximum swap-phase iterations. Each pass evaluates N-1 adjacent swaps per
     * group, so the swap phase is O(MAX_SWAP_ITERATIONS * G * N * E^2) where
     * G = groups, N = max elements per group, E = edges.
     */
    static final int MAX_SWAP_ITERATIONS = 20;

    /**
     * A group with its element IDs and their center positions.
     *
     * @param groupId    the group's view object ID
     * @param elementIds ordered list of element IDs within this group
     * @param centers    center positions [x, y] for each element, parallel to elementIds
     */
    record GroupInfo(String groupId, List<String> elementIds, List<int[]> centers) {
    }

    /**
     * An inter-group edge between two elements in different groups.
     *
     * @param sourceElementId element ID in source group
     * @param sourceGroupId   group ID containing source element
     * @param targetElementId element ID in target group
     * @param targetGroupId   group ID containing target element
     */
    record InterGroupEdge(String sourceElementId, String sourceGroupId,
                          String targetElementId, String targetGroupId) {
    }

    /**
     * Result of the optimization.
     *
     * @param crossingsBefore    crossing count before optimization
     * @param crossingsAfter     crossing count after optimization
     * @param reorderedGroups    list of group IDs whose element order changed
     * @param newOrderByGroup    map from groupId to new element ID ordering
     * @param elementMoves       total number of elements that changed position
     */
    record OptimizationResult(int crossingsBefore, int crossingsAfter,
                              List<String> reorderedGroups,
                              Map<String, List<String>> newOrderByGroup,
                              int elementMoves) {
    }

    /**
     * Optimizes element order within groups to minimize inter-group edge crossings.
     *
     * @param groups list of groups with their elements and center positions
     * @param edges  list of inter-group edges
     * @return optimization result with before/after crossing counts and new orderings
     */
    OptimizationResult optimize(List<GroupInfo> groups, List<InterGroupEdge> edges) {
        if (groups == null || groups.isEmpty() || edges == null || edges.isEmpty()) {
            return new OptimizationResult(0, 0, List.of(), Map.of(), 0);
        }

        // Filter groups with > 1 element (single-element groups can't be reordered)
        List<GroupInfo> optimizableGroups = new ArrayList<>();
        for (GroupInfo group : groups) {
            if (group.elementIds().size() > 1) {
                optimizableGroups.add(group);
            }
        }

        if (optimizableGroups.isEmpty()) {
            int crossings = countStraightLineCrossings(groups, edges);
            return new OptimizationResult(crossings, crossings, List.of(), Map.of(), 0);
        }

        // Build mutable element orderings per group
        Map<String, List<String>> currentOrder = new HashMap<>();
        Map<String, List<int[]>> currentCenters = new HashMap<>();
        for (GroupInfo group : groups) {
            currentOrder.put(group.groupId(), new ArrayList<>(group.elementIds()));
            currentCenters.put(group.groupId(), new ArrayList<>(group.centers()));
        }

        int crossingsBefore = countStraightLineCrossings(groups, edges);
        int bestCrossings = crossingsBefore;
        Map<String, List<String>> bestOrder = deepCopyOrder(currentOrder);

        // Multi-pass barycentric iteration
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            boolean anyChanged = false;

            for (GroupInfo group : optimizableGroups) {
                String groupId = group.groupId();
                List<String> order = currentOrder.get(groupId);

                // Compute barycenter for each element
                double[] barycenters = computeBarycenters(
                        groupId, order, currentOrder, currentCenters, edges);

                // Sort by barycenter (elements with no connections go to end)
                List<String> newOrder = sortByBarycenter(order, barycenters);

                if (!newOrder.equals(order)) {
                    anyChanged = true;
                    currentOrder.put(groupId, newOrder);
                    // Update centers to match new order
                    recomputeCenters(groupId, newOrder, group, currentCenters);
                }
            }

            if (!anyChanged) {
                break; // Converged
            }

            // Evaluate fitness — revert if worse
            List<GroupInfo> updatedGroups = buildUpdatedGroups(groups, currentOrder, currentCenters);
            int crossingsNow = countStraightLineCrossings(updatedGroups, edges);

            if (crossingsNow < bestCrossings) {
                bestCrossings = crossingsNow;
                bestOrder = deepCopyOrder(currentOrder);
            } else if (crossingsNow > bestCrossings) {
                // Revert to best known order
                currentOrder = deepCopyOrder(bestOrder);
                // Recompute centers for reverted order
                for (GroupInfo group : groups) {
                    recomputeCenters(group.groupId(),
                            currentOrder.get(group.groupId()), group, currentCenters);
                }
                break;
            }
        }

        // Phase 2: Adjacent-swap local search
        if (bestCrossings > 0) {
            bestCrossings = adjacentSwapPhase(optimizableGroups, groups, edges,
                    currentOrder, currentCenters, bestOrder, bestCrossings);
        }

        // Determine which groups actually changed
        List<String> reorderedGroups = new ArrayList<>();
        int elementMoves = 0;
        for (GroupInfo group : groups) {
            List<String> originalOrder = group.elementIds();
            List<String> finalOrder = bestOrder.get(group.groupId());
            if (finalOrder != null && !finalOrder.equals(originalOrder)) {
                reorderedGroups.add(group.groupId());
                // Count elements that moved position
                for (int i = 0; i < originalOrder.size(); i++) {
                    if (!originalOrder.get(i).equals(finalOrder.get(i))) {
                        elementMoves++;
                    }
                }
            }
        }

        return new OptimizationResult(
                crossingsBefore, bestCrossings, reorderedGroups, bestOrder, elementMoves);
    }

    /**
     * Adjacent-swap local search phase. Tries swapping each pair of adjacent
     * elements within each optimizable group and greedily accepts any swap that
     * reduces total crossings. Iterates until no improving swap is found or
     * {@link #MAX_SWAP_ITERATIONS} is reached.
     *
     * @return the best crossing count achieved (may be unchanged if no improvement found)
     */
    private int adjacentSwapPhase(
            List<GroupInfo> optimizableGroups, List<GroupInfo> allGroups,
            List<InterGroupEdge> edges,
            Map<String, List<String>> currentOrder,
            Map<String, List<int[]>> currentCenters,
            Map<String, List<String>> bestOrder, int bestCrossings) {

        // Restore currentOrder/currentCenters to bestOrder before starting swaps
        for (Map.Entry<String, List<String>> entry : bestOrder.entrySet()) {
            currentOrder.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        for (GroupInfo group : allGroups) {
            recomputeCenters(group.groupId(),
                    currentOrder.get(group.groupId()), group, currentCenters);
        }

        for (int iteration = 0; iteration < MAX_SWAP_ITERATIONS; iteration++) {
            boolean improved = false;

            for (GroupInfo group : optimizableGroups) {
                String groupId = group.groupId();
                List<String> order = currentOrder.get(groupId);

                for (int i = 0; i < order.size() - 1; i++) {
                    // Swap adjacent elements
                    String tmp = order.get(i);
                    order.set(i, order.get(i + 1));
                    order.set(i + 1, tmp);
                    recomputeCenters(groupId, order, group, currentCenters);

                    List<GroupInfo> updatedGroups =
                            buildUpdatedGroups(allGroups, currentOrder, currentCenters);
                    int crossingsNow = countStraightLineCrossings(updatedGroups, edges);

                    if (crossingsNow < bestCrossings) {
                        bestCrossings = crossingsNow;
                        // Copy entire current state as new best
                        for (Map.Entry<String, List<String>> entry : currentOrder.entrySet()) {
                            bestOrder.put(entry.getKey(), new ArrayList<>(entry.getValue()));
                        }
                        improved = true;
                    } else {
                        // Revert swap
                        order.set(i + 1, order.get(i));
                        order.set(i, tmp);
                        recomputeCenters(groupId, order, group, currentCenters);
                    }
                }
            }

            if (!improved) {
                break; // No swap helped — converged
            }
        }

        return bestCrossings;
    }

    /**
     * Counts straight-line crossings between inter-group edges.
     * Each edge is a straight line from source element center to target element center.
     */
    int countStraightLineCrossings(List<GroupInfo> groups, List<InterGroupEdge> edges) {
        // Build element center lookup
        Map<String, int[]> centerByElementId = new HashMap<>();
        for (GroupInfo group : groups) {
            for (int i = 0; i < group.elementIds().size(); i++) {
                centerByElementId.put(group.elementIds().get(i), group.centers().get(i));
            }
        }

        // Collect edge segments as center-to-center lines
        List<double[]> segments = new ArrayList<>(); // [x1, y1, x2, y2]
        for (InterGroupEdge edge : edges) {
            int[] srcCenter = centerByElementId.get(edge.sourceElementId());
            int[] tgtCenter = centerByElementId.get(edge.targetElementId());
            if (srcCenter != null && tgtCenter != null) {
                segments.add(new double[]{
                        srcCenter[0], srcCenter[1],
                        tgtCenter[0], tgtCenter[1]});
            }
        }

        // Count pairwise intersections
        int count = 0;
        for (int i = 0; i < segments.size(); i++) {
            for (int j = i + 1; j < segments.size(); j++) {
                double[] s1 = segments.get(i);
                double[] s2 = segments.get(j);
                if (segmentsIntersect(
                        s1[0], s1[1], s1[2], s1[3],
                        s2[0], s2[1], s2[2], s2[3])) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Computes barycenter values for each element in a group.
     * The barycenter is the average position index of connected elements in other groups.
     * Elements with no inter-group connections get Double.MAX_VALUE (sorted to end).
     */
    private double[] computeBarycenters(
            String groupId, List<String> elementOrder,
            Map<String, List<String>> allOrders,
            Map<String, List<int[]>> allCenters,
            List<InterGroupEdge> edges) {

        double[] barycenters = new double[elementOrder.size()];
        int[] connectionCounts = new int[elementOrder.size()];
        Arrays.fill(barycenters, 0.0);

        // Build element-to-index mapping for this group
        Map<String, Integer> elementIndex = new HashMap<>();
        for (int i = 0; i < elementOrder.size(); i++) {
            elementIndex.put(elementOrder.get(i), i);
        }

        // For each edge involving this group, accumulate connected element positions
        for (InterGroupEdge edge : edges) {
            if (edge.sourceGroupId().equals(groupId)) {
                // Source is in this group, target is in another
                Integer srcIdx = elementIndex.get(edge.sourceElementId());
                if (srcIdx != null) {
                    int targetPosition = getElementPositionInGroup(
                            edge.targetGroupId(), edge.targetElementId(), allOrders);
                    if (targetPosition >= 0) {
                        barycenters[srcIdx] += targetPosition;
                        connectionCounts[srcIdx]++;
                    }
                }
            }
            if (edge.targetGroupId().equals(groupId)) {
                // Target is in this group, source is in another
                Integer tgtIdx = elementIndex.get(edge.targetElementId());
                if (tgtIdx != null) {
                    int sourcePosition = getElementPositionInGroup(
                            edge.sourceGroupId(), edge.sourceElementId(), allOrders);
                    if (sourcePosition >= 0) {
                        barycenters[tgtIdx] += sourcePosition;
                        connectionCounts[tgtIdx]++;
                    }
                }
            }
        }

        // Compute averages; unconnected elements get MAX_VALUE
        for (int i = 0; i < barycenters.length; i++) {
            if (connectionCounts[i] > 0) {
                barycenters[i] = barycenters[i] / connectionCounts[i];
            } else {
                barycenters[i] = Double.MAX_VALUE;
            }
        }

        return barycenters;
    }

    /**
     * Gets the position index of an element within its group's current ordering.
     */
    private int getElementPositionInGroup(
            String groupId, String elementId, Map<String, List<String>> allOrders) {
        List<String> order = allOrders.get(groupId);
        if (order == null) return -1;
        return order.indexOf(elementId);
    }

    /**
     * Sorts elements by their barycenter values (stable sort preserves
     * relative order of elements with equal barycenters).
     */
    private List<String> sortByBarycenter(List<String> elementOrder, double[] barycenters) {
        // Create index array for sorting
        Integer[] indices = new Integer[elementOrder.size()];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = i;
        }

        // Stable sort by barycenter value
        Arrays.sort(indices, (a, b) -> Double.compare(barycenters[a], barycenters[b]));

        List<String> sorted = new ArrayList<>(elementOrder.size());
        for (int idx : indices) {
            sorted.add(elementOrder.get(idx));
        }
        return sorted;
    }

    /**
     * Updates the center positions for a group to match a new element ordering.
     * Elements are assigned to position SLOTS from the original group — element
     * at new index 0 gets the center that was at slot 0, etc. This simulates
     * re-layout after reordering.
     *
     * <p>Note: This is an approximation. For grid layouts or mixed-size elements
     * (autoWidth), actual re-layout positions may differ from these uniform slots.
     * The approximation is adequate for row/column with uniform sizing (the common
     * case) and produces correct convergence direction for grid layouts.</p>
     */
    private void recomputeCenters(
            String groupId, List<String> newOrder,
            GroupInfo originalGroup, Map<String, List<int[]>> allCenters) {
        // Position slots are the original centers in their original order.
        // After reordering, element at new index i occupies slot i's position.
        List<int[]> originalPositionSlots = originalGroup.centers();
        List<int[]> newCenters = new ArrayList<>(newOrder.size());
        for (int i = 0; i < newOrder.size(); i++) {
            newCenters.add(originalPositionSlots.get(i));
        }
        allCenters.put(groupId, newCenters);
    }

    /**
     * Builds updated GroupInfo list reflecting current orderings and centers.
     */
    private List<GroupInfo> buildUpdatedGroups(
            List<GroupInfo> originalGroups,
            Map<String, List<String>> currentOrder,
            Map<String, List<int[]>> currentCenters) {
        List<GroupInfo> updated = new ArrayList<>(originalGroups.size());
        for (GroupInfo group : originalGroups) {
            String gid = group.groupId();
            updated.add(new GroupInfo(
                    gid,
                    currentOrder.get(gid),
                    currentCenters.get(gid)));
        }
        return updated;
    }

    /**
     * Deep-copies the order map to allow safe reversion.
     */
    private Map<String, List<String>> deepCopyOrder(Map<String, List<String>> order) {
        Map<String, List<String>> copy = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : order.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copy;
    }

    /**
     * Line segment intersection test using cross-product orientation.
     * Identical to {@link LayoutQualityAssessor#segmentsIntersect} — duplicated
     * to keep this class free of dependencies.
     */
    static boolean segmentsIntersect(double p1x, double p1y, double p2x, double p2y,
                                     double p3x, double p3y, double p4x, double p4y) {
        double d1 = crossProduct(p3x, p3y, p4x, p4y, p1x, p1y);
        double d2 = crossProduct(p3x, p3y, p4x, p4y, p2x, p2y);
        double d3 = crossProduct(p1x, p1y, p2x, p2y, p3x, p3y);
        double d4 = crossProduct(p1x, p1y, p2x, p2y, p4x, p4y);

        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
                && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }
        return false;
    }

    private static double crossProduct(double ax, double ay, double bx, double by,
                                       double cx, double cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }
}
