package net.vheerden.archi.mcp.ui;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;

import net.vheerden.archi.mcp.McpPlugin;

/**
 * Initializes default preference values for the MCP Server plugin.
 * Registered via the {@code org.eclipse.core.runtime.preferences} extension point.
 *
 * <p>Uses {@link DefaultScope} to set defaults without requiring plugin activation,
 * since this initializer may run before {@link McpPlugin#start}.
 * {@code McpPlugin.start()} also sets defaults as a safety fallback;
 * both initializers are harmless when run together.</p>
 */
public class McpPreferenceInitializer extends AbstractPreferenceInitializer {

    @Override
    public void initializeDefaultPreferences() {
        IEclipsePreferences defaults = DefaultScope.INSTANCE.getNode(McpPlugin.PLUGIN_ID);
        defaults.putInt(McpPlugin.PREF_PORT, McpPlugin.DEFAULT_PORT);
        defaults.put(McpPlugin.PREF_BIND_ADDRESS, McpPlugin.DEFAULT_BIND_ADDRESS);
        defaults.putBoolean(McpPlugin.PREF_AUTO_START, McpPlugin.DEFAULT_AUTO_START);
        defaults.put(McpPlugin.PREF_LOG_LEVEL, McpPlugin.DEFAULT_LOG_LEVEL);
        defaults.putBoolean(McpPlugin.PREF_TLS_ENABLED, McpPlugin.DEFAULT_TLS_ENABLED);
        defaults.put(McpPlugin.PREF_KEYSTORE_PATH, McpPlugin.DEFAULT_KEYSTORE_PATH);
        defaults.put(McpPlugin.PREF_KEYSTORE_PASSWORD, McpPlugin.DEFAULT_KEYSTORE_PASSWORD);
    }
}
