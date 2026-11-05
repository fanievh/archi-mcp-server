package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Tests for {@link LabelClearance}.
 * Pure-geometry tests — no OSGi runtime required.
 */
public class LabelClearanceTest {

    // --- computeReservedLabelRect tests ---

    @Test
    public void shouldReturnNull_whenLabelTextIsEmpty() {
        List<AbsoluteBendpointDto> path = List.of(
                new AbsoluteBendpointDto(200, 100),
                new AbsoluteBendpointDto(400, 100));
        RoutingRect result = LabelClearance.computeReservedLabelRect(
                path, new int[]{100, 100}, new int[]{500, 100}, "", 1);
        assertNull(result);
    }

    @Test
    public void shouldReturnNull_whenLabelTextIsNull() {
        List<AbsoluteBendpointDto> path = List.of(
                new AbsoluteBendpointDto(200, 100));
        RoutingRect result = LabelClearance.computeReservedLabelRect(
                path, new int[]{100, 100}, new int[]{500, 100}, null, 1);
        assertNull(result);
    }

    @Test
    public void shouldComputeLabelRect_atMiddlePosition() {
        // Straight horizontal path: source(0,100) -> bp(250,100) -> bp(500,100) -> target(750,100)
        List<AbsoluteBendpointDto> path = List.of(
                new AbsoluteBendpointDto(250, 100),
                new AbsoluteBendpointDto(500, 100));
        RoutingRect rect = LabelClearance.computeReservedLabelRect(
                path, new int[]{0, 100}, new int[]{750, 100},
                "Accesses", 1); // textPosition=1 (middle)

        assertNotNull(rect);
        // "Accesses" = 8 chars → width = 8*8+10 = 74, height = 14+6 = 20
        assertEquals(74, rect.width());
        assertEquals(20, rect.height());
        // Midpoint of 750px path = 375, centered: 375 - 37 = 338
        assertEquals(338, rect.x());
        // Vertically centered: 100 - 10 = 90
        assertEquals(90, rect.y());
    }

    @Test
    public void renderedLabelRect_atMiddle_isTheRenderCalibratedBoxOnTheSameCentre() {
        // The same path as the reserved case above, between boxes centred on (0, 100) and (750, 100):
        // "Accesses" scores 8 * 8 * 1.35 + 10 = 96.4 px wide, drawn 96 px. The middle segment runs 250..500,
        // so Archi centres it on 375 as the reserved box is; x = round(375 - 48.2) = 327.
        List<AbsoluteBendpointDto> path = List.of(
                new AbsoluteBendpointDto(250, 100),
                new AbsoluteBendpointDto(500, 100));
        RoutingRect rect = LabelClearance.computeRenderedLabelRect(
                path, new RoutingRect(-50, 70, 100, 60, "s"), new RoutingRect(700, 70, 100, 60, "t"),
                "Accesses", 1);

        assertNotNull(rect);
        assertEquals(96, rect.width());
        assertEquals(20, rect.height());
        assertEquals(327, rect.x());
        assertEquals(90, rect.y());
    }

    @Test
    public void renderedLabelRect_isNull_forAnEmptyLabel() {
        assertNull(LabelClearance.computeRenderedLabelRect(
                List.of(), new RoutingRect(-50, -30, 100, 60, "s"), new RoutingRect(50, -30, 100, 60, "t"), "", 1));
    }

