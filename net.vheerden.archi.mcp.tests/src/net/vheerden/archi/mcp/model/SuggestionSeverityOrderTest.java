package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.handlers.HandlerRegistrar;
import net.vheerden.archi.mcp.handlers.SearchHandler;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Pins the order of {@code assess-layout}'s suggestion list against the severity the rating model
 * actually assigns each metric, and the clause every rated sentence carries about its own band.
 *
 * <p><strong>What was measured.</strong> On a grouped view rating {@code fair} whose breakdown was
 * clean apart from {@code edgeCrossings: fair} and {@code labelOverlaps: fair}, the crossings
 * sentence was published first. {@code edgeCrossings} sits in a cap-good band: it cannot hold a
 * view at {@code fair}, and the same rating model that produced the headline proves it cannot.
 * {@code labelOverlaps} sits in a cap-fair band and is what held that view. An agent working the
 * list top-down spent its first remediation attempt on the one metric that could not move the
 * headline.</p>
 *
 * <p><strong>Fixtures assert their own shape first.</strong> Every fixture below states the
 * breakdown it means to produce before it asserts anything about prose. A fixture that has drifted
 * off the shape it was built for would otherwise keep pinning something else, quietly.</p>
 *
 * <p><strong>Bands are never typed here.</strong> Where a band spelling is asserted, it is asserted
 * against what the production clause published for a metric whose live value the fixture set — not
 * against a table held in this file, which would drift in step with nothing.</p>
 *
 * <p>Deliberately NOT in {@code tools/osgi-excluded-tests.txt}: this runs in the default headless
 * lane and in CI. A pawl the build never pulls is not a pawl.</p>
 */
public class SuggestionSeverityOrderTest {

    private static final String ASSESSOR_SOURCE =
            "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/LayoutQualityAssessor.java";
    private static final String LAYOUT_ENGINE = "docs/layout-engine.md";

    /**
     * The trailing severity clause: metric, band, cap, and limiter status. Anchored at the end of
     * the sentence and forbidden from containing brackets, so it cannot accidentally match the
     * parenthetical counts several sentences already carry mid-text.
     */
    private static final Pattern SEVERITY_CLAUSE =
            Pattern.compile(" \\(([A-Za-z0-9]+) — (Tier [1-3][LR]), ([^()]*)\\)$");

    private final LayoutQualityAssessor assessor = new LayoutQualityAssessor();

    // ---- Fixture builders --------------------------------------------------------------------

