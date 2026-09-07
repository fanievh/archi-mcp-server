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
 * Build-fired parity guard for the newest reason inside the published
 * {@code structural_no_change_<reason>} branch: a view whose corridor lies between containers the
 * declining tool's own positioning step does not move.
 *
 * <p><strong>Why a guard at all.</strong> Five surfaces enumerate that branch's reasons in prose —
 * three served tool descriptions, the agent-facing checklist resource, and the layout-engine
 * reference. Adding a reason does not break any of them; it simply leaves five lists that are each
 * silently one short. <em>A presence grep cannot find a list that omits a name</em>, which is why
 * the denominator here is the list of surfaces rather than the list of reasons: a surface that
 * stops enumerating fails loudly at its anchor rather than passing vacuously.</p>
 *
 * <p><strong>Why the enumerating region, not the file.</strong> Both markdown files discuss
 * containers drawn inside a host in several places — the depth carve-out paragraph, the
 * arrange-groups section, the container table — and every one of those mentions the same words this
 * guard looks for. A file-wide {@code contains} would therefore pass on a reference hundreds of
 * lines from the taxonomy table, and would have passed before this reason was added to that table
 * at all. Each surface is narrowed to the single line or paragraph that enumerates, from an anchor
 * that must match exactly once.</p>
 *
 * <p><strong>The count is unchanged and is asserted so.</strong> This is a new reason inside branch
 * (d), not an eleventh branch, so the taxonomy still has ten of them —
 * {@code SpacingTerminationReasonDocSyncTest} owns that assertion and this guard must not be read
 * as widening the taxonomy.</p>
 */
public class NotPositionedReasonSurfaceParityTest {

    /** The published reference table's home. Repo-relative; not on the classpath. */
    private static final String LAYOUT_ENGINE = "docs/layout-engine.md";

    /** The agent-facing checklist, served to clients as an MCP resource. */
    private static final String CHECKLIST_RESOURCE =
            "resources/prompts/routing-preconditions-checklist.md";

    /** The handler source carrying the three served tool descriptions. */
    private static final String HANDLER_SOURCE =
            "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/handlers/ViewPlacementHandler.java";

    /**
     * The taxonomy row both markdown surfaces carry, verbatim in each. Anchoring on the row's own
     * leading cell rather than on any prose around it is what keeps the two from drifting: the row
     * is the same sentence in two places and a guard that anchored differently in each could not
     * see them diverge.
     */
    private static final String TAXONOMY_ROW_ANCHOR = "| `structural_no_change_<reason>` |";

    /**
     * The words a reader must be able to find in that row, and in each served description's (d)
     * clause. Checked as a phrase rather than as the machine code because the machine code is not
     * what these surfaces publish — they publish the {@code <reason>} placeholder and describe in
     * words which short-circuits fill it.
     */
    private static final List<String> OWED_PHRASES =
            List.of("drawn inside a host", "arrange-groups");

    // ---- the two markdown surfaces --------------------------------------------------------------

    @Test
    public void shouldNameTheNewReasonInTheTaxonomyRow_whenTheChecklistResourceIsRead() {
        assertRowNamesTheReason(CHECKLIST_RESOURCE, readClasspathResource(CHECKLIST_RESOURCE));
    }

    @Test
    public void shouldNameTheNewReasonInTheTaxonomyRow_whenTheLayoutReferenceIsRead() {
        assertRowNamesTheReason(LAYOUT_ENGINE, readRepoFile(LAYOUT_ENGINE));
    }

    /**
     * The two markdown rows are one sentence written twice and must not drift. Compared after the
     * leading cell, so a difference in surrounding table formatting is not read as a difference in
     * what was published.
     */
    @Test
    public void shouldPublishTheSameRow_whenBothMarkdownSurfacesAreRead() {
        String checklist = rowOf(CHECKLIST_RESOURCE, readClasspathResource(CHECKLIST_RESOURCE));
        String reference = rowOf(LAYOUT_ENGINE, readRepoFile(LAYOUT_ENGINE));
        assertTrue("The " + TAXONOMY_ROW_ANCHOR + " row differs between " + CHECKLIST_RESOURCE
                        + " and " + LAYOUT_ENGINE + ". They are the same sentence in two places and "
                        + "an agent may read either.\n  served:    " + checklist
                        + "\n  reference: " + reference,
                checklist.equals(reference));
    }

