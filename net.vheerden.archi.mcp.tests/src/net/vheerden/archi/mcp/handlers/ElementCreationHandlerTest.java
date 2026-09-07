package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import net.vheerden.archi.mcp.response.dto.DuplicateCandidate;
import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.RelationshipDto;
import net.vheerden.archi.mcp.response.dto.RelationshipSemanticAttributes;
import net.vheerden.archi.mcp.response.dto.ViewDto;

import org.eclipse.gef.commands.Command;

/**
 * Tests for {@link ElementCreationHandler}.
 *
 * <p>Uses a StubCreationAccessor that returns canned DTOs for creation
 * methods, avoiding EMF/GEF dependencies in handler tests.</p>
 */
public class ElementCreationHandlerTest {

    private ObjectMapper objectMapper;
    private CommandRegistry registry;
    private ResponseFormatter formatter;
    private StubCreationAccessor accessor;
    private ElementCreationHandler handler;

    @Before
    public void setUp() {
        objectMapper = new ObjectMapper();
        registry = new CommandRegistry();
        formatter = new ResponseFormatter();
        accessor = new StubCreationAccessor();
        handler = new ElementCreationHandler(accessor, formatter, registry, null);
        handler.registerTools();
    }

    // ---- Tool registration tests ----

    @Test
    public void shouldRegisterFourTools_whenHandlerRegistered() {
        assertEquals(4, registry.getToolSpecifications().size());
    }

