package net.vheerden.archi.mcp;

import static org.junit.Assert.*;

import org.eclipse.jface.preference.PreferenceStore;
import org.junit.Test;

/**
 * Unit tests for {@link McpPlugin}.
 *
 * <p>Tests plugin activation, preference defaults, and singleton behavior, and how each setting is resolved
 * when the stored value is missing or unusable (on an in-memory store, so no running plugin is needed).</p>
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

    // ---- resolving a stored value ---------------------------------------------------------------

    @Test
    public void shouldUseTheDefaultPort_whenThePortIsMissingOrUnusable() {
        assertEquals(McpPlugin.DEFAULT_PORT, McpPlugin.portPreference(new PreferenceStore()));
        for (String bad : new String[] {"abc", "0", "-1", "65536", "", " ", "18090.5"}) {
            assertEquals("port '" + bad + "'", McpPlugin.DEFAULT_PORT,
                    McpPlugin.portPreference(valued(McpPlugin.PREF_PORT, bad)));
        }
    }

    @Test
    public void shouldUseTheStoredPort_whenItIsInRange() {
        assertEquals(1, McpPlugin.portPreference(valued(McpPlugin.PREF_PORT, "1")));
        assertEquals(18091, McpPlugin.portPreference(valued(McpPlugin.PREF_PORT, "18091")));
        assertEquals(65535, McpPlugin.portPreference(valued(McpPlugin.PREF_PORT, "65535")));
        assertEquals("a value can carry trailing spaces", 18091,
                McpPlugin.portPreference(valued(McpPlugin.PREF_PORT, "18091 ")));
    }

    @Test
    public void shouldUseTheFallback_whenABooleanIsMissingOrUnusable() {
        assertTrue(McpPlugin.booleanPreference(new PreferenceStore(), "k", true));
        assertFalse(McpPlugin.booleanPreference(new PreferenceStore(), "k", false));
        for (String bad : new String[] {"yes", "1", "", "on"}) {
            assertTrue("boolean '" + bad + "'", McpPlugin.booleanPreference(valued("k", bad), "k", true));
            assertFalse("boolean '" + bad + "'", McpPlugin.booleanPreference(valued("k", bad), "k", false));
        }
    }

    @Test
    public void shouldUseTheStoredBoolean_whenItIsTrueOrFalseInAnyCase() {
        assertTrue(McpPlugin.booleanPreference(valued("k", "TRUE"), "k", false));
        assertTrue(McpPlugin.booleanPreference(valued("k", "true "), "k", false));
        assertFalse(McpPlugin.booleanPreference(valued("k", "False"), "k", true));
        assertFalse(McpPlugin.booleanPreference(valued("k", "false"), "k", true));
    }

    @Test
    public void shouldUseTheDefaultBindAddress_whenItIsMissingOrBlank() {
        assertEquals(McpPlugin.DEFAULT_BIND_ADDRESS, McpPlugin.bindAddressPreference(new PreferenceStore()));
        assertEquals(McpPlugin.DEFAULT_BIND_ADDRESS,
                McpPlugin.bindAddressPreference(valued(McpPlugin.PREF_BIND_ADDRESS, "   ")));
    }

    @Test
    public void shouldPassABindAddressThroughUnchecked_whenItIsNotBlank() {
        // An unresolvable host is refused later, loudly, when the server binds it
        for (String host : new String[] {"::1", "0.0.0.0", "localhost", "not a host"}) {
            assertEquals(host, McpPlugin.bindAddressPreference(valued(McpPlugin.PREF_BIND_ADDRESS, host)));
        }
    }

    @Test
    public void shouldKeepApprovalModeGated_whenNoValueIsStoredInEitherScope() {
        assertTrue(McpPlugin.approvalModePreference(new PreferenceStore()));
        assertTrue(McpPlugin.approvalModePreference(valued(McpPlugin.PREF_APPROVAL_MODE, "nope")));
    }

    @Test
    public void shouldKeepApprovalModeGated_whenAnUnusableValueHidesAPresetThatSwitchesItOff() {
        PreferenceStore store = valued(McpPlugin.PREF_APPROVAL_MODE, "nope");
        store.setDefault(McpPlugin.PREF_APPROVAL_MODE, false);
        assertTrue(McpPlugin.approvalModePreference(store));
        // The String setter: JFace's boolean setValue is a no-op here, as it reads "nope" as false already
        store.setValue(McpPlugin.PREF_APPROVAL_MODE, "false");
        assertFalse("a readable choice still switches it off", McpPlugin.approvalModePreference(store));
    }

    @Test
    public void shouldUseThePresetDefault_whenTheUsersValueIsUnusable() {
        PreferenceStore port = valued(McpPlugin.PREF_PORT, "abc");
        port.setDefault(McpPlugin.PREF_PORT, 19000);
        assertEquals(19000, McpPlugin.portPreference(port));

        PreferenceStore flag = valued(McpPlugin.PREF_AUTO_START, "yes");
        flag.setDefault(McpPlugin.PREF_AUTO_START, true);
        assertTrue(McpPlugin.booleanPreference(flag, McpPlugin.PREF_AUTO_START, false));

        PreferenceStore bind = valued(McpPlugin.PREF_BIND_ADDRESS, "   ");
        bind.setDefault(McpPlugin.PREF_BIND_ADDRESS, "0.0.0.0");
        assertEquals("0.0.0.0", McpPlugin.bindAddressPreference(bind));
    }

    @Test
    public void shouldUseTheBuiltInDefault_whenThePresetDefaultIsUnusableToo() {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(McpPlugin.PREF_PORT, "abc");
        assertEquals("a preset that is itself unusable", McpPlugin.DEFAULT_PORT, McpPlugin.portPreference(store));
        store.setValue(McpPlugin.PREF_PORT, "0");
        assertEquals("an unusable value over an unusable preset", McpPlugin.DEFAULT_PORT,
                McpPlugin.portPreference(store));
    }

    @Test
    public void shouldTrimTheBindAddress_whenItCarriesSurroundingSpaces() {
        assertEquals("127.0.0.1",
                McpPlugin.bindAddressPreference(valued(McpPlugin.PREF_BIND_ADDRESS, " 127.0.0.1 ")));
    }

    @Test
    public void shouldReadEachDefault_whenTheInitializerDefaultsAreInPlace() {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(McpPlugin.PREF_PORT, McpPlugin.DEFAULT_PORT);
        store.setDefault(McpPlugin.PREF_BIND_ADDRESS, McpPlugin.DEFAULT_BIND_ADDRESS);
        store.setDefault(McpPlugin.PREF_AUTO_START, McpPlugin.DEFAULT_AUTO_START);
        store.setDefault(McpPlugin.PREF_TLS_ENABLED, McpPlugin.DEFAULT_TLS_ENABLED);
        store.setDefault(McpPlugin.PREF_AUTH_TOKEN_ENABLED, McpPlugin.DEFAULT_AUTH_TOKEN_ENABLED);
        store.setDefault(McpPlugin.PREF_APPROVAL_MODE, McpPlugin.DEFAULT_APPROVAL_MODE);
        assertEquals(18090, McpPlugin.portPreference(store));
        assertEquals("127.0.0.1", McpPlugin.bindAddressPreference(store));
        assertFalse(McpPlugin.booleanPreference(store, McpPlugin.PREF_AUTO_START, true));
        assertFalse(McpPlugin.booleanPreference(store, McpPlugin.PREF_TLS_ENABLED, true));
        assertFalse(McpPlugin.booleanPreference(store, McpPlugin.PREF_AUTH_TOKEN_ENABLED, true));
        assertTrue(McpPlugin.approvalModePreference(store));
    }

    @Test
    public void shouldPreferTheUsersValue_whenADefaultIsAlsoSet() {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(McpPlugin.PREF_PORT, McpPlugin.DEFAULT_PORT);
        store.setValue(McpPlugin.PREF_PORT, 18091);
        assertEquals(18091, McpPlugin.portPreference(store));
        store.setDefault(McpPlugin.PREF_APPROVAL_MODE, true);
        store.setValue(McpPlugin.PREF_APPROVAL_MODE, false);
        assertFalse(McpPlugin.approvalModePreference(store));
    }

    @Test
    public void shouldServeEveryTool_whenReadOnlyIsLeftAtItsShippedDefault() {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(McpPlugin.PREF_READ_ONLY, McpPlugin.DEFAULT_READ_ONLY);
        assertFalse(McpPlugin.DEFAULT_READ_ONLY);
        assertFalse(McpPlugin.readOnlyPreference(store));
    }

    @Test
    public void shouldFollowAnAdministratorsPreset_whenNoUserValueIsStored() {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(McpPlugin.PREF_READ_ONLY, true);
        assertTrue(McpPlugin.readOnlyPreference(store));
    }

    @Test
    public void shouldReadAReadableReadOnlyValue_inAnyCase() {
        assertTrue(McpPlugin.readOnlyPreference(valued(McpPlugin.PREF_READ_ONLY, "TRUE")));
        assertTrue(McpPlugin.readOnlyPreference(valued(McpPlugin.PREF_READ_ONLY, " true ")));
        assertFalse(McpPlugin.readOnlyPreference(valued(McpPlugin.PREF_READ_ONLY, "False")));
    }

    @Test
    public void shouldServeReadOnly_whenTheStoredValueCannotBeRead() {
        assertTrue(McpPlugin.readOnlyPreference(valued(McpPlugin.PREF_READ_ONLY, "nope")));
        PreferenceStore store = valued(McpPlugin.PREF_READ_ONLY, "nope");
        store.setDefault(McpPlugin.PREF_READ_ONLY, false);
        assertTrue("an unreadable value never falls back to a preset that serves every tool",
                McpPlugin.readOnlyPreference(store));
        assertTrue("nothing stored in either scope", McpPlugin.readOnlyPreference(new PreferenceStore()));
    }

    @Test
    public void shouldRefuseServerImageSources_whenTheSettingIsLeftAtItsShippedDefault() {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(McpPlugin.PREF_ALLOW_SERVER_IMAGE_SOURCES, McpPlugin.DEFAULT_ALLOW_SERVER_IMAGE_SOURCES);
        assertFalse(McpPlugin.DEFAULT_ALLOW_SERVER_IMAGE_SOURCES);
        assertFalse(McpPlugin.allowServerImageSourcesPreference(store));
        assertFalse("nothing stored in either scope",
                McpPlugin.allowServerImageSourcesPreference(new PreferenceStore()));
    }

    @Test
    public void shouldAllowServerImageSources_whenSetOrPreset() {
        assertTrue(McpPlugin.allowServerImageSourcesPreference(
                valued(McpPlugin.PREF_ALLOW_SERVER_IMAGE_SOURCES, " TRUE ")));
        PreferenceStore preset = new PreferenceStore();
        preset.setDefault(McpPlugin.PREF_ALLOW_SERVER_IMAGE_SOURCES, true);
        assertTrue(McpPlugin.allowServerImageSourcesPreference(preset));
        assertFalse(McpPlugin.allowServerImageSourcesPreference(
                valued(McpPlugin.PREF_ALLOW_SERVER_IMAGE_SOURCES, "false")));
    }

    @Test
    public void shouldRefuseServerImageSources_whenTheStoredValueCannotBeRead() {
        assertFalse(McpPlugin.allowServerImageSourcesPreference(
                valued(McpPlugin.PREF_ALLOW_SERVER_IMAGE_SOURCES, "yes")));
        PreferenceStore store = valued(McpPlugin.PREF_ALLOW_SERVER_IMAGE_SOURCES, "yes");
        store.setDefault(McpPlugin.PREF_ALLOW_SERVER_IMAGE_SOURCES, true);
        assertFalse("an unreadable value never falls back to a preset that allows them",
                McpPlugin.allowServerImageSourcesPreference(store));
    }

    private static PreferenceStore valued(String key, String value) {
        PreferenceStore store = new PreferenceStore();
        store.setValue(key, value);
        return store;
    }
}