    // ---- the three served descriptions ----------------------------------------------------------

    // The three served (d) clauses are guarded where the served string can actually be read —
    // ViewPlacementHandlerTest.spacingTools_structuralBranchShouldNameTheNotPositionedReason,
    // which resolves each tool's registered description rather than its source.
    //
    // They are deliberately NOT checked here. This class reads files from disk, and the handler
    // source holds those descriptions as concatenated string literals: a phrase that straddles two
    // of them is present in what the agent reads and absent from every individual line, so a
    // source-text scan would report a complete clause as missing — or, worse, pass on a clause the
    // compiler never assembles that way. Source and served are different surfaces and only one of
    // them is published.

    /**
     * The description clause must not be confused with the branch COUNT. This reason lives inside
     * branch (d); adding it did not make an eleventh branch, and a surface that says otherwise is
     * as wrong as one that omits the reason.
     */
    @Test
    public void shouldLeaveTheBranchCountAtTen_whenTheServedDescriptionsAreRead() {
        String source = readRepoFile(HANDLER_SOURCE);
        assertFalse("A served description now claims eleven termination branches. This reason is a "
                        + "new entry inside branch (d), not a new branch.",
                source.contains("eleven branches") || source.contains("ONE of eleven"));
        assertTrue("The served descriptions no longer state the branch count at all.",
                source.contains("ten branches"));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static void assertRowNamesTheReason(String locator, String text) {
        String row = rowOf(locator, text);
        List<String> silent = new ArrayList<>();
        for (String phrase : OWED_PHRASES) {
            if (!row.contains(phrase)) {
                silent.add(phrase);
            }
        }
        if (!silent.isEmpty()) {
            fail("The " + TAXONOMY_ROW_ANCHOR + " row in " + locator + " does not name "
                    + String.join(" or ", silent) + ".\n  row: " + row
                    + "\n\nThe row enumerates which short-circuits fill the <reason> placeholder. "
                    + "Add the reason to the row — do not satisfy this guard by mentioning it "
                    + "elsewhere in the file, which is the exact failure the row scoping exists to "
                    + "catch.");
        }
    }

    /** The single taxonomy row, or a loud failure if the anchor stopped identifying exactly one. */
    private static String rowOf(String locator, String text) {
        List<String> matches = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (line.contains(TAXONOMY_ROW_ANCHOR)) {
                matches.add(line.trim());
            }
        }
        if (matches.size() != 1) {
            throw new AssertionError("Anchor '" + TAXONOMY_ROW_ANCHOR + "' matches "
                    + matches.size() + " lines in " + locator + "; it must identify exactly one "
                    + "row. Zero means the taxonomy table moved or was reworded — or, for the "
                    + "served resource, that this run resolved a stale staged copy under build/ "
                    + "rather than the source tree. Re-anchor here; do not widen to the whole "
                    + "file, which mentions these words in unrelated sections.");
        }
        return matches.get(0);
    }

    /**
     * Every {@code (d) structural_no_change_<reason> …} clause in the handler source, each read to
     * the end of its parenthetical.
     */
    private static List<String> structuralNoChangeClausesIn(String source) {
        List<String> clauses = new ArrayList<>();
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].contains("(d) structural_no_change_<reason>")) {
                continue;
            }
            StringBuilder clause = new StringBuilder();
            for (int j = i; j < lines.length && j < i + 12; j++) {
                clause.append(lines[j].trim()).append(' ');
                if (lines[j].contains("(e) heuristic_already_met_no_change")) {
                    break;
                }
            }
            clauses.add(clause.toString());
        }
        return clauses;
    }

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

    private static String readClasspathResource(String resourcePath) {
        try (InputStream is = NotPositionedReasonSurfaceParityTest.class.getClassLoader()
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
