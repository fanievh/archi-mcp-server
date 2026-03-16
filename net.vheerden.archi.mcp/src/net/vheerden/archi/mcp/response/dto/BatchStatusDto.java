package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * DTO for batch operational mode status (Story 7-1, extended Story 7-6).
 *
 * <p>In GUI_ATTACHED mode, only {@code mode} is populated.
 * In BATCH mode, all batch fields are populated with queue details.
 * Approval fields are populated when approval mode is active.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchStatusDto(
    String mode,
    Integer queuedCount,
    List<String> queuedDescriptions,
    String batchStarted,
    Boolean approvalRequired,
    Integer pendingApprovalCount
) {

    /**
     * Convenience constructor for backward compatibility (no approval fields).
     */
    public BatchStatusDto(String mode, Integer queuedCount,
            List<String> queuedDescriptions, String batchStarted) {
        this(mode, queuedCount, queuedDescriptions, batchStarted, null, null);
    }
}
