package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * DTO for batch commit/rollback result summary (Story 7-1).
 *
 * <p>Returned by end-batch to summarize what happened to the queued mutations.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchSummaryDto(
    int operationCount,
    List<String> descriptions,
    String duration,
    boolean rolledBack
) {}
