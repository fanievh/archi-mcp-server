package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;

/**
 * Pins that a view the staged work deletes ahead of an operation is not found by it:
 * {@code set-view-label-expression} and {@code clear-view} answer the same "View not found" refusal
 * they answer for an id that names no view, whether the delete is earlier in the same
 * {@code bulk-mutate} call or queued in the enclosing batch, and whether it is a {@code delete-view}
 * or a {@code delete-folder} holding the view. A delete that comes after the operation does not count.
 */
public class ViewDeletedAheadTest {

    private static final String SESSION = ViewContentsFixture.SESSION;

    private ViewContentsFixture f;
    private String viewId;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-view-deleted-ahead");
        IArchimateElement p1 = f.element("p1");
        f.object(f.view, "vo-1", p1, 40, 40, 120, 55);
        viewId = f.view.getId();
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    // ---- bulk-mutate ------------------------------------------------------------------------------

    @Test
    public void shouldNotFindForALabel_aViewAnEarlierOperationDeleted() {
        ModelAccessException refused = refused(() -> bulk(deleteView(), label()));

        assertEquals("Operation 1 (set-view-label-expression): " + notFound(), refused.getMessage());
        assertEquals("failedOperationIndex=1, failedTool=set-view-label-expression", refused.getDetails());
        assertTrue("nothing ran", f.model.getDiagramModels().contains(f.view));
    }

    @Test
    public void shouldNotFindForAClear_aViewAnEarlierOperationDeleted() {
        ModelAccessException refused = refused(() -> bulk(deleteView(), clear()));

        assertEquals("Operation 1 (clear-view): " + notFound(), refused.getMessage());
        assertEquals("failedOperationIndex=1, failedTool=clear-view", refused.getDetails());
    }

    @Test
    public void shouldNotFind_aViewInAFolderAnEarlierOperationDeleted() {
        IFolder folder = viewInSubfolder();

        ModelAccessException refused = refused(() -> bulk(
                new BulkOperation("delete-folder", Map.of("folderId", folder.getId(), "force", true), null),
                label()));

        assertEquals("Operation 1 (set-view-label-expression): " + notFound(), refused.getMessage());
    }

    /** The order control: the label runs before the delete, so it finds the view and applies. */
    @Test
    public void shouldStillLabelAndDelete_whenTheDeleteComesAfter() {
        BulkMutationResult result = bulk(label(), deleteView());

        assertTrue(result.allSucceeded());
        assertFalse(f.model.getDiagramModels().contains(f.view));
    }

    // ---- inside a batch ---------------------------------------------------------------------------

    @Test
    public void shouldNotFindForTheSingleClear_aViewTheBatchQueuedADeleteFor() {
        f.dispatcher.beginBatch(SESSION, "queued delete");
        f.accessor.deleteView(SESSION, viewId);

        ModelAccessException refused = refused(() -> f.accessor.clearView(SESSION, viewId));
        f.dispatcher.endBatch(SESSION, true);

        assertEquals(notFound(), refused.getMessage());
        assertEquals(ErrorCode.VIEW_NOT_FOUND, refused.getErrorCode());
        assertEquals("Use get-views to find valid view IDs", refused.getSuggestedCorrection());
        assertFalse(f.model.getDiagramModels().contains(f.view));
    }

    @Test
    public void shouldNotFindForABulkLabel_aViewTheBatchQueuedAFolderDeleteFor() {
        IFolder folder = viewInSubfolder();
        f.dispatcher.beginBatch(SESSION, "queued folder delete");
        f.accessor.deleteFolder(SESSION, folder.getId(), true);

        ModelAccessException refused = refused(() -> bulk(label()));
        f.dispatcher.endBatch(SESSION, false);

        assertEquals("Operation 0 (set-view-label-expression): " + notFound(), refused.getMessage());
    }

    /** The batch's order control: a clear queued before the delete still finds the view. */
    @Test
    public void shouldStillClear_whenTheBatchQueuesTheDeleteAfter() {
        f.dispatcher.beginBatch(SESSION, "clear then delete");
        f.accessor.clearView(SESSION, viewId);
        f.accessor.deleteView(SESSION, viewId);
        f.dispatcher.endBatch(SESSION, true);

        assertFalse(f.model.getDiagramModels().contains(f.view));
    }

    // ---- helpers ----------------------------------------------------------------------------------

    String notFound() {
        return "View not found: " + viewId;
    }

    IFolder viewInSubfolder() {
        IFolder folder = com.archimatetool.model.IArchimateFactory.eINSTANCE.createFolder();
        folder.setName("Holder");
        f.model.getFolder(com.archimatetool.model.FolderType.DIAGRAMS).getFolders().add(folder);
        folder.getElements().add(f.view);
        return folder;
    }

    BulkOperation deleteView() {
        return new BulkOperation("delete-view", Map.of("viewId", viewId), null);
    }

    BulkOperation label() {
        return new BulkOperation("set-view-label-expression", Map.of("viewId", viewId, "labelExpression", "${name}!"),
                null);
    }

    BulkOperation clear() {
        return new BulkOperation("clear-view", Map.of("viewId", viewId), null);
    }

    BulkMutationResult bulk(BulkOperation... operations) {
        return f.accessor.executeBulk(SESSION, List.of(operations), "deleted ahead", false, null);
    }

    static ModelAccessException refused(Runnable call) {
        try {
            call.run();
        } catch (ModelAccessException e) {
            return e;
        }
        fail("expected a refusal");
        return null;
    }
}
