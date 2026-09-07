package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.handlers.HandlerRegistrar;
import net.vheerden.archi.mcp.handlers.SearchHandler;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Build-fired guard on every surface that prescribes one-undo recovery for
 * {@code auto-layout-and-route} <em>before</em> the arm the call will take is known.
 *
 * <h2>What drifted</h2>
 *
 * <p>The rating-regression disclosure is composed by {@code buildQualityTargetDto}, which runs
 * ahead of both the approval gate and {@code dispatchOrQueue}. Its tail — that the layout and
 * routes were applied anyway and that {@code undo} recovers them in one call — is true of exactly
 * one of the three arms, and was stamped onto all three. On the batched arm one response carried
 * both "the new layout and routes were applied anyway. Undo restores the previous state" and
 * "mutation queued as operation #1 in current batch"; on the approval arm the card described the
 * change as already applied to the human who had not yet approved it. An agent obeying that reverts
 * whichever command is really on top of the stack, or on a clean stack reverts nothing and then
 * re-reads a still-degraded view — and an agent that concludes {@code undo} does not work stops
 * attempting recoverable operations at all.</p>
 *
 * <p>The surfaces below are the ones read <em>before</em> the call, when no arm exists yet. They
 * were never false about the immediate arm; they were unscoped. So this guard does not ban the
 * claim — it requires the qualifier that makes it arm-aware, the difference between a probe that
 * catches the defect and one that also fires on the corrected sentence.</p>
 *
 * <h2>The missing qualifier, not the phrase</h2>
 *
 * <p>A probe banning "one undo is enough" would go red on every correct rewrite, because the
 * corrected sentences still say it — conditionally. A probe requiring the word "applied" would have
 * been green before the fix, because two of these five sites already said "the applied result is
 * worse". What separates them is a genuine <em>conditional</em>: "once the run has been applied"
 * conditions the remedy, "the applied result" asserts the state. Only the first counts here.</p>
 *
 * <h2>Scoped to the sentence, not the file</h2>
 *
 * <p>Each claim is checked inside the sentence that makes it. A whole-file scan passes on a file
 * whose qualifier lives three sections away from the claim it was meant to qualify, which is how a
 * sibling guard in this repo once stayed green through the deletion it existed to catch. Each
 * surface must also still <em>carry</em> a claim: a surface that stops matching has been reworded,
 * and this reports the lost anchor rather than quietly checking an empty region.</p>
 *
 * <p>The two served surfaces are read off the live registration, description <em>and</em>
 * input-schema property descriptions — a property description is served text an agent reads exactly
 * like the tool description, and a guard scoped to descriptions alone misses it. {@code CHANGELOG.md}
 * is deliberately out of scope: it is an accurate record of what shipped at the time.</p>
 */
public class AutoLayoutUndoRemedyScopeTest {

    private static final String TOOL = "auto-layout-and-route";

    private static final String CHECKLIST =
            "net.vheerden.archi.mcp/resources/prompts/routing-preconditions-checklist.md";
    private static final String VIEW_PATTERNS =
            "net.vheerden.archi.mcp/resources/reference/archimate-view-patterns.md";
    private static final String WARNING_CODES =
            "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/response/dto/"
                    + "StructuredWarningCodes.java";

    /**
     * A sentence saying one call of {@code undo} is the recovery.
     *
     * <p>Wording around these stays free; what is pinned is that a sentence making the claim in one
     * of these shapes carries the qualifier below.</p>
     */
    private static final List<String> ONE_UNDO_CLAIMS = List.of(
            "one undo is enough",
            "undo restores it in one call",
            "recovery is a single call",
            "undo restores the previous state in a single call");

    /**
     * Any one of these turns the claim from an assertion about state into a condition on it.
     *
     * <p>Deliberately loose about what sits between the conditional word and "applied" — "once
     * applied", "once the run has been applied", "after it has been applied", "when the run has
     * been applied" all pass — and loose about their ORDER, so "applied only once the batch
     * commits" passes too. A guard that failed a correct rewrite would be a tax on every future
     * edit of these sentences, and the thing being pinned is the presence of a condition, not a
     * house style for expressing it. It still refuses to cross a sentence-internal break, so a
     * qualifier belonging to a different clause cannot be borrowed.</p>
     *
     * <p>The two literals are the other honest form: naming the arms on which there is nothing to
     * undo at all.</p>
     */
    private static final Pattern ONCE_APPLIED = Pattern.compile(
            "(?:\\b(?:once|after|when)\\b[^.;]{0,40}\\bapplied\\b)"
                    + "|(?:\\bapplied\\b[^.;]{0,40}\\b(?:once|after|when)\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final List<String> QUALIFIER_LITERALS = List.of(
            "nothing to undo",
            "nothing has been applied");

