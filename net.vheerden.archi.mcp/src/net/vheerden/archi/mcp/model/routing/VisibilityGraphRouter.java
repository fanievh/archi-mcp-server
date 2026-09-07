package net.vheerden.archi.mcp.model.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.model.routing.VisEdge.Direction;

/**
 * A* path search with direction tracking for orthogonal visibility graphs.
 * Pure-geometry class — no EMF/SWT dependencies.
 *
 * <p>Finds optimal connection routes that minimize a weighted cost of
 * {@code pathLength + BEND_PENALTY * bendCount}. Direction changes (bends)
 * are tracked via an augmented search state {@code (node, entryDirection)}.</p>
 */
public class VisibilityGraphRouter {

    private static final Logger logger = LoggerFactory.getLogger(VisibilityGraphRouter.class);

    /** Default bend penalty in pixels — penalizes direction changes. */
    public static final int DEFAULT_BEND_PENALTY = 30;

    /** Small penalty for edges moving away from target on dominant axis. */
    static final int DIRECTION_PENALTY = 15;

    /** Default congestion weight — 0.0 disables congestion-aware routing. */
    static final double DEFAULT_CONGESTION_WEIGHT = 0.0;

    /**
     * JVM-property keys for overriding the A* cost weights during calibration.
     * When set at JVM startup (e.g. {@code -Darchi.mcp.weights.clearance=150}),
     * the corresponding DEFAULT_* constant below reads from the property; absence
     * preserves shipping defaults, so an unset JVM behaves exactly as if these
     * did not exist.
     */
    static final String CLEARANCE_WEIGHT_PROP = "archi.mcp.weights.clearance";
    static final String DIRECTIONALITY_WEIGHT_PROP = "archi.mcp.weights.directionality";
    static final String OCCUPANCY_WEIGHT_PROP = "archi.mcp.weights.occupancy";

    /** Default clearance weight — penalizes edges close to obstacle boundaries. */
    static final double DEFAULT_CLEARANCE_WEIGHT =
            Double.parseDouble(System.getProperty(CLEARANCE_WEIGHT_PROP, "75.0"));

    /** Default directionality weight — penalizes edges moving away from target. */
    static final double DEFAULT_DIRECTIONALITY_WEIGHT =
            Double.parseDouble(System.getProperty(DIRECTIONALITY_WEIGHT_PROP, "30.0"));

    /** Maximum effective clearance — corridors wider than this get no additional benefit. */
    static final double MAX_EFFECTIVE_CLEARANCE = 60.0;

    /** Default occupancy weight — multiplicative penalty for occupied corridors. */
    public static final double DEFAULT_OCCUPANCY_WEIGHT =
            Double.parseDouble(System.getProperty(OCCUPANCY_WEIGHT_PROP, "0.75"));

    /**
     * Diagnostic flag. When {@code -Darchi.mcp.diag.routingcost=true}
     * is set, {@link #findPath} emits a per-route cost breakdown summary to stdout
     * after computing the chosen path. Zero cost when unset (short-circuits before
     * any log assembly).
     */
    static final String COST_DIAG_FLAG = "archi.mcp.diag.routingcost";

    private final int bendPenalty;
    private final double congestionWeight;
    private final double clearanceWeight;
    private final double directionalityWeight;
    private final double occupancyWeight;
    private final List<RoutingRect> groupBoundaries;

    public VisibilityGraphRouter() {
        this(DEFAULT_BEND_PENALTY);
    }

    public VisibilityGraphRouter(int bendPenalty) {
        this(bendPenalty, DEFAULT_CONGESTION_WEIGHT);
    }

    /**
     * Creates a router with configurable bend penalty and congestion weight.
     *
     * @param bendPenalty      penalty in pixels for direction changes (bends)
     * @param congestionWeight multiplier for local obstacle density — 0.0 disables congestion awareness
     */
    public VisibilityGraphRouter(int bendPenalty, double congestionWeight) {
        this(bendPenalty, congestionWeight, DEFAULT_CLEARANCE_WEIGHT);
    }

    /**
     * Creates a router with configurable bend penalty, congestion weight,
     * and clearance weight.
     *
     * @param bendPenalty      penalty in pixels for direction changes (bends)
     * @param congestionWeight multiplier for local obstacle density — 0.0 disables congestion awareness
     * @param clearanceWeight  inverse clearance penalty weight — higher values penalize edges near obstacles more
     */
    public VisibilityGraphRouter(int bendPenalty, double congestionWeight, double clearanceWeight) {
        this(bendPenalty, congestionWeight, clearanceWeight, DEFAULT_DIRECTIONALITY_WEIGHT);
    }

