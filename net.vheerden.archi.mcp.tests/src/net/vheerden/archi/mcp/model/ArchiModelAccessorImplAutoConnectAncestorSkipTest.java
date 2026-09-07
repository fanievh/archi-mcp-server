package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IApplicationComponent;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IBusinessProcess;
import com.archimatetool.model.IDiagramModelArchimateObject;

import net.vheerden.archi.mcp.response.dto.AddToViewResultDto;
import net.vheerden.archi.mcp.response.dto.AddToViewResultDto.SkippedConnection;
import net.vheerden.archi.mcp.response.dto.ProposalDto;
import net.vheerden.archi.mcp.response.dto.AutoConnectResultDto;
import net.vheerden.archi.mcp.response.dto.AutoConnectResultDto.SkippedNestingPair;
import net.vheerden.archi.mcp.response.dto.ViewGroupDto;

/**
 * Pin tests for the ancestor-on-view skip in
 * {@link ArchiModelAccessorImpl#autoConnectView}.
 *
 * <p>Surfaces the silent-acceptance bug in {@code auto-connect-view}: prior to
 * this change, the tool drew a visual connection between source and target even
 * when one endpoint was visually nested inside the other on the view (rendering
 * as a self-pass-through that {@code assess-layout} subsequently flagged as
 * {@code M4 connectionEdgeCoincidence}).
 *
 * <p>Real EMF via {@link IArchimateFactory#eINSTANCE}; runs as a JUnit
 * Plug-in Test in the OSGi runtime. The accessor under test is wired with a
 * minimal {@link IEditorModelManager} stub plus a synchronous test
 * {@link MutationDispatcher} that bypasses {@code Display.syncExec} +
 * {@code CommandStack} (mirror of {@code ArchiModelAccessorImplTest}'s
 * {@code createAccessorWithTestDispatcher} pattern).
 *
 * <p>The empirical live-Archi run is the broader integration gate —
 * these tests cover the EMF assembly + skip-policy contract.
 */
public class ArchiModelAccessorImplAutoConnectAncestorSkipTest {

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;

    /**
     * Whether the gated dispatcher is currently parking mutations for a human.
     *
     * <p>Flipped mid-test on purpose: the card tests need a nested fixture, which they have to
     * BUILD by applying placements, and a gate that was on from the start would park those instead
     * of executing them — leaving the pass with nothing nested to decline and a card assertion that
     * passes for the wrong reason.</p>
     */
    private boolean approvalRequired;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();
        approvalRequired = false;
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ------------------------------------------------------------------
    // Nested descendant inside ancestor: skip the connection, record
    // the pair, leave no IDiagramModelArchimateConnection on the view.
    // ------------------------------------------------------------------

    @Test
    public void shouldSkipNestedAncestorPair_descendantInsideAncestor() {
        // Uses RealizationRelationship — literal match to the spec
        // AND mirrors the View K retail-bank reproducer (WP→Deliverable
        // realization is the exact bug surface the empirical caught).
        IArchimateModel model = buildModelWithPair("wp-A", "del-1", /*useRealization=*/ true);
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Place WP-A as direct view child, big enough to hold the nested deliverable.
        MutationResult<AddToViewResultDto> parentRes = accessor.addToView(
                "default", "view-001", "wp-A", 50, 50, 300, 200,
                false, null, null, null);
        String parentVoId = parentRes.entity().viewObject().viewObjectId();

        // Place Deliverable-1 nested inside WP-A via parentViewObjectId.
        MutationResult<AddToViewResultDto> childRes = accessor.addToView(
                "default", "view-001", "del-1", 10, 10, 100, 50,
                false, parentVoId, null, null);
        String childVoId = childRes.entity().viewObject().viewObjectId();

        MutationResult<AutoConnectResultDto> result = accessor.autoConnectView(
                "default", "view-001", null, null, null, null, null);

        AutoConnectResultDto dto = result.entity();
        assertEquals("No connection should be created", 0, dto.connectionsCreated());
        assertEquals("Idempotent-skip counter is unchanged",
                0, dto.connectionsSkipped());
        assertTrue("relationshipIdsConnected is empty",
                dto.relationshipIdsConnected().isEmpty());

        assertNotNull("skippedDueToNesting always present, never null",
                dto.skippedDueToNesting());
        assertEquals("One pair recorded", 1, dto.skippedDueToNesting().size());

        SkippedNestingPair entry = dto.skippedDueToNesting().get(0);
        assertEquals("Source view-object ID is the ancestor (WP-A)",
                parentVoId, entry.sourceViewObjectId());
        assertEquals("Target view-object ID is the descendant (Deliverable-1)",
                childVoId, entry.targetViewObjectId());
        assertEquals("Relationship type is the EMF class name",
                "RealizationRelationship", entry.relationshipType());
        assertEquals("Reason is the fixed enum string",
                "ancestor_descendant_on_view", entry.reason());

        // EMF state: no IDiagramModelArchimateConnection was added to the view.
        IDiagramModelArchimateObject parentVo = lookupArchimateViewObject(model, parentVoId);
        IDiagramModelArchimateObject childVo = lookupArchimateViewObject(model, childVoId);
        assertTrue("Parent has no source-connection",
                parentVo.getSourceConnections().isEmpty());
        assertTrue("Child has no target-connection",
                childVo.getTargetConnections().isEmpty());
    }

