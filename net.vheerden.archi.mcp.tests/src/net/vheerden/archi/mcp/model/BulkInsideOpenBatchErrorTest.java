package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.Platform;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.model.IArchiveManager;
import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.handlers.HandlerRegistrar;
import net.vheerden.archi.mcp.handlers.SearchHandler;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Nesting bulk-mutate inside an open batch is a supported composition, and an id the batch has
 * queued is addressable from inside it.
 *
 * <p>The rule governs <em>addressability</em> — naming a queued id as an operation's target — not
 * visibility. This arm used to refuse such an id with an error naming the mechanism, on the reading
 * that the two were alternative routes to one capability rather than layers to nest. Two sibling
 * arms already resolved the same id silently, so the refusal was a policy inconsistency rather than
 * a guard, and it is the queued id that is now resolved instead.</p>
 *
 * <p>All three directions are pinned here, and the class cannot be half-updated: an id the batch
 * holds resolves and the update lands, an id that genuinely exists nowhere still gets the ordinary
 * not-found message, and with no batch open at all that message is unchanged. The negative pin is
 * what proves resolving the queue did not widen into resolving anything.</p>
 *
 * <p>Resolving identity alone would not be enough, and one test here says so in geometry rather
 * than in prose: the same edit run through a nested bulk and through a same-batch
 * {@code update-view-object} must leave the model in the same state, parent-fit cascade included.
 * A queued parent that silently fails to grow is a wrong number where there used to be a loud
 * throw, which is the worse of the two.</p>
 */
public class BulkInsideOpenBatchErrorTest {

    private IArchimateFactory factory;
    private ArchiModelAccessorImpl accessor;
    private CommandStack stack;
    private MutationDispatcher dispatcher;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private IArchimateDiagramModel otherView;

    private static final String SESSION = "code-session";

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        StubEditorModelManager stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Bulk Inside Open Batch Fixture");
        model.setId("model-bulk-in-batch");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Nested");
        diagrams.getElements().add(view);

        otherView = factory.createArchimateDiagramModel();
        otherView.setId("view-2");
        otherView.setName("Elsewhere");
        diagrams.getElements().add(otherView);

        IFolder business = model.getFolder(FolderType.BUSINESS);
        IBusinessActor actor = factory.createBusinessActor();
        actor.setId("actor-1");
        actor.setName("Nested Actor");
        business.getElements().add(actor);

        stubModelManager.setModels(List.of(model));

