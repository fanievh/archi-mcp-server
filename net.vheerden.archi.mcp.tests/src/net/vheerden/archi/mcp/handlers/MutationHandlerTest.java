package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
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
import net.vheerden.archi.mcp.model.NoModelLoadedException;
import net.vheerden.archi.mcp.model.ProposalContext;
import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.BulkOperationFailure;
import net.vheerden.archi.mcp.response.dto.BulkOperationResult;

import org.eclipse.gef.commands.Command;

/**
 * Tests for {@link MutationHandler} (Story 7-1).
 *
 * <p>Uses a StubAccessor extending {@link BaseTestAccessor} with a stub
 * MutationDispatcher that bypasses Display.syncExec + CommandStack.</p>
 */
public class MutationHandlerTest {

    private ObjectMapper objectMapper;
    private CommandRegistry registry;
    private ResponseFormatter formatter;
    private StubAccessor accessor;
    private MutationHandler handler;

    @Before
    public void setUp() {
        objectMapper = new ObjectMapper();
        registry = new CommandRegistry();
        formatter = new ResponseFormatter();
        accessor = new StubAccessor();
        handler = new MutationHandler(accessor, formatter, registry, null);
        handler.registerTools();
    }

    // ---- Tool registration tests ----

    @Test
    public void shouldRegisterFourTools_whenHandlerRegistered() {
        assertEquals(4, registry.getToolSpecifications().size());
    }

