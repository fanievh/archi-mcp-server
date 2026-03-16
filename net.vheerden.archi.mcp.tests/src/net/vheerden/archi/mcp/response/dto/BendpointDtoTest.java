package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for {@link BendpointDto} record.
 */
public class BendpointDtoTest {

    @Test
    public void shouldCreateWithAllFields() {
        BendpointDto dto = new BendpointDto(10, 20, 30, 40);

        assertEquals(10, dto.startX());
        assertEquals(20, dto.startY());
        assertEquals(30, dto.endX());
        assertEquals(40, dto.endY());
    }
}
