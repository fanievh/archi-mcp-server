package net.vheerden.archi.mcp;

import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plugin activator for the ArchiMate MCP Server.
 * Manages plugin lifecycle and provides access to plugin preferences.
 *
 * <p>This plugin uses lazy activation - it only activates when a class
 * from this bundle is first referenced.</p>
 */
public class McpPlugin extends AbstractUIPlugin {

    private static final Logger logger = LoggerFactory.getLogger(McpPlugin.class);

    /** The plugin ID matching Bundle-SymbolicName in MANIFEST.MF */
    public static final String PLUGIN_ID = "net.vheerden.archi.mcp";

    // Preference keys
    public static final String PREF_PORT = "mcp.server.port";
    public static final String PREF_BIND_ADDRESS = "mcp.server.bindAddress";
    public static final String PREF_AUTO_START = "mcp.server.autoStart";
    public static final String PREF_LOG_LEVEL = "mcp.server.logLevel";
    public static final String PREF_TLS_ENABLED = "mcp.server.tlsEnabled";
    public static final String PREF_KEYSTORE_PATH = "mcp.server.keystorePath";
    public static final String PREF_KEYSTORE_PASSWORD = "mcp.server.keystorePassword";

    // Default values
    public static final int DEFAULT_PORT = 18090;
    public static final String DEFAULT_BIND_ADDRESS = "127.0.0.1";
    public static final boolean DEFAULT_AUTO_START = false;
    public static final String DEFAULT_LOG_LEVEL = "INFO";
    public static final boolean DEFAULT_TLS_ENABLED = false;
    public static final String DEFAULT_KEYSTORE_PATH = "";
    public static final String DEFAULT_KEYSTORE_PASSWORD = "";

    /** Singleton instance */
    private static McpPlugin plugin;

    /**
     * Default constructor required by OSGi.
     */
    public McpPlugin() {
        // Required by OSGi
    }

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
        initializeDefaultPreferences();
        logger.info("ArchiMate MCP Server plugin started (version {})",
                    context.getBundle().getVersion());
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        logger.info("ArchiMate MCP Server plugin stopping");

        // Gracefully shut down the MCP server if running
        try {
            var manager = net.vheerden.archi.mcp.server.McpServerManager.getInstance();
            if (manager.isRunning()) {
                logger.info("Stopping MCP Server during plugin shutdown");
                manager.stop();
            }
        } catch (Exception e) {
            logger.error("Error stopping MCP Server during plugin shutdown", e);
        }

        // Dispose status indicator
        try {
            net.vheerden.archi.mcp.ui.McpStatusIndicator.dispose();
        } catch (Exception e) {
            logger.debug("Error disposing status indicator", e);
        }

        plugin = null;
        super.stop(context);
    }

    /**
     * Returns the singleton plugin instance.
     *
     * @return the plugin instance, or null if not yet activated
     */
    public static McpPlugin getDefault() {
        return plugin;
    }

    /**
     * Initializes default preference values.
     * Called once during plugin startup.
     */
    private void initializeDefaultPreferences() {
        var store = getPreferenceStore();
        store.setDefault(PREF_PORT, DEFAULT_PORT);
        store.setDefault(PREF_BIND_ADDRESS, DEFAULT_BIND_ADDRESS);
        store.setDefault(PREF_AUTO_START, DEFAULT_AUTO_START);
        store.setDefault(PREF_LOG_LEVEL, DEFAULT_LOG_LEVEL);
        store.setDefault(PREF_TLS_ENABLED, DEFAULT_TLS_ENABLED);
        store.setDefault(PREF_KEYSTORE_PATH, DEFAULT_KEYSTORE_PATH);
        store.setDefault(PREF_KEYSTORE_PASSWORD, DEFAULT_KEYSTORE_PASSWORD);
    }

    /**
     * Gets the configured MCP server port.
     *
     * @return the port number from preferences, or DEFAULT_PORT if not set
     */
    public int getServerPort() {
        return getPreferenceStore().getInt(PREF_PORT);
    }

    /**
     * Gets the configured network bind address.
     *
     * @return the bind address from preferences, or DEFAULT_BIND_ADDRESS if not set
     */
    public String getBindAddress() {
        return getPreferenceStore().getString(PREF_BIND_ADDRESS);
    }

    /**
     * Checks if auto-start is enabled.
     *
     * @return true if the server should start automatically on plugin activation
     */
    public boolean isAutoStartEnabled() {
        return getPreferenceStore().getBoolean(PREF_AUTO_START);
    }

    /**
     * Gets the configured log level.
     *
     * @return the log level string from preferences
     */
    public String getLogLevel() {
        return getPreferenceStore().getString(PREF_LOG_LEVEL);
    }

    /**
     * Checks if TLS is enabled.
     *
     * @return true if TLS/HTTPS should be used for the server
     */
    public boolean isTlsEnabled() {
        return getPreferenceStore().getBoolean(PREF_TLS_ENABLED);
    }

    /**
     * Gets the configured keystore file path.
     *
     * @return the keystore path from preferences, or empty string if not set
     */
    public String getKeystorePath() {
        return getPreferenceStore().getString(PREF_KEYSTORE_PATH);
    }

    /**
     * Gets the configured keystore password.
     *
     * @return the keystore password from preferences, or empty string if not set
     */
    public String getKeystorePassword() {
        return getPreferenceStore().getString(PREF_KEYSTORE_PASSWORD);
    }
}
