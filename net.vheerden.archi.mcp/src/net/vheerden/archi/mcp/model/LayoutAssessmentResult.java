package net.vheerden.archi.mcp.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Result of layout quality assessment containing all metrics,
 * issue descriptions, and improvement suggestions.
 *
 * <p>{@code overlapCount} contains only sibling overlaps (genuine layout problems).
 * {@code containmentOverlapCount} tracks expected ancestor-descendant overlaps separately.
 * {@code orphanedConnectionCount} tracks connections whose source/target view objects
 * are missing from the view hierarchy.
 * {@code noteOverlapCount} tracks note-element overlaps (informational, not penalizing).
 * {@code hasGroups} indicates whether the view contains group containers.
 * {@code ratingBreakdown} shows per-metric contributions to the overall rating.
 * {@code coincidentSegmentCount} tracks overlapping connection route segments.
 * {@code nonOrthogonalTerminalCount} tracks connections with diagonal terminal segments
 * (corrected definition: post-clip visible segment, ignores Archi-clipped diagonals when
 * the bendpoint lies on or inside the source/target element).
 * {@code contentBounds} is the axis-aligned bounding box of all visual content.
 * {@code labelTruncationCount}, {@code parentLabelObscuredCount}, {@code imageSiblingOverlapCount}
 * are informational detections. {@code parentLabelObscuredCount} is
 * promoted to layout Tier 1L; {@code labelTruncationCount} is promoted to routing Tier 2R.
 * {@code violatorIds} maps metric names to sets of visual object IDs that violate each metric.
 * Null when not requested (includeViolatorIds=false). Crossings excluded (emergent property).
 *
 * <p>Assessor.Redesign (M2-M6, 2026-04-26):
 * <ul>
 *   <li>{@code interiorTerminationCount} — connections whose first/last bendpoint is strictly
 *       inside the source/target element bounds (M2). Routing Tier 1R.</li>
 *   <li>{@code zigzagCount} — connections containing at least one three-bendpoint reversal
 *       at a shared axis (M3). Routing Tier 1R.</li>
 *   <li>{@code connectionEdgeCoincidenceCount} — connection segments hugging a foreign element's
 *       edge within {@code EDGE_COINCIDENCE_TOLERANCE}px (M4). Routing Tier 2R.</li>
 *   <li>{@code hubPortQualityScore} — view-aggregate = the WORST (min) per-hub-face distinct-slot
 *       ratio across any element face with ≥4 connections (M5). 1.0 when no hub face exists. min
 *       (not mean) so one degraded face on an otherwise-healthy hub is surfaced honestly rather than
 *       averaged away (2026-06-14). Quality below 0.5
 *       contributes to Tier 2R.</li>
 *   <li>{@code hubPortQualityFaces} — per-face details when {@code includeViolatorIds=true},
 *       else empty.</li>
 *   <li>{@code layoutRating} / {@code routingRating} — two-dimensional decomposition.
 *       {@code overallRating == levelToRating(max(layoutLevel, routingLevel))} (M6, worse
 *       dimension dominates).</li>
 *   <li>{@code corridorUtilisationScore} — occupant-count-weighted mean of per-corridor
 *       {@code spread_ratio = span / available} for multi-occupant corridor channels (R8).
 *       1.0 when no multi-occupant channel exists. Higher is better.</li>
 *   <li>{@code corridorUtilisationChannels} — per-channel R8 details when
 *       {@code includeViolatorIds=true}, else empty.</li>
 *   <li>{@code vAxisParallelGapP10} / {@code vAxisParallelGapNarrow25Count} /
 *       {@code parallelConnectionGapDetail} — Successor D parallelConnectionGap family
 *       (V primary, H secondary; perception-anchor against V4 manual gold V_p10 = 13.30
 *       at PARALLEL_GAP_AXIS_TOLERANCE_PX = 2). Informational only (no rating impact in
 *       v1.4 — sibling work tied to routing-pipeline narrow-corridor floor closure).
 *       {@code parallelConnectionGapDetail} is null when {@code includeViolatorIds=false}.</li>
 * </ul>
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
        // Note-text-clip detection (informational; no rating impact)
        int noteClipCount,
        List<String> noteClipDescriptions,
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
        List<String> suggestions,
        // Assessor.Redesign M2-M6 (appended; backwards-compat)
        int interiorTerminationCount,
        List<String> interiorTerminationDescriptions,
        int zigzagCount,
        List<String> zigzagDescriptions,
        int connectionEdgeCoincidenceCount,
        List<String> edgeCoincidenceDescriptions,
        double hubPortQualityScore,
        List<HubFaceDetail> hubPortQualityFaces,
        String layoutRating,
        String routingRating,
        // R8 (appended)
        double corridorUtilisationScore,
        List<CorridorUtilisationDetail> corridorUtilisationChannels,
        // Successor D parallelConnectionGap (appended; backwards-compat)
        Double vAxisParallelGapP10,
        int vAxisParallelGapNarrow25Count,
        ParallelConnectionGapDetail parallelConnectionGapDetail,
        // Hub-to-neighbour crowding (appended; 2026-06-25). Min px clearance from a detected
        // hub's edge to its nearest spoke row; -1.0 sentinel when no hub face qualifies.
        double hubNeighbourClearanceMin,
        // Coverage declaration (appended). Per-dimension {checked|not-checked|not-applicable}
        // driven by the assessor's canonical dimension registry, so that a never-computed
        // dimension is distinguishable from one that ran and found nothing (absence != zero).
        // Informational only — no rating impact.
        Map<String, String> coverage,
        // Connection-through-note/image (appended). Count of (connection, Note-or-Image)
        // penetrations and their descriptions. Informational only — no rating impact; the
        // element-only connectionPassThroughs count and its tier contribution are unchanged.
        int connectionThroughNoteCount,
        List<String> connectionThroughNoteDescriptions,
        // Redundant (collinear / removable) bendpoints (appended). Count of bendpoints that are
        // collinear with their neighbours and lie between them — removable without changing the
        // route shape — and their descriptions. Informational only — no rating impact; distinct
        // from the reversal-based zigzagCount and the cross-connection coincidentSegmentCount.
        int connectionRedundantBendpointCount,
        List<String> connectionRedundantBendpointDescriptions,
        // Non-orthogonal interior (mid) segments (appended). Count of connections with at least
        // one off-cardinal segment strictly between the two terminal segments, and their
        // descriptions. Informational only — no rating impact; distinct from the rating-affecting
        // nonOrthogonalTerminalCount (terminals) and from interiorTerminationCount (a bendpoint
        // inside an element rect).
        int nonOrthogonalInteriorSegmentCount,
        List<String> nonOrthogonalInteriorSegmentDescriptions,
        // Containers whose AUTHORED fill equals a nested child's fill (the flat-blob backstop for
        // the container-recession emitter), and their descriptions. Informational only — no rating
        // impact; the emitter prevents the unauthored-fill blob, this surfaces the authored residue.
        int containerFillEqualsChildCount,
        List<String> containerFillEqualsChildDescriptions,
        // Connection grazing a note/image BORDER (appended). Count of (connection, Note-or-Image)
        // border-grazes — a route touching a visual's outer band (the ring the through-visual 10px
        // inset discards), including visuals too small to inset — and their descriptions.
        // Informational only — no rating impact; DISJOINT from connectionThroughNoteCount (interior
        // penetration): a single crossing is classified as exactly one of through or graze.
        int connectionGrazesVisualCount,
        List<String> connectionGrazesVisualDescriptions,
        // Connection labels rendered on a Note rectangle (appended). Count of (connection-label,
        // Note) overlaps and their descriptions. Informational only — no rating impact. Independent
        // of connectionThroughNoteCount / connectionGrazesVisualCount: a label is positioned off the
        // line, so it can sit on a note while the route runs clear (and vice-versa).
        int labelOnNoteCount,
        List<String> labelOnNoteDescriptions,
        // Connection labels rendered on a visual Group's TITLE BAND (appended). Count of
        // (connection-label, group-title-band) overlaps and their descriptions. The label-vs-element
        // overlap detector skips groups wholesale (transparent containers a label may legitimately sit
        // within), which also hides the one defect: a label colliding with the group's own title text.
        // This tests only the top title strip, so a label deep in the group body does NOT flag.
        // Informational only — no rating impact.
        int labelOnGroupCount,
        List<String> labelOnGroupDescriptions,
        // Per-element edge-coincidence enumeration (appended). Counts every distinct
        // (connection, element) graze across the view; the rating-bearing
        // connectionEdgeCoincidenceCount above counts CONNECTIONS-with-a-graze (stops at the first).
        // A single trunk grazing three element edges contributes 3 here, 1 there.
        // Informational only — no rating impact.
        int edgeCoincidenceGrazedElementCount,
        // Terminal routes that depart an element face then run parallel to and hug that face (the
        // first exterior segment travels along the departed face within OFF_FACE_MIN_STUB_PX of it),
        // and their descriptions. Counted per connection (a route hugging at either terminal counts
        // once). Informational only — no rating impact; distinct from the rating-bearing
        // nonOrthogonalTerminalCount (raw terminal-segment angle), which is unchanged.
        int offFaceParallelTerminalCount,
        List<String> offFaceParallelTerminalDescriptions,
        // Coincident same-face ports (appended). Count of element faces on which two or more
        // connection terminals land within HUB_PORT_SLOT_TOLERANCE_PX of each other along the face
        // axis (a visible port collision), and their descriptions. Informational only — no rating
        // impact; the rating-bearing hubPortQualityScore (M5) is unchanged. Enumerates the same-face
        // collision M5 misses on any face below its four-connection guard (a 2–3-connection coincident
        // face reads a vacuous hubPortQualityScore of 1.0).
        int coincidentFacePortCount,
        List<String> coincidentFacePortDescriptions) {

    /**
     * Per-face hub-port allocation detail (M5).
     * {@code face} ∈ {LEFT, RIGHT, TOP, BOTTOM}. {@code quality = distinctSlots / connectionsOnFace}.
     */
    record HubFaceDetail(String elementId, String face, int connectionsOnFace,
                         int distinctSlots, double quality) {}

    /**
     * Per-corridor R8 utilisation detail. {@code axis}: 0 = vertical, 1 = horizontal.
     * {@code sharedCoord}: occupant midpoint {@code (min + max) / 2.0} of the per-occupant
     * shared-coords (NOT the corridor's geometric centre). {@code wallLow/HighId}:
     * AssessmentNode IDs of the bracketing walls in the perpendicular axis.
     * {@code spreadRatio = min(span / available, 1.0)}, clamped to [0.0, 1.0]; values
     * pre-clamp > 1.0 indicate wall-hugging occupants (already flagged by M4).
     */
    record CorridorUtilisationDetail(int axis, double sharedCoord, String wallLowId,
                                      String wallHighId, int occupantCount,
                                      double span, double available, double spreadRatio) {}

    /**
     * Per-axis aggregate of nearest-parallel-overlapping-neighbour gaps (Successor D).
     * {@code mean / min / p10} are boxed because they are {@code null} when
     * {@code qualifyingSegmentCount == 0}. {@code violatorIds} are surfaced in the
     * result's top-level {@code violatorIds} map (under keys
     * {@code parallelConnectionGapV} / {@code parallelConnectionGapH}) — not duplicated
     * inside this detail record.
     */
    record ParallelConnectionGapAxisDetail(int qualifyingSegmentCount, Double mean, Double min,
                                            Double p10, int narrowGapCount15, int narrowGapCount25,
                                            int narrowGapCount40) {}

    /**
     * Full per-axis Successor D parallelConnectionGap detail. Lazy-populated: only
     * present in the result when {@code includeViolatorIds=true}; null otherwise (matches
     * {@code hubPortQualityFaces} lazy pattern). The top-level convenience fields
     * {@code vAxisParallelGapP10} and {@code vAxisParallelGapNarrow25Count} carry the
     * primary perception-anchor signals on every call.
     */
    record ParallelConnectionGapDetail(ParallelConnectionGapAxisDetail vAxis,
                                        ParallelConnectionGapAxisDetail hAxis) {}
}
