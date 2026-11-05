package net.vheerden.archi.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * Pins the minimum supported Archi version, declared in the plugin manifest, and keeps the
 * README's published requirement in step with it.
 *
 * <p>The plugin calls {@code IDiagramModelNote.isLegend()}, which first exists in Archi 5.8.
 * Without a {@code bundle-version} on {@code com.archimatetool.model} the bundle resolves on
 * 5.7 and then throws {@code NoSuchMethodError} on the first note update. Every test lane runs
 * on a newer Archi where the method exists, so no behavioural test can see that failure. Only
 * the declaration can be checked here, and this class checks it.</p>
 *
 * <p><strong>What is proved elsewhere, and what nothing proves.</strong> The CI workflow takes an
 * {@code archi_version} input on a manual run; run with {@code 5.8.0}, it compiles the plugin and
 * runs both test lanes against Archi 5.8's classes, which catches a newer-only API referenced at
 * compile time (the {@code isLegend()} defect class). Neither that run nor this class resolves the
 * manifest through OSGi, so nothing in the build proves that an older Archi refuses the plugin;
 * that rests on the declaration pinned here.</p>
 *
 * <p>The floor is matched exactly, not as a minimum: raising it is a published-contract change
 * that must move the README and CHANGELOG with it, so it should red this class on purpose.</p>
 */
public class ArchiPlatformFloorTest {

    /** The declared minimum Archi release. */
    private static final int[] FLOOR = {5, 8, 0};

    /** The release every build and test lane runs on; a declared range must admit it. */
    private static final int[] TESTED = {5, 10, 0};

    private static final String MANIFEST = "net.vheerden.archi.mcp/META-INF/MANIFEST.MF";

    /** The test fragment's manifest, which declares optional Archi bundles of its own. */
    private static final String TESTS_MANIFEST = "net.vheerden.archi.mcp.tests/META-INF/MANIFEST.MF";

    /** The bundle namespace the floor applies to. */
    private static final String ARCHI_BUNDLE_PREFIX = "com.archimatetool.";

    private static final Pattern VERSION_ATTRIBUTE =
            Pattern.compile("bundle-version\\s*=\\s*(\"[^\"]*\"|[^;\\s]+)");

    @Test
    public void shouldDeclareTheArchiFloor_onTheModelBundle() throws IOException {
        assertFloor("com.archimatetool.model");
    }

    @Test
    public void shouldDeclareTheArchiFloor_onTheEditorBundle() throws IOException {
        assertFloor("com.archimatetool.editor");
    }

    /**
     * Every Archi bundle either manifest names with a version carries the same floor, optional ones
     * included. An optional requirement does not stop an older Archi from loading the plugin, so a
     * lower floor there changes nothing at runtime; it is held anyway because a manifest that names
     * two minimum Archi versions is a published contract that contradicts itself.
     */
    @Test
    public void shouldDeclareTheSameFloor_onEveryArchiBundleEitherManifestNames() throws IOException {
        List<String> wrong = new ArrayList<>();
        List<String> checked = new ArrayList<>();
        for (String manifest : new String[] {MANIFEST, TESTS_MANIFEST}) {
            for (String clause : requireBundleClauses(manifest)) {
                if (!clause.split(";", 2)[0].trim().startsWith(ARCHI_BUNDLE_PREFIX)) {
                    continue;
                }
                Matcher v = VERSION_ATTRIBUTE.matcher(clause);
                if (!v.find()) {
                    continue;
                }
                checked.add(clause.split(";", 2)[0].trim());
                String range = v.group(1).replace("\"", "").trim();
                String lower = range.startsWith("[") || range.startsWith("(")
                        ? range.substring(1).split(",")[0].trim()
                        : range;
                if (range.startsWith("(")
                        || !normalise(lower).equals(FLOOR[0] + "." + FLOOR[1] + "." + FLOOR[2])) {
                    wrong.add(manifest + ": " + clause);
                }
            }
        }
        // Named, not counted: a clause that lost its version while another gained one keeps a count.
        assertEquals("Archi bundle clauses carrying a bundle-version across both manifests",
                List.of("com.archimatetool.model", "com.archimatetool.editor", "com.archimatetool.export.svg",
                        "com.archimatetool.canvas"), checked);
        assertTrue("Every versioned Archi bundle must declare the floor " + FLOOR[0] + "." + FLOOR[1]
                + "." + FLOOR[2] + ", inclusive:\n  " + String.join("\n  ", wrong), wrong.isEmpty());
    }

