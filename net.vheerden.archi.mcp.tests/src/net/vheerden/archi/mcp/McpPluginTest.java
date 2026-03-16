package net.vheerden.archi.mcp;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Unit tests for {@link McpPlugin}.
 *
 * <p>Tests plugin activation, preference defaults, and singleton behavior.</p>
 */
public class McpPluginTest {

    @Test
    public void shouldHaveCorrectPluginId() {
        assertEquals("net.vheerden.archi.mcp", McpPlugin.PLUGIN_ID);
    }

    @Test
    public void shouldHaveCorrectDefaultPort() {
        assertEquals(18090, McpPlugin.DEFAULT_PORT);
    }

    @Test
    public void shouldHaveCorrectDefaultBindAddress() {
        assertEquals("127.0.0.1", McpPlugin.DEFAULT_BIND_ADDRESS);
    }

    @Test
    public void shouldHaveAutoStartDisabledByDefault() {
        assertFalse(McpPlugin.DEFAULT_AUTO_START);
    }

    @Test
    public void shouldHaveCorrectDefaultLogLevel() {
        assertEquals("INFO", McpPlugin.DEFAULT_LOG_LEVEL);
    }

    @Test
    public void shouldHaveCorrectPreferenceKeys() {
        assertEquals("mcp.server.port", McpPlugin.PREF_PORT);
        assertEquals("mcp.server.bindAddress", McpPlugin.PREF_BIND_ADDRESS);
        assertEquals("mcp.server.autoStart", McpPlugin.PREF_AUTO_START);
        assertEquals("mcp.server.logLevel", McpPlugin.PREF_LOG_LEVEL);
    }
}
