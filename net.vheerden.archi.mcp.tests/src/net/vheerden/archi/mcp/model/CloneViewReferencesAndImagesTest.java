package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

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
import org.slf4j.Logger;

import com.archimatetool.editor.model.IArchiveManager;
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
import com.archimatetool.model.IDiagramModelImage;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IProperty;
import com.archimatetool.model.ISketchModelSticky;
import com.archimatetool.model.ITextAlignment;
import com.archimatetool.model.ITextPosition;
import com.archimatetool.model.util.ArchimateModelUtils;
import com.archimatetool.model.util.ArchimateResourceFactory;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.BatchSummaryDto;

/**
 * Pins that {@code clone-view} copies view references and images, as Archi's own Duplicate does,
 * rather than refusing a view that holds them as a server fault.
 *
 * <p>Archi duplicates a view by copying it whole, so every object on it is in the copy, and a view
 * reference to the duplicated view itself points at the duplicate. Each fix pin below asserts on the
 * model after the clone, against literal values; each answered {@code INTERNAL_ERROR} or declined at
 * commit before the copy learnt the two kinds. The same goes for a connection whose end is another
 * connection, which the copy used to drop with only a log line.</p>
 *
 * <p>What is still refused: an object a clone has no copy for (one only a script or a hand-edited
 * file can put on an ArchiMate view), and a view reference to a view that is not in the model, which
 * a saved model fails to reopen with. Both are the caller's to fix, so they are answered
 * {@code INVALID_PARAMETER} before anything is queued, or declined at commit inside a batch. A batch
 * cannot itself add such a reference to the source: the reference declines first, so the clone has
 * nothing to refuse.</p>
 *
 * <p>Same headless idiom as {@code CloneViewExecuteTimeTest}. Images need an archive to place them
 * through the accessor, which only the OSGi runtime can create, so the model is given a stand-in that
 * holds one image's bytes.</p>
 */
public class CloneViewReferencesAndImagesTest {

    private static final String THEN_CLONE = "then clone the view once that removal is applied; a clone "
            + "queued in the same batch as the removal is still refused.";
    private static final String SESSION = "clone-view-references-and-images-session";
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
    private IArchimateElement elB;
    private IArchimateElement elC;
    private IDiagramModelArchimateObject a;
    private IArchimateRelationship ab;
    private IDiagramModelArchimateConnection connectionAb;

