package net.vheerden.archi.mcp.response.dto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Tests for {@link AutoRouteResultDto} (Story 13-7, backlog-b14).
 */
public class AutoRouteResultDtoTest {

    @Test
    public void shouldStoreNudgedElements_whenFullConstructor() {
        List<NudgedElementDto> nudged = List.of(
                new NudgedElementDto("vo-1", "El A", 50, 0),
                new NudgedElementDto("vo-2", "El B", 0, -40));

        AutoRouteResultDto dto = new AutoRouteResultDto(
                "v-1", 8, 0, "orthogonal", false, 2,
                List.of(), List.of(), List.of(), List.of(), nudged);

        assertEquals("v-1", dto.viewId());
        assertEquals(8, dto.connectionsRouted());
        assertEquals(0, dto.connectionsFailed());
        assertEquals("orthogonal", dto.strategy());
        assertFalse(dto.routerTypeSwitched());
        assertEquals(2, dto.labelsOptimized());
        assertEquals(2, dto.nudgedElements().size());
        assertEquals("vo-1", dto.nudgedElements().get(0).viewObjectId());
        assertEquals(50, dto.nudgedElements().get(0).deltaX());
    }

    @Test
    public void shouldDefaultNudgedElementsToEmptyList_whenNullPassed() {
        AutoRouteResultDto dto = new AutoRouteResultDto(
                "v-1", 5, 0, "orthogonal", false, 0,
                null, null, null, null, null);

        assertTrue(dto.nudgedElements().isEmpty());
        assertTrue(dto.warnings().isEmpty());
        assertTrue(dto.failed().isEmpty());
        assertTrue(dto.recommendations().isEmpty());
        assertTrue(dto.violations().isEmpty());
    }

    @Test
    public void shouldDefaultNudgedElementsToEmpty_whenConvenienceConstructor() {
        AutoRouteResultDto dto = new AutoRouteResultDto(
                "v-1", 5, "orthogonal", false);

        assertTrue(dto.nudgedElements().isEmpty());
        assertEquals(0, dto.connectionsFailed());
    }

    @Test
    public void shouldDefaultNudgedElementsToEmpty_whenTenParamConstructor() {
        AutoRouteResultDto dto = new AutoRouteResultDto(
                "v-1", 5, 2, "orthogonal", false, 1,
                List.of(), List.of(), List.of(), List.of());

        assertTrue(dto.nudgedElements().isEmpty());
        assertEquals(2, dto.connectionsFailed());
    }

    // ---- Crossing delta tests (backlog-b14) ----

    @Test
    public void shouldStoreCrossingFields_whenCanonicalConstructor() {
        AutoRouteResultDto dto = new AutoRouteResultDto(
                "v-1", 5, 0, "orthogonal", false, 1,
                3, 1,
                List.of(), List.of(), List.of(), List.of(), List.of());

        assertEquals(3, dto.crossingsBefore());
        assertEquals(1, dto.crossingsAfter());
    }

    @Test
    public void shouldDefaultCrossingsToZero_whenConvenienceConstructorWithoutCrossings() {
        // 11-param convenience constructor (without crossings, with nudgedElements)
        AutoRouteResultDto dto = new AutoRouteResultDto(
                "v-1", 5, 0, "orthogonal", false, 1,
                List.of(), List.of(), List.of(), List.of(), List.of());

        assertEquals(0, dto.crossingsBefore());
        assertEquals(0, dto.crossingsAfter());
    }

    @Test
    public void shouldDefaultCrossingsToZero_whenMinimalConstructor() {
        AutoRouteResultDto dto = new AutoRouteResultDto(
                "v-1", 5, "orthogonal", false);

        assertEquals(0, dto.crossingsBefore());
        assertEquals(0, dto.crossingsAfter());
    }

    @Test
    public void shouldDefaultCrossingsToZero_whenTenParamConstructor() {
        AutoRouteResultDto dto = new AutoRouteResultDto(
                "v-1", 5, 2, "orthogonal", false, 1,
                List.of(), List.of(), List.of(), List.of());

        assertEquals(0, dto.crossingsBefore());
        assertEquals(0, dto.crossingsAfter());
    }

    @Test
    public void shouldDefaultCrossingsToZero_whenWarningsOnlyConstructor() {
        AutoRouteResultDto dto = new AutoRouteResultDto(
                "v-1", 3, "clear", false, List.of("warning"));

        assertEquals(0, dto.crossingsBefore());
        assertEquals(0, dto.crossingsAfter());
    }

