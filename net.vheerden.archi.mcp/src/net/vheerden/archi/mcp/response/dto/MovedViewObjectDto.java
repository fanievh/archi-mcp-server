package net.vheerden.archi.mcp.response.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A view object a mutation re-positioned or re-sized without being asked to touch it.
 *
 * <p>Two levers produce these. Some objects hold their position <em>relative</em> to another object
 * rather than as a frozen pair of coordinates, so growing that other object displaces them. And a
 * container whose icon is anchored in the corner an arriving child would occupy is grown to clear
 * it, which in turn grows any group that must keep containing it. In both cases the caller never
 * named the affected object. The client here is an agent that cannot see the canvas, so an object
 * that changed for a reason the response does not mention is an object it can no longer reason
 * about — it will keep planning against the rectangle it last saw.</p>
 *
 * <p>Carries the landed rectangle rather than a delta or a count: what the agent needs is where the
 * object <em>is</em>, and a count of how many changed is an index into information it does not
 * have.</p>
 *
 * @param viewObjectId the moved object's view object id
 * @param name         its display name, falling back to the id when the view cannot name it
 * @param newX         landed x, relative to the immediate parent
 * @param newY         landed y, relative to the immediate parent
 * @param newWidth     landed width
 * @param newHeight    landed height
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MovedViewObjectDto(
        String viewObjectId,
        String name,
        int newX,
        int newY,
        int newWidth,
        int newHeight) {
}
