package net.vheerden.archi.mcp.server;

/**
 * Server lifecycle states for the MCP server.
 *
 * <p>State machine:</p>
 * <pre>
 * STOPPED → STARTING → RUNNING
 *    ↑                    ↓
 *    ← ←  STOPPING  ← ← ←
 *    ↑
 *  ERROR  (from STARTING only)
 * </pre>
 */
public enum ServerState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR
}
