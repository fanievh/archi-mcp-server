package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.FolderDto;

/**
 * Pins that what a call says about a folder follows a move of that folder queued earlier in the same
 * call: the path it previews, the layer a later move into it is judged by, and whether a later move
 * would put a folder inside itself.
 *
 * <p>Before, an earlier move of a folder already in the model was not seen. A folder created under a
 * moved folder previewed its old path; a business concept moved into a business folder that an
 * earlier operation had moved under the Application root was accepted, then declined at commit; and
 * "move F into G" then "move G into F" was accepted, then declined at commit. Moving F back where it
 * started was refused as "already in the target folder", and an object moved twice was recorded as
 * leaving the folder it was in before the call, so undo could not put the call back in order.</p>
 */
public class QueuedFolderMoveTest {

    private static final String SESSION = "queued-folder-move-session";

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private IArchimateModel model;
    private IFolder business;
    private IFolder f;
    private IFolder g;
    private IFolder app;
    private IArchimateElement actor;
    private MutationDispatcher dispatcher;
    private ArchiModelAccessorImpl accessor;
    private CommandStack stack;

    @Before
    public void setUp() {
        model = factory.createArchimateModel();
        model.setId("model-queued-folder-move");
        model.setDefaults();
        business = model.getFolder(FolderType.BUSINESS);
        f = folder(business, "F");
        g = folder(business, "G");
        app = folder(model.getFolder(FolderType.APPLICATION), "App");
        actor = factory.createBusinessActor();
        actor.setId("actor");
        actor.setName("Actor");
        business.getElements().add(actor);

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

    // ---- the path ------------------------------------------------------------------------------------

    @Test
    public void preview_shouldGiveTheMovedFolderItsPathAfterTheMove() {
        List<PreparedMutation<?>> call = new ArrayList<>();
        FolderMutationOperations folders = accessor.folderOps;
        call.add(folders.prepareMoveToFolder(f.getId(), g.getId(), call));
        PreparedMutation<FolderDto> made = folders.prepareCreateFolder(f.getId(), "N", null, null, call);
        call.add(made);

        assertEquals("Business/G/F/N", made.entity().path());
        assertEquals("Business/G/F", FolderMutationOperations.pathOf(f, call));

        CompoundCommand all = new CompoundCommand();
        call.forEach(prepared -> all.add(toPlainCompound(prepared.command())));
        stack.execute(all);
        assertEquals("the preview is the committed path", made.entity().path(),
                FolderOperations.buildFolderPath((IFolder) made.rawObject()));
    }

    @Test
    public void preview_shouldFollowTheLastMove_whenTheFolderIsMovedTwice() {
        List<PreparedMutation<?>> call = new ArrayList<>();
        FolderMutationOperations folders = accessor.folderOps;
        IFolder h = folder(business, "H");
        call.add(folders.prepareMoveToFolder(f.getId(), g.getId(), call));
        call.add(folders.prepareMoveToFolder(f.getId(), h.getId(), call));

        assertEquals("Business/H/F", FolderMutationOperations.pathOf(f, call));
    }

    @Test
    public void batch_shouldPreviewThePathAfterTheMove() throws Exception {
        dispatcher.beginBatch(SESSION, "move F into G, then make N in F");
        accessor.moveToFolder(SESSION, f.getId(), g.getId());
        FolderDto made = accessor.createFolder(SESSION, f.getId(), "N", null, null).entity();
        dispatcher.endBatch(SESSION, true);

        assertEquals("Business/G/F/N", made.path());
        assertSame(g, f.eContainer());
    }

    @Test
    public void preview_shouldBeUnchanged_whenNoMoveIsQueued() {
        List<PreparedMutation<?>> call = new ArrayList<>();
        assertEquals("Business/F/N",
                accessor.folderOps.prepareCreateFolder(f.getId(), "N", null, null, call).entity().path());
        assertSame(f, FolderMutationOperations.inModel(f, call));
        assertEquals("Business/F", FolderMutationOperations.pathOf(f, call));
    }

    // ---- the cycle check -----------------------------------------------------------------------------

    @Test
    public void batch_shouldRefuseAtOnce_aMoveThatAnEarlierMoveTurnsIntoACycle() throws Exception {
        dispatcher.beginBatch(SESSION, "move F into G, then G into F");
        accessor.moveToFolder(SESSION, f.getId(), g.getId());
        try {
            accessor.moveToFolder(SESSION, g.getId(), f.getId());
            fail("G is inside F's new parent's subtree once F is in G");
        } catch (ModelAccessException refused) {
            assertEquals(ErrorCode.CIRCULAR_FOLDER_REFERENCE, refused.getErrorCode());
        }
        dispatcher.endBatch(SESSION, true);
        assertSame(g, f.eContainer());
        assertSame(business, g.eContainer());
    }

    // ---- the layer check -----------------------------------------------------------------------------

    @Test
    public void batch_shouldRefuseAtOnce_aConceptMovedIntoAFolderAnEarlierMovePutUnderAnotherLayer()
            throws Exception {
        dispatcher.beginBatch(SESSION, "move F under the Application root, then the actor into F");
        accessor.moveToFolder(SESSION, f.getId(), app.getId());
        try {
            accessor.moveToFolder(SESSION, actor.getId(), f.getId());
            fail("F is under the Application root once moved, and the actor is a business concept");
        } catch (ModelAccessException refused) {
            assertTrue(refused.getMessage(), refused.getMessage().contains("'F'"));
        }
        dispatcher.endBatch(SESSION, true);
        assertSame(business, actor.eContainer());
    }

    @Test
    public void batch_shouldAcceptAConceptMovedIntoAFolderAnEarlierMoveKeptInItsLayer() throws Exception {
        dispatcher.beginBatch(SESSION, "move F into G, then the actor into F");
        accessor.moveToFolder(SESSION, f.getId(), g.getId());
        accessor.moveToFolder(SESSION, actor.getId(), f.getId());
        dispatcher.endBatch(SESSION, true);
        assertSame(f, actor.eContainer());
        assertSame(g, f.eContainer());
    }

    // ---- moving back, and moving twice ---------------------------------------------------------------

    @Test
    public void batch_shouldMoveAFolderBackWhereItStarted_andUndoInOrder() throws Exception {
        String before = order();
        dispatcher.beginBatch(SESSION, "move F into G, then back");
        accessor.moveToFolder(SESSION, f.getId(), g.getId());
        accessor.moveToFolder(SESSION, f.getId(), business.getId());
        dispatcher.endBatch(SESSION, true);

        assertSame(business, f.eContainer());
        stack.undo();
        assertEquals("undo puts every folder back where and in the order it was", before, order());
        stack.redo();
        assertSame(business, f.eContainer());
    }

    @Test
    public void bulk_shouldMoveAFolderBackWhereItStarted_andUndoInOrder() {
        String before = order();
        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("move-to-folder", Map.of("objectId", f.getId(), "targetFolderId", g.getId())),
                new BulkOperation("move-to-folder", Map.of("objectId", f.getId(), "targetFolderId", business.getId()))),
                "move F into G, then back", false, null);

