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
 * Tests for {@link DeletionHandler} delete-view tool.
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
    public void deleteView_descriptionShouldAdvertiseOnlyTheMeasuredCounts() {
        CommandRegistry reg = new CommandRegistry();
        DeletionHandler h = new DeletionHandler(accessor, formatter, reg, null);
        h.registerTools();
        String desc = reg.getToolSpecifications().stream()
                .filter(spec -> "delete-view".equals(spec.tool().name()))
                .findFirst().orElseThrow().tool().description();

        // POSITIVE half: these two are measured by prepareDeleteView, so an agent
        // must be able to discover them without pre-walking the view.
        for (String measured : new String[] {
                "viewConnectionsRemoved", "viewReferencesRemoved" }) {
            assertTrue("delete-view must advertise the measured count '" + measured + "'",
                    desc.contains(measured));
        }

        // NEGATIVE half: the remaining four are 0/absent because a view delete removes
        // no model concepts and no folders. That is the CORRECT answer, not an
        // unfinished one — but naming them would still tell an agent it can size a
        // cascade that does not exist. They must stay out of the description.
        // (foldersRemoved belongs in this list: DeleteResultDto has six count
        // components, and an earlier version of this pin guarded only five.)
        for (String notReported : new String[] {
                "relationshipsRemoved", "elementsRemoved",
                "viewsRemoved", "foldersRemoved" }) {
            assertFalse("delete-view must not advertise '" + notReported
                            + "' (always 0 or absent for a view delete)",
                    desc.contains(notReported));
        }
    }

    @Test
    public void shouldDeleteView() throws Exception {
        accessor.setDeleteViewBehavior((sessionId, viewId) -> {
            DeleteResultDto dto = new DeleteResultDto(
                    viewId, "My View", "ArchimateDiagramModel",
                    0, 0, 0, null, null, null);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        Map<String, Object> result = callAndParse(args);

        Map<String, Object> entity = getResult(result);
        assertEquals("view-1", entity.get("id"));
        assertEquals("My View", entity.get("name"));
        // Serialization only, like the counts pin below: the stub feeds this value, so it proves
        // the key reaches the caller intact, not that production computes it. The eClass spelling
        // is held to what prepareDeleteView really emits so the fixture cannot drift into
        // describing a system that no longer exists — DeleteViewCascadeCountTest drives the real
        // accessor and is the authority on the value itself.
        assertEquals("ArchimateDiagramModel", entity.get("type"));
    }

    /**
     * JSON-plumbing pin: the two measured counts must reach the caller intact and
     * keep their own identities. Distinct non-zero values, so a transposed pair
     * cannot pass. This asserts serialization only — that production actually
     * measures these is proven in {@code DeleteViewCascadeCountTest}, which drives
     * the real accessor instead of a stub.
     */
    @Test
    public void shouldSurfaceMeasuredCascadeCounts() throws Exception {
        accessor.setDeleteViewBehavior((sessionId, viewId) -> {
            DeleteResultDto dto = new DeleteResultDto(
                    viewId, "Test View", "View",
                    0, 3, 5, null, null, null);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        Map<String, Object> result = callAndParse(args);

        Map<String, Object> entity = getResult(result);
        assertEquals(3, entity.get("viewReferencesRemoved"));
        assertEquals(5, entity.get("viewConnectionsRemoved"));
    }

    /**
     * A view delete removes no model concepts and no folders, so relationshipsRemoved
     * stays 0 and the three folder-scoped counts stay absent from the JSON entirely
     * (DeleteResultDto is @JsonInclude(NON_NULL), and those three are boxed Integers).
     */
    @Test
    public void shouldNotDeleteModelElements_viewDeletionDoesNotCascade() throws Exception {
        accessor.setDeleteViewBehavior((sessionId, viewId) -> {
            DeleteResultDto dto = new DeleteResultDto(
                    viewId, "Test View", "View",
                    0, 3, 5, null, null, null);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("viewId", "view-1");
        Map<String, Object> result = callAndParse(args);

        Map<String, Object> entity = getResult(result);
        assertEquals(0, entity.get("relationshipsRemoved"));
        assertFalse("elementsRemoved must be absent, not 0",
                entity.containsKey("elementsRemoved"));
        assertFalse("viewsRemoved must be absent, not 0",
                entity.containsKey("viewsRemoved"));
        assertFalse("foldersRemoved must be absent, not 0",
                entity.containsKey("foldersRemoved"));
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