    @Before
    public void setUp() throws Exception {
        model = factory.createArchimateModel();
        model.setId("model-clone-view-references-and-images");
        model.setDefaults();
        view = factory.createArchimateDiagramModel();
        view.setId("view-source");
        view.setName("Source");
        view.setConnectionRouterType(IDiagramModel.CONNECTION_ROUTER_BENDPOINT);
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        w = factory.createArchimateDiagramModel();
        w.setId("view-w");
        w.setName("W");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(w);
        elA = element("el-a");
        elB = element("el-b");
        elC = element("el-c");
        a = object(view, "vo-a", elA, 400, 0, 120, 60);
        IDiagramModelArchimateObject b = object(view, "vo-b", elB, 700, 200, 120, 60);
        ab = relationship("rel-ab", elA, elB);
        connectionAb = factory.createDiagramModelArchimateConnection();
        connectionAb.setId("conn-ab");
        connectionAb.setArchimateRelationship(ab);
        connectionAb.connect(a, b);

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

    // ---- fixed: references and images are copied ----------------------------------------------------

    @Test
    public void cloneView_shouldCopyTheViewReference_whenTheSourceHoldsOne() {
        accessor.addViewReferenceToView(SESSION, view.getId(), w.getId(), 10, 400, 120, 55, null, null);
        IDiagramModelReference reference = only(view, IDiagramModelReference.class);
        reference.setTextPosition(ITextPosition.TEXT_POSITION_BOTTOM);
        reference.setTextAlignment(ITextAlignment.TEXT_ALIGNMENT_RIGHT);
        reference.setIconVisibleState(IDiagramModelObject.ICON_VISIBLE_NEVER);
        reference.setFillColor("#abcdef");

        IArchimateDiagramModel clone = clone(cloneView());

        IDiagramModelReference copied = only(clone, IDiagramModelReference.class);
        assertNotSame(reference, copied);
        assertNotEquals(reference.getId(), copied.getId());
        assertBounds(copied, 10, 400, 120, 55);
        assertSame("it references the same view", w, copied.getReferencedModel());
        assertEquals(ITextPosition.TEXT_POSITION_BOTTOM, copied.getTextPosition());
        assertEquals(ITextAlignment.TEXT_ALIGNMENT_RIGHT, copied.getTextAlignment());
        assertEquals(IDiagramModelObject.ICON_VISIBLE_NEVER, copied.getIconVisibleState());
        assertEquals("#abcdef", copied.getFillColor());
        assertSame("the source keeps its own", w, reference.getReferencedModel());
        assertEveryReferenceIsInTheModel(clone);
    }

    @Test
    public void cloneView_shouldCopyTheImage_whenTheSourceHoldsOne() {
        accessor.addImageToView(SESSION, view.getId(), IMAGE_PATH, 200, 400, 64, 48, null, null, "#ff0000",
                "image doc");
        IDiagramModelImage image = only(view, IDiagramModelImage.class);
        IProperty property = factory.createProperty();
        property.setKey("k");
        property.setValue("v");
        image.getProperties().add(property);

        IArchimateDiagramModel clone = clone(cloneView());

        IDiagramModelImage copied = only(clone, IDiagramModelImage.class);
        assertNotSame(image, copied);
        assertNotEquals(image.getId(), copied.getId());
        assertBounds(copied, 200, 400, 64, 48);
        assertEquals(IMAGE_PATH, copied.getImagePath());
        assertEquals("#ff0000", copied.getBorderColor());
        assertEquals("image doc", copied.getDocumentation());
        assertEquals(1, copied.getProperties().size());
        assertNotSame(property, copied.getProperties().get(0));
        assertEquals("k", copied.getProperties().get(0).getKey());
        assertEquals("v", copied.getProperties().get(0).getValue());
    }

    @Test
    public void cloneView_shouldCopyThemAtTheirRelativeBounds_whenTheyAreNested() {
        IDiagramModelGroup group = factory.createDiagramModelGroup();
        group.setBounds(0, 300, 400, 300);
        view.getChildren().add(group);
        IDiagramModelReference inGroup = reference(group, w, 30, 40);
        IDiagramModelImage inElement = image(a, 5, 6);
        IDiagramModelReference alsoInElement = reference(a, w, 7, 8);

        IArchimateDiagramModel clone = clone(cloneView());

        IDiagramModelGroup clonedGroup = only(clone, IDiagramModelGroup.class);
        IDiagramModelReference copiedInGroup = only(clonedGroup, IDiagramModelReference.class);
        assertNotSame(inGroup, copiedInGroup);
        assertNotEquals(inGroup.getId(), copiedInGroup.getId());
        assertBounds(copiedInGroup, 30, 40, 120, 55);
        assertSame(w, copiedInGroup.getReferencedModel());
        IDiagramModelArchimateObject clonedA = objectFor(clone, elA);
        IDiagramModelImage copiedInElement = only(clonedA, IDiagramModelImage.class);
        assertNotSame(inElement, copiedInElement);
        assertNotEquals(inElement.getId(), copiedInElement.getId());
        assertBounds(copiedInElement, 5, 6, 64, 48);
        IDiagramModelReference copiedAlsoInElement = only(clonedA, IDiagramModelReference.class);
        assertNotSame(alsoInElement, copiedAlsoInElement);
        assertBounds(copiedAlsoInElement, 7, 8, 120, 55);
        assertSame(w, copiedAlsoInElement.getReferencedModel());
    }

    @Test
    public void cloneView_shouldCopyTheImage_whenTheBatchAddedItFirst() throws Exception {
        dispatcher.beginBatch(SESSION, "add an image then clone");
        accessor.addImageToView(SESSION, view.getId(), IMAGE_PATH, 200, 400, 64, 48, null, null, null, null);
        String id = cloneView();
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);
        assertEquals(List.of(), skipped(summary));
        IDiagramModelImage original = only(view, IDiagramModelImage.class);
        IDiagramModelImage copied = only(clone(id), IDiagramModelImage.class);
        assertNotSame(original, copied);
        assertNotEquals(original.getId(), copied.getId());
        assertBounds(copied, 200, 400, 64, 48);
        assertEquals(IMAGE_PATH, copied.getImagePath());
    }

