package net.vheerden.archi.mcp.response.dto;

/**
 * Result DTO for the apply-positions compound operation (Story 9-0a, renamed 11-8).
 */
public record ApplyViewLayoutResultDto(
    String viewId,
    int positionsUpdated,
    int connectionsUpdated,
    int totalOperations) {}
