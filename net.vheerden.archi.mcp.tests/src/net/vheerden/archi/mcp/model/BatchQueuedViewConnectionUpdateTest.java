package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
import org.junit.Test;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.EmbeddedViewDto;
import net.vheerden.archi.mcp.response.dto.ProposalDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionSpec;
import net.vheerden.archi.mcp.response.dto.ViewPositionSpec;

/**
 * Pins that every id a deferred unit of work hands back is addressable by the operations that
 * follow it in that same unit: a connection added by an earlier {@code add-connection-to-view}, a
 * view created by an earlier {@code create-view} and named as a {@code referencedViewId}, the view
 * objects and relationship an {@code add-connection-to-view} draws between, and all three ids
 * {@code apply-view-layout} takes.
 *
 * <p>They are all the same seam. The prepare resolves the id through
 * {@code ArchimateModelUtils.getObjectByID}, which walks committed containment, while the command
 * that will create the object is still sitting in the batch queue and has not executed. The bulk
 * path bridges the seam with its back-reference maps; queue mode had no bridge, so the caller got a
 * not-found for an id the same request had just handed it.</p>
 *
 * <p>Each closure is paired with the negative control that keeps it honest — a genuinely absent id
 * must fail with the message it always had, and a queued object of the wrong kind must fail rather
 * than resolve, because a lookup that quietly widens what it accepts is worse than the throw.</p>
 *
 * <h2>Execution model</h2>
 *
 * <p>Same headless idiom as {@code BatchQueuedViewObjectUpdateTest} /
 * {@code BatchQueuedParentContainerTest}: a real GEF {@link CommandStack} driven over an
 * <em>ordered</em> compound, because queue order is load-bearing — the add must execute before the
 * update. The production compound is {@code NonNotifyingCompoundCommand}, whose {@code execute()}
 * dereferences {@code IEditorModelManager.INSTANCE} and cannot run headless, so queued children are
 * rebuilt into a plain GEF {@link CompoundCommand} — order preserved, only ECORE event suppression
 * dropped. {@link RequireAttachedContainerCommand} is rebuilt through its own
 * {@code withGuarded} rather than flattened, so the endpoint guard still runs.</p>
 *
 * <p>Every add passes explicit x/y/width/height so no path reaches {@code ElementSizer}'s
 * {@code Display.getDefault().syncExec}, which is what would otherwise force this class onto a
 * display.</p>
 */
public class BatchQueuedViewConnectionUpdateTest {

    private static final String SESSION = "batch-queued-connection-session";

    /** The endpoint not-found hint both connection prepares must give, byte for byte. */
    private static final String ENDPOINT_SUGGESTION =
            "Use get-view-contents to find valid view object IDs (viewObjectId field in visualMetadata)";

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private CommandStack stack;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private IArchimateDiagramModel otherView;
    private IBusinessActor source;
    private IBusinessActor target;
    private IArchimateRelationship relationship;
    private IArchimateRelationship secondRelationship;
    private IDiagramModelArchimateObject sourceObj;
    private IDiagramModelArchimateObject targetObj;
    private Command lastDispatched;
    private boolean approvalRequired;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Batch Queued Connection Fixture");
        model.setId("model-batch-queued-connection");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Connecting");
        diagrams.getElements().add(view);

        otherView = factory.createArchimateDiagramModel();
        otherView.setId("view-2");
        otherView.setName("Already There");
        diagrams.getElements().add(otherView);

        IFolder business = model.getFolder(FolderType.BUSINESS);
        source = factory.createBusinessActor();
        source.setId("actor-src");
        source.setName("Source Actor");
        business.getElements().add(source);

        target = factory.createBusinessActor();
        target.setId("actor-tgt");
        target.setName("Target Actor");
        business.getElements().add(target);

        relationship = factory.createAssociationRelationship();
        relationship.setId("rel-1");
        relationship.setName("associates");
        relationship.setSource(source);
        relationship.setTarget(target);
        model.getFolder(FolderType.RELATIONS).getElements().add(relationship);

        // A second relationship over the same pair, so a test needing two connections between the
        // same view objects does not trip the one-connection-per-relationship guard.
        secondRelationship = factory.createAssociationRelationship();
        secondRelationship.setId("rel-2");
        secondRelationship.setName("also associates");
        secondRelationship.setSource(source);
        secondRelationship.setTarget(target);
        model.getFolder(FolderType.RELATIONS).getElements().add(secondRelationship);

        // Two committed endpoints, so a test can hold one end still while varying the other.
        sourceObj = liveObject("vo-src", source, 0, 0, 120, 55);
        targetObj = liveObject("vo-tgt", target, 400, 0, 120, 55);

        stubModelManager.setModels(List.of(model));

        // A real, Display-free CommandStack hung off the model, so the staleness guard can register
        // its listener and a human edit during a review window is observable.
        stack = new CommandStack();
        model.setAdapter(CommandStack.class, stack);
        approvalRequired = false;
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
        dispatcher.setApprovalModeProvider(() -> approvalRequired);
        accessor = new ArchiModelAccessorImpl(stubModelManager, dispatcher);
        dispatcher.onModelActive(model);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private IDiagramModelArchimateObject liveObject(String id, IBusinessActor element,
            int x, int y, int w, int h) {
        IDiagramModelArchimateObject obj = factory.createDiagramModelArchimateObject();
        obj.setId(id);
        obj.setArchimateElement(element);
        obj.setBounds(x, y, w, h);
        view.getChildren().add(obj);
        return obj;
    }

    private String connect() {
        return connect(relationship);
    }

    private String connect(IArchimateRelationship rel) {
        return accessor.addConnectionToView(SESSION, view.getId(), rel.getId(),
                sourceObj.getId(), targetObj.getId(), null, null, null, null, null)
                .entity().viewConnectionId();
    }

    private MutationResult<ViewConnectionDto> updateConnection(String id, Boolean showLabel,
            Integer textPosition) {
        return accessor.updateViewConnection(SESSION, id, null, null, null, showLabel, textPosition);
    }

    /** The connection with that id, found through live containment after commit. */
    private IDiagramModelArchimateConnection findConnection(String id) {
        for (Object child : view.getChildren()) {
            if (child instanceof IDiagramModelObject obj) {
                for (Object conn : obj.getSourceConnections()) {
                    if (conn instanceof IDiagramModelArchimateConnection archConn
                            && id.equals(archConn.getId())) {
                        return archConn;
                    }
                }
            }
        }
        return null;
    }

    /** A connection's stored bendpoints as start/end offset quadruples, for comparison. */
    private static List<int[]> bendpointsOf(IDiagramModelArchimateConnection conn) {
        assertNotNull("connection must exist to read its bendpoints", conn);
        List<int[]> out = new ArrayList<>();
        for (Object bp : conn.getBendpoints()) {
            IDiagramModelBendpoint point = (IDiagramModelBendpoint) bp;
            out.add(new int[] { point.getStartX(), point.getStartY(),
                    point.getEndX(), point.getEndY() });
        }
        return out;
    }

    /** The view reference with that id, found through live containment after commit. */
    private static IDiagramModelReference findReference(IArchimateDiagramModel host, String id) {
        for (Object child : host.getChildren()) {
            if (child instanceof IDiagramModelReference ref && id.equals(ref.getId())) {
                return ref;
            }
        }
        return null;
    }

    private static void assertThrows(Runnable call, String expectedMessage, ErrorCode expectedCode) {
        assertThrows(call, expectedMessage, expectedCode, null);
    }

    /**
     * @param expectedSuggestion the exact {@code suggestedCorrection}, or null not to check it.
     *     The endpoint failures pass it: that string reaches an agent, and folding four copies of
     *     the lookup into one changed it on two of them, so it is pinned rather than assumed.
     */
    private static void assertThrows(Runnable call, String expectedMessage, ErrorCode expectedCode,
            String expectedSuggestion) {
        try {
            call.run();
            fail("expected ModelAccessException: " + expectedMessage);
        } catch (ModelAccessException e) {
            assertEquals("message", expectedMessage, e.getMessage());
            assertEquals("error code", expectedCode, e.getErrorCode());
            if (expectedSuggestion != null) {
                assertEquals("suggestedCorrection", expectedSuggestion, e.getSuggestedCorrection());
            }
        }
    }

    // ---- member 1: a queued connection is updatable -------------------------------------------

