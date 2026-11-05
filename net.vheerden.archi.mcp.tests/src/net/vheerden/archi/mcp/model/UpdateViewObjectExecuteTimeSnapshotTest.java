package net.vheerden.archi.mcp.model;

import static net.vheerden.archi.mcp.model.ApprovalStalenessFixture.SESSION;
import static net.vheerden.archi.mcp.model.ApprovalStalenessFixture.human;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IIconic;

import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.ViewObjectDto;
import net.vheerden.archi.mcp.response.dto.ViewPositionSpec;

/**
 * Pins that {@link UpdateViewObjectCommand} takes what its undo restores from the object as it meets
 * it when it runs, writes only the fields the caller named, and re-arms its snapshot on every undo.
 *
 * <p>A proposal is built when the agent proposes and runs when the human approves; a batched update
 * is built when it is queued and runs when the batch commits. Anything applied in between — the
 * agent's own later change, a human's edit, an earlier update in the same batch — used to be undone
 * by Undo, and for styling and image fields the caller did not name, overwritten by the commit.</p>
 */
public class UpdateViewObjectExecuteTimeSnapshotTest {

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

    private static StylingParams fill(String colour) {
        return new StylingParams(colour, null, null, null, null);
    }

    private static StylingParams line(String colour) {
        return new StylingParams(null, colour, null, null, null);
    }

    // ---- the approval arm --------------------------------------------------------------------

    /**
     * A frozen card approved over the agent's own later move: the guard forgives an agent-authored
     * change, so approval goes through, and Undo must give back the agent's move, not the position
     * the object had when the card was proposed.
     */
    @Test
    public void undo_shouldRestoreTheAgentsLaterMove_whenAFrozenPositionsCardIsApprovedOverIt() {
        f.approvalRequired = true;
        String proposal;
        try {
            proposal = f.accessor.applyViewLayout(SESSION, f.view.getId(),
                    List.of(new ViewPositionSpec("vo-a", 100, 100, null, null)), null, null)
                    .proposalContext().proposalId();
        } finally {
            f.approvalRequired = false;
        }
        f.accessor.updateViewObject(SESSION, "vo-a", 300, 300, null, null, null, null, null, null);
        assertEquals("precondition: the agent's own move landed", List.of(300, 300, 120, 60), bounds(a));

        assertNull("an agent-only change is forgiven", f.approveOrRefusal(proposal));
        assertEquals("approval wrote the card's position", List.of(100, 100, 120, 60), bounds(a));

        f.stack.undo();
        assertEquals("undo gives back what the object held just before approval",
                List.of(300, 300, 120, 60), bounds(a));
    }

    // ---- the batch arm -----------------------------------------------------------------------

    @Test
    public void commit_shouldKeepTheHumansLineColour_whenABatchedUpdateNamesOnlyTheFill() {
        a.setLineColor("#111111");
        f.dispatcher.beginBatch(SESSION, "fill while a human recolours the line");
        f.accessor.updateViewObject(SESSION, "vo-a", null, null, null, null, null, fill("#00aa00"), null, null);
        f.stack.execute(human("Recolour line", () -> a.setLineColor("#ff0000"), () -> a.setLineColor("#111111")));
        assertEquals("precondition: the human's colour landed", "#ff0000", a.getLineColor());
        f.dispatcher.endBatch(SESSION, true);

        assertEquals("the named field is written", "#00aa00", a.getFillColor());
        assertEquals("a field the update did not name is left as the human set it", "#ff0000", a.getLineColor());

        f.stack.undo();
        assertNull("undo restores the fill", a.getFillColor());
        assertEquals("and leaves the human's line colour", "#ff0000", a.getLineColor());
    }

    @Test
    public void commit_shouldKeepBoth_andUndoToTheOriginal_whenOneBatchQueuesTwoUpdatesOnOneObject() {
        a.setAlpha(200);
        f.dispatcher.beginBatch(SESSION, "two updates on one object");
        f.accessor.updateViewObject(SESSION, "vo-a", null, null, null, null, null, fill("#00aa00"), null, null);
        f.accessor.updateViewObject(SESSION, "vo-a", null, null, null, null, null, line("#aa0000"), null, null);
        f.dispatcher.endBatch(SESSION, true);

        assertEquals("the first update's fill survives the second", "#00aa00", a.getFillColor());
        assertEquals("the second update's line colour", "#aa0000", a.getLineColor());
        assertEquals("an opacity neither named is untouched", 200, a.getAlpha());

        f.stack.undo();
        assertNull(a.getFillColor());
        assertNull(a.getLineColor());
        assertEquals(200, a.getAlpha());
    }

