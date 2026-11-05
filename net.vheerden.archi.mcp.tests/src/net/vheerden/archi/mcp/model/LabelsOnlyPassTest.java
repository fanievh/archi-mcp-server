package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.Test;

import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelConnection;

/**
 * The labels-only entry of {@link LabelOptimizationPass} changes the labels only when that scores strictly lower than
 * they already do, by the optimizer's own measure with each label's anchor: the best trial, or the best anchor for
 * each Middle label at the positions they hold when that scores strictly lower than the best trial. A second run over
 * the result is a no-op.
 *
 * <p>The guard fixtures are views of straight connections (no bendpoints, centre to centre) between 120 x 55 boxes,
 * found by a seeded search over the optimizer: where no Label Offset ships, the best of the 10 trials scores worse
 * than, or the same as, the stored positions while moving a label (pinned in
 * {@code LabelPositionOptimizerScoreAssignmentTest}). Here the offset ships, and an anchor changes what is best.
 * Elements are unnamed (the pass never measures text through SWT); only relationships are named, and the label box is
 * a character count.</p>
 */
public class LabelsOnlyPassTest {

    private static final int SOURCE = 0;
    private static final int MIDDLE = 1;
    private static final int TARGET = 2;
    private static final int NORTH = 1;
    private static final int CENTRE = 2;
    private static final int WEST = 8;
    private static final int EAST = 16;

    // ==================== the no-worse guard ====================

    @Test
    public void anAnchorAtThePositionsHeld_beatsAMove_andARerunIsANoOp() {
        // Without the offset the best trial moves c2 to Target and scores worse than the view; with it, East lifts
        // c2's Middle label clear where it is.
        AutoRouteArmFixture.Ctx c = worse();

        LabelOptimizationPass.LabelsOnlyResult result = LabelOptimizationPass.computeLabelsOnly(c.view, 10, null);

        assertEquals(Map.of(), textPositions(result.compound()));
        assertEquals(Map.of("c2", EAST), offsets(result.compound()));
        assertFalse("an anchor changed", result.unchanged());
        assertEquals(3, result.labels());
        assertRerunIsANoOp(c, result);
    }

    @Test
    public void bestTrialScoringLower_moves_withTheAnchorItChose() {
        // Without the offset the best trial moves c0 to Target for no gain; with it, c0 at Middle offset West scores
        // strictly lower than the view and than any anchor at the positions held.
        AutoRouteArmFixture.Ctx c = churn();

        LabelOptimizationPass.LabelsOnlyResult result = LabelOptimizationPass.computeLabelsOnly(c.view, 10, null);

        assertEquals(Map.of("c0", MIDDLE), textPositions(result.compound()));
        assertEquals(Map.of("c0", WEST), offsets(result.compound()));
        assertFalse(result.unchanged());
        assertRerunIsANoOp(c, result);
    }

    /**
     * A line crossing the Middle label. Source is clear of it, and so is the label offset West (North and South still
     * cross the line): the two score the same, and the tie goes to the best trial, which moves the label to Source.
     */
    @Test
    public void bestTrialTyingTheAnchorsInPlace_moves() {
        AutoRouteArmFixture.Ctx c = crossed();

        LabelOptimizationPass.LabelsOnlyResult result = LabelOptimizationPass.computeLabelsOnly(c.view, 10, null);

        assertEquals(Map.of("l", SOURCE), textPositions(result.compound()));
        assertEquals(Map.of(), offsets(result.compound()));
        assertFalse(result.unchanged());
        assertEquals("the line is not a label", 1, result.labels());
        assertRerunIsANoOp(c, result);
    }

    /** Applies {@code result} and runs the pass again over the view it leaves: nothing more to change. */
    private static void assertRerunIsANoOp(AutoRouteArmFixture.Ctx c, LabelOptimizationPass.LabelsOnlyResult result) {
        for (Object command : result.compound().getCommands()) {
            ((Command) command).execute();
        }
        LabelOptimizationPass.LabelsOnlyResult again = LabelOptimizationPass.computeLabelsOnly(c.view, 10, null);
        assertTrue("a second run changes nothing", again.compound().isEmpty());
    }

