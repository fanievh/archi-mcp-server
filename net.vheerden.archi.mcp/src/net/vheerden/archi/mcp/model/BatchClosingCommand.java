package net.vheerden.archi.mcp.model;

import org.eclipse.gef.commands.Command;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * A command whose effects are decided only when it runs, and which removes or re-points model
 * objects that an operation prepared before it could name.
 *
 * <p><strong>Why queuing one closes the batch.</strong> Every queued operation is prepared when it
 * is queued, against the model as it is then, and runs at commit in queue order. An operation
 * queued after one of these commands was therefore prepared against a model in which that command
 * has not happened, yet it runs after it: it could write to an object the command deleted, connect
 * to one, or carry a cascade computed before the command moved things onto its target. What such a
 * command removes is decided at commit, so no check at queue time can tell which later operations
 * are safe. Once one is queued, the batch accepts only what the command itself allows to follow
 * ({@link #refuseFollower}), until {@code end-batch} commits or discards the queue.</p>
 *
 * <p>The rule is applied by {@link MutationDispatcher#queueForBatch}, which every queued command
 * passes through, under the batch's lock and together with the enqueue, so a tool added later is
 * covered without naming it here; and, for approval mode, by
 * {@link MutationDispatcher#storeProposal(String, PendingProposal)}, which every proposal passes
 * through.</p>
 */
interface BatchClosingCommand {

    /**
     * Decides whether {@code follower} may be queued after this command.
     *
     * @param seq      this command's operation number in the batch
     * @param follower the command about to be queued
     * @param tool     the tool queuing it, or null when not known
     * @return the refusal to throw, or null when {@code follower} may follow
     */
    Refusal refuseFollower(int seq, Command follower, String tool);

    /**
     * Decides whether a proposal from {@code tool} may be stored while this command is queued.
     * Approving it would queue its command after this one, so a tool this command would refuse is
     * refused when it proposes, rather than only when a human approves.
     *
     * @param seq  this command's operation number in the batch
     * @param tool the tool proposing, or null when not known
     * @return the refusal to throw, or null when the proposal may be stored
     */
    Refusal refuseProposal(int seq, String tool);

    /**
     * A refusal to queue a command after a batch-closing one, or after a command that already takes off
     * a view object this one would take off. Nothing was queued, and the batch is still open; an approval
     * refused this way keeps its card.
     */
    final class Refusal extends ModelAccessException {

        private static final long serialVersionUID = 1L;

        Refusal(String message, ErrorCode errorCode, String suggestedCorrection) {
            super(message, errorCode, null, suggestedCorrection, null);
        }
    }
}
