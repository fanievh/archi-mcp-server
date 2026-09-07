package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IAccessRelationship;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IGrouping;
import com.archimatetool.model.IProfile;
import com.archimatetool.model.ITextAlignment;
import com.archimatetool.editor.model.IEditorModelManager;

import net.vheerden.archi.mcp.handlers.HandlerRegistrar;
import net.vheerden.archi.mcp.handlers.SearchHandler;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.session.SessionManager;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.AddToViewResultDto;
import net.vheerden.archi.mcp.response.dto.AdjustViewSpacingResultDto;
import net.vheerden.archi.mcp.response.dto.ArrangeGroupsResultDto;
import net.vheerden.archi.mcp.response.dto.AutoLayoutAndRouteResultDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.BulkOperationResult;
import net.vheerden.archi.mcp.response.dto.DeleteResultDto;
import net.vheerden.archi.mcp.response.dto.FolderDto;
import net.vheerden.archi.mcp.response.dto.LayoutFlatViewResultDto;
import net.vheerden.archi.mcp.response.dto.LayoutWithinGroupResultDto;
import net.vheerden.archi.mcp.response.dto.MovedViewObjectDto;
import net.vheerden.archi.mcp.response.dto.OptimizeGroupOrderResultDto;
import net.vheerden.archi.mcp.response.dto.ResizeElementsResultDto;
import net.vheerden.archi.mcp.response.dto.ResizedGroupDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;
import net.vheerden.archi.mcp.response.dto.ViewObjectDto;

/**
 * Cross-tool contract test for the effective-state invariant: <em>a mutating tool's success
 * response must describe the state the model now holds, never the values the caller passed in.</em>
 *
 * <p>The client of this server is an LLM agent that cannot see the canvas, so the response IS its
 * only ground truth. A confident wrong value is worse than an error, because the agent builds its
 * next action on it and the failure surfaces far downstream, unattributable. Fixing one call site
 * does nothing to stop the next tool being born with the same bug — hence a contract test over
 * every registered tool rather than a per-tool afterthought.</p>
 *
 * <h2>The pawl</h2>
 *
 * <p>Every registered tool must be classified exactly once: {@link #READ_ONLY} (mutates nothing, no
 * obligation), {@link #ORACLE_COVERED} (actively asserted below), or listed in
 * {@code tools/effective-state-gaps.txt}. A tool in none of the three fails the build — that is the
 * "a new tool must register or fail" property. The gap registry can only shrink:
 * {@link #GAP_ENTRY_CEILING} is lowered, never raised, in the same commit that deletes an entry,
 * exactly as {@code tools/size-ratchet.sh} lowers {@code CEILING_LOC}.</p>
 *
 * <h2>What "effective" means in each of the three mutation modes</h2>
 *
 * <p>{@code MutationResult} distinguishes immediate, batched and proposal results. Nothing is
 * effective in the latter two <em>by construction</em> — the command has not run — so a projection
 * is the only thing a response there can honestly contain. The invariant is therefore discharged in
 * those modes by an <strong>envelope</strong> obligation instead of a value obligation: the entity
 * must be structurally labelled as not-yet-effective (nested under {@code preview}, beside a
 * {@code batch} or {@code proposal} sibling) rather than presented at the top level where an agent
 * would read it as state the model holds. That labelling is asserted here, not assumed, because it
 * is otherwise only a per-handler convention with nothing enforcing it.</p>
 *
 * <h2>What a report of effective state has to contain</h2>
 *
 * <p>A field that reports <em>that</em> something changed, without reporting <em>what it changed
 * to</em>, does not satisfy the invariant when the changed value is agent-actionable geometry. A
 * count or a boolean is an index into missing data, not a report of state. Equally, a field named
 * as an outcome must not be sourced from the request that asked for it.</p>
 */
public class EffectiveStateContractTest {

    /**
     * Maximum number of entries permitted in {@code tools/effective-state-gaps.txt}.
     *
     * <p><strong>LOWER-ONLY.</strong> Drop this by exactly the number of lines removed, in the same
     * commit that removes them. Never raise it: a new tool that cannot report effective state must
     * be fixed, not admitted. History (lower-only): 45 (initial) -> 44 (resize-elements-to-fit
     * reports the groups its parent-fit cascade grew) -> 43 (bulk-mutate reports per-operation
     * effective geometry and describes what its deletions destroyed) -> 39 (the four folder and
     * specialization CRUD tools derive their reported name and path from the value the write
     * receives) -> 38 (delete-relationship's two zero cascade counts pinned as correct rather than
     * unmeasured) -> 37 (add-to-view names the containers its icon-band reservation grew) -> 35
     * (adjust-view-spacing names the groups its parent-fit cascade grew underneath the spacing it
     * was asked for; layout-within-group names what each ancestor it re-fitted became and derives
     * groupResized from an observation instead of echoing the autoResize request) -> 33 (the two
     * connection tools: the bulk per-operation entry now carries the connection's post-dispatch
     * state under {@code effectiveConnection}, so both of their paths report it and the gap is
     * closed on the path that was actually open rather than on the one that already passed) -> 32
     * (update-relationship: the re-read it already performed is now proven by an oracle that drives
     * the tool through the registry and asserts a cleared name arrives present-and-empty rather
     * than as the name the model held before) -&gt; 31 (update-element, on the same basis: its
     * oracle changes documentation alone and asserts the response still reports the name, which a
     * response echoing the request could not have produced) -&gt; 30 (arrange-groups reports where
     * each container it positioned actually landed, read back from the model after the write; the
     * count it used to report alone was what let a run that skipped fifteen containers still read
     * as a success).</p>
     */
    private static final int GAP_ENTRY_CEILING = 30;

    /**
     * Tools that mutate nothing, so the invariant does not apply. Batch-mode and session-mode
     * tools are here because their responses describe the <em>session</em>, not the model.
     */
    private static final Set<String> READ_ONLY = Set.of(
            "assess-layout", "begin-batch", "detect-hub-elements", "end-batch", "export-view",
            "find-concept-usage", "get-batch-status", "get-element", "get-folder-tree",
            "get-folders", "get-guidance", "get-model-info", "get-relationships", "get-session-filters",
            "get-specialization-usage", "get-view-contents", "get-views", "list-model-images",
            "list-pending-approvals", "list-specializations", "search-elements",
            "search-relationships", "set-session-filter");

    /**
     * Mutating tools this suite actively asserts an oracle for. Membership is a claim that a test
     * method below re-reads the model and compares — never a claim that the tool "looks fine".
     */
    private static final Set<String> ORACLE_COVERED = Set.of(
            "add-connection-to-view",
            "add-to-view",
            "adjust-view-spacing",
            "arrange-groups",
            "auto-route-connections",
            "bulk-mutate",
            "layout-within-group",
            "create-folder",
            "create-specialization",
            "delete-relationship",
            "resize-elements-to-fit",
            "update-element",
            "update-folder",
            "update-relationship",
            "update-specialization",
            "update-view-connection",
            "update-view-object");

    private static final String GAP_FILE = "tools/effective-state-gaps.txt";
    private static final String SESSION = "effective-state-contract-session";

    /** The session id {@code HandlerUtils.extractSessionId} falls back to when no SessionManager. */
    private static final String HANDLER_SESSION = "default";

    private IArchimateFactory factory;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private CommandStack stack;
    private IArchimateModel model;
    private IArchimateDiagramModel view;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        StubEditorModelManager stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Effective State Contract Fixture");
        model.setId("model-effective-state");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Contract");
        diagrams.getElements().add(view);

        IFolder business = model.getFolder(FolderType.BUSINESS);
        IBusinessActor actor = factory.createBusinessActor();
        actor.setId("actor-1");
        actor.setName("Contract Actor");
        business.getElements().add(actor);

        IBusinessActor peer = factory.createBusinessActor();
        peer.setId("actor-2");
        peer.setName("Peer Actor");
        business.getElements().add(peer);

        IBusinessActor third = factory.createBusinessActor();
        third.setId("actor-3");
        third.setName("Third Actor");
        business.getElements().add(third);

        IBusinessActor fourth = factory.createBusinessActor();
        fourth.setId("actor-4");
        fourth.setName("Fourth Actor");
        business.getElements().add(fourth);

