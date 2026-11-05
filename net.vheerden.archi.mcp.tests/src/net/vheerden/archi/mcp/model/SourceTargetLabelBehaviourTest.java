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
 * A Source or Target label is scored where Archi draws it, by both the label optimizer and the layout-quality
 * assessor: draw2d's {@code ConnectionEndpointLocator} puts it 14 px beyond that end of the drawn line, hanging
 * 4 px below an EAST or WEST end and to the right of a NORTH or SOUTH one, outside the endpoint's box. Before
 * that rule was modelled the label was centred 15% (Target: 85%) of the way along the centre path, which on a
 * short connection is on the endpoint box itself.
 */
public class SourceTargetLabelBehaviourTest {

    private static final String SHORT_LABEL = "serves"; // renders 74.8 px wide
    private static final String LONG_LABEL = "abcdefghijabcdefghij"; // renders 226 px wide
    private static final int CENTRE = RelativePositionFeature.CENTER;
    private static final int SOURCE = 0;

    private final LayoutQualityAssessor assessor = new LayoutQualityAssessor();

    /**
     * Source (0,0 100x50) and target (300,0 100x50), no bendpoints. Archi draws the Source label at
     * x 114..188.8, y 29..49, clear of both boxes. 15% along the centre path it was centred on x 95, two
     * thirds of it on the source box (x 57.6..132.4): an own-endpoint bleed Archi never draws, which the
     * optimizer moved the label off (pre-edit: 1 counted, moved to Middle).
     */
    @Test
    public void phantomBleed_aSourceLabelBesideItsOwnBoxIsCountedNowhere_andKept() {
        RoutingRect source = new RoutingRect(0, 0, 100, 50, "s");
        RoutingRect target = new RoutingRect(300, 0, 100, 50, "t");
        RoutingPipeline.ConnectionEndpoints conn = new RoutingPipeline.ConnectionEndpoints(
                "c1", source, target, List.of(), SHORT_LABEL, SOURCE);

        assertEquals("the assessor counts nothing on Source", 0,
                assessed(conn, List.of(), SOURCE, source, target).count());
        assertEquals("the optimizer keeps Source", SOURCE, optimized(conn, source, target));
    }

    /**
     * Source (0,0 100x50) and target (600,0 100x50), no bendpoints, a box (130,40 40x30) just below the line
     * 30 px out of the source. Archi draws the Source label (x 114..188.8, y 29..49) on it; centred 15% along
     * (x 102.6..177.4, y 15..35) the label was above it, so the optimizer kept Source and the assessor counted
     * 0 (pre-edit): a clear reported for a label drawn on a box. Now the optimizer moves to a clear slot.
     */
    @Test
    public void falseClear_aSourceLabelDrawnOnABoxIsMovedToAClearSlot() {
        RoutingRect source = new RoutingRect(0, 0, 100, 50, "s");
        RoutingRect target = new RoutingRect(600, 0, 100, 50, "t");
        RoutingRect under = new RoutingRect(130, 40, 40, 30, "under");
        RoutingPipeline.ConnectionEndpoints conn = new RoutingPipeline.ConnectionEndpoints(
                "c1", source, target, List.of(under), SHORT_LABEL, SOURCE);

        int chosen = optimized(conn, source, target, under);

        assertEquals("control: held at Source, the assessor counts the label on the box", 1,
                assessed(conn, List.of(), SOURCE, source, target, under).count());
        assertEquals("the label moves to Middle", 1, chosen);
        assertEquals("the assessor counts nothing on the chosen slot", 0,
                assessed(conn, List.of(), chosen, source, target, under).count());
    }

    /**
     * Source (0,0 100x50) straight down to target (0,400 100x50): a SOUTH exit at (50, 50). Archi turns the
     * label's width across the line, to its right: x 54..280, y 64..84. A box right of the line (200,70 40x20)
     * is under it. Centred 15% along (x -63..163, y 75..95) the label was left of that box (pre-edit: 0).
     */
    @Test
    public void southExit_theSourceLabelRunsToTheRightOfTheLine() {
        RoutingRect source = new RoutingRect(0, 0, 100, 50, "s");
        RoutingRect target = new RoutingRect(0, 400, 100, 50, "t");
        RoutingRect right = new RoutingRect(200, 70, 40, 20, "right");
        RoutingPipeline.ConnectionEndpoints conn = new RoutingPipeline.ConnectionEndpoints(
                "c1", source, target, List.of(right), LONG_LABEL, SOURCE);

        assertEquals(1, assessed(conn, List.of(), SOURCE, source, target, right).count());
    }

    /** The optimizer's chosen position for the one connection on a straight route. */
    private static int optimized(RoutingPipeline.ConnectionEndpoints conn, RoutingRect... boxes) {
        LabelPositionOptimizer.MultiTrialResult result = new LabelPositionOptimizer().optimizeMultiTrial(
                List.of(conn), List.of(List.of()), List.of(boxes),
                Map.of(conn.connectionId(), Set.of(conn.source().id(), conn.target().id())), 1, new Random(0));
        return result.allPositions().get(conn.connectionId());
    }

    /** The assessor's label overlap result for the connection at the given slot, with no offset. */
    private LayoutQualityAssessor.LabelOverlapResult assessed(RoutingPipeline.ConnectionEndpoints conn,
            List<AbsoluteBendpointDto> path, int textPosition, RoutingRect... boxes) {
        List<double[]> points = new ArrayList<>();
        points.add(new double[]{conn.source().centerX(), conn.source().centerY()});
        for (AbsoluteBendpointDto bp : path) {
            points.add(new double[]{bp.x(), bp.y()});
        }
        points.add(new double[]{conn.target().centerX(), conn.target().centerY()});
        AssessmentConnection assessed = new AssessmentConnection(conn.connectionId(),
                conn.source().id(), conn.target().id(), points, conn.labelText(), textPosition, CENTRE);

        List<AssessmentNode> nodes = new ArrayList<>();
        for (RoutingRect box : boxes) {
            nodes.add(new AssessmentNode(box.id(), box.x(), box.y(), box.width(), box.height(),
                    null, false, false, null, 0.0, null, null, 0.0, 0.0, 0.0));
        }
        return assessor.countLabelOverlaps(List.of(assessed), nodes);
    }
}
