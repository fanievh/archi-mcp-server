package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.handlers.DeletionHandlerDeleteElementTest.StubDeleteAccessor;
import net.vheerden.archi.mcp.model.ModelAccessException;
import net.vheerden.archi.mcp.model.MutationResult;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.DeleteResultDto;

/**
 * Tests for {@link DeletionHandler} delete-folder tool (Story 8-4).
 */
public class DeletionHandlerDeleteFolderTest {

    private ObjectMapper objectMapper;
    private ResponseFormatter formatter;
    private StubDeleteAccessor accessor;
    private DeletionHandler handler;

    @Before
    public void setUp() {
        objectMapper = new ObjectMapper();
        formatter = new ResponseFormatter();
        accessor = new StubDeleteAccessor();
        handler = new DeletionHandler(accessor, formatter, new CommandRegistry(), null);
        handler.registerTools();
    }

    @Test
    public void shouldDeleteEmptyFolder() throws Exception {
        accessor.setDeleteFolderBehavior((sessionId, folderId, force) -> {
            DeleteResultDto dto = new DeleteResultDto(
                    folderId, "Empty Folder", "Folder",
                    0, 0, 0, null, null, null);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("folderId", "folder-1");
        Map<String, Object> result = callAndParse(args);

        Map<String, Object> entity = getResult(result);
        assertEquals("folder-1", entity.get("id"));
        assertEquals("Empty Folder", entity.get("name"));
        assertEquals("Folder", entity.get("type"));
    }

    @Test
    public void shouldRejectNonEmptyFolder_whenForceNotSet() throws Exception {
        accessor.setDeleteFolderBehavior((sessionId, folderId, force) -> {
            throw new ModelAccessException(
                    "Folder is not empty: 3 elements, 2 relationships, 1 views, 0 subfolders. "
                    + "Use force: true to cascade-delete all contents.",
                    ErrorCode.FOLDER_NOT_EMPTY);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("folderId", "folder-2");

        McpSchema.CallToolResult result = callTool(args);
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("FOLDER_NOT_EMPTY", error.get("code"));
        assertTrue("Message should mention force",
                error.get("message").toString().contains("force"));
    }

    @Test
    public void shouldForceDeleteNonEmptyFolder() throws Exception {
        accessor.setDeleteFolderBehavior((sessionId, folderId, force) -> {
            assertTrue("Force should be true", force);
            DeleteResultDto dto = new DeleteResultDto(
                    folderId, "Non-Empty Folder", "Folder",
                    5, 3, 2, 4, 1, 2);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("folderId", "folder-3");
        args.put("force", true);
        Map<String, Object> result = callAndParse(args);

        Map<String, Object> entity = getResult(result);
        assertEquals("folder-3", entity.get("id"));
        assertEquals(4, entity.get("elementsRemoved"));
        assertEquals(1, entity.get("viewsRemoved"));
        assertEquals(2, entity.get("foldersRemoved"));
    }

    @Test
    public void shouldRejectDefaultFolderDeletion() throws Exception {
        accessor.setDeleteFolderBehavior((sessionId, folderId, force) -> {
            throw new ModelAccessException(
                    "Cannot delete default ArchiMate folder 'Business'",
                    ErrorCode.CANNOT_DELETE_DEFAULT_FOLDER);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("folderId", "default-folder-id");

        McpSchema.CallToolResult result = callTool(args);
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("CANNOT_DELETE_DEFAULT_FOLDER", error.get("code"));
    }

    @Test
    public void shouldReturnNotFound_whenFolderMissing() throws Exception {
        accessor.setDeleteFolderBehavior((sessionId, folderId, force) -> {
            throw new ModelAccessException("Folder not found: " + folderId,
                    ErrorCode.FOLDER_NOT_FOUND);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("folderId", "bad-id");

        McpSchema.CallToolResult result = callTool(args);
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("FOLDER_NOT_FOUND", error.get("code"));
    }

    @Test
    public void shouldReturnModelNotLoaded_whenNoModel() throws Exception {
        StubDeleteAccessor noModel = new StubDeleteAccessor(false);
        DeletionHandler noModelHandler = new DeletionHandler(
                noModel, formatter, new CommandRegistry(), null);

        Map<String, Object> args = new HashMap<>();
        args.put("folderId", "folder-1");

        McpSchema.CallToolResult result = noModelHandler.handleDeleteFolder(null,
                McpSchema.CallToolRequest.builder().name("delete-folder")
                        .arguments(args).build());
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("MODEL_NOT_LOADED", error.get("code"));
    }

    @Test
    public void shouldIncludeCascadeCountsInForceDelete() throws Exception {
        accessor.setDeleteFolderBehavior((sessionId, folderId, force) -> {
            DeleteResultDto dto = new DeleteResultDto(
                    folderId, "Deep Folder", "Folder",
                    10, 5, 8, 7, 3, 4);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("folderId", "folder-deep");
        args.put("force", true);
        Map<String, Object> result = callAndParse(args);

        Map<String, Object> entity = getResult(result);
        assertEquals(10, entity.get("relationshipsRemoved"));
        assertEquals(5, entity.get("viewReferencesRemoved"));
        assertEquals(8, entity.get("viewConnectionsRemoved"));
        assertEquals(7, entity.get("elementsRemoved"));
        assertEquals(3, entity.get("viewsRemoved"));
        assertEquals(4, entity.get("foldersRemoved"));
    }

    // ---- Helpers ----

    private McpSchema.CallToolResult callTool(Map<String, Object> args) {
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name("delete-folder")
                .arguments(args)
                .build();
        return handler.handleDeleteFolder(null, request);
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
