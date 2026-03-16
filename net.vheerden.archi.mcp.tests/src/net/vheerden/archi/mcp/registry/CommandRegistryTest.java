package net.vheerden.archi.mcp.registry;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Unit tests for {@link CommandRegistry}.
 */
public class CommandRegistryTest {

    private CommandRegistry registry;

    @Before
    public void setUp() {
        registry = new CommandRegistry();
    }

    @Test
    public void shouldReturnEmptyList_whenNoToolsRegistered() {
        List<McpServerFeatures.SyncToolSpecification> specs = registry.getToolSpecifications();
        assertNotNull(specs);
        assertTrue(specs.isEmpty());
    }

    @Test
    public void shouldReturnZeroCount_whenNoToolsRegistered() {
        assertEquals(0, registry.getToolCount());
    }

    @Test
    public void shouldRegisterTool_andReturnIt() {
        McpServerFeatures.SyncToolSpecification spec = createToolSpec("test-tool", "A test tool");
        registry.registerTool(spec);

        assertEquals(1, registry.getToolCount());
        List<McpServerFeatures.SyncToolSpecification> specs = registry.getToolSpecifications();
        assertEquals(1, specs.size());
        assertEquals("test-tool", specs.get(0).tool().name());
    }

    @Test
    public void shouldRegisterMultipleTools() {
        registry.registerTool(createToolSpec("tool-a", "Tool A"));
        registry.registerTool(createToolSpec("tool-b", "Tool B"));
        registry.registerTool(createToolSpec("tool-c", "Tool C"));

        assertEquals(3, registry.getToolCount());
    }

    @Test
    public void shouldReturnUnmodifiableList() {
        registry.registerTool(createToolSpec("test-tool", "A test tool"));
        List<McpServerFeatures.SyncToolSpecification> specs = registry.getToolSpecifications();

        try {
            specs.add(createToolSpec("another-tool", "Another tool"));
            fail("Expected UnsupportedOperationException for unmodifiable list");
        } catch (UnsupportedOperationException e) {
            // expected
        }
    }

    @Test(expected = NullPointerException.class)
    public void shouldRejectNullToolSpec() {
        registry.registerTool(null);
    }

    @Test
    public void shouldClearMcpServersWithoutError() {
        // Should not throw even if no servers are set
        registry.clearMcpServers();
    }

    @Test
    public void shouldSetAndClearMcpServers() {
        // Passing null is allowed for clearing
        registry.setMcpServers(null, null);
        registry.clearMcpServers();

        // Verify tools can still be registered after clear (no NPE from null servers)
        McpServerFeatures.SyncToolSpecification spec = createToolSpec("post-clear-tool", "Tool after clear");
        registry.registerTool(spec);
        assertEquals(1, registry.getToolCount());
        assertEquals("post-clear-tool", registry.getToolSpecifications().get(0).tool().name());
    }

    @Test
    public void shouldClearAllTools() {
        registry.registerTool(createToolSpec("tool-a", "Tool A"));
        registry.registerTool(createToolSpec("tool-b", "Tool B"));
        assertEquals(2, registry.getToolCount());

        registry.clearTools();

        assertEquals(0, registry.getToolCount());
        assertTrue(registry.getToolSpecifications().isEmpty());
    }

    @Test
    public void shouldAllowReRegistration_afterClearTools() {
        registry.registerTool(createToolSpec("tool-x", "Tool X"));
        registry.clearTools();

        registry.registerTool(createToolSpec("tool-y", "Tool Y"));

        assertEquals(1, registry.getToolCount());
        assertEquals("tool-y", registry.getToolSpecifications().get(0).tool().name());
    }

    // ---- Timing wrapper integration tests ----

