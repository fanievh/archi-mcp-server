package net.vheerden.archi.mcp.response.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Data Transfer Object for ArchiMate folders.
 *
 * <p>Returned by the get-folders command. Provides information about
 * a folder in the model hierarchy including its type and content counts.</p>
 *
 * <p>The {@code type} field contains the FolderType name (e.g., "BUSINESS",
 * "APPLICATION", "USER" for user-created subfolders).</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FolderDto(
    String id,
    String name,
    String type,
    String path,
    int elementCount,
    int subfolderCount
) {}