    @Test
    public void shouldRegisterCreateElementTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "create-element".equals(spec.tool().name()));
        assertTrue("create-element tool should be registered", found);
    }

    @Test
    public void shouldRegisterCreateRelationshipTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "create-relationship".equals(spec.tool().name()));
        assertTrue("create-relationship tool should be registered", found);
    }

    @Test
    public void createRelationship_descriptionShouldDocumentResponseFields() {
        String desc = registry.getToolSpecifications().stream()
                .filter(spec -> "create-relationship".equals(spec.tool().name()))
                .findFirst().orElseThrow().tool().description();
        assertTrue("must name sourceName", desc.contains("sourceName"));
        // alreadyExisted is @JsonInclude(NON_DEFAULT): it is ABSENT rather than false on
        // a fresh create, so an agent testing `alreadyExisted == false` never matches.
        assertTrue("must name alreadyExisted", desc.contains("alreadyExisted"));
        assertTrue("must state the flag is absent rather than false on a fresh create",
                desc.contains("absent, not false"));
        // The duplicate-detection path builds the DTO with (…, true, null, null, null,
        // null): sourceName/targetName are NON_NULL and therefore vanish. Listing them
        // unconditionally would promise endpoint names that a duplicate never returns.
        assertTrue("sourceName/targetName must be scoped to the fresh-create path",
                desc.contains("only on a fresh create"));
        assertTrue("must state the duplicate path omits the name fields",
                desc.contains("omits the two name fields"));
    }

    @Test
    public void createView_descriptionShouldDocumentResponseFields() {
        String desc = registry.getToolSpecifications().stream()
                .filter(spec -> "create-view".equals(spec.tool().name()))
                .findFirst().orElseThrow().tool().description();
        assertTrue("must name folderPath", desc.contains("folderPath"));
        // viewpointType is dropped by DtoMapper when unset/empty.
        assertTrue("must gate viewpointType on a viewpoint having been set",
                desc.contains("viewpointType is present only when"));
        // DtoMapper.mapConnectionRouterType returns null for CONNECTION_ROUTER_BENDPOINT
        // — i.e. for 'manual', which is both the tool default AND the value this very
        // description tells agents to prefer. So the field is absent in the recommended
        // case; documenting it as always-returned is backwards.
        assertTrue("must state connectionRouterType comes back only for manhattan",
                desc.contains("only for 'manhattan'"));
        assertTrue("must state the default manual router is omitted",
                desc.contains("'manual' is omitted"));
    }

    @Test
    public void shouldRegisterCreateViewTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "create-view".equals(spec.tool().name()));
        assertTrue("create-view tool should be registered", found);
    }

    @Test
    public void shouldHaveMutationPrefix_inToolDescriptions() {
        registry.getToolSpecifications().forEach(spec -> {
            assertTrue(spec.tool().name() + " description should start with [Mutation]",
                    spec.tool().description().startsWith("[Mutation]"));
        });
    }

    // ---- create-element tests ----

    @Test
    public void shouldReturnElementDto_whenCreateElementCalled() throws Exception {
        Map<String, Object> result = callAndParse("create-element",
                Map.of("type", "BusinessActor", "name", "Customer"));

        Map<String, Object> entity = getResult(result);
        assertEquals("elem-created-1", entity.get("id"));
        assertEquals("Customer", entity.get("name"));
        assertEquals("BusinessActor", entity.get("type"));
    }

    @Test
    public void shouldReturnNextSteps_whenCreateElementSucceeds() throws Exception {
        Map<String, Object> result = callAndParse("create-element",
                Map.of("type", "BusinessActor", "name", "Customer"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertFalse(nextSteps.isEmpty());
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("get-element")));
    }

    @Test
    public void shouldReturnEnvelopeWithMeta_whenCreateElementSucceeds() throws Exception {
        Map<String, Object> result = callAndParse("create-element",
                Map.of("type", "BusinessActor", "name", "Customer"));

        assertNotNull("result key should exist", result.get("result"));
        assertNotNull("nextSteps key should exist", result.get("nextSteps"));
        assertNotNull("_meta key should exist", result.get("_meta"));
    }

    @Test
    public void shouldReturnInvalidParameterError_whenTypeMissing() throws Exception {
        McpSchema.CallToolResult result = callTool("create-element",
                Map.of("name", "Customer"));
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @Test
    public void shouldReturnInvalidParameterError_whenNameMissing() throws Exception {
        McpSchema.CallToolResult result = callTool("create-element",
                Map.of("type", "BusinessActor"));
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @Test
    public void shouldReturnInvalidElementTypeError_whenTypeInvalid() throws Exception {
        accessor.setCreateElementBehavior((sessionId, type, name, doc, props, folderId) -> {
            throw new ModelAccessException("Invalid ArchiMate element type: " + type,
                    ErrorCode.INVALID_ELEMENT_TYPE);
        });

        McpSchema.CallToolResult result = callTool("create-element",
                Map.of("type", "NotARealType", "name", "Test"));
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("INVALID_ELEMENT_TYPE", error.get("code"));
    }

    @Test
    public void shouldReturnMutationFailedError_whenCreateElementThrowsMutationException()
            throws Exception {
        accessor.setCreateElementBehavior((sessionId, type, name, doc, props, folderId) -> {
            throw new MutationException("CommandStack execution failed");
        });

        McpSchema.CallToolResult result = callTool("create-element",
                Map.of("type", "BusinessActor", "name", "Test"));
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("MUTATION_FAILED", error.get("code"));
    }

    @Test
    public void shouldReturnFolderNotFoundError_whenFolderIdInvalid() throws Exception {
        accessor.setCreateElementBehavior((sessionId, type, name, doc, props, folderId) -> {
            throw new ModelAccessException("Folder not found: " + folderId,
                    ErrorCode.FOLDER_NOT_FOUND);
        });

        McpSchema.CallToolResult result = callTool("create-element",
                Map.of("type", "BusinessActor", "name", "Test", "folderId", "bad-folder-id"));
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("FOLDER_NOT_FOUND", error.get("code"));
    }

    // ---- create-relationship tests ----

    @Test
    public void shouldReturnRelationshipDto_whenCreateRelationshipCalled() throws Exception {
        Map<String, Object> result = callAndParse("create-relationship",
                Map.of("type", "ServingRelationship",
                        "sourceId", "elem-1", "targetId", "elem-2"));

        Map<String, Object> entity = getResult(result);
        assertEquals("rel-created-1", entity.get("id"));
        assertEquals("ServingRelationship", entity.get("type"));
        assertEquals("elem-1", entity.get("sourceId"));
        assertEquals("elem-2", entity.get("targetId"));
    }

    @Test
    public void shouldReturnNextSteps_whenCreateRelationshipSucceeds() throws Exception {
        Map<String, Object> result = callAndParse("create-relationship",
                Map.of("type", "ServingRelationship",
                        "sourceId", "elem-1", "targetId", "elem-2"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertFalse(nextSteps.isEmpty());
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("get-relationships")));
    }

    @Test
    public void shouldReturnInvalidParameterError_whenSourceIdMissing() throws Exception {
        McpSchema.CallToolResult result = callTool("create-relationship",
                Map.of("type", "ServingRelationship", "targetId", "elem-2"));
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @Test
    public void shouldReturnSourceNotFoundError_whenSourceInvalid() throws Exception {
        accessor.setCreateRelationshipBehavior((sessionId, type, sourceId, targetId, name) -> {
            throw new ModelAccessException("Source element not found: " + sourceId,
                    ErrorCode.SOURCE_ELEMENT_NOT_FOUND);
        });

        McpSchema.CallToolResult result = callTool("create-relationship",
                Map.of("type", "ServingRelationship",
                        "sourceId", "bad-id", "targetId", "elem-2"));
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("SOURCE_ELEMENT_NOT_FOUND", error.get("code"));
    }

    @Test
    public void shouldReturnRelationshipNotAllowedError_whenSpecViolated() throws Exception {
        accessor.setCreateRelationshipBehavior((sessionId, type, sourceId, targetId, name) -> {
            throw new ModelAccessException(
                    "ServingRelationship not valid between source and target",
                    ErrorCode.RELATIONSHIP_NOT_ALLOWED,
                    "Valid types: AssociationRelationship",
                    "Try AssociationRelationship",
                    "ArchiMate 3.2 specification");
        });

        McpSchema.CallToolResult result = callTool("create-relationship",
                Map.of("type", "ServingRelationship",
                        "sourceId", "elem-1", "targetId", "elem-2"));
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("RELATIONSHIP_NOT_ALLOWED", error.get("code"));
        assertNotNull("Should have details", error.get("details"));
        assertNotNull("Should have suggestedCorrection", error.get("suggestedCorrection"));
        assertNotNull("Should have archiMateReference", error.get("archiMateReference"));
    }

    // ---- create-view tests ----

    @Test
    public void shouldReturnViewDto_whenCreateViewCalled() throws Exception {
        Map<String, Object> result = callAndParse("create-view",
                Map.of("name", "New Diagram"));

        Map<String, Object> entity = getResult(result);
        assertEquals("view-created-1", entity.get("id"));
        assertEquals("New Diagram", entity.get("name"));
    }

    @Test
    public void shouldReturnNextSteps_whenCreateViewSucceeds() throws Exception {
        Map<String, Object> result = callAndParse("create-view",
                Map.of("name", "New Diagram"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertFalse(nextSteps.isEmpty());
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("get-views")));
    }

    @Test
    public void shouldReturnInvalidParameterError_whenViewNameMissing() throws Exception {
        McpSchema.CallToolResult result = callTool("create-view",
                Map.of("viewpoint", "layered"));
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    // ---- Model not loaded tests ----

    @Test
    public void shouldReturnModelNotLoaded_whenCreateElementWithNoModel() throws Exception {
        StubCreationAccessor noModel = new StubCreationAccessor(false);
        ElementCreationHandler noModelHandler = new ElementCreationHandler(
                noModel, formatter, new CommandRegistry(), null);

        McpSchema.CallToolResult result = noModelHandler.handleCreateElement(null,
                McpSchema.CallToolRequest.builder().name("create-element")
                        .arguments(Map.of("type", "BusinessActor", "name", "Test")).build());
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("MODEL_NOT_LOADED", error.get("code"));
    }

    @Test
    public void shouldReturnModelNotLoaded_whenCreateRelationshipWithNoModel() throws Exception {
        StubCreationAccessor noModel = new StubCreationAccessor(false);
        ElementCreationHandler noModelHandler = new ElementCreationHandler(
                noModel, formatter, new CommandRegistry(), null);

        McpSchema.CallToolResult result = noModelHandler.handleCreateRelationship(null,
                McpSchema.CallToolRequest.builder().name("create-relationship")
                        .arguments(Map.of("type", "ServingRelationship",
                                "sourceId", "a", "targetId", "b")).build());
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("MODEL_NOT_LOADED", error.get("code"));
    }

    @Test
    public void shouldReturnModelNotLoaded_whenCreateViewWithNoModel() throws Exception {
        StubCreationAccessor noModel = new StubCreationAccessor(false);
        ElementCreationHandler noModelHandler = new ElementCreationHandler(
                noModel, formatter, new CommandRegistry(), null);

        McpSchema.CallToolResult result = noModelHandler.handleCreateView(null,
                McpSchema.CallToolRequest.builder().name("create-view")
                        .arguments(Map.of("name", "Test")).build());
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("MODEL_NOT_LOADED", error.get("code"));
    }

    // ---- Duplicate detection tests ----

    @Test
    public void shouldReturnPotentialDuplicatesError_whenDuplicatesFound() throws Exception {
        accessor.setFindDuplicatesResult(List.of(
                new DuplicateCandidate("dup-1", "Customer Services", "BusinessActor", 0.92),
                new DuplicateCandidate("dup-2", "Customer Svc", "BusinessActor", 0.78)));

        McpSchema.CallToolResult result = callTool("create-element",
                Map.of("type", "BusinessActor", "name", "Customer Service"));
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("POTENTIAL_DUPLICATES", error.get("code"));
        assertNotNull("Should have duplicates array", error.get("duplicates"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> duplicates = (List<Map<String, Object>>) error.get("duplicates");
        assertEquals(2, duplicates.size());
        assertEquals("dup-1", duplicates.get(0).get("id"));
    }

    @Test
    public void shouldReturnNextSteps_whenDuplicatesFound() throws Exception {
        accessor.setFindDuplicatesResult(List.of(
                new DuplicateCandidate("dup-1", "Customer Services", "BusinessActor", 0.92)));

        McpSchema.CallToolResult result = callTool("create-element",
                Map.of("type", "BusinessActor", "name", "Customer Service"));
        Map<String, Object> parsed = parseResult(result);

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) parsed.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("get-element")));
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("force: true")));
    }

    @Test
    public void shouldBypassDuplicateDetection_whenForceTrue() throws Exception {
        accessor.setFindDuplicatesResult(List.of(
                new DuplicateCandidate("dup-1", "Customer Services", "BusinessActor", 0.92)));

        Map<String, Object> result = callAndParse("create-element",
                Map.of("type", "BusinessActor", "name", "Customer Service", "force", true));

        Map<String, Object> entity = getResult(result);
        assertEquals("elem-created-1", entity.get("id"));
        assertEquals("Customer Service", entity.get("name"));
    }

    @Test
    public void shouldCreateNormally_whenNoDuplicatesFound() throws Exception {
        accessor.setFindDuplicatesResult(List.of());

        Map<String, Object> result = callAndParse("create-element",
                Map.of("type", "BusinessActor", "name", "Unique Name"));

        Map<String, Object> entity = getResult(result);
        assertEquals("elem-created-1", entity.get("id"));
        assertEquals("Unique Name", entity.get("name"));
    }

    @Test
    public void shouldNotCallCreate_whenDuplicatesFoundWithoutForce() throws Exception {
        accessor.setFindDuplicatesResult(List.of(
                new DuplicateCandidate("dup-1", "Existing", "BusinessActor", 0.85)));

        callTool("create-element",
                Map.of("type", "BusinessActor", "name", "Similar"));

        assertFalse("createElement should not have been called", accessor.createElementCalled);
    }

    @Test
    public void shouldIncludeSimilarityScores_inDuplicateResponse() throws Exception {
        accessor.setFindDuplicatesResult(List.of(
                new DuplicateCandidate("dup-1", "Customer Services", "BusinessActor", 0.925)));

        McpSchema.CallToolResult result = callTool("create-element",
                Map.of("type", "BusinessActor", "name", "Customer Service"));
        Map<String, Object> parsed = parseResult(result);

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> duplicates = (List<Map<String, Object>>) error.get("duplicates");
        Number score = (Number) duplicates.get(0).get("similarityScore");
        assertEquals(0.93, score.doubleValue(), 0.01);
    }

    // ---- Source traceability tests ----

    @Test
    public void shouldPassSourceToAccessor_whenSourceProvided() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("type", "BusinessActor");
        args.put("name", "Sourced Element");
        args.put("source", Map.of("tool", "import-script", "version", "1.0"));

        callTool("create-element", args);

        assertEquals("import-script", accessor.capturedSource.get("tool"));
        assertEquals("1.0", accessor.capturedSource.get("version"));
    }

    @Test
    public void shouldPassNullSource_whenSourceOmitted() throws Exception {
        callAndParse("create-element",
                Map.of("type", "BusinessActor", "name", "No Source"));

        assertNull("Source should be null when not provided", accessor.capturedSource);
    }

    // ---- Approval mode tests ----

    @Test
    public void shouldReturnProposalResponse_whenApprovalModeEnabled() throws Exception {
        ProposalContext proposalCtx = new ProposalContext("p-42", "Create BusinessActor: Approved Actor", Instant.now());
        accessor.setCreateElementBehavior((sessionId, type, name, doc, props, folderId) -> {
            ElementDto dto = ElementDto.standard(
                    "elem-preview-1", name, type, null, "Business", doc, null);
            return new MutationResult<>(dto, null, proposalCtx);
        });

        Map<String, Object> result = callAndParse("create-element",
                Map.of("type", "BusinessActor", "name", "Approved Actor"));

        Map<String, Object> entity = getResult(result);
        assertNotNull("Should have proposal", entity.get("proposal"));

        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) entity.get("proposal");
        assertEquals("p-42", proposal.get("proposalId"));
        assertEquals("pending", proposal.get("status"));

        assertNotNull("Should have preview", entity.get("preview"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("list-pending-approvals")));
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("list-pending-approvals")));
    }

    @Test
    public void shouldPassSourceAndReturnProposal_whenApprovalModeWithSource() throws Exception {
        ProposalContext proposalCtx = new ProposalContext("p-src-1",
                "Create BusinessActor: Sourced Actor", Instant.now());
        accessor.setCreateElementBehavior((sessionId, type, name, doc, props, folderId) -> {
            ElementDto dto = ElementDto.standard(
                    "elem-src-1", name, type, null, "Business", doc, null);
            return new MutationResult<>(dto, null, proposalCtx);
        });

        Map<String, Object> args = new HashMap<>();
        args.put("type", "BusinessActor");
        args.put("name", "Sourced Actor");
        args.put("source", Map.of("tool", "import-script", "version", "2.0"));

        Map<String, Object> result = callAndParse("create-element", args);

        // Verify source was captured
        assertEquals("import-script", accessor.capturedSource.get("tool"));
        assertEquals("2.0", accessor.capturedSource.get("version"));

        // Verify proposal response structure
        Map<String, Object> entity = getResult(result);
        assertNotNull("Should have proposal", entity.get("proposal"));

        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) entity.get("proposal");
        assertEquals("p-src-1", proposal.get("proposalId"));
        assertEquals("pending", proposal.get("status"));

        assertNotNull("Should have preview", entity.get("preview"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("list-pending-approvals")));
    }

    // ---- Batch mode tests ----

    @Test
    public void shouldReturnBatchInfo_whenCreateElementInBatchMode() throws Exception {
        accessor.setBatchMode(true);

        Map<String, Object> result = callAndParse("create-element",
                Map.of("type", "BusinessActor", "name", "Customer"));

        // In batch mode, result contains batch info
        Map<String, Object> entity = getResult(result);
        assertNotNull("Should have batch info", entity.get("batch"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("batch")));
    }

    // ---- clone-view tests ----

    @Test
    public void shouldRegisterCloneViewTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "clone-view".equals(spec.tool().name()));
        assertTrue("clone-view tool should be registered", found);
    }

    @Test
    public void shouldReturnViewDto_whenCloneViewCalled() throws Exception {
        Map<String, Object> result = callAndParse("clone-view",
                Map.of("sourceViewId", "view-src-1", "newName", "Cloned Diagram"));

        Map<String, Object> entity = getResult(result);
        assertEquals("view-cloned-1", entity.get("id"));
        assertEquals("Cloned Diagram", entity.get("name"));
    }

    @Test
    public void shouldReturnNextSteps_whenCloneViewSucceeds() throws Exception {
        Map<String, Object> result = callAndParse("clone-view",
                Map.of("sourceViewId", "view-src-1", "newName", "Cloned Diagram"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertFalse(nextSteps.isEmpty());
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("get-view-contents")));
    }

    @Test
    public void shouldReturnInvalidParameterError_whenCloneViewSourceViewIdMissing() throws Exception {
        McpSchema.CallToolResult result = callTool("clone-view",
                Map.of("newName", "Missing Source"));
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @Test
    public void shouldReturnInvalidParameterError_whenCloneViewNewNameMissing() throws Exception {
        McpSchema.CallToolResult result = callTool("clone-view",
                Map.of("sourceViewId", "view-src-1"));
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @Test
    public void shouldReturnNotFoundError_whenCloneViewSourceInvalid() throws Exception {
        accessor.setCloneViewBehavior((sessionId, sourceViewId, newName, folderId) -> {
            throw new ModelAccessException("Source view not found: " + sourceViewId,
                    ErrorCode.VIEW_NOT_FOUND);
        });

        McpSchema.CallToolResult result = callTool("clone-view",
                Map.of("sourceViewId", "bad-view-id", "newName", "Cloned"));
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("VIEW_NOT_FOUND", error.get("code"));
    }

    @Test
    public void shouldReturnModelNotLoaded_whenCloneViewWithNoModel() throws Exception {
        StubCreationAccessor noModel = new StubCreationAccessor(false);
        ElementCreationHandler noModelHandler = new ElementCreationHandler(
                noModel, formatter, new CommandRegistry(), null);

        McpSchema.CallToolResult result = noModelHandler.handleCloneView(null,
                McpSchema.CallToolRequest.builder().name("clone-view")
                        .arguments(Map.of("sourceViewId", "v1", "newName", "Clone")).build());
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("MODEL_NOT_LOADED", error.get("code"));
    }

    @Test
    public void shouldHaveMutationPrefix_inCloneViewDescription() {
        boolean found = registry.getToolSpecifications().stream()
                .filter(spec -> "clone-view".equals(spec.tool().name()))
                .anyMatch(spec -> spec.tool().description().startsWith("[Mutation]"));
        assertTrue("clone-view description should start with [Mutation]", found);
    }

    @Test
    public void shouldPassFolderId_whenCloneViewWithFolderId() throws Exception {
        final String[] capturedFolderId = {null};
        accessor.setCloneViewBehavior((sessionId, sourceViewId, newName, folderId) -> {
            capturedFolderId[0] = folderId;
            ViewDto dto = new ViewDto("view-cloned-folder", newName, null, "Custom/Folder");
            return new MutationResult<>(dto, null);
        });

        callAndParse("clone-view",
                Map.of("sourceViewId", "view-src-1", "newName", "Cloned",
                        "folderId", "folder-42"));

        assertEquals("folder-42", capturedFolderId[0]);
    }

    // ---- create-view connectionRouterType tests ----

    @Test
    public void shouldPassConnectionRouterType_whenCreateViewWithRouterType() throws Exception {
        final String[] capturedRouterType = {null};
        accessor.setCreateViewBehavior((sessionId, name, viewpoint, folderId,
                connectionRouterType) -> {
            capturedRouterType[0] = connectionRouterType;
            ViewDto dto = new ViewDto("view-rt-1", name, viewpoint, "manhattan", "Views", null, null);
            return new MutationResult<>(dto, null);
        });

        Map<String, Object> result = callAndParse("create-view",
                Map.of("name", "Manhattan View", "connectionRouterType", "manhattan"));

        assertEquals("manhattan", capturedRouterType[0]);
        Map<String, Object> entity = getResult(result);
        assertEquals("view-rt-1", entity.get("id"));
    }

    @Test
    public void shouldPassNullRouterType_whenCreateViewWithoutRouterType() throws Exception {
        final String[] capturedRouterType = {"SENTINEL"};
        accessor.setCreateViewBehavior((sessionId, name, viewpoint, folderId,
                connectionRouterType) -> {
            capturedRouterType[0] = connectionRouterType;
            ViewDto dto = new ViewDto("view-rt-2", name, viewpoint, "Views");
            return new MutationResult<>(dto, null);
        });

        callAndParse("create-view", Map.of("name", "Default View"));

        assertNull("Router type should be null when omitted", capturedRouterType[0]);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldHaveConnectionRouterTypeInCreateViewSchema() {
        boolean found = registry.getToolSpecifications().stream()
                .filter(spec -> "create-view".equals(spec.tool().name()))
                .anyMatch(spec -> {
                    Map<String, Object> properties = spec.tool().inputSchema().properties();
                    return properties.containsKey("connectionRouterType");
                });
        assertTrue("create-view should have connectionRouterType property", found);
    }

    // ---- relationship semantic-attribute tests ----

    @Test
    @SuppressWarnings("unchecked")
    public void shouldAdvertiseSemanticAttributeParamsInCreateRelationshipSchema() {
        Map<String, Object> properties = registry.getToolSpecifications().stream()
                .filter(spec -> "create-relationship".equals(spec.tool().name()))
                .findFirst()
                .orElseThrow()
                .tool().inputSchema().properties();

        assertTrue("schema should advertise accessType", properties.containsKey("accessType"));
        assertTrue("schema should advertise associationDirected",
                properties.containsKey("associationDirected"));
        assertTrue("schema should advertise influenceStrength",
                properties.containsKey("influenceStrength"));

        // accessType has the closed 4-string enum
        Map<String, Object> accessTypeProp = (Map<String, Object>) properties.get("accessType");
        List<?> enumValues = (List<?>) accessTypeProp.get("enum");
        assertNotNull("accessType should declare an enum", enumValues);
        assertTrue(enumValues.contains("access"));
        assertTrue(enumValues.contains("read"));
        assertTrue(enumValues.contains("write"));
        assertTrue(enumValues.contains("readwrite"));
    }

    @Test
    public void shouldPassAccessTypeThroughHandler_toAccessor() throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("type", "AccessRelationship");
        args.put("sourceId", "src-1");
        args.put("targetId", "tgt-1");
        args.put("accessType", "read");
        callTool("create-relationship", args);
        assertNotNull("handler should have populated semanticAttributes bundle",
                accessor.capturedSemanticAttributes);
        assertEquals("read", accessor.capturedSemanticAttributes.accessType());
        assertNull(accessor.capturedSemanticAttributes.associationDirected());
        assertNull(accessor.capturedSemanticAttributes.influenceStrength());
    }

    /**
     * The provenance trio must reach the accessor. The published schema advertising them is not
     * evidence that a handler read exists — a tool can describe a parameter it never consumes — so
     * this asserts the forwarding rather than the description.
     */
    @Test
    public void shouldPassProvenanceThroughHandler_toAccessor() throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("type", "ServingRelationship");
        args.put("sourceId", "src-1");
        args.put("targetId", "tgt-1");
        args.put("documentation", "Derived from diagram edge 42");
        args.put("properties", Map.of("evidence", "high"));
        args.put("source", Map.of("file", "architecture.drawio"));
        callTool("create-relationship", args);

        assertEquals("documentation must reach the accessor",
                "Derived from diagram edge 42", accessor.capturedRelationshipDocumentation);
        assertNotNull("properties must reach the accessor",
                accessor.capturedRelationshipProperties);
        assertEquals("high", accessor.capturedRelationshipProperties.get("evidence"));
        assertNotNull("source must reach the accessor", accessor.capturedRelationshipSource);
        assertEquals("architecture.drawio", accessor.capturedRelationshipSource.get("file"));
    }

    /**
     * The three parameters must also be advertised, or an agent reading the schema concludes the
     * capability does not exist — which is the failure this pairing exists to prevent.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void shouldAdvertiseProvenanceParameters_onCreateRelationshipSchema() throws Exception {
        Map<String, Object> properties = registry.getToolSpecifications().stream()
                .filter(spec -> "create-relationship".equals(spec.tool().name()))
                .findFirst()
                .orElseThrow()
                .tool().inputSchema().properties();
        assertTrue("documentation must be advertised", properties.containsKey("documentation"));
        assertTrue("properties must be advertised", properties.containsKey("properties"));
        assertTrue("source must be advertised", properties.containsKey("source"));

        Map<String, Object> sourceProp = (Map<String, Object>) properties.get("source");
        assertEquals("object", sourceProp.get("type"));
        assertTrue("source description must name the prefix it applies",
                String.valueOf(sourceProp.get("description")).contains("mcp.source."));
    }

    @Test
    public void shouldRejectAccessTypeOnNonAccessType_atHandler() throws Exception {
        accessor.setCreateRelationshipBehavior((sessionId, type, sourceId, targetId, name) -> {
            throw new ModelAccessException(
                    "accessType only applies to AccessRelationship; got CompositionRelationship.",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Use type='AccessRelationship' to apply accessType, or remove the accessType parameter.",
                    null);
        });

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("type", "CompositionRelationship");
        args.put("sourceId", "src-1");
        args.put("targetId", "tgt-1");
        args.put("accessType", "read");
        McpSchema.CallToolResult result = callTool("create-relationship", args);
        Map<String, Object> parsed = parseResult(result);

        assertTrue("should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue(((String) error.get("message")).contains("accessType"));
    }

    @Test
    public void shouldCaptureAllThreeSemanticAttributeFields() throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("type", "AccessRelationship");
        args.put("sourceId", "src-1");
        args.put("targetId", "tgt-1");
        args.put("accessType", "readwrite");
        args.put("associationDirected", Boolean.TRUE);
        args.put("influenceStrength", "+");
        callTool("create-relationship", args);
        // All 3 fields flow through together; nothing is dropped at the boundary
        assertEquals("readwrite", accessor.capturedSemanticAttributes.accessType());
        assertEquals(Boolean.TRUE, accessor.capturedSemanticAttributes.associationDirected());
        assertEquals("+", accessor.capturedSemanticAttributes.influenceStrength());
    }

    // ---- Helper methods ----

    private McpSchema.CallToolResult callTool(String toolName, Map<String, Object> args)
            throws Exception {
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name(toolName)
                .arguments(args)
                .build();

        return switch (toolName) {
            case "create-element" -> handler.handleCreateElement(null, request);
            case "create-relationship" -> handler.handleCreateRelationship(null, request);
            case "create-view" -> handler.handleCreateView(null, request);
            case "clone-view" -> handler.handleCloneView(null, request);
            default -> throw new IllegalArgumentException("Unknown tool: " + toolName);
        };
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
    interface CreateElementBehavior {
        MutationResult<ElementDto> apply(String sessionId, String type, String name,
                String documentation, Map<String, String> properties, String folderId);
    }

    @FunctionalInterface
    interface CreateRelationshipBehavior {
        MutationResult<RelationshipDto> apply(String sessionId, String type,
                String sourceId, String targetId, String name);
    }

    @FunctionalInterface
    interface CreateViewBehavior {
        MutationResult<ViewDto> apply(String sessionId, String name,
                String viewpoint, String folderId, String connectionRouterType);
    }

    @FunctionalInterface
    interface CloneViewBehavior {
        MutationResult<ViewDto> apply(String sessionId, String sourceViewId,
                String newName, String folderId);
    }

    /**
     * StubCreationAccessor that returns canned DTOs for creation methods.
     * Behavior can be overridden per-test for error scenarios.
     */
    private static class StubCreationAccessor extends BaseTestAccessor {

        private final StubMutationDispatcher dispatcher;
        private boolean batchMode = false;
        private CreateElementBehavior createElementBehavior;
        private CreateRelationshipBehavior createRelationshipBehavior;
        private CreateViewBehavior createViewBehavior;
        private CloneViewBehavior cloneViewBehavior;
        private List<DuplicateCandidate> findDuplicatesResult = List.of();
        boolean createElementCalled = false;
        Map<String, String> capturedSource;
        String capturedSpecialization;
        RelationshipSemanticAttributes capturedSemanticAttributes;
        String capturedRelationshipDocumentation;
        Map<String, String> capturedRelationshipProperties;
        Map<String, String> capturedRelationshipSource;

        StubCreationAccessor() {
            super(true);
            this.dispatcher = new StubMutationDispatcher();
            resetBehaviors();
        }

        StubCreationAccessor(boolean modelLoaded) {
            super(modelLoaded);
            this.dispatcher = modelLoaded ? new StubMutationDispatcher() : null;
            resetBehaviors();
        }

        void setBatchMode(boolean batch) {
            this.batchMode = batch;
        }

        void setCreateElementBehavior(CreateElementBehavior behavior) {
            this.createElementBehavior = behavior;
        }

        void setCreateRelationshipBehavior(CreateRelationshipBehavior behavior) {
            this.createRelationshipBehavior = behavior;
        }

        void setCreateViewBehavior(CreateViewBehavior behavior) {
            this.createViewBehavior = behavior;
        }

        void setCloneViewBehavior(CloneViewBehavior behavior) {
            this.cloneViewBehavior = behavior;
        }

        void setFindDuplicatesResult(List<DuplicateCandidate> result) {
            this.findDuplicatesResult = result;
        }

        private void resetBehaviors() {
            this.createElementBehavior = (sessionId, type, name, doc, props, folderId) -> {
                createElementCalled = true;
                ElementDto dto = ElementDto.standard(
                        "elem-created-1", name, type, null, "Business", doc, null);
                return new MutationResult<>(dto, batchMode ? 1 : null);
            };
            this.createRelationshipBehavior = (sessionId, type, sourceId, targetId, name) -> {
                RelationshipDto dto = new RelationshipDto(
                        "rel-created-1", name, type, sourceId, targetId);
                return new MutationResult<>(dto, batchMode ? 1 : null);
            };
            this.createViewBehavior = (sessionId, name, viewpoint, folderId,
                    connectionRouterType) -> {
                ViewDto dto = new ViewDto("view-created-1", name, viewpoint, "Views");
                return new MutationResult<>(dto, batchMode ? 1 : null);
            };
            this.cloneViewBehavior = (sessionId, sourceViewId, newName, folderId) -> {
                ViewDto dto = new ViewDto("view-cloned-1", newName, null, "Views");
                return new MutationResult<>(dto, batchMode ? 1 : null);
            };
        }

        @Override
        public List<DuplicateCandidate> findDuplicates(String type, String name, String specialization) {
            return findDuplicatesResult;
        }

        @Override
        public MutationResult<ElementDto> createElement(String sessionId, String type,
                String name, String documentation, Map<String, String> properties,
                String folderId, String specialization) {
            capturedSpecialization = specialization;
            return createElementBehavior.apply(sessionId, type, name, documentation,
                    properties, folderId);
        }

        @Override
        public MutationResult<ElementDto> createElement(String sessionId, String type,
                String name, String documentation, Map<String, String> properties,
                String folderId, Map<String, String> source, String specialization) {
            capturedSource = source;
            capturedSpecialization = specialization;
            return createElementBehavior.apply(sessionId, type, name, documentation,
                    properties, folderId);
        }

        @Override
        public MutationResult<RelationshipDto> createRelationship(String sessionId,
                String type, String sourceId, String targetId, String name, String specialization,
                RelationshipSemanticAttributes semanticAttributes,
                String documentation, Map<String, String> properties, Map<String, String> source) {
            capturedSpecialization = specialization;
            capturedSemanticAttributes = semanticAttributes;
            capturedRelationshipDocumentation = documentation;
            capturedRelationshipProperties = properties;
            capturedRelationshipSource = source;
            return createRelationshipBehavior.apply(sessionId, type, sourceId, targetId, name);
        }

        @Override
        public MutationResult<ViewDto> createView(String sessionId, String name,
                String viewpoint, String folderId, String connectionRouterType) {
            return createViewBehavior.apply(sessionId, name, viewpoint, folderId,
                    connectionRouterType);
        }

        @Override
        public MutationResult<ViewDto> cloneView(String sessionId, String sourceViewId,
                String newName, String folderId) {
            return cloneViewBehavior.apply(sessionId, sourceViewId, newName, folderId);
        }

        @Override
        public MutationDispatcher getMutationDispatcher() {
            return dispatcher;
        }
    }

    /**
     * MutationDispatcher subclass that overrides dispatchCommand
     * to avoid Display.syncExec + CommandStack dependencies.
     */
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
