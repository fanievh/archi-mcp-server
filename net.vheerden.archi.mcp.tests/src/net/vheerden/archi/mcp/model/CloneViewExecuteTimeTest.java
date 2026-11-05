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
import java.util.Map;

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
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IIconic;
import com.archimatetool.model.IProperty;
import com.archimatetool.model.ISketchModelSticky;
import com.archimatetool.model.ITextAlignment;
import com.archimatetool.model.ITextPosition;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.response.dto.AddToViewResultDto;
import net.vheerden.archi.mcp.response.dto.BatchSummaryDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;
import net.vheerden.archi.mcp.response.dto.ViewDto;

/**
 * Pins that {@code clone-view} copies the source view as it is when the clone is made, not as it was
 * when the call was prepared.
 *
 * <p>A batch prepares every call before any of them runs. A clone built at prepare therefore copied
 * the source as it stood before the batch: a resize, a bendpoint edit, an added or removed object, a
 * styling or metadata change queued ahead of it was missing from the copy, and a delete queued ahead
 * of it left the clone holding a diagram object or connection for a concept no longer in the model,
 * which a saved model then failed to reopen with. Each fix pin below asserts on the model after the
 * batch commits, against literal values, and each stored the stale copy before the copy moved into
 * {@link CloneViewContentsCommand}.</p>
 *
 * <p>The controls pin what must not change: a clone outside a batch, a batch with nothing ahead of
 * the clone, a rolled-back batch, an approval rebuilt after a human edit, and the refusal of a view
 * holding an object a clone cannot copy. The batch's later operations see the clone empty until it
 * commits; that is pinned here too, so a change to it is seen.</p>
 *
 * <p>Same headless idiom as {@code AddConnectionAbsoluteBendpointsExecuteTimeTest}: a real GEF
 * {@link CommandStack}, queued compounds rebuilt into plain GEF compounds, explicit bounds, and
 * unnamed elements so no label is measured through SWT.</p>
 */
public class CloneViewExecuteTimeTest {

    private static final String SESSION = "clone-view-execute-time-session";

    /** The self-connection's loop as stored on the source, sized for a 120x60 object. */
    private static final List<BendpointDto> LOOP_120_BY_60 = List.of(new BendpointDto(0, 60, 0, 60),
            new BendpointDto(100, 60, 100, 60), new BendpointDto(100, 0, 100, 0));

    /** Where auto-placement puts an object on a view holding nothing, which the clone is until commit. */
    private static final int PLACED_ON_AN_EMPTY_VIEW_X = 50;
    private static final int PLACED_ON_AN_EMPTY_VIEW_Y = 50;

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private MutationDispatcher dispatcher;
    private ArchiModelAccessorImpl accessor;
    private CommandStack stack;
    private boolean approvalRequired;
    private IArchimateElement elA;
    private IArchimateElement elB;
    private IArchimateElement elC;
    private IDiagramModelArchimateObject a;
    private IDiagramModelArchimateObject b;
    private IArchimateRelationship ab;
    private IArchimateRelationship aa;
    private IDiagramModelArchimateConnection connectionAb;

