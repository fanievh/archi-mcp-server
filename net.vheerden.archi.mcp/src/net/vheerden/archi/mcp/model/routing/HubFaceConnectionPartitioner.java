package net.vheerden.archi.mcp.model.routing;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Hub-perimeter routing — foundational primitive: partition routed connections into per-(hub, face)
 * cells for the {@link HubPerimeterRoutingStage}.
 *
 * <p><b>Architectural commitment (load-bearing):</b> routing quality is a global
 * property of the route set, not a local property of individual edges or connections.
 * This partitioner assembles the per-(hub, face) cell route-sets that
 * {@link AlternativeCorridorSelector} (Axis 1 — corridor-CHOICE diversification) and
 * {@link CorridorSpreadEnforcer} (Axis 2 — intra-corridor SPREAD enforcement) operate
 * on. The two axes must be visible simultaneously: hub-perimeter quality is a
 * 2-axis simultaneous property per the V4 chain mechanism-design synthesis.
 *
 * <p><b>Hub detection.</b>
 * An element is a hub if its incident-connection degree is greater than or equal to
 * {@link #HUB_DETECTION_THRESHOLD} (5). The threshold value mirrors
 * {@code LayoutQualityAssessor.HUB_DETECTION_THRESHOLD}, but
 * is inlined locally because {@code LayoutQualityAssessor} is package-private to
 * {@code net.vheerden.archi.mcp.model} (not visible from this {@code .routing}
 * sub-package) and elevating its visibility is forbidden. The local degree-counter
 * duplicates the assessor's internal counter; this is acceptable per sibling-API
 * discipline. If the assessor's threshold ever changes, this constant must be updated
 * in lockstep — pin-test {@code v4OracleAllSixThresholds_pinReleaseGate} is the
 * regression guard.
 *
 * <p><b>Cell membership.</b> A connection is a member of a (hub, face) cell if it has
 * at least one axis-aligned segment within {@link #PROXIMITY_TOLERANCE_PX} pixels of the
 * face edge AND whose parallel extent overlaps the face by at least
 * {@link #MIN_PARALLEL_OVERLAP_PX} pixels. A single segment can be a member of multiple
 * cells (e.g., a long horizontal run abutting both hub-A TOP and hub-B TOP). Multi-cell
 * membership is intentional — the partitioner assembles a complete view of hub-perimeter
 * incidence, leaving per-cell action decisions to the corridor-choice and spread
 * primitives.
 *
 * <p><b>Proximity tolerance choice (5px).</b> Wider than the assessor's M4 trigger
 * tolerance (3px per {@code EDGE_COINCIDENCE_TOLERANCE_PX}) so that connections currently
 * near-but-not-flagging M4 are included in cell scope. This gives the Axis 1/2 primitives
 * margin to act proactively before connections trip the M4 trigger — a 4px gap today is
 * a 2px gap tomorrow after a different upstream change. The design intent is "within
 * {@code EDGE_COINCIDENCE_FAIR_MAX = 5px} of any hub face" — note that constant
 * reference is to the grading threshold (count) not the proximity tolerance;
 * the intent (5px proximity) is the load-bearing scope.
 *
 * <p><b>Parallel-overlap requirement (10px).</b> Matches the assessor's M4 algorithm
 * exactly ({@code EDGE_COINCIDENCE_MIN_OVERLAP_PX = 10}). A segment is considered
 * "facing" a hub face only if its parallel-axis extent overlaps the face extent by at
 * least 10px — preventing terminal stubs and short jogs from falsely registering as
 * perimeter hugging.
 *
 * <p><b>Pure geometry.</b> No EMF, no SWT, no PDE. All inputs are in-memory DTOs and
 * RoutingRects; no mutation of inputs. Callable from standard JUnit tests without OSGi.
 *
 * @see HubPerimeterRoutingStage
 * @see AlternativeCorridorSelector
 * @see CorridorSpreadEnforcer
 */
public class HubFaceConnectionPartitioner {

    /**
     * Hub-detection degree threshold. Mirrors {@code LayoutQualityAssessor.HUB_DETECTION_THRESHOLD}
     * (package-private from this sub-package; see class-level Javadoc for the
     * rationale for local inlining). An element with 5 or more
     * incident connections qualifies as a hub.
     */
    static final int HUB_DETECTION_THRESHOLD = 5;

    /** Perpendicular proximity threshold (px) for cell membership. */
    static final double PROXIMITY_TOLERANCE_PX = 5.0;

    /** Minimum parallel-extent overlap with hub face (px) for cell membership. */
    static final double MIN_PARALLEL_OVERLAP_PX = 10.0;

    /** Axis-aligned classification tolerance (px). Matches {@code LayoutQualityAssessor.ZIGZAG_AXIS_TOLERANCE_PX}. */
    static final double AXIS_TOLERANCE_PX = 1.0;

    /**
     * A per-(hub, face) cell: the set of routed connections with at least one
     * axis-aligned segment proximate to the named face of the named hub.
     *
     * @param hub     the hub element rectangle
     * @param face    one of TOP / BOTTOM / LEFT / RIGHT
     * @param members the connections (with proximate segment indices) participating in this cell
     */
    public record HubFaceCell(RoutingRect hub,
                              EdgeAttachmentCalculator.Face face,
                              List<CellMember> members) {
        public HubFaceCell {
            members = members != null ? List.copyOf(members) : List.of();
        }
    }

    /**
     * A connection's participation in a {@link HubFaceCell}: the connection id, the
     * index of its path in the parent index-parallel paths list, and the bendpoint
     * indices of one proximate segment. A connection can have multiple {@code CellMember}
     * records if multiple of its segments are proximate to the same face.
     *
     * @param connectionId       the connection identifier
     * @param pathIndex          index into the parent {@code List<List<AbsoluteBendpointDto>>}
     * @param segmentStartBpIdx  bendpoint index — start of the proximate segment
     * @param segmentEndBpIdx    bendpoint index — end of the proximate segment
     */
    public record CellMember(String connectionId,
                             int pathIndex,
                             int segmentStartBpIdx,
                             int segmentEndBpIdx) {}

    /**
     * Identify hub elements: those whose incident-connection degree (sum of source +
     * target incidences across the connection set) is at least
     * {@link #HUB_DETECTION_THRESHOLD}.
     *
     * @param connections  per-route endpoint records
     * @param allObstacles every non-container view object — elements, notes and images alike
     *                     (hubs must be in this set
     *                     and have a non-null id matched against connection source/target ids)
     * @return distinct hub rectangles in {@code allObstacles} iteration order
     */
    public List<RoutingRect> detectHubs(
            List<RoutingPipeline.ConnectionEndpoints> connections,
            List<RoutingRect> allObstacles) {
        if (connections == null || connections.isEmpty()
                || allObstacles == null || allObstacles.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> degree = new HashMap<>();
        for (RoutingPipeline.ConnectionEndpoints c : connections) {
            if (c == null) continue;
            if (c.source() != null && c.source().id() != null) {
                degree.merge(c.source().id(), 1, Integer::sum);
            }
            if (c.target() != null && c.target().id() != null) {
                degree.merge(c.target().id(), 1, Integer::sum);
            }
        }
        List<RoutingRect> hubs = new ArrayList<>();
        for (RoutingRect el : allObstacles) {
            if (el == null || el.id() == null) continue;
            if (degree.getOrDefault(el.id(), 0) >= HUB_DETECTION_THRESHOLD) {
                hubs.add(el);
            }
        }
        return hubs;
    }

    /**
     * Partition all axis-aligned segments of all routes into per-(hub, face) cells.
     * Pure read-only over inputs.
     *
     * @param connections  index-parallel per-route endpoint records
     * @param paths        index-parallel per-route bendpoint lists (NOT mutated)
     * @param allObstacles every non-container view object — elements, notes and images alike
     * @return distinct {@code HubFaceCell} records, one per (hub, face) with at least
     *         one member; empty if no hubs are detected
     */
    public List<HubFaceCell> partition(
            List<RoutingPipeline.ConnectionEndpoints> connections,
            List<List<AbsoluteBendpointDto>> paths,
            List<RoutingRect> allObstacles) {
        if (connections == null || paths == null || connections.size() != paths.size()) {
            return List.of();
        }
        List<RoutingRect> hubs = detectHubs(connections, allObstacles);
        if (hubs.isEmpty()) {
            return List.of();
        }

        Map<String, Map<EdgeAttachmentCalculator.Face, List<CellMember>>> cellsByHub =
                new LinkedHashMap<>();
        for (RoutingRect hub : hubs) {
            cellsByHub.put(hub.id(), new EnumMap<>(EdgeAttachmentCalculator.Face.class));
        }

        for (int i = 0; i < connections.size(); i++) {
            RoutingPipeline.ConnectionEndpoints conn = connections.get(i);
            List<AbsoluteBendpointDto> path = paths.get(i);
            if (conn == null || path == null || path.size() < 2) continue;
            int lastIdx = path.size() - 1;

            // TerminalAnchoring exemption: terminal-incident segments (those whose start bp
            // is the source terminal at idx=0, or whose end bp is the target terminal at idx=lastIdx)
            // are NOT eligible cell members. The perpendicular-shift primitives would mutate the
            // terminal bp's coordinate, violating preservesEndpoints. Terminal-segment positioning
            // is owned by EdgeAttachmentCalculator + TerminalAnchoring, not this stage. The loop
            // bound s < lastIdx - 1 ensures the segment's end-bp (idx=s+1) stays ≤ lastIdx - 1,
            // i.e. NOT the terminal.
            for (int s = 1; s < lastIdx - 1; s++) {
                AbsoluteBendpointDto bp1 = path.get(s);
                AbsoluteBendpointDto bp2 = path.get(s + 1);
                int dx = bp2.x() - bp1.x();
                int dy = bp2.y() - bp1.y();
                boolean horizontal = Math.abs(dy) <= AXIS_TOLERANCE_PX
                        && Math.abs(dx) > AXIS_TOLERANCE_PX;
                boolean vertical = Math.abs(dx) <= AXIS_TOLERANCE_PX
                        && Math.abs(dy) > AXIS_TOLERANCE_PX;
                if (!horizontal && !vertical) continue;

                for (RoutingRect hub : hubs) {
                    if (horizontal) {
                        double y = (bp1.y() + bp2.y()) / 2.0;
                        double topEdge = hub.y();
                        double bottomEdge = (double) hub.y() + hub.height();
                        double segLeft = Math.min(bp1.x(), bp2.x());
                        double segRight = Math.max(bp1.x(), bp2.x());
                        double overlap = Math.min(segRight, (double) hub.x() + hub.width())
                                - Math.max(segLeft, hub.x());
                        if (overlap < MIN_PARALLEL_OVERLAP_PX) continue;
                        if (Math.abs(y - topEdge) <= PROXIMITY_TOLERANCE_PX) {
                            addMember(cellsByHub, hub.id(), EdgeAttachmentCalculator.Face.TOP,
                                    conn.connectionId(), i, s);
                        }
                        if (Math.abs(y - bottomEdge) <= PROXIMITY_TOLERANCE_PX) {
                            addMember(cellsByHub, hub.id(), EdgeAttachmentCalculator.Face.BOTTOM,
                                    conn.connectionId(), i, s);
                        }
                    } else {
                        double x = (bp1.x() + bp2.x()) / 2.0;
                        double leftEdge = hub.x();
                        double rightEdge = (double) hub.x() + hub.width();
                        double segTop = Math.min(bp1.y(), bp2.y());
                        double segBottom = Math.max(bp1.y(), bp2.y());
                        double overlap = Math.min(segBottom, (double) hub.y() + hub.height())
                                - Math.max(segTop, hub.y());
                        if (overlap < MIN_PARALLEL_OVERLAP_PX) continue;
                        if (Math.abs(x - leftEdge) <= PROXIMITY_TOLERANCE_PX) {
                            addMember(cellsByHub, hub.id(), EdgeAttachmentCalculator.Face.LEFT,
                                    conn.connectionId(), i, s);
                        }
                        if (Math.abs(x - rightEdge) <= PROXIMITY_TOLERANCE_PX) {
                            addMember(cellsByHub, hub.id(), EdgeAttachmentCalculator.Face.RIGHT,
                                    conn.connectionId(), i, s);
                        }
                    }
                }
            }
        }

        List<HubFaceCell> result = new ArrayList<>();
        for (RoutingRect hub : hubs) {
            Map<EdgeAttachmentCalculator.Face, List<CellMember>> faceMap = cellsByHub.get(hub.id());
            for (Map.Entry<EdgeAttachmentCalculator.Face, List<CellMember>> entry : faceMap.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    result.add(new HubFaceCell(hub, entry.getKey(), entry.getValue()));
                }
            }
        }
        return result;
    }

    private static void addMember(
            Map<String, Map<EdgeAttachmentCalculator.Face, List<CellMember>>> cellsByHub,
            String hubId,
            EdgeAttachmentCalculator.Face face,
            String connectionId,
            int pathIndex,
            int segmentStartBpIdx) {
        cellsByHub.get(hubId)
                .computeIfAbsent(face, f -> new ArrayList<>())
                .add(new CellMember(connectionId, pathIndex, segmentStartBpIdx, segmentStartBpIdx + 1));
    }
}
