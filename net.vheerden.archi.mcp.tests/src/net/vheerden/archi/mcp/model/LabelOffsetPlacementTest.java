package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.Test;

import net.vheerden.archi.mcp.model.routing.LabelPositionOptimizer;
import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * A Label Offset is one of a label's placements: where the offset ships, the label optimizer scores the Middle
 * label at each of the eight anchors on the box Archi draws, by the same terms as the three text positions, and the
 * best placement wins. The assessor is read on each result, so a pick the optimizer calls clear is one
 * {@code assess-layout} counts clear.
 *
 * <p>Every fixture is a horizontal line from a source box at x 0..100 to a target box at x 900..1000, with
 * "abcdefgh" (96 px drawn, 20 tall) centred at x 500 on its line at Middle; an offset lifts it 15 px North or South,
 * 53 px East or West. Where a fixture blocks the Source and Target labels, boxes sb (x 110..240) and tb
 * (x 760..890) cover them.</p>
 */
public class LabelOffsetPlacementTest {

    private static final String LABEL = "abcdefgh";
    private static final int MIDDLE = 1;
    private static final int NORTH = 1;
    private static final int CENTRE = RelativePositionFeature.CENTER;
    private static final int SOUTH = 4;

    private static final RoutingRect S = new RoutingRect(0, 0, 100, 200, "s");
    private static final RoutingRect T = new RoutingRect(900, 0, 100, 200, "t");
    private static final RoutingRect SB = new RoutingRect(110, 40, 130, 120, "sb");
    private static final RoutingRect TB = new RoutingRect(760, 40, 130, 120, "tb");

    /** The connections, their paths and the boxes of one fixture. */
    private static final class View {
        final List<RoutingPipeline.ConnectionEndpoints> conns = new ArrayList<>();
        final List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        final Map<String, Set<String>> excludes = new HashMap<>();
        final List<RoutingRect> boxes = new ArrayList<>(List.of(S, T));
        final Set<String> labelled = new HashSet<>();

        View line(String id, String label, int y, int anchor) {
            return line(id, label, S, T, 100, 900, y, anchor);
        }

        View line(String id, String label, RoutingRect from, RoutingRect to, int x1, int x2, int y, int anchor) {
            conns.add(new RoutingPipeline.ConnectionEndpoints(id, from, to, List.of(), label, MIDDLE, List.of(),
                    anchor));
            paths.add(List.of(new AbsoluteBendpointDto(x1, y), new AbsoluteBendpointDto(x2, y)));
            excludes.put(id, Set.of(from.id(), to.id()));
            if (!label.isEmpty()) {
                labelled.add(id);
            }
            return this;
        }

        View box(RoutingRect box) {
            if (!boxes.contains(box)) {
                boxes.add(box);
            }
            return this;
        }

        LabelPositionOptimizer.MultiTrialResult pick(Set<String> writable) {
            return new LabelPositionOptimizer().optimizeMultiTrial(conns, paths, boxes, excludes, 10,
                    new Random(3), writable);
        }

        LabelPositionOptimizer.MultiTrialResult pick() {
            return pick(labelled);
        }

        /** What {@code assess-layout} counts with the labels at {@code positions} and {@code anchors}. */
        LayoutQualityAssessor.LabelOverlapResult assessed(Map<String, Integer> positions,
                Map<String, Integer> anchors) {
            List<AssessmentConnection> drawn = new ArrayList<>();
            for (int i = 0; i < conns.size(); i++) {
                RoutingPipeline.ConnectionEndpoints c = conns.get(i);
                List<double[]> points = new ArrayList<>();
                points.add(new double[]{c.source().centerX(), c.source().centerY()});
                for (AbsoluteBendpointDto b : paths.get(i)) {
                    points.add(new double[]{b.x(), b.y()});
                }
                points.add(new double[]{c.target().centerX(), c.target().centerY()});
                drawn.add(new AssessmentConnection(c.connectionId(), c.source().id(), c.target().id(), points,
                        c.labelText(), positions.getOrDefault(c.connectionId(), c.textPosition()),
                        anchors.getOrDefault(c.connectionId(), c.relativePosition())));
            }
            List<AssessmentNode> nodes = new ArrayList<>();
            for (RoutingRect b : boxes) {
                nodes.add(new AssessmentNode(b.id(), b.x(), b.y(), b.width(), b.height(), null, false, false, null,
                        0.0, null, null, 0.0, 0.0, 0.0));
            }
            return new LayoutQualityAssessor().countLabelOverlaps(drawn, nodes);
        }
    }

    /**
     * Three parallel lines 12 px apart, their Middle labels on each other and clear of every box; the Source and
     * Target labels are blocked. Offered the anchors, the outer two lift North and South and the middle one stays.
     */
    @Test
    public void corridorLabels_areStaggered() {
        View v = new View().line("c0", LABEL, 88, CENTRE).line("c1", LABEL, 100, CENTRE)
                .line("c2", LABEL, 112, CENTRE).box(SB).box(TB);

        LabelPositionOptimizer.MultiTrialResult result = v.pick();

        assertEquals("every label stays at Middle", Map.of(), result.changedPositions());
        assertEquals("the outer two are offset", Map.of("c0", NORTH, "c2", SOUTH), result.offsets());
        assertEquals("the assessor counts the three labels on one another", 3,
                v.assessed(Map.of(), Map.of()).count());
        assertEquals("and nothing once they are staggered: no box, no label",
                List.of(), v.assessed(result.allPositions(), result.offsets()).descriptions());
    }

