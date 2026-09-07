package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tests for {@link ViewContentsDto} record.
 */
public class ViewContentsDtoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void shouldCreateWithAllFields() {
        ElementDto element = ElementDto.standard("e-1", "Actor", "BusinessActor", null, "Business", "doc", null);
        RelationshipDto rel = new RelationshipDto("r-1", "uses", "ServingRelationship", "e-1", "e-2");
        ViewNodeDto node = new ViewNodeDto("vo-1", "e-1", 100, 200, 120, 55);
        ViewConnectionDto conn = new ViewConnectionDto("vc-1", "r-1", "ServingRelationship",
                "vo-1", "vo-2", List.of(new BendpointDto(10, 0, -10, 0)));

        ViewContentsDto dto = new ViewContentsDto(
                "view-1", "Main View", "layered",
                List.of(element), List.of(rel), List.of(node), List.of(conn));

        assertEquals("view-1", dto.viewId());
        assertEquals("Main View", dto.viewName());
        assertEquals("layered", dto.viewpoint());
        assertEquals(1, dto.elements().size());
        assertEquals(1, dto.relationships().size());
        assertEquals(1, dto.visualMetadata().size());
        assertEquals(1, dto.connections().size());
        assertEquals("vc-1", dto.connections().get(0).viewConnectionId());
    }

    @Test
    public void shouldSupportNullViewpoint() {
        ViewContentsDto dto = new ViewContentsDto(
                "view-1", "Overview", null,
                List.of(), List.of(), List.of(), List.of());

        assertNull(dto.viewpoint());
    }

    @Test
    public void shouldSupportEmptyLists() {
        ViewContentsDto dto = new ViewContentsDto(
                "view-1", "Empty", null,
                List.of(), List.of(), List.of(), List.of());

        assertTrue(dto.elements().isEmpty());
        assertTrue(dto.relationships().isEmpty());
        assertTrue(dto.visualMetadata().isEmpty());
        assertTrue(dto.connections().isEmpty());
    }

    @Test
    public void shouldSupportEquality() {
        ViewContentsDto dto1 = new ViewContentsDto(
                "v-1", "View", "layered", List.of(), List.of(), List.of(), List.of());
        ViewContentsDto dto2 = new ViewContentsDto(
                "v-1", "View", "layered", List.of(), List.of(), List.of(), List.of());

        assertEquals(dto1, dto2);
        assertEquals(dto1.hashCode(), dto2.hashCode());
    }

    @Test
    public void shouldSupportConnectionsWithEmptyBendpoints() {
        ViewConnectionDto conn = new ViewConnectionDto("vc-1", "r-1", "ServingRelationship",
                "vo-1", "vo-2", List.of());

        ViewContentsDto dto = new ViewContentsDto(
                "view-1", "Test", null,
                List.of(), List.of(), List.of(), List.of(conn));

        assertEquals(1, dto.connections().size());
        assertTrue(dto.connections().get(0).bendpoints().isEmpty());
    }

    // ---- G16 AXIS C — images array ----

    @Test
    public void shouldSerialiseImagesArrayWhenSet() throws Exception {
        DiagramImageDto image = new DiagramImageDto(
                "img-vo-1", "images/abc.png", 100, 100, 64, 64,
                null, null, null);
        ViewContentsDto dto = new ViewContentsDto(
                "v-1", "Test View", null, null,
                List.of(), List.of(), List.of(), List.of(),
                null, null, List.of(image));

        String json = mapper.writeValueAsString(dto);
        assertTrue("images array should appear when populated",
                json.contains("\"images\""));
        assertTrue("image entry should serialise its imagePath",
                json.contains("\"imagePath\":\"images/abc.png\""));
    }

    @Test
    public void shouldOmitImagesArrayWhenNull() throws Exception {
        ViewContentsDto dto = new ViewContentsDto(
                "v-2", "Test View", null, null,
                List.of(), List.of(), List.of(), List.of(),
                null, null, null);

        String json = mapper.writeValueAsString(dto);
        assertFalse("images field should be omitted under NON_NULL when null",
                json.contains("images"));
    }

    @Test
    public void shouldPreserveBackCompat10ArgCtor() throws Exception {
        // Earlier callers used the 10-arg ctor; the 11th
        // `images` field was added via a NEW canonical ctor + preserved the 10-arg form
        // as a back-compat delegating ctor that passes null for images. JSON
        // for a view without image visuals must be byte-identical to its
        // earlier form.
        ViewContentsDto dto = new ViewContentsDto(
                "v-3", "Legacy View", "layered", "manual",
                List.of(), List.of(), List.of(), List.of(),
                null, null);

        // The new images field is null (NON_NULL omitted) — serialised JSON
        // should not contain the word "images".
        String json = mapper.writeValueAsString(dto);
        assertFalse("10-arg ctor should produce byte-identical pre-14-8 JSON "
                + "(images field omitted)",
                json.contains("\"images\""));
        assertNull(dto.images());
    }

    @Test
    public void shouldPreserveBackCompat7ArgCtor() throws Exception {
        // Earlier callers used the 7-arg ctor. It is preserved
        // by delegating with null for connectionRouterType, groups, notes,
        // AND images.
        ViewContentsDto dto = new ViewContentsDto(
                "v-4", "Older Legacy View", "layered",
                List.of(), List.of(), List.of(), List.of());

        String json = mapper.writeValueAsString(dto);
        assertFalse("7-arg ctor should produce no images key",
                json.contains("\"images\""));
        assertFalse("7-arg ctor should produce no groups key",
                json.contains("\"groups\""));
        assertFalse("7-arg ctor should produce no notes key",
                json.contains("\"notes\""));
        assertFalse("7-arg ctor should produce no connectionRouterType key",
                json.contains("connectionRouterType"));
        assertNull(dto.images());
        assertNull(dto.groups());
        assertNull(dto.notes());
        assertNull(dto.connectionRouterType());
    }
}
