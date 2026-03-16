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
import net.vheerden.archi.mcp.response.dto.MoveResultDto;

/**
 * Tests for {@link FolderMutationHandler} move-to-folder tool (Story 8-5).
 */
public class FolderMutationHandlerMoveTest {

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
    public void shouldRegisterMoveToFolderTool() {
        CommandRegistry registry = new CommandRegistry();
        FolderMutationHandler h = new FolderMutationHandler(
                accessor, formatter, registry, null);
        h.registerTools();
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "move-to-folder".equals(spec.tool().name()));
        assertTrue("move-to-folder tool should be registered", found);
    }

    @Test
    public void shouldMoveElementToFolder() throws Exception {
        accessor.setMoveToFolderBehavior((sessionId, objectId, targetFolderId) -> {
            MoveResultDto dto = new MoveResultDto(
                    objectId, "My Process", "Element", "BusinessProcess",
                    "Business/Active", "Business/Archived");
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("objectId", "elem-1");
        args.put("targetFolderId", "folder-archived");
        Map<String, Object> result = callAndParse(args);

        Map<String, Object> entity = getResult(result);
        assertEquals("elem-1", entity.get("id"));
        assertEquals("My Process", entity.get("name"));
        assertEquals("Element", entity.get("objectType"));
        assertEquals("BusinessProcess", entity.get("elementType"));
        assertEquals("Business/Active", entity.get("sourceFolderPath"));
        assertEquals("Business/Archived", entity.get("targetFolderPath"));
    }

    @Test
    public void shouldMoveFolderToNewParent() throws Exception {
        accessor.setMoveToFolderBehavior((sessionId, objectId, targetFolderId) -> {
            MoveResultDto dto = new MoveResultDto(
                    objectId, "Subfolder", "Folder", null,
                    "Business/Old", "Application");
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("objectId", "folder-sub");
        args.put("targetFolderId", "folder-app");
        Map<String, Object> result = callAndParse(args);

        Map<String, Object> entity = getResult(result);
        assertEquals("Folder", entity.get("objectType"));
        assertNull("elementType should be null for folders", entity.get("elementType"));
    }

    @Test
    public void shouldRejectCircularMove() throws Exception {
        accessor.setMoveToFolderBehavior((sessionId, objectId, targetFolderId) -> {
            throw new ModelAccessException(
                    "Cannot move folder into its own subtree",
                    ErrorCode.CIRCULAR_FOLDER_REFERENCE);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("objectId", "folder-parent");
        args.put("targetFolderId", "folder-child");

        McpSchema.CallToolResult result = callTool(args);
        assertTrue("Should be an error", result.isError());

        Map<String, Object> parsed = parseResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("CIRCULAR_FOLDER_REFERENCE", error.get("code"));
    }

    @Test
    public void shouldRejectDefaultFolderMove() throws Exception {
        accessor.setMoveToFolderBehavior((sessionId, objectId, targetFolderId) -> {
            throw new ModelAccessException(
                    "Cannot move default ArchiMate folder: Business",
                    ErrorCode.CANNOT_MOVE_DEFAULT_FOLDER);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("objectId", "business-folder-id");
        args.put("targetFolderId", "some-folder");

        McpSchema.CallToolResult result = callTool(args);
        assertTrue("Should be an error", result.isError());

        Map<String, Object> parsed = parseResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("CANNOT_MOVE_DEFAULT_FOLDER", error.get("code"));
    }

    @Test
    public void shouldRejectAlreadyInTarget() throws Exception {
        accessor.setMoveToFolderBehavior((sessionId, objectId, targetFolderId) -> {
            throw new ModelAccessException(
                    "Object is already in the target folder",
                    ErrorCode.ALREADY_IN_TARGET_FOLDER);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("objectId", "elem-1");
        args.put("targetFolderId", "same-folder");

        McpSchema.CallToolResult result = callTool(args);
        assertTrue("Should be an error", result.isError());

        Map<String, Object> parsed = parseResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("ALREADY_IN_TARGET_FOLDER", error.get("code"));
    }

    @Test
    public void shouldReturnNotFound_whenObjectMissing() throws Exception {
        accessor.setMoveToFolderBehavior((sessionId, objectId, targetFolderId) -> {
            throw new ModelAccessException(
                    "Object not found: " + objectId,
                    ErrorCode.OBJECT_NOT_FOUND);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("objectId", "bad-id");
        args.put("targetFolderId", "folder-1");

        McpSchema.CallToolResult result = callTool(args);
        assertTrue("Should be an error", result.isError());

        Map<String, Object> parsed = parseResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("OBJECT_NOT_FOUND", error.get("code"));
    }

    @Test
    public void shouldReturnNotFound_whenTargetFolderMissing() throws Exception {
        accessor.setMoveToFolderBehavior((sessionId, objectId, targetFolderId) -> {
            throw new ModelAccessException(
                    "Target folder not found: " + targetFolderId,
                    ErrorCode.FOLDER_NOT_FOUND);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("objectId", "elem-1");
        args.put("targetFolderId", "bad-folder");

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
        args.put("objectId", "elem-1");
        args.put("targetFolderId", "folder-1");

        McpSchema.CallToolResult result = noModelHandler.handleMoveToFolder(null,
                McpSchema.CallToolRequest.builder().name("move-to-folder")
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
                .name("move-to-folder")
                .arguments(args)
                .build();
        return handler.handleMoveToFolder(null, request);
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
