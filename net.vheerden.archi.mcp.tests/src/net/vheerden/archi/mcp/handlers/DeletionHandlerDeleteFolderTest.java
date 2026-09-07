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
 * Tests for {@link DeletionHandler} delete-folder tool.
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
    public void deleteFolder_descriptionShouldExplainTheZerosAndGateOnForce() {
        CommandRegistry reg = new CommandRegistry();
        DeletionHandler h = new DeletionHandler(accessor, formatter, reg, null);
        h.registerTools();
        String desc = reg.getToolSpecifications().stream()
                .filter(spec -> "delete-folder".equals(spec.tool().name()))
                .findFirst().orElseThrow().tool().description();
        // The DTO passes `force ? x : null` for exactly three fields, so they vanish
        // from JSON without force — while the other three remain present reading 0.
        // That asymmetry is invisible from the DTO record alone.
        assertTrue("must name elementsRemoved", desc.contains("elementsRemoved"));
        assertTrue("must name foldersRemoved", desc.contains("foldersRemoved"));
        assertTrue("must state the gated fields are absent without force",
                desc.contains("absent entirely"));
        // Without this the clause reads as though force unlocks a measurement that
        // was withheld. The zeros are the answer, not a placeholder for one.
        assertTrue("must explain that the no-force zeros are real, not suppressed",
                desc.contains("genuine 0"));
        // "the other three read a genuine 0" is only true in the no-force case —
        // under force those same three fields carry real cascade totals. The scoping
        // phrase is what keeps the sentence from reading as a claim about every
        // state, so pin the scope, not just the claim.
        assertTrue("the zero claim must be scoped to the no-force case",
                desc.contains("in that case"));
        // The force-path counts now fold in a contained view's own connections and
        // placeholders, so they ARE a complete total. Pin the old lower-bound caveat
        // as GONE and pin the new completeness claim as present — deleting a clause
        // leaves every positive assertion green, so without the assertFalse the
        // correction would carry no test signal.
        assertFalse("the force counts are now a complete total, not a lower bound",
                desc.contains("lower bound"));
        assertTrue("must state the force counts are a complete total",
                desc.contains("complete total"));
        // The cascade's type test used to be written against IArchimateDiagramModel,
        // whose siblings ISketchModel and ICanvasModel it silently skipped — so the
        // description carried a second caveat naming them. The cascade now tests the
        // shared IDiagramModel supertype and claims all three. Pin the stale caveat as
        // GONE: deleting a sentence leaves every positive assertion here green, so
        // without this the correction would have no test signal at all.
        assertFalse("the sketch/canvas exclusion caveat is obsolete and must not remain",
                desc.contains("sketch and canvas views are not counted"));
        assertTrue("must state that every view kind is counted",
                desc.contains("including sketch and canvas views"));
        // On the deferred paths the emptiness check is re-run when the changes are applied,
        // and the delete declines rather than destroying content an earlier operation in the
        // same request added. An agent that is not told this reads a skipped delete as a
        // silent failure.
        assertTrue("must tell the agent the delete can be skipped on the deferred paths",
                desc.contains("skipped"));
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

        // The three primitive counts cannot be suppressed by NON_NULL, so they reach
        // the client reading 0; the three boxed ones are omitted entirely. Absence
        // and zero mean the same thing here, but they do not look the same on the
        // wire. (Values originate in the stub above — that these zeros are the truth
        // for an empty folder is proven against the real accessor in
        // net.vheerden.archi.mcp.model.DeleteFolderEmptyCountTest.)
        assertEquals(0, entity.get("relationshipsRemoved"));
        assertEquals(0, entity.get("viewReferencesRemoved"));
        assertEquals(0, entity.get("viewConnectionsRemoved"));
        assertFalse("elementsRemoved must be absent, not 0",
                entity.containsKey("elementsRemoved"));
        assertFalse("viewsRemoved must be absent, not 0",
                entity.containsKey("viewsRemoved"));
        assertFalse("foldersRemoved must be absent, not 0",
                entity.containsKey("foldersRemoved"));
    }

    @Test
    public void shouldPresentBoxedCountsAsZero_whenForcingAnEmptyFolder() throws Exception {
        accessor.setDeleteFolderBehavior((sessionId, folderId, force) -> {
            assertTrue("Force should be true", force);
            DeleteResultDto dto = new DeleteResultDto(
                    folderId, "Empty Folder", "Folder",
                    0, 0, 0, 0, 0, 0);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("folderId", "folder-1");
        args.put("force", true);
        Map<String, Object> result = callAndParse(args);

        Map<String, Object> entity = getResult(result);
        // Same empty folder as the test above, same underlying reality — but with
        // force the three boxed counts are present reading 0 instead of absent.
        // The flag changes the response shape, not what was removed.
        assertTrue("elementsRemoved must be present with force",
                entity.containsKey("elementsRemoved"));
        assertEquals(0, entity.get("elementsRemoved"));
        assertEquals(0, entity.get("viewsRemoved"));
        assertEquals(0, entity.get("foldersRemoved"));
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
