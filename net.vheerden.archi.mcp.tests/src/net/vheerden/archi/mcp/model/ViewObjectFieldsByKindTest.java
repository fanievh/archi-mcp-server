package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelImage;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.IGrouping;
import com.archimatetool.model.ITextPosition;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.ViewObjectDto;
import net.vheerden.archi.mcp.response.dto.ViewPositionSpec;

/**
 * Pins what {@code update-view-object}, {@code add-image-to-view} and {@code add-view-reference-to-view}
 * do with a field the object's kind cannot hold: refused by name on an image and a view reference, and
 * never reported as held on any kind.
 *
 * <p>Archi's own Properties view shows an image only its line width, border colour, opacity, outline
 * opacity, line style and picture, and a view reference everything but a figure type and a border type.
 * Before, the update wrote such a field where it changes nothing, or skipped it, and answered success with
 * the requested value echoed back, on every kind: a note answered the figure type it was sent, a group the
 * border type. Every refusal here is pinned whole, and every write is read back off the EMF object.</p>
 */
public class ViewObjectFieldsByKindTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String IMAGE_SUGGESTION = "An image takes opacity, outlineOpacity, lineWidth, lineStyle "
            + "and imagePath, which replaces its picture. Archi draws no fill, line colour, text, label or icon on "
            + "an image, so the other styling fields have nothing to change.";
    private static final String REFERENCE_SUGGESTION = "A view reference has no figure type or border type: "
            + "figureType is for groups and Grouping elements, borderType for notes. Every other styling field, "
            + "and an icon, applies to a view reference.";

    /** Every field an image does not take, in the order update-view-object publishes them, with a value the
     * pre-edit code accepted. */
    private static final Map<String, Object> IMAGE_UNHELD = new LinkedHashMap<>();
    static {
        IMAGE_UNHELD.put("labelExpression", "${name}");
        IMAGE_UNHELD.put("fillColor", "#112233");
        IMAGE_UNHELD.put("lineColor", "#445566");
        IMAGE_UNHELD.put("fontColor", "#778899");
        IMAGE_UNHELD.put("figureType", "rectangular");
        IMAGE_UNHELD.put("textAlignment", "left");
        IMAGE_UNHELD.put("verticalTextAlignment", "bottom");
        IMAGE_UNHELD.put("fontName", "Arial");
        IMAGE_UNHELD.put("fontSize", 11);
        IMAGE_UNHELD.put("fontStyle", "bold");
        IMAGE_UNHELD.put("gradient", "top-bottom");
        IMAGE_UNHELD.put("borderType", "rectangle");
        IMAGE_UNHELD.put("deriveLineColor", Boolean.FALSE);
        IMAGE_UNHELD.put("imagePosition", "top-left");
        IMAGE_UNHELD.put("showIcon", "always");
    }

    private ViewContentsFixture f;
    private IDiagramModelImage image;
    private IDiagramModelReference reference;
    private IDiagramModelNote note;
    private IDiagramModelGroup group;
    private IDiagramModelArchimateObject element;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-fields-by-kind");
        image = f.image(f.view, "img-1", 40, 300);
        reference = f.reference(f.view, "ref-1", f.other, 400, 300);
        note = f.note(f.view, "note-1", 700, 300);
        group = f.group(f.view, "grp-1", 40, 500, 300, 200);
        element = f.object(f.view, "vo-a", f.element("el-a"), 40, 40, 120, 55);
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    // ---- refused on an image ----------------------------------------------------------------------

    @Test
    public void updateViewObject_shouldRefuseEachFieldAnImageDoesNotTake_andWriteNothing() {
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, Object> field : IMAGE_UNHELD.entrySet()) {
            String before = emf(image);
            String version = f.accessor.getModelVersion();

            String served = f.serve("update-view-object", Map.of("viewObjectId", "img-1", field.getKey(),
                    field.getValue(), "x", 99));

            String expected = refusal("Cannot set " + field.getKey() + " on an image", IMAGE_SUGGESTION);
            if (!expected.equals(served) || !before.equals(emf(image)) || !version.equals(f.accessor.getModelVersion())
                    || f.stack.canUndo()) {
                wrong.add(field.getKey() + " -> " + served);
            }
        }
        assertEquals("every unheld field refused whole, nothing written", List.of(), wrong);
    }

    @Test
    public void updateViewObject_shouldRefuseTheEmptyClearSentinel_onAnImage_too() {
        assertEquals("clearing what an image does not take is still a claim about it",
                refusal("Cannot set fontName on an image", IMAGE_SUGGESTION),
                f.serve("update-view-object", Map.of("viewObjectId", "img-1", "fontName", "")));
    }

    @Test
    public void updateViewObject_shouldNameEveryUnheldFieldInOneRefusal_inSchemaOrder() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("viewObjectId", "img-1");
        args.put("fontName", "Arial");
        args.put("fillColor", "#112233");
        args.put("labelExpression", "${name}");
        args.put("opacity", 128);

        assertEquals(refusal("Cannot set labelExpression, fillColor and fontName on an image", IMAGE_SUGGESTION),
                f.serve("update-view-object", args));
        assertEquals("the held field beside them is not applied either", 255, image.getAlpha());
    }

    @Test
    public void bulkMutate_shouldRefuseAFieldAnImageDoesNotTake() {
        try {
            f.accessor.executeBulk(ViewContentsFixture.SESSION, List.of(new BulkOperation("update-view-object",
                    Map.of("viewObjectId", "img-1", "fontName", "Arial"), null)), "font on an image", false);
            fail("expected fontName on an image to be refused");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BULK_VALIDATION_FAILED, e.getErrorCode());
            assertTrue(e.getMessage(), e.getMessage().startsWith(
                    "Operation 0 (update-view-object): Cannot set fontName on an image"));
        }
        assertNull(image.getFont());
        assertNull("nothing reached the undo stack", f.stack.getUndoCommand());
    }

    // ---- refused on a view reference -------------------------------------------------------------

    @Test
    public void updateViewObject_shouldRefuseFigureTypeAndBorderType_onAViewReference() {
        assertEquals(refusal("Cannot set figureType on a view reference", REFERENCE_SUGGESTION),
                f.serve("update-view-object", Map.of("viewObjectId", "ref-1", "figureType", "rectangular")));
        assertEquals(refusal("Cannot set borderType on a view reference", REFERENCE_SUGGESTION),
                f.serve("update-view-object", Map.of("viewObjectId", "ref-1", "borderType", "rectangle")));
        assertEquals(refusal("Cannot set figureType and borderType on a view reference", REFERENCE_SUGGESTION),
                f.serve("update-view-object", Map.of("viewObjectId", "ref-1", "borderType", "rectangle",
                        "figureType", "rectangular", "fontName", "Arial")));
        assertNull("the font beside them is not applied either", reference.getFont());
        assertFalse(f.stack.canUndo());
    }

    @Test
    public void updateViewObject_shouldStillRefuseText_beforeAnyOtherField() {
        assertTrue(f.serve("update-view-object", Map.of("viewObjectId", "img-1", "text", "caption", "fontName",
                "Arial")).contains("\"message\":\"Cannot set text on an image\""));
    }

    // ---- what a kind does take, applied and reported ----------------------------------------------

    @Test
    public void updateViewObject_shouldApplyAndReport_theFieldsAnImageTakes() {
        StylingParams styling = new StylingParams(null, null, null, 128, 3, null, null, null, null, null, null,
                "dashed", null, null, null, 200);

        ViewObjectDto dto = f.accessor.updateViewObject(ViewContentsFixture.SESSION, "img-1", null, null, null,
                null, null, styling, null, null).entity();

        assertEquals(128, image.getAlpha());
        assertEquals(3, image.getLineWidth());
        assertEquals(200, image.getLineAlpha());
        assertEquals(Integer.valueOf(128), dto.opacity());
        assertEquals(Integer.valueOf(3), dto.lineWidth());
        assertEquals(Integer.valueOf(200), dto.outlineOpacity());
        assertEquals("dashed", dto.lineStyle());
        assertEquals("the picture it shows", ViewContentsFixture.IMAGE_PATH, dto.imagePath());
        assertNull("a picture is not an icon over a figure", dto.imageCoveragePercent());
        assertNull(dto.imageCoverageWarning());
    }

    @Test
    public void updateViewObject_shouldApplyAndReport_theFieldsAViewReferenceTakes() {
        StylingParams styling = new StylingParams(null, null, null, null, null, null, null, "bottom", "Arial",
                null, null, null, null, null, null, null);

        ViewObjectDto dto = f.accessor.updateViewObject(ViewContentsFixture.SESSION, "ref-1", null, null, null,
                null, null, styling, new ImageParams(ViewContentsFixture.IMAGE_PATH, "top-left", null), "${name}")
                .entity();

        assertEquals(ITextPosition.TEXT_POSITION_BOTTOM, reference.getTextPosition());
        assertEquals("bottom", dto.verticalTextAlignment());
        assertEquals("Arial", dto.fontName());
        assertEquals("${name}", dto.labelExpression());
        assertEquals(ViewContentsFixture.IMAGE_PATH, reference.getImagePath());
        assertEquals(ViewContentsFixture.IMAGE_PATH, dto.imagePath());
        assertEquals("top-left", dto.imagePosition());
    }

    // ---- an image's picture -----------------------------------------------------------------------

    @Test
    public void updateViewObject_shouldReplaceAnImagesPicture_undoably_andReportIt() {
        image.setImagePath("images/old.png");

        String served = f.serve("update-view-object", Map.of("viewObjectId", "img-1", "imagePath",
                ViewContentsFixture.IMAGE_PATH));

        assertTrue(served, served.contains("\"imagePath\":\"" + ViewContentsFixture.IMAGE_PATH + "\""));
        assertFalse("a picture is not an icon over a figure", served.contains("imageCoverage"));
        assertEquals(ViewContentsFixture.IMAGE_PATH, image.getImagePath());
        f.stack.undo();
        assertEquals("images/old.png", image.getImagePath());
    }

    @Test
    public void updateViewObject_shouldRefuseClearingAnImagesPicture() {
        assertEquals(refusal("Cannot clear the picture of an image", "An image always shows a picture. Pass "
                + "another archive imagePath to replace it (list-model-images lists them), or remove the image "
                + "with remove-from-view."),
                f.serve("update-view-object", Map.of("viewObjectId", "img-1", "imagePath", "")));
        assertEquals(ViewContentsFixture.IMAGE_PATH, image.getImagePath());
    }

    @Test
    public void updateViewObject_shouldRefuseAPictureMissingFromTheArchive_withTheExistingMessage() {
        String served = f.serve("update-view-object", Map.of("viewObjectId", "img-1", "imagePath",
                "images/missing.png"));

        assertEquals("ERROR {\"error\":{\"code\":\"IMAGE_NOT_FOUND\",\"message\":\"imagePath 'images/missing.png' "
                + "does not resolve to an image in the model archive\",\"suggestedCorrection\":\"Use list-model-images "
                + "to browse stored images, or add-image-to-model to import a new one.\"},\"_meta\":{\"sessionActive\":true}}",
                served);
        assertEquals(ViewContentsFixture.IMAGE_PATH, image.getImagePath());
    }

    // ---- never reported as held, on any kind ------------------------------------------------------

    @Test
    public void updateViewObject_shouldReportWhatTheObjectHolds_notTheRequest_onEveryKind() {
        List<String> echoed = new ArrayList<>();
        expectOmitted(echoed, "note figureType", note, "figureType", "rectangular");
        expectOmitted(echoed, "group borderType", group, "borderType", "rectangle");
        expectOmitted(echoed, "element borderType", element, "borderType", "rectangle");
        expectOmitted(echoed, "element figureType", element, "figureType", "rectangular");
        assertEquals("a field the object cannot hold is reported as it holds it: absent", List.of(), echoed);
    }

    @Test
    public void updateViewObject_shouldStillReportTheFieldsEachKindHolds() {
        IArchimateElement grouping = f.factory.createGrouping();
        grouping.setId("el-g");
        f.model.getDefaultFolderForObject(grouping).getElements().add(grouping);
        f.object(f.view, "vo-g", grouping, 400, 40, 200, 150);
        assertTrue(grouping instanceof IGrouping);

        assertEquals("rectangular", update("vo-g", "figureType", "rectangular").figureType());
        assertEquals("rectangular", update("grp-1", "figureType", "rectangular").figureType());
        assertEquals("rectangle", update("note-1", "borderType", "rectangle").borderType());
        assertEquals("bottom", update("vo-a", "verticalTextAlignment", "bottom").verticalTextAlignment());
    }

    // ---- the add tools ----------------------------------------------------------------------------

    @Test
    public void addImageToView_shouldRefuseAFieldAnImageDoesNotTake_onTheToolAndInBulk() {
        assertEquals(refusal("Cannot set fontName on an image", IMAGE_SUGGESTION),
                f.serve("add-image-to-view", Map.of("viewId", f.view.getId(), "imagePath",
                        ViewContentsFixture.IMAGE_PATH, "fontName", "Arial")));
        try {
            f.accessor.executeBulk(ViewContentsFixture.SESSION, List.of(new BulkOperation("add-image-to-view",
                    Map.of("viewId", f.view.getId(), "imagePath", ViewContentsFixture.IMAGE_PATH, "fillColor",
                            "#112233"), null)), "fill on an image", false);
            fail("expected fillColor on an image to be refused");
        } catch (ModelAccessException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith(
                    "Operation 0 (add-image-to-view): Cannot set fillColor on an image"));
        }
        assertEquals("nothing placed", 1, images());
    }

    @Test
    public void addImageToView_shouldStillApplyTheFieldsAnImageTakes() {
        String served = f.serve("add-image-to-view", Map.of("viewId", f.view.getId(), "imagePath",
                ViewContentsFixture.IMAGE_PATH, "opacity", 128, "lineWidth", 2));

        assertFalse(served, served.startsWith("ERROR "));
        assertEquals(2, images());
    }

    @Test
    public void addViewReferenceToView_shouldRefuseFigureTypeAndBorderType_onTheToolAndInBulk() {
        assertEquals(refusal("Cannot set figureType on a view reference", REFERENCE_SUGGESTION),
                f.serve("add-view-reference-to-view", Map.of("viewId", f.view.getId(), "referencedViewId",
                        f.other.getId(), "figureType", "rectangular")));
        try {
            f.accessor.executeBulk(ViewContentsFixture.SESSION, List.of(new BulkOperation(
                    "add-view-reference-to-view", Map.of("viewId", f.view.getId(), "referencedViewId",
                            f.other.getId(), "borderType", "rectangle"), null)), "border on a reference", false);
            fail("expected borderType on a view reference to be refused");
        } catch (ModelAccessException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith(
                    "Operation 0 (add-view-reference-to-view): Cannot set borderType on a view reference"));
        }
        assertEquals("nothing placed", 1, references());
    }

    // ---- the layout callers -----------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    public void applyPositions_shouldStillMoveAnImageAndAReference_sendingNoStyling() throws Exception {
        String served = f.serve("apply-positions", Map.of("viewId", f.view.getId(), "positions", List.of(
                Map.of("viewObjectId", "img-1", "x", 60, "y", 320),
                Map.of("viewObjectId", "ref-1", "x", 420, "y", 320))));

        assertFalse(served, served.startsWith("ERROR "));
        assertTrue(JSON.readValue(served, Map.class).get("result") instanceof Map);
        assertEquals(60, image.getBounds().getX());
        assertEquals(420, reference.getBounds().getX());
    }

    /**
     * apply-positions reaches the update prepare itself, with computed geometry and no styling, so an
     * image and a view reference it moves must pass the per-kind rule untouched.
     */
    @Test
    public void applyViewLayout_shouldStillMoveAnImageAndAReference_throughTheUpdatePrepare() {
        f.accessor.applyViewLayout(ViewContentsFixture.SESSION, f.view.getId(), List.of(
                new ViewPositionSpec("img-1", 70, 330, 120, 90),
                new ViewPositionSpec("ref-1", 430, 330, null, null)), null, null);

        assertEquals(70, image.getBounds().getX());
        assertEquals(120, image.getBounds().getWidth());
        assertEquals(430, reference.getBounds().getX());
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private void expectOmitted(List<String> echoed, String label, IDiagramModelObject target, String key,
            Object value) {
        String served = f.serve("update-view-object", Map.of("viewObjectId", target.getId(), key, value));
        if (served.startsWith("ERROR ") || served.contains("\"" + key + "\":")) {
            echoed.add(label + " -> " + served);
        }
    }

    private ViewObjectDto update(String id, String key, String value) {
        StylingParams styling = new StylingParams(null, null, null, null, null,
                key.equals("figureType") ? value : null, null,
                key.equals("verticalTextAlignment") ? value : null, null, null, null, null, null,
                key.equals("borderType") ? value : null, null, null);
        return f.accessor.updateViewObject(ViewContentsFixture.SESSION, id, null, null, null, null, null, styling,
                null, null).entity();
    }

    private int images() {
        return (int) f.view.getChildren().stream().filter(c -> c instanceof IDiagramModelImage).count();
    }

    private int references() {
        return (int) f.view.getChildren().stream().filter(c -> c instanceof IDiagramModelReference).count();
    }

    /** Every attribute an update could write on an image, as one comparable string. */
    private static String emf(IDiagramModelImage i) {
        return String.join("|", String.valueOf(i.getBounds().getX()), String.valueOf(i.getBounds().getY()),
                String.valueOf(i.getBounds().getWidth()), String.valueOf(i.getBounds().getHeight()),
                String.valueOf(i.getLineWidth()), String.valueOf(i.getLineAlpha()), String.valueOf(i.getLineStyle()),
                i.getBorderColor(), i.getFillColor(), i.getLineColor(),
                i.getFontColor(), i.getFont(), String.valueOf(i.getTextAlignment()), String.valueOf(i.getGradient()),
                String.valueOf(i.getDeriveElementLineColor()), String.valueOf(i.getIconVisibleState()),
                i.getFeatures().getString("labelExpression", null), i.getImagePath(), String.valueOf(i.getAlpha()));
    }

    private static String refusal(String message, String suggestion) {
        return "ERROR {\"error\":{\"code\":\"INVALID_PARAMETER\",\"message\":\"" + message
                + "\",\"suggestedCorrection\":\"" + suggestion + "\"},\"_meta\":{\"sessionActive\":true}}";
    }
}