    // ------------------------------------------------------------------
    // Sibling pair inside a group (over-skip regression pin).
    // Two archimate objects that are both children of the same group — neither
    // is an ancestor of the other — MUST still be connected.
    // ------------------------------------------------------------------

    @Test
    public void shouldDrawSiblingPair_insideSameGroup() {
        IArchimateModel model = buildModelWithPair("sib-A", "sib-B");
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> groupRes = accessor.addGroupToView(
                "default", "view-001", "Group-X", 50, 50, 400, 200,
                null, null, null);
        String groupVoId = groupRes.entity().viewObjectId();

        accessor.addToView("default", "view-001", "sib-A",
                10, 10, 120, 55, false, groupVoId, null, null);
        accessor.addToView("default", "view-001", "sib-B",
                150, 10, 120, 55, false, groupVoId, null, null);

        MutationResult<AutoConnectResultDto> result = accessor.autoConnectView(
                "default", "view-001", null, null, null, null, null);

        AutoConnectResultDto dto = result.entity();
        assertEquals("Sibling pair inside a group must be drawn",
                1, dto.connectionsCreated());
        assertTrue("skippedDueToNesting must remain empty",
                dto.skippedDueToNesting().isEmpty());
        assertEquals("One relationship ID connected", 1,
                dto.relationshipIdsConnected().size());
    }

    // ------------------------------------------------------------------
    // 3-deep nesting with two transitive ancestor/descendant rels.
    // Container ⊃ WP ⊃ Deliverable; rels Container→Deliverable AND
    // WP→Deliverable. The helper walks transitively through both levels.
    // ------------------------------------------------------------------

    @Test
    public void shouldSkipBothEdges_threeDeepNesting() {
        IArchimateModel model = factory.createArchimateModel();
        model.setName("Auto-Connect Ancestor Skip Test");
        model.setId("model-auto-connect-ancestor-skip");
        model.setDefaults();

        IApplicationComponent containerX = factory.createApplicationComponent();
        containerX.setId("container-X");
        containerX.setName("Container X");
        model.getFolder(FolderType.APPLICATION).getElements().add(containerX);

        IApplicationComponent wpA = factory.createApplicationComponent();
        wpA.setId("wp-A");
        wpA.setName("WP A");
        model.getFolder(FolderType.APPLICATION).getElements().add(wpA);

        IApplicationComponent delD = factory.createApplicationComponent();
        delD.setId("del-D");
        delD.setName("Deliverable D");
        model.getFolder(FolderType.APPLICATION).getElements().add(delD);

        // Two transitive ancestor/descendant rels.
        IArchimateRelationship relXD = factory.createAssociationRelationship();
        relXD.setId("rel-x-d");
        relXD.connect(containerX, delD);
        model.getFolder(FolderType.RELATIONS).getElements().add(relXD);

        IArchimateRelationship relAD = factory.createAssociationRelationship();
        relAD.setId("rel-a-d");
        relAD.connect(wpA, delD);
        model.getFolder(FolderType.RELATIONS).getElements().add(relAD);

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-001");
        view.setName("Main");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<AddToViewResultDto> xRes = accessor.addToView(
                "default", "view-001", "container-X", 50, 50, 400, 300,
                false, null, null, null);
        String xVoId = xRes.entity().viewObject().viewObjectId();

        MutationResult<AddToViewResultDto> aRes = accessor.addToView(
                "default", "view-001", "wp-A", 20, 20, 300, 200,
                false, xVoId, null, null);
        String aVoId = aRes.entity().viewObject().viewObjectId();

        MutationResult<AddToViewResultDto> dRes = accessor.addToView(
                "default", "view-001", "del-D", 20, 20, 100, 50,
                false, aVoId, null, null);
        String dVoId = dRes.entity().viewObject().viewObjectId();

        MutationResult<AutoConnectResultDto> result = accessor.autoConnectView(
                "default", "view-001", null, null, null, null, null);

        AutoConnectResultDto dto = result.entity();
        assertEquals("Neither transitive ancestor pair is drawn",
                0, dto.connectionsCreated());
        assertEquals("Both ancestor/descendant pairs recorded",
                2, dto.skippedDueToNesting().size());

        // The container→deliverable and wp→deliverable pairs are both present.
        // Order is loop-order-dependent but both entries must reference the
        // deliverable view-object as target.
        boolean sawXD = false, sawAD = false;
        for (SkippedNestingPair pair : dto.skippedDueToNesting()) {
            assertEquals("All skipped entries target the deliverable",
                    dVoId, pair.targetViewObjectId());
            if (pair.sourceViewObjectId().equals(xVoId)) {
                sawXD = true;
            } else if (pair.sourceViewObjectId().equals(aVoId)) {
                sawAD = true;
            }
        }
        assertTrue("Container→Deliverable transitive skip recorded", sawXD);
        assertTrue("WP→Deliverable direct-parent skip recorded", sawAD);
    }

