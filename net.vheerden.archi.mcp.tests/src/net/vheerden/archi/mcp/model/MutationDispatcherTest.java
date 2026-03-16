package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.eclipse.gef.commands.Command;
import org.junit.Before;
import org.junit.Test;

import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.response.dto.BatchStatusDto;
import net.vheerden.archi.mcp.response.dto.BatchSummaryDto;
import net.vheerden.archi.mcp.response.dto.ProposalDto;

/**
 * Tests for {@link MutationDispatcher} batch management (Story 7-1).
 *
 * <p>Uses a test subclass that overrides {@code dispatchCommand} to avoid
 * Display.syncExec + CommandStack dependencies. E2E dispatch is tested
 * in Story 7-2 with Archi runtime.</p>
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

    // ---- Approval mode tests (Story 7-6) ----

    @Test
    public void shouldSetApprovalRequired() {
        dispatcher.setApprovalRequired("session-1", true);

        assertTrue(dispatcher.isApprovalRequired("session-1"));
    }

    @Test
    public void shouldReturnFalse_whenApprovalNotSet() {
        assertFalse(dispatcher.isApprovalRequired("unknown-session"));
    }

    @Test
    public void shouldDisableApproval() {
        dispatcher.setApprovalRequired("session-1", true);
        dispatcher.setApprovalRequired("session-1", false);

        assertFalse(dispatcher.isApprovalRequired("session-1"));
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

    @Test
    public void shouldIncludeApprovalInfoInBatchStatus() {
        dispatcher.setApprovalRequired("session-1", true);
        PendingProposal proposal = new PendingProposal(
                null, "create-element", "desc",
                new StubCommand("cmd"), "e", null, Map.of(), "v", Instant.now());
        dispatcher.storeProposal("session-1", proposal);

        BatchStatusDto status = dispatcher.getBatchStatus("session-1");

        assertEquals(Boolean.TRUE, status.approvalRequired());
        assertEquals(Integer.valueOf(1), status.pendingApprovalCount());
    }

    @Test
    public void shouldPreserveApprovalState_acrossBatchReset() {
        dispatcher.setApprovalRequired("session-1", true);
        PendingProposal proposal = new PendingProposal(
                null, "create-element", "desc",
                new StubCommand("cmd"), "e", null, Map.of(), "v", Instant.now());
        dispatcher.storeProposal("session-1", proposal);

        // Start and end a batch
        dispatcher.beginBatch("session-1", "batch");
        dispatcher.endBatch("session-1", true);

        // Approval state should survive
        assertTrue(dispatcher.isApprovalRequired("session-1"));
        assertEquals(1, dispatcher.getPendingProposals("session-1").size());
    }

    @Test
    public void shouldClearApprovalState_onClearAllSessions() {
        dispatcher.setApprovalRequired("session-1", true);
        PendingProposal proposal = new PendingProposal(
                null, "create-element", "desc",
                new StubCommand("cmd"), "e", null, Map.of(), "v", Instant.now());
        dispatcher.storeProposal("session-1", proposal);

        dispatcher.clearAllSessions();

        assertFalse(dispatcher.isApprovalRequired("session-1"));
        assertTrue(dispatcher.getPendingProposals("session-1").isEmpty());
    }

    // ---- Immediate dispatch callback tests (Story 7-6 code review Fix 1) ----

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

    // ---- Test helpers ----

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
