package net.vheerden.archi.mcp.model.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.model.routing.VisEdge.Direction;
import net.vheerden.archi.mcp.model.routing.VisNode.NodeType;

/**
 * Orthogonal visibility graph for obstacle-aware connection routing (Story 10-6a).
 * Pure-geometry class — no EMF/SWT dependencies.
 *
 * <p>Algorithm:</p>
 * <ol>
 *   <li>Expand each obstacle by a configurable margin</li>
 *   <li>Collect corner points from expanded obstacles</li>
 *   <li>Project horizontal and vertical scan lines from all interest points</li>
 *   <li>Compute scan-line intersections to create additional nodes</li>
 *   <li>Build edges between co-linear nodes, pruning those blocked by obstacles</li>
 * </ol>
 *
 * <p>Build the graph once per view with {@link #build(List)}, then inject
 * connection endpoints with {@link #addPortNodes(int, int, int, int)} for each
 * connection to route.</p>
 */
public class OrthogonalVisibilityGraph {

    /** Default clearance (px) around obstacles. */
    public static final int DEFAULT_MARGIN = 10;

    /** Radius (px) around edge midpoint for congestion density computation (Story 11-30). */
    static final int CONGESTION_RADIUS = 60;

    private final int margin;
    private final int perimeterMargin;
    private final boolean inclusiveBoundaries;
    private final Map<VisNode, List<VisEdge>> adjacency = new HashMap<>();
    private List<ExpandedRect> expandedObstacles = Collections.emptyList();

    public OrthogonalVisibilityGraph() {
        this(DEFAULT_MARGIN);
    }

    public OrthogonalVisibilityGraph(int margin) {
        this(margin, margin, false);
    }

    /**
     * Creates a visibility graph with separate clearance and perimeter margins (B36).
     *
     * @param margin clearance in pixels around obstacles (used by expandObstacles)
     * @param perimeterMargin extension in pixels beyond outermost obstacles for exterior routing
     */
    public OrthogonalVisibilityGraph(int margin, int perimeterMargin) {
        this(margin, perimeterMargin, false);
    }

    /**
     * Creates a visibility graph with the given margins and boundary mode.
     *
     * <p><b>Spike 10-19c finding:</b> Inclusive mode ({@code true}) eliminates
     * corner nodes that sit ON expanded obstacle boundaries, breaking graph
     * connectivity. Always use strict mode ({@code false}) in production.</p>
     *
     * @param margin clearance in pixels around obstacles
     * @param perimeterMargin extension in pixels beyond outermost obstacles for exterior routing
     * @param inclusiveBoundaries if true, segments touching obstacle edges are blocked
     */
    public OrthogonalVisibilityGraph(int margin, int perimeterMargin, boolean inclusiveBoundaries) {
        if (perimeterMargin < 0) {
            throw new IllegalArgumentException("perimeterMargin must be >= 0, got " + perimeterMargin);
        }
        this.margin = margin;
        this.perimeterMargin = perimeterMargin;
        this.inclusiveBoundaries = inclusiveBoundaries;
    }

    /**
     * Returns whether inclusive boundary mode is enabled.
     * When true, segments touching obstacle boundaries are blocked.
     */
    public boolean isInclusiveBoundaries() {
        return inclusiveBoundaries;
    }

