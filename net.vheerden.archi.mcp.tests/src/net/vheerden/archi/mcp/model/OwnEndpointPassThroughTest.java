package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/**
 * A route that runs through its own source or target is a pass-through like any other: it is
 * counted, whatever else the view holds, and it is charged on the passThroughs band beside the
 * crossings of other elements.
 *
 * <p>Two things used to hide it. The detection ran only while the capped description list had
 * room, so on a view with ten earlier descriptions it was never made; and the rating read the
 * cross-element count only, so a route drawn straight through its own source was rated "pass".</p>
 *
 * <p>The geometry is a captured route: a source at [1335,2600,620,635], a target at
 * [868,3345,620,635] below and to its left, and a route that leaves the source's left face, turns
 * back in to x = 1361 and runs down inside the source to the target's top face.</p>
 */
public class OwnEndpointPassThroughTest {

    private static final List<double[]> THROUGH_ITS_SOURCE = List.of(
            new double[] {1645, 2917.5}, new double[] {1331, 2917}, new double[] {1361, 2917},
            new double[] {1361, 3341}, new double[] {1178, 3662.5});

    @Test
    public void routeThroughItsOwnSource_isCountedAndCharged() {
        LayoutAssessmentResult result = assess(new ArrayList<>(ends()),
                new ArrayList<>(List.of(connection("own", "src", "tgt", THROUGH_ITS_SOURCE))));

        assertEquals(List.of("Connection 'own' routes through its own source element 'src'"),
                result.connectionPassThroughs());
        assertEquals(0, result.crossElementPassThroughCount());
        assertEquals(1, result.ownEndpointPassThroughCount());
        assertEquals("fair", result.ratingBreakdown().get("passThroughs"));
        assertEquals(Set.of("own"), Set.copyOf(result.violatorIds().get("passThroughs")));
    }

    @Test
    public void routeThroughItsOwnSource_isCounted_pastTheTenthDescription() {
        // Eleven straight connections across one blocker fill the description list first.
        List<AssessmentNode> nodes = new ArrayList<>(ends());
        List<AssessmentConnection> connections = new ArrayList<>();
        nodes.add(node("blocker", 500, 0, 100, 1000));
        for (int i = 0; i < 11; i++) {
            nodes.add(node("left" + i, 0, i * 80, 100, 50));
            nodes.add(node("right" + i, 1000, i * 80, 100, 50));
            connections.add(connection("across" + i, "left" + i, "right" + i,
                    List.of(new double[] {50, i * 80 + 25}, new double[] {1050, i * 80 + 25})));
        }
        connections.add(connection("own", "src", "tgt", THROUGH_ITS_SOURCE));

        LayoutAssessmentResult result = assess(nodes, connections);

        assertEquals("precondition: the list is full", 10, result.connectionPassThroughs().size());
        assertEquals(11, result.crossElementPassThroughCount());
        assertEquals(1, result.ownEndpointPassThroughCount());
        assertTrue(String.valueOf(result.violatorIds().get("passThroughs")),
                result.violatorIds().get("passThroughs").contains("own"));
    }

    @Test
    public void routeTouchingItsEndsOnlyAtItsTerminals_isNotCounted() {
        // the same ends, the route leaving the source's left face and entering the target's top
        List<double[]> clear = List.of(new double[] {1645, 2917.5}, new double[] {1331, 2917},
                new double[] {1178, 2917}, new double[] {1178, 3662.5});
        LayoutAssessmentResult result = assess(new ArrayList<>(ends()),
                new ArrayList<>(List.of(connection("clear", "src", "tgt", clear))));

        assertEquals(List.of(), result.connectionPassThroughs());
        assertEquals(0, result.ownEndpointPassThroughCount());
        assertEquals("pass", result.ratingBreakdown().get("passThroughs"));
    }

    @Test
    public void routeInsideAContainerEnd_isNotCounted() {
        // child -> its container: the route has to run inside the container to reach the child
        AssessmentNode container = new AssessmentNode("box", 0, 0, 400, 300, null, false, false,
                "box", 0.0, null, null, 0.0, 0.0, 0.0, false, null, true,
                AssessmentNode.TEXT_ALIGNMENT_CENTRE, AssessmentNode.TEXT_POSITION_TOP);
        AssessmentNode child = new AssessmentNode("child", 50, 50, 100, 60, "box", false, false,
                "child", 0.0, null, null, 0.0, 0.0, 0.0, false, null, false,
                AssessmentNode.TEXT_ALIGNMENT_CENTRE, AssessmentNode.TEXT_POSITION_TOP);
        List<double[]> path = List.of(new double[] {100, 80}, new double[] {100, 46},
                new double[] {250, 46}, new double[] {250, 304}, new double[] {200, 150});
        LayoutAssessmentResult result = assess(new ArrayList<>(List.of(container, child)),
                new ArrayList<>(List.of(connection("in", "child", "box", path))));

        assertEquals(0, result.ownEndpointPassThroughCount());
        assertEquals("pass", result.ratingBreakdown().get("passThroughs"));
    }

