package net.vheerden.archi.mcp.response;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Tests for {@link GraphFormatter}.
 */
public class GraphFormatterTest {

    // ---- formatAsGraph(items) — single-arg ----

    @Test
    @SuppressWarnings("unchecked")
    public void formatAsGraph_itemsOnly_shouldReturnNodesAndEmptyEdges() {
        List<Map<String, Object>> items = List.of(
                Map.of("id", "e1", "name", "Element 1"),
                Map.of("id", "e2", "name", "Element 2"));

        Map<String, Object> graph = GraphFormatter.formatAsGraph(items);

        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");
        List<Map<String, Object>> edges = (List<Map<String, Object>>) graph.get("edges");

        assertEquals(2, nodes.size());
        assertTrue(edges.isEmpty());
        assertEquals("e1", nodes.get(0).get("id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void formatAsGraph_nullItems_shouldReturnEmptyNodesAndEdges() {
        Map<String, Object> graph = GraphFormatter.formatAsGraph((List<Map<String, Object>>) null);

        List<?> nodes = (List<?>) graph.get("nodes");
        List<?> edges = (List<?>) graph.get("edges");

        assertTrue(nodes.isEmpty());
        assertTrue(edges.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void formatAsGraph_emptyItems_shouldReturnEmptyNodesAndEdges() {
        Map<String, Object> graph = GraphFormatter.formatAsGraph(List.of());

        List<?> nodes = (List<?>) graph.get("nodes");
        List<?> edges = (List<?>) graph.get("edges");

        assertTrue(nodes.isEmpty());
        assertTrue(edges.isEmpty());
    }

    // ---- formatAsGraph(elements, relationships) — two-arg ----

    @Test
    public void formatAsGraph_nullBoth_shouldReturnEmptyGraph() {
        Map<String, Object> graph = GraphFormatter.formatAsGraph(null, null);

        List<?> nodes = (List<?>) graph.get("nodes");
        List<?> edges = (List<?>) graph.get("edges");

        assertTrue(nodes.isEmpty());
        assertTrue(edges.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void formatAsGraph_nullRelationships_shouldReturnNodesOnly() {
        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(createMap("id", "e1", "name", "Portal"));

        Map<String, Object> graph = GraphFormatter.formatAsGraph(elements, null);

        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");
        List<?> edges = (List<?>) graph.get("edges");

        assertEquals(1, nodes.size());
        assertTrue(edges.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void formatAsGraph_nullElements_withRelationships_shouldReturnMinimalNodes() {
        List<Map<String, Object>> relationships = new ArrayList<>();
        relationships.add(createMap("id", "r1", "type", "Serving",
                "sourceId", "e1", "targetId", "e2"));

        Map<String, Object> graph = GraphFormatter.formatAsGraph(null, relationships);

        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");
        List<Map<String, Object>> edges = (List<Map<String, Object>>) graph.get("edges");

        assertEquals(2, nodes.size()); // e1 and e2 as minimal nodes
        assertEquals(1, edges.size());
        assertEquals("(referenced)", nodes.get(0).get("name"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void formatAsGraph_withRelationships_shouldDeduplicateNodes() {
        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(createMap("id", "e1", "name", "Portal", "type", "ApplicationComponent"));
        elements.add(createMap("id", "e2", "name", "Auth", "type", "ApplicationService"));

        List<Map<String, Object>> relationships = new ArrayList<>();
        relationships.add(createMap("id", "r1", "type", "ServingRelationship",
                "sourceId", "e1", "targetId", "e2"));

        Map<String, Object> graph = GraphFormatter.formatAsGraph(elements, relationships);

        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");
        List<Map<String, Object>> edges = (List<Map<String, Object>>) graph.get("edges");

        assertEquals(2, nodes.size());
        assertEquals(1, edges.size());
        assertEquals("r1", edges.get(0).get("id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void formatAsGraph_shouldAddMinimalNodeForUnresolvedReference() {
        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(createMap("id", "e1", "name", "Portal"));

        List<Map<String, Object>> relationships = new ArrayList<>();
        relationships.add(createMap("id", "r1", "type", "ServingRelationship",
                "sourceId", "e1", "targetId", "e-unknown"));

        Map<String, Object> graph = GraphFormatter.formatAsGraph(elements, relationships);

        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");

        assertEquals(2, nodes.size());
        // Find the minimal node
        Map<String, Object> minimalNode = nodes.stream()
                .filter(n -> "e-unknown".equals(n.get("id")))
                .findFirst().orElse(null);
        assertNotNull(minimalNode);
        assertEquals("(referenced)", minimalNode.get("name"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void formatAsGraph_elementInMultipleRelationships_shouldAppearOnceInNodes() {
        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(createMap("id", "e1", "name", "Portal"));
        elements.add(createMap("id", "e2", "name", "Auth"));
        elements.add(createMap("id", "e3", "name", "DB"));

        List<Map<String, Object>> relationships = new ArrayList<>();
        relationships.add(createMap("id", "r1", "sourceId", "e1", "targetId", "e2"));
        relationships.add(createMap("id", "r2", "sourceId", "e1", "targetId", "e3"));
        relationships.add(createMap("id", "r3", "sourceId", "e2", "targetId", "e3"));

        Map<String, Object> graph = GraphFormatter.formatAsGraph(elements, relationships);

        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");
        List<Map<String, Object>> edges = (List<Map<String, Object>>) graph.get("edges");

        assertEquals(3, nodes.size()); // Each element once
        assertEquals(3, edges.size()); // All relationships
    }

    // ---- formatDepthModeAsGraph ----

    @Test
    @SuppressWarnings("unchecked")
    public void formatDepthModeAsGraph_shouldExtractNodesFromSourceTarget() {
        List<Map<String, Object>> expandedRels = new ArrayList<>();
        Map<String, Object> rel = createMap("id", "r1", "type", "ServingRelationship", "name", "serves");
        rel.put("source", createMap("id", "e1", "name", "Portal", "type", "ApplicationComponent"));
        rel.put("target", createMap("id", "e2", "name", "Auth", "type", "ApplicationService"));
        expandedRels.add(rel);

        Map<String, Object> graph = GraphFormatter.formatDepthModeAsGraph(expandedRels);

        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");
        List<Map<String, Object>> edges = (List<Map<String, Object>>) graph.get("edges");

        assertEquals(2, nodes.size());
        assertEquals(1, edges.size());
        assertEquals("e1", edges.get(0).get("sourceId"));
        assertEquals("e2", edges.get(0).get("targetId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void formatDepthModeAsGraph_shouldDeduplicateSharedElements() {
        List<Map<String, Object>> expandedRels = new ArrayList<>();

        Map<String, Object> rel1 = createMap("id", "r1", "type", "Serving", "name", null);
        rel1.put("source", createMap("id", "e1", "name", "Portal"));
        rel1.put("target", createMap("id", "e2", "name", "Auth"));
        expandedRels.add(rel1);

        Map<String, Object> rel2 = createMap("id", "r2", "type", "Serving", "name", null);
        rel2.put("source", createMap("id", "e1", "name", "Portal")); // same e1
        rel2.put("target", createMap("id", "e3", "name", "DB"));
        expandedRels.add(rel2);

        Map<String, Object> graph = GraphFormatter.formatDepthModeAsGraph(expandedRels);

        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");
        assertEquals(3, nodes.size()); // e1 appears once
    }

    // ---- formatDepth0AsGraph ----

    @Test
    @SuppressWarnings("unchecked")
    public void formatDepth0AsGraph_shouldReturnEdgesOnlyNoNodes() {
        List<Map<String, Object>> rels = new ArrayList<>();
        rels.add(createMap("id", "r1", "type", "Serving", "name", null,
                "sourceId", "e1", "targetId", "e2"));

        Map<String, Object> graph = GraphFormatter.formatDepth0AsGraph(rels);

        List<?> nodes = (List<?>) graph.get("nodes");
        List<Map<String, Object>> edges = (List<Map<String, Object>>) graph.get("edges");

        assertTrue(nodes.isEmpty());
        assertEquals(1, edges.size());
        assertEquals("r1", edges.get(0).get("id"));
    }

    // ---- flattenTraverseResult ----

    @Test
    @SuppressWarnings("unchecked")
    public void flattenTraverseResult_shouldCreateNodesAndEdgesFromHops() {
        Map<String, Object> traverseResult = new LinkedHashMap<>();
        traverseResult.put("startElement", createMap("id", "start", "name", "Start Element"));

        List<Map<String, Object>> hop1Rels = new ArrayList<>();
        Map<String, Object> hopRel = createMap("id", "r1", "type", "Serving", "name", null,
                "sourceId", "start", "targetId", "e1");
        hopRel.put("connectedElement", createMap("id", "e1", "name", "Connected 1"));
        hop1Rels.add(hopRel);

        List<Map<String, Object>> hops = new ArrayList<>();
        Map<String, Object> hop1 = new LinkedHashMap<>();
        hop1.put("hopLevel", 1);
        hop1.put("relationships", hop1Rels);
        hops.add(hop1);
        traverseResult.put("hops", hops);

        Map<String, Object> summary = createMap(
                "totalElementsDiscovered", 1, "totalRelationships", 1,
                "maxDepthReached", 1, "cyclesDetected", false);
        traverseResult.put("traversalSummary", summary);

        Map<String, Object> graph = GraphFormatter.flattenTraverseResult(traverseResult);

        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");
        List<Map<String, Object>> edges = (List<Map<String, Object>>) graph.get("edges");

        assertEquals(2, nodes.size()); // start + connected
        assertEquals("start", nodes.get(0).get("id"));
        assertEquals("e1", nodes.get(1).get("id"));

        assertEquals(1, edges.size());
        assertEquals(1, edges.get(0).get("hopLevel")); // hopLevel preserved
        assertNotNull(graph.get("traversalSummary"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void flattenTraverseResult_shouldDeduplicateAcrossHops() {
        Map<String, Object> traverseResult = new LinkedHashMap<>();
        traverseResult.put("startElement", createMap("id", "start", "name", "Start"));

        // Hop 1: start -> e1
        Map<String, Object> hopRel1 = createMap("id", "r1", "type", "T", "name", null,
                "sourceId", "start", "targetId", "e1");
        hopRel1.put("connectedElement", createMap("id", "e1", "name", "E1"));

        // Hop 2: e1 -> e2
        Map<String, Object> hopRel2 = createMap("id", "r2", "type", "T", "name", null,
                "sourceId", "e1", "targetId", "e2");
        hopRel2.put("connectedElement", createMap("id", "e2", "name", "E2"));

        List<Map<String, Object>> hops = new ArrayList<>();
        Map<String, Object> hop1 = new LinkedHashMap<>();
        hop1.put("hopLevel", 1);
        hop1.put("relationships", List.of(hopRel1));
        hops.add(hop1);

        Map<String, Object> hop2 = new LinkedHashMap<>();
        hop2.put("hopLevel", 2);
        hop2.put("relationships", List.of(hopRel2));
        hops.add(hop2);

        traverseResult.put("hops", hops);
        traverseResult.put("traversalSummary", createMap(
                "totalElementsDiscovered", 2, "totalRelationships", 2,
                "maxDepthReached", 2, "cyclesDetected", false));

        Map<String, Object> graph = GraphFormatter.flattenTraverseResult(traverseResult);

        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");
        List<Map<String, Object>> edges = (List<Map<String, Object>>) graph.get("edges");

        assertEquals(3, nodes.size()); // start, e1, e2 — each once
        assertEquals(2, edges.size());
        assertEquals(1, edges.get(0).get("hopLevel"));
        assertEquals(2, edges.get(1).get("hopLevel"));
    }

    // ---- Helper ----

    private static Map<String, Object> createMap(Object... kvPairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kvPairs.length; i += 2) {
            map.put((String) kvPairs[i], kvPairs[i + 1]);
        }
        return map;
    }
}