    @Test
    @SuppressWarnings("unchecked")
    public void shouldWrapHandler_withTimingInjection() throws Exception {
        // Given a tool that returns a JSON envelope with _meta
        String responseJson = "{\"result\":{\"name\":\"test\"},\"_meta\":{\"modelVersion\":\"v1\",\"sessionActive\":true}}";
        McpServerFeatures.SyncToolSpecification spec = createToolSpecWithJson("timed-tool", "A timed tool", responseJson, false);
        registry.registerTool(spec);

        // When invoking the wrapped handler
        McpServerFeatures.SyncToolSpecification wrapped = registry.getToolSpecifications().get(0);
        McpSchema.CallToolResult result = wrapped.callHandler().apply(null, null);

        // Then durationMs should be injected into _meta
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> envelope = mapper.readValue(text, Map.class);
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertNotNull("_meta should exist", meta);
        assertTrue("durationMs should be present", meta.containsKey("durationMs"));
        long durationMs = ((Number) meta.get("durationMs")).longValue();
        assertTrue("durationMs should be >= 0", durationMs >= 0);
        assertEquals("modelVersion should be preserved", "v1", meta.get("modelVersion"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldWrapErrorHandler_withTimingInjection() throws Exception {
        // Given a tool that returns an error response
        String responseJson = "{\"error\":{\"code\":\"NOT_FOUND\"},\"_meta\":{\"sessionActive\":true}}";
        McpServerFeatures.SyncToolSpecification spec = createToolSpecWithJson("error-tool", "An error tool", responseJson, true);
        registry.registerTool(spec);

        // When invoking the wrapped handler
        McpServerFeatures.SyncToolSpecification wrapped = registry.getToolSpecifications().get(0);
        McpSchema.CallToolResult result = wrapped.callHandler().apply(null, null);

        // Then durationMs should still be injected
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> envelope = mapper.readValue(text, Map.class);
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertNotNull("_meta should exist", meta);
        assertTrue("durationMs should be present", meta.containsKey("durationMs"));
        assertTrue("isError should be preserved", result.isError());
    }

    // ---- injectDurationMs unit tests ----

    @Test
    @SuppressWarnings("unchecked")
    public void shouldInjectDurationMs_whenSuccessResponse() throws Exception {
        String json = "{\"result\":{\"name\":\"test\"},\"_meta\":{\"modelVersion\":\"v1\",\"sessionActive\":true}}";
        McpSchema.CallToolResult result = buildJsonResult(json, false);

        McpSchema.CallToolResult injected = CommandRegistry.injectDurationMs(result, 42);

        Map<String, Object> envelope = parseResultJson(injected);
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertNotNull("_meta should exist", meta);
        assertEquals("durationMs should be 42", 42, ((Number) meta.get("durationMs")).longValue());
        assertEquals("modelVersion preserved", "v1", meta.get("modelVersion"));
        assertTrue("sessionActive preserved", (Boolean) meta.get("sessionActive"));
        assertFalse("isError should be false", injected.isError());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldInjectDurationMs_whenErrorResponse() throws Exception {
        String json = "{\"error\":{\"code\":\"NOT_FOUND\"},\"_meta\":{\"sessionActive\":true}}";
        McpSchema.CallToolResult result = buildJsonResult(json, true);

        McpSchema.CallToolResult injected = CommandRegistry.injectDurationMs(result, 7);

        Map<String, Object> envelope = parseResultJson(injected);
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(7, ((Number) meta.get("durationMs")).longValue());
        assertTrue("isError preserved", injected.isError());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldCreateMeta_whenNoMetaKeyExists() throws Exception {
        String json = "{\"result\":{\"name\":\"test\"}}";
        McpSchema.CallToolResult result = buildJsonResult(json, false);

        McpSchema.CallToolResult injected = CommandRegistry.injectDurationMs(result, 100);

        Map<String, Object> envelope = parseResultJson(injected);
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertNotNull("_meta should be created", meta);
        assertEquals(100, ((Number) meta.get("durationMs")).longValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldReturnPlausibleDuration_nonNegative() throws Exception {
        String json = "{\"result\":{},\"_meta\":{}}";
        McpSchema.CallToolResult result = buildJsonResult(json, false);

        McpSchema.CallToolResult injected = CommandRegistry.injectDurationMs(result, 0);

        Map<String, Object> envelope = parseResultJson(injected);
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertTrue(((Number) meta.get("durationMs")).longValue() >= 0);
    }

    @Test
    public void shouldReturnOriginal_whenNullResult() {
        assertNull(CommandRegistry.injectDurationMs(null, 10));
    }

    @Test
    public void shouldReturnOriginal_whenEmptyContent() {
        McpSchema.CallToolResult result = McpSchema.CallToolResult.builder()
                .content(List.of())
                .isError(false)
                .build();
        assertSame(result, CommandRegistry.injectDurationMs(result, 10));
    }

    @Test
    public void shouldReturnOriginal_whenNonJsonContent() {
        McpSchema.CallToolResult result = buildJsonResult("not valid json", false);
        McpSchema.CallToolResult injected = CommandRegistry.injectDurationMs(result, 10);
        String text = ((McpSchema.TextContent) injected.content().get(0)).text();
        assertEquals("not valid json", text);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldPreserveAllEnvelopeFields() throws Exception {
        String json = "{\"result\":{\"id\":\"abc\"},\"nextSteps\":[\"do something\"],\"_meta\":{\"modelVersion\":\"v2\",\"resultCount\":1,\"totalCount\":1,\"isTruncated\":false,\"sessionActive\":true}}";
        McpSchema.CallToolResult result = buildJsonResult(json, false);

        McpSchema.CallToolResult injected = CommandRegistry.injectDurationMs(result, 55);

        Map<String, Object> envelope = parseResultJson(injected);
        assertNotNull(envelope.get("result"));
        assertNotNull(envelope.get("nextSteps"));
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals("v2", meta.get("modelVersion"));
        assertEquals(55, ((Number) meta.get("durationMs")).longValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldPreserveAdditionalContentItems() throws Exception {
        // Given a multi-content result (like export-view: TextContent + ImageContent)
        String metadataJson = "{\"result\":{\"format\":\"png\"},\"_meta\":{\"sessionActive\":true}}";
        McpSchema.TextContent textContent = new McpSchema.TextContent(metadataJson);
        McpSchema.ImageContent imageContent = new McpSchema.ImageContent(null, "base64data", "image/png");
        McpSchema.CallToolResult result = McpSchema.CallToolResult.builder()
                .content(List.of(textContent, imageContent))
                .isError(false)
                .build();

        // When injecting duration
        McpSchema.CallToolResult injected = CommandRegistry.injectDurationMs(result, 33);

        // Then both content items should be preserved
        assertEquals("should have 2 content items", 2, injected.content().size());

        // First item should have durationMs injected
        Map<String, Object> envelope = parseResultJson(injected);
        Map<String, Object> meta = (Map<String, Object>) envelope.get("_meta");
        assertEquals(33, ((Number) meta.get("durationMs")).longValue());

        // Second item (ImageContent) should be preserved unchanged
        assertTrue("second content should be ImageContent",
                injected.content().get(1) instanceof McpSchema.ImageContent);
        McpSchema.ImageContent preservedImage = (McpSchema.ImageContent) injected.content().get(1);
        assertEquals("base64data", preservedImage.data());
        assertEquals("image/png", preservedImage.mimeType());
    }

    // ---- Helpers ----

    private McpSchema.CallToolResult buildJsonResult(String json, boolean isError) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(json)))
                .isError(isError)
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseResultJson(McpSchema.CallToolResult result) throws Exception {
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        return new ObjectMapper().readValue(text, Map.class);
    }

    private McpServerFeatures.SyncToolSpecification createToolSpecWithJson(
            String name, String description, String responseJson, boolean isError) {
        McpSchema.JsonSchema emptySchema = new McpSchema.JsonSchema(
                "object", Collections.emptyMap(), null, null, null, null);
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(emptySchema)
                .build();

        BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler =
                (exchange, request) -> McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(responseJson)))
                        .isError(isError)
                        .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(handler)
                .build();
    }

    private McpServerFeatures.SyncToolSpecification createToolSpec(String name, String description) {
        McpSchema.JsonSchema emptySchema = new McpSchema.JsonSchema(
                "object", Collections.emptyMap(), null, null, null, null);
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(emptySchema)
                .build();

        BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler =
                (exchange, request) -> McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent("test")))
                        .isError(false)
                        .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(handler)
                .build();
    }
}
