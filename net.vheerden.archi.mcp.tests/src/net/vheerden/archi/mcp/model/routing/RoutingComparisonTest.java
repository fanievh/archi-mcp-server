package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.*;

import org.eclipse.elk.core.RecursiveGraphLayoutEngine;
import org.eclipse.elk.core.options.CoreOptions;
import org.eclipse.elk.core.options.EdgeRouting;
import org.eclipse.elk.core.options.PortConstraints;
import org.eclipse.elk.core.options.SizeConstraint;
import org.eclipse.elk.core.util.BasicProgressMonitor;
import org.eclipse.elk.graph.*;
import org.eclipse.elk.graph.util.ElkGraphUtil;
import org.junit.Before;
import org.junit.Test;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Routing comparison test (ELK Routing Spike).
 * Runs the custom A* visibility-graph pipeline on the Application Architecture
 * View fixture and captures baseline metrics for comparison with ELK.
 * Pure-geometry tests - no OSGi runtime required.
 */
public class RoutingComparisonTest {

    private ViewFixture fixture;
    private RoutingPipeline pipeline;

    @Before
    public void setUp() throws IOException {
        fixture = ViewFixture.load("testdata/app-architecture-view-fixture.json");
        pipeline = new RoutingPipeline();
    }

    // --- Task 1: Fixture validation ---

    @Test
    public void shouldLoadFixture_withCorrectCounts() {
        assertEquals("Expected 41 elements", 41, fixture.getElements().size());
        assertEquals("Expected 31 connections", 31, fixture.getConnections().size());
    }

    @Test
    public void shouldResolveAllConnectionEndpoints() {
        for (ViewFixture.FixtureConnection conn : fixture.getConnections()) {
            assertNotNull("Source not found: " + conn.sourceId(),
                    fixture.getElementById(conn.sourceId()));
            assertNotNull("Target not found: " + conn.targetId(),
                    fixture.getElementById(conn.targetId()));
        }
    }

    @Test
    public void shouldBuildConnectionEndpoints() {
        List<RoutingPipeline.ConnectionEndpoints> endpoints = fixture.buildConnectionEndpoints();
        assertEquals("Expected 31 connection endpoints", 31, endpoints.size());
        for (RoutingPipeline.ConnectionEndpoints ep : endpoints) {
            assertNotNull("Source rect must not be null", ep.source());
            assertNotNull("Target rect must not be null", ep.target());
            assertNotNull("Obstacles must not be null", ep.obstacles());
            assertTrue("Obstacles should exclude source and target",
                    ep.obstacles().stream().noneMatch(o ->
                            o.id().equals(ep.source().id()) || o.id().equals(ep.target().id())));
        }
    }

    @Test
    public void shouldExcludeChildrenFromParentConnectionObstacles() {
        // Connection c18: Core Banking -> ESB
        // Children of CBS (vo-asf) and ESB (vo-mrf) should be excluded
        List<RoutingRect> obstacles = fixture.buildObstaclesForConnection("vo-cbs", "vo-esb");
        Set<String> obstacleIds = new HashSet<>();
        for (RoutingRect r : obstacles) {
            obstacleIds.add(r.id());
        }
        assertFalse("CBS child (vo-asf) should be excluded", obstacleIds.contains("vo-asf"));
        assertFalse("ESB child (vo-mrf) should be excluded", obstacleIds.contains("vo-mrf"));
        assertFalse("Source (vo-cbs) should be excluded", obstacleIds.contains("vo-cbs"));
        assertFalse("Target (vo-esb) should be excluded", obstacleIds.contains("vo-esb"));
        // Other elements should still be present
        assertTrue("vo-amp should be an obstacle", obstacleIds.contains("vo-amp"));
    }

    // --- Task 2: Custom pipeline baseline metrics ---

    @Test
    public void shouldRouteAllConnections_customPipeline() {
        List<RoutingPipeline.ConnectionEndpoints> endpoints = fixture.buildConnectionEndpoints();
        List<RoutingRect> allObstacles = fixture.buildAllObstacles();

        RoutingResult routingResult =
                pipeline.routeAllConnections(endpoints, allObstacles);
        Map<String, List<AbsoluteBendpointDto>> results = routingResult.routed();

        // Some connections legitimately fail final obstacle-crossing validation due to
        // tight corridors (< 24px gaps) or pipeline stage interactions (nudging pushes
        // paths into obstacles, label clearance widens paths beyond corridor width,
        // edge attachment shifts endpoints). Upper bound of 5 chosen empirically —
        // if this rises above 5, investigate whether a routing regression occurred.
        int failedCount = routingResult.failed().size();
        assertTrue("At most 5 connections should fail routing, but " + failedCount + " failed: "
                + routingResult.failed(),
                failedCount <= 5);
        assertEquals("Routed + failed should equal total connections",
                31, results.size() + failedCount);

        // Collect metrics
        int totalBendpoints = 0;
        int maxBendpoints = 0;
        String maxBpConnection = "";

        for (Map.Entry<String, List<AbsoluteBendpointDto>> entry : results.entrySet()) {
            int bpCount = entry.getValue().size();
            totalBendpoints += bpCount;
            if (bpCount > maxBendpoints) {
                maxBendpoints = bpCount;
                maxBpConnection = entry.getKey();
            }
        }

        double avgBendpoints = results.isEmpty() ? 0 : (double) totalBendpoints / results.size();

        // Count pass-throughs and crossings
        int passThroughs = countPassThroughs(results, endpoints);
        int crossings = countCrossings(results, endpoints);
        List<String> nonOrthogonal = findNonOrthogonalConnections(results, endpoints);

        // Print metrics for comparison
        System.out.println("========================================");
        System.out.println("CUSTOM PIPELINE METRICS (A* Visibility Graph)");
        System.out.println("========================================");
        System.out.println("Connections routed:    " + results.size());
        System.out.println("Connections failed:    " + failedCount);
        System.out.println("Total bendpoints:      " + totalBendpoints);
        System.out.printf("Avg BPs/connection:    %.2f%n", avgBendpoints);
        System.out.println("Max BPs on connection: " + maxBendpoints + " (" + maxBpConnection + ")");
        System.out.println("Pass-throughs:         " + passThroughs);
        System.out.println("Edge crossings:        " + crossings);
        System.out.println("Non-orthogonal conns:  " + nonOrthogonal.size());
        if (!nonOrthogonal.isEmpty()) {
            System.out.println("  Affected: " + nonOrthogonal);
        }
        System.out.println("========================================");

        // Print per-connection detail
        for (RoutingPipeline.ConnectionEndpoints ep : endpoints) {
            List<AbsoluteBendpointDto> bps = results.get(ep.connectionId());
            if (bps != null) {
                System.out.printf("  %s: %d BPs (%s -> %s)%n",
                        ep.connectionId(), bps.size(),
                        ep.source().id(), ep.target().id());
            } else {
                System.out.printf("  %s: FAILED (%s -> %s)%n",
                        ep.connectionId(),
                        ep.source().id(), ep.target().id());
            }
        }

        // Basic assertions - routing should succeed for the majority
        assertTrue("At least 26 connections should route successfully", results.size() >= 26);
        if (!results.isEmpty()) {
            assertTrue("Avg BPs should be reasonable (< 10)", avgBendpoints < 10);
        }
    }