    @Test
    public void bulk_shouldKeepBoth_andUndoToTheOriginal_whenOneCallUpdatesOneObjectTwice() {
        f.accessor.executeBulk(SESSION, List.of(
                new BulkOperation("update-view-object", Map.of("viewObjectId", "vo-a", "fillColor", "#00aa00"), null),
                new BulkOperation("update-view-object", Map.of("viewObjectId", "vo-a", "lineColor", "#aa0000"), null)),
                "two updates on one object", false);

        assertEquals("the first operation's fill survives the second", "#00aa00", a.getFillColor());
        assertEquals("#aa0000", a.getLineColor());

        f.stack.undo();
        assertNull(a.getFillColor());
        assertNull(a.getLineColor());
    }

    @Test
    public void undo_shouldRestoreTheHumansText_labelExpression_anchorAndImage_whenEditedWhileTheBatchWasOpen() {
        IDiagramModelGroup group = f.group("grp", "Original", 0, 300, 200, 100);
        f.dispatcher.beginBatch(SESSION, "rails while a human edits");
        f.accessor.updateViewObject(SESSION, "grp", null, null, null, null, "Queued", null, null, null);
        f.accessor.updateViewObject(SESSION, "vo-a", null, null, null, null, null, null, null, "${name}");
        f.accessor.updateViewObject(SESSION, "vo-a", null, null, null, null, null, null, null, null,
                "vo-b", "below", 0, 10);
        f.accessor.updateViewObject(SESSION, "vo-a", null, null, null, null, null, null,
                new ImageParams(null, "top-left", null), null);
        f.stack.execute(human("Edit everything", () -> {
            group.setName("Human");
            a.getFeatures().putString(UpdateViewObjectCommand.LABEL_EXPRESSION_FEATURE, "${type}");
            a.getFeatures().putString(AnchorResolver.ANCHOR_TARGET_FEATURE, "grp");
            a.setImagePosition(IIconic.ICON_POSITION_BOTTOM_RIGHT);
        }, () -> { }));
        assertEquals("precondition: the human's edits landed", "Human", group.getName());
        f.dispatcher.endBatch(SESSION, true);
        assertEquals("Queued", group.getName());
        assertEquals(IIconic.ICON_POSITION_TOP_LEFT, a.getImagePosition());

        f.stack.undo();
        assertEquals("text", "Human", group.getName());
        assertEquals("label expression", "${type}",
                a.getFeatures().getString(UpdateViewObjectCommand.LABEL_EXPRESSION_FEATURE, null));
        assertEquals("anchor", "grp", a.getFeatures().getString(AnchorResolver.ANCHOR_TARGET_FEATURE, null));
        assertEquals("image position", IIconic.ICON_POSITION_BOTTOM_RIGHT, a.getImagePosition());
    }

    @Test
    public void undo_shouldRestoreTheImagePathItMet_andTheSourceItMet() {
        a.setImagePath("images/built.png");
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(a, 400, 0, 120, 60, null, null,
                new ImageParams("images/new.png", null, null));
        a.setImagePath("images/ran.png");
        a.setImageSource(IDiagramModelArchimateObject.IMAGE_SOURCE_PROFILE);
        int showIcon = a.getIconVisibleState();
        f.stack.execute(cmd);
        assertEquals("images/new.png", a.getImagePath());
        assertEquals(IDiagramModelArchimateObject.IMAGE_SOURCE_CUSTOM, a.getImageSource());

        a.setIconVisibleState(showIcon == 0 ? 1 : 0);
        int humanShowIcon = a.getIconVisibleState();
        f.stack.undo();
        assertEquals("images/ran.png", a.getImagePath());
        assertEquals("the source it met, not the custom source it wrote",
                IDiagramModelArchimateObject.IMAGE_SOURCE_PROFILE, a.getImageSource());
        assertEquals("a show-icon the update did not name is not restored", humanShowIcon, a.getIconVisibleState());
    }

    // ---- what is named, and what an empty string means ---------------------------------------

