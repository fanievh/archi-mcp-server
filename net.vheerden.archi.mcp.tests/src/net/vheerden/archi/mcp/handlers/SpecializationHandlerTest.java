package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.BaseTestAccessor;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;

/**
 * Tests for {@link SpecializationHandler} (Story C3c).
 *
 * <p>Uses {@link BaseTestAccessor} which returns canned DTOs from the
 * specialization mutation methods. Verifies tool registration, parameter
 * validation, and response envelope shape — actual model logic is covered
 * in {@code ArchiModelAccessorImplTest}.</p>
 */
public class SpecializationHandlerTest {

    private ObjectMapper objectMapper;
    private CommandRegistry registry;
    private SpecializationHandler handler;

    @Before
    public void setUp() {
        objectMapper = new ObjectMapper();
        registry = new CommandRegistry();
        ResponseFormatter formatter = new ResponseFormatter();
        BaseTestAccessor accessor = new BaseTestAccessor(true);
        handler = new SpecializationHandler(accessor, formatter, registry, null);
        handler.registerTools();
    }

    // ---- Tool registration ----

    @Test
    public void shouldRegisterFourTools() {
        assertEquals(4, registry.getToolSpecifications().size());
    }

    @Test
    public void shouldRegisterAllSpecializationToolNames() {
        List<String> names = registry.getToolSpecifications().stream()
                .map(s -> s.tool().name())
                .toList();
        assertTrue(names.contains("create-specialization"));
        assertTrue(names.contains("update-specialization"));
        assertTrue(names.contains("delete-specialization"));
        assertTrue(names.contains("get-specialization-usage"));
    }

    @Test
    public void shouldHaveMutationOrQueryPrefix_inDescriptions() {
        registry.getToolSpecifications().forEach(spec -> {
            String desc = spec.tool().description();
            String name = spec.tool().name();
            if ("get-specialization-usage".equals(name)) {
                assertTrue(name + " should be [Query]", desc.startsWith("[Query]"));
            } else {
                assertTrue(name + " should be [Mutation]", desc.startsWith("[Mutation]"));
            }
        });
    }

    @Test
    public void shouldCrossReferenceInlineSpecializationParam() {
        String createDesc = findTool("create-specialization").description();
        assertTrue("Should mention create-element inline param",
                createDesc.contains("create-element"));
    }

    // ---- Parameter validation ----

    @Test
    public void shouldRejectMissingName_onCreate() throws Exception {
        McpSchema.CallToolResult result = call("create-specialization",
                Map.of("conceptType", "Node"));
        assertTrue("Missing name should be an error", result.isError());
        Map<String, Object> parsed = parse(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @Test
    public void shouldRejectMissingConceptType_onCreate() throws Exception {
        McpSchema.CallToolResult result = call("create-specialization",
                Map.of("name", "Foo"));
        assertTrue(result.isError());
    }

    @Test
    public void shouldRejectMissingNewName_onUpdate() throws Exception {
        McpSchema.CallToolResult result = call("update-specialization",
                Map.of("name", "Foo", "conceptType", "Node"));
        assertTrue(result.isError());
    }

    @Test
    public void shouldRejectMissingName_onDelete() throws Exception {
        McpSchema.CallToolResult result = call("delete-specialization",
                Map.of("conceptType", "Node"));
        assertTrue(result.isError());
    }

    @Test
    public void shouldRejectMissingName_onGetUsage() throws Exception {
        McpSchema.CallToolResult result = call("get-specialization-usage",
                Map.of("conceptType", "Node"));
        assertTrue(result.isError());
    }

    // ---- Happy-path envelope shape ----

    @Test
    public void shouldReturnEnvelope_onCreate() throws Exception {
        McpSchema.CallToolResult result = call("create-specialization",
                Map.of("name", "Cloud Server", "conceptType", "Node"));
        assertEquals(false, result.isError());
        Map<String, Object> parsed = parse(result);
        assertNotNull("envelope should have a result", parsed.get("result"));
        assertNotNull("envelope should have nextSteps", parsed.get("nextSteps"));
    }

    @Test
    public void shouldReturnEnvelope_onGetUsage() throws Exception {
        McpSchema.CallToolResult result = call("get-specialization-usage",
                Map.of("name", "Cloud Server", "conceptType", "Node"));
        assertEquals(false, result.isError());
        Map<String, Object> parsed = parse(result);
        assertNotNull(parsed.get("result"));
    }

    // ---- helpers ----

    private McpSchema.Tool findTool(String name) {
        return registry.getToolSpecifications().stream()
                .map(s -> s.tool())
                .filter(t -> name.equals(t.name()))
                .findFirst()
                .orElseThrow();
    }

    private McpSchema.CallToolResult call(String toolName, Map<String, Object> args) {
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name(toolName)
                .arguments(args)
                .build();
        return switch (toolName) {
            case "create-specialization" -> handler.handleCreateSpecialization(null, request);
            case "update-specialization" -> handler.handleUpdateSpecialization(null, request);
            case "delete-specialization" -> handler.handleDeleteSpecialization(null, request);
            case "get-specialization-usage" -> handler.handleGetSpecializationUsage(null, request);
            default -> throw new IllegalArgumentException("Unknown tool: " + toolName);
        };
    }

    private Map<String, Object> parse(McpSchema.CallToolResult result) throws Exception {
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        return objectMapper.readValue(text, new TypeReference<Map<String, Object>>() {});
    }
}
