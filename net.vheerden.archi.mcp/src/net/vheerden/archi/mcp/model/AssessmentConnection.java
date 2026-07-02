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
 */
record AssessmentConnection(String id, String sourceNodeId, String targetNodeId,
                            List<double[]> pathPoints, String labelText, int textPosition,
                            int relativePosition)
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
}
