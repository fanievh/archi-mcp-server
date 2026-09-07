package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.*;

import java.util.LinkedHashMap;
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
import net.vheerden.archi.mcp.model.NoModelLoadedException;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.ConceptUsageDto;
import net.vheerden.archi.mcp.response.dto.ConceptUsageDto.ViewReferenceDto;
import net.vheerden.archi.mcp.response.dto.ConceptUsageDto.VisualObjectReferenceDto;
import net.vheerden.archi.mcp.response.dto.FolderDto;
import net.vheerden.archi.mcp.response.dto.ViewDto;

/**
 * Unit tests for the find-concept-usage tool (G10) on
 * {@link ModelQueryHandler}. Uses a stub accessor — no EMF/OSGi runtime.
 */
public class FindConceptUsageHandlerTest {

    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {};

    private CommandRegistry registry;
    private ResponseFormatter formatter;
    private ObjectMapper objectMapper;

    @Before
    public void setUp() {
        registry = new CommandRegistry();
        formatter = new ResponseFormatter();
        objectMapper = new ObjectMapper();
    }

    @Test
    public void shouldRegisterFindConceptUsageTool() {
        StubAccessor accessor = new StubAccessor();
        new ModelQueryHandler(accessor, formatter, registry, null).registerTools();
        McpSchema.Tool tool = findToolSpec("find-concept-usage").tool();
        assertEquals("find-concept-usage", tool.name());
        assertNotNull(tool.description());
        assertTrue("description mentions where-used",
                tool.description().toLowerCase().contains("where-used")
                        || tool.description().toLowerCase().contains("usage"));
        assertNotNull(tool.inputSchema());
        assertEquals("object", tool.inputSchema().type());
        assertTrue("schema requires conceptId",
                tool.inputSchema().required() != null
                        && tool.inputSchema().required().contains("conceptId"));
    }

