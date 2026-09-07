package net.vheerden.archi.mcp.model;

import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.gef.commands.Command;

/**
 * Stores a mutation proposal awaiting human approval (stores the request, not a pre-built Command).
 *
 * <p>Package-private — only used within the {@code model/} package by {@link MutationContext},
 * {@link MutationDispatcher}, {@link ProposalBuilder}, and {@link ProposalStalenessGuard}.</p>
 *
 * <p><strong>Store the request, not the Command.</strong> Previously a proposal held a pre-built GEF
 * {@code Command} closed over EObjects resolved at <em>propose</em>-time. Across a human-paced (minutes)
 * review window, any human edit to a targeted object made that frozen command NPE, misapply, or clobber
 * the human's work — caught only <em>after the fact</em>. This record now holds instead:
 * <ul>
 *   <li>{@code rebuild} — a deferred {@code Supplier<PreparedMutation<?>>} that re-invokes the same
 *       per-tool {@code prepareXxx(...)} logic against the <em>current</em> model at approve-time (via
 *       {@link ProposalBuilder}); it closes over param primitives / id-strings, never live EObjects;</li>
 *   <li>{@code capture} — the {@link StalenessCapture} (stack sequence + per-target fingerprints + names)
 *       the {@link ProposalStalenessGuard} compares at approve to reject-stale a proposal whose targets the
 *       human edited or removed in the meantime.</li>
 * </ul>
 *
 * <p>The propose-time card fields — {@code entity}, {@code currentState}, {@code proposedChanges},
 * {@code validationSummary}, {@code createdAt}, plus the {@code effectDescription} (server-owned,
 * non-spoofable effect text) and {@code intent} (the agent's lower-trust stated reason) — are preserved
 * unchanged so the card renders exactly as before. {@code effectDescription} and {@code intent} are
 * never merged — different trust levels.</p>
 *
 * <p><strong>Command-bridge constructors.</strong> The {@code Command}-carrying constructors below wrap a
 * pre-built command as a trivial no-rebuild handle with an {@linkplain StalenessCapture#EMPTY empty}
 * (always-fresh) capture. They exist for legacy/test construction and the dispatcher's field overload; the
 * ~40 production propose sites use the deferred-rebuild canonical constructor so no frozen command is ever
 * stored on the live path.</p>
 *
 * @param proposalId        unique identifier (e.g., "p-1"), assigned by {@link MutationContext}
 * @param tool              the MCP tool name (e.g., "create-element", "bulk-mutate")
 * @param description       human-readable description of the proposed mutation
 * @param rebuild           deferred handle that rebuilds a fresh {@link PreparedMutation} at approve-time
 * @param capture           the staleness snapshot ({@link StalenessCapture#EMPTY} when no targets tracked)
 * @param entity            the propose-time DTO representing the proposed result (ElementDto, etc.)
 * @param currentState      snapshot of current state before mutation (null for creates)
 * @param proposedChanges   map of field names to proposed values
 * @param validationSummary human-readable validation result summary
 * @param createdAt         timestamp when the proposal was created
 * @param effectDescription server-owned rich human-readable effect text; null when not enriched
 * @param intent            the agent's optional stated intent; null when absent
 */
record PendingProposal(
    String proposalId,
    String tool,
    String description,
    Supplier<PreparedMutation<?>> rebuild,
    StalenessCapture capture,
    Object entity,
    Map<String, Object> currentState,
    Map<String, Object> proposedChanges,
    String validationSummary,
    Instant createdAt,
    String effectDescription,
    String intent
) {
    /**
     * Returns a copy of this proposal with the given {@code proposalId} (assigned by
     * {@link MutationContext#storeProposal}). Arity-proof — adding a field never breaks the id-stamping
     * site (record-arity discipline).
     */
    PendingProposal withProposalId(String id) {
        return new PendingProposal(id, tool, description, rebuild, capture, entity,
                currentState, proposedChanges, validationSummary, createdAt, effectDescription, intent);
    }

    /**
     * Command-bridge constructor carrying the {@code effectDescription}/{@code intent}. Wraps a
     * pre-built {@code command} as a no-rebuild handle ({@code () -> new PreparedMutation<>(command,
     * entity, null)}) with an {@linkplain StalenessCapture#EMPTY always-fresh} capture. Used by legacy/test
     * call-sites; production propose sites use the deferred-rebuild canonical constructor.
     */
    PendingProposal(
        String proposalId,
        String tool,
        String description,
        Command command,
        Object entity,
        Map<String, Object> currentState,
        Map<String, Object> proposedChanges,
        String validationSummary,
        Instant createdAt,
        String effectDescription,
        String intent
    ) {
        this(proposalId, tool, description,
                () -> new PreparedMutation<>(command, entity, null),
                StalenessCapture.EMPTY, entity, currentState,
                proposedChanges, validationSummary, createdAt, effectDescription, intent);
    }

    /**
     * Back-compat command-bridge constructor for call-sites that carry neither
     * {@code effectDescription} nor {@code intent}. Delegates with both null, preserving NON_NULL wire
     * omission.
     */
    PendingProposal(
        String proposalId,
        String tool,
        String description,
        Command command,
        Object entity,
        Map<String, Object> currentState,
        Map<String, Object> proposedChanges,
        String validationSummary,
        Instant createdAt
    ) {
        this(proposalId, tool, description, command, entity, currentState,
                proposedChanges, validationSummary, createdAt, null, null);
    }
}
