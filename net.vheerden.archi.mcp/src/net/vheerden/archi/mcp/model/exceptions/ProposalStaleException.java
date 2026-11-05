package net.vheerden.archi.mcp.model.exceptions;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * An approval refused because the proposal no longer describes what approving it would do: a targeted
 * object was changed or removed since it was proposed, the proposal could not be rebuilt against the
 * current model, or the rebuilt change would remove more than the reviewed card described.
 *
 * <p>Raised on the human's approve seam only (the Pending Approvals view, through
 * {@code ApprovalService}). The proposal stays queued, and the message is the plain-language reason the
 * card shows. No MCP tool returns this refusal to an agent. A refusal for any other reason (a domain
 * precondition such as a folder that is no longer empty, a model or batch that changed under the
 * approval) is a plain {@link MutationException}.</p>
 */
public class ProposalStaleException extends MutationException {

    private static final long serialVersionUID = 1L;

    public ProposalStaleException(String message) {
        super(message);
    }

    public ProposalStaleException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Always {@link ErrorCode#PROPOSAL_STALE}. */
    public ErrorCode getErrorCode() {
        return ErrorCode.PROPOSAL_STALE;
    }
}
