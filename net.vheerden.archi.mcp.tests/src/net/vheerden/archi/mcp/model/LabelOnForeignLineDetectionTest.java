package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import net.vheerden.archi.mcp.model.routing.ForeignLineHits;

/**
 * The informational {@code labelOnForeignLine} metric: a connection label drawn on another connection's line.
 *
 * <p>The base fixture: a labelled connection l from A (0, 400) to B (500, 400), both 100 x 60, a straight line
 * along y 430. Its 12-character label is 139.6 x 20 and sits at Middle on (300, 430), x 230.2..369.8, y
 * 420..440, so the box a line must enter is x 240.2..359.8, y 426.67..433.33. An unlabelled connection f runs
 * straight down at x 300 from c (270, 100) to d (270, 700), through the label.</p>
 *
 * <p>Fixture labels are estimated, not measured, so nothing reaches the SWT text-measurement path.</p>
 */
public class LabelOnForeignLineDetectionTest {

    private static final int MIDDLE = 1;
    private static final int TARGET = 2;
    private static final int EAST = 16;

    private final LayoutQualityAssessor assessor = new LayoutQualityAssessor();

    // ==================== what is counted ====================

    /** The detector's foreign-line box is the box the label-overlap detector shrinks, so the two move together. */
    @Test
    public void insetIsTheLabelOverlapInset() {
        assertEquals(LayoutQualityAssessor.LABEL_OVERLAP_INSET,
                ForeignLineHits.LABEL_INSET, 0.0);
    }

    @Test
    public void aLabelOnAnUnlabelledLine_isOne_namingBothConnections() {
        LayoutAssessmentResult result = assessor.assess(baseNodes(), List.of(l(MIDDLE, 2), f()), true);

        assertEquals(1, result.labelOnForeignLineCount());
        assertEquals(List.of("Label on connection 'l' lies on the line of connection 'f'"),
                result.labelOnForeignLineDescriptions());
        assertEquals(Set.of("l"), result.violatorIds().get("labelOnForeignLine"));
        assertEquals("checked", result.coverage().get("labelOnForeignLine"));
    }

    @Test
    public void violatorKey_isAbsent_whenNotRequested() {
        LayoutAssessmentResult result = assessor.assess(baseNodes(), List.of(l(MIDDLE, 2), f()), false);

        assertEquals(1, result.labelOnForeignLineCount());
        assertFalse(result.violatorIds() != null && result.violatorIds().containsKey("labelOnForeignLine"));
    }

    @Test
    public void aLineCrossingTheLabelTwice_isCountedOnce() {
        // f bends back through the label: down at x 260, across at y 600, back up at x 340 to e above.
        List<AssessmentNode> nodes = new ArrayList<>(baseNodes());
        nodes.add(node("e", 310, 100, 60, 60));
        AssessmentConnection sBend = conn("f", "c", "e", "", MIDDLE, 2,
                pt(300, 130), pt(260, 130), pt(260, 600), pt(340, 600), pt(340, 130));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(l(MIDDLE, 2), sBend), true);

