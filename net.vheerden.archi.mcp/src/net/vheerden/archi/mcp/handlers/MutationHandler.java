package net.vheerden.archi.mcp.handlers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.ArchiModelAccessor;
import net.vheerden.archi.mcp.model.ModelAccessException;
import net.vheerden.archi.mcp.model.MutationDispatcher;
import net.vheerden.archi.mcp.model.NoModelLoadedException;
import net.vheerden.archi.mcp.model.OperationalMode;
import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ErrorResponse;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.BatchStatusDto;
import net.vheerden.archi.mcp.response.dto.BatchSummaryDto;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.BulkOperationFailure;
import net.vheerden.archi.mcp.response.dto.BulkOperationResult;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Handler for mutation operational mode tools: begin-batch, end-batch,
 * get-batch-status (Story 7-1).
 *
 * <p>Manages transitions between GUI-attached mode (immediate mutations)
 * and batch mode (queued mutations with atomic commit/rollback).</p>
 *
 * <p><strong>Architecture boundary:</strong> This class MUST NOT import
 * any EMF, GEF, SWT, or ArchimateTool model types. All mutation dispatch
 * goes through {@link MutationDispatcher} via {@link ArchiModelAccessor}.</p>
 */
public class MutationHandler {

    private static final Logger logger = LoggerFactory.getLogger(MutationHandler.class);

    private static final Set<String> VIEW_TOOLS = Set.of(
            "add-to-view", "add-connection-to-view", "remove-from-view",
            "update-view-object", "update-view-connection", "clear-view");

    private final ArchiModelAccessor accessor;
    private final ResponseFormatter formatter;
    private final CommandRegistry registry;
    // nullable — null in test mode without session management
    private final SessionManager sessionManager;

