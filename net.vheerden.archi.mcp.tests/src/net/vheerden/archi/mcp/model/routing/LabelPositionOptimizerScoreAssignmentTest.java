package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.Test;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * {@link LabelPositionOptimizer#scoreAssignment} scores a given assignment by the measure the multi-trial search ranks
 * its trials with, so a caller can compare the labels as they stand with the best trial.
 *
 * <p>Two small views of straight connections (no bendpoints, centre to centre, as Archi draws them) show why the
 * comparison is needed. The search never puts the current assignment among its candidates, so its best trial can
 * score <em>worse</em> than the labels already hold, or equal with labels moved. Boxes are 120 x 55; the seed is the
 * one the label pass uses (labels x 31 + obstacles).</p>
 *
 * <p>The two fixtures are searched where no Label Offset ships (an empty offset-writable set): offered the eight
 * anchors as well, the search clears both views, so they no longer show the gap. The gap itself is a property of a
 * greedy search with random orders, offsets or not.</p>
 */
public class LabelPositionOptimizerScoreAssignmentTest {

    private static final int SOURCE = 0;
    private static final int MIDDLE = 1;
    private static final int TARGET = 2;

    @Test
    public void scoreAssignment_isTheTotalTheSearchRanksTrialsWith() {
        View v = worse();
        LabelPositionOptimizer optimizer = new LabelPositionOptimizer();
        LabelPositionOptimizer.MultiTrialResult best = v.search(optimizer);

        assertEquals(best.totalScore(), v.score(optimizer, best.allPositions()), 0.0);
        LabelPositionOptimizer.MultiTrialResult withAnchors = v.searchWithAnchors(optimizer);
        assertEquals("the anchors a trial chose are scored with it", withAnchors.totalScore(),
                optimizer.scoreAssignment(v.in, v.paths, v.boxes, v.excludes, withAnchors.allPositions(),
                        withAnchors.offsets()), 0.0);
        assertEquals(v.score(optimizer, v.asIs()),
                optimizer.computeTotalScore(v.asIs(), v.in, v.paths, v.boxes, v.excludes), 0.0);
    }

    /** The search alone would move c2 to Target and end higher than the labels already score. */
    @Test
    public void bestTrial_canScoreWorseThanTheLabelsAsTheyStand() {
        View v = worse();
        LabelPositionOptimizer optimizer = new LabelPositionOptimizer();
        LabelPositionOptimizer.MultiTrialResult best = v.search(optimizer);

        assertEquals(1.5, v.score(optimizer, v.asIs()), 0.0);
        assertEquals(2.0, best.totalScore(), 0.0);
        assertEquals(Map.of("c2", TARGET), best.changedPositions());
    }

    /** The search alone would move c0 to Target for no gain: the same total as the labels already score. */
    @Test
    public void bestTrial_canMoveALabelForNoGain() {
        View v = churn();
        LabelPositionOptimizer optimizer = new LabelPositionOptimizer();
        LabelPositionOptimizer.MultiTrialResult best = v.search(optimizer);

        assertEquals(2.0, v.score(optimizer, v.asIs()), 0.0);
        assertEquals(2.0, best.totalScore(), 0.0);
        assertEquals(Map.of("c0", TARGET), best.changedPositions());
    }

    @Test
    public void scoreAssignment_skipsAnEntryWithoutALabel() {
        View v = churn();
        LabelPositionOptimizer optimizer = new LabelPositionOptimizer();
        double labelled = v.score(optimizer, v.asIs());
        v.in.add(new RoutingPipeline.ConnectionEndpoints("line", v.boxes.get(0), v.boxes.get(2), List.of(), "",
                MIDDLE, List.of()));
        v.paths.add(List.of());
        v.excludes.put("line", Set.of("b0", "b2"));
        Map<String, Integer> withLine = new LinkedHashMap<>(v.asIs());
        withLine.put("line", MIDDLE);

        // the line-only entry is not scored as a label; whether its line touches a label is the only change it can make
        double scored = v.score(optimizer, withLine);
        assertEquals(scored, optimizer.computeTotalScore(withLine, v.in, v.paths, v.boxes, v.excludes), 0.0);
        assertEquals("the line crosses no label here", labelled, scored, 0.0);
    }

    // ==================== fixtures (found by a seeded search; see the class comment) ====================

    /** b0 60,140 / b1 20,320 / b2 280,40; c0 b0-b2 Target, c1 b1-b2 Source, c2 b1-b2 Middle. */
    static View worse() {
        View v = new View(box("b0", 60, 140), box("b1", 20, 320), box("b2", 280, 40));
        v.connect("c0", 0, 2, "sends orders", TARGET);
        v.connect("c1", 1, 2, "uses", SOURCE);
        v.connect("c2", 1, 2, "serves", MIDDLE);
        return v;
    }

    /** b0 400,40 / b1 100,80 / b2 160,0; c0 b1-b2 Source, c1 b1-b2 Target. */
    static View churn() {
        View v = new View(box("b0", 400, 40), box("b1", 100, 80), box("b2", 160, 0));
        v.connect("c0", 1, 2, "uses", SOURCE);
        v.connect("c1", 1, 2, "notifies", TARGET);
        return v;
    }

    private static RoutingRect box(String id, int x, int y) {
        return new RoutingRect(x, y, 120, 55, id);
    }

    static final class View {
        final List<RoutingRect> boxes;
        final List<RoutingPipeline.ConnectionEndpoints> in = new ArrayList<>();
        final List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        final Map<String, Set<String>> excludes = new LinkedHashMap<>();

        View(RoutingRect... boxes) {
            this.boxes = List.of(boxes);
        }

        void connect(String id, int source, int target, String label, int position) {
            in.add(new RoutingPipeline.ConnectionEndpoints(id, boxes.get(source), boxes.get(target), List.of(), label,
                    position, List.of()));
            paths.add(List.of());
            excludes.put(id, new HashSet<>(Set.of(boxes.get(source).id(), boxes.get(target).id())));
        }

        Map<String, Integer> asIs() {
            Map<String, Integer> positions = new LinkedHashMap<>();
            in.forEach(c -> positions.put(c.connectionId(), c.textPosition()));
            return positions;
        }

        LabelPositionOptimizer.MultiTrialResult search(LabelPositionOptimizer optimizer) {
            return optimizer.optimizeMultiTrial(in, paths, boxes, excludes, 10, new Random(in.size() * 31L + boxes.size()),
                    Set.of());
        }

        LabelPositionOptimizer.MultiTrialResult searchWithAnchors(LabelPositionOptimizer optimizer) {
            Set<String> every = new HashSet<>();
            in.forEach(c -> every.add(c.connectionId()));
            return optimizer.optimizeMultiTrial(in, paths, boxes, excludes, 10, new Random(in.size() * 31L + boxes.size()),
                    every);
        }

        double score(LabelPositionOptimizer optimizer, Map<String, Integer> positions) {
            return optimizer.scoreAssignment(in, paths, boxes, excludes, positions);
        }
    }
}
