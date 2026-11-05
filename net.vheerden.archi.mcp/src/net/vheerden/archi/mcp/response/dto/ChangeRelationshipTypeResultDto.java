package net.vheerden.archi.mcp.response.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import net.vheerden.archi.mcp.response.dto.ChangeElementTypeResultDto.SpecializationDropped;

/**
 * What a {@code change-relationship-type} call did: one entry per listed relationship, in the
 * order the call listed them, the id each relationship has afterwards, and the totals.
 *
 * <p>Each relationship is replaced by one of the new type that keeps its id, so {@code idMap} maps
 * every listed id to itself; it is reported so a caller need not rely on that.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChangeRelationshipTypeResultDto(
    List<Change> changes,
    Map<String, String> idMap,
    Totals totals
) {

    /**
     * One relationship and what changing its type did. {@code sourceId}, {@code targetId} and
     * {@code folderId} are where it is once the call is done. {@code attributesDropped} holds the
     * old type's own attribute, which the new type cannot carry, and is omitted when there was none
     * to lose; {@code attributes} holds the new type's own attribute as the model now holds it (the
     * new type's default), and is omitted when the new type has none set;
     * {@code specializationDropped} is omitted when the relationship had none.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Change(
        String relationshipId,
        String resultingId,
        String name,
        String fromType,
        String toType,
        String sourceId,
        String targetId,
        int connectionsRepointed,
        int relationshipsRepointed,
        String folderId,
        RelationshipSemanticAttributes attributesDropped,
        RelationshipSemanticAttributes attributes,
        SpecializationDropped specializationDropped
    ) {}

    /**
     * The whole call. {@code attributesDropped} and {@code specializationsDropped} count the
     * relationships that lost one.
     */
    public record Totals(
        int relationshipsChanged,
        int connectionsRepointed,
        int relationshipsRepointed,
        int attributesDropped,
        int specializationsDropped
    ) {}
}
