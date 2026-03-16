package net.vheerden.archi.mcp.model;

/**
 * Thrown when an operation requires a loaded ArchiMate model but none is available.
 *
 * <p>Error code: {@code NO_MODEL_LOADED}. Handlers should translate this to a
 * structured error response with a suggestion to open a model in ArchimateTool.</p>
 */
public class NoModelLoadedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Structured error code — aligned with {@link net.vheerden.archi.mcp.response.ErrorCode#MODEL_NOT_LOADED}. */
    public static final String ERROR_CODE = "MODEL_NOT_LOADED";

    /** Standard user-facing message. */
    public static final String DEFAULT_MESSAGE =
            "No ArchiMate model is currently loaded. Open a model in ArchimateTool and try again.";

    public NoModelLoadedException() {
        super(DEFAULT_MESSAGE);
    }

    public NoModelLoadedException(String message) {
        super(message);
    }

    /**
     * Returns the structured error code.
     *
     * @return the error code string
     */
    public String getErrorCode() {
        return ERROR_CODE;
    }
}
