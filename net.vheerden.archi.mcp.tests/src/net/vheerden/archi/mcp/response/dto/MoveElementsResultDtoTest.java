package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;

import org.junit.Test;

/**
 * Tests for {@link MoveElementsResultDto} (Story 13-1).
 */
public class MoveElementsResultDtoTest {

    @Test
    public void shouldStoreAllFields() {
        MoveElementsResultDto.MovedElement moved = new MoveElementsResultDto.MovedElement(
                "vo-1", 150, 200, 120, 55);
        MoveElementsResultDto dto = new MoveElementsResultDto(
                "view-1", 50, -30, 1, List.of(moved));

        assertEquals("view-1", dto.viewId());
        assertEquals(50, dto.deltaX());
        assertEquals(-30, dto.deltaY());
        assertEquals(1, dto.elementsMoved());
        assertEquals(1, dto.positions().size());
        assertEquals("vo-1", dto.positions().get(0).viewObjectId());
        assertEquals(150, dto.positions().get(0).x());
        assertEquals(200, dto.positions().get(0).y());
    }

    @Test
    public void shouldSupportMultipleElements() {
        List<MoveElementsResultDto.MovedElement> positions = List.of(
                new MoveElementsResultDto.MovedElement("vo-1", 100, 100, 120, 55),
                new MoveElementsResultDto.MovedElement("vo-2", 300, 100, 120, 55));
        MoveElementsResultDto dto = new MoveElementsResultDto(
                "view-1", 80, 0, 2, positions);

        assertEquals(2, dto.elementsMoved());
        assertEquals(2, dto.positions().size());
    }
}