    // ------------------------------------------------------------------
    // Cross-branch pair: G-X⊃WP-A, G-Y⊃Del-1; G-X and G-Y are
    // siblings at the view root. Neither endpoint is an ancestor of the
    // other; the connection MUST be drawn (over-skip regression pin).
    // ------------------------------------------------------------------

    @Test
    public void shouldDrawCrossBranchPair() {
        IArchimateModel model = buildModelWithPair("cross-A", "cross-B");
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> gxRes = accessor.addGroupToView(
                "default", "view-001", "Group-X", 50, 50, 250, 200,
                null, null, null);
        String gxVoId = gxRes.entity().viewObjectId();

        MutationResult<ViewGroupDto> gyRes = accessor.addGroupToView(
                "default", "view-001", "Group-Y", 400, 50, 250, 200,
                null, null, null);
        String gyVoId = gyRes.entity().viewObjectId();

        accessor.addToView("default", "view-001", "cross-A",
                10, 10, 120, 55, false, gxVoId, null, null);
        accessor.addToView("default", "view-001", "cross-B",
                10, 10, 120, 55, false, gyVoId, null, null);

        MutationResult<AutoConnectResultDto> result = accessor.autoConnectView(
                "default", "view-001", null, null, null, null, null);

        AutoConnectResultDto dto = result.entity();
        assertEquals("Cross-branch pair must be drawn",
                1, dto.connectionsCreated());
        assertTrue("skippedDueToNesting must remain empty for cross-branch",
                dto.skippedDueToNesting().isEmpty());
    }

    // ------------------------------------------------------------------
    // Empty-commands early-return path threads the skipped list.
    // When the only candidate is an ancestor/descendant pair, the
    // commands.isEmpty() branch fires; the early-return DTO MUST carry
    // the populated skippedDueToNesting list (not List.of()).
    // ------------------------------------------------------------------

    @Test
    public void shouldRecordSkippedPair_evenWhenAllCandidatesAreSkipped() {
        IArchimateModel model = buildModelWithPair("only-A", "only-B");
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<AddToViewResultDto> parentRes = accessor.addToView(
                "default", "view-001", "only-A", 50, 50, 300, 200,
                false, null, null, null);
        String parentVoId = parentRes.entity().viewObject().viewObjectId();

        MutationResult<AddToViewResultDto> childRes = accessor.addToView(
                "default", "view-001", "only-B", 10, 10, 100, 50,
                false, parentVoId, null, null);
        String childVoId = childRes.entity().viewObject().viewObjectId();

        MutationResult<AutoConnectResultDto> result = accessor.autoConnectView(
                "default", "view-001", null, null, null, null, null);

        AutoConnectResultDto dto = result.entity();
        // Hits the empty-commands early-return branch (no other relationships exist).
        assertEquals("Empty commands path: no connection created",
                0, dto.connectionsCreated());
        assertEquals("Empty commands path: idempotent counter zero",
                0, dto.connectionsSkipped());
        assertTrue("Empty commands path: no relationship IDs",
                dto.relationshipIdsConnected().isEmpty());
        // The load-bearing assertion: the early-return DTO MUST carry the
        // populated skippedDueToNesting list — not List.of() and not null.
        assertNotNull("Early-return DTO must carry skippedDueToNesting",
                dto.skippedDueToNesting());
        assertEquals("Pair is recorded even on the empty-commands path",
                1, dto.skippedDueToNesting().size());
        assertEquals(parentVoId,
                dto.skippedDueToNesting().get(0).sourceViewObjectId());
        assertEquals(childVoId,
                dto.skippedDueToNesting().get(0).targetViewObjectId());
    }

    // ------------------------------------------------------------------
    // Flat view byte-identical regression pin.
    // Mirrors ArchiModelAccessorImplTest.shouldAutoConnect_whenRelationshipsExist
    // setup. The four prior fields must match the legacy behaviour
    // exactly, and the new skippedDueToNesting field must be empty.
    // ------------------------------------------------------------------

