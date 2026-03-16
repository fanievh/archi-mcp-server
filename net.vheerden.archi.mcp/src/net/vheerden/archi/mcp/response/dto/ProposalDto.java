package net.vheerden.archi.mcp.response.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * DTO for a pending mutation proposal summary (Story 7-6).
 *
 * <p>Used in {@code list-pending-approvals} responses and mutation tool
 * responses when approval mode is active. Omits the GEF Command —
 * handlers never touch EMF types.</p>
 *
 * @param proposalId        unique identifier (e.g., "p-1")
 * @param tool              the MCP tool name that generated this proposal
 * @param status            proposal status ("pending", "approved", "rejected")
 * @param description       human-readable description of the proposed mutation
 * @param currentState      snapshot of current state before mutation (null for creates)
 * @param proposedChanges   map of field names to proposed values
 * @param validationSummary human-readable validation result summary
 * @param createdAt         ISO 8601 timestamp when the proposal was created
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProposalDto(
    String proposalId,
    String tool,
    String status,
    String description,
    Map<String, Object> currentState,
    Map<String, Object> proposedChanges,
    String validationSummary,
    String createdAt
) {
}
