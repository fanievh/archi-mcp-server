package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.Test;

import net.vheerden.archi.mcp.model.routing.LabelPositionOptimizer;
import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * A Middle label is scored where Archi draws it, by both the label optimizer and the layout-quality
 * assessor. With one bendpoint the drawn line has three points and draw2d's {@code ConnectionLocator}
 * centres the label on the middle one, the corner, not half way along the route. On a lopsided route
 * those are hundreds of pixels apart.
 *
 * <p>At a corner the label hangs from the segment that ends there (the incoming one): its orientation
 * decides which perpendicular offset is tried first, and its length whether the label fits.</p>
 */
public class MiddleLabelAnchorConsumersTest {

    private static final String LABEL = "abcdefghijklmnopqrstuvwxyzabcd"; // renders 334 px wide
    private static final int CENTRE = RelativePositionFeature.CENTER;
    private static final int WEST = 8;

    private final LayoutQualityAssessor assessor = new LayoutQualityAssessor();

    /**
     * Source (0,0 100x50), right along y = 25 to a corner at (900, 25), then down to target (850,200
     * 100x50). The legs are 850 and 200 px. Half the length lies 525 px in, at (575, 25), where the box
     * (x 408..742, y 15..35) is far from the blocker at x 1000..1040; Archi centres the label on the corner
     * (x 733..1067), across the blocker. The Source slot (x 40.5..374.5) is on no third party, and the Target
     * slot is below the blocker. Before Archi's anchor was modelled the optimizer kept Middle and the
     * assessor counted 0 on it: a clear reported for a label drawn on a box.
     */
    @Test
    public void optimizer_movesOffAMiddleSlotDrawnOnABoxAtTheCorner_andTheAssessorAgrees() {
        RoutingRect source = new RoutingRect(0, 0, 100, 50, "s");
        RoutingRect target = new RoutingRect(850, 200, 100, 50, "t");
        RoutingRect blocker = new RoutingRect(1000, 0, 40, 40, "blocker");
        RoutingPipeline.ConnectionEndpoints conn = new RoutingPipeline.ConnectionEndpoints(
                "c1", source, target, List.of(blocker), LABEL, 1);
        List<AbsoluteBendpointDto> path = List.of(new AbsoluteBendpointDto(900, 25));

        LabelPositionOptimizer.MultiTrialResult result = new LabelPositionOptimizer().optimizeMultiTrial(
                List.of(conn), List.of(path), List.of(source, target, blocker),
                Map.of("c1", Set.of("s", "t")), 1, new Random(0));
        int chosen = result.allPositions().get("c1");
        int offset = result.offsets().getOrDefault("c1", CENTRE);

        assertEquals("the assessor counts the Middle label on the blocker", 1,
                assessed(conn, path, 1, CENTRE, source, target, blocker).count());
        assertEquals("the label moves to Source", 0, chosen);
        assertEquals("with no offset", CENTRE, offset);
        assertEquals("the assessor counts nothing on the chosen slot", 0,
                assessed(conn, path, chosen, offset, source, target, blocker).count());
    }

    /**
     * Source (0,0 100x60), down from its centre to a corner at (50, 200), then right to target (700,170
     * 100x60): a 170 px vertical leg into the corner and a 700 px horizontal one out of it. The label sits on
     * a small box at the corner, and each perpendicular offset clears it. The leg ending at the corner is
     * vertical, so West is tried, and kept, before North. (Half the length lies on the horizontal leg at
     * (315, 200), clear of the box; there the label needed no offset.)
     */
    @Test
    public void offsetSearch_triesThePerpendicularOfTheLegEndingAtTheCornerFirst() {
        RoutingRect source = new RoutingRect(0, 0, 100, 60, "s");
        RoutingRect target = new RoutingRect(700, 170, 100, 60, "t");
        RoutingRect onCorner = new RoutingRect(45, 195, 10, 10, "onCorner");
        RoutingPipeline.ConnectionEndpoints conn = new RoutingPipeline.ConnectionEndpoints(
                "c1", source, target, List.of(onCorner), "abcdefghij", 1);
        List<AbsoluteBendpointDto> path = List.of(new AbsoluteBendpointDto(50, 200));

        Map<String, Integer> offsets = new LabelPositionOptimizer().computeOffsetsForPositions(
                List.of(conn), Map.of("c1", path), List.of(source, target, onCorner),
                Map.of("c1", Set.of("s", "t")), Map.of("c1", 1));

        assertEquals(Map.of("c1", WEST), offsets);
    }