        assertTrue(result.allSucceeded());
        assertSame(business, f.eContainer());
        stack.undo();
        assertEquals(before, order());
    }

    @Test
    public void batch_shouldRefuseASecondMoveIntoTheFolderAnEarlierMovePutItIn() throws Exception {
        dispatcher.beginBatch(SESSION, "move F into G twice");
        accessor.moveToFolder(SESSION, f.getId(), g.getId());
        try {
            accessor.moveToFolder(SESSION, f.getId(), g.getId());
            fail("F is already in G once the first move runs");
        } catch (ModelAccessException refused) {
            assertEquals(ErrorCode.ALREADY_IN_TARGET_FOLDER, refused.getErrorCode());
        }
        dispatcher.endBatch(SESSION, true);
        assertSame(g, f.eContainer());
    }

    @Test
    public void batch_shouldUndoInOrder_whenAnElementIsMovedTwice() throws Exception {
        String before = order();
        dispatcher.beginBatch(SESSION, "move the actor into F, then into G");
        accessor.moveToFolder(SESSION, actor.getId(), f.getId());
        accessor.moveToFolder(SESSION, actor.getId(), g.getId());
        dispatcher.endBatch(SESSION, true);

        assertSame(g, actor.eContainer());
        stack.undo();
        assertEquals(before, order());
        assertSame(business, actor.eContainer());
    }

    @Test
    public void batch_shouldMoveAConceptBackOutOfAFolderTheSameCallCreated_andUndoInOrder() throws Exception {
        String before = order();
        dispatcher.beginBatch(SESSION, "make N, move the actor into it, then back");
        String n = accessor.createFolder(SESSION, business.getId(), "N", null, null).entity().id();
        accessor.moveToFolder(SESSION, actor.getId(), n);
        accessor.moveToFolder(SESSION, actor.getId(), business.getId());
        dispatcher.endBatch(SESSION, true);

        assertSame(business, actor.eContainer());
        stack.undo();
        assertEquals(before, order());
    }

    @Test
    public void undo_shouldReturnTheObjectToTheFolderItLeft_whenAnEarlierMoveOfItDeclined() {
        List<PreparedMutation<?>> call = new ArrayList<>();
        FolderMutationOperations folders = accessor.folderOps;
        call.add(folders.prepareMoveToFolder(actor.getId(), f.getId(), call));
        call.add(folders.prepareMoveToFolder(actor.getId(), g.getId(), call));
        business.getFolders().remove(f); // the first move's target leaves before it runs, so it declines

        CompoundCommand all = new CompoundCommand();
        call.forEach(prepared -> all.add(toPlainCompound(prepared.command())));
        stack.execute(all);
        assertSame("the second move ran from where the actor really was", g, actor.eContainer());

        stack.undo();
        assertSame("undo puts it back in Business, the folder it left, not into F, which it never entered",
                business, actor.eContainer());
        assertSame(model, actor.getArchimateModel());
    }

    @Test
    public void shouldStillRefuseAMoveIntoTheFolderItIsIn_whenNoMoveIsQueued() {
        try {
            accessor.moveToFolder(SESSION, f.getId(), business.getId());
            fail("F is in Business");
        } catch (ModelAccessException refused) {
            assertEquals(ErrorCode.ALREADY_IN_TARGET_FOLDER, refused.getErrorCode());
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /** Every folder's sub-folders and elements, in order, from Business down. */
    private String order() {
        StringBuilder sb = new StringBuilder();
        appendOrder(business, sb);
        return sb.toString();
    }

    private void appendOrder(IFolder folder, StringBuilder sb) {
        sb.append(folder.getName()).append(" [");
        folder.getFolders().forEach(sub -> sb.append(sub.getName()).append(' '));
        folder.getElements().forEach(el -> sb.append(((com.archimatetool.model.INameable) el).getName()).append(' '));
        sb.append("] ");
        folder.getFolders().forEach(sub -> appendOrder(sub, sb));
    }

    private IFolder folder(IFolder parent, String name) {
        IFolder folder = factory.createFolder();
        folder.setId("folder-" + name);
        folder.setName(name);
        parent.getFolders().add(folder);
        return folder;
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
