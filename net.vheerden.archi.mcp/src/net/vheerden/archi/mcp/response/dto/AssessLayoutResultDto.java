package net.vheerden.archi.mcp.response.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * Result DTO for the assess-layout tool.
 *
 * <p>{@code overlapCount} contains only sibling overlaps (genuine layout problems).
 * {@code containmentOverlaps} tracks expected ancestor-descendant overlaps (informational).
 * {@code orphanedConnections} counts connections with missing source/target view objects.
 * {@code noteOverlapCount} tracks note-element overlaps (informational, not penalizing).
 * {@code hasGroups} indicates whether the view contains group containers.
 * {@code ratingBreakdown} shows per-metric contributions to the overall rating.
 * {@code coincidentSegmentCount} tracks overlapping connection route segments.
 * {@code nonOrthogonalTerminalCount} tracks connections with diagonal terminal segments.
 * {@code contentBounds} is the axis-aligned bounding box of all visual content.
 * {@code labelTruncationCount}, {@code parentLabelObscuredCount}, {@code imageSiblingOverlapCount}
 * are informational detections. {@code parentLabelObscuredCount} promoted to layout Tier 1L;
 * {@code labelTruncationCount} promoted to routing Tier 2R.
 * {@code violatorIds} maps metric names to lists of visual object IDs that violate each metric.
 * Null/omitted when not requested (includeViolatorIds=false). Crossings excluded (emergent property).
 *
 * <p>Routing-quality metrics: {@code interiorTerminationCount},
 * {@code zigzagCount}, {@code connectionEdgeCoincidenceCount}, {@code hubPortQualityScore},
 * {@code hubPortQualityFaces}, {@code layoutRating}, {@code routingRating}. Existing field
 * positions preserved; new fields appended.</p>
 *
 * <p>Corridor Utilisation:
 * {@code corridorUtilisationScore} (occupant-count-weighted mean of per-corridor
 * {@code spread_ratio = span / available}), {@code corridorUtilisationChannels}
 * (per-corridor details when {@code includeViolatorIds=true}). Appended.</p>
 *
 * <p>parallelConnectionGap:
 * {@code vAxisParallelGapP10} (10th-percentile V-axis parallel gap; perception-anchor
 * primary signal, null when no qualifying V segment exists),
 * {@code vAxisParallelGapNarrow25Count} (count of V-axis segments below 25 px gap),
 * {@code parallelConnectionGapDetail} (full per-axis aggregate, lazy — null unless
 * {@code includeViolatorIds=true}). Informational only — does NOT contribute to the
 * rating. Calibration-anchor pin in {@code ParallelConnectionGapMetricTest} locks
 * V4 manual gold V_p10 = 13.30 &plusmn; 0.5.</p>
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
        // Note-text-clip detection (informational; no rating impact)
        int noteClipCount,
        List<String> noteClipDescriptions,
        boolean hasGroups,
        int coincidentSegmentCount,
        int nonOrthogonalTerminalCount,
        ContentBoundsDto contentBounds,
        int labelTruncationCount,
        List<String> labelTruncations,
        int parentLabelObscuredCount,
        List<String> parentLabelObscuredDescriptions,
        int imageSiblingOverlapCount,
        List<String> imageSiblingOverlapDescriptions,
        Map<String, List<String>> violatorIds,
        List<String> suggestions,
        // Routing-quality metrics (appended; backwards-compat)
        int interiorTerminationCount,
        List<String> interiorTerminationDescriptions,
        int zigzagCount,
        List<String> zigzagDescriptions,
        int connectionEdgeCoincidenceCount,
        List<String> edgeCoincidenceDescriptions,
        double hubPortQualityScore,
        List<HubFaceDetailDto> hubPortQualityFaces,
        String layoutRating,
        String routingRating,
        // Corridor utilisation (appended)
        double corridorUtilisationScore,
        List<CorridorUtilisationDetailDto> corridorUtilisationChannels,
        // parallelConnectionGap (appended; backwards-compat)
        Double vAxisParallelGapP10,
        int vAxisParallelGapNarrow25Count,
        ParallelConnectionGapDetailDto parallelConnectionGapDetail,
        // Hub-to-neighbour crowding density signal (appended)
        double hubNeighbourClearanceMin,
        // Coverage declaration (appended). Maps each defect-dimension id to one of
        // {@code checked} / {@code not-checked} / {@code not-applicable}, driven by the
        // assessor's canonical dimension registry. Non-null and populated on the main
        // {@code assess()} path; an EMPTY map means "legacy/degenerate path, coverage not
        // declared" (only reachable via the backward-compat constructors). Informational
        // only — never affects any rating. {@code not-checked} means the dimension was NOT
        // evaluated: absence of a finding is not evidence of absence.
        Map<String, String> coverage,
        // Connection-through-note/image (appended). {@code connectionThroughNoteCount} counts
        // connections whose route penetrates a Note or an Image visual's rectangle (one per
        // connection×visual); {@code connectionThroughNoteDescriptions} names each. Informational
        // only — no rating impact, and distinct from the element-only {@code connectionPassThroughs}.
        int connectionThroughNoteCount,
        List<String> connectionThroughNoteDescriptions,
        // Redundant (collinear / removable) bendpoints (appended). {@code connectionRedundantBendpointCount}
        // counts bendpoints collinear with their neighbours and lying between them — removable without
        // changing the rendered route shape (the "many unnecessary bendpoints" defect);
        // {@code connectionRedundantBendpointDescriptions} names each. Informational only — no rating
        // impact, and distinct from the reversal-based {@code zigzagCount}.
        int connectionRedundantBendpointCount,
        List<String> connectionRedundantBendpointDescriptions,
        // Non-orthogonal interior (mid) segments (appended). {@code nonOrthogonalInteriorSegmentCount}
        // counts connections with at least one off-cardinal segment strictly between the two terminal
        // segments; {@code nonOrthogonalInteriorSegmentDescriptions} names each. Informational only —
        // no rating impact, and distinct from the rating-affecting {@code nonOrthogonalTerminalCount}
        // (which examines only the terminal segments).
        int nonOrthogonalInteriorSegmentCount,
        List<String> nonOrthogonalInteriorSegmentDescriptions,
        // Container fill == nested-child fill (appended). {@code containerFillEqualsChildCount}
        // counts containers whose AUTHORED fill equals a nested child's fill — the residual flat
        // "blob" the container-recession emitter is forbidden to touch (it recedes only unauthored,
        // null-fill parents at add time); {@code containerFillEqualsChildDescriptions} names each.
        // Informational only — no rating impact.
        int containerFillEqualsChildCount,
        List<String> containerFillEqualsChildDescriptions,
        // Connection grazing a note/image BORDER (appended). {@code connectionGrazesVisualCount}
        // counts (connection, Note-or-Image) border-grazes — a route touching a visual's outer band
        // (the ring the through-visual 10px inset discards), including visuals too small to inset;
        // {@code connectionGrazesVisualDescriptions} names each. Informational only — no rating
        // impact, and DISJOINT from {@code connectionThroughNoteCount} (interior penetration).
        int connectionGrazesVisualCount,
        List<String> connectionGrazesVisualDescriptions,
        // Connection labels rendered on a Note rectangle (appended). {@code labelOnNoteCount} counts
        // (connection-label, Note) overlaps; {@code labelOnNoteDescriptions} names each. Informational
        // only — no rating impact, and independent of {@code connectionThroughNoteCount} /
        // {@code connectionGrazesVisualCount} (a label is positioned off the line).
        int labelOnNoteCount,
        List<String> labelOnNoteDescriptions,
        // Connection labels rendered on a visual Group's TITLE BAND (appended). {@code labelOnGroupCount}
        // counts (connection-label, group-title-band) overlaps; {@code labelOnGroupDescriptions} names
        // each. Informational only — no rating impact. Tests the group's top title strip only, so a
        // label inside the group body is not flagged (the label-vs-element detector skips groups
        // wholesale, hiding this title collision).
        int labelOnGroupCount,
        List<String> labelOnGroupDescriptions,
        // Per-element edge-coincidence enumeration (appended). The rating-bearing
        // {@code connectionEdgeCoincidenceCount} counts CONNECTIONS with >=1 edge-coincident
        // segment (stops at the first graze); {@code edgeCoincidenceGrazedElementCount} counts
        // every distinct (connection, element) graze across the view (a trunk grazing three
        // element edges contributes 3 here, 1 to the connection count). Informational only — no
        // rating impact; grazed element ids are surfaced under the
        // {@code edgeCoincidenceGrazedElements} violator key.
        int edgeCoincidenceGrazedElementCount,
        // Terminal routes that depart an element face then run parallel to and hug that face (first
        // exterior segment travels along the departed face within the stub minimum), and their
        // descriptions. Counted per connection. Informational only — no rating impact; distinct from
        // the rating-bearing nonOrthogonalTerminalCount (raw terminal-segment angle), unchanged.
        int offFaceParallelTerminalCount,
        List<String> offFaceParallelTerminalDescriptions,
        // Coincident same-face ports (appended). Count of element faces on which two or more
        // connection terminals overlap on one perimeter point (slots within the hub-port tolerance),
        // and their descriptions. Informational only — no rating impact; distinct from the
        // rating-bearing hubPortQualityScore, which is unchanged. Surfaces the same-face collision M5
        // misses on any face below its four-connection guard; colliding connection ids ride the
        // {@code coincidentFacePorts} violator key.
        int coincidentFacePortCount,
        List<String> coincidentFacePortDescriptions) {

    /**
     * Sentinel for {@code hubNeighbourClearanceMin} when no detected hub has a measurable
     * spoke row. Negative reads as "not crowded / not measured", so the next-step emitter
     * keeps its hub-existence-safe diagnostic instead of branching sparse vs dense. Mirrors
     * {@code LayoutQualityAssessor.NO_HUB_NEIGHBOUR_CLEARANCE}.
     */
    public static final double NO_HUB_NEIGHBOUR_CLEARANCE = -1.0;

    /**
     * Backwards-compatible 33-arg constructor (preserved through the routing-quality,
     * corridor-utilisation, and parallelConnectionGap appendings). Pre-redesign callers
     * (test fixtures, legacy DTO builders) construct the DTO without those fields. This
     * delegating constructor populates the appended fields with neutral defaults (zero
     * counts, null description lists, hub-port quality 1.0, layout/routing ratings
     * mirror the overall rating, corridor-utilisation score 1.0, parallelConnectionGap null/0/null) so
     * existing call sites compile unchanged. Production code (the {@code assess-layout}
     * handler) uses the canonical widest form, which carries the real registry-driven
     * coverage map, to forward real values.
     */
    public AssessLayoutResultDto(
            String viewId, int elementCount, int connectionCount,
            int overlapCount, int containmentOverlaps, int edgeCrossingCount,
            double crossingsPerConnection, double averageSpacing, int alignmentScore,
            String overallRating, Map<String, String> ratingBreakdown,
            List<String> overlaps, List<String> boundaryViolations,
            List<String> connectionPassThroughs, List<String> offCanvasWarnings,
            int labelOverlapCount, List<String> labelOverlaps,
            int orphanedConnections, List<String> orphanedConnectionDescriptions,
            int noteOverlapCount, List<String> noteOverlapDescriptions,
            boolean hasGroups, int coincidentSegmentCount, int nonOrthogonalTerminalCount,
            ContentBoundsDto contentBounds,
            int labelTruncationCount, List<String> labelTruncations,
            int parentLabelObscuredCount, List<String> parentLabelObscuredDescriptions,
            int imageSiblingOverlapCount, List<String> imageSiblingOverlapDescriptions,
            Map<String, List<String>> violatorIds, List<String> suggestions) {
        this(viewId, elementCount, connectionCount, overlapCount, containmentOverlaps,
                edgeCrossingCount, crossingsPerConnection, averageSpacing, alignmentScore,
                overallRating, ratingBreakdown, overlaps, boundaryViolations,
                connectionPassThroughs, offCanvasWarnings, labelOverlapCount, labelOverlaps,
                orphanedConnections, orphanedConnectionDescriptions, noteOverlapCount,
                noteOverlapDescriptions,
                // note-text-clip defaults (informational)
                0, null,
                hasGroups, coincidentSegmentCount,
                nonOrthogonalTerminalCount, contentBounds,
                labelTruncationCount, labelTruncations, parentLabelObscuredCount,
                parentLabelObscuredDescriptions, imageSiblingOverlapCount,
                imageSiblingOverlapDescriptions, violatorIds, suggestions,
                // Routing-quality defaults + corridor-utilisation default + parallelConnectionGap defaults
                0, null, 0, null, 0, null, 1.0, null,
                overallRating, overallRating, 1.0, null,
                null, 0, null,
                // hub-to-neighbour crowding default (no hub measured)
                NO_HUB_NEIGHBOUR_CLEARANCE,
                // coverage default — legacy path, coverage not declared
                Map.of(),
                // connection-through-note/image default (not detected on this legacy path)
                0, null,
                // redundant-bendpoint default (not detected on this legacy path)
                0, null,
                // non-orthogonal interior-segment default (not detected on this legacy path)
                0, null,
                // container-fill==child default (not detected on this legacy path)
                0, null,
                // connection-grazes-visual default (not detected on this legacy path)
                0, null,
                // label-on-note default (not detected on this legacy path)
                0, null,
                // label-on-group default (not detected on this legacy path)
                0, null,
                // per-element edge-coincidence enumeration default (not detected on this legacy path)
                0,
                // off-face parallel-terminal default (not detected on this legacy path)
                0, null,
                // coincident-face-port default (not detected on this legacy path)
                0, null);
    }

    /**
     * Backwards-compatible 45-arg constructor.
     *
     * <p>Preserves call sites that built the DTO with the post-corridor-utilisation /
     * pre-parallelConnectionGap shape. The three new parallelConnectionGap fields populate with neutral defaults
     * ({@code null / 0 / null}) so callers compile unchanged. Production code (the
     * {@code assess-layout} handler) uses the canonical widest form, which carries the real
     * registry-driven coverage map, to forward real values.</p>
     */
    public AssessLayoutResultDto(
            String viewId, int elementCount, int connectionCount,
            int overlapCount, int containmentOverlaps, int edgeCrossingCount,
            double crossingsPerConnection, double averageSpacing, int alignmentScore,
            String overallRating, Map<String, String> ratingBreakdown,
            List<String> overlaps, List<String> boundaryViolations,
            List<String> connectionPassThroughs, List<String> offCanvasWarnings,
            int labelOverlapCount, List<String> labelOverlaps,
            int orphanedConnections, List<String> orphanedConnectionDescriptions,
            int noteOverlapCount, List<String> noteOverlapDescriptions,
            boolean hasGroups, int coincidentSegmentCount, int nonOrthogonalTerminalCount,
            ContentBoundsDto contentBounds,
            int labelTruncationCount, List<String> labelTruncations,
            int parentLabelObscuredCount, List<String> parentLabelObscuredDescriptions,
            int imageSiblingOverlapCount, List<String> imageSiblingOverlapDescriptions,
            Map<String, List<String>> violatorIds, List<String> suggestions,
            int interiorTerminationCount, List<String> interiorTerminationDescriptions,
            int zigzagCount, List<String> zigzagDescriptions,
            int connectionEdgeCoincidenceCount, List<String> edgeCoincidenceDescriptions,
            double hubPortQualityScore, List<HubFaceDetailDto> hubPortQualityFaces,
            String layoutRating, String routingRating,
            double corridorUtilisationScore,
            List<CorridorUtilisationDetailDto> corridorUtilisationChannels) {
        this(viewId, elementCount, connectionCount, overlapCount, containmentOverlaps,
                edgeCrossingCount, crossingsPerConnection, averageSpacing, alignmentScore,
                overallRating, ratingBreakdown, overlaps, boundaryViolations,
                connectionPassThroughs, offCanvasWarnings, labelOverlapCount, labelOverlaps,
                orphanedConnections, orphanedConnectionDescriptions, noteOverlapCount,
                noteOverlapDescriptions,
                // note-text-clip defaults (informational)
                0, null,
                hasGroups, coincidentSegmentCount,
                nonOrthogonalTerminalCount, contentBounds,
                labelTruncationCount, labelTruncations, parentLabelObscuredCount,
                parentLabelObscuredDescriptions, imageSiblingOverlapCount,
                imageSiblingOverlapDescriptions, violatorIds, suggestions,
                interiorTerminationCount, interiorTerminationDescriptions,
                zigzagCount, zigzagDescriptions,
                connectionEdgeCoincidenceCount, edgeCoincidenceDescriptions,
                hubPortQualityScore, hubPortQualityFaces,
                layoutRating, routingRating,
                corridorUtilisationScore, corridorUtilisationChannels,
                // parallelConnectionGap defaults
                null, 0, null,
                // hub-to-neighbour crowding default (no hub measured)
                NO_HUB_NEIGHBOUR_CLEARANCE,
                // coverage default — legacy path, coverage not declared
                Map.of(),
                // connection-through-note/image default (not detected on this legacy path)
                0, null,
                // redundant-bendpoint default (not detected on this legacy path)
                0, null,
                // non-orthogonal interior-segment default (not detected on this legacy path)
                0, null,
                // container-fill==child default (not detected on this legacy path)
                0, null,
                // connection-grazes-visual default (not detected on this legacy path)
                0, null,
                // label-on-note default (not detected on this legacy path)
                0, null,
                // label-on-group default (not detected on this legacy path)
                0, null,
                // per-element edge-coincidence enumeration default (not detected on this legacy path)
                0,
                // off-face parallel-terminal default (not detected on this legacy path)
                0, null,
                // coincident-face-port default (not detected on this legacy path)
                0, null);
    }

    /**
     * Backwards-compatible 50-arg constructor (the canonical shape before the coverage
     * declaration was appended).
     *
     * <p>Preserves call sites — chiefly test fixtures — that build the DTO with the
     * post-hub-neighbour-crowding / pre-coverage shape. The {@code coverage} map populates
     * with an empty map (NOT null), which by contract means "legacy path, coverage not
     * declared". Production code (the {@code assess-layout} handler's main path) uses the
     * canonical widest form to forward the real, registry-driven coverage map.</p>
     */
    public AssessLayoutResultDto(
            String viewId, int elementCount, int connectionCount,
            int overlapCount, int containmentOverlaps, int edgeCrossingCount,
            double crossingsPerConnection, double averageSpacing, int alignmentScore,
            String overallRating, Map<String, String> ratingBreakdown,
            List<String> overlaps, List<String> boundaryViolations,
            List<String> connectionPassThroughs, List<String> offCanvasWarnings,
            int labelOverlapCount, List<String> labelOverlaps,
            int orphanedConnections, List<String> orphanedConnectionDescriptions,
            int noteOverlapCount, List<String> noteOverlapDescriptions,
            int noteClipCount, List<String> noteClipDescriptions,
            boolean hasGroups, int coincidentSegmentCount, int nonOrthogonalTerminalCount,
            ContentBoundsDto contentBounds,
            int labelTruncationCount, List<String> labelTruncations,
            int parentLabelObscuredCount, List<String> parentLabelObscuredDescriptions,
            int imageSiblingOverlapCount, List<String> imageSiblingOverlapDescriptions,
            Map<String, List<String>> violatorIds, List<String> suggestions,
            int interiorTerminationCount, List<String> interiorTerminationDescriptions,
            int zigzagCount, List<String> zigzagDescriptions,
            int connectionEdgeCoincidenceCount, List<String> edgeCoincidenceDescriptions,
            double hubPortQualityScore, List<HubFaceDetailDto> hubPortQualityFaces,
            String layoutRating, String routingRating,
            double corridorUtilisationScore,
            List<CorridorUtilisationDetailDto> corridorUtilisationChannels,
            Double vAxisParallelGapP10, int vAxisParallelGapNarrow25Count,
            ParallelConnectionGapDetailDto parallelConnectionGapDetail,
            double hubNeighbourClearanceMin) {
        this(viewId, elementCount, connectionCount, overlapCount, containmentOverlaps,
                edgeCrossingCount, crossingsPerConnection, averageSpacing, alignmentScore,
                overallRating, ratingBreakdown, overlaps, boundaryViolations,
                connectionPassThroughs, offCanvasWarnings, labelOverlapCount, labelOverlaps,
                orphanedConnections, orphanedConnectionDescriptions, noteOverlapCount,
                noteOverlapDescriptions, noteClipCount, noteClipDescriptions,
                hasGroups, coincidentSegmentCount, nonOrthogonalTerminalCount, contentBounds,
                labelTruncationCount, labelTruncations, parentLabelObscuredCount,
                parentLabelObscuredDescriptions, imageSiblingOverlapCount,
                imageSiblingOverlapDescriptions, violatorIds, suggestions,
                interiorTerminationCount, interiorTerminationDescriptions,
                zigzagCount, zigzagDescriptions,
                connectionEdgeCoincidenceCount, edgeCoincidenceDescriptions,
                hubPortQualityScore, hubPortQualityFaces,
                layoutRating, routingRating,
                corridorUtilisationScore, corridorUtilisationChannels,
                vAxisParallelGapP10, vAxisParallelGapNarrow25Count,
                parallelConnectionGapDetail, hubNeighbourClearanceMin,
                // coverage default — legacy path, coverage not declared
                Map.of(),
                // connection-through-note/image default (not detected on this legacy path)
                0, null,
                // redundant-bendpoint default (not detected on this legacy path)
                0, null,
                // non-orthogonal interior-segment default (not detected on this legacy path)
                0, null,
                // container-fill==child default (not detected on this legacy path)
                0, null,
                // connection-grazes-visual default (not detected on this legacy path)
                0, null,
                // label-on-note default (not detected on this legacy path)
                0, null,
                // label-on-group default (not detected on this legacy path)
                0, null,
                // per-element edge-coincidence enumeration default (not detected on this legacy path)
                0,
                // off-face parallel-terminal default (not detected on this legacy path)
                0, null,
                // coincident-face-port default (not detected on this legacy path)
                0, null);
    }

    /**
     * Axis-aligned bounding box of all visual content on a view.
     * Uses absolute canvas coordinates.
     */
    public record ContentBoundsDto(double x, double y, double width, double height) {}

    /**
     * Per-face hub-port allocation detail.
     * {@code face} is one of {@code LEFT}, {@code RIGHT}, {@code TOP}, {@code BOTTOM}.
     */
    public record HubFaceDetailDto(String elementId, String face, int connectionsOnFace,
                                   int distinctSlots, double quality) {}

    /**
     * Per-corridor utilisation detail. {@code axis}: 0 = vertical, 1 = horizontal.
     * {@code sharedCoord}: occupant midpoint {@code (min + max) / 2.0} of per-occupant
     * shared-coords (NOT the corridor's geometric centre). {@code wallLow/HighId}:
     * AssessmentNode IDs of the bracketing walls in the perpendicular axis.
     * {@code spreadRatio} is clamped to [0.0, 1.0]; pre-clamp values &gt; 1.0 indicate
     * wall-hugging occupants (already flagged by M4 edge-coincidence).
     */
    public record CorridorUtilisationDetailDto(int axis, double sharedCoord, String wallLowId,
                                                String wallHighId, int occupantCount,
                                                double span, double available, double spreadRatio) {}

    /**
     * Per-axis aggregate of parallelConnectionGap (mirror of
     * {@link net.vheerden.archi.mcp.model.LayoutAssessmentResult.ParallelConnectionGapAxisDetail}).
     * Violator IDs are surfaced via the top-level {@code violatorIds} map under
     * {@code parallelConnectionGapV} / {@code parallelConnectionGapH}.
     */
    public record ParallelConnectionGapAxisDetailDto(int qualifyingSegmentCount, Double mean,
                                                      Double min, Double p10,
                                                      int narrowGapCount15, int narrowGapCount25,
                                                      int narrowGapCount40) {}

    /**
     * Full per-axis parallelConnectionGap detail. Present only when
     * {@code includeViolatorIds=true}; null otherwise — {@code @JsonInclude(NON_NULL)}
     * on the enclosing class omits the field from JSON output in that case.
     */
    public record ParallelConnectionGapDetailDto(ParallelConnectionGapAxisDetailDto vAxis,
                                                  ParallelConnectionGapAxisDetailDto hAxis) {}
}
