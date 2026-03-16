package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Unit tests for {@link ElementDto}.
 */
public class ElementDtoTest {

    @Test
    public void shouldCreateMinimalDto() {
        ElementDto dto = ElementDto.minimal("id-123", "Test Element");

        assertEquals("id-123", dto.id());
        assertEquals("Test Element", dto.name());
        assertNull(dto.type());
        assertNull(dto.layer());
        assertNull(dto.documentation());
        assertNull(dto.properties());
    }

    @Test
    public void shouldCreateStandardDto() {
        List<Map<String, String>> props = List.of(
            Map.of("key", "owner", "value", "Team A")
        );

        ElementDto dto = ElementDto.standard(
            "id-456",
            "Application Component",
            "ApplicationComponent",
            "Application",
            "This is the documentation",
            props
        );

        assertEquals("id-456", dto.id());
        assertEquals("Application Component", dto.name());
        assertEquals("ApplicationComponent", dto.type());
        assertEquals("Application", dto.layer());
        assertEquals("This is the documentation", dto.documentation());
        assertEquals(1, dto.properties().size());
    }

    @Test
    public void shouldSupportRecordEquality() {
        ElementDto dto1 = ElementDto.minimal("id-1", "Name");
        ElementDto dto2 = ElementDto.minimal("id-1", "Name");

        assertEquals(dto1, dto2);
        assertEquals(dto1.hashCode(), dto2.hashCode());
    }
}
