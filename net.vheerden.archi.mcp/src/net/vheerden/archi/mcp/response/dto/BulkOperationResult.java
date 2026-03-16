package net.vheerden.archi.mcp.response.dto;

/**
 * Result of a single operation within a bulk-mutate response (Story 7-5).
 *
 * @param index      the 0-based position of this operation in the bulk array
 * @param tool       the tool that was executed
 * @param action     "created" or "updated"
 * @param entityId   the ID of the created/updated entity
 * @param entityType the ArchiMate type of the entity
 * @param entityName the name of the entity (may be null for unnamed relationships)
 */
public record BulkOperationResult(
    int index,
    String tool,
    String action,
    String entityId,
    String entityType,
    String entityName
) {}
