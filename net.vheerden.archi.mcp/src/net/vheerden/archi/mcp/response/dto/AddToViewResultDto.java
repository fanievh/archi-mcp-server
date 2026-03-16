package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Data Transfer Object for the result of an add-to-view operation (Story 7-7).
 *
 * <p>Contains the created view object and optional list of auto-created
 * connections (when autoConnect=true). The autoConnections list is omitted
 * from JSON when null (NON_NULL annotation). When auto-connect is capped
 * at the maximum, skippedAutoConnections reports the count not created.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AddToViewResultDto(
    ViewObjectDto viewObject,
    List<ViewConnectionDto> autoConnections,
    Integer skippedAutoConnections
) {
    /**
     * Convenience constructor without skipped count (no cap hit).
     */
    public AddToViewResultDto(ViewObjectDto viewObject, List<ViewConnectionDto> autoConnections) {
        this(viewObject, autoConnections, null);
    }
}
