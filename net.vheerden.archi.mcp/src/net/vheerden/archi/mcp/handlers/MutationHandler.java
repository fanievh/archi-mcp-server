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
import net.vheerden.archi.mcp.model.BulkValidationException;
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
 * get-batch-status.
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

        // Optional agent intent — the WHY of this batch. Recorded on the session's batch
        // context. Distinct from 'description' (the undo-history label). Never required; the server
        // never depends on it. NOTE: under approval mode the end-batch compound is dispatched
        // directly (not stored as a proposal), so begin-batch intent is captured but not yet
        // rendered on an approval card — 'bulk-mutate' is the seam whose intent appears on the card.
        Map<String, Object> intentProp = new LinkedHashMap<>();
        intentProp.put("type", "string");
        intentProp.put("description",
                "Optional plain-language intent for this batch (the WHY). Recorded on the batch; "
                        + "distinct from 'description'. Never required, never affects behaviour. "
                        + "(The agent's-note card line is rendered for 'bulk-mutate' intent.)");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("description", descriptionProp);
        properties.put("intent", intentProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, null, null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("begin-batch")
                .description("[Mutation] Start batch mode for mutations. "
                        + "Subsequent mutation operations will be queued instead of applied immediately. "
                        + "Use end-batch to commit all changes or rollback to discard. "
                        + "While a batch is open every mutation response is reshaped: the tool's normal "
                        + "result moves to result.preview, and a result.batch sibling carries "
                        + "sequenceNumber (its success and description fields are constants and carry "
                        + "no information). Harvest created IDs from result.preview — end-batch reports "
                        + "only operationCount, descriptions, duration and rolledBack, never entity IDs, "
                        + "so preview is the only place a created ID is ever offered. The human's "
                        + "approval gate takes precedence over batching: while it is on a mutation "
                        + "is not queued at all and comes back with a result.proposal sibling "
                        + "instead of result.batch — get-batch-status reports both states. Two "
                        + "exceptions to the preview shape: bulk-mutate has no preview and keeps "
                        + "its normal result fields, and get-or-create-element and search-and-create "
                        + "put the element at result.element instead — see their own descriptions. "
                        + "bulk-mutate may be called while a batch is open: the nested call is "
                        + "queued into this batch like any other operation, so what it reports is a "
                        + "preview and whether its operations run is decided at end-batch. An id "
                        + "harvested from result.preview resolves inside it on every tool that "
                        + "places something on a view — as the viewId placed on, as the "
                        + "parentViewObjectId nested into, and as the element being placed — and as "
                        + "the target of update-view-object and update-view-connection, and as any "
                        + "of add-connection-to-view's relationship, view and endpoint ids — "
                        + "whether or not that operation also carries a back-reference of its "
                        + "own. Tools that update, delete or re-file a concept rather than place it "
                        + "— update-element, update-relationship, update-view, remove-from-view, "
                        + "clear-view, set-view-label-expression, the delete-* family, "
                        + "move-to-folder, and create-relationship's endpoints — report such an id "
                        + "as not found; queue those operations here instead, or "
                        + "end-batch first and then use bulk-mutate on its own. "
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
            String intent = null;
            if (request.arguments() != null) {
                Object descObj = request.arguments().get("description");
                if (descObj instanceof String d && !d.isBlank()) {
                    description = d;
                }
                Object intentObj = request.arguments().get("intent");
                if (intentObj instanceof String it && !it.isBlank()) {
                    intent = it;
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

            dispatcher.beginBatch(sessionId, description, intent);
            BatchStatusDto status = dispatcher.getBatchStatus(sessionId);
            String modelVersion = accessor.getModelVersion();

            List<String> nextSteps = List.of(
                    "Use create-element, create-relationship, update-element for queued mutations",
                    "Use end-batch to commit all changes, or rollback to discard them",
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
                        + "or rollback (discard all). In commit mode, all changes are "
                        + "applied as a single undoable operation. A queued operation can still "
                        + "decline to run when an earlier operation in the same batch changed its "
                        + "target: either applying it would destroy something the batch never "
                        + "authorised (a folder delete whose folder was filled by an earlier "
                        + "create), or the container it named has since been removed from the "
                        + "model, so what it created would be unreachable and lost on reload (a "
                        + "create into a folder an earlier operation deleted). Such an operation "
                        + "is skipped and named in skippedOperations, with the reason. Anything "
                        + "the operation would have created is not created either, so an id "
                        + "reported for it before the changes were applied names nothing — "
                        + "skippedOperations is the authority. That field is absent when "
                        + "everything ran, which is the normal case. operationCount counts what "
                        + "was queued, so it still includes anything skipped. "
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
                        + "the number of queued operations. Call this to learn which response shape "
                        + "mutations will return. Returns mode ('GUI_ATTACHED' or 'BATCH'); "
                        + "queuedCount, queuedDescriptions and batchStarted only in batch mode; "
                        + "approvalRequired only when the human's approval gate is on (it is never "
                        + "false — the field is absent instead); and pendingApprovalCount only when "
                        + "at least one proposal is pending (never 0). "
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
                nextSteps.add("Use end-batch to commit all changes, or rollback to discard them");
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
        // Single source of truth for the advertised supported-tools lists: derive from the
        // deterministically-ordered canonical list so both the toolProp and tool-level
        // descriptions stay complete and consistent with BulkOperation.SUPPORTED_TOOLS.
        // (Do NOT join BulkOperation.SUPPORTED_TOOLS directly — Set.of iteration order is
        // randomized per JVM run, which would make the served description unstable.)
        String supportedTools = String.join(", ", BulkOperation.SUPPORTED_TOOLS_ORDERED);

        // operations array item schema
        Map<String, Object> toolProp = new LinkedHashMap<>();
        toolProp.put("type", "string");
        toolProp.put("description",
                "Mutation tool to execute: " + supportedTools);

        Map<String, Object> paramsProp = new LinkedHashMap<>();
        paramsProp.put("type", "object");
        paramsProp.put("description",
                "Parameters for the tool (same as calling the tool directly)");

        // Optional, and deliberately not in "required": an operation names itself only when a
        // later one needs to point at it. Published so a caller can discover the form at all —
        // and because an operation carrying any key not listed here is now refused rather than
        // silently stripped, which makes this list the accepted set rather than a suggestion.
        Map<String, Object> asProp = new LinkedHashMap<>();
        asProp.put("type", "string");
        asProp.put("description",
                "Optional name for this operation, referenced by a later operation in the same "
                        + "call as \"$name.id\" instead of \"$N.id\". Must start with a letter "
                        + "or underscore, continue with letters, digits or underscores, and be "
                        + "unique within the call.");

        Map<String, Object> itemProperties = new LinkedHashMap<>();
        itemProperties.put("tool", toolProp);
        itemProperties.put("params", paramsProp);
        itemProperties.put("as", asProp);

        Map<String, Object> itemSchema = new LinkedHashMap<>();
        itemSchema.put("type", "object");
        itemSchema.put("properties", itemProperties);
        itemSchema.put("required", List.of("tool", "params"));

        Map<String, Object> operationsProp = new LinkedHashMap<>();
        operationsProp.put("type", "array");
        operationsProp.put("description",
                "Array of mutation operations, applied in order as one undoable change. "
                        + "To reference the ID of an entity an earlier operation created, name that "
                        + "operation two ways: by position, $N.id for the operation at index N "
                        + "(0-based), or by name, $name.id for the operation that carries "
                        + "as: \"name\". Prefer the name in a long call — a mistyped position is "
                        + "still a legal earlier operation and resolves silently to the wrong one. "
                        + "Max " + BulkOperation.MAX_OPERATIONS + " operations.");
        operationsProp.put("items", itemSchema);
        operationsProp.put("maxItems", BulkOperation.MAX_OPERATIONS);

        Map<String, Object> descriptionProp = new LinkedHashMap<>();
        descriptionProp.put("type", "string");
        descriptionProp.put("description",
                "Optional label for undo history");

        // Optional agent intent — the WHY of this batch, shown as a quiet 'agent's note:' on
        // the approval card. Distinct from 'description' (the undo-history label). Never required.
        Map<String, Object> intentProp = new LinkedHashMap<>();
        intentProp.put("type", "string");
        intentProp.put("description",
                "Optional plain-language intent for this batch (the WHY). Shown as a quiet "
                        + "'agent's note:' on the human approval card; never required, never affects behaviour.");

        Map<String, Object> continueOnErrorProp = new LinkedHashMap<>();
        continueOnErrorProp.put("type", "boolean");
        continueOnErrorProp.put("description",
                "When true, valid operations execute even if others fail. "
                        + "Failed operations are reported separately in the 'failed' array. "
                        + "A back-reference ($N.id or $name.id) to a failed operation cascades "
                        + "failure to dependent operations. Default: false (all-or-nothing atomic semantics).");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("operations", operationsProp);
        properties.put("description", descriptionProp);
        properties.put("intent", intentProp);
        properties.put("continueOnError", continueOnErrorProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("operations"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("bulk-mutate")
                .description("[Mutation] Execute multiple mutations as a single compound command. "
                        + "By default (continueOnError: false), all operations are "
                        + "pre-validated before any execute — if any fails validation, none are applied. "
                        + "Validation does not stop at the first failure: the refusal names the first "
                        + "one in its message and, when more than one failed, lists every one of them "
                        + "in a 'failed' array carrying each operation's own index, tool and error "
                        + "code. Fix them all and resend once rather than one per round-trip. Rows do "
                        + "not repeat what they share: a long ending common to every row of one error "
                        + "code is published once beside the array — in 'messages' and 'corrections', "
                        + "keyed by that error code — and the row names it with 'messageRef' or "
                        + "'correctionRef'. A split row keeps its own head under the field's own name, "
                        + "so head followed by the shared string is the whole value; a row that shares "
                        + "the WHOLE of its suggestedCorrection carries 'correctionRef' and no "
                        + "'suggestedCorrection' at all, so resolve the reference rather than reading "
                        + "that row as having no remedy. An "
                        + "operation whose shape cannot be read at all — not an object, naming no "
                        + "supported tool, or carrying a top-level key that is not tool, params or "
                        + "as — is reported the same way under INVALID_PARAMETER, and no flag makes "
                        + "such an operation runnable: continueOnError does not apply to it and the "
                        + "whole call is refused, because an operation that is skipped is not sent "
                        + "on at all and every later $N.id would come to mean a different "
                        + "operation. "
                        + "Validation happens before any operation runs, so an operation can still "
                        + "decline once the changes are being applied: if it would destroy something "
                        + "an earlier operation in the same call created — a folder delete whose "
                        + "folder was just filled — or if the container it named has since been "
                        + "removed from the model — a create into a folder an earlier operation "
                        + "deleted — it is skipped and its reason listed in "
                        + "skippedOperations. Once the changes have been applied that also makes "
                        + "allSucceeded false; queued into a batch or awaiting approval nothing has "
                        + "run yet, so no verdict is reported at all and the field is absent. The "
                        + "per-operation entries are built before the changes are applied, so one "
                        + "can name an action that did not happen; skippedOperations is the "
                        + "authority. "
                        + "With continueOnError: true, valid operations execute and failed ones are "
                        + "reported separately in the 'failed' array. A back-reference ($N.id or "
                        + "$name.id) to a failed operation cascades failure to dependent operations. "
                        + "Supports back-references, written two ways in parameter values and "
                        + "mixable in one call. BY POSITION: $N.id is the entity created by the "
                        + "operation at index N (0-indexed: $0 is the first operation, $1 the "
                        + "second); from operation N, use $(N-1).id for the operation immediately "
                        + "before this one. BY NAME: give an operation as: \"coreBanking\" — a "
                        + "sibling of tool and params, never a key inside params — and write "
                        + "$coreBanking.id. A REFERENCE MUST BE THE WHOLE VALUE of a top-level "
                        + "params entry: \"$0.id\" is substituted, \"grp-$0.id\" is not, and neither "
                        + "is a reference sitting inside a nested array or object. Anything not "
                        + "substituted is passed through as the literal text you wrote, and that text "
                        + "is not an id — so the operation fails naming the literal \"$0.id\" it was "
                        + "handed, which is the signal that a reference was never substituted rather "
                        + "than pointed at the wrong thing. Build the id by reference alone; do not decorate it. "
                        + "PREFER THE NAME in a long or reordered call: a "
                        + "mistyped position is still a legal earlier operation, so it resolves "
                        + "silently into a container nothing will reject, while a mistyped name is "
                        + "refused in every mode — including inside a batch and awaiting approval, "
                        + "where no response field names the container at all. Both forms are "
                        + "governed by everything that follows. Back-references can only "
                        + "point to operations earlier in the batch — neither the current operation "
                        + "(self-reference) nor any future operation is allowed. The "
                        + "referenced operation must be a create-tool (create-element, "
                        + "create-relationship, create-view, add-to-view, add-connection-to-view, "
                        + "add-group-to-view, add-note-to-view) — update/delete operations cannot "
                        + "be back-referenced. What a resolved reference may then be USED as depends on "
                        + "what the referenced operation made: the four that place something on a "
                        + "view yield an id that is also a valid target for the matching update "
                        + "in the same call — add-to-view, add-group-to-view and add-note-to-view "
                        + "for update-view-object, add-connection-to-view for "
                        + "update-view-connection — so an object can be created and then re-sized, "
                        + "re-titled or re-styled without ending the call. A note is such a target "
                        + "but is never a valid parentViewObjectId: only groups and element view "
                        + "objects can contain anything. A "
                        + "create-element or create-relationship id is a model concept with no view "
                        + "object at all until an add-to-view or add-connection-to-view places it, "
                        + "so back-reference THAT operation when you need a view target. Max "
                        + BulkOperation.MAX_OPERATIONS + " operations per call. "
                        + "Required: operations (array of {tool, params} objects, each optionally "
                        + "carrying as: \"name\"). "
                        + "Optional: description (label for undo history), "
                        + "intent (plain-language WHY, shown as a quiet note on the human approval card), "
                        + "continueOnError (boolean, default false). "
                        + "Supported tools: " + supportedTools + ". "
                        + "add-image-to-model is deliberately absent and will not be added: an "
                        + "archive write is not undoable (Archi exposes no removal API), so it can "
                        + "neither be rolled back when a proposal is declined nor deferred to "
                        + "execute time alongside the operations that place the image. To import "
                        + "many images in one call, pass add-image-to-model its own 'images' array, "
                        + "then reference the returned imagePath values from this call. "
                        + "This description covers batching semantics only: an operation's own "
                        + "parameters and response fields are governed by the standalone tool of "
                        + "the same name — read that tool's description for per-operation detail. "
                        + "One operation is an exception because no standalone tool exists for it: "
                        + "set-view-label-expression is bulk-only. It stamps a label expression "
                        + "onto every eligible object in one view — required viewId, required "
                        + "labelExpression (empty string clears it), optional objectTypes (array; "
                        + "non-empty subset of element, note, group; default element only) — and "
                        + "reports appliedCount and skippedCount. Objects outside objectTypes are "
                        + "skipped; blank-named objects are skipped only when setting a non-empty "
                        + "expression, never when clearing. "
                        + "Note: autoConnect is forced false for add-to-view in bulk context "
                        + "— use explicit add-connection-to-view operations instead. "
                        + "Use a reference to an add-group-to-view operation as parentViewObjectId in "
                        + "subsequent add-to-view or add-group-to-view operations to nest elements "
                        + "inside groups. The same reference also works as the viewObjectId of a later "
                        + "update-view-object in the same call, so a group can be created and then "
                        + "re-sized without ending the call. "
                        + "bulk-mutate may be called inside an open begin-batch: the call is queued "
                        + "into that batch like any other operation, its results are a preview, and "
                        + "whether its operations run is decided at end-batch. An id the enclosing "
                        + "batch created resolves here on every tool that places something on a "
                        + "view — add-to-view, add-group-to-view, add-note-to-view, "
                        + "add-image-to-view and add-view-reference-to-view — in the viewId placed "
                        + "on, the parentViewObjectId nested into, and the element or referenced "
                        + "view being placed; and as the target of update-view-object and "
                        + "update-view-connection. add-connection-to-view resolves one in all four "
                        + "of its ids — relationshipId, viewId and either endpoint — whether or not "
                        + "the operation also carries a back-reference of its own, so "
                        + "creating a relationship and connecting it to something the batch queued "
                        + "works in a single nested call. An operation name is scoped to its own "
                        + "call and never addresses the enclosing batch's queue. "
                        + "Tools that update, delete or re-file a concept rather than place it — "
                        + "update-element, update-relationship, update-view, remove-from-view, "
                        + "clear-view, set-view-label-expression, the delete-* family, "
                        + "move-to-folder, and create-relationship's endpoints — report such an id "
                        + "as not found; queue those operations individually in the open batch, or "
                        + "end-batch first and then call bulk-mutate. A back-reference to an "
                        + "object this same call created always wins over the enclosing batch's "
                        + "queue. "
                        + "Unlike single-tool mutations, bulk-mutate does not use result.preview: in "
                        + "batch or approval mode its normal result fields stay where they are and a "
                        + "batch or proposal object is added alongside them. modelChanged is false in "
                        + "both modes, and proposal carries validOperationCount and "
                        + "failedValidationCount only when some operations failed validation. "
                        + "Ids created by an approved bulk are stable, unlike single-tool "
                        + "mutations: approving applies the reviewed compound itself rather than "
                        + "rebuilding it, so the ids in the preview are the ids you get. "
                        + "The per-operation fields below report what the operation actually "
                        + "landed, and appear only once the changes have been applied — never in "
                        + "batch or approval mode, where nothing has executed. For an operation on a "
                        + "view object, effectiveBounds is the rectangle the model holds after the "
                        + "write, which can differ from what the operation asked for because Archi "
                        + "auto-fits a container to its children, and parentViewObjectId is the "
                        + "container it sits in, omitted when it sits on the view itself — read that "
                        + "one first, because effectiveBounds' x and y are RELATIVE to the parent's "
                        + "top-left corner whenever there is a parent, and because a "
                        + "back-reference that resolved to a container you did not intend is a legal "
                        + "nesting nothing will reject and this is where you see it — on an applied "
                        + "call only, which is why naming the operation is the surer guard; "
                        + "resizedAncestors "
                        + "names any container the "
                        + "operation GREW that it was not asked to touch, and movedObjects names any "
                        + "object ANCHORED to something it moved or grew, each carrying where it "
                        + "landed. For an operation on a view connection, effectiveConnection is the "
                        + "state the connection holds after the write — its styling, its typography, "
                        + "its label position, its routing points, and the view objects and anchor "
                        + "points it joins, which is the whole report the single-tool "
                        + "add-connection-to-view and update-view-connection return, nested under "
                        + "one key. It is read from the model afterwards rather than echoed from the "
                        + "operation, so its anchors reflect an endpoint that a LATER operation in "
                        + "the same call moved, which is a change no field on that later operation "
                        + "would tell you about. For an operation on an ArchiMate concept rather "
                        + "than something drawn on a view, effectiveRelationship and "
                        + "effectiveElement are the same thing again: the state the concept holds "
                        + "after the write, nested under one key. That is where a bulk caller who "
                        + "sets accessType, associationDirected or influenceStrength — or who "
                        + "rewrites the documentation of an element OR of a relationship — is told "
                        + "what those became, rather "
                        + "than only that the call succeeded. effectiveRelationship also names the "
                        + "two concepts the relationship joins, as sourceName and targetName, so a "
                        + "caller is not left holding two opaque ids. They are read after the write too, so "
                        + "an operation LATER in the same call that changes the same concept again "
                        + "is reflected in the earlier operation's report. A relationship subtype "
                        + "that cannot hold an attribute omits it, exactly as the single-tool "
                        + "response does: an Association reports associationDirected and no "
                        + "accessType. Each of them is omitted when it does not apply, and a "
                        + "concept report is attached only where the entry already says what its "
                        + "entity is, which every operation on a concept now does — a move-to-folder "
                        + "whose subject is an element or a relationship is described too, and its "
                        + "report carries that subject's exact type. "
                        + "entityName is not one of them and is reported in every mode, but "
                        + "it is read the same way: on a call that was applied it is the name the "
                        + "entity holds once EVERY operation has run, so an operation that renames "
                        + "— or one whose entity a later operation in the same call renames again "
                        + "— reports the final name rather than the name it started with, and a "
                        + "name cleared to an empty string is reported as an empty string rather "
                        + "than as the previous name. In batch or approval mode it is the name as "
                        + "prepared, because nothing has executed there. Operations that name "
                        + "nothing — a placed connection or note, and a removal — omit it entirely. "
                        + "A folder operation, a model update and a move-to-folder each name their "
                        + "subject and report its type. A deletion is the one case the read "
                        + "cannot reach, because the id stops resolving the moment the delete "
                        + "applies, so it is not read that way: the deleting command records the "
                        + "name at the instant it destroys the entity, and that is what both "
                        + "entityName and deletion.name report — the name it was destroyed under "
                        + "even when an earlier operation in the same call renamed it first. A "
                        + "deletion only records a name if it actually destroyed something: one "
                        + "named in skippedOperations, and one whose subject an earlier operation "
                        + "in the same call had already deleted, both leave deletion.name as "
                        + "prepared. Their entityName follows the ordinary rule above — for a "
                        + "deletion that declined, that is the name its still-present subject "
                        + "holds now. "
                        + "An operation named in skippedOperations reports no resizedAncestors and "
                        + "no movedObjects: it grew and moved nothing, so what it was prepared to "
                        + "change is withdrawn rather than left standing. It reports no "
                        + "effectiveConnection either: the only connection operation that can "
                        + "decline is add-connection-to-view, and a connection it declined to create "
                        + "was never joined to anything, so there is nothing to describe. "
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

            // Parse operations. Every malformed entry is collected and they are reported
            // together: a payload with three misspelled tool names is one mistake made three
            // times, and naming one of them per round-trip makes the caller rebuild and resend
            // the whole array to discover the next. continueOnError is deliberately not consulted
            // — an operation whose shape cannot be read cannot be executed under any flag.
            List<BulkOperation> operations = new ArrayList<>();
            List<BulkOperationFailure> malformed = new ArrayList<>();
            // The first offender's correction in full. Two of the four shapes below want to name
            // every supported tool, which is a long list, and a row repeats its correction once
            // per failure. So the rows carry a short form and the refusal carries this one — the
            // caller still learns the list exactly once, and a call with a single malformed
            // operation reads precisely as it always did.
            String firstFullCorrection = null;
            for (int i = 0; i < opsList.size(); i++) {
                Object opRaw = opsList.get(i);
                if (!(opRaw instanceof Map<?, ?> opMap)) {
                    String correction = "Each operation must be {\"tool\": \"...\", \"params\": {...}}";
                    if (malformed.isEmpty()) {
                        firstFullCorrection = correction;
                    }
                    malformed.add(new BulkOperationFailure(i, null,
                            ErrorCode.INVALID_PARAMETER.name(),
                            "Operation at index " + i + " must be an object with 'tool' and 'params'",
                            correction));
                    continue;
                }

                Object toolObj = opMap.get("tool");
                Object paramsObj = opMap.get("params");

                if (!(toolObj instanceof String toolName) || toolName.isBlank()) {
                    // This message does not name the supported tools, so unlike the
                    // unsupported-tool case below the list is the caller's only source. It is
                    // carried once, on the refusal, rather than once per row.
                    if (malformed.isEmpty()) {
                        firstFullCorrection =
                                "Provide a valid tool name: " + BulkOperation.SUPPORTED_TOOLS;
                    }
                    malformed.add(new BulkOperationFailure(i,
                            toolObj != null ? String.valueOf(toolObj) : null,
                            ErrorCode.INVALID_PARAMETER.name(),
                            "Operation at index " + i + ": missing or invalid 'tool' field",
                            "Provide a valid tool name"));
                    continue;
                }

                if (!(paramsObj instanceof Map<?, ?> paramsMap)) {
                    String correction = "Provide a params object with the tool's required parameters";
                    if (malformed.isEmpty()) {
                        firstFullCorrection = correction;
                    }
                    malformed.add(new BulkOperationFailure(i, toolName,
                            ErrorCode.INVALID_PARAMETER.name(),
                            "Operation at index " + i + ": missing or invalid 'params' field",
                            correction));
                    continue;
                }

                // Convert params to Map<String, Object>
                Map<String, Object> typedParams = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : paramsMap.entrySet()) {
                    if (entry.getKey() instanceof String key) {
                        typedParams.put(key, entry.getValue());
                    }
                }

                // Every other top-level key is a typo the caller cannot see. This loop reads only
                // the keys it recognises, so an operation carrying "As" or "label" would lose its
                // name in silence and the reference to that name would then fail on a different
                // operation entirely — the displaced blame the named form exists to remove.
                List<String> unrecognised = new ArrayList<>();
                for (Object key : opMap.keySet()) {
                    String name = String.valueOf(key);
                    if (!BulkOperation.OPERATION_KEYS.contains(name)) {
                        unrecognised.add(name);
                    }
                }
                Object asObj = opMap.get("as");
                // Both problems, not the first of them. An operation can carry a stray key AND a
                // name that is not a string, and reporting one sends the caller back for a second
                // round-trip to discover the other — the very cost reporting every bad operation
                // together exists to remove, reintroduced one field down.
                List<String> problems = new ArrayList<>();
                if (!unrecognised.isEmpty()) {
                    problems.add("unrecognised key(s) " + unrecognised);
                }
                if (asObj != null && !(asObj instanceof String)) {
                    problems.add("'as' must be a string naming this operation");
                }
                if (!problems.isEmpty()) {
                    // The accepted set rides on the ROW, not only on the call-level correction.
                    // The convention of carrying a correction once exists because two of the other
                    // shapes want to name all twenty-eight supported tools; three key names are not
                    // that, and without them a caller reading a row for an operation that was not
                    // the first to fail learns which key is wrong and never which one is right.
                    String accepted = "An operation may carry "
                            + String.join(", ", BulkOperation.OPERATION_KEYS) + ".";
                    if (malformed.isEmpty()) {
                        firstFullCorrection = accepted + " 'as' is an optional name a later "
                                + "operation can reference as \"$name.id\".";
                    }
                    malformed.add(new BulkOperationFailure(i, toolName,
                            ErrorCode.INVALID_PARAMETER.name(),
                            "Operation at index " + i + ": " + String.join("; ", problems)
                                    + ". " + accepted,
                            "Remove it, or correct the spelling"));
                    continue;
                }

                BulkOperation op = new BulkOperation(toolName, typedParams, (String) asObj);
                try {
                    op.validate();
                } catch (IllegalArgumentException e) {
                    // The message already enumerates every supported tool, so a second copy in
                    // the row is pure duplication and was being paid once per malformed
                    // operation. The refusal still carries the caller's usual correction in full.
                    if (malformed.isEmpty()) {
                        firstFullCorrection =
                                "Use a supported tool: " + BulkOperation.SUPPORTED_TOOLS;
                    }
                    malformed.add(new BulkOperationFailure(i, toolName,
                            ErrorCode.INVALID_PARAMETER.name(),
                            "Operation at index " + i + ": " + e.getMessage(),
                            "Use one of the supported tools named in the message"));
                    continue;
                }
                operations.add(op);
            }

            if (!malformed.isEmpty()) {
                return buildMalformedOperationsError(malformed, firstFullCorrection);
            }

            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);
            String description = HandlerUtils.optionalStringParam(args, "description");
            String intent = HandlerUtils.optionalStringParam(args, "intent");
            boolean continueOnError = Boolean.TRUE.equals(args.get("continueOnError"));
            BulkMutationResult result = accessor.executeBulk(
                    sessionId, operations, description, continueOnError, intent);

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
        // Queued into a batch, or parked awaiting a human, nothing in this call has executed: the
        // compound is collected for later, so an operation that will decline at commit has not
        // declined yet and there is no outcome to report. Claiming every operation succeeded there
        // is a verdict on work that has not happened — the same failure that already keeps
        // effectiveBounds and the collateral lists out of these two modes, and the one an agent
        // acts on hardest, because a success flag is what it checks before planning its next call.
        // The batch or proposal sibling added below is what those modes say instead.
        boolean executed = !result.isBatched() && !result.isProposal();
        if (executed) {
            resultMap.put("allSucceeded", result.allSucceeded());
        }
        boolean modelChanged = executed && succeededCount > 0;
        resultMap.put("modelChanged", modelChanged);
        // Operations that declined when the changes were applied. This map is built field by
        // field, so the DTO carrying them reaches the wire only if it is copied across here.
        // Without it allSucceeded goes false with nothing to explain why, and the per-operation
        // entry below still reports the action it was prepared to take.
        if (!result.skippedOperations().isEmpty()) {
            resultMap.put("skippedOperations", result.skippedOperations());
        }

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
            // Fan-out operations (e.g. set-view-label-expression) report how many objects
            // were affected vs left untouched; omitted for single-entity operations.
            if (opResult.appliedCount() != null) {
                opMap.put("appliedCount", opResult.appliedCount());
            }
            if (opResult.skippedCount() != null) {
                opMap.put("skippedCount", opResult.skippedCount());
            }
            // The geometry the model holds once the whole call has been applied — present only
            // for view-object operations that were actually dispatched. An operation that sized a
            // group cannot know at prepare time that a later operation will force it larger, so
            // this is the only field here that reports the outcome rather than the request.
            if (opResult.effectiveBounds() != null) {
                opMap.put("effectiveBounds", opResult.effectiveBounds());
            }
            // The container that view object sits in once the whole call has been applied. Without
            // it the rectangle above cannot be placed: a nested object's x/y are relative to its
            // immediate parent's top-left corner, so the geometry is a frame with no origin. Read
            // after dispatch rather than taken from the operation's request, because a request
            // names a parent as a reference still to be resolved, and one that resolved to a
            // container the caller did not intend is the case this exists to expose. Copied across
            // explicitly because this map is hand-built key by key, which is where the same
            // family's effective geometry was lost once before.
            if (opResult.parentViewObjectId() != null) {
                opMap.put("parentViewObjectId", opResult.parentViewObjectId());
            }
            // The label alignment that view object holds once the whole call has been applied.
            // A group, a note and a Grouping are stamped with their type default at placement, so
            // this reports a value the caller never asked for and could not otherwise learn — the
            // standalone placement tools serialize their whole prepared DTO and have always said
            // it. Copied across explicitly because this map is hand-built key by key, which is
            // where the same family's effective geometry was lost once before.
            if (opResult.textAlignment() != null) {
                opMap.put("textAlignment", opResult.textAlignment());
            }
            // Deletions carry the standalone deletion tool's cascade report, so the same delete
            // says the same thing whether it was called directly or as one operation in bulk.
            if (opResult.deletion() != null) {
                opMap.put("deletion", opResult.deletion());
            }
            // The state a connection holds once the whole call has been applied — the same report
            // the single-tool caller gets. Trustworthy because it is read from the model after
            // dispatch rather than projected when the operation was prepared: its anchors are
            // absolute canvas centres derived from the endpoints' live geometry, so an operation
            // later in the same call can move an endpoint and change them without ever naming the
            // connection. Populated only on the dispatched path, so it is already absent when the
            // call was queued into a batch or parked awaiting approval — nothing has been written
            // in those modes, and a projection there would be the lie the labelling exists to
            // avoid. Copied across explicitly because this map is hand-built key by key, which is
            // where the same family's effective geometry was lost once before.
            if (opResult.effectiveConnection() != null) {
                opMap.put("effectiveConnection", opResult.effectiveConnection());
            }
            // The same, for an operation whose entity is an ArchiMate concept rather than something
            // drawn on a view. These carry the state a bulk caller could change and was told
            // nothing about — a relationship's semantic attributes, an element's documentation —
            // and they are read after dispatch for the same reason the connection report is: an
            // operation later in the same call can change the same concept again, so a value
            // captured when this operation was prepared would describe neither the request nor the
            // result. Copied across explicitly for the reason above them.
            if (opResult.effectiveRelationship() != null) {
                opMap.put("effectiveRelationship", opResult.effectiveRelationship());
            }
            if (opResult.effectiveElement() != null) {
                opMap.put("effectiveElement", opResult.effectiveElement());
            }
            // Objects this operation grew or displaced that it was never asked to touch. Neither
            // is the operation's own entity, so effectiveBounds above can never describe them, and
            // the standalone tool reports both — omitting them here left the bulk caller blind to
            // changes the single-tool caller is told about. They are computed, re-read live after
            // dispatch, and were being dropped at this boundary rather than upstream, so a typed
            // assertion on the result object passed while nothing reached the wire.
            //
            // Gated on the same condition as modelChanged, for the same reason effectiveBounds is
            // absent in those modes. Both lists are built at PREPARE time from commands that have
            // not run, and only the dispatched path re-reads them against live containment. Queued
            // into a batch, or parked awaiting a human, they describe a growth that has not
            // happened to an object that may not exist yet — a projection wearing the name of a
            // measurement, which is the failure this field was added to end rather than relocate.
            if (modelChanged) {
                if (!opResult.resizedAncestors().isEmpty()) {
                    opMap.put("resizedAncestors", opResult.resizedAncestors());
                }
                if (!opResult.movedObjects().isEmpty()) {
                    opMap.put("movedObjects", opResult.movedObjects());
                }
            }
            opResults.add(opMap);
        }
        resultMap.put(hasFailures ? "succeeded" : "operations", opResults);

        // Failed operations. The dictionary of what those rows share is their sibling here, as it
        // is inside the error object on the refusal paths: wherever the rows are published, what
        // they reference is published beside them, so a row is never an index into a map the
        // caller did not receive.
        if (hasFailures) {
            HandlerUtils.putFailures(resultMap, result.failedOperations());
        }

        // Approval mode: add proposal info
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
            nextSteps = new ArrayList<>();
            nextSteps.add("This bulk change is pending the human's approval and was NOT applied");
            nextSteps.add("Tell the user to approve or reject it in Archi (the agent cannot approve its own changes)");
            nextSteps.add("Use list-pending-approvals to see all changes awaiting the human's decision");
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
     * Formats the refusal for operations whose shape could not be read.
     *
     * <p>Kept at {@code INVALID_PARAMETER} rather than promoted to the whole-call validation code:
     * these are malformed request shapes caught before any model access, and re-coding them would
     * make one error code describe two unrelated things. As with the validation refusal, the
     * scalar fields describe the first offender and are unchanged when there is only one.</p>
     */
    private McpSchema.CallToolResult buildMalformedOperationsError(
            List<BulkOperationFailure> malformed, String firstFullCorrection) {
        BulkOperationFailure first = malformed.get(0);
        String message = first.message();
        String details = null;
        if (malformed.size() > 1) {
            message += " — " + malformed.size()
                    + " operations could not be read; every one is listed.";
            details = "malformedOperationCount=" + malformed.size();
        }

        ErrorResponse error = new ErrorResponse(
                ErrorCode.INVALID_PARAMETER, message, details,
                firstFullCorrection != null ? firstFullCorrection : first.suggestedCorrection(),
                null);

        Map<String, Object> extras = new LinkedHashMap<>();
        if (malformed.size() > 1) {
            HandlerUtils.putFailures(extras, malformed);
        }

        List<String> nextSteps = new ArrayList<>();
        int named = Math.min(malformed.size(), HandlerUtils.NEXT_STEP_FAILURE_LIMIT);
        for (int i = 0; i < named; i++) {
            nextSteps.add("Fix " + HandlerUtils.headline(malformed.get(i).message()));
        }
        int unnamed = malformed.size() - named;
        if (unnamed > 0) {
            nextSteps.add(unnamed + " further malformed " + (unnamed == 1 ? "operation is" : "operations are")
                    + " not listed here — read the 'failed' array in the error for all "
                    + malformed.size());
        }
        nextSteps.add("Nothing was applied — no operation ran, including the well-formed ones");

        return HandlerUtils.buildResult(
                formatter.toJsonString(formatter.formatErrorWithExtras(
                        error, extras, nextSteps, accessor.getModelVersion())), true);
    }

    /**
     * Formats a bulk validation failure error response.
     *
     * <p>Every operation that failed pre-validation is carried, not only the one that failed
     * first. The scalar fields still describe the first failure alone and are unchanged for a call
     * with one bad operation — the common case, where a count would say nothing and cost the
     * caller the detail it used to get.</p>
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

        List<BulkOperationFailure> failures = e instanceof BulkValidationException bulk
                ? bulk.getFailures()
                : List.of();

        // One failure is already fully described by the fields above; a one-row array beside them
        // is noise, and its absence is what keeps that call's refusal exactly what it always was.
        Map<String, Object> extras = new LinkedHashMap<>();
        if (failures.size() > 1) {
            HandlerUtils.putFailures(extras, failures);
        }

        List<String> nextSteps = new ArrayList<>();
        int named = Math.min(failures.size(), HandlerUtils.NEXT_STEP_FAILURE_LIMIT);
        for (int i = 0; i < named; i++) {
            BulkOperationFailure failure = failures.get(i);
            nextSteps.add("Fix operation " + failure.index() + " (" + failure.tool() + "): "
                    + HandlerUtils.headline(failure.message()));
        }
        int unnamed = failures.size() - named;
        if (unnamed > 0) {
            nextSteps.add(unnamed + " further failed " + (unnamed == 1 ? "operation is" : "operations are")
                    + " not listed here — read the 'failed' array in the error for all "
                    + failures.size());
        }
        nextSteps.add("Nothing was applied — fix every operation listed and resend the whole call");

        return HandlerUtils.buildResult(
                formatter.toJsonString(formatter.formatErrorWithExtras(
                        error, extras, nextSteps, accessor.getModelVersion())), true);
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