    /**
     * Creates a MutationHandler with its required dependencies.
     *
     * @param accessor       the model accessor for querying ArchiMate data
     * @param formatter      the response formatter for building JSON envelopes
     * @param registry       the command registry for tool registration
     * @param sessionManager the session manager for session ID extraction, may be null
     */
    public MutationHandler(ArchiModelAccessor accessor,
                           ResponseFormatter formatter,
                           CommandRegistry registry,
                           SessionManager sessionManager) {
        this.accessor = Objects.requireNonNull(accessor, "accessor must not be null");
        this.formatter = Objects.requireNonNull(formatter, "formatter must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.sessionManager = sessionManager;
    }

    /**
     * Registers all tools provided by this handler with the command registry.
     * Registers: begin-batch, end-batch, get-batch-status, bulk-mutate.
     */
    public void registerTools() {
        registry.registerTool(buildBeginBatchSpec());
        registry.registerTool(buildEndBatchSpec());
        registry.registerTool(buildGetBatchStatusSpec());
        registry.registerTool(buildBulkMutateSpec());
    }

    // ---- begin-batch ----

    private McpServerFeatures.SyncToolSpecification buildBeginBatchSpec() {
        Map<String, Object> descriptionProp = new LinkedHashMap<>();
        descriptionProp.put("type", "string");
        descriptionProp.put("description",
                "Optional description for this batch operation");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("description", descriptionProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, null, null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("begin-batch")
                .description("[Mutation] Start batch mode for mutations. "
                        + "Subsequent mutation operations will be queued instead of applied immediately. "
                        + "Use end-batch to commit all changes atomically or rollback to discard. "
                        + "Related: end-batch (commit/rollback), get-batch-status (check queue).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleBeginBatch)
                .build();
    }

    McpSchema.CallToolResult handleBeginBatch(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling begin-batch request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            MutationDispatcher dispatcher = requireDispatcher();

            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);
            String description = null;
            if (request.arguments() != null) {
                Object descObj = request.arguments().get("description");
                if (descObj instanceof String d && !d.isBlank()) {
                    description = d;
                }
            }

            // Check not already in batch mode
            if (dispatcher.getMode(sessionId) == OperationalMode.BATCH) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.BATCH_ALREADY_ACTIVE,
                        "A batch is already active for this session",
                        null,
                        "Use end-batch to commit or rollback the current batch before starting a new one",
                        null);
                return HandlerUtils.buildResult(
                        formatter.toJsonString(formatter.formatError(error)), true);
            }

            dispatcher.beginBatch(sessionId, description);
            BatchStatusDto status = dispatcher.getBatchStatus(sessionId);
            String modelVersion = accessor.getModelVersion();

            List<String> nextSteps = List.of(
                    "Use create-element, create-relationship, update-element for queued mutations",
                    "Use end-batch to commit all changes atomically",
                    "Use get-batch-status to check queue status");

            Map<String, Object> envelope = formatter.formatSuccess(
                    status, nextSteps, modelVersion, 1, 1, false);

            return HandlerUtils.buildResult(formatter.toJsonString(envelope), false);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling begin-batch", e);
            ErrorResponse error = new ErrorResponse(
                    ErrorCode.INTERNAL_ERROR,
                    "An unexpected error occurred while starting batch mode");
            return HandlerUtils.buildResult(
                    formatter.toJsonString(formatter.formatError(error)), true);
        }
    }

    // ---- end-batch ----

    private McpServerFeatures.SyncToolSpecification buildEndBatchSpec() {
        Map<String, Object> rollbackProp = new LinkedHashMap<>();
        rollbackProp.put("type", "boolean");
        rollbackProp.put("description",
                "If true, discard all queued mutations. If false or omitted, commit all mutations.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("rollback", rollbackProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, null, null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("end-batch")
                .description("[Mutation] End batch mode and either commit all queued mutations "
                        + "atomically or rollback (discard all). In commit mode, all changes are "
                        + "applied as a single undoable operation. "
                        + "Related: begin-batch (start batch), get-batch-status (check queue).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleEndBatch)
                .build();
    }

    McpSchema.CallToolResult handleEndBatch(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling end-batch request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            MutationDispatcher dispatcher = requireDispatcher();

            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);
            boolean rollback = false;
            if (request.arguments() != null) {
                Object rollbackObj = request.arguments().get("rollback");
                if (rollbackObj instanceof Boolean rb) {
                    rollback = rb;
                }
            }

            // Check in batch mode
            if (dispatcher.getMode(sessionId) != OperationalMode.BATCH) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.BATCH_NOT_ACTIVE,
                        "No active batch for this session",
                        null,
                        "Use begin-batch to start a new batch before calling end-batch",
                        null);
                return HandlerUtils.buildResult(
                        formatter.toJsonString(formatter.formatError(error)), true);
            }

            BatchSummaryDto summary = dispatcher.endBatch(sessionId, !rollback);
            String modelVersion = accessor.getModelVersion();

            List<String> nextSteps;
            if (rollback) {
                nextSteps = List.of(
                        "All queued mutations discarded — model unchanged",
                        "Use begin-batch to start a new batch");
            } else {
                nextSteps = List.of(
                        "Changes applied as single undoable operation",
                        "Use get-model-info to verify model state",
                        "All changes can be undone with Ctrl+Z in ArchimateTool");
            }

            Map<String, Object> envelope = formatter.formatSuccess(
                    summary, nextSteps, modelVersion, 1, 1, false);

            return HandlerUtils.buildResult(formatter.toJsonString(envelope), false);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (MutationException e) {
            logger.error("Mutation failed during batch commit", e);
            ErrorResponse error = new ErrorResponse(
                    ErrorCode.MUTATION_FAILED,
                    "Batch commit failed: " + e.getMessage(),
                    null,
                    "The batch is still active. Use end-batch to retry or end-batch with rollback=true to discard.",
                    null);
            return HandlerUtils.buildResult(
                    formatter.toJsonString(formatter.formatError(error)), true);
        } catch (Exception e) {
            logger.error("Unexpected error handling end-batch", e);
            ErrorResponse error = new ErrorResponse(
                    ErrorCode.INTERNAL_ERROR,
                    "An unexpected error occurred while ending batch mode");
            return HandlerUtils.buildResult(
                    formatter.toJsonString(formatter.formatError(error)), true);
        }
    }

    // ---- get-batch-status ----

    private McpServerFeatures.SyncToolSpecification buildGetBatchStatusSpec() {
        Map<String, Object> properties = new LinkedHashMap<>();

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, null, null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("get-batch-status")
                .description("[Mutation] Get current operational mode and batch status. "
                        + "Shows whether in GUI-attached or batch mode, and if in batch mode, "
                        + "the number of queued operations. "
                        + "Related: begin-batch (start batch), end-batch (commit/rollback).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleGetBatchStatus)
                .build();
    }

    McpSchema.CallToolResult handleGetBatchStatus(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling get-batch-status request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            MutationDispatcher dispatcher = requireDispatcher();

            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);
            OperationalMode mode = dispatcher.getMode(sessionId);
            BatchStatusDto status = dispatcher.getBatchStatus(sessionId);
            String modelVersion = accessor.getModelVersion();

            List<String> nextSteps;
            if (mode == OperationalMode.GUI_ATTACHED) {
                nextSteps = List.of(
                        "Currently in GUI-attached mode — mutations apply immediately",
                        "Use begin-batch to switch to batch mode");
            } else {
                nextSteps = new ArrayList<>();
                nextSteps.add(status.queuedCount() + " mutation(s) queued");
                nextSteps.add("Use end-batch to commit all changes atomically");
                nextSteps.add("Use end-batch with rollback=true to discard all changes");
            }

            Map<String, Object> envelope = formatter.formatSuccess(
                    status, nextSteps, modelVersion, 1, 1, false);

            return HandlerUtils.buildResult(formatter.toJsonString(envelope), false);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling get-batch-status", e);
            ErrorResponse error = new ErrorResponse(
                    ErrorCode.INTERNAL_ERROR,
                    "An unexpected error occurred while retrieving batch status");
            return HandlerUtils.buildResult(
                    formatter.toJsonString(formatter.formatError(error)), true);
        }
    }

    // ---- bulk-mutate ----

    private McpServerFeatures.SyncToolSpecification buildBulkMutateSpec() {
        // operations array item schema
        Map<String, Object> toolProp = new LinkedHashMap<>();
        toolProp.put("type", "string");
        toolProp.put("description",
                "Mutation tool to execute: create-element, create-relationship, "
                        + "create-view, update-element, add-to-view, "
                        + "add-connection-to-view, remove-from-view, "
                        + "update-view-object, update-view-connection, "
                        + "or clear-view");

        Map<String, Object> paramsProp = new LinkedHashMap<>();
        paramsProp.put("type", "object");
        paramsProp.put("description",
                "Parameters for the tool (same as calling the tool directly)");

        Map<String, Object> itemProperties = new LinkedHashMap<>();
        itemProperties.put("tool", toolProp);
        itemProperties.put("params", paramsProp);

        Map<String, Object> itemSchema = new LinkedHashMap<>();
        itemSchema.put("type", "object");
        itemSchema.put("properties", itemProperties);
        itemSchema.put("required", List.of("tool", "params"));

        Map<String, Object> operationsProp = new LinkedHashMap<>();
        operationsProp.put("type", "array");
        operationsProp.put("description",
                "Array of mutation operations to execute atomically. "
                        + "Use $N.id in param values to reference the ID of entity created "
                        + "by operation at index N (0-based). Max 50 operations.");
        operationsProp.put("items", itemSchema);
        operationsProp.put("maxItems", BulkOperation.MAX_OPERATIONS);

        Map<String, Object> descriptionProp = new LinkedHashMap<>();
        descriptionProp.put("type", "string");
        descriptionProp.put("description",
                "Optional label for undo history");

        Map<String, Object> continueOnErrorProp = new LinkedHashMap<>();
        continueOnErrorProp.put("type", "boolean");
        continueOnErrorProp.put("description",
                "When true, valid operations execute even if others fail. "
                        + "Failed operations are reported separately in the 'failed' array. "
                        + "Back-references ($N.id) to failed operations cascade failure "
                        + "to dependent operations. Default: false (all-or-nothing atomic semantics).");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("operations", operationsProp);
        properties.put("description", descriptionProp);
        properties.put("continueOnError", continueOnErrorProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("operations"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("bulk-mutate")
                .description("[Mutation] Execute multiple mutations as a single compound command. "
                        + "By default (continueOnError: false), all-or-nothing: all operations are "
                        + "pre-validated before any execute — if any fails, none are applied. "
                        + "With continueOnError: true, valid operations execute and failed ones are "
                        + "reported separately in the 'failed' array. Back-references ($N.id) to "
                        + "failed operations cascade failure to dependent operations. "
                        + "Supports back-references: use $N.id in parameter values to reference "
                        + "the ID of the entity created by operation at index N. Max "
                        + BulkOperation.MAX_OPERATIONS + " operations per call. "
                        + "Required: operations (array of {tool, params} objects). "
                        + "Optional: description (label for undo history), "
                        + "continueOnError (boolean, default false). "
                        + "Supported tools: create-element, create-relationship, create-view, "
                        + "update-element, add-to-view, add-connection-to-view, "
                        + "add-group-to-view, add-note-to-view, "
                        + "remove-from-view, update-view-object, update-view-connection, "
                        + "clear-view. "
                        + "Note: autoConnect is forced false for add-to-view in bulk context "
                        + "— use explicit add-connection-to-view operations instead. "
                        + "Use $N.id from add-group-to-view as parentViewObjectId in subsequent "
                        + "add-to-view or add-group-to-view operations to nest elements inside groups. "
                        + "Related: begin-batch (interactive multi-step workflow), "
                        + "end-batch (commit batch), create-element (single creation).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleBulkMutate)
                .build();
    }

    McpSchema.CallToolResult handleBulkMutate(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling bulk-mutate request");
        try {
            HandlerUtils.requireModelLoaded(accessor);

            Map<String, Object> args = request.arguments();
            if (args == null || !args.containsKey("operations")) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.INVALID_PARAMETER,
                        "Missing required parameter: operations",
                        null,
                        "Provide an 'operations' array of {tool, params} objects",
                        null);
                return HandlerUtils.buildResult(
                        formatter.toJsonString(formatter.formatError(error)), true);
            }

            Object opsRaw = args.get("operations");
            if (!(opsRaw instanceof List<?> opsList)) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.INVALID_PARAMETER,
                        "Parameter 'operations' must be an array",
                        null,
                        "Provide an array of {tool, params} objects",
                        null);
                return HandlerUtils.buildResult(
                        formatter.toJsonString(formatter.formatError(error)), true);
            }

            if (opsList.isEmpty()) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.INVALID_PARAMETER,
                        "Operations array must not be empty",
                        null,
                        "Provide at least one operation",
                        null);
                return HandlerUtils.buildResult(
                        formatter.toJsonString(formatter.formatError(error)), true);
            }

            if (opsList.size() > BulkOperation.MAX_OPERATIONS) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.INVALID_PARAMETER,
                        "Operations array exceeds maximum of " + BulkOperation.MAX_OPERATIONS
                                + " (got " + opsList.size() + ")",
                        null,
                        "Split into multiple bulk-mutate calls of "
                                + BulkOperation.MAX_OPERATIONS + " or fewer",
                        null);
                return HandlerUtils.buildResult(
                        formatter.toJsonString(formatter.formatError(error)), true);
            }

            // Parse operations
            List<BulkOperation> operations = new ArrayList<>();
            for (int i = 0; i < opsList.size(); i++) {
                Object opRaw = opsList.get(i);
                if (!(opRaw instanceof Map<?, ?> opMap)) {
                    ErrorResponse error = new ErrorResponse(
                            ErrorCode.INVALID_PARAMETER,
                            "Operation at index " + i + " must be an object with 'tool' and 'params'",
                            null,
                            "Each operation must be {\"tool\": \"...\", \"params\": {...}}",
                            null);
                    return HandlerUtils.buildResult(
                            formatter.toJsonString(formatter.formatError(error)), true);
                }

                Object toolObj = opMap.get("tool");
                Object paramsObj = opMap.get("params");

                if (!(toolObj instanceof String toolName) || toolName.isBlank()) {
                    ErrorResponse error = new ErrorResponse(
                            ErrorCode.INVALID_PARAMETER,
                            "Operation at index " + i + ": missing or invalid 'tool' field",
                            null,
                            "Provide a valid tool name: " + BulkOperation.SUPPORTED_TOOLS,
                            null);
                    return HandlerUtils.buildResult(
                            formatter.toJsonString(formatter.formatError(error)), true);
                }

                if (!(paramsObj instanceof Map<?, ?> paramsMap)) {
                    ErrorResponse error = new ErrorResponse(
                            ErrorCode.INVALID_PARAMETER,
                            "Operation at index " + i + ": missing or invalid 'params' field",
                            null,
                            "Provide a params object with the tool's required parameters",
                            null);
                    return HandlerUtils.buildResult(
                            formatter.toJsonString(formatter.formatError(error)), true);
                }

                // Convert params to Map<String, Object>
                Map<String, Object> typedParams = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : paramsMap.entrySet()) {
                    if (entry.getKey() instanceof String key) {
                        typedParams.put(key, entry.getValue());
                    }
                }

                BulkOperation op = new BulkOperation(toolName, typedParams);
                try {
                    op.validate();
                } catch (IllegalArgumentException e) {
                    ErrorResponse error = new ErrorResponse(
                            ErrorCode.INVALID_PARAMETER,
                            "Operation at index " + i + ": " + e.getMessage(),
                            null,
                            "Use a supported tool: " + BulkOperation.SUPPORTED_TOOLS,
                            null);
                    return HandlerUtils.buildResult(
                            formatter.toJsonString(formatter.formatError(error)), true);
                }
                operations.add(op);
            }

            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);
            String description = HandlerUtils.optionalStringParam(args, "description");
            boolean continueOnError = Boolean.TRUE.equals(args.get("continueOnError"));
            BulkMutationResult result = accessor.executeBulk(
                    sessionId, operations, description, continueOnError);

            return formatBulkResponse(result);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            if (e.getErrorCode() == ErrorCode.BULK_VALIDATION_FAILED) {
                return buildBulkValidationError(e);
            }
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling bulk-mutate", e);
            return HandlerUtils.buildInternalError(formatter,
                    "An unexpected error occurred during bulk mutation");
        }
    }

    /**
     * Formats a successful bulk-mutate response.
     */
    private McpSchema.CallToolResult formatBulkResponse(BulkMutationResult result) {
        String modelVersion = accessor.getModelVersion();
        boolean hasFailures = !result.failedOperations().isEmpty();
        int succeededCount = result.operations().size();
        int failedCount = result.failedOperations().size();

        Map<String, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("totalOperations", result.totalOperations());
        if (hasFailures) {
            resultMap.put("succeededCount", succeededCount);
            resultMap.put("failedCount", failedCount);
        }
        resultMap.put("allSucceeded", result.allSucceeded());
        boolean modelChanged = !result.isBatched() && !result.isProposal()
                && succeededCount > 0;
        resultMap.put("modelChanged", modelChanged);

        // Succeeded operations
        List<Map<String, Object>> opResults = new ArrayList<>();
        for (BulkOperationResult opResult : result.operations()) {
            Map<String, Object> opMap = new LinkedHashMap<>();
            opMap.put("index", opResult.index());
            opMap.put("tool", opResult.tool());
            opMap.put("action", opResult.action());
            opMap.put("entityId", opResult.entityId());
            opMap.put("entityType", opResult.entityType());
            if (opResult.entityName() != null) {
                opMap.put("entityName", opResult.entityName());
            }
            opResults.add(opMap);
        }
        resultMap.put(hasFailures ? "succeeded" : "operations", opResults);

        // Failed operations (Story 11-9)
        if (hasFailures) {
            List<Map<String, Object>> failResults = new ArrayList<>();
            for (BulkOperationFailure failure : result.failedOperations()) {
                Map<String, Object> failMap = new LinkedHashMap<>();
                failMap.put("index", failure.index());
                failMap.put("tool", failure.tool());
                failMap.put("errorCode", failure.errorCode());
                failMap.put("message", failure.message());
                if (failure.suggestedCorrection() != null) {
                    failMap.put("suggestedCorrection", failure.suggestedCorrection());
                }
                failResults.add(failMap);
            }
            resultMap.put("failed", failResults);
        }

        // Approval mode: add proposal info (Story 7-6)
        if (result.isProposal()) {
            Map<String, Object> proposalInfo = new LinkedHashMap<>();
            proposalInfo.put("proposalId", result.proposalContext().proposalId());
            proposalInfo.put("status", "pending");
            proposalInfo.put("description", result.proposalContext().description());
            proposalInfo.put("createdAt", result.proposalContext().createdAt().toString());
            if (hasFailures) {
                proposalInfo.put("validOperationCount", succeededCount);
                proposalInfo.put("failedValidationCount", failedCount);
            }
            resultMap.put("proposal", proposalInfo);
        }

        if (result.isBatched()) {
            Map<String, Object> batchInfo = new LinkedHashMap<>();
            batchInfo.put("sequenceNumber", result.batchSequenceNumber());
            batchInfo.put("message", "Bulk mutation (" + succeededCount
                    + " operations) queued as batch operation #"
                    + result.batchSequenceNumber());
            resultMap.put("batch", batchInfo);
        }

        List<String> nextSteps;
        if (result.isProposal()) {
            String proposalId = result.proposalContext().proposalId();
            nextSteps = new ArrayList<>();
            nextSteps.add("Use list-pending-approvals to review all pending mutations");
            nextSteps.add("Use decide-mutation with proposalId '" + proposalId
                    + "' and decision 'approve' to apply this bulk mutation");
            nextSteps.add("Use decide-mutation with proposalId '" + proposalId
                    + "' and decision 'reject' to discard this bulk mutation");
            if (hasFailures) {
                nextSteps.add(failedCount + " operations failed validation — fix and retry "
                        + "in a separate bulk-mutate call");
            }
        } else if (result.isBatched()) {
            nextSteps = new ArrayList<>();
            nextSteps.add("Bulk mutation queued as operation #" + result.batchSequenceNumber()
                    + " in current batch");
            if (hasFailures) {
                nextSteps.add(failedCount + " operations failed validation — fix and retry "
                        + "in a separate bulk-mutate call");
            }
            nextSteps.add("Use get-batch-status to check batch progress");
            nextSteps.add("Use end-batch to commit all queued mutations");
        } else {
            nextSteps = new ArrayList<>();
            if (succeededCount > 0) {
                nextSteps.add("Use get-element to verify created elements");
                nextSteps.add("Use get-relationships to verify connections");
                boolean hasViewTools = result.operations().stream()
                        .anyMatch(op -> VIEW_TOOLS.contains(op.tool()));
                if (hasViewTools) {
                    nextSteps.add("Use get-view-contents to verify view layout");
                }
            }
            if (hasFailures) {
                nextSteps.add(failedCount + " operations failed — review the 'failed' array, "
                        + "fix errors, and retry failed operations in a new bulk-mutate call");
            }
            if (succeededCount > 0) {
                nextSteps.add("All " + succeededCount
                        + " succeeded operations can be undone as a single unit via undo");
            }
        }

        Map<String, Object> envelope = formatter.formatSuccess(
                resultMap, nextSteps, modelVersion,
                result.totalOperations(), result.totalOperations(), false);

        return HandlerUtils.buildResult(formatter.toJsonString(envelope),
                hasFailures && succeededCount == 0);
    }

    /**
     * Formats a bulk validation failure error response.
     */
    private McpSchema.CallToolResult buildBulkValidationError(ModelAccessException e) {
        ErrorResponse error = new ErrorResponse(
                ErrorCode.BULK_VALIDATION_FAILED,
                e.getMessage(),
                e.getDetails(),
                e.getSuggestedCorrection() != null
                        ? e.getSuggestedCorrection()
                        : "Fix the failed operation and retry the entire bulk-mutate call",
                e.getArchiMateReference());
        return HandlerUtils.buildResult(
                formatter.toJsonString(formatter.formatError(error)), true);
    }

    // ---- Handler-specific helper ----

    private MutationDispatcher requireDispatcher() {
        MutationDispatcher dispatcher = accessor.getMutationDispatcher();
        if (dispatcher == null) {
            throw new MutationException("Mutation operations not supported by this accessor");
        }
        return dispatcher;
    }
}
