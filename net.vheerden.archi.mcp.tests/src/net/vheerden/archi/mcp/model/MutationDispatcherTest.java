package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.eclipse.gef.commands.Command;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;

import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.response.dto.BatchStatusDto;
import net.vheerden.archi.mcp.response.dto.BatchSummaryDto;
import net.vheerden.archi.mcp.response.dto.PendingProposalView;
import net.vheerden.archi.mcp.response.dto.ProposalDto;

/**
 * Tests for {@link MutationDispatcher} batch management.
 *
 * <p>Uses a test subclass that overrides {@code dispatchCommand} to avoid
 * Display.syncExec + CommandStack dependencies. E2E dispatch is tested
 * separately with Archi runtime.</p>
 */
public class MutationDispatcherTest {

    private TestMutationDispatcher dispatcher;

    @Before
    public void setUp() {
        dispatcher = new TestMutationDispatcher();
    }

    // ---- beginBatch tests ----

    @Test
    public void shouldCreateBatchContext_whenBeginBatchCalled() {
        dispatcher.beginBatch("session-1", "Test batch");

        assertEquals(OperationalMode.BATCH, dispatcher.getMode("session-1"));
    }

    @Test
    public void shouldReturnBatchStatusWithTimestamp_whenBeginBatchCalled() {
        dispatcher.beginBatch("session-1", null);

        BatchStatusDto status = dispatcher.getBatchStatus("session-1");
        assertEquals("BATCH", status.mode());
        assertEquals(Integer.valueOf(0), status.queuedCount());
        assertNotNull(status.batchStarted());
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrow_whenBeginBatchCalledTwice() {
        dispatcher.beginBatch("session-1", null);
        dispatcher.beginBatch("session-1", null); // should throw
    }

    // ---- endBatch tests ----

    @Test
    public void shouldReturnCommitSummary_whenEndBatchWithCommit() {
        dispatcher.beginBatch("session-1", "Test batch");
        dispatcher.queueForBatch("session-1", new StubCommand("cmd-1"), "Create element A");
        dispatcher.queueForBatch("session-1", new StubCommand("cmd-2"), "Create element B");

        BatchSummaryDto summary = dispatcher.endBatch("session-1", true);

        assertEquals(2, summary.operationCount());
        assertEquals(2, summary.descriptions().size());
        assertEquals("Create element A", summary.descriptions().get(0));
        assertEquals("Create element B", summary.descriptions().get(1));
        assertNotNull(summary.duration());
        assertEquals(false, summary.rolledBack());
    }

    @Test
    public void shouldDispatchCompoundCommand_whenEndBatchWithCommit() {
        dispatcher.beginBatch("session-1", null);
        dispatcher.queueForBatch("session-1", new StubCommand("cmd-1"), "op 1");

        dispatcher.endBatch("session-1", true);

        assertEquals(1, dispatcher.dispatchedCommands.size());
    }

    @Test
    public void shouldNotDispatch_whenEndBatchWithEmptyQueue() {
        dispatcher.beginBatch("session-1", null);

        dispatcher.endBatch("session-1", true);

        assertEquals(0, dispatcher.dispatchedCommands.size());
    }

    @Test
    public void shouldResetToGuiAttached_whenEndBatchWithCommit() {
        dispatcher.beginBatch("session-1", null);
        dispatcher.queueForBatch("session-1", new StubCommand("cmd-1"), "op 1");

        dispatcher.endBatch("session-1", true);

        assertEquals(OperationalMode.GUI_ATTACHED, dispatcher.getMode("session-1"));
    }

    @Test
    public void shouldClearQueue_whenEndBatchWithRollback() {
        dispatcher.beginBatch("session-1", null);
        dispatcher.queueForBatch("session-1", new StubCommand("cmd-1"), "op 1");
        dispatcher.queueForBatch("session-1", new StubCommand("cmd-2"), "op 2");

        BatchSummaryDto summary = dispatcher.endBatch("session-1", false);

        assertEquals(2, summary.operationCount());
        assertTrue(summary.rolledBack());
        assertEquals(OperationalMode.GUI_ATTACHED, dispatcher.getMode("session-1"));
    }

    @Test
    public void shouldNotDispatch_whenEndBatchWithRollback() {
        dispatcher.beginBatch("session-1", null);
        dispatcher.queueForBatch("session-1", new StubCommand("cmd-1"), "op 1");

        dispatcher.endBatch("session-1", false);

        assertEquals(0, dispatcher.dispatchedCommands.size());
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrow_whenEndBatchWithoutActiveBatch() {
        dispatcher.endBatch("session-1", true); // should throw
    }

    // ---- batchSessions entry removed on endBatch when nothing pends ----

    @Test
    public void shouldRemoveBatchSession_whenEndBatchCommitAndNoProposalsPending() {
        dispatcher.beginBatch("session-1", "batch");
        dispatcher.queueForBatch("session-1", new StubCommand("c1"), "op 1");
        assertEquals(1, dispatcher.batchSessionCount());

        dispatcher.endBatch("session-1", true);

        assertEquals("empty context dropped from batchSessions", 0, dispatcher.batchSessionCount());
        assertEquals(OperationalMode.GUI_ATTACHED, dispatcher.getMode("session-1"));
    }

    @Test
    public void shouldRemoveBatchSession_whenEndBatchRollbackAndNoProposalsPending() {
        dispatcher.beginBatch("session-1", null);
        dispatcher.queueForBatch("session-1", new StubCommand("c1"), "op 1");
        assertEquals(1, dispatcher.batchSessionCount());

        dispatcher.endBatch("session-1", false);

        assertEquals("rollback also drops the empty context", 0, dispatcher.batchSessionCount());
    }

    @Test
    public void shouldKeepBatchSession_whenEndBatchAndProposalPending() {
        // CORRECTNESS TRAP: proposals live in the SAME MutationContext as the batch; the entry
        // must NOT be removed while a proposal pends, or the human's pending-approval queue is dropped.
        String id = dispatcher.storeProposal("session-1", proposal("create-element", "A", Instant.now()));
        dispatcher.beginBatch("session-1", "batch");
        assertEquals(1, dispatcher.batchSessionCount());

        dispatcher.endBatch("session-1", true);

        assertEquals("context kept while a proposal pends", 1, dispatcher.batchSessionCount());
        // The proposal survives and is still retrievable and approvable.
        assertNotNull(dispatcher.getProposal("session-1", id));
        ApprovalResult result = dispatcher.approveProposal("session-1", id);
        assertNotNull("pending proposal still approvable after endBatch", result);
        assertEquals(1, dispatcher.dispatchedCommands.size());
    }

    // ---- queueCommand tests ----

    @Test
    public void shouldIncrementSequenceCounter_whenQueueCommand() {
        dispatcher.beginBatch("session-1", null);

        int seq1 = dispatcher.queueForBatch("session-1", new StubCommand("cmd-1"), "op 1");
        int seq2 = dispatcher.queueForBatch("session-1", new StubCommand("cmd-2"), "op 2");
        int seq3 = dispatcher.queueForBatch("session-1", new StubCommand("cmd-3"), "op 3");

        assertEquals(1, seq1);
        assertEquals(2, seq2);
        assertEquals(3, seq3);
    }

    @Test(expected = IllegalStateException.class)
    public void shouldThrow_whenQueueCommandNotInBatch() {
        dispatcher.queueForBatch("session-1", new StubCommand("cmd-1"), "op 1"); // should throw
    }

    // ---- getMode tests ----

    @Test
    public void shouldReturnGuiAttached_whenNoSessionExists() {
        assertEquals(OperationalMode.GUI_ATTACHED, dispatcher.getMode("unknown-session"));
    }

    @Test
    public void shouldReturnBatch_whenInBatchMode() {
        dispatcher.beginBatch("session-1", null);

        assertEquals(OperationalMode.BATCH, dispatcher.getMode("session-1"));
    }

    // ---- getBatchStatus tests ----

    @Test
    public void shouldReturnGuiAttachedStatus_whenNoSessionExists() {
        BatchStatusDto status = dispatcher.getBatchStatus("unknown");

        assertEquals("GUI_ATTACHED", status.mode());
        assertEquals(null, status.queuedCount());
        assertEquals(null, status.queuedDescriptions());
        assertEquals(null, status.batchStarted());
    }

    @Test
    public void shouldReturnBatchStatus_whenInBatchMode() {
        dispatcher.beginBatch("session-1", null);
        dispatcher.queueForBatch("session-1", new StubCommand("cmd-1"), "Create element");

        BatchStatusDto status = dispatcher.getBatchStatus("session-1");

        assertEquals("BATCH", status.mode());
        assertEquals(Integer.valueOf(1), status.queuedCount());
        assertEquals(1, status.queuedDescriptions().size());
        assertEquals("Create element", status.queuedDescriptions().get(0));
        assertNotNull(status.batchStarted());
    }

    // ---- Multi-session isolation tests ----

    @Test
    public void shouldIsolateSessions_whenMultipleBatches() {
        dispatcher.beginBatch("session-A", "Batch A");
        dispatcher.queueForBatch("session-A", new StubCommand("cmd-A1"), "A op 1");

        // Session B is not in batch mode
        assertEquals(OperationalMode.GUI_ATTACHED, dispatcher.getMode("session-B"));

        // Start batch for session B
        dispatcher.beginBatch("session-B", "Batch B");
        dispatcher.queueForBatch("session-B", new StubCommand("cmd-B1"), "B op 1");
        dispatcher.queueForBatch("session-B", new StubCommand("cmd-B2"), "B op 2");

        // Session A still has 1 queued
        BatchStatusDto statusA = dispatcher.getBatchStatus("session-A");
        assertEquals(Integer.valueOf(1), statusA.queuedCount());

        // Session B has 2 queued
        BatchStatusDto statusB = dispatcher.getBatchStatus("session-B");
        assertEquals(Integer.valueOf(2), statusB.queuedCount());

        // End session A
        dispatcher.endBatch("session-A", true);
        assertEquals(OperationalMode.GUI_ATTACHED, dispatcher.getMode("session-A"));

        // Session B still in batch
        assertEquals(OperationalMode.BATCH, dispatcher.getMode("session-B"));
    }

    // ---- clearAllSessions tests ----

    @Test
    public void shouldClearAllSessions_whenClearCalled() {
        dispatcher.beginBatch("session-1", null);
        dispatcher.beginBatch("session-2", null);

        dispatcher.clearAllSessions();

        assertEquals(OperationalMode.GUI_ATTACHED, dispatcher.getMode("session-1"));
        assertEquals(OperationalMode.GUI_ATTACHED, dispatcher.getMode("session-2"));
    }

    // ---- Approval mode tests ----

    @Test
    public void shouldDefaultToGated_whenProviderNotOverridden() {
        // A fresh dispatcher fails safe — the gate is ON until wiring proves otherwise.
        assertTrue(dispatcher.isApprovalRequired("session-1"));
    }

    @Test
    public void shouldReadApprovalFromInjectedProvider() {
        dispatcher.setApprovalModeProvider(() -> false);
        assertFalse(dispatcher.isApprovalRequired("session-1"));

        dispatcher.setApprovalModeProvider(() -> true);
        assertTrue(dispatcher.isApprovalRequired("session-1"));
    }

    @Test
    public void shouldBeGlobal_notPerSession() {
        // One human, one gate: every session sees the same mode regardless of sessionId.
        dispatcher.setApprovalModeProvider(() -> true);
        assertTrue(dispatcher.isApprovalRequired("session-1"));
        assertTrue(dispatcher.isApprovalRequired("session-2"));
        assertTrue(dispatcher.isApprovalRequired("any-other-session"));
    }

    @Test
    public void shouldNotExposeApprovalSetterOnTheDispatcher() throws Exception {
        // There is no setApprovalRequired (the agent path to flip the gate is gone).
        for (var m : MutationDispatcher.class.getMethods()) {
            assertFalse("MutationDispatcher must not expose setApprovalRequired",
                    m.getName().equals("setApprovalRequired"));
        }
    }

    @Test
    public void shouldStoreAndRetrieveProposal() {
        PendingProposal proposal = new PendingProposal(
                null, "create-element", "Create BusinessActor: Test",
                new StubCommand("cmd"), "entity", null,
                Map.of("type", "BusinessActor"), "Valid", Instant.now());

        String id = dispatcher.storeProposal("session-1", proposal);

        assertNotNull(id);
        assertTrue(id.startsWith("p-"));
        PendingProposal stored = dispatcher.getProposal("session-1", id);
        assertNotNull(stored);
        assertEquals(id, stored.proposalId());
        assertEquals("create-element", stored.tool());
    }

    @Test
    public void shouldRemoveProposal() {
        PendingProposal proposal = new PendingProposal(
                null, "create-element", "Create element",
                new StubCommand("cmd"), "entity", null,
                Map.of("name", "X"), "Valid", Instant.now());
        String id = dispatcher.storeProposal("session-1", proposal);

        PendingProposal removed = dispatcher.removeProposal("session-1", id);

        assertNotNull(removed);
        assertEquals(id, removed.proposalId());
        assertNull(dispatcher.getProposal("session-1", id));
    }

    @Test
    public void shouldReturnNull_whenProposalNotFound() {
        assertNull(dispatcher.getProposal("session-1", "p-999"));
        assertNull(dispatcher.removeProposal("session-1", "p-999"));
    }

    @Test
    public void shouldListPendingProposals() {
        PendingProposal p1 = new PendingProposal(
                null, "create-element", "desc1",
                new StubCommand("c1"), "e1", null, Map.of(), "v", Instant.now());
        PendingProposal p2 = new PendingProposal(
                null, "create-relationship", "desc2",
                new StubCommand("c2"), "e2", null, Map.of(), "v", Instant.now());
        dispatcher.storeProposal("session-1", p1);
        dispatcher.storeProposal("session-1", p2);

        List<PendingProposal> pending = dispatcher.getPendingProposals("session-1");

        assertEquals(2, pending.size());
    }

    @Test
    public void shouldReturnEmptyList_whenNoPendingProposals() {
        List<PendingProposal> pending = dispatcher.getPendingProposals("session-1");

        assertTrue(pending.isEmpty());
    }

    @Test
    public void shouldReturnPendingProposalDtos() {
        PendingProposal proposal = new PendingProposal(
                null, "create-element", "Create Node",
                new StubCommand("cmd"), "entity", null,
                Map.of("type", "Node"), "Type valid", Instant.now());
        dispatcher.storeProposal("session-1", proposal);

        List<ProposalDto> dtos = dispatcher.getPendingProposalDtos("session-1");

        assertEquals(1, dtos.size());
        assertEquals("create-element", dtos.get(0).tool());
        assertEquals("pending", dtos.get(0).status());
        assertEquals("Create Node", dtos.get(0).description());
    }

    @Test
    public void shouldApproveProposal_andDispatchCommand() {
        PendingProposal proposal = new PendingProposal(
                null, "create-element", "Create Actor",
                new StubCommand("approve-cmd"), "entityDto", null,
                Map.of("type", "Actor"), "Valid", Instant.now());
        String id = dispatcher.storeProposal("session-1", proposal);

        ApprovalResult result = dispatcher.approveProposal("session-1", id);

        assertNotNull(result);
        assertEquals("entityDto", result.entity());
        assertEquals("create-element", result.tool());
        assertNull(result.batchSequenceNumber()); // GUI_ATTACHED → dispatched
        assertEquals(1, dispatcher.dispatchedCommands.size());
        // Proposal should be removed
        assertNull(dispatcher.getProposal("session-1", id));
    }

    @Test
    public void shouldApproveProposal_andQueueInBatch() {
        dispatcher.beginBatch("session-1", "Test batch");
        PendingProposal proposal = new PendingProposal(
                null, "create-element", "Create Actor",
                new StubCommand("batch-cmd"), "entityDto", null,
                Map.of(), "Valid", Instant.now());
        String id = dispatcher.storeProposal("session-1", proposal);

        ApprovalResult result = dispatcher.approveProposal("session-1", id);

        assertNotNull(result);
        assertNotNull(result.batchSequenceNumber());
        assertEquals(0, dispatcher.dispatchedCommands.size()); // queued, not dispatched
    }

    @Test
    public void shouldReturnNull_whenApprovingNonExistentProposal() {
        ApprovalResult result = dispatcher.approveProposal("session-1", "p-999");

        assertNull(result);
    }

    @Test
    public void shouldRejectProposal_andReturnDto() {
        PendingProposal proposal = new PendingProposal(
                null, "create-view", "Create view",
                new StubCommand("reject-cmd"), "viewDto", null,
                Map.of("name", "My View"), "Valid", Instant.now());
        String id = dispatcher.storeProposal("session-1", proposal);

        ProposalDto rejected = dispatcher.rejectProposal("session-1", id);

        assertNotNull(rejected);
        assertEquals("rejected", rejected.status());
        assertEquals("create-view", rejected.tool());
        assertEquals(0, dispatcher.dispatchedCommands.size()); // nothing dispatched
        assertNull(dispatcher.getProposal("session-1", id)); // removed
    }

    @Test
    public void shouldReturnNull_whenRejectingNonExistentProposal() {
        ProposalDto rejected = dispatcher.rejectProposal("session-1", "p-999");

        assertNull(rejected);
    }

    // ---- store-the-request approve path (reject-stale + TTL sweep) ----

    @Test
    public void shouldRejectStale_whenTargetRemovedBeforeApprove() {
        // A proposal targeting an object the human removed during review must reject-stale
        // (naming the object) and dispatch NOTHING — never run the would-be-frozen command.
        com.archimatetool.model.IArchimateModel model =
                com.archimatetool.model.IArchimateFactory.eINSTANCE.createArchimateModel();
        model.setDefaults();
        com.archimatetool.model.IBusinessActor actor =
                com.archimatetool.model.IArchimateFactory.eINSTANCE.createBusinessActor();
        actor.setName("Payment Gateway");
        model.getFolder(com.archimatetool.model.FolderType.BUSINESS).getElements().add(actor);

        List<Command> dispatched = new java.util.ArrayList<>();
        MutationDispatcher d = new MutationDispatcher(() -> model) {
            @Override
            protected void dispatchCommand(Command c) {
                dispatched.add(c);
            }
        };
        StalenessCapture cap = d.captureStaleness("s-1", java.util.Set.of(actor.getId()));
        PendingProposal p = new PendingProposal(null, "delete-element", "Delete actor",
                () -> new PreparedMutation<>(new StubCommand("del"), "e", actor.getId()),
                cap, "e", null, Map.of(), "v", Instant.now(), null, null);
        String id = d.storeProposal("s-1", p);

        // Human removes the targeted element before approving.
        model.getFolder(com.archimatetool.model.FolderType.BUSINESS).getElements().remove(actor);

        try {
            d.approveProposal("s-1", id);
            fail("expected a stale rejection");
        } catch (MutationException e) {
            assertTrue("reason names the removed target", e.getMessage().contains("Payment Gateway"));
        }
        assertEquals("nothing dispatched when stale", 0, dispatched.size());
        // A stale rejection leaves the proposal IN the queue, so the card stays on screen with its
        // named reason for the human to read and Reject — vet/rebuild happen before the proposal is removed.
        assertNotNull("stale proposal stays queued", d.getProposal("s-1", id));
    }

    @Test
    public void shouldApproveFresh_whenTargetUntouched_rebuildingCommand() {
        // An untouched target rebuilds fresh and dispatches the rebuilt command.
        com.archimatetool.model.IArchimateModel model =
                com.archimatetool.model.IArchimateFactory.eINSTANCE.createArchimateModel();
        model.setDefaults();
        com.archimatetool.model.IBusinessActor actor =
                com.archimatetool.model.IArchimateFactory.eINSTANCE.createBusinessActor();
        actor.setName("Payment Gateway");
        model.getFolder(com.archimatetool.model.FolderType.BUSINESS).getElements().add(actor);

        List<Command> dispatched = new java.util.ArrayList<>();
        MutationDispatcher d = new MutationDispatcher(() -> model) {
            @Override
            protected void dispatchCommand(Command c) {
                dispatched.add(c);
            }
        };
        StalenessCapture cap = d.captureStaleness("s-1", java.util.Set.of(actor.getId()));
        Command rebuilt = new StubCommand("update");
        PendingProposal p = new PendingProposal(null, "update-element", "Update actor",
                () -> new PreparedMutation<>(rebuilt, "freshEntity", actor.getId()),
                cap, "proposeEntity", null, Map.of(), "v", Instant.now(), null, null);
        String id = d.storeProposal("s-1", p);

        ApprovalResult result = d.approveProposal("s-1", id);

        assertEquals("the freshly rebuilt command is dispatched", 1, dispatched.size());
        assertEquals("the rebuilt command (not a frozen one) is what ran", rebuilt, dispatched.get(0));
        assertEquals("the fresh entity is returned", "freshEntity", result.entity());
        assertNull("a successful approve removes the proposal from the queue", d.getProposal("s-1", id));
    }

    @Test
    public void shouldSweepExpiredProposal_onListAllPending() {
        // A proposal abandoned past the TTL is swept from the live list (relieving the cap).
        dispatcher.storeProposal("s-1",
                proposal("create-element", "abandoned", Instant.now().minus(java.time.Duration.ofHours(1))));

        assertTrue("expired proposal swept from the live list", dispatcher.listAllPending().isEmpty());
    }

    @Test
    public void shouldFireQueueChanged_whenListAllPendingSweepsExpired() {
        // A TTL sweep triggered by listAllPending must notify queue listeners so the
        // dock repaints even when the sweep was driven by a non-dock caller (e.g. the agent's list call).
        dispatcher.storeProposal("s-1",
                proposal("create-element", "abandoned", Instant.now().minus(java.time.Duration.ofHours(1))));
        int[] fired = {0};
        dispatcher.addQueueListener(() -> fired[0]++);

        dispatcher.listAllPending(); // sweeps the expired one

        assertEquals("sweep on list fires queue-changed exactly once", 1, fired[0]);
    }

    @Test
    public void shouldNotFireQueueChanged_whenListAllPendingSweepsNothing() {
        // No fire when nothing was swept — the dock must not be churned on every read.
        dispatcher.storeProposal("s-1", proposal("create-element", "fresh", Instant.now()));
        int[] fired = {0};
        dispatcher.addQueueListener(() -> fired[0]++);

        dispatcher.listAllPending(); // nothing expired

        assertEquals("no sweep ⇒ no queue-changed fire", 0, fired[0]);
    }

    @Test
    public void shouldIncludeApprovalInfoInBatchStatus() {
        dispatcher.setApprovalModeProvider(() -> true);
        PendingProposal proposal = new PendingProposal(
                null, "create-element", "desc",
                new StubCommand("cmd"), "e", null, Map.of(), "v", Instant.now());
        dispatcher.storeProposal("session-1", proposal);

        BatchStatusDto status = dispatcher.getBatchStatus("session-1");

        // Approval flag now overlaid from the global provider; pending count stays per-session.
        assertEquals(Boolean.TRUE, status.approvalRequired());
        assertEquals(Integer.valueOf(1), status.pendingApprovalCount());
    }

    @Test
    public void shouldOmitApprovalFlagInBatchStatus_whenOff() {
        // Wire shape preserved: approvalRequired is omitted (null) when OFF, present
        // (TRUE) when ON — get-batch-status keeps its byte-for-byte legacy contract.
        dispatcher.setApprovalModeProvider(() -> false);
        PendingProposal proposal = new PendingProposal(
                null, "create-element", "desc",
                new StubCommand("cmd"), "e", null, Map.of(), "v", Instant.now());
        dispatcher.storeProposal("session-1", proposal);

        BatchStatusDto status = dispatcher.getBatchStatus("session-1");

        assertNull(status.approvalRequired());
        assertEquals(Integer.valueOf(1), status.pendingApprovalCount());
    }

    @Test
    public void shouldKeepGlobalApprovalBit_acrossBatchReset() {
        dispatcher.setApprovalModeProvider(() -> true);
        PendingProposal proposal = new PendingProposal(
                null, "create-element", "desc",
                new StubCommand("cmd"), "e", null, Map.of(), "v", Instant.now());
        dispatcher.storeProposal("session-1", proposal);

        // Start and end a batch
        dispatcher.beginBatch("session-1", "batch");
        dispatcher.endBatch("session-1", true);

        // The global bit is independent of batch lifecycle; proposals survive too.
        assertTrue(dispatcher.isApprovalRequired("session-1"));
        assertEquals(1, dispatcher.getPendingProposals("session-1").size());
    }

    @Test
    public void shouldNotClearGlobalApprovalBit_onClearAllSessions() {
        dispatcher.setApprovalModeProvider(() -> true);
        PendingProposal proposal = new PendingProposal(
                null, "create-element", "desc",
                new StubCommand("cmd"), "e", null, Map.of(), "v", Instant.now());
        dispatcher.storeProposal("session-1", proposal);

        dispatcher.clearAllSessions();

        // clearAllSessions drops per-session proposals, but the global gate is human-owned
        // and unaffected.
        assertTrue(dispatcher.isApprovalRequired("session-1"));
        assertTrue(dispatcher.getPendingProposals("session-1").isEmpty());
    }

    // ---- Immediate dispatch callback tests ----

    @Test
    public void shouldCallImmediateDispatchCallback_whenProposalApprovedImmediately() {
        int[] callCount = {0};
        dispatcher.setOnImmediateDispatchCallback(() -> callCount[0]++);

        PendingProposal proposal = new PendingProposal(
                null, "create-element", "Create Actor",
                new StubCommand("callback-cmd"), "entityDto", null,
                Map.of("type", "Actor"), "Valid", Instant.now());
        String id = dispatcher.storeProposal("session-1", proposal);

        dispatcher.approveProposal("session-1", id);

        assertEquals("Callback should have been invoked once", 1, callCount[0]);
    }

    @Test
    public void shouldNotCallImmediateDispatchCallback_whenProposalQueuedInBatch() {
        int[] callCount = {0};
        dispatcher.setOnImmediateDispatchCallback(() -> callCount[0]++);

        dispatcher.beginBatch("session-1", "Test batch");
        PendingProposal proposal = new PendingProposal(
                null, "create-element", "Create Actor",
                new StubCommand("batch-cmd"), "entityDto", null,
                Map.of(), "Valid", Instant.now());
        String id = dispatcher.storeProposal("session-1", proposal);

        dispatcher.approveProposal("session-1", id);

        assertEquals("Callback should NOT have been invoked (batch mode)", 0, callCount[0]);
    }

    // ---- Approval-queue listener fan-out + cross-session aggregation ----

    private static PendingProposal proposal(String tool, String name, Instant createdAt) {
        return new PendingProposal(
                null, tool, "Desc " + name,
                new StubCommand(tool), "entity", null,
                Map.of("name", name), "Valid", createdAt);
    }

    @Test
    public void shouldFireQueueListener_onStore() {
        int[] fired = {0};
        dispatcher.addQueueListener(() -> fired[0]++);

        dispatcher.storeProposal("s-1", proposal("create-element", "A", Instant.now()));

        assertEquals(1, fired[0]);
    }

    @Test
    public void shouldFireQueueListener_onRemove() {
        String id = dispatcher.storeProposal("s-1", proposal("create-element", "A", Instant.now()));
        int[] fired = {0};
        dispatcher.addQueueListener(() -> fired[0]++);

        dispatcher.removeProposal("s-1", id);

        assertEquals(1, fired[0]);
    }

    @Test
    public void shouldNotFireQueueListener_whenRemoveMisses() {
        int[] fired = {0};
        dispatcher.addQueueListener(() -> fired[0]++);

        PendingProposal removed = dispatcher.removeProposal("s-1", "p-999");

        assertNull(removed);
        assertEquals("A missed removal must not fire the queue listener", 0, fired[0]);
    }

    @Test
    public void shouldFireQueueListener_onApprove() {
        String id = dispatcher.storeProposal("s-1", proposal("create-element", "A", Instant.now()));
        int[] fired = {0};
        dispatcher.addQueueListener(() -> fired[0]++);

        dispatcher.approveProposal("s-1", id);

        assertEquals(1, fired[0]);
    }

    @Test
    public void shouldFireQueueListener_onReject() {
        String id = dispatcher.storeProposal("s-1", proposal("create-element", "A", Instant.now()));
        int[] fired = {0};
        dispatcher.addQueueListener(() -> fired[0]++);

        dispatcher.rejectProposal("s-1", id);

        assertEquals(1, fired[0]);
    }

    @Test
    public void shouldFireQueueListener_onClearAllSessions() {
        dispatcher.storeProposal("s-1", proposal("create-element", "A", Instant.now()));
        int[] fired = {0};
        dispatcher.addQueueListener(() -> fired[0]++);

        dispatcher.clearAllSessions();

        assertEquals(1, fired[0]);
    }

    @Test
    public void shouldStopFiring_afterListenerRemoved() {
        int[] fired = {0};
        ApprovalQueueListener listener = () -> fired[0]++;
        dispatcher.addQueueListener(listener);
        dispatcher.removeQueueListener(listener);

        dispatcher.storeProposal("s-1", proposal("create-element", "A", Instant.now()));

        assertEquals(0, fired[0]);
    }

    @Test
    public void shouldNotBreakDispatch_whenListenerThrows() {
        dispatcher.addQueueListener(() -> {
            throw new IllegalStateException("listener boom");
        });
        int[] good = {0};
        dispatcher.addQueueListener(() -> good[0]++);

        // A throwing listener must not propagate out of the store path.
        String id = dispatcher.storeProposal("s-1", proposal("create-element", "A", Instant.now()));

        assertNotNull(id);
        assertEquals("Well-behaved listeners still fire after a throwing one", 1, good[0]);
    }

    @Test
    public void shouldAggregateAllPending_acrossSessions_carryingSessionId() {
        // Timestamps must be within PROPOSAL_TTL (listAllPending sweeps older ones), so use
        // now-relative instants rather than fixed ancient dates.
        Instant base = Instant.now();
        dispatcher.storeProposal("s-a", proposal("create-element", "A", base.minusSeconds(120)));
        dispatcher.storeProposal("s-b", proposal("create-relationship", "B", base.minusSeconds(60)));

        List<PendingProposalView> all = dispatcher.listAllPending();

        assertEquals(2, all.size());
        // Each entry carries its routing session id so Approve/Reject can target the right session.
        assertTrue(all.stream().anyMatch(v -> v.sessionId().equals("s-a")));
        assertTrue(all.stream().anyMatch(v -> v.sessionId().equals("s-b")));
    }

    @Test
    public void shouldOrderListAllPending_byCreatedAtAscending() {
        // Stored newest-first; aggregation must return oldest-first regardless of session/insert order.
        // Keep both within PROPOSAL_TTL so neither is swept on list.
        Instant base = Instant.now();
        Instant newer = base.minusSeconds(30);
        Instant older = base.minusSeconds(300);
        dispatcher.storeProposal("s-a", proposal("create-element", "newer", newer));
        dispatcher.storeProposal("s-b", proposal("create-element", "older", older));

        List<PendingProposalView> all = dispatcher.listAllPending();

        assertEquals(older.toString(), all.get(0).proposal().createdAt());
        assertEquals("s-b", all.get(0).sessionId());
        assertEquals(newer.toString(), all.get(1).proposal().createdAt());
    }

    @Test
    public void shouldReturnEmptyList_whenNoSessionsPending() {
        assertTrue(dispatcher.listAllPending().isEmpty());
    }

    // ---- silent measurement window ----
    // Guards the content-change version bump during a known net-zero
    // measurement (the route-normalized baseline probe): a call that applies
    // nothing must not advance the model-changed signal.

    @Test
    public void shouldNotBeSilent_byDefault() {
        assertFalse(dispatcher.isSilentMeasurementActive());
    }

    @Test
    public void shouldBeSilent_whileWindowOpen() {
        dispatcher.beginSilentMeasurement();
        assertTrue(dispatcher.isSilentMeasurementActive());
        dispatcher.endSilentMeasurement();
        assertFalse(dispatcher.isSilentMeasurementActive());
    }

    @Test
    public void shouldStaySilentUntilOutermostClose_whenWindowsNest() {
        // Re-entrant: the composer runs the probe once per arm, so nested/
        // repeated windows must not close early.
        dispatcher.beginSilentMeasurement();
        dispatcher.beginSilentMeasurement();
        dispatcher.endSilentMeasurement();
        assertTrue("inner close must not end the window",
                dispatcher.isSilentMeasurementActive());
        dispatcher.endSilentMeasurement();
        assertFalse("outermost close ends the window",
                dispatcher.isSilentMeasurementActive());
    }

    @Test
    public void shouldNotUnderflow_whenEndCalledWithoutBegin() {
        dispatcher.endSilentMeasurement();
        assertFalse(dispatcher.isSilentMeasurementActive());
        // A subsequent well-formed window still works (depth clamped at 0).
        dispatcher.beginSilentMeasurement();
        assertTrue(dispatcher.isSilentMeasurementActive());
        dispatcher.endSilentMeasurement();
        assertFalse(dispatcher.isSilentMeasurementActive());
    }

    @Test
    public void shouldBeThreadScoped_soAnotherThreadNeverSeesAnOpenWindow()
            throws InterruptedException {
        // A window opened on this thread must NOT suppress a real mutation's
        // version bump performed concurrently on a different thread.
        dispatcher.beginSilentMeasurement();
        try {
            final boolean[] otherThreadSawActive = { true };
            Thread other = new Thread(() ->
                    otherThreadSawActive[0] = dispatcher.isSilentMeasurementActive());
            other.start();
            other.join();
            assertFalse("window must be invisible to other threads",
                    otherThreadSawActive[0]);
            assertTrue("window still open on the opening thread",
                    dispatcher.isSilentMeasurementActive());
        } finally {
            dispatcher.endSilentMeasurement();
        }
    }

    // ---- Test helpers ----

    // ---- queuedParentContainer: the batch-mode gate over the queue lookup ----

    /** The guard is mechanical: no batch open on this session means no queued container, ever. */
    @Test
    public void shouldReturnNullQueuedParent_whenSessionIsNotInBatchMode() {
        assertEquals("pre-condition", OperationalMode.GUI_ATTACHED, dispatcher.getMode("session-1"));

        assertNull("no context at all", dispatcher.queuedParentContainer("session-1", "grp-1"));
        assertNull("null id", dispatcher.queuedParentContainer("session-1", null));
    }

    /** Inside a batch, a queued group resolves; an id that names nothing queued still returns null. */
    @Test
    public void shouldResolveQueuedParent_whenSessionIsInBatchMode() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IArchimateDiagramModel view = f.createArchimateDiagramModel();
        IDiagramModelGroup group = f.createDiagramModelGroup();
        group.setId("grp-1");
        group.setBounds(0, 0, 100, 100);

        dispatcher.beginBatch("session-1", "batch");
        dispatcher.queueForBatch("session-1", new AddGroupToViewCommand(group, view), "add group");

        assertEquals("the queued group resolves inside the batch",
                group, dispatcher.queuedParentContainer("session-1", "grp-1"));
        assertNull("an unknown id resolves to nothing",
                dispatcher.queuedParentContainer("session-1", "grp-nope"));
        assertNull("another session sees nothing of this batch",
                dispatcher.queuedParentContainer("session-2", "grp-1"));
    }

    /** Rollback clears the queue, so the group stops being addressable. */
    @Test
    public void shouldStopResolvingQueuedParent_whenBatchIsRolledBack() throws Exception {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IArchimateDiagramModel view = f.createArchimateDiagramModel();
        IDiagramModelGroup group = f.createDiagramModelGroup();
        group.setId("grp-1");
        group.setBounds(0, 0, 100, 100);

        dispatcher.beginBatch("session-1", "batch");
        dispatcher.queueForBatch("session-1", new AddGroupToViewCommand(group, view), "add group");
        dispatcher.endBatch("session-1", false);

        assertNull("a rolled-back group must not stay addressable",
                dispatcher.queuedParentContainer("session-1", "grp-1"));
    }

    // ---- queuedViewObject: the same gate over the update-target lookup ----

    /** Same mechanical guard: no batch open on this session means no queued target, ever. */
    @Test
    public void shouldReturnNullQueuedViewObject_whenSessionIsNotInBatchMode() {
        assertEquals("pre-condition", OperationalMode.GUI_ATTACHED, dispatcher.getMode("session-1"));

        assertNull("no context at all", dispatcher.queuedViewObject("session-1", "note-1"));
        assertNull("null id", dispatcher.queuedViewObject("session-1", null));
    }

    /**
     * Inside a batch a queued object resolves along with its destined parent, and the widening is
     * asymmetric on purpose: a note is an update target but never a nesting parent.
     */
    @Test
    public void shouldResolveQueuedViewObject_whenSessionIsInBatchMode() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IArchimateDiagramModel view = f.createArchimateDiagramModel();
        IDiagramModelNote note = f.createDiagramModelNote();
        note.setId("note-1");
        note.setBounds(0, 0, 100, 100);

        dispatcher.beginBatch("session-1", "batch");
        dispatcher.queueForBatch("session-1", new AddNoteToViewCommand(note, view), "add note");

        assertEquals("the queued note resolves as an update target",
                note, dispatcher.queuedViewObject("session-1", "note-1").object());
        assertEquals("its destined parent comes back with it",
                view, dispatcher.queuedViewObject("session-1", "note-1").parent());
        assertNull("but it is never a nesting parent",
                dispatcher.queuedParentContainer("session-1", "note-1"));
        assertNull("an unknown id resolves to nothing",
                dispatcher.queuedViewObject("session-1", "note-nope"));
        assertNull("another session sees nothing of this batch",
                dispatcher.queuedViewObject("session-2", "note-1"));
    }

