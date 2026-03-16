package net.vheerden.archi.mcp.model.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import net.vheerden.archi.mcp.model.routing.VisEdge.Direction;

/**
 * A* path search with direction tracking for orthogonal visibility graphs (Story 10-6b).
 * Pure-geometry class — no EMF/SWT dependencies.
 *
 * <p>Finds optimal connection routes that minimize a weighted cost of
 * {@code pathLength + BEND_PENALTY * bendCount}. Direction changes (bends)
 * are tracked via an augmented search state {@code (node, entryDirection)}.</p>
 */
public class VisibilityGraphRouter {

    /** Default bend penalty in pixels — penalizes direction changes. */
    public static final int DEFAULT_BEND_PENALTY = 30;

    /** Small penalty for edges moving away from target on dominant axis (Story 10-28). */
    static final int DIRECTION_PENALTY = 15;

    /** Default congestion weight — 0.0 disables congestion-aware routing (Story 11-30). */
    static final double DEFAULT_CONGESTION_WEIGHT = 0.0;

    private final int bendPenalty;
    private final double congestionWeight;

    public VisibilityGraphRouter() {
        this(DEFAULT_BEND_PENALTY);
    }

    public VisibilityGraphRouter(int bendPenalty) {
        this(bendPenalty, DEFAULT_CONGESTION_WEIGHT);
    }

    /**
     * Creates a router with configurable bend penalty and congestion weight (Story 11-30).
     *
     * @param bendPenalty      penalty in pixels for direction changes (bends)
     * @param congestionWeight multiplier for local obstacle density — 0.0 disables congestion awareness
     */
    public VisibilityGraphRouter(int bendPenalty, double congestionWeight) {
        if (bendPenalty < 0) {
            throw new IllegalArgumentException("bendPenalty must be non-negative: " + bendPenalty);
        }
        if (congestionWeight < 0) {
            throw new IllegalArgumentException("congestionWeight must be non-negative: " + congestionWeight);
        }
        this.bendPenalty = bendPenalty;
        this.congestionWeight = congestionWeight;
    }

    /**
     * Finds the optimal path from source to target in the visibility graph,
     * minimizing {@code pathLength + bendPenalty * bendCount}.
     *
     * @param graph  the visibility graph (must be built and have ports added)
     * @param source the source node
     * @param target the target node
     * @return ordered list of nodes from source to target, or empty list if no path exists
     */
    public List<VisNode> findPath(OrthogonalVisibilityGraph graph, VisNode source, VisNode target) {
        if (source.equals(target)) {
            return List.of(source);
        }

        PriorityQueue<SearchState> openSet = new PriorityQueue<>();
        Map<StateKey, Double> bestGCost = new HashMap<>();

        // Initial state: no entry direction (first move is free)
        SearchState initial = new SearchState(source, null, 0.0, manhattanDistance(source, target), null);
        openSet.add(initial);
        bestGCost.put(new StateKey(source, null), 0.0);

        while (!openSet.isEmpty()) {
            SearchState current = openSet.poll();

            if (current.node.equals(target)) {
                return reconstructPath(current);
            }

            StateKey currentKey = new StateKey(current.node, current.entryDir);
            Double bestKnown = bestGCost.get(currentKey);
            if (bestKnown != null && current.gCost > bestKnown) {
                continue; // Skip stale entry
            }

            for (VisEdge edge : graph.getEdges(current.node)) {
                double bendCost = (current.entryDir != null && current.entryDir != edge.direction())
                        ? bendPenalty : 0.0;
                double directionCost = computeDirectionCost(edge.direction(), current.node, target);
                int density = graph.computeEdgeDensity(current.node, edge.target());
                double congestionCost = (density >= 2) ? congestionWeight * density : 0.0;
                double newGCost = current.gCost + edge.distance() + bendCost + directionCost + congestionCost;
                double hCost = manhattanDistance(edge.target(), target);

                StateKey neighborKey = new StateKey(edge.target(), edge.direction());
                Double neighborBest = bestGCost.get(neighborKey);

                if (neighborBest == null || newGCost < neighborBest) {
                    bestGCost.put(neighborKey, newGCost);
                    SearchState neighbor = new SearchState(
                            edge.target(), edge.direction(), newGCost, hCost, current);
                    openSet.add(neighbor);
                }
            }
        }

        return Collections.emptyList(); // No path found
    }

    /**
     * Computes a small directional preference cost (Story 10-28).
     * Penalizes edges that move away from the target on the dominant axis.
     * Returns 0 for edges moving toward target or on the perpendicular axis.
     */
    private double computeDirectionCost(Direction edgeDir, VisNode current, VisNode target) {
        int dx = target.x() - current.x();
        int dy = target.y() - current.y();

        // Dominant axis: the one with greater remaining distance
        boolean xDominant = Math.abs(dx) >= Math.abs(dy);

        if (xDominant) {
            // Penalize LEFT when target is RIGHT, or RIGHT when target is LEFT
            if (dx > 0 && edgeDir == Direction.LEFT) return DIRECTION_PENALTY;
            if (dx < 0 && edgeDir == Direction.RIGHT) return DIRECTION_PENALTY;
        } else {
            // Penalize UP when target is DOWN, or DOWN when target is UP
            if (dy > 0 && edgeDir == Direction.UP) return DIRECTION_PENALTY;
            if (dy < 0 && edgeDir == Direction.DOWN) return DIRECTION_PENALTY;
        }
        return 0.0;
    }

    private double manhattanDistance(VisNode a, VisNode b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y());
    }

    private List<VisNode> reconstructPath(SearchState goalState) {
        List<VisNode> path = new ArrayList<>();
        SearchState current = goalState;
        while (current != null) {
            path.add(current.node);
            current = current.parent;
        }
        Collections.reverse(path);
        return path;
    }

    /**
     * Search state for A* with direction tracking.
     * Two arrivals at the same node from different directions are distinct states.
     */
    private static class SearchState implements Comparable<SearchState> {
        final VisNode node;
        final Direction entryDir;
        final double gCost;
        final double hCost;
        final SearchState parent;

        SearchState(VisNode node, Direction entryDir, double gCost, double hCost, SearchState parent) {
            this.node = node;
            this.entryDir = entryDir;
            this.gCost = gCost;
            this.hCost = hCost;
            this.parent = parent;
        }

        double fCost() {
            return gCost + hCost;
        }

        @Override
        public int compareTo(SearchState other) {
            return Double.compare(this.fCost(), other.fCost());
        }
    }

    /**
     * Key for visited-state deduplication: (node, entryDirection).
     */
    private record StateKey(VisNode node, Direction dir) {}
}
