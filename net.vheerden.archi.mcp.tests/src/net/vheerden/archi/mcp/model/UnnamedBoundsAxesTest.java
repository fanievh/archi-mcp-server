package net.vheerden.archi.mcp.model;

import static net.vheerden.archi.mcp.model.ApprovalStalenessFixture.SESSION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.ViewPositionSpec;

/**
 * Pins that an {@code update-view-object} which does not name a bounds axis leaves that axis at the
 * value the object holds when the update runs — not the value merged in while the update was
 * prepared — and that every axis the update does name, or computes, is written as before.
 *
 * <p>A batched update runs when the batch commits and a bulk card when it is approved. A rectangle
 * merged at prepare wrote the prepare-time value of every axis the caller left out back over a
 * drag the human made, or a move the agent made, in between.</p>
 */
public class UnnamedBoundsAxesTest {

    private ApprovalStalenessFixture f;
    private IDiagramModelArchimateObject a;

    @Before
    public void setUp() {
        f = new ApprovalStalenessFixture();
        a = f.pair().a();
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    private static List<Integer> bounds(IDiagramModelObject o) {
        return List.of(o.getBounds().getX(), o.getBounds().getY(), o.getBounds().getWidth(),
                o.getBounds().getHeight());
    }

    // ---- the batch arm, with the human dragging while the batch is open ----------------------

    @Test
    public void commit_shouldKeepTheHumansY_whenABatchedUpdateNamesOnlyX() {
        f.dispatcher.beginBatch(SESSION, "x only while a human drags");
        f.accessor.updateViewObject(SESSION, "vo-a", 50, null, null, null, null, null, null, null);
        f.humanBounds(a, 410, 90, 120, 60);
        assertEquals("precondition: the human's drag landed", List.of(410, 90, 120, 60), bounds(a));
        f.dispatcher.endBatch(SESSION, true);

        assertEquals("x is the caller's, y the human's", List.of(50, 90, 120, 60), bounds(a));
        f.stack.undo();
        assertEquals("undo gives back the human's rectangle", List.of(410, 90, 120, 60), bounds(a));
    }

    @Test
    public void commit_shouldKeepTheHumansRectangle_whenABatchedUpdateNamesNoBounds() {
        f.dispatcher.beginBatch(SESSION, "styling only while a human drags");
        f.accessor.updateViewObject(SESSION, "vo-a", null, null, null, null, null,
                new StylingParams("#00aa00", null, null, null, null), null, null);
        f.humanBounds(a, 410, 90, 150, 70);
        f.dispatcher.endBatch(SESSION, true);

        assertEquals(List.of(410, 90, 150, 70), bounds(a));
        assertEquals("#00aa00", a.getFillColor());
    }

    @Test
    public void commit_shouldWriteBothAxes_whenOneBatchQueuesXThenY() {
        f.dispatcher.beginBatch(SESSION, "x then y");
        f.accessor.updateViewObject(SESSION, "vo-a", 11, null, null, null, null, null, null, null);
        f.accessor.updateViewObject(SESSION, "vo-a", null, 22, null, null, null, null, null, null);
        f.dispatcher.endBatch(SESSION, true);
        assertEquals(List.of(11, 22, 120, 60), bounds(a));
    }

    @Test
    public void commit_shouldWriteTheAnchoredPosition_overADrag_becauseTheAnchorSetsXAndY() {
        f.dispatcher.beginBatch(SESSION, "anchor while a human drags");
        f.accessor.updateViewObject(SESSION, "vo-a", null, null, null, null, null, null, null, null,
                "vo-b", "below", 0, 10);
        f.humanBounds(a, 410, 90, 150, 70);
        f.dispatcher.endBatch(SESSION, true);

        assertEquals("below vo-b [700,200,120,60] with a 10 gap, at the size the object has when it runs",
                List.of(700, 270, 150, 70), bounds(a));
    }

    // ---- one bulk call, and a frozen bulk card ----------------------------------------------

    @Test
    public void bulk_shouldWriteBothAxes_whenOneCallNamesXThenY() {
        f.accessor.executeBulk(SESSION, List.of(
                new BulkOperation("update-view-object", Map.of("viewObjectId", "vo-a", "x", 11), null),
                new BulkOperation("update-view-object", Map.of("viewObjectId", "vo-a", "y", 22), null)),
                "x then y", false);
        assertEquals(List.of(11, 22, 120, 60), bounds(a));
    }

    @Test
    public void approve_shouldKeepTheAgentsLaterY_whenAFrozenBulkCardNamesOnlyX() {
        f.approvalRequired = true;
        String proposal;
        try {
            proposal = f.accessor.executeBulk(SESSION, List.of(new BulkOperation("update-view-object",
                    Map.of("viewObjectId", "vo-a", "x", 11), null)), "x only", false)
                    .proposalContext().proposalId();
        } finally {
            f.approvalRequired = false;
        }
        f.accessor.updateViewObject(SESSION, "vo-a", null, 333, null, null, null, null, null, null);
        assertEquals("precondition: the agent's own move landed", List.of(400, 333, 120, 60), bounds(a));

        assertNull("an agent-only change is forgiven", f.approveOrRefusal(proposal));
        assertEquals(List.of(11, 333, 120, 60), bounds(a));
    }

    // ---- a height the prepare computed is written --------------------------------------------

    @Test
    public void commit_shouldWriteAnIconBandHeight_overADrag() {
        IDiagramModelGroup group = f.group("grp", "G", 0, 300, 200, 100);
        f.object("vo-c", f.element("el-c", "Child"), group, 0, 60, 60, 40);
        f.dispatcher.beginBatch(SESSION, "icon corner while a human resizes");
        f.accessor.updateViewObject(SESSION, "grp", null, null, null, null, null, null,
                new ImageParams(null, "bottom-left", null), null);
        f.humanBounds(group, 0, 300, 200, 33);
        f.dispatcher.endBatch(SESSION, true);
        assertTrue("the band-grown height is written, not the human's 33: " + bounds(group),
                group.getBounds().getHeight() > 100);
    }

    // ---- a pass that commits its fit after every entry treats pending axes as named -----------

    /**
     * apply-positions commits the group re-fits it owns after every entry has landed, so when an
     * earlier entry pushed a child out of a group, the group's grown size in the pass's pending
     * bounds comes from a command that runs AFTER the group's own entry. When the group's entry
     * re-states only x, its merged rectangle covers the re-fit, so the re-fit is retired and the
     * entry alone must write the grown size — reading the group's size when the entry runs would
     * read it before the retired re-fit, and leave the child outside.
     */
    @Test
    public void applyPositions_shouldKeepAGroupGrownByAnEarlierEntry_whenTheGroupsEntryNamesOnlyX() {
        IDiagramModelGroup group = f.group("grp", "G", 0, 300, 200, 100);
        IDiagramModelArchimateObject child = f.object("vo-c", f.element("el-c", "Child"), group, 10, 10, 50, 50);
        f.accessor.applyViewLayout(SESSION, f.view.getId(), List.of(
                new ViewPositionSpec("vo-c", 300, 10, null, null),
                new ViewPositionSpec("grp", 0, null, null, null)), null, null);

        assertTrue("the group still contains the child it was grown for: " + bounds(group),
                group.getBounds().getWidth() >= child.getBounds().getX() + child.getBounds().getWidth());
    }

    /**
     * The re-fit an earlier entry provoked runs after every entry, but it is there to keep the
     * group around its children, not to put back where the group stood: the group's own entry
     * moves it, and the re-fit keeps the size containment needs at the position the entry set.
     */
    @Test
    public void applyPositions_shouldMoveAGroupGrownByAnEarlierEntry_toTheXItsOwnEntryNames() {
        IDiagramModelGroup group = f.group("grp", "G", 0, 300, 200, 100);
        IDiagramModelArchimateObject child = f.object("vo-c", f.element("el-c", "Child"), group, 10, 10, 50, 50);
        f.accessor.applyViewLayout(SESSION, f.view.getId(), List.of(
                new ViewPositionSpec("vo-c", 300, 10, null, null),
                new ViewPositionSpec("grp", 5, null, null, null)), null, null);

        assertEquals("the group's own x, at the size its child needs", List.of(5, 300, 360, 100), bounds(group));
        assertEquals(List.of(300, 10, 50, 50), bounds(child));
        f.stack.undo();
        assertEquals("one undo gives back the group as it was", List.of(0, 300, 200, 100), bounds(group));
    }

    /**
     * An entry that asks for a group wider than the re-fit, but no taller than it was, keeps its
     * width: the re-fit grows the axis containment needs and leaves the one the entry enlarged.
     */
    @Test
    public void applyPositions_shouldKeepAWidthTheGroupsEntryEnlarged_whenTheReFitNeedsHeight() {
        IDiagramModelGroup group = f.group("grp", "G", 0, 300, 200, 100);
        f.object("vo-c", f.element("el-c", "Child"), group, 10, 10, 50, 50);
        f.accessor.applyViewLayout(SESSION, f.view.getId(), List.of(
                new ViewPositionSpec("vo-c", 10, 200, null, null),
                new ViewPositionSpec("grp", null, null, 500, 100)), null, null);

        assertEquals(500, group.getBounds().getWidth());
        assertTrue("still tall enough for the child: " + bounds(group), group.getBounds().getHeight() >= 250);
    }

    /**
     * A third entry that pushes the same group again measures it against the settled rectangle,
     * so the re-fit it provokes still contains the child the first push needed room for.
     */
    @Test
    public void applyPositions_shouldKeepRoomForTheFirstPush_whenALaterEntryPushesTheGroupAgain() {
        IDiagramModelGroup group = f.group("grp", "G", 0, 300, 200, 100);
        f.object("vo-c", f.element("el-c", "Child"), group, 10, 10, 50, 50);
        f.object("vo-d", f.element("el-d", "Other"), group, 70, 10, 50, 50);
        f.accessor.applyViewLayout(SESSION, f.view.getId(), List.of(
                new ViewPositionSpec("vo-c", 300, 10, null, null),
                new ViewPositionSpec("grp", 5, null, 200, null),
                new ViewPositionSpec("vo-d", 10, 200, null, null)), null, null);

        assertEquals(5, group.getBounds().getX());
        assertTrue("wide enough for the first push: " + bounds(group), group.getBounds().getWidth() >= 350);
        assertTrue("tall enough for the second push: " + bounds(group), group.getBounds().getHeight() >= 250);
    }

    /**
     * Inside a batch the pass's pending bounds are seeded from what the batch has queued, which
     * runs before this pass. Only the pass's own fits run after its entries, so an entry for an
     * object an earlier queued update touched still keeps the axes it does not name at what the
     * object holds when it runs.
     */
    @Test
    public void applyPositionsInABatch_shouldKeepTheHumansY_overAnEarlierQueuedUpdateOfTheSameObject() {
        f.dispatcher.beginBatch(SESSION, "queued move, then positions, while a human drags");
        f.accessor.updateViewObject(SESSION, "vo-a", 11, null, null, null, null, null, null, null);
        f.accessor.applyViewLayout(SESSION, f.view.getId(),
                List.of(new ViewPositionSpec("vo-a", 50, null, null, null)), null, null);
        f.humanBounds(a, 410, 90, 120, 60);
        f.dispatcher.endBatch(SESSION, true);

        assertEquals("x is the entry's, y the human's", List.of(50, 90, 120, 60), bounds(a));
    }

    /**
     * A group whose own entry moves it after an earlier entry grew it is measured, for its own
     * parent's fit, at the size it ends at — not at the size the entry merged before the re-fit
     * was folded in — so the grandparent still contains it.
     */
    @Test
    public void applyPositions_shouldGrowTheGrandparent_toTheSizeAMovedGroupEndsAt() {
        IDiagramModelGroup outer = f.group("outer", "Outer", 0, 300, 400, 300);
        IDiagramModelGroup inner = f.factory.createDiagramModelGroup();
        inner.setId("inner");
        inner.setName("Inner");
        inner.setBounds(10, 10, 200, 100);
        outer.getChildren().add(inner);
        f.object("vo-c", f.element("el-c", "Child"), inner, 10, 10, 50, 50);
        f.accessor.applyViewLayout(SESSION, f.view.getId(), List.of(
                new ViewPositionSpec("vo-c", 300, 10, null, null),
                new ViewPositionSpec("inner", 100, null, 200, null)), null, null);

        assertEquals("the inner group's own x, at the width its child needs", List.of(100, 10, 360, 100),
                bounds(inner));
        assertTrue("the outer group still contains the inner one: outer " + bounds(outer),
                outer.getBounds().getWidth() >= 100 + 360);
    }

    /** A height the icon band computed belongs to the width it was computed for. */
    @Test
    public void commit_shouldWriteTheWidthAnIconBandHeightWasComputedFor_overAHumanResize() {
        IDiagramModelGroup group = f.group("grp", "G", 0, 300, 200, 100);
        f.object("vo-c", f.element("el-c", "Child"), group, 0, 60, 60, 40);
        f.dispatcher.beginBatch(SESSION, "icon corner while a human resizes");
        f.accessor.updateViewObject(SESSION, "grp", null, null, null, null, null, null,
                new ImageParams(null, "bottom-left", null), null);
        f.humanBounds(group, 0, 300, 150, 33);
        f.dispatcher.endBatch(SESSION, true);
        assertEquals("the width the band was computed for: " + bounds(group), 200, group.getBounds().getWidth());
    }

    // ---- a queued update that sets no axis is not a move -------------------------------------

    @Test
    public void queuedReaders_shouldNotCallAStylingOnlyUpdateAMove_afterAHumanDrag() {
        f.dispatcher.beginBatch(SESSION, "styling only while a human drags");
        f.accessor.updateViewObject(SESSION, "vo-a", null, null, null, null, null,
                new StylingParams("#00aa00", null, null, null, null), null, null);
        f.humanBounds(a, 410, 90, 120, 60);

        assertTrue("the batch has decided nothing about where it goes",
                !f.dispatcher.queuedBounds(SESSION).containsKey("vo-a"));
        assertTrue("nothing a note is placed against moves: "
                + QueuedViewGeometry.contentChanges(f.view, f.dispatcher.queuedLeafCommands(SESSION)),
                QueuedViewGeometry.contentChanges(f.view, f.dispatcher.queuedLeafCommands(SESSION)).isEmpty());
        f.dispatcher.endBatch(SESSION, true);
    }

    @Test
    public void queuedReaders_shouldStillCallAnXOnlyUpdateAMove() {
        f.dispatcher.beginBatch(SESSION, "x only while a human drags");
        f.accessor.updateViewObject(SESSION, "vo-a", 50, null, null, null, null, null, null, null);
        f.humanBounds(a, 410, 90, 120, 60);

        assertTrue(f.dispatcher.queuedBounds(SESSION).containsKey("vo-a"));
        assertTrue(QueuedViewGeometry.contentChanges(f.view, f.dispatcher.queuedLeafCommands(SESSION))
                .values().stream().anyMatch(ids -> ids.contains("vo-a")));
        f.dispatcher.endBatch(SESSION, true);
    }

    // ---- the immediate arm is unchanged ------------------------------------------------------

    @Test
    public void immediate_shouldStoreTheSame_forAPartialBoundsUpdate() {
        f.accessor.updateViewObject(SESSION, "vo-a", null, 77, null, 90, null, null, null, null);
        assertEquals(List.of(400, 77, 120, 90), bounds(a));
        f.stack.undo();
        assertEquals(List.of(400, 0, 120, 60), bounds(a));
    }

    // ---- the command ----------------------------------------------------------------------

    @Test
    public void command_shouldKeepAnUnnamedAxisAtTheValueItMeets_andUndoToIt() {
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(a, 50, 0, 120, 60, null, null, null, null,
                new NamedAxes(true, false, false, false));
        a.setBounds(410, 90, 150, 70);
        f.stack.execute(cmd);
        assertEquals(List.of(50, 90, 150, 70), bounds(a));
        assertEquals("the projection still answers the merged rectangle", 0, cmd.getNewY());
        f.stack.undo();
        assertEquals(List.of(410, 90, 150, 70), bounds(a));
    }

    @Test
    public void undo_shouldLeaveAnAxisItDidNotSet_asItIsNow() {
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(a, 50, 0, 120, 60, null, null, null, null,
                new NamedAxes(true, false, false, false));
        cmd.execute();
        a.setBounds(a.getBounds().getX(), 77, 130, 70);
        cmd.undo();
        assertEquals("x is given back; the axes it did not set stay as they were changed",
                List.of(400, 77, 130, 70), bounds(a));
    }
}
