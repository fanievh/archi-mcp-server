package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.*;

import java.util.Collections;
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
import net.vheerden.archi.mcp.model.NoModelLoadedException;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.ModelInfoDto;
import net.vheerden.archi.mcp.response.dto.RelationshipDto;
import net.vheerden.archi.mcp.response.dto.ViewContentsDto;
import net.vheerden.archi.mcp.response.dto.ViewDto;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Unit tests for {@link TraversalHandler}.
 *
 * <p>Uses a stub extending BaseTestAccessor — no EMF/OSGi runtime required.</p>
 */
public class TraversalHandlerTest {

    private CommandRegistry registry;
    private ResponseFormatter formatter;
    private ObjectMapper objectMapper;

    @Before
    public void setUp() {
        registry = new CommandRegistry();
        formatter = new ResponseFormatter();
        objectMapper = new ObjectMapper();
    }

    // ---- Tool Registration Tests ----

    @Test
    public void shouldRegisterGetRelationshipsTool() {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        assertEquals(1, registry.getToolCount());
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");
        assertEquals("get-relationships", spec.tool().name());
    }

    @Test
    public void shouldHaveDescriptionInToolSchema() {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.Tool tool = findToolSpec("get-relationships").tool();
        assertNotNull(tool.description());
        assertTrue(tool.description().contains("relationship"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldHaveElementIdRequiredAndDepthOptional() {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.Tool tool = findToolSpec("get-relationships").tool();
        assertNotNull(tool.inputSchema());
        assertEquals("object", tool.inputSchema().type());

        Map<String, Object> properties = tool.inputSchema().properties();
        assertNotNull(properties);
        assertTrue(properties.containsKey("elementId"));
        assertTrue(properties.containsKey("depth"));
        assertTrue(properties.containsKey("traverse"));
        assertTrue(properties.containsKey("maxDepth"));
        assertTrue(properties.containsKey("direction"));

        Map<String, Object> elementIdProp = (Map<String, Object>) properties.get("elementId");
        assertEquals("string", elementIdProp.get("type"));

        Map<String, Object> depthProp = (Map<String, Object>) properties.get("depth");
        assertEquals("integer", depthProp.get("type"));

        Map<String, Object> traverseProp = (Map<String, Object>) properties.get("traverse");
        assertEquals("boolean", traverseProp.get("type"));

        Map<String, Object> maxDepthProp = (Map<String, Object>) properties.get("maxDepth");
        assertEquals("integer", maxDepthProp.get("type"));

        Map<String, Object> directionProp = (Map<String, Object>) properties.get("direction");
        assertEquals("string", directionProp.get("type"));

        // elementId is required, others are not
        List<String> required = tool.inputSchema().required();
        assertNotNull(required);
        assertTrue(required.contains("elementId"));
        assertFalse(required.contains("depth"));
        assertFalse(required.contains("traverse"));
        assertFalse(required.contains("maxDepth"));
        assertFalse(required.contains("direction"));
    }

    // ---- Constructor Validation ----

    @Test(expected = NullPointerException.class)
    public void shouldRejectNullAccessor() {
        new TraversalHandler(null, formatter, registry, null);
    }

    @Test(expected = NullPointerException.class)
    public void shouldRejectNullFormatter() {
        new TraversalHandler(new StubAccessor(true), null, registry, null);
    }

    @Test(expected = NullPointerException.class)
    public void shouldRejectNullRegistry() {
        new TraversalHandler(new StubAccessor(true), formatter, null, null);
    }

    // ---- Depth 0 Tests (AC #1) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnRelationshipDtoFields_atDepth0() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetRelationships("elem-1", 0);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertEquals(1, resultList.size());

        Map<String, Object> rel = resultList.get(0);
        assertEquals("rel-1", rel.get("id"));
        assertEquals("Serves", rel.get("name"));
        assertEquals("ServingRelationship", rel.get("type"));
        assertEquals("elem-1", rel.get("sourceId"));
        assertEquals("elem-2", rel.get("targetId"));

        // Depth 0 should NOT have expanded source/target objects
        assertFalse("Depth 0 should not have 'source' key", rel.containsKey("source"));
        assertFalse("Depth 0 should not have 'target' key", rel.containsKey("target"));
    }

    // ---- Depth 1 Tests (AC #2) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInlineSummaries_atDepth1() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetRelationships("elem-1", 1);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertEquals(1, resultList.size());

        Map<String, Object> rel = resultList.get(0);
        assertEquals("rel-1", rel.get("id"));
        assertEquals("Serves", rel.get("name"));
        assertEquals("ServingRelationship", rel.get("type"));

        // Source should be a summary: id, name, type only
        Map<String, Object> source = (Map<String, Object>) rel.get("source");
        assertNotNull("Depth 1 should have source", source);
        assertEquals("elem-1", source.get("id"));
        assertEquals("App Component", source.get("name"));
        assertEquals("ApplicationComponent", source.get("type"));
        // Summary should NOT have layer or documentation
        assertFalse("Summary should not have layer", source.containsKey("layer"));
        assertFalse("Summary should not have documentation", source.containsKey("documentation"));

        // Target should also be a summary
        Map<String, Object> target = (Map<String, Object>) rel.get("target");
        assertNotNull("Depth 1 should have target", target);
        assertEquals("elem-2", target.get("id"));
        assertEquals("Business Process", target.get("name"));
        assertEquals("BusinessProcess", target.get("type"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnCorrectSourceAndTarget_whenQueryingFromTargetSide() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // Query elem-2 which is the TARGET of rel-1 (elem-1 -> elem-2) and SOURCE of rel-2
        McpSchema.CallToolResult result = invokeGetRelationships("elem-2", 1);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertEquals(2, resultList.size());

        // Find rel-1 in the list
        Map<String, Object> rel1 = resultList.stream()
                .filter(r -> "rel-1".equals(r.get("id")))
                .findFirst().orElseThrow();
        // Source should be elem-1 (not the queried element)
        Map<String, Object> source = (Map<String, Object>) rel1.get("source");
        assertEquals("elem-1", source.get("id"));
        assertEquals("App Component", source.get("name"));
        // Target should be elem-2 (the queried element)
        Map<String, Object> target = (Map<String, Object>) rel1.get("target");
        assertEquals("elem-2", target.get("id"));
        assertEquals("Business Process", target.get("name"));
    }

    // ---- Depth 2 Tests (AC #3) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnFullElementDto_atDepth2() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetRelationships("elem-1", 2);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertEquals(1, resultList.size());

        Map<String, Object> rel = resultList.get(0);

        // Source should have full element fields
        Map<String, Object> source = (Map<String, Object>) rel.get("source");
        assertNotNull(source);
        assertEquals("elem-1", source.get("id"));
        assertEquals("App Component", source.get("name"));
        assertEquals("ApplicationComponent", source.get("type"));
        assertEquals("Application", source.get("layer"));
        assertEquals("A test application component", source.get("documentation"));

        // Target should also have full element fields
        Map<String, Object> target = (Map<String, Object>) rel.get("target");
        assertNotNull(target);
        assertEquals("elem-2", target.get("id"));
        assertEquals("Business Process", target.get("name"));
        assertEquals("BusinessProcess", target.get("type"));
        assertEquals("Business", target.get("layer"));
        assertEquals("A test business process", target.get("documentation"));

        // Depth 2 should NOT have nested relationships
        assertFalse("Depth 2 source should not have relationships",
                source.containsKey("relationships"));
        assertFalse("Depth 2 target should not have relationships",
                target.containsKey("relationships"));
    }

    // ---- Depth 3 Tests (AC #4) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnFullElementDtoWithNestedRelationships_atDepth3() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetRelationships("elem-1", 3);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertEquals(1, resultList.size());

        Map<String, Object> rel = resultList.get(0);

        // Source element should have full fields + relationships array
        Map<String, Object> source = (Map<String, Object>) rel.get("source");
        assertNotNull(source);
        assertEquals("elem-1", source.get("id"));
        assertEquals("App Component", source.get("name"));
        assertEquals("ApplicationComponent", source.get("type"));
        assertEquals("Application", source.get("layer"));
        assertEquals("A test application component", source.get("documentation"));
        // Source is the queried element — its own relationships are included
        List<Map<String, Object>> sourceRels =
                (List<Map<String, Object>>) source.get("relationships");
        assertNotNull("Depth 3 source should have relationships", sourceRels);
        assertEquals("Queried element should include its own relationships", 1, sourceRels.size());
        assertEquals("rel-1", sourceRels.get(0).get("id"));

        // Target element should have full fields + nested relationships
        Map<String, Object> target = (Map<String, Object>) rel.get("target");
        assertNotNull(target);
        assertEquals("elem-2", target.get("id"));
        assertEquals("Business Process", target.get("name"));
        assertNotNull("Depth 3 target should have relationships", target.get("relationships"));
        List<Map<String, Object>> nestedRels =
                (List<Map<String, Object>>) target.get("relationships");
        // elem-2 has 2 relationships: rel-1 (as target) and rel-2 (as source)
        assertEquals(2, nestedRels.size());
    }

    // ---- Default Depth Tests (AC #5) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldDefaultToDepth1_whenNoDepthParameter() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // Invoke without depth parameter
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "get-relationships", Map.of("elementId", "elem-1"));
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertEquals(1, resultList.size());

