package net.vheerden.archi.mcp.response.dto;

/**
 * Response DTO for an element that was automatically nudged during auto-route (Story 13-7).
 *
 * @param viewObjectId  view object ID of the nudged element
 * @param elementName   human-readable name of the element
 * @param deltaX        horizontal displacement applied (positive = right)
 * @param deltaY        vertical displacement applied (positive = down)
 */
public record NudgedElementDto(String viewObjectId, String elementName,
                                int deltaX, int deltaY) {}
