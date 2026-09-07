package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
 * Tests for {@link SpecializationHandler}.
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
    public void shouldRejectMissingConceptType_onUpdate() throws Exception {
        // newName is now OPTIONAL; the "at least one of newName/imagePath/clearImagePath"
        // guard moved to ArchiModelAccessorImpl.prepareUpdateSpecialization (INVALID_PARAMETER
        // "No fields to update …") and is deliberately NOT modelled by BaseTestAccessor (see its
        // stub-limitation note), so it cannot be exercised through this handler stub. This
        // test instead pins the handler-level required-param validation that DOES live here:
        // conceptType is required (requireStringParam) and its absence is rejected.
        McpSchema.CallToolResult result = call("update-specialization",
                Map.of("name", "Foo", "newName", "Bar"));
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

    // ---- AXIS B — imagePath schema introspection ----

    @Test
    public void shouldAdvertiseImagePathOnCreateSpecialization() {
        McpSchema.Tool tool = findTool("create-specialization");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>)
                tool.inputSchema().properties();
        assertTrue("create-specialization schema should advertise imagePath",
                properties.containsKey("imagePath"));
        // Required list stays at name + conceptType (imagePath optional).
        List<String> required = tool.inputSchema().required();
        assertTrue(required.contains("name"));
        assertTrue(required.contains("conceptType"));
        assertEquals("required size unchanged at 2 (imagePath is optional)",
                2, required.size());
    }

    @Test
    public void shouldAdvertiseImagePathAndClearImagePathOnUpdate() {
        McpSchema.Tool tool = findTool("update-specialization");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>)
                tool.inputSchema().properties();
        assertTrue("update-specialization schema should advertise imagePath",
                properties.containsKey("imagePath"));
        assertTrue("update-specialization schema should advertise clearImagePath",
                properties.containsKey("clearImagePath"));
        // Required list relaxed from [name, conceptType, newName] → [name, conceptType].
        List<String> required = tool.inputSchema().required();
        assertTrue(required.contains("name"));
        assertTrue(required.contains("conceptType"));
        assertEquals("newName relaxed to optional (at-least-one-of guard)",
                2, required.size());
    }

    @Test
    public void shouldDescribeImagePathClearSemantic_inDescriptions() {
        String createDesc = findTool("create-specialization").description();
        String updateDesc = findTool("update-specialization").description();
        assertTrue("create-spec description should reference imagePath",
                createDesc.contains("imagePath"));
        assertTrue("update-spec description should reference imagePath",
                updateDesc.contains("imagePath"));
        assertTrue("update-spec description should reference clearImagePath",
                updateDesc.contains("clearImagePath"));
        assertTrue("update-spec description should call out mutex",
                updateDesc.contains("Mutually exclusive")
                        || updateDesc.contains("mutually exclusive"));
    }

    @Test
    public void shouldPassImagePathThroughHandler_OnCreate() throws Exception {
        McpSchema.CallToolResult result = call("create-specialization",
                Map.of("name", "Cloud Server", "conceptType", "Node",
                        "imagePath", "images/cloud.png"));
        assertEquals(false, result.isError());
        Map<String, Object> parsed = parse(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> resultMap = (Map<String, Object>) parsed.get("result");
        assertEquals("Stub BaseTestAccessor should echo imagePath",
                "images/cloud.png", resultMap.get("imagePath"));
    }

    @Test
    public void shouldPassImagePathThroughHandler_OnUpdate() throws Exception {
        McpSchema.CallToolResult result = call("update-specialization",
                Map.of("name", "Cloud Server", "conceptType", "Node",
                        "imagePath", "images/new.png"));
        assertEquals(false, result.isError());
        Map<String, Object> parsed = parse(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> resultMap = (Map<String, Object>) parsed.get("result");
        assertEquals("Update should echo new imagePath",
                "images/new.png", resultMap.get("imagePath"));
    }

    @Test
    public void shouldAllowUpdateWithOnlyImagePath() throws Exception {
        // AXIS B relaxation: newName no longer required when imagePath supplied.
        // Handler boundary parses newName as optional — accessor enforces the
        // at-least-one-of guard. With imagePath supplied, this should succeed.
        McpSchema.CallToolResult result = call("update-specialization",
                Map.of("name", "Cloud Server", "conceptType", "Node",
                        "imagePath", "images/x.png"));
        assertEquals("update with only imagePath should succeed (no newName)",
                false, result.isError());
    }

    @Test
    public void shouldRegisterFourTools_unchanged() {
        // AXIS B extends EXISTING tool schemas, does not add new tools.
        // Tool count STAYS at 4 (create / update / delete / get-usage).
        assertEquals(4, registry.getToolSpecifications().size());
    }

    // ---- helpers ----

    @Test
    public void updateSpecialization_descriptionShouldDocumentResponseFields() {
        String desc = findTool("update-specialization").description();
        // NOTE: `conceptType` alone is NOT a valid pin — it is already a required
        // request parameter in this description. Pin the response sentence instead.
        assertTrue("must state the returned name is the post-update one",
                desc.contains("post-update name"));
        // buildProfileMap omits imagePath when null, so its ABSENCE is the confirmation
        // that clearImagePath took effect.
        assertTrue("must explain that absence of imagePath confirms removal",
                desc.contains("confirms removal"));
    }

    @Test
    public void deleteSpecialization_descriptionShouldLocateUsageCountOnTheErrorPath() {
        String desc = findTool("delete-specialization").description();
        assertTrue("must name clearedFromConcepts", desc.contains("clearedFromConcepts"));
        // The pre-existing description already promised usageCount, but that value is
        // thrown as ModelAccessException details on the refusal path — it is NOT a
        // success-result field. The success analogue is clearedFromConcepts.
        assertTrue("must state usageCount is not in the success result",
                desc.contains("NOT in that"));
        assertTrue("must locate usageCount on the error path",
                desc.contains("error details"));
    }

    /**
     * The archive path is minted by Archi as {@code images/} + a generated identifier + the source
     * file's extension, lower-cased ({@code ArchiveManager.createArchiveImagePathname}). It is not a
     * content hash and its extension is not always {@code .png}, so the previously published
     * {@code 'images/<sha1>.png'} shape matched nothing the server ever returns — while the same
     * sentence named the {@code IMAGE_NOT_FOUND} rejection that follows a path a caller built itself.
     */
    @Test
    public void shouldDescribeImagePathAsOpaque_notAsAConstructableShape() {
        for (String toolName : List.of("create-specialization", "update-specialization")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>)
                    findTool(toolName).inputSchema().properties();
            @SuppressWarnings("unchecked")
            Map<String, Object> imagePathProp = (Map<String, Object>) properties.get("imagePath");
            String desc = (String) imagePathProp.get("description");
            assertFalse(toolName + " must not describe the path as a content hash",
                    desc.contains("sha1"));
            assertFalse(toolName + " must not publish a constructable path format",
                    desc.contains("images/<"));
            assertTrue(toolName + " must say the value is opaque", desc.contains("opaque"));
            assertTrue(toolName + " must tell the caller to pass it back unmodified",
                    desc.contains("never construct or parse one"));
        }
    }

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