        stack = new CommandStack();
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                stack.execute(toPlainCompound(command));
            }
            @Override
            protected void dispatchCommand(Command command) {
                stack.execute(toPlainCompound(command));
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

    /**
     * The branch the story is about. Asserted on the serialized envelope, because what the agent
     * reads is what changed: the id it harvested from {@code result.preview} now works.
     */
    @Test
    public void shouldResolveTheId_whenANestedBulkTargetsAnObjectTheOpenBatchQueued()
            throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        invokeTool(registry, "begin-batch", Map.of("description", "outer"));
        Map<String, Object> queued = invokeTool(registry, "add-group-to-view", Map.of(
                "viewId", view.getId(), "label", "Queued",
                "x", 0, "y", 0, "width", 200, "height", 200));
        String queuedId = queuedViewObjectId(queued);

        Map<String, Object> envelope = invokeTool(registry, "bulk-mutate", Map.of(
                "operations", List.of(Map.of("tool", "update-view-object",
                        "params", Map.of("viewObjectId", queuedId, "height", 400))),
                "description", "nested bulk"));

        assertTrue("an id the enclosing batch queued must resolve, not raise: " + envelope,
                !envelope.containsKey("error"));
    }

    /**
     * Identity is not the whole of it, and this is the test that says so in geometry.
     *
     * <p>Run the same edit twice over identical fixtures — once through a nested {@code bulk-mutate},
     * once through a same-batch {@code update-view-object} — and the committed model must be
     * indistinguishable, the parent-fit cascade included. Wiring only the object's identity and not
     * the pending-geometry the batch has queued would leave the nested arm resolving the target and
     * then sizing its still-detached parent against the live model, which reports success and grows
     * nothing. That trades a loud throw for a wrong number.</p>
     */
    @Test
    public void shouldLandTheSameGeometryAsASameBatchUpdate_whenTheTargetIsQueued() {
        int[] viaBatch = growAQueuedChildInsideItsQueuedParent(false);
        int[] viaNestedBulk = growAQueuedChildInsideItsQueuedParent(true);

        assertEquals("the queued child must end the same width on both routes",
                viaBatch[0], viaNestedBulk[0]);
        assertEquals("and the same height", viaBatch[1], viaNestedBulk[1]);
        assertEquals("the queued PARENT must have grown identically — this is the half that "
                + "identity-only wiring would silently skip", viaBatch[2], viaNestedBulk[2]);
        assertEquals("and to the same height", viaBatch[3], viaNestedBulk[3]);
        assertTrue("the fixture is only a discriminator if the parent actually had to grow: "
                + viaBatch[2] + "x" + viaBatch[3], viaBatch[3] > 200);
    }

    /**
     * Queue a group, queue a child inside it, then grow the child past the group's bounds so the
     * parent-fit cascade has to find a parent that is itself still detached.
     *
     * @param nested whether the grow runs through a nested bulk-mutate or directly in the batch
     * @return the committed child width/height followed by the committed parent width/height
     */
    private int[] growAQueuedChildInsideItsQueuedParent(boolean nested) {
        String session = nested ? "geometry-nested" : "geometry-batch";
        dispatcher.beginBatch(session, "grow a queued child");
        String parentId = accessor.addGroupToView(session, view.getId(), "Queued Parent",
                500, 0, 200, 200, null, null, null).entity().viewObjectId();
        String childId = accessor.addToView(session, view.getId(), "actor-1",
                10, 10, 120, 55, false, parentId, null, null)
                .entity().viewObject().viewObjectId();

        if (nested) {
            accessor.executeBulk(session, List.of(
                    new net.vheerden.archi.mcp.response.dto.BulkOperation("update-view-object",
                            Map.of("viewObjectId", childId, "width", 250, "height", 250))),
                    "nested bulk", false);
        } else {
            accessor.updateViewObject(session, childId, null, null, 250, 250,
                    null, null, null, null, null, null, null, null);
        }
        dispatcher.endBatch(session, true);

        IDiagramModelObject parent = findDescendant(view, parentId);
        IDiagramModelObject child = findDescendant(view, childId);
        assertNotNull("the batch must have committed the parent", parent);
        assertNotNull("and the child", child);
        return new int[] {
                child.getBounds().getWidth(), child.getBounds().getHeight(),
                parent.getBounds().getWidth(), parent.getBounds().getHeight() };
    }

    /** The object with that id anywhere beneath the container, committed containment only. */
    private static IDiagramModelObject findDescendant(IDiagramModelContainer container, String id) {
        for (IDiagramModelObject child : container.getChildren()) {
            if (id.equals(child.getId())) {
                return child;
            }
            if (child instanceof IDiagramModelContainer nested) {
                IDiagramModelObject hit = findDescendant(nested, id);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /**
     * A same-call back-reference must still win over the enclosing batch's queue. The bulk pass
     * consults its own maps before it consults anything else, so an id this call created resolves
     * to that object and never reaches the queue lookup at all.
     */
    @Test
    public void shouldPreferTheSameCallBackReference_whenABatchIsAlsoOpen() {
        dispatcher.beginBatch("precedence-session", "outer");
        accessor.addGroupToView("precedence-session", view.getId(), "Queued",
                0, 0, 200, 200, null, null, null);

        var result = accessor.executeBulk("precedence-session", List.of(
                new net.vheerden.archi.mcp.response.dto.BulkOperation("add-group-to-view",
                        Map.of("viewId", view.getId(), "label", "Call Scoped",
                                "x", 900, "y", 0, "width", 150, "height", 150)),
                new net.vheerden.archi.mcp.response.dto.BulkOperation("update-view-object",
                        Map.of("viewObjectId", "$0.id", "height", 320))),
                "back-reference beats the outer queue", false);
        String callScopedId = result.operations().get(0).entityId();
        dispatcher.endBatch("precedence-session", true);

        IDiagramModelObject callScoped = findDescendant(view, callScopedId);
        assertNotNull("the call-scoped group must have been committed", callScoped);
        assertEquals("the back-reference must have addressed the object THIS call created",
                320, callScoped.getBounds().getHeight());
    }

    @Test
    public void shouldResolveTheNamedOperation_whenInterleavedBackReferencesRunInsideAnOpenBatch() {
        // The sibling above settles PRECEDENCE (call-scoped state beats the queue) using $0, where
        // the operation-index framing and an append-position framing coincide. This one settles the
        // INDEX inside that same state. The host is committed BEFORE the batch opens, so the call
        // can begin with two operations that create nothing; both references below therefore name a
        // slot an append-position framing would resolve to a different object.
        //
        // Scope: this covers a reference to an object THIS call created while a batch is open.
        // Addressing an object the enclosing batch QUEUED is a separate question, already covered
        // by the queued-id tests elsewhere in this class.
        String hostId = accessor.addGroupToView("interleaved-session", view.getId(), "Host",
                0, 0, 1200, 1200, null, null, null).entity().viewObjectId();

        dispatcher.beginBatch("interleaved-session", "outer");

        var result = accessor.executeBulk("interleaved-session", List.of(
                // 0 - creates nothing
                new net.vheerden.archi.mcp.response.dto.BulkOperation("update-view-object",
                        Map.of("viewObjectId", hostId, "height", 1300)),
                // 1 - creates nothing ("id", not "elementId", is this tool's key)
                new net.vheerden.archi.mcp.response.dto.BulkOperation("update-element",
                        Map.of("id", "actor-1", "documentation", "probe")),
                // 2 - the first create, referenced below
                new net.vheerden.archi.mcp.response.dto.BulkOperation("add-group-to-view",
                        Map.of("viewId", view.getId(), "label", "Outer Zone",
                                "parentViewObjectId", hostId,
                                "x", 10, "y", 10, "width", 900, "height", 900)),
                // 3 - creates nothing, and sits between two referenced creates
                new net.vheerden.archi.mcp.response.dto.BulkOperation("update-view-object",
                        Map.of("viewObjectId", hostId, "width", 1400)),
                // 4 - nests into op 2
                new net.vheerden.archi.mcp.response.dto.BulkOperation("add-group-to-view",
                        Map.of("viewId", view.getId(), "label", "Inner Zone",
                                "parentViewObjectId", "$2.id",
                                "x", 20, "y", 20, "width", 600, "height", 600)),
                // 5 - nests into op 4
                new net.vheerden.archi.mcp.response.dto.BulkOperation("add-to-view",
                        Map.of("viewId", view.getId(), "elementId", "actor-1",
                                "parentViewObjectId", "$4.id",
                                "x", 30, "y", 30, "width", 100, "height", 100))),
                "interleaved back-references inside an open batch", false);

        String outerZone = result.operations().get(2).entityId();
        String innerZone = result.operations().get(4).entityId();
        String leaf = result.operations().get(5).entityId();
        dispatcher.endBatch("interleaved-session", true);

        // Containment after the batch commits is the ground truth; inside the window the response
        // is a projection by construction, so it is not the thing to assert on.
        assertEquals("the op-4 group must sit in the object operation 2 created",
                outerZone, containerIdOf(findDescendant(view, innerZone)));
        assertEquals("the placed element must sit in the object operation 4 created",
                innerZone, containerIdOf(findDescendant(view, leaf)));
    }

    /**
     * The id of an object's container, or a printable form of the container when it carries no id.
     * Read rather than cast: the defect these back-reference pins target drops an optional parent
     * and lands the child at the VIEW ROOT, and a cast to a nested-object type would fail there
     * with a ClassCastException instead of a readable expected-versus-actual difference.
     */
    private static String containerIdOf(IDiagramModelObject obj) {
        org.eclipse.emf.ecore.EObject parent = obj.eContainer();
        return parent instanceof IIdentifier identified ? identified.getId() : String.valueOf(parent);
    }

    /**
     * The negative direction, and the reason resolving the queue is not the same as resolving
     * anything. An id that genuinely exists nowhere must still fail, with the message it always
     * had, from inside an open batch.
     *
     * <p>This pin used to carry a second assertion — that the failure was not blamed on the batch —
     * against a message only the deleted refusal could produce. With that producer gone the
     * assertion could no longer fail, and an assertion that cannot fail is not a guard. What is
     * load-bearing now is the direction: the queue lookup must answer for ids the batch holds and
     * <em>only</em> those, so a miss has to stay a miss rather than widening into a resolution.</p>
     */
    @Test
    public void shouldStillReportNotFound_whenTheIdExistsNowhereAtAll() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        invokeTool(registry, "begin-batch", Map.of("description", "outer"));

        Map<String, Object> envelope = invokeTool(registry, "bulk-mutate", Map.of(
                "operations", List.of(Map.of("tool", "update-view-object",
                        "params", Map.of("viewObjectId", "id-does-not-exist", "height", 400))),
                "description", "nested bulk, unknown id"));

        assertTrue("an id nothing holds must still fail rather than resolve: " + envelope,
                envelope.containsKey("error"));
        String message = errorField(envelope, "message");
        assertTrue("and must still be reported as not found: " + message,
                message.contains("View object not found: id-does-not-exist"));
    }

    /** Outside a batch the discriminator must stay silent — the message is unchanged. */
    @Test
    public void shouldReportNotFound_whenThereIsNoOpenBatchAtAll() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        Map<String, Object> envelope = invokeTool(registry, "bulk-mutate", Map.of(
                "operations", List.of(Map.of("tool", "update-view-object",
                        "params", Map.of("viewObjectId", "id-does-not-exist", "height", 400))),
                "description", "no batch open"));

        String message = errorField(envelope, "message");
        assertTrue("no batch open means the ordinary not-found message: " + message,
                message.contains("View object not found: id-does-not-exist"));
    }

    /**
     * The same branch at the accessor boundary rather than through the envelope, so nothing about
     * this closure depends on the handler layer. A nested bulk naming a queued id is accepted and
     * queued into the enclosing batch like any other operation, which is why the result is batched
     * and carries no execution verdict.
     */
    @Test
    public void shouldQueueIntoTheEnclosingBatch_whenANestedBulkTargetsAQueuedObject() {
        dispatcher.beginBatch("code-session", "outer");
        var queued = accessor.addGroupToView("code-session", view.getId(), "Queued",
                0, 0, 200, 200, null, null, null);
        String queuedId = queued.entity().viewObjectId();

        var result = accessor.executeBulk("code-session", List.of(
                new net.vheerden.archi.mcp.response.dto.BulkOperation("update-view-object",
                        Map.of("viewObjectId", queuedId, "height", 400))),
                "nested bulk", false);

        assertNotNull("a nested bulk naming a queued id must be accepted", result);
        assertEquals("and must carry the one operation it was given",
                1, result.operations().size());
    }

    /**
     * What the client sees when the id really is absent. {@code executeBulk} re-wraps every
     * per-operation failure as {@code BULK_VALIDATION_FAILED}, so whatever code the underlying
     * lookup raised never reaches the caller — the message is the whole of the meaning. Pinned at
     * the accessor boundary because the envelope test above cannot see the code.
     */
    @Test
    public void shouldSurfaceAsABulkValidationFailure_whenTheIdExistsNowhere() {
        dispatcher.beginBatch("code-session", "outer");

        try {
            accessor.executeBulk("code-session", List.of(
                    new net.vheerden.archi.mcp.response.dto.BulkOperation("update-view-object",
                            Map.of("viewObjectId", "id-does-not-exist", "height", 400))),
                    "nested bulk", false);
            throw new AssertionError("expected the nested bulk to be rejected");
        } catch (ModelAccessException e) {
            assertTrue("the message must report the id as not found: " + e.getMessage(),
                    e.getMessage().contains("View object not found: id-does-not-exist"));
            assertEquals("bulk re-wraps every per-operation failure, so this is the code the "
                    + "client sees whatever the rejection was raised with",
                    net.vheerden.archi.mcp.response.ErrorCode.BULK_VALIDATION_FAILED,
                    e.getErrorCode());
        }
    }

    // ---- the five add-* arms and update-view-connection ----------------------------------------
    //
    // Each of these arms fills a slot from the bulk call's own back-reference maps and, until now,
    // stopped there — while the single-tool entry beside it already passed the enclosing batch's
    // queued value into that very slot. The asymmetry was invisible from outside: the same id, on
    // the same tool, resolved when called directly and reported not-found when called through a
    // nested bulk. Every slot below is pinned in BOTH directions, because resolving the queue must
    // not widen into resolving anything.

    /** The parent slot, on the arm that places an element. */
    @Test
    public void shouldNestIntoAQueuedGroup_whenANestedBulkAddsAnElement() {
        dispatcher.beginBatch(SESSION, "outer");
        String parent = accessor.addGroupToView(SESSION, view.getId(), "Queued Parent",
                0, 0, 400, 400, null, null, null).entity().viewObjectId();

        String child = bulkEntityId(op("add-to-view", Map.of(
                "viewId", view.getId(), "elementId", "actor-1", "parentViewObjectId", parent,
                "x", 10, "y", 10, "width", 120, "height", 55)));
        dispatcher.endBatch(SESSION, true);

        assertChildOf(parent, child);
    }

    /** The view slot, on the same arm: a view whose create has not run is still a placement target. */
    @Test
    public void shouldPlaceOnAQueuedView_whenANestedBulkAddsAnElement() {
        dispatcher.beginBatch(SESSION, "outer");
        String queuedView = accessor.createView(SESSION, "Queued View", null, null, null)
                .entity().id();

        String placed = bulkEntityId(op("add-to-view", Map.of(
                "viewId", queuedView, "elementId", "actor-1",
                "x", 10, "y", 10, "width", 120, "height", 55)));
        dispatcher.endBatch(SESSION, true);

        assertNotNull("the element must have landed on the view the batch queued",
                findDescendant(viewById(queuedView), placed));
    }

    /** The element slot: an element whose create has not run is still placeable. */
    @Test
    public void shouldPlaceAQueuedElement_whenANestedBulkAddsItToAView() {
        dispatcher.beginBatch(SESSION, "outer");
        String queuedElement = accessor.createElement(SESSION, "BusinessActor", "Queued Actor",
                null, null, null, null).entity().id();

        String placed = bulkEntityId(op("add-to-view", Map.of(
                "viewId", view.getId(), "elementId", queuedElement,
                "x", 600, "y", 600, "width", 120, "height", 55)));
        dispatcher.endBatch(SESSION, true);

        assertNotNull("the queued element must have been placed", findDescendant(view, placed));
    }

    /** Both slots of the group arm. */
    @Test
    public void shouldResolveBothSlots_whenANestedBulkAddsAGroup() {
        dispatcher.beginBatch(SESSION, "outer");
        String parent = accessor.addGroupToView(SESSION, view.getId(), "Queued Parent",
                0, 0, 400, 400, null, null, null).entity().viewObjectId();
        String queuedView = accessor.createView(SESSION, "Queued View", null, null, null)
                .entity().id();

        String nested = bulkEntityId(op("add-group-to-view", Map.of(
                "viewId", view.getId(), "label", "Nested", "parentViewObjectId", parent,
                "x", 10, "y", 10, "width", 100, "height", 100)));
        String onQueuedView = bulkEntityId(op("add-group-to-view", Map.of(
                "viewId", queuedView, "label", "On Queued View",
                "x", 10, "y", 10, "width", 100, "height", 100)));
        dispatcher.endBatch(SESSION, true);

        assertChildOf(parent, nested);
        assertNotNull("the group must have landed on the queued view",
                findDescendant(viewById(queuedView), onQueuedView));
    }

    /** Both slots of the note arm. */
    @Test
    public void shouldResolveBothSlots_whenANestedBulkAddsANote() {
        dispatcher.beginBatch(SESSION, "outer");
        String parent = accessor.addGroupToView(SESSION, view.getId(), "Queued Parent",
                0, 0, 400, 400, null, null, null).entity().viewObjectId();
        String queuedView = accessor.createView(SESSION, "Queued View", null, null, null)
                .entity().id();

        String nested = bulkEntityId(op("add-note-to-view", Map.of(
                "viewId", view.getId(), "content", "Nested", "parentViewObjectId", parent,
                "x", 10, "y", 10, "width", 150, "height", 60)));
        String onQueuedView = bulkEntityId(op("add-note-to-view", Map.of(
                "viewId", queuedView, "content", "On Queued View",
                "x", 10, "y", 10, "width", 150, "height", 60)));
        dispatcher.endBatch(SESSION, true);

        assertChildOf(parent, nested);
        assertNotNull("the note must have landed on the queued view",
                findDescendant(viewById(queuedView), onQueuedView));
    }

    /**
     * The view-reference arm carries TWO view ids and only one of them resolved: its
     * {@code referencedViewId} has always coalesced against the queue inside the prepare, while the
     * {@code viewId} naming the view it is placed ON did not. Two ids, two slots, both pinned.
     */
    @Test
    public void shouldResolveBothSlots_whenANestedBulkAddsAViewReference() {
        dispatcher.beginBatch(SESSION, "outer");
        String parent = accessor.addGroupToView(SESSION, view.getId(), "Queued Parent",
                0, 0, 400, 400, null, null, null).entity().viewObjectId();
        String queuedHost = accessor.createView(SESSION, "Queued Host", null, null, null)
                .entity().id();

        String nested = bulkEntityId(op("add-view-reference-to-view", Map.of(
                "viewId", view.getId(), "referencedViewId", otherView.getId(),
                "parentViewObjectId", parent,
                "x", 10, "y", 10, "width", 150, "height", 60)));
        String onQueuedHost = bulkEntityId(op("add-view-reference-to-view", Map.of(
                "viewId", queuedHost, "referencedViewId", otherView.getId(),
                "x", 10, "y", 10, "width", 150, "height", 60)));
        dispatcher.endBatch(SESSION, true);

        assertChildOf(parent, nested);
        assertNotNull("the reference must have landed on the queued host view",
                findDescendant(viewById(queuedHost), onQueuedHost));
    }

    /**
     * The image arm needs a real {@link IArchiveManager}, which only the PDE/OSGi runtime can
     * create, so it assumes out headlessly exactly as its sibling pin in
     * {@code BatchQueuedParentContainerTest} does. The other four add-* arms are unconditional.
     */
    @Test
    public void shouldNestIntoAQueuedGroup_whenANestedBulkAddsAnImage() throws Exception {
        assumeTrue("requires PDE/OSGi runtime for IArchiveManager", Platform.isRunning());
        IArchiveManager archiveManager = IArchiveManager.FACTORY.createArchiveManager(model);
        model.setAdapter(IArchiveManager.class, archiveManager);
        File png = File.createTempFile("known", ".png");
        png.deleteOnExit();
        writeMinimalPng(png);
        String imagePath = archiveManager.addImageFromFile(png);

        dispatcher.beginBatch(SESSION, "outer");
        String parent = accessor.addGroupToView(SESSION, view.getId(), "Queued Parent",
                0, 0, 400, 400, null, null, null).entity().viewObjectId();
        String queuedView = accessor.createView(SESSION, "Queued View", null, null, null)
                .entity().id();

        String image = bulkEntityId(op("add-image-to-view", Map.of(
                "viewId", view.getId(), "imagePath", imagePath, "parentViewObjectId", parent,
                "x", 10, "y", 10, "width", 100, "height", 100)));
        String onQueuedView = bulkEntityId(op("add-image-to-view", Map.of(
                "viewId", queuedView, "imagePath", imagePath,
                "x", 10, "y", 10, "width", 100, "height", 100)));
        dispatcher.endBatch(SESSION, true);

        assertChildOf(parent, image);
        assertNotNull("the image must have landed on the view the batch queued",
                findDescendant(viewById(queuedView), onQueuedView));
    }

    /** The connection arm: a connection whose add has not run is still a re-styling target. */
    @Test
    public void shouldResolveAQueuedConnection_whenANestedBulkUpdatesIt() {
        seedConnectableFixture();
        dispatcher.beginBatch(SESSION, "outer");
        String queuedConnection = accessor.addConnectionToView(SESSION, view.getId(), "rel-1",
                "vo-1", "vo-2", null, null, null, null, null).entity().viewConnectionId();

        var result = accessor.executeBulk(SESSION, List.of(op("update-view-connection",
                Map.of("viewConnectionId", queuedConnection, "showLabel", Boolean.TRUE))),
                "nested bulk", false);
        dispatcher.endBatch(SESSION, true);

        assertEquals("the queued connection must have been addressed, not reported missing",
                1, result.operations().size());
        assertNotNull("and it must have committed onto the view",
                findConnection(queuedConnection));
    }

    /**
     * Precedence, asserted on the slots this change actually opened rather than inherited from the
     * one arm that already had it. A {@code $N.id} naming an object THIS call created must still
     * resolve to that object while an enclosing batch is open and holding candidates of its own —
     * the queue is consulted only after the call's own maps come back empty, never before.
     */
    @Test
    public void shouldPreferTheSameCallBackReference_onTheNewlyResolvingPlacementSlots() {
        dispatcher.beginBatch(SESSION, "outer");
        String queuedParent = accessor.addGroupToView(SESSION, view.getId(), "Queued Parent",
                0, 0, 400, 400, null, null, null).entity().viewObjectId();
        String queuedView = accessor.createView(SESSION, "Queued View", null, null, null)
                .entity().id();

        var result = accessor.executeBulk(SESSION, List.of(
                op("add-group-to-view", Map.of("viewId", view.getId(), "label", "Call Scoped",
                        "x", 900, "y", 0, "width", 200, "height", 200)),
                op("add-to-view", Map.of("viewId", view.getId(), "elementId", "actor-1",
                        "parentViewObjectId", "$0.id",
                        "x", 10, "y", 10, "width", 120, "height", 55))),
                "back-reference beats the outer queue on a placement arm", false);
        String callScopedParent = result.operations().get(0).entityId();
        String child = result.operations().get(1).entityId();
        dispatcher.endBatch(SESSION, true);

        assertChildOf(callScopedParent, child);
        assertNull("the child must NOT have landed in the group the enclosing batch queued",
                findDescendant((IDiagramModelContainer) findDescendant(view, queuedParent), child));
        assertNotNull("and the batch's own queued view must still have committed untouched",
                viewById(queuedView));
    }

    // ---- the negative direction: a miss must stay a miss ----------------------------------------

    /**
     * One control per newly-resolving slot kind. Without these the block above would pass just as
     * well against a lookup that resolved everything, which is not the change that was made.
     */
    @Test
    public void shouldStillReportNotFound_whenTheNewlyResolvingSlotsNameAnUnknownId() {
        dispatcher.beginBatch(SESSION, "outer");

        assertBulkFails("Parent view object not found: nope-parent", op("add-group-to-view",
                Map.of("viewId", view.getId(), "label", "X", "parentViewObjectId", "nope-parent",
                        "x", 0, "y", 0, "width", 100, "height", 100)));
        assertBulkFails("View not found: nope-view", op("add-group-to-view",
                Map.of("viewId", "nope-view", "label", "X",
                        "x", 0, "y", 0, "width", 100, "height", 100)));
        assertBulkFails("Element not found: nope-element", op("add-to-view",
                Map.of("viewId", view.getId(), "elementId", "nope-element",
                        "x", 0, "y", 0, "width", 100, "height", 55)));
        // The connection arm answers with the view-object wording, not a connection-flavoured one.
        assertBulkFails("View object not found: nope-connection", op("update-view-connection",
                Map.of("viewConnectionId", "nope-connection", "showLabel", Boolean.TRUE)));

        dispatcher.endBatch(SESSION, false);
    }

    /**
     * The constraint the queued parent must NOT escape. {@code resolveParentContainer} carries a
     * destined-view check on its pre-resolved branch, and a queued container coalesces in ABOVE
     * that check rather than beside it — entering through the live-lookup arm instead would resolve
     * a parent without ever asking which view it belongs to, and the child would land in a view the
     * operation never named. A fallback inherits every constraint of the path it replaces.
     */
    @Test
    public void shouldStillRejectAQueuedParentFromAnotherView_whenANestedBulkNamesIt() {
        dispatcher.beginBatch(SESSION, "outer");
        String foreignParent = accessor.addGroupToView(SESSION, otherView.getId(), "Elsewhere",
                0, 0, 400, 400, null, null, null).entity().viewObjectId();

        assertBulkFails("belongs to view", op("add-to-view", Map.of(
                "viewId", view.getId(), "elementId", "actor-1",
                "parentViewObjectId", foreignParent,
                "x", 10, "y", 10, "width", 120, "height", 55)));

        dispatcher.endBatch(SESSION, false);
    }

    /**
     * Effective state, not merely absence-of-throw. Resolving the parent's identity and stopping
     * there would place the child and leave the queued parent sized against the live model — a
     * silently un-grown container reported as success, which is worse than the loud not-found it
     * replaced. The same placement run through the single-tool path is the oracle.
     */
    @Test
    public void shouldGrowTheQueuedParentIdentically_whenANestedBulkPlacesIntoIt() {
        int[] viaBatch = placeAnOversizedChildInAQueuedParent(false);
        int[] viaNestedBulk = placeAnOversizedChildInAQueuedParent(true);

        assertEquals("the queued parent must end the same width on both routes",
                viaBatch[0], viaNestedBulk[0]);
        assertEquals("and the same height", viaBatch[1], viaNestedBulk[1]);
        assertTrue("the fixture only discriminates if the parent actually had to grow: "
                + viaBatch[0] + "x" + viaBatch[1], viaBatch[1] > 200);
    }

    /**
     * Auto-fit inside a batch is a VISIBILITY read and predates this change: {@code prepareAddToView}
     * has always consulted the queue for pending bounds and parents without being able to *address*
     * a queued id. Widening addressability must not disturb it, so this pins the untouched half on a
     * route that names no queued id through bulk at all — the cascade must still grow a queued
     * parent from queue-derived bounds.
     */
    @Test
    public void shouldStillAutoFitFromTheQueue_whenTheBatchResizesAQueuedChildDirectly() {
        dispatcher.beginBatch(SESSION, "outer");
        String parent = accessor.addGroupToView(SESSION, view.getId(), "Queued Parent",
                0, 0, 200, 200, null, null, null).entity().viewObjectId();
        String child = accessor.addToView(SESSION, view.getId(), "actor-1", 10, 10, 120, 55,
                false, parent, null, null).entity().viewObject().viewObjectId();
        accessor.updateViewObject(SESSION, child, null, null, 250, 250,
                null, null, null, null, null, null, null, null);
        dispatcher.endBatch(SESSION, true);

        IDiagramModelObject committed = findDescendant(view, parent);
        assertNotNull("the parent must have committed", committed);
        assertTrue("the parent-fit cascade must still have grown the queued parent: "
                + committed.getBounds().getWidth() + "x" + committed.getBounds().getHeight(),
                committed.getBounds().getHeight() > 200);
    }

    /**
     * Placing into a queued parent is the newly-addressable half; growing the child afterwards is
     * what forces the cascade to size a parent that is itself still detached. Both steps run on
     * whichever route is under test, so the nested-bulk placement has to leave the model in exactly
     * the state the single-tool placement leaves it in — parent-fit included.
     *
     * @param nested whether the placement runs through a nested bulk-mutate or straight in the batch
     * @return the committed parent width and height
     */
    private int[] placeAnOversizedChildInAQueuedParent(boolean nested) {
        String session = nested ? "fit-nested" : "fit-batch";
        dispatcher.beginBatch(session, "place into a queued parent");
        String parent = accessor.addGroupToView(session, view.getId(), "Queued Parent",
                500, 0, 200, 200, null, null, null).entity().viewObjectId();

        String child;
        if (nested) {
            var result = accessor.executeBulk(session, List.of(op("add-to-view", Map.of(
                    "viewId", view.getId(), "elementId", "actor-1",
                    "parentViewObjectId", parent,
                    "x", 10, "y", 10, "width", 120, "height", 55))), "nested bulk", false);
            child = result.operations().get(0).entityId();
        } else {
            child = accessor.addToView(session, view.getId(), "actor-1", 10, 10, 120, 55, false,
                    parent, null, null).entity().viewObject().viewObjectId();
        }
        accessor.updateViewObject(session, child, null, null, 250, 250,
                null, null, null, null, null, null, null, null);
        dispatcher.endBatch(session, true);

        IDiagramModelObject committed = findDescendant(view, parent);
        assertNotNull("the batch must have committed the queued parent", committed);
        return new int[] { committed.getBounds().getWidth(), committed.getBounds().getHeight() };
    }

    // ---- add-connection-to-view's back-reference branch -----------------------------------------
    //
    // The bulk pass routes a connection operation to a second, back-reference-aware prepare as soon
    // as ANY of relationshipId, viewId or either endpoint is a $N.id — and that prepare read
    // committed containment only. So the natural pattern "place something and connect it in one
    // nested call" reported the enclosing batch's ids as not found, while the very same operation
    // WITHOUT a back-reference resolved them. The control below is the whole point: it is the same
    // endpoint, on the same view, differing only by whether a $N.id appears elsewhere in the call.

    @Test
    public void shouldResolveAQueuedEndpoint_whenTheConnectionAlsoCarriesABackReference() {
        seedConnectableFixture();
        dispatcher.beginBatch(SESSION, "outer");
        String queuedEnd = accessor.addToView(SESSION, view.getId(), "actor-2",
                300, 400, 120, 55, false, null, null, null)
                .entity().viewObject().viewObjectId();

        var result = accessor.executeBulk(SESSION, List.of(
                op("add-to-view", Map.of("viewId", view.getId(), "elementId", "actor-1",
                        "x", 0, "y", 400, "width", 120, "height", 55)),
                op("add-connection-to-view", Map.of("viewId", view.getId(),
                        "relationshipId", "rel-1",
                        "sourceViewObjectId", "$0.id",
                        "targetViewObjectId", queuedEnd))),
                "place and connect in one call", false);
        dispatcher.endBatch(SESSION, true);

        assertEquals("both operations must have been accepted", 2, result.operations().size());
        assertNotNull("the connection must have committed onto the view",
                findConnection(result.operations().get(1).entityId()));
    }

    /** The negative direction on the branch that just started resolving. */
    @Test
    public void shouldStillReportNotFound_whenAConnectionEndpointExistsNowhere() {
        seedConnectableFixture();
        dispatcher.beginBatch(SESSION, "outer");

        assertBulkFails("Target view object not found: nope-endpoint",
                op("add-to-view", Map.of("viewId", view.getId(), "elementId", "actor-1",
                        "x", 0, "y", 400, "width", 120, "height", 55)),
                op("add-connection-to-view", Map.of("viewId", view.getId(),
                        "relationshipId", "rel-1",
                        "sourceViewObjectId", "$0.id",
                        "targetViewObjectId", "nope-endpoint")));

        dispatcher.endBatch(SESSION, false);
    }

    /**
     * The endpoint fallback is view-scoped, and must stay so. A queued object destined for a
     * different diagram keeps taking the ordinary not-found path — resolving it would build a
     * connection whose two ends land on different views.
     */
    @Test
    public void shouldNotResolveAQueuedEndpoint_whenItIsDestinedForAnotherView() {
        seedConnectableFixture();
        dispatcher.beginBatch(SESSION, "outer");
        String elsewhere = accessor.addToView(SESSION, otherView.getId(), "actor-2",
                0, 0, 120, 55, false, null, null, null)
                .entity().viewObject().viewObjectId();

        assertBulkFails("Target view object not found: " + elsewhere,
                op("add-to-view", Map.of("viewId", view.getId(), "elementId", "actor-1",
                        "x", 0, "y", 400, "width", 120, "height", 55)),
                op("add-connection-to-view", Map.of("viewId", view.getId(),
                        "relationshipId", "rel-1",
                        "sourceViewObjectId", "$0.id",
                        "targetViewObjectId", elsewhere)));

        dispatcher.endBatch(SESSION, false);
    }

    /**
     * The fourth id on this branch. The relationship and both endpoints are pinned above and below;
     * this is the {@code viewId} — the connection is drawn on a view whose own {@code create-view}
     * is still sitting in the enclosing batch's queue, with both endpoints placed on that same
     * unwritten view.
     */
    @Test
    public void shouldResolveAQueuedView_whenTheConnectionBranchDrawsOntoIt() {
        seedConnectableFixture();
        dispatcher.beginBatch(SESSION, "outer");
        String queuedView = accessor.createView(SESSION, "Queued Canvas", null, null, null)
                .entity().id();
        String queuedEnd = accessor.addToView(SESSION, queuedView, "actor-2",
                300, 0, 120, 55, false, null, null, null)
                .entity().viewObject().viewObjectId();

        var result = accessor.executeBulk(SESSION, List.of(
                op("add-to-view", Map.of("viewId", queuedView, "elementId", "actor-1",
                        "x", 0, "y", 0, "width", 120, "height", 55)),
                op("add-connection-to-view", Map.of("viewId", queuedView,
                        "relationshipId", "rel-1",
                        "sourceViewObjectId", "$0.id",
                        "targetViewObjectId", queuedEnd))),
                "connect on a view the batch has not written yet", false);
        dispatcher.endBatch(SESSION, true);

        assertEquals("both operations must have been accepted", 2, result.operations().size());
        IArchimateDiagramModel committed = viewById(queuedView);
        assertNotNull("the queued view must have committed", committed);
        assertNotNull("and the connection must have landed on it",
                findDescendant(committed, result.operations().get(0).entityId()));
    }

    /**
     * A relationship the enclosing batch queued is addressable on this branch — and this is also
     * the pin that the branch reads its ends through the dispatcher rather than raw.
     *
     * <p>The endpoints named here <em>match</em> the queued relationship, so the call must be
     * accepted. Read raw, a queued relationship answers null at both ends, no orientation can
     * match, and this legitimate connection would be rejected as a mismatch. Proven by reverting
     * the two dispatcher reads to the raw ones: this test goes red.</p>
     */
    @Test
    public void shouldResolveAnOuterQueuedRelationship_whenTheEndpointsMatchIt() {
        seedConnectableFixture();
        dispatcher.beginBatch(SESSION, "outer");
        String queuedRel = queueARelationshipCreate("rel-queued", element("actor-1"),
                element("actor-2"));

        var result = accessor.executeBulk(SESSION, List.of(
                op("add-to-view", Map.of("viewId", view.getId(), "elementId", "actor-1",
                        "x", 0, "y", 400, "width", 120, "height", 55)),
                op("add-connection-to-view", Map.of("viewId", view.getId(),
                        "relationshipId", queuedRel,
                        "sourceViewObjectId", "$0.id",
                        "targetViewObjectId", "vo-2"))),
                "connect a queued relationship", false);
        dispatcher.endBatch(SESSION, false);

        assertEquals("the queued relationship must have been addressed", 2,
                result.operations().size());
    }

    /**
     * ⛔ Resolving the relationship out of the enclosing queue must NOT disable validation.
     *
     * <p>The skip that lets a same-call back-reference through tests three conjuncts — null source,
     * null target, null container — and a relationship pulled from an <em>enclosing</em> batch's
     * queue satisfies every one of them, because {@code create-relationship} defers {@code connect()}
     * and the folder attachment to commit. Resolving the relationship without narrowing that skip
     * would therefore trade a not-found for a connection nobody checked. This names endpoints the
     * queued relationship does not join, and the rejection has to survive.</p>
     */
    @Test
    public void shouldStillRejectAMismatch_whenTheRelationshipCameFromTheEnclosingQueue() {
        seedConnectableFixture();
        dispatcher.beginBatch(SESSION, "outer");
        String queuedRel = queueARelationshipCreate("rel-queued", element("actor-1"),
                element("actor-2"));

        // vo-3 shows actor-3, which the queued relationship does not touch at either end.
        assertBulkFails("does not connect the elements",
                op("add-to-view", Map.of("viewId", view.getId(), "elementId", "actor-1",
                        "x", 0, "y", 400, "width", 120, "height", 55)),
                op("add-connection-to-view", Map.of("viewId", view.getId(),
                        "relationshipId", queuedRel,
                        "sourceViewObjectId", "$0.id",
                        "targetViewObjectId", "vo-3")));

        dispatcher.endBatch(SESSION, false);
    }

    /**
     * And the rejection has to stay readable, on the path where the reader can still see it.
     *
     * <p>A queued relationship's own {@code getSource()} and {@code getTarget()} are null until
     * commit, so a detail line built from the raw ends says the relationship connects
     * "nothing -> nothing" about endpoints the server had just resolved. The match is unaffected
     * when that happens, so a green suite proves nothing and the string has to be the assertion.</p>
     *
     * <p>It is asserted through the single-tool entry rather than through a nested bulk because
     * {@code executeBulk} replaces the {@code details} of the refusal with its own
     * {@code failedOperationIndex} line, extended with a {@code failedOperationCount} once more
     * than one operation has failed — the mismatch detail never reaches a bulk caller at all. The
     * per-operation {@code message}, {@code errorCode} and {@code suggestedCorrection} now do
     * survive, in the refusal's {@code failed} rows; {@code details} still does not.
     * Both connection prepares hand the same validator the same dispatcher-resolved ends, so this
     * pins the line itself while the branch-level behaviour is pinned by the two tests above.</p>
     */
    @Test
    public void shouldNameTheResolvedEnds_whenAQueuedRelationshipMismatches() {
        seedConnectableFixture();
        dispatcher.beginBatch(SESSION, "outer");
        String queuedRel = queueARelationshipCreate("rel-queued", element("actor-1"),
                element("actor-2"));

        try {
            accessor.addConnectionToView(SESSION, view.getId(), queuedRel, "vo-1", "vo-3",
                    null, null, null, null, null);
            throw new AssertionError("expected the mismatch to be rejected");
        } catch (ModelAccessException e) {
            String detail = String.valueOf(e.getDetails());
            assertTrue("the detail must name the ends the server resolved, not 'nothing': " + detail,
                    detail.contains("actor-1") && detail.contains("actor-2"));
            assertTrue("and must not report the queued relationship as joining nothing: " + detail,
                    !detail.contains("nothing"));
        }
        dispatcher.endBatch(SESSION, false);
    }

    // ---- helpers for the block above ------------------------------------------------------------

    private static net.vheerden.archi.mcp.response.dto.BulkOperation op(String tool,
            Map<String, Object> params) {
        return new net.vheerden.archi.mcp.response.dto.BulkOperation(tool, params);
    }

    private String bulkEntityId(net.vheerden.archi.mcp.response.dto.BulkOperation operation) {
        var result = accessor.executeBulk(SESSION, List.of(operation), "nested bulk", false);
        assertEquals("the nested bulk must have carried its one operation",
                1, result.operations().size());
        return result.operations().get(0).entityId();
    }

    private void assertBulkFails(String expectedFragment,
            net.vheerden.archi.mcp.response.dto.BulkOperation... operations) {
        try {
            accessor.executeBulk(SESSION, List.of(operations), "nested bulk", false);
            throw new AssertionError("expected a rejection mentioning: " + expectedFragment);
        } catch (ModelAccessException e) {
            assertTrue("expected a message containing '" + expectedFragment + "' but got: "
                    + e.getMessage(), e.getMessage().contains(expectedFragment));
        }
    }

    /**
     * Queues a relationship create into the open batch without going through
     * {@code create-relationship}, whose validation cannot initialise outside the PDE runtime. The
     * queue is keyed on the command, so what the lookups see is identical either way — and this
     * keeps the endpoint-validation pins runnable in the headless lane, where they matter most.
     */
    private String queueARelationshipCreate(String id, IArchimateElement source,
            IArchimateElement target) {
        IArchimateRelationship rel = factory.createAssociationRelationship();
        rel.setId(id);
        dispatcher.queueForBatch(SESSION, new CreateRelationshipCommand(rel,
                model.getFolder(FolderType.RELATIONS), source, target), "queued relationship");
        return id;
    }

    private IArchimateElement element(String id) {
        for (Object candidate : model.getFolder(FolderType.BUSINESS).getElements()) {
            if (candidate instanceof IArchimateElement el && id.equals(el.getId())) {
                return el;
            }
        }
        throw new AssertionError("no element with id " + id);
    }

    private void assertChildOf(String parentId, String childId) {
        IDiagramModelObject parent = findDescendant(view, parentId);
        assertNotNull("the queued parent must have committed", parent);
        assertNotNull("the child must have committed INSIDE that parent, not beside it",
                findDescendant((IDiagramModelContainer) parent, childId));
    }

    private IArchimateDiagramModel viewById(String id) {
        for (Object candidate : model.getFolder(FolderType.DIAGRAMS).getElements()) {
            if (candidate instanceof IArchimateDiagramModel diagram
                    && id.equals(diagram.getId())) {
                return diagram;
            }
        }
        throw new AssertionError("no committed view with id " + id);
    }

    private IDiagramModelConnection findConnection(String id) {
        for (IDiagramModelObject child : view.getChildren()) {
            for (IDiagramModelConnection c : child.getSourceConnections()) {
                if (id.equals(c.getId())) {
                    return c;
                }
            }
        }
        return null;
    }

    /**
     * Three live actors, three live view objects and one live relationship joining the first two.
     * The third actor is what makes a mismatch assertable: it is on the view and on no relationship.
     */
    private void seedConnectableFixture() {
        IBusinessActor first = (IBusinessActor) model.getFolder(FolderType.BUSINESS)
                .getElements().get(0);
        IBusinessActor second = seedActor("actor-2", "Second Actor");
        IBusinessActor third = seedActor("actor-3", "Third Actor");

        IArchimateRelationship rel = factory.createAssociationRelationship();
        rel.setId("rel-1");
        rel.setSource(first);
        rel.setTarget(second);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);

        seedViewObject("vo-1", first, 0);
        seedViewObject("vo-2", second, 300);
        seedViewObject("vo-3", third, 600);
    }

    private IBusinessActor seedActor(String id, String name) {
        IBusinessActor actor = factory.createBusinessActor();
        actor.setId(id);
        actor.setName(name);
        model.getFolder(FolderType.BUSINESS).getElements().add(actor);
        return actor;
    }

    private void seedViewObject(String id, IBusinessActor element, int x) {
        IDiagramModelArchimateObject vo = factory.createDiagramModelArchimateObject();
        vo.setId(id);
        vo.setArchimateElement(element);
        vo.setBounds(x, 700, 120, 55);
        view.getChildren().add(vo);
    }

    private static void writeMinimalPng(File file) throws IOException {
        java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        javax.imageio.ImageIO.write(image, "png", file);
    }

    @SuppressWarnings("unchecked")
    private static String queuedViewObjectId(Map<String, Object> envelope) {
        Map<String, Object> result = (Map<String, Object>) envelope.get("result");
        Map<String, Object> preview = (Map<String, Object>) result.get("preview");
        return (String) preview.get("viewObjectId");
    }

    @SuppressWarnings("unchecked")
    private static String errorField(Map<String, Object> envelope, String field) {
        Object error = envelope.get("error");
        if (!(error instanceof Map<?, ?> m)) {
            throw new AssertionError("expected an error envelope, got: " + envelope);
        }
        return String.valueOf(((Map<String, Object>) m).get(field));
    }

    private CommandRegistry registryOverLiveAccessor() {
        CommandRegistry registry = new CommandRegistry();
        SessionManager sessions =
                new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS);
        HandlerRegistrar.registerAll(accessor, new ResponseFormatter(), registry, sessions);
        return registry;
    }

    private Map<String, Object> invokeTool(CommandRegistry registry, String toolName,
            Map<String, Object> args) throws Exception {
        io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification spec =
                registry.getToolSpecifications().stream()
                        .filter(s -> s.tool().name().equals(toolName))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("Tool not found: " + toolName));
        io.modelcontextprotocol.spec.McpSchema.CallToolResult result = spec.callHandler()
                .apply(null, new io.modelcontextprotocol.spec.McpSchema.CallToolRequest(toolName, args));
        io.modelcontextprotocol.spec.McpSchema.TextContent content =
                (io.modelcontextprotocol.spec.McpSchema.TextContent) result.content().get(0);
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(content.text(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
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
