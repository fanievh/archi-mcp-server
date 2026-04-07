package net.vheerden.archi.mcp.model.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Routing pipeline orchestrator for obstacle-aware orthogonal connection routing (Story 10-6c).
 * Pure-geometry class — no EMF/SWT dependencies.
 *
 * <p>Builds an {@link OrthogonalVisibilityGraph} from obstacles and routes connections
 * via {@link VisibilityGraphRouter} A* search. Replaces the simple Z/L-shape
 * {@code ConnectionRouter} with optimal paths that avoid all obstacles.</p>
 */
public class RoutingPipeline {

    private static final Logger logger = LoggerFactory.getLogger(RoutingPipeline.class);

    public static final int DEFAULT_BEND_PENALTY = 30;
    public static final int DEFAULT_MARGIN = 10;
    static final int MICRO_JOG_THRESHOLD = 15;

    /** Default snap-to-straight threshold in pixels (backlog-b17). */
    public static final int DEFAULT_SNAP_THRESHOLD = 20;

    /** Default congestion weight for production routing (Story 11-30). */
    public static final double DEFAULT_CONGESTION_WEIGHT = 5.0;

    /** Minimum clearance in pixels between intermediate bendpoints and obstacle boundaries (backlog-b22). */
    static final int MIN_CLEARANCE = 8;

    /** Crossing inflation threshold: warn if routed crossings exceed this ratio of straight-line estimate (backlog-b22). */
    public static final double CROSSING_INFLATION_THRESHOLD = 1.5;

    /** Default exterior perimeter margin in pixels (B36). */
    public static final int DEFAULT_PERIMETER_MARGIN = 50;

    private final int bendPenalty;
    private final int margin;
    private final int perimeterMargin;
    private final double congestionWeight;
    private final PathOrderer pathOrderer;
    private final EdgeNudger edgeNudger;
    private final EdgeAttachmentCalculator edgeAttachmentCalculator;
    private final CoincidentSegmentDetector coincidentDetector;
    private final LabelPositionOptimizer labelPositionOptimizer;

    public RoutingPipeline() {
        this(DEFAULT_BEND_PENALTY, DEFAULT_MARGIN);
    }

    public RoutingPipeline(int bendPenalty, int margin) {
        this(bendPenalty, margin, DEFAULT_CONGESTION_WEIGHT);
    }

    public RoutingPipeline(int bendPenalty, int margin, double congestionWeight) {
        this(bendPenalty, margin, congestionWeight, margin);
    }

    public RoutingPipeline(int bendPenalty, int margin, double congestionWeight, int perimeterMargin) {
        if (perimeterMargin < 0) {
            throw new IllegalArgumentException("perimeterMargin must be >= 0, got " + perimeterMargin);
        }
        this.bendPenalty = bendPenalty;
        this.margin = margin;
        this.perimeterMargin = perimeterMargin;
        this.congestionWeight = congestionWeight;
        this.pathOrderer = new PathOrderer();
        this.edgeNudger = new EdgeNudger(this.pathOrderer);
        this.edgeAttachmentCalculator = new EdgeAttachmentCalculator();
        this.coincidentDetector = new CoincidentSegmentDetector(this.pathOrderer);
        this.labelPositionOptimizer = new LabelPositionOptimizer();
    }

    /**
     * Route a single connection around obstacles.
     * All coordinates are absolute canvas coordinates.
     *
     * <p>Routes from source center to target center. After initial routing, checks
     * if the path passes through source or target element bodies (Story 13-4).
     * If a pass-through is detected, re-routes with the offending element(s) added
     * as obstacles with edge port approach to force clean approach from outside.</p>
     *
     * @param source    source element rectangle
     * @param target    target element rectangle
     * @param obstacles list of obstacle rectangles (caller must exclude source/target/ancestors)
     * @return list of absolute bendpoints (intermediate path nodes, excluding source/target centers)
     */
    public List<AbsoluteBendpointDto> routeConnection(
            RoutingRect source, RoutingRect target, List<RoutingRect> obstacles) {
        return routeConnection(source, target, obstacles, List.of());
    }

    /**
     * Route a single connection around obstacles with group-wall clearance awareness (B43-b).
     *
     * @param source           source element rectangle
     * @param target           target element rectangle
     * @param obstacles        list of obstacle rectangles (caller must exclude source/target/ancestors)
     * @param groupBoundaries  group rectangles for group-wall clearance cost (excluding ancestor groups)
     * @return list of absolute bendpoints (intermediate path nodes, excluding source/target centers)
     */
    public List<AbsoluteBendpointDto> routeConnection(
            RoutingRect source, RoutingRect target, List<RoutingRect> obstacles,
            List<RoutingRect> groupBoundaries) {
        return routeConnection(source, target, obstacles, groupBoundaries, null);
    }

    /**
     * Route a single connection with corridor occupancy awareness (B47).
     *
     * @param source            source element rectangle
     * @param target            target element rectangle
     * @param obstacles         list of obstacle rectangles (caller must exclude source/target/ancestors)
     * @param groupBoundaries   group rectangles for group-wall clearance cost
     * @param occupancyTracker  corridor occupancy tracker (nullable — null disables occupancy cost)
     * @return list of absolute bendpoints (intermediate path nodes, excluding source/target centers)
     */
    List<AbsoluteBendpointDto> routeConnection(
            RoutingRect source, RoutingRect target, List<RoutingRect> obstacles,
            List<RoutingRect> groupBoundaries, CorridorOccupancyTracker occupancyTracker) {
        logger.debug("Routing connection: source={}, target={}, obstacles={}, groups={}",
                source.id(), target.id(), obstacles.size(), groupBoundaries.size());

        // Self-connection: same center → straight line
        // Return mutable list — downstream stages (applyEdgeAttachments) add terminal bendpoints
        if (source.centerX() == target.centerX() && source.centerY() == target.centerY()) {
            return new ArrayList<>();
        }

        // Primary route: center-to-center with source/target NOT as obstacles
        // B47: Pass occupancy tracker for corridor diversity (null for single-connection routing)
        List<AbsoluteBendpointDto> bendpoints = routeFromCenters(source, target, obstacles, groupBoundaries, occupancyTracker);

        // Story 13-4: Check if the route passes through source or target body.
        // If so, re-route with the offending element(s) as obstacles using edge ports.
        boolean srcPT = hasEndpointPassThrough(bendpoints, source, target, true);
        boolean tgtPT = hasEndpointPassThrough(bendpoints, source, target, false);

        if (srcPT || tgtPT) {
            logger.debug("Endpoint pass-through detected (src={}, tgt={}) — re-routing with obstacles",
                    srcPT, tgtPT);
            List<RoutingRect> augmented = new ArrayList<>(obstacles);
            if (srcPT) augmented.add(source);
            if (tgtPT) augmented.add(target);

            int[] srcPort = srcPT ? calculateEdgePort(source, target) : null;
            int srcX = srcPT ? srcPort[0] : source.centerX();
            int srcY = srcPT ? srcPort[1] : source.centerY();
            int[] tgtPort = tgtPT ? calculateEdgePort(target, source) : null;
            int tgtX = tgtPT ? tgtPort[0] : target.centerX();
            int tgtY = tgtPT ? tgtPort[1] : target.centerY();

            List<AbsoluteBendpointDto> rerouted = routeFromPorts(augmented, groupBoundaries, srcX, srcY, tgtX, tgtY);
            if (!rerouted.isEmpty() || (srcPT && tgtPT)) {
                bendpoints = rerouted;
            } else {
                logger.warn("Re-route failed for endpoint pass-through (src={}, tgt={}) — keeping original path",
                        source.id(), target.id());
            }

            // Story 13-8: Fallback edge port strategy.
            // If the primary re-route still has obstacle violations, try alternative edge ports.
            if (hasRouteViolation(bendpoints, srcX, srcY, tgtX, tgtY, augmented)) {
                logger.debug("Primary edge port route has violations — trying alternative edge ports");
                int[][] srcAlts = srcPT ? calculateAlternativeEdgePorts(source, target) : new int[0][];
                int[][] tgtAlts = tgtPT ? calculateAlternativeEdgePorts(target, source) : new int[0][];

                List<AbsoluteBendpointDto> bestRoute = null;

                // Try alternative source ports with primary target port
                for (int[] altSrc : srcAlts) {
                    List<AbsoluteBendpointDto> candidate = routeFromPorts(
                            augmented, groupBoundaries, altSrc[0], altSrc[1], tgtX, tgtY);
                    if (!hasRouteViolation(candidate, altSrc[0], altSrc[1], tgtX, tgtY, augmented)) {
                        bestRoute = candidate;
                        logger.debug("Fallback: clean route found with alternative source port ({},{})",
                                altSrc[0], altSrc[1]);
                        break;
                    }
                }

                // If source alternatives didn't help, try alternative target ports with primary source
                if (bestRoute == null) {
                    for (int[] altTgt : tgtAlts) {
                        List<AbsoluteBendpointDto> candidate = routeFromPorts(
                                augmented, groupBoundaries, srcX, srcY, altTgt[0], altTgt[1]);
                        if (!hasRouteViolation(candidate, srcX, srcY,
                                altTgt[0], altTgt[1], augmented)) {
                            bestRoute = candidate;
                            logger.debug("Fallback: clean route found with alternative target port ({},{})",
                                    altTgt[0], altTgt[1]);
                            break;
                        }
                    }
                }

                // If still no clean route, try all source+target combinations
                if (bestRoute == null && srcAlts.length > 0 && tgtAlts.length > 0) {
                    outer:
                    for (int[] altSrc : srcAlts) {
                        for (int[] altTgt : tgtAlts) {
                            List<AbsoluteBendpointDto> candidate = routeFromPorts(
                                    augmented, groupBoundaries, altSrc[0], altSrc[1], altTgt[0], altTgt[1]);
                            if (!hasRouteViolation(candidate, altSrc[0], altSrc[1],
                                    altTgt[0], altTgt[1], augmented)) {
                                bestRoute = candidate;
                                logger.debug("Fallback: clean route found with alt source ({},{}) + alt target ({},{})",
                                        altSrc[0], altSrc[1], altTgt[0], altTgt[1]);
                                break outer;
                            }
                        }
                    }
                }

                if (bestRoute != null) {
                    bendpoints = bestRoute;
                } else {
                    logger.warn("All edge port alternatives exhausted for (src={}, tgt={}) — keeping primary re-route",
                            source.id(), target.id());
                }
            }
        }

        return bendpoints;
    }

    /**
     * Routes from source center to target center using the given obstacles.
     */
    private List<AbsoluteBendpointDto> routeFromCenters(
            RoutingRect source, RoutingRect target, List<RoutingRect> obstacles,
            List<RoutingRect> groupBoundaries, CorridorOccupancyTracker occupancyTracker) {
        OrthogonalVisibilityGraph graph = new OrthogonalVisibilityGraph(margin, perimeterMargin);
        graph.build(obstacles);

        VisNode[] ports = graph.addPortNodes(
                source.centerX(), source.centerY(), target.centerX(), target.centerY());
        List<VisNode> path = findPath(graph, ports[0], ports[1], groupBoundaries, occupancyTracker);

        if (path.isEmpty()) {
            logger.warn("No path found from ({},{}) to ({},{}) — falling back to straight line",
                    source.centerX(), source.centerY(), target.centerX(), target.centerY());
            // Return mutable list — downstream stages (applyEdgeAttachments) add terminal bendpoints
            return new ArrayList<>();
        }

        List<AbsoluteBendpointDto> bendpoints = new ArrayList<>();
        for (int i = 1; i < path.size() - 1; i++) {
            bendpoints.add(new AbsoluteBendpointDto(path.get(i).x(), path.get(i).y()));
        }
        return bendpoints;
    }

    /**
     * Routes from explicit port coordinates using the given obstacles.
     */
    private List<AbsoluteBendpointDto> routeFromPorts(
            List<RoutingRect> obstacles, List<RoutingRect> groupBoundaries,
            int srcX, int srcY, int tgtX, int tgtY) {
        OrthogonalVisibilityGraph graph = new OrthogonalVisibilityGraph(margin, perimeterMargin);
        graph.build(obstacles);

        VisNode[] ports = graph.addPortNodes(srcX, srcY, tgtX, tgtY);
        List<VisNode> path = findPath(graph, ports[0], ports[1], groupBoundaries);

        if (path.isEmpty()) {
            // Return mutable list — downstream stages (applyEdgeAttachments) add terminal bendpoints
            return new ArrayList<>();
        }

        List<AbsoluteBendpointDto> bendpoints = new ArrayList<>();
        for (int i = 1; i < path.size() - 1; i++) {
            bendpoints.add(new AbsoluteBendpointDto(path.get(i).x(), path.get(i).y()));
        }
        return bendpoints;
    }

