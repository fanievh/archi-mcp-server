package net.vheerden.archi.mcp.response.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What a {@code change-element-type} call did, element by element.
 *
 * <p>An immediate result is read off the command after it ran, so every number is what the write
 * did. A proposed result is a projection of the model as it was when the call arrived, and is
 * nested under {@code preview} by the response envelope.</p>
 *
 * <p>{@code idMap} maps each id passed to the id the element has now. An element keeps its id, so
 * the map is the identity; it is returned so that a caller can chain it into a later call without
 * depending on that.</p>
 *
 * <p>A relationship the new types do not allow is listed in {@code relationshipsConverted} when the
 * call asked for it to become an Association. It is listed once, under the first element in the
 * call that it joins, and it is still counted in {@code relationshipsRepointed}.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChangeElementTypeResultDto(
    List<Change> changes,
    Map<String, String> idMap,
    Totals totals
) {

    /**
     * One element and what changing its type did. {@code figureTypesReset} is omitted when no
     * box's figure changed, {@code specializationDropped} when the element had none, and
     * {@code relationshipsConverted} when no relationship listed under it was converted.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Change(
        String elementId,
        String resultingId,
        String name,
        String fromType,
        String toType,
        int relationshipsRepointed,
        int diagramObjectsRepointed,
        String folderId,
        boolean folderChanged,
        List<FigureTypeReset> figureTypesReset,
        SpecializationDropped specializationDropped,
        List<RelationshipConverted> relationshipsConverted
    ) {}

    /**
     * A relationship the new types do not allow, replaced by an Association that keeps its id,
     * name, documentation, properties, features, folder place, view connections and the
     * relationships attached to it. {@code sourceId} and {@code targetId} are its ends once the
     * call is done. {@code attributesDropped} holds the old type's attributes, which an
     * Association cannot carry, and is omitted when it had none; {@code specializationDropped} is
     * omitted when it had no specialization; {@code documentationNoteAdded} is true when Archi's
     * note was put above its documentation, and omitted otherwise.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RelationshipConverted(
        String relationshipId,
        String resultingId,
        String fromType,
        String toType,
        String sourceId,
        String targetId,
        int connectionsRepointed,
        RelationshipSemanticAttributes attributesDropped,
        SpecializationDropped specializationDropped,
        Boolean documentationNoteAdded
    ) {}

    /**
     * A box that now draws the new type's default figure. {@code from} and {@code to} are Archi's
     * figure numbers, whose meaning depends on the element type.
     */
    public record FigureTypeReset(String diagramObjectId, String viewId, int from, int to) {}

    /**
     * The specialization the element or relationship had, which the new type cannot carry. The
     * specialization itself stays in the model.
     */
    public record SpecializationDropped(String name, String conceptType) {}

    /** The whole call. A relationship joining two changed elements is counted once. */
    public record Totals(
        int elementsChanged,
        int relationshipsRepointed,
        int diagramObjectsRepointed,
        int figureTypesReset,
        int specializationsDropped,
        int foldersChanged,
        int relationshipsConverted
    ) {}
}
