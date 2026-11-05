package net.vheerden.archi.mcp.registry;

import java.util.Map;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Brackets every tool call, on the thread that runs its handler: opened before the handler runs and
 * closed after it returns or throws.
 *
 * <p>Lets per-call state (such as which model the call acts on) reach every handler without a
 * parameter on each one, and lets the response report what that state resolved to.</p>
 */
public interface ToolCallScope {

    /**
     * Opens the call.
     *
     * @return a result to answer the call with instead of running its handler, or null to run it
     */
    McpSchema.CallToolResult enter(McpSyncServerExchange exchange, McpSchema.CallToolRequest request);

    /**
     * Fields to add to the response's {@code _meta}, read after the handler returns.
     *
     * @return the fields, or null for none
     */
    Map<String, Object> responseMeta();

    /** Closes the call. */
    void exit();
}
