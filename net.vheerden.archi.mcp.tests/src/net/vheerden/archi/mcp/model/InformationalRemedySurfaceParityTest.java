package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * Build-fired guard for the claim that every informational detection reaches the caller's prose.
 *
 * <p><strong>What was wrong.</strong> Thirteen metrics were measured, published as counts and
 * declared {@code checked} in the coverage map while emitting no sentence anywhere, and the
 * disclosure meant to catch that fired only on a view with no other prose at all. The published
 * surfaces described each detection and its remedy without ever saying where a caller would be
 * told about it, and one of them confined the "informational governs the rating, not the prose"
 * claim to a single metric.</p>
 *
 * <p><strong>Why a guard and not a one-off edit.</strong> The defect class is an <em>omission</em>,
 * and an omission matches no search for the sentence it omits — so nothing but a standing
 * assertion notices when a surface silently drops the claim again.</p>
 *
 * <p><strong>Scoped to the enumerating region, not the file.</strong> Each surface is probed inside
 * the one block that does the enumerating: the document section, the served description's own
 * informational block, and the README row for this tool. A whole-file scan passes on a file whose
 * correct sentence sits in an unrelated section, which is how a sibling guard in this repo once
 * stayed green through the regression it existed to catch. Every anchor must match exactly once,
 * so a renamed heading reports a lost anchor rather than quietly checking an empty region.</p>
 */
public class InformationalRemedySurfaceParityTest {

    private static final String LAYOUT_ENGINE = "docs/layout-engine.md";
    private static final String README = "README.md";
    private static final String HANDLER =
            "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/handlers/ViewPlacementHandler.java";

    @Test
    public void layoutEngine_informationalSection_mustStateThatEveryDetectionReachesTheProse() {
        String section = headingBlock(read(LAYOUT_ENGINE),
                "### Informational Detections (Non-Rating)");

        assertTrue("the section must say where a caller is told about these counts",
                section.contains("named in `suggestions` on any run where its count is nonzero"));
        assertTrue("...and that the backstop is sourced from the run, not from a per-metric table",
                section.contains("at the moment that suggestion is added"));
        assertTrue("...and that it fires whether or not other prose did",
                section.contains("whether or not other prose fired"));
    }

    @Test
    public void layoutEngine_informationalSection_mustRecordBothCompanionsAndTheirDirections() {
        String section = headingBlock(read(LAYOUT_ENGINE),
                "### Informational Detections (Non-Rating)");

        assertTrue("the cross-branch companion must be named as a companion",
                section.contains("`cousinOverlapCount` is the companion of `overlapCount`"));
        assertTrue("...with the case in which it IS emitted",
                section.contains("only on the runs where its principal is clean"));
        assertTrue("the grazed-element companion must be named as a companion",
                section.contains("`edgeCoincidenceGrazedElementCount` is the companion of"
                        + " `connectionEdgeCoincidenceCount`"));
        assertTrue("...and must record that it has NO standalone case, which is why it has no"
                        + " sentence of its own rather than a suppressed one",
                section.contains("has **no** such run"));
    }

    @Test
    public void layoutEngine_informationalSection_mustRecordTheTwoDeliberateSilences() {
        // Recorded where the next reader finds it, rather than in a backlog row alone. Both are
        // open, and each is open for a different reason — a section that stated only one of them
        // invites the other to be re-opened as an oversight.
        String section = headingBlock(read(LAYOUT_ENGINE),
                "### Informational Detections (Non-Rating)");

        assertTrue("the percentile must be recorded as a measurement, not a count",
                section.contains("`vAxisParallelGapP10` is a 10th-percentile **measurement**, not a"
                        + " count"));
        assertTrue("...with the reason count-shaped prose would be wrong for it",
                section.contains("zero is not its clean value"));
        assertTrue("the H-axis count must be recorded as blocked on a published field",
                section.contains("no top-level field in the published result"));
    }

    @Test
    public void layoutEngine_informationalSection_mustNotCountItsOwnSubsectionsByHand() {
        // The section opened with "Eleven detections are described in this section. Nine carry no
        // rating impact" while fifteen subsections sat underneath it — both figures wrong by four,
        // and wrong since well before the change that noticed them. A tally maintained by hand
        // beside the list it counts is the one claim on the page a reader cannot check without
        // recounting, and it rots on the next detector rather than on the next edit.
        //
        // The rule this pins is DELETE a hand-maintained tally, do not correct it: a corrected
        // number is the same defect with a later expiry date. So the assertion is not "the count is
        // right" — it is that no such count is stated at all. The two rating-bearing exceptions are
        // NAMED instead, which the pin below holds and which a reader can verify unaided.
        String section = headingBlock(read(LAYOUT_ENGINE),
                "### Informational Detections (Non-Rating)");

        // Only the sentence that counts the SUBSECTIONS is banned. The section says "Two detections
        // have no sentence of their own" and "Two measurements are deliberately left without" —
        // both count named, enumerated things a reader can check on the spot, which is the shape
        // that does not rot.
        Matcher tally = Pattern.compile(
                "(?i)\\b(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen"
                        + "|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|[0-9]+)\\b"
                        + "[^.]{0,60}?\\bdetections?\\b[^.]{0,40}?"
                        + "\\b(described|are listed|in this section|carry no rating)")
                .matcher(section);
        assertFalse("the section counts its own subsections by hand again — delete the tally and"
                + " name what you mean instead, because the number goes stale on the next detector"
                + " and no reader can check it without recounting: \""
                + (tally.find() ? tally.group() : "") + "\"",
                tally.reset().find());
    }

