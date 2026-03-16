package net.vheerden.archi.mcp.response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.FolderDto;
import net.vheerden.archi.mcp.response.dto.FolderTreeDto;
import net.vheerden.archi.mcp.response.dto.RelationshipDto;
import net.vheerden.archi.mcp.response.dto.ViewContentsDto;
import net.vheerden.archi.mcp.response.dto.ViewDto;

/**
 * Stateless utility for field selection and exclude filtering on DTOs (Story 5.2, FR17/FR18).
 *
 * <p>Transforms DTO records into {@code Map<String, Object>} with only the
 * requested fields, then removes excluded fields. Records are immutable so
 * we cannot selectively null out fields in-place.</p>
 *
 * <p>The flow is: DTO &rarr; Map (full) &rarr; retain preset fields &rarr; remove excludes.</p>
 *
 * <p>{@code id} and {@code name} are <em>always</em> included regardless of
 * preset or exclude list (protected fields).</p>
 *
 * <p><strong>Architecture boundary:</strong> This class imports only DTO types
 * from {@code response/dto/}. It has no handler, model, or session dependencies.</p>
 */
public final class FieldSelector {

    private FieldSelector() {
        // Static utility — no instantiation
    }

    // ---- FieldPreset enum ----

    /**
     * Controls which fields are included in element/view responses.
     */
    public enum FieldPreset {
        /** Only id and name. */
        MINIMAL("minimal"),
        /** id, name, type, layer, documentation, properties (default). */
        STANDARD("standard"),
        /** All available fields. */
        FULL("full");

        private final String value;

        FieldPreset(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        /**
         * Parses a preset string, returning empty for invalid values.
         *
         * @param value the preset string (case-insensitive)
         * @return the matching preset, or empty if invalid
         */
        public static Optional<FieldPreset> fromString(String value) {
            if (value == null) {
                return Optional.empty();
            }
            for (FieldPreset preset : values()) {
                if (preset.value.equalsIgnoreCase(value)) {
                    return Optional.of(preset);
                }
            }
            return Optional.empty();
        }
    }

    // ---- Field constants ----

    /** Fields that can never be excluded. */
    public static final Set<String> ALWAYS_INCLUDED_FIELDS = Set.of("id", "name");

    /** Valid field names that may appear in an exclude list. */
    public static final Set<String> VALID_EXCLUDE_FIELDS = Set.of(
            "documentation", "properties", "layer", "type",
            "viewpointType", "connectionRouterType", "folderPath",
            "visualMetadata", "connections");

    // ---- Preset field sets per DTO type ----

    private static final Set<String> ELEMENT_MINIMAL = Set.of("id", "name");
    private static final Set<String> ELEMENT_STANDARD = Set.of(
            "id", "name", "type", "layer", "documentation", "properties");
    private static final Set<String> ELEMENT_FULL = ELEMENT_STANDARD; // no extra fields yet

    private static final Set<String> VIEW_MINIMAL = Set.of("id", "name");
    private static final Set<String> VIEW_STANDARD = Set.of(
            "id", "name", "viewpointType", "connectionRouterType", "folderPath");
    private static final Set<String> VIEW_FULL = Set.of(
            "id", "name", "viewpointType", "connectionRouterType", "folderPath",
            "documentation", "properties");

    private static final Set<String> FOLDER_MINIMAL = Set.of("id", "name");
    private static final Set<String> FOLDER_STANDARD = Set.of(
            "id", "name", "type", "path", "elementCount", "subfolderCount");
    private static final Set<String> FOLDER_FULL = FOLDER_STANDARD; // no extra fields yet

    // Relationships: all fields always included (no optional fields currently)
    private static final Set<String> RELATIONSHIP_ALL = Set.of(
            "id", "name", "type", "sourceId", "targetId");

    // ---- DTO to Map conversions ----

    /**
     * Converts an {@link ElementDto} to a mutable map with all non-null fields.
     *
     * @param dto the element DTO
     * @return a linked hash map preserving field order
     */
    public static Map<String, Object> elementDtoToMap(ElementDto dto) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", dto.id());
        map.put("name", dto.name());
        if (dto.type() != null) map.put("type", dto.type());
        if (dto.layer() != null) map.put("layer", dto.layer());
        if (dto.documentation() != null) map.put("documentation", dto.documentation());
        if (dto.properties() != null && !dto.properties().isEmpty()) map.put("properties", dto.properties());
        return map;
    }

