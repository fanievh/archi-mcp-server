package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import static org.junit.Assume.assumeTrue;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.eclipse.core.runtime.Platform;

import com.archimatetool.editor.model.IArchiveManager;
import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IAssociationRelationship;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.response.dto.AddToViewResultDto;
import net.vheerden.archi.mcp.response.dto.BatchSummaryDto;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;

/**
 * Pins that a placement prepared against a container an earlier operation then removed declines
 * rather than landing somewhere unreachable — and that the response stops claiming what the
 * decline undid.
 *
 * <p>A placement resolves its target container while the request is prepared. On the deferred
 * paths — a queued batch, or a multi-operation bulk request — every operation is prepared before
 * any of them runs, so an operation earlier in the same request can delete the view or take the
 * group off it afterwards. The removal is legitimate at its own turn, which is why no prepare-time
 * check on either operation can see the conflict. Without a re-check the add still runs, attaching
 * a real object to a container that no longer reaches the model — unsaved, gone on reload — while
 * the response hands back a fresh id for it, and for {@code add-to-view} a set of ancestor
 * rectangles as well.</p>
 *
 * <h2>Execution model</h2>
 *
 * <p>These tests drive a real {@link CommandStack} over a rebuilt compound, because what a queued
 * command does at commit is the property under test. The guard is itself a {@link CompoundCommand}
 * and must be rebuilt through its own factory rather than flattened, or the re-check under test is
 * dropped and every one of these tests passes for the wrong reason. Skip reasons are read off the
 * command objects the queue holds, so the rebuild also has to happen in place.</p>
 */
public class DeferredPlacementDeclineTest {

    private static final String SESSION = "deferred-placement-session";

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private CommandStack stack;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private IBusinessActor actorA;
    private IBusinessActor actorB;
    private IAssociationRelationship relationship;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Deferred Placement Fixture");
        model.setId("model-deferred-placement");
        model.setDefaults();

        IFolder business = model.getFolder(FolderType.BUSINESS);
        actorA = factory.createBusinessActor();
        actorA.setId("actor-a");
        actorA.setName("Actor A");
        business.getElements().add(actorA);

        actorB = factory.createBusinessActor();
        actorB.setId("actor-b");
        actorB.setName("Actor B");
        business.getElements().add(actorB);

        relationship = factory.createAssociationRelationship();
        relationship.setId("rel-1");
        relationship.setName("Rel One");
        relationship.setSource(actorA);
        relationship.setTarget(actorB);
        model.getFolder(FolderType.RELATIONS).getElements().add(relationship);

        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Target View");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        stubModelManager.setModels(List.of(model));