    @Test
    public void aViewWithNoShownLabel_placesNothing() {
        AutoRouteArmFixture.Ctx c = crossed();
        conn(c, "l").setNameVisible(false);

        LabelOptimizationPass.LabelsOnlyResult result = LabelOptimizationPass.computeLabelsOnly(c.view, 10, null);

        assertTrue(result.compound().isEmpty());
        assertEquals(0, result.labels());
        assertFalse("nothing to keep", result.unchanged());
    }

    // ==================== offsets when the positions stay ====================

    /**
     * Middle sits on box x; Source and Target each sit on a box too, so no trial is lower and the label stays at
     * Middle. A held North lifts it clear of x: kept, nothing written. Held at Centre, the pass searches and writes
     * one, which shows the kept case is not vacuous.
     */
    @Test
    public void positionsHeld_aHeldOffsetThatClears_isKept() {
        AutoRouteArmFixture.Ctx c = offsetView(true);
        RelativePositionFeature.set(conn(c, "l"), NORTH);

        LabelOptimizationPass.LabelsOnlyResult held = LabelOptimizationPass.computeLabelsOnly(c.view, 10, null);

        assertTrue(held.unchanged());
        assertTrue(held.compound().isEmpty());

        RelativePositionFeature.set(conn(c, "l"), CENTRE);
        LabelOptimizationPass.LabelsOnlyResult searched = LabelOptimizationPass.computeLabelsOnly(c.view, 10, null);

        assertFalse(searched.unchanged());
        assertEquals(Map.of("l", NORTH), offsets(searched.compound()));
        assertEquals(Map.of(), textPositions(searched.compound()));
    }

    /** Middle is clear; a held North draws the label onto box y above it: reset to Centre, the position held. */
    @Test
    public void positionsHeld_aStaleHeldOffset_isReset() {
        AutoRouteArmFixture.Ctx c = offsetView(false);
        RelativePositionFeature.set(conn(c, "l"), NORTH);

        LabelOptimizationPass.LabelsOnlyResult result = LabelOptimizationPass.computeLabelsOnly(c.view, 10, null);

        assertFalse(result.unchanged());
        assertEquals(Map.of("l", CENTRE), offsets(result.compound()));
        assertEquals(Map.of(), textPositions(result.compound()));
    }

    // ==================== fixtures ====================

    /** b0 60,140 / b1 20,320 / b2 280,40; c0 b0-b2 Target, c1 b1-b2 Source, c2 b1-b2 Middle. */
    static AutoRouteArmFixture.Ctx worse() {
        AutoRouteArmFixture.Ctx c = AutoRouteArmFixture.newView();
        AutoRouteArmFixture.addHost(c, "b0", 60, 140, 120, 55);
        AutoRouteArmFixture.addHost(c, "b1", 20, 320, 120, 55);
        AutoRouteArmFixture.addHost(c, "b2", 280, 40, 120, 55);
        labelled(c, "c0", "b0", "b2", "sends orders", TARGET);
        labelled(c, "c1", "b1", "b2", "uses", SOURCE);
        labelled(c, "c2", "b1", "b2", "serves", MIDDLE);
        return c;
    }

    /** b0 400,40 / b1 100,80 / b2 160,0; c0 b1-b2 Source, c1 b1-b2 Target. */
    static AutoRouteArmFixture.Ctx churn() {
        AutoRouteArmFixture.Ctx c = AutoRouteArmFixture.newView();
        AutoRouteArmFixture.addHost(c, "b0", 400, 40, 120, 55);
        AutoRouteArmFixture.addHost(c, "b1", 100, 80, 120, 55);
        AutoRouteArmFixture.addHost(c, "b2", 160, 0, 120, 55);
        labelled(c, "c0", "b1", "b2", "uses", SOURCE);
        labelled(c, "c1", "b1", "b2", "notifies", TARGET);
        return c;
    }

