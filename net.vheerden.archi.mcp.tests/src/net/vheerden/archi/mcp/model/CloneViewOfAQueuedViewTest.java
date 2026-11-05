package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.BatchSummaryDto;
import net.vheerden.archi.mcp.response.dto.ViewDto;

/**
 * Pins that {@code clone-view} accepts a source view an earlier operation of the same batch
 * created.
 *
 * <p>A view a batch creates is attached to its folder only when the batch commits, so a clone that
 * looked its source up in the model alone answered "Source view not found" for an id the batch had
 * itself handed back. The source is now resolved against what the batch has queued first, and the
 * clone copies it when it runs at commit, after the operations that create and fill it. Each fix pin
 * below asserts on the model after the batch commits, against literal values.</p>
 *
 * <p>Three consequences are pinned with it: a clone with no {@code folderId} goes in the folder the
 * source's own create puts it in, as the tool's description promises; a clone whose queued source was
 * never created declines with a reason that says so, rather than one saying the source was removed;
 * and undo, redo and rollback treat the clone and its source as one batch. The controls pin what must
 * not change: an id nothing knows, and an id the batch gave to something other than a view, both
 * still answer "Source view not found".</p>
 *
 * <p>Same headless idiom as {@code CloneViewExecuteTimeTest}: a real GEF {@link CommandStack},
 * queued compounds rebuilt into plain GEF compounds, explicit bounds, and unnamed elements so no
 * label is measured through SWT.</p>
 */
public class CloneViewOfAQueuedViewTest {

    private static final String SESSION = "clone-view-of-a-queued-view-session";

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private IArchimateModel model;
    private IArchimateDiagramModel source;
    private IFolder folderX;
    private IFolder folderY;
    private MutationDispatcher dispatcher;
    private ArchiModelAccessorImpl accessor;
    private CommandStack stack;
    private boolean approvalRequired;
    private IArchimateElement elA;
    private IArchimateElement elB;
    private IArchimateRelationship ab;

