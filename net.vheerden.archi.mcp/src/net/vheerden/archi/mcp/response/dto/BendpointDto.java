package net.vheerden.archi.mcp.response.dto;

/**
 * Data Transfer Object for a connection bendpoint (Story 7-7).
 *
 * <p>Represents a single routing point on a visual connection between
 * two view objects. Coordinates are relative offsets: startX/startY
 * from source anchor, endX/endY from target anchor.</p>
 */
public record BendpointDto(
    int startX,
    int startY,
    int endX,
    int endY
) {}