    @Test
    public void everyPreCallSurfaceMustScopeItsOneUndoRemedyToAnAppliedRun() {
        Map<String, String> surfaces = new LinkedHashMap<>();
        surfaces.put("the served " + TOOL + " tool description", servedDescription());
        surfaces.put("the served targetRating property description", servedTargetRatingProperty());
        surfaces.put(CHECKLIST, claimBearingLines(read(CHECKLIST)));
        surfaces.put(VIEW_PATTERNS, claimBearingLines(read(VIEW_PATTERNS)));
        surfaces.put(WARNING_CODES + " (AUTO_LAYOUT_RATING_REGRESSED javadoc)",
                warningCodeJavadoc());

        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String> surface : surfaces.entrySet()) {
            failures.addAll(unqualifiedClaimsIn(surface.getKey(), surface.getValue()));
        }

        if (!failures.isEmpty()) {
            fail("An " + TOOL + " surface prescribes one-undo recovery without saying the run has "
                    + "to have been applied first. These surfaces are read BEFORE the call, when "
                    + "the arm is not yet known: a queued run is discarded with end-batch "
                    + "rollback:true and an awaiting-approval run is rejected, and on both of them "
                    + "undo reverts somebody else's command. Add the conditional; do not delete "
                    + "the claim, which is the common path's most useful sentence.\n  "
                    + String.join("\n  ", failures));
        }
    }

    /**
     * Completeness, as a census rather than a list.
     *
     * <p>The test above enumerates five surfaces and states the RULE for them. An enumeration
     * cannot see a sixth: a new tool description, a new prompt resource, a claim moved to a file
     * nobody re-pointed the anchors at. The acceptance criterion this guard serves is written as a
     * grep over {@code src} and {@code resources}, not as a list, and this is that grep.</p>
     *
     * <p>Every one-undo claim anywhere in the shipped source and resources must fall into exactly
     * one of three categories, and anything else fails the build so a human has to classify it:</p>
     *
     * <ul>
     *   <li><strong>Scoped</strong> — carries the applied-conditional. The pre-call surfaces.</li>
     *   <li><strong>Arm-specific</strong> — asserts the state was applied ("were applied anyway"),
     *       which is only ever emitted on the arm where that is true. Correctly unconditional.</li>
     *   <li><strong>A different family</strong> — the spacing tools carry their own disclosure with
     *       their own remedy and their own queued-arm abstention. Not this story's to police.</li>
     * </ul>
     *
     * <p>This is the split the repo already uses for its other build-fired registries: the rule is
     * enumerated, and completeness is a census that refuses to let a new item go unclassified.</p>
     */
    @Test
    public void noUnclassifiedOneUndoClaimMayExistAnywhereInSourceOrResources() {
        List<String> failures = new ArrayList<>();
        for (Path file : shippedTextFiles()) {
            List<String> sentences = sentences(readAt(file));
            for (int i = 0; i < sentences.size(); i++) {
                String claim = sentences.get(i).toLowerCase(Locale.ROOT);
                if (ONE_UNDO_CLAIMS.stream().noneMatch(claim::contains)) {
                    continue;
                }
                // Classification reads the sentence BEFORE the claim as well. The applied-arm
                // constant puts its licence in its own preceding sentence — "The new layout and
                // routes were applied anyway. Undo restores the previous state, and one undo is
                // enough…" — and that sentence does govern the claim after it. The enumerated rule
                // above stays strictly per-sentence; only this completeness pass, which is asking
                // which CATEGORY a site belongs to rather than whether one sentence is well
                // formed, gets the wider window.
                // ONLY the applied-assertion gets the wider window, and this is load-bearing: a
                // first cut widened all three, and a claim whose PRECEDING sentence merely happened
                // to mention spacing was then exempted as "another family". That let an injected
                // unconditional surface pass — the census certified nothing until this was split.
                String appliedWindow =
                        (i > 0 ? sentences.get(i - 1).toLowerCase(Locale.ROOT) + " " : "") + claim;
                if (isSpacingFamily(claim) || assertsItWasApplied(appliedWindow)
                        || isScoped(claim)) {
                    continue;
                }
                failures.add(file + "\n      " + sentences.get(i).trim());
            }
        }

        if (!failures.isEmpty()) {
            fail("A one-undo recovery claim exists that is neither scoped to an applied run, nor "
                    + "an arm-specific constant asserting the state WAS applied, nor part of the "
                    + "spacing family. If it is a new pre-call surface, add the applied-conditional "
                    + "and point the enumerated test above at it. If it is a new arm-specific "
                    + "constant, it must say the state was applied. Do not widen this census to "
                    + "make it pass.\n  " + String.join("\n  ", failures));
        }
    }

    /**
     * The now-dead sibling must stay dead.
     *
     * <p>{@code putRatingDisclosure} writes the arm-blind message, applied tail and all, onto an
     * approval card. Both quality loops were moved off it onto
     * {@code putProposedRatingDisclosure}, leaving it reachable only from tests — and a method that
     * still compiles, still works, and still does the wrong thing is exactly what a later
     * refactor reaches for. Rewiring a card onto it would put "the layout and routes were applied
     * anyway" back in front of a human who has not approved anything, and no probe above would
     * notice, because the surfaces they scan would all still be correct.</p>
     *
     * <p>It is not deleted because the spacing family's own approval-card test drives it directly
     * and that test is required to stay green, so removing it would mean editing a test this
     * change is explicitly forbidden from touching.</p>
     */
    @Test
    public void theArmBlindCardWriterMustHaveNoProductionCallers() {
        List<String> callers = new ArrayList<>();
        for (Path file : shippedTextFiles()) {
            if (!file.toString().endsWith(".java")) {
                continue;
            }
            String text = readAt(file);
            for (String line : text.split("\n", -1)) {
                if (line.contains("putRatingDisclosure(")
                        && !line.contains("public static void putRatingDisclosure")) {
                    callers.add(file + " -> " + line.trim());
                }
            }
        }

        assertTrue("putRatingDisclosure writes the arm-blind applied tail onto an approval card. "
                        + "It has no production callers and must keep none — use "
                        + "putProposedRatingDisclosure, which rescopes the remedy to an arm where "
                        + "nothing has been applied yet. Found: " + callers,
                callers.isEmpty());
    }

    private static boolean isSpacingFamily(String lower) {
        return lower.contains("spacing");
    }

    /** An arm-specific constant states outright that the mutation landed; that is its licence. */
    private static boolean assertsItWasApplied(String lower) {
        return lower.contains("were applied anyway") || lower.contains("was applied anyway");
    }

    private static boolean isScoped(String lower) {
        return ONCE_APPLIED.matcher(lower).find()
                || QUALIFIER_LITERALS.stream().anyMatch(lower::contains);
    }

    /** Every shipped Java source and markdown resource — the surface an agent or reader can reach. */
    private static List<Path> shippedTextFiles() {
        List<Path> roots = List.of(
                locate("net.vheerden.archi.mcp/src"),
                locate("net.vheerden.archi.mcp/resources"));
        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(f -> f.toString().endsWith(".java") || f.toString().endsWith(".md"))
                        .forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to walk " + root, e);
            }
        }
        assertTrue("the census must actually find the shipped tree, or it certifies nothing: "
                + files.size(), files.size() > 50);
        return files;
    }

    // ---- the check ----------------------------------------------------------------------------

    /**
     * Every sentence of {@code text} that claims one-undo recovery without qualifying it.
     *
     * <p>Also fails when the surface makes no claim at all: the anchor has been reworded and the
     * check would otherwise pass having examined nothing.</p>
     */
    private static List<String> unqualifiedClaimsIn(String surface, String text) {
        List<String> failures = new ArrayList<>();
        int claims = 0;
        for (String sentence : sentences(text)) {
            String lower = sentence.toLowerCase(Locale.ROOT);
            if (ONE_UNDO_CLAIMS.stream().noneMatch(lower::contains)) {
                continue;
            }
            claims++;
            boolean qualified = ONCE_APPLIED.matcher(lower).find()
                    || QUALIFIER_LITERALS.stream().anyMatch(lower::contains);
            if (!qualified) {
                failures.add(surface + " states the remedy unconditionally:\n      "
                        + sentence.trim());
            }
        }
        if (claims == 0) {
            failures.add(surface + " no longer states the one-undo remedy in any recognised shape. "
                    + "Either it was deleted — the remedy is genuinely useful on the immediate arm "
                    + "and should not be — or it was reworded and this anchor must be re-pointed "
                    + "at the new wording. It must not silently check nothing.");
        }
        return failures;
    }

    /**
     * Whitespace-collapsed sentences, bounded by a full stop followed by a space.
     *
     * <p>A bare ". " split would cut "e.g. after a compound dispatch" and "v1.9" in half, stranding
     * a qualifier in one fragment and the claim it governs in the next — failing a sentence that is
     * in fact correctly conditioned. So a stop is not a boundary when it follows a digit (version
     * numbers, decimals) or one of the abbreviations these documents actually use.</p>
     *
     * <p>Erring long is the safe direction here in a way erring short is not: a window wider than
     * the real sentence can only let a qualifier from a neighbouring clause satisfy the check,
     * which this guard already tolerates by design, whereas a window narrower than the sentence
     * fails correct text outright.</p>
     */
    private static List<String> sentences(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i + 1 < flat.length(); i++) {
            if (flat.charAt(i) == '.' && flat.charAt(i + 1) == ' ' && isSentenceEnd(flat, i)) {
                out.add(flat.substring(start, i + 1));
                start = i + 2;
            }
        }
        out.add(flat.substring(start));
        return out;
    }

    /** Abbreviations and decimals whose full stop does not end a sentence. */
    private static final List<String> NOT_SENTENCE_ENDS =
            List.of("e.g", "i.e", "etc", "cf", "vs", "no", "fig");

    private static boolean isSentenceEnd(String flat, int stopAt) {
        if (stopAt > 0 && Character.isDigit(flat.charAt(stopAt - 1))) {
            return false;
        }
        String head = flat.substring(0, stopAt).toLowerCase(Locale.ROOT);
        return NOT_SENTENCE_ENDS.stream().noneMatch(head::endsWith);
    }

    // ---- surfaces -----------------------------------------------------------------------------

    private static String servedDescription() {
        return servedTool().description();
    }

    /**
     * The {@code targetRating} property description.
     *
     * <p>Served text, read by the same agent as the tool description and carrying the same claim —
     * so a guard scoped to tool descriptions alone would leave this one unscoped.</p>
     */
    @SuppressWarnings("unchecked")
    private static String servedTargetRatingProperty() {
        Object property = servedTool().inputSchema().properties().get("targetRating");
        if (!(property instanceof Map)) {
            throw new AssertionError(TOOL + " no longer serves a targetRating property; re-point "
                    + "this anchor at whatever now carries the quality-target guidance.");
        }
        return String.valueOf(((Map<String, Object>) property).get("description"));
    }

    private static McpSchema.Tool servedTool() {
        CommandRegistry registry = new CommandRegistry();
        SessionManager sessions =
                new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        HandlerRegistrar.registerAll(
                new BaseTestAccessor(), new ResponseFormatter(), registry, sessions);
        return registry.getToolSpecifications().stream()
                .filter(spec -> TOOL.equals(spec.tool().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not registered: " + TOOL))
                .tool();
    }

    /**
     * The markdown PARAGRAPHS that mention the tool and claim the remedy.
     *
     * <p>Narrowed to those paragraphs rather than scanned whole-file: both documents discuss undo
     * in other contexts, and a file-wide scan would answer a different question from the one
     * asked.</p>
     *
     * <p>Paragraph-scoped rather than line-scoped because these bullets are currently single
     * physical lines of 400+ characters. Hard-wrapping one — a purely cosmetic edit that changes
     * no meaning — would, under a line filter, drop every continuation line and take the qualifier
     * or the claim with it, failing the build for a re-wrap. A paragraph survives re-wrapping,
     * which is the unit the prose actually lives in.</p>
     */
    private static String claimBearingLines(String markdown) {
        StringBuilder relevant = new StringBuilder();
        for (String paragraph : markdown.split("\n\\s*\n")) {
            if (paragraph.contains(TOOL) || paragraph.contains("AUTO_LAYOUT_RATING_REGRESSED")) {
                relevant.append(paragraph).append("\n\n");
            }
        }
        return relevant.toString();
    }

    /** The javadoc block owning the published warning code, anchored on the declaration itself. */
    private static String warningCodeJavadoc() {
        String source = read(WARNING_CODES);
        String declaration =
                "public static final String AUTO_LAYOUT_RATING_REGRESSED =";
        int declarationAt = source.indexOf(declaration);
        if (declarationAt < 0) {
            throw new AssertionError("AUTO_LAYOUT_RATING_REGRESSED is no longer declared in "
                    + WARNING_CODES + "; the code is published and must not be renamed.");
        }
        int javadocAt = source.lastIndexOf("/**", declarationAt);
        return source.substring(javadocAt, declarationAt)
                .replaceAll("(?m)^\\s*\\*/?", " ")
                .replaceAll("<[^>]+>", " ");
    }

    // ---- io -----------------------------------------------------------------------------------

    private static String read(String relative) {
        return readAt(locate(relative));
    }

    private static String readAt(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
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
