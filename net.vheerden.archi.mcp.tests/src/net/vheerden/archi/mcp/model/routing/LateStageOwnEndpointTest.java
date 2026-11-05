package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * The late stages that move corners after the route is found — path simplification and the
 * self-hug correction — keep a route out of its own source and target, which the per-connection
 * obstacle list leaves out. A route may touch its own ends only at its terminals.
 *
 * <p>The geometry is captured from a 44-connection view on which the winning best-of-K run wrote a
 * corner inside the connection's own source (simplification) and, on another connection, a corner
 * inside its own target (self-hug correction). Later stages removed both on that view, but on
 * other views a simplified corner that is outside both ends while its segment crosses one is
 * never repaired.</p>
 *
 * <p>Where one end encloses the other the route must run inside the container to reach the nested
 * end, so the container is not avoided; only the nested end is.</p>
 */
public class LateStageOwnEndpointTest {

    /** Source left face line 1331, target top face line 3341. */
    private static final RoutingRect SIMPLIFY_SOURCE = new RoutingRect(1335, 2600, 620, 635, "src");
    private static final RoutingRect SIMPLIFY_TARGET = new RoutingRect(868, 3345, 620, 635, "tgt");

    /** Source bottom face line 4293; the target sits below it, right face line 446. */
    private static final RoutingRect HUG_SOURCE = new RoutingRect(298, 4219, 300, 70, "src");
    private static final RoutingRect HUG_TARGET = new RoutingRect(142, 4399, 300, 70, "tgt");
    /** The obstacle whose top edge puts the corridor midpoint at y = (4293 + 4579) / 2 = 4436. */
    private static final RoutingRect HUG_WALL = new RoutingRect(0, 4579, 100, 50, "wall");

    // ---- path simplification ----------------------------------------------------------------

    @Test
    public void simplifyFinalPath_shouldTakeTheCornerOutsideItsOwnSource() {
        List<AbsoluteBendpointDto> path = points(1331, 2917, 1235, 2917, 1235, 3335, 1361, 3335, 1361, 3341);

        RoutingPipeline.simplifyFinalPath(path, List.of(), SIMPLIFY_SOURCE, SIMPLIFY_TARGET);

        // the horizontal-first corner (1361, 2917) is inside the source; vertical-first is clear
        assertEquals(points(1331, 2917, 1331, 3341, 1361, 3341), path);
        assertClearOf(path, SIMPLIFY_SOURCE, SIMPLIFY_TARGET);
    }

    @Test
    public void simplifyFinalPath_shouldStillShortcutAJog_whenTheShortcutClearsBothEnds() {
        // control: the same ends, a jog whose horizontal-first shortcut stays outside both
        List<AbsoluteBendpointDto> path = points(1331, 2917, 1300, 2917, 1300, 3000, 1200, 3000, 1200, 3341);

        RoutingPipeline.simplifyFinalPath(path, List.of(), SIMPLIFY_SOURCE, SIMPLIFY_TARGET);

        assertEquals(points(1331, 2917, 1200, 2917, 1200, 3341), path);
    }

    @Test
    public void simplifyFinalPath_shouldLeaveTheJog_whenTheOnlyOtherShortcutIsBlocked() {
        // The shortcut's horizontal-first corner (150, 150) lies inside the source and its
        // vertical-first leg crosses an obstacle, so the jog stays as routed.
        RoutingRect source = new RoutingRect(100, 100, 100, 100, "src");
        RoutingRect target = new RoutingRect(0, 300, 300, 100, "tgt");
        RoutingRect obstacle = new RoutingRect(80, 220, 30, 20, "obs");
        List<AbsoluteBendpointDto> before = points(96, 150, 50, 150, 50, 250, 150, 250, 150, 296);
        List<AbsoluteBendpointDto> path = new ArrayList<>(before);

        RoutingPipeline.simplifyFinalPath(path, List.of(obstacle), source, target);

        assertEquals(before, path);
    }

    // ---- self-hug correction ----------------------------------------------------------------

    @Test
    public void correctSourceSelfHug_shouldNotMoveTheHugIntoItsOwnTarget() {
        List<AbsoluteBendpointDto> path = points(400, 4293, 446, 4293, 446, 4434);
        List<AbsoluteBendpointDto> before = new ArrayList<>(path);

        boolean corrected = RoutingPipeline.correctSourceSelfHug(path, List.of(HUG_WALL),
                new TerminalAnchoring(EdgeAttachmentCalculator.Face.BOTTOM), HUG_SOURCE, HUG_TARGET);

        assertFalse("the corridor midpoint y=4436 lies inside the target", corrected);
        assertEquals(before, path);
    }

    @Test
    public void correctSourceSelfHug_shouldStillCorrectAHug_whenTheCorridorClearsBothEnds() {
        // control: the target moved clear of the corridor, so the same hug is corrected
        RoutingRect target = new RoutingRect(700, 4399, 300, 70, "tgt");
        List<AbsoluteBendpointDto> path = points(400, 4293, 446, 4293, 446, 4434);

        boolean corrected = RoutingPipeline.correctSourceSelfHug(path, List.of(HUG_WALL),
                new TerminalAnchoring(EdgeAttachmentCalculator.Face.BOTTOM), HUG_SOURCE, target);

        assertTrue(corrected);
        assertEquals(points(400, 4293, 400, 4436, 446, 4436, 446, 4434), path);
    }

