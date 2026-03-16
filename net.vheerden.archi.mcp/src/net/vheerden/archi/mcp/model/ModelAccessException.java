package net.vheerden.archi.mcp.model;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * Runtime exception for unexpected errors during model access.
 *
 * <p>Wraps unexpected EMF or model traversal errors with an {@link ErrorCode}
 * for structured error reporting. Handlers should catch this and translate
 * it to an {@link net.vheerden.archi.mcp.response.ErrorResponse}.</p>
 */
public class ModelAccessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;
    private final String details;
    private final String suggestedCorrection;
    private final String archiMateReference;

    public ModelAccessException(String message, ErrorCode errorCode) {
        super(message);
        this.errorCode = errorCode;
        this.details = null;
        this.suggestedCorrection = null;
        this.archiMateReference = null;
    }

    public ModelAccessException(String message, Throwable cause, ErrorCode errorCode) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = null;
        this.suggestedCorrection = null;
        this.archiMateReference = null;
    }

    /**
     * Creates an exception with full error context for structured error responses.
     * Used for ArchiMate spec violations (e.g., invalid relationship combinations).
     */
    public ModelAccessException(String message, ErrorCode errorCode,
            String details, String suggestedCorrection, String archiMateReference) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
        this.suggestedCorrection = suggestedCorrection;
        this.archiMateReference = archiMateReference;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public String getDetails() {
        return details;
    }

    public String getSuggestedCorrection() {
        return suggestedCorrection;
    }

    public String getArchiMateReference() {
        return archiMateReference;
    }
}
