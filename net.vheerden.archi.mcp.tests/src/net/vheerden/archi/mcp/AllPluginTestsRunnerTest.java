package net.vheerden.archi.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

/**
 * Headless pins for the PDE batch runner. The runner itself only runs inside Eclipse, so its list and
 * its "did this class really run" rule are checked here, where every CI lane sees them.
 */
public class AllPluginTestsRunnerTest {

    private static final String MANIFEST = "tools/osgi-excluded-tests.txt";
    private static final String GROUP_A_HEADER = "# --- Group A";
    private static final String TEST_SOURCE_ROOT = "net.vheerden.archi.mcp.tests/src";

    /**
     * The two ways a test in this tree steps aside when it is not under OSGi: it asks the platform
     * ({@code Platform.isRunning()}, or whether a bundle is present), and it catches the error an
     * Archi class throws when its static init fails off-OSGi and turns it into an assumption. Only a
     * test that asks the platform has a reason to: headlessly the answer is always "not running".
     * Matched on code only, with comments and string contents blanked, because both span lines.
     */
    private static final Pattern PLATFORM_QUERY =
            Pattern.compile("\\bPlatform\\s*\\.\\s*(isRunning|getBundle)\\s*\\(");
    private static final Pattern CATCH_CLAUSE = Pattern.compile("\\bcatch\\s*\\(([^)]*)\\)\\s*\\{");
    private static final Pattern INIT_FAILURE =
            Pattern.compile("\\b(ExceptionInInitializerError|NoClassDefFoundError)\\b");
    private static final Pattern ASSUMPTION_CALL = Pattern.compile("\\bassume[A-Z]\\w*\\s*\\(");
    static final String IDIOM_PLATFORM_QUERY = "Platform query";
    static final String IDIOM_CAUGHT_INIT_FAILURE = "caught init failure";

    /**
     * Group A classes run nowhere but this runner (headless lanes skip them, ci-pde does not run
     * them), so a Group A class missing from the list is a class that never runs.
     */
    @Test
    public void shouldListEveryGroupAClass_whenManifestNamesThem() throws IOException {
        List<String> groupA = readGroupA();
        assertFalse("manifest Group A must not be empty - did its header change?", groupA.isEmpty());

        Set<String> listed = new TreeSet<>(Arrays.asList(AllPluginTestsRunner.TEST_CLASS_NAMES));
        List<String> missing = new ArrayList<>();
        for (String fqcn : groupA) {
            if (!listed.contains(fqcn)) {
                missing.add(fqcn);
            }
        }
        assertTrue("AllPluginTestsRunner.TEST_CLASS_NAMES is missing manifest Group A classes: " + missing,
                missing.isEmpty());
    }

    /**
     * A test that skips off-OSGi runs only where OSGi does, and this runner is that place. Outside
     * Group A such a class still runs its other tests headlessly, so nothing else notices that the
     * skipped ones run in no lane at all.
     */
    @Test
    public void shouldListEveryClassThatSkipsOffOsgi_whenItsSourceUsesTheGuardIdiom() throws IOException {
        Map<String, Set<String>> users = readOffOsgiSkipClasses();
        // One guard per idiom: a pattern that silently stops matching must not hide behind the other.
        for (String idiom : List.of(IDIOM_PLATFORM_QUERY, IDIOM_CAUGHT_INIT_FAILURE)) {
            assertTrue("no test class uses the off-OSGi skip idiom '" + idiom
                    + "' - did the scan root or the idiom's pattern change?",
                    users.values().stream().anyMatch(idioms -> idioms.contains(idiom)));
        }
        List<String> idiomUsers = new ArrayList<>(users.keySet());

        Set<String> listed = new TreeSet<>(Arrays.asList(AllPluginTestsRunner.TEST_CLASS_NAMES));
        List<String> missing = new ArrayList<>();
        for (String fqcn : idiomUsers) {
            if (!listed.contains(fqcn)) {
                missing.add(fqcn);
            }
        }
        assertTrue("these classes skip tests off-OSGi but are not in AllPluginTestsRunner.TEST_CLASS_NAMES, "
                + "so the skipped tests run in no lane - list each class, or move its skipped tests into "
                + "a class that is listed: " + missing, missing.isEmpty());
    }