    @Test
    public void lineColour_shouldNeverFlipDeriveLineColour() {
        a.setDeriveElementLineColor(true);
        f.stack.execute(new UpdateViewObjectCommand(a, 400, 0, 120, 60, null, line("#aa0000")));
        assertTrue(a.getDeriveElementLineColor());
        a.setDeriveElementLineColor(false);
        f.stack.undo();
        assertFalse("undo restores only the line colour", a.getDeriveElementLineColor());
    }

    @Test
    public void emptyString_shouldClearAColour_resetAGradient_andLeaveAFigureTypeUnchanged() {
        IDiagramModelGroup group = f.group("grp", "G", 0, 300, 200, 100);
        group.setFillColor("#123456");
        group.setGradient(2);
        group.setBorderType(IDiagramModelGroup.BORDER_RECTANGLE);
        StylingParams empties = new StylingParams("", null, null, null, null, "", null, null,
                null, null, null, null, "", null, null, null);
        f.stack.execute(new UpdateViewObjectCommand(group, 0, 300, 200, 100, null, empties));

        assertNull("an empty colour clears it", group.getFillColor());
        assertEquals("an empty gradient resets it", IDiagramModelGroup.GRADIENT_NONE, group.getGradient());
        assertEquals("an empty figure type changes nothing", IDiagramModelGroup.BORDER_RECTANGLE,
                group.getBorderType());

        f.stack.undo();
        assertEquals("#123456", group.getFillColor());
        assertEquals(2, group.getGradient());
    }

    @Test
    public void emptyBorderType_shouldResetANoteToTheDogEar() {
        IDiagramModelNote note = f.factory.createDiagramModelNote();
        note.setBounds(0, 500, 100, 50);
        note.setBorderType(IDiagramModelNote.BORDER_NONE);
        f.view.getChildren().add(note);
        f.stack.execute(new UpdateViewObjectCommand(note, 0, 500, 100, 50, null,
                new StylingParams(null, null, null, null, null, null, null, null,
                        null, null, null, null, null, "", null, null)));
        assertEquals(IDiagramModelNote.BORDER_DOGEAR, note.getBorderType());
        f.stack.undo();
        assertEquals(IDiagramModelNote.BORDER_NONE, note.getBorderType());
    }

    @Test
    public void font_shouldMergeIntoTheFontTheObjectHoldsWhenTheUpdateRuns() {
        a.setFont("Arial|9|0");
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(a, 400, 0, 120, 60, null,
                new StylingParams(null, null, null, null, null, null, null, null,
                        null, null, "bold", null, null, null, null, null));
        a.setFont("Courier|12|0");
        f.stack.execute(cmd);
        assertEquals("1|Courier|12.0|1|", a.getFont());
        f.stack.undo();
        assertEquals("Courier|12|0", a.getFont());
    }

    // ---- the snapshot is re-armed by undo ----------------------------------------------------

    @Test
    public void execute_shouldSnapshotAgain_afterAnUndo_soARealRunAfterASpeculativeOneUndoesToWhatItMet() {
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(a, 10, 20, 120, 60, null, fill("#00aa00"));
        cmd.execute();
        cmd.undo();
        assertEquals("the speculative run is rolled back", List.of(400, 0, 120, 60), bounds(a));

        a.setBounds(555, 66, 120, 60);
        a.setFillColor("#ff0000");
        cmd.execute();
        cmd.undo();
        assertEquals("undo lands on what the second run met", List.of(555, 66, 120, 60), bounds(a));
        assertEquals("#ff0000", a.getFillColor());
    }

    @Test
    public void execute_shouldKeepItsSnapshot_whenRunAgainWithoutAnUndo() {
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(a, 10, 20, 120, 60, null, fill("#00aa00"));
        cmd.execute();
        cmd.execute();
        cmd.undo();
        assertEquals("undo restores what was there before the first run", List.of(400, 0, 120, 60), bounds(a));
        assertNull(a.getFillColor());
    }

    @Test
    public void undo_shouldChangeNothing_beforeAnyExecute() {
        a.setFillColor("#ff0000");
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(a, 10, 20, 30, 40, "text", fill("#00aa00"));
        cmd.undo();
        assertEquals(List.of(400, 0, 120, 60), bounds(a));
        assertEquals("#ff0000", a.getFillColor());
    }

