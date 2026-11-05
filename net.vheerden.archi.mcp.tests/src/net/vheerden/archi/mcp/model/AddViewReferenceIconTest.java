package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.IIconic;

import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.EmbeddedViewDto;
import net.vheerden.archi.mcp.response.dto.PendingProposalView;

/**
 * Pins that {@code add-view-reference-to-view} takes a view reference's icon when it places one, as the
 * other placement tools do: {@code imagePath}, {@code imagePosition} and {@code showIcon}, on the tool, in
 * bulk and under approval, with the add response and the {@code get-view-contents} row agreeing.
 *
 * <p>Before, the tool published no icon keys yet answered success when given them, dropping the icon, so
 * an iconned reference took two calls: place it, then {@code update-view-object}.</p>
 */
public class AddViewReferenceIconTest {

    private static final ImageParams ICON = new ImageParams(ViewContentsFixture.IMAGE_PATH, "top-left", "always");

    private ViewContentsFixture f;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-add-view-reference-icon");
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    @Test
    public void addViewReferenceToView_shouldTakeTheIcon_onTheTool() {
        String served = f.serve("add-view-reference-to-view", iconArgs());

        assertFalse(served, served.startsWith("ERROR "));
        assertTrue(served, served.contains("\"imagePath\":\"" + ViewContentsFixture.IMAGE_PATH + "\","
                + "\"imagePosition\":\"top-left\",\"showIcon\":\"always\""));
        IDiagramModelReference placed = onlyReference();
        assertEquals(ViewContentsFixture.IMAGE_PATH, placed.getImagePath());
        assertEquals(IIconic.ICON_POSITION_TOP_LEFT, placed.getImagePosition());
    }

    @Test
    public void addViewReferenceToView_shouldAnswerTheRowGetViewContentsReadsBack() {
        EmbeddedViewDto added = f.accessor.addViewReferenceToView(ViewContentsFixture.SESSION, f.view.getId(),
                f.other.getId(), 10, 400, 150, 70, null, null, ICON).entity();

        EmbeddedViewDto row = f.accessor.getViewContents(f.view.getId()).orElseThrow().viewReferences().get(0);

        assertEquals(ViewContentsFixture.IMAGE_PATH, row.imagePath());
        assertEquals("top-left", row.imagePosition());
        assertEquals("always", row.showIcon());
        assertEquals(List.of(row.imagePath(), row.imagePosition(), row.showIcon()),
                List.of(added.imagePath(), added.imagePosition(), added.showIcon()));
    }

    @Test
    public void addViewReferenceToView_shouldRefuseAnIconMissingFromTheArchive() {
        Map<String, Object> args = iconArgs();
        args.put("imagePath", "images/missing.png");

        String served = f.serve("add-view-reference-to-view", args);

        assertTrue(served, served.startsWith("ERROR {\"error\":{\"code\":\"IMAGE_NOT_FOUND\",\"message\":\"imagePath "
                + "'images/missing.png' does not resolve to an image in the model archive\""));
        assertTrue("nothing placed", f.view.getChildren().isEmpty());
    }

    @Test
    public void bulkMutate_shouldPlaceAnIconnedReference() {
        f.accessor.executeBulk(ViewContentsFixture.SESSION, List.of(new BulkOperation("add-view-reference-to-view",
                iconArgs(), null)), "iconned reference", false);

        IDiagramModelReference placed = onlyReference();
        assertEquals(ViewContentsFixture.IMAGE_PATH, placed.getImagePath());
        assertEquals(IIconic.ICON_POSITION_TOP_LEFT, placed.getImagePosition());
        assertEquals("always", ImageHelper.readShowIcon(placed));
    }

    @Test
    public void bulkMutate_shouldRefuseAnIconMissingFromTheArchive() {
        Map<String, Object> args = iconArgs();
        args.put("imagePath", "images/missing.png");
        try {
            f.accessor.executeBulk(ViewContentsFixture.SESSION, List.of(new BulkOperation(
                    "add-view-reference-to-view", args, null)), "missing icon", false);
            fail("expected a missing icon to be refused");
        } catch (ModelAccessException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("does not resolve to an image in the model archive"));
        }
        assertTrue(f.view.getChildren().isEmpty());
    }

    @Test
    public void addViewReferenceToView_shouldNameTheIconOnTheProposal_whenApprovalIsRequired() {
        f.approval = true;

        String served = f.serve("add-view-reference-to-view", iconArgs());

        assertFalse(served, served.startsWith("ERROR "));
        List<PendingProposalView> pending = f.dispatcher.listAllPending();
        assertEquals(1, pending.size());
        assertEquals(Map.of("imagePath", ViewContentsFixture.IMAGE_PATH, "imagePosition", "top-left", "showIcon",
                "always"), pending.get(0).proposal().proposedChanges().get("imageParams"));
        assertTrue("nothing is placed until approved", f.view.getChildren().isEmpty());
    }

    @Test
    public void toolsList_shouldPublishTheThreeIconKeys_onTheTool() {
        Map<String, Object> properties = f.tool("add-view-reference-to-view").inputSchema().properties();

        assertTrue(properties.keySet().toString(), properties.keySet().containsAll(
                List.of("imagePath", "imagePosition", "showIcon")));
    }

    private Map<String, Object> iconArgs() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("viewId", f.view.getId());
        args.put("referencedViewId", f.other.getId());
        args.put("x", 10);
        args.put("y", 400);
        args.put("imagePath", ViewContentsFixture.IMAGE_PATH);
        args.put("imagePosition", "top-left");
        args.put("showIcon", "always");
        return args;
    }

    private IDiagramModelReference onlyReference() {
        assertEquals(1, f.view.getChildren().size());
        return (IDiagramModelReference) f.view.getChildren().get(0);
    }
}
