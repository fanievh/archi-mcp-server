package net.vheerden.archi.mcp;

import static net.vheerden.archi.mcp.JavaSourceText.blank;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * Keeps {@code McpPreferenceInitializer} the only production code that writes the plugin's default
 * preferences.
 *
 * <p>Equinox builds the DEFAULT scope once: it runs the registered initializer, then applies the
 * product's {@code plugin_customization.ini} and any {@code -pluginCustomization} file on top. That is how an
 * administrator presets this plugin's settings. Any code that writes a default after that load
 * overwrites the administrator's value with ours, and nothing reports it: the setting simply reads as
 * our default. This test refuses a second writer.</p>
 *
 * <p>The property is checked over every Java file under {@code net.vheerden.archi.mcp/src}, so a writer
 * added anywhere is caught without editing this test. Comments and literals are blanked first, so a
 * mention in prose never counts. Caught spellings: a {@code setDefault(} call on any receiver, the
 * method reference {@code ::setDefault}, and any reference to the type {@code DefaultScope}.
 * <strong>Not caught:</strong> reflection, a write through a {@code Preferences} node handed in from
 * elsewhere, and a bare {@code setDefault(} called from inside a preference-store subclass. Writes to the
 * user's own (instance) scope, such as {@code setValue(}, are not default writes and are allowed.</p>
 *
 * <p>It also keeps {@code McpPlugin} the only production code that reads a preference value. Its getters
 * fall back to the built-in default, with a warning, when a value is missing or unusable; a caller that
 * reads the store itself gets {@code 0}, {@code ""} or {@code false} instead, and approval mode read that
 * way switches off. Caught: a typed read, {@code get} or {@code getDefault} followed by {@code Int},
 * {@code Boolean}, {@code String}, {@code Long}, {@code Double} or {@code Float}, whose argument names a
 * {@code PREF_} constant anywhere inside its parentheses, nested calls included. <strong>Not caught:</strong>
 * field editors on the preference page, which bind a key by name and are read by JFace through the page's
 * own validation; writes such as {@code setValue(}; and a read whose call does not itself name a
 * {@code PREF_} constant, such as one through a local holding the key or a key spelled as a string
 * literal.</p>
 */
public class PreferenceDefaultsContractTest {

    private static final String SRC = "net.vheerden.archi.mcp/src";
    private static final String INITIALIZER = "net/vheerden/archi/mcp/ui/McpPreferenceInitializer.java";
    private static final String PLUGIN = "net/vheerden/archi/mcp/McpPlugin.java";

    private static final Pattern DEFAULT_WRITE =
            Pattern.compile("(?:\\.\\s*setDefault\\s*\\(|::\\s*setDefault\\b|\\bDefaultScope\\b)");
    /** A static String constant named PREF_*, whatever the order or presence of its other modifiers. */
    private static final Pattern PREF_KEY =
            Pattern.compile("\\bstatic\\b[^;={}()]*\\bString\\s+(PREF_\\w+)\\s*=");
    private static final Pattern TYPED_READ_CALL = Pattern.compile(
            "\\.\\s*get(?:Default)?(?:Int|Boolean|String|Long|Double|Float)\\s*\\(");
    private static final Pattern PREF_REFERENCE = Pattern.compile("\\bPREF_\\w+");
    private static final Pattern INITIALIZER_ELEMENT =
            Pattern.compile("<initializer\\s+class\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern REGISTERED_INITIALIZER = Pattern.compile(
            "<extension\\s+point\\s*=\\s*\"org\\.eclipse\\.core\\.runtime\\.preferences\"\\s*>\\s*"
            + "<initializer\\s+class\\s*=\\s*\"net\\.vheerden\\.archi\\.mcp\\.ui\\.McpPreferenceInitializer\"");

    @Test
    public void shouldWriteDefaultsOnlyInTheInitializer_whenProductionCodeTouchesPreferences() {
        List<String> offenders = new ArrayList<>();
        for (Path file : productionSources()) {
            String rel = relative(file);
            if (!INITIALIZER.equals(rel) && writesADefault(code(file))) {
                offenders.add(rel);
            }
        }
        assertTrue("These files write the plugin's default preferences, which overwrites any default an "
                + "administrator set with -pluginCustomization: " + offenders + ". Set defaults only in "
                + "McpPreferenceInitializer.", offenders.isEmpty());
    }

    @Test
    public void shouldReadPreferencesOnlyInMcpPlugin_whenProductionCodeNeedsASetting() {
        List<String> offenders = new ArrayList<>();
        for (Path file : productionSources()) {
            String rel = relative(file);
            if (!PLUGIN.equals(rel) && readsAPreference(code(file))) {
                offenders.add(rel);
            }
        }
        assertTrue("These files read a preference from the store instead of through McpPlugin's getters, so "
                + "a missing or unusable value reads as 0, \"\" or false instead of the default: " + offenders
                + ". Add or use a getter on McpPlugin.", offenders.isEmpty());
    }

    @Test
    public void shouldReadPreferencesInMcpPlugin_whenTheGettersResolveThem() {
        // Without this, a change to how McpPlugin reads the store would leave the property above true of nothing
        assertTrue("McpPlugin must be the code that reads the preferences",
                readsAPreference(code(locate(SRC).resolve(PLUGIN))));
    }

    @Test
    public void shouldCatchEveryTypedRead_andIgnoreProseAndWrites_whenScanningSource() {
        assertTrue(readsAPreference(blank("on = store.getBoolean(McpPlugin.PREF_APPROVAL_MODE);")));
        assertTrue(readsAPreference(blank("p = plugin.getPreferenceStore() . getInt ( PREF_PORT );")));
        assertTrue(readsAPreference(blank("s = store.getString(\n    McpPlugin.PREF_BIND_ADDRESS);")));
        assertTrue(readsAPreference(blank("n = store.getLong(McpPlugin.PREF_X);")));
        assertTrue("a nested call is inside the argument",
                readsAPreference(blank("b = store.getBoolean(keyOf(McpPlugin.PREF_X));")));
        assertTrue("a key after a nested call's closing parenthesis is still inside the argument",
                readsAPreference(blank("b = store.getBoolean(scope(ID) + McpPlugin.PREF_X);")));
        assertTrue(readsAPreference(blank("d = store.getDouble(McpPlugin.PREF_X);")));
        assertTrue(readsAPreference(blank("f = store.getFloat(McpPlugin.PREF_X);")));
        assertTrue(readsAPreference(blank("s = store.getDefaultString(McpPlugin.PREF_X);")));
        assertFalse("a comment is not a read", readsAPreference(blank("// getBoolean(McpPlugin.PREF_X)\n")));
        assertFalse("Javadoc is not a read",
                readsAPreference(blank("/** {@code store.getBoolean(McpPlugin.PREF_X)} */")));
        assertFalse("a string is not a read", readsAPreference(blank("s = \"getBoolean(McpPlugin.PREF_X)\";")));
        assertFalse("a write is not a read", readsAPreference(blank("store.setValue(McpPlugin.PREF_X, v);")));
        assertFalse("a read of another key is not a preference read",
                readsAPreference(blank("v = json.getString(\"name\");")));
        assertFalse("PREF_ after the call closes is not its argument",
                readsAPreference(blank("v = node.getString(k); store.setValue(McpPlugin.PREF_X, v);")));
        assertFalse("PREF_ beside the call is not its argument",
                readsAPreference(blank("v = node.getString(k) + McpPlugin.PREF_X;")));
    }

    @Test
    public void shouldWriteDefaultsInTheInitializer_whenTheDefaultsAreLoaded() {
        // Without this, renaming or moving the initializer would leave the property above true of nothing
        assertTrue("McpPreferenceInitializer must be the code that writes the defaults",
                writesADefault(code(locate(SRC).resolve(INITIALIZER))));
    }

    @Test
    public void shouldRegisterOnlyMcpPreferenceInitializer_whenEclipseLoadsTheDefaults() {
        // With no second writer, every default depends on Equinox running this initializer. If plugin.xml
        // stopped registering it, every setting would read as 0, "" or false, approval mode included.
        String pluginXml = withoutXmlComments(read(locate("net.vheerden.archi.mcp/plugin.xml")));
        List<String> initializers = new ArrayList<>();
        Matcher m = INITIALIZER_ELEMENT.matcher(pluginXml);
        while (m.find()) {
            initializers.add(m.group(1));
        }
        assertTrue("plugin.xml must register exactly one preference initializer, McpPreferenceInitializer: "
                + initializers, initializers.equals(List.of("net.vheerden.archi.mcp.ui.McpPreferenceInitializer")));
        assertTrue("McpPreferenceInitializer must be registered on org.eclipse.core.runtime.preferences",
                REGISTERED_INITIALIZER.matcher(pluginXml).find());
    }

    @Test
    public void shouldGiveEveryPreferenceKeyADefault_whenTheInitializerRuns() {
        List<String> keys = preferenceKeys(code(locate(SRC).resolve(PLUGIN)));
        assertTrue("the key scan must find McpPlugin's PREF_ constants, or it certifies nothing: " + keys,
                keys.contains("PREF_PORT") && keys.contains("PREF_AUTO_START"));
        String initializer = code(locate(SRC).resolve(INITIALIZER));
        List<String> missing = new ArrayList<>();
        for (String key : keys) {
            if (!putsADefaultFor(initializer, key)) {
                missing.add(key);
            }
        }
        assertTrue("McpPreferenceInitializer sets no default for " + missing + ". A key without a default "
                + "reads as 0, \"\" or false; a port of 0 lets the server bind any free port.",
                missing.isEmpty());
    }

    @Test
    public void shouldCatchEveryDefaultWrite_andIgnoreProseAndInstanceWrites_whenScanningSource() {
        assertTrue(writesADefault(blank("store.setDefault(KEY, 1);")));
        assertTrue(writesADefault(blank("getPreferenceStore() . setDefault (KEY, true);")));
        assertTrue(writesADefault(blank("keys.forEach(store::setDefault);")));
        assertTrue(writesADefault(blank("IEclipsePreferences d = DefaultScope.INSTANCE.getNode(ID);")));
        assertTrue(writesADefault(blank("import org.eclipse.core.runtime.preferences.DefaultScope;")));
        assertTrue(writesADefault(blank("new DefaultScope().getNode(ID);")));
        assertFalse("a comment is not a write", writesADefault(blank("// store.setDefault(KEY, 1)\n")));
        assertFalse("Javadoc is not a write", writesADefault(blank("/** see {@link DefaultScope} */")));
        assertFalse("a string is not a write", writesADefault(blank("s = \"store.setDefault(KEY, 1)\";")));
        assertFalse("an instance write is allowed", writesADefault(blank("store.setValue(KEY, true);")));
        assertFalse("another method's name is not a write", writesADefault(blank("model.setDefaults();")));
    }

    @Test
    public void shouldMatchEachKeyByName_whenCheckingTheInitializerForADefault() {
        String code = blank("defaults.putInt(McpPlugin.PREF_PORT, 1);\n"
                + "defaults.put( McpPlugin . PREF_BIND_ADDRESS , \"x\");\n"
                + "// defaults.putBoolean(McpPlugin.PREF_AUTO_START, true);\n");
        assertTrue(putsADefaultFor(code, "PREF_PORT"));
        assertTrue(putsADefaultFor(code, "PREF_BIND_ADDRESS"));
        assertFalse("a commented-out put is no default", putsADefaultFor(code, "PREF_AUTO_START"));
        assertFalse("a key's name is not a prefix of another's", putsADefaultFor(code, "PREF_POR"));
        assertTrue(preferenceKeys(blank("public static final String PREF_X = \"a\";\n"
                + "public static final int DEFAULT_Y = 1;\n")).equals(List.of("PREF_X")));
        assertTrue("any modifier order, and package-private, is a key", preferenceKeys(blank(
                "static final String PREF_A = \"a\";\nfinal static public String PREF_B = \"b\";\n"))
                .equals(List.of("PREF_A", "PREF_B")));
        assertTrue("a local variable is not a key",
                preferenceKeys(blank("void m() { String PREF_C = \"c\"; }\n")).isEmpty());
    }

    // ---- the match ----------------------------------------------------------------------------

    private static boolean writesADefault(String code) {
        return DEFAULT_WRITE.matcher(code).find();
    }

    private static boolean readsAPreference(String code) {
        Matcher call = TYPED_READ_CALL.matcher(code);
        while (call.find()) {
            int open = call.end() - 1;
            if (PREF_REFERENCE.matcher(code.substring(open, closingParen(code, open))).find()) {
                return true;
            }
        }
        return false;
    }

    /** The index just past the parenthesis that closes the one at {@code open}, or the end of the code. */
    private static int closingParen(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i + 1;
            }
        }
        return code.length();
    }

    private static boolean putsADefaultFor(String code, String key) {
        return Pattern.compile("\\bput\\w*\\s*\\(\\s*McpPlugin\\s*\\.\\s*" + Pattern.quote(key) + "\\b")
                .matcher(code).find();
    }

    private static List<String> preferenceKeys(String code) {
        List<String> keys = new ArrayList<>();
        Matcher m = PREF_KEY.matcher(code);
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }

    // ---- the tree -----------------------------------------------------------------------------

    private static List<Path> productionSources() {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(locate(SRC))) {
            walk.filter(Files::isRegularFile).filter(f -> f.toString().endsWith(".java")).forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to walk " + SRC, e);
        }
        assertTrue("the scan must actually find the production tree, or it certifies nothing: "
                + files.size(), files.size() > 50);
        return files;
    }

    private static String relative(Path file) {
        return locate(SRC).relativize(file).toString().replace('\\', '/');
    }

    private static String code(Path file) {
        return blank(read(file));
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }

    private static String withoutXmlComments(String xml) {
        return xml.replaceAll("(?s)<!--.*?-->", "");
    }

    private static Path locate(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new AssertionError("Could not locate " + relative + " by walking up from "
                + Path.of("").toAbsolutePath() + ". This test must run with a working directory "
                + "inside the checkout.");
    }
}