    /**
     * Converts a {@link RelationshipDto} to a mutable map with all non-null fields.
     *
     * @param dto the relationship DTO
     * @return a linked hash map preserving field order
     */
    public static Map<String, Object> relationshipDtoToMap(RelationshipDto dto) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", dto.id());
        map.put("name", dto.name());
        if (dto.type() != null) map.put("type", dto.type());
        if (dto.sourceId() != null) map.put("sourceId", dto.sourceId());
        if (dto.targetId() != null) map.put("targetId", dto.targetId());
        return map;
    }

    /**
     * Converts a {@link FolderDto} to a mutable map with all non-null fields.
     *
     * @param dto the folder DTO
     * @return a linked hash map preserving field order
     */
    public static Map<String, Object> folderDtoToMap(FolderDto dto) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", dto.id());
        map.put("name", dto.name());
        if (dto.type() != null) map.put("type", dto.type());
        if (dto.path() != null) map.put("path", dto.path());
        map.put("elementCount", dto.elementCount());
        map.put("subfolderCount", dto.subfolderCount());
        return map;
    }

    /**
     * Converts a {@link FolderTreeDto} to a mutable map with all non-null fields.
     * Children are recursively converted.
     *
     * @param dto     the folder tree DTO
     * @param preset  the field preset to apply to this node and children
     * @param exclude fields to exclude
     * @return a linked hash map preserving field order
     */
    public static Map<String, Object> folderTreeDtoToMap(FolderTreeDto dto,
                                                          FieldPreset preset,
                                                          Set<String> exclude) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", dto.id());
        map.put("name", dto.name());
        if (dto.type() != null) map.put("type", dto.type());
        if (dto.path() != null) map.put("path", dto.path());
        map.put("elementCount", dto.elementCount());
        map.put("subfolderCount", dto.subfolderCount());

        // Apply preset filtering on this node's flat fields
        Set<String> includeFields = folderFieldsForPreset(preset);
        filterMap(map, includeFields, exclude);

        // Recurse into children
        if (dto.children() != null && !dto.children().isEmpty()) {
            List<Object> childMaps = new ArrayList<>();
            for (FolderTreeDto child : dto.children()) {
                childMaps.add(folderTreeDtoToMap(child, preset, exclude));
            }
            map.put("children", childMaps);
        }
        return map;
    }

    /**
     * Converts a {@link ViewDto} to a mutable map with all non-null fields.
     *
     * @param dto the view DTO
     * @return a linked hash map preserving field order
     */
    public static Map<String, Object> viewDtoToMap(ViewDto dto) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", dto.id());
        map.put("name", dto.name());
        if (dto.viewpointType() != null) map.put("viewpointType", dto.viewpointType());
        if (dto.connectionRouterType() != null) map.put("connectionRouterType", dto.connectionRouterType());
        if (dto.folderPath() != null) map.put("folderPath", dto.folderPath());
        if (dto.documentation() != null) map.put("documentation", dto.documentation());
        if (dto.properties() != null && !dto.properties().isEmpty()) map.put("properties", dto.properties());
        return map;
    }

    // ---- Field filtering ----

    /**
     * Filters a map to retain only the fields in {@code includeFields},
     * then removes any fields in {@code excludeFields}.
     * {@link #ALWAYS_INCLUDED_FIELDS} are never removed.
     *
     * @param map            the map to filter (modified in place)
     * @param includeFields  fields to retain (null = retain all)
     * @param excludeFields  fields to remove after include filtering (null/empty = no exclusions)
     * @return the filtered map (same instance)
     */
    public static Map<String, Object> filterMap(Map<String, Object> map,
                                                  Set<String> includeFields,
                                                  Set<String> excludeFields) {
        if (map == null) {
            return null;
        }

        // Step 1: retain only includeFields (if specified)
        if (includeFields != null) {
            map.keySet().retainAll(includeFields);
        }

        // Step 2: remove excludeFields (if specified), but never remove protected fields
        if (excludeFields != null && !excludeFields.isEmpty()) {
            for (String field : excludeFields) {
                if (!ALWAYS_INCLUDED_FIELDS.contains(field)) {
                    map.remove(field);
                }
            }
        }

        return map;
    }

    // ---- Main public API ----

    /**
     * Applies field selection to a DTO or list of DTOs.
     *
     * <p>Converts DTOs to maps, applies the preset's included-fields filter,
     * then removes any excluded fields. For container types like {@link ViewContentsDto},
     * the nested elements and relationships are filtered individually.</p>
     *
     * <p>If {@code preset} is null, {@link FieldPreset#STANDARD} is used.
     * If {@code excludeFields} is null or empty, no exclusions are applied.</p>
     *
     * @param dtoOrList     a DTO record, a List of DTO records, or a Map (pass-through)
     * @param preset        the field verbosity preset, or null for STANDARD
     * @param excludeFields fields to exclude after preset filtering, or null
     * @return the filtered result (Map, List of Maps, or original object if unrecognized)
     */
    @SuppressWarnings("unchecked")
    public static Object applyFieldSelection(Object dtoOrList, FieldPreset preset,
                                              Set<String> excludeFields) {
        if (dtoOrList == null) {
            return null;
        }

        FieldPreset effectivePreset = (preset != null) ? preset : FieldPreset.STANDARD;

        if (dtoOrList instanceof List<?> list) {
            return applyToList(list, effectivePreset, excludeFields);
        }
        if (dtoOrList instanceof ElementDto dto) {
            return applyToElement(dto, effectivePreset, excludeFields);
        }
        if (dtoOrList instanceof RelationshipDto dto) {
            return applyToRelationship(dto, excludeFields);
        }
        if (dtoOrList instanceof ViewDto dto) {
            return applyToView(dto, effectivePreset, excludeFields);
        }
        if (dtoOrList instanceof FolderTreeDto dto) {
            return folderTreeDtoToMap(dto, effectivePreset, excludeFields);
        }
        if (dtoOrList instanceof FolderDto dto) {
            return applyToFolder(dto, effectivePreset, excludeFields);
        }
        if (dtoOrList instanceof ViewContentsDto dto) {
            return applyToViewContents(dto, effectivePreset, excludeFields);
        }
        if (dtoOrList instanceof Map<?, ?> map) {
            // Already a map — apply exclude only (TraversalHandler depth expansion maps)
            return filterMap((Map<String, Object>) map, null, excludeFields);
        }
        // Unknown type (e.g., ModelInfoDto) — return as-is
        return dtoOrList;
    }

    // ---- Private helpers ----

    private static List<Object> applyToList(List<?> list, FieldPreset preset,
                                             Set<String> excludeFields) {
        List<Object> result = new ArrayList<>(list.size());
        for (Object item : list) {
            result.add(applyFieldSelection(item, preset, excludeFields));
        }
        return result;
    }

    private static Map<String, Object> applyToElement(ElementDto dto, FieldPreset preset,
                                                        Set<String> excludeFields) {
        Map<String, Object> map = elementDtoToMap(dto);
        Set<String> includeFields = elementFieldsForPreset(preset);
        return filterMap(map, includeFields, excludeFields);
    }

    private static Map<String, Object> applyToRelationship(RelationshipDto dto,
                                                              Set<String> excludeFields) {
        Map<String, Object> map = relationshipDtoToMap(dto);
        // Relationships always include all fields; only exclude applies (future-proof)
        return filterMap(map, RELATIONSHIP_ALL, excludeFields);
    }

    private static Map<String, Object> applyToView(ViewDto dto, FieldPreset preset,
                                                     Set<String> excludeFields) {
        Map<String, Object> map = viewDtoToMap(dto);
        Set<String> includeFields = viewFieldsForPreset(preset);
        return filterMap(map, includeFields, excludeFields);
    }

    private static Map<String, Object> applyToViewContents(ViewContentsDto dto,
                                                             FieldPreset preset,
                                                             Set<String> excludeFields) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("viewId", dto.viewId());
        map.put("viewName", dto.viewName());
        if (dto.viewpoint() != null) {
            map.put("viewpoint", dto.viewpoint());
        }
        if (dto.connectionRouterType() != null) {
            map.put("connectionRouterType", dto.connectionRouterType());
        }

        // Apply field selection to nested elements
        if (dto.elements() != null) {
            map.put("elements", applyToList(dto.elements(), preset, excludeFields));
        }

        // Apply field selection to nested relationships
        if (dto.relationships() != null) {
            map.put("relationships", applyToList(dto.relationships(), preset, excludeFields));
        }

        // visualMetadata: include unless excluded
        if (dto.visualMetadata() != null) {
            if (excludeFields == null || !excludeFields.contains("visualMetadata")) {
                map.put("visualMetadata", dto.visualMetadata());
            }
        }

        // connections: include unless excluded
        if (dto.connections() != null) {
            if (excludeFields == null || !excludeFields.contains("connections")) {
                map.put("connections", dto.connections());
            }
        }

        return map;
    }

    private static Set<String> elementFieldsForPreset(FieldPreset preset) {
        return switch (preset) {
            case MINIMAL -> ELEMENT_MINIMAL;
            case STANDARD -> ELEMENT_STANDARD;
            case FULL -> ELEMENT_FULL;
        };
    }

    private static Set<String> viewFieldsForPreset(FieldPreset preset) {
        return switch (preset) {
            case MINIMAL -> VIEW_MINIMAL;
            case STANDARD -> VIEW_STANDARD;
            case FULL -> VIEW_FULL;
        };
    }

    private static Map<String, Object> applyToFolder(FolderDto dto, FieldPreset preset,
                                                       Set<String> excludeFields) {
        Map<String, Object> map = folderDtoToMap(dto);
        Set<String> includeFields = folderFieldsForPreset(preset);
        return filterMap(map, includeFields, excludeFields);
    }

    private static Set<String> folderFieldsForPreset(FieldPreset preset) {
        return switch (preset) {
            case MINIMAL -> FOLDER_MINIMAL;
            case STANDARD, FULL -> FOLDER_STANDARD;
        };
    }

    /**
     * Returns an unmodifiable set of valid exclude field names.
     * Used by handlers and SessionManager for validation.
     *
     * @return the set of valid exclude field names
     */
    public static Set<String> getValidExcludeFields() {
        return VALID_EXCLUDE_FIELDS;
    }
}