    /**
     * The Middle label sits on a sliver x at y 97..99. North, first in order on a horizontal line, clears x but lies
     * on the unlabelled line f at y 85 (and passes 2 px from x); South is clear of both, so South wins.
     */
    @Test
    public void bestAnchor_notTheFirstThatClearsTheBox() {
        RoutingRect u = new RoutingRect(250, 75, 20, 20, "u");
        RoutingRect w = new RoutingRect(730, 75, 20, 20, "w");
        View v = new View().line("c1", LABEL, 100, CENTRE).line("f", "", u, w, 270, 730, 85, CENTRE)
                .box(new RoutingRect(500, 97, 10, 2, "x")).box(SB).box(TB).box(u).box(w);

        LabelPositionOptimizer.MultiTrialResult result = v.pick();

        assertEquals(Map.of("c1", SOUTH), result.offsets());
        assertEquals("the assessor finds South clear", List.of(),
                v.assessed(result.allPositions(), result.offsets()).descriptions());
    }

    /**
     * The Middle label is on box x, its held North draws it clear, and Source is clear too: the tie keeps the
     * placement the label holds, so it stays at Middle with its anchor and nothing is written.
     */
    @Test
    public void aHeldAnchorThatDrawsClear_keepsTheLabelAtMiddle() {
        View v = new View().line("c1", LABEL, 100, NORTH).box(new RoutingRect(480, 102, 40, 4, "x"));

        LabelPositionOptimizer.MultiTrialResult result = v.pick();

        assertEquals("the label stays at Middle", Map.of(), result.changedPositions());
        assertEquals("with its held North: nothing to write", Map.of(), result.offsets());
        assertEquals("the assessor finds it clear where it is", List.of(),
                v.assessed(Map.of(), Map.of()).descriptions());
        assertEquals("control: at Centre it is on x", 1, v.assessed(Map.of(), Map.of("c1", CENTRE)).count());
    }

    /** The same views give the same picks, run after run. */
    @Test
    public void thePick_isDeterministic() {
        for (int run = 0; run < 3; run++) {
            View v = new View().line("c0", LABEL, 88, CENTRE).line("c1", LABEL, 100, CENTRE)
                    .line("c2", LABEL, 112, CENTRE).box(SB).box(TB);
            LabelPositionOptimizer.MultiTrialResult result = v.pick();
            assertEquals("run " + run, Map.of("c0", NORTH, "c2", SOUTH), result.offsets());
            assertEquals("run " + run, 0.0, result.totalScore(), 0.0);
        }
    }

    /** Where no offset ships the corridor keeps its overlaps: there is no anchor to pick. */
    @Test
    public void whereNoOffsetShips_noAnchorIsPicked() {
        View v = new View().line("c0", LABEL, 88, CENTRE).line("c1", LABEL, 100, CENTRE)
                .line("c2", LABEL, 112, CENTRE).box(SB).box(TB);

        LabelPositionOptimizer.MultiTrialResult result = v.pick(Set.of());

        assertTrue(result.offsets().isEmpty());
        assertTrue("the overlaps are a cost, not hidden: " + result.totalScore(), result.totalScore() > 0);
    }

    /**
     * The Source and Target labels are blocked and a box covers the Middle label at Centre and at every anchor: all
     * eleven placements collide, so the label is reported unplaceable.
     */
    @Test
    public void everyPlacementColliding_isUnplaceable() {
        View v = new View().line("c1", LABEL, 100, CENTRE).box(SB).box(TB)
                .box(new RoutingRect(420, 60, 160, 80, "m"));

        assertEquals(Set.of("c1"), v.pick(Set.of("c1")).unresolvableLabels());
    }

    /**
     * A sliver box m sits on the Middle label at Centre and East and West; North, South and the diagonals draw it
     * clear of every box. Alone, that clear anchor makes the label placeable. With the labels of two longer lines
     * placed first above and below it (only c1 may take an anchor), every anchor that clears m lands on one of
     * them, so the label is unplaceable: a label already placed counts as a collision for an anchor as for a
     * position.
     */
    @Test
    public void anAnchorThatClearsTheBoxOnlyOntoAPlacedLabel_doesNotRescueIt() {
        RoutingRect m = new RoutingRect(470, 95, 60, 10, "m");
        View alone = new View().line("c1", LABEL, S, T, 200, 800, 100, CENTRE).box(SB).box(TB).box(m);
        assertEquals("control: a clear anchor rescues it", Set.of(),
                alone.pick(Set.of("c1")).unresolvableLabels());

        View locked = new View().line("c0", LABEL, 85, CENTRE).line("c1", LABEL, S, T, 200, 800, 100, CENTRE)
                .line("c2", LABEL, 115, CENTRE).box(SB).box(TB).box(m);
        LabelPositionOptimizer.MultiTrialResult result = locked.pick(Set.of("c1"));

        assertEquals("the labels above and below stay at Middle", Map.of(), result.changedPositions());
        assertEquals(Set.of("c1"), result.unresolvableLabels());
    }
}
