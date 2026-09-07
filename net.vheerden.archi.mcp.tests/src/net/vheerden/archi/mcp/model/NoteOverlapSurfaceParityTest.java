package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * Build-fired guard for the published surfaces of {@code noteOverlapCount}.
 *
 * <p><strong>What was wrong.</strong> The detector was live and wired — computed on every run,
 * carried into the response with its descriptions, and declaring its coverage dimension fully
 * {@code checked} — while appearing on <em>no</em> served surface at all: zero mentions in the
 * tool description, zero in the README, zero in {@code docs/}. An agent holding a nonzero count
 * could not learn from anything the project publishes what the field meant or what to do about
 * it, and the coverage map asserted the dimension had been fully examined.</p>
 *
 * <p><strong>Why a guard and not a one-off edit.</strong> The defect class is an <em>omission</em>,
 * and an omission matches no search for the name it omits — so nothing but a standing assertion
 * notices when a surface loses it again.</p>
 *
 * <p><strong>Scoped to the enumerating region, not the file.</strong> Each document is probed
 * inside the one section that does the enumerating. A whole-file scan passes on a file whose
 * correct sentence sits in an unrelated section, which is how a sibling guard in this repo once
 * stayed green through the regression it existed to catch. The anchors must match exactly once, so
 * a renamed heading reports a lost anchor rather than quietly checking an empty region.</p>
 *
 * <p><strong>The two facts that must agree.</strong> Both documents must state the nested-note
 * exclusion and the per-pair counting unit. They are the two things a reader gets wrong by
 * default: that a note inside a group is a collision (it is not), and that the count counts notes
 * (it counts pairs, so it can exceed the number of notes on the view).</p>
 */
public class NoteOverlapSurfaceParityTest {

    private static final String GLOSSARY = "docs/glossary.md";
    private static final String LAYOUT_ENGINE = "docs/layout-engine.md";

    @Test
    public void layoutEngine_informationalSection_mustCarryTheDetectorAndItsRemedy() {
        String section = headingBlock(read(LAYOUT_ENGINE), "#### Note Overlap");

        assertTrue("the subsection must name the field pair",
                section.contains("`noteOverlapCount` / `noteOverlapDescriptions`"));
        assertTrue("...must state the nested-note exclusion",
                section.contains("nested inside") || section.contains("**nested inside**"));
        assertTrue("...must state the per-pair counting unit",
                section.contains("(note, object) pair"));
        assertTrue("...and must carry a remedy, the bar its note sibling sets",
                section.contains("update-view-object"));
    }

    @Test
    public void glossary_metricsTable_mustCarryTheRow() {
        String table = headingBlock(read(GLOSSARY), "## Layout-quality metrics");

        assertTrue("the metrics table must carry a noteOverlapCount row",
                table.contains("`noteOverlapCount`"));
        assertTrue("...stating the nested-note exclusion", table.contains("nested inside"));
        assertTrue("...and the per-pair counting unit", table.contains("(note, object) pair"));
    }

    @Test
    public void bothDocuments_mustAgreeThatTheMetricMovesNoRating() {
        String section = headingBlock(read(LAYOUT_ENGINE), "#### Note Overlap");
        String table = headingBlock(read(GLOSSARY), "## Layout-quality metrics");

        assertTrue("layout-engine must say the detector is informational",
                section.contains("Informational only"));
        assertTrue("and the glossary row must say the same",
                table.contains("`noteOverlapCount`")
                        && table.substring(table.indexOf("`noteOverlapCount`"))
                                .startsWith("`noteOverlapCount` | A note's box overlapping an"
                                        + " element or a group. Informational"));
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