    @Test
    public void shouldSeeAPlatformQuery_whenItIsReachedThroughAHelper() {
        assertEquals(Set.of(IDIOM_PLATFORM_QUERY), offOsgiIdioms(
                "private static boolean osgi() { return Platform.isRunning(); }\n"
                + "@Before public void setUp() { assumeTrue(osgi()); }"));
        assertEquals(Set.of(IDIOM_PLATFORM_QUERY), offOsgiIdioms(
                "assumeTrue(\"needs the export bundle\", Platform.getBundle(\"x\") != null);"));
    }

    @Test
    public void shouldSeeACaughtInitFailure_whenTheCatchIsWrittenAnotherWay() {
        assertEquals("final modifier", Set.of(IDIOM_CAUGHT_INIT_FAILURE), offOsgiIdioms(
                "try { a(); } catch (final NoClassDefFoundError e) { Assume.assumeNoException(e); }"));
        assertEquals("type second in a multi-catch", Set.of(IDIOM_CAUGHT_INIT_FAILURE), offOsgiIdioms(
                "try { a(); } catch (IllegalStateException | ExceptionInInitializerError e) {"
                + " Assume.assumeTrue(\"m\", false); }"));
        assertEquals("nested block before the assumption", Set.of(IDIOM_CAUGHT_INIT_FAILURE), offOsgiIdioms(
                "try { a(); } catch (NoClassDefFoundError e) { if (x) { log(); }"
                + " Assume.assumeTrue(\"m\", false); }"));
    }

    @Test
    public void shouldIgnoreTheIdioms_whenTheyAppearOnlyInComments() {
        assertEquals(Set.of(), offOsgiIdioms(
                "/** Unlike its sibling, this never calls {@code Platform.isRunning()}. */\n"
                + "// assumeTrue(Platform.isRunning()) would skip it headlessly\n"
                + "try { a(); } catch (NoClassDefFoundError e) { // assumes nothing\n throw e; }"));
    }

    @Test
    public void shouldNotListAClassTwice_whenEntriesAreAdded() {
        List<String> names = Arrays.asList(AllPluginTestsRunner.TEST_CLASS_NAMES);
        assertEquals("duplicate entries in TEST_CLASS_NAMES",
                names.size(), new TreeSet<>(names).size());
    }

    @Test
    public void shouldReturnNull_whenEveryTestRan() {
        assertNull(AllPluginTestsRunner.notRunReason(5, 0, 0));
        assertNull("an @Ignore'd test next to real runs is not a skip fallback",
                AllPluginTestsRunner.notRunReason(5, 0, 1));
    }

    @Test
    public void shouldFail_whenAnyTestWasAssumptionSkipped() {
        assertEquals("1 of 20 tests assumption-skipped", AllPluginTestsRunner.notRunReason(20, 1, 0));
        assertEquals("every test was assumption-skipped", AllPluginTestsRunner.notRunReason(3, 3, 0));
    }

    @Test
    public void shouldNameTheCause_whenNoTestRan() {
        // JUnit 4.13.2 reports a @BeforeClass assumption failure as run=0, assumptionFailures=1.
        assertEquals("class-level assumption skip, no test ran", AllPluginTestsRunner.notRunReason(0, 1, 0));
        assertEquals("every test is @Ignore'd", AllPluginTestsRunner.notRunReason(0, 0, 2));
        assertEquals("no test ran", AllPluginTestsRunner.notRunReason(0, 0, 0));
    }

