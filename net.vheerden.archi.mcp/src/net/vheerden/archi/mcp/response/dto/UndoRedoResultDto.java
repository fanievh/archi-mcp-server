package net.vheerden.archi.mcp.response.dto;

import java.util.List;

/**
 * Result DTO for undo/redo operations (Story 11-1).
 */
public record UndoRedoResultDto(
		int operationsRequested,
		int operationsPerformed,
		List<String> labels,
		boolean canUndoAfter,
		boolean canRedoAfter) {
}
