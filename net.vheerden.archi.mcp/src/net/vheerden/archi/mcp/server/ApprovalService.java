package net.vheerden.archi.mcp.server;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.vheerden.archi.mcp.model.ApprovalQueueListener;
import net.vheerden.archi.mcp.model.ApprovalResult;
import net.vheerden.archi.mcp.model.MutationDispatcher;
import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.response.dto.PendingProposalView;
import net.vheerden.archi.mcp.response.dto.ProposalDto;

/**
 * UI-callable approve/reject seam for pending mutation proposals.
 *
 * <p><strong>Control/data-plane split.</strong> Before the control-plane split, the agent approved its own
 * queued mutations via the {@code decide-mutation} MCP tool — a "cat-flap" that made the
 * approval gate theatre. That tool is removed. This service is the replacement seam: it is
 * <em>not</em> on the MCP tool surface, so no agent can reach it. The human side binds to it
 * — the Pending Approvals dock view wires its approve/reject buttons here, and its
 * tests exercise it directly to prove the queue still drains.</p>
 *
 * <p><strong>Architecture boundary:</strong> a thin orchestration collaborator. It imports
 * no MCP SDK or SWT types — only the {@code model/} dispatcher facade and response DTOs.
 * The actual command dispatch stays in {@link MutationDispatcher}.</p>
 *
 * <p><strong>Stale proposals (held-Command hazard retired).</strong> A pending proposal
 * no longer holds a pre-built GEF {@code Command} closed over propose-time objects. It now
 * holds a deferred rebuild handle + a staleness capture: at approve the {@code ProposalStalenessGuard}
 * re-resolves the targeted objects and rejects-stale (naming what the human touched) if any was edited or
 * removed during the review window, and the {@code ProposalBuilder} rebuilds the command fresh against the
 * current model otherwise. {@link #approve} surfaces staleness as a
 * {@link net.vheerden.archi.mcp.model.exceptions.ProposalStaleException}, a {@link MutationException}
 * carrying {@code PROPOSAL_STALE}, whose message is a plain-language, target-named reason; nothing is
 * applied and the proposal stays queued. The code is raised on this seam only: no MCP tool returns it.</p>
 */
public final class ApprovalService {

    private static final Logger logger = LoggerFactory.getLogger(ApprovalService.class);

    private final MutationDispatcher dispatcher;

    /**
     * @param dispatcher the model-layer dispatcher that owns proposal storage and command dispatch
     */
    public ApprovalService(MutationDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
    }

    /**
     * Lists the pending proposals for a session (read-only convenience for the dock view).
     *
     * @param sessionId the session identifier
     * @return the pending proposals as DTOs (empty if none)
     */
    public List<ProposalDto> listPending(String sessionId) {
        return dispatcher.getPendingProposalDtos(sessionId);
    }

    /**
     * Lists the pending proposals across <em>all</em> sessions for the Pending Approvals dock view
     * Each entry carries its routing {@code sessionId} so {@link #approve}/{@link #reject}
     * can target the right session. Ordered oldest-first.
     *
     * @return all sessions' pending proposals (empty if none)
     */
    public List<PendingProposalView> listAllPending() {
        return dispatcher.listAllPending();
    }

    /**
     * Registers a listener notified whenever the pending-approval queue changes, so the dock view
     * can rebuild live without polling. The view registers on open and removes on
     * dispose. The listener fires on whatever thread mutated the queue (typically a Jetty thread);
     * the view is responsible for marshalling to the SWT thread.
     *
     * @param listener the listener to add
     */
    public void addQueueListener(ApprovalQueueListener listener) {
        dispatcher.addQueueListener(listener);
    }

    /**
     * Removes a previously registered queue listener.
     *
     * @param listener the listener to remove
     */
    public void removeQueueListener(ApprovalQueueListener listener) {
        dispatcher.removeQueueListener(listener);
    }

    /**
     * Approves a pending proposal: removes it from the queue and dispatches its held command
     * to the model (or queues it if a batch is active).
     *
     * @param sessionId  the session identifier
     * @param proposalId the proposal to approve
     * @return the approval result (entity + optional batch sequence), or {@code null} if no such proposal
     * @throws MutationException if the held command fails to apply (proposal is stale)
     */
    public ApprovalResult approve(String sessionId, String proposalId) throws MutationException {
        logger.info("Human approve of proposal '{}' (session '{}')", proposalId, sessionId);
        return dispatcher.approveProposal(sessionId, proposalId);
    }

    /**
     * Rejects a pending proposal: removes it from the queue with no change to the model.
     *
     * @param sessionId  the session identifier
     * @param proposalId the proposal to reject
     * @return a DTO of the rejected proposal, or {@code null} if no such proposal
     */
    public ProposalDto reject(String sessionId, String proposalId) {
        logger.info("Human reject of proposal '{}' (session '{}')", proposalId, sessionId);
        return dispatcher.rejectProposal(sessionId, proposalId);
    }
}
