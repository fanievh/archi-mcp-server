package net.vheerden.archi.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * Settings the server reads once as it starts are read in exactly one place, the production start path,
 * so the tools it registers and what it announces cannot disagree within one run, and nothing re-reads
 * them while it runs. Only the plugin, the defaults initializer and the preference page may name the key.
 */
public class StartupPreferenceReadOnceTest {

    private static final String SRC = "net.vheerden.archi.mcp/src";
    private static final String MANAGER = "net/vheerden/archi/mcp/server/McpServerManager.java";
    private static final Set<String> MAY_NAME_THE_KEY = Set.of(
            "net/vheerden/archi/mcp/McpPlugin.java",
            "net/vheerden/archi/mcp/ui/McpPreferenceInitializer.java",
            "net/vheerden/archi/mcp/ui/McpPreferencePage.java");

    @Test
    public void shouldReadReadOnlyOnlyOnce_inTheServersStartPath() throws IOException {
        assertReadOnceAtStart("isReadOnly", "PREF_READ_ONLY");
    }

    @Test
    public void shouldReadTheServerImageSourcesSettingOnlyOnce_inTheServersStartPath() throws IOException {
        assertReadOnceAtStart("isServerImageSourcesAllowed", "PREF_ALLOW_SERVER_IMAGE_SOURCES");
    }

    @Test
    public void shouldJudgeTheBindTheServerTook_withTheClassificationThePreferencePageReads() throws IOException {
        Path src = repoRoot().resolve(SRC);
        String manager = Files.readString(src.resolve(MANAGER), StandardCharsets.UTF_8);
        String start = manager.substring(manager.indexOf("public synchronized void start() {"),
                manager.indexOf("synchronized void start(int port, String bindAddress) {"));
        assertTrue(start.replaceAll("\\s+", " ").contains(
                "boolean allowServerImageSources = net.vheerden.archi.mcp.McpPlugin.getDefault()"
                        + " .isServerImageSourcesAllowed();"));
        assertTrue(start.contains("BindExposure boundTo = transportConfig.getBindExposure();"));
        assertTrue(start.replaceAll("\\s+", " ").contains("boolean refuseServerImageSources = !allowServerImageSources"
                + " && (boundTo == null || !boundTo.allowsServerFileAccess());"));
        assertTrue("the bind is judged after the server has bound it",
                start.indexOf("transportConfig.startServer();")
                        < start.indexOf("transportConfig.getBindExposure()"));
        assertTrue(start.contains("initializeHandlers(readOnly, refuseServerImageSources);"));
        String page = Files.readString(src.resolve("net/vheerden/archi/mcp/ui/McpPreferencePage.java"),
                StandardCharsets.UTF_8);
        Matcher exposed = Pattern.compile("IpAddressFieldEditor\\.isValidAddress\\((\\w+)\\)\\s*&&\\s*"
                + "BindExposure\\.of\\(\\1\\)\\.warnsOperator\\(\\)").matcher(page);
        int uses = 0;
        while (exposed.find()) {
            uses++;
        }
        assertEquals("the exposure banner and the image-sources checkbox share one test", 1, uses);
        assertTrue(page.contains("static boolean isExposedValidBind(String bind)"));
    }

    @Test
    public void shouldHandTheSameReadOnlyValueToTheTransportAndTheRegistrar() throws IOException {
        String manager = Files.readString(repoRoot().resolve(SRC).resolve(MANAGER), StandardCharsets.UTF_8);
        String start = manager.substring(manager.indexOf("public synchronized void start() {"),
                manager.indexOf("synchronized void start(int port, String bindAddress) {"));
        String testStart = manager.substring(manager.indexOf("synchronized void start(int port, String bindAddress) {"));
        assertTrue(start.contains("boolean readOnly = net.vheerden.archi.mcp.McpPlugin.getDefault().isReadOnly();"));
        assertTrue(start.contains("transportConfig.setInstructions(readOnly ? TransportConfig.READ_ONLY_INSTRUCTIONS : null);"));
                assertTrue("the test-only start serves every tool, explicitly",
                testStart.contains("transportConfig.setInstructions(null);")
                        && testStart.contains("initializeHandlers(false, false);"));
    }

    private void assertReadOnceAtStart(String getter, String key) throws IOException {
        Pattern call = Pattern.compile("\\." + getter + "\\(\\)");
        Pattern keyUse = Pattern.compile("\\b" + key + "\\b");
        List<String> calls = new ArrayList<>();
        Set<String> namers = new TreeSet<>();
        Path src = repoRoot().resolve(SRC);
        try (Stream<Path> files = Files.walk(src)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String rel = src.relativize(file).toString().replace('\\', '/');
                String code = Files.readString(file, StandardCharsets.UTF_8);
                Matcher m = call.matcher(code);
                while (m.find()) {
                    calls.add(rel);
                }
                if (keyUse.matcher(code).find()) {
                    namers.add(rel);
                }
            }
        }
        assertEquals("calls to " + getter + "()", List.of(MANAGER), calls);
        String manager = Files.readString(src.resolve(MANAGER), StandardCharsets.UTF_8);
        int start = manager.indexOf("public synchronized void start() {");
        int testStart = manager.indexOf("synchronized void start(int port, String bindAddress) {");
        int at = manager.indexOf("." + getter + "()");
        assertTrue("the read is inside the production start(), before the test-only start",
                start >= 0 && at > start && at < testStart);
        namers.removeAll(MAY_NAME_THE_KEY);
        assertEquals("files outside the plugin, the initializer and the page that name " + key,
                Set.of(), namers);
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isDirectory(dir.resolve(SRC))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new AssertionError("Could not locate " + SRC + " above " + Path.of("").toAbsolutePath());
    }
}