    /**
     * Source (0,0 100x60) right to a corner at (250, 30), then down 600 px to target (220,600 60x60). The
     * 20-character label (226 px) is wider than the 150 px drawn leg ending at the corner, and fits the
     * vertical one. Archi centres it on the corner and it hangs from the short horizontal leg, so the
     * assessor says it exceeds that segment. (Half the length lies on the vertical leg; there the label fit,
     * and no guidance was given.)
     */
    @Test
    public void shortSegmentGuidance_measuresTheLegEndingAtTheCorner() {
        RoutingRect source = new RoutingRect(0, 0, 100, 60, "s");
        RoutingRect target = new RoutingRect(220, 600, 60, 60, "t");
        RoutingPipeline.ConnectionEndpoints conn = new RoutingPipeline.ConnectionEndpoints(
                "c1", source, target, List.of(), "abcdefghijabcdefghij", 1);
        List<AbsoluteBendpointDto> path = List.of(new AbsoluteBendpointDto(250, 30));

        LayoutQualityAssessor.LabelOverlapResult result = assessed(conn, path, 1, CENTRE, source, target);

        assertEquals(1, result.shortSegmentCount());
        assertEquals(List.of("Label on connection 'c1' exceeds segment length — increase spacing between s and t"),
                result.descriptions());
    }

    /**
     * The hosting leg is measured as drawn, from where the line leaves the source box. Same route as above
     * with a 16-character label (182.8 px): longer than the 150 px drawn leg, shorter than the 200 px from
     * the source's centre.
     */
    @Test
    public void shortSegmentGuidance_measuresTheDrawnLegNotTheCentreLeg() {
        RoutingRect source = new RoutingRect(0, 0, 100, 60, "s");
        RoutingRect target = new RoutingRect(220, 600, 60, 60, "t");
        RoutingPipeline.ConnectionEndpoints conn = new RoutingPipeline.ConnectionEndpoints(
                "c1", source, target, List.of(), "abcdefghijabcdef", 1);
        List<AbsoluteBendpointDto> path = List.of(new AbsoluteBendpointDto(250, 30));

        assertEquals(1, assessed(conn, path, 1, CENTRE, source, target).shortSegmentCount());
    }

    /**
     * A Source label hangs from the drawn line's first segment, where Archi draws it (draw2d
     * {@code ConnectionEndpointLocator}), no longer from the centre-path segment holding the point 15% along.
     * Same route and the 16-character label (182.8 px): the drawn first leg runs from the source's face
     * (100, 30) to the corner (250, 30), 150 px, which the label does not fit. The centre leg it was measured
     * on before is 200 px, which it fits (pre-edit: 0).
     */
    @Test
    public void shortSegmentGuidance_atSource_measuresTheDrawnEndLeg() {
        RoutingRect source = new RoutingRect(0, 0, 100, 60, "s");
        RoutingRect target = new RoutingRect(220, 600, 60, 60, "t");
        RoutingPipeline.ConnectionEndpoints conn = new RoutingPipeline.ConnectionEndpoints(
                "c1", source, target, List.of(), "abcdefghijabcdef", 0);
        List<AbsoluteBendpointDto> path = List.of(new AbsoluteBendpointDto(250, 30));

        LayoutQualityAssessor.LabelOverlapResult result = assessed(conn, path, 0, CENTRE, source, target);
        assertEquals(1, result.shortSegmentCount());
        assertEquals(List.of("Label on connection 'c1' exceeds segment length — increase spacing between s and t"),
                result.descriptions());
    }

    /**
     * A drawn end segment with no length is passed over for the next one inward. Source (0,0 100x60), a
     * bendpoint on its right face at (100, 30), then right to (400, 30) and down to target (370,400 60x60).
     * The drawn line starts on that bendpoint, so its first segment has no length; the 300 px leg after it
     * hosts the 16-character label (182.8 px), which fits. Taking the zero-length segment would report it as
     * too short.
     */
    @Test
    public void shortSegmentGuidance_atSource_passesOverAZeroLengthEndSegment() {
        RoutingRect source = new RoutingRect(0, 0, 100, 60, "s");
        RoutingRect target = new RoutingRect(370, 400, 60, 60, "t");
        List<AbsoluteBendpointDto> path = List.of(new AbsoluteBendpointDto(100, 30),
                new AbsoluteBendpointDto(400, 30));
        RoutingPipeline.ConnectionEndpoints conn = new RoutingPipeline.ConnectionEndpoints(
                "c1", source, target, List.of(), "abcdefghijabcdef", 0);

        assertEquals(0, assessed(conn, path, 0, CENTRE, source, target).shortSegmentCount());
    }

