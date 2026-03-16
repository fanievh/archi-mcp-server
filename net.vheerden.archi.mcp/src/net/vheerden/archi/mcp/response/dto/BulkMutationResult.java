package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import net.vheerden.archi.mcp.model.ProposalContext;

/**
 * Aggregated result of a bulk-mutate operation (Story 7-5, enhanced Story 11-9).
 *
 * <p>When {@code continueOnError} is false (default), all operations succeed or none do,
 * and {@code failedOperations} is empty. When {@code continueOnError} is true, succeeded
 * and failed operations are reported separately.</p>
 *
 * @param operations           per-operation results in order (succeeded operations only)
 * @param failedOperations     per-operation failure details (empty when continueOnError is false)
 * @param totalOperations      total number of operations in the bulk
 * @param allSucceeded         true if all operations completed successfully
 * @param batchSequenceNumber  sequence number if queued in batch mode, null for immediate
 * @param proposalContext      proposal context if stored for approval, null otherwise
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record BulkMutationResult(
    List<BulkOperationResult> operations,
    List<BulkOperationFailure> failedOperations,
    int totalOperations,
    boolean allSucceeded,
    Integer batchSequenceNumber,
    ProposalContext proposalContext
) {

    /**
     * Convenience constructor for non-continueOnError results (backward compatible).
     */
    public BulkMutationResult(List<BulkOperationResult> operations,
            int totalOperations, boolean allSucceeded,
            Integer batchSequenceNumber) {
        this(operations, List.of(), totalOperations, allSucceeded, batchSequenceNumber, null);
    }

    /**
     * Convenience constructor for non-continueOnError results with proposal context.
     */
    public BulkMutationResult(List<BulkOperationResult> operations,
            int totalOperations, boolean allSucceeded,
            Integer batchSequenceNumber, ProposalContext proposalContext) {
        this(operations, List.of(), totalOperations, allSucceeded, batchSequenceNumber, proposalContext);
    }

    /**
     * Returns true if this bulk mutation was queued for batch execution
     * rather than dispatched immediately.
     */
    public boolean isBatched() {
        return batchSequenceNumber != null;
    }

    /**
     * Returns true if this bulk mutation was stored as a proposal awaiting
     * human approval rather than executed.
     */
    public boolean isProposal() {
        return proposalContext != null;
    }
}