    // --- Task 4: ELK Layered routing ---

    @Test
    public void shouldRouteWithElk_layeredAlgorithm() {
        // Build ELK graph from fixture
        ElkNode rootGraph = ElkGraphUtil.createGraph();
        rootGraph.setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.layered");
        rootGraph.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL);

        // Create ELK nodes with fixed positions and sizes
        Map<String, ElkNode> elkNodes = new LinkedHashMap<>();
        for (ViewFixture.FixtureElement elem : fixture.getElements()) {
            if (elem.isChild()) continue; // Only top-level elements as ELK nodes

            ElkNode node = ElkGraphUtil.createNode(rootGraph);
            node.setIdentifier(elem.id());
            node.setX(elem.x());
            node.setY(elem.y());
            node.setWidth(elem.w());
            node.setHeight(elem.h());
            // Fix node position - ELK should not move nodes
            node.setProperty(CoreOptions.NO_LAYOUT, false);
            node.setProperty(CoreOptions.PORT_CONSTRAINTS, PortConstraints.FREE);
            // Fix node size
            node.setProperty(CoreOptions.NODE_SIZE_CONSTRAINTS, EnumSet.noneOf(SizeConstraint.class));
            elkNodes.put(elem.id(), node);
        }

        // Create ELK edges from fixture connections
        Map<String, ElkEdge> elkEdges = new LinkedHashMap<>();
        for (ViewFixture.FixtureConnection conn : fixture.getConnections()) {
            ElkNode srcNode = elkNodes.get(conn.sourceId());
            ElkNode tgtNode = elkNodes.get(conn.targetId());
            if (srcNode == null || tgtNode == null) {
                System.out.println("  SKIP: " + conn.id() + " (missing node)");
                continue;
            }
            ElkEdge edge = ElkGraphUtil.createEdge(rootGraph);
            edge.setIdentifier(conn.id());
            edge.getSources().add(srcNode);
            edge.getTargets().add(tgtNode);
            elkEdges.put(conn.id(), edge);
        }

        System.out.println("========================================");
        System.out.println("ELK LAYERED ROUTING");
        System.out.println("========================================");
        System.out.println("ELK nodes created:     " + elkNodes.size());
        System.out.println("ELK edges created:     " + elkEdges.size());

        // Run ELK layout
        try {
            RecursiveGraphLayoutEngine engine = new RecursiveGraphLayoutEngine();
            engine.layout(rootGraph, new BasicProgressMonitor());
        } catch (Exception e) {
            System.out.println("ELK LAYOUT FAILED: " + e.getMessage());
            e.printStackTrace();
            fail("ELK layout threw exception: " + e.getMessage());
            return;
        }

        // Check if ELK moved node positions (we want fixed positions)
        boolean nodesMoved = false;
        int movedCount = 0;
        for (ViewFixture.FixtureElement elem : fixture.getElements()) {
            if (elem.isChild()) continue;
            ElkNode node = elkNodes.get(elem.id());
            if (node == null) continue;
            if (Math.abs(node.getX() - elem.x()) > 1 || Math.abs(node.getY() - elem.y()) > 1) {
                nodesMoved = true;
                movedCount++;
                if (movedCount <= 5) {
                    System.out.printf("  MOVED: %s from (%d,%d) to (%.0f,%.0f)%n",
                            elem.name(), elem.x(), elem.y(), node.getX(), node.getY());
                }
            }
        }
        System.out.println("Nodes moved by ELK:    " + movedCount + " / " + elkNodes.size());

        // Dump ALL ELK node positions for visual reference view creation
        System.out.println("--- ELK NODE POSITIONS (all) ---");
        for (ViewFixture.FixtureElement elem : fixture.getElements()) {
            if (elem.isChild()) continue;
            ElkNode node = elkNodes.get(elem.id());
            if (node == null) continue;
            System.out.printf("  ELK_POS: %s | %.0f,%.0f,%.0f,%.0f | %s%n",
                    elem.id(), node.getX(), node.getY(), node.getWidth(), node.getHeight(), elem.name());
        }
        System.out.println("--- END ELK NODE POSITIONS ---");

        // Extract edge routes and compute metrics
        int totalBendpoints = 0;
        int maxBendpoints = 0;
        String maxBpEdge = "";
        int edgesWithBendpoints = 0;

