package net.vheerden.archi.mcp.handlers;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Whether each MCP tool only reads the model or can change something, declared for every tool in one
 * table.
 *
 * <p>{@link #READ} means the tool never changes the model, never writes to the server's disk, and is
 * still meaningful when nothing may change. A read-only server registers only these tools. Everything
 * else is {@link #WRITE}. That includes the batch and approval tools: they change nothing themselves,
 * but they exist only to serve a change, so a server that can make none would only mislead with them.
 * {@code export-view} is READ because a read-only server refuses its file output and only returns the
 * picture in the response.</p>
 *
 * <p>The table is applied as a tool decorator at registration ({@link #annotate}), which also sets the
 * tool's {@code readOnlyHint} and {@code destructiveHint} in every mode. A tool with no entry is refused
 * there with an {@link IllegalStateException}, so a new tool cannot be served until someone decides
 * which kind it is. There is deliberately no default.</p>
 */
public enum ToolAccess {

    /** Never changes the model or writes to the server's disk; offered by a read-only server. */
    READ,

    /** Can change the model or the server's disk, or exists only to serve such a change. */
    WRITE;

    /**
     * One tool's classification.
     *
     * @param access      whether the tool only reads
     * @param destructive whether the tool removes or destroys something; always false for a READ tool
     */
    public record Entry(ToolAccess access, boolean destructive) {
    }

    private static final Map<String, Entry> TABLE = buildTable();

    private static Map<String, Entry> buildTable() {
        Map<String, Entry> table = new LinkedHashMap<>();
        read(table, "assess-layout");
        read(table, "detect-hub-elements");
        read(table, "export-view");
        read(table, "find-concept-usage");
        read(table, "get-element");
        read(table, "get-folder-tree");
        read(table, "get-folders");
        read(table, "get-guidance");
        read(table, "get-model-info");
        read(table, "get-models");
        read(table, "get-relationships");
        read(table, "get-session-filters");
        read(table, "get-specialization-usage");
        read(table, "get-view-contents");
        read(table, "get-views");
        read(table, "list-model-images");
        read(table, "list-specializations");
        read(table, "search-elements");
        read(table, "search-relationships");
        // Changes only the calling session's query filter, never the model.
        read(table, "set-session-filter");

        write(table, "add-connection-to-view", false);
        write(table, "add-group-to-view", false);
        // Also reads a file on the server or fetches a URL from it.
        write(table, "add-image-to-model", false);
        write(table, "add-image-to-view", false);
        write(table, "add-note-to-view", false);
        write(table, "add-to-view", false);
        write(table, "add-view-reference-to-view", false);
        write(table, "adjust-view-spacing", false);
        write(table, "apply-element-spacing-recommendations", false);
        write(table, "apply-group-spacing-recommendations", false);
        write(table, "apply-positions", false);
        write(table, "apply-spacing-recommendations", false);
        write(table, "arrange-groups", false);
        write(table, "auto-connect-view", false);
        write(table, "auto-layout-and-route", false);
        write(table, "auto-route-connections", false);
        write(table, "begin-batch", false);
        write(table, "bulk-mutate", false);
        // Not destructive: the concept is replaced by one of the new type that keeps its id and
        // everything that referred to it.
        write(table, "change-element-type", false);
        write(table, "change-relationship-type", false);
        write(table, "clear-view", true);
        write(table, "clone-view", false);
        write(table, "create-element", false);
        write(table, "create-folder", false);
        write(table, "create-relationship", false);
        write(table, "create-specialization", false);
        write(table, "create-view", false);
        write(table, "delete-element", true);
        write(table, "delete-folder", true);
        write(table, "delete-relationship", true);
        write(table, "delete-specialization", true);
        write(table, "delete-view", true);
        write(table, "end-batch", false);
        write(table, "get-batch-status", false);
        write(table, "get-or-create-element", false);
        write(table, "layout-flat-view", false);
        write(table, "layout-within-group", false);
        write(table, "list-pending-approvals", false);
        // A merge deletes the duplicates it folds into the survivor.
        write(table, "merge-elements", true);
        write(table, "move-to-folder", false);
        write(table, "optimize-group-order", false);
        write(table, "redo", false);
        write(table, "remove-from-view", true);
        write(table, "resize-elements-to-fit", false);
        write(table, "search-and-create", false);
        write(table, "undo", false);
        write(table, "update-element", false);
        write(table, "update-folder", false);
        write(table, "update-model", false);
        write(table, "update-relationship", false);
        write(table, "update-specialization", false);
        write(table, "update-view", false);
        write(table, "update-view-connection", false);
        write(table, "update-view-object", false);
        return Collections.unmodifiableMap(table);
    }

    private static void read(Map<String, Entry> table, String tool) {
        put(table, tool, new Entry(READ, false));
    }

    private static void write(Map<String, Entry> table, String tool, boolean destructive) {
        put(table, tool, new Entry(WRITE, destructive));
    }

    private static void put(Map<String, Entry> table, String tool, Entry entry) {
        if (table.put(tool, entry) != null) {
            throw new IllegalStateException("Tool " + tool + " is classified twice");
        }
    }

    /**
     * Every classified tool, in table order.
     *
     * @return an unmodifiable map from tool name to its classification
     */
    public static Map<String, Entry> table() {
        return TABLE;
    }

    /**
     * The classification of one tool.
     *
     * @param tool the tool name
     * @return its entry
     * @throws IllegalStateException if the tool has no entry
     */
    public static Entry of(String tool) {
        Entry entry = tool == null ? null : TABLE.get(tool);
        if (entry == null) {
            throw new IllegalStateException("MCP tool " + tool + " has no access classification. "
                    + "Declare it READ or WRITE in " + ToolAccess.class.getSimpleName()
                    + " before registering it.");
        }
        return entry;
    }

    /**
     * Whether a read-only server offers this tool.
     *
     * @param tool the tool definition
     * @return true if the tool is classified READ
     * @throws IllegalStateException if the tool has no entry
     */
    public static boolean isRead(McpSchema.Tool tool) {
        return of(tool.name()).access() == READ;
    }

    /**
     * Sets the tool's {@code readOnlyHint} from its classification and, for a WRITE tool, its
     * {@code destructiveHint}. A READ tool's {@code destructiveHint} is left null, since the hint has
     * meaning only when {@code readOnlyHint} is false. Any other annotation the tool already carries is
     * kept.
     *
     * @param tool the tool definition
     * @return a copy of the tool carrying the hints
     * @throws IllegalStateException if the tool has no entry
     */
    public static McpSchema.Tool annotate(McpSchema.Tool tool) {
        Entry entry = of(tool.name());
        boolean read = entry.access() == READ;
        McpSchema.ToolAnnotations existing = tool.annotations();
        McpSchema.ToolAnnotations annotations = new McpSchema.ToolAnnotations(
                existing == null ? null : existing.title(),
                read,
                read ? null : entry.destructive(),
                existing == null ? null : existing.idempotentHint(),
                existing == null ? null : existing.openWorldHint(),
                existing == null ? null : existing.returnDirect());
        return new McpSchema.Tool(tool.name(), tool.title(), tool.description(), tool.inputSchema(),
                tool.outputSchema(), annotations, tool.meta());
    }
}
