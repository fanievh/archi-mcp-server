package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IFontAttribute;
import com.archimatetool.model.IGrouping;
import com.archimatetool.model.ILineObject;
import com.archimatetool.model.ITextAlignment;
import com.archimatetool.model.ITextPosition;

/**
 * Tests for {@link UpdateViewObjectCommand}.
 *
 * <p>Uses real EMF objects via {@link IArchimateFactory#eINSTANCE} to test
 * execute (update bounds) and undo (restore old bounds) behavior.</p>
 */
public class UpdateViewObjectCommandTest {

    private IArchimateFactory factory;
    private IDiagramModelArchimateObject diagramObject;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setDefaults();

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setName("Test View");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        IArchimateElement element = factory.createApplicationComponent();
        element.setName("My Component");
        model.getFolder(FolderType.APPLICATION).getElements().add(element);

        diagramObject = factory.createDiagramModelArchimateObject();
        diagramObject.setArchimateElement(element);
        diagramObject.setBounds(50, 60, 120, 55);
        view.getChildren().add(diagramObject);
    }

    @Test
    public void shouldUpdateBounds_whenExecuted() {
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                diagramObject, 200, 100, 150, 70);

        cmd.execute();

        assertEquals(200, diagramObject.getBounds().getX());
        assertEquals(100, diagramObject.getBounds().getY());
        assertEquals(150, diagramObject.getBounds().getWidth());
        assertEquals(70, diagramObject.getBounds().getHeight());
    }

    @Test
    public void shouldRestoreOldBounds_whenUndone() {
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                diagramObject, 200, 100, 150, 70);
        cmd.execute();

        cmd.undo();

        assertEquals(50, diagramObject.getBounds().getX());
        assertEquals(60, diagramObject.getBounds().getY());
        assertEquals(120, diagramObject.getBounds().getWidth());
        assertEquals(55, diagramObject.getBounds().getHeight());
    }

    @Test
    public void shouldCaptureOldBoundsAtConstruction() {
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                diagramObject, 200, 100, 150, 70);

        assertEquals(50, cmd.getOldX());
        assertEquals(60, cmd.getOldY());
        assertEquals(120, cmd.getOldWidth());
        assertEquals(55, cmd.getOldHeight());
    }

    @Test
    public void shouldExposeNewBoundsViaGetters() {
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                diagramObject, 200, 100, 150, 70);

        assertEquals(200, cmd.getNewX());
        assertEquals(100, cmd.getNewY());
        assertEquals(150, cmd.getNewWidth());
        assertEquals(70, cmd.getNewHeight());
    }

    @Test
    public void shouldExposeDiagramObjectViaGetter() {
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                diagramObject, 200, 100, 150, 70);

        assertSame(diagramObject, cmd.getDiagramObject());
    }

    @Test
    public void shouldHaveDescriptiveLabel() {
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                diagramObject, 200, 100, 150, 70);

        assertTrue("Label should describe view object update",
                cmd.getLabel().contains("view object"));
    }

    @Test
    public void shouldReApplyNewBounds_whenRedone() {
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                diagramObject, 200, 100, 150, 70);
        cmd.execute();
        cmd.undo();

        cmd.redo();

        assertEquals(200, diagramObject.getBounds().getX());
        assertEquals(100, diagramObject.getBounds().getY());
        assertEquals(150, diagramObject.getBounds().getWidth());
        assertEquals(70, diagramObject.getBounds().getHeight());
    }

    // ---- Styling tests ----

    @Test
    public void shouldApplyFillColor_whenStylingProvided() {
        StylingParams styling = new StylingParams("#FF6600", null, null, null, null);
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                diagramObject, 50, 60, 120, 55, null, styling);

        cmd.execute();

        assertEquals("#FF6600", diagramObject.getFillColor());
        assertTrue(cmd.hasStylingChange());
    }

    @Test
    public void shouldApplyLineColor_whenStylingProvided() {
        StylingParams styling = new StylingParams(null, "#0000FF", null, null, null);
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                diagramObject, 50, 60, 120, 55, null, styling);

        cmd.execute();

        assertEquals("#0000FF", ((ILineObject) diagramObject).getLineColor());
    }

    @Test
    public void shouldApplyFontColor_whenStylingProvided() {
        StylingParams styling = new StylingParams(null, null, "#00FF00", null, null);
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                diagramObject, 50, 60, 120, 55, null, styling);

        cmd.execute();

        assertEquals("#00FF00", ((IFontAttribute) diagramObject).getFontColor());
    }

    @Test
    public void shouldApplyOpacity_whenStylingProvided() {
        StylingParams styling = new StylingParams(null, null, null, 128, null);
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                diagramObject, 50, 60, 120, 55, null, styling);

        cmd.execute();

        assertEquals(128, diagramObject.getAlpha());
    }

    @Test
    public void shouldApplyLineWidth_whenStylingProvided() {
        StylingParams styling = new StylingParams(null, null, null, null, 3);
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                diagramObject, 50, 60, 120, 55, null, styling);

        cmd.execute();

        assertEquals(3, ((ILineObject) diagramObject).getLineWidth());
    }

    @Test
    public void shouldApplyAllStyling_whenAllProvided() {
        StylingParams styling = new StylingParams("#FF0000", "#00FF00", "#0000FF", 200, 2);
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                diagramObject, 50, 60, 120, 55, null, styling);

        cmd.execute();

        assertEquals("#FF0000", diagramObject.getFillColor());
        assertEquals("#00FF00", ((ILineObject) diagramObject).getLineColor());
        assertEquals("#0000FF", ((IFontAttribute) diagramObject).getFontColor());
        assertEquals(200, diagramObject.getAlpha());
        assertEquals(2, ((ILineObject) diagramObject).getLineWidth());
    }

    @Test
    public void shouldClearFillColor_whenEmptyString() {
        diagramObject.setFillColor("#FF0000");
        StylingParams styling = new StylingParams("", null, null, null, null);
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                diagramObject, 50, 60, 120, 55, null, styling);

        cmd.execute();

        assertNull("Empty string should clear to null (default)", diagramObject.getFillColor());
    }

    @Test
    public void shouldUndoStyling_whenStylingApplied() {
        String origFill = diagramObject.getFillColor();
        int origAlpha = diagramObject.getAlpha();

        StylingParams styling = new StylingParams("#FF6600", null, null, 128, null);
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                diagramObject, 50, 60, 120, 55, null, styling);
        cmd.execute();

        assertEquals("#FF6600", diagramObject.getFillColor());
        assertEquals(128, diagramObject.getAlpha());

        cmd.undo();

        assertEquals(origFill, diagramObject.getFillColor());
        assertEquals(origAlpha, diagramObject.getAlpha());
    }

    @Test
    public void shouldNotHaveStylingChange_whenNullStylingParams() {
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                diagramObject, 200, 100, 150, 70, null, null);

        assertFalse(cmd.hasStylingChange());
    }

    @Test
    public void shouldNotHaveStylingChange_whenNoStylingValues() {
        StylingParams styling = new StylingParams(null, null, null, null, null);
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                diagramObject, 200, 100, 150, 70, null, styling);

        assertFalse(cmd.hasStylingChange());
    }

    @Test
    public void shouldCaptureOldStylingAtConstruction() {
        diagramObject.setFillColor("#AABBCC");
        ((ILineObject) diagramObject).setLineColor("#112233");

        StylingParams styling = new StylingParams("#FF0000", "#00FF00", null, null, null);
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                diagramObject, 50, 60, 120, 55, null, styling);

        assertEquals("#AABBCC", cmd.getOldFillColor());
        assertEquals("#112233", cmd.getOldLineColor());
        assertEquals("#FF0000", cmd.getNewFillColor());
        assertEquals("#00FF00", cmd.getNewLineColor());
    }

    // ---- figureType + text alignment pins ----

    @Test
    public void shouldApplyTextAlignmentAndUndoRestores() {
        // Capture initial state (centre = Archi default)
        assertEquals(ITextAlignment.TEXT_ALIGNMENT_CENTER, diagramObject.getTextAlignment());

        StylingParams styling = new StylingParams(
                null, null, null, null, null, null, "left", null);
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                diagramObject, 50, 60, 120, 55, null, styling);

        cmd.execute();
        assertEquals(ITextAlignment.TEXT_ALIGNMENT_LEFT, diagramObject.getTextAlignment());

        cmd.undo();
        assertEquals(ITextAlignment.TEXT_ALIGNMENT_CENTER, diagramObject.getTextAlignment());
    }

    @Test
    public void shouldApplyVerticalTextAlignmentAndUndoRestores() {
        // TEXT_POSITION_TOP (0) is the EMF default for a freshly-created view object
        // (label renders in a top "header band"). Apply CENTRE — a non-default — and round-trip.
        assertEquals(ITextPosition.TEXT_POSITION_TOP, diagramObject.getTextPosition());

        StylingParams styling = new StylingParams(
                null, null, null, null, null, null, null, "centre");
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                diagramObject, 50, 60, 120, 55, null, styling);

        cmd.execute();
        assertEquals(ITextPosition.TEXT_POSITION_CENTRE, diagramObject.getTextPosition());

        cmd.undo();
        assertEquals(ITextPosition.TEXT_POSITION_TOP, diagramObject.getTextPosition());
    }

    @Test
    public void shouldApplyFigureTypeOnGroupingElementAndUndoRestores() {
        // Build a fresh Grouping element + diagramObject (the default setUp's diagramObject
        // wraps an ApplicationComponent which is non-Grouping — figureType is silently ignored).
        IArchimateModel m = IArchimateFactory.eINSTANCE.createArchimateModel();
        m.setDefaults();
        IArchimateElement grouping = IArchimateFactory.eINSTANCE.createGrouping();
        m.getFolder(FolderType.OTHER).getElements().add(grouping);
        IDiagramModelArchimateObject obj = IArchimateFactory.eINSTANCE.createDiagramModelArchimateObject();
        obj.setArchimateElement(grouping);
        obj.setBounds(0, 0, 100, 50);
        assertTrue(obj.getArchimateElement() instanceof IGrouping);
        assertEquals(0, obj.getType()); // tabbed = primary figure for Grouping

        StylingParams styling = new StylingParams(
                null, null, null, null, null, "rectangular", null, null);
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                obj, 0, 0, 100, 50, null, styling);

        cmd.execute();
        assertEquals(1, obj.getType());

        cmd.undo();
        assertEquals(0, obj.getType());
    }

    @Test
    public void shouldSilentlyIgnoreFigureTypeOnNonGroupingElement() {
        // diagramObject wraps an ApplicationComponent — not Grouping.
        int beforeType = diagramObject.getType();

        StylingParams styling = new StylingParams(
                null, null, null, null, null, "rectangular", null, null);
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                diagramObject, 50, 60, 120, 55, null, styling);

        cmd.execute();
        assertEquals(beforeType, diagramObject.getType());

        cmd.undo();
        assertEquals(beforeType, diagramObject.getType());
    }

    @Test
    public void shouldApplyFigureTypeOnNativeGroup() {
        IDiagramModelGroup group = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        group.setBounds(0, 0, 200, 200);
        // BORDER_TABBED = 0 is the EMF default
        assertEquals(IDiagramModelGroup.BORDER_TABBED, group.getBorderType());

        StylingParams styling = new StylingParams(
                null, null, null, null, null, "rectangular", null, null);
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                group, 0, 0, 200, 200, null, styling);

        cmd.execute();
        assertEquals(IDiagramModelGroup.BORDER_RECTANGLE, group.getBorderType());

        cmd.undo();
        assertEquals(IDiagramModelGroup.BORDER_TABBED, group.getBorderType());
    }

    @Test
    public void shouldApplyAllThreeNewFieldsInOneCommand() {
        // Single-undo-unit pin: one command captures and applies all three new fields together.
        // Use CENTRE (non-default) for verticalTextAlignment since TOP is the EMF default.
        IDiagramModelGroup group = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        group.setBounds(0, 0, 200, 200);

        StylingParams styling = new StylingParams(
                null, null, null, null, null, "rectangular", "left", "centre");
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                group, 0, 0, 200, 200, null, styling);

        cmd.execute();
        assertEquals(IDiagramModelGroup.BORDER_RECTANGLE, group.getBorderType());
        assertEquals(ITextAlignment.TEXT_ALIGNMENT_LEFT, group.getTextAlignment());
        assertEquals(ITextPosition.TEXT_POSITION_CENTRE, group.getTextPosition());

        cmd.undo();
        assertEquals(IDiagramModelGroup.BORDER_TABBED, group.getBorderType());
        assertEquals(ITextAlignment.TEXT_ALIGNMENT_CENTER, group.getTextAlignment());
        assertEquals(ITextPosition.TEXT_POSITION_TOP, group.getTextPosition());
    }

    @Test
    public void shouldBeIdempotentUnderRepeatedApply() {
        // Run execute twice — captured "new" values are static at construction, so applyStyling
        // is structurally idempotent (second execute is a no-op effect on the EMF state).
        IDiagramModelGroup group = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        group.setBounds(0, 0, 200, 200);

        StylingParams styling = new StylingParams(
                null, null, null, null, null, "rectangular", "right", "bottom");
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                group, 0, 0, 200, 200, null, styling);

        cmd.execute();
        assertEquals(IDiagramModelGroup.BORDER_RECTANGLE, group.getBorderType());
        assertEquals(ITextAlignment.TEXT_ALIGNMENT_RIGHT, group.getTextAlignment());
        assertEquals(ITextPosition.TEXT_POSITION_BOTTOM, group.getTextPosition());

        cmd.execute(); // second apply — identical end state
        assertEquals(IDiagramModelGroup.BORDER_RECTANGLE, group.getBorderType());
        assertEquals(ITextAlignment.TEXT_ALIGNMENT_RIGHT, group.getTextAlignment());
        assertEquals(ITextPosition.TEXT_POSITION_BOTTOM, group.getTextPosition());
    }

    @Test
    public void shouldCaptureOldFigureTypeAtConstruction() {
        // Capture-at-construction pin: pre-populate the group to a non-default border type,
        // construct the command (capturing oldFigureType), then mutate the group directly,
        // then undo and verify undo restored the captured-at-construction value (NOT the
        // post-mutate value).
        IDiagramModelGroup group = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        group.setBounds(0, 0, 200, 200);
        group.setBorderType(IDiagramModelGroup.BORDER_RECTANGLE);

        StylingParams styling = new StylingParams(
                null, null, null, null, null, "tabbed", null, null);
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                group, 0, 0, 200, 200, null, styling);

        // After construction: oldFigureType = BORDER_RECTANGLE, newFigureType = BORDER_TABBED
        assertEquals(Integer.valueOf(IDiagramModelGroup.BORDER_RECTANGLE), cmd.getOldFigureType());
        assertEquals(Integer.valueOf(IDiagramModelGroup.BORDER_TABBED), cmd.getNewFigureType());

        // Mutate the group bypass-style — execute then undo should restore the captured old value.
        cmd.execute();
        assertEquals(IDiagramModelGroup.BORDER_TABBED, group.getBorderType());

        cmd.undo();
        assertEquals(IDiagramModelGroup.BORDER_RECTANGLE, group.getBorderType());
    }

    // ---- G4: labelExpression rail tests ----
    //
    // labelExpression is stored as a generic IFeatures entry under key
    // "labelExpression", NOT a typed EMF getter/setter. The rail's
    // bookkeeping matches the existing 4 rails (old/new/has-change triplet),
    // but the apply method writes via IFeatures.putString / IFeatures.remove.
    // See the Task 0 EMF finding.

    @Test
    public void shouldApplyLabelExpression_whenSetOnElementViewObject() {
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                diagramObject, 50, 60, 120, 55, null, null, null, "${name}");

        cmd.execute();

        assertEquals("${name}",
                diagramObject.getFeatures().getString("labelExpression", null));
        assertTrue("Feature should be present after set",
                diagramObject.getFeatures().has("labelExpression"));
    }

    @Test
    public void shouldReplaceLabelExpression_whenExistingValuePresent() {
        diagramObject.getFeatures().putString("labelExpression", "${property:Owner}");

        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                diagramObject, 50, 60, 120, 55, null, null, null, "${name}");
        cmd.execute();

        assertEquals("${name}",
                diagramObject.getFeatures().getString("labelExpression", null));
    }

    @Test
    public void shouldClearLabelExpression_whenEmptyString() {
        diagramObject.getFeatures().putString("labelExpression", "${name}");

        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                diagramObject, 50, 60, 120, 55, null, null, null, "");
        cmd.execute();

        assertFalse("Feature should be removed after empty-string clear",
                diagramObject.getFeatures().has("labelExpression"));
        assertNull("getString default must be returned after clear",
                diagramObject.getFeatures().getString("labelExpression", null));
    }

    @Test
    public void shouldLeaveLabelExpressionUnchanged_whenParamIsNull() {
        diagramObject.getFeatures().putString("labelExpression", "${name}");

        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                diagramObject, 50, 60, 120, 55, null, null, null, null);
        cmd.execute();

        assertEquals("Null param means leave-unchanged (omit-means-no-change)",
                "${name}",
                diagramObject.getFeatures().getString("labelExpression", null));
        assertFalse("No labelExpression change tracked when param is null",
                cmd.hasLabelExpressionChange());
    }

    @Test
    public void shouldRestoreLabelExpression_whenUndoneAfterSet() {
        // Pre-state: no labelExpression feature.
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                diagramObject, 50, 60, 120, 55, null, null, null, "${name}");
        cmd.execute();
        assertEquals("${name}",
                diagramObject.getFeatures().getString("labelExpression", null));

        cmd.undo();

        assertFalse("Undo of set-from-empty must REMOVE the feature",
                diagramObject.getFeatures().has("labelExpression"));
    }

    @Test
    public void shouldRestoreLabelExpression_whenUndoneAfterReplace() {
        diagramObject.getFeatures().putString("labelExpression", "${property:Owner}");

        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                diagramObject, 50, 60, 120, 55, null, null, null, "${name}");
        cmd.execute();
        cmd.undo();

        assertEquals("Undo restores the previous expression value verbatim",
                "${property:Owner}",
                diagramObject.getFeatures().getString("labelExpression", null));
    }

    @Test
    public void shouldRestoreLabelExpression_whenUndoneAfterClear() {
        diagramObject.getFeatures().putString("labelExpression", "${name}");

        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                diagramObject, 50, 60, 120, 55, null, null, null, "");
        cmd.execute();
        cmd.undo();

        assertEquals("Undo of empty-string-clear restores the pre-clear value",
                "${name}",
                diagramObject.getFeatures().getString("labelExpression", null));
    }

    @Test
    public void shouldCaptureOldLabelExpressionAtConstruction() {
        diagramObject.getFeatures().putString("labelExpression", "${property:Owner}");

        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                diagramObject, 50, 60, 120, 55, null, null, null, "${name}");

        // Snapshot pin: the old value is captured at construction, not at execute.
        assertEquals("${property:Owner}", cmd.getOldLabelExpression());
        assertEquals("${name}", cmd.getNewLabelExpression());
        assertTrue(cmd.hasLabelExpressionChange());

        // Mutate the EMF state directly (bypassing the command) — undo must
        // still restore the value captured at construction, not the
        // bypass-mutated value.
        diagramObject.getFeatures().putString("labelExpression", "${type}");
        cmd.execute();
        cmd.undo();

        assertEquals("Undo restores construction-time captured value, not post-bypass",
                "${property:Owner}",
                diagramObject.getFeatures().getString("labelExpression", null));
    }

    @Test
    public void shouldNormalizeEmptyStringToNull_inNewLabelExpression() {
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                diagramObject, 50, 60, 120, 55, null, null, null, "");

        // emptyToNull normalization mirrors the existing styling rail idiom.
        assertNull("Empty-string newLabelExpression normalizes to null (clear semantic)",
                cmd.getNewLabelExpression());
        assertTrue("hasChange remains true so the clear semantic fires on execute",
                cmd.hasLabelExpressionChange());
    }

    @Test
    public void shouldBeIdempotentUnderRepeatedApply_labelExpression() {
        UpdateViewObjectCommand cmd = new UpdateViewObjectCommand(
                diagramObject, 50, 60, 120, 55, null, null, null, "${name}");

        cmd.execute();
        cmd.execute();   // second apply — captured "new" value is static, so idempotent.

        assertEquals("${name}",
                diagramObject.getFeatures().getString("labelExpression", null));
    }
}