    @Test
    public void selfConnectionThroughItsOwnObject_isCountedOnce() {
        // one object is both ends: a loop whose return run crosses the object is one crossing
        List<double[]> loopBackThrough = List.of(new double[] {50, 30}, new double[] {50, 90},
                new double[] {150, 90}, new double[] {150, 40}, new double[] {20, 40},
                new double[] {50, 30});
        LayoutAssessmentResult result = assess(
                new ArrayList<>(List.of(node("self", 0, 0, 100, 60))),
                new ArrayList<>(List.of(connection("loop", "self", "self", loopBackThrough))));

        assertEquals(1, result.ownEndpointPassThroughCount());
        assertEquals(1, result.connectionPassThroughs().size());
        assertEquals(Set.of("loop"), Set.copyOf(result.violatorIds().get("passThroughs")));
    }

    @Test
    public void routeThroughAContainerEndTheOtherEndIsNotIn_isCounted() {
        // the source is a container, but the target is not nested in it: nothing obliges the
        // route to run inside the source, so running through it is a crossing like any other
        AssessmentNode container = new AssessmentNode("src", 1335, 2600, 620, 635, null, false,
                false, "src", 0.0, null, null, 0.0, 0.0, 0.0, false, null, true,
                AssessmentNode.TEXT_ALIGNMENT_CENTRE, AssessmentNode.TEXT_POSITION_TOP);
        LayoutAssessmentResult result = assess(
                new ArrayList<>(List.of(container, node("tgt", 868, 3345, 620, 635))),
                new ArrayList<>(List.of(connection("own", "src", "tgt", THROUGH_ITS_SOURCE))));

        assertEquals(1, result.ownEndpointPassThroughCount());
        assertEquals("fair", result.ratingBreakdown().get("passThroughs"));
    }

    @Test
    public void routeThroughAnotherElementAndItsOwnSource_isChargedForBoth_andNamedOnce() {
        // a blocker on the run between the source's bottom face (y 3235) and the target (y 3345)
        List<AssessmentNode> nodes = new ArrayList<>(ends());
        nodes.add(node("blocker", 1330, 3240, 60, 40));
        LayoutAssessmentResult result = assess(nodes,
                new ArrayList<>(List.of(connection("own", "src", "tgt", THROUGH_ITS_SOURCE))));

        assertEquals(1, result.crossElementPassThroughCount());
        assertEquals(1, result.ownEndpointPassThroughCount());
        assertEquals("fair", result.ratingBreakdown().get("passThroughs"));
        assertEquals(1, result.violatorIds().get("passThroughs").size());
        assertTrue(result.violatorIds().get("passThroughs").contains("own"));
    }

    @Test
    public void overshootThroughItsOwnTarget_isChargedOnBothBands() {
        // the route runs past its target's centre and back. The zigzag guard skips whole
        // connections, and only cross-element ones, so a reversal elsewhere on an own-end route
        // is not hidden; this overshoot is therefore charged as a pass-through and as a zigzag.
        LayoutAssessmentResult result = assess(
                new ArrayList<>(List.of(node("a", 0, 100, 50, 50), node("b", 100, 100, 100, 50))),
                new ArrayList<>(List.of(connection("c1", "a", "b", List.of(
                        new double[] {25, 125}, new double[] {350, 125},
                        new double[] {150, 125}, new double[] {150, 125})))));

        assertEquals(1, result.ownEndpointPassThroughCount());
        assertEquals(1, result.zigzagCount());
    }

    private static LayoutAssessmentResult assess(List<AssessmentNode> nodes,
            List<AssessmentConnection> connections) {
        return new LayoutQualityAssessor().assess(nodes, connections, true);
    }

    private static List<AssessmentNode> ends() {
        return List.of(node("src", 1335, 2600, 620, 635), node("tgt", 868, 3345, 620, 635));
    }

    private static AssessmentConnection connection(String id, String source, String target,
            List<double[]> path) {
        return new AssessmentConnection(id, source, target, path, "", 1);
    }

    private static AssessmentNode node(String id, double x, double y, double w, double h) {
        return new AssessmentNode(id, x, y, w, h, null, false, false, id, 0.0, null, null, 0.0,
                0.0, 0.0, false, null, false, AssessmentNode.TEXT_ALIGNMENT_CENTRE,
                AssessmentNode.TEXT_POSITION_TOP);
    }
}