    @Test
    public void shouldPreserveExistingDtoFields_byteIdenticalOnFlatViews() {
        IArchimateModel model = buildFlatBaselineModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Place both elements as direct view children — no nesting.
        accessor.addToView("default", "view-001", "ac-001",
                50, 50, 120, 55, false, null, null, null);
        accessor.addToView("default", "view-001", "bp-001",
                250, 50, 120, 55, false, null, null, null);

        MutationResult<AutoConnectResultDto> result = accessor.autoConnectView(
                "default", "view-001", null, null, null, null, null);

        AutoConnectResultDto dto = result.entity();
        // Prior fields — must be byte-identical to legacy behaviour.
        assertEquals("view-001", dto.viewId());
        assertEquals("Single serving relationship drawn", 1, dto.connectionsCreated());
        assertEquals("Idempotent counter zero", 0, dto.connectionsSkipped());
        assertEquals("Single relationship ID surfaced",
                List.of("rel-001"), dto.relationshipIdsConnected());
        // New field — must be empty on the flat-view baseline.
        assertNotNull("skippedDueToNesting always present", dto.skippedDueToNesting());
        assertTrue("Flat view must yield zero ancestor/descendant skips",
                dto.skippedDueToNesting().isEmpty());
    }

    // ------------------------------------------------------------------
    // The sibling that places and connects in one call. add-to-view with
    // autoConnect scans the same relationships and drew the pair this pass
    // declines, on exactly the shape the server's own guidance prescribes.
    // ------------------------------------------------------------------

    /**
     * Placing a child inside a container it has a relationship with must not draw the line between
     * them. The nesting already expresses the relationship, and the connection would leave the
     * container and re-enter it — the self-pass-through {@code assess-layout} flags.
     *
     * <p>The model relationship is untouched, which is what makes this a disclosure and not a
     * refusal: the response names the pair, and an agent that wants the line anyway has both view
     * object ids and the relationship id to draw it with.</p>
     */
    @Test
    public void shouldDeclineTheSelfPassThrough_whenAPlacementNestsInsideARelatedEndpoint() {
        IArchimateModel model = buildModelWithPair("wp-A", "del-1", true);
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        String parentVoId = accessor.addToView("default", "view-001", "wp-A",
                50, 50, 300, 200, false, null, null, null)
                .entity().viewObject().viewObjectId();

        MutationResult<AddToViewResultDto> nested = accessor.addToView(
                "default", "view-001", "del-1", 10, 10, 100, 50,
                true, parentVoId, null, null);

        AddToViewResultDto dto = nested.entity();
        String childVoId = dto.viewObject().viewObjectId();

        assertTrue("no connection may be drawn between a child and the container it sits in",
                dto.autoConnections() == null || dto.autoConnections().isEmpty());
        assertEquals("and the pair must be named, not merely omitted",
                1, dto.skippedDueToNesting().size());
        SkippedConnection pair = dto.skippedDueToNesting().get(0);
        assertEquals(parentVoId, pair.sourceViewObjectId());
        assertEquals(childVoId, pair.targetViewObjectId());
        assertEquals("RealizationRelationship", pair.relationshipType());
        assertEquals("rel-wp-A-del-1", pair.relationshipId());
        assertEquals("ancestor_descendant_on_view", pair.reason());
        assertNotNull("the model relationship is preserved — this declines a LINE, not a fact",
                com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(
                        model, "rel-wp-A-del-1"));
    }

    /**
     * The control, and the non-regression. The same relationship between two objects that are
     * siblings rather than nested is still drawn, and both skip lists stay empty — so a flat
     * placement serializes exactly as it did before this pass learned to decline anything.
     */
    @Test
    public void shouldStillDrawTheConnection_whenTheAutoConnectedPlacementIsNotNested() {
        IArchimateModel model = buildModelWithPair("wp-A", "del-1", true);
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        accessor.addToView("default", "view-001", "wp-A",
                50, 50, 300, 200, false, null, null, null);

        AddToViewResultDto dto = accessor.addToView("default", "view-001", "del-1",
                500, 50, 100, 50, true, null, null, null).entity();

        assertEquals("a sibling pair is an ordinary connection and must still be drawn",
                1, dto.autoConnections().size());
        assertTrue("nothing was declined for nesting", dto.skippedDueToNesting().isEmpty());
        assertTrue("nothing was declined by the cap", dto.skippedByCap().isEmpty());
        assertNull("and the cap count stays absent", dto.skippedAutoConnections());
    }

