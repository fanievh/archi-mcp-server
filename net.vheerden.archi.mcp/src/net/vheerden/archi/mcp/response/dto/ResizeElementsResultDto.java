package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result DTO for the resize-elements-to-fit tool.
 *
 * <p>Growing elements to fit their labels can push them past the edge of a containing group, and
 * the parent-fit cascade then grows that group too. {@code resizedGroups} reports those groups and
 * the bounds each ended at: the client is an agent that cannot see the canvas, so a group that
 * silently moved is a group it can no longer reason about. Omitted from JSON when empty, so a pass
 * that grew no group says nothing rather than saying "none" in a way that looks measured.</p>
 *
 * <p>{@code skippedContainers} answers the same question for an object this pass deliberately did
 * <em>not</em> touch. An ArchiMate {@code Grouping} is a zone rather than a label-bearing element,
 * so it is never sized to its own name; when the caller <em>named</em> one in {@code elementIds},
 * a success that mentions neither the object nor the reason would read as "resized". A zone the
 * walk merely found is left out, which is exactly the treatment a native view group gets in the
 * same position — the caller asked about the view, not about that object.</p>
 *
 * <p>{@code movedObjects} carries the same obligation one step further. An object anchored to one
 * this pass grew is displaced by that growth even though the caller never named it, so its landed
 * rectangle is reported for the same reason: the agent cannot see the canvas, and an object that
 * moved silently is one it will keep planning against at coordinates that no longer exist.</p>
 *
 * @param viewId           the view whose elements were resized
 * @param resizedCount     number of elements that were actually resized
 * @param unchangedCount   number of elements that kept their original size
 * @param resizedElements  details of each resized element
 * @param resizedGroups    groups the parent-fit cascade grew, with their effective bounds
 * @param movedObjects     objects anchored to something this pass grew, and where each landed
 * @param skippedContainers ArchiMate {@code Grouping} zones the caller NAMED and this pass
 *                          declined to size, each with the reason
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResizeElementsResultDto(
        String viewId,
        int resizedCount,
        int unchangedCount,
        List<ResizedElement> resizedElements,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ResizedGroupDto> resizedGroups,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<MovedViewObjectDto> movedObjects,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<SkippedContainerDto> skippedContainers) {

    /**
     * Back-compatible constructor for callers that report no group resizes.
     */
    public ResizeElementsResultDto(String viewId, int resizedCount, int unchangedCount,
            List<ResizedElement> resizedElements) {
        this(viewId, resizedCount, unchangedCount, resizedElements, List.of(), List.of(),
                List.of());
    }

    /**
     * Back-compatible constructor preserving the previous arity, for callers that grow groups but
     * displace nothing anchored.
     */
    public ResizeElementsResultDto(String viewId, int resizedCount, int unchangedCount,
            List<ResizedElement> resizedElements, List<ResizedGroupDto> resizedGroups) {
        this(viewId, resizedCount, unchangedCount, resizedElements, resizedGroups, List.of(),
                List.of());
    }

    /**
     * Back-compatible constructor for callers that report no declined zone.
     */
    public ResizeElementsResultDto(String viewId, int resizedCount, int unchangedCount,
            List<ResizedElement> resizedElements, List<ResizedGroupDto> resizedGroups,
            List<MovedViewObjectDto> movedObjects) {
        this(viewId, resizedCount, unchangedCount, resizedElements, resizedGroups, movedObjects,
                List.of());
    }

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