    @Test
    public void cloneView_shouldCopyTheViewReference_whenItPointsToAViewTheBatchCreatedFirst() throws Exception {
        dispatcher.beginBatch(SESSION, "create a view, reference it, clone");
        String planned = accessor.createView(SESSION, "Planned", null, null, null).entity().id();
        accessor.addViewReferenceToView(SESSION, view.getId(), planned, 10, 400, 120, 55, null, null);
        String id = cloneView();
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals(List.of(), skipped(summary));
        EObject created = ArchimateModelUtils.getObjectByID(model, planned);
        assertNotNull("the referenced view was created", created);
        assertSame(created, only(clone(id), IDiagramModelReference.class).getReferencedModel());
        assertEveryReferenceIsInTheModel(clone(id));
    }

    @Test
    public void cloneView_shouldPointASelfReferenceAtTheClone_whenTheSourceReferencesItself() {
        accessor.addViewReferenceToView(SESSION, view.getId(), view.getId(), 10, 400, 120, 55, null, null);

        IArchimateDiagramModel clone = clone(cloneView());

        assertSame("as Archi's Duplicate does", clone, only(clone, IDiagramModelReference.class).getReferencedModel());
        assertSame("the source's still points at the source", view,
                only(view, IDiagramModelReference.class).getReferencedModel());
    }

    // ---- fixed: a connection whose end is a connection -------------------------------------------------

    @Test
    public void cloneView_shouldCopyConnectionsOnConnections_whenEitherEndIsAConnection() {
        IDiagramModelArchimateObject c = object(view, "vo-c", elC, 100, 300, 120, 60);
        IArchimateRelationship cToAb = relationship("rel-c-ab", elC, ab);
        IArchimateRelationship abToC = relationship("rel-ab-c", ab, elC);
        IDiagramModelArchimateConnection intoAb = archimateConnection(cToAb, c, connectionAb);
        IDiagramModelArchimateConnection outOfAb = archimateConnection(abToC, connectionAb, c);

        IArchimateDiagramModel clone = clone(cloneView());

        IDiagramModelArchimateObject clonedC = objectFor(clone, elC);
        IDiagramModelArchimateConnection clonedAb = connectionFor(objectFor(clone, elA), ab);
        IDiagramModelArchimateConnection clonedInto = connectionFor(clonedC, cToAb);
        IDiagramModelArchimateConnection clonedOutOf = connectionFor(clonedAb, abToC);
        assertNotSame(intoAb, clonedInto);
        assertNotSame(outOfAb, clonedOutOf);
        assertSame("into the cloned connection", clonedAb, clonedInto.getTarget());
        assertSame("out of the cloned connection", clonedAb, clonedOutOf.getSource());
        assertSame(clonedC, clonedOutOf.getTarget());
        assertEquals("every connection on the source is on the clone", 3, connections(clone).size());
        assertEquals("the source keeps its own", 3, connections(view).size());
    }

    @Test
    public void cloneView_shouldCopyConnectionsWhoseTargetsAreEachOther_whenTwoConnectionsPointAtEachOther() {
        IDiagramModelArchimateObject c = object(view, "vo-c", elC, 100, 300, 120, 60);
        IArchimateRelationship x = relationship("rel-x", elC, ab);
        IArchimateRelationship y = relationship("rel-y", elA, x);
        x.setTarget(y);
        // Each one's target is the other, so neither can be wired after the other's ends are.
        IDiagramModelArchimateConnection fromC = factory.createDiagramModelArchimateConnection();
        fromC.setArchimateRelationship(x);
        IDiagramModelArchimateConnection fromA = factory.createDiagramModelArchimateConnection();
        fromA.setArchimateRelationship(y);
        fromC.connect(c, fromA);
        fromA.connect(a, fromC);

        IArchimateDiagramModel clone = clone(cloneView());

        IDiagramModelArchimateConnection clonedFromC = connectionFor(objectFor(clone, elC), x);
        IDiagramModelArchimateConnection clonedFromA = connectionFor(objectFor(clone, elA), y);
        assertSame(clonedFromA, clonedFromC.getTarget());
        assertSame(clonedFromC, clonedFromA.getTarget());
        assertEquals(3, connections(clone).size());
    }

