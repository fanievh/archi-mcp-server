package net.vheerden.archi.mcp.response.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Data Transfer Object for ArchiMate relationships.
 *
 * <p>Represents a relationship between two ArchiMate concepts.
 * Used in view contents, relationship queries, and search results.</p>
 *
 * <p>Fields {@code documentation}, {@code properties}, {@code sourceName},
 * and {@code targetName} are optional, and what decides whether a caller sees them differs by
 * surface. On the responses of the mutating relationship tools they are always populated — a
 * caller who has just written the relationship needs to see what it now holds and what it now
 * joins, and those responses are not field-selected. On the list-returning read tools they are
 * populated on the record but gated by the field preset, which names them in {@code full} alone,
 * so a default response does not carry them and they cannot grow every row. They are omitted from
 * JSON when null.</p>
 *
 * <p>{@code documentation} carries one deliberate asymmetry between those two uses: on a mutation
 * response an empty string is preserved, because a cleared value must be distinguishable from a
 * field the tool declined to report; everywhere else empty is normalised to null so
 * {@code @JsonInclude(NON_NULL)} omits it. A concept's documentation defaults to the empty string
 * and is never null, so without that normalisation the field would appear on every row.</p>
 *
 * <p>Fields {@code accessType}, {@code associationDirected}, and
 * {@code influenceStrength} are populated only when the
 * relationship is the matching ArchiMate subtype: {@code accessType} for
 * {@code AccessRelationship} (one of {@code "access" / "read" / "write" / "readwrite"});
 * {@code associationDirected} for {@code AssociationRelationship} (boxed boolean);
 * {@code influenceStrength} for {@code InfluenceRelationship} (free text, omitted
 * when empty). Omitted from JSON for relationships of other subtypes.</p>
 */
public record RelationshipDto(
    String id,
    String name,
    String type,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String specialization,
    String sourceId,
    String targetId,
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    boolean alreadyExisted,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String documentation,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    List<Map<String, String>> properties,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String sourceName,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String targetName,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String accessType,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    Boolean associationDirected,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String influenceStrength
) {
    /**
     * Convenience constructor without optional fields (defaults to null/false).
     */
    public RelationshipDto(String id, String name, String type, String sourceId, String targetId) {
        this(id, name, type, null, sourceId, targetId, false, null, null, null, null,
                null, null, null);
    }

    /**
     * Constructor with alreadyExisted flag but no optional search fields.
     */
    public RelationshipDto(String id, String name, String type, String sourceId, String targetId,
                           boolean alreadyExisted) {
        this(id, name, type, null, sourceId, targetId, alreadyExisted, null, null, null, null,
                null, null, null);
    }

    /**
     * Back-compat constructor matching the prior 11-field canonical shape.
     * Delegates to the 14-field canonical with {@code null} for the 3 semantic-attribute fields.
     */
    public RelationshipDto(String id, String name, String type, String specialization,
                           String sourceId, String targetId, boolean alreadyExisted,
                           String documentation, List<Map<String, String>> properties,
                           String sourceName, String targetName) {
        this(id, name, type, specialization, sourceId, targetId, alreadyExisted,
                documentation, properties, sourceName, targetName,
                null, null, null);
    }
}
