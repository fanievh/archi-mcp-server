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
 * since this initializer may run before {@link McpPlugin#start}.</p>
 *
 * <p>This is the <em>only</em> code that sets this plugin's defaults. Equinox runs it when the
 * default scope is first loaded, then applies the product's {@code plugin_customization.ini} and any
 * {@code -pluginCustomization} file on top, which is how an administrator presets these settings.
 * A second writer that runs later, for example from {@code McpPlugin.start()}, overwrites the
 * administrator's values with ours. {@code PreferenceDefaultsContractTest} refuses one.</p>
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
        defaults.putBoolean(McpPlugin.PREF_AUTH_TOKEN_ENABLED, McpPlugin.DEFAULT_AUTH_TOKEN_ENABLED);
        defaults.putBoolean(McpPlugin.PREF_APPROVAL_MODE, McpPlugin.DEFAULT_APPROVAL_MODE);
        defaults.putBoolean(McpPlugin.PREF_READ_ONLY, McpPlugin.DEFAULT_READ_ONLY);
        defaults.putBoolean(McpPlugin.PREF_ALLOW_SERVER_IMAGE_SOURCES, McpPlugin.DEFAULT_ALLOW_SERVER_IMAGE_SOURCES);
    }
}
