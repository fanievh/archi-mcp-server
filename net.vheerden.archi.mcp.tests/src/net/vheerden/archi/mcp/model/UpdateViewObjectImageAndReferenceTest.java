package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IDiagramModelImage;
import com.archimatetool.model.IDiagramModelReference;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.BulkOperation;

/**
 * Pins that {@code update-view-object} acts on the two kinds its text used to leave out, an image and a
 * view reference, and that it refuses {@code text} on both instead of answering success over a value it
 * wrote nowhere.
 *
 * <p>Neither kind has text of its own: an image has no label, and a view reference shows the name of the
 * view it points at. Before, the call accepted the value, wrote it to no field and returned the object
 * unchanged under a success envelope. The refusals are pinned whole, message and suggestion, so a
 * different refusal naming the same parameter cannot pass for them.</p>
 */
public class UpdateViewObjectImageAndReferenceTest {

    private static final String IMAGE_REFUSAL = "ERROR {\"error\":{\"code\":\"INVALID_PARAMETER\","
            + "\"message\":\"Cannot set text on an image\","
            + "\"suggestedCorrection\":\"An image has no label. The 'text' parameter is only valid for groups "
            + "(label) and notes (content); place a note beside the image to caption it.\"},"
            + "\"_meta\":{\"sessionActive\":true}}";
    private static final String REFERENCE_REFUSAL = "ERROR {\"error\":{\"code\":\"INVALID_PARAMETER\","
            + "\"message\":\"Cannot set text on a view reference\","
            + "\"suggestedCorrection\":\"A view reference shows the name of the view it points at. The 'text' "
            + "parameter is only valid for groups (label) and notes (content); use update-view to rename the "
            + "referenced view.\"},"
            + "\"_meta\":{\"sessionActive\":true}}";

    private ViewContentsFixture f;
    private IDiagramModelImage image;
    private IDiagramModelReference reference;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-update-image-and-reference");
        image = f.image(f.view, "img-1", 40, 300);
        reference = f.reference(f.view, "ref-1", f.other, 400, 300);
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    @Test
    public void shouldMoveAnImage() {
        String served = f.serve("update-view-object", Map.of("viewObjectId", "img-1", "x", 50, "y", 310));

        assertTrue(served, served.startsWith("{\"result\":{\"viewObjectId\":\"img-1\""));
        assertEquals(50, image.getBounds().getX());
        assertEquals(310, image.getBounds().getY());
    }

    @Test
    public void shouldMoveAViewReference() {
        String served = f.serve("update-view-object", Map.of("viewObjectId", "ref-1", "x", 410, "width", 150));

        assertTrue(served, served.startsWith("{\"result\":{\"viewObjectId\":\"ref-1\""));
        assertEquals(410, reference.getBounds().getX());
        assertEquals(150, reference.getBounds().getWidth());
    }

    @Test
    public void shouldRefuseText_onAnImage_andWriteNothing() {
        String version = f.accessor.getModelVersion();

        assertEquals(IMAGE_REFUSAL, f.serve("update-view-object",
                Map.of("viewObjectId", "img-1", "text", "caption", "x", 99)));

        assertEquals("the move beside the refused text is not applied either", 40, image.getBounds().getX());
        assertEquals(version, f.accessor.getModelVersion());
        assertTrue("nothing reached the undo stack", !f.stack.canUndo());
    }

    @Test
    public void shouldRefuseText_onAViewReference_andWriteNothing() {
        String version = f.accessor.getModelVersion();

        assertEquals(REFERENCE_REFUSAL, f.serve("update-view-object",
                Map.of("viewObjectId", "ref-1", "text", "renamed", "x", 99)));

        assertEquals(400, reference.getBounds().getX());
        assertEquals("the reference still shows its view's name", "Other", reference.getReferencedModel().getName());
        assertEquals(version, f.accessor.getModelVersion());
        assertTrue("nothing reached the undo stack", !f.stack.canUndo());
    }

    @Test
    public void shouldRefuseAnEmptyText_onAViewReference_too() {
        assertEquals("an empty text is a supplied value, not an omitted one",
                REFERENCE_REFUSAL, f.serve("update-view-object", Map.of("viewObjectId", "ref-1", "text", "")));
    }

    @Test
    public void shouldStillRefuseText_onAnElementViewObject_withItsOwnWording() {
        f.object(f.view, "vo-a", f.element("el-a"), 40, 40, 120, 55);

        assertEquals("ERROR {\"error\":{\"code\":\"INVALID_PARAMETER\","
                + "\"message\":\"Cannot set text on an ArchiMate element view object\","
                + "\"suggestedCorrection\":\"The 'text' parameter is only valid for groups (label) and notes "
                + "(content). Use update-element to change an element's name.\"},"
                + "\"_meta\":{\"sessionActive\":true}}",
                f.serve("update-view-object", Map.of("viewObjectId", "vo-a", "text", "x")));
    }

    @Test
    public void bulkMutate_shouldRefuseText_onAnImageAndAViewReference() {
        for (String id : List.of("img-1", "ref-1")) {
            try {
                f.accessor.executeBulk(ViewContentsFixture.SESSION, List.of(
                        new BulkOperation("update-view-object", Map.of("viewObjectId", id, "text", "x"), null)),
                        "text on " + id, false);
                fail("expected text on " + id + " to be refused");
            } catch (ModelAccessException e) {
                assertEquals(ErrorCode.BULK_VALIDATION_FAILED, e.getErrorCode());
                String expected = id.equals("img-1")
                        ? "Operation 0 (update-view-object): Cannot set text on an image"
                        : "Operation 0 (update-view-object): Cannot set text on a view reference";
                assertTrue(id + ": " + e.getMessage(), e.getMessage().startsWith(expected));
            }
        }
        assertNull("nothing reached the undo stack", f.stack.getUndoCommand());
    }
}
