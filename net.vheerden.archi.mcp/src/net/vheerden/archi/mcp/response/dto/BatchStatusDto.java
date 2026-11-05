package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * DTO for batch operational mode status.
 *
 * <p>In GUI_ATTACHED mode, only {@code mode} is populated.
 * In BATCH mode, all batch fields are populated with queue details, including the model the
 * batch is bound to: the model that was active at {@code begin-batch}, and the only model its
 * queued work may be committed into.
 * Approval fields are populated when approval mode is active.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchStatusDto(
    String mode,
    Integer queuedCount,
    List<String> queuedDescriptions,
    String batchStarted,
    Boolean approvalRequired,
    Integer pendingApprovalCount,
    String boundModelId,
    String boundModelName
) {

    /**
     * Convenience constructor for backward compatibility (no approval fields).
     */
    public BatchStatusDto(String mode, Integer queuedCount,
            List<String> queuedDescriptions, String batchStarted) {
        this(mode, queuedCount, queuedDescriptions, batchStarted, null, null);
    }

    /**
     * Back-compat constructor at the prior canonical arity: no bound model.
     */
    public BatchStatusDto(String mode, Integer queuedCount, List<String> queuedDescriptions,
            String batchStarted, Boolean approvalRequired, Integer pendingApprovalCount) {
        this(mode, queuedCount, queuedDescriptions, batchStarted, approvalRequired,
                pendingApprovalCount, null, null);
    }
}