    @Test
    public void cloneView_shouldCopyConnectionsToAndFromReferencesAndImages_whenTheSourceHoldsThem() {
        IDiagramModelNote note = factory.createDiagramModelNote();
        note.setBounds(0, 500, 100, 50);
        view.getChildren().add(note);
        IDiagramModelReference reference = reference(view, w, 10, 400);
        IDiagramModelImage image = image(view, 200, 400);
        IDiagramModelConnection noteToReference = factory.createDiagramModelConnection();
        noteToReference.connect(note, reference);
        IDiagramModelConnection imageToA = factory.createDiagramModelConnection();
        imageToA.connect(image, a);

        IArchimateDiagramModel clone = clone(cloneView());

        IDiagramModelNote clonedNote = only(clone, IDiagramModelNote.class);
        assertEquals(1, clonedNote.getSourceConnections().size());
        assertSame(only(clone, IDiagramModelReference.class), clonedNote.getSourceConnections().get(0).getTarget());
        IDiagramModelImage clonedImage = only(clone, IDiagramModelImage.class);
        assertEquals(1, clonedImage.getSourceConnections().size());
        assertSame(objectFor(clone, elA), clonedImage.getSourceConnections().get(0).getTarget());
    }

    // ---- fixed: inside a batch, around a delete-view ------------------------------------------------------

    @Test
    public void cloneView_shouldDropTheCopiedReference_whenTheBatchDeletesItsViewAfterTheClone() throws Exception {
        accessor.addViewReferenceToView(SESSION, view.getId(), w.getId(), 10, 400, 120, 55, null, null);
        IDiagramModelReference sourceReference = only(view, IDiagramModelReference.class);
        dispatcher.beginBatch(SESSION, "clone then delete the referenced view");
        String id = cloneView();
        accessor.deleteView(SESSION, w.getId());
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals(List.of(), skipped(summary));
        IArchimateDiagramModel clone = clone(id);
        assertEquals("A and B; the cascade took the copied reference", 2, clone.getChildren().size());
        assertNull(ArchimateModelUtils.getObjectByID(model, w.getId()));
        assertFalse(view.getChildren().contains(sourceReference));

        stack.undo();
        assertNull("undo removes the clone", ArchimateModelUtils.getObjectByID(model, id));
        assertSame("and restores the view", w, ArchimateModelUtils.getObjectByID(model, w.getId()));
        assertSame("and the source's reference", w, only(view, IDiagramModelReference.class).getReferencedModel());

        stack.redo();
        assertSame(clone, ArchimateModelUtils.getObjectByID(model, id));
        assertEquals(2, clone.getChildren().size());
        assertNull(ArchimateModelUtils.getObjectByID(model, w.getId()));
        assertEveryReferenceIsInTheModel(clone);
    }

    @Test
    public void cloneView_shouldCopyNoReference_whenTheBatchDeletesItsViewBeforeTheClone() throws Exception {
        accessor.addViewReferenceToView(SESSION, view.getId(), w.getId(), 10, 400, 120, 55, null, null);
        dispatcher.beginBatch(SESSION, "delete the referenced view then clone");
        accessor.deleteView(SESSION, w.getId());
        String id = cloneView();
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals(List.of(), skipped(summary));
        IArchimateDiagramModel clone = clone(id);
        assertEquals("A and B only", 2, clone.getChildren().size());
        assertEveryReferenceIsInTheModel(clone);
    }

    @Test
    public void cloneView_shouldCopyNoReference_whenTheBatchReferencesAViewWhoseCreateDeclined() throws Exception {
        IFolder f = factory.createFolder();
        f.setName("F");
        model.getFolder(FolderType.DIAGRAMS).getFolders().add(f);
        dispatcher.beginBatch(SESSION, "delete F, create Late in F, reference Late, clone");
        accessor.deleteFolder(SESSION, f.getId(), true);
        String late = accessor.createView(SESSION, "Late", null, f.getId(), null).entity().id();
        accessor.addViewReferenceToView(SESSION, view.getId(), late, 10, 400, 120, 55, null, null);
        String id = cloneView();
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        List<String> reasons = skipped(summary);
        assertEquals("Late's decline, then the reference's: " + reasons, 2, reasons.size());
        assertTrue(reasons.get(0), reasons.get(0).contains("'Late' was not created: the folder 'F'"));
        assertTrue(reasons.get(1), reasons.get(1).contains("A reference to the view 'Late' was not added: that "
                + "view is not in the model."));
        IArchimateDiagramModel clone = clone(id);
        assertEquals("A and B only", 2, clone.getChildren().size());
        assertEveryReferenceIsInTheModel(clone);
    }

