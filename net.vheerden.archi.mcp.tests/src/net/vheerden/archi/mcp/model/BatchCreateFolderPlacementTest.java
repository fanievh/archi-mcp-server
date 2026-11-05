package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.INameable;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.BatchSummaryDto;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.FolderDto;
import net.vheerden.archi.mcp.response.dto.MoveResultDto;
import net.vheerden.archi.mcp.response.dto.ViewDto;

/**
 * Pins that an operation inside a batch can name a folder an earlier operation of the batch creates,
 * in every slot that takes a folder: {@code create-element} and {@code create-view} {@code folderId},
 * {@code create-folder} {@code parentId}, {@code move-to-folder} {@code targetFolderId} and the
 * {@code update-folder} id.
 *
 * <p>Each slot looked the id up in the model, where a queued folder is not until the batch commits,
 * and answered {@code FOLDER_NOT_FOUND}. The folder is now found among the batch's queued creates, its
 * previewed path is the path it has once the batch commits, and the layer and Views-root rules are
 * judged by the nearest folder above it already in the model. A queued folder cannot itself be moved
 * or deleted in the batch that creates it. A move into a folder that is not in the model when the move
 * runs — its create declined, or an earlier operation deleted it — declines instead of taking the
 * moved object out of the model with it.</p>
 */
public class BatchCreateFolderPlacementTest {

    private static final String SESSION = "batch-create-folder-session";
    private static final String AFTER = "[Application [A Existing [B2 InB]]][Views [VA InVA]]";

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private IArchimateModel model;
    private IFolder application;
    private IFolder views;
    private IArchimateElement existing;
    private MutationDispatcher dispatcher;
    private ArchiModelAccessorImpl accessor;
    private CommandStack stack;

