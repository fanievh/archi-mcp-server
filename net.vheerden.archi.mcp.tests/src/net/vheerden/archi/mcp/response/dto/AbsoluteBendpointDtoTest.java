package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for {@link AbsoluteBendpointDto} record.
 */
public class AbsoluteBendpointDtoTest {

    @Test
    public void shouldCreateWithAllFields() {
        AbsoluteBendpointDto dto = new AbsoluteBendpointDto(300, 150);

        assertEquals(300, dto.x());
        assertEquals(150, dto.y());
    }

    @Test
    public void shouldSupportEquality() {
        AbsoluteBendpointDto dto1 = new AbsoluteBendpointDto(300, 150);
        AbsoluteBendpointDto dto2 = new AbsoluteBendpointDto(300, 150);

        assertEquals(dto1, dto2);
        assertEquals(dto1.hashCode(), dto2.hashCode());
    }

    @Test
    public void shouldSupportInequality() {
        AbsoluteBendpointDto dto1 = new AbsoluteBendpointDto(300, 150);
        AbsoluteBendpointDto dto2 = new AbsoluteBendpointDto(400, 200);

        assertNotEquals(dto1, dto2);
    }
}
