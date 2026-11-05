package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
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
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.INameable;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.FolderDto;
import net.vheerden.archi.mcp.response.dto.MoveResultDto;

/**
 * Pins that a folder one {@code bulk-mutate} operation creates can be named by a later operation of
 * the same call, by back-reference, in every slot that takes a folder.
 *
 * <p>Such a reference was refused before any operation was prepared ("targets an create-folder
 * operation — only create operations can be referenced"), and even with the label accepted every
 * folder slot looked the id up in the model, where the new folder is not until the call commits. A
 * created folder has no container yet, so its path, its layer root and whether it sits under the Views
 * root are read from the call's own record of where it goes; the pins compare each previewed path with
 * the path the folder has once the commands have run.</p>
 */
public class BulkCreateFolderBackReferenceTest {

    private static final String SESSION = "bulk-create-folder-session";

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
        model.setId("model-bulk-create-folder");
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

    // ---- every slot, one call ----------------------------------------------------------------------------

    @Test
    public void bulk_shouldPutEverythingWhereTheCallSaid_whenLaterOperationsNameTheFoldersItCreates() {
        String before = tree();
        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                op("create-folder", "parentId", application.getId(), "name", "A"),
                op("create-folder", "parentId", "$0.id", "name", "B"),
                op("create-element", "type", "ApplicationComponent", "name", "InB", "folderId", "$1.id"),
                op("create-folder", "parentId", views.getId(), "name", "VA"),
                op("create-view", "name", "InVA", "folderId", "$3.id"),
                op("update-folder", "id", "$1.id", "name", "B2"),
                op("move-to-folder", "objectId", existing.getId(), "targetFolderId", "$0.id")),
                "folders", false, null);

        assertTrue(result.allSucceeded());
        assertEquals("[Application [A Existing [B2 InB]]][Views [VA InVA]]", tree());
        assertEquals("the update-folder row names the folder as renamed", "B2", result.operations().get(5).entityName());

        stack.undo();
        assertEquals("undo puts the tree back as it was before the call", before, tree());
        stack.redo();
        assertEquals("[Application [A Existing [B2 InB]]][Views [VA InVA]]", tree());
    }

    @Test
    public void bulkInABatch_shouldPutEverythingWhereTheCallSaid_whenLaterOperationsNameTheFoldersItCreates()
            throws Exception {
        dispatcher.beginBatch(SESSION, "folders in a batch");
        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                op("create-folder", "parentId", application.getId(), "name", "A"),
                op("create-element", "type", "ApplicationComponent", "name", "InA", "folderId", "$0.id")),
                "folders", false, null);
        assertTrue(result.allSucceeded());
        dispatcher.endBatch(SESSION, true);

        assertEquals("[Application Existing [A InA]][Views]", tree());
    }

    // ---- previewed paths are the committed paths -----------------------------------------------------------

    @Test
    public void preview_shouldGiveEachFolderThePathItHasOnceTheCallHasRun() {
        List<PreparedMutation<?>> call = new ArrayList<>();
        FolderMutationOperations folders = accessor.folderOps;
        PreparedMutation<FolderDto> a = add(call, folders.prepareCreateFolder(application.getId(), "A", null, null, call));
        PreparedMutation<FolderDto> b = add(call, folders.prepareCreateFolder(a.entityId(), "B", null, null, call));
        PreparedMutation<FolderDto> c = add(call, folders.prepareCreateFolder(b.entityId(), "C", null, null, call));
        PreparedMutation<FolderDto> renamed = add(call, folders.prepareUpdateFolder(b.entityId(), "B2", null, null, call));
        PreparedMutation<MoveResultDto> moved = add(call,
                folders.prepareMoveToFolder(existing.getId(), c.entityId(), call));

        assertEquals("Application/A", a.entity().path());
        assertEquals("Application/A/B", b.entity().path());
        assertEquals("Application/A/B/C", c.entity().path());
        assertEquals("Application/A/B2", renamed.entity().path());
        assertEquals("the move's target reads the rename made earlier in the call", "Application/A/B2/C",
                moved.entity().targetFolderPath());
        assertEquals("Application/A/B2/C", FolderMutationOperations.pathOf((IFolder) c.rawObject(), call));

        CompoundCommand all = new CompoundCommand();
        call.forEach(prepared -> all.add(toPlainCompound(prepared.command())));
        stack.execute(all);

        assertEquals(renamed.entity().path(), FolderOperations.buildFolderPath((IFolder) b.rawObject()));
        assertEquals(moved.entity().targetFolderPath(), FolderOperations.buildFolderPath((IFolder) c.rawObject()));
        assertSame(c.rawObject(), existing.eContainer());
    }

    @Test
    public void preview_shouldBeUnchanged_forAFolderAlreadyInTheModel() {
        IFolder sub = factory.createFolder();
        sub.setName("Sub");
        application.getFolders().add(sub);
        List<PreparedMutation<?>> call = new ArrayList<>();

        assertEquals("Application/Sub", FolderMutationOperations.pathOf(sub, call));
        assertSame(sub, FolderMutationOperations.inModel(sub, call));
        assertSame(sub, FolderMutationOperations.find(model, sub.getId(), call));
        assertEquals("Application/Sub/N",
                accessor.folderOps.prepareCreateFolder(sub.getId(), "N", null, null, call).entity().path());
    }

    // ---- the rules that hold for a folder in the model hold for one the call creates ------------------------

    @Test
    public void bulk_shouldRefuseAnElementInACreatedFolderUnderAnotherLayer() {
        ModelAccessException refusal = refused(List.of(
                op("create-folder", "parentId", model.getFolder(FolderType.BUSINESS).getId(), "name", "Biz"),
                op("create-element", "type", "ApplicationComponent", "name", "Wrong", "folderId", "$0.id")));

        assertTrue(refusal.getMessage(), refusal.getMessage().contains("belong to the Application layer"));
    }

    @Test
    public void bulk_shouldMoveAViewIntoACreatedFolderUnderTheViewsRoot_andRefuseOneOutsideIt() {
        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setName("V");
        views.getElements().add(view);

        assertTrue(accessor.executeBulk(SESSION, List.of(
                op("create-folder", "parentId", views.getId(), "name", "VA"),
                op("move-to-folder", "objectId", view.getId(), "targetFolderId", "$0.id")),
                "move a view", false, null).allSucceeded());
        assertEquals("VA", ((INameable) view.eContainer()).getName());

        ModelAccessException refusal = refused(List.of(
                op("create-folder", "parentId", application.getId(), "name", "NotViews"),
                op("move-to-folder", "objectId", view.getId(), "targetFolderId", "$0.id")));
        assertTrue(refusal.getMessage(), refusal.getMessage().contains("Views folder hierarchy"));
    }

    @Test
    public void bulk_shouldRefuseMovingAFolderIntoAFolderTheCallCreatesInsideIt() {
        IFolder sub = factory.createFolder();
        sub.setName("Sub");
        application.getFolders().add(sub);

        ModelAccessException refusal = refused(List.of(
                op("create-folder", "parentId", sub.getId(), "name", "Inner"),
                op("move-to-folder", "objectId", sub.getId(), "targetFolderId", "$0.id")));

        assertTrue(refusal.getMessage(), refusal.getMessage().contains("circular reference"));
    }

    @Test
    public void bulk_shouldRefuse_movingOrDeletingAFolderTheSameCallCreates() {
        ModelAccessException moved = refused(List.of(
                op("create-folder", "parentId", application.getId(), "name", "New"),
                op("move-to-folder", "objectId", "$0.id", "targetFolderId", application.getId())));
        assertTrue(moved.getMessage(), moved.getMessage().contains(
                "'New' is created by an earlier operation that has not been committed yet, so it cannot be moved until it has"));

        ModelAccessException deleted = refused(List.of(
                op("create-folder", "parentId", application.getId(), "name", "New"),
                op("delete-folder", "folderId", "$0.id")));
        assertTrue(deleted.getMessage(), deleted.getMessage().contains(
                "Folder 'New' is created by an earlier operation that has not been committed yet, so it cannot be deleted until it has"));
        assertEquals("nothing was created", "[Application Existing][Views]", tree());
    }

    @Test
    public void bulk_shouldStillRefuseABackReferenceToAnOperationThatCreatesNothing_inAGrammaticalSentence() {
        IFolder sub = factory.createFolder();
        sub.setName("Sub");
        application.getFolders().add(sub);
        ModelAccessException refusal = refused(List.of(
                op("move-to-folder", "objectId", existing.getId(), "targetFolderId", sub.getId()),
                op("create-element", "type", "ApplicationComponent", "name", "E", "folderId", "$0.id")));

        assertTrue(refusal.getMessage(), refusal.getMessage().contains(
                "Back-reference '$0.id' targets a move-to-folder operation — only create operations can be referenced"));
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private static <T> PreparedMutation<T> add(List<PreparedMutation<?>> call, PreparedMutation<T> prepared) {
        call.add(prepared);
        return prepared;
    }

    private ModelAccessException refused(List<BulkOperation> ops) {
        try {
            accessor.executeBulk(SESSION, ops, "refused", false, null);
        } catch (ModelAccessException refusal) {
            assertEquals(ErrorCode.BULK_VALIDATION_FAILED, refusal.getErrorCode());
            return refusal;
        }
        fail("the call must be refused");
        return null;
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

    @SuppressWarnings("unused")
    private EObject find(String id) {
        return ArchimateModelUtils.getObjectByID(model, id);
    }
}
