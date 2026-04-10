package net.vheerden.archi.mcp.response.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Data Transfer Object for ArchiMate elements.
 *
 * <p>This DTO provides a consistent shape for element data across all
 * MCP commands. The same fields appear whether the element comes from
 * get-element, search-elements, or get-view-contents.</p>
 *
 * <p>Standard field level (MVP default):</p>
 * <ul>
 *   <li>id - unique element identifier</li>
 *   <li>name - element display name</li>
 *   <li>type - ArchiMate element type (e.g., "ApplicationComponent")</li>
 *   <li>specialization - primary specialization name (null if none)</li>
 *   <li>layer - ArchiMate layer (e.g., "Application", "Technology")</li>
 *   <li>documentation - element description/documentation</li>
 *   <li>properties - custom key-value properties</li>
 * </ul>
 *
 * <p>JSON serialization uses Jackson with camelCase field names.
 * Null fields are omitted from output.</p>
 */
public record ElementDto(
    String id,
    String name,
    String type,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String specialization,
    String layer,
    String documentation,
    List<Map<String, String>> properties
) {

    /**
     * Creates an ElementDto with minimal fields (id and name only).
     * Used for minimal field level preset.
     */
    public static ElementDto minimal(String id, String name) {
        return new ElementDto(id, name, null, null, null, null, null);
    }

    /**
     * Creates an ElementDto with standard fields.
     * Used for standard field level preset (MVP default).
     */
    public static ElementDto standard(String id, String name, String type,
                                       String specialization, String layer,
                                       String documentation,
                                       List<Map<String, String>> properties) {
        return new ElementDto(id, name, type, specialization, layer, documentation, properties);
    }
}
