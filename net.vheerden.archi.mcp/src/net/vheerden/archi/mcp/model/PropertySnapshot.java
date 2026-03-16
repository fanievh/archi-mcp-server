package net.vheerden.archi.mcp.model;

/**
 * Immutable snapshot of a property key-value pair, used by mutation commands
 * to capture old property state for undo support.
 *
 * <p>Shared by {@link UpdateElementCommand} and {@link UpdateFolderCommand}
 * to avoid duplicating this structure.</p>
 */
record PropertySnapshot(String key, String value) {}