    /** Rollback clears the queue, so the object stops being addressable as a target too. */
    @Test
    public void shouldStopResolvingQueuedViewObject_whenBatchIsRolledBack() throws Exception {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IArchimateDiagramModel view = f.createArchimateDiagramModel();
        IDiagramModelNote note = f.createDiagramModelNote();
        note.setId("note-1");
        note.setBounds(0, 0, 100, 100);

        dispatcher.beginBatch("session-1", "batch");
        dispatcher.queueForBatch("session-1", new AddNoteToViewCommand(note, view), "add note");
        dispatcher.endBatch("session-1", false);

        assertNull("a rolled-back object must not stay addressable",
                dispatcher.queuedViewObject("session-1", "note-1"));
    }

    // ---- queuedParents: the same gate over the whole-queue containment ----

    /** Same mechanical guard: outside a batch there is no queue, so there is no containment. */
    @Test
    public void shouldReturnNullQueuedParents_whenSessionIsNotInBatchMode() {
        assertEquals("pre-condition", OperationalMode.GUI_ATTACHED, dispatcher.getMode("session-1"));

        assertNull("no context at all", dispatcher.queuedParents("session-1"));
    }

    /**
     * Inside a batch the whole queue's containment resolves at once, and stays confined to the
     * session that queued it.
     */
    @Test
    public void shouldResolveQueuedParents_whenSessionIsInBatchMode() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IArchimateDiagramModel view = f.createArchimateDiagramModel();
        IDiagramModelGroup group = f.createDiagramModelGroup();
        group.setId("grp-1");
        group.setBounds(0, 0, 100, 100);

