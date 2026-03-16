package net.vheerden.archi.mcp.ui;

import static org.junit.Assert.*;

import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.junit.Before;
import org.junit.Test;

import net.vheerden.archi.mcp.McpPlugin;

/**
 * Unit tests for {@link McpPreferenceInitializer}.
 *
 * <p>Tests verify that the initializer correctly sets default values
 * in the DefaultScope preference node.</p>
 */
public class McpPreferenceInitializerTest {

    private McpPreferenceInitializer initializer;
    private IEclipsePreferences defaults;

    @Before
    public void setUp() {
        initializer = new McpPreferenceInitializer();
        defaults = DefaultScope.INSTANCE.getNode(McpPlugin.PLUGIN_ID);
    }

    @Test
    public void shouldSetDefaultPort_whenInitialized() {
        // Execute
        initializer.initializeDefaultPreferences();

        // Verify - use a sentinel value different from expected default to ensure it was set
        int port = defaults.getInt(McpPlugin.PREF_PORT, -1);
        assertEquals("Default port should be set to 18090", McpPlugin.DEFAULT_PORT, port);
    }

    @Test
    public void shouldSetDefaultBindAddress_whenInitialized() {
        // Execute
        initializer.initializeDefaultPreferences();

        // Verify
        String bindAddress = defaults.get(McpPlugin.PREF_BIND_ADDRESS, "NOT_SET");
        assertEquals("Default bind address should be 127.0.0.1", McpPlugin.DEFAULT_BIND_ADDRESS, bindAddress);
    }

    @Test
    public void shouldSetDefaultAutoStartFalse_whenInitialized() {
        // Execute
        initializer.initializeDefaultPreferences();

        // Verify - use true as sentinel since default should be false
        boolean autoStart = defaults.getBoolean(McpPlugin.PREF_AUTO_START, true);
        assertEquals("Default auto-start should be false", McpPlugin.DEFAULT_AUTO_START, autoStart);
    }

    @Test
    public void shouldSetDefaultLogLevelInfo_whenInitialized() {
        // Execute
        initializer.initializeDefaultPreferences();

        // Verify
        String logLevel = defaults.get(McpPlugin.PREF_LOG_LEVEL, "NOT_SET");
        assertEquals("Default log level should be INFO", McpPlugin.DEFAULT_LOG_LEVEL, logLevel);
    }

    @Test
    public void shouldSetDefaultTlsDisabled_whenInitialized() {
        initializer.initializeDefaultPreferences();

        boolean tlsEnabled = defaults.getBoolean(McpPlugin.PREF_TLS_ENABLED, true);
        assertEquals("Default TLS should be disabled", McpPlugin.DEFAULT_TLS_ENABLED, tlsEnabled);
    }

    @Test
    public void shouldSetDefaultKeystorePathEmpty_whenInitialized() {
        initializer.initializeDefaultPreferences();

        String keystorePath = defaults.get(McpPlugin.PREF_KEYSTORE_PATH, "NOT_SET");
        assertEquals("Default keystore path should be empty", McpPlugin.DEFAULT_KEYSTORE_PATH, keystorePath);
    }

    @Test
    public void shouldSetDefaultKeystorePasswordEmpty_whenInitialized() {
        initializer.initializeDefaultPreferences();

        String password = defaults.get(McpPlugin.PREF_KEYSTORE_PASSWORD, "NOT_SET");
        assertEquals("Default keystore password should be empty", McpPlugin.DEFAULT_KEYSTORE_PASSWORD, password);
    }

    @Test
    public void shouldUseCorrectPreferenceKeys() {
        // Verify preference keys match the documented convention
        assertEquals("mcp.server.port", McpPlugin.PREF_PORT);
        assertEquals("mcp.server.bindAddress", McpPlugin.PREF_BIND_ADDRESS);
        assertEquals("mcp.server.autoStart", McpPlugin.PREF_AUTO_START);
        assertEquals("mcp.server.logLevel", McpPlugin.PREF_LOG_LEVEL);
    }

    @Test
    public void shouldUseCorrectDefaultValues() {
        // Verify default values match AC requirements
        assertEquals("Port default per AC#1", 18090, McpPlugin.DEFAULT_PORT);
        assertEquals("Bind address default per AC#1", "127.0.0.1", McpPlugin.DEFAULT_BIND_ADDRESS);
        assertEquals("Auto-start default per AC#1", false, McpPlugin.DEFAULT_AUTO_START);
        assertEquals("Log level default per AC#1", "INFO", McpPlugin.DEFAULT_LOG_LEVEL);
    }
}