    /**
     * Creates a router with configurable bend penalty, congestion weight,
     * clearance weight, and directionality weight.
     *
     * @param bendPenalty          penalty in pixels for direction changes (bends)
     * @param congestionWeight     multiplier for local obstacle density — 0.0 disables congestion awareness
     * @param clearanceWeight      inverse clearance penalty weight — higher values penalize edges near obstacles more
     * @param directionalityWeight cosine-based penalty for edges moving away from target
     */
    public VisibilityGraphRouter(int bendPenalty, double congestionWeight, double clearanceWeight,
            double directionalityWeight) {
        this(bendPenalty, congestionWeight, clearanceWeight, directionalityWeight, List.of());
    }

    /**
     * Creates a router with all configurable weights and group boundaries.
     *
     * @param bendPenalty          penalty in pixels for direction changes (bends)
     * @param congestionWeight     multiplier for local obstacle density — 0.0 disables congestion awareness
     * @param clearanceWeight      inverse clearance penalty weight — higher values penalize edges near obstacles more
     * @param directionalityWeight cosine-based penalty for edges moving away from target
     * @param groupBoundaries      group rectangles for group-wall clearance cost; connections near group walls
     *                             get higher cost to prefer inter-group gap corridors
     */
    public VisibilityGraphRouter(int bendPenalty, double congestionWeight, double clearanceWeight,
            double directionalityWeight, List<RoutingRect> groupBoundaries) {
        this(bendPenalty, congestionWeight, clearanceWeight, directionalityWeight, groupBoundaries,
                DEFAULT_OCCUPANCY_WEIGHT);
    }

    /**
     * Creates a router with all configurable weights, group boundaries, and occupancy weight.
     *
     * @param bendPenalty          penalty in pixels for direction changes (bends)
     * @param congestionWeight     multiplier for local obstacle density — 0.0 disables congestion awareness
     * @param clearanceWeight      inverse clearance penalty weight — higher values penalize edges near obstacles more
     * @param directionalityWeight cosine-based penalty for edges moving away from target
     * @param groupBoundaries      group rectangles for group-wall clearance cost
     * @param occupancyWeight      multiplicative penalty for occupied corridors — 0.0 disables
     */
    public VisibilityGraphRouter(int bendPenalty, double congestionWeight, double clearanceWeight,
            double directionalityWeight, List<RoutingRect> groupBoundaries, double occupancyWeight) {
        if (bendPenalty < 0) {
            throw new IllegalArgumentException("bendPenalty must be non-negative: " + bendPenalty);
        }
        if (congestionWeight < 0) {
            throw new IllegalArgumentException("congestionWeight must be non-negative: " + congestionWeight);
        }
        if (clearanceWeight < 0) {
            throw new IllegalArgumentException("clearanceWeight must be non-negative: " + clearanceWeight);
        }
        if (directionalityWeight < 0) {
            throw new IllegalArgumentException("directionalityWeight must be non-negative: " + directionalityWeight);
        }
        if (occupancyWeight < 0) {
            throw new IllegalArgumentException("occupancyWeight must be non-negative: " + occupancyWeight);
        }
        this.bendPenalty = bendPenalty;
        this.congestionWeight = congestionWeight;
        this.clearanceWeight = clearanceWeight;
        this.directionalityWeight = directionalityWeight;
        this.occupancyWeight = occupancyWeight;
        this.groupBoundaries = groupBoundaries != null ? groupBoundaries : List.of();
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
        return findPath(graph, source, target, null);
    }

    /**
     * Finds the optimal path with occupancy-aware corridor cost.
     * When a non-null tracker is provided, corridors already used by prior
     * connections receive a multiplicative distance penalty:
     * {@code effectiveDistance = distance * (1 + occupancyWeight * occupancy)}.
     *
     * @param graph            the visibility graph (must be built and have ports added)
     * @param source           the source node
     * @param target           the target node
     * @param occupancyTracker corridor occupancy tracker (nullable — null disables occupancy cost)
     * @return ordered list of nodes from source to target, or empty list if no path exists
     */
    public List<VisNode> findPath(OrthogonalVisibilityGraph graph, VisNode source, VisNode target,
            CorridorOccupancyTracker occupancyTracker) {
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
                List<VisNode> path = reconstructPath(current);
                if (Boolean.getBoolean(COST_DIAG_FLAG)) {
                    emitRoutingCostDiagnostic(path, graph, occupancyTracker, source, target);
                }
                return path;
            }

