package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertNotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.model.routing.RoutingResult;
import net.vheerden.archi.mcp.model.routing.ViewFixture;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Routes an oracle fixture through the production pipeline and builds the assessor's inputs from
 * the result — the one harness shared by the oracle regression classes and the terminal-zone
 * census, so the census measures exactly the routes the oracle ceilings are anchored on.
 */
final class OracleFixtureRouting {

    /**
     * Live-MCP {@code auto-route-connections} perimeterMargin handler default
     * (ViewPlacementHandler: {@code perimeterMarginObj != null
     * ? Math.max(10, Math.min(200, perimeterMarginObj)) : 50}). The no-arg
     * RoutingPipeline constructor uses {@code DEFAULT_MARGIN=10} for
     * perimeterMargin which produces a tighter visibility graph than the
     * live MCP path — a 5x exterior corridor difference that meaningfully
     * shifts A* path choices and inflates M5 (verified 2026-04-29: M5
     * dropped 9 → 6 → 2 as the test parameters converged on the live MCP
     * configuration).
     */
    private static final int LIVE_MCP_PERIMETER_MARGIN = 50;

    /** The assessor's inputs for one routed fixture. */
    record Inputs(List<AssessmentNode> nodes, List<AssessmentConnection> connections) {}

    private final ViewFixture fixture;
    private final String logTag;

    private OracleFixtureRouting(ViewFixture fixture, String logTag) {
        this.fixture = fixture;
        this.logTag = logTag;
    }

    /** Loads {@code fixturePath}; {@code logTag} prefixes the routing-yield line. */
    static OracleFixtureRouting load(String fixturePath, String logTag) throws IOException {
        return of(ViewFixture.load(fixturePath), logTag);
    }

    /** Wraps an already-loaded fixture; {@code logTag} prefixes the routing-yield line. */
    static OracleFixtureRouting of(ViewFixture fixture, String logTag) {
        return new OracleFixtureRouting(fixture, logTag);
    }

    /** Match live-MCP RoutingPipeline construction (perimeterMargin=50). */
    private static RoutingPipeline liveMcpPipeline() {
        return new RoutingPipeline(
                RoutingPipeline.DEFAULT_BEND_PENALTY,
                RoutingPipeline.DEFAULT_MARGIN,
                RoutingPipeline.DEFAULT_CONGESTION_WEIGHT,
                LIVE_MCP_PERIMETER_MARGIN);
    }

