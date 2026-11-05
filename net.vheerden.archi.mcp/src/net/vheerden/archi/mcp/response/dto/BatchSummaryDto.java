package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * DTO for batch commit/rollback result summary.
 *
 * <p>Returned by end-batch to summarize what happened to the queued mutations.</p>
 *
 * <p>{@code skippedOperations} names any queued operation that declined to run when the
 * batch was applied, each with the reason. An operation is skipped only to avoid
 * destroying something the request never authorised — typically a delete whose target
 * was changed by an earlier operation in the same batch. It is null, and therefore
 * omitted from JSON, for the overwhelmingly common case where everything ran.
 * {@code operationCount} counts what was queued, so it still includes any skipped
 * operation; the two fields must be read together.</p>
 *
 * <p>{@code nothingApplied} is true when a commit's every operation declined with nothing written, so
 * the commit left no undo step and the model as it was. It is null, and omitted, in every other
 * case.</p>
 *
 * <p>{@code boundModelId} / {@code boundModelName} name the model the batch was bound to, and are
 * set only on a rollback made while that model was not the active one (switched away from, or
 * closed). Such a rollback is always allowed, so this is what keeps the discard attributable; on
 * every other end-batch they are null and omitted.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchSummaryDto(
    int operationCount,
    List<String> descriptions,
    String duration,
    boolean rolledBack,
    List<String> skippedOperations,
    Boolean nothingApplied,
    String boundModelId,
    String boundModelName
) {

    /**
     * Back-compat constructor at the prior canonical arity: something was applied, or nothing was
     * committed.
     */
    public BatchSummaryDto(int operationCount, List<String> descriptions, String duration,
            boolean rolledBack, List<String> skippedOperations, String boundModelId, String boundModelName) {
        this(operationCount, descriptions, duration, rolledBack, skippedOperations, null, boundModelId,
                boundModelName);
    }

    /**
     * Back-compat constructor at the prior canonical arity: no bound model named.
     */
    public BatchSummaryDto(int operationCount, List<String> descriptions, String duration,
            boolean rolledBack, List<String> skippedOperations) {
        this(operationCount, descriptions, duration, rolledBack, skippedOperations, null, null, null);
    }
}