    @Before
    public void setUp() {
        model = factory.createArchimateModel();
        model.setId("model-clone-view-of-a-queued-view");
        model.setDefaults();
        folderX = folder("X");
        folderY = folder("Y");
        elA = element("el-a");
        elB = element("el-b");
        ab = relationship("rel-ab", elA, elB);
        source = factory.createArchimateDiagramModel();
        source.setId("view-source");
        source.setName("Source");
        folderY.getElements().add(source);
        IDiagramModelArchimateObject a = factory.createDiagramModelArchimateObject();
        a.setArchimateElement(elA);
        a.setBounds(400, 0, 120, 60);
        source.getChildren().add(a);

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
        dispatcher.setApprovalModeProvider(() -> approvalRequired);
        accessor = new ArchiModelAccessorImpl(mgr, dispatcher);
        dispatcher.onModelActive(model);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ---- fixed: the batch created the source ------------------------------------------------------------

    @Test
    public void cloneView_shouldCopyTheViewTheBatchCreated_whenTheBatchCreatedAndFilledItFirst() throws Exception {
        dispatcher.beginBatch(SESSION, "create, fill, clone");
        String v = createView("V", folderX);
        String voA = add(v, elA, 100, 100);
        String voB = add(v, elB, 400, 100);
        accessor.addConnectionToView(SESSION, v, ab.getId(), voA, voB, null, null, null, null, null);
        String id = cloneView(v, null);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals("nothing declined: " + summary.skippedOperations(), List.of(), skipped(summary));
        IArchimateDiagramModel clone = view(id);
        assertEquals(2, clone.getChildren().size());
        IDiagramModelArchimateObject clonedA = objectFor(clone, elA);
        IDiagramModelArchimateObject clonedB = objectFor(clone, elB);
        assertBounds(clonedA, 100, 100, 120, 60);
        assertBounds(clonedB, 400, 100, 120, 60);
        IDiagramModelArchimateConnection clonedAb = connectionFor(clonedA, ab);
        assertSame("the connection joins the cloned objects", clonedB, clonedAb.getTarget());
        assertEveryReferenceIsInTheModel(clone);
        assertEquals("the source keeps its own two", 2, view(v).getChildren().size());
    }

    @Test
    public void cloneView_shouldCreateAnEmptyCloneWithTheSourcesMetadata_whenTheBatchCreatedTheSourceEmpty()
            throws Exception {
        dispatcher.beginBatch(SESSION, "create, clone");
        String v = accessor.createView(SESSION, "V", "layered", folderX.getId(), "manhattan").entity().id();
        String id = cloneView(v, null);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals(List.of(), skipped(summary));
        IArchimateDiagramModel clone = view(id);
        assertEquals("Clone", clone.getName());
        assertEquals(0, clone.getChildren().size());
        assertEquals("layered", clone.getViewpoint());
        assertEquals(IDiagramModel.CONNECTION_ROUTER_MANHATTAN, clone.getConnectionRouterType());
    }

    @Test
    public void cloneView_shouldCopyWhatTheQueuedCloneHolds_whenTheBatchClonesAClone() throws Exception {
        source.setViewpoint("layered");
        source.setConnectionRouterType(IDiagramModel.CONNECTION_ROUTER_MANHATTAN);
        dispatcher.beginBatch(SESSION, "clone, clone the clone");
        String c1 = accessor.cloneView(SESSION, source.getId(), "C1", null).entity().id();
        String c2 = accessor.cloneView(SESSION, c1, "C2", null).entity().id();
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals(List.of(), skipped(summary));
        IArchimateDiagramModel first = view(c1);
        IArchimateDiagramModel second = view(c2);
        assertBounds(objectFor(first, elA), 400, 0, 120, 60);
        assertBounds(objectFor(second, elA), 400, 0, 120, 60);
        assertEquals(1, first.getChildren().size());
        assertEquals(1, second.getChildren().size());
        assertEquals("the source's viewpoint, through both", "layered", first.getViewpoint());
        assertEquals("layered", second.getViewpoint());
        assertEquals(IDiagramModel.CONNECTION_ROUTER_MANHATTAN, second.getConnectionRouterType());
        assertNotSame("two clones hold two objects", objectFor(first, elA), objectFor(second, elA));
        assertSame("both in the source's folder", folderY, first.eContainer());
        assertSame(folderY, second.eContainer());
    }

    @Test
    public void cloneView_shouldCopyTheBatchsObjects_whenTheCloneIsApprovedIntoTheBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "create, fill, propose a clone, approve");
        String v = createView("V", folderX);
        add(v, elA, 100, 100);
        approvalRequired = true;
        MutationResult<ViewDto> proposed = accessor.cloneView(SESSION, v, "Clone", null);
        approvalRequired = false;
        assertTrue(proposed.isProposal());
        ApprovalResult approved = dispatcher.approveProposal(SESSION, proposed.proposalContext().proposalId());
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals(List.of(), skipped(summary));
        IArchimateDiagramModel clone = view(((ViewDto) approved.entity()).id());
        assertBounds(objectFor(clone, elA), 100, 100, 120, 60);
        assertSame(folderX, clone.eContainer());
    }

    @Test
    public void cloneView_shouldNotCopyAnObjectAddedAfterIt_whenTheBatchAddsToTheSourceAfterTheClone()
            throws Exception {
        dispatcher.beginBatch(SESSION, "create, add A, clone, add B");
        String v = createView("V", folderX);
        add(v, elA, 100, 100);
        String id = cloneView(v, null);
        add(v, elB, 400, 100);
        dispatcher.endBatch(SESSION, true);

        IArchimateDiagramModel clone = view(id);
        assertEquals(1, clone.getChildren().size());
        assertBounds(objectFor(clone, elA), 100, 100, 120, 60);
        assertNull(findObject(clone, elB));
        assertNotNull("the source got B", findObject(view(v), elB));
    }

    // ---- the folder a clone with no folderId goes in --------------------------------------------------

    @Test
    public void cloneView_shouldGoInTheFolderTheSourcesCreateNames_whenNoFolderIdIsGiven() throws Exception {
        dispatcher.beginBatch(SESSION, "create in X, clone");
        String v = createView("V", folderX);
        MutationResult<ViewDto> cloned = accessor.cloneView(SESSION, v, "Clone", null);
        dispatcher.endBatch(SESSION, true);

        assertTrue(cloned.isBatched());
        assertEquals("the preview names the source's folder", "Views/X", cloned.entity().folderPath());
        assertSame(folderX, view(cloned.entity().id()).eContainer());
    }

    @Test
    public void cloneView_shouldGoInTheFolderGiven_whenAFolderIdIsGiven() throws Exception {
        dispatcher.beginBatch(SESSION, "create in X, clone into Y");
        String v = createView("V", folderX);
        MutationResult<ViewDto> cloned = accessor.cloneView(SESSION, v, "Clone", folderY.getId());
        dispatcher.endBatch(SESSION, true);

        assertEquals("Views/Y", cloned.entity().folderPath());
        assertSame(folderY, view(cloned.entity().id()).eContainer());
    }

    // ---- declined: the source was never created ----------------------------------------------------------

    @Test
    public void cloneView_shouldDeclineOnlyForItsFolder_whenTheSourceAndTheCloneShareADeletedFolder()
            throws Exception {
        IFolder z = folder("Z");
        dispatcher.beginBatch(SESSION, "delete Z, create in Z, clone");
        accessor.deleteFolder(SESSION, z.getId(), true);
        String v = createView("V", z);
        String id = cloneView(v, null);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        List<String> reasons = skipped(summary);
        assertEquals("one decline each: " + reasons, 2, reasons.size());
        assertTrue(reasons.get(0), reasons.get(0).contains("'V' was not created: the folder 'Z' it was to be "
                + "added to was removed from the model"));
        assertTrue(reasons.get(1), reasons.get(1).contains("'Clone' was not created: the folder 'Z' it was to be "
                + "added to was removed from the model"));
        assertNull(ArchimateModelUtils.getObjectByID(model, v));
        assertNull(ArchimateModelUtils.getObjectByID(model, id));
    }

    @Test
    public void cloneView_shouldSayTheSourceWasNeverCreated_whenTheSourcesCreateDeclined() throws Exception {
        IFolder z = folder("Z");
        dispatcher.beginBatch(SESSION, "delete Z, create in Z, clone into Y");
        accessor.deleteFolder(SESSION, z.getId(), true);
        String v = createView("V", z);
        String id = cloneView(v, folderY.getId());
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        List<String> reasons = skipped(summary);
        assertEquals("one decline each: " + reasons, 2, reasons.size());
        assertTrue(reasons.get(0), reasons.get(0).contains("'V' was not created: the folder 'Z'"));
        assertNeverCreatedReason(reasons.get(1), "Clone", "V");
        assertNull(ArchimateModelUtils.getObjectByID(model, v));
        assertNull(ArchimateModelUtils.getObjectByID(model, id));
        assertEquals("Y holds only the source", List.of(source), List.copyOf(folderY.getElements()));
    }

    @Test
    public void cloneView_shouldSayTheSourceWasNeverCreated_whenTheSourceIsACloneThatDeclined() throws Exception {
        dispatcher.beginBatch(SESSION, "delete the source, clone it, clone the clone");
        accessor.deleteView(SESSION, source.getId());
        String c1 = accessor.cloneView(SESSION, source.getId(), "C1", null).entity().id();
        String c2 = accessor.cloneView(SESSION, c1, "C2", null).entity().id();
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        List<String> reasons = skipped(summary);
        assertEquals("one decline each: " + reasons, 2, reasons.size());
        assertTrue(reasons.get(0), reasons.get(0).contains("'C1' was not created: the view 'Source' it was to be "
                + "copied from was removed from the model"));
        assertNeverCreatedReason(reasons.get(1), "C2", "C1");
        assertNull(ArchimateModelUtils.getObjectByID(model, c1));
        assertNull(ArchimateModelUtils.getObjectByID(model, c2));
        assertEquals(List.of(), List.copyOf(folderY.getElements()));
    }

    @Test
    public void cloneView_shouldCopyTheViewReference_whenTheBatchPutOneOnTheSourceFirst() throws Exception {
        dispatcher.beginBatch(SESSION, "create, add a view reference, clone");
        String v = createView("V", folderX);
        accessor.addViewReferenceToView(SESSION, v, source.getId(), 10, 10, 120, 55, null, null);
        String id = cloneView(v, null);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals("nothing declined", List.of(), skipped(summary));
        IArchimateDiagramModel clone = view(id);
        assertEquals("V and its clone, in V's folder", List.of(view(v), clone), List.copyOf(folderX.getElements()));
        assertEquals(1, clone.getChildren().size());
        IDiagramModelReference copied = (IDiagramModelReference) clone.getChildren().get(0);
        assertSame(source, copied.getReferencedModel());
        assertNotSame(view(v).getChildren().get(0), copied);
    }

    @Test
    public void cloneView_shouldCopyTheCreatedView_whenTheBatchForceDeletesItsFolderAfterCreatingIt()
            throws Exception {
        assertTheFolderDeleteKeepsTheCreatedView(false);
    }

    @Test
    public void cloneView_shouldCopyTheCreatedView_whenTheBatchForceDeletesAFolderAboveItAfterCreatingIt()
            throws Exception {
        assertTheFolderDeleteKeepsTheCreatedView(true);
    }

    /**
     * A view the batch creates cannot be removed later in the same batch, which is what lets the
     * decline above say it was never created: a force delete of its folder, or of a folder above it,
     * declines rather than take it.
     */
    private void assertTheFolderDeleteKeepsTheCreatedView(boolean deleteTheParent) throws Exception {
        IFolder parent = folder("P");
        IFolder z = factory.createFolder();
        z.setName("Z");
        parent.getFolders().add(z);
        dispatcher.beginBatch(SESSION, "create in Z, force-delete, clone into Y");
        String v = createView("V", z);
        add(v, elA, 100, 100);
        accessor.deleteFolder(SESSION, (deleteTheParent ? parent : z).getId(), true);
        String id = cloneView(v, folderY.getId());
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        List<String> reasons = skipped(summary);
        assertEquals("only the folder delete declines: " + reasons, 1, reasons.size());
        assertTrue(reasons.get(0), reasons.get(0).contains("Folder 'Z' was not empty when the changes were applied"));
        assertSame(z, view(v).eContainer());
        assertSame(folderY, view(id).eContainer());
        assertBounds(objectFor(view(id), elA), 100, 100, 120, 60);
    }

    @Test
    public void queuedCreatedViewFolder_shouldNameTheQueuedCreatesFolder_onlyWhileTheBatchQueuesIt() throws Exception {
        assertNull("no batch open", dispatcher.queuedCreatedViewFolder(SESSION, source.getId()));
        dispatcher.beginBatch(SESSION, "create in X");
        String v = createView("V", folderX);
        assertSame(folderX, dispatcher.queuedCreatedViewFolder(SESSION, v));
        assertNull("a view already in the model is not queued",
                dispatcher.queuedCreatedViewFolder(SESSION, source.getId()));
        assertNull(dispatcher.queuedCreatedViewFolder(SESSION, "no-such-view"));
        assertNull(dispatcher.queuedCreatedViewFolder(SESSION, null));
        assertNull("another session's batch is not this one's",
                dispatcher.queuedCreatedViewFolder("another-session", v));
        dispatcher.endBatch(SESSION, true);
        assertNull("nothing is queued once the batch commits", dispatcher.queuedCreatedViewFolder(SESSION, v));
    }

    @Test
    public void defaultFolder_shouldGoBesideTheSource_whenTheBatchCommitsBetweenTheTwoReads() {
        IArchimateDiagramModel queued = factory.createArchimateDiagramModel();
        IFolder chosen = CloneViewContentsCommand.defaultFolder(queued, () -> {
            folderX.getElements().add(queued); // the batch commits here
            return null;                       // so the queued lookup finds nothing
        }, model);
        assertSame(folderX, chosen);
    }

    // ---- undo, redo and rollback ---------------------------------------------------------------------------

    @Test
    public void cloneView_shouldUndoAndRedoTheSameSourceAndClone_whenTheBatchCreatedTheSource() throws Exception {
        dispatcher.beginBatch(SESSION, "create, fill, clone");
        String v = createView("V", folderX);
        add(v, elA, 100, 100);
        String id = cloneView(v, null);
        dispatcher.endBatch(SESSION, true);
        IArchimateDiagramModel created = view(v);
        IArchimateDiagramModel clone = view(id);
        IDiagramModelObject clonedA = objectFor(clone, elA);

        stack.undo();
        assertNull("undo removes the source", ArchimateModelUtils.getObjectByID(model, v));
        assertNull("and the clone", ArchimateModelUtils.getObjectByID(model, id));
        assertEquals(List.of(), List.copyOf(folderX.getElements()));

        stack.redo();
        assertSame(created, ArchimateModelUtils.getObjectByID(model, v));
        assertSame(clone, ArchimateModelUtils.getObjectByID(model, id));
        assertEquals(List.of(clonedA), List.copyOf(clone.getChildren()));
        assertBounds(clonedA, 100, 100, 120, 60);
    }

    @Test
    public void cloneView_shouldStoreNothing_whenTheBatchCreatingTheSourceIsRolledBack() throws Exception {
        dispatcher.beginBatch(SESSION, "create, fill, clone, rolled back");
        String v = createView("V", folderX);
        add(v, elA, 100, 100);
        String id = cloneView(v, null);
        dispatcher.endBatch(SESSION, false);

        assertNull(ArchimateModelUtils.getObjectByID(model, v));
        assertNull(ArchimateModelUtils.getObjectByID(model, id));
        assertEquals(List.of(), List.copyOf(folderX.getElements()));
        assertEquals("only the source's own object refers to A", 1, elA.getReferencingDiagramComponents().size());
    }

    // ---- controls ---------------------------------------------------------------------------------------

    @Test
    public void cloneView_shouldAnswerSourceViewNotFound_whenTheBatchGaveTheIdToAnElement() throws Exception {
        dispatcher.beginBatch(SESSION, "create an element, clone it");
        String e = accessor.createElement(SESSION, "ApplicationComponent", "E", null, null, null, null).entity().id();
        assertSourceViewNotFound(e);
        dispatcher.endBatch(SESSION, true);
    }

    @Test
    public void cloneView_shouldAnswerSourceViewNotFound_whenNothingKnowsTheId() throws Exception {
        dispatcher.beginBatch(SESSION, "clone an unknown id");
        assertSourceViewNotFound("no-such-view");
        dispatcher.endBatch(SESSION, true);
        assertSourceViewNotFound("no-such-view");
    }

    @Test
    public void cloneView_shouldGoInTheSourcesFolder_whenTheSourceIsAlreadyInTheModel() throws Exception {
        dispatcher.beginBatch(SESSION, "clone a committed view");
        MutationResult<ViewDto> cloned = accessor.cloneView(SESSION, source.getId(), "Clone", null);
        dispatcher.endBatch(SESSION, true);

        assertEquals("Views/Y", cloned.entity().folderPath());
        assertSame(folderY, view(cloned.entity().id()).eContainer());
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private String createView(String name, IFolder folder) {
        return accessor.createView(SESSION, name, null, folder.getId(), null).entity().id();
    }

    private String add(String viewId, IArchimateElement el, int x, int y) {
        return accessor.addToView(SESSION, viewId, el.getId(), x, y, 120, 60, false, null, null, null)
                .entity().viewObject().viewObjectId();
    }

    private String cloneView(String sourceViewId, String folderId) {
        return accessor.cloneView(SESSION, sourceViewId, "Clone", folderId).entity().id();
    }

    private void assertSourceViewNotFound(String id) {
        try {
            accessor.cloneView(SESSION, id, "Clone", null);
            fail("no view has the id " + id);
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.VIEW_NOT_FOUND, e.getErrorCode());
            assertEquals("Source view not found: " + id, e.getMessage());
            assertEquals("Use get-views to find valid view IDs", e.getSuggestedCorrection());
        }
    }

    private static void assertNeverCreatedReason(String reason, String clone, String source) {
        assertTrue(reason, reason.contains("'" + clone + "' was not created: the view '" + source
                + "' it was to be copied from is not in the model: the operation earlier in the same request "
                + "that was to create it was declined, so there was nothing to copy."));
        assertFalse("a view that was never created was not removed: " + reason, reason.contains("removed"));
        assertTrue("names the remedy: " + reason, reason.contains("Clone it in a separate request"));
    }

    private IArchimateDiagramModel view(String id) {
        EObject found = ArchimateModelUtils.getObjectByID(model, id);
        assertTrue("the view is in the model: " + id, found instanceof IArchimateDiagramModel);
        return (IArchimateDiagramModel) found;
    }

    private static List<String> skipped(BatchSummaryDto summary) {
        return summary.skippedOperations() == null ? List.of() : summary.skippedOperations();
    }

    private static void assertBounds(IDiagramModelObject object, int x, int y, int width, int height) {
        assertEquals("bounds", List.of(x, y, width, height), List.of(object.getBounds().getX(),
                object.getBounds().getY(), object.getBounds().getWidth(), object.getBounds().getHeight()));
    }

    private static IDiagramModelArchimateObject findObject(IDiagramModelContainer container, IArchimateElement el) {
        for (IDiagramModelObject child : container.getChildren()) {
            if (child instanceof IDiagramModelArchimateObject obj && obj.getArchimateElement() == el) {
                return obj;
            }
        }
        return null;
    }

    private static IDiagramModelArchimateObject objectFor(IDiagramModelContainer container, IArchimateElement el) {
        IDiagramModelArchimateObject found = findObject(container, el);
        assertNotNull("an object for " + el.getId(), found);
        return found;
    }

    private static IDiagramModelArchimateConnection connectionFor(IDiagramModelObject from,
            IArchimateRelationship rel) {
        for (IDiagramModelConnection conn : from.getSourceConnections()) {
            if (conn instanceof IDiagramModelArchimateConnection ac && ac.getArchimateRelationship() == rel) {
                return ac;
            }
        }
        fail("a connection for " + rel.getId());
        return null;
    }

    /** Every concept the clone draws is still in the model, so a saved copy reopens. */
    private void assertEveryReferenceIsInTheModel(IArchimateDiagramModel clone) {
        List<String> orphans = new ArrayList<>();
        for (var it = clone.eAllContents(); it.hasNext();) {
            EObject o = it.next();
            if (o instanceof IDiagramModelArchimateObject obj && obj.getArchimateElement().getArchimateModel() != model) {
                orphans.add(obj.getArchimateElement().getId());
            }
            if (o instanceof IDiagramModelArchimateConnection conn
                    && conn.getArchimateRelationship().getArchimateModel() != model) {
                orphans.add(conn.getArchimateRelationship().getId());
            }
        }
        assertEquals("concepts drawn on the clone but not in the model", List.of(), orphans);
    }

    private IFolder folder(String name) {
        IFolder f = factory.createFolder();
        f.setName(name);
        model.getFolder(FolderType.DIAGRAMS).getFolders().add(f);
        return f;
    }

    private IArchimateElement element(String id) {
        IArchimateElement el = factory.createApplicationComponent();
        el.setId(id);
        model.getFolder(FolderType.APPLICATION).getElements().add(el);
        return el;
    }

    private IArchimateRelationship relationship(String id, IArchimateConcept src, IArchimateConcept tgt) {
        IArchimateRelationship rel = factory.createAssociationRelationship();
        rel.setId(id);
        rel.setSource(src);
        rel.setTarget(tgt);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);
        return rel;
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