        for (Map.Entry<String, ElkEdge> entry : elkEdges.entrySet()) {
            ElkEdge edge = entry.getValue();
            int bpCount = 0;
            for (ElkEdgeSection section : edge.getSections()) {
                bpCount += section.getBendPoints().size();
            }
            totalBendpoints += bpCount;
            if (bpCount > maxBendpoints) {
                maxBendpoints = bpCount;
                maxBpEdge = entry.getKey();
            }
            if (bpCount > 0) edgesWithBendpoints++;
        }

        double avgBendpoints = elkEdges.isEmpty() ? 0 :
                (double) totalBendpoints / elkEdges.size();

        // Count pass-throughs for ELK routes
        int elkPassThroughs = countElkPassThroughs(elkEdges, elkNodes, fixture);

        // Check orthogonality
        int nonOrthogonalCount = countElkNonOrthogonal(elkEdges);

        System.out.println("========================================");
        System.out.println("ELK METRICS");
        System.out.println("========================================");
        System.out.println("Edges with bendpoints: " + edgesWithBendpoints + " / " + elkEdges.size());
        System.out.println("Total bendpoints:      " + totalBendpoints);
        System.out.printf("Avg BPs/connection:    %.2f%n", avgBendpoints);
        System.out.println("Max BPs on edge:       " + maxBendpoints + " (" + maxBpEdge + ")");
        System.out.println("Pass-throughs:         " + elkPassThroughs);
        System.out.println("Non-orthogonal edges:  " + nonOrthogonalCount);
        System.out.println("Nodes moved:           " + (nodesMoved ? "YES (" + movedCount + ")" : "NO"));
        System.out.println("========================================");

        // Print per-edge detail
        for (Map.Entry<String, ElkEdge> entry : elkEdges.entrySet()) {
            ElkEdge edge = entry.getValue();
            int bpCount = 0;
            StringBuilder bpDetail = new StringBuilder();
            for (ElkEdgeSection section : edge.getSections()) {
                bpCount += section.getBendPoints().size();
                for (ElkBendPoint bp : section.getBendPoints()) {
                    bpDetail.append(String.format(" (%.0f,%.0f)", bp.getX(), bp.getY()));
                }
            }
            ElkNode src = (ElkNode) edge.getSources().get(0);
            ElkNode tgt = (ElkNode) edge.getTargets().get(0);
            System.out.printf("  %s: %d BPs (%s -> %s)%s%n",
                    entry.getKey(), bpCount,
                    src.getIdentifier(), tgt.getIdentifier(),
                    bpDetail.length() > 0 ? " |" + bpDetail : "");
        }

