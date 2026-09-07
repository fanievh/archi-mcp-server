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
import net.vheerden.archi.mcp.response.dto.RelationshipSemanticAttributes;

import org.eclipse.gef.commands.Command;

/**
 * Tests for {@link ElementUpdateHandler}.
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
    public void updateElement_descriptionShouldDocumentResponseFields() {
        String desc = registry.getToolSpecifications().stream()
                .filter(spec -> "update-element".equals(spec.tool().name()))
                .findFirst().orElseThrow().tool().description();
        // NOTE: bare `documentation` is NOT a valid pin — it is already an optional
        // request parameter here. Pin the response sentence.
        assertTrue("must state specialization is returned only when set",
                desc.contains("specialization only when set"));
        // The DTO is re-read post-execution ONLY when batchSeq == null. In a batch or
        // under approval gating the caller gets the PRE-update snapshot, so an agent
        // verifying its own write from this response would wrongly conclude it failed.
        assertTrue("must warn the batch/approval response is pre-update",
                desc.contains("pre-update"));
    }

    @Test
    public void updateRelationship_descriptionShouldDocumentResponseFields() {
        String desc = registry.getToolSpecifications().stream()
                .filter(spec -> "update-relationship".equals(spec.tool().name()))
                .findFirst().orElseThrow().tool().description();
        assertTrue("must name sourceId", desc.contains("sourceId"));
        // specialization is NON_NULL and convertToRelationshipDto leaves it null when the
        // relationship has no primary profile — the common case. The update-element
        // clause already scopes this correctly; this one must match.
        assertTrue("specialization must be scoped to when one is set",
                desc.contains("specialization only when set"));
        // The response field list is a NEGATIVE enumeration: it claims by omission, so a field
        // the sentence does not name is a field the agent is told it will not get. It must
        // therefore name the full returned set, and it must not keep the retired claim that
        // documentation and properties are withheld — the mapper reports both on this path now.
        assertFalse("the retired claim that documentation/properties are withheld must be gone",
                desc.contains("NOT echoed"));
        for (String field : List.of("documentation", "properties", "sourceName", "targetName")) {
            assertTrue("the returned field set must name " + field, desc.contains(field));
        }
        assertTrue("must state that a cleared documentation comes back as an empty string",
                desc.contains("empty string rather than omitting"));
        assertTrue("must warn the batch/approval response is pre-update",
                desc.contains("pre-update"));
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

    /**
     * {@code create-element}'s description tells agents that provenance is a create-time parameter
     * and that no update tool accepts a {@code source} map — so later provenance must be written as
     * ordinary {@code mcp.source.}-prefixed property keys. That is an absolute claim in a shipped
     * string, and this is what stops it going stale: adding a {@code source} parameter to either
     * update tool makes the published sentence false, and fails here rather than in the field.
     *
     * <p>Asserted over both registered tools by reading their schemas, not by naming one of them,
     * so a third update tool registered by this handler is covered the day it appears.</p>
     */
    @Test
    public void shouldDeclareNoSourceParameter_onAnyUpdateTool() {
        assertEquals("both update tools must be registered for this guard to mean anything",
                2, registry.getToolSpecifications().size());
        registry.getToolSpecifications().forEach(spec -> {
            assertFalse(spec.tool().name() + " must not declare a source map: create-element's "
                    + "description tells agents no update tool takes one",
                    spec.tool().inputSchema().properties().containsKey("source"));
        });
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
            ElementDto dto = ElementDto.standard(id, "Test", "BusinessActor", null, "Business", doc, null);
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

    // ---- Approval mode tests ----

    @Test
    public void shouldReturnProposalResponse_whenApprovalModeEnabled() throws Exception {
        ProposalContext proposalCtx = new ProposalContext("p-99",
                "Update element: elem-1", Instant.now());
        accessor.setUpdateElementBehavior((sessionId, id, name, doc, properties) -> {
            ElementDto dto = ElementDto.standard(
                    id, name != null ? name : "Original", "BusinessActor", null, "Business", doc, null);
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
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("list-pending-approvals")));
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("list-pending-approvals")));
    }

    @Test
    public void shouldIncludeProposalDescription_whenApprovalModeEnabled() throws Exception {
        Instant testTime = Instant.parse("2026-02-24T10:00:00Z");
        ProposalContext proposalCtx = new ProposalContext("p-desc-1",
                "Update element: elem-1 (name → Updated)", testTime);
        accessor.setUpdateElementBehavior((sessionId, id, name, doc, properties) -> {
            ElementDto dto = ElementDto.standard(
                    id, name != null ? name : "Original", "BusinessActor", null, "Business", doc, null);
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
                    id, name != null ? name : "Original", "BusinessActor", null, "Business", doc, null);
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

    // ---- relationship semantic-attribute tests ----

    @Test
    @SuppressWarnings("unchecked")
    public void shouldAdvertiseSemanticAttributeParamsInUpdateRelationshipSchema() {
        Map<String, Object> properties = registry.getToolSpecifications().stream()
                .filter(spec -> "update-relationship".equals(spec.tool().name()))
                .findFirst()
                .orElseThrow()
                .tool().inputSchema().properties();

        assertTrue("schema should advertise accessType", properties.containsKey("accessType"));
        assertTrue("schema should advertise associationDirected",
                properties.containsKey("associationDirected"));
        assertTrue("schema should advertise influenceStrength",
                properties.containsKey("influenceStrength"));

        Map<String, Object> accessTypeProp = (Map<String, Object>) properties.get("accessType");
        List<?> enumValues = (List<?>) accessTypeProp.get("enum");
        assertTrue(enumValues.contains("access"));
        assertTrue(enumValues.contains("read"));
        assertTrue(enumValues.contains("write"));
        assertTrue(enumValues.contains("readwrite"));
    }

    @Test
    public void shouldPassInfluenceStrengthThroughHandler() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("id", "rel-1");
        args.put("influenceStrength", "+");
        callRelTool(args);
        assertNotNull(accessor.capturedRelationshipSemanticAttributes);
        assertEquals("+", accessor.capturedRelationshipSemanticAttributes.influenceStrength());
        assertNull(accessor.capturedRelationshipSemanticAttributes.accessType());
        assertNull(accessor.capturedRelationshipSemanticAttributes.associationDirected());
    }

    @Test
    public void shouldRejectInfluenceStrengthOnNonInfluence_atHandler() throws Exception {
        accessor.setUpdateRelationshipBehavior((sessionId, id, name, doc, properties) -> {
            throw new ModelAccessException(
                    "influenceStrength only applies to InfluenceRelationship; got CompositionRelationship.",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Omit influenceStrength, or update a relationship of type InfluenceRelationship.",
                    null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("id", "rel-comp");
        args.put("influenceStrength", "+");
        McpSchema.CallToolResult result = callRelTool(args);
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue(((String) error.get("message")).contains("influenceStrength"));
    }

    @Test
    public void shouldExtendNoFieldsToUpdateGuard_withSemanticAttributeFields() throws Exception {
        // When only semantic-attribute fields are supplied (no name/documentation/properties/specialization),
        // the handler should still pass the bundle through (no "no fields" error at handler).
        // The "at least one of" guard moved to the prepare boundary handles enforcement.
        Map<String, Object> args = new HashMap<>();
        args.put("id", "rel-1");
        args.put("accessType", "read");
        callRelTool(args);
        assertNotNull(accessor.capturedRelationshipSemanticAttributes);
        assertEquals("read", accessor.capturedRelationshipSemanticAttributes.accessType());
    }

    // ---- relationship empty-string clear (the schema's own promise) ----
    //
    // update-relationship's schema says "Empty string clears the name" and "Empty string clears
    // documentation". The command layer honours that. These pin the wire, which sits between the
    // two: the handler must hand the accessor the empty string it was given, because null is this
    // signature's sentinel for "leave unchanged" and a stripped "" is indistinguishable from an
    // omitted key by the time the command sees it.

    @Test
    public void shouldPassEmptyDocumentationToAccessor_whenClearRequested() throws Exception {
        String[] captured = new String[1];
        captured[0] = "<never called>";
        accessor.setUpdateRelationshipBehavior((sessionId, id, name, doc, properties) -> {
            captured[0] = doc;
            return new MutationResult<>(new RelationshipDto(
                    id, "Test Relationship", "AssociationRelationship", "src-1", "tgt-1"), null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("id", "rel-1");
        args.put("documentation", "");

        callRelTool(args);
        assertEquals("an empty documentation must reach the accessor as \"\", not as the "
                + "leave-unchanged null", "", captured[0]);
    }

    @Test
    public void shouldPassEmptyNameToAccessor_whenClearRequested() throws Exception {
        String[] captured = new String[1];
        captured[0] = "<never called>";
        accessor.setUpdateRelationshipBehavior((sessionId, id, name, doc, properties) -> {
            captured[0] = name;
            return new MutationResult<>(new RelationshipDto(
                    id, "Test Relationship", "AssociationRelationship", "src-1", "tgt-1"), null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("id", "rel-1");
        args.put("name", "");

        callRelTool(args);
        assertEquals("an empty name must reach the accessor as \"\", not as the leave-unchanged "
                + "null", "", captured[0]);
    }

    /**
     * The mixed set-and-clear shape. Pinned separately because a fix that only relaxed the
     * downstream "no fields to update" guard would satisfy a lone clear while leaving this one a
     * silent no-op — the caller sets the name, gets success, and keeps the old documentation.
     */
    @Test
    public void shouldPassNewNameAndEmptyDocumentation_whenSettingOneAndClearingTheOther()
            throws Exception {
        String[] captured = new String[2];
        accessor.setUpdateRelationshipBehavior((sessionId, id, name, doc, properties) -> {
            captured[0] = name;
            captured[1] = doc;
            return new MutationResult<>(new RelationshipDto(
                    id, name, "AssociationRelationship", "src-1", "tgt-1"), null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("id", "rel-1");
        args.put("name", "Serves");
        args.put("documentation", "");

        callRelTool(args);
        assertEquals("the supplied name must survive the wire", "Serves", captured[0]);
        assertEquals("the clear must survive the wire alongside it", "", captured[1]);
    }

    /**
     * The negative control for the two above: preserving "" must not turn "absent" into "clear".
     * An omitted key and an explicit JSON null both still mean leave-unchanged.
     */
    @Test
    public void shouldPassNullDocumentation_whenTheKeyIsAbsentOrExplicitlyNull() throws Exception {
        String[] captured = new String[1];
        captured[0] = "<never called>";
        accessor.setUpdateRelationshipBehavior((sessionId, id, name, doc, properties) -> {
            captured[0] = doc;
            return new MutationResult<>(new RelationshipDto(
                    id, name, "AssociationRelationship", "src-1", "tgt-1"), null);
        });

        Map<String, Object> absent = new HashMap<>();
        absent.put("id", "rel-1");
        absent.put("name", "X");
        callRelTool(absent);
        assertNull("an omitted documentation key must stay the leave-unchanged null", captured[0]);

        captured[0] = "<never called>";
        Map<String, Object> explicitNull = new HashMap<>();
        explicitNull.put("id", "rel-1");
        explicitNull.put("name", "X");
        explicitNull.put("documentation", null);
        callRelTool(explicitNull);
        assertNull("an explicit JSON null must stay the leave-unchanged null", captured[0]);
    }

    /**
     * update-element makes no empty-clear promise in its schema, so it must keep stripping. Pinned
     * here so a future widening of the shared reader cannot silently give it clear semantics its
     * own description never advertised.
     */
    @Test
    public void shouldStillStripEmptyDocumentation_forUpdateElement() throws Exception {
        String[] captured = new String[1];
        captured[0] = "<never called>";
        accessor.setUpdateElementBehavior((sessionId, id, name, doc, properties) -> {
            captured[0] = doc;
            return new MutationResult<>(ElementDto.standard(
                    id, "Test Element", "BusinessActor", null, "Business", doc, null), null);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("id", "elem-1");
        args.put("name", "X");
        args.put("documentation", "");

        callTool(args);
        assertNull("update-element advertises no empty-clear, so \"\" must keep arriving as null",
                captured[0]);
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
                        id, displayName, "BusinessActor", null, "Business", doc, null);
                return new MutationResult<>(dto, batchMode ? 1 : null);
            };
            this.updateRelationshipBehavior = (sessionId, id, name, doc, properties) -> {
                String displayName = name != null ? name : "Test Relationship";
                RelationshipDto dto = new RelationshipDto(
                        id, displayName, "AssociationRelationship", "src-1", "tgt-1");
                return new MutationResult<>(dto, batchMode ? 1 : null);
            };
        }

        String capturedElementSpecialization;
        String capturedRelationshipSpecialization;
        RelationshipSemanticAttributes capturedRelationshipSemanticAttributes;

        @Override
        public MutationResult<ElementDto> updateElement(String sessionId, String id,
                String name, String documentation, Map<String, String> properties,
                String specialization) {
            capturedElementSpecialization = specialization;
            return updateElementBehavior.apply(sessionId, id, name, documentation, properties);
        }

        @Override
        public MutationResult<RelationshipDto> updateRelationship(String sessionId, String id,
                String name, String documentation, Map<String, String> properties,
                String specialization, RelationshipSemanticAttributes semanticAttributes) {
            capturedRelationshipSpecialization = specialization;
            capturedRelationshipSemanticAttributes = semanticAttributes;
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
