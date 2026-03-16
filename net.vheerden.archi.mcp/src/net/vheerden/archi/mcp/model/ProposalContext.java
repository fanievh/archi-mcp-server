package net.vheerden.archi.mcp.model;

import java.time.Instant;

/**
 * Lightweight context returned when a mutation is stored as a proposal
 * instead of executed immediately (Story 7-6, human-in-the-loop approval).
 *
 * <p>Contains only the proposal identifier and metadata needed by handlers
 * to format the proposal response. The full proposal state (Command, entity,
 * current/proposed state) is held in {@link PendingProposal} within the
 * {@code model/} package and never exposed to handlers.</p>
 *
 * @param proposalId  unique proposal identifier (e.g., "p-1")
 * @param description human-readable description of the proposed mutation
 * @param createdAt   timestamp when the proposal was created
 */
public record ProposalContext(String proposalId, String description, Instant createdAt) {
}
