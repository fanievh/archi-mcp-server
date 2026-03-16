package net.vheerden.archi.mcp.ui;

import java.util.Map;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.menus.UIElement;

import net.vheerden.archi.mcp.server.McpServerManager;
import net.vheerden.archi.mcp.server.McpServerStateListener;
import net.vheerden.archi.mcp.server.ServerState;

/**
 * Eclipse command handler for Start/Stop MCP Server toggle.
 *
 * <p>Implements {@link IElementUpdater} to dynamically update the menu label
 * between "Start MCP Server" and "Stop MCP Server — {modelName} — {url}".</p>
 */
public class ToggleServerHandler extends AbstractHandler implements IElementUpdater, McpServerStateListener {

    private static final String COMMAND_ID = "net.vheerden.archi.mcp.toggleServer";

    public ToggleServerHandler() {
        McpServerManager.getInstance().addStateListener(this);
    }

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        McpServerManager manager = McpServerManager.getInstance();

        if (manager.isRunning()) {
            manager.stop();
        } else {
            manager.start();

            // Check for error after start attempt — execute() runs on UI thread,
            // so show dialog directly (no asyncExec needed)
            if (manager.getState() == ServerState.ERROR) {
                String errorMessage = manager.getLastErrorMessage();
                MessageDialog.openError(
                    HandlerUtil.getActiveShell(event),
                    "MCP Server Error",
                    errorMessage != null ? errorMessage : "Failed to start MCP Server.");
            }
        }

        return null;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void updateElement(UIElement element, Map parameters) {
        McpServerManager manager = McpServerManager.getInstance();

        if (manager.isRunning()) {
            String url = manager.buildServerUrl();
            if (url == null) {
                return; // Server stopped between checks; next state callback will fix label
            }
            String modelName = manager.getCurrentModelName().orElse("No model selected");
            // Escape '&' as '&&' to prevent SWT mnemonic interpretation in menu labels
            String safeModelName = modelName.replace("&", "&&");
            element.setText("Stop MCP Server \u2014 " + safeModelName + " \u2014 " + url);
            element.setTooltip("MCP Server running: " + modelName + "\nURL: " + url + "\n(Copy the URL to configure your LLM client)");
        } else {
            element.setText("Start MCP Server");
            element.setTooltip("Start the MCP Server");
        }
    }

    @Override
    public void onStateChanged(ServerState oldState, ServerState newState) {
        // Refresh the menu element to update its label
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed()) {
            display.asyncExec(() -> {
                try {
                    org.eclipse.ui.PlatformUI.getWorkbench()
                        .getService(org.eclipse.ui.commands.ICommandService.class)
                        .refreshElements(COMMAND_ID, null);
                } catch (Exception e) {
                    // Workbench may not be available yet during startup
                }
            });
        }
    }

    @Override
    public void dispose() {
        McpServerManager.getInstance().removeStateListener(this);
        super.dispose();
    }
}
