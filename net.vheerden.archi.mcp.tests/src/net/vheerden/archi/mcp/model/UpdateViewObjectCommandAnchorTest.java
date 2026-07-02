package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateFactory;
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
}
