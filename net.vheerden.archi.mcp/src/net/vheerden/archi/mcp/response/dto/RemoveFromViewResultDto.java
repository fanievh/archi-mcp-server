package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Data Transfer Object for the result of a remove-from-view operation.
 *
 * <p>Contains the ID and type of the removed object or connection, plus optional list of
 * cascade-removed connection IDs (every connection the removal took with it; for a group,
 * its descendant view-object IDs too). The type is {@code viewObject}, {@code group},
 * {@code note}, {@code viewReference}, {@code image}, {@code viewConnection},
 * {@code connection} (not an ArchiMate one), or the Archi type name of any other view
 * object. The cascadeRemovedConnectionIds list is omitted from JSON when null
 * (NON_NULL annotation).</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RemoveFromViewResultDto(
    String removedObjectId,
    String removedObjectType,
    List<String> cascadeRemovedConnectionIds
) {}
