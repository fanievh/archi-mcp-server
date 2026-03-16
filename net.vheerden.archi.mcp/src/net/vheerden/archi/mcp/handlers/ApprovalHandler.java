package net.vheerden.archi.mcp.handlers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.ApprovalResult;
import net.vheerden.archi.mcp.model.ArchiModelAccessor;
import net.vheerden.archi.mcp.model.ModelAccessException;
import net.vheerden.archi.mcp.model.MutationDispatcher;
import net.vheerden.archi.mcp.model.NoModelLoadedException;
import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.ProposalDto;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Handler for human-in-the-loop approval tools (Story 7-6).
 *
 * <p>Provides 3 MCP tools: {@code set-approval-mode}, {@code list-pending-approvals},
 * and {@code decide-mutation}. When approval mode is active, all mutations become
 * proposals that require explicit approval before being applied to the model.</p>
 *
 * <p><strong>Architecture boundary:</strong> This class MUST NOT import
 * any EMF, GEF, SWT, or ArchimateTool model types. All mutation logic
 * goes through {@link MutationDispatcher} facade methods.</p>
 */
public class ApprovalHandler {

    private static final Logger logger = LoggerFactory.getLogger(ApprovalHandler.class);

    private final ArchiModelAccessor accessor;
    private final ResponseFormatter formatter;
    private final CommandRegistry registry;
    private final SessionManager sessionManager;