    // ---- still refused: a reference to a view not in the model ---------------------------------------------

    @Test
    public void cloneView_shouldRefuseAsInvalidParameter_whenTheSourceReferencesAViewNotInTheModel() {
        IArchimateDiagramModel gone = factory.createArchimateDiagramModel();
        gone.setName("Gone");
        IDiagramModelReference dangling = reference(view, gone, 10, 400);

        ModelAccessException refused = refusal();

        assertEquals(ErrorCode.INVALID_PARAMETER, refused.getErrorCode());
        assertEquals("Cannot clone the view 'Source': its view reference '" + dangling.getId()
                + "' points to the view 'Gone', which is not in the model.", refused.getMessage());
        assertEquals("Remove that view reference with remove-from-view, " + THEN_CLONE,
                refused.getSuggestedCorrection());
        assertEquals("not logged as a server fault", "DEBUG", loggedAt(refused));
        assertEquals("nothing was queued or created", 2, model.getFolder(FolderType.DIAGRAMS).getElements().size());
    }

    @Test
    public void cloneView_shouldSayTheReferencePointsToNoView_whenTheSourceHoldsAReferenceWithoutAView() {
        IDiagramModelReference empty = reference(view, null, 10, 400);

        ModelAccessException refused = refusal();

        assertEquals(ErrorCode.INVALID_PARAMETER, refused.getErrorCode());
        assertEquals("Cannot clone the view 'Source': its view reference '" + empty.getId()
                + "' points to no view.", refused.getMessage());
    }

    // ---- still refused: a connection with an end that is not on the view ------------------------------------

    @Test
    public void cloneView_shouldRefuseAsInvalidParameter_whenAConnectionHasAnEndOffTheView() {
        IDiagramModelArchimateObject onW = object(w, "vo-on-w", elC, 0, 0, 120, 60);
        IDiagramModelArchimateConnection across = archimateConnection(relationship("rel-a-c", elA, elC), a, onW);

        ModelAccessException refused = refusal();

        assertEquals(ErrorCode.INVALID_PARAMETER, refused.getErrorCode());
        assertEquals("Cannot clone the view 'Source': its connection '" + across.getId()
                + "' has an end that is not on the view.", refused.getMessage());
        assertEquals("Remove that connection with remove-from-view, passing this view, " + THEN_CLONE, refused.getSuggestedCorrection());
        assertEquals("not logged as a server fault", "DEBUG", loggedAt(refused));
        assertEquals("nothing was queued or created", 2, model.getFolder(FolderType.DIAGRAMS).getElements().size());
    }

    @Test
    public void cloneView_shouldDeclineWhole_whenAConnectionHasAnEndOffTheViewAtCommit() throws Exception {
        dispatcher.beginBatch(SESSION, "clone, then a script joins the source to another view");
        String id = cloneView();
        IDiagramModelArchimateObject onW = object(w, "vo-on-w", elC, 0, 0, 120, 60);
        archimateConnection(relationship("rel-a-c", elA, elC), a, onW);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        List<String> reasons = skipped(summary);
        assertEquals(reasons.toString(), 1, reasons.size());
        assertTrue(reasons.get(0), reasons.get(0).contains("'Clone' was not created: the view 'Source' holds a "
                + "connection with an end that is not on the view, so a clone cannot copy it."));
        assertNull("no half-built clone is left in the model", ArchimateModelUtils.getObjectByID(model, id));
        assertEquals("only the source and W", 2, model.getFolder(FolderType.DIAGRAMS).getElements().size());
    }

    // ---- still refused: an object a clone has no copy for --------------------------------------------------

