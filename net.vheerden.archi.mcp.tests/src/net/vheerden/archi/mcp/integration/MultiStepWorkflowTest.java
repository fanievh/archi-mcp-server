package net.vheerden.archi.mcp.integration;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.handlers.ModelQueryHandler;
import net.vheerden.archi.mcp.handlers.SearchHandler;
import net.vheerden.archi.mcp.handlers.ViewHandler;
import net.vheerden.archi.mcp.model.ArchiModelAccessor;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;

/**
 * Integration test verifying multi-step exploration workflow.
 * Tests AC #2: chained tool invocations using data from previous responses.
 *
 * <p>Simulates the workflow: get-model-info -> get-views -> get-view-contents -> get-element,
 * using output from each step to inform the next.</p>
 */
public class MultiStepWorkflowTest {

    private CommandRegistry registry;
    private ObjectMapper objectMapper;

    @Before
    public void setUp() {
        registry = new CommandRegistry();
        objectMapper = new ObjectMapper();
        ResponseFormatter formatter = new ResponseFormatter();
        ArchiModelAccessor accessor = new IntegrationStubAccessor(true);

        ModelQueryHandler mqh = new ModelQueryHandler(accessor, formatter, registry, null);
        ViewHandler vh = new ViewHandler(accessor, formatter, registry, null);
        SearchHandler sh = new SearchHandler(accessor, formatter, registry, null);
        mqh.registerTools();
        vh.registerTools();
        sh.registerTools();
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldCompleteFullExplorationWorkflow() throws Exception {
        // Step 1: get-model-info — discover what's in the model
        McpSchema.CallToolResult modelInfoResult = invokeTool("get-model-info", Collections.emptyMap());
        assertFalse("get-model-info should succeed", modelInfoResult.isError());
        Map<String, Object> modelInfoEnvelope = parseJson(modelInfoResult);
        Map<String, Object> modelInfo = (Map<String, Object>) modelInfoEnvelope.get("result");
        assertNotNull("get-model-info result should not be null", modelInfo);
        assertEquals("Integration Test Model", modelInfo.get("name"));
        assertEquals(1, modelInfo.get("viewCount"));

        // Step 2: get-views — list available views
        McpSchema.CallToolResult viewsResult = invokeTool("get-views", Collections.emptyMap());
        assertFalse("get-views should succeed", viewsResult.isError());
        Map<String, Object> viewsEnvelope = parseJson(viewsResult);
        List<Map<String, Object>> views = (List<Map<String, Object>>) viewsEnvelope.get("result");
        assertNotNull("get-views result should not be null", views);
        assertFalse("get-views should return at least one view", views.isEmpty());

        // Extract view ID from the first view in the result
        String viewId = (String) views.get(0).get("id");
        assertNotNull("View should have an ID", viewId);
        assertEquals("view-int-1", viewId);

        // Step 3: get-view-contents — examine the view's contents
        McpSchema.CallToolResult viewContentsResult = invokeTool("get-view-contents", Map.of("viewId", viewId));
        assertFalse("get-view-contents should succeed", viewContentsResult.isError());
        Map<String, Object> viewContentsEnvelope = parseJson(viewContentsResult);
        Map<String, Object> viewContents = (Map<String, Object>) viewContentsEnvelope.get("result");
        assertNotNull("get-view-contents result should not be null", viewContents);

        List<Map<String, Object>> elements = (List<Map<String, Object>>) viewContents.get("elements");
        assertNotNull("View should contain elements", elements);
        assertFalse("View should have at least one element", elements.isEmpty());

        // Extract element ID from the first element in the view
        String elementId = (String) elements.get(0).get("id");
        assertNotNull("Element should have an ID", elementId);

        // Step 4: get-element — get detailed info about the element
        McpSchema.CallToolResult elementResult = invokeTool("get-element", Map.of("id", elementId));
        assertFalse("get-element should succeed", elementResult.isError());
        Map<String, Object> elementEnvelope = parseJson(elementResult);
        Map<String, Object> element = (Map<String, Object>) elementEnvelope.get("result");
        assertNotNull("get-element result should not be null", element);
        assertEquals("Element ID should match", elementId, element.get("id"));
        assertNotNull("Element should have a name", element.get("name"));
        assertNotNull("Element should have a type", element.get("type"));
        assertNotNull("Element should have a layer", element.get("layer"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldIncludeNextStepsInEveryResponse() throws Exception {
        // Invoke all 5 tools and verify each has nextSteps
        Map<String, Map<String, Object>> toolArgs = Map.of(
                "get-model-info", Collections.emptyMap(),
                "get-views", Collections.emptyMap(),
                "get-view-contents", Map.of("viewId", "view-int-1"),
                "get-element", Map.of("id", "elem-int-1"),
                "search-elements", Map.of("query", "Portal"));

        for (Map.Entry<String, Map<String, Object>> entry : toolArgs.entrySet()) {
            String toolName = entry.getKey();
            McpSchema.CallToolResult result = invokeTool(toolName, entry.getValue());
            assertFalse(toolName + " should succeed", result.isError());

            Map<String, Object> envelope = parseJson(result);
            List<String> nextSteps = (List<String>) envelope.get("nextSteps");
            assertNotNull(toolName + " should include nextSteps", nextSteps);
            assertFalse(toolName + " nextSteps should not be empty", nextSteps.isEmpty());
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldIncludeMetaInEveryResponse() throws Exception {
        Map<String, Map<String, Object>> toolArgs = Map.of(
                "get-model-info", Collections.emptyMap(),
                "get-views", Collections.emptyMap(),
                "get-view-contents", Map.of("viewId", "view-int-1"),
                "get-element", Map.of("id", "elem-int-1"),
                "search-elements", Map.of("query", "Portal"));

        for (Map.Entry<String, Map<String, Object>> entry : toolArgs.entrySet()) {
            String toolName = entry.getKey();
            McpSchema.CallToolResult result = invokeTool(toolName, entry.getValue());
            assertFalse(toolName + " should succeed", result.isError());

            Map<String, Object> envelope = parseJson(result);
            Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
            assertNotNull(toolName + " should include _meta", meta);
            assertNotNull(toolName + " _meta should have modelVersion", meta.get("modelVersion"));
            assertNotNull(toolName + " _meta should have resultCount", meta.get("resultCount"));
            assertNotNull(toolName + " _meta should have totalCount", meta.get("totalCount"));
            assertNotNull(toolName + " _meta should have isTruncated", meta.get("isTruncated"));
        }
    }

    /**
     * Validates handler processing overhead stays within NFR1 budget (2s).
     * Note: tests stub accessor, not real model access — validates handler/formatter
     * overhead only. Real model access performance requires E2E testing with Archi.
     */
    @Test
    public void shouldCompleteWithinPerformanceBudget() throws Exception {
        Map<String, Map<String, Object>> toolArgs = Map.of(
                "get-model-info", Collections.emptyMap(),
                "get-views", Collections.emptyMap(),
                "get-view-contents", Map.of("viewId", "view-int-1"),
                "get-element", Map.of("id", "elem-int-1"),
                "search-elements", Map.of("query", "Portal"));

        for (Map.Entry<String, Map<String, Object>> entry : toolArgs.entrySet()) {
            String toolName = entry.getKey();
            long startTime = System.currentTimeMillis();
            McpSchema.CallToolResult result = invokeTool(toolName, entry.getValue());
            long elapsed = System.currentTimeMillis() - startTime;

            assertFalse(toolName + " should succeed", result.isError());
            assertTrue(toolName + " should complete within 2000ms (NFR1), took " + elapsed + "ms",
                    elapsed < 2000);
        }
    }

    // ---- Helper Methods ----

    private McpSchema.CallToolResult invokeTool(String toolName, Map<String, Object> args) {
        McpServerFeatures.SyncToolSpecification spec = registry.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not found: " + toolName));
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(toolName, args);
        return spec.callHandler().apply(null, request);
    }

    private Map<String, Object> parseJson(McpSchema.CallToolResult result) throws Exception {
        McpSchema.TextContent content = (McpSchema.TextContent) result.content().get(0);
        return objectMapper.readValue(content.text(),
                new TypeReference<Map<String, Object>>() {});
    }
}
