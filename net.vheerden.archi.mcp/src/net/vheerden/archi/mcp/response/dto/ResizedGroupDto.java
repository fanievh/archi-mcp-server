package net.vheerden.archi.mcp.response.dto;

/**
 * Response DTO for a group that was automatically resized during autoNudge (backlog-b15).
 *
 * @param viewObjectId  view object ID of the resized group
 * @param groupName     human-readable name of the group
 * @param newX          new x position (relative to parent, or absolute if top-level)
 * @param newY          new y position (relative to parent, or absolute if top-level)
 * @param newWidth      new width after resize
 * @param newHeight     new height after resize
 */
public record ResizedGroupDto(String viewObjectId, String groupName,
                               int newX, int newY, int newWidth, int newHeight) {}
