package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Build-fired parity guard for the published "what counts as a container" vocabulary.
 *
 * <p><strong>The problem.</strong> Archi renders two objects as a labelled box holding others — a
 * native view group and an ArchiMate {@code Grouping} element — and seven predicates in this
 * codebase decide which of them counts as a container. All six that differ from the canonical one
 * differ deliberately, each answering a different question. A census measured them from source; the
 * answer is published as a table in {@code docs/layout-engine.md}, and the one tool that does
 * <em>not</em> honour the arrangement family's parity is called out in the agent-facing checklist
 * that recommends it.</p>
 *
 * <p>The census found a seventh answer that was <em>not</em> deliberate:
 * {@code resize-elements-to-fit} treated the two kinds oppositely, shrinking an ArchiMate
 * {@code Grouping} and collapsing an empty one while never collecting a native view group at all.
 * That was fixed rather than documented — a {@code Grouping} is now grown and never shrunk — which
 * is why the checklist carve-out below names one diverging tool where it used to name two, and why
 * the retraction of the old claim is pinned here per surface rather than merely deleted.</p>
 *
 * <p><strong>What this enforces, in both directions.</strong> The published table must name every
 * predicate the census found, and must name <em>none</em> of the sites the census deliberately
 * ruled out. The second direction is not symmetry for its own sake: the excluded sites ask a
 * <em>type</em> question ("does this object carry a group's border API") or a
 * <em>parent-eligibility</em> question ("what may be a parent"), not a zone question. Adding one to
 * this table would read as completing the census while actually restating the confusion the table
 * exists to end — and a subset check cannot see it.</p>
 *
 * <p><strong>Why the region, not the file.</strong> {@code docs/layout-engine.md} names
 * {@code StylingHelper} roughly a hundred lines above the table, in the connection-label
 * reservation section, for entirely unrelated reasons. (Measured 2026-08-29: line 40 against a
 * table anchored at line 145. The distance is incidental and will drift — what matters is that the
 * mention is outside the table, not how far outside.) A file-scoped guard would therefore report
 * that the table "claims" a styling site it never mentions — or, run the other way, would pass on a table
 * that had lost a row while the file still discussed the symbol elsewhere. Each surface is narrowed
 * to its own enumerating block from an anchor that must match <em>exactly once</em>, and an anchor
 * that stops matching fails loudly rather than silently checking an empty region.</p>
 *
 * <p><strong>Why the denominator lives here rather than in a committed map.</strong> The
 * structured-warning guard commits its code&nbsp;&rarr;&nbsp;tool split because ownership could not
 * be derived from the constants. Here each row's owner is a symbol, so the denominator is checked
 * against the production source directly by
 * {@link #shouldResolveEveryNamedSymbol_whenTheProductionSourceIsRead()} — a renamed predicate
 * fails there rather than leaving the table quietly naming something that no longer exists.</p>
 */
public class ContainerDefinitionSurfaceParityTest {

    /** The published table's home. Repo-relative; not on the classpath. */
    private static final String TABLE_FILE = "docs/layout-engine.md";

    /** The agent-facing checklist, served to clients as an MCP resource. */
    private static final String CHECKLIST_RESOURCE =
            "resources/prompts/routing-preconditions-checklist.md";

    /** The anchor for the published table. Must match exactly one line. */
    private static final String TABLE_ANCHOR =
            "| Predicate (the symbol that owns it) | Admits | Used by | Why it differs |";

    /** The anchor for the checklist's divergence carve-out. Must match exactly one line. */
    private static final String CHECKLIST_ANCHOR =
            "> **One tool this checklist names does NOT treat the two kinds alike.";

    /**
     * One container-ness predicate the census found, and the production file that owns it.
     *
     * @param symbol the method or class name the published table must name
     * @param file   the repo-relative production file the symbol must still exist in
     */
    private record Definition(String symbol, String file) { }

    private static final String MODEL = "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/";
    private static final String RESPONSE =
            "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/response/";

    /**
     * Every predicate the census measured. Seven answers, six distinct definitions plus the
     * canonical one they are stated against.
     */
    private static final List<Definition> OWED = List.of(
            // The canonical model-layer predicate.
            new Definition("isTarget", MODEL + "TopLevelGroupTargets.java"),
            // The deliberate second copy above the model layer (handlers may not import EMF).
            new Definition("isContainerType", RESPONSE + "ViewContainers.java"),
            // DTO shaping and sort keys: a Grouping is bucketed among the elements, by design.
            new Definition("collectViewContents", MODEL + "ArchiModelAccessorImpl.java"),
            new Definition("getFlatViewSortKey", MODEL + "ArchiModelAccessorImpl.java"),
            new Definition("getFlatViewCategoryValue", MODEL + "ArchiModelAccessorImpl.java"),
            // The upward ancestor re-fit: native groups only, at both ends. Disclosed per call.
            new Definition("propagateToAncestors", MODEL + "NestedLayoutOperations.java"),
            new Definition("resizeAncestorGroups", MODEL + "NestedLayoutOperations.java"),
            // The downward recursion and layout eligibility: wider than the canonical predicate.
            new Definition("isRecursableContainer", MODEL + "NestedLayoutOperations.java"),
            new Definition("clampInsideParent", MODEL + "NestedLayoutOperations.java"),
            new Definition("layoutWithinGroup", MODEL + "ArchiModelAccessorImpl.java"),
            // The groupIds per-call opt-in: a deliberate override of the canonical predicate.
            new Definition("resolveRequested", MODEL + "TopLevelGroupTargets.java"),
            // resize-elements-to-fit's target walk, and the predicate that decides what happens
            // to each thing it collects: collection is wide, treatment is not.
            new Definition("collectElementViewObjects", MODEL + "TopLevelGroupTargets.java"),
            new Definition("isGroupingZone", MODEL + "TopLevelGroupTargets.java"),
            // The outermost-container walk: the canonical predicate asked downward, and the
            // definition of "top-level" every route now shares. Its upward twin resolves an
            // object to a member of exactly this collection.
            new Definition("collectOutermost", MODEL + "TopLevelGroupTargets.java"),
            new Definition("containerOf", MODEL + "TopLevelGroupTargets.java"));

    /**
     * Sites the census read and deliberately ruled out, which must NOT appear in the table.
     *
     * <p>Each asks a question that is not "is this a zone". {@code StylingHelper} and
     * {@code RecedeContainerFillCommand} ask whether an object carries a group's border/fill API;
     * {@code SetViewLabelExpressionCommand} asks the same question in the other direction — its
     * {@code matches} branches on the two types only to honour the caller's own
     * {@code objectTypes} filter, so the type test is the caller's vocabulary rather than this
     * codebase's; {@code MutationContext} asks what may be a <em>parent</em>, a containment
     * question the census put explicitly out of scope; {@code labelHeightFor} asks how tall a
     * title band is, which differs by kind without disagreeing about what a container is.</p>
     */
    private static final List<String> FOREIGN = List.of(
            "StylingHelper",
            "RecedeContainerFillCommand",
            "SetViewLabelExpressionCommand",
            "MutationContext",
            "labelHeightFor");

    /**
     * Lower bound on the number of named <em>sites</em> — which is not the number of definitions.
     *
     * <p><strong>Read the frame.</strong> {@link #OWED} holds <b>13 sites</b> covering the
     * <b>7 definitions</b> the published table presents. The DTO-shaping row alone owns three of
     * them ({@code collectViewContents}, {@code getFlatViewSortKey},
     * {@code getFlatViewCategoryValue}), which are three call sites sharing <em>one</em> dispatch
     * rather than three independently-derived predicates; the upward and downward rows own two
     * each; and the resize row owns the walk plus {@code isGroupingZone}, which is the ArchiMate
     * half of the canonical predicate rather than an eighth answer. Do not read 13 as a count of
     * definitions, here or anywhere the number is quoted.</p>
     *
     * <p>A FLOOR, never an equality: a fourteenth site must not break this guard merely by
     * existing. Its reach is narrow and worth stating rather than overselling — the constant and
     * the list it measures live in <em>this</em> file, so it only catches a list gutted by a
     * careless edit here, and an edit that lowered the floor in the same pass would defeat it. The
     * pawl that is <em>not</em> self-referential is
     * {@link #shouldResolveEveryNamedSymbol_whenTheProductionSourceIsRead()}, which measures all
     * thirteen against the real production source.</p>
     */
    private static final int OWED_FLOOR = 14;

    // ---- The pawl -------------------------------------------------------------------------------

    @Test
    public void shouldKeepTheDenominatorAtItsFloor_whenTheCensusListIsRead() {
        assertTrue("The container-predicate census should hold at least " + OWED_FLOOR
                        + " entries; it holds " + OWED.size()
                        + ". A gutted denominator leaves every parity assertion below asserting "
                        + "nothing at all.",
                OWED.size() >= OWED_FLOOR);
        assertFalse("The excluded-site list is empty, so the foreign-entry direction would check "
                        + "nothing. A subset-only guard cannot see a definition added to the wrong "
                        + "surface.",
                FOREIGN.isEmpty());
    }

    @Test
    public void shouldResolveEveryNamedSymbol_whenTheProductionSourceIsRead() {
        List<String> missing = new ArrayList<>();
        for (Definition definition : OWED) {
            if (!namesSymbol(readRepoFile(definition.file()), definition.symbol())) {
                missing.add(definition.symbol() + " not found in " + definition.file());
            }
        }
        if (!missing.isEmpty()) {
            fail("The published container table names symbol(s) that no longer exist in the "
                    + "production source:\n  " + String.join("\n  ", missing)
                    + "\n\nA table naming a dead symbol sends a reader looking for a predicate that "
                    + "was renamed or removed. Re-measure the census from source and update BOTH "
                    + "this list and " + TABLE_FILE + " — do not delete the row to make this pass.");
        }
    }

    // ---- Direction one: everything owed is published ---------------------------------------------

    @Test
    public void shouldNameEveryContainerPredicate_whenThePublishedTableIsRead() {
        String table = narrowToBlock(TABLE_FILE, readRepoFile(TABLE_FILE), TABLE_ANCHOR);
        List<String> omitted = new ArrayList<>();
        for (Definition definition : OWED) {
            if (!namesSymbol(table, definition.symbol())) {
                omitted.add(definition.symbol());
            }
        }
        if (!omitted.isEmpty()) {
            fail("The container vocabulary table in " + TABLE_FILE + " OMITS predicate(s): "
                    + omitted + ". That table presents itself as THE list of the questions this "
                    + "codebase asks about what a container is, so an omission tells a reader the "
                    + "predicate does not exist — which is how one tool's answer comes to be read "
                    + "as another's. Add the row in the table's own voice: what it admits, which "
                    + "tools use it, and why it differs from the canonical predicate.");
        }
    }

    // ---- Direction two: nothing foreign is published ---------------------------------------------

    @Test
    public void shouldNotClaimAnExcludedSite_whenThePublishedTableIsRead() {
        String table = narrowToBlock(TABLE_FILE, readRepoFile(TABLE_FILE), TABLE_ANCHOR);
        List<String> claimed = new ArrayList<>();
        for (String foreign : FOREIGN) {
            if (namesSymbol(table, foreign)) {
                claimed.add(foreign);
            }
        }
        if (!claimed.isEmpty()) {
            fail("The container vocabulary table in " + TABLE_FILE + " CLAIMS site(s) the census "
                    + "deliberately ruled out: " + claimed + ". These ask a type question ('does "
                    + "this object carry a group's border API') or a parent-eligibility question "
                    + "('what may be a parent'), not a zone question. Listing one as a container "
                    + "definition reads as completing the census while restating the confusion the "
                    + "table exists to end. If a site genuinely belongs, re-measure it and move it "
                    + "out of the excluded list here in the same change.");
        }
    }

    // ---- The agent-facing surface ----------------------------------------------------------------

    /**
     * The carve-out named two diverging tools; {@code resize-elements-to-fit} stopped being one of
     * them when it started treating a {@code Grouping} as a zone. The token stays owed, because the
     * correction is the load-bearing half now: an agent that read the old carve-out was told to
     * scope this tool away from zones, and only this paragraph unteaches that. With it go the two
     * response fields that let the agent observe what the tool did instead of taking the prose on
     * trust — {@code skippedContainers} for a zone it declined to size, {@code resizedGroups} for
     * the native group the cascade grew.
     */
    @Test
    public void shouldWarnAboutTheDivergingTool_whenTheServedChecklistIsRead() {
        String carveOut = narrowToBlock(CHECKLIST_RESOURCE, readClasspathResource(CHECKLIST_RESOURCE),
                CHECKLIST_ANCHOR);
        List<String> owed = List.of(
                "layout-within-group",
                "ancestorPropagation",
                "container-not-a-native-group",
                "stopped-at-non-native-ancestor",
                "resize-elements-to-fit",
                "skippedContainers",
                "resizedGroups");
        List<String> omitted = new ArrayList<>();
        for (String token : owed) {
            if (!namesSymbol(carveOut, token)) {
                omitted.add(token);
            }
        }
        if (!omitted.isEmpty()) {
            fail("The container carve-out in the served checklist OMITS: " + omitted
                    + ". This resource goes to an agent that cannot see the canvas, and the "
                    + "paragraph above the carve-out states parity for the arrangement family. The "
                    + "tool that still diverges must be named there, the tool that no longer does "
                    + "must have its correction stated rather than merely deleted, and the "
                    + "disclosure fields an agent reads to find out what each one actually did must "
                    + "be named with them — otherwise the agent is told a difference exists and not "
                    + "how to observe it.");
        }
    }

    /**
     * Every surface this change re-scoped, with the exact sentence it must never say again.
     *
     * <p>One surface per corrected claim, because the changelog says four were corrected and a
     * guard that pins only one leaves the other three free to regress to the blanket wording while
     * the build stays green. Whitespace is normalised before matching so a javadoc claim wrapped
     * across two lines with a leading asterisk is caught exactly as a one-line markdown claim is —
     * that wrapping is precisely how the accessor's version of this sentence hid from a naive
     * grep.</p>
     *
     * @param locator repo-relative path, or the classpath path when {@code classpathResource}
     * @param retracted the sentence fragment the surface must no longer contain
     */
    private record Correction(String locator, boolean classpathResource, String retracted) { }

    private static final List<Correction> CORRECTIONS = List.of(
            new Correction(CHECKLIST_RESOURCE, true,
                    "every tool named in this checklist treats them alike"),
            new Correction(TABLE_FILE, false,
                    "the whole layout family treats them alike"),
            new Correction("net.vheerden.archi.mcp/resources/recipes/index.md", false,
                    "the layout tools treat them alike"),
            new Correction("net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/"
                    + "ArchiModelAccessor.java", false,
                    "the one predicate the whole layout family shares"),
            // "A native view group is never touched" is measurably false, and three surfaces said
            // it. Every target this tool resizes goes through ParentFitCascade.fitAround, which
            // acts on native view groups ONLY and grows one around a child the pass widened —
            // measured 2026-08-29, a 400x300 group came back 450x325 and was reported in
            // resizedGroups. "Never collected as a target" is the true claim; "never touched" is
            // the one that must not come back, on any of them.
            new Correction(CHECKLIST_RESOURCE, true, "not touched at all"),
            new Correction("net.vheerden.archi.mcp/resources/reference/"
                    + "archimate-view-patterns.md", false, "A native view group is never touched"),
            new Correction(TABLE_FILE, false, "the group itself is never resized"),
            new Correction("net.vheerden.archi.mcp/resources/recipes/index.md", false,
                    "leaving a native group untouched"),
            // The open question about which container counts as top-level is settled: the
            // outermost qualifying container wins, in every route. It was published in two
            // places that had to be retracted together — a retraction landing on one of them
            // leaves the codebase arguing with itself — so both are registered, and so is the
            // premise sentence, which is the one that would come back if someone re-derived the
            // old behaviour from the collector's name.
            new Correction(TABLE_FILE, false,
                    "deliberately left unreconciled"),
            new Correction(TABLE_FILE, false,
                    "Do not \"fix\" either route in isolation"),
            new Correction("net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/"
                    + "TopLevelGroupTargets.java", false,
                    "Do not \"fix\" either route in isolation"),
            new Correction("net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/"
                    + "TopLevelGroupTargets.java", false,
                    "the two routes disagree on that shape"),
            new Correction(TABLE_FILE, false,
                    "the arrange-groups counting route seeds from TopLevelGroupTargets.collect, "
                    + "which admits direct children of the view only"),
            new Correction("docs/glossary.md", false,
                    "the one open question about which container counts as top-level"));

    @Test
    public void shouldNotPromiseBlanketParity_whenEveryCorrectedSurfaceIsRead() {
        assertTrue("No corrected surfaces are registered, so this check would assert nothing.",
                CORRECTIONS.size() >= 14);
        List<String> regressed = new ArrayList<>();
        for (Correction correction : CORRECTIONS) {
            String text = correction.classpathResource()
                    ? readClasspathResource(correction.locator())
                    : readRepoFile(correction.locator());
            if (collapseWhitespace(text).contains(collapseWhitespace(correction.retracted()))) {
                regressed.add(correction.locator() + " still says \"" + correction.retracted()
                        + "\"");
            }
        }
        if (!regressed.isEmpty()) {
            fail("Surface(s) have regressed to a blanket container-parity promise:\n  "
                    + String.join("\n  ", regressed)
                    + "\n\nNone of these holds. The upward pass of layout-within-group is narrower "
                    + "than the arrangement family's predicate and its downward pass is wider, so a "
                    + "blanket parity promise is false: narrow it to the ARRANGEMENT FAMILY and name "
                    + "the exception — do not delete the promise, because the half about a "
                    + "Grouping-built view being a fully supported grouped view is true and "
                    + "load-bearing. And a native view group is never COLLECTED AS A TARGET by "
                    + "resize-elements-to-fit, which is not the same as never touched: the "
                    + "parent-fit cascade grows one around a child that pass widened and reports it "
                    + "in resizedGroups. Say what the code does — do not restate the blanket claim.");
        }
    }

    /** Collapses every whitespace run to one space so a line-wrapped javadoc claim still matches. */
    private static String collapseWhitespace(String text) {
        return text.replaceAll("[\\s*]+", " ").trim();
    }

    // ---- Reading ---------------------------------------------------------------------------------

    /**
     * Cuts {@code text} down to the block that does the enumerating.
     *
     * <p>An anchor that no longer matches, or that matches more than once, fails here rather than
     * yielding an empty or arbitrary region. That distinction is the whole guard: an empty region
     * satisfies every "contains" assertion vacuously, so a surface whose heading was reworded would
     * stop being checked at the moment it most needed checking.</p>
     */
    private static String narrowToBlock(String locator, String text, String anchor) {
        String[] lines = text.split("\n", -1);
        int start = -1;
        int matches = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(anchor)) {
                matches++;
                if (start < 0) {
                    start = i;
                }
            }
        }
        if (matches > 1) {
            throw new AssertionError("Anchor matches " + matches + " lines in " + locator + ": '"
                    + anchor + "'. It must identify ONE block. Narrow the anchor so it picks out "
                    + "the enumerating block and nothing else — do not let the scan guess which "
                    + "copy was meant.");
        }
        if (start < 0) {
            throw new AssertionError("Anchor not found in " + locator + ": '" + anchor
                    + "'. The enumerating block has moved or been reworded — or, for a served "
                    + "resource, this run resolved it against a STALE STAGED BUNDLE under build/ "
                    + "rather than the source tree; check which copy is on the classpath before "
                    + "editing anything. Re-anchor it here — do "
                    + "NOT delete the surface, and do not fall back to scanning the whole file: "
                    + locator + " names some of these symbols hundreds of lines away for unrelated "
                    + "reasons, so a whole-file scan cannot tell a complete table from a gutted "
                    + "one.");
        }
        StringBuilder block = new StringBuilder(lines[start]).append('\n');
        for (int i = start + 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                break;
            }
            block.append(lines[i]).append('\n');
        }
        return block.toString();
    }

    /**
     * True when {@code text} names {@code symbol} as a standalone token.
     *
     * <p>A bare {@code contains} is not good enough: {@code isContainerType} is a strict prefix of
     * nothing today, but {@code isTarget} sits inside {@code targetGroupIds} and
     * {@code collect} inside {@code collectPopulated}, so a table that named only the longer symbol
     * would satisfy a substring check while never naming the predicate a reader keys off.</p>
     */
    private static boolean namesSymbol(String text, String symbol) {
        int from = 0;
        while (true) {
            int at = text.indexOf(symbol, from);
            if (at < 0) {
                return false;
            }
            boolean leftClear = at == 0 || !isIdentifierChar(text.charAt(at - 1));
            int after = at + symbol.length();
            boolean rightClear = after >= text.length() || !isIdentifierChar(text.charAt(after));
            if (leftClear && rightClear) {
                return true;
            }
            from = at + 1;
        }
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-';
    }

    /**
     * Reads a repo-relative file by walking upward from the working directory.
     *
     * <p>{@code docs/**} and the production source are not on the classpath, so the classloader
     * lookup cannot see them. A runner started outside the checkout gets the explicit failure below
     * rather than a silently skipped check — the alternative failure mode for a pawl is to quietly
     * stop pawling.</p>
     */
    private static String readRepoFile(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to read " + candidate, e);
                }
            }
            dir = dir.getParent();
        }
        throw new AssertionError("Could not locate " + relative + " by walking up from "
                + Path.of("").toAbsolutePath() + ". This test must run with a working directory "
                + "inside the checkout.");
    }

    /**
     * Reads a served MCP resource through the classloader, exactly as {@code ResourceHandler} does.
     *
     * <p>If this returns null the path is usually innocent: check that the tests bundle still
     * declares {@code Fragment-Host: net.vheerden.archi.mcp} and that the production bundle's
     * {@code build.properties bin.includes} still lists {@code resources/}.</p>
     */
    private static String readClasspathResource(String resourcePath) {
        try (InputStream is = ContainerDefinitionSurfaceParityTest.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new AssertionError("Served MCP resource " + resourcePath + " is not on the "
                        + "test classpath. Check Fragment-Host on the tests bundle and "
                        + "bin.includes resources/ on net.vheerden.archi.mcp before assuming the "
                        + "path is wrong.");
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read served resource " + resourcePath, e);
        }
    }
}
