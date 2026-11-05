package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.MergeClusterRequest;
import net.vheerden.archi.mcp.response.dto.MergeElementsResultDto;

/**
 * {@code merge-elements} at the accessor: the caller errors and the order they are checked in,
 * and the approval proposal; inside a batch, {@link MergeElementsBatchTest}. Every refusal is also
 * checked to leave the model unchanged and to store no proposal, under the approval gate.
 */
public class MergeElementsOperationsTest {

    private static final String SESSION = "merge-ops-session";

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private final MergeElementsCommandTest.StubEditorModelManager models =
            new MergeElementsCommandTest.StubEditorModelManager();
    private IArchimateModel model;
    private ArchiModelAccessorImpl accessor;
    private CommandStack stack;
    private final List<Command> dispatched = new ArrayList<>();
    /** Runs on an immediate call, after it is prepared and before its command executes. */
    private Runnable beforeDispatch;

    @Before
    public void setUp() {
        model = factory.createArchimateModel();
        model.setId("model-merge-ops");
        model.setDefaults();
        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("View");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        IFolder sub = factory.createFolder();
        sub.setId("folder-1");
        sub.setName("Sub");
        model.getFolder(FolderType.BUSINESS).getFolders().add(sub);
        actor("t", "Customer");
        actor("s", "Customer");
        actor("s2", "Customer");
        actor("u", "Billing");
        actor("v", "Billing");
        IArchimateElement role = factory.createBusinessRole();
        role.setId("role");
        role.setName("Customer");
        model.getFolder(FolderType.BUSINESS).getElements().add(role);
        IArchimateRelationship rel = factory.createAssociationRelationship();
        rel.setId("rel-1");
        rel.connect(element("s"), element("u"));
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);
        IDiagramModelArchimateObject box = factory.createDiagramModelArchimateObject();
        box.setId("box-s");
        box.setArchimateElement(element("s"));
        box.setBounds(0, 0, 120, 55);
        view.getChildren().add(box);
        models.setModels(List.of(model));

