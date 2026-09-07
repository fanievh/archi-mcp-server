package net.vheerden.archi.mcp.model.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.vheerden.archi.mcp.model.RoutingRect;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Loads a view fixture from JSON for routing comparison tests.
 * All element coordinates in the fixture are absolute canvas coordinates.
 */
public class ViewFixture {

    public record FixtureElement(String id, String name, int x, int y, int w, int h,
                                  boolean isChild, String parentId) {}

    /**
     * A bendpoint as Archi stores it: offsets relative to the source centre
     * ({@code startX/startY}) and the target centre ({@code endX/endY}). Resolve to
     * absolute canvas coordinates via {@link ViewFixture#buildStoredPath}.
     */
    public record FixtureBendpoint(int startX, int startY, int endX, int endY) {}

    /**
     * @param bendpoints the connection's stored geometry; empty when the connection has
     *                   none (a straight centre-to-centre line). Optional in the JSON —
     *                   fixtures that predate the field simply carry no stored geometry.
     */
    public record FixtureConnection(String id, String sourceId, String targetId, String label,
                                     List<FixtureBendpoint> bendpoints) {

        /** Compact constructor: null-guard the bendpoint list. */
        public FixtureConnection {
            bendpoints = bendpoints != null ? bendpoints : List.of();
        }

        /** Back-compatible 4-arg form for fixtures with no stored geometry. */
        public FixtureConnection(String id, String sourceId, String targetId, String label) {
            this(id, sourceId, targetId, label, List.of());
        }
    }

    private final String viewName;
    private final List<FixtureElement> elements;
    private final List<FixtureConnection> connections;
    private final Map<String, FixtureElement> elementById;

    private ViewFixture(String viewName, List<FixtureElement> elements,
                        List<FixtureConnection> connections) {
        this.viewName = viewName;
        this.elements = Collections.unmodifiableList(elements);
        this.connections = Collections.unmodifiableList(connections);
        this.elementById = new LinkedHashMap<>();
        for (FixtureElement e : elements) {
            elementById.put(e.id(), e);
        }
    }

    public static ViewFixture load(String resourcePath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root;

        // Try classpath first (PDE/OSGi), fall back to file system (CLI JUnit)
        InputStream is = ViewFixture.class.getClassLoader().getResourceAsStream(resourcePath);
        if (is != null) {
            try { root = mapper.readTree(is); } finally { is.close(); }
        } else {
            // Try relative to working directory (project root or test project root)
            Path filePath = Path.of(resourcePath);
            if (!Files.exists(filePath)) {
                // Try from test project subdirectory
                filePath = Path.of("net.vheerden.archi.mcp.tests", resourcePath);
            }
            if (!Files.exists(filePath)) {
                throw new IOException("Fixture not found: " + resourcePath);
            }
            root = mapper.readTree(filePath.toFile());
        }
        {

            String viewName = root.get("viewName").asText();

            List<FixtureElement> elements = new ArrayList<>();
            for (JsonNode eNode : root.get("elements")) {
                elements.add(new FixtureElement(
                        eNode.get("id").asText(),
                        eNode.get("name").asText(),
                        eNode.get("x").asInt(),
                        eNode.get("y").asInt(),
                        eNode.get("w").asInt(),
                        eNode.get("h").asInt(),
                        eNode.has("isChild") && eNode.get("isChild").asBoolean(),
                        eNode.has("parentId") ? eNode.get("parentId").asText() : null));
            }

            List<FixtureConnection> connections = new ArrayList<>();
            for (JsonNode cNode : root.get("connections")) {
                List<FixtureBendpoint> bendpoints = new ArrayList<>();
                if (cNode.has("bendpoints")) {
                    for (JsonNode bNode : cNode.get("bendpoints")) {
                        bendpoints.add(new FixtureBendpoint(
                                bNode.get("startX").asInt(), bNode.get("startY").asInt(),
                                bNode.get("endX").asInt(), bNode.get("endY").asInt()));
                    }
                }
                connections.add(new FixtureConnection(
                        cNode.get("id").asText(),
                        cNode.get("sourceId").asText(),
                        cNode.get("targetId").asText(),
                        cNode.has("label") ? cNode.get("label").asText() : "",
                        bendpoints));
            }

            return new ViewFixture(viewName, elements, connections);
        }
    }

