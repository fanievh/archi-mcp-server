package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import net.vheerden.archi.mcp.model.routing.EdgeAttachmentCalculator;
import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Characterises how two edges sharing one element face can land on a single coincident perimeter
 * slot, feeding each path's output into the real {@link LayoutQualityAssessor#countCoincidentFacePorts}
 * oracle. Findings:
 * <ul>
 *   <li>The unified batch distributor ({@link EdgeAttachmentCalculator#applyEdgeAttachments}) keeps
 *       same-face ports distinct — including a mixed source-exit + target-entry pair.</li>
 *   <li>Re-attaching a connection in isolation (a single-connection group) collapses it to the face
 *       midpoint, so two isolated attachments coincide.</li>
 *   <li>End-to-end, the organic hub collision is produced by downstream terminal stages pulling
 *       a mixed pair onto the face centre line after the batch distributed them — and is resolved by
 *       the pipeline's final coincident-face-port spread pass.</li>
 * </ul>
 */
public class HubPortCollisionDiagnosticTest {

    private final EdgeAttachmentCalculator calc = new EdgeAttachmentCalculator();
    private final LayoutQualityAssessor assessor = new LayoutQualityAssessor();

    // Hub element H: LEFT face at x=400, y 100..300 (height 200, midpoint y=200).
    private static final RoutingRect HUB = new RoutingRect(400, 100, 120, 200, "hub");
    // Two peers to the LEFT of H, at DISTINCT y — natural distinct approach.
    private static final RoutingRect P1 = new RoutingRect(100, 130, 60, 30, "p1");
    private static final RoutingRect P2 = new RoutingRect(100, 250, 60, 30, "p2");

    private static AssessmentNode nodeOf(RoutingRect r) {
        return new AssessmentNode(r.id(), r.x(), r.y(), r.width(), r.height(),
                null, false, false, r.id(), 0.0, null, null, 0.0, 0.0, 0.0);
    }

    /** Bridge a routed connection (peer -> hub) into an AssessmentConnection: [peerCenter, ...bps..., hubCenter]. */
    private static AssessmentConnection toAssessmentConn(String id, RoutingRect src, RoutingRect tgt,
            List<AbsoluteBendpointDto> bendpoints) {
        List<double[]> pts = new ArrayList<>();
        pts.add(new double[]{src.centerX(), src.centerY()});
        for (AbsoluteBendpointDto bp : bendpoints) {
            pts.add(new double[]{bp.x(), bp.y()});
        }
        pts.add(new double[]{tgt.centerX(), tgt.centerY()});
        return new AssessmentConnection(id, src.id(), tgt.id(), pts, "", 1);
    }

    private RoutingPipeline.ConnectionEndpoints conn(String id, RoutingRect src, RoutingRect tgt) {
        return new RoutingPipeline.ConnectionEndpoints(id, src, tgt, List.of(), "", 1);
    }

    @Test
    public void batchPath_twoEdgesOneFace_distributesToDistinctSlots_noCollision() {
        // Both connections peer -> HUB (target face = HUB LEFT). Empty pre-attachment paths.
        List<String> ids = new ArrayList<>(List.of("c1", "c2"));
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        paths.add(new ArrayList<>());
        paths.add(new ArrayList<>());
        List<RoutingPipeline.ConnectionEndpoints> conns =
                List.of(conn("c1", P1, HUB), conn("c2", P2, HUB));

        calc.applyEdgeAttachments(ids, paths, conns);

        AssessmentConnection a1 = toAssessmentConn("c1", P1, HUB, paths.get(0));
        AssessmentConnection a2 = toAssessmentConn("c2", P2, HUB, paths.get(1));
        List<AssessmentNode> nodes = List.of(nodeOf(HUB), nodeOf(P1), nodeOf(P2));

        int count = assessor.countCoincidentFacePorts(List.of(a1, a2), nodes, false).count();
        // Record the two HUB-side target terminal slots for the DAR.
        double s1 = paths.get(0).get(paths.get(0).size() - 1).y();
        double s2 = paths.get(1).get(paths.get(1).size() - 1).y();
        assertEquals("BATCH: two edges on HUB LEFT face must distribute to distinct slots "
                + "(hub-side slots were y=" + s1 + " and y=" + s2 + ") → coincidentFacePortCount",
                0, count);
    }

    @Test
    public void isolationPath_twoEdgesReattachedSeparately_collapseToMidpoint_collision() {
        // Reproduce the selective corridor-reroute contract at RoutingPipeline:1500 — each failed
        // connection is re-attached via applyEdgeAttachments IN ISOLATION (single-connection batch).
        List<AbsoluteBendpointDto> path1 = new ArrayList<>();
        calc.applyEdgeAttachments(new ArrayList<>(List.of("c1")),
                new ArrayList<>(List.of(path1)), List.of(conn("c1", P1, HUB)));

        List<AbsoluteBendpointDto> path2 = new ArrayList<>();
        calc.applyEdgeAttachments(new ArrayList<>(List.of("c2")),
                new ArrayList<>(List.of(path2)), List.of(conn("c2", P2, HUB)));

        AssessmentConnection a1 = toAssessmentConn("c1", P1, HUB, path1);
        AssessmentConnection a2 = toAssessmentConn("c2", P2, HUB, path2);
        List<AssessmentNode> nodes = List.of(nodeOf(HUB), nodeOf(P1), nodeOf(P2));

        int count = assessor.countCoincidentFacePorts(List.of(a1, a2), nodes, true).count();
        double s1 = path1.get(path1.size() - 1).y();
        double s2 = path2.get(path2.size() - 1).y();
        assertTrue("ISOLATION: two edges each re-attached alone collapse to the HUB LEFT midpoint "
                + "(hub-side slots were y=" + s1 + " and y=" + s2 + ") → coincidentFacePortCount must be >= 1",
                count >= 1);
    }

    // ---- Mixed source-exit + target-entry on one face (an organic low-degree hub pattern) ----

    // A degree-4 hub whose LEFT-face midpoint is y=149, with two peers to its left (absolute coords).
    private static final RoutingRect HUB2 = new RoutingRect(632, 104, 200, 90, "hub2");
    private static final RoutingRect PEER_A = new RoutingRect(356, 104, 206, 55, "peerA"); // hub-source exits toward it
    private static final RoutingRect PEER_B = new RoutingRect(356, 229, 206, 55, "peerB"); // enters the hub (hub-target)

    @Test
    public void batchPath_mixedSourceExitAndTargetEntryOneFace_distributesDistinctly() {
        // conn A: hub -> peerA (hub is SOURCE, exits LEFT). conn B: peerB -> hub (hub is TARGET, enters
        // LEFT). Both on the hub's LEFT face but opposite direction. The batch's unified per-face
        // grouping keys source AND target terminals together, so it co-distributes them to distinct
        // slots — the batch is NOT the collision producer even for the mixed-direction case.
        List<String> ids = new ArrayList<>(List.of("cA", "cB"));
        List<List<AbsoluteBendpointDto>> paths = new ArrayList<>();
        paths.add(new ArrayList<>());
        paths.add(new ArrayList<>());
        List<RoutingPipeline.ConnectionEndpoints> conns =
                List.of(conn("cA", HUB2, PEER_A), conn("cB", PEER_B, HUB2));

        calc.applyEdgeAttachments(ids, paths, conns);

        AssessmentConnection a = toAssessmentConn("cA", HUB2, PEER_A, paths.get(0));
        AssessmentConnection b = toAssessmentConn("cB", PEER_B, HUB2, paths.get(1));
        List<AssessmentNode> nodes = List.of(nodeOf(HUB2), nodeOf(PEER_A), nodeOf(PEER_B));
        assertEquals("batch co-distributes a mixed source/target pair on one face → no collision",
                0, assessor.countCoincidentFacePorts(List.of(a, b), nodes, false).count());
    }

}