        stack = new CommandStack();
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                stack.execute(new AgentAuthoredCompoundCommand(toPlainCompound(command)));
            }
            @Override
            protected void dispatchCommand(Command command) {
                stack.execute(new AgentAuthoredCompoundCommand(toPlainCompound(command)));
            }
            private Command toPlainCompound(Command command) {
                // Rebuilt through the guard's own factory: flattening it into a plain compound
                // would drop the execution-time re-check these tests exist to exercise, and
                // rebuilding it into a COPY would leave the queued guard's reason unset, so the
                // commit summary would report a silent success for an operation that declined.
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

    // ---- add-to-view, the site that also reports collateral ------------------------------------

    /**
     * The deleted-view arm. Queued behind a delete of the very view it names, the add must decline
     * and say so, rather than attaching an object to a view no longer in the model.
     */
    @Test
    public void shouldNotAddToView_whenTheViewWasDeletedEarlierInTheSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "delete the view, then add to it");
        accessor.deleteView(SESSION, view.getId());
        accessor.addToView(SESSION, view.getId(), actorA.getId(), 10, 10, 120, 55,
                false, null, null, null);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals("the add must decline, naming itself once", 1,
                skipped(summary).size());
        assertTrue("the reason must say it was not added to the view, not that it was not "
                + "created: " + skipped(summary).get(0),
                skipped(summary).get(0).contains("was not added to the view"));
        assertTrue("and it must name the view it was to land in: "
                + skipped(summary).get(0),
                skipped(summary).get(0).contains("Target View"));
        assertEquals("nothing may have been added to the detached view", 0,
                view.getChildren().size());
    }

    /**
     * The removed-group arm. The view survives; the container inside it does not. Same decline,
     * because what the placement was prepared against is the group.
     */
    @Test
    public void shouldNotAddToView_whenTheParentGroupWasRemovedEarlierInTheSameBatch()
            throws Exception {
        String groupId = accessor.addGroupToView(SESSION, view.getId(), "Holder",
                0, 0, 300, 300, null, null, null).entity().viewObjectId();

        dispatcher.beginBatch(SESSION, "remove the group, then add into it");
        accessor.removeFromView(SESSION, view.getId(), groupId);
        accessor.addToView(SESSION, view.getId(), actorA.getId(), 10, 10, 120, 55,
                false, groupId, null, null);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals("the add must decline exactly once", 1, skipped(summary).size());
        assertTrue("the reason must name the group it was to land in: "
                + skipped(summary).get(0),
                skipped(summary).get(0).contains("Holder"));
        assertEquals("the group must be gone from the view", 0, view.getChildren().size());
    }

    /**
     * The bulk path for the same defect, and the collateral retraction with it. Here — unlike the
     * queued path — the compound has already run when the response is built, so an operation that
     * declined must not still be reporting ancestors it grew.
     */
    @Test
    public void shouldRetractReportedAncestors_whenABulkAddToViewDeclined() {
        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("delete-view", Map.of("viewId", view.getId())),
                new BulkOperation("add-to-view", Map.of(
                        "viewId", view.getId(), "elementId", actorA.getId(),
                        "x", 10, "y", 10, "width", 120, "height", 55))),
                "delete the view, then add to it", false);

        assertEquals("the declined add must be named once", 1, result.skippedOperations().size());
        assertFalse("a bulk call whose operation declined is not a wholly successful one",
                result.allSucceeded());
        for (var op : result.operations()) {
            if ("add-to-view".equals(op.tool())) {
                assertTrue("an operation that declined grew nothing, so it must not still be "
                        + "naming ancestors it resized", op.resizedAncestors().isEmpty());
                assertTrue("nor objects it displaced", op.movedObjects().isEmpty());
            }
        }
    }

    // ---- the four container-shaped cheap sites -------------------------------------------------

    @Test
    public void shouldNotAddGroupToView_whenTheViewWasDeletedEarlierInTheSameBatch()
            throws Exception {
        assertDeclinedInBatch(() -> accessor.addGroupToView(SESSION, view.getId(), "Late Group",
                0, 0, 200, 200, null, null, null), "Late Group");
    }

    @Test
    public void shouldNotAddNoteToView_whenTheViewWasDeletedEarlierInTheSameBatch()
            throws Exception {
        assertDeclinedInBatch(() -> accessor.addNoteToView(SESSION, view.getId(), "a note",
                null, null, 0, 0, 185, 80, null, null, null), "note");
    }

    @Test
    public void shouldNotAddViewReferenceToView_whenTheViewWasDeletedEarlierInTheSameBatch()
            throws Exception {
        IArchimateDiagramModel other = factory.createArchimateDiagramModel();
        other.setId("view-2");
        other.setName("Referenced View");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(other);

        assertDeclinedInBatch(() -> accessor.addViewReferenceToView(SESSION, view.getId(),
                other.getId(), 0, 0, 120, 55, null, null), "Referenced View");
    }

    /**
     * Assumed out headlessly for the same reason its sibling pin is: a usable {@code imagePath} has
     * to come from an {@link IArchiveManager}, which only the PDE/OSGi runtime can create. The
     * guard itself is the same one the four tests above exercise — this pins that it is reached
     * through this prepare too, in the lane that can run it.
     */
    @Test
    public void shouldNotAddImageToView_whenTheViewWasDeletedEarlierInTheSameBatch()
            throws Exception {
        assumeTrue("requires PDE/OSGi runtime for IArchiveManager", Platform.isRunning());

        IArchiveManager archiveManager = IArchiveManager.FACTORY.createArchiveManager(model);
        model.setAdapter(IArchiveManager.class, archiveManager);
        File png = tempFolder.newFile("known.png");
        writeMinimalPng(png);
        String imagePath = archiveManager.addImageFromFile(png);

        assertDeclinedInBatch(() -> accessor.addImageToView(SESSION, view.getId(), imagePath,
                0, 0, 120, 55, null, null, null, null), null);
    }

    // ---- add-connection-to-view: two endpoints, and two prepares -------------------------------

    /**
     * A removed SOURCE must produce one line for the operation, not one per endpoint — the guards
     * nest, and only the endpoint that actually went missing speaks.
     */
    @Test
    public void shouldNotAddConnection_whenTheSourceViewObjectWasRemovedEarlierInTheSameBatch()
            throws Exception {
        assertConnectionDeclinedOnce(true);
    }

    /** The same for a removed TARGET, which the guard for the source alone would never catch. */
    @Test
    public void shouldNotAddConnection_whenTheTargetViewObjectWasRemovedEarlierInTheSameBatch()
            throws Exception {
        assertConnectionDeclinedOnce(false);
    }

    /**
     * The other prepare. A bulk operation whose source, target and relationship arrive as
     * back-references is routed through {@code prepareAddConnectionToViewDirect}, a different
     * method building a different command object — so a guard placed on the standalone prepare
     * alone leaves this path wide open. That asymmetry is exactly what the path-parity axis exists
     * to catch, and it cannot be seen by testing either path on its own.
     */
    @Test
    public void shouldNotAddConnection_whenABackReferencedEndpointWasRemovedInTheSameBulkCall() {
        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("add-to-view", Map.of(
                        "viewId", view.getId(), "elementId", actorA.getId(),
                        "x", 10, "y", 10, "width", 120, "height", 55)),
                new BulkOperation("add-to-view", Map.of(
                        "viewId", view.getId(), "elementId", actorB.getId(),
                        "x", 200, "y", 10, "width", 120, "height", 55)),
                new BulkOperation("delete-view", Map.of("viewId", view.getId())),
                new BulkOperation("add-connection-to-view", Map.of(
                        "viewId", view.getId(), "relationshipId", relationship.getId(),
                        "sourceViewObjectId", "$0.id", "targetViewObjectId", "$1.id"))),
                "add both ends, delete the view, then connect them", false);

        assertFalse("a bulk call whose operation declined is not a wholly successful one",
                result.allSucceeded());
        assertTrue("the back-referenced connection must decline: "
                + result.skippedOperations(),
                result.skippedOperations().stream().anyMatch(r -> r.contains("Rel One")));
        assertEquals("and it must contribute exactly one line for the one operation, however "
                + "many of its endpoints went missing", 1,
                result.skippedOperations().stream().filter(r -> r.contains("Rel One")).count());
    }

    // ---- creates into a folder the same request removes ----------------------------------------

    /**
     * A view created into a folder an earlier operation deleted. No cascade and no collateral, so
     * this is the plain shape of the same defect — the guard already in the tree, one wrap.
     */
    @Test
    public void shouldNotCreateView_whenTheTargetFolderWasDeletedEarlierInTheSameBatch()
            throws Exception {
        IFolder doomed = newFolder("Doomed Views", "folder-doomed-views", FolderType.DIAGRAMS);

        dispatcher.beginBatch(SESSION, "delete the folder, then create a view in it");
        accessor.deleteFolder(SESSION, doomed.getId(), false);
        accessor.createView(SESSION, "Late View", null, doomed.getId(), null);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals("the create must decline exactly once", 1, skipped(summary).size());
        assertTrue("naming the view that was not created: " + skipped(summary).get(0),
                skipped(summary).get(0).contains("Late View"));
        assertTrue("and the folder it was to land in: " + skipped(summary).get(0),
                skipped(summary).get(0).contains("Doomed Views"));
    }

    /** The same for a folder created under a parent folder the same request deletes. */
    @Test
    public void shouldNotCreateFolder_whenTheParentFolderWasDeletedEarlierInTheSameBatch()
            throws Exception {
        IFolder doomed = newFolder("Doomed Parent", "folder-doomed-parent", FolderType.BUSINESS);

        dispatcher.beginBatch(SESSION, "delete the parent, then create a folder under it");
        accessor.deleteFolder(SESSION, doomed.getId(), false);
        accessor.createFolder(SESSION, doomed.getId(), "Late Folder", null, null);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals("the create must decline exactly once", 1, skipped(summary).size());
        assertTrue("naming the folder that was not created: " + skipped(summary).get(0),
                skipped(summary).get(0).contains("Late Folder"));
    }

    /** Both again on the other deferred path, where the compound runs inside the call. */
    @Test
    public void shouldNotCreateViewOrFolder_whenABulkCallDeletesTheirParentFirst() {
        IFolder doomed = newFolder("Doomed Both", "folder-doomed-both", FolderType.DIAGRAMS);

        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("delete-folder", Map.of("folderId", doomed.getId())),
                new BulkOperation("create-view", Map.of(
                        "name", "Bulk Late View", "folderId", doomed.getId())),
                new BulkOperation("create-folder", Map.of(
                        "parentId", doomed.getId(), "name", "Bulk Late Folder"))),
                "delete the folder, then create into it", false);

        assertEquals("both creates must decline", 2, result.skippedOperations().size());
        assertFalse("and the call must not report itself wholly successful",
                result.allSucceeded());
    }

    // ---- connections a placement draws for itself ----------------------------------------------

    /**
     * The sixth path, and the one the guard rollout missed: {@code add-to-view} with
     * {@code autoConnect} draws connections of its own, to OTHER view objects it found by scanning
     * the view at prepare time. Guarding the new object's parent container says nothing about those
     * endpoints, so a queued {@code remove-from-view} of one left the connection connecting into a
     * detached object — created, unreachable from the model root, and reported as a success.
     *
     * <p>Only reachable from the standalone tool: the bulk arm forces {@code autoConnect} false.</p>
     */
    @Test
    public void shouldNotDrawAnAutoConnection_whenItsOtherEndpointWasRemovedInTheSameBatch()
            throws Exception {
        String aId = accessor.addToView(SESSION, view.getId(), actorA.getId(),
                10, 10, 120, 55, false, null, null, null).entity().viewObject().viewObjectId();

        dispatcher.beginBatch(SESSION, "remove one end, then auto-connect to it");
        accessor.removeFromView(SESSION, view.getId(), aId);
        accessor.addToView(SESSION, view.getId(), actorB.getId(), 200, 10, 120, 55,
                true, null, null, null);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals("the auto-connection must decline and say so", 1, skipped(summary).size());
        assertTrue("naming the relationship it did not draw: " + skipped(summary).get(0),
                skipped(summary).get(0).contains("Rel One"));
        assertTrue("the placement itself must still have landed — only the connection declined",
                findObject(view.getChildren(), actorB.getId()) != null);
        assertTrue("and no connection may hang off the detached object",
                actorA.getReferencingDiagramObjects().stream()
                        .noneMatch(o -> !o.getSourceConnections().isEmpty()
                                || !o.getTargetConnections().isEmpty()));
    }

    /** {@code clone-view} resolves a target folder at prepare time exactly as create-view does. */
    @Test
    public void shouldNotCloneView_whenTheTargetFolderWasDeletedEarlierInTheSameBatch()
            throws Exception {
        IFolder doomed = newFolder("Doomed Clones", "folder-doomed-clones", FolderType.DIAGRAMS);

        dispatcher.beginBatch(SESSION, "delete the folder, then clone into it");
        accessor.deleteFolder(SESSION, doomed.getId(), false);
        accessor.cloneView(SESSION, view.getId(), "Late Clone", doomed.getId());
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals("the clone must decline exactly once", 1, skipped(summary).size());
        assertTrue("naming the clone that was not created: " + skipped(summary).get(0),
                skipped(summary).get(0).contains("Late Clone"));
    }

    // ---- the bulk arm of every guarded placement -----------------------------------------------

    @Test
    public void shouldNotAddGroupToView_whenABulkCallDeletesTheViewFirst() {
        assertDeclinedInBulk("add-group-to-view", Map.of(
                "viewId", view.getId(), "label", "Bulk Late Group",
                "x", 0, "y", 0, "width", 200, "height", 200), "Bulk Late Group");
    }

    @Test
    public void shouldNotAddNoteToView_whenABulkCallDeletesTheViewFirst() {
        assertDeclinedInBulk("add-note-to-view", Map.of(
                "viewId", view.getId(), "content", "bulk note",
                "x", 0, "y", 0, "width", 185, "height", 80), "note");
    }

    @Test
    public void shouldNotAddViewReferenceToView_whenABulkCallDeletesTheViewFirst() {
        IArchimateDiagramModel other = factory.createArchimateDiagramModel();
        other.setId("view-ref-bulk");
        other.setName("Bulk Referenced View");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(other);

        assertDeclinedInBulk("add-view-reference-to-view", Map.of(
                "viewId", view.getId(), "referencedViewId", other.getId(),
                "x", 0, "y", 0, "width", 120, "height", 55), "Bulk Referenced View");
    }

    /** Assumed out headlessly for the same archive-manager reason as its batch twin. */
    @Test
    public void shouldNotAddImageToView_whenABulkCallDeletesTheViewFirst() throws Exception {
        assumeTrue("requires PDE/OSGi runtime for IArchiveManager", Platform.isRunning());

        IArchiveManager archiveManager = IArchiveManager.FACTORY.createArchiveManager(model);
        model.setAdapter(IArchiveManager.class, archiveManager);
        File png = tempFolder.newFile("bulk.png");
        writeMinimalPng(png);
        String imagePath = archiveManager.addImageFromFile(png);

        assertDeclinedInBulk("add-image-to-view", Map.of(
                "viewId", view.getId(), "imagePath", imagePath,
                "x", 0, "y", 0, "width", 120, "height", 55), null);
    }

    /**
     * The remedy has to read as English for every container kind the call sites supply, and one of
     * them takes "an". Pinned on a placement into an ELEMENT parent, which is the only kind that
     * breaks and the one every fixture happened to miss until the live gate read the sentence.
     */
    @Test
    public void shouldSayAnElement_whenTheContainerKindTakesThatArticle() throws Exception {
        String hostId = accessor.addToView(SESSION, view.getId(), actorA.getId(),
                0, 0, 300, 300, false, null, null, null).entity().viewObject().viewObjectId();

        dispatcher.beginBatch(SESSION, "remove the element host, then place inside it");
        accessor.removeFromView(SESSION, view.getId(), hostId);
        accessor.addToView(SESSION, view.getId(), actorB.getId(), 10, 10, 50, 30,
                false, hostId, null, null);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals("the placement must decline", 1, skipped(summary).size());
        String reason = skipped(summary).get(0);
        assertTrue("the container kind must be the element one: " + reason,
                reason.contains("the element 'Actor A'"));
        assertTrue("and the remedy must not read 'a element': " + reason,
                !reason.contains("a element"));
        assertTrue("it must read 'an element': " + reason, reason.contains("an element"));
    }

    // ---- non-regressions -----------------------------------------------------------------------

    /**
     * The guard is a compound on purpose: both walks over a queued command tree recurse into
     * compounds and nothing else, so a plain decorator would hide the wrapped add from the
     * read-back that lets a later operation in the same batch address it by id. Asserted by
     * running that idiom rather than by reading the walkers.
     */
    @Test
    public void shouldStillResolveAQueuedAdd_whenItIsWrappedInTheGuard() throws Exception {
        dispatcher.beginBatch(SESSION, "queue a group, then use it as a parent and as a target");
        String groupId = accessor.addGroupToView(SESSION, view.getId(), "Queued Holder",
                0, 0, 300, 300, null, null, null).entity().viewObjectId();
        accessor.addToView(SESSION, view.getId(), actorA.getId(), 10, 10, 120, 55,
                false, groupId, null, null);
        accessor.updateViewObject(SESSION, groupId, 0, 0, 400, 400, null, null, null, null);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertTrue("nothing may decline here — every container is live: "
                + skipped(summary), skipped(summary).isEmpty());
        IDiagramModelGroup group = (IDiagramModelGroup) findObject(groupId);
        assertNotNull("the wrapped group must still have been created", group);
        assertEquals("the queued add must have landed inside the queued group", 1,
                group.getChildren().size());
        assertEquals("and the queued group must still be addressable as an update target",
                400, group.getBounds().getWidth());
    }

    /**
     * The immediate path is every call outside a batch or a bulk request, and the guard must be
     * invisible there: the container is attached, so nothing declines and the collateral report is
     * unchanged. Pinned with a real icon-band growth so the field under discussion is non-empty.
     */
    @Test
    public void shouldReportResizedAncestorsUnchanged_whenTheContainerIsAttached() {
        String parentId = accessor.addToView(SESSION, view.getId(), actorA.getId(),
                0, 0, 300, 300, false, null, null,
                new ImageParams(null, "bottom-left", null))
                .entity().viewObject().viewObjectId();

        AddToViewResultDto dto = accessor.addToView(SESSION, view.getId(), actorB.getId(),
                0, 270, 50, 30, false, parentId, null, null).entity();

        IDiagramModelObject parent = findObject(parentId);
        assertEquals("fixture guard: the container must actually grow, or the field under test "
                + "is empty and this pins nothing", 324, parent.getBounds().getHeight());
        assertEquals("the immediate path must report exactly what it reported before the guard — "
                + "the container, and nothing above it", 1, dto.resizedAncestors().size());
        assertEquals("named by the object it grew", parentId,
                dto.resizedAncestors().get(0).viewObjectId());
        assertEquals("carrying the height the model actually holds", 324,
                dto.resizedAncestors().get(0).newHeight());
    }

    /**
     * A declined command must stay declined across undo and redo. {@code undo()} is inert when
     * nothing ran, and {@code redo()} re-asks the question rather than replaying a decision — so the
     * object it refused to create must still not exist after a full round trip. Asserted by driving
     * the stack, because "redo re-checks" is a property of the code that a reading cannot confirm
     * actually holds through GEF's own compound.
     */
    @Test
    public void shouldStayDeclined_whenTheBatchIsUndoneAndRedone() throws Exception {
        dispatcher.beginBatch(SESSION, "delete the view, then add to it");
        accessor.deleteView(SESSION, view.getId());
        accessor.addToView(SESSION, view.getId(), actorA.getId(), 10, 10, 120, 55,
                false, null, null, null);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);
        assertEquals("fixture guard: the add must have declined, or the redo proves nothing",
                1, skipped(summary).size());

        assertTrue("the batch must be undoable", stack.canUndo());
        stack.undo();
        assertEquals("undo restores the view the batch deleted", 1,
                model.getFolder(FolderType.DIAGRAMS).getElements().size());
        assertEquals("and must not resurrect an object that was never added", 0,
                view.getChildren().size());

        assertTrue("the batch must be redoable", stack.canRedo());
        stack.redo();
        assertEquals("redo re-deletes the view", 0,
                model.getFolder(FolderType.DIAGRAMS).getElements().size());
        assertEquals("and the add must decline again rather than replay as a success", 0,
                view.getChildren().size());
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Queues a delete of the view, then the supplied placement, and asserts it declined once. */
    private void assertDeclinedInBatch(Runnable placement, String expectedSubject)
            throws Exception {
        dispatcher.beginBatch(SESSION, "delete the view, then place into it");
        accessor.deleteView(SESSION, view.getId());
        placement.run();
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals("the placement must decline exactly once", 1,
                skipped(summary).size());
        String reason = skipped(summary).get(0);
        assertTrue("the reason must be the placement wording, not the create one: " + reason,
                reason.contains("was not added to the view"));
        assertTrue("and must name the view: " + reason, reason.contains("Target View"));
        if (expectedSubject != null) {
            assertTrue("and must name what was to be placed: " + reason,
                    reason.contains(expectedSubject));
        }
        assertEquals("nothing may have been placed in the detached view", 0,
                view.getChildren().size());
    }

    /** Removes one endpoint in a batch, then connects across it, and asserts a single reason. */
    private void assertConnectionDeclinedOnce(boolean removeSource) throws Exception {
        String sourceId = accessor.addToView(SESSION, view.getId(), actorA.getId(),
                10, 10, 120, 55, false, null, null, null).entity().viewObject().viewObjectId();
        String targetId = accessor.addToView(SESSION, view.getId(), actorB.getId(),
                200, 10, 120, 55, false, null, null, null).entity().viewObject().viewObjectId();

        dispatcher.beginBatch(SESSION, "remove an endpoint, then connect across it");
        accessor.removeFromView(SESSION, view.getId(), removeSource ? sourceId : targetId);
        accessor.addConnectionToView(SESSION, view.getId(), relationship.getId(),
                sourceId, targetId, null, null, null, null, null);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals("the connection must decline, and contribute exactly ONE line for the one "
                + "operation — the two endpoint guards nest, they do not each report: " + summary
                .skippedOperations(), 1, skipped(summary).size());
        String reason = skipped(summary).get(0);
        assertTrue("the reason must be the endpoint wording, not the container one: " + reason,
                reason.contains("it connects"));
        assertTrue("and must name the relationship that was not drawn: " + reason,
                reason.contains("Rel One"));
    }

    /**
     * The commit summary's skip list, never null.
     *
     * <p>An operation that declined nothing leaves the field unset, and asserting straight through
     * it would make every one of these tests fail with a null dereference before it reached its own
     * assertion — proving that something differs, but never pinning WHICH condition. Normalising
     * here is what lets the failure message name the missing decline.</p>
     */
    /** Deletes the view in a bulk call, then runs the placement, and asserts it declined once. */
    private void assertDeclinedInBulk(String tool, Map<String, Object> params,
            String expectedSubject) {
        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("delete-view", Map.of("viewId", view.getId())),
                new BulkOperation(tool, params)),
                "delete the view, then place into it", false);

        assertEquals("the placement must decline exactly once", 1,
                result.skippedOperations().size());
        String reason = result.skippedOperations().get(0);
        assertTrue("the reason must be the placement wording: " + reason,
                reason.contains("was not added to the view"));
        if (expectedSubject != null) {
            assertTrue("and must name what was to be placed: " + reason,
                    reason.contains(expectedSubject));
        }
        assertFalse("a call whose operation declined is not wholly successful",
                result.allSucceeded());
    }

    private IFolder newFolder(String name, String id, FolderType type) {
        IFolder folder = factory.createFolder();
        folder.setName(name);
        folder.setId(id);
        model.getFolder(type).getFolders().add(folder);
        return folder;
    }

    private static List<String> skipped(BatchSummaryDto summary) {
        return summary.skippedOperations() == null ? List.of() : summary.skippedOperations();
    }

    private IDiagramModelObject findObject(List<?> children, String elementId) {
        for (Object child : children) {
            if (child instanceof IDiagramModelArchimateObject obj
                    && obj.getArchimateElement() != null
                    && elementId.equals(obj.getArchimateElement().getId())) {
                return obj;
            }
        }
        return null;
    }

    private IDiagramModelObject findObject(String viewObjectId) {
        return findIn(view.getChildren(), viewObjectId);
    }

    private IDiagramModelObject findIn(List<?> children, String viewObjectId) {
        for (Object child : children) {
            if (child instanceof IDiagramModelObject obj) {
                if (viewObjectId.equals(obj.getId())) {
                    return obj;
                }
                if (obj instanceof IDiagramModelGroup group) {
                    IDiagramModelObject hit = findIn(group.getChildren(), viewObjectId);
                    if (hit != null) {
                        return hit;
                    }
                }
                if (obj instanceof IDiagramModelArchimateObject element) {
                    IDiagramModelObject hit = findIn(element.getChildren(), viewObjectId);
                    if (hit != null) {
                        return hit;
                    }
                }
            }
        }
        return null;
    }

    /** The smallest byte sequence Archi's archive manager will accept as an image. */
    private static void writeMinimalPng(File file) throws IOException {
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        javax.imageio.ImageIO.write(img, "png", file);
    }

    private static class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final List<PropertyChangeListener> listeners = new ArrayList<>();

        void setModels(List<IArchimateModel> models) { this.models = models; }

        @Override public List<IArchimateModel> getModels() { return models; }
        @Override public void addPropertyChangeListener(PropertyChangeListener listener) {
            listeners.add(listener);
        }
        @Override public void removePropertyChangeListener(PropertyChangeListener listener) {
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