        IArchimateRelationship rel = factory.createAssociationRelationship();
        rel.setId("rel-1");
        rel.setSource(actor);
        rel.setTarget(peer);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);

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
            @Override
            public UndoRedoState undo(int steps) {
                // adjust-view-spacing applies, assesses and undoes before returning; production
                // marshals that onto the SWT display, which no headless run has.
                for (int i = 0; i < steps && stack.canUndo(); i++) {
                    stack.undo();
                }
                return null;
            }
            private Command toPlainCompound(Command command) {
                // Rebuilt through the guard's own factory rather than flattened. Flattening drops
                // the execution-time re-check, so an operation that production would decline runs
                // here instead — and a test asserting what a declined operation reports would be
                // asserting against an operation that never declined. The same rebuild the two
                // decline-focused suites in this package already do.
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

    // ---- The pawl -----------------------------------------------------------------------------

    /**
     * The register-or-fail property. A tool registered with the server and classified nowhere is a
     * tool nothing checks — which is exactly how the invariant was violated three separate times
     * before this test existed.
     */
    @Test
    public void shouldClassifyEveryRegisteredTool_soANewToolMustRegisterOrFail() throws IOException {
        Set<String> gaps = readGapRegistry().keySet();
        Set<String> unclassified = new TreeSet<>();

        for (String tool : registeredToolNames()) {
            boolean classified = READ_ONLY.contains(tool)
                    || ORACLE_COVERED.contains(tool)
                    || gaps.contains(tool);
            if (!classified) {
                unclassified.add(tool);
            }
        }

        assertTrue("These registered tools are classified nowhere. Every tool must be declared "
                + "read-only, given an oracle in this test, or listed in " + GAP_FILE
                + " with a reason — a tool nothing checks is how this invariant gets re-broken: "
                + unclassified,
                unclassified.isEmpty());
    }

    /**
     * The reverse direction. A gap entry naming a tool that no longer exists is dead weight that
     * quietly inflates the ceiling and buys headroom nobody reviewed.
     */
    @Test
    public void shouldRejectStaleClassifications_whenAToolIsRenamedOrRemoved() throws IOException {
        Set<String> registered = registeredToolNames();
        Set<String> stale = new TreeSet<>();

        for (String tool : readGapRegistry().keySet()) {
            if (!registered.contains(tool)) {
                stale.add(tool + " (in " + GAP_FILE + ")");
            }
        }
        for (String tool : READ_ONLY) {
            if (!registered.contains(tool)) {
                stale.add(tool + " (in READ_ONLY)");
            }
        }
        for (String tool : ORACLE_COVERED) {
            if (!registered.contains(tool)) {
                stale.add(tool + " (in ORACLE_COVERED)");
            }
        }

        assertTrue("These classifications name tools that are no longer registered. Delete them "
                + "rather than leaving them to inflate the gap ceiling: " + stale,
                stale.isEmpty());
    }

    /** A tool may not be excused twice; overlapping classification hides which one is load-bearing. */
    @Test
    public void shouldClassifyEachToolExactlyOnce_soAnExcuseCannotBeDoubleCounted() throws IOException {
        Set<String> gaps = readGapRegistry().keySet();
        Set<String> doubled = new TreeSet<>();

        for (String tool : registeredToolNames()) {
            int classifications = (READ_ONLY.contains(tool) ? 1 : 0)
                    + (ORACLE_COVERED.contains(tool) ? 1 : 0)
                    + (gaps.contains(tool) ? 1 : 0);
            if (classifications > 1) {
                doubled.add(tool);
            }
        }

        assertTrue("These tools are classified more than once: " + doubled, doubled.isEmpty());
    }

    /**
     * The ratchet click. Each repaired tool deletes one line here and lowers the ceiling by one, so
     * the registry is monotonically shrinking rather than a list that grows whenever it is easier
     * to admit a gap than to close one.
     */
    @Test
    public void shouldKeepTheGapRegistryAtOrBelowItsCeiling_whichOnlyEverLowers() throws IOException {
        int entries = readGapRegistry().size();
        assertTrue("The effective-state gap registry has " + entries + " entries against a ceiling "
                + "of " + GAP_ENTRY_CEILING + ". The ceiling is LOWER-ONLY: close the gap and drop "
                + "the ceiling, never the reverse.",
                entries <= GAP_ENTRY_CEILING);
    }

    /** Every entry must carry a status and a reason, so the registry stays reviewable. */
    @Test
    public void shouldRequireAStatusAndAReasonOnEveryGapEntry() throws IOException {
        Map<String, String> gaps = readGapRegistry();
        List<String> malformed = new ArrayList<>();

        for (Map.Entry<String, String> entry : gaps.entrySet()) {
            String rest = entry.getValue();
            boolean hasStatus = rest.startsWith("KNOWN-GAP ") || rest.startsWith("UNAUDITED ");
            int hash = rest.indexOf('#');
            boolean hasReason = hash >= 0 && rest.substring(hash + 1).trim().length() >= 20;
            if (!hasStatus || !hasReason) {
                malformed.add(entry.getKey());
            }
        }

        assertTrue("Gap entries must read '<tool> <KNOWN-GAP|UNAUDITED> # <reason>'. Malformed: "
                + malformed, malformed.isEmpty());
    }

    // ---- The envelope obligation (batched and proposal modes) ---------------------------------

    /**
     * In batch mode nothing has executed, so the queued payload is a projection by construction.
     * That is honest only while it is <em>labelled</em> as one. This asserts the label rather than
     * trusting the convention: the entity must sit under {@code preview}, beside a {@code batch}
     * sibling, and must not appear where an agent would read it as state the model holds.
     */
    @Test
    public void shouldLabelAQueuedPayloadAsAPreview_ratherThanPresentingItAsEffectiveState()
            throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        String groupId = accessor.addGroupToView(HANDLER_SESSION, view.getId(), "G",
                0, 0, 200, 200, null, null, null).entity().viewObjectId();

        // A handler built without a SessionManager resolves every call to the "default" session,
        // so the batch must be opened on that same session or the invocation runs immediately.
        dispatcher.beginBatch(HANDLER_SESSION, "queued update");
        Map<String, Object> envelope;
        try {
            envelope = invokeTool(registry, "update-view-object",
                    Map.of("viewObjectId", groupId, "x", 40, "y", 60));
        } finally {
            dispatcher.endBatch(HANDLER_SESSION, true);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) envelope.get("result");
        assertNotNull("queued response must still use the standard envelope", result);

        assertTrue("a queued payload must be nested under 'preview' — an agent reading a top-level "
                + "entity has no way to know the model does not hold it yet",
                result.containsKey("preview"));
        assertTrue("the 'preview' nesting must be accompanied by a 'batch' sibling that names the "
                + "deferred state", result.containsKey("batch"));
        assertFalse("the entity must NOT also appear at the top level of 'result'",
                result.containsKey("viewObjectId"));
        assertFalse("the entity's geometry must NOT appear at the top level of 'result'",
                result.containsKey("x"));
    }

    /**
     * The same envelope obligation, for the two spacing and layout tools that report objects the
     * caller never named. A field that names objects the model does not yet hold is exactly the kind
     * an agent would act on, so the label matters more here than anywhere: queued, it must sit under
     * {@code preview}, never at the top level of {@code result}.
     */
    @Test
    public void shouldLabelTheNewlyReportedGeometryAsAPreview_whenTheCallIsQueued() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        String outerId = accessor.addGroupToView(HANDLER_SESSION, view.getId(), "Outer",
                0, 0, 300, 300, null, null, null).entity().viewObjectId();
        String innerId = accessor.addGroupToView(HANDLER_SESSION, view.getId(), "Inner",
                10, 10, 260, 200, outerId, null, null).entity().viewObjectId();
        accessor.addToView(HANDLER_SESSION, view.getId(), "actor-1", 10, 30, 60, 30, false, innerId, null, null);
        accessor.addToView(HANDLER_SESSION, view.getId(), "actor-2", 90, 30, 60, 30, false, innerId, null, null);
        // Deliberately wider than its siblings: a single-level grid gives every cell the widest
        // element's width, so the queued layout below really does populate resizedElements. With
        // three equal children it would be empty, and NON_EMPTY omission alone would then satisfy
        // the "absent from the top level" assertion no matter what the preview labelling did.
        accessor.addToView(HANDLER_SESSION, view.getId(), "actor-3", 170, 30, 160, 30, false, innerId, null, null);

        dispatcher.beginBatch(HANDLER_SESSION, "queue both reporting tools");
        Map<String, Object> spacing;
        Map<String, Object> layout;
        try {
            spacing = invokeTool(registry, "adjust-view-spacing",
                    Map.of("viewId", view.getId(), "interElementDelta", 60));
            layout = invokeTool(registry, "layout-within-group",
                    Map.of("viewId", view.getId(), "groupViewObjectId", innerId,
                            "arrangement", "grid", "columns", 3));
        } finally {
            dispatcher.endBatch(HANDLER_SESSION, true);
        }

        assertPreviewLabelled(spacing, "adjust-view-spacing", "groupsAdjusted");
        assertPreviewLabelled(layout, "layout-within-group", "elementsRepositioned");

        // The guard the negative assertions need: this call really did name resized children, so
        // "not at the top level" is a statement about where a populated list went, not a restatement
        // of the empty-omission rule.
        @SuppressWarnings("unchecked")
        Map<String, Object> preview = (Map<String, Object>)
                ((Map<String, Object>) layout.get("result")).get("preview");
        assertNotNull("the queued layout must carry a preview entity", preview);
        Object resized = preview.get("resizedElements");
        assertTrue("fixture must actually produce a non-empty resizedElements inside the preview, "
                + "or the top-level assertion above cannot discriminate. preview was: " + preview,
                resized instanceof List && !((List<?>) resized).isEmpty());

        // The same guard for the OTHER tool's envelope. Each arm needs its own populated list:
        // an always-empty list plus NON_EMPTY omission satisfies "absent from the top level"
        // however the labelling behaves, so one arm's populated preview proves nothing about
        // the next one's.
        @SuppressWarnings("unchecked")
        Map<String, Object> spacingPreview = (Map<String, Object>)
                ((Map<String, Object>) spacing.get("result")).get("preview");
        assertNotNull("the queued spacing call must carry a preview entity", spacingPreview);
        Object spacingResized = spacingPreview.get("resizedElements");
        assertTrue("the queued spacing fixture must itself produce a non-empty resizedElements "
                + "inside its preview. preview was: " + spacingPreview,
                spacingResized instanceof List && !((List<?>) spacingResized).isEmpty());
    }

    /**
     * A queued payload must be nested under {@code preview} beside a {@code batch} sibling, with
     * nothing from the entity left at the top level of {@code result}.
     */
    private static void assertPreviewLabelled(Map<String, Object> envelope, String tool,
            String entityField) {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) envelope.get("result");
        assertNotNull(tool + ": queued response must still use the standard envelope", result);
        assertTrue(tool + ": a queued payload must be nested under 'preview'",
                result.containsKey("preview"));
        assertTrue(tool + ": the 'preview' nesting must be accompanied by a 'batch' sibling",
                result.containsKey("batch"));
        assertFalse(tool + ": the entity must NOT also appear at the top level of 'result'",
                result.containsKey(entityField));
        assertFalse(tool + ": the newly reported geometry must NOT appear at the top level",
                result.containsKey("resizedAncestors"));
        assertFalse(tool + ": nor may the resized-leaf geometry, which is newer still and would "
                + "otherwise read as state the model already holds",
                result.containsKey("resizedElements"));
    }

    // ---- The geometry oracle -------------------------------------------------------------------

    /**
     * {@code update-view-object} on the immediate path: the reported bounds must equal the bounds a
     * fresh read of the model returns. This is the oracle shape every geometry-bearing tool is held
     * to — execute, then re-read, then compare — rather than trusting the DTO the prepare built.
     */
    @Test
    public void shouldReportTheBoundsTheModelHolds_whenUpdateViewObjectMovesAnObject() {
        String groupId = accessor.addGroupToView(SESSION, view.getId(), "G",
                0, 0, 200, 200, null, null, null).entity().viewObjectId();

        ViewObjectDto reported = accessor.updateViewObject(SESSION, groupId,
                140, 90, 260, 180, null, null, null, null, null, null, null, null).entity();

        assertBoundsMatchModel("update-view-object", groupId,
                reported.x(), reported.y(), reported.width(), reported.height());
    }

    /**
     * The same oracle under the condition the invariant actually exists for: the container
     * auto-fits, so the value that lands is not the value that was asked for. A response that
     * echoed the request would pass a naive equality check against itself and fail this one.
     */
    @Test
    public void shouldReportTheBoundsTheModelHolds_whenAChildForcesItsParentGroupToGrow() {
        String parentId = accessor.addGroupToView(SESSION, view.getId(), "Parent",
                0, 0, 200, 200, null, null, null).entity().viewObjectId();
        String childId = accessor.addGroupToView(SESSION, view.getId(), "Child",
                10, 10, 60, 60, parentId, null, null).entity().viewObjectId();

        // Push the child well past the parent's right/bottom edge so the parent-fit cascade fires.
        ViewObjectDto reportedChild = accessor.updateViewObject(SESSION, childId,
                20, 20, 400, 300, null, null, null, null, null, null, null, null).entity();

        assertBoundsMatchModel("update-view-object (child)", childId,
                reportedChild.x(), reportedChild.y(),
                reportedChild.width(), reportedChild.height());

        IDiagramModelObject parent = find(view, parentId);
        assertNotNull("parent group must still exist", parent);
        assertTrue("fixture must actually trigger the auto-fit this oracle exists to catch — "
                + "the parent should have grown past its requested 200 width, but it is "
                + parent.getBounds().getWidth(),
                parent.getBounds().getWidth() > 200);
    }

    /** The entry naming {@code id} in a reported resize list, or null. */
    private static MovedViewObjectDto entryIn(List<MovedViewObjectDto> entries, String id) {
        for (MovedViewObjectDto entry : entries) {
            if (id.equals(entry.viewObjectId())) {
                return entry;
            }
        }
        return null;
    }

    /**
     * {@code adjust-view-spacing}, the geometry it produces that nobody asked for. Inflating the
     * gaps between elements pushes some of them past the edge of the group they sit in, and the
     * parent-fit cascade widens every enclosing group so it still contains them. The caller asked
     * for a spacing delta; a group it never named changed shape.
     *
     * <p>This is the oracle the tool's gap-registry entry was holding a place for. Asserted
     * field-by-field against the model and against the serialized response, because a value that
     * exists in Java but never reaches JSON would satisfy a typed assertion while leaving the client
     * just as blind. The fixture guard asserts the growth actually happened, so the oracle cannot
     * pass by proving nothing.</p>
     */
    @Test
    public void shouldNameTheGroupsItGrew_whenAdjustViewSpacingOutgrowsAnEnclosingGroup()
            throws Exception {
        String outerId = accessor.addGroupToView(SESSION, view.getId(), "Outer",
                0, 0, 300, 300, null, null, null).entity().viewObjectId();
        String innerId = accessor.addGroupToView(SESSION, view.getId(), "Inner",
                10, 10, 260, 200, outerId, null, null).entity().viewObjectId();
        accessor.addToView(SESSION, view.getId(), "actor-1", 10, 30, 60, 30, false, innerId, null, null);
        accessor.addToView(SESSION, view.getId(), "actor-2", 90, 30, 60, 30, false, innerId, null, null);
        accessor.addToView(SESSION, view.getId(), "actor-3", 170, 30, 60, 30, false, innerId, null, null);

        String roomyId = accessor.addGroupToView(SESSION, view.getId(), "Roomy",
                600, 0, 2000, 1200, null, null, null).entity().viewObjectId();
        accessor.addToView(SESSION, view.getId(), "actor-4", 20, 60, 60, 30, false, roomyId, null, null);

        int outerWidthBefore = find(view, outerId).getBounds().getWidth();
        int roomyWidthBefore = find(view, roomyId).getBounds().getWidth();
        AdjustViewSpacingResultDto dto =
                accessor.adjustViewSpacing(SESSION, view.getId(), 60, null, null, true).entity();
        IBounds outerAfter = find(view, outerId).getBounds();
        IBounds roomyAfter = find(view, roomyId).getBounds();

        assertTrue("fixture must actually grow the enclosing group, or this oracle proves nothing: "
                + outerWidthBefore + " -> " + outerAfter.getWidth(),
                outerAfter.getWidth() != outerWidthBefore);
        assertTrue("...and must also SHRINK a roomy TOP-LEVEL container, the other direction of "
                + "the same re-fit and the largest single resize this call makes: "
                + roomyWidthBefore + " -> " + roomyAfter.getWidth(),
                roomyAfter.getWidth() < roomyWidthBefore);

        assertFalse("resizedAncestors must not be empty once the cascade grew a group",
                dto.resizedAncestors().isEmpty());
        for (MovedViewObjectDto grown : dto.resizedAncestors()) {
            IDiagramModelObject live = find(view, grown.viewObjectId());
            assertNotNull("adjust-view-spacing reported a group not in the view: "
                    + grown.viewObjectId(), live);
            IBounds actual = live.getBounds();
            assertEquals("adjust-view-spacing " + grown.name() + ": x", actual.getX(), grown.newX());
            assertEquals("adjust-view-spacing " + grown.name() + ": y", actual.getY(), grown.newY());
            assertEquals("adjust-view-spacing " + grown.name() + ": width",
                    actual.getWidth(), grown.newWidth());
            assertEquals("adjust-view-spacing " + grown.name() + ": height",
                    actual.getHeight(), grown.newHeight());
        }

        // The second axis of the same oracle: the objects INSIDE those groups whose size the pass
        // changed. Inner is re-fitted by the recursion and then written at that new size by its
        // parent's placement loop, so it is named here as well as in resizedAncestors — and both
        // must carry the rectangle the model ended up holding, not the one their own pass proposed.
        assertFalse("resizedElements must not be empty once the recursion re-fitted a container "
                + "that its parent then placed at the new size", dto.resizedElements().isEmpty());
        assertNotNull("...and it must name the top-level container the call re-fitted. A top-level "
                + "container is a child of the VIEW, so no placement loop ever holds it: it is the "
                + "one resize an observation map structurally cannot see. resizedElements was: "
                + dto.resizedElements(), entryIn(dto.resizedElements(), roomyId));
        for (MovedViewObjectDto sized : dto.resizedElements()) {
            IDiagramModelObject live = find(view, sized.viewObjectId());
            assertNotNull("adjust-view-spacing reported an object not in the view: "
                    + sized.viewObjectId(), live);
            IBounds actual = live.getBounds();
            assertEquals("resized " + sized.name() + ": x", actual.getX(), sized.newX());
            assertEquals("resized " + sized.name() + ": y", actual.getY(), sized.newY());
            assertEquals("resized " + sized.name() + ": width",
                    actual.getWidth(), sized.newWidth());
            assertEquals("resized " + sized.name() + ": height",
                    actual.getHeight(), sized.newHeight());
        }

        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(dto);
        assertTrue("the response must name the enclosing group it grew. Response was: " + json,
                json.contains(outerId));
        assertTrue("...and carry its effective width (" + outerAfter.getWidth() + "). Response was: "
                + json, json.contains("\"newWidth\":" + outerAfter.getWidth()));
        assertTrue("the resized-element rectangles must reach the wire too. Response was: " + json,
                json.contains("\"resizedElements\""));
    }

    /**
     * The same tool, the same field, with the geometry decided by the batch rather than by the fit.
     *
     * <p>The pass now measures every container it re-fits against what the batch has already queued
     * for it, so a group the caller sized in the same batch keeps that size. The rectangles the tool
     * reports have to follow: naming a group at the size the fit <em>would</em> have chosen, while
     * the model commits the size the batch asked for, is the same defect one field over — a
     * confident wrong value in the only ground truth the caller has.</p>
     *
     * <p>Asserted against the model <em>after</em> the batch closes, not against the tool's own
     * echo, and only for the groups the tool actually named: a group whose size the batch — not this
     * pass — established is not something this pass resized, and is deliberately absent from the
     * report.</p>
     */
    @Test
    public void shouldReportTheFlooredRectangles_whenAdjustViewSpacingRunsInsideABatch()
            throws Exception {
        String outerId = accessor.addGroupToView(SESSION, view.getId(), "Outer",
                0, 0, 300, 300, null, null, null).entity().viewObjectId();
        String innerId = accessor.addGroupToView(SESSION, view.getId(), "Inner",
                10, 10, 260, 200, outerId, null, null).entity().viewObjectId();
        accessor.addToView(SESSION, view.getId(), "actor-1", 10, 30, 60, 30, false, innerId, null, null);
        accessor.addToView(SESSION, view.getId(), "actor-2", 90, 30, 60, 30, false, innerId, null, null);
        accessor.addToView(SESSION, view.getId(), "actor-3", 170, 30, 60, 30, false, innerId, null, null);

        String roomyId = accessor.addGroupToView(SESSION, view.getId(), "Roomy",
                600, 0, 2000, 1200, null, null, null).entity().viewObjectId();
        accessor.addToView(SESSION, view.getId(), "actor-4", 20, 60, 60, 30, false, roomyId, null, null);
        int roomyWidthBefore = find(view, roomyId).getBounds().getWidth();

        dispatcher.beginBatch(SESSION, "size the enclosing group, then space the view");
        accessor.updateViewObject(SESSION, outerId, null, null, 1200, 900, null, null, null, null);
        AdjustViewSpacingResultDto dto =
                accessor.adjustViewSpacing(SESSION, view.getId(), 60, null, null, true).entity();
        dispatcher.endBatch(SESSION, true);

        IBounds outerAfter = find(view, outerId).getBounds();
        assertEquals("the size the batch queued must survive the pass, or this proves nothing",
                1200, outerAfter.getWidth());
        assertEquals(900, outerAfter.getHeight());
        IBounds roomyAfter = find(view, roomyId).getBounds();
        assertTrue("...while the container the batch queued NOTHING for is unfloored and shrinks, "
                + "so the batch frame is being applied per object rather than globally: "
                + roomyWidthBefore + " -> " + roomyAfter.getWidth(),
                roomyAfter.getWidth() < roomyWidthBefore);

        assertFalse("resizedElements must not be empty: the pass re-fitted a top-level container",
                dto.resizedElements().isEmpty());
        assertNotNull("...and must name that container. resizedElements was: "
                + dto.resizedElements(), entryIn(dto.resizedElements(), roomyId));

        for (MovedViewObjectDto grown : dto.resizedAncestors()) {
            IDiagramModelObject live = find(view, grown.viewObjectId());
            assertNotNull("adjust-view-spacing reported a group not in the view: "
                    + grown.viewObjectId(), live);
            IBounds actual = live.getBounds();
            assertEquals("batched adjust-view-spacing " + grown.name() + ": x",
                    actual.getX(), grown.newX());
            assertEquals("batched adjust-view-spacing " + grown.name() + ": y",
                    actual.getY(), grown.newY());
            assertEquals("batched adjust-view-spacing " + grown.name() + ": width",
                    actual.getWidth(), grown.newWidth());
            assertEquals("batched adjust-view-spacing " + grown.name() + ": height",
                    actual.getHeight(), grown.newHeight());
        }

        for (MovedViewObjectDto sized : dto.resizedElements()) {
            IDiagramModelObject live = find(view, sized.viewObjectId());
            assertNotNull("adjust-view-spacing reported an object not in the view: "
                    + sized.viewObjectId(), live);
            IBounds actual = live.getBounds();
            assertEquals("batched resized " + sized.name() + ": x", actual.getX(), sized.newX());
            assertEquals("batched resized " + sized.name() + ": y", actual.getY(), sized.newY());
            assertEquals("batched resized " + sized.name() + ": width",
                    actual.getWidth(), sized.newWidth());
            assertEquals("batched resized " + sized.name() + ": height",
                    actual.getHeight(), sized.newHeight());
        }
    }

    /**
     * {@code layout-flat-view}'s first oracle. The tool had NO end-to-end coverage of any kind
     * before this: every existing test drives the handler over a stub accessor, so its geometry ran
     * in no test at all.
     *
     * <p>The subject is the one thing the call changes that the caller did not ask for — a parent
     * element grown to contain the children the same call laid out inside it. Its only trace was a
     * local boolean used to decide whether to re-run the top-level arrangement, and then dropped:
     * the flag case the invariant names explicitly.</p>
     *
     * <p>Execute, re-read the model, compare field by field, and assert the rectangle reaches the
     * serialized response — a value that exists in Java but never reaches JSON leaves the client
     * exactly as blind. The fixture guard asserts the growth actually happened.</p>
     */
    @Test
    public void shouldReportTheBoundsTheModelHolds_whenLayoutFlatViewGrowsAParent()
            throws Exception {
        String parentId = accessor.addToView(SESSION, view.getId(), "actor-1",
                0, 0, 140, 60, false, null, null, null).entity().viewObject().viewObjectId();
        accessor.addToView(SESSION, view.getId(), "actor-2", 10, 30, 120, 55, false,
                parentId, null, null);
        accessor.addToView(SESSION, view.getId(), "actor-3", 10, 100, 120, 55, false,
                parentId, null, null);
        accessor.addToView(SESSION, view.getId(), "actor-4", 600, 0, 140, 60, false, null, null,
                null);

        int heightBefore = find(view, parentId).getBounds().getHeight();
        LayoutFlatViewResultDto dto = accessor.layoutFlatView(SESSION, view.getId(), "row",
                null, null, null, null, null, true).entity();
        IBounds parentAfter = find(view, parentId).getBounds();

        assertTrue("fixture must actually grow the parent, or this oracle proves nothing: "
                + heightBefore + " -> " + parentAfter.getHeight(),
                parentAfter.getHeight() != heightBefore);

        assertFalse("resizedElements must not be empty once a parent was grown",
                dto.resizedElements().isEmpty());
        for (MovedViewObjectDto sized : dto.resizedElements()) {
            IDiagramModelObject live = find(view, sized.viewObjectId());
            assertNotNull("layout-flat-view reported an object not in the view: "
                    + sized.viewObjectId(), live);
            IBounds actual = live.getBounds();
            assertEquals("layout-flat-view " + sized.name() + ": x", actual.getX(), sized.newX());
            assertEquals("layout-flat-view " + sized.name() + ": y", actual.getY(), sized.newY());
            assertEquals("layout-flat-view " + sized.name() + ": width",
                    actual.getWidth(), sized.newWidth());
            assertEquals("layout-flat-view " + sized.name() + ": height",
                    actual.getHeight(), sized.newHeight());
        }

        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(dto);
        assertTrue("the rectangle must reach the wire. Response was: " + json,
                json.contains("\"resizedElements\""));
        assertTrue("...carrying the parent it grew. Response was: " + json,
                json.contains(parentId));
        assertTrue("...at its effective height (" + parentAfter.getHeight() + "). Response was: "
                + json, json.contains("\"newHeight\":" + parentAfter.getHeight()));
    }

    /**
     * The same field, queued. Nothing is effective inside a batch, so the entity is discharged by
     * LABELLING: it must sit under {@code preview} beside a {@code batch} sibling, never at the top
     * level of {@code result}. The fixture is guarded to actually populate the list — an
     * always-empty list plus the empty-omission satisfies "absent from the top level" however the
     * labelling behaves.
     */
    @Test
    public void shouldLabelTheFlatLayoutGeometryAsAPreview_whenTheCallIsQueued() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        String parentId = accessor.addToView(HANDLER_SESSION, view.getId(), "actor-1",
                0, 0, 140, 60, false, null, null, null).entity().viewObject().viewObjectId();
        accessor.addToView(HANDLER_SESSION, view.getId(), "actor-2", 10, 30, 120, 55, false,
                parentId, null, null);
        accessor.addToView(HANDLER_SESSION, view.getId(), "actor-3", 10, 100, 120, 55, false,
                parentId, null, null);

        dispatcher.beginBatch(HANDLER_SESSION, "queue a flat layout");
        Map<String, Object> flat;
        try {
            flat = invokeTool(registry, "layout-flat-view",
                    Map.of("viewId", view.getId(), "arrangement", "row"));
        } finally {
            dispatcher.endBatch(HANDLER_SESSION, true);
        }

        assertPreviewLabelled(flat, "layout-flat-view", "elementsRepositioned");

        @SuppressWarnings("unchecked")
        Map<String, Object> preview = (Map<String, Object>)
                ((Map<String, Object>) flat.get("result")).get("preview");
        assertNotNull("the queued flat layout must carry a preview entity", preview);
        Object resized = preview.get("resizedElements");
        assertTrue("the queued flat fixture must itself produce a non-empty resizedElements inside "
                + "its preview, or the top-level assertion cannot discriminate. preview was: "
                + preview, resized instanceof List && !((List<?>) resized).isEmpty());
    }

    /**
     * {@code optimize-group-order}'s first oracle.
     *
     * <p>Reordering re-runs a group's arrangement, and a grid arrangement gives every cell the
     * width of the widest element in that group — so reordering a group silently widens its narrow
     * children. The response carried {@code elementsReordered}, a count of children re-placed, and
     * no geometry at all.</p>
     *
     * <p>Execute, re-read the model, compare field by field. The fixture guard asserts a child
     * really was widened, so the oracle cannot pass by proving nothing.</p>
     */
    @Test
    public void shouldReportTheBoundsTheModelHolds_whenOptimizeGroupOrderWidensAChild()
            throws Exception {
        String left = accessor.addGroupToView(SESSION, view.getId(), "Left",
                0, 0, 700, 700, null, null, null).entity().viewObjectId();
        String right = accessor.addGroupToView(SESSION, view.getId(), "Right",
                900, 0, 700, 700, null, null, null).entity().viewObjectId();
        // Two more actors and two more relationships, so each group holds THREE children wired
        // ACROSS in a crossed order. The optimiser skips a group it has nothing to reorder, and a
        // fixture it skips resizes nothing at all — which would make this oracle pass by proving
        // nothing rather than by observing the widening.
        for (String id : List.of("actor-5", "actor-6")) {
            IBusinessActor extra = factory.createBusinessActor();
            extra.setId(id);
            extra.setName(id);
            model.getFolder(FolderType.BUSINESS).getElements().add(extra);
        }
        String narrow = accessor.addToView(SESSION, view.getId(), "actor-1",
                20, 40, 60, 55, false, left, null, null).entity().viewObject().viewObjectId();
        String wide = accessor.addToView(SESSION, view.getId(), "actor-3",
                20, 140, 300, 55, false, left, null, null).entity().viewObject().viewObjectId();
        String third = accessor.addToView(SESSION, view.getId(), "actor-5",
                20, 240, 120, 55, false, left, null, null).entity().viewObject().viewObjectId();
        String peerA = accessor.addToView(SESSION, view.getId(), "actor-2",
                20, 40, 120, 55, false, right, null, null).entity().viewObject().viewObjectId();
        String peerB = accessor.addToView(SESSION, view.getId(), "actor-4",
                20, 140, 120, 55, false, right, null, null).entity().viewObject().viewObjectId();
        String peerC = accessor.addToView(SESSION, view.getId(), "actor-6",
                20, 240, 120, 55, false, right, null, null).entity().viewObject().viewObjectId();
        crossRelationship("rel-cross-1", "actor-1", "actor-6");
        crossRelationship("rel-cross-2", "actor-3", "actor-4");
        crossRelationship("rel-cross-3", "actor-5", "actor-2");
        accessor.addConnectionToView(SESSION, view.getId(), "rel-cross-1", narrow, peerC,
                null, null, null, null, null);
        accessor.addConnectionToView(SESSION, view.getId(), "rel-cross-2", wide, peerB,
                null, null, null, null, null);
        accessor.addConnectionToView(SESSION, view.getId(), "rel-cross-3", third, peerA,
                null, null, null, null, null);

        int narrowBefore = find(view, narrow).getBounds().getWidth();
        OptimizeGroupOrderResultDto dto = accessor.optimizeGroupOrder(SESSION, view.getId(),
                "grid", 40, 10, null, null, false, 2, null).entity();
        IBounds narrowAfter = find(view, narrow).getBounds();

        assertTrue("fixture must actually widen a child, or this oracle proves nothing: "
                + narrowBefore + " -> " + narrowAfter.getWidth(),
                narrowAfter.getWidth() != narrowBefore);

        assertFalse("resizedElements must not be empty once the grid arm widened a child",
                dto.resizedElements().isEmpty());
        for (MovedViewObjectDto sized : dto.resizedElements()) {
            IDiagramModelObject live = find(view, sized.viewObjectId());
            assertNotNull("optimize-group-order reported an object not in the view: "
                    + sized.viewObjectId(), live);
            IBounds actual = live.getBounds();
            assertEquals("optimize-group-order " + sized.name() + ": x",
                    actual.getX(), sized.newX());
            assertEquals("optimize-group-order " + sized.name() + ": y",
                    actual.getY(), sized.newY());
            assertEquals("optimize-group-order " + sized.name() + ": width",
                    actual.getWidth(), sized.newWidth());
            assertEquals("optimize-group-order " + sized.name() + ": height",
                    actual.getHeight(), sized.newHeight());
        }

        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(dto);
        assertTrue("the rectangle must reach the wire. Response was: " + json,
                json.contains("\"resizedElements\""));
        assertTrue("...at the width the model holds (" + narrowAfter.getWidth() + "). Response "
                + "was: " + json, json.contains("\"newWidth\":" + narrowAfter.getWidth()));
    }

    /**
     * {@code auto-layout-and-route} grouped mode's first oracle.
     *
     * <p>This one is not a missing observation but a discarded one: the recursive descent already
     * recorded every leaf it re-sized, and the call site read {@code fittedContainers} and
     * {@code depthCapHit} out of the result and dropped the rest. Until now the ONLY reader of that
     * collection in the whole plugin was {@code layout-within-group}.</p>
     *
     * <p>Execute, re-read the model, compare field by field, with a fixture guard that the descent
     * actually re-sized something.</p>
     */
    @Test
    public void shouldReportTheBoundsTheModelHolds_whenGroupedLayoutResizesALeaf()
            throws Exception {
        String outer = accessor.addGroupToView(SESSION, view.getId(), "Outer",
                0, 0, 600, 500, null, null, null).entity().viewObjectId();
        accessor.addToView(SESSION, view.getId(), "actor-1", 10, 30, 60, 55, false, outer,
                null, null);
        accessor.addToView(SESSION, view.getId(), "actor-2", 10, 120, 300, 55, false, outer,
                null, null);
        String second = accessor.addGroupToView(SESSION, view.getId(), "Second",
                800, 0, 400, 400, null, null, null).entity().viewObjectId();
        accessor.addToView(SESSION, view.getId(), "actor-3", 10, 30, 120, 55, false, second,
                null, null);

        AutoLayoutAndRouteResultDto dto = accessor.autoLayoutAndRoute(SESSION, view.getId(),
                "grouped", "DOWN", 50, null, null).entity();

        assertFalse("fixture must actually make the descent re-size a leaf, or this oracle proves "
                + "nothing", dto.resizedElements().isEmpty());

        for (MovedViewObjectDto sized : dto.resizedElements()) {
            IDiagramModelObject live = find(view, sized.viewObjectId());
            assertNotNull("grouped mode reported an object not in the view: "
                    + sized.viewObjectId(), live);
            IBounds actual = live.getBounds();
            assertEquals("grouped " + sized.name() + ": x", actual.getX(), sized.newX());
            assertEquals("grouped " + sized.name() + ": y", actual.getY(), sized.newY());
            assertEquals("grouped " + sized.name() + ": width",
                    actual.getWidth(), sized.newWidth());
            assertEquals("grouped " + sized.name() + ": height",
                    actual.getHeight(), sized.newHeight());
        }

        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(dto);
        assertTrue("the rectangle must reach the wire. Response was: " + json,
                json.contains("\"resizedElements\""));
    }

    /**
     * {@code arrange-groups}, which reported only how many containers it moved.
     *
     * <p>{@code groupsPositioned: 4} is a count, and a count is an index into information the
     * caller does not have. On the run that motivated this, four native groups were positioned and
     * fifteen ArchiMate {@code Grouping} containers were skipped; the response was a success, and
     * nothing in it could have told an agent that cannot see the canvas otherwise.
     *
     * <p>Both container kinds are arranged here, so the oracle also settles whether the tool's
     * promise to preserve each container's width and height survives the write — Archi re-fits a
     * container to its children, and an element container is not obliged to behave like a native
     * group under {@code setBounds}. The answer is read from the model rather than assumed.
     */
    @Test
    public void shouldReportWhereEachContainerLanded_forArrangeGroups() throws Exception {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        com.archimatetool.model.IGrouping zone = f.createGrouping();
        zone.setId("zone-1");
        zone.setName("Cloud Zone");
        model.getFolder(FolderType.OTHER).getElements().add(zone);

        String nativeId = accessor.addGroupToView(SESSION, view.getId(), "Native Zone",
                0, 0, 200, 150, null, null, null).entity().viewObjectId();
        accessor.addToView(SESSION, view.getId(), "actor-1", 10, 30, 60, 30, false, nativeId, null, null);
        String zoneId = accessor.addToView(SESSION, view.getId(), "zone-1",
                0, 0, 200, 150, false, null, null, null).entity().viewObject().viewObjectId();
        accessor.addToView(SESSION, view.getId(), "actor-2", 10, 30, 60, 30, false, zoneId, null, null);

        Map<String, String> sizeBefore = new LinkedHashMap<>();
        for (String id : List.of(nativeId, zoneId)) {
            IBounds b = find(view, id).getBounds();
            sizeBefore.put(id, b.getWidth() + "x" + b.getHeight());
        }

        ArrangeGroupsResultDto dto = accessor.arrangeGroups(
                SESSION, view.getId(), "column", null, 40, null, null).entity();

        assertEquals("both container kinds must be counted", 2, dto.groupsPositioned());
        assertEquals("every counted container must also be located",
                dto.groupsPositioned(), dto.positionedContainers().size());

        for (MovedViewObjectDto placed : dto.positionedContainers()) {
            IDiagramModelObject live = find(view, placed.viewObjectId());
            assertNotNull("arrange-groups reported a container not in the view: "
                    + placed.viewObjectId(), live);
            IBounds actual = live.getBounds();
            assertEquals("arrange-groups " + placed.name() + ": x", actual.getX(), placed.newX());
            assertEquals("arrange-groups " + placed.name() + ": y", actual.getY(), placed.newY());
            assertEquals("arrange-groups " + placed.name() + ": width",
                    actual.getWidth(), placed.newWidth());
            assertEquals("arrange-groups " + placed.name() + ": height",
                    actual.getHeight(), placed.newHeight());

            // The tool documents that it preserves each container's width and height. Asserted
            // against the re-read rather than against the command, so a divergence introduced by
            // Archi's auto-fit would surface here rather than in a user's exported PNG.
            assertEquals("arrange-groups must preserve " + placed.name() + "'s size, and the "
                    + "response must say so from the model",
                    sizeBefore.get(placed.viewObjectId()),
                    placed.newWidth() + "x" + placed.newHeight());
        }

        // Discriminates: a report that never left the request would still match the model if the
        // arrangement moved nothing, so the fixture must actually have relocated a container.
        assertTrue("the arrangement must actually have moved something",
                dto.positionedContainers().stream().anyMatch(p -> p.newY() != 0));

        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(dto);
        assertTrue("the response must name the Grouping container it positioned. Response was: "
                + json, json.contains(zoneId));
    }

    /**
     * {@code layout-within-group}, the two things it used to state without observing. Laying a
     * nested group out re-fits every ancestor above it, which the response counted and never
     * described; and {@code groupResized} was the caller's own {@code autoResize} argument echoed
     * back under an outcome's name.
     *
     * <p>The second half is asserted by running the same layout twice: the second call changes
     * nothing, and a field sourced from the request cannot tell the difference.</p>
     */
    @Test
    public void shouldNameWhatItRefittedAndObserveWhetherItResized_forLayoutWithinGroup()
            throws Exception {
        String outerId = accessor.addGroupToView(SESSION, view.getId(), "Outer",
                0, 0, 400, 400, null, null, null).entity().viewObjectId();
        String innerId = accessor.addGroupToView(SESSION, view.getId(), "Inner",
                10, 10, 100, 100, outerId, null, null).entity().viewObjectId();
        accessor.addToView(SESSION, view.getId(), "actor-1", 10, 30, 60, 30, false, innerId, null, null);
        accessor.addToView(SESSION, view.getId(), "actor-2", 10, 70, 60, 30, false, innerId, null, null);

        int outerWidthBefore = find(view, outerId).getBounds().getWidth();
        LayoutWithinGroupResultDto dto = accessor.layoutWithinGroup(SESSION, view.getId(), innerId,
                "column", 20, 10, null, null, true, false, null, true, false).entity();
        IBounds outerAfter = find(view, outerId).getBounds();

        assertTrue("fixture must actually re-fit the ancestor, or this oracle proves nothing: "
                + outerWidthBefore + " -> " + outerAfter.getWidth(),
                outerAfter.getWidth() != outerWidthBefore);

        assertEquals("every counted ancestor must also be named",
                dto.ancestorsResized(), dto.resizedAncestors().size());
        assertFalse("resizedAncestors must not be empty once an ancestor was re-fitted",
                dto.resizedAncestors().isEmpty());
        for (MovedViewObjectDto grown : dto.resizedAncestors()) {
            IDiagramModelObject live = find(view, grown.viewObjectId());
            assertNotNull("layout-within-group reported an ancestor not in the view: "
                    + grown.viewObjectId(), live);
            IBounds actual = live.getBounds();
            assertEquals("layout-within-group " + grown.name() + ": x", actual.getX(), grown.newX());
            assertEquals("layout-within-group " + grown.name() + ": y", actual.getY(), grown.newY());
            assertEquals("layout-within-group " + grown.name() + ": width",
                    actual.getWidth(), grown.newWidth());
            assertEquals("layout-within-group " + grown.name() + ": height",
                    actual.getHeight(), grown.newHeight());
        }

        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(dto);
        assertTrue("the response must name the ancestor it re-fitted. Response was: " + json,
                json.contains(outerId));

        assertTrue("the first layout really did resize the group", dto.groupResized());
        IBounds innerAfterFirst = find(view, innerId).getBounds();
        LayoutWithinGroupResultDto again = accessor.layoutWithinGroup(SESSION, view.getId(), innerId,
                "column", 20, 10, null, null, true, false, null, true, false).entity();
        IBounds innerAfterSecond = find(view, innerId).getBounds();
        assertEquals("fixture guard: the second layout really does leave the size alone",
                innerAfterFirst.getWidth() + "x" + innerAfterFirst.getHeight(),
                innerAfterSecond.getWidth() + "x" + innerAfterSecond.getHeight());
        assertFalse("groupResized must be an observation — the second call resized nothing and "
                + "autoResize was still true", again.groupResized());
    }

    /**
     * The same oracle shape for the third thing {@code layout-within-group} changes without being
     * asked: the size of the children themselves.
     *
     * <p>Both arms write a full rectangle per child, and a grid cell takes its column's width — so
     * a narrow element sharing a column with a container that fitted wide is stretched to that
     * container's width. Measured on a real conversion, an element went from 120px to 2230px and
     * the response named nothing: {@code elementsRepositioned} counts moves, and a count of moves
     * that silently also covers resizes reports neither.</p>
     *
     * <p>Execute, re-read the model, compare — never the prepared DTO against itself. The fixture
     * guards itself twice over: the leaf's width must actually have changed, and both descendant
     * lists must be non-empty, or the disjointness half proves nothing.</p>
     */
    @Test
    public void shouldReportTheSizeTheLeavesLandedAt_forLayoutWithinGroup() throws Exception {
        String outerId = accessor.addGroupToView(SESSION, view.getId(), "Region",
                0, 0, 600, 600, null, null, null).entity().viewObjectId();
        String wideId = accessor.addGroupToView(SESSION, view.getId(), "Wide",
                10, 10, 100, 100, outerId, null, null).entity().viewObjectId();
        accessor.addToView(SESSION, view.getId(), "actor-1", 10, 30, 260, 30, false, wideId, null, null);
        String leafId = accessor.addToView(SESSION, view.getId(), "actor-2",
                10, 200, 60, 30, false, outerId, null, null).entity().viewObject().viewObjectId();

        int leafWidthBefore = find(view, leafId).getBounds().getWidth();
        LayoutWithinGroupResultDto dto = accessor.layoutWithinGroup(SESSION, view.getId(), outerId,
                "grid", 20, 10, null, null, true, false, 1, false, true).entity();
        IBounds leafAfter = find(view, leafId).getBounds();

        assertTrue("fixture must actually stretch the leaf, or this oracle proves nothing: "
                + leafWidthBefore + " -> " + leafAfter.getWidth(),
                leafAfter.getWidth() != leafWidthBefore);
        assertFalse("layout-within-group re-sized a child and must name it. resizedElements was: "
                + dto.resizedElements(), dto.resizedElements().isEmpty());

        for (MovedViewObjectDto resized : dto.resizedElements()) {
            IDiagramModelObject live = find(view, resized.viewObjectId());
            assertNotNull("layout-within-group reported an element not in the view: "
                    + resized.viewObjectId(), live);
            IBounds actual = live.getBounds();
            assertEquals("layout-within-group " + resized.name() + ": x",
                    actual.getX(), resized.newX());
            assertEquals("layout-within-group " + resized.name() + ": y",
                    actual.getY(), resized.newY());
            assertEquals("layout-within-group " + resized.name() + ": width",
                    actual.getWidth(), resized.newWidth());
            assertEquals("layout-within-group " + resized.name() + ": height",
                    actual.getHeight(), resized.newHeight());
        }

        assertFalse("fixture must also re-fit a container, or the partition claim is vacuous",
                dto.nestedContainersFitted().isEmpty());
        for (MovedViewObjectDto fitted : dto.nestedContainersFitted()) {
            for (MovedViewObjectDto resized : dto.resizedElements()) {
                assertFalse("a container named under nestedContainersFitted must not be repeated "
                        + "under resizedElements: " + fitted.viewObjectId(),
                        fitted.viewObjectId().equals(resized.viewObjectId()));
            }
        }

        // The observation half: re-running writes every child the same rectangle it already has.
        LayoutWithinGroupResultDto again = accessor.layoutWithinGroup(SESSION, view.getId(),
                outerId, "grid", 20, 10, null, null, true, false, 1, false, true).entity();
        IBounds leafAfterSecond = find(view, leafId).getBounds();
        assertEquals("fixture guard: the second call really does leave the leaf's size alone",
                leafAfter.getWidth() + "x" + leafAfter.getHeight(),
                leafAfterSecond.getWidth() + "x" + leafAfterSecond.getHeight());
        assertTrue("resizedElements must be an observation — the second call changed no size, so "
                + "it must name nothing. Was: " + again.resizedElements(),
                again.resizedElements().isEmpty());
    }

    /**
     * {@code update-view-object}'s oracle, extended to the objects the call changes that the caller
     * never named: everything anchored to the object being resized moves, and the group around it is
     * widened when the new rectangle no longer fits.
     *
     * <p>The landed position in the model is asserted <em>before</em> anything about the response,
     * because the mechanism that reports these moves is one argument away from a form that stops
     * making them — a report-only assertion would pass on a build where the anchored child never
     * moved at all.</p>
     */
    @Test
    public void shouldNameTheObjectsItDisplaced_whenUpdateViewObjectGrowsAnAnchorTarget()
            throws Exception {
        String groupId = accessor.addGroupToView(SESSION, view.getId(), "Around",
                0, 0, 200, 200, null, null, null).entity().viewObjectId();
        String targetId = accessor.addToView(SESSION, view.getId(), "actor-1",
                10, 10, 60, 30, false, groupId, null, null).entity().viewObject().viewObjectId();
        String childId = accessor.addToView(SESSION, view.getId(), "actor-2",
                10, 50, 60, 30, false, groupId, null, null).entity().viewObject().viewObjectId();
        accessor.updateViewObject(SESSION, childId, null, null, null, null,
                null, null, null, null, targetId, "below", 0, 10);

        int childYBefore = find(view, childId).getBounds().getY();
        int groupHeightBefore = find(view, groupId).getBounds().getHeight();

        ViewObjectDto dto = accessor.updateViewObject(SESSION, targetId,
                null, null, null, 260, null, null, null, null).entity();

        IBounds childAfter = find(view, childId).getBounds();
        IBounds groupAfter = find(view, groupId).getBounds();
        assertTrue("fixture must actually displace the anchored child, and the child must still be "
                + "MOVED and not merely reported: " + childYBefore + " -> " + childAfter.getY(),
                childAfter.getY() != childYBefore);
        assertTrue("fixture must actually grow the enclosing group: " + groupHeightBefore + " -> "
                + groupAfter.getHeight(), groupAfter.getHeight() > groupHeightBefore);

        // The group must end up around every object this call displaced, not only around the one
        // the caller named. Asserted with the cascade's own overflow predicate so the oracle and
        // the production fit cannot disagree about what "inside" means.
        assertFalse("the group must contain the anchored child this call displaced: child at y="
                        + childAfter.getY() + " h=" + childAfter.getHeight()
                        + " inside a group of height " + groupAfter.getHeight(),
                ParentFitCascade.childExceedsParentBounds(
                        childAfter.getX(), childAfter.getY(),
                        childAfter.getWidth(), childAfter.getHeight(),
                        groupAfter.getWidth(), groupAfter.getHeight(),
                        ArchiModelAccessorImpl.DEFAULT_GROUP_PADDING));

        assertFalse("movedObjects must name the displaced anchored child",
                dto.movedObjects().isEmpty());
        for (MovedViewObjectDto moved : dto.movedObjects()) {
            IBounds actual = find(view, moved.viewObjectId()).getBounds();
            assertEquals("update-view-object moved " + moved.name() + ": x", actual.getX(), moved.newX());
            assertEquals("update-view-object moved " + moved.name() + ": y", actual.getY(), moved.newY());
            assertEquals("update-view-object moved " + moved.name() + ": width",
                    actual.getWidth(), moved.newWidth());
            assertEquals("update-view-object moved " + moved.name() + ": height",
                    actual.getHeight(), moved.newHeight());
        }

        assertFalse("resizedAncestors must name the group it grew", dto.resizedAncestors().isEmpty());
        for (MovedViewObjectDto grown : dto.resizedAncestors()) {
            IBounds actual = find(view, grown.viewObjectId()).getBounds();
            assertEquals("update-view-object grew " + grown.name() + ": width",
                    actual.getWidth(), grown.newWidth());
            assertEquals("update-view-object grew " + grown.name() + ": height",
                    actual.getHeight(), grown.newHeight());
        }

        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(dto);
        assertTrue("the response must name the object it displaced. Response was: " + json,
                json.contains(childId));
        assertTrue("...and the group it grew. Response was: " + json, json.contains(groupId));
    }

    /**
     * {@code auto-route-connections} is the tool that already does this right — it returns a
     * {@code resizedGroups} list naming every group its parent-fit pass grew and the bounds each
     * grew to. Nothing in this story changes it; this oracle exists so that stays true, and so the
     * shape other tools are being held to is itself pinned rather than merely described.
     *
     * <p>The assertion is deliberately conditional-free: the fixture is asserted to have actually
     * produced a resize before the comparison runs. A response with an empty list would otherwise
     * pass vacuously, which is the "all-clear from a detector that never checked" anti-pattern.</p>
     */
    @Test
    public void shouldReportTheBoundsTheModelHolds_forEveryGroupAutoRouteReportsResizing() {
        String groupId = accessor.addGroupToView(SESSION, view.getId(), "Routed",
                0, 0, 200, 200, null, null, null).entity().viewObjectId();
        // Two connected children, the second placed well past the group's bounds: the connection
        // gives auto-route something to route, and the overflow makes the parent-fit pass fire.
        String sourceId = accessor.addToView(SESSION, view.getId(), "actor-1",
                10, 10, 120, 55, false, groupId, null, null).entity().viewObject().viewObjectId();
        String targetId = accessor.addToView(SESSION, view.getId(), "actor-2",
                20, 320, 120, 55, false, groupId, null, null).entity().viewObject().viewObjectId();
        accessor.addConnectionToView(SESSION, view.getId(), "rel-1",
                sourceId, targetId, null, null, null, null, null);

        // force MUST stay false: auto-route computes effectiveAutoNudge as (autoNudge && !force),
        // and it is the nudge pass that owns the parent-fit cascade this oracle checks.
        List<ResizedGroupDto> reported = accessor.autoRouteConnections(SESSION, view.getId(),
                null, null, false, true, 0, 0, null).entity().resizedGroups();

        assertNotNull("auto-route must report a resizedGroups list", reported);
        assertFalse("fixture must actually trigger a group resize, or this oracle proves nothing",
                reported.isEmpty());

        for (ResizedGroupDto group : reported) {
            IDiagramModelObject live = find(view, group.viewObjectId());
            assertNotNull("auto-route reported a resize for an object not in the view: "
                    + group.viewObjectId(), live);
            IBounds actual = live.getBounds();
            assertEquals("auto-route " + group.groupName() + ": reported x must be the x the model holds",
                    actual.getX(), group.newX());
            assertEquals("auto-route " + group.groupName() + ": reported y must be the y the model holds",
                    actual.getY(), group.newY());
            assertEquals("auto-route " + group.groupName() + ": reported width must be the width the model holds",
                    actual.getWidth(), group.newWidth());
            assertEquals("auto-route " + group.groupName() + ": reported height must be the height the model holds",
                    actual.getHeight(), group.newHeight());
        }
    }

    /**
     * {@code resize-elements-to-fit} grows elements to fit their labels, and the parent-fit cascade
     * grows any group those elements outgrow. The pass already computes the grown group bounds — it
     * threads a group-id-keyed fit-bounds map through every prepare it makes — so the agent being
     * unable to see the new group size is a dropped value, not an unknown one.
     *
     * <p>Asserted on the serialized response rather than a typed accessor: the wire shape is what
     * the agent actually receives, and a field that exists in Java but never reaches JSON would
     * satisfy a typed assertion while leaving the client just as blind.</p>
     */
    @Test
    public void shouldNameTheGroupsItGrew_whenResizeElementsToFitOutgrowsAParentGroup()
            throws Exception {
        String groupId = accessor.addGroupToView(SESSION, view.getId(), "Fit",
                0, 0, 100, 100, null, null, null).entity().viewObjectId();
        // Short names keep ElementSizer off the SWT path; undersized boxes still force a resize to
        // its 120x55 default, and two stacked children then outgrow the 100x100 group.
        accessor.addToView(SESSION, view.getId(), "actor-1",
                10, 10, 40, 20, false, groupId, null, null);
        accessor.addToView(SESSION, view.getId(), "actor-2",
                10, 40, 40, 20, false, groupId, null, null);

        int widthBefore = find(view, groupId).getBounds().getWidth();
        ResizeElementsResultDto dto =
                accessor.resizeElementsToFit(SESSION, view.getId(), null).entity();
        IBounds after = find(view, groupId).getBounds();

        assertTrue("fixture must actually grow the group, or this oracle proves nothing: "
                + widthBefore + " -> " + after.getWidth(), after.getWidth() > widthBefore);

        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(dto);
        assertTrue("the response must name the group it grew — the agent cannot see the canvas and "
                + "has no other way to learn the group moved. Response was: " + json,
                json.contains(groupId));
        assertTrue("the response must carry the group's effective width (" + after.getWidth() + "). "
                + "Response was: " + json,
                json.contains(String.valueOf(after.getWidth())));

        // ...and the reported bounds must be the ones the model holds, not the pass's intention.
        assertFalse("resizedGroups must not be empty once the group grew", dto.resizedGroups().isEmpty());
        for (ResizedGroupDto group : dto.resizedGroups()) {
            IDiagramModelObject live = find(view, group.viewObjectId());
            assertNotNull("resize-elements-to-fit reported a group not in the view: "
                    + group.viewObjectId(), live);
            IBounds actual = live.getBounds();
            assertEquals("resize-elements-to-fit " + group.groupName() + ": x",
                    actual.getX(), group.newX());
            assertEquals("resize-elements-to-fit " + group.groupName() + ": y",
                    actual.getY(), group.newY());
            assertEquals("resize-elements-to-fit " + group.groupName() + ": width",
                    actual.getWidth(), group.newWidth());
            assertEquals("resize-elements-to-fit " + group.groupName() + ": height",
                    actual.getHeight(), group.newHeight());
        }
    }

    /**
     * The two halves of that oracle put together: the group this tool grows must be the group that
     * contains everything this tool moved, not only the elements it re-sized.
     *
     * <p>The pass names both — the groups it grew and the objects it displaced — and each is
     * accurate on its own. Accurate about a group that does not contain the child listed beside it
     * is still a description of a broken canvas, and the agent has nothing else to check it
     * against.</p>
     */
    @Test
    public void shouldGrowTheGroupAroundTheObjectsItDisplaced_whenResizeElementsToFitRuns() {
        String groupId = accessor.addGroupToView(SESSION, view.getId(), "FitAround",
                0, 0, 300, 120, null, null, null).entity().viewObjectId();
        String targetId = accessor.addToView(SESSION, view.getId(), "actor-1",
                30, 30, 60, 30, false, groupId, null, null).entity().viewObject().viewObjectId();
        String childId = accessor.addToView(SESSION, view.getId(), "actor-2",
                30, 70, 60, 40, false, groupId, null, null).entity().viewObject().viewObjectId();
        accessor.updateViewObject(SESSION, childId, null, null, null, null,
                null, null, null, null, targetId, "below", 0, 10);

        int childYBefore = find(view, childId).getBounds().getY();
        ResizeElementsResultDto dto =
                accessor.resizeElementsToFit(SESSION, view.getId(), List.of(targetId)).entity();

        IBounds childAfter = find(view, childId).getBounds();
        IBounds groupAfter = find(view, groupId).getBounds();
        assertTrue("fixture must actually displace the anchored child, or this oracle proves "
                + "nothing: " + childYBefore + " -> " + childAfter.getY(),
                childAfter.getY() != childYBefore);

        assertFalse("the group must contain the object this pass displaced: child at y="
                        + childAfter.getY() + " h=" + childAfter.getHeight()
                        + " inside a group of height " + groupAfter.getHeight(),
                ParentFitCascade.childExceedsParentBounds(
                        childAfter.getX(), childAfter.getY(),
                        childAfter.getWidth(), childAfter.getHeight(),
                        groupAfter.getWidth(), groupAfter.getHeight(),
                        ArchiModelAccessorImpl.DEFAULT_GROUP_PADDING));

        assertFalse("and it must say so: a group grown for a displaced child is still a group this "
                + "call grew", dto.resizedGroups().isEmpty());
        for (ResizedGroupDto group : dto.resizedGroups()) {
            IBounds actual = find(view, group.viewObjectId()).getBounds();
            assertEquals("resize-elements-to-fit grew " + group.groupName() + ": height",
                    actual.getHeight(), group.newHeight());
        }
    }

    /**
     * The same tool, the other thing it silently moves. An object anchored to one the pass grows is
     * displaced by that growth without ever being named in the request, so it is exactly the case
     * the invariant exists for: the agent asked to resize one element, a different element moved,
     * and nothing in the response would say so.
     *
     * <p>Asserted on the serialized response as well as the typed one, and field-by-field against
     * the model, because a landed rectangle is what the agent needs — a count of how many objects
     * moved is an index into information it does not have.</p>
     */
    @Test
    public void shouldNameTheObjectsItDisplaced_whenResizeElementsToFitGrowsAnAnchorTarget()
            throws Exception {
        String targetId = accessor.addToView(SESSION, view.getId(), "actor-1",
                100, 100, 60, 30, false, null, null, null).entity().viewObject().viewObjectId();
        String childId = accessor.addToView(SESSION, view.getId(), "actor-2",
                100, 140, 60, 30, false, null, null, null).entity().viewObject().viewObjectId();
        accessor.updateViewObject(SESSION, childId, null, null, null, null,
                null, null, null, null, targetId, "below", 0, 10);

        int childYBefore = find(view, childId).getBounds().getY();
        ResizeElementsResultDto dto =
                accessor.resizeElementsToFit(SESSION, view.getId(), List.of(targetId)).entity();
        IBounds childAfter = find(view, childId).getBounds();

        assertTrue("fixture must actually displace the anchored child, or this oracle proves "
                + "nothing: " + childYBefore + " -> " + childAfter.getY(),
                childAfter.getY() != childYBefore);

        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(dto);
        assertTrue("the response must name the object it displaced — the caller never mentioned it "
                + "and cannot see the canvas. Response was: " + json, json.contains(childId));
        assertTrue("the response must carry where it landed (y=" + childAfter.getY() + "). "
                + "Response was: " + json, json.contains(String.valueOf(childAfter.getY())));

        assertFalse("movedObjects must not be empty once an anchored object was displaced",
                dto.movedObjects().isEmpty());
        for (MovedViewObjectDto moved : dto.movedObjects()) {
            IDiagramModelObject liveObj = find(view, moved.viewObjectId());
            assertNotNull("resize-elements-to-fit reported an object not in the view: "
                    + moved.viewObjectId(), liveObj);
            IBounds actual = liveObj.getBounds();
            assertEquals("resize-elements-to-fit " + moved.name() + ": x", actual.getX(), moved.newX());
            assertEquals("resize-elements-to-fit " + moved.name() + ": y", actual.getY(), moved.newY());
            assertEquals("resize-elements-to-fit " + moved.name() + ": width",
                    actual.getWidth(), moved.newWidth());
            assertEquals("resize-elements-to-fit " + moved.name() + ": height",
                    actual.getHeight(), moved.newHeight());
        }
    }

    /**
     * {@code add-to-view}, the geometry it produces that nobody asked for. Placing a child into the
     * corner where its container's icon renders grows that container, and a container that grows
     * must still fit inside the group above it — so one placement changes the bounds of two objects
     * the request never named.
     *
     * <p>This is the oracle the tool's gap-registry entry was holding a place for. Asserted
     * field-by-field against the model and against the serialized response, because a value that
     * exists in Java but never reaches JSON would satisfy a typed assertion while leaving the client
     * just as blind. The fixture guard asserts both objects actually grew, so the oracle cannot pass
     * by proving nothing.</p>
     */
    @Test
    public void shouldNameTheContainersItGrew_whenAddToViewReservesAnIconBand() throws Exception {
        String groupId = accessor.addGroupToView(SESSION, view.getId(), "Enclosing",
                0, 0, 340, 340, null, null, null).entity().viewObjectId();
        String containerId = accessor.addToView(SESSION, view.getId(), "actor-1",
                10, 10, 300, 300, false, groupId, null,
                new ImageParams(null, "bottom-left", null))
                .entity().viewObject().viewObjectId();

        AddToViewResultDto dto = accessor.addToView(SESSION, view.getId(), "actor-2",
                0, 270, 50, 30, false, containerId, null, null).entity();

        IBounds containerAfter = find(view, containerId).getBounds();
        IBounds groupAfter = find(view, groupId).getBounds();
        assertTrue("fixture must actually grow the container, or this oracle proves nothing: "
                + "300 -> " + containerAfter.getHeight(), containerAfter.getHeight() > 300);
        assertTrue("fixture must actually grow the group above it: 340 -> " + groupAfter.getHeight(),
                groupAfter.getHeight() > 340);

        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(dto);
        assertTrue("the response must name the container it grew — the caller never mentioned it "
                + "and cannot see the canvas. Response was: " + json, json.contains(containerId));
        assertTrue("the response must name the group the growth pushed out. Response was: " + json,
                json.contains(groupId));
        assertTrue("the response must carry the container's effective height ("
                + containerAfter.getHeight() + "). Response was: " + json,
                json.contains(String.valueOf(containerAfter.getHeight())));

        assertFalse("resizedAncestors must not be empty once an ancestor grew",
                dto.resizedAncestors().isEmpty());
        for (MovedViewObjectDto grown : dto.resizedAncestors()) {
            IDiagramModelObject live = find(view, grown.viewObjectId());
            assertNotNull("add-to-view reported an object not in the view: " + grown.viewObjectId(),
                    live);
            IBounds actual = live.getBounds();
            assertEquals("add-to-view " + grown.name() + ": x", actual.getX(), grown.newX());
            assertEquals("add-to-view " + grown.name() + ": y", actual.getY(), grown.newY());
            assertEquals("add-to-view " + grown.name() + ": width", actual.getWidth(), grown.newWidth());
            assertEquals("add-to-view " + grown.name() + ": height", actual.getHeight(), grown.newHeight());
        }
    }

    /**
     * The same property through the registered tool, so the field is proven to reach the wire rather
     * than merely to exist on the result object — the distinction the bulk wire test below exists
     * for, applied to the tool this entry was registered against.
     */
    @Test
    public void shouldPutTheGrownContainersOnTheWire_whenAddToViewIsCalledAsATool() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        String groupId = accessor.addGroupToView(HANDLER_SESSION, view.getId(), "WireEnclosing",
                0, 0, 340, 340, null, null, null).entity().viewObjectId();
        String containerId = accessor.addToView(HANDLER_SESSION, view.getId(), "actor-1",
                10, 10, 300, 300, false, groupId, null,
                new ImageParams(null, "bottom-left", null))
                .entity().viewObject().viewObjectId();

        Map<String, Object> envelope = invokeTool(registry, "add-to-view", Map.of(
                "viewId", view.getId(), "elementId", "actor-2",
                "parentViewObjectId", containerId,
                "x", 0, "y", 270, "width", 50, "height", 30));

        int containerHeight = find(view, containerId).getBounds().getHeight();
        int groupHeight = find(view, groupId).getBounds().getHeight();
        assertTrue("fixture must grow the container; it ended at " + containerHeight,
                containerHeight > 300);

        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(envelope);
        assertTrue("the wire response must carry resizedAncestors. Response was: " + json,
                json.contains("\"resizedAncestors\""));
        assertTrue("it must carry the container's landed height (" + containerHeight
                + "). Response was: " + json, json.contains("\"newHeight\":" + containerHeight));
        assertTrue("and the group's (" + groupHeight + "). Response was: " + json,
                json.contains("\"newHeight\":" + groupHeight));
    }

    /**
     * The batched arm of the same field, on the wire. Inside a batch nothing has executed, so the
     * growth this reports is a projection — and a projection presented at the top level of
     * {@code result} would be read by an agent as geometry the model already holds.
     *
     * <p>Asserted directly rather than inferred from the envelope being field-agnostic: the
     * labelling is the whole of the invariant's discharge in this mode, and "it should ride the
     * existing mechanism" is an argument, not a measurement.</p>
     */
    @Test
    public void shouldNestTheGrownContainersUnderPreview_whenAddToViewIsBatched() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        String groupId = accessor.addGroupToView(HANDLER_SESSION, view.getId(), "BatchEnclosing",
                0, 0, 340, 340, null, null, null).entity().viewObjectId();
        String containerId = accessor.addToView(HANDLER_SESSION, view.getId(), "actor-1",
                10, 10, 300, 300, false, groupId, null,
                new ImageParams(null, "bottom-left", null))
                .entity().viewObject().viewObjectId();

        dispatcher.beginBatch(HANDLER_SESSION, "batched placement into an icon corner");
        Map<String, Object> envelope = invokeTool(registry, "add-to-view", Map.of(
                "viewId", view.getId(), "elementId", "actor-2",
                "parentViewObjectId", containerId,
                "x", 0, "y", 270, "width", 50, "height", 30));
        dispatcher.endBatch(HANDLER_SESSION, true);

        assertEquals("fixture guard: the batch must actually grow the container", 324,
                find(view, containerId).getBounds().getHeight());

        Object result = envelope.get("result");
        assertTrue("a batched result must be a map carrying batch and preview siblings",
                result instanceof Map);
        Map<?, ?> resultMap = (Map<?, ?>) result;
        assertNotNull("the batched entity must be labelled as a preview", resultMap.get("preview"));
        assertNull("nothing may sit at the top level of a batched result where an agent would read "
                + "it as state the model holds", resultMap.get("resizedAncestors"));

        String previewJson = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(resultMap.get("preview"));
        assertTrue("the projection must still be carried, nested under preview. Preview was: "
                + previewJson, previewJson.contains("resizedAncestors"));
        assertTrue("and must name the container it will grow. Preview was: " + previewJson,
                previewJson.contains(containerId));
    }

    /**
     * A group is created holding the title alignment its type defaults to, so the response has to
     * say so — a caller that is never told cannot know its group differs from the one it would get
     * by asking explicitly.
     *
     * <p>On the batched path nothing is effective yet by construction, so the honest thing the
     * response can carry is a projection, and it must be labelled as one. The value is read off the
     * object the prepare built, which is the same object the queued command will attach: the write
     * already happened at prepare time, so this is a read of the model rather than a second
     * resolution of what the write is expected to do.</p>
     */
    @Test
    public void shouldNestTheStampedAlignmentUnderPreview_whenAddGroupToViewIsBatched()
            throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        dispatcher.beginBatch(HANDLER_SESSION, "batched group placement");
        Map<String, Object> envelope = invokeTool(registry, "add-group-to-view", Map.of(
                "viewId", view.getId(), "label", "BatchedGroup",
                "x", 400, "y", 400, "width", 300, "height", 200));
        dispatcher.endBatch(HANDLER_SESSION, true);

        Object result = envelope.get("result");
        assertTrue("a batched result must be a map carrying batch and preview siblings",
                result instanceof Map);
        Map<?, ?> resultMap = (Map<?, ?>) result;
        assertNotNull("the batched entity must be labelled as a preview", resultMap.get("preview"));
        assertNull("the entity may not sit at the top level of a batched result, where an agent "
                + "would read it as state the model already holds", resultMap.get("viewObjectId"));
        assertNull("nor may the stamped value itself", resultMap.get("textAlignment"));

        String previewJson = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(resultMap.get("preview"));
        assertTrue("the alignment the group will carry must be projected, not omitted. Preview "
                + "was: " + previewJson, previewJson.contains("\"textAlignment\":\"left\""));

        // The projection is only honest if the committed model agrees with it. Asserting the
        // preview alone would pass even if the queued command attached a different object than the
        // prepare stamped, which is exactly the prepare/execute divergence the label exists to
        // declare — so re-read the view after the batch has run.
        IDiagramModelGroup committed = view.getChildren().stream()
                .filter(IDiagramModelGroup.class::isInstance)
                .map(IDiagramModelGroup.class::cast)
                .filter(g -> "BatchedGroup".equals(g.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the batched group was never attached to the view"));
        assertEquals("the committed group must hold the alignment the preview promised",
                ITextAlignment.TEXT_ALIGNMENT_LEFT, committed.getTextAlignment());
    }

    /**
     * An untitled group survives the queued path end to end: the projection under {@code preview}
     * says the label is empty, and the object the batch commits agrees.
     *
     * <p>Nothing about the empty label is proven by the immediate path alone — the queued route has
     * its own prepare and, before this was closed, its own blank rejection. Asserting the preview
     * without re-reading the committed model would also pass if the queued command attached a
     * differently-named object, which is the prepare/execute divergence the {@code preview} label
     * exists to declare rather than to hide.</p>
     */
    @Test
    public void shouldProjectTheEmptyLabelUnderPreview_whenAnUntitledGroupIsBatched()
            throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();

        dispatcher.beginBatch(HANDLER_SESSION, "batched untitled group");
        Map<String, Object> envelope = invokeTool(registry, "add-group-to-view", Map.of(
                "viewId", view.getId(), "label", "",
                "x", 420, "y", 420, "width", 300, "height", 200));
        dispatcher.endBatch(HANDLER_SESSION, true);

        assertNull("the queued path must accept an empty label. Error was: " + envelope.get("error"),
                envelope.get("error"));
        Map<?, ?> resultMap = (Map<?, ?>) envelope.get("result");
        assertNotNull("the batched entity must be labelled as a preview", resultMap.get("preview"));
        assertNull("the entity may not sit at the top level of a batched result",
                resultMap.get("viewObjectId"));

        String previewJson = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(resultMap.get("preview"));
        assertTrue("the empty label must be projected as present-and-empty. An omitted key would "
                + "leave an agent unable to tell an untitled group from an unreported one. "
                + "Preview was: " + previewJson, previewJson.contains("\"label\":\"\""));

        IDiagramModelGroup committed = view.getChildren().stream()
                .filter(IDiagramModelGroup.class::isInstance)
                .map(IDiagramModelGroup.class::cast)
                .filter(g -> g.getBounds().getX() == 420)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the batched untitled group was never attached to the view"));
        assertEquals("the committed group must hold the empty label the preview promised",
                "", committed.getName());
    }

    /**
     * The same obligation on the other two divergent tools. The preview wrapping is shared
     * machinery, so this is not re-testing the mechanism — it is checking that each tool's own
     * entity actually carries the field through it, and that each tool's queued command attaches
     * the object the prepare stamped.
     */
    @Test
    public void shouldNestTheStampedAlignmentUnderPreview_whenAGroupingAndANoteAreBatched()
            throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        IGrouping grouping = IArchimateFactory.eINSTANCE.createGrouping();
        grouping.setId("grouping-batched");
        grouping.setName("BatchedGrouping");
        model.getFolder(FolderType.OTHER).getElements().add(grouping);

        dispatcher.beginBatch(HANDLER_SESSION, "batched Grouping and note");
        Map<String, Object> groupingEnvelope = invokeTool(registry, "add-to-view", Map.of(
                "viewId", view.getId(), "elementId", "grouping-batched",
                "x", 700, "y", 400, "width", 400, "height", 140));
        Map<String, Object> noteEnvelope = invokeTool(registry, "add-note-to-view", Map.of(
                "viewId", view.getId(), "content", "BatchedNote",
                "x", 700, "y", 600, "width", 300, "height", 100));
        dispatcher.endBatch(HANDLER_SESSION, true);

        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        for (Map.Entry<String, Map<String, Object>> e : Map.of(
                "add-to-view", groupingEnvelope, "add-note-to-view", noteEnvelope).entrySet()) {
            Map<?, ?> resultMap = (Map<?, ?>) e.getValue().get("result");
            assertNotNull(e.getKey() + ": the batched entity must be labelled as a preview",
                    resultMap.get("preview"));
            assertNull(e.getKey() + ": the stamped value may not sit at the top level of a "
                    + "batched result", resultMap.get("textAlignment"));
            String json = mapper.writeValueAsString(resultMap.get("preview"));
            assertTrue(e.getKey() + ": the alignment must be projected under preview. Was: " + json,
                    json.contains("\"textAlignment\":\"left\""));
        }

        boolean groupingLanded = view.getChildren().stream()
                .anyMatch(c -> c instanceof IDiagramModelArchimateObject o
                        && o.getArchimateElement() == grouping
                        && ((ITextAlignment) o).getTextAlignment()
                                == ITextAlignment.TEXT_ALIGNMENT_LEFT);
        assertTrue("the committed Grouping must hold the alignment its preview promised",
                groupingLanded);
        boolean noteLanded = view.getChildren().stream()
                .anyMatch(c -> c instanceof IDiagramModelNote n
                        && "BatchedNote".equals(n.getContent())
                        && n.getTextAlignment() == ITextAlignment.TEXT_ALIGNMENT_LEFT);
        assertTrue("the committed note must hold the alignment its preview promised", noteLanded);
    }

    /**
     * The condition the invariant exists for, on the bulk path: one operation sets a group's size,
     * a later operation in the same call moves a child that forces the group past it. The per-op
     * result for the first operation is built during the prepare loop, before anything executes and
     * before the second operation is even prepared, so it cannot know what it will end up meaning.
     *
     * <p>An agent reading "operation 0 succeeded" with no geometry has no way to learn its group is
     * now half again the size it asked for. It is an omission rather than a lie, but the agent is
     * equally blind either way, and it is blind about a value it will build its next call on.</p>
     */
    @Test
    public void shouldReportTheHeightTheGroupEndedAt_whenALaterBulkOpForcesItPastTheRequestedOne()
            throws Exception {
        String groupId = accessor.addGroupToView(SESSION, view.getId(), "Sized",
                0, 0, 200, 200, null, null, null).entity().viewObjectId();
        String childId = accessor.addToView(SESSION, view.getId(), "actor-1",
                10, 10, 120, 55, false, groupId, null, null)
                .entity().viewObject().viewObjectId();

        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("update-view-object",
                        Map.of("viewObjectId", groupId, "height", 300)),
                new BulkOperation("update-view-object",
                        Map.of("viewObjectId", childId, "y", 440, "height", 60))),
                "set the group, then outgrow it", false);

        IBounds groupAfter = find(view, groupId).getBounds();
        assertTrue("fixture must actually push the group past the requested 300, or this oracle "
                + "proves nothing; it ended at " + groupAfter.getHeight(),
                groupAfter.getHeight() > 300);

        BulkOperationResult setOp = result.operations().get(0);
        assertNotNull("the operation that sized the group must carry the geometry the group ENDED "
                + "at (" + groupAfter.getHeight() + "), not the 300 it asked for",
                setOp.effectiveBounds());
        assertEquals("reported effective height must be the height the model holds",
                groupAfter.getHeight(), setOp.effectiveBounds().height());
        assertEquals("reported effective width must be the width the model holds",
                groupAfter.getWidth(), setOp.effectiveBounds().width());
    }

    /**
     * The same property, asserted on the bytes the agent actually receives.
     *
     * <p>This is not redundant with the accessor-level oracle above, and the difference is the whole
     * point: the bulk handler hand-builds each operation's response map key by key rather than
     * serializing the result object, so a field can exist on the DTO, satisfy every typed assertion,
     * and never reach the wire. A test that serializes the DTO itself cannot see that — it proves
     * the value was computed, not that it was sent. Only a round trip through the registered tool
     * distinguishes the two, and the difference is invisible to the agent in exactly the direction
     * that matters: it is told nothing, and cannot tell it was told nothing.</p>
     */
    @Test
    public void shouldPutTheEffectiveGeometryOnTheWire_notMerelyOnTheResultObject() throws Exception {
        CommandRegistry registry = registryOverLiveAccessor();
        String groupId = accessor.addGroupToView(HANDLER_SESSION, view.getId(), "Wire",
                0, 0, 200, 200, null, null, null).entity().viewObjectId();
        String childId = accessor.addToView(HANDLER_SESSION, view.getId(), "actor-1",
                10, 10, 120, 55, false, groupId, null, null)
                .entity().viewObject().viewObjectId();

        Map<String, Object> envelope = invokeTool(registry, "bulk-mutate", Map.of(
                "operations", List.of(
                        Map.of("tool", "update-view-object",
                                "params", Map.of("viewObjectId", groupId, "height", 300)),
                        Map.of("tool", "update-view-object",
                                "params", Map.of("viewObjectId", childId, "y", 440, "height", 60))),
                "description", "set the group, then outgrow it"));

        int landedHeight = find(view, groupId).getBounds().getHeight();
        assertTrue("fixture must push the group past the requested 300; it ended at " + landedHeight,
                landedHeight > 300);

        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(envelope);
        assertTrue("the serialized response must carry the height the group ENDED at ("
                + landedHeight + "). Response was: " + json,
                json.contains("\"effectiveBounds\""));
        assertTrue("effectiveBounds must report the landed height, not the requested 300. "
                + "Response was: " + json,
                json.contains("\"height\":" + landedHeight));
    }

    /**
     * The same deletion, called standalone and called in bulk, must report the same thing. The bulk
     * path's per-operation builder type-switches over entity types and had no branch for a deletion
     * result, so a delete routed through bulk came back with a null type, a null name and none of
     * the cascade counts the standalone tool reports — an agent could not tell what it had just
     * destroyed, and could not tell that it could not tell.
     */
    @Test
    public void shouldReportWhatADeletionDestroyed_whenTheDeleteIsRoutedThroughBulk()
            throws Exception {
        String groupId = accessor.addGroupToView(SESSION, view.getId(), "Doomed",
                0, 0, 300, 300, null, null, null).entity().viewObjectId();
        String sourceId = accessor.addToView(SESSION, view.getId(), "actor-1",
                10, 10, 120, 55, false, groupId, null, null)
                .entity().viewObject().viewObjectId();
        String targetId = accessor.addToView(SESSION, view.getId(), "actor-2",
                10, 100, 120, 55, false, groupId, null, null)
                .entity().viewObject().viewObjectId();
        accessor.addConnectionToView(SESSION, view.getId(), "rel-1",
                sourceId, targetId, null, null, null, null, null);

        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("delete-view", Map.of("viewId", view.getId()))),
                "delete the view in bulk", false);

        BulkOperationResult deleteOp = result.operations().get(0);
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(deleteOp);

        assertNotNull("a bulk deletion must report what type of thing it destroyed. Result was: "
                + json, deleteOp.entityType());
        assertNotNull("a bulk deletion must report the name of what it destroyed. Result was: "
                + json, deleteOp.entityName());
        assertEquals("a bulk deletion must name the deleted view. Result was: " + json,
                "Contract", deleteOp.entityName());
        assertTrue("a bulk deletion must carry the same cascade counts the standalone tool "
                + "reports — the view held one connection that ceased to exist. Result was: " + json,
                json.contains("viewConnectionsRemoved"));

        // And it must survive the handler, which builds its response map key by key rather than
        // serializing this object — see the wire test above for why that distinction is load-bearing.
        CommandRegistry registry = registryOverLiveAccessor();
        IArchimateDiagramModel second = factory.createArchimateDiagramModel();
        second.setId("view-2");
        second.setName("Second");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(second);

        Map<String, Object> envelope = invokeTool(registry, "bulk-mutate", Map.of(
                "operations", List.of(Map.of("tool", "delete-view",
                        "params", Map.of("viewId", "view-2")))));
        String wire = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(envelope);
        assertTrue("the deletion's cascade report must reach the wire, not just the result object. "
                + "Response was: " + wire, wire.contains("\"deletion\""));
        assertTrue("the wire response must name what was destroyed. Response was: " + wire,
                wire.contains("Second"));
    }

    /**
     * The shared projection resolves group names by walking live containment. It replaced a loop
     * that resolved them from a map the caller had built separately, and the two walks are only
     * interchangeable if they reach the same objects at the same depths.
     *
     * <p>The flat oracle above cannot tell the difference — at depth one, any traversal that works
     * at all agrees. This runs the same tool over a group nested two levels deep, which is where a
     * traversal that failed to recurse would show up: the reported name would silently degrade to
     * the raw id fallback while every assertion about bounds still passed.</p>
     */
    @Test
    public void shouldResolveGroupNamesAtEveryNestingDepth_notJustTopLevel() {
        String outerId = accessor.addGroupToView(SESSION, view.getId(), "Outer",
                0, 0, 600, 600, null, null, null).entity().viewObjectId();
        String middleId = accessor.addGroupToView(SESSION, view.getId(), "Middle",
                10, 10, 200, 200, outerId, null, null).entity().viewObjectId();
        String innerId = accessor.addGroupToView(SESSION, view.getId(), "Inner",
                10, 10, 150, 150, middleId, null, null).entity().viewObjectId();

        String sourceId = accessor.addToView(SESSION, view.getId(), "actor-1",
                10, 10, 120, 55, false, innerId, null, null)
                .entity().viewObject().viewObjectId();
        String targetId = accessor.addToView(SESSION, view.getId(), "actor-2",
                10, 400, 120, 55, false, innerId, null, null)
                .entity().viewObject().viewObjectId();
        accessor.addConnectionToView(SESSION, view.getId(), "rel-1",
                sourceId, targetId, null, null, null, null, null);

        List<ResizedGroupDto> reported = accessor.autoRouteConnections(SESSION, view.getId(),
                null, null, false, true, 0, 0, null).entity().resizedGroups();

        assertFalse("fixture must actually grow a nested group, or this proves nothing",
                reported.isEmpty());
        boolean sawNested = false;
        for (ResizedGroupDto group : reported) {
            IDiagramModelObject live = find(view, group.viewObjectId());
            assertNotNull("reported a group not present in the view: " + group.viewObjectId(), live);
            assertEquals("a group nested below the top level must still be reported by NAME — an id "
                    + "here means the name walk failed to recurse and silently fell back",
                    live.getName(), group.groupName());
            if (innerId.equals(group.viewObjectId()) || middleId.equals(group.viewObjectId())) {
                sawNested = true;
            }
        }
        assertTrue("the fixture must exercise a group below the top level, or the depth this test "
                + "exists for was never reached; reported " + reported.size() + " group(s)",
                sawNested);
    }

    /**
     * The post-dispatch read is gated on the bulk call having actually been dispatched. In batch
     * mode it has not been — the compound is queued for commit later — so there is no effective
     * geometry to report, and reporting one anyway would be the same lie in a new place.
     *
     * <p>Asserted by running it rather than by reading the gate, because "this branch is skipped"
     * is exactly the kind of claim that survives a code reading and dies on a fixture.</p>
     */
    @Test
    public void shouldNotClaimEffectiveGeometry_whenTheBulkCallWasOnlyQueued() {
        String groupId = accessor.addGroupToView(SESSION, view.getId(), "Queued",
                0, 0, 200, 200, null, null, null).entity().viewObjectId();

        dispatcher.beginBatch(SESSION, "queued bulk");
        BulkMutationResult result;
        try {
            result = accessor.executeBulk(SESSION, List.of(
                    new BulkOperation("update-view-object",
                            Map.of("viewObjectId", groupId, "height", 300))),
                    "queued bulk", false);
        } finally {
            dispatcher.endBatch(SESSION, true);
        }

        assertTrue("fixture must actually queue rather than dispatch, or the gate under test was "
                + "never reached", result.batchSequenceNumber() != null);
        for (BulkOperationResult op : result.operations()) {
            assertNull("a queued operation has written nothing, so it must not report effective "
                    + "geometry — the queued envelope declares itself a preview instead",
                    op.effectiveBounds());
        }
    }

    // ---- The CRUD oracle -----------------------------------------------------------------------

    /**
     * The CRUD arm of the invariant. These tools cannot re-read after execution — in a batch the
     * write happens later still — so the obligation is one of <em>provenance</em>: every reported
     * field must be derived from the value the write will actually receive, never from the raw
     * request beside it.
     *
     * <p>That is checkable without a normalising validator, by asserting the reported fields agree
     * with <em>each other</em>. A name read off the created object and a path concatenated from the
     * request are indistinguishable while validation is strict pass-through, and disagree the
     * instant it is not. Pinning the agreement now is what stops the divergence being introduced
     * later by a change that looks unrelated.</p>
     */
    @Test
    public void shouldDeriveTheFolderPathFromTheNameItReports_notFromTheRequestBesideIt() {
        IFolder parent = model.getFolder(FolderType.BUSINESS);

        FolderDto created = accessor.createFolder(SESSION, parent.getId(), "Created", null, null)
                .entity();
        assertTrue("create-folder's path must end in the name it reported, or the two were built "
                + "from different sources: name=" + created.name() + " path=" + created.path(),
                created.path().endsWith("/" + created.name()));

        FolderDto renamed = accessor.updateFolder(SESSION, created.id(), "Renamed", null, null)
                .entity();
        assertEquals("update-folder must report the new name", "Renamed", renamed.name());
        assertTrue("update-folder's path must end in the name it reported: name=" + renamed.name()
                + " path=" + renamed.path(), renamed.path().endsWith("/" + renamed.name()));
    }

    /**
     * Specializations do run on the immediate path, so this one is a true post-execute re-read: the
     * name in the response must be the name the stored profile carries.
     */
    @Test
    public void shouldReportTheNameTheProfileCarries_whenASpecializationIsCreatedOrRenamed() {
        Map<String, Object> created =
                accessor.createSpecialization(SESSION, "Critical", "BusinessActor", null).entity();
        assertEquals("create-specialization must report the name the model now holds",
                profileName("BusinessActor", "Critical"), created.get("name"));

        Map<String, Object> renamed = accessor.updateSpecialization(
                SESSION, "Critical", "BusinessActor", "Essential", null, false).entity();
        assertEquals("update-specialization must report the name the model now holds",
                profileName("BusinessActor", "Essential"), renamed.get("name"));
    }

    /** The name the model actually stores for the given concept type, or null if no such profile. */
    private String profileName(String conceptType, String expectedName) {
        for (IProfile profile : model.getProfiles()) {
            if (conceptType.equals(profile.getConceptType())
                    && expectedName.equals(profile.getName())) {
                return profile.getName();
            }
        }
        return "<no profile named '" + expectedName + "' for " + conceptType + " in the model>";
    }

    /**
     * {@code update-relationship} re-reads the concept after the write, but nothing proved it — the
     * tool sat in the gap registry as UNAUDITED on exactly that basis. The clear is the case where
     * a re-read and an echo diverge visibly: the caller sends {@code ""}, so an echo would report
     * {@code ""} for the wrong reason, but the name it must NOT report is the pre-update one, and
     * only a re-read can tell those apart. Driven through the registry rather than the accessor so
     * the assertion is about what the response carries, not about what a DTO could be built to say.
     *
     * <p>The empty name must arrive <em>present and empty</em>. {@code RelationshipDto.name} is
     * deliberately unannotated where its siblings carry {@code NON_NULL}, so a cleared name
     * serialises as {@code "name":""} rather than vanishing — an omitted field would leave an agent
     * unable to tell a cleared name from a field the tool declined to report.</p>
     */
    @Test
    public void shouldReportTheEmptyNameTheModelHolds_whenUpdateRelationshipClearsIt()
            throws Exception {
        IArchimateRelationship rel = fixtureRelationship();
        rel.setName("Serves");
        rel.setDocumentation("Original docs");
        CommandRegistry registry = registryOverLiveAccessor();

        Map<String, Object> envelope =
                invokeTool(registry, "update-relationship", Map.of("id", "rel-1", "name", ""));

        assertEquals("the model must hold the cleared name", "", rel.getName());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) envelope.get("result");
        assertNotNull("update-relationship must return a result envelope, not an error: "
                + envelope, result);
        assertTrue("the cleared name must be PRESENT on the wire, not omitted. Response was: "
                + envelope, result.containsKey("name"));
        assertEquals("update-relationship must report the name the model now holds, not the one it "
                + "held before. Response was: " + envelope, "", result.get("name"));
    }

    /**
     * The same tool's other clear, and its negative control in one run: clearing documentation must
     * reach the model, and must not take the untouched name with it.
     */
    @Test
    public void shouldClearOnlyTheFieldItWasGiven_whenUpdateRelationshipClearsDocumentation()
            throws Exception {
        IArchimateRelationship rel = fixtureRelationship();
        rel.setName("Serves");
        rel.setDocumentation("Original docs");
        CommandRegistry registry = registryOverLiveAccessor();

        invokeTool(registry, "update-relationship", Map.of("id", "rel-1", "documentation", ""));

        assertEquals("the model must hold the cleared documentation", "", rel.getDocumentation());
        assertEquals("the name was not supplied, so it must be untouched", "Serves", rel.getName());
    }

    /**
     * The sibling above asserts the MODEL and never reads the envelope, so it stays green whatever
     * the response contains — which is exactly how the response could go on saying nothing about
     * documentation while the tool's own description confessed it. This is the claim on the wire.
     *
     * <p>The cleared value must arrive <em>present and empty</em>, for the same reason the cleared
     * name must: an omitted key and a key holding {@code ""} are indistinguishable to an agent that
     * cannot see the model, and the omission reads as "unchanged" — the opposite of what happened.
     * {@code containsKey} alone does not discharge this; it passes on a {@code null}.</p>
     */
    @Test
    public void shouldReportTheEmptyDocumentationTheModelHolds_whenUpdateRelationshipClearsIt()
            throws Exception {
        IArchimateRelationship rel = fixtureRelationship();
        rel.setName("Serves");
        rel.setDocumentation("Original docs");
        CommandRegistry registry = registryOverLiveAccessor();

        Map<String, Object> envelope = invokeTool(registry, "update-relationship",
                Map.of("id", "rel-1", "documentation", ""));

        assertEquals("the model must hold the cleared documentation", "", rel.getDocumentation());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) envelope.get("result");
        assertNotNull("update-relationship must return a result envelope, not an error: "
                + envelope, result);
        assertTrue("the cleared documentation must be PRESENT on the wire, not omitted. Response "
                + "was: " + envelope, result.containsKey("documentation"));
        assertEquals("update-relationship must report the documentation the model now holds. "
                + "Response was: " + envelope, "", result.get("documentation"));
    }

    /**
     * The discriminator between a re-read and an echo. A case that sets {@code documentation} to a
     * value and asserts that value came back proves nothing — an echo of the request produces the
     * identical result. So the request mentions <em>only</em> documentation, and the assertions are
     * about the fields it never mentioned: the property the model holds, the untouched name, and
     * the two endpoint names. No echo of this request can invent any of them.
     */
    @Test
    public void shouldReportFieldsTheRequestNeverMentioned_whenUpdateRelationshipSetsDocumentation()
            throws Exception {
        IArchimateRelationship rel = fixtureRelationship();
        rel.setName("Serves");
        rel.setDocumentation("Original docs");
        com.archimatetool.model.IProperty owner = IArchimateFactory.eINSTANCE.createProperty();
        owner.setKey("owner");
        owner.setValue("treasury");
        rel.getProperties().add(owner);
        CommandRegistry registry = registryOverLiveAccessor();

        Map<String, Object> envelope = invokeTool(registry, "update-relationship",
                Map.of("id", "rel-1", "documentation", "Rewritten"));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) envelope.get("result");
        assertNotNull("update-relationship must return a result envelope, not an error: "
                + envelope, result);
        assertEquals("the documentation the model now holds must be reported. Response was: "
                + envelope, "Rewritten", result.get("documentation"));
        assertEquals("the untouched name must be reported from the model, and the request never "
                + "mentioned it. Response was: " + envelope, "Serves", result.get("name"));
        assertEquals("the property the model holds must be reported, and the request never "
                + "mentioned it — an echo cannot invent it. Response was: " + envelope,
                List.of(Map.of("key", "owner", "value", "treasury")), result.get("properties"));
        assertEquals("the source endpoint must be named, not left as an opaque id. Response was: "
                + envelope, "Contract Actor", result.get("sourceName"));
        assertEquals("the target endpoint must be named, not left as an opaque id. Response was: "
                + envelope, "Peer Actor", result.get("targetName"));
    }

    /**
     * The endpoint names must be the names the model holds <em>after</em> the write, which only a
     * post-dispatch read can produce. One bulk call renames the source element and updates the
     * relationship; the relationship operation must name the endpoint by its NEW name. An echo — or
     * a projection built during the prepare loop — would report the old one.
     */
    @Test
    public void shouldNameTheRenamedEndpoint_whenBulkRenamesTheSourceAndUpdatesTheRelationship()
            throws Exception {
        fixtureRelationship().setName("Serves");

        Map<String, Object> envelope = invokeTool(registryOverLiveAccessor(), "bulk-mutate", Map.of(
                "operations", List.of(
                        Map.of("tool", "update-element",
                                "params", Map.of("id", "actor-1", "name", "Renamed Actor")),
                        Map.of("tool", "update-relationship",
                                "params", Map.of("id", "rel-1", "documentation", "after rename"))),
                "description", "rename an endpoint and update the relationship it anchors"));

        Map<String, Object> relationshipOp = bulkOperationAt(envelope, 1);
        Map<String, Object> report = relationshipReportOf(relationshipOp);
        assertEquals("the bulk relationship report must name the source by the name the model holds "
                + "after the whole compound ran, not the one it held when the operation was "
                + "prepared. Response was: " + envelope, "Renamed Actor", report.get("sourceName"));
        assertEquals("the documentation the model now holds must be reported. Response was: "
                + envelope, "after rename", report.get("documentation"));
    }

    /**
     * The parity axis compares leaf key names and ignores nesting, so the standalone response and
     * the bulk {@code effectiveRelationship} must describe the same operation in the same words.
     * Asserted here rather than left for the axis to discover, because the axis can only see a
     * field one side has and the other lacks — a field missing from BOTH sides, which is what this
     * story found, is invisible to it.
     */
    @Test
    public void shouldCarryTheSameLeafKeys_whenUpdateRelationshipRunsStandaloneAndThroughBulk()
            throws Exception {
        IArchimateRelationship rel = fixtureRelationship();
        rel.setName("Serves");
        rel.setDocumentation("Original docs");

        Map<String, Object> standalone = (Map<String, Object>) invokeTool(
                registryOverLiveAccessor(), "update-relationship",
                Map.of("id", "rel-1", "documentation", "through the single tool")).get("result");

        Map<String, Object> throughBulk = relationshipReportOf(bulkUpdateRelationship(
                Map.of("id", "rel-1", "documentation", "through bulk")));

        assertEquals("the standalone response and the bulk effectiveRelationship must carry the "
                + "same leaf key set for the same operation; standalone=" + standalone.keySet()
                + " bulk=" + throughBulk.keySet(),
                standalone.keySet(), throughBulk.keySet());
        assertTrue("and that shared key set must actually include the documentation this story "
                + "added; it was: " + standalone.keySet(),
                standalone.keySet().containsAll(
                        List.of("documentation", "sourceName", "targetName")));
    }

    /**
     * The pre-dispatch prepare feeds the batched and approval arms, and nothing is effective in
     * those modes by construction. The obligation there is structural: the entity is nested under
     * {@code preview}, and must NOT appear at the top level of {@code result} where an agent would
     * read it as state the model holds. Widening the projection must not quietly promote it.
     */
    @Test
    public void shouldKeepTheRelationshipUnderPreview_whenUpdateRelationshipIsBatched()
            throws Exception {
        IArchimateRelationship rel = fixtureRelationship();
        rel.setName("Serves");
        rel.setDocumentation("Original docs");
        CommandRegistry registry = registryOverLiveAccessor();

        dispatcher.beginBatch(HANDLER_SESSION, "batched relationship update");
        Map<String, Object> envelope = invokeTool(registry, "update-relationship",
                Map.of("id", "rel-1", "documentation", "queued"));
        dispatcher.endBatch(HANDLER_SESSION, true);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) envelope.get("result");
        assertNotNull("the batched entity must be labelled as a preview. Response was: " + envelope,
                result.get("preview"));
        assertNull("the relationship's documentation may not sit at the top level of a batched "
                + "result, where it would read as state the model holds. Response was: " + envelope,
                result.get("documentation"));
        assertNull("nor may the endpoint names. Response was: " + envelope,
                result.get("sourceName"));
        assertEquals("the queued command must still have landed on the model",
                "queued", rel.getDocumentation());
        // Asserting only that `preview` is non-null and the top level is clean would pass on a
        // preview carrying nothing, or the wrong entity. The label is only half the discharge —
        // what it labels has to be the relationship, described in the same words.
        Map<?, ?> preview = (Map<?, ?>) result.get("preview");
        assertEquals("the preview must describe the relationship the call named",
                "rel-1", preview.get("id"));
        assertEquals("and must carry the pre-update documentation, which is what the description "
                + "promises a batched caller. Preview was: " + preview,
                "Original docs", preview.get("documentation"));
        assertEquals("and the endpoint names, so the batched shape matches the immediate one",
                "Contract Actor", preview.get("sourceName"));
    }

    /**
     * The approval arm of the same obligation. Nothing is effective while a proposal waits for a
     * human, so the widened relationship must sit inside the proposal envelope and never at the top
     * level of {@code result} — the batched test above proves only one of the two deferred modes.
     */
    @Test
    public void shouldKeepTheRelationshipUnderPreview_whenUpdateRelationshipAwaitsApproval()
            throws Exception {
        IArchimateRelationship rel = fixtureRelationship();
        rel.setName("Serves");
        rel.setDocumentation("Original docs");
        CommandRegistry registry = registryOverLiveAccessor();
        dispatcher.setApprovalModeProvider(() -> true);
        try {
            Map<String, Object> envelope = invokeTool(registry, "update-relationship",
                    Map.of("id", "rel-1", "documentation", "proposed"));

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) envelope.get("result");
            assertNotNull("the proposed entity must be labelled as a preview. Response was: "
                    + envelope, result.get("preview"));
            assertNotNull("and must carry its proposal sibling. Response was: " + envelope,
                    result.get("proposal"));
            assertNull("the relationship's documentation may not sit at the top level of a "
                    + "proposal result. Response was: " + envelope, result.get("documentation"));
            assertNull("nor may the endpoint names. Response was: " + envelope,
                    result.get("sourceName"));
            assertEquals("and nothing may have reached the model while approval is pending",
                    "Original docs", rel.getDocumentation());
        } finally {
            dispatcher.setApprovalModeProvider(() -> false);
        }
    }

    /**
     * {@code update-element} re-reads the concept after the write, and sat in the gap registry as
     * UNAUDITED on exactly that basis: the re-read was visible in the code and proven by nothing.
     *
     * <p>The discriminator is a call that changes documentation and says nothing about the name.
     * A response echoing the request has no name to echo and would omit it; only a re-read of the
     * concept can report the name the element actually carries. The same call pins the field it
     * did change, so the oracle fails whether the response invents state or drops it.</p>
     *
     * <p>Driven through the registry rather than the accessor, so the assertion is about what the
     * response carries rather than about what a DTO could be built to say.</p>
     */
    @Test
    public void shouldReportTheNameTheModelHolds_whenUpdateElementChangesOnlyDocumentation()
            throws Exception {
        IArchimateElement element = fixtureElement();
        element.setDocumentation("Original docs");
        CommandRegistry registry = registryOverLiveAccessor();

        Map<String, Object> envelope = invokeTool(registry, "update-element",
                Map.of("id", "actor-1", "documentation", "Rewritten docs"));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) envelope.get("result");
        assertNotNull("update-element must return a result envelope, not an error: " + envelope,
                result);
        assertEquals("the model must hold the new documentation", "Rewritten docs",
                element.getDocumentation());
        assertEquals("and the response must report it", "Rewritten docs",
                result.get("documentation"));
        assertEquals("the name was never supplied, so only a re-read of the concept can report it. "
                + "Response was: " + envelope, "Contract Actor", result.get("name"));
    }

    /**
     * The rename arm, whose value is that the reported name is the model's rather than the
     * request's. They agree whenever the write succeeds, so this pins the agreement rather than
     * discriminating on it — the arm above is what tells a re-read from an echo.
     */
    @Test
    public void shouldReportTheNewNameTheModelHolds_whenUpdateElementRenamesIt() throws Exception {
        IArchimateElement element = fixtureElement();
        CommandRegistry registry = registryOverLiveAccessor();

        Map<String, Object> envelope = invokeTool(registry, "update-element",
                Map.of("id", "actor-1", "name", "Renamed Actor"));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) envelope.get("result");
        assertNotNull("update-element must return a result envelope, not an error: " + envelope,
                result);
        assertEquals("the model must hold the new name", "Renamed Actor", element.getName());
        assertEquals("and the response must report the name the model holds. Response was: "
                + envelope, "Renamed Actor", result.get("name"));
    }

    /** The fixture element, read back out of the model rather than held from setUp. */
    private IArchimateElement fixtureElement() {
        for (Object each : model.getFolder(FolderType.BUSINESS).getElements()) {
            if (each instanceof IArchimateElement element && "actor-1".equals(element.getId())) {
                return element;
            }
        }
        throw new AssertionError("fixture element actor-1 missing from the model");
    }

    /** The fixture relationship, read back out of the model rather than held from setUp. */
    private IArchimateRelationship fixtureRelationship() {
        for (Object each : model.getFolder(FolderType.RELATIONS).getElements()) {
            if (each instanceof IArchimateRelationship relationship
                    && "rel-1".equals(relationship.getId())) {
                return relationship;
            }
        }
        throw new AssertionError("fixture relationship rel-1 missing from the model");
    }

    // ---- The counts oracle ---------------------------------------------------------------------

    /**
     * {@code delete-relationship} reports two counts as literal zeros it never measures. That reads
     * like the unmeasured-zero defect its sibling deletion tools had — a number in a measured
     * field's clothing — so it was traced rather than assumed, and the zeros turn out to be
     * <em>correct</em> for every relationship this server can build: the delete command disconnects
     * only its own relationship, and a relationship has no view <em>references</em> (those are
     * placeholders pointing at views).
     *
     * <p>So this is a negative pin, not a fix. It asserts the zeros against what the model actually
     * loses, so that a future cascade which really does remove sibling relationships cannot quietly
     * keep reporting zero — which is exactly the hole the tool has today, having no pin at all
     * while its description is free to advertise the fields.</p>
     *
     * <p><strong>The scope of that "correct" is deliberately bounded.</strong> The metamodel lets a
     * relationship's own source or target be another relationship, and deleting the inner one would
     * leave the outer one dangling with nothing counted. No tool here can create that shape —
     * relationship creation requires both endpoints to be elements, and relationship update takes no
     * endpoint arguments at all — so it is unreachable through this server rather than handled by
     * it, and the same limitation exists upstream. Stated rather than left implied, because a zero
     * that is right by construction and a zero that is right by luck look identical in a response.</p>
     */
    @Test
    public void shouldReportZeroCascade_onlyBecauseARelationshipDeleteTrulyCascadesToNeither() {
        String sourceId = accessor.addToView(SESSION, view.getId(), "actor-1",
                10, 10, 120, 55, false, null, null, null)
                .entity().viewObject().viewObjectId();
        String targetId = accessor.addToView(SESSION, view.getId(), "actor-2",
                10, 200, 120, 55, false, null, null, null)
                .entity().viewObject().viewObjectId();
        accessor.addConnectionToView(SESSION, view.getId(), "rel-1",
                sourceId, targetId, null, null, null, null, null);

        int relationshipsBefore = model.getFolder(FolderType.RELATIONS).getElements().size();
        int connectionsBefore = countConnections();
        assertEquals("fixture must place exactly one connection, or the measured count proves "
                + "nothing", 1, connectionsBefore);

        DeleteResultDto deleted = accessor.deleteRelationship(SESSION, "rel-1").entity();

        int relationshipsAfter = model.getFolder(FolderType.RELATIONS).getElements().size();
        assertEquals("the delete must remove exactly the subject relationship — if it ever "
                + "cascades to siblings, relationshipsRemoved stops being an honest zero",
                1, relationshipsBefore - relationshipsAfter);
        assertEquals("relationshipsRemoved counts CASCADED relationships; the subject is already "
                + "named by id and name, and nothing else was removed", 0,
                deleted.relationshipsRemoved());
        assertEquals("a relationship has no view references — those are placeholders pointing at "
                + "views — so zero here is a fact about the type, not an unmeasured default", 0,
                deleted.viewReferencesRemoved());
        assertEquals("viewConnectionsRemoved IS measured and must match what the view lost",
                connectionsBefore - countConnections(), deleted.viewConnectionsRemoved());
        assertEquals("the view's connection must be gone", 0, countConnections());
    }

    /** Every connection currently attached to an object in the fixture view. */
    private int countConnections() {
        return AssessmentCollector.collectAllConnections(view).size();
    }

    // ---- helpers -------------------------------------------------------------------------------

    /**
     * Reads the committed gap registry. Returns tool name to the remainder of its line, preserving
     * file order. Blank lines and {@code #}-comments are ignored.
     *
     * <p>A repeated tool name is rejected outright rather than collapsed. Keying entries by tool
     * name means a duplicate would otherwise overwrite its twin, so the map would report fewer
     * entries than the file contains — and the ceiling is checked against that count. An extra,
     * un-clicked line could then hide behind a name already present, which is precisely the
     * "sneak a gap in without a reviewable ceiling click" move the pawl exists to prevent.</p>
     */
    private Map<String, String> readGapRegistry() throws IOException {
        Path file = locateRepoFile(GAP_FILE);
        Map<String, String> entries = new LinkedHashMap<>();
        int lineCount = 0;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int space = line.indexOf(' ');
            assertTrue("malformed gap entry (no status): " + line, space > 0);
            lineCount++;
            String tool = line.substring(0, space);
            assertFalse("'" + tool + "' is listed twice in " + GAP_FILE + ". Entries are keyed by "
                    + "tool name, so a duplicate would collapse into one and let the file grow past "
                    + "the ceiling without a click. Merge the two lines into one.",
                    entries.containsKey(tool));
            entries.put(tool, line.substring(space + 1).trim());
        }
        assertEquals("every non-comment line in " + GAP_FILE + " must survive parsing into exactly "
                + "one entry", lineCount, entries.size());
        return entries;
    }

    /**
     * Resolves a repo-relative path by walking upward from the working directory.
     *
     * <p>Covers the harness this test actually runs under, where the working directory is inside
     * the checkout. It is <em>not</em> a general solution: a runner whose working directory sits
     * outside the repo entirely has nothing to walk up to, and gets the explicit failure below
     * rather than a silently skipped check. That is the right trade — the alternative failure mode
     * for a ratchet is to quietly stop ratcheting.</p>
     */
    private static Path locateRepoFile(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new AssertionError("Could not locate " + relative + " by walking up from "
                + Path.of("").toAbsolutePath() + ". This test must run with a working directory "
                + "inside the repository checkout so it can read the committed gap registry; "
                + "running it from elsewhere would silently disable the effective-state ratchet.");
    }

    // ---- Connection oracles ---------------------------------------------------------------------

    /**
     * The connection tools build their response DTO at prepare time, by reading the connection as
     * it is now and overlaying the styling the caller asked for. That projection is only honest if
     * the command that runs afterwards actually produces it — so this re-reads the EMF connection
     * after execution and compares against what the caller was told, rather than against what the
     * caller asked for.
     */
    @Test
    public void shouldReportTheStylingTheConnectionHolds_whenUpdateViewConnectionRestylesIt() {
        String connectionId = parityConnection("update-oracle");

        ViewConnectionDto reported = accessor.updateViewConnection(HANDLER_SESSION, connectionId,
                List.of(new BendpointDto(30, 40, -30, 40)), null,
                new StylingParams(null, "#D35400", null, null, 2), null, null).entity();

        IDiagramModelConnection live = liveConnection(connectionId);
        assertEquals("reported lineColor must be the colour the connection now carries",
                live.getLineColor(), reported.lineColor());
        assertEquals("reported lineWidth must be the width the connection now carries",
                Integer.valueOf(live.getLineWidth()), reported.lineWidth());
        assertEquals("reported bendpoint count must match the connection's own",
                live.getBendpoints().size(), reported.bendpoints().size());
        assertEquals("the connection must actually hold the requested colour, or the comparison "
                + "above is two copies of the same wrong value", "#D35400", live.getLineColor());
    }

    /**
     * Same obligation on the create path, where the connection does not exist until the command
     * runs — so a response built before it exists has more room to diverge, not less.
     */
    @Test
    public void shouldReportTheStylingTheConnectionHolds_whenAddConnectionToViewCreatesIt() {
        String[] ends = parityConnectableEndpoints("add-oracle");

        ViewConnectionDto reported = accessor.addConnectionToView(HANDLER_SESSION, view.getId(),
                ends[0], ends[1], ends[2],
                List.of(new BendpointDto(20, 25, -20, 25)), null,
                new StylingParams(null, "#2C3E50", null, null, 3), null, null).entity();

        IDiagramModelConnection live = liveConnection(reported.viewConnectionId());
        assertEquals("reported lineColor must be the colour the new connection carries",
                live.getLineColor(), reported.lineColor());
        assertEquals("reported lineWidth must be the width the new connection carries",
                Integer.valueOf(live.getLineWidth()), reported.lineWidth());
        assertEquals("reported bendpoint count must match the connection's own",
                live.getBendpoints().size(), reported.bendpoints().size());
        assertEquals("the connection must actually hold the requested colour, or the comparison "
                + "above is two copies of the same wrong value", "#2C3E50", live.getLineColor());
        assertEquals("the endpoints reported must be the ones the connection actually joins",
                ends[1], reported.sourceViewObjectId());
    }

    // ---- The same obligation, reached through bulk ----------------------------------------------

    /**
     * THE DISCRIMINATING OBSERVABLE. A bulk call whose later operation moves an endpoint placed
     * before it must report the anchors the connection finally holds, not the ones it held when its
     * own operation was prepared.
     *
     * <p>Anchors are the only field in the connection report that a later operation can change
     * <em>without naming the connection</em> — they are absolute canvas centres derived from the
     * endpoints' live geometry. So a projection captured at prepare time would not merely be
     * unreported here, it would be reported <em>wrong</em>, and every other field in the report
     * would look identical either way. That is what makes this the test that tells prepare-time
     * projection apart from a post-dispatch read, rather than a test that asserts the distinction
     * in prose.</p>
     */
    @Test
    public void shouldReportAnchorsFromTheFinalGeometry_whenALaterBulkOperationMovesAnEndpoint()
            throws Exception {
        String[] ends = parityConnectableEndpoints("anchor-staleness");
        int[] anchorBeforeTheMove = absoluteCentreOf(ends[1]);

        Map<String, Object> operation = firstBulkOperation(invokeTool(registryOverLiveAccessor(),
                "bulk-mutate", Map.of(
                        "operations", List.of(
                                Map.of("tool", "add-connection-to-view", "params", Map.of(
                                        "viewId", view.getId(), "relationshipId", ends[0],
                                        "sourceViewObjectId", ends[1],
                                        "targetViewObjectId", ends[2])),
                                Map.of("tool", "update-view-object", "params", Map.of(
                                        "viewObjectId", ends[1], "x", 100, "y", 100))),
                        "description", "a later operation moves an endpoint of an earlier one")));

        Map<String, Object> sourceAnchor = nested(connectionReportOf(operation), "sourceAnchor");
        int[] anchorAfterTheMove = absoluteCentreOf(ends[1]);

        assertEquals("the reported source anchor must be the centre the endpoint holds once the "
                + "whole call has been applied", anchorAfterTheMove[0],
                ((Number) sourceAnchor.get("x")).intValue());
        assertEquals(anchorAfterTheMove[1], ((Number) sourceAnchor.get("y")).intValue());
        assertFalse("the fixture must actually move the endpoint, or this comparison is two "
                + "readings of the same geometry and proves nothing about when the read happened",
                anchorBeforeTheMove[0] == anchorAfterTheMove[0]
                        && anchorBeforeTheMove[1] == anchorAfterTheMove[1]);
    }

    /**
     * The bulk caller must be told the styling the connection actually carries, re-read from the
     * model rather than echoed from the operation's own parameters.
     */
    @Test
    public void shouldReportTheStylingTheConnectionHolds_whenUpdateViewConnectionRunsInBulk()
            throws Exception {
        String connectionId = parityConnection("bulk-restyle");

        Map<String, Object> report = connectionReportOf(firstBulkOperation(
                invokeTool(registryOverLiveAccessor(), "bulk-mutate", Map.of(
                        "operations", List.of(Map.of("tool", "update-view-connection", "params",
                                Map.of("viewConnectionId", connectionId,
                                        "lineColor", "#D35400", "lineWidth", 2))),
                        "description", "restyle through bulk"))));

        IDiagramModelConnection live = liveConnection(connectionId);
        assertEquals("reported lineColor must be the colour the connection now carries",
                live.getLineColor(), report.get("lineColor"));
        assertEquals("reported lineWidth must be the width the connection now carries",
                live.getLineWidth(), ((Number) report.get("lineWidth")).intValue());
        assertEquals("the connection must actually hold the requested colour, or the comparison "
                + "above is two copies of the same wrong value", "#D35400", live.getLineColor());
    }

    /**
     * Queued into a batch, nothing has executed, so there is no connection state to report and the
     * field must be absent rather than projected.
     *
     * <p>Driven against a connection that <em>does</em> resolve, with the dispatched flag as the
     * only thing that varies. Had the fixture used an unresolvable id, the field would have been
     * absent for the wrong reason and the test would have passed without touching the mode gate at
     * all. The dispatched arm is the negative control that proves the assertion is live.</p>
     */
    @Test
    public void shouldOmitTheConnectionReport_whenNothingHasBeenDispatched() {
        String connectionId = parityConnection("queued");
        List<BulkOperationResult> prepared = List.of(new BulkOperationResult(
                0, "update-view-connection", "updated", connectionId,
                "AssociationRelationship", null));

        assertNull("a batched or awaiting-approval call has written nothing, so there is no "
                + "connection state to read and inventing one is the same lie in a new place",
                BulkResultProjection.withPostDispatchState(model, prepared, false)
                        .get(0).effectiveConnection());
        assertNotNull("negative control: the same id under the dispatched flag must produce a "
                + "report, or the assertion above passes for the wrong reason",
                BulkResultProjection.withPostDispatchState(model, prepared, true)
                        .get(0).effectiveConnection());
    }

    /**
     * A removal must not carry a report describing the connection it removed.
     *
     * <p>{@code remove-from-view} is the one non-connection tool whose {@code entityId} can be a
     * connection id, so it is the whole measured surface of the type-driven gate's over-reporting
     * risk. Two independent facts close it: a disconnected connection leaves the containment tree
     * that {@code getObjectByID} walks, and {@code RemoveConnectionFromViewCommand} is not a
     * {@code CommitSkippableCommand}, so it cannot decline and leave the connection both present
     * and reportable. Pinned because the second fact is a property of another class that nothing
     * else stops someone changing.</p>
     */
    @Test
    public void shouldNotAttachAConnectionReport_whenTheOperationRemovedTheConnection()
            throws Exception {
        String connectionId = parityConnection("removed");

        Map<String, Object> operation = firstBulkOperation(invokeTool(registryOverLiveAccessor(),
                "bulk-mutate", Map.of(
                        "operations", List.of(Map.of("tool", "remove-from-view", "params",
                                Map.of("viewId", view.getId(), "viewObjectId", connectionId))),
                        "description", "remove a connection through bulk")));

        assertFalse("a removal reports what it destroyed through its own envelope fields; a "
                + "connection report here would describe something that is no longer there",
                operation.containsKey("effectiveConnection"));
    }

    /**
     * A create that declined joined nothing, so there is no connection to describe.
     *
     * <p>Provoked rather than asserted: the earlier operation removes the view object the later
     * operation was prepared to connect, which is exactly the deferred-path conflict
     * {@code RequireAttachedContainerCommand} exists to decline. The connection is built at prepare
     * time and never joined to anything, so it never enters containment and the re-read finds
     * nothing — self-correcting, which is why the field needs no entry in the retraction pass
     * beside the two collateral lists. This is that claim under test rather than in prose.</p>
     */
    @Test
    public void shouldOmitTheConnectionReport_whenTheCreateDeclined() throws Exception {
        String[] ends = parityConnectableEndpoints("declined-create");

        Map<String, Object> envelope = invokeTool(registryOverLiveAccessor(), "bulk-mutate", Map.of(
                "operations", List.of(
                        Map.of("tool", "remove-from-view", "params", Map.of(
                                "viewId", view.getId(), "viewObjectId", ends[1])),
                        Map.of("tool", "add-connection-to-view", "params", Map.of(
                                "viewId", view.getId(), "relationshipId", ends[0],
                                "sourceViewObjectId", ends[1], "targetViewObjectId", ends[2]))),
                "description", "an earlier operation removes the endpoint a later one connects"));

        Map<String, Object> result = resultOf(envelope);
        assertTrue("the fixture must actually provoke a decline, or this says nothing about the "
                + "declined path. Envelope was: " + result,
                result.get("skippedOperations") instanceof List<?> reasons && !reasons.isEmpty());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations =
                (List<Map<String, Object>>) result.get("operations");
        assertFalse("a connection that was never joined to anything must not be described as "
                + "though it exists", operations.get(1).containsKey("effectiveConnection"));
    }

    /**
     * An endpoint whose bounds cannot be read costs the anchors and nothing else — it must not
     * throw, and it must not cost the styling.
     *
     * <p>{@code ConnectionResponseBuilder.computeAbsoluteCenter} dereferences {@code getBounds()} on
     * the endpoint and on every ancestor it walks, neither guarded, and bounds are nullable on
     * {@code IDiagramModelObject} — a state this very class already handles twice, in the
     * view-object branch and in {@code refreshLive}. On the standalone path a throw there fails one
     * call. Here the compound has <em>already been dispatched</em>, so it would replace the whole
     * bulk response with an error after the model was mutated, telling the agent nothing about work
     * that did happen. That is the failure this pins, and it fails with an NPE rather than an
     * assertion if the guard is removed.</p>
     */
    @Test
    public void shouldReportStylingWithoutAnchors_whenAnEndpointHasNoBounds() {
        String connectionId = parityConnection("no-bounds");
        accessor.updateViewConnection(HANDLER_SESSION, connectionId, null, null,
                new StylingParams(null, "#8E44AD", null, null, 3), null, null);

        IDiagramModelConnection live = liveConnection(connectionId);
        IDiagramModelObject source = (IDiagramModelObject) live.getSource();
        source.setBounds(null);

        List<BulkOperationResult> prepared = List.of(new BulkOperationResult(
                0, "update-view-connection", "updated", connectionId,
                "AssociationRelationship", null));

        ViewConnectionDto reported = BulkResultProjection
                .withPostDispatchState(model, prepared, true).get(0).effectiveConnection();

        assertNotNull("an unreadable anchor must cost the anchor, not the whole report", reported);
        assertNull("an anchor that cannot be computed must be absent rather than guessed at",
                reported.sourceAnchor());
        assertNull(reported.targetAnchor());
        assertEquals("the styling is readable regardless of geometry and must still be reported",
                "#8E44AD", reported.lineColor());
        assertEquals("a width of 3 is not the default 1, so this is a reported value rather than "
                + "an omitted one", Integer.valueOf(3), reported.lineWidth());
    }

    // ==================== the concept's own post-dispatch state ====================
    //
    // Three attributes, three relationship subtypes, one fixture each — not one relationship
    // carrying all three. RelationshipSemantics.validateForUpdate rejects an attribute the subtype
    // cannot hold with a structured INVALID_PARAMETER, so the combined payload fails at the prepare
    // boundary and would prove nothing about the projection.
    //
    // Each oracle sets a value that is NOT the fresh-object default, because a reported value equal
    // to the default cannot tell a re-read apart from a field that was never written. The path
    // parity axis is blind to two of the three: its fixture builds an Association, so accessType
    // and influenceStrength read null there, @JsonInclude(NON_NULL) omits them from the standalone
    // response, and the axis never compares them in either direction. Its greenness is therefore
    // not coverage of them, and these oracles are what covers them instead.

    /** accessType defaults to int 0, reported as "write", so "read" is the discriminating value. */
    @Test
    public void shouldReportTheAccessType_whenABulkUpdateChangesIt() throws Exception {
        String id = parityRelationshipOfType("rel-access-eff",
                factory.createAccessRelationship());

        Map<String, Object> report = relationshipReportOf(
                bulkUpdateRelationship(Map.of("id", id, "accessType", "read")));

        assertEquals("a bulk caller who sets accessType must be told what it became",
                "read", report.get("accessType"));
        assertEquals("and the model must actually hold it, or the report is agreeing with itself",
                IAccessRelationship.READ_ACCESS,
                ((IAccessRelationship) liveRelationship(id)).getAccessType());
        assertFalse("an Access relationship holds no direction and must not acquire one",
                report.containsKey("associationDirected"));
        assertFalse("nor a strength", report.containsKey("influenceStrength"));
    }

    /** associationDirected defaults to false, so true is the discriminating value. */
    @Test
    public void shouldReportTheAssociationDirection_whenABulkUpdateChangesIt() throws Exception {
        String id = parityRelationshipOfType("rel-assoc-eff",
                factory.createAssociationRelationship());

        Map<String, Object> report = relationshipReportOf(
                bulkUpdateRelationship(Map.of("id", id, "associationDirected", true)));

        assertEquals("a bulk caller who directs an association must be told what it became",
                Boolean.TRUE, report.get("associationDirected"));
    }

    /**
     * influenceStrength is omitted entirely when empty, so an empty-strength oracle asserts nothing
     * — the field would be absent whether the write landed or not. A non-empty strength is the only
     * payload that discriminates.
     */
    @Test
    public void shouldReportTheInfluenceStrength_whenABulkUpdateChangesIt() throws Exception {
        String id = parityRelationshipOfType("rel-influence-eff",
                factory.createInfluenceRelationship());

        Map<String, Object> report = relationshipReportOf(
                bulkUpdateRelationship(Map.of("id", id, "influenceStrength", "++")));

        assertEquals("a bulk caller who sets a strength must be told what it became",
                "++", report.get("influenceStrength"));
        assertFalse("an Influence relationship holds no access type and must not acquire one",
                report.containsKey("accessType"));
        assertFalse("nor a direction", report.containsKey("associationDirected"));
    }

    /**
     * The arm that tells a post-dispatch re-read apart from both of the things it could be mistaken
     * for. Two operations in one call change the same strength: a projection built when the earlier
     * operation was prepared reports the field absent, an echo of that operation's own request
     * reports "+", and only a read of the applied model reports "+++".
     */
    @Test
    public void shouldReportTheFinalInfluenceStrength_whenALaterOperationChangesItAgain()
            throws Exception {
        String id = parityRelationshipOfType("rel-influence-final",
                factory.createInfluenceRelationship());

        Map<String, Object> result = resultOf(invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", List.of(
                        Map.of("tool", "update-relationship", "params",
                                Map.of("id", id, "influenceStrength", "+")),
                        Map.of("tool", "update-relationship", "params",
                                Map.of("id", id, "influenceStrength", "+++"))),
                        "description", "two operations change the same strength")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) result.get("operations");

        assertEquals("the earlier operation must report the strength the model ends up holding",
                "+++", relationshipReportOf(operations.get(0)).get("influenceStrength"));
        assertEquals("+++", relationshipReportOf(operations.get(1)).get("influenceStrength"));
    }

    /**
     * A subtype that cannot hold an attribute must stay exactly as quiet as it is today. The report
     * is composed from the readers the standalone path uses, which return null off-subtype, so this
     * is the omit-at-default rule holding through the new field rather than being re-derived in it.
     */
    @Test
    public void shouldOmitTheAttributesTheSubtypeCannotHold_whenTheRelationshipIsAnAssociation()
            throws Exception {
        String id = parityRelationshipOfType("rel-quiet-eff",
                factory.createAssociationRelationship());

        Map<String, Object> report = relationshipReportOf(
                bulkUpdateRelationship(Map.of("id", id, "name", "Quiet")));

        assertFalse("an Association has no accessType and must not acquire one",
                report.containsKey("accessType"));
        assertFalse("nor an influenceStrength", report.containsKey("influenceStrength"));
        assertEquals("the one attribute it does hold is reported, at its default",
                Boolean.FALSE, report.get("associationDirected"));
    }

    /**
     * The element half of the same gap. documentation is state the bulk {@code update-element} case
     * can change and reported nothing about, while the standalone caller is told it — a parity loss
     * rather than a two-path omission, which is why it is closed here and its relationship-side
     * namesake is not.
     */
    @Test
    public void shouldReportTheDocumentation_whenABulkUpdateChangesIt() throws Exception {
        String id = parityElement("actor-doc-eff");

        Map<String, Object> operation = firstBulkOperation(invokeTool(registryOverLiveAccessor(),
                "bulk-mutate", Map.of(
                        "operations", List.of(Map.of("tool", "update-element", "params",
                                Map.of("id", id, "documentation", "Rewritten docs"))),
                        "description", "documentation through bulk")));

        assertTrue("the bulk per-operation entry must carry the element's post-dispatch state; it "
                + "carried: " + operation.keySet(), operation.containsKey("effectiveElement"));
        assertEquals("a bulk caller who rewrites documentation must be told what it became",
                "Rewritten docs", nested(operation, "effectiveElement").get("documentation"));
    }

    /**
     * The gate the type-driven branch is paired with, pinned rather than argued.
     *
     * <p>Deliberately hand-built rather than driven through a real tool. The subject is the GATE —
     * an entry that describes no entity must acquire no concept report — and pinning it against
     * whichever tool happens to describe nothing this month would make it restate that tool's
     * projection instead. {@code move-to-folder} was that tool and is no longer: it now names its
     * subject and so, correctly, now carries a report. The gate is unchanged, which is why this
     * test is unchanged; a hand-built undescribed entry still gets nothing, and the negative
     * control still proves the assertion is not passing for the wrong reason.</p>
     */
    @Test
    public void shouldNotAttachARelationshipReport_whenTheEnvelopeDescribesNoEntity() {
        BulkOperationResult undescribed = new BulkOperationResult(
                0, "move-to-folder", "moved", "rel-1", null, null);

        assertNull("a live read must not introduce a description the operation never carried",
                BulkResultProjection.withPostDispatchState(model, List.of(undescribed), true)
                        .get(0).effectiveRelationship());
        assertNotNull("negative control: the same id under an entry that does describe its entity "
                + "must produce a report, or the assertion above passes for the wrong reason",
                BulkResultProjection.withPostDispatchState(model, List.of(new BulkOperationResult(
                        0, "update-relationship", "updated", "rel-1", "AssociationRelationship",
                        null)), true).get(0).effectiveRelationship());
    }

    /**
     * The negative control that runs the REAL path, rather than a stub that never reaches it.
     *
     * <p>The wire-level omission test drives a stubbed accessor, so it proves only that the response
     * builder invents no key from a result that already carries none — it never executes
     * {@code withLiveEntityState} at all. This one puts a genuine view object and a genuine
     * connection through the live projection and requires the concept reports to stay absent.</p>
     *
     * <p>Both operations are asserted to carry the report they <em>should</em> — {@code
     * effectiveBounds} and {@code effectiveConnection} — because without that this passes just as
     * happily when the projection never ran, which is the failure it exists to exclude. The
     * hierarchies are disjoint at the metamodel ({@code IDiagramModelArchimateObject} descends from
     * {@code IDiagramModelComponent}, {@code IArchimateElement} from {@code IArchimateConcept}), so
     * this cannot fail today; it is here so that a future branch widened to a shared supertype
     * cannot start describing a view object as though it were the concept behind it.</p>
     */
    @Test
    public void shouldAttachNoConceptReport_whenTheLiveEntityIsAViewObjectOrConnection()
            throws Exception {
        String viewObjectId = parityViewObject("actor-1", "actor-2");
        String connectionId = parityConnection("concept-negative-control");

        Map<String, Object> result = resultOf(invokeTool(registryOverLiveAccessor(), "bulk-mutate",
                Map.of("operations", List.of(
                        Map.of("tool", "update-view-object", "params",
                                Map.of("viewObjectId", viewObjectId, "height", 300)),
                        Map.of("tool", "update-view-connection", "params",
                                Map.of("viewConnectionId", connectionId, "lineColor", "#D35400"))),
                        "description", "visual operations must acquire no concept report")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) result.get("operations");

        assertTrue("the fixture must actually reach the live projection, or the absences below are "
                + "satisfied by a pass that never ran it. Entry was: " + operations.get(0).keySet(),
                operations.get(0).containsKey("effectiveBounds"));
        assertTrue("same, for the connection arm. Entry was: " + operations.get(1).keySet(),
                operations.get(1).containsKey("effectiveConnection"));

        for (Map<String, Object> operation : operations) {
            assertFalse("a view object is not the concept behind it, and must not be described as "
                    + "one. Entry was: " + operation.keySet(),
                    operation.containsKey("effectiveRelationship"));
            assertFalse("nor as an element. Entry was: " + operation.keySet(),
                    operation.containsKey("effectiveElement"));
        }
    }

    /** A relationship that a bulk call was queued rather than applied describes no state yet. */
    @Test
    public void shouldOmitTheRelationshipReport_whenNothingWasDispatched() {
        List<BulkOperationResult> prepared = List.of(new BulkOperationResult(
                0, "update-relationship", "updated", "rel-1", "AssociationRelationship", null));

        assertNull("nothing has been written in batch or approval mode, so there is no state to "
                + "read and a projection there would be the lie the labelling exists to avoid",
                BulkResultProjection.withPostDispatchState(model, prepared, false)
                        .get(0).effectiveRelationship());
    }

    /** A bulk update-relationship through the registry, as one operation. */
    private Map<String, Object> bulkUpdateRelationship(Map<String, Object> params)
            throws Exception {
        return firstBulkOperation(invokeTool(registryOverLiveAccessor(), "bulk-mutate", Map.of(
                "operations", List.of(Map.of("tool", "update-relationship", "params", params)),
                "description", "a semantic attribute through bulk")));
    }

    /**
     * A relationship of the caller's chosen subtype, joined between two fixture actors.
     *
     * <p>Built through the factory rather than through {@code create-relationship} because the
     * subtype is the whole point and the endpoint pair is incidental to every assertion here — the
     * attribute readers key on the relationship's own EClass and never look at what it joins.</p>
     */
    private String parityRelationshipOfType(String id, IArchimateRelationship rel) {
        rel.setId(id);
        rel.setSource((IBusinessActor) model.getFolder(FolderType.BUSINESS).getElements().get(0));
        rel.setTarget((IBusinessActor) model.getFolder(FolderType.BUSINESS).getElements().get(1));
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);
        return id;
    }

    /** The EMF relationship behind a reported id, for oracles that re-read rather than trust. */
    private IArchimateRelationship liveRelationship(String id) {
        for (Object each : model.getFolder(FolderType.RELATIONS).getElements()) {
            if (each instanceof IArchimateRelationship rel && id.equals(rel.getId())) {
                return rel;
            }
        }
        throw new AssertionError("relationship not in the model: " + id);
    }

    /** The relationship report a per-operation entry carries, asserted present before being read. */
    private static Map<String, Object> relationshipReportOf(Map<String, Object> operation) {
        assertTrue("the bulk per-operation entry must carry the relationship's post-dispatch "
                + "state; it carried: " + operation.keySet(),
                operation.containsKey("effectiveRelationship"));
        return nested(operation, "effectiveRelationship");
    }

    /** The connection report a per-operation entry carries, asserted present before being read. */
    private static Map<String, Object> connectionReportOf(Map<String, Object> operation) {
        assertTrue("the bulk per-operation entry must carry the connection's post-dispatch state; "
                + "it carried: " + operation.keySet(),
                operation.containsKey("effectiveConnection"));
        return nested(operation, "effectiveConnection");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> parent, String key) {
        Object child = parent.get(key);
        assertTrue(key + " must be an object, got: " + child, child instanceof Map);
        return (Map<String, Object>) child;
    }

    /** The absolute canvas centre of a view object, by the same walk the response builder uses. */
    private int[] absoluteCentreOf(String viewObjectId) {
        IDiagramModelObject live = find(view, viewObjectId);
        assertNotNull("view object must be on the view: " + viewObjectId, live);
        return ConnectionResponseBuilder.computeAbsoluteCenter(live);
    }

    // ---- The pawl's second axis: path parity ---------------------------------------------------

    /**
     * Keys a tool reports standalone that its bulk per-operation entry legitimately does not carry
     * under the same name, each with the reason. Keyed {@code tool.dotted.key}.
     *
     * <p>Every entry here is a hole in the parity property, so the list is the reviewable part of
     * this axis and is asserted to be <em>exactly</em> consumed — an entry that stops applying is
     * deleted rather than left to excuse a future loss. Structural renames dominate: bulk wraps
     * every operation in a uniform envelope, so the entity's own id and name arrive as
     * {@code entityId} and {@code entityName} and its geometry as {@code effectiveBounds}. What is
     * <em>not</em> renamed — anything describing objects the operation touched that it was not
     * asked to touch — must appear under its own name on both paths, which is the loss this axis
     * exists to catch.</p>
     */
    private static final Map<String, String> BULK_PARITY_DIVERGENCES = bulkParityDivergences();

    private static Map<String, String> bulkParityDivergences() {
        Map<String, String> d = new LinkedHashMap<>();
        String id = "the entity's own id arrives as the envelope's entityId";
        String name = "the entity's own name arrives as the envelope's entityName, re-read after "
                + "dispatch rather than projected at prepare";
        String type = "the entity's own type arrives as the envelope's entityType";
        String geometry = "the entity's own geometry arrives as effectiveBounds, re-read after "
                + "dispatch rather than projected at prepare";
        String wrapper = "a container key of the standalone shape; bulk spreads its contents "
                + "across the uniform envelope instead of nesting them";
        String behindTheViewObject = "the id of the ArchiMate element behind the view object. The "
                + "caller named it in the request, so echoing it back reports nothing the caller "
                + "does not already hold; the envelope carries the view object's own id, which is "
                + "the value the request did not contain";

        d.put("add-to-view.viewObject", wrapper);
        for (String tool : List.of("add-to-view", "update-view-object")) {
            d.put(tool + ".elementId", behindTheViewObject);
            d.put(tool + ".elementName", name);
            d.put(tool + ".elementType", type);
        }

        // These two carried a distinct reason for as long as the projection had no branch for a
        // prepared folder: the fields were absent from the entry outright rather than renamed into
        // it. The projection now describes a folder, so the ordinary structural-rename reason is
        // the true one and is shared with every sibling above. The declarations stay either way —
        // this axis compares leaf key names, and the envelope calls them entityName and entityType,
        // so the rename is a divergence the axis still sees and still requires a reason for.
        for (String tool : List.of("create-folder", "update-folder")) {
            d.put(tool + ".id", id);
            d.put(tool + ".name", name);
            d.put(tool + ".type", type);
            d.put(tool + ".path", "folder metadata the uniform envelope does not carry. Derived "
                    + "from containment rather than changed by the operation, and re-readable with "
                    + "get-folders against the entityId the envelope does carry");
            d.put(tool + ".elementCount", "as path: a count of what the folder holds, not of what "
                    + "this operation did to it");
            d.put(tool + ".subfolderCount", "as path: a count of what the folder holds, not of "
                    + "what this operation did to it");
        }

        for (String tool : List.of("create-specialization", "update-specialization")) {
            d.put(tool + ".name", name);
            d.put(tool + ".conceptType", "arrives inside the envelope's entityType, which is "
                    + "rendered as \"Specialization:<conceptType>\"");
            d.put(tool + ".conceptTypeLayer", "derived from conceptType, which the envelope "
                    + "already carries, so it is recomputable rather than lost");
        }
        d.put("create-specialization.created", "a constant true on the create path; the envelope's "
                + "action field carries the same fact for every tool uniformly");

        // NOTHING is declared for update-element or update-relationship. Both once declared their
        // whole standalone leaf set here — most of it as benign renames, and two entries
        // (update-element.documentation, update-relationship.associationDirected) as explicit OPEN
        // GAPs, because they named state the bulk path could change and reported nothing about.
        // Both are now closed the way those entries said they had to be: the per-operation entry
        // carries the concept's post-dispatch state under effectiveElement and
        // effectiveRelationship, exactly as the connection tools carry effectiveConnection. Because
        // this comparison ignores nesting, every leaf of those reports counts as told — so the
        // renames stopped being renames at the same moment the gaps stopped being gaps, and all
        // eleven declarations went stale together rather than one at a time.

        // Geometry is NOT declared here: the envelope's effectiveBounds carries x/y/width/height
        // under those very names, so they match. Nor are a deletion's cascade counts: bulk nests
        // the standalone deletion report verbatim under `deletion`, and this comparison ignores
        // nesting. Both are parity that holds, not parity that was excused.
        return Map.copyOf(d);
    }

    /**
     * The tools this axis drives, each with a standalone payload and an equivalent bulk payload.
     * Equivalent, not identical: a create or a delete cannot be run twice against the same entity,
     * so each side gets its own target of the same shape.
     */
    private Set<String> declaredParityTools() {
        return Set.of("add-connection-to-view", "add-to-view", "create-folder",
                "create-specialization", "delete-relationship", "update-element",
                "update-folder", "update-relationship", "update-specialization",
                "update-view-connection", "update-view-object");
    }

    /**
     * Bulk-reachable and oracle-covered, derived rather than listed. {@code SUPPORTED_TOOLS} is the
     * same constant the bulk dispatcher validates against, so a tool that becomes bulk-reachable
     * enters this set without anyone remembering to add it.
     */
    private Set<String> bulkReachableOracleCovered() {
        Set<String> both = new TreeSet<>(BulkOperation.SUPPORTED_TOOLS);
        both.retainAll(ORACLE_COVERED);
        return both;
    }

    /**
     * Register-or-fail, one level up from the gap registry's own. A tool that is oracle-covered and
     * reachable through bulk acquires a parity obligation the moment it becomes either, and nothing
     * about adding it to {@code SUPPORTED_TOOLS_ORDERED} prompts anyone to think about the bulk
     * projection. So the obligation is asserted rather than remembered.
     */
    @Test
    public void shouldForceEveryBulkReachableOracleCoveredTool_toDeclareAParityCase() {
        assertEquals("Every ORACLE_COVERED tool reachable through bulk-mutate must declare a parity "
                + "case below, and a declared case that is no longer either must be deleted. This "
                + "is the same register-or-fail property the gap registry has, applied to the call "
                + "path rather than the tool name.",
                bulkReachableOracleCovered(), new TreeSet<>(declaredParityTools()));
    }

    /**
     * THE AXIS. The gap registry classifies by tool name, so "update-view-object as reached through
     * bulk-mutate" is not a subject it can name — and a field added to a shared DTO acquires its
     * obligation on exactly one of the four paths that tool is reachable by. Both times that
     * happened, every membership stayed green while the bulk path shipped silent.
     *
     * <p>So: run each tool standalone and through bulk with an equivalent payload, flatten both
     * responses to dotted key paths, and require that nothing the standalone caller is told is
     * missing from what the bulk caller is told, except where a divergence is declared with a
     * reason. Structural, not enumerative: it catches the <em>next</em> field, which is the only
     * reason this is worth building rather than asserting the two fields already known.</p>
     */
    @Test
    public void shouldNotTellTheBulkCallerLess_thanTheStandaloneCallerIsTold() throws Exception {
        Set<String> consumed = new TreeSet<>();
        List<String> losses = new ArrayList<>();

        for (String tool : bulkReachableOracleCovered()) {
            CommandRegistry registry = registryOverLiveAccessor();
            Set<String> standalone =
                    leafNames(resultOf(invokeTool(registry, tool, standalonePayload(tool))));
            Set<String> bulk = leafNames(firstBulkOperation(
                    invokeTool(registry, "bulk-mutate", Map.of(
                            "operations", List.of(Map.of("tool", tool, "params", bulkPayload(tool))),
                            "description", "path parity for " + tool))));

            assertFixtureExercisesCollateralReporting(tool, standalone);

            for (String key : standalone) {
                if (bulk.contains(key)) {
                    continue;
                }
                String declared = tool + "." + key;
                if (BULK_PARITY_DIVERGENCES.containsKey(declared)) {
                    consumed.add(declared);
                    continue;
                }
                losses.add(declared + "  (standalone says it, bulk does not)");
            }
        }

        assertTrue("These fields reach the standalone caller and not the bulk caller. Either the "
                + "bulk path must report them, or the divergence must be declared with a reason in "
                + "BULK_PARITY_DIVERGENCES — silence is how this invariant gets re-broken:\n  "
                + String.join("\n  ", losses), losses.isEmpty());

        assertEquals("Declared parity divergences that no longer apply. Delete them rather than "
                + "leaving them to excuse a future loss — the same staleness rule the gap registry "
                + "has.", BULK_PARITY_DIVERGENCES.keySet(), consumed);
    }

    /**
     * The axis is about fields naming collateral change — objects an operation touched that nobody
     * named — because those are the ones a uniform envelope has no other way to carry, and both
     * times this broke it was one of them. A fixture that never provokes any is subsumed by a
     * passing comparison and proves nothing, so the two tools that can provoke one must.
     */
    private static void assertFixtureExercisesCollateralReporting(String tool,
            Set<String> standaloneKeys) {
        if (!List.of("add-to-view", "update-view-object").contains(tool)) {
            return;
        }
        assertTrue(tool + ": the parity fixture must actually grow an ancestor, or this comparison "
                + "is vacuous for the field it exists to protect. Standalone keys were: "
                + standaloneKeys, standaloneKeys.contains("resizedAncestors"));
        if (tool.equals("update-view-object")) {
            assertTrue(tool + ": the parity fixture must also displace an anchored object, or the "
                    + "comparison says nothing about the other field this axis protects. "
                    + "Standalone keys were: " + standaloneKeys,
                    standaloneKeys.contains("movedObjects"));
        }
    }

    private Map<String, Object> standalonePayload(String tool) {
        return switch (tool) {
            case "add-to-view" -> Map.of("viewId", view.getId(), "elementId", "actor-1",
                    "parentViewObjectId", parityIconBandContainer("parity-add-a"),
                    "x", 0, "y", 270, "width", 50, "height", 30);
            case "update-view-object" -> Map.of("viewObjectId", parityViewObject("actor-2", "actor-3"),
                    "height", 300);
            case "create-folder" -> Map.of("parentId", parityRootFolderId(), "name", "Parity A");
            case "update-folder" -> Map.of("id", parityFolder("Parity Update A"), "name",
                    "Parity Update A renamed");
            case "create-specialization" -> Map.of("name", "ParitySpecA",
                    "conceptType", "BusinessActor");
            case "update-specialization" -> Map.of("name", paritySpecialization("ParityUpdA"),
                    "conceptType", "BusinessActor", "newName", "ParityUpdA renamed");
            case "delete-relationship" -> Map.of("relationshipId", parityRelationship("rel-parity-a"));
            // A lone empty-clear on purpose: it is the payload both paths used to reject outright,
            // so this parity case doubles as the pin that neither path may start rejecting again.
            case "update-relationship" -> Map.of("id", parityNamedRelationship("rel-parity-upd-a"),
                    "name", "", "documentation", "");
            case "update-element" -> Map.of("id", parityElement("actor-parity-upd-a"),
                    "name", "Parity element renamed");
            case "add-connection-to-view" -> connectionCreatePayload("parity-conn-a");
            case "update-view-connection" -> Map.of(
                    "viewConnectionId", parityConnection("parity-upd-a"),
                    "lineColor", "#D35400", "lineWidth", 2);
            default -> throw new AssertionError("no parity payload declared for " + tool);
        };
    }

    /**
     * A create payload against fresh endpoints. Styled, because an unstyled connection reports its
     * styling fields as absent by design — a comparison over a connection with nothing on it would
     * pass without the fields this axis exists to protect ever appearing on either side.
     */
    private Map<String, Object> connectionCreatePayload(String suffix) {
        String[] ends = parityConnectableEndpoints(suffix);
        return Map.of("viewId", view.getId(), "relationshipId", ends[0],
                "sourceViewObjectId", ends[1], "targetViewObjectId", ends[2],
                "lineColor", "#2C3E50", "lineWidth", 3);
    }

    private Map<String, Object> bulkPayload(String tool) {
        return switch (tool) {
            case "add-to-view" -> Map.of("viewId", view.getId(), "elementId", "actor-3",
                    "parentViewObjectId", parityIconBandContainer("parity-add-b"),
                    "x", 0, "y", 270, "width", 50, "height", 30);
            case "update-view-object" -> Map.of("viewObjectId", parityViewObject("actor-1", "actor-4"),
                    "height", 300);
            case "create-folder" -> Map.of("parentId", parityRootFolderId(), "name", "Parity B");
            case "update-folder" -> Map.of("id", parityFolder("Parity Update B"), "name",
                    "Parity Update B renamed");
            case "create-specialization" -> Map.of("name", "ParitySpecB",
                    "conceptType", "BusinessActor");
            case "update-specialization" -> Map.of("name", paritySpecialization("ParityUpdB"),
                    "conceptType", "BusinessActor", "newName", "ParityUpdB renamed");
            case "delete-relationship" -> Map.of("relationshipId", parityRelationship("rel-parity-b"));
            case "update-relationship" -> Map.of("id", parityNamedRelationship("rel-parity-upd-b"),
                    "name", "", "documentation", "");
            case "update-element" -> Map.of("id", parityElement("actor-parity-upd-b"),
                    "name", "Parity element renamed");
            case "add-connection-to-view" -> connectionCreatePayload("parity-conn-b");
            case "update-view-connection" -> Map.of(
                    "viewConnectionId", parityConnection("parity-upd-b"),
                    "lineColor", "#D35400", "lineWidth", 2);
            default -> throw new AssertionError("no parity payload declared for " + tool);
        };
    }

    /**
     * A view object inside a group, with a sibling anchored below it. Growing it therefore grows an
     * ancestor nobody named <em>and</em> displaces an object nobody named — the two kinds of
     * collateral change this axis protects. Without both, a comparison that passed would say
     * nothing about the fields the axis exists for.
     */
    private String parityViewObject(String elementId, String anchoredElementId) {
        String groupId = accessor.addGroupToView(HANDLER_SESSION, view.getId(),
                "Parity holder " + elementId, 0, 0, 200, 200, null, null, null)
                .entity().viewObjectId();
        String target = accessor.addToView(HANDLER_SESSION, view.getId(), elementId,
                10, 10, 120, 55, false, groupId, null, null)
                .entity().viewObject().viewObjectId();
        String anchored = accessor.addToView(HANDLER_SESSION, view.getId(), anchoredElementId,
                10, 80, 120, 55, false, groupId, null, null)
                .entity().viewObject().viewObjectId();
        accessor.updateViewObject(HANDLER_SESSION, anchored, null, null, null, null,
                null, null, null, null, target, "below", 0, 10);
        return target;
    }

    /**
     * A container whose bottom-left corner is reserved for an icon, nested in a group. Placing into
     * that corner grows the container to clear the icon and the group to keep containing it —
     * which is how a placement comes to report ancestors nobody named.
     */
    private String parityIconBandContainer(String label) {
        String groupId = accessor.addGroupToView(HANDLER_SESSION, view.getId(), label,
                0, 0, 340, 340, null, null, null).entity().viewObjectId();
        return accessor.addToView(HANDLER_SESSION, view.getId(), "actor-2",
                10, 10, 300, 300, false, groupId, null,
                new ImageParams(null, "bottom-left", null))
                .entity().viewObject().viewObjectId();
    }

    private String parityRootFolderId() {
        return model.getFolder(FolderType.OTHER).getId();
    }

    private String parityFolder(String name) {
        IFolder folder = factory.createFolder();
        folder.setId("folder-" + name.replace(' ', '-'));
        folder.setName(name);
        model.getFolder(FolderType.OTHER).getFolders().add(folder);
        return folder.getId();
    }

    private String paritySpecialization(String name) {
        IProfile profile = factory.createProfile();
        profile.setId("profile-" + name);
        profile.setName(name);
        profile.setConceptType("BusinessActor");
        model.getProfiles().add(profile);
        return name;
    }

    /**
     * Two fresh actors placed on the view with an undrawn relationship between them, so a
     * connection tool has something to create rather than tripping the already-on-view
     * precondition. Fresh per call: the standalone and bulk halves of a parity comparison must not
     * contend for the same connection.
     *
     * @return {relationshipId, sourceViewObjectId, targetViewObjectId}
     */
    private String[] parityConnectableEndpoints(String suffix) {
        IBusinessActor from = factory.createBusinessActor();
        from.setId("actor-conn-from-" + suffix);
        from.setName("Conn From " + suffix);
        model.getFolder(FolderType.BUSINESS).getElements().add(from);

        IBusinessActor to = factory.createBusinessActor();
        to.setId("actor-conn-to-" + suffix);
        to.setName("Conn To " + suffix);
        model.getFolder(FolderType.BUSINESS).getElements().add(to);

        IArchimateRelationship rel = factory.createAssociationRelationship();
        rel.setId("rel-conn-" + suffix);
        rel.setSource(from);
        rel.setTarget(to);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);

        String source = accessor.addToView(HANDLER_SESSION, view.getId(), from.getId(),
                600, 600, 120, 55, false, null, null, null)
                .entity().viewObject().viewObjectId();
        String target = accessor.addToView(HANDLER_SESSION, view.getId(), to.getId(),
                820, 600, 120, 55, false, null, null, null)
                .entity().viewObject().viewObjectId();
        return new String[] {rel.getId(), source, target};
    }

    /** A live, already-drawn connection for the update tool to restyle. */
    private String parityConnection(String suffix) {
        String[] ends = parityConnectableEndpoints(suffix);
        return accessor.addConnectionToView(HANDLER_SESSION, view.getId(), ends[0],
                ends[1], ends[2], null, null, null, null, null)
                .entity().viewConnectionId();
    }

    /** The EMF connection behind a reported id, for oracles that re-read rather than trust. */
    private IDiagramModelConnection liveConnection(String viewConnectionId) {
        for (Object child : view.getChildren()) {
            if (child instanceof IDiagramModelObject obj) {
                for (Object outgoing : obj.getSourceConnections()) {
                    if (outgoing instanceof IDiagramModelConnection conn
                            && conn.getId().equals(viewConnectionId)) {
                        return conn;
                    }
                }
            }
        }
        throw new AssertionError("connection not on the view: " + viewConnectionId);
    }

    private String parityRelationship(String id) {
        IArchimateRelationship rel = factory.createAssociationRelationship();
        rel.setId(id);
        rel.setSource((IBusinessActor) model.getFolder(FolderType.BUSINESS).getElements().get(0));
        rel.setTarget((IBusinessActor) model.getFolder(FolderType.BUSINESS).getElements().get(1));
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);
        return id;
    }

    /** A parity relationship that starts with both clearable fields populated. */
    private String parityNamedRelationship(String id) {
        parityRelationship(id);
        for (Object each : model.getFolder(FolderType.RELATIONS).getElements()) {
            if (each instanceof IArchimateRelationship rel && id.equals(rel.getId())) {
                rel.setName("Parity name");
                rel.setDocumentation("Parity docs");
            }
        }
        return id;
    }

    /** A fresh element for a parity arm, so a rename here cannot disturb another arm's fixture. */
    private String parityElement(String id) {
        IBusinessActor actor = factory.createBusinessActor();
        actor.setId(id);
        actor.setName("Parity element");
        actor.setDocumentation("Parity docs");
        model.getFolder(FolderType.BUSINESS).getElements().add(actor);
        return id;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resultOf(Map<String, Object> envelope) {
        Object result = envelope.get("result");
        assertTrue("expected a successful envelope, got: " + envelope, result instanceof Map);
        return (Map<String, Object>) result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstBulkOperation(Map<String, Object> envelope) {
        return bulkOperationAt(envelope, 0);
    }

    /** The per-operation entry at {@code index}, for compounds whose later ops are the subject. */
    private static Map<String, Object> bulkOperationAt(Map<String, Object> envelope, int index) {
        Map<String, Object> result = resultOf(envelope);
        Object ops = result.get("operations");
        assertTrue("expected a succeeded bulk operation at index " + index + ", got: " + envelope,
                ops instanceof List && ((List<?>) ops).size() > index);
        return (Map<String, Object>) ((List<?>) ops).get(index);
    }

    /**
     * Every field name appearing anywhere in a response, at any depth, without its path.
     *
     * <p>Nesting-insensitive on purpose. The two shapes nest differently by design — a deletion's
     * cascade counts sit at the top level standalone and under {@code deletion} in bulk, a
     * placement's object sits under {@code viewObject} standalone and is spread across the envelope
     * in bulk — and comparing paths would report every one of those as a loss, burying the losses
     * that are real. What must not differ is whether the caller is <em>told</em> a thing at all.</p>
     *
     * <p>The cost is that a name occurring anywhere counts as present, including inside a nested
     * list — so a future field would be excused if its name happened to coincide with one already
     * used in, say, a {@code resizedAncestors} entry. The exactly-consumed assertion on the
     * divergence list is what keeps that visible: a coincidence turns a declared divergence stale
     * and fails, rather than passing quietly.</p>
     */
    private static Set<String> leafNames(Map<String, Object> source) {
        Set<String> names = new TreeSet<>();
        collectNames(source, names);
        return names;
    }

    @SuppressWarnings("unchecked")
    private static void collectNames(Object node, Set<String> names) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) map).entrySet()) {
                names.add(entry.getKey());
                collectNames(entry.getValue(), names);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                collectNames(item, names);
            }
        }
    }

    /** Every tool name the server registers, across all handlers. */
    private Set<String> registeredToolNames() {
        CommandRegistry registry = registryOverLiveAccessor();
        Set<String> names = new LinkedHashSet<>();
        registry.getToolSpecifications().forEach(spec -> names.add(spec.tool().name()));
        assertTrue("the registry should expose the full tool surface; got " + names.size(),
                names.size() >= 60);
        return names;
    }

    /**
     * A registry wired to the real accessor, so invocations reach real model code.
     *
     * <p>Registration goes through {@link HandlerRegistrar} — the same entry point the server uses
     * — rather than a hand-listed set of handlers. That is load-bearing for the pawl: a tool added
     * behind a brand-new handler class still appears here, so it is still forced to classify. A
     * hand-maintained list would silently omit it, which is the exact failure this test exists to
     * prevent, one level up.</p>
     */
    /** An association between two model elements, for a fixture that needs inter-group edges. */
    private void crossRelationship(String id, String sourceId, String targetId) {
        IArchimateRelationship rel = factory.createAssociationRelationship();
        rel.setId(id);
        rel.setSource((com.archimatetool.model.IArchimateElement)
                com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, sourceId));
        rel.setTarget((com.archimatetool.model.IArchimateElement)
                com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, targetId));
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);
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

    /** The oracle itself: what the response said, against what a fresh read of the model says. */
    private void assertBoundsMatchModel(String tool, String viewObjectId,
            int reportedX, int reportedY, int reportedWidth, int reportedHeight) {
        IDiagramModelObject live = find(view, viewObjectId);
        assertNotNull(tool + ": object must exist in the model after the mutation", live);
        IBounds actual = live.getBounds();
        assertEquals(tool + ": reported x must be the x the model holds", actual.getX(), reportedX);
        assertEquals(tool + ": reported y must be the y the model holds", actual.getY(), reportedY);
        assertEquals(tool + ": reported width must be the width the model holds",
                actual.getWidth(), reportedWidth);
        assertEquals(tool + ": reported height must be the height the model holds",
                actual.getHeight(), reportedHeight);
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

    /** Minimal headless {@link IEditorModelManager}: the same stub idiom the model tests use. */
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
