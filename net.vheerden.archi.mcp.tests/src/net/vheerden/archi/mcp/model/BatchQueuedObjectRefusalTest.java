package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelImage;

import net.vheerden.archi.mcp.response.dto.AddToViewResultDto;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.DiagramImageDto;

/**
 * Pins that a view object an open batch has queued but not yet placed is refused by name where it
 * cannot serve, instead of being answered "not found" for an id an earlier call of the batch returned.
 *
 * <p>A queued object is detached until the batch commits. As a parent, a relationship's connection end
 * or the object to remove, the live lookups behind those uses could not see it. A queued element view
 * object still nests and connects, as it always has; only what used to fail now fails differently. The
 * last pins cover the same removal inside one bulk call, for a same-call element view object.</p>
 */
public class BatchQueuedObjectRefusalTest {

    private static final String PARENT_ADVICE = "A note, an image and a view reference hold no other objects. "
            + "Name a group or an element view object as parentViewObjectId.";
    private static final String END_ADVICE = "A relationship's connection joins the element view objects of its "
            + "source and target. A group, a note, an image and a view reference stand for no element, so none "
            + "can be its end.";
    private static final String REMOVE_ADVICE = "It is not on the view until the batch commits. Commit with "
            + "end-batch and remove it in a later call, or end the batch with rollback: true to discard what it "
            + "queued.";

    private static final String SESSION = ViewContentsFixture.SESSION;

    private ViewContentsFixture f;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-batch-queued-object-refusal");
        f.object(f.view, "vo-a", f.element("el-a"), 40, 40, 120, 55);
        f.relationship("rel-ab", f.element("el-b"), f.element("el-c"));
        f.dispatcher.beginBatch(SESSION, "queued objects");
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    @Test
    public void addImageToView_shouldRefuseAQueuedImageAsItsParent_namingWhatItIs() {
        String image = queuedImage();

        ModelAccessException refused = refusalOf(() -> f.accessor.addImageToView(SESSION, f.view.getId(),
                ViewContentsFixture.IMAGE_PATH, null, null, null, null, image, null, null, null));

        assertWhole("Parent view object must be a group or element: " + image + " is the image this batch places",
                PARENT_ADVICE, refused);
    }

    @Test
    public void addConnectionToView_shouldRefuseAQueuedImageAsAnEnd_namingWhatItIs() {
        String image = queuedImage();

        ModelAccessException refused = refusalOf(() -> f.accessor.addConnectionToView(SESSION, f.view.getId(),
                "rel-ab", image, "vo-a", null, null, null, null, null));

        assertWhole("Connection end must be an element view object: " + image + " is the image this batch places",
                END_ADVICE, refused);
    }

    @Test
    public void removeFromView_shouldRefuseAQueuedImage_namingWhatItIs() {
        String image = queuedImage();

        ModelAccessException refused = refusalOf(() -> f.accessor.removeFromView(SESSION, f.view.getId(), image));

        assertWhole("Cannot remove a view object this batch places: " + image
                + " is the image an earlier operation of this batch places", REMOVE_ADVICE, refused);
    }

    @Test
    public void removeFromView_shouldRefuseAQueuedElementViewObject_too() {
        String element = queuedElement(120, 55);

        ModelAccessException refused = refusalOf(() -> f.accessor.removeFromView(SESSION, f.view.getId(), element));

        assertWhole("Cannot remove a view object this batch places: " + element
                + " is the element view object an earlier operation of this batch places", REMOVE_ADVICE, refused);
    }

    @Test
    public void addImageToView_shouldStillNestUnderAQueuedElementViewObject() {
        String element = queuedElement(200, 150);

        f.accessor.addImageToView(SESSION, f.view.getId(), ViewContentsFixture.IMAGE_PATH, 10, 30, null, null,
                element, null, null, null);
        f.dispatcher.endBatch(SESSION, true);

        IDiagramModelArchimateObject placed = (IDiagramModelArchimateObject) f.view.getChildren().stream()
                .filter(c -> element.equals(c.getId())).findFirst().orElseThrow();
        assertTrue(placed.getChildren().get(0) instanceof IDiagramModelImage);
    }

    @Test
    public void removeFromView_shouldStillRemoveAnObjectAlreadyOnTheView_insideTheBatch() {
        assertTrue(f.accessor.removeFromView(SESSION, f.view.getId(), "vo-a").isBatched());
    }

    @Test
    public void bulkMutate_shouldRefuseRemovingASameCallElementViewObject_namingWhatItIs() {
        f.dispatcher.endBatch(SESSION, true);

        ModelAccessException refused = null;
        try {
            f.accessor.executeBulk(ViewContentsFixture.SESSION, List.of(
                    new BulkOperation("add-to-view", Map.of("viewId", f.view.getId(), "elementId", "el-b",
                            "x", 300, "y", 40, "width", 120, "height", 55), "box"),
                    new BulkOperation("remove-from-view", Map.of("viewId", f.view.getId(),
                            "viewObjectId", "$box.id"), null)), "same-call removal", false);
        } catch (ModelAccessException e) {
            refused = e;
        }

        assertEquals("Operation 1 (remove-from-view): Cannot remove a view object this call places: <id> is the "
                + "element view object operation 0 places",
                refused == null ? "(no refusal)" : refused.getMessage().replaceAll("id-[0-9a-f]{32}", "<id>"));
        assertEquals(1, f.view.getChildren().size());
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private String queuedImage() {
        MutationResult<DiagramImageDto> queued = f.accessor.addImageToView(SESSION, f.view.getId(),
                ViewContentsFixture.IMAGE_PATH, 200, 300, null, null, null, null, null, null);
        assertTrue("the image must be queued, not placed", queued.isBatched());
        return queued.entity().viewObjectId();
    }

    private String queuedElement(int width, int height) {
        MutationResult<AddToViewResultDto> queued = f.accessor.addToView(SESSION, f.view.getId(), "el-b", 300, 40,
                width, height, false, null, null, null);
        assertTrue("the element view object must be queued, not placed", queued.isBatched());
        return queued.entity().viewObject().viewObjectId();
    }

    private static ModelAccessException refusalOf(Runnable call) {
        try {
            call.run();
        } catch (ModelAccessException e) {
            return e;
        }
        throw new AssertionError("no refusal");
    }

    private static void assertWhole(String message, String suggestion, ModelAccessException refused) {
        assertEquals(message, refused.getMessage());
        assertEquals(suggestion, refused.getSuggestedCorrection());
        assertEquals("INVALID_PARAMETER", refused.getErrorCode().name());
    }
}
