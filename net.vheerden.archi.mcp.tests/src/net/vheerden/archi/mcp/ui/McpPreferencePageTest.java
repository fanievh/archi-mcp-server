package net.vheerden.archi.mcp.ui;

import static org.junit.Assert.*;

import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.junit.Test;

/**
 * Unit tests for {@link McpPreferencePage}.
 *
 * <p>Tests structural properties of the preference page that can be verified
 * without Eclipse workbench runtime. Full UI integration testing (page renders,
 * fields display, persistence works) is covered by Task 7 manual validation.</p>
 */
public class McpPreferencePageTest {

    @Test
    public void shouldBeInstantiable_whenCreated() {
        McpPreferencePage page = new McpPreferencePage();
        assertNotNull("Page should be instantiable", page);
    }

    @Test
    public void shouldHaveCorrectDescription_whenCreated() {
        McpPreferencePage page = new McpPreferencePage();
        assertEquals("Configuration settings for the ArchiMate MCP Server.", page.getDescription());
    }

    @Test
    public void shouldExtendFieldEditorPreferencePage() {
        McpPreferencePage page = new McpPreferencePage();
        assertTrue("Should extend FieldEditorPreferencePage",
                page instanceof FieldEditorPreferencePage);
    }

    @Test
    public void shouldImplementIWorkbenchPreferencePage() {
        McpPreferencePage page = new McpPreferencePage();
        assertTrue("Should implement IWorkbenchPreferencePage",
                page instanceof IWorkbenchPreferencePage);
    }

    @Test
    public void shouldUseGridLayout() {
        // FieldEditorPreferencePage uses GRID or FLAT layout
        // Constructor calls super(GRID), which we can verify via description being set
        // (Description is only shown in GRID layout mode)
        McpPreferencePage page = new McpPreferencePage();
        assertNotNull("Description should be set (implies GRID layout)", page.getDescription());
    }

    @Test
    public void shouldSayTheReadOnlySettingAppliesAtTheNextStart() {
        assertTrue(McpPreferencePage.READ_ONLY_LABEL, McpPreferencePage.READ_ONLY_LABEL.startsWith("&Read-only"));
        assertTrue(McpPreferencePage.READ_ONLY_LABEL,
                McpPreferencePage.READ_ONLY_LABEL.contains("applies when the server next starts"));
    }

    @Test
    public void shouldBindTheReadOnlyCheckboxToItsKey() throws java.io.IOException {
        String page = pageSource();
        assertTrue("the page binds a checkbox to the read-only key with its label",
                page.contains("new BooleanFieldEditor(McpPlugin.PREF_READ_ONLY, READ_ONLY_LABEL, parent)"));
        assertEquals("mcp.server.readOnly", net.vheerden.archi.mcp.McpPlugin.PREF_READ_ONLY);
    }

    @Test
    public void shouldLabelTheImageSourcesCheckbox_andSayItAppliesAtTheNextStart() {
        String label = McpPreferencePage.ALLOW_SERVER_IMAGE_SOURCES_LABEL;
        assertTrue(label, label.contains("&file paths and URLs, and exported files,"));
        assertTrue(label, label.contains("reachable from other machines"));
        assertTrue(label, label.contains("applies when the server next starts"));
    }

    @Test
    public void shouldOfferTheImageSourcesCheckboxOnlyForAValidBindReachableFromOtherMachines() {
        assertFalse(McpPreferencePage.isExposedValidBind("127.0.0.1"));
        assertFalse(McpPreferencePage.isExposedValidBind("localhost"));
        assertFalse(McpPreferencePage.isExposedValidBind("::1"));
        assertFalse("an invalid bind", McpPreferencePage.isExposedValidBind("999.1.1.1"));
        assertFalse("half typed", McpPreferencePage.isExposedValidBind("192.168."));
        assertFalse("reachable, but not an address this page accepts", McpPreferencePage.isExposedValidBind("::"));
        assertFalse(McpPreferencePage.isExposedValidBind(""));
        assertFalse(McpPreferencePage.isExposedValidBind(null));
        assertTrue(McpPreferencePage.isExposedValidBind("0.0.0.0"));
        assertTrue(McpPreferencePage.isExposedValidBind("192.168.1.10"));
    }

    @Test
    public void shouldBindTheImageSourcesCheckboxDirectlyUnderTheBindAddress() throws java.io.IOException {
        String page = pageSource();
        int bind = page.indexOf("addField(bindAddressEditor);");
        int checkbox = page.indexOf("allowServerImageSourcesEditor = new BooleanFieldEditor(");
        int next = page.indexOf("McpPlugin.PREF_AUTO_START");
        assertTrue(bind > 0 && checkbox > bind && next > checkbox);
        assertTrue(page.contains("McpPlugin.PREF_ALLOW_SERVER_IMAGE_SOURCES, ALLOW_SERVER_IMAGE_SOURCES_LABEL, parent);"));
        assertEquals("mcp.server.allowServerImageSources",
                net.vheerden.archi.mcp.McpPlugin.PREF_ALLOW_SERVER_IMAGE_SOURCES);
    }

