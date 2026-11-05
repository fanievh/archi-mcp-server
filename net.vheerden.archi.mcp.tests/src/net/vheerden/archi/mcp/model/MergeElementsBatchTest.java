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
import java.util.function.Supplier;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IJunction;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.BatchSummaryDto;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.MergeClusterRequest;
import net.vheerden.archi.mcp.response.dto.MergeElementsResultDto;

/**
 * {@code merge-elements} inside a batch: queued as one operation and decided at commit, after what
 * was queued before it; closing the batch to every later operation but another merge; refusing a
 * later merge that names one of its duplicates, and an id only the batch creates; queued from an
 * approved card under the same rule; and one undo step for the whole committed batch.
 */
public class MergeElementsBatchTest {

    private static final String SESSION = "merge-batch-session";

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private final MergeElementsCommandTest.StubEditorModelManager models =
            new MergeElementsCommandTest.StubEditorModelManager();
    private IArchimateModel model;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private CommandStack stack;
    private final List<Command> dispatched = new ArrayList<>();

    @Before
    public void setUp() {
        model = factory.createArchimateModel();
        model.setId("model-merge-batch");
        model.setDefaults();
        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("View");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        actor("t", "Customer");
        actor("s", "Customer");
        actor("s2", "Customer");
        actor("u", "Billing");
        actor("v", "Billing");
        IArchimateElement role = factory.createBusinessRole();
        role.setId("role");
        role.setName("Customer");
        model.getFolder(FolderType.BUSINESS).getElements().add(role);
        junction("and-1", IJunction.AND_JUNCTION_TYPE);
        junction("or-1", IJunction.OR_JUNCTION_TYPE);
        IArchimateRelationship rel = factory.createAssociationRelationship();
        rel.setId("rel-1");
        rel.connect(element("s"), element("u"));
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);
        IDiagramModelArchimateObject boxS = box(view, "box-s", "s", 0);
        IDiagramModelArchimateObject boxU = box(view, "box-u", "u", 300);
        IDiagramModelArchimateConnection drawn = factory.createDiagramModelArchimateConnection();
        drawn.setId("conn-1");
        drawn.setArchimateRelationship(rel);
        drawn.connect(boxS, boxU);
        models.setModels(List.of(model));

