package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Data Transfer Object for ArchiMate folder tree nodes.
 *
 * <p>Returned by the get-folder-tree command. Extends the flat folder
 * representation with a recursive {@code children} list for tree display.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FolderTreeDto(
    String id,
    String name,
    String type,
    String path,
    int elementCount,
    int subfolderCount,
    List<FolderTreeDto> children
) {}
