package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import net.vheerden.archi.mcp.model.ProposalContext;

/**
 * Aggregated result of a bulk-mutate operation.
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
 * @param skippedOperations    reasons for any operation that declined to run when the changes
 *                             were applied, each explaining what it refused to destroy. Empty
 *                             (and omitted) in the normal case. Per-operation entries in
 *                             {@code operations} are built before the changes are applied, so an
 *                             operation named here reports an {@code action} that did not
 *                             actually happen — this list is the authority, and
 *                             {@code allSucceeded} is false whenever it is non-empty.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record BulkMutationResult(
    List<BulkOperationResult> operations,
    List<BulkOperationFailure> failedOperations,
    int totalOperations,
    boolean allSucceeded,
    Integer batchSequenceNumber,
    ProposalContext proposalContext,
    List<String> skippedOperations
) {

    /**
     * Builds a result whose {@code allSucceeded} accounts for declined operations as well as
     * failed ones, so a skip that has already happened can never be reported as a wholly
     * successful bulk mutation.
     *
     * <p><strong>It is an execution verdict, and only the dispatched path has one.</strong> When
     * this call was queued into an open batch or stored for approval, nothing has run: the skip
     * reasons are collected from a compound that has not executed, so they are necessarily empty
     * and this computes true for operations that may yet decline at commit. That is why the handler
     * omits the field entirely in those two modes rather than putting a verdict on the wire — see
     * {@code MutationHandler.formatBulkResponse}. Read {@code isBatched()} / {@code isProposal()}
     * before reading this.</p>
     */
    public static BulkMutationResult of(List<BulkOperationResult> operations,
            List<BulkOperationFailure> failedOperations, int totalOperations,
            Integer batchSequenceNumber, List<String> skippedOperations) {
        return new BulkMutationResult(operations, failedOperations, totalOperations,
                failedOperations.isEmpty() && skippedOperations.isEmpty(),
                batchSequenceNumber, null, skippedOperations);
    }

    /**
     * Convenience constructor for results carrying no declined operations.
     */
    public BulkMutationResult(List<BulkOperationResult> operations,
            List<BulkOperationFailure> failedOperations, int totalOperations,
            boolean allSucceeded, Integer batchSequenceNumber, ProposalContext proposalContext) {
        this(operations, failedOperations, totalOperations, allSucceeded,
                batchSequenceNumber, proposalContext, List.of());
    }

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