    /**
     * Builds the visibility graph from the given obstacles.
     * Call once per view; then call {@link #addPortNodes} for each connection.
     *
     * @param obstacles element rectangles in absolute canvas coordinates
     */
    public void build(List<RoutingRect> obstacles) {
        adjacency.clear();

        // Step 1: Expand obstacles
        expandedObstacles = expandObstacles(obstacles);

        // Step 2: Collect corner points
        Set<VisNode> nodes = new HashSet<>();
        for (ExpandedRect er : expandedObstacles) {
            nodes.add(new VisNode(er.left, er.top, NodeType.OBSTACLE_CORNER));
            nodes.add(new VisNode(er.right, er.top, NodeType.OBSTACLE_CORNER));
            nodes.add(new VisNode(er.left, er.bottom, NodeType.OBSTACLE_CORNER));
            nodes.add(new VisNode(er.right, er.bottom, NodeType.OBSTACLE_CORNER));
        }

        // Step 2b: Add perimeter boundary nodes so A* can route around the outside
        // of all obstacles. Without these, connections between elements on opposite
        // sides of a dense field have no graph nodes to route through.
        if (!expandedObstacles.isEmpty()) {
            int perimLeft = Integer.MAX_VALUE, perimTop = Integer.MAX_VALUE;
            int perimRight = Integer.MIN_VALUE, perimBottom = Integer.MIN_VALUE;
            for (ExpandedRect er : expandedObstacles) {
                perimLeft = Math.min(perimLeft, er.left);
                perimTop = Math.min(perimTop, er.top);
                perimRight = Math.max(perimRight, er.right);
                perimBottom = Math.max(perimBottom, er.bottom);
            }
            // Extend perimeter beyond all obstacles by perimeterMargin (B36: separate from obstacle clearance)
            perimLeft -= perimeterMargin;
            perimTop -= perimeterMargin;
            perimRight += perimeterMargin;
            perimBottom += perimeterMargin;
            nodes.add(new VisNode(perimLeft, perimTop, NodeType.OBSTACLE_CORNER));
            nodes.add(new VisNode(perimRight, perimTop, NodeType.OBSTACLE_CORNER));
            nodes.add(new VisNode(perimLeft, perimBottom, NodeType.OBSTACLE_CORNER));
            nodes.add(new VisNode(perimRight, perimBottom, NodeType.OBSTACLE_CORNER));
        }

        // Remove corner nodes that fall inside another expanded obstacle
        nodes.removeIf(n -> isInsideAnyObstacle(n.x(), n.y(), expandedObstacles, null));

        // Step 3 & 4: Project scan lines and compute intersections
        Set<VisNode> scanNodes = computeScanIntersections(nodes);
        nodes.addAll(scanNodes);

        // Initialize adjacency for all nodes
        for (VisNode n : nodes) {
            adjacency.put(n, new ArrayList<>());
        }

        // Step 5: Build edges between co-linear, unblocked node pairs
        buildEdges();
    }

    /**
     * Injects source and target port nodes into the graph and connects them
     * to visible nodes. Call after {@link #build(List)} for each connection.
     *
     * @return the source and target VisNodes (index 0 = source, index 1 = target)
     */
    public VisNode[] addPortNodes(int srcX, int srcY, int tgtX, int tgtY) {
        // Use coordinate-based lookup to reuse existing nodes at same position
        VisNode src = findNodeAt(srcX, srcY);
        if (src == null) {
            src = new VisNode(srcX, srcY, NodeType.PORT);
            adjacency.put(src, new ArrayList<>());
            connectPortToGraph(src);
        }
        VisNode tgt = findNodeAt(tgtX, tgtY);
        if (tgt == null) {
            tgt = new VisNode(tgtX, tgtY, NodeType.PORT);
            adjacency.put(tgt, new ArrayList<>());
            connectPortToGraph(tgt);
        }

        return new VisNode[]{src, tgt};
    }

    /**
     * Returns the adjacency list representation of the graph.
     */
    public Map<VisNode, List<VisEdge>> getAdjacency() {
        return Collections.unmodifiableMap(adjacency);
    }

    /**
     * Returns all nodes in the graph.
     */
    public Set<VisNode> getNodes() {
        return Collections.unmodifiableSet(adjacency.keySet());
    }

    /**
     * Returns edges from the given node, or empty list if not in graph.
     */
    public List<VisEdge> getEdges(VisNode node) {
        return adjacency.getOrDefault(node, Collections.emptyList());
    }

    /**
     * Computes the local obstacle density around the midpoint of an edge (Story 11-30).
     * Counts the number of expanded obstacles whose bounding box overlaps a square
     * region centered at the edge midpoint with side length {@code 2 * CONGESTION_RADIUS}.
     *
     * @param from edge start node
     * @param to   edge end node
     * @return number of nearby obstacles (0 for sparse areas)
     */
    public int computeEdgeDensity(VisNode from, VisNode to) {
        int midX = (from.x() + to.x()) / 2;
        int midY = (from.y() + to.y()) / 2;

        int count = 0;
        for (ExpandedRect er : expandedObstacles) {
            // Check if the obstacle's bounding box overlaps the congestion region
            // Region: [midX - CONGESTION_RADIUS, midX + CONGESTION_RADIUS] x
            //         [midY - CONGESTION_RADIUS, midY + CONGESTION_RADIUS]
            if (er.right >= midX - CONGESTION_RADIUS && er.left <= midX + CONGESTION_RADIUS
                    && er.bottom >= midY - CONGESTION_RADIUS && er.top <= midY + CONGESTION_RADIUS) {
                count++;
            }
        }
        return count;
    }