    @Test
    public void cloneView_shouldRefuseAsInvalidParameter_whenTheSourceHoldsAnObjectACloneHasNoCopyFor() {
        ISketchModelSticky sticky = factory.createSketchModelSticky();
        sticky.setId("sticky-1");
        sticky.setBounds(0, 0, 50, 50);
        view.getChildren().add(sticky);

        ModelAccessException refused = refusal();

        assertEquals(ErrorCode.INVALID_PARAMETER, refused.getErrorCode());
        assertEquals("Cannot clone the view 'Source': it holds a view object of a type a clone cannot copy, "
                + "SketchModelSticky 'sticky-1'.", refused.getMessage());
        assertEquals("Remove that object with remove-from-view, " + THEN_CLONE,
                refused.getSuggestedCorrection());
        assertEquals("not logged as a server fault", "DEBUG", loggedAt(refused));
    }

    @Test
    public void cloneView_shouldDeclineWithoutClaimingAnOrder_whenTheSourceHoldsAnObjectACloneHasNoCopyForAtCommit()
            throws Exception {
        dispatcher.beginBatch(SESSION, "clone, then a script adds a sketch sticky");
        String id = cloneView();
        ISketchModelSticky sticky = factory.createSketchModelSticky();
        sticky.setBounds(0, 0, 50, 50);
        view.getChildren().add(sticky);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        List<String> reasons = skipped(summary);
        assertEquals(reasons.toString(), 1, reasons.size());
        assertTrue(reasons.get(0), reasons.get(0).contains("'Clone' was not created: the view 'Source' held a view "
                + "object of a type a clone cannot copy (SketchModelSticky)"));
        assertFalse(reasons.get(0), reasons.get(0).contains("added after"));
        assertNull(ArchimateModelUtils.getObjectByID(model, id));
    }

    // ---- undo, redo and a save that reopens -----------------------------------------------------------------

    @Test
    public void cloneView_shouldUndoAndRedoTheSameReferenceAndImage_whenTheSourceHoldsThem() {
        reference(view, w, 10, 400);
        image(view, 200, 400);
        IDiagramModelArchimateObject c = object(view, "vo-c", elC, 100, 300, 120, 60);
        archimateConnection(relationship("rel-c-ab", elC, ab), c, connectionAb);
        IArchimateDiagramModel clone = clone(cloneView());
        IDiagramModelReference reference = only(clone, IDiagramModelReference.class);
        IDiagramModelImage image = only(clone, IDiagramModelImage.class);
        IDiagramModelConnection onConnection = objectFor(clone, elC).getSourceConnections().get(0);

        stack.undo();
        assertNull(ArchimateModelUtils.getObjectByID(model, clone.getId()));

        stack.redo();
        assertSame(clone, ArchimateModelUtils.getObjectByID(model, clone.getId()));
        assertSame(reference, only(clone, IDiagramModelReference.class));
        assertSame(image, only(clone, IDiagramModelImage.class));
        assertSame(onConnection, objectFor(clone, elC).getSourceConnections().get(0));
        assertSame(connectionFor(objectFor(clone, elA), ab), onConnection.getTarget());
        assertEquals(connections(view).size(), connections(clone).size());
    }

