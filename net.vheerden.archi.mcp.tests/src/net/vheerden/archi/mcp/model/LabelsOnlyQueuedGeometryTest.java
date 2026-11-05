package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;

import org.eclipse.gef.commands.Command;
import org.junit.Test;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModel;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;

/**
 * Inside a batch, {@code mode: "labels-only"} refuses a view the batch has already changed in a way the pass reads:
 * a move, an addition or removal, a bendpoint edit to any connection on the view (a foreign line moves a label as much
 * as its own line does), a queued text position or Label Offset, and a rename of a relationship drawn on the view (the
 * label box is its name). Styling and label visibility are allowed: the label box is a character count, and visibility
 * is read through the queue.
 *
 * <p>Each kind is pinned twice: on {@link QueuedViewGeometry#changes} with {@code Form.LABELS_ONLY}, and through the
 * tool inside a real batch. The other forms are pinned to ignore the label-only kinds, so the new form widened none of
 * them.</p>
 */
public class LabelsOnlyQueuedGeometryTest {

    private static final String SESSION = AutoRouteArmFixture.SESSION;
    private static final int SOURCE = 0;
    private static final int MIDDLE = 1;
    private static final int CENTRE = 2;

    // ==================== on the predicate ====================

    @Test
    public void changes_aQueuedTextPosition_isALabelEdit() {
        AutoRouteArmFixture.Ctx c = LabelsOnlyPassTest.crossed();
        List<Command> leaves = List.of(new SetTextPositionCommand(LabelsOnlyPassTest.conn(c, "l"), SOURCE));

        assertEquals(Map.of(QueuedViewGeometry.LABELS_EDITED, List.of("l")), labelsOnly(c, leaves));
    }

    @Test
    public void changes_aCallersTextPosition_isALabelEdit() {
        AutoRouteArmFixture.Ctx c = LabelsOnlyPassTest.crossed();
        List<Command> leaves = List.of(new UpdateViewConnectionCommand(LabelsOnlyPassTest.conn(c, "l"), null, null,
                null, SOURCE));

        assertEquals(Map.of(QueuedViewGeometry.LABELS_EDITED, List.of("l")), labelsOnly(c, leaves));
    }

    @Test
    public void changes_aQueuedLabelOffset_isALabelEdit() {
        AutoRouteArmFixture.Ctx c = LabelsOnlyPassTest.crossed();
        List<Command> leaves = List.of(new SetTextRelativePositionCommand(LabelsOnlyPassTest.conn(c, "l"), 1));

        assertEquals(Map.of(QueuedViewGeometry.LABELS_EDITED, List.of("l")), labelsOnly(c, leaves));
    }

    @Test
    public void changes_aRenameOfARelationshipOnTheView_isARename() {
        AutoRouteArmFixture.Ctx c = LabelsOnlyPassTest.crossed();
        IArchimateRelationship rel = LabelsOnlyPassTest.conn(c, "l").getArchimateRelationship();
        List<Command> leaves = List.of(new UpdateRelationshipCommand(rel, "renamed", null, null));

        assertEquals(Map.of(QueuedViewGeometry.RENAMED, List.of("rel-l")), labelsOnly(c, leaves));
    }

    /** Same name, documentation only, and a relationship drawn on no view: none of them changes a label box. */
    @Test
    public void changes_aRenameThatChangesNoLabel_isNotARename() {
        AutoRouteArmFixture.Ctx c = LabelsOnlyPassTest.crossed();
        IArchimateRelationship rel = LabelsOnlyPassTest.conn(c, "l").getArchimateRelationship();
        IArchimateRelationship offView = IArchimateFactory.eINSTANCE.createAssociationRelationship();
        offView.setName("before");
        List<Command> leaves = List.of(new UpdateRelationshipCommand(rel, "sends orders", null, null),
                new UpdateRelationshipCommand(rel, null, "documented", null),
                new UpdateRelationshipCommand(offView, "after", null, null));

        assertEquals(Map.of(), labelsOnly(c, leaves));
    }

    /** The unlabelled line f: its bendpoints decide which label slots it crosses. */
    @Test
    public void changes_aBendpointEditToAnUnlabelledConnection_isRefused() {
        AutoRouteArmFixture.Ctx c = LabelsOnlyPassTest.crossed();
        List<Command> leaves = List.of(new UpdateViewConnectionCommand(LabelsOnlyPassTest.conn(c, "f"),
                List.of(IArchimateFactory.eINSTANCE.createDiagramModelBendpoint())));

        assertEquals(Map.of(QueuedViewGeometry.BENDPOINTS_EDITED, List.of("f")), labelsOnly(c, leaves));
    }

    /** A loop a queued route stores on a self-connection is a line some label is scored against. */
    @Test
    public void changes_aQueuedSelfConnectionLoop_isABendpointEdit() {
        AutoRouteArmFixture.Ctx c = LabelsOnlyPassTest.crossed();
        AutoRouteArmFixture.connect(c, "s", "rel-s", "obj-a-n", "obj-a-n");
        List<Command> leaves = List.of(new SelfConnectionLoop.LoopCommand(LabelsOnlyPassTest.conn(c, "s")));

        assertEquals(Map.of(QueuedViewGeometry.BENDPOINTS_EDITED, List.of("s")), labelsOnly(c, leaves));
    }

