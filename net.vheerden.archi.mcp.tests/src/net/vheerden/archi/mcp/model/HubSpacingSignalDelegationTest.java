package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * Build-fired guard that every consumer of the large-hub boundary reads it from
 * {@link HubSpacingSignal} rather than from an expression or a literal of its
 * own. Two granularities are covered: the five view-level spacing callsites in
 * the accessor, and the element-level gate in
 * {@link HubSizingSuggestionBuilder}.
 *
 * <p><strong>Why a source-level guard rather than a behavioural one.</strong>
 * The five callsites live inside accessor methods whose committing path runs
 * through Archi's command stack, which needs an OSGi context and is therefore
 * unreachable in both automated lanes. {@code HubSpacingSignalTest} pins the
 * predicate itself and goes red the moment its threshold moves — but it is
 * blind to a callsite that stops calling it, because nothing it exercises
 * touches the accessor. Restoring an inline derivation at any of the five
 * leaves that pin fully green. This guard is the only check in a headless lane
 * that can see the difference.</p>
 *
 * <p><strong>What made this worth pinning.</strong> The predicate was written
 * out at each callsite. Four copies agreed on "some element carries more than
 * six connections"; a fifth had drifted to "the hub-detection result is not
 * empty", which is a test for the view being connected at all, because
 * {@code detect-hub-elements} admits every element with at least one
 * connection. The two spacing heuristics both read that boolean, so the single
 * wrong copy moved an element target and a group target one column apart from
 * the sibling tools on the identical view. Copies that merely happen to agree
 * are what allowed it; delegation is what makes the agreement structural.</p>
 *
 * <p><strong>Three ways a regression could evade a weaker guard, and how each
 * is closed here.</strong> Counting delegations alone is not enough.</p>
 * <ol>
 *   <li>A callsite could keep calling the helper and then <em>weaken</em> the
 *       result — {@code = HubSpacingSignal.hasLargeHubs(r) && someOtherFlag}
 *       contains the delegation as a substring while no longer being the
 *       canonical derivation. So the initialiser is matched <em>whole</em>,
 *       not searched for a substring.</li>
 *   <li>A sixth derivation could appear under a local name this file's
 *       pattern does not recognise, or be declared with {@code var}. So a
 *       second sweep looks for the inline <em>predicate</em> across the whole
 *       production tree, independent of any variable name.</li>
 *   <li>A sixth derivation could appear in a different class entirely. That
 *       same sweep is repository-wide rather than scoped to the accessor.</li>
 * </ol>
 *
 * <p><strong>The local names are matched case-insensitively on purpose.</strong>
 * Two of the five callsites name the variable {@code triggerHasLargeHubs}. A
 * case-sensitive search for {@code hasLargeHubs} misses both, which is exactly
 * how the earlier census of this defect undercounted the sites.</p>
 *
 * <p><strong>Why the element-level consumer needs its own two assertions.</strong>
 * {@link HubSizingSuggestionBuilder} does not merely test the boundary — it
 * subtracts it to size the suggested growth and interpolates it into the served
 * sentence. Those two spellings are invisible to the repository-wide sweep
 * below, which matches one predicate shape: {@code - 6} uses a different
 * operator and {@code "…: 6)"} is a string literal, so neither contains
 * {@link #INLINE_PREDICATE}. A guard that cleared that file on the predicate
 * alone would be certifying a file it had never actually read for the other two
 * forms. So a second sweep, scoped to that one file, rejects the boundary
 * written as a bare integer in <em>any</em> form, and a delegation assertion
 * pins the two reads it must make instead.</p>
 */
public class HubSpacingSignalDelegationTest {

    /** The facade holding every spacing callsite. Repo-relative, not on the classpath. */
    private static final String ACCESSOR_FILE =
            "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/ArchiModelAccessorImpl.java";

    /** The production source root swept for inline re-derivations. */
    private static final String PRODUCTION_SRC = "net.vheerden.archi.mcp/src";

    /** The element-level consumer of the boundary. Repo-relative, not on the classpath. */
    private static final String SIZING_BUILDER_FILE =
            "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/HubSizingSuggestionBuilder.java";

    /**
     * Number of spacing callsites deriving the signal, measured from source.
     *
     * <p>One each for the density-aware default of {@code adjust-view-spacing},
     * the two single-arm convenience tools, the composed tool that drives both
     * arms, and the default resolution of {@code arrange-groups}.</p>
     */
    private static final int EXPECTED_CALLSITES = 5;

    /**
     * A local boolean named for the large-hub signal, and everything up to the
     * terminating semicolon — so an initialiser wrapped onto the next line is
     * captured whole rather than read as an empty assignment.
     */
    private static final Pattern DERIVATION = Pattern.compile(
            "boolean\\s+(\\w*[Hh]as[Ll]arge[Hh]ubs)\\s*=([^;]*);");

    /**
     * The one initialiser shape a spacing callsite may have: the shared call
     * and nothing else. Anchored at both ends so a widened or weakened
     * expression that merely <em>contains</em> the call fails.
     */
    private static final Pattern CANONICAL_INITIALISER = Pattern.compile(
            "HubSpacingSignal\\.hasLargeHubs\\([A-Za-z_$][A-Za-z0-9_$]*\\)");

    /** The inline predicate this extraction exists to eliminate. */
    private static final String INLINE_PREDICATE = "connectionCount() > 6";

    /**
     * Files permitted to contain {@link #INLINE_PREDICATE}, each for a stated
     * reason. Adding a file here is a deliberate, reviewable admission that a
     * further place now spells the threshold out — which is the whole thing
     * this guard exists to make visible rather than silent.
     *
     * <ul>
     *   <li>{@code SpacingControlLoop} — javadoc on
     *       {@code DENSITY_HUB_FANOUT_CONN_THRESHOLD}, a named constant for a
     *       different signal (whether a hub's box is adequate for its fan-out)
     *       that documents itself as mirroring this threshold.</li>
     * </ul>
     */
    private static final Set<String> INLINE_PREDICATE_ALLOWED = Set.of(
            "SpacingControlLoop.java");

    /**
     * The element-level gate {@link HubSizingSuggestionBuilder} must call, and
     * the constant it must read for the value it cannot get from a predicate.
     *
     * <p>Both are needed. The gate alone leaves the excess arithmetic and the
     * served sentence free to spell the number again; the constant alone leaves
     * the branch free to drift to a different boundary.</p>
     */
    private static final List<String> ELEMENT_LEVEL_READS = List.of(
            "HubSpacingSignal.isLargeHub(",
            "HubSpacingSignal.LARGE_HUB_CONNECTION_COUNT");

    /**
     * The boundary written as a bare integer, in any form.
     *
     * <p>Word-bounded so it cannot fire on the digit inside another number:
     * {@code 15}, {@code 12} and {@code 16} are all left alone, while
     * {@code > 6}, {@code - 6} and {@code : 6)} all match. Scoped to
     * {@link #SIZING_BUILDER_FILE} rather than swept repository-wide, where a
     * lone digit is far too common to police and the allow-list would end up
     * longer than the thing it guards.</p>
     *
     * <p>Comments are <em>not</em> stripped here, unlike the delegation check
     * above. Two of the five original spellings were javadoc, so prose is
     * in scope by design — the cost is that an unrelated punctuation-adjacent
     * six anywhere in that file ({@code @since 1.6.0}, a footnote, an index)
     * also trips it. That is a deliberate trade, and the assertion says how to
     * resolve it rather than leaving a reader to assume a defect.</p>
     */
    private static final Pattern BARE_BOUNDARY = Pattern.compile("\\b6\\b");

    @Test
    public void shouldFindEverySpacingCallsite_whenTheFacadeSourceIsRead() {
        assertEquals("The number of large-hub derivations in the facade changed. "
                + "If a spacing callsite was added or removed this is the place to "
                + "record it; if one merely moved, the count should not have shifted.",
                EXPECTED_CALLSITES, derivations().size());
    }

    @Test
    public void shouldDeriveThroughTheSharedSignal_atEverySpacingCallsite() {
        List<String> offenders = new ArrayList<>();
        for (Derivation derivation : derivations()) {
            String normalised = derivation.initialiser.trim().replaceAll("\\s+", " ");
            if (!CANONICAL_INITIALISER.matcher(normalised).matches()) {
                offenders.add(derivation.describe());
            }
        }

        assertTrue("Every large-hub derivation must be exactly a call to "
                + "HubSpacingSignal.hasLargeHubs(...) and nothing else, so the callsites "
                + "are provably identical rather than coincidentally equal. An inline "
                + "expression here is how the composed tool came to select a different "
                + "spacing column than its siblings on the same view; a call that is then "
                + "combined with another term is the same divergence wearing the call's "
                + "name. Offending: " + offenders, offenders.isEmpty());
    }

    @Test
    public void shouldSpellTheThresholdInOnePlace_whenTheProductionTreeIsSwept() {
        List<String> offenders = new ArrayList<>();
        Path root = repoFile(PRODUCTION_SRC);
        try (Stream<Path> tree = Files.walk(root)) {
            for (Path java : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = java.getFileName().toString();
                if (INLINE_PREDICATE_ALLOWED.contains(name)) {
                    continue;
                }
                if (read(java).contains(INLINE_PREDICATE)) {
                    offenders.add(root.relativize(java).toString());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to walk " + root, e);
        }

        assertTrue("HubSpacingSignal owns the large-hub threshold; no other production "
                + "file may spell '" + INLINE_PREDICATE + "' inline. This sweep is "
                + "repository-wide and name-agnostic on purpose: the sibling assertions "
                + "above see only declarations in the accessor whose local is named for "
                + "the signal, so a re-derivation in another class, or under another "
                + "name, would otherwise ship with no test signal at all. If a new site "
                + "is genuinely a different question, add it to the allow-list with the "
                + "reason. Offending: " + offenders, offenders.isEmpty());
    }

    @Test
    public void shouldReadTheSharedBoundary_atTheElementLevelGate() {
        String code = strippedOfComments(read(repoFile(SIZING_BUILDER_FILE)));
        List<String> missing = new ArrayList<>();
        for (String read : ELEMENT_LEVEL_READS) {
            if (!code.contains(read)) {
                missing.add(read);
            }
        }

        assertTrue("The per-element sizing gate must take its boundary from "
                + "HubSpacingSignal, both as a predicate and as a value. Its sibling "
                + "assertions cannot see this: the accessor sweep reads a different "
                + "file, and HubSpacingSignalTest exercises the helper rather than "
                + "anything that calls it, so a gate that stops calling leaves both "
                + "fully green. Comments are stripped before this match, because that "
                + "class documents the constant by name: matching the raw source would "
                + "let the javadoc alone satisfy a claim about what the code does. "
                + "Missing: " + missing, missing.isEmpty());
    }

    @Test
    public void shouldSpellNoBareBoundary_whenTheElementLevelConsumerIsRead() {
        Path file = repoFile(SIZING_BUILDER_FILE);
        List<String> offenders = new ArrayList<>();
        String[] lines = read(file).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (BARE_BOUNDARY.matcher(lines[i]).find()) {
                offenders.add("line " + (i + 1) + ": " + lines[i].trim());
            }
        }

        assertTrue("The large-hub boundary must not reappear as a bare integer in "
                + "the sizing builder — not in the branch, not in the excess "
                + "arithmetic, and not inside the served sentence. The repository-wide "
                + "sweep above matches one predicate shape and is structurally blind "
                + "to the other two: a subtraction uses a different operator and a "
                + "format string is a literal. This assertion is what actually clears "
                + "that file. If the six reported here IS the boundary, route it "
                + "through HubSpacingSignal.LARGE_HUB_CONNECTION_COUNT. If it is an "
                + "unrelated six that happens to live in this file, this sweep cannot "
                + "tell the difference and narrowing it is a deliberate, reviewable "
                + "change to make here — not a reason to respell the number. "
                + "Offending: " + offenders, offenders.isEmpty());
    }

    /**
     * Removes block and line comments so a claim about executable code cannot be
     * satisfied by prose that merely names the symbol.
     *
     * <p>Deliberately naive: it does not track string literals, so a {@code //}
     * or {@code /*} inside a string would truncate the line. Neither file this
     * class reads contains one, and the failure direction is safe — a stripped
     * literal can only <em>remove</em> text, which makes a required read look
     * missing and fails the guard loudly, never the reverse.</p>
     */
    private static String strippedOfComments(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("//[^\n]*", " ");
    }

    private record Derivation(String name, String initialiser, int line) {
        String describe() {
            return name + " at line " + line + " -> '" + initialiser.trim() + "'";
        }
    }

    private static List<Derivation> derivations() {
        String source = read(repoFile(ACCESSOR_FILE));
        List<Derivation> found = new ArrayList<>();
        Matcher matcher = DERIVATION.matcher(source);
        while (matcher.find()) {
            int line = 1 + (int) source.substring(0, matcher.start())
                    .chars().filter(c -> c == '\n').count();
            found.add(new Derivation(matcher.group(1), matcher.group(2), line));
        }
        return found;
    }

    /**
     * Resolves a repo-relative path by walking upward from the working directory.
     *
     * <p>Production source is not on the classpath, so the classloader cannot
     * see it. A runner started outside the checkout fails explicitly here —
     * the alternative failure mode for a guard is to quietly stop guarding.</p>
     */
    private static Path repoFile(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new AssertionError("Could not locate " + relative + " by walking up from "
                + Path.of("").toAbsolutePath() + ". This test must run with a working "
                + "directory inside the checkout.");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + path, e);
        }
    }
}