    /**
     * Checks if the routed path passes through an endpoint element's body on a
     * non-terminal segment (Story 13-4). The first segment naturally exits the source
     * and the last segment naturally enters the target, so they are excluded.
     *
     * @param bendpoints  intermediate bendpoints from routing
     * @param source      source element rectangle
     * @param target      target element rectangle
     * @param checkSource true to check source element, false to check target element
     * @return true if a non-terminal segment passes through the checked element
     */
    boolean hasEndpointPassThrough(List<AbsoluteBendpointDto> bendpoints,
                                    RoutingRect source, RoutingRect target, boolean checkSource) {
        // Build full path: sourceCenter + BPs + targetCenter
        List<int[]> fullPath = new ArrayList<>();
        fullPath.add(new int[]{source.centerX(), source.centerY()});
        for (AbsoluteBendpointDto bp : bendpoints) {
            fullPath.add(new int[]{bp.x(), bp.y()});
        }
        fullPath.add(new int[]{target.centerX(), target.centerY()});

        if (fullPath.size() < 3) return false; // Straight line — no non-terminal segments

        RoutingRect element = checkSource ? source : target;
        int inset = 5; // tolerance to avoid false positives at edges
        int ix = element.x() + inset;
        int iy = element.y() + inset;
        int iw = element.width() - 2 * inset;
        int ih = element.height() - 2 * inset;
        if (iw <= 0 || ih <= 0) return false; // Element too small after inset

        // For source: skip first segment (0→1) which naturally exits source
        // For target: skip last segment (n-2→n-1) which naturally enters target
        int start = checkSource ? 1 : 0;
        int end = checkSource ? fullPath.size() - 1 : fullPath.size() - 2;

        for (int i = start; i < end; i++) {
            int[] a = fullPath.get(i);
            int[] b = fullPath.get(i + 1);
            if (segmentIntersectsAnyObstacle(a[0], a[1], b[0], b[1],
                    List.of(new RoutingRect(ix, iy, iw, ih, element.id())))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Calculates the port position for an element on the edge nearest to the other element.
     * The port is placed at the expanded obstacle boundary (element edge + margin) so it
     * sits on the visibility graph's obstacle corner coordinate and is reachable by A*.
     * When horizontal and vertical distances are equal, horizontal dominance wins.
     *
     * @param element the element to calculate the port for
     * @param other   the other element (determines which edge to use)
     * @return [x, y] port coordinates
     */
    int[] calculateEdgePort(RoutingRect element, RoutingRect other) {
        int ecx = element.centerX(), ecy = element.centerY();
        int ocx = other.centerX(), ocy = other.centerY();

        int dx = Math.abs(ocx - ecx);
        int dy = Math.abs(ocy - ecy);

        if (dx >= dy) {
            // Horizontal dominance: departure/approach from left or right edge
            if (ocx < ecx) {
                // Other is to the left → port on left edge
                return new int[]{ element.x() - margin, ecy };
            } else {
                // Other is to the right → port on right edge
                return new int[]{ element.x() + element.width() + margin, ecy };
            }
        } else {
            // Vertical dominance: departure/approach from top or bottom edge
            if (ocy < ecy) {
                // Other is above → port on top edge
                return new int[]{ ecx, element.y() - margin };
            } else {
                // Other is below → port on bottom edge
                return new int[]{ ecx, element.y() + element.height() + margin };
            }
        }
    }

    /**
     * Returns up to 3 alternative edge ports for the given element, excluding the primary
     * edge port that {@link #calculateEdgePort} would return. Alternatives are ordered by
     * angular proximity to the target element (closest angle first).
     *
     * <p>Story 13-8: Used by the fallback loop in {@link #routeConnection} when the primary
     * edge port leads into an adjacent obstacle.</p>
     *
     * @param element the element to calculate alternative ports for
     * @param other   the other element (target direction)
     * @return array of [x, y] port coordinates, ordered by angular proximity to target
     */
    int[][] calculateAlternativeEdgePorts(RoutingRect element, RoutingRect other) {
        int ecx = element.centerX(), ecy = element.centerY();
        int ocx = other.centerX(), ocy = other.centerY();

        // All 4 edge ports
        int[][] allPorts = {
            { element.x() + element.width() + margin, ecy },  // RIGHT
            { element.x() - margin, ecy },                     // LEFT
            { ecx, element.y() - margin },                     // TOP
            { ecx, element.y() + element.height() + margin }   // BOTTOM
        };

        // Determine which port is the primary by delegating to calculateEdgePort
        int[] primary = calculateEdgePort(element, other);
        int primaryIdx = -1;
        for (int i = 0; i < allPorts.length; i++) {
            if (allPorts[i][0] == primary[0] && allPorts[i][1] == primary[1]) {
                primaryIdx = i;
                break;
            }
        }

        // Target angle from element center
        double targetAngle = Math.atan2(ocy - ecy, ocx - ecx);

        // Build alternatives with angular distance, excluding primary
        record PortWithAngle(int[] port, double angleDist) {}
        List<PortWithAngle> alternatives = new ArrayList<>();
        for (int i = 0; i < allPorts.length; i++) {
            if (i == primaryIdx) continue;
            double portAngle = Math.atan2(allPorts[i][1] - ecy, allPorts[i][0] - ecx);
            double diff = Math.abs(portAngle - targetAngle);
            if (diff > Math.PI) diff = 2 * Math.PI - diff;
            alternatives.add(new PortWithAngle(allPorts[i], diff));
        }

        // Sort by angular distance (closest to target direction first)
        alternatives.sort((a, b) -> Double.compare(a.angleDist(), b.angleDist()));

        int[][] result = new int[alternatives.size()][];
        for (int i = 0; i < alternatives.size(); i++) {
            result[i] = alternatives.get(i).port();
        }
        return result;
    }

    /**
     * Checks if a routed path has any obstacle violation when rendered from
     * the given source/target coordinates.
     *
     * @param bendpoints intermediate bendpoints
     * @param srcX       source x coordinate
     * @param srcY       source y coordinate
     * @param tgtX       target x coordinate
     * @param tgtY       target y coordinate
     * @param obstacles  obstacles to check against (should include augmented src/tgt)
     * @return true if any segment crosses an obstacle
     */
    private boolean hasRouteViolation(List<AbsoluteBendpointDto> bendpoints,
                                       int srcX, int srcY, int tgtX, int tgtY,
                                       List<RoutingRect> obstacles) {
        List<AbsoluteBendpointDto> fullPath = new ArrayList<>();
        fullPath.add(new AbsoluteBendpointDto(srcX, srcY));
        fullPath.addAll(bendpoints);
        fullPath.add(new AbsoluteBendpointDto(tgtX, tgtY));
        return findFirstObstacleViolation(fullPath, obstacles) != null;
    }

    /**
     * Lightweight record for batch routing input.
     *
     * @param connectionId unique identifier for the connection
     * @param source       source element rectangle
     * @param target       target element rectangle
     * @param obstacles    obstacle rectangles (source/target/ancestors already excluded)
     */
    public record ConnectionEndpoints(String connectionId, RoutingRect source,
                                       RoutingRect target, List<RoutingRect> obstacles,
                                       String labelText, int textPosition,
                                       List<RoutingRect> groupBoundaries) {

        /** Backwards-compatible constructor without group boundaries. */
        public ConnectionEndpoints(String connectionId, RoutingRect source,
                                   RoutingRect target, List<RoutingRect> obstacles,
                                   String labelText, int textPosition) {
            this(connectionId, source, target, obstacles, labelText, textPosition, List.of());
        }
    }

    /**
     * Route all connections and apply path ordering and edge nudging.
     * All coordinates are absolute canvas coordinates.
     *
     * @param connections  list of connection endpoints to route
     * @param allObstacles all element rectangles on the view (for corridor width computation)
     * @return RoutingResult with routed connections and failed connections
     */
    public RoutingResult routeAllConnections(
            List<ConnectionEndpoints> connections, List<RoutingRect> allObstacles) {
        return routeAllConnections(connections, allObstacles, null);
    }

    /**
     * Route all connections with pre-built label exclusion sets for the label position optimizer.
     * When labelExcludeSets is null, exclude sets are built from source/target IDs only.
     * Callers with access to the node hierarchy should provide full exclude sets
     * (source, target, ancestors, descendants) for AC3 consistency with LayoutQualityAssessor.
     */
    public RoutingResult routeAllConnections(
            List<ConnectionEndpoints> connections, List<RoutingRect> allObstacles,
            Map<String, Set<String>> labelExcludeSets) {
        return routeAllConnections(connections, allObstacles, labelExcludeSets, DEFAULT_SNAP_THRESHOLD);
    }

    /**
     * Route all connections with snap-to-straight threshold (backlog-b17).
     * When snapThreshold > 0, near-aligned connections (port offset within threshold)
     * are snapped to straight segments to eliminate visually negligible Z-bends.
     *
     * @param connections     list of connection endpoints to route
     * @param allObstacles    all element rectangles on the view
     * @param labelExcludeSets per-connection label exclusion sets (nullable)
     * @param snapThreshold   max pixel offset for snap-to-straight (0 disables, default 20)
     * @return RoutingResult with routed connections and failed connections
     */
    public RoutingResult routeAllConnections(
            List<ConnectionEndpoints> connections, List<RoutingRect> allObstacles,
            Map<String, Set<String>> labelExcludeSets, int snapThreshold) {
        logger.info("Batch routing {} connections with path ordering and edge nudging",
                connections.size());

        // B47: Sort connections by descending Manhattan distance (longest first).
        // Most constrained connections route first, getting best corridor selection.
        // Build index mapping to restore original order after routing.
        Integer[] sortedIndices = buildConnectionRoutingOrder(connections);

        // 1. Route each connection individually with corridor occupancy tracking (B47)
        List<String> connectionIds = new ArrayList<>(Collections.nCopies(connections.size(), null));
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>(Collections.nCopies(connections.size(), null));
        List<int[]> sourceCenters = new ArrayList<>(Collections.nCopies(connections.size(), null));
        List<int[]> targetCenters = new ArrayList<>(Collections.nCopies(connections.size(), null));
        CorridorOccupancyTracker occupancyTracker = new CorridorOccupancyTracker();

        for (int si = 0; si < sortedIndices.length; si++) {
            int origIdx = sortedIndices[si].intValue();
            ConnectionEndpoints conn = connections.get(origIdx);
            int[] srcCenter = new int[]{conn.source().centerX(), conn.source().centerY()};
            int[] tgtCenter = new int[]{conn.target().centerX(), conn.target().centerY()};
            List<AbsoluteBendpointDto> routed = routeConnection(
                    conn.source(), conn.target(), conn.obstacles(), conn.groupBoundaries(), occupancyTracker);
            // Record routed path for corridor occupancy (B47)
            occupancyTracker.recordPath(routed, srcCenter, tgtCenter);
            // Store in original order position
            connectionIds.set(origIdx, conn.connectionId());
            bendpointLists.set(origIdx, routed);
            sourceCenters.set(origIdx, srcCenter);
            targetCenters.set(origIdx, tgtCenter);
        }
        logger.debug("B47: Routed {} connections with occupancy tracking ({} corridors occupied)",
                connections.size(), occupancyTracker.getCorridorOccupancy().size());

        // 1.1. Straight-line crossing estimate (backlog-b22)
        // Uses only source/target centers (not routed paths), so placement after
        // routeConnection() calls is functionally equivalent to computing before routing.
        int straightLineCrossings = computeStraightLineCrossings(sourceCenters, targetCenters);

        // 1.5. Path simplification — reduce staircase patterns from A* grid traversal
        for (int i = 0; i < connections.size(); i++) {
            simplifyPath(bendpointLists.get(i), sourceCenters.get(i), targetCenters.get(i),
                    connections.get(i).obstacles());
        }

        // 2. Apply path ordering analysis
        List<List<AbsoluteBendpointDto>> orderedPaths =
                pathOrderer.orderPaths(connectionIds, bendpointLists, sourceCenters, targetCenters);

        // 3. Apply edge nudging for parallel segment separation
        List<List<AbsoluteBendpointDto>> nudgedPaths =
                edgeNudger.nudgePaths(connectionIds, orderedPaths, sourceCenters, targetCenters, allObstacles);

        // 3.5a. Coincident segment detection and offset (Story 11-23)
        List<CoincidentSegmentDetector.CoincidentPair> coincidentPairs =
                coincidentDetector.detect(connectionIds, nudgedPaths, sourceCenters, targetCenters);
        if (!coincidentPairs.isEmpty()) {
            coincidentDetector.applyOffsets(coincidentPairs, nudgedPaths, allObstacles);
        }

        // 3.6. Label clearance pass — shift connections whose labels would overlap obstacles
        for (int i = 0; i < connections.size(); i++) {
            ConnectionEndpoints conn = connections.get(i);
            if (conn.labelText() != null && !conn.labelText().isEmpty()) {
                RoutingRect labelRect = LabelClearance.computeLabelRect(
                        nudgedPaths.get(i),
                        new int[]{conn.source().centerX(), conn.source().centerY()},
                        new int[]{conn.target().centerX(), conn.target().centerY()},
                        conn.labelText(), conn.textPosition());
                if (labelRect != null && LabelClearance.overlapsAnyObstacle(labelRect, allObstacles)) {
                    // Adjust the nudge offset: shift the path segment at the label position
                    // by the label height + margin to clear the obstacle
                    adjustPathForLabelClearance(nudgedPaths.get(i), labelRect, allObstacles);
                }
            }
        }

        // 3.5. Trim BPs inside source/target elements (prevents artifacts with edge attachment)
        for (int i = 0; i < connections.size(); i++) {
            trimEndpointBendpoints(nudgedPaths.get(i),
                    connections.get(i).source(), connections.get(i).target());
        }

        // 3.7. Post-routing obstacle re-validation (Story 10-25, Patterns 1 & 4)
        // Pipeline stages (nudger, label clearance) can shift paths into obstacle boundaries.
        // Must run BEFORE edge attachment so terminal bendpoints are not stripped.
        for (int i = 0; i < nudgedPaths.size(); i++) {
            removeObstacleViolations(nudgedPaths.get(i), connections.get(i).obstacles());
        }

        // 3.8. Enforce orthogonal path segments (Story 10-25, Pattern 3)
        // Must run BEFORE edge attachment so terminals are added to clean orthogonal paths.
        for (int i = 0; i < nudgedPaths.size(); i++) {
            enforceOrthogonalPaths(nudgedPaths.get(i));
        }

        // 4. Apply edge attachments (terminal bendpoints at element faces)
        edgeAttachmentCalculator.applyEdgeAttachments(connectionIds, nudgedPaths, connections);

        // Save terminal BP positions for post-cleanup restoration (Story 13-12)
        // Post-attachment stages (4.1–4.6a) can shift terminals via micro-jog removal,
        // coordinate propagation, or collinear cleanup. Saving allows restoration.
        int[][] savedSourceTerminals = new int[nudgedPaths.size()][];
        int[][] savedTargetTerminals = new int[nudgedPaths.size()][];
        for (int i = 0; i < nudgedPaths.size(); i++) {
            List<AbsoluteBendpointDto> p = nudgedPaths.get(i);
            if (p.size() >= 2) {
                savedSourceTerminals[i] = new int[]{p.get(0).x(), p.get(0).y()};
                savedTargetTerminals[i] = new int[]{p.get(p.size() - 1).x(), p.get(p.size() - 1).y()};
            }
        }

        // 4.1. Post-attachment orthogonal enforcement (Story 10-28)
        // Edge attachment may introduce diagonal terminal segments
        for (int i = 0; i < nudgedPaths.size(); i++) {
            enforceOrthogonalPaths(nudgedPaths.get(i));
        }

        // 4.2. Post-attachment obstacle re-validation (Story 10-28)
        // Edge attachment and orthogonal enforcement may create segments passing through obstacles
        for (int i = 0; i < nudgedPaths.size(); i++) {
            removeObstacleViolations(nudgedPaths.get(i), connections.get(i).obstacles());
        }

        // 4.4. Snap near-aligned connections to straight segments (backlog-b17)
        // When source and target ports differ by at most snapThreshold pixels in one axis,
        // replace the Z-bend path with a single straight segment (if obstacle-free).
        if (snapThreshold > 0) {
            for (int i = 0; i < nudgedPaths.size(); i++) {
                snapToStraightIfAligned(nudgedPaths.get(i),
                        connections.get(i).source(), connections.get(i).target(),
                        connections.get(i).obstacles(), snapThreshold);
            }
        }

        // 4.5. Clean up paths (remove artifacts from pipeline stage interactions)
        for (int i = 0; i < nudgedPaths.size(); i++) {
            removeMicroJogs(nudgedPaths.get(i), MICRO_JOG_THRESHOLD);
            removeDuplicatePoints(nudgedPaths.get(i));
            removeCollinearPoints(nudgedPaths.get(i));
        }

        // 4.6. Final obstacle validation after cleanup (Story 10-28)
        // Micro-jog removal and collinear cleanup can merge segments into obstacle-crossing paths
        for (int i = 0; i < nudgedPaths.size(); i++) {
            removeObstacleViolations(nudgedPaths.get(i), connections.get(i).obstacles());
        }

        // 4.6a. Endpoint pass-through correction (Story 13-4)
        // Pipeline stages (simplify, nudge, edge attachment, cleanup) can introduce
        // BPs inside source/target elements and segments that pass through them.
        // Step 1: Remove any BPs that are inside endpoint elements.
        // Step 2: Insert corrective detour BPs around the element where segments still cross.
        for (int i = 0; i < nudgedPaths.size(); i++) {
            correctEndpointPassThroughs(nudgedPaths.get(i),
                    connections.get(i).source(), connections.get(i).target());
        }

        // 4.6b. Terminal realignment — restore face-center exit/entry after cleanup (Story 13-12)
        // Post-attachment stages (4.1–4.6a) can shift terminal BPs via micro-jog removal,
        // coordinate propagation, or path restructuring. This pass restores correct
        // terminal positions to ensure ChopboxAnchor produces face-center visual exits.
        for (int i = 0; i < nudgedPaths.size(); i++) {
            if (savedSourceTerminals[i] != null) {
                realignTerminals(nudgedPaths.get(i),
                        savedSourceTerminals[i], savedTargetTerminals[i],
                        connections.get(i));
            }
        }

        // 4.7. Bendpoint clearance enforcement (backlog-b22, fixed in B25)
        // After all path cleanup stages, ensure intermediate BPs maintain minimum clearance
        // from obstacle boundaries. Terminal BPs (at element faces) are excluded.
        // B25 fix: axis-constrained nudging preserves orthogonality.
        int totalClearanceNudges = 0;
        for (int i = 0; i < nudgedPaths.size(); i++) {
            totalClearanceNudges += enforceMinClearance(nudgedPaths.get(i), allObstacles,
                    connections.get(i).source(), connections.get(i).target());
        }
        if (totalClearanceNudges > 0) {
            logger.info("Clearance enforcement: nudged {} bendpoints to maintain {}px minimum clearance",
                    totalClearanceNudges, MIN_CLEARANCE);
            // B25: Post-clearance cleanup — restore path quality after nudging
            for (int i = 0; i < nudgedPaths.size(); i++) {
                removeMicroJogs(nudgedPaths.get(i), MICRO_JOG_THRESHOLD);
                removeDuplicatePoints(nudgedPaths.get(i));
                removeCollinearPoints(nudgedPaths.get(i));
                removeObstacleViolations(nudgedPaths.get(i), connections.get(i).obstacles());
            }
        }

        // 4.7b. Segment-based clearance enforcement (backlog-b26)
        // After point-based clearance, check entire intermediate segments against obstacle
        // boundaries. Catches grazing where all BPs are outside obstacle bands but the
        // segment itself runs too close to an obstacle face.
        int totalSegmentShifts = 0;
        for (int i = 0; i < nudgedPaths.size(); i++) {
            totalSegmentShifts += enforceSegmentClearance(nudgedPaths.get(i), allObstacles,
                    connections.get(i).source(), connections.get(i).target());
        }
        if (totalSegmentShifts > 0) {
            logger.info("Segment clearance enforcement: shifted {} segments to maintain {}px minimum clearance",
                    totalSegmentShifts, MIN_CLEARANCE);
            // Post-shift cleanup — same pattern as point clearance (B25)
            for (int i = 0; i < nudgedPaths.size(); i++) {
                removeMicroJogs(nudgedPaths.get(i), MICRO_JOG_THRESHOLD);
                removeDuplicatePoints(nudgedPaths.get(i));
                removeCollinearPoints(nudgedPaths.get(i));
                removeObstacleViolations(nudgedPaths.get(i), connections.get(i).obstacles());
            }
        }

        // 4.7c. Terminal corridor clearance enforcement (backlog-b27)
        // After point-based (4.7) and segment-based (4.7b) clearance, handle 2-BP and 3-BP
        // paths where terminal-to-terminal segments graze obstacles. These paths have no
        // intermediate BPs/segments for the earlier stages to check.
        int totalTerminalFixes = 0;
        for (int i = 0; i < nudgedPaths.size(); i++) {
            totalTerminalFixes += enforceTerminalCorridorClearance(nudgedPaths.get(i), allObstacles,
                    connections.get(i).source(), connections.get(i).target());
        }
        if (totalTerminalFixes > 0) {
            logger.info("Terminal corridor clearance: fixed {} paths with terminal segment grazing",
                    totalTerminalFixes);
            // Post-fix cleanup — same pattern as segment clearance (4.7b)
            for (int i = 0; i < nudgedPaths.size(); i++) {
                removeMicroJogs(nudgedPaths.get(i), MICRO_JOG_THRESHOLD);
                removeDuplicatePoints(nudgedPaths.get(i));
                removeCollinearPoints(nudgedPaths.get(i));
                removeObstacleViolations(nudgedPaths.get(i), connections.get(i).obstacles());
            }
        }

        // 4.7d. Post-pipeline terminal orthogonality verification (backlog-b28)
        // Safety net: catches diagonal terminal segments surviving or reintroduced by
        // cleanup/clearance stages. Runs after all routing quality stages, before label
        // optimization so labels account for any inserted BPs.
        int totalTerminalOrthoFixes = 0;
        for (int i = 0; i < nudgedPaths.size(); i++) {
            totalTerminalOrthoFixes += enforceTerminalOrthogonality(nudgedPaths.get(i),
                    connections.get(i));
        }
        if (totalTerminalOrthoFixes > 0) {
            logger.info("Terminal orthogonality: fixed {} diagonal terminal segments", totalTerminalOrthoFixes);
            // Post-fix cleanup — same pattern as terminal corridor stage (4.7c)
            for (int i = 0; i < nudgedPaths.size(); i++) {
                removeDuplicatePoints(nudgedPaths.get(i));
                removeCollinearPoints(nudgedPaths.get(i));
                removeObstacleViolations(nudgedPaths.get(i), connections.get(i).obstacles());
            }
        }

        // 4.7e. ChopboxAnchor center-aligned terminal alignment (backlog-b29)
        // Ensures first/last BPs share a coordinate with source/target element center.
        // Archi draws from center to first/last BP — misalignment produces diagonal visual
        // segments. Runs after all routing quality stages, before label optimization.
        int totalCenterAlignments = 0;
        for (int i = 0; i < nudgedPaths.size(); i++) {
            totalCenterAlignments += alignTerminalsWithCenter(nudgedPaths.get(i),
                    connections.get(i));
        }
        if (totalCenterAlignments > 0) {
            logger.info("ChopboxAnchor alignment: inserted {} center-aligned terminal BPs",
                    totalCenterAlignments);
            // Minimal cleanup only — B29 inserts BPs at element face edges, which cannot
            // create obstacle crossings. removeObstacleViolations is intentionally omitted
            // to avoid removing intermediate BPs placed by earlier stages.
            for (int i = 0; i < nudgedPaths.size(); i++) {
                removeDuplicatePoints(nudgedPaths.get(i));
                removeCollinearPoints(nudgedPaths.get(i));
            }
        }

        // 4.7f. Self-element pass-through face correction (backlog-b35 Phase B)
        // When a connection's routed path clips through its own source or target element,
        // re-select the face and re-route terminal segments with a clearance waypoint.
        // B35 redesign: re-routes terminal-adjacent segments instead of just swapping
        // the terminal BP (B34's Frankenstein path issue).
        int selfSourceFixes = 0;
        int selfTargetFixes = 0;
        for (int i = 0; i < nudgedPaths.size(); i++) {
            ConnectionEndpoints conn = connections.get(i);
            if (correctSelfElementPassThrough(nudgedPaths.get(i), conn, true)) {
                selfSourceFixes++;
            }
            if (correctSelfElementPassThrough(nudgedPaths.get(i), conn, false)) {
                selfTargetFixes++;
            }
        }
        if (selfSourceFixes > 0 || selfTargetFixes > 0) {
            logger.info("Self-element face correction: fixed {} connections ({} source, {} target)",
                    selfSourceFixes + selfTargetFixes, selfSourceFixes, selfTargetFixes);
        }

        // 4.7g. Late-stage path simplification (backlog-b37)
        // Greedy shortcutting to eliminate unnecessary jogs introduced by intermediate
        // pipeline stages. Operates on final paths with terminals locked as chain anchors.
        // Runs after all quality stages, before label optimization.
        int totalBpsRemoved = 0;
        int simplifiedConns = 0;
        for (int i = 0; i < nudgedPaths.size(); i++) {
            int before = nudgedPaths.get(i).size();
            simplifyFinalPath(nudgedPaths.get(i), connections.get(i).obstacles());
            int removed = before - nudgedPaths.get(i).size();
            if (removed > 0) {
                simplifiedConns++;
            }
            totalBpsRemoved += removed;
        }
        if (totalBpsRemoved > 0) {
            logger.info("Late-stage path simplification: removed {} bendpoints across {} connections",
                    totalBpsRemoved, simplifiedConns);
        }
        for (int i = 0; i < nudgedPaths.size(); i++) {
            removeDuplicatePoints(nudgedPaths.get(i));
            removeCollinearPoints(nudgedPaths.get(i));
        }

        // 4.7h. Post-simplification coincident segment resolver (backlog-b39)
        // Re-runs CoincidentSegmentDetector after B37 simplification, which collapses
        // separation jogs and creates new coincident segments. Unlike Stage 3.5a which
        // runs pre-attachment, this pass catches coincidences introduced by all post-
        // processing stages (B32, B35, B37). Uses segment-based detection (not endpoint
        // grouping) so ALL coincident corridors are found regardless of shared endpoints.
        List<CoincidentSegmentDetector.CoincidentPair> postSimplifyPairs =
                coincidentDetector.detect(connectionIds, nudgedPaths, sourceCenters, targetCenters);
        if (!postSimplifyPairs.isEmpty()) {
            int coincidentResolved = coincidentDetector.applyOffsets(
                    postSimplifyPairs, nudgedPaths, allObstacles);
            if (coincidentResolved > 0) {
                logger.info("Coincident segment resolver: separated {} coincident segments", coincidentResolved);
                for (int i = 0; i < nudgedPaths.size(); i++) {
                    removeDuplicatePoints(nudgedPaths.get(i));
                    removeCollinearPoints(nudgedPaths.get(i));
                }
            }
        }

        // 4.7i. Post-routing path straightening (backlog-b42)
        // Snap-to-straight for near-aligned segments, direction reversal elimination,
        // and redundant bend collapsing. Complements Stage 4.7g (greedy shortcutting)
        // by targeting patterns it misses: near-aligned snaps with intermediate BPs,
        // overshoot-then-doubleback reversals, and zigzag collapses.
        // Runs after coincident resolver (4.7h) which may shift paths laterally.
        //
        // Source/target centers are temporarily prepended/appended so that
        // eliminateReversals can detect reversals involving terminal anchors
        // (e.g., source→BP1→BP2 overshoot patterns). Per-connection obstacles
        // (excluding source/target elements) are used so that segments near
        // terminals are not falsely blocked by the source/target rectangles.
        int straightenedConns = 0;
        int totalBpsBefore47i = 0;
        int totalBpsAfter47i = 0;
        for (int i = 0; i < nudgedPaths.size(); i++) {
            List<AbsoluteBendpointDto> path = nudgedPaths.get(i);
            int before = path.size();
            totalBpsBefore47i += before;

            // Prepend source center, append target center
            int[] sc = sourceCenters.get(i);
            int[] tc = targetCenters.get(i);
            path.add(0, new AbsoluteBendpointDto(sc[0], sc[1]));
            path.add(new AbsoluteBendpointDto(tc[0], tc[1]));

            List<RoutingRect> connObstacles = connections.get(i).obstacles();
            PathStraightener.snapToStraight(path, DEFAULT_SNAP_THRESHOLD, connObstacles);
            PathStraightener.eliminateReversals(path, connObstacles);
            PathStraightener.collapseStaircaseJogs(path, DEFAULT_SNAP_THRESHOLD, connObstacles);
            PathStraightener.collapseBends(path, connObstacles);

            // Strip the prepended/appended terminal anchors
            path.remove(path.size() - 1);
            path.remove(0);

            int after = path.size();
            totalBpsAfter47i += after;
            if (after < before) {
                straightenedConns++;
            }
        }
        int bpsRemovedBy47i = totalBpsBefore47i - totalBpsAfter47i;
        if (bpsRemovedBy47i > 0) {
            logger.info("Path straightening: removed {} bendpoints across {} connections",
                    bpsRemovedBy47i, straightenedConns);
        }
        for (int i = 0; i < nudgedPaths.size(); i++) {
            removeDuplicatePoints(nudgedPaths.get(i));
            removeCollinearPoints(nudgedPaths.get(i));
        }

        // 4.7k. Center-termination fix + final ChopboxAnchor alignment (backlog-b44)
        // First pass: fix any terminal BPs that ended up at element center coordinates
        // (would cause zero-length ChopboxAnchor ray = visual center termination).
        // Second pass: re-run alignTerminalsWithCenter() after all post-processing stages
        // (4.7f–4.7i) which can remove or shift the alignment BPs inserted at stage 4.7e.
        int centerFixes = 0;
        for (int i = 0; i < nudgedPaths.size(); i++) {
            centerFixes += fixCenterTerminatedPath(nudgedPaths.get(i), connections.get(i));
        }
        if (centerFixes > 0) {
            logger.info("B44 center-termination fix: corrected {} terminal(s) at element center",
                    centerFixes);
        }
        int finalCenterAlignments = 0;
        for (int i = 0; i < nudgedPaths.size(); i++) {
            finalCenterAlignments += alignTerminalsWithCenter(nudgedPaths.get(i),
                    connections.get(i));
        }
        if (finalCenterAlignments > 0) {
            logger.info("B44 final center alignment: re-inserted {} terminal BPs after post-processing",
                    finalCenterAlignments);
        }
        if (centerFixes > 0 || finalCenterAlignments > 0) {
            for (int i = 0; i < nudgedPaths.size(); i++) {
                removeDuplicatePoints(nudgedPaths.get(i));
                removeCollinearPoints(nudgedPaths.get(i));
            }
            // B44 defense-in-depth: removeCollinearPoints can expose a previously-
            // hidden center BP by removing intermediate collinear points. Re-check
            // after cleanup to catch any newly-exposed center-terminations.
            for (int i = 0; i < nudgedPaths.size(); i++) {
                centerFixes += fixCenterTerminatedPath(nudgedPaths.get(i), connections.get(i));
            }
        }

        // 4.7l. Center-termination safety net validation (backlog-b44)
        // Detects any remaining connections where first/last BP is at element center
        // coordinates (indicating ChopboxAnchor will produce zero-length ray = visual
        // center termination). Logs warnings for diagnostic purposes.
        int centerTerminations = 0;
        for (int i = 0; i < nudgedPaths.size(); i++) {
            List<AbsoluteBendpointDto> path = nudgedPaths.get(i);
            ConnectionEndpoints conn = connections.get(i);
            if (path.size() < 2) continue;
            AbsoluteBendpointDto first = path.get(0);
            AbsoluteBendpointDto last = path.get(path.size() - 1);
            if (first.x() == conn.source().centerX() && first.y() == conn.source().centerY()) {
                logger.warn("B44 center-termination detected at SOURCE for connection {} — "
                        + "first BP ({},{}) equals source center", conn.connectionId(),
                        first.x(), first.y());
                centerTerminations++;
            }
            if (last.x() == conn.target().centerX() && last.y() == conn.target().centerY()) {
                logger.warn("B44 center-termination detected at TARGET for connection {} — "
                        + "last BP ({},{}) equals target center", conn.connectionId(),
                        last.x(), last.y());
                centerTerminations++;
            }
        }
        if (centerTerminations > 0) {
            logger.warn("B44 center-termination safety net: {} terminal(s) at element center "
                    + "across {} connections", centerTerminations, nudgedPaths.size());
        }

        // 4.7m. Interior terminal BP fix (backlog-b45)
        // Post-processing stages (4.7g–4.7i) can shift BPs inside endpoint elements.
        // fixCenterTerminatedPath (4.7k) only catches BPs at exact element center.
        // This catches any remaining terminal or intermediate BPs inside element bounds.
        int interiorFixes = 0;
        for (int i = 0; i < nudgedPaths.size(); i++) {
            interiorFixes += fixInteriorTerminalBPs(nudgedPaths.get(i), connections.get(i));
        }
        if (interiorFixes > 0) {
            logger.info("B45 interior terminal BP fix: corrected {} BP(s) inside endpoint elements",
                    interiorFixes);
            for (int i = 0; i < nudgedPaths.size(); i++) {
                alignTerminalsWithCenter(nudgedPaths.get(i), connections.get(i));
            }
            for (int i = 0; i < nudgedPaths.size(); i++) {
                removeDuplicatePoints(nudgedPaths.get(i));
                removeCollinearPoints(nudgedPaths.get(i));
            }
            // Defense-in-depth: second pass after cleanup (mirrors B44 pattern)
            for (int i = 0; i < nudgedPaths.size(); i++) {
                fixInteriorTerminalBPs(nudgedPaths.get(i), connections.get(i));
            }
        }

        // 4.7n. Final orthogonality enforcement safety net (B45)
        // Post-processing stages (4.6a correctEndpointPassThroughs, 4.7g-4.7m) can create
        // non-orthogonal segments by removing BPs inside elements without reinserting L-bends.
        // This catches any remaining diagonals as a last resort before label optimization.
        for (int i = 0; i < nudgedPaths.size(); i++) {
            enforceOrthogonalPaths(nudgedPaths.get(i));
        }

        // 4.8. Label position optimization pass (Story 11-31)
        // After all path cleanup, evaluate alternative label positions to minimize overlaps.
        // Use caller-provided exclude sets if available (includes ancestors/descendants),
        // otherwise fall back to source+target only.
        Map<String, Set<String>> connectionExcludeSets;
        if (labelExcludeSets != null) {
            connectionExcludeSets = labelExcludeSets;
        } else {
            connectionExcludeSets = new LinkedHashMap<>();
            for (ConnectionEndpoints conn : connections) {
                Set<String> excludeIds = new HashSet<>();
                if (conn.source().id() != null) excludeIds.add(conn.source().id());
                if (conn.target().id() != null) excludeIds.add(conn.target().id());
                connectionExcludeSets.put(conn.connectionId(), excludeIds);
            }
        }
        Map<String, Integer> optimalPositions = labelPositionOptimizer.optimize(
                connections, nudgedPaths, allObstacles, connectionExcludeSets);

        // 5. Final violation check and build RoutingResult (Story 10-30, 10-32)
        Map<String, List<AbsoluteBendpointDto>> routed = new LinkedHashMap<>();
        Map<String, List<AbsoluteBendpointDto>> violatedRoutes = new LinkedHashMap<>();
        List<FailedConnection> failed = new ArrayList<>();
        for (int i = 0; i < connectionIds.size(); i++) {
            ConnectionEndpoints conn = connections.get(i);
            List<AbsoluteBendpointDto> path = nudgedPaths.get(i);

            // Check if the full rendered path crosses obstacles.
            // Build complete path: source center + BPs + target center.
            // This catches violations in terminal segments (source→first-BP,
            // last-BP→target) and the implicit straight line when path is empty.
            List<AbsoluteBendpointDto> fullPath = new ArrayList<>();
            fullPath.add(new AbsoluteBendpointDto(
                    conn.source().centerX(), conn.source().centerY()));
            fullPath.addAll(path);
            fullPath.add(new AbsoluteBendpointDto(
                    conn.target().centerX(), conn.target().centerY()));
            RoutingRect crossedObstacle = findFirstObstacleViolation(fullPath, conn.obstacles());

            if (crossedObstacle != null) {
                failed.add(new FailedConnection(conn.connectionId(),
                        conn.source().id(), conn.target().id(), "element_crossing",
                        crossedObstacle.id()));
                violatedRoutes.put(connectionIds.get(i), path);
                logger.debug("Connection {} classified as failed — still crosses obstacle after all pipeline stages",
                        conn.connectionId());
            } else {
                routed.put(connectionIds.get(i), path);
            }
        }

        if (!failed.isEmpty()) {
            logger.info("Routing complete: {} routed, {} failed", routed.size(), failed.size());
        }

        // 5a. Corridor re-route for failed connections (Story B31)
        // When the full pipeline produces an element_crossing, re-route the failed connection
        // individually (fresh A* path without batch interference from nudging/ordering).
        // Applies single-connection post-processing mirroring stages 3.5–4.7e (excluding
        // batch-only stages: path ordering, nudging, coincident detection).
        if (!failed.isEmpty()) {
            int corridorReroutes = 0;
            List<FailedConnection> stillFailed = new ArrayList<>();

            Map<String, ConnectionEndpoints> connectionMap = new HashMap<>();
            for (ConnectionEndpoints c : connections) {
                connectionMap.put(c.connectionId(), c);
            }

            for (FailedConnection fc : failed) {
                ConnectionEndpoints conn = connectionMap.get(fc.connectionId());

                if (conn == null || !"element_crossing".equals(fc.constraintViolated())) {
                    stillFailed.add(fc);
                    continue;
                }

                // Re-route using fresh A* (routeConnection handles pass-through + edge port fallback)
                List<AbsoluteBendpointDto> rerouted = routeConnection(
                        conn.source(), conn.target(), conn.obstacles(), conn.groupBoundaries());

                // Pre-attachment processing (mirrors stages 1.5, 3.8, 3.5)
                int[] srcCenter = {conn.source().centerX(), conn.source().centerY()};
                int[] tgtCenter = {conn.target().centerX(), conn.target().centerY()};
                simplifyPath(rerouted, srcCenter, tgtCenter, conn.obstacles());
                enforceOrthogonalPaths(rerouted);
                trimEndpointBendpoints(rerouted, conn.source(), conn.target());

                // Edge attachment as single-connection batch (stage 4)
                List<List<AbsoluteBendpointDto>> singlePath = new ArrayList<>();
                singlePath.add(rerouted);
                edgeAttachmentCalculator.applyEdgeAttachments(
                        List.of(conn.connectionId()), singlePath, List.of(conn));

                // Post-attachment quality stages (mirrors 4.1–4.2)
                enforceOrthogonalPaths(rerouted);
                removeObstacleViolations(rerouted, conn.obstacles());

                // Cleanup (mirrors 4.5–4.6)
                removeMicroJogs(rerouted, MICRO_JOG_THRESHOLD);
                removeDuplicatePoints(rerouted);
                removeCollinearPoints(rerouted);
                removeObstacleViolations(rerouted, conn.obstacles());

                // Terminal quality stages (mirrors 4.6a, 4.7d, 4.7e, 4.7f, 4.7k)
                correctEndpointPassThroughs(rerouted, conn.source(), conn.target());
                enforceTerminalOrthogonality(rerouted, conn);
                alignTerminalsWithCenter(rerouted, conn);
                // B35 Phase B face correction with re-routing (replaces B34 terminal-only swap)
                correctSelfElementPassThrough(rerouted, conn, true);
                correctSelfElementPassThrough(rerouted, conn, false);
                removeDuplicatePoints(rerouted);
                removeCollinearPoints(rerouted);
                // B44: Fix center-terminated terminals + final alignment (mirrors 4.7k)
                fixCenterTerminatedPath(rerouted, conn);
                // B45: Fix interior terminal BPs (mirrors 4.7m)
                fixInteriorTerminalBPs(rerouted, conn);
                alignTerminalsWithCenter(rerouted, conn);
                removeDuplicatePoints(rerouted);
                removeCollinearPoints(rerouted);
                // B45: Final orthogonality safety net (mirrors 4.7n)
                enforceOrthogonalPaths(rerouted);

                // Validate re-routed path
                List<AbsoluteBendpointDto> reroutedFull = new ArrayList<>();
                reroutedFull.add(new AbsoluteBendpointDto(
                        conn.source().centerX(), conn.source().centerY()));
                reroutedFull.addAll(rerouted);
                reroutedFull.add(new AbsoluteBendpointDto(
                        conn.target().centerX(), conn.target().centerY()));
                RoutingRect reroutedViolation = findFirstObstacleViolation(
                        reroutedFull, conn.obstacles());

                if (reroutedViolation == null) {
                    // Corridor re-route succeeded — promote to routed
                    routed.put(conn.connectionId(), rerouted);
                    violatedRoutes.remove(conn.connectionId());
                    corridorReroutes++;
                    logger.debug("B31 corridor re-route succeeded for connection {}",
                            conn.connectionId());
                } else {
                    // Still fails — keep original failure
                    stillFailed.add(fc);
                    logger.debug("B31 corridor re-route still violates obstacle {} for connection {}",
                            reroutedViolation.id(), conn.connectionId());
                }
            }

            if (corridorReroutes > 0) {
                logger.info("B31 corridor re-route: {} of {} failed connections recovered",
                        corridorReroutes, failed.size());
                failed = stillFailed;
            }
        }

        // 5.1. Recommendation engine — only runs if failed list non-empty (Story 10-31)
        List<MoveRecommendation> recommendations = List.of();
        if (!failed.isEmpty()) {
            recommendations = RoutingRecommendationEngine.recommend(failed, connections, allObstacles);
            if (!recommendations.isEmpty()) {
                logger.info("Generated {} move recommendations for {} blocking elements",
                        recommendations.size(), recommendations.size());
            }
        }

        return new RoutingResult(routed, failed, recommendations, violatedRoutes,
                optimalPositions.size(), optimalPositions, straightLineCrossings);
    }

    /**
     * Adjusts a routed path to avoid label overlap with obstacles.
     * Finds the segment nearest to the label center and shifts it by the label height + margin.
     * After adjustment, re-runs micro-jog removal, dedup, and collinear cleanup.
     */
    static void adjustPathForLabelClearance(List<AbsoluteBendpointDto> path,
            RoutingRect labelRect, List<RoutingRect> obstacles) {
        if (path.size() < 2) {
            return;
        }

        // Find which segment the label center is closest to
        int labelCenterX = labelRect.x() + labelRect.width() / 2;
        int labelCenterY = labelRect.y() + labelRect.height() / 2;
        int bestIdx = 0;
        double bestDist = Double.MAX_VALUE;

        for (int i = 0; i < path.size() - 1; i++) {
            AbsoluteBendpointDto a = path.get(i);
            AbsoluteBendpointDto b = path.get(i + 1);
            double dist = pointToSegmentDist(labelCenterX, labelCenterY,
                    a.x(), a.y(), b.x(), b.y());
            if (dist < bestDist) {
                bestDist = dist;
                bestIdx = i;
            }
        }

        // Determine shift direction: perpendicular to the segment
        AbsoluteBendpointDto a = path.get(bestIdx);
        AbsoluteBendpointDto b = path.get(bestIdx + 1);
        boolean isHorizontal = (a.y() == b.y());
        int shift = labelRect.height() + DEFAULT_MARGIN;

        if (isHorizontal) {
            // Shift vertically; try preferred direction first, fall back to other
            int yUp = a.y() - shift;
            int yDown = a.y() + shift;
            int preferred = (labelCenterY < a.y()) ? yDown : yUp;
            int fallback = (preferred == yDown) ? yUp : yDown;
            int newY = pickClearShift(a.x(), preferred, b.x(), preferred,
                    a.x(), fallback, b.x(), fallback, obstacles, true);
            if (newY == Integer.MIN_VALUE) {
                return; // Neither direction is clear — leave path unchanged
            }
            path.set(bestIdx, new AbsoluteBendpointDto(a.x(), newY));
            path.set(bestIdx + 1, new AbsoluteBendpointDto(b.x(), newY));
        } else {
            // Shift horizontally; try preferred direction first, fall back to other
            int xLeft = a.x() - shift;
            int xRight = a.x() + shift;
            int preferred = (labelCenterX < a.x()) ? xRight : xLeft;
            int fallback = (preferred == xRight) ? xLeft : xRight;
            int newX = pickClearShift(preferred, a.y(), preferred, b.y(),
                    fallback, a.y(), fallback, b.y(), obstacles, false);
            if (newX == Integer.MIN_VALUE) {
                return; // Neither direction is clear — leave path unchanged
            }
            path.set(bestIdx, new AbsoluteBendpointDto(newX, a.y()));
            path.set(bestIdx + 1, new AbsoluteBendpointDto(newX, b.y()));
        }

        // Re-run cleanup after path modification (Story 10-16 learning)
        removeMicroJogs(path, MICRO_JOG_THRESHOLD);
        removeDuplicatePoints(path);
        removeCollinearPoints(path);
    }

    /**
     * Tries preferred shift direction first; if the shifted segment overlaps an obstacle,
     * tries the fallback direction. Returns the clear coordinate, or Integer.MIN_VALUE
     * if neither direction is obstacle-free.
     *
     * @param isHorizontalShift true if comparing y coordinates (horizontal segment shifted vertically)
     */
    private static int pickClearShift(
            int prefAx, int prefAy, int prefBx, int prefBy,
            int fbAx, int fbAy, int fbBx, int fbBy,
            List<RoutingRect> obstacles, boolean isHorizontalShift) {
        // Check preferred direction
        if (!segmentOverlapsAnyObstacle(prefAx, prefAy, prefBx, prefBy, obstacles)) {
            return isHorizontalShift ? prefAy : prefAx;
        }
        // Check fallback direction
        if (!segmentOverlapsAnyObstacle(fbAx, fbAy, fbBx, fbBy, obstacles)) {
            return isHorizontalShift ? fbAy : fbAx;
        }
        return Integer.MIN_VALUE;
    }

    private static boolean segmentOverlapsAnyObstacle(
            int x1, int y1, int x2, int y2, List<RoutingRect> obstacles) {
        return CoincidentSegmentDetector.segmentOverlapsAnyObstacle(x1, y1, x2, y2, obstacles);
    }

    private static double pointToSegmentDist(int px, int py,
            int ax, int ay, int bx, int by) {
        double dx = bx - ax;
        double dy = by - ay;
        double lenSq = dx * dx + dy * dy;
        if (lenSq < 1e-10) {
            return Math.sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay));
        }
        double t = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / lenSq));
        double nearX = ax + t * dx;
        double nearY = ay + t * dy;
        return Math.sqrt((px - nearX) * (px - nearX) + (py - nearY) * (py - nearY));
    }

    /**
     * Trims bendpoints that fall inside source or target element boundaries.
     * A* routes from center-to-center, so intermediate BPs can land inside
     * source/target elements (which are excluded from obstacles). Removing
     * these prevents visual artifacts when edge attachment adds terminal BPs
     * at element edges.
     */
    static void trimEndpointBendpoints(List<AbsoluteBendpointDto> path,
            RoutingRect source, RoutingRect target) {
        // Trim from start: remove BPs inside source element
        while (!path.isEmpty() && isInsideOrOnBoundary(path.get(0), source)) {
            path.remove(0);
        }
        // Trim from end: remove BPs inside target element
        while (!path.isEmpty() && isInsideOrOnBoundary(path.get(path.size() - 1), target)) {
            path.remove(path.size() - 1);
        }
    }

    /**
     * Removes consecutive duplicate points.
     */
    public static void removeDuplicatePoints(List<AbsoluteBendpointDto> path) {
        int i = 0;
        while (i < path.size() - 1) {
            AbsoluteBendpointDto a = path.get(i);
            AbsoluteBendpointDto b = path.get(i + 1);
            if (a.x() == b.x() && a.y() == b.y()) {
                path.remove(i + 1);
            } else {
                i++;
            }
        }
    }

    /**
     * Restores terminal bendpoints to their edge-attachment positions if post-attachment
     * cleanup stages shifted them (Story 13-12). Determines the exit face from the
     * terminal position relative to the element, then inserts perpendicular alignment
     * if restoration creates a diagonal with the adjacent bendpoint.
     *
     * <p>Primary fix for: micro-jog removal propagating coordinates to terminal BPs,
     * which shifts them away from face center and causes off-center ChopboxAnchor exits.</p>
     */
    static void realignTerminals(List<AbsoluteBendpointDto> path,
            int[] savedFirst, int[] savedLast,
            ConnectionEndpoints conn) {
        if (path.size() < 2) return;

        // Restore source terminal if shifted
        AbsoluteBendpointDto first = path.get(0);
        if (first.x() != savedFirst[0] || first.y() != savedFirst[1]) {
            path.set(0, new AbsoluteBendpointDto(savedFirst[0], savedFirst[1]));
            // Ensure perpendicular: insert L-turn if diagonal with BP[1]
            AbsoluteBendpointDto next = path.get(1);
            if (savedFirst[0] != next.x() && savedFirst[1] != next.y()) {
                EdgeAttachmentCalculator.Face face = determineFaceFromTerminal(
                        savedFirst, conn.source());
                if (face == EdgeAttachmentCalculator.Face.LEFT
                        || face == EdgeAttachmentCalculator.Face.RIGHT) {
                    // Horizontal exit: maintain terminal Y
                    path.add(1, new AbsoluteBendpointDto(next.x(), savedFirst[1]));
                } else {
                    // Vertical exit: maintain terminal X
                    path.add(1, new AbsoluteBendpointDto(savedFirst[0], next.y()));
                }
            }
            logger.debug("Restored source terminal from ({},{}) to ({},{})",
                    first.x(), first.y(), savedFirst[0], savedFirst[1]);
        }

        // Restore target terminal if shifted
        AbsoluteBendpointDto last = path.get(path.size() - 1);
        if (last.x() != savedLast[0] || last.y() != savedLast[1]) {
            path.set(path.size() - 1, new AbsoluteBendpointDto(savedLast[0], savedLast[1]));
            AbsoluteBendpointDto prev = path.get(path.size() - 2);
            if (savedLast[0] != prev.x() && savedLast[1] != prev.y()) {
                EdgeAttachmentCalculator.Face face = determineFaceFromTerminal(
                        savedLast, conn.target());
                if (face == EdgeAttachmentCalculator.Face.LEFT
                        || face == EdgeAttachmentCalculator.Face.RIGHT) {
                    // Horizontal entry: maintain terminal Y
                    path.add(path.size() - 1, new AbsoluteBendpointDto(prev.x(), savedLast[1]));
                } else {
                    // Vertical entry: maintain terminal X
                    path.add(path.size() - 1, new AbsoluteBendpointDto(savedLast[0], prev.y()));
                }
            }
            logger.debug("Restored target terminal from ({},{}) to ({},{})",
                    last.x(), last.y(), savedLast[0], savedLast[1]);
        }

        // Clean up any duplicates/collinear introduced by realignment
        removeDuplicatePoints(path);
        removeCollinearPoints(path);
    }

    /**
     * Determines which element face a terminal bendpoint is on, based on its
     * position relative to the element boundary. Terminals are placed 1px outside
     * the element edge by {@link EdgeAttachmentCalculator#computeAttachmentPoint}.
     */
    static EdgeAttachmentCalculator.Face determineFaceFromTerminal(
            int[] terminal, RoutingRect element) {
        if (terminal[0] == element.x() - 1) return EdgeAttachmentCalculator.Face.LEFT;
        if (terminal[0] == element.x() + element.width() + 1) return EdgeAttachmentCalculator.Face.RIGHT;
        if (terminal[1] == element.y() - 1) return EdgeAttachmentCalculator.Face.TOP;
        if (terminal[1] == element.y() + element.height() + 1) return EdgeAttachmentCalculator.Face.BOTTOM;
        // Distributed terminal: X or Y varies along the face, but the other axis is at face edge.
        // Check axis that's fixed for each face pair.
        if (terminal[1] <= element.y()) return EdgeAttachmentCalculator.Face.TOP;
        if (terminal[1] >= element.y() + element.height()) return EdgeAttachmentCalculator.Face.BOTTOM;
        if (terminal[0] <= element.x()) return EdgeAttachmentCalculator.Face.LEFT;
        // Default: RIGHT — terminal is at or beyond the right edge (should not reach here
        // for terminals inside the element, as edge attachment always places them outside).
        return EdgeAttachmentCalculator.Face.RIGHT;
    }

    /**
     * Post-pipeline safety net (backlog-b28): ensures terminal segments are orthogonal.
     * Catches diagonals that survive or are reintroduced by cleanup/clearance stages.
     * Uses {@link #determineFaceFromTerminal} to choose correct L-turn direction.
     *
     * @return number of terminal segments corrected (0, 1, or 2 per path)
     */
    static int enforceTerminalOrthogonality(List<AbsoluteBendpointDto> path,
            ConnectionEndpoints conn) {
        if (path.size() < 2) return 0;
        int fixes = 0;

        // Check source terminal: BP[0] → BP[1]
        AbsoluteBendpointDto source = path.get(0);
        AbsoluteBendpointDto next = path.get(1);
        if (source.x() != next.x() && source.y() != next.y()) {
            // Diagonal — insert L-turn based on exit face
            EdgeAttachmentCalculator.Face face = determineFaceFromTerminal(
                    new int[]{source.x(), source.y()}, conn.source());
            if (face == EdgeAttachmentCalculator.Face.LEFT
                    || face == EdgeAttachmentCalculator.Face.RIGHT) {
                // Horizontal exit: maintain terminal Y
                path.add(1, new AbsoluteBendpointDto(next.x(), source.y()));
            } else {
                // Vertical exit: maintain terminal X
                path.add(1, new AbsoluteBendpointDto(source.x(), next.y()));
            }
            fixes++;
        }

        // Check target terminal: BP[n-2] → BP[n-1]
        AbsoluteBendpointDto last = path.get(path.size() - 1);
        AbsoluteBendpointDto prev = path.get(path.size() - 2);
        if (last.x() != prev.x() && last.y() != prev.y()) {
            EdgeAttachmentCalculator.Face face = determineFaceFromTerminal(
                    new int[]{last.x(), last.y()}, conn.target());
            if (face == EdgeAttachmentCalculator.Face.LEFT
                    || face == EdgeAttachmentCalculator.Face.RIGHT) {
                // Horizontal entry: maintain terminal Y
                path.add(path.size() - 1, new AbsoluteBendpointDto(prev.x(), last.y()));
            } else {
                // Vertical entry: maintain terminal X
                path.add(path.size() - 1, new AbsoluteBendpointDto(last.x(), prev.y()));
            }
            fixes++;
        }
        return fixes;
    }

    /**
     * Post-pipeline ChopboxAnchor alignment (backlog-b29): ensures first/last BPs share
     * a coordinate with the source/target element center. Archi draws from element center
     * to first/last BP — when they don't share an axis, the visual segment is diagonal.
     *
     * <p>Inserts a center-aligned BP as new first/last BP. The old terminal BP (at the
     * distributed face position) becomes the second/second-to-last BP.</p>
     *
     * @return number of terminal alignments inserted (0, 1, or 2 per path)
     */
    public static int alignTerminalsWithCenter(List<AbsoluteBendpointDto> path,
            ConnectionEndpoints conn) {
        if (path.size() < 2) return 0;
        int alignments = 0;

        // Source side: ensure BP[0] shares coordinate with source center
        AbsoluteBendpointDto first = path.get(0);
        RoutingRect source = conn.source();
        int scx = source.centerX();
        int scy = source.centerY();

        EdgeAttachmentCalculator.Face sourceFace = determineFaceFromTerminal(
                new int[]{first.x(), first.y()}, source);

        if (sourceFace == EdgeAttachmentCalculator.Face.LEFT
                || sourceFace == EdgeAttachmentCalculator.Face.RIGHT) {
            // Horizontal exit — need same Y as center
            if (first.y() != scy) {
                path.add(0, new AbsoluteBendpointDto(first.x(), scy));
                alignments++;
            }
        } else {
            // Vertical exit — need same X as center
            if (first.x() != scx) {
                path.add(0, new AbsoluteBendpointDto(scx, first.y()));
                alignments++;
            }
        }

        // Target side: ensure BP[n-1] shares coordinate with target center
        AbsoluteBendpointDto last = path.get(path.size() - 1);
        RoutingRect target = conn.target();
        int tcx = target.centerX();
        int tcy = target.centerY();

        EdgeAttachmentCalculator.Face targetFace = determineFaceFromTerminal(
                new int[]{last.x(), last.y()}, target);

        if (targetFace == EdgeAttachmentCalculator.Face.LEFT
                || targetFace == EdgeAttachmentCalculator.Face.RIGHT) {
            if (last.y() != tcy) {
                path.add(new AbsoluteBendpointDto(last.x(), tcy));
                alignments++;
            }
        } else {
            if (last.x() != tcx) {
                path.add(new AbsoluteBendpointDto(tcx, last.y()));
                alignments++;
            }
        }
        return alignments;
    }

    /**
     * Fixes center-terminated paths where a terminal BP is at element center coordinates
     * (backlog-b44). ChopboxAnchor draws from element center to first/last BP — if the BP
     * IS at center, the ray has zero length and the connection visually terminates at the
     * center instead of at an edge face.
     *
     * <p>For each affected terminal, computes the correct edge face toward the next/prev BP
     * and replaces the center-positioned BP with one at 1px outside the edge face (center
     * of the face for single-connection case).</p>
     *
     * @return number of terminals fixed (0, 1, or 2 per path)
     */
    public static int fixCenterTerminatedPath(List<AbsoluteBendpointDto> path,
            ConnectionEndpoints conn) {
        if (path.size() < 2) return 0;
        int fixes = 0;

        // Check source terminal: is first BP at source center?
        AbsoluteBendpointDto first = path.get(0);
        RoutingRect source = conn.source();
        if (first.x() == source.centerX() && first.y() == source.centerY()) {
            // Determine face toward BP[1] using center-relative direction
            AbsoluteBendpointDto next = path.get(1);
            EdgeAttachmentCalculator.Face face = EdgeAttachmentCalculator.determineFace(source, next.x(), next.y());
            int[] edgePt = computeEdgeFaceMidpoint(source, face);
            path.set(0, new AbsoluteBendpointDto(edgePt[0], edgePt[1]));
            fixes++;
            logger.debug("B44: Fixed source center-termination for connection {} — "
                    + "moved ({},{}) to {} face ({},{})", conn.connectionId(),
                    first.x(), first.y(), face, edgePt[0], edgePt[1]);
        }

        // Check target terminal: is last BP at target center?
        AbsoluteBendpointDto last = path.get(path.size() - 1);
        RoutingRect target = conn.target();
        if (last.x() == target.centerX() && last.y() == target.centerY()) {
            // Determine face toward BP[n-2] using center-relative direction
            AbsoluteBendpointDto prev = path.get(path.size() - 2);
            EdgeAttachmentCalculator.Face face = EdgeAttachmentCalculator.determineFace(target, prev.x(), prev.y());
            int[] edgePt = computeEdgeFaceMidpoint(target, face);
            path.set(path.size() - 1, new AbsoluteBendpointDto(edgePt[0], edgePt[1]));
            fixes++;
            logger.debug("B44: Fixed target center-termination for connection {} — "
                    + "moved ({},{}) to {} face ({},{})", conn.connectionId(),
                    last.x(), last.y(), face, edgePt[0], edgePt[1]);
        }

        return fixes;
    }

    /**
     * Fixes terminal and intermediate BPs that are inside source or target element bounds
     * (backlog-b45). Post-processing stages 4.7g–4.7i can shift BPs back inside elements
     * after correctEndpointPassThroughs (4.6a) cleaned them. fixCenterTerminatedPath (4.7k)
     * only catches BPs at exact element center — this method catches all interior BPs.
     *
     * <p>For terminal BPs (first/last), repositions to the appropriate edge face midpoint
     * (1px outside). For intermediate BPs, removes them entirely.</p>
     *
     * @return number of BPs fixed or removed
     */
    public static int fixInteriorTerminalBPs(List<AbsoluteBendpointDto> path,
            ConnectionEndpoints conn) {
        if (path.size() < 2) return 0;
        int fixes = 0;

        RoutingRect source = conn.source();
        RoutingRect target = conn.target();

        // Check source terminal: is first BP inside source element?
        AbsoluteBendpointDto first = path.get(0);
        if (isInsideOrOnBoundary(first, source)) {
            AbsoluteBendpointDto next = path.get(1);
            EdgeAttachmentCalculator.Face face = EdgeAttachmentCalculator.determineFace(
                    source, next.x(), next.y());
            int[] edgePt = computeEdgeFaceMidpoint(source, face);
            path.set(0, new AbsoluteBendpointDto(edgePt[0], edgePt[1]));
            // B45 fix: insert L-bend if repositioning broke orthogonality
            next = path.get(1); // re-read in case list changed
            if (edgePt[0] != next.x() && edgePt[1] != next.y()) {
                if (face == EdgeAttachmentCalculator.Face.TOP
                        || face == EdgeAttachmentCalculator.Face.BOTTOM) {
                    path.add(1, new AbsoluteBendpointDto(edgePt[0], next.y()));
                } else {
                    path.add(1, new AbsoluteBendpointDto(next.x(), edgePt[1]));
                }
                logger.debug("B45: Inserted L-bend after source fix for connection {}",
                        conn.connectionId());
            }
            fixes++;
            logger.debug("B45: Fixed source interior-BP for connection {} — "
                    + "moved ({},{}) to {} face ({},{})", conn.connectionId(),
                    first.x(), first.y(), face, edgePt[0], edgePt[1]);
        }

        // Check target terminal: is last BP inside target element?
        AbsoluteBendpointDto last = path.get(path.size() - 1);
        if (isInsideOrOnBoundary(last, target)) {
            AbsoluteBendpointDto prev = path.get(path.size() - 2);
            EdgeAttachmentCalculator.Face face = EdgeAttachmentCalculator.determineFace(
                    target, prev.x(), prev.y());
            int[] edgePt = computeEdgeFaceMidpoint(target, face);
            path.set(path.size() - 1, new AbsoluteBendpointDto(edgePt[0], edgePt[1]));
            // B45 fix: insert L-bend if repositioning broke orthogonality
            prev = path.get(path.size() - 2); // re-read in case list changed
            if (edgePt[0] != prev.x() && edgePt[1] != prev.y()) {
                if (face == EdgeAttachmentCalculator.Face.TOP
                        || face == EdgeAttachmentCalculator.Face.BOTTOM) {
                    path.add(path.size() - 1, new AbsoluteBendpointDto(edgePt[0], prev.y()));
                } else {
                    path.add(path.size() - 1, new AbsoluteBendpointDto(prev.x(), edgePt[1]));
                }
                logger.debug("B45: Inserted L-bend before target fix for connection {}",
                        conn.connectionId());
            }
            fixes++;
            logger.debug("B45: Fixed target interior-BP for connection {} — "
                    + "moved ({},{}) to {} face ({},{})", conn.connectionId(),
                    last.x(), last.y(), face, edgePt[0], edgePt[1]);
        }

        // Remove intermediate BPs inside source or target element
        if (path.size() > 2) {
            for (int i = path.size() - 2; i >= 1; i--) {
                AbsoluteBendpointDto bp = path.get(i);
                if (isInsideOrOnBoundary(bp, source) || isInsideOrOnBoundary(bp, target)) {
                    path.remove(i);
                    fixes++;
                    logger.debug("B45: Removed intermediate BP ({},{}) inside endpoint "
                            + "element for connection {}", bp.x(), bp.y(),
                            conn.connectionId());
                }
            }
        }

        return fixes;
    }

    /**
     * Computes the midpoint on an element edge face at 1px outside the element boundary.
     * Used by center-termination fix to place terminal BPs at the correct edge position.
     */
    private static int[] computeEdgeFaceMidpoint(RoutingRect element,
            EdgeAttachmentCalculator.Face face) {
        int x = element.x();
        int y = element.y();
        int w = element.width();
        int h = element.height();
        int cx = element.centerX();
        int cy = element.centerY();
        switch (face) {
            case TOP:    return new int[]{cx, y - 1};
            case BOTTOM: return new int[]{cx, y + h + 1};
            case LEFT:   return new int[]{x - 1, cy};
            case RIGHT:  return new int[]{x + w + 1, cy};
            default:     throw new IllegalArgumentException("Unknown face: " + face);
        }
    }

    /**
     * Corrects endpoint pass-throughs introduced by pipeline stages (Story 13-4).
     * Step 1: Remove any BPs that are inside source/target element bodies
     *         (trimEndpointBendpoints only removes from ends, not interior).
     * Step 2: Fix diagonals created by BP removal — choose the orthogonal direction
     *         that avoids the endpoint element.
     * Step 3: Insert corrective detour BPs for any remaining crossings.
     */
    static void correctEndpointPassThroughs(List<AbsoluteBendpointDto> path,
                                             RoutingRect source, RoutingRect target) {
        // Guard: skip if source and target overlap — removing BPs could destroy the path
        if (rectsOverlap(source, target)) return;

        // Step 1: Remove interior BPs inside endpoint elements
        path.removeIf(bp -> isInsideOrOnBoundary(bp, target));
        path.removeIf(bp -> isInsideOrOnBoundary(bp, source));

        // Step 2: Fix diagonals from BP removal — choose direction avoiding endpoints
        fixDiagonalsAvoidingElement(path, target);
        fixDiagonalsAvoidingElement(path, source);

        // Step 3: Insert detours for segments still crossing endpoint elements
        insertDetourAroundElement(path, target, 10);
        insertDetourAroundElement(path, source, 10);

        // Clean up artifacts from insertion
        removeDuplicatePoints(path);
        removeCollinearPoints(path);
    }

    /**
     * Detects if the full path passes through a self-element (source or target) on a
     * non-terminal segment (backlog-b34). Mirrors {@link #hasEndpointPassThrough} logic
     * but returns the offending segment index for targeted correction.
     *
     * @param bendpoints intermediate bendpoints (no centers)
     * @param source     source element rectangle
     * @param target     target element rectangle
     * @param isSource   true to check source element, false for target
     * @return index of the first offending segment in the full path, or -1 if clean
     */
    int detectSelfElementPassThrough(List<AbsoluteBendpointDto> bendpoints,
                                      RoutingRect source, RoutingRect target, boolean isSource) {
        List<int[]> fullPath = new ArrayList<>();
        fullPath.add(new int[]{source.centerX(), source.centerY()});
        for (AbsoluteBendpointDto bp : bendpoints) {
            fullPath.add(new int[]{bp.x(), bp.y()});
        }
        fullPath.add(new int[]{target.centerX(), target.centerY()});

        if (fullPath.size() < 3) return -1;

        RoutingRect element = isSource ? source : target;
        int inset = 5;
        int ix = element.x() + inset;
        int iy = element.y() + inset;
        int iw = element.width() - 2 * inset;
        int ih = element.height() - 2 * inset;
        if (iw <= 0 || ih <= 0) return -1;

        RoutingRect insetRect = new RoutingRect(ix, iy, iw, ih, element.id());

        int start = isSource ? 1 : 0;
        int end = isSource ? fullPath.size() - 1 : fullPath.size() - 2;

        for (int i = start; i < end; i++) {
            int[] a = fullPath.get(i);
            int[] b = fullPath.get(i + 1);
            if (segmentIntersectsAnyObstacle(a[0], a[1], b[0], b[1], List.of(insetRect))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Corrects a self-element pass-through by re-selecting the face and re-routing
     * terminal segments (backlog-b35 Phase B). When a connection's routed path clips
     * through its own source or target element, this method:
     * <ol>
     *   <li>Tries alternative faces in angular proximity order</li>
     *   <li>For each candidate face, builds a re-routed path with a clearance waypoint</li>
     *   <li>Removes old terminal-adjacent segments inside the element</li>
     *   <li>Connects the clearance waypoint to the first external BP</li>
     * </ol>
     *
     * <p>B35 redesign: Unlike B34 which only swapped the terminal BP (creating a
     * Frankenstein path with old intermediate segments routed for the wrong face),
     * this version re-routes the terminal-adjacent segments to produce a coherent path.</p>
     *
     * @param path       mutable bendpoint list (includes terminal BPs)
     * @param connection connection endpoint data
     * @param isSource   true for source element, false for target
     * @return true if a correction was applied
     */
    boolean correctSelfElementPassThrough(List<AbsoluteBendpointDto> path,
                                           ConnectionEndpoints connection, boolean isSource) {
        RoutingRect source = connection.source();
        RoutingRect target = connection.target();

        int offendingIdx = detectSelfElementPassThrough(path, source, target, isSource);
        if (offendingIdx < 0) return false;

        RoutingRect element = isSource ? source : target;
        RoutingRect other = isSource ? target : source;

        // Determine current face from terminal BP position
        AbsoluteBendpointDto terminalBp = isSource ? path.get(0) : path.get(path.size() - 1);
        EdgeAttachmentCalculator.Face currentFace =
                EdgeAttachmentCalculator.determineFace(element, terminalBp.x(), terminalBp.y());

        // Try alternative faces in angular proximity order (B35: consistent with Phase A ordering)
        EdgeAttachmentCalculator.Face[] alternatives =
                edgeAttachmentCalculator.getAlternativeFacesInAngularOrder(element, other, currentFace);

        for (EdgeAttachmentCalculator.Face candidateFace : alternatives) {
            // Build re-routed path with clearance waypoint
            List<AbsoluteBendpointDto> trial = buildReroutedPath(path, element, candidateFace, isSource);

            // Check if the re-routed path eliminates the PT
            int newOffending = detectSelfElementPassThrough(trial, source, target, isSource);
            if (newOffending < 0) {
                // Apply the re-routed path
                path.clear();
                path.addAll(trial);

                // Ensure perpendicular approach on the new face
                int termIdx = isSource ? 0 : path.size() - 1;
                int adjIdx = isSource ? 1 : path.size() - 2;
                if (path.size() >= 2) {
                    edgeAttachmentCalculator.ensurePerpendicularSegment(
                            path, termIdx, adjIdx, candidateFace, isSource,
                            connection.obstacles());
                }

                // Post-correction cleanup
                removeDuplicatePoints(path);
                removeCollinearPoints(path);

                logger.debug("B35 Phase B: {} face {} → {} for conn {} (re-routed with clearance WP)",
                        isSource ? "source" : "target", currentFace, candidateFace,
                        connection.connectionId());
                return true;
            }
        }

        logger.warn("B35 Phase B: no face eliminates {} pass-through for conn {}",
                isSource ? "source" : "target", connection.connectionId());
        return false;
    }

    /**
     * Builds a re-routed path with a new terminal BP and clearance waypoint on the given face.
     * Removes old terminal-adjacent BPs that are inside the element bounds, keeping
     * the core intermediate path intact.
     *
     * @param originalPath the current path (with terminal BPs)
     * @param element      the element being re-routed around
     * @param newFace      the new face to exit/enter from
     * @param isSource     true for source side, false for target side
     * @return a new path with re-routed terminal segments
     */
    private List<AbsoluteBendpointDto> buildReroutedPath(List<AbsoluteBendpointDto> originalPath,
            RoutingRect element, EdgeAttachmentCalculator.Face newFace, boolean isSource) {

        // Compute new terminal BP on the candidate face
        int[] terminalPoint = edgeAttachmentCalculator.computeAttachmentPoint(element, newFace, 0, 1);
        AbsoluteBendpointDto newTerminal = new AbsoluteBendpointDto(terminalPoint[0], terminalPoint[1]);

        // Compute clearance waypoint at margin distance outside element on new face
        AbsoluteBendpointDto clearanceWP = computeClearanceWaypoint(element, newFace, terminalPoint);

        List<AbsoluteBendpointDto> result = new ArrayList<>();

        if (isSource) {
            // Source side: prepend new terminal + clearance, connect to first external BP
            result.add(newTerminal);
            result.add(clearanceWP);

            // Find first BP (after old terminal) that is outside element bounds.
            // Note: BPs after this point that happen to be inside the element are retained
            // as part of the intermediate path — only terminal-adjacent internal BPs are removed.
            // If retained internal BPs cause a PT, detectSelfElementPassThrough rejects the trial
            // and the next face candidate is tried.
            int firstExternalIdx = -1;
            for (int i = 1; i < originalPath.size(); i++) {
                AbsoluteBendpointDto bp = originalPath.get(i);
                if (!isInsideElement(bp.x(), bp.y(), element)) {
                    firstExternalIdx = i;
                    break;
                }
            }

            // Add remaining BPs from firstExternalIdx onward
            int startIdx = (firstExternalIdx >= 0) ? firstExternalIdx : 1;
            for (int i = startIdx; i < originalPath.size(); i++) {
                result.add(originalPath.get(i));
            }
        } else {
            // Target side: keep BPs up to last external, then append clearance + new terminal
            int lastExternalIdx = -1;
            for (int i = originalPath.size() - 2; i >= 0; i--) {
                AbsoluteBendpointDto bp = originalPath.get(i);
                if (!isInsideElement(bp.x(), bp.y(), element)) {
                    lastExternalIdx = i;
                    break;
                }
            }

            // Add BPs up to lastExternalIdx
            int endIdx = (lastExternalIdx >= 0) ? lastExternalIdx : originalPath.size() - 2;
            for (int i = 0; i <= endIdx; i++) {
                result.add(originalPath.get(i));
            }
            result.add(clearanceWP);
            result.add(newTerminal);
        }

        return result;
    }

    /**
     * Computes a clearance waypoint at {@code margin} distance outside the element on the given face.
     * The waypoint is perpendicular-aligned with the terminal BP to maintain orthogonality.
     */
    private AbsoluteBendpointDto computeClearanceWaypoint(RoutingRect element,
            EdgeAttachmentCalculator.Face face, int[] terminalPoint) {
        switch (face) {
            case TOP:
                return new AbsoluteBendpointDto(terminalPoint[0], element.y() - margin);
            case BOTTOM:
                return new AbsoluteBendpointDto(terminalPoint[0], element.y() + element.height() + margin);
            case LEFT:
                return new AbsoluteBendpointDto(element.x() - margin, terminalPoint[1]);
            case RIGHT:
                return new AbsoluteBendpointDto(element.x() + element.width() + margin, terminalPoint[1]);
            default:
                return new AbsoluteBendpointDto(terminalPoint[0], terminalPoint[1]);
        }
    }

    /**
     * Tests whether a point is inside an element's bounding box (inclusive of edges).
     */
    private static boolean isInsideElement(int x, int y, RoutingRect element) {
        return x >= element.x() && x <= element.x() + element.width()
            && y >= element.y() && y <= element.y() + element.height();
    }

    /**
     * Fixes diagonal segments created by BP removal by inserting an L-turn midpoint.
     * Chooses the orthogonal direction (horizontal-first vs vertical-first) that
     * avoids crossing through the given element (Story 13-4).
     */
    private static void fixDiagonalsAvoidingElement(List<AbsoluteBendpointDto> path,
                                                     RoutingRect element) {
        int inset = 5;
        int iw = element.width() - 2 * inset, ih = element.height() - 2 * inset;
        if (iw <= 0 || ih <= 0) return;
        RoutingRect insetRect = new RoutingRect(
                element.x() + inset, element.y() + inset, iw, ih, element.id());
        List<RoutingRect> insetList = List.of(insetRect);

        for (int i = 0; i < path.size() - 1; i++) {
            AbsoluteBendpointDto a = path.get(i);
            AbsoluteBendpointDto b = path.get(i + 1);

            if (a.x() == b.x() || a.y() == b.y()) continue; // already orthogonal

            // Diagonal segment — try both L-turn directions
            // Horizontal-first: (ax,ay) → (bx,ay) → (bx,by)
            boolean hCrosses =
                    segmentIntersectsAnyObstacle(a.x(), a.y(), b.x(), a.y(), insetList)
                    || segmentIntersectsAnyObstacle(b.x(), a.y(), b.x(), b.y(), insetList);
            // Vertical-first: (ax,ay) → (ax,by) → (bx,by)
            boolean vCrosses =
                    segmentIntersectsAnyObstacle(a.x(), a.y(), a.x(), b.y(), insetList)
                    || segmentIntersectsAnyObstacle(a.x(), b.y(), b.x(), b.y(), insetList);

            // Prefer the direction that avoids the element
            AbsoluteBendpointDto mid;
            if (!vCrosses) {
                mid = new AbsoluteBendpointDto(a.x(), b.y());
            } else if (!hCrosses) {
                mid = new AbsoluteBendpointDto(b.x(), a.y());
            } else {
                // Both cross — horizontal-first default (insertDetour will handle)
                mid = new AbsoluteBendpointDto(b.x(), a.y());
            }
            path.add(i + 1, mid);
            i++; // skip the inserted point
        }
    }

    /**
     * If any segment of the path crosses through the given element's inset rect,
     * inserts corrective BPs to detour around the element (Story 13-4).
     * For horizontal crossings, detours above or below. For vertical, left or right.
     * Picks the direction closest to the segment's current position.
     * Loops until no crossings remain (max 10 iterations to prevent infinite loops).
     */
    private static void insertDetourAroundElement(List<AbsoluteBendpointDto> path,
                                                    RoutingRect element, int detourMargin) {
        if (path.size() < 2) return;

        int inset = 5;
        int ix = element.x() + inset, iy = element.y() + inset;
        int iw = element.width() - 2 * inset, ih = element.height() - 2 * inset;
        if (iw <= 0 || ih <= 0) return;
        RoutingRect insetRect = new RoutingRect(ix, iy, iw, ih, element.id());

        int maxIterations = 10;
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            boolean corrected = false;

            for (int i = 0; i < path.size() - 1; i++) {
                AbsoluteBendpointDto a = path.get(i);
                AbsoluteBendpointDto b = path.get(i + 1);

                if (!segmentIntersectsAnyObstacle(a.x(), a.y(), b.x(), b.y(), List.of(insetRect))) {
                    continue;
                }

                int eCenterY = element.y() + element.height() / 2;
                int eCenterX = element.x() + element.width() / 2;

                if (a.y() == b.y()) {
                    // Horizontal segment crossing through element — detour above or below
                    int detourY = (a.y() <= eCenterY)
                            ? element.y() - detourMargin       // above
                            : element.y() + element.height() + detourMargin; // below
                    path.add(i + 1, new AbsoluteBendpointDto(a.x(), detourY));
                    path.add(i + 2, new AbsoluteBendpointDto(b.x(), detourY));
                    corrected = true;
                    break; // restart scan after insertion
                } else if (a.x() == b.x()) {
                    // Vertical segment crossing through element — detour left or right
                    int detourX = (a.x() <= eCenterX)
                            ? element.x() - detourMargin       // left
                            : element.x() + element.width() + detourMargin; // right
                    path.add(i + 1, new AbsoluteBendpointDto(detourX, a.y()));
                    path.add(i + 2, new AbsoluteBendpointDto(detourX, b.y()));
                    corrected = true;
                    break; // restart scan after insertion
                }
                // Non-orthogonal segment — skip (enforceOrthogonalPaths should have cleaned these)
            }

            if (!corrected) break; // no more crossings found
        }
    }

    /**
     * Removes collinear intermediate points (3+ consecutive points on the same
     * horizontal or vertical line). The middle point adds no direction change
     * and creates visual artifacts.
     */
    public static void removeCollinearPoints(List<AbsoluteBendpointDto> path) {
        int i = 0;
        while (i < path.size() - 2) {
            AbsoluteBendpointDto a = path.get(i);
            AbsoluteBendpointDto b = path.get(i + 1);
            AbsoluteBendpointDto c = path.get(i + 2);
            if ((a.x() == b.x() && b.x() == c.x()) || (a.y() == b.y() && b.y() == c.y())) {
                path.remove(i + 1);
            } else {
                i++;
            }
        }
    }

    /**
     * Snaps near-aligned connections to straight segments (backlog-b17).
     * When source and target terminal bendpoints differ by at most {@code threshold}
     * pixels in one axis, replaces the entire path with a 2-point straight segment
     * (aligning the source terminal to the target's coordinate in the minor axis).
     *
     * <p>The snap is rejected if the resulting straight path passes through any obstacle
     * or through the source/target elements themselves.</p>
     *
     * @param path       mutable bendpoint list (modified in place if snap applies)
     * @param source     source element bounding box
     * @param target     target element bounding box
     * @param obstacles  per-connection obstacle list
     * @param threshold  max pixel offset for snap (0 disables)
     */
    static void snapToStraightIfAligned(List<AbsoluteBendpointDto> path,
            RoutingRect source, RoutingRect target,
            List<RoutingRect> obstacles, int threshold) {
        if (path.size() <= 2 || threshold <= 0) {
            return;
        }

        AbsoluteBendpointDto sourceBP = path.get(0);
        AbsoluteBendpointDto targetBP = path.get(path.size() - 1);

        int deltaX = Math.abs(targetBP.x() - sourceBP.x());
        int deltaY = Math.abs(targetBP.y() - sourceBP.y());

        // Determine snap axis: snap the minor offset to target's coordinate
        int newSourceX = sourceBP.x();
        int newSourceY = sourceBP.y();

        if (deltaX <= threshold && deltaY > deltaX) {
            // Near-aligned vertically — snap source X to target X
            newSourceX = targetBP.x();
        } else if (deltaY <= threshold && deltaX > deltaY) {
            // Near-aligned horizontally — snap source Y to target Y
            newSourceY = targetBP.y();
        } else {
            // Not near-aligned or diagonal — skip
            return;
        }

        AbsoluteBendpointDto newSourceBP = new AbsoluteBendpointDto(newSourceX, newSourceY);

        // Validate: straight path must not pass through any obstacle
        if (segmentIntersectsAnyObstacle(newSourceBP.x(), newSourceBP.y(),
                targetBP.x(), targetBP.y(), obstacles)) {
            return;
        }

        // Validate: snapped source terminal must not be inside source element
        if (isPointInsideRect(newSourceBP.x(), newSourceBP.y(), source)) {
            return;
        }

        // Validate: straight path must not pass through source or target element
        if (segmentIntersectsElement(newSourceBP.x(), newSourceBP.y(),
                targetBP.x(), targetBP.y(), source)
                || segmentIntersectsElement(newSourceBP.x(), newSourceBP.y(),
                        targetBP.x(), targetBP.y(), target)) {
            return;
        }

        // Apply: replace entire path with straight segment
        path.clear();
        path.add(newSourceBP);
        path.add(targetBP);
    }

    /** Returns true if point (px, py) is strictly inside the rectangle. */
    private static boolean isPointInsideRect(int px, int py, RoutingRect rect) {
        return px > rect.x() && px < rect.x() + rect.width()
                && py > rect.y() && py < rect.y() + rect.height();
    }

    /** Returns true if the line segment intersects the element bounding box. */
    private static boolean segmentIntersectsElement(int x1, int y1, int x2, int y2,
            RoutingRect element) {
        return EdgeAttachmentCalculator.lineSegmentIntersectsRect(
                x1, y1, x2, y2,
                element.x(), element.y(), element.width(), element.height());
    }

    /**
     * Removes micro-jog segments — very short orthogonal segments that create
     * unnecessary visual bends at nudge boundaries and edge attachment seams.
     * Snaps the shorter side to the dominant adjacent segment's coordinate,
     * propagating the change along the connected segment to maintain orthogonality.
     */
    static void removeMicroJogs(List<AbsoluteBendpointDto> path, int threshold) {
        if (path.size() < 3) {
            return;
        }

        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < path.size() - 1; i++) {
                AbsoluteBendpointDto a = path.get(i);
                AbsoluteBendpointDto b = path.get(i + 1);
                int dx = Math.abs(b.x() - a.x());
                int dy = Math.abs(b.y() - a.y());

                boolean isVerticalJog = dx == 0 && dy > 0 && dy <= threshold;
                boolean isHorizontalJog = dy == 0 && dx > 0 && dx <= threshold;

                if (!isVerticalJog && !isHorizontalJog) {
                    continue;
                }

                if (isVerticalJog) {
                    int countBackward = countMatchingCoord(path, i, true, true);
                    int countForward = countMatchingCoord(path, i + 1, false, true);
                    if (countBackward >= countForward) {
                        propagateCoord(path, i + 1, countForward, false, true, a.y());
                    } else {
                        propagateCoord(path, i, countBackward, true, true, b.y());
                    }
                } else {
                    int countBackward = countMatchingCoord(path, i, true, false);
                    int countForward = countMatchingCoord(path, i + 1, false, false);
                    if (countBackward >= countForward) {
                        propagateCoord(path, i + 1, countForward, false, false, a.x());
                    } else {
                        propagateCoord(path, i, countBackward, true, false, b.x());
                    }
                }

                changed = true;
                break; // Restart scan after modification
            }
        }
    }

    /**
     * Counts consecutive BPs from startIndex that share the same coordinate.
     * @param isY true to compare y coordinates, false for x
     * @param backward true to count toward index 0, false toward end
     */
    private static int countMatchingCoord(List<AbsoluteBendpointDto> path,
            int startIndex, boolean backward, boolean isY) {
        int coord = isY ? path.get(startIndex).y() : path.get(startIndex).x();
        int count = 1;
        int step = backward ? -1 : 1;
        int idx = startIndex + step;
        while (idx >= 0 && idx < path.size()) {
            int c = isY ? path.get(idx).y() : path.get(idx).x();
            if (c != coord) break;
            count++;
            idx += step;
        }
        return count;
    }

    /**
     * Propagates a coordinate change along consecutive BPs.
     * @param isY true to change y coordinates, false for x
     */
    private static void propagateCoord(List<AbsoluteBendpointDto> path,
            int startIndex, int count, boolean backward, boolean isY, int newCoord) {
        int step = backward ? -1 : 1;
        int idx = startIndex;
        for (int n = 0; n < count; n++) {
            AbsoluteBendpointDto bp = path.get(idx);
            if (isY) {
                path.set(idx, new AbsoluteBendpointDto(bp.x(), newCoord));
            } else {
                path.set(idx, new AbsoluteBendpointDto(newCoord, bp.y()));
            }
            idx += step;
        }
    }

    /**
     * Removes bendpoints that create segments passing through obstacles (Story 10-25).
     * Walks the path and removes any bendpoint whose adjacent segments intersect an obstacle.
     * Uses expanded obstacle bounds (margin-inflated) to match A* clearance.
     */
    static void removeObstacleViolations(List<AbsoluteBendpointDto> path,
            List<RoutingRect> obstacles) {
        if (path.size() < 2 || obstacles.isEmpty()) {
            return;
        }

        boolean changed = true;
        int maxIterations = path.size() + 5; // Safety bound
        int iterations = 0;
        while (changed && iterations++ < maxIterations) {
            changed = false;
            for (int i = 0; i < path.size() - 1; i++) {
                AbsoluteBendpointDto a = path.get(i);
                AbsoluteBendpointDto b = path.get(i + 1);
                if (segmentIntersectsAnyObstacle(a.x(), a.y(), b.x(), b.y(), obstacles)) {
                    // Remove the bendpoint that is more likely the offender:
                    // - If it's an interior point (not first or last), remove it
                    // - If both are endpoints, remove the one closer to an obstacle center
                    if (i > 0 && i < path.size() - 2) {
                        // Interior segment: try removing point i+1 first, then i
                        path.remove(i + 1);
                    } else if (i == 0 && path.size() > 2) {
                        path.remove(0);
                    } else if (i == path.size() - 2 && path.size() > 2) {
                        path.remove(path.size() - 1);
                    } else {
                        // Only 2 points left and they intersect — can't fix, leave as-is
                        break;
                    }
                    changed = true;
                    break; // Restart scan
                }
            }
        }
    }

    /**
     * Read-only check: returns the first obstacle crossed by any path segment, or null if clean
     * (Story 10-30, enriched Story 10-34).
     * Does NOT modify the path — used to classify connections as failed after all pipeline stages.
     */
    static RoutingRect findFirstObstacleViolation(List<AbsoluteBendpointDto> path,
            List<RoutingRect> obstacles) {
        if (path.size() < 2 || obstacles.isEmpty()) {
            return null;
        }
        for (int i = 0; i < path.size() - 1; i++) {
            AbsoluteBendpointDto a = path.get(i);
            AbsoluteBendpointDto b = path.get(i + 1);
            RoutingRect hit = findFirstIntersectedObstacle(a.x(), a.y(), b.x(), b.y(), obstacles);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /**
     * Finds the first obstacle rectangle intersected by a line segment, or null if none.
     * Uses Liang-Barsky clipping (delegates to EdgeAttachmentCalculator).
     */
    static RoutingRect findFirstIntersectedObstacle(int x1, int y1, int x2, int y2,
            List<RoutingRect> obstacles) {
        for (RoutingRect obs : obstacles) {
            if (EdgeAttachmentCalculator.lineSegmentIntersectsRect(
                    x1, y1, x2, y2,
                    obs.x(), obs.y(), obs.width(), obs.height())) {
                return obs;
            }
        }
        return null;
    }

    /**
     * Boolean convenience: checks if a line segment intersects any obstacle rectangle.
     */
    static boolean segmentIntersectsAnyObstacle(int x1, int y1, int x2, int y2,
            List<RoutingRect> obstacles) {
        return findFirstIntersectedObstacle(x1, y1, x2, y2, obstacles) != null;
    }

    /**
     * Ensures consecutive bendpoints form orthogonal segments (Story 10-25, Pattern 3).
     * If two consecutive bendpoints differ in both x and y (diagonal), inserts an
     * intermediate L-turn bendpoint to restore orthogonality.
     */
    static void enforceOrthogonalPaths(List<AbsoluteBendpointDto> path) {
        int i = 0;
        while (i < path.size() - 1) {
            AbsoluteBendpointDto a = path.get(i);
            AbsoluteBendpointDto b = path.get(i + 1);
            if (a.x() != b.x() && a.y() != b.y()) {
                // Diagonal segment — insert L-turn: go horizontal first, then vertical
                AbsoluteBendpointDto intermediate = new AbsoluteBendpointDto(b.x(), a.y());
                path.add(i + 1, intermediate);
                logger.debug("Inserted orthogonal L-turn at ({},{}) between ({},{}) and ({},{})",
                        intermediate.x(), intermediate.y(), a.x(), a.y(), b.x(), b.y());
                // Don't increment i — re-check the new segment pair
            } else {
                i++;
            }
        }
    }

    /**
     * Simplifies a routed path by greedily shortcutting non-adjacent points
     * with obstacle-free orthogonal segments (straight lines or L-turns).
     * Reduces staircase patterns created by A* stepping through visibility graph nodes.
     *
     * @param path         mutable list of intermediate bendpoints (source/target excluded)
     * @param sourceCenter source element center [x, y]
     * @param targetCenter target element center [x, y]
     * @param obstacles    per-connection obstacle list
     */
    static void simplifyPath(List<AbsoluteBendpointDto> path,
            int[] sourceCenter, int[] targetCenter, List<RoutingRect> obstacles) {
        if (path.size() < 2) {
            return; // 0 or 1 intermediate BPs — nothing to simplify
        }

        // Build full path: source center + intermediates + target center
        List<AbsoluteBendpointDto> full = new ArrayList<>();
        full.add(new AbsoluteBendpointDto(sourceCenter[0], sourceCenter[1]));
        full.addAll(path);
        full.add(new AbsoluteBendpointDto(targetCenter[0], targetCenter[1]));

        // Greedy shortcutting: from each point, find the farthest reachable point
        List<AbsoluteBendpointDto> simplified = new ArrayList<>();
        simplified.add(full.get(0)); // start with source

        int i = 0;
        while (i < full.size() - 1) {
            // Try to shortcut to the farthest reachable point
            int bestJ = i + 1;
            for (int j = full.size() - 1; j > i + 1; j--) {
                if (canShortcut(full.get(i), full.get(j), obstacles)) {
                    bestJ = j;
                    break;
                }
            }

            if (bestJ > i + 1) {
                // Shortcut found — add L-turn midpoint if endpoints differ in both x and y
                AbsoluteBendpointDto a = full.get(i);
                AbsoluteBendpointDto b = full.get(bestJ);
                if (a.x() != b.x() && a.y() != b.y()) {
                    // Try horizontal-first L-turn (matches canShortcut priority)
                    AbsoluteBendpointDto hMid = new AbsoluteBendpointDto(b.x(), a.y());
                    if (!segmentIntersectsAnyObstacle(a.x(), a.y(), hMid.x(), hMid.y(), obstacles)
                            && !segmentIntersectsAnyObstacle(hMid.x(), hMid.y(), b.x(), b.y(), obstacles)) {
                        simplified.add(hMid);
                    } else {
                        // Must be vertical-first (canShortcut verified one works)
                        simplified.add(new AbsoluteBendpointDto(a.x(), b.y()));
                    }
                }
                // If same x or same y → straight line, no midpoint needed
            }

            simplified.add(full.get(bestJ));
            i = bestJ;
        }

        // Extract intermediate points (strip source at index 0 and target at last)
        path.clear();
        for (int k = 1; k < simplified.size() - 1; k++) {
            path.add(simplified.get(k));
        }
    }

    /**
     * Simplifies a final routed path by greedily shortcutting non-adjacent points
     * with obstacle-free orthogonal segments. Unlike {@link #simplifyPath}, this operates
     * directly on the full path where index 0 and last index ARE terminal BPs placed by
     * edge attachment — no source/target center prepend/append needed.
     *
     * Terminal BPs (first and last) are preserved as greedy chain anchors.
     * Requires at least 4 BPs for a jog to exist (source-terminal, 2+ intermediates,
     * target-terminal).
     *
     * @param path      mutable list of all bendpoints including terminals
     * @param obstacles per-connection obstacle list (excludes source/target elements)
     */
    static void simplifyFinalPath(List<AbsoluteBendpointDto> path, List<RoutingRect> obstacles) {
        if (path.size() < 4) {
            return; // Need at least source-terminal, 2 intermediates, target-terminal for a jog
        }

        // Greedy shortcutting: from each point, find the farthest reachable point
        List<AbsoluteBendpointDto> simplified = new ArrayList<>();
        simplified.add(path.get(0)); // start with source terminal

        int i = 0;
        while (i < path.size() - 1) {
            int bestJ = i + 1;
            for (int j = path.size() - 1; j > i + 1; j--) {
                if (canShortcut(path.get(i), path.get(j), obstacles)) {
                    bestJ = j;
                    break;
                }
            }

            if (bestJ > i + 1) {
                // Shortcut found — add L-turn midpoint if endpoints differ in both x and y
                AbsoluteBendpointDto a = path.get(i);
                AbsoluteBendpointDto b = path.get(bestJ);
                if (a.x() != b.x() && a.y() != b.y()) {
                    // Try horizontal-first L-turn (matches canShortcut priority)
                    AbsoluteBendpointDto hMid = new AbsoluteBendpointDto(b.x(), a.y());
                    if (!segmentIntersectsAnyObstacle(a.x(), a.y(), hMid.x(), hMid.y(), obstacles)
                            && !segmentIntersectsAnyObstacle(hMid.x(), hMid.y(), b.x(), b.y(), obstacles)) {
                        simplified.add(hMid);
                    } else {
                        // Must be vertical-first (canShortcut verified one works)
                        simplified.add(new AbsoluteBendpointDto(a.x(), b.y()));
                    }
                }
                // If same x or same y → straight line, no midpoint needed
            }

            simplified.add(path.get(bestJ));
            i = bestJ;
        }

        // Replace path contents with simplified version
        path.clear();
        path.addAll(simplified);
    }

    /**
     * Tests whether two points can be connected via an obstacle-free shortcut:
     * straight line (collinear), horizontal-first L-turn, or vertical-first L-turn.
     */
    private static boolean canShortcut(AbsoluteBendpointDto a, AbsoluteBendpointDto b,
            List<RoutingRect> obstacles) {
        // Case 1: straight line (same x or same y)
        if (a.x() == b.x() || a.y() == b.y()) {
            return !segmentIntersectsAnyObstacle(a.x(), a.y(), b.x(), b.y(), obstacles);
        }
        // Case 2: L-turn horizontal-first: (ax, ay) -> (bx, ay) -> (bx, by)
        if (!segmentIntersectsAnyObstacle(a.x(), a.y(), b.x(), a.y(), obstacles)
                && !segmentIntersectsAnyObstacle(b.x(), a.y(), b.x(), b.y(), obstacles)) {
            return true;
        }
        // Case 3: L-turn vertical-first: (ax, ay) -> (ax, by) -> (bx, by)
        if (!segmentIntersectsAnyObstacle(a.x(), a.y(), a.x(), b.y(), obstacles)
                && !segmentIntersectsAnyObstacle(a.x(), b.y(), b.x(), b.y(), obstacles)) {
            return true;
        }
        return false;
    }

    // Old endpoint-based B39 methods (resolveCoincidentSegments, resolveCorridorCoincidence,
    // applyCorridorOffset, applySourceCorridorOffset, applyTargetCorridorOffset) removed.
    // Stage 4.7h now reuses CoincidentSegmentDetector.detect() + applyOffsets() which finds
    // ALL coincident corridors regardless of shared endpoints.

    private static boolean isInsideOrOnBoundary(AbsoluteBendpointDto bp, RoutingRect rect) {
        return bp.x() >= rect.x() && bp.x() <= rect.x() + rect.width()
                && bp.y() >= rect.y() && bp.y() <= rect.y() + rect.height();
    }

    /**
     * Checks if two rectangles overlap (share any interior area).
     */
    private static boolean rectsOverlap(RoutingRect a, RoutingRect b) {
        return a.x() < b.x() + b.width() && a.x() + a.width() > b.x()
                && a.y() < b.y() + b.height() && a.y() + a.height() > b.y();
    }

    /**
     * Runs A* path search. Package-visible for test overriding (empty-path fallback test).
     */
    List<VisNode> findPath(OrthogonalVisibilityGraph graph, VisNode sourcePort, VisNode targetPort,
            List<RoutingRect> groupBoundaries) {
        return findPath(graph, sourcePort, targetPort, groupBoundaries, null);
    }

    /**
     * Runs A* path search with corridor occupancy awareness (B47).
     * Package-visible for test overriding.
     */
    List<VisNode> findPath(OrthogonalVisibilityGraph graph, VisNode sourcePort, VisNode targetPort,
            List<RoutingRect> groupBoundaries, CorridorOccupancyTracker occupancyTracker) {
        VisibilityGraphRouter router = new VisibilityGraphRouter(bendPenalty, congestionWeight,
                VisibilityGraphRouter.DEFAULT_CLEARANCE_WEIGHT,
                VisibilityGraphRouter.DEFAULT_DIRECTIONALITY_WEIGHT,
                groupBoundaries);
        return router.findPath(graph, sourcePort, targetPort, occupancyTracker);
    }

    /**
     * Computes a straight-line crossing estimate by drawing imaginary direct lines between
     * source and target element centers for all connections and counting intersections (backlog-b22).
     * O(n^2) for n connections — acceptable for typical views with &lt;50 connections.
     *
     * @param sourceCenters list of [x, y] source element centers
     * @param targetCenters list of [x, y] target element centers
     * Builds the routing order for connections (B47): descending Manhattan distance,
     * tie-break by connection ID (alphabetical). Returns indices into the original list.
     * Package-visible for testing.
     *
     * @param connections the connections to order
     * @return array of original-list indices in routing order (longest first)
     */
    static Integer[] buildConnectionRoutingOrder(List<ConnectionEndpoints> connections) {
        Integer[] indices = new Integer[connections.size()];
        for (int i = 0; i < indices.length; i++) indices[i] = i;
        java.util.Arrays.sort(indices, (a, b) -> {
            ConnectionEndpoints ca = connections.get(a);
            ConnectionEndpoints cb = connections.get(b);
            int distA = Math.abs(ca.source().centerX() - ca.target().centerX())
                    + Math.abs(ca.source().centerY() - ca.target().centerY());
            int distB = Math.abs(cb.source().centerX() - cb.target().centerX())
                    + Math.abs(cb.source().centerY() - cb.target().centerY());
            int cmp = Integer.compare(distB, distA); // descending
            return cmp != 0 ? cmp : ca.connectionId().compareTo(cb.connectionId());
        });
        return indices;
    }

    /**
     * @return number of crossing pairs among the straight-line segments
     */
    static int computeStraightLineCrossings(List<int[]> sourceCenters, List<int[]> targetCenters) {
        int n = sourceCenters.size();
        int crossings = 0;
        for (int i = 0; i < n; i++) {
            int[] s1 = sourceCenters.get(i);
            int[] t1 = targetCenters.get(i);
            for (int j = i + 1; j < n; j++) {
                int[] s2 = sourceCenters.get(j);
                int[] t2 = targetCenters.get(j);
                if (segmentsIntersect(s1[0], s1[1], t1[0], t1[1],
                                      s2[0], s2[1], t2[0], t2[1])) {
                    crossings++;
                }
            }
        }
        return crossings;
    }

    /**
     * Parametric segment-segment intersection test (backlog-b22).
     * Returns true if segments (p1x,p1y)-(p2x,p2y) and (p3x,p3y)-(p4x,p4y)
     * intersect strictly (0 &lt; t &lt; 1 and 0 &lt; u &lt; 1).
     */
    static boolean segmentsIntersect(int p1x, int p1y, int p2x, int p2y,
                                      int p3x, int p3y, int p4x, int p4y) {
        long d1x = p2x - p1x;
        long d1y = p2y - p1y;
        long d2x = p4x - p3x;
        long d2y = p4y - p3y;

        long cross = d1x * d2y - d1y * d2x;
        if (cross == 0) {
            return false; // parallel or collinear
        }

        long diffX = p3x - p1x;
        long diffY = p3y - p1y;

        // t = ((p3-p1) x d2) / cross
        long tNum = diffX * d2y - diffY * d2x;
        // u = ((p3-p1) x d1) / cross
        long uNum = diffX * d1y - diffY * d1x;

        // Check 0 < t < 1 and 0 < u < 1 (strict, excluding endpoints)
        if (cross > 0) {
            return tNum > 0 && tNum < cross && uNum > 0 && uNum < cross;
        } else {
            return tNum < 0 && tNum > cross && uNum < 0 && uNum > cross;
        }
    }

    /**
     * Builds a crossing inflation warning string if routed crossings exceed the threshold
     * relative to the straight-line estimate (backlog-b22).
     *
     * @param crossingsAfter          actual crossing count after routing
     * @param straightLineCrossings   straight-line crossing estimate
     * @return warning string, or null if no warning needed
     */
    public static String buildCrossingInflationWarning(int crossingsAfter, int straightLineCrossings) {
        if (straightLineCrossings <= 0) {
            return null;
        }
        if (crossingsAfter > straightLineCrossings * CROSSING_INFLATION_THRESHOLD) {
            double ratio = (double) crossingsAfter / straightLineCrossings;
            return String.format(
                    "Routing produced %d crossings vs %d straight-line estimate (%.1fx inflation). "
                    + "Layout may be too dense for clean orthogonal routing. "
                    + "Consider increasing element spacing and re-routing.",
                    crossingsAfter, straightLineCrossings, ratio);
        }
        return null;
    }

    /**
     * Enforces minimum clearance between intermediate bendpoints and obstacle boundaries (backlog-b22).
     * Skips terminal BPs (first and last). For each connection, the source and target elements
     * are excluded from obstacle checking (only third-party obstacles are checked).
     *
     * <p>B25 fix: orthogonality-preserving nudging. After nudging a BP, any perpendicular
     * coordinate change is propagated to the adjacent BP that shared the same coordinate,
     * preserving the orthogonal segment connection. Nudging along a segment axis (sliding)
     * is always safe. Nudging perpendicular requires propagation to maintain orthogonality.</p>
     *
     * @param path       mutable list of bendpoints for one connection
     * @param obstacles  all obstacle rectangles on the view
     * @param source     source element rectangle (excluded from checks for this connection)
     * @param target     target element rectangle (excluded from checks for this connection)
     * @return number of bendpoints that were nudged
     */
    static int enforceMinClearance(List<AbsoluteBendpointDto> path,
                                    List<RoutingRect> obstacles,
                                    RoutingRect source, RoutingRect target) {
        if (path.size() < 3) {
            return 0; // need at least 3 BPs to have intermediate ones
        }

        int nudgedCount = 0;
        // Skip first (index 0) and last (index size-1) — terminal BPs
        for (int i = 1; i < path.size() - 1; i++) {
            AbsoluteBendpointDto bp = path.get(i);
            AbsoluteBendpointDto prev = path.get(i - 1);
            AbsoluteBendpointDto next = path.get(i + 1);
            int origX = bp.x();
            int origY = bp.y();
            int bpx = origX;
            int bpy = origY;
            boolean nudged = false;

            // Determine segment context for propagation decisions
            boolean sameYPrev = (bp.y() == prev.y());
            boolean sameXPrev = (bp.x() == prev.x());
            boolean sameYNext = (bp.y() == next.y());
            boolean sameXNext = (bp.x() == next.x());

            for (RoutingRect obs : obstacles) {
                // Skip source and target elements for this connection
                if (obs.id() != null && (obs.id().equals(source.id()) || obs.id().equals(target.id()))) {
                    continue;
                }

                int obsLeft = obs.x();
                int obsRight = obs.x() + obs.width();
                int obsTop = obs.y();
                int obsBottom = obs.y() + obs.height();

                // Check if BP is horizontally within obstacle column
                boolean inHorizontalBand = bpx >= obsLeft && bpx <= obsRight;
                // Check if BP is vertically within obstacle row
                boolean inVerticalBand = bpy >= obsTop && bpy <= obsBottom;
                // BP fully inside obstacle — must be nudged out regardless of edge distance
                boolean insideObstacle = inHorizontalBand && inVerticalBand;

                int newBpx = bpx;
                int newBpy = bpy;

                if (inHorizontalBand) {
                    int distToTop = Math.abs(bpy - obsTop);
                    int distToBottom = Math.abs(bpy - obsBottom);
                    int dyNear = Math.min(distToTop, distToBottom);
                    if (dyNear < MIN_CLEARANCE || insideObstacle) {
                        if (distToTop <= distToBottom) {
                            newBpy = obsTop - MIN_CLEARANCE;
                        } else {
                            newBpy = obsBottom + MIN_CLEARANCE;
                        }
                    }
                }

                if (inVerticalBand) {
                    int distToLeft = Math.abs(bpx - obsLeft);
                    int distToRight = Math.abs(bpx - obsRight);
                    int dxNear = Math.min(distToLeft, distToRight);
                    if (dxNear < MIN_CLEARANCE || insideObstacle) {
                        if (distToLeft <= distToRight) {
                            newBpx = obsLeft - MIN_CLEARANCE;
                        } else {
                            newBpx = obsRight + MIN_CLEARANCE;
                        }
                    }
                }

                if (newBpx != bpx || newBpy != bpy) {
                    // Verify nudged position doesn't intersect any obstacle
                    if (!pointInsideAnyObstacle(newBpx, newBpy, obstacles, source, target)) {
                        path.set(i, new AbsoluteBendpointDto(newBpx, newBpy));
                        bpx = newBpx;
                        bpy = newBpy;
                        nudged = true;
                        logger.debug("Clearance enforcement: nudged BP from ({},{}) to ({},{}) — obstacle {} too close",
                                origX, origY, newBpx, newBpy, obs.id());
                    } else {
                        logger.debug("Clearance enforcement: BP ({},{}) near obstacle {} — nudge to ({},{}) "
                                + "would create new violation, leaving unchanged",
                                bpx, bpy, obs.id(), newBpx, newBpy);
                    }
                }
            }

            // Post-loop verification: confirm final position satisfies clearance from ALL obstacles.
            if (nudged && violatesClearance(bpx, bpy, obstacles, source, target)) {
                path.set(i, bp); // revert to original position
                nudged = false;
                logger.debug("Clearance enforcement: reverted BP ({},{}) to original ({},{}) — "
                        + "multi-obstacle nudge left residual clearance violation",
                        bpx, bpy, origX, origY);
            }

            // B25: Propagate perpendicular coordinate changes to adjacent BPs to maintain
            // orthogonality. Nudging along a segment axis (e.g., X on horizontal) is just
            // sliding and doesn't break orthogonality. Nudging perpendicular (e.g., Y on
            // horizontal) would create a diagonal — propagate to prevent this.
            if (nudged) {
                nudgedCount++;
                int finalX = path.get(i).x();
                int finalY = path.get(i).y();

                // If Y changed, propagate to the neighbor that shared our original Y
                if (finalY != origY) {
                    if (sameYPrev && i - 1 > 0) {
                        AbsoluteBendpointDto p = path.get(i - 1);
                        path.set(i - 1, new AbsoluteBendpointDto(p.x(), finalY));
                    } else if (sameYNext && i + 1 < path.size() - 1) {
                        AbsoluteBendpointDto n = path.get(i + 1);
                        path.set(i + 1, new AbsoluteBendpointDto(n.x(), finalY));
                    }
                }
                // If X changed, propagate to the neighbor that shared our original X
                if (finalX != origX) {
                    if (sameXPrev && i - 1 > 0) {
                        AbsoluteBendpointDto p = path.get(i - 1);
                        path.set(i - 1, new AbsoluteBendpointDto(finalX, p.y()));
                    } else if (sameXNext && i + 1 < path.size() - 1) {
                        AbsoluteBendpointDto n = path.get(i + 1);
                        path.set(i + 1, new AbsoluteBendpointDto(finalX, n.y()));
                    }
                }
            }
        }
        return nudgedCount;
    }

    /**
     * Segment-based clearance enforcement (backlog-b26).
     * Complements point-based {@link #enforceMinClearance} by checking entire intermediate
     * segments (pairs of consecutive BPs) against obstacle boundaries. A vertical segment
     * running 3px from an obstacle's left edge will be shifted outward even if neither
     * endpoint falls inside the obstacle's bounding band.
     *
     * <p>Terminal segments (first and last in path) are excluded — terminal BPs are placed
     * at 1px from element faces by {@link EdgeAttachmentCalculator} for ChopboxAnchor
     * compatibility.</p>
     *
     * @param path       mutable list of bendpoints for one connection
     * @param obstacles  all obstacle rectangles on the view
     * @param source     source element rectangle (excluded from checks for this connection)
     * @param target     target element rectangle (excluded from checks for this connection)
     * @return number of segments that were shifted
     */
    static int enforceSegmentClearance(List<AbsoluteBendpointDto> path,
                                        List<RoutingRect> obstacles,
                                        RoutingRect source, RoutingRect target) {
        if (path.size() < 4) {
            // Need at least 4 BPs to have an intermediate segment (skip 0->1 and (n-2)->(n-1))
            return 0;
        }

        int shiftedCount = 0;
        // Intermediate segments: from index 1 to index (size-3) inclusive as start of segment
        // Segment i -> i+1, where i >= 1 and i+1 <= size-2 (both non-terminal)
        for (int i = 1; i < path.size() - 2; i++) {
            AbsoluteBendpointDto bp1 = path.get(i);
            AbsoluteBendpointDto bp2 = path.get(i + 1);

            boolean isVertical = (bp1.x() == bp2.x());
            boolean isHorizontal = (bp1.y() == bp2.y());

            // Skip diagonal/non-orthogonal segments
            if (!isVertical && !isHorizontal) {
                continue;
            }

            int bestShift = 0;
            int shiftAxis = 0; // 0=none, 1=X (vertical seg), 2=Y (horizontal seg)

            if (isVertical) {
                int segX = bp1.x();
                int segMinY = Math.min(bp1.y(), bp2.y());
                int segMaxY = Math.max(bp1.y(), bp2.y());

                for (RoutingRect obs : obstacles) {
                    if (obs.id() != null && (obs.id().equals(source.id()) || obs.id().equals(target.id()))) {
                        continue;
                    }
                    int obsLeft = obs.x();
                    int obsRight = obs.x() + obs.width();
                    int obsTop = obs.y();
                    int obsBottom = obs.y() + obs.height();

                    // Check Y extent overlap
                    if (segMaxY < obsTop || segMinY > obsBottom) {
                        continue; // no Y overlap
                    }

                    // Compute perpendicular distance from segment to obstacle
                    int grazeDist;
                    int shift;
                    if (segX < obsLeft) {
                        grazeDist = obsLeft - segX;
                        shift = -(MIN_CLEARANCE - grazeDist); // shift left (more negative X)
                    } else if (segX > obsRight) {
                        grazeDist = segX - obsRight;
                        shift = (MIN_CLEARANCE - grazeDist); // shift right (more positive X)
                    } else {
                        continue; // segment inside obstacle column — handled by point-based/violation checks
                    }

                    if (grazeDist < MIN_CLEARANCE) {
                        // Pick the largest needed shift (closest obstacle wins)
                        if (Math.abs(shift) > Math.abs(bestShift)) {
                            bestShift = shift;
                            shiftAxis = 1;
                        }
                    }
                }
            } else { // isHorizontal
                int segY = bp1.y();
                int segMinX = Math.min(bp1.x(), bp2.x());
                int segMaxX = Math.max(bp1.x(), bp2.x());

                for (RoutingRect obs : obstacles) {
                    if (obs.id() != null && (obs.id().equals(source.id()) || obs.id().equals(target.id()))) {
                        continue;
                    }
                    int obsLeft = obs.x();
                    int obsRight = obs.x() + obs.width();
                    int obsTop = obs.y();
                    int obsBottom = obs.y() + obs.height();

                    // Check X extent overlap
                    if (segMaxX < obsLeft || segMinX > obsRight) {
                        continue; // no X overlap
                    }

                    // Compute perpendicular distance from segment to obstacle
                    int grazeDist;
                    int shift;
                    if (segY < obsTop) {
                        grazeDist = obsTop - segY;
                        shift = -(MIN_CLEARANCE - grazeDist); // shift up (more negative Y)
                    } else if (segY > obsBottom) {
                        grazeDist = segY - obsBottom;
                        shift = (MIN_CLEARANCE - grazeDist); // shift down (more positive Y)
                    } else {
                        continue; // segment inside obstacle row — handled by point-based/violation checks
                    }

                    if (grazeDist < MIN_CLEARANCE) {
                        if (Math.abs(shift) > Math.abs(bestShift)) {
                            bestShift = shift;
                            shiftAxis = 2;
                        }
                    }
                }
            }

            if (bestShift == 0) {
                continue; // no shift needed for this segment
            }

            // Compute shifted positions
            int newBp1x = bp1.x() + (shiftAxis == 1 ? bestShift : 0);
            int newBp1y = bp1.y() + (shiftAxis == 2 ? bestShift : 0);
            int newBp2x = bp2.x() + (shiftAxis == 1 ? bestShift : 0);
            int newBp2y = bp2.y() + (shiftAxis == 2 ? bestShift : 0);

            // Validate shifted positions don't create new obstacle violations
            if (pointInsideAnyObstacle(newBp1x, newBp1y, obstacles, source, target)
                    || pointInsideAnyObstacle(newBp2x, newBp2y, obstacles, source, target)) {
                logger.debug("Segment clearance: segment ({},{})-({},{}) shift by {} on axis {} "
                        + "would create obstacle violation, leaving unchanged",
                        bp1.x(), bp1.y(), bp2.x(), bp2.y(), bestShift, shiftAxis == 1 ? "X" : "Y");
                continue;
            }

            // Verify shifted positions still satisfy clearance from all obstacles
            if (violatesClearance(newBp1x, newBp1y, obstacles, source, target)
                    || violatesClearance(newBp2x, newBp2y, obstacles, source, target)) {
                logger.debug("Segment clearance: segment ({},{})-({},{}) shift by {} on axis {} "
                        + "would create new clearance violation, leaving unchanged",
                        bp1.x(), bp1.y(), bp2.x(), bp2.y(), bestShift, shiftAxis == 1 ? "X" : "Y");
                continue;
            }

            // Apply shift
            path.set(i, new AbsoluteBendpointDto(newBp1x, newBp1y));
            path.set(i + 1, new AbsoluteBendpointDto(newBp2x, newBp2y));
            shiftedCount++;
            logger.debug("Segment clearance: shifted segment ({},{})-({},{}) by {} on {} axis",
                    bp1.x(), bp1.y(), bp2.x(), bp2.y(), bestShift, shiftAxis == 1 ? "X" : "Y");

            // Propagate orthogonality to adjacent segments (B25 pattern)
            // Segment endpoints at i and i+1 were shifted. Propagate to neighbors.
            if (shiftAxis == 1) {
                // X shift on vertical segment — propagate X to connected segments
                // BP at i: if BP i-1 shared original X and is non-terminal, propagate
                if (i > 1 && path.get(i - 1).x() == bp1.x()) {
                    AbsoluteBendpointDto prev = path.get(i - 1);
                    path.set(i - 1, new AbsoluteBendpointDto(newBp1x, prev.y()));
                }
                // BP at i+1: if BP i+2 shared original X and is non-terminal, propagate
                if (i + 2 < path.size() - 1 && path.get(i + 2).x() == bp2.x()) {
                    AbsoluteBendpointDto next = path.get(i + 2);
                    path.set(i + 2, new AbsoluteBendpointDto(newBp2x, next.y()));
                }
            } else {
                // Y shift on horizontal segment — propagate Y to connected segments
                if (i > 1 && path.get(i - 1).y() == bp1.y()) {
                    AbsoluteBendpointDto prev = path.get(i - 1);
                    path.set(i - 1, new AbsoluteBendpointDto(prev.x(), newBp1y));
                }
                if (i + 2 < path.size() - 1 && path.get(i + 2).y() == bp2.y()) {
                    AbsoluteBendpointDto next = path.get(i + 2);
                    path.set(i + 2, new AbsoluteBendpointDto(next.x(), newBp2y));
                }
            }
        }
        return shiftedCount;
    }

    /**
     * Enforces minimum clearance for terminal-only paths (2-BP and 3-BP) where the terminal-to-terminal
     * segment or terminal-adjacent segments graze unrelated obstacles. These paths are not handled by
     * enforceMinClearance (skips terminal BPs) or enforceSegmentClearance (requires 4+ BPs for
     * intermediate segments). Inserts intermediate bendpoints to create a rectangular detour around
     * grazed obstacles (backlog-b27).
     *
     * @param path      mutable bendpoint list (2 or 3 BPs)
     * @param obstacles all obstacles on the view
     * @param source    source element rect (excluded from obstacle checks)
     * @param target    target element rect (excluded from obstacle checks)
     * @return number of paths modified (0 or 1)
     */
    static int enforceTerminalCorridorClearance(List<AbsoluteBendpointDto> path,
                                                 List<RoutingRect> obstacles,
                                                 RoutingRect source, RoutingRect target) {
        if (path.size() != 2 && path.size() != 3) {
            return 0;
        }
        // Pre-compute filtered obstacle list once (excludes source/target)
        List<RoutingRect> nonEndpointObstacles = filterExcludingEndpoints(obstacles, source, target);
        if (path.size() == 2) {
            return handleTwoBpTerminalCorridor(path, obstacles, nonEndpointObstacles, source, target);
        } else {
            return handleThreeBpTerminalCorridor(path, obstacles, nonEndpointObstacles, source, target);
        }
    }

    /**
     * Handles 2-BP terminal-only paths by inserting a rectangular detour when the single
     * terminal-to-terminal segment grazes an obstacle.
     */
    private static int handleTwoBpTerminalCorridor(List<AbsoluteBendpointDto> path,
                                                    List<RoutingRect> obstacles,
                                                    List<RoutingRect> nonEndpointObstacles,
                                                    RoutingRect source, RoutingRect target) {
        AbsoluteBendpointDto bp0 = path.get(0);
        AbsoluteBendpointDto bp1 = path.get(1);

        boolean isVertical = (bp0.x() == bp1.x());
        boolean isHorizontal = (bp0.y() == bp1.y());

        if (!isVertical && !isHorizontal) {
            return 0; // diagonal — not our concern
        }

        // Find grazing obstacles on each side of the segment.
        // nearestPositiveEdge: closest obstacle edge in positive direction (right for vertical, below for horizontal)
        // nearestNegativeEdge: closest obstacle edge in negative direction (left for vertical, above for horizontal)
        int nearestPositiveEdge = Integer.MAX_VALUE;
        int nearestNegativeEdge = Integer.MIN_VALUE;
        boolean grazesPositiveSide = false;
        boolean grazesNegativeSide = false;

        if (isVertical) {
            int segX = bp0.x();
            int segMinY = Math.min(bp0.y(), bp1.y());
            int segMaxY = Math.max(bp0.y(), bp1.y());

            for (RoutingRect obs : obstacles) {
                if (obs.id() != null && (obs.id().equals(source.id()) || obs.id().equals(target.id()))) {
                    continue;
                }
                int obsLeft = obs.x();
                int obsRight = obs.x() + obs.width();
                int obsTop = obs.y();
                int obsBottom = obs.y() + obs.height();

                // Check Y extent overlap
                if (segMaxY < obsTop || segMinY > obsBottom) {
                    continue;
                }

                if (segX <= obsLeft) {
                    int grazeDist = obsLeft - segX;
                    if (grazeDist < MIN_CLEARANCE) {
                        grazesPositiveSide = true;
                        nearestPositiveEdge = Math.min(nearestPositiveEdge, obsLeft);
                    }
                } else if (segX >= obsRight) {
                    int grazeDist = segX - obsRight;
                    if (grazeDist < MIN_CLEARANCE) {
                        grazesNegativeSide = true;
                        nearestNegativeEdge = Math.max(nearestNegativeEdge, obsRight);
                    }
                }
            }

            if (!grazesPositiveSide && !grazesNegativeSide) {
                return 0;
            }

            // If grazed from both sides, can't detour — sandwiched
            if (grazesPositiveSide && grazesNegativeSide) {
                logger.debug("Terminal corridor: 2-BP vertical path at x={} sandwiched between obstacles, "
                        + "cannot insert detour", segX);
                return 0;
            }

            int detourX;
            if (grazesPositiveSide) {
                detourX = nearestPositiveEdge - MIN_CLEARANCE; // detour left, away from obstacle on right
            } else {
                detourX = nearestNegativeEdge + MIN_CLEARANCE; // detour right, away from obstacle on left
            }

            return insertVerticalDetour(path, bp0, bp1, detourX, obstacles, nonEndpointObstacles, source, target);
        } else {
            // Horizontal segment
            int segY = bp0.y();
            int segMinX = Math.min(bp0.x(), bp1.x());
            int segMaxX = Math.max(bp0.x(), bp1.x());

            for (RoutingRect obs : obstacles) {
                if (obs.id() != null && (obs.id().equals(source.id()) || obs.id().equals(target.id()))) {
                    continue;
                }
                int obsLeft = obs.x();
                int obsRight = obs.x() + obs.width();
                int obsTop = obs.y();
                int obsBottom = obs.y() + obs.height();

                // Check X extent overlap
                if (segMaxX < obsLeft || segMinX > obsRight) {
                    continue;
                }

                if (segY <= obsTop) {
                    int grazeDist = obsTop - segY;
                    if (grazeDist < MIN_CLEARANCE) {
                        grazesPositiveSide = true;
                        nearestPositiveEdge = Math.min(nearestPositiveEdge, obsTop);
                    }
                } else if (segY >= obsBottom) {
                    int grazeDist = segY - obsBottom;
                    if (grazeDist < MIN_CLEARANCE) {
                        grazesNegativeSide = true;
                        nearestNegativeEdge = Math.max(nearestNegativeEdge, obsBottom);
                    }
                }
            }

            if (!grazesPositiveSide && !grazesNegativeSide) {
                return 0;
            }

            if (grazesPositiveSide && grazesNegativeSide) {
                logger.debug("Terminal corridor: 2-BP horizontal path at y={} sandwiched between obstacles, "
                        + "cannot insert detour", segY);
                return 0;
            }

            int detourY;
            if (grazesPositiveSide) {
                detourY = nearestPositiveEdge - MIN_CLEARANCE; // detour up, away from obstacle below
            } else {
                detourY = nearestNegativeEdge + MIN_CLEARANCE; // detour down, away from obstacle above
            }

            return insertHorizontalDetour(path, bp0, bp1, detourY, obstacles, nonEndpointObstacles, source, target);
        }
    }

    /**
     * Inserts a vertical detour for a 2-BP path. The original path is vertical (same X),
     * and the detour jogs to detourX and back.
     * Path: [T0(segX,y0)] → [T0(segX,y0), I0(detourX,y0), I1(detourX,y1), T1(segX,y1)]
     */
    private static int insertVerticalDetour(List<AbsoluteBendpointDto> path,
                                             AbsoluteBendpointDto bp0, AbsoluteBendpointDto bp1,
                                             int detourX, List<RoutingRect> obstacles,
                                             List<RoutingRect> nonEndpointObstacles,
                                             RoutingRect source, RoutingRect target) {
        AbsoluteBendpointDto i0 = new AbsoluteBendpointDto(detourX, bp0.y());
        AbsoluteBendpointDto i1 = new AbsoluteBendpointDto(detourX, bp1.y());

        // Validate new intermediate BPs don't create violations
        if (pointInsideAnyObstacle(i0.x(), i0.y(), obstacles, source, target)
                || pointInsideAnyObstacle(i1.x(), i1.y(), obstacles, source, target)) {
            logger.debug("Terminal corridor: vertical detour to x={} would place BPs inside obstacle, "
                    + "leaving 2-BP path unchanged", detourX);
            return 0;
        }

        // Validate new segments don't intersect obstacles (excluding source/target)
        if (segmentIntersectsAnyObstacle(bp0.x(), bp0.y(), i0.x(), i0.y(), nonEndpointObstacles)
                || segmentIntersectsAnyObstacle(i0.x(), i0.y(), i1.x(), i1.y(), nonEndpointObstacles)
                || segmentIntersectsAnyObstacle(i1.x(), i1.y(), bp1.x(), bp1.y(), nonEndpointObstacles)) {
            logger.debug("Terminal corridor: vertical detour to x={} would intersect obstacle, "
                    + "leaving 2-BP path unchanged", detourX);
            return 0;
        }

        // Insert detour BPs
        path.add(1, i0);
        path.add(2, i1);
        logger.debug("Terminal corridor: inserted vertical detour at x={} for 2-BP path "
                + "({},{})->({},{})", detourX, bp0.x(), bp0.y(), bp1.x(), bp1.y());
        return 1;
    }

    /**
     * Inserts a horizontal detour for a 2-BP path. The original path is horizontal (same Y),
     * and the detour jogs to detourY and back.
     * Path: [T0(x0,segY)] → [T0(x0,segY), I0(x0,detourY), I1(x1,detourY), T1(x1,segY)]
     */
    private static int insertHorizontalDetour(List<AbsoluteBendpointDto> path,
                                               AbsoluteBendpointDto bp0, AbsoluteBendpointDto bp1,
                                               int detourY, List<RoutingRect> obstacles,
                                               List<RoutingRect> nonEndpointObstacles,
                                               RoutingRect source, RoutingRect target) {
        AbsoluteBendpointDto i0 = new AbsoluteBendpointDto(bp0.x(), detourY);
        AbsoluteBendpointDto i1 = new AbsoluteBendpointDto(bp1.x(), detourY);

        // Validate new intermediate BPs don't create violations
        if (pointInsideAnyObstacle(i0.x(), i0.y(), obstacles, source, target)
                || pointInsideAnyObstacle(i1.x(), i1.y(), obstacles, source, target)) {
            logger.debug("Terminal corridor: horizontal detour to y={} would place BPs inside obstacle, "
                    + "leaving 2-BP path unchanged", detourY);
            return 0;
        }

        // Validate new segments don't intersect obstacles (excluding source/target)
        if (segmentIntersectsAnyObstacle(bp0.x(), bp0.y(), i0.x(), i0.y(), nonEndpointObstacles)
                || segmentIntersectsAnyObstacle(i0.x(), i0.y(), i1.x(), i1.y(), nonEndpointObstacles)
                || segmentIntersectsAnyObstacle(i1.x(), i1.y(), bp1.x(), bp1.y(), nonEndpointObstacles)) {
            logger.debug("Terminal corridor: horizontal detour to y={} would intersect obstacle, "
                    + "leaving 2-BP path unchanged", detourY);
            return 0;
        }

        // Insert detour BPs
        path.add(1, i0);
        path.add(2, i1);
        logger.debug("Terminal corridor: inserted horizontal detour at y={} for 2-BP path "
                + "({},{})->({},{})", detourY, bp0.x(), bp0.y(), bp1.x(), bp1.y());
        return 1;
    }

    /**
     * Handles 3-BP paths by checking terminal-adjacent segments for grazing.
     * Prefers shifting the intermediate BP over inserting new BPs.
     */
    private static int handleThreeBpTerminalCorridor(List<AbsoluteBendpointDto> path,
                                                      List<RoutingRect> obstacles,
                                                      List<RoutingRect> nonEndpointObstacles,
                                                      RoutingRect source, RoutingRect target) {
        // 3-BP path: [T0, I0, T1]
        // Terminal-adjacent segments: T0→I0 and I0→T1
        // Try shifting I0 to resolve grazing on either segment
        AbsoluteBendpointDto t0 = path.get(0);
        AbsoluteBendpointDto intermediate = path.get(1);
        AbsoluteBendpointDto t1 = path.get(2);

        int bestShift = 0;
        int shiftAxis = 0; // 0=none, 1=X, 2=Y

        // Check segment T0→I0
        int segShift = computeTerminalAdjacentShift(t0, intermediate, obstacles, source, target);
        if (segShift != 0) {
            boolean seg0Vertical = (t0.x() == intermediate.x());
            if (seg0Vertical) {
                shiftAxis = 1;
            } else {
                shiftAxis = 2;
            }
            bestShift = segShift;
        }

        // Check segment I0→T1
        int seg1Shift = computeTerminalAdjacentShift(intermediate, t1, obstacles, source, target);
        if (seg1Shift != 0) {
            boolean seg1Vertical = (intermediate.x() == t1.x());
            int axis1 = seg1Vertical ? 1 : 2;

            // If both segments need shifts on the same axis, use the larger shift
            if (shiftAxis == axis1 || shiftAxis == 0) {
                if (Math.abs(seg1Shift) > Math.abs(bestShift)) {
                    bestShift = seg1Shift;
                    shiftAxis = axis1;
                }
            }
            // If different axes, a single BP shift can only resolve one — keep the first and log
            if (shiftAxis != 0 && axis1 != shiftAxis) {
                logger.debug("Terminal corridor: 3-BP path has cross-axis grazing (axis {} and {}), "
                        + "only fixing axis {} shift={}", shiftAxis, axis1, shiftAxis, bestShift);
            }
        }

        if (bestShift == 0) {
            return 0;
        }

        // Compute shifted intermediate position
        int newX = intermediate.x() + (shiftAxis == 1 ? bestShift : 0);
        int newY = intermediate.y() + (shiftAxis == 2 ? bestShift : 0);

        // Validate shifted position doesn't create violations
        if (pointInsideAnyObstacle(newX, newY, obstacles, source, target)) {
            logger.debug("Terminal corridor: 3-BP intermediate shift to ({},{}) would be inside obstacle, "
                    + "leaving path unchanged", newX, newY);
            return 0;
        }

        // Validate new segments don't intersect obstacles
        if (segmentIntersectsAnyObstacle(t0.x(), t0.y(), newX, newY, nonEndpointObstacles)
                || segmentIntersectsAnyObstacle(newX, newY, t1.x(), t1.y(), nonEndpointObstacles)) {
            logger.debug("Terminal corridor: 3-BP intermediate shift to ({},{}) would intersect obstacle, "
                    + "leaving path unchanged", newX, newY);
            return 0;
        }

        path.set(1, new AbsoluteBendpointDto(newX, newY));
        logger.debug("Terminal corridor: shifted 3-BP intermediate from ({},{}) to ({},{}) "
                + "for clearance", intermediate.x(), intermediate.y(), newX, newY);
        return 1;
    }

    /**
     * Computes the shift needed for a terminal-adjacent segment to maintain MIN_CLEARANCE
     * from all obstacles. Returns 0 if no shift needed.
     */
    private static int computeTerminalAdjacentShift(AbsoluteBendpointDto a, AbsoluteBendpointDto b,
                                                     List<RoutingRect> obstacles,
                                                     RoutingRect source, RoutingRect target) {
        boolean isVertical = (a.x() == b.x());
        boolean isHorizontal = (a.y() == b.y());
        if (!isVertical && !isHorizontal) {
            return 0;
        }

        int bestShift = 0;

        if (isVertical) {
            int segX = a.x();
            int segMinY = Math.min(a.y(), b.y());
            int segMaxY = Math.max(a.y(), b.y());

            for (RoutingRect obs : obstacles) {
                if (obs.id() != null && (obs.id().equals(source.id()) || obs.id().equals(target.id()))) {
                    continue;
                }
                int obsLeft = obs.x();
                int obsRight = obs.x() + obs.width();
                int obsTop = obs.y();
                int obsBottom = obs.y() + obs.height();

                if (segMaxY < obsTop || segMinY > obsBottom) {
                    continue;
                }

                if (segX <= obsLeft) {
                    int grazeDist = obsLeft - segX;
                    if (grazeDist < MIN_CLEARANCE) {
                        int shift = -(MIN_CLEARANCE - grazeDist);
                        if (Math.abs(shift) > Math.abs(bestShift)) {
                            bestShift = shift;
                        }
                    }
                } else if (segX >= obsRight) {
                    int grazeDist = segX - obsRight;
                    if (grazeDist < MIN_CLEARANCE) {
                        int shift = (MIN_CLEARANCE - grazeDist);
                        if (Math.abs(shift) > Math.abs(bestShift)) {
                            bestShift = shift;
                        }
                    }
                }
            }
        } else {
            int segY = a.y();
            int segMinX = Math.min(a.x(), b.x());
            int segMaxX = Math.max(a.x(), b.x());

            for (RoutingRect obs : obstacles) {
                if (obs.id() != null && (obs.id().equals(source.id()) || obs.id().equals(target.id()))) {
                    continue;
                }
                int obsLeft = obs.x();
                int obsRight = obs.x() + obs.width();
                int obsTop = obs.y();
                int obsBottom = obs.y() + obs.height();

                if (segMaxX < obsLeft || segMinX > obsRight) {
                    continue;
                }

                if (segY <= obsTop) {
                    int grazeDist = obsTop - segY;
                    if (grazeDist < MIN_CLEARANCE) {
                        int shift = -(MIN_CLEARANCE - grazeDist);
                        if (Math.abs(shift) > Math.abs(bestShift)) {
                            bestShift = shift;
                        }
                    }
                } else if (segY >= obsBottom) {
                    int grazeDist = segY - obsBottom;
                    if (grazeDist < MIN_CLEARANCE) {
                        int shift = (MIN_CLEARANCE - grazeDist);
                        if (Math.abs(shift) > Math.abs(bestShift)) {
                            bestShift = shift;
                        }
                    }
                }
            }
        }

        return bestShift;
    }

    /**
     * Filters an obstacle list to exclude source and target elements.
     * Used for segment intersection checks where we don't want to detect intersection
     * with the connection's own endpoints.
     */
    private static List<RoutingRect> filterExcludingEndpoints(List<RoutingRect> obstacles,
                                                               RoutingRect source, RoutingRect target) {
        List<RoutingRect> filtered = new ArrayList<>();
        for (RoutingRect obs : obstacles) {
            if (obs.id() != null && (obs.id().equals(source.id()) || obs.id().equals(target.id()))) {
                continue;
            }
            filtered.add(obs);
        }
        return filtered;
    }

    /**
     * Checks if a point violates MIN_CLEARANCE from any obstacle (excluding source/target).
     * Used as a post-loop verification after multi-obstacle nudging.
     */
    private static boolean violatesClearance(int x, int y, List<RoutingRect> obstacles,
                                              RoutingRect source, RoutingRect target) {
        for (RoutingRect obs : obstacles) {
            if (obs.id() != null && (obs.id().equals(source.id()) || obs.id().equals(target.id()))) {
                continue;
            }
            int obsLeft = obs.x();
            int obsRight = obs.x() + obs.width();
            int obsTop = obs.y();
            int obsBottom = obs.y() + obs.height();

            boolean inH = x >= obsLeft && x <= obsRight;
            boolean inV = y >= obsTop && y <= obsBottom;

            // Fully inside obstacle
            if (inH && inV) {
                return true;
            }
            if (inH) {
                int dyNear = Math.min(Math.abs(y - obsTop), Math.abs(y - obsBottom));
                if (dyNear < MIN_CLEARANCE) {
                    return true;
                }
            }
            if (inV) {
                int dxNear = Math.min(Math.abs(x - obsLeft), Math.abs(x - obsRight));
                if (dxNear < MIN_CLEARANCE) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks if a point is inside any obstacle rectangle (excluding source/target for own connection).
     * Uses inclusive boundary checks (>=, <=) — a point exactly on the obstacle edge is considered inside.
     * This is intentional: enforceMinClearance nudges to MIN_CLEARANCE pixels away from edges,
     * so valid nudge targets will never land on a boundary.
     */
    private static boolean pointInsideAnyObstacle(int x, int y, List<RoutingRect> obstacles,
                                                   RoutingRect source, RoutingRect target) {
        for (RoutingRect obs : obstacles) {
            if (obs.id() != null && (obs.id().equals(source.id()) || obs.id().equals(target.id()))) {
                continue;
            }
            if (x >= obs.x() && x <= obs.x() + obs.width()
                    && y >= obs.y() && y <= obs.y() + obs.height()) {
                return true;
            }
        }
        return false;
    }
}
