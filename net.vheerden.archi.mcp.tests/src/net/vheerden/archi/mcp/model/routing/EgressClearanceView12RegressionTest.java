package net.vheerden.archi.mcp.model.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Option B regression pin: reconstructs the LIVE final-stage geometry of view 1.2
 * "Behaviour — Mutation Request Flow" (read 2026-06-29 via get-view-contents) and runs
 * {@link TerminalEgressClearancePass#run}. Because the pass is the LAST geometry-mutating stage,
 * feeding it the final stored bendpoints is a faithful reproduction of what it sees live — closing
 * the verification gap that sank the earlier {@code EdgeAttachmentCalculator} clamp (whose headless
 * repro tested an isolated stage, never the downstream-created hug).
 *
 * <p>Element rects are absolute (= containing group xy + local xy). The defect: {@code e5946477}
 * (E10 source BOTTOM face &rarr; E5 target) departs the bottom face at (472,110) then runs a
 * horizontal trunk to (1276,110), hugging the face it just exited (~1.5px). Two LEFT-face target
 * hugs ({@code cdda}, {@code 9569}) hug at ~1px. Pre-Option-B the pass produced {@code applied=0};
 * Option B (axis-correct gap floor + healthy-floor netImproves) lifts the egress stubs clear.
 */
public class EgressClearanceView12RegressionTest {

    private final TerminalEgressClearancePass pass = new TerminalEgressClearancePass();

    private static AbsoluteBendpointDto bp(int x, int y) { return new AbsoluteBendpointDto(x, y); }
    private static List<AbsoluteBendpointDto> path(AbsoluteBendpointDto... b) {
        return new ArrayList<>(List.of(b));
    }

    static final RoutingRect E1  = new RoutingRect(1899, 54, 182, 55, "e1");   // Issue MCP tool call
    static final RoutingRect E2  = new RoutingRect(1522, 54, 214, 55, "e2");   // Receive on Jetty thread
    static final RoutingRect E3  = new RoutingRect(1522, 199, 222, 68, "e3");  // Return response envelope
    static final RoutingRect E4  = new RoutingRect(1185, 54, 182, 55, "e4");   // Validate parameters
    static final RoutingRect E5  = new RoutingRect(1185, 199, 182, 55, "e5");  // Format DTO response (TARGET)
    static final RoutingRect E6  = new RoutingRect(768, 54, 158, 55, "e6");    // Prepare mutation
    static final RoutingRect E7  = new RoutingRect(768, 199, 262, 55, "e7");   // Dispatch via Display.syncExec
    static final RoutingRect E8  = new RoutingRect(768, 344, 14, 14, "e8");    // approval gate (junction)
    static final RoutingRect E9  = new RoutingRect(30, 54, 214, 68, "e9");     // Review & approve change
    static final RoutingRect E10 = new RoutingRect(399, 54, 214, 55, "e10");   // Execute on CommandStack (SOURCE)

    static final List<RoutingRect> ALL = List.of(E1, E2, E3, E4, E5, E6, E7, E8, E9, E10);

    private static RoutingPipeline.ConnectionEndpoints c(String id, RoutingRect s, RoutingRect t) {
        List<RoutingRect> obs = new ArrayList<>(ALL);
        obs.remove(s);
        obs.remove(t);
        return new RoutingPipeline.ConnectionEndpoints(id, s, t, obs, null, 0);
    }

    private static void build(List<RoutingPipeline.ConnectionEndpoints> conns,
                              List<List<AbsoluteBendpointDto>> paths) {
        conns.add(c("1b87", E1, E2));  paths.add(path(bp(1898, 81), bp(1737, 81)));
        conns.add(c("b9b9", E2, E4));  paths.add(path(bp(1521, 81), bp(1368, 81)));
        conns.add(c("3d4b", E4, E6));  paths.add(path(bp(1184, 81), bp(927, 81)));
        conns.add(c("cdda", E5, E3));  paths.add(path(bp(1368, 226), bp(1521, 226), bp(1521, 233)));
        conns.add(c("032c", E6, E7));  paths.add(path(bp(847, 110), bp(847, 118), bp(899, 118), bp(899, 198)));
        conns.add(c("4d85", E7, E8));  paths.add(path(bp(899, 255), bp(899, 299), bp(775, 299)));
        conns.add(c("4964", E8, E9));  paths.add(path(bp(767, 351), bp(137, 351), bp(137, 123)));
        conns.add(c("62d6", E8, E10)); paths.add(path(bp(767, 306), bp(540, 306), bp(540, 110)));
        conns.add(c("9569", E9, E10)); paths.add(path(bp(245, 88), bp(398, 88), bp(398, 81)));
        conns.add(c("e594", E10, E5)); paths.add(path(bp(472, 110), bp(1276, 110), bp(1276, 198)));
    }

    /** e5946477 (the named defect) is lifted clear of the BOTTOM face by >= the egress target. */
    @Test
    public void view12_e5946477_sourceBottomHug_isCleared() {
        List<RoutingPipeline.ConnectionEndpoints> conns = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        build(conns, paths);
        int idx = conns.size() - 1; // e594 is last

        TerminalEgressClearancePass.Result r = pass.run(conns, paths, ALL);
        List<AbsoluteBendpointDto> e = paths.get(idx);
        System.out.println("VIEW12 applied=" + r.applied() + " rolled=" + r.rolled()
                + " evaluated=" + r.proposalsEvaluated() + " e5946477=" + e);

        assertTrue("at least the source-bottom hug must be fixed", r.applied() >= 1);
        int termY = e.get(0).y();                       // terminal on bottom face line (110)
        int firstStubY = e.get(1).y();                  // first interior point after the fix
        int clearance = Math.abs(firstStubY - termY);
        assertEquals("terminal byte-identical (perimeter-immutable)", 472, e.get(0).x());
        assertEquals("terminal byte-identical (perimeter-immutable)", 110, termY);
        assertTrue("egress stub clears the face by >= 8px, got " + clearance + " path=" + e,
                clearance >= TerminalEgressClearancePass.TARGET_EGRESS_CLEARANCE_PX);
    }

    /**
     * Short-run story (router-egress-shortrun-microhug): ALL THREE off-face hugs clear — the long
     * horizontal {@code e5946477} (already cleared by Option B) PLUS the two LEFT-face 7px target
     * micro-hugs {@code cdda} (E5&rarr;E3) and {@code 9569} (E9&rarr;E10), which Option B left below
     * its 10px detection/keep granularity. {@code applied} rises to 3; each LEFT terminal is lifted
     * so its first interior point leaves the face perpendicularly, terminals byte-identical.
     */
    @Test
    public void view12_allThreeOffFaceHugs_cleared() {
        List<RoutingPipeline.ConnectionEndpoints> conns = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        build(conns, paths);
        int idxCdda = 3;   // c("cdda", E5, E3)
        int idx9569 = 8;   // c("9569", E9, E10)

        TerminalEgressClearancePass.Result r = pass.run(conns, paths, ALL);
        List<AbsoluteBendpointDto> cdda = paths.get(idxCdda);
        List<AbsoluteBendpointDto> n9569 = paths.get(idx9569);
        System.out.println("VIEW12-ALL3 applied=" + r.applied() + " rolled=" + r.rolled()
                + " evaluated=" + r.proposalsEvaluated() + " cdda=" + cdda + " 9569=" + n9569);

        assertEquals("all three off-face hugs cleared", 3, r.applied());

        // cdda target terminal on E3 LEFT face line (x=1521): byte-identical, and its first interior
        // point now leaves the face perpendicularly (same y=233, pushed left off the face line).
        AbsoluteBendpointDto cddaTerm = cdda.get(cdda.size() - 1);
        AbsoluteBendpointDto cddaAdj = cdda.get(cdda.size() - 2);
        assertEquals("cdda target terminal byte-identical (perimeter-immutable)", bp(1521, 233), cddaTerm);
        assertEquals("cdda first interior point shares terminal y (perpendicular egress)", 233, cddaAdj.y());
        assertTrue("cdda stub lifted >= TARGET off the face edge (1522), got x=" + cddaAdj.x(),
                (1522 - cddaAdj.x()) >= TerminalEgressClearancePass.TARGET_EGRESS_CLEARANCE_PX);

        // 9569 target terminal on E10 LEFT face line (x=398): same shape.
        AbsoluteBendpointDto n9569Term = n9569.get(n9569.size() - 1);
        AbsoluteBendpointDto n9569Adj = n9569.get(n9569.size() - 2);
        assertEquals("9569 target terminal byte-identical (perimeter-immutable)", bp(398, 81), n9569Term);
        assertEquals("9569 first interior point shares terminal y (perpendicular egress)", 81, n9569Adj.y());
        assertTrue("9569 stub lifted >= TARGET off the face edge (399), got x=" + n9569Adj.x(),
                (399 - n9569Adj.x()) >= TerminalEgressClearancePass.TARGET_EGRESS_CLEARANCE_PX);
    }

    /** Idempotency (AC-5): re-running on the fixed geometry is a no-op. */
    @Test
    public void view12_isIdempotent() {
        List<RoutingPipeline.ConnectionEndpoints> conns = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        build(conns, paths);
        pass.run(conns, paths, ALL);
        List<List<AbsoluteBendpointDto>> afterFirst = deepCopy(paths);
        TerminalEgressClearancePass.Result second = pass.run(conns, paths, ALL);
        assertEquals("second pass applies nothing", 0, second.applied());
        assertEquals("geometry unchanged on re-run", afterFirst, paths);
    }

    private static List<List<AbsoluteBendpointDto>> deepCopy(List<List<AbsoluteBendpointDto>> in) {
        List<List<AbsoluteBendpointDto>> out = new ArrayList<>();
        for (List<AbsoluteBendpointDto> p : in) out.add(new ArrayList<>(p));
        return out;
    }
}