    private static AssessmentNode node(String id, double x, double y, double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, false, false, null, 0.0, null, null,
                0.0, 0.0, 0.0);
    }

    private static AssessmentNode group(String id, double x, double y, double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, true, false, null, 0.0, null, null,
                0.0, 0.0, 0.0);
    }

    private static double[] p(double x, double y) {
        return new double[] {x, y};
    }

    /**
     * Three labelled connections whose labels each collide with a third element, appended to the
     * given fixture. Each triple contributes exactly one label overlap and nothing else: the route
     * clears the colliding element by more than the edge-coincidence tolerance and by more than the
     * pass-through inset, so the only dimension it moves is the one it was built for.
     */
    private static void appendLabelCollisions(List<AssessmentNode> nodes,
                                              List<AssessmentConnection> connections, int count) {
        for (int i = 0; i < count; i++) {
            double base = 3000 + 300 * i;
            nodes.add(node("labelSrc" + i, 0, base, 200, 60));
            nodes.add(node("labelTgt" + i, 900, base, 200, 60));
            nodes.add(node("labelHit" + i, 510, base + 36, 80, 60));
            connections.add(new AssessmentConnection("labelConn" + i, "labelSrc" + i,
                    "labelTgt" + i, List.of(p(200, base + 30), p(900, base + 30)), "LABEL", 1));
        }
    }

    /**
     * The measured shape: a grouped view whose only non-pass breakdown entries are
     * {@code edgeCrossings: fair} — a cap-good band — and {@code labelOverlaps: fair}, a cap-fair
     * band and therefore the limiter.
     *
     * <p>Seven crossbar connections in full reversal cross once per pair, giving 21 crossings over
     * ten connections. Each carries its own corridor and its own terminal offset, so no two
     * segments run along each other and the reversal produces crossings and nothing else.</p>
     */
    private LayoutAssessmentResult crossingsAndLabelOverlapsView() {
        List<AssessmentNode> nodes = new ArrayList<>();
        // An empty container far from every route: it makes the view grouped — which selects the
        // grouped remedy wording the measured view carried — without putting an obstacle on a path.
        nodes.add(group("emptyZone", 1700, 0, 200, 100));
        for (int i = 0; i < 7; i++) {
            nodes.add(node("S" + i, 0, 200 * i, 200, 60));
            nodes.add(node("T" + i, 1200, 200 * i, 200, 60));
        }
        List<AssessmentConnection> connections = new ArrayList<>();
        for (int k = 0; k < 7; k++) {
            int j = 6 - k;
            double corridor = 350 + 100 * k;
            connections.add(new AssessmentConnection("c" + k, "S" + k, "T" + j,
                    List.of(p(200, 200 * k + 20), p(corridor, 200 * k + 20),
                            p(corridor, 200 * j + 40), p(1200, 200 * j + 40)),
                    "", 1));
        }
        appendLabelCollisions(nodes, connections, 3);
        return assessor.assess(nodes, connections, false);
    }

    /**
     * A view whose only routing findings are diagonal terminals — split across both halves of the
     * family, so the mixed branch fires and emits two sentences — beside three label overlaps.
     * Both metrics sit in cap-fair bands and both are at or past {@code fair}, so their capped
     * contributions are equal and the ordering between them is decided by stability alone.
     */
    private LayoutAssessmentResult mixedDiagonalsAndLabelOverlapsView() {
        List<AssessmentNode> nodes = new ArrayList<>();
        List<AssessmentConnection> connections = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            double y = 400 * i;
            nodes.add(node("zeroBpSrc" + i, 0, y, 100, 60));
            nodes.add(node("zeroBpTgt" + i, 400, y + 200, 100, 60));
            // Two points only: a straight line between two element centres, which is the half of
            // the family that carries no routed body.
            connections.add(new AssessmentConnection("zeroBp" + i, "zeroBpSrc" + i, "zeroBpTgt" + i,
                    List.of(p(50, y + 30), p(450, y + 230)), "", 1));
        }
        for (int i = 0; i < 2; i++) {
            double y = 900 + 400 * i;
            nodes.add(node("routedSrc" + i, 0, y, 100, 60));
            nodes.add(node("routedTgt" + i, 400, y + 200, 100, 60));
            connections.add(new AssessmentConnection("routed" + i, "routedSrc" + i, "routedTgt" + i,
                    List.of(p(50, y + 30), p(250, y + 100), p(450, y + 230)), "", 1));
        }
        appendLabelCollisions(nodes, connections, 3);
        return assessor.assess(nodes, connections, false);
    }

    /**
     * {@code n} connections each hugging one element edge. The count is what decides whether
     * {@code connectionEdgeCoincidence} stays in its cap-fair band or escalates into the uncapped
     * one, so this is the fixture both sides of that gate are driven from.
     */
    private LayoutAssessmentResult edgeHugView(int n) {
        List<AssessmentNode> nodes = new ArrayList<>();
        List<AssessmentConnection> connections = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            double y = 500 * k;
            nodes.add(node("hugSrc" + k, 0, y, 150, 60));
            nodes.add(node("hugTgt" + k, 700, y, 150, 60));
            // Top edge 2px below the route: inside the 3px hug tolerance and outside the 10px
            // pass-through inset, so the connection hugs this element and passes through nothing.
            nodes.add(node("hugged" + k, 300, y + 32, 200, 60));
            connections.add(new AssessmentConnection("hug" + k, "hugSrc" + k, "hugTgt" + k,
                    List.of(p(150, y + 30), p(700, y + 30)), "", 1));
        }
        return assessor.assess(nodes, connections, false);
    }

    /**
     * A cap-fair metric sitting at {@code good} on a view another cap-fair metric holds at
     * {@code fair}: two label overlaps rate {@code good}, one off-canvas element rates {@code fair}.
     */
    private LayoutAssessmentResult capFairMetricAtGoodView() {
        List<AssessmentNode> nodes = new ArrayList<>();
        List<AssessmentConnection> connections = new ArrayList<>();
        nodes.add(node("offCanvasElement", -400, 100, 120, 60));
        appendLabelCollisions(nodes, connections, 2);
        return assessor.assess(nodes, connections, false);
    }

    /**
     * A cap-fair metric sitting at {@code good} as the view's ONLY finding, so the view itself
     * rates {@code good}. This is the case that separates a cap from a pin: if the band pinned,
     * two overlapping labels would drag the view to {@code fair}.
     */
    private LayoutAssessmentResult capFairMetricAloneAtGoodView() {
        List<AssessmentNode> nodes = new ArrayList<>();
        List<AssessmentConnection> connections = new ArrayList<>();
        appendLabelCollisions(nodes, connections, 2);
        return assessor.assess(nodes, connections, false);
    }

    /**
     * Two dense clusters whose average spacing is 12px, joined by two connections that cross once
     * in the corridor between them. The single crossing is measured and registered but sits far
     * below the crossings remedy threshold, so it is a finding no sentence explains — the closing
     * disclosure fires beside a rated spacing sentence.
     */
    private LayoutAssessmentResult tightSpacingWithUnexplainedFindingView() {
        List<AssessmentNode> nodes = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            nodes.add(node("left" + i, (i % 5) * 122, (i / 5) * 82, 110, 70));
            nodes.add(node("right" + i, 1400 + (i % 5) * 122, (i / 5) * 82, 110, 70));
        }
        List<AssessmentConnection> connections = List.of(
                new AssessmentConnection("cross1", "left9", "right15",
                        List.of(p(598, 117), p(1250, 117), p(1250, 281), p(1400, 281)), "", 1),
                new AssessmentConnection("cross2", "left19", "right0",
                        List.of(p(598, 281), p(1100, 281), p(1100, 35), p(1400, 35)), "", 1));
        return assessor.assess(nodes, connections, false);
    }

    /** Well-spaced but unaligned elements: the only non-pass entry is {@code alignment}. */
    private LayoutAssessmentResult unalignedView() {
        int[] xs = {0, 137, 311, 63, 452, 219, 388, 91, 273, 501};
        int[] ys = {0, 213, 47, 391, 158, 526, 289, 634, 462, 701};
        List<AssessmentNode> nodes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            nodes.add(node("scattered" + i, xs[i] * 3, ys[i] * 3, 90, 50));
        }
        return assessor.assess(nodes, List.of(), false);
    }

    /**
     * A hub whose six spokes all land on one face point. Two metrics go non-pass, and which is
     * which is the whole point of the fixture: {@code hubPortQuality} is driven to {@code poor} in
     * the BREAKDOWN but sits in a cap-fair band, while the coincident routes trip
     * {@code coincidentSegments}, whose band is uncapped and is therefore what actually takes the
     * VIEW to {@code poor}. Naming only the first would pin an unattributed side effect.
     */
    private LayoutAssessmentResult crowdedHubView() {
        List<AssessmentNode> nodes = new ArrayList<>();
        List<AssessmentConnection> connections = new ArrayList<>();
        nodes.add(node("hub", 1000, 1000, 200, 400));
        for (int i = 0; i < 6; i++) {
            double y = 200 * i;
            nodes.add(node("spoke" + i, 300, y, 120, 60));
            connections.add(new AssessmentConnection("spokeConn" + i, "spoke" + i, "hub",
                    List.of(p(420, y + 30), p(700, y + 30), p(700, 1200), p(1000, 1200)), "", 1));
        }
        return assessor.assess(nodes, connections, false);
    }

    /** A view past the large-view warning threshold that also carries a rated defect. */
    private LayoutAssessmentResult largeViewWithADefect() {
        List<AssessmentNode> nodes = new ArrayList<>();
        for (int i = 0; i < 505; i++) {
            nodes.add(node("bulk" + i, (i % 25) * 300, (i / 25) * 300, 100, 60));
        }
        nodes.add(node("overlapping", 40, 20, 100, 60));
        return assessor.assess(nodes, List.of(), false);
    }

    // ---- Small helpers -----------------------------------------------------------------------

    private static int indexOf(List<String> suggestions, String needle) {
        for (int i = 0; i < suggestions.size(); i++) {
            if (suggestions.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    /** The severity clause on the first sentence naming {@code needle}, or null. */
    private static Matcher clauseOn(List<String> suggestions, String needle) {
        int at = indexOf(suggestions, needle);
        if (at < 0) {
            return null;
        }
        Matcher matcher = SEVERITY_CLAUSE.matcher(suggestions.get(at));
        return matcher.find() ? matcher : null;
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

    private static String readRepoFile(String relative) {
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

    private static String servedDescriptionOf(String toolName) {
        CommandRegistry registry = new CommandRegistry();
        SessionManager sessions =
                new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        HandlerRegistrar.registerAll(
                new BaseTestAccessor(), new ResponseFormatter(), registry, sessions);
        McpSchema.Tool tool = registry.getToolSpecifications().stream()
                .filter(spec -> toolName.equals(spec.tool().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not registered: " + toolName))
                .tool();
        return tool.description();
    }

    // ---- The measured defect -----------------------------------------------------------------

    @Test
    public void shouldRankTheCapFairMetricAboveTheCapGoodOne_whenBothAreFairOnAFairView() {
        LayoutAssessmentResult result = crossingsAndLabelOverlapsView();
        assertEquals("the fixture must reproduce the measured overall rating",
                "fair", result.overallRating());
        assertOnlyNonPassEntriesAre(result, "edgeCrossings", "labelOverlaps");
        assertEquals("fair", result.ratingBreakdown().get("edgeCrossings"));
        assertEquals("fair", result.ratingBreakdown().get("labelOverlaps"));

        List<String> suggestions = result.suggestions();
        int labels = indexOf(suggestions, "connection labels overlap");
        int crossings = indexOf(suggestions, "edge crossings");
        assertTrue("the label-overlap sentence must be published", labels >= 0);
        assertTrue("the crossings sentence must be published", crossings >= 0);
        assertTrue("labelOverlaps caps the routing tier at 'fair' and is what holds this view at"
                + " 'fair'; edgeCrossings caps at 'good' and cannot be. The cap-fair sentence must"
                + " come first, so an agent working the list top-down spends its first attempt on"
                + " a metric that can move the headline. Got labelOverlaps at index " + labels
                + " and edgeCrossings at index " + crossings + ".",
                labels < crossings);
    }

    @Test
    public void shouldCallOnlyTheLimiterALimiter_whenACapGoodMetricSitsBesideACapFairOne() {
        List<String> suggestions = crossingsAndLabelOverlapsView().suggestions();

        Matcher labels = clauseOn(suggestions, "connection labels overlap");
        Matcher crossings = clauseOn(suggestions, "edge crossings");
        assertTrue("the label-overlap sentence must carry a severity clause", labels != null);
        assertTrue("the crossings sentence must carry a severity clause", crossings != null);

        assertEquals("labelOverlaps", labels.group(1));
        assertTrue("the limiter's clause must say it is one of the metrics holding the view where"
                + " it is, and must not claim to be the only one: " + labels.group(3),
                labels.group(3).contains("one of the metrics holding this view at 'fair'"));

        assertEquals("edgeCrossings", crossings.group(1));
        assertTrue("the cap-good metric must be published as NOT what holds this view at 'fair':"
                + " " + crossings.group(3),
                crossings.group(3).contains("not the 'fair' this view sits at"));
    }

    // ---- The four stable groups ---------------------------------------------------------------

    @Test
    public void shouldKeepTheAssessmentQualifierFirst_whenTheViewAlsoCarriesARatedDefect() {
        LayoutAssessmentResult result = largeViewWithADefect();
        List<String> suggestions = result.suggestions();

        assertEquals("the large-view warning qualifies the whole assessment rather than reporting"
                + " a defect, so it stays ahead of every finding however severe the findings are",
                0, indexOf(suggestions, "assessment metrics may be slow"));
        int overlaps = indexOf(suggestions, "overlapping element pairs");
        assertTrue("the fixture must also carry a rated defect, or it proves nothing about order",
                overlaps > 0);
        assertTrue("the rated defect must carry a severity clause",
                SEVERITY_CLAUSE.matcher(suggestions.get(overlaps)).find());
    }

    @Test
    public void shouldKeepTheClosingDisclosureLast_whenTheViewAlsoCarriesARatedDefect() {
        LayoutAssessmentResult result = tightSpacingWithUnexplainedFindingView();
        assertOnlyNonPassEntriesAre(result, "spacing");
        List<String> suggestions = result.suggestions();

        int spacing = indexOf(suggestions, "Average spacing is only");
        int disclosure = indexOf(suggestions, "carries no specific remedy above");
        assertTrue("the rated spacing sentence must be published", spacing >= 0);
        assertTrue("a finding nothing explained must still be disclosed", disclosure >= 0);
        assertEquals("the closing disclosure stays last, where a reader who has worked the list"
                + " expects to find what nothing above accounted for",
                suggestions.size() - 1, disclosure);
        assertTrue("the rated sentence comes before the closing disclosure", spacing < disclosure);
    }

    @Test
    public void shouldKeepEmissionOrder_whenTwoMetricsContributeTheSameLevel() {
        LayoutAssessmentResult result = mixedDiagonalsAndLabelOverlapsView();
        assertOnlyNonPassEntriesAre(result, "labelOverlaps", "nonOrthogonalTerminals");
        List<String> suggestions = result.suggestions();

        Matcher labels = clauseOn(suggestions, "connection labels overlap");
        Matcher terminals = clauseOn(suggestions, "carry no bendpoints");
        assertTrue("the label-overlap sentence must carry a severity clause", labels != null);
        assertTrue("the diagonal-terminal sentence must carry a severity clause", terminals != null);
        assertEquals("both metrics must be capped into the same band for this to be a tie at all",
                labels.group(2), terminals.group(2));

        int labelAt = indexOf(suggestions, "connection labels overlap");
        int terminalAt = indexOf(suggestions, "carry no bendpoints");
        assertTrue("two metrics contributing the same level are a tie, and a tie must resolve to"
                + " the order the checks emitted them in — the label-overlap check runs first."
                + " A sort that lost this has stopped being stable.",
                labelAt < terminalAt);
    }

    @Test
    public void shouldKeepTheDiagonalTerminalPairInItsEmittedOrder_whenBothHalvesArePresent() {
        List<String> suggestions = mixedDiagonalsAndLabelOverlapsView().suggestions();

        int zeroBendpoint = indexOf(suggestions, "carry no bendpoints");
        int routed = indexOf(suggestions, "carry a routed body");
        assertTrue("the mixed diagonal-terminal case must emit BOTH halves", zeroBendpoint >= 0);
        assertTrue("the mixed diagonal-terminal case must emit BOTH halves", routed >= 0);
        assertTrue("the two halves have opposite remedies and are published as one partition, so"
                + " the zero-bendpoint half must keep coming first", zeroBendpoint < routed);
        assertEquals("both halves rank on the same breakdown key",
                clauseOn(suggestions, "carry no bendpoints").group(1),
                clauseOn(suggestions, "carry a routed body").group(1));
    }

    // ---- A tier caps a contribution; it does not pin it ---------------------------------------

    @Test
    public void shouldPublishTheContributionNotTheCap_whenACapFairMetricSitsAtGood() {
        LayoutAssessmentResult result = capFairMetricAtGoodView();
        assertOnlyNonPassEntriesAre(result, "labelOverlaps", "offCanvas");
        assertEquals("the fixture needs the cap-fair metric sitting at good, not at fair",
                "good", result.ratingBreakdown().get("labelOverlaps"));
        assertEquals("fair", result.overallRating());

        List<String> suggestions = result.suggestions();
        Matcher labels = clauseOn(suggestions, "connection labels overlap");
        assertTrue("the label-overlap sentence must carry a severity clause", labels != null);
        assertTrue("the band must still be published as the cap-fair one it is: " + labels.group(3),
                labels.group(3).contains("caps at 'fair'"));
        assertTrue("a tier CAPS a contribution and does not pin it, so a cap-fair metric sitting at"
                + " 'good' contributes 'good' and is NOT what holds this view at 'fair': "
                + labels.group(3),
                labels.group(3).contains("contributes 'good', not the 'fair' this view sits at"));

        Matcher offCanvas = clauseOn(suggestions, "negative or extreme coordinates");
        assertTrue("the off-canvas sentence must carry a severity clause", offCanvas != null);
        assertTrue("the metric that IS at the view's level must be named as one of the limiters: "
                + offCanvas.group(3),
                offCanvas.group(3).contains("one of the metrics holding this view at 'fair'"));
        assertTrue("the cap-fair metric at 'good' must rank BELOW the one actually holding the"
                + " view at 'fair'",
                indexOf(suggestions, "negative or extreme coordinates")
                        < indexOf(suggestions, "connection labels overlap"));
    }

    @Test
    public void shouldLeaveTheViewAtGood_whenACapFairMetricAtGoodIsTheOnlyFinding() {
        // The case that separates a CAP from a PIN. If a cap-fair band pinned rather than capped,
        // two overlapping labels would be enough to drag this view to 'fair'; it rates 'good', and
        // the clause has to agree with that rather than announce the cap as the outcome.
        LayoutAssessmentResult result = capFairMetricAloneAtGoodView();
        assertOnlyNonPassEntriesAre(result, "labelOverlaps");
        assertEquals("the fixture needs the cap-fair metric sitting at good",
                "good", result.ratingBreakdown().get("labelOverlaps"));
        assertEquals("a cap-fair metric at 'good' must leave the view at 'good' — this is the"
                + " difference between a cap and a pin", "good", result.overallRating());

        Matcher clause = clauseOn(result.suggestions(), "connection labels overlap");
        assertTrue("the label-overlap sentence must carry a severity clause", clause != null);
        assertTrue("the band published must still be the cap-fair one: " + clause.group(3),
                clause.group(3).contains("caps at 'fair'"));
        assertTrue("the metric is the only thing marking this view down, so it is one of the"
                + " limiters — at 'good', the level the view actually sits at, never at the 'fair'"
                + " its band caps at: " + clause.group(3),
                clause.group(3).contains("one of the metrics holding this view at 'good'"));
        assertTrue("the clause must not announce the cap as the view's level: " + clause.group(3),
                !clause.group(3).contains("holding this view at 'fair'"));
    }

    @Test
    public void shouldRankOnAMetricTheViewActuallyCarries_wheneverASentenceIsRated() {
        // The CROSS-check the two source scans cannot make between them. One guard proves every
        // rank metric is a real breakdown key; the other proves every explained id is a real
        // registered metric. Neither notices a branch re-pointed at a different-but-real key while
        // the sentence beside it still describes the original finding — both stay green while the
        // sentence sorts by a severity that is not its own.
        //
        // The invariant that closes it is behavioural: every branch that emits a rated sentence
        // fires on a condition that also takes ITS OWN metric off 'pass'. So a sentence whose
        // clause names a metric this view rates 'pass' is a sentence ranking on someone else's
        // severity. This is the same reachability argument the 'view already at the top level' arm
        // of severityClause is documented as guarding against, asserted rather than reasoned about.
        List<LayoutAssessmentResult> corpus = List.of(
                crossingsAndLabelOverlapsView(),
                mixedDiagonalsAndLabelOverlapsView(),
                capFairMetricAtGoodView(),
                capFairMetricAloneAtGoodView(),
                tightSpacingWithUnexplainedFindingView(),
                unalignedView(),
                crowdedHubView(),
                largeViewWithADefect(),
                edgeHugView(LayoutQualityAssessor.EDGE_COINCIDENCE_EGREGIOUS_MAX - 1),
                edgeHugView(LayoutQualityAssessor.EDGE_COINCIDENCE_EGREGIOUS_MAX + 1));

        int checked = 0;
        for (LayoutAssessmentResult result : corpus) {
            for (String sentence : result.suggestions()) {
                Matcher matcher = SEVERITY_CLAUSE.matcher(sentence);
                if (!matcher.find()) {
                    continue;
                }
                String metric = matcher.group(1);
                String live = result.ratingBreakdown().get(metric);
                assertTrue("a rated sentence names '" + metric + "', which this view's breakdown"
                        + " does not carry at all, so the clause is banding a metric the rating"
                        + " model never rated: " + sentence, live != null);
                assertTrue("a rated sentence ranks on '" + metric + "', which this view rates"
                        + " 'pass'. A branch emits its sentence on a condition that also takes its"
                        + " own metric off 'pass', so this sentence is ranking on a metric that is"
                        + " not the one it reports — check the rank metric at its addRated site"
                        + " against the finding the sentence describes: " + sentence,
                        !"pass".equals(live));
                checked++;
            }
        }
        assertTrue("no rated sentence was reached across the whole corpus — this cross-check has"
                + " lost its target and is certifying nothing", checked >= 12);
    }

    // ---- The count-gated band -----------------------------------------------------------------

    @Test
    public void shouldBandEdgeCoincidenceAsCapped_whenTheRunsCountIsBelowTheEscalation() {
        int count = LayoutQualityAssessor.EDGE_COINCIDENCE_EGREGIOUS_MAX - 1;
        LayoutAssessmentResult result = edgeHugView(count);
        assertEquals("the fixture must produce exactly the count it is banding against",
                count, result.connectionEdgeCoincidenceCount());
        assertOnlyNonPassEntriesAre(result, "connectionEdgeCoincidence");

        Matcher clause = clauseOn(result.suggestions(), "hug element edges within");
        assertTrue("the edge-coincidence sentence must carry a severity clause", clause != null);
        assertTrue("below the escalation the metric is capped, so its clause must publish a cap: "
                + clause.group(3), clause.group(3).contains("caps at 'fair'"));
    }

    @Test
    public void shouldBandEdgeCoincidenceAsUncapped_whenTheRunsCountReachesTheEscalation() {
        int count = LayoutQualityAssessor.EDGE_COINCIDENCE_EGREGIOUS_MAX + 1;
        LayoutAssessmentResult belowGate =
                edgeHugView(LayoutQualityAssessor.EDGE_COINCIDENCE_EGREGIOUS_MAX - 1);
        LayoutAssessmentResult result = edgeHugView(count);
        assertEquals("the fixture must produce exactly the count it is banding against",
                count, result.connectionEdgeCoincidenceCount());
        assertOnlyNonPassEntriesAre(result, "connectionEdgeCoincidence");

        Matcher clause = clauseOn(result.suggestions(), "hug element edges within");
        Matcher below = clauseOn(belowGate.suggestions(), "hug element edges within");
        assertTrue("the edge-coincidence sentence must carry a severity clause", clause != null);
        assertTrue("the below-gate run must carry one too", below != null);
        assertTrue("at or above the escalation the metric is uncapped, so its clause must say so"
                + " rather than publishing a cap: " + clause.group(3),
                clause.group(3).startsWith("uncapped"));
        assertTrue("the band itself must differ across the gate. A run whose band did not move is"
                + " a run whose probes were driven at a placeholder count rather than at this"
                + " view's own — which is the whole reason the count is a parameter.",
                !clause.group(2).equals(below.group(2)));
        assertTrue("the escalated metric drives the view past the capped level, so it must now be"
                + " named as one of the limiters: " + clause.group(3),
                clause.group(3).contains("one of the metrics holding this view at 'poor'"));
    }

    // ---- The clause itself --------------------------------------------------------------------

    @Test
    public void shouldKeepEverySeverityClauseShortAndSelfContained_whenEveryFixtureIsRendered() {
        List<LayoutAssessmentResult> corpus = List.of(
                crossingsAndLabelOverlapsView(),
                mixedDiagonalsAndLabelOverlapsView(),
                capFairMetricAtGoodView(),
                tightSpacingWithUnexplainedFindingView(),
                unalignedView(),
                crowdedHubView(),
                edgeHugView(LayoutQualityAssessor.EDGE_COINCIDENCE_EGREGIOUS_MAX + 1));

        int clauses = 0;
        for (LayoutAssessmentResult result : corpus) {
            for (String sentence : result.suggestions()) {
                Matcher matcher = SEVERITY_CLAUSE.matcher(sentence);
                if (!matcher.find()) {
                    continue;
                }
                clauses++;
                String clause = matcher.group();
                assertTrue("a severity clause must stay short enough to read at the end of an"
                        + " already-long sentence (140 characters). Got " + clause.length()
                        + ": " + clause, clause.length() <= 140);
                // auto-layout-and-route republishes this list beside a response carrying no
                // rating breakdown at all, so a pointer at that field would be a pointer to
                // something absent from the response in front of the reader.
                assertTrue("a severity clause must name the metric and the band inline and point"
                        + " at no field: " + clause, !clause.contains("ratingBreakdown"));
                assertTrue("the band must use the published spelling (Tier 1L .. Tier 3R), never"
                        + " the unpublished shorthand: " + clause,
                        matcher.group(2).matches("Tier [1-3][LR]"));
                assertTrue("the clause must state what the band caps the contribution at, or that"
                        + " it caps nothing: " + clause,
                        matcher.group(3).startsWith("caps at '")
                                || matcher.group(3).startsWith("uncapped"));
            }
        }
        assertTrue("no severity clause was rendered anywhere in the corpus — this check has lost"
                + " its target and is certifying nothing", clauses >= 8);
    }

    @Test
    public void shouldRankTheScoreValuedMetrics_whenEachIsTheFindingOnItsOwnView() {
        // The four rating-bearing metrics that are score-valued or unregistered. Each one moves a
        // rating, so each has to rank; a Tier-2L spacing defect sorting below a Tier-3R crossings
        // sentence would be this story's own defect one metric along.
        LayoutAssessmentResult unaligned = unalignedView();
        LayoutAssessmentResult hub = crowdedHubView();
        // Both fixtures are shape-asserted before anything is concluded from them. The hub one
        // names BOTH of its non-pass metrics on purpose: the assertion below reads the view's
        // 'poor', and that 'poor' comes from coincidentSegments, not from the hub-port metric the
        // assertion is about. A pin that left the real driver unnamed would stay green if the
        // driver changed entirely.
        assertOnlyNonPassEntriesAre(unaligned, "alignment");
        assertOnlyNonPassEntriesAre(hub, "hubPortQuality", "coincidentSegments");
        assertEquals("the hub fixture's view level must come from the uncapped metric, or the"
                + " contribution assertion below is measuring something else",
                "poor", hub.ratingBreakdown().get("coincidentSegments"));

        Matcher spacing = clauseOn(tightSpacingWithUnexplainedFindingView().suggestions(),
                "Average spacing is only");
        Matcher alignment = clauseOn(unaligned.suggestions(), "Alignment score is");
        Matcher offCanvas = clauseOn(capFairMetricAtGoodView().suggestions(),
                "negative or extreme coordinates");
        Matcher hubPort = clauseOn(hub.suggestions(), "Hub-port allocation quality is");

        assertTrue("the spacing sentence must rank", spacing != null);
        assertTrue("the alignment sentence must rank", alignment != null);
        assertTrue("the off-canvas sentence must rank", offCanvas != null);
        assertTrue("the hub-port sentence must rank", hubPort != null);

        assertEquals("spacing", spacing.group(1));
        assertEquals("alignment", alignment.group(1));
        assertEquals("offCanvas", offCanvas.group(1));
        assertEquals("hubPortQuality", hubPort.group(1));

        assertTrue("a cap-fair layout metric must not be published as capping at 'good': "
                + spacing.group(3), spacing.group(3).contains("caps at 'fair'"));
        assertTrue("a cap-good layout metric must not be published as capping at 'fair': "
                + alignment.group(3), alignment.group(3).contains("caps at 'good'"));
        assertTrue("a cap-fair routing metric sitting below the view's level must publish its own"
                + " contribution: " + hubPort.group(3),
                hubPort.group(3).contains("contributes 'fair', not the 'poor' this view sits at"));
    }

    // ---- What the production source may and may not hold --------------------------------------

    /** Every metric named as a rank metric at a real {@code addRated} call site. */
    private static Set<String> rankMetricsInSource() {
        Matcher matcher = Pattern.compile("suggestions\\.addRated\\(\"([A-Za-z0-9_]+)\"")
                .matcher(readRepoFile(ASSESSOR_SOURCE));
        Set<String> metrics = new LinkedHashSet<>();
        while (matcher.find()) {
            metrics.add(matcher.group(1));
        }
        return metrics;
    }

    @Test
    public void shouldRankOnlyOnKeysTheLiveBreakdownActuallyCarries() {
        // Read off the real call sites rather than a list typed here: a guard comparing a
        // hand-typed roster against the code is green through the very edit it exists to catch.
        Set<String> ranked = rankMetricsInSource();
        // The measured count, not a slack floor. Every rating-bearing metric that has a sentence
        // ranks, and there are 20 of them — the last two, passThroughs and hubNeighbourCrowding,
        // were added when the metrics that moved a rating while putting no sentence anywhere were
        // given remedies. A floor with slack in it would let an addRated site revert to a bare
        // add — silently demoting a rated sentence into the unranked group — without failing
        // anything. Raising this is what a genuine addition looks like.
        assertEquals("the assessor no longer ranks the number of metrics it did. If a metric was"
                + " legitimately added or removed, move this number in the same commit; if not,"
                + " an addRated site has been demoted to an unranked add and its sentence has"
                + " stopped sorting by its severity.", 20, ranked.size());

        LayoutQualityAssessor.RatingResult clean = assessor.computeRatingWithBreakdown(
                0, 0, 100.0, 100, 0, 0, 0, 0, 10, false,
                0, 0, 0, 0, 0, 0, 0, 1.0, false, 0, 0, 0);
        Set<String> breakdownKeys = clean.breakdown().keySet();

        List<String> unknown = new ArrayList<>();
        for (String metric : ranked) {
            if (!breakdownKeys.contains(metric)) {
                unknown.add(metric);
            }
        }
        assertTrue("these sentences rank on keys the rating breakdown does not carry, so they can"
                + " publish no band and sort at the bottom of the rated group instead of where"
                + " their severity puts them: " + unknown, unknown.isEmpty());
    }

    @Test
    public void shouldDeriveEveryBandRatherThanHoldingATableOfThem() {
        String source = readRepoFile(ASSESSOR_SOURCE);
        String withoutComments = source
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
        Matcher literal = Pattern.compile("\"\\s*Tier\\s*[1-3][LR]").matcher(withoutComments);
        assertTrue("the assessor holds a literal band spelling in code. The bands must be measured"
                + " by driving computeLayoutTierLevel / computeRoutingTierLevel one metric at a"
                + " time — a fourth hand-maintained copy of the table would drift in step with"
                + " nothing, and the clause would then disagree with the rating that produced it.",
                !literal.find());
    }

    @Test
    public void shouldLeaveTheExplainedRecordingsAloneOnEveryScoreValuedMetric() {
        // The rank metric and the explained id answer different questions. Recording one of these
        // four as explained would suppress the terminal disclosure for a registered finding — a
        // behaviour change to a different feature, reached through this one.
        String source = readRepoFile(ASSESSOR_SOURCE);
        Set<String> ranked = rankMetricsInSource();
        for (String metric : List.of("spacing", "alignment", "offCanvas", "hubPortQuality")) {
            assertTrue("'" + metric + "' moves a rating and must carry a rank metric",
                    ranked.contains(metric));
            assertTrue("'" + metric + "' is score-valued or unregistered and must NOT be recorded"
                    + " as explained: doing so tells the terminal disclosure a finding was"
                    + " accounted for by prose that never named it",
                    !source.contains("explained.add(\"" + metric + "\")"));
        }
    }

    @Test
    public void shouldKeepTheMultiIdBranchesRecordingEveryIdTheyRecorded() {
        String source = readRepoFile(ASSESSOR_SOURCE);
        // One sentence can account for several registered findings, and one finding can be
        // accounted for by a branch that adds no sentence at all. A rank metric is one value per
        // sentence and can subsume neither shape.
        for (String id : List.of("nonOrthogonalTerminals", "nonOrthogonalTerminalsZeroBendpoint",
                "nonOrthogonalTerminalsRouted", "connectionEdgeCoincidence",
                "edgeCoincidenceGrazedElements", "cousinOverlaps")) {
            assertTrue("the recording of '" + id + "' has gone missing; the terminal disclosure"
                    + " will now name it beside the sentence that already explained it",
                    source.contains("explained.add(\"" + id + "\")"));
        }
    }

    // ---- The published surfaces ---------------------------------------------------------------

    @Test
    public void shouldPublishTheOrderingContractInTheServedDescription() {
        String served = servedDescriptionOf("assess-layout");
        String anchor = "SUGGESTION ORDER: ";
        int first = served.indexOf(anchor);
        assertTrue("the served assess-layout description no longer enumerates how `suggestions`"
                + " is ordered. Re-point this check at whatever now publishes it rather than"
                + " deleting it — an order the reader cannot account for is indistinguishable"
                + " from the arbitrary one it replaced.", first >= 0);
        assertEquals("the ordering contract is published twice in one description, so the two"
                + " copies can drift apart", first, served.lastIndexOf(anchor));

        int end = served.indexOf("\n\n", first);
        String region = served.substring(first, end < 0 ? served.length() : end);
        for (String claim : List.of("worst CAPPED CONTRIBUTION first", "caps its contribution at",
                "auto-layout-and-route and adjust-view-spacing republish")) {
            assertTrue("the ordering block must state \"" + claim + "\"; it says: " + region,
                    region.contains(claim));
        }
        // The SCOPE is asserted as presence of the qualifiers, never as absence of a phrase: an
        // absence probe fires on the corrected sentence as readily as on the refuted one. Two
        // qualifiers, because the unscoped claim is false on two unrelated paths — a view that is
        // not rated at all, and a view whose limiter is a metric whose own remedy threshold was
        // not crossed, so the ordering has no sentence of that metric's to rank.
        for (String qualifier : List.of("on a RATED view", "DEGENERATE view",
                "ONLY PAST A REMEDY THRESHOLD")) {
            assertTrue("the ordering block claims more than the code delivers unless it keeps the"
                    + " qualifier \"" + qualifier + "\". Without it the block promises ordering and"
                    + " clauses on responses that carry neither. It says: " + region,
                    region.contains(qualifier));
        }
    }

    @Test
    public void shouldPublishTheOrderingContractInTheLayoutEngineDoc() {
        String doc = readRepoFile(LAYOUT_ENGINE);
        String anchor = "### Suggestion Generation";
        int first = doc.indexOf(anchor);
        assertTrue("docs/layout-engine.md no longer carries a '" + anchor + "' section. Re-point"
                + " this check at whatever now documents the list rather than deleting it.",
                first >= 0);
        assertEquals("the section heading appears more than once, so a reader cannot tell which"
                + " copy is authoritative", first, doc.lastIndexOf(anchor));

        int end = doc.indexOf("\n### ", first + anchor.length());
        String region = doc.substring(first, end < 0 ? doc.length() : end);
        for (String claim : List.of("ordered by measured severity",
                "worst **capped contribution** first",
                "A tier CAPS a metric's contribution; it does not pin it",
                "derived, never tabulated",
                // The same two scope qualifiers the served description carries, for the same
                // reason: the section documents a contract that does not hold on an unrated view,
                // and cannot rank a metric that puts no sentence in the list.
                "A degenerate view is outside this contract",
                "ranks the sentences that exist",
                "only past a remedy threshold of their own")) {
            assertTrue("the '" + anchor + "' section must state \"" + claim + "\". A whole-file"
                    + " scan is not acceptable here: a sibling guard in this repo once stayed"
                    + " green through the deletion it existed to catch, because the corrected"
                    + " sentence it matched lived three sections away from the stale one.",
                    region.contains(claim));
        }
        assertTrue("the stale pre-ordering bullet list must not be all this section says",
                region.contains("| group | contents |"));
    }

    @Test
    public void shouldNotUseUnpublishedTierShorthandInAnyClause() {
        for (LayoutAssessmentResult result : List.of(
                crossingsAndLabelOverlapsView(), crowdedHubView(), unalignedView())) {
            for (String sentence : result.suggestions()) {
                Matcher matcher = SEVERITY_CLAUSE.matcher(sentence);
                if (!matcher.find()) {
                    continue;
                }
                if (Pattern.compile("\\b[LR][1-3]\\b").matcher(matcher.group()).find()) {
                    fail("a clause used the unpublished tier shorthand, which resolves for nobody"
                            + " outside this project: " + matcher.group());
                }
            }
        }
    }
}
