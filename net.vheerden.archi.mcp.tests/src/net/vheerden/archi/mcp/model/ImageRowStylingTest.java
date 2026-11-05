package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IDiagramModelImage;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.response.dto.DiagramImageDto;

/**
 * Pins that an image's row reports the styling an image takes: opacity, outline opacity, line width and
 * line style, each omitted at Archi's default.
 *
 * <p>An image takes exactly those four (with its border colour), and every other styling field is
 * refused on it. Before, its row carried none of the four, so after placing an image with
 * {@code opacity} or styling it with {@code update-view-object}, neither the placement response nor
 * {@code get-view-contents} could show what the image held.</p>
 */
public class ImageRowStylingTest {

    private ViewContentsFixture f;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-image-row-styling");
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    @Test
    public void getViewContents_shouldReportTheStylingAnImageHolds() {
        IDiagramModelImage image = f.image(f.view, "img-styled", 20, 20);
        image.setAlpha(128);
        image.setLineAlpha(90);
        image.setLineWidth(3);
        image.setLineStyle(IDiagramModelObject.LINE_STYLE_DASHED);

        DiagramImageDto row = onlyRow();

        assertEquals(Integer.valueOf(128), row.opacity());
        assertEquals(Integer.valueOf(90), row.outlineOpacity());
        assertEquals(Integer.valueOf(3), row.lineWidth());
        assertEquals("dashed", row.lineStyle());
    }

    @Test
    public void getViewContents_shouldOmitEachStylingFieldAtItsDefault() {
        f.image(f.view, "img-plain", 20, 20);

        String served = f.serve(Map.of());

        assertTrue(served, served.contains("\"viewObjectId\":\"img-plain\""));
        for (String field : new String[] {"opacity", "outlineOpacity", "lineWidth", "lineStyle"}) {
            assertFalse(field + " must be omitted at its default: " + served, served.contains("\"" + field + "\""));
        }
    }

    @Test
    public void addImageToView_shouldAnswerTheStylingItPlaced() {
        String served = f.serve("add-image-to-view", Map.of("viewId", f.view.getId(), "imagePath",
                ViewContentsFixture.IMAGE_PATH, "opacity", 128, "lineWidth", 2, "lineStyle", "dotted"));

        assertTrue(served, served.contains("\"opacity\":128"));
        assertTrue(served, served.contains("\"lineWidth\":2"));
        assertTrue(served, served.contains("\"lineStyle\":\"dotted\""));
        assertFalse(served, served.contains("\"outlineOpacity\""));
    }

    @Test
    public void getViewContents_shouldReadBackAnImageStyledThroughUpdateViewObject() {
        f.image(f.view, "img-later", 20, 20);

        String updated = f.serve("update-view-object", Map.of("viewObjectId", "img-later", "outlineOpacity", 40));

        assertFalse(updated, updated.startsWith("ERROR "));
        assertEquals(Integer.valueOf(40), onlyRow().outlineOpacity());
        assertNull(onlyRow().opacity());
    }

    @Test
    public void getViewContents_shouldCarryTheStylingOnTheGraphImageNode_too() {
        IDiagramModelImage image = f.image(f.view, "img-graph", 20, 20);
        image.setAlpha(128);
        image.setLineWidth(3);

        String served = f.serve(Map.of("format", "graph"));

        int node = served.indexOf("\"viewObjectId\":\"img-graph\"");
        assertTrue(served, node >= 0);
        String row = served.substring(node, served.indexOf('}', node));
        assertTrue(row, row.contains("\"opacity\":128"));
        assertTrue(row, row.contains("\"lineWidth\":3"));
        assertTrue(row, row.contains("\"_nodeType\":\"image\""));
    }

    private DiagramImageDto onlyRow() {
        return f.accessor.getViewContents(f.view.getId()).orElseThrow().images().get(0);
    }
}