        dispatcher.beginBatch("session-1", "batch");
        dispatcher.queueForBatch("session-1", new AddGroupToViewCommand(group, view), "add group");

        assertEquals("the queued group's destined parent resolves",
                view, dispatcher.queuedParents("session-1").get("grp-1"));
        assertNull("an unknown id maps to nothing",
                dispatcher.queuedParents("session-1").get("grp-nope"));
        assertNull("another session sees nothing of this batch",
                dispatcher.queuedParents("session-2"));
    }

    /** Rollback clears the queue, so the derived containment goes with it. */
    @Test
    public void shouldStopResolvingQueuedParents_whenBatchIsRolledBack() throws Exception {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IArchimateDiagramModel view = f.createArchimateDiagramModel();
        IDiagramModelGroup group = f.createDiagramModelGroup();
        group.setId("grp-1");
        group.setBounds(0, 0, 100, 100);

        dispatcher.beginBatch("session-1", "batch");
        dispatcher.queueForBatch("session-1", new AddGroupToViewCommand(group, view), "add group");
        dispatcher.endBatch("session-1", false);

        assertNull("a rolled-back batch derives no containment",
                dispatcher.queuedParents("session-1"));
    }

    // ---- queuedBounds: the same gate over the whole-queue geometry ----

    /** Same mechanical guard: outside a batch there is no queue, so there is no geometry. */
    @Test
    public void shouldReturnNullQueuedBounds_whenSessionIsNotInBatchMode() {
        assertEquals("pre-condition", OperationalMode.GUI_ATTACHED, dispatcher.getMode("session-1"));

        assertNull("no context at all", dispatcher.queuedBounds("session-1"));
    }

    /**
     * Inside a batch the whole queue's geometry resolves at once, and stays confined to the session
     * that queued it.
     */
    @Test
    public void shouldResolveQueuedBounds_whenSessionIsInBatchMode() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IDiagramModelGroup group = f.createDiagramModelGroup();
        group.setId("grp-1");
        group.setBounds(0, 0, 100, 100);

        dispatcher.beginBatch("session-1", "batch");
        dispatcher.queueForBatch("session-1",
                new UpdateViewObjectCommand(group, 5, 5, 800, 800), "resize group");

        assertArrayEquals("the queued write resolves",
                new int[] { 5, 5, 800, 800 }, dispatcher.queuedBounds("session-1").get("grp-1"));
        assertNull("an unknown id maps to nothing",
                dispatcher.queuedBounds("session-1").get("grp-nope"));
        assertNull("another session sees nothing of this batch",
                dispatcher.queuedBounds("session-2"));
    }

    /**
     * The view a queued {@code create-view} will make resolves for the session that queued it, and
     * for no other — the same session isolation its sibling lookups keep.
     */
    @Test
    public void shouldResolveQueuedCreatedView_whenSessionIsInBatchMode() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IArchimateModel m = f.createArchimateModel();
        m.setDefaults();
        IArchimateDiagramModel view = f.createArchimateDiagramModel();
        view.setId("view-created-1");

        dispatcher.beginBatch("session-1", "batch");
        dispatcher.queueForBatch("session-1",
                new CreateViewCommand(view, m.getFolder(FolderType.DIAGRAMS)), "create view");

        assertSame("the queued view resolves",
                view, dispatcher.queuedCreatedView("session-1", "view-created-1"));
        assertNull("an unknown id resolves to nothing",
                dispatcher.queuedCreatedView("session-1", "view-nope"));
        assertNull("another session sees nothing of this batch",
                dispatcher.queuedCreatedView("session-2", "view-created-1"));
    }

    /** Rollback clears the queue, so a created view stops being addressable with it. */
    @Test
    public void shouldStopResolvingQueuedCreatedView_whenBatchIsRolledBack() throws Exception {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IArchimateModel m = f.createArchimateModel();
        m.setDefaults();
        IArchimateDiagramModel view = f.createArchimateDiagramModel();
        view.setId("view-created-2");

        dispatcher.beginBatch("session-1", "batch");
        dispatcher.queueForBatch("session-1",
                new CreateViewCommand(view, m.getFolder(FolderType.DIAGRAMS)), "create view");
        assertNotNull("pre-condition: resolvable while queued",
                dispatcher.queuedCreatedView("session-1", "view-created-2"));

        dispatcher.endBatch("session-1", false);

        assertNull("a rolled-back create leaves nothing addressable",
                dispatcher.queuedCreatedView("session-1", "view-created-2"));
    }

    /**
     * The element a queued {@code create-element} will make resolves for the session that queued it,
     * and for no other.
     */
    @Test
    public void shouldResolveQueuedCreatedElement_whenSessionIsInBatchMode() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IArchimateModel m = f.createArchimateModel();
        m.setDefaults();
        IBusinessActor actor = f.createBusinessActor();
        actor.setId("actor-created-1");

        dispatcher.beginBatch("session-1", "batch");
        dispatcher.queueForBatch("session-1",
                new CreateElementCommand(actor, m.getFolder(FolderType.BUSINESS)),
                "create element");

        assertSame("the queued element resolves",
                actor, dispatcher.queuedCreatedElement("session-1", "actor-created-1"));
        assertNull("an unknown id resolves to nothing",
                dispatcher.queuedCreatedElement("session-1", "actor-nope"));
        assertNull("another session sees nothing of this batch",
                dispatcher.queuedCreatedElement("session-2", "actor-created-1"));
    }

    /** Rollback clears the queue, so a created element stops being addressable with it. */
    @Test
    public void shouldStopResolvingQueuedCreatedElement_whenBatchIsRolledBack() throws Exception {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IArchimateModel m = f.createArchimateModel();
        m.setDefaults();
        IBusinessActor actor = f.createBusinessActor();
        actor.setId("actor-created-2");

        dispatcher.beginBatch("session-1", "batch");
        dispatcher.queueForBatch("session-1",
                new CreateElementCommand(actor, m.getFolder(FolderType.BUSINESS)),
                "create element");
        assertNotNull("pre-condition: resolvable while queued",
                dispatcher.queuedCreatedElement("session-1", "actor-created-2"));

        dispatcher.endBatch("session-1", false);

        assertNull("a rolled-back create leaves nothing addressable",
                dispatcher.queuedCreatedElement("session-1", "actor-created-2"));
    }

    /** Rollback clears the queue, so the derived geometry goes with it. */
    @Test
    public void shouldStopResolvingQueuedBounds_whenBatchIsRolledBack() throws Exception {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IDiagramModelGroup group = f.createDiagramModelGroup();
        group.setId("grp-1");
        group.setBounds(0, 0, 100, 100);

        dispatcher.beginBatch("session-1", "batch");
        dispatcher.queueForBatch("session-1",
                new UpdateViewObjectCommand(group, 5, 5, 800, 800), "resize group");
        dispatcher.endBatch("session-1", false);

        assertNull("a rolled-back batch derives no geometry",
                dispatcher.queuedBounds("session-1"));
    }

    /**
     * Test subclass that overrides dispatchCommand to avoid Display.syncExec + CommandStack.
     * Tracks dispatched commands for assertions.
     */
    private static class TestMutationDispatcher extends MutationDispatcher {

        final java.util.List<Command> dispatchedCommands = new java.util.ArrayList<>();

        TestMutationDispatcher() {
            super(() -> null); // No model needed for batch management tests
        }

        @Override
        protected void dispatchCommand(Command command) throws MutationException {
            dispatchedCommands.add(command);
        }
    }

    // ---- armFor tests ----

    @Test
    public void armFor_shouldReportApplied_whenNeitherApprovalNorBatchIsActive() {
        dispatcher.setApprovalModeProvider(() -> false);

        assertEquals(DispatchArm.APPLIED, dispatcher.armFor("session-arm"));
    }

    @Test
    public void armFor_shouldReportQueued_whenABatchIsOpenAndApprovalIsOff() {
        dispatcher.setApprovalModeProvider(() -> false);
        dispatcher.beginBatch("session-arm", "Arm batch");

        assertEquals(DispatchArm.QUEUED, dispatcher.armFor("session-arm"));
    }

    @Test
    public void armFor_shouldReportAwaitingApproval_whenApprovalIsOnAndNoBatchIsOpen() {
        dispatcher.setApprovalModeProvider(() -> true);

        assertEquals(DispatchArm.AWAITING_APPROVAL, dispatcher.armFor("session-arm"));
    }

    @Test
    public void armFor_shouldReportAwaitingApproval_whenApprovalIsOnInsideAnOpenBatch() {
        // The ordering claim, and the one a naive implementation gets wrong. Both conditions hold
        // here, so the answer depends entirely on which is read first -- and the accessor settles
        // it: every tool tests isApprovalRequired and returns a proposal BEFORE it reaches
        // dispatchOrQueue, so nothing is queued on this path. Answering QUEUED would hand the call
        // a disclosure describing a batch entry that was never made, and tell the caller to run
        // end-batch to commit something the batch does not hold.
        dispatcher.setApprovalModeProvider(() -> true);
        dispatcher.beginBatch("session-arm", "Arm batch");

        assertEquals(OperationalMode.BATCH, dispatcher.getMode("session-arm"));
        assertEquals("approval wins over an open batch, exactly as the accessor's gates order it",
                DispatchArm.AWAITING_APPROVAL, dispatcher.armFor("session-arm"));
    }

    /**
     * Minimal Command stub for testing batch queue management.
     */
    private static class StubCommand extends Command {
        StubCommand(String label) {
            super(label);
        }

        @Override
        public void execute() {
            // no-op for testing
        }
    }
}
