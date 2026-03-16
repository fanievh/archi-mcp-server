package net.vheerden.archi.mcp.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.vheerden.archi.mcp.server.McpServerManager;
import net.vheerden.archi.mcp.server.McpServerStateListener;
import net.vheerden.archi.mcp.server.ServerState;

/**
 * Displays MCP Server status information.
 *
 * <p>Registers as a {@link McpServerStateListener} and logs state transitions.
 * The primary UI status is provided by {@link ToggleServerHandler}'s dynamic
 * menu label which shows "Start MCP Server" / "Stop MCP Server (port N)".</p>
 *
 * <p>For MVP, status is conveyed through:</p>
 * <ul>
 *   <li>Toggle menu label (running/stopped + port)</li>
 *   <li>Error dialogs on startup failure</li>
 *   <li>SLF4J log entries at INFO level</li>
 * </ul>
 */
public class McpStatusIndicator implements McpServerStateListener {

    private static final Logger logger = LoggerFactory.getLogger(McpStatusIndicator.class);

    private static McpStatusIndicator instance;

    private McpStatusIndicator() {
        McpServerManager.getInstance().addStateListener(this);
    }

    /**
     * Initializes the status indicator singleton and registers with the server manager.
     */
    public static synchronized void initialize() {
        if (instance == null) {
            instance = new McpStatusIndicator();
        }
    }

    /**
     * Disposes the status indicator and unregisters from the server manager.
     */
    public static synchronized void dispose() {
        if (instance != null) {
            McpServerManager.getInstance().removeStateListener(instance);
            instance = null;
        }
    }

    @Override
    public void onStateChanged(ServerState oldState, ServerState newState) {
        McpServerManager manager = McpServerManager.getInstance();
        String statusText = buildStatusText(newState, manager);
        logger.info("MCP Server status: {}", statusText);
    }

    /**
     * Builds a human-readable status string for the current server state.
     *
     * @param state   the current server state
     * @param manager the server manager
     * @return the status text
     */
    static String buildStatusText(ServerState state, McpServerManager manager) {
        switch (state) {
            case RUNNING:
                return "Running on port " + manager.getPort() + " (0 clients)";
            case STARTING:
                return "Starting...";
            case STOPPING:
                return "Stopping...";
            case ERROR:
                String error = manager.getLastErrorMessage();
                return "Error" + (error != null ? " - " + error : "");
            case STOPPED:
            default:
                return "Stopped";
        }
    }
}
