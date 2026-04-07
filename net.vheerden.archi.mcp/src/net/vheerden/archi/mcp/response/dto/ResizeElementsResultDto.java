package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result DTO for the resize-elements-to-fit tool (Story B48).
 *
 * @param viewId           the view whose elements were resized
 * @param resizedCount     number of elements that were actually resized
 * @param unchangedCount   number of elements that kept their original size
 * @param resizedElements  details of each resized element
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResizeElementsResultDto(
        String viewId,
        int resizedCount,
        int unchangedCount,
        List<ResizedElement> resizedElements) {

    /**
     * Details of a single resized element.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResizedElement(
            String elementId,
            String name,
            int oldWidth,
            int oldHeight,
            int newWidth,
            int newHeight) {}
}