    @Before
    public void setUp() {
        model = factory.createArchimateModel();
        model.setId("model-clone-view-execute-time");
        model.setDefaults();
        view = factory.createArchimateDiagramModel();
        view.setId("view-source");
        view.setName("Source");
        view.setConnectionRouterType(IDiagramModel.CONNECTION_ROUTER_BENDPOINT);
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        elA = element("el-a");
        elB = element("el-b");
        elC = element("el-c");
        a = object(view, "vo-a", elA, 400, 0, 120, 60);
        b = object(view, "vo-b", elB, 700, 200, 120, 60);
        ab = relationship("rel-ab", elA, elB);
        aa = relationship("rel-aa", elA, elA);
        connectionAb = connect(ab, a, b, List.of());
        connect(aa, a, a, LOOP_120_BY_60);

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

    // ---- fixed: the batch changed the source before the clone ---------------------------------------

    @Test
    public void cloneView_shouldCopyTheBoundsTheBatchGaveTheObject_whenTheBatchResizedItFirst() throws Exception {
        dispatcher.beginBatch(SESSION, "resize then clone");
        resize("vo-a", 300, 200);
        String id = cloneView();
        dispatcher.endBatch(SESSION, true);
        IDiagramModelArchimateObject clonedA = objectFor(clone(id), elA);
        assertBounds(clonedA, 400, 0, 300, 200);
        // A resize leaves the source's stored loop as it was, so this holds whichever moment the
        // bendpoints are read at; the bendpoint edit below is what tells the two apart.
        assertEquals("the self-connection's stored loop is the source's at commit", LOOP_120_BY_60,
                ConnectionResponseBuilder.collectBendpoints(connectionFor(clonedA, aa)));
    }

    @Test
    public void cloneView_shouldCopyTheEditedBendpoints_whenTheBatchEditedAConnectionFirst() throws Exception {
        dispatcher.beginBatch(SESSION, "edit bendpoints then clone");
        accessor.updateViewConnection(SESSION, connectionAb.getId(), List.of(new BendpointDto(10, 20, -30, -40)),
                null, null, null, null);
        String id = cloneView();
        dispatcher.endBatch(SESSION, true);
        assertEquals(List.of(new BendpointDto(10, 20, -30, -40)),
                ConnectionResponseBuilder.collectBendpoints(connectionFor(objectFor(clone(id), elA), ab)));
    }

    @Test
    public void cloneView_shouldCopyTheAddedObject_whenTheBatchAddedItFirst() throws Exception {
        dispatcher.beginBatch(SESSION, "add then clone");
        accessor.addToView(SESSION, view.getId(), elC.getId(), 100, 300, 120, 60, false, null, null, null);
        String id = cloneView();
        dispatcher.endBatch(SESSION, true);
        IArchimateDiagramModel clone = clone(id);
        assertEquals(3, clone.getChildren().size());
        assertBounds(objectFor(clone, elC), 100, 300, 120, 60);
    }

    @Test
    public void cloneView_shouldNotCopyTheRemovedObject_whenTheBatchRemovedItFirst() throws Exception {
        dispatcher.beginBatch(SESSION, "remove then clone");
        accessor.removeFromView(SESSION, view.getId(), "vo-b");
        String id = cloneView();
        dispatcher.endBatch(SESSION, true);
        IArchimateDiagramModel clone = clone(id);
        assertEquals("only A is left on the source", 1, clone.getChildren().size());
        assertNull(findObject(clone, elB));
        assertNull("the removal took the connection to B with it",
                findConnection(objectFor(clone, elA), ab));
        assertNotNull(connectionFor(objectFor(clone, elA), aa));
    }

    @Test
    public void cloneView_shouldHoldNothingForTheDeletedElement_whenTheBatchDeletedItFirst() throws Exception {
        dispatcher.beginBatch(SESSION, "delete an element then clone");
        accessor.deleteElement(SESSION, elB.getId());
        String id = cloneView();
        dispatcher.endBatch(SESSION, true);
        IArchimateDiagramModel clone = clone(id);
        assertNull("no diagram object for an element no longer in the model", findObject(clone, elB));
        assertNull("no connection for the relationship deleted with it", findConnection(objectFor(clone, elA), ab));
        assertEveryReferenceIsInTheModel(clone);
    }

    @Test
    public void cloneView_shouldHoldNothingForTheDeletedRelationship_whenTheBatchDeletedItFirst() throws Exception {
        dispatcher.beginBatch(SESSION, "delete a relationship then clone");
        accessor.deleteRelationship(SESSION, ab.getId());
        String id = cloneView();
        dispatcher.endBatch(SESSION, true);
        IArchimateDiagramModel clone = clone(id);
        assertNull(findConnection(objectFor(clone, elA), ab));
        assertNotNull("B itself stays", findObject(clone, elB));
        assertEveryReferenceIsInTheModel(clone);
    }

    @Test
    public void cloneView_shouldCopyTheNewStyling_whenTheBatchRestyledTheObjectFirst() throws Exception {
        dispatcher.beginBatch(SESSION, "restyle then clone");
        accessor.updateViewObject(SESSION, "vo-a", null, null, null, null, null,
                new StylingParams("#ff0000", null, null, null, null, null, "left", null, null, null, null,
                        null, null, null, null, null, null),
                null, null, null, null, null, null);
        String id = cloneView();
        dispatcher.endBatch(SESSION, true);
        IDiagramModelArchimateObject clonedA = objectFor(clone(id), elA);
        assertEquals("#ff0000", clonedA.getFillColor());
        assertEquals(ITextAlignment.TEXT_ALIGNMENT_LEFT, clonedA.getTextAlignment());
    }

    @Test
    public void cloneView_shouldCopyTheNewMetadata_whenTheBatchUpdatedTheViewFirst() throws Exception {
        dispatcher.beginBatch(SESSION, "update the view then clone");
        accessor.updateView(SESSION, view.getId(), null, "layered", "new doc", Map.of("k", "v"), "manhattan");
        String id = cloneView();
        dispatcher.endBatch(SESSION, true);
        IArchimateDiagramModel clone = clone(id);
        assertEquals("layered", clone.getViewpoint());
        assertEquals("new doc", clone.getDocumentation());
        assertEquals(IDiagramModel.CONNECTION_ROUTER_MANHATTAN, clone.getConnectionRouterType());
        assertEquals("k=v;", properties(clone));
    }

    @Test
    public void cloneView_shouldCopyTheBatchsChanges_whenAnApprovalIsApprovedIntoTheBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "propose a clone, resize, approve");
        approvalRequired = true;
        MutationResult<ViewDto> proposed = accessor.cloneView(SESSION, view.getId(), "Clone", null);
        approvalRequired = false;
        assertTrue(proposed.isProposal());
        resize("vo-a", 300, 200);
        ApprovalResult approved = dispatcher.approveProposal(SESSION, proposed.proposalContext().proposalId());
        dispatcher.endBatch(SESSION, true);
        assertBounds(objectFor(clone(((ViewDto) approved.entity()).id()), elA), 400, 0, 300, 200);
    }

    // ---- declined: nothing left to copy, or something a clone cannot copy ------------------------------

    @Test
    public void cloneView_shouldDeclineOnce_whenTheBatchDeletedTheSourceFirst() throws Exception {
        dispatcher.beginBatch(SESSION, "delete the source then clone");
        accessor.deleteView(SESSION, view.getId());
        String id = cloneView();
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);
        assertNull("no view was created", ArchimateModelUtils.getObjectByID(model, id));
        assertEquals("declined exactly once: " + summary.skippedOperations(), 1, skipped(summary).size());
        String reason = skipped(summary).get(0);
        assertTrue(reason, reason.contains("'Clone' was not created: the view 'Source' it was to be copied "
                + "from was removed from the model"));
        assertEquals("only the source's own folder entries are left", 0,
                model.getFolder(FolderType.DIAGRAMS).getElements().size());
    }

    @Test
    public void cloneView_shouldCopyTheViewReference_whenTheBatchAddedItFirst() throws Exception {
        IArchimateDiagramModel other = factory.createArchimateDiagramModel();
        other.setId("view-other");
        other.setName("Other");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(other);
        dispatcher.beginBatch(SESSION, "add a view reference then clone");
        accessor.addViewReferenceToView(SESSION, view.getId(), other.getId(), 10, 400, 120, 55, null, null);
        String id = cloneView();
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);
        assertTrue("nothing declined: " + summary.skippedOperations(), skipped(summary).isEmpty());
        IArchimateDiagramModel clone = clone(id);
        assertEquals("A, B and the reference", 3, clone.getChildren().size());
        IDiagramModelReference copied = (IDiagramModelReference) clone.getChildren().get(2);
        IDiagramModelReference original = (IDiagramModelReference) view.getChildren().get(2);
        assertNotSame(original, copied);
        assertFalse(original.getId().equals(copied.getId()));
        assertSame(other, copied.getReferencedModel());
        assertBounds(copied, 10, 400, 120, 55);
    }

    // ---- undo and redo ---------------------------------------------------------------------------------

    @Test
    public void cloneView_shouldUndoAndRedoTheSameObjects_whenTheBatchResizedTheSourceFirst() throws Exception {
        dispatcher.beginBatch(SESSION, "resize then clone");
        resize("vo-a", 300, 200);
        String id = cloneView();
        dispatcher.endBatch(SESSION, true);
        IArchimateDiagramModel clone = clone(id);
        IDiagramModelArchimateObject clonedA = objectFor(clone, elA);
        IDiagramModelArchimateConnection clonedLoop = connectionFor(clonedA, aa);
        IDiagramModelArchimateConnection clonedAb = connectionFor(clonedA, ab);
        IDiagramModelArchimateObject clonedB = objectFor(clone, elB);

        stack.undo();
        assertNull("undo removes the clone", ArchimateModelUtils.getObjectByID(model, id));
        assertBounds(a, 400, 0, 120, 60);

        stack.redo();
        assertSame("redo re-adds the same view", clone, ArchimateModelUtils.getObjectByID(model, id));
        assertSame("the same cloned object", clonedA, objectFor(clone, elA));
        assertSame(clonedB, objectFor(clone, elB));
        assertSame("the same loop", clonedLoop, connectionFor(clonedA, aa));
        assertSame(clonedAb, connectionFor(clonedA, ab));
        assertSame(clonedB, clonedAb.getTarget());
        assertEquals(2, clone.getChildren().size());
        assertEquals(2, clonedA.getSourceConnections().size());
        assertBounds(clonedA, 400, 0, 300, 200);
        assertEquals(LOOP_120_BY_60, ConnectionResponseBuilder.collectBendpoints(clonedLoop));
    }

    @Test
    public void cloneView_shouldUndoAndRedoTheCopiedMetadata_whenTheBatchUpdatedTheViewFirst() throws Exception {
        dispatcher.beginBatch(SESSION, "update the view then clone");
        accessor.updateView(SESSION, view.getId(), null, "layered", "new doc", Map.of("k", "v"), "manhattan");
        String id = cloneView();
        dispatcher.endBatch(SESSION, true);
        IArchimateDiagramModel clone = clone(id);
        IProperty copied = clone.getProperties().get(0);

        stack.undo();
        assertNull(ArchimateModelUtils.getObjectByID(model, id));
        assertEquals("", view.getViewpoint());

        stack.redo();
        assertSame(clone, ArchimateModelUtils.getObjectByID(model, id));
        assertEquals("layered", clone.getViewpoint());
        assertEquals("new doc", clone.getDocumentation());
        assertEquals(IDiagramModel.CONNECTION_ROUTER_MANHATTAN, clone.getConnectionRouterType());
        assertEquals(1, clone.getProperties().size());
        assertSame("the same property", copied, clone.getProperties().get(0));
    }

    @Test
    public void cloneView_shouldUndoAndRedoToNoClone_whenTheBatchDeletedTheSourceFirst() throws Exception {
        dispatcher.beginBatch(SESSION, "delete the source then clone");
        accessor.deleteView(SESSION, view.getId());
        String id = cloneView();
        dispatcher.endBatch(SESSION, true);

        stack.undo();
        assertNull("undo creates no clone", ArchimateModelUtils.getObjectByID(model, id));
        assertEquals("the source is back, alone", List.of(view),
                List.copyOf(model.getFolder(FolderType.DIAGRAMS).getElements()));

        stack.redo();
        assertNull("redo creates no clone", ArchimateModelUtils.getObjectByID(model, id));
        assertEquals(0, model.getFolder(FolderType.DIAGRAMS).getElements().size());
    }

    // ---- what a later operation in the same batch sees ---------------------------------------------

    @Test
    public void addToView_shouldConnectNothingOnTheClone_whenTheBatchClonedTheViewJustBefore() throws Exception {
        relationship("rel-ac", elA, elC);
        dispatcher.beginBatch(SESSION, "clone then add with autoConnect");
        String id = cloneView();
        MutationResult<AddToViewResultDto> added = accessor.addToView(SESSION, id, elC.getId(), 100, 300, 120,
                60, true, null, null, null);
        dispatcher.endBatch(SESSION, true);
        assertTrue("the clone is empty until the batch commits: " + added.entity().autoConnections(),
                added.entity().autoConnections() == null || added.entity().autoConnections().isEmpty());
        IArchimateDiagramModel clone = clone(id);
        assertEquals("A and B copied, C added after them", 3, clone.getChildren().size());
        assertBounds(objectFor(clone, elC), 100, 300, 120, 60);
        assertTrue(objectFor(clone, elC).getTargetConnections().isEmpty());
    }

    @Test
    public void addToView_shouldPlaceOnTheEmptyClone_whenTheBatchClonedTheViewJustBefore() throws Exception {
        dispatcher.beginBatch(SESSION, "clone then add with no position");
        String id = cloneView();
        MutationResult<AddToViewResultDto> added = accessor.addToView(SESSION, id, elC.getId(), null, null, null,
                null, false, null, null, null);
        dispatcher.endBatch(SESSION, true);
        IDiagramModelArchimateObject clonedC = objectFor(clone(id), elC);
        assertEquals(added.entity().viewObject().x(), clonedC.getBounds().getX());
        assertEquals(added.entity().viewObject().y(), clonedC.getBounds().getY());
        assertBounds(clonedC, PLACED_ON_AN_EMPTY_VIEW_X, PLACED_ON_AN_EMPTY_VIEW_Y, 120, 55);
    }

    // ---- controls ----------------------------------------------------------------------------------------

    @Test
    public void cloneView_shouldCopyEveryAttribute_whenNoBatchIsOpen() {
        IDiagramModelGroup group = factory.createDiagramModelGroup();
        group.setName("G");
        group.setDocumentation("group doc");
        group.setBounds(10, 10, 300, 200);
        group.setFillColor("#00ff00");
        group.setAlpha(200);
        group.setLineColor("#0000ff");
        group.setLineWidth(2);
        group.setFont("1|Arial|10.0|1|COCOA|1|Arial");
        group.setFontColor("#123456");
        group.setTextAlignment(ITextAlignment.TEXT_ALIGNMENT_RIGHT);
        ((ITextPosition) group).setTextPosition(ITextPosition.TEXT_POSITION_BOTTOM);
        group.setImagePath("images/logo.png");
        group.setImagePosition(IIconic.ICON_POSITION_BOTTOM_LEFT);
        group.setIconVisibleState(IDiagramModelObject.ICON_VISIBLE_NEVER);
        view.getChildren().add(group);
        IDiagramModelNote note = factory.createDiagramModelNote();
        note.setContent("note text");
        note.setBounds(20, 30, 150, 60);
        group.getChildren().add(note);
        IDiagramModelArchimateObject nested = object(group, "vo-nested", elC, 20, 100, 120, 60);
        nested.setType(1);
        IDiagramModelConnection noteLink = factory.createDiagramModelConnection();
        noteLink.connect(note, nested);
        connectionAb.setLineColor("#ff00ff");
        connectionAb.setLineWidth(3);
        connectionAb.setTextPosition(IDiagramModelConnection.CONNECTION_TEXT_POSITION_SOURCE);
        connectionAb.setNameVisible(false);
        connectionAb.setFont("1|Arial|9.0|0|COCOA|1|Arial");
        connectionAb.setFontColor("#654321");
        ConnectionResponseBuilder.applyBendpointsToConnection(connectionAb, List.of(new BendpointDto(5, 6, 7, 8)));
        view.setViewpoint("layered");
        view.setDocumentation("view doc");
        IProperty p = factory.createProperty();
        p.setKey("k");
        p.setValue("v");
        view.getProperties().add(p);

        MutationResult<ViewDto> result = accessor.cloneView(SESSION, view.getId(), "Clone", null);
        assertFalse(result.isBatched());
        ViewDto dto = result.entity();
        assertEquals("layered", dto.viewpointType());
        assertEquals("view doc", dto.documentation());
        IArchimateDiagramModel clone = clone(dto.id());

        assertEquals("layered", clone.getViewpoint());
        assertEquals("view doc", clone.getDocumentation());
        assertEquals(IDiagramModel.CONNECTION_ROUTER_BENDPOINT, clone.getConnectionRouterType());
        assertEquals("k=v;", properties(clone));
        assertEquals(3, clone.getChildren().size());
        IDiagramModelArchimateObject clonedA = objectFor(clone, elA);
        assertBounds(clonedA, 400, 0, 120, 60);
        IDiagramModelGroup clonedGroup = (IDiagramModelGroup) clone.getChildren().get(2);
        assertEquals("G", clonedGroup.getName());
        assertEquals("group doc", clonedGroup.getDocumentation());
        assertBounds(clonedGroup, 10, 10, 300, 200);
        assertEquals("#00ff00", clonedGroup.getFillColor());
        assertEquals(200, clonedGroup.getAlpha());
        assertEquals("#0000ff", clonedGroup.getLineColor());
        assertEquals(2, clonedGroup.getLineWidth());
        assertEquals("1|Arial|10.0|1|COCOA|1|Arial", clonedGroup.getFont());
        assertEquals("#123456", clonedGroup.getFontColor());
        assertEquals(ITextAlignment.TEXT_ALIGNMENT_RIGHT, clonedGroup.getTextAlignment());
        assertEquals(ITextPosition.TEXT_POSITION_BOTTOM, ((ITextPosition) clonedGroup).getTextPosition());
        assertEquals("images/logo.png", clonedGroup.getImagePath());
        assertEquals(IIconic.ICON_POSITION_BOTTOM_LEFT, clonedGroup.getImagePosition());
        assertEquals(IDiagramModelObject.ICON_VISIBLE_NEVER, clonedGroup.getIconVisibleState());
        IDiagramModelNote clonedNote = (IDiagramModelNote) clonedGroup.getChildren().get(0);
        assertEquals("note text", clonedNote.getContent());
        assertBounds(clonedNote, 20, 30, 150, 60);
        IDiagramModelArchimateObject clonedNested = (IDiagramModelArchimateObject) clonedGroup.getChildren().get(1);
        assertSame(elC, clonedNested.getArchimateElement());
        assertEquals(1, clonedNested.getType());
        assertBounds(clonedNested, 20, 100, 120, 60);
        IDiagramModelConnection clonedNoteLink = clonedNote.getSourceConnections().get(0);
        assertSame(clonedNested, clonedNoteLink.getTarget());
        IDiagramModelArchimateConnection clonedAb = connectionFor(clonedA, ab);
        assertSame(objectFor(clone, elB), clonedAb.getTarget());
        assertEquals("#ff00ff", clonedAb.getLineColor());
        assertEquals(3, clonedAb.getLineWidth());
        assertEquals(IDiagramModelConnection.CONNECTION_TEXT_POSITION_SOURCE, clonedAb.getTextPosition());
        assertFalse(clonedAb.isNameVisible());
        assertEquals("1|Arial|9.0|0|COCOA|1|Arial", clonedAb.getFont());
        assertEquals("#654321", clonedAb.getFontColor());
        assertEquals(List.of(new BendpointDto(5, 6, 7, 8)), ConnectionResponseBuilder.collectBendpoints(clonedAb));
        assertEquals(LOOP_120_BY_60, ConnectionResponseBuilder.collectBendpoints(connectionFor(clonedA, aa)));
        assertBounds(a, 400, 0, 120, 60);
        assertEquals("the source keeps its own connections", 2, a.getSourceConnections().size());
    }

    @Test
    public void cloneView_shouldCopyTheSource_whenTheBatchHasNothingAheadOfIt() throws Exception {
        dispatcher.beginBatch(SESSION, "clone only");
        String id = cloneView();
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);
        assertTrue(skipped(summary).isEmpty());
        IArchimateDiagramModel clone = clone(id);
        assertBounds(objectFor(clone, elA), 400, 0, 120, 60);
        assertBounds(objectFor(clone, elB), 700, 200, 120, 60);
        assertEquals(2, objectFor(clone, elA).getSourceConnections().size());
    }

    @Test
    public void cloneView_shouldStoreNothing_whenTheBatchIsRolledBack() throws Exception {
        dispatcher.beginBatch(SESSION, "resize then clone, rolled back");
        resize("vo-a", 300, 200);
        String id = cloneView();
        dispatcher.endBatch(SESSION, false);
        assertNull(ArchimateModelUtils.getObjectByID(model, id));
        assertBounds(a, 400, 0, 120, 60);
        assertEquals("only the source's own object refers to A", 1, elA.getReferencingDiagramComponents().size());
        assertEquals("only the source is in the folder", 1, model.getFolder(FolderType.DIAGRAMS).getElements().size());
    }

    @Test
    public void cloneView_shouldCopyTheHumansResize_whenAnApprovalIsApprovedAfterIt() throws Exception {
        approvalRequired = true;
        MutationResult<ViewDto> proposed = accessor.cloneView(SESSION, view.getId(), "Clone", null);
        approvalRequired = false;
        // not agent-authored: the stack sees a human resize
        stack.execute(new Command("resize") {
            @Override
            public void execute() {
                a.setBounds(400, 0, 300, 200);
            }
            @Override
            public void undo() {
                a.setBounds(400, 0, 120, 60);
            }
        });
        ApprovalResult approved = dispatcher.approveProposal(SESSION, proposed.proposalContext().proposalId());
        assertBounds(objectFor(clone(((ViewDto) approved.entity()).id()), elA), 400, 0, 300, 200);
    }

    @Test
    public void cloneView_shouldRefuseBeforeQueueing_whenTheSourceHoldsAnObjectAViewCloneCannotCopy() {
        ISketchModelSticky sticky = factory.createSketchModelSticky();
        sticky.setId("sticky-1");
        sticky.setBounds(0, 0, 50, 50);
        view.getChildren().add(sticky);
        try {
            accessor.cloneView(SESSION, view.getId(), "Clone", null);
            fail("a sketch sticky cannot be cloned");
        } catch (ModelAccessException e) {
            assertEquals("Cannot clone the view 'Source': it holds a view object of a type a clone cannot copy, "
                    + "SketchModelSticky 'sticky-1'.", e.getMessage());
        }
        assertEquals(1, model.getFolder(FolderType.DIAGRAMS).getElements().size());
    }

    // ---- whether the copy vouches it changed nothing ---------------------------------------------------

    @Test
    public void copy_shouldVouchWithNoReason_whenItsCloneWasNeverAttached() {
        CloneViewContentsCommand copy = new CloneViewContentsCommand(view, newClone("clone-detached"), model);

        copy.execute();

        assertTrue(copy.changedNothing());
        assertNull("the create gives the one reason", copy.getSkipReason());
    }

    @Test
    public void copy_shouldNotVouch_whenItCopied() {
        IArchimateDiagramModel clone = attached(newClone("clone-copied"));
        CloneViewContentsCommand copy = new CloneViewContentsCommand(view, clone, model);

        copy.execute();

        assertNull("fixture check: it copied", copy.getSkipReason());
        assertFalse(clone.getChildren().isEmpty());
        assertFalse(copy.changedNothing());
    }

    /** The create attached the clone and the copy detached it again: the pair wrote, then reverted. */
    @Test
    public void copy_shouldNotVouch_whenItsSourceWentAfterTheCreate() {
        IArchimateDiagramModel clone = attached(newClone("clone-source-gone"));
        CloneViewContentsCommand copy = new CloneViewContentsCommand(view, clone, model);
        model.getFolder(FolderType.DIAGRAMS).getElements().remove(view);

        copy.execute();

        assertNotNull("fixture check: it declined", copy.getSkipReason());
        assertFalse(copy.changedNothing());
    }

    @Test
    public void copy_shouldNotVouch_whenItsSourceGainedAnObjectAViewCloneCannotCopy() {
        IArchimateDiagramModel clone = attached(newClone("clone-uncopyable"));
        CloneViewContentsCommand copy = new CloneViewContentsCommand(view, clone, model);
        ISketchModelSticky sticky = factory.createSketchModelSticky();
        sticky.setId("sticky-late");
        sticky.setBounds(0, 0, 50, 50);
        view.getChildren().add(sticky);

        copy.execute();

        assertNotNull("fixture check: it declined", copy.getSkipReason());
        assertFalse(copy.changedNothing());
    }

    /**
     * Built as a pair, the create is guarded by the copy's own check, so a source that went declines the
     * create before it attaches the clone and the pair vouches it wrote nothing.
     */
    @Test
    public void pair_shouldVouchWithTheCopysReason_andNeverAttachTheClone_whenItsSourceWent() {
        IFolder views = model.getFolder(FolderType.DIAGRAMS);
        IArchimateDiagramModel clone = newClone("clone-pair-gone");
        CompoundCommand pair = new CompoundCommand("pair");
        CloneViewContentsCommand.addTo(pair, view, clone, views, model);
        views.getElements().remove(view);
        List<String> seen = AllDeclinedBatchCommitTest.watch(views, "clone-pair-gone");

        pair.execute();

        assertTrue(CommitSkippableCommand.vouchesNothingChanged(pair));
        List<String> reasons = CommitSkippableCommand.collectSkipReasons(pair);
        assertEquals(1, reasons.size());
        assertTrue(reasons.get(0), reasons.get(0).startsWith("'Clone' was not created: the view 'Source' it was "
                + "to be copied from was removed from the model"));
        assertNull("the copy adds no second reason", copy(pair).getSkipReason());
        assertEquals("the clone was never attached", List.of(), seen);
    }

    @Test
    public void pair_shouldCopy_andNotVouch_whenItsSourceIsThere() {
        IFolder views = model.getFolder(FolderType.DIAGRAMS);
        IArchimateDiagramModel clone = newClone("clone-pair-copied");
        CompoundCommand pair = new CompoundCommand("pair");
        CloneViewContentsCommand.addTo(pair, view, clone, views, model);
        List<String> seen = AllDeclinedBatchCommitTest.watch(views, "clone-pair-copied");

        pair.execute();

        assertEquals("the watch sees the create attach it", List.of("ADD"), seen);
        assertSame("attached", views, clone.eContainer());
        assertEquals("holds the copy", view.getChildren().size(), clone.getChildren().size());
        assertFalse(CommitSkippableCommand.vouchesNothingChanged(pair));
        assertEquals(List.of(), CommitSkippableCommand.collectSkipReasons(pair));
    }

    /** A redo restores the clone the first run made, even if the source went off the undo stack since. */
    @Test
    public void pair_shouldRedoTheSameClone_whenTheSourceWentAfterTheUndo() {
        IFolder views = model.getFolder(FolderType.DIAGRAMS);
        IArchimateDiagramModel clone = newClone("clone-pair-redo");
        CompoundCommand pair = new CompoundCommand("pair");
        CloneViewContentsCommand.addTo(pair, view, clone, views, model);
        pair.execute();
        int copiedChildren = clone.getChildren().size();
        pair.undo();
        views.getElements().remove(view);

        pair.redo();

        assertSame("attached again", views, clone.eContainer());
        assertEquals("holds the same copy", copiedChildren, clone.getChildren().size());
    }

    /** A redo of a pair that declined declines again, even if the source came back off the undo stack since. */
    @Test
    public void pair_shouldStayDeclinedOnRedo_whenTheSourceCameBackAfterTheUndo() {
        IFolder views = model.getFolder(FolderType.DIAGRAMS);
        IArchimateDiagramModel clone = newClone("clone-pair-declined-redo");
        CompoundCommand pair = new CompoundCommand("pair");
        CloneViewContentsCommand.addTo(pair, view, clone, views, model);
        views.getElements().remove(view);
        pair.execute();
        pair.undo();
        views.getElements().add(view);

        pair.redo();

        assertNull("no empty clone appears", clone.eContainer());
        assertTrue(CommitSkippableCommand.vouchesNothingChanged(pair));
    }

        private static CloneViewContentsCommand copy(CompoundCommand pair) {
        return (CloneViewContentsCommand) pair.getCommands().get(1);
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private IArchimateDiagramModel newClone(String id) {
        IArchimateDiagramModel clone = factory.createArchimateDiagramModel();
        clone.setId(id);
        clone.setName("Clone");
        return clone;
    }

    private IArchimateDiagramModel attached(IArchimateDiagramModel clone) {
        model.getFolder(FolderType.DIAGRAMS).getElements().add(clone);
        return clone;
    }

    private String cloneView() {
        return accessor.cloneView(SESSION, view.getId(), "Clone", null).entity().id();
    }

    private IArchimateDiagramModel clone(String id) {
        EObject found = ArchimateModelUtils.getObjectByID(model, id);
        assertTrue("the clone is in the model: " + id, found instanceof IArchimateDiagramModel);
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
            if (child instanceof IDiagramModelContainer nested) {
                IDiagramModelArchimateObject found = findObject(nested, el);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static IDiagramModelArchimateObject objectFor(IDiagramModelContainer container, IArchimateElement el) {
        IDiagramModelArchimateObject found = findObject(container, el);
        assertNotNull("an object for " + el.getId(), found);
        return found;
    }

    private static IDiagramModelArchimateConnection findConnection(IDiagramModelObject source,
            IArchimateRelationship rel) {
        for (IDiagramModelConnection conn : source.getSourceConnections()) {
            if (conn instanceof IDiagramModelArchimateConnection ac && ac.getArchimateRelationship() == rel) {
                return ac;
            }
        }
        return null;
    }

    private static IDiagramModelArchimateConnection connectionFor(IDiagramModelObject source,
            IArchimateRelationship rel) {
        IDiagramModelArchimateConnection found = findConnection(source, rel);
        assertNotNull("a connection for " + rel.getId(), found);
        return found;
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

    private static String properties(IArchimateDiagramModel v) {
        StringBuilder sb = new StringBuilder();
        for (IProperty p : v.getProperties()) {
            sb.append(p.getKey()).append('=').append(p.getValue()).append(';');
        }
        return sb.toString();
    }

    private void resize(String id, int width, int height) {
        accessor.updateViewObject(SESSION, id, null, null, width, height, null, null, null, null, null, null, null,
                null);
    }

    private IDiagramModelArchimateConnection connect(IArchimateRelationship rel, IDiagramModelArchimateObject source,
            IDiagramModelArchimateObject target, List<BendpointDto> bendpoints) {
        IDiagramModelArchimateConnection conn = factory.createDiagramModelArchimateConnection();
        conn.setArchimateRelationship(rel);
        ConnectionResponseBuilder.applyBendpointsToConnection(conn, bendpoints);
        conn.connect(source, target);
        return conn;
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

    private IDiagramModelArchimateObject object(IDiagramModelContainer parent, String id,
            IArchimateElement el, int x, int y, int w, int h) {
        IDiagramModelArchimateObject obj = factory.createDiagramModelArchimateObject();
        obj.setId(id);
        obj.setArchimateElement(el);
        obj.setBounds(x, y, w, h);
        parent.getChildren().add(obj);
        return obj;
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
