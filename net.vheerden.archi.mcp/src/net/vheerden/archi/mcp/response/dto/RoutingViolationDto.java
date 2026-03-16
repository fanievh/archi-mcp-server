package net.vheerden.archi.mcp.response.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * DTO for a routing constraint violation reported in force mode (Story 10-32).
 * Enriched with crossed obstacle identity (Story 10-34).
 * Indicates a connection whose route was applied despite violating constraints.
 *
 * @param connectionId       the connection ID
 * @param sourceElementName  display name of the source element
 * @param targetElementName  display name of the target element
 * @param constraintViolated the constraint that was violated (e.g. "element_crossing")
 * @param severity           violation severity ("warning" for crossings, "info" for suboptimal)
 * @param crossedElementId   view object ID of the element being crossed, or null if not applicable
 * @param crossedElementName display name of the crossed element, or null if not applicable
 */
public record RoutingViolationDto(String connectionId, String sourceElementName,
                                   String targetElementName, String constraintViolated,
                                   String severity,
                                   @JsonInclude(JsonInclude.Include.NON_NULL)
                                   String crossedElementId,
                                   @JsonInclude(JsonInclude.Include.NON_NULL)
                                   String crossedElementName) {

    /** Backward-compatible constructor without crossed element fields. */
    public RoutingViolationDto(String connectionId, String sourceElementName,
                               String targetElementName, String constraintViolated,
                               String severity) {
        this(connectionId, sourceElementName, targetElementName, constraintViolated,
                severity, null, null);
    }
}