    /**
     * Computes the minimum perpendicular distance from an edge to any expanded obstacle
     * boundary whose parallel range overlaps the edge (B41).
     *
     * <p>For a horizontal edge, measures vertical distance to the nearest obstacle
     * top/bottom boundary. For a vertical edge, measures horizontal distance to
     * the nearest obstacle left/right boundary.</p>
     *
     * @param from edge start node
     * @param to   edge end node
     * @return minimum perpendicular clearance in pixels, or {@code Double.MAX_VALUE} if no nearby obstacles
     */
    public double computePerpendicularClearance(VisNode from, VisNode to) {
        double minClearance = Double.MAX_VALUE;
        boolean isHorizontal = (from.y() == to.y());

        if (isHorizontal) {
            int edgeY = from.y();
            int minX = Math.min(from.x(), to.x());
            int maxX = Math.max(from.x(), to.x());

            for (ExpandedRect er : expandedObstacles) {
                // Check if obstacle's x-range overlaps the edge's x-span
                if (er.right <= minX || er.left >= maxX) {
                    continue;
                }
                // Perpendicular distance to top and bottom boundaries
                double distToTop = Math.abs(edgeY - er.top);
                double distToBottom = Math.abs(edgeY - er.bottom);
                double nearest = Math.min(distToTop, distToBottom);
                // Edge inside obstacle => clearance is 0
                if (edgeY > er.top && edgeY < er.bottom) {
                    nearest = 0;
                }
                minClearance = Math.min(minClearance, nearest);
            }
        } else {
            // Vertical edge
            int edgeX = from.x();
            int minY = Math.min(from.y(), to.y());
            int maxY = Math.max(from.y(), to.y());

            for (ExpandedRect er : expandedObstacles) {
                // Check if obstacle's y-range overlaps the edge's y-span
                if (er.bottom <= minY || er.top >= maxY) {
                    continue;
                }
                // Perpendicular distance to left and right boundaries
                double distToLeft = Math.abs(edgeX - er.left);
                double distToRight = Math.abs(edgeX - er.right);
                double nearest = Math.min(distToLeft, distToRight);
                // Edge inside obstacle => clearance is 0
                if (edgeX > er.left && edgeX < er.right) {
                    nearest = 0;
                }
                minClearance = Math.min(minClearance, nearest);
            }
        }

        return minClearance;
    }

    // ---- Internal ----

    private boolean containsCoordinate(Set<VisNode> nodes, int x, int y) {
        for (VisNode n : nodes) {
            if (n.x() == x && n.y() == y) return true;
        }
        return false;
    }

    private VisNode findNodeAt(int x, int y) {
        for (VisNode n : adjacency.keySet()) {
            if (n.x() == x && n.y() == y) return n;
        }
        return null;
    }

    private List<ExpandedRect> expandObstacles(List<RoutingRect> obstacles) {
        List<ExpandedRect> expanded = new ArrayList<>(obstacles.size());
        for (RoutingRect r : obstacles) {
            expanded.add(new ExpandedRect(
                    r.x() - margin,
                    r.y() - margin,
                    r.x() + r.width() + margin,
                    r.y() + r.height() + margin,
                    r));
        }
        return expanded;
    }

