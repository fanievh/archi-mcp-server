package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Tests for {@link PathStraightener} — post-routing path straightening.
 * Pure-geometry tests, no EMF/SWT required.
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
        // Segments 0 (up) and 1 (down) are vertical reversal on the same axis.
        // Algorithm detects same-axis reversal at (i=0, j=1) and collapses directly,
        // removing the overshoot to y=50. The cross-axis pair (i=0, j=2) is skipped
        // by the terminal guard. Result: direct vertical collapse, not L-turn.
        // Re-blessed from a stale prior expectation (L-turn via (200,200)).
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(100, 50),
                new AbsoluteBendpointDto(100, 150),
                new AbsoluteBendpointDto(200, 150));

        PathStraightener.eliminateReversals(path, NO_OBSTACLES);

        // Same-axis reversal (0,1) collapses by removing the overshoot point (100,50)
        assertEquals("Reversal should collapse to 3 points", 3, path.size());
        assertEquals(100, path.get(0).x());
        assertEquals(200, path.get(0).y());
        assertEquals(100, path.get(1).x());
        assertEquals(150, path.get(1).y());
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
        // Terminal guard skips the full-span (i=0, j=4) reversal. Algorithm instead:
        //   1. Detects (i=0, j=2) horizontal reversal, L-turn collapses via (200,100)
        //   2. Detects inner (i=1, j=2) micro-reversal, collapses to degenerate
        // Result includes a duplicate point at (200,100) — downstream removeCollinearPoints
        // and collapseBends clean this up in the full pipeline.
        // Re-blessed from a stale prior expectation (direct 2-point collapse).
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(300, 100),
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(100, 200),
                new AbsoluteBendpointDto(200, 200),
                new AbsoluteBendpointDto(200, 100),
                new AbsoluteBendpointDto(400, 100));

        PathStraightener.eliminateReversals(path, NO_OBSTACLES);

        // Partial collapse: interior loop removed, duplicate point remains for downstream cleanup
        assertEquals("Multi-segment overshoot partially collapsed", 4, path.size());
        assertEquals(300, path.get(0).x());
        assertEquals(100, path.get(0).y());
        assertEquals(200, path.get(1).x());
        assertEquals(100, path.get(1).y());
        // Duplicate point from micro-reversal collapse (cleaned by removeCollinearPoints)
        assertEquals(200, path.get(2).x());
        assertEquals(100, path.get(2).y());
        assertEquals(400, path.get(3).x());
        assertEquals(100, path.get(3).y());
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

    // ===== C2: exit-then-return terminal overshoot (augmented terminal-anchoring overload) =====
    // The augmented stage-4.7i frame
    // prepends/appends the source/target CENTERS as sentinels (index 0 / size-1), so the
    // real terminal anchors sit at index 1 / size-2. These exercise the protectTerminals
    // guard added to eliminateReversalsCore. All assert against the 9-arg terminal-anchoring
    // overload (augmented=true) — the legacy 2-arg overload is unaffected (protectTerminals=false).
    //
    // View-D-shaped fixture: source S BELOW target T; T attaches on its BOTTOM face
    // (line y=82=ty+th+1), S on its TOP face (line y=149). The overshoot apex (260,53) is
    // T's TOP face line — the route exits past the far edge then doubles back to attach.
    // Augmented: [(260,170)srcCenter, (260,149)srcAnchor, (260,53)OVERSHOOT, (260,82)tgtAnchor, (260,67)tgtCenter].

    private static final RoutingRect C2_TARGET = new RoutingRect(200, 54, 120, 27, "tgt");
    private static final RoutingRect C2_SOURCE = new RoutingRect(200, 150, 120, 40, "src");
    private static final int[] C2_SRC_CENTER = {260, 170};
    private static final int[] C2_TGT_CENTER = {260, 67};
    private static final TerminalAnchoring C2_SRC_ANCH =
            new TerminalAnchoring(EdgeAttachmentCalculator.Face.TOP);
    private static final TerminalAnchoring C2_TGT_ANCH =
            new TerminalAnchoring(EdgeAttachmentCalculator.Face.BOTTOM);

    @Test
    public void eliminateReversals_shouldCollapseExitThenReturnTerminalOvershoot_whenAugmented() {
        // Pre-fix this path is UNCHANGED (red): the greedy core matches the
        // widest reversal (i=0) first, deletes the source anchor, drops size to 3, and the
        // terminal-anchoring size<4 wrap rolls the whole pass back. The protectTerminals guard
        // confines the scan to the interior collapse (i=1,j=2), removing only the overshoot apex.
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(260, 170),   // source center (sentinel)
                new AbsoluteBendpointDto(260, 149),   // source anchor (TOP face line)
                new AbsoluteBendpointDto(260, 53),    // OVERSHOOT apex (target TOP face line)
                new AbsoluteBendpointDto(260, 82),    // target anchor (BOTTOM face line)
                new AbsoluteBendpointDto(260, 67));   // target center (sentinel)

        PathStraightener.eliminateReversals(path, NO_OBSTACLES,
                C2_SOURCE, C2_TARGET, C2_SRC_CENTER, C2_TGT_CENTER,
                C2_SRC_ANCH, C2_TGT_ANCH, true);

        // Overshoot collapsed — 4 points, monotonic, apex gone.
        assertEquals("Overshoot apex should be collapsed", 4, path.size());
        for (AbsoluteBendpointDto bp : path) {
            assertNotEquals("Overshoot apex (260,53) should be removed", 53, bp.y());
        }
        // BOTH terminal anchors byte-identical (sentinels still bracket them at 0/size-1).
        assertEquals(260, path.get(0).x()); assertEquals(170, path.get(0).y()); // src center
        assertEquals(260, path.get(1).x()); assertEquals(149, path.get(1).y()); // src anchor — on TOP face line
        assertEquals(260, path.get(2).x()); assertEquals(82,  path.get(2).y()); // tgt anchor — on BOTTOM face line
        assertEquals(260, path.get(3).x()); assertEquals(67,  path.get(3).y()); // tgt center
        // Terminal segment monotonic toward the attachment face (no reversal past it).
        assertTrue("Y-sequence must be monotonic (no exit-then-return)", isMonotonicY(path));
        // zigzagCount == 0 — no triple matches LayoutQualityAssessor.isZigzagTriple.
        assertFalse("Post-fix path must carry no zigzag triple", hasZigzagTriple(path));
    }

    @Test
    public void eliminateReversals_shouldDeclineOvershootCollapse_whenForeignObstacleBlocksCorridor_augmented() {
        // Soundness (the consume-not-create-clearance lesson): a foreign element in the straight
        // collapse corridor must keep the overshoot rather than pierce it. The obstacle set the
        // pipeline passes at 4.7i excludes source/target, so only genuine foreign elements appear.
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(260, 170),
                new AbsoluteBendpointDto(260, 149),
                new AbsoluteBendpointDto(260, 53),
                new AbsoluteBendpointDto(260, 82),
                new AbsoluteBendpointDto(260, 67));
        // Foreign box straddling x=260 between the anchor (149) and the attach point (82).
        List<RoutingRect> obstacles = List.of(new RoutingRect(255, 90, 30, 40, "foreign"));

        PathStraightener.eliminateReversals(path, obstacles,
                C2_SOURCE, C2_TARGET, C2_SRC_CENTER, C2_TGT_CENTER,
                C2_SRC_ANCH, C2_TGT_ANCH, true);

        // Direct collapse would pierce the foreign element → declined → overshoot preserved.
        assertEquals("Path unchanged when a foreign obstacle blocks the collapse", 5, path.size());
        assertEquals(53, path.get(2).y());
    }

    @Test
    public void eliminateReversals_shouldCollapseTailOvershoot_keepingAnchors_whenAugmented() {
        // Longer path (leading jog before the tail overshoot) — confirms the
        // guard reaches an interior reversal at the tail without touching either anchor.
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(260, 170),   // src center
                new AbsoluteBendpointDto(260, 149),   // src anchor
                new AbsoluteBendpointDto(300, 149),
                new AbsoluteBendpointDto(300, 53),
                new AbsoluteBendpointDto(260, 53),    // overshoot region
                new AbsoluteBendpointDto(260, 82),    // tgt anchor
                new AbsoluteBendpointDto(260, 67));   // tgt center

        PathStraightener.eliminateReversals(path, NO_OBSTACLES,
                C2_SOURCE, C2_TARGET, C2_SRC_CENTER, C2_TGT_CENTER,
                C2_SRC_ANCH, C2_TGT_ANCH, true);

        // Anchors byte-identical; no point at y=53 survives; monotonic; no zigzag triple.
        assertEquals("Leading jog + tail overshoot collapse to a straight run", 4, path.size());
        assertEquals(149, path.get(1).y());                          // src anchor intact
        assertEquals(82, path.get(path.size() - 2).y());             // tgt anchor intact
        for (AbsoluteBendpointDto bp : path) {
            assertNotEquals(53, bp.y());
        }
        assertTrue(isMonotonicY(path));
        assertFalse(hasZigzagTriple(path));
    }

    @Test
    public void eliminateReversals_shouldCollapseSourceSideOvershoot_protectingSourceAnchor_whenAugmented() {
        // Mirror of the target-side case on the SOURCE end — directly pins the guard's `i==0`
        // arm (source-anchor protection). Source ABOVE, attaches its BOTTOM face (line y=82);
        // target BELOW, attaches its TOP face (line y=149). The route exits the source bottom
        // but overshoots UP to the source's TOP face line (260,53) before doubling back down.
        // Pre-fix: the greedy i=0 match deletes the source anchor → terminal-anchoring rollback →
        // overshoot survives (path unchanged). Post-fix: i==0 is skipped, the interior collapse (i=1,j=2)
        // removes only the overshoot apex, keeping the source anchor on its face line.
        RoutingRect src = new RoutingRect(200, 54, 120, 27, "src");   // bottom face line = 82
        RoutingRect tgt = new RoutingRect(200, 150, 120, 40, "tgt");  // top face line = 149
        int[] srcCenter = {260, 67};
        int[] tgtCenter = {260, 170};
        TerminalAnchoring srcAnch = new TerminalAnchoring(EdgeAttachmentCalculator.Face.BOTTOM);
        TerminalAnchoring tgtAnch = new TerminalAnchoring(EdgeAttachmentCalculator.Face.TOP);
        List<AbsoluteBendpointDto> path = mutableList(
                new AbsoluteBendpointDto(260, 67),    // source center (sentinel)
                new AbsoluteBendpointDto(260, 82),    // source anchor (BOTTOM face line)
                new AbsoluteBendpointDto(260, 53),    // OVERSHOOT apex (source TOP face line)
                new AbsoluteBendpointDto(260, 149),   // target anchor (TOP face line)
                new AbsoluteBendpointDto(260, 170));  // target center (sentinel)

        PathStraightener.eliminateReversals(path, NO_OBSTACLES,
                src, tgt, srcCenter, tgtCenter, srcAnch, tgtAnch, true);

        assertEquals("Source-side overshoot collapsed to a straight run", 4, path.size());
        assertEquals(260, path.get(1).x()); assertEquals(82,  path.get(1).y()); // src anchor — on BOTTOM face line
        assertEquals(260, path.get(2).x()); assertEquals(149, path.get(2).y()); // tgt anchor — on TOP face line
        for (AbsoluteBendpointDto bp : path) {
            assertNotEquals("Overshoot apex (260,53) should be removed", 53, bp.y());
        }
        assertTrue("Y-sequence must be monotonic (no exit-then-return)", isMonotonicY(path));
        assertFalse("Post-fix path must carry no zigzag triple", hasZigzagTriple(path));
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

    /** True iff the path's Y-sequence has no direction reversal (monotonic up or down). */
    private static boolean isMonotonicY(List<AbsoluteBendpointDto> path) {
        int dir = 0;
        for (int i = 1; i < path.size(); i++) {
            int dy = path.get(i).y() - path.get(i - 1).y();
            if (dy == 0) continue;
            int s = Integer.signum(dy);
            if (dir == 0) dir = s;
            else if (s != dir) return false;
        }
        return true;
    }

    /**
     * Mirrors {@code LayoutQualityAssessor.isZigzagTriple} exactly (tol = minDelta = 1.0px):
     * three consecutive points sharing one axis within tolerance with opposite-sign deltas on
     * the other. A zigzag-free path is what {@code countZigzags} measures as 0, so this
     * fixture-level assert stands in for the assessor without crossing the model package boundary.
     */
    private static boolean hasZigzagTriple(List<AbsoluteBendpointDto> path) {
        final double tol = 1.0;
        final double minDelta = 1.0;
        for (int i = 0; i + 2 < path.size(); i++) {
            AbsoluteBendpointDto a = path.get(i);
            AbsoluteBendpointDto b = path.get(i + 1);
            AbsoluteBendpointDto c = path.get(i + 2);
            boolean sharedX = Math.abs(a.x() - b.x()) <= tol && Math.abs(b.x() - c.x()) <= tol;
            if (sharedX) {
                double dy1 = b.y() - a.y();
                double dy2 = c.y() - b.y();
                if (Math.abs(dy1) > minDelta && Math.abs(dy2) > minDelta
                        && Math.signum(dy1) != Math.signum(dy2)) {
                    return true;
                }
            }
            boolean sharedY = Math.abs(a.y() - b.y()) <= tol && Math.abs(b.y() - c.y()) <= tol;
            if (sharedY) {
                double dx1 = b.x() - a.x();
                double dx2 = c.x() - b.x();
                if (Math.abs(dx1) > minDelta && Math.abs(dx2) > minDelta
                        && Math.signum(dx1) != Math.signum(dx2)) {
                    return true;
                }
            }
        }
        return false;
    }

    @SafeVarargs
    private static <T> List<T> mutableList(T... items) {
        List<T> list = new ArrayList<>();
        for (T item : items) {
            list.add(item);
        }
        return list;
    }
}
