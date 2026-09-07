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
 * {@link RelativePositionFeature#CENTER} when un-offset or unsupported). It lets the own-endpoint overlap
 * check account for a label that has already been lifted off its box, so a successfully-offset label is not
 * re-reported as bleeding. The bounds geometry is otherwise derived from {@code textPosition}.</p>
 *
 * <p>{@code anchorDriftX} / {@code anchorDriftY} carry the largest disagreement, in px on each axis,
 * between a bendpoint's two stored reconstructions. Archi stores every bendpoint twice — once
 * relative to the source centre and once relative to the target centre — and both describe the same
 * absolute point at the moment the route was written. {@code pathPoints} above blends the two, so the
 * disagreement is gone by the time any detector sees the polyline; these two components are the only
 * way it survives the collector. Zero when the anchors agree, which is the ordinary case.</p>
 */
record AssessmentConnection(String id, String sourceNodeId, String targetNodeId,
                            List<double[]> pathPoints, String labelText, int textPosition,
                            int relativePosition, double anchorDriftX, double anchorDriftY)
        implements CoincidentSegmentDetector.CoincidentAssessable {

    /**
     * Backward-compatible constructor defaulting {@code relativePosition} to
     * {@link RelativePositionFeature#CENTER} — for callers that do not know the applied offset (an older
     * platform, or a speculative pre-apply candidate where no offset has been written yet).
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
