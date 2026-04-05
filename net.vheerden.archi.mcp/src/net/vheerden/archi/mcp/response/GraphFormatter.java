package net.vheerden.archi.mcp.response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transforms element/relationship data into deduplicated graph structures
 * with separate {@code nodes} and {@code edges} arrays (Story 6.3, FR21).
 *
 * <p>Used by query handlers when {@code format=graph} is requested.
 * Each unique element appears once in {@code nodes}; relationships reference
 * nodes via {@code sourceId}/{@code targetId}.</p>
 *
 * <p>Thread-safe: all methods are stateless and static.</p>
 */
public final class GraphFormatter {

    private GraphFormatter() {} // utility class

    /**
     * Creates a graph from an items-only list (no relationships).
     * Used by {@code search-elements} and {@code get-views} where results
     * are element/view collections without relationship data.
     *
     * @param items field-selected element or view maps
     * @return graph map with {@code nodes} = items and {@code edges} = empty list
     */
    public static Map<String, Object> formatAsGraph(List<Map<String, Object>> items) {
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", items != null ? items : List.of());
        graph.put("edges", List.of());
        return graph;
    }

    /**
     * Creates a deduplicated graph from elements and relationships.
     * Used by {@code get-view-contents} where both element and relationship
     * data is available.
     *
     * <p>Deduplication: elements that appear multiple times (in the elements
     * list and/or as relationship endpoints) appear once in {@code nodes}.
     * If a relationship references an element not in the provided elements list,
     * a minimal node {@code { "id": "...", "name": "(referenced)" }} is emitted.</p>
     *
     * @param elements      field-selected element maps
     * @param relationships field-selected relationship maps (must have sourceId/targetId)
     * @return graph map with deduplicated {@code nodes} and all {@code edges}
     */
    public static Map<String, Object> formatAsGraph(
            List<Map<String, Object>> elements,
            List<Map<String, Object>> relationships) {

        Map<String, Map<String, Object>> nodeMap = new LinkedHashMap<>();

        // Add all provided elements to node map (keyed by id)
        if (elements != null) {
            for (Map<String, Object> element : elements) {
                String id = (String) element.get("id");
                if (id != null) {
                    nodeMap.put(id, element);
                }
            }
        }

        // Build edges and add minimal nodes for unresolved references
        List<Map<String, Object>> edges = new ArrayList<>();
        if (relationships != null) {
            for (Map<String, Object> rel : relationships) {
                edges.add(rel);
                addMinimalNodeIfMissing(nodeMap, (String) rel.get("sourceId"));
                addMinimalNodeIfMissing(nodeMap, (String) rel.get("targetId"));
            }
        }

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", new ArrayList<>(nodeMap.values()));
        graph.put("edges", edges);
        return graph;
    }

    /**
     * Creates a graph from depth-mode expanded relationships (depth 1+).
     * Extracts {@code source} and {@code target} element maps from each
     * expanded relationship as nodes, and creates edges with
     * {@code sourceId}/{@code targetId}.
     *
     * @param expandedRelationships expanded relationship maps with source/target element maps
     * @return graph map with deduplicated {@code nodes} and {@code edges}
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> formatDepthModeAsGraph(
            List<Map<String, Object>> expandedRelationships) {

        Map<String, Map<String, Object>> nodeMap = new LinkedHashMap<>();
        List<Map<String, Object>> edges = new ArrayList<>();

        if (expandedRelationships != null) {
            for (Map<String, Object> rel : expandedRelationships) {
                Map<String, Object> source = (Map<String, Object>) rel.get("source");
                Map<String, Object> target = (Map<String, Object>) rel.get("target");

                String sourceId = extractAndAddNode(nodeMap, source);
                String targetId = extractAndAddNode(nodeMap, target);

                Map<String, Object> edge = new LinkedHashMap<>();
                edge.put("id", rel.get("id"));
                edge.put("type", rel.get("type"));
                edge.put("name", rel.get("name"));
                edge.put("sourceId", sourceId);
                edge.put("targetId", targetId);
                edges.add(edge);
            }
        }

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", new ArrayList<>(nodeMap.values()));
        graph.put("edges", edges);
        return graph;
    }

    /**
     * Creates a graph from depth-0 relationship maps (no element data).
     * Relationships become edges; nodes array is empty.
     *
     * @param relationships field-selected relationship maps
     * @return graph map with empty {@code nodes} and relationship {@code edges}
     */
    public static Map<String, Object> formatDepth0AsGraph(
            List<Map<String, Object>> relationships) {

        List<Map<String, Object>> edges = new ArrayList<>();
        if (relationships != null) {
            for (Map<String, Object> rel : relationships) {
                Map<String, Object> edge = new LinkedHashMap<>();
                edge.put("id", rel.get("id"));
                edge.put("type", rel.get("type"));
                edge.put("name", rel.get("name"));
                edge.put("sourceId", rel.get("sourceId"));
                edge.put("targetId", rel.get("targetId"));
                edges.add(edge);
            }
        }

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", List.of());
        graph.put("edges", edges);
        return graph;
    }

