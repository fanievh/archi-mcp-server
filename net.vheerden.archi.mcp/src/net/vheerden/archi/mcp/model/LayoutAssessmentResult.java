package net.vheerden.archi.mcp.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Result of layout quality assessment containing all metrics,
 * issue descriptions, and improvement suggestions (Story 9-2, 9-0d, 10-14, 11-15, 11-17).
 *
 * <p>{@code overlapCount} contains only sibling overlaps (genuine layout problems).
 * {@code containmentOverlapCount} tracks expected ancestor-descendant overlaps separately.
 * {@code orphanedConnectionCount} tracks connections whose source/target view objects
 * are missing from the view hierarchy (Story 10-14).
 * {@code noteOverlapCount} tracks note-element overlaps (informational, not penalizing — Story 11-15).
 * {@code hasGroups} indicates whether the view contains group containers (Story 11-17).
 * {@code ratingBreakdown} shows per-metric contributions to the overall rating (Story 11-19).
 * {@code coincidentSegmentCount} tracks overlapping connection route segments (Story 11-23).
 * {@code nonOrthogonalTerminalCount} tracks connections with diagonal terminal segments (B38).
 * {@code contentBounds} is the axis-aligned bounding box of all visual content (Story 11-29).
 * {@code labelTruncationCount}, {@code parentLabelObscuredCount}, {@code imageSiblingOverlapCount}
 * are informational detections added by B53 — no rating impact.
 * {@code violatorIds} maps metric names to sets of visual object IDs that violate each metric (B55).
 * Null when not requested (includeViolatorIds=false). Crossings excluded (emergent property).</p>
 */
record LayoutAssessmentResult(
        int overlapCount,
        int containmentOverlapCount,
        int edgeCrossingCount,
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
        int orphanedConnectionCount,
        List<String> orphanedConnectionDescriptions,
        int connectionCount,
        double crossingsPerConnection,
        int noteOverlapCount,
        List<String> noteOverlapDescriptions,
        boolean hasGroups,
        int coincidentSegmentCount,
        int nonOrthogonalTerminalCount,
        ContentBounds contentBounds,
        int labelTruncationCount,
        List<String> labelTruncations,
        int parentLabelObscuredCount,
        List<String> parentLabelObscuredDescriptions,
        int imageSiblingOverlapCount,
        List<String> imageSiblingOverlapDescriptions,
        Map<String, Set<String>> violatorIds,
        List<String> suggestions) {}
