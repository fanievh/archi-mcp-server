package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.response.dto.AddToViewResultDto;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;

/**
 * Pins that the staged-work reader reads a {@code delete-folder} that will decline as deleting
 * nothing. A folder delete declines when an earlier operation puts a child into the folder that
 * the delete was not prepared with (a create, a move in, a new subfolder); it then leaves the folder
 * and everything in it, so the views it holds are still there for a later operation to label, clear
 * or draw into. The reader's answer is the commit's. A view an earlier operation moves out of the
 * folder survives the delete too, whole: the cascade leaves a view no longer in the folder alone. A nested folder's decline does not void the outer delete of the
 * views beside it, and a child added after the delete does not void it.
 *
 * <p>Headless: no view object is sized and nothing measures text.</p>
 */
public class FolderDeleteDeclineReadTest {

    private static final String SESSION = ViewContentsFixture.SESSION;

    private ViewContentsFixture f;
    private IFolder holder;
    private String viewId;

    @Before
    public void setUp() throws Exception {
        f = new ViewContentsFixture("model-folder-delete-decline");
        f.object(f.view, "vo-1", f.element("p1"), 40, 40, 120, 55);
        holder = folder(f.model.getFolder(FolderType.DIAGRAMS), "Holder");
        holder.getElements().add(f.view);
        viewId = f.view.getId();
    }

    @After
    public void tearDown() {
        f.dispose();
    }

    private static IFolder folder(IFolder parent, String name) {
        IFolder folder = IArchimateFactory.eINSTANCE.createFolder();
        folder.setName(name);
        parent.getFolders().add(folder);
        return folder;
    }

    private BulkOperation deleteHolder() {
        return new BulkOperation("delete-folder", Map.of("folderId", holder.getId(), "force", true), null);
    }

    private BulkOperation label() {
        return new BulkOperation("set-view-label-expression", Map.of("viewId", viewId, "labelExpression", "${name}!"),
                null);
    }

    private BulkOperation clear() {
        return new BulkOperation("clear-view", Map.of("viewId", viewId), null);
    }

    private BulkOperation createViewIn(IFolder folder) {
        return new BulkOperation("create-view", Map.of("name", "X", "folderId", folder.getId()), null);
    }

    private BulkOperation moveInto(String objectId, IFolder folder) {
        return new BulkOperation("move-to-folder", Map.of("objectId", objectId, "targetFolderId", folder.getId()),
                null);
    }

    private BulkMutationResult bulk(BulkOperation... operations) {
        return f.accessor.executeBulk(SESSION, List.of(operations), "folder delete", false, null);
    }

    private boolean viewAlive() {
        return f.model.getDiagramModels().contains(f.view);
    }

    /** The label expression the view's element object carries: set-view-label-expression writes it there. */
    private String labelOfView() {
        return f.view.getChildren().get(0).getFeatures().getString("labelExpression", null);
    }

    @Test
    public void shouldLabelAndClearTheView_whenTheQueuedFolderDeleteWillDecline_andTheCommitAgrees() {
        f.dispatcher.beginBatch(SESSION, "create into the folder, then delete it");
        bulk(createViewIn(holder));
        f.accessor.deleteFolder(SESSION, holder.getId(), true);
        BulkMutationResult staged = bulk(label(), clear());
        f.dispatcher.endBatch(SESSION, true);

        assertTrue(staged.allSucceeded());
        assertTrue("the delete declined at commit", viewAlive());
        assertTrue(f.model.getFolder(FolderType.DIAGRAMS).getFolders().contains(holder));
        assertEquals("the label and then the clear ran", 0, f.view.getChildren().size());
    }

    @Test
    public void shouldLabelTheView_whenAnEarlierOperationInTheSameCallMovesAViewIn() {
        BulkMutationResult result = bulk(moveInto(f.other.getId(), holder), deleteHolder(), label());

        assertEquals("the delete declined when it ran: " + result.skippedOperations(), 1,
                result.skippedOperations().size());
        assertTrue(result.skippedOperations().get(0), result.skippedOperations().get(0).contains("Holder"));
        assertTrue(viewAlive());
        assertTrue(holder.getElements().contains(f.other));
        assertEquals("${name}!", labelOfView());
    }

