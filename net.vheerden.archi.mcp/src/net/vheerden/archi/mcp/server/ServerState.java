package net.vheerden.archi.mcp.server;

/**
 * Server lifecycle states for the MCP server.
 *
 * <p>State machine:</p>
 * <pre>
 * STOPPED → STARTING → RUNNING
 *    ↑ ↑       ↓          ↓
 *    ↑ ↑     ERROR  ←  STOPPING
 *    ↑ ↑       ↓          ↓
 *    ↑ ← ← ← ← ←          ↓       (ERROR → STOPPED: a Stop given up on finishes)
 *    ← ← ← ← ← ← ← ← ← ← ←
 * </pre>
 *
 * <p>{@link #ERROR} is reached when a start fails or a stop is given up on. From there, Start or
 * Stop can be tried again (to {@link #STARTING} or {@link #STOPPING}); and after a Stop that was
 * given up on, the state goes to {@link #STOPPED} by itself once that stop finishes.</p>
 */
public enum ServerState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR
}
