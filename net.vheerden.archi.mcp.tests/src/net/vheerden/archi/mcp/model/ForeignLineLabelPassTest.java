package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.Test;

import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelConnection;

import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;

/**
 * Both label-optimizer entry points see the lines a label must stay off: the label pass hands the optimizer
 * every unlabelled and hidden-label connection as a line only, and the routing pass hands it the batch it
 * routed, so a connection left out of a subset route is not seen. The assessor, fed by the real collector,
 * counts a hidden label's line too.
 *
 * <p>The fixture: a labelled connection from a (0, 100) to b (500, 100), both 100 x 60, a straight line along
 * y 130. Its 12-character label sits at Middle on (300, 130), x 230..370; its Source slot is x 114..254 and its
 * Target slot x 346..486, both just below the line. A second connection runs straight down at x 300 from c
 * (270, -200) to d (270, 400), through the Middle label only.</p>
 *
 * <p>The labelled relationship is named, so the real collector measures its text: this class realizes SWT and
 * is filed with the display-lane classes.</p>
 */
public class ForeignLineLabelPassTest {

    private static final String SESSION = AutoRouteArmFixture.SESSION;
    private static final String VIEW_ID = AutoRouteArmFixture.VIEW_ID;
    private static final String LABEL = "sends orders";
    private static final int SOURCE = 0;
    private static final int MIDDLE = 1;

    // ==================== the label pass ====================

    @Test
    public void labelPass_anUnlabelledLineThroughTheLabel_movesItToSource() {
        LabelOptimizationPass.Result result = LabelOptimizationPass.compute(view("", true).view, 10, null);

        assertNotNull("before, the pass never saw the unlabelled line and changed nothing", result);
        assertEquals(Map.of("l", SOURCE), textPositions(result.compound));
        assertEquals("only the label is counted", 1, result.labelsOptimized);
    }

    @Test
    public void labelPass_aHiddenLabelsLineThroughTheLabel_movesItToSource() {
        LabelOptimizationPass.Result result = LabelOptimizationPass.compute(view("routes cash", false).view, 10,
                null);

        assertNotNull("before, the pass dropped the hidden-label connection and changed nothing", result);
        assertEquals("the hidden label gets no command", Map.of("l", SOURCE), textPositions(result.compound));
        assertEquals("only the shown label is counted", 1, result.labelsOptimized);
    }

    @Test
    public void labelPass_aLineHiddenByTheBatch_isStillALine() {
        // The model shows the second label; the batch will hide it. It goes in as a line only, as when hidden.
        AutoRouteArmFixture.Ctx c = view("routes cash", true);
        LabelOptimizationPass.Result result = LabelOptimizationPass.compute(c.view, 10, Map.of("f", false));

        assertNotNull(result);
        assertEquals(Map.of("l", SOURCE), textPositions(result.compound));
        assertEquals(1, result.labelsOptimized);
    }

    // ==================== the routing pass ====================

    @Test
    public void routing_everyConnection_movesTheLabelOffTheLine() {
        AutoRouteArmFixture.Ctx c = view("", true);
        AutoRouteArmFixture.route(c, AutoRouteArmFixture.Arm.APPLIED);

        assertEquals(SOURCE, conn(c, "l").getTextPosition());
    }

    /**
     * Documents a known gap, not a wanted behaviour: a subset route hands the optimizer only the lines it
     * routes, so a line outside the subset is not seen and the label stays on it.
     */
    @Test
    public void routing_aSubsetLeavingTheLineOut_doesNotSeeIt_knownGap() {
        AutoRouteArmFixture.Ctx c = view("", true);
        AutoRouteArmFixture.routeIds(c, AutoRouteArmFixture.Arm.APPLIED, null, List.of("l"));

        assertEquals(MIDDLE, conn(c, "l").getTextPosition());
    }

    // ==================== the hide policy ====================

