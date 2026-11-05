package net.vheerden.archi.mcp.response.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * DTO for a pending mutation proposal summary.
 *
 * <p>Used in {@code list-pending-approvals} responses and mutation tool
 * responses when approval mode is active. Omits the GEF Command —
 * handlers never touch EMF types.</p>
 *
 * <p>Two nullable trailing fields — {@code effectDescription} (server-owned rich
 * effect text) and {@code intent} (the agent's optional stated reason). Both are {@code @JsonInclude
 * NON_NULL}, so when absent they do not appear on the wire and existing proposals serialize
 * byte-identically. They are never merged — different trust levels.</p>
 *
 * @param proposalId        unique identifier (e.g., "p-1")
 * @param tool              the MCP tool name that generated this proposal
 * @param status            proposal status ("pending", "approved", "rejected")
 * @param description       human-readable description of the proposed mutation
 * @param currentState      snapshot of current state before mutation (null for creates)
 * @param proposedChanges   map of field names to proposed values
 * @param validationSummary human-readable validation result summary
 * @param createdAt         ISO 8601 timestamp when the proposal was created
 * @param effectDescription server-owned rich effect text; omitted from JSON when null
 * @param intent            the agent's optional stated intent; omitted from JSON when null
 * @param modelId           id of the model the proposal will write to (the model active when it
 *                          was proposed); omitted from JSON when null
 * @param modelName         name of that model, for the approval card; omitted from JSON when null
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
    String createdAt,
    String effectDescription,
    String intent,
    String modelId,
    String modelName
) {
    /**
     * Back-compat constructor at the prior canonical arity: no target model named.
     */
    public ProposalDto(
        String proposalId,
        String tool,
        String status,
        String description,
        Map<String, Object> currentState,
        Map<String, Object> proposedChanges,
        String validationSummary,
        String createdAt,
        String effectDescription,
        String intent
    ) {
        this(proposalId, tool, status, description, currentState, proposedChanges,
                validationSummary, createdAt, effectDescription, intent, null, null);
    }

    /**
     * Back-compat constructor for call-sites that carry neither the server
     * {@code effectDescription} nor the agent {@code intent}. Delegates with both null,
     * preserving NON_NULL wire omission.
     */
    public ProposalDto(
        String proposalId,
        String tool,
        String status,
        String description,
        Map<String, Object> currentState,
        Map<String, Object> proposedChanges,
        String validationSummary,
        String createdAt
    ) {
        this(proposalId, tool, status, description, currentState,
                proposedChanges, validationSummary, createdAt, null, null);
    }
}
