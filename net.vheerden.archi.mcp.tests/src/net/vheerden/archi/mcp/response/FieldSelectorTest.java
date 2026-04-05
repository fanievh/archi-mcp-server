package net.vheerden.archi.mcp.response;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import net.vheerden.archi.mcp.response.FieldSelector.FieldPreset;
import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.ModelInfoDto;
import net.vheerden.archi.mcp.response.dto.RelationshipDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;
import net.vheerden.archi.mcp.response.dto.ViewContentsDto;
import net.vheerden.archi.mcp.response.dto.ViewDto;
import net.vheerden.archi.mcp.response.dto.ViewNodeDto;

/**
 * Tests for {@link FieldSelector} — field selection and exclude filtering (Story 5.2).
 */
public class FieldSelectorTest {

    // ---- FieldPreset.fromString ----

    @Test
    public void shouldParseMinimalPreset() {
        assertTrue(FieldPreset.fromString("minimal").isPresent());
        assertEquals(FieldPreset.MINIMAL, FieldPreset.fromString("minimal").get());
    }

    @Test
    public void shouldParseStandardPreset() {
        assertTrue(FieldPreset.fromString("standard").isPresent());
        assertEquals(FieldPreset.STANDARD, FieldPreset.fromString("standard").get());
    }

    @Test
    public void shouldParseFullPreset() {
        assertTrue(FieldPreset.fromString("full").isPresent());
        assertEquals(FieldPreset.FULL, FieldPreset.fromString("full").get());
    }

    @Test
    public void shouldParseCaseInsensitive() {
        assertTrue(FieldPreset.fromString("MINIMAL").isPresent());
        assertTrue(FieldPreset.fromString("Standard").isPresent());
        assertTrue(FieldPreset.fromString("FULL").isPresent());
    }

    @Test
    public void shouldReturnEmptyForInvalidPreset() {
        assertTrue(FieldPreset.fromString("compact").isEmpty());
        assertTrue(FieldPreset.fromString("").isEmpty());
        assertTrue(FieldPreset.fromString(null).isEmpty());
    }

    // ---- Element field selection ----

    @Test
    public void shouldReturnMinimalFields_whenMinimalPreset() {
        ElementDto element = new ElementDto("e1", "Test Element", "ApplicationComponent",
                "Application", "Some docs", List.of(Map.of("key", "val")));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) FieldSelector.applyFieldSelection(
                element, FieldPreset.MINIMAL, null);

