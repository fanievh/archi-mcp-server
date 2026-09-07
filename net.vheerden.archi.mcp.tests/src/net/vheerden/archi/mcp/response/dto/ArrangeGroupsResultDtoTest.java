package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tests for {@link ArrangeGroupsResultDto} record.
 */
public class ArrangeGroupsResultDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void shouldCreateForGridArrangement() {
        ArrangeGroupsResultDto dto = new ArrangeGroupsResultDto(
                "view-1", 6, 800, 600, 3, "grid", 40, null);

        assertEquals("view-1", dto.viewId());
        assertEquals(6, dto.groupsPositioned());
        assertEquals(800, dto.layoutWidth());
        assertEquals(600, dto.layoutHeight());
        assertEquals(Integer.valueOf(3), dto.columnsUsed());
        assertEquals("grid", dto.arrangement());
        assertEquals(Integer.valueOf(40), dto.resolvedSpacing());
        assertNull(dto.defaultResolutionReason());
    }

    @Test
    public void shouldCreateForRowArrangement() {
        ArrangeGroupsResultDto dto = new ArrangeGroupsResultDto(
                "view-1", 4, 1200, 300, null, "row", 40, null);

        assertEquals(4, dto.groupsPositioned());
        assertNull(dto.columnsUsed());
        assertEquals("row", dto.arrangement());
    }

    @Test
    public void shouldCreateForColumnArrangement() {
        ArrangeGroupsResultDto dto = new ArrangeGroupsResultDto(
                "view-1", 3, 400, 900, null, "column", 40, null);

        assertEquals(3, dto.groupsPositioned());
        assertNull(dto.columnsUsed());
        assertEquals("column", dto.arrangement());
    }

    @Test
    public void shouldOmitNullColumnsUsed_whenSerialized() throws Exception {
        ArrangeGroupsResultDto dto = new ArrangeGroupsResultDto(
                "view-1", 4, 1200, 300, null, "row", 40, null);

        String json = objectMapper.writeValueAsString(dto);
        assertFalse("columnsUsed should be omitted when null",
                json.contains("columnsUsed"));
        assertTrue("arrangement should be present",
                json.contains("\"arrangement\":\"row\""));
    }

    @Test
    public void shouldIncludeColumnsUsed_whenNonNull() throws Exception {
        ArrangeGroupsResultDto dto = new ArrangeGroupsResultDto(
                "view-1", 6, 800, 600, 3, "grid", 40, null);

        String json = objectMapper.writeValueAsString(dto);
        assertTrue("columnsUsed should be present",
                json.contains("\"columnsUsed\":3"));
        assertTrue("groupsPositioned should be present",
                json.contains("\"groupsPositioned\":6"));
    }

    @Test
    public void shouldSerializeAllFields() throws Exception {
        ArrangeGroupsResultDto dto = new ArrangeGroupsResultDto(
                "view-1", 6, 800, 600, 3, "grid", 40, null);

        String json = objectMapper.writeValueAsString(dto);
        assertTrue(json.contains("\"viewId\":\"view-1\""));
        assertTrue(json.contains("\"groupsPositioned\":6"));
        assertTrue(json.contains("\"layoutWidth\":800"));
        assertTrue(json.contains("\"layoutHeight\":600"));
        assertTrue(json.contains("\"columnsUsed\":3"));
        assertTrue(json.contains("\"arrangement\":\"grid\""));
        assertTrue("resolvedSpacing should be present",
                json.contains("\"resolvedSpacing\":40"));
    }

    @Test
    public void shouldSerializeDefaultResolutionReason_whenFired() throws Exception {
        ArrangeGroupsResultDto dto = new ArrangeGroupsResultDto(
                "view-1", 6, 800, 600, 3, "grid", 100,
                "spacing omitted; isConnected=true with 24 inter-group "
                + "connections; heuristic for connectionCount=24 => "
                + "target=100 px (connected column)");

        String json = objectMapper.writeValueAsString(dto);
        assertTrue("defaultResolutionReason should be present when populated",
                json.contains("\"defaultResolutionReason\":"));
        assertTrue("resolvedSpacing should reflect the heuristic value",
                json.contains("\"resolvedSpacing\":100"));
    }

    @Test
    public void shouldSerializeTopLevelObjects_evenWhenItIsZero() throws Exception {
        // The denominator is what makes the other counters falsifiable, so it must never be the
        // field that disappears. A count omitted beside a description list reads as an all-clear
        // rather than as the absence of a measurement, and telling those two apart is the entire
        // reason this field exists.
        ArrangeGroupsResultDto dto = new ArrangeGroupsResultDto(
                "view-1", 0, 0, 0, null, "row", 40, null);

        String json = objectMapper.writeValueAsString(dto);
        assertTrue("topLevelObjects must be present at 0, not omitted. Was: " + json,
                json.contains("\"topLevelObjects\":0"));
    }

    @Test
    public void shouldOmitUnhandled_whenNothingFellThrough() throws Exception {
        // Unlike the denominator, an empty residual is not a claim — it is the ordinary case, and
        // it follows the skippedContainers convention rather than inventing a third one.
        ArrangeGroupsResultDto dto = new ArrangeGroupsResultDto(
                "view-1", 2, 400, 300, null, "row", 40, null, 0, List.of(), 2, List.of());

        String json = objectMapper.writeValueAsString(dto);
        assertFalse("an empty residual should be absent, not an empty array. Was: " + json,
                json.contains("unhandled"));
        assertTrue("the denominator stays, so the caller can still reconcile",
                json.contains("\"topLevelObjects\":2"));
    }

    @Test
    public void shouldSerializeUnhandled_whenSomethingFellThrough() throws Exception {
        ArrangeGroupsResultDto dto = new ArrangeGroupsResultDto(
                "view-1", 1, 400, 300, null, "row", 40, null, 0, List.of(), 2,
                List.of(new SkippedContainerDto("obj-1", "Firewall", "Node",
                        "insufficient-connections: connected to fewer than two arranged containers")));

        String json = objectMapper.writeValueAsString(dto);
        assertTrue("the residual must carry the object's id so the caller can act on it",
                json.contains("\"viewObjectId\":\"obj-1\""));
        assertTrue("and the reason code it can filter on without reading the prose",
                json.contains("insufficient-connections"));
    }

    @Test
    public void shouldOmitDefaultResolutionReason_whenNull() throws Exception {
        ArrangeGroupsResultDto dto = new ArrangeGroupsResultDto(
                "view-1", 6, 800, 600, 3, "grid", 40, null);

        String json = objectMapper.writeValueAsString(dto);
        assertFalse("defaultResolutionReason should be omitted when null",
                json.contains("defaultResolutionReason"));
    }
}
