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
import net.vheerden.archi.mcp.model.MutationDispatcher;
import net.vheerden.archi.mcp.model.MutationResult;
import net.vheerden.archi.mcp.model.ProposalContext;
import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.DeleteResultDto;

import org.eclipse.gef.commands.Command;

/**
 * Tests for {@link DeletionHandler} delete-element tool (Story 8-4).
 */
public class DeletionHandlerDeleteElementTest {

    private ObjectMapper objectMapper;
    private CommandRegistry registry;
    private ResponseFormatter formatter;
    private StubDeleteAccessor accessor;
    private DeletionHandler handler;

    @Before
    public void setUp() {
        objectMapper = new ObjectMapper();
        registry = new CommandRegistry();
        formatter = new ResponseFormatter();
        accessor = new StubDeleteAccessor();
        handler = new DeletionHandler(accessor, formatter, registry, null);
        handler.registerTools();
    }

    // ---- Tool registration ----

    @Test
    public void shouldRegisterFourTools() {
        assertEquals(4, registry.getToolSpecifications().size());
    }

    @Test
    public void shouldRegisterDeleteElementTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "delete-element".equals(spec.tool().name()));
        assertTrue("delete-element tool should be registered", found);
    }

    @Test
    public void shouldHaveMutationPrefix_inDescription() {
        registry.getToolSpecifications().stream()
                .filter(spec -> "delete-element".equals(spec.tool().name()))
                .forEach(spec -> assertTrue(
                        "Description should start with [Mutation]",
                        spec.tool().description().startsWith("[Mutation]")));
    }

    // ---- delete-element success tests ----

    @Test
    public void shouldDeleteElement_withCascadeCounts() throws Exception {
        accessor.setDeleteElementBehavior((sessionId, elementId) -> {
            DeleteResultDto dto = new DeleteResultDto(
                    elementId, "My Element", "BusinessActor",
                    2, 1, 0, null, null, null);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("elementId", "elem-1");
        Map<String, Object> result = callAndParse("delete-element", args);

        Map<String, Object> entity = getResult(result);
        assertEquals("elem-1", entity.get("id"));
        assertEquals("My Element", entity.get("name"));
        assertEquals("BusinessActor", entity.get("type"));
        assertEquals(2, entity.get("relationshipsRemoved"));
        assertEquals(1, entity.get("viewReferencesRemoved"));
    }

    @Test
    public void shouldReturnNextSteps_afterDeletion() throws Exception {
        accessor.setDeleteElementBehavior((sessionId, elementId) -> {
            DeleteResultDto dto = new DeleteResultDto(
                    elementId, "Test", "BusinessActor",
                    0, 0, 0, null, null, null);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("elementId", "elem-1");
        Map<String, Object> result = callAndParse("delete-element", args);

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertFalse(nextSteps.isEmpty());
    }

    @Test
    public void shouldReturnEnvelopeWithMeta() throws Exception {
        accessor.setDeleteElementBehavior((sessionId, elementId) -> {
            DeleteResultDto dto = new DeleteResultDto(
                    elementId, "Test", "BusinessActor",
                    0, 0, 0, null, null, null);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("elementId", "elem-1");
        Map<String, Object> result = callAndParse("delete-element", args);

        assertNotNull("result key should exist", result.get("result"));
        assertNotNull("nextSteps key should exist", result.get("nextSteps"));
        assertNotNull("_meta key should exist", result.get("_meta"));
    }

    // ---- Error handling ----

    @Test
    public void shouldReturnNotFound_whenElementMissing() throws Exception {
        accessor.setDeleteElementBehavior((sessionId, elementId) -> {
            throw new ModelAccessException("Element not found: " + elementId,
                    ErrorCode.ELEMENT_NOT_FOUND);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("elementId", "bad-id");

        McpSchema.CallToolResult result = callTool("delete-element", args);
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("ELEMENT_NOT_FOUND", error.get("code"));
    }

    @Test
    public void shouldReturnInvalidParameter_whenElementIdMissing() throws Exception {
        Map<String, Object> args = new HashMap<>();
        // no elementId

        McpSchema.CallToolResult result = callTool("delete-element", args);
        assertTrue("Should be an error", result.isError());

        Map<String, Object> parsed = parseResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @Test
    public void shouldReturnMutationFailed_whenMutationExceptionThrown() throws Exception {
        accessor.setDeleteElementBehavior((sessionId, elementId) -> {
            throw new MutationException("CommandStack execution failed");
        });

        Map<String, Object> args = new HashMap<>();
        args.put("elementId", "elem-1");

        McpSchema.CallToolResult result = callTool("delete-element", args);
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("MUTATION_FAILED", error.get("code"));
    }

    @Test
    public void shouldReturnModelNotLoaded_whenNoModel() throws Exception {
        StubDeleteAccessor noModel = new StubDeleteAccessor(false);
        DeletionHandler noModelHandler = new DeletionHandler(
                noModel, formatter, new CommandRegistry(), null);

        Map<String, Object> args = new HashMap<>();
        args.put("elementId", "elem-1");

        McpSchema.CallToolResult result = noModelHandler.handleDeleteElement(null,
                McpSchema.CallToolRequest.builder().name("delete-element")
                        .arguments(args).build());
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("MODEL_NOT_LOADED", error.get("code"));
    }

    // ---- Approval mode ----

    @Test
    public void shouldReturnProposalResponse_whenApprovalModeEnabled() throws Exception {
        ProposalContext proposalCtx = new ProposalContext("p-42",
                "Delete element: elem-1", Instant.now());
        accessor.setDeleteElementBehavior((sessionId, elementId) -> {
            DeleteResultDto dto = new DeleteResultDto(
                    elementId, "Test", "BusinessActor",
                    0, 0, 0, null, null, null);
            return new MutationResult<>(dto, null, proposalCtx);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("elementId", "elem-1");
        Map<String, Object> result = callAndParse("delete-element", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull("Should have proposal", entity.get("proposal"));

        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) entity.get("proposal");
        assertEquals("p-42", proposal.get("proposalId"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("decide-mutation")));
    }

    // ---- Batch mode ----

    @Test
    public void shouldReturnBatchInfo_whenInBatchMode() throws Exception {
        accessor.setBatchMode(true);

        Map<String, Object> args = new HashMap<>();
        args.put("elementId", "elem-1");
        Map<String, Object> result = callAndParse("delete-element", args);

        Map<String, Object> entity = getResult(result);
        assertNotNull("Should have batch info", entity.get("batch"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("batch")));
    }

    // ---- Helper methods ----

    private McpSchema.CallToolResult callTool(String toolName, Map<String, Object> args) {
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name(toolName)
                .arguments(args)
                .build();
        return handler.handleDeleteElement(null, request);
    }

    private Map<String, Object> callAndParse(String toolName, Map<String, Object> args)
            throws Exception {
        McpSchema.CallToolResult result = callTool(toolName, args);
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

    // ---- Test stubs ----

    @FunctionalInterface
    interface DeleteElementBehavior {
        MutationResult<DeleteResultDto> apply(String sessionId, String elementId);
    }

    static class StubDeleteAccessor extends BaseTestAccessor {

        private final StubMutationDispatcher dispatcher;
        private boolean batchMode = false;
        private DeleteElementBehavior deleteElementBehavior;
        private DeleteRelationshipBehavior deleteRelationshipBehavior;
        private DeleteViewBehavior deleteViewBehavior;
        private DeleteFolderBehavior deleteFolderBehavior;

        StubDeleteAccessor() {
            super(true);
            this.dispatcher = new StubMutationDispatcher();
            resetBehaviors();
        }

        StubDeleteAccessor(boolean modelLoaded) {
            super(modelLoaded);
            this.dispatcher = modelLoaded ? new StubMutationDispatcher() : null;
            resetBehaviors();
        }

        void setBatchMode(boolean batch) {
            this.batchMode = batch;
        }

        void setDeleteElementBehavior(DeleteElementBehavior behavior) {
            this.deleteElementBehavior = behavior;
        }

        void setDeleteRelationshipBehavior(DeleteRelationshipBehavior behavior) {
            this.deleteRelationshipBehavior = behavior;
        }

        void setDeleteViewBehavior(DeleteViewBehavior behavior) {
            this.deleteViewBehavior = behavior;
        }

        void setDeleteFolderBehavior(DeleteFolderBehavior behavior) {
            this.deleteFolderBehavior = behavior;
        }

        private void resetBehaviors() {
            this.deleteElementBehavior = (sessionId, elementId) -> {
                DeleteResultDto dto = new DeleteResultDto(
                        elementId, "Test Element", "BusinessActor",
                        0, 0, 0, null, null, null);
                return new MutationResult<>(dto, batchMode ? 1 : null);
            };
            this.deleteRelationshipBehavior = (sessionId, relationshipId) -> {
                DeleteResultDto dto = new DeleteResultDto(
                        relationshipId, "Test Relationship", "ServingRelationship",
                        0, 0, 0, null, null, null);
                return new MutationResult<>(dto, batchMode ? 1 : null);
            };
            this.deleteViewBehavior = (sessionId, viewId) -> {
                DeleteResultDto dto = new DeleteResultDto(
                        viewId, "Test View", "View",
                        0, 0, 0, null, null, null);
                return new MutationResult<>(dto, batchMode ? 1 : null);
            };
            this.deleteFolderBehavior = (sessionId, folderId, force) -> {
                DeleteResultDto dto = new DeleteResultDto(
                        folderId, "Test Folder", "Folder",
                        0, 0, 0,
                        force ? 0 : null, force ? 0 : null, force ? 0 : null);
                return new MutationResult<>(dto, batchMode ? 1 : null);
            };
        }

        @Override
        public MutationResult<DeleteResultDto> deleteElement(String sessionId, String elementId) {
            return deleteElementBehavior.apply(sessionId, elementId);
        }

        @Override
        public MutationResult<DeleteResultDto> deleteRelationship(String sessionId,
                String relationshipId) {
            return deleteRelationshipBehavior.apply(sessionId, relationshipId);
        }

        @Override
        public MutationResult<DeleteResultDto> deleteView(String sessionId, String viewId) {
            return deleteViewBehavior.apply(sessionId, viewId);
        }

        @Override
        public MutationResult<DeleteResultDto> deleteFolder(String sessionId, String folderId,
                boolean force) {
            return deleteFolderBehavior.apply(sessionId, folderId, force);
        }

        @Override
        public MutationDispatcher getMutationDispatcher() {
            return dispatcher;
        }
    }

    @FunctionalInterface
    interface DeleteRelationshipBehavior {
        MutationResult<DeleteResultDto> apply(String sessionId, String relationshipId);
    }

    @FunctionalInterface
    interface DeleteViewBehavior {
        MutationResult<DeleteResultDto> apply(String sessionId, String viewId);
    }

    @FunctionalInterface
    interface DeleteFolderBehavior {
        MutationResult<DeleteResultDto> apply(String sessionId, String folderId, boolean force);
    }

    static class StubMutationDispatcher extends MutationDispatcher {

        StubMutationDispatcher() {
            super(() -> null);
        }

        @Override
        protected void dispatchCommand(Command command) throws MutationException {
            // no-op for handler tests
        }
    }
}
