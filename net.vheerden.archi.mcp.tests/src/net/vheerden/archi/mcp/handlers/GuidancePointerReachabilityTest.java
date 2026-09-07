package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * The pointer-reachability pawl.
 *
 * <p><strong>What this exists to stop.</strong> The plugin points the agent at {@code archimate://}
 * guidance URIs from two kinds of site: tool descriptions, and — less obviously — runtime response
 * prose emitted mid-workflow. A URI that no longer resolves turns each of those into an instruction
 * the agent cannot carry out. That failure is invisible in every existing test, because nothing
 * links the string in the handler to the registry that would have to serve it. It surfaces in the
 * field, as an agent burning turns on a scheme it cannot fetch.</p>
 *
 * <p><strong>Why a source scan rather than a runtime one.</strong> Pointers are assembled inside
 * tool descriptions and, in one case, inside a response body on a branch a test would have to reach
 * first. Scanning the shipped source reaches every site without having to drive it.</p>
 *
 * <p><strong>Known limit, stated rather than implied.</strong> The scan only sees URIs written as
 * string literals. A pointer assembled from a variable or {@code String.format} — e.g.
 * {@code URI_PREFIX + namespace + "/" + name} — contributes nothing to the scan and nothing to the
 * pinned count, so it would be invisible here. Every pointer that ships today is a literal; if that
 * ever stops being true, this test does not cover the new site and the count pin will not say so.</p>
 *
 * <p><strong>Why literals are joined before matching.</strong> Several pointers are split across a
 * string concatenation to satisfy line length, e.g. {@code "archimate://prompts/routing-preconditions-"
 * + "checklist"}. A per-line regex sees {@code archimate://prompts/routing-preconditions-}, which
 * resolves nowhere — a false failure that would train the next reader to weaken the assertion. So
 * the scanner concatenates runs of literals joined by {@code +} before extracting.</p>
 */
public class GuidancePointerReachabilityTest {

    /** Source root of the production plugin, relative to either project directory. */
    private static final String[] SOURCE_ROOTS = {
            "net.vheerden.archi.mcp/src",
            "../net.vheerden.archi.mcp/src",
    };

    /** The shipped guidance bodies, which cross-reference each other by the same URIs. */
    private static final String[] RESOURCE_ROOTS = {
            "net.vheerden.archi.mcp/resources",
            "../net.vheerden.archi.mcp/resources",
    };

    private static final String SCHEME = "archimate://";

    /** A Java string literal, honouring escapes so an embedded quote does not end the match. */
    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** Whitespace-only concatenation between two literals — the signal they form one string. */
    private static final Pattern CONCAT_GAP = Pattern.compile("\\s*\\+\\s*");

    private static final Pattern URI = Pattern.compile("archimate://[A-Za-z0-9._~/-]*");

    /**
     * Number of guidance pointers currently shipped in production source.
     *
     * <p>Pinned so that a pointer silently deleted, or a scanner regression that stops finding
     * them, fails rather than passing quietly on an empty set. Update deliberately, in the commit
     * that adds or removes a pointer.</p>
     *
     * <p>Current distribution: 23 in {@code ViewPlacementHandler}, 3 in {@code ResourceHandler}
     * (the guidance tool's own worked example, its follow-up suggestion, and the example it offers
     * when a caller supplies a uri of the wrong type), 1 each in {@code DiscoveryHandler} and
     * {@code ElementCreationHandler}. Bare {@code archimate://} occurrences — the scheme constant
     * and prose naming the scheme in general — are not pointers and are excluded by the scanner.</p>
     */
    private static final int EXPECTED_POINTER_COUNT = 28;

    /**
     * Number of {@code archimate://} cross-references inside the shipped guidance bodies.
     *
     * <p>The recipe index routes the agent to a family page by URI, and the pages point back at the
     * reference material the same way. Being prose rather than compiled string literals, these rot
     * more easily than source, not less — renaming a resource file would break them silently.</p>
     *
     * <p>Counted as OCCURRENCES, not distinct URIs, and not lines: a second reference to a URI
     * already cited elsewhere still moves this number, and one line carrying two pointers counts
     * twice. So adding a single cross-reference anywhere requires the bump. Current distribution:
     * 10 in the recipe index, 5 in {@code archimate-view-patterns}, 2 in
     * {@code application-integration}, 1 each in {@code archimate-layers} and the
     * routing-preconditions checklist — which sums to the constant below. Derive a revised
     * distribution the way the scanner does, by counting matches of the URI pattern; a
     * line-oriented count (one hit per matching line) under-reports the index by one.</p>
     */
    private static final int EXPECTED_RESOURCE_POINTER_COUNT = 19;

    // ---- The pawl ---------------------------------------------------------------------------

    /**
     * Every guidance URI that ships must resolve to a resource the server can actually serve.
     */
    @Test
    public void shouldResolveEveryShippedGuidancePointer_soNoneOrdersAnUnreachableFetch() {
        Set<String> servable = servableUris();
        Set<String> dead = new TreeSet<>();

        List<Pointer> all = new ArrayList<>(scanPointers());
        all.addAll(scanResourcePointers());

        for (Pointer pointer : all) {
            if (!servable.contains(pointer.uri())) {
                dead.add(pointer.uri() + "  (" + pointer.file() + ")");
            }
        }

        assertTrue("These guidance URIs are named in shipped source but resolve to nothing the "
                + "server can serve. Each one instructs the agent to fetch something that does not "
                + "exist — through the resource capability or through get-guidance alike. Fix the "
                + "pointer or add the resource: " + dead,
                dead.isEmpty());
    }

    /**
     * The count pin. Without it, a scanner that silently matched nothing would report a clean bill
     * of health over an empty set.
     */
    @Test
    public void shouldFindEveryKnownPointer_soAnEmptyScanCannotPassAsClean() {
        List<Pointer> pointers = scanPointers();

        assertEquals("Guidance-pointer count changed. If you added or removed a pointer, update "
                + "EXPECTED_POINTER_COUNT in the same commit. If you did not, the scanner has "
                + "regressed and is no longer seeing the sites it guards. Found: " + pointers,
                EXPECTED_POINTER_COUNT, pointers.size());
    }

    /** The same pin for the guidance bodies, counted separately so neither can mask the other. */
    @Test
    public void shouldFindEveryCrossReferenceInTheGuidanceBodies_soAnEmptyScanCannotPassAsClean() {
        List<Pointer> pointers = scanResourcePointers();

        assertEquals("Cross-reference count inside the shipped guidance bodies changed. Update "
                + "EXPECTED_RESOURCE_POINTER_COUNT in the same commit if that was deliberate; "
                + "otherwise the markdown scan has regressed. Found: " + pointers,
                EXPECTED_RESOURCE_POINTER_COUNT, pointers.size());
    }

    /**
     * Guards the joiner itself. If literal concatenation stopped being handled, the split pointers
     * would decay into truncated prefixes — which is exactly what this test would then catch.
     */
    @Test
    public void shouldJoinConcatenatedLiterals_soASplitUriIsNotReadAsTruncated() {
        List<String> found = extractUris(
                "logger.info(\"see archimate://prompts/routing-preconditions-\"\n"
                        + "        + \"checklist for the playbook\");");

        assertEquals(List.of("archimate://prompts/routing-preconditions-checklist"), found);
    }

    /** Adjacent literals NOT joined by {@code +} are separate strings and must not be glued. */
    @Test
    public void shouldNotGlueUnrelatedLiterals_whenTheyAreMerelyNeighbours() {
        List<String> found = extractUris(
                "String prefix = \"archimate://\";\n"
                        + "String name = \"reference/archimate-layers\";");

        assertEquals(List.of(SCHEME), found);
    }

    /**
     * Every declared URI must be backed by a file that actually loads.
     *
     * <p>A declaration that outlives its file is invisible to the pointer scan above — the pointer
     * still names something the table still declares — but both delivery routes would answer a
     * client "not found".</p>
     */
    @Test
    public void shouldBackEveryDeclaredUri_soADeclarationCannotOutliveItsFile() {
        Set<String> loaded = new ResourceHandler().loadedUris();
        Set<String> declaredButUnloadable = new TreeSet<>();

        for (ResourceHandler.GuidanceEntry entry : ResourceHandler.guidanceCatalogue()) {
            if (!loaded.contains(entry.uri())) {
                declaredButUnloadable.add(entry.uri());
            }
        }

        assertTrue("These URIs are declared in the guidance catalogue but their content did not "
                + "load, so every route would answer 'not found' for them while the pointer scan "
                + "still passed. Restore the file or drop the declaration: " + declaredButUnloadable,
                declaredButUnloadable.isEmpty());
    }

    /**
     * A lone double quote inside a char literal or a comment must not desynchronise the scan.
     * Without this, everything after such a character is mis-parsed and a dead pointer further down
     * the same file is silently missed — a false PASS, the dangerous direction.
     */
    @Test
    public void shouldNotDesynchronise_whenAQuoteAppearsInACharLiteralOrComment() {
        String source = "case '\"' -> sb.append(\"esc\");\n"
                + "// a comment mentioning \" and archimate://reference/commented-out\n"
                + "String s = \"see archimate://reference/archimate-layers now\";";

        assertEquals(List.of("archimate://reference/archimate-layers"), extractUris(source));
    }

    /** A comment between two joined fragments must not break the join into a truncated pointer. */
    @Test
    public void shouldStillJoin_whenACommentSitsBetweenTwoConcatenatedFragments() {
        String source = "String s = \"see archimate://prompts/routing-preconditions-\" // note\n"
                + "        + \"checklist for the playbook\";";

        assertEquals(List.of("archimate://prompts/routing-preconditions-checklist"),
                extractUris(source));
    }

    /**
     * A pointer truncated to a single path segment must still be counted and must still fail to
     * resolve — only the bare scheme is exempt. Without this, weakening the scheme exemption into
     * a prefix match would silently stop guarding half-written URIs.
     */
    @Test
    public void shouldStillCountATruncatedPointer_thoughTheBareSchemeIsExempt() {
        List<Pointer> pointers = new ArrayList<>();
        for (String uri : extractUris("String s = \"see archimate://prompts/ for details\";")) {
            if (!SCHEME.equals(uri)) {
                pointers.add(new Pointer(uri, "synthetic"));
            }
        }

        assertEquals(1, pointers.size());
        assertEquals("archimate://prompts/", pointers.get(0).uri());
        assertTrue("a truncated pointer must not resolve",
                !servableUris().contains(pointers.get(0).uri()));
    }

    // ---- Scanner ----------------------------------------------------------------------------

    private record Pointer(String uri, String file) {
        @Override
        public String toString() {
            return uri + " (" + file + ")";
        }
    }

    /**
     * Pointers written in the guidance bodies themselves, which cross-reference one another.
     *
     * <p>These are prose, not string literals, so they are extracted directly — and being prose,
     * they rot more readily than source. A recipe telling the agent to "fetch
     * {@code archimate://recipes/index} first" fails exactly the same way as a tool description
     * doing so.</p>
     */
    private List<Pointer> scanResourcePointers() {
        List<Pointer> pointers = new ArrayList<>();

        for (Path file : filesUnder(RESOURCE_ROOTS, ".md")) {
            Matcher m = URI.matcher(read(file));
            while (m.find()) {
                String uri = trimTrailingProse(m.group());
                if (!SCHEME.equals(uri) && !uri.isEmpty()) {
                    pointers.add(new Pointer(uri, file.getFileName().toString()));
                }
            }
        }
        return pointers;
    }

    private List<Pointer> scanPointers() {
        List<Pointer> pointers = new ArrayList<>();

        for (Path file : productionSources()) {
            String source = read(file);
            String fileName = file.getFileName().toString();

            for (String uri : extractUris(source)) {
                // The bare scheme with no path is not a pointer at anything: it is either the
                // constant URIs are built from, or prose naming the scheme in general ("any
                // archimate:// URI named here..."). Neither instructs a fetch, so neither can be
                // dead. A TRUNCATED pointer still carries at least one path segment
                // ("archimate://prompts/"), so it is still counted and still has to resolve.
                if (SCHEME.equals(uri)) {
                    continue;
                }
                pointers.add(new Pointer(uri, fileName));
            }
        }
        return pointers;
    }

    /**
     * Extracts every guidance URI from Java source, joining literal runs first.
     *
     * <p>Trailing prose punctuation is trimmed: pointers are embedded in sentences, so
     * {@code "...archimate://reference/archimate-layers."} names the resource, not a resource
     * whose id ends in a full stop.</p>
     */
    static List<String> extractUris(String source) {
        List<String> uris = new ArrayList<>();
        for (String run : literalRuns(stripCommentsAndCharLiterals(source))) {
            Matcher m = URI.matcher(run);
            while (m.find()) {
                String uri = trimTrailingProse(m.group());
                if (!uri.isEmpty()) {
                    uris.add(uri);
                }
            }
        }
        return uris;
    }

    /**
     * Blanks out comments and character literals before the string-literal scan runs.
     *
     * <p>Both can carry a lone double quote — this codebase already writes {@code case '"' ->}
     * elsewhere — and a lone quote desynchronises naive quote pairing for the whole rest of the
     * file. The consequence is not a noisy failure but a <em>silent</em> one: everything after it
     * is mis-parsed, so a genuinely dead pointer further down the same file stops being seen. A
     * comment mentioning a URI is likewise not a pointer at anything, and a comment sitting between
     * two concatenated fragments must not break the join.</p>
     *
     * <p>Replacement preserves length and newlines so that offsets and the concatenation-gap check
     * still line up with the original source.</p>
     */
    static String stripCommentsAndCharLiterals(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        boolean inString = false;

        while (i < source.length()) {
            char c = source.charAt(i);

            if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < source.length()) {
                    out.append(source.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                i++;
                continue;
            }

            if (c == '"') {
                inString = true;
                out.append(c);
                i++;
                continue;
            }
            if (c == '\'') {
                i = blank(source, out, i, charLiteralEnd(source, i));
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                int end = source.indexOf('\n', i);
                i = blank(source, out, i, end < 0 ? source.length() : end);
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                i = blank(source, out, i, end < 0 ? source.length() : end + 2);
                continue;
            }

            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** Appends {@code [from, to)} as spaces (newlines kept) and returns {@code to}. */
    private static int blank(String source, StringBuilder out, int from, int to) {
        for (int k = from; k < to; k++) {
            out.append(source.charAt(k) == '\n' ? '\n' : ' ');
        }
        return to;
    }

    /** End offset (exclusive) of the char literal opening at {@code start}. */
    private static int charLiteralEnd(String source, int start) {
        int i = start + 1;
        if (i < source.length() && source.charAt(i) == '\\') {
            i++;
        }
        i++;
        return i < source.length() && source.charAt(i) == '\'' ? i + 1 : start + 1;
    }

    /** Concatenates literals separated by nothing but {@code +} and whitespace. */
    private static List<String> literalRuns(String source) {
        List<String> runs = new ArrayList<>();
        Matcher m = LITERAL.matcher(source);

        StringBuilder current = null;
        int previousEnd = -1;
        while (m.find()) {
            boolean continues = current != null
                    && CONCAT_GAP.matcher(source.substring(previousEnd, m.start())).matches();
            if (continues) {
                current.append(m.group(1));
            } else {
                if (current != null) {
                    runs.add(current.toString());
                }
                current = new StringBuilder(m.group(1));
            }
            previousEnd = m.end();
        }
        if (current != null) {
            runs.add(current.toString());
        }
        return runs;
    }

    private static String trimTrailingProse(String uri) {
        int end = uri.length();
        while (end > 0 && ".,;:)`".indexOf(uri.charAt(end - 1)) >= 0) {
            end--;
        }
        return uri.substring(0, end);
    }

    // ---- Fixtures ---------------------------------------------------------------------------

    /**
     * The URIs the server can actually serve — bodies that <strong>loaded</strong>, not URIs that
     * are merely declared.
     *
     * <p>The distinction is the whole point. Resolving a pointer against the declaration table
     * would report a clean bill of health when a resource file has been deleted, renamed, or had
     * its path mistyped: the declaration survives, the pointer still names it, and both delivery
     * routes would answer a real client "not found" while this test passed.</p>
     */
    private static Set<String> servableUris() {
        Set<String> uris = new LinkedHashSet<>(new ResourceHandler().loadedUris());
        assertTrue("No resource bodies loaded at all, so this test would pass over an empty set and "
                + "prove nothing. The resource files must be on the classpath for this gate to mean "
                + "anything — check that the harness packaged net.vheerden.archi.mcp/resources/.",
                uris.size() >= 14);
        return uris;
    }

    private static List<Path> productionSources() {
        return filesUnder(SOURCE_ROOTS, ".java");
    }

    /**
     * Every file under the first resolvable root with the given extension.
     *
     * <p>Fails rather than returning empty when no root resolves — a scan that silently covers
     * nothing is the one outcome a build gate must never report as clean.</p>
     */
    private static List<Path> filesUnder(String[] roots, String extension) {
        Path root = null;
        for (String candidate : roots) {
            Path path = Paths.get(candidate);
            if (Files.isDirectory(path)) {
                root = path;
                break;
            }
        }
        assertTrue("None of " + String.join(", ", roots) + " resolved from "
                + Paths.get("").toAbsolutePath() + " — the scan cannot silently cover nothing",
                root != null);

        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(extension))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