    /** Routes every connection with a fresh live-MCP pipeline and builds the assessor's inputs. */
    Inputs route() {
        RoutingPipeline pipeline = liveMcpPipeline();
        // 1. Route every connection via the production pipeline.
        // Mirrors ArchiModelAccessorImpl — group containers go to a SEPARATE
        // per-connection groupBoundaries list (group-wall clearance cost), NOT to
        // the obstacles list (which is non-group-only). Without this split, the
        // V4 oracle groups (322x1422 / 400x1589 / 338x2089) become fat hard
        // obstacles that force connections into narrow corridors, producing
        // M5 ~ 33 vs the live MCP M5 = 2 (falsification surfaced 2026-04-29).
        List<RoutingPipeline.ConnectionEndpoints> endpoints = new ArrayList<>();
        for (RoutingPipeline.ConnectionEndpoints ep : fixture.buildConnectionEndpoints()) {
            List<RoutingRect> filteredObstacles = new ArrayList<>();
            List<RoutingRect> groupBoundaries = new ArrayList<>();
            for (RoutingRect obs : ep.obstacles()) {
                if (isGroupId(obs.id())) {
                    groupBoundaries.add(obs);
                } else {
                    filteredObstacles.add(obs);
                }
            }
            endpoints.add(new RoutingPipeline.ConnectionEndpoints(
                    ep.connectionId(), ep.source(), ep.target(),
                    filteredObstacles, ep.labelText(), ep.textPosition(),
                    groupBoundaries));
        }
        List<RoutingRect> allObstacles = new ArrayList<>();
        for (RoutingRect obs : fixture.buildAllObstacles()) {
            if (!isGroupId(obs.id())) {
                allObstacles.add(obs);
            }
        }
        // Build per-connection labelExcludeSets matching ArchiModelAccessorImpl
        // — source + ancestors + descendants + target + ancestors + descendants.
        // Used by Stage 4.8 label position optimizer.
        Map<String, Set<String>> labelExcludeSets = new LinkedHashMap<>();
        for (RoutingPipeline.ConnectionEndpoints conn : endpoints) {
            Set<String> excludeIds = new HashSet<>();
            excludeIds.add(conn.source().id());
            excludeIds.add(conn.target().id());
            collectAncestors(conn.source().id(), excludeIds);
            collectAncestors(conn.target().id(), excludeIds);
            collectChildren(conn.source().id(), excludeIds);
            collectChildren(conn.target().id(), excludeIds);
            labelExcludeSets.put(conn.connectionId(), excludeIds);
        }

        RoutingResult routingResult = pipeline.routeAllConnections(
                endpoints, allObstacles, labelExcludeSets);

        // Successful routes PLUS violated routes (force=true semantics). Live-MCP
        // default is force=false (only routed applied; violated kept at
        // pre-existing bendpoints) — but in a stateless JUnit fixture there are no
        // pre-existing bendpoints to fall back to, so including violatedRoutes is
        // the closest stateless approximation. Without this merge, connections in
        // violatedRoutes are omitted from the AssessmentConnection list entirely,
        // which produces a degenerate hub-port-quality + coincident-segment count
        // vs the live model.
        Map<String, List<AbsoluteBendpointDto>> routedBps = new LinkedHashMap<>();
        routedBps.putAll(routingResult.routed());
        routedBps.putAll(routingResult.violatedRoutes());

        // Diagnostic: log routing yield. If routed + violated < total, some
        // connections failed entirely and the test can't represent them —
        // investigate before trusting the metrics.
        System.out.println("[" + logTag + "] routing yield: "
                + routingResult.routed().size() + " routed + "
                + routingResult.violatedRoutes().size() + " violated + "
                + routingResult.failed().size() + " failed = "
                + (routingResult.routed().size()
                        + routingResult.violatedRoutes().size()
                        + routingResult.failed().size())
                + " of " + fixture.getConnections().size() + " total");

        // 2. Build AssessmentNode list from fixture elements.
        // Group containers (isChild=false) become isGroup=true so the assessor
        // excludes them from hub-port-quality and coincident-segment scoring.
        List<AssessmentNode> nodes = new ArrayList<>();
        for (ViewFixture.FixtureElement e : fixture.getElements()) {
            boolean isGroup = !e.isChild();
            nodes.add(new AssessmentNode(
                    e.id(),
                    e.x(), e.y(), e.w(), e.h(),
                    e.parentId(),
                    isGroup,
                    false, // isNote — fixture excludes notes
                    e.name(),
                    0.0,   // labelTextWidth — informational, not used by the oracle metrics
                    null,  // imagePath
                    null   // imagePosition
            , 0.0, 0.0, 0.0));
        }

        // 3. Build AssessmentConnection list from routed bendpoints.
        // pathPoints = [sourceCenter, ...bendpoints, targetCenter] per
        // AssessmentConnection contract.
        List<AssessmentConnection> connections = new ArrayList<>();
        for (ViewFixture.FixtureConnection c : fixture.getConnections()) {
            List<AbsoluteBendpointDto> bps = routedBps.get(c.id());
            if (bps == null) {
                // Routing failed for this connection — skip rather than
                // fabricate a path; lets the metrics reflect only successful
                // routes (matches live MCP assess-layout semantics).
                continue;
            }
            ViewFixture.FixtureElement src = fixture.getElementById(c.sourceId());
            ViewFixture.FixtureElement tgt = fixture.getElementById(c.targetId());
            // A fixture sourceId/targetId mismatch (orphaned connection) would NPE
            // on the next line and surface as a stack trace, not a useful
            // assertion failure.
            assertNotNull("Fixture connection " + c.id()
                    + " references unknown sourceId=" + c.sourceId(), src);
            assertNotNull("Fixture connection " + c.id()
                    + " references unknown targetId=" + c.targetId(), tgt);

            List<double[]> pathPoints = new ArrayList<>();
            pathPoints.add(new double[] {src.x() + src.w() / 2.0, src.y() + src.h() / 2.0});
            for (AbsoluteBendpointDto bp : bps) {
                pathPoints.add(new double[] {bp.x(), bp.y()});
            }
            pathPoints.add(new double[] {tgt.x() + tgt.w() / 2.0, tgt.y() + tgt.h() / 2.0});

            connections.add(new AssessmentConnection(
                    c.id(), c.sourceId(), c.targetId(),
                    pathPoints, c.label(), 1));
        }
        return new Inputs(nodes, connections);
    }

    /**
     * True if the fixture element id is a group container (isChild=false in
     * the fixture means top-level, which for the oracle fixtures means a
     * styling group container — every leaf element is parented in a group).
     */
    private boolean isGroupId(String id) {
        ViewFixture.FixtureElement e = fixture.getElementById(id);
        return e != null && !e.isChild();
    }

    /** Walks parentId chain (matches ArchiModelAccessorImpl.getAncestorIds). */
    private void collectAncestors(String id, Set<String> into) {
        ViewFixture.FixtureElement e = fixture.getElementById(id);
        while (e != null && e.parentId() != null) {
            into.add(e.parentId());
            e = fixture.getElementById(e.parentId());
        }
    }

    /** One-level children (matches ArchiModelAccessorImpl.getChildIds). */
    private void collectChildren(String parentId, Set<String> into) {
        for (ViewFixture.FixtureElement e : fixture.getElements()) {
            if (parentId.equals(e.parentId())) {
                into.add(e.id());
            }
        }
    }
}