        assertEquals(2, result.size());
        assertEquals("e1", result.get("id"));
        assertEquals("Test Element", result.get("name"));
        assertNull(result.get("type"));
        assertNull(result.get("documentation"));
    }

    @Test
    public void shouldReturnStandardFields_whenStandardPreset() {
        ElementDto element = new ElementDto("e1", "Test Element", "ApplicationComponent",
                "Application", "Some docs", List.of(Map.of("key", "val")));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) FieldSelector.applyFieldSelection(
                element, FieldPreset.STANDARD, null);

        assertEquals(6, result.size());
        assertEquals("e1", result.get("id"));
        assertEquals("Test Element", result.get("name"));
        assertEquals("ApplicationComponent", result.get("type"));
        assertEquals("Application", result.get("layer"));
        assertEquals("Some docs", result.get("documentation"));
        assertNotNull(result.get("properties"));
    }

    @Test
    public void shouldReturnFullFields_whenFullPreset() {
        ElementDto element = new ElementDto("e1", "Test Element", "ApplicationComponent",
                "Application", "Some docs", List.of(Map.of("key", "val")));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) FieldSelector.applyFieldSelection(
                element, FieldPreset.FULL, null);

        // FULL currently same as STANDARD
        assertEquals(6, result.size());
        assertEquals("e1", result.get("id"));
    }

    // ---- Exclude patterns ----

    @Test
    public void shouldExcludeDocumentation_whenExcludeContainsDocumentation() {
        ElementDto element = new ElementDto("e1", "Test", "AppComp",
                "Application", "Remove this", null);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) FieldSelector.applyFieldSelection(
                element, FieldPreset.STANDARD, Set.of("documentation"));

        assertNull(result.get("documentation"));
        assertEquals("e1", result.get("id"));
        assertEquals("Test", result.get("name"));
        assertEquals("AppComp", result.get("type"));
    }

    @Test
    public void shouldExcludeProperties_whenExcludeContainsProperties() {
        ElementDto element = new ElementDto("e1", "Test", "AppComp",
                "Application", "Docs", List.of(Map.of("k", "v")));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) FieldSelector.applyFieldSelection(
                element, FieldPreset.STANDARD, Set.of("properties"));

        assertNull(result.get("properties"));
        assertEquals("Docs", result.get("documentation"));
    }

    @Test
    public void shouldExcludeMultipleFields_whenMultipleExcludeProvided() {
        ElementDto element = new ElementDto("e1", "Test", "AppComp",
                "Application", "Docs", List.of(Map.of("k", "v")));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) FieldSelector.applyFieldSelection(
                element, FieldPreset.STANDARD, Set.of("documentation", "properties", "layer"));

        assertEquals(3, result.size()); // id, name, type
        assertEquals("e1", result.get("id"));
        assertEquals("Test", result.get("name"));
        assertEquals("AppComp", result.get("type"));
        assertNull(result.get("documentation"));
        assertNull(result.get("properties"));
        assertNull(result.get("layer"));
    }

    @Test
    public void shouldCombinePresetAndExclude_whenBothProvided() {
        ElementDto element = new ElementDto("e1", "Test", "AppComp",
                "Application", "Docs", List.of(Map.of("k", "v")));

        // Standard preset + exclude documentation → 5 fields (all except documentation)
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) FieldSelector.applyFieldSelection(
                element, FieldPreset.STANDARD, Set.of("documentation"));

        assertEquals(5, result.size());
        assertNull(result.get("documentation"));
        assertNotNull(result.get("properties"));
    }

    // ---- Protected fields ----

    @Test
    public void shouldNeverExcludeId_whenIdInExcludeList() {
        ElementDto element = new ElementDto("e1", "Test", "AppComp", null, null, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) FieldSelector.applyFieldSelection(
                element, FieldPreset.STANDARD, Set.of("id"));

        assertEquals("e1", result.get("id")); // Protected — still present
    }

    @Test
    public void shouldNeverExcludeName_whenNameInExcludeList() {
        ElementDto element = new ElementDto("e1", "Test", "AppComp", null, null, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) FieldSelector.applyFieldSelection(
                element, FieldPreset.MINIMAL, Set.of("name"));

        assertEquals("Test", result.get("name")); // Protected — still present
    }

    // ---- List handling ----

    @Test
    @SuppressWarnings("unchecked")
    public void shouldHandleElementDtoList_whenListProvided() {
        List<ElementDto> elements = List.of(
                new ElementDto("e1", "First", "AppComp", "Application", "Doc1", null),
                new ElementDto("e2", "Second", "Node", "Technology", "Doc2", null));

        List<Object> result = (List<Object>) FieldSelector.applyFieldSelection(
                elements, FieldPreset.MINIMAL, null);

        assertEquals(2, result.size());
        Map<String, Object> first = (Map<String, Object>) result.get(0);
        assertEquals("e1", first.get("id"));
        assertEquals("First", first.get("name"));
        assertEquals(2, first.size()); // Only id and name for MINIMAL
    }

    // ---- ViewDto ----

    @Test
    public void shouldHandleViewDto_whenViewDtoProvided() {
        ViewDto view = new ViewDto("v1", "My View", "Application Usage", "/folder/path");

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) FieldSelector.applyFieldSelection(
                view, FieldPreset.MINIMAL, null);

        assertEquals(2, result.size());
        assertEquals("v1", result.get("id"));
        assertEquals("My View", result.get("name"));
        assertNull(result.get("viewpointType"));
    }

    @Test
    public void shouldReturnStandardViewFields_whenStandardPreset() {
        ViewDto view = new ViewDto("v1", "My View", "Application Usage", "/folder/path");

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) FieldSelector.applyFieldSelection(
                view, FieldPreset.STANDARD, null);

        assertEquals(4, result.size());
        assertEquals("v1", result.get("id"));
        assertEquals("Application Usage", result.get("viewpointType"));
        assertEquals("/folder/path", result.get("folderPath"));
    }

    @Test
    public void shouldReturnFullViewFields_whenFullPreset() {
        ViewDto view = new ViewDto("v1", "My View", "Application Usage", null, "/folder/path",
                "Some docs", Map.of("status", "active"));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) FieldSelector.applyFieldSelection(
                view, FieldPreset.FULL, null);

        assertEquals(6, result.size());
        assertEquals("v1", result.get("id"));
        assertEquals("Application Usage", result.get("viewpointType"));
        assertEquals("/folder/path", result.get("folderPath"));
        assertEquals("Some docs", result.get("documentation"));
        assertNotNull(result.get("properties"));
    }

    @Test
    public void shouldIncludeConnectionRouterType_whenStandardPresetAndManhattan() {
        ViewDto view = new ViewDto("v1", "My View", "Application Usage", "manhattan",
                "/folder/path", null, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) FieldSelector.applyFieldSelection(
                view, FieldPreset.STANDARD, null);

        assertEquals(5, result.size());
        assertEquals("manhattan", result.get("connectionRouterType"));
    }

    @Test
    public void shouldExcludeConnectionRouterType_whenExcludeSpecified() {
        ViewDto view = new ViewDto("v1", "My View", "Application Usage", "manhattan",
                "/folder/path", "Docs", Map.of("k", "v"));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) FieldSelector.applyFieldSelection(
                view, FieldPreset.FULL, Set.of("connectionRouterType"));

        assertNull(result.get("connectionRouterType"));
        assertNotNull(result.get("viewpointType"));
    }

    @Test
    public void shouldOmitDocAndProps_whenStandardPresetWithFullViewDto() {
        ViewDto view = new ViewDto("v1", "My View", "Application Usage", null, "/folder/path",
                "Some docs", Map.of("status", "active"));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) FieldSelector.applyFieldSelection(
                view, FieldPreset.STANDARD, null);

        assertEquals(4, result.size());
        assertNull(result.get("documentation"));
        assertNull(result.get("properties"));
    }

    // ---- ViewContentsDto ----

    @Test
    @SuppressWarnings("unchecked")
    public void shouldHandleViewContentsDto_whenViewContentsDtoProvided() {
        List<ElementDto> elements = List.of(
                new ElementDto("e1", "Elem", "AppComp", "Application", "Doc", null));
        List<RelationshipDto> rels = List.of(
                new RelationshipDto("r1", "Rel", "ServingRelationship", "e1", "e2"));
        List<ViewNodeDto> nodes = List.of(
                new ViewNodeDto("vo-1", "e1", 10, 20, 100, 50));

        List<ViewConnectionDto> connections = List.of(
                new ViewConnectionDto("vc-1", "r1", "ServingRelationship", "vo-1", "vo-2", List.of()));
        ViewContentsDto contents = new ViewContentsDto("v1", "View1", "Usage", elements, rels, nodes, connections);

        Map<String, Object> result = (Map<String, Object>) FieldSelector.applyFieldSelection(
                contents, FieldPreset.MINIMAL, null);

        assertEquals("v1", result.get("viewId"));
        assertEquals("View1", result.get("viewName"));

        List<Object> filteredElements = (List<Object>) result.get("elements");
        assertEquals(1, filteredElements.size());
        Map<String, Object> elem = (Map<String, Object>) filteredElements.get(0);
        assertEquals(2, elem.size()); // MINIMAL: id + name only
        assertEquals("e1", elem.get("id"));

        // connections included by default
        assertNotNull(result.get("connections"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldExcludeVisualMetadata_whenExcludeContainsVisualMetadata() {
        List<ElementDto> elements = List.of(
                new ElementDto("e1", "Elem", "AppComp", "Application", null, null));
        List<ViewNodeDto> nodes = List.of(new ViewNodeDto("vo-1", "e1", 10, 20, 100, 50));

        ViewContentsDto contents = new ViewContentsDto("v1", "View1", null, elements, List.of(), nodes, List.of());

        Map<String, Object> result = (Map<String, Object>) FieldSelector.applyFieldSelection(
                contents, FieldPreset.STANDARD, Set.of("visualMetadata"));

        assertNull(result.get("visualMetadata"));
        assertNotNull(result.get("elements"));
        assertNotNull(result.get("connections")); // connections NOT excluded
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldExcludeConnections_whenExcludeContainsConnections() {
        List<ElementDto> elements = List.of(
                new ElementDto("e1", "Elem", "AppComp", "Application", null, null));
        List<ViewConnectionDto> connections = List.of(
                new ViewConnectionDto("vc-1", "r1", "ServingRelationship", "vo-1", "vo-2", List.of()));

        ViewContentsDto contents = new ViewContentsDto("v1", "View1", null, elements, List.of(), List.of(), connections);

        Map<String, Object> result = (Map<String, Object>) FieldSelector.applyFieldSelection(
                contents, FieldPreset.STANDARD, Set.of("connections"));

        assertNull(result.get("connections"));
        assertNotNull(result.get("elements"));
        assertNotNull(result.get("visualMetadata"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldExcludeBothVisualMetadataAndConnections() {
        List<ElementDto> elements = List.of(
                new ElementDto("e1", "Elem", "AppComp", "Application", null, null));
        List<ViewNodeDto> nodes = List.of(new ViewNodeDto("vo-1", "e1", 10, 20, 100, 50));
        List<ViewConnectionDto> connections = List.of(
                new ViewConnectionDto("vc-1", "r1", "ServingRelationship", "vo-1", "vo-2", List.of()));

        ViewContentsDto contents = new ViewContentsDto("v1", "View1", null, elements, List.of(), nodes, connections);

        Map<String, Object> result = (Map<String, Object>) FieldSelector.applyFieldSelection(
                contents, FieldPreset.STANDARD, Set.of("visualMetadata", "connections"));

        assertNull(result.get("visualMetadata"));
        assertNull(result.get("connections"));
        assertNotNull(result.get("elements"));
    }

    // ---- ModelInfoDto (exempt) ----

    @Test
    public void shouldReturnModelInfoUnchanged_whenModelInfoDtoProvided() {
        ModelInfoDto info = new ModelInfoDto("TestModel", 100, 50, 10,
                Map.of("ApplicationComponent", 30, "Node", 20),
                Map.of("ServingRelationship", 25, "FlowRelationship", 25),
                Map.of("Application", 30, "Technology", 20));

        Object result = FieldSelector.applyFieldSelection(info, FieldPreset.MINIMAL, null);

        assertSame(info, result); // Returned as-is, not filtered
    }

    // ---- Map input (TraversalHandler depth expansion) ----

    @Test
    @SuppressWarnings("unchecked")
    public void shouldHandleMapInput_whenTraversalExpandedMap() {
        Map<String, Object> elementMap = new java.util.LinkedHashMap<>();
        elementMap.put("id", "e1");
        elementMap.put("name", "Test");
        elementMap.put("type", "AppComp");
        elementMap.put("documentation", "Remove this");

        Map<String, Object> result = (Map<String, Object>) FieldSelector.applyFieldSelection(
                elementMap, FieldPreset.STANDARD, Set.of("documentation"));

        assertNull(result.get("documentation"));
        assertEquals("e1", result.get("id"));
        assertEquals("Test", result.get("name"));
    }

    // ---- Unknown type ----

    @Test
    public void shouldReturnAsIs_whenUnknownType() {
        String unknown = "not a DTO";
        Object result = FieldSelector.applyFieldSelection(unknown, FieldPreset.STANDARD, null);
        assertSame(unknown, result);
    }

    // ---- Null handling ----

    @Test
    public void shouldReturnNull_whenNullInput() {
        assertNull(FieldSelector.applyFieldSelection(null, FieldPreset.STANDARD, null));
    }

    @Test
    public void shouldDefaultToStandard_whenNullPreset() {
        ElementDto element = new ElementDto("e1", "Test", "AppComp",
                "Application", "Docs", null);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) FieldSelector.applyFieldSelection(
                element, null, null);

        // null preset defaults to STANDARD
        assertEquals(5, result.size()); // id, name, type, layer, documentation (no properties — null)
        assertNotNull(result.get("type"));
        assertNotNull(result.get("layer"));
    }

    // ---- DTO to Map conversions ----

    @Test
    public void shouldConvertElementDtoToMap_withNonNullFields() {
        ElementDto dto = new ElementDto("e1", "Test", "AppComp",
                "Application", "Docs", List.of(Map.of("k", "v")));

        Map<String, Object> map = FieldSelector.elementDtoToMap(dto);
        assertEquals(6, map.size());
        assertEquals("e1", map.get("id"));
        assertEquals("Test", map.get("name"));
    }

    @Test
    public void shouldOmitNullFieldsFromElementDtoMap() {
        ElementDto dto = new ElementDto("e1", "Test", null, null, null, null);

        Map<String, Object> map = FieldSelector.elementDtoToMap(dto);
        assertEquals(2, map.size()); // Only id and name
        assertFalse(map.containsKey("type"));
        assertFalse(map.containsKey("layer"));
    }

    @Test
    public void shouldConvertRelationshipDtoToMap() {
        RelationshipDto dto = new RelationshipDto("r1", "Flow", "FlowRelationship", "e1", "e2");

        Map<String, Object> map = FieldSelector.relationshipDtoToMap(dto);
        assertEquals(5, map.size());
        assertEquals("r1", map.get("id"));
        assertEquals("FlowRelationship", map.get("type"));
        assertEquals("e1", map.get("sourceId"));
    }

    @Test
    public void shouldConvertViewDtoToMap() {
        ViewDto dto = new ViewDto("v1", "View", "Usage", "/path");

        Map<String, Object> map = FieldSelector.viewDtoToMap(dto);
        assertEquals(4, map.size());
        assertEquals("v1", map.get("id"));
        assertEquals("Usage", map.get("viewpointType"));
    }

    @Test
    public void shouldConvertFullViewDtoToMap() {
        ViewDto dto = new ViewDto("v1", "View", "Usage", null, "/path",
                "My docs", Map.of("key", "val"));

        Map<String, Object> map = FieldSelector.viewDtoToMap(dto);
        assertEquals(6, map.size());
        assertEquals("My docs", map.get("documentation"));
        assertNotNull(map.get("properties"));
    }

    // ---- filterMap ----

    @Test
    public void shouldFilterMapByIncludeFields() {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", "e1");
        map.put("name", "Test");
        map.put("type", "AppComp");
        map.put("documentation", "Docs");

        FieldSelector.filterMap(map, Set.of("id", "name"), null);
        assertEquals(2, map.size());
        assertEquals("e1", map.get("id"));
        assertEquals("Test", map.get("name"));
    }

    @Test
    public void shouldReturnNullFromFilterMap_whenNullInput() {
        assertNull(FieldSelector.filterMap(null, null, null));
    }

    // ---- VALID_EXCLUDE_FIELDS ----

    @Test
    public void shouldContainAllValidExcludeFields() {
        Set<String> expected = Set.of("documentation", "properties", "layer", "type",
                "viewpointType", "connectionRouterType", "folderPath",
                "visualMetadata", "connections", "groups", "notes");
        assertEquals(expected, FieldSelector.VALID_EXCLUDE_FIELDS);
    }

    @Test
    public void shouldContainProtectedFields() {
        assertEquals(Set.of("id", "name"), FieldSelector.ALWAYS_INCLUDED_FIELDS);
    }
}
