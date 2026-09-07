package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;

/**
 * Tests the anchor feature rail on {@link UpdateViewObjectCommand}: setting, clearing, defaults,
 * and undo byte-intactness. Anchor is stored as four {@code IFeatures} entries on the child.
 */
public class UpdateViewObjectCommandAnchorTest {

    private IDiagramModelNote note;

    @Before
    public void setUp() {
        note = IArchimateFactory.eINSTANCE.createDiagramModelNote();
        note.setBounds(10, 20, 100, 40);
    }

    private String feat(String key) {
        return note.getFeatures().getString(key, null);
    }

    @Test
    public void shouldWriteAllFourAnchorFeatures_onSet_andRemoveOnUndo() {
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                note, 30, 250, 100, 40, null, null, null, null,
                "target-1", "below", 0, 10);
        assertTrue(cmd.hasAnchorChange());

        cmd.execute();
        assertEquals("target-1", feat(AnchorResolver.ANCHOR_TARGET_FEATURE));
        assertEquals("below", feat(AnchorResolver.ANCHOR_EDGE_FEATURE));
        assertEquals("0", feat(AnchorResolver.ANCHOR_DX_FEATURE));
        assertEquals("10", feat(AnchorResolver.ANCHOR_DY_FEATURE));
        // bounds applied too (caller resolved them)
        assertEquals(30, note.getBounds().getX());
        assertEquals(250, note.getBounds().getY());

