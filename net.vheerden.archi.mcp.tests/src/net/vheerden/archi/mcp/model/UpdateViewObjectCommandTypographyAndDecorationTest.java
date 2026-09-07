package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;

/**
 * Single-undo-unit pins for the typography and decoration styling-rail extensions to
 * {@link UpdateViewObjectCommand}: gradient, note borderType, deriveLineColor,
 * outlineOpacity, and the typography composite-string merge.
 *
 * <p>Each test follows the predecessor pattern:
 * construct → capture-at-construction → execute → assert new state → undo →
 * assert old state restored. The single-undo-unit property is satisfied by construction
 * since all typography and decoration fields ride the existing styling rail and share the same
 * {@code hasStylingChange} boundary.</p>
 */
public class UpdateViewObjectCommandTypographyAndDecorationTest {

    private static IDiagramModelGroup freshGroup() {
        IDiagramModelGroup group = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        IBounds bounds = IArchimateFactory.eINSTANCE.createBounds();
        bounds.setX(0); bounds.setY(0); bounds.setWidth(100); bounds.setHeight(50);
        group.setBounds(bounds);
        return group;
    }

    private static IDiagramModelNote freshNote() {
        IDiagramModelNote note = IArchimateFactory.eINSTANCE.createDiagramModelNote();
        IBounds bounds = IArchimateFactory.eINSTANCE.createBounds();
        bounds.setX(0); bounds.setY(0); bounds.setWidth(100); bounds.setHeight(50);
        note.setBounds(bounds);
        return note;
    }

    private static StylingParams onlyTypographyAndDecoration(String gradient, String borderType,
                                         Boolean deriveLineColor, Integer outlineOpacity,
                                         String fontName, Integer fontSize, String fontStyle) {
        return new StylingParams(
                null, null, null, null, null,
                null, null, null,
                fontName, fontSize, fontStyle,
                null, gradient, borderType, deriveLineColor, outlineOpacity);
    }

    private static StylingParams onlyLineStyle(String lineStyle) {
        return new StylingParams(
                null, null, null, null, null, null, null, null,
                null, null, null, lineStyle, null, null, null, null);
    }

    // ------------------------------------------------------------------
    // Gradient — set / undo
    // ------------------------------------------------------------------

    @Test
    public void shouldApplyGradient_whenStylingProvided() {
        IDiagramModelGroup group = freshGroup();
        assertEquals(IDiagramModelObject.GRADIENT_NONE, group.getGradient());

        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                group, 0, 0, 100, 50, null,
                onlyTypographyAndDecoration("top-bottom", null, null, null, null, null, null), null);