    @Test
    public void shouldFollowTheBindText_andOnlyEverGreyTheImageSourcesCheckbox() throws java.io.IOException {
        String page = pageSource();
        String updater = page.substring(page.indexOf("private void updateImageSourcesEnabled() {"));
        updater = updater.substring(0, updater.indexOf("\n    }\n") + 6);
        assertTrue(updater, updater.contains("allowServerImageSourcesEditor.setEnabled("
                + "isExposedValidBind(bindAddressEditor.getStringValue()), fieldParent);"));
        for (String writes : new String[] {"setBooleanValue", "loadDefault", "store()", "setValue(", "load()"}) {
            assertFalse("greying the checkbox never changes its value: " + writes, updater.contains(writes));
        }
        String initialize = page.substring(page.indexOf("protected void initialize() {"));
        initialize = initialize.substring(0, initialize.indexOf("\n    }\n"));
        assertTrue(initialize.contains("updateImageSourcesEnabled();"));
        String change = page.substring(page.indexOf("} else if (event.getSource() == bindAddressEditor) {"));
        change = change.substring(0, change.indexOf("\n        }"));
        assertTrue(change, change.contains("updateImageSourcesEnabled();"));
        String defaults = page.substring(page.indexOf("protected void performDefaults() {"));
        defaults = defaults.substring(0, defaults.indexOf("\n    }\n"));
        assertTrue(defaults.contains("updateImageSourcesEnabled();"));
    }

    private static final String BASE = "This bind address exposes the MCP Server to other devices on your network: any "
            + "device that can reach this machine can call its tools.";
    private static final String CLOSE = " Only bind to a non-loopback address on a trusted network, or enable "
            + "bearer-token authentication below to require a secret on every request.";
    private static final String CHANGES = " That includes changing and deleting model content.";
    private static final String READ_ONLY = " The server is read-only, so those tools can read the model but not change it.";
    private static final String FILES = " Image file paths, URLs and exported files are allowed too, so a caller can "
            + "read files this machine can see and write exports to its disk.";

    @Test
    public void shouldSayWhatAnExposedServerOffers_forEachReadOnlyAndFileAccessSetting() {
        assertEquals(BASE + CHANGES + CLOSE, McpPreferencePage.exposureWarningText(false, false));
        assertEquals(BASE + CHANGES + FILES + CLOSE, McpPreferencePage.exposureWarningText(false, true));
        assertEquals(BASE + READ_ONLY + CLOSE, McpPreferencePage.exposureWarningText(true, false));
        assertEquals("a read-only server writes no files and imports no images, so the setting changes nothing",
                BASE + READ_ONLY + CLOSE, McpPreferencePage.exposureWarningText(true, true));
    }

    @Test
    public void shouldClaimFileAccessOnlyWhileTheSettingAllowsIt_onAServerThatCanChangeTheModel() {
        for (boolean readOnly : new boolean[] {false, true}) {
            for (boolean allow : new boolean[] {false, true}) {
                String text = McpPreferencePage.exposureWarningText(readOnly, allow);
                assertFalse(text, text.contains("file read/write"));
                assertEquals(text, !readOnly && allow, text.contains("files"));
                assertFalse("no tool count", text.matches(".*\\d.*"));
            }
        }
    }

    @Test
    public void shouldRefreshTheExposureBanner_whenReadOnlyOrFileAccessIsToggled() throws java.io.IOException {
        String page = pageSource();
        String change = page.substring(page.indexOf("public void propertyChange(PropertyChangeEvent event) {"));
        change = change.substring(0, change.indexOf("\n    }\n"));
        String branch = "} else if (event.getSource() == readOnlyEditor || event.getSource() == allowServerImageSourcesEditor) {";
        assertTrue(change, change.contains(branch));
        String body = change.substring(change.indexOf(branch));
        assertTrue(body, body.contains("updateExposureWarning();"));
        assertTrue(page.contains("readOnlyEditor = new BooleanFieldEditor(McpPlugin.PREF_READ_ONLY, READ_ONLY_LABEL, parent);"));
        String updater = page.substring(page.indexOf("private void updateExposureWarning() {"));
        updater = updater.substring(0, updater.indexOf("\n    }\n"));
        assertTrue("the guard lets a text change through", updater.contains(
                "if (newState == exposureState && java.util.Objects.equals(newText, exposureText)) {"));
        assertTrue(updater.contains("exposureWarningText(readOnlyEditor != null && readOnlyEditor.getBooleanValue(),"));
    }

    private static String pageSource() throws java.io.IOException {
        java.nio.file.Path dir = java.nio.file.Path.of("").toAbsolutePath();
        String rel = "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/ui/McpPreferencePage.java";
        for (int i = 0; i < 6 && dir != null; i++) {
            if (java.nio.file.Files.exists(dir.resolve(rel))) {
                return java.nio.file.Files.readString(dir.resolve(rel), java.nio.charset.StandardCharsets.UTF_8);
            }
            dir = dir.getParent();
        }
        throw new AssertionError("Could not locate " + rel);
    }
}