    @Test
    public void shouldRegisterBeginBatchTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "begin-batch".equals(spec.tool().name()));
        assertTrue("begin-batch tool should be registered", found);
    }

    @Test
    public void shouldRegisterEndBatchTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "end-batch".equals(spec.tool().name()));
        assertTrue("end-batch tool should be registered", found);
    }

    @Test
    public void shouldRegisterGetBatchStatusTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "get-batch-status".equals(spec.tool().name()));
        assertTrue("get-batch-status tool should be registered", found);
    }

    @Test
    public void shouldRegisterBulkMutateTool() {
        boolean found = registry.getToolSpecifications().stream()
                .anyMatch(spec -> "bulk-mutate".equals(spec.tool().name()));
        assertTrue("bulk-mutate tool should be registered", found);
    }

    // ---- begin-batch tests ----

    @Test
    public void shouldReturnBatchMode_whenBeginBatchCalled() throws Exception {
        Map<String, Object> result = callAndParse("begin-batch", Map.of());

        Map<String, Object> batchResult = getResult(result);
        assertEquals("BATCH", batchResult.get("mode"));
    }

    @Test
    public void shouldReturnNextSteps_whenBeginBatchCalled() throws Exception {
        Map<String, Object> result = callAndParse("begin-batch", Map.of());

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertFalse(nextSteps.isEmpty());
    }

    @Test
    public void shouldReturnEnvelopeWithMeta_whenBeginBatchCalled() throws Exception {
        Map<String, Object> result = callAndParse("begin-batch", Map.of());

        assertNotNull(result.get("result"));
        assertNotNull(result.get("nextSteps"));
        assertNotNull(result.get("_meta"));
    }

    @Test
    public void shouldReturnBatchAlreadyActive_whenBeginBatchCalledTwice() throws Exception {
        // First begin-batch succeeds
        callTool("begin-batch", Map.of());

        // Second begin-batch should return error
        McpSchema.CallToolResult result = callTool("begin-batch", Map.of());
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("BATCH_ALREADY_ACTIVE", error.get("code"));
    }

    // ---- end-batch tests ----

    @Test
    public void shouldReturnCommitSummary_whenEndBatchWithCommit() throws Exception {
        callTool("begin-batch", Map.of());

        Map<String, Object> result = callAndParse("end-batch", Map.of());

        Map<String, Object> summary = getResult(result);
        assertEquals(0, summary.get("operationCount"));
        assertFalse((Boolean) summary.get("rolledBack"));
    }

    @Test
    public void shouldReturnRollbackSummary_whenEndBatchWithRollback() throws Exception {
        callTool("begin-batch", Map.of());

        Map<String, Object> result = callAndParse("end-batch", Map.of("rollback", true));

        Map<String, Object> summary = getResult(result);
        assertTrue((Boolean) summary.get("rolledBack"));
    }

    @Test
    public void shouldReturnBatchNotActive_whenEndBatchWithoutBegin() throws Exception {
        McpSchema.CallToolResult result = callTool("end-batch", Map.of());
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("BATCH_NOT_ACTIVE", error.get("code"));
    }

    @Test
    public void shouldReturnCommitNextSteps_whenEndBatchCommit() throws Exception {
        callTool("begin-batch", Map.of());
        Map<String, Object> result = callAndParse("end-batch", Map.of());

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("undoable")));
    }

    @Test
    public void shouldReturnRollbackNextSteps_whenEndBatchRollback() throws Exception {
        callTool("begin-batch", Map.of());
        Map<String, Object> result = callAndParse("end-batch", Map.of("rollback", true));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("discarded")));
    }

    // ---- get-batch-status tests ----

    @Test
    public void shouldReturnGuiAttachedMode_whenNotInBatch() throws Exception {
        Map<String, Object> result = callAndParse("get-batch-status", Map.of());

        Map<String, Object> status = getResult(result);
        assertEquals("GUI_ATTACHED", status.get("mode"));
    }

    @Test
    public void shouldReturnBatchMode_whenInBatch() throws Exception {
        callTool("begin-batch", Map.of());

        Map<String, Object> result = callAndParse("get-batch-status", Map.of());

        Map<String, Object> status = getResult(result);
        assertEquals("BATCH", status.get("mode"));
        assertEquals(0, status.get("queuedCount"));
    }

    @Test
    public void shouldReturnGuiAttachedNextSteps_whenNotInBatch() throws Exception {
        Map<String, Object> result = callAndParse("get-batch-status", Map.of());

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("begin-batch")));
    }

    // ---- Model not loaded tests ----

    @Test
    public void shouldReturnModelNotLoaded_whenBeginBatchWithNoModel() throws Exception {
        StubAccessor noModel = new StubAccessor(false);
        MutationHandler noModelHandler = new MutationHandler(noModel, formatter, new CommandRegistry(), null);
        noModelHandler.registerTools();

        McpSchema.CallToolResult result = noModelHandler.handleBeginBatch(null,
                McpSchema.CallToolRequest.builder().name("begin-batch").arguments(Map.of()).build());
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("MODEL_NOT_LOADED", error.get("code"));
    }

    @Test
    public void shouldReturnModelNotLoaded_whenEndBatchWithNoModel() throws Exception {
        StubAccessor noModel = new StubAccessor(false);
        MutationHandler noModelHandler = new MutationHandler(noModel, formatter, new CommandRegistry(), null);

        McpSchema.CallToolResult result = noModelHandler.handleEndBatch(null,
                McpSchema.CallToolRequest.builder().name("end-batch").arguments(Map.of()).build());
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("MODEL_NOT_LOADED", error.get("code"));
    }

    @Test
    public void shouldReturnModelNotLoaded_whenGetBatchStatusWithNoModel() throws Exception {
        StubAccessor noModel = new StubAccessor(false);
        MutationHandler noModelHandler = new MutationHandler(noModel, formatter, new CommandRegistry(), null);

        McpSchema.CallToolResult result = noModelHandler.handleGetBatchStatus(null,
                McpSchema.CallToolRequest.builder().name("get-batch-status").arguments(Map.of()).build());
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("MODEL_NOT_LOADED", error.get("code"));
    }

    // ---- bulk-mutate tests ----

    @Test
    public void shouldReturnSuccess_whenBulkMutateWithValidOps() throws Exception {
        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "create-element",
                        "params", Map.of("type", "BusinessActor", "name", "Actor 1")),
                Map.of("tool", "create-element",
                        "params", Map.of("type", "BusinessProcess", "name", "Process 1"))
        ));

        Map<String, Object> result = callAndParse("bulk-mutate", args);

        Map<String, Object> bulkResult = getResult(result);
        assertEquals(2, bulkResult.get("totalOperations"));
        assertTrue((Boolean) bulkResult.get("allSucceeded"));
        assertTrue((Boolean) bulkResult.get("modelChanged"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ops = (List<Map<String, Object>>) bulkResult.get("operations");
        assertEquals(2, ops.size());
        assertEquals("create-element", ops.get(0).get("tool"));
        assertEquals("created", ops.get(0).get("action"));
    }

    @Test
    public void shouldReturnNextSteps_whenBulkMutateSuccess() throws Exception {
        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "create-element",
                        "params", Map.of("type", "BusinessActor", "name", "Test"))
        ));

        Map<String, Object> result = callAndParse("bulk-mutate", args);

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertFalse(nextSteps.isEmpty());
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("undo")));
    }

    @Test
    public void shouldReturnError_whenBulkMutateMissingOperations() throws Exception {
        McpSchema.CallToolResult result = callTool("bulk-mutate", Map.of());
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @Test
    public void shouldReturnError_whenBulkMutateEmptyOperations() throws Exception {
        Map<String, Object> args = Map.of("operations", List.of());

        McpSchema.CallToolResult result = callTool("bulk-mutate", args);
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue(error.get("message").toString().contains("empty"));
    }

    @Test
    public void shouldReturnError_whenBulkMutateExceedsMaxOps() throws Exception {
        List<Map<String, Object>> ops = new ArrayList<>();
        for (int i = 0; i < 151; i++) {
            ops.add(Map.of("tool", "create-element",
                    "params", Map.of("type", "BusinessActor", "name", "Actor " + i)));
        }

        McpSchema.CallToolResult result = callTool("bulk-mutate", Map.of("operations", ops));
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue(error.get("message").toString().contains("150"));
    }

    @Test
    public void shouldReturnError_whenBulkMutateInvalidOperationFormat() throws Exception {
        Map<String, Object> args = Map.of("operations", List.of("not an object"));

        McpSchema.CallToolResult result = callTool("bulk-mutate", args);
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @Test
    public void shouldReturnError_whenBulkMutateMissingToolField() throws Exception {
        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("params", Map.of("type", "BusinessActor"))
        ));

        McpSchema.CallToolResult result = callTool("bulk-mutate", args);
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @Test
    public void shouldReturnError_whenBulkMutateMissingParamsField() throws Exception {
        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "create-element")
        ));

        McpSchema.CallToolResult result = callTool("bulk-mutate", args);
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @Test
    public void shouldReturnError_whenBulkMutateUnsupportedTool() throws Exception {
        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "search-elements",
                        "params", Map.of("query", "abc"))
        ));

        McpSchema.CallToolResult result = callTool("bulk-mutate", args);
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
    }

    @Test
    public void shouldReturnModelNotLoaded_whenBulkMutateWithNoModel() throws Exception {
        StubAccessor noModel = new StubAccessor(false);
        MutationHandler noModelHandler = new MutationHandler(noModel, formatter, new CommandRegistry(), null);
        noModelHandler.registerTools();

        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "create-element",
                        "params", Map.of("type", "BusinessActor", "name", "Test"))
        ));

        McpSchema.CallToolResult result = noModelHandler.handleBulkMutate(null,
                McpSchema.CallToolRequest.builder().name("bulk-mutate").arguments(args).build());
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("MODEL_NOT_LOADED", error.get("code"));
    }

    @Test
    public void shouldReturnBulkValidationFailed_whenAccessorThrowsBulkValidation() throws Exception {
        accessor.setBulkBehavior(ops -> {
            throw new ModelAccessException(
                    "Operation 1 (create-element): Invalid ArchiMate element type: FakeType",
                    ErrorCode.BULK_VALIDATION_FAILED,
                    "failedOperationIndex=1, failedTool=create-element",
                    "Fix the failed operation and retry the entire bulk-mutate call",
                    null);
        });

        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "create-element",
                        "params", Map.of("type", "BusinessActor", "name", "Valid")),
                Map.of("tool", "create-element",
                        "params", Map.of("type", "FakeType", "name", "Invalid"))
        ));

        McpSchema.CallToolResult result = callTool("bulk-mutate", args);
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("BULK_VALIDATION_FAILED", error.get("code"));
    }

    @Test
    public void shouldReturnMutationFailed_whenAccessorThrowsMutationException() throws Exception {
        accessor.setBulkBehavior(ops -> {
            throw new MutationException("CommandStack not available");
        });

        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "create-element",
                        "params", Map.of("type", "BusinessActor", "name", "Test"))
        ));

        McpSchema.CallToolResult result = callTool("bulk-mutate", args);
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("MUTATION_FAILED", error.get("code"));
    }

    @Test
    public void shouldReturnBatchResponse_whenBulkMutateInBatchMode() throws Exception {
        accessor.setBulkBehavior(ops -> {
            List<BulkOperationResult> opResults = new ArrayList<>();
            opResults.add(new BulkOperationResult(0, "create-element", "created",
                    "elem-1", "BusinessActor", "Actor 1"));
            return new BulkMutationResult(opResults, 1, true, 3);
        });

        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "create-element",
                        "params", Map.of("type", "BusinessActor", "name", "Actor 1"))
        ));

        Map<String, Object> result = callAndParse("bulk-mutate", args);

        Map<String, Object> bulkResult = getResult(result);
        assertFalse((Boolean) bulkResult.get("modelChanged"));
        assertNotNull(bulkResult.get("batch"));

        @SuppressWarnings("unchecked")
        Map<String, Object> batch = (Map<String, Object>) bulkResult.get("batch");
        assertEquals(3, batch.get("sequenceNumber"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("batch")));
    }

    @Test
    public void shouldReturnEntityIds_inBulkMutateResponse() throws Exception {
        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "create-element",
                        "params", Map.of("type", "BusinessActor", "name", "Test"))
        ));

        Map<String, Object> result = callAndParse("bulk-mutate", args);

        Map<String, Object> bulkResult = getResult(result);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ops = (List<Map<String, Object>>) bulkResult.get("operations");
        assertNotNull("entityId should be present", ops.get(0).get("entityId"));
    }

    // ---- bulk-mutate approval mode tests (Story 7-6) ----

    @Test
    public void shouldReturnProposalResponse_whenBulkMutateInApprovalMode() throws Exception {
        ProposalContext proposalCtx = new ProposalContext(
                "p-bulk-1", "Bulk mutation (2 operations)", Instant.now());
        accessor.setBulkBehavior(ops -> {
            List<BulkOperationResult> results = new ArrayList<>();
            results.add(new BulkOperationResult(0, "create-element", "created",
                    "elem-1", "BusinessActor", "Actor 1"));
            results.add(new BulkOperationResult(1, "create-element", "created",
                    "elem-2", "BusinessProcess", "Process 1"));
            return new BulkMutationResult(results, 2, true, null, proposalCtx);
        });

        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "create-element",
                        "params", Map.of("type", "BusinessActor", "name", "Actor 1")),
                Map.of("tool", "create-element",
                        "params", Map.of("type", "BusinessProcess", "name", "Process 1"))
        ));

        Map<String, Object> result = callAndParse("bulk-mutate", args);

        Map<String, Object> bulkResult = getResult(result);
        assertFalse("Model should not have changed (proposal)", (Boolean) bulkResult.get("modelChanged"));
        assertNotNull("Should have proposal", bulkResult.get("proposal"));

        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) bulkResult.get("proposal");
        assertEquals("p-bulk-1", proposal.get("proposalId"));
        assertEquals("pending", proposal.get("status"));

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("decide-mutation")));
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("list-pending-approvals")));
    }

    @Test
    public void shouldIncludeProposalDescription_whenBulkMutateInApprovalMode() throws Exception {
        Instant testTime = Instant.parse("2026-02-24T12:00:00Z");
        ProposalContext proposalCtx = new ProposalContext(
                "p-desc-bulk", "Bulk mutation (1 operation)", testTime);
        accessor.setBulkBehavior(ops -> {
            List<BulkOperationResult> results = new ArrayList<>();
            results.add(new BulkOperationResult(0, "create-element", "created",
                    "elem-1", "BusinessActor", "Actor 1"));
            return new BulkMutationResult(results, 1, true, null, proposalCtx);
        });

        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "create-element",
                        "params", Map.of("type", "BusinessActor", "name", "Actor 1"))
        ));

        Map<String, Object> result = callAndParse("bulk-mutate", args);

        Map<String, Object> bulkResult = getResult(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) bulkResult.get("proposal");
        assertEquals("Bulk mutation (1 operation)", proposal.get("description"));
        assertEquals(testTime.toString(), proposal.get("createdAt"));
    }

    @Test
    public void shouldIncludeOperationDetails_whenBulkMutateInApprovalMode() throws Exception {
        ProposalContext proposalCtx = new ProposalContext(
                "p-ops-bulk", "Bulk mutation (2 operations)", Instant.now());
        accessor.setBulkBehavior(ops -> {
            List<BulkOperationResult> results = new ArrayList<>();
            results.add(new BulkOperationResult(0, "create-element", "created",
                    "elem-1", "BusinessActor", "Actor 1"));
            results.add(new BulkOperationResult(1, "create-element", "created",
                    "elem-2", "BusinessProcess", "Process 1"));
            return new BulkMutationResult(results, 2, true, null, proposalCtx);
        });

        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "create-element",
                        "params", Map.of("type", "BusinessActor", "name", "Actor 1")),
                Map.of("tool", "create-element",
                        "params", Map.of("type", "BusinessProcess", "name", "Process 1"))
        ));

        Map<String, Object> result = callAndParse("bulk-mutate", args);

        Map<String, Object> bulkResult = getResult(result);
        assertEquals(2, bulkResult.get("totalOperations"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ops = (List<Map<String, Object>>) bulkResult.get("operations");
        assertEquals(2, ops.size());
        assertEquals("create-element", ops.get(0).get("tool"));
    }

    // ---- bulk-mutate view tool tests (Story 8-0b) ----

    @Test
    public void shouldAcceptViewTools_whenBulkMutateWithViewOps() throws Exception {
        accessor.setBulkBehavior(ops -> {
            List<BulkOperationResult> results = new ArrayList<>();
            results.add(new BulkOperationResult(0, "add-to-view", "placed",
                    "vo-1", "BusinessActor", "Actor 1"));
            results.add(new BulkOperationResult(1, "add-connection-to-view", "connected",
                    "vc-1", "ServingRelationship", null));
            results.add(new BulkOperationResult(2, "remove-from-view", "removed",
                    "vo-2", "viewObject", null));
            results.add(new BulkOperationResult(3, "update-view-object", "updated",
                    "vo-3", "ApplicationComponent", "App 1"));
            results.add(new BulkOperationResult(4, "update-view-connection", "updated",
                    "vc-2", "ServingRelationship", null));
            return new BulkMutationResult(results, 5, true, null);
        });

        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "add-to-view",
                        "params", Map.of("viewId", "v1", "elementId", "e1")),
                Map.of("tool", "add-connection-to-view",
                        "params", Map.of("viewId", "v1", "relationshipId", "r1",
                                "sourceViewObjectId", "vo1", "targetViewObjectId", "vo2")),
                Map.of("tool", "remove-from-view",
                        "params", Map.of("viewId", "v1", "viewObjectId", "vo3")),
                Map.of("tool", "update-view-object",
                        "params", Map.of("viewObjectId", "vo4")),
                Map.of("tool", "update-view-connection",
                        "params", Map.of("viewConnectionId", "vc1"))
        ));

        Map<String, Object> result = callAndParse("bulk-mutate", args);

        Map<String, Object> bulkResult = getResult(result);
        assertEquals(5, bulkResult.get("totalOperations"));
        assertTrue((Boolean) bulkResult.get("allSucceeded"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ops = (List<Map<String, Object>>) bulkResult.get("operations");
        assertEquals(5, ops.size());
        assertEquals("add-to-view", ops.get(0).get("tool"));
        assertEquals("placed", ops.get(0).get("action"));
        assertEquals("add-connection-to-view", ops.get(1).get("tool"));
        assertEquals("connected", ops.get(1).get("action"));
        assertEquals("remove-from-view", ops.get(2).get("tool"));
        assertEquals("removed", ops.get(2).get("action"));
        assertEquals("update-view-object", ops.get(3).get("tool"));
        assertEquals("updated", ops.get(3).get("action"));
        assertEquals("update-view-connection", ops.get(4).get("tool"));
        assertEquals("updated", ops.get(4).get("action"));
    }

    @Test
    public void shouldIncludeViewNextSteps_whenBulkMutateWithViewTools() throws Exception {
        accessor.setBulkBehavior(ops -> {
            List<BulkOperationResult> results = new ArrayList<>();
            results.add(new BulkOperationResult(0, "add-to-view", "placed",
                    "vo-1", "BusinessActor", "Actor"));
            return new BulkMutationResult(results, 1, true, null);
        });

        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "add-to-view",
                        "params", Map.of("viewId", "v1", "elementId", "e1"))
        ));

        Map<String, Object> result = callAndParse("bulk-mutate", args);

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("Should include view-specific next step",
                nextSteps.stream().anyMatch(s -> s.contains("get-view-contents")));
    }

    @Test
    public void shouldNotIncludeViewNextSteps_whenBulkMutateWithOnlyModelTools() throws Exception {
        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "create-element",
                        "params", Map.of("type", "BusinessActor", "name", "Test"))
        ));

        Map<String, Object> result = callAndParse("bulk-mutate", args);

        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) result.get("nextSteps");
        assertNotNull(nextSteps);
        assertTrue("Should not include view next step for model-only ops",
                nextSteps.stream().noneMatch(s -> s.contains("get-view-contents")));
    }

    @Test
    public void shouldReportViewActionStrings_whenBulkMutateWithViewTools() throws Exception {
        accessor.setBulkBehavior(ops -> {
            List<BulkOperationResult> results = new ArrayList<>();
            for (int i = 0; i < ops.size(); i++) {
                BulkOperation op = ops.get(i);
                String action = switch (op.tool()) {
                    case "add-to-view" -> "placed";
                    case "add-connection-to-view" -> "connected";
                    case "remove-from-view" -> "removed";
                    default -> op.tool().startsWith("update") ? "updated" : "created";
                };
                results.add(new BulkOperationResult(i, op.tool(), action,
                        "id-" + i, "TestType", null));
            }
            return new BulkMutationResult(results, ops.size(), true, null);
        });

        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "add-to-view",
                        "params", Map.of("viewId", "v1", "elementId", "e1")),
                Map.of("tool", "remove-from-view",
                        "params", Map.of("viewId", "v1", "viewObjectId", "vo1"))
        ));

        Map<String, Object> result = callAndParse("bulk-mutate", args);

        Map<String, Object> bulkResult = getResult(result);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ops = (List<Map<String, Object>>) bulkResult.get("operations");
        assertEquals("placed", ops.get(0).get("action"));
        assertEquals("removed", ops.get(1).get("action"));
    }

    // ---- bulk-mutate group/note back-reference and limit tests (Story 9-8) ----

    @Test
    public void shouldAcceptGroupAndNoteTools_whenBulkMutateWithGroupOps() throws Exception {
        accessor.setBulkBehavior(ops -> {
            List<BulkOperationResult> results = new ArrayList<>();
            results.add(new BulkOperationResult(0, "add-group-to-view", "placed",
                    "grp-1", "group", "Test Group"));
            results.add(new BulkOperationResult(1, "add-to-view", "placed",
                    "vo-1", "BusinessActor", "Actor 1"));
            return new BulkMutationResult(results, 2, true, null);
        });

        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "add-group-to-view",
                        "params", Map.of("viewId", "v1", "label", "Test Group")),
                Map.of("tool", "add-to-view",
                        "params", Map.of("viewId", "v1", "elementId", "e1",
                                "parentViewObjectId", "$0.id"))
        ));

        Map<String, Object> result = callAndParse("bulk-mutate", args);

        Map<String, Object> bulkResult = getResult(result);
        assertEquals(2, bulkResult.get("totalOperations"));
        assertTrue((Boolean) bulkResult.get("allSucceeded"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ops = (List<Map<String, Object>>) bulkResult.get("operations");
        assertEquals(2, ops.size());
        assertEquals("add-group-to-view", ops.get(0).get("tool"));
        assertEquals("placed", ops.get(0).get("action"));
        assertEquals("add-to-view", ops.get(1).get("tool"));
        assertEquals("placed", ops.get(1).get("action"));
    }

    @Test
    public void shouldAcceptNestedGroupBackRef_whenBulkMutateWithNestedGroups() throws Exception {
        accessor.setBulkBehavior(ops -> {
            List<BulkOperationResult> results = new ArrayList<>();
            results.add(new BulkOperationResult(0, "add-group-to-view", "placed",
                    "grp-1", "group", "Outer Group"));
            results.add(new BulkOperationResult(1, "add-group-to-view", "placed",
                    "grp-2", "group", "Inner Group"));
            return new BulkMutationResult(results, 2, true, null);
        });

        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "add-group-to-view",
                        "params", Map.of("viewId", "v1", "label", "Outer Group")),
                Map.of("tool", "add-group-to-view",
                        "params", Map.of("viewId", "v1", "label", "Inner Group",
                                "parentViewObjectId", "$0.id"))
        ));

        Map<String, Object> result = callAndParse("bulk-mutate", args);

        Map<String, Object> bulkResult = getResult(result);
        assertEquals(2, bulkResult.get("totalOperations"));
        assertTrue((Boolean) bulkResult.get("allSucceeded"));
    }

    @Test
    public void shouldAcceptNoteInBatchGroup_whenBulkMutateWithNoteInGroup() throws Exception {
        accessor.setBulkBehavior(ops -> {
            List<BulkOperationResult> results = new ArrayList<>();
            results.add(new BulkOperationResult(0, "add-group-to-view", "placed",
                    "grp-1", "group", "Test Group"));
            results.add(new BulkOperationResult(1, "add-note-to-view", "placed",
                    "note-1", "note", null));
            return new BulkMutationResult(results, 2, true, null);
        });

        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "add-group-to-view",
                        "params", Map.of("viewId", "v1", "label", "Test Group")),
                Map.of("tool", "add-note-to-view",
                        "params", Map.of("viewId", "v1", "content", "A note",
                                "parentViewObjectId", "$0.id"))
        ));

        Map<String, Object> result = callAndParse("bulk-mutate", args);

        Map<String, Object> bulkResult = getResult(result);
        assertEquals(2, bulkResult.get("totalOperations"));
        assertTrue((Boolean) bulkResult.get("allSucceeded"));
    }

    @Test
    public void shouldAccept150Operations_whenBulkMutateAtLimit() throws Exception {
        accessor.setBulkBehavior(ops -> {
            List<BulkOperationResult> results = new ArrayList<>();
            for (int i = 0; i < ops.size(); i++) {
                BulkOperation op = ops.get(i);
                results.add(new BulkOperationResult(i, op.tool(), "created",
                        "id-" + i, "BusinessActor", "Actor " + i));
            }
            return new BulkMutationResult(results, ops.size(), true, null);
        });

        List<Map<String, Object>> ops = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            ops.add(Map.of("tool", "create-element",
                    "params", Map.of("type", "BusinessActor", "name", "Actor " + i)));
        }

        Map<String, Object> args = Map.of("operations", ops);
        Map<String, Object> result = callAndParse("bulk-mutate", args);

        Map<String, Object> bulkResult = getResult(result);
        assertEquals(150, bulkResult.get("totalOperations"));
        assertTrue((Boolean) bulkResult.get("allSucceeded"));
    }

    @Test
    public void shouldRejectBulkMutate_whenExceeds150Operations() throws Exception {
        List<Map<String, Object>> ops = new ArrayList<>();
        for (int i = 0; i < 151; i++) {
            ops.add(Map.of("tool", "create-element",
                    "params", Map.of("type", "BusinessActor", "name", "Actor " + i)));
        }

        McpSchema.CallToolResult result = callTool("bulk-mutate", Map.of("operations", ops));
        Map<String, Object> parsed = parseResult(result);

        assertTrue("Should be an error", result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) parsed.get("error");
        assertEquals("INVALID_PARAMETER", error.get("code"));
        assertTrue(error.get("message").toString().contains("150"));
    }

    // ---- bulk-mutate continueOnError response formatting tests (Story 11-9 code review) ----

    @Test
    public void shouldFormatPartialFailureResponse_whenBulkMutateWithFailures() throws Exception {
        accessor.setBulkBehavior(ops -> {
            List<BulkOperationResult> succeeded = new ArrayList<>();
            succeeded.add(new BulkOperationResult(0, "create-element", "created",
                    "elem-1", "BusinessActor", "Actor A"));
            succeeded.add(new BulkOperationResult(2, "create-element", "created",
                    "elem-2", "BusinessProcess", "Process B"));

            List<BulkOperationFailure> failed = List.of(
                    new BulkOperationFailure(1, "create-element", "INVALID_PARAMETER",
                            "Unknown ArchiMate type: FakeType",
                            "Use a valid type like ApplicationComponent"));

            return new BulkMutationResult(succeeded, failed, 3, false, null, null);
        });

        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "create-element",
                        "params", Map.of("type", "BusinessActor", "name", "Actor A")),
                Map.of("tool", "create-element",
                        "params", Map.of("type", "FakeType", "name", "Fails")),
                Map.of("tool", "create-element",
                        "params", Map.of("type", "BusinessProcess", "name", "Process B"))));

        McpSchema.CallToolResult callResult = callTool("bulk-mutate", args);
        Map<String, Object> parsed = parseResult(callResult);
        Map<String, Object> bulkResult = getResult(parsed);

        // Verify partial-failure response structure
        assertFalse("Should not be marked as error when some operations succeed",
                callResult.isError());
        assertEquals(3, bulkResult.get("totalOperations"));
        assertEquals(2, bulkResult.get("succeededCount"));
        assertEquals(1, bulkResult.get("failedCount"));
        assertFalse((Boolean) bulkResult.get("allSucceeded"));
        assertTrue((Boolean) bulkResult.get("modelChanged"));

        // Verify 'succeeded' key (not 'operations') when failures present
        assertNotNull("Should use 'succeeded' key when failures present",
                bulkResult.get("succeeded"));
        assertNull("Should not use 'operations' key when failures present",
                bulkResult.get("operations"));

        // Verify 'failed' array
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failedOps =
                (List<Map<String, Object>>) bulkResult.get("failed");
        assertNotNull(failedOps);
        assertEquals(1, failedOps.size());
        assertEquals(1, failedOps.get(0).get("index"));
        assertEquals("INVALID_PARAMETER", failedOps.get(0).get("errorCode"));
        assertNotNull(failedOps.get(0).get("suggestedCorrection"));

        // Verify nextSteps include failure guidance
        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) parsed.get("nextSteps");
        assertTrue("nextSteps should mention failed operations",
                nextSteps.stream().anyMatch(s -> s.contains("1 operations failed")));
        assertTrue("nextSteps should mention undo for succeeded",
                nextSteps.stream().anyMatch(s -> s.contains("2 succeeded operations")));
    }

    @Test
    public void shouldFormatAllFailedResponse_whenBulkMutateAllFail() throws Exception {
        accessor.setBulkBehavior(ops -> {
            List<BulkOperationFailure> failed = List.of(
                    new BulkOperationFailure(0, "create-element", "INVALID_PARAMETER",
                            "Unknown type: FakeType1", null),
                    new BulkOperationFailure(1, "create-element", "INVALID_PARAMETER",
                            "Unknown type: FakeType2", null));

            return new BulkMutationResult(List.of(), failed, 2, false, null, null);
        });

        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "create-element",
                        "params", Map.of("type", "FakeType1", "name", "Fails 1")),
                Map.of("tool", "create-element",
                        "params", Map.of("type", "FakeType2", "name", "Fails 2"))));

        McpSchema.CallToolResult callResult = callTool("bulk-mutate", args);
        Map<String, Object> parsed = parseResult(callResult);
        Map<String, Object> bulkResult = getResult(parsed);

        // Should be marked as error when all operations fail
        assertTrue("Should be marked as error when all operations fail",
                callResult.isError());
        assertEquals(0, bulkResult.get("succeededCount"));
        assertEquals(2, bulkResult.get("failedCount"));
        assertFalse((Boolean) bulkResult.get("modelChanged"));

        // Verify nextSteps do NOT include verification guidance for created elements
        @SuppressWarnings("unchecked")
        List<String> nextSteps = (List<String>) parsed.get("nextSteps");
        assertTrue("nextSteps should mention failures",
                nextSteps.stream().anyMatch(s -> s.contains("operations failed")));
        assertFalse("nextSteps should NOT mention verifying elements when none were created",
                nextSteps.stream().anyMatch(s -> s.contains("get-element")));
        assertFalse("nextSteps should NOT mention undo when nothing succeeded",
                nextSteps.stream().anyMatch(s -> s.contains("can be undone")));
    }

    // ---- Response envelope structure tests ----

    @Test
    public void shouldHaveStandardEnvelope_whenBeginBatch() throws Exception {
        Map<String, Object> result = callAndParse("begin-batch", Map.of());

        assertNotNull("result key should exist", result.get("result"));
        assertNotNull("nextSteps key should exist", result.get("nextSteps"));
        assertNotNull("_meta key should exist", result.get("_meta"));

        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) result.get("_meta");
        assertNotNull("modelVersion should exist", meta.get("modelVersion"));
    }

    // ---- Helper methods ----

    private McpSchema.CallToolResult callTool(String toolName, Map<String, Object> args)
            throws Exception {
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name(toolName)
                .arguments(args)
                .build();

        return switch (toolName) {
            case "begin-batch" -> handler.handleBeginBatch(null, request);
            case "end-batch" -> handler.handleEndBatch(null, request);
            case "get-batch-status" -> handler.handleGetBatchStatus(null, request);
            case "bulk-mutate" -> handler.handleBulkMutate(null, request);
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

    /**
     * StubAccessor with a stub MutationDispatcher that bypasses Display.syncExec.
     * Supports configurable executeBulk behavior for testing handler-level logic.
     */
    private static class StubAccessor extends BaseTestAccessor {

        private final StubMutationDispatcher dispatcher;
        private java.util.function.Function<List<BulkOperation>, BulkMutationResult> bulkBehavior;

        StubAccessor() {
            super(true);
            this.dispatcher = new StubMutationDispatcher();
            // Default: return a simple success result
            this.bulkBehavior = ops -> {
                List<BulkOperationResult> results = new ArrayList<>();
                for (int i = 0; i < ops.size(); i++) {
                    BulkOperation op = ops.get(i);
                    String action = op.tool().startsWith("update") ? "updated" : "created";
                    results.add(new BulkOperationResult(i, op.tool(), action,
                            "generated-id-" + i, "BusinessActor", "Test " + i));
                }
                return new BulkMutationResult(results, ops.size(), true, null);
            };
        }

        StubAccessor(boolean modelLoaded) {
            super(modelLoaded);
            this.dispatcher = modelLoaded ? new StubMutationDispatcher() : null;
        }

        void setBulkBehavior(
                java.util.function.Function<List<BulkOperation>, BulkMutationResult> behavior) {
            this.bulkBehavior = behavior;
        }

        @Override
        public BulkMutationResult executeBulk(String sessionId, List<BulkOperation> operations,
                String description, boolean continueOnError) {
            if (!isModelLoaded()) {
                throw new NoModelLoadedException();
            }
            return bulkBehavior.apply(operations);
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