    /**
     * The cap counted what it dropped and named none of it. An agent told that eight connections
     * were excluded cannot draw any of them; one told which can place each with
     * {@code add-connection-to-view}. The count is kept — it was published — and is now pinned as
     * exactly the size of the list beside it, so the two cannot drift.
     */
    @Test
    public void shouldNameThePairsTheCapDropped_ratherThanOnlyCountingThem() {
        int over = 3;
        IArchimateModel model = buildHubModel(50 + over);
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        for (int i = 0; i < 50 + over; i++) {
            accessor.addToView("default", "view-001", "spoke-" + i,
                    600, 40 * i, 80, 30, false, null, null, null);
        }

        AddToViewResultDto dto = accessor.addToView("default", "view-001", "hub",
                50, 50, 200, 100, true, null, null, null).entity();

        assertEquals("the cap still applies", 50, dto.autoConnections().size());
        assertEquals("the published count is unchanged",
                Integer.valueOf(over), dto.skippedAutoConnections());
        assertEquals("and it is now the size of a list that names each one",
                over, dto.skippedByCap().size());
        for (SkippedConnection dropped : dto.skippedByCap()) {
            assertEquals("auto_connect_cap_reached", dropped.reason());
            assertNotNull("each dropped pair must name its relationship, or an agent cannot draw "
                    + "it: " + dropped, dropped.relationshipId());
            assertNotNull(dropped.sourceViewObjectId());
            assertNotNull(dropped.targetViewObjectId());
        }
    }

    // ------------------------------------------------------------------
    // The approval card — the one surface a HUMAN authorises from.
    // ------------------------------------------------------------------

    /**
     * The card is the only description of the pending write anyone gets: it travels verbatim onto
     * the wire for {@code list-pending-approvals} and verbatim into the card's technical details.
     * It collapsed the pairs to a bare integer there, so the response named what was suppressed and
     * the authorising surface did not.
     *
     * <p>Asserted as "not a Number" as well as by content, because replacing the list with
     * {@code .size()} is the exact regression, and an assertion that only checked presence would
     * survive it.</p>
     */
    @Test
    public void shouldCarryThePairsOntoTheApprovalCard_ratherThanTheirCount() {
        // The fixture needs a DRAWABLE pair beside the nested one. A pass whose every candidate is
        // skipped returns at the empty-commands branch, well above the approval gate — correctly,
        // since there is nothing to authorise — so a lone nested pair would store no card at all
        // and the assertions below would never run.
        IArchimateModel model = buildModelWithNestedAndDrawablePairs();
        stubModelManager.setModels(List.of(model));
        MutationDispatcher gated = gatedDispatcher(model);
        accessor = new ArchiModelAccessorImpl(stubModelManager, gated);

        // Place all three with approval OFF at the dispatcher, so the fixture reaches the nested
        // shape rather than parking its own placements for a human.
        String parentVoId = accessor.addToView("default", "view-001", "wp-A",
                50, 50, 300, 200, false, null, null, null)
                .entity().viewObject().viewObjectId();
        accessor.addToView("default", "view-001", "del-1", 10, 10, 100, 50,
                false, parentVoId, null, null);
        accessor.addToView("default", "view-001", "sib-1", 500, 50, 120, 55,
                false, null, null, null);

        approvalRequired = true;
        accessor.autoConnectView("default", "view-001", null, null, null, null, null);

        List<ProposalDto> pending = gated.getPendingProposalDtos("default");
        assertEquals("the call must have stored exactly one proposal", 1, pending.size());
        Object disclosed = pending.get(0).proposedChanges().get("skippedDueToNesting");

        assertEquals("the fixture must still propose real work, or the gate is not the thing "
                + "under test", 1, pending.get(0).proposedChanges().get("connectionsCreated"));
        assertNotNull("the card must disclose what the pass declined to draw", disclosed);
        assertFalse("a count is not a disclosure: the pairs collapse to a number the human cannot "
                + "act on. Card carried: " + disclosed, disclosed instanceof Number);
        assertTrue("it must be the pairs themselves. Card carried: " + disclosed,
                disclosed instanceof List);
        assertEquals("both endpoints and the relationship type, per pair",
                1, ((List<?>) disclosed).size());
        assertTrue("and each entry must be addressable. Card carried: " + disclosed,
                ((List<?>) disclosed).get(0) instanceof SkippedNestingPair);
    }

    /**
     * The negative for the card. A pass that declined nothing must omit the key rather than send an
     * empty list or a zero — a count defaulted to nothing beside a description list reads as a
     * measured all-clear.
     */
    @Test
    public void shouldOmitTheDisclosureFromTheCard_whenThePassDeclinedNothing() {
        IArchimateModel model = buildFlatBaselineModel();
        stubModelManager.setModels(List.of(model));
        MutationDispatcher gated = gatedDispatcher(model);
        accessor = new ArchiModelAccessorImpl(stubModelManager, gated);

        accessor.addToView("default", "view-001", "ac-001", 50, 50, 120, 55,
                false, null, null, null);
        accessor.addToView("default", "view-001", "bp-001", 250, 50, 120, 55,
                false, null, null, null);

        approvalRequired = true;
        accessor.autoConnectView("default", "view-001", null, null, null, null, null);

        List<ProposalDto> pending = gated.getPendingProposalDtos("default");
        assertEquals(1, pending.size());
        assertFalse("nothing was declined, so the card must say nothing rather than say 'none'",
                pending.get(0).proposedChanges().containsKey("skippedDueToNesting"));
    }

