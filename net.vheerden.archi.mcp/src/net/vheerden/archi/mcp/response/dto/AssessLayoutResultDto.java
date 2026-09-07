package net.vheerden.archi.mcp.response.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * Result DTO for the assess-layout tool.
 *
 * <p>{@code overlapCount} contains only SAME-PARENT overlaps — two children of one container, or
 * two top-level objects, which are siblings of each other because both carry a null parent. A
 * cross-branch pair (different parents, neither inside the other) is NOT counted there under its
 * own names: {@code cousinOverlapCount} names that pair, and it reaches the rating through the
 * overlap of its containers or through {@code boundaryViolationCount}.
 * {@code containmentOverlaps} tracks expected ancestor-descendant overlaps (informational).
 * {@code orphanedConnections} counts connections with missing source/target view objects.
 * {@code noteOverlapCount} tracks note-element overlaps (informational, not penalizing).
 * {@code hasGroups} indicates whether the view contains group containers — BOTH kinds: a native
 * view group created by {@code add-group-to-view}, and an ArchiMate {@code Grouping} element
 * placed by {@code add-to-view}. The name is kept for compatibility, but the question every
 * consumer asks of it is "does the grouped tool family apply to this view?", and it does for
 * either kind. Note this is deliberately WIDER than the {@code groups} bucket of
 * {@code get-view-contents}, which reports only native groups and lists a {@code Grouping} among
 * the elements: that field answers "where do I look this object up?", this one answers "which
 * remedies apply?".
 * {@code ratingBreakdown} shows per-metric contributions to the overall rating.
 * {@code coincidentSegmentCount} tracks overlapping connection route segments.
 * {@code nonOrthogonalTerminalCount} tracks connections with diagonal terminal segments, and is
 * partitioned by {@code zeroBendpointNonOrthogonalTerminalCount} (straight lines between two
 * element centres) and {@code routedNonOrthogonalTerminalCount} (connections carrying stored
 * bendpoints), which sum to it and whose remedies differ.
 * {@code contentBounds} is the axis-aligned bounding box of all visual content.
 * {@code labelTruncationCount}, {@code parentLabelObscuredCount}, {@code imageSiblingOverlapCount},
 * {@code overlayIconCollisionCount} are informational detections. {@code imageSiblingOverlapCount}
 * covers the sibling axis; {@code overlayIconCollisionCount} covers the containment axis (an
 * element's overlay icon colliding with the icon of an element that contains it).
 * {@code parentLabelObscuredCount} promoted to layout Tier 1L;
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
 * {@code hAxisParallelGapNarrow25Count} (the same count on the H axis),
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
        int overlayIconCollisionCount,
        List<String> overlayIconCollisionDescriptions,
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
        int hAxisParallelGapNarrow25Count,
        ParallelConnectionGapDetailDto parallelConnectionGapDetail,
        // Hub-to-neighbour crowding density signal (appended)
        double hubNeighbourClearanceMin,
        // Coverage declaration (appended). Maps each defect-dimension id to one of
        // {@code checked} / {@code partial} / {@code not-checked} / {@code not-applicable},
        // driven by the assessor's canonical dimension registry. Non-null and populated on
        // the main {@code assess()} path AND on the degenerate empty/single-object path,
        // which declares what it could not evaluate rather than returning nothing.
        // An EMPTY map means "legacy path, coverage not declared" and is now reachable only
        // via the back-compat constructors that do not take a coverage argument — no shipped
        // response path produces one. Informational only — never affects any rating.
        // {@code not-checked} means the dimension was NOT evaluated: absence of a finding is
        // not evidence of absence.
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
        List<String> coincidentFacePortDescriptions,
        // An element's own overlay icon drawn over its own title label (appended), wherever the
        // object's own textAlignment and verticalTextAlignment place that title.
        // {@code ownIconOverLabelCount} counts elements whose clamped icon rectangle intersects
        // their own title band; {@code ownIconOverLabelDescriptions} names each. Informational only
        // — no rating impact. Distinct from both icon counts above, which compare an icon against
        // sibling boxes / an ancestor's icon and therefore cannot see a glyph sitting on the title
        // inside the icon's own element box.
        int ownIconOverLabelCount,
        List<String> ownIconOverLabelDescriptions,
        // Cross-branch ("cousin") overlaps (appended). EVERY pair of objects with different
        // parents, neither inside the other, whose rectangles intersect. {@code overlapCount}
        // above is a SAME-PARENT count, so it reports such a pair's containers rather than the
        // pair itself; the colliding objects themselves are named among these pairs. A count of
        // pairs to inspect, not of distinct visible collisions — one collision between two nested
        // objects normally yields several pairs. Informational only — it appears in no rating
        // breakdown and in no tier. {@code cousinOverlaps} is capped at 10 entries like every
        // other description list here, so it is shorter than {@code cousinOverlapCount} on a
        // badly broken view; the {@code cousinOverlaps} violator-id key enumerates the rest.
        int cousinOverlapCount,
        List<String> cousinOverlaps,
        // TRUE, uncapped number of boundary violations (appended). {@code boundaryViolations}
        // above is a capped description list, so on a badly broken view its size understates the
        // problem; this is the count to act on.
        int boundaryViolationCount,
        // Anchor drift (appended). Connections whose two stored bendpoint reconstructions — one
        // relative to the source centre, one relative to the target centre — disagree by more than
        // the representable-precision floor on either axis, meaning the stored route was computed
        // for a geometry that has since moved. {@code anchorDriftDescriptions} carries the measured
        // drift in px per axis, so the agent can act on the size of the move rather than on a flag.
        // Informational only — no rating impact. Not a route-shape defect: the shape was correct
        // when written, so the remedy is to re-route the connection, not to straighten it.
        int anchorDriftCount,
        List<String> anchorDriftDescriptions,
        // Lateral-jog reversals (appended). Connections containing a four-point window whose two
        // outer arms run in opposite directions along one axis, separated by a perpendicular
        // sidestep too narrow to be routing around anything. {@code lateralJogReversalDescriptions}
        // carries the four coordinates of each window. Informational only — no rating impact.
        // Distinct from the zigzag reversal above, which needs three points sharing ONE axis: the
        // sidestep puts the two arms on parallel lines, so no triple in the window shares an axis.
        int lateralJogReversalCount,
        List<String> lateralJogReversalDescriptions,
        // The two disjoint halves of {@code nonOrthogonalTerminalCount} (appended), each MEASURED
        // when its connection was flagged rather than derived by subtracting the other from the
        // total. {@code zeroBendpointNonOrthogonalTerminalCount} counts connections drawn as a
        // straight line between two element centres — the ELK auto-layout signature, with no
        // routed body to preserve; {@code routedNonOrthogonalTerminalCount} counts those carrying
        // stored bendpoints. On every response the assessor produces they sum to
        // {@code nonOrthogonalTerminalCount}, whose name, meaning and value are unchanged, and all
        // three are published so the partition is readable without the client performing the
        // arithmetic to discover one exists. The sum is a property of the measured path, NOT of
        // this record: the back-compat constructors below take a caller-supplied
        // {@code nonOrthogonalTerminalCount} and default both halves to zero, because a legacy
        // caller passes a total it never partitioned and inventing a split for it would publish a
        // measurement nothing made. So on a legacy-constructed response the two halves can
        // both read zero beside a non-zero total; production never takes that path, which builds
        // the canonical widest form from the assessor's own measured counts.
        // Both are reported on every call; their connection ids ride in the {@code violatorIds}
        // map under {@code nonOrthogonalTerminalsZeroBendpoint} and
        // {@code nonOrthogonalTerminalsRouted}. The halves matter because their remedies are
        // opposite: re-routing is right for one and is what the other's remedy warns against.
        int zeroBendpointNonOrthogonalTerminalCount,
        int routedNonOrthogonalTerminalCount,
        // Fan-out sizing preconditions (appended). Every element on the view carrying more
        // connections than the fan-out gate whose box is below the absolute floor for that count,
        // with the box it has and the box it needs. This is a PRECONDITION, not a defect
        // dimension: it appears in no rating breakdown, moves no tier and is absent from the
        // coverage registry, because it reports a state the caller can still act on cheaply
        // rather than one the layout has already been marked down for. Null when nothing is
        // unmet, so the field's presence is itself the signal. Every value is measured from the
        // view's own geometry — the caller does not have to compute a target from prose.
        List<HubPreconditionDto> unsizedHubs,
        // The dimensions this run downgraded CONTEXTUALLY (appended) — declared fully covered in
        // the assessor's registry and reported {@code partial} here only because something on THIS
        // view could not be measured. In registry order, so it matches the {@code coverage} map's
        // own order.
        //
        // Deliberately NOT every dimension reading {@code partial}. Two dimensions declare
        // {@code partial} permanently and a third declares {@code not-checked}, so every
        // fully-assessed run carries at least three non-{@code checked} entries before anything
        // about the view is considered; those are a property of the code, identical on every
        // response, and republishing them as though they were this run's findings would bury the
        // entries that are. This list is the news, and {@code coverage} beside it is the detail.
        //
        // An EMPTY list is a positive, verified statement — no dimension was contextually
        // downgraded on this run — and NOT an abstention, which is why it is not folded to null the
        // way the description lists are: there, an empty array would read as "checked and found
        // none" for a dimension nothing examined, whereas here the coverage map sitting beside it
        // still declares every permanent gap. The back-compat constructors are the exception and
        // are NOT all alike: two of them declare no {@code coverage} at all, so an empty list
        // there says exactly as little as the empty map beside it. The third takes a
        // caller-supplied {@code coverage} and still passes an empty list, because this record
        // cannot derive the selection — the classification lives on the assessor's dimension
        // registry, in the model layer, which this package deliberately does not import (it
        // mirrors the assessor's constants rather than depending on it). So on THAT constructor
        // the list is "not derived", not "nothing was downgraded". Nothing reaches it with a map
        // that could disagree — every current caller builds from the degenerate map, whose
        // baseline levels are never {@code partial} — but the honest reading of an empty list on
        // any back-compat path is "this path declares nothing", never "this run was clean".
        //
        // Informational only — it appears in no rating breakdown and moves no tier.
        List<String> contextualPartialDimensions,
        // The number of pass-through crossings the RATING charges (appended). It is NOT the size of
        // {@code connectionPassThroughs} above, and the two disagree in BOTH directions: that list
        // also names the unrated self-element pass-throughs (so its size can overstate this count)
        // and it is capped at ten entries (so its size can understate it). A caller asking "how
        // many crossings was this view marked down for" reads this field; the list stays what it
        // has always been, a capped human-readable description of what was seen.
        //
        // The back-compat constructors below default it to {@code 0}, so a legacy-constructed
        // response can read {@code 0} here beside a non-empty {@code connectionPassThroughs} — the
        // same shape, and for the same reason, as the terminal-partition halves above: a legacy
        // caller passes a description list it never partitioned into charged and unrated, and
        // deriving a count from that list would publish a measurement nothing made. Production
        // never takes those paths; it builds the canonical widest form from the assessor's own
        // cross-element tally.
        int crossElementPassThroughCount) {

    /**
     * Sentinel for {@code hubNeighbourClearanceMin} when no detected hub has a measurable
     * spoke row. Negative reads as "not crowded / not measured", so the next-step emitter
     * keeps its hub-existence-safe diagnostic instead of branching sparse vs dense. Mirrors
     * {@code LayoutQualityAssessor.NO_HUB_NEIGHBOUR_CLEARANCE}.
     */
    public static final double NO_HUB_NEIGHBOUR_CLEARANCE = -1.0;

    /**
     * Builds the response for a DEGENERATE view — one holding at most one object, where no
     * geometric comparison between objects is possible.
     *
     * <p>The view does not rate: {@code overallRating}, {@code layoutRating} and
     * {@code routingRating} are all {@code not-applicable}, because the spacing and alignment
     * inputs a rating needs return an explicit no-data sentinel below two objects and scoring
     * those would judge a pristine one-object view for being small. Every pairwise metric is
     * therefore zero here as a structural fact, not as a measurement.</p>
     *
     * <p>What is NOT zero is the object-local findings. Four detectors are computable on a single
     * object and their real counts and descriptions ride here, alongside the {@code coverage} map
     * declaring which of them actually ran. {@code connectionCount} likewise carries the measured
     * count — a lone object can hold a self-referencing connection, and reporting a hard zero
     * beside a coverage map that says the connection dimensions are unevaluated would assert as
     * fact something no detector established.</p>
     *
     * <p>Lives here rather than at the call site because assembling the canonical widest shape is
     * DTO knowledge, and because the accessor facade is size-ratcheted: keeping the assembly in the
     * DTO leaves the facade a wiring call.</p>
     */
    public static AssessLayoutResultDto degenerate(
            String viewId, int objectCount, int connectionCount,
            int orphanedConnections, List<String> orphanedConnectionDescriptions,
            List<String> offCanvasWarnings,
            int labelTruncationCount, List<String> labelTruncations,
            int noteClipCount, List<String> noteClipDescriptions,
            int ownIconOverLabelCount, List<String> ownIconOverLabelDescriptions,
            List<String> suggestions, Map<String, String> coverage,
            List<String> contextualPartialDimensions) {
        return new AssessLayoutResultDto(
                viewId, objectCount, connectionCount, 0, 0, 0, 0.0, 0.0, 0,
                "not-applicable", Map.of("overall", "not-applicable"),
                null, null, null, emptyToNull(offCanvasWarnings), 0, null,
                orphanedConnections, emptyToNull(orphanedConnectionDescriptions),
                0, null,
                noteClipCount, emptyToNull(noteClipDescriptions),
                false, 0, 0, null,
                labelTruncationCount, emptyToNull(labelTruncations),
                0, null, 0, null,
                // overlay-icon collision needs a second object to compare against
                0, null,
                null, suggestions,
                0, null, 0, null, 0, null, 1.0, null,
                "not-applicable", "not-applicable",
                1.0, null,
                // parallelConnectionGap needs two parallel routes
                null, 0, 0, null,
                NO_HUB_NEIGHBOUR_CLEARANCE,
                coverage,
                // route-vs-visual, bendpoint and label-host dimensions all need a route to examine
                0, null, 0, null, 0, null, 0, null, 0, null, 0, null, 0, null,
                0, 0, null, 0, null,
                ownIconOverLabelCount, emptyToNull(ownIconOverLabelDescriptions),
                // cross-branch overlap + true boundary-violation count defaults (informational)
                0, null, 0,
                // anchor drift and lateral-jog reversal both need a connection to examine
                0, null, 0, null,
                // both halves of a partition of zero flagged terminals are structurally zero
                0, 0,
                // A view holding at most one object has no fan-out precondition to report: the
                // gate is more connections than the fan-out threshold, and a lone object cannot
                // reach it even through a self-loop. Null is measured here, not skipped.
                null,
                // A degenerate view CAN carry a contextual downgrade: its own icon detector runs on
                // the lone object and reports an unmeasured title. Carrying the list here is what
                // lets a whole-model sweep see that state on a one-object view instead of reading
                // its silence as a clean result.
                contextualPartialDimensions == null ? List.of() : contextualPartialDimensions,
                // MEASURED zero, not a default: a cross-element crossing needs a second element to
                // be drawn across, and a view holding at most one object has none.
                0);
    }

    /**
     * Empty list to null, so the response mapper omits the key entirely rather than emitting an
     * empty array that reads as "checked and found none" for a dimension nothing examined.
     */
    private static List<String> emptyToNull(List<String> values) {
        return values == null || values.isEmpty() ? null : values;
    }

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
                imageSiblingOverlapDescriptions,
                // overlay-icon collision defaults (informational)
                0, null,
                violatorIds, suggestions,
                // Routing-quality defaults + corridor-utilisation default + parallelConnectionGap defaults
                0, null, 0, null, 0, null, 1.0, null,
                overallRating, overallRating, 1.0, null,
                null, 0, 0, null,
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
                0, null,
                // own-icon-over-own-label default (not detected on this legacy path)
                0, null,
                // cross-branch overlap default (not detected on this legacy path).
                // The boundary count is DERIVED from the description list rather
                // than defaulted to 0: this overload takes the descriptions but
                // has no separate count to take, and a 0 beside a non-empty list
                // is not a missing value — it is a false all-clear on a field the
                // rating, the Tier-1L regression veto and the iteration loop all
                // read. Deriving is exact whenever the list is uncapped, and can
                // only ever understate on a view already past the cap; it can
                // never claim clean when violations were reported.
                0, null,
                boundaryViolations == null ? 0 : boundaryViolations.size(),
                // anchor drift and lateral-jog reversal defaults — a legacy caller passes a path
                // it did not reconstruct from stored anchors, so it has no disagreement to report
                0, null, 0, null,
                // non-orthogonal-terminal partition defaults (not measured on this legacy path)
                0, 0,
                // A caller that predates the fan-out preconditions never measured them, and
                // inventing an empty list here would publish "all hubs adequate" as a finding
                // nothing established. Null omits the field instead.
                null,
                // Empty because a caller on this ladder declares no coverage it could be derived
                // from — the same reason the coverage map is empty here, and asserting no more
                // than that map does.
                List.of(),
                // Charged pass-through count default (see the component's own note): a legacy
                // caller passes a description list it never partitioned, so there is no charged
                // count to forward and none may be invented from the list.
                0);
    }

    /**
     * Backwards-compatible 45-arg constructor.
     *
     * <p>Preserves call sites that built the DTO with the post-corridor-utilisation /
     * pre-parallelConnectionGap shape. The three new parallelConnectionGap fields populate with neutral defaults
     * ({@code null / 0 / null}) so callers compile unchanged. Production code (the
     * {@code assess-layout} handler) uses the canonical widest form, which carries the real
     * registry-driven coverage map, to forward real values.</p>
     *
     * <p>Delegates to the 46-arg overload with an empty coverage map, which by contract means
     * "legacy path, coverage not declared". A caller that CAN declare coverage — the degenerate
     * short-circuit, which knows the object count — must use that overload instead: an empty map
     * there would report silence as if it were an answer.</p>
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
                noteOverlapDescriptions, hasGroups, coincidentSegmentCount,
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
                // coverage default — legacy path, coverage not declared
                Map.of());
    }

    /**
     * Backwards-compatible 46-arg constructor: the 45-arg shape above plus an explicit
     * {@code coverage} declaration.
     *
     * <p>Exists for the degenerate short-circuit, which returns before the assessor runs and so
     * cannot use the canonical widest form, yet DOES know which dimensions could not apply and
     * which simply went unevaluated. Every other legacy default of the 45-arg shape is unchanged,
     * so the two differ in exactly one thing: whether coverage is declared or left empty.</p>
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
            List<CorridorUtilisationDetailDto> corridorUtilisationChannels,
            Map<String, String> coverage) {
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
                imageSiblingOverlapDescriptions,
                // overlay-icon collision defaults (informational)
                0, null,
                violatorIds, suggestions,
                interiorTerminationCount, interiorTerminationDescriptions,
                zigzagCount, zigzagDescriptions,
                connectionEdgeCoincidenceCount, edgeCoincidenceDescriptions,
                hubPortQualityScore, hubPortQualityFaces,
                layoutRating, routingRating,
                corridorUtilisationScore, corridorUtilisationChannels,
                // parallelConnectionGap defaults
                null, 0, 0, null,
                // hub-to-neighbour crowding default (no hub measured)
                NO_HUB_NEIGHBOUR_CLEARANCE,
                // coverage as declared by the caller (empty only on the legacy 45-arg path)
                coverage,
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
                0, null,
                // own-icon-over-own-label default (not detected on this legacy path)
                0, null,
                // cross-branch overlap default (not detected on this legacy path).
                // The boundary count is DERIVED from the description list rather
                // than defaulted to 0: this overload takes the descriptions but
                // has no separate count to take, and a 0 beside a non-empty list
                // is not a missing value — it is a false all-clear on a field the
                // rating, the Tier-1L regression veto and the iteration loop all
                // read. Deriving is exact whenever the list is uncapped, and can
                // only ever understate on a view already past the cap; it can
                // never claim clean when violations were reported.
                0, null,
                boundaryViolations == null ? 0 : boundaryViolations.size(),
                // anchor drift and lateral-jog reversal defaults — a legacy caller passes a path
                // it did not reconstruct from stored anchors, so it has no disagreement to report
                0, null, 0, null,
                // non-orthogonal-terminal partition defaults (not measured on this legacy path)
                0, 0,
                // A caller that predates the fan-out preconditions never measured them, and
                // inventing an empty list here would publish "all hubs adequate" as a finding
                // nothing established. Null omits the field instead.
                null,
                // Empty because a caller on this ladder declares no coverage it could be derived
                // from — the same reason the coverage map is empty here, and asserting no more
                // than that map does.
                List.of(),
                // Charged pass-through count default (see the component's own note): a legacy
                // caller passes a description list it never partitioned, so there is no charged
                // count to forward and none may be invented from the list.
                0);
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
                imageSiblingOverlapDescriptions,
                // overlay-icon collision defaults (informational)
                0, null,
                violatorIds, suggestions,
                interiorTerminationCount, interiorTerminationDescriptions,
                zigzagCount, zigzagDescriptions,
                connectionEdgeCoincidenceCount, edgeCoincidenceDescriptions,
                hubPortQualityScore, hubPortQualityFaces,
                layoutRating, routingRating,
                corridorUtilisationScore, corridorUtilisationChannels,
                vAxisParallelGapP10, vAxisParallelGapNarrow25Count,
                // This rung has no H-axis parameter, so the count is taken from the detail record
                // it is already forwarding rather than defaulted to zero. A hard zero beside a
                // detail whose hAxis reports a nonzero count would make the DTO disagree with
                // itself about one measurement pass — and zero is this metric's clean value, so
                // the disagreement would read as an all-clear rather than as an absence.
                hAxisNarrowCountOf(parallelConnectionGapDetail),
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
                0, null,
                // own-icon-over-own-label default (not detected on this legacy path)
                0, null,
                // cross-branch overlap default (not detected on this legacy path).
                // The boundary count is DERIVED from the description list rather
                // than defaulted to 0: this overload takes the descriptions but
                // has no separate count to take, and a 0 beside a non-empty list
                // is not a missing value — it is a false all-clear on a field the
                // rating, the Tier-1L regression veto and the iteration loop all
                // read. Deriving is exact whenever the list is uncapped, and can
                // only ever understate on a view already past the cap; it can
                // never claim clean when violations were reported.
                0, null,
                boundaryViolations == null ? 0 : boundaryViolations.size(),
                // anchor drift and lateral-jog reversal defaults — a legacy caller passes a path
                // it did not reconstruct from stored anchors, so it has no disagreement to report
                0, null, 0, null,
                // non-orthogonal-terminal partition defaults (not measured on this legacy path)
                0, 0,
                // A caller that predates the fan-out preconditions never measured them, and
                // inventing an empty list here would publish "all hubs adequate" as a finding
                // nothing established. Null omits the field instead.
                null,
                // Empty because a caller on this ladder declares no coverage it could be derived
                // from — the same reason the coverage map is empty here, and asserting no more
                // than that map does.
                List.of(),
                // Charged pass-through count default (see the component's own note): a legacy
                // caller passes a description list it never partitioned, so there is no charged
                // count to forward and none may be invented from the list.
                0);
    }

    /**
     * Axis-aligned bounding box of all visual content on a view.
     * Uses absolute canvas coordinates.
     */
    public record ContentBoundsDto(double x, double y, double width, double height) {}

    /**
     * One element whose connection fan-out needs more room than its current box provides.
     *
     * <p>{@code connectionCount} counts every ArchiMate connection on the view incident to this
     * view object, <strong>once per endpoint</strong> — so a self-loop counts twice — and ignores
     * plain (non-ArchiMate) diagram connections such as a line drawn to a Note. It is produced by
     * the same walk {@code detect-hub-elements} publishes, so the two tools cannot report
     * different numbers for the same element.</p>
     *
     * <p>{@code requiredWidth} / {@code requiredHeight} are an absolute floor for that connection
     * count, not a growth step: an element already at or above both is not reported here at all,
     * and one resized to them stays satisfied. An entry appears when EITHER axis is short, and
     * both required values are given so a single {@code update-view-object} call can satisfy the
     * whole row.</p>
     */
    public record HubPreconditionDto(String elementId, String viewObjectId, String name,
                                      int connectionCount, int currentWidth, int currentHeight,
                                      int requiredWidth, int requiredHeight) {}

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

    /**
     * The H-axis narrow-gap count carried by a detail record, or zero when there is none to carry.
     *
     * <p>Used by the rung that predates the top-level H count, so that rung reports what its own
     * detail says instead of asserting a clean zero over it.</p>
     */
    private static int hAxisNarrowCountOf(ParallelConnectionGapDetailDto detail) {
        return (detail == null || detail.hAxis() == null) ? 0 : detail.hAxis().narrowGapCount25();
    }
}