        cmd.execute();
        assertEquals(0, group.getGradient());   // top-bottom → 0
    }

    @Test
    public void shouldUndoGradient_whenUndone() {
        IDiagramModelGroup group = freshGroup();
        // Pre-set gradient — captured as oldGradient.
        group.setGradient(2);   // right-left

        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                group, 0, 0, 100, 50, null,
                onlyTypographyAndDecoration("top-bottom", null, null, null, null, null, null), null);
        cmd.execute();
        assertEquals(0, group.getGradient());
        cmd.undo();
        assertEquals(2, group.getGradient());   // restored to right-left
    }

    @Test
    public void shouldClearGradientToDefault_whenEmptyString() {
        IDiagramModelGroup group = freshGroup();
        group.setGradient(1);  // left-right

        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                group, 0, 0, 100, 50, null,
                onlyTypographyAndDecoration("", null, null, null, null, null, null), null);
        cmd.execute();
        assertEquals(IDiagramModelObject.GRADIENT_NONE, group.getGradient());
        cmd.undo();
        assertEquals(1, group.getGradient());
    }

    // ------------------------------------------------------------------
    // borderType — set / undo (note only)
    // ------------------------------------------------------------------

    @Test
    public void shouldApplyBorderType_onNote() {
        IDiagramModelNote note = freshNote();
        assertEquals(IDiagramModelNote.BORDER_DOGEAR, note.getBorderType());

        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                note, 0, 0, 100, 50, null,
                onlyTypographyAndDecoration(null, "rectangle", null, null, null, null, null), null);
        cmd.execute();
        assertEquals(IDiagramModelNote.BORDER_RECTANGLE, note.getBorderType());

        cmd.undo();
        assertEquals(IDiagramModelNote.BORDER_DOGEAR, note.getBorderType());
    }

    @Test
    public void shouldNotApplyBorderType_onGroup() {
        // Predecessor figureType uses tabbed/rectangular vocabulary on groups; the
        // borderType field (dogear/rectangle/none) is note-only — applying to a group
        // must NOT call setBorderType. The command's oldBorderType is null for
        // non-notes, so the apply-branch is a no-op.
        IDiagramModelGroup group = freshGroup();
        int initialBorderType = group.getBorderType();

        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                group, 0, 0, 100, 50, null,
                onlyTypographyAndDecoration(null, "rectangle", null, null, null, null, null), null);
        cmd.execute();
        // Group's borderType is its figureType semantic — unchanged by note borderType.
        assertEquals(initialBorderType, group.getBorderType());

        assertNull(cmd.getOldBorderType());
        assertNull(cmd.getNewBorderType());
    }

    // ------------------------------------------------------------------
    // deriveLineColor — set / undo
    // ------------------------------------------------------------------

    @Test
    public void shouldApplyDeriveLineColor() {
        IDiagramModelGroup group = freshGroup();
        assertTrue(group.getDeriveElementLineColor());  // Archi default true

        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                group, 0, 0, 100, 50, null,
                onlyTypographyAndDecoration(null, null, Boolean.FALSE, null, null, null, null), null);
        cmd.execute();
        assertFalse(group.getDeriveElementLineColor());

        cmd.undo();
        assertTrue(group.getDeriveElementLineColor());
    }

    // ------------------------------------------------------------------
    // outlineOpacity — set / undo
    // ------------------------------------------------------------------

    @Test
    public void shouldApplyOutlineOpacity() {
        IDiagramModelGroup group = freshGroup();
        assertEquals(IDiagramModelObject.FEATURE_LINE_ALPHA_DEFAULT, group.getLineAlpha());

        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                group, 0, 0, 100, 50, null,
                onlyTypographyAndDecoration(null, null, null, 128, null, null, null), null);
        cmd.execute();
        assertEquals(128, group.getLineAlpha());

        cmd.undo();
        assertEquals(IDiagramModelObject.FEATURE_LINE_ALPHA_DEFAULT, group.getLineAlpha());
    }

    // ------------------------------------------------------------------
    // Typography — set / undo (composite-string merge)
    // ------------------------------------------------------------------

    @Test
    public void shouldApplyFont_whenAllThreeTypographyFieldsProvided() {
        IDiagramModelGroup group = freshGroup();
        assertNull(group.getFont());

        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                group, 0, 0, 100, 50, null,
                onlyTypographyAndDecoration(null, null, null, null, "Arial", 14, "bold"), null);
        cmd.execute();
        assertEquals("Arial|14|1", group.getFont());

        cmd.undo();
        assertNull(group.getFont());   // restored to original (null)
    }

    @Test
    public void shouldMergeFontSizeOnly_whenOtherComponentsAbsent() {
        IDiagramModelGroup group = freshGroup();
        group.setFont("Segoe UI|9|0");   // pre-existing font: Segoe UI / 9pt / NORMAL

        // Update size only.
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                group, 0, 0, 100, 50, null,
                onlyTypographyAndDecoration(null, null, null, null, null, 18, null), null);
        cmd.execute();
        assertEquals("Segoe UI|18|0", group.getFont());

        cmd.undo();
        assertEquals("Segoe UI|9|0", group.getFont());
    }

    // ------------------------------------------------------------------
    // lineStyle (view-object) — post Task-9 empirical correction
    // ------------------------------------------------------------------

    @Test
    public void shouldApplyLineStyle_dashed_onViewObject() {
        IDiagramModelGroup group = freshGroup();
        assertEquals(IDiagramModelObject.LINE_STYLE_DEFAULT, group.getLineStyle());

        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                group, 0, 0, 100, 50, null, onlyLineStyle("dashed"), null);
        cmd.execute();
        assertEquals(IDiagramModelObject.LINE_STYLE_DASHED, group.getLineStyle());

        cmd.undo();
        assertEquals(IDiagramModelObject.LINE_STYLE_DEFAULT, group.getLineStyle());
    }

    @Test
    public void shouldApplyLineStyle_none() {
        IDiagramModelGroup group = freshGroup();
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                group, 0, 0, 100, 50, null, onlyLineStyle("none"), null);
        cmd.execute();
        assertEquals(IDiagramModelObject.LINE_STYLE_NONE, group.getLineStyle());
    }

    @Test
    public void shouldClearLineStyleToDefault_onEmptyString() {
        IDiagramModelGroup group = freshGroup();
        group.setLineStyle(IDiagramModelObject.LINE_STYLE_DOTTED);

        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                group, 0, 0, 100, 50, null, onlyLineStyle(""), null);
        cmd.execute();
        assertEquals(IDiagramModelObject.LINE_STYLE_DEFAULT, group.getLineStyle());

        cmd.undo();
        assertEquals(IDiagramModelObject.LINE_STYLE_DOTTED, group.getLineStyle());
    }

    // ------------------------------------------------------------------
    // back-compat byte-identical when no typography or decoration fields set
    // ------------------------------------------------------------------

    @Test
    public void shouldLeaveAllTypographyAndDecorationFieldsAtDefault_whenNoStylingProvided() {
        IDiagramModelGroup group = freshGroup();
        int initialGradient = group.getGradient();
        boolean initialDerive = group.getDeriveElementLineColor();
        int initialLineAlpha = group.getLineAlpha();
        String initialFont = group.getFont();

        // Construct a command with NO typography or decoration fields (all null) but with
        // a pre-existing styling field (lineWidth) to trigger the styling rail.
        StylingParams preExistingStyling = new StylingParams(
                "#FF0000", null, null, null, 2,
                null, null, null,
                null, null, null, null, null, null, null, null);

        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                group, 0, 0, 100, 50, null, preExistingStyling, null);
        cmd.execute();

        // Typography and decoration fields unchanged from their captured values (the initial defaults).
        assertEquals(initialGradient, group.getGradient());
        assertEquals(initialDerive, group.getDeriveElementLineColor());
        assertEquals(initialLineAlpha, group.getLineAlpha());
        assertEquals(initialFont, group.getFont());
    }

    // ------------------------------------------------------------------
    // single undo unit covering the pre-existing and the typography/decoration fields together
    // ------------------------------------------------------------------

    @Test
    public void shouldUndoAllTypographyAndDecorationFieldsTogether() {
        IDiagramModelNote note = freshNote();

        // Combine ALL typography and decoration fields plus a pre-existing styling change into one command.
        StylingParams styling = new StylingParams(
                "#AABBCC", null, null, 200, null,
                null, null, null,
                "Comic Sans MS", 16, "bold-italic",
                null, "left-right", "rectangle", Boolean.FALSE, 100);

        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                note, 0, 0, 100, 50, null, styling, null);
        cmd.execute();
        // All typography and decoration fields applied.
        assertEquals(1, note.getGradient());  // left-right
        assertEquals(IDiagramModelNote.BORDER_RECTANGLE, note.getBorderType());
        assertFalse(note.getDeriveElementLineColor());
        assertEquals(100, note.getLineAlpha());
        assertEquals("Comic Sans MS|16|3", note.getFont());
        assertEquals("#AABBCC", note.getFillColor());

        // Single undo restores every captured field at once.
        cmd.undo();
        assertEquals(IDiagramModelObject.GRADIENT_NONE, note.getGradient());
        assertEquals(IDiagramModelNote.BORDER_DOGEAR, note.getBorderType());
        assertTrue(note.getDeriveElementLineColor());
        assertEquals(IDiagramModelObject.FEATURE_LINE_ALPHA_DEFAULT, note.getLineAlpha());
        assertNull(note.getFont());
        assertNull(note.getFillColor());
    }

    // ------------------------------------------------------------------
    // Captured-state accessors
    // ------------------------------------------------------------------

    @Test
    public void shouldExposeCapturedStylingState_forTesting() {
        IDiagramModelGroup group = freshGroup();
        group.setGradient(1);
        group.setDeriveElementLineColor(false);
        group.setLineAlpha(200);

        StylingParams styling = new StylingParams(
                null, null, null, null, null, null, null, null,
                null, null, null, null, "top-bottom", null, Boolean.TRUE, 50);

        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                group, 0, 0, 100, 50, null, styling, null);

        // Captured old values reflect pre-execute state.
        assertEquals(1, cmd.getOldGradient());
        assertFalse(cmd.getOldDeriveLineColor());
        assertEquals(200, cmd.getOldOutlineOpacity());

        // New values reflect what will be applied.
        assertEquals(0, cmd.getNewGradient());
        assertTrue(cmd.getNewDeriveLineColor());
        assertEquals(50, cmd.getNewOutlineOpacity());
    }
}