    @Test
    public void shouldLabelTheView_whenTheBatchQueuedAMoveIntoTheFolderAhead() {
        f.dispatcher.beginBatch(SESSION, "move in, then delete");
        bulk(moveInto(f.other.getId(), holder));
        f.accessor.deleteFolder(SESSION, holder.getId(), true);
        BulkMutationResult staged = bulk(label());
        f.dispatcher.endBatch(SESSION, true);

        assertTrue(staged.allSucceeded());
        assertTrue(viewAlive());
        assertEquals("${name}!", labelOfView());
    }

    @Test
    public void shouldLabelTheView_whenAnEarlierOperationCreatesASubfolderInTheFolder() {
        BulkMutationResult result = bulk(
                new BulkOperation("create-folder", Map.of("parentId", holder.getId(), "name", "New"), null),
                deleteHolder(), label());

        assertEquals(result.skippedOperations().toString(), 1, result.skippedOperations().size());
        assertTrue(viewAlive());
        assertEquals("${name}!", labelOfView());
    }

    /** Shape b: the nested folder declines, the view beside it in the outer folder is still deleted. */
    @Test
    public void shouldStillReadTheViewAsGone_whenOnlyANestedFolderWillDecline_andTheCommitDeletesIt() {
        IFolder sub = folder(holder, "Sub");
        f.dispatcher.beginBatch(SESSION, "create into the subfolder, then delete the outer");
        bulk(createViewIn(sub));
        f.accessor.deleteFolder(SESSION, holder.getId(), true);
        ModelAccessException refused = ViewDeletedAheadTest.refused(() -> bulk(label()));
        f.dispatcher.endBatch(SESSION, true);

        assertEquals("Operation 0 (set-view-label-expression): View not found: " + viewId, refused.getMessage());
        assertFalse("the commit deleted the view beside the declining subfolder", viewAlive());
    }

    /**
     * A view moved out of the folder first is not deleted with it, and keeps what it holds: the
     * delete's cascade leaves a view that is no longer in the folder alone. So it is found, labelled,
     * as the commit leaves it.
     */
    @Test
    public void shouldFindAViewTheBatchMovedOutOfTheFolderAhead_withItsContents_asTheCommitLeavesIt() {
        f.dispatcher.beginBatch(SESSION, "move out, then delete");
        bulk(moveInto(viewId, f.model.getFolder(FolderType.DIAGRAMS)));
        f.accessor.deleteFolder(SESSION, holder.getId(), true);
        BulkMutationResult staged = bulk(label());
        f.dispatcher.endBatch(SESSION, true);

        assertTrue(staged.allSucceeded());
        assertTrue(viewAlive());
        assertEquals("the view keeps its object", 1, f.view.getChildren().size());
        assertEquals("${name}!", labelOfView());
        assertFalse(f.model.getFolder(FolderType.DIAGRAMS).getFolders().contains(holder));
    }

    /** The same in one bulk call: the moved-out view survives the delete whole. */
    @Test
    public void shouldKeepAMovedOutViewWhole_whenOneBulkCallMovesItOutAndDeletesTheFolder() {
        BulkMutationResult result = bulk(moveInto(viewId, f.model.getFolder(FolderType.DIAGRAMS)), deleteHolder(),
                label());

        assertTrue(result.skippedOperations().toString(), result.allSucceeded());
        assertTrue(viewAlive());
        assertEquals(1, f.view.getChildren().size());
        assertEquals("${name}!", labelOfView());
        assertFalse(f.model.getFolder(FolderType.DIAGRAMS).getFolders().contains(holder));
    }

    /** Undo puts the folder back and the view back in it, with its object, as before the call. */
    @Test
    public void shouldUndoTheMoveAndTheDelete_leavingTheViewWholeInTheFolder() {
        bulk(moveInto(viewId, f.model.getFolder(FolderType.DIAGRAMS)), deleteHolder());
        f.stack.undo();

        assertTrue(f.model.getFolder(FolderType.DIAGRAMS).getFolders().contains(holder));
        assertTrue(holder.getElements().contains(f.view));
        assertEquals(1, f.view.getChildren().size());
    }

    /** A move out that a later move brings back leaves the view in the folder, so the delete takes it. */
    @Test
    public void shouldStillReadTheViewAsGone_whenTheBatchMovesItOutAndBackAhead() {
        f.dispatcher.beginBatch(SESSION, "move out, move back, then delete");
        bulk(moveInto(viewId, f.model.getFolder(FolderType.DIAGRAMS)));
        bulk(moveInto(viewId, holder));
        f.accessor.deleteFolder(SESSION, holder.getId(), true);
        ModelAccessException refused = ViewDeletedAheadTest.refused(() -> bulk(label()));
        f.dispatcher.endBatch(SESSION, true);

        assertEquals("Operation 0 (set-view-label-expression): View not found: " + viewId, refused.getMessage());
        assertFalse("the commit deleted the view", viewAlive());
    }

