package net.vheerden.archi.mcp.response.dto;

import java.util.Map;
import java.util.Set;

/**
 * Represents a single operation within a bulk-mutate request (Story 7-5).
 *
 * <p>Each operation specifies a mutation tool to invoke and the parameters
 * for that tool. Supported tools are limited to deterministic mutation
 * operations — discovery tools are excluded.</p>
 *
 * @param tool   the mutation tool name (e.g., "create-element")
 * @param params the tool parameters
 */
public record BulkOperation(String tool, Map<String, Object> params) {

    /** Tools supported in bulk-mutate operations. */
    public static final Set<String> SUPPORTED_TOOLS = Set.of(
            "create-element",
            "create-relationship",
            "create-view",
            "update-element",
            "update-relationship",
            "update-view",
            "add-to-view",
            "add-connection-to-view",
            "add-group-to-view",
            "add-note-to-view",
            "remove-from-view",
            "update-view-object",
            "update-view-connection",
            "clear-view",
            "delete-element",
            "delete-relationship",
            "delete-view",
            "delete-folder",
            "create-folder",
            "update-folder",
            "move-to-folder");

    /** Maximum number of operations allowed per bulk-mutate call. */
    public static final int MAX_OPERATIONS = 150;

    /**
     * Validates this operation's tool and params are non-null and the tool
     * is one of the supported mutation tools.
     *
     * @throws IllegalArgumentException if validation fails
     */
    public void validate() {
        if (tool == null || tool.isBlank()) {
            throw new IllegalArgumentException("Operation tool must not be null or blank");
        }
        if (!SUPPORTED_TOOLS.contains(tool)) {
            throw new IllegalArgumentException(
                    "Unsupported tool '" + tool + "'. Supported: " + SUPPORTED_TOOLS);
        }
        if (params == null) {
            throw new IllegalArgumentException("Operation params must not be null");
        }
    }
}
