package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.*;

import java.util.HashMap;
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
 * Tests for {@link DeletionHandler} delete-view tool (Story 8-4).
 */
public class DeletionHandlerDeleteViewTest {

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
    public void shouldDeleteView() throws Exception {
        accessor.setDeleteViewBehavior((sessionId, viewId) -> {
            DeleteResultDto dto = new DeleteResultDto(
                    viewId, "My View", "View",
                    0, 0, 0, null, null, null);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        Map<String, Object> result = callAndParse(args);

        Map<String, Object> entity = getResult(result);
        assertEquals("view-1", entity.get("id"));
        assertEquals("My View", entity.get("name"));
        assertEquals("View", entity.get("type"));
    }

    @Test
    public void shouldNotDeleteModelElements_viewDeletionDoesNotCascade() throws Exception {
        accessor.setDeleteViewBehavior((sessionId, viewId) -> {
            // View deletion returns 0 for element/relationship cascades
            DeleteResultDto dto = new DeleteResultDto(
                    viewId, "Test View", "View",
                    0, 0, 0, null, null, null);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        Map<String, Object> result = callAndParse(args);

        Map<String, Object> entity = getResult(result);
        assertEquals(0, entity.get("relationshipsRemoved"));
        assertEquals(0, entity.get("viewReferencesRemoved"));
    }

    @Test
    public void shouldReturnNotFound_whenViewMissing() throws Exception {
        accessor.setDeleteViewBehavior((sessionId, viewId) -> {
            throw new ModelAccessException("View not found: " + viewId,
                    ErrorCode.VIEW_NOT_FOUND);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "bad-id");

        McpSchema.CallToolResult result = callTool(args);
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("VIEW_NOT_FOUND", error.get("code"));
    }

    @Test
    public void shouldReturnModelNotLoaded_whenNoModel() throws Exception {
        StubDeleteAccessor noModel = new StubDeleteAccessor(false);
        DeletionHandler noModelHandler = new DeletionHandler(
                noModel, formatter, new CommandRegistry(), null);

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");

        McpSchema.CallToolResult result = noModelHandler.handleDeleteView(null,
                McpSchema.CallToolRequest.builder().name("delete-view")
                        .arguments(args).build());
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("MODEL_NOT_LOADED", error.get("code"));
    }

    // ---- Helpers ----

    private McpSchema.CallToolResult callTool(Map<String, Object> args) {
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name("delete-view")
                .arguments(args)
                .build();
        return handler.handleDeleteView(null, request);
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
