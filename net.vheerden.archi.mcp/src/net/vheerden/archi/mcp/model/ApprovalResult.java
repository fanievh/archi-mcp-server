package net.vheerden.archi.mcp.model;

/**
 * Result of approving a pending mutation proposal (Story 7-6).
 *
 * <p>Returned by {@link MutationDispatcher#approveProposal(String, String)}.
 * Contains the entity DTO that was stored in the proposal (for the response)
 * and optionally the batch sequence number if the mutation was queued.</p>
 *
 * @param entity              the entity DTO (ElementDto, RelationshipDto, ViewDto, or List for bulk)
 * @param batchSequenceNumber batch sequence number if queued, null if dispatched immediately
 * @param tool                the MCP tool name that generated this proposal
 * @param description         human-readable description of the approved mutation
 */
public record ApprovalResult(
    Object entity,
    Integer batchSequenceNumber,
    String tool,
    String description
) {
}
