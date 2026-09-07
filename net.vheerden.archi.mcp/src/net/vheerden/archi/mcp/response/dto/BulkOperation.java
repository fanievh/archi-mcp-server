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
 * @param as     an optional name for this operation, which a later operation in the same call can
 *               use in place of this one's position — {@code "$coreBanking.id"} rather than
 *               {@code "$4.id"}. Null on an operation that declares none, which is every operation
 *               that predates the form. The two ways of naming an earlier operation differ in what
 *               a typo does: every integer below the current index names some legal earlier
 *               operation, so a mistyped position resolves — silently — to a different container,
 *               while a mistyped name matches no declaration and can only be refused. Scoped to the
 *               call it appears in: it does not address an enclosing batch's queue and does not
 *               survive the call.
 */
public record BulkOperation(String tool, Map<String, Object> params, String as) {

    /**
     * An operation that declares no name, which is the shape every caller wrote before the name
     * existed and the shape most callers still write.
     */
    public BulkOperation(String tool, Map<String, Object> params) {
        this(tool, params, null);
    }

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
     * The keys one operation object may carry, in the order a refusal lists them.
     *
     * <p>The single source for both the served item schema and the refusal an unrecognised key
     * earns, so the two cannot come to disagree about what is accepted. Held here rather than in
     * the handler because {@code as} is a component of this record and the set is what that
     * component is worth: a key read by nobody is a name lost in silence, and the reference to it
     * then fails on a different operation entirely.</p>
     */
    public static final List<String> OPERATION_KEYS = List.of("tool", "params", "as");

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
