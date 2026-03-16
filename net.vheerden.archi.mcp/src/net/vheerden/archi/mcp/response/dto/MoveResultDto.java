package net.vheerden.archi.mcp.response.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Data Transfer Object for move-to-folder operation results (Story 8-5).
 *
 * <p>Reports what was moved and where it was moved from/to. The
 * {@code elementType} field is only populated for ArchiMate elements;
 * it is null for relationships, views, and folders.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MoveResultDto(
    String id,
    String name,
    String objectType,
    String elementType,
    String sourceFolderPath,
    String targetFolderPath
) {}