    /**
     * l from a (0, 100) to b (500, 100), 100 x 60, a straight line along y 130, "sends orders" at Middle on (300, 130);
     * an unlabelled line down x 300 from c (270, -200) to d (270, 400) crosses the Middle label only.
     */
    static AutoRouteArmFixture.Ctx crossed() {
        AutoRouteArmFixture.Ctx c = AutoRouteArmFixture.newView();
        AutoRouteArmFixture.addHost(c, "a", 0, 100, 100, 60);
        AutoRouteArmFixture.addHost(c, "b", 500, 100, 100, 60);
        AutoRouteArmFixture.addHost(c, "c", 270, -200, 60, 60);
        AutoRouteArmFixture.addHost(c, "d", 270, 400, 60, 60);
        labelled(c, "l", "a", "b", "sends orders", MIDDLE);
        AutoRouteArmFixture.connect(c, "f", "rel-f", "obj-c-n", "obj-d-n");
        return c;
    }

    /**
     * l from a (0, 100) to b (500, 100) as in {@link #crossed}, with boxes s and t on its Source and Target slots.
     * With {@code onMiddle}, box x (280, 125, 40, 40) sits on the Middle label; without, box y (260, 60, 80, 55) sits
     * just above it, where a North offset draws the label.
     */
    static AutoRouteArmFixture.Ctx offsetView(boolean onMiddle) {
        AutoRouteArmFixture.Ctx c = AutoRouteArmFixture.newView();
        AutoRouteArmFixture.addHost(c, "a", 0, 100, 100, 60);
        AutoRouteArmFixture.addHost(c, "b", 500, 100, 100, 60);
        AutoRouteArmFixture.addHost(c, "s", 150, 135, 60, 40);
        AutoRouteArmFixture.addHost(c, "t", 390, 135, 60, 40);
        if (onMiddle) {
            AutoRouteArmFixture.addHost(c, "x", 280, 125, 40, 40);
        } else {
            AutoRouteArmFixture.addHost(c, "y", 260, 60, 80, 55);
        }
        labelled(c, "l", "a", "b", "sends orders", MIDDLE);
        return c;
    }

    static void labelled(AutoRouteArmFixture.Ctx c, String id, String from, String to, String name, int position) {
        AutoRouteArmFixture.connect(c, id, "rel-" + id, "obj-" + from + "-n", "obj-" + to + "-n");
        IDiagramModelArchimateConnection conn = conn(c, id);
        conn.getArchimateRelationship().setName(name);
        conn.setTextPosition(position);
    }

    static IDiagramModelArchimateConnection conn(AutoRouteArmFixture.Ctx c, String id) {
        for (var child : c.view.getChildren()) {
            for (IDiagramModelConnection conn : child.getSourceConnections()) {
                if (id.equals(conn.getId())) {
                    return (IDiagramModelArchimateConnection) conn;
                }
            }
        }
        throw new AssertionError("no connection " + id);
    }

    /** Connection id to the text position each {@link SetTextPositionCommand} in the command sets. */
    static Map<String, Integer> textPositions(Command command) {
        Map<String, Integer> positions = new LinkedHashMap<>();
        if (command instanceof SetTextPositionCommand set) {
            positions.put(set.getConnection().getId(), set.getNewTextPosition());
        } else if (command instanceof CompoundCommand compound) {
            for (Object child : compound.getCommands()) {
                positions.putAll(textPositions((Command) child));
            }
        }
        return positions;
    }

    /** Connection id to the anchor each {@link SetTextRelativePositionCommand} in the command sets. */
    static Map<String, Integer> offsets(Command command) {
        Map<String, Integer> anchors = new LinkedHashMap<>();
        if (command instanceof SetTextRelativePositionCommand set) {
            anchors.put(set.getConnection().getId(), set.getNewRelativePosition());
        } else if (command instanceof CompoundCommand compound) {
            for (Object child : compound.getCommands()) {
                anchors.putAll(offsets((Command) child));
            }
        }
        return anchors;
    }
}
