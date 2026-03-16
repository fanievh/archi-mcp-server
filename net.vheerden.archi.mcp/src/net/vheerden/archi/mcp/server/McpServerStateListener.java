package net.vheerden.archi.mcp.server;

/**
 * Listener interface for MCP server state changes.
 *
 * <p>Listeners are notified on the thread that triggers the state change.
 * UI listeners MUST wrap updates in {@code Display.getDefault().asyncExec()}.</p>
 */
public interface McpServerStateListener {

    /**
     * Called when the server state changes.
     *
     * @param oldState the previous state
     * @param newState the new state
     */
    void onStateChanged(ServerState oldState, ServerState newState);
}
