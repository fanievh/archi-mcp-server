package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * Pins the remedies for the two rating-bearing metrics that moved a rating and put no sentence
 * anywhere: cross-element pass-throughs and hub-to-neighbour crowding.
 *
 * <p><strong>What was measured, before any production change.</strong> A view whose only defect was
 * one connection crossing one unrelated element published {@code overallRating: "fair"} — four such
 * crossings published {@code "poor"} — and a {@code suggestions} list holding exactly one sentence:
 * <em>"No defects were found on the dimensions this run examined."</em> A false all-clear standing
 * beside a downgraded headline, which this project forbids a detector from emitting. The cause was
 * that {@code passThroughs} was in neither source the verdict reads: not in the finding registry,
 * so nothing reached {@code found}, and not in the suggestion prose, so the list stayed at the
 * expected-state tally. A view whose only defect was a crowded hub reached the same all-clear by
 * the same route, and on a view where spacing fired beside it the spacing sentence truthfully
 * called itself <em>one of</em> the limiters while the co-equal limiter went unnamed.</p>
 *
 * <p><strong>Fixtures assert their own shape first.</strong> The obvious crowding fixture cannot
 * isolate the metric — a hub 45px from a 45px-pitch spoke row also averages 10px spacing — so the
 * discriminating one below widens the pitch until spacing passes, and says so by asserting the
 * breakdown before it reads a single sentence.</p>
 *
 * <p>Deliberately NOT in {@code tools/osgi-excluded-tests.txt}: this runs in the default headless
 * lane and in CI.</p>
 */
public class PassThroughAndCrowdingRemedyTest {

    private static final String ASSESSOR_SOURCE =
            "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/LayoutQualityAssessor.java";

    /** The all-clear that must never stand beside a downgraded rating. */
    private static final String ALL_CLEAR =
            "No defects were found on the dimensions this run examined";

    /** The trailing severity clause: metric, band, then cap and limiter status. */
    private static final Pattern SEVERITY_CLAUSE =
            Pattern.compile(" \\(([A-Za-z0-9]+) — (Tier [1-3][LR]), ([^()]*)\\)$");

    private final LayoutQualityAssessor assessor = new LayoutQualityAssessor();

    // ---- Fixtures -----------------------------------------------------------------------------

