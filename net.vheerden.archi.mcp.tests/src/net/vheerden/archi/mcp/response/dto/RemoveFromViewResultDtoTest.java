package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.List;

import org.junit.Test;

/**
 * Tests for {@link RemoveFromViewResultDto} (Story 7-8).
 */
public class RemoveFromViewResultDtoTest {

    @Test
    public void shouldConstructWithCascadeConnections() {
        List<String> cascadeIds = List.of("vc-1", "vc-2");
        RemoveFromViewResultDto dto = new RemoveFromViewResultDto(
                "vo-1", "viewObject", cascadeIds);

        assertEquals("vo-1", dto.removedObjectId());
        assertEquals("viewObject", dto.removedObjectType());
        assertNotNull(dto.cascadeRemovedConnectionIds());
        assertEquals(2, dto.cascadeRemovedConnectionIds().size());
        assertEquals("vc-1", dto.cascadeRemovedConnectionIds().get(0));
    }

    @Test
    public void shouldConstructWithoutCascadeConnections() {
        RemoveFromViewResultDto dto = new RemoveFromViewResultDto(
                "vo-1", "viewObject", null);

        assertEquals("vo-1", dto.removedObjectId());
        assertEquals("viewObject", dto.removedObjectType());
        assertNull(dto.cascadeRemovedConnectionIds());
    }

    @Test
    public void shouldConstructForConnectionType() {
        RemoveFromViewResultDto dto = new RemoveFromViewResultDto(
                "vc-5", "viewConnection", null);

        assertEquals("vc-5", dto.removedObjectId());
        assertEquals("viewConnection", dto.removedObjectType());
        assertNull(dto.cascadeRemovedConnectionIds());
    }
}