    @Before
    public void setUp() {
        model = factory.createArchimateModel();
        model.setId("model-batch-create-folder");
        model.setDefaults();
        application = model.getFolder(FolderType.APPLICATION);
        views = model.getFolder(FolderType.DIAGRAMS);
        existing = factory.createApplicationComponent();
        existing.setId("el-existing");
        existing.setName("Existing");
        application.getElements().add(existing);

        AutoRouteArmFixture.StubEditorModelManager mgr = new AutoRouteArmFixture.StubEditorModelManager();
        mgr.setModels(List.of(model));
        stack = new CommandStack();
        model.setAdapter(CommandStack.class, stack);
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                stack.execute(new AgentAuthoredCompoundCommand(toPlainCompound(command)));
            }
            @Override
            protected void dispatchCommand(Command command) {
                stack.execute(new AgentAuthoredCompoundCommand(toPlainCompound(command)));
            }
        };
        dispatcher.setApprovalModeProvider(() -> false);
        accessor = new ArchiModelAccessorImpl(mgr, dispatcher);
        dispatcher.onModelActive(model);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ---- every slot -------------------------------------------------------------------------------------

    @Test
    public void batch_shouldPutEverythingWhereTheBatchSaid_withPreviewsThatMatchTheCommittedPaths() throws Exception {
        String before = tree();
        dispatcher.beginBatch(SESSION, "folders in a batch");
        FolderDto a = accessor.createFolder(SESSION, application.getId(), "A", null, null).entity();
        FolderDto b = accessor.createFolder(SESSION, a.id(), "B", null, null).entity();
        accessor.createElement(SESSION, "ApplicationComponent", "InB", null, null, b.id(), null);
        FolderDto va = accessor.createFolder(SESSION, views.getId(), "VA", null, null).entity();
        ViewDto view = accessor.createView(SESSION, "InVA", null, va.id(), null).entity();
        FolderDto renamed = accessor.updateFolder(SESSION, b.id(), "B2", null, null).entity();
        MoveResultDto moved = accessor.moveToFolder(SESSION, existing.getId(), a.id()).entity();

        assertEquals("Application/A/B", b.path());
        assertEquals("Views/VA", view.folderPath());
        assertEquals("Application/A/B2", renamed.path());
        assertEquals("Application/A", moved.targetFolderPath());

        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals(null, summary.skippedOperations());
        assertEquals(AFTER, tree());
        stack.undo();
        assertEquals("undo puts the tree back as it was before the batch", before, tree());
        stack.redo();
        assertEquals(AFTER, tree());
    }

    @Test
    public void batch_shouldGiveAPathThroughAQueuedFolderTheNameAnEarlierRenameGaveIt() throws Exception {
        dispatcher.beginBatch(SESSION, "create a folder, rename it, create inside it");
        FolderDto a = accessor.createFolder(SESSION, application.getId(), "A", null, null).entity();
        accessor.updateFolder(SESSION, a.id(), "A2", null, null);
        FolderDto c = accessor.createFolder(SESSION, a.id(), "C", null, null).entity();

        assertEquals("Application/A2/C", c.path());
        dispatcher.endBatch(SESSION, true);
        assertEquals("[Application Existing [A2 [C]]][Views]", tree());
    }

    @Test
    public void bulkInABatch_shouldPlaceIntoAFolderAnEarlierBatchOperationCreated() throws Exception {
        dispatcher.beginBatch(SESSION, "a folder, then a bulk call that fills it");
        FolderDto a = accessor.createFolder(SESSION, application.getId(), "A", null, null).entity();
        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                op("create-folder", "parentId", a.id(), "name", "B"),
                op("create-element", "type", "ApplicationComponent", "name", "InB", "folderId", "$0.id"),
                op("move-to-folder", "objectId", existing.getId(), "targetFolderId", a.id())),
                "fill the queued folder", false, null);
        assertTrue(result.allSucceeded());
        dispatcher.endBatch(SESSION, true);

        assertEquals("[Application [A Existing [B InB]]][Views]", tree());
    }

    // ---- the rules that hold for a folder in the model hold for a queued one ------------------------------

    @Test
    public void batch_shouldRefuseAnElementInAQueuedFolderUnderAnotherLayer() {
        dispatcher.beginBatch(SESSION, "wrong layer");
        FolderDto biz = accessor.createFolder(SESSION, model.getFolder(FolderType.BUSINESS).getId(), "Biz",
                null, null).entity();
        try {
            accessor.createElement(SESSION, "ApplicationComponent", "Wrong", null, null, biz.id(), null);
            fail("an application element in a business folder must be refused");
        } catch (ModelAccessException refusal) {
            assertTrue(refusal.getMessage(), refusal.getMessage().contains(
                    "belong to the Application layer but the target folder 'Biz' is under the Business layer"));
        }
        assertEquals("the batch holds the folder only", 1, (int) dispatcher.getBatchStatus(SESSION).queuedCount());
    }

    @Test
    public void batch_shouldRefuse_movingOrDeletingAFolderTheBatchCreates() {
        dispatcher.beginBatch(SESSION, "move or delete a queued folder");
        FolderDto made = accessor.createFolder(SESSION, application.getId(), "New", null, null).entity();
        try {
            accessor.moveToFolder(SESSION, made.id(), application.getId());
            fail("a queued folder cannot be moved in its own batch");
        } catch (ModelAccessException refusal) {
            assertEquals(ErrorCode.INVALID_PARAMETER, refusal.getErrorCode());
            assertTrue(refusal.getMessage(), refusal.getMessage().contains("'New' is created by an earlier "
                    + "operation that has not been committed yet, so it cannot be moved until it has"));
        }
        try {
            accessor.deleteFolder(SESSION, made.id(), false);
            fail("a queued folder cannot be deleted in its own batch");
        } catch (ModelAccessException refusal) {
            assertEquals(ErrorCode.INVALID_PARAMETER, refusal.getErrorCode());
            assertTrue(refusal.getMessage(), refusal.getMessage().contains("Folder 'New' is created by an "
                    + "earlier operation that has not been committed yet, so it cannot be deleted until it has"));
        }
        assertEquals(1, (int) dispatcher.getBatchStatus(SESSION).queuedCount());
    }

    @Test
    public void batch_shouldStillAnswerFolderNotFound_forAnIdThatNamesNoFolder() {
        dispatcher.beginBatch(SESSION, "a bogus folder id");
        accessor.createFolder(SESSION, application.getId(), "A", null, null);
        try {
            accessor.createElement(SESSION, "ApplicationComponent", "E", null, null, "no-such-folder", null);
            fail("a bogus folder id must be refused");
        } catch (ModelAccessException refusal) {
            assertEquals(ErrorCode.FOLDER_NOT_FOUND, refusal.getErrorCode());
        }
    }

    // ---- a move into a folder that is gone when it runs ---------------------------------------------------

    @Test
    public void batch_shouldDeclineAMoveIntoAQueuedFolderWhoseCreateDeclined_andKeepTheObject() throws Exception {
        IFolder doomed = folder(application, "Doomed");
        dispatcher.beginBatch(SESSION, "delete a folder, create one in it, move into that");
        accessor.deleteFolder(SESSION, doomed.getId(), true);
        FolderDto orphan = accessor.createFolder(SESSION, doomed.getId(), "Orphan", null, null).entity();
        accessor.moveToFolder(SESSION, existing.getId(), orphan.id());

        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertSame("the element stays where it was", application, existing.eContainer());
        assertMoveDeclined(summary, "Orphan");
    }

    @Test
    public void batch_shouldDeclineAMoveIntoAFolderAnEarlierOperationDeleted_andKeepTheObject() throws Exception {
        IFolder doomed = folder(application, "Doomed");
        dispatcher.beginBatch(SESSION, "delete a folder, then move into it");
        accessor.deleteFolder(SESSION, doomed.getId(), true);
        accessor.moveToFolder(SESSION, existing.getId(), doomed.getId());

        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertSame("the element stays where it was", application, existing.eContainer());
        assertMoveDeclined(summary, "Doomed");
    }

    @Test
    public void batch_shouldDeclineMovingAFolderIntoAFolderAnEarlierOperationDeleted_andKeepItsContents() throws Exception {
        IFolder doomed = folder(application, "Doomed");
        IFolder kept = folder(application, "Kept");
        kept.getElements().add(existing);
        dispatcher.beginBatch(SESSION, "delete a folder, then move a folder into it");
        accessor.deleteFolder(SESSION, doomed.getId(), true);
        accessor.moveToFolder(SESSION, kept.getId(), doomed.getId());

        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertSame("the folder stays where it was", application, kept.eContainer());
        assertSame("with its contents", model, existing.getArchimateModel());
        List<String> skipped = summary.skippedOperations();
        assertTrue("skipped: " + skipped, skipped != null && skipped.stream().anyMatch(line -> line.contains(
                "'Kept' was not moved into 'Doomed': that folder is not in the model")));
    }

    @Test
    public void batch_shouldLeaveRemoved_anElementWhoseFolderAnEarlierOperationDeleted() throws Exception {
        IFolder doomed = folder(application, "Doomed");
        IFolder kept = folder(application, "Kept");
        doomed.getElements().add(existing);
        dispatcher.beginBatch(SESSION, "delete a folder, then move what was in it");
        accessor.deleteFolder(SESSION, doomed.getId(), true);
        accessor.moveToFolder(SESSION, existing.getId(), kept.getId());

        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals("the element stays removed", null, existing.eContainer());
        assertTrue("and Kept stays empty", kept.getElements().isEmpty());
        stack.undo();
        assertSame("undo brings it back where it was", doomed, existing.eContainer());
        List<String> skipped = summary.skippedOperations();
        assertTrue("skipped: " + skipped, skipped != null && skipped.stream().anyMatch(line -> line.contains(
                "'Existing' was not moved into 'Kept': it is no longer in the model.")));
    }

    @Test
    public void batch_shouldDeclineUpdatingAFolderWhoseCreateDeclined() throws Exception {
        IFolder doomed = folder(application, "Doomed");
        dispatcher.beginBatch(SESSION, "delete a folder, create one in it, rename that");
        accessor.deleteFolder(SESSION, doomed.getId(), true);
        FolderDto orphan = accessor.createFolder(SESSION, doomed.getId(), "Orphan", null, null).entity();
        accessor.updateFolder(SESSION, orphan.id(), "Renamed", null, null);

        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        List<String> skipped = summary.skippedOperations();
        assertTrue("skipped: " + skipped, skipped != null && skipped.stream().anyMatch(line -> line.contains(
                "The folder 'Orphan' was not updated: it is not in the model.")));
    }

    @Test
    public void batch_shouldGiveAPathThroughAFolderInTheModel_theNameAnEarlierRenameGaveIt() throws Exception {
        IFolder r = folder(application, "Drafts");
        IFolder sub = folder(r, "Sub");
        IFolder kept = folder(application, "Kept");
        dispatcher.beginBatch(SESSION, "rename a folder, then file under it");
        accessor.updateFolder(SESSION, r.getId(), "Reports", null, null);
        FolderDto n = accessor.createFolder(SESSION, r.getId(), "N", null, null).entity();
        MoveResultDto into = accessor.moveToFolder(SESSION, existing.getId(), r.getId()).entity();
        MoveResultDto out = accessor.moveToFolder(SESSION, sub.getId(), kept.getId()).entity();

        assertEquals("Application/Reports/N", n.path());
        assertEquals("Application/Reports", into.targetFolderPath());
        assertEquals("Application/Reports", out.sourceFolderPath());
        dispatcher.endBatch(SESSION, true);
    }

    @Test
    public void bulk_shouldDeclineAMoveIntoAFolderTheCallCreatesInAFolderItDeletes() {
        IFolder doomed = folder(application, "Doomed");
        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                op("delete-folder", "folderId", doomed.getId(), "force", true),
                op("create-folder", "parentId", doomed.getId(), "name", "Orphan"),
                op("move-to-folder", "objectId", existing.getId(), "targetFolderId", "$1.id")),
                "move into a folder that will not exist", false, null);

        assertSame("the element stays where it was", application, existing.eContainer());
        assertTrue("skipped: " + result.skippedOperations(), result.skippedOperations().stream()
                .anyMatch(line -> line.contains("'Existing' was not moved into 'Orphan': that folder is not in the model")));
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private static void assertMoveDeclined(BatchSummaryDto summary, String target) {
        List<String> skipped = summary.skippedOperations();
        assertTrue("skipped: " + skipped, skipped != null && skipped.stream().anyMatch(line -> line.contains(
                "'Existing' was not moved into '" + target + "': that folder is not in the model")));
    }

    private IFolder folder(IFolder parent, String name) {
        IFolder folder = factory.createFolder();
        folder.setName(name);
        parent.getFolders().add(folder);
        return folder;
    }

    private static BulkOperation op(String tool, Object... keysAndValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            params.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return new BulkOperation(tool, params);
    }

    /** The Application and Views trees, names only, in folder order. */
    private String tree() {
        return tree(application) + tree(views);
    }

    private static String tree(IFolder folder) {
        StringBuilder sb = new StringBuilder("[").append(folder.getName());
        for (EObject child : folder.getElements()) {
            sb.append(' ').append(((INameable) child).getName());
        }
        for (IFolder sub : folder.getFolders()) {
            sb.append(' ').append(tree(sub));
        }
        return sb.append(']').toString();
    }

    private static Command toPlainCompound(Command command) {
        if (command instanceof RequireAttachedContainerCommand guard) {
            return guard.withGuarded(toPlainCompound(guard.getGuarded()));
        }
        if (command instanceof CompoundCommand compound) {
            CompoundCommand plain = new CompoundCommand(compound.getLabel());
            for (Object child : compound.getCommands()) {
                plain.add(toPlainCompound((Command) child));
            }
            return plain;
        }
        return command;
    }
}