    @Test
    public void labelClearancePass_doesNotShiftARouteThatOnlyTheRenderedBoxWouldMove() {
        // The pass moves geometry, so it tests the reserved box. A 30-character Middle label centred on
        // the vertical leg at (350, 180) reserves x 225..475 and renders x 183..517; the box at
        // x 490..530 is 15 px clear of the reserved box and 27 px inside the rendered one. The route stays
        // exactly as it is.
        RoutingRect source = new RoutingRect(0, 0, 100, 60, "s");
        RoutingRect target = new RoutingRect(600, 300, 100, 60, "t");
        RoutingRect beside = new RoutingRect(490, 160, 40, 40, "beside");
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(beside),
                        "abcdefghijklmnopqrstuvwxyzabcd", 1));
        List<AbsoluteBendpointDto> path = new java.util.ArrayList<>(List.of(
                new AbsoluteBendpointDto(104, 30),
                new AbsoluteBendpointDto(350, 30),
                new AbsoluteBendpointDto(350, 330)));

        RoutingPipeline.shiftPathsForLabelClearance(
                connections, List.of(path), List.of(beside, source, target));

        assertEquals(List.of(
                new AbsoluteBendpointDto(104, 30),
                new AbsoluteBendpointDto(350, 30),
                new AbsoluteBendpointDto(350, 330)), path);
    }

    @Test
    public void labelClearancePass_shiftsARouteWhoseReservedBoxMeetsABox() {
        // The same route with the box moved 30 px left, to x 460..500: now inside the reserved box
        // (x 225..475 at y 170..190, half way along the centre path), so the pass moves the vertical leg
        // 30 px left, exactly as it did before a Middle label was scored where Archi draws it.
        RoutingRect source = new RoutingRect(0, 0, 100, 60, "s");
        RoutingRect target = new RoutingRect(600, 300, 100, 60, "t");
        RoutingRect beside = new RoutingRect(460, 160, 40, 40, "beside");
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(beside),
                        "abcdefghijklmnopqrstuvwxyzabcd", 1));
        List<AbsoluteBendpointDto> path = new java.util.ArrayList<>(List.of(
                new AbsoluteBendpointDto(104, 30),
                new AbsoluteBendpointDto(350, 30),
                new AbsoluteBendpointDto(350, 330)));

        RoutingPipeline.shiftPathsForLabelClearance(
                connections, List.of(path), List.of(beside, source, target));

        assertEquals(List.of(
                new AbsoluteBendpointDto(104, 30),
                new AbsoluteBendpointDto(320, 30),
                new AbsoluteBendpointDto(320, 330)), path);
    }

    @Test
    public void labelClearancePass_reservesHalfWayAlongTheCentrePath_notWhereArchiDrawsTheLabel() {
        // The route above has five points, so Archi centres the Middle label on the corner (350, 30). A
        // reserved box there (x 225..475 at y 20..40) is clear of the box at x 460..500 x y 160..200, so
        // only a pass that reserves half way along the centre path shifts this route; it does (above).
        // The pass keeps that anchor: it moves geometry, and the optimizer, which runs after it and scores
        // the drawn point, has the last word on the slot.
        RoutingRect beside = new RoutingRect(460, 160, 40, 40, "beside");
        List<AbsoluteBendpointDto> path = List.of(
                new AbsoluteBendpointDto(104, 30),
                new AbsoluteBendpointDto(350, 30),
                new AbsoluteBendpointDto(350, 330));

        double[] drawnAnchor = LabelAnchor.middlePoint(LabelAnchor.drawnPoints(
                LabelClearance.centrePath(path, new int[]{50, 30}, new int[]{650, 330}),
                new double[]{0, 0, 100, 60}, new double[]{600, 300, 100, 60}));
        assertArrayEquals(new double[]{350, 30}, drawnAnchor, 0.0);
        assertFalse(LabelClearance.overlapsAnyObstacle(new RoutingRect(225, 20, 250, 20, null), List.of(beside)));

        RoutingRect reserved = LabelClearance.computeReservedLabelRect(
                path, new int[]{50, 30}, new int[]{650, 330}, "abcdefghijklmnopqrstuvwxyzabcd", 1);
        assertEquals(new RoutingRect(225, 170, 250, 20, null), reserved);
        assertTrue(LabelClearance.overlapsAnyObstacle(reserved, List.of(beside)));
    }

    @Test
    public void labelClearancePass_reservesASourceLabel15PercentAlong_notWhereArchiDrawsIt() {
        // The same route with a Source label. 15% along the 900 px centre path is (185, 30), so the reserved
        // box (x 60..310, y 20..40) meets the box above the line at x 200..240, y 5..25, and the pass moves
        // the first leg 30 px up, exactly as it did before a Source label was scored where Archi draws it. Archi draws
        // the label below the line (x 114..448, y 34..54), clear of that box.
        RoutingRect source = new RoutingRect(0, 0, 100, 60, "s");
        RoutingRect target = new RoutingRect(600, 300, 100, 60, "t");
        RoutingRect above = new RoutingRect(200, 5, 40, 20, "above");
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(above),
                        "abcdefghijklmnopqrstuvwxyzabcd", 0));
        List<AbsoluteBendpointDto> path = new java.util.ArrayList<>(List.of(
                new AbsoluteBendpointDto(104, 30),
                new AbsoluteBendpointDto(350, 30),
                new AbsoluteBendpointDto(350, 330)));

        RoutingPipeline.shiftPathsForLabelClearance(
                connections, List.of(path), List.of(above, source, target));

        assertEquals(List.of(
                new AbsoluteBendpointDto(104, 0),
                new AbsoluteBendpointDto(350, 0),
                new AbsoluteBendpointDto(350, 330)), path);
    }

    @Test
    public void labelClearancePass_reservesATargetLabel85PercentAlong_notWhereArchiDrawsIt() {
        // The same route with a Target label. 85% along the 900 px centre path is (515, 330), so the reserved
        // box (x 390..640, y 320..340) meets the box above the last leg at x 500..540, y 305..325, and the pass
        // moves the vertical leg nearest that point 30 px left, exactly as it did before a Target label was scored where
        // Archi draws it. Archi draws the label below the line, ending 14 px short of the target (x 252..586,
        // y 334..354), clear of that box.
        RoutingRect source = new RoutingRect(0, 0, 100, 60, "s");
        RoutingRect target = new RoutingRect(600, 300, 100, 60, "t");
        RoutingRect above = new RoutingRect(500, 305, 40, 20, "above");
        List<RoutingPipeline.ConnectionEndpoints> connections = List.of(
                new RoutingPipeline.ConnectionEndpoints("c1", source, target, List.of(above),
                        "abcdefghijklmnopqrstuvwxyzabcd", 2));
        List<AbsoluteBendpointDto> path = new java.util.ArrayList<>(List.of(
                new AbsoluteBendpointDto(104, 30),
                new AbsoluteBendpointDto(350, 30),
                new AbsoluteBendpointDto(350, 330)));

        RoutingPipeline.shiftPathsForLabelClearance(
                connections, List.of(path), List.of(above, source, target));

        assertEquals(List.of(
                new AbsoluteBendpointDto(104, 30),
                new AbsoluteBendpointDto(320, 30),
                new AbsoluteBendpointDto(320, 330)), path);
    }

    @Test
    public void shouldComputeLabelRect_atSourcePosition() {
        // Horizontal path 0→1000
        List<AbsoluteBendpointDto> path = List.of(
                new AbsoluteBendpointDto(500, 200));
        RoutingRect rect = LabelClearance.computeReservedLabelRect(
                path, new int[]{0, 200}, new int[]{1000, 200},
                "Uses", 0); // textPosition=0 (source = 15%)

        assertNotNull(rect);
        // 15% of 1000 = 150, label "Uses" = 4*8+10 = 42 width
        // x = 150 - 21 = 129
        assertEquals(129, rect.x());
    }

    @Test
    public void shouldComputeLabelRect_atTargetPosition() {
        // Horizontal path 0→1000
        List<AbsoluteBendpointDto> path = List.of(
                new AbsoluteBendpointDto(500, 200));
        RoutingRect rect = LabelClearance.computeReservedLabelRect(
                path, new int[]{0, 200}, new int[]{1000, 200},
                "Uses", 2); // textPosition=2 (target = 85%)

        assertNotNull(rect);
        // 85% of 1000 = 850, label "Uses" = 4*8+10 = 42 width
        // x = 850 - 21 = 829
        assertEquals(829, rect.x());
    }

    @Test
    public void shouldHandleShortPath() {
        // Very short path — should still produce a rect
        List<AbsoluteBendpointDto> path = List.of();
        RoutingRect rect = LabelClearance.computeReservedLabelRect(
                path, new int[]{100, 100}, new int[]{110, 100},
                "A", 1);
        assertNotNull(rect);
        // Path length = 10px, label at 50% = 5px from source
        // "A" = 1*8+10 = 18 width, 14+6 = 20 height
        assertEquals(18, rect.width());
        assertEquals(20, rect.height());
    }

    @Test
    public void shouldReturnNull_whenPathHasZeroLength() {
        // Source and target at same point, no bendpoints
        List<AbsoluteBendpointDto> path = List.of();
        RoutingRect rect = LabelClearance.computeReservedLabelRect(
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