    /** A change of router type is refused; a write of the router type the view already has is not. */
    @Test
    public void changes_aRouterTypeChange_isRefused_andAnUnchangedOneIsNot() {
        AutoRouteArmFixture.Ctx c = LabelsOnlyPassTest.crossed();
        List<Command> manhattan = List.of(new UpdateViewCommand(c.view, null, null, false, null, null,
                IDiagramModel.CONNECTION_ROUTER_MANHATTAN));
        List<Command> same = List.of(new UpdateViewCommand(c.view, null, null, false, null, null,
                IDiagramModel.CONNECTION_ROUTER_BENDPOINT));

        assertEquals(Map.of(QueuedViewGeometry.ROUTER_CHANGED, List.of(c.view.getId())), labelsOnly(c, manhattan));
        assertEquals(Map.of(), labelsOnly(c, same));
    }

    @Test
    public void changes_aMove_isRefused() {
        AutoRouteArmFixture.Ctx c = LabelsOnlyPassTest.crossed();
        Map<String, List<String>> changes = QueuedViewGeometry.changes(c.view, QueuedViewGeometry.Form.LABELS_ONLY,
                Map.of("obj-c-n", new int[] {200, -200, 60, 60}), null, List.of(), null);

        assertEquals(Map.of(QueuedViewGeometry.MOVED, List.of("obj-c-n")), changes);
    }

    /** The routing forms read none of the label-only kinds: a text position, an offset or a rename leaves them free. */
    @Test
    public void changes_theOtherForms_ignoreTheLabelKinds() {
        AutoRouteArmFixture.Ctx c = LabelsOnlyPassTest.crossed();
        IArchimateRelationship rel = LabelsOnlyPassTest.conn(c, "l").getArchimateRelationship();
        List<Command> leaves = List.of(new SetTextPositionCommand(LabelsOnlyPassTest.conn(c, "l"), SOURCE),
                new SetTextRelativePositionCommand(LabelsOnlyPassTest.conn(c, "l"), 1),
                new UpdateViewConnectionCommand(LabelsOnlyPassTest.conn(c, "l"), null, null, null, SOURCE),
                new UpdateRelationshipCommand(rel, "renamed", null, null));

        for (QueuedViewGeometry.Form form : List.of(QueuedViewGeometry.Form.FULL,
                QueuedViewGeometry.Form.TERMINALS_ONLY, QueuedViewGeometry.Form.CLEAR)) {
            assertEquals(form.name(), Map.of(), QueuedViewGeometry.changes(c.view, form, null, null, leaves, null));
        }
        assertEquals(2, labelsOnly(c, leaves).size());
    }

    // ==================== through the tool, in a real batch ====================

    @Test
    public void tool_aQueuedMove_isRefused() {
        LabelsOnlyRouteTest.Harness h = new LabelsOnlyRouteTest.Harness(LabelsOnlyPassTest.crossed());
        h.dispatcher.beginBatch(SESSION, "move then place");
        h.accessor.updateViewObject(SESSION, "obj-c-n", 200, -200, 60, 60,
                null, null, null, null, null, null, null, null);

        assertRefused(h, "moved or resized: obj-c-n");
    }

    @Test
    public void tool_aQueuedBendpointEditToAnUnlabelledConnection_isRefused() {
        LabelsOnlyRouteTest.Harness h = new LabelsOnlyRouteTest.Harness(LabelsOnlyPassTest.crossed());
        h.dispatcher.beginBatch(SESSION, "bend then place");
        h.accessor.updateViewConnection(SESSION, "f", List.of(new BendpointDto(0, 50, 0, -50)), null, null, null,
                null);

        assertRefused(h, "connection bendpoints edited: f");
    }

    @Test
    public void tool_aQueuedLabelPosition_isRefused() {
        LabelsOnlyRouteTest.Harness h = new LabelsOnlyRouteTest.Harness(LabelsOnlyPassTest.crossed());
        h.dispatcher.beginBatch(SESSION, "position then place");
        h.accessor.updateViewConnection(SESSION, "l", null, null, null, null, SOURCE);

        assertRefused(h, "label positions edited: l");
    }

    /** The first pass keeps the position and queues only an offset; the second reads that offset and is refused. */
    @Test
    public void tool_aQueuedLabelOffset_isRefused() {
        AutoRouteArmFixture.Ctx c = LabelsOnlyPassTest.offsetView(true);
        RelativePositionFeature.set(LabelsOnlyPassTest.conn(c, "l"), CENTRE);
        LabelsOnlyRouteTest.Harness h = new LabelsOnlyRouteTest.Harness(c);
        h.dispatcher.beginBatch(SESSION, "place twice");

        assertTrue(h.labelsOnly().isBatched());
        List<Command> queued = h.dispatcher.queuedLeafCommands(SESSION);
        assertEquals(1, queued.size());
        assertTrue(queued.get(0) instanceof SetTextRelativePositionCommand);

        assertRefused(h, "label positions edited: l");
    }

