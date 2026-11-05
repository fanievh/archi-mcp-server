package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * A connection that draws no line passes through nothing. A self-connection stored without
 * bendpoints has both ends on its element's centre, so its clipped path is one point twice and
 * Archi draws no line for it; tested as a path, that point lies inside any box over the centre
 * and read as a route through it. The geometry is the one a live probe on Archi 5.10 measured: an
 * Application Component at [100,100,120,60] with an Association to itself and a sibling box at
 * [140,110,60,40] over its centre (160,130).
 *
 * <p>Such a connection is not tested at all, so the pass-through dimension says it could not be
 * certified on that run, as the note and image dimensions already do.</p>
 */
public class UndrawnConnectionPassThroughTest {

    private static final List<double[]> CENTRE_TWICE =
            List.of(new double[] {160, 130}, new double[] {160, 130});

    @Test
    public void bareSelfConnection_shouldNotPassThroughASiblingOverItsCentre() {
        LayoutAssessmentResult result = new LayoutQualityAssessor().assess(
                List.of(node("a", 100, 100, 120, 60), node("sibling", 140, 110, 60, 40)),
                List.of(new AssessmentConnection("assoc", "a", "a", CENTRE_TWICE, "", 1)), true);

        assertEquals(List.of(), result.connectionPassThroughs());
        assertEquals(0, result.crossElementPassThroughCount());
        assertEquals("pass", result.ratingBreakdown().get("passThroughs"));
    }

    @Test
    public void bareSelfConnection_shouldLeaveThePassThroughDimensionPartial_andSayWhy() {
        LayoutAssessmentResult result = new LayoutQualityAssessor().assess(
                List.of(node("a", 100, 100, 120, 60), node("sibling", 140, 110, 60, 40)),
                List.of(new AssessmentConnection("assoc", "a", "a", CENTRE_TWICE, "", 1)), true);

        assertEquals(LayoutQualityAssessor.COVERAGE_PARTIAL, result.coverage().get("connectionPassThroughs"));
        assertTrue(String.valueOf(LayoutQualityAssessor.contextualPartialDimensions(result.coverage())),
                LayoutQualityAssessor.contextualPartialDimensions(result.coverage()).contains("connectionPassThroughs"));
        String sentence = "The connectionPassThroughs dimension could not be fully examined on this run, because "
                + "a connection on this view draws no line, its two ends landing on one point (in practice a"
                + " self-connection with no bendpoints), so it was not tested for passing through an element."
                + " That dimension is declared \"partial\" rather than \"checked\" in assess-layout's coverage"
                + " map, so its count is honestly zero yet certifies nothing — render-verify it before treating"
                + " this view as clean on that dimension.";
        assertTrue(String.valueOf(result.suggestions()), result.suggestions().contains(sentence));
    }

    /** The gate is on the drawn line, not on source == target: Archi's loop draws one. */
    @Test
    public void defaultLoopSelfConnection_shouldStillPassThroughASiblingOnItsLine() {
        // Archi's palette loop for [100,100,120,60]: offsets (0,60), (100,60), (100,0) off the
        // centre, so the line runs down to (160,190), across to (260,190) and up to (260,130). The
        // sibling insets to [190,230) x [180,200), which holds y = 190.
        List<double[]> loop = List.of(new double[] {160, 130}, new double[] {160, 190},
                new double[] {260, 190}, new double[] {260, 130}, new double[] {160, 130});
        LayoutAssessmentResult result = new LayoutQualityAssessor().assess(
                List.of(node("a", 100, 100, 120, 60), node("sibling", 180, 170, 60, 40)),
                List.of(new AssessmentConnection("loop", "a", "a", loop, "", 1)), true);

        assertEquals(List.of("Connection 'loop' passes through element 'sibling'"),
                result.connectionPassThroughs());
        assertEquals(1, result.crossElementPassThroughCount());
        assertEquals(LayoutQualityAssessor.COVERAGE_CHECKED, result.coverage().get("connectionPassThroughs"));
    }

    private static AssessmentNode node(String id, double x, double y, double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, false, false, id, 0.0, null, null, 0.0,
                0.0, 0.0, false, null, false, AssessmentNode.TEXT_ALIGNMENT_CENTRE,
                AssessmentNode.TEXT_POSITION_TOP);
    }
}
