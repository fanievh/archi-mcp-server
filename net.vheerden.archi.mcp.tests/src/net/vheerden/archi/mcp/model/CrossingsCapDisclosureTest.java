package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Pins the sentence {@code assess-layout} adds when the Tier 3R cap, and only the cap, set a
 * {@code good} headline over crossings that measure worse: the measured band, the count, the ratio
 * and a pointer to the guidance. No rating moves; the sentence says what the headline cannot.
 *
 * <p>The fixture is an orthogonal lattice: {@code n} horizontal connections between a left and a
 * right column, crossed by {@code n} vertical connections between a top and a bottom row, so it
 * carries exactly {@code n × n} crossings and nothing else. Nodes are unnamed, so nothing measures
 * text.</p>
 */
public class CrossingsCapDisclosureTest {

    private static final String OPENING = "edgeCrossings measures '";

    private final LayoutQualityAssessor assessor = new LayoutQualityAssessor();

    private static AssessmentNode node(String id, double x, double y) {
        return new AssessmentNode(id, x, y, 120, 60, null, false, false, null, 0.0, null, null, 0.0, 0.0, 0.0);
    }

    private static AssessmentNode note(String id, double x, double y, double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, false, true, "note", 0.0, null, null, 0.0, 0.0, 0.0);
    }

    private final List<AssessmentNode> nodes = new ArrayList<>();
    private final List<AssessmentConnection> conns = new ArrayList<>();

    private void lattice(int n) {
        double right = 300 + n * 250 + 200;
        for (int i = 0; i < n; i++) {
            double y = 200 + i * 200;
            nodes.add(node("l" + i, 0, y));
            nodes.add(node("r" + i, right, y));
            conns.add(new AssessmentConnection("h" + i, "l" + i, "r" + i,
                    List.of(new double[] {60, y + 30}, new double[] {right + 60, y + 30}), "", 1));
            double x = 300 + i * 250;
            double bottom = 200 + n * 200 + 200;
            nodes.add(node("t" + i, x, 0));
            nodes.add(node("b" + i, x, bottom));
            conns.add(new AssessmentConnection("v" + i, "t" + i, "b" + i,
                    List.of(new double[] {x + 60, 30}, new double[] {x + 60, bottom + 30}), "", 1));
        }
    }

    private LayoutAssessmentResult assess() {
        return assessor.assess(nodes, conns, false);
    }

    private static List<String> capSentences(LayoutAssessmentResult result) {
        return result.suggestions().stream().filter(s -> s.startsWith(OPENING)).toList();
    }

    @Test
    public void shouldSayTheCapHidFairCrossings_withTheCountTheRatioAndThePointer() {
        lattice(5);
        LayoutAssessmentResult result = assess();

        assertEquals(25, result.edgeCrossingCount());
        assertEquals("fair", result.ratingBreakdown().get("edgeCrossings"));
        assertEquals("good", result.overallRating());
        assertEquals(List.of("edgeCrossings measures 'fair' (25 crossings, 2.5 per connection), but Tier 3R"
                + " caps its effect at 'good', so the 'good' headline does not show it: it is the only"
                + " metric holding this view at 'good'. Crossings in a dense many-to-many view are often"
                + " structurally unavoidable; the levers are the order and spacing of the elements and"
                + " groups, not another re-route. See Connection Routing in"
                + " archimate://reference/archimate-view-patterns. Read that URI as an MCP resource, or call"
                + " get-guidance with it if your client does not expose resources."), capSentences(result));
    }

    @Test
    public void shouldSayTheCapHidPoorCrossings() {
        lattice(9);
        LayoutAssessmentResult result = assess();

        assertEquals("poor", result.ratingBreakdown().get("edgeCrossings"));
        assertEquals("good", result.overallRating());
        List<String> cap = capSentences(result);
        assertEquals(result.suggestions().toString(), 1, cap.size());
        assertTrue(cap.get(0), cap.get(0).startsWith("edgeCrossings measures 'poor' (81 crossings, 4.5 per"
                + " connection)"));
    }

    @Test
    public void shouldSayNothing_whenTheCrossingsMeasureGood() {
        lattice(4);
        LayoutAssessmentResult result = assess();

        assertEquals("good", result.ratingBreakdown().get("edgeCrossings"));
        assertEquals(List.of(), capSentences(result));
    }

    /** connectionThroughNote also holds the view at 'good', so the headline would read 'good' without the cap. */
    @Test
    public void shouldSayNothing_whenAnotherMetricAlsoHoldsTheViewAtGood() {
        lattice(5);
        nodes.add(note("n1", 120, 210, 60, 40));
        LayoutAssessmentResult result = assess();

        assertEquals("fair", result.ratingBreakdown().get("edgeCrossings"));
        assertEquals("good", result.ratingBreakdown().get("connectionThroughNote"));
        assertEquals("good", result.overallRating());
        assertEquals(List.of(), capSentences(result));
    }

    /** A diagonal line apart from the lattice leaves a Tier 2R metric at 'good' too, so the cap hid nothing. */
    @Test
    public void shouldSayNothing_whenATier2RMetricAlsoHoldsTheViewAtGood() {
        lattice(5);
        nodes.add(node("d1", 5000, 0));
        nodes.add(node("d2", 5400, 300));
        conns.add(new AssessmentConnection("diag", "d1", "d2",
                List.of(new double[] {5060, 30}, new double[] {5460, 330}), "", 1));
        LayoutAssessmentResult result = assess();

        assertEquals("fair", result.ratingBreakdown().get("edgeCrossings"));
        assertEquals("good", result.overallRating());
        assertEquals(result.ratingBreakdown().toString(), "good",
                result.ratingBreakdown().get("nonOrthogonalTerminals"));
        assertEquals(List.of(), capSentences(result));
    }

    @Test
    public void shouldSayNothing_whenTheHeadlineIsWorseThanGood() {
        lattice(5);
        nodes.add(node("o1", 3000, 0));
        nodes.add(node("o2", 3020, 20));
        LayoutAssessmentResult result = assess();

        assertEquals("fair", result.ratingBreakdown().get("edgeCrossings"));
        assertTrue(result.overallRating(), !"good".equals(result.overallRating())
                && !"excellent".equals(result.overallRating()));
        assertEquals(List.of(), capSentences(result));
    }

    /** The sentence is prose only: the ratings it describes are the ones the lattice earns without it. */
    @Test
    public void shouldNotDiscloseTheCrossingsAsUnexplained() {
        lattice(5);
        String prose = String.join("\n", assess().suggestions());

        assertTrue(prose, !prose.contains("edgeCrossings (25)"));
        assertTrue(prose, !prose.contains("No defects were found"));
    }
}
