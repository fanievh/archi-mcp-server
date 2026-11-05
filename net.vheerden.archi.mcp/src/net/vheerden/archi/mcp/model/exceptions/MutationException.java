package net.vheerden.archi.mcp.model.exceptions;

/**
 * Unchecked exception for mutation-specific errors.
 *
 * <p>Thrown when a mutation operation fails (e.g., CommandStack not available,
 * Display not available in headless mode, or CommandStack.execute failure).</p>
 *
 * <p>Handlers should catch this and translate to a structured
 * {@link net.vheerden.archi.mcp.response.ErrorResponse} with
 * {@link net.vheerden.archi.mcp.response.ErrorCode#MUTATION_FAILED}.</p>
 */
public class MutationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MutationException(String message) {
        super(message);
    }

    public MutationException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * What the caller should do next, when this failure knows better than the generic advice; null
     * otherwise. A failure that may have left part of a change in the model says so here, since
     * repeating the call could then apply that part twice.
     */
    public String getSuggestedCorrection() {
        return null;
    }
}
