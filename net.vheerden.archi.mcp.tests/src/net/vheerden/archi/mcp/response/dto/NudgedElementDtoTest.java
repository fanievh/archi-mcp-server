package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests for {@link NudgedElementDto} (Story 13-7).
 */
public class NudgedElementDtoTest {

    @Test
    public void shouldStoreAllFields() {
        NudgedElementDto dto = new NudgedElementDto("vo-1", "Element A", 50, -30);
        assertEquals("vo-1", dto.viewObjectId());
        assertEquals("Element A", dto.elementName());
        assertEquals(50, dto.deltaX());
        assertEquals(-30, dto.deltaY());
    }

    @Test
    public void shouldHandleZeroDeltas() {
        NudgedElementDto dto = new NudgedElementDto("vo-2", "Element B", 0, 0);
        assertEquals(0, dto.deltaX());
        assertEquals(0, dto.deltaY());
    }
}
