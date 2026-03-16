package net.vheerden.archi.mcp.ui;

import org.eclipse.ui.IStartup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.vheerden.archi.mcp.McpPlugin;
import net.vheerden.archi.mcp.server.McpServerManager;
import net.vheerden.archi.mcp.server.ServerState;

/**
 * Handles auto-start of the MCP Server on ArchimateTool launch.
 *
 * <p>Registered via {@code org.eclipse.ui.startup} extension point.
 * The {@code earlyStartup()} method is called after the workbench is initialized,
 * NOT during plugin activation.</p>
 */
public class McpStartupHandler implements IStartup {

    private static final Logger logger = LoggerFactory.getLogger(McpStartupHandler.class);

    @Override
    public void earlyStartup() {
        // Initialize the status indicator
        McpStatusIndicator.initialize();

        // Check auto-start preference
        McpPlugin plugin = McpPlugin.getDefault();
        if (plugin == null) {
            logger.debug("McpPlugin not yet activated — skipping auto-start check");
            return;
        }

        if (!plugin.isAutoStartEnabled()) {
            logger.debug("Auto-start is disabled — MCP Server will not start automatically");
            return;
        }

        logger.info("Auto-start is enabled — starting MCP Server");

        McpServerManager manager = McpServerManager.getInstance();
        manager.start();

        if (manager.getState() == ServerState.ERROR) {
            logger.error("Auto-start failed: {}", manager.getLastErrorMessage());
        } else {
            logger.info("Auto-start completed — MCP Server running on port {}", manager.getPort());
        }
    }
}