        cmd.undo();
        // originally unanchored -> all four removed
        assertNull(feat(AnchorResolver.ANCHOR_TARGET_FEATURE));
        assertNull(feat(AnchorResolver.ANCHOR_EDGE_FEATURE));
        assertNull(feat(AnchorResolver.ANCHOR_DX_FEATURE));
        assertNull(feat(AnchorResolver.ANCHOR_DY_FEATURE));
        assertEquals(10, note.getBounds().getX());
        assertEquals(20, note.getBounds().getY());
    }

    @Test
    public void shouldDefaultEdgeToBelow_andOffsetsToZero() {
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                note, 10, 20, 100, 40, null, null, null, null,
                "target-1", null, null, null);
        cmd.execute();
        assertEquals("below", feat(AnchorResolver.ANCHOR_EDGE_FEATURE));
        assertEquals("0", feat(AnchorResolver.ANCHOR_DX_FEATURE));
        assertEquals("0", feat(AnchorResolver.ANCHOR_DY_FEATURE));
    }

    @Test
    public void shouldClearAnchorFeatures_onEmptyTarget_andRestoreOnUndo() {
        // Pre-existing anchor.
        note.getFeatures().putString(AnchorResolver.ANCHOR_TARGET_FEATURE, "old-target");
        note.getFeatures().putString(AnchorResolver.ANCHOR_EDGE_FEATURE, "right");
        note.getFeatures().putString(AnchorResolver.ANCHOR_DX_FEATURE, "5");
        note.getFeatures().putString(AnchorResolver.ANCHOR_DY_FEATURE, "0");

        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                note, 10, 20, 100, 40, null, null, null, null,
                "", null, null, null);
        assertTrue(cmd.hasAnchorChange());
        cmd.execute();
        assertNull(feat(AnchorResolver.ANCHOR_TARGET_FEATURE));
        assertNull(feat(AnchorResolver.ANCHOR_EDGE_FEATURE));

        cmd.undo();
        assertEquals("old-target", feat(AnchorResolver.ANCHOR_TARGET_FEATURE));
        assertEquals("right", feat(AnchorResolver.ANCHOR_EDGE_FEATURE));
        assertEquals("5", feat(AnchorResolver.ANCHOR_DX_FEATURE));
    }

    @Test
    public void shouldLeaveAnchorUntouched_whenTargetNull() {
        note.getFeatures().putString(AnchorResolver.ANCHOR_TARGET_FEATURE, "keep");
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                note, 99, 99, 100, 40, null, null, null, null,
                null, null, null, null);
        assertFalse(cmd.hasAnchorChange());
        cmd.execute();
        assertEquals("keep", feat(AnchorResolver.ANCHOR_TARGET_FEATURE));
    }

    @Test
    public void nineArgConstructor_shouldLeaveAnchorUnchanged() {
        note.getFeatures().putString(AnchorResolver.ANCHOR_TARGET_FEATURE, "keep");
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                note, 1, 2, 100, 40, null, null, null, null);
        assertFalse(cmd.hasAnchorChange());
        cmd.execute();
        assertEquals("keep", feat(AnchorResolver.ANCHOR_TARGET_FEATURE));
    }

    // ---- execution-time anchor re-resolution --------------------------------------------------
    //
    // The caller resolves an anchor into a concrete x/y while preparing the request. A command
    // queued in the same batch can move the target before this command runs, so execute() checks
    // the target against the bounds it had at construction. These pin both halves of that guard:
    // an unmoved target must write the prepared numbers byte-for-byte, and every way the check can
    // fail must fall back to those same numbers rather than throw mid-commit.

    private IArchimateDiagramModel diagram;
    private IDiagramModelGroup target;

    /** Puts {@link #note} and a target group side by side as top-level children of one diagram. */
    private void withDiagramFixture() {
        diagram = IArchimateFactory.eINSTANCE.createArchimateDiagramModel();
        target = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        target.setId("target-1");
        target.setBounds(100, 100, 200, 50);
        note.setId("note-1");
        diagram.getChildren().add(target);
        diagram.getChildren().add(note);
    }

    private UpdateViewObjectCommand anchorBelowTarget(int preparedX, int preparedY) {
        return new UpdateViewObjectCommand(note, preparedX, preparedY, 100, 40,
                null, null, null, null, "target-1", "below", 0, 10);
    }

    /**
     * The guard that makes every non-deferred call mechanically byte-identical: when the target's
     * bounds are unchanged the command writes the prepared position verbatim, even where that
     * position disagrees with what the edge geometry would produce.
     */
    @Test
    public void shouldWriteThePreparedPositionVerbatim_whenTheAnchorTargetHasNotMoved() {
        withDiagramFixture();
        // Deliberately not the edge-resolved value (which would be 100,160). If the command
        // re-resolved unconditionally, these bytes could not survive.
        UpdateViewObjectCommand cmd = anchorBelowTarget(7, 9);
        cmd.execute();

        assertEquals(7, note.getBounds().getX());
        assertEquals(9, note.getBounds().getY());
    }

    @Test
    public void shouldReresolveFromLiveBounds_whenTheAnchorTargetMovedAfterConstruction() {
        withDiagramFixture();
        UpdateViewObjectCommand cmd = anchorBelowTarget(100, 160);

        target.setBounds(100, 100, 200, 300);
        cmd.execute();

        assertEquals("x still tracks the unchanged left edge", 100, note.getBounds().getX());
        assertEquals("y follows the grown bottom: 100 + 300 + 10", 410, note.getBounds().getY());
    }

    @Test
    public void shouldKeepThePreparedPosition_whenTheAnchorTargetLeftTheDiagram() {
        withDiagramFixture();
        UpdateViewObjectCommand cmd = anchorBelowTarget(100, 160);

        diagram.getChildren().remove(target);
        cmd.execute();

        assertEquals(100, note.getBounds().getX());
        assertEquals(160, note.getBounds().getY());
        assertEquals("the anchor is still recorded even though it could not be re-resolved",
                "target-1", feat(AnchorResolver.ANCHOR_TARGET_FEATURE));
    }

    @Test
    public void shouldKeepThePreparedPosition_whenTheAnchorTargetWasReparented() {
        withDiagramFixture();
        UpdateViewObjectCommand cmd = anchorBelowTarget(100, 160);

        // Same diagram, different coordinate space — resolving across it would write the wrong
        // numbers, so the prepared position stands.
        IDiagramModelGroup other = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        other.setId("other");
        other.setBounds(0, 0, 500, 500);
        diagram.getChildren().add(other);
        diagram.getChildren().remove(target);
        other.getChildren().add(target);
        target.setBounds(0, 0, 200, 300);

        cmd.execute();

        assertEquals(100, note.getBounds().getX());
        assertEquals(160, note.getBounds().getY());
    }

    @Test
    public void shouldKeepThePreparedPosition_whenTheAnchorIsCleared() {
        withDiagramFixture();
        note.getFeatures().putString(AnchorResolver.ANCHOR_TARGET_FEATURE, "target-1");
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(note, 42, 43, 100, 40,
                null, null, null, null, "", null, null, null);

        target.setBounds(100, 100, 200, 300);
        cmd.execute();

        assertEquals("clearing an anchor must not re-resolve against the target being dropped",
                42, note.getBounds().getX());
        assertEquals(43, note.getBounds().getY());
        assertNull(feat(AnchorResolver.ANCHOR_TARGET_FEATURE));
    }

    /**
     * Anchoring to self is rejected when the request is validated. Should one ever reach a command,
     * execution must fall back to the prepared position rather than becoming a second validation
     * site that throws part-way through a commit.
     */
    @Test
    public void shouldKeepThePreparedPosition_whenTheAnchorTargetIsTheObjectItself() {
        withDiagramFixture();
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(note, 11, 12, 100, 40,
                null, null, null, null, "note-1", "below", 0, 10);
        cmd.execute();

        assertEquals(11, note.getBounds().getX());
        assertEquals(12, note.getBounds().getY());
    }

    /**
     * A null snapshot means two different things, and only one of them may keep the prepared
     * position: "no anchor is being set" (nothing to resolve) versus "an anchor is being set but
     * the target could not be found when the command was built". The latter is what a deferred
     * path produces — either object may still be detached — and it must resolve at execute, when
     * both are attached. Here the note is attached only after construction, so the constructor's
     * lookup necessarily failed.
     */
    @Test
    public void shouldResolveAtExecute_whenTheTargetCouldNotBeFoundAtConstruction() {
        diagram = IArchimateFactory.eINSTANCE.createArchimateDiagramModel();
        target = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        target.setId("target-1");
        target.setBounds(100, 100, 200, 50);
        note.setId("note-1");
        diagram.getChildren().add(target);

        // Built while detached — exactly what an add queued earlier in the same batch leaves.
        UpdateViewObjectCommand cmd = anchorBelowTarget(0, 0);
        diagram.getChildren().add(note);
        cmd.execute();

        assertEquals(100, note.getBounds().getX());
        assertEquals("100 + 50 + 10", 160, note.getBounds().getY());
    }

    /** Undo after a healed execute restores the object's own construction-time bounds. */
    @Test
    public void shouldRestoreTheConstructionTimeBounds_whenAHealedExecuteIsUndone() {
        diagram = IArchimateFactory.eINSTANCE.createArchimateDiagramModel();
        target = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        target.setId("target-1");
        target.setBounds(100, 100, 200, 50);
        note.setId("note-1");
        diagram.getChildren().add(target);

        UpdateViewObjectCommand cmd = anchorBelowTarget(0, 0);
        diagram.getChildren().add(note);
        cmd.execute();
        assertEquals(160, note.getBounds().getY());

        cmd.undo();
        assertEquals("undo restores the pre-command bounds, not the resolved ones",
                10, note.getBounds().getX());
        assertEquals(20, note.getBounds().getY());
    }

    /** Re-resolution is idempotent: redo lands on the same position, it does not accumulate. */
    @Test
    public void shouldResolveToTheSamePosition_whenAHealedExecuteIsRedone() {
        diagram = IArchimateFactory.eINSTANCE.createArchimateDiagramModel();
        target = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        target.setId("target-1");
        target.setBounds(100, 100, 200, 50);
        note.setId("note-1");
        diagram.getChildren().add(target);

        UpdateViewObjectCommand cmd = anchorBelowTarget(0, 0);
        diagram.getChildren().add(note);
        cmd.execute();
        cmd.undo();
        cmd.redo();

        assertEquals("redo re-resolves to the same edge, it does not accumulate", 100,
                note.getBounds().getX());
        assertEquals(160, note.getBounds().getY());
    }

    @Test
    public void shouldReresolveOnRedo_afterUndoRestoredTheOriginalBounds() {
        withDiagramFixture();
        UpdateViewObjectCommand cmd = anchorBelowTarget(100, 160);

        target.setBounds(100, 100, 200, 300);
        cmd.execute();
        assertEquals(410, note.getBounds().getY());

        cmd.undo();
        assertEquals("undo restores the object's own pre-command bounds", 20, note.getBounds().getY());
        assertEquals(10, note.getBounds().getX());

        cmd.redo();
        assertEquals("redo re-lands the corrected position, not the prepared one",
                410, note.getBounds().getY());
    }
}