    /**
     * The sibling card. A proposal carries {@code proposedChanges} and a summary onto the wire and
     * never the prepared result, so the skip lists this pass computes are invisible to the human
     * authorising it unless they are copied across.
     *
     * <p>The fixture is the worst case on purpose: every eligible pair is nested, so nothing will be
     * drawn. Counting only what WILL be drawn produces a card that mentions auto-connect not at all,
     * which is a human approving silent non-drawing.</p>
     */
    @Test
    public void shouldDiscloseOnTheCard_whatAPlacementsAutoConnectWillNotDraw() {
        IArchimateModel model = buildModelWithPair("wp-A", "del-1", true);
        stubModelManager.setModels(List.of(model));
        MutationDispatcher gated = gatedDispatcher(model);
        accessor = new ArchiModelAccessorImpl(stubModelManager, gated);

        String parentVoId = accessor.addToView("default", "view-001", "wp-A",
                50, 50, 300, 200, false, null, null, null)
                .entity().viewObject().viewObjectId();

        approvalRequired = true;
        accessor.addToView("default", "view-001", "del-1", 10, 10, 100, 50,
                true, parentVoId, null, null);

        List<ProposalDto> pending = gated.getPendingProposalDtos("default");
        assertEquals("the placement must have been parked for a human", 1, pending.size());
        ProposalDto card = pending.get(0);
        Object disclosed = card.proposedChanges().get("skippedDueToNesting");

        assertNotNull("the card must name the connection this placement will decline to draw — "
                + "nothing else on the wire carries it", disclosed);
        assertFalse("the pairs, never their count. Card carried: " + disclosed,
                disclosed instanceof Number);
        assertEquals(1, ((List<?>) disclosed).size());
        assertTrue("and the human-readable summary must say so too, because a card whose only "
                + "auto-connect sentence is about what WILL be drawn says nothing at all when "
                + "nothing will be. Summary was: " + card.validationSummary(),
                card.validationSummary().contains("NOT be drawn"));
        assertTrue("the summary must also say the relationship survives, or 'not drawn' reads as "
                + "'deleted'. Summary was: " + card.validationSummary(),
                card.validationSummary().contains("preserved"));
    }

    /** The negative: a placement that declines nothing must not grow a disclosure. */
    @Test
    public void shouldLeaveTheCardUnchanged_whenAPlacementDeclinesNothing() {
        IArchimateModel model = buildModelWithPair("wp-A", "del-1", true);
        stubModelManager.setModels(List.of(model));
        MutationDispatcher gated = gatedDispatcher(model);
        accessor = new ArchiModelAccessorImpl(stubModelManager, gated);

        accessor.addToView("default", "view-001", "wp-A",
                50, 50, 300, 200, false, null, null, null);

        approvalRequired = true;
        accessor.addToView("default", "view-001", "del-1", 500, 50, 100, 50,
                true, null, null, null);

        ProposalDto card = gated.getPendingProposalDtos("default").get(0);
        assertFalse("nothing was declined, so the key must be absent rather than empty",
                card.proposedChanges().containsKey("skippedDueToNesting"));
        assertFalse("and no cap disclosure either",
                card.proposedChanges().containsKey("skippedByCap"));
        assertTrue("the ordinary sentence is unchanged. Summary was: " + card.validationSummary(),
                card.validationSummary().contains("1 auto-connection(s) will be created."));
    }

    /**
     * Nesting and the cap in ONE call, which nothing else drives.
     *
     * <p>A declined pair no longer consumes a cap slot: {@code eligibleCount} advances in the drawn
     * and capped branches and not in the declined one. That changes WHICH pairs end up capped —
     * more drawable ones now fit under the cap than before — so the two disclosures have to stay
     * exact against each other when both fire, not merely when each fires alone.</p>
     */
    @Test
    public void shouldKeepBothDisclosuresExact_whenAPlacementNestsAndAlsoOverflowsTheCap() {
        int over = 3;
        IArchimateModel model = buildHubModel(50 + over);
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // spoke-0 becomes the hub's CONTAINER, so the hub nests inside a spoke it relates to.
        String containerVoId = accessor.addToView("default", "view-001", "spoke-0",
                0, 0, 600, 400, false, null, null, null)
                .entity().viewObject().viewObjectId();
        for (int i = 1; i < 50 + over; i++) {
            accessor.addToView("default", "view-001", "spoke-" + i,
                    700, 40 * i, 80, 30, false, null, null, null);
        }

        AddToViewResultDto dto = accessor.addToView("default", "view-001", "hub",
                10, 10, 200, 100, true, containerVoId, null, null).entity();

        assertEquals("the one nested pair is declined and named", 1,
                dto.skippedDueToNesting().size());
        assertEquals("and it is the container it was placed into", containerVoId,
                dto.skippedDueToNesting().get(0).targetViewObjectId());

        // 52 remaining candidates, 50 drawn, 2 capped — the declined pair did NOT eat a cap slot,
        // which is the whole point: a connection that was never going to be drawn cannot displace
        // one that would have been.
        assertEquals("the cap is spent only on connections that could actually be drawn",
                50, dto.autoConnections().size());
        assertEquals("the count and the list must agree when BOTH disclosures fire",
                Integer.valueOf(dto.skippedByCap().size()), dto.skippedAutoConnections());
        assertEquals(2, dto.skippedByCap().size());

        for (SkippedConnection capped : dto.skippedByCap()) {
            assertEquals("auto_connect_cap_reached", capped.reason());
        }
        assertEquals("a declined pair must not also be reported as capped, or the agent double-counts",
                "ancestor_descendant_on_view", dto.skippedDueToNesting().get(0).reason());
    }

