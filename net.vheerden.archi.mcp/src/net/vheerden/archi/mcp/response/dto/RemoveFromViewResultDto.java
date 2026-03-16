package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Data Transfer Object for the result of a remove-from-view operation (Story 7-8).
 *
 * <p>Contains the ID and type of the removed object, plus optional list of
 * cascade-removed connection IDs (when removing an element that has attached
 * connections). The cascadeRemovedConnectionIds list is omitted from JSON
 * when null (NON_NULL annotation).</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RemoveFromViewResultDto(
    String removedObjectId,
    String removedObjectType,
    List<String> cascadeRemovedConnectionIds
) {}
