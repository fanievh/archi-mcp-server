package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result DTO for the detect-hub-elements tool.
 * Contains elements sorted by connection count descending, summary statistics,
 * and optional sizing suggestions for hub elements.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DetectHubElementsResultDto(
        String viewId,
        int totalElements,
        int totalConnections,
        double averageConnectionCount,
        List<HubElementEntryDto> elements,
        List<String> suggestions) {
}
