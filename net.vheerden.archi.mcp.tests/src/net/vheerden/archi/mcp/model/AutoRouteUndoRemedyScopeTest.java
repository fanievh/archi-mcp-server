package net.vheerden.archi.mcp.model;

import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.Test;

/**
 * Build-fired guard on every published surface that names {@code undo} as the recovery for
 * {@code auto-route-connections}' crossings regression.
 *
 * <p>These surfaces are read <em>before</em> the call, when no arm exists yet, so an unqualified
 * "the remediationTool is undo" is not false about the immediate arm — it is unscoped, and an agent
 * obeying it inside an open batch reverts whichever command is really on top of the stack. The
 * sibling guard for {@code auto-layout-and-route} exists for the identical reason; this covers the
 * tool that was left out of it — including the two SERVED resources, which are the ones an agent
 * actually reads and the two most easily missed, because the row that filed this named four
 * surfaces and there are six.</p>
 *
 * <p><strong>What is pinned is the qualifier, not the phrase.</strong> Banning {@code undo} would
 * go red on every correct rewrite, since the corrected sentences still say it — conditionally. A
 * block making the claim must either condition it on the re-route having been applied or name what
 * recovers a deferred one.</p>
 *
 * <p><strong>Scoped to the block, not the file.</strong> A whole-file scan passes on a file whose
 * qualifier lives three sections away from the claim it was meant to qualify. Each block is
 * addressed by a stable anchor, and an anchor that no longer matches fails here rather than
 * silently yielding an empty region to check.</p>
 */
public class AutoRouteUndoRemedyScopeTest {

    /** A published block making the undo claim, addressed by an anchor and a block shape. */
    private record Surface(String locator, String anchor, Extent extent) {}

    private enum Extent {
        /** The anchor's own line: a markdown table row, or a list item. */
        LINE,
        /** The anchor's line through to the end of its javadoc paragraph. */
        JAVADOC_PARAGRAPH
    }

    private static final String CHECKLIST =
            "net.vheerden.archi.mcp/resources/prompts/routing-preconditions-checklist.md";
    private static final String VIEW_PATTERNS =
            "net.vheerden.archi.mcp/resources/reference/archimate-view-patterns.md";
    private static final String WARNING_CODES =
            "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/response/dto/"
                    + "StructuredWarningCodes.java";
    private static final String ROUTING_DOC = "docs/routing-pipeline.md";

    private static final List<Surface> SURFACES = List.of(
            new Surface("README.md", "| `auto-route-connections` |", Extent.LINE),
            new Surface("CHANGELOG.md",
                    "**`auto-route-connections` reports a full re-route that makes a view worse.**",
                    Extent.LINE),
            new Surface(ROUTING_DOC,
                    "| `AUTO_ROUTE_CROSSINGS_REGRESSED` | A **full** re-route left",
                    Extent.LINE),
            new Surface(ROUTING_DOC,
                    "- `remediationTool` names a **recovery of this call**", Extent.LINE),
            new Surface(CHECKLIST, "- **`AUTO_ROUTE_CROSSINGS_REGRESSED`** —", Extent.LINE),
            new Surface(VIEW_PATTERNS,
                    "- **Ignoring `structuredWarnings[]` from `auto-route-connections`:**",
                    Extent.LINE),
            new Surface(WARNING_CODES,
                    "<p><strong>The remedy is {@code undo} once the re-route has been applied",
                    Extent.JAVADOC_PARAGRAPH));

    /**
     * Any one of these turns the claim from an unconditional prescription into an arm-aware one: a
     * condition on the re-route having landed, or a named recovery for an arm on which it has not.
     * Deliberately loose about wording — what is pinned is that a condition is present, not a house
     * style for expressing it.
     */
    private static final List<String> QUALIFIERS = List.of(
            "once applied",
            "once the re-route has been applied",
            "while it is queued",
            "while queued",
            "end-batch",
            "awaiting approval",
            "awaiting-approval",
            "awaits a human");

    @Test
    public void everyPublishedUndoClaimForTheCrossingsRegressionIsScopedToTheArmThatCanRunIt() {
        List<String> failures = new ArrayList<>();
        for (Surface surface : SURFACES) {
            String block = blockOf(surface, failures);
            if (block == null) {
                continue;
            }
            String lower = block.toLowerCase(Locale.ROOT);
            if (!lower.contains("undo")) {
                failures.add(surface.locator() + " [" + surface.anchor() + "]: the block no longer "
                        + "names undo at all. Either the claim moved — in which case re-point this "
                        + "anchor at where it went — or it was deleted, which loses the common "
                        + "path's most useful sentence.");
                continue;
            }
            if (QUALIFIERS.stream().noneMatch(lower::contains)) {
                failures.add(surface.locator() + " [" + surface.anchor() + "]: names undo without "
                        + "saying which arm can run it.");
            }
        }

        if (!failures.isEmpty()) {
            fail("A published surface prescribes undo for the auto-route crossings regression "
                    + "without scoping it to the arm the call took. These are read BEFORE the "
                    + "call, when the arm is not yet known: a queued re-route is discarded with "
                    + "end-batch rollback:true and an awaiting-approval one is rejected in Archi, "
                    + "and on both of those undo reverts somebody else's command. Add the "
                    + "condition; do not delete the claim.\n  " + String.join("\n  ", failures));
        }
    }

    /** The anchored block, or {@code null} after recording a lost or ambiguous anchor. */
    private static String blockOf(Surface surface, List<String> failures) {
        String[] lines = read(surface.locator()).split("\n", -1);
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(surface.anchor())) {
                hits.add(i);
            }
        }
        if (hits.isEmpty()) {
            failures.add(surface.locator() + ": anchor no longer matches: '" + surface.anchor()
                    + "'. The block has moved or been reworded, and this guard would otherwise be "
                    + "checking nothing.");
            return null;
        }
        if (hits.size() > 1) {
            // An ambiguous anchor is the same hazard as a missing one, and quieter.
            failures.add(surface.locator() + ": anchor matches " + hits.size() + " lines: '"
                    + surface.anchor() + "'. Narrow it so it identifies ONE block.");
            return null;
        }

        int start = hits.get(0);
        if (surface.extent() == Extent.LINE) {
            return lines[start];
        }
        StringBuilder paragraph = new StringBuilder(lines[start]);
        for (int i = start + 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("*/") || line.contains("<p>") || line.isEmpty()
                    || !line.startsWith("*")) {
                break;
            }
            paragraph.append(' ').append(line.substring(1).trim());
        }
        return paragraph.toString();
    }

    private static String read(String locator) {
        Path path = Path.of(locator);
        if (!Files.exists(path)) {
            path = Path.of("..", locator);
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read published surface " + locator, e);
        }
    }
}
