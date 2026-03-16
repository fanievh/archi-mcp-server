package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

/**
 * Tests for {@link ViewGroupDto} record.
 */
public class ViewGroupDtoTest {

    @Test
    public void shouldCreateWithAllFields() {
        List<String> children = List.of("child-1", "child-2");
        ViewGroupDto dto = new ViewGroupDto(
                "vo-group-1", "Application Tier",
                50, 100, 400, 300,
                "vo-parent-1", children);

        assertEquals("vo-group-1", dto.viewObjectId());
        assertEquals("Application Tier", dto.label());
        assertEquals(50, dto.x());
        assertEquals(100, dto.y());
        assertEquals(400, dto.width());
        assertEquals(300, dto.height());
        assertEquals("vo-parent-1", dto.parentViewObjectId());
        assertEquals(List.of("child-1", "child-2"), dto.childViewObjectIds());
    }

    @Test
    public void shouldSupportNullOptionalFields() {
        ViewGroupDto dto = new ViewGroupDto(
                "vo-group-2", "Ungrouped",
                0, 0, 200, 150,
                null, null);

        assertNull(dto.parentViewObjectId());
        assertNull(dto.childViewObjectIds());
    }

    @Test
    public void shouldSupportEquality() {
        List<String> children = List.of("c-1");
        ViewGroupDto dto1 = new ViewGroupDto(
                "vo-1", "Label", 10, 20, 300, 200, "p-1", children);
        ViewGroupDto dto2 = new ViewGroupDto(
                "vo-1", "Label", 10, 20, 300, 200, "p-1", children);

        assertEquals(dto1, dto2);
        assertEquals(dto1.hashCode(), dto2.hashCode());
    }
}