    @Test
    public void stack_undoRedoUndo_shouldBeStable() {
        a.setFillColor("#ff0000");
        f.stack.execute(new UpdateViewObjectCommand(a, 10, 20, 130, 70, null, fill("#00aa00"), null, "${name}"));
        for (int round = 0; round < 2; round++) {
            f.stack.undo();
            assertEquals(List.of(400, 0, 120, 60), bounds(a));
            assertEquals("#ff0000", a.getFillColor());
            assertNull(a.getFeatures().getString(UpdateViewObjectCommand.LABEL_EXPRESSION_FEATURE, null));
            f.stack.redo();
            assertEquals(List.of(10, 20, 130, 70), bounds(a));
            assertEquals("#00aa00", a.getFillColor());
            assertEquals("${name}", a.getFeatures().getString(UpdateViewObjectCommand.LABEL_EXPRESSION_FEATURE, null));
        }
    }

    // ---- the immediate arm is unchanged ------------------------------------------------------

    @Test
    public void immediate_shouldStoreAndReportTheSame_forStylingImageTextLabelAndAnchor() {
        a.setLineColor("#111111");
        a.setAlpha(200);
        a.setFont("Arial|9|0");
        MutationResult<ViewObjectDto> result = f.accessor.updateViewObject(SESSION, "vo-a", null, null, null, null,
                null, new StylingParams("#00bb00", null, null, null, null, null, null, null,
                        null, null, "italic", null, null, null, null, null),
                new ImageParams(null, "middle-centre", null), "${name}", "vo-b", "right", 5, 0);
        ViewObjectDto dto = result.entity();
        assertEquals("#00bb00", dto.fillColor());
        assertEquals("#111111", dto.lineColor());
        assertEquals("${name}", dto.labelExpression());

        assertEquals("#00bb00", a.getFillColor());
        assertEquals("#111111", a.getLineColor());
        assertEquals(200, a.getAlpha());
        assertEquals("1|Arial|9.0|2|", a.getFont());
        assertEquals(IIconic.ICON_POSITION_MIDDLE_CENTRE, a.getImagePosition());
        assertEquals("${name}", a.getFeatures().getString(UpdateViewObjectCommand.LABEL_EXPRESSION_FEATURE, null));
        assertEquals("vo-b", a.getFeatures().getString(AnchorResolver.ANCHOR_TARGET_FEATURE, null));
        assertEquals("anchored right of vo-b", List.of(825, 200, 120, 60), bounds(a));

        f.stack.undo();
        assertNull(a.getFillColor());
        assertEquals("#111111", a.getLineColor());
        assertEquals(200, a.getAlpha());
        assertEquals("Arial|9|0", a.getFont());
        assertNull(a.getFeatures().getString(UpdateViewObjectCommand.LABEL_EXPRESSION_FEATURE, null));
        assertNull(a.getFeatures().getString(AnchorResolver.ANCHOR_TARGET_FEATURE, null));
        assertEquals(List.of(400, 0, 120, 60), bounds(a));
    }

    @Test
    public void immediate_shouldStoreTheSame_forANotesText() {
        IDiagramModelNote note = f.factory.createDiagramModelNote();
        note.setId("note-1");
        note.setBounds(0, 500, 185, 80);
        note.setContent("before");
        f.view.getChildren().add(note);
        f.accessor.updateViewObject(SESSION, "note-1", null, null, null, null, "after", null, null, null);
        assertEquals("after", note.getContent());
        f.stack.undo();
        assertEquals("before", note.getContent());
    }

    // ---- what the object takes is judged when the update runs --------------------------------

    @Test
    public void figureType_shouldNotBeWritten_whenTheBoxStoppedBeingAGroupingBeforeItRan() {
        IArchimateElement grouping = f.factory.createGrouping();
        grouping.setId("el-grouping");
        f.model.getFolder(FolderType.OTHER).getElements().add(grouping);
        IDiagramModelArchimateObject box = f.object("vo-g", grouping, f.view, 0, 400, 120, 60);
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(box, 0, 400, 120, 60, null,
                new StylingParams(null, null, null, null, null, "rectangular", null, null));
        assertTrue("precondition: a Grouping box takes a figure type", cmd.getNewFigureType() != null);
        int typeBefore = box.getType();

        box.setArchimateElement(f.element("el-plain", "Plain"));
        f.stack.execute(cmd);
        assertEquals("a box that is no longer a Grouping keeps its own figure", typeBefore, box.getType());
        f.stack.undo();
        assertEquals(typeBefore, box.getType());
    }
}
