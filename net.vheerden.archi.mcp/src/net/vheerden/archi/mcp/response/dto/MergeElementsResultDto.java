package net.vheerden.archi.mcp.response.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What a {@code merge-elements} call did, cluster by cluster.
 *
 * <p>An immediate result is read off the command after it ran, so every number is what the write
 * did. A batched or proposed result is a projection of the model as it was when the call arrived,
 * and is nested under {@code preview} by the response envelope.</p>
 *
 * <p>A list that belongs to a check the call ran is always present, empty when the check found
 * nothing. A field that belongs to a check the chosen policy does not run is omitted:
 * {@code propertyConflicts} exists only under {@code union}, {@code propertiesDiscarded} only under
 * {@code keep-target}, {@code documentationAppended} only when documentation is merged,
 * {@code selfRelationshipsKept} only under {@code keep}, and {@code parallelsCollapsed},
 * {@code parallelsNotCollapsed}, {@code relationshipIdMap} and
 * {@code totals.parallelRelationshipsCollapsed} only under {@code collapseParallel}.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MergeElementsResultDto(
    List<Cluster> clusters,
    Map<String, String> idMap,
    Totals totals,
    String propertyPolicy,
    String selfRelationships,
    boolean recordMergedIds,
    boolean collapseParallel,
    Map<String, String> relationshipIdMap
) {

    /** One target and what collapsing its sources into it changed. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Cluster(
        String targetId,
        String targetName,
        String targetType,
        List<String> mergedSourceIds,
        int relationshipsRepointed,
        int diagramObjectsRepointed,
        List<DroppedRelationship> selfRelationshipsDropped,
        List<String> selfRelationshipsKept,
        List<String> cascadedRelationshipIds,
        List<String> cascadedConnectionIds,
        List<ParallelPair> parallelRelationships,
        List<ParallelCollapsed> parallelsCollapsed,
        List<ParallelNotCollapsed> parallelsNotCollapsed,
        List<PropertyConflict> propertyConflicts,
        int propertiesCopied,
        Integer propertiesDiscarded,
        Integer documentationAppended,
        List<DoublePlacement> doublePlacements,
        List<SpecializationDiffers> specializationDiffers,
        List<NameDropped> namesDropped
    ) {}

    /**
     * A relationship between two members of one cluster, removed because it would have become a
     * relationship of the target with itself. {@code reason} is {@code DROPPED_BY_POLICY} under
     * {@code selfRelationships: drop} and {@code INVALID_SELF} when {@code keep} was asked for but
     * Archi does not allow that relationship type from the target's type to itself.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DroppedRelationship(
        String relationshipId,
        String type,
        String reason,
        List<String> connectionIds
    ) {}

    /** Two relationships of one type that now share both ends. Both are kept. */
    public record ParallelPair(List<String> relationshipIds, String type) {}

    /**
     * A relationship folded into a parallel one it agreed with, and deleted. {@code filled} names
     * what the survivor took from it ({@code name}, {@code documentation}, {@code properties}).
     * Each of its view connections was either re-pointed to the survivor, keeping its id and
     * layout, or removed where the survivor is already drawn between the same two objects. What
     * hung on it now hangs on the survivor: relationships in {@code relationshipsMoved}, and view
     * connections that hung on a removed connection in {@code connectionsMoved}.
     */
    public record ParallelCollapsed(
        String survivorId,
        String droppedId,
        String type,
        List<String> filled,
        List<String> connectionsRepointed,
        List<String> connectionsRemoved,
        List<String> relationshipsMoved,
        List<String> connectionsMoved
    ) {}

    /**
     * A relationship parallel to {@code survivorId} that was kept, with every reason:
     * {@code NAME_DIFFERS}, {@code DOCUMENTATION_DIFFERS}, {@code PROPERTIES_DIFFER},
     * {@code ATTRIBUTE_DIFFERS}, {@code JOINED_TO_SURVIVOR} when a relationship the merge keeps
     * joins it to the survivor, or {@code CREATED_BY_COLLAPSE} for a pair that only a fold's move
     * made parallel (it is not folded in turn).
     */
    public record ParallelNotCollapsed(String relationshipId, String survivorId, String type,
            List<String> reasons) {}

    /** A source property whose key the target already holds with a different value. */
    public record PropertyConflict(String key, String targetValue, String sourceValue, String sourceId) {}

    /** A view that now shows the target in more than one box. Every box was kept. */
    public record DoublePlacement(String viewId, String viewName, List<String> diagramObjectIds) {}

    /**
     * A source whose specialization was not the target's. The target keeps its own; the
     * source's is not copied. A null side means that element had none.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SpecializationDiffers(String sourceId, String sourceSpecialization,
            String targetSpecialization) {}

    /** A source whose name was not the target's, so the name is gone from the model. */
    public record NameDropped(String sourceId, String name) {}

    /** The whole call. A relationship whose ends moved into two clusters is counted once. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Totals(
        int clusters,
        int sourcesMerged,
        int relationshipsRepointed,
        int diagramObjectsRepointed,
        int selfRelationshipsDropped,
        int selfRelationshipsKept,
        int cascadedRelationships,
        int cascadedConnections,
        int parallelRelationshipPairs,
        int propertyConflicts,
        int propertiesCopied,
        int doublePlacements,
        Integer parallelRelationshipsCollapsed
    ) {}
}
