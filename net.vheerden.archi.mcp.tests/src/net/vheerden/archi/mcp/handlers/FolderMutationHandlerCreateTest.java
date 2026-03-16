package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.BaseTestAccessor;
import net.vheerden.archi.mcp.model.ModelAccessException;
import net.vheerden.archi.mcp.model.MutationResult;
import net.vheerden.archi.mcp.model.ProposalContext;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.FolderDto;
import net.vheerden.archi.mcp.response.dto.MoveResultDto;

/**
 * Tests for {@link FolderMutationHandler} create-folder tool (Story 8-5).
 */
public class FolderMutationHandlerCreateTest {

    private ObjectMapper objectMapper;
    private CommandRegistry registry;
    private ResponseFormatter formatter;
    private StubFolderMutationAccessor accessor;
    private FolderMutationHandler handler;

    @Before
    public void setUp() {
        objectMapper = new ObjectMapper();
        registry = new CommandRegistry();
        formatter = new ResponseFormatter();
        accessor = new StubFolderMutationAccessor();
        handler = new FolderMutationHandler(accessor, formatter, registry, null);
        handler.registerTools();
    }

    // ---- Tool registration ----

    @Test
    public void shouldRegisterCreateFolderTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "create-folder".equals(spec.tool().name()));
        assertTrue("create-folder tool should be registered", found);
    }

    @Test
    public void shouldRegisterThreeTools() {
        assertEquals(3, registry.getToolSpecifications().size());
    }

    // ---- Success tests ----

    @Test
    public void shouldCreateSubfolder() throws Exception {
        accessor.setCreateFolderBehavior((sessionId, parentId, name, doc, props) -> {
            FolderDto dto = new FolderDto(
                    "new-folder-id", name, "USER",
                    "Business/" + name, 0, 0);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("parentId", "parent-1");
        args.put("name", "My Subfolder");
        Map<String, Object> result = callAndParse(args);

        Map<String, Object> entity = getResult(result);
        assertEquals("new-folder-id", entity.get("id"));
        assertEquals("My Subfolder", entity.get("name"));
        assertEquals("USER", entity.get("type"));
        assertEquals("Business/My Subfolder", entity.get("path"));
        assertEquals(0, entity.get("elementCount"));
        assertEquals(0, entity.get("subfolderCount"));
    }

    @Test
    public void shouldCreateFolderWithMetadata() throws Exception {
        accessor.setCreateFolderBehavior((sessionId, parentId, name, doc, props) -> {
            assertNotNull("Documentation should be passed", doc);
            assertNotNull("Properties should be passed", props);
            assertEquals("Test docs", doc);
            assertEquals("value1", props.get("key1"));
            FolderDto dto = new FolderDto(
                    "new-folder-id", name, "USER",
                    "Business/" + name, 0, 0);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("parentId", "parent-1");
        args.put("name", "Documented Folder");
        args.put("documentation", "Test docs");
        Map<String, Object> propsMap = new HashMap<>();
        propsMap.put("key1", "value1");
        args.put("properties", propsMap);

        Map<String, Object> result = callAndParse(args);
        Map<String, Object> entity = getResult(result);
        assertEquals("Documented Folder", entity.get("name"));
    }

    // ---- Error tests ----

    @Test
    public void shouldReturnNotFound_whenParentMissing() throws Exception {
        accessor.setCreateFolderBehavior((sessionId, parentId, name, doc, props) -> {
            throw new ModelAccessException("Parent folder not found: " + parentId,
                    ErrorCode.FOLDER_NOT_FOUND);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("parentId", "bad-parent");
        args.put("name", "Test");

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
        args.put("parentId", "parent-1");
        args.put("name", "Test");

        McpSchema.CallToolResult result = noModelHandler.handleCreateFolder(null,
                McpSchema.CallToolRequest.builder().name("create-folder")
                        .arguments(args).build());

        assertTrue("Should be an error", result.isError());
        Map<String, Object> parsed = parseResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("MODEL_NOT_LOADED", error.get("code"));
    }

    @Test
    public void shouldRespectApprovalMode() throws Exception {
        ProposalContext proposalCtx = new ProposalContext("p-99",
                "Create folder: My Folder", Instant.now());
        accessor.setCreateFolderBehavior((sessionId, parentId, name, doc, props) -> {
            FolderDto dto = new FolderDto(
                    "new-id", name, "USER", "Business/" + name, 0, 0);
            return new MutationResult<>(dto, null, proposalCtx);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("parentId", "parent-1");
        args.put("name", "My Folder");
        Map<String, Object> result = callAndParse(args);

        Map<String, Object> entity = getResult(result);
        assertNotNull("Should have proposal", entity.get("proposal"));

        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) entity.get("proposal");
        assertEquals("p-99", proposal.get("proposalId"));
    }

    @Test
    public void shouldRespectBatchMode() throws Exception {
        accessor.setBatchMode(true);

        Map<String, Object> args = new HashMap<>();
        args.put("parentId", "parent-1");
        args.put("name", "Batched Folder");
        Map<String, Object> result = callAndParse(args);

        Map<String, Object> entity = getResult(result);
        assertNotNull("Should have batch info", entity.get("batch"));
    }

    // ---- Helpers ----

    private McpSchema.CallToolResult callTool(Map<String, Object> args) {
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name("create-folder")
                .arguments(args)
                .build();
        return handler.handleCreateFolder(null, request);
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

    // ---- Stub accessor ----

    @FunctionalInterface
    interface CreateFolderBehavior {
        MutationResult<FolderDto> apply(String sessionId, String parentId, String name,
                String documentation, Map<String, String> properties);
    }

    @FunctionalInterface
    interface UpdateFolderBehavior {
        MutationResult<FolderDto> apply(String sessionId, String id, String name,
                String documentation, Map<String, String> properties);
    }

    @FunctionalInterface
    interface MoveToFolderBehavior {
        MutationResult<MoveResultDto> apply(String sessionId,
                String objectId, String targetFolderId);
    }

    static class StubFolderMutationAccessor extends BaseTestAccessor {

        private boolean batchMode = false;
        private CreateFolderBehavior createFolderBehavior;
        private UpdateFolderBehavior updateFolderBehavior;
        private MoveToFolderBehavior moveToFolderBehavior;

        StubFolderMutationAccessor() {
            super(true);
            resetBehaviors();
        }

        StubFolderMutationAccessor(boolean modelLoaded) {
            super(modelLoaded);
            resetBehaviors();
        }

        void setBatchMode(boolean batch) {
            this.batchMode = batch;
        }

        void setCreateFolderBehavior(CreateFolderBehavior behavior) {
            this.createFolderBehavior = behavior;
        }

        void setUpdateFolderBehavior(UpdateFolderBehavior behavior) {
            this.updateFolderBehavior = behavior;
        }

        void setMoveToFolderBehavior(MoveToFolderBehavior behavior) {
            this.moveToFolderBehavior = behavior;
        }

        private void resetBehaviors() {
            this.createFolderBehavior = (sessionId, parentId, name, doc, props) -> {
                FolderDto dto = new FolderDto(
                        "stub-folder-id", name, "USER", "Stub/" + name, 0, 0);
                return new MutationResult<>(dto, batchMode ? 1 : null);
            };
            this.updateFolderBehavior = (sessionId, id, name, doc, props) -> {
                String effectiveName = name != null ? name : "Stub Folder";
                FolderDto dto = new FolderDto(
                        id, effectiveName, "USER", "Stub/" + effectiveName, 0, 0);
                return new MutationResult<>(dto, batchMode ? 1 : null);
            };
            this.moveToFolderBehavior = (sessionId, objectId, targetFolderId) -> {
                MoveResultDto dto = new MoveResultDto(
                        objectId, "Stub Object", "Element", "BusinessActor",
                        "Source/Path", "Target/Path");
                return new MutationResult<>(dto, batchMode ? 1 : null);
            };
        }

        @Override
        public MutationResult<FolderDto> createFolder(String sessionId, String parentId,
                String name, String documentation, Map<String, String> properties) {
            return createFolderBehavior.apply(sessionId, parentId, name, documentation, properties);
        }

        @Override
        public MutationResult<FolderDto> updateFolder(String sessionId, String id,
                String name, String documentation, Map<String, String> properties) {
            return updateFolderBehavior.apply(sessionId, id, name, documentation, properties);
        }

        @Override
        public MutationResult<MoveResultDto> moveToFolder(String sessionId,
                String objectId, String targetFolderId) {
            return moveToFolderBehavior.apply(sessionId, objectId, targetFolderId);
        }
    }
}