    @Test
    public void hidePolicy_aLabelWithEverySlotOnALine_isNotHidden() {
        // Lines at x 184, 300 and 416 cross the Source, Middle and Target slots; nothing else is near.
        AutoRouteArmFixture.Ctx c = view("", true);
        AutoRouteArmFixture.addHost(c, "s1", 154, -200, 60, 60);
        AutoRouteArmFixture.addHost(c, "s2", 154, 400, 60, 60);
        AutoRouteArmFixture.addHost(c, "t1", 386, -200, 60, 60);
        AutoRouteArmFixture.addHost(c, "t2", 386, 400, 60, 60);
        AutoRouteArmFixture.connect(c, "s", "rel-s", "obj-s1-n", "obj-s2-n");
        AutoRouteArmFixture.connect(c, "t", "rel-t", "obj-t1-n", "obj-t2-n");

        AutoRouteArmFixture.StubEditorModelManager mgr = new AutoRouteArmFixture.StubEditorModelManager();
        mgr.setModels(List.of(c.model));
        MutationDispatcher dispatcher = AutoRouteArmFixture.newDispatcher(c.model);
        dispatcher.setApprovalModeProvider(() -> false);
        ArchiModelAccessorImpl accessor = new ArchiModelAccessorImpl(mgr, dispatcher);
        AutoRouteResultDto dto;
        try {
            dto = accessor.autoRouteConnections(SESSION, VIEW_ID, null, "orthogonal", false, false, 0, 0, null,
                    true, "auto-hide-on-collision").entity();
        } finally {
            accessor.dispose();
        }

        List<String> hidden = new ArrayList<>();
        if (dto.hiddenLabels() != null) {
            dto.hiddenLabels().forEach(h -> hidden.add(h.connectionId()));
        }
        assertEquals(List.of(), hidden);
        assertTrue("the label is still shown", conn(c, "l").isNameVisible());
    }

    // ==================== the assessor ====================

    /**
     * A hidden label's line still counts; the hidden label does not. Shown, the second label sits at Middle on
     * (300, 130), on the first connection's line, so each label lies on the other's line.
     */
    @Test
    public void assess_aHiddenLabelsLineCounts_butTheHiddenLabelDoesNot() {
        assertEquals(List.of("Label on connection 'l' lies on the line of connection 'f'"),
                assessed(view("routes cash", false)).labelOnForeignLineDescriptions());
        assertEquals(List.of("Label on connection 'l' lies on the line of connection 'f'",
                "Label on connection 'f' lies on the line of connection 'l'"),
                assessed(view("routes cash", true)).labelOnForeignLineDescriptions());
    }

    private static LayoutAssessmentResult assessed(AutoRouteArmFixture.Ctx c) {
        List<AssessmentNode> nodes = AssessmentCollector.collectAssessmentNodes(c.view);
        return new LayoutQualityAssessor().assess(nodes,
                AssessmentCollector.collectAssessmentConnections(c.view, nodes), true);
    }

    // ==================== fixture ====================

    /** The labelled connection l (a to b) and the second connection f (c to d), named and shown as given. */
    private static AutoRouteArmFixture.Ctx view(String secondName, boolean secondShown) {
        AutoRouteArmFixture.Ctx c = AutoRouteArmFixture.newView();
        AutoRouteArmFixture.addHost(c, "a", 0, 100, 100, 60);
        AutoRouteArmFixture.addHost(c, "b", 500, 100, 100, 60);
        AutoRouteArmFixture.addHost(c, "c", 270, -200, 60, 60);
        AutoRouteArmFixture.addHost(c, "d", 270, 400, 60, 60);
        AutoRouteArmFixture.connect(c, "l", "rel-l", "obj-a-n", "obj-b-n");
        AutoRouteArmFixture.connect(c, "f", "rel-f", "obj-c-n", "obj-d-n");
        IDiagramModelArchimateConnection l = conn(c, "l");
        l.getArchimateRelationship().setName(LABEL);
        l.setTextPosition(MIDDLE);
        IDiagramModelArchimateConnection f = conn(c, "f");
        f.getArchimateRelationship().setName(secondName);
        f.setNameVisible(secondShown);
        f.setTextPosition(MIDDLE);
        return c;
    }

    private static IDiagramModelArchimateConnection conn(AutoRouteArmFixture.Ctx c, String id) {
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
    private static Map<String, Integer> textPositions(Command command) {
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
}
