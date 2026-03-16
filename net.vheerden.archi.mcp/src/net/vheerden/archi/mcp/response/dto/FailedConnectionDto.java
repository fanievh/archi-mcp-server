package net.vheerden.archi.mcp.response.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response DTO for a connection that failed routing constraint validation (Story 10-30).
 * Enriched with crossed obstacle identity (Story 10-34).
 *
 * @param connectionId       unique identifier for the connection
 * @param sourceElementName  human-readable name of the source element
 * @param targetElementName  human-readable name of the target element
 * @param constraintViolated description of which constraint was violated (e.g. "element_crossing")
 * @param crossedElementId   view object ID of the element being crossed, or null if not applicable
 * @param crossedElementName display name of the crossed element, or null if not applicable
 */
public record FailedConnectionDto(String connectionId, String sourceElementName,
                                   String targetElementName, String constraintViolated,
                                   @JsonInclude(JsonInclude.Include.NON_NULL)
                                   String crossedElementId,
                                   @JsonInclude(JsonInclude.Include.NON_NULL)
                                   String crossedElementName) {

    /** Backward-compatible constructor without crossed element fields. */
    public FailedConnectionDto(String connectionId, String sourceElementName,
                               String targetElementName, String constraintViolated) {
        this(connectionId, sourceElementName, targetElementName, constraintViolated, null, null);
    }
}
