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
import net.vheerden.archi.mcp.response.dto.AnchorPointDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.BulkOperationFailure;
import net.vheerden.archi.mcp.response.dto.BulkOperationResult;
import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.RelationshipDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;

import org.eclipse.gef.commands.Command;

/**
 * Tests for {@link MutationHandler}.
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

    // ---- Response-envelope documentation pins ----

    private String descriptionOf(String toolName) {
        return registry.getToolSpecifications().stream()
                .filter(spec -> toolName.equals(spec.tool().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not found: " + toolName))
                .tool().description();
    }

    @Test
    public void endBatch_descriptionShouldDocumentSkippedOperations() {
        String desc = descriptionOf("end-batch");
        assertTrue("must name the field carrying declined operations",
                desc.contains("skippedOperations"));
        assertTrue("must say the field is absent when nothing was skipped",
                desc.contains("absent when"));
        // operationCount counts what was QUEUED, so a batch with a skip reports a count
        // higher than the number of changes actually applied. An agent reconciling the two
        // without being told this reads it as a lost operation.
        assertTrue("must reconcile operationCount against skips",
                desc.contains("operationCount"));
        // "atomically" was removed deliberately: a skipped operation means the batch is no
        // longer all-or-nothing, and the word would now be a false promise.
        assertFalse("must not promise atomicity it no longer provides",
                desc.contains("atomically"));
    }

    /**
     * The atomicity promise appeared in five wire-visible places, not one. Fixing only
     * end-batch's own description left it live on three paths an agent reads constantly —
     * including two nextSteps hints returned on every call — so all of them are pinned here.
     */
    @Test
    public void batchTools_mustNotPromiseAtomicityAnywhereOnTheWire() throws Exception {
        assertFalse("begin-batch's description must not promise atomicity",
                descriptionOf("begin-batch").contains("atomically"));
        assertFalse("bulk-mutate's operations schema must not promise atomicity",
                descriptionOf("bulk-mutate").contains("atomically"));

        // nextSteps hints are returned on every call, so a stale promise there is read more
        // often than the tool description itself.
        assertFalse("begin-batch's nextSteps must not promise atomicity",
                String.valueOf(callAndParse("begin-batch", Map.of())).contains("atomically"));
        assertFalse("get-batch-status's nextSteps must not promise atomicity",
                String.valueOf(callAndParse("get-batch-status", Map.of())).contains("atomically"));
        callAndParse("end-batch", Map.of("rollback", true));
    }

    /**
     * The wire, not the DTO. This response map is assembled field by field, so a component
     * added to {@code BulkMutationResult} reaches the agent only if it is copied across
     * explicitly — a DTO-level assertion passes while the field is missing from the JSON.
     * That is exactly how this shipped broken once.
     */
    @Test
    public void bulkMutate_shouldPutSkippedOperationsOnTheWire() throws Exception {
        accessor.setBulkBehavior(ops -> BulkMutationResult.of(
                List.of(new BulkOperationResult(0, "delete-folder", "deleted",
                        "folder-1", "Folder", "Doomed")),
                List.of(), 1, null,
                List.of("Delete folder: folder-1 — it was not empty when the changes were applied")));

        Map<String, Object> parsed = callAndParse("bulk-mutate", Map.of("operations", List.of(
                Map.of("tool", "delete-folder", "params", Map.of("folderId", "folder-1")))));
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) parsed.get("result");

        assertEquals("A declined operation must clear allSucceeded", false, res.get("allSucceeded"));
        @SuppressWarnings("unchecked")
        List<String> skipped = (List<String>) res.get("skippedOperations");
        assertNotNull("skippedOperations must reach the wire, not just the DTO", skipped);
        assertEquals(1, skipped.size());
        assertTrue(skipped.get(0).contains("not empty"));
    }

    @Test
    public void bulkMutate_shouldOmitSkippedOperations_whenNothingDeclined() throws Exception {
        accessor.setBulkBehavior(ops -> BulkMutationResult.of(
                List.of(new BulkOperationResult(0, "create-element", "created",
                        "e-1", "BusinessActor", "Fine")),
                List.of(), 1, null, List.of()));

        Map<String, Object> parsed = callAndParse("bulk-mutate", Map.of("operations", List.of(
                Map.of("tool", "create-element",
                        "params", Map.of("type", "BusinessActor", "name", "Fine")))));
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) parsed.get("result");

        assertEquals(true, res.get("allSucceeded"));
        assertNull("The field must stay absent in the normal case",
                res.get("skippedOperations"));
    }

    @Test
    public void bulkMutate_descriptionShouldDocumentSkippedOperations() {
        String desc = descriptionOf("bulk-mutate");
        assertTrue("must name the field carrying declined operations",
                desc.contains("skippedOperations"));
        // Per-operation entries are built before dispatch, so one can report an action that
        // never happened. The agent must be told which field is authoritative.
        assertTrue("must warn that per-operation entries predate the changes being applied",
                desc.contains("before"));
        assertTrue("must tie a skip to allSucceeded", desc.contains("allSucceeded"));
    }

    /**
     * The description used to state a COUNT beside the field list it introduces, which made the
     * count load-bearing rather than decorative: an agent that reads "four" and finds three assumes
     * it missed one. Every field added since cost a second edit to a number nothing computes, and
     * one of them would eventually not be made — so the count is gone rather than corrected again,
     * and what is pinned here is that each field is NAMED and that no superseded count survives to
     * miscount the list beside it.
     */
    @Test
    public void bulkMutate_descriptionShouldDocumentTheConnectionReport() {
        String desc = descriptionOf("bulk-mutate");
        assertTrue("must name the field carrying a connection's post-dispatch state",
                desc.contains("effectiveConnection"));
        assertTrue("must name the fields carrying a concept's post-dispatch state",
                desc.contains("effectiveRelationship") && desc.contains("effectiveElement"));
        assertTrue("must name the field carrying the container a view object sits in, which is "
                + "the origin its effectiveBounds are measured from",
                desc.contains("parentViewObjectId"));
        assertFalse("no count may stand beside the field list: it is maintained by hand, nothing "
                + "computes it, and a stale one tells an agent it missed a field",
                desc.contains("Three per-operation fields") || desc.contains("All three are")
                        || desc.contains("Four per-operation fields")
                        || desc.contains("Each of the four is omitted")
                        || desc.contains("Six per-operation fields")
                        || desc.contains("Each of the six is omitted")
                        || desc.contains("Seven per-operation fields")
                        || desc.contains("Each of the seven is omitted"));
        assertTrue("must say which attributes the relationship report is the answer for, or an "
                + "agent cannot tell that setting one is now confirmed rather than merely accepted",
                desc.contains("accessType, associationDirected or influenceStrength"));
        assertTrue("must state the omit-at-default rule, since a subtype that cannot hold an "
                + "attribute reports nothing and silence there is not a failure",
                desc.contains("cannot hold an attribute omits it"));
        // The gate is "does the entry describe its entity", and describe() now has a branch for a
        // prepared MoveResultDto — so a moved concept passes it and does carry a report. The
        // superseded sentence said the opposite, and an agent that reads it declines to look for a
        // field the server is sending.
        assertTrue("must carry the gate, not just the type rule",
                desc.contains("entity is, which every operation on a concept now does"));
        assertFalse("the superseded gate sentence must not survive alongside the current one",
                desc.contains("move-to-folder names no entity today")
                        || desc.contains("any folder or model update — omit it entirely"));
        assertTrue("must say the value is read after the write rather than echoed, or an agent "
                + "cannot tell it apart from the parameters it sent",
                desc.contains("read from the model"));
        // update-view-connection is dispatched as a bare UpdateViewConnectionCommand, which does
        // not implement CommitSkippableCommand — so a restyle CANNOT decline, and prose describing
        // what a declined restyle reports would describe a state the server cannot produce. An
        // unconditional sentence is a claim about every state, including the unreachable ones.
        assertFalse("must not describe what a DECLINED update reports — no connection update can "
                + "decline, so that sentence would be an unreachable claim",
                desc.contains("an update that declined"));
    }

    @Test
    public void beginBatch_descriptionShouldDocumentBatchEnvelopeReshape() {
        String desc = descriptionOf("begin-batch");
        assertTrue("must state where the result moves", desc.contains("result.preview"));
        assertTrue("must name the batch sibling", desc.contains("result.batch"));
        assertTrue("must name the only informative batch field",
                desc.contains("sequenceNumber"));
        // batch.success is hardcoded true and batch.description is a fixed string, so a caller
        // must not branch on them.
        assertTrue("must mark the other batch fields as carrying no information",
                desc.contains("are constants"));
        // BatchSummaryDto has no id field, so preview is the sole source of created ids.
        assertTrue("must state end-batch reports no entity IDs",
                desc.contains("never entity IDs"));
        // bulk-mutate keeps its normal shape and the two discovery create tools put the element
        // beside preview — documenting the reshape as unconditional would be false for all three.
        assertTrue("must carry the bulk-mutate exception",
                desc.contains("bulk-mutate has no preview"));
        assertTrue("must carry the discovery-tool exception",
                desc.contains("search-and-create put the element at result.element"));
        // The approval gate is checked BEFORE dispatchOrQueue at every accessor call site, and
        // formatMutationResponse tests isProposal() first — so with the gate on, a mutation
        // inside an open batch is never queued and never gets a result.batch sibling. Claiming
        // the batch reshape unconditionally would be false in exactly that state.
        assertTrue("must state the approval gate outranks batching",
                desc.contains("approval gate takes precedence over batching"));
        assertTrue("must state the mutation is not queued while the gate is on",
                desc.contains("not queued at all"));
    }

    @Test
    public void getBatchStatus_descriptionShouldDocumentConditionalFields() {
        String desc = descriptionOf("get-batch-status");
        assertTrue("must name the literal mode values", desc.contains("'GUI_ATTACHED'"));
        assertTrue("must scope the queue fields to batch mode",
                desc.contains("only in batch mode"));
        // approvalRequired is Boolean.TRUE or null — NON_NULL drops it, so it is never false.
        assertTrue("must scope approvalRequired to the gate being on",
                desc.contains("approvalRequired only when"));
        assertTrue("must state approvalRequired is absent rather than false",
                desc.contains("the field is absent instead"));
        // pendingApprovalCount is null when the queue is empty, not 0.
        assertTrue("must scope pendingApprovalCount to a non-empty queue",
                desc.contains("pendingApprovalCount only when"));
        assertTrue("must state pendingApprovalCount is never zero", desc.contains("never 0"));
    }

    @Test
    public void bulkMutate_descriptionShouldDocumentEnvelopeDivergence() {
        String desc = descriptionOf("bulk-mutate");
        assertTrue("must state bulk-mutate does not use preview",
                desc.contains("does not use result.preview"));
        assertTrue("must state modelChanged is false in both deferred modes",
                desc.contains("modelChanged is false in "));
        // validOperationCount/failedValidationCount are added only when hasFailures.
        assertTrue("must gate the proposal counts on failed validation",
                desc.contains("only when some operations failed validation"));
        // bulk's rebuild handle returns the already-reviewed compound rather than re-invoking
        // prepareXxx, so its approved ids are stable — the INVERSE of the single-tool rule that
        // get-model-info states. Routing a reader here without saying so would mislead them.
        assertTrue("must state approved bulk ids are stable",
                desc.contains("approved bulk are stable"));
        assertTrue("must give the reason bulk ids survive approval",
                desc.contains("applies the reviewed compound itself"));
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

    // ---- bulk-mutate approval mode tests ----

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
        assertTrue(nextSteps.stream().anyMatch(s -> s.contains("list-pending-approvals")));
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

    // ---- bulk-mutate view tool tests ----

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

    // ---- bulk-mutate group/note back-reference and limit tests ----

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

    // ---- bulk-mutate continueOnError response formatting tests ----

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

    /**
     * The partial-success path publishes rows too, and what those rows share is published beside
     * them — in the result, where they are, rather than in an error object this envelope does not
     * have.
     *
     * <p>This is the one of the four row-building sites where many rows reach a caller on a
     * <em>successful</em> call, so a fix applied only to the refusal builders would leave it
     * repeating. It is also the only one reachable without a live model, which is what lets it be
     * asserted in the lane that runs on every change.</p>
     */
    @Test
    public void shouldCarryASharedCorrectionOnce_whenContinueOnErrorReportsManyFailures()
            throws Exception {
        String shared = "Use get-view-contents to find valid view object IDs on the view, and "
                + "back-reference the operation that placed the object rather than the operation "
                + "that created the concept it draws.";
        accessor.setBulkBehavior(ops -> {
            List<BulkOperationResult> succeeded = List.of(new BulkOperationResult(
                    0, "create-element", "created", "elem-1", "BusinessActor", "Actor A"));
            List<BulkOperationFailure> failed = List.of(
                    new BulkOperationFailure(1, "update-view-object", "VIEW_OBJECT_NOT_FOUND",
                            "View object not found: ghost-a", shared),
                    new BulkOperationFailure(2, "update-view-object", "VIEW_OBJECT_NOT_FOUND",
                            "View object not found: ghost-b", shared),
                    new BulkOperationFailure(3, "update-view-object", "VIEW_OBJECT_NOT_FOUND",
                            "View object not found: ghost-c", shared));
            return new BulkMutationResult(succeeded, failed, 4, false, null, null);
        });

        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "create-element",
                        "params", Map.of("type", "BusinessActor", "name", "Actor A")),
                Map.of("tool", "update-view-object",
                        "params", Map.of("viewId", "v", "viewObjectId", "ghost-a")),
                Map.of("tool", "update-view-object",
                        "params", Map.of("viewId", "v", "viewObjectId", "ghost-b")),
                Map.of("tool", "update-view-object",
                        "params", Map.of("viewId", "v", "viewObjectId", "ghost-c"))),
                "continueOnError", true);

        McpSchema.CallToolResult callResult = callTool("bulk-mutate", args);
        Map<String, Object> parsed = parseResult(callResult);
        Map<String, Object> bulkResult = getResult(parsed);

        assertFalse("a partial success is not an error", callResult.isError());
        assertNull("the dictionary belongs beside the rows, and the rows are in the result",
                parsed.get("error"));

        @SuppressWarnings("unchecked")
        Map<String, Object> corrections = (Map<String, Object>) bulkResult.get("corrections");
        assertNotNull("the shared correction must be published once, beside the rows", corrections);
        assertEquals("one shared string, one entry", 1, corrections.size());
        assertEquals(shared, corrections.get("VIEW_OBJECT_NOT_FOUND"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failedOps =
                (List<Map<String, Object>>) bulkResult.get("failed");
        assertEquals(3, failedOps.size());
        for (Map<String, Object> row : failedOps) {
            assertNull("no row may still carry its own copy: " + row,
                    row.get("suggestedCorrection"));
            assertEquals("and every row must name the one that was kept",
                    "VIEW_OBJECT_NOT_FOUND", row.get("correctionRef"));
            assertEquals("reassembly must return exactly what the row used to carry",
                    shared, corrections.get(row.get("correctionRef")));
            assertNotNull("the row keeps its own account of what went wrong", row.get("message"));
        }
    }

    /**
     * One failure shares a string with nobody, so nothing is carried away from it however long its
     * correction is.
     *
     * <p>The refusal builders never reach the projection with a single failure — they publish no
     * row array at all below two — so this partial-success path is the only place a lone failure
     * is projected, and therefore the only place that can prove the rule is in the projection
     * rather than only in the guards around it. A dictionary emitted for a group of one would move
     * a correction off the single row that carries it and make the caller resolve a reference to
     * read what used to be in front of them, for a saving of nothing.</p>
     */
    @Test
    public void shouldLeaveALoneFailuresCorrectionOnItsRow_howeverLongItIs() throws Exception {
        String long_ = "Use get-view-contents to find valid view object IDs and connection IDs on "
                + "the view. A back-reference resolves only to something addressable on a view: a "
                + "note, group, element view object or connection this call created.";
        assertTrue("the fixture must be past the length a dictionary would pay for",
                long_.length() > 120);
        accessor.setBulkBehavior(ops -> new BulkMutationResult(
                List.of(new BulkOperationResult(0, "create-element", "created",
                        "elem-1", "BusinessActor", "Actor A")),
                List.of(new BulkOperationFailure(1, "update-view-object", "VIEW_OBJECT_NOT_FOUND",
                        "View object not found: ghost-a", long_)),
                2, false, null, null));

        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "create-element",
                        "params", Map.of("type", "BusinessActor", "name", "Actor A")),
                Map.of("tool", "update-view-object",
                        "params", Map.of("viewId", "v", "viewObjectId", "ghost-a"))),
                "continueOnError", true);

        Map<String, Object> bulkResult = getResult(parseResult(callTool("bulk-mutate", args)));
        assertNull("one failure shares nothing, so nothing is carried away from it",
                bulkResult.get("corrections"));
        assertNull(bulkResult.get("messages"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failedOps =
                (List<Map<String, Object>>) bulkResult.get("failed");
        assertEquals(1, failedOps.size());
        assertEquals("the row keeps its own correction, whole and in place",
                long_, failedOps.get(0).get("suggestedCorrection"));
        assertNull(failedOps.get(0).get("correctionRef"));
    }

    /**
     * Rows that share a long ending with no sentence boundary in it are left alone.
     *
     * <p>Splitting needs somewhere to split. When the shared ending runs back past every full stop
     * there is no point at which a head could be cut and still read as something a client can take
     * at face value, so the projection declines rather than cutting mid-clause — the conservative
     * direction, at the cost of a saving it could otherwise have taken.</p>
     *
     * <p>Built here from hand-written failures because no live refusal produces this shape: the
     * branch exists for strings the tools do not currently throw, and a rule with no fixture
     * reaching it is a rule nothing is holding.</p>
     */
    @Test
    public void shouldDeclineToSplit_whenTheSharedEndingHasNoSentenceBoundary() throws Exception {
        String tail = " and the identifier must be one this view already carries rather than one "
                + "the caller expects it to carry after some later operation has run";
        assertTrue("the fixture's shared ending must be past the length gate", tail.length() > 80);
        assertFalse("and must contain no sentence boundary to split on", tail.contains(". "));
        accessor.setBulkBehavior(ops -> new BulkMutationResult(
                List.of(),
                List.of(new BulkOperationFailure(0, "update-view-object", "VIEW_OBJECT_NOT_FOUND",
                                "Object ghost-a was not found" + tail, null),
                        new BulkOperationFailure(1, "update-view-object", "VIEW_OBJECT_NOT_FOUND",
                                "Object ghost-b was not found" + tail, null)),
                2, false, null, null));

        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "update-view-object",
                        "params", Map.of("viewId", "v", "viewObjectId", "ghost-a")),
                Map.of("tool", "update-view-object",
                        "params", Map.of("viewId", "v", "viewObjectId", "ghost-b"))),
                "continueOnError", true);

        Map<String, Object> bulkResult = getResult(parseResult(callTool("bulk-mutate", args)));
        assertNull("nothing may be carried away when there is nowhere to cut",
                bulkResult.get("messages"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failedOps =
                (List<Map<String, Object>>) bulkResult.get("failed");
        for (Map<String, Object> row : failedOps) {
            assertNull("so every row keeps its whole message: " + row, row.get("messageRef"));
            assertTrue(String.valueOf(row.get("message")).endsWith(tail));
        }
    }

    /**
     * A failure carrying no error code still produces a dictionary a JSON encoder will take.
     *
     * <p>The error code is three things at once here — the group a row is bucketed into, the value
     * of its reference, and the key of the dictionary entry — and they have to be the same string.
     * Reading the group from a sanitised value and the reference from the raw one leaves a row
     * naming a key the dictionary does not hold; worse, a null code written as a map key is
     * something Jackson refuses outright, so the refusal would fail while being serialised and the
     * caller would get an internal error in place of a report that used to work. Losing the whole
     * response is a worse outcome than the duplication this mechanism removes.</p>
     *
     * <p>The interface permits a null code and nothing else holds that, which is why this is
     * pinned from hand-written failures: both collectors currently default a missing code to
     * {@code UNKNOWN}, so no live path reaches it and no live path guards it either.</p>
     */
    @Test
    public void shouldStillPublishAUsableDictionary_whenFailuresCarryNoErrorCode() throws Exception {
        String shared = "Use get-view-contents to find valid view object IDs on the view, and "
                + "back-reference the operation that placed the object rather than the one that "
                + "created the concept it draws.";
        accessor.setBulkBehavior(ops -> new BulkMutationResult(
                List.of(),
                List.of(new BulkOperationFailure(0, "update-view-object", null,
                                "View object not found: ghost-a", shared),
                        new BulkOperationFailure(1, "update-view-object", null,
                                "View object not found: ghost-b", shared)),
                2, false, null, null));

        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "update-view-object",
                        "params", Map.of("viewId", "v", "viewObjectId", "ghost-a")),
                Map.of("tool", "update-view-object",
                        "params", Map.of("viewId", "v", "viewObjectId", "ghost-b"))),
                "continueOnError", true);

        // Serialising at all is half the assertion: a null map key throws here, not later.
        McpSchema.CallToolResult callResult = callTool("bulk-mutate", args);
        Map<String, Object> bulkResult = getResult(parseResult(callResult));

        @SuppressWarnings("unchecked")
        Map<String, Object> corrections = (Map<String, Object>) bulkResult.get("corrections");
        assertNotNull("the two rows share a remedy, so it is carried once", corrections);
        assertFalse("and no entry may be keyed by null: " + corrections.keySet(),
                corrections.containsKey(null));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failedOps =
                (List<Map<String, Object>>) bulkResult.get("failed");
        for (Map<String, Object> row : failedOps) {
            Object ref = row.get("correctionRef");
            assertNotNull("every row must name an entry, not null: " + row, ref);
            assertEquals("and the entry it names must resolve to what the row used to carry",
                    shared, corrections.get(ref));
        }
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

    // ---- bulk-mutate flows labelExpression through update-view-object ----

    @Test
    public void shouldFlowLabelExpression_inBulkUpdateViewObject() throws Exception {
        // Capture the operations the handler hands to the accessor so we can
        // assert the labelExpression param survived the JSON → BulkOperation
        // conversion intact.
        final java.util.concurrent.atomic.AtomicReference<List<BulkOperation>> capturedOps =
                new java.util.concurrent.atomic.AtomicReference<>();
        accessor.setBulkBehavior(ops -> {
            capturedOps.set(ops);
            List<BulkOperationResult> results = new ArrayList<>();
            results.add(new BulkOperationResult(0, "update-view-object", "updated",
                    "vo-1", "BusinessActor", "Test"));
            return new BulkMutationResult(results, 1, true, null);
        });

        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "update-view-object",
                        "params", Map.of(
                                "viewObjectId", "vo-1",
                                "labelExpression", "${name}"))
        ));

        Map<String, Object> result = callAndParse("bulk-mutate", args);
        Map<String, Object> bulkResult = getResult(result);
        assertTrue("Bulk op should succeed", (Boolean) bulkResult.get("allSucceeded"));

        List<BulkOperation> ops = capturedOps.get();
        assertNotNull("Bulk operations must reach the accessor", ops);
        assertEquals(1, ops.size());
        assertEquals("update-view-object", ops.get(0).tool());
        // The params map must carry labelExpression verbatim — the bulk
        // dispatcher inside the accessor reads this key and passes it through
        // prepareUpdateViewObject / prepareUpdateViewObjectDirect.
        assertEquals("${name}", ops.get(0).params().get("labelExpression"));
    }

    @Test
    public void shouldSurfaceFanOutCounts_inBulkSetViewLabelExpressionResponse() throws Exception {
        // Regression guard: the per-op result Map built by formatBulkResponse must carry
        // appliedCount/skippedCount for fan-out ops — they are populated on BulkOperationResult
        // but the hand-assembled response Map has to copy them onto the wire.
        accessor.setBulkBehavior(ops -> {
            List<BulkOperationResult> results = new ArrayList<>();
            results.add(new BulkOperationResult(0, "set-view-label-expression", "updated",
                    "view-1", "ArchimateDiagramModel", "Main View", 11, 1));
            return new BulkMutationResult(results, 1, true, null);
        });

        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "set-view-label-expression",
                        "params", Map.of(
                                "viewId", "view-1",
                                "labelExpression", "${name} ${property:evidenceMark}"))
        ));

        Map<String, Object> result = callAndParse("bulk-mutate", args);
        Map<String, Object> bulkResult = getResult(result);
        assertTrue((Boolean) bulkResult.get("allSucceeded"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations =
                (List<Map<String, Object>>) bulkResult.get("operations");
        assertEquals(1, operations.size());
        Map<String, Object> op = operations.get(0);
        assertEquals("set-view-label-expression", op.get("tool"));
        assertEquals("ArchimateDiagramModel", op.get("entityType"));
        assertEquals("Main View", op.get("entityName"));
        assertEquals(11, ((Number) op.get("appliedCount")).intValue());
        assertEquals(1, ((Number) op.get("skippedCount")).intValue());
    }

    @Test
    public void shouldOmitFanOutCounts_forSingleEntityBulkOpResponse() throws Exception {
        // Single-entity ops use the 6-arg result (null counts) → the keys must be absent.
        accessor.setBulkBehavior(ops -> {
            List<BulkOperationResult> results = new ArrayList<>();
            results.add(new BulkOperationResult(0, "update-view-object", "updated",
                    "vo-1", "BusinessActor", "Test"));
            return new BulkMutationResult(results, 1, true, null);
        });

        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "update-view-object",
                        "params", Map.of("viewObjectId", "vo-1"))
        ));

        Map<String, Object> result = callAndParse("bulk-mutate", args);
        Map<String, Object> bulkResult = getResult(result);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations =
                (List<Map<String, Object>>) bulkResult.get("operations");
        Map<String, Object> op = operations.get(0);
        assertFalse("single-entity op must not carry appliedCount", op.containsKey("appliedCount"));
        assertFalse("single-entity op must not carry skippedCount", op.containsKey("skippedCount"));
    }

    /**
     * The connection report must survive {@code formatBulkResponse}, which hand-builds each
     * operation map key by key.
     *
     * <p>This assertion is on the serialized JSON the tool actually returns, not on the result
     * object, because the object is exactly where the predecessor family's evidence stopped being
     * true: {@code effectiveBounds} was computed, attached to the DTO, and never copied across
     * here, so a typed assertion passed while nothing reached the agent. It took a second commit.
     * Attaching a component to the record proves it was computed; only parsing the envelope proves
     * it was sent.</p>
     */
    @Test
    public void shouldPutEffectiveConnectionOnTheWire_whenABulkOperationTouchedAConnection()
            throws Exception {
        accessor.setBulkBehavior(ops -> {
            List<BulkOperationResult> results = new ArrayList<>();
            results.add(new BulkOperationResult(0, "add-connection-to-view", "created",
                    "conn-1", "AssociationRelationship", null)
                    .withEffectiveConnection(new ViewConnectionDto(
                            "conn-1", "rel-1", "AssociationRelationship", "vo-src", "vo-tgt",
                            List.of(new BendpointDto(10, 20, -10, 20)), null,
                            new AnchorPointDto(60, 30), new AnchorPointDto(260, 30), 2,
                            "#D35400", 2, null, null, null, null, null)));
            return new BulkMutationResult(results, 1, true, null);
        });

        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "add-connection-to-view",
                        "params", Map.of(
                                "viewId", "view-1",
                                "relationshipId", "rel-1",
                                "sourceViewObjectId", "vo-src",
                                "targetViewObjectId", "vo-tgt"))
        ));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>)
                getResult(callAndParse("bulk-mutate", args)).get("operations");
        Map<String, Object> op = operations.get(0);

        assertTrue("the per-operation map must carry the connection report",
                op.containsKey("effectiveConnection"));
        @SuppressWarnings("unchecked")
        Map<String, Object> connection = (Map<String, Object>) op.get("effectiveConnection");
        assertEquals("#D35400", connection.get("lineColor"));
        assertEquals(2, ((Number) connection.get("lineWidth")).intValue());
        assertEquals("conn-1", connection.get("viewConnectionId"));
        assertNotNull("anchors are the field a later operation can change without naming the "
                + "connection, so they are the ones worth proving reached the wire",
                connection.get("sourceAnchor"));
        assertNotNull(connection.get("targetAnchor"));
    }

    /**
     * The same proof for the relationship report, at the same boundary and for the same reason.
     *
     * <p>{@code formatBulkResponse} hand-builds each operation's map key by key, so a component
     * that is computed, attached to the record and never copied across is invisible on the wire
     * while every typed assertion about it still passes. That has cost this family a field once
     * already, which is why the assertion here parses the envelope rather than reading the DTO.</p>
     */
    @Test
    public void shouldPutEffectiveRelationshipOnTheWire_whenABulkOperationTouchedARelationship()
            throws Exception {
        accessor.setBulkBehavior(ops -> {
            List<BulkOperationResult> results = new ArrayList<>();
            results.add(new BulkOperationResult(0, "update-relationship", "updated",
                    "rel-1", "InfluenceRelationship", "Drives")
                    .withEffectiveRelationship(new RelationshipDto(
                            "rel-1", "Drives", "InfluenceRelationship", null, "el-src", "el-tgt",
                            false, null, null, null, null, null, null, "+++")));
            return new BulkMutationResult(results, 1, true, null);
        });

        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "update-relationship",
                        "params", Map.of("id", "rel-1", "influenceStrength", "+++"))
        ));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>)
                getResult(callAndParse("bulk-mutate", args)).get("operations");
        Map<String, Object> op = operations.get(0);

        assertTrue("the per-operation map must carry the relationship report",
                op.containsKey("effectiveRelationship"));
        @SuppressWarnings("unchecked")
        Map<String, Object> relationship = (Map<String, Object>) op.get("effectiveRelationship");
        assertEquals("the semantic attribute is the field the bulk caller could set and was never "
                + "told about, so it is the one worth proving reached the wire",
                "+++", relationship.get("influenceStrength"));
        assertEquals("rel-1", relationship.get("id"));
        assertEquals("InfluenceRelationship", relationship.get("type"));
        assertFalse("an attribute this subtype cannot hold must stay off the wire",
                relationship.containsKey("accessType"));
    }

    /** The element half, proven at the same boundary. */
    @Test
    public void shouldPutEffectiveElementOnTheWire_whenABulkOperationTouchedAnElement()
            throws Exception {
        accessor.setBulkBehavior(ops -> {
            List<BulkOperationResult> results = new ArrayList<>();
            results.add(new BulkOperationResult(0, "update-element", "updated",
                    "el-1", "BusinessActor", "Alpha")
                    .withEffectiveElement(ElementDto.standard("el-1", "Alpha", "BusinessActor",
                            null, "Business", "Rewritten docs", null)));
            return new BulkMutationResult(results, 1, true, null);
        });

        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "update-element",
                        "params", Map.of("id", "el-1", "documentation", "Rewritten docs"))
        ));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>)
                getResult(callAndParse("bulk-mutate", args)).get("operations");
        Map<String, Object> op = operations.get(0);

        assertTrue("the per-operation map must carry the element report",
                op.containsKey("effectiveElement"));
        @SuppressWarnings("unchecked")
        Map<String, Object> element = (Map<String, Object>) op.get("effectiveElement");
        assertEquals("Rewritten docs", element.get("documentation"));
        assertEquals("el-1", element.get("id"));
    }

    /** A non-concept operation's wire bytes must be exactly what they were before. */
    @Test
    public void shouldOmitTheConceptReports_whenTheOperationTouchedNoConcept() throws Exception {
        accessor.setBulkBehavior(ops -> {
            List<BulkOperationResult> results = new ArrayList<>();
            results.add(new BulkOperationResult(0, "update-view-object", "updated",
                    "vo-1", "BusinessActor", "Test"));
            return new BulkMutationResult(results, 1, true, null);
        });

        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "update-view-object",
                        "params", Map.of("viewObjectId", "vo-1"))
        ));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>)
                getResult(callAndParse("bulk-mutate", args)).get("operations");
        assertFalse("a view-object operation must stay byte-identical to before",
                operations.get(0).containsKey("effectiveRelationship"));
        assertFalse(operations.get(0).containsKey("effectiveElement"));
    }

    /** A non-connection operation's wire bytes must be exactly what they were before. */
    @Test
    public void shouldOmitEffectiveConnection_whenTheOperationTouchedNoConnection()
            throws Exception {
        accessor.setBulkBehavior(ops -> {
            List<BulkOperationResult> results = new ArrayList<>();
            results.add(new BulkOperationResult(0, "update-view-object", "updated",
                    "vo-1", "BusinessActor", "Test"));
            return new BulkMutationResult(results, 1, true, null);
        });

        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "update-view-object",
                        "params", Map.of("viewObjectId", "vo-1"))
        ));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>)
                getResult(callAndParse("bulk-mutate", args)).get("operations");
        assertFalse("a non-connection operation must stay byte-identical to before",
                operations.get(0).containsKey("effectiveConnection"));
    }

    @Test
    public void shouldFlowEmptyLabelExpression_inBulkUpdateViewObject() throws Exception {
        final java.util.concurrent.atomic.AtomicReference<List<BulkOperation>> capturedOps =
                new java.util.concurrent.atomic.AtomicReference<>();
        accessor.setBulkBehavior(ops -> {
            capturedOps.set(ops);
            List<BulkOperationResult> results = new ArrayList<>();
            results.add(new BulkOperationResult(0, "update-view-object", "updated",
                    "vo-1", "BusinessActor", "Test"));
            return new BulkMutationResult(results, 1, true, null);
        });

        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "update-view-object",
                        "params", Map.of(
                                "viewObjectId", "vo-1",
                                "labelExpression", ""))
        ));

        callAndParse("bulk-mutate", args);

        // Empty string must survive the JSON map round-trip so the bulk
        // dispatcher inside the accessor (optionalAllowEmptyParam) can
        // distinguish "" (clear) from absent (no change).
        assertEquals("", capturedOps.get().get(0).params().get("labelExpression"));
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

    // ---- update-model bulk-mutate parity tests ----

    @Test
    public void shouldFlowUpdateModel_inBulkMutate() throws Exception {
        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "update-model",
                        "params", Map.of("name", "Renamed Model", "purpose", "New EA cut"))
        ));

        Map<String, Object> result = callAndParse("bulk-mutate", args);
        Map<String, Object> bulkResult = getResult(result);

        assertEquals(1, bulkResult.get("totalOperations"));
        assertTrue("update-model must pass validation as a supported tool",
                (Boolean) bulkResult.get("allSucceeded"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ops = (List<Map<String, Object>>) bulkResult.get("operations");
        assertEquals("update-model", ops.get(0).get("tool"));
        assertEquals("updated", ops.get(0).get("action"));
    }

    @Test
    public void shouldFlowEmptyPurpose_inBulkUpdateModel() throws Exception {
        // Empty-string purpose must NOT cause the bulk-mutate framing to reject the op.
        // The empty-string → clearPurpose conversion happens inside prepareBulkOperation
        // (Layer 3) — this test verifies the Layer 2 framing accepts the request.
        Map<String, Object> args = Map.of("operations", List.of(
                Map.of("tool", "update-model",
                        "params", Map.of("purpose", ""))
        ));

        Map<String, Object> result = callAndParse("bulk-mutate", args);
        Map<String, Object> bulkResult = getResult(result);

        assertEquals(1, bulkResult.get("totalOperations"));
        assertTrue("empty-string purpose param must not bounce off bulk-mutate validation",
                (Boolean) bulkResult.get("allSucceeded"));
    }

    @Test
    public void shouldAdvertiseUpdateModel_inBulkMutateDescription() {
        // The bulk-mutate tool description is built from BulkOperation.SUPPORTED_TOOLS_ORDERED.
        // Adding "update-model" to the list automatically extends the description.
        // This is the regression pin for the one-liner registry insert in BulkOperation.
        String description = registry.getToolSpecifications().stream()
                .filter(s -> "bulk-mutate".equals(s.tool().name()))
                .findFirst()
                .orElseThrow()
                .tool().description();
        assertNotNull(description);
        assertTrue("bulk-mutate description must advertise update-model",
                description.contains("update-model"));
    }

    @Test
    public void shouldAdvertiseAddViewReferenceToView_inBulkMutateDescription() {
        // Regression pin for the one-liner registry insert.
        String description = registry.getToolSpecifications().stream()
                .filter(s -> "bulk-mutate".equals(s.tool().name()))
                .findFirst()
                .orElseThrow()
                .tool().description();
        assertNotNull(description);
        assertTrue("bulk-mutate description must advertise add-view-reference-to-view",
                description.contains("add-view-reference-to-view"));
    }

    @Test
    public void shouldPointToStandaloneToolsForPerOperationDocs_inBulkMutateDescription() {
        // bulk-mutate owns batching semantics; each operation's own parameters and response
        // fields are documented on the standalone tool of the same name. Without this pointer
        // an agent driving bulk-mutate has no route to per-operation detail, while the same
        // operation called standalone is fully documented — two discoverability tiers over
        // one execution path.
        //
        // Assert SHORT, STABLE substrings, not whole sentences: a full-string match would
        // fail on the next innocuous rewording of the description.
        String description = registry.getToolSpecifications().stream()
                .filter(s -> "bulk-mutate".equals(s.tool().name()))
                .findFirst()
                .orElseThrow()
                .tool().description();
        assertNotNull(description);
        assertTrue("bulk-mutate description must point at the standalone tool of the same name",
                description.contains("standalone tool of the same name"));
        assertTrue("bulk-mutate description must scope itself to batching semantics",
                description.contains("batching semantics only"));
        // The pointer must direct the caller to READ the standalone tool's description, not to
        // invoke it — several bulk operations are destructive (clear-view, delete-*), so an
        // agent must never be nudged into calling one just to discover its response shape.
        assertTrue("pointer must direct the agent to the tool's description, not to calling it",
                description.contains("read that tool's description"));
    }

    @Test
    public void shouldDocumentTheBulkOnlyOperation_inBulkMutateDescription() {
        // set-view-label-expression is the ONE supported operation with no standalone tool
        // (verified against the registered tool names), so bulk-mutate is its only
        // LLM-facing surface. The pointer above would send an agent looking for a tool that
        // does not exist unless this exception is named and documented here.
        String description = registry.getToolSpecifications().stream()
                .filter(s -> "bulk-mutate".equals(s.tool().name()))
                .findFirst()
                .orElseThrow()
                .tool().description();
        assertNotNull(description);
        assertTrue("bulk-mutate description must name the bulk-only operation as an exception",
                description.contains("set-view-label-expression is bulk-only"));
        assertTrue("bulk-only operation's required params must be documented on its only surface",
                description.contains("labelExpression"));
        assertTrue("bulk-only operation's objectTypes filter must be documented",
                description.contains("objectTypes"));
        assertTrue("bulk-only operation's result fields must be documented",
                description.contains("appliedCount") && description.contains("skippedCount"));
        // The blank-name skip is CONDITIONAL: SetViewLabelExpressionCommand passes
        // requireName = (value != null), so nameless objects are skipped only while setting a
        // non-empty expression — when clearing they are still cleared and counted in
        // appliedCount. Documenting the guard unconditionally would make an agent mispredict
        // skippedCount for every mass-clear call.
        assertTrue("blank-name skip must be documented as conditional on setting, not clearing",
                description.contains("only when setting a non-empty")
                        && description.contains("never when clearing"));
    }

    /**
     * Guards the division of labour the pointer promises: bulk-mutate must NOT accumulate
     * per-operation documentation for operations that have their own standalone tool. With
     * 28 supported operations, inlining that detail would balloon the description, cost
     * tokens on every tool listing, and drift the first time a standalone tool changes.
     */
    @Test
    public void shouldNotDuplicatePerOperationDetail_inBulkMutateDescription() {
        String description = registry.getToolSpecifications().stream()
                .filter(s -> "bulk-mutate".equals(s.tool().name()))
                .findFirst()
                .orElseThrow()
                .tool().description();
        assertNotNull(description);
        // Parameters that belong exclusively to standalone-documented operations. If one of
        // these appears here, per-op detail has started leaking into the batching surface.
        for (String standaloneOnlyParam : new String[] {
                "imageCoveragePercent", "connectionRouterType", "fillColor", "bendpoints" }) {
            assertFalse("bulk-mutate must not duplicate per-operation detail ('"
                            + standaloneOnlyParam + "' belongs on the standalone tool)",
                    description.contains(standaloneOnlyParam));
        }
    }

    /**
     * Image import is the one capability an agent reaches for here and cannot have: an archive
     * write has no inverse, so it can be neither rolled back with a declined proposal nor deferred
     * to execute time. Listing only what IS supported leaves the agent to discover the absence by
     * probing, learning nothing about why or where to go instead — so the reason and the redirect
     * belong beside the supported list. The sibling half of this pin lives on add-image-to-model's
     * own description; an agent arriving from either direction must find the same answer.
     */
    @Test
    public void shouldExplainWhyImageImportIsNotBulkable_inBulkMutateDescription() {
        String description = registry.getToolSpecifications().stream()
                .filter(s -> "bulk-mutate".equals(s.tool().name()))
                .findFirst()
                .orElseThrow()
                .tool().description();
        assertNotNull(description);
        assertTrue("bulk-mutate must name the import tool it cannot carry",
                description.contains("add-image-to-model"));
        assertTrue("bulk-mutate must give the reason, not just the absence",
                description.contains("not undoable"));
        assertTrue("bulk-mutate must redirect to the batch form that does work",
                description.contains("'images'"));
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
