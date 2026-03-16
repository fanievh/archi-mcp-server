package net.vheerden.archi.mcp.model.routing;

import java.util.ArrayList;
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

    static final int DEFAULT_BEND_PENALTY = 30;
    static final int DEFAULT_MARGIN = 10;
    static final int MICRO_JOG_THRESHOLD = 15;

    /** Default congestion weight for production routing (Story 11-30). */
    static final double DEFAULT_CONGESTION_WEIGHT = 5.0;

    private final int bendPenalty;
    private final int margin;
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
        this.bendPenalty = bendPenalty;
        this.margin = margin;
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
     * @param source    source element rectangle
     * @param target    target element rectangle
     * @param obstacles list of obstacle rectangles (caller must exclude source/target/ancestors)
     * @return list of absolute bendpoints (intermediate path nodes, excluding source/target centers)
     */
    public List<AbsoluteBendpointDto> routeConnection(
            RoutingRect source, RoutingRect target, List<RoutingRect> obstacles) {
        logger.debug("Routing connection: source={}, target={}, obstacles={}",
                source.id(), target.id(), obstacles.size());

        // Self-connection: same center → straight line
        if (source.centerX() == target.centerX() && source.centerY() == target.centerY()) {
            return List.of();
        }

        // Build visibility graph from obstacles
        OrthogonalVisibilityGraph graph = new OrthogonalVisibilityGraph(margin);
        graph.build(obstacles);

        // Inject port nodes for this connection
        VisNode[] ports = graph.addPortNodes(
                source.centerX(), source.centerY(),
                target.centerX(), target.centerY());
        VisNode sourcePort = ports[0];
        VisNode targetPort = ports[1];

        // A* path search
        List<VisNode> path = findPath(graph, sourcePort, targetPort);

        // Empty path fallback: no route found → straight line
        if (path.isEmpty()) {
            logger.warn("No path found from ({},{}) to ({},{}) — falling back to straight line",
                    source.centerX(), source.centerY(), target.centerX(), target.centerY());
            return List.of();
        }

        // Convert path to bendpoints: exclude first (source) and last (target) nodes
        List<AbsoluteBendpointDto> bendpoints = new ArrayList<>();
        for (int i = 1; i < path.size() - 1; i++) {
            VisNode node = path.get(i);
            bendpoints.add(new AbsoluteBendpointDto(node.x(), node.y()));
        }

        return bendpoints;
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
                                       String labelText, int textPosition) {}

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
        logger.info("Batch routing {} connections with path ordering and edge nudging",
                connections.size());

        // 1. Route each connection individually
        List<String> connectionIds = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> bendpointLists = new ArrayList<>();
        List<int[]> sourceCenters = new ArrayList<>();
        List<int[]> targetCenters = new ArrayList<>();

        for (ConnectionEndpoints conn : connections) {
            connectionIds.add(conn.connectionId());
            bendpointLists.add(routeConnection(conn.source(), conn.target(), conn.obstacles()));
            sourceCenters.add(new int[]{conn.source().centerX(), conn.source().centerY()});
            targetCenters.add(new int[]{conn.target().centerX(), conn.target().centerY()});
        }

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

        // 4.7. Label position optimization pass (Story 11-31)
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
                optimalPositions.size(), optimalPositions);
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
    static void removeDuplicatePoints(List<AbsoluteBendpointDto> path) {
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
     * Removes collinear intermediate points (3+ consecutive points on the same
     * horizontal or vertical line). The middle point adds no direction change
     * and creates visual artifacts.
     */
    static void removeCollinearPoints(List<AbsoluteBendpointDto> path) {
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

    private static boolean isInsideOrOnBoundary(AbsoluteBendpointDto bp, RoutingRect rect) {
        return bp.x() >= rect.x() && bp.x() <= rect.x() + rect.width()
                && bp.y() >= rect.y() && bp.y() <= rect.y() + rect.height();
    }

    /**
     * Runs A* path search. Package-visible for test overriding (empty-path fallback test).
     */
    List<VisNode> findPath(OrthogonalVisibilityGraph graph, VisNode sourcePort, VisNode targetPort) {
        VisibilityGraphRouter router = new VisibilityGraphRouter(bendPenalty, congestionWeight);
        return router.findPath(graph, sourcePort, targetPort);
    }
}
