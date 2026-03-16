package net.vheerden.archi.mcp.response;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Structured error response for MCP tool invocations.
 *
 * <p>Follows the standard error envelope format:</p>
 * <pre>
 * {
 *   "error": {
 *     "code": "ELEMENT_NOT_FOUND",
 *     "message": "No element found with ID 'xyz-789'",
 *     "details": "The model contains 2,341 elements.",
 *     "suggestedCorrection": "search-elements with a name query",
 *     "archiMateReference": null
 *   }
 * }
 * </pre>
 *
 * <p>Null fields are omitted from the serialized JSON output.</p>
 */
public class ErrorResponse {

    private final ErrorCode code;
    private final String message;
    private final String details;
    private final String suggestedCorrection;
    private final String archiMateReference;

    public ErrorResponse(ErrorCode code, String message, String details,
                         String suggestedCorrection, String archiMateReference) {
        this.code = Objects.requireNonNull(code, "ErrorCode must not be null");
        this.message = Objects.requireNonNull(message, "message must not be null");
        this.details = details;
        this.suggestedCorrection = suggestedCorrection;
        this.archiMateReference = archiMateReference;
    }

    public ErrorResponse(ErrorCode code, String message) {
        this(code, message, null, null, null);
    }

    public ErrorCode getCode() {
        return code;
    }

    public String getMessage() {
        return message;
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

    /**
     * Converts this error response to a Map suitable for JSON serialization.
     * Null fields are excluded.
     *
     * @return map representation of the error
     */
    public Map<String, Object> toMap() {
        Map<String, Object> errorMap = new LinkedHashMap<>();
        errorMap.put("code", code.name());
        errorMap.put("message", message);
        if (details != null) {
            errorMap.put("details", details);
        }
        if (suggestedCorrection != null) {
            errorMap.put("suggestedCorrection", suggestedCorrection);
        }
        if (archiMateReference != null) {
            errorMap.put("archiMateReference", archiMateReference);
        }
        return errorMap;
    }
}
