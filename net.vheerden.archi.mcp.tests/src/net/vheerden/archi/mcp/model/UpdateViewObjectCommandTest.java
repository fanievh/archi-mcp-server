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
import com.archimatetool.model.IFontAttribute;
import com.archimatetool.model.ILineObject;

/**
 * Tests for {@link UpdateViewObjectCommand} (Story 7-8).
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

    // ---- Story 11-2: Styling tests ----

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
}