    /**
     * The headline case: a connection added earlier in the batch must be updatable later in that
     * same batch, and the update must land at commit.
     */
    @Test
    public void shouldUpdateQueuedConnection_whenItWasAddedEarlierInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "connect then restyle");
        String connId = connect();
        updateConnection(connId, Boolean.FALSE, 1);
        dispatcher.endBatch(SESSION, true);

        IDiagramModelArchimateConnection conn = findConnection(connId);
        assertNotNull("connection must exist after commit", conn);
        assertFalse("same-batch update must have hidden the label", conn.isNameVisible());
        assertEquals("same-batch update must have moved the label", 1, conn.getTextPosition());
    }

    /**
     * The response for the queued update names the connection the batch created, not some other
     * object, and reports the label state the update asked for.
     */
    @Test
    public void shouldReportTheQueuedConnection_whenUpdatedInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "connect then restyle");
        String connId = connect();
        MutationResult<ViewConnectionDto> result = updateConnection(connId, Boolean.FALSE, null);
        dispatcher.endBatch(SESSION, true);

        assertEquals("the update must address the queued connection",
                connId, result.entity().viewConnectionId());
        assertEquals("the relationship must be the one the connection was added for",
                relationship.getId(), result.entity().relationshipId());
        assertEquals("label visibility must be reported as requested",
                Boolean.FALSE, result.entity().nameVisible());
    }

    /**
     * Batched-mode response honesty: the queued update reports as batched with a queue position,
     * which is what nests the entity under {@code preview} beside a {@code batch} sibling rather
     * than surfacing it as state the model holds.
     */
    @Test
    public void shouldReportQueuedUpdateAsBatched_notAsAppliedState() throws Exception {
        dispatcher.beginBatch(SESSION, "connect then restyle");
        String connId = connect();
        MutationResult<ViewConnectionDto> result = updateConnection(connId, Boolean.FALSE, null);

        assertTrue("a queued update must report as batched", result.isBatched());
        assertFalse("a queued update is not a proposal", result.isProposal());
        assertNotNull("a queued update must carry its queue position", result.batchSequenceNumber());
        dispatcher.endBatch(SESSION, false);
    }

    /**
     * The negative control: an id no queued command creates and no committed object carries still
     * takes the ordinary not-found path, inside a batch exactly as outside one. The queue fallback
     * must not swallow a real failure.
     */
    @Test
    public void shouldStillThrow_whenConnectionIdIsGenuinelyAbsentInsideBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "absent id");
        try {
            assertThrows(() -> updateConnection("no-such-connection", Boolean.FALSE, null),
                    "View object not found: no-such-connection", ErrorCode.VIEW_OBJECT_NOT_FOUND);
        } finally {
            dispatcher.endBatch(SESSION, false);
        }
    }

    /** Outside a batch the same absent id must fail identically — the fallback is batch-only. */
    @Test
    public void shouldStillThrow_whenConnectionIdIsAbsentOutsideBatch() {
        assertThrows(() -> updateConnection("no-such-connection", Boolean.FALSE, null),
                "View object not found: no-such-connection", ErrorCode.VIEW_OBJECT_NOT_FOUND);
    }

    /**
     * The compound-recursion dependency, pinned.
     *
     * <p>{@code add-connection-to-view} does not queue a bare {@link AddConnectionToViewCommand}:
     * it queues that command wrapped in two {@link RequireAttachedContainerCommand} endpoint
     * guards, one per endpoint. The queue read-back reaches the inner command only because the
     * guard is a {@link CompoundCommand} and the walk recurses into compounds. Reshape the guard
     * into a non-compound decorator and every queue read-back in the model layer goes blind at
     * once — so this asserts the nesting is real (the top-level entry is not the add command),
     * that the guard is a compound, and that the read-back resolves through it anyway.</p>
     */
    @Test
    public void shouldReachTheQueuedAddCommandThroughBothEndpointGuards() throws Exception {
        assertTrue("the endpoint guard must remain a CompoundCommand — the queue read-back "
                + "recurses into compounds and nothing else",
                CompoundCommand.class.isAssignableFrom(RequireAttachedContainerCommand.class));

        dispatcher.beginBatch(SESSION, "guard nesting");
        String connId = connect();

        IDiagramModelArchimateConnection resolved = dispatcher.queuedViewConnection(SESSION, connId);
        assertNotNull("the queued connection must be reachable through the guard wrappers", resolved);
        assertEquals("the resolved connection must be the queued one", connId, resolved.getId());

        dispatcher.endBatch(SESSION, true);

        assertTrue("the batch must dispatch as one compound", lastDispatched instanceof CompoundCommand);
        Command queued = (Command) ((CompoundCommand) lastDispatched).getCommands().get(0);
        assertTrue("the queued entry must be the outer endpoint guard",
                queued instanceof RequireAttachedContainerCommand);
        Command inner = ((RequireAttachedContainerCommand) queued).getGuarded();
        assertTrue("the guard must wrap the second endpoint guard",
                inner instanceof RequireAttachedContainerCommand);
        assertTrue("the add command must sit two levels down, never at the top of the queue",
                ((RequireAttachedContainerCommand) inner).getGuarded()
                        instanceof AddConnectionToViewCommand);
    }

    /** Outside a batch there is no queue, so the lookup answers null rather than guessing. */
    @Test
    public void shouldResolveNothing_whenNoBatchIsOpen() {
        assertNull("no batch means no queued connection",
                dispatcher.queuedViewConnection(SESSION, "anything"));
    }

    /** A null id resolves to nothing rather than matching the first queued connection. */
    @Test
    public void shouldResolveNothing_whenIdIsNull() throws Exception {
        dispatcher.beginBatch(SESSION, "null id");
        connect();
        assertNull("a null id must not match a queued connection",
                dispatcher.queuedViewConnection(SESSION, null));
        dispatcher.endBatch(SESSION, false);
    }

    /**
     * A queued <em>view object</em> id must not resolve as a connection. The typed lookups are
     * deliberately separate so naming the wrong kind still takes the ordinary not-found path.
     */
    @Test
    public void shouldResolveNothing_whenTheQueuedIdIsNotAConnection() throws Exception {
        dispatcher.beginBatch(SESSION, "wrong kind");
        String groupId = accessor.addGroupToView(SESSION, view.getId(), "G",
                600, 0, 120, 120, null, null, null).entity().viewObjectId();
        assertNull("a queued group must not resolve as a queued connection",
                dispatcher.queuedViewConnection(SESSION, groupId));
        dispatcher.endBatch(SESSION, false);
    }

    /** Rolling the batch back drops the queue, so the id stops resolving and nothing is committed. */
    @Test
    public void shouldStopResolving_whenTheBatchIsRolledBack() throws Exception {
        dispatcher.beginBatch(SESSION, "rollback");
        String connId = connect();
        dispatcher.endBatch(SESSION, false);

        assertNull("a rolled-back batch leaves nothing to resolve",
                dispatcher.queuedViewConnection(SESSION, connId));
        assertNull("and nothing was committed either", findConnection(connId));
    }

    // ---- member 1: what a queued connection still cannot reach ---------------------------------

    /**
     * Relative bendpoints land on a queued connection exactly as they do on a committed one: they
     * are stored offsets and need nothing resolved.
     */
    @Test
    public void shouldApplyRelativeBendpointsIdentically_forQueuedAndCommittedConnections()
            throws Exception {
        List<BendpointDto> relative = List.of(new BendpointDto(30, 40, -30, 40));

        String liveConn = connect();
        accessor.updateViewConnection(SESSION, liveConn, relative, null, null, null, null);
        List<int[]> live = bendpointsOf(findConnection(liveConn));

        dispatcher.beginBatch(SESSION, "queued bendpoints");
        String queuedConn = connect(secondRelationship);
        accessor.updateViewConnection(SESSION, queuedConn, relative, null, null, null, null);
        dispatcher.endBatch(SESSION, true);
        List<int[]> queued = bendpointsOf(findConnection(queuedConn));

        assertEquals("a queued connection must store exactly one bendpoint", 1, queued.size());
        assertEquals("both must store the same number", live.size(), queued.size());
        for (int i = 0; i < live.size(); i++) {
            assertArrayEquals("bendpoint " + i + " must match the committed-connection result",
                    live.get(i), queued.get(i));
        }
    }

    /**
     * The absolute bendpoint form declines on a queued connection, and the decline is the correct
     * outcome rather than a gap left open.
     *
     * <p>Converting absolute canvas coordinates to Archi's endpoint-relative offsets needs both
     * endpoints, and a queued connection has none: {@code connect()} runs at commit, so until then
     * the connection's source and target are null. The conversion therefore cannot be attempted,
     * and the shared resolver says so in its own words instead of computing an offset from
     * coordinates it does not have. The relative form above remains available throughout, which is
     * exactly what the suggestion points the caller at.</p>
     *
     * <p>Bulk reaches this the same way for a back-referenced connection, so the two paths agree.</p>
     */
    @Test
    public void shouldDeclineAbsoluteBendpoints_whenTheConnectionIsStillQueued() throws Exception {
        List<AbsoluteBendpointDto> absolute = List.of(new AbsoluteBendpointDto(260, 200));

        dispatcher.beginBatch(SESSION, "queued absolute bendpoints");
        String queuedConn = connect();
        try {
            assertThrows(() -> accessor.updateViewConnection(
                            SESSION, queuedConn, null, absolute, null, null, null),
                    "Cannot use absoluteBendpoints: connection endpoints are not ArchiMate view objects",
                    ErrorCode.INVALID_PARAMETER);
        } finally {
            dispatcher.endBatch(SESSION, false);
        }
    }

    /** The same call on a committed connection converts, so the decline above is endpoint-driven. */
    @Test
    public void shouldConvertAbsoluteBendpoints_whenTheConnectionIsCommitted() {
        String liveConn = connect();
        accessor.updateViewConnection(SESSION, liveConn, null,
                List.of(new AbsoluteBendpointDto(260, 200)), null, null, null);

        assertEquals("a committed connection converts to one bendpoint",
                1, bendpointsOf(findConnection(liveConn)).size());
    }

    /**
     * A queued connection reports null endpoints rather than inventing them: they are genuinely
     * not set until the add command runs, and a projection that filled them in would be reporting
     * state the model does not hold.
     */
    @Test
    public void shouldReportNullEndpoints_whileTheConnectionIsStillQueued() throws Exception {
        dispatcher.beginBatch(SESSION, "queued endpoints in dto");
        String connId = connect();
        MutationResult<ViewConnectionDto> result = updateConnection(connId, Boolean.FALSE, null);

        assertNull("source is not set until the add command runs",
                result.entity().sourceViewObjectId());
        assertNull("target is not set until the add command runs",
                result.entity().targetViewObjectId());
        dispatcher.endBatch(SESSION, false);
    }

    /**
     * A batch can place an element and connect it in the same unit of work: the source endpoint may
     * name a view object an earlier operation in this batch added, whose add command has not run.
     */
    @Test
    public void shouldConnectAQueuedSourceEndpoint_addedEarlierInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "queued source endpoint");
        String queuedSource = accessor.addToView(SESSION, view.getId(), source.getId(),
                600, 0, 120, 55, false, null, null, null)
                .entity().viewObject().viewObjectId();
        String connId = accessor.addConnectionToView(SESSION, view.getId(),
                        secondRelationship.getId(), queuedSource, targetObj.getId(),
                        null, null, null, null, null)
                .entity().viewConnectionId();
        dispatcher.endBatch(SESSION, true);

        IDiagramModelArchimateConnection conn = findConnection(connId);
        assertNotNull("the connection must exist after commit", conn);
        assertEquals("and hang off the object the same batch added",
                queuedSource, conn.getSource().getId());
    }

    /**
     * The target end resolves a queued object too. Both ends run through one resolver, but they are
     * separate arguments to it, so only asserting both proves both — the source test above cannot
     * speak for this one.
     */
    @Test
    public void shouldConnectAQueuedTargetEndpoint_addedEarlierInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "queued target endpoint");
        String queuedTarget = accessor.addToView(SESSION, view.getId(), target.getId(),
                800, 0, 120, 55, false, null, null, null)
                .entity().viewObject().viewObjectId();
        String connId = accessor.addConnectionToView(SESSION, view.getId(),
                        secondRelationship.getId(), sourceObj.getId(), queuedTarget,
                        null, null, null, null, null)
                .entity().viewConnectionId();
        dispatcher.endBatch(SESSION, true);

        IDiagramModelArchimateConnection conn = findConnection(connId);
        assertNotNull("the connection must exist after commit", conn);
        assertEquals("and land on the object the same batch added",
                queuedTarget, conn.getTarget().getId());
    }

    /** Both ends queued at once — the shape an agent drawing a view from scratch actually writes. */
    @Test
    public void shouldConnectTwoQueuedEndpoints_placedEarlierInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "place two, connect them");
        String queuedSource = accessor.addToView(SESSION, view.getId(), source.getId(),
                600, 0, 120, 55, false, null, null, null)
                .entity().viewObject().viewObjectId();
        String queuedTarget = accessor.addToView(SESSION, view.getId(), target.getId(),
                800, 0, 120, 55, false, null, null, null)
                .entity().viewObject().viewObjectId();
        String connId = accessor.addConnectionToView(SESSION, view.getId(),
                        secondRelationship.getId(), queuedSource, queuedTarget,
                        null, null, null, null, null)
                .entity().viewConnectionId();
        dispatcher.endBatch(SESSION, true);

        IDiagramModelArchimateConnection conn = findConnection(connId);
        assertNotNull("the connection must exist after commit", conn);
        assertEquals("source must be the first queued object", queuedSource, conn.getSource().getId());
        assertEquals("target must be the second", queuedTarget, conn.getTarget().getId());
    }

    /**
     * The negative control for the endpoints: a genuinely absent id keeps its exact message, error
     * code and suggestion, inside a batch exactly as outside one. The queue fallback must not
     * swallow a real failure.
     */
    @Test
    public void shouldStillThrow_whenAnEndpointIsGenuinelyAbsentInsideBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "absent endpoint");
        try {
            assertThrows(() -> accessor.addConnectionToView(SESSION, view.getId(),
                            relationship.getId(), "no-such-source", targetObj.getId(),
                            null, null, null, null, null),
                    "Source view object not found: no-such-source",
                    ErrorCode.VIEW_OBJECT_NOT_FOUND, ENDPOINT_SUGGESTION);
            assertThrows(() -> accessor.addConnectionToView(SESSION, view.getId(),
                            relationship.getId(), sourceObj.getId(), "no-such-target",
                            null, null, null, null, null),
                    "Target view object not found: no-such-target",
                    ErrorCode.VIEW_OBJECT_NOT_FOUND, ENDPOINT_SUGGESTION);
        } finally {
            dispatcher.endBatch(SESSION, false);
        }
    }

    /**
     * Outside a batch the same absent endpoints fail identically — the fallback is batch-only. Both
     * ends are asserted: they are separate arguments to the resolver, so one cannot speak for the
     * other, and the message differs by design.
     */
    @Test
    public void shouldStillThrow_whenAnEndpointIsAbsentOutsideBatch() {
        assertThrows(() -> accessor.addConnectionToView(SESSION, view.getId(),
                        relationship.getId(), "no-such-source", targetObj.getId(),
                        null, null, null, null, null),
                "Source view object not found: no-such-source",
                ErrorCode.VIEW_OBJECT_NOT_FOUND, ENDPOINT_SUGGESTION);
        assertThrows(() -> accessor.addConnectionToView(SESSION, view.getId(),
                        relationship.getId(), sourceObj.getId(), "no-such-target",
                        null, null, null, null, null),
                "Target view object not found: no-such-target",
                ErrorCode.VIEW_OBJECT_NOT_FOUND, ENDPOINT_SUGGESTION);
    }

    /**
     * A queued endpoint destined for a <em>different view</em> must not resolve.
     *
     * <p>The live lookup searches one view's containment, so an endpoint has always had to belong to
     * the view the connection is drawn on. A queued object carries its destination in the queue
     * instead of in its container, and answering without checking it would let one batch join two
     * objects that land on different diagrams — a connection the live path cannot express, and one
     * Archi has no way to render.</p>
     */
    @Test
    public void shouldStillThrow_whenAQueuedEndpointIsDestinedForAnotherView() throws Exception {
        dispatcher.beginBatch(SESSION, "endpoint queued for another view");
        try {
            String queuedElsewhere = accessor.addToView(SESSION, otherView.getId(), source.getId(),
                    0, 0, 120, 55, false, null, null, null)
                    .entity().viewObject().viewObjectId();
            assertThrows(() -> accessor.addConnectionToView(SESSION, view.getId(),
                            relationship.getId(), queuedElsewhere, targetObj.getId(),
                            null, null, null, null, null),
                    "Source view object not found: " + queuedElsewhere,
                    ErrorCode.VIEW_OBJECT_NOT_FOUND, ENDPOINT_SUGGESTION);
        } finally {
            dispatcher.endBatch(SESSION, false);
        }
    }

    /**
     * The positive twin, so the check above cannot pass by refusing everything: an endpoint queued
     * for a container <em>nested inside</em> the right view still resolves. The destination is
     * reached by climbing queued containment, so a queued object inside a queued group counts as
     * belonging to the view that group is destined for.
     */
    @Test
    public void shouldConnectAQueuedEndpointNestedInAQueuedGroup() throws Exception {
        dispatcher.beginBatch(SESSION, "endpoint nested in a queued group");
        String queuedGroup = accessor.addGroupToView(SESSION, view.getId(), "G",
                600, 0, 300, 200, null, null, null).entity().viewObjectId();
        String nested = accessor.addToView(SESSION, view.getId(), source.getId(),
                10, 10, 120, 55, false, queuedGroup, null, null)
                .entity().viewObject().viewObjectId();

        String connId = accessor.addConnectionToView(SESSION, view.getId(),
                        secondRelationship.getId(), nested, targetObj.getId(),
                        null, null, null, null, null)
                .entity().viewConnectionId();
        dispatcher.endBatch(SESSION, true);

        // The endpoint lands inside the group, so the search has to recurse — findConnection only
        // walks the view's own children.
        IDiagramModelArchimateConnection conn = findConnectionRecursive(view, connId);
        assertNotNull("a queued endpoint inside a queued group on this view must connect", conn);
        assertEquals("and it must be that object", nested, conn.getSource().getId());
    }

    /** As {@link #findConnection(String)}, but descending into nested containers. */
    private static IDiagramModelArchimateConnection findConnectionRecursive(
            IDiagramModelContainer container, String id) {
        for (Object child : container.getChildren()) {
            if (child instanceof IDiagramModelObject obj) {
                for (Object conn : obj.getSourceConnections()) {
                    if (conn instanceof IDiagramModelArchimateConnection archConn
                            && id.equals(archConn.getId())) {
                        return archConn;
                    }
                }
            }
            if (child instanceof IDiagramModelContainer nested) {
                IDiagramModelArchimateConnection found = findConnectionRecursive(nested, id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Bulk parity for the endpoint slots, asserted rather than assumed: the same two operations
     * through {@code bulk-mutate} place an object and connect it in one call. Batch has to end up
     * where bulk already was, and only running both proves it.
     */
    @Test
    public void shouldConnectABulkCreatedEndpoint_whenTheSameCallPlacedIt() throws Exception {
        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("add-to-view", Map.of(
                        "viewId", view.getId(), "elementId", source.getId(),
                        "x", 600, "y", 0, "width", 120, "height", 55)),
                new BulkOperation("add-connection-to-view", Map.of(
                        "viewId", view.getId(), "relationshipId", secondRelationship.getId(),
                        "sourceViewObjectId", "$0.id",
                        "targetViewObjectId", targetObj.getId()))), null, false);

        assertTrue("bulk must apply both operations", result.allSucceeded());
        String placedId = result.operations().get(0).entityId();
        IDiagramModelArchimateConnection conn =
                findConnection(result.operations().get(1).entityId());
        assertNotNull("the bulk connection must exist", conn);
        assertEquals("and hang off the object the same call placed",
                placedId, conn.getSource().getId());
    }

    /**
     * Batched-mode response honesty for the second update, pinned rather than left to prose.
     *
     * <p>A styling-only update prepares against a model where the earlier bendpoint write has not
     * run, so its {@code preview} cannot show the bendpoints that will exist at commit. That is a
     * declared divergence, not a defect — the entity sits under {@code preview} beside its
     * {@code batch} sibling and is a projection, not a claim about state. It is pinned because the
     * tempting repair is to resolve the executor's work at prepare time, which is exactly the
     * prepare/execute divergence the label exists to declare.</p>
     */
    @Test
    public void shouldReportTheSecondUpdateAsAPreview_notAsTheBendpointsThatWillCommit()
            throws Exception {
        String connId = connect();

        dispatcher.beginBatch(SESSION, "bend then restyle");
        accessor.updateViewConnection(SESSION, connId,
                List.of(new BendpointDto(30, 40, -30, 40)), null, null, null, null);
        MutationResult<ViewConnectionDto> second = updateConnection(connId, Boolean.FALSE, null);

        assertTrue("the second update must report as batched", second.isBatched());
        assertNotNull("and carry its queue position", second.batchSequenceNumber());
        assertTrue("its preview cannot show the queued bendpoints, and must not invent them",
                second.entity().bendpoints() == null || second.entity().bendpoints().isEmpty());

        dispatcher.endBatch(SESSION, true);
        assertEquals("while the committed model does hold them", 1,
                bendpointsOf(findConnection(connId)).size());
    }

    /**
     * A queued object of the <em>wrong kind</em> still takes the ordinary not-found path. A
     * connection endpoint has to be an ArchiMate view object, because the relationship is validated
     * against the element behind it; a group, note or image has no element. The narrowing lives in
     * the lookup so this message is unchanged rather than becoming a later, stranger failure.
     */
    @Test
    public void shouldStillThrow_whenAQueuedGroupIsNamedAsAnEndpoint() throws Exception {
        dispatcher.beginBatch(SESSION, "queued group as endpoint");
        try {
            String queuedGroup = accessor.addGroupToView(SESSION, view.getId(), "G",
                    600, 0, 120, 120, null, null, null).entity().viewObjectId();
            assertThrows(() -> accessor.addConnectionToView(SESSION, view.getId(),
                            relationship.getId(), queuedGroup, targetObj.getId(),
                            null, null, null, null, null),
                    "Source view object not found: " + queuedGroup,
                    ErrorCode.VIEW_OBJECT_NOT_FOUND, ENDPOINT_SUGGESTION);
        } finally {
            dispatcher.endBatch(SESSION, false);
        }
    }

    /** A queued note is refused for the same reason, and must not be covered by the group case. */
    @Test
    public void shouldStillThrow_whenAQueuedNoteIsNamedAsAnEndpoint() throws Exception {
        dispatcher.beginBatch(SESSION, "queued note as endpoint");
        try {
            String queuedNote = accessor.addNoteToView(SESSION, view.getId(), "N",
                    null, null, 600, 0, 120, 60, null, null, null).entity().viewObjectId();
            assertThrows(() -> accessor.addConnectionToView(SESSION, view.getId(),
                            relationship.getId(), sourceObj.getId(), queuedNote,
                            null, null, null, null, null),
                    "Target view object not found: " + queuedNote,
                    ErrorCode.VIEW_OBJECT_NOT_FOUND, ENDPOINT_SUGGESTION);
        } finally {
            dispatcher.endBatch(SESSION, false);
        }
    }

    /**
     * The same endpoint failure through {@code bulk-mutate}, which reaches the other connection
     * prepare. That prepare used to carry its own shorter suggestion; folding the four copies into
     * one gave it this one, so both routes are pinned to the same string and cannot drift again.
     */
    @Test
    public void shouldGiveTheSameEndpointSuggestion_onTheBulkRoute() throws Exception {
        try {
            accessor.executeBulk(SESSION, List.of(
                    new BulkOperation("add-to-view", Map.of(
                            "viewId", view.getId(), "elementId", source.getId(),
                            "x", 600, "y", 0, "width", 120, "height", 55)),
                    new BulkOperation("add-connection-to-view", Map.of(
                            "viewId", view.getId(), "relationshipId", relationship.getId(),
                            "sourceViewObjectId", "$0.id",
                            "targetViewObjectId", "no-such-target"))), null, false);
            fail("expected the bulk connection add to fail on its target endpoint");
        } catch (ModelAccessException e) {
            assertTrue("must name the target endpoint: " + e.getMessage(),
                    e.getMessage().contains("Target view object not found: no-such-target"));
            assertEquals("both prepares must give the identical endpoint suggestion",
                    ENDPOINT_SUGGESTION, e.getSuggestedCorrection());
        }
    }

    /**
     * The third id slot: a relationship an earlier operation in this batch created can be drawn in
     * that same batch. {@code create-relationship} defers both its {@code connect()} and its folder
     * attachment to commit, so the id it hands back names nothing in containment until then.
     *
     * <p>The tool itself cannot be called here — {@code prepareCreateRelationship} validates
     * against Archi's legality matrix, whose static initialiser needs the OSGi runtime — so the
     * command that tool queues is queued directly. That is the same queued state by construction:
     * an unspecialized create-relationship queues exactly this command and nothing else.</p>
     */
    @Test
    public void shouldConnectAQueuedRelationship_createdEarlierInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "queued relationship");
        IArchimateRelationship pending = queueRelationshipCreate("rel-queued");

        String connId = accessor.addConnectionToView(SESSION, view.getId(), pending.getId(),
                        sourceObj.getId(), targetObj.getId(), null, null, null, null, null)
                .entity().viewConnectionId();
        dispatcher.endBatch(SESSION, true);

        IDiagramModelArchimateConnection conn = findConnection(connId);
        assertNotNull("the connection must exist after commit", conn);
        assertEquals("and carry the relationship the same batch created",
                pending.getId(), conn.getArchimateRelationship().getId());
    }

    /** All three slots queued at once — none of them may depend on the others being committed. */
    @Test
    public void shouldConnectWhenAllThreeIdSlotsAreQueuedInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "all three slots queued");
        String queuedSource = accessor.addToView(SESSION, view.getId(), source.getId(),
                600, 0, 120, 55, false, null, null, null)
                .entity().viewObject().viewObjectId();
        String queuedTarget = accessor.addToView(SESSION, view.getId(), target.getId(),
                800, 0, 120, 55, false, null, null, null)
                .entity().viewObject().viewObjectId();
        IArchimateRelationship pending = queueRelationshipCreate("rel-queued-all-three");

        String connId = accessor.addConnectionToView(SESSION, view.getId(), pending.getId(),
                        queuedSource, queuedTarget, null, null, null, null, null)
                .entity().viewConnectionId();
        dispatcher.endBatch(SESSION, true);

        IDiagramModelArchimateConnection conn = findConnection(connId);
        assertNotNull("the connection must exist after commit", conn);
        assertEquals("relationship", pending.getId(), conn.getArchimateRelationship().getId());
        assertEquals("source", queuedSource, conn.getSource().getId());
        assertEquals("target", queuedTarget, conn.getTarget().getId());
    }

    /** The negative control for the relationship slot, inside a batch and outside one. */
    @Test
    public void shouldStillThrow_whenTheRelationshipIsGenuinelyAbsent() throws Exception {
        dispatcher.beginBatch(SESSION, "absent relationship");
        try {
            assertThrows(() -> accessor.addConnectionToView(SESSION, view.getId(),
                            "no-such-relationship", sourceObj.getId(), targetObj.getId(),
                            null, null, null, null, null),
                    "Relationship not found: no-such-relationship",
                    ErrorCode.RELATIONSHIP_NOT_FOUND,
                    "Use get-relationships to find valid relationship IDs");
        } finally {
            dispatcher.endBatch(SESSION, false);
        }
        assertThrows(() -> accessor.addConnectionToView(SESSION, view.getId(),
                        "no-such-relationship", sourceObj.getId(), targetObj.getId(),
                        null, null, null, null, null),
                "Relationship not found: no-such-relationship",
                ErrorCode.RELATIONSHIP_NOT_FOUND,
                "Use get-relationships to find valid relationship IDs");
    }

    /**
     * A queued <em>element</em> named where the relationship belongs still takes the ordinary
     * not-found path. The typed lookups stay separate so naming the wrong kind fails as it always
     * did rather than resolving to an object the validation below would then choke on.
     */
    @Test
    public void shouldStillThrow_whenAQueuedElementIsNamedAsTheRelationship() throws Exception {
        dispatcher.beginBatch(SESSION, "queued element as relationship");
        try {
            String queuedElement = accessor.createElement(SESSION, "BusinessActor",
                    "Queued Actor", null, null, null, null).entity().id();
            assertThrows(() -> accessor.addConnectionToView(SESSION, view.getId(),
                            queuedElement, sourceObj.getId(), targetObj.getId(),
                            null, null, null, null, null),
                    "Relationship not found: " + queuedElement,
                    ErrorCode.RELATIONSHIP_NOT_FOUND);

            // A queued view OBJECT named as the relationship fails the same way. The two kinds
            // reach the typed lookup differently, so neither case covers the other.
            String queuedViewObject = accessor.addToView(SESSION, view.getId(), source.getId(),
                    600, 0, 120, 55, false, null, null, null)
                    .entity().viewObject().viewObjectId();
            assertThrows(() -> accessor.addConnectionToView(SESSION, view.getId(),
                            queuedViewObject, sourceObj.getId(), targetObj.getId(),
                            null, null, null, null, null),
                    "Relationship not found: " + queuedViewObject,
                    ErrorCode.RELATIONSHIP_NOT_FOUND);
        } finally {
            dispatcher.endBatch(SESSION, false);
        }
    }

    /**
     * The approval-card degradation this closure makes reachable, now measured rather than declared.
     *
     * <p>The card's richer sentence names the relationship's two endpoints, which it reads by
     * resolving {@code relationshipId} against the model. A queued relationship is not in the model
     * yet, so that lookup finds nothing and the sentence falls back to the plain description. While
     * every relationship a connection could name was necessarily committed, this arm could not be
     * reached and was recorded as unreachable; it can be reached now, so it is asserted.</p>
     *
     * <p>Falling back is the right outcome — the alternative is a card naming endpoints it guessed —
     * but it must stay a fallback and not rot into a card that names the wrong pair.</p>
     */
    @Test
    public void shouldFallBackToThePlainDescription_whenTheApprovedRelationshipIsStillQueued()
            throws Exception {
        dispatcher.beginBatch(SESSION, "approve a connection for a queued relationship");
        IArchimateRelationship pending = queueRelationshipCreate("rel-queued-approval");
        approvalRequired = true;

        MutationResult<ViewConnectionDto> result = accessor.addConnectionToView(SESSION,
                view.getId(), pending.getId(), sourceObj.getId(), targetObj.getId(),
                null, null, null, null, null);

        assertTrue("approval mode must store a proposal", result.isProposal());
        ProposalDto card = onlyCard();
        assertEquals("the card must degrade to the plain description",
                "Add connection for relationship " + pending.getId() + " to view 'Connecting'",
                card.effectDescription());
        assertFalse("and must not name endpoints it could not resolve",
                card.effectDescription().contains(source.getName()));

        approvalRequired = false;
        dispatcher.endBatch(SESSION, false);
    }

    /**
     * The same card for a <em>committed</em> relationship does name both endpoints, which is what
     * makes the fallback above a degradation rather than the tool never having had the sentence.
     */
    @Test
    public void shouldNameBothEndpoints_whenTheApprovedRelationshipIsCommitted() {
        approvalRequired = true;

        MutationResult<ViewConnectionDto> result = accessor.addConnectionToView(SESSION,
                view.getId(), relationship.getId(), sourceObj.getId(), targetObj.getId(),
                null, null, null, null, null);

        assertTrue("approval mode must store a proposal", result.isProposal());
        ProposalDto card = onlyCard();
        assertTrue("a committed relationship names its source: " + card.effectDescription(),
                card.effectDescription().contains(source.getName()));
        assertTrue("and its target: " + card.effectDescription(),
                card.effectDescription().contains(target.getName()));
        approvalRequired = false;
    }

    /**
     * The fourth id this tool takes is its own {@code viewId}, and it was the last thing standing
     * between a batch and a view drawn from scratch: with the three slots above closed, an agent
     * that created a view, placed two elements on it and connected them still failed at the view
     * check, before either endpoint was looked at.
     */
    @Test
    public void shouldConnectOnAQueuedView_createdEarlierInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "create a view, place two, connect them");
        String newViewId = accessor.createView(SESSION, "Drawn From Scratch", null, null, null)
                .entity().id();
        String queuedSource = accessor.addToView(SESSION, newViewId, source.getId(),
                0, 0, 120, 55, false, null, null, null)
                .entity().viewObject().viewObjectId();
        String queuedTarget = accessor.addToView(SESSION, newViewId, target.getId(),
                400, 0, 120, 55, false, null, null, null)
                .entity().viewObject().viewObjectId();

        String connId = accessor.addConnectionToView(SESSION, newViewId, relationship.getId(),
                        queuedSource, queuedTarget, null, null, null, null, null)
                .entity().viewConnectionId();
        dispatcher.endBatch(SESSION, true);

        IArchimateDiagramModel created = null;
        for (Object child : model.getFolder(FolderType.DIAGRAMS).getElements()) {
            if (child instanceof IArchimateDiagramModel diagram && newViewId.equals(diagram.getId())) {
                created = diagram;
            }
        }
        assertNotNull("the batch must have created the view", created);
        IDiagramModelObject placedSource = findChild(created, queuedSource);
        assertNotNull("and placed the source object on it", placedSource);
        assertNotNull("and the target object", findChild(created, queuedTarget));

        IDiagramModelArchimateConnection conn = null;
        for (Object candidate : placedSource.getSourceConnections()) {
            if (candidate instanceof IDiagramModelArchimateConnection archConn
                    && connId.equals(archConn.getId())) {
                conn = archConn;
            }
        }
        assertNotNull("and joined them with the connection", conn);
        assertEquals("target", queuedTarget, conn.getTarget().getId());
    }

    /** An absent host view keeps its own message on this tool too, inside a batch and outside one. */
    @Test
    public void shouldStillThrow_whenTheConnectionHostViewIsGenuinelyAbsent() throws Exception {
        dispatcher.beginBatch(SESSION, "absent connection host view");
        try {
            assertThrows(() -> accessor.addConnectionToView(SESSION, "no-such-view",
                            relationship.getId(), sourceObj.getId(), targetObj.getId(),
                            null, null, null, null, null),
                    "View not found: no-such-view", ErrorCode.VIEW_NOT_FOUND,
                    "Use get-views to find valid view IDs");
        } finally {
            dispatcher.endBatch(SESSION, false);
        }
        assertThrows(() -> accessor.addConnectionToView(SESSION, "no-such-view",
                        relationship.getId(), sourceObj.getId(), targetObj.getId(),
                        null, null, null, null, null),
                "View not found: no-such-view", ErrorCode.VIEW_NOT_FOUND,
                "Use get-views to find valid view IDs");
    }

    // ---- a second update in one unit of work must not discard the first's bendpoints -----------

    /**
     * The data loss: two updates on the same connection in one deferred unit, the first supplying
     * bendpoints and the second only a label change, committed with the first call's bendpoints
     * gone. Nothing asked for them to be cleared.
     *
     * <p>The mechanism is a prepare/execute split. The styling-only update prepares against a model
     * where the first command has not run, snapshots the bendpoints it sees — none, or the
     * pre-batch ones — and the command writes that snapshot back unconditionally at commit,
     * on top of what the first command had just written.</p>
     */
    @Test
    public void shouldKeepTheFirstUpdatesBendpoints_whenASecondUpdateInTheSameBatchOnlyRestyles()
            throws Exception {
        String connId = connect();

        dispatcher.beginBatch(SESSION, "set bendpoints then restyle");
        accessor.updateViewConnection(SESSION, connId,
                List.of(new BendpointDto(30, 40, -30, 40)), null, null, null, null);
        updateConnection(connId, Boolean.FALSE, null);
        dispatcher.endBatch(SESSION, true);

        List<int[]> bendpoints = bendpointsOf(findConnection(connId));
        assertEquals("the first update's bendpoint must survive the second", 1, bendpoints.size());
        assertArrayEquals("with the exact offsets it was given",
                new int[] { 30, 40, -30, 40 }, bendpoints.get(0));
        assertFalse("and the second update's own change must still have landed",
                findConnection(connId).isNameVisible());
    }

    /** The same two operations on a connection the same batch created, not a committed one. */
    @Test
    public void shouldKeepTheFirstUpdatesBendpoints_whenTheConnectionItselfIsAlsoQueued()
            throws Exception {
        dispatcher.beginBatch(SESSION, "connect, bend, restyle");
        String connId = connect();
        accessor.updateViewConnection(SESSION, connId,
                List.of(new BendpointDto(30, 40, -30, 40)), null, null, null, null);
        updateConnection(connId, Boolean.FALSE, null);
        dispatcher.endBatch(SESSION, true);

        List<int[]> bendpoints = bendpointsOf(findConnection(connId));
        assertEquals("the first update's bendpoint must survive the second", 1, bendpoints.size());
        assertArrayEquals("with the exact offsets it was given",
                new int[] { 30, 40, -30, 40 }, bendpoints.get(0));
    }

    /**
     * The same loss through {@code bulk-mutate}, which is why the repair is at the command rather
     * than per-path: a bulk call has no batch queue to project from, and reaches the identical
     * prepare-then-execute split.
     */
    @Test
    public void shouldKeepTheFirstUpdatesBendpoints_whenTheSameTwoOpsRunAsBulk() throws Exception {
        String connId = connect();

        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("update-view-connection", Map.of(
                        "viewConnectionId", connId,
                        "bendpoints", List.of(Map.of("startX", 30, "startY", 40,
                                "endX", -30, "endY", 40)))),
                new BulkOperation("update-view-connection", Map.of(
                        "viewConnectionId", connId, "showLabel", false))), null, false);

        assertTrue("bulk must apply both operations", result.allSucceeded());
        List<int[]> bendpoints = bendpointsOf(findConnection(connId));
        assertEquals("the first operation's bendpoint must survive the second", 1, bendpoints.size());
        assertArrayEquals("with the exact offsets it was given",
                new int[] { 30, 40, -30, 40 }, bendpoints.get(0));
    }

    /**
     * The invariant the repair is most likely to break, pinned from both directions.
     *
     * <p>An explicit empty list means <em>clear</em> and must keep meaning it. That is not the same
     * signal as supplying no bendpoints at all, and the whole repair rests on the two staying
     * distinguishable — the prepare has always told them apart, only the command could not.</p>
     */
    @Test
    public void shouldStillClear_whenAnEmptyBendpointListIsSuppliedExplicitly() {
        String connId = connect();
        accessor.updateViewConnection(SESSION, connId,
                List.of(new BendpointDto(30, 40, -30, 40)), null, null, null, null);
        assertEquals("fixture check: one bendpoint is stored",
                1, bendpointsOf(findConnection(connId)).size());

        accessor.updateViewConnection(SESSION, connId, List.of(), null, null, null, null);

        assertTrue("an explicit empty list must still clear",
                bendpointsOf(findConnection(connId)).isEmpty());
    }

    /** The no-argument layout default clears too — it normalises to an empty list, not to nothing. */
    @Test
    public void shouldStillClear_whenApplyViewLayoutSuppliesNeitherBendpointForm() {
        String connId = connect();
        accessor.updateViewConnection(SESSION, connId,
                List.of(new BendpointDto(30, 40, -30, 40)), null, null, null, null);

        accessor.applyViewLayout(SESSION, view.getId(), null,
                List.of(new ViewConnectionSpec(connId, null, null)), null);

        assertTrue("the layout default must still clear to a straight line",
                bendpointsOf(findConnection(connId)).isEmpty());
    }

    /** And inside a batch, where the clear is queued rather than immediate. */
    @Test
    public void shouldStillClear_whenAnEmptyBendpointListIsSuppliedInsideABatch() throws Exception {
        String connId = connect();
        accessor.updateViewConnection(SESSION, connId,
                List.of(new BendpointDto(30, 40, -30, 40)), null, null, null, null);

        dispatcher.beginBatch(SESSION, "clear inside a batch");
        accessor.updateViewConnection(SESSION, connId, List.of(), null, null, null, null);
        dispatcher.endBatch(SESSION, true);

        assertTrue("an explicit empty list must clear from inside a batch too",
                bendpointsOf(findConnection(connId)).isEmpty());
    }

    /**
     * Undo is symmetric with execute: whatever gates the bendpoint write gates the restore. A
     * styling-only update that writes no bendpoints must not restore any either, or undoing it
     * would clear bendpoints it never touched.
     */
    @Test
    public void shouldRestoreThePreBatchBendpointsExactly_whenTheWholeUnitIsUndone()
            throws Exception {
        String connId = connect();
        accessor.updateViewConnection(SESSION, connId,
                List.of(new BendpointDto(11, 12, -13, 14)), null, null, null, null);
        List<int[]> before = bendpointsOf(findConnection(connId));

        dispatcher.beginBatch(SESSION, "bend then restyle, then undo it all");
        accessor.updateViewConnection(SESSION, connId,
                List.of(new BendpointDto(30, 40, -30, 40)), null, null, null, null);
        updateConnection(connId, Boolean.FALSE, null);
        dispatcher.endBatch(SESSION, true);

        stack.undo();

        List<int[]> after = bendpointsOf(findConnection(connId));
        assertEquals("undo must restore exactly the pre-batch bendpoint count",
                before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertArrayEquals("bendpoint " + i + " must be restored exactly",
                    before.get(i), after.get(i));
        }
        assertTrue("and the label visibility with it", findConnection(connId).isNameVisible());
    }

    /**
     * A styling-only update on its own leaves the bendpoints exactly as they were, outside any
     * deferred unit. The negative control for the gate: it must not have turned "no bendpoint
     * change" into "clear".
     */
    @Test
    public void shouldLeaveBendpointsUntouched_whenAStylingOnlyUpdateRunsImmediately() {
        String connId = connect();
        accessor.updateViewConnection(SESSION, connId,
                List.of(new BendpointDto(30, 40, -30, 40)), null, null, null, null);

        updateConnection(connId, Boolean.FALSE, 2);

        List<int[]> bendpoints = bendpointsOf(findConnection(connId));
        assertEquals("a styling-only update must not touch the bendpoints", 1, bendpoints.size());
        assertArrayEquals("they must be exactly what was there",
                new int[] { 30, 40, -30, 40 }, bendpoints.get(0));
    }

    // ---- a proposal whose only target was queued must still be vetted -------------------------

    /**
     * The harm, not just the path: propose against a target the batch has only queued, let the
     * batch commit so the object becomes real and editable, let the human edit it, then approve.
     * The human's edit was silently overwritten.
     *
     * <p>Nothing was broken about the vetting itself. The propose-time snapshot resolves each
     * target through committed containment, a queued target resolves to nothing, and a capture that
     * fingerprinted nothing is treated as fresh — so the comparison that would have caught the edit
     * never ran. The window is real: it opens the moment the batch commits and lasts as long as the
     * human takes to read the card.</p>
     */
    @Test
    public void shouldRejectAsStale_whenAQueuedTargetIsEditedAfterTheBatchCommits() throws Exception {
        dispatcher.beginBatch(SESSION, "propose against a queued target");
        String connId = connect();
        approvalRequired = true;
        MutationResult<ViewConnectionDto> proposed = updateConnection(connId, Boolean.FALSE, 2);
        String proposalId = proposed.proposalContext().proposalId();
        approvalRequired = false;
        dispatcher.endBatch(SESSION, true);

        IDiagramModelArchimateConnection conn = findConnection(connId);
        assertNotNull("the batch must have committed the connection", conn);
        humanEdits(conn);

        try {
            dispatcher.approveProposal(SESSION, proposalId);
            fail("approving must not silently overwrite the human's edit");
        } catch (MutationException e) {
            assertTrue("the human must be told what they touched: " + e.getMessage(),
                    e.getMessage().contains("you edited"));
            assertFalse("and must not be told it was removed, which it never was: " + e.getMessage(),
                    e.getMessage().contains("no longer exists"));
        }
        assertEquals("the human's edit survives", 1, conn.getTextPosition());
        assertEquals("the card stays on screen so they can read the reason and reject",
                1, dispatcher.getPendingProposalDtos(SESSION).size());
    }

    /**
     * The non-regression twin. Vetting a queued target must not mean refusing it: a proposal whose
     * target committed normally and was not touched has to approve cleanly, or the fix above would
     * "pass" by rejecting everything.
     */
    @Test
    public void shouldApproveNormally_whenAQueuedTargetCommitsAndIsNotTouched() throws Exception {
        dispatcher.beginBatch(SESSION, "propose against a queued target");
        String connId = connect();
        approvalRequired = true;
        MutationResult<ViewConnectionDto> proposed = updateConnection(connId, Boolean.FALSE, 2);
        String proposalId = proposed.proposalContext().proposalId();
        approvalRequired = false;
        dispatcher.endBatch(SESSION, true);

        dispatcher.approveProposal(SESSION, proposalId);

        IDiagramModelArchimateConnection conn = findConnection(connId);
        assertFalse("the approved update must have landed", conn.isNameVisible());
        assertEquals("including its label position", 2, conn.getTextPosition());
        assertTrue("and the queue drained", dispatcher.getPendingProposalDtos(SESSION).isEmpty());
    }

    /**
     * A queued target must never be reported as REMOVED. The tempting shortcut — record the
     * unresolved id with a placeholder fingerprint so the capture is non-empty — makes the
     * approve-time comparison see a target with no propose-time fingerprint, which it reads as
     * deleted. A target that committed perfectly normally would then be refused with a message
     * saying it no longer exists.
     */
    @Test
    public void shouldNotReportAQueuedTargetAsRemoved_whileTheBatchIsStillOpen() throws Exception {
        dispatcher.beginBatch(SESSION, "approve while the batch is open");
        String connId = connect();
        approvalRequired = true;
        MutationResult<ViewConnectionDto> proposed = updateConnection(connId, Boolean.FALSE, null);
        String proposalId = proposed.proposalContext().proposalId();
        approvalRequired = false;

        dispatcher.approveProposal(SESSION, proposalId);
        dispatcher.endBatch(SESSION, true);

        assertFalse("the approved update must have landed", findConnection(connId).isNameVisible());
    }

    /**
     * The control that shows the vetting was never broken for a committed target — it was only
     * skipped for a queued one. Same script, same edit, and this has always been refused.
     */
    @Test
    public void shouldRejectAsStale_whenACommittedTargetIsEditedDuringTheWindow() {
        String connId = connect();
        IDiagramModelArchimateConnection conn = findConnection(connId);
        approvalRequired = true;
        String proposalId = updateConnection(connId, Boolean.FALSE, 2)
                .proposalContext().proposalId();
        approvalRequired = false;

        humanEdits(conn);

        try {
            dispatcher.approveProposal(SESSION, proposalId);
            fail("a committed target edited during the window must reject-stale");
        } catch (MutationException e) {
            assertTrue("names what the human touched: " + e.getMessage(),
                    e.getMessage().contains("you edited"));
        }
        assertEquals("the human's edit survives", 1, conn.getTextPosition());
    }

    /**
     * Simulates the human editing the connection in Archi: a real command on the real
     * {@link CommandStack} that is not agent-authored, so the guard sees a genuine intervention.
     * It moves the label to a different position than the proposal asks for, so overwriting is
     * observable rather than coincidentally identical.
     */
    private void humanEdits(IDiagramModelArchimateConnection conn) {
        stack.execute(new Command("Human moves the label") {
            @Override
            public void execute() {
                conn.setName("Renamed by the human");
                conn.setTextPosition(1);
            }
        });
    }

    /** The single approval card this session has queued, as the dock would read it. */
    private ProposalDto onlyCard() {
        List<ProposalDto> pending = dispatcher.getPendingProposalDtos(SESSION);
        assertEquals("exactly one proposal queued", 1, pending.size());
        return pending.get(0);
    }

    /**
     * Queues the command {@code create-relationship} queues for an unspecialized relationship,
     * without going through the tool — see the relationship-slot test above for why.
     */
    private IArchimateRelationship queueRelationshipCreate(String id) {
        IArchimateRelationship pending = factory.createAssociationRelationship();
        pending.setId(id);
        pending.setName("queued association");
        dispatcher.queueForBatch(SESSION, new CreateRelationshipCommand(
                pending, model.getFolder(FolderType.RELATIONS), source, target),
                "Create AssociationRelationship");
        return pending;
    }

    // ---- member 1: bulk parity reference -------------------------------------------------------

    /**
     * The parity reference for the queued update: the same two operations through
     * {@code bulk-mutate}, which already bridges this seam by handing the back-referenced
     * connection object to the same prepare. Batch must end up where bulk already is.
     */
    @Test
    public void shouldUpdateBackReferencedConnection_whenTheSameTwoOpsRunAsBulk() throws Exception {
        List<BulkOperation> operations = List.of(
                new BulkOperation("add-connection-to-view", Map.of(
                        "viewId", view.getId(), "relationshipId", relationship.getId(),
                        "sourceViewObjectId", sourceObj.getId(),
                        "targetViewObjectId", targetObj.getId())),
                new BulkOperation("update-view-connection", Map.of(
                        "viewConnectionId", "$0.id",
                        "showLabel", false,
                        "labelPosition", "source")));

        BulkMutationResult result = accessor.executeBulk(SESSION, operations, null, false);

        assertTrue("bulk must apply both operations", result.allSucceeded());
        String connId = result.operations().get(0).entityId();
        IDiagramModelArchimateConnection conn = findConnection(connId);
        assertNotNull("bulk connection must exist", conn);
        assertFalse("bulk must have hidden the label", conn.isNameVisible());
        assertEquals("bulk must have moved the label", 0, conn.getTextPosition());
    }

    // ---- member 1: approval-card degradation ---------------------------------------------------

    /**
     * With a queued connection the approval card cannot name the view it lives in — the connection
     * is not attached to a diagram yet, so {@code resolveViewName} has nothing to walk. The clause
     * is dropped rather than filled with a guess, and this pins that it stays dropped: a later
     * change must not turn a graceful degradation into a confident wrong name.
     *
     * <p>Both this test and its committed-connection sibling drive a {@code showLabel}-only call,
     * so the sentence names label visibility. They previously expected {@code "Update bendpoints
     * …"} — the unconditional wording, which described a change the call did not make; the clause
     * they exist to pin is the trailing one, and it is unaffected by which aspect is named.</p>
     */
    @Test
    public void shouldDropTheViewClause_whenTheApprovedConnectionIsStillQueued() throws Exception {
        dispatcher.beginBatch(SESSION, "queue then approve");
        String connId = connect();
        approvalRequired = true;

        MutationResult<ViewConnectionDto> result = updateConnection(connId, Boolean.FALSE, null);

        assertTrue("approval mode must store a proposal", result.isProposal());
        String description = result.proposalContext().description();
        assertEquals("the view clause must be dropped, not guessed",
                "Update label visibility for connection (AssociationRelationship)", description);
        assertFalse("no view name may appear", description.contains(view.getName()));

        approvalRequired = false;
        dispatcher.endBatch(SESSION, false);
    }

    /**
     * The same card for a <em>committed</em> connection does name its view, which is what makes the
     * clause's absence above a degradation rather than the tool never having had one.
     */
    @Test
    public void shouldNameTheView_whenTheConnectionIsCommitted() {
        String connId = connect();
        approvalRequired = true;

        MutationResult<ViewConnectionDto> result = updateConnection(connId, Boolean.FALSE, null);

        assertTrue("approval mode must store a proposal", result.isProposal());
        assertEquals("a live connection resolves its view",
                "Update label visibility for connection (AssociationRelationship) in view 'Connecting'",
                result.proposalContext().description());
    }

    /**
     * Approving the stored proposal re-runs the prepare, so the queue is read at approve time
     * rather than captured at store time. Approving while the batch is still open must therefore
     * still resolve the queued connection.
     */
    @Test
    public void shouldResolveTheQueuedConnectionAgain_whenTheProposalIsApproved() throws Exception {
        dispatcher.beginBatch(SESSION, "queue then approve");
        String connId = connect();
        approvalRequired = true;

        MutationResult<ViewConnectionDto> result = updateConnection(connId, Boolean.FALSE, null);
        String proposalId = result.proposalContext().proposalId();

        approvalRequired = false;
        dispatcher.approveProposal(SESSION, proposalId);

        dispatcher.endBatch(SESSION, true);
        IDiagramModelArchimateConnection conn = findConnection(connId);
        assertNotNull("connection must exist after commit", conn);
        assertFalse("the approved update must have landed", conn.isNameVisible());
    }

    // ---- member 2: a same-batch-created view is embeddable -------------------------------------

    /**
     * A view created earlier in the batch must be embeddable as a reference by a later
     * {@code add-view-reference-to-view} in that same batch.
     */
    @Test
    public void shouldEmbedQueuedView_whenItWasCreatedEarlierInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "create then embed");
        String newViewId = accessor.createView(SESSION, "Fresh View", null, null, null)
                .entity().id();
        MutationResult<EmbeddedViewDto> result = accessor.addViewReferenceToView(
                SESSION, view.getId(), newViewId, 10, 300, 185, 80, null, null);
        dispatcher.endBatch(SESSION, true);

        assertEquals("the reference must name the queued view",
                newViewId, result.entity().referencedViewId());
        IDiagramModelReference ref = findReference(view, result.entity().viewObjectId());
        assertNotNull("the reference must exist on the host view after commit", ref);
        assertNotNull("the reference must point at a real view after commit",
                ref.getReferencedModel());
        assertEquals("and it must be the view the batch created",
                newViewId, ref.getReferencedModel().getId());
    }

    /** A committed view stays embeddable inside a batch — the fallback must not shadow the live path. */
    @Test
    public void shouldEmbedCommittedView_whenNamedInsideABatch() throws Exception {
        dispatcher.beginBatch(SESSION, "embed live");
        MutationResult<EmbeddedViewDto> result = accessor.addViewReferenceToView(
                SESSION, view.getId(), otherView.getId(), 10, 300, 185, 80, null, null);
        dispatcher.endBatch(SESSION, true);

        IDiagramModelReference ref = findReference(view, result.entity().viewObjectId());
        assertNotNull("the reference must exist after commit", ref);
        assertSame("a committed view must still resolve the ordinary way",
                otherView, ref.getReferencedModel());
    }

    /**
     * The negative control for member 2: a genuinely absent referenced view keeps its own message
     * and error code byte for byte. It must not be rerouted through the host-view resolver, whose
     * message is the different "View not found: " — a caller distinguishing the host-view failure
     * from the referenced-view failure has to keep being able to.
     */
    @Test
    public void shouldStillThrow_whenReferencedViewIsGenuinelyAbsentInsideBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "absent referenced view");
        try {
            assertThrows(() -> accessor.addViewReferenceToView(
                            SESSION, view.getId(), "no-such-view", 10, 300, 185, 80, null, null),
                    "Referenced view not found or is not an ArchiMate view: no-such-view",
                    ErrorCode.VIEW_NOT_FOUND);
        } finally {
            dispatcher.endBatch(SESSION, false);
        }
    }

    /** An absent <em>host</em> view keeps its own distinct message, so the two stay tellable apart. */
    @Test
    public void shouldKeepTheHostViewFailureDistinct_fromTheReferencedViewFailure() throws Exception {
        dispatcher.beginBatch(SESSION, "absent host view");
        try {
            assertThrows(() -> accessor.addViewReferenceToView(
                            SESSION, "no-such-host", otherView.getId(), 10, 300, 185, 80, null, null),
                    "View not found: no-such-host", ErrorCode.VIEW_NOT_FOUND);
        } finally {
            dispatcher.endBatch(SESSION, false);
        }
    }

    // ---- member 2: bulk parity reference -------------------------------------------------------

    /**
     * The asymmetry closed: {@code bulk-mutate} embeds a view the same call created, exactly as an
     * open batch does.
     *
     * <p>The two routes reach it differently and neither can serve the other. A batch resolves the
     * id against its command queue; a bulk call has no queue, it holds the views it creates in a
     * local back-reference map. So the referenced view arrives through the same
     * {@code findBackReferenced} lookup the host {@code viewId} has always used, handed to the
     * prepare in its own slot — the host slot cannot carry it, the two ids fail differently on
     * purpose.</p>
     */
    @Test
    public void shouldEmbedABulkCreatedView_whenTheSameCallCreatedIt() throws Exception {
        List<BulkOperation> operations = List.of(
                new BulkOperation("create-view", Map.of("name", "Bulk Fresh View")),
                new BulkOperation("add-view-reference-to-view", Map.of(
                        "viewId", view.getId(), "referencedViewId", "$0.id",
                        "x", 10, "y", 300, "width", 185, "height", 80)));

        BulkMutationResult result = accessor.executeBulk(SESSION, operations, null, false);

        assertTrue("bulk must apply both operations", result.allSucceeded());
        String newViewId = result.operations().get(0).entityId();
        IDiagramModelReference ref = findReference(view, result.operations().get(1).entityId());
        assertNotNull("the reference must exist on the host view", ref);
        assertNotNull("and point at a real view", ref.getReferencedModel());
        assertEquals("which must be the view this same call created",
                newViewId, ref.getReferencedModel().getId());
    }

    /**
     * The negative control on the bulk route: an id no operation in the call created and no
     * committed view carries still fails with the referenced-view message, re-wrapped as the
     * ordinary bulk failure. The back-reference must not swallow a real absence.
     */
    @Test
    public void shouldStillThrow_whenABulkReferencedViewIsGenuinelyAbsent() throws Exception {
        List<BulkOperation> operations = List.of(
                new BulkOperation("create-view", Map.of("name", "Bulk Fresh View")),
                new BulkOperation("add-view-reference-to-view", Map.of(
                        "viewId", view.getId(), "referencedViewId", "no-such-view",
                        "x", 10, "y", 300, "width", 185, "height", 80)));

        try {
            accessor.executeBulk(SESSION, operations, null, false);
            fail("expected bulk to refuse a referenced view that does not exist");
        } catch (ModelAccessException e) {
            assertTrue("the bulk failure must name the referenced view: " + e.getMessage(),
                    e.getMessage().contains(
                            "Referenced view not found or is not an ArchiMate view: no-such-view"));
            // executeBulk re-wraps every per-operation failure, so the referenced-view code is not
            // client-visible here — the message is the whole of what the caller sees.
            assertEquals("and arrive as the ordinary bulk failure",
                    ErrorCode.BULK_VALIDATION_FAILED, e.getErrorCode());
        }
    }

    /**
     * The host slot and the referenced slot stay distinct on the bulk route too: an absent
     * <em>host</em> view reports the view-level message, which is how a caller tells which of the
     * two ids it got wrong.
     */
    @Test
    public void shouldKeepTheBulkHostViewFailureDistinct_fromTheReferencedViewFailure()
            throws Exception {
        List<BulkOperation> operations = List.of(
                new BulkOperation("create-view", Map.of("name", "Bulk Fresh View")),
                new BulkOperation("add-view-reference-to-view", Map.of(
                        "viewId", "no-such-host", "referencedViewId", "$0.id",
                        "x", 10, "y", 300, "width", 185, "height", 80)));

        try {
            accessor.executeBulk(SESSION, operations, null, false);
            fail("expected bulk to refuse an absent host view");
        } catch (ModelAccessException e) {
            assertTrue("the host-view failure must stay tellable apart: " + e.getMessage(),
                    e.getMessage().contains("View not found: no-such-host"));
        }
    }

    // ---- apply-view-layout: every id it takes must be addressable inside the batch ---------------

    /**
     * {@code apply-view-layout} is the one remaining caller of the connection prepare that takes the
     * id path, so a connection an earlier operation in the batch added could not be laid out in that
     * same batch — the exact unit of work an agent draws a view in.
     */
    @Test
    public void shouldLayOutAQueuedConnection_whenItWasAddedEarlierInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "connect then lay out");
        String connId = connect();
        accessor.applyViewLayout(SESSION, view.getId(), null,
                List.of(new ViewConnectionSpec(connId, List.of(new BendpointDto(30, 40, -30, 40)), null)),
                null);
        dispatcher.endBatch(SESSION, true);

        List<int[]> bendpoints = bendpointsOf(findConnection(connId));
        assertEquals("the layout must have landed on the queued connection", 1, bendpoints.size());
        assertArrayEquals("with the offsets it was given",
                new int[] { 30, 40, -30, 40 }, bendpoints.get(0));
    }

    /**
     * The negative control: the per-entry wrapper is what tells an agent <em>which</em> entry failed,
     * so a genuinely absent id must keep both the wrapper and the inner message.
     *
     * <p>Still exactly true now that the walk collects every failing entry instead of unwinding at
     * the first. A call with one bad entry — this one — reports what it always reported, wrapper,
     * inner message and error code alike, and gains no count clause; the list is what a call with
     * several gains. That is what makes these equalities a live contract rather than a fixture that
     * happens to still pass.</p>
     */
    @Test
    public void shouldStillThrow_whenALayoutConnectionIdIsGenuinelyAbsentInsideBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "absent layout connection");
        try {
            assertThrows(() -> accessor.applyViewLayout(SESSION, view.getId(), null,
                            List.of(new ViewConnectionSpec("no-such-connection", null, null)), null),
                    "Connection entry [0] (viewConnectionId='no-such-connection'): "
                            + "View object not found: no-such-connection",
                    ErrorCode.VIEW_OBJECT_NOT_FOUND);
        } finally {
            dispatcher.endBatch(SESSION, false);
        }
    }

    /**
     * The refusal keeps the original failure as its cause. The wrapper this replaced chained it,
     * and a change made to report MORE about a failure must not report less of it to whatever logs
     * the stack.
     */
    @Test
    public void shouldKeepTheOriginalFailureAsTheCause_whenALayoutEntryIsRefused() {
        try {
            accessor.applyViewLayout(SESSION, view.getId(), null,
                    List.of(new ViewConnectionSpec("no-such-connection", null, null)), null);
            fail("expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertNotNull("the refusal must carry the failure that caused it", e.getCause());
            assertEquals("View object not found: no-such-connection", e.getCause().getMessage());
        }
    }

    /** Outside a batch the same absent id fails identically — the fallback is batch-only. */
    @Test
    public void shouldStillThrow_whenALayoutConnectionIdIsAbsentOutsideBatch() {
        assertThrows(() -> accessor.applyViewLayout(SESSION, view.getId(), null,
                        List.of(new ViewConnectionSpec("no-such-connection", null, null)), null),
                "Connection entry [0] (viewConnectionId='no-such-connection'): "
                        + "View object not found: no-such-connection",
                ErrorCode.VIEW_OBJECT_NOT_FOUND);
    }

    /**
     * The {@code positions} half has the same blind spot as the {@code connections} half: it passed
     * null for every batch slot, so a view object the batch had only queued could not be positioned.
     */
    @Test
    public void shouldPositionAQueuedViewObject_whenItWasAddedEarlierInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "add then position");
        String queuedVo = accessor.addToView(SESSION, view.getId(), source.getId(),
                600, 0, 120, 55, false, null, null, null)
                .entity().viewObject().viewObjectId();
        accessor.applyViewLayout(SESSION, view.getId(),
                List.of(new ViewPositionSpec(queuedVo, 700, 90, 140, 60)), null, null);
        dispatcher.endBatch(SESSION, true);

        IDiagramModelObject placed = findChild(view, queuedVo);
        assertNotNull("the queued object must exist after commit", placed);
        assertEquals("x must be the laid-out one, not the added one", 700, placed.getBounds().getX());
        assertEquals("y must be the laid-out one", 90, placed.getBounds().getY());
        assertEquals("width must be the laid-out one", 140, placed.getBounds().getWidth());
        assertEquals("height must be the laid-out one", 60, placed.getBounds().getHeight());
    }

    /** The per-entry wrapper for a genuinely absent position id is preserved too. */
    @Test
    public void shouldStillThrow_whenALayoutPositionIdIsGenuinelyAbsentInsideBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "absent layout position");
        try {
            assertThrows(() -> accessor.applyViewLayout(SESSION, view.getId(),
                            List.of(new ViewPositionSpec("no-such-object", 10, 10, 120, 55)), null, null),
                    "Position entry [0] (viewObjectId='no-such-object'): "
                            + "View object not found: no-such-object",
                    ErrorCode.VIEW_OBJECT_NOT_FOUND);
        } finally {
            dispatcher.endBatch(SESSION, false);
        }
    }

    /**
     * The layout call validates its own {@code viewId} before it reaches either loop, and that
     * validation had no batch slot either — so a batch that creates a view and lays it out failed at
     * the first check, before any of the fixes above could be reached. This is the whole script an
     * agent runs to draw a view from scratch in one unit of work.
     */
    @Test
    public void shouldLayOutAQueuedView_whenItWasCreatedEarlierInSameBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "create a view then lay it out");
        String newViewId = accessor.createView(SESSION, "Drawn In One Batch", null, null, null)
                .entity().id();
        String queuedVo = accessor.addToView(SESSION, newViewId, source.getId(),
                0, 0, 120, 55, false, null, null, null)
                .entity().viewObject().viewObjectId();
        accessor.applyViewLayout(SESSION, newViewId,
                List.of(new ViewPositionSpec(queuedVo, 250, 150, 130, 60)), null, null);
        dispatcher.endBatch(SESSION, true);

        IArchimateDiagramModel created = null;
        for (Object child : model.getFolder(FolderType.DIAGRAMS).getElements()) {
            if (child instanceof IArchimateDiagramModel diagram && newViewId.equals(diagram.getId())) {
                created = diagram;
            }
        }
        assertNotNull("the batch must have created the view", created);
        IDiagramModelObject placed = findChild(created, queuedVo);
        assertNotNull("and placed the object on it", placed);
        assertEquals("laid out at the position the layout asked for", 250, placed.getBounds().getX());
        assertEquals("and the y it asked for", 150, placed.getBounds().getY());
    }

    /** An absent host view keeps the view-level message, inside a batch exactly as outside one. */
    @Test
    public void shouldStillThrow_whenTheLayoutViewIsGenuinelyAbsentInsideBatch() throws Exception {
        dispatcher.beginBatch(SESSION, "absent layout view");
        try {
            assertThrows(() -> accessor.applyViewLayout(SESSION, "no-such-view",
                            List.of(new ViewPositionSpec(sourceObj.getId(), 10, 10, 120, 55)), null, null),
                    "View not found: no-such-view", ErrorCode.VIEW_NOT_FOUND,
                    "Use get-views to find valid view IDs");
        } finally {
            dispatcher.endBatch(SESSION, false);
        }
    }

    /** A queued view object must not resolve as the layout's <em>view</em>. */
    @Test
    public void shouldStillThrow_whenTheLayoutViewIdNamesAQueuedObjectInstead() throws Exception {
        dispatcher.beginBatch(SESSION, "queued object as layout view");
        try {
            String queuedVo = accessor.addToView(SESSION, view.getId(), source.getId(),
                    600, 0, 120, 55, false, null, null, null)
                    .entity().viewObject().viewObjectId();
            assertThrows(() -> accessor.applyViewLayout(SESSION, queuedVo,
                            List.of(new ViewPositionSpec(sourceObj.getId(), 10, 10, 120, 55)), null, null),
                    "View not found: " + queuedVo, ErrorCode.VIEW_NOT_FOUND,
                    "Use get-views to find valid view IDs");
        } finally {
            dispatcher.endBatch(SESSION, false);
        }
    }

    // ---- a relationship-typed endpoint gets a reason, not an internal error --------------------

    /**
     * ArchiMate permits a relationship whose endpoint is another relationship, and no view object
     * can reference such an end. Both connection prepares used to fall over on that shape and
     * surface it as an internal error — the queue-aware one by dereferencing the null its resolver
     * answered, the direct one by casting a relationship to an element. Neither told the caller
     * anything it could act on.
     *
     * <p>The two failures are easy to mistake for one bug and easy to mistake for unrelated ones:
     * they throw different exceptions, and they are caught in different places, so the queue-aware
     * arm surfaces as "Error adding connection for relationship X to view Y" while the direct arm
     * surfaces as "Error executing bulk mutation". Fixing either alone leaves the other, which is
     * why both are pinned here against the same fixture.</p>
     */
    private IArchimateRelationship relationshipEndedRelationship() {
        IArchimateRelationship relToRel = factory.createAssociationRelationship();
        relToRel.setId("rel-to-rel");
        relToRel.setName("annotates the association");
        relToRel.setSource(source);
        relToRel.setTarget(relationship);
        model.getFolder(FolderType.RELATIONS).getElements().add(relToRel);
        return relToRel;
    }

    /** The queue-aware arm: no back-references anywhere, so the plain prepare runs. */
    @Test
    public void shouldReportAMismatch_whenARelationshipEndIsItselfARelationship() {
        IArchimateRelationship relToRel = relationshipEndedRelationship();

        assertThrows(() -> accessor.addConnectionToView(SESSION, view.getId(), relToRel.getId(),
                        sourceObj.getId(), targetObj.getId(), null, null, null, null, null),
                "Relationship 'rel-to-rel' does not connect the elements referenced by the "
                        + "source and target view objects",
                ErrorCode.RELATIONSHIP_MISMATCH,
                "Verify the relationship connects the correct elements, "
                        + "or use different view objects");
    }

    /**
     * The direct arm, reached by giving the call a back-reference. Same fixture, same verdict —
     * and the assertion is on the message rather than on absence-of-internal-error, because both
     * arms used to fail loudly enough to look handled.
     */
    @Test
    public void shouldReportAMismatchOnTheDirectArm_whenARelationshipEndIsItselfARelationship() {
        IArchimateRelationship relToRel = relationshipEndedRelationship();

        assertThrows(() -> accessor.executeBulk(SESSION, List.of(
                        new BulkOperation("add-to-view", Map.of(
                                "viewId", view.getId(), "elementId", source.getId(),
                                "x", 600, "y", 0, "width", 120, "height", 55)),
                        new BulkOperation("add-connection-to-view", Map.of(
                                "viewId", view.getId(), "relationshipId", relToRel.getId(),
                                "sourceViewObjectId", "$0.id",
                                "targetViewObjectId", targetObj.getId()))), null, false),
                "Operation 1 (add-connection-to-view): Relationship 'rel-to-rel' does not connect "
                        + "the elements referenced by the source and target view objects",
                ErrorCode.BULK_VALIDATION_FAILED);
    }

    /**
     * The detail line names the relationship's actual ends, so the caller can look them up and
     * discover what shape it built. It reads them off the raw ends rather than off the narrowed
     * ones, which is the only reason it can name a relationship-typed end at all.
     */
    @Test
    public void shouldNameBothRawEnds_whenTheMismatchDetailIsBuilt() {
        IArchimateRelationship relToRel = relationshipEndedRelationship();

        try {
            accessor.addConnectionToView(SESSION, view.getId(), relToRel.getId(),
                    sourceObj.getId(), targetObj.getId(), null, null, null, null, null);
            fail("expected the relationship-typed end to be rejected");
        } catch (ModelAccessException e) {
            assertEquals("the detail must name what the relationship really connects",
                    "Relationship connects actor-src -> rel-1, but view objects reference "
                            + "actor-src and actor-tgt",
                    e.getDetails());
        }
    }

    /**
     * The other half of the detail line, and the half a shared validator is most likely to lose.
     *
     * <p>A relationship the same batch queued is not connected until commit, so its own
     * {@code getSource()}/{@code getTarget()} are still null while the check runs — the ends are
     * known only through the dispatcher, which reads them off the create that will connect it. The
     * match has always used those resolved ends. The detail line must name them too: reporting the
     * raw ends here would print "nothing -&gt; nothing" for a relationship whose endpoints the server
     * had just finished resolving, turning the most actionable line in the error into noise on
     * exactly the deferred path this class exists to cover.</p>
     *
     * <p>Its sibling above pins the opposite direction — an end that is itself a relationship,
     * where the raw concept is the only thing that has an id. One helper has to serve both, so both
     * are pinned.</p>
     */
    @Test
    public void shouldNameTheQueuedEnds_whenAQueuedRelationshipDoesNotMatchTheViewObjects() {
        IBusinessActor other = factory.createBusinessActor();
        other.setId("actor-other");
        other.setName("Other Actor");
        model.getFolder(FolderType.BUSINESS).getElements().add(other);
        IDiagramModelArchimateObject otherObj = liveObject("vo-other", other, 800, 0, 120, 55);

        dispatcher.beginBatch(SESSION, "queued relationship, mismatched endpoints");
        try {
            IArchimateRelationship pending = queueRelationshipCreate("rel-queued-detail");
            assertNull("the fixture is only meaningful while the queued relationship is still "
                    + "unconnected", pending.getSource());

            accessor.addConnectionToView(SESSION, view.getId(), pending.getId(),
                    sourceObj.getId(), otherObj.getId(), null, null, null, null, null);
            fail("a queued relationship that does not connect these view objects must be rejected");
        } catch (ModelAccessException e) {
            assertEquals("the detail must name the ends the server resolved, not the nulls the "
                            + "relationship still carries before commit",
                    "Relationship connects actor-src -> actor-tgt, but view objects reference "
                            + "actor-src and actor-other",
                    e.getDetails());
        } finally {
            dispatcher.endBatch(SESSION, false);
        }
    }

    /**
     * The shape that cannot currently be built, and the guard that keeps it that way.
     *
     * <p>The direct prepare skips endpoint validation for a relationship the same call created but
     * has not connected yet: its {@code getSource()}/{@code getTarget()} are null and it is not yet
     * in containment. Narrowing those ends to elements <em>before</em> testing them would make a
     * relationship-typed end read as null too, so a relationship with two relationship-typed ends
     * and no container would satisfy all three conjuncts and skip the match check entirely —
     * accepted with no validation at all, on precisely the shape the check exists to reject.</p>
     *
     * <p>That state has no route through the tool surface today, which was verified rather than
     * assumed: the only way a detached relationship reaches this prepare is as a back-reference to
     * a {@code create-relationship} in the same call, and that tool resolves its {@code sourceId}
     * and {@code targetId} as <em>elements</em> and rejects a relationship id outright. So there is
     * no behaviour to assert, and a test claiming to provoke it would be provoking something
     * else.</p>
     *
     * <p>What can be asserted is the wiring, which is where the defect would actually live. The
     * skip must read the relationship's own ends, not values narrowed to {@code IArchimateElement}
     * first. If {@code create-relationship} ever widens to accept a relationship end, this becomes
     * reachable and the guard is already correct; if the skip is ever rewritten against narrowed
     * values, this pin goes red before that happens.</p>
     *
     * <p>The skip carries a fourth leg for a different reason. Its three null tests are satisfied
     * just as well by a relationship pulled from an <em>enclosing</em> batch's queue, whose ends
     * are null before commit for exactly the same reason — so once that prepare began resolving
     * such a relationship, the three legs alone would have skipped validation on every one of them.
     * Only the call's own back-reference distinguishes the two, which is why it is required here.</p>
     */
    @Test
    public void shouldComputeTheDeferredConnectSkipFromRawEnds_notFromNarrowedOnes() {
        String source = readAccessorSource();
        int at = source.indexOf("boolean isSameCallBackRef");
        assertTrue("the deferred-connect skip must still exist to be pinned", at > 0);
        String skip = source.substring(at, source.indexOf(';', at));

        assertTrue("the skip must test the relationship's OWN ends — narrowing them first gives "
                        + "null a second meaning and disables the check it guards: " + skip,
                skip.contains("relationship.getSource() == null")
                        && skip.contains("relationship.getTarget() == null"));
        assertTrue("and must still require the relationship to be out of containment, which is "
                        + "the leg that tells a deferred connect from a committed mismatch: " + skip,
                skip.contains("relationship.eContainer() == null"));
        assertTrue("and must require the back-reference itself, which is the only leg that tells a "
                        + "relationship THIS CALL created from one an enclosing batch queued — "
                        + "without it the skip disables validation for every outer-queued "
                        + "relationship: " + skip,
                skip.contains("directRelationship != null"));
    }

    /**
     * The pin above is only worth trusting if it has been seen to fail, so the same reading is run
     * over the narrowed shape it exists to reject.
     */
    @Test
    public void shouldRejectTheNarrowedSkipShape_soThePinIsKnownToBeAbleToFail() {
        String planted = "boolean isSameCallBackRef = relSource == null && relTarget == null "
                + "&& relationship.eContainer() == null;";
        String skip = planted.substring(0, planted.indexOf(';'));
        assertFalse("the detector must not accept a skip computed from narrowed locals",
                skip.contains("relationship.getSource() == null")
                        && skip.contains("relationship.getTarget() == null"));
    }

    // ---- the view is walked once per call, not once per endpoint ------------------------------

    /**
     * A group that records how often its children are read, so "walks the view twice" is a number
     * rather than an assertion about the shape of the code.
     *
     * <p>{@code collectViewObjectMap} recurses into every container it finds, so one build of the
     * lookup map reads this group's children exactly once. Extending the real EMF implementation
     * rather than stubbing the interface keeps it a legitimate child of the view — containment,
     * notification and the id lookup all behave as they do for any other group.</p>
     */
    private static final class WalkCountingGroup
            extends com.archimatetool.model.impl.DiagramModelGroup {
        private int walks;

        @Override
        public org.eclipse.emf.common.util.EList<IDiagramModelObject> getChildren() {
            walks++;
            return super.getChildren();
        }
    }

    private WalkCountingGroup countingGroupOnTheView() {
        WalkCountingGroup counter = new WalkCountingGroup();
        counter.setId("vo-counting-group");
        counter.setName("Counter");
        counter.setBounds(0, 400, 200, 100);
        view.getChildren().add(counter);
        return counter;
    }

    /**
     * Both endpoints of one connection search the same view, so the lookup map is built once for
     * the call rather than once for each end. Correctness is unaffected either way — this is the
     * cost, measured.
     */
    @Test
    public void shouldWalkTheViewOnce_whenBothEndpointsAreResolvedById() {
        WalkCountingGroup counter = countingGroupOnTheView();
        int before = counter.walks;

        accessor.addConnectionToView(SESSION, view.getId(), relationship.getId(),
                sourceObj.getId(), targetObj.getId(), null, null, null, null, null);

        assertEquals("one connection resolving two ids must walk the view's containment once, "
                        + "not once per endpoint", 1, counter.walks - before);
    }

    /**
     * And a call that supplies both endpoints directly must not walk it at all. This is the case
     * the lazy build exists for: a fully back-referenced bulk operation already holds both objects,
     * so there is nothing to look up and no reason to pay for a map.
     */
    @Test
    public void shouldNotWalkTheViewAtAll_whenBothEndpointsAreBackReferenced() {
        WalkCountingGroup counter = countingGroupOnTheView();

        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("add-to-view", Map.of(
                        "viewId", view.getId(), "elementId", source.getId(),
                        "x", 600, "y", 0, "width", 120, "height", 55)),
                new BulkOperation("add-to-view", Map.of(
                        "viewId", view.getId(), "elementId", target.getId(),
                        "x", 800, "y", 0, "width", 120, "height", 55)),
                new BulkOperation("add-connection-to-view", Map.of(
                        "viewId", view.getId(), "relationshipId", relationship.getId(),
                        "sourceViewObjectId", "$0.id",
                        "targetViewObjectId", "$1.id"))), null, false);
        assertTrue("the fixture is only meaningful if the call succeeded", result.allSucceeded());

        int walksDuringConnect = counter.walks;
        accessor.executeBulk(SESSION, List.of(
                new BulkOperation("add-to-view", Map.of(
                        "viewId", view.getId(), "elementId", source.getId(),
                        "x", 1000, "y", 0, "width", 120, "height", 55)),
                new BulkOperation("add-to-view", Map.of(
                        "viewId", view.getId(), "elementId", target.getId(),
                        "x", 1200, "y", 0, "width", 120, "height", 55)),
                new BulkOperation("add-connection-to-view", Map.of(
                        "viewId", view.getId(), "relationshipId", secondRelationship.getId(),
                        "sourceViewObjectId", "$0.id",
                        "targetViewObjectId", "$1.id"))), null, false);

        assertEquals("a fully back-referenced connection must build no lookup map, so the second "
                        + "identical call must cost exactly what the first did",
                walksDuringConnect, counter.walks - walksDuringConnect);
    }

    /** The accessor's own source, read for the wiring pin above. */
    private static String readAccessorSource() {
        java.nio.file.Path dir = java.nio.file.Path.of("").toAbsolutePath();
        String relative =
                "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/ArchiModelAccessorImpl.java";
        for (int i = 0; i < 6 && dir != null; i++) {
            java.nio.file.Path candidate = dir.resolve(relative);
            if (java.nio.file.Files.exists(candidate)) {
                try {
                    return java.nio.file.Files.readString(candidate,
                            java.nio.charset.StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new AssertionError("Could not read " + candidate, e);
                }
            }
            dir = dir.getParent();
        }
        throw new AssertionError("Could not locate " + relative + " by walking up from "
                + java.nio.file.Path.of("").toAbsolutePath());
    }

    /** The child view object with that id, found through live containment after commit. */
    private static IDiagramModelObject findChild(IArchimateDiagramModel host, String id) {
        for (Object child : host.getChildren()) {
            if (child instanceof IDiagramModelObject obj && id.equals(obj.getId())) {
                return obj;
            }
        }
        return null;
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