    @Test
    public void layoutEngine_informationalSection_mustNameTheTwoRatingBearingExceptions() {
        // What replaced the tally. The section documents two detectors that DO move a rating,
        // beside thirteen that do not, and a reader who takes the heading at face value would draw
        // exactly the wrong conclusion about them. Naming them is what makes the claim checkable
        // against the subsections; a count never was.
        String section = headingBlock(read(LAYOUT_ENGINE),
                "### Informational Detections (Non-Rating)");

        assertTrue("the section must name the label-truncation exception by its published field",
                section.contains("`labelTruncations`"));
        assertTrue("...and the obscured-parent-label exception",
                section.contains("`parentLabelObscured`"));
        assertTrue("...and must say the two of them DO affect the rating, since the heading says"
                        + " the opposite of the section as a whole",
                section.contains("Both *do* affect the rating"));
        assertTrue("...with the tier each one moves, which is the actionable half",
                section.contains("Tier-2R") && section.contains("Tier-1L"));
    }

    @Test
    public void servedDescription_informationalBlock_mustStateThatEachCountIsNamedInSuggestions() {
        String block = literalBlock(read(HANDLER),
                "\"INFORMATIONAL DETECTIONS (no rating impact): each is NAMED IN \"",
                "\"FURTHER RATING-AFFECTING DETECTIONS ");

        // Asserted against the RENDERED text, with the source's own string concatenation removed.
        // A phrase in this block is split across `" + "` boundaries wherever the line filled up, so
        // a probe reading the source verbatim would be asserting today's line wrapping rather than
        // the sentence a caller receives — and would go red on a reflow that changed nothing.
        String served = rendered(block);

        assertTrue("the served block must say the counts reach `suggestions`: " + served,
                served.contains("each is NAMED IN `suggestions` on any run where its count is"
                        + " nonzero"));
        assertTrue("...and must state the shortfall rule the prose actually applies: " + served,
                served.contains("plus the shortfall and its size where the count outruns the"
                        + " 10-entry description cap"));
        assertTrue("...and must name the terminal disclosure as the backstop, unconditionally on"
                        + " other findings: " + served,
                served.contains("named by a terminal disclosure instead, inline with its count,"
                        + " whether or not other defects were reported"));
    }

    @Test
    public void readmeRow_mustNotConfineTheProseClaimToASingleMetric() {
        // A published description can UNDER-claim as well as over-claim. This row said the
        // informational-governs-the-prose rule applied to one metric, which was true when one
        // metric had prose and is now a narrower statement than the tool's own behaviour.
        String row = toolRow(read(README), "| `assess-layout` |");

        assertTrue("the claim must cover every informational count",
                row.contains("every informational count is named in `suggestions`"));
        assertFalse("...so it must no longer read as a claim about one of them",
                row.contains("`ownIconOverLabelCount` is named in `suggestions` and in `nextSteps`"
                        + " while entering no rating"));
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

    /**
     * A Java source region reduced to the string it renders: the concatenation operators and the
     * quotes around each fragment removed, so an assertion reads the sentence a caller receives
     * rather than the line wrapping of the file that builds it.
     */
    private static String rendered(String sourceRegion) {
        return sourceRegion
                .replaceAll("\"\\s*\\+\\s*\"", "")
                .replace("\\\"", "\"")
                .replace("\"", "");
    }

    /** The source region between two literal anchors, each of which must occur exactly once. */
    private static String literalBlock(String text, String openAnchor, String closeAnchor) {
        requireExactlyOnce(text, openAnchor);
        requireExactlyOnce(text, closeAnchor);
        int start = text.indexOf(openAnchor);
        int end = text.indexOf(closeAnchor);
        if (end <= start) {
            throw new AssertionError("The closing anchor precedes the opening one; the region "
                    + "this guard scopes to no longer exists in the order it assumed.");
        }
        return text.substring(start, end);
    }

    private static void requireExactlyOnce(String text, String anchor) {
        int matches = 0;
        int at = text.indexOf(anchor);
        while (at >= 0) {
            matches++;
            at = text.indexOf(anchor, at + 1);
        }
        if (matches != 1) {
            throw new AssertionError("Anchor \"" + anchor + "\" matches " + matches
                    + " times; it must identify exactly one region.");
        }
    }

    /** The single markdown table row introduced by the given cell prefix. */
    private static String toolRow(String text, String rowPrefix) {
        String[] lines = text.split("\n", -1);
        String found = null;
        int matches = 0;
        for (String line : lines) {
            if (line.startsWith(rowPrefix)) {
                matches++;
                if (found == null) {
                    found = line;
                }
            }
        }
        if (matches != 1) {
            throw new AssertionError("Row prefix \"" + rowPrefix + "\" matches " + matches
                    + " lines; it must identify exactly one row.");
        }
        return found;
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