    /**
     * Projects horizontal and vertical scan lines from all interest points,
     * computes intersections to generate additional graph nodes.
     */
    private Set<VisNode> computeScanIntersections(Set<VisNode> cornerNodes) {
        // Collect all unique x and y coordinates from corner nodes
        TreeSet<Integer> xCoords = new TreeSet<>();
        TreeSet<Integer> yCoords = new TreeSet<>();
        for (VisNode n : cornerNodes) {
            xCoords.add(n.x());
            yCoords.add(n.y());
        }

        // Each corner projects a horizontal scan line (same y, all x coords)
        // and a vertical scan line (same x, all y coords).
        // Intersections of these scan lines create new nodes.
        // Use coordinate-based check (not record equals) to avoid duplicates
        // at positions already occupied by corner nodes.
        Set<VisNode> intersections = new HashSet<>();
        for (int x : xCoords) {
            for (int y : yCoords) {
                if (!containsCoordinate(cornerNodes, x, y)
                        && !isInsideAnyObstacle(x, y, expandedObstacles, null)) {
                    intersections.add(new VisNode(x, y, NodeType.SCAN_INTERSECTION));
                }
            }
        }
        return intersections;
    }

    /**
     * Builds edges between all pairs of nodes sharing the same x or y coordinate,
     * pruning edges that pass through obstacles.
     */
    private void buildEdges() {
        // Group nodes by x-coordinate (vertical scan lines)
        Map<Integer, List<VisNode>> byX = new HashMap<>();
        // Group nodes by y-coordinate (horizontal scan lines)
        Map<Integer, List<VisNode>> byY = new HashMap<>();

        for (VisNode n : adjacency.keySet()) {
            byX.computeIfAbsent(n.x(), k -> new ArrayList<>()).add(n);
            byY.computeIfAbsent(n.y(), k -> new ArrayList<>()).add(n);
        }

        // Connect adjacent nodes on same vertical line (sorted by y)
        for (List<VisNode> column : byX.values()) {
            column.sort((a, b) -> Integer.compare(a.y(), b.y()));
            connectAdjacentNodes(column, true);
        }

        // Connect adjacent nodes on same horizontal line (sorted by x)
        for (List<VisNode> row : byY.values()) {
            row.sort((a, b) -> Integer.compare(a.x(), b.x()));
            connectAdjacentNodes(row, false);
        }
    }

    /**
     * Connects consecutive nodes in a sorted list if the segment between them
     * is not blocked by any obstacle.
     *
     * @param sorted   nodes sorted by the varying coordinate
     * @param vertical true if nodes share x (vertical line), false if they share y
     */
    private void connectAdjacentNodes(List<VisNode> sorted, boolean vertical) {
        for (int i = 0; i < sorted.size() - 1; i++) {
            VisNode a = sorted.get(i);
            VisNode b = sorted.get(i + 1);

            if (!isSegmentBlocked(a.x(), a.y(), b.x(), b.y())) {
                double dist = vertical
                        ? Math.abs(b.y() - a.y())
                        : Math.abs(b.x() - a.x());

                Direction abDir = vertical
                        ? Direction.DOWN   // a.y < b.y since sorted
                        : Direction.RIGHT; // a.x < b.x since sorted
                Direction baDir = vertical
                        ? Direction.UP
                        : Direction.LEFT;

                adjacency.get(a).add(new VisEdge(b, dist, abDir));
                adjacency.get(b).add(new VisEdge(a, dist, baDir));
            }
        }
    }

    /**
     * Connects a port node to the nearest visible node in each cardinal direction.
     * At most 4 edges are added (up, down, left, right).
     */
    private void connectPortToGraph(VisNode port) {
        VisNode nearestUp = null, nearestDown = null, nearestLeft = null, nearestRight = null;
        double distUp = Double.MAX_VALUE, distDown = Double.MAX_VALUE;
        double distLeft = Double.MAX_VALUE, distRight = Double.MAX_VALUE;

        for (VisNode other : new ArrayList<>(adjacency.keySet())) {
            if (other.equals(port)) continue;

            // Same x: vertical candidates
            if (other.x() == port.x() && !isSegmentBlocked(port.x(), port.y(), other.x(), other.y())) {
                double d = Math.abs(other.y() - port.y());
                if (other.y() < port.y() && d < distUp) {
                    nearestUp = other; distUp = d;
                } else if (other.y() > port.y() && d < distDown) {
                    nearestDown = other; distDown = d;
                }
            }
            // Same y: horizontal candidates
            else if (other.y() == port.y() && !isSegmentBlocked(port.x(), port.y(), other.x(), other.y())) {
                double d = Math.abs(other.x() - port.x());
                if (other.x() < port.x() && d < distLeft) {
                    nearestLeft = other; distLeft = d;
                } else if (other.x() > port.x() && d < distRight) {
                    nearestRight = other; distRight = d;
                }
            }
        }

        // Connect to nearest in each direction
        if (nearestUp != null) addBidirectionalEdge(port, nearestUp, distUp, Direction.UP, Direction.DOWN);
        if (nearestDown != null) addBidirectionalEdge(port, nearestDown, distDown, Direction.DOWN, Direction.UP);
        if (nearestLeft != null) addBidirectionalEdge(port, nearestLeft, distLeft, Direction.LEFT, Direction.RIGHT);
        if (nearestRight != null) addBidirectionalEdge(port, nearestRight, distRight, Direction.RIGHT, Direction.LEFT);

        // If port has no connections on its scan lines, project to nearest
        // nodes on adjacent scan lines
        if (adjacency.get(port).isEmpty()) {
            connectPortViaScanProjection(port);
        }
    }

