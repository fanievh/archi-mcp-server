package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.model.routing.RoutingResult;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * {@link RoutedAssessmentPaths#overlay} judges a routing candidate with the label state the connection
 * will hold after apply: the candidate's own text position, and the Label Offset anchor the pass writes
 * for it where it may write one, else the anchor the connection holds. Pure: no EMF, no SWT.
 */
public class RoutedAssessmentPathsTest {

    private static final int NORTH = 1;
    private static final int EAST = 16;
    private static final int CENTER = RelativePositionFeature.CENTER;

    private final LayoutQualityAssessor assessor = new LayoutQualityAssessor();

    private static RoutingPipeline.ConnectionEndpoints endpoints(RoutingRect source, RoutingRect target,
            String label, int textPosition, int anchor) {
        return new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(), label, textPosition,
                List.of(), anchor);
    }

    private static RoutingResult candidate(List<AbsoluteBendpointDto> bendpoints, Map<String, Integer> positions) {
        return candidate(bendpoints, positions, Map.of());
    }

    private static RoutingResult candidate(List<AbsoluteBendpointDto> bendpoints, Map<String, Integer> positions,
            Map<String, Integer> anchors) {
        return new RoutingResult(Map.of("c1", bendpoints), List.of(), List.of(), Map.of(), positions.size(),
                positions, 0, 0, Set.of(), null, anchors);
    }

    private static AbsoluteBendpointDto bp(int x, int y) {
        return new AbsoluteBendpointDto(x, y);
    }

    private static AssessmentNode node(String id, double x, double y, double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, false, false, null, 0.0, null, null, 0.0, 0.0, 0.0);
    }

    // ---- the fields the overlay carries ----

    /**
     * The path ends at each element's exact centre, as the collector's read-back does. An element
     * of odd size has a centre on a half pixel; the integer centre sits half a pixel off it, so a
     * route entering from below and ending there had already passed the centre, and the assessor
     * charged it as running through its own target. The geometry is a captured one: a 206 x 55
     * target at (484, 434), entered from below.
     */
    @Test
    public void overlay_endsThePathAtTheExactCentre_soAnOddSizedEndIsNotCrossed() {
        RoutingRect s = new RoutingRect(874, 137, 214, 68, "s");
        RoutingRect t = new RoutingRect(484, 434, 206, 55, "t");
        List<RoutingPipeline.ConnectionEndpoints> batch = List.of(endpoints(s, t, "", 1, CENTER));

        AssessmentConnection c = RoutedAssessmentPaths.overlay(batch,
                candidate(List.of(bp(809, 171), bp(809, 541), bp(587, 541)), Map.of()),
                RoutedAssessmentPaths.LabelInputs.HELD_ANCHORS).get(0);

        double[] end = c.pathPoints().get(c.pathPoints().size() - 1);
        assertEquals(587.0, end[0], 0.0);
        assertEquals(461.5, end[1], 0.0);
        assertEquals(981.0, c.pathPoints().get(0)[0], 0.0);
        assertEquals(171.0, c.pathPoints().get(0)[1], 0.0);
        LayoutAssessmentResult r = assessor.assess(
                List.of(node("s", 874, 137, 214, 68), node("t", 484, 434, 206, 55)), List.of(c), false);
        assertEquals(r.connectionPassThroughs().toString(), 0, r.ownEndpointPassThroughCount());
    }

    @Test
    public void overlay_usesTheCandidatesChangedTextPosition_elseTheCurrentOne() {
        RoutingRect s = new RoutingRect(0, 0, 40, 40, "s");
        RoutingRect t = new RoutingRect(400, 0, 40, 40, "t");
        List<RoutingPipeline.ConnectionEndpoints> batch = List.of(endpoints(s, t, "x", 1, CENTER));

        AssessmentConnection moved = RoutedAssessmentPaths.overlay(batch,
                candidate(List.of(), Map.of("c1", 0)), RoutedAssessmentPaths.LabelInputs.HELD_ANCHORS).get(0);
        AssessmentConnection kept = RoutedAssessmentPaths.overlay(batch,
                candidate(List.of(), Map.of()), RoutedAssessmentPaths.LabelInputs.HELD_ANCHORS).get(0);

        assertEquals("the optimizer moved it to Source", 0, moved.textPosition());
        assertEquals("unchanged: the current Middle", 1, kept.textPosition());
    }

    @Test
    public void overlay_usesTheHeldAnchor_whenNoOffsetIsWritten() {
        RoutingRect s = new RoutingRect(0, 0, 40, 40, "s");
        RoutingRect t = new RoutingRect(400, 0, 40, 40, "t");
        List<RoutingPipeline.ConnectionEndpoints> batch = List.of(endpoints(s, t, "x", 1, EAST));

        AssessmentConnection c = RoutedAssessmentPaths.overlay(batch,
                candidate(List.of(), Map.of()), RoutedAssessmentPaths.LabelInputs.HELD_ANCHORS).get(0);

        assertEquals(EAST, c.relativePosition());
    }

    /**
     * The candidate's label pick chose NORTH for the Middle label. Whether the scorer credits it depends only
     * on the write rule.
     */
    @Test
    public void overlay_creditsTheAnchorThePickChose_onlyWhereItMayWriteOne() {
        RoutingRect s = new RoutingRect(0, 10, 100, 30, "s");
        RoutingRect t = new RoutingRect(120, 10, 100, 30, "t");
        List<RoutingPipeline.ConnectionEndpoints> batch = List.of(endpoints(s, t, "Accesses", 1, CENTER));
        RoutingResult winner = candidate(List.of(bp(100, 25), bp(120, 25)), Map.of(), Map.of("c1", NORTH));

        AssessmentConnection writable = RoutedAssessmentPaths.overlay(batch, winner,
                new RoutedAssessmentPaths.LabelInputs(Set.of("c1"))).get(0);
        AssessmentConnection notWritable = RoutedAssessmentPaths.overlay(batch, winner,
                new RoutedAssessmentPaths.LabelInputs(Set.of("other"))).get(0);

        assertEquals("the pick chose NORTH, so the candidate is judged with it", NORTH,
                writable.relativePosition());
        assertEquals("a hidden label, or a platform without the feature: nothing ships, held Centre",
                CENTER, notWritable.relativePosition());
    }

    /**
     * A held EAST the candidate's pick reset to Centre: the candidate is judged at Centre, not at the EAST it
     * holds today.
     */
    @Test
    public void overlay_judgesAResetHeldOffsetAtCentre() {
        RoutingRect s = new RoutingRect(0, 0, 100, 50, "s");
        RoutingRect t = new RoutingRect(500, 0, 100, 50, "t");
        List<RoutingPipeline.ConnectionEndpoints> batch = List.of(endpoints(s, t, "Accesses", 1, EAST));
        RoutingResult winner = candidate(List.of(bp(100, 25), bp(500, 25)), Map.of(), Map.of("c1", CENTER));

        AssessmentConnection reset = RoutedAssessmentPaths.overlay(batch, winner,
                new RoutedAssessmentPaths.LabelInputs(Set.of("c1"))).get(0);
        AssessmentConnection held = RoutedAssessmentPaths.overlay(batch, winner,
                new RoutedAssessmentPaths.LabelInputs(Set.of())).get(0);

        assertEquals("the pass writes Centre over the stale EAST", CENTER, reset.relativePosition());
        assertEquals("where no offset is written the held EAST ships", EAST, held.relativePosition());
    }

    // ---- the ranking follows the post-apply label state ----

    /**
     * One connection holding EAST, two candidate routes. A runs straight along y=20, B dips to y=300. Box
     * X sits right of A's label where its EAST offset draws it; box Z covers the left half of B's un-offset
     * label but stops 20 px short of where EAST draws it (the label count also counts a near-miss within
     * 5 px, so the gap is kept well clear of that). Judged at Centre (the overlay before this
     * change) A is clear and B is on Z, so A ranks first. Judged with the held EAST that both will ship
     * with, A's label is on X and B's is clear, so B ranks first. Box positions come from the assessor's
     * own label box, so they do not depend on its size estimate.
     */
    @Test
    public void ranking_followsTheHeldAnchor_notCentre() {
        RoutingRect s = new RoutingRect(0, 0, 40, 40, "s");
        RoutingRect t = new RoutingRect(1000, 0, 40, 40, "t");
        RoutingPipeline.ConnectionEndpoints ep = endpoints(s, t, "accesses the ledger", 1, EAST);
        RoutingResult a = candidate(List.of(bp(40, 20), bp(1000, 20)), Map.of());
        RoutingResult b = candidate(List.of(bp(20, 300), bp(1020, 300)), Map.of());

        LayoutQualityAssessor.LabelBounds la = assessor.drawnLabelBounds(
                RoutedAssessmentPaths.overlay(List.of(endpoints(s, t, "accesses the ledger", 1, CENTER)),
                        a, RoutedAssessmentPaths.LabelInputs.HELD_ANCHORS).get(0));
        LayoutQualityAssessor.LabelBounds lb = assessor.drawnLabelBounds(
                RoutedAssessmentPaths.overlay(List.of(endpoints(s, t, "accesses the ledger", 1, CENTER)),
                        b, RoutedAssessmentPaths.LabelInputs.HELD_ANCHORS).get(0));
        double w = la.width();
        List<AssessmentNode> nodes = List.of(
                node("s", 0, 0, 40, 40), node("t", 1000, 0, 40, 40),
                node("x", la.x() + w + 10, la.y() - 20, 60, la.height() + 40),
                node("z", lb.x() - 20, lb.y() - 20, w / 2 + 5, lb.height() + 40));

        int aAtCentre = labelOverlaps(nodes, ep, a, true);
        int bAtCentre = labelOverlaps(nodes, ep, b, true);
        int aHeld = labelOverlaps(nodes, ep, a, false);
        int bHeld = labelOverlaps(nodes, ep, b, false);

        assertTrue("at Centre A ranks first: A=" + aAtCentre + " B=" + bAtCentre, aAtCentre < bAtCentre);
        assertTrue("with the held EAST B ranks first: A=" + aHeld + " B=" + bHeld, bHeld < aHeld);
    }

    /** {@code atCentre} reproduces the overlay before it carried the anchor. */
    private int labelOverlaps(List<AssessmentNode> nodes, RoutingPipeline.ConnectionEndpoints ep,
            RoutingResult candidate, boolean atCentre) {
        RoutingPipeline.ConnectionEndpoints judged = atCentre
                ? endpoints(ep.source(), ep.target(), ep.labelText(), ep.textPosition(), CENTER) : ep;
        return assessor.assess(nodes, RoutedAssessmentPaths.overlay(List.of(judged), candidate,
                RoutedAssessmentPaths.LabelInputs.HELD_ANCHORS), false).labelOverlapCount();
    }
}
