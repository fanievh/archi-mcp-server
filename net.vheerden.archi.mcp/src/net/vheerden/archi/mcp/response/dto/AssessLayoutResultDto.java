package net.vheerden.archi.mcp.response.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * Result DTO for the assess-layout tool (Story 9-2, 9-0d, 10-14, 11-15, 11-17).
 *
 * <p>{@code overlapCount} contains only sibling overlaps (genuine layout problems).
 * {@code containmentOverlaps} tracks expected ancestor-descendant overlaps (informational).
 * {@code orphanedConnections} counts connections with missing source/target view objects (Story 10-14).
 * {@code noteOverlapCount} tracks note-element overlaps (informational, not penalizing — Story 11-15).
 * {@code hasGroups} indicates whether the view contains group containers (Story 11-17).
 * {@code ratingBreakdown} shows per-metric contributions to the overall rating (Story 11-19).
 * {@code coincidentSegmentCount} tracks overlapping connection route segments (Story 11-23).
 * {@code nonOrthogonalTerminalCount} tracks connections with diagonal terminal segments (B38).
 * {@code contentBounds} is the axis-aligned bounding box of all visual content (Story 11-29).</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssessLayoutResultDto(
        String viewId,
        int elementCount,
        int connectionCount,
        int overlapCount,
        int containmentOverlaps,
        int edgeCrossingCount,
        double crossingsPerConnection,
        double averageSpacing,
        int alignmentScore,
        String overallRating,
        Map<String, String> ratingBreakdown,
        List<String> overlaps,
        List<String> boundaryViolations,
        List<String> connectionPassThroughs,
        List<String> offCanvasWarnings,
        int labelOverlapCount,
        List<String> labelOverlaps,
        int orphanedConnections,
        List<String> orphanedConnectionDescriptions,
        int noteOverlapCount,
        List<String> noteOverlapDescriptions,
        boolean hasGroups,
        int coincidentSegmentCount,
        int nonOrthogonalTerminalCount,
        ContentBoundsDto contentBounds,
        List<String> suggestions) {

    /**
     * Axis-aligned bounding box of all visual content on a view (Story 11-29).
     * Uses absolute canvas coordinates.
     */
    public record ContentBoundsDto(double x, double y, double width, double height) {}
}