    @Test
    public void shouldReturnUsageForElement_withOneViewReference() throws Exception {
        StubAccessor accessor = new StubAccessor();
        accessor.canned = elementUsage("c1", 1);
        new ModelQueryHandler(accessor, formatter, registry, null).registerTools();

        McpSchema.CallToolResult result = invoke("c1");
        assertFalse(result.isError());
        Map<String, Object> env = parseJson(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> resultMap = (Map<String, Object>) env.get("result");
        assertEquals("c1", resultMap.get("conceptId"));
        assertEquals(1, ((Number) resultMap.get("viewReferenceCount")).intValue());
    }

    @Test
    public void shouldReturnUsageForRelationship_withOneViewReference() throws Exception {
        StubAccessor accessor = new StubAccessor();
        accessor.canned = relationshipUsage("r1");
        new ModelQueryHandler(accessor, formatter, registry, null).registerTools();

        McpSchema.CallToolResult result = invoke("r1");
        assertFalse(result.isError());
        Map<String, Object> env = parseJson(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> resultMap = (Map<String, Object>) env.get("result");
        assertEquals("relationship", resultMap.get("conceptKind"));
    }

    @Test
    public void shouldReturnEmptyVisualObjects_whenOrphanConcept() throws Exception {
        StubAccessor accessor = new StubAccessor();
        accessor.canned = elementUsage("orphan", 0);
        new ModelQueryHandler(accessor, formatter, registry, null).registerTools();

        McpSchema.CallToolResult result = invoke("orphan");
        assertFalse(result.isError());
        Map<String, Object> env = parseJson(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> resultMap = (Map<String, Object>) env.get("result");
        assertEquals(0, ((Number) resultMap.get("viewReferenceCount")).intValue());
        assertEquals(0, ((Number) resultMap.get("visualReferenceCount")).intValue());
    }

    @Test
    public void shouldRejectMissingConceptId() throws Exception {
        StubAccessor accessor = new StubAccessor();
        new ModelQueryHandler(accessor, formatter, registry, null).registerTools();
        McpSchema.CallToolResult result = invoke(null);
        assertTrue(result.isError());
        Map<String, Object> env = parseJson(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) env.get("error");
        assertEquals("INVALID_PARAMETER", err.get("code"));
    }

    @Test
    public void shouldRejectBlankConceptId() throws Exception {
        StubAccessor accessor = new StubAccessor();
        new ModelQueryHandler(accessor, formatter, registry, null).registerTools();
        McpSchema.CallToolResult result = invoke("   ");
        assertTrue(result.isError());
        Map<String, Object> env = parseJson(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) env.get("error");
        assertEquals("INVALID_PARAMETER", err.get("code"));
    }

    @Test
    public void shouldReturn404_whenConceptIdNotFound() throws Exception {
        StubAccessor accessor = new StubAccessor(); // canned == null → returns Optional.empty
        new ModelQueryHandler(accessor, formatter, registry, null).registerTools();
        McpSchema.CallToolResult result = invoke("unknown-id");
        assertTrue(result.isError());
        Map<String, Object> env = parseJson(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) env.get("error");
        assertEquals("ELEMENT_NOT_FOUND", err.get("code"));
        assertTrue("message mentions ID",
                ((String) err.get("message")).contains("unknown-id"));
    }

    @Test
    public void shouldRejectFolderId_withInvalidParameter() throws Exception {
        StubAccessor accessor = new StubAccessor();
        accessor.knownFolderIds.add("folder-1");
        new ModelQueryHandler(accessor, formatter, registry, null).registerTools();
        McpSchema.CallToolResult result = invoke("folder-1");
        assertTrue(result.isError());
        Map<String, Object> env = parseJson(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) env.get("error");
        assertEquals("INVALID_PARAMETER", err.get("code"));
        assertTrue("message mentions folder",
                ((String) err.get("message")).contains("folder"));
    }

    @Test
    public void shouldRejectViewId_withInvalidParameter() throws Exception {
        StubAccessor accessor = new StubAccessor();
        accessor.knownViewIds.add("view-1");
        new ModelQueryHandler(accessor, formatter, registry, null).registerTools();
        McpSchema.CallToolResult result = invoke("view-1");
        assertTrue(result.isError());
        Map<String, Object> env = parseJson(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) env.get("error");
        assertEquals("INVALID_PARAMETER", err.get("code"));
        assertTrue("message mentions view",
                ((String) err.get("message")).contains("view"));
    }

    @Test
    public void shouldReturnConceptKindElement_forArchimateElement() throws Exception {
        StubAccessor accessor = new StubAccessor();
        accessor.canned = elementUsage("e1", 1);
        new ModelQueryHandler(accessor, formatter, registry, null).registerTools();
        Map<String, Object> resultMap = unwrap(invoke("e1"));
        assertEquals("element", resultMap.get("conceptKind"));
    }

    @Test
    public void shouldReturnConceptKindRelationship_forArchimateRelationship() throws Exception {
        StubAccessor accessor = new StubAccessor();
        accessor.canned = relationshipUsage("r1");
        new ModelQueryHandler(accessor, formatter, registry, null).registerTools();
        Map<String, Object> resultMap = unwrap(invoke("r1"));
        assertEquals("relationship", resultMap.get("conceptKind"));
    }

    @Test
    public void shouldEmitOrphanNextSteps_whenZeroReferences() throws Exception {
        StubAccessor accessor = new StubAccessor();
        accessor.canned = elementUsage("orphan", 0);
        new ModelQueryHandler(accessor, formatter, registry, null).registerTools();
        Map<String, Object> env = parseJson(invoke("orphan"));
        @SuppressWarnings("unchecked")
        List<String> steps = (List<String>) env.get("nextSteps");
        assertNotNull(steps);
        assertTrue("at least one orphan-specific suggestion",
                steps.stream().anyMatch(s -> s.toLowerCase().contains("safe to delete")));
    }

    @Test
    public void shouldEmitSingleViewNextSteps_whenOneReference() throws Exception {
        StubAccessor accessor = new StubAccessor();
        accessor.canned = elementUsage("c1", 1);
        new ModelQueryHandler(accessor, formatter, registry, null).registerTools();
        Map<String, Object> env = parseJson(invoke("c1"));
        @SuppressWarnings("unchecked")
        List<String> steps = (List<String>) env.get("nextSteps");
        assertTrue("mentions remove-from-view",
                steps.stream().anyMatch(s -> s.contains("remove-from-view")));
    }

    @Test
    public void shouldEmitMultiViewNextSteps_whenManyReferences() throws Exception {
        StubAccessor accessor = new StubAccessor();
        accessor.canned = elementUsage("c1", 3);
        new ModelQueryHandler(accessor, formatter, registry, null).registerTools();
        Map<String, Object> env = parseJson(invoke("c1"));
        @SuppressWarnings("unchecked")
        List<String> steps = (List<String>) env.get("nextSteps");
        assertTrue("mentions cascade",
                steps.stream().anyMatch(s -> s.toLowerCase().contains("cascade")));
    }

    @Test
    public void shouldRejectModelNotLoaded() throws Exception {
        StubAccessor accessor = new StubAccessor();
        accessor.modelLoadedFlag = false;
        new ModelQueryHandler(accessor, formatter, registry, null).registerTools();
        McpSchema.CallToolResult result = invoke("any-id");
        assertTrue(result.isError());
        Map<String, Object> env = parseJson(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) env.get("error");
        assertEquals("MODEL_NOT_LOADED", err.get("code"));
    }

    // ---- Helpers ----

    private McpSchema.CallToolResult invoke(String conceptId) {
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("find-concept-usage");
        Map<String, Object> args = new LinkedHashMap<>();
        if (conceptId != null) {
            args.put("conceptId", conceptId);
        }
        McpSchema.CallToolRequest req = new McpSchema.CallToolRequest("find-concept-usage", args);
        return spec.callHandler().apply(null, req);
    }

    private McpServerFeatures.SyncToolSpecification findToolSpec(String name) {
        return registry.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals(name))
                .findFirst().orElseThrow();
    }

    private Map<String, Object> parseJson(McpSchema.CallToolResult result) throws Exception {
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        return objectMapper.readValue(text, MAP_TYPE);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unwrap(McpSchema.CallToolResult result) throws Exception {
        return (Map<String, Object>) parseJson(result).get("result");
    }

    private static ConceptUsageDto elementUsage(String id, int viewCount) {
        List<ViewReferenceDto> views = new java.util.ArrayList<>();
        for (int i = 0; i < viewCount; i++) {
            views.add(new ViewReferenceDto(
                    "v-" + i, "View " + i, null, "archimate",
                    List.of(new VisualObjectReferenceDto("vo-" + id + "-" + i, "object"))));
        }
        return new ConceptUsageDto(id, "Name-" + id, "ApplicationComponent", "element",
                viewCount, viewCount, views, null);
    }

    private static ConceptUsageDto relationshipUsage(String id) {
        return new ConceptUsageDto(id, "serves-" + id, "ServingRelationship", "relationship",
                1, 1, List.of(new ViewReferenceDto(
                        "v-0", "View", null, "archimate",
                        List.of(new VisualObjectReferenceDto("conn-" + id, "connection")))),
                null);
    }

    // ---- Stub Accessor ----

    private static class StubAccessor extends BaseTestAccessor {
        ConceptUsageDto canned;
        boolean modelLoadedFlag = true;
        final java.util.Set<String> knownViewIds = new java.util.HashSet<>();
        final java.util.Set<String> knownFolderIds = new java.util.HashSet<>();

        StubAccessor() {
            super(true);
        }

        @Override
        public boolean isModelLoaded() {
            return modelLoadedFlag;
        }

        @Override
        public Optional<ConceptUsageDto> findConceptUsage(String conceptId) {
            if (!modelLoadedFlag) throw new NoModelLoadedException();
            if (canned != null && canned.conceptId().equals(conceptId)) {
                return Optional.of(canned);
            }
            return Optional.empty();
        }

        @Override
        public String getModelVersion() {
            return "test-version";
        }

        @Override
        public List<ViewDto> getViews(String viewpointFilter) {
            return knownViewIds.stream()
                    .map(id -> new ViewDto(id, "View " + id, null, "/Views"))
                    .toList();
        }

        @Override
        public Optional<FolderDto> getFolderById(String id) {
            if (knownFolderIds.contains(id)) {
                return Optional.of(new FolderDto(id, "Folder " + id, "USER", null, 0, 0));
            }
            return Optional.empty();
        }
    }
}