    private static AssessmentNode node(String id, double x, double y, double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, false, false, null, 0.0, null, null,
                0.0, 0.0, 0.0);
    }

    private static double[] p(double x, double y) {
        return new double[] {x, y};
    }

    /**
     * {@code n} independent source/blocker/target triples, each contributing exactly one
     * cross-element pass-through and nothing else. The triple is the shape
     * {@code LayoutQualityAssessorTest} already uses to prove the no-double-charge rule; the
     * triples are stacked 500px apart so no triple is any other triple's neighbour.
     */
    private LayoutAssessmentResult passThroughView(int n) {
        List<AssessmentNode> nodes = new ArrayList<>();
        List<AssessmentConnection> connections = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double y = 500 * i;
            nodes.add(node("src" + i, 0, y, 80, 50));
            nodes.add(node("tgt" + i, 400, y, 80, 50));
            nodes.add(node("mid" + i, 180, y, 140, 80));
            connections.add(new AssessmentConnection("c" + i, "src" + i, "tgt" + i,
                    List.of(p(80, y + 25), p(400, y + 25)), "", 1));
        }
        return assessor.assess(nodes, connections, false);
    }

    /**
     * Ten connections that route through their OWN target, followed by connections that cross an
     * unrelated element.
     *
     * <p>Both kinds share one description list capped at ten entries, and the self-element kind is
     * written first here, so the cap is exhausted before any cross-element crossing is described.
     * The rating still charges the cross-element ones. This is the shape that proves the pointer
     * cannot be sourced from the description list alone: the list names none of the charged
     * crossings, and a clause built on "the first N of them" has no N to name.</p>
     */
    private LayoutAssessmentResult selfElementsStarveTheCapView(int crossings) {
        List<AssessmentNode> nodes = new ArrayList<>();
        List<AssessmentConnection> connections = new ArrayList<>();
        // The overshoot-and-return shape: the non-terminal segment crosses the target's own body.
        for (int i = 0; i < 10; i++) {
            double y = 100 + 300 * i;
            nodes.add(node("selfA" + i, 0, y, 50, 50));
            nodes.add(node("selfB" + i, 100, y, 100, 50));
            connections.add(new AssessmentConnection("self" + i, "selfA" + i, "selfB" + i,
                    List.of(p(25, y + 25), p(350, y + 25), p(150, y + 25)), "", 1));
        }
        for (int i = 0; i < crossings; i++) {
            double y = 5000 + 500 * i;
            nodes.add(node("src" + i, 0, y, 80, 50));
            nodes.add(node("tgt" + i, 400, y, 80, 50));
            nodes.add(node("mid" + i, 180, y, 140, 80));
            connections.add(new AssessmentConnection("cross" + i, "src" + i, "tgt" + i,
                    List.of(p(80, y + 25), p(400, y + 25)), "", 1));
        }
        return assessor.assess(nodes, connections, false);
    }

    /**
     * A crowded hub whose crowding is the ONLY thing wrong with the view.
     *
     * <p>Four spokes sit 45px below a 320x160 hub — inside the 60px clearance floor, and enough of
     * them to make the face a spoke row — but at a 90px pitch, so the 50px gap between adjacent
     * spokes is wider than their 45px gap to the hub and every node's nearest-neighbour distance is
     * 45px or more. Average spacing therefore passes, which the 45px-pitch fixture the crowding
     * detector's own tests use does not: that one averages 10px and fires {@code spacing} as well,
     * so nothing read off it can be attributed to crowding. A fifth spoke to the left keeps the hub
     * above the five-connection hub-detection threshold without adding a second spoke row, and the
     * whole fixture sits in positive coordinates so {@code offCanvas} stays out of it.</p>
     */
    private LayoutAssessmentResult crowdingOnlyView() {
        List<AssessmentNode> nodes = new ArrayList<>();
        List<AssessmentConnection> connections = new ArrayList<>();
        nodes.add(node("hub", 400, 0, 320, 160));
        for (int i = 0; i < 4; i++) {
            double x = 400 + i * 90.0;
            nodes.add(node("s" + i, x, 205, 40, 40));
            connections.add(new AssessmentConnection("c" + i, "s" + i, "hub",
                    List.of(p(x + 20, 205), p(x + 20, 160)), "", 1));
        }
        nodes.add(node("far", 200, 60, 40, 40));
        connections.add(new AssessmentConnection("cFar", "far", "hub",
                List.of(p(240, 80), p(400, 80)), "", 1));
        return assessor.assess(nodes, connections, false);
    }

    /**
     * The same shape as {@link #crowdingOnlyView()} with the spoke row 59.6px below the hub —
     * inside the 60px floor, but close enough that rounding the measurement to nearest would
     * carry it up to the floor's own printed value.
     */
    private LayoutAssessmentResult crowdingJustUnderTheFloorView() {
        List<AssessmentNode> nodes = new ArrayList<>();
        List<AssessmentConnection> connections = new ArrayList<>();
        nodes.add(node("hub", 400, 0, 320, 160));
        double rowTop = 160 + 59.6;
        for (int i = 0; i < 4; i++) {
            double x = 400 + i * 90.0;
            nodes.add(node("s" + i, x, rowTop, 40, 40));
            connections.add(new AssessmentConnection("c" + i, "s" + i, "hub",
                    List.of(p(x + 20, rowTop), p(x + 20, 160)), "", 1));
        }
        nodes.add(node("far", 200, 60, 40, 40));
        connections.add(new AssessmentConnection("cFar", "far", "hub",
                List.of(p(240, 80), p(400, 80)), "", 1));
        return assessor.assess(nodes, connections, false);
    }

    /**
     * The same hub at the same 45px clearance, with the spoke row at its live 45px pitch. Spacing
     * collapses to 10px and fires alongside crowding, so this view has TWO metrics contributing at
     * the same level and is the case that proves neither sentence claims to be the sole cause.
     */
    private LayoutAssessmentResult crowdingBesideTightSpacingView() {
        List<AssessmentNode> nodes = new ArrayList<>();
        List<AssessmentConnection> connections = new ArrayList<>();
        nodes.add(node("hub", 0, 0, 320, 160));
        double rowTop = 205;
        for (int i = 0; i < 7; i++) {
            double x = i * 45.0;
            nodes.add(node("s" + i, x, rowTop, 40, 40));
            connections.add(new AssessmentConnection("c" + i, "s" + i, "hub",
                    List.of(p(x + 20, rowTop), p(x + 20, 160)), "", 1));
        }
        return assessor.assess(nodes, connections, false);
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private static String sentenceNaming(List<String> suggestions, String needle) {
        for (String suggestion : suggestions) {
            if (suggestion.contains(needle)) {
                return suggestion;
            }
        }
        return null;
    }

    private static boolean anySentenceContains(List<String> suggestions, String needle) {
        return sentenceNaming(suggestions, needle) != null;
    }

    private static void assertOnlyNonPassEntriesAre(LayoutAssessmentResult result,
                                                    String... expected) {
        Set<String> allowed = new LinkedHashSet<>(List.of(expected));
        for (String metric : result.ratingBreakdown().keySet()) {
            if (metric.startsWith("overall") || allowed.contains(metric)) {
                continue;
            }
            assertEquals("the fixture has drifted off the shape it was built for — '" + metric
                    + "' is no longer pass, so anything this test concludes is about a different"
                    + " view", "pass", result.ratingBreakdown().get(metric));
        }
        for (String metric : expected) {
            assertTrue("the fixture no longer produces the '" + metric + "' finding it was built"
                    + " for", !"pass".equals(result.ratingBreakdown().get(metric)));
        }
    }

    /**
     * Package-crossing on purpose: the enumeration guard over the handler's served descriptions
     * needs the same reader, and a second copy of a source-scanning helper is a second thing that
     * can silently stop scanning.
     */
    public static String readRepoFile(String relative) {
        Path dir = Paths.get("").toAbsolutePath();
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
        throw new AssertionError("Could not locate " + relative + " from "
                + Paths.get("").toAbsolutePath());
    }

    /**
     * The source with every comment removed and every string literal kept.
     *
     * <p>A prose-bearing file explains its own decisions in comments, so a source-reading pin that
     * scans the raw text can be satisfied by a javadoc describing the very construct it exists to
     * forbid. That has happened in this file before. String literals are preserved because the
     * constructs being searched for are code, and a literal that happened to contain one would be a
     * finding worth seeing rather than noise.</p>
     */
    public static String withoutComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '"' && source.startsWith("\"\"\"", i)) {
                // A text block. Without this arm the first two quotes read as an empty string and
                // the third opens a literal that swallows the block body and every comment after
                // it until the next quote — which would make this scan pass over exactly the
                // construct it exists to forbid. The file carries none today; the arm is here so
                // that adding one is not a silent loss of the guard.
                out.append("\"\"\"");
                i += 3;
                while (i < source.length() && !source.startsWith("\"\"\"", i)) {
                    if (source.charAt(i) == '\\' && i + 1 < source.length()) {
                        out.append(source.charAt(i));
                        i++;
                    }
                    out.append(source.charAt(i));
                    i++;
                }
                if (i < source.length()) {
                    out.append("\"\"\"");
                    i += 3;
                }
            } else if (c == '"' || c == '\'') {
                char quote = c;
                out.append(c);
                i++;
                while (i < source.length()) {
                    char d = source.charAt(i);
                    out.append(d);
                    i++;
                    if (d == '\\' && i < source.length()) {
                        out.append(source.charAt(i));
                        i++;
                    } else if (d == quote) {
                        break;
                    }
                }
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < source.length()
                        && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, source.length());
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    // ---- The false all-clear ------------------------------------------------------------------

    @Test
    public void shouldNameTheCrossingRatherThanPublishAnAllClear_whenOnePassThroughIsTheOnlyDefect() {
        LayoutAssessmentResult result = passThroughView(1);
        assertOnlyNonPassEntriesAre(result, "passThroughs");
        assertEquals("fair", result.overallRating());

        assertFalse("a view rated '" + result.overallRating() + "' published \"" + ALL_CLEAR
                + "\" — the response contradicts itself and the caller has no way to reconcile the"
                + " two. Suggestions: " + result.suggestions(),
                anySentenceContains(result.suggestions(), ALL_CLEAR));
        assertTrue("nothing in the suggestion list names the crossing that downgraded the view: "
                + result.suggestions(),
                anySentenceContains(result.suggestions(), "does not connect to"));
    }

    @Test
    public void shouldNameTheCrossingsRatherThanPublishAnAllClear_whenPassThroughsDriveTheViewToPoor() {
        LayoutAssessmentResult result = passThroughView(4);
        assertOnlyNonPassEntriesAre(result, "passThroughs");
        assertEquals("poor", result.overallRating());
        assertEquals("poor", result.ratingBreakdown().get("passThroughs"));

        assertFalse("a view rated 'poor' published an all-clear: " + result.suggestions(),
                anySentenceContains(result.suggestions(), ALL_CLEAR));
        String sentence = sentenceNaming(result.suggestions(), "do not connect to");
        assertNotNull("no sentence names the four crossings: " + result.suggestions(), sentence);
        assertTrue("the sentence must name the count the rating charges, which is 4 cross-element"
                + " crossings: " + sentence, sentence.startsWith("4 connections"));
    }

    @Test
    public void shouldNameTheCrowdingRatherThanPublishAnAllClear_whenCrowdingIsTheOnlyDefect() {
        LayoutAssessmentResult result = crowdingOnlyView();
        // Shape first: the fixture is worthless unless crowding is the only thing it moves.
        assertOnlyNonPassEntriesAre(result, "hubNeighbourCrowding");
        assertEquals("fair", result.overallRating());
        assertEquals(45.0, result.hubNeighbourClearanceMin(), 0.001);

        assertFalse("a view rated 'fair' by hub crowding alone published an all-clear: "
                + result.suggestions(),
                anySentenceContains(result.suggestions(), ALL_CLEAR));
        assertTrue("nothing names the crowded hub: " + result.suggestions(),
                anySentenceContains(result.suggestions(), "clearance floor"));
    }

    // ---- Rank and annotation, derived rather than typed ----------------------------------------

    @Test
    public void shouldRankThePassThroughSentenceOnItsOwnBandAndNameItAsALimiter() {
        LayoutAssessmentResult result = passThroughView(4);
        String sentence = sentenceNaming(result.suggestions(), "do not connect to");
        assertNotNull(sentence);

        Matcher clause = SEVERITY_CLAUSE.matcher(sentence);
        assertTrue("the pass-through sentence carries no severity clause, so it was not added as a"
                + " rated sentence: " + sentence, clause.find());
        assertEquals("the sentence ranks on a metric other than its own", "passThroughs",
                clause.group(1));
        assertEquals("the band is derived from the routing fold, which reads passThroughs in its"
                + " uncapped critical arm", "Tier 1R", clause.group(2));
        assertTrue("an uncapped metric must publish no cap: " + clause.group(3),
                clause.group(3).startsWith("uncapped"));
        assertTrue("the metric that drove this view to 'poor' must be named as a limiter: "
                + clause.group(3),
                clause.group(3).contains("one of the metrics holding this view at 'poor'"));
    }

    @Test
    public void shouldRankTheCrowdingSentenceOnItsOwnBandAndNameItAsALimiter() {
        LayoutAssessmentResult result = crowdingOnlyView();
        assertOnlyNonPassEntriesAre(result, "hubNeighbourCrowding");
        String sentence = sentenceNaming(result.suggestions(), "clearance floor");
        assertNotNull(sentence);

        Matcher clause = SEVERITY_CLAUSE.matcher(sentence);
        assertTrue("the crowding sentence carries no severity clause: " + sentence, clause.find());
        assertEquals("hubNeighbourCrowding", clause.group(1));
        assertEquals("the band is derived from the layout fold, which reads hub crowding in its"
                + " cap-fair arm", "Tier 2L", clause.group(2));
        assertTrue("a cap-fair metric must publish its cap: " + clause.group(3),
                clause.group(3).startsWith("caps at 'fair'"));
        assertTrue("crowding is what holds this view at 'fair' and must say so: " + clause.group(3),
                clause.group(3).contains("one of the metrics holding this view at 'fair'"));
    }

    @Test
    public void shouldNameBothLimiters_whenSpacingAndCrowdingHoldTheViewAtTheSameLevel() {
        LayoutAssessmentResult result = crowdingBesideTightSpacingView();
        assertOnlyNonPassEntriesAre(result, "spacing", "hubNeighbourCrowding");
        assertEquals("fair", result.overallRating());

        String spacing = sentenceNaming(result.suggestions(), "Average spacing is only");
        String crowding = sentenceNaming(result.suggestions(), "clearance floor");
        assertNotNull("the spacing sentence is missing: " + result.suggestions(), spacing);
        assertNotNull("the co-equal crowding limiter is unnamed, so the spacing sentence is the"
                + " caller's only account of a view two metrics hold down: " + result.suggestions(),
                crowding);

        String limiter = "one of the metrics holding this view at 'fair'";
        assertTrue("the spacing sentence must not claim to be the sole cause: " + spacing,
                spacing.contains(limiter));
        assertTrue("the crowding sentence must not claim to be the sole cause: " + crowding,
                crowding.contains(limiter));
    }

    // ---- The registry, the count, and the terminal backstop -------------------------------------

    @Test
    public void shouldNameTheShortfall_whenTheChargedCountExceedsWhatTheDescriptionListNames() {
        LayoutAssessmentResult result = passThroughView(12);
        assertOnlyNonPassEntriesAre(result, "passThroughs");
        assertEquals("the description list is capped, so it cannot name all twelve", 10,
                result.connectionPassThroughs().size());

        String sentence = sentenceNaming(result.suggestions(), "do not connect to");
        assertNotNull(sentence);
        assertTrue("the sentence charges twelve crossings: " + sentence,
                sentence.startsWith("12 connections"));
        assertTrue("the caller is sent to a field holding ten entries for a count of twelve and is"
                + " never told the field is short, nor how to recover the other two: " + sentence,
                sentence.contains("names the first 10 of them")
                        && sentence.contains("includeViolatorIds to recover the remaining 2"));
    }

    @Test
    public void shouldNotRepeatThePassThroughInTheTerminalDisclosure() {
        LayoutAssessmentResult result = passThroughView(4);
        String backstop = sentenceNaming(result.suggestions(),
                "carries no specific remedy above");
        String backstopPlural = sentenceNaming(result.suggestions(),
                "carry no specific remedy above");
        assertTrue("passThroughs is registered as a finding AND explained by the sentence above,"
                + " so the closing disclosure must not name it a second time. It said: "
                + result.suggestions(),
                backstop == null && backstopPlural == null);
    }

    @Test
    public void shouldMatchTheDescriptionFormTheNamedCrossElementCountIsCountedOn() {
        // The shortfall clause is built from the number of CROSS-element entries the published list
        // names, and the only thing distinguishing those from the self-element entries beside them
        // is the wording the detector writes. Nothing in the record carries the split, and the
        // record is frozen for its other callers, so this pin is what single-sources the two: a
        // reword of the description that left the counter behind would silently take the named
        // count to zero and delete the shortfall clause with it.
        LayoutAssessmentResult result = passThroughView(1);
        assertEquals(1, result.connectionPassThroughs().size());
        assertTrue("the cross-element description no longer carries the form the named-count"
                + " helper matches on, so that count is now zero on every view: "
                + result.connectionPassThroughs(),
                result.connectionPassThroughs().get(0).contains("' passes through element '"));
    }

    // ---- What hub crowding must NOT gain -------------------------------------------------------

    @Test
    public void shouldNotRegisterHubCrowdingAsAFindingNorRecordItAsExplained() {
        // Read with comments stripped: this file explains its own rulings in prose, and a javadoc
        // saying "no registry entry for hubNeighbourCrowding" would satisfy a raw-text scan for the
        // very construct the scan exists to forbid.
        String code = withoutComments(readRepoFile(ASSESSOR_SOURCE));

        assertFalse("hub-neighbour clearance is score-valued: its clean value is a large number or"
                + " the no-hub sentinel, so a registry entry keyed on a nonzero count inverts its"
                + " meaning — the ruling MetricFindings already records for the other score-valued"
                + " metrics", code.contains("new MetricFinding(\"hubNeighbourCrowding\""));
        assertFalse("recording an id that is registered nowhere puts a name in the explained set"
                + " that no finding can ever be matched against",
                code.contains("explained.add(\"hubNeighbourCrowding\")"));
        assertTrue("the pass-through half IS registered and IS recorded — without both, the metric"
                + " that publishes the false all-clear is only half accounted for",
                code.contains("new MetricFinding(\"passThroughs\"")
                        && code.contains("explained.add(\"passThroughs\")"));
    }

    @Test
    public void shouldReadTheCrowdingFloorFromTheConstantRatherThanRetypeIt() {
        // A second copy of the floor is already carried by the handler's own diagnostic step, and a
        // third here could come to disagree with the fold that decided this view was crowded.
        String code = withoutComments(readRepoFile(ASSESSOR_SOURCE));
        int at = code.indexOf("px clearance floor");
        assertTrue("the crowding remedy no longer states the clearance floor", at >= 0);
        String preceding = code.substring(Math.max(0, at - 300), at);
        assertTrue("the floor in the crowding sentence must be read from CROWDING_FLOOR_PX, never"
                + " retyped as a literal: " + preceding,
                preceding.contains("Math.round(CROWDING_FLOOR_PX)"));
    }
    @Test
    public void shouldStillPointSomewhere_whenTheDescriptionListNamesNoneOfTheChargedCrossings() {
        LayoutAssessmentResult result = selfElementsStarveTheCapView(2);
        String sentence = sentenceNaming(result.suggestions(), "connect to");
        assertNotNull("no pass-through sentence at all: " + result.suggestions(), sentence);
        assertTrue("the rating must still charge both cross-element crossings even though the"
                + " description list describes neither: " + sentence,
                sentence.startsWith("2 connections"));

        // The precondition that makes this fixture worth anything: the published list really does
        // name none of the charged crossings. Without this the pin could pass on an ordinary view.
        for (String description : result.connectionPassThroughs()) {
            assertFalse("the fixture no longer starves the cap — a cross-element description got"
                    + " into the list, so this is not the case the pin was built for: "
                    + description,
                    description.contains("' passes through element '"));
        }

        assertTrue("the sentence charges a count and then points the caller nowhere — the caller"
                + " cannot look up a single one of the crossings it was marked down for: "
                + sentence,
                sentence.contains("includeViolatorIds"));
        assertTrue("when the description list names none of them, the sentence must say so rather"
                + " than pointing at a list that will disappoint: " + sentence,
                sentence.contains("names none of them"));
    }

    @Test
    public void shouldNotPublishAClearanceEqualToTheFloorItClaimsToBeBelow() {
        // Rounding to nearest carries a clearance of 59.6 up to 60 and publishes "only 60px ...
        // below the 60px clearance floor" — a sentence asserting a number is below itself.
        LayoutAssessmentResult result = crowdingJustUnderTheFloorView();
        assertOnlyNonPassEntriesAre(result, "hubNeighbourCrowding");
        assertTrue("the fixture must sit in the rounding danger band, just under the floor",
                result.hubNeighbourClearanceMin() > 59.0
                        && result.hubNeighbourClearanceMin() < 60.0);

        String sentence = sentenceNaming(result.suggestions(), "clearance floor");
        assertNotNull(sentence);
        assertFalse("the sentence claims a clearance is below a floor it prints as the same"
                + " number: " + sentence,
                sentence.contains("only 60px from the row of spokes facing it, below the 60px"));
        assertTrue("the measured clearance must still be published: " + sentence,
                sentence.contains("only 59px"));
    }

}
