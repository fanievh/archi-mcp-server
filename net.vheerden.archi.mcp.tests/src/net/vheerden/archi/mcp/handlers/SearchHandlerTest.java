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
import net.vheerden.archi.mcp.response.dto.ViewContentsDto;
import net.vheerden.archi.mcp.response.dto.ViewDto;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Unit tests for {@link SearchHandler}.
 *
 * <p>Uses a stub ArchiModelAccessor — no EMF/OSGi runtime required.
 * Follows the established test pattern from {@link ModelQueryHandlerTest}
 * and ViewHandlerTest.</p>
 */
public class SearchHandlerTest {

    private CommandRegistry registry;
    private ResponseFormatter formatter;
    private ObjectMapper objectMapper;

    @Before
    public void setUp() {
        registry = new CommandRegistry();
        formatter = new ResponseFormatter();
        objectMapper = new ObjectMapper();
    }

    // ---- Tool Registration Tests (AC #5) ----

    @Test
    public void shouldRegisterSearchElementsTool() {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        assertEquals(1, registry.getToolCount());
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");
        assertEquals("search-elements", spec.tool().name());
    }

    @Test
    public void shouldHaveDescriptionInToolSchema() {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.Tool tool = findToolSpec("search-elements").tool();
        assertNotNull(tool.description());
        assertTrue(tool.description().contains("Search"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldHaveQueryParameterInSchema() {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.Tool tool = findToolSpec("search-elements").tool();
        assertNotNull(tool.inputSchema());
        assertEquals("object", tool.inputSchema().type());

        Map<String, Object> properties = tool.inputSchema().properties();
        assertNotNull(properties);
        assertTrue(properties.containsKey("query"));

        Map<String, Object> queryProp = (Map<String, Object>) properties.get("query");
        assertEquals("string", queryProp.get("type"));
        assertNotNull(queryProp.get("description"));

        List<String> required = tool.inputSchema().required();
        assertNotNull(required);
        assertTrue(required.contains("query"));
    }

    // ---- Success Path Tests (AC #1) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnMatchingElements_whenValidQuery() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeSearchElements("Customer");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        // Verify result is array of elements
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertEquals(1, resultList.size());

        // Verify element fields (standard level)
        Map<String, Object> element = resultList.get(0);
        assertEquals("elem-1", element.get("id"));
        assertEquals("Customer Portal", element.get("name"));
        assertEquals("ApplicationComponent", element.get("type"));
        assertEquals("Application", element.get("layer"));
        assertEquals("Main customer-facing web application", element.get("documentation"));

        // Verify nextSteps (AC #1)
        List<String> nextSteps = (List<String>) envelope.get("nextSteps");
        assertNotNull(nextSteps);
        assertEquals(2, nextSteps.size());
        assertTrue(nextSteps.get(0).contains("get-element"));
        assertTrue(nextSteps.get(1).contains("get-relationships"));

        // Verify _meta (AC #1)
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertNotNull(meta);
        assertEquals("42", meta.get("modelVersion"));
        assertEquals(1, meta.get("resultCount"));
        assertEquals(1, meta.get("totalCount"));
        assertEquals(false, meta.get("isTruncated"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnSingleMatch_whenQueryMatchesOneName() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // "component" matches both "Customer Portal" (ApplicationComponent type) and "Order Service"
        // (ApplicationComponent type) — but our stub returns based on content match
        McpSchema.CallToolResult result = invokeSearchElements("Service");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertEquals(1, resultList.size());
        assertEquals("Order Service", resultList.get(0).get("name"));
    }

    // ---- Empty Results Tests (AC #2) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnEmptyArray_whenNoMatches() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeSearchElements("nonexistent-query-xyz");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        // Verify empty result array (not an error)
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertTrue(resultList.isEmpty());

        // Verify _meta shows zero counts (AC #2)
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertNotNull(meta);
        assertEquals(0, meta.get("resultCount"));
        assertEquals(0, meta.get("totalCount"));
        assertEquals(false, meta.get("isTruncated"));

        // Verify specific nextSteps for empty results (AC #2)
        List<String> nextSteps = (List<String>) envelope.get("nextSteps");
        assertNotNull(nextSteps);
        assertEquals(2, nextSteps.size());
        assertTrue(nextSteps.get(0).contains("broader"));
        assertTrue(nextSteps.get(1).contains("get-model-info"));
    }

    // ---- Parameter Validation Tests (AC #3) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameterError_whenQueryNull() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeSearchElements(null);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertEquals("The 'query' parameter is required and must be a string", error.get("message"));
        String suggestion = (String) error.get("suggestedCorrection");
        assertNotNull(suggestion);
        assertTrue("suggestedCorrection should mention empty string usage",
                suggestion.contains("empty string"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnResults_whenQueryBlank() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "search-elements", Map.of("query", "   "));
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse("Blank query should not be an error", result.isError());
        Map<String, Object> envelope = parseJson(result);
        // Whitespace query matches nothing (no element contains "   ") — empty results, not error
        List<Map<String, Object>> results = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(results);
        assertEquals(0, results.size());
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertNotNull(meta);
        assertEquals(0, meta.get("totalCount"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnAllElements_whenQueryEmpty() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "search-elements", Map.of("query", ""));
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse("Empty query should return all elements, not error", result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> results = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(results);
        assertEquals("Empty query should return all 3 stub elements", 3, results.size());
        // Verify returned elements are the expected stub elements
        List<String> names = results.stream()
                .map(e -> (String) e.get("name")).sorted().toList();
        assertEquals(List.of("Billing Process", "Customer Portal", "Order Service"), names);
        // Verify _meta.totalCount matches full element count
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertNotNull(meta);
        assertEquals(3, meta.get("totalCount"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnFilteredElements_whenEmptyQueryWithTypeFilter() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");
        Map<String, Object> args = new HashMap<>();
        args.put("query", "");
        args.put("type", "ApplicationComponent");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search-elements", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse("Empty query with type filter should not error", result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> results = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(results);
        assertEquals("Should return only ApplicationComponent elements", 2, results.size());
        for (Map<String, Object> elem : results) {
            assertEquals("ApplicationComponent", elem.get("type"));
        }
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertNotNull(meta);
        assertEquals(2, meta.get("totalCount"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnFilteredElements_whenEmptyQueryWithLayerFilter() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");
        Map<String, Object> args = new HashMap<>();
        args.put("query", "");
        args.put("layer", "Business");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search-elements", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse("Empty query with layer filter should not error", result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> results = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(results);
        assertEquals("Should return only Business layer elements", 1, results.size());
        assertEquals("Business", results.get(0).get("layer"));
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertNotNull(meta);
        assertEquals(1, meta.get("totalCount"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldPaginate_whenEmptyQueryExceedsLimit() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");
        Map<String, Object> args = new HashMap<>();
        args.put("query", "");
        args.put("limit", 2);
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search-elements", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse("Empty query with pagination should not error", result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> results = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(results);
        assertEquals("Should return only 2 elements (limit)", 2, results.size());
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertNotNull(meta);
        assertEquals(3, meta.get("totalCount"));
        assertNotNull("Should have cursor for next page", meta.get("cursor"));

        // Verify second page retrieval via cursor
        String cursor = (String) meta.get("cursor");
        Map<String, Object> page2Args = new HashMap<>();
        page2Args.put("query", "");
        page2Args.put("cursor", cursor);
        McpSchema.CallToolRequest page2Request = new McpSchema.CallToolRequest("search-elements", page2Args);
        McpSchema.CallToolResult page2Result = spec.callHandler().apply(null, page2Request);

        assertFalse("Second page should not error", page2Result.isError());
        Map<String, Object> page2Envelope = parseJson(page2Result);
        List<Map<String, Object>> page2Results = (List<Map<String, Object>>) page2Envelope.get("result");
        assertNotNull(page2Results);
        assertEquals("Second page should have 1 remaining element", 1, page2Results.size());
        Map<String, Object> page2Meta = (Map<String, Object>) page2Envelope.get("_meta");
        assertNull("No cursor on last page", page2Meta.get("cursor"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldEstimateCost_whenEmptyQueryWithDryRun() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");
        Map<String, Object> args = new HashMap<>();
        args.put("query", "");
        args.put("dryRun", true);
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search-elements", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse("Empty query with dryRun should not error", result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> dryRunResult = (Map<String, Object>) envelope.get("dryRun");
        assertNotNull(dryRunResult);
        assertEquals(3, dryRunResult.get("estimatedResultCount"));
        assertNotNull("Should have estimatedTokens", dryRunResult.get("estimatedTokens"));
        assertNotNull("Should have recommendedPreset", dryRunResult.get("recommendedPreset"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameterError_whenQueryWrongType() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "search-elements", Map.of("query", 123));
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
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "search-elements", null);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameterError_whenQueryValueExplicitlyNull() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");
        Map<String, Object> args = new HashMap<>();
        args.put("query", null);
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "search-elements", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    // ---- Error Path Tests (AC #1, #3) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnModelNotLoadedError_whenNoModel() throws Exception {
        StubAccessor accessor = new StubAccessor(false);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeSearchElements("anything");

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
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeSearchElements("anything");

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("INTERNAL_ERROR", error.get("code"));
    }

    // ---- Constructor Validation Tests ----

    @Test(expected = NullPointerException.class)
    public void shouldRejectNullAccessor() {
        new SearchHandler(null, formatter, registry, null);
    }

    @Test(expected = NullPointerException.class)
    public void shouldRejectNullFormatter() {
        new SearchHandler(new StubAccessor(true), null, registry, null);
    }

    @Test(expected = NullPointerException.class)
    public void shouldRejectNullRegistry() {
        new SearchHandler(new StubAccessor(true), formatter, null, null);
    }

    // ---- Case-Insensitivity Test (AC #1) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldMatchCaseInsensitively() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // "customer" (lowercase) should match "Customer Portal"
        McpSchema.CallToolResult result = invokeSearchElements("customer");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertEquals(1, resultList.size());
        assertEquals("Customer Portal", resultList.get(0).get("name"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldMatchUpperCaseQuery() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // "CUSTOMER" (uppercase) should match "Customer Portal"
        McpSchema.CallToolResult result = invokeSearchElements("CUSTOMER");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertEquals(1, resultList.size());
        assertEquals("Customer Portal", resultList.get(0).get("name"));
    }

    // ---- Documentation/Property Search Tests (AC #1) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldMatchAgainstDocumentation() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // "web application" appears in documentation of "Customer Portal" but not in its name
        McpSchema.CallToolResult result = invokeSearchElements("web application");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertEquals(1, resultList.size());
        assertEquals("Customer Portal", resultList.get(0).get("name"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldMatchAgainstPropertyValues() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // "Digital Team" is a property value of "Customer Portal"
        McpSchema.CallToolResult result = invokeSearchElements("Digital Team");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertEquals(1, resultList.size());
        assertEquals("Customer Portal", resultList.get(0).get("name"));
    }

    // ---- Pagination Tests (Story 6.1) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnAllResults_whenBelowLimit() throws Exception {
        // 3 results with default limit=200 → no pagination
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeSearchElements("o");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertEquals(3, resultList.size());

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(3, meta.get("resultCount"));
        assertEquals(3, meta.get("totalCount"));
        assertEquals(false, meta.get("isTruncated"));
        assertFalse("No cursor when all results fit", meta.containsKey("cursor"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnFirstPage_whenAboveLimit() throws Exception {
        LargeResultAccessor accessor = new LargeResultAccessor();
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeSearchElements("element");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertEquals(SearchHandler.DEFAULT_SEARCH_LIMIT, resultList.size());

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertNotNull(meta);
        assertEquals(SearchHandler.DEFAULT_SEARCH_LIMIT, meta.get("resultCount"));
        assertEquals(250, meta.get("totalCount"));
        assertEquals(true, meta.get("isTruncated"));
        assertNotNull("Cursor should be present when truncated", meta.get("cursor"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnFirstPage_whenCustomLimitExceeded() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // limit=1 with 3 results → 1 result + cursor
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");
        Map<String, Object> args = new HashMap<>();
        args.put("query", "o");
        args.put("limit", 1);
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search-elements", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertEquals(1, resultList.size());

        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(1, meta.get("resultCount"));
        assertEquals(3, meta.get("totalCount"));
        assertEquals(true, meta.get("isTruncated"));
        assertNotNull("Cursor should be present", meta.get("cursor"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnNextPage_whenValidCursor() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // First page: limit=1
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");
        Map<String, Object> args1 = new HashMap<>();
        args1.put("query", "o");
        args1.put("limit", 1);
        McpSchema.CallToolResult result1 = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("search-elements", args1));
        Map<String, Object> env1 = parseJson(result1);
        Map<String, Object> meta1 = (Map<String, Object>) env1.get("_meta");
        String cursor = (String) meta1.get("cursor");
        assertNotNull(cursor);

        // Second page: use cursor
        Map<String, Object> args2 = new HashMap<>();
        args2.put("query", "o"); // required param
        args2.put("cursor", cursor);
        McpSchema.CallToolResult result2 = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("search-elements", args2));

        assertFalse(result2.isError());
        Map<String, Object> env2 = parseJson(result2);
        List<Map<String, Object>> resultList2 = (List<Map<String, Object>>) env2.get("result");
        assertEquals(1, resultList2.size());

        Map<String, Object> meta2 = (Map<String, Object>) env2.get("_meta");
        assertEquals(1, meta2.get("resultCount"));
        assertEquals(3, meta2.get("totalCount"));
        assertEquals(true, meta2.get("isTruncated")); // still more pages
        assertNotNull("Should have cursor for next page", meta2.get("cursor"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnLastPage_whenCursorReachesFinalPage() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");

        // Page through all 3 results with limit=1
        String cursor = null;
        for (int page = 0; page < 3; page++) {
            Map<String, Object> args = new HashMap<>();
            args.put("query", "o");
            args.put("limit", 1);
            if (cursor != null) args.put("cursor", cursor);
            McpSchema.CallToolResult result = spec.callHandler().apply(null,
                    new McpSchema.CallToolRequest("search-elements", args));
            Map<String, Object> env = parseJson(result);
            Map<String, Object> meta = (Map<String, Object>) env.get("_meta");
            cursor = (String) meta.get("cursor");
        }

        // After 3 pages of 1 item each, cursor should be null (last page)
        assertNull("No cursor on last page", cursor);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidCursor_whenModelVersionChanged() throws Exception {
        VersionBumpAccessor accessor = new VersionBumpAccessor();
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");

        // Get a cursor
        Map<String, Object> args1 = new HashMap<>();
        args1.put("query", "o");
        args1.put("limit", 1);
        McpSchema.CallToolResult result1 = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("search-elements", args1));
        Map<String, Object> env1 = parseJson(result1);
        String cursor = (String) ((Map<String, Object>) env1.get("_meta")).get("cursor");
        assertNotNull(cursor);

        // Change model version
        accessor.setVersion("99");

        // Use stale cursor
        Map<String, Object> args2 = new HashMap<>();
        args2.put("query", "o");
        args2.put("cursor", cursor);
        McpSchema.CallToolResult result2 = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("search-elements", args2));

        assertTrue(result2.isError());
        Map<String, Object> env2 = parseJson(result2);
        Map<String, Object> error = (Map<String, Object>) env2.get("error");
        assertEquals("INVALID_CURSOR", error.get("code"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidCursor_whenCursorMalformed() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");
        Map<String, Object> args = new HashMap<>();
        args.put("query", "test");
        args.put("cursor", "not-a-valid-cursor");
        McpSchema.CallToolResult result = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("search-elements", args));

        assertTrue(result.isError());
        Map<String, Object> env = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) env.get("error");
        assertEquals("INVALID_CURSOR", error.get("code"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameter_whenLimitTooLarge() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");
        Map<String, Object> args = new HashMap<>();
        args.put("query", "test");
        args.put("limit", 1000);
        McpSchema.CallToolResult result = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("search-elements", args));

        assertTrue(result.isError());
        Map<String, Object> env = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) env.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue(((String) error.get("message")).contains("limit"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameter_whenLimitNegative() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");
        Map<String, Object> args = new HashMap<>();
        args.put("query", "test");
        args.put("limit", -5);
        McpSchema.CallToolResult result = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("search-elements", args));

        assertTrue(result.isError());
        Map<String, Object> env = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) env.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldUseCursorParams_whenCursorAndQueryParamsProvided() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");

        // Get cursor for "o" query
        Map<String, Object> args1 = new HashMap<>();
        args1.put("query", "o");
        args1.put("limit", 1);
        McpSchema.CallToolResult result1 = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("search-elements", args1));
        Map<String, Object> env1 = parseJson(result1);
        String cursor = (String) ((Map<String, Object>) env1.get("_meta")).get("cursor");

        // Use cursor with different query — cursor params should win
        Map<String, Object> args2 = new HashMap<>();
        args2.put("query", "nonexistent"); // this would normally return 0 results
        args2.put("cursor", cursor);
        McpSchema.CallToolResult result2 = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("search-elements", args2));

        assertFalse(result2.isError());
        Map<String, Object> env2 = parseJson(result2);
        List<Map<String, Object>> resultList2 = (List<Map<String, Object>>) env2.get("result");
        // Cursor uses original "o" query → should get results
        assertFalse("Cursor params should override, returning results", resultList2.isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldHaveLimitAndCursorInSchema() {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.Tool tool = findToolSpec("search-elements").tool();
        Map<String, Object> properties = tool.inputSchema().properties();

        assertTrue("Should have limit property", properties.containsKey("limit"));
        Map<String, Object> limitProp = (Map<String, Object>) properties.get("limit");
        assertEquals("integer", limitProp.get("type"));

        assertTrue("Should have cursor property", properties.containsKey("cursor"));
        Map<String, Object> cursorProp = (Map<String, Object>) properties.get("cursor");
        assertEquals("string", cursorProp.get("type"));
    }

    // ---- Special Characters Test ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldHandleSpecialCharactersInQuery() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // Special regex characters should not cause errors (substring match, not regex)
        McpSchema.CallToolResult result = invokeSearchElements("test.*[regex]+");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        // No matches expected, but no error
        assertTrue(resultList.isEmpty());
    }

    // ---- Type Filter Tests (AC #1, #3) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldFilterByType_whenTypeProvided() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // "ApplicationComponent" type filter + broad query matching multiple elements
        McpSchema.CallToolResult result = invokeSearchElementsWithFilters(
                "o", "ApplicationComponent", null);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        // "o" matches "Customer Portal" (name) and "Order Service" (name) — both ApplicationComponent
        // Does NOT match "Billing Process" because it's BusinessProcess
        assertEquals(2, resultList.size());
        for (Map<String, Object> elem : resultList) {
            assertEquals("ApplicationComponent", elem.get("type"));
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldFilterByType_businessProcess() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeSearchElementsWithFilters(
                "Billing", "BusinessProcess", null);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertEquals(1, resultList.size());
        assertEquals("Billing Process", resultList.get(0).get("name"));
        assertEquals("BusinessProcess", resultList.get(0).get("type"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnEmpty_whenTypeFilterExcludesAllMatches() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // "Customer" matches elem-1 (ApplicationComponent) but filter is BusinessProcess
        McpSchema.CallToolResult result = invokeSearchElementsWithFilters(
                "Customer", "BusinessProcess", null);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertTrue(resultList.isEmpty());
    }

    // ---- Layer Filter Tests (AC #2, #3) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldFilterByLayer_application() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeSearchElementsWithFilters(
                "Customer", null, "Application");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertEquals(1, resultList.size());
        assertEquals("Application", resultList.get(0).get("layer"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldFilterByLayer_business() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeSearchElementsWithFilters(
                "Billing", null, "Business");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertEquals(1, resultList.size());
        assertEquals("Business", resultList.get(0).get("layer"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnEmpty_whenLayerFilterExcludesAllMatches() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // "Customer" matches elem-1 (Application layer) but filter is Business
        McpSchema.CallToolResult result = invokeSearchElementsWithFilters(
                "Customer", null, "Business");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertTrue(resultList.isEmpty());
    }

    // ---- Combined Type + Layer Filter Tests (AC #3) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldApplyBothTypeAndLayerFilters() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // Type=ApplicationComponent + Layer=Application matches elem-1 and elem-2
        McpSchema.CallToolResult result = invokeSearchElementsWithFilters(
                "o", "ApplicationComponent", "Application");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertEquals(2, resultList.size());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnEmpty_whenTypeAndLayerConflict() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // ApplicationComponent is Application layer, not Business → 0 results
        McpSchema.CallToolResult result = invokeSearchElementsWithFilters(
                "o", "ApplicationComponent", "Business");

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertTrue(resultList.isEmpty());
    }

    // ---- Invalid Type/Layer Validation Tests (AC #4) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameterError_whenInvalidType() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeSearchElementsWithFilters(
                "anything", "FakeType", null);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("INVALID_PARAMETER", error.get("code"));
        String message = (String) error.get("message");
        assertTrue(message.contains("FakeType"));
        String correction = (String) error.get("suggestedCorrection");
        assertNotNull(correction);
        assertTrue(correction.contains("get-model-info"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameterError_whenInvalidLayer() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.CallToolResult result = invokeSearchElementsWithFilters(
                "anything", null, "FakeLayer");

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("INVALID_PARAMETER", error.get("code"));
        String message = (String) error.get("message");
        assertTrue(message.contains("FakeLayer"));
        String correction = (String) error.get("suggestedCorrection");
        assertNotNull(correction);
        assertTrue(correction.contains("Business"));
        assertTrue(correction.contains("Application"));
        assertTrue(correction.contains("Technology"));
    }

    // ---- Case-Sensitivity Tests (AC #4 edge cases) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameterError_whenTypeCaseWrong() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // "applicationComponent" (lowercase 'a') is a realistic LLM mistake
        McpSchema.CallToolResult result = invokeSearchElementsWithFilters(
                "anything", "applicationComponent", null);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue(((String) error.get("message")).contains("applicationComponent"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameterError_whenLayerCaseWrong() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // "business" (lowercase 'b') is a realistic LLM mistake
        McpSchema.CallToolResult result = invokeSearchElementsWithFilters(
                "anything", null, "business");

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue(((String) error.get("message")).contains("business"));
    }

    // ---- Blank/Empty Type and Layer Tests (AC #4 edge cases) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldTreatBlankTypeAsNoFilter() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // Blank type should be treated as no filter (all types returned)
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");
        Map<String, Object> args = new HashMap<>();
        args.put("query", "o");
        args.put("type", "   ");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search-elements", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        // "o" matches: "Customer Portal", "Order Service", "Billing Process" (all 3)
        assertEquals(3, resultList.size());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldTreatBlankLayerAsNoFilter() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");
        Map<String, Object> args = new HashMap<>();
        args.put("query", "o");
        args.put("layer", "");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search-elements", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertEquals(3, resultList.size());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldTreatIntegerTypeAsNoFilter() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // Integer type value should be ignored (not instanceof String)
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");
        Map<String, Object> args = new HashMap<>();
        args.put("query", "o");
        args.put("type", 42);
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search-elements", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertEquals(3, resultList.size());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldTreatIntegerLayerAsNoFilter() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // Integer layer value should be ignored (not instanceof String)
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");
        Map<String, Object> args = new HashMap<>();
        args.put("query", "o");
        args.put("layer", 42);
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search-elements", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertEquals(3, resultList.size());
    }

    // ---- Type/Layer Filter with No Text Matches (AC #1, #2 edge case) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnEmpty_whenValidTypeButNoTextMatch() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        // Valid type but query doesn't match any element
        McpSchema.CallToolResult result = invokeSearchElementsWithFilters(
                "nonexistent-xyz", "ApplicationComponent", null);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertNotNull(resultList);
        assertTrue(resultList.isEmpty());
    }

    // ---- Updated Schema Tests (AC #5) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldHaveTypeAndLayerInSchema() {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.Tool tool = findToolSpec("search-elements").tool();
        Map<String, Object> properties = tool.inputSchema().properties();
        assertNotNull(properties);

        // Verify type property exists with correct schema
        assertTrue(properties.containsKey("type"));
        Map<String, Object> typeProp = (Map<String, Object>) properties.get("type");
        assertEquals("string", typeProp.get("type"));
        assertNotNull(typeProp.get("description"));

        // Verify layer property exists with correct schema
        assertTrue(properties.containsKey("layer"));
        Map<String, Object> layerProp = (Map<String, Object>) properties.get("layer");
        assertEquals("string", layerProp.get("type"));
        assertNotNull(layerProp.get("description"));
    }

    @Test
    public void shouldNotRequireTypeOrLayerInSchema() {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.Tool tool = findToolSpec("search-elements").tool();
        List<String> required = tool.inputSchema().required();
        assertNotNull(required);
        assertTrue(required.contains("query"));
        assertFalse("type should NOT be required", required.contains("type"));
        assertFalse("layer should NOT be required", required.contains("layer"));
    }

    // ---- VALID_TYPES and VALID_LAYERS Constant Tests ----

    @Test
    public void validTypesShouldContainCoreTypes() {
        // Guard against accidental omissions
        assertEquals(61, SearchHandler.VALID_TYPES.size());
        // Spot-check representative types from each layer
        assertTrue(SearchHandler.VALID_TYPES.contains("ApplicationComponent"));
        assertTrue(SearchHandler.VALID_TYPES.contains("BusinessProcess"));
        assertTrue(SearchHandler.VALID_TYPES.contains("Node"));
        assertTrue(SearchHandler.VALID_TYPES.contains("Equipment"));
        assertTrue(SearchHandler.VALID_TYPES.contains("Capability"));
        assertTrue(SearchHandler.VALID_TYPES.contains("Stakeholder"));
        assertTrue(SearchHandler.VALID_TYPES.contains("WorkPackage"));
        assertTrue(SearchHandler.VALID_TYPES.contains("Location"));
    }

    @Test
    public void validLayersShouldContainAllLayers() {
        assertEquals(7, SearchHandler.VALID_LAYERS.size());
        assertTrue(SearchHandler.VALID_LAYERS.contains("Business"));
        assertTrue(SearchHandler.VALID_LAYERS.contains("Application"));
        assertTrue(SearchHandler.VALID_LAYERS.contains("Technology"));
        assertTrue(SearchHandler.VALID_LAYERS.contains("Physical"));
        assertTrue(SearchHandler.VALID_LAYERS.contains("Strategy"));
        assertTrue(SearchHandler.VALID_LAYERS.contains("Motivation"));
        assertTrue(SearchHandler.VALID_LAYERS.contains("Implementation & Migration"));
    }

    // ---- Story 5.3: Model Version Change Detection Tests ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldNotIncludeModelChanged_whenVersionStable() throws Exception {
        VersionBumpAccessor accessor = new VersionBumpAccessor();
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, sm);
        handler.registerTools();

        // First call — stores version, no change
        McpSchema.CallToolResult result = invokeSearchElements("test");
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertFalse("modelChanged should not be present on first call",
                meta.containsKey("modelChanged"));

        // Second call — same version, no change
        McpSchema.CallToolResult result2 = invokeSearchElements("test");
        Map<String, Object> envelope2 = parseJson(result2);
        Map<String, Object> meta2 = (Map<String, Object>) envelope2.get("_meta");
        assertFalse("modelChanged should not be present when version unchanged",
                meta2.containsKey("modelChanged"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldIncludeModelChanged_whenVersionChanges_searchElements() throws Exception {
        VersionBumpAccessor accessor = new VersionBumpAccessor();
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, sm);
        handler.registerTools();

        // First call — stores version
        invokeSearchElements("test");

        // Bump version
        accessor.setVersion("43");

        // Second call — detects change
        McpSchema.CallToolResult result = invokeSearchElements("test");
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(true, meta.get("modelChanged"));
    }

    // ---- Helper Methods ----

    private McpSchema.CallToolResult invokeSearchElements(String query) {
        return invokeSearchElementsWithFilters(query, null, null);
    }

    private McpSchema.CallToolResult invokeSearchElementsWithFilters(
            String query, String type, String layer) {
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");
        Map<String, Object> args = new HashMap<>();
        if (query != null) {
            args.put("query", query);
        }
        if (type != null) {
            args.put("type", type);
        }
        if (layer != null) {
            args.put("layer", layer);
        }
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search-elements", args);
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

    // ---- Session Filter Integration Tests (Task 12.1-12.5) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldApplySessionTypeFilter_whenNoPerQueryType() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        sm.setSessionFilter("default", "ApplicationComponent", null);
        CommandRegistry sessionRegistry = new CommandRegistry();
        SearchHandler handler = new SearchHandler(accessor, formatter, sessionRegistry, sm);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = sessionRegistry.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals("search-elements")).findFirst().orElseThrow();
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "search-elements", Map.of("query", "o"));
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        // "o" matches all 3 elements, but session type filter limits to ApplicationComponent
        assertEquals(2, resultList.size());
        for (Map<String, Object> elem : resultList) {
            assertEquals("ApplicationComponent", elem.get("type"));
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldOverrideSessionType_whenPerQueryTypeProvided() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        sm.setSessionFilter("default", "ApplicationComponent", null);
        CommandRegistry sessionRegistry = new CommandRegistry();
        SearchHandler handler = new SearchHandler(accessor, formatter, sessionRegistry, sm);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = sessionRegistry.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals("search-elements")).findFirst().orElseThrow();
        Map<String, Object> args = new HashMap<>();
        args.put("query", "Billing");
        args.put("type", "BusinessProcess");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search-elements", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertEquals(1, resultList.size());
        assertEquals("BusinessProcess", resultList.get(0).get("type"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldApplySessionLayerFilter_whenNoPerQueryLayer() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        sm.setSessionFilter("default", null, "Business");
        CommandRegistry sessionRegistry = new CommandRegistry();
        SearchHandler handler = new SearchHandler(accessor, formatter, sessionRegistry, sm);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = sessionRegistry.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals("search-elements")).findFirst().orElseThrow();
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "search-elements", Map.of("query", "o"));
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        // Only "Billing Process" is in Business layer and matches "o"
        assertEquals(1, resultList.size());
        assertEquals("Business", resultList.get(0).get("layer"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldOverrideSessionLayer_whenPerQueryLayerProvided() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        sm.setSessionFilter("default", null, "Business");
        CommandRegistry sessionRegistry = new CommandRegistry();
        SearchHandler handler = new SearchHandler(accessor, formatter, sessionRegistry, sm);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = sessionRegistry.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals("search-elements")).findFirst().orElseThrow();
        Map<String, Object> args = new HashMap<>();
        args.put("query", "o");
        args.put("layer", "Application");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search-elements", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        // Per-query "Application" overrides session "Business"
        assertEquals(2, resultList.size());
        for (Map<String, Object> elem : resultList) {
            assertEquals("Application", elem.get("layer"));
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldApplyCombinedSessionFilters() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        sm.setSessionFilter("default", "ApplicationComponent", "Application");
        CommandRegistry sessionRegistry = new CommandRegistry();
        SearchHandler handler = new SearchHandler(accessor, formatter, sessionRegistry, sm);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = sessionRegistry.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals("search-elements")).findFirst().orElseThrow();
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "search-elements", Map.of("query", "o"));
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertEquals(2, resultList.size());
        for (Map<String, Object> elem : resultList) {
            assertEquals("ApplicationComponent", elem.get("type"));
            assertEquals("Application", elem.get("layer"));
        }
    }

    // ---- Field Selection Integration Tests (Story 5.2, Task 11.1-11.2) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnMinimalElements_whenFieldsMinimal() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");
        Map<String, Object> args = new HashMap<>();
        args.put("query", "Customer");
        args.put("fields", "minimal");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search-elements", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertEquals(1, resultList.size());

        Map<String, Object> element = resultList.get(0);
        assertEquals("elem-1", element.get("id"));
        assertEquals("Customer Portal", element.get("name"));
        // MINIMAL: only id and name — no type, layer, documentation, properties
        assertNull("type should be excluded in minimal", element.get("type"));
        assertNull("layer should be excluded in minimal", element.get("layer"));
        assertNull("documentation should be excluded in minimal", element.get("documentation"));
        assertNull("properties should be excluded in minimal", element.get("properties"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldExcludeDocumentation_whenExcludeParam() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");
        Map<String, Object> args = new HashMap<>();
        args.put("query", "Customer");
        args.put("exclude", List.of("documentation"));
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search-elements", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertEquals(1, resultList.size());

        Map<String, Object> element = resultList.get(0);
        assertEquals("elem-1", element.get("id"));
        assertEquals("Customer Portal", element.get("name"));
        assertEquals("ApplicationComponent", element.get("type")); // standard fields present
        assertNull("documentation should be excluded", element.get("documentation"));
    }

    // ---- AC #5: Invalid fields preset fallback + warning (H2 code review fix) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldFallbackToStandard_whenInvalidFieldsPreset() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");
        Map<String, Object> args = new HashMap<>();
        args.put("query", "Customer");
        args.put("fields", "bogus_preset");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("search-elements", args);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        // Should NOT be an error — falls back to standard
        assertFalse("Invalid preset should not cause error", result.isError());
        Map<String, Object> envelope = parseJson(result);

        // _meta.warning should be present with fallback message
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertNotNull("_meta should be present", meta);
        String warning = (String) meta.get("warning");
        assertNotNull("_meta.warning should be present for invalid preset", warning);
        assertTrue("Warning should mention the invalid preset",
                warning.contains("bogus_preset"));
        assertTrue("Warning should mention 'standard' fallback",
                warning.contains("standard"));

        // Result should use standard fields (type, layer present)
        List<Map<String, Object>> resultList = (List<Map<String, Object>>) envelope.get("result");
        assertFalse("Should have results", resultList.isEmpty());
        Map<String, Object> element = resultList.get(0);
        assertNotNull("Standard fields should include type", element.get("type"));
        assertNotNull("Standard fields should include layer", element.get("layer"));
    }

    // ---- Story 5.4: Session Cache Tests ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldCacheSearchResult_andReturnCacheHitOnSecondCall() throws Exception {
        CountingAccessor accessor = new CountingAccessor();
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, sm);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");

        // First call — cache miss
        Map<String, Object> args1 = new HashMap<>();
        args1.put("query", "Customer");
        McpSchema.CallToolResult result1 = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("search-elements", args1));
        Map<String, Object> env1 = parseJson(result1);
        Map<String, Object> meta1 = (Map<String, Object>) env1.get("_meta");
        assertFalse("First call should not have cacheHit", meta1.containsKey("cacheHit"));
        assertEquals(1, accessor.searchCount);

        // Second call with same params — cache hit
        Map<String, Object> args2 = new HashMap<>();
        args2.put("query", "Customer");
        McpSchema.CallToolResult result2 = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("search-elements", args2));
        Map<String, Object> env2 = parseJson(result2);
        Map<String, Object> meta2 = (Map<String, Object>) env2.get("_meta");
        assertEquals("Second call should have cacheHit", true, meta2.get("cacheHit"));
        assertEquals("Accessor should not be called again", 1, accessor.searchCount);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldNotReturnCacheHit_whenDifferentSearchQuery() throws Exception {
        CountingAccessor accessor = new CountingAccessor();
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, sm);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");

        // First call
        Map<String, Object> args1 = new HashMap<>();
        args1.put("query", "Customer");
        spec.callHandler().apply(null, new McpSchema.CallToolRequest("search-elements", args1));
        assertEquals(1, accessor.searchCount);

        // Second call with different query
        Map<String, Object> args2 = new HashMap<>();
        args2.put("query", "Order");
        McpSchema.CallToolResult result2 = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("search-elements", args2));
        Map<String, Object> env2 = parseJson(result2);
        Map<String, Object> meta2 = (Map<String, Object>) env2.get("_meta");
        assertFalse("Different query should not be cacheHit", meta2.containsKey("cacheHit"));
        assertEquals("Accessor should be called for different query", 2, accessor.searchCount);
    }

    // ---- Story 6.2: DryRun Tests ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnDryRunEstimate_whenDryRunTrue() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");
        Map<String, Object> args = new HashMap<>();
        args.put("query", "o");
        args.put("dryRun", true);
        McpSchema.CallToolResult result = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("search-elements", args));

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);

        // dryRun object at top level
        Map<String, Object> dryRun = (Map<String, Object>) envelope.get("dryRun");
        assertNotNull("dryRun key must be present", dryRun);
        assertEquals(3, dryRun.get("estimatedResultCount"));
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
    public void shouldIgnoreCursorAndLimit_whenDryRun() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");
        Map<String, Object> args = new HashMap<>();
        args.put("query", "o");
        args.put("dryRun", true);
        args.put("limit", 1);
        args.put("cursor", "some-invalid-cursor");
        McpSchema.CallToolResult result = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("search-elements", args));

        assertFalse("dryRun should not error on invalid cursor", result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> dryRun = (Map<String, Object>) envelope.get("dryRun");
        // Should count ALL 3 matches, not limited to 1
        assertEquals(3, dryRun.get("estimatedResultCount"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldRespectSessionFilters_whenDryRun() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        sm.setSessionFilter("default", "ApplicationComponent", null);
        CommandRegistry sessionRegistry = new CommandRegistry();
        SearchHandler handler = new SearchHandler(accessor, formatter, sessionRegistry, sm);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = sessionRegistry.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals("search-elements")).findFirst().orElseThrow();
        Map<String, Object> args = new HashMap<>();
        args.put("query", "o");
        args.put("dryRun", true);
        McpSchema.CallToolResult result = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("search-elements", args));

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> dryRun = (Map<String, Object>) envelope.get("dryRun");
        // Session filter limits to ApplicationComponent: 2 of 3 match
        assertEquals(2, dryRun.get("estimatedResultCount"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldValidateQuery_whenDryRun() throws Exception {
        StubAccessor accessor = new StubAccessor(true);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");
        Map<String, Object> args = new HashMap<>();
        args.put("dryRun", true);
        // No query parameter
        McpSchema.CallToolResult result = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("search-elements", args));

        assertTrue("dryRun should still validate required params", result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldNotCache_whenDryRun() throws Exception {
        CountingAccessor accessor = new CountingAccessor();
        SessionManager sm = new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, sm);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");

        // dryRun call
        Map<String, Object> args1 = new HashMap<>();
        args1.put("query", "Customer");
        args1.put("dryRun", true);
        spec.callHandler().apply(null, new McpSchema.CallToolRequest("search-elements", args1));
        assertEquals(1, accessor.searchCount);

        // Regular call with same query — should NOT be a cache hit
        Map<String, Object> args2 = new HashMap<>();
        args2.put("query", "Customer");
        McpSchema.CallToolResult result2 = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("search-elements", args2));
        assertEquals("Regular call after dryRun should execute fresh query", 2, accessor.searchCount);

        Map<String, Object> env2 = parseJson(result2);
        Map<String, Object> meta2 = (Map<String, Object>) env2.get("_meta");
        assertFalse("Should not be cache hit after dryRun", meta2.containsKey("cacheHit"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldIncludeNextSteps_inDryRunResponse() throws Exception {
        LargeResultAccessor accessor = new LargeResultAccessor();
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpServerFeatures.SyncToolSpecification spec = findToolSpec("search-elements");
        Map<String, Object> args = new HashMap<>();
        args.put("query", "element");
        args.put("dryRun", true);
        McpSchema.CallToolResult result = spec.callHandler().apply(null,
                new McpSchema.CallToolRequest("search-elements", args));

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
        SearchHandler handler = new SearchHandler(accessor, formatter, registry, null);
        handler.registerTools();

        McpSchema.Tool tool = findToolSpec("search-elements").tool();
        Map<String, Object> properties = tool.inputSchema().properties();
        assertTrue("Should have dryRun property", properties.containsKey("dryRun"));
        Map<String, Object> dryRunProp = (Map<String, Object>) properties.get("dryRun");
        assertEquals("boolean", dryRunProp.get("type"));
    }

    // ---- Stub Implementations ----

    /**
     * Stub accessor with canned search data.
     * Returns elements matching by name, documentation, or property values.
     */
    private static class StubAccessor extends BaseTestAccessor {

        private static final List<ElementDto> ALL_ELEMENTS = List.of(
                ElementDto.standard("elem-1", "Customer Portal", "ApplicationComponent",
                        "Application", "Main customer-facing web application",
                        List.of(Map.of("key", "owner", "value", "Digital Team"))),
                ElementDto.standard("elem-2", "Order Service", "ApplicationComponent",
                        "Application", "Handles order processing",
                        List.of(Map.of("key", "owner", "value", "Backend Team"))),
                ElementDto.standard("elem-3", "Billing Process", "BusinessProcess",
                        "Business", null, null)
        );

        StubAccessor(boolean modelLoaded) {
            super(modelLoaded);
        }

        @Override
        public List<ElementDto> searchElements(String query, String typeFilter, String layerFilter) {
            if (!isModelLoaded()) {
                throw new NoModelLoadedException();
            }
            String lowerQuery = query.toLowerCase();
            return ALL_ELEMENTS.stream()
                    .filter(e -> typeFilter == null || typeFilter.equals(e.type()))
                    .filter(e -> layerFilter == null || layerFilter.equals(e.layer()))
                    .filter(e -> matchesElement(e, lowerQuery))
                    .toList();
        }

        private boolean matchesElement(ElementDto element, String lowerQuery) {
            if (element.name() != null
                    && element.name().toLowerCase().contains(lowerQuery)) {
                return true;
            }
            if (element.documentation() != null
                    && element.documentation().toLowerCase().contains(lowerQuery)) {
                return true;
            }
            if (element.properties() != null) {
                for (Map<String, String> prop : element.properties()) {
                    String value = prop.get("value");
                    if (value != null && value.toLowerCase().contains(lowerQuery)) {
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public ModelInfoDto getModelInfo() {
            if (!isModelLoaded()) throw new NoModelLoadedException();
            return new ModelInfoDto("Test Model", 3, 0, 0, Map.of(), Map.of(), Map.of());
        }

        @Override
        public Optional<ElementDto> getElementById(String id) {
            if (!isModelLoaded()) throw new NoModelLoadedException();
            return ALL_ELEMENTS.stream()
                    .filter(e -> e.id().equals(id))
                    .findFirst();
        }

        @Override
        public List<ElementDto> getElementsByIds(List<String> ids) {
            if (!isModelLoaded()) throw new NoModelLoadedException();
            return ids.stream()
                    .map(this::getElementById)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .toList();
        }

        @Override
        public List<ViewDto> getViews(String viewpointFilter) {
            return Collections.emptyList();
        }

        @Override
        public Optional<ViewContentsDto> getViewContents(String viewId) {
            return Optional.empty();
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
        public List<ElementDto> searchElements(String query, String typeFilter, String layerFilter) {
            throw new RuntimeException("Simulated EMF explosion");
        }
    }

    /**
     * Accessor that returns more results than MAX_SEARCH_RESULTS to test truncation.
     */
    private static class LargeResultAccessor extends StubAccessor {
        LargeResultAccessor() {
            super(true);
        }

        @Override
        public List<ElementDto> searchElements(String query, String typeFilter, String layerFilter) {
            List<ElementDto> largeList = new java.util.ArrayList<>();
            for (int i = 0; i < 250; i++) {
                largeList.add(ElementDto.standard(
                        "elem-" + i, "Element " + i, "ApplicationComponent",
                        "Application", null, null));
            }
            return largeList;
        }
    }

    /**
     * Stub accessor that counts method invocations for cache hit/miss verification (Story 5.4).
     */
    private static class CountingAccessor extends StubAccessor {
        int searchCount = 0;

        CountingAccessor() {
            super(true);
        }

        @Override
        public List<ElementDto> searchElements(String query, String typeFilter, String layerFilter) {
            searchCount++;
            return super.searchElements(query, typeFilter, layerFilter);
        }
    }
}
