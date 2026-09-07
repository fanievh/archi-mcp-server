package net.vheerden.archi.mcp.model;

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
 * Source contract for the logging rule: <em>SLF4J only; never {@code System.out.println}</em>
 * (project-context.md, Code Quality Rules).
 *
 * <h2>Why a source scan rather than review habit</h2>
 *
 * <p>A stray console write is invisible in normal use. It goes to the Eclipse launcher's stdout,
 * which a user running Archi from the Applications folder never sees, so it is neither a working
 * diagnostic nor a harmless one — it is a diagnostic that <em>looks</em> wired up while going
 * nowhere the person debugging will look. The one this test was written for sat behind a
 * system-property flag in {@code VisibilityGraphRouter}, so it survived every review that read the
 * surrounding routing logic: nothing about it was wrong except where its output went.</p>
 *
 * <p>The same argument that puts the internal-code rule in a contract test puts this one here. One
 * cleanup pass does not keep a tree clean.</p>
 *
 * <h2>The one permitted sink</h2>
 *
 * <p>{@code logging/EclipseLogger} is the SLF4J binding itself. Its {@code System.err} write is
 * the last-resort fallback for when the Eclipse log service is unavailable — the floor the rest of
 * the rule stands on, not a violation of it. Excluded by path, and named here so the exemption is
 * a decision on the record rather than a pattern that happens not to match.</p>
 *
 * <h2>What review had to teach this class</h2>
 *
 * <p>The first version shipped three defects a scanner of all things should not have had. Each is
 * now pinned rather than merely fixed:</p>
 *
 * <ol>
 *   <li>Its exclusion proof asserted {@code !isExcluded(f) || f.endsWith(EXCLUDED_PATHS.get(0))},
 *       which is the tautology {@code !X || X} while that list has one entry. It passed for every
 *       possible input and would have kept passing with the exclusion deleted outright.</li>
 *   <li>It reduced each line by cutting at the first {@code //}, so a {@code //} inside a string
 *       literal truncated the line and hid any real call that followed it.</li>
 *   <li>Neither scan asserted the walk found anything, so an emptied or renamed root would have
 *       reported "clean" indistinguishably from a real clean tree.</li>
 * </ol>
 */
public class LoggingContractTest {

    /** The tree the rule governs. Test code may print freely; shipped code may not. */
    private static final String PLUGIN_ROOT = "net.vheerden.archi.mcp/src";

    /**
     * Floor on the number of plugin sources, so a scan that walked nothing cannot report clean.
     * The plugin has roughly 290 java files; this sits far below that deliberately, because the
     * check exists to catch "walked zero", not to track the file count.
     */
    private static final int MINIMUM_PLUGIN_SOURCES = 100;

    /**
     * The SLF4J binding's own fallback sink. See the class Javadoc — this is the implementation
     * of logging, so it cannot itself be required to log through SLF4J.
     */
    private static final List<String> EXCLUDED_PATHS = List.of(
            "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/logging/EclipseLogger.java");

    /**
     * Matches the call, not the words: {@code System.out}/{@code System.err} followed by a stream
     * method. {@code print}, {@code println}, {@code printf}, {@code write} and {@code append} are
     * all caught rather than only the spelling that happened to be in the tree when this was
     * written. Prose that spells out a call is excluded structurally by {@link #executableTextOf},
     * not by hoping the pattern misses it.
     */
    private static final Pattern CONSOLE_WRITE = Pattern.compile(
            "\\bSystem\\s*\\.\\s*(?:out|err)\\s*\\.\\s*(?:print|println|printf|write|append)\\s*\\(");

    /** A {@code System.out} left dangling at end of line — the call is on the next line. */
    private static final Pattern DANGLING_CONSOLE_TARGET =
            Pattern.compile("\\bSystem\\s*\\.\\s*(?:out|err)\\s*$");

    @Test
    public void shouldRouteEveryPluginDiagnosticThroughSlf4j_neverTheConsole() {
        List<String> offenders = new ArrayList<>();

        for (Path file : assertTheWalkActuallyHappened()) {
            for (CodeLine line : executableLinesOf(file)) {
                Matcher matcher = CONSOLE_WRITE.matcher(line.text());
                if (matcher.find()) {
                    offenders.add(file + ":" + line.number() + " -> " + matcher.group().trim());
                }
            }
        }

        assertTrue("Console writes found in " + PLUGIN_ROOT + ". Diagnostics must go through "
                + "SLF4J (logger.debug/info/warn/error): a console write lands on the Eclipse "
                + "launcher's stdout, which nobody running Archi normally ever sees, so it reads "
                + "as a wired-up diagnostic while going nowhere. " + offenders,
                offenders.isEmpty());
    }

    /**
     * A call split across a line break — {@code System.out} on one line, {@code .println} on the
     * next — is invisible to a line-scoped regex. There are none in the tree today. This fails the
     * moment that stops being true rather than letting the gate keep reporting clean over a hole,
     * exactly as {@code InternalCodeContractTest} refuses to let a text block in silently: an
     * unscannable construct is <em>unchecked</em>, never clean.
     */
    @Test
    public void shouldFailIfAConsoleTargetDanglesAtEndOfLine_whichTheLineScopedRegexCannotSee() {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFilesUnder(PLUGIN_ROOT)) {
            for (CodeLine line : executableLinesOf(file)) {
                if (DANGLING_CONSOLE_TARGET.matcher(line.text().stripTrailing()).find()) {
                    offenders.add(file + ":" + line.number());
                }
            }
        }
        assertTrue("A System.out/System.err reference dangles at end of line, so the stream method "
                + "is on the NEXT line and CONSOLE_WRITE cannot see the call. Teach the scanner "
                + "about wrapped calls before landing this: " + offenders,
                offenders.isEmpty());
    }

    /**
     * Proves the exclusion is load-bearing rather than incidental: the binding really does write
     * to the console, and the walk really does leave it out. Asserted against the walk's OUTPUT,
     * because re-deriving the exclusion from the same predicate the walk uses is a tautology that
     * cannot fail — which is exactly what this test did before review caught it.
     */
    @Test
    public void shouldExcludeTheSlf4jBindingsOwnFallbackSink_andProveItStillNeedsExcluding() {
        Path binding = locateRepoFile(EXCLUDED_PATHS.get(0));
        assertTrue("the SLF4J binding is expected to contain the fallback console write this test "
                + "exempts — if it no longer does, the exclusion is protecting nothing and should "
                + "be deleted rather than left in place",
                CONSOLE_WRITE.matcher(readFile(binding)).find());

        assertTrue("the scan must skip the SLF4J binding's own fallback sink, but it appeared in "
                + "the scanned set — the exclusion filter in javaFilesUnder is not doing its job",
                assertTheWalkActuallyHappened().stream().noneMatch(
                        p -> p.toString().replace('\\', '/').endsWith(EXCLUDED_PATHS.get(0))));
    }

    /** The extractor's traps, pinned so a future simplification cannot quietly undo them. */
    @Test
    public void shouldReadExecutableTextWithoutBeingFooledByLiteralsOrComments() {
        assertTrue("a // inside a string literal must NOT truncate the line, or a real call after "
                + "it becomes invisible",
                fires("String u = \"http://x\"; System.out.println(u);", false));

        assertFalse("a call spelled out INSIDE a string literal is text, not code",
                fires("throw new IllegalStateException(\"System.out.println( left\");", false));

        assertFalse("a call spelled out in a trailing line comment is not code",
                fires("int x = 1; // never System.out.println(x)", false));

        assertFalse("a call spelled out inside a Javadoc block is not code",
                fires(" * Never use {@code System.out.println(x)} here.", true));

        assertTrue("a block comment that opens and closes on one line must not swallow the code "
                + "that follows it",
                fires("/* note */ System.out.println(x);", false));

        assertTrue("an unterminated block comment must carry over to the next line",
                executableTextOf("/* opening", false).stillInBlockComment());

        assertFalse("a closed block comment must not leak into the next line",
                executableTextOf("/* opened and closed */ int x = 1;", false).stillInBlockComment());

        assertFalse("a char literal holding a quote must not open a phantom string that swallows "
                + "the rest of the line",
                executableTextOf("if (c == '\"') { int y = 2; }", false).stillInBlockComment());
    }

    private static boolean fires(String line, boolean startsInBlockComment) {
        return CONSOLE_WRITE.matcher(executableTextOf(line, startsInBlockComment).text()).find();
    }

    private static List<Path> assertTheWalkActuallyHappened() {
        List<Path> scanned = javaFilesUnder(PLUGIN_ROOT);
        assertTrue("the plugin scan found only " + scanned.size() + " java files under "
                + PLUGIN_ROOT + ", below the floor of " + MINIMUM_PLUGIN_SOURCES + ". A scan that "
                + "walked nothing reports 'clean' identically to a real clean scan, so this fails "
                + "rather than certifying a tree it never read.",
                scanned.size() >= MINIMUM_PLUGIN_SOURCES);
        return scanned;
    }

    /** One source line reduced to its executable text, with its 1-based line number. */
    private record CodeLine(int number, String text) { }

    /** Executable text of one line, plus whether the NEXT line starts inside a block comment. */
    private record CodeOnly(String text, boolean stillInBlockComment) { }

    private static List<CodeLine> executableLinesOf(Path file) {
        List<CodeLine> lines = new ArrayList<>();
        boolean inBlockComment = false;
        int number = 0;
        for (String raw : readFile(file).split("\r?\n", -1)) {
            CodeOnly code = executableTextOf(raw, inBlockComment);
            inBlockComment = code.stillInBlockComment();
            lines.add(new CodeLine(++number, code.text()));
        }
        return lines;
    }

    /**
     * Reduces a source line to the text a compiler would treat as code: comments removed, string
     * and char literal <em>contents</em> dropped (their delimiters kept, so nothing runs together).
     *
     * <p>The naive version — cut at the first {@code //} — is wrong in both directions, and review
     * found both. It yields <strong>false negatives</strong>, because a {@code //} inside a literal
     * truncates the line before a real call: {@code String u = "http://x"; System.out.println(u);}
     * loses its violation to the URL's own slashes. And it yields <strong>false positives</strong>,
     * because prose spelling the call out — a Javadoc paragraph explaining what not to do —
     * matches as if it were the call.</p>
     *
     * <p>The sibling {@code InternalCodeContractTest.stringLiteralsOf} already solved exactly this,
     * down to a unit test pinning {@code "http://example/M4"}. This is that same left-to-right
     * literal-aware pass, inverted: it keeps what that one throws away.</p>
     */
    private static CodeOnly executableTextOf(String line, boolean startsInBlockComment) {
        StringBuilder code = new StringBuilder();
        boolean inBlock = startsInBlockComment;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inBlock) {
                if (c == '*' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                    inBlock = false;
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < line.length()) {
                char next = line.charAt(i + 1);
                if (next == '/') {
                    break;
                }
                if (next == '*') {
                    inBlock = true;
                    i++;
                    continue;
                }
            }
            if (c == '"' || c == '\'') {
                code.append(c);
                for (i++; i < line.length(); i++) {
                    char inner = line.charAt(i);
                    if (inner == '\\') {
                        i++;
                    } else if (inner == c) {
                        code.append(c);
                        break;
                    }
                }
                continue;
            }
            code.append(c);
        }
        return new CodeOnly(code.toString(), inBlock);
    }

    private static boolean isExcluded(Path file) {
        String path = file.toString().replace('\\', '/');
        return EXCLUDED_PATHS.stream().anyMatch(path::endsWith);
    }

    private static List<Path> javaFilesUnder(String root) {
        Path dir = locateRepoFile(root);
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !isExcluded(p))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("could not walk " + dir, e);
        }
    }

    private static String readFile(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }

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
