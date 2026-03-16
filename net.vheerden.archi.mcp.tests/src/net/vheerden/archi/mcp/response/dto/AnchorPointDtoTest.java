package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for {@link AnchorPointDto} record.
 */
public class AnchorPointDtoTest {

    @Test
    public void shouldCreateWithAllFields() {
        AnchorPointDto dto = new AnchorPointDto(110, 77);

        assertEquals(110, dto.x());
        assertEquals(77, dto.y());
    }

    @Test
    public void shouldSupportEquality() {
        AnchorPointDto dto1 = new AnchorPointDto(110, 77);
        AnchorPointDto dto2 = new AnchorPointDto(110, 77);

        assertEquals(dto1, dto2);
        assertEquals(dto1.hashCode(), dto2.hashCode());
    }

    @Test
    public void shouldSupportInequality() {
        AnchorPointDto dto1 = new AnchorPointDto(110, 77);
        AnchorPointDto dto2 = new AnchorPointDto(200, 100);

        assertNotEquals(dto1, dto2);
    }
}