    /** Control: a child added after the delete is not ahead of it, so the delete still takes the view. */
    @Test
    public void shouldStillReadTheViewAsGone_whenTheChildArrivesAfterTheDelete() {
        ModelAccessException refused = ViewDeletedAheadTest.refused(() -> bulk(deleteHolder(),
                moveInto(f.other.getId(), holder), label()));

        assertEquals("Operation 2 (set-view-label-expression): View not found: " + viewId, refused.getMessage());
        assertTrue("nothing ran", viewAlive());
    }

    /** Control: nothing staged into the folder, so the delete is read as it always was. */
    @Test
    public void shouldStillReadTheViewAsGone_whenNothingIsStagedIntoTheFolder() {
        ModelAccessException refused = ViewDeletedAheadTest.refused(() -> bulk(deleteHolder(), label()));

        assertEquals("Operation 1 (set-view-label-expression): View not found: " + viewId, refused.getMessage());
    }

    /** A relationship folder that will decline keeps its relationships, so autoConnect draws them. */
    @Test
    public void shouldAutoConnectARelationshipInARelationsFolderThatWillDecline() {
        IArchimateElement p1 = f.element("p1-el");
        IArchimateElement p2 = f.element("p2-el");
        f.object(f.other, "vo-p1", p1, 40, 40, 120, 55);
        IArchimateRelationship r12 = f.relationship("r12", p1, p2);
        IArchimateRelationship rx = f.relationship("rx", p2, p1);
        IFolder relations = folder(f.model.getFolder(FolderType.RELATIONS), "FR");
        relations.getElements().add(r12);

        f.dispatcher.beginBatch(SESSION, "move a relationship in, then delete the folder");
        bulk(moveInto(rx.getId(), relations));
        f.accessor.deleteFolder(SESSION, relations.getId(), true);
        AddToViewResultDto placed = f.accessor.addToView(SESSION, f.other.getId(), p2.getId(), 400, 40, 120, 55,
                true, null, null, null).entity();
        f.dispatcher.endBatch(SESSION, true);

        assertNotNull(placed.autoConnections());
        assertTrue(placed.autoConnections().toString(), placed.autoConnections().stream()
                .anyMatch(c -> "r12".equals(c.relationshipId())));
        assertTrue("the delete declined at commit", relations.getElements().contains(r12));
    }

    // ---- the queued-geometry check reads a declining folder delete as removing nothing ----

    /** A second object and a connection, so a route has something to route against. */
    private void connect() {
        IDiagramModelArchimateObject a = (IDiagramModelArchimateObject) f.view.getChildren().get(0);
        f.plainConnection("c-1", a, f.object(f.view, "vo-2", f.element("p2"), 400, 40, 120, 55));
    }

    private String geometryCheck() {
        try {
            QueuedViewGeometry.refuseIfChanged(f.dispatcher, SESSION, f.view, null, "full", null);
            return "ran";
        } catch (ModelAccessException e) {
            return e.getMessage();
        }
    }

    private BulkOperation noteBelowContent() {
        return new BulkOperation("add-note-to-view", Map.of("viewId", viewId, "content", "n", "position",
                "below-content", "width", 440, "height", 40), null);
    }

    /** A route checked against the view finds nothing removed: the delete will decline, and the commit keeps the view. */
    @Test
    public void shouldNotReadTheViewsObjectsAsRemoved_forARoute_whenTheQueuedFolderDeleteWillDecline() {
        connect();
        f.dispatcher.beginBatch(SESSION, "create into the folder, delete it, then route");
        bulk(createViewIn(holder));
        f.accessor.deleteFolder(SESSION, holder.getId(), true);
        String routed = geometryCheck();
        f.dispatcher.endBatch(SESSION, true);

        assertEquals("ran", routed);
        assertTrue("the delete declined at commit", viewAlive());
        assertEquals(2, f.view.getChildren().size());
    }

    /** Control: a folder delete nothing makes decline takes the view's objects, and the route is refused. */
    @Test
    public void shouldStillReadTheViewsObjectsAsRemoved_forARoute_whenTheQueuedFolderDeleteWillRun() {
        connect();
        f.dispatcher.beginBatch(SESSION, "delete the folder, then route");
        f.accessor.deleteFolder(SESSION, holder.getId(), true);
        String routed = geometryCheck();
        f.dispatcher.endBatch(SESSION, true);

        assertTrue(routed, routed.contains("removed: vo-1"));
        assertFalse("the commit deleted the view", viewAlive());
    }