        stack = new CommandStack();
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                dispatched.add(command);
                stack.execute(toPlainCompound(command));
            }

            @Override
            protected void dispatchCommand(Command command) {
                dispatched.add(command);
                stack.execute(toPlainCompound(command));
            }
        };
        dispatcher.setApprovalModeProvider(() -> false);
        accessor = new ArchiModelAccessorImpl(models, dispatcher);
    }

    @After
    public void tearDown() {
        accessor.dispose();
    }

    // ---- queued, not refused ------------------------------------------------------------------

    @Test
    public void shouldQueueOneOperation_describedByItsPreview_andLeaveTheModelUntilCommit() {
        Map<String, String> before = snapshot();
        dispatcher.beginBatch(SESSION, "cleanup");

        MutationResult<MergeElementsResultDto> result = merge(cluster("t", "s"));

        assertTrue(result.isBatched());
        assertEquals(Integer.valueOf(1), result.batchSequenceNumber());
        assertEquals(1, (int) dispatcher.getBatchStatus(SESSION).queuedCount());
        String expected = "Merge 1 duplicate into 1 element (1 relationship re-pointed, 1 view object re-pointed, "
                + "0 self-relationships dropped)";
        assertEquals(expected, MergeElementsOperations.describe(result.entity()));
        assertEquals(List.of(expected), dispatcher.getBatchStatus(SESSION).queuedDescriptions());
        assertEquals("the preview names what the merge would do", Map.of("s", "t"), result.entity().idMap());
        MergeElementsCommandTest.assertSnapshot("nothing is written until commit", before, snapshot());
        assertTrue(dispatched.isEmpty());
    }

    @Test
    public void shouldRefuseEachCallerErrorInsideABatch_exactlyAsOutsideIt_andQueueNothing() {
        List<Supplier<MutationResult<MergeElementsResultDto>>> calls = List.of(
                () -> merge(new MergeClusterRequest("t", List.of())),
                () -> merge(cluster("t", "s", "s")),
                () -> merge(cluster("t", "ghost")),
                () -> merge(cluster("t", "rel-1")),
                () -> merge(cluster("t", "role")),
                () -> merge(cluster("and-1", "or-1")),
                () -> accessor.mergeElements(SESSION, List.of(cluster("t", "s")), "bogus", null, false, false));
        List<ModelAccessException> outside = new ArrayList<>();
        for (Supplier<MutationResult<MergeElementsResultDto>> call : calls) {
            outside.add(refusal(call::get));
        }
        dispatcher.beginBatch(SESSION, "cleanup");
        Map<String, String> before = snapshot();
        for (int i = 0; i < calls.size(); i++) {
            ModelAccessException inside = refusal(calls.get(i)::get);
            assertEquals(outside.get(i).getMessage(), outside.get(i).getErrorCode(), inside.getErrorCode());
            assertEquals(outside.get(i).getMessage(), inside.getMessage());
            assertEquals(outside.get(i).getSuggestedCorrection(), inside.getSuggestedCorrection());
        }
        assertEquals(0, (int) dispatcher.getBatchStatus(SESSION).queuedCount());
        MergeElementsCommandTest.assertSnapshot("refused means unchanged", before, snapshot());
    }

    // ---- commit -------------------------------------------------------------------------------

    /**
     * The relationship is created on the duplicate earlier in the same batch, and the duplicate is
     * documented there too: neither exists when the merge is prepared, so only a merge decided at
     * commit can move the one and carry the other.
     */
    @Test
    public void shouldMoveWhatEarlierQueuedOperationsMade_inOneUndoStep_andUndoAndRedoExactly() throws Exception {
        Map<String, String> before = snapshot();
        dispatcher.beginBatch(SESSION, "cleanup");
        String created = queueRelationship("s", "v");
        accessor.updateElement(SESSION, "s", "Customer (old)", "Kept from the duplicate", null, null);
        merge(cluster("t", "s"));

        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertNull("nothing skipped: " + summary.skippedOperations(), summary.skippedOperations());
        IArchimateRelationship moved = (IArchimateRelationship) ArchimateModelUtils.getObjectByID(model, created);
        assertNotNull("the relationship keeps its id", moved);
        assertSame("the relationship made earlier in the batch now starts at the target", element("t"),
                moved.getSource());
        assertTrue("the documentation written earlier in the batch reached the target: "
                + element("t").getDocumentation(), element("t").getDocumentation().contains("Kept from the duplicate"));
        assertNull("the duplicate is gone", ArchimateModelUtils.getObjectByID(model, "s"));
        assertEquals("one stack entry for the whole batch", 1, dispatched.size());
        Map<String, String> committed = snapshot();

        stack.undo();
        MergeElementsCommandTest.assertSnapshot("one undo restores the model before the batch", before, snapshot());
        stack.redo();
        MergeElementsCommandTest.assertSnapshot("redo replays the committed batch", committed, snapshot());
    }

    @Test
    public void shouldDeclineTheMerge_andCommitTheRest_whenAnEarlierOperationDeletesADuplicate() throws Exception {
        dispatcher.beginBatch(SESSION, "cleanup");
        accessor.deleteElement(SESSION, "s");
        MutationResult<MergeElementsResultDto> queued = merge(cluster("t", "s"));

        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertNotNull(summary.skippedOperations());
        assertEquals(summary.skippedOperations().toString(), 1, summary.skippedOperations().size());
        assertEquals(MergeElementsOperations.describe(queued.entity())
                + " — merge-elements wrote nothing: Element not found: s (merges[0].sourceIds[0])",
                summary.skippedOperations().get(0));
        assertNull("the delete committed", ArchimateModelUtils.getObjectByID(model, "s"));
        assertNotNull("the merge wrote nothing", ArchimateModelUtils.getObjectByID(model, "s2"));
        assertEquals(OperationalMode.GUI_ATTACHED, dispatcher.getMode(SESSION));
    }

    // ---- an id only the batch creates ---------------------------------------------------------

    @Test
    public void shouldRefuseATargetOrDuplicateThatOnlyTheBatchCreates_andLeaveTheBatchOpen() {
        dispatcher.beginBatch(SESSION, "cleanup");
        String fresh = accessor.createElement(SESSION, "BusinessActor", "Customer", null, null, null, null)
                .entity().id();

        ModelAccessException asDuplicate = refusal(() -> merge(cluster("t", fresh)));
        assertEquals(ErrorCode.INVALID_PARAMETER, asDuplicate.getErrorCode());
        assertEquals(fresh + " (merges[0].sourceIds[0]) is created by an operation queued in this batch; "
                + "merge-elements merges elements that exist before the batch commits", asDuplicate.getMessage());
        assertEquals("Call end-batch first, then merge-elements; or create the element outside the batch.",
                asDuplicate.getSuggestedCorrection());
        ModelAccessException asTarget = refusal(() -> merge(cluster(fresh, "s")));
        assertEquals(fresh + " (merges[0].targetId) is created by an operation queued in this batch; "
                + "merge-elements merges elements that exist before the batch commits", asTarget.getMessage());

        assertEquals(1, (int) dispatcher.getBatchStatus(SESSION).queuedCount());
        accessor.updateElement(SESSION, "u", "Billing 2", null, null, null);
        assertEquals("not closed: the refused merge was never queued", 2,
                (int) dispatcher.getBatchStatus(SESSION).queuedCount());
    }

    // ---- the merge closes the batch -----------------------------------------------------------

    @Test
    public void shouldRefuseEveryOtherToolAfterAQueuedMerge_namingIt_andKeepTheBatchUsable() throws Exception {
        dispatcher.beginBatch(SESSION, "cleanup");
        accessor.updateElement(SESSION, "v", "Billing (dup)", null, null, null);
        merge(cluster("t", "s"));
        Map<String, String> before = snapshot();

        assertClosedTo("update-element", 2, () -> accessor.updateElement(SESSION, "u", "Billing 2", null, null, null));
        assertClosedTo("delete-element", 2, () -> accessor.deleteElement(SESSION, "u"));
        assertClosedTo("add-to-view", 2, () -> accessor.addToView(SESSION, "view-1", "v", 600, 0, 120, 55, false,
                null, null, null));
        assertClosedTo("bulk-mutate", 2, () -> accessor.executeBulk(SESSION, List.of(
                new BulkOperation("update-element", Map.of("id", "u", "name", "Billing 3"))), null, false));
        assertClosedTo("auto-route-connections", 2, () -> accessor.autoRouteConnections(SESSION, "view-1", null,
                "orthogonal", true, false, 0, 0, null));

        assertTrue("a read still answers", accessor.getElementById("u").isPresent());
        assertEquals(2, (int) dispatcher.getBatchStatus(SESSION).queuedCount());
        MergeElementsCommandTest.assertSnapshot("refused means unchanged", before, snapshot());

        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);
        assertNull(summary.skippedOperations());
        assertNull("the merge committed", ArchimateModelUtils.getObjectByID(model, "s"));
        assertEquals("Billing (dup)", element("v").getName());
    }

    /** The rule lives in the queue every command passes through, so it holds for any command. */
    @Test
    public void shouldRefuseAnyCommandQueuedAfterAMerge_notOnlyANamedTool() {
        dispatcher.beginBatch(SESSION, "cleanup");
        merge(cluster("t", "s"));

        ModelAccessException e = refusal(() -> dispatcher.queueForBatch(SESSION, new Command() { }, "stub"));

        assertTrue(e instanceof BatchClosingCommand.Refusal);
        assertEquals(ErrorCode.BATCH_ALREADY_ACTIVE, e.getErrorCode());
        assertEquals("This operation cannot be queued after merge-elements (operation #1) in this batch: it was "
                + "prepared before the merge runs, and the merge deletes or re-points what it may name. Nothing "
                + "was queued.", e.getMessage());
        assertEquals("Call end-batch to commit the merge (or with rollback: true to discard the queue), then call "
                + "it again. Queue merges last in a batch.", e.getSuggestedCorrection());
        assertEquals(1, (int) dispatcher.getBatchStatus(SESSION).queuedCount());

        ModelAccessException relationship = refusal(() -> queueRelationship("u", "v"));
        assertTrue("create-relationship's command too: " + relationship.getMessage(),
                relationship instanceof BatchClosingCommand.Refusal);
        assertEquals(1, (int) dispatcher.getBatchStatus(SESSION).queuedCount());
    }

    @Test
    public void shouldRefuseAnApprovedCardOfAnotherTool_afterAQueuedMerge_andKeepTheCard() {
        approval(true);
        String proposalId = accessor.updateElement(SESSION, "u", "Billing 2", null, null, null)
                .proposalContext().proposalId();
        approval(false);
        dispatcher.beginBatch(SESSION, "cleanup");
        merge(cluster("t", "s"));

        try {
            dispatcher.approveProposal(SESSION, proposalId);
            fail("an approval queued after a merge must be refused");
        } catch (MutationException e) {
            assertEquals("update-element cannot be queued after merge-elements (operation #1) in this batch: it "
                    + "was prepared before the merge runs, and the merge deletes or re-points what it may name. "
                    + "Nothing was queued. Ask the agent to end its batch, then approve; or reject this proposal.",
                    e.getMessage());
        }
        assertNotNull("the card is kept", dispatcher.getProposal(SESSION, proposalId));
        assertEquals(1, (int) dispatcher.getBatchStatus(SESSION).queuedCount());
        assertEquals("Billing", element("u").getName());
    }

    // ---- a second merge -----------------------------------------------------------------------

    @Test
    public void shouldQueueASecondMergeOnOtherIds_andCommitBothInOneStep() throws Exception {
        dispatcher.beginBatch(SESSION, "cleanup");
        merge(cluster("t", "s"));
        MutationResult<MergeElementsResultDto> second = merge(cluster("u", "v"));
        MutationResult<MergeElementsResultDto> third = merge(cluster("t", "s2"));
        assertEquals(Integer.valueOf(2), second.batchSequenceNumber());
        assertEquals("naming the earlier merge's target is allowed", Integer.valueOf(3), third.batchSequenceNumber());

        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertNull(summary.skippedOperations());
        for (String gone : List.of("s", "v", "s2")) {
            assertNull(gone, ArchimateModelUtils.getObjectByID(model, gone));
        }
        IArchimateRelationship rel = (IArchimateRelationship) ArchimateModelUtils.getObjectByID(model, "rel-1");
        assertSame(element("t"), rel.getSource());
        assertSame(element("u"), rel.getTarget());
        assertEquals(1, dispatched.size());
    }

    @Test
    public void shouldRefuseALaterMergeNamingAnEarlierMergesDuplicate_asTargetOrDuplicate() {
        dispatcher.beginBatch(SESSION, "cleanup");
        merge(cluster("t", "s"));

        ModelAccessException asDuplicate = refusal(() -> merge(cluster("u", "v"), cluster("t", "s")));
        assertEquals(ErrorCode.INVALID_PARAMETER, asDuplicate.getErrorCode());
        assertEquals("s (merges[1].sourceIds[0]) is a duplicate that merge-elements (operation #1) in this batch "
                + "merges into t and deletes; a later merge cannot name it. Nothing was queued.",
                asDuplicate.getMessage());
        assertEquals("Name t instead, or call end-batch first, then merge-elements.",
                asDuplicate.getSuggestedCorrection());
        ModelAccessException asTarget = refusal(() -> merge(cluster("s", "s2")));
        assertEquals("s (merges[0].targetId) is a duplicate that merge-elements (operation #1) in this batch "
                + "merges into t and deletes; a later merge cannot name it. Nothing was queued.",
                asTarget.getMessage());
        assertEquals(1, (int) dispatcher.getBatchStatus(SESSION).queuedCount());
    }

    // ---- approve into a batch -----------------------------------------------------------------

    @Test
    public void shouldQueueAMergeCardApprovedInsideABatch_andCommitItLikeACall() throws Exception {
        approval(true);
        String proposalId = merge(cluster("t", "s")).proposalContext().proposalId();
        approval(false);
        dispatcher.beginBatch(SESSION, "cleanup");

        ApprovalResult approved = dispatcher.approveProposal(SESSION, proposalId);

        assertEquals(Integer.valueOf(1), approved.batchSequenceNumber());
        assertEquals(1, (int) dispatcher.getBatchStatus(SESSION).queuedCount());
        assertNotNull("queued, not run", ArchimateModelUtils.getObjectByID(model, "s"));
        assertNull(dispatcher.endBatch(SESSION, true).skippedOperations());
        assertNull(ArchimateModelUtils.getObjectByID(model, "s"));
        assertSame(element("t"), ((IArchimateRelationship) ArchimateModelUtils.getObjectByID(model, "rel-1"))
                .getSource());
    }

    /**
     * A relationship queued between the duplicate and the target before the approval becomes a
     * self-relationship the merge would drop at commit, which the card never listed.
     */
    @Test
    public void shouldDeclineAnApprovedMergeAtCommit_thatWouldDeleteWhatTheCardDidNotList() throws Exception {
        approval(true);
        String proposalId = merge(cluster("t", "s")).proposalContext().proposalId();
        approval(false);
        dispatcher.beginBatch(SESSION, "cleanup");
        String self = queueRelationship("s", "t");
        dispatcher.approveProposal(SESSION, proposalId);

        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertNotNull(summary.skippedOperations());
        assertEquals(1, summary.skippedOperations().size());
        String skipped = summary.skippedOperations().get(0);
        assertTrue(skipped, skipped.contains("merge-elements wrote nothing: it would now also delete 1 "
                + "relationship(s) or view connection(s) the proposal did not list ('" + self + "')"));
        assertNotNull("the relationship queued before it committed", ArchimateModelUtils.getObjectByID(model, self));
        assertNotNull("the merge wrote nothing", ArchimateModelUtils.getObjectByID(model, "s"));
    }

    @Test
    public void shouldRefuseAMergeCardThatCollidesWithAQueuedMerge_andKeepTheCard() {
        approval(true);
        String proposalId = merge(cluster("u", "s")).proposalContext().proposalId();
        approval(false);
        dispatcher.beginBatch(SESSION, "cleanup");
        merge(cluster("t", "s"));

        try {
            dispatcher.approveProposal(SESSION, proposalId);
            fail("a card naming a queued merge's duplicate must be refused");
        } catch (MutationException e) {
            assertEquals("s (merges[0].sourceIds[0]) is a duplicate that merge-elements (operation #1) in this "
                    + "batch merges into t and deletes; a later merge cannot name it. Nothing was queued. Reject "
                    + "this proposal: the merge already queued in the agent's batch deletes that element when the "
                    + "batch commits, so this proposal cannot be approved as it stands.", e.getMessage());
        }
        assertNotNull("the card is kept", dispatcher.getProposal(SESSION, proposalId));
        assertEquals(1, (int) dispatcher.getBatchStatus(SESSION).queuedCount());
    }

    @Test
    public void shouldRefuseAnotherToolsProposal_whenTheBatchIsClosed_andStoreNothing() {
        dispatcher.beginBatch(SESSION, "cleanup");
        merge(cluster("t", "s"));
        approval(true);

        assertClosedTo("update-element",
                1, () -> accessor.updateElement(SESSION, "u", "Billing 2", null, null, null));
        assertTrue("no card for the human to meet the refusal on", dispatcher.getPendingProposals(SESSION).isEmpty());
    }

    @Test
    public void shouldStoreAMergeProposal_whenTheBatchIsClosed() {
        dispatcher.beginBatch(SESSION, "cleanup");
        merge(cluster("t", "s"));
        approval(true);

        String proposalId = merge(cluster("u", "v")).proposalContext().proposalId();

        assertNotNull(dispatcher.getProposal(SESSION, proposalId));
    }

    // ---- rollback -----------------------------------------------------------------------------

    @Test
    public void shouldOpenTheNextBatchToEveryOperation_afterAMergeIsRolledBack() throws Exception {
        Map<String, String> before = snapshot();
        dispatcher.beginBatch(SESSION, "cleanup");
        merge(cluster("t", "s"));

        BatchSummaryDto rolledBack = dispatcher.endBatch(SESSION, false);

        assertTrue(rolledBack.rolledBack());
        MergeElementsCommandTest.assertSnapshot("nothing ran", before, snapshot());
        dispatcher.beginBatch(SESSION, "again");
        accessor.updateElement(SESSION, "u", "Billing 2", null, null, null);
        assertEquals(1, (int) dispatcher.getBatchStatus(SESSION).queuedCount());
    }

    @Test
    public void shouldForgetTheClosingCommand_whenTheContextIsReset() {
        MutationContext context = new MutationContext();
        context.beginBatch("cleanup");
        context.queueCommand(new MergeElementsCommand(model, List.of(cluster("t", "s")),
                MergeElementsOperations.options(null, null, false, false)), "merge");
        assertNotNull(context.refuseQueuing(new Command() { }, null));

        context.reset();
        context.beginBatch("again");

        assertNull(context.refuseQueuing(new Command() { }, null));
    }

    // ---- helpers ------------------------------------------------------------------------------

    /**
     * Queues the command {@code create-relationship} queues. The tool itself cannot be prepared in
     * this lane: its relationship rule loads Archi's matrix from the running platform.
     */
    private String queueRelationship(String sourceId, String targetId) {
        IArchimateRelationship relationship = factory.createAssociationRelationship();
        dispatcher.queueForBatch(SESSION, new CreateRelationshipCommand(relationship,
                model.getFolder(FolderType.RELATIONS), element(sourceId), element(targetId)),
                "Create AssociationRelationship");
        return relationship.getId();
    }

    /**
     * The batch commits as Archi's non-notifying compound, which needs the running editor; its
     * children run in the same order inside a plain one.
     */
    private static Command toPlainCompound(Command command) {
        if (command instanceof CompoundCommand compound) {
            CompoundCommand plain = new CompoundCommand(compound.getLabel());
            for (Object child : compound.getCommands()) {
                plain.add(toPlainCompound((Command) child));
            }
            return plain;
        }
        return command;
    }

    private void assertClosedTo(String tool, int mergeSeq, Runnable call) {
        ModelAccessException e = refusal(call);
        assertEquals(tool + ": " + e.getMessage(), ErrorCode.BATCH_ALREADY_ACTIVE, e.getErrorCode());
        assertEquals(tool + " cannot be queued after merge-elements (operation #" + mergeSeq + ") in this batch: "
                + "it was prepared before the merge runs, and the merge deletes or re-points what it may name. "
                + "Nothing was queued.", e.getMessage());
        assertEquals("Call end-batch to commit the merge (or with rollback: true to discard the queue), then call "
                + tool + ". Queue merges last in a batch.", e.getSuggestedCorrection());
        assertEquals(tool, mergeSeq, (int) dispatcher.getBatchStatus(SESSION).queuedCount());
    }

    private MutationResult<MergeElementsResultDto> merge(MergeClusterRequest... clusters) {
        return accessor.mergeElements(SESSION, Arrays.asList(clusters), null, null, false, false);
    }

    private void approval(boolean on) {
        dispatcher.setApprovalModeProvider(() -> on);
    }

    private Map<String, String> snapshot() {
        return MergeElementsCommandTest.snapshot(model, Set.of());
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

    private void junction(String id, String type) {
        IJunction junction = factory.createJunction();
        junction.setId(id);
        junction.setType(type);
        model.getFolder(FolderType.OTHER).getElements().add(junction);
    }

    private IDiagramModelArchimateObject box(IArchimateDiagramModel view, String id, String elementId, int x) {
        IDiagramModelArchimateObject box = factory.createDiagramModelArchimateObject();
        box.setId(id);
        box.setArchimateElement(element(elementId));
        box.setBounds(x, 0, 120, 55);
        view.getChildren().add(box);
        return box;
    }

    private IArchimateElement element(String id) {
        return (IArchimateElement) ArchimateModelUtils.getObjectByID(model, id);
    }

    private static MergeClusterRequest cluster(String targetId, String... sourceIds) {
        return new MergeClusterRequest(targetId, Arrays.asList(sourceIds));
    }
}