    @Test
    public void shouldKeepEachSkipMessage_whenATestIsAssumptionSkipped() {
        List<Failure> skips = new ArrayList<>();
        Result result = AllPluginTestsRunner.runClass(OneSkipFixture.class, skips);

        assertEquals(2, result.getRunCount());
        assertEquals(1, result.getAssumptionFailureCount());
        assertEquals(1, skips.size());
        assertEquals("needs the launch argument X", skips.get(0).getMessage());
    }

    /** Run only through {@link AllPluginTestsRunner#runClass}; the harness runs top-level classes. */
    public static class OneSkipFixture {
        @Test
        public void runs() {
        }

        @Test
        public void skips() {
            Assume.assumeTrue("needs the launch argument X", false);
        }
    }

    private static List<String> readGroupA() throws IOException {
        List<String> classes = new ArrayList<>();
        boolean inGroupA = false;
        for (String raw : Files.readAllLines(locateRepoFile(MANIFEST), StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (line.startsWith("# --- Group")) {
                inGroupA = line.startsWith(GROUP_A_HEADER);
                continue;
            }
            if (!inGroupA || line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            classes.add(line.split("\\s+")[0]);
        }
        return classes;
    }

    /** Test classes using an off-OSGi skip idiom, each with the idioms it uses, by class name. */
    private static Map<String, Set<String>> readOffOsgiSkipClasses() throws IOException {
        Path root = locateRepoFile(TEST_SOURCE_ROOT);
        // This file spells out the idioms it looks for, so it would match itself.
        String self = AllPluginTestsRunnerTest.class.getName();
        Map<String, Set<String>> classes = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(f -> f.toString().endsWith("Test.java")).sorted().toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                Set<String> idioms = offOsgiIdioms(text);
                if (idioms.isEmpty()) {
                    continue;
                }
                String relative = root.relativize(file).toString();
                String fqcn = relative.substring(0, relative.length() - ".java".length())
                        .replace(file.getFileSystem().getSeparator(), ".");
                if (!fqcn.equals(self)) {
                    classes.put(fqcn, idioms);
                }
            }
        }
        return classes;
    }

    /** Which off-OSGi skip idioms a test source uses; empty when it uses none. */
    static Set<String> offOsgiIdioms(String source) {
        String code = blankCommentsAndStrings(source);
        Set<String> found = new TreeSet<>();
        if (PLATFORM_QUERY.matcher(code).find()) {
            found.add(IDIOM_PLATFORM_QUERY);
        }
        Matcher clause = CATCH_CLAUSE.matcher(code);
        while (clause.find()) {
            if (INIT_FAILURE.matcher(clause.group(1)).find()
                    && ASSUMPTION_CALL.matcher(blockFrom(code, clause.end() - 1)).find()) {
                found.add(IDIOM_CAUGHT_INIT_FAILURE);
                break;
            }
        }
        return found;
    }

    /** The block opened by the brace at {@code open}, braces balanced; to the end if unclosed. */
    private static String blockFrom(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return code.substring(open, i + 1);
            }
        }
        return code.substring(open);
    }

    /**
     * Java source with every comment, and the contents of every string and char literal, replaced
     * by spaces (quotes and line breaks kept), so a mention in prose or a brace in a message cannot
     * pass for code.
     */
    static String blankCommentsAndStrings(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            char next = i + 1 < n ? source.charAt(i + 1) : 0;
            if (c == '/' && next == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && next == '*') {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                for (; i < end; i++) {
                    out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                }
            } else if (c == '"' || c == '\'') {
                out.append(c);
                i++;
                while (i < n && source.charAt(i) != c && source.charAt(i) != '\n') {
                    if (source.charAt(i) == '\\' && i + 1 < n) {
                        out.append(' ');
                        i++;
                    }
                    out.append(' ');
                    i++;
                }
                if (i < n) {
                    out.append(source.charAt(i));
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Walks up from the working directory, as {@code InternalCodeContractTest} does. */
    private static Path locateRepoFile(String relative) {
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