    @Test
    public void tool_aQueuedRename_isRefused() {
        LabelsOnlyRouteTest.Harness h = new LabelsOnlyRouteTest.Harness(LabelsOnlyPassTest.crossed());
        h.dispatcher.beginBatch(SESSION, "rename then place");
        h.accessor.updateRelationship(SESSION, "rel-l", "renamed", null, null, null);

        assertRefused(h, "relationships renamed: rel-l");
    }

    /** The natural sequence in one batch: a full route, then labels-only over the routes it has not stored yet. */
    @Test
    public void tool_aQueuedFullRoute_isRefused() {
        LabelsOnlyRouteTest.Harness h = new LabelsOnlyRouteTest.Harness(LabelsOnlyPassTest.crossed());
        h.dispatcher.beginBatch(SESSION, "route then place");
        assertTrue(h.call(null, null, false, null, null).isBatched());

        assertRefused(h, "connection bendpoints edited: ");
    }

    /** On a manhattan view the queued route switches the router: the batch is named, not the router it replaces. */
    @Test
    public void tool_aQueuedFullRouteOnAManhattanView_namesTheBatch() {
        AutoRouteArmFixture.Ctx c = LabelsOnlyPassTest.crossed();
        c.view.setConnectionRouterType(IDiagramModel.CONNECTION_ROUTER_MANHATTAN);
        LabelsOnlyRouteTest.Harness h = new LabelsOnlyRouteTest.Harness(c);
        h.dispatcher.beginBatch(SESSION, "route then place");
        assertTrue(h.call(null, null, false, null, null).isBatched());

        assertRefused(h, "router type changed: " + c.view.getId());
        try {
            h.labelsOnly();
        } catch (ModelAccessException e) {
            assertFalse(e.getMessage(), e.getMessage().contains("manhattan router"));
        }
    }

    @Test
    public void tool_aQueuedRouterTypeChange_isRefused() {
        LabelsOnlyRouteTest.Harness h = new LabelsOnlyRouteTest.Harness(LabelsOnlyPassTest.crossed());
        h.dispatcher.beginBatch(SESSION, "switch then place");
        h.accessor.updateView(SESSION, AutoRouteArmFixture.VIEW_ID, null, null, null, null, "manhattan");

        assertRefused(h, "router type changed: " + AutoRouteArmFixture.VIEW_ID);
    }

    @Test
    public void tool_aQueuedStylingEdit_isAllowed() {
        LabelsOnlyRouteTest.Harness h = new LabelsOnlyRouteTest.Harness(LabelsOnlyPassTest.crossed());
        h.dispatcher.beginBatch(SESSION, "restyle then place");
        h.accessor.updateViewConnection(SESSION, "l", null, null,
                new StylingParams(null, "#ff0000", null, null, 2, null, null, null, null, null, null, null, null,
                        null, null, null, null),
                null, null);

        assertTrue(h.labelsOnly().isBatched());
        h.dispatcher.endBatch(SESSION, true);
        assertEquals(SOURCE, LabelsOnlyPassTest.conn(h.c, "l").getTextPosition());
        assertEquals("#ff0000", LabelsOnlyPassTest.conn(h.c, "l").getLineColor());
    }

    /** Hidden in the queue, l becomes a line only: the pass reads that and places nothing. */
    @Test
    public void tool_aQueuedHiddenLabel_isAllowed_andGetsNoCommand() {
        LabelsOnlyRouteTest.Harness h = new LabelsOnlyRouteTest.Harness(LabelsOnlyPassTest.crossed());
        h.dispatcher.beginBatch(SESSION, "hide then place");
        h.accessor.updateViewConnection(SESSION, "l", null, null, null, false, null);
        int queuedBefore = h.dispatcher.queuedLeafCommands(SESSION).size();

        MutationResult<AutoRouteResultDto> result = h.labelsOnly();

        assertEquals(0, result.entity().labelsOptimized());
        assertEquals(queuedBefore, h.dispatcher.queuedLeafCommands(SESSION).size());
        h.dispatcher.endBatch(SESSION, true);
        assertEquals(MIDDLE, LabelsOnlyPassTest.conn(h.c, "l").getTextPosition());
        assertFalse(LabelsOnlyPassTest.conn(h.c, "l").isNameVisible());
    }

    // ==================== helpers ====================

    private static Map<String, List<String>> labelsOnly(AutoRouteArmFixture.Ctx c, List<Command> leaves) {
        return QueuedViewGeometry.changes(c.view, QueuedViewGeometry.Form.LABELS_ONLY, null, null, leaves, null);
    }

    /** Refused with the kind named, and nothing more queued than before. */
    private static void assertRefused(LabelsOnlyRouteTest.Harness h, String kind) {
        int queuedBefore = h.dispatcher.queuedLeafCommands(SESSION).size();
        try {
            h.labelsOnly();
            fail("labels-only queued over: " + kind);
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue(e.getMessage(), e.getMessage().contains(kind));
        }
        assertEquals(queuedBefore, h.dispatcher.queuedLeafCommands(SESSION).size());
    }
}
