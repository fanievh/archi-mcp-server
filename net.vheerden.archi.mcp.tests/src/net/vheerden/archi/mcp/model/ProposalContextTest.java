package net.vheerden.archi.mcp.model;

import static org.junit.Assert.*;

import java.time.Instant;

import org.junit.Test;

/**
 * Tests for {@link ProposalContext} and {@link MutationResult#isProposal()} (Story 7-6).
 */
public class ProposalContextTest {

    @Test
    public void shouldConstructWithAllFields() {
        Instant now = Instant.now();
        ProposalContext ctx = new ProposalContext("p-1", "Create element: Node", now);

        assertEquals("p-1", ctx.proposalId());
        assertEquals("Create element: Node", ctx.description());
        assertEquals(now, ctx.createdAt());
    }

    @Test
    public void shouldReturnTrue_forIsProposal_whenProposalContextPresent() {
        ProposalContext ctx = new ProposalContext("p-2", "desc", Instant.now());
        MutationResult<String> result = new MutationResult<>("entity", null, ctx);

        assertTrue(result.isProposal());
        assertFalse(result.isBatched());
    }

    @Test
    public void shouldReturnFalse_forIsProposal_whenNoProposalContext() {
        MutationResult<String> result = new MutationResult<>("entity", null);

        assertFalse(result.isProposal());
    }

    @Test
    public void shouldSupportBothBatchedAndProposal() {
        // Technically shouldn't happen, but test the boolean independence
        ProposalContext ctx = new ProposalContext("p-3", "desc", Instant.now());
        MutationResult<String> result = new MutationResult<>("entity", 5, ctx);

        assertTrue(result.isProposal());
        assertTrue(result.isBatched());
    }
}
