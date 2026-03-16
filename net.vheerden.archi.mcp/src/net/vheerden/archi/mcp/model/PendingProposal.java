package net.vheerden.archi.mcp.model;

import java.time.Instant;
import java.util.Map;

import org.eclipse.gef.commands.Command;

/**
 * Stores a mutation proposal awaiting human approval (Story 7-6).
 *
 * <p>Package-private — only used within the {@code model/} package by
 * {@link MutationContext} and {@link MutationDispatcher}. Holds the GEF
 * Command and all metadata needed to present the proposal and execute
 * it on approval.</p>
 *
 * <p>The Command is held in memory until approved (dispatched) or rejected
 * (discarded). If the model changes between proposal and approval, the
 * Command execution may fail — this is handled as a "stale proposal" error.</p>
 *
 * @param proposalId        unique identifier (e.g., "p-1"), assigned by MutationContext
 * @param tool              the MCP tool name (e.g., "create-element", "bulk-mutate")
 * @param description       human-readable description of the proposed mutation
 * @param command           the GEF Command ready for CommandStack execution
 * @param entity            the DTO representing the proposed result (ElementDto, etc.)
 * @param currentState      snapshot of current state before mutation (null for creates)
 * @param proposedChanges   map of field names to proposed values
 * @param validationSummary human-readable validation result summary
 * @param createdAt         timestamp when the proposal was created
 */
record PendingProposal(
    String proposalId,
    String tool,
    String description,
    Command command,
    Object entity,
    Map<String, Object> currentState,
    Map<String, Object> proposedChanges,
    String validationSummary,
    Instant createdAt
) {
}
