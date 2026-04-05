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
 * and {@code targetName} are optional — populated only for search results
 * with the {@code full} field preset. They are omitted from JSON when null.</p>
 */
public record RelationshipDto(
    String id,
    String name,
    String type,
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
    String targetName
) {
    /**
     * Convenience constructor without optional fields (defaults to null/false).
     */
    public RelationshipDto(String id, String name, String type, String sourceId, String targetId) {
        this(id, name, type, sourceId, targetId, false, null, null, null, null);
    }

    /**
     * Constructor with alreadyExisted flag but no optional search fields.
     */
    public RelationshipDto(String id, String name, String type, String sourceId, String targetId,
                           boolean alreadyExisted) {
        this(id, name, type, sourceId, targetId, alreadyExisted, null, null, null, null);
    }
}
