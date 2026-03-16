package net.vheerden.archi.mcp.response.dto;

/**
 * Data Transfer Object for the result of a clear-view operation (Story 8-0c).
 *
 * <p>Contains the ID and name of the cleared view, plus counts of removed
 * elements and connections. All fields are always present since counts are
 * primitive int.</p>
 */
public record ClearViewResultDto(
    String viewId,
    String viewName,
    int elementsRemoved,
    int connectionsRemoved,
    int nonArchimateObjectsRemoved
) {}
