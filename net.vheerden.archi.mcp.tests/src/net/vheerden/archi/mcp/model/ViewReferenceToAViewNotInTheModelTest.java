package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.util.ArchimateModelUtils;
import com.archimatetool.model.util.ArchimateResourceFactory;

import net.vheerden.archi.mcp.response.dto.BatchSummaryDto;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;

/**
 * Pins that a view reference is never committed to a view the model does not hold.
 *
 * <p>A view reference lands on one view and points at another. Its placement was guarded on the view
 * it lands on only, so when the view it points at was gone by the time it ran — removed by an earlier
 * operation in the request, or never created because its own operation declined — the reference was
 * added anyway, pointing outside the model, and the saved model failed to reopen. The reference now
 * declines at commit, with a reason that is true for both causes; a reference to a view the request
 * creates successfully still commits.</p>
 */
public class ViewReferenceToAViewNotInTheModelTest {

    private static final String SESSION = "view-reference-not-in-model-session";
    private static final String DECLINED = "was not added: that view is not in the model. It was removed after this "
            + "change was prepared (by an earlier operation in the same request, or an edit in Archi), or the "
            + "operation that was to create it did not.";

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private IArchimateModel model;
    private IArchimateDiagramModel host;
    private IArchimateDiagramModel other;
    private MutationDispatcher dispatcher;
    private ArchiModelAccessorImpl accessor;
    private CommandStack stack;

    @Before
    public void setUp() {
        model = factory.createArchimateModel();
        model.setId("model-view-reference-not-in-model");
        model.setDefaults();
        host = view("view-host", "Host");
        other = view("view-other", "Other");

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

    // ---- declined -------------------------------------------------------------------------------------------

    @Test
    public void batch_shouldNotCommitTheReference_whenTheViewItRefersToWasNeverCreated() throws Exception {
        String doomed = accessor.createFolder(SESSION, model.getFolder(FolderType.DIAGRAMS).getId(), "Doomed",
                null, null).entity().id();
        dispatcher.beginBatch(SESSION, "delete a folder, create a view in it, refer to the view");
        accessor.deleteFolder(SESSION, doomed, false);
        String created = accessor.createView(SESSION, "InDoomed", null, doomed, null).entity().id();
        accessor.addViewReferenceToView(SESSION, host.getId(), created, 0, 300, 120, 60, null, null);

        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals("no reference on the view", 0, references(host).size());
        assertDeclined(summary.skippedOperations(), "InDoomed");
        assertReopens();
    }

    @Test
    public void batch_shouldNotCommitTheReference_whenAnEarlierOperationDeletedTheViewItRefersTo() throws Exception {
        dispatcher.beginBatch(SESSION, "delete a view, then refer to it");
        accessor.deleteView(SESSION, other.getId());
        accessor.addViewReferenceToView(SESSION, host.getId(), other.getId(), 0, 300, 120, 60, null, null);

        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals(0, references(host).size());
        assertDeclined(summary.skippedOperations(), "Other");
        assertReopens();
    }

    @Test
    public void bulk_shouldNotCommitTheReference_whenTheViewItRefersToWasNeverCreated() throws Exception {
        String doomed = accessor.createFolder(SESSION, model.getFolder(FolderType.DIAGRAMS).getId(), "Doomed",
                null, null).entity().id();
        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("delete-folder", Map.of("folderId", doomed)),
                new BulkOperation("create-view", Map.of("name", "InDoomed", "folderId", doomed)),
                new BulkOperation("add-view-reference-to-view", Map.of("viewId", host.getId(),
                        "referencedViewId", "$1.id", "x", 0, "y", 300, "width", 120, "height", 60))),
                "refer to a view that will not exist", false, null);

        assertEquals(0, references(host).size());
        assertDeclined(result.skippedOperations(), "InDoomed");
        assertReopens();
    }

    @Test
    public void batch_shouldKeepTheReferenceOut_whenTheDeclinedBatchIsUndoneAndRedone() throws Exception {
        dispatcher.beginBatch(SESSION, "delete a view, then refer to it");
        accessor.deleteView(SESSION, other.getId());
        accessor.addViewReferenceToView(SESSION, host.getId(), other.getId(), 0, 300, 120, 60, null, null);
        dispatcher.endBatch(SESSION, true);

        stack.undo();
        assertSame("undo brings the deleted view back", model.getFolder(FolderType.DIAGRAMS), other.eContainer());
        assertEquals("and adds no reference", 0, references(host).size());
        stack.redo();
        assertEquals(0, references(host).size());
        assertReopens();
    }

    // ---- committed -----------------------------------------------------------------------------------------

    @Test
    public void batch_shouldCommitTheReference_whenTheViewItRefersToIsCreatedEarlierInTheBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "create a view, then refer to it");
        String created = accessor.createView(SESSION, "Created", null, null, null).entity().id();
        accessor.addViewReferenceToView(SESSION, host.getId(), created, 0, 300, 120, 60, null, null);

        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals(null, summary.skippedOperations());
        assertEquals(1, references(host).size());
        assertSame(ArchimateModelUtils.getObjectByID(model, created), references(host).get(0).getReferencedModel());
        assertReopens();
    }

    @Test
    public void immediate_shouldCommitTheReference_toAViewInTheModel() throws Exception {
        accessor.addViewReferenceToView(SESSION, host.getId(), other.getId(), 0, 300, 120, 60, null, null);

        assertEquals(1, references(host).size());
        assertSame(other, references(host).get(0).getReferencedModel());
        assertReopens();
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private static void assertDeclined(List<String> skipped, String viewName) {
        assertTrue("skipped: " + skipped, skipped != null && skipped.stream().anyMatch(line -> line.contains(
                "A reference to the view '" + viewName + "' " + DECLINED)));
    }

    /** The model saves and reopens with no unresolved reference. */
    private void assertReopens() throws Exception {
        File file = temp.newFile();
        Resource saved = ArchimateResourceFactory.createNewResource(file);
        saved.getContents().add(model);
        saved.save(null);
        Resource reloaded = ArchimateResourceFactory.createResourceSet()
                .getResource(URI.createFileURI(file.getAbsolutePath()), true);
        assertEquals("reload errors", List.of(), reloaded.getErrors());
    }

    private static List<IDiagramModelReference> references(IArchimateDiagramModel view) {
        List<IDiagramModelReference> found = new ArrayList<>();
        for (EObject child : view.getChildren()) {
            if (child instanceof IDiagramModelReference reference) {
                found.add(reference);
            }
        }
        return found;
    }

    private IArchimateDiagramModel view(String id, String name) {
        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId(id);
        view.setName(name);
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        return view;
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