    private void addBidirectionalEdge(VisNode a, VisNode b, double dist,
                                       Direction abDir, Direction baDir) {
        adjacency.get(a).add(new VisEdge(b, dist, abDir));
        adjacency.get(b).add(new VisEdge(a, dist, baDir));
    }

    /**
     * If a port has no direct co-linear neighbors, project scan lines
     * from the port's coordinates and add intersection nodes, then connect.
     */
    private void connectPortViaScanProjection(VisNode port) {
        // Collect existing scan line coordinates
        TreeSet<Integer> xCoords = new TreeSet<>();
        TreeSet<Integer> yCoords = new TreeSet<>();
        for (VisNode n : adjacency.keySet()) {
            xCoords.add(n.x());
            yCoords.add(n.y());
        }

        // Project port's y onto existing x-coords (horizontal scan from port)
        for (int x : xCoords) {
            if (x == port.x()) continue;
            if (!isInsideAnyObstacle(x, port.y(), expandedObstacles, null)
                    && !isSegmentBlocked(port.x(), port.y(), x, port.y())) {
                VisNode intersection = new VisNode(x, port.y(), NodeType.SCAN_INTERSECTION);
                if (!adjacency.containsKey(intersection)) {
                    adjacency.put(intersection, new ArrayList<>());
                    // Connect this new intersection to its column neighbors
                    reconnectNodeToColumn(intersection);
                }
                double dist = Math.abs(x - port.x());
                Direction dir = x > port.x() ? Direction.RIGHT : Direction.LEFT;
                Direction rev = dir == Direction.RIGHT ? Direction.LEFT : Direction.RIGHT;
                adjacency.get(port).add(new VisEdge(intersection, dist, dir));
                adjacency.get(intersection).add(new VisEdge(port, dist, rev));
            }
        }

        // Project port's x onto existing y-coords (vertical scan from port)
        for (int y : yCoords) {
            if (y == port.y()) continue;
            if (!isInsideAnyObstacle(port.x(), y, expandedObstacles, null)
                    && !isSegmentBlocked(port.x(), port.y(), port.x(), y)) {
                VisNode intersection = new VisNode(port.x(), y, NodeType.SCAN_INTERSECTION);
                if (!adjacency.containsKey(intersection)) {
                    adjacency.put(intersection, new ArrayList<>());
                    reconnectNodeToRow(intersection);
                }
                double dist = Math.abs(y - port.y());
                Direction dir = y > port.y() ? Direction.DOWN : Direction.UP;
                Direction rev = dir == Direction.DOWN ? Direction.UP : Direction.DOWN;
                adjacency.get(port).add(new VisEdge(intersection, dist, dir));
                adjacency.get(intersection).add(new VisEdge(port, dist, rev));
            }
        }
    }

    /**
     * Connects a newly added node to its vertical column neighbors.
     */
    private void reconnectNodeToColumn(VisNode node) {
        List<VisNode> column = new ArrayList<>();
        for (VisNode n : adjacency.keySet()) {
            if (n.x() == node.x()) column.add(n);
        }
        column.sort((a, b) -> Integer.compare(a.y(), b.y()));
        reconnectInSortedList(column, true);
    }

