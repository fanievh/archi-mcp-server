package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.*;

import java.util.ArrayList;
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
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Unit tests for {@link ModelQueryHandler}.
 *
 * <p>Uses a stub accessor extending BaseTestAccessor — no EMF/OSGi runtime required.</p>
 */
public class ModelQueryHandlerTest {

    private CommandRegistry registry;
    private ResponseFormatter formatter;
    private ObjectMapper objectMapper;

    @Before
    public void setUp() {
        registry = new CommandRegistry();
        formatter = new ResponseFormatter();
        objectMapper = new ObjectMapper();
    }

    // ---- Tool Registration Tests (AC #3) ----

    @Test
    public void shouldRegisterGetModelInfoTool() {
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        assertEquals(3, registry.getToolCount());
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-model-info");
        assertEquals("get-model-info", spec.tool().name());
    }

    @Test
    public void shouldHaveDescriptionInToolSchema() {
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.Tool tool = findToolSpec("get-model-info").tool();
        assertNotNull(tool.description());
        assertTrue(tool.description().contains("model"));
    }

    @Test
    public void shouldHaveEmptyInputSchema() {
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.Tool tool = findToolSpec("get-model-info").tool();
        assertNotNull(tool.inputSchema());
        assertEquals("object", tool.inputSchema().type());
        assertTrue(tool.inputSchema().properties().isEmpty());
    }

    // ---- Success Path Tests (AC #1) ----

    @Test
    public void shouldReturnModelInfo_whenModelLoaded() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeHandler();

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        // Verify result contains model info
        assertNotNull(envelope.get("result"));
        @SuppressWarnings("unchecked")
        Map<String, Object> modelResult = (Map<String, Object>) envelope.get("result");
        assertEquals("Test Model", modelResult.get("name"));
        assertEquals(10, modelResult.get("elementCount"));
        assertEquals(5, modelResult.get("relationshipCount"));
        assertEquals(3, modelResult.get("viewCount"));

        // AC #1: element type distribution
        @SuppressWarnings("unchecked")
        Map<String, Object> typeDist =
                (Map<String, Object>) modelResult.get("elementTypeDistribution");
        assertNotNull(typeDist);
        assertEquals(4, typeDist.get("ApplicationComponent"));
        assertEquals(6, typeDist.get("BusinessProcess"));

        // Story 5.5 AC #3: relationship type distribution
        @SuppressWarnings("unchecked")
        Map<String, Object> relTypeDist =
                (Map<String, Object>) modelResult.get("relationshipTypeDistribution");
        assertNotNull("relationshipTypeDistribution should be present", relTypeDist);
        assertEquals(3, relTypeDist.get("ServingRelationship"));
        assertEquals(2, relTypeDist.get("FlowRelationship"));