    @Test
    public void cloneView_shouldReopenWithTheSameContents_whenTheModelIsSavedAndReloaded() throws Exception {
        reference(view, w, 10, 400);
        reference(view, view, 10, 500);
        image(view, 200, 400);
        IDiagramModelArchimateObject c = object(view, "vo-c", elC, 100, 300, 120, 60);
        archimateConnection(relationship("rel-c-ab", elC, ab), c, connectionAb);
        archimateConnection(relationship("rel-ab-c", ab, elC), connectionAb, c);
        String id = cloneView();

        File file = temp.newFile("clone.archimate");
        Resource saved = ArchimateResourceFactory.createNewResource(file);
        saved.getContents().add(model);
        saved.save(null);
        Resource reloaded = ArchimateResourceFactory.createResourceSet()
                .getResource(URI.createFileURI(file.getAbsolutePath()), true);

        assertEquals("reload errors", List.of(), reloaded.getErrors());
        IArchimateModel reopened = (IArchimateModel) reloaded.getContents().get(0);
        IArchimateDiagramModel reopenedSource = (IArchimateDiagramModel) ArchimateModelUtils
                .getObjectByID(reopened, view.getId());
        IArchimateDiagramModel reopenedClone = (IArchimateDiagramModel) ArchimateModelUtils.getObjectByID(reopened, id);
        assertEquals(reopenedSource.getChildren().size(), reopenedClone.getChildren().size());
        assertEquals(6, reopenedClone.getChildren().size());
        assertEquals(connections(reopenedSource).size(), connections(reopenedClone).size());
        assertEquals(3, connections(reopenedClone).size());
        List<IDiagramModel> referenced = new ArrayList<>();
        for (IDiagramModelObject child : reopenedClone.getChildren()) {
            if (child instanceof IDiagramModelReference r) {
                referenced.add(r.getReferencedModel());
            }
        }
        assertEquals(List.of(ArchimateModelUtils.getObjectByID(reopened, w.getId()), reopenedClone), referenced);
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private String cloneView() {
        return accessor.cloneView(SESSION, view.getId(), "Clone", null).entity().id();
    }

    private ModelAccessException refusal() {
        try {
            accessor.cloneView(SESSION, view.getId(), "Clone", null);
        } catch (ModelAccessException e) {
            return e;
        }
        fail("the clone was not refused");
        return null;
    }

    /** The level the one rule for logging an answered error logs this at. */
    private static String loggedAt(ModelAccessException e) {
        String[] level = {"none"};
        Logger logger = (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(),
                new Class<?>[] {Logger.class}, (proxy, method, args) -> {
                    if (List.of("error", "warn", "info", "debug", "trace").contains(method.getName())) {
                        level[0] = method.getName().toUpperCase();
                    }
                    return method.getReturnType() == boolean.class ? Boolean.TRUE : null;
                });
        ToolErrorLog.log(logger, e);
        return level[0];
    }

    private IArchimateDiagramModel clone(String id) {
        EObject found = ArchimateModelUtils.getObjectByID(model, id);
        assertTrue("the clone is in the model: " + id, found instanceof IArchimateDiagramModel);
        return (IArchimateDiagramModel) found;
    }

    private static List<String> skipped(BatchSummaryDto summary) {
        return summary.skippedOperations() == null ? List.of() : summary.skippedOperations();
    }

    private static <T extends IDiagramModelObject> T only(IDiagramModelContainer container, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (IDiagramModelObject child : container.getChildren()) {
            if (type.isInstance(child)) {
                found.add(type.cast(child));
            }
        }
        assertEquals("one " + type.getSimpleName() + " directly under " + container, 1, found.size());
        return found.get(0);
    }

    private static List<IDiagramModelConnection> connections(IDiagramModel diagram) {
        List<IDiagramModelConnection> found = new ArrayList<>();
        for (var it = diagram.eAllContents(); it.hasNext();) {
            if (it.next() instanceof IDiagramModelConnection conn) {
                found.add(conn);
            }
        }
        return found;
    }

    private static void assertBounds(IDiagramModelObject object, int x, int y, int width, int height) {
        assertEquals("bounds", List.of(x, y, width, height), List.of(object.getBounds().getX(),
                object.getBounds().getY(), object.getBounds().getWidth(), object.getBounds().getHeight()));
    }

    private static IDiagramModelArchimateObject objectFor(IDiagramModelContainer container, IArchimateElement el) {
        for (IDiagramModelObject child : container.getChildren()) {
            if (child instanceof IDiagramModelArchimateObject obj && obj.getArchimateElement() == el) {
                return obj;
            }
        }
        fail("an object for " + el.getId());
        return null;
    }

    private static IDiagramModelArchimateConnection connectionFor(com.archimatetool.model.IConnectable source,
            IArchimateRelationship rel) {
        for (IDiagramModelConnection conn : source.getSourceConnections()) {
            if (conn instanceof IDiagramModelArchimateConnection ac && ac.getArchimateRelationship() == rel) {
                return ac;
            }
        }
        fail("a connection for " + rel.getId());
        return null;
    }

    /** Every concept and view the clone draws or points to is in the model, so a saved copy reopens. */
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
            if (o instanceof IDiagramModelReference ref && ref.getReferencedModel().getArchimateModel() != model) {
                orphans.add(ref.getReferencedModel().getId());
            }
        }
        assertEquals("concepts or views the clone points to but not in the model", List.of(), orphans);
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

    private IDiagramModelArchimateConnection archimateConnection(IArchimateRelationship rel,
            com.archimatetool.model.IConnectable source, com.archimatetool.model.IConnectable target) {
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
            IArchimateElement el, int x, int y, int width, int height) {
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
