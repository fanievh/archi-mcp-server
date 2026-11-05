package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.archimatetool.editor.model.IArchiveManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelImage;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.ISketchModelSticky;
import com.archimatetool.model.util.ArchimateModelUtils;
import com.archimatetool.model.util.ArchimateResourceFactory;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.BatchSummaryDto;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.BulkOperationResult;
import net.vheerden.archi.mcp.response.dto.RemoveFromViewResultDto;

/**
 * Pins that {@code remove-from-view} takes any object or connection off a view the way Archi's own
 * Delete does, rather than answering that an id the add tools just returned is not a view object.
 *
 * <p>Archi deletes every diagram object alike: the object, every connection attached to it or to
 * anything inside it, and, recursively, every connection attached to those connections. Each fix pin
 * below asserts on the model against literal values; before the fix a view reference, an image, a
 * sketch sticky, a connection that is not an ArchiMate one and a connection whose source is another
 * connection answered {@code VIEW_OBJECT_NOT_FOUND}, and a removal that left a connection attached to
 * what it removed wrote a file that failed to reopen.</p>
 *
 * <p>Same headless idiom as {@code CloneViewReferencesAndImagesTest}: a real dispatcher on a real GEF
 * command stack, and a stand-in archive holding one image's bytes. Shapes this server cannot draw
 * (generic connections, connections on connections, a sticky) are built through EMF, as Archi's
 * editor or a script would.</p>
 */
public class RemoveFromViewReferencesAndImagesTest {

    private static final String THEN_CLONE = "then clone the view once that removal is applied; a clone "
            + "queued in the same batch as the removal is still refused.";
    private static final String SESSION = "remove-from-view-references-and-images-session";
    private static final String IMAGE_PATH = "images/logo.png";

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private IArchimateDiagramModel w;
    private MutationDispatcher dispatcher;
    private ArchiModelAccessorImpl accessor;
    private CommandStack stack;
    private IArchimateElement elA;
    private IArchimateElement elC;
    private IDiagramModelArchimateObject a;
    private IDiagramModelArchimateObject b;
    private IArchimateRelationship ab;
    private IDiagramModelArchimateConnection connectionAb;
    private boolean approval;

