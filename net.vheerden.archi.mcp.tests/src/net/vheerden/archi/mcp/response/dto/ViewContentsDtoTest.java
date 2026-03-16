package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Tests for {@link ViewContentsDto} record.
 */
public class ViewContentsDtoTest {

    @Test
    public void shouldCreateWithAllFields() {
        ElementDto element = ElementDto.standard("e-1", "Actor", "BusinessActor", "Business", "doc", null);
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
}