        assertEquals(1, result.labelOnForeignLineCount());
    }

    // ==================== where the label is drawn ====================

    @Test
    public void anOffsetLiftingTheLabelOffTheLine_isZero() {
        // EAST moves the Middle box 74 px right: x 304.2..443.8, its inner box from x 314.2, clear of x 300.
        LayoutAssessmentResult result = assessor.assess(baseNodes(), List.of(l(MIDDLE, EAST), f()), true);

        assertEquals(0, result.labelOnForeignLineCount());
    }

    @Test
    public void theSameOffsetLiftingTheLabelOntoASecondLine_isOne() {
        // A second unlabelled line at x 400 misses the unmoved box (inner x 240.2..359.8) and enters the moved one.
        List<AssessmentNode> nodes = new ArrayList<>(baseNodes());
        nodes.add(node("c2", 370, 100, 60, 60));
        nodes.add(node("d2", 370, 700, 60, 60));
        AssessmentConnection f2 = conn("f2", "c2", "d2", "", MIDDLE, 2, pt(400, 130), pt(400, 730));

        LayoutAssessmentResult moved = assessor.assess(nodes, List.of(l(MIDDLE, EAST), f(), f2), true);
        LayoutAssessmentResult unmoved = assessor.assess(nodes, List.of(l(MIDDLE, 2), f(), f2), true);

        assertEquals(List.of("Label on connection 'l' lies on the line of connection 'f2'"),
                moved.labelOnForeignLineDescriptions());
        assertEquals(List.of("Label on connection 'l' lies on the line of connection 'f'"),
                unmoved.labelOnForeignLineDescriptions());
    }

    /**
     * The hub case: a Target label on a WEST end hangs below the line, x 346.4..486, y 454..474 (inner box y
     * 460.67..467.33), where a sibling leaving the same face 12 px lower runs through it.
     */
    @Test
    public void aTargetLabelOnAWestEnd_crossedByASiblingLeavingTheSameFace_isOne() {
        assertEquals(1, hub(462).labelOnForeignLineCount());
        assertEquals(List.of("Label on connection 'l' lies on the line of connection 's'"),
                hub(462).labelOnForeignLineDescriptions());
    }

    @Test
    public void aSiblingInTheLabelsEdgeBand_isZero() {
        // y 470 is inside the drawn box (to 474) but below its inner box (to 467.33).
        assertEquals(0, hub(470).labelOnForeignLineCount());
    }

    // ==================== which lines count ====================

    @Test
    public void aSelfConnectionWithNoBendpoints_drawsNothing_isZero() {
        // s sits wholly inside the label's inner box, so wherever its degenerate line lands it is inside.
        List<AssessmentNode> nodes = new ArrayList<>(baseNodes().subList(0, 2));
        nodes.add(node("s", 296, 428, 8, 4));
        AssessmentConnection self = conn("self", "s", "s", "", MIDDLE, 2, pt(300, 430), pt(300, 430));

        LayoutAssessmentResult result = assessor.assess(nodes, List.of(l(MIDDLE, 2), self), true);

        assertEquals(0, result.labelOnForeignLineCount());
    }

    @Test
    public void aLabelledLineWhoseLabelIsElsewhere_stillCounts() {
        // f's own label hangs at its Source end, below c, far from l's line.
        AssessmentConnection named = conn("f", "c", "d", "routes cash", 0, 2, pt(300, 130), pt(300, 730));

        LayoutAssessmentResult result = assessor.assess(baseNodes(), List.of(l(MIDDLE, 2), named), true);

        assertEquals(List.of("Label on connection 'l' lies on the line of connection 'f'"),
                result.labelOnForeignLineDescriptions());
    }

    /**
     * The label's own line never counts: a U-turn route puts its Middle label on the corner (150, 480), with
     * two of its own segments running through it.
     */
    @Test
    public void aLabelOnItsOwnUTurn_isZero() {
        AssessmentConnection uTurn = conn("l", "A", "B", "sends orders", MIDDLE, 2,
                pt(50, 430), pt(450, 430), pt(450, 480), pt(150, 480), pt(150, 550), pt(550, 550), pt(550, 430));

        LayoutAssessmentResult result = assessor.assess(baseNodes().subList(0, 2), List.of(uTurn), true);

        assertEquals(0, result.labelOnForeignLineCount());
    }

    @Test
    public void degenerateDto_defaultsToZeroAndNull() {
        var dto = net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto.degenerate(
                "v1", 1, 0, 0, List.of(), List.of(), 0, List.of(), 0, List.of(),
                0, List.of(), List.of(), java.util.Map.of(), List.of());

        assertEquals(0, dto.labelOnForeignLineCount());
        assertNull(dto.labelOnForeignLineDescriptions());
    }

    // ==================== fixture ====================

    /** l from X (0, 420) to the hub H (500, 400, 100 x 100) at Target, and a sibling s leaving H's west face at y. */
    private LayoutAssessmentResult hub(int siblingY) {
        List<AssessmentNode> nodes = List.of(node("X", 0, 420, 100, 60), node("H", 500, 400, 100, 100),
                node("W", 390, 700, 60, 60));
        AssessmentConnection l = conn("l", "X", "H", "sends orders", TARGET, 2, pt(50, 450), pt(550, 450));
        AssessmentConnection s = conn("s", "H", "W", "", MIDDLE, 2,
                pt(550, 450), pt(420, siblingY), pt(420, 730));
        return assessor.assess(nodes, List.of(l, s), true);
    }

    // ==================== the remedies ====================

    /** Both label remedies name the pass that moves labels and keeps every route. */
    @Test
    public void theLabelRemedies_nameTheLabelsOnlyMode() {
        LayoutAssessmentResult foreign = assessor.assess(baseNodes(), List.of(l(MIDDLE, 2), f()), true);
        List<AssessmentNode> boxed = new java.util.ArrayList<>(baseNodes());
        boxed.add(node("x", 280, 415, 40, 30));
        LayoutAssessmentResult overlap = assessor.assess(boxed, List.of(l(MIDDLE, 2)), true);

        assertTrue(foreign.suggestions().toString(), foreign.suggestions().stream().anyMatch(s ->
                s.contains("another connection's line") && s.contains("auto-route-connections with mode='labels-only'")));
        assertTrue(overlap.labelOverlapCount() > 0);
        assertTrue(overlap.suggestions().toString(), overlap.suggestions().stream().anyMatch(s ->
                s.contains("connection labels overlap") && s.contains("auto-route-connections with mode='labels-only'")));
    }

    private static List<AssessmentNode> baseNodes() {
        return List.of(node("A", 0, 400, 100, 60), node("B", 500, 400, 100, 60),
                node("c", 270, 100, 60, 60), node("d", 270, 700, 60, 60));
    }

    private static AssessmentConnection l(int textPosition, int relativePosition) {
        return conn("l", "A", "B", "sends orders", textPosition, relativePosition, pt(50, 430), pt(550, 430));
    }

    private static AssessmentConnection f() {
        return conn("f", "c", "d", "", MIDDLE, 2, pt(300, 130), pt(300, 730));
    }

    private static AssessmentConnection conn(String id, String source, String target, String label,
            int textPosition, int relativePosition, double[]... path) {
        return new AssessmentConnection(id, source, target, List.of(path), label, textPosition, relativePosition,
                0.0, 0.0);
    }

    private static double[] pt(double x, double y) {
        return new double[]{x, y};
    }

    private static AssessmentNode node(String id, double x, double y, double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, false, false, "", 0.0, null, null, 0.0, 0.0, 0.0);
    }
}
