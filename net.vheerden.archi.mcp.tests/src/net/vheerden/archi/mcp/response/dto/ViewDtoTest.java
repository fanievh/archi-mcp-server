package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for {@link ViewDto} record.
 */
public class ViewDtoTest {

    @Test
    public void shouldCreateWithAllFields() {
        ViewDto dto = new ViewDto("view-1", "Main View", "application_cooperation", "Views/Diagrams");

        assertEquals("view-1", dto.id());
        assertEquals("Main View", dto.name());
        assertEquals("application_cooperation", dto.viewpointType());
        assertEquals("Views/Diagrams", dto.folderPath());
    }

    @Test
    public void shouldSupportNullViewpoint() {
        ViewDto dto = new ViewDto("view-1", "Overview", null, "Views");
        assertNull(dto.viewpointType());
    }

    @Test
    public void shouldSupportEquality() {
        ViewDto dto1 = new ViewDto("v-1", "View", "layered", "Views");
        ViewDto dto2 = new ViewDto("v-1", "View", "layered", "Views");
        assertEquals(dto1, dto2);
        assertEquals(dto1.hashCode(), dto2.hashCode());
    }
}
