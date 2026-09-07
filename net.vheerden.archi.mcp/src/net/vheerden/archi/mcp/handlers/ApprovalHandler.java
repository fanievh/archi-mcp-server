package net.vheerden.archi.mcp.handlers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.ArchiModelAccessor;
import net.vheerden.archi.mcp.model.ModelAccessException;
import net.vheerden.archi.mcp.model.MutationDispatcher;
import net.vheerden.archi.mcp.model.NoModelLoadedException;
import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.ProposalDto;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Handler for the human-in-the-loop approval surface (control plane is owned by the human).
 *
 * <p>Provides <strong>1 MCP tool: {@code list-pending-approvals}</strong> — a read-only,
 * data-plane observation tool. The agent uses it to <em>see</em> what is awaiting the human's
 * decision so it can honestly say "you're gated — confirm in Archi"; it cannot act on the queue.</p>
 *
 * <p><strong>Control plane is the human's.</strong> The former {@code set-approval-mode}
 * (toggle) and {@code decide-mutation} (approve/reject) tools are removed from the MCP surface
 * entirely — the only robust guard against the agent moving its own gate is the setter not
 * existing on the agent side. The toggle now lives in the Archi SWT menu
 * ({@code net.vheerden.archi.mcp.server.ApprovalMode}); approve/reject lives in the UI-callable
 * {@code net.vheerden.archi.mcp.server.ApprovalService} that the Pending Approvals view binds to.</p>
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
        registry.registerTool(buildListPendingApprovalsSpec());
    }

    // ---- list-pending-approvals (read-only observation) ----

    private McpServerFeatures.SyncToolSpecification buildListPendingApprovalsSpec() {
        Map<String, Object> properties = new LinkedHashMap<>();

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of(), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("list-pending-approvals")
                .description("[Session] List the pending mutation proposals awaiting the human's "
                        + "approval for the current session. Each proposal shows what would change, "
                        + "current state (for updates), proposed state, and validation status. "
                        + "proposedChanges is the complete disclosure of the pending write: every "
                        + "parameter the approval will apply is in it, and a parameter the write "
                        + "will not apply is not, so it can be read as the authoritative account "
                        + "of what approving does — a contract test over every proposal site holds "
                        + "that, rather than it being maintained by hand. On update-view-object "
                        + "and update-view-connection, description and validationSummary are "
                        + "derived from that same map, so they name every aspect the call will "
                        + "change rather than only one of them. "
                        + "Returns empty list if no proposals pending. This is a READ-ONLY "
                        + "observation tool: approval mode is owned by the human in Archi — you "
                        + "cannot enable/disable it or approve/reject from here. When changes are "
                        + "pending, tell the user to approve or reject them in Archi. "
                        + "While the gate is on, every mutation returns its normal result under "
                        + "result.preview with a result.proposal sibling instead of at result, and "
                        + "the id of a newly created object in that preview is provisional — the "
                        + "object is rebuilt when the human approves and gets a different id. "
                        + "bulk-mutate and the two discovery create tools shape this differently "
                        + "— see their own descriptions.")
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
                        "No changes are pending the human's approval",
                        "Approval mode is owned by the human in Archi — it cannot be changed from here");
            } else {
                nextSteps = List.of(
                        "These changes are awaiting the human's decision and have NOT been applied",
                        "Tell the user to approve or reject them in Archi (the agent cannot approve its own changes)");
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

    // ---- Helper ----

    private MutationDispatcher requireDispatcher() {
        MutationDispatcher dispatcher = accessor.getMutationDispatcher();
        if (dispatcher == null) {
            throw new MutationException("Approval operations not supported by this accessor");
        }
        return dispatcher;
    }
}