    /** A note placed against the content finds nothing removed when the folder delete will decline. */
    @Test
    public void shouldPlaceANoteAgainstTheContent_whenTheQueuedFolderDeleteWillDecline() {
        f.dispatcher.beginBatch(SESSION, "create into the folder, delete it, then place a note");
        bulk(createViewIn(holder));
        f.accessor.deleteFolder(SESSION, holder.getId(), true);
        BulkMutationResult placed = bulk(noteBelowContent());
        f.dispatcher.endBatch(SESSION, true);

        assertTrue(placed.allSucceeded());
        assertTrue(viewAlive());
        assertEquals("the object and the note", 2, f.view.getChildren().size());
    }

    // ---- a move of an object deleted ahead of it brings nothing into the folder ----

    /** Delete a view, move it into the folder, delete the folder: the move declines, so the folder delete runs. */
    @Test
    public void shouldReadTheViewAsGone_whenTheViewMovedIntoTheFolderWasDeletedFirst() {
        f.dispatcher.beginBatch(SESSION, "delete a view, move it in, then delete the folder");
        f.accessor.deleteView(SESSION, f.other.getId());
        bulk(moveInto(f.other.getId(), holder));
        f.accessor.deleteFolder(SESSION, holder.getId(), true);
        ModelAccessException refused = ViewDeletedAheadTest.refused(() -> bulk(label()));
        f.dispatcher.endBatch(SESSION, true);

        assertEquals("Operation 0 (set-view-label-expression): View not found: " + viewId, refused.getMessage());
        assertFalse("the commit deleted the view", viewAlive());
        assertFalse(f.model.getFolder(FolderType.DIAGRAMS).getFolders().contains(holder));
    }

    /** The other order: the move runs before the view's delete, so it arrives, and the folder delete declines. */
    @Test
    public void shouldLabelTheView_whenTheViewMovedIntoTheFolderIsDeletedOnlyAfterTheMove() {
        f.dispatcher.beginBatch(SESSION, "move a view in, delete it, then delete the folder");
        bulk(moveInto(f.other.getId(), holder));
        f.accessor.deleteView(SESSION, f.other.getId());
        f.accessor.deleteFolder(SESSION, holder.getId(), true);
        BulkMutationResult staged = bulk(label());
        f.dispatcher.endBatch(SESSION, true);

        assertTrue(staged.allSucceeded());
        assertTrue("the delete declined at commit", viewAlive());
        assertTrue(f.model.getFolder(FolderType.DIAGRAMS).getFolders().contains(holder));
        assertEquals("${name}!", labelOfView());
    }

    /**
     * A relationship an earlier element delete takes with it is gone before it is moved: the move
     * declines, so the folder delete runs and takes the folder's relationship, and autoConnect does
     * not draw it.
     */
    @Test
    public void shouldNotAutoConnectARelationshipInAFolderWhoseOnlyArrivalWasDeletedWithItsElement() {
        IArchimateElement p1 = f.element("p1-el");
        IArchimateElement p2 = f.element("p2-el");
        IArchimateElement p3 = f.element("p3-el");
        f.object(f.other, "vo-p1", p1, 40, 40, 120, 55);
        IArchimateRelationship r12 = f.relationship("r12", p1, p2);
        IArchimateRelationship r3 = f.relationship("r3", p3, p2);
        IFolder relations = folder(f.model.getFolder(FolderType.RELATIONS), "FR");
        relations.getElements().add(r12);

        f.dispatcher.beginBatch(SESSION, "delete an element, move its relationship in, then delete the folder");
        f.accessor.deleteElement(SESSION, p3.getId());
        bulk(moveInto(r3.getId(), relations));
        f.accessor.deleteFolder(SESSION, relations.getId(), true);
        AddToViewResultDto placed = f.accessor.addToView(SESSION, f.other.getId(), p2.getId(), 400, 40, 120, 55,
                true, null, null, null).entity();
        f.dispatcher.endBatch(SESSION, true);

        assertFalse("the folder delete ran at commit", f.model.getFolder(FolderType.RELATIONS).getFolders()
                .contains(relations));
        assertTrue(String.valueOf(placed.autoConnections()), placed.autoConnections() == null
                || placed.autoConnections().stream().noneMatch(c -> "r12".equals(c.relationshipId())));
    }
}
