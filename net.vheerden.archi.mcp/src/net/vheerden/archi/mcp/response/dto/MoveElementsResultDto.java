package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result DTO for the move-elements operation (Story 13-1).
 *
 * <p>Reports how many elements were moved, the delta applied, and the
 * new positions of all moved elements.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MoveElementsResultDto(
    String viewId,
    int deltaX,
    int deltaY,
    int elementsMoved,
    List<MovedElement> positions
) {

    /**
     * New position of a moved element after delta application.
     */
    public record MovedElement(
        String viewObjectId,
        int x,
        int y,
        int width,
        int height
    ) {}
}
