package net.vheerden.archi.mcp.model;

import java.util.List;

import net.vheerden.archi.mcp.model.routing.CoincidentSegmentDetector;

/**
 * A connection's visual path for layout quality assessment.
 * pathPoints is the ordered list of (x,y) coordinates forming the path:
 * source center, then any bendpoints, then target center.
 * Implements CoincidentAssessable for coincident segment detection.
 *
 * <p>{@code relativePosition} is the connection's "Label Offset" compass anchor (newer-platform feature;
 * {@link RelativePositionFeature#CENTER} when un-offset or unsupported). Every label detector reads it through
 * {@code LayoutQualityAssessor.drawnLabelBounds}, which moves a Middle label's box the way Archi draws the
 * offset; a Source or Target label ignores it, as Archi does. The rest of the geometry is derived from
 * {@code textPosition}.</p>
 *
 * <p>{@code anchorDriftX} / {@code anchorDriftY} carry the largest disagreement, in px on each axis,
 * between a bendpoint's two stored reconstructions. Archi stores every bendpoint twice — once
 * relative to the source centre and once relative to the target centre — and both describe the same
 * absolute point at the moment the route was written. {@code pathPoints} above blends the two, so the
 * disagreement is gone by the time any detector sees the polyline; these two components are the only
 * way it survives the collector. Zero when the anchors agree, which is the ordinary case.</p>
 *
 * <p>{@code drawnBendpoints} carries the bendpoints where Archi's renderer draws them
 * ({@link net.vheerden.archi.mcp.model.geometry.GeometryUtils#drawnBendpointCoordinate}: whole-pixel
 * reference points, a float weight, floored), which can sit up to a pixel from the analysis blend in
 * {@code pathPoints} on an odd-sized element. Only a decision that must match the rendered pixels
 * reads it — the off-face hug, which the router decides over the integer points it writes. Null when
 * the path was not reconstructed from stored offsets (a routing candidate): its bendpoints are
 * already the integer points that will be drawn, so {@link #drawnPathPoints()} falls back to
 * {@code pathPoints}.</p>
 */
record AssessmentConnection(String id, String sourceNodeId, String targetNodeId,
                            List<double[]> pathPoints, String labelText, int textPosition,
                            int relativePosition, double anchorDriftX, double anchorDriftY,
                            List<double[]> drawnBendpoints)
        implements CoincidentSegmentDetector.CoincidentAssessable {

    /**
     * Backward-compatible constructor with no separate drawn bendpoints — for a path whose
     * bendpoints are already the drawn integer points, or a fixture that does not distinguish them.
     */
    AssessmentConnection(String id, String sourceNodeId, String targetNodeId,
                         List<double[]> pathPoints, String labelText, int textPosition,
                         int relativePosition, double anchorDriftX, double anchorDriftY) {
        this(id, sourceNodeId, targetNodeId, pathPoints, labelText, textPosition,
                relativePosition, anchorDriftX, anchorDriftY, null);
    }

    /**
     * The path as drawn: {@code pathPoints}' two centres around {@link #drawnBendpoints()}, or
     * {@code pathPoints} itself when there are no separate drawn bendpoints (or their count does not
     * match, which only a malformed fixture could produce).
     */
    List<double[]> drawnPathPoints() {
        if (drawnBendpoints == null || pathPoints.size() != drawnBendpoints.size() + 2) {
            return pathPoints;
        }
        List<double[]> drawn = new java.util.ArrayList<>(pathPoints.size());
        drawn.add(pathPoints.get(0));
        drawn.addAll(drawnBendpoints);
        drawn.add(pathPoints.get(pathPoints.size() - 1));
        return drawn;
    }

    /**
     * Backward-compatible constructor defaulting {@code relativePosition} to
     * {@link RelativePositionFeature#CENTER} — for callers whose connections carry no offset (an older
     * platform, or a fixture that has none). A pre-apply routing candidate is not one of them: it is built
     * with the anchor it will ship with ({@code RoutedAssessmentPaths}).
     */
    AssessmentConnection(String id, String sourceNodeId, String targetNodeId,
                         List<double[]> pathPoints, String labelText, int textPosition) {
        this(id, sourceNodeId, targetNodeId, pathPoints, labelText, textPosition,
                RelativePositionFeature.CENTER);
    }

    /**
     * Backward-compatible constructor defaulting the anchor drift to zero — for the callers that
     * build a path directly rather than reconstructing it from stored anchor pairs (the routing
     * passes and every speculative pre-apply candidate). A path with no stored anchors behind it
     * has no disagreement to carry, so zero is the truthful value rather than a placeholder.
     */
    AssessmentConnection(String id, String sourceNodeId, String targetNodeId,
                         List<double[]> pathPoints, String labelText, int textPosition,
                         int relativePosition) {
        this(id, sourceNodeId, targetNodeId, pathPoints, labelText, textPosition,
                relativePosition, 0.0, 0.0);
    }
}