    @Test
    public void shouldPublishTheSameFloor_inTheReadmeRequirements() throws IOException {
        String readme = Files.readString(repoRoot().resolve("README.md"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\\| \\[Archi\\]\\([^)]*\\) \\| ([0-9.]+)\\+ \\|").matcher(readme);
        assertTrue("README requirements table must carry an '| [Archi](...) | X.Y+ |' row", m.find());
        assertEquals("README's minimum Archi must match the manifest floor",
                FLOOR[0] + "." + FLOOR[1], m.group(1));
    }

    private static void assertFloor(String bundle) throws IOException {
        String clause = requireBundleClause(bundle);
        assertFalse(bundle + " must not be optional: an optional requirement does not stop an "
                + "older Archi from loading the plugin", clause.matches("(?s).*resolution\\s*:=\\s*optional.*"));
        Matcher v = VERSION_ATTRIBUTE.matcher(clause);
        assertTrue(bundle + " must declare a bundle-version floor, or older Archi resolves it "
                + "and fails at runtime", v.find());
        String range = v.group(1).replace("\"", "").trim();
        String lower;
        String upper = null;
        boolean upperInclusive = false;
        if (range.startsWith("[") || range.startsWith("(")) {
            assertTrue(bundle + " floor must be inclusive ('['), got " + range, range.startsWith("["));
            String[] bounds = range.substring(1, range.length() - 1).split(",");
            assertEquals(bundle + " range must have two bounds: " + range, 2, bounds.length);
            lower = bounds[0].trim();
            upper = bounds[1].trim();
            upperInclusive = range.endsWith("]");
        } else {
            lower = range;
        }
        assertEquals(bundle + " floor", FLOOR[0] + "." + FLOOR[1] + "." + FLOOR[2], normalise(lower));
        if (upper != null) {
            int cmp = compare(TESTED, parse(normalise(upper)));
            assertTrue(bundle + " range " + range + " must admit the tested Archi "
                    + TESTED[0] + "." + TESTED[1] + "." + TESTED[2], upperInclusive ? cmp <= 0 : cmp < 0);
        }
    }

    /** One {@code Require-Bundle} clause of the plugin manifest, by exact symbolic name. */
    private static String requireBundleClause(String bundle) throws IOException {
        for (String clause : requireBundleClauses(MANIFEST)) {
            if (clause.split(";", 2)[0].trim().equals(bundle)) {
                return clause;
            }
        }
        throw new AssertionError(bundle + " must be in Require-Bundle");
    }

    /** Every {@code Require-Bundle} clause of a manifest, splitting on commas outside quotes. */
    private static List<String> requireBundleClauses(String manifestPath) throws IOException {
        String manifest = Files.readString(repoRoot().resolve(manifestPath), StandardCharsets.UTF_8)
                .replace("\r\n", "\n").replace('\r', '\n').replace("\n ", "");
        Matcher m = Pattern.compile("(?im)^Require-Bundle:\\s*(.*)$").matcher(manifest);
        assertTrue(manifestPath + " must carry a Require-Bundle header", m.find());
        List<String> clauses = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (char c : m.group(1).toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
            }
            if (c == ',' && !quoted) {
                clauses.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        clauses.add(current.toString().trim());
        return clauses;
    }

    private static String normalise(String version) {
        String[] parts = version.split("\\.");
        return (parts.length > 0 ? parts[0] : "0") + "." + (parts.length > 1 ? parts[1] : "0")
                + "." + (parts.length > 2 ? parts[2] : "0");
    }

    private static int[] parse(String version) {
        String[] parts = version.split("\\.");
        return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }

    /** The checkout root: the nearest ancestor of the working directory holding the plugin manifest. */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.exists(dir.resolve(MANIFEST))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new AssertionError("Could not locate " + MANIFEST + " by walking up from "
                + Path.of("").toAbsolutePath() + ". This test must run with a working directory "
                + "inside the checkout.");
    }
}
