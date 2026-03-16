package net.vheerden.archi.mcp.response.dto;

/**
 * Failure details for a single operation within a bulk-mutate response (Story 11-9).
 *
 * @param index               the 0-based position of this operation in the bulk array
 * @param tool                the tool that was attempted
 * @param errorCode           the error code (e.g., INVALID_PARAMETER, BACK_REFERENCE_FAILED)
 * @param message             human-readable error description
 * @param suggestedCorrection guidance on how to fix the operation
 */
public record BulkOperationFailure(
    int index,
    String tool,
    String errorCode,
    String message,
    String suggestedCorrection
) {}