    @Test
    public void correctTargetSelfHug_shouldNotMoveTheHugIntoItsOwnSource() {
        // the mirror: the same two boxes with the roles swapped, so the hug is on this
        // connection's target's bottom face and the corridor runs into its source
        RoutingRect source = HUG_TARGET;
        RoutingRect target = HUG_SOURCE;
        List<AbsoluteBendpointDto> path = points(446, 4434, 446, 4293, 400, 4293);
        List<AbsoluteBendpointDto> before = new ArrayList<>(path);

        boolean corrected = RoutingPipeline.correctTargetSelfHug(path, List.of(HUG_WALL),
                new TerminalAnchoring(EdgeAttachmentCalculator.Face.BOTTOM), source, target);

        assertFalse("the corridor midpoint y=4436 lies inside the source", corrected);
        assertEquals(before, path);
    }

    @Test
    public void correctSourceSelfHug_shouldNotLeadTheCorrectedRunThroughItsOwnTarget() {
        // The corner (400, 4446) and the run to the corrected point (446, 4446) are clear; the run
        // on from the corrected point down to the next bendpoint is the one that crosses the target.
        RoutingRect target = new RoutingRect(430, 4460, 40, 30, "tgt");
        RoutingRect wall = new RoutingRect(0, 4600, 50, 50, "wall");
        List<AbsoluteBendpointDto> path = points(400, 4293, 446, 4293, 446, 4520, 500, 4520);
        List<AbsoluteBendpointDto> before = new ArrayList<>(path);

        boolean corrected = RoutingPipeline.correctSourceSelfHug(path, List.of(wall),
                new TerminalAnchoring(EdgeAttachmentCalculator.Face.BOTTOM), HUG_SOURCE, target);

        assertFalse(corrected);
        assertEquals(before, path);
    }

    // ---- a container end ----------------------------------------------------------------------

    @Test
    public void ownEndsToAvoid_shouldKeepOnlyTheNestedEnd_whenOneEndEnclosesTheOther() {
        RoutingRect container = new RoutingRect(0, 0, 400, 300, "container");
        RoutingRect child = new RoutingRect(50, 50, 100, 60, "child");
        assertEquals(List.of(child), RoutingPipeline.ownEndsToAvoid(child, container));
        assertEquals(List.of(child), RoutingPipeline.ownEndsToAvoid(container, child));
        assertEquals(List.of(SIMPLIFY_SOURCE, SIMPLIFY_TARGET),
                RoutingPipeline.ownEndsToAvoid(SIMPLIFY_SOURCE, SIMPLIFY_TARGET));
        // a self-connection's one object, once
        assertEquals(List.of(child), RoutingPipeline.ownEndsToAvoid(child, child));
        // two objects on the same bounds enclose nothing: both are avoided
        RoutingRect twin = new RoutingRect(50, 50, 100, 60, "twin");
        assertEquals(List.of(child, twin), RoutingPipeline.ownEndsToAvoid(child, twin));
    }

    @Test
    public void fixInteriorTerminalBPs_shouldCloseTheGapInsideAContainerEnd() {
        // child -> container: the corner inside the child is removed, and the gap is closed by the
        // horizontal-first corner (404, 46). Its run along y = 46 lies inside the container, which
        // is allowed because the route has to run inside the container to reach the child
        RoutingRect container = new RoutingRect(0, 0, 400, 300, "container");
        RoutingRect child = new RoutingRect(50, 50, 100, 60, "child");
        List<AbsoluteBendpointDto> path = points(100, 46, 100, 80, 404, 80);
        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", child, container, List.of(), null, 1);

        int fixes = RoutingPipeline.fixInteriorTerminalBPs(path, conn);

        assertEquals(1, fixes);
        assertEquals(points(100, 46, 404, 46, 404, 80), path);
        assertClearOf(path, child);
    }

    @Test
    public void fixInteriorTerminalBPs_shouldNotRemoveACornerInsideAContainerEndOnly() {
        // a corner inside the container but outside the child is where the route has to be
        RoutingRect container = new RoutingRect(0, 0, 400, 300, "container");
        RoutingRect child = new RoutingRect(50, 50, 100, 60, "child");
        List<AbsoluteBendpointDto> path = points(154, 80, 250, 80, 250, 304);
        List<AbsoluteBendpointDto> before = new ArrayList<>(path);
        RoutingPipeline.ConnectionEndpoints conn =
                new RoutingPipeline.ConnectionEndpoints("c1", child, container, List.of(), null, 1);

        assertEquals(0, RoutingPipeline.fixInteriorTerminalBPs(path, conn));
        assertEquals(before, path);
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static List<AbsoluteBendpointDto> points(int... xy) {
        List<AbsoluteBendpointDto> out = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) {
            out.add(new AbsoluteBendpointDto(xy[i], xy[i + 1]));
        }
        return out;
    }

    private static void assertClearOf(List<AbsoluteBendpointDto> path, RoutingRect... ends) {
        for (int i = 0; i + 1 < path.size(); i++) {
            AbsoluteBendpointDto a = path.get(i);
            AbsoluteBendpointDto b = path.get(i + 1);
            assertTrue("segment " + i + " is axis-aligned: " + path, a.x() == b.x() || a.y() == b.y());
            for (RoutingRect r : ends) {
                boolean enters = Math.max(a.x(), b.x()) > r.x() && Math.min(a.x(), b.x()) < r.x() + r.width()
                        && Math.max(a.y(), b.y()) > r.y() && Math.min(a.y(), b.y()) < r.y() + r.height();
                assertFalse("segment " + i + " stays out of " + r.id() + ": " + path, enters);
            }
        }
    }
}
