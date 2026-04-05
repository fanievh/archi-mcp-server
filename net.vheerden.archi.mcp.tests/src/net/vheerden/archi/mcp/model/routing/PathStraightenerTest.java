package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Tests for {@link PathStraightener} — post-routing path straightening
 * (backlog-b42). Pure-geometry tests, no EMF/SWT required.
 */
public class PathStraightenerTest {

    private static final int SNAP_THRESHOLD = 20;
    private static final List<RoutingRect> NO_OBSTACLES = Collections.emptyList();

    // ==================== snapToStraight tests (Task 1) ====================

    @Test
    public void snapToStraight_shouldNotModifyPathWithNoIntermediateBendpoints() {
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(300, 112));

        PathStraightener.snapToStraight(path, SNAP_THRESHOLD, NO_OBSTACLES);

        assertEquals(2, path.size());
        assertEquals(100, path.get(0).x());
        assertEquals(300, path.get(1).x());
    }

    @Test
    public void snapToStraight_shouldSnapSmallDeltaX() {
        // Path where interior point has small X offset
        // (100,100) -> (112,200) -> (100,300) — dx=12 < threshold, dy=100 > dx
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(112, 200),
                new AbsoluteBendpointDto(100, 300));

        PathStraightener.snapToStraight(path, SNAP_THRESHOLD, NO_OBSTACLES);

        // Interior point (112,200) should snap X to 100 (prev.x)
        assertEquals(100, path.get(1).x());
        assertEquals(200, path.get(1).y());
    }

    @Test
    public void snapToStraight_shouldSnapSmallDeltaY() {
        // (100,100) -> (300,115) -> (500,100) — dy=15 < threshold, dx=200 > dy
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(300, 115),
                new AbsoluteBendpointDto(500, 100));

        PathStraightener.snapToStraight(path, SNAP_THRESHOLD, NO_OBSTACLES);

        // Interior point (300,115) should snap Y to 100 (prev.y)
        assertEquals(300, path.get(1).x());
        assertEquals(100, path.get(1).y());
    }

    @Test
    public void snapToStraight_shouldNotSnapWhenDeltaExceedsThreshold() {
        // (100,100) -> (125,200) -> (100,300) — dx=25 > 20 threshold
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(125, 200),
                new AbsoluteBendpointDto(100, 300));

        PathStraightener.snapToStraight(path, SNAP_THRESHOLD, NO_OBSTACLES);

        assertEquals(125, path.get(1).x());
        assertEquals(200, path.get(1).y());
    }

    @Test
    public void snapToStraight_shouldNotSnapWhenDeltaXEqualsZero() {
        // Already perfectly aligned
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(100, 300));

        PathStraightener.snapToStraight(path, SNAP_THRESHOLD, NO_OBSTACLES);

        assertEquals(100, path.get(1).x());
        assertEquals(200, path.get(1).y());
    }

    @Test
    public void snapToStraight_shouldSnapSmallerDelta() {
        // (100,100) -> (110,108) -> (200,200) — dx=10, dy=8
        // dy < dx, dy <= threshold, dx > dy → snap Y
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(110, 108),
                new AbsoluteBendpointDto(200, 200));

        PathStraightener.snapToStraight(path, SNAP_THRESHOLD, NO_OBSTACLES);

        assertEquals(110, path.get(1).x());
        assertEquals(100, path.get(1).y());
    }

    @Test
    public void snapToStraight_shouldPreserveTerminalAnchors() {
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(200, 115),
                new AbsoluteBendpointDto(300, 100));

        PathStraightener.snapToStraight(path, SNAP_THRESHOLD, NO_OBSTACLES);

        assertEquals(100, path.get(0).x());
        assertEquals(100, path.get(0).y());
        assertEquals(300, path.get(2).x());
        assertEquals(100, path.get(2).y());
    }

    @Test
    public void snapToStraight_shouldHandleMultipleIntermediateBPs() {
        // (100,100) -> (200,105) -> (300,112) -> (400,100)
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(200, 105),
                new AbsoluteBendpointDto(300, 112),
                new AbsoluteBendpointDto(400, 100));

        PathStraightener.snapToStraight(path, SNAP_THRESHOLD, NO_OBSTACLES);

        // Point 1: prev=(100,100), curr=(200,105) — dx=100, dy=5, snap Y → 100
        assertEquals(100, path.get(1).y());
        // Point 2: prev=(200,100), curr=(300,112) — dx=100, dy=12, snap Y → 100
        assertEquals(100, path.get(2).y());
    }

    @Test
    public void snapToStraight_shouldHandleThresholdBoundaryExact() {
        // Delta exactly at threshold — should snap
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(120, 200),
                new AbsoluteBendpointDto(100, 300));

        PathStraightener.snapToStraight(path, SNAP_THRESHOLD, NO_OBSTACLES);

        assertEquals(100, path.get(1).x());
    }

    @Test
    public void snapToStraight_shouldNotSnapWhenObstacleBlocks() {
        // (100,100) -> (112,200) -> (100,300) — would snap X to 100
        // But obstacle at (90,150,20,60) blocks the snapped segment
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(112, 200),
                new AbsoluteBendpointDto(100, 300));

        List<RoutingRect> obstacles = List.of(
                new RoutingRect(90, 150, 20, 60, "blocker"));

        PathStraightener.snapToStraight(path, SNAP_THRESHOLD, obstacles);

        // Should remain unchanged — obstacle blocks snapped path
        assertEquals(112, path.get(1).x());
    }

    @Test
    public void snapToStraight_shouldNotSnapLTurnCorner() {
        // Payment Engine→API Gateway: (1165,131) -> (640,131) -> (640,119)
        // This is a valid L-turn (horizontal then vertical). Snapping point 1
        // to (640,119) would create a diagonal (1165,131)→(640,119), so the
        // snap must NOT fire. The 12px Y jog is inherent to the terminal port.
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(1165, 131),
                new AbsoluteBendpointDto(640, 131),
                new AbsoluteBendpointDto(640, 119));

        PathStraightener.snapToStraight(path, SNAP_THRESHOLD, NO_OBSTACLES);

        // L-turn corner preserved — no snap
        assertEquals(640, path.get(1).x());
        assertEquals(131, path.get(1).y());
    }

    @Test
    public void snapToStraight_shouldSnapToSuccessorWhenNotLTurn() {
        // Path with kink: (100,100) -> (300,112) -> (500,100)
        // Point 1 has 12px Y offset from prev AND 12px from next
        // Predecessor snap fires (dy=12, dx=200 > dy) — snaps Y to 100
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(300, 112),
                new AbsoluteBendpointDto(500, 100));

        PathStraightener.snapToStraight(path, SNAP_THRESHOLD, NO_OBSTACLES);

        assertEquals(300, path.get(1).x());
        assertEquals(100, path.get(1).y());
    }

    @Test
    public void snapToStraight_shouldSnapSuccessorSameXSmallYJog_whenPrevCurrNotHorizontal() {
        // Tests branch at lines 90-94: dxNext==0, dyNext<=threshold, prev.y!=curr.y
        // (100,100) -> (200,200) -> (200,210) -> (300,210)
        // curr=(200,200), next=(200,210): dxNext=0, dyNext=10<=20, prev.y(100)!=curr.y(200) → snap
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(200, 200),
                new AbsoluteBendpointDto(200, 210),
                new AbsoluteBendpointDto(300, 210));

        PathStraightener.snapToStraight(path, SNAP_THRESHOLD, NO_OBSTACLES);

        // Interior point 1 (200,200) should snap Y to 210 (next.y)
        assertEquals(200, path.get(1).x());
        assertEquals(210, path.get(1).y());
    }

    @Test
    public void snapToStraight_shouldNotSnapSuccessorSameXSmallYJog_whenPrevCurrIsHorizontal() {
        // Tests L-turn protection at lines 90-94: dxNext==0, dyNext<=threshold,
        // but prev.y==curr.y (horizontal prev→curr = valid L-turn, don't snap)
        // (100,200) -> (200,200) -> (200,210) -> (300,210)
        // curr=(200,200), next=(200,210): dxNext=0, dyNext=10, but prev.y(200)==curr.y(200) → NO snap
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(200, 200),
                new AbsoluteBendpointDto(200, 210),
                new AbsoluteBendpointDto(300, 210));

        PathStraightener.snapToStraight(path, SNAP_THRESHOLD, NO_OBSTACLES);

        // L-turn corner preserved — no snap
        assertEquals(200, path.get(1).x());
        assertEquals(200, path.get(1).y());
    }

    @Test
    public void snapToStraight_shouldSnapSuccessorSameYSmallXJog_whenPrevCurrNotVertical() {
        // Tests branch at lines 95-99: dyNext==0, dxNext<=threshold, prev.x!=curr.x
        // (100,100) -> (200,200) -> (210,200) -> (210,300)
        // curr=(200,200), next=(210,200): dyNext=0, dxNext=10<=20, prev.x(100)!=curr.x(200) → snap
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(200, 200),
                new AbsoluteBendpointDto(210, 200),
                new AbsoluteBendpointDto(210, 300));

        PathStraightener.snapToStraight(path, SNAP_THRESHOLD, NO_OBSTACLES);

        // Interior point 1 (200,200) should snap X to 210 (next.x)
        assertEquals(210, path.get(1).x());
        assertEquals(200, path.get(1).y());
    }

    @Test
    public void snapToStraight_shouldNotSnapSuccessorSameYSmallXJog_whenPrevCurrIsVertical() {
        // Tests L-turn protection at lines 95-99: dyNext==0, dxNext<=threshold,
        // but prev.x==curr.x (vertical prev→curr = valid L-turn, don't snap)
        // (200,100) -> (200,200) -> (210,200) -> (210,300)
        // curr=(200,200), next=(210,200): dyNext=0, dxNext=10, but prev.x(200)==curr.x(200) → NO snap
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(200, 100),
                new AbsoluteBendpointDto(200, 200),
                new AbsoluteBendpointDto(210, 200),
                new AbsoluteBendpointDto(210, 300));

        PathStraightener.snapToStraight(path, SNAP_THRESHOLD, NO_OBSTACLES);

        // L-turn corner preserved — no snap
        assertEquals(200, path.get(1).x());
        assertEquals(200, path.get(1).y());
    }

    // ==================== eliminateReversals tests (Task 2) ====================

    @Test
    public void eliminateReversals_shouldCollapseSimpleHorizontalOvershoot() {
        // Overshoot left then doubleback right
        // (200,119) -> (22,119) -> (117,119) -> (117,138)
        // Segments 0 (right→left) and 1 (left→right) are horizontal reversal.
        // start=(200,119), end=(117,138) differ in both axes → L-turn collapse.
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(200, 119),
                new AbsoluteBendpointDto(22, 119),
                new AbsoluteBendpointDto(117, 119),
                new AbsoluteBendpointDto(117, 138));

        PathStraightener.eliminateReversals(path, NO_OBSTACLES);

        // Should collapse to 3 points: start → L-turn midpoint → end
        assertEquals("Reversal should collapse to 3 points", 3, path.size());
        assertEquals(200, path.get(0).x());
        assertEquals(119, path.get(0).y());
        // L-turn midpoint: horizontal-first → (117, 119)
        assertEquals(117, path.get(1).x());
        assertEquals(119, path.get(1).y());
        assertEquals(117, path.get(2).x());
        assertEquals(138, path.get(2).y());
    }

    @Test
    public void eliminateReversals_shouldCollapseVerticalOvershoot() {
        // Path overshoots up then comes back down
        // (100,200) -> (100,50) -> (100,150) -> (200,150)
        // Segments 0 (up) and 1 (down) are vertical reversal.
        // start=(100,200), end=(200,150) differ in both axes → L-turn collapse.
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(100, 50),
                new AbsoluteBendpointDto(100, 150),
                new AbsoluteBendpointDto(200, 150));

        PathStraightener.eliminateReversals(path, NO_OBSTACLES);

        // Should collapse to 3 points: start → L-turn midpoint → end
        assertEquals("Reversal should collapse to 3 points", 3, path.size());
        assertEquals(100, path.get(0).x());
        assertEquals(200, path.get(0).y());
        // L-turn midpoint: horizontal-first → (200, 200)
        assertEquals(200, path.get(1).x());
        assertEquals(200, path.get(1).y());
        assertEquals(200, path.get(2).x());
        assertEquals(150, path.get(2).y());
    }

    @Test
    public void eliminateReversals_shouldNotCollapseWhenBlocked() {
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(200, 100),
                new AbsoluteBendpointDto(50, 100),
                new AbsoluteBendpointDto(150, 100),
                new AbsoluteBendpointDto(150, 200));

        // Obstacle blocks direct path from (200,100) to (150,100)
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(160, 80, 30, 40, "blocker"));

        PathStraightener.eliminateReversals(path, obstacles);

        assertEquals(4, path.size());
    }

    @Test
    public void eliminateReversals_shouldHandleMultiSegmentOvershoot() {
        // (300,100) -> (100,100) -> (100,200) -> (200,200) -> (200,100) -> (400,100)
        // Segments 0 (left) and 3 (right) are horizontal reversal.
        // start=(300,100), end=(400,100) share Y=100 → collinear direct collapse.
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(300, 100),
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(200, 200),
                new AbsoluteBendpointDto(200, 100),
                new AbsoluteBendpointDto(400, 100));

        PathStraightener.eliminateReversals(path, NO_OBSTACLES);

        // Should collapse to 2 points: (300,100) → (400,100) direct
        assertEquals("Multi-segment overshoot should collapse to direct", 2, path.size());
        assertEquals(300, path.get(0).x());
        assertEquals(100, path.get(0).y());
        assertEquals(400, path.get(1).x());
        assertEquals(100, path.get(1).y());
    }

    @Test
    public void eliminateReversals_shouldNotModifyShortPath() {
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(200, 100),
                new AbsoluteBendpointDto(200, 200));

        PathStraightener.eliminateReversals(path, NO_OBSTACLES);

        assertEquals(3, path.size());
    }

    // ==================== collapseBends tests (Task 3) ====================

    @Test
    public void collapseBends_shouldCollapseCollinearPoints() {
        // (100,100) -> (200,100) -> (300,100) -> (400,100) -> (400,200)
        // Points 0-3 are collinear on y=100
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(200, 100),
                new AbsoluteBendpointDto(300, 100),
                new AbsoluteBendpointDto(400, 100),
                new AbsoluteBendpointDto(400, 200));

        PathStraightener.collapseBends(path, NO_OBSTACLES);

        // Collinear points on y=100 should collapse
        assertTrue("Collinear chain should be simplified", path.size() <= 3);
        assertEquals(100, path.get(0).x());
        assertEquals(400, path.get(path.size() - 1).x());
        assertEquals(200, path.get(path.size() - 1).y());
    }

    @Test
    public void collapseBends_shouldNotCollapseWhenBlocked() {
        // Collinear but obstacle blocks direct path
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(200, 100),
                new AbsoluteBendpointDto(300, 100),
                new AbsoluteBendpointDto(300, 200));

        List<RoutingRect> obstacles = List.of(
                new RoutingRect(140, 80, 30, 40, "blocker"));

        PathStraightener.collapseBends(path, obstacles);

        // Can't remove (200,100) because (100,100)→(300,100) hits the obstacle
        assertEquals(4, path.size());
    }

    @Test
    public void collapseBends_shouldPreserveTerminalAnchors() {
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(200, 100),
                new AbsoluteBendpointDto(300, 100),
                new AbsoluteBendpointDto(300, 200));

        PathStraightener.collapseBends(path, NO_OBSTACLES);

        // First and last should remain unchanged
        assertEquals(100, path.get(0).x());
        assertEquals(100, path.get(0).y());
        assertEquals(300, path.get(path.size() - 1).x());
        assertEquals(200, path.get(path.size() - 1).y());
    }

    @Test
    public void collapseBends_shouldNotModifyThreePointPath() {
        // 3 points is minimum for an L-turn — don't touch
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(200, 100),
                new AbsoluteBendpointDto(200, 200));

        PathStraightener.collapseBends(path, NO_OBSTACLES);

        assertEquals(3, path.size());
    }

    @Test
    public void collapseBends_shouldNotModifyTwoPointPath() {
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(200, 200));

        PathStraightener.collapseBends(path, NO_OBSTACLES);

        assertEquals(2, path.size());
    }

    @Test
    public void collapseBends_shouldCollapseVerticalCollinearPoints() {
        // (100,100) -> (200,100) -> (200,150) -> (200,200) -> (300,200)
        // Points 1-3 are collinear on x=200
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(200, 100),
                new AbsoluteBendpointDto(200, 150),
                new AbsoluteBendpointDto(200, 200),
                new AbsoluteBendpointDto(300, 200));

        PathStraightener.collapseBends(path, NO_OBSTACLES);

        // (200,150) should be removed as it's collinear with (200,100) and (200,200)
        assertTrue("Should remove collinear intermediate", path.size() <= 4);
    }

    // ==================== collapseStaircaseJogs tests ====================

    @Test
    public void collapseStaircaseJogs_shouldCollapseHorizontalJog() {
        // Payment Engine→API Gateway with terminals:
        // (1165,166) → (1165,131) → (640,131) → (640,119) → (443,119)
        // Staircase: horizontal at y=131, 12px jog down, horizontal at y=119
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(1165, 166),  // source center
                new AbsoluteBendpointDto(1165, 131),
                new AbsoluteBendpointDto(640, 131),
                new AbsoluteBendpointDto(640, 119),
                new AbsoluteBendpointDto(443, 119));   // target center

        PathStraightener.collapseStaircaseJogs(path, SNAP_THRESHOLD, NO_OBSTACLES);

        // Jog collapsed: (1165,131) shifts to (1165,119), middle points removed
        assertEquals("Should collapse to 3 points", 3, path.size());
        assertEquals(1165, path.get(0).x());
        assertEquals(166, path.get(0).y());   // source preserved
        assertEquals(1165, path.get(1).x());
        assertEquals(119, path.get(1).y());   // shifted from y=131 to y=119
        assertEquals(443, path.get(2).x());
        assertEquals(119, path.get(2).y());   // target preserved
    }

    @Test
    public void collapseStaircaseJogs_shouldCollapseVerticalJog() {
        // Vertical staircase: vertical at x=100, 15px horizontal jog, vertical at x=115
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(100, 50),    // source center
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(100, 300),
                new AbsoluteBendpointDto(115, 300),
                new AbsoluteBendpointDto(115, 400),
                new AbsoluteBendpointDto(115, 500));   // target center

        PathStraightener.collapseStaircaseJogs(path, SNAP_THRESHOLD, NO_OBSTACLES);

        // Jog collapsed: (100,300) shifts to (115,300)→removed, result is straight vertical
        assertEquals(4, path.size());
        assertEquals(100, path.get(0).x());   // source preserved
        assertEquals(115, path.get(1).x());   // shifted from x=100 to x=115
        assertEquals(200, path.get(1).y());   // Y preserved
    }

    @Test
    public void collapseStaircaseJogs_shouldNotCollapseWhenJogExceedsThreshold() {
        // 25px jog > 20px threshold
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(1165, 166),
                new AbsoluteBendpointDto(1165, 131),
                new AbsoluteBendpointDto(640, 131),
                new AbsoluteBendpointDto(640, 106),   // 25px jog
                new AbsoluteBendpointDto(443, 106));

        PathStraightener.collapseStaircaseJogs(path, SNAP_THRESHOLD, NO_OBSTACLES);

        assertEquals("Should not collapse — jog too large", 5, path.size());
    }

    @Test
    public void collapseStaircaseJogs_shouldNotCollapseWhenObstacleBlocks() {
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(1165, 166),
                new AbsoluteBendpointDto(1165, 131),
                new AbsoluteBendpointDto(640, 131),
                new AbsoluteBendpointDto(640, 119),
                new AbsoluteBendpointDto(443, 119));

        // Obstacle blocks the shifted horizontal segment at y=119
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(800, 110, 200, 20, "blocker"));

        PathStraightener.collapseStaircaseJogs(path, SNAP_THRESHOLD, obstacles);

        assertEquals("Should not collapse — obstacle blocks", 5, path.size());
    }

    @Test
    public void collapseStaircaseJogs_shouldPreserveSourceTerminal() {
        // Staircase starts at index 0 (source terminal) — should NOT modify
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(500, 100),   // source center — staircase starts here
                new AbsoluteBendpointDto(300, 100),
                new AbsoluteBendpointDto(300, 112),
                new AbsoluteBendpointDto(100, 112));

        PathStraightener.collapseStaircaseJogs(path, SNAP_THRESHOLD, NO_OBSTACLES);

        // Loop starts at i=1, so staircase at indices 0-3 is not detected
        assertEquals("Source terminal protected", 4, path.size());
        assertEquals(500, path.get(0).x());
        assertEquals(100, path.get(0).y());
    }

    @Test
    public void collapseStaircaseJogs_shouldNotModifyShortPath() {
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(200, 100),
                new AbsoluteBendpointDto(200, 200));

        PathStraightener.collapseStaircaseJogs(path, SNAP_THRESHOLD, NO_OBSTACLES);

        assertEquals(3, path.size());
    }

    // ==================== Pipeline integration tests (terminal prepend/append) ====================

    @Test
    public void eliminateReversals_shouldNotDetectReversalWithoutTerminals() {
        // Real-world scenario: API Gateway→Mobile Banking App path as the pipeline
        // originally provided it — WITHOUT source/target centers. Only 3 BPs.
        // eliminateReversals exits at size < 4 guard → reversal not detected.
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(22, 119),
                new AbsoluteBendpointDto(117, 119),
                new AbsoluteBendpointDto(117, 138));

        PathStraightener.eliminateReversals(path, NO_OBSTACLES);

        // Without terminals, the 3-point path is unchanged — reversal invisible
        assertEquals("Path should be unchanged without terminals", 3, path.size());
        assertEquals(22, path.get(0).x());
    }

    @Test
    public void eliminateReversals_shouldDetectReversalWithTerminals() {
        // Same connection WITH source/target centers prepended/appended (pipeline fix).
        // Source center (443,119), target center (117,166).
        // Reversal: source(443)→BP(22)→BP(117) is LEFT then RIGHT.
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(443, 119),   // source center
                new AbsoluteBendpointDto(22, 119),
                new AbsoluteBendpointDto(117, 119),
                new AbsoluteBendpointDto(117, 138),
                new AbsoluteBendpointDto(117, 166));   // target center

        PathStraightener.eliminateReversals(path, NO_OBSTACLES);

        // Reversal collapsed: (443,119)→(22,119)→(117,119) becomes direct (443,119)→(117,119)
        // Point (22,119) removed. Result: 4 points.
        assertEquals("Reversal should be collapsed", 4, path.size());
        assertEquals(443, path.get(0).x());
        assertEquals(117, path.get(1).x());
        assertEquals(119, path.get(1).y());
        // Verify overshoot point (22,119) is gone
        for (AbsoluteBendpointDto bp : path) {
            assertNotEquals("Overshoot point should be removed", 22, bp.x());
        }
    }

    @Test
    public void eliminateReversals_shouldRespectObstaclesWithTerminals() {
        // Same reversal pattern but with an obstacle blocking the direct path
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(443, 119),   // source center
                new AbsoluteBendpointDto(22, 119),
                new AbsoluteBendpointDto(117, 119),
                new AbsoluteBendpointDto(117, 138),
                new AbsoluteBendpointDto(117, 166));   // target center

        // Obstacle at (200,100,100,40) blocks the direct segment (443,119)→(117,119)
        List<RoutingRect> obstacles = List.of(
                new RoutingRect(200, 100, 100, 40, "blocker"));

        PathStraightener.eliminateReversals(path, obstacles);

        // Reversal detected but blocked — path unchanged
        assertEquals("Path should be unchanged when obstacle blocks", 5, path.size());
        assertEquals(22, path.get(1).x());
    }

    // ==================== Integration / edge case tests ====================

    @Test
    public void allPasses_shouldHandleEmptyPath() {
        List<AbsoluteBendpointDto> path = new ArrayList<>();

        PathStraightener.snapToStraight(path, SNAP_THRESHOLD, NO_OBSTACLES);
        PathStraightener.eliminateReversals(path, NO_OBSTACLES);
        PathStraightener.collapseBends(path, NO_OBSTACLES);

        assertTrue(path.isEmpty());
    }

    @Test
    public void allPasses_shouldHandleSinglePointPath() {
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(100, 100));

        PathStraightener.snapToStraight(path, SNAP_THRESHOLD, NO_OBSTACLES);
        PathStraightener.eliminateReversals(path, NO_OBSTACLES);
        PathStraightener.collapseBends(path, NO_OBSTACLES);

        assertEquals(1, path.size());
    }

    // ==================== Helper methods ====================

    @SafeVarargs
    private static <T> List<T> mutableList(T... items) {
        List<T> list = new ArrayList<>();
        for (T item : items) {
            list.add(item);
        }
        return list;
    }
}