        Map<String, Object> rel = resultList.get(0);

        // Should be depth 1 behavior: source/target are summaries
        Map<String, Object> source = (Map<String, Object>) rel.get("source");
        assertNotNull("Default depth should include source summary", source);
        assertEquals("elem-1", source.get("id"));
        assertEquals("App Component", source.get("name"));
        assertEquals("ApplicationComponent", source.get("type"));
        // Summary should NOT have full details
        assertFalse("Default depth summary should not have layer", source.containsKey("layer"));
    }

    // ---- Element Not Found Tests (AC #6) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnElementNotFoundError_whenIdNotFound() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetRelationships("nonexistent-id", 1);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("ELEMENT_NOT_FOUND", error.get("code"));
        assertNotNull(error.get("message"));
        assertTrue(((String) error.get("message")).contains("nonexistent-id"));
        assertEquals("Use search-elements to find elements by name or type",
                error.get("suggestedCorrection"));
    }

    // ---- No Relationships Tests ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnEmptyResult_whenElementHasNoRelationships() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // elem-4 exists but has no relationships
        McpSchema.CallToolResult result = invokeGetRelationships("elem-4", 0);

        assertFalse("No relationships is NOT an error", result.isError());
        Map<String, Object> envelope = parseJson(result);

        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertTrue("Result should be empty", resultList.isEmpty());

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(0, meta.get("resultCount"));
        assertEquals(0, meta.get("totalCount"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnEmptyResult_whenElementHasNoRelationships_depth1() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetRelationships("elem-4", 1);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertTrue(resultList.isEmpty());
    }

    // ---- Error Path Tests (AC #6) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnModelNotLoadedError_whenNoModelLoaded() throws Exception {
        StubAccessor accessor = new StubAccessor(false);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetRelationships("any-id", 1);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("MODEL_NOT_LOADED", error.get("code"));
        assertEquals("Open an ArchiMate model in ArchimateTool", error.get("suggestedCorrection"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInternalError_whenUnexpectedException() throws Exception {
        ExplodingAccessor accessor = new ExplodingAccessor();
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetRelationships("elem-1", 1);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("INTERNAL_ERROR", error.get("code"));
    }

    // ---- Validation Edge Cases ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameterError_whenElementIdMissing() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "get-relationships", Collections.emptyMap());
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertNotNull(error.get("suggestedCorrection"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameterError_whenElementIdBlank() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "get-relationships", Map.of("elementId", "   "));
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameterError_whenElementIdNotString() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "get-relationships", Map.of("elementId", 123));
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameterError_whenArgumentsNull() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "get-relationships", null);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    // ---- Depth Validation Tests ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameterError_whenDepthIsString() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");
        Map<String, Object> args = new HashMap<>();
        args.put("elementId", "elem-1");
        args.put("depth", "1");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "get-relationships", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertNotNull(error.get("suggestedCorrection"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameterError_whenDepthBelowMinimum() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetRelationships("elem-1", -1);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue(((String) error.get("message")).contains("-1"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameterError_whenDepthAboveMaximum() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetRelationships("elem-1", 4);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue(((String) error.get("message")).contains("4"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameterError_whenDepthIsBoolean() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");
        Map<String, Object> args = new HashMap<>();
        args.put("elementId", "elem-1");
        args.put("depth", true);
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "get-relationships", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    // ---- Boundary Depth Values ----

    @Test
    public void shouldSucceed_whenDepthIsZero() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetRelationships("elem-1", 0);
        assertFalse(result.isError());
    }

    @Test
    public void shouldSucceed_whenDepthIsThree() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetRelationships("elem-1", 3);
        assertFalse(result.isError());
    }

    // ---- nextSteps Tests (AC #7) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnNextSteps_whenRelationshipsExist() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetRelationships("elem-1", 1);

        Map<String, Object> envelope = parseJson(result);
        List<String> nextSteps = (List<String>) envelope.get("nextSteps");
        assertNotNull(nextSteps);
        assertEquals(2, nextSteps.size());
        assertTrue(nextSteps.get(0).contains("get-element"));
        assertTrue(nextSteps.get(1).contains("search-elements"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnDifferentNextSteps_whenNoRelationships() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetRelationships("elem-4", 0);

        Map<String, Object> envelope = parseJson(result);
        List<String> nextSteps = (List<String>) envelope.get("nextSteps");
        assertNotNull(nextSteps);
        assertEquals(2, nextSteps.size());
        assertTrue(nextSteps.get(0).contains("search-elements"));
        assertTrue(nextSteps.get(1).contains("get-model-info"));
    }

    // ---- _meta Tests (AC #7) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldIncludeMetadata_inSuccessResponse() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetRelationships("elem-1", 0);

        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertNotNull(meta);
        assertEquals("42", meta.get("modelVersion"));
        assertEquals(1, meta.get("resultCount"));
        assertEquals(1, meta.get("totalCount"));
        assertEquals(false, meta.get("isTruncated"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnZeroCounts_whenNoRelationships() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetRelationships("elem-4", 0);

        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertNotNull(meta);
        assertEquals(0, meta.get("resultCount"));
        assertEquals(0, meta.get("totalCount"));
    }

    // ==== Traversal Mode Tests (Story 4.2) ====

    // ---- Outgoing Traversal (AC #1, #2) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldTraverseOutgoing_withChain() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeTraversal("elem-1", 3, "outgoing");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> resultMap = (Map<String, Object>) envelope.get("result");
        assertNotNull(resultMap);

        // Start element
        Map<String, Object> startElement = (Map<String, Object>) resultMap.get("startElement");
        assertEquals("elem-1", startElement.get("id"));
        assertEquals("App Component", startElement.get("name"));
        assertEquals("ApplicationComponent", startElement.get("type"));

        // Hops
        List<Map<String, Object>> hops = (List<Map<String, Object>>) resultMap.get("hops");
        assertNotNull(hops);
        assertEquals(2, hops.size());

        // Hop 1: elem-1 -> elem-2 via rel-1
        Map<String, Object> hop1 = hops.get(0);
        assertEquals(1, hop1.get("hopLevel"));
        List<Map<String, Object>> hop1Rels = (List<Map<String, Object>>) hop1.get("relationships");
        assertEquals(1, hop1Rels.size());
        assertEquals("rel-1", hop1Rels.get(0).get("id"));
        Map<String, Object> connected1 = (Map<String, Object>) hop1Rels.get(0).get("connectedElement");
        assertEquals("elem-2", connected1.get("id"));
        assertEquals("Business Process", connected1.get("name"));
        assertEquals("BusinessProcess", connected1.get("type"));

        // Hop 2: elem-2 -> elem-3 via rel-2
        Map<String, Object> hop2 = hops.get(1);
        assertEquals(2, hop2.get("hopLevel"));
        List<Map<String, Object>> hop2Rels = (List<Map<String, Object>>) hop2.get("relationships");
        assertEquals(1, hop2Rels.size());
        assertEquals("rel-2", hop2Rels.get(0).get("id"));
        Map<String, Object> connected2 = (Map<String, Object>) hop2Rels.get(0).get("connectedElement");
        assertEquals("elem-3", connected2.get("id"));
        assertEquals("Tech Service", connected2.get("name"));
        assertEquals("TechnologyService", connected2.get("type"));

        // Traversal summary
        Map<String, Object> summary = (Map<String, Object>) resultMap.get("traversalSummary");
        assertEquals(2, summary.get("totalElementsDiscovered"));
        assertEquals(2, summary.get("totalRelationships"));
        assertEquals(2, summary.get("maxDepthReached"));
        assertEquals(false, summary.get("cyclesDetected"));
    }

    // ---- Incoming Traversal (AC #3) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldTraverseIncoming() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // Traverse incoming from elem-3 — should follow rel-2 backward to elem-2, then rel-1 backward to elem-1
        McpSchema.CallToolResult result = invokeTraversal("elem-3", 3, "incoming");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> resultMap = (Map<String, Object>) envelope.get("result");

        Map<String, Object> startElement = (Map<String, Object>) resultMap.get("startElement");
        assertEquals("elem-3", startElement.get("id"));

        List<Map<String, Object>> hops = (List<Map<String, Object>>) resultMap.get("hops");
        assertEquals(2, hops.size());

        // Hop 1: incoming to elem-3 — rel-2 (elem-2 -> elem-3) → discover elem-2
        Map<String, Object> hop1 = hops.get(0);
        assertEquals(1, hop1.get("hopLevel"));
        List<Map<String, Object>> hop1Rels = (List<Map<String, Object>>) hop1.get("relationships");
        assertEquals(1, hop1Rels.size());
        assertEquals("rel-2", hop1Rels.get(0).get("id"));
        Map<String, Object> connected1 = (Map<String, Object>) hop1Rels.get(0).get("connectedElement");
        assertEquals("elem-2", connected1.get("id"));

        // Hop 2: incoming to elem-2 — rel-1 (elem-1 -> elem-2) → discover elem-1
        Map<String, Object> hop2 = hops.get(1);
        assertEquals(2, hop2.get("hopLevel"));
        List<Map<String, Object>> hop2Rels = (List<Map<String, Object>>) hop2.get("relationships");
        assertEquals(1, hop2Rels.size());
        assertEquals("rel-1", hop2Rels.get(0).get("id"));
        Map<String, Object> connected2 = (Map<String, Object>) hop2Rels.get(0).get("connectedElement");
        assertEquals("elem-1", connected2.get("id"));
    }

    // ---- Bidirectional Traversal (AC #4) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldTraverseBidirectional() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // From elem-2, direction=both: discovers elem-1 (via rel-1) and elem-3 (via rel-2) in hop 1
        McpSchema.CallToolResult result = invokeTraversal("elem-2", 3, "both");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> resultMap = (Map<String, Object>) envelope.get("result");

        List<Map<String, Object>> hops = (List<Map<String, Object>>) resultMap.get("hops");
        assertEquals(1, hops.size()); // All elements discovered in 1 hop from elem-2

        Map<String, Object> hop1 = hops.get(0);
        List<Map<String, Object>> hop1Rels = (List<Map<String, Object>>) hop1.get("relationships");
        assertEquals(2, hop1Rels.size()); // rel-1 and rel-2

        Map<String, Object> summary = (Map<String, Object>) resultMap.get("traversalSummary");
        assertEquals(2, summary.get("totalElementsDiscovered")); // elem-1 and elem-3
        // With direction="both", hop 2 revisits elem-2 via back-edges from elem-1 and elem-3.
        // This is not a true directed cycle but the BFS dedup correctly flags it.
        assertEquals(true, summary.get("cyclesDetected"));
    }

    // ---- Cycle Detection (AC #5) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldDetectCycle_andNotRevisitElements() throws Exception {
        CycleStubAccessor accessor = new CycleStubAccessor();
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // Chain: elem-1 -> elem-2 -> elem-3 -> elem-1 (cycle)
        McpSchema.CallToolResult result = invokeTraversal("elem-1", 5, "outgoing");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> resultMap = (Map<String, Object>) envelope.get("result");

        List<Map<String, Object>> hops = (List<Map<String, Object>>) resultMap.get("hops");
        // elem-1 -> elem-2 (hop 1), elem-2 -> elem-3 (hop 2), elem-3 -> elem-1 (cycle, skipped)
        assertEquals(2, hops.size());

        Map<String, Object> summary = (Map<String, Object>) resultMap.get("traversalSummary");
        assertEquals(true, summary.get("cyclesDetected"));
        assertEquals(2, summary.get("totalElementsDiscovered")); // elem-2 and elem-3
        assertEquals(2, summary.get("totalRelationships"));

        // Verify elem-1 is NOT revisited (no hop 3 with elem-1)
        assertEquals(2, summary.get("maxDepthReached"));
    }

    // ---- Depth Limit Enforcement / Truncation (AC #6) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldTruncateAtMaxDepth_whenDeeperPathsExist() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // maxDepth=1 from elem-1: discovers elem-2 at hop 1, but elem-3 is at hop 2 (truncated)
        McpSchema.CallToolResult result = invokeTraversal("elem-1", 1, "outgoing");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> resultMap = (Map<String, Object>) envelope.get("result");

        List<Map<String, Object>> hops = (List<Map<String, Object>>) resultMap.get("hops");
        assertEquals(1, hops.size());

        Map<String, Object> summary = (Map<String, Object>) resultMap.get("traversalSummary");
        assertEquals(1, summary.get("totalElementsDiscovered"));
        assertEquals(1, summary.get("maxDepthReached"));

        // _meta.isTruncated should be true (deeper paths exist)
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(true, meta.get("isTruncated"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldNotTruncate_whenAllElementsDiscovered() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // maxDepth=5 from elem-1: discovers all elements, no truncation
        McpSchema.CallToolResult result = invokeTraversal("elem-1", 5, "outgoing");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(false, meta.get("isTruncated"));
    }

    // ---- Traverse=false Preserves Story 4-1 Behavior ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldPreserveStory4_1Behavior_whenTraverseFalse() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // Explicitly set traverse=false
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");
        Map<String, Object> args = new HashMap<>();
        args.put("elementId", "elem-1");
        args.put("depth", 1);
        args.put("traverse", false);
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "get-relationships", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        // Should return depth-1 format (list of relationships with summaries), not traversal format
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertEquals(1, resultList.size());

        Map<String, Object> rel = resultList.get(0);
        assertTrue(rel.containsKey("source")); // depth 1 has inline source
        assertTrue(rel.containsKey("target")); // depth 1 has inline target
        assertFalse(rel.containsKey("connectedElement")); // NOT traversal format
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldDefaultToNoTraversal_whenTraverseAbsent() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // No traverse parameter → Story 4-1 behavior
        McpSchema.CallToolResult result = invokeGetRelationships("elem-1", 1);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        // Story 4-1 format: list of relationships (not traversal object)
        assertTrue(resultList.get(0).containsKey("source"));
    }

    // ---- Traversal Validation Edge Cases ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameter_whenTraverseNotBoolean() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");
        Map<String, Object> args = new HashMap<>();
        args.put("elementId", "elem-1");
        args.put("traverse", "yes");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "get-relationships", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue(((String) error.get("message")).contains("traverse"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameter_whenDirectionInvalid() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeTraversal("elem-1", 3, "sideways");

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue(((String) error.get("message")).contains("direction"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameter_whenDirectionNotString() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");
        Map<String, Object> args = new HashMap<>();
        args.put("elementId", "elem-1");
        args.put("traverse", true);
        args.put("direction", 42);
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "get-relationships", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameter_whenMaxDepthTooLow() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeTraversal("elem-1", 0, "outgoing");

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue(((String) error.get("message")).contains("0"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameter_whenMaxDepthTooHigh() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeTraversal("elem-1", 6, "outgoing");

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue(((String) error.get("message")).contains("6"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameter_whenMaxDepthNotInteger() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");
        Map<String, Object> args = new HashMap<>();
        args.put("elementId", "elem-1");
        args.put("traverse", true);
        args.put("maxDepth", "three");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "get-relationships", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue(((String) error.get("message")).contains("maxDepth"));
    }

    // ---- Empty Traversal ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnEmptyHops_whenNoRelationshipsInDirection() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // elem-3 has no outgoing relationships (only incoming via rel-2)
        McpSchema.CallToolResult result = invokeTraversal("elem-3", 3, "outgoing");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> resultMap = (Map<String, Object>) envelope.get("result");

        List<Map<String, Object>> hops = (List<Map<String, Object>>) resultMap.get("hops");
        assertTrue("Hops should be empty when no outgoing rels", hops.isEmpty());

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(false, meta.get("isTruncated"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnEmptyHops_whenIsolatedElement() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // elem-4 has no relationships at all
        McpSchema.CallToolResult result = invokeTraversal("elem-4", 3, "both");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> resultMap = (Map<String, Object>) envelope.get("result");

        List<Map<String, Object>> hops = (List<Map<String, Object>>) resultMap.get("hops");
        assertTrue(hops.isEmpty());

        Map<String, Object> summary = (Map<String, Object>) resultMap.get("traversalSummary");
        assertEquals(0, summary.get("totalElementsDiscovered"));
        assertEquals(0, summary.get("totalRelationships"));
    }

    // ---- Error Paths with Traversal ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnElementNotFound_withTraverseTrue() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeTraversal("nonexistent", 3, "outgoing");

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("ELEMENT_NOT_FOUND", error.get("code"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnModelNotLoaded_withTraverseTrue() throws Exception {
        StubAccessor accessor = new StubAccessor(false);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeTraversal("elem-1", 3, "outgoing");

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("MODEL_NOT_LOADED", error.get("code"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInternalError_withTraverseTrue() throws Exception {
        ExplodingAccessor accessor = new ExplodingAccessor();
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeTraversal("elem-1", 3, "outgoing");

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INTERNAL_ERROR", error.get("code"));
    }

    // ---- Response Structure (AC #1) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldIncludeCorrectResponseStructure_traversal() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeTraversal("elem-1", 3, "outgoing");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        // Top-level envelope
        assertNotNull(envelope.get("result"));
        assertNotNull(envelope.get("nextSteps"));
        assertNotNull(envelope.get("_meta"));

        Map<String, Object> resultMap = (Map<String, Object>) envelope.get("result");

        // Result structure
        assertNotNull(resultMap.get("startElement"));
        assertNotNull(resultMap.get("hops"));
        assertNotNull(resultMap.get("traversalSummary"));

        // startElement has id, name, type
        Map<String, Object> startElement = (Map<String, Object>) resultMap.get("startElement");
        assertNotNull(startElement.get("id"));
        assertNotNull(startElement.get("name"));
        assertNotNull(startElement.get("type"));

        // Each hop has hopLevel and relationships
        List<Map<String, Object>> hops = (List<Map<String, Object>>) resultMap.get("hops");
        for (Map<String, Object> hop : hops) {
            assertNotNull(hop.get("hopLevel"));
            assertNotNull(hop.get("relationships"));
            List<Map<String, Object>> rels = (List<Map<String, Object>>) hop.get("relationships");
            for (Map<String, Object> rel : rels) {
                assertNotNull(rel.get("id"));
                assertNotNull(rel.get("type"));
                assertNotNull(rel.get("sourceId"));
                assertNotNull(rel.get("targetId"));
                assertNotNull(rel.get("connectedElement"));
            }
        }

        // traversalSummary fields
        Map<String, Object> summary = (Map<String, Object>) resultMap.get("traversalSummary");
        assertNotNull(summary.get("totalElementsDiscovered"));
        assertNotNull(summary.get("totalRelationships"));
        assertNotNull(summary.get("maxDepthReached"));
        assertNotNull(summary.get("cyclesDetected"));
        // "truncated" should have been removed (goes to _meta only)
        assertFalse(summary.containsKey("truncated"));

        // nextSteps
        List<String> nextSteps = (List<String>) envelope.get("nextSteps");
        assertNotNull(nextSteps);
        assertFalse(nextSteps.isEmpty());

        // _meta
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertNotNull(meta.get("modelVersion"));
        assertNotNull(meta.get("resultCount"));
        assertNotNull(meta.get("isTruncated"));
    }

    // ---- Default maxDepth and direction ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldDefaultMaxDepthTo3_whenTraverseTrue() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // Invoke traverse without maxDepth
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");
        Map<String, Object> args = new HashMap<>();
        args.put("elementId", "elem-1");
        args.put("traverse", true);
        args.put("direction", "outgoing");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "get-relationships", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> resultMap = (Map<String, Object>) envelope.get("result");

        // Chain is elem-1 -> elem-2 -> elem-3 (2 hops). Default maxDepth=3 allows all.
        List<Map<String, Object>> hops = (List<Map<String, Object>>) resultMap.get("hops");
        assertEquals(2, hops.size()); // All elements discovered within default maxDepth
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldDefaultDirectionToBoth_whenTraverseTrue() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // Invoke traverse without direction from elem-2
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");
        Map<String, Object> args = new HashMap<>();
        args.put("elementId", "elem-2");
        args.put("traverse", true);
        args.put("maxDepth", 1);
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "get-relationships", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> resultMap = (Map<String, Object>) envelope.get("result");

        // Default direction=both from elem-2: discovers elem-1 (via rel-1) and elem-3 (via rel-2)
        Map<String, Object> summary = (Map<String, Object>) resultMap.get("traversalSummary");
        assertEquals(2, summary.get("totalElementsDiscovered"));
    }

    // ---- Traversal nextSteps ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnTraversalNextSteps_whenRelationshipsFound() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeTraversal("elem-1", 3, "outgoing");

        Map<String, Object> envelope = parseJson(result);
        List<String> nextSteps = (List<String>) envelope.get("nextSteps");
        assertNotNull(nextSteps);
        assertEquals(3, nextSteps.size());
        assertTrue(nextSteps.get(0).contains("get-element"));
        assertTrue(nextSteps.get(1).contains("get-relationships"));
        assertTrue(nextSteps.get(2).contains("search-elements"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnEmptyTraversalNextSteps_whenNoRelationships() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeTraversal("elem-4", 3, "both");

        Map<String, Object> envelope = parseJson(result);
        List<String> nextSteps = (List<String>) envelope.get("nextSteps");
        assertNotNull(nextSteps);
        assertEquals(2, nextSteps.size());
        assertTrue(nextSteps.get(0).contains("search-elements"));
        assertTrue(nextSteps.get(1).contains("get-model-info"));
    }

    // ---- Hop Relationship Fields ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldIncludeNameInHopRelationships() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeTraversal("elem-1", 1, "outgoing");

        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> resultMap = (Map<String, Object>) envelope.get("result");
        List<Map<String, Object>> hops = (List<Map<String, Object>>) resultMap.get("hops");
        List<Map<String, Object>> rels = (List<Map<String, Object>>) hops.get(0).get("relationships");

        Map<String, Object> rel = rels.get(0);
        assertEquals("rel-1", rel.get("id"));
        assertEquals("Serves", rel.get("name"));
        assertEquals("ServingRelationship", rel.get("type"));
        assertEquals("elem-1", rel.get("sourceId"));
        assertEquals("elem-2", rel.get("targetId"));
    }

    // ==== Filter Tests (Story 4.3) ====

    // ---- Non-Traverse excludeTypes (Task 5.1) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldExcludeRelationshipsByType_nonTraverse() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // elem-1 only has ServingRelationship (rel-1). Exclude it → 0 results.
        McpSchema.CallToolResult result = invokeWithFilters(
                "elem-1", 0, List.of("ServingRelationship"), null, null);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertTrue("All relationships excluded", resultList.isEmpty());
    }

    // ---- Non-Traverse includeTypes (Task 5.2) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldIncludeOnlySpecifiedTypes_nonTraverse() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // elem-2 has rel-1 (ServingRelationship) and rel-2 (FlowRelationship).
        // Include only ServingRelationship → expect rel-1 only.
        McpSchema.CallToolResult result = invokeWithFilters(
                "elem-2", 0, null, List.of("ServingRelationship"), null);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertEquals(1, resultList.size());
        assertEquals("rel-1", resultList.get(0).get("id"));
        assertEquals("ServingRelationship", resultList.get(0).get("type"));
    }

    // ---- Non-Traverse filterLayer (Task 5.3) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldFilterByConnectedElementLayer_nonTraverse() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // elem-1 has rel-1 (to elem-2 which is Business layer).
        // filterLayer="Business" → should include rel-1.
        McpSchema.CallToolResult result = invokeWithFilters(
                "elem-1", 0, null, null, "Business");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertEquals(1, resultList.size());
        assertEquals("rel-1", resultList.get(0).get("id"));
    }

    // ---- Non-Traverse filterLayer excludes (Task 5.4) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldExcludeByConnectedElementLayer_nonTraverse() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // elem-1 has rel-1 (to elem-2 which is Business layer).
        // filterLayer="Technology" → elem-2 is Business, not Technology → exclude rel-1.
        McpSchema.CallToolResult result = invokeWithFilters(
                "elem-1", 0, null, null, "Technology");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertTrue("No relationships to Technology layer from elem-1", resultList.isEmpty());
    }

    // ---- Non-Traverse combined filters (Task 5.5) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldApplyCombinedFilters_nonTraverse() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // elem-2 has rel-1 (ServingRelationship, connected to elem-1/Application)
        // and rel-2 (FlowRelationship, connected to elem-3/Technology).
        // excludeTypes=["ServingRelationship"], filterLayer="Technology" →
        // rel-1 excluded by type, rel-2 passes type + connected elem-3 is Technology → expect rel-2.
        McpSchema.CallToolResult result = invokeWithFilters(
                "elem-2", 0, List.of("ServingRelationship"), null, "Technology");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertEquals(1, resultList.size());
        assertEquals("rel-2", resultList.get(0).get("id"));
    }

    // ---- Traverse excludeTypes (Task 5.6) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldExcludeRelationshipTypes_duringTraversal() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // Chain: elem-1 --ServingRel--> elem-2 --FlowRel--> elem-3.
        // Exclude "FlowRelationship" → chain stops at elem-2.
        McpSchema.CallToolResult result = invokeTraversalWithFilters(
                "elem-1", 3, "outgoing", List.of("FlowRelationship"), null, null);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> resultMap = (Map<String, Object>) envelope.get("result");

        List<Map<String, Object>> hops = (List<Map<String, Object>>) resultMap.get("hops");
        assertEquals("Only 1 hop — FlowRelationship excluded", 1, hops.size());

        Map<String, Object> summary = (Map<String, Object>) resultMap.get("traversalSummary");
        assertEquals(1, summary.get("totalElementsDiscovered"));
    }

    // ---- Traverse includeTypes (Task 5.7) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldIncludeOnlySpecifiedTypes_duringTraversal() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // Chain: elem-1 --ServingRel--> elem-2 --FlowRel--> elem-3.
        // Include only "ServingRelationship" → only hop 1 (elem-2), FlowRelationship not followed.
        McpSchema.CallToolResult result = invokeTraversalWithFilters(
                "elem-1", 3, "outgoing", null, List.of("ServingRelationship"), null);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> resultMap = (Map<String, Object>) envelope.get("result");

        List<Map<String, Object>> hops = (List<Map<String, Object>>) resultMap.get("hops");
        assertEquals("Only 1 hop — only ServingRelationship included", 1, hops.size());

        Map<String, Object> summary = (Map<String, Object>) resultMap.get("traversalSummary");
        assertEquals(1, summary.get("totalElementsDiscovered"));
    }

    // ---- Traverse filterLayer (Task 5.8) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldFilterByLayer_duringTraversal() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // Chain: elem-1 --ServingRel--> elem-2(Business) --FlowRel--> elem-3(Technology).
        // filterLayer="Business" → only follow to Business layer elements.
        // Hop 1: elem-2 is Business → included. Hop 2: elem-3 is Technology → excluded.
        McpSchema.CallToolResult result = invokeTraversalWithFilters(
                "elem-1", 3, "outgoing", null, null, "Business");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> resultMap = (Map<String, Object>) envelope.get("result");

        List<Map<String, Object>> hops = (List<Map<String, Object>>) resultMap.get("hops");
        assertEquals("Only 1 hop — Technology layer filtered out", 1, hops.size());

        Map<String, Object> summary = (Map<String, Object>) resultMap.get("traversalSummary");
        assertEquals(1, summary.get("totalElementsDiscovered"));
    }

    // ---- Traverse combined filters (Task 5.9) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldApplyCombinedFilters_duringTraversal() throws Exception {
        CycleStubAccessor accessor = new CycleStubAccessor();
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // Cycle chain: elem-1(App) --ServingRel--> elem-2(Business) --FlowRel--> elem-3(Tech)
        //              elem-3 --TriggeringRel--> elem-1
        // excludeTypes=["TriggeringRelationship"], filterLayer="Business" →
        // Hop 1 from elem-1: rel-1 ServingRel to elem-2 (Business) → PASS.
        // Hop 2 from elem-2: rel-2 FlowRel to elem-3 (Technology) → FAIL layer filter.
        // No more hops.
        McpSchema.CallToolResult result = invokeTraversalWithFilters(
                "elem-1", 5, "outgoing",
                List.of("TriggeringRelationship"), null, "Business");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> resultMap = (Map<String, Object>) envelope.get("result");

        List<Map<String, Object>> hops = (List<Map<String, Object>>) resultMap.get("hops");
        assertEquals(1, hops.size());

        Map<String, Object> summary = (Map<String, Object>) resultMap.get("traversalSummary");
        assertEquals(1, summary.get("totalElementsDiscovered"));
    }

    // ---- Validation: Invalid relationship type (Task 5.10) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameter_whenExcludeTypesContainsInvalidType() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeWithFilters(
                "elem-1", 0, List.of("NotARelType"), null, null);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue(((String) error.get("message")).contains("NotARelType"));
        assertTrue(((String) error.get("suggestedCorrection")).contains("Valid relationship types"));
    }

    // ---- Validation: Invalid layer (Task 5.11) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameter_whenFilterLayerInvalid() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeWithFilters(
                "elem-1", 0, null, null, "NotALayer");

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue(((String) error.get("message")).contains("NotALayer"));
    }

    // ---- Validation: Non-list excludeTypes (Task 5.12) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameter_whenExcludeTypesNotArray() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");
        Map<String, Object> args = new HashMap<>();
        args.put("elementId", "elem-1");
        args.put("excludeTypes", "ServingRelationship"); // string instead of array
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "get-relationships", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue(((String) error.get("message")).contains("excludeTypes"));
    }

    // ---- Validation: Non-string entry in includeTypes (Task 5.13) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameter_whenIncludeTypesHasNonStringEntry() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");
        Map<String, Object> args = new HashMap<>();
        args.put("elementId", "elem-1");
        args.put("includeTypes", List.of("ServingRelationship", 42));
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "get-relationships", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue(((String) error.get("message")).contains("includeTypes"));
    }

    // ---- Empty filters treated as no filter (Task 5.14) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldTreatEmptyArrayAsNoFilter() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // elem-1 has 1 relationship. Empty excludeTypes=[] should be treated as no filter.
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");
        Map<String, Object> args = new HashMap<>();
        args.put("elementId", "elem-1");
        args.put("depth", 0);
        args.put("excludeTypes", List.of());
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "get-relationships", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertEquals("Empty filter should not affect results", 1, resultList.size());
    }

    // ---- Filters resulting in empty results (Task 5.15) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnEmptyResult_whenAllRelationshipsFilteredOut() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // elem-2 has ServingRelationship and FlowRelationship.
        // Include only "TriggeringRelationship" → nothing matches → empty result, not error.
        McpSchema.CallToolResult result = invokeWithFilters(
                "elem-2", 0, null, List.of("TriggeringRelationship"), null);

        assertFalse("Filtered empty result is NOT an error", result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertTrue(resultList.isEmpty());

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(0, meta.get("resultCount"));
    }

    // ---- No filter params preserves existing behavior (Task 5.16) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldPreserveExistingBehavior_whenNoFilterParams() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // Invoke without any filter params — should return same as Story 4.1 tests
        McpSchema.CallToolResult result = invokeGetRelationships("elem-2", 0);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertEquals("No filters = unfiltered results", 2, resultList.size());
    }

    // ---- Filters with element not found (Task 5.17) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnElementNotFound_evenWithFilters() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // Valid filters + bad elementId → filter validation passes, then ELEMENT_NOT_FOUND.
        McpSchema.CallToolResult result = invokeWithFilters(
                "nonexistent-id", 0, List.of("FlowRelationship"), null, "Business");

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("ELEMENT_NOT_FOUND", error.get("code"));
    }

    // ---- Filters with MODEL_NOT_LOADED (Task 5.18) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnModelNotLoaded_withFilters() throws Exception {
        StubAccessor accessor = new StubAccessor(false);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeWithFilters(
                "elem-1", 0, List.of("FlowRelationship"), null, "Business");

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("MODEL_NOT_LOADED", error.get("code"));
    }

    // ---- Combined includeTypes + excludeTypes (L1 review fix) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldApplyIncludeAndExcludeTogether_nonTraverse() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // elem-2 has rel-1 (ServingRelationship) and rel-2 (FlowRelationship).
        // includeTypes=["ServingRelationship", "FlowRelationship"] + excludeTypes=["FlowRelationship"]
        // → include keeps both, then exclude removes FlowRelationship → expect only rel-1.
        McpSchema.CallToolResult result = invokeWithFilters(
                "elem-2", 0,
                List.of("FlowRelationship"),
                List.of("ServingRelationship", "FlowRelationship"),
                null);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertEquals(1, resultList.size());
        assertEquals("rel-1", resultList.get(0).get("id"));
    }

    // ---- Validation: includeTypes non-list and filterLayer non-string (L2 review fix) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameter_whenIncludeTypesNotArray() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");
        Map<String, Object> args = new HashMap<>();
        args.put("elementId", "elem-1");
        args.put("includeTypes", "ServingRelationship"); // string instead of array
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "get-relationships", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue(((String) error.get("message")).contains("includeTypes"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameter_whenFilterLayerNotString() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");
        Map<String, Object> args = new HashMap<>();
        args.put("elementId", "elem-1");
        args.put("filterLayer", 42); // integer instead of string
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "get-relationships", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue(((String) error.get("message")).contains("filterLayer"));
    }

    // ---- Schema Tests for Filter Params (AC #6) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldHaveFilterParamsInSchema() {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.Tool tool = findToolSpec("get-relationships").tool();
        Map<String, Object> properties = tool.inputSchema().properties();

        // excludeTypes
        assertTrue(properties.containsKey("excludeTypes"));
        Map<String, Object> excludeTypesProp = (Map<String, Object>) properties.get("excludeTypes");
        assertEquals("array", excludeTypesProp.get("type"));
        assertNotNull(excludeTypesProp.get("items"));

        // includeTypes
        assertTrue(properties.containsKey("includeTypes"));
        Map<String, Object> includeTypesProp = (Map<String, Object>) properties.get("includeTypes");
        assertEquals("array", includeTypesProp.get("type"));
        assertNotNull(includeTypesProp.get("items"));

        // filterLayer
        assertTrue(properties.containsKey("filterLayer"));
        Map<String, Object> filterLayerProp = (Map<String, Object>) properties.get("filterLayer");
        assertEquals("string", filterLayerProp.get("type"));

        // None of the filter params should be required
        List<String> required = tool.inputSchema().required();
        assertFalse(required.contains("excludeTypes"));
        assertFalse(required.contains("includeTypes"));
        assertFalse(required.contains("filterLayer"));
    }

    // ---- Session Filter Integration Tests (Task 12.6-12.7) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldApplySessionLayerFilter_whenNoPerQueryFilterLayer() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        sm.setSessionFilter("default", null, "Business");
        CommandRegistry sessionRegistry = new CommandRegistry();
        TraversalHandler handler = new TraversalHandler(accessor, formatter, sessionRegistry, sm);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = sessionRegistry.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals("get-relationships")).findFirst().orElseThrow();
        Map<String, Object> args = new HashMap<>();
        args.put("elementId", "elem-1");
        args.put("depth", 1);
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get-relationships", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        // Session layer "Business" applied — only relationships with Business-layer connected elements
        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        // elem-1 has 1 rel: to elem-2 (Business layer) — passes "Business" filter
        assertEquals(1, meta.get("resultCount"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldOverrideSessionLayer_whenPerQueryFilterLayerProvided() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        sm.setSessionFilter("default", null, "Application");
        CommandRegistry sessionRegistry = new CommandRegistry();
        TraversalHandler handler = new TraversalHandler(accessor, formatter, sessionRegistry, sm);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = sessionRegistry.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals("get-relationships")).findFirst().orElseThrow();
        Map<String, Object> args = new HashMap<>();
        args.put("elementId", "elem-1");
        args.put("depth", 1);
        args.put("filterLayer", "Business");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get-relationships", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        // Per-query "Business" overrides session "Application"
        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        // Only rel to elem-3 (Business) passes
        assertEquals(1, meta.get("resultCount"));
    }

    // ---- Field Selection Integration Tests (Story 5.2, Task 11.7-11.8) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldApplyFieldSelection_whenDepth2WithMinimalFields() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");
        Map<String, Object> args = new HashMap<>();
        args.put("elementId", "elem-1");
        args.put("depth", 2);
        args.put("fields", "minimal");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get-relationships", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertEquals(1, resultList.size());

        // Depth 2: relationship with full element — but fields=minimal means element has only id+name
        Map<String, Object> rel = resultList.get(0);
        assertEquals("rel-1", rel.get("id"));
        Map<String, Object> source = (Map<String, Object>) rel.get("source");
        Map<String, Object> target = (Map<String, Object>) rel.get("target");
        assertNotNull(source);
        assertNotNull(target);
        assertEquals("elem-1", source.get("id"));
        assertEquals("App Component", source.get("name"));
        // MINIMAL: only id and name on expanded elements
        assertNull("type should be excluded in minimal", source.get("type"));
        assertNull("documentation should be excluded in minimal", source.get("documentation"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldExcludeDocumentation_whenExcludeWithDepthExpansion() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");
        Map<String, Object> args = new HashMap<>();
        args.put("elementId", "elem-1");
        args.put("depth", 2);
        args.put("exclude", List.of("documentation"));
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get-relationships", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertEquals(1, resultList.size());

        Map<String, Object> rel = resultList.get(0);
        Map<String, Object> source = (Map<String, Object>) rel.get("source");
        assertNotNull(source);
        assertEquals("elem-1", source.get("id"));
        assertEquals("ApplicationComponent", source.get("type")); // standard fields present
        assertNull("documentation should be excluded", source.get("documentation"));
    }

    // ---- Traverse Mode Field Selection Tests (H1 code review fix) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldApplyMinimalFieldSelection_inTraverseMode() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");
        Map<String, Object> args = new HashMap<>();
        args.put("elementId", "elem-1");
        args.put("traverse", true);
        args.put("maxDepth", 1);
        args.put("direction", "outgoing");
        args.put("fields", "minimal");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get-relationships", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> resultMap = (Map<String, Object>) envelope.get("result");

        // startElement should have only id and name (MINIMAL)
        Map<String, Object> startElement = (Map<String, Object>) resultMap.get("startElement");
        assertNotNull(startElement.get("id"));
        assertNotNull(startElement.get("name"));
        assertNull("MINIMAL startElement should not have type", startElement.get("type"));

        // connectedElement in hops should also be MINIMAL
        List<Map<String, Object>> hops = (List<Map<String, Object>>) resultMap.get("hops");
        assertFalse(hops.isEmpty());
        List<Map<String, Object>> rels = (List<Map<String, Object>>) hops.get(0).get("relationships");
        Map<String, Object> connected = (Map<String, Object>) rels.get(0).get("connectedElement");
        assertNotNull(connected.get("id"));
        assertNotNull(connected.get("name"));
        assertNull("MINIMAL connectedElement should not have type", connected.get("type"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldFallbackToStandard_whenInvalidPreset_inTraverseMode() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");
        Map<String, Object> args = new HashMap<>();
        args.put("elementId", "elem-1");
        args.put("traverse", true);
        args.put("maxDepth", 1);
        args.put("direction", "outgoing");
        args.put("fields", "bogus_preset");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get-relationships", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse("Invalid preset should not cause error", result.isError());
        Map<String, Object> envelope = parseJson(result);

        // _meta.warning should be present
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        String warning = (String) meta.get("warning");
        assertNotNull("_meta.warning should be present for invalid preset", warning);
        assertTrue(warning.contains("bogus_preset"));

        // startElement should have type (STANDARD fallback)
        Map<String, Object> resultMap = (Map<String, Object>) envelope.get("result");
        Map<String, Object> startElement = (Map<String, Object>) resultMap.get("startElement");
        assertNotNull("STANDARD fallback should include type", startElement.get("type"));
    }

    // ---- Story 5.3: Model Version Change Detection Tests ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldNotIncludeModelChanged_whenVersionStable() throws Exception {
        VersionBumpAccessor accessor = new VersionBumpAccessor();
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, sm);
        handler.registerTools();

        // First call — stores version, no change
        McpSchema.CallToolResult result = invokeGetRelationships("elem-1", 1);
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertFalse("modelChanged should not be present on first call",
                meta.containsKey("modelChanged"));

        // Second call — same version, no change
        McpSchema.CallToolResult result2 = invokeGetRelationships("elem-1", 1);
        Map<String, Object> envelope2 = parseJson(result2);
        Map<String, Object> meta2 = (Map<String, Object>) envelope2.get("_meta");
        assertFalse("modelChanged should not be present when version unchanged",
                meta2.containsKey("modelChanged"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldIncludeModelChanged_whenVersionChanges_depthMode() throws Exception {
        VersionBumpAccessor accessor = new VersionBumpAccessor();
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, sm);
        handler.registerTools();

        // First call — stores version
        invokeGetRelationships("elem-1", 1);

        // Bump version
        accessor.setVersion("43");

        // Second call — detects change
        McpSchema.CallToolResult result = invokeGetRelationships("elem-1", 1);
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(true, meta.get("modelChanged"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldIncludeModelChanged_whenVersionChanges_traverseMode() throws Exception {
        VersionBumpAccessor accessor = new VersionBumpAccessor();
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, sm);
        handler.registerTools();

        // First call — stores version
        invokeTraversal("elem-1", 2, "both");

        // Bump version
        accessor.setVersion("43");

        // Second call — detects change
        McpSchema.CallToolResult result = invokeTraversal("elem-1", 2, "both");
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(true, meta.get("modelChanged"));
    }

    // ---- Story 5.4: Session Caching Tests ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldCacheDepthModeResult_andReturnCacheHitOnSecondCall() throws Exception {
        CountingAccessor accessor = new CountingAccessor();
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, sm);
        handler.registerTools();

        // First call — cache miss, queries accessor
        McpSchema.CallToolResult result1 = invokeGetRelationships("elem-1", 1);
        assertFalse(result1.isError());
        Map<String, Object> envelope1 = parseJson(result1);
        Map<String, Object> meta1 = (Map<String, Object>) envelope1.get("_meta");
        assertFalse("First call should not have cacheHit", meta1.containsKey("cacheHit"));
        int callsAfterFirst = accessor.getRelationshipsCount;

        // Second call — should be cache hit
        McpSchema.CallToolResult result2 = invokeGetRelationships("elem-1", 1);
        assertFalse(result2.isError());
        Map<String, Object> envelope2 = parseJson(result2);
        Map<String, Object> meta2 = (Map<String, Object>) envelope2.get("_meta");
        assertEquals("Second call should have cacheHit", true, meta2.get("cacheHit"));

        // Accessor should not have been called again
        assertEquals("Accessor should not be called on cache hit",
                callsAfterFirst, accessor.getRelationshipsCount);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldCacheTraverseModeResult_andReturnCacheHitOnSecondCall() throws Exception {
        CountingAccessor accessor = new CountingAccessor();
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, sm);
        handler.registerTools();

        // First call — cache miss, queries accessor
        McpSchema.CallToolResult result1 = invokeTraversal("elem-1", 2, "both");
        assertFalse(result1.isError());
        Map<String, Object> envelope1 = parseJson(result1);
        Map<String, Object> meta1 = (Map<String, Object>) envelope1.get("_meta");
        assertFalse("First call should not have cacheHit", meta1.containsKey("cacheHit"));
        int callsAfterFirst = accessor.getRelationshipsCount;

        // Second call — should be cache hit
        McpSchema.CallToolResult result2 = invokeTraversal("elem-1", 2, "both");
        assertFalse(result2.isError());
        Map<String, Object> envelope2 = parseJson(result2);
        Map<String, Object> meta2 = (Map<String, Object>) envelope2.get("_meta");
        assertEquals("Second call should have cacheHit", true, meta2.get("cacheHit"));

        // Accessor should not have been called again
        assertEquals("Accessor should not be called on cache hit",
                callsAfterFirst, accessor.getRelationshipsCount);
    }

    // ---- Progress Indication Tests (Story 5.5 AC8) ----

    @Test
    public void shouldExtractProgressToken_whenMetaContainsToken() {
        Map<String, Object> meta = Map.of("progressToken", "tok-123");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "get-relationships", Map.of("elementId", "elem-1"), meta);
        Object token = TraversalHandler.extractProgressToken(request);
        assertEquals("tok-123", token);
    }

    @Test
    public void shouldReturnNullProgressToken_whenMetaIsNull() {
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "get-relationships", Map.of("elementId", "elem-1"));
        Object token = TraversalHandler.extractProgressToken(request);
        assertNull(token);
    }

    @Test
    public void shouldReturnNullProgressToken_whenMetaHasNoToken() {
        Map<String, Object> meta = Map.of("otherKey", "value");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "get-relationships", Map.of("elementId", "elem-1"), meta);
        Object token = TraversalHandler.extractProgressToken(request);
        assertNull(token);
    }

    @Test
    public void shouldCompleteTraversalWithoutError_whenExchangeIsNull() throws Exception {
        // Tests that progress indication is safe when exchange=null (existing behavior)
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // invokeTraversal passes null for exchange — should not throw
        McpSchema.CallToolResult result = invokeTraversal("elem-1", 2, "both");
        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        assertNotNull(envelope.get("result"));
    }

    // ---- Story 6.2: Dry-Run Cost Estimation Tests ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnDryRunEstimate_depthMode() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");
        Map<String, Object> args = new HashMap<>();
        args.put("elementId", "elem-2"); // has 2 relationships (rel-1, rel-2)
        args.put("depth", 1);
        args.put("dryRun", true);
        McpSchema.CallToolResult result = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("get-relationships", args));

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        // dryRun object at top level
        Map<String, Object> dryRun = (Map<String, Object>) envelope.get("dryRun");
        assertNotNull("dryRun key must be present", dryRun);
        assertEquals(2, dryRun.get("estimatedResultCount"));
        assertTrue((int) dryRun.get("estimatedTokens") > 0);
        assertNotNull(dryRun.get("recommendedPreset"));
        assertNotNull(dryRun.get("recommendation"));

        // No result key
        assertFalse("dryRun response must NOT contain 'result' key", envelope.containsKey("result"));

        // _meta with dryRun flag
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(true, meta.get("dryRun"));
        assertEquals("42", meta.get("modelVersion"));
        assertFalse("No resultCount in dryRun _meta", meta.containsKey("resultCount"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnDryRunEstimate_traverseMode() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");
        Map<String, Object> args = new HashMap<>();
        args.put("elementId", "elem-1");
        args.put("traverse", true);
        args.put("maxDepth", 2);
        args.put("direction", "outgoing");
        args.put("dryRun", true);
        McpSchema.CallToolResult result = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("get-relationships", args));

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        // dryRun object at top level
        Map<String, Object> dryRun = (Map<String, Object>) envelope.get("dryRun");
        assertNotNull("dryRun key must be present", dryRun);
        // Traversal from elem-1 outgoing: elem-1->elem-2 (hop 1), elem-2->elem-3 (hop 2)
        // totalElements=2, totalRelationships=2, totalItems=4
        int estimatedCount = (int) dryRun.get("estimatedResultCount");
        assertTrue("Should have items discovered", estimatedCount > 0);
        assertTrue((int) dryRun.get("estimatedTokens") > 0);
        assertNotNull(dryRun.get("recommendedPreset"));

        // No result key
        assertFalse("dryRun response must NOT contain 'result' key", envelope.containsKey("result"));

        // _meta with dryRun flag
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(true, meta.get("dryRun"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldRespectFilters_whenDryRunDepthMode() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");

        // elem-2 has rel-1 (ServingRelationship) and rel-2 (FlowRelationship)
        // Include only ServingRelationship — should filter to 1 relationship
        Map<String, Object> args = new HashMap<>();
        args.put("elementId", "elem-2");
        args.put("depth", 1);
        args.put("dryRun", true);
        args.put("includeTypes", List.of("ServingRelationship"));
        McpSchema.CallToolResult result = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("get-relationships", args));

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> dryRun = (Map<String, Object>) envelope.get("dryRun");
        assertEquals("Should count 1 after includeTypes filter", 1, dryRun.get("estimatedResultCount"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldValidateElementId_whenDryRun() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");
        Map<String, Object> args = new HashMap<>();
        args.put("elementId", "nonexistent-id");
        args.put("dryRun", true);
        McpSchema.CallToolResult result = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("get-relationships", args));

        assertTrue("dryRun should still validate element existence", result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("ELEMENT_NOT_FOUND", error.get("code"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldNotCache_whenDryRunDepthMode() throws Exception {
        CountingAccessor accessor = new CountingAccessor();
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, sm);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");

        // dryRun call
        Map<String, Object> args1 = new HashMap<>();
        args1.put("elementId", "elem-1");
        args1.put("depth", 1);
        args1.put("dryRun", true);
        spec.callHandler().apply(null, new McpSchema.CallToolRequest("get-relationships", args1));
        int firstCallCount = accessor.getRelationshipsCount;

        // Regular call with same params — should NOT be a cache hit (dryRun didn't populate cache)
        Map<String, Object> args2 = new HashMap<>();
        args2.put("elementId", "elem-1");
        args2.put("depth", 1);
        McpSchema.CallToolResult result2 = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("get-relationships", args2));

        assertTrue("Regular call after dryRun should execute fresh query",
                accessor.getRelationshipsCount > firstCallCount);
        Map<String, Object> env2 = parseJson(result2);
        Map<String, Object> meta2 = (Map<String, Object>) env2.get("_meta");
        assertFalse("Should not be cache hit after dryRun", meta2.containsKey("cacheHit"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldIncludeNextSteps_inDryRunDepthResponse() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");
        Map<String, Object> args = new HashMap<>();
        args.put("elementId", "elem-1");
        args.put("depth", 1);
        args.put("dryRun", true);
        McpSchema.CallToolResult result = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("get-relationships", args));

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<String> nextSteps = (List<String>) envelope.get("nextSteps");
        assertNotNull("dryRun should have nextSteps", nextSteps);
        assertFalse("nextSteps should not be empty", nextSteps.isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldHaveDryRunParameterInSchema() {
        StubAccessor accessor = new StubAccessor(true);
        TraversalHandler handler = new TraversalHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.Tool tool = findToolSpec("get-relationships").tool();
        Map<String, Object> properties = tool.inputSchema().properties();
        assertTrue("Should have dryRun property", properties.containsKey("dryRun"));
        Map<String, Object> dryRunProp = (Map<String, Object>) properties.get("dryRun");
        assertEquals("boolean", dryRunProp.get("type"));
    }

    // ---- Helper Methods ----

    private McpSchema.CallToolResult invokeGetRelationships(String elementId, int depth) {
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");
        Map<String, Object> args = new HashMap<>();
        args.put("elementId", elementId);
        args.put("depth", depth);
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "get-relationships", args);
        return spec.callHandler().apply(null, request);
    }

    private McpSchema.CallToolResult invokeWithFilters(String elementId, int depth,
                                                       List<String> excludeTypes,
                                                       List<String> includeTypes,
                                                       String filterLayer) {
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");
        Map<String, Object> args = new HashMap<>();
        args.put("elementId", elementId);
        args.put("depth", depth);
        if (excludeTypes != null) args.put("excludeTypes", excludeTypes);
        if (includeTypes != null) args.put("includeTypes", includeTypes);
        if (filterLayer != null) args.put("filterLayer", filterLayer);
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "get-relationships", args);
        return spec.callHandler().apply(null, request);
    }

    private McpSchema.CallToolResult invokeTraversalWithFilters(String elementId, int maxDepth,
                                                                 String direction,
                                                                 List<String> excludeTypes,
                                                                 List<String> includeTypes,
                                                                 String filterLayer) {
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");
        Map<String, Object> args = new HashMap<>();
        args.put("elementId", elementId);
        args.put("traverse", true);
        args.put("maxDepth", maxDepth);
        args.put("direction", direction);
        if (excludeTypes != null) args.put("excludeTypes", excludeTypes);
        if (includeTypes != null) args.put("includeTypes", includeTypes);
        if (filterLayer != null) args.put("filterLayer", filterLayer);
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "get-relationships", args);
        return spec.callHandler().apply(null, request);
    }

    private McpSchema.CallToolResult invokeTraversal(String elementId, int maxDepth, String direction) {
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-relationships");
        Map<String, Object> args = new HashMap<>();
        args.put("elementId", elementId);
        args.put("traverse", true);
        args.put("maxDepth", maxDepth);
        args.put("direction", direction);
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "get-relationships", args);
        return spec.callHandler().apply(null, request);
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

    // ---- Stub Implementations ----

    /**
     * Stub accessor with configurable test data for TraversalHandler.
     *
     * <p>Contains 4 elements: elem-1 (ApplicationComponent), elem-2 (BusinessProcess),
     * elem-3 (TechnologyService), elem-4 (Node, isolated — no relationships).
     * Contains 2 relationships: rel-1 (ServingRelationship elem-1 -> elem-2),
     * rel-2 (FlowRelationship elem-2 -> elem-3).</p>
     */
    private static class StubAccessor extends BaseTestAccessor {

        StubAccessor(boolean modelLoaded) {
            super(modelLoaded);
        }

        @Override
        public ModelInfoDto getModelInfo() {
            if (!isModelLoaded()) throw new NoModelLoadedException();
            return new ModelInfoDto("Test Model", 4, 2, 0, Map.of(), Map.of(), Map.of());
        }

        @Override
        public Optional<ElementDto> getElementById(String id) {
            if (!isModelLoaded()) throw new NoModelLoadedException();
            if ("elem-1".equals(id)) {
                return Optional.of(ElementDto.standard(
                        "elem-1", "App Component", "ApplicationComponent",
                        "Application", "A test application component",
                        List.of(Map.of("key", "version", "value", "1.0"))));
            }
            if ("elem-2".equals(id)) {
                return Optional.of(ElementDto.standard(
                        "elem-2", "Business Process", "BusinessProcess",
                        "Business", "A test business process", List.of()));
            }
            if ("elem-3".equals(id)) {
                return Optional.of(ElementDto.standard(
                        "elem-3", "Tech Service", "TechnologyService",
                        "Technology", "A test technology service", List.of()));
            }
            if ("elem-4".equals(id)) {
                return Optional.of(ElementDto.standard(
                        "elem-4", "Isolated Node", "Node",
                        "Technology", "An isolated node", List.of()));
            }
            return Optional.empty();
        }

        @Override
        public List<ElementDto> getElementsByIds(List<String> ids) {
            if (!isModelLoaded()) throw new NoModelLoadedException();
            return ids.stream()
                    .distinct()
                    .map(this::getElementById)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .toList();
        }

        @Override
        public List<RelationshipDto> getRelationshipsForElement(String elementId) {
            if (!isModelLoaded()) throw new NoModelLoadedException();
            RelationshipDto rel1 = new RelationshipDto("rel-1", "Serves",
                    "ServingRelationship", "elem-1", "elem-2");
            RelationshipDto rel2 = new RelationshipDto("rel-2", "Flows",
                    "FlowRelationship", "elem-2", "elem-3");
            if ("elem-1".equals(elementId)) {
                return List.of(rel1);
            }
            if ("elem-2".equals(elementId)) {
                return List.of(rel1, rel2);
            }
            if ("elem-3".equals(elementId)) {
                return List.of(rel2);
            }
            return List.of();
        }

        @Override
        public List<ViewDto> getViews(String viewpointFilter) {
            return List.of();
        }

        @Override
        public Optional<ViewContentsDto> getViewContents(String viewId) {
            return Optional.empty();
        }
    }

    /**
     * StubAccessor with cycle: elem-3 -> elem-1 via rel-3.
     * Chain: elem-1 -> elem-2 -> elem-3 -> elem-1 (cycle).
     */
    private static class CycleStubAccessor extends StubAccessor {
        CycleStubAccessor() {
            super(true);
        }

        @Override
        public List<RelationshipDto> getRelationshipsForElement(String elementId) {
            RelationshipDto rel1 = new RelationshipDto("rel-1", "Serves",
                    "ServingRelationship", "elem-1", "elem-2");
            RelationshipDto rel2 = new RelationshipDto("rel-2", "Flows",
                    "FlowRelationship", "elem-2", "elem-3");
            RelationshipDto rel3 = new RelationshipDto("rel-3", "Triggers",
                    "TriggeringRelationship", "elem-3", "elem-1");
            if ("elem-1".equals(elementId)) {
                return List.of(rel1, rel3);
            }
            if ("elem-2".equals(elementId)) {
                return List.of(rel1, rel2);
            }
            if ("elem-3".equals(elementId)) {
                return List.of(rel2, rel3);
            }
            return List.of();
        }
    }

    /**
     * Stub accessor with mutable model version for testing version change detection.
     */
    private static class VersionBumpAccessor extends StubAccessor {
        private String version = "42";

        VersionBumpAccessor() {
            super(true);
        }

        @Override
        public String getModelVersion() {
            return version;
        }

        void setVersion(String version) {
            this.version = version;
        }
    }

    /**
     * Accessor that throws RuntimeException to test unexpected error handling.
     */
    private static class ExplodingAccessor extends StubAccessor {
        ExplodingAccessor() {
            super(true);
        }

        @Override
        public Optional<ElementDto> getElementById(String id) {
            throw new RuntimeException("Simulated EMF explosion");
        }

        @Override
        public List<ElementDto> getElementsByIds(List<String> ids) {
            throw new RuntimeException("Simulated EMF explosion");
        }

        @Override
        public List<RelationshipDto> getRelationshipsForElement(String elementId) {
            throw new RuntimeException("Simulated EMF explosion");
        }
    }

    /**
     * StubAccessor with invocation counters for verifying cache hit behavior (Story 5.4).
     */
    private static class CountingAccessor extends StubAccessor {
        int getRelationshipsCount = 0;
        int getByIdCount = 0;

        CountingAccessor() {
            super(true);
        }

        @Override
        public Optional<ElementDto> getElementById(String id) {
            getByIdCount++;
            return super.getElementById(id);
        }

        @Override
        public List<RelationshipDto> getRelationshipsForElement(String elementId) {
            getRelationshipsCount++;
            return super.getRelationshipsForElement(elementId);
        }
    }
}
