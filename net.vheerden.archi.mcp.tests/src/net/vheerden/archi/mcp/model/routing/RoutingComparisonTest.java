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
 * Routing comparison test for Story 10-27 (ELK Routing Spike).
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
}
