package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IDiagramModelImage;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.ITextPosition;

import net.vheerden.archi.mcp.response.dto.BulkOperation;

/**
 * Pins that one bulk call can place an image or a view reference and then update it by back-reference,
 * as it already can a note, a group or an element view object.
 *
 * <p>Before, {@code $0.id} naming an {@code add-image-to-view} or {@code add-view-reference-to-view}
 * operation was refused as not a create operation, so placing and then styling one took two calls. Every
 * other place such an id can be used either works or answers a refusal naming why.</p>
 */
public class BulkSameCallImageAndReferenceTest {

    private ViewContentsFixture f;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-bulk-same-call-image-and-reference");
        f.object(f.view, "vo-a", f.element("el-a"), 40, 40, 120, 55);
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    @Test
    public void bulkMutate_shouldUpdateAnImageTheSameCallPlaced_byPosition() {
        bulk(placeImage(null), new BulkOperation("update-view-object", Map.of("viewObjectId", "$0.id",
                "opacity", 128), null));

        assertEquals(128, onlyImage().getAlpha());
    }

    @Test
    public void bulkMutate_shouldUpdateAViewReferenceTheSameCallPlaced_byName() {
        bulk(placeReference("logo"), new BulkOperation("update-view-object", Map.of("viewObjectId", "$logo.id",
                "verticalTextAlignment", "bottom"), null));

        assertEquals(ITextPosition.TEXT_POSITION_BOTTOM, onlyReference().getTextPosition());
    }

    @Test
    public void bulkMutate_shouldUpdateAViewReferenceTheSameCallPlaced_byPosition() {
        bulk(placeReference(null), new BulkOperation("update-view-object", Map.of("viewObjectId", "$0.id",
                "x", 500, "fontName", "Arial"), null));

        assertEquals(500, onlyReference().getBounds().getX());
        assertTrue(onlyReference().getFont(), onlyReference().getFont().contains("Arial"));
    }

    @Test
    public void bulkMutate_shouldMoveAndRestyleTheSameCallImage_byName() {
        bulk(placeImage("logo"), new BulkOperation("update-view-object", Map.of("viewObjectId", "$logo.id",
                "x", 300, "y", 310, "lineWidth", 3), null));

        IDiagramModelImage image = onlyImage();
        assertEquals(300, image.getBounds().getX());
        assertEquals(310, image.getBounds().getY());
        assertEquals(3, image.getLineWidth());
    }

    @Test
    public void bulkMutate_shouldRefuseAFieldTheSameCallImageDoesNotTake() {
        String refused = refusal(() -> bulk(placeImage(null), new BulkOperation("update-view-object",
                Map.of("viewObjectId", "$0.id", "fontName", "Arial"), null)));

        assertTrue(refused, refused.startsWith("Operation 1 (update-view-object): Cannot set fontName on an image"));
        assertNull("nothing placed", f.view.getChildren().stream().filter(c -> c instanceof IDiagramModelImage)
                .findFirst().orElse(null));
    }

    @Test
    public void bulkMutate_shouldRefuseASameCallImageAsAParent_namingWhatItIs() {
        ModelAccessException refused = refusalOf(() -> bulk(placeImage(null), new BulkOperation("add-note-to-view",
                Map.of("viewId", f.view.getId(), "content", "n", "parentViewObjectId", "$0.id"), null)));

        assertEquals("Operation 1 (add-note-to-view): Parent view object must be a group or element: <id> is the "
                + "image operation 0 places", masked(refused.getMessage()));
        assertEquals("A note, an image and a view reference hold no other objects. Name a group or an element "
                + "view object as parentViewObjectId.", refused.getSuggestedCorrection());
    }

    @Test
    public void bulkMutate_shouldRefuseASameCallViewReferenceAsAConnectionEnd_namingWhatItIs() {
        f.relationship("rel-x", f.element("el-b"), f.element("el-c"));

        ModelAccessException refused = refusalOf(() -> bulk(placeReference(null), new BulkOperation(
                "add-connection-to-view", Map.of("viewId", f.view.getId(), "relationshipId", "rel-x",
                        "sourceViewObjectId", "vo-a", "targetViewObjectId", "$0.id"), null)));

        assertEquals("Operation 1 (add-connection-to-view): Target view object must be an element view object: "
                + "<id> is the view reference operation 0 places", masked(refused.getMessage()));
        assertEquals("A relationship's connection joins the element view objects of its source and target. A "
                + "group, a note, an image and a view reference stand for no element, so none can be its end.",
                refused.getSuggestedCorrection());
    }