        // Basic assertion: ELK should produce some routing
        assertTrue("ELK should route at least some edges", edgesWithBendpoints > 0 || elkEdges.size() > 0);
    }

    private int countElkPassThroughs(Map<String, ElkEdge> elkEdges,
                                      Map<String, ElkNode> elkNodes,
                                      ViewFixture fix) {
        int passThroughs = 0;
        for (Map.Entry<String, ElkEdge> entry : elkEdges.entrySet()) {
            ElkEdge edge = entry.getValue();
            ElkNode src = (ElkNode) edge.getSources().get(0);
            ElkNode tgt = (ElkNode) edge.getTargets().get(0);

            // Build path from edge sections
            List<int[]> path = new ArrayList<>();
            for (ElkEdgeSection section : edge.getSections()) {
                path.add(new int[]{(int) section.getStartX(), (int) section.getStartY()});
                for (ElkBendPoint bp : section.getBendPoints()) {
                    path.add(new int[]{(int) bp.getX(), (int) bp.getY()});
                }
                path.add(new int[]{(int) section.getEndX(), (int) section.getEndY()});
            }
            if (path.size() < 2) continue;

            // Check against all obstacle nodes (excluding source, target, children)
            List<RoutingRect> obstacles = fix.buildObstaclesForConnection(
                    src.getIdentifier(), tgt.getIdentifier());
            for (RoutingRect obs : obstacles) {
                for (int i = 0; i < path.size() - 1; i++) {
                    if (segmentIntersectsRect(path.get(i)[0], path.get(i)[1],
                            path.get(i + 1)[0], path.get(i + 1)[1], obs)) {
                        passThroughs++;
                        break;
                    }
                }
            }
        }
        return passThroughs;
    }

    private int countElkNonOrthogonal(Map<String, ElkEdge> elkEdges) {
        int count = 0;
        for (ElkEdge edge : elkEdges.values()) {
            for (ElkEdgeSection section : edge.getSections()) {
                List<ElkBendPoint> bps = section.getBendPoints();
                // Check start -> first BP
                double prevX = section.getStartX();
                double prevY = section.getStartY();
                boolean nonOrth = false;
                for (ElkBendPoint bp : bps) {
                    if (Math.abs(bp.getX() - prevX) > 1 && Math.abs(bp.getY() - prevY) > 1) {
                        nonOrth = true;
                        break;
                    }
                    prevX = bp.getX();
                    prevY = bp.getY();
                }
                // Check last BP -> end
                if (!nonOrth && !bps.isEmpty()) {
                    ElkBendPoint last = bps.get(bps.size() - 1);
                    if (Math.abs(section.getEndX() - last.getX()) > 1 &&
                        Math.abs(section.getEndY() - last.getY()) > 1) {
                        nonOrth = true;
                    }
                }
                if (nonOrth) { count++; break; }
            }
        }
        return count;
    }

    // --- Metric computation helpers ---

    private int countPassThroughs(Map<String, List<AbsoluteBendpointDto>> results,
                                  List<RoutingPipeline.ConnectionEndpoints> endpoints) {
        int passThroughs = 0;
        for (RoutingPipeline.ConnectionEndpoints ep : endpoints) {
            List<AbsoluteBendpointDto> bps = results.get(ep.connectionId());
            if (bps == null) continue;

            // Build full path: source center -> bendpoints -> target center
            List<int[]> path = buildFullPath(ep.source(), ep.target(), bps);

            // Check each segment against obstacles for this connection
            for (RoutingRect obstacle : ep.obstacles()) {
                for (int i = 0; i < path.size() - 1; i++) {
                    if (segmentIntersectsRect(
                            path.get(i)[0], path.get(i)[1],
                            path.get(i + 1)[0], path.get(i + 1)[1],
                            obstacle)) {
                        passThroughs++;
                        break; // Count once per obstacle per connection
                    }
                }
            }
        }
        return passThroughs;
    }

    private int countCrossings(Map<String, List<AbsoluteBendpointDto>> results,
                                List<RoutingPipeline.ConnectionEndpoints> endpoints) {
        // Build all paths
        List<List<int[]>> allPaths = new ArrayList<>();
        for (RoutingPipeline.ConnectionEndpoints ep : endpoints) {
            List<AbsoluteBendpointDto> bps = results.get(ep.connectionId());
            if (bps != null) {
                allPaths.add(buildFullPath(ep.source(), ep.target(), bps));
            }
        }

        int crossings = 0;
        for (int i = 0; i < allPaths.size(); i++) {
            for (int j = i + 1; j < allPaths.size(); j++) {
                crossings += countPathCrossings(allPaths.get(i), allPaths.get(j));
            }
        }
        return crossings;
    }

    private int countPathCrossings(List<int[]> pathA, List<int[]> pathB) {
        int crossings = 0;
        for (int i = 0; i < pathA.size() - 1; i++) {
            for (int j = 0; j < pathB.size() - 1; j++) {
                if (segmentsIntersect(
                        pathA.get(i)[0], pathA.get(i)[1], pathA.get(i + 1)[0], pathA.get(i + 1)[1],
                        pathB.get(j)[0], pathB.get(j)[1], pathB.get(j + 1)[0], pathB.get(j + 1)[1])) {
                    crossings++;
                }
            }
        }
        return crossings;
    }

    private List<String> findNonOrthogonalConnections(
            Map<String, List<AbsoluteBendpointDto>> results,
            List<RoutingPipeline.ConnectionEndpoints> endpoints) {
        List<String> nonOrthogonal = new ArrayList<>();
        for (RoutingPipeline.ConnectionEndpoints ep : endpoints) {
            List<AbsoluteBendpointDto> bps = results.get(ep.connectionId());
            if (bps == null || bps.size() < 2) continue;

            for (int i = 0; i < bps.size() - 1; i++) {
                AbsoluteBendpointDto a = bps.get(i);
                AbsoluteBendpointDto b = bps.get(i + 1);
                if (a.x() != b.x() && a.y() != b.y()) {
                    nonOrthogonal.add(ep.connectionId());
                    break;
                }
            }
        }
        return nonOrthogonal;
    }

    private List<int[]> buildFullPath(RoutingRect source, RoutingRect target,
                                       List<AbsoluteBendpointDto> bendpoints) {
        List<int[]> path = new ArrayList<>();
        path.add(new int[]{source.centerX(), source.centerY()});
        for (AbsoluteBendpointDto bp : bendpoints) {
            path.add(new int[]{bp.x(), bp.y()});
        }
        path.add(new int[]{target.centerX(), target.centerY()});
        return path;
    }

    private boolean segmentIntersectsRect(int x1, int y1, int x2, int y2, RoutingRect rect) {
        // Check if an orthogonal segment passes through a rectangle (not just touches edge)
        int left = rect.x();
        int top = rect.y();
        int right = rect.x() + rect.width();
        int bottom = rect.y() + rect.height();

        if (x1 == x2) { // Vertical segment
            int minY = Math.min(y1, y2);
            int maxY = Math.max(y1, y2);
            return x1 > left && x1 < right && maxY > top && minY < bottom;
        }
        if (y1 == y2) { // Horizontal segment
            int minX = Math.min(x1, x2);
            int maxX = Math.max(x1, x2);
            return y1 > top && y1 < bottom && maxX > left && minX < right;
        }
        // Diagonal segment - simplified check using bounding box
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        return maxX > left && minX < right && maxY > top && minY < bottom;
    }

    private boolean segmentsIntersect(int ax1, int ay1, int ax2, int ay2,
                                       int bx1, int by1, int bx2, int by2) {
        // Check if two line segments intersect using cross product method
        double d1 = crossProduct(bx1, by1, bx2, by2, ax1, ay1);
        double d2 = crossProduct(bx1, by1, bx2, by2, ax2, ay2);
        double d3 = crossProduct(ax1, ay1, ax2, ay2, bx1, by1);
        double d4 = crossProduct(ax1, ay1, ax2, ay2, bx2, by2);

        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
            ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }

        // Collinear cases - check overlap
        if (d1 == 0 && onSegment(bx1, by1, bx2, by2, ax1, ay1)) return true;
        if (d2 == 0 && onSegment(bx1, by1, bx2, by2, ax2, ay2)) return true;
        if (d3 == 0 && onSegment(ax1, ay1, ax2, ay2, bx1, by1)) return true;
        if (d4 == 0 && onSegment(ax1, ay1, ax2, ay2, bx2, by2)) return true;

        return false;
    }

    private double crossProduct(int ox, int oy, int ax, int ay, int bx, int by) {
        return (double)(ax - ox) * (by - oy) - (double)(ay - oy) * (bx - ox);
    }

    private boolean onSegment(int px, int py, int qx, int qy, int rx, int ry) {
        return Math.min(px, qx) <= rx && rx <= Math.max(px, qx) &&
               Math.min(py, qy) <= ry && ry <= Math.max(py, qy);
    }

    // --- Terminal-faceline characterisation census ---

    /**
     * The six view fixtures, in a fixed order so the census below reads as one table.
     * {@code hh-source-clone} and {@code v4-integration-architecture-oracle} are
     * geometrically identical substrates, which is why their rows are identical.
     */
    private static final String[] CENSUS_FIXTURES = {
        "app-architecture-view",
        "v4-integration-architecture-oracle",
        "hh-source-clone",
        "st-source-clone",
        "retail-bank-business-architecture",
        "retail-bank-application-collaboration",
    };

    /**
     * Routed-connection count per fixture, pinned so the census denominator cannot drift silently.
     *
     * <p>Every census in this file asserts against it, and they do not all route the corpus the
     * same way — the perimeter-frame censuses use a different margin and a different obstacle set
     * and still reach these counts. That the denominator is frame-invariant on this corpus is a
     * measured property, not a guarantee: a third frame added later must re-measure rather than
     * assume it.
     */
    private static final int[] CENSUS_ROUTED = {30, 30, 30, 30, 32, 17};

    /**
     * Exterior perimeter margin used by the hub-perimeter stage gate and the live-client quality
     * pins. The census above routes at the pipeline default instead; the two are different frames.
     */
    private static final int PERIMETER_PIN_MARGIN = 50;

    /**
     * Counts terminals that sit on <em>no</em> face line of their own element, per fixture,
     * as {@code {source, target}} pairs.
     *
     * <p>The predicate is {@link RoutingPipeline#isOnElementPerimeter}: a terminal one pixel
     * outside any of the four element edges is on a face line. A terminal that fails it is off
     * every face line — the shape edge attachment exists to prevent, because ChopboxAnchor then
     * clips the terminal segment diagonally instead of perpendicular.
     *
     * @param channelNudging value for the stage gate whose contribution the census isolates
     */
    private int[][] offFacelineCensus(boolean channelNudging) throws IOException {
        int[][] census = new int[CENSUS_FIXTURES.length][2];
        for (int f = 0; f < CENSUS_FIXTURES.length; f++) {
            ViewFixture fx = ViewFixture.load("testdata/" + CENSUS_FIXTURES[f] + "-fixture.json");
            List<RoutingPipeline.ConnectionEndpoints> endpoints = fx.buildConnectionEndpoints();
            RoutingResult result = new RoutingPipeline().routeAllConnections(
                    endpoints, fx.buildAllObstacles(), null,
                    RoutingPipeline.DEFAULT_SNAP_THRESHOLD, channelNudging);
            Map<String, List<AbsoluteBendpointDto>> routed = result.routed();
            int routedCount = 0;
            for (RoutingPipeline.ConnectionEndpoints ep : endpoints) {
                List<AbsoluteBendpointDto> path = routed.get(ep.connectionId());
                if (path == null || path.size() < 2) {
                    continue;
                }
                routedCount++;
                if (!RoutingPipeline.isOnElementPerimeter(path.get(0), ep.source())) {
                    census[f][0]++;
                }
                if (!RoutingPipeline.isOnElementPerimeter(
                        path.get(path.size() - 1), ep.target())) {
                    census[f][1]++;
                }
            }
            assertEquals("Routed-connection count changed for " + CENSUS_FIXTURES[f]
                    + " — the census denominator moved, so its counts are not comparable",
                    CENSUS_ROUTED[f], routedCount);
        }
        return census;
    }

    /**
     * @param population what the two numbers in each pair actually count, so a census added later
     *     cannot inherit a message describing the population it does not measure
     */
    private void assertCensus(String what, String population, int[][] expected, int[][] actual) {
        StringBuilder diff = new StringBuilder();
        for (int f = 0; f < CENSUS_FIXTURES.length; f++) {
            if (expected[f][0] != actual[f][0] || expected[f][1] != actual[f][1]) {
                diff.append(String.format("%n  %s: expected %d/%d, got %d/%d",
                        CENSUS_FIXTURES[f], expected[f][0], expected[f][1],
                        actual[f][0], actual[f][1]));
            }
        }
        assertEquals(what + " changed (source/target " + population + "):" + diff,
                0, diff.length());
    }

    /**
     * Characterisation: pins what the shipped pipeline currently does, not what it should do.
     *
     * <p>Measured by instrumenting every terminal-touching call in the pipeline and running the
     * whole corpus once. Terminals leave their face line at four places:
     *
     * <ul>
     *   <li>micro-jog removal, in the stage-4.5 cleanup and again in the post-clearance cleanup,
     *       which propagates a neighbouring coordinate onto the terminal index;</li>
     *   <li>the channel-nudging pass, whose guard tests orthogonality to the element centre rather
     *       than the face line;</li>
     *   <li>obstacle re-validation, which repositions the terminal along its own face where it
     *       can and otherwise <em>deletes</em> it, promoting its neighbour — a deletion is
     *       invisible to any census that looks for writes;</li>
     *   <li>the self-element pass-through correction, on two connections, which re-selects a face
     *       deliberately. Its anchoring record is refreshed to follow the terminal; the geometry
     *       still lands off the recorded line until that refresh runs.</li>
     * </ul>
     *
     * <p>Two passes repair any of it, both of them the same terminal realignment run against a
     * different capture. The first restores the edge-attachment terminals and runs before all of
     * the above except the stage-4.5 cleanup. The second is seeded from a capture taken at the
     * first one's exit — the last rung at which the terminals are known to be on their face lines
     * — and runs immediately after the point-clearance cleanup, which is where the largest
     * uncompensated displacement is created. It is placed before the self-element face correction
     * and before channel-global nudging so it cannot revert either: restoring a coordinate over a
     * deliberately re-selected face would leave the terminal on the old face while the anchoring
     * record names the new one, and restoring one over a nudged hub port would undo the port
     * distribution. Everything those later stages create is still uncompensated.
     *
     * <p><strong>What the predicate does not say.</strong> A terminal counts as on a face line
     * when it matches one of the four face-line coordinates. That is a test against the infinite
     * line, not against the face segment, so a terminal that overshoots a corner — on the line but
     * past the element's extent — passes this census and can still fail a perimeter check. The two
     * are different questions and this one is deliberately the weaker of them.
     *
     * <p><strong>Frame.</strong> This census routes the corpus the way {@link ViewFixture} builds it
     * — every parent is an obstacle — and counts the terminals sitting on no face line at all at the
     * routed output: 21 across the six fixtures, over 169 routed connections. It was 46 before the
     * second realignment described above and the terminal repositioning in obstacle re-validation;
     * the v4 and hh fixtures are the same substrate, so the deduplicated figures are 21 of 139 now
     * and 35 of 139 before. The per-stage attribution above was taken in a
     * container-aware frame, where parents are routing boundaries instead, and its counts are per-
     * stage creation events rather than survivors. The two are different quantities in different
     * frames and do not sum to each other; the numbers pinned below are this test's own.
     *
     * <p>Red means the off-face-line population moved. That is not automatically a regression —
     * but it must be explained, and the explanation belongs beside the new numbers.
     */
    @Test
    public void shouldReportTerminalsOffEveryFaceline_whenTheCorpusIsRoutedAsShipped()
            throws IOException {
        assertCensus("Off-face-line terminal census", "off every face line",
                new int[][]{{4, 4}, {0, 0}, {0, 0}, {1, 8}, {0, 4}, {0, 0}},
                offFacelineCensus(true));
    }

    /**
     * Characterisation of the channel-nudging pass's own contribution to the census above,
     * isolated by its gate: disabling it removes four source and four target terminals from the
     * application-architecture fixture and four target terminals from the business-architecture
     * fixture, and changes nothing on the other four fixtures. That contribution is unchanged by
     * the second realignment, which runs before the pass by design.
     *
     * <p>The pass guards its writes with a terminal-alignment check that tests orthogonality to
     * the element <em>centre</em>, which is a weaker property than staying on the assigned face
     * line — so a nudge that keeps the centre alignment can still move the terminal off its face.
     */
    @Test
    public void shouldReportFewerTerminalsOffEveryFaceline_whenChannelNudgingIsDisabled()
            throws IOException {
        assertCensus("Off-face-line terminal census with channel nudging disabled",
                "off every face line",
                new int[][]{{0, 0}, {0, 0}, {0, 0}, {1, 8}, {0, 0}, {0, 0}},
                offFacelineCensus(false));
    }

    // --- Perimeter-strength companion census ---

    /**
     * True when {@code bp} sits on one of {@code elem}'s four face LINES but outside that
     * element's own extent — on the line, past the corner.
     *
     * <p>This is the population {@link RoutingPipeline#isOnElementPerimeter} cannot see. That
     * predicate tests the infinite line and returns true here, so the off-face-line census above
     * scores such a terminal as correct while a perimeter check that also tests the extent
     * rejects it. The two predicates disagree by construction and this one names the difference.
     *
     * <p>Uses the same 1-px-outside convention as the predicate it complements, so a terminal is
     * classified by exactly one of: on a face segment, on a line past the extent, off every line.
     */
    private static boolean isOnFaceLinePastExtent(AbsoluteBendpointDto bp, RoutingRect elem) {
        int left = elem.x() - 1;
        int right = elem.x() + elem.width() + 1;
        int top = elem.y() - 1;
        int bottom = elem.y() + elem.height() + 1;
        boolean onVertical = bp.x() == left || bp.x() == right;
        boolean onHorizontal = bp.y() == top || bp.y() == bottom;
        if (!onVertical && !onHorizontal) {
            return false; // off every line — the census above owns this population
        }
        boolean withinVerticalExtent = bp.y() >= top && bp.y() <= bottom;
        boolean withinHorizontalExtent = bp.x() >= left && bp.x() <= right;
        return !((onVertical && withinVerticalExtent)
                || (onHorizontal && withinHorizontalExtent));
    }

    /**
     * Counts terminals on a face line but past the element's extent, per fixture, as
     * {@code {source, target}} pairs.
     *
     * @param perimeterPinFrame when true, routes the corpus the way the hub-perimeter stage gate
     *     does — exterior perimeter margin 50, group rectangles moved out of the obstacle set and
     *     passed as routing boundaries instead. When false, routes it the way the census above
     *     does — margin 10, every parent an obstacle. The two are different routings of the same
     *     fixture files and their counts do not convert into one another.
     */
    /** One fixture's routing in a named frame: the endpoints as built, and the result. */
    private record FramedRouting(
            List<RoutingPipeline.ConnectionEndpoints> endpoints, RoutingResult result) {}

    /**
     * Routes one fixture the way the hub-perimeter stage gate does: exterior perimeter margin 50,
     * group rectangles moved out of the obstacle set and passed as routing boundaries instead.
     *
     * <p>The obstacle partition is a deliberate line-for-line mirror of that gate's own, including
     * its treatment of an id that resolves to no fixture element. Diverging from it would measure
     * a frame no gate routes in.
     */
    private static FramedRouting routeInPerimeterFrame(ViewFixture fx) {
        List<RoutingPipeline.ConnectionEndpoints> endpoints = new ArrayList<>();
        for (RoutingPipeline.ConnectionEndpoints ep : fx.buildConnectionEndpoints()) {
            List<RoutingRect> elementObstacles = new ArrayList<>();
            List<RoutingRect> groupBoundaries = new ArrayList<>();
            for (RoutingRect obs : ep.obstacles()) {
                ViewFixture.FixtureElement e = fx.getElementById(obs.id());
                if (e != null && !e.isChild()) {
                    groupBoundaries.add(obs);
                } else {
                    elementObstacles.add(obs);
                }
            }
            endpoints.add(new RoutingPipeline.ConnectionEndpoints(
                    ep.connectionId(), ep.source(), ep.target(),
                    elementObstacles, ep.labelText(), ep.textPosition(), groupBoundaries));
        }
        List<RoutingRect> obstacles = new ArrayList<>();
        for (RoutingRect obs : fx.buildAllObstacles()) {
            ViewFixture.FixtureElement e = fx.getElementById(obs.id());
            if (e != null && e.isChild()) {
                obstacles.add(obs);
            }
        }
        RoutingPipeline pipeline = new RoutingPipeline(
                RoutingPipeline.DEFAULT_BEND_PENALTY, RoutingPipeline.DEFAULT_MARGIN,
                RoutingPipeline.DEFAULT_CONGESTION_WEIGHT, PERIMETER_PIN_MARGIN);
        return new FramedRouting(endpoints, pipeline.routeAllConnections(endpoints, obstacles));
    }

    /**
     * Asserts the routed-connection denominator, and that the scored bucket is the whole corpus.
     *
     * <p>The censuses score {@link RoutingResult#routed()}. A connection that ends in
     * {@code violatedRoutes()} or {@code failed()} is therefore not scored at all, and the
     * denominator alone cannot catch that: one connection leaving the routed bucket while another
     * enters it holds the count still. Pinning the other two buckets at empty makes any such
     * movement red, so a census cannot quietly shrink the population it measures — which for a
     * terminal-quality census is the population most likely to carry the defect.
     *
     * <p>Both buckets are empty on the current corpus in both frames, and the denominator
     * assertion above fires first whenever a connection simply stops routing — so this second
     * assertion is a guard against a compensating movement, not a measured repair, and it has not
     * been observed to fire. It is the cheaper half of the pair: the denominator catches a
     * connection lost, this catches one exchanged.
     */
    private void assertRoutingYield(String fixtureName, int fixtureIndex, RoutingResult result,
            int routedCount) {
        assertEquals("Routed-connection count changed for " + fixtureName
                + " — the census denominator moved, so its counts are not comparable",
                CENSUS_ROUTED[fixtureIndex], routedCount);
        assertEquals("Connections left the routed bucket for " + fixtureName
                + " — they are no longer scored by this census, so its count understates the"
                + " population rather than reporting it: violated=" + result.violatedRoutes().size()
                + ", failed=" + result.failed().size(),
                0, result.violatedRoutes().size() + result.failed().size());
    }

    private int[][] pastExtentCensus(boolean perimeterPinFrame) throws IOException {
        int[][] census = new int[CENSUS_FIXTURES.length][2];
        for (int f = 0; f < CENSUS_FIXTURES.length; f++) {
            ViewFixture fx = ViewFixture.load("testdata/" + CENSUS_FIXTURES[f] + "-fixture.json");
            List<RoutingPipeline.ConnectionEndpoints> endpoints;
            RoutingResult result;
            if (perimeterPinFrame) {
                FramedRouting framed = routeInPerimeterFrame(fx);
                endpoints = framed.endpoints();
                result = framed.result();
            } else {
                endpoints = fx.buildConnectionEndpoints();
                result = new RoutingPipeline().routeAllConnections(
                        endpoints, fx.buildAllObstacles());
            }

            Map<String, List<AbsoluteBendpointDto>> routed = result.routed();
            int routedCount = 0;
            for (RoutingPipeline.ConnectionEndpoints ep : endpoints) {
                List<AbsoluteBendpointDto> path = routed.get(ep.connectionId());
                if (path == null || path.size() < 2) {
                    continue;
                }
                routedCount++;
                if (isOnFaceLinePastExtent(path.get(0), ep.source())) {
                    census[f][0]++;
                }
                if (isOnFaceLinePastExtent(path.get(path.size() - 1), ep.target())) {
                    census[f][1]++;
                }
            }
            assertRoutingYield(CENSUS_FIXTURES[f], f, result, routedCount);
        }
        return census;
    }

    /**
     * Counts terminals on <em>no</em> face line of their element in the perimeter frame, per
     * fixture, as {@code {source, target}} pairs — the same question the shipped-frame census
     * above asks, asked where the perimeter gates route.
     */
    private int[][] offFacelineCensusInPerimeterFrame() throws IOException {
        int[][] census = new int[CENSUS_FIXTURES.length][2];
        for (int f = 0; f < CENSUS_FIXTURES.length; f++) {
            ViewFixture fx = ViewFixture.load("testdata/" + CENSUS_FIXTURES[f] + "-fixture.json");
            FramedRouting framed = routeInPerimeterFrame(fx);
            Map<String, List<AbsoluteBendpointDto>> routed = framed.result().routed();
            int routedCount = 0;
            for (RoutingPipeline.ConnectionEndpoints ep : framed.endpoints()) {
                List<AbsoluteBendpointDto> path = routed.get(ep.connectionId());
                if (path == null || path.size() < 2) {
                    continue;
                }
                routedCount++;
                if (!RoutingPipeline.isOnElementPerimeter(path.get(0), ep.source())) {
                    census[f][0]++;
                }
                if (!RoutingPipeline.isOnElementPerimeter(
                        path.get(path.size() - 1), ep.target())) {
                    census[f][1]++;
                }
            }
            assertRoutingYield(CENSUS_FIXTURES[f], f, framed.result(), routedCount);
        }
        return census;
    }

    /**
     * Characterisation: pins how many terminals the shipped pipeline leaves on a face line but
     * past the element's extent, in the same frame as the off-face-line census above.
     *
     * <p><strong>Why this census exists.</strong> The off-face-line census counts terminals that
     * reach none of the four face lines. It is deliberately the weaker of the two available
     * questions, and it is blind to a terminal that slides <em>along</em> a face line and off the
     * end of the element: that terminal keeps a face-line coordinate, so the weaker predicate goes
     * on scoring it as correct while the render clips it and a perimeter check rejects it. This
     * census counts exactly that population, so a change cannot trade one defect for the other and
     * show only the improvement.
     *
     * <p><strong>Frame.</strong> Margin 10, every parent an obstacle, 169 routed connections —
     * identical to the census above, so the two are directly comparable. In this frame the
     * population is small and stable: four terminals, all of them targets, spread over three
     * fixtures.
     *
     * <p><strong>Measured limit of this frame, and the reason the companion below exists.</strong>
     * A displacement of exactly this kind was produced on the integration-oracle substrate by a
     * terminal-realignment placement, and this frame did <em>not</em> move: the count stayed at
     * four while the hub-perimeter stage gate failed. The defect is only reachable in the
     * perimeter-margin-50 routing, so a companion census in that frame is what actually guards it.
     * This one guards the shipped-frame population and nothing beyond it.
     *
     * <p>Red means the past-extent population moved. That is not automatically a regression — but
     * it must be explained, and the explanation belongs beside the new numbers.
     */
    @Test
    public void shouldReportTerminalsOnAFaceLinePastTheExtent_whenTheCorpusIsRoutedAsShipped()
            throws IOException {
        assertCensus("Past-extent terminal census", "on a face line past the element extent",
                new int[][]{{0, 1}, {0, 0}, {0, 0}, {0, 0}, {0, 2}, {0, 1}},
                pastExtentCensus(false));
    }

    /**
     * Characterisation of the same question in the frame the perimeter gates actually route in:
     * exterior perimeter margin 50, group rectangles passed as routing boundaries rather than
     * obstacles.
     *
     * <p><strong>The frames disagree, and only this one is visible to those gates.</strong> Over
     * the same six fixture files and the same 169 routed connections, this frame carries
     * <em>sixteen</em> past-extent terminals against the shipped frame's four, and sixty terminals
     * off every face line against the shipped frame's twenty-one. Those are not the same terminals
     * measured twice — on the hardest fixture the two frames' off-face-line populations share only
     * three of the shipped frame's nine, and even those three sit at different coordinates. A count
     * quoted without naming its frame therefore says nothing, and a corrective measured only in the
     * shipped frame has not been measured where its pins live.
     *
     * <p><strong>What this census is for.</strong> Three mechanistically unrelated attempts to pull
     * terminals back onto their face lines — changing which side a micro-jog collapse writes to,
     * and two different placements of an extra terminal realignment — each improved the shipped
     * frame's off-face-line count and each broke the hub-perimeter stage gate on the same
     * connection, at the same coordinate, on the same element: on a left face line, a few pixels
     * past the bottom of the element's extent. This census moves under all three. It is the
     * measurement that would have ruled them out before a pin did.
     *
     * <p>Red means the past-extent population moved in the perimeter frame. Explain it beside the
     * new numbers; a fall is as much in need of explanation as a rise, because the cheapest way to
     * lower this count is to stop routing a connection at all.
     */
    @Test
    public void shouldReportMorePastExtentTerminals_whenTheCorpusIsRoutedInThePerimeterFrame()
            throws IOException {
        assertCensus("Past-extent terminal census in the perimeter frame",
                "on a face line past the element extent",
                new int[][]{{1, 6}, {0, 1}, {0, 1}, {4, 1}, {0, 1}, {1, 0}},
                pastExtentCensus(true));
    }

    /**
     * Characterisation of the off-face-line population in the perimeter frame — the sibling of the
     * shipped-frame census above, and the number that makes the two frames' disagreement concrete.
     *
     * <p><strong>Why it is pinned rather than stated.</strong> The frame divergence is the whole
     * reason the past-extent companion above is pinned twice, and quoting it as prose beside two
     * checked-in numbers would leave the load-bearing figure the only one free to rot. Over the
     * same six fixture files and the same 169 routed connections, this frame carries <em>sixty</em>
     * terminals off every face line against the shipped frame's twenty-one — the shipped census
     * understates the population by roughly three times, in the frame the perimeter gates do not
     * route in.
     *
     * <p>Red means the perimeter-frame off-face-line population moved. Explain it beside the new
     * numbers.
     */
    @Test
    public void shouldReportMoreTerminalsOffEveryFaceline_whenTheCorpusIsRoutedInThePerimeterFrame()
            throws IOException {
        assertCensus("Off-face-line terminal census in the perimeter frame", "off every face line",
                new int[][]{{1, 5}, {3, 10}, {3, 10}, {13, 13}, {1, 1}, {0, 0}},
                offFacelineCensusInPerimeterFrame());
    }

    // --- Anchoring refresh after the self-element pass-through correction ---

    /**
     * Characterisation of the anchoring refresh: pins what the pipeline currently produces on the
     * one view where the self-element pass-through correction re-selects a face, not what it should
     * produce.
     *
     * <p>Two connections meet on the same element face at x=1291. The correction moves one of them
     * to a different face; before the refresh the anchoring record went on naming the face that
     * terminal had left, so every later wrap site measured the connection against a line it was no
     * longer on and declined offsets it had no business declining. With the record re-derived, the
     * two terminals seat at the slots below — swapped from where they sat before.
     *
     * <p>Red means the refresh stopped taking effect, or something upstream changed which
     * connections the correction touches. Either way the new coordinates need an explanation beside
     * them; this is not a should-assertion.
     */
    @Test
    public void shouldSwapTheTwoSharedFacePorts_whenTheAnchoringIsRefreshedAfterAFaceReselection()
            throws IOException {
        ViewFixture fx = ViewFixture.load(
                "testdata/v4-integration-architecture-oracle-fixture.json");
        List<RoutingPipeline.ConnectionEndpoints> endpoints = fx.buildConnectionEndpoints();
        Map<String, List<AbsoluteBendpointDto>> routed = new RoutingPipeline()
                .routeAllConnections(endpoints, fx.buildAllObstacles()).routed();

        List<AbsoluteBendpointDto> entering = routed.get("id-123d02ef39184fcbad4ee915e4df90fc");
        List<AbsoluteBendpointDto> leaving = routed.get("id-b250a38f40ff40dd941d11d1b573ed59");
        assertNotNull("the entering connection must be routed", entering);
        assertNotNull("the leaving connection must be routed", leaving);

        assertEquals("entering terminal seats at the lower slot of the shared face",
                new AbsoluteBendpointDto(1291, 980), entering.get(entering.size() - 1));
        assertEquals("leaving terminal seats at the upper slot of the shared face",
                new AbsoluteBendpointDto(1291, 958), leaving.get(0));
    }
}