    // ------------------------------------------------------------------
    // Fixture builders
    // ------------------------------------------------------------------

    /**
     * Default-overload: pair held together by an {@code AssociationRelationship}.
     * Used by the sibling, cross-branch, and all-skipped tests — none of which
     * pin the relationship type in their assertions (the skip behaviour is
     * type-agnostic).
     */
    private IArchimateModel buildModelWithPair(String sourceId, String targetId) {
        return buildModelWithPair(sourceId, targetId, /*useRealization=*/ false);
    }

    /**
     * Two ApplicationComponents + a single relationship between them
     * (source = first, target = second) + an empty {@code view-001} diagram.
     *
     * <p>{@code useRealization=true} uses a {@code RealizationRelationship}
     * (matches the spec's wording verbatim AND the View K retail-bank
     * reproducer); {@code false} uses an {@code AssociationRelationship}.
     * EMF-level {@code connect()} does not enforce ArchiMate semantic-validity
     * rules, so either type is wireable between two ApplicationComponents for
     * test-fixture purposes — the autoConnectView path under test only inspects
     * {@code rel.eContainer()} and {@code rel.eClass().getName()}.
     */
    private IArchimateModel buildModelWithPair(String sourceId, String targetId,
                                               boolean useRealization) {
        IArchimateModel model = factory.createArchimateModel();
        model.setName("Test 14-11 pair");
        model.setId("model-14-11-pair-" + sourceId + "-" + targetId);
        model.setDefaults();

        IApplicationComponent src = factory.createApplicationComponent();
        src.setId(sourceId);
        src.setName(sourceId);
        model.getFolder(FolderType.APPLICATION).getElements().add(src);

        IApplicationComponent tgt = factory.createApplicationComponent();
        tgt.setId(targetId);
        tgt.setName(targetId);
        model.getFolder(FolderType.APPLICATION).getElements().add(tgt);

        IArchimateRelationship rel = useRealization
                ? factory.createRealizationRelationship()
                : factory.createAssociationRelationship();
        rel.setId("rel-" + sourceId + "-" + targetId);
        rel.connect(src, tgt);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-001");
        view.setName("Main");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        return model;
    }

    /**
     * Mirrors {@code ArchiModelAccessorImplTest.createTestModel()} for the flat
     * baseline pin. ac-001 (Order System) — bp-001 (Order Processing) with
     * rel-001 (ServingRelationship) — same shape as the v1 surface used by
     * {@code shouldAutoConnect_whenRelationshipsExist}.
     */
    private IArchimateModel buildFlatBaselineModel() {
        IArchimateModel model = factory.createArchimateModel();
        model.setName("Test 14-11 flat baseline");
        model.setId("model-14-11-flat");
        model.setDefaults();

        IApplicationComponent appComp = factory.createApplicationComponent();
        appComp.setId("ac-001");
        appComp.setName("Order System");
        model.getFolder(FolderType.APPLICATION).getElements().add(appComp);

        IBusinessProcess process = factory.createBusinessProcess();
        process.setId("bp-001");
        process.setName("Order Processing");
        model.getFolder(FolderType.BUSINESS).getElements().add(process);

        IArchimateRelationship serving = factory.createServingRelationship();
        serving.setId("rel-001");
        serving.setName("serves");
        serving.connect(appComp, process);
        model.getFolder(FolderType.RELATIONS).getElements().add(serving);

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-001");
        view.setName("Main View");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        return model;
    }

