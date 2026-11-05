package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelImage;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelReference;

import net.vheerden.archi.mcp.response.dto.ViewContentsDto;
import net.vheerden.archi.mcp.response.dto.ViewObjectDto;

/**
 * Pins that an image path stored as the empty string reads as no image, on every row that reports one.
 *
 * <p>The plugin never stores {@code ""}: both writers turn the empty clear sentinel into null. A model
 * edited elsewhere (a script, a hand edit, an older tool) can hold it, though, and Archi draws no image
 * for it. Before, the readers passed it through, so a row read {@code "imagePath":""} where the field is
 * documented as omitted when there is no image. Each fixture object here stores {@code ""} directly
 * through EMF, as such an editor would.</p>
 */
public class StoredEmptyImagePathTest {

    private ViewContentsFixture f;
    private IDiagramModelArchimateObject element;
    private IDiagramModelGroup group;
    private IDiagramModelNote note;
    private IDiagramModelReference reference;
    private IDiagramModelImage image;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-stored-empty-image-path");
        element = f.object(f.view, "vo-a", f.element("el-a"), 0, 0, 120, 60);
        group = f.group(f.view, "grp-1", 0, 200, 300, 200);
        note = f.note(f.view, "note-1", 400, 0);
        reference = f.reference(f.view, "ref-1", f.other, 400, 200);
        image = f.image(f.view, "img-1", 700, 0);
        element.setImagePath("");
        group.setImagePath("");
        note.setImagePath("");
        reference.setImagePath("");
        image.setImagePath("");
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    @Test
    public void readImagePath_shouldAnswerNull_whenTheStoredPathIsEmpty() {
        assertNull(ImageHelper.readImagePath(element));
        assertNull(ImageHelper.readImagePath(group));
        assertNull(ImageHelper.readImagePath(note));
        assertNull(ImageHelper.readImagePath(reference));
    }

    @Test
    public void iconCornerOrNone_shouldAnswerNone_whenTheStoredPathIsEmpty() {
        assertEquals(-1, ImageHelper.iconCornerOrNone(element));
        assertEquals(-1, ImageHelper.iconCornerOrNone(reference));
    }

    @Test
    public void getViewContents_shouldOmitTheImagePath_onEveryRow_whenTheStoredPathIsEmpty() {
        ViewContentsDto contents = f.accessor.getViewContents(f.view.getId()).orElseThrow();

        assertNull("element row", contents.visualMetadata().get(0).imagePath());
        assertNull("group row", contents.groups().get(0).imagePath());
        assertNull("note row", contents.notes().get(0).imagePath());
        assertNull("reference row", contents.viewReferences().get(0).imagePath());
        assertNull("image row: an image whose picture is empty has no picture", contents.images().get(0).imagePath());
    }

    @Test
    public void getViewContents_shouldServeNoEmptyImagePath_inAnyFormat() {
        for (String format : new String[] {"json", "graph"}) {
            String served = f.serve(Map.of("format", format));
            assertFalse(format + ": " + served, served.contains("\"imagePath\":\"\""));
        }
    }

    @Test
    public void updateViewObject_shouldReportNoImagePath_whenTheStoredPathIsEmptyAndNoneWasSent() {
        ViewObjectDto dto = f.accessor.updateViewObject(ViewContentsFixture.SESSION, "vo-a", 5, null, null, null,
                null, null, null, null).entity();

        assertEquals(5, dto.x());
        assertNull(dto.imagePath());
    }

    @Test
    public void getViewContents_shouldStillReportAStoredPath_whenOneIsSet() {
        element.setImagePath(ViewContentsFixture.IMAGE_PATH);
        image.setImagePath(ViewContentsFixture.IMAGE_PATH);

        ViewContentsDto contents = f.accessor.getViewContents(f.view.getId()).orElseThrow();

        assertEquals(ViewContentsFixture.IMAGE_PATH, contents.visualMetadata().get(0).imagePath());
        assertEquals(ViewContentsFixture.IMAGE_PATH, contents.images().get(0).imagePath());
        assertTrue(ImageHelper.iconCornerOrNone(element) >= 0);
    }
}
