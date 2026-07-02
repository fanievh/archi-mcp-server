package net.vheerden.archi.mcp.response.dto;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents a single operation within a bulk-mutate request.
 *
 * <p>Each operation specifies a mutation tool to invoke and the parameters
 * for that tool. Supported tools are limited to deterministic mutation
 * operations — discovery tools are excluded.</p>
 *
 * @param tool   the mutation tool name (e.g., "create-element")
 * @param params the tool parameters
 */
public record BulkOperation(String tool, Map<String, Object> params) {

    /**
     * Canonical, deterministically-ordered list of tools supported in bulk-mutate
     * operations — the single source of truth for both the {@link #SUPPORTED_TOOLS}
     * validation set and the {@code bulk-mutate} tool's advertised supported-tools
     * descriptions (built from this list in {@code MutationHandler.buildBulkMutateSpec}).
     *
     * <p>Ordered by logical grouping (create / update / view-add / view-mutate /
     * delete / folder / specialization) so the served tool descriptions are stable
     * across server restarts — unlike {@link Set#of} iteration order, which the JDK
     * deliberately randomizes per JVM run. Add new bulk-supported tools here and both
     * the validation set and the advertised lists update automatically.</p>
     */
    public static final List<String> SUPPORTED_TOOLS_ORDERED = List.of(
            "create-element",
            "create-relationship",
            "create-view",
            "update-model",
            "update-element",
            "update-relationship",
            "update-view",
            "add-to-view",
            "add-connection-to-view",
            "add-group-to-view",
            "add-note-to-view",
            "add-view-reference-to-view",
            "add-image-to-view",
            "remove-from-view",
            "update-view-object",
            "update-view-connection",
            "set-view-label-expression",
            "clear-view",
            "delete-element",
            "delete-relationship",
            "delete-view",
            "create-folder",
            "update-folder",
            "move-to-folder",
            "delete-folder",
            "create-specialization",
            "update-specialization",
            "delete-specialization");

    /** Tools supported in bulk-mutate operations (derived from {@link #SUPPORTED_TOOLS_ORDERED}). */
    public static final Set<String> SUPPORTED_TOOLS = Set.copyOf(SUPPORTED_TOOLS_ORDERED);

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
            // Render the ordered list (not the Set) so the error lists tools in the same
            // stable, human-sensible order as the advertised tool descriptions.
            throw new IllegalArgumentException(
                    "Unsupported tool '" + tool + "'. Supported: " + SUPPORTED_TOOLS_ORDERED);
        }
        if (params == null) {
            throw new IllegalArgumentException("Operation params must not be null");
        }
    }
}
