package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

/**
 * Tests for {@link AddToViewResultDto} record.
 */
public class AddToViewResultDtoTest {

    @Test
    public void shouldCreateWithViewObjectOnly() {
        ViewObjectDto vo = new ViewObjectDto(
                "vo-1", "e-1", "Name", "Type", 50, 50, 120, 55);
        AddToViewResultDto dto = new AddToViewResultDto(vo, null);

        assertEquals("vo-1", dto.viewObject().viewObjectId());
        assertNull(dto.autoConnections());
    }

    @Test
    public void shouldCreateWithAutoConnections() {
        ViewObjectDto vo = new ViewObjectDto(
                "vo-1", "e-1", "Name", "Type", 50, 50, 120, 55);
        ViewConnectionDto conn = new ViewConnectionDto(
                "vc-1", "rel-1", "Serving", "vo-1", "vo-2", null);
        AddToViewResultDto dto = new AddToViewResultDto(vo, List.of(conn));

        assertNotNull(dto.autoConnections());
        assertEquals(1, dto.autoConnections().size());
        assertEquals("vc-1", dto.autoConnections().get(0).viewConnectionId());
    }

    @Test
    public void shouldSupportEmptyAutoConnections() {
        ViewObjectDto vo = new ViewObjectDto(
                "vo-1", "e-1", "Name", "Type", 50, 50, 120, 55);
        AddToViewResultDto dto = new AddToViewResultDto(vo, List.of());

        assertNotNull(dto.autoConnections());
        assertTrue(dto.autoConnections().isEmpty());
    }

    @Test
    public void shouldHaveNullSkippedByDefault() {
        ViewObjectDto vo = new ViewObjectDto(
                "vo-1", "e-1", "Name", "Type", 50, 50, 120, 55);
        AddToViewResultDto dto = new AddToViewResultDto(vo, null);

        assertNull(dto.skippedAutoConnections());
    }

    @Test
    public void shouldTrackSkippedAutoConnections() {
        ViewObjectDto vo = new ViewObjectDto(
                "vo-1", "e-1", "Name", "Type", 50, 50, 120, 55);
        ViewConnectionDto conn = new ViewConnectionDto(
                "vc-1", "rel-1", "Serving", "vo-1", "vo-2", null);
        AddToViewResultDto dto = new AddToViewResultDto(vo, List.of(conn), 5);

        assertEquals(Integer.valueOf(5), dto.skippedAutoConnections());
    }
}
