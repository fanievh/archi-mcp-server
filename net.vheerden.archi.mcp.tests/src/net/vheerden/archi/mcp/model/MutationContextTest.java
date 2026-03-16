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

/**
 * Tests for {@link MutationContext} approval and proposal management (Story 7-6).
 *
 * <p>MutationContext is package-private, so this test class resides in the
 * same package within the test fragment. Uses a StubCommand for proposal
 * construction.</p>
 */
public class MutationContextTest {

    private MutationContext context;

    @Before
    public void setUp() {
        context = new MutationContext();
    }

    // ---- Approval flag tests ----

    @Test
    public void shouldDefaultApprovalToFalse() {
        assertFalse(context.isApprovalRequired());
    }

    @Test
    public void shouldSetApprovalRequired() {
        context.setApprovalRequired(true);

        assertTrue(context.isApprovalRequired());
    }

    @Test
    public void shouldDisableApproval() {
        context.setApprovalRequired(true);
        context.setApprovalRequired(false);

        assertFalse(context.isApprovalRequired());
    }

    // ---- Proposal storage tests ----

    @Test
    public void shouldStoreAndRetrieveProposal() {
        PendingProposal proposal = makeProposal("create-element", "Create Actor");
        String id = context.storeProposal(proposal);

        assertNotNull(id);
        assertTrue(id.startsWith("p-"));

        PendingProposal stored = context.getProposal(id);
        assertNotNull(stored);
        assertEquals(id, stored.proposalId());
        assertEquals("create-element", stored.tool());
        assertEquals("Create Actor", stored.description());
    }

    @Test
    public void shouldAssignIncrementingProposalIds() {
        String id1 = context.storeProposal(makeProposal("create-element", "desc1"));
        String id2 = context.storeProposal(makeProposal("create-element", "desc2"));
        String id3 = context.storeProposal(makeProposal("create-element", "desc3"));

        assertEquals("p-1", id1);
        assertEquals("p-2", id2);
        assertEquals("p-3", id3);
    }

    @Test
    public void shouldRemoveProposal() {
        String id = context.storeProposal(makeProposal("create-element", "desc"));

        PendingProposal removed = context.removeProposal(id);

        assertNotNull(removed);
        assertEquals(id, removed.proposalId());
        assertNull(context.getProposal(id));
    }

    @Test
    public void shouldReturnNullForNonExistentProposal() {
        assertNull(context.getProposal("p-999"));
        assertNull(context.removeProposal("p-999"));
    }

    @Test
    public void shouldListPendingProposals() {
        context.storeProposal(makeProposal("create-element", "desc1"));
        context.storeProposal(makeProposal("create-relationship", "desc2"));

        List<PendingProposal> pending = context.getPendingProposals();

        assertEquals(2, pending.size());
        assertEquals("create-element", pending.get(0).tool());
        assertEquals("create-relationship", pending.get(1).tool());
    }

    @Test
    public void shouldClearProposals() {
        context.storeProposal(makeProposal("create-element", "desc1"));
        context.storeProposal(makeProposal("create-element", "desc2"));
        assertEquals(2, context.getPendingCount());

        context.clearProposals();

        assertEquals(0, context.getPendingCount());
        assertTrue(context.getPendingProposals().isEmpty());
    }

    @Test
    public void shouldPreserveApprovalStateAcrossBatchReset() {
        context.setApprovalRequired(true);
        context.storeProposal(makeProposal("create-element", "desc"));

        // Simulate batch cycle
        context.beginBatch("test batch");
        context.reset();

        // Approval state should survive
        assertTrue(context.isApprovalRequired());
        assertEquals(1, context.getPendingCount());
    }

    @Test
    public void shouldThrowWhenMaxProposalsReached() {
        for (int i = 0; i < MutationContext.MAX_PENDING_PROPOSALS; i++) {
            context.storeProposal(makeProposal("create-element", "desc-" + i));
        }

        try {
            context.storeProposal(makeProposal("create-element", "one too many"));
            fail("Expected IllegalStateException for max proposals");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Maximum pending proposals reached"));
        }
    }

    // ---- Helpers ----

    private PendingProposal makeProposal(String tool, String description) {
        return new PendingProposal(
                null, tool, description, new StubCommand(description),
                "entity", null, Map.of("key", "value"), "Valid", Instant.now());
    }

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
