package net.vheerden.archi.mcp.response.dto;

/**
 * Result DTO for the compute-layout tool (Story 9-1, renamed 11-8).
 */
public record LayoutViewResultDto(
		String viewId,
		String algorithmUsed,
		String presetUsed,
		int elementsRepositioned,
		int connectionsCleared,
		int totalOperations) {
}