        stack = new CommandStack();
        MutationDispatcher dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                if (beforeDispatch != null) {
                    beforeDispatch.run();
                }
                dispatched.add(command);
                stack.execute(command);
            }

            @Override
            protected void dispatchCommand(Command command) {
                dispatched.add(command);
                stack.execute(command);
            }
        };
        dispatcher.setApprovalModeProvider(() -> false);
        accessor = new ArchiModelAccessorImpl(models, dispatcher);
    }

    @After
    public void tearDown() {
        accessor.dispose();
    }

    // ---- caller errors ------------------------------------------------------------------------

    @Test
    public void shouldRefuseAnIdThatNamesNothing_asElementNotFound() {
        assertRefused(ErrorCode.ELEMENT_NOT_FOUND, "Element not found: ghost (merges[0].sourceIds[1])",
                cluster("t", "s", "ghost"));
    }

    @Test
    public void shouldRefuseARelationshipAViewOrAFolderId_namingWhatItIs() {
        assertRefused(ErrorCode.INVALID_PARAMETER, "rel-1 (merges[0].sourceIds[0]) is a relationship",
                cluster("t", "rel-1"));
        assertRefused(ErrorCode.INVALID_PARAMETER, "view-1 (merges[0].targetId) is a view",
                cluster("view-1", "s"));
        assertRefused(ErrorCode.INVALID_PARAMETER, "folder-1 (merges[0].sourceIds[0]) is a folder",
                cluster("t", "folder-1"));
    }

    @Test
    public void shouldRefuseATargetListedAsASource_inItsOwnClusterOrAnother() {
        assertRefused(ErrorCode.INVALID_PARAMETER,
                "Element t appears at both merges[0].targetId and merges[0].sourceIds[0]", cluster("t", "t"));
        assertRefused(ErrorCode.INVALID_PARAMETER,
                "Element t appears at both merges[0].targetId and merges[1].sourceIds[0]",
                cluster("t", "s"), cluster("u", "t"));
    }

    @Test
    public void shouldRefuseAnIdInTwoClusters_andAnIdTwiceInOneCluster() {
        assertRefused(ErrorCode.INVALID_PARAMETER,
                "Element s appears at both merges[0].sourceIds[0] and merges[1].sourceIds[0]",
                cluster("t", "s"), cluster("u", "s"));
        assertRefused(ErrorCode.INVALID_PARAMETER,
                "Element s appears at both merges[0].sourceIds[0] and merges[0].sourceIds[1]",
                cluster("t", "s", "s"));
    }

    @Test
    public void shouldRefuseADuplicateOfAnotherType_naminBothTypes() {
        ModelAccessException e = assertRefused(ErrorCode.INVALID_ELEMENT_TYPE,
                "Cannot merge role (BusinessRole) into t (BusinessActor)", cluster("t", "role"));
        assertTrue(e.getSuggestedCorrection().startsWith("Change its type first"));
    }

    @Test
    public void shouldRefuseAnUnknownPolicy_listingTheAllowedValues() {
        assertRefusedWith("bogus", null, ErrorCode.INVALID_PARAMETER,
                "propertyPolicy 'bogus' is not one of union, keep-target, append", cluster("t", "s"));
        assertRefusedWith(null, "always", ErrorCode.INVALID_PARAMETER,
                "selfRelationships 'always' is not one of drop, keep", cluster("t", "s"));
    }

    @Test
    public void shouldRefuseAnEmptyCall_anEmptyCluster_andAClusterWithoutATarget() {
        assertRefusedList(ErrorCode.INVALID_PARAMETER, "merges must list at least one cluster", List.of());
        assertRefusedList(ErrorCode.INVALID_PARAMETER, "merges must list at least one cluster", null);
        assertRefused(ErrorCode.INVALID_PARAMETER, "merges[0] has no sourceIds",
                new MergeClusterRequest("t", List.of()));
        assertRefused(ErrorCode.INVALID_PARAMETER, "merges[0] has no sourceIds",
                new MergeClusterRequest("t", null));
        assertRefused(ErrorCode.INVALID_PARAMETER, "merges[1] has no targetId",
                cluster("t", "s"), new MergeClusterRequest(null, List.of("u")));
        assertRefused(ErrorCode.INVALID_PARAMETER, "merges[0].sourceIds[0] is blank",
                new MergeClusterRequest("t", Arrays.asList(" ")));
    }

    @Test
    public void shouldEchoAnUnusualIdEscaped() {
        assertRefused(ErrorCode.ELEMENT_NOT_FOUND, "Element not found: 'p\\nq' (merges[0].sourceIds[0])",
                cluster("t", "p\nq"));
    }

    @Test
    public void shouldCheckEveryClusterInTheStatedOrder_beforeAnyIsMerged() {
        // policy first, before the clusters are looked at
        assertRefusedWith("bogus", null, ErrorCode.INVALID_PARAMETER, "propertyPolicy", cluster("t", "ghost"));
        // shape of every cluster before any id is checked
        assertRefused(ErrorCode.INVALID_PARAMETER, "merges[1] has no sourceIds",
                cluster("t", "ghost"), new MergeClusterRequest("u", List.of()));
        // repeated ids before resolution
        assertRefused(ErrorCode.INVALID_PARAMETER, "appears at both",
                cluster("t", "ghost"), cluster("u", "ghost"));
        // resolution of every cluster before types
        assertRefused(ErrorCode.ELEMENT_NOT_FOUND, "Element not found: ghost",
                cluster("t", "role"), cluster("u", "ghost"));
        // and a good cluster before a bad one is not merged
        assertRefused(ErrorCode.INVALID_ELEMENT_TYPE, "Cannot merge role",
                cluster("u", "v"), cluster("t", "role"));
        assertNotNull(element("v"));
    }

    // ---- approval -----------------------------------------------------------------------------

    @Test
    public void shouldStoreOneProposal_describingTheMerge_andLeaveTheModelUntilApproval() throws Exception {
        approvalOn();
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());

        MutationResult<MergeElementsResultDto> result = accessor.mergeElements(SESSION,
                List.of(cluster("t", "s", "s2"), cluster("u", "v")), "append", "keep", true, false);

        assertNotNull(result.proposalContext());
        assertEquals(1, accessor.getMutationDispatcher().getPendingProposals(SESSION).size());
        PendingProposal pending = accessor.getMutationDispatcher().getProposal(SESSION,
                result.proposalContext().proposalId());
        assertEquals("Merge 3 duplicates into 2 elements (1 relationship re-pointed, 1 view object "
                + "re-pointed, 0 self-relationships dropped)", pending.description());
        assertEquals("merge-elements", pending.tool());
        assertEquals(List.of(Map.of("targetId", "t", "sourceIds", List.of("s", "s2")),
                Map.of("targetId", "u", "sourceIds", List.of("v"))), pending.proposedChanges().get("merges"));
        assertEquals("append", pending.proposedChanges().get("propertyPolicy"));
        assertEquals("keep", pending.proposedChanges().get("selfRelationships"));
        assertEquals(Boolean.TRUE, pending.proposedChanges().get("recordMergedIds"));
        assertEquals(Boolean.FALSE, pending.proposedChanges().get("collapseParallel"));
        assertNotNull(pending.proposedChanges().get("clusters"));
        @SuppressWarnings("unchecked")
        Map<String, Object> card = ((List<Map<String, Object>>) pending.proposedChanges().get("clusters")).get(0);
        assertTrue("no fold count when nothing is folded by request: " + card,
                !card.containsKey("parallelsCollapsed"));
        MergeElementsCommandTest.assertSnapshot("nothing is written until approval", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
        assertTrue(dispatched.isEmpty());

        accessor.getMutationDispatcher().approveProposal(SESSION, result.proposalContext().proposalId());

        assertNull(ArchimateModelUtils.getObjectByID(model, "s"));
        assertSame(element("t"), ((IArchimateRelationship) ArchimateModelUtils.getObjectByID(model, "rel-1"))
                .getSource());
        assertEquals("one stack entry for the whole call", 1, dispatched.size());
        stack.undo();
        MergeElementsCommandTest.assertSnapshot("one undo restores the approved merge", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
    }

    /**
     * The guard fingerprints every id the proposal names. Whether an attribute edit counts depends
     * on a human command having been seen on Archi's stack, which a headless fixture does not have;
     * a removal is stale either way, so that is the edit used here, and the tracked ids are pinned
     * so an edit to any target or duplicate reaches the guard's own rule.
     */
    @Test
    public void shouldRejectTheProposalAsStale_whenADuplicateIsRemovedWhileItWaits() {
        approvalOn();
        MutationResult<MergeElementsResultDto> result = accessor.mergeElements(SESSION,
                List.of(cluster("t", "s", "s2"), cluster("u", "v")), null, null, false, false);
        PendingProposal pending = accessor.getMutationDispatcher().getProposal(SESSION,
                result.proposalContext().proposalId());
        assertEquals(Set.of("t", "s", "s2", "u", "v"), Set.copyOf(pending.capture().targetIds()));
        model.getFolder(FolderType.BUSINESS).getElements().remove(element("s2"));

        try {
            accessor.getMutationDispatcher().approveProposal(SESSION, result.proposalContext().proposalId());
            fail("a duplicate removed while the proposal waited must make it stale");
        } catch (MutationException expected) {
            // stale
        }
        assertNotNull("nothing merged", ArchimateModelUtils.getObjectByID(model, "s"));
        assertNotNull(ArchimateModelUtils.getObjectByID(model, "v"));
        assertTrue(dispatched.isEmpty());
    }

    /**
     * The approved merge is read again from the model, so a relationship added to a duplicate
     * while the card waits moves too. A deletion is different: a self-relationship added meanwhile
     * would be deleted by a card that never showed it, so the approved merge writes nothing, and
     * the approval says so, keeps the card and leaves the model version where it was.
     */
    @Test
    public void shouldDeclineAnApprovedMerge_thatWouldDeleteWhatTheCardDidNotList() throws Exception {
        approvalOn();
        MutationResult<MergeElementsResultDto> result = accessor.mergeElements(SESSION,
                List.of(cluster("t", "s")), null, null, false, false);
        IArchimateRelationship late = factory.createAssociationRelationship();
        late.setId("late-self");
        late.connect(element("s"), element("t"));
        model.getFolder(FolderType.RELATIONS).getElements().add(late);
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());
        String version = accessor.getModelVersion();

        try {
            accessor.getMutationDispatcher().approveProposal(SESSION, result.proposalContext().proposalId());
            fail("an approval that wrote nothing must not report success");
        } catch (MutationException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("merge-elements wrote nothing: it would now also "
                    + "delete 1 relationship(s) or view connection(s) the proposal did not list ('late-self')"));
            assertTrue(e.getMessage(), e.getMessage().endsWith(" Nothing was changed. Reject this proposal and "
                    + "ask the agent to make the change again."));
        }

        MergeElementsCommandTest.assertSnapshot("the approved merge must write nothing", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
        assertEquals("the card stays, with its reason", 1,
                accessor.getMutationDispatcher().getPendingProposals(SESSION).size());
        assertEquals("nothing was written, so the version stays", version, accessor.getModelVersion());
        MergeElementsCommand command = dispatched.stream().filter(MergeElementsCommand.class::isInstance)
                .map(MergeElementsCommand.class::cast).findFirst().orElseThrow();
        assertTrue(command.getSkipReason(), command.getSkipReason().contains("'late-self'")
                && command.getSkipReason().contains("the proposal did not list"));
    }

    /**
     * An immediate merge that declines as it runs, with nothing written, answers the same refusal as
     * before and leaves the model version alone; one that runs advances it once.
     */
    @Test
    public void shouldLeaveTheVersion_whenAnImmediateMergeDeclinesWithNothingWritten() {
        beforeDispatch = () -> model.getFolder(FolderType.BUSINESS).getElements().remove(element("s"));
        long version = Long.parseLong(accessor.getModelVersion());

        try {
            accessor.mergeElements(SESSION, List.of(cluster("t", "s")), null, null, false, false);
            fail("a merge that declined must be refused");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.MUTATION_FAILED, e.getErrorCode());
            assertTrue(e.getMessage(), e.getMessage().startsWith("merge-elements wrote nothing: Element not found: s"));
            assertEquals("Nothing was changed. Re-read the elements with get-element and call merge-elements "
                    + "again; if it stops the same way, the merge cannot be made as asked.", e.getSuggestedCorrection());
        }
        assertEquals("a decline that wrote nothing is no new version", version,
                Long.parseLong(accessor.getModelVersion()));

        beforeDispatch = null;
        accessor.mergeElements(SESSION, List.of(cluster("u", "v")), null, null, false, false);
        assertNull(ArchimateModelUtils.getObjectByID(model, "v"));
        assertEquals("a merge that ran is one new version", version + 1, Long.parseLong(accessor.getModelVersion()));
    }

    @Test
    public void shouldMoveARelationshipAddedToADuplicate_whileTheCardWaits() throws Exception {
        approvalOn();
        MutationResult<MergeElementsResultDto> result = accessor.mergeElements(SESSION,
                List.of(cluster("t", "s")), null, null, false, false);
        IArchimateRelationship late = factory.createAssociationRelationship();
        late.setId("late-move");
        late.connect(element("s"), element("v"));
        model.getFolder(FolderType.RELATIONS).getElements().add(late);

        accessor.getMutationDispatcher().approveProposal(SESSION, result.proposalContext().proposalId());

        assertNull(ArchimateModelUtils.getObjectByID(model, "s"));
        assertSame(element("t"), late.getSource());
    }

    @Test
    public void shouldShowOnTheCardEveryViewConnectionTheMergeRemoves() {
        approvalOn();
        IArchimateDiagramModel view = (IArchimateDiagramModel) ArchimateModelUtils.getObjectByID(model, "view-1");
        IDiagramModelArchimateObject boxT = factory.createDiagramModelArchimateObject();
        boxT.setId("box-t");
        boxT.setArchimateElement(element("t"));
        boxT.setBounds(300, 0, 120, 55);
        view.getChildren().add(boxT);
        IArchimateRelationship self = factory.createAssociationRelationship();
        self.setId("self");
        self.connect(element("s"), element("t"));
        model.getFolder(FolderType.RELATIONS).getElements().add(self);
        com.archimatetool.model.IDiagramModelArchimateConnection drawn =
                factory.createDiagramModelArchimateConnection();
        drawn.setId("conn-self");
        drawn.setArchimateRelationship(self);
        drawn.connect((IDiagramModelArchimateObject) ArchimateModelUtils.getObjectByID(model, "box-s"), boxT);

        MutationResult<MergeElementsResultDto> result = accessor.mergeElements(SESSION,
                List.of(cluster("t", "s")), null, null, false, false);

        @SuppressWarnings("unchecked")
        Map<String, Object> card = ((List<Map<String, Object>>) accessor.getMutationDispatcher()
                .getProposal(SESSION, result.proposalContext().proposalId()).proposedChanges().get("clusters"))
                .get(0);
        assertEquals(1, card.get("selfRelationshipsDropped"));
        assertEquals(1, card.get("viewConnectionsRemoved"));
    }

    /**
     * Under collapseParallel the card says how many relationships fold and counts the view
     * connections a fold removes with the rest, and the proposal carries the option so the
     * approved merge folds the same way.
     */
    @Test
    public void shouldShowTheFoldsOnTheCard_andFoldTheSameWayOnApproval() throws Exception {
        approvalOn();
        IArchimateDiagramModel view = (IArchimateDiagramModel) ArchimateModelUtils.getObjectByID(model, "view-1");
        IDiagramModelArchimateObject boxS = (IDiagramModelArchimateObject) ArchimateModelUtils.getObjectByID(model,
                "box-s");
        IDiagramModelArchimateObject boxU = factory.createDiagramModelArchimateObject();
        boxU.setId("box-u");
        boxU.setArchimateElement(element("u"));
        boxU.setBounds(300, 0, 120, 55);
        view.getChildren().add(boxU);
        IArchimateRelationship survivor = factory.createAssociationRelationship();
        survivor.setId("on-target");
        survivor.connect(element("t"), element("u"));
        model.getFolder(FolderType.RELATIONS).getElements().add(survivor);
        IArchimateRelationship twin = factory.createAssociationRelationship();
        twin.setId("twin");
        twin.connect(element("s"), element("u"));
        model.getFolder(FolderType.RELATIONS).getElements().add(twin);
        for (String[] each : new String[][] {{"conn-rel-1", "rel-1"}, {"conn-twin", "twin"}}) {
            com.archimatetool.model.IDiagramModelArchimateConnection drawn =
                    factory.createDiagramModelArchimateConnection();
            drawn.setId(each[0]);
            drawn.setArchimateRelationship((IArchimateRelationship) ArchimateModelUtils.getObjectByID(model, each[1]));
            drawn.connect(boxS, boxU);
        }

        MutationResult<MergeElementsResultDto> result = accessor.mergeElements(SESSION,
                List.of(cluster("t", "s")), null, null, false, true);

        PendingProposal pending = accessor.getMutationDispatcher().getProposal(SESSION,
                result.proposalContext().proposalId());
        assertEquals(Boolean.TRUE, pending.proposedChanges().get("collapseParallel"));
        assertEquals("Merge 1 duplicate into 1 element (0 relationships re-pointed, 1 view object re-pointed, "
                + "0 self-relationships dropped, 2 parallel relationships collapsed)", pending.description());
        @SuppressWarnings("unchecked")
        Map<String, Object> card = ((List<Map<String, Object>>) pending.proposedChanges().get("clusters")).get(0);
        assertEquals(2, card.get("parallelsCollapsed"));
        assertEquals("the fold-removed connection is counted with the rest", 1, card.get("viewConnectionsRemoved"));

        accessor.getMutationDispatcher().approveProposal(SESSION, result.proposalContext().proposalId());

        assertNull(ArchimateModelUtils.getObjectByID(model, "rel-1"));
        assertNull(ArchimateModelUtils.getObjectByID(model, "twin"));
        assertNull(ArchimateModelUtils.getObjectByID(model, "conn-twin"));
        assertSame(survivor, ((com.archimatetool.model.IDiagramModelArchimateConnection) ArchimateModelUtils
                .getObjectByID(model, "conn-rel-1")).getArchimateRelationship());
    }

    @Test
    public void shouldSayOneParallelRelationship_inTheSingular() {
        MergeElementsResultDto.Totals totals = new MergeElementsResultDto.Totals(1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1);
        MergeElementsResultDto preview = new MergeElementsResultDto(List.of(), Map.of(), totals, "union", "drop",
                false, true, Map.of());
        assertTrue(MergeElementsOperations.describe(preview),
                MergeElementsOperations.describe(preview).endsWith(", 1 parallel relationship collapsed)"));
    }

    // ---- helpers ------------------------------------------------------------------------------

    private void approvalOn() {
        accessor.getMutationDispatcher().setApprovalModeProvider(() -> true);
    }

    private ModelAccessException assertRefused(ErrorCode code, String messagePart, MergeClusterRequest... clusters) {
        return assertRefusedWith(null, null, code, messagePart, clusters);
    }

    private void assertRefusedList(ErrorCode code, String messagePart, List<MergeClusterRequest> merges) {
        check(null, null, code, messagePart, merges);
    }

    private ModelAccessException assertRefusedWith(String propertyPolicy, String selfRelationships, ErrorCode code,
            String messagePart, MergeClusterRequest... clusters) {
        return check(propertyPolicy, selfRelationships, code, messagePart, Arrays.asList(clusters));
    }

    /** Refused with this code and cause, the model unchanged and no proposal stored, under approval. */
    private ModelAccessException check(String propertyPolicy, String selfRelationships, ErrorCode code,
            String messagePart, List<MergeClusterRequest> merges) {
        approvalOn();
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());
        ModelAccessException e = refusal(() -> accessor.mergeElements(SESSION, merges, propertyPolicy,
                selfRelationships, false, false));
        assertEquals(e.getMessage(), code, e.getErrorCode());
        assertTrue("message '" + e.getMessage() + "' should contain '" + messagePart + "'",
                e.getMessage().contains(messagePart));
        assertNotNull("a caller error says what to do", e.getSuggestedCorrection());
        MergeElementsCommandTest.assertSnapshot("refused means unchanged", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
        assertTrue("refused means nothing proposed",
                accessor.getMutationDispatcher().getPendingProposals(SESSION).isEmpty());
        assertTrue(dispatched.isEmpty());
        return e;
    }

    private static ModelAccessException refusal(Runnable call) {
        try {
            call.run();
        } catch (ModelAccessException e) {
            return e;
        }
        fail("expected a refusal");
        return null;
    }

    private void actor(String id, String name) {
        IArchimateElement actor = factory.createBusinessActor();
        actor.setId(id);
        actor.setName(name);
        model.getFolder(FolderType.BUSINESS).getElements().add(actor);
    }

    private IArchimateElement element(String id) {
        return (IArchimateElement) ArchimateModelUtils.getObjectByID(model, id);
    }

    private static MergeClusterRequest cluster(String targetId, String... sourceIds) {
        return new MergeClusterRequest(targetId, Arrays.asList(sourceIds));
    }
}
