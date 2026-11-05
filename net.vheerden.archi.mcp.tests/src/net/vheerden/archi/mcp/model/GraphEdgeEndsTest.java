package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pins that a {@code get-view-contents} graph names each edge's type and ends at every field level,
 * so one minimal graph call selects a view's connections of one type and says what each joins; the
 * nodes are the ones the standard graph serves, and the dry run prices what is served.
 *
 * <p>Served through the real handler over an in-memory model; nothing measures text.</p>
 */
public class GraphEdgeEndsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ViewContentsFixture f;
    private IArchimateRelationship bc;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-graph-edge-ends");
        IArchimateElement a = f.element("el-a");
        IArchimateElement b = f.element("el-b");
        IArchimateElement c = f.element("el-c");
        IDiagramModelArchimateObject va = f.object(f.view, "vo-a", a, 40, 40, 120, 55);
        IDiagramModelArchimateObject vb = f.object(f.view, "vo-b", b, 400, 40, 120, 55);
        IDiagramModelArchimateObject vc = f.object(f.view, "vo-c", c, 400, 300, 120, 55);
        f.archimateConnection("conn-ab", composition("rel-ab", a, b), va, vb);
        f.archimateConnection("conn-ac", composition("rel-ac", a, c), va, vc);
        bc = f.relationship("rel-bc", b, c);
        f.archimateConnection("conn-bc", bc, vb, vc);
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    private IArchimateRelationship composition(String id, IArchimateElement source, IArchimateElement target) {
        IArchimateRelationship rel = f.factory.createCompositionRelationship();
        rel.setId(id);
        rel.setSource(source);
        rel.setTarget(target);
        f.model.getFolder(com.archimatetool.model.FolderType.RELATIONS).getElements().add(rel);
        return rel;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> graph(String fields) throws Exception {
        String served = f.serve(Map.of("viewId", f.view.getId(), "format", "graph", "fields", fields));
        assertTrue(served, !served.startsWith("ERROR"));
        Map<String, Object> graph = (Map<String, Object>) JSON.readValue(served, Map.class).get("graph");
        assertTrue(served, graph != null);
        return graph;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> edges(Map<String, Object> graph) {
        return (List<Map<String, Object>>) graph.get("edges");
    }

    @SuppressWarnings("unchecked")
    private static TreeSet<String> nodeIds(Map<String, Object> graph) {
        TreeSet<String> ids = new TreeSet<>();
        for (Map<String, Object> node : (List<Map<String, Object>>) graph.get("nodes")) {
            ids.add(node.get("id") + "|" + node.get("name"));
        }
        return ids;
    }

    private static List<String> ends(Map<String, Object> graph) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> edge : edges(graph)) {
            out.add(edge.get("id") + " " + edge.get("type") + " " + edge.get("sourceId") + "->"
                    + edge.get("targetId"));
        }
        return out;
    }

    @Test
    public void shouldNameEachEdgesTypeAndEnds_atMinimal_asAtStandard() throws Exception {
        List<String> minimal = ends(graph("minimal"));

        assertEquals(List.of("rel-ab CompositionRelationship el-a->el-b", "rel-ac CompositionRelationship el-a->el-c",
                "rel-bc AssociationRelationship el-b->el-c"), minimal);
        assertEquals(ends(graph("standard")), minimal);
    }

    @Test
    public void shouldSelectTheCompositionEdges_fromOneMinimalGraphCall() throws Exception {
        List<String> compositions = edges(graph("minimal")).stream()
                .filter(e -> "CompositionRelationship".equals(e.get("type")))
                .map(e -> e.get("sourceId") + "->" + e.get("targetId")).toList();

        assertEquals(List.of("el-a->el-b", "el-a->el-c"), compositions);
    }

    @Test
    public void shouldServeTheSameNodes_atMinimal_asAtStandard() throws Exception {
        assertEquals(nodeIds(graph("standard")).stream().map(s -> s.substring(0, s.indexOf('|'))).toList(),
                nodeIds(graph("minimal")).stream().map(s -> s.substring(0, s.indexOf('|'))).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldPriceTheMinimalGraph_atOrAboveWhatItServes() throws Exception {
        int served = f.serve(Map.of("viewId", f.view.getId(), "format", "graph", "fields", "minimal")).length();
        String dry = f.serve(Map.of("viewId", f.view.getId(), "format", "graph", "fields", "minimal", "dryRun", true));
        Map<String, Object> dryRun = (Map<String, Object>) JSON.readValue(dry, Map.class).get("dryRun");
        assertTrue(dry, dryRun != null);
        int estimated = ((Number) dryRun.get("estimatedTokens")).intValue() * 4;

        assertTrue("estimate " + estimated + " < served " + served, estimated >= served);
    }

    /** A relationship missing an end still serves its edge, without the end it lacks. */
    @Test
    public void shouldServeAnEdgeWithoutTheEndItsRelationshipLacks() throws Exception {
        bc.setTarget(null);

        List<String> minimal = ends(graph("minimal"));

        assertTrue(minimal.toString(), minimal.contains("rel-bc AssociationRelationship el-b->null"));
    }

    /**
     * A field the caller excludes stays out of the edge. Of the three, only {@code type} is an
     * exclude field ({@code sourceId} and {@code targetId} are refused as exclude names), so it is
     * the one pinned.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void shouldLeaveOutTheTypeWhenTheCallerExcludesIt() throws Exception {
        String served = f.serve(Map.of("viewId", f.view.getId(), "format", "graph", "fields", "minimal",
                "exclude", List.of("type")));
        assertTrue(served, !served.startsWith("ERROR"));
        Map<String, Object> graph = (Map<String, Object>) JSON.readValue(served, Map.class).get("graph");
        for (Map<String, Object> edge : edges(graph)) {
            assertTrue(edge.toString(), !edge.containsKey("type") && edge.containsKey("sourceId")
                    && edge.containsKey("targetId"));
        }
    }
}