            StateKey currentKey = new StateKey(current.node, current.entryDir);
            Double bestKnown = bestGCost.get(currentKey);
            if (bestKnown != null && current.gCost > bestKnown) {
                continue; // Skip stale entry
            }

            for (VisEdge edge : graph.getEdges(current.node)) {
                CostBreakdown cost = computeEdgeCost(
                        current.node, edge, target, current.entryDir, graph, occupancyTracker);
                double newGCost = current.gCost + cost.total();
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
     * Computes the per-edge cost breakdown for A* expansion.
     *
     * <p>Cost-decomposition refactor: previously the cost terms
     * ({@code base + bend + direction + congestion + clearance + directionality
     * + groupWall + occupancyExtra}) were inlined inside {@link #findPath}'s main
     * loop. Extraction is behavior-preserving — the returned
     * {@link CostBreakdown#total()} equals the former {@code newGCost - current.gCost}
     * increment per edge, bit-for-bit.</p>
     *
     * <p>Package-private so tests and the path-evaluation entry point
     * ({@link #evaluatePathCost}) can call it directly.</p>
     *
     * @param from             source node of the edge
     * @param edge             the outgoing edge being evaluated
     * @param target           final routing target (for direction + directionality costs)
     * @param entryDir         direction by which the search arrived at {@code from}; {@code null}
     *                         for the initial state (bend cost is then 0)
     * @param graph            visibility graph (used for density + clearance lookups)
     * @param occupancyTracker corridor occupancy tracker; nullable (disables occupancyExtra)
     */
    CostBreakdown computeEdgeCost(
            VisNode from, VisEdge edge, VisNode target,
            Direction entryDir,
            OrthogonalVisibilityGraph graph,
            CorridorOccupancyTracker occupancyTracker) {
        double bendCost = (entryDir != null && entryDir != edge.direction())
                ? bendPenalty : 0.0;
        double directionCost = computeDirectionCost(edge.direction(), from, target);
        int density = graph.computeEdgeDensity(from, edge.target());
        double congestionCost = (density >= 2) ? congestionWeight * density : 0.0;
        double clearanceCost = 0.0;
        if (clearanceWeight > 0) {
            double clearance = graph.computePerpendicularClearance(from, edge.target());
            if (clearance < Double.MAX_VALUE) {
                clearanceCost = clearanceWeight / Math.max(Math.min(clearance, MAX_EFFECTIVE_CLEARANCE), 1.0);
            }
        }
        double directionalityCost = computeCorridorDirectionalityCost(edge.direction(), from, target);
        double groupWallClearanceCost = 0.0;
        if (clearanceWeight > 0 && !groupBoundaries.isEmpty()) {
            double groupClearance = computeGroupWallClearance(from, edge.target());
            if (groupClearance < Double.MAX_VALUE) {
                groupWallClearanceCost = clearanceWeight / Math.max(Math.min(groupClearance, MAX_EFFECTIVE_CLEARANCE), 1.0);
            }
        }
        double baseDistance = edge.distance();
        double occupancyExtra = 0.0;
        if (occupancyTracker != null && occupancyWeight > 0) {
            int occupancy = occupancyTracker.getOccupancy(
                    from.x(), from.y(), edge.target().x(), edge.target().y());
            if (occupancy > 0) {
                occupancyExtra = baseDistance * occupancyWeight * occupancy;
            }
        }
        return new CostBreakdown(baseDistance, occupancyExtra, bendCost, directionCost,
                congestionCost, clearanceCost, directionalityCost, groupWallClearanceCost);
    }

    /**
     * Path-evaluation mode: sum the A* cost breakdown
     * for a pre-built canonical path, without running A*.
     *
     * <p>For each consecutive pair {@code (path[i], path[i+1])}, synthesises a
     * {@link VisEdge} with the pair's cardinal direction and Manhattan distance,
     * then invokes {@link #computeEdgeCost}. Entry direction tracks the prior
     * segment (null for the first segment, matching {@link #findPath}'s initial
     * state behaviour).</p>
     *
     * <p>Used to evaluate oracle canonical paths under live weight
     * configuration, and by weight-sweep Metric A scoring.</p>
     *
     * <p><b>Requires orthogonal path:</b> canonicalisation guarantees Δx=0 or
     * Δy=0 per segment (routing-cost design note §1.1). Diagonal segments produce
     * undefined direction; caller must canonicalise before invocation.</p>
     *
     * @param graph         visibility graph (used for density + clearance lookups)
     * @param canonicalPath ordered list of nodes, length ≥ 2; {@code null}/shorter returns {@link CostBreakdown#EMPTY}
     * @param tracker       corridor occupancy tracker; nullable
     * @return summed per-edge {@link CostBreakdown} across the path
     */
    public CostBreakdown evaluatePathCost(
            OrthogonalVisibilityGraph graph,
            List<VisNode> canonicalPath,
            CorridorOccupancyTracker tracker) {
        if (canonicalPath == null || canonicalPath.size() < 2) {
            return CostBreakdown.EMPTY;
        }
        VisNode target = canonicalPath.get(canonicalPath.size() - 1);
        CostBreakdown total = CostBreakdown.EMPTY;
        Direction prevDir = null;
        for (int i = 0; i < canonicalPath.size() - 1; i++) {
            VisNode from = canonicalPath.get(i);
            VisNode to = canonicalPath.get(i + 1);
            Direction dir = directionBetween(from, to);
            double dist = Math.abs(to.x() - from.x()) + Math.abs(to.y() - from.y());
            VisEdge syntheticEdge = new VisEdge(to, dist, dir);
            CostBreakdown segCost = computeEdgeCost(from, syntheticEdge, target, prevDir, graph, tracker);
            total = total.plus(segCost);
            prevDir = dir;
        }
        return total;
    }

    /**
     * Cardinal direction from {@code from} to {@code to}. Assumes orthogonal
     * segments (Δx=0 or Δy=0 after canonicalisation). For diagonal inputs, picks
     * the dominant axis; returns {@code null} only when both deltas are zero.
     */
    private static Direction directionBetween(VisNode from, VisNode to) {
        int dx = to.x() - from.x();
        int dy = to.y() - from.y();
        if (dx == 0 && dy == 0) {
            return null;
        }
        if (Math.abs(dx) >= Math.abs(dy)) {
            return dx >= 0 ? Direction.RIGHT : Direction.LEFT;
        }
        return dy >= 0 ? Direction.DOWN : Direction.UP;
    }

    /**
     * Diagnostic emission — flag-gated per-route cost breakdown.
     * Called from {@link #findPath} at target-reached exit when
     * {@code -Darchi.mcp.diag.routingcost=true}.
     *
     * <p>Emits a single line matching design note §2.2 runtime-cost format.
     * Oracle-cost comparison + corridor/port-match + final-class fields are
     * added when the oracle map is wired in.</p>
     */
    private void emitRoutingCostDiagnostic(
            List<VisNode> path,
            OrthogonalVisibilityGraph graph,
            CorridorOccupancyTracker tracker,
            VisNode source, VisNode target) {
        CostBreakdown cb = evaluatePathCost(graph, path, tracker);
        logger.debug(String.format(
                "routing-cost diagnostic: findPath src=(%d,%d) tgt=(%d,%d) bp=%d"
                        + " runtime-cost {base=%.1f, occExtra=%.1f, bend=%.1f,"
                        + " direction=%.1f, congestion=%.1f, clearance=%.1f,"
                        + " directionality=%.1f, groupWall=%.1f} total=%.1f",
                source.x(), source.y(), target.x(), target.y(), path.size(),
                cb.base(), cb.occupancyExtra(), cb.bend(), cb.direction(),
                cb.congestion(), cb.clearance(), cb.directionality(), cb.groupWall(),
                cb.total()));
    }

    /**
     * Computes a small directional preference cost.
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

    /**
     * Computes a continuous directional penalty using cosine of the angle between
     * the edge direction and the vector to the target.
     *
     * <p>Penalty = directionalityWeight * (1 - cos(angle)) / 2, giving:
     * <ul>
     *   <li>0 when edge moves directly toward target</li>
     *   <li>directionalityWeight/2 when edge is perpendicular to target</li>
     *   <li>directionalityWeight when edge moves directly away from target</li>
     * </ul>
     */
    private double computeCorridorDirectionalityCost(Direction edgeDir, VisNode current, VisNode target) {
        if (directionalityWeight <= 0) {
            return 0.0;
        }
        double dx = target.x() - current.x();
        double dy = target.y() - current.y();
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist < 1.0) {
            return 0.0; // At target — no directional preference
        }
        // Normalize target vector
        double txNorm = dx / dist;
        double tyNorm = dy / dist;

        // Edge direction unit vectors: UP=(0,-1), DOWN=(0,1), LEFT=(-1,0), RIGHT=(1,0)
        double ex, ey;
        switch (edgeDir) {
            case UP:    ex = 0; ey = -1; break;
            case DOWN:  ex = 0; ey = 1;  break;
            case LEFT:  ex = -1; ey = 0; break;
            case RIGHT: ex = 1; ey = 0;  break;
            default:    return 0.0;
        }

        double cosAngle = ex * txNorm + ey * tyNorm; // dot product
        return directionalityWeight * (1.0 - cosAngle) / 2.0;
    }

    /**
     * Computes minimum perpendicular distance from an edge to the nearest group wall.
     * Uses the same logic as {@link OrthogonalVisibilityGraph#computePerpendicularClearance}
     * but against group boundaries instead of expanded obstacles.
     *
     * <p>For horizontal edges, measures vertical distance to nearest group top/bottom boundary.
     * For vertical edges, measures horizontal distance to nearest group left/right boundary.
     * Only considers group walls whose span overlaps the edge's extent.</p>
     *
     * @param from edge start node
     * @param to   edge end node
     * @return minimum perpendicular clearance to any group wall, or {@code Double.MAX_VALUE} if no nearby groups
     */
    double computeGroupWallClearance(VisNode from, VisNode to) {
        double minClearance = Double.MAX_VALUE;
        boolean isHorizontal = (from.y() == to.y());

        if (isHorizontal) {
            int edgeY = from.y();
            int minX = Math.min(from.x(), to.x());
            int maxX = Math.max(from.x(), to.x());

            for (RoutingRect gr : groupBoundaries) {
                int grLeft = gr.x();
                int grRight = gr.x() + gr.width();
                int grTop = gr.y();
                int grBottom = gr.y() + gr.height();

                // Skip groups whose x-range doesn't overlap the edge
                if (grRight <= minX || grLeft >= maxX) {
                    continue;
                }
                double distToTop = Math.abs(edgeY - grTop);
                double distToBottom = Math.abs(edgeY - grBottom);
                double nearest = Math.min(distToTop, distToBottom);
                // Edge inside group — still measure distance to walls (groups are transparent)
                minClearance = Math.min(minClearance, nearest);
            }
        } else {
            // Vertical edge
            int edgeX = from.x();
            int minY = Math.min(from.y(), to.y());
            int maxY = Math.max(from.y(), to.y());

            for (RoutingRect gr : groupBoundaries) {
                int grLeft = gr.x();
                int grRight = gr.x() + gr.width();
                int grTop = gr.y();
                int grBottom = gr.y() + gr.height();

                // Skip groups whose y-range doesn't overlap the edge
                if (grBottom <= minY || grTop >= maxY) {
                    continue;
                }
                double distToLeft = Math.abs(edgeX - grLeft);
                double distToRight = Math.abs(edgeX - grRight);
                double nearest = Math.min(distToLeft, distToRight);
                // Edge inside group — still measure distance to walls (groups are transparent)
                minClearance = Math.min(minClearance, nearest);
            }
        }

        return minClearance;
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

    /**
     * Per-edge cost breakdown produced by {@link #computeEdgeCost}.
     *
     * <p>Each field is in A* cost units (pixels-equivalent). {@link #total()} is
     * the summed cost contribution the A* main loop adds to {@code newGCost}.</p>
     *
     * <p>Fields align with the routing-cost design note §2.2 per-route diagnostic format:
     * {@code {base, occupancyExtra, bend, direction, congestion, clearance,
     * directionality, groupWall}}.</p>
     *
     * <p>Immutable value type; {@link #plus(CostBreakdown)} supports path-sum
     * accumulation in (b1) path-evaluation mode.</p>
     */
    public record CostBreakdown(
            double base,
            double occupancyExtra,
            double bend,
            double direction,
            double congestion,
            double clearance,
            double directionality,
            double groupWall) {

        /** Zero-cost breakdown; neutral element for {@link #plus}. */
        public static final CostBreakdown EMPTY = new CostBreakdown(0, 0, 0, 0, 0, 0, 0, 0);

        /**
         * Total cost contribution — sum of all 8 terms.
         * Matches the {@code newGCost - current.gCost} increment per edge in
         * {@link #findPath}'s main loop (behavior-preservation invariant).
         */
        public double total() {
            return base + occupancyExtra + bend + direction
                    + congestion + clearance + directionality + groupWall;
        }

        /**
         * Element-wise addition, for summing per-edge costs across a path
         * in (b1) path-evaluation mode ({@link #evaluatePathCost}).
         */
        public CostBreakdown plus(CostBreakdown other) {
            return new CostBreakdown(
                    base + other.base,
                    occupancyExtra + other.occupancyExtra,
                    bend + other.bend,
                    direction + other.direction,
                    congestion + other.congestion,
                    clearance + other.clearance,
                    directionality + other.directionality,
                    groupWall + other.groupWall);
        }
    }
}