    /**
     * Walks the diagram tree to locate the archimate view object with the
     * given view-object ID. Returns {@code null} if not found.
     */
    private IDiagramModelArchimateObject lookupArchimateViewObject(
            IArchimateModel model, String viewObjectId) {
        for (Object viewEObj : model.getFolder(FolderType.DIAGRAMS).getElements()) {
            if (!(viewEObj instanceof IArchimateDiagramModel view)) {
                continue;
            }
            IDiagramModelArchimateObject found = findInChildren(view.getChildren(), viewObjectId);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private IDiagramModelArchimateObject findInChildren(
            List<? extends Object> children, String viewObjectId) {
        for (Object child : children) {
            if (child instanceof IDiagramModelArchimateObject dmao
                    && viewObjectId.equals(dmao.getId())) {
                return dmao;
            }
            if (child instanceof com.archimatetool.model.IDiagramModelContainer container) {
                IDiagramModelArchimateObject nested =
                        findInChildren(container.getChildren(), viewObjectId);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Test plumbing (minimal stub IEditorModelManager + synchronous dispatcher)
    // — mirror of ArchiModelAccessorImplTest.createAccessorWithTestDispatcher
    // and the per-file StubEditorModelManager already in use by
    // ArchiModelAccessorImplAddViewReferenceToViewTest.
    // ------------------------------------------------------------------

    /** As {@link #createAccessorWithTestDispatcher}, but the gate follows {@code approvalRequired}. */
    private MutationDispatcher gatedDispatcher(IArchimateModel testModel) {
        MutationDispatcher testDispatcher = new MutationDispatcher(() -> testModel) {
            @Override
            public void dispatchImmediate(Command command) {
                executeDecomposed(command);
            }
            @Override
            protected void dispatchCommand(Command command) {
                executeDecomposed(command);
            }
            private void executeDecomposed(Command command) {
                if (command instanceof CompoundCommand compound) {
                    for (Object cmd : compound.getCommands()) {
                        executeDecomposed((Command) cmd);
                    }
                } else {
                    command.execute();
                }
            }
        };
        testDispatcher.setApprovalModeProvider(() -> approvalRequired);
        return testDispatcher;
    }

    /**
     * {@code wp-A} realized by {@code del-1} (the pair that will be nested and declined) and
     * associated with {@code sib-1} (the pair that will be drawn), on an empty {@code view-001}.
     */
    private IArchimateModel buildModelWithNestedAndDrawablePairs() {
        IArchimateModel model = buildModelWithPair("wp-A", "del-1", true);
        model.setId("model-auto-connect-card");

        IApplicationComponent sibling = factory.createApplicationComponent();
        sibling.setId("sib-1");
        sibling.setName("sib-1");
        model.getFolder(FolderType.APPLICATION).getElements().add(sibling);

        IArchimateRelationship drawable = factory.createAssociationRelationship();
        drawable.setId("rel-wp-A-sib-1");
        drawable.connect(
                (IApplicationComponent) com.archimatetool.model.util.ArchimateModelUtils
                        .getObjectByID(model, "wp-A"),
                sibling);
        model.getFolder(FolderType.RELATIONS).getElements().add(drawable);

        return model;
    }

    /**
     * A hub with {@code spokes} outgoing relationships, for driving the fifty-connection cap.
     */
    private IArchimateModel buildHubModel(int spokes) {
        IArchimateModel model = factory.createArchimateModel();
        model.setName("Auto-connect cap fixture");
        model.setId("model-auto-connect-cap");
        model.setDefaults();

        IApplicationComponent hub = factory.createApplicationComponent();
        hub.setId("hub");
        hub.setName("Hub");
        model.getFolder(FolderType.APPLICATION).getElements().add(hub);

        for (int i = 0; i < spokes; i++) {
            IApplicationComponent spoke = factory.createApplicationComponent();
            spoke.setId("spoke-" + i);
            spoke.setName("Spoke " + i);
            model.getFolder(FolderType.APPLICATION).getElements().add(spoke);

            IArchimateRelationship rel = factory.createAssociationRelationship();
            rel.setId("rel-spoke-" + i);
            rel.connect(hub, spoke);
            model.getFolder(FolderType.RELATIONS).getElements().add(rel);
        }

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-001");
        view.setName("Hub");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        return model;
    }

    private ArchiModelAccessorImpl createAccessorWithTestDispatcher(
            IArchimateModel testModel) {
        MutationDispatcher testDispatcher = new MutationDispatcher(() -> testModel) {
            @Override
            public void dispatchImmediate(Command command) {
                executeDecomposed(command);
            }
            @Override
            protected void dispatchCommand(Command command) {
                executeDecomposed(command);
            }
            private void executeDecomposed(Command command) {
                if (command instanceof CompoundCommand compound) {
                    for (Object cmd : compound.getCommands()) {
                        executeDecomposed((Command) cmd);
                    }
                } else {
                    command.execute();
                }
            }
        };
        // The dispatcher now defaults to GATED (fail-safe). These tests exercise the
        // immediate-apply path, so opt approval OFF explicitly (production wires the human bit).
        testDispatcher.setApprovalModeProvider(() -> false);
        return new ArchiModelAccessorImpl(stubModelManager, testDispatcher);
    }

    private static class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final List<PropertyChangeListener> listeners = new ArrayList<>();

        void setModels(List<IArchimateModel> models) {
            this.models = models;
        }

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