        // Story 5.5 AC #3: layer distribution
        @SuppressWarnings("unchecked")
        Map<String, Object> layerDist =
                (Map<String, Object>) modelResult.get("layerDistribution");
        assertNotNull("layerDistribution should be present", layerDist);
        assertEquals(4, layerDist.get("Application"));
        assertEquals(6, layerDist.get("Business"));
    }

    @Test
    public void shouldReturnSmallModelNextSteps_whenElementCountBelowThreshold() throws Exception {
        // Default StubAccessor has 10 elements (< SMALL_MODEL_THRESHOLD=100)
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeHandler();

        Map<String, Object> envelope = parseJson(result);
        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) envelope.get("nextSteps");
        assertNotNull(nextSteps);
        assertEquals(3, nextSteps.size());
        assertTrue("Small model should suggest get-views first",
                nextSteps.get(0).contains("get-views"));
        assertTrue("Small model should suggest search-elements second",
                nextSteps.get(1).contains("search-elements"));
    }

    @Test
    public void shouldReturnMediumModelNextSteps_whenElementCountInMediumRange() throws Exception {
        MediumModelAccessor accessor = new MediumModelAccessor();
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeHandler();

        Map<String, Object> envelope = parseJson(result);
        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) envelope.get("nextSteps");
        assertNotNull(nextSteps);
        assertEquals(4, nextSteps.size());
        assertTrue("Medium model should suggest search-elements with filters",
                nextSteps.get(0).contains("search-elements"));
        assertTrue("Medium model should suggest get-views with viewpoint",
                nextSteps.get(1).contains("get-views"));
        assertTrue("Medium model should suggest set-session-filter",
                nextSteps.get(2).contains("set-session-filter"));
    }

    @Test
    public void shouldReturnLargeModelNextSteps_whenElementCountAboveThreshold() throws Exception {
        LargeModelAccessor accessor = new LargeModelAccessor();
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeHandler();

        Map<String, Object> envelope = parseJson(result);
        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) envelope.get("nextSteps");
        assertNotNull(nextSteps);
        assertEquals(5, nextSteps.size());
        assertTrue("Large model should suggest search-elements first",
                nextSteps.get(0).contains("search-elements"));
        assertTrue("Large model should suggest set-session-filter",
                nextSteps.get(1).contains("set-session-filter"));
        assertTrue("Large model should warn against unfiltered get-views",
                nextSteps.get(3).contains("Avoid"));
    }

    // ---- Boundary Value Tests for Dynamic nextSteps (Review Fix M1) ----

    @Test
    public void shouldReturnSmallModelNextSteps_whenElementCountIs99() throws Exception {
        SizedModelAccessor accessor = new SizedModelAccessor(99);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeHandler();
        Map<String, Object> envelope = parseJson(result);
        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) envelope.get("nextSteps");
        assertEquals("99 elements should be small model (3 nextSteps)", 3, nextSteps.size());
        assertTrue(nextSteps.get(0).contains("get-views"));
    }

    @Test
    public void shouldReturnMediumModelNextSteps_whenElementCountIs100() throws Exception {
        SizedModelAccessor accessor = new SizedModelAccessor(100);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeHandler();
        Map<String, Object> envelope = parseJson(result);
        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) envelope.get("nextSteps");
        assertEquals("100 elements should be medium model (4 nextSteps)", 4, nextSteps.size());
        assertTrue(nextSteps.get(0).contains("search-elements"));
    }

    @Test
    public void shouldReturnMediumModelNextSteps_whenElementCountIs500() throws Exception {
        SizedModelAccessor accessor = new SizedModelAccessor(500);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeHandler();
        Map<String, Object> envelope = parseJson(result);
        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) envelope.get("nextSteps");
        assertEquals("500 elements should be medium model (4 nextSteps)", 4, nextSteps.size());
        assertTrue(nextSteps.get(0).contains("search-elements"));
    }

    @Test
    public void shouldReturnLargeModelNextSteps_whenElementCountIs501() throws Exception {
        SizedModelAccessor accessor = new SizedModelAccessor(501);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeHandler();
        Map<String, Object> envelope = parseJson(result);
        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) envelope.get("nextSteps");
        assertEquals("501 elements should be large model (5 nextSteps)", 5, nextSteps.size());
        assertTrue(nextSteps.get(0).contains("search-elements"));
        assertTrue(nextSteps.get(3).contains("Avoid"));
    }

    @Test
    public void shouldIncludeModelVersion_inMeta() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeHandler();

        Map<String, Object> envelope = parseJson(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertNotNull(meta);
        assertEquals("42", meta.get("modelVersion"));
        assertEquals(1, meta.get("resultCount"));
        assertEquals(1, meta.get("totalCount"));
        assertEquals(false, meta.get("isTruncated"));
    }

    @Test
    public void shouldNotReturnError_whenModelLoaded() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeHandler();

        assertFalse(result.isError());
    }

    // ---- Error Path Tests (AC #2) ----

    @Test
    public void shouldReturnModelNotLoadedError_whenNoModelLoaded() throws Exception {
        StubAccessor accessor = new StubAccessor(false);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeHandler();

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("MODEL_NOT_LOADED", error.get("code"));
        assertEquals("Open an ArchiMate model in ArchimateTool", error.get("suggestedCorrection"));
    }

    @Test
    public void shouldReturnInternalError_whenUnexpectedException() throws Exception {
        ExplodingAccessor accessor = new ExplodingAccessor();
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeHandler();

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("INTERNAL_ERROR", error.get("code"));
    }

    // ---- Constructor Validation ----

    @Test(expected = NullPointerException.class)
    public void shouldRejectNullAccessor() {
        new ModelQueryHandler(null, formatter, registry, null);
    }

    @Test(expected = NullPointerException.class)
    public void shouldRejectNullFormatter() {
        new ModelQueryHandler(new StubAccessor(true), null, registry, null);
    }

    @Test(expected = NullPointerException.class)
    public void shouldRejectNullRegistry() {
        new ModelQueryHandler(new StubAccessor(true), formatter, null, null);
    }

    // ---- get-element Registration Tests (AC #3) ----

    @Test
    public void shouldRegisterGetElementTool() {
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-element");
        assertEquals("get-element", spec.tool().name());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldHaveIdParameterInGetElementSchema() {
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.Tool tool = findToolSpec("get-element").tool();
        assertNotNull(tool.inputSchema());
        assertEquals("object", tool.inputSchema().type());

        Map<String, Object> properties = tool.inputSchema().properties();
        assertNotNull(properties);
        assertTrue(properties.containsKey("id"));

        Map<String, Object> idProp = (Map<String, Object>) properties.get("id");
        assertEquals("string", idProp.get("type"));
        assertNotNull(idProp.get("description"));

        // id is now optional (neither id nor ids is required in schema)
        List<String> required = tool.inputSchema().required();
        assertTrue("required should be null or empty since neither id nor ids is required",
                required == null || required.isEmpty());
    }

    // ---- get-element Success Path Tests (AC #1) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnElement_whenElementFound() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetElement("test-element-id");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        // Verify result contains element DTO fields
        assertNotNull(envelope.get("result"));
        Map<String, Object> elementResult = (Map<String, Object>) envelope.get("result");
        assertEquals("test-element-id", elementResult.get("id"));
        assertEquals("Test Component", elementResult.get("name"));
        assertEquals("ApplicationComponent", elementResult.get("type"));
        assertEquals("Application", elementResult.get("layer"));
        assertEquals("A test component for unit testing", elementResult.get("documentation"));

        // Verify properties list contents (AC #1: properties key-value pairs)
        List<Map<String, Object>> props = (List<Map<String, Object>>) elementResult.get("properties");
        assertNotNull(props);
        assertEquals(1, props.size());
        assertEquals("version", props.get(0).get("key"));
        assertEquals("1.0", props.get(0).get("value"));

        // Verify nextSteps
        List<String> nextSteps = (List<String>) envelope.get("nextSteps");
        assertNotNull(nextSteps);
        assertEquals(2, nextSteps.size());
        assertTrue(nextSteps.get(0).contains("get-relationships"));
        assertTrue(nextSteps.get(1).contains("get-view-contents"));

        // Verify _meta
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertNotNull(meta);
        assertEquals("42", meta.get("modelVersion"));
        assertEquals(1, meta.get("resultCount"));
        assertEquals(1, meta.get("totalCount"));
        assertEquals(false, meta.get("isTruncated"));
    }

    // ---- get-element Error Path Tests (AC #2) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnElementNotFoundError_whenIdNotFound() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetElement("nonexistent-id");

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("ELEMENT_NOT_FOUND", error.get("code"));
        assertNotNull(error.get("message"));
        assertTrue(((String) error.get("message")).contains("nonexistent-id"));

        // Verify details include element count
        assertNotNull(error.get("details"));
        assertTrue(((String) error.get("details")).contains("10"));

        // Verify suggestedCorrection
        assertEquals("Use search-elements to find elements by name or type",
                error.get("suggestedCorrection"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnModelNotLoadedError_whenNoModel_getElement() throws Exception {
        StubAccessor accessor = new StubAccessor(false);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetElement("any-id");

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("MODEL_NOT_LOADED", error.get("code"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInternalError_whenUnexpectedException_getElement() throws Exception {
        ExplodingAccessor accessor = new ExplodingAccessor();
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetElement("test-element-id");

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("INTERNAL_ERROR", error.get("code"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameterError_whenIdMissing() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetElement(null);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertNotNull(error.get("suggestedCorrection"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameterError_whenIdBlank() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-element");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "get-element", Map.of("id", "   "));
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameterError_whenIdNotString() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-element");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "get-element", Map.of("id", 123));
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
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-element");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "get-element", null);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    // ---- get-element Batch Schema Tests (AC #4) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldHaveIdsParameterInGetElementSchema() {
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.Tool tool = findToolSpec("get-element").tool();
        Map<String, Object> properties = tool.inputSchema().properties();
        assertNotNull(properties);

        // ids property exists with array type
        assertTrue(properties.containsKey("ids"));
        Map<String, Object> idsProp = (Map<String, Object>) properties.get("ids");
        assertEquals("array", idsProp.get("type"));
        assertNotNull(idsProp.get("description"));

        // items type is string
        Map<String, Object> items = (Map<String, Object>) idsProp.get("items");
        assertNotNull(items);
        assertEquals("string", items.get("type"));
    }

    @Test
    public void shouldNotRequireIdOrIdsInSchema() {
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.Tool tool = findToolSpec("get-element").tool();
        // Neither id nor ids should be required — handler validates
        List<String> required = tool.inputSchema().required();
        assertTrue("required should be null or empty",
                required == null || required.isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldStillHaveIdParameterInGetElementSchema() {
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.Tool tool = findToolSpec("get-element").tool();
        Map<String, Object> properties = tool.inputSchema().properties();
        assertTrue(properties.containsKey("id"));
        Map<String, Object> idProp = (Map<String, Object>) properties.get("id");
        assertEquals("string", idProp.get("type"));
    }

    // ---- get-element Batch Success Tests (AC #1) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnBatchResults_whenSingleIdInArray() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeBatchGetElement(
                List.of("test-element-id"));

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        // Batch returns array
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertEquals(1, resultList.size());
        assertEquals("test-element-id", resultList.get(0).get("id"));
        assertEquals("Test Component", resultList.get(0).get("name"));

        // Verify _meta
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(1, meta.get("resultCount"));
        assertEquals(1, meta.get("totalCount"));
        assertEquals(false, meta.get("isTruncated"));
        assertFalse("notFound should not be present when all found",
                meta.containsKey("notFound"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnBatchResults_whenMultipleIdsFound() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeBatchGetElement(
                List.of("test-element-id", "second-element-id"));

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertEquals(2, resultList.size());

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(2, meta.get("resultCount"));
        assertEquals(2, meta.get("totalCount"));
        assertFalse(meta.containsKey("notFound"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnNextSteps_inBatchResponse() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeBatchGetElement(
                List.of("test-element-id"));

        Map<String, Object> envelope = parseJson(result);
        List<String> nextSteps = (List<String>) envelope.get("nextSteps");
        assertNotNull(nextSteps);
        assertEquals(2, nextSteps.size());
        assertTrue(nextSteps.get(0).contains("get-relationships"));
        assertTrue(nextSteps.get(1).contains("search-elements"));
    }

    // ---- get-element Batch Partial Match Tests (AC #2) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnPartialResults_withNotFoundIds() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeBatchGetElement(
                List.of("test-element-id", "nonexistent-id"));

        assertFalse("Partial match is NOT an error", result.isError());
        Map<String, Object> envelope = parseJson(result);

        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertEquals(1, resultList.size());
        assertEquals("test-element-id", resultList.get(0).get("id"));

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(1, meta.get("resultCount"));
        assertEquals(1, meta.get("totalCount"));

        // notFound should list the missing IDs
        List<String> notFound = (List<String>) meta.get("notFound");
        assertNotNull("notFound should be present", notFound);
        assertEquals(1, notFound.size());
        assertTrue(notFound.contains("nonexistent-id"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnAllIdsInNotFound_whenNoneMatch() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeBatchGetElement(
                List.of("bad-1", "bad-2"));

        assertFalse("All not-found is NOT an error", result.isError());
        Map<String, Object> envelope = parseJson(result);

        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertTrue(resultList.isEmpty());

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(0, meta.get("resultCount"));
        assertEquals(0, meta.get("totalCount"));

        List<String> notFound = (List<String>) meta.get("notFound");
        assertNotNull(notFound);
        assertEquals(2, notFound.size());
        assertTrue(notFound.contains("bad-1"));
        assertTrue(notFound.contains("bad-2"));
    }

    // ---- get-element Backward Compatibility Tests (AC #3) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnSingleObject_whenUsingIdParameter() throws Exception {
        // This is the key backward compat test: single id returns object, not array
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeGetElement("test-element-id");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        // Single id returns an object, NOT an array
        Object resultObj = envelope.get("result");
        assertTrue("Single id should return an object (Map), not a List",
                resultObj instanceof Map);
        Map<String, Object> elementResult = (Map<String, Object>) resultObj;
        assertEquals("test-element-id", elementResult.get("id"));
    }

    // ---- get-element Batch Validation Tests (AC #1, #4) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameterError_whenNeitherIdNorIdsProvided() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-element");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "get-element", Collections.emptyMap());
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameterError_whenBothIdAndIdsProvided() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-element");
        Map<String, Object> args = new HashMap<>();
        args.put("id", "test-element-id");
        args.put("ids", List.of("test-element-id"));
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get-element", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue(((String) error.get("message")).contains("not both"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameterError_whenIdsIsEmptyArray() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeBatchGetElement(List.of());

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue(((String) error.get("message")).contains("at least one"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameterError_whenIdsContainsNonString() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-element");
        List<Object> mixedIds = new ArrayList<>();
        mixedIds.add("valid-id");
        mixedIds.add(123);
        Map<String, Object> args = Map.of("ids", mixedIds);
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get-element", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue(((String) error.get("message")).contains("non-empty strings"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameterError_whenIdsIsNotArray() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-element");
        Map<String, Object> args = Map.of("ids", "not-an-array");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get-element", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue(((String) error.get("message")).contains("array of strings"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameterError_whenIdsContainsBlankString() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-element");
        Map<String, Object> args = Map.of("ids", List.of("valid-id", "   "));
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get-element", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameterError_whenIdsBatchTooLarge() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // Create a list of 51 IDs (exceeds MAX_BATCH_SIZE of 50)
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            ids.add("id-" + i);
        }

        McpSchema.CallToolResult result = invokeBatchGetElement(ids);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue(((String) error.get("message")).contains("at most"));
    }

    // ---- get-element Batch Error Path Tests ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnModelNotLoadedError_whenNoModel_batchGetElement() throws Exception {
        StubAccessor accessor = new StubAccessor(false);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeBatchGetElement(
                List.of("any-id"));

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("MODEL_NOT_LOADED", error.get("code"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInternalError_whenUnexpectedException_batchGetElement() throws Exception {
        ExplodingAccessor accessor = new ExplodingAccessor();
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeBatchGetElement(
                List.of("any-id"));

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INTERNAL_ERROR", error.get("code"));
    }

    // ---- get-element Batch Duplicate IDs Test ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldHandleDuplicateIds_gracefully() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeBatchGetElement(
                List.of("test-element-id", "test-element-id"));

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertEquals("Duplicate IDs should be deduplicated", 1, resultList.size());
        assertEquals("test-element-id", resultList.get(0).get("id"));
    }

    // ---- Field Selection Integration Tests (Story 5.2, Task 11.3-11.4) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnMinimalElement_whenFieldsMinimal() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-element");
        Map<String, Object> args = new HashMap<>();
        args.put("id", "test-element-id");
        args.put("fields", "minimal");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get-element", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> element = (Map<String, Object>) envelope.get("result");
        assertNotNull(element);
        assertEquals("test-element-id", element.get("id"));
        assertEquals("Test Component", element.get("name"));
        // MINIMAL: only id and name
        assertNull("type should be excluded in minimal", element.get("type"));
        assertNull("layer should be excluded in minimal", element.get("layer"));
        assertNull("documentation should be excluded in minimal", element.get("documentation"));
        assertNull("properties should be excluded in minimal", element.get("properties"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldExcludeProperties_whenExcludeParam() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-element");
        Map<String, Object> args = new HashMap<>();
        args.put("id", "test-element-id");
        args.put("exclude", List.of("properties"));
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get-element", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> element = (Map<String, Object>) envelope.get("result");
        assertNotNull(element);
        assertEquals("test-element-id", element.get("id"));
        assertEquals("Test Component", element.get("name"));
        assertEquals("ApplicationComponent", element.get("type")); // standard fields present
        assertNotNull("documentation should still be present", element.get("documentation"));
        assertNull("properties should be excluded", element.get("properties"));
    }

    // ---- Story 5.3: Model Version Change Detection Tests ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldNotIncludeModelChanged_whenVersionStable() throws Exception {
        VersionBumpAccessor accessor = new VersionBumpAccessor();
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, sm);
        handler.registerTools();

        // First call — stores version, no change
        McpSchema.CallToolResult result = invokeHandler();
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertFalse("modelChanged should not be present on first call",
                meta.containsKey("modelChanged"));

        // Second call — same version, no change
        McpSchema.CallToolResult result2 = invokeHandler();
        Map<String, Object> envelope2 = parseJson(result2);
        Map<String, Object> meta2 = (Map<String, Object>) envelope2.get("_meta");
        assertFalse("modelChanged should not be present when version unchanged",
                meta2.containsKey("modelChanged"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldIncludeModelChanged_whenVersionChanges_getModelInfo() throws Exception {
        VersionBumpAccessor accessor = new VersionBumpAccessor();
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, sm);
        handler.registerTools();

        // First call — stores version "42"
        invokeHandler();

        // Bump version to simulate model content change
        accessor.setVersion("43");

        // Second call — detects change
        McpSchema.CallToolResult result = invokeHandler();
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals("43", meta.get("modelVersion"));
        assertEquals(true, meta.get("modelChanged"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldIncludeModelChanged_whenVersionChanges_getElement() throws Exception {
        VersionBumpAccessor accessor = new VersionBumpAccessor();
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, sm);
        handler.registerTools();

        // First call — stores version "42"
        invokeGetElement("test-element-id");

        // Bump version
        accessor.setVersion("43");

        // Second call — detects change
        McpSchema.CallToolResult result = invokeGetElement("test-element-id");
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(true, meta.get("modelChanged"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldIncludeModelChanged_whenVersionChanges_batchGetElement() throws Exception {
        VersionBumpAccessor accessor = new VersionBumpAccessor();
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, sm);
        handler.registerTools();

        // First call — stores version
        invokeBatchGetElement(List.of("test-element-id"));

        // Bump version
        accessor.setVersion("43");

        // Second call — detects change
        McpSchema.CallToolResult result = invokeBatchGetElement(List.of("test-element-id"));
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(true, meta.get("modelChanged"));
    }

    // ---- Story 5.4: Session Cache Tests ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldCacheModelInfoResult_andReturnCacheHitOnSecondCall() throws Exception {
        CountingAccessor accessor = new CountingAccessor();
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, sm);
        handler.registerTools();

        // First call — cache miss
        McpSchema.CallToolResult result1 = invokeHandler();
        Map<String, Object> env1 = parseJson(result1);
        Map<String, Object> meta1 = (Map<String, Object>) env1.get("_meta");
        assertFalse("First call should not have cacheHit", meta1.containsKey("cacheHit"));
        assertEquals(1, accessor.getModelInfoCount);

        // Second call — cache hit
        McpSchema.CallToolResult result2 = invokeHandler();
        Map<String, Object> env2 = parseJson(result2);
        Map<String, Object> meta2 = (Map<String, Object>) env2.get("_meta");
        assertEquals("Second call should have cacheHit", true, meta2.get("cacheHit"));
        assertEquals("Accessor should not be called again", 1, accessor.getModelInfoCount);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldCacheSingleGetElementResult_andReturnCacheHitOnSecondCall() throws Exception {
        CountingAccessor accessor = new CountingAccessor();
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, sm);
        handler.registerTools();

        // First call — cache miss
        McpSchema.CallToolResult result1 = invokeGetElement("test-element-id");
        Map<String, Object> env1 = parseJson(result1);
        Map<String, Object> meta1 = (Map<String, Object>) env1.get("_meta");
        assertFalse("First call should not have cacheHit", meta1.containsKey("cacheHit"));
        assertEquals(1, accessor.getByIdCount);

        // Second call with same ID — cache hit
        McpSchema.CallToolResult result2 = invokeGetElement("test-element-id");
        Map<String, Object> env2 = parseJson(result2);
        Map<String, Object> meta2 = (Map<String, Object>) env2.get("_meta");
        assertEquals("Second call should have cacheHit", true, meta2.get("cacheHit"));
        assertEquals("Accessor should not be called again", 1, accessor.getByIdCount);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldNotReturnCacheHit_whenDifferentElementId() throws Exception {
        CountingAccessor accessor = new CountingAccessor();
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, sm);
        handler.registerTools();

        // First call with one ID
        invokeGetElement("test-element-id");
        assertEquals(1, accessor.getByIdCount);

        // Second call with different ID
        McpSchema.CallToolResult result2 = invokeGetElement("second-element-id");
        Map<String, Object> env2 = parseJson(result2);
        Map<String, Object> meta2 = (Map<String, Object>) env2.get("_meta");
        assertFalse("Different params should not be cacheHit", meta2.containsKey("cacheHit"));
        assertEquals("Accessor should be called for different ID", 2, accessor.getByIdCount);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldInvalidateCache_whenModelChanges() throws Exception {
        CountingAccessor accessor = new CountingAccessor();
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, sm);
        handler.registerTools();

        // First call — populate cache
        invokeHandler();
        assertEquals(1, accessor.getModelInfoCount);

        // Model changes — clear cache
        sm.onModelChanged("New Model", "model-id");

        // Next call — cache miss (re-fetched)
        McpSchema.CallToolResult result = invokeHandler();
        Map<String, Object> env = parseJson(result);
        Map<String, Object> meta = (Map<String, Object>) env.get("_meta");
        assertFalse("Should not have cacheHit after model change", meta.containsKey("cacheHit"));
        assertEquals("Accessor should be called again after cache clear", 2, accessor.getModelInfoCount);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldInvalidateCache_whenBatchGetElementDetectsVersionChange() throws Exception {
        CountingAccessor accessor = new CountingAccessor();
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        ModelQueryHandler handler = new ModelQueryHandler(accessor, formatter, registry, sm);
        handler.registerTools();

        // Populate model-info cache
        invokeHandler();
        assertEquals(1, accessor.getModelInfoCount);

        // Verify cache hit
        McpSchema.CallToolResult cached = invokeHandler();
        Map<String, Object> cachedEnv = parseJson(cached);
        Map<String, Object> cachedMeta = (Map<String, Object>) cachedEnv.get("_meta");
        assertEquals("Should be cache hit", true, cachedMeta.get("cacheHit"));
        assertEquals("Accessor not called again", 1, accessor.getModelInfoCount);

        // Bump version — batch-get-element should detect this and invalidate cache
        accessor.setVersion("43");
        invokeBatchGetElement(List.of("test-element-id"));

        // model-info cache should now be invalidated — next call must re-fetch
        McpSchema.CallToolResult fresh = invokeHandler();
        Map<String, Object> freshEnv = parseJson(fresh);
        Map<String, Object> freshMeta = (Map<String, Object>) freshEnv.get("_meta");
        assertFalse("Should not have cacheHit after batch invalidated cache",
                freshMeta.containsKey("cacheHit"));
        assertEquals("Accessor should be called again after cache invalidation",
                2, accessor.getModelInfoCount);
    }

    // ---- Helper Methods ----

    private McpSchema.CallToolResult invokeHandler() {
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-model-info");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "get-model-info", Collections.emptyMap());
        return spec.callHandler().apply(null, request);
    }

    private McpSchema.CallToolResult invokeGetElement(String id) {
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-element");
        Map<String, Object> args = (id != null) ? Map.of("id", id) : Collections.emptyMap();
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get-element", args);
        return spec.callHandler().apply(null, request);
    }

    private McpSchema.CallToolResult invokeBatchGetElement(List<String> ids) {
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-element");
        Map<String, Object> args = Map.of("ids", ids);
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("get-element", args);
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

    // ---- list-specializations tests (Story C3a) ----

    @Test
    public void shouldListAllSpecializations() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        new ModelQueryHandler(accessor, formatter, registry, null).registerTools();

        McpSchema.CallToolResult result = invokeListSpecializations(Collections.emptyMap());
        Map<String, Object> envelope = parseJson(result);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> specs = (List<Map<String, Object>>) envelope.get("result");
        assertEquals(3, specs.size());
        assertEquals("Cloud Server", specs.get(0).get("name"));
        assertEquals("Node", specs.get(0).get("conceptType"));
        assertEquals("Technology", specs.get(0).get("conceptTypeLayer"));
        assertEquals(3, specs.get(0).get("usageCount"));
    }

    @Test
    public void shouldFilterSpecializationsByConceptType() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        new ModelQueryHandler(accessor, formatter, registry, null).registerTools();

        McpSchema.CallToolResult result = invokeListSpecializations(
                Map.of("conceptType", "Node"));
        Map<String, Object> envelope = parseJson(result);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> specs = (List<Map<String, Object>>) envelope.get("result");
        assertEquals(1, specs.size());
        assertEquals("Cloud Server", specs.get(0).get("name"));
    }

    @Test
    public void shouldReturnEmptyListWhenNoSpecializationsMatch() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        new ModelQueryHandler(accessor, formatter, registry, null).registerTools();

        McpSchema.CallToolResult result = invokeListSpecializations(
                Map.of("conceptType", "Nonexistent"));
        Map<String, Object> envelope = parseJson(result);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> specs = (List<Map<String, Object>>) envelope.get("result");
        assertEquals(0, specs.size());
    }

    @Test
    public void shouldReturnErrorWhenNoModelForListSpecializations() throws Exception {
        StubAccessor accessor = new StubAccessor(false);
        new ModelQueryHandler(accessor, formatter, registry, null).registerTools();

        McpSchema.CallToolResult result = invokeListSpecializations(Collections.emptyMap());
        assertTrue(result.isError());
    }

    @Test
    public void shouldIncludeTotalCountInListSpecializationsMeta() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        new ModelQueryHandler(accessor, formatter, registry, null).registerTools();

        McpSchema.CallToolResult result = invokeListSpecializations(Collections.emptyMap());
        Map<String, Object> envelope = parseJson(result);

        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(3, meta.get("totalCount"));
    }

    private McpSchema.CallToolResult invokeListSpecializations(Map<String, Object> args) {
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("list-specializations");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("list-specializations", args);
        return spec.callHandler().apply(null, request);
    }

    // ---- Stub Implementations ----

    /**
     * Stub accessor extending BaseTestAccessor that returns canned data or throws
     * NoModelLoadedException based on constructor flag.
     */
    private static class StubAccessor extends BaseTestAccessor {

        StubAccessor(boolean modelLoaded) {
            super(modelLoaded);
        }

        @Override
        public ModelInfoDto getModelInfo() {
            if (!isModelLoaded()) {
                throw new NoModelLoadedException();
            }
            return new ModelInfoDto(
                    "Test Model", 10, 5, 3, 3,
                    Map.of("ApplicationComponent", 4, "BusinessProcess", 6),
                    Map.of("ServingRelationship", 3, "FlowRelationship", 2),
                    Map.of("Application", 4, "Business", 6));
        }

        @Override
        public Optional<ElementDto> getElementById(String id) {
            if (!isModelLoaded()) {
                throw new NoModelLoadedException();
            }
            if ("test-element-id".equals(id)) {
                return Optional.of(ElementDto.standard(
                        "test-element-id", "Test Component", "ApplicationComponent",
                        null, "Application", "A test component for unit testing",
                        List.of(Map.of("key", "version", "value", "1.0"))));
            }
            if ("second-element-id".equals(id)) {
                return Optional.of(ElementDto.standard(
                        "second-element-id", "Second Component", "BusinessProcess",
                        null, "Business", "A second test component", List.of()));
            }
            return Optional.empty();
        }

        @Override
        public List<Map<String, Object>> listSpecializations(String conceptTypeFilter) {
            if (!isModelLoaded()) throw new NoModelLoadedException();
            List<Map<String, Object>> all = List.of(
                    Map.of("name", "Cloud Server", "conceptType", "Node",
                            "conceptTypeLayer", "Technology", "usageCount", 3),
                    Map.of("name", "Web Application", "conceptType", "ApplicationComponent",
                            "conceptTypeLayer", "Application", "usageCount", 2),
                    Map.of("name", "Data Flow", "conceptType", "FlowRelationship",
                            "conceptTypeLayer", "Relationship", "usageCount", 5));
            if (conceptTypeFilter != null) {
                return all.stream()
                        .filter(m -> conceptTypeFilter.equals(m.get("conceptType")))
                        .toList();
            }
            return all;
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
        public ModelInfoDto getModelInfo() {
            throw new RuntimeException("Simulated EMF explosion");
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
        public List<ElementDto> searchElements(String query, String typeFilter, String layerFilter,
                                               String specializationFilter) {
            throw new RuntimeException("Simulated EMF explosion");
        }

        @Override
        public List<RelationshipDto> getRelationshipsForElement(String elementId) {
            throw new RuntimeException("Simulated EMF explosion");
        }
    }

    /**
     * Stub accessor that counts method invocations for cache hit/miss verification (Story 5.4).
     */
    private static class CountingAccessor extends StubAccessor {
        int getModelInfoCount = 0;
        int getByIdCount = 0;
        int getByIdsCount = 0;
        private String version = "42";

        CountingAccessor() {
            super(true);
        }

        @Override
        public String getModelVersion() {
            return version;
        }

        void setVersion(String version) {
            this.version = version;
        }

        @Override
        public ModelInfoDto getModelInfo() {
            getModelInfoCount++;
            return super.getModelInfo();
        }

        @Override
        public Optional<ElementDto> getElementById(String id) {
            getByIdCount++;
            return super.getElementById(id);
        }

        @Override
        public List<ElementDto> getElementsByIds(List<String> ids) {
            getByIdsCount++;
            return super.getElementsByIds(ids);
        }
    }

    /**
     * Accessor returning a medium-sized model (250 elements, between thresholds).
     */
    private static class MediumModelAccessor extends StubAccessor {
        MediumModelAccessor() {
            super(true);
        }

        @Override
        public ModelInfoDto getModelInfo() {
            return new ModelInfoDto("Medium Model", 250, 120, 15, 0,
                    Map.of("ApplicationComponent", 100, "BusinessProcess", 80, "Node", 70),
                    Map.of("ServingRelationship", 60, "RealizationRelationship", 30, "FlowRelationship", 30),
                    Map.of("Application", 100, "Business", 80, "Technology", 70));
        }
    }

    /**
     * Accessor returning a large model (1500 elements, above large threshold).
     */
    private static class LargeModelAccessor extends StubAccessor {
        LargeModelAccessor() {
            super(true);
        }

        @Override
        public ModelInfoDto getModelInfo() {
            return new ModelInfoDto("Large Model", 1500, 800, 50, 0,
                    Map.of("ApplicationComponent", 500, "BusinessProcess", 400, "Node", 300, "DataObject", 300),
                    Map.of("ServingRelationship", 300, "RealizationRelationship", 200, "FlowRelationship", 200, "CompositionRelationship", 100),
                    Map.of("Application", 500, "Business", 400, "Technology", 300, "Physical", 100, "Motivation", 200));
        }
    }

    /**
     * Parameterized accessor that returns a model with a specific element count.
     * Used for boundary value testing of dynamic nextSteps thresholds.
     */
    private static class SizedModelAccessor extends StubAccessor {
        private final int elementCount;

        SizedModelAccessor(int elementCount) {
            super(true);
            this.elementCount = elementCount;
        }

        @Override
        public ModelInfoDto getModelInfo() {
            return new ModelInfoDto("Sized Model", elementCount, elementCount / 2, 10, 0,
                    Map.of("ApplicationComponent", elementCount),
                    Map.of("ServingRelationship", elementCount / 2),
                    Map.of("Application", elementCount));
        }
    }
}
