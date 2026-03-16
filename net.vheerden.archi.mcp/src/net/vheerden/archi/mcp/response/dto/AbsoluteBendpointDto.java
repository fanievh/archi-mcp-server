package net.vheerden.archi.mcp.response.dto;

/**
 * Data Transfer Object for an absolute-coordinate bendpoint (Story 8-0d).
 *
 * <p>Represents a single routing point on a visual connection using
 * absolute canvas coordinates. The server converts between absolute
 * and Archi's native relative-offset format transparently.</p>
 */
public record AbsoluteBendpointDto(int x, int y) {}
