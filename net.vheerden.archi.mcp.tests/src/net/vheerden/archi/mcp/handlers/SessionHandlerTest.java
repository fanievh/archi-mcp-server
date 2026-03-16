package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Unit tests for {@link SessionHandler}.
 *
 * <p>Uses a real {@link SessionManager} (pure Java, no mocking needed).
 * Follows the established handler test pattern.</p>
 */
public class SessionHandlerTest {

    private CommandRegistry registry;
    private ResponseFormatter formatter;
    private SessionManager sessionManager;
    private ObjectMapper objectMapper;

    @Before
    public void setUp() {
        registry = new CommandRegistry();
        formatter = new ResponseFormatter();
        sessionManager = new SessionManager(
                SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        objectMapper = new ObjectMapper();

        SessionHandler handler = new SessionHandler(sessionManager, formatter, registry);
        handler.registerTools();
    }

    // ---- Task 11.1 ----
    @Test
    public void shouldRegisterSetSessionFilterTool() {
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("set-session-filter");
        assertNotNull(spec);
        assertEquals("set-session-filter", spec.tool().name());
    }

    // ---- Task 11.2 ----
    @Test
    public void shouldRegisterGetSessionFiltersTool() {
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-session-filters");
        assertNotNull(spec);
        assertEquals("get-session-filters", spec.tool().name());
    }

    // ---- Task 11.3 ----
    @SuppressWarnings("unchecked")
    @Test
    public void shouldSetTypeFilter_whenTypeProvided() throws Exception {
        McpSchema.CallToolResult result = invokeSetSessionFilter(
                Map.of("type", "ApplicationComponent"));

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> resultData = (Map<String, Object>) envelope.get("result");
        Map<String, Object> activeFilters = (Map<String, Object>) resultData.get("activeFilters");
        assertNotNull(activeFilters);
        assertEquals("ApplicationComponent", activeFilters.get("type"));
        assertFalse(activeFilters.containsKey("layer"));
    }

    // ---- Task 11.4 ----
    @SuppressWarnings("unchecked")
    @Test
    public void shouldSetLayerFilter_whenLayerProvided() throws Exception {
        McpSchema.CallToolResult result = invokeSetSessionFilter(
                Map.of("layer", "Application"));

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> resultData = (Map<String, Object>) envelope.get("result");
        Map<String, Object> activeFilters = (Map<String, Object>) resultData.get("activeFilters");
        assertNotNull(activeFilters);
        assertEquals("Application", activeFilters.get("layer"));
        assertFalse(activeFilters.containsKey("type"));
    }

    // ---- Task 11.5 ----
    @SuppressWarnings("unchecked")
    @Test
    public void shouldSetBothFilters_whenBothProvided() throws Exception {
        McpSchema.CallToolResult result = invokeSetSessionFilter(
                Map.of("type", "BusinessProcess", "layer", "Business"));

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> resultData = (Map<String, Object>) envelope.get("result");
        Map<String, Object> activeFilters = (Map<String, Object>) resultData.get("activeFilters");
        assertNotNull(activeFilters);
        assertEquals("BusinessProcess", activeFilters.get("type"));
        assertEquals("Business", activeFilters.get("layer"));
    }

    // ---- Task 11.6 ----
    @SuppressWarnings("unchecked")
    @Test
    public void shouldClearFilters_whenClearTrue() throws Exception {
        // First set a filter
        invokeSetSessionFilter(Map.of("type", "ApplicationComponent"));

        // Then clear
        McpSchema.CallToolResult result = invokeSetSessionFilter(
                Map.of("clear", true));

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> resultData = (Map<String, Object>) envelope.get("result");
        assertNull(resultData.get("activeFilters"));
    }

    // ---- Task 11.7 ----
    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnActiveFilters_afterSetting() throws Exception {
        invokeSetSessionFilter(Map.of("type", "Node", "layer", "Technology"));

        // Now get filters
        McpSchema.CallToolResult result = invokeGetSessionFilters();

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> resultData = (Map<String, Object>) envelope.get("result");
        Map<String, Object> activeFilters = (Map<String, Object>) resultData.get("activeFilters");
        assertNotNull(activeFilters);
        assertEquals("Node", activeFilters.get("type"));
        assertEquals("Technology", activeFilters.get("layer"));
    }

    // ---- Task 11.8 ----
    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnNoFilters_whenNoneSet() throws Exception {
        McpSchema.CallToolResult result = invokeGetSessionFilters();

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> resultData = (Map<String, Object>) envelope.get("result");
        assertNull(resultData.get("activeFilters"));
    }

    // ---- Task 11.9 ----
    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameter_whenInvalidType() throws Exception {
        McpSchema.CallToolResult result = invokeSetSessionFilter(
                Map.of("type", "FakeType"));

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue(((String) error.get("message")).contains("FakeType"));
    }

    // ---- Task 11.10 ----
    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameter_whenInvalidLayer() throws Exception {
        McpSchema.CallToolResult result = invokeSetSessionFilter(
                Map.of("layer", "FakeLayer"));

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertNotNull(error);
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue(((String) error.get("message")).contains("FakeLayer"));
    }

    // ---- Task 11.11 ----
    @SuppressWarnings("unchecked")
    @Test
    public void shouldHaveSchemaWithOptionalParams() {
        McpSchema.Tool tool = findToolSpec("set-session-filter").tool();
        Map<String, Object> properties = tool.inputSchema().properties();

        assertTrue(properties.containsKey("type"));
        assertTrue(properties.containsKey("layer"));
        assertTrue(properties.containsKey("clear"));

        Map<String, Object> typeProp = (Map<String, Object>) properties.get("type");
        assertEquals("string", typeProp.get("type"));

        Map<String, Object> layerProp = (Map<String, Object>) properties.get("layer");
        assertEquals("string", layerProp.get("type"));

        Map<String, Object> clearProp = (Map<String, Object>) properties.get("clear");
        assertEquals("boolean", clearProp.get("type"));

        // No required params
        assertNull(tool.inputSchema().required());
    }

    // ---- Task 11.12 ----
    @SuppressWarnings("unchecked")
    @Test
    public void shouldIncludeNextSteps_inResponse() throws Exception {
        McpSchema.CallToolResult result = invokeSetSessionFilter(
                Map.of("type", "ApplicationComponent"));

        Map<String, Object> envelope = parseJson(result);
        List<String> nextSteps = (List<String>) envelope.get("nextSteps");
        assertNotNull(nextSteps);
        assertFalse(nextSteps.isEmpty());
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("search-elements")));
    }

    // ---- Field Selection Integration Tests (Story 5.2, Task 11.9-11.10) ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldSetFieldsPreference_whenFieldsProvided() throws Exception {
        McpSchema.CallToolResult result = invokeSetSessionFilter(
                Map.of("fields", "minimal"));

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> resultData = (Map<String, Object>) envelope.get("result");
        Map<String, Object> fieldSelection = (Map<String, Object>) resultData.get("activeFieldSelection");
        assertNotNull(fieldSelection);
        assertEquals("minimal", fieldSelection.get("fields"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldSetExcludePreference_whenExcludeProvided() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("exclude", List.of("documentation", "properties"));
        McpSchema.CallToolResult result = invokeSetSessionFilter(args);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> resultData = (Map<String, Object>) envelope.get("result");
        Map<String, Object> fieldSelection = (Map<String, Object>) resultData.get("activeFieldSelection");
        assertNotNull(fieldSelection);
        List<String> excludeFields = (List<String>) fieldSelection.get("exclude");
        assertNotNull(excludeFields);
        assertTrue(excludeFields.contains("documentation"));
        assertTrue(excludeFields.contains("properties"));
    }

    // ---- Additional edge case tests ----

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameter_whenNoParamsProvided() throws Exception {
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("set-session-filter");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "set-session-filter", new HashMap<>());
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue(((String) error.get("message")).contains("At least one"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldReturnInvalidParameter_whenNullArgs() throws Exception {
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("set-session-filter");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "set-session-filter", null);
        McpSchema.CallToolResult result = spec.callHandler().apply(null, request);

        assertTrue(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> error = (Map<String, Object>) envelope.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldClearThenSetNewFilters_whenClearAndTypeProvided() throws Exception {
        // Set initial filter
        invokeSetSessionFilter(Map.of("type", "ApplicationComponent", "layer", "Application"));

        // Clear and set new type
        Map<String, Object> args = new HashMap<>();
        args.put("clear", true);
        args.put("type", "BusinessProcess");
        McpSchema.CallToolResult result = invokeSetSessionFilter(args);

        assertFalse(result.isError());
        Map<String, Object> envelope = parseJson(result);
        Map<String, Object> resultData = (Map<String, Object>) envelope.get("result");
        Map<String, Object> activeFilters = (Map<String, Object>) resultData.get("activeFilters");
        assertNotNull(activeFilters);
        assertEquals("BusinessProcess", activeFilters.get("type"));
        // Layer should NOT be present (was cleared)
        assertFalse(activeFilters.containsKey("layer"));
    }

    @Test(expected = NullPointerException.class)
    public void shouldRejectNullSessionManager() {
        new SessionHandler(null, formatter, registry);
    }

    @Test(expected = NullPointerException.class)
    public void shouldRejectNullFormatter() {
        new SessionHandler(sessionManager, null, registry);
    }

    @Test(expected = NullPointerException.class)
    public void shouldRejectNullRegistry() {
        new SessionHandler(sessionManager, formatter, null);
    }

    // ---- Helper Methods ----

    private McpSchema.CallToolResult invokeSetSessionFilter(Map<String, Object> args) {
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("set-session-filter");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "set-session-filter", args);
        return spec.callHandler().apply(null, request);
    }

    private McpSchema.CallToolResult invokeGetSessionFilters() {
        McpServerFeatures.SyncToolSpecification spec = findToolSpec("get-session-filters");
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "get-session-filters", null);
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
}
