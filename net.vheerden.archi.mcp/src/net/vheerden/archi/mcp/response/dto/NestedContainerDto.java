package net.vheerden.archi.mcp.response.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A container {@code arrange-groups} positioned inside a host rather than on the canvas.
 *
 * <p>A zone drawn inside a host the arrangement predicate declines — an ArchiMate {@code Grouping}
 * inside a {@code Node} typing a cloud region — is top-level in the sense that matters: no other
 * zone contains it. It cannot join the canvas arrangement, because its coordinates are stored
 * relative to the host and dropping it into the canvas grid would place it inside the host and
 * reserve a canvas slot for a box that is not on the canvas. So it is arranged in its host's own
 * space and reported here.</p>
 *
 * <p><b>These are deliberately NOT part of the four-bucket identity.</b>
 * {@code groupsPositioned + standaloneElementsPlaced + skippedContainers + unhandled} counts the
 * view's DIRECT children and still equals {@code topLevelObjects}; a nested container is not one of
 * those and appears in none of the four. Counting it in {@code groupsPositioned} would make a
 * published equality arithmetically false, and re-deriving the denominator to some deeper count
 * would silently change what five surfaces mean by it. A new field adds information; a redefined
 * field invalidates every consumer of the old one.</p>
 *
 * @param viewObjectId     the arranged container's view object id
 * @param name             its display name, falling back to the id when the view cannot name it
 * @param newX             landed x, <em>relative to {@code hostViewObjectId}</em>, not to the canvas
 * @param newY             landed y, relative to the host
 * @param newWidth         landed width
 * @param newHeight        landed height
 * @param hostViewObjectId the container this one was arranged inside, and the origin its x/y are
 *     measured from. Present on every entry: the coordinates are unreadable without it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NestedContainerDto(
        String viewObjectId,
        String name,
        int newX,
        int newY,
        int newWidth,
        int newHeight,
        String hostViewObjectId) {
}