    /**
     * A repeated bendpoint puts a zero-length segment into the corner. Source (0,0 100x60) down 240 px from
     * its bottom face to (50, 300), twice, then right to (400, 300) and down to target (350,420 100x60). The
     * drawn line has five points and the label is centred on the middle one, the corner (50, 300). The
     * segment ending there has no length, so the label hangs from the 240 px vertical leg before it: the
     * 20-character label (226 px) fits it, and West is tried before North.
     */
    @Test
    public void zeroLengthSegmentIntoTheCorner_theLegBeforeItHostsTheLabel() {
        RoutingRect source = new RoutingRect(0, 0, 100, 60, "s");
        RoutingRect target = new RoutingRect(350, 420, 100, 60, "t");
        List<AbsoluteBendpointDto> path = List.of(new AbsoluteBendpointDto(50, 300),
                new AbsoluteBendpointDto(50, 300), new AbsoluteBendpointDto(400, 300));

        RoutingPipeline.ConnectionEndpoints wide = new RoutingPipeline.ConnectionEndpoints(
                "c1", source, target, List.of(), "abcdefghijabcdefghij", 1);
        assertEquals(0, assessed(wide, path, 1, CENTRE, source, target).shortSegmentCount());

        RoutingRect onCorner = new RoutingRect(45, 295, 10, 10, "onCorner");
        RoutingPipeline.ConnectionEndpoints conn = new RoutingPipeline.ConnectionEndpoints(
                "c1", source, target, List.of(onCorner), "abcdefghij", 1);
        Map<String, Integer> offsets = new LabelPositionOptimizer().computeOffsetsForPositions(
                List.of(conn), Map.of("c1", path), List.of(source, target, onCorner),
                Map.of("c1", Set.of("s", "t")), Map.of("c1", 1));
        assertEquals(Map.of("c1", WEST), offsets);
    }

    /**
     * {@code assess-layout} hands the label-on-note and label-on-group arms the endpoint boxes, so a label
     * on a line with no bendpoints is placed where Archi draws it there too. Source (0,0 100x60) and target
     * (400,20 200x90) overlap on y 20..60: the line runs at y 40 from x 100 to x 400 and the 17-character
     * label centres on (250, 40). A note at x 150..180, y 30..38 sits under its left end; from the centres'
     * midpoint (275, 47.5) the label would start right of it. Likewise a group's title band there.
     */
    @Test
    public void assess_placesLabelsOnNotesAndGroupsWhereArchiDrawsThem() {
        AssessmentNode source = new AssessmentNode("s", 0, 0, 100, 60,
                null, false, false, null, 0.0, null, null, 0.0, 0.0, 0.0);
        AssessmentNode target = new AssessmentNode("t", 400, 20, 200, 90,
                null, false, false, null, 0.0, null, null, 0.0, 0.0, 0.0);
        AssessmentNode note = new AssessmentNode("n", 150, 30, 30, 8,
                null, false, true, null, 0.0, null, null, 0.0, 0.0, 0.0);
        AssessmentNode group = new AssessmentNode("g", 150, 30, 30, 200,
                null, true, false, "G", 0.0, null, null, 0.0, 0.0, 0.0);
        AssessmentConnection conn = new AssessmentConnection("c1", "s", "t",
                List.of(new double[]{50, 30}, new double[]{500, 65}), "x".repeat(17), 1, CENTRE);

        assertEquals(1, assessor.assess(List.of(source, target, note), List.of(conn), false).labelOnNoteCount());
        assertEquals(1, assessor.assess(List.of(source, target, group), List.of(conn), false).labelOnGroupCount());
    }

    /** The assessor's label overlap result for the connection at the given slot and offset. */
    private LayoutQualityAssessor.LabelOverlapResult assessed(RoutingPipeline.ConnectionEndpoints conn,
            List<AbsoluteBendpointDto> path, int textPosition, int offset, RoutingRect... boxes) {
        List<double[]> points = new ArrayList<>();
        points.add(new double[]{conn.source().centerX(), conn.source().centerY()});
        for (AbsoluteBendpointDto bp : path) {
            points.add(new double[]{bp.x(), bp.y()});
        }
        points.add(new double[]{conn.target().centerX(), conn.target().centerY()});
        AssessmentConnection assessed = new AssessmentConnection(conn.connectionId(),
                conn.source().id(), conn.target().id(), points, conn.labelText(), textPosition, offset);

        List<AssessmentNode> nodes = new ArrayList<>();
        for (RoutingRect box : boxes) {
            nodes.add(new AssessmentNode(box.id(), box.x(), box.y(), box.width(), box.height(),
                    null, false, false, null, 0.0, null, null, 0.0, 0.0, 0.0));
        }
        return assessor.countLabelOverlaps(List.of(assessed), nodes);
    }
}
