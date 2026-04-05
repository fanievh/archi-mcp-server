package net.vheerden.archi.mcp.response.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Per-element entry in the hub element detection result.
 * Contains the visual connection count, current dimensions, and max label width
 * for a single view object.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HubElementEntryDto(
        String viewObjectId,
        String elementId,
        String elementName,
        String elementType,
        int connectionCount,
        int width,
        int height,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        int maxLabelWidth) {
}
