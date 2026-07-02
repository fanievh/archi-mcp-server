package net.vheerden.archi.mcp.response.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result of a single operation within a bulk-mutate response.
 *
 * @param index        the 0-based position of this operation in the bulk array
 * @param tool         the tool that was executed
 * @param action       "created" or "updated"
 * @param entityId     the ID of the created/updated entity
 * @param entityType   the ArchiMate type of the entity
 * @param entityName   the name of the entity (may be null for unnamed relationships)
 * @param appliedCount for fan-out operations (e.g. set-view-label-expression), the number of
 *                     objects affected; null and omitted for single-entity operations
 * @param skippedCount for fan-out operations, the number of objects left untouched; null and
 *                     omitted for single-entity operations
 */
public record BulkOperationResult(
    int index,
    String tool,
    String action,
    String entityId,
    String entityType,
    String entityName,
    @JsonInclude(JsonInclude.Include.NON_NULL) Integer appliedCount,
    @JsonInclude(JsonInclude.Include.NON_NULL) Integer skippedCount
) {
    /**
     * Back-compatible constructor for single-entity operations (no fan-out counts).
     */
    public BulkOperationResult(int index, String tool, String action,
            String entityId, String entityType, String entityName) {
        this(index, tool, action, entityId, entityType, entityName, null, null);
    }
}
