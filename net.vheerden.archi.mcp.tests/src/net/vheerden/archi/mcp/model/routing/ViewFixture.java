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

    public record FixtureConnection(String id, String sourceId, String targetId, String label) {}

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
                connections.add(new FixtureConnection(
                        cNode.get("id").asText(),
                        cNode.get("sourceId").asText(),
                        cNode.get("targetId").asText(),
                        cNode.has("label") ? cNode.get("label").asText() : ""));
            }

            return new ViewFixture(viewName, elements, connections);
        }
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