    /**
     * Flattens a traverse-mode result into a unified graph structure.
     * Each discovered element becomes a node. Each traversed relationship
     * becomes an edge with a {@code hopLevel} attribute for provenance.
     * The {@code traversalSummary} is included in the graph object.
     *
     * @param traverseResult the traverse-mode result map (startElement, hops, traversalSummary)
     * @return graph map with flattened {@code nodes}, {@code edges}, and {@code traversalSummary}
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> flattenTraverseResult(Map<String, Object> traverseResult) {
        Map<String, Map<String, Object>> nodeMap = new LinkedHashMap<>();
        List<Map<String, Object>> edges = new ArrayList<>();

        // Add start element as first node
        Map<String, Object> startElement = (Map<String, Object>) traverseResult.get("startElement");
        if (startElement != null) {
            String id = (String) startElement.get("id");
            if (id != null) {
                nodeMap.put(id, startElement);
            }
        }

        // Process hops
        List<Map<String, Object>> hops = (List<Map<String, Object>>) traverseResult.get("hops");
        if (hops != null) {
            for (Map<String, Object> hop : hops) {
                int hopLevel = ((Number) hop.get("hopLevel")).intValue();
                List<Map<String, Object>> hopRelationships =
                        (List<Map<String, Object>>) hop.get("relationships");
                if (hopRelationships != null) {
                    for (Map<String, Object> rel : hopRelationships) {
                        // Extract connected element as node
                        Map<String, Object> connectedElement =
                                (Map<String, Object>) rel.get("connectedElement");
                        if (connectedElement != null) {
                            String connId = (String) connectedElement.get("id");
                            if (connId != null && !nodeMap.containsKey(connId)) {
                                nodeMap.put(connId, connectedElement);
                            }
                        }

                        // Create edge with hopLevel
                        Map<String, Object> edge = new LinkedHashMap<>();
                        edge.put("id", rel.get("id"));
                        edge.put("type", rel.get("type"));
                        edge.put("name", rel.get("name"));
                        edge.put("sourceId", rel.get("sourceId"));
                        edge.put("targetId", rel.get("targetId"));
                        edge.put("hopLevel", hopLevel);
                        edges.add(edge);
                    }
                }
            }
        }

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", new ArrayList<>(nodeMap.values()));
        graph.put("edges", edges);

        // Include traversalSummary in graph object for traverse mode
        Map<String, Object> traversalSummary =
                (Map<String, Object>) traverseResult.get("traversalSummary");
        if (traversalSummary != null) {
            graph.put("traversalSummary", traversalSummary);
        }

        return graph;
    }

    /**
     * Creates a graph from relationship search results.
     * Relationships become edges; source/target element IDs become minimal nodes.
     * If {@code sourceName}/{@code targetName} fields are present (full preset),
     * they are used as node names.
     *
     * @param relationships field-selected relationship maps (must have sourceId/targetId)
     * @return graph map with {@code nodes} (unique source/target elements) and {@code edges}
     */
    public static Map<String, Object> formatRelationshipsAsGraph(
            List<Map<String, Object>> relationships) {

        Map<String, Map<String, Object>> nodeMap = new LinkedHashMap<>();
        List<Map<String, Object>> edges = new ArrayList<>();

        if (relationships != null) {
            for (Map<String, Object> rel : relationships) {
                // Build edge
                Map<String, Object> edge = new LinkedHashMap<>();
                edge.put("id", rel.get("id"));
                if (rel.get("name") != null) edge.put("name", rel.get("name"));
                if (rel.get("type") != null) edge.put("type", rel.get("type"));
                edge.put("sourceId", rel.get("sourceId"));
                edge.put("targetId", rel.get("targetId"));
                edges.add(edge);

                // Add source node (with name if available from full preset)
                String sourceId = (String) rel.get("sourceId");
                if (sourceId != null && !nodeMap.containsKey(sourceId)) {
                    Map<String, Object> node = new LinkedHashMap<>();
                    node.put("id", sourceId);
                    Object sourceName = rel.get("sourceName");
                    node.put("name", sourceName != null ? sourceName : "(referenced)");
                    nodeMap.put(sourceId, node);
                }

                // Add target node
                String targetId = (String) rel.get("targetId");
                if (targetId != null && !nodeMap.containsKey(targetId)) {
                    Map<String, Object> node = new LinkedHashMap<>();
                    node.put("id", targetId);
                    Object targetName = rel.get("targetName");
                    node.put("name", targetName != null ? targetName : "(referenced)");
                    nodeMap.put(targetId, node);
                }
            }
        }

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", new ArrayList<>(nodeMap.values()));
        graph.put("edges", edges);
        return graph;
    }

    // ---- Private Helpers ----

    private static void addMinimalNodeIfMissing(
            Map<String, Map<String, Object>> nodeMap, String id) {
        if (id != null && !nodeMap.containsKey(id)) {
            Map<String, Object> minimalNode = new LinkedHashMap<>();
            minimalNode.put("id", id);
            minimalNode.put("name", "(referenced)");
            nodeMap.put(id, minimalNode);
        }
    }

    private static String extractAndAddNode(
            Map<String, Map<String, Object>> nodeMap,
            Map<String, Object> elementData) {
        if (elementData == null) return null;
        String id = (String) elementData.get("id");
        if (id != null && !nodeMap.containsKey(id)) {
            // Copy without nested relationships (depth 3 nesting)
            Map<String, Object> nodeData = new LinkedHashMap<>(elementData);
            nodeData.remove("relationships");
            nodeMap.put(id, nodeData);
        }
        return id;
    }
}
