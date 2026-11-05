package net.vheerden.archi.mcp.ui;

import static org.junit.Assert.*;

import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.After;
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
    private IEclipsePreferences instance;
    private String savedInstanceAutoStart;
    private String savedInstancePort;
    private String savedDefaultAutoStart;
    private String savedDefaultPort;

    @Before
    public void setUp() {
        // DefaultScope/ConfigurationPreferences only initialise under the Eclipse
        // PDE JUnit Plug-in Test runtime (OSGi). Run the net.vheerden.archi.mcp.tests
        // fragment as a JUnit Plug-in Test.
        org.junit.Assume.assumeTrue(
                "Requires OSGi runtime (run as JUnit Plug-in Test)",
                isEclipseRuntimeRunning());
        initializer = new McpPreferenceInitializer();
        defaults = DefaultScope.INSTANCE.getNode(McpPlugin.PLUGIN_ID);
        instance = InstanceScope.INSTANCE.getNode(McpPlugin.PLUGIN_ID);
        savedInstanceAutoStart = instance.get(McpPlugin.PREF_AUTO_START, null);
        savedInstancePort = instance.get(McpPlugin.PREF_PORT, null);
        savedDefaultAutoStart = defaults.get(McpPlugin.PREF_AUTO_START, null);
        savedDefaultPort = defaults.get(McpPlugin.PREF_PORT, null);
    }

    @After
    public void tearDown() {
        if (defaults == null || instance == null) {
            return; // skipped outside OSGi
        }
        // Put back what was there, not our constants: the test runtime may itself carry a customization
        restore(defaults, McpPlugin.PREF_AUTO_START, savedDefaultAutoStart);
        restore(defaults, McpPlugin.PREF_PORT, savedDefaultPort);
        restore(instance, McpPlugin.PREF_AUTO_START, savedInstanceAutoStart);
        restore(instance, McpPlugin.PREF_PORT, savedInstancePort);
    }

    private static void restore(IEclipsePreferences node, String key, String saved) {
        if (saved == null) {
            node.remove(key);
        } else {
            node.put(key, saved);
        }
    }

    /** True only under the Eclipse PDE JUnit Plug-in Test runtime (OSGi up). */
    private static boolean isEclipseRuntimeRunning() {
        try {
            return org.eclipse.core.runtime.Platform.isRunning();
        } catch (Throwable t) {
            return false;
        }
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
    public void shouldSetDefaultReadOnlyOff_whenInitialized() {
        initializer.initializeDefaultPreferences();

        assertEquals("Default read-only should be off", McpPlugin.DEFAULT_READ_ONLY,
                defaults.getBoolean(McpPlugin.PREF_READ_ONLY, true));
    }

    @Test
    public void shouldSetDefaultServerImageSourcesRefused_whenInitialized() {
        initializer.initializeDefaultPreferences();

        assertEquals("Default server image sources should be refused", false,
                defaults.getBoolean(McpPlugin.PREF_ALLOW_SERVER_IMAGE_SOURCES, true));
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

    /**
     * An administrator's default (set here the way Equinox applies a {@code -pluginCustomization}
     * file, on top of the initializer) is what the plugin reads, and a user's own setting still wins.
     *
     * <p>This pins only that the plugin's getters read through to the default scope. It cannot catch
     * the plugin overwriting the default when it starts, because the plugin started before this test
     * ran: it passes with or without that overwrite. {@code PreferenceDefaultsContractTest} refuses the
     * overwrite.</p>
     */
    @Test
    public void shouldReadACustomizedDefault_andLetTheUsersOwnSettingWin_whenTheAdministratorPresetsOne() {
        instance.remove(McpPlugin.PREF_AUTO_START);
        instance.remove(McpPlugin.PREF_PORT);
        defaults.putBoolean(McpPlugin.PREF_AUTO_START, true);
        defaults.putInt(McpPlugin.PREF_PORT, 18091);

        McpPlugin plugin = McpPlugin.getDefault();
        assertNotNull("the plugin must be active under the plug-in test runtime", plugin);
        assertTrue("a customized auto-start default must be read", plugin.isAutoStartEnabled());
        assertEquals("a customized port default must be read", 18091, plugin.getServerPort());

        instance.putBoolean(McpPlugin.PREF_AUTO_START, false);
        instance.putInt(McpPlugin.PREF_PORT, 18092);
        assertFalse("the user's own auto-start setting wins over the default", plugin.isAutoStartEnabled());
        assertEquals("the user's own port wins over the default", 18092, plugin.getServerPort());
    }
}
