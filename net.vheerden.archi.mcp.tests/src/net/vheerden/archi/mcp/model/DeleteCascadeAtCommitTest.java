package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

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
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.util.ArchimateModelUtils;
import com.archimatetool.model.util.ArchimateResourceFactory;

import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.DeleteResultDto;

/**
 * Pins that deleting an element or a relationship removes what an earlier operation in the same
 * request added: a view it cloned, an object it placed, a relationship it connected.
 *
 * <p>The delete's cascade was decided at prepare, against the model before the request ran, so
 * anything an earlier queued operation would add was missed: the committed model kept a diagram
 * object or a connection of a concept no longer in it, or a relationship whose end was gone, and
 * failed to reopen. The delete now finds its cascade when it runs. Each case asserts the committed
 * model holds nothing of the deleted concept and reopens, that undo puts back membership and order,
 * and that redo removes exactly the same set. The immediate arm's counts are the control.</p>
 */
public class DeleteCascadeAtCommitTest {

    private static final String SESSION = "delete-cascade-at-commit-session";

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private IArchimateModel model;
    private IArchimateDiagramModel v;
    private IArchimateDiagramModel w;
    private IArchimateElement elA;
    private IArchimateElement elB;
    private IArchimateElement elC;
    private MutationDispatcher dispatcher;
    private ArchiModelAccessorImpl accessor;
    private CommandStack stack;

    @Before
    public void setUp() {
        model = factory.createArchimateModel();
        model.setId("model-delete-cascade-at-commit");
        model.setDefaults();
        v = view("view-v");
        w = view("view-w");
        elA = element("el-a");
        elB = element("el-b");
        elC = element("el-c");
        IDiagramModelArchimateObject a = object(v, "vo-a", elA, 0);
        IDiagramModelArchimateObject b = object(v, "vo-b", elB, 300);
        IArchimateRelationship ab = factory.createAssociationRelationship();
        ab.setId("rel-ab");
        ab.connect(elA, elB);
        model.getFolder(FolderType.RELATIONS).getElements().add(ab);
        IDiagramModelArchimateConnection drawn = factory.createDiagramModelArchimateConnection();
        drawn.setId("conn-ab");
        drawn.setArchimateRelationship(ab);
        drawn.connect(a, b);

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

    // ---- the cascade includes what the request added ----------------------------------------------------

    @Test
    public void batch_shouldRemoveTheDeletedElementFromAViewTheBatchCloned() throws Exception {
        List<String> before = order();
        dispatcher.beginBatch(SESSION, "clone a view, then delete an element it draws");
        String clone = accessor.cloneView(SESSION, v.getId(), "Clone", null).entity().id();
        accessor.deleteElement(SESSION, elB.getId());
        dispatcher.endBatch(SESSION, true);

        IArchimateDiagramModel cloned = (IArchimateDiagramModel) ArchimateModelUtils.getObjectByID(model, clone);
        assertEquals("the clone keeps A only", 1, cloned.getChildren().size());
        assertTrue("and no connection", ((IDiagramModelArchimateObject) cloned.getChildren().get(0))
                .getSourceConnections().isEmpty());
        assertNothingOf("el-b", "rel-ab");
        assertUndoRestoresOrder_andRedoRemovesTheSameSet(before);
        assertReopens();
    }

    @Test
    public void batch_shouldRemoveTheDeletedRelationshipFromAViewTheBatchCloned() throws Exception {
        List<String> before = order();
        dispatcher.beginBatch(SESSION, "clone a view, then delete a relationship it draws");
        accessor.cloneView(SESSION, v.getId(), "Clone", null);
        accessor.deleteRelationship(SESSION, "rel-ab");
        dispatcher.endBatch(SESSION, true);

        assertNothingOf("rel-ab");
        assertUndoRestoresOrder_andRedoRemovesTheSameSet(before);
        assertReopens();
    }

    @Test
    public void batch_shouldRemoveTheDeletedElementFromAViewTheBatchCreatedAndPlacedItOn() throws Exception {
        List<String> before = order();
        dispatcher.beginBatch(SESSION, "create a view, place B on it, delete B");
        String created = accessor.createView(SESSION, "New", null, null, null).entity().id();
        accessor.addToView(SESSION, created, elB.getId(), 0, 0, 120, 60, false, null, null, null);
        accessor.deleteElement(SESSION, elB.getId());
        dispatcher.endBatch(SESSION, true);

        assertEquals(0, ((IArchimateDiagramModel) ArchimateModelUtils.getObjectByID(model, created))
                .getChildren().size());
        assertNothingOf("el-b", "rel-ab");
        assertUndoRestoresOrder_andRedoRemovesTheSameSet(before);
        assertReopens();
    }

    @Test
    public void batch_shouldRemoveTheDeletedElementFromAnExistingViewTheBatchPlacedItOn() throws Exception {
        List<String> before = order();
        dispatcher.beginBatch(SESSION, "place C on W, then delete C");
        accessor.addToView(SESSION, w.getId(), elC.getId(), 0, 0, 120, 60, false, null, null, null);
        accessor.deleteElement(SESSION, elC.getId());
        dispatcher.endBatch(SESSION, true);

        assertEquals(0, w.getChildren().size());
        assertNothingOf("el-c");
        assertUndoRestoresOrder_andRedoRemovesTheSameSet(before);
        assertReopens();
    }

    @Test
    public void bulk_shouldRemoveTheDeletedElementFromAnExistingViewTheCallPlacedItOn() throws Exception {
        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("add-to-view", Map.of("viewId", w.getId(), "elementId", elC.getId(),
                        "x", 0, "y", 0, "width", 120, "height", 60)),
                new BulkOperation("delete-element", Map.of("elementId", elC.getId()))),
                "place C on W, then delete C", false, null);