    public ApprovalHandler(ArchiModelAccessor accessor,
                           ResponseFormatter formatter,
                           CommandRegistry registry,
                           SessionManager sessionManager) {
        this.accessor = Objects.requireNonNull(accessor, "accessor must not be null");
        this.formatter = Objects.requireNonNull(formatter, "formatter must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.sessionManager = sessionManager;
    }

    public void registerTools() {
        registry.registerTool(buildSetApprovalModeSpec());
        registry.registerTool(buildListPendingApprovalsSpec());
        registry.registerTool(buildDecideMutationSpec());
    }

    // ---- set-approval-mode ----

    private McpServerFeatures.SyncToolSpecification buildSetApprovalModeSpec() {
        Map<String, Object> enabledProp = new LinkedHashMap<>();
        enabledProp.put("type", "boolean");
        enabledProp.put("description",
                "true to enable approval mode (mutations become proposals), "
                + "false to disable (mutations apply immediately)");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("enabled", enabledProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("enabled"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("set-approval-mode")
                .description("[Session] Enable or disable human-in-the-loop approval mode. "
                        + "When enabled, all mutations (create, update, bulk-mutate) become "
                        + "proposals that require explicit approval before being applied to "
                        + "the model. Existing pending proposals remain accessible regardless "
                        + "of mode. Required: enabled (boolean). Related: "
                        + "list-pending-approvals (view proposals), decide-mutation "
                        + "(approve/reject), get-batch-status (mode overview).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleSetApprovalMode)
                .build();
    }

    McpSchema.CallToolResult handleSetApprovalMode(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling set-approval-mode request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            MutationDispatcher dispatcher = requireDispatcher();
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            Object enabledVal = args != null ? args.get("enabled") : null;
            if (!(enabledVal instanceof Boolean enabled)) {
                throw new ModelAccessException(
                        "Missing or invalid required parameter: enabled (must be boolean)",
                        ErrorCode.INVALID_PARAMETER);
            }

            dispatcher.setApprovalRequired(sessionId, enabled);

            int pendingCount = dispatcher.getPendingProposalDtos(sessionId).size();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("approvalMode", enabled);
            result.put("pendingCount", pendingCount);

            List<String> nextSteps;
            if (enabled) {
                nextSteps = List.of(
                        "Approval mode enabled — all mutations will require explicit approval",
                        "Use list-pending-approvals to view pending proposals",
                        "Use decide-mutation to approve or reject proposals");
            } else {
                if (pendingCount > 0) {
                    nextSteps = List.of(
                            "Approval mode disabled — mutations will apply immediately",
                            pendingCount + " pending proposal" + (pendingCount != 1 ? "s" : "")
                                    + " remain — use decide-mutation to resolve them");
                } else {
                    nextSteps = List.of(
                            "Approval mode disabled — mutations will apply immediately");
                }
            }

            String modelVersion = accessor.getModelVersion();
            Map<String, Object> envelope = formatter.formatSuccess(
                    result, nextSteps, modelVersion, 1, 1, false);

            return HandlerUtils.buildResult(formatter.toJsonString(envelope), false);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling set-approval-mode", e);
            return HandlerUtils.buildInternalError(formatter,
                    "An unexpected error occurred while setting approval mode");
        }
    }

    // ---- list-pending-approvals ----

    private McpServerFeatures.SyncToolSpecification buildListPendingApprovalsSpec() {
        Map<String, Object> properties = new LinkedHashMap<>();

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of(), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("list-pending-approvals")
                .description("[Session] List all pending mutation proposals awaiting approval "
                        + "for the current session. Each proposal shows what would change, "
                        + "current state (for updates), proposed state, and validation status. "
                        + "Returns empty list if no proposals pending. Related: "
                        + "set-approval-mode (toggle approval), decide-mutation (approve/reject).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleListPendingApprovals)
                .build();
    }

    McpSchema.CallToolResult handleListPendingApprovals(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling list-pending-approvals request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            MutationDispatcher dispatcher = requireDispatcher();
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            List<ProposalDto> proposals = dispatcher.getPendingProposalDtos(sessionId);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("pendingCount", proposals.size());
            result.put("proposals", proposals);
            result.put("approvalMode", dispatcher.isApprovalRequired(sessionId));

            List<String> nextSteps;
            if (proposals.isEmpty()) {
                nextSteps = List.of(
                        "No pending proposals",
                        "Use set-approval-mode to toggle approval mode");
            } else {
                nextSteps = List.of(
                        "Use decide-mutation with proposalId and decision 'approve' to apply a mutation",
                        "Use decide-mutation with proposalId and decision 'reject' to discard a mutation",
                        "Use decide-mutation with proposalId 'all' to approve or reject all pending");
            }

            String modelVersion = accessor.getModelVersion();
            Map<String, Object> envelope = formatter.formatSuccess(
                    result, nextSteps, modelVersion,
                    proposals.size(), proposals.size(), false);

            return HandlerUtils.buildResult(formatter.toJsonString(envelope), false);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling list-pending-approvals", e);
            return HandlerUtils.buildInternalError(formatter,
                    "An unexpected error occurred while listing pending approvals");
        }
    }

    // ---- decide-mutation ----

    private McpServerFeatures.SyncToolSpecification buildDecideMutationSpec() {
        Map<String, Object> proposalIdProp = new LinkedHashMap<>();
        proposalIdProp.put("type", "string");
        proposalIdProp.put("description",
                "Proposal ID to act on (e.g., 'p-1'), or 'all' to apply decision to all pending");

        Map<String, Object> decisionProp = new LinkedHashMap<>();
        decisionProp.put("type", "string");
        decisionProp.put("description", "Decision: 'approve' or 'reject'");

        Map<String, Object> reasonProp = new LinkedHashMap<>();
        reasonProp.put("type", "string");
        reasonProp.put("description",
                "Optional explanation for the decision, included in response");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("proposalId", proposalIdProp);
        properties.put("decision", decisionProp);
        properties.put("reason", reasonProp);

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("proposalId", "decision"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("decide-mutation")
                .description("[Mutation] Approve or reject a pending mutation proposal. "
                        + "Approved mutations are applied to the model immediately (or "
                        + "queued if batch mode is active). Rejected mutations are discarded "
                        + "with no model changes. Required: proposalId (string — proposal ID "
                        + "or 'all' for all pending), decision (string — 'approve' or 'reject'). "
                        + "Optional: reason (string — explanation for the decision, included "
                        + "in response). Related: list-pending-approvals (view proposals), "
                        + "set-approval-mode (toggle approval).")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleDecideMutation)
                .build();
    }

    McpSchema.CallToolResult handleDecideMutation(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling decide-mutation request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            MutationDispatcher dispatcher = requireDispatcher();
            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);

            Map<String, Object> args = request.arguments();
            String proposalId = HandlerUtils.requireStringParam(args, "proposalId");
            String decision = HandlerUtils.requireStringParam(args, "decision");
            String reason = HandlerUtils.optionalStringParam(args, "reason");

            if (!"approve".equals(decision) && !"reject".equals(decision)) {
                throw new ModelAccessException(
                        "Invalid decision: '" + decision + "'. Must be 'approve' or 'reject'.",
                        ErrorCode.INVALID_PARAMETER,
                        null,
                        "Use decision 'approve' to apply or 'reject' to discard",
                        null);
            }

            boolean isApprove = "approve".equals(decision);

            if ("all".equals(proposalId)) {
                return isApprove
                        ? handleApproveAll(dispatcher, sessionId, reason)
                        : handleRejectAll(dispatcher, sessionId, reason);
            }

            return isApprove
                    ? handleApproveSingle(dispatcher, sessionId, proposalId, reason)
                    : handleRejectSingle(dispatcher, sessionId, proposalId, reason);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (MutationException e) {
            return HandlerUtils.buildMutationError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling decide-mutation", e);
            return HandlerUtils.buildInternalError(formatter,
                    "An unexpected error occurred while deciding mutation");
        }
    }

    // ---- Single approve/reject ----

    private McpSchema.CallToolResult handleApproveSingle(
            MutationDispatcher dispatcher, String sessionId,
            String proposalId, String reason) {
        ApprovalResult result;
        try {
            result = dispatcher.approveProposal(sessionId, proposalId);
        } catch (MutationException e) {
            // Stale proposal — command execution failed
            throw new ModelAccessException(
                    "Proposal '" + proposalId + "' is stale — model state changed since "
                            + "proposal was created: " + e.getMessage(),
                    ErrorCode.PROPOSAL_STALE,
                    null,
                    "The proposal has been removed. Create a new mutation to apply the change.",
                    null);
        }

        if (result == null) {
            throw new ModelAccessException(
                    "Proposal not found: " + proposalId,
                    ErrorCode.PROPOSAL_NOT_FOUND,
                    null,
                    "Use list-pending-approvals to see available proposals",
                    null);
        }

        Map<String, Object> responseMap = new LinkedHashMap<>();
        responseMap.put("decision", "approved");
        responseMap.put("proposalId", proposalId);
        responseMap.put("tool", result.tool());
        responseMap.put("description", result.description());
        responseMap.put("entity", result.entity());
        if (reason != null) {
            responseMap.put("reason", reason);
        }
        if (result.batchSequenceNumber() != null) {
            responseMap.put("batchSequenceNumber", result.batchSequenceNumber());
        }

        int pendingCount = dispatcher.getPendingProposalDtos(sessionId).size();
        List<String> nextSteps = new ArrayList<>();
        nextSteps.add("Mutation applied successfully");
        if (pendingCount > 0) {
            nextSteps.add(pendingCount + " proposal" + (pendingCount != 1 ? "s" : "")
                    + " still pending — use list-pending-approvals to review");
        }

        String modelVersion = accessor.getModelVersion();
        Map<String, Object> envelope = formatter.formatSuccess(
                responseMap, nextSteps, modelVersion, 1, 1, false);

        return HandlerUtils.buildResult(formatter.toJsonString(envelope), false);
    }

    private McpSchema.CallToolResult handleRejectSingle(
            MutationDispatcher dispatcher, String sessionId,
            String proposalId, String reason) {
        ProposalDto rejected = dispatcher.rejectProposal(sessionId, proposalId);
        if (rejected == null) {
            throw new ModelAccessException(
                    "Proposal not found: " + proposalId,
                    ErrorCode.PROPOSAL_NOT_FOUND,
                    null,
                    "Use list-pending-approvals to see available proposals",
                    null);
        }

        Map<String, Object> responseMap = new LinkedHashMap<>();
        responseMap.put("decision", "rejected");
        responseMap.put("proposalId", proposalId);
        responseMap.put("tool", rejected.tool());
        responseMap.put("description", rejected.description());
        if (reason != null) {
            responseMap.put("reason", reason);
        }

        int pendingCount = dispatcher.getPendingProposalDtos(sessionId).size();
        List<String> nextSteps = new ArrayList<>();
        nextSteps.add("Proposal rejected — no changes made to model");
        if (pendingCount > 0) {
            nextSteps.add("Use list-pending-approvals to see remaining " + pendingCount
                    + " proposal" + (pendingCount != 1 ? "s" : ""));
        }

        String modelVersion = accessor.getModelVersion();
        Map<String, Object> envelope = formatter.formatSuccess(
                responseMap, nextSteps, modelVersion, 1, 1, false);

        return HandlerUtils.buildResult(formatter.toJsonString(envelope), false);
    }

    // ---- Approve/reject all ----

    private McpSchema.CallToolResult handleApproveAll(
            MutationDispatcher dispatcher, String sessionId, String reason) {
        List<ProposalDto> pending = dispatcher.getPendingProposalDtos(sessionId);
        if (pending.isEmpty()) {
            throw new ModelAccessException(
                    "No pending proposals to approve",
                    ErrorCode.APPROVAL_NOT_ACTIVE,
                    null,
                    "No pending proposals exist. Create mutations while approval mode is active to generate proposals.",
                    null);
        }

        List<Map<String, Object>> applied = new ArrayList<>();
        List<String> failedRemaining = new ArrayList<>();
        boolean staleEncountered = false;
        String staleMessage = null;

        // Process in order — stop on first failure
        for (ProposalDto p : pending) {
            if (staleEncountered) {
                failedRemaining.add(p.proposalId());
                continue;
            }
            try {
                ApprovalResult result = dispatcher.approveProposal(sessionId, p.proposalId());
                if (result != null) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("proposalId", p.proposalId());
                    entry.put("tool", result.tool());
                    entry.put("description", result.description());
                    entry.put("status", "approved");
                    applied.add(entry);
                }
            } catch (MutationException e) {
                staleEncountered = true;
                staleMessage = "Proposal '" + p.proposalId() + "' is stale: " + e.getMessage();
                failedRemaining.add(p.proposalId());
            }
        }

        Map<String, Object> responseMap = new LinkedHashMap<>();
        responseMap.put("decision", "approve-all");
        responseMap.put("totalProcessed", applied.size());
        responseMap.put("applied", applied);
        if (reason != null) {
            responseMap.put("reason", reason);
        }

        List<String> nextSteps = new ArrayList<>();
        if (staleEncountered) {
            responseMap.put("staleError", staleMessage);
            responseMap.put("unappliedProposals", failedRemaining);
            nextSteps.add(applied.size() + " proposal" + (applied.size() != 1 ? "s" : "")
                    + " applied before encountering stale proposal");
            nextSteps.add("Use list-pending-approvals to review remaining "
                    + failedRemaining.size() + " proposal"
                    + (failedRemaining.size() != 1 ? "s" : ""));
        } else {
            nextSteps.add("All " + applied.size() + " proposal"
                    + (applied.size() != 1 ? "s" : "") + " approved and applied");
            nextSteps.add("Use get-element or get-relationships to verify changes");
        }

        String modelVersion = accessor.getModelVersion();
        Map<String, Object> envelope = formatter.formatSuccess(
                responseMap, nextSteps, modelVersion,
                applied.size(), pending.size(), false);

        return HandlerUtils.buildResult(
                formatter.toJsonString(envelope), false);
    }

    private McpSchema.CallToolResult handleRejectAll(
            MutationDispatcher dispatcher, String sessionId, String reason) {
        List<ProposalDto> pending = dispatcher.getPendingProposalDtos(sessionId);
        if (pending.isEmpty()) {
            throw new ModelAccessException(
                    "No pending proposals to reject",
                    ErrorCode.APPROVAL_NOT_ACTIVE,
                    null,
                    "No pending proposals exist. Create mutations while approval mode is active to generate proposals.",
                    null);
        }

        List<Map<String, Object>> rejected = new ArrayList<>();
        for (ProposalDto p : pending) {
            dispatcher.rejectProposal(sessionId, p.proposalId());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("proposalId", p.proposalId());
            entry.put("tool", p.tool());
            entry.put("description", p.description());
            entry.put("status", "rejected");
            rejected.add(entry);
        }

        Map<String, Object> responseMap = new LinkedHashMap<>();
        responseMap.put("decision", "reject-all");
        responseMap.put("totalRejected", rejected.size());
        responseMap.put("rejected", rejected);
        if (reason != null) {
            responseMap.put("reason", reason);
        }

        List<String> nextSteps = List.of(
                "All " + rejected.size() + " proposal" + (rejected.size() != 1 ? "s" : "")
                        + " rejected — no changes made to model",
                "Use set-approval-mode to toggle approval mode");

        String modelVersion = accessor.getModelVersion();
        Map<String, Object> envelope = formatter.formatSuccess(
                responseMap, nextSteps, modelVersion,
                rejected.size(), rejected.size(), false);

        return HandlerUtils.buildResult(formatter.toJsonString(envelope), false);
    }

    // ---- Helper ----

    private MutationDispatcher requireDispatcher() {
        MutationDispatcher dispatcher = accessor.getMutationDispatcher();
        if (dispatcher == null) {
            throw new MutationException("Approval operations not supported by this accessor");
        }
        return dispatcher;
    }
}
