package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.BaseTestAccessor;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.ModelInfoDto;
import net.vheerden.archi.mcp.response.dto.RelationshipDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;
import net.vheerden.archi.mcp.response.dto.ViewContentsDto;
import net.vheerden.archi.mcp.response.dto.ViewDto;
import net.vheerden.archi.mcp.response.dto.ViewGroupDto;
import net.vheerden.archi.mcp.response.dto.ViewNodeDto;
import net.vheerden.archi.mcp.response.dto.ViewNoteDto;

/**
 * Unit tests for get-view-contents format=graph viewConnectionId / viewObjectId
 * enrichment.
 *
 * <p>The graph format historically returned the <em>semantic</em> model graph:
 * edge {@code id} = model-relationship id, node {@code id} = model-element id —
 * neither of which {@code remove-from-view} accepts. The enrichment joins the raw
 * {@code ViewContentsDto.connections()} / {@code visualMetadata()} onto the graph
 * so each edge carries the {@code viewConnectionId} and each element node the
 * {@code viewObjectId} of the visual it addresses.</p>
 *
 * <p>Uses stub accessors — no EMF/OSGi runtime required. Mirrors the
 * {@code ViewHandlerTreeFormatTest} harness exactly.</p>
 */
public class ViewHandlerGraphFormatTest {

    private CommandRegistry registry;
    private ResponseFormatter formatter;
    private ObjectMapper objectMapper;

    @Before
    public void setUp() {
        registry = new CommandRegistry();
        formatter = new ResponseFormatter();
        objectMapper = new ObjectMapper();
    }

    // ---- 1:1 relationship → singular viewConnectionId on the edge ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldAddViewConnectionId_when1to1Edge() throws Exception {
        Map<String, Object> graph = invokeGraph("view-simple");

        List<Map<String, Object>> edges = (List<Map<String, Object>>) graph.get("edges");
        Map<String, Object> edge = edges.stream()
                .filter(e -> "rel-1".equals(e.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("rel-1 edge missing"));

        // The new viewConnectionId equals the IDiagramModelArchimateConnection id.
        assertEquals("conn-1", edge.get("viewConnectionId"));
        // No plural form for the 1:1 case.
        assertFalse("singular case must not emit viewConnectionIds", edge.containsKey("viewConnectionIds"));
        // Existing semantic fields are unchanged.
        assertEquals("rel-1", edge.get("id"));
        assertEquals("elem-1", edge.get("sourceId"));
        assertEquals("elem-2", edge.get("targetId"));
        assertEquals("ServingRelationship", edge.get("type"));
    }

    // ---- 1:1 element placement → singular viewObjectId on the node ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldAddViewObjectId_when1to1ElementNode() throws Exception {
        Map<String, Object> graph = invokeGraph("view-simple");

        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");
        Map<String, Object> node = nodes.stream()
                .filter(n -> "elem-1".equals(n.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("elem-1 node missing"));

        assertEquals("vo-1", node.get("viewObjectId"));
        assertFalse("singular case must not emit viewObjectIds", node.containsKey("viewObjectIds"));
        // Existing fields unchanged.
        assertEquals("elem-1", node.get("id"));
        assertEquals("Web App", node.get("name"));
    }

    // ---- N>1 multi-draw / multi-placement → plural arrays ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldEmitPluralArrays_whenRelationshipAndElementDrawnMultipleTimes() throws Exception {
        Map<String, Object> graph = invokeGraph("view-multidraw");

        List<Map<String, Object>> edges = (List<Map<String, Object>>) graph.get("edges");
        Map<String, Object> edge = edges.stream()
                .filter(e -> "rel-1".equals(e.get("id")))
                .findFirst().orElseThrow(() -> new AssertionError("rel-1 edge missing"));

        assertFalse("multi-draw must omit singular viewConnectionId", edge.containsKey("viewConnectionId"));
        List<String> connIds = (List<String>) edge.get("viewConnectionIds");
        assertNotNull("multi-draw must emit viewConnectionIds array", connIds);
        // Deterministic order = connections() insertion order.
        assertEquals(List.of("conn-1a", "conn-1b"), connIds);

        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");
        Map<String, Object> node = nodes.stream()
                .filter(n -> "elem-1".equals(n.get("id")))
                .findFirst().orElseThrow(() -> new AssertionError("elem-1 node missing"));

