package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Tests for {@link LabelClearance} (Story 10-8).
 * Pure-geometry tests — no OSGi runtime required.
 */
public class LabelClearanceTest {

    // --- computeLabelRect tests ---

    @Test
    public void shouldReturnNull_whenLabelTextIsEmpty() {
        List<AbsoluteBendpointDto> path = List.of(
                new AbsoluteBendpointDto(200, 100),
                new AbsoluteBendpointDto(400, 100));
        RoutingRect result = LabelClearance.computeLabelRect(
                path, new int[]{100, 100}, new int[]{500, 100}, "", 1);
        assertNull(result);
    }

    @Test
    public void shouldReturnNull_whenLabelTextIsNull() {
        List<AbsoluteBendpointDto> path = List.of(
                new AbsoluteBendpointDto(200, 100));
        RoutingRect result = LabelClearance.computeLabelRect(
                path, new int[]{100, 100}, new int[]{500, 100}, null, 1);
        assertNull(result);
    }

    @Test
    public void shouldComputeLabelRect_atMiddlePosition() {
        // Straight horizontal path: source(0,100) -> bp(250,100) -> bp(500,100) -> target(750,100)
        List<AbsoluteBendpointDto> path = List.of(
                new AbsoluteBendpointDto(250, 100),
                new AbsoluteBendpointDto(500, 100));
        RoutingRect rect = LabelClearance.computeLabelRect(
                path, new int[]{0, 100}, new int[]{750, 100},
                "Accesses", 1); // textPosition=1 (middle)

        assertNotNull(rect);
        // "Accesses" = 8 chars → width = 8*7+10 = 66, height = 14+6 = 20
        assertEquals(66, rect.width());
        assertEquals(20, rect.height());
        // Midpoint of 750px path = 375, centered: 375 - 33 = 342
        assertEquals(342, rect.x());
        // Vertically centered: 100 - 10 = 90
        assertEquals(90, rect.y());
    }

    @Test
    public void shouldComputeLabelRect_atSourcePosition() {
        // Horizontal path 0→1000
        List<AbsoluteBendpointDto> path = List.of(
                new AbsoluteBendpointDto(500, 200));
        RoutingRect rect = LabelClearance.computeLabelRect(
                path, new int[]{0, 200}, new int[]{1000, 200},
                "Uses", 0); // textPosition=0 (source = 15%)

        assertNotNull(rect);
        // 15% of 1000 = 150, label "Uses" = 4*7+10 = 38 width
        // x = 150 - 19 = 131
        assertEquals(131, rect.x());
    }

    @Test
    public void shouldComputeLabelRect_atTargetPosition() {
        // Horizontal path 0→1000
        List<AbsoluteBendpointDto> path = List.of(
                new AbsoluteBendpointDto(500, 200));
        RoutingRect rect = LabelClearance.computeLabelRect(
                path, new int[]{0, 200}, new int[]{1000, 200},
                "Uses", 2); // textPosition=2 (target = 85%)

        assertNotNull(rect);
        // 85% of 1000 = 850, label "Uses" = 4*7+10 = 38 width
        // x = 850 - 19 = 831
        assertEquals(831, rect.x());
    }

    @Test
    public void shouldHandleShortPath() {
        // Very short path — should still produce a rect
        List<AbsoluteBendpointDto> path = List.of();
        RoutingRect rect = LabelClearance.computeLabelRect(
                path, new int[]{100, 100}, new int[]{110, 100},
                "A", 1);
        assertNotNull(rect);
        // Path length = 10px, label at 50% = 5px from source
        // "A" = 1*7+10 = 17 width, 14+6 = 20 height
        assertEquals(17, rect.width());
        assertEquals(20, rect.height());
    }

    @Test
    public void shouldReturnNull_whenPathHasZeroLength() {
        // Source and target at same point, no bendpoints
        List<AbsoluteBendpointDto> path = List.of();
        RoutingRect rect = LabelClearance.computeLabelRect(
                path, new int[]{100, 100}, new int[]{100, 100},
                "Label", 1);
        assertNull(rect);
    }

    // --- overlapsAnyObstacle tests ---

    @Test
    public void shouldDetectOverlap_whenLabelOverlapsObstacle() {
        RoutingRect labelRect = new RoutingRect(90, 90, 60, 20, null);
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(100, 80, 120, 60, "elem1"));
        assertTrue(LabelClearance.overlapsAnyObstacle(labelRect, obstacles));
    }

    @Test
    public void shouldNotDetectOverlap_whenLabelIsClear() {
        RoutingRect labelRect = new RoutingRect(0, 0, 50, 20, null);
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(200, 200, 120, 60, "elem1"));
        assertFalse(LabelClearance.overlapsAnyObstacle(labelRect, obstacles));
    }

    @Test
    public void shouldReturnFalse_whenLabelRectIsNull() {
        assertFalse(LabelClearance.overlapsAnyObstacle(null, List.of(
                new RoutingRect(0, 0, 100, 100, "elem1"))));
    }

    @Test
    public void shouldReturnFalse_whenNoObstacles() {
        RoutingRect labelRect = new RoutingRect(50, 50, 60, 20, null);
        assertFalse(LabelClearance.overlapsAnyObstacle(labelRect, List.of()));
    }
}
