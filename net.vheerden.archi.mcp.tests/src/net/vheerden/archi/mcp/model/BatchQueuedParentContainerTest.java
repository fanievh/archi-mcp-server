package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.Platform;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.archimatetool.editor.model.IArchiveManager;
import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.AddToViewResultDto;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.ViewGroupDto;

/**
 * Pins that a container added earlier in an open batch can be named as {@code parentViewObjectId}
 * by a later operation in that same batch.
 *
 * <p>{@code add-group-to-view} builds a <em>detached</em> group and defers containment to
 * {@code AddGroupToViewCommand.execute()}. Inside a batch that execute runs at commit, so between
 * the two calls the group exists only as a queued command: the response hands the agent a real id
 * for an object the view cannot see. Before the queued-parent lookup existed, the next queued
 * operation naming that id resolved its parent against live containment only, missed, and threw
 * {@code VIEW_OBJECT_NOT_FOUND} — the nested build could not be constructed at all, while the same
 * three calls succeeded as one {@code bulk-mutate}, which bridges the gap with its
 * batch-created-parent maps.</p>
 *
 * <h2>Execution model</h2>
 *
 * <p>Same headless idiom as {@code BatchAnchorTargetStalenessTest} / {@code BatchDeleteFolderGuardTest}:
 * a real GEF {@link CommandStack} driven over an <em>ordered</em> compound, because queue order is
 * load-bearing here (the parent's add must execute before the child's). The production compound is
 * {@code NonNotifyingCompoundCommand}, whose {@code execute()} dereferences
 * {@code IEditorModelManager.INSTANCE} and cannot run headless, so the queued children are rebuilt
 * into a plain GEF {@link CompoundCommand} — order preserved, only ECORE event suppression dropped.
 * That substitution is why real-{@code CommandStack} undo through the production compound remains a
 * live-gate observation; what is proven here is the one-unit, ordering and membership property.
 * {@code executeDecomposed} is deliberately not used: it flattens the compound and would hide the
 * ordering property.</p>
 *
 * <p>Every add passes explicit x/y/width/height except where auto-placement is itself under test,
 * so no code path reaches {@code ElementSizer}'s {@code Display.getDefault().syncExec} — which is
 * what would otherwise force this class onto a display. Only the {@code add-image-to-view} pin needs
 * the PDE/OSGi runtime (a real {@link IArchiveManager}) and assumes its way out headlessly.</p>
 */
public class BatchQueuedParentContainerTest {

    private static final String SESSION = "batch-queued-parent-session";

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private CommandStack stack;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private IArchimateDiagramModel otherView;
    private IBusinessActor actor;
    private IBusinessActor peer;
    private IDiagramModelGroup liveHost;
    private IDiagramModelArchimateObject livePeerObject;
    private Command lastDispatched;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Batch Queued Parent Fixture");
        model.setId("model-batch-queued-parent");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Nesting");
        diagrams.getElements().add(view);

        otherView = factory.createArchimateDiagramModel();
        otherView.setId("view-2");
        otherView.setName("Referenced");
        diagrams.getElements().add(otherView);

        IFolder business = model.getFolder(FolderType.BUSINESS);
        actor = factory.createBusinessActor();
        actor.setId("actor-1");
        actor.setName("Nested Actor");
        business.getElements().add(actor);

        peer = factory.createBusinessActor();
        peer.setId("actor-2");
        peer.setName("Peer Actor");
        business.getElements().add(peer);

