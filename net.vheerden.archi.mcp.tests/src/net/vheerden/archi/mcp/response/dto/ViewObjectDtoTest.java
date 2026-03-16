package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for {@link ViewObjectDto} record.
 */
public class ViewObjectDtoTest {

    @Test
    public void shouldCreateWithAllFields() {
        ViewObjectDto dto = new ViewObjectDto(
                "vo-1", "elem-1", "My Component", "ApplicationComponent",
                100, 200, 120, 55);

        assertEquals("vo-1", dto.viewObjectId());
        assertEquals("elem-1", dto.elementId());
        assertEquals("My Component", dto.elementName());
        assertEquals("ApplicationComponent", dto.elementType());
        assertEquals(100, dto.x());
        assertEquals(200, dto.y());
        assertEquals(120, dto.width());
        assertEquals(55, dto.height());
    }

    @Test
    public void shouldSupportEquality() {
        ViewObjectDto dto1 = new ViewObjectDto(
                "vo-1", "e-1", "Name", "Type", 10, 20, 120, 55);
        ViewObjectDto dto2 = new ViewObjectDto(
                "vo-1", "e-1", "Name", "Type", 10, 20, 120, 55);

        assertEquals(dto1, dto2);
        assertEquals(dto1.hashCode(), dto2.hashCode());
    }
}