    @Before
    public void setUp() throws Exception {
        model = factory.createArchimateModel();
        model.setId("model-remove-from-view-references-and-images");
        model.setDefaults();
        view = factory.createArchimateDiagramModel();
        view.setId("view-source");
        view.setName("Source");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        w = factory.createArchimateDiagramModel();
        w.setId("view-w");
        w.setName("Other");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(w);
        elA = element("el-a");
        IArchimateElement elB = element("el-b");
        elC = element("el-c");
        a = object(view, "vo-a", elA, 400, 0, 120, 60);
        b = object(view, "vo-b", elB, 700, 200, 120, 60);
        ab = relationship("rel-ab", elA, elB);
        connectionAb = archimateConnection(ab, a, b);
        connectionAb.setId("conn-ab");

        byte[] png = png();
        model.setAdapter(IArchiveManager.class, Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {IArchiveManager.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getBytesFromEntry")) {
                        return IMAGE_PATH.equals(args[0]) ? png : null;
                    }
                    return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                }));

        AutoRouteArmFixture.StubEditorModelManager mgr = new AutoRouteArmFixture.StubEditorModelManager();
        mgr.setModels(List.of(model));
        stack = new CommandStack();
        model.setAdapter(CommandStack.class, stack);
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                executeEntry(stack, agentEntry(toPlainCompound(command)));
            }
            @Override
            protected void dispatchCommand(Command command) {
                executeEntry(stack, agentEntry(toPlainCompound(command)));
            }
        };
        dispatcher.setApprovalModeProvider(() -> approval);
        accessor = new ArchiModelAccessorImpl(mgr, dispatcher);
        dispatcher.onModelActive(model);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ---- fixed: a view reference and an image are removed ---------------------------------------------

    @Test
    public void removeFromView_shouldRemoveTheViewReference_whenGivenTheIdTheAddToolReturned() {
        String id = placeReference();
        int wChildren = w.getChildren().size();

        RemoveFromViewResultDto removed = accessor.removeFromView(SESSION, view.getId(), id).entity();

        assertEquals(id, removed.removedObjectId());
        assertEquals("viewReference", removed.removedObjectType());
        assertNull("nothing was attached to it", removed.cascadeRemovedConnectionIds());
        assertNull("off the view and out of the model", ArchimateModelUtils.getObjectByID(model, id));
        assertEquals(List.of(a, b), view.getChildren());
        assertSame("the referenced view is untouched", w, ArchimateModelUtils.getObjectByID(model, "view-w"));
        assertEquals(wChildren, w.getChildren().size());
    }

    @Test
    public void removeFromView_shouldRemoveTheImage_whenGivenTheIdTheAddToolReturned() {
        String id = placeImage();
        IDiagramModelImage image = (IDiagramModelImage) ArchimateModelUtils.getObjectByID(model, id);

        RemoveFromViewResultDto removed = accessor.removeFromView(SESSION, view.getId(), id).entity();

        assertEquals(id, removed.removedObjectId());
        assertEquals("image", removed.removedObjectType());
        assertNull(removed.cascadeRemovedConnectionIds());
        assertNull(ArchimateModelUtils.getObjectByID(model, id));
        assertEquals(List.of(a, b), view.getChildren());
        assertEquals("the image keeps its archive path", IMAGE_PATH, image.getImagePath());
    }

    @Test
    public void removeFromView_shouldRemoveBothAtCommit_whenABatchQueuesTheirRemoval() throws Exception {
        String reference = placeReference();
        String image = placeImage();
        dispatcher.beginBatch(SESSION, "remove a reference and an image");
        assertEquals("viewReference",
                accessor.removeFromView(SESSION, view.getId(), reference).entity().removedObjectType());
        assertEquals("image", accessor.removeFromView(SESSION, view.getId(), image).entity().removedObjectType());
        assertEquals("nothing is removed before the commit", 4, view.getChildren().size());
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertNull(summary.skippedOperations());
        assertEquals(List.of(a, b), view.getChildren());
    }

    @Test
    public void removeFromView_shouldRemoveThemFromTheirNestedParents_whenTheyAreNested() {
        IDiagramModelGroup group = factory.createDiagramModelGroup();
        group.setBounds(0, 300, 400, 300);
        view.getChildren().add(group);
        IDiagramModelReference inGroup = reference(group, w, 30, 40);
        IDiagramModelImage sibling = image(group, 200, 40);
        IDiagramModelImage inElement = image(a, 5, 6);

        assertEquals("viewReference",
                accessor.removeFromView(SESSION, view.getId(), inGroup.getId()).entity().removedObjectType());
        assertEquals("image",
                accessor.removeFromView(SESSION, view.getId(), inElement.getId()).entity().removedObjectType());

        assertEquals(List.of(sibling), group.getChildren());
        assertEquals(List.of(), a.getChildren());
        assertEquals(List.of(a, b, group), view.getChildren());
        assertEquals(List.of(0, 300, 400, 300), List.of(group.getBounds().getX(), group.getBounds().getY(),
                group.getBounds().getWidth(), group.getBounds().getHeight()));
        assertEquals(List.of(400, 0, 120, 60), List.of(a.getBounds().getX(), a.getBounds().getY(),
                a.getBounds().getWidth(), a.getBounds().getHeight()));
    }

    @Test
    public void removeFromView_shouldRemoveAnObjectOnlyAScriptCanPlace_andNameItsType() {
        ISketchModelSticky sticky = factory.createSketchModelSticky();
        sticky.setId("sticky-1");
        sticky.setBounds(0, 0, 50, 50);
        view.getChildren().add(sticky);

        RemoveFromViewResultDto removed = accessor.removeFromView(SESSION, view.getId(), "sticky-1").entity();

        assertEquals("SketchModelSticky", removed.removedObjectType());
        assertEquals(List.of(a, b), view.getChildren());
    }

    // ---- fixed: connections the server cannot draw, by id ---------------------------------------------

    @Test
    public void removeFromView_shouldRemoveAConnectionThatIsNotArchimate_whenGivenItsId() {
        IDiagramModelNote note = note(view);
        IDiagramModelReference reference = reference(view, w, 10, 400);
        IDiagramModelConnection generic = genericConnection(note, reference);

        RemoveFromViewResultDto removed = accessor.removeFromView(SESSION, view.getId(), generic.getId()).entity();

        assertEquals("connection", removed.removedObjectType());
        assertNull(removed.cascadeRemovedConnectionIds());
        assertEquals(List.of(), note.getSourceConnections());
        assertEquals(List.of(), reference.getTargetConnections());

        stack.undo();
        assertEquals(List.of(generic), note.getSourceConnections());
        assertEquals(List.of(generic), reference.getTargetConnections());
    }

    @Test
    public void removeFromView_shouldRemoveAConnectionWhoseSourceIsAConnection_inBothDirections() {
        IDiagramModelArchimateObject c = object(view, "vo-c", elC, 100, 300, 120, 60);
        IDiagramModelArchimateConnection into = archimateConnection(relationship("rel-c-ab", elC, ab), c,
                connectionAb);
        IDiagramModelArchimateConnection outOf = archimateConnection(relationship("rel-ab-c", ab, elC),
                connectionAb, c);

        RemoveFromViewResultDto removed = accessor.removeFromView(SESSION, view.getId(), outOf.getId()).entity();

        assertEquals("viewConnection", removed.removedObjectType());
        assertEquals(List.of(), connectionAb.getSourceConnections());
        assertEquals(List.of(into), c.getSourceConnections());
        assertEquals(List.of(), c.getTargetConnections());
        assertEquals("viewConnection",
                accessor.removeFromView(SESSION, view.getId(), into.getId()).entity().removedObjectType());
        assertEquals(List.of(), connectionAb.getTargetConnections());

        stack.undo();
        stack.undo();
        assertEquals(List.of(outOf), connectionAb.getSourceConnections());
        assertEquals(List.of(outOf), c.getTargetConnections());
        assertEquals(List.of(into), connectionAb.getTargetConnections());
    }

    // ---- fixed: a removal takes every connection Archi's delete takes, so the file reopens ---------------

    @Test
    public void removeFromView_shouldCascadeAGenericConnectionToTheRemovedElement_soTheFileReopens()
            throws Exception {
        IDiagramModelNote note = note(view);
        IDiagramModelConnection toA = genericConnection(note, a);

        RemoveFromViewResultDto removed = accessor.removeFromView(SESSION, view.getId(), "vo-a").entity();

        assertEquals(List.of("conn-ab", toA.getId()), removed.cascadeRemovedConnectionIds());
        assertEquals(List.of(), note.getSourceConnections());
        assertEquals(List.of(), reopenErrors());
    }

    @Test
    public void removeFromView_shouldCascadeAConnectionOnTheRemovedConnection_soTheFileReopens() throws Exception {
        IDiagramModelArchimateObject c = object(view, "vo-c", elC, 100, 300, 120, 60);
        IDiagramModelArchimateConnection into = archimateConnection(relationship("rel-c-ab", elC, ab), c,
                connectionAb);

        RemoveFromViewResultDto removed = accessor.removeFromView(SESSION, view.getId(), "conn-ab").entity();

        assertEquals(List.of(into.getId()), removed.cascadeRemovedConnectionIds());
        assertEquals(List.of(), c.getSourceConnections());
        assertEquals(List.of(), reopenErrors());
    }

    @Test
    public void removeFromView_shouldCascadeAConnectionOnACascadedConnection_whenTheElementIsRemoved()
            throws Exception {
        IDiagramModelArchimateObject c = object(view, "vo-c", elC, 100, 300, 120, 60);
        IDiagramModelArchimateConnection into = archimateConnection(relationship("rel-c-ab", elC, ab), c,
                connectionAb);

        RemoveFromViewResultDto removed = accessor.removeFromView(SESSION, view.getId(), "vo-a").entity();

        assertEquals(List.of("conn-ab", into.getId()), removed.cascadeRemovedConnectionIds());
        assertEquals(List.of(), c.getSourceConnections());
        assertEquals(List.of(), reopenErrors());
    }

    @Test
    public void removeFromView_shouldCascadeAConnectionToANestedChild_whenTheElementIsRemoved() throws Exception {
        IDiagramModelArchimateObject nested = object(a, "vo-nested", elC, 10, 10, 60, 30);
        IDiagramModelArchimateConnection intoNested = archimateConnection(relationship("rel-b-c", ab.getTarget(),
                elC), b, nested);

        RemoveFromViewResultDto removed = accessor.removeFromView(SESSION, view.getId(), "vo-a").entity();

        assertEquals(List.of("conn-ab", intoNested.getId()), removed.cascadeRemovedConnectionIds());
        assertEquals(List.of(), b.getSourceConnections());
        assertEquals(List.of(), reopenErrors());
    }

    @Test
    public void removeFromView_shouldCascadeAConnectionOnTheNotesConnection_whenTheNoteIsRemoved()
            throws Exception {
        IDiagramModelNote first = note(view);
        IDiagramModelNote second = note(view);
        IDiagramModelConnection toA = genericConnection(first, a);
        IDiagramModelConnection onIt = genericConnection(second, toA);

        RemoveFromViewResultDto removed = accessor.removeFromView(SESSION, view.getId(), first.getId()).entity();

        assertEquals("note", removed.removedObjectType());
        assertEquals(List.of(toA.getId(), onIt.getId()), removed.cascadeRemovedConnectionIds());
        assertEquals(List.of(), second.getSourceConnections());
        assertEquals(List.of(), a.getTargetConnections());
        assertEquals(List.of(), reopenErrors());
    }

    @Test
    public void removeFromView_shouldCascadeTheReferencesConnection_andReopen_whenItCarriesOne() throws Exception {
        IDiagramModelNote note = note(view);
        String id = placeReference();
        IDiagramModelReference reference = (IDiagramModelReference) ArchimateModelUtils.getObjectByID(model, id);
        IDiagramModelConnection toReference = genericConnection(note, reference);
        String image = placeImage();

        RemoveFromViewResultDto removed = accessor.removeFromView(SESSION, view.getId(), id).entity();
        accessor.removeFromView(SESSION, view.getId(), image);

        assertEquals(List.of(toReference.getId()), removed.cascadeRemovedConnectionIds());
        assertEquals(List.of(), note.getSourceConnections());
        assertEquals(List.of(), reopenErrors());
    }

    // ---- undo and redo -------------------------------------------------------------------------------

    @Test
    public void removeFromView_shouldUndoAndRedoTheSameReferenceAndImage_atTheSameIndex() {
        String referenceId = placeReference();
        String imageId = placeImage();
        IDiagramModelReference reference = (IDiagramModelReference) ArchimateModelUtils.getObjectByID(model,
                referenceId);
        IDiagramModelImage image = (IDiagramModelImage) ArchimateModelUtils.getObjectByID(model, imageId);
        IDiagramModelNote note = note(view);
        IDiagramModelConnection toImage = genericConnection(note, image);
        accessor.removeFromView(SESSION, view.getId(), referenceId);
        accessor.removeFromView(SESSION, view.getId(), imageId);
        assertEquals(List.of(a, b, note), view.getChildren());

        stack.undo();
        stack.undo();
        assertEquals(List.of(a, b, reference, image, note), view.getChildren());
        assertSame(w, reference.getReferencedModel());
        assertEquals(IMAGE_PATH, image.getImagePath());
        assertEquals(List.of(toImage), note.getSourceConnections());
        assertEquals(List.of(toImage), image.getTargetConnections());

        stack.redo();
        stack.redo();
        assertEquals(List.of(a, b, note), view.getChildren());
        assertEquals(List.of(), note.getSourceConnections());
    }

    // ---- approval cards name what they remove ----------------------------------------------------------

    @Test
    public void removeFromView_shouldNameEachKindOnTheApprovalCard_andRemoveItOnApproval() {
        String reference = placeReference();
        String image = placeImage();
        String group = accessor.addGroupToView(SESSION, view.getId(), "Layer", 0, 600, 200, 100, null, null,
                null).entity().viewObjectId();
        String note = accessor.addNoteToView(SESSION, view.getId(), "hello", null, null, 300, 600, 100, 50, null,
                null, null).entity().viewObjectId();
        approval = true;

        assertEquals("Remove view reference to 'Other' from view 'Source'", card(reference));
        assertEquals("Remove image from view 'Source'", card(image));
        assertEquals("Remove group 'Layer' from view 'Source'", card(group));
        assertEquals("Remove note from view 'Source'", card(note));
        assertEquals("unchanged", "Remove ApplicationComponent 'el-a' from view 'Source' (and 1 attached connection)",
                card("vo-a"));
        assertEquals("unchanged", "Remove connection (AssociationRelationship) from view 'Source'", card("conn-ab"));

        MutationResult<RemoveFromViewResultDto> proposed = accessor.removeFromView(SESSION, view.getId(), reference);
        dispatcher.approveProposal(SESSION, proposed.proposalContext().proposalId());
        assertNull(ArchimateModelUtils.getObjectByID(model, reference));
    }

    // ---- bulk-mutate names the type ------------------------------------------------------------------

    @Test
    public void bulkMutate_shouldReportTheRemovedTypes_whenItRemovesAReferenceAndAnImage() {
        String reference = placeReference();
        String image = placeImage();

        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(remove(reference), remove(image)),
                "remove a reference and an image", false);

        List<String> types = new ArrayList<>();
        for (BulkOperationResult op : result.operations()) {
            types.add(op.entityType());
        }
        assertEquals(List.of("DiagramModelReference", "DiagramModelImage"), types);
        assertEquals(List.of(a, b), view.getChildren());
    }

    // ---- clone-view's remedies now name this tool, and following them lets the clone through ------------

    @Test
    public void cloneView_shouldSucceed_whenTheDanglingReferenceItRefusedIsRemovedWithRemoveFromView() {
        IArchimateDiagramModel gone = factory.createArchimateDiagramModel();
        gone.setName("Gone");
        IDiagramModelReference dangling = reference(view, gone, 10, 400);
        assertEquals("Remove that view reference with remove-from-view, " + THEN_CLONE,
                cloneRefusal().getSuggestedCorrection());

        assertEquals("viewReference",
                accessor.removeFromView(SESSION, view.getId(), dangling.getId()).entity().removedObjectType());

        assertEquals("Clone", accessor.cloneView(SESSION, view.getId(), "Clone", null).entity().name());
    }

    @Test
    public void cloneView_shouldSucceed_whenTheObjectItCannotCopyIsRemovedWithRemoveFromView() {
        ISketchModelSticky sticky = factory.createSketchModelSticky();
        sticky.setBounds(0, 0, 50, 50);
        view.getChildren().add(sticky);
        assertEquals("Remove that object with remove-from-view, " + THEN_CLONE,
                cloneRefusal().getSuggestedCorrection());

        accessor.removeFromView(SESSION, view.getId(), sticky.getId());

        assertEquals("Clone", accessor.cloneView(SESSION, view.getId(), "Clone", null).entity().name());
    }

    @Test
    public void cloneView_shouldSucceed_whenTheConnectionWithAnEndOffTheViewIsRemovedFromThisView() {
        IDiagramModelArchimateObject onOther = object(w, "vo-on-other", elC, 0, 0, 120, 60);
        IDiagramModelArchimateConnection out = archimateConnection(relationship("rel-a-c", elA, elC), a, onOther);
        IDiagramModelArchimateConnection in = archimateConnection(relationship("rel-c-a", elC, elA), onOther, a);
        assertEquals("Remove that connection with remove-from-view, passing this view, " + THEN_CLONE, cloneRefusal().getSuggestedCorrection());

        accessor.removeFromView(SESSION, view.getId(), out.getId());

        assertEquals(List.of(connectionAb), a.getSourceConnections());
        assertEquals("the other view holds the incoming one, so it stays", List.of(in), a.getTargetConnections());
        assertEquals("Clone", accessor.cloneView(SESSION, view.getId(), "Clone", null).entity().name());
    }

    @Test
    public void cloneView_shouldNameRemoveFromViewWhenItDeclines_whenAnObjectItCannotCopyArrivesBeforeCommit()
            throws Exception {
        dispatcher.beginBatch(SESSION, "clone, then a script adds a sketch sticky");
        accessor.cloneView(SESSION, view.getId(), "Clone", null);
        ISketchModelSticky sticky = factory.createSketchModelSticky();
        sticky.setBounds(0, 0, 50, 50);
        view.getChildren().add(sticky);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals(1, summary.skippedOperations().size());
        assertTrue(summary.skippedOperations().get(0),
                summary.skippedOperations().get(0).endsWith("Remove it with remove-from-view, " + THEN_CLONE));
    }

    @Test
    public void cloneView_shouldStillRefuse_whenTheRemovalItNamesIsQueuedInTheSameBatch() throws Exception {
        ISketchModelSticky sticky = factory.createSketchModelSticky();
        sticky.setBounds(0, 0, 50, 50);
        view.getChildren().add(sticky);
        dispatcher.beginBatch(SESSION, "remove the sticky, then clone");
        accessor.removeFromView(SESSION, view.getId(), sticky.getId());

        ModelAccessException refused = cloneRefusal();
        dispatcher.endBatch(SESSION, true);

        assertEquals("the remedy says so", "Remove that object with remove-from-view, " + THEN_CLONE,
                refused.getSuggestedCorrection());
        assertEquals(List.of(), view.getChildren().stream().filter(ISketchModelSticky.class::isInstance).toList());
        assertEquals("Clone", accessor.cloneView(SESSION, view.getId(), "Clone", null).entity().name());
    }

    // ---- unchanged: ids that are not on this view ------------------------------------------------------

    @Test
    public void removeFromView_shouldStillAnswerNotFound_whenTheReferenceOrImageIsOnAnotherView() {
        IDiagramModelReference onOther = reference(w, view, 0, 0);
        IDiagramModelImage imageOnOther = image(w, 0, 100);

        assertNotFound(onOther.getId(), "View object not found on view: " + onOther.getId());
        assertNotFound(imageOnOther.getId(), "View object not found on view: " + imageOnOther.getId());
        assertEquals(2, w.getChildren().size());
    }

    @Test
    public void removeFromView_shouldRefuseByName_whenTheBatchPlacedTheReferenceEarlier() throws Exception {
        dispatcher.beginBatch(SESSION, "place then remove");
        String id = placeReference();
        try {
            accessor.removeFromView(SESSION, view.getId(), id);
            fail("removed " + id);
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertEquals("Cannot remove a view object this batch places: " + id + " is the view reference an "
                    + "earlier operation of this batch places", e.getMessage());
        }
        dispatcher.endBatch(SESSION, true);
    }

    @Test
    public void removeFromView_shouldTakeAConnectionTheBatchDrewEarlier_soTheFileReopens() throws Exception {
        IDiagramModelArchimateObject c = object(view, "vo-c", elC, 100, 300, 120, 60);
        IArchimateRelationship ca = relationship("rel-c-a", elC, elA);
        dispatcher.beginBatch(SESSION, "connect then remove");
        accessor.addConnectionToView(SESSION, view.getId(), ca.getId(), "vo-c", "vo-a", null, null, null, null, null);
        accessor.removeFromView(SESSION, view.getId(), "vo-a");
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertNull(summary.skippedOperations());
        assertEquals(List.of(), c.getSourceConnections());
        assertEquals(List.of(), b.getTargetConnections());
        assertEquals(List.of(), reopenErrors());

        stack.undo();
        assertEquals("undo takes back the batch's connection too", List.of(), c.getSourceConnections());
        assertEquals(List.of(), a.getTargetConnections());
        assertEquals(List.of(connectionAb), a.getSourceConnections());
        assertEquals(List.of(connectionAb), b.getTargetConnections());
    }

    @Test
    public void removeFromView_shouldNotListAConnectionAsItsOwnCascade_whenTwoConnectionsEndOnEachOther()
            throws Exception {
        IDiagramModelArchimateObject c = object(view, "vo-c", elC, 100, 300, 120, 60);
        IArchimateRelationship x = relationship("rel-x", elC, ab);
        IArchimateRelationship y = relationship("rel-y", elA, x);
        x.setTarget(y);
        IDiagramModelArchimateConnection fromC = factory.createDiagramModelArchimateConnection();
        fromC.setArchimateRelationship(x);
        IDiagramModelArchimateConnection fromA = factory.createDiagramModelArchimateConnection();
        fromA.setArchimateRelationship(y);
        fromC.connect(c, fromA);
        fromA.connect(a, fromC);

        RemoveFromViewResultDto removed = accessor.removeFromView(SESSION, view.getId(), fromC.getId()).entity();

        assertEquals(List.of(fromA.getId()), removed.cascadeRemovedConnectionIds());
        assertEquals(List.of(), c.getSourceConnections());
        assertEquals(List.of(connectionAb), a.getSourceConnections());
        assertEquals(List.of(), reopenErrors());

        stack.undo();
        assertEquals(List.of(fromC), c.getSourceConnections());
        assertEquals(List.of(connectionAb, fromA), a.getSourceConnections());
    }

    @Test
    public void removeFromView_shouldCommitAndUndoCleanly_whenOneBatchRemovesAConnectionAndOneOnIt()
            throws Exception {
        IDiagramModelArchimateObject c = object(view, "vo-c", elC, 100, 300, 120, 60);
        IDiagramModelArchimateConnection into = archimateConnection(relationship("rel-c-ab", elC, ab), c,
                connectionAb);
        dispatcher.beginBatch(SESSION, "remove a connection, then the one drawn to it");
        accessor.removeFromView(SESSION, view.getId(), "conn-ab");
        accessor.removeFromView(SESSION, view.getId(), into.getId());
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertNull(summary.skippedOperations());
        assertEquals(List.of(), a.getSourceConnections());
        assertEquals(List.of(), c.getSourceConnections());
        assertEquals(List.of(), reopenErrors());

        stack.undo();
        assertEquals(List.of(connectionAb), a.getSourceConnections());
        assertEquals(List.of(connectionAb), b.getTargetConnections());
        assertEquals(List.of(into), c.getSourceConnections());
        assertEquals("reconnected once, not twice", List.of(into), connectionAb.getTargetConnections());
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private String placeReference() {
        return accessor.addViewReferenceToView(SESSION, view.getId(), w.getId(), 10, 400, 120, 55, null, null)
                .entity().viewObjectId();
    }

    private String placeImage() {
        return accessor.addImageToView(SESSION, view.getId(), IMAGE_PATH, 200, 400, 64, 48, null, null, null, null)
                .entity().viewObjectId();
    }

    private ModelAccessException cloneRefusal() {
        try {
            accessor.cloneView(SESSION, view.getId(), "Clone", null);
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            return e;
        }
        fail("the clone was not refused");
        return null;
    }

    private String card(String id) {
        return accessor.removeFromView(SESSION, view.getId(), id).proposalContext().description();
    }

    private BulkOperation remove(String id) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("viewId", view.getId());
        params.put("viewObjectId", id);
        return new BulkOperation("remove-from-view", params, null);
    }

    private void assertNotFound(String id, String message) {
        try {
            accessor.removeFromView(SESSION, view.getId(), id);
            fail("removed " + id);
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.VIEW_OBJECT_NOT_FOUND, e.getErrorCode());
            assertEquals(message, e.getMessage());
            assertTrue(e.getSuggestedCorrection(), e.getSuggestedCorrection().startsWith("A model id"));
        }
    }

    /** Saves the model and reads it back, returning the errors the reload reports. */
    private List<?> reopenErrors() throws Exception {
        File file = temp.newFile("model.archimate");
        Resource saved = ArchimateResourceFactory.createNewResource(file);
        saved.getContents().add(model);
        saved.save(null);
        try {
            Resource reloaded = ArchimateResourceFactory.createResourceSet()
                    .getResource(URI.createFileURI(file.getAbsolutePath()), true);
            return reloaded.getErrors();
        } catch (RuntimeException e) {
            return List.of(String.valueOf(e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
        }
    }

    private IDiagramModelNote note(IDiagramModelContainer parent) {
        IDiagramModelNote note = factory.createDiagramModelNote();
        note.setBounds(0, 500, 100, 50);
        parent.getChildren().add(note);
        return note;
    }

    private IDiagramModelConnection genericConnection(IConnectable source, IConnectable target) {
        IDiagramModelConnection conn = factory.createDiagramModelConnection();
        conn.connect(source, target);
        return conn;
    }

    private IDiagramModelReference reference(IDiagramModelContainer parent, IDiagramModel to, int x, int y) {
        IDiagramModelReference reference = factory.createDiagramModelReference();
        reference.setReferencedModel(to);
        reference.setBounds(x, y, 120, 55);
        parent.getChildren().add(reference);
        return reference;
    }

    private IDiagramModelImage image(IDiagramModelContainer parent, int x, int y) {
        IDiagramModelImage image = factory.createDiagramModelImage();
        image.setImagePath(IMAGE_PATH);
        image.setBounds(x, y, 64, 48);
        parent.getChildren().add(image);
        return image;
    }

    private IDiagramModelArchimateConnection archimateConnection(IArchimateRelationship rel, IConnectable source,
            IConnectable target) {
        IDiagramModelArchimateConnection conn = factory.createDiagramModelArchimateConnection();
        conn.setArchimateRelationship(rel);
        conn.connect(source, target);
        return conn;
    }

    private static byte[] png() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png",
                bytes);
        return bytes.toByteArray();
    }

    private IArchimateElement element(String id) {
        IArchimateElement el = factory.createApplicationComponent();
        el.setId(id);
        el.setName(id);
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

    private IDiagramModelArchimateObject object(IDiagramModelContainer parent, String id, IArchimateElement el,
            int x, int y, int width, int height) {
        IDiagramModelArchimateObject obj = factory.createDiagramModelArchimateObject();
        obj.setId(id);
        obj.setArchimateElement(el);
        obj.setBounds(x, y, width, height);
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