        assertTrue(result.allSucceeded());
        assertEquals(0, w.getChildren().size());
        assertNothingOf("el-c");
        assertReopens();
    }

    @Test
    public void batch_shouldRemoveARelationshipTheBatchConnectedToTheDeletedElement() throws Exception {
        List<String> before = order();
        dispatcher.beginBatch(SESSION, "connect B to C, then delete B");
        // Queued directly: the headless lane cannot load the relationship rules create-relationship checks.
        IArchimateRelationship bc = factory.createAssociationRelationship();
        bc.setId("rel-bc");
        dispatcher.queueForBatch(SESSION, new CreateRelationshipCommand(bc, model.getFolder(FolderType.RELATIONS),
                elB, elC), "connect B to C");
        accessor.deleteElement(SESSION, elB.getId());
        dispatcher.endBatch(SESSION, true);

        assertNull("the relationship went with B", ArchimateModelUtils.getObjectByID(model, "rel-bc"));
        assertTrue("and is disconnected from C", elC.getTargetRelationships().isEmpty());
        assertNothingOf("el-b", "rel-ab", "rel-bc");
        assertReopens();
    }

    @Test
    public void batch_shouldRemoveTheElementsOfAForcedFolderDeleteFromAViewTheBatchPlacedThemOn()
            throws Exception {
        IFolder g = factory.createFolder();
        g.setId("folder-g");
        g.setName("G");
        model.getFolder(FolderType.APPLICATION).getFolders().add(g);
        g.getElements().add(elC);
        dispatcher.beginBatch(SESSION, "place C on W, then force-delete C's folder");
        accessor.addToView(SESSION, w.getId(), elC.getId(), 0, 0, 120, 60, false, null, null, null);
        accessor.deleteFolder(SESSION, g.getId(), true);
        dispatcher.endBatch(SESSION, true);

        assertEquals(0, w.getChildren().size());
        assertNothingOf("el-c", "folder-g");
        stack.undo();
        assertSame("undo puts C back in its folder", g, elC.eContainer());
        assertEquals("and takes W's object away with the batch", 0, w.getChildren().size());
        assertReopens();
    }

    @Test
    public void batch_shouldRemoveNothingMore_whenTheElementWentWithAFolderAnEarlierOperationDeleted()
            throws Exception {
        IFolder g = factory.createFolder();
        g.setId("folder-g");
        g.setName("G");
        model.getFolder(FolderType.APPLICATION).getFolders().add(g);
        g.getElements().add(elC);
        dispatcher.beginBatch(SESSION, "force-delete C's folder, then delete C");
        accessor.deleteFolder(SESSION, g.getId(), true);
        accessor.deleteElement(SESSION, elC.getId());
        dispatcher.endBatch(SESSION, true);

        assertNothingOf("el-c", "folder-g");
        assertEquals("the folder's own delete took C out, and the second delete removed nothing more", null,
                elC.eContainer());
        stack.undo();
        assertSame(model.getFolder(FolderType.APPLICATION), g.eContainer());
        assertSame("undo puts C back in its folder", g, elC.eContainer());
        stack.redo();
        assertNothingOf("el-c", "folder-g");
        assertReopens();
    }

    // ---- unchanged ----------------------------------------------------------------------------------------

    @Test
    public void immediate_shouldReportTheSameCounts_andRemoveTheSameSet() throws Exception {
        object(w, "vo-wb", elB, 0);
        List<String> before = order();

        DeleteResultDto deleted = accessor.deleteElement(SESSION, elB.getId()).entity();

        assertEquals(1, deleted.relationshipsRemoved());
        assertEquals(2, deleted.viewReferencesRemoved());
        assertEquals(1, deleted.viewConnectionsRemoved());
        assertNothingOf("el-b", "rel-ab", "vo-b", "vo-wb", "conn-ab");
        assertUndoRestoresOrder_andRedoRemovesTheSameSet(before);
    }

    @Test
    public void immediate_shouldReportTheSameCount_forARelationship() {
        DeleteResultDto deleted = accessor.deleteRelationship(SESSION, "rel-ab").entity();

        assertEquals(0, deleted.relationshipsRemoved());
        assertEquals(0, deleted.viewReferencesRemoved());
        assertEquals(1, deleted.viewConnectionsRemoved());
        assertNothingOf("rel-ab", "conn-ab");
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    /** No object, connection or relationship in the model draws or ends at any of {@code gone}. */
    private void assertNothingOf(String... gone) {
        List<String> left = new ArrayList<>();
        List<String> ids = List.of(gone);
        for (Iterator<EObject> it = model.eAllContents(); it.hasNext();) {
            EObject o = it.next();
            if (o instanceof IIdentifier id && ids.contains(id.getId())) {
                left.add("still in the model: " + id.getId());
            }
            if (o instanceof IDiagramModelArchimateObject obj && ids.contains(obj.getArchimateElement().getId())) {
                left.add("object " + obj.getId() + " draws " + obj.getArchimateElement().getId());
            }
            if (o instanceof IDiagramModelArchimateConnection c && ids.contains(c.getArchimateRelationship().getId())) {
                left.add("connection " + c.getId() + " draws " + c.getArchimateRelationship().getId());
            }
            if (o instanceof IArchimateRelationship r && (ids.contains(r.getSource().getId())
                    || ids.contains(r.getTarget().getId()))) {
                left.add("relationship " + r.getId() + " ends at a deleted element");
            }
        }
        assertEquals(List.of(), left);
    }

    /**
     * Undo puts back every folder's and view's members in their order; redo leaves exactly what
     * the first commit left.
     */
    private void assertUndoRestoresOrder_andRedoRemovesTheSameSet(List<String> before) {
        TreeSet<String> committed = ids();
        stack.undo();
        assertEquals("undo restores membership and order", before, order());
        stack.redo();
        assertEquals("redo removes exactly the same set", committed, ids());
    }

    /** Every folder's members and every view's children, in order. */
    private List<String> order() {
        List<String> lines = new ArrayList<>();
        for (Iterator<EObject> it = model.eAllContents(); it.hasNext();) {
            EObject o = it.next();
            if (o instanceof IFolder folder) {
                lines.add(folder.getName() + ": " + folder.getElements().stream()
                        .map(e -> ((IIdentifier) e).getId()).toList());
            } else if (o instanceof IArchimateDiagramModel view) {
                lines.add(view.getId() + ": " + view.getChildren().stream().map(IIdentifier::getId).toList());
            } else if (o instanceof IArchimateRelationship r) {
                lines.add(r.getId() + ": " + r.getSource().getId() + " -> " + r.getTarget().getId());
            } else if (o instanceof IDiagramModelArchimateObject obj) {
                lines.add(obj.getId() + " out: " + obj.getSourceConnections().stream()
                        .map(IIdentifier::getId).toList());
            }
        }
        return lines;
    }

    private TreeSet<String> ids() {
        TreeSet<String> ids = new TreeSet<>();
        for (Iterator<EObject> it = model.eAllContents(); it.hasNext();) {
            if (it.next() instanceof IIdentifier id) {
                ids.add(id.getId());
            }
        }
        return ids;
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

    private IArchimateDiagramModel view(String id) {
        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId(id);
        view.setName(id);
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        return view;
    }

    private IArchimateElement element(String id) {
        IArchimateElement element = factory.createApplicationComponent();
        element.setId(id);
        element.setName(id);
        model.getFolder(FolderType.APPLICATION).getElements().add(element);
        return element;
    }

    private IDiagramModelArchimateObject object(IArchimateDiagramModel view, String id, IArchimateConcept element,
            int x) {
        IDiagramModelArchimateObject object = factory.createDiagramModelArchimateObject();
        object.setId(id);
        object.setArchimateElement((IArchimateElement) element);
        object.setBounds(x, 0, 120, 60);
        view.getChildren().add(object);
        return object;
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