        IArchimateRelationship rel = factory.createAssociationRelationship();
        rel.setId("rel-1");
        rel.setSource(actor);
        rel.setTarget(peer);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);

        // A live, unauthored-fill group: nesting into it triggers the fill-recession wrap, so a
        // group queued inside it arrives in the queue as a compound rather than a bare add.
        liveHost = factory.createDiagramModelGroup();
        liveHost.setId("grp-live-host");
        liveHost.setName("Live Host");
        liveHost.setBounds(0, 0, 600, 600);
        view.getChildren().add(liveHost);

        // A live peer view object so auto-connect has something to connect to.
        livePeerObject = factory.createDiagramModelArchimateObject();
        livePeerObject.setId("obj-live-peer");
        livePeerObject.setArchimateElement(peer);
        livePeerObject.setBounds(700, 700, 120, 60);
        view.getChildren().add(livePeerObject);

        stubModelManager.setModels(List.of(model));

        stack = new CommandStack();
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                stack.execute(new AgentAuthoredCompoundCommand(toPlainCompound(command)));
            }
            @Override
            protected void dispatchCommand(Command command) {
                lastDispatched = command;
                stack.execute(new AgentAuthoredCompoundCommand(toPlainCompound(command)));
            }
            private Command toPlainCompound(Command command) {
                if (command instanceof CompoundCommand compound) {
                    CompoundCommand plain = new CompoundCommand(compound.getLabel());
                    for (Object child : compound.getCommands()) {
                        plain.add(toPlainCompound((Command) child));
                    }
                    return plain;
                }
                return command;
            }
        };
        dispatcher.setApprovalModeProvider(() -> false);
        accessor = new ArchiModelAccessorImpl(stubModelManager, dispatcher);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private String group(String label, int x, int y, int w, int h, String parentId) {
        return accessor.addGroupToView(SESSION, view.getId(), label,
                x, y, w, h, parentId, null, null).entity().viewObjectId();
    }

    private String element(String elementId, int x, int y, int w, int h, String parentId) {
        return accessor.addToView(SESSION, view.getId(), elementId, x, y, w, h,
                false, parentId, null, null).entity().viewObject().viewObjectId();
    }

    /** Depth-first search of live containment for the view object holding a given element. */
    private static IDiagramModelObject findByElement(IDiagramModelContainer container, String elementId) {
        for (Object child : container.getChildren()) {
            IDiagramModelObject obj = (IDiagramModelObject) child;
            if (obj instanceof IDiagramModelArchimateObject archi
                    && archi.getArchimateElement() != null
                    && elementId.equals(archi.getArchimateElement().getId())) {
                return obj;
            }
            if (obj instanceof IDiagramModelContainer nested) {
                IDiagramModelObject hit = findByElement(nested, elementId);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /** Depth-first search of live containment for a view object id. */
    private static IDiagramModelObject find(IDiagramModelContainer container, String id) {
        for (Object child : container.getChildren()) {
            IDiagramModelObject obj = (IDiagramModelObject) child;
            if (id.equals(obj.getId())) {
                return obj;
            }
            if (obj instanceof IDiagramModelContainer nested) {
                IDiagramModelObject hit = find(nested, id);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private void assertChildOf(String parentId, String childId) {
        IDiagramModelObject parent = find(view, parentId);
        assertNotNull("parent '" + parentId + "' must be in the view after commit", parent);
        assertTrue("parent must be a container", parent instanceof IDiagramModelContainer);
        IDiagramModelObject child = find(view, childId);
        assertNotNull("child '" + childId + "' must be in the view after commit", child);
        assertSame("child must be contained by the named parent, not the view root",
                parent, child.eContainer());
    }

    /**
     * Asserts a cross-view rejection that names the mechanism: which view the parent belongs to
     * and which view the operation named. A bare "not found" would send the agent looking for an
     * id it was just handed.
     */
    private void assertCrossViewRejected(Runnable call) {
        try {
            call.run();
            fail("an operation naming one view must not silently nest into another view's parent");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue("the message must name the view the parent belongs to. Was: "
                    + e.getMessage(), e.getMessage().contains(view.getId()));
            assertTrue("the message must name the view the operation asked for. Was: "
                    + e.getMessage(), e.getMessage().contains(otherView.getId()));
        }
    }

    /** Asserts the ordinary live-lookup failure — the same one a non-batch caller gets. */
    private void assertParentNotFound(Runnable call, String parentId) {
        try {
            call.run();
            fail("expected the ordinary live-lookup failure for parent " + parentId);
        } catch (ModelAccessException e) {
            assertEquals("message must stay the ordinary not-found one",
                    "Parent view object not found: " + parentId, e.getMessage());
            assertEquals("error code must stay VIEW_OBJECT_NOT_FOUND",
                    ErrorCode.VIEW_OBJECT_NOT_FOUND, e.getErrorCode());
        }
    }

    // ---- the defect ---------------------------------------------------------------------------

    /**
     * The headline case: a group queued earlier in the batch must be usable as the parent of a
     * later queued add, and the containment must land at commit.
     */
    @Test
    public void shouldNestElementInQueuedGroup_whenGroupWasAddedEarlierInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "group then nest");
        String outer = group("Outer", 0, 0, 400, 400, null);
        String child = element(actor.getId(), 20, 20, 100, 100, outer);
        dispatcher.endBatch(SESSION, true);

        assertChildOf(outer, child);
        assertSame("Outer must sit directly on the view", view, find(view, outer).eContainer());
        IDiagramModelObject childObj = find(view, child);
        assertEquals("x stays parent-relative", 20, childObj.getBounds().getX());
        assertEquals("y stays parent-relative", 20, childObj.getBounds().getY());
    }

    /** Depth 2: a queued group nested in a queued group, with a queued element at the leaf. */
    @Test
    public void shouldNestThreeDeep_whenEveryParentIsQueuedInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "three deep");
        String outer = group("Outer", 0, 0, 400, 400, null);
        String inner = group("Inner", 10, 10, 300, 300, outer);
        String leaf = element(actor.getId(), 20, 20, 100, 100, inner);
        dispatcher.endBatch(SESSION, true);

        assertSame("Outer must sit directly on the view", view, find(view, outer).eContainer());
        assertChildOf(outer, inner);
        assertChildOf(inner, leaf);
    }

    /** A queued element view object is a valid parent too — the bulk path's second arm. */
    @Test
    public void shouldNestGroupInQueuedElement_whenElementWasAddedEarlierInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "element then nest");
        String host = element(actor.getId(), 0, 0, 400, 400, null);
        String nested = group("Inside Element", 15, 15, 200, 200, host);
        dispatcher.endBatch(SESSION, true);

        assertChildOf(host, nested);
    }

    // ---- every add-* tool accepts a queued parent -----------------------------------------------

    @Test
    public void shouldNestNoteInQueuedGroup_whenGroupWasAddedEarlierInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "note into queued group");
        String outer = group("Outer", 0, 0, 400, 400, null);
        String note = accessor.addNoteToView(SESSION, view.getId(), "Nested note", null, null,
                20, 20, 150, 60, outer, null, null).entity().viewObjectId();
        dispatcher.endBatch(SESSION, true);

        assertChildOf(outer, note);
    }

    @Test
    public void shouldNestViewReferenceInQueuedGroup_whenGroupWasAddedEarlierInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "view reference into queued group");
        String outer = group("Outer", 0, 0, 400, 400, null);
        String ref = accessor.addViewReferenceToView(SESSION, view.getId(), otherView.getId(),
                20, 20, 150, 60, outer, null).entity().viewObjectId();
        dispatcher.endBatch(SESSION, true);

        assertChildOf(outer, ref);
    }

    /**
     * The image tool needs a real {@link IArchiveManager} to validate the archive path, which only
     * the PDE/OSGi runtime can create — so this one pin assumes out headlessly and is covered in
     * the plug-in lane. The other four add-* tools are pinned unconditionally above.
     */
    @Test
    public void shouldNestImageInQueuedGroup_whenGroupWasAddedEarlierInSameBatch() throws Exception {
        assumeTrue("requires PDE/OSGi runtime for IArchiveManager", Platform.isRunning());

        IArchiveManager archiveManager = IArchiveManager.FACTORY.createArchiveManager(model);
        model.setAdapter(IArchiveManager.class, archiveManager);
        File png = tempFolder.newFile("known.png");
        writeMinimalPng(png);
        String imagePath = archiveManager.addImageFromFile(png);

        dispatcher.beginBatch(SESSION, "image into queued group");
        String outer = group("Outer", 0, 0, 400, 400, null);
        String image = accessor.addImageToView(SESSION, view.getId(), imagePath,
                20, 20, 100, 100, outer, null, null, null).entity().viewObjectId();
        dispatcher.endBatch(SESSION, true);

        assertChildOf(outer, image);
    }

    /**
     * The queued lookup matches only the two container-creating commands, so a queued note — which
     * carries a real id but can never be a parent — falls through to the ordinary live lookup and
     * produces exactly the error a non-batch caller would get. Groups and elements only, in both
     * batch and bulk.
     */
    @Test
    public void shouldRejectQueuedNoteAsParent_whenNamedByALaterAddInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "note is not a container");
        String note = accessor.addNoteToView(SESSION, view.getId(), "Not a parent", null, null,
                0, 0, 150, 60, null, null, null).entity().viewObjectId();

        assertParentNotFound(() -> element(actor.getId(), 5, 5, 100, 60, note), note);
        dispatcher.endBatch(SESSION, false);
    }

    // ---- lifetime: the queue is the whole record ------------------------------------------------

    /** A rolled-back group must stop being addressable — the queue clears on rollback. */
    @Test
    public void shouldNotResolveRolledBackGroup_whenLaterBatchNamesItAsParent() throws Exception {
        dispatcher.beginBatch(SESSION, "queue then roll back");
        String outer = group("Outer", 0, 0, 400, 400, null);
        dispatcher.endBatch(SESSION, false);

        dispatcher.beginBatch(SESSION, "name the rolled-back group");
        assertParentNotFound(() -> element(actor.getId(), 20, 20, 100, 100, outer), outer);
        dispatcher.endBatch(SESSION, false);
    }

    /**
     * After commit the id must resolve through the ordinary live path, not a lingering queue entry:
     * the fix must not shadow live lookups.
     */
    @Test
    public void shouldResolveCommittedGroupLive_whenLaterBatchNamesItAsParent() throws Exception {
        dispatcher.beginBatch(SESSION, "queue then commit");
        String outer = group("Outer", 0, 0, 400, 400, null);
        dispatcher.endBatch(SESSION, true);

        assertNull("the committed batch must leave nothing queued to resolve",
                dispatcher.queuedParentContainer(SESSION, outer));

        dispatcher.beginBatch(SESSION, "nest into the committed group");
        String child = element(actor.getId(), 20, 20, 100, 100, outer);
        dispatcher.endBatch(SESSION, true);

        assertChildOf(outer, child);
    }

    // ---- wrapped commands ------------------------------------------------------------------------

    /**
     * Nesting into the live unauthored-fill host makes the queued group's own entry a compound
     * (fill recession wraps the add). Resolving that group as a parent therefore only works if the
     * queue walk descends into compounds.
     */
    @Test
    public void shouldResolveQueuedParent_whenItsAddIsWrappedByFillRecession() throws Exception {
        dispatcher.beginBatch(SESSION, "wrapped group then nest");
        String wrapped = group("Wrapped", 10, 10, 300, 300, liveHost.getId());
        String child = element(actor.getId(), 20, 20, 100, 100, wrapped);
        dispatcher.endBatch(SESSION, true);

        assertChildOf(liveHost.getId(), wrapped);
        assertChildOf(wrapped, child);
    }

    /** The auto-connect path wraps the add in its own compound — the queued object is a level down. */
    @Test
    public void shouldResolveQueuedParent_whenItsAddIsWrappedByAutoConnectCompound() throws Exception {
        dispatcher.beginBatch(SESSION, "auto-connected element then nest");
        String host = accessor.addToView(SESSION, view.getId(), actor.getId(), 0, 0, 400, 400,
                true, null, null, null).entity().viewObject().viewObjectId();
        String nested = group("Inside Auto-Connected", 15, 15, 200, 200, host);
        dispatcher.endBatch(SESSION, true);

        assertChildOf(host, nested);
    }

    // ---- a detached parent must not break the rest of the prepare -------------------------------

    /**
     * With a detached parent flowing into the prepare, the icon-band reservation reads
     * {@code parentContainer.getChildren()} (empty) and {@code parentObj.eContainer()} (null on a
     * detached parent, so the grandparent-cascade branch is skipped). Drive the branch that was
     * unreachable before this change: a queued parent carrying a non-default corner icon.
     */
    @Test
    public void shouldNotThrow_whenQueuedParentCarriesCornerIcon() throws Exception {
        dispatcher.beginBatch(SESSION, "corner-icon queued parent");
        String outer = accessor.addGroupToView(SESSION, view.getId(), "Corner Icon",
                0, 0, 400, 400, null, null,
                new ImageParams(null, "bottom-left", null)).entity().viewObjectId();
        String child = element(actor.getId(), 5, 340, 60, 40, outer);
        dispatcher.endBatch(SESSION, true);

        assertChildOf(outer, child);
    }

    /** Auto-placement must cope with a parent whose children list is still empty. */
    @Test
    public void shouldNotThrow_whenChildOmitsCoordinatesUnderQueuedParent() throws Exception {
        dispatcher.beginBatch(SESSION, "auto-placed child");
        String outer = group("Outer", 0, 0, 400, 400, null);
        String child = accessor.addToView(SESSION, view.getId(), actor.getId(),
                null, null, 100, 60, false, outer, null, null)
                .entity().viewObject().viewObjectId();
        dispatcher.endBatch(SESSION, true);

        assertChildOf(outer, child);
    }

    // ---- non-batch mode is untouched --------------------------------------------------------------

    /**
     * Outside a batch the lookup returns null by construction, so a bogus parent id must still fail
     * with exactly the message and code it failed with before.
     */
    @Test
    public void shouldFailIdentically_whenParentIsBogusOutsideBatch() {
        assertEquals("pre-condition: session is not in batch mode",
                OperationalMode.GUI_ATTACHED, dispatcher.getMode(SESSION));
        assertParentNotFound(() -> element(actor.getId(), 20, 20, 100, 100, "no-such-parent"),
                "no-such-parent");
    }

    // ---- the operation's own viewId must not be silently ignored ------------------------------
    //
    // The live-lookup arm enforces view membership by construction: it searches the view the
    // operation named, so a parent that is not in it is simply not found. The pre-resolved arm
    // could not, because the container it is handed is still detached and belongs to no view yet.
    // The result was that supplying a same-request parent silently overrode the operation's own
    // viewId, and the child landed in whichever view the parent was destined for.

    @Test
    public void shouldRejectCrossViewParent_whenAddingAnElementUnderAnotherViewsQueuedGroup()
            throws Exception {
        dispatcher.beginBatch(SESSION, "queue a group in one view, then add into another");
        String outer = group("Outer", 0, 0, 400, 400, null);

        assertCrossViewRejected(() -> accessor.addToView(SESSION, otherView.getId(),
                actor.getId(), 20, 20, 100, 100, false, outer, null, null));

        dispatcher.endBatch(SESSION, true);
        assertEquals("the view the operation named must stay untouched",
                0, otherView.getChildren().size());
        assertNull("and the element must not have landed in the parent's view either",
                findByElement(view, actor.getId()));
    }

    /** The second of the five tools that share the arm, on the same path. */
    @Test
    public void shouldRejectCrossViewParent_whenAddingAGroupUnderAnotherViewsQueuedGroup()
            throws Exception {
        dispatcher.beginBatch(SESSION, "queue a group in one view, then nest a group from another");
        String outer = group("Outer", 0, 0, 400, 400, null);

        assertCrossViewRejected(() -> accessor.addGroupToView(SESSION, otherView.getId(),
                "Intruder", 20, 20, 100, 100, outer, null, null));

        dispatcher.endBatch(SESSION, true);
        assertEquals("the view the operation named must stay untouched",
                0, otherView.getChildren().size());
    }

    @Test
    public void shouldRejectCrossViewParent_whenBulkMutateNamesABackReferenceFromAnotherView() {
        List<BulkOperation> ops = List.of(
                new BulkOperation("add-group-to-view", Map.of(
                        "viewId", view.getId(), "label", "GroupInA",
                        "x", 0, "y", 0, "width", 400, "height", 400)),
                new BulkOperation("add-to-view", Map.of(
                        "viewId", otherView.getId(), "elementId", actor.getId(),
                        "parentViewObjectId", "$0.id",
                        "x", 20, "y", 20, "width", 100, "height", 100)));

        try {
            accessor.executeBulk(SESSION, ops, "cross-view nest through a back-reference", false);
            fail("a bulk operation naming one view must not nest into another view's parent");
        } catch (RuntimeException expected) {
            assertTrue("the failure must name both views. Was: " + expected.getMessage(),
                    expected.getMessage().contains(view.getId())
                            && expected.getMessage().contains(otherView.getId()));
        }

        assertEquals("the view the operation named must stay untouched",
                0, otherView.getChildren().size());
        assertNull("and nothing may have landed in the parent's view either",
                findByElement(view, actor.getId()));
    }

    /** The same rejection through the second tool, on the bulk path. */
    @Test
    public void shouldRejectCrossViewParent_whenBulkMutateNestsAGroupFromAnotherView() {
        List<BulkOperation> ops = List.of(
                new BulkOperation("add-group-to-view", Map.of(
                        "viewId", view.getId(), "label", "GroupInA",
                        "x", 0, "y", 0, "width", 400, "height", 400)),
                new BulkOperation("add-group-to-view", Map.of(
                        "viewId", otherView.getId(), "label", "Intruder",
                        "parentViewObjectId", "$0.id",
                        "x", 20, "y", 20, "width", 100, "height", 100)));

        try {
            accessor.executeBulk(SESSION, ops, "cross-view group nest", false);
            fail("a bulk operation naming one view must not nest into another view's parent");
        } catch (RuntimeException expected) {
            assertTrue("the failure must name both views. Was: " + expected.getMessage(),
                    expected.getMessage().contains(view.getId())
                            && expected.getMessage().contains(otherView.getId()));
        }
        assertEquals("the view the operation named must stay untouched",
                0, otherView.getChildren().size());
    }

    /**
     * The non-regression that makes the change to this arm safe, restated on the bulk path: a
     * same-request parent used within its <em>own</em> view still nests. Measured against a naive
     * {@code getDiagramModel() != view} implementation, which rejects every case here — a detached
     * parent answers null, which is the whole reason the pre-resolved arm exists.
     */
    @Test
    public void shouldStillNestInBulk_whenTheBackReferencedParentBelongsToTheNamedView() {
        List<BulkOperation> ops = List.of(
                new BulkOperation("add-group-to-view", Map.of(
                        "viewId", view.getId(), "label", "GroupInA",
                        "x", 0, "y", 0, "width", 400, "height", 400)),
                new BulkOperation("add-to-view", Map.of(
                        "viewId", view.getId(), "elementId", actor.getId(),
                        "parentViewObjectId", "$0.id",
                        "x", 20, "y", 20, "width", 100, "height", 100)));

        accessor.executeBulk(SESSION, ops, "same-view nest through a back-reference", false);

        IDiagramModelObject placed = findByElement(view, actor.getId());
        assertNotNull("the element must be placed", placed);
        assertTrue("and must sit inside the group the same call created",
                placed.eContainer() instanceof IDiagramModelGroup);
    }

    /**
     * The rest of the five tools that share the arm, nesting into a same-call parent within their
     * own view. Image is left to the batch-path pin above, which needs a real archive manager.
     */
    @Test
    public void shouldStillNestEveryToolInBulk_whenTheBackReferencedParentBelongsToTheNamedView() {
        List<BulkOperation> ops = List.of(
                new BulkOperation("add-group-to-view", Map.of(
                        "viewId", view.getId(), "label", "Host",
                        "x", 0, "y", 0, "width", 500, "height", 500)),
                new BulkOperation("add-group-to-view", Map.of(
                        "viewId", view.getId(), "label", "Inner", "parentViewObjectId", "$0.id",
                        "x", 10, "y", 10, "width", 100, "height", 100)),
                new BulkOperation("add-note-to-view", Map.of(
                        "viewId", view.getId(), "content", "Note", "parentViewObjectId", "$0.id",
                        "x", 10, "y", 150, "width", 100, "height", 60)),
                new BulkOperation("add-view-reference-to-view", Map.of(
                        "viewId", view.getId(), "referencedViewId", otherView.getId(),
                        "parentViewObjectId", "$0.id",
                        "x", 10, "y", 250, "width", 100, "height", 60)));

        accessor.executeBulk(SESSION, ops, "same-view nesting across the tools", false);

        IDiagramModelObject host = null;
        for (Object child : view.getChildren()) {
            if (child instanceof IDiagramModelGroup group && "Host".equals(group.getName())) {
                host = group;
            }
        }
        assertNotNull("the host group must be placed at the view root", host);
        assertEquals("all three later tools must have nested inside it",
                3, ((IDiagramModelContainer) host).getChildren().size());
    }

    /** The guard itself: no batch open means no queued container, whatever the id. */
    @Test
    public void shouldReturnNull_whenSessionIsNotInBatchMode() {
        assertNull("no batch context at all", dispatcher.queuedParentContainer(SESSION, "any-id"));
        assertNull("null id is never resolvable", dispatcher.queuedParentContainer(SESSION, null));
    }

    /**
     * The approval lambda re-runs the prepare at approval-commit time, so its mode is whatever the
     * session is in <em>then</em>. With no batch open the lookup returns null and the proposal
     * behaves exactly as before.
     */
    @Test
    public void shouldNotResolveQueuedParent_whenApprovalRunsOutsideBatch() {
        dispatcher.setApprovalModeProvider(() -> true);
        assertParentNotFound(() -> element(actor.getId(), 20, 20, 100, 100, "no-such-parent"),
                "no-such-parent");
    }

    /**
     * The other leg, measured end-to-end rather than assumed: with a batch open, a proposal's
     * prepare resolves the queued parent just as a direct call does. The approval lambda re-runs the
     * prepare at <em>approve</em> time, so this drives the whole chain — propose, approve, commit —
     * and asserts the child actually lands inside the queued parent. Asserting only that the
     * proposal was stored would leave the re-run itself unmeasured, which is exactly where a
     * same-batch fix can silently evaporate.
     */
    @Test
    public void shouldNestUnderQueuedParent_whenProposalIsApprovedInsideOpenBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "queue a group");
        String outer = group("Outer", 0, 0, 400, 400, null);

        dispatcher.setApprovalModeProvider(() -> true);
        var proposed = accessor.addToView(SESSION, view.getId(), actor.getId(),
                20, 20, 100, 100, false, outer, null, null);

        assertTrue("the add must be stored as a proposal, not rejected", proposed.isProposal());
        assertNotNull("the queued parent must still be resolvable under approval mode",
                dispatcher.queuedParentContainer(SESSION, outer));

        // Approve while the batch is still open: the re-run prepare must resolve the queued parent
        // again, and the approved command joins the same batch rather than dispatching on its own.
        ApprovalResult approved = dispatcher.approveProposal(
                SESSION, proposed.proposalContext().proposalId());
        assertNotNull("the proposal must approve, not reject as stale", approved);

        dispatcher.setApprovalModeProvider(() -> false);
        dispatcher.endBatch(SESSION, true);

        String child = approved.entity() instanceof AddToViewResultDto dto
                ? dto.viewObject().viewObjectId() : null;
        assertNotNull("the approved result must name the created view object", child);
        assertChildOf(outer, child);
    }

    // ---- one undo unit, in queue order ------------------------------------------------------------

    /**
     * The batch commits as one compound whose members are in queue order — the parent's add strictly
     * before the child's, which is what makes the deferred containment land correctly.
     */
    @Test
    public void shouldCommitAsOneCompoundInQueueOrder_whenNestingIsBuiltInBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "ordered nesting");
        String outer = group("Outer", 0, 0, 400, 400, null);
        String child = element(actor.getId(), 20, 20, 100, 100, outer);
        dispatcher.endBatch(SESSION, true);

        assertTrue("the batch must commit as a single compound",
                lastDispatched instanceof CompoundCommand);
        CompoundCommand compound = (CompoundCommand) lastDispatched;
        assertEquals("exactly the two queued operations", 2, compound.getCommands().size());
        assertTrue("the parent's add must be queued first",
                containsAdd(compound.getCommands().get(0), outer));
        assertTrue("the child's add must be queued second",
                containsAdd(compound.getCommands().get(1), child));
    }

    /** One undo removes the group and its child together, restoring the pre-batch view. */
    @Test
    public void shouldRestorePreBatchView_whenBatchIsUndoneOnce() throws Exception {
        int before = view.getChildren().size();

        dispatcher.beginBatch(SESSION, "nesting to undo");
        String outer = group("Outer", 0, 0, 400, 400, null);
        String child = element(actor.getId(), 20, 20, 100, 100, outer);
        dispatcher.endBatch(SESSION, true);

        assertEquals("the group landed on the view", before + 1, view.getChildren().size());

        stack.undo();

        assertEquals("one undo must restore the pre-batch child count",
                before, view.getChildren().size());
        assertNull("the group must be gone", find(view, outer));
        assertNull("the nested child must be gone with it", find(view, child));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** True when {@code command} (or anything inside it) is the add that creates {@code id}. */
    private static boolean containsAdd(Object command, String id) {
        if (command instanceof AddGroupToViewCommand add) {
            return id.equals(add.getGroup().getId());
        }
        if (command instanceof AddToViewCommand add) {
            return id.equals(add.getDiagramObject().getId());
        }
        if (command instanceof CompoundCommand compound) {
            for (Object child : compound.getCommands()) {
                if (containsAdd(child, id)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A 1x1 transparent PNG — the smallest thing the archive manager will accept. */
    private static void writeMinimalPng(File file) throws IOException {
        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(png);
        }
    }

    private static class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final List<PropertyChangeListener> listeners = new ArrayList<>();

        void setModels(List<IArchimateModel> models) { this.models = models; }

        @Override
        public List<IArchimateModel> getModels() { return models; }
        @Override
        public void addPropertyChangeListener(PropertyChangeListener listener) {
            listeners.add(listener);
        }
        @Override
        public void removePropertyChangeListener(PropertyChangeListener listener) {
            listeners.remove(listener);
        }
        @Override public IArchimateModel createNewModel() { return null; }
        @Override public void registerModel(IArchimateModel m) {}
        @Override public IArchimateModel openModel(File file) { return null; }
        @Override public void openModel(IArchimateModel m) {}
        @Override public IArchimateModel loadModel(File file) { return null; }
        @Override public IArchimateModel load(File file) throws IOException { return null; }
        @Override public boolean closeModel(IArchimateModel m) throws IOException { return false; }
        @Override public boolean closeModel(IArchimateModel m, boolean askSave) throws IOException { return false; }
        @Override public boolean isModelLoaded(File file) { return false; }
        @Override public boolean isModelDirty(IArchimateModel m) { return false; }
        @Override public boolean saveModel(IArchimateModel m) throws IOException { return false; }
        @Override public boolean saveModelAs(IArchimateModel m) throws IOException { return false; }
        @Override public void saveState() throws IOException {}
        @Override public void firePropertyChange(Object src, String p, Object oldV, Object newV) {}
    }
}