    /**
     * Resolves a connection's stored geometry to the absolute path the assessor measures:
     * source centre → bendpoints → target centre.
     *
     * <p>Mirrors {@code AssessmentCollector}'s construction verbatim, including its bendpoint
     * resolution: each point interpolates between the source-anchored and target-anchored
     * reconstructions at the weight Archi renders with, {@code (i + 1) / (n + 1)}. The counts this
     * feeds are only comparable with a routed result because both sides use this same
     * centre-anchored basis.</p>
     *
     * @return the path as {@code [x, y]} points, for
     *         {@code LayoutQualityAssessor.countPathCrossings}
     */
    public List<double[]> buildStoredPath(FixtureConnection conn) {
        FixtureElement src = elementById.get(conn.sourceId());
        FixtureElement tgt = elementById.get(conn.targetId());
        if (src == null || tgt == null) {
            throw new IllegalArgumentException("Unresolved endpoint on connection: " + conn.id());
        }
        double srcCentreX = src.x() + src.w() / 2.0;
        double srcCentreY = src.y() + src.h() / 2.0;
        double tgtCentreX = tgt.x() + tgt.w() / 2.0;
        double tgtCentreY = tgt.y() + tgt.h() / 2.0;

        List<double[]> path = new ArrayList<>();
        path.add(new double[]{srcCentreX, srcCentreY});
        int bendpointCount = conn.bendpoints().size();
        int bendpointIndex = 0;
        for (FixtureBendpoint bp : conn.bendpoints()) {
            double weight = (bendpointIndex + 1.0) / (bendpointCount + 1.0);
            path.add(new double[]{
                    (bp.startX() + srcCentreX) * (1.0 - weight) + (bp.endX() + tgtCentreX) * weight,
                    (bp.startY() + srcCentreY) * (1.0 - weight) + (bp.endY() + tgtCentreY) * weight});
            bendpointIndex++;
        }
        path.add(new double[]{tgtCentreX, tgtCentreY});
        return path;
    }

    /** Stored-geometry paths for every connection, in fixture order. */
    public List<List<double[]>> buildStoredPaths() {
        List<List<double[]>> paths = new ArrayList<>();
        for (FixtureConnection c : connections) {
            paths.add(buildStoredPath(c));
        }
        return paths;
    }

    public String getViewName() { return viewName; }
    public List<FixtureElement> getElements() { return elements; }
    public List<FixtureConnection> getConnections() { return connections; }
    public FixtureElement getElementById(String id) { return elementById.get(id); }

    /**
     * Build RoutingRect for a specific element.
     */
    public RoutingRect toRoutingRect(FixtureElement e) {
        return new RoutingRect(e.x(), e.y(), e.w(), e.h(), e.id());
    }

    /**
     * Build RoutingRect for an element by ID.
     */
    public RoutingRect toRoutingRect(String id) {
        FixtureElement e = elementById.get(id);
        if (e == null) throw new IllegalArgumentException("Unknown element: " + id);
        return toRoutingRect(e);
    }

    /**
     * Build the all-obstacles list (all non-group elements).
     * Mirrors what auto-route-connections uses for the unified obstacle list.
     */
    public List<RoutingRect> buildAllObstacles() {
        List<RoutingRect> obstacles = new ArrayList<>();
        for (FixtureElement e : elements) {
            obstacles.add(toRoutingRect(e));
        }
        return obstacles;
    }

    /**
     * Build per-connection obstacle list, excluding source, target,
     * their children, and their ancestors (mirrors auto-route logic).
     */
    public List<RoutingRect> buildObstaclesForConnection(String sourceId, String targetId) {
        Set<String> excludeIds = new HashSet<>();
        excludeIds.add(sourceId);
        excludeIds.add(targetId);

        // Exclude children of source and target
        for (FixtureElement e : elements) {
            if (e.isChild() && (sourceId.equals(e.parentId()) || targetId.equals(e.parentId()))) {
                excludeIds.add(e.id());
            }
        }

        // Exclude ancestors of source and target
        addAncestors(sourceId, excludeIds);
        addAncestors(targetId, excludeIds);

        List<RoutingRect> obstacles = new ArrayList<>();
        for (FixtureElement e : elements) {
            if (!excludeIds.contains(e.id())) {
                obstacles.add(toRoutingRect(e));
            }
        }
        return obstacles;
    }

    private void addAncestors(String id, Set<String> into) {
        FixtureElement e = elementById.get(id);
        while (e != null && e.parentId() != null) {
            into.add(e.parentId());
            e = elementById.get(e.parentId());
        }
    }

    /**
     * Build ConnectionEndpoints list for the routing pipeline.
     */
    public List<RoutingPipeline.ConnectionEndpoints> buildConnectionEndpoints() {
        List<RoutingPipeline.ConnectionEndpoints> endpoints = new ArrayList<>();
        for (FixtureConnection conn : connections) {
            RoutingRect src = toRoutingRect(conn.sourceId());
            RoutingRect tgt = toRoutingRect(conn.targetId());
            List<RoutingRect> obstacles = buildObstaclesForConnection(conn.sourceId(), conn.targetId());
            endpoints.add(new RoutingPipeline.ConnectionEndpoints(
                    conn.id(), src, tgt, obstacles, conn.label(), 1));
        }
        return endpoints;
    }
}
