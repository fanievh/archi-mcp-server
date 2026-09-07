package net.vheerden.archi.mcp.response.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Data Transfer Object for move-to-folder operation results.
 *
 * <p>Reports what was moved and where it was moved from/to. The two type fields differ in
 * precision, not in subject: {@code objectType} is the coarse noun a human reads
 * (Folder/Element/Relationship/View), while {@code elementType} is the exact Archi type of
 * whatever moved — {@code BusinessActor}, {@code AssociationRelationship},
 * {@code ArchimateDiagramModel}, {@code SketchModel}, {@code Folder}. It is populated for every
 * kind, which is what lets a caller tell a sketch view from an ArchiMate one.</p>
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
