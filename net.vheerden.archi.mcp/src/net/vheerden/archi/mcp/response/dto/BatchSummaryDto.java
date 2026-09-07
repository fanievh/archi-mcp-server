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
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchSummaryDto(
    int operationCount,
    List<String> descriptions,
    String duration,
    boolean rolledBack,
    List<String> skippedOperations
) {}
