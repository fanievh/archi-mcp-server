package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Build-fired guard for every published surface that states WHICH dimensions carry a permanent
 * {@code partial} and which are downgraded contextually.
 *
 * <p><strong>What drifted.</strong> That classification was written down in four places at once —
 * a branch in the map builder, the registry's own comments, the served tool description and the
 * glossary — and a fifth copy lived in a test helper, which had already gone stale: it named two
 * of the three contextually-downgradable dimensions, so every clean-run test using it was blind to
 * a {@code parentLabelObscured} misfire. The classification now has a single home on
 * {@link LayoutQualityAssessor.CoverageDimension}, and this guard is what stops the published
 * prose drifting from it again.</p>
 *
 * <p><strong>Derived, never copied.</strong> Both expected sets are read off the registry at run
 * time. A guard holding its own committed list of dimension names would drift in step with the
 * surfaces it is meant to police and certify nothing.</p>
 *
 * <p><strong>Scoped to the enumerating region.</strong> Each surface is probed inside the one list
 * item, or the one paragraph, that does the enumerating — never across the whole file. A
 * whole-file scan passes on a file whose corrected sentence lives three sections away from the
 * stale one it was meant to replace, which is how a sibling guard in this repo once stayed green
 * through the very regression it existed to catch. Anchors must match exactly once and fail loudly
 * when they stop matching, so a reworded surface reports a lost anchor rather than quietly
 * checking an empty region.</p>
 *
 * <p><strong>Both directions.</strong> Each region must name every dimension in its own class AND
 * none from the other. Presence alone would pass a surface that listed all five under both
 * headings.</p>
 */
public class CoverageClassificationSurfaceParityTest {

    private static final String GLOSSARY = "docs/glossary.md";
    private static final String LAYOUT_ENGINE = "docs/layout-engine.md";

    /** Dimensions the registry marks as contextually downgradable. */
    private static List<String> contextual() {
        List<String> ids = new ArrayList<>();
        for (LayoutQualityAssessor.CoverageDimension dim
                : LayoutQualityAssessor.CoverageDimension.values()) {
            if (dim.contextualTrigger != LayoutQualityAssessor.ContextualTrigger.NONE) {
                ids.add(dim.id);
            }
        }
        return ids;
    }

    /** Dimensions that DECLARE a permanent partial — the level is theirs on every run. */
    private static List<String> permanentPartial() {
        List<String> ids = new ArrayList<>();
        for (LayoutQualityAssessor.CoverageDimension dim
                : LayoutQualityAssessor.CoverageDimension.values()) {
            if (LayoutQualityAssessor.COVERAGE_PARTIAL.equals(dim.coverage)) {
                ids.add(dim.id);
            }
        }
        return ids;
    }

    @Test
    public void theTwoClassesMustBeDisjointAndNonEmpty() {
        // Without this the two assertions below could both be vacuously satisfied by an empty set,
        // and a dimension declaring a permanent partial AND a contextual trigger would be
        // describable under either heading — which would make "names none from the other class"
        // an unmeetable requirement rather than a guard.
        List<String> contextual = contextual();
        List<String> permanent = permanentPartial();

        assertEquals("the contextual set the surfaces describe", 3, contextual.size());
        assertEquals("the permanent set the surfaces describe", 2, permanent.size());
        for (String id : contextual) {
            assertFalse("a dimension cannot be both permanent and contextual: " + id,
                    permanent.contains(id));
        }
    }

    @Test
    public void glossaryContextualRowMustNameExactlyTheContextualDimensions() {
        String region = listItem(read(GLOSSARY), "- **contextual `partial`**", GLOSSARY);

        for (String id : contextual()) {
            assertTrue("the glossary's contextual row must name " + id, region.contains(id));
        }
        for (String id : permanentPartial()) {
            assertFalse("the glossary's contextual row must not claim " + id
                    + ", which declares a PERMANENT partial", region.contains(id));
        }
        assertTrue("the row must state how many there are, and agree with the registry",
                region.contains(numberWord(contextual().size()) + " dimensions do this"));
    }

