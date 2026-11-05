package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.archimatetool.model.IArchimateDiagramModel;

import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * The {@code crossingsAfter} figure of {@code auto-route-connections}: the crossings among the routed connections as
 * the view will hold them once the call's commands run, counted the way the next read counts them.
 *
 * <p>The router works in whole pixels and writes each bendpoint relative to the integer centre of each end
 * ({@code x + width / 2} in ints). The next read adds the stored offsets back to the untruncated centre and blends the
 * source- and target-anchored points by the bendpoint's weight along the list
 * ({@link AssessmentCollector#readBackPath}). The two frames differ by up to half a pixel on an odd width or height,
 * and the blend makes that shift differ from one bendpoint to the next. Routes that share a track or a port slot touch
 * at a whole-pixel coordinate, and a crossing is counted only where two segments properly cross, so the shift turns
 * touches into crossings and back. A count taken in the router's frame is therefore not the count the next call
 * publishes as {@code crossingsBefore} for the same untouched view. This class counts in the read's frame.</p>
 */
final class WrittenRouteCrossings {

    private WrittenRouteCrossings() {
        // static-only utility
    }

    /**
     * Counts the crossings among {@code targetIds} as the view will read after the call.
     *
     * @param view        the routed view
     * @param nodes       the view's objects where the call leaves them: after any auto-nudge moves
     * @param targetIds   the connections the call routes (self-connections are never among them)
     * @param routedPaths the absolute bendpoints the call writes, per connection; a target connection absent here
     *                    keeps its stored bendpoints, read against {@code nodes}
     */
    static int count(IArchimateDiagramModel view, List<AssessmentNode> nodes, Set<String> targetIds,
            Map<String, List<AbsoluteBendpointDto>> routedPaths) {
        Map<String, AssessmentNode> nodeById = new LinkedHashMap<>();
        for (AssessmentNode node : nodes) {
            nodeById.put(node.id(), node);
        }
        List<List<double[]>> paths = new ArrayList<>();
        for (AssessmentConnection conn : AssessmentCollector.collectAssessmentConnections(view, nodes)) {
            if (!targetIds.contains(conn.id())) {
                continue;
            }
            List<AbsoluteBendpointDto> written = routedPaths.get(conn.id());
            if (written == null) {
                paths.add(conn.pathPoints());
                continue;
            }
            AssessmentNode source = nodeById.get(conn.sourceNodeId());
            AssessmentNode target = nodeById.get(conn.targetNodeId());
            paths.add(AssessmentCollector.readBackPath(ConnectionResponseBuilder.convertAbsoluteToRelative(written,
                    intCentre(source.x(), source.width()), intCentre(source.y(), source.height()),
                    intCentre(target.x(), target.width()), intCentre(target.y(), target.height())), source, target));
        }
        return LayoutQualityAssessor.countPathCrossings(paths);
    }

    /** The centre the router writes relative to: {@link RoutingRect#centerX()} over the int-cast bounds. */
    private static int intCentre(double origin, double size) {
        return (int) origin + (int) size / 2;
    }
}