    @Test
    public void bulkMutate_shouldRefuseASameCallViewReferenceAsAParent_namingWhatItIs() {
        ModelAccessException refused = refusalOf(() -> bulk(placeReference("ref"), new BulkOperation("add-image-to-view",
                Map.of("viewId", f.view.getId(), "imagePath", ViewContentsFixture.IMAGE_PATH,
                        "parentViewObjectId", "$ref.id"), null)));

        assertEquals("Operation 1 (add-image-to-view): Parent view object must be a group or element: <id> is the "
                + "view reference operation 0 places", masked(refused.getMessage()));
    }

    @Test
    public void bulkMutate_shouldRefuseASameCallImageAsAConnectionSource_namingWhatItIs() {
        f.relationship("rel-y", f.element("el-d"), f.element("el-e"));

        ModelAccessException refused = refusalOf(() -> bulk(placeImage(null), new BulkOperation(
                "add-connection-to-view", Map.of("viewId", f.view.getId(), "relationshipId", "rel-y",
                        "sourceViewObjectId", "$0.id", "targetViewObjectId", "vo-a"), null)));

        assertEquals("Operation 1 (add-connection-to-view): Source view object must be an element view object: "
                + "<id> is the image operation 0 places", masked(refused.getMessage()));
    }

    @Test
    public void bulkMutate_shouldRefuseRemovingASameCallImage_namingWhatItIs() {
        ModelAccessException refused = refusalOf(() -> bulk(placeImage("logo"), new BulkOperation(
                "remove-from-view", Map.of("viewId", f.view.getId(), "viewObjectId", "$logo.id"), null)));

        assertEquals("Operation 1 (remove-from-view): Cannot remove a view object this call places: <id> is the "
                + "image operation 0 places", masked(refused.getMessage()));
        assertEquals("Leave the operation that places it out of the call instead.", refused.getSuggestedCorrection());
    }

    @Test
    public void updateViewObject_shouldStyleAnImageQueuedEarlierInTheSameBatch() {
        f.dispatcher.beginBatch(ViewContentsFixture.SESSION, "image then opacity");
        String placed = f.serve("add-image-to-view", Map.of("viewId", f.view.getId(), "imagePath",
                ViewContentsFixture.IMAGE_PATH));
        Matcher id = Pattern.compile("\"viewObjectId\":\"(id-[0-9a-f]{32})\"").matcher(placed);
        assertTrue(placed, id.find());

        String updated = f.serve("update-view-object", Map.of("viewObjectId", id.group(1), "opacity", 90));
        f.dispatcher.endBatch(ViewContentsFixture.SESSION, true);

        assertTrue(updated, !updated.startsWith("ERROR "));
        assertEquals(90, onlyImage().getAlpha());
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private BulkOperation placeImage(String as) {
        return new BulkOperation("add-image-to-view", Map.of("viewId", f.view.getId(), "imagePath",
                ViewContentsFixture.IMAGE_PATH, "x", 200, "y", 300), as);
    }

    private BulkOperation placeReference(String as) {
        return new BulkOperation("add-view-reference-to-view", Map.of("viewId", f.view.getId(),
                "referencedViewId", f.other.getId(), "x", 400, "y", 300), as);
    }

    private void bulk(BulkOperation... operations) {
        f.accessor.executeBulk(ViewContentsFixture.SESSION, List.of(operations), "same-call", false);
    }

    private static String refusal(Runnable call) {
        return refusalOf(call).getMessage();
    }

    private static ModelAccessException refusalOf(Runnable call) {
        try {
            call.run();
        } catch (ModelAccessException e) {
            return e;
        }
        throw new AssertionError("no refusal");
    }

    /** The message with the generated object id replaced, since the refused call commits nothing. */
    private static String masked(String message) {
        return message.replaceAll("id-[0-9a-f]{32}", "<id>");
    }

    private IDiagramModelImage onlyImage() {
        return (IDiagramModelImage) f.view.getChildren().stream().filter(c -> c instanceof IDiagramModelImage)
                .findFirst().orElseThrow();
    }

    private IDiagramModelReference onlyReference() {
        return (IDiagramModelReference) f.view.getChildren().stream()
                .filter(c -> c instanceof IDiagramModelReference).findFirst().orElseThrow();
    }
}