    /**
     * Connects a newly added node to its horizontal row neighbors.
     */
    private void reconnectNodeToRow(VisNode node) {
        List<VisNode> row = new ArrayList<>();
        for (VisNode n : adjacency.keySet()) {
            if (n.y() == node.y()) row.add(n);
        }
        row.sort((a, b) -> Integer.compare(a.x(), b.x()));
        reconnectInSortedList(row, false);
    }

    /**
     * Re-establishes edges for a sorted list of nodes, only adding missing edges.
     */
    private void reconnectInSortedList(List<VisNode> sorted, boolean vertical) {
        for (int i = 0; i < sorted.size() - 1; i++) {
            VisNode a = sorted.get(i);
            VisNode b = sorted.get(i + 1);

            boolean alreadyConnected = adjacency.get(a).stream()
                    .anyMatch(e -> e.target().equals(b));
            if (!alreadyConnected && !isSegmentBlocked(a.x(), a.y(), b.x(), b.y())) {
                double dist = vertical
                        ? Math.abs(b.y() - a.y())
                        : Math.abs(b.x() - a.x());

                Direction abDir = vertical ? Direction.DOWN : Direction.RIGHT;
                Direction baDir = vertical ? Direction.UP : Direction.LEFT;

                adjacency.get(a).add(new VisEdge(b, dist, abDir));
                adjacency.get(b).add(new VisEdge(a, dist, baDir));
            }
        }
    }

    /**
     * Tests if an axis-aligned segment is blocked by any expanded obstacle.
     * Segments that touch the boundary of an obstacle are NOT considered blocked.
     */
    private boolean isSegmentBlocked(int x1, int y1, int x2, int y2) {
        for (ExpandedRect er : expandedObstacles) {
            if (segmentIntersectsExpandedRect(x1, y1, x2, y2, er)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Axis-aligned segment intersection test (optimized for orthogonal segments).
     * In strict mode (default), a segment is blocked only if it passes strictly
     * through the interior. In inclusive mode, segments touching the boundary
     * are also blocked.
     *
     * <p><b>Spike 10-19c finding:</b> Strict mode is required. Inclusive mode
     * eliminates corner nodes that sit ON expanded obstacle boundaries, breaking
     * graph connectivity for single-obstacle and wall scenarios. The 10px
     * expansion margin already provides sufficient clearance.</p>
     */
    private boolean segmentIntersectsExpandedRect(int x1, int y1, int x2, int y2,
                                                   ExpandedRect er) {
        if (inclusiveBoundaries) {
            if (x1 == x2) {
                if (x1 < er.left || x1 > er.right) return false;
                int minY = Math.min(y1, y2);
                int maxY = Math.max(y1, y2);
                return minY <= er.bottom && maxY >= er.top;
            } else {
                if (y1 < er.top || y1 > er.bottom) return false;
                int minX = Math.min(x1, x2);
                int maxX = Math.max(x1, x2);
                return minX <= er.right && maxX >= er.left;
            }
        } else {
            if (x1 == x2) {
                if (x1 <= er.left || x1 >= er.right) return false;
                int minY = Math.min(y1, y2);
                int maxY = Math.max(y1, y2);
                return minY < er.bottom && maxY > er.top;
            } else {
                if (y1 <= er.top || y1 >= er.bottom) return false;
                int minX = Math.min(x1, x2);
                int maxX = Math.max(x1, x2);
                return minX < er.right && maxX > er.left;
            }
        }
    }

    /**
     * Tests if a point is inside any expanded obstacle.
     * In strict mode, only points strictly inside are matched.
     * In inclusive mode, points on the boundary are also matched.
     *
     * @param exclude obstacle to exclude from the test (null = test all)
     */
    private boolean isInsideAnyObstacle(int x, int y, List<ExpandedRect> obstacles,
                                         ExpandedRect exclude) {
        for (ExpandedRect er : obstacles) {
            if (er == exclude) continue;
            if (inclusiveBoundaries) {
                if (x >= er.left && x <= er.right && y >= er.top && y <= er.bottom) {
                    return true;
                }
            } else {
                if (x > er.left && x < er.right && y > er.top && y < er.bottom) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Internal expanded obstacle representation.
     */
    record ExpandedRect(int left, int top, int right, int bottom, RoutingRect original) {
        int width() { return right - left; }
        int height() { return bottom - top; }
    }
}