    @Test
    public void glossaryPermanentRowMustNameExactlyThePermanentlyPartialDimensions() {
        String region = listItem(read(GLOSSARY), "- **permanent `partial`**", GLOSSARY);

        for (String id : permanentPartial()) {
            assertTrue("the glossary's permanent row must name " + id, region.contains(id));
        }
        for (String id : contextual()) {
            assertFalse("the glossary's permanent row must not claim " + id
                    + ", which is downgraded contextually", region.contains(id));
        }
        assertTrue("the row must state how many there are, and agree with the registry",
                region.contains(numberWord(permanentPartial().size()) + " dimensions ship this way"));
    }

    @Test
    public void glossaryMustPublishTheDerivedListTheResponseCarries() {
        String region = listItem(read(GLOSSARY), "- **`contextualPartialDimensions`**", GLOSSARY);

        assertTrue("the published key's row must say it is derived from the coverage map",
                region.contains("derived from the `coverage` map"));
        assertTrue("...and that it excludes the permanent declarations",
                region.contains("not** every dimension reading `partial`"));
        assertTrue("...and that an empty list is a measured statement, not a dropped field",
                region.contains("always present"));
    }

    @Test
    public void layoutEngineMustExplainWhyTheNamingIsNotConfinedToTheCleanBranch() {
        // The one claim in that section a reader would act on and could not otherwise check: the
        // reason labelOverlaps cannot be disclosed from inside the no-findings branch. If the
        // implementation were ever re-confined to that branch, this sentence would become false
        // while every presence check above stayed green.
        String region = headingBlock(read(LAYOUT_ENGINE), "#### The prose reads the map");

        assertTrue("the section must name the dimension the confined design could not reach",
                region.contains("labelOverlaps"));
        assertTrue("...and the condition that makes it unreachable",
                region.contains("shortSegmentCount > 0"));
        assertTrue("the section must state the structural count the all-clear contradicted",
                region.contains("at least three non-`checked` entries"));
    }

    // ---- region helpers ---------------------------------------------------------------------

    private static String numberWord(int n) {
        switch (n) {
            case 2: return "Two";
            case 3: return "Three";
            case 4: return "Four";
            case 5: return "Five";
            default: return String.valueOf(n);
        }
    }

    /** The one markdown list item introduced by {@code anchor}, ending at the next list item. */
    private static String listItem(String text, String anchor, String file) {
        String[] lines = text.split("\n", -1);
        int start = -1;
        int matches = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith(anchor)) {
                matches++;
                if (start < 0) {
                    start = i;
                }
            }
        }
        if (matches != 1) {
            throw new AssertionError("Anchor \"" + anchor + "\" matches " + matches + " lines in "
                    + file + "; it must identify exactly one list item. An anchor that matches "
                    + "none was reworded — re-point this check rather than letting it scan an "
                    + "empty region.");
        }
        StringBuilder block = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            if (i > start && (lines[i].startsWith("- ") || lines[i].startsWith("#"))) {
                break;
            }
            block.append(lines[i]).append('\n');
        }
        return block.toString();
    }

    /** The block introduced by a markdown heading, ending at the next heading. */
    private static String headingBlock(String text, String heading) {
        String[] lines = text.split("\n", -1);
        int start = -1;
        int matches = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals(heading)) {
                matches++;
                if (start < 0) {
                    start = i;
                }
            }
        }
        if (matches != 1) {
            throw new AssertionError("Heading \"" + heading + "\" matches " + matches + " lines; "
                    + "it must identify exactly one section.");
        }
        StringBuilder block = new StringBuilder();
        for (int i = start + 1; i < lines.length; i++) {
            if (lines[i].startsWith("#")) {
                break;
            }
            block.append(lines[i]).append('\n');
        }
        return block.toString();
    }

    private static String read(String relative) {
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
}
