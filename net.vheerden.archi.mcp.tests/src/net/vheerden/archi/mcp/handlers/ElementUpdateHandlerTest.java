package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.RelationshipDto;

import org.eclipse.gef.commands.Command;

/**
 * Tests for {@link ElementUpdateHandler} (Story 7-3).
 *
 * <p>Uses a StubUpdateAccessor that returns canned DTOs for the
 * updateElement method, avoiding EMF/GEF dependencies in handler tests.</p>
 */
public class ElementUpdateHandlerTest {

    private ObjectMapper objectMapper;
    private CommandRegistry registry;
    private ResponseFormatter formatter;
    private StubUpdateAccessor accessor;
    private ElementUpdateHandler handler;

    @Before
    public void setUp() {
        objectMapper = new ObjectMapper();
        registry = new CommandRegistry();
        formatter = new ResponseFormatter();
        accessor = new StubUpdateAccessor();
        handler = new ElementUpdateHandler(accessor, formatter, registry, null);
        handler.registerTools();
    }

    // ---- Tool registration tests ----

    @Test
    public void shouldRegisterTwoTools_whenHandlerRegistered() {
        assertEquals(2, registry.getToolSpecifications().size());
    }

    @Test
    public void shouldRegisterUpdateElementTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "update-element".equals(spec.tool().name()));
        assertTrue("update-element tool should be registered", found);
    }

    @Test
    public void shouldRegisterUpdateRelationshipTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "update-relationship".equals(spec.tool().name()));
        assertTrue("update-relationship tool should be registered", found);
    }

    @Test
    public void shouldHaveMutationPrefix_inToolDescription() {
        registry.getToolSpecifications().forEach(spec -> {
            assertTrue(spec.tool().name() + " description should start with [Mutation]",
                    spec.tool().description().startsWith("[Mutation]"));
        });
    }

    @Test
    public void shouldHaveIdAsRequiredParam() {
        McpSchema.Tool tool = registry.getToolSpecifications().get(0).tool();
        assertTrue("id should be required",
                tool.inputSchema().required().contains("id"));
    }

    // ---- update-element success tests ----

    @Test
    public void shouldReturnUpdatedElementDto_whenNameUpdated() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("id", "elem-1");
        args.put("name", "Updated Name");
        Map<String, Object> result = callAndParse(args);

        Map<String, Object> entity = getResult(result);
        assertEquals("elem-1", entity.get("id"));
        assertEquals("Updated Name", entity.get("name"));
    }

    @Test
    public void shouldReturnNextSteps_whenUpdateSucceeds() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("id", "elem-1");
        args.put("name", "Updated Name");
        Map<String, Object> result = callAndParse(args);

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertFalse(nextSteps.isEmpty());
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("get-element")));
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("elem-1")));
    }

    @Test
    public void shouldReturnEnvelopeWithMeta_whenUpdateSucceeds() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("id", "elem-1");
        args.put("documentation", "New docs");
        Map<String, Object> result = callAndParse(args);

        assertNotNull("result key should exist", result.get("result"));
        assertNotNull("nextSteps key should exist", result.get("nextSteps"));
        assertNotNull("_meta key should exist", result.get("_meta"));
    }

    @Test
    public void shouldPassPropertiesWithNulls_toAccessor() throws Exception {
        Map<String, Object> props = new HashMap<>();
        props.put("keep", "value");
        props.put("remove", null);

        Map<String, Object> args = new HashMap<>();
        args.put("id", "elem-1");
        args.put("properties", props);

        accessor.setUpdateElementBehavior((sessionId, id, name, doc, properties) -> {
            // Verify null values are preserved
            assertNotNull("Properties should not be null", properties);
            assertEquals("value", properties.get("keep"));
            assertTrue("Should contain key with null value", properties.containsKey("remove"));
            assertNull("Null value should be preserved", (Object) properties.get("remove"));
            ElementDto dto = ElementDto.standard(id, "Test", "BusinessActor", "Business", doc, null);
            return new MutationResult<>(dto, null);
        });

        McpSchema.CallToolResult result = callTool(args);
        assertFalse("Should not be an error", result.isError());
    }

    // ---- Error handling tests ----

    @Test
    public void shouldReturnInvalidParameterError_whenIdMissing() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("name", "Updated Name");

        McpSchema.CallToolResult result = callTool(args);
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @Test
    public void shouldReturnInvalidParameterError_whenIdEmpty() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("id", "   ");
        args.put("name", "Updated Name");

        McpSchema.CallToolResult result = callTool(args);
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @Test
    public void shouldReturnElementNotFoundError_whenElementMissing() throws Exception {
        accessor.setUpdateElementBehavior((sessionId, id, name, doc, properties) -> {
            throw new ModelAccessException("Element not found: " + id,
                    ErrorCode.ELEMENT_NOT_FOUND);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("id", "bad-id");
        args.put("name", "Updated");

        McpSchema.CallToolResult result = callTool(args);
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("ELEMENT_NOT_FOUND", error.get("code"));
    }

    @Test
    public void shouldReturnInvalidParameterError_whenNoFieldsToUpdate() throws Exception {
        accessor.setUpdateElementBehavior((sessionId, id, name, doc, properties) -> {
            throw new ModelAccessException(
                    "No fields to update",
                    ErrorCode.INVALID_PARAMETER);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("id", "elem-1");
        // No name, documentation, or properties

        McpSchema.CallToolResult result = callTool(args);
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @Test
    public void shouldReturnMutationFailedError_whenMutationExceptionThrown() throws Exception {
        accessor.setUpdateElementBehavior((sessionId, id, name, doc, properties) -> {
            throw new MutationException("CommandStack execution failed");
        });

        Map<String, Object> args = new HashMap<>();
        args.put("id", "elem-1");
        args.put("name", "Updated");

        McpSchema.CallToolResult result = callTool(args);
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("MUTATION_FAILED", error.get("code"));
    }

    // ---- Model not loaded test ----

    @Test
    public void shouldReturnModelNotLoaded_whenNoModelLoaded() throws Exception {
        StubUpdateAccessor noModel = new StubUpdateAccessor(false);
        ElementUpdateHandler noModelHandler = new ElementUpdateHandler(
                noModel, formatter, new CommandRegistry(), null);

        Map<String, Object> args = new HashMap<>();
        args.put("id", "elem-1");
        args.put("name", "Test");

        McpSchema.CallToolResult result = noModelHandler.handleUpdateElement(null,
                McpSchema.CallToolRequest.builder().name("update-element")
                        .arguments(args).build());
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("MODEL_NOT_LOADED", error.get("code"));
    }

    // ---- Approval mode tests (Story 7-6) ----

    @Test
    public void shouldReturnProposalResponse_whenApprovalModeEnabled() throws Exception {
        ProposalContext proposalCtx = new ProposalContext("p-99",
                "Update element: elem-1", Instant.now());
        accessor.setUpdateElementBehavior((sessionId, id, name, doc, properties) -> {
            ElementDto dto = ElementDto.standard(
                    id, name != null ? name : "Original", "BusinessActor", "Business", doc, null);
            return new MutationResult<>(dto, null, proposalCtx);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("id", "elem-1");
        args.put("name", "Updated Name");
        Map<String, Object> result = callAndParse(args);

        Map<String, Object> entity = getResult(result);
        assertNotNull("Should have proposal", entity.get("proposal"));

        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) entity.get("proposal");
        assertEquals("p-99", proposal.get("proposalId"));
        assertEquals("pending", proposal.get("status"));

        assertNotNull("Should have preview", entity.get("preview"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("decide-mutation")));
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("list-pending-approvals")));
    }

    @Test
    public void shouldIncludeProposalDescription_whenApprovalModeEnabled() throws Exception {
        Instant testTime = Instant.parse("2026-02-24T10:00:00Z");
        ProposalContext proposalCtx = new ProposalContext("p-desc-1",
                "Update element: elem-1 (name → Updated)", testTime);
        accessor.setUpdateElementBehavior((sessionId, id, name, doc, properties) -> {
            ElementDto dto = ElementDto.standard(
                    id, name != null ? name : "Original", "BusinessActor", "Business", doc, null);
            return new MutationResult<>(dto, null, proposalCtx);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("id", "elem-1");
        args.put("name", "Updated");
        Map<String, Object> result = callAndParse(args);

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) entity.get("proposal");
        assertEquals("Update element: elem-1 (name → Updated)", proposal.get("description"));
        assertEquals(testTime.toString(), proposal.get("createdAt"));
    }

    @Test
    public void shouldIncludePreviewInProposal_whenApprovalModeEnabled() throws Exception {
        ProposalContext proposalCtx = new ProposalContext("p-preview-1",
                "Update element: elem-1", Instant.now());
        accessor.setUpdateElementBehavior((sessionId, id, name, doc, properties) -> {
            ElementDto dto = ElementDto.standard(
                    id, name != null ? name : "Original", "BusinessActor", "Business", doc, null);
            return new MutationResult<>(dto, null, proposalCtx);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("id", "elem-1");
        args.put("name", "Preview Name");
        Map<String, Object> result = callAndParse(args);

        Map<String, Object> entity = getResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> preview = (Map<String, Object>) entity.get("preview");
        assertNotNull("Preview should be present", preview);
        assertEquals("elem-1", preview.get("id"));
        assertEquals("Preview Name", preview.get("name"));
    }

    // ---- Batch mode tests ----

    @Test
    public void shouldReturnBatchInfo_whenUpdateInBatchMode() throws Exception {
        accessor.setBatchMode(true);

        Map<String, Object> args = new HashMap<>();
        args.put("id", "elem-1");
        args.put("name", "Updated Name");
        Map<String, Object> result = callAndParse(args);

        Map<String, Object> entity = getResult(result);
        assertNotNull("Should have batch info", entity.get("batch"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("batch")));
    }

    // ---- update-relationship success tests ----

    @Test
    public void shouldReturnUpdatedRelationshipDto_whenNameUpdated() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("id", "rel-1");
        args.put("name", "Updated Rel");
        Map<String, Object> result = callRelAndParse(args);

        Map<String, Object> entity = getResult(result);
        assertEquals("rel-1", entity.get("id"));
        assertEquals("Updated Rel", entity.get("name"));
    }

    @Test
    public void shouldReturnUpdatedRelationship_whenDocumentationUpdated() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("id", "rel-1");
        args.put("documentation", "New rel docs");
        Map<String, Object> result = callRelAndParse(args);

        Map<String, Object> entity = getResult(result);
        assertEquals("rel-1", entity.get("id"));
    }

    @Test
    public void shouldReturnUpdatedRelationship_whenPropertiesUpdated() throws Exception {
        Map<String, Object> props = new HashMap<>();
        props.put("keep", "value");
        props.put("remove", null);

        Map<String, Object> args = new HashMap<>();
        args.put("id", "rel-1");
        args.put("properties", props);

        accessor.setUpdateRelationshipBehavior((sessionId, id, name, doc, properties) -> {
            assertNotNull("Properties should not be null", properties);
            assertEquals("value", properties.get("keep"));
            assertTrue("Should contain key with null value", properties.containsKey("remove"));
            assertNull("Null value should be preserved", (Object) properties.get("remove"));
            RelationshipDto dto = new RelationshipDto(id, "Test Rel", "AssociationRelationship", "src-1", "tgt-1");
            return new MutationResult<>(dto, null);
        });

        McpSchema.CallToolResult result = callRelTool(args);
        assertFalse("Should not be an error", result.isError());
    }

    @Test
    public void shouldReturnUpdatedRelationship_whenAllFieldsUpdated() throws Exception {
        Map<String, Object> props = new HashMap<>();
        props.put("status", "active");

        Map<String, Object> args = new HashMap<>();
        args.put("id", "rel-1");
        args.put("name", "New Name");
        args.put("documentation", "New docs");
        args.put("properties", props);
        Map<String, Object> result = callRelAndParse(args);

        Map<String, Object> entity = getResult(result);
        assertEquals("rel-1", entity.get("id"));
        assertEquals("New Name", entity.get("name"));
    }

    @Test
    public void shouldReturnNextSteps_whenRelationshipUpdateSucceeds() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("id", "rel-1");
        args.put("name", "Updated Rel");
        Map<String, Object> result = callRelAndParse(args);

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertFalse(nextSteps.isEmpty());
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("get-relationships")));
    }

    // ---- update-relationship error tests ----

    @Test
    public void shouldReturnInvalidParameterError_whenRelIdMissing() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("name", "Updated Rel");

        McpSchema.CallToolResult result = callRelTool(args);
        assertTrue("Should be an error", result.isError());

        Map<String, Object> parsed = parseResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @Test
    public void shouldReturnRelationshipNotFoundError_whenRelationshipMissing() throws Exception {
        accessor.setUpdateRelationshipBehavior((sessionId, id, name, doc, properties) -> {
            throw new ModelAccessException("Relationship not found: " + id,
                    ErrorCode.RELATIONSHIP_NOT_FOUND);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("id", "bad-id");
        args.put("name", "Updated");

        McpSchema.CallToolResult result = callRelTool(args);
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("RELATIONSHIP_NOT_FOUND", error.get("code"));
    }

    @Test
    public void shouldReturnInvalidParameterError_whenNoRelFieldsToUpdate() throws Exception {
        accessor.setUpdateRelationshipBehavior((sessionId, id, name, doc, properties) -> {
            throw new ModelAccessException(
                    "No fields to update",
                    ErrorCode.INVALID_PARAMETER);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("id", "rel-1");

        McpSchema.CallToolResult result = callRelTool(args);
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    // ---- Helper methods ----

    private McpSchema.CallToolResult callRelTool(Map<String, Object> args) throws Exception {
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name("update-relationship")
                .arguments(args)
                .build();
        return handler.handleUpdateRelationship(null, request);
    }

    private Map<String, Object> callRelAndParse(Map<String, Object> args) throws Exception {
        McpSchema.CallToolResult result = callRelTool(args);
        return parseResult(result);
    }

    private McpSchema.CallToolResult callTool(Map<String, Object> args) throws Exception {
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name("update-element")
                .arguments(args)
                .build();
        return handler.handleUpdateElement(null, request);
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

    // ---- Test stubs ----

    @FunctionalInterface
    interface UpdateElementBehavior {
        MutationResult<ElementDto> apply(String sessionId, String id, String name,
                String documentation, Map<String, String> properties);
    }

    @FunctionalInterface
    interface UpdateRelationshipBehavior {
        MutationResult<RelationshipDto> apply(String sessionId, String id, String name,
                String documentation, Map<String, String> properties);
    }

    private static class StubUpdateAccessor extends BaseTestAccessor {

        private final StubMutationDispatcher dispatcher;
        private boolean batchMode = false;
        private UpdateElementBehavior updateElementBehavior;
        private UpdateRelationshipBehavior updateRelationshipBehavior;

        StubUpdateAccessor() {
            super(true);
            this.dispatcher = new StubMutationDispatcher();
            resetBehaviors();
        }

        StubUpdateAccessor(boolean modelLoaded) {
            super(modelLoaded);
            this.dispatcher = modelLoaded ? new StubMutationDispatcher() : null;
            resetBehaviors();
        }

        void setBatchMode(boolean batch) {
            this.batchMode = batch;
        }

        void setUpdateElementBehavior(UpdateElementBehavior behavior) {
            this.updateElementBehavior = behavior;
        }

        void setUpdateRelationshipBehavior(UpdateRelationshipBehavior behavior) {
            this.updateRelationshipBehavior = behavior;
        }

        private void resetBehaviors() {
            this.updateElementBehavior = (sessionId, id, name, doc, properties) -> {
                String displayName = name != null ? name : "Test Element";
                ElementDto dto = ElementDto.standard(
                        id, displayName, "BusinessActor", "Business", doc, null);
                return new MutationResult<>(dto, batchMode ? 1 : null);
            };
            this.updateRelationshipBehavior = (sessionId, id, name, doc, properties) -> {
                String displayName = name != null ? name : "Test Relationship";
                RelationshipDto dto = new RelationshipDto(
                        id, displayName, "AssociationRelationship", "src-1", "tgt-1");
                return new MutationResult<>(dto, batchMode ? 1 : null);
            };
        }

        @Override
        public MutationResult<ElementDto> updateElement(String sessionId, String id,
                String name, String documentation, Map<String, String> properties) {
            return updateElementBehavior.apply(sessionId, id, name, documentation, properties);
        }

        @Override
        public MutationResult<RelationshipDto> updateRelationship(String sessionId, String id,
                String name, String documentation, Map<String, String> properties) {
            return updateRelationshipBehavior.apply(sessionId, id, name, documentation, properties);
        }

        @Override
        public MutationDispatcher getMutationDispatcher() {
            return dispatcher;
        }
    }

    private static class StubMutationDispatcher extends MutationDispatcher {

        StubMutationDispatcher() {
            super(() -> null);
        }

        @Override
        protected void dispatchCommand(Command command) throws MutationException {
            // no-op for handler tests
        }
    }
}
