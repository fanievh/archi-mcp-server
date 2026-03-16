package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.*;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tests for {@link ArrangeGroupsResultDto} record (Story 11-20).
 */
public class ArrangeGroupsResultDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void shouldCreateForGridArrangement() {
        ArrangeGroupsResultDto dto = new ArrangeGroupsResultDto(
                "view-1", 6, 800, 600, 3, "grid");

        assertEquals("view-1", dto.viewId());
        assertEquals(6, dto.groupsPositioned());
        assertEquals(800, dto.layoutWidth());
        assertEquals(600, dto.layoutHeight());
        assertEquals(Integer.valueOf(3), dto.columnsUsed());
        assertEquals("grid", dto.arrangement());
    }

    @Test
    public void shouldCreateForRowArrangement() {
        ArrangeGroupsResultDto dto = new ArrangeGroupsResultDto(
                "view-1", 4, 1200, 300, null, "row");

        assertEquals(4, dto.groupsPositioned());
        assertNull(dto.columnsUsed());
        assertEquals("row", dto.arrangement());
    }

    @Test
    public void shouldCreateForColumnArrangement() {
        ArrangeGroupsResultDto dto = new ArrangeGroupsResultDto(
                "view-1", 3, 400, 900, null, "column");

        assertEquals(3, dto.groupsPositioned());
        assertNull(dto.columnsUsed());
        assertEquals("column", dto.arrangement());
    }

    @Test
    public void shouldOmitNullColumnsUsed_whenSerialized() throws Exception {
        ArrangeGroupsResultDto dto = new ArrangeGroupsResultDto(
                "view-1", 4, 1200, 300, null, "row");

        String json = objectMapper.writeValueAsString(dto);
        assertFalse("columnsUsed should be omitted when null",
                json.contains("columnsUsed"));
        assertTrue("arrangement should be present",
                json.contains("\"arrangement\":\"row\""));
    }

    @Test
    public void shouldIncludeColumnsUsed_whenNonNull() throws Exception {
        ArrangeGroupsResultDto dto = new ArrangeGroupsResultDto(
                "view-1", 6, 800, 600, 3, "grid");

        String json = objectMapper.writeValueAsString(dto);
        assertTrue("columnsUsed should be present",
                json.contains("\"columnsUsed\":3"));
        assertTrue("groupsPositioned should be present",
                json.contains("\"groupsPositioned\":6"));
    }

    @Test
    public void shouldSerializeAllFields() throws Exception {
        ArrangeGroupsResultDto dto = new ArrangeGroupsResultDto(
                "view-1", 6, 800, 600, 3, "grid");

        String json = objectMapper.writeValueAsString(dto);
        assertTrue(json.contains("\"viewId\":\"view-1\""));
        assertTrue(json.contains("\"groupsPositioned\":6"));
        assertTrue(json.contains("\"layoutWidth\":800"));
        assertTrue(json.contains("\"layoutHeight\":600"));
        assertTrue(json.contains("\"columnsUsed\":3"));
        assertTrue(json.contains("\"arrangement\":\"grid\""));
    }
}