    @Test
    public void shouldDefaultCrossingsToZero_whenNineParamConstructor() {
        // 9-param: without labelsOptimized
        AutoRouteResultDto dto = new AutoRouteResultDto(
                "v-1", 5, 1, "orthogonal", false,
                List.of(), List.of(), List.of(), List.of());

        assertEquals(0, dto.crossingsBefore());
        assertEquals(0, dto.crossingsAfter());
    }

    @Test
    public void shouldDefaultCrossingsToZero_whenEightParamConstructor() {
        // 8-param: without violations
        AutoRouteResultDto dto = new AutoRouteResultDto(
                "v-1", 5, 1, "orthogonal", false,
                List.of(), List.of(), List.of());

        assertEquals(0, dto.crossingsBefore());
        assertEquals(0, dto.crossingsAfter());
    }

    @Test
    public void shouldDefaultCrossingsToZero_whenSevenParamConstructor() {
        // 7-param: without recommendations
        AutoRouteResultDto dto = new AutoRouteResultDto(
                "v-1", 5, 1, "orthogonal", false,
                List.of(), List.of());

        assertEquals(0, dto.crossingsBefore());
        assertEquals(0, dto.crossingsAfter());
    }

    // ---- Resized groups tests (backlog-b15) ----

    @Test
    public void shouldStoreResizedGroups_whenCanonicalConstructor() {
        List<ResizedGroupDto> groups = List.of(
                new ResizedGroupDto("g-1", "Group A", 100, 100, 450, 300));

        AutoRouteResultDto dto = new AutoRouteResultDto(
                "v-1", 5, 0, "orthogonal", false, 1,
                3, 1,
                List.of(), List.of(), List.of(), List.of(), List.of(), groups);

        assertEquals(1, dto.resizedGroups().size());
        assertEquals("g-1", dto.resizedGroups().get(0).viewObjectId());
        assertEquals("Group A", dto.resizedGroups().get(0).groupName());
        assertEquals(450, dto.resizedGroups().get(0).newWidth());
    }

    @Test
    public void shouldDefaultResizedGroupsToEmpty_whenNullPassed() {
        AutoRouteResultDto dto = new AutoRouteResultDto(
                "v-1", 5, 0, "orthogonal", false, 0,
                0, 0, null, null, null, null, null, null);

        assertTrue(dto.resizedGroups().isEmpty());
    }

    @Test
    public void shouldDefaultResizedGroupsToEmpty_when13ParamConstructor() {
        // 13-param constructor (backward compat, no resizedGroups)
        AutoRouteResultDto dto = new AutoRouteResultDto(
                "v-1", 5, 0, "orthogonal", false, 1,
                3, 1,
                List.of(), List.of(), List.of(), List.of(), List.of());

        assertTrue(dto.resizedGroups().isEmpty());
    }

    @Test
    public void shouldDefaultResizedGroupsToEmpty_whenConvenienceConstructor() {
        AutoRouteResultDto dto = new AutoRouteResultDto(
                "v-1", 5, "orthogonal", false);

        assertTrue(dto.resizedGroups().isEmpty());
    }

    // ---- Straight-line crossings tests (backlog-b22) ----

    @Test
    public void shouldStoreStraightLineCrossings_whenCanonicalConstructor() {
        AutoRouteResultDto dto = new AutoRouteResultDto(
                "v-1", 5, 0, "orthogonal", false, 1,
                3, 1, 8,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        assertEquals(3, dto.crossingsBefore());
        assertEquals(1, dto.crossingsAfter());
        assertEquals(8, dto.straightLineCrossings());
    }

    @Test
    public void shouldDefaultStraightLineCrossingsToZero_when14ParamConstructor() {
        // 14-param backward-compat constructor (without straightLineCrossings)
        AutoRouteResultDto dto = new AutoRouteResultDto(
                "v-1", 5, 0, "orthogonal", false, 1,
                3, 1,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        assertEquals(0, dto.straightLineCrossings());
        assertEquals(3, dto.crossingsBefore());
        assertEquals(1, dto.crossingsAfter());
    }

    @Test
    public void shouldDefaultStraightLineCrossingsToZero_whenMinimalConstructor() {
        AutoRouteResultDto dto = new AutoRouteResultDto(
                "v-1", 5, "orthogonal", false);

        assertEquals(0, dto.straightLineCrossings());
    }

    @Test
    public void shouldDefaultStraightLineCrossingsToZero_whenNoCrossingsConstructor() {
        // 11-param: without crossings, with nudgedElements
        AutoRouteResultDto dto = new AutoRouteResultDto(
                "v-1", 5, 0, "orthogonal", false, 1,
                List.of(), List.of(), List.of(), List.of(), List.of());

        assertEquals(0, dto.straightLineCrossings());
    }
}
