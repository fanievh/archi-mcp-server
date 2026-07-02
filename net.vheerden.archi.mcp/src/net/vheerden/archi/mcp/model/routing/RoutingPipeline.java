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
 * Routing pipeline orchestrator for obstacle-aware orthogonal connection routing.
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

    /** Default snap-to-straight threshold in pixels. */
    public static final int DEFAULT_SNAP_THRESHOLD = 20;

    /** Default congestion weight for production routing. */
    public static final double DEFAULT_CONGESTION_WEIGHT = 5.0;

    /** Minimum clearance in pixels between intermediate bendpoints and obstacle boundaries. */
    static final int MIN_CLEARANCE = 8;

    /** Crossing inflation threshold: warn if routed crossings exceed this ratio of straight-line estimate. */
    public static final double CROSSING_INFLATION_THRESHOLD = 1.5;

    /** Default exterior perimeter margin in pixels. */
    public static final int DEFAULT_PERIMETER_MARGIN = 50;

    /** Default for the channel-global ordered nudging post-pass (Stage 4.7o). */
    public static final boolean DEFAULT_ENABLE_CHANNEL_NUDGING = true;

    private final int bendPenalty;
    private final int margin;
    private final int perimeterMargin;
    private final double congestionWeight;
    private final double occupancyWeight;
    private final PathOrderer pathOrderer;
    private final EdgeNudger edgeNudger;
    private final EdgeAttachmentCalculator edgeAttachmentCalculator;
    private final CoincidentSegmentDetector coincidentDetector;
    private final LabelPositionOptimizer labelPositionOptimizer;
    /** H5 hub-perimeter routing stage (Axis 1 corridor-CHOICE + Axis 2 SPREAD). */
    private final HubPerimeterRoutingStage hubPerimeterRoutingStage = new HubPerimeterRoutingStage();
    /**
     * Corridor-aware terminal-egress clearance. Runs as the LAST geometry-mutating stage
     * (4.7s, after 4.7r) so its M4/V_p10/HPQ/Tier-1 validation is a final-pipeline-state check.
     */
    private final TerminalEgressClearancePass terminalEgressClearancePass = new TerminalEgressClearancePass();

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
        this(bendPenalty, margin, congestionWeight, perimeterMargin,
                VisibilityGraphRouter.DEFAULT_OCCUPANCY_WEIGHT);
    }

    /**
     * Creates a pipeline with all routing parameters including occupancy weight.
     *
     * @param bendPenalty      penalty for direction changes in A* search
     * @param margin           clearance margin around obstacles
     * @param congestionWeight multiplier for local obstacle density
     * @param perimeterMargin  exterior perimeter margin
     * @param occupancyWeight  multiplicative penalty for occupied corridors
     */
    public RoutingPipeline(int bendPenalty, int margin, double congestionWeight, int perimeterMargin,
            double occupancyWeight) {
        if (perimeterMargin < 0) {
            throw new IllegalArgumentException("perimeterMargin must be >= 0, got " + perimeterMargin);
        }
        if (occupancyWeight < 0) {
            throw new IllegalArgumentException("occupancyWeight must be >= 0, got " + occupancyWeight);
        }
        this.bendPenalty = bendPenalty;
        this.margin = margin;
        this.perimeterMargin = perimeterMargin;
        this.congestionWeight = congestionWeight;
        this.occupancyWeight = occupancyWeight;
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
     * if the path passes through source or target element bodies.
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
     * Route a single connection around obstacles with group-wall clearance awareness.
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
     * Route a single connection with corridor occupancy awareness.
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
        // Pass occupancy tracker for corridor diversity (null for single-connection routing)
        List<AbsoluteBendpointDto> bendpoints = routeFromCenters(source, target, obstacles, groupBoundaries, occupancyTracker);

        // Check if the route passes through source or target body.
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

            // Fallback edge port strategy.
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
     * non-terminal segment. The first segment naturally exits the source
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
     * <p>Used by the fallback loop in {@link #routeConnection} when the primary
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
     * (source, target, ancestors, descendants) for consistency with LayoutQualityAssessor.
     */
    public RoutingResult routeAllConnections(
            List<ConnectionEndpoints> connections, List<RoutingRect> allObstacles,
            Map<String, Set<String>> labelExcludeSets) {
        return routeAllConnections(connections, allObstacles, labelExcludeSets, DEFAULT_SNAP_THRESHOLD);
    }

    /**
     * Route all connections with snap-to-straight threshold.
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
        return routeAllConnections(connections, allObstacles, labelExcludeSets,
                snapThreshold, DEFAULT_ENABLE_CHANNEL_NUDGING);
    }

    /**
     * Route all connections with the channel-global ordered nudging post-pass gate.
     *
     * <p>When {@code enableChannelNudging} is true (default), the new Stage 4.7o runs between
     * Stage 4.7n (final orthogonality safety net) and Stage 4.8 (label position optimization).
     * When false, {@link ChannelNudgingPass} is neither constructed nor invoked — byte-identical
     * output to the previous 4-arg overload.</p>
     *
     * @param connections          list of connection endpoints to route
     * @param allObstacles         all element rectangles on the view
     * @param labelExcludeSets     per-connection label exclusion sets (nullable)
     * @param snapThreshold        snap-to-straight threshold (0 disables)
     * @param enableChannelNudging when true, channel-global ordered nudging post-pass runs
     * @return RoutingResult with routed connections and failed connections
     */
    public RoutingResult routeAllConnections(
            List<ConnectionEndpoints> connections, List<RoutingRect> allObstacles,
            Map<String, Set<String>> labelExcludeSets, int snapThreshold,
            boolean enableChannelNudging) {
        return routeAllConnections(connections, allObstacles, labelExcludeSets,
                snapThreshold, enableChannelNudging, null);
    }

    /**
     * Route all connections with an explicit connection processing-order override
     * The best-of-K multi-start seam (spike decision D1).
     *
     * <p><b>Purely additive — the narrowest possible seam.</b> When
     * {@code processingOrderOverride == null} this method is <em>byte-identical to
     * current {@code main}</em>: the pipeline computes its own unchanged
     * {@link #buildConnectionRoutingOrder}. Every pre-existing overload delegates
     * here with {@code null}, so all existing callers are unaffected. A non-null
     * array is the exact order (indices into {@code connections}) in which
     * connections are fed to the otherwise <em>entirely unchanged</em> pipeline —
     * it changes ONLY the feed order: no stage in the 4.7a..4.7r sequence, no A*
     * cost, no corridor-occupancy keying, no channel nudging, no edge attachment
     * and no assessor behaviour is altered. The {@link BestOfKRoutingStrategy}
     * outer wrapper supplies seeded permutations here and selects the best
     * complete result by the ship-gate aggregate (run&nbsp;0 always uses
     * {@code null} ⇒ never-worse-by-construction). A non-null override whose
     * length does not match {@code connections.size()} is rejected defensively
     * (falls back to {@link #buildConnectionRoutingOrder}) so a malformed override
     * can never crash the pipeline or violate never-worse.</p>
     *
     * @param connections             list of connection endpoints to route
     * @param allObstacles            all element rectangles on the view
     * @param labelExcludeSets        per-connection label exclusion sets (nullable)
     * @param snapThreshold           snap-to-straight threshold (0 disables)
     * @param enableChannelNudging    when true, channel-global ordered nudging runs
     * @param processingOrderOverride exact processing order (indices into
     *        {@code connections}); {@code null} ⇒ the unchanged
     *        {@link #buildConnectionRoutingOrder} (≡ current {@code main})
     * @return RoutingResult with routed connections and failed connections
     */
    public RoutingResult routeAllConnections(
            List<ConnectionEndpoints> connections, List<RoutingRect> allObstacles,
            Map<String, Set<String>> labelExcludeSets, int snapThreshold,
            boolean enableChannelNudging, Integer[] processingOrderOverride) {
        logger.info("Batch routing {} connections with path ordering and edge nudging",
                connections.size());

        // Sort connections by descending Manhattan distance (longest first).
        // Most constrained connections route first, getting best corridor selection.
        // Build index mapping to restore original order after routing.
        // best-of-K: an explicit, length-matched processing-order
        // override replaces the default order; null (every legacy caller) ⇒
        // byte-identical to the default ordering.
        Integer[] sortedIndices;
        if (processingOrderOverride != null
                && processingOrderOverride.length == connections.size()) {
            sortedIndices = processingOrderOverride;
        } else {
            if (processingOrderOverride != null) {
                logger.warn("best-of-K: ignoring processingOrderOverride of length {} "
                        + "(expected {}) — falling back to buildConnectionRoutingOrder",
                        processingOrderOverride.length, connections.size());
            }
            sortedIndices = buildConnectionRoutingOrder(connections);
        }

        // 1. Route each connection individually with corridor occupancy tracking
        List<String> connectionIds = new ArrayList<>(Collections.nCopies(connections.size(), null));
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>(Collections.nCopies(connections.size(), null));
        List<int[]> sourceCenters = new ArrayList<>(Collections.nCopies(connections.size(), null));
        List<int[]> targetCenters = new ArrayList<>(Collections.nCopies(connections.size(), null));
        // Parallel arrays for per-connection TerminalAnchoring records,
        // populated by EdgeAttachmentCalculator.applyEdgeAttachments at stage 4.
        // Consumed by the five wrap sites (4 in PathStraightener at stage 4.7i,
        // 1 in CoincidentSegmentDetector.applyOffsets at stage 4.7h). No new
        // carrier type — just two more parallel arrays alongside
        // sourceCenters / targetCenters.
        List<TerminalAnchoring> sourceAnchorings = new ArrayList<>();
        List<TerminalAnchoring> targetAnchorings = new ArrayList<>();
        CorridorOccupancyTracker occupancyTracker = new CorridorOccupancyTracker();

        for (int si = 0; si < sortedIndices.length; si++) {
            int origIdx = sortedIndices[si].intValue();
            ConnectionEndpoints conn = connections.get(origIdx);
            int[] srcCenter = new int[]{conn.source().centerX(), conn.source().centerY()};
            int[] tgtCenter = new int[]{conn.target().centerX(), conn.target().centerY()};
            List<AbsoluteBendpointDto> routed = routeConnection(
                    conn.source(), conn.target(), conn.obstacles(), conn.groupBoundaries(), occupancyTracker);
            // Record routed path for corridor occupancy
            occupancyTracker.recordPath(routed, srcCenter, tgtCenter);
            // Store in original order position
            connectionIds.set(origIdx, conn.connectionId());
            bendpointLists.set(origIdx, routed);
            sourceCenters.set(origIdx, srcCenter);
            targetCenters.set(origIdx, tgtCenter);
        }
        logger.debug("B47: Routed {} connections with occupancy tracking ({} corridors occupied)",
                connections.size(), occupancyTracker.getCorridorOccupancy().size());

        // 1.1. Straight-line crossing estimate
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

        // 3.5a. Coincident segment detection and offset
        // Pre-attachment, anchorings are not yet populated — the legacy
        // 3-arg overload is correct here (no terminal-anchoring wrap because
        // no perimeter terminals exist yet).
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

        // 3.7. Post-routing obstacle re-validation (Patterns 1 & 4)
        // Pipeline stages (nudger, label clearance) can shift paths into obstacle boundaries.
        // Must run BEFORE edge attachment so terminal bendpoints are not stripped.
        for (int i = 0; i < nudgedPaths.size(); i++) {
            removeObstacleViolations(nudgedPaths.get(i), connections.get(i).obstacles());
        }

        // 3.8. Enforce orthogonal path segments (Pattern 3)
        // Must run BEFORE edge attachment so terminals are added to clean orthogonal paths.
        for (int i = 0; i < nudgedPaths.size(); i++) {
            enforceOrthogonalPaths(nudgedPaths.get(i));
        }

        // 4. Apply edge attachments (terminal bendpoints at element faces)
        // The 5-arg producer overload also fills sourceAnchorings / targetAnchorings
        // for the five downstream wrap sites (stage 4.7h applyOffsets +
        // stage 4.7i PathStraightener × 4).
        edgeAttachmentCalculator.applyEdgeAttachments(connectionIds, nudgedPaths, connections,
                sourceAnchorings, targetAnchorings);

        // Save terminal BP positions for post-cleanup restoration
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

        // 4.1. Post-attachment orthogonal enforcement
        // Edge attachment may introduce diagonal terminal segments
        for (int i = 0; i < nudgedPaths.size(); i++) {
            enforceOrthogonalPaths(nudgedPaths.get(i));
        }

        // 4.2. Post-attachment obstacle re-validation
        // Edge attachment and orthogonal enforcement may create segments passing through obstacles
        for (int i = 0; i < nudgedPaths.size(); i++) {
            removeObstacleViolations(nudgedPaths.get(i), connections.get(i).obstacles());
        }

        // 4.4. Snap near-aligned connections to straight segments
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

        // 4.6. Final obstacle validation after cleanup
        // Micro-jog removal and collinear cleanup can merge segments into obstacle-crossing paths
        for (int i = 0; i < nudgedPaths.size(); i++) {
            removeObstacleViolations(nudgedPaths.get(i), connections.get(i).obstacles());
        }

        // 4.6a. Endpoint pass-through correction
        // Pipeline stages (simplify, nudge, edge attachment, cleanup) can introduce
        // BPs inside source/target elements and segments that pass through them.
        // Step 1: Remove any BPs that are inside endpoint elements.
        // Step 2: Insert corrective detour BPs around the element where segments still cross.
        for (int i = 0; i < nudgedPaths.size(); i++) {
            correctEndpointPassThroughs(nudgedPaths.get(i),
                    connections.get(i).source(), connections.get(i).target());
        }

        // 4.6b. Terminal realignment — restore face-center exit/entry after cleanup
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

        // 4.7. Bendpoint clearance enforcement
        // After all path cleanup stages, ensure intermediate BPs maintain minimum clearance
        // from obstacle boundaries. Terminal BPs (at element faces) are excluded.
        // Axis-constrained nudging preserves orthogonality.
        // Source-side-reversal lane-crossing fix: the clearance passes
        // (4.7 / 4.7b / 4.7c) use the connection's OWN obstacle set, which has the
        // endpoints' ancestors/children already excluded (built in
        // ArchiModelAccessorImpl#buildOrthogonalRoutingCommands), rather than the
        // full allObstacles. Routing legitimately runs inside its own ancestor
        // container (e.g. a swimlane BusinessRole element that spans the canvas
        // width); treating that container as a clearance obstacle made
        // enforceMinClearance find an intra-container BP "inside" the band and yank
        // it to the band's far edge (e.g. corner (390,105) inside a full-width lane
        // -> (12,168)), seeding a source-side reversal / self-pass-through. Using
        // conn.obstacles() makes the clearance passes consistent with the A* router
        // and with TerminalEgressClearancePass's ancestor-aware Tier-1 check.
        // No empty-set fallback to allObstacles (unlike the egress REJECTION check):
        // an empty obstacle set means no foreign elements exist, so there is nothing
        // to clear from and the pass must be a no-op — falling back here would
        // re-introduce the ancestor band and the reversal.
        int totalClearanceNudges = 0;
        for (int i = 0; i < nudgedPaths.size(); i++) {
            totalClearanceNudges += enforceMinClearance(nudgedPaths.get(i),
                    connections.get(i).obstacles(),
                    connections.get(i).source(), connections.get(i).target());
        }
        if (totalClearanceNudges > 0) {
            logger.info("Clearance enforcement: nudged {} bendpoints to maintain {}px minimum clearance",
                    totalClearanceNudges, MIN_CLEARANCE);
            // Post-clearance cleanup — restore path quality after nudging
            for (int i = 0; i < nudgedPaths.size(); i++) {
                removeMicroJogs(nudgedPaths.get(i), MICRO_JOG_THRESHOLD);
                removeDuplicatePoints(nudgedPaths.get(i));
                removeCollinearPoints(nudgedPaths.get(i));
                removeObstacleViolations(nudgedPaths.get(i), connections.get(i).obstacles());
            }
        }

        // 4.7b. Segment-based clearance enforcement
        // After point-based clearance, check entire intermediate segments against obstacle
        // boundaries. Catches grazing where all BPs are outside obstacle bands but the
        // segment itself runs too close to an obstacle face.
        int totalSegmentShifts = 0;
        for (int i = 0; i < nudgedPaths.size(); i++) {
            // conn.obstacles() (ancestor-excluded) — see the source-side-reversal note at stage 4.7.
            totalSegmentShifts += enforceSegmentClearance(nudgedPaths.get(i),
                    connections.get(i).obstacles(),
                    connections.get(i).source(), connections.get(i).target());
        }
        if (totalSegmentShifts > 0) {
            logger.info("Segment clearance enforcement: shifted {} segments to maintain {}px minimum clearance",
                    totalSegmentShifts, MIN_CLEARANCE);
            // Post-shift cleanup — same pattern as point clearance
            for (int i = 0; i < nudgedPaths.size(); i++) {
                removeMicroJogs(nudgedPaths.get(i), MICRO_JOG_THRESHOLD);
                removeDuplicatePoints(nudgedPaths.get(i));
                removeCollinearPoints(nudgedPaths.get(i));
                removeObstacleViolations(nudgedPaths.get(i), connections.get(i).obstacles());
            }
        }

        // 4.7c. Terminal corridor clearance enforcement
        // After point-based (4.7) and segment-based (4.7b) clearance, handle 2-BP and 3-BP
        // paths where terminal-to-terminal segments graze obstacles. These paths have no
        // intermediate BPs/segments for the earlier stages to check.
        int totalTerminalFixes = 0;
        for (int i = 0; i < nudgedPaths.size(); i++) {
            // conn.obstacles() (ancestor-excluded) — see the source-side-reversal note at stage 4.7.
            totalTerminalFixes += enforceTerminalCorridorClearance(nudgedPaths.get(i),
                    connections.get(i).obstacles(),
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

        // 4.7d. Post-pipeline terminal orthogonality verification
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

        // 4.7e. ChopboxAnchor center-aligned terminal alignment
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
            // Minimal cleanup only — this stage inserts BPs at element face edges, which cannot
            // create obstacle crossings. removeObstacleViolations is intentionally omitted
            // to avoid removing intermediate BPs placed by earlier stages.
            for (int i = 0; i < nudgedPaths.size(); i++) {
                removeDuplicatePoints(nudgedPaths.get(i));
                removeCollinearPoints(nudgedPaths.get(i));
            }
        }

        // 4.7f. Self-element pass-through face correction (Phase B)
        // When a connection's routed path clips through its own source or target element,
        // re-select the face and re-route terminal segments with a clearance waypoint.
        // Re-routes terminal-adjacent segments instead of just swapping the terminal BP
        // (avoiding the earlier Frankenstein path issue).
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

        // 4.7g. Late-stage path simplification
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

        // 4.7h. Post-simplification coincident segment resolver
        // Re-runs CoincidentSegmentDetector after late-stage simplification, which collapses
        // separation jogs and creates new coincident segments. Unlike Stage 3.5a which
        // runs pre-attachment, this pass catches coincidences introduced by all post-
        // processing stages. Uses segment-based detection (not endpoint
        // grouping) so ALL coincident corridors are found regardless of shared endpoints.
        List<CoincidentSegmentDetector.CoincidentPair> postSimplifyPairs =
                coincidentDetector.detect(connectionIds, nudgedPaths, sourceCenters, targetCenters);
        if (!postSimplifyPairs.isEmpty()) {
            // Wrap site #5 (applyOffsets): build per-connection anchoring
            // contexts from the parallel arrays produced by stage 4 and let
            // CoincidentSegmentDetector roll back any segment offset that
            // would violate TerminalAnchoring.preservesEndpoints. Replaces
            // the earlier Mode B touchesPerimeterAnchoredTerminal filter.
            // Load-bearing for V4 Integration Architecture: pre-fix, the
            // legacy offset dragged path[0] off hub LEFT perimeter and
            // collapsed 3 of 7 API Gateway outbound terminals to the face
            // midpoint.
            Map<Integer, CoincidentSegmentDetector.AnchoringContext> anchoringContexts =
                    new HashMap<>();
            for (int ci = 0; ci < connections.size(); ci++) {
                anchoringContexts.put(ci, new CoincidentSegmentDetector.AnchoringContext(
                        connections.get(ci),
                        sourceCenters.get(ci), targetCenters.get(ci),
                        ci < sourceAnchorings.size() ? sourceAnchorings.get(ci) : null,
                        ci < targetAnchorings.size() ? targetAnchorings.get(ci) : null));
            }
            int coincidentResolved = coincidentDetector.applyOffsets(
                    postSimplifyPairs, nudgedPaths, allObstacles, anchoringContexts);
            if (coincidentResolved > 0) {
                logger.info("Coincident segment resolver: separated {} coincident segments", coincidentResolved);
                for (int i = 0; i < nudgedPaths.size(); i++) {
                    removeDuplicatePoints(nudgedPaths.get(i));
                    removeCollinearPoints(nudgedPaths.get(i));
                }
            }
        }

        // 4.7i. Post-routing path straightening
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
            // All four PathStraightener mutators are wrap sites under the
            // TerminalAnchoring.preservesEndpoints predicate. The new 8-arg
            // overloads snapshot each path on entry, run the existing logic,
            // and roll back on predicate violation at either terminal.
            // Replaces the earlier containsPerimeterBP guards inline at
            // eliminateReversals and collapseBends respectively. The wrap also
            // covers the two formerly-unguarded silent offenders (snapToStraight,
            // collapseStaircaseJogs).
            RoutingRect connSource = connections.get(i).source();
            RoutingRect connTarget = connections.get(i).target();
            TerminalAnchoring srcAnchoring = (i < sourceAnchorings.size()) ? sourceAnchorings.get(i) : null;
            TerminalAnchoring tgtAnchoring = (i < targetAnchorings.size()) ? targetAnchorings.get(i) : null;
            // augmented=true: stage 4.7i prepends source center at index 0
            // and appends target center at index size-1 — the wrap evaluates
            // the predicate against the inner view (path[1] / path[size-2]).
            PathStraightener.snapToStraight(path, DEFAULT_SNAP_THRESHOLD, connObstacles,
                    connSource, connTarget, sc, tc, srcAnchoring, tgtAnchoring, true);
            PathStraightener.eliminateReversals(path, connObstacles,
                    connSource, connTarget, sc, tc, srcAnchoring, tgtAnchoring, true);
            PathStraightener.collapseStaircaseJogs(path, DEFAULT_SNAP_THRESHOLD, connObstacles,
                    connSource, connTarget, sc, tc, srcAnchoring, tgtAnchoring, true);
            PathStraightener.collapseBends(path, connObstacles,
                    connSource, connTarget, sc, tc, srcAnchoring, tgtAnchoring, true);

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

        // 4.7k. Center-termination fix + final ChopboxAnchor alignment
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
            // Defense-in-depth: removeCollinearPoints can expose a previously-
            // hidden center BP by removing intermediate collinear points. Re-check
            // after cleanup to catch any newly-exposed center-terminations.
            for (int i = 0; i < nudgedPaths.size(); i++) {
                centerFixes += fixCenterTerminatedPath(nudgedPaths.get(i), connections.get(i));
            }
        }

        // 4.7l. Center-termination safety net validation
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

        // 4.7m. Interior terminal BP fix
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
            // Defense-in-depth: second pass after cleanup (mirrors the center-termination pattern)
            for (int i = 0; i < nudgedPaths.size(); i++) {
                fixInteriorTerminalBPs(nudgedPaths.get(i), connections.get(i));
            }
        }

        // 4.7n. Final orthogonality enforcement safety net
        // Post-processing stages (4.6a correctEndpointPassThroughs, 4.7g-4.7m) can create
        // non-orthogonal segments by removing BPs inside elements without reinserting L-bends.
        // This catches any remaining diagonals as a last resort before label optimization.
        for (int i = 0; i < nudgedPaths.size(); i++) {
            enforceOrthogonalPaths(nudgedPaths.get(i));
        }

        // 4.7o. Channel-global ordered nudging post-pass
        // Groups all axis-aligned runs into obstacle-bounded channels (keyed by
        // (axis, gapLow, gapHigh) via CoincidentSegmentDetector.computeCorridorGap), then
        // walks channels (not connections) and allocates distinct tracks within each
        // corridor's free zone. Single-occupant channels centre on slack midpoint;
        // multi-occupant channels fan out evenly. Per-route monotone rollback guards
        // terminal alignment, obstacle clearance, and new-coincident-pair invariants.
        //
        // Why this layer: routing quality is a global property of the route set, not a
        // local property of individual edges or connections. Clearance-weighted A*,
        // the CorridorOccupancyTracker, the centerline symmetry pass (reverted), and
        // the clearance balance term (falsified) all operated with local visibility
        // and all hit the same wall. This pass is the first to operate on routes with
        // global visibility after A* has settled.
        //
        // Primary references: Wybrow, Marriott, Stuckey — Orthogonal Connector Routing,
        // GD 2009 §4 "Ordering and Nudging"; Hegemann & Wolff — A Simple Pipeline for
        // Orthogonal Graph Drawing, GD 2023 §3-§4; libavoid orthogonal.cpp nudgeOrthogonal*.
        //
        // Gate: when enableChannelNudging is false, ChannelNudgingPass is neither
        // constructed nor invoked — byte-identical output to the prior behaviour.
        if (enableChannelNudging) {
            // Aggregate unique top-level group boundaries from all connections
            // for inter-group corridor channel detection. A group is top-level if no
            // other group in the set fully encloses it.
            List<RoutingRect> topLevelGroupBounds = extractTopLevelGroupBounds(connections);

            ChannelNudgingPass channelNudging = new ChannelNudgingPass();
            int b69bNudges = channelNudging.run(connections, nudgedPaths, allObstacles,
                    topLevelGroupBounds);
            if (b69bNudges > 0 || channelNudging.getRollbackCount() > 0) {
                logger.info("B69-B channel nudging: {} nudges applied, {} per-route rollbacks",
                        b69bNudges, channelNudging.getRollbackCount());
                // Defense-in-depth: re-apply terminal alignment and orthogonality after
                // the pass. Per-route rollback is the primary safety net; this
                // second pass catches any micro-orthogonality drift from track assignment.
                for (int i = 0; i < nudgedPaths.size(); i++) {
                    alignTerminalsWithCenter(nudgedPaths.get(i), connections.get(i));
                    enforceOrthogonalPaths(nudgedPaths.get(i));
                }
                for (int i = 0; i < nudgedPaths.size(); i++) {
                    removeDuplicatePoints(nudgedPaths.get(i));
                    removeCollinearPoints(nudgedPaths.get(i));
                }
            }
        }

        // 4.7m. H5 — Hub-Perimeter Routing Stage.
        // Conceptually this stage inserts "between ChannelNudgingPass and PathStraightener". In the
        // actual pipeline order PathStraightener (stage 4.7i) runs BEFORE ChannelNudgingPass
        // (4.7o), so the operative interpretation is: after the global-nudging pass has assigned
        // tracks across all corridors, run the hub-perimeter-aware refinement before the next
        // post-processing stage (4.7p self-hug correction). The stage runs ALONGSIDE
        // ChannelNudgingPass — never mutates it; the divisor-7 width-aware cap stays at current
        // calibration. Single field + single line.
        //
        // Re-enabled 2026-05-14 after the verifyMetricMonotonicity() guard shipped:
        // before every Axis-1 / Axis-2 apply, the stage
        // computes a MetricSnapshot and rejects any post-state that regresses M4 / V_p10 / HPQ
        // (rollback-on-fail). The stage can now only IMPROVE or no-op on the
        // monotonicity-guarded metrics — the regressions caught on HH (V_p10 6.4 → 5.8)
        // and ST (M4 7 → 8) are now structurally impossible.
        HubPerimeterRoutingStage.Result h5Result = hubPerimeterRoutingStage.apply(connections, nudgedPaths, allObstacles);
        if (h5Result.axis1Applied() > 0 || h5Result.axis2Applied() > 0
                || h5Result.axis1Rolled() > 0 || h5Result.axis2Rolled() > 0
                || h5Result.migratorApplied() > 0 || h5Result.migratorRolled() > 0) {
            logger.info("H5 hub-perimeter routing: {} cells, axis1 {}/{} applied/rolled, "
                            + "axis2 {}/{} applied/rolled, axis3-migrator {}/{} applied/rolled",
                    h5Result.cellsProcessed(),
                    h5Result.axis1Applied(), h5Result.axis1Rolled(),
                    h5Result.axis2Applied(), h5Result.axis2Rolled(),
                    h5Result.migratorApplied(), h5Result.migratorRolled());
        }

        // 4.7p. Source/target self-hug correction
        // Post-processing pass that detects face-parallel segments created by
        // simplifyFinalPath's vertical-first L-turn fallback (which places midpoint
        // at a.x = face line for LEFT/RIGHT faces) and redirects them into the
        // nearest obstacle-free corridor. Runs after all post-processing stages
        // (4.7h coincident resolver, 4.7i straightener, 4.7o channel nudging) to
        // avoid cascading through coincident detection. Only modifies interior BPs.
        int selfHugFixes = 0;
        for (int i = 0; i < nudgedPaths.size(); i++) {
            TerminalAnchoring srcAnch = (i < sourceAnchorings.size()) ? sourceAnchorings.get(i) : null;
            TerminalAnchoring tgtAnch = (i < targetAnchorings.size()) ? targetAnchorings.get(i) : null;
            if (srcAnch != null) {
                if (correctSourceSelfHug(nudgedPaths.get(i), connections.get(i).obstacles(),
                        srcAnch, connections.get(i).source())) {
                    selfHugFixes++;
                }
            }
            if (tgtAnch != null) {
                if (correctTargetSelfHug(nudgedPaths.get(i), connections.get(i).obstacles(),
                        tgtAnch, connections.get(i).target())) {
                    selfHugFixes++;
                }
            }
        }
        if (selfHugFixes > 0) {
            logger.info("Source/target self-hug correction: fixed {} face-hugging segments", selfHugFixes);
            for (int i = 0; i < nudgedPaths.size(); i++) {
                removeDuplicatePoints(nudgedPaths.get(i));
                removeCollinearPoints(nudgedPaths.get(i));
            }

            // 4.7p+1. Re-run coincident segment resolver after face-hug correction.
            // The correction at 4.7p may create new coincident segments that 4.7h
            // couldn't anticipate. Reuses the same detector and anchoring contexts.
            List<CoincidentSegmentDetector.CoincidentPair> postHugPairs =
                    coincidentDetector.detect(connectionIds, nudgedPaths, sourceCenters, targetCenters);
            if (!postHugPairs.isEmpty()) {
                Map<Integer, CoincidentSegmentDetector.AnchoringContext> postHugAnchCtx =
                        new HashMap<>();
                for (int ci = 0; ci < connections.size(); ci++) {
                    postHugAnchCtx.put(ci, new CoincidentSegmentDetector.AnchoringContext(
                            connections.get(ci),
                            sourceCenters.get(ci), targetCenters.get(ci),
                            ci < sourceAnchorings.size() ? sourceAnchorings.get(ci) : null,
                            ci < targetAnchorings.size() ? targetAnchorings.get(ci) : null));
                }
                int postHugResolved = coincidentDetector.applyOffsets(
                        postHugPairs, nudgedPaths, allObstacles, postHugAnchCtx);
                if (postHugResolved > 0) {
                    logger.info("Post-hug-correction coincident resolver: separated {} segments",
                            postHugResolved);
                    for (int i = 0; i < nudgedPaths.size(); i++) {
                        removeDuplicatePoints(nudgedPaths.get(i));
                        removeCollinearPoints(nudgedPaths.get(i));
                    }
                }
            }
        }

        // 4.7q. Approach-3 reconciliation pass for terminal-anchored coincident segments.
        // Runs after the rollback-eligible applyOffsets sites (stage 4.7h post-
        // edge-attach + stage 4.7p+1 post-hug — both invoke the 4-arg overload
        // with anchoring contexts, so TerminalAnchoring rollback can fire).
        // Stage 3.5a pre-attach uses the legacy 3-arg overload (Map.of() empty
        // contexts, line ~628) because terminals aren't yet attached at that
        // point, so 3.5a's applyOffsets has no rollback to reconcile from.
        //
        // For coincident segments stages 4.7h / 4.7p+1 couldn't separate
        // (the simple perpendicular delta-shift would have moved a terminal
        // BP off its face line — TerminalAnchoring rollback fires correctly,
        // but the coincidence persists), this pass INSERTS two BPs to shift
        // the corridor perpendicular while leaving the terminal BP itself on
        // the face line. Resolves the V4 oracle current-pipeline regression
        // (M5=12 corridor-perpendicular coincidences with parallel-axis-
        // distinct terminals). The existing rollback path is unmodified — this stage adds
        // a NEW resolution path that runs only when the rollback has fired.
        List<CoincidentSegmentDetector.CoincidentPair> reconcilerPairs =
                coincidentDetector.detect(connectionIds, nudgedPaths, sourceCenters, targetCenters);
        if (!reconcilerPairs.isEmpty()) {
            Map<Integer, CoincidentSegmentDetector.AnchoringContext> reconcilerAnchCtx =
                    new HashMap<>();
            for (int ci = 0; ci < connections.size(); ci++) {
                reconcilerAnchCtx.put(ci, new CoincidentSegmentDetector.AnchoringContext(
                        connections.get(ci),
                        sourceCenters.get(ci), targetCenters.get(ci),
                        ci < sourceAnchorings.size() ? sourceAnchorings.get(ci) : null,
                        ci < targetAnchorings.size() ? targetAnchorings.get(ci) : null));
            }
            int reconciled = coincidentDetector.applyTerminalAnchoredReconciliation(
                    connectionIds, nudgedPaths, sourceCenters, targetCenters,
                    allObstacles, reconcilerAnchCtx);
            if (reconciled > 0) {
                logger.info("Terminal-anchored reconciliation: inserted drops for {} coincident segments",
                        reconciled);
                for (int i = 0; i < nudgedPaths.size(); i++) {
                    removeDuplicatePoints(nudgedPaths.get(i));
                    removeCollinearPoints(nudgedPaths.get(i));
                }
            }
        }

        // 4.7r. Final interior-BP safety net
        // Stages 4.7n–4.7q (enforceOrthogonalPaths, alignTerminalsWithCenter,
        // self-hug correction, coincident resolver, terminal-anchored reconciler)
        // can introduce terminal BPs inside endpoint elements after
        // fixInteriorTerminalBPs ran at 4.7m. This final pass catches any
        // remaining interior terminals before label optimization and the final
        // violation check.
        int finalInteriorFixes = 0;
        for (int i = 0; i < nudgedPaths.size(); i++) {
            finalInteriorFixes += fixCenterTerminatedPath(nudgedPaths.get(i), connections.get(i));
            finalInteriorFixes += fixInteriorTerminalBPs(nudgedPaths.get(i), connections.get(i));
        }
        if (finalInteriorFixes > 0) {
            logger.info("B77 final interior-BP safety net: corrected {} BP(s) inside endpoint elements",
                    finalInteriorFixes);
            for (int i = 0; i < nudgedPaths.size(); i++) {
                removeDuplicatePoints(nudgedPaths.get(i));
                removeCollinearPoints(nudgedPaths.get(i));
            }
        }

        // 4.7s. Terminal-egress corridor-aware clearance (the W3 Lever-B successor).
        // Eliminates the terminal-egress "edge-hug" (a connector that leaves a face, runs
        // hard-parallel ~1px against that element's own edge, then bends 90°) on feasible/open
        // views by lengthening the perpendicular egress stub into the clearance that exists
        // beside it. Runs AS THE LAST geometry-mutating stage — after 4.7r — so its in-stage
        // M4/V_p10/HPQ/Tier-1 validation is, by construction, a FINAL-pipeline-state validation:
        // nothing downstream re-mutates bendpoints (4.8 only positions labels; stage 5 only
        // classifies). This closes the downstream-guard blind spot that rejected the in-stage
        // (HubPerimeterRoutingStage Axis-4) v1.
        //
        // Safe on tight hub corridors (HH): a cheap view-level pre-gate skips the whole pass when
        // the pre-pass V_p10 parallel-connection gap is already narrow (< MIN_CLEARANCE), and the
        // per-proposal room search requires max(MIN_CLEARANCE, pre-pass V_p10) clearance to
        // neighbouring co-axial connection segments — so a push can NEVER introduce a
        // parallel-connection gap narrower than the view already had (the killer V_p10 4.0->3.4
        // regression of v1 is structurally impossible). Terminal kept byte-identical by
        // construction → hub slot preserved → HPQ cannot regress from the transform. Any push
        // that does not net-improve the final M4 without regressing V_p10/HPQ/Tier-1 is rolled
        // back byte-identical.
        TerminalEgressClearancePass.Result egressResult =
                terminalEgressClearancePass.run(connections, nudgedPaths, allObstacles);
        if (egressResult.applied() > 0 || egressResult.rolled() > 0) {
            logger.info("Terminal-egress corridor-aware clearance: {} applied, {} rolled back, "
                            + "{} proposals evaluated",
                    egressResult.applied(), egressResult.rolled(), egressResult.proposalsEvaluated());
            for (int i = 0; i < nudgedPaths.size(); i++) {
                removeDuplicatePoints(nudgedPaths.get(i));
                removeCollinearPoints(nudgedPaths.get(i));
            }
        } else if (egressResult.skippedByPreGate()) {
            logger.debug("Terminal-egress corridor-aware clearance: pre-gate skipped (view V_p10 < {})",
                    (int) TerminalEgressClearancePass.PRE_GATE_VP10_PX);
        }

        // 4.8. Label position optimization pass
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

        // 5. Final violation check and build RoutingResult
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

        // 5a. Corridor re-route for failed connections
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
                // Phase B face correction with re-routing (replaces the earlier terminal-only swap)
                correctSelfElementPassThrough(rerouted, conn, true);
                correctSelfElementPassThrough(rerouted, conn, false);
                removeDuplicatePoints(rerouted);
                removeCollinearPoints(rerouted);
                // Fix center-terminated terminals + final alignment (mirrors 4.7k)
                fixCenterTerminatedPath(rerouted, conn);
                // Fix interior terminal BPs (mirrors 4.7m)
                fixInteriorTerminalBPs(rerouted, conn);
                alignTerminalsWithCenter(rerouted, conn);
                removeDuplicatePoints(rerouted);
                removeCollinearPoints(rerouted);
                // Final orthogonality safety net (mirrors 4.7n)
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

        // 5.0b. Dissolve coincident same-face terminal ports.
        // The per-face distributor spreads co-grouped terminals to distinct slots, but later terminal
        // stages (center-alignment / restoration) can pull a source-exit and a target-entry sharing a
        // face back onto its centre line, collapsing them onto one perimeter port. This final pass
        // re-fans any such collision — collision-gated, crossing-aware, on-line, distinguishability-
        // floored — so a face's ports stay distinct; non-colliding faces are left byte-identical.
        spreadCoincidentFacePorts(routed, connections);

        // 5.1. Recommendation engine — only runs if failed list non-empty
        List<MoveRecommendation> recommendations = List.of();
        if (!failed.isEmpty()) {
            recommendations = RoutingRecommendationEngine.recommend(failed, connections, allObstacles);
            if (!recommendations.isEmpty()) {
                logger.info("Generated {} move recommendations for {} blocking elements",
                        recommendations.size(), recommendations.size());
            }
        }

        return new RoutingResult(routed, failed, recommendations, violatedRoutes,
                optimalPositions.size(), optimalPositions, straightLineCrossings, egressResult.rolled());
    }

    /**
     * Along-face slot tolerance below which two same-face terminals are one visible port.
     * Mirrors the layout assessor's coincident-face-port predicate (its
     * {@code HUB_PORT_SLOT_TOLERANCE_PX}); kept as a local copy because that constant is
     * package-private to the assessor's package. The gate fires on exactly what the assessor
     * enumerates, so a spread here drives the assessor's coincident-face-port reading to zero.
     */
    private static final double COINCIDENT_PORT_SLOT_TOLERANCE_PX = 1.0;

    /**
     * Elements with this many or more incident connections are hubs, whose ports are laid out by the
     * dedicated hub distribution / perimeter-routing machinery (reduced-port packing, per-face
     * redistribution). The coincident-port spread stays clear of them — it targets the ordinary
     * low-degree element where a source-exit and a target-entry collapse onto one face-centre port.
     * Mirrors {@code LayoutQualityAssessor.HUB_DETECTION_THRESHOLD} (package-private to that package).
     */
    private static final int HUB_DEGREE_THRESHOLD = 5;

    /** One connection terminal that sits on a low-degree element's perimeter — a spread candidate. */
    private record PortRef(List<AbsoluteBendpointDto> path, ConnectionEndpoints conn, boolean isSource,
            RoutingRect elem, boolean slotAlongY, double slot) {}

    /**
     * Final pass: dissolves coincident same-face terminal ports across all routed connections.
     *
     * <p>Terminals on low-degree, on-perimeter elements are grouped by element face, clustered by
     * along-face slot, and each colliding cluster is separated by moving whichever members CAN move
     * (an on-line terminal whose stub stays perpendicular) off the ones that cannot (e.g. a terminal
     * whose stub hugs the face line — moving it would defeat orthogonality). Every move is
     * collision-gated, distinguishability-floored, crossing-aware, and obstacle-safe; a face whose
     * ports are already distinct, or that cannot be separated without breaking a gate, is left
     * byte-identical. Dense hubs (degree ≥ {@link #HUB_DEGREE_THRESHOLD}) are excluded — their ports
     * are owned by the dedicated hub distribution machinery.</p>
     *
     * @param routed      routed paths (connectionId -> bendpoints), mutated in place
     * @param connections all connection endpoints, providing stable order and element resolution
     */
    void spreadCoincidentFacePorts(Map<String, List<AbsoluteBendpointDto>> routed,
            List<ConnectionEndpoints> connections) {
        Map<String, ConnectionEndpoints> connectionMap = new HashMap<>();
        // Port degree per element, memoised once (a self-referencing connection counts once, matching
        // "connections incident on the element").
        Map<String, Integer> degreeByElement = new HashMap<>();
        for (ConnectionEndpoints c : connections) {
            connectionMap.put(c.connectionId(), c);
            degreeByElement.merge(c.source().id(), 1, Integer::sum);
            if (!c.target().id().equals(c.source().id())) {
                degreeByElement.merge(c.target().id(), 1, Integer::sum);
            }
        }
        // Group spread-candidate terminals by element face (stable order).
        Map<String, List<PortRef>> faceGroups = new LinkedHashMap<>();
        for (ConnectionEndpoints c : connections) {
            List<AbsoluteBendpointDto> path = routed.get(c.connectionId());
            if (path == null || path.size() < 2) {
                continue;
            }
            collectPortRef(faceGroups, path, c, true, c.source(), degreeByElement);
            collectPortRef(faceGroups, path, c, false, c.target(), degreeByElement);
        }
        for (List<PortRef> refs : faceGroups.values()) {
            resolveFaceGroup(refs, routed, connectionMap);
        }
    }

    /** Records a terminal as a spread candidate when it is on a low-degree element's perimeter. */
    private static void collectPortRef(Map<String, List<PortRef>> faceGroups,
            List<AbsoluteBendpointDto> path, ConnectionEndpoints conn, boolean isSource,
            RoutingRect elem, Map<String, Integer> degreeByElement) {
        // Hubs are handled by the dedicated hub distribution machinery — leave their ports untouched.
        if (degreeByElement.getOrDefault(elem.id(), 0) >= HUB_DEGREE_THRESHOLD) {
            return;
        }
        AbsoluteBendpointDto term = path.get(isSource ? 0 : path.size() - 1);
        // Only a terminal exactly on the perimeter is a candidate — the slot math and the perpendicular
        // stub reconstruction assume an on-line terminal.
        if (!isOnElementPerimeter(term, elem)) {
            return;
        }
        EdgeAttachmentCalculator.Face face =
                determineFaceFromTerminal(new int[]{term.x(), term.y()}, elem);
        boolean slotAlongY = (face == EdgeAttachmentCalculator.Face.LEFT
                || face == EdgeAttachmentCalculator.Face.RIGHT);
        double slot = slotAlongY ? term.y() : term.x();
        faceGroups.computeIfAbsent(elem.id() + "|" + face, k -> new ArrayList<>())
                .add(new PortRef(path, conn, isSource, elem, slotAlongY, slot));
    }

    /** Clusters a face's terminals by slot and separates every cluster that carries a real collision. */
    private void resolveFaceGroup(List<PortRef> refs,
            Map<String, List<AbsoluteBendpointDto>> routed,
            Map<String, ConnectionEndpoints> connectionMap) {
        if (refs.size() < 2) {
            return;
        }
        refs.sort((a, b) -> Double.compare(a.slot(), b.slot()));
        int i = 0;
        while (i < refs.size()) {
            double first = refs.get(i).slot();
            int j = i + 1;
            while (j < refs.size()
                    && Math.abs(refs.get(j).slot() - first) <= COINCIDENT_PORT_SLOT_TOLERANCE_PX) {
                j++;
            }
            if (j - i >= 2 && distinctConnectionCount(refs.subList(i, j)) >= 2) {
                // Avoid EVERY other terminal already on this face (its current slot), not just the
                // colliding cluster — otherwise a spread could land on a previously-distinct sibling and
                // manufacture a new collision. Current slots are re-read so an earlier cluster's moves
                // are respected.
                List<Double> fixedAvoid = new ArrayList<>();
                for (int x = 0; x < refs.size(); x++) {
                    if (x < i || x >= j) {
                        fixedAvoid.add(currentSlot(refs.get(x)));
                    }
                }
                resolveCluster(refs.subList(i, j), fixedAvoid, routed, connectionMap);
            }
            i = j;
        }
    }

    /** Distinct connection ids in a cluster (a self-referencing connection contributes only once). */
    private static int distinctConnectionCount(List<PortRef> cluster) {
        Set<String> ids = new HashSet<>();
        for (PortRef r : cluster) {
            ids.add(r.conn().connectionId());
        }
        return ids.size();
    }

    /** A terminal's live along-face slot, re-read from its (possibly already-moved) path. */
    private static double currentSlot(PortRef r) {
        List<AbsoluteBendpointDto> p = r.path();
        AbsoluteBendpointDto t = p.get(r.isSource() ? 0 : p.size() - 1);
        return r.slotAlongY() ? t.y() : t.x();
    }

    /**
     * Separates one coincident cluster: keeps the first member as the anchor and moves each other
     * member to a distinct free slot ≥ the floor from every already-occupied slot (both the fixed
     * non-cluster face ports and the committed cluster ports). When a member cannot move (its
     * relocation would break a gate) and only the anchor is committed, the anchor is moved off the
     * immovable member instead — so a movable/immovable pair (e.g. a clean stub colliding with a
     * face-hug stub) still separates.
     *
     * @param fixedAvoid current slots of every other terminal on this face (never moved by this cluster)
     */
    private void resolveCluster(List<PortRef> cluster, List<Double> fixedAvoid,
            Map<String, List<AbsoluteBendpointDto>> routed,
            Map<String, ConnectionEndpoints> connectionMap) {
        List<Double> occupied = new ArrayList<>(fixedAvoid);
        int anchorIdx = occupied.size();
        occupied.add(cluster.get(0).slot());
        for (int k = 1; k < cluster.size(); k++) {
            PortRef m = cluster.get(k);
            Double moved = trySpreadTerminal(m, occupied, routed, connectionMap);
            if (moved != null) {
                occupied.add(moved);
            } else if (occupied.size() == fixedAvoid.size() + 1) {
                // m is immovable and only the anchor has been committed → move the anchor off m instead,
                // still avoiding every fixed face slot.
                List<Double> anchorAvoid = new ArrayList<>(fixedAvoid);
                anchorAvoid.add(m.slot());
                Double anchorMoved = trySpreadTerminal(cluster.get(0), anchorAvoid, routed, connectionMap);
                if (anchorMoved != null) {
                    occupied.set(anchorIdx, anchorMoved);
                }
                occupied.add(m.slot());
            } else {
                occupied.add(m.slot());
            }
        }
    }

    /**
     * Attempts to move one terminal to a free on-line slot ≥ the distinguishability floor from every
     * {@code avoidSlots}, nearest its natural approach coordinate. Returns the new slot, or null when
     * the terminal does not collide, no distinguishable slot exists (too-short face — accept the
     * collision), or the relocation would break terminal orthogonality, add an edge crossing, or hit an
     * obstacle (in which case the path is restored byte-identical). The terminal stays on the face line,
     * so {@code TerminalAnchoring.preservesTerminalAnchoring} holds.
     */
    private Double trySpreadTerminal(PortRef m, List<Double> avoidSlots,
            Map<String, List<AbsoluteBendpointDto>> routed,
            Map<String, ConnectionEndpoints> connectionMap) {
        List<AbsoluteBendpointDto> path = m.path();
        if (path.size() < 2) {
            return null;
        }
        boolean isSource = m.isSource();
        boolean slotAlongY = m.slotAlongY();
        RoutingRect elem = m.elem();
        ConnectionEndpoints conn = m.conn();
        int termIdx = isSource ? 0 : path.size() - 1;
        int adjIdx = isSource ? 1 : path.size() - 2;
        AbsoluteBendpointDto term = path.get(termIdx);
        double slot = slotAlongY ? term.y() : term.x();

        // Gate 1: provable collision under the assessor's predicate.
        boolean collides = false;
        for (double s : avoidSlots) {
            if (Math.abs(s - slot) <= COINCIDENT_PORT_SLOT_TOLERANCE_PX) {
                collides = true;
                break;
            }
        }
        if (!collides) {
            return null;
        }

        // Gate 2: a free on-line slot ≥ the distinguishability floor from every avoided slot, within the
        // corner-margin-inset usable span, nearest the natural approach coordinate.
        int margin = EdgeAttachmentCalculator.DEFAULT_CORNER_MARGIN;
        double spanStart = (slotAlongY ? elem.y() : elem.x()) + margin;
        double spanEnd = (slotAlongY ? elem.y() + elem.height() : elem.x() + elem.width()) - margin;
        if (spanEnd <= spanStart) {
            return null;
        }
        RoutingRect far = isSource ? conn.target() : conn.source();
        double approach = slotAlongY ? far.centerY() : far.centerX();
        Double newSlot = chooseFreeSlot(avoidSlots, spanStart, spanEnd, approach,
                EdgeAttachmentCalculator.VISUAL_DISTINGUISHABILITY_THRESHOLD);
        if (newSlot == null) {
            return null; // face too short to seat a distinguishable port — accept the collision
        }
        int newSlotI = (int) Math.round(newSlot);
        if ((slotAlongY && newSlotI == term.y()) || (!slotAlongY && newSlotI == term.x())) {
            return null; // no movement needed
        }

        // Snapshot for revert, then relocate the terminal on the face line.
        List<AbsoluteBendpointDto> before = new ArrayList<>(path);
        int fixedAxis = slotAlongY ? term.x() : term.y();
        AbsoluteBendpointDto newTerm = slotAlongY
                ? new AbsoluteBendpointDto(fixedAxis, newSlotI)
                : new AbsoluteBendpointDto(newSlotI, fixedAxis);
        path.set(termIdx, newTerm);
        // Insert an L-corner so the terminal stub stays perpendicular to the face; redundant corners
        // are dropped by the collinear/dup cleanup below (equivalent to shifting the existing corner).
        AbsoluteBendpointDto adj = path.get(adjIdx);
        AbsoluteBendpointDto corner = slotAlongY
                ? new AbsoluteBendpointDto(adj.x(), newSlotI)
                : new AbsoluteBendpointDto(newSlotI, adj.y());
        path.add(isSource ? 1 : path.size() - 1, corner);
        // Clean up until stable: a multi-bend stub can leave a doubled-back spur that a single collinear
        // pass only partly resolves — loop so no redundant (collinear) bendpoint survives the relocation.
        int prevSize;
        do {
            prevSize = path.size();
            removeDuplicatePoints(path);
            removeCollinearPoints(path);
        } while (path.size() < prevSize);

        // Gate 3: reject if the spread breaks terminal orthogonality (degenerate corner → face-parallel
        // stub), introduces an edge crossing, or introduces an obstacle crossing.
        boolean nonOrthogonal = !terminalSegmentPerpendicular(path, isSource, slotAlongY);
        int crossingsBefore = crossingsAgainstOthers(before, conn, routed, connectionMap);
        int crossingsAfter = crossingsAgainstOthers(path, conn, routed, connectionMap);
        boolean obstacleHit = findFirstObstacleViolation(withCenters(path, conn), conn.obstacles()) != null;
        if (nonOrthogonal || crossingsAfter > crossingsBefore || obstacleHit) {
            path.clear();
            path.addAll(before);
            return null;
        }
        return (double) newSlotI;
    }

    /**
     * True when the relocated terminal's first segment is perpendicular to its face — horizontal for a
     * LEFT/RIGHT face (shared Y), vertical for a TOP/BOTTOM face (shared X). A path reduced to a single
     * point cannot express a terminal segment and is treated as non-perpendicular (revert).
     */
    private static boolean terminalSegmentPerpendicular(List<AbsoluteBendpointDto> path,
            boolean isSource, boolean slotAlongY) {
        if (path.size() < 2) {
            return false;
        }
        AbsoluteBendpointDto term = isSource ? path.get(0) : path.get(path.size() - 1);
        AbsoluteBendpointDto next = isSource ? path.get(1) : path.get(path.size() - 2);
        return slotAlongY ? term.y() == next.y() : term.x() == next.x();
    }

    /**
     * Picks the point in [{@code spanStart}, {@code spanEnd}] nearest {@code approach} that is at
     * least {@code minGap} from every occupied slot. Returns null when no such point exists (the
     * face cannot seat a distinguishable port), leaving the collision accepted.
     */
    static Double chooseFreeSlot(List<Double> occupied, double spanStart, double spanEnd,
            double approach, double minGap) {
        List<Double> sorted = new ArrayList<>(occupied);
        Collections.sort(sorted);
        Double best = null;
        double bestDist = Double.MAX_VALUE;
        // Candidate feasible intervals: before first, between consecutive, after last.
        double lo = spanStart;
        for (int i = 0; i <= sorted.size(); i++) {
            double intervalLo = (i == 0) ? spanStart : sorted.get(i - 1) + minGap;
            double intervalHi = (i == sorted.size()) ? spanEnd : sorted.get(i) - minGap;
            if (intervalLo > intervalHi) {
                continue;
            }
            double cand = Math.max(intervalLo, Math.min(approach, intervalHi));
            double dist = Math.abs(cand - approach);
            if (dist < bestDist) {
                bestDist = dist;
                best = cand;
            }
        }
        return best;
    }

    /** Prepends the source centre and appends the target centre to a bendpoint path. */
    private static List<AbsoluteBendpointDto> withCenters(List<AbsoluteBendpointDto> path,
            ConnectionEndpoints conn) {
        List<AbsoluteBendpointDto> full = new ArrayList<>(path.size() + 2);
        full.add(new AbsoluteBendpointDto(conn.source().centerX(), conn.source().centerY()));
        full.addAll(path);
        full.add(new AbsoluteBendpointDto(conn.target().centerX(), conn.target().centerY()));
        return full;
    }

    /** Counts edge crossings between one connection's full path and every other routed connection. */
    private static int crossingsAgainstOthers(List<AbsoluteBendpointDto> path, ConnectionEndpoints conn,
            Map<String, List<AbsoluteBendpointDto>> routed,
            Map<String, ConnectionEndpoints> connectionMap) {
        List<AbsoluteBendpointDto> self = withCenters(path, conn);
        int crossings = 0;
        for (Map.Entry<String, List<AbsoluteBendpointDto>> e : routed.entrySet()) {
            if (e.getKey().equals(conn.connectionId())) {
                continue;
            }
            ConnectionEndpoints other = connectionMap.get(e.getKey());
            if (other == null) {
                continue;
            }
            crossings += countOrthogonalCrossings(self, withCenters(e.getValue(), other));
        }
        return crossings;
    }

    /** Counts proper perpendicular crossings between the orthogonal segments of two polylines.
     *  <p>Deliberately counts only H×V interior crossings, not collinear same-axis overlaps: the gate
     *  compares this count before vs after a spread, and the collision being resolved is itself a
     *  collinear overlap of the mover with its sibling — folding collinear overlap into the count would
     *  let that expected pre-existing overlap mask a genuinely new crossing introduced by the move. A
     *  spread that merely runs flush along an unrelated trunk (a rare collinear coincidence, distinct
     *  from a crossing) is instead caught by the assessor's separate edge-coincidence metric; empirically
     *  the pass introduces none on the corpus or the live view.</p> */
    static int countOrthogonalCrossings(List<AbsoluteBendpointDto> a,
            List<AbsoluteBendpointDto> b) {
        int count = 0;
        for (int i = 0; i < a.size() - 1; i++) {
            for (int j = 0; j < b.size() - 1; j++) {
                if (orthSegmentsCross(a.get(i), a.get(i + 1), b.get(j), b.get(j + 1))) {
                    count++;
                }
            }
        }
        return count;
    }

    /** True when a horizontal and a vertical segment cross at an interior point. */
    private static boolean orthSegmentsCross(AbsoluteBendpointDto p1, AbsoluteBendpointDto p2,
            AbsoluteBendpointDto q1, AbsoluteBendpointDto q2) {
        boolean pHorizontal = p1.y() == p2.y();
        boolean pVertical = p1.x() == p2.x();
        boolean qHorizontal = q1.y() == q2.y();
        boolean qVertical = q1.x() == q2.x();
        if (pHorizontal && qVertical) {
            return strictlyBetween(q1.x(), p1.x(), p2.x()) && strictlyBetween(p1.y(), q1.y(), q2.y());
        }
        if (pVertical && qHorizontal) {
            return strictlyBetween(p1.x(), q1.x(), q2.x()) && strictlyBetween(q1.y(), p1.y(), p2.y());
        }
        return false;
    }

    /** True when {@code v} lies strictly between {@code a} and {@code b} (exclusive). */
    private static boolean strictlyBetween(int v, int a, int b) {
        return v > Math.min(a, b) && v < Math.max(a, b);
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

        // Re-run cleanup after path modification
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
     * cleanup stages shifted them. Determines the exit face from the
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
     * Guard helper: returns true iff {@code bp} is exactly on {@code elem}'s
     * perimeter at the 1-px-outside offset produced by
     * {@link EdgeAttachmentCalculator#computeAttachmentPoint}. Hub port
     * distribution places terminals on the perimeter by construction;
     * center-aligned BPs introduced by previous alignTerminalsWithCenter calls
     * do not. Used to skip the center-alignment overwrite for already-perimeter-
     * aligned distributed terminals so the hub distribution survives the full
     * pipeline.
     */
    static boolean isOnElementPerimeter(AbsoluteBendpointDto bp, RoutingRect elem) {
        return bp.x() == elem.x() - 1
                || bp.x() == elem.x() + elem.width() + 1
                || bp.y() == elem.y() - 1
                || bp.y() == elem.y() + elem.height() + 1;
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
     * Terminals-only rectification entry point. Pure geometry, no EMF.
     *
     * <p>Computes a new bendpoint list whose first and last segments are
     * orthogonal (axis-aligned with the element centers) by prepending and/or
     * appending at most one L-bend each, without touching any intermediate
     * bendpoint. Returns {@code null} when no change is needed (terminal
     * segments already within ≤5° of a cardinal axis).</p>
     *
     * <p>Algorithm: for each terminal, check if the center→firstRef (or
     * lastRef→center) segment is within the 5° angular tolerance; if not,
     * insert an L-bend whose position is chosen from the face that the
     * element exits/enters. The L-bend shares an axis with the element
     * center by construction, so Archi's center→firstBP segment is
     * automatically orthogonal. Intermediate BPs are copied through
     * verbatim — no post-processing helper (enforce, fixInterior,
     * removeCollinear) is invoked, which would risk erasing a genuine
     * intermediate BP when it lies on the same axis as the inserted L-bend.</p>
     *
     * @param source       source element rect
     * @param target       target element rect
     * @param existingAbs  current absolute bendpoints (Archi's storage form —
     *                     does NOT include terminal anchors)
     * @return new bendpoint list, or {@code null} when the connection's terminal
     *         segments are already orthogonal and no change is needed
     */
    public static List<AbsoluteBendpointDto> terminalsOnlyRectify(
            RoutingRect source, RoutingRect target,
            List<AbsoluteBendpointDto> existingAbs) {

        int srcCX = source.centerX();
        int srcCY = source.centerY();
        int tgtCX = target.centerX();
        int tgtCY = target.centerY();

        int firstRefX = existingAbs.isEmpty() ? tgtCX : existingAbs.get(0).x();
        int firstRefY = existingAbs.isEmpty() ? tgtCY : existingAbs.get(0).y();
        int lastRefX = existingAbs.isEmpty() ? srcCX
                : existingAbs.get(existingAbs.size() - 1).x();
        int lastRefY = existingAbs.isEmpty() ? srcCY
                : existingAbs.get(existingAbs.size() - 1).y();

        boolean srcOrtho = isWithinOrthogonalTolerance(srcCX, srcCY, firstRefX, firstRefY);
        boolean tgtOrtho = isWithinOrthogonalTolerance(tgtCX, tgtCY, lastRefX, lastRefY);
        if (srcOrtho && tgtOrtho) {
            return null;
        }

        List<AbsoluteBendpointDto> result = new ArrayList<>(existingAbs.size() + 2);
        result.addAll(existingAbs);

        if (!srcOrtho) {
            EdgeAttachmentCalculator.Face srcFace =
                    EdgeAttachmentCalculator.determineFace(source, firstRefX, firstRefY);
            AbsoluteBendpointDto lBend;
            if (srcFace == EdgeAttachmentCalculator.Face.LEFT
                    || srcFace == EdgeAttachmentCalculator.Face.RIGHT) {
                // Horizontal exit: maintain source center Y
                lBend = new AbsoluteBendpointDto(firstRefX, srcCY);
            } else {
                // Vertical exit: maintain source center X
                lBend = new AbsoluteBendpointDto(srcCX, firstRefY);
            }
            // Avoid prepending a duplicate of what is already at position 0
            if (result.isEmpty()
                    || result.get(0).x() != lBend.x()
                    || result.get(0).y() != lBend.y()) {
                result.add(0, lBend);
            }
        }

        // Re-evaluate target ortho against the list's new last ref. When the
        // list was originally empty and we just prepended a src L-bend that
        // happens to align with tgtCenter, the target is now orthogonal for
        // free and no append is needed.
        int newLastRefX = result.isEmpty() ? srcCX : result.get(result.size() - 1).x();
        int newLastRefY = result.isEmpty() ? srcCY : result.get(result.size() - 1).y();
        boolean tgtOrthoAfter =
                isWithinOrthogonalTolerance(tgtCX, tgtCY, newLastRefX, newLastRefY);

        if (!tgtOrthoAfter) {
            EdgeAttachmentCalculator.Face tgtFace =
                    EdgeAttachmentCalculator.determineFace(target, newLastRefX, newLastRefY);
            AbsoluteBendpointDto lBend;
            if (tgtFace == EdgeAttachmentCalculator.Face.LEFT
                    || tgtFace == EdgeAttachmentCalculator.Face.RIGHT) {
                // Horizontal entry: maintain target center Y
                lBend = new AbsoluteBendpointDto(newLastRefX, tgtCY);
            } else {
                // Vertical entry: maintain target center X
                lBend = new AbsoluteBendpointDto(tgtCX, newLastRefY);
            }
            AbsoluteBendpointDto tail =
                    result.isEmpty() ? null : result.get(result.size() - 1);
            if (tail == null || tail.x() != lBend.x() || tail.y() != lBend.y()) {
                result.add(lBend);
            }
        }

        if (result.equals(existingAbs)) {
            return null;
        }
        return result;
    }

    /**
     * Terminals-only entry point that orthogonalises the terminal segments AND enforces a minimum
     * perpendicular egress clearance. This is the composition the EMF-aware terminals-only dispatcher
     * calls: {@link #terminalsOnlyRectify} followed by {@link #terminalsOnlyEnforceEgressClearance}.
     *
     * <p>The egress step also runs when {@code terminalsOnlyRectify} returns {@code null} (the terminal
     * segments were already within the orthogonal tolerance): an ELK-placed bendpoint can sit a couple
     * of pixels off the face with an already-orthogonal terminal segment, so the route hugs the face it
     * just exited without {@code terminalsOnlyRectify} having anything to rectify. Running the clearance
     * over {@code existingAbs} in that case lets the off-face hug be lifted even though no rectification
     * was needed.</p>
     *
     * @return the final bendpoint list to commit, or {@code null} only when the fully processed path is
     *         byte-equal to {@code existingAbs} (a genuine no-op — the dispatcher counts it as already
     *         orthogonal). Non-null is returned whenever the result differs from the input: after
     *         rectification, after an egress lift, OR after the interior collinear collapse removes a
     *         pre-existing redundant point even though rectification and egress were both no-ops. When
     *         non-null, the terminal segments are orthogonal, any first exterior trunk that was hugging
     *         its departed face has been pushed clear, and no interior collinear point remains.
     */
    public static List<AbsoluteBendpointDto> terminalsOnlyRectifyAndClearEgress(
            RoutingRect source, RoutingRect target,
            List<AbsoluteBendpointDto> existingAbs) {
        List<AbsoluteBendpointDto> rectified = terminalsOnlyRectify(source, target, existingAbs);
        List<AbsoluteBendpointDto> base = (rectified != null) ? rectified : existingAbs;
        List<AbsoluteBendpointDto> cleared =
                terminalsOnlyEnforceEgressClearance(source, target, base);
        // Interior collinear collapse. terminalsOnlyRectify prepends/appends an L-bend without any
        // collinear sweep (by design, to avoid touching intermediate BPs), so an inserted L-bend that
        // lands collinear with the existing trunk leaves an exactly-collinear INTERIOR bendpoint that the
        // full router would have removed. Sweep it here. This removes only interior points (the sweep
        // needs a bendpoint to be the middle of three), so the prepended/appended terminal L-bends
        // themselves are never removed — the terminals-only orthogonal-egress contract is preserved. Runs
        // even when rectify/egress were no-ops so a pre-existing interior survivor is still collapsed;
        // the removal is geometrically invisible (same polyline, one fewer stored vertex on a straight run).
        List<AbsoluteBendpointDto> collapsed = new ArrayList<>(cleared);
        removeCollinearPoints(collapsed);
        // Genuine no-op only when the final path is byte-equal to the stored input.
        if (collapsed.equals(existingAbs)) {
            return null;
        }
        return collapsed;
    }

    /**
     * Enforces a minimum perpendicular egress clearance on a terminals-only path: when a terminal
     * departs an element face and its first exterior trunk runs <b>parallel to that face within</b>
     * {@link TerminalEgressClearancePass#OFF_FACE_MIN_STUB_PX} of it (the off-face parallel hug the
     * assessor flags via {@code countOffFaceParallelTerminals}), the hugging trunk is pushed
     * perpendicular-out so it clears the face by {@link TerminalEgressClearancePass#HEALTHY_PARALLEL_GAP_PX}.
     *
     * <p>This mirrors the full-route {@link TerminalEgressClearancePass} transform in the terminals-only
     * (center-based, no terminal-anchor) geometry model. The full-route pass cannot be reused directly
     * here: it requires the hug to be the terminal-incident segment on a path whose first point is a
     * terminal anchor on the face line, whereas a terminals-only path renders {@code elementCenter →
     * L-bend → trunk}, so the terminal-incident segment is a proper perpendicular egress and the hug is
     * the trunk one segment further in.</p>
     *
     * <p>Detection mirrors the assessor oracle exactly (the {@code OFF_FACE_MIN_STUB_PX} threshold), so a
     * terminal that already clears the face by &ge; that distance is left untouched — the returned list is
     * reference-equal to {@code rectified}. The push targets {@code HEALTHY_PARALLEL_GAP_PX} for a healthy
     * margin. A lift that would make the egress segment or the segment beyond the moved trunk
     * non-orthogonal is declined (returns {@code rectified} unchanged), so the result is always fully
     * orthogonal at the terminals; the dispatcher's interior/zigzag/obstacle/crossing vetoes guard the
     * rest. Idempotent: a lifted trunk clears the threshold, so a second pass does not re-fire.</p>
     *
     * <p>Pure geometry — no EMF/SWT/PDE; callable from standard JUnit.</p>
     *
     * @param source    source element rect
     * @param target    target element rect
     * @param rectified  the terminals-only bendpoint list (absolute coords; no terminal anchors)
     * @return a new list with hugging terminal trunks lifted, or {@code rectified} unchanged
     *         (reference-equal) when nothing hugged or every candidate lift was declined
     */
    public static List<AbsoluteBendpointDto> terminalsOnlyEnforceEgressClearance(
            RoutingRect source, RoutingRect target,
            List<AbsoluteBendpointDto> rectified) {
        if (rectified == null || rectified.size() < 2) {
            return rectified;
        }
        List<AbsoluteBendpointDto> work = new ArrayList<>(rectified);
        boolean changed = false;
        if (tryLiftTerminalHug(work, source, true, target)) {
            changed = true;
        }
        if (tryLiftTerminalHug(work, target, false, source)) {
            changed = true;
        }
        return changed ? work : rectified;
    }

    /**
     * Attempts to lift one terminal's hugging trunk in {@code work} (mutated in place on success).
     * Source side inspects {@code work[0]} (exit point) and {@code work[1]} (trunk end); target side
     * inspects {@code work[last]} and {@code work[last-1]}. Returns true iff a lift was applied.
     *
     * <p>Fires only when: the trunk is axis-aligned and parallel to a resolved departure face; the exit
     * point sits on/beyond that face within the face's parallel extent; the perpendicular clearance is
     * below {@link TerminalEgressClearancePass#OFF_FACE_MIN_STUB_PX}; and the lift keeps both the egress
     * segment (element center → exit point) and the segment beyond the moved trunk axis-aligned. The
     * element center is the connection's rendered terminal anchor (ChopboxAnchor), so the egress check
     * uses {@code elem.centerX()/centerY()}.</p>
     *
     * <p>The segment beyond the moved trunk end runs to the adjacent interior bendpoint when one exists;
     * on a short two-bendpoint path it runs to the OPPOSITE element's centre (the trunk end is then also
     * the opposite terminal's bendpoint, whose terminal segment to that centre must stay orthogonal).
     * {@code opposite} supplies that implicit anchor so the orthogonality check is not skipped — a lift
     * that would bend the trunk end's run to the opposite centre into a diagonal is declined.</p>
     */
    private static boolean tryLiftTerminalHug(
            List<AbsoluteBendpointDto> work, RoutingRect elem, boolean sourceSide,
            RoutingRect opposite) {
        int n = work.size();
        if (elem == null || n < 2) {
            return false;
        }
        int bpIdx = sourceSide ? 0 : n - 1;
        int trunkIdx = sourceSide ? 1 : n - 2;
        AbsoluteBendpointDto bp = work.get(bpIdx);
        AbsoluteBendpointDto trunkEnd = work.get(trunkIdx);

        int trunkDx = trunkEnd.x() - bp.x();
        int trunkDy = trunkEnd.y() - bp.y();
        boolean trunkHorizontal = trunkDy == 0 && trunkDx != 0;
        boolean trunkVertical = trunkDx == 0 && trunkDy != 0;
        if (!trunkHorizontal && !trunkVertical) {
            return false; // diagonal or degenerate trunk — not a clean parallel hug
        }

        int left = elem.x();
        int right = elem.x() + elem.width();
        int top = elem.y();
        int bottom = elem.y() + elem.height();

        int faceLine;
        int awaySign;
        boolean perpIsY; // true → push along Y (parallel face TOP/BOTTOM); false → push along X
        if (trunkHorizontal) {
            // Trunk parallel to a TOP/BOTTOM face → the exit must depart vertically. Require the exit
            // point within the face's horizontal extent, else the assessor would resolve a side face.
            if (bp.x() < left || bp.x() > right) {
                return false;
            }
            if (bp.y() >= bottom) {
                faceLine = bottom;
                awaySign = +1;
            } else if (bp.y() <= top) {
                faceLine = top;
                awaySign = -1;
            } else {
                return false; // exit point inside the vertical band — interior / side departure
            }
            perpIsY = true;
            if (Math.abs(bp.y() - faceLine) >= TerminalEgressClearancePass.OFF_FACE_MIN_STUB_PX) {
                return false; // already clears the face — leave byte-identical
            }
        } else {
            // Trunk parallel to a LEFT/RIGHT face → exit departs horizontally.
            if (bp.y() < top || bp.y() > bottom) {
                return false;
            }
            if (bp.x() >= right) {
                faceLine = right;
                awaySign = +1;
            } else if (bp.x() <= left) {
                faceLine = left;
                awaySign = -1;
            } else {
                return false;
            }
            perpIsY = false;
            if (Math.abs(bp.x() - faceLine) >= TerminalEgressClearancePass.OFF_FACE_MIN_STUB_PX) {
                return false;
            }
        }

        int pushed = faceLine + awaySign * TerminalEgressClearancePass.HEALTHY_PARALLEL_GAP_PX;
        AbsoluteBendpointDto newBp = perpIsY
                ? new AbsoluteBendpointDto(bp.x(), pushed)
                : new AbsoluteBendpointDto(pushed, bp.y());
        AbsoluteBendpointDto newTrunkEnd = perpIsY
                ? new AbsoluteBendpointDto(trunkEnd.x(), pushed)
                : new AbsoluteBendpointDto(pushed, trunkEnd.y());

        // Orthogonality backstop. (1) The egress segment center → newBp must stay axis-aligned (it is
        // perpendicular in the normal hug case; if the egress was somehow parallel, declining here keeps
        // the result orthogonal). (2) The segment from the moved trunk end to its further neighbour must
        // stay axis-aligned, so moving the trunk end does not create a diagonal where it turns.
        if (newBp.x() != elem.centerX() && newBp.y() != elem.centerY()) {
            return false;
        }
        int neighbourIdx = sourceSide ? trunkIdx + 1 : trunkIdx - 1;
        AbsoluteBendpointDto neighbour;
        if (neighbourIdx >= 0 && neighbourIdx < n) {
            neighbour = work.get(neighbourIdx);
        } else if (opposite != null) {
            // No interior bendpoint beyond the trunk end (short two-bendpoint path): the trunk end
            // connects directly to the opposite element's centre, so that centre is the segment's far
            // endpoint the lift must keep orthogonal.
            neighbour = new AbsoluteBendpointDto(opposite.centerX(), opposite.centerY());
        } else {
            neighbour = null;
        }
        if (neighbour != null
                && newTrunkEnd.x() != neighbour.x() && newTrunkEnd.y() != neighbour.y()) {
            return false;
        }

        work.set(bpIdx, newBp);
        work.set(trunkIdx, newTrunkEnd);
        return true;
    }

    /**
     * Mirror of {@code LayoutQualityAssessor.PERIMETER_TOLERANCE_PX} (0.5) — the inward
     * tolerance used to decide whether a bendpoint lies <b>strictly inside</b> an element
     * (and would therefore register as an interior termination, assessor metric M2).
     */
    private static final double TERMINAL_INTERIOR_TOLERANCE_PX = 0.5;

    /**
     * Returns true if the rectified terminals-only path would create an
     * <b>interior termination</b> at either terminal — its first stored bendpoint is
     * strictly inside the source element, or its last stored bendpoint is strictly inside
     * the target element. This mirrors exactly what the assessor's M2 detector
     * ({@code LayoutQualityAssessor.countInteriorTerminations} via {@code isStrictlyInside})
     * flags: M2 inspects {@code path.get(1)} (first BP after the source centre) and
     * {@code path.get(size-2)} (last BP before the target centre); for the terminals-only
     * full path {@code [srcCentre, rectified…, tgtCentre]} those are exactly the first and
     * last entries of {@code rectified}. Used by the terminals-only interior-termination
     * veto so the router can never raise a view's interior-termination count.
     *
     * <p>Parity note: {@code source}/{@code target} are the int-precision {@link RoutingRect}s
     * that {@link #terminalsOnlyRectify} computes the L-bend against, so the veto is
     * <em>internally exact</em> — it rejects exactly the interior points rectify could produce.
     * For deeply nested elements under an odd-dimension parent these int bounds can differ by
     * &le;1px from the assessor's double-precision {@code AssessmentNode} bounds, so on such
     * (rare) views the veto and assess-layout M2 could disagree on a boundary-grazing
     * bendpoint. Flat ELK views — the target use-case — use top-level elements where the two
     * are identical.</p>
     *
     * @param source    the source element bounds
     * @param target    the target element bounds
     * @param rectified the rectified bendpoint list returned by
     *                  {@link #terminalsOnlyRectify} (absolute coords; never the centres)
     * @return true if the first BP is strictly inside {@code source} or the last BP is
     *         strictly inside {@code target}
     */
    public static boolean terminalsOnlyTerminatesInside(
            RoutingRect source, RoutingRect target,
            List<AbsoluteBendpointDto> rectified) {
        if (rectified == null || rectified.isEmpty()) {
            return false;
        }
        return isStrictlyInside(rectified.get(0), source)
                || isStrictlyInside(rectified.get(rectified.size() - 1), target);
    }

    /**
     * Strict point-in-rectangle test with a 0.5px inward tolerance, byte-identical to
     * {@code LayoutQualityAssessor.isStrictlyInside} and
     * {@code HubPerimeterRoutingStage.isStrictlyInside} (a bendpoint on the perimeter line
     * is NOT strictly inside). Kept private; reuse via {@link #terminalsOnlyTerminatesInside}.
     */
    private static boolean isStrictlyInside(AbsoluteBendpointDto bp, RoutingRect elem) {
        if (bp == null || elem == null) {
            return false;
        }
        double tol = TERMINAL_INTERIOR_TOLERANCE_PX;
        return bp.x() > elem.x() + tol
                && bp.x() < elem.x() + elem.width() - tol
                && bp.y() > elem.y() + tol
                && bp.y() < elem.y() + elem.height() - tol;
    }

    /** Mirror of {@code LayoutQualityAssessor.ZIGZAG_AXIS_TOLERANCE_PX} (1.0). */
    private static final double ZIGZAG_AXIS_TOLERANCE_PX = 1.0;
    /** Mirror of {@code LayoutQualityAssessor.ZIGZAG_MIN_DELTA_PX} (1.0). */
    private static final double ZIGZAG_MIN_DELTA_PX = 1.0;

    /**
     * Returns true if a terminals-only rectification would <b>introduce</b> a zigzag/reversal
     * that the original path did not have — i.e. the new full path contains a zigzag triple
     * and the old one did not. This is the sibling of the interior-termination veto: the same
     * terminal L-bend insertion can create a Tier-1R zigzag (M3) instead of an interior
     * termination, so terminals-only must veto it too (widened scope).
     *
     * <p>Comparison (not absolute) so a connection whose ELK <em>body</em> already zigzags is
     * not vetoed for a defect terminals-only did not cause. Both paths are the assessment form
     * {@code [srcCentre, bendpoints…, tgtCentre]} — exactly what M3 consumes.</p>
     *
     * @param oldFullPath the pre-rectification full path. A null old path is treated as
     *                    clean (no pre-existing zigzag), so the veto fires conservatively if
     *                    {@code newFullPath} has a zigzag — i.e. null old → "introduced".
     * @param newFullPath the post-rectification full path
     * @return true iff {@code newFullPath} has a zigzag triple and {@code oldFullPath} does not
     */
    public static boolean terminalsOnlyIntroducesZigzag(
            List<double[]> oldFullPath, List<double[]> newFullPath) {
        return pathHasZigzag(newFullPath) && !pathHasZigzag(oldFullPath);
    }

    /**
     * True if {@code path} contains at least one zigzag/reversal triple, byte-identical to
     * {@code LayoutQualityAssessor.countZigzags} (M3): three consecutive points sharing an
     * axis within {@link #ZIGZAG_AXIS_TOLERANCE_PX} with opposite-sign deltas on the other
     * axis, both &gt; {@link #ZIGZAG_MIN_DELTA_PX}.
     */
    public static boolean pathHasZigzag(List<double[]> path) {
        if (path == null || path.size() < 3) {
            return false;
        }
        for (int i = 0; i < path.size() - 2; i++) {
            if (isZigzagTriple(path.get(i), path.get(i + 1), path.get(i + 2))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isZigzagTriple(double[] a, double[] b, double[] c) {
        double tol = ZIGZAG_AXIS_TOLERANCE_PX;
        double minDelta = ZIGZAG_MIN_DELTA_PX;
        boolean sharedX = Math.abs(a[0] - b[0]) <= tol && Math.abs(b[0] - c[0]) <= tol;
        if (sharedX) {
            double dy1 = b[1] - a[1];
            double dy2 = c[1] - b[1];
            if (Math.abs(dy1) > minDelta && Math.abs(dy2) > minDelta
                    && Math.signum(dy1) != Math.signum(dy2)) {
                return true;
            }
        }
        boolean sharedY = Math.abs(a[1] - b[1]) <= tol && Math.abs(b[1] - c[1]) <= tol;
        if (sharedY) {
            double dx1 = b[0] - a[0];
            double dx2 = c[0] - b[0];
            if (Math.abs(dx1) > minDelta && Math.abs(dx2) > minDelta
                    && Math.signum(dx1) != Math.signum(dx2)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWithinOrthogonalTolerance(int ax, int ay, int bx, int by) {
        double dx = Math.abs(bx - ax);
        double dy = Math.abs(by - ay);
        if (dx < 1e-9 && dy < 1e-9) return true;
        double angleDeg = Math.toDegrees(Math.atan2(dy, dx));
        double deviation = Math.min(angleDeg, 90.0 - angleDeg);
        return deviation <= 5.0;
    }

    /**
     * Post-pipeline safety net: ensures terminal segments are orthogonal.
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
     * Post-pipeline ChopboxAnchor alignment: ensures first/last BPs share
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

        // Guard: when the terminal is already on the element perimeter at a
        // position produced by EdgeAttachmentCalculator.computeAttachmentPoint,
        // the line from element center to first BP already exits through the
        // perimeter at (or within 1 px of) the distributed port coordinate.
        // Prepending a center-aligned BP here collapses the visual exit onto
        // the face midpoint and destroys hub port distribution.
        boolean sourceOnPerimeter = isOnElementPerimeter(first, source);

        EdgeAttachmentCalculator.Face sourceFace = determineFaceFromTerminal(
                new int[]{first.x(), first.y()}, source);

        if (sourceFace == EdgeAttachmentCalculator.Face.LEFT
                || sourceFace == EdgeAttachmentCalculator.Face.RIGHT) {
            // Horizontal exit — need same Y as center
            if (first.y() != scy && !sourceOnPerimeter) {
                path.add(0, new AbsoluteBendpointDto(first.x(), scy));
                alignments++;
            }
        } else {
            // Vertical exit — need same X as center
            if (first.x() != scx && !sourceOnPerimeter) {
                path.add(0, new AbsoluteBendpointDto(scx, first.y()));
                alignments++;
            }
        }

        // Target side: ensure BP[n-1] shares coordinate with target center
        AbsoluteBendpointDto last = path.get(path.size() - 1);
        RoutingRect target = conn.target();
        int tcx = target.centerX();
        int tcy = target.centerY();

        // Guard (target side): same rationale as source side.
        boolean targetOnPerimeter = isOnElementPerimeter(last, target);

        EdgeAttachmentCalculator.Face targetFace = determineFaceFromTerminal(
                new int[]{last.x(), last.y()}, target);

        if (targetFace == EdgeAttachmentCalculator.Face.LEFT
                || targetFace == EdgeAttachmentCalculator.Face.RIGHT) {
            if (last.y() != tcy && !targetOnPerimeter) {
                path.add(new AbsoluteBendpointDto(last.x(), tcy));
                alignments++;
            }
        } else {
            if (last.x() != tcx && !targetOnPerimeter) {
                path.add(new AbsoluteBendpointDto(tcx, last.y()));
                alignments++;
            }
        }
        return alignments;
    }

    /**
     * Fixes center-terminated paths where a terminal BP is at element center coordinates
     * ChopboxAnchor draws from element center to first/last BP — if the BP
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
     * Post-processing stages 4.7g–4.7i can shift BPs back inside elements
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
            // Insert L-bend if repositioning broke orthogonality
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
            // Insert L-bend if repositioning broke orthogonality
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
     * Corrects endpoint pass-throughs introduced by pipeline stages.
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
     * non-terminal segment. Mirrors {@link #hasEndpointPassThrough} logic
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
     * terminal segments (Phase B). When a connection's routed path clips
     * through its own source or target element, this method:
     * <ol>
     *   <li>Tries alternative faces in angular proximity order</li>
     *   <li>For each candidate face, builds a re-routed path with a clearance waypoint</li>
     *   <li>Removes old terminal-adjacent segments inside the element</li>
     *   <li>Connects the clearance waypoint to the first external BP</li>
     * </ol>
     *
     * <p>Unlike the earlier approach which only swapped the terminal BP (creating a
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

        // Try alternative faces in angular proximity order (consistent with Phase A ordering)
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
     * avoids crossing through the given element.
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
     * inserts corrective BPs to detour around the element.
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
     * Snaps near-aligned connections to straight segments.
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
     * Removes bendpoints that create segments passing through obstacles.
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
     * Read-only check: returns the first obstacle crossed by any path segment, or null if clean.
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
     * Widened to {@code public} for terminals-only obstacle veto reuse.
     */
    public static boolean segmentIntersectsAnyObstacle(int x1, int y1, int x2, int y2,
            List<RoutingRect> obstacles) {
        return findFirstIntersectedObstacle(x1, y1, x2, y2, obstacles) != null;
    }

    /**
     * Ensures consecutive bendpoints form orthogonal segments (Pattern 3).
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
     * Post-simplification correction for source-side face-hugging segments.
     *
     * <p>Detects when {@code path[1]} shares the source face-line coordinate with
     * {@code path[0]} (creating a segment that runs parallel to the source face
     * instead of moving into a corridor) and redirects the interior BP to the
     * midpoint of the nearest obstacle-free corridor.
     *
     * <p>Only modifies interior BPs — terminal anchors ({@code path[0]}) are untouched.
     *
     * @param path            mutable path list (at least 3 BPs for a hug to exist)
     * @param obstacles       per-connection obstacle list
     * @param sourceAnchoring the source terminal's face anchoring
     * @param source          the source element rect
     * @return true if a face-hug was corrected
     */
    static boolean correctSourceSelfHug(List<AbsoluteBendpointDto> path,
            List<RoutingRect> obstacles, TerminalAnchoring sourceAnchoring,
            RoutingRect source) {
        if (path.size() < 3) {
            return false;
        }
        int faceLine = sourceAnchoring.lineCoordinate(source);
        AbsoluteBendpointDto bp0 = path.get(0);
        AbsoluteBendpointDto bp1 = path.get(1);

        boolean isHug;
        int hugLength;
        if (sourceAnchoring.face() == EdgeAttachmentCalculator.Face.LEFT
                || sourceAnchoring.face() == EdgeAttachmentCalculator.Face.RIGHT) {
            // Vertical face: hug when bp0.x == bp1.x == faceLine
            isHug = bp0.x() == faceLine && bp1.x() == faceLine;
            hugLength = Math.abs(bp1.y() - bp0.y());
        } else {
            // Horizontal face: hug when bp0.y == bp1.y == faceLine
            isHug = bp0.y() == faceLine && bp1.y() == faceLine;
            hugLength = Math.abs(bp1.x() - bp0.x());
        }

        // 20px threshold filters trivial 1-2px noise from rounding/snapping;
        // genuine face-hugs are 58px+ (smallest exemplar: API Gateway→ATM).
        if (!isHug || hugLength < 20) {
            return false;
        }

        // Find corridor midpoint: scan obstacles to find the nearest boundary
        // in the direction away from the source face, then place bp1 at the
        // midpoint between face line and that boundary.
        Integer corridorCoord = findCorridorMidpoint(faceLine, sourceAnchoring.face(),
                obstacles);
        if (corridorCoord == null) {
            return false; // no clear corridor found
        }

        // Replace bp1's face-line coordinate with the corridor midpoint
        AbsoluteBendpointDto corrected;
        if (sourceAnchoring.face() == EdgeAttachmentCalculator.Face.LEFT
                || sourceAnchoring.face() == EdgeAttachmentCalculator.Face.RIGHT) {
            corrected = new AbsoluteBendpointDto(corridorCoord, bp1.y());
        } else {
            corrected = new AbsoluteBendpointDto(bp1.x(), corridorCoord);
        }

        // Insert corner BP to maintain orthogonality: bp0 -> new corner -> corrected bp1
        // The corner is at (corridorCoord, bp0.y) for LEFT/RIGHT or (bp0.x, corridorCoord) for TOP/BOTTOM
        AbsoluteBendpointDto corner;
        if (sourceAnchoring.face() == EdgeAttachmentCalculator.Face.LEFT
                || sourceAnchoring.face() == EdgeAttachmentCalculator.Face.RIGHT) {
            corner = new AbsoluteBendpointDto(corridorCoord, bp0.y());
        } else {
            corner = new AbsoluteBendpointDto(bp0.x(), corridorCoord);
        }

        // Verify the two new orthogonal segments (bp0->corner and corner->corrected)
        // are obstacle-free. The corrected->bp2 segment is not checked because the
        // corridor midpoint is by construction between the face and the nearest
        // obstacle, so the corrected position can only improve clearance vs the
        // original face-hugging bp1 position.
        if (segmentIntersectsAnyObstacle(bp0.x(), bp0.y(), corner.x(), corner.y(), obstacles)
                || segmentIntersectsAnyObstacle(corner.x(), corner.y(), corrected.x(), corrected.y(), obstacles)) {
            return false; // corridor blocked
        }

        // Replace bp1 with corner + corrected
        path.set(1, corner);
        path.add(2, corrected);

        logger.debug("B72-a source self-hug corrected: face={}, faceLine={}, corridor={}",
                sourceAnchoring.face(), faceLine, corridorCoord);
        return true;
    }

    /**
     * Symmetric target-side face-hug correction.
     *
     * <p>Same logic as {@link #correctSourceSelfHug} but applied to the last
     * interior BP ({@code path[last-1]}) when it shares the target face line
     * with {@code path[last]}.
     */
    static boolean correctTargetSelfHug(List<AbsoluteBendpointDto> path,
            List<RoutingRect> obstacles, TerminalAnchoring targetAnchoring,
            RoutingRect target) {
        if (path.size() < 3) {
            return false;
        }
        int lastIdx = path.size() - 1;
        int faceLine = targetAnchoring.lineCoordinate(target);
        AbsoluteBendpointDto bpLast = path.get(lastIdx);
        AbsoluteBendpointDto bpPrev = path.get(lastIdx - 1);

        boolean isHug;
        int hugLength;
        if (targetAnchoring.face() == EdgeAttachmentCalculator.Face.LEFT
                || targetAnchoring.face() == EdgeAttachmentCalculator.Face.RIGHT) {
            isHug = bpLast.x() == faceLine && bpPrev.x() == faceLine;
            hugLength = Math.abs(bpLast.y() - bpPrev.y());
        } else {
            isHug = bpLast.y() == faceLine && bpPrev.y() == faceLine;
            hugLength = Math.abs(bpLast.x() - bpPrev.x());
        }

        // 20px threshold filters trivial 1-2px noise from rounding/snapping;
        // genuine face-hugs are 58px+ (smallest exemplar: API Gateway→ATM).
        if (!isHug || hugLength < 20) {
            return false;
        }

        Integer corridorCoord = findCorridorMidpoint(faceLine, targetAnchoring.face(),
                obstacles);
        if (corridorCoord == null) {
            return false;
        }

        AbsoluteBendpointDto corrected;
        if (targetAnchoring.face() == EdgeAttachmentCalculator.Face.LEFT
                || targetAnchoring.face() == EdgeAttachmentCalculator.Face.RIGHT) {
            corrected = new AbsoluteBendpointDto(corridorCoord, bpPrev.y());
        } else {
            corrected = new AbsoluteBendpointDto(bpPrev.x(), corridorCoord);
        }

        // Insert corner BP for orthogonality
        AbsoluteBendpointDto corner;
        if (targetAnchoring.face() == EdgeAttachmentCalculator.Face.LEFT
                || targetAnchoring.face() == EdgeAttachmentCalculator.Face.RIGHT) {
            corner = new AbsoluteBendpointDto(corridorCoord, bpLast.y());
        } else {
            corner = new AbsoluteBendpointDto(bpLast.x(), corridorCoord);
        }

        // Verify the two new orthogonal segments are obstacle-free (same rationale
        // as correctSourceSelfHug — corridor midpoint is between face and nearest
        // obstacle by construction).
        if (segmentIntersectsAnyObstacle(corrected.x(), corrected.y(), corner.x(), corner.y(), obstacles)
                || segmentIntersectsAnyObstacle(corner.x(), corner.y(), bpLast.x(), bpLast.y(), obstacles)) {
            return false;
        }

        // Replace bpPrev with corrected + corner
        path.set(lastIdx - 1, corrected);
        path.add(lastIdx, corner);

        logger.debug("B72-a target self-hug corrected: face={}, faceLine={}, corridor={}",
                targetAnchoring.face(), faceLine, corridorCoord);
        return true;
    }

    /**
     * Finds the midpoint of the nearest obstacle-free corridor perpendicular to
     * the given face. Scans obstacles to find the nearest boundary in the outward
     * direction from the face, then returns the midpoint between face line and
     * that boundary.
     *
     * @param faceLine  the face-line coordinate (1px outside the element edge)
     * @param face      which face the terminal is on
     * @param obstacles per-connection obstacle list
     * @return the corridor midpoint coordinate, or null if no clear corridor found
     */
    static Integer findCorridorMidpoint(int faceLine, EdgeAttachmentCalculator.Face face,
            List<RoutingRect> obstacles) {
        // Find the nearest obstacle boundary in the outward direction from the face.
        // No parallel-range overlap check — any obstacle in the outward direction
        // defines a conservative corridor boundary. This handles cases where the
        // target's ancestor group (the natural corridor wall) is excluded from the
        // per-connection obstacle list.
        int nearestBoundary = Integer.MAX_VALUE;

        for (RoutingRect obs : obstacles) {
            int obsBoundary;

            switch (face) {
                case LEFT:
                    obsBoundary = obs.x() + obs.width();
                    if (obsBoundary < faceLine) {
                        int gap = faceLine - obsBoundary;
                        if (gap < nearestBoundary) {
                            nearestBoundary = gap;
                        }
                    }
                    break;
                case RIGHT:
                    obsBoundary = obs.x();
                    if (obsBoundary > faceLine) {
                        int gap = obsBoundary - faceLine;
                        if (gap < nearestBoundary) {
                            nearestBoundary = gap;
                        }
                    }
                    break;
                case TOP:
                    obsBoundary = obs.y() + obs.height();
                    if (obsBoundary < faceLine) {
                        int gap = faceLine - obsBoundary;
                        if (gap < nearestBoundary) {
                            nearestBoundary = gap;
                        }
                    }
                    break;
                case BOTTOM:
                    obsBoundary = obs.y();
                    if (obsBoundary > faceLine) {
                        int gap = obsBoundary - faceLine;
                        if (gap < nearestBoundary) {
                            nearestBoundary = gap;
                        }
                    }
                    break;
            }
        }

        if (nearestBoundary == Integer.MAX_VALUE || nearestBoundary < 150) {
            // No clear corridor, or corridor too narrow for a meaningful redirect.
            // Threshold 150px ensures the midpoint shift is at least 75px — below
            // this, the correction adds corners to a clean perimeter-detour path
            // without visible improvement (V7 BE→RelMgr at gap=118px is the
            // archetypal case: 4-BP path inflated to 6 BPs with 59px jogs).
            return null;
        }

        // Compute corridor midpoint
        switch (face) {
            case LEFT:
                return faceLine - nearestBoundary / 2;
            case RIGHT:
                return faceLine + nearestBoundary / 2;
            case TOP:
                return faceLine - nearestBoundary / 2;
            case BOTTOM:
                return faceLine + nearestBoundary / 2;
            default:
                return null;
        }
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

    // Old endpoint-based methods (resolveCoincidentSegments, resolveCorridorCoincidence,
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
     * Runs A* path search with corridor occupancy awareness.
     * Package-visible for test overriding.
     */
    List<VisNode> findPath(OrthogonalVisibilityGraph graph, VisNode sourcePort, VisNode targetPort,
            List<RoutingRect> groupBoundaries, CorridorOccupancyTracker occupancyTracker) {
        VisibilityGraphRouter router = new VisibilityGraphRouter(bendPenalty, congestionWeight,
                VisibilityGraphRouter.DEFAULT_CLEARANCE_WEIGHT,
                VisibilityGraphRouter.DEFAULT_DIRECTIONALITY_WEIGHT,
                groupBoundaries, this.occupancyWeight);
        return router.findPath(graph, sourcePort, targetPort, occupancyTracker);
    }

    /**
     * Computes a straight-line crossing estimate by drawing imaginary direct lines between
     * source and target element centers for all connections and counting intersections.
     * O(n^2) for n connections — acceptable for typical views with &lt;50 connections.
     *
     * @param sourceCenters list of [x, y] source element centers
     * @param targetCenters list of [x, y] target element centers
     * Builds the routing order for connections: descending Manhattan distance,
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
     * Parametric segment-segment intersection test.
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
     * relative to the straight-line estimate.
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
     * Enforces minimum clearance between intermediate bendpoints and obstacle boundaries.
     * Skips terminal BPs (first and last). For each connection, the source and target elements
     * are excluded from obstacle checking (only third-party obstacles are checked).
     *
     * <p>Orthogonality-preserving nudging. After nudging a BP, any perpendicular
     * coordinate change is propagated to the adjacent BP that shared the same coordinate,
     * preserving the orthogonal segment connection. Nudging along a segment axis (sliding)
     * is always safe. Nudging perpendicular requires propagation to maintain orthogonality.</p>
     *
     * @param path       mutable list of bendpoints for one connection
     * @param obstacles  obstacle rectangles to clear from. Production passes the
     *                   connection's ancestor-excluded set ({@code conn.obstacles()}),
     *                   so an endpoint's own container is not treated as a clearance
     *                   obstacle; source/target are additionally skipped by id.
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

            // Propagate perpendicular coordinate changes to adjacent BPs to maintain
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
     * Segment-based clearance enforcement.
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
     * @param obstacles  obstacle rectangles to clear from. Production passes the
     *                   connection's ancestor-excluded set ({@code conn.obstacles()}),
     *                   so an endpoint's own container is not treated as a clearance
     *                   obstacle; source/target are additionally skipped by id.
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

            // Propagate orthogonality to adjacent segments
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
     * grazed obstacles.
     *
     * @param path      mutable bendpoint list (2 or 3 BPs)
     * @param obstacles obstacle rectangles to clear from (production passes the
     *                  connection's ancestor-excluded {@code conn.obstacles()} set)
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
     * Aggregates unique top-level group boundaries from all connections.
     *
     * <p>Each {@link ConnectionEndpoints} carries a per-connection {@code groupBoundaries}
     * list (all groups minus ancestors of that connection's endpoints). This method
     * deduplicates by group ID across all connections, then filters to top-level only:
     * a group is top-level if no other group in the set fully encloses it.
     *
     * @param connections all connection endpoint records
     * @return deduplicated list of top-level group rectangles
     */
    static List<RoutingRect> extractTopLevelGroupBounds(List<ConnectionEndpoints> connections) {
        // Deduplicate group bounds by ID across all connections.
        Map<String, RoutingRect> uniqueGroups = new LinkedHashMap<>();
        for (ConnectionEndpoints conn : connections) {
            if (conn.groupBoundaries() == null) continue;
            for (RoutingRect group : conn.groupBoundaries()) {
                if (group.id() != null && !uniqueGroups.containsKey(group.id())) {
                    uniqueGroups.put(group.id(), group);
                }
            }
        }

        if (uniqueGroups.isEmpty()) {
            return List.of();
        }

        // Filter to top-level only: exclude any group whose bounds are fully enclosed
        // by another group's bounds.
        List<RoutingRect> all = new ArrayList<>(uniqueGroups.values());
        List<RoutingRect> topLevel = new ArrayList<>();
        for (RoutingRect candidate : all) {
            boolean enclosed = false;
            int cx = candidate.x(), cy = candidate.y();
            int cRight = cx + candidate.width(), cBottom = cy + candidate.height();
            for (RoutingRect other : all) {
                if (java.util.Objects.equals(other.id(), candidate.id())) continue;
                int ox = other.x(), oy = other.y();
                int oRight = ox + other.width(), oBottom = oy + other.height();
                if (ox <= cx && oy <= cy && oRight >= cRight && oBottom >= cBottom) {
                    enclosed = true;
                    break;
                }
            }
            if (!enclosed) {
                topLevel.add(candidate);
            }
        }
        return topLevel;
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