        assertFalse("multi-placement must omit singular viewObjectId", node.containsKey("viewObjectId"));
        List<String> voIds = (List<String>) node.get("viewObjectIds");
        assertNotNull("multi-placement must emit viewObjectIds array", voIds);
        assertEquals(List.of("vo-1a", "vo-1b"), voIds);
    }

    // ---- enrichment is exclude-independent (joins from raw contents) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldStillAddIds_whenConnectionsAndVisualMetadataExcluded() throws Exception {
        McpServerFeatures.SyncToolSpecification spec = install();
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-simple");
        args.put("format", "graph");
        args.put("exclude", List.of("connections", "visualMetadata"));
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get-view-contents", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);
        assertFalse(result.isError());

        Map<String, Object> graph = (Map<String, Object>) parseJson(result).get("graph");
        List<Map<String, Object>> edges = (List<Map<String, Object>>) graph.get("edges");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");

        assertEquals("conn-1", edges.stream().filter(e -> "rel-1".equals(e.get("id")))
                .findFirst().orElseThrow().get("viewConnectionId"));
        assertEquals("vo-1", nodes.stream().filter(n -> "elem-1".equals(n.get("id")))
                .findFirst().orElseThrow().get("viewObjectId"));
    }

    // ---- group/note nodes keep their own viewObjectId, not overwritten ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldNotTouchGroupAndNoteNodeViewObjectId() throws Exception {
        Map<String, Object> graph = invokeGraph("view-groups-notes");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");

        Map<String, Object> groupNode = nodes.stream()
                .filter(n -> "group".equals(n.get("_nodeType")))
                .findFirst().orElseThrow(() -> new AssertionError("group node missing"));
        assertEquals("grp-1", groupNode.get("viewObjectId"));
        assertFalse("group node must not gain a viewObjectIds array", groupNode.containsKey("viewObjectIds"));
        // Structural immunity: group/note nodes carry no "id" key — the
        // join keys on node.get("id") — AND are appended after the element-enrichment loop. Pin
        // the no-"id" invariant so a future refactor that gives group nodes an "id" (or reorders
        // the loops) trips this test rather than silently overwriting their own viewObjectId.
        assertFalse("group node must have no 'id' key (immune to the elementId join)",
                groupNode.containsKey("id"));

        Map<String, Object> noteNode = nodes.stream()
                .filter(n -> "note".equals(n.get("_nodeType")))
                .findFirst().orElseThrow(() -> new AssertionError("note node missing"));
        assertEquals("note-1", noteNode.get("viewObjectId"));
        assertFalse("note node must not gain a viewObjectIds array", noteNode.containsKey("viewObjectIds"));
        assertFalse("note node must have no 'id' key (immune to the elementId join)",
                noteNode.containsKey("id"));

        // The element node inside the group still gets its join.
        Map<String, Object> elemNode = nodes.stream()
                .filter(n -> "elem-1".equals(n.get("id")))
                .findFirst().orElseThrow(() -> new AssertionError("elem-1 node missing"));
        assertEquals("vo-1", elemNode.get("viewObjectId"));
    }

    // ---- Defensive: relationship with no drawn connection / element with no placement ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldOmitIds_whenNoMatchingVisual() throws Exception {
        Map<String, Object> graph = invokeGraph("view-no-visuals");
        List<Map<String, Object>> edges = (List<Map<String, Object>>) graph.get("edges");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");

        Map<String, Object> edge = edges.stream().filter(e -> "rel-1".equals(e.get("id")))
                .findFirst().orElseThrow();
        assertFalse("edge without a drawn connection must not get viewConnectionId",
                edge.containsKey("viewConnectionId"));
        assertFalse(edge.containsKey("viewConnectionIds"));

        Map<String, Object> node = nodes.stream().filter(n -> "elem-1".equals(n.get("id")))
                .findFirst().orElseThrow();
        assertFalse("node without a placement must not get viewObjectId",
                node.containsKey("viewObjectId"));
        assertFalse(node.containsKey("viewObjectIds"));
    }

    // ---- nextSteps mentions the new ids ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldMentionIdsInNextSteps() throws Exception {
        McpServerFeatures.SyncToolSpecification spec = install();
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-simple");
        args.put("format", "graph");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get-view-contents", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        Map<String, Object> envelope = parseJson(result);
        List<String> nextSteps = (List<String>) envelope.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("nextSteps should mention viewConnectionId/viewObjectId for remove-from-view",
                nextSteps.stream().anyMatch(s -> s.contains("viewConnectionId")
                        && s.contains("viewObjectId")));
    }

    // ---- tool description graph sentence mentions the new ids ----

    @Test
    public void shouldMentionIdsInToolDescription() {
        install();
        McpSchema.Tool tool = findToolSpec("get-view-contents").tool();
        String desc = tool.description();
        assertTrue("tool description graph sentence should mention viewConnectionId",
                desc.contains("viewConnectionId"));
        assertTrue("tool description graph sentence should mention viewObjectId",
                desc.contains("viewObjectId"));
    }

    // ---- Container marking ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldMarkBothContainerKinds_soOnePredicateFindsEveryContainer() throws Exception {
        // An agent scanning _nodeType == "group" sees only the native-group bucket, so a view built
        // from ArchiMate Grouping containers read as having none — the tree format's old blind spot,
        // reachable by changing one parameter. isGroup marks both kinds.
        Map<String, Object> graph = invokeGraph("view-grouping-containers");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");

        Map<String, Object> groupingNode = nodes.stream()
                .filter(n -> "zone-1".equals(n.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Grouping element missing from graph nodes"));
        assertEquals("an ArchiMate Grouping is a container even though it is an element node",
                Boolean.TRUE, groupingNode.get("isGroup"));

        Map<String, Object> nativeGroupNode = nodes.stream()
                .filter(n -> "group".equals(n.get("_nodeType")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("native group missing from graph nodes"));
        assertEquals(Boolean.TRUE, nativeGroupNode.get("isGroup"));

        assertEquals("exactly the two containers are marked — the Node is a leaf", 2,
                nodes.stream().filter(n -> Boolean.TRUE.equals(n.get("isGroup"))).count());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldNotMarkAPlainElementNode_whenItIsNotAContainer() throws Exception {
        Map<String, Object> graph = invokeGraph("view-grouping-containers");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");

        Map<String, Object> leaf = nodes.stream()
                .filter(n -> "elem-1".equals(n.get("id")))
                .findFirst()
                .orElseThrow();
        assertFalse("a non-container element must not carry the marker at all",
                leaf.containsKey("isGroup"));
    }

    // ---- Helper Methods ----

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeGraph(String viewId) throws Exception {
        McpServerFeatures.SyncToolSpecification spec = install();
        Map<String, Object> args = new HashMap<>();
        args.put("viewId", viewId);
        args.put("format", "graph");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get-view-contents", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);
        assertFalse("graph format must not error for " + viewId, result.isError());
        return (Map<String, Object>) parseJson(result).get("graph");
    }

    private McpServerFeatures.SyncToolSpecification install() {
        // Fixture selection is entirely inside GraphStubAccessor.getViewContents(viewId); this just
        // registers the tools on the @Before-reset registry and returns the get-view-contents spec.
        GraphStubAccessor accessor = new GraphStubAccessor();
        ViewHandler handler = new ViewHandler(accessor, formatter, registry, null);
        handler.registerTools();
        return findToolSpec("get-view-contents");
    }

    private McpServerFeatures.SyncToolSpecification findToolSpec(String toolName) {
        return registry.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not found: " + toolName));
    }

    private Map<String, Object> parseJson(McpSchema.CallToolResult result) throws Exception {
        McpSchema.TextContent content = (McpSchema.TextContent) result.content().get(0);
        return objectMapper.readValue(content.text(),
                new TypeReference<Map<String, Object>>() {});
    }

    // ---- Stub Accessor ----

    private static class GraphStubAccessor extends BaseTestAccessor {
        GraphStubAccessor() {
            super(true);
        }

        @Override
        public List<ViewDto> getViews(String viewpointFilter) {
            return List.of(
                    new ViewDto("view-simple", "Simple View", null, "Views"),
                    new ViewDto("view-multidraw", "Multi-draw View", null, "Views"),
                    new ViewDto("view-groups-notes", "Groups and Notes View", null, "Views"),
                    new ViewDto("view-no-visuals", "No Visuals View", null, "Views"),
                    new ViewDto("view-grouping-containers", "Grouping Containers View", null, "Views"));
        }

        @Override
        public ModelInfoDto getModelInfo() {
            return new ModelInfoDto("Test Model", 10, 5, 4, 0, Map.of(), Map.of(), Map.of());
        }

        @Override
        public Optional<ViewContentsDto> getViewContents(String viewId) {
            return switch (viewId) {
                case "view-simple" -> Optional.of(simpleView());
                case "view-multidraw" -> Optional.of(multiDrawView());
                case "view-groups-notes" -> Optional.of(groupsAndNotesView());
                case "view-no-visuals" -> Optional.of(noVisualsView());
                case "view-grouping-containers" -> Optional.of(groupingContainersView());
                default -> Optional.empty();
            };
        }

        /**
         * One ArchiMate Grouping container holding a Node, plus a native group. Both are containers
         * but they reach the graph through different buckets, so this is the shape that shows
         * whether one predicate finds both.
         */
        private ViewContentsDto groupingContainersView() {
            List<ElementDto> elements = List.of(
                    ElementDto.standard("zone-1", "Ingress Zone", "Grouping", null, "Other", null, null),
                    ElementDto.standard("elem-1", "Load Balancer", "Node", null, "Technology", null, null));
            List<ViewNodeDto> visualMetadata = List.of(
                    new ViewNodeDto("vo-zone-1", "zone-1", 40, 40, 260, 180, null),
                    new ViewNodeDto("vo-elem-1", "elem-1", 30, 60, 120, 55, "vo-zone-1"));
            List<ViewGroupDto> groups = List.of(
                    new ViewGroupDto("grp-1", "Observability", 340, 40, 250, 160, null, List.of()));
            return new ViewContentsDto("view-grouping-containers", "Grouping Containers View", null, null,
                    elements, List.of(), visualMetadata, List.of(), groups, List.of());
        }

        /** 2 elements, 1 relationship drawn exactly once (the 1:1 common case). */
        private ViewContentsDto simpleView() {
            List<ElementDto> elements = List.of(
                    ElementDto.standard("elem-1", "Web App", "ApplicationComponent", null, "Application", null, null),
                    ElementDto.standard("elem-2", "API", "ApplicationComponent", null, "Application", null, null));
            List<RelationshipDto> relationships = List.of(
                    new RelationshipDto("rel-1", "Serves", "ServingRelationship", "elem-1", "elem-2"));
            List<ViewNodeDto> visualMetadata = List.of(
                    new ViewNodeDto("vo-1", "elem-1", 100, 50, 120, 55),
                    new ViewNodeDto("vo-2", "elem-2", 300, 50, 120, 55));
            List<ViewConnectionDto> connections = List.of(
                    new ViewConnectionDto("conn-1", "rel-1", "ServingRelationship", "vo-1", "vo-2", null));
            return new ViewContentsDto("view-simple", "Simple View", null, null,
                    elements, relationships, visualMetadata, connections, List.of(), List.of());
        }

        /** rel-1 drawn twice (conn-1a, conn-1b); elem-1 placed twice (vo-1a, vo-1b). */
        private ViewContentsDto multiDrawView() {
            List<ElementDto> elements = List.of(
                    ElementDto.standard("elem-1", "Shared Service", "ApplicationService", null, "Application", null, null),
                    ElementDto.standard("elem-2", "Consumer", "ApplicationComponent", null, "Application", null, null));
            List<RelationshipDto> relationships = List.of(
                    new RelationshipDto("rel-1", "Serves", "ServingRelationship", "elem-1", "elem-2"));
            List<ViewNodeDto> visualMetadata = List.of(
                    new ViewNodeDto("vo-1a", "elem-1", 100, 50, 120, 55),
                    new ViewNodeDto("vo-1b", "elem-1", 100, 200, 120, 55),
                    new ViewNodeDto("vo-2", "elem-2", 300, 50, 120, 55));
            List<ViewConnectionDto> connections = List.of(
                    new ViewConnectionDto("conn-1a", "rel-1", "ServingRelationship", "vo-1a", "vo-2", null),
                    new ViewConnectionDto("conn-1b", "rel-1", "ServingRelationship", "vo-1b", "vo-2", null));
            return new ViewContentsDto("view-multidraw", "Multi-draw View", null, null,
                    elements, relationships, visualMetadata, connections, List.of(), List.of());
        }

        /** 1 group (with its own viewObjectId) + 1 note + 1 element placed once inside the group. */
        private ViewContentsDto groupsAndNotesView() {
            List<ElementDto> elements = List.of(
                    ElementDto.standard("elem-1", "Server", "Node", null, "Technology", null, null));
            List<ViewNodeDto> visualMetadata = List.of(
                    new ViewNodeDto("vo-1", "elem-1", 10, 10, 120, 55, "grp-1"));
            List<ViewGroupDto> groups = List.of(
                    new ViewGroupDto("grp-1", "Infrastructure", 0, 0, 250, 200, null, List.of()));
            List<ViewNoteDto> notes = List.of(
                    new ViewNoteDto("note-1", "Design note", 300, 200, 150, 40, null));
            return new ViewContentsDto("view-groups-notes", "Groups and Notes View", null, null,
                    elements, List.of(), visualMetadata, List.of(), groups, notes);
        }

        /** A relationship and an element with NO drawn connection / placement (empty visuals). */
        private ViewContentsDto noVisualsView() {
            List<ElementDto> elements = List.of(
                    ElementDto.standard("elem-1", "Orphan", "ApplicationComponent", null, "Application", null, null),
                    ElementDto.standard("elem-2", "Other", "ApplicationComponent", null, "Application", null, null));
            List<RelationshipDto> relationships = List.of(
                    new RelationshipDto("rel-1", "Serves", "ServingRelationship", "elem-1", "elem-2"));
            // visualMetadata + connections intentionally empty.
            return new ViewContentsDto("view-no-visuals", "No Visuals View", null, null,
                    elements, relationships, List.of(), List.of(), List.of(), List.of());
        }
    }
}
