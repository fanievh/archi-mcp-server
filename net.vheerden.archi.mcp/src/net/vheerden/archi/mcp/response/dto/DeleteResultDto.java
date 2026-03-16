package net.vheerden.archi.mcp.response.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Data Transfer Object for deletion operation results (Story 8-4).
 *
 * <p>Contains the deleted entity's identity and cascade counts indicating
 * how many related objects were also removed. Folder-specific cascade counts
 * (elementsRemoved, viewsRemoved, foldersRemoved) are null for non-folder
 * deletions and omitted from JSON output.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeleteResultDto(
    String id,
    String name,
    String type,
    int relationshipsRemoved,
    int viewReferencesRemoved,
    int viewConnectionsRemoved,
    Integer elementsRemoved,
    Integer viewsRemoved,
    Integer foldersRemoved
) {}
