package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.handlers.FolderMutationHandlerCreateTest.StubFolderMutationAccessor;
import net.vheerden.archi.mcp.model.ModelAccessException;
import net.vheerden.archi.mcp.model.MutationResult;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.FolderDto;

/**
 * Tests for {@link FolderMutationHandler} update-folder tool (Story 8-5).
 */
public class FolderMutationHandlerUpdateTest {

    private ObjectMapper objectMapper;
    private ResponseFormatter formatter;
    private StubFolderMutationAccessor accessor;
    private FolderMutationHandler handler;

    @Before
    public void setUp() {
        objectMapper = new ObjectMapper();
        formatter = new ResponseFormatter();
        accessor = new StubFolderMutationAccessor();
        handler = new FolderMutationHandler(accessor, formatter, new CommandRegistry(), null);
        handler.registerTools();
    }

    @Test
    public void shouldRegisterUpdateFolderTool() {
        CommandRegistry registry = new CommandRegistry();
        FolderMutationHandler h = new FolderMutationHandler(
                accessor, formatter, registry, null);
        h.registerTools();
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "update-folder".equals(spec.tool().name()));
        assertTrue("update-folder tool should be registered", found);
    }

    @Test
    public void shouldUpdateFolderName() throws Exception {
        accessor.setUpdateFolderBehavior((sessionId, id, name, doc, props) -> {
            FolderDto dto = new FolderDto(
                    id, name, "USER", "Business/" + name, 3, 1);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("id", "folder-1");
        args.put("name", "Renamed Folder");
        Map<String, Object> result = callAndParse(args);

        Map<String, Object> entity = getResult(result);
        assertEquals("folder-1", entity.get("id"));
        assertEquals("Renamed Folder", entity.get("name"));
    }

    @Test
    public void shouldUpdateDocumentation() throws Exception {
        accessor.setUpdateFolderBehavior((sessionId, id, name, doc, props) -> {
            assertNotNull("Documentation should be passed", doc);
            assertEquals("New docs", doc);
            FolderDto dto = new FolderDto(
                    id, "Existing Name", "USER", "Business/Existing Name", 0, 0);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("id", "folder-1");
        args.put("documentation", "New docs");
        Map<String, Object> result = callAndParse(args);

        Map<String, Object> entity = getResult(result);
        assertEquals("Existing Name", entity.get("name"));
    }

    @Test
    public void shouldRemovePropertyWithNull() throws Exception {
        accessor.setUpdateFolderBehavior((sessionId, id, name, doc, props) -> {
            assertNotNull("Properties should be passed", props);
            assertTrue("Should contain key to remove", props.containsKey("removeMe"));
            assertNull("Value should be null for removal", props.get("removeMe"));
            FolderDto dto = new FolderDto(
                    id, "Test", "USER", "Business/Test", 0, 0);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("id", "folder-1");
        Map<String, Object> propsMap = new HashMap<>();
        propsMap.put("removeMe", null);
        args.put("properties", propsMap);

        callAndParse(args);
    }

    @Test
    public void shouldReturnNotFound_whenFolderMissing() throws Exception {
        accessor.setUpdateFolderBehavior((sessionId, id, name, doc, props) -> {
            throw new ModelAccessException("Folder not found: " + id,
                    ErrorCode.FOLDER_NOT_FOUND);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("id", "bad-id");
        args.put("name", "New Name");

        McpSchema.CallToolResult result = callTool(args);
        assertTrue("Should be an error", result.isError());

        Map<String, Object> parsed = parseResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("FOLDER_NOT_FOUND", error.get("code"));
    }

    @Test
    public void shouldRequireModelLoaded() throws Exception {
        StubFolderMutationAccessor noModel = new StubFolderMutationAccessor(false);
        FolderMutationHandler noModelHandler = new FolderMutationHandler(
                noModel, formatter, new CommandRegistry(), null);

        Map<String, Object> args = new HashMap<>();
        args.put("id", "folder-1");
        args.put("name", "Test");

        McpSchema.CallToolResult result = noModelHandler.handleUpdateFolder(null,
                McpSchema.CallToolRequest.builder().name("update-folder")
                        .arguments(args).build());

        assertTrue("Should be an error", result.isError());
        Map<String, Object> parsed = parseResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("MODEL_NOT_LOADED", error.get("code"));
    }

    // ---- Helpers ----

    private McpSchema.CallToolResult callTool(Map<String, Object> args) {
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name("update-folder")
                .arguments(args)
                .build();
        return handler.handleUpdateFolder(null, request);
    }

    private Map<String, Object> callAndParse(Map<String, Object> args) throws Exception {
        McpSchema.CallToolResult result = callTool(args);
        return parseResult(result);
    }

    private Map<String, Object> parseResult(McpSchema.CallToolResult result) throws Exception {
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        return objectMapper.readValue(content, new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getResult(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("result");
    }
}
