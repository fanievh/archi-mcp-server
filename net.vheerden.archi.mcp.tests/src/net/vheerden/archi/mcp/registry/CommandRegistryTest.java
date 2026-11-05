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
    public void shouldRejectDuplicateToolName_whenSameNameRegisteredTwice() {
        registry.registerTool(createToolSpec("dup-tool", "First registration"));

        try {
            registry.registerTool(createToolSpec("dup-tool", "Second registration"));
            fail("Expected the registry to reject a duplicate tool name");
        } catch (IllegalStateException e) {
            // expected — the no-duplicate-name invariant is enforced at the source
            assertTrue("Rejection message should name the offending tool",
                    e.getMessage() != null && e.getMessage().contains("dup-tool"));
        }

        // The rejected registration must leave registry state untouched.
        assertEquals("Duplicate must not be added", 1, registry.getToolCount());
        assertEquals("First registration must survive", "dup-tool",
                registry.getToolSpecifications().get(0).tool().name());
    }

    @Test
    public void shouldAllowSameNameAfterClearTools() {
        registry.registerTool(createToolSpec("recycled-name", "Before clear"));
        registry.clearTools();

        // After clearTools the name is no longer registered, so it is legal again.
        registry.registerTool(createToolSpec("recycled-name", "After clear"));

        assertEquals(1, registry.getToolCount());
        assertEquals("recycled-name", registry.getToolSpecifications().get(0).tool().name());
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

    // ---- call-exit hook ------------------------------------------------------------------------

    private McpServerFeatures.SyncToolSpecification registerAndGet(String name,
            BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler) {
        McpSchema.JsonSchema emptySchema = new McpSchema.JsonSchema(
                "object", Collections.emptyMap(), null, null, null, null);
        registry.registerTool(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder().name(name).description(name).inputSchema(emptySchema).build())
                .callHandler(handler)
                .build());
        return registry.getToolSpecifications().get(registry.getToolCount() - 1);
    }

    private static McpSchema.CallToolResult textResult(String text) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(text)))
                .isError(false)
                .build();
    }

    @Test
    public void shouldRunTheExitHookOnceAfterTheHandlerReturns_andKeepTheResult() {
        java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger runsSeenByHandler = new java.util.concurrent.atomic.AtomicInteger(-1);
        registry.setCallExitHook(runs::incrementAndGet);
        McpServerFeatures.SyncToolSpecification wrapped = registerAndGet("returns", (exchange, request) -> {
            runsSeenByHandler.set(runs.get());
            return textResult("handler text");
        });

        McpSchema.CallToolResult result = wrapped.callHandler().apply(null, null);

        assertEquals("the hook runs after the handler, not before", 0, runsSeenByHandler.get());
        assertEquals(1, runs.get());
        assertEquals("handler text", ((McpSchema.TextContent) result.content().get(0)).text());
    }

    @Test
    public void shouldRunTheExitHookAndPropagateTheHandlersException_whenTheHandlerThrows() {
        java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();
        registry.setCallExitHook(runs::incrementAndGet);
        IllegalStateException thrown = new IllegalStateException("handler failed");
        McpServerFeatures.SyncToolSpecification wrapped = registerAndGet("throws", (exchange, request) -> {
            throw thrown;
        });

        try {
            wrapped.callHandler().apply(null, null);
            fail("the handler's exception must propagate");
        } catch (IllegalStateException e) {
            assertSame(thrown, e);
        }
        assertEquals(1, runs.get());
    }

    @Test
    public void shouldKeepTheHandlersResult_whenTheExitHookThrows() {
        registry.setCallExitHook(() -> {
            throw new IllegalStateException("hook failed");
        });
        McpServerFeatures.SyncToolSpecification wrapped = registerAndGet("returns",
                (exchange, request) -> textResult("handler text"));

        McpSchema.CallToolResult result = wrapped.callHandler().apply(null, null);

        assertEquals("handler text", ((McpSchema.TextContent) result.content().get(0)).text());
    }

    @Test
    public void shouldKeepTheHandlersException_whenTheExitHookAlsoThrows() {
        registry.setCallExitHook(() -> {
            throw new IllegalArgumentException("hook failed");
        });
        IllegalStateException thrown = new IllegalStateException("handler failed");
        McpServerFeatures.SyncToolSpecification wrapped = registerAndGet("throws", (exchange, request) -> {
            throw thrown;
        });

        try {
            wrapped.callHandler().apply(null, null);
            fail("the handler's exception must propagate");
        } catch (IllegalStateException e) {
            assertSame(thrown, e);
        }
    }

    @Test
    public void shouldReturnTheHandlersResult_whenNoExitHookIsSet() {
        McpServerFeatures.SyncToolSpecification wrapped = registerAndGet("returns",
                (exchange, request) -> textResult("handler text"));

        McpSchema.CallToolResult result = wrapped.callHandler().apply(null, null);

        assertEquals("handler text", ((McpSchema.TextContent) result.content().get(0)).text());
    }

    @Test
    public void shouldNotRunTheExitHook_afterTheToolsAreCleared() {
        java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();
        registry.setCallExitHook(runs::incrementAndGet);
        McpServerFeatures.SyncToolSpecification wrapped = registerAndGet("returns",
                (exchange, request) -> textResult("handler text"));

        registry.clearTools();
        wrapped.callHandler().apply(null, null);   // a call still in flight across a stop

        assertEquals("a stopped server must not keep its dispatcher reachable through the hook", 0, runs.get());
    }

    // ---- admission -----------------------------------------------------------------------------

    @Test
    public void shouldAdmitEveryTool_whenNoAdmissionIsSet() {
        registry.setToolAdmission(null);
        registry.registerTool(createToolSpec("tool-a", "Tool A"));
        registry.registerTool(createToolSpec("tool-b", "Tool B"));

        assertEquals(2, registry.getToolCount());
        assertTrue(registry.getWithheldToolNames().isEmpty());
    }

    @Test
    public void shouldWithholdATool_whenTheAdmissionRefusesIt() {
        registry.setToolAdmission(tool -> !tool.name().startsWith("write-"));

        registry.registerTool(createToolSpec("read-a", "Read A"));
        registry.registerTool(createToolSpec("write-b", "Write B"));
        registry.registerTool(createToolSpec("read-c", "Read C"));

        assertEquals(2, registry.getToolCount());
        assertEquals("read-a", registry.getToolSpecifications().get(0).tool().name());
        assertEquals("read-c", registry.getToolSpecifications().get(1).tool().name());
        assertEquals(java.util.Set.of("write-b"), registry.getWithheldToolNames());
    }

    @Test
    public void shouldJudgeTheDecoratedTool_whenAdmitting() {
        registry.setToolDecorator(tool -> McpSchema.Tool.builder().name(tool.name()).description("decorated")
                .inputSchema(tool.inputSchema()).build());
        registry.setToolAdmission(tool -> "decorated".equals(tool.description()));

        registry.registerTool(createToolSpec("tool-a", "Tool A"));

        assertEquals(1, registry.getToolCount());
    }

    @Test
    public void shouldStillRejectADuplicateName_evenWhenTheFirstWasWithheld() {
        registry.setToolAdmission(tool -> false);
        registry.registerTool(createToolSpec("tool-a", "Tool A"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> registry.registerTool(createToolSpec("tool-a", "Tool A again")));

        assertTrue(e.getMessage(), e.getMessage().contains("tool-a"));
        assertEquals(0, registry.getToolCount());
        assertEquals(java.util.Set.of("tool-a"), registry.getWithheldToolNames());
    }

    @Test
    public void shouldForgetTheAdmissionAndTheWithheldNames_whenToolsAreCleared() {
        registry.setToolAdmission(tool -> false);
        registry.registerTool(createToolSpec("tool-a", "Tool A"));

        registry.clearTools();
        registry.registerTool(createToolSpec("tool-a", "Tool A"));

        assertEquals(1, registry.getToolCount());
        assertTrue(registry.getWithheldToolNames().isEmpty());
    }

    // ---- suggestions of tools the server withholds ---------------------------------------------

    private static final String SUGGESTING = "{\"result\":{},\"nextSteps\":["
            + "\"Use get-view to open it\","
            + "\"Use get-views to list views\","
            + "\"Then call get-element for details\","
            + "\"Or call (get-view) again\","
            + "\"Run create-relationship, then get-element\"],"
            + "\"_meta\":{}}";

    @SuppressWarnings("unchecked")
    private List<Object> nextStepsOf(McpSchema.CallToolResult result) throws Exception {
        return (List<Object>) parseResultJson(result).get("nextSteps");
    }

    @Test
    public void shouldDropOnlyTheSuggestionsThatNameAWithheldTool_inTheirOrder() throws Exception {
        registry.setToolAdmission(tool -> !java.util.Set.of("get-view", "create-relationship")
                .contains(tool.name()));
        registry.registerTool(createToolSpecWithJson("get-views", "lists", SUGGESTING, false));
        registry.registerTool(createToolSpec("get-view", "withheld"));
        registry.registerTool(createToolSpec("create-relationship", "withheld"));

        McpSchema.CallToolResult result = registry.getToolSpecifications().get(0).callHandler().apply(null, null);

        assertEquals(List.of("Use get-views to list views", "Then call get-element for details"),
                nextStepsOf(result));
        assertNotNull("the rest of the envelope is kept", parseResultJson(result).get("result"));
        assertNotNull(((Map<?, ?>) parseResultJson(result).get("_meta")).get("durationMs"));
    }

    @Test
    public void shouldKeepAnEmptySuggestionList_whenEverySuggestionNamesAWithheldTool() throws Exception {
        registry.setToolAdmission(tool -> !tool.name().equals("create-relationship"));
        registry.registerTool(createToolSpecWithJson("reader", "reads",
                "{\"result\":{},\"nextSteps\":[\"Run create-relationship\"]}", false));
        registry.registerTool(createToolSpec("create-relationship", "withheld"));

        McpSchema.CallToolResult result = registry.getToolSpecifications().get(0).callHandler().apply(null, null);

        assertEquals(List.of(), nextStepsOf(result));
    }

    @Test
    public void shouldLeaveTheSuggestionsOfAnErrorResponseUntouched() throws Exception {
        registry.setToolAdmission(tool -> !tool.name().equals("get-view"));
        registry.registerTool(createToolSpecWithJson("reader", "reads", SUGGESTING, true));
        registry.registerTool(createToolSpec("get-view", "withheld"));

        McpSchema.CallToolResult result = registry.getToolSpecifications().get(0).callHandler().apply(null, null);

        assertEquals(5, nextStepsOf(result).size());
    }

    @Test
    public void shouldLeaveEverySuggestion_whenNoToolIsWithheld() throws Exception {
        registry.registerTool(createToolSpecWithJson("reader", "reads", SUGGESTING, false));
        registry.registerTool(createToolSpec("get-view", "registered"));

        McpSchema.CallToolResult result = registry.getToolSpecifications().get(0).callHandler().apply(null, null);

        assertEquals(5, nextStepsOf(result).size());
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text, text.startsWith("{\"result\":{},\"nextSteps\":[\"Use get-view to open it\","));
    }

    @Test
    public void shouldPassANonEnvelopeResponseThroughUnchanged_whenAToolIsWithheld() {
        registry.setToolAdmission(tool -> !tool.name().equals("get-view"));
        McpSchema.ImageContent image = new McpSchema.ImageContent(null, "aGVsbG8=", "image/png");
        registry.registerTool(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder().name("exporter").description("x").inputSchema(
                        new McpSchema.JsonSchema("object", Collections.emptyMap(), null, null, null, null)).build())
                .callHandler((exchange, request) -> McpSchema.CallToolResult.builder()
                        .content(List.of(image)).isError(false).build())
                .build());
        registry.registerTool(createToolSpec("get-view", "withheld"));
        registry.registerTool(createToolSpecWithJson("plain", "x", "Use get-view now", false));

        McpSchema.CallToolResult imageResult = registry.getToolSpecifications().get(0).callHandler()
                .apply(null, null);
        McpSchema.CallToolResult plainResult = registry.getToolSpecifications().get(1).callHandler()
                .apply(null, null);

        assertSame(image, imageResult.content().get(0));
        assertEquals("Use get-view now", ((McpSchema.TextContent) plainResult.content().get(0)).text());
    }

    @Test
    public void shouldKeepASuggestionThatIsNotText() throws Exception {
        registry.setToolAdmission(tool -> !tool.name().equals("get-view"));
        registry.registerTool(createToolSpecWithJson("reader", "reads",
                "{\"result\":{},\"nextSteps\":[{\"tool\":\"get-view\"},\"Use get-view\"]}", false));
        registry.registerTool(createToolSpec("get-view", "withheld"));

        McpSchema.CallToolResult result = registry.getToolSpecifications().get(0).callHandler().apply(null, null);

        assertEquals(List.of(Map.of("tool", "get-view")), nextStepsOf(result));
    }
}
