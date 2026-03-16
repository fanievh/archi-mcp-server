package net.vheerden.archi.mcp.model;

/**
 * Wraps the result of a mutation operation, supporting GUI-attached (immediate),
 * batch (queued), and approval (proposed) modes.
 *
 * <p>In GUI-attached mode, {@code batchSequenceNumber} and {@code proposalContext}
 * are null, and {@code entity} contains the created/modified DTO.</p>
 *
 * <p>In batch mode, {@code batchSequenceNumber} holds the queue position and
 * {@code entity} contains the DTO for the element created in memory (not yet committed).</p>
 *
 * <p>In approval mode, {@code proposalContext} holds the proposal ID and metadata,
 * and {@code entity} contains the proposed DTO (not yet applied to the model).</p>
 *
 * @param <T> the entity DTO type (e.g., ElementDto, RelationshipDto)
 */
public record MutationResult<T>(T entity, Integer batchSequenceNumber, ProposalContext proposalContext) {

    /**
     * Convenience constructor for non-proposal results (backward compatible).
     */
    public MutationResult(T entity, Integer batchSequenceNumber) {
        this(entity, batchSequenceNumber, null);
    }

    /**
     * Returns true if this mutation was queued for batch execution
     * rather than dispatched immediately.
     */
    public boolean isBatched() {
        return batchSequenceNumber != null;
    }

    /**
     * Returns true if this mutation was stored as a proposal awaiting
     * human approval rather than executed.
     */
    public boolean isProposal() {
        return proposalContext != null;
    }
}
