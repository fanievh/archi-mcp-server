package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.vheerden.archi.mcp.model.geometry.GeometryUtils;
import net.vheerden.archi.mcp.model.routing.CoincidentSegmentDetector;
import net.vheerden.archi.mcp.model.routing.CoincidentSegmentDiagnostic;

/**
 * Stateless pure-geometry computation for layout quality assessment.
 * No EMF imports — operates on {@link AssessmentNode} and {@link AssessmentConnection} records.
 *
 * <p>All coordinates are expected to be in absolute canvas space. The accessor is
 * responsible for converting nested element coordinates by accumulating parent offsets.</p>
 *
 * <p>Ancestor-descendant containment relationships (groups containing elements,
 * including nested groups) are handled specially: overlap, spacing, and alignment
 * metrics exclude containment pairs to avoid false positives from intentional nesting.</p>
 */
class LayoutQualityAssessor {

    private static final int MAX_DESCRIPTIONS = 10;
    private static final double ALIGNMENT_TOLERANCE = 5.0;
    /** Angular threshold (degrees) for non-orthogonal terminal detection. */
    private static final double NON_ORTH_ANGLE_THRESHOLD = 5.0;
    /**
     * Visible-segment-length guard (2026-04-27):
     * when a candidate non-orthogonal terminal's visible (post-clip) segment length is
     * below this threshold, the diagonal is sub-perceptible at typical Archi zoom levels
     * and is silently skipped. Calibrated against the V4 manual oracle view
     * (id-3b2665e3ff6840708dbed2b3d1415613): 20 of 21 violators were 1.0–1.3px visible,
     * produced by Archi storing manually-routed BPs 1px off the perimeter face line.
     */
    static final double VISIBLE_DIAGONAL_MIN_PX = 3.0;
    /**
     * Minimum perpendicular departure clearance (px) for a terminal exit. When a connection
     * leaves an element face but its first exterior segment runs PARALLEL to that face with a
     * perpendicular clearance below this value, the route hugs the face it just departed — a
     * visible "stub" defect the angular terminal check ({@link #countNonOrthogonalTerminals})
     * misses because the imperceptible &lt;{@link #VISIBLE_DIAGONAL_MIN_PX}px diagonal exit is
     * suppressed. Counted by {@link #countOffFaceParallelTerminals}. Informational only — never
     * fed to the rating. Calibrated against the real render; refine if a clean L-exit at this
     * clearance still reads as hugging.
     */
    static final double OFF_FACE_MIN_STUB_PX = 8.0;
    /**
     * Local mirror of the routing pass's healthy parallel-connection-gap floor
     * ({@code TerminalEgressClearancePass.HEALTHY_PARALLEL_GAP_PX = 15}). When the perpendicular
     * room available to lift a hugging terminal off its face is below this value, the router
     * declines the lift (applying it would narrow a parallel-connection gap below the floor), so
     * the hug is layout-bound and the remedy is to widen the corridor, not to re-route. The
     * assessor does not run the router, so it keeps a local copy of the floor to make its off-face
     * remedy advice consistent with the router's actual decision — the same mirroring rationale the
     * router uses for the assessor's coincident-port slot tolerance. Kept in sync manually with the
     * routing-package constant, which is package-private there.
     */
    static final double HEALTHY_PARALLEL_GAP_PX = 15.0;
    private static final double OFF_CANVAS_THRESHOLD = 10000.0;

    // Suggestion thresholds (Finding #11: named constants with documented rationale)
    /** Edge crossings above this count trigger a suggestion to use hierarchical layout. */
    static final int CROSSING_SUGGESTION_THRESHOLD = 10;
    /** Average spacing below this (px) triggers a "too tight" suggestion. */
    static final double SPACING_SUGGESTION_THRESHOLD = 15.0;
    /** Alignment score below this triggers a "poor alignment" suggestion. */
    static final int ALIGNMENT_SUGGESTION_THRESHOLD = 30;

    // Overall rating thresholds
    static final int EXCELLENT_MAX_CROSSINGS = 5;
    static final double EXCELLENT_MIN_SPACING = 30.0;
    static final int EXCELLENT_MIN_ALIGNMENT = 60;
    static final int GOOD_MAX_CROSSINGS = 20;
    static final double GOOD_MIN_SPACING = 15.0;
    static final int GOOD_MIN_ALIGNMENT = 30;
    static final int FAIR_MAX_CROSSINGS = 30;
    static final int FAIR_MAX_PASS_THROUGHS = 3;

    // Density-aware crossing thresholds
    /** Crossings per connection ratio: moderate impact threshold. */
    static final double CROSSING_RATIO_MODERATE = 4.0;
    /** Crossings/connection ratio below this is "good" quality. */
    static final double CROSSING_RATIO_GOOD = 1.5;

    /**
     * Inset (px) applied to obstacle rectangles before pass-through intersection tests.
     * Accounts for OrthogonalAnchor corner-arc imprecision in diagonal exit zones
     * where the simplified ChopboxAnchor fallback deviates from Archi's actual
     * corner arc calculation (using COSPI4). Typical deviation: 10-15px.
     */
    static final double PASS_THROUGH_INSET = 10.0;

    /**
     * Inset for self-element pass-through detection.
     * Smaller than PASS_THROUGH_INSET to match the router's 5px tolerance,
     * ensuring the assessor safety net catches everything the router detects.
     */
    static final double SELF_ELEMENT_INSET = 5.0;

    // Coincident segment thresholds
    static final int GOOD_MAX_COINCIDENT = 3;
    static final int FAIR_MAX_COINCIDENT = 8;

    // Non-orthogonal terminal density thresholds
    /** Non-orth terminals per connection ratio: at or below this is "good" quality. */
    static final double NON_ORTH_RATIO_GOOD = 0.10;
    /** Non-orth terminals per connection ratio: at or below this is "fair" quality. */
    static final double NON_ORTH_RATIO_FAIR = 0.30;

    /** Above this element count, add a performance warning to suggestions. */
    static final int LARGE_VIEW_WARNING_THRESHOLD = 500;

    // ---- Perception-aligned metric constants ----

    /**
     * Tolerance (px) for testing whether a bendpoint lies on an element's perimeter line (M1).
     * Bendpoints stored by Archi are int-snapped, so 0.5px tolerance is sufficient and
     * avoids float-equality fragility.
     */
    static final double PERIMETER_TOLERANCE_PX = 0.5;

    /**
     * Tolerance (px) for the terminal egress-stub on-face test only (see {@link #isOnPerimeterFace}).
     * Wider than {@link #PERIMETER_TOLERANCE_PX} because Archi stores a router-attached terminal port
     * up to ~1px off the exact perimeter line (int-snapped edge attachment on odd-parity rects), so a
     * 0.5px band misses genuine egress ports and the exclusion under-fires. This looser band is scoped
     * to the redundant-bendpoint exclusion; the strict 0.5px constant that the overlap/perimeter
     * detectors depend on is left unchanged.
     */
    static final double ON_FACE_STUB_TOLERANCE_PX = 1.5;

    /**
     * Tolerance (px) for testing whether two bendpoints share an axis (M3 zigzag detection).
     * Matches Archi's int-snapped storage.
     */
    static final double ZIGZAG_AXIS_TOLERANCE_PX = 1.0;

    /**
     * Minimum delta (px) magnitude for the two reversal arms of a zigzag triple (M3).
     * Below this, the bendpoint sequence is treated as colinear (no reversal).
     */
    static final double ZIGZAG_MIN_DELTA_PX = 1.0;

    /**
     * Reconstruction-noise floor (px) for treating a bendpoint as redundant — collinear along a
     * HORIZONTAL or VERTICAL segment and removable without changing the orthogonal route. A point
     * is redundant only when the triple {@code a,b,c} lies within this ε of an axis-aligned line
     * (thinner bounding-box extent ≤ ε), matching the router's exact axis-aligned collinear-removal
     * contract (RoutingPipeline.removeCollinearPoints), which is the only remediation an agent has.
     *
     * <p>ε absorbs the ±0.5 px injected when relative int bendpoints are reconstructed against
     * double element centres (int {@code x + width/2} vs double {@code x + width/2.0} on odd
     * widths), so genuinely axis-collinear leftovers still flag. It must stay {@code < 1 px} so a
     * real ≥1 px micro-jog (a tiny but visible orthogonal corner) is NOT reported: removing such a
     * point would diagonalise the route, so it was never truly redundant.
     */
    static final double REDUNDANT_BENDPOINT_AXIS_COLLINEAR_EPSILON_PX = 0.5;

    /**
     * Noise floor (px) below which a disagreement between a bendpoint's two stored reconstructions
     * is representable-precision noise rather than movement.
     *
     * <p>Derivation, not measurement. Archi stores a bendpoint's offsets as <strong>integers</strong>
     * against element centres that are half-integral whenever the box width or height is odd
     * ({@code x + width / 2}). Writing one absolute point through both anchors therefore rounds
     * twice, independently, each by at most ±0.5 px, so two reconstructions of an <em>unmoved</em>
     * point can differ by up to 1.0 px. Anything beyond that is not representable as rounding: some
     * endpoint moved or was resized after the route was written.
     *
     * <p>Deliberately NOT fitted to any model. The largest innocent disagreement observable on a
     * given view is usually 0.5 px, because that needs only one endpoint to have an odd dimension —
     * but a view whose connection joins two odd-dimensioned boxes reaches the full 1.0, and a floor
     * tuned to the commoner case would report it as drift.
     */
    static final double ANCHOR_DRIFT_NOISE_FLOOR_PX = 1.0;

    /**
     * Largest perpendicular sidestep (px) that still reads as a lateral jog rather than a deliberate
     * detour, for the four-point reversal test in {@link #countLateralJogReversals}.
     *
     * <p>Mirrors the derivation of {@link #OFF_FACE_MIN_STUB_PX} (8.0), the clearance that already
     * separates a hugging exit from a legitimate one: a sidestep narrower than the minimum stub a
     * healthy route uses cannot be routing around anything, so the two arms it separates are the
     * same corridor traversed twice.
     *
     * <p>The constant is an upper bound only. A sidestep of <em>any</em> width puts the two arms on
     * two parallel lines, so no triple inside the window ever carries two live arms and
     * {@link #countZigzags} cannot claim the shape at any tolerance — narrowing the jog does not
     * hand it back. What the bound separates is a reversal from a legitimate detour: above 8px the
     * route is genuinely going around something, and is correctly reported by nobody.
     */
    static final double LATERAL_JOG_MAX_PX = 8.0;

    /**
     * Distance (px) within which a connection segment is considered coincident with a
     * foreign element's edge line (M4). Per spec: 3px hugs the perimeter visibly.
     */
    static final double EDGE_COINCIDENCE_TOLERANCE_PX = 3.0;

    /**
     * Minimum overlap (px) of a connection segment's projected extent against a foreign
     * element's edge extent for an M4 edge-coincidence flag.
     */
    static final double EDGE_COINCIDENCE_MIN_OVERLAP_PX = 10.0;

    /**
     * Public canonical hub-detection threshold (2026-05-04).
     * Elements with at or above this number of connections are CANDIDATE HUBS for the
     * agent's pre-routing analysis. This is the single public number cited in
     * {@code CLAUDE.md}, {@code README.md}, {@code archimate-view-patterns.md}, the
     * {@code detect-hub-elements} tool description, and {@code docs/layout-engine.md}.
     *
     * <p>Distinct from two internal thresholds with different roles:
     * <ul>
     *   <li>{@link #M5_FACE_GUARD_MIN_CONNECTIONS} (4) — face-count guard for the M5
     *       hub-port-quality metric (rating-internal, not public).
     *   <li>{@code EdgeAttachmentCalculator.HUB_FACE_REDISTRIBUTION_THRESHOLD} (6) —
     *       Phase 1.1 face-redistribution gate (router-internal, behavioural).
     * </ul>
     * The {@code detect-hub-elements} tool emits sizing suggestions when
     * {@code connectionCount > HUB_DETECTION_THRESHOLD + 1} (i.e., {@code > 6}); the
     * {@code +1} aligns with the dimension formula's growth term
     * {@code 15 × (count − 6)} which is non-positive at exactly 5.
     */
    public static final int HUB_DETECTION_THRESHOLD = 5;

    /**
     * Internal threshold for the M5 hub-port-quality metric — minimum face-count to
     * participate in M5 scoring. Below this the metric is vacuous (a single face is
     * trivially balanced). Distinct from {@link #HUB_DETECTION_THRESHOLD} (public, 5).
     * Renamed from {@code HUB_FACE_MIN_CONNECTIONS}, 2026-05-04.
     */
    static final int M5_FACE_GUARD_MIN_CONNECTIONS = 4;

    /**
     * Slot-equality tolerance (px) when computing distinct slot counts on a hub face (M5).
     * Two terminal endpoints whose along-face coordinate differs by less than this are
     * considered to share a slot.
     */
    static final double HUB_PORT_SLOT_TOLERANCE_PX = 1.0;

    /**
     * Hub-port quality threshold (M5). View-aggregate quality below this contributes to
     * routing Tier 2R; at or above, the metric contributes "good" (no rating impact).
     */
    static final double HUB_PORT_QUALITY_FAIR_THRESHOLD = 0.5;

    /** Hub-port quality at or above this is "good" (no rating impact under M5). */
    static final double HUB_PORT_QUALITY_GOOD_THRESHOLD = 0.75;

    /** Hub-port quality at or above this is treated as a clean signal (no defect). */
    static final double HUB_PORT_QUALITY_PASS_THRESHOLD = 0.95;

    /**
     * The rating band {@code hubPortQuality} lands in for {@code score} — one of {@code "pass"},
     * {@code "good"}, {@code "fair"} or {@code "poor"}, the four values the rating breakdown
     * publishes for the metric.
     *
     * <p>This is the single definition of the boundaries. Both the breakdown entry and every
     * remedy keyed off the metric read it, because the remedy's job is to explain a band the
     * rating has already assigned: {@code fair} and {@code poor} each cap the view's routing
     * tier, so a view sitting anywhere in either band has been marked down for hub-port
     * allocation and needs to be told so. Encoding that boundary a second time as a literal is
     * how the two came apart before — the metric capped the whole quarter-wide {@code fair}
     * interval while the remedies fired only below the {@code poor} edge.</p>
     */
    static String hubPortQualityBand(double score) {
        if (score >= HUB_PORT_QUALITY_PASS_THRESHOLD) return "pass";
        if (score >= HUB_PORT_QUALITY_GOOD_THRESHOLD) return "good";
        if (score >= HUB_PORT_QUALITY_FAIR_THRESHOLD) return "fair";
        return "poor";
    }

    /** M4 edge-coincidence count thresholds for breakdown rating. */
    static final int EDGE_COINCIDENCE_GOOD_MAX = 2;
    static final int EDGE_COINCIDENCE_FAIR_MAX = 5;

    /**
     * A-gated escalation threshold (2026-05-21).
     * M4 {@code connectionEdgeCoincidence} is normally Tier-2R (cap-fair). When the count reaches or
     * exceeds this value the edge-hug is "egregious" and escalates to Tier-1R, so {@code overall}
     * reads "poor" instead of being masked at "fair". Anchored at 7 = the Retail Bank View G count
     * flagged by eye (2026-05-19); the common 1-5 forced-hug case stays cap-fair. MUST be
     * &gt; {@link #EDGE_COINCIDENCE_FAIR_MAX} for the escalation to be meaningful (below FAIR_MAX the
     * breakdown rating is not yet "poor", so escalating it would not change overall). Validated
     * 2026-05-21 as the regression guardrail beside the egress-lift router fix; live
     * geometry proved an egregious count is router-eliminable, not a topology floor.
     */
    static final int EDGE_COINCIDENCE_EGREGIOUS_MAX = 7;

    // ---- Hub-to-neighbour crowding / clearance signal (2026-06-25) ----

    /**
     * Sentinel for the hub-neighbour clearance scalar when no hub has a measurable spoke
     * row (no element with at least {@link #HUB_DETECTION_THRESHOLD} connections, or none
     * whose face carries at least {@link #CROWDING_MIN_ADJACENT_K} overlapping spoke
     * neighbours). A negative value reads as "not crowded / not measured": the crowding
     * breakdown entry stays {@code pass} and the next-step emitter falls back to its
     * hub-existence-safe diagnostic instead of branching sparse vs dense.
     */
    static final double NO_HUB_NEIGHBOUR_CLEARANCE = -1.0;

    /**
     * Crowding clearance floor (px). A hub edge whose nearest qualifying spoke row sits
     * closer than this has collapsed the inter-row corridor below readable spacing, so the
     * view can no longer rate {@code good}.
     *
     * <p>Live-calibration anchor — same playbook as {@link #VISIBLE_DIAGONAL_MIN_PX} and the
     * own-endpoint overlap fractions. The project layout strategy treats &lt; ~30 px as tight
     * and 100 px+ as generous; live evidence showed a resized hub sitting 45 px from a
     * 7-spoke row reading {@code good} purely because no metric captured the crowding. The
     * floor is set above that 45 px crowded evidence and below typical organic inter-row
     * spacing so the crowded resize fires while a sparse hub keeping a &ge; 60 px corridor
     * does not. The owner live gate is where the final value is confirmed.</p>
     */
    static final double CROWDING_FLOOR_PX = 60.0;

    /**
     * Minimum overlapping spoke neighbours on one hub face for a "row" to qualify. A single
     * close neighbour is incidental; at least three aligned along one face is a genuine spoke
     * row whose corridor the hub edge collapses. Gating on a row (not a single neighbour) is
     * what keeps the metric from firing on ordinary two-box adjacency (over-flag discipline).
     */
    static final int CROWDING_MIN_ADJACENT_K = 3;

    // ---- parallelConnectionGap metric constants ----
    // Informational narrow-corridor signal; no rating impact.

    /**
     * Tolerance (px) for testing whether a bendpoint pair forms an axis-aligned segment
     * (parallelConnectionGap V/H classification). Distinct from
     * {@link #ZIGZAG_AXIS_TOLERANCE_PX} (1.0): that constant tests zigzag-triple axis
     * membership; this constant tests parallel-gap segment classification.
     *
     * <p>Calibration-anchor value: the 4-view calibration workspace
     * (compute_parallel_gap.py:70) used AXIS_TOL = 2 px and produced V4 manual gold
     * V_p10 = 13.30 (perception-aligned). Tightening to 1.0 would drop borderline
     * near-axis segments and shift V_p10 away from the gold anchor, breaking the
     * JUnit pin.</p>
     */
    static final double PARALLEL_GAP_AXIS_TOLERANCE_PX = 2.0;

    /**
     * Narrow-gap count threshold (px) — T1 in the parallelConnectionGap metric family.
     * Segments with {@code nearestParallelGap < T1} are counted in {@code narrowGapCount15}.
     * See workspace {@code results.md} &sect; "Primary Metric Selection".
     */
    static final int PARALLEL_GAP_NARROW_T1_PX = 15;

    /**
     * Narrow-gap count threshold (px) — T2 in the parallelConnectionGap metric family.
     * Primary calibration-validated narrow-count threshold (per workspace
     * {@code results.md} &sect; "Primary Metric Selection"). Segments with
     * {@code nearestParallelGap < T2} are counted in {@code narrowGapCount25} and
     * contribute to the V-axis {@code violatorIds} set.
     */
    static final int PARALLEL_GAP_NARROW_T2_PX = 25;

    /**
     * Narrow-gap count threshold (px) — T3 in the parallelConnectionGap metric family.
     * Segments with {@code nearestParallelGap < T3} are counted in {@code narrowGapCount40}.
     */
    static final int PARALLEL_GAP_NARROW_T3_PX = 40;

    private final CoincidentSegmentDetector coincidentDetector;

    LayoutQualityAssessor() {
        this.coincidentDetector = new CoincidentSegmentDetector();
    }

    /**
     * Runs full layout quality assessment on the given nodes and connections.
     *
     * @param includeViolatorIds if true, collects per-metric violator IDs
     */
    LayoutAssessmentResult assess(List<AssessmentNode> nodes,
                                   List<AssessmentConnection> connections,
                                   boolean includeViolatorIds) {
        // Separate notes from layout nodes.
        // Notes are excluded from all scoring metrics but used for informational overlap detection.
        List<AssessmentNode> layoutNodes = new ArrayList<>();
        List<AssessmentNode> noteNodes = new ArrayList<>();
        for (AssessmentNode node : nodes) {
            if (node.isNote()) {
                noteNodes.add(node);
            } else {
                layoutNodes.add(node);
            }
        }

        // Build transitive containment set for exclusions (transitive closure)
        Set<String> containmentPairs = buildContainmentPairs(layoutNodes);

        // Single-pass overlap detection: sibling + containment counts (notes excluded)
        OverlapResult overlapResult = computeOverlaps(layoutNodes, containmentPairs, includeViolatorIds);
        int crossingCount = countEdgeCrossings(connections);
        double avgSpacing = computeAverageSpacing(layoutNodes, containmentPairs);
        int alignment = computeAlignmentScore(layoutNodes);
        // Label overlap detection — must precede rating/suggestions
        LabelOverlapResult labelResult = countLabelOverlaps(connections, layoutNodes);
        BoundaryViolationResult boundaryResult = detectBoundaryViolations(layoutNodes, includeViolatorIds);
        PassThroughResult passThroughResult = detectPassThroughs(connections, layoutNodes, includeViolatorIds);
        // Whether the grouped tool family applies to this view — which is what every consumer of
        // this flag actually asks (the crossing leniency below, the suggestion prose, and the
        // next-steps builder all branch on "is this a zoned view?"). Both container kinds answer
        // yes: an ArchiMate Grouping is a zone the group-aware remedies work on exactly as a
        // native group is, and reporting false for one left an agent told a canvas full of
        // populated zones had none.
        boolean hasGroups = false;
        for (AssessmentNode node : layoutNodes) {
            if (node.isContainer()) {
                hasGroups = true;
                break;
            }
        }
        // Coincident segment detection (optional violator IDs)
        CoincidentSegmentDetector.CoincidentSegmentResult coincidentResult =
                coincidentDetector.detectCoincidentSegments(connections, includeViolatorIds);
        int coincidentSegmentCount = coincidentResult.count();
        // Optional categorization of coincident segments (gated by system property).
        // Emits per-pair log with TERMINAL_APPROACH / GAP_CROSSING / WITHIN_GROUP tags.
        // Zero cost when property unset.
        if (Boolean.getBoolean("archi.mcp.diag.coincident") && !connections.isEmpty()) {
            emitCoincidentDiagnostic(connections, layoutNodes);
        }
        // Non-orthogonal terminal detection — post-clip visible segment.
        // Correction: bendpoints on or inside source/target element bounds are
        // not counted (Archi clips the rendered line at the perimeter).
        NonOrthogonalTerminalResult nonOrthResult =
                countNonOrthogonalTerminals(connections, layoutNodes, includeViolatorIds);
        int nonOrthogonalTerminalCount = nonOrthResult.count();

        // M2-M5: perception-aligned metrics.
        InteriorTerminationResult interiorResult =
                countInteriorTerminations(connections, layoutNodes, includeViolatorIds);
        ZigzagResult zigzagResult = countZigzags(connections, passThroughResult.violatorIds(), includeViolatorIds);
        // Anchor drift — a stored route whose geometry moved underneath it. Measured in the
        // collector, because the midpoint blend it performs is what makes the drift invisible to
        // every shape-based dimension below. Informational only; never fed into the rating.
        AnchorDriftResult anchorDriftResult = detectAnchorDrift(connections, includeViolatorIds);
        // Lateral-jog reversal — two opposite arms joined by a sidestep too narrow to be a detour.
        // The four-point shape the three-point zigzag predicate cannot express. Informational only.
        LateralJogReversalResult lateralJogResult = countLateralJogReversals(
                connections, passThroughResult.violatorIds(), zigzagResult.violatorIds(),
                includeViolatorIds);
        EdgeCoincidenceResult edgeCoincidenceResult =
                countConnectionEdgeCoincidence(connections, layoutNodes, includeViolatorIds);
        HubPortQualityResult hubPortResult =
                computeHubPortQuality(connections, layoutNodes, includeViolatorIds);
        // R8 Corridor Utilisation (2026-05-03).
        R8CorridorUtilisationResult corridorUtilisationResult =
                computeR8CorridorUtilisation(connections, layoutNodes, includeViolatorIds);
        // Hub-to-neighbour crowding / clearance (2026-06-25). Pure geometry; the crowded
        // flag caps the rating at fair (Tier 2L), the clearance scalar feeds the emitter.
        HubNeighbourCrowdingResult hubCrowdingResult =
                computeHubNeighbourCrowding(connections, layoutNodes);
        // parallelConnectionGap (2026-05-12).
        // Informational only — does NOT contribute to rating/suggestions.
        ParallelConnectionGapResult parallelGapResult =
                computeParallelConnectionGap(connections, includeViolatorIds);

        // Informational detection (label truncation, parent label obscured, image sibling overlap).
        // Hoisted above the rating call — these contribute to layoutRating (parentLabelObscured
        // promoted Tier 1L) and routingRating (labelTruncation promoted Tier 2R).
        LabelTruncationResult labelTruncResult = detectLabelTruncation(layoutNodes);
        ParentLabelObscuredResult parentLabelResult = detectParentLabelObscuredByChild(layoutNodes);
        ImageSiblingOverlapResult imageSiblingResult = detectImageSiblingOverlap(layoutNodes);
        // Overlay-icon collision across a containment pair — the axis the sibling detector above
        // cannot reach (it compares only within a parent bucket). Informational only; never an
        // argument to the rating or suggestion calls below.
        OverlayIconCollisionResult overlayIconResult = detectOverlayIconCollision(layoutNodes);
        // An element's own icon drawn over its own title — the axis both detectors above
        // are blind to, because the icon rect is clamped to the very box the title sits in.
        // The title's side is read from the object's own textAlignment, not assumed.
        // Informational only; never an argument to the rating or suggestion calls below.
        OwnIconOverLabelResult ownIconOverLabelResult = detectOwnIconOverLabel(layoutNodes);
        // Non-orthogonal interior-segment detection (off-cardinal mid segments). Hoisted above the
        // rating call — it contributes to routingRating (cap-fair, tier 2), mirroring the terminal
        // sibling: a route that bends off-cardinal mid-path is just as visible as one bending at an
        // endpoint, so it costs the same routing tier. The descriptions/violatorIds it carries are
        // consumed later when the result is assembled.
        NonOrthogonalInteriorSegmentResult nonOrthInteriorResult =
                countNonOrthogonalInteriorSegments(connections, includeViolatorIds);
        // Connection-through-note/image detection. Hoisted above the rating call — it contributes
        // to routingRating (cap-good, tier 3) on binary presence: a line routed through a Note or
        // image visual is an obstacle the router failed to avoid, always jarring to the reader.
        // Notes are excluded from the scoring node set, so a route through a Note is invisible to
        // detectPassThroughs (scoring elements only). An image rect is bounded by its element box,
        // so a route through an element's image is also a box pass-through; where a crossing trips
        // both, the routing tier takes the worse of the two and it is never charged twice. The
        // descriptions it carries are consumed later when the result is assembled.
        ConnectionThroughVisualResult throughVisualResult =
                detectConnectionThroughVisuals(connections, layoutNodes, noteNodes);
        // Off-face parallel-terminal detection: a route that departs an element face then runs
        // parallel to and hugs it. Hoisted above the rating call — it contributes to routingRating
        // (Tier-2R cap-fair, binary presence): any such hug is a plainly-visible defect the
        // visible-length-guarded terminal metric suppresses as a sub-pixel stub, so the headline
        // cannot read good/excellent while the render shows the hug. Disjoint from the terminal-angle
        // and interior-segment metrics by construction. Its descriptions/violatorIds (carrying the
        // layout-bound spacing remedy) are consumed later when the result is assembled.
        OffFaceParallelTerminalResult offFaceParallelResult =
                countOffFaceParallelTerminals(connections, layoutNodes, includeViolatorIds);

        // Rating and suggestions use sibling overlaps only
        // Two-dimensional rating (layout-tier × routing-tier × min combiner).
        // Rating uses cross-element PT count only (self-element PTs don't penalise)
        List<String> offCanvas = detectOffCanvas(layoutNodes);
        RatingResult ratingResult = computeRatingWithBreakdown(
                overlapResult.siblingCount(), crossingCount, avgSpacing, alignment,
                labelResult.count(), passThroughResult.crossElementCount(), coincidentSegmentCount,
                nonOrthogonalTerminalCount, connections.size(), hasGroups,
                boundaryResult.violationCount(), parentLabelResult.count(),
                offCanvas.size(), labelTruncResult.count(),
                interiorResult.count(), zigzagResult.count(),
                edgeCoincidenceResult.count(), hubPortResult.viewAggregate(),
                hubCrowdingResult.crowded(), nonOrthInteriorResult.count(),
                throughVisualResult.count(), offFaceParallelResult.count());
        String rating = ratingResult.rating();
        Map<String, String> ratingBreakdown = ratingResult.breakdown();
        // Built ONCE, here, and read by both consumers below: the prose this call produces and the
        // coverage field the response publishes. Constructing it twice would let the sentence and
        // the map disagree — the sentence is only trustworthy because it is arithmetic over the
        // very map the caller can read back.
        CoverageDeclaration coverageDeclaration = new CoverageDeclaration(
                buildCoverageMap(labelResult.shortSegmentCount() > 0,
                        ownIconOverLabelResult.unmeasuredTitle(),
                        parentLabelResult.unmeasuredParentBand()));
        // The suggestions are built AFTER every detector below has run, not here. They used to be
        // built at this point, which put two thirds of the view's metrics structurally out of reach
        // of the verdict that speaks for all of them. Nothing between here and the call reads
        // `suggestions`, so the move is a relocation of one statement rather than a reordering.

        // Density-aware crossing metric
        double crossingsPerConnection = connections.size() > 0
                ? (double) crossingCount / connections.size() : 0.0;

        // Informational note-overlap detection (notes vs layout nodes)
        NoteOverlapResult noteOverlapResult = countNoteOverlaps(noteNodes, layoutNodes);
        // Informational note-text-clip detection (note content vs box height). No rating impact.
        NoteClipResult noteClipResult = detectNoteTextClipping(noteNodes);
        // Informational label-on-note detection (connection labels rendered on a Note rectangle).
        // No rating impact — kept OUT of countLabelOverlaps (whose count feeds the rating) precisely
        // so it stays informational; notes are excluded from the scoring node set, so this is the
        // only arm that tests a label against a note. Independent of the route-vs-visual counts.
        LabelOnNoteResult labelOnNoteResult = countLabelOnNote(connections, noteNodes, includeViolatorIds);
        // Informational label-on-group detection (connection labels rendered on a visual Group's title
        // band). No rating impact — kept OUT of countLabelOverlaps (whose count feeds the rating, and
        // which skips groups wholesale) precisely so it stays informational; tests only the group's top
        // title strip, so a label inside the group body does NOT flag.
        LabelOnGroupResult labelOnGroupResult = countLabelOnGroup(connections, layoutNodes, includeViolatorIds);
        // Informational redundant-bendpoint detection (collinear, removable points). No rating
        // impact — pure geometry over each connection's bendpoint array, independent of zigzag.
        // Node-aware overload: excludes router-pinned terminal egress-stub ports (a first/last
        // bendpoint on its element's perimeter face) so the count means genuinely-removable interior
        // redundancy, not intentional terminal anchors that no re-route will drop.
        RedundantBendpointResult redundantBendpointResult =
                countRedundantBendpoints(connections, layoutNodes, includeViolatorIds);
        // Backstop for the container-recession emitter: an authored container fill that equals a
        // nested child's fill (the flat-blob the emitter must not touch). No rating impact.
        ContainerFillResult containerFillResult =
                countContainerFillEqualsChild(nodes, includeViolatorIds);
        // Coincident same-face ports — 2+ connection terminals overlapping on one perimeter point.
        // Closes the M5 face-guard blind spot (computeHubPortQuality skips faces below its
        // connection-count guard, so a 2–3-connection coincident face reads a vacuous 1.0).
        // Informational only — never fed into the rating.
        CoincidentFacePortResult coincidentPortResult =
                countCoincidentFacePorts(connections, layoutNodes, includeViolatorIds);

        // Every count-valued metric this run measured, in the order the response publishes them.
        // The verdict at the end of generateSuggestions is answerable for all of them; the list is
        // the one place a metric added later has to be registered, and the parity pin over the
        // result record's own components is what makes forgetting it a red test rather than a
        // silently narrower verdict.
        //
        // Each entry carries TWO names: the metric id the prose prints, and the component of the
        // published result that holds the count. The second is what makes the registry answerable
        // in both directions — the pin resolves every printed id against the published registers,
        // and partitions every count-valued component of the result into "registered here" or
        // "declared not a finding", so a count added to the result and forgotten here is a red
        // test. The two names differ wherever the id is a coverage-dimension id (most of them are)
        // and the field is the result component; they coincide where no dimension id exists.
        MetricFindings metricFindings = new MetricFindings(List.of(
                new MetricFinding("overlaps", "overlapCount", overlapResult.siblingCount()),
                new MetricFinding("cousinOverlaps", "cousinOverlapCount",
                        overlapResult.cousinCount()),
                new MetricFinding("edgeCrossings", "edgeCrossingCount", crossingCount),
                new MetricFinding("boundaryViolations", "boundaryViolationCount",
                        boundaryResult.violationCount()),
                new MetricFinding("labelOverlaps", "labelOverlapCount", labelResult.count()),
                new MetricFinding("noteOverlap", "noteOverlapCount", noteOverlapResult.count()),
                new MetricFinding("noteClip", "noteClipCount", noteClipResult.count()),
                new MetricFinding("coincidentSegments", "coincidentSegmentCount",
                        coincidentSegmentCount),
                new MetricFinding("nonOrthogonalTerminals", "nonOrthogonalTerminalCount",
                        nonOrthogonalTerminalCount),
                new MetricFinding("nonOrthogonalTerminalsZeroBendpoint", "zeroBendpointNonOrthogonalTerminalCount",
                        nonOrthResult.zeroBendpointCount()),
                new MetricFinding("nonOrthogonalTerminalsRouted", "routedNonOrthogonalTerminalCount",
                        nonOrthResult.routedCount()),
                new MetricFinding("labelTruncations", "labelTruncationCount",
                        labelTruncResult.count()),
                new MetricFinding("parentLabelObscured", "parentLabelObscuredCount",
                        parentLabelResult.count()),
                new MetricFinding("imageSiblingOverlap", "imageSiblingOverlapCount",
                        imageSiblingResult.count()),
                new MetricFinding("overlayIconCollision", "overlayIconCollisionCount",
                        overlayIconResult.count()),
                new MetricFinding("ownIconOverLabel", "ownIconOverLabelCount",
                        ownIconOverLabelResult.count()),
                new MetricFinding("interiorTerminations", "interiorTerminationCount",
                        interiorResult.count()),
                new MetricFinding("zigzags", "zigzagCount", zigzagResult.count()),
                new MetricFinding("connectionEdgeCoincidence", "connectionEdgeCoincidenceCount",
                        edgeCoincidenceResult.count()),
                new MetricFinding("edgeCoincidenceGrazedElements", "edgeCoincidenceGrazedElementCount",
                        edgeCoincidenceResult.grazedElementCount()),
                new MetricFinding("anchorDrift", "anchorDriftCount", anchorDriftResult.count()),
                new MetricFinding("lateralJogReversals", "lateralJogReversalCount",
                        lateralJogResult.count()),
                new MetricFinding("vAxisParallelGapNarrow25Count", "vAxisParallelGapNarrow25Count",
                        parallelGapResult.vAxis().narrowGapCount25()),
                new MetricFinding("hAxisParallelGapNarrow25Count", "hAxisParallelGapNarrow25Count",
                        parallelGapResult.hAxis().narrowGapCount25()),
                new MetricFinding("connectionThroughNote", "connectionThroughNoteCount",
                        throughVisualResult.count()),
                new MetricFinding("connectionGrazesVisual", "connectionGrazesVisualCount",
                        throughVisualResult.grazeCount()),
                new MetricFinding("redundantBendpoints", "connectionRedundantBendpointCount",
                        redundantBendpointResult.count()),
                new MetricFinding("nonOrthogonalInteriorSegments", "nonOrthogonalInteriorSegmentCount",
                        nonOrthInteriorResult.count()),
                new MetricFinding("containerFillRecession", "containerFillEqualsChildCount",
                        containerFillResult.count()),
                new MetricFinding("labelOnNote", "labelOnNoteCount", labelOnNoteResult.count()),
                new MetricFinding("labelOnGroup", "labelOnGroupCount", labelOnGroupResult.count()),
                new MetricFinding("offFaceParallelTerminals", "offFaceParallelTerminalCount",
                        offFaceParallelResult.count()),
                new MetricFinding("coincidentFacePorts", "coincidentFacePortCount", coincidentPortResult.count()),
                // Field and count are the SAME measured quantity: crossElementPassThroughCount is
                // the published component carrying exactly the cross-element tally registered
                // beside it, which is the number the rating charges. The field is deliberately not
                // connectionPassThroughs — that list is a capped description of what was seen, it
                // also names the self-element pass-throughs (published for visibility, and
                // deliberately unrated), and its size is therefore neither this count nor reliably
                // related to it in either direction. The distinction still matters downstream: the
                // pass-through remedy below reconciles the charged count against what that list
                // manages to name. The objects behind the charged count are recoverable in full
                // from the passThroughs violator-id key, which is uncapped and cross-element only.
                new MetricFinding("passThroughs", "crossElementPassThroughCount",
                        passThroughResult.crossElementCount())));

        // The metrics that move a rating and, until now, explained none of it. Named, never
        // counted: a tally beside the list it counts goes stale the moment the list grows.
        RatingBearingFindings ratingBearingFindings = new RatingBearingFindings(
                parentLabelResult, labelTruncResult, nonOrthInteriorResult,
                offFaceParallelResult, throughVisualResult, passThroughResult,
                hubCrowdingResult);

        // The thirteen that move no rating and, until now, explained nothing either.
        InformationalFindings informationalFindings = new InformationalFindings(
                overlapResult, noteOverlapResult, noteClipResult, imageSiblingResult,
                overlayIconResult, edgeCoincidenceResult, parallelGapResult, throughVisualResult,
                redundantBendpointResult, containerFillResult, labelOnNoteResult,
                labelOnGroupResult, coincidentPortResult);

        List<String> suggestions = generateSuggestions(
                overlapResult.siblingCount(), crossingCount, avgSpacing, alignment,
                boundaryResult.violationCount(), offCanvas.size(), layoutNodes.size(),
                labelResult.count(), hasGroups, connections.size(), coincidentSegmentCount,
                nonOrthogonalTerminalCount, labelResult.shortSegmentCount(),
                overlapResult.containmentCount(), nonOrthResult.zeroBendpointCount(),
                nonOrthResult.routedCount(),
                interiorResult.count(), zigzagResult.count(),
                edgeCoincidenceResult.count(), hubPortResult.viewAggregate(),
                anchorDriftResult.count(), lateralJogResult.count(),
                ownIconOverLabelResult, coverageDeclaration,
                metricFindings, ratingBearingFindings, informationalFindings,
                ratingBreakdown);

        // Compute bounding box of ALL visual content (elements + groups + notes)
        ContentBounds contentBounds = computeContentBounds(nodes);

        // Build violator IDs map (only when requested, omit empty metrics)
        Map<String, Set<String>> violatorIds = null;
        if (includeViolatorIds) {
            violatorIds = new LinkedHashMap<>();
            if (!overlapResult.violatorIds().isEmpty()) {
                violatorIds.put("overlaps", overlapResult.violatorIds());
            }
            // Without this the cross-branch metric cannot serve its purpose past the description
            // cap: the count is uncapped, so a view with many cross-branch pairs would report a
            // number with no way to enumerate the objects behind it.
            if (!overlapResult.cousinViolatorIds().isEmpty()) {
                violatorIds.put("cousinOverlaps", overlapResult.cousinViolatorIds());
            }
            if (!passThroughResult.violatorIds().isEmpty()) {
                violatorIds.put("passThroughs", passThroughResult.violatorIds());
            }
            // Map coincident connection indices back to IDs
            if (!coincidentResult.violatorConnectionIndices().isEmpty()) {
                Set<String> coincidentIds = new HashSet<>();
                for (int idx : coincidentResult.violatorConnectionIndices()) {
                    if (idx >= 0 && idx < connections.size()) {
                        coincidentIds.add(connections.get(idx).id());
                    }
                }
                if (!coincidentIds.isEmpty()) {
                    violatorIds.put("coincidentSegments", coincidentIds);
                }
            }
            if (!nonOrthResult.violatorIds().isEmpty()) {
                violatorIds.put("nonOrthogonalTerminals", nonOrthResult.violatorIds());
            }
            // The union key above keeps its whole-population meaning; these two name its disjoint
            // halves, so a remedy that applies to only one half can be scoped to just those ids.
            if (!nonOrthResult.zeroBendpointViolatorIds().isEmpty()) {
                violatorIds.put("nonOrthogonalTerminalsZeroBendpoint",
                        nonOrthResult.zeroBendpointViolatorIds());
            }
            if (!nonOrthResult.routedViolatorIds().isEmpty()) {
                violatorIds.put("nonOrthogonalTerminalsRouted", nonOrthResult.routedViolatorIds());
            }
            if (!boundaryResult.violatorIds().isEmpty()) {
                violatorIds.put("boundaryViolations", boundaryResult.violatorIds());
            }
            // M2-M5: violator IDs for the perception-aligned metrics.
            if (!interiorResult.violatorIds().isEmpty()) {
                violatorIds.put("interiorTerminations", interiorResult.violatorIds());
            }
            if (!zigzagResult.violatorIds().isEmpty()) {
                violatorIds.put("zigzags", zigzagResult.violatorIds());
            }
            if (!redundantBendpointResult.violatorIds().isEmpty()) {
                violatorIds.put("redundantBendpoints", redundantBendpointResult.violatorIds());
            }
            if (!anchorDriftResult.violatorIds().isEmpty()) {
                violatorIds.put("anchorDrift", anchorDriftResult.violatorIds());
            }
            if (!lateralJogResult.violatorIds().isEmpty()) {
                violatorIds.put("lateralJogReversals", lateralJogResult.violatorIds());
            }
            if (!nonOrthInteriorResult.violatorIds().isEmpty()) {
                violatorIds.put("nonOrthogonalInteriorSegments", nonOrthInteriorResult.violatorIds());
            }
            if (!containerFillResult.violatorIds().isEmpty()) {
                violatorIds.put("containerFillRecession", containerFillResult.violatorIds());
            }
            // Label-on-note violators are the note ids carrying a connection label (informational).
            if (!labelOnNoteResult.violatorIds().isEmpty()) {
                violatorIds.put("labelOnNote", labelOnNoteResult.violatorIds());
            }
            // Label-on-group violators are the group ids whose title band carries a connection label.
            if (!labelOnGroupResult.violatorIds().isEmpty()) {
                violatorIds.put("labelOnGroup", labelOnGroupResult.violatorIds());
            }
            if (!edgeCoincidenceResult.violatorIds().isEmpty()) {
                violatorIds.put("edgeCoincidence", edgeCoincidenceResult.violatorIds());
            }
            // The grazed ELEMENT ids (every element a route hugs) — distinct from the connection-id
            // "edgeCoincidence" key above. Surfaces the full breadth of a multi-element graze.
            if (!edgeCoincidenceResult.grazedElementIds().isEmpty()) {
                violatorIds.put("edgeCoincidenceGrazedElements",
                        edgeCoincidenceResult.grazedElementIds());
            }
            if (!hubPortResult.lowQualityElementIds().isEmpty()) {
                violatorIds.put("hubPortLowQuality", hubPortResult.lowQualityElementIds());
            }
            // parallelConnectionGap (per-axis V/H violator surfaces).
            if (!parallelGapResult.vAxis().violatorIds().isEmpty()) {
                violatorIds.put("parallelConnectionGapV", parallelGapResult.vAxis().violatorIds());
            }
            if (!parallelGapResult.hAxis().violatorIds().isEmpty()) {
                violatorIds.put("parallelConnectionGapH", parallelGapResult.hAxis().violatorIds());
            }
            // Off-face parallel-terminal violators are the connection ids hugging a departed face.
            if (!offFaceParallelResult.violatorIds().isEmpty()) {
                violatorIds.put("offFaceParallelTerminals", offFaceParallelResult.violatorIds());
            }
            // Coincident-face-port violators are the connection ids sharing a perimeter point.
            if (!coincidentPortResult.violatorIds().isEmpty()) {
                violatorIds.put("coincidentFacePorts", coincidentPortResult.violatorIds());
            }
            if (violatorIds.isEmpty()) {
                violatorIds = null;
            }
        }

        // Orphan detection is done at EMF level in ArchiModelAccessorImpl, not here.
        // Pass 0/empty — the accessor merges orphan data into the DTO directly.
        return new LayoutAssessmentResult(
                overlapResult.siblingCount(), overlapResult.containmentCount(),
                crossingCount, avgSpacing, alignment, rating, ratingBreakdown,
                overlapResult.siblingDescriptions(), boundaryResult.descriptions(),
                passThroughResult.descriptions(),
                offCanvas, labelResult.count(), labelResult.descriptions(),
                0, List.of(), connections.size(), crossingsPerConnection,
                noteOverlapResult.count(), noteOverlapResult.descriptions(),
                noteClipResult.count(), noteClipResult.descriptions(),
                hasGroups, coincidentSegmentCount, nonOrthogonalTerminalCount,
                contentBounds,
                labelTruncResult.count(), labelTruncResult.descriptions(),
                parentLabelResult.count(), parentLabelResult.descriptions(),
                imageSiblingResult.count(), imageSiblingResult.descriptions(),
                overlayIconResult.count(), overlayIconResult.descriptions(),
                violatorIds,
                suggestions,
                // M2-M6 (appended to the record tail; backwards-compat)
                interiorResult.count(), interiorResult.descriptions(),
                zigzagResult.count(), zigzagResult.descriptions(),
                edgeCoincidenceResult.count(), edgeCoincidenceResult.descriptions(),
                hubPortResult.viewAggregate(),
                includeViolatorIds ? hubPortResult.perFaceDetails() : List.of(),
                ratingResult.layoutRating(), ratingResult.routingRating(),
                // R8 Corridor Utilisation (2026-05-03)
                corridorUtilisationResult.viewAggregate(),
                corridorUtilisationResult.perChannelDetails(),
                // parallelConnectionGap (2026-05-12)
                parallelGapResult.vAxis().p10(),
                parallelGapResult.vAxis().narrowGapCount25(),
                parallelGapResult.hAxis().narrowGapCount25(),
                includeViolatorIds ? buildParallelGapDetail(parallelGapResult) : null,
                // Hub-to-neighbour crowding clearance (2026-06-25)
                hubCrowdingResult.minClearance(),
                // Coverage declaration — each dimension reports its declared level: "checked"
                // (fully covered), "partial" (some failure modes uncovered), or "not-checked".
                // labelOverlaps downgrades to "partial" on a run carrying a label wider than its
                // hosting segment (the overlap count cannot certify that crowding mode clean).
                // A READ of the value hoisted above, not a second construction: the suggestion
                // prose quotes figures counted off this same map.
                coverageDeclaration.coverage(),
                // Connection-through-note/image (count drives routing Tier-3R cap-good; descriptions are output)
                throughVisualResult.count(), throughVisualResult.descriptions(),
                // Redundant (collinear / removable) bendpoints (informational; no rating impact)
                redundantBendpointResult.count(), redundantBendpointResult.descriptions(),
                // Non-orthogonal interior (mid) segments (informational; no rating impact)
                nonOrthInteriorResult.count(), nonOrthInteriorResult.descriptions(),
                // Container fill == nested-child fill — emitter backstop (informational; no rating impact)
                containerFillResult.count(), containerFillResult.descriptions(),
                // Connection grazing a note/image border (informational; no rating impact; disjoint
                // from the interior-penetration connectionThroughNote count above)
                throughVisualResult.grazeCount(), throughVisualResult.grazeDescriptions(),
                // Connection labels rendered on a Note rectangle (informational; no rating impact;
                // independent of the route-vs-visual counts — a label is positioned off the line)
                labelOnNoteResult.count(), labelOnNoteResult.descriptions(),
                // Connection labels rendered on a visual Group's title band (informational; no rating
                // impact; the title-band-only test leaves body labels alone)
                labelOnGroupResult.count(), labelOnGroupResult.descriptions(),
                // Per-element edge-coincidence enumeration (informational; no rating impact — the
                // rating-bearing tally is connectionEdgeCoincidenceCount above)
                edgeCoincidenceResult.grazedElementCount(),
                // Off-face parallel-terminal hugs (informational; no rating impact — the rating-bearing
                // nonOrthogonalTerminalCount above is unchanged; this is the route-hugs-departed-face mode)
                offFaceParallelResult.count(), offFaceParallelResult.descriptions(),
                // Coincident same-face ports (informational; no rating impact — the rating-bearing
                // hubPortQualityScore/M5 is unchanged; this enumerates faces below M5's connection guard)
                coincidentPortResult.count(), coincidentPortResult.descriptions(),
                // An element's own icon over its own title (informational; no rating impact —
                // the two icon counts above compare an icon against OTHER geometry and cannot see it)
                ownIconOverLabelResult.count(), ownIconOverLabelResult.descriptions(),
                // Cross-branch overlaps (informational; no rating impact — the rating-bearing
                // overlapCount above reports this pair's ancestors, never the pair itself)
                overlapResult.cousinCount(), overlapResult.cousinDescriptions(),
                // TRUE uncapped boundary-violation count (the description list beside it is capped)
                boundaryResult.violationCount(),
                // Anchor drift (informational; no rating impact — the disagreement is measured in the
                // collector, before the midpoint blend that hides it from every dimension above)
                anchorDriftResult.count(), anchorDriftResult.descriptions(),
                // Lateral-jog reversals (informational; no rating impact — the four-point shape the
                // three-point zigzagCount predicate above structurally cannot express)
                lateralJogResult.count(), lateralJogResult.descriptions(),
                // The two measured halves of nonOrthogonalTerminalCount above (which is unchanged).
                nonOrthResult.zeroBendpointCount(), nonOrthResult.routedCount(),
                // The charged pass-through count — the same reading computeRatingWithBreakdown was
                // given above, so the number published and the number rated cannot diverge.
                passThroughResult.crossElementCount());
    }

    /**
     * Converts the internal {@link ParallelConnectionGapResult} to the public
     * {@link LayoutAssessmentResult.ParallelConnectionGapDetail} record (per-axis,
     * without the violator-id sets — those are surfaced in the result's top-level
     * {@code violatorIds} map).
     */
    private LayoutAssessmentResult.ParallelConnectionGapDetail buildParallelGapDetail(
            ParallelConnectionGapResult r) {
        return new LayoutAssessmentResult.ParallelConnectionGapDetail(
                toAxisDetail(r.vAxis()), toAxisDetail(r.hAxis()));
    }

    private LayoutAssessmentResult.ParallelConnectionGapAxisDetail toAxisDetail(
            ParallelConnectionGapAxis a) {
        return new LayoutAssessmentResult.ParallelConnectionGapAxisDetail(
                a.qualifyingSegmentCount(), a.mean(), a.min(), a.p10(),
                a.narrowGapCount15(), a.narrowGapCount25(), a.narrowGapCount40());
    }

    // ---- Containment relationship helpers (transitive closure) ----

    /**
     * Builds a set of ALL ancestor-descendant pairs (transitive closure) for
     * fast containment lookup. For each node, walks up the parentId chain and
     * adds pairs for EVERY ancestor, not just the direct parent.
     *
     * <p>Example: TopGroup → SubGroup → Element produces pairs:
     * "TopGroup:SubGroup", "SubGroup:Element", AND "TopGroup:Element".</p>
     */
    private Set<String> buildContainmentPairs(List<AssessmentNode> nodes) {
        Map<String, AssessmentNode> nodeMap = new HashMap<>();
        for (AssessmentNode node : nodes) {
            nodeMap.put(node.id(), node);
        }

        Set<String> pairs = new HashSet<>();
        for (AssessmentNode node : nodes) {
            if (node.parentId() != null) {
                // Walk up the ancestor chain and add ALL ancestor:descendant pairs
                String descendantId = node.id();
                AssessmentNode current = nodeMap.get(node.parentId());
                // EMF containment is a real tree, so a parent cycle cannot occur on the live
                // path. This walker is also reachable through the package-visible assess seam
                // with hand-built parent ids, where a self-parent or a mutual pair would spin
                // forever without allocating — a silent hang with no failure and no diagnostic.
                Set<String> seen = new HashSet<>();
                while (current != null && seen.add(current.id())) {
                    pairs.add(current.id() + ":" + descendantId);
                    if (current.parentId() == null) break;
                    current = nodeMap.get(current.parentId());
                }
            }
        }
        return pairs;
    }

    private boolean isContainmentPair(AssessmentNode a, AssessmentNode b,
                                       Set<String> containmentPairs) {
        return containmentPairs.contains(a.id() + ":" + b.id())
                || containmentPairs.contains(b.id() + ":" + a.id());
    }

    /**
     * Collects all descendant IDs for a given node (children, grandchildren, etc.).
     *
     * <p>Delegates to {@link RoutingExcludeSets#descendantIds}, which is this walk — plus the
     * geometric filter that drops a descendant dragged clear of the node it is nested under. Two
     * definitions of "descendants" in one package is what let the routing exclusion sets stop at
     * direct children while this class already walked the whole subtree — a disagreement nobody saw
     * because nothing compared them. One definition cannot disagree with itself.</p>
     */
    private Set<String> getDescendantIds(String nodeId, List<AssessmentNode> nodes) {
        return RoutingExcludeSets.descendantIds(nodeId, nodes);
    }

    /**
     * Collects all ancestor IDs for a given node by walking the parentId chain.
     *
     * <p>Delegates to {@link RoutingExcludeSets#ancestorIds}, which is this walk — cycle guard and
     * all — plus the geometric filter that stops a "parent" dragged clear of its child from being
     * treated as a box the child's connections must cross. Two definitions of "ancestors" in one
     * package is the same shape as the descendant fork one class over: it survived only for as long
     * as nothing compared them, and the two disagreed about termination the whole time. One
     * definition cannot disagree with itself.</p>
     */
    private Set<String> getAncestorIds(String nodeId,
                                        Map<String, AssessmentNode> nodeMap) {
        return RoutingExcludeSets.ancestorIds(nodeId, nodeMap);
    }

    // ---- Overlap Detection (Finding #2: exclude containment, #10: single pass, transitive) ----

    /**
     * Combined sibling + containment + cross-branch counts and descriptions from a single pass.
     *
     * <p>{@code cousinCount} is informational only. It never feeds the rating or the tiers — it
     * exists so the colliding objects themselves can be named, which the sibling arm cannot do
     * when the overlap crosses a container boundary. It counts EVERY such pair, including pairs
     * where one side is a container, so it is a list of pairs to inspect rather than a count of
     * distinct visible collisions.</p>
     */
    record OverlapResult(int siblingCount, int containmentCount,
                         List<String> siblingDescriptions, Set<String> violatorIds,
                         int cousinCount, List<String> cousinDescriptions,
                         Set<String> cousinViolatorIds) {}

    OverlapResult computeOverlaps(List<AssessmentNode> nodes,
                                   Set<String> containmentPairs,
                                   boolean collectViolatorIds) {
        int siblingCount = 0;
        int containmentCount = 0;
        int cousinCount = 0;
        List<String> siblingDescriptions = new ArrayList<>();
        List<String> cousinDescriptions = new ArrayList<>();
        Set<String> violatorIds = collectViolatorIds ? new HashSet<>() : Set.of();
        Set<String> cousinViolatorIds = collectViolatorIds ? new HashSet<>() : Set.of();
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                AssessmentNode a = nodes.get(i);
                AssessmentNode b = nodes.get(j);
                if (!rectanglesOverlap(a, b)) {
                    continue;
                }
                // Containment pairs overlap by design — count separately
                if (isContainmentPair(a, b, containmentPairs)) {
                    containmentCount++;
                } else if (Objects.equals(a.parentId(), b.parentId())) {
                    // Count only SAME-PARENT overlaps. Two top-level objects both carry a null
                    // parentId, and Objects.equals(null, null) is true, so they are siblings of
                    // each other here.
                    //
                    // The cross-branch case — different parents, no ancestor relationship — is
                    // excluded DELIBERATELY AND PERMANENTLY, not for want of a check, and it is
                    // never thereby hidden. If every child sits inside its parent, then two
                    // overlapping cross-branch objects force the ancestors that are children of
                    // their lowest common ancestor to overlap as well; those ancestors share a
                    // parent, so this arm fires on THEM. If instead a child escaped its parent,
                    // detectBoundaryViolations reports the escape, in the same uncapped severity
                    // band. Counting the cross-branch pair here would therefore charge an
                    // already-reported defect a second time and move ratings model-wide.
                    //
                    // What the exclusion does cost is ATTRIBUTION: the pair named here is the
                    // ancestor pair, one or more levels up from what a reader sees colliding.
                    // The cousin arm below closes that gap by naming the colliding objects
                    // themselves among its pairs, without touching this count.
                    siblingCount++;
                    if (siblingDescriptions.size() < MAX_DESCRIPTIONS) {
                        siblingDescriptions.add("Element '" + a.id()
                                + "' overlaps with element '" + b.id() + "'");
                    }
                    if (collectViolatorIds) {
                        violatorIds.add(a.id());
                        violatorIds.add(b.id());
                    }
                } else {
                    // Cross-branch overlap. Informational: named, never rated.
                    cousinCount++;
                    if (collectViolatorIds) {
                        cousinViolatorIds.add(a.id());
                        cousinViolatorIds.add(b.id());
                    }
                    if (cousinDescriptions.size() < MAX_DESCRIPTIONS) {
                        // Either side may be a container, so name each by what it is — the same
                        // reason the boundary description below distinguishes group from element.
                        cousinDescriptions.add((a.isGroup() ? "Group '" : "Element '") + a.id()
                                + "' overlaps with " + (b.isGroup() ? "group '" : "element '")
                                + b.id()
                                + "' across a container boundary (different parents, neither is"
                                + " an ancestor of the other)");
                    }
                }
            }
        }
        return new OverlapResult(siblingCount, containmentCount, siblingDescriptions, violatorIds,
                cousinCount, cousinDescriptions, cousinViolatorIds);
    }

    private boolean rectanglesOverlap(AssessmentNode a, AssessmentNode b) {
        return a.x() < b.x() + b.width()
                && a.x() + a.width() > b.x()
                && a.y() < b.y() + b.height()
                && a.y() + a.height() > b.y();
    }

    // ---- Edge Crossing Detection (Finding #8: remove sharesEndpoint skip) ----

    int countEdgeCrossings(List<AssessmentConnection> connections) {
        List<List<double[]>> paths = new ArrayList<>(connections.size());
        for (AssessmentConnection conn : connections) {
            paths.add(conn.pathPoints());
        }
        return countPathCrossings(paths);
    }

    /**
     * Counts edge crossings among a list of raw path point lists.
     * Each path is a list of [x, y] points (source center → bendpoints → target center).
     * Package-visible for use by autoRouteConnections crossing delta.
     */
    static int countPathCrossings(List<List<double[]>> paths) {
        int count = 0;
        for (int i = 0; i < paths.size(); i++) {
            for (int j = i + 1; j < paths.size(); j++) {
                count += countSegmentCrossings(paths.get(i), paths.get(j));
            }
        }
        return count;
    }

    static int countSegmentCrossings(List<double[]> path1, List<double[]> path2) {
        int count = 0;
        for (int i = 0; i < path1.size() - 1; i++) {
            for (int j = 0; j < path2.size() - 1; j++) {
                if (segmentsIntersect(
                        path1.get(i)[0], path1.get(i)[1],
                        path1.get(i + 1)[0], path1.get(i + 1)[1],
                        path2.get(j)[0], path2.get(j)[1],
                        path2.get(j + 1)[0], path2.get(j + 1)[1])) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Line segment intersection test using cross-product orientation.
     * Returns true if segment P1-P2 properly intersects segment P3-P4.
     * Segments sharing an endpoint (P1=P3, etc.) produce a zero cross-product
     * and correctly return false.
     */
    static boolean segmentsIntersect(double p1x, double p1y, double p2x, double p2y,
                                      double p3x, double p3y, double p4x, double p4y) {
        double d1 = crossProduct(p3x, p3y, p4x, p4y, p1x, p1y);
        double d2 = crossProduct(p3x, p3y, p4x, p4y, p2x, p2y);
        double d3 = crossProduct(p1x, p1y, p2x, p2y, p3x, p3y);
        double d4 = crossProduct(p1x, p1y, p2x, p2y, p4x, p4y);

        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
                && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }

        // Collinear cases — not counted as crossings
        return false;
    }

    /**
     * Cross product of vectors (bx-ax, by-ay) and (cx-ax, cy-ay).
     */
    private static double crossProduct(double ax, double ay, double bx, double by,
                                        double cx, double cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }

    // ---- Average Spacing (Finding #4: exclude containment pairs) ----

    double computeAverageSpacing(List<AssessmentNode> nodes,
                                  Set<String> containmentPairs) {
        if (nodes.size() < 2) {
            return 0.0;
        }

        double totalMinGap = 0.0;
        int counted = 0;
        for (int i = 0; i < nodes.size(); i++) {
            double minGap = Double.MAX_VALUE;
            boolean hasNeighbor = false;
            for (int j = 0; j < nodes.size(); j++) {
                if (i == j) continue;
                // Skip containment pairs for spacing computation
                if (isContainmentPair(nodes.get(i), nodes.get(j), containmentPairs)) {
                    continue;
                }
                double gap = edgeToEdgeDistance(nodes.get(i), nodes.get(j));
                if (gap < minGap) {
                    minGap = gap;
                    hasNeighbor = true;
                }
            }
            if (hasNeighbor) {
                totalMinGap += minGap;
                counted++;
            }
        }
        return counted > 0 ? totalMinGap / counted : 0.0;
    }

    /**
     * Computes the minimum edge-to-edge distance between two axis-aligned rectangles.
     * Returns 0 if they overlap.
     */
    private double edgeToEdgeDistance(AssessmentNode a, AssessmentNode b) {
        double dx = Math.max(0, Math.max(b.x() - (a.x() + a.width()),
                a.x() - (b.x() + b.width())));
        double dy = Math.max(0, Math.max(b.y() - (a.y() + a.height()),
                a.y() - (b.y() + b.height())));

        if (dx == 0 && dy == 0) {
            return 0; // overlapping
        }
        return Math.sqrt(dx * dx + dy * dy);
    }

    // ---- Alignment Score (Finding #9: exclude groups, #12: 0 for empty) ----

    int computeAlignmentScore(List<AssessmentNode> nodes) {
        // Filter to non-container (leaf) elements only for alignment scoring. A zone's own edges
        // are placed by the arrangement, not by the author aligning peers, so scoring them as
        // alignment participants measures the arranger rather than the layout.
        List<AssessmentNode> leafNodes = new ArrayList<>();
        for (AssessmentNode node : nodes) {
            if (!node.isContainer()) {
                leafNodes.add(node);
            }
        }

        // Finding #12: return 0 for empty/single — no alignment data, not "perfect"
        if (leafNodes.size() < 2) {
            return 0;
        }

        Set<String> alignedElements = new HashSet<>();

        // Check left-edge alignment
        findAlignedGroups(leafNodes, n -> n.x(), alignedElements);
        // Check center-x alignment
        findAlignedGroups(leafNodes, n -> n.x() + n.width() / 2, alignedElements);
        // Check top-edge alignment
        findAlignedGroups(leafNodes, n -> n.y(), alignedElements);
        // Check center-y alignment
        findAlignedGroups(leafNodes, n -> n.y() + n.height() / 2, alignedElements);

        return Math.min(100, (int) ((alignedElements.size() * 100.0) / leafNodes.size()));
    }

    @FunctionalInterface
    interface CoordinateExtractor {
        double extract(AssessmentNode node);
    }

    private void findAlignedGroups(List<AssessmentNode> nodes,
                                    CoordinateExtractor extractor,
                                    Set<String> alignedElements) {
        for (int i = 0; i < nodes.size(); i++) {
            double coord = extractor.extract(nodes.get(i));
            for (int j = i + 1; j < nodes.size(); j++) {
                double otherCoord = extractor.extract(nodes.get(j));
                if (Math.abs(coord - otherCoord) <= ALIGNMENT_TOLERANCE) {
                    alignedElements.add(nodes.get(i).id());
                    alignedElements.add(nodes.get(j).id());
                }
            }
        }
    }

    // ---- Rating Comparison Utilities ----

    /**
     * Returns the ordinal value of a rating for comparison purposes.
     * Higher is better: excellent=4, good=3, fair=2, poor=1, not-applicable=0.
     */
    static int ratingOrdinal(String rating) {
        return switch (rating) {
            case "excellent" -> 4;
            case "good" -> 3;
            case "fair" -> 2;
            case "poor" -> 1;
            default -> 0; // "not-applicable" or unknown
        };
    }

    /**
     * Returns true if the achieved rating meets or exceeds the target rating.
     */
    static boolean meetsTarget(String achieved, String target) {
        return ratingOrdinal(achieved) >= ratingOrdinal(target);
    }

    // ---- Overall Rating (Finding #11: named constants; M6 two-dimensional rating) ----

    /**
     * Result of rating computation including per-metric breakdown and
     * the two-dimensional layout/routing decomposition (M6).
     *
     * <p>Under M6, {@code rating} is the worse of {@code layoutRating} and {@code routingRating}
     * ("min" in human terms — `excellent < good < fair < poor` — i.e. worse-dimension dominates).</p>
     */
    record RatingResult(String rating, Map<String, String> breakdown,
                         String layoutRating, String routingRating) {}

    /**
     * Coverage value: the detector ran and fully covers this dimension's failure-mode space.
     * {@code checked} with a zero/absent metric means genuinely clean — deliberately distinct
     * from {@link #COVERAGE_NOT_CHECKED}. Contrast {@link #COVERAGE_PARTIAL} (a detector ran but
     * covers only some failure modes).
     */
    static final String COVERAGE_CHECKED = "checked";
    /**
     * Coverage value: this defect dimension was NOT evaluated this run — there is no
     * detector for it yet. Absence of a finding for such a dimension is NOT evidence of
     * absence; a consumer must treat it as "unknown", never as "clean".
     */
    static final String COVERAGE_NOT_CHECKED = "not-checked";
    /**
     * Coverage value: the detector for this dimension ran and covers <em>some</em> of its
     * failure modes but not all. A zero/absent metric here means only that the <em>checked</em>
     * modes are clean — the uncovered modes must be render-verified before the dimension can be
     * certified clean. Distinct from {@link #COVERAGE_CHECKED} (complete coverage of the
     * dimension's failure-mode space) and {@link #COVERAGE_NOT_CHECKED} (no detector at all).
     */
    static final String COVERAGE_PARTIAL = "partial";
    /**
     * Coverage value: the view structurally cannot exhibit this dimension, so there is nothing
     * for a detector to find and no render-verification to do. Stronger than
     * {@link #COVERAGE_NOT_CHECKED} and therefore only ever claimed where the failure mode is
     * genuinely unreachable — claiming it for a mode that IS reachable would be a false
     * all-clear, the one error this whole map exists to prevent.
     *
     * <p>Emitted by {@link #buildDegenerateCoverageMap(int)} for a view holding at most one
     * object: every dimension on a zero-object view, and on a one-object view those whose
     * failure mode requires two or more view objects. On the fully-assessed path implemented
     * detectors run unconditionally and honestly report {@code checked} (ran, found nothing)
     * instead.</p>
     */
    static final String COVERAGE_NOT_APPLICABLE = "not-applicable";

    /**
     * The run-scoped condition under which a dimension's declared coverage level is downgraded to
     * {@link #COVERAGE_PARTIAL} — the distinction {@code docs/glossary.md} publishes as
     * <em>contextual</em> {@code partial} versus <em>permanent</em> {@code partial}.
     *
     * <p>A PERMANENT {@code partial} is declared on the dimension itself and reported on every run,
     * because the detector's blind spot is unconditional; it is a property of the code and is the
     * same on every view. A CONTEXTUAL one is declared {@code checked} and downgraded only on a run
     * that actually triggered it, so it is a property of THIS run — which is what makes it the
     * news a consumer has to act on, and the constant is not.</p>
     *
     * <p>Declared here, on the dimension itself, rather than as a branch inside
     * {@link #buildCoverageMap} — the same reason
     * {@link CoverageDimension#degenerateCoverage} is declared at its definition site: a dimension
     * added later cannot compile without stating its trigger, so it cannot silently inherit
     * {@link #NONE} and report a certified {@code checked} for a mode nothing examined. That is the
     * false all-clear this whole map exists to prevent, and defaulting is exactly how it would
     * return.</p>
     *
     * <p>The reason text is the prose an agent reads. It is carried here, beside the trigger, so
     * the map and the sentence explaining it cannot drift apart.</p>
     */
    enum ContextualTrigger {
        /** No run-scoped downgrade: the declared level is final on every run. */
        NONE(null),
        /**
         * This run carries a connection label wider than the segment hosting it. Such a label can
         * crowd a neighbouring box while still clearing it geometrically, so an overlap count of
         * zero does not certify that crowding mode clean.
         */
        LABEL_EXCEEDS_SEGMENT("a connection label is wider than the segment hosting it, so a label"
                + " can crowd a neighbouring box while still clearing it geometrically"),
        /**
         * This run carries a named, icon-bearing object whose title width could not be measured —
         * a native group, which is never measured, or a Grouping or element whose text measurement
         * failed. There was no title rectangle to test the icon against.
         */
        UNMEASURED_TITLE("a named, icon-bearing object's title width could not be measured, so its"
                + " icon was never compared against a title rectangle"),
        /**
         * This run carries a parent, with children, whose title band width was never measured. Its
         * band was sized as a single line however long the title is, so a title that wraps is
         * compared against only its first row.
         */
        UNMEASURED_PARENT_BAND("a parent holding children had its title band width left unmeasured,"
                + " so its clearance verdict was answered against a single-line band");

        /**
         * Why the dimension could not be certified on a run that fired this trigger, phrased as a
         * clause for the caller's prose. Null on {@link #NONE}, which never downgrades anything and
         * therefore has nothing to explain.
         */
        final String reason;

        ContextualTrigger(String reason) {
            this.reason = reason;
        }
    }

    /**
     * Canonical, ordered registry of every layout/routing defect dimension {@code assess-layout}
     * aspires to cover — including dimensions not yet implemented. This is the authority that
     * drives the {@code coverage} map: it makes silent blind spots impossible by forcing every
     * dimension to declare whether it was actually evaluated.
     *
     * <p>Each entry declares its own coverage level — one of {@link #COVERAGE_CHECKED} (the
     * detector fully covers this dimension's failure-mode space; {@code checked + zero findings}
     * is deliberately distinct from {@code not-checked}), {@link #COVERAGE_PARTIAL} (a detector
     * exists but covers only some failure modes — the rest must be render-verified), or
     * {@link #COVERAGE_NOT_CHECKED} (no detector at all). The declared level is the BASELINE:
     * {@link #buildCoverageMap(boolean, boolean, boolean)} emits it verbatim unless it contextually downgrades a
     * dimension for the current run (e.g. {@code labelOverlaps} → {@code partial} when a label
     * exceeds its hosting segment). To add a partially-covered dimension later and then close it,
     * flip that entry to {@code COVERAGE_CHECKED} once its gaps are covered.</p>
     *
     * <p>These ids are the coverage namespace and are intentionally independent of the
     * {@code ratingBreakdown} keys (which answer "how did it score?" not "did we look?").</p>
     */
    enum CoverageDimension {
        OVERLAPS("overlaps", COVERAGE_CHECKED, COVERAGE_NOT_APPLICABLE,
                ContextualTrigger.NONE),
        CONTAINMENT_OVERLAPS("containmentOverlaps", COVERAGE_CHECKED, COVERAGE_NOT_APPLICABLE,
                ContextualTrigger.NONE),
        COUSIN_OVERLAPS("cousinOverlaps", COVERAGE_CHECKED, COVERAGE_NOT_APPLICABLE,
                ContextualTrigger.NONE),
        EDGE_CROSSINGS("edgeCrossings", COVERAGE_CHECKED, COVERAGE_NOT_CHECKED,
                ContextualTrigger.NONE),
        SPACING("spacing", COVERAGE_CHECKED, COVERAGE_NOT_APPLICABLE,
                ContextualTrigger.NONE),
        ALIGNMENT("alignment", COVERAGE_CHECKED, COVERAGE_NOT_APPLICABLE,
                ContextualTrigger.NONE),
        // Fully covered: label-vs-element and label-vs-label overlaps, plus the own-endpoint pass (a
        // label rendered on its own source/target box) — including the wide-label-on-short-segment
        // case, where a label wider than its hosting segment drapes both endpoint boxes at a per-box
        // fraction below the base bar yet is still caught via the promoted
        // LABEL_OWN_ENDPOINT_SHORT_SEGMENT_OVERLAP_FRACTION. A label over a CONTAINER — a native
        // group or an ArchiMate Grouping alike — is covered by the separate labelOnGroup dimension
        // below (this detector intentionally skips container hosts, which a label may legitimately
        // sit within). Both detectors test the same predicate, so exactly one of them owns any
        // given (label, container) pair; when they disagreed, one label over one zone was a false
        // positive here and a false negative there at the same time. The declared level is the baseline: at runtime this
        // dimension is DOWNGRADED to "partial" on a run carrying a label wider than its hosting
        // segment (see buildCoverageMap), because such a label can crowd a neighbour while clearing
        // it geometrically — an overlap count of zero cannot certify that mode clean.
        LABEL_OVERLAPS("labelOverlaps", COVERAGE_CHECKED, COVERAGE_NOT_CHECKED,
                ContextualTrigger.LABEL_EXCEEDS_SEGMENT),
        // A connection's label rendered ON a Note rectangle (informational; no rating impact). A
        // separate concern from labelOverlaps (label vs non-note element / other label) and from the
        // route-vs-visual connectionThroughNote/connectionGrazesVisual dimensions: a label is
        // positioned independently of the line, so the route detectors cannot see it. Fully covered.
        LABEL_ON_NOTE("labelOnNote", COVERAGE_CHECKED, COVERAGE_NOT_CHECKED,
                ContextualTrigger.NONE),
        // A connection's label rendered on a CONTAINER's TITLE BAND (informational; no rating
        // impact) — a native group or an ArchiMate Grouping, which renders as the same transparent
        // labelled box. The label-vs-element detector (labelOverlaps) skips containers wholesale,
        // hiding this title collision; this dimension tests the container's top title strip only (a
        // label in the body is normal). Fully covered — and "fully" is load-bearing: while this
        // detector asked only whether the host was a native group, a Grouping's title band was
        // never examined and the dimension reported a certified zero for a case it never looked at.
        LABEL_ON_GROUP("labelOnGroup", COVERAGE_CHECKED, COVERAGE_NOT_CHECKED,
                ContextualTrigger.NONE),
        // Partial by construction, two layers deep: AssessmentCollector guards its label
        // measurement with `!isGroup && !isNote`, so a visual group's title width is never measured
        // and keeps its 0.0 initialiser; detectLabelTruncation then discards the node at
        // `node.isGroup()` — the first clause of its entry guard — before any width, box or wrap
        // arithmetic runs. A separate width test a few lines later, `textWidth <= 0`, discards a
        // normal element whose measurement failed, so two distinct unmeasured modes arrive as one
        // indistinguishable sentinel. A zero therefore certifies only that every MEASURED element label fits its box;
        // it says nothing about a group's title or an unmeasured width, both of which must be
        // render-verified. This is a permanent declaration rather than a contextual downgrade
        // because both skips are unconditional — there is no run on which a group's title mode is
        // covered. Flip to COVERAGE_CHECKED only when a detector actually measures group titles.
        LABEL_TRUNCATIONS("labelTruncations", COVERAGE_PARTIAL, COVERAGE_NOT_CHECKED,
                ContextualTrigger.NONE),
        // The declared level is the baseline: at runtime this dimension is DOWNGRADED to "partial"
        // on a run carrying a parent, with children, whose label width was never measured — a
        // visual group (excluded from measurement outright) or an element whose text measurement
        // failed, which arrive as the same zero width. Such a parent's title band is sized as a
        // single line however long its title is, so a title that wraps is compared against only its
        // first row and a child sitting under the remaining rows is not flagged. Note this detector
        // does NOT skip those parents the way the truncation detector skips groups: it examines
        // them and returns a verdict, so the downgrade is what stops that verdict reading as
        // certified. The downgrade is contextual rather than declared because a run whose parents
        // were all measured genuinely is fully covered.
        PARENT_LABEL_OBSCURED("parentLabelObscured", COVERAGE_CHECKED, COVERAGE_NOT_APPLICABLE,
                ContextualTrigger.UNMEASURED_PARENT_BAND),
        BOUNDARY_VIOLATIONS("boundaryViolations", COVERAGE_CHECKED, COVERAGE_NOT_APPLICABLE,
                ContextualTrigger.NONE),
        OFF_CANVAS("offCanvas", COVERAGE_CHECKED, COVERAGE_NOT_CHECKED,
                ContextualTrigger.NONE),
        CONNECTION_PASS_THROUGHS("connectionPassThroughs", COVERAGE_CHECKED, COVERAGE_NOT_CHECKED,
                ContextualTrigger.NONE),
        COINCIDENT_SEGMENTS("coincidentSegments", COVERAGE_CHECKED, COVERAGE_NOT_CHECKED,
                ContextualTrigger.NONE),
        NON_ORTHOGONAL_TERMINALS("nonOrthogonalTerminals", COVERAGE_CHECKED, COVERAGE_NOT_CHECKED,
                ContextualTrigger.NONE),
        INTERIOR_TERMINATIONS("interiorTerminations", COVERAGE_CHECKED, COVERAGE_NOT_CHECKED,
                ContextualTrigger.NONE),
        ZIGZAGS("zigzags", COVERAGE_CHECKED, COVERAGE_NOT_CHECKED,
                ContextualTrigger.NONE),
        // Partial by construction: countConnectionEdgeCoincidence classifies each segment as
        // horizontal or vertical and skips everything else outright, so a DIAGONAL segment is never
        // compared against any element edge — it returns before an element is consulted. The
        // examined modes are further bounded to an EDGE_COINCIDENCE_TOLERANCE_PX band. A zero
        // therefore certifies only that no axis-aligned segment hugs within that band; it says
        // nothing about the diagonal mode, which must be render-verified. This is a permanent
        // declaration rather than a contextual downgrade because the skip is unconditional — there
        // is no run on which the diagonal mode is covered. Flip to COVERAGE_CHECKED only when a
        // detector actually examines non-axis-aligned segments.
        EDGE_COINCIDENCE("edgeCoincidence", COVERAGE_PARTIAL, COVERAGE_NOT_CHECKED,
                ContextualTrigger.NONE),
        HUB_PORT_QUALITY("hubPortQuality", COVERAGE_CHECKED, COVERAGE_NOT_CHECKED,
                ContextualTrigger.NONE),
        CORRIDOR_UTILISATION("corridorUtilisation", COVERAGE_CHECKED, COVERAGE_NOT_APPLICABLE,
                ContextualTrigger.NONE),
        // Whether a single connection route sits centred within its corridor band versus hugs one
        // edge. The corridorUtilisation metric above measures multi-occupant occupancy/spread (how
        // widely two or more parallel routes sharing a wall-pair fan out) and cannot see this: a
        // single-occupant corridor is skipped (contributes nothing → vacuous 1.0) and multi-occupant
        // wall-hugging clamps to 1.0 (edge-hugging surfaces via edgeCoincidence, not here). No
        // detector covers single-route centring, so this dimension is not-checked — a perfect
        // occupancy score is NOT evidence the route is centred; render-verify.
        CORRIDOR_CENTERING("corridorCentering", COVERAGE_NOT_CHECKED, COVERAGE_NOT_CHECKED,
                ContextualTrigger.NONE),
        HUB_NEIGHBOUR_CROWDING("hubNeighbourCrowding", COVERAGE_CHECKED, COVERAGE_NOT_APPLICABLE,
                ContextualTrigger.NONE),
        PARALLEL_CONNECTION_GAP("parallelConnectionGap", COVERAGE_CHECKED, COVERAGE_NOT_CHECKED,
                ContextualTrigger.NONE),
        NOTE_OVERLAP("noteOverlap", COVERAGE_CHECKED, COVERAGE_NOT_APPLICABLE,
                ContextualTrigger.NONE),
        NOTE_CLIP("noteClip", COVERAGE_CHECKED, COVERAGE_NOT_CHECKED,
                ContextualTrigger.NONE),
        IMAGE_SIBLING_OVERLAP("imageSiblingOverlap", COVERAGE_CHECKED, COVERAGE_NOT_APPLICABLE,
                ContextualTrigger.NONE),
        // The companion containment axis: the sibling dimension above compares only within a
        // parent bucket, so an element's icon colliding with the icon of an element containing
        // it is covered here instead. Informational; no rating impact.
        OVERLAY_ICON_COLLISION("overlayIconCollision", COVERAGE_CHECKED, COVERAGE_NOT_APPLICABLE,
                ContextualTrigger.NONE),
        // The third icon axis: an element's own icon drawn over its own title, wherever that
        // title's own textAlignment puts it. Neither dimension above can reach it — the icon
        // rectangle is clamped to its element box, so the icon and the title it covers are never
        // compared. Informational; no rating impact.
        // Contextual, like labelOverlaps below-declared: downgrades to "partial" on a run carrying
        // an icon-bearing named object whose title width could not be measured, so the icon was
        // never actually compared against anything.
        OWN_ICON_OVER_LABEL("ownIconOverLabel", COVERAGE_CHECKED, COVERAGE_NOT_CHECKED,
                ContextualTrigger.UNMEASURED_TITLE),
        // The connection-route-vs-visual class: a connection penetrating a Note/image interior
        // (this dimension; drives routing Tier-3R, cap-good) OR grazing its border (the sibling
        // connectionGrazesVisual dimension below). Both route-vs-visual modes are now covered, so
        // this dimension is fully checked. (A label sitting ON a note is a separate label concern,
        // not part of this route dimension.)
        CONNECTION_THROUGH_NOTE("connectionThroughNote", COVERAGE_CHECKED, COVERAGE_NOT_CHECKED,
                ContextualTrigger.NONE),
        // A connection grazing a Note/image BORDER — the outer band the through-visual inset
        // discards, including visuals too small to inset (informational; no rating impact).
        // Disjoint from connectionThroughNote (interior penetration), together completing the
        // connection-route-vs-visual class.
        CONNECTION_GRAZES_VISUAL("connectionGrazesVisual", COVERAGE_CHECKED, COVERAGE_NOT_CHECKED,
                ContextualTrigger.NONE),
        // Redundant (collinear / removable) bendpoints (informational; no rating impact).
        REDUNDANT_BENDPOINTS("redundantBendpoints", COVERAGE_CHECKED, COVERAGE_NOT_CHECKED,
                ContextualTrigger.NONE),
        // Non-orthogonal interior (mid) segments — off-cardinal segments between the terminals
        // (informational; no rating impact, distinct from the rating-affecting terminal count).
        NON_ORTHOGONAL_INTERIOR_SEGMENTS("nonOrthogonalInteriorSegments", COVERAGE_CHECKED, COVERAGE_NOT_CHECKED,
                ContextualTrigger.NONE),
        // Container fill == nested-child fill — backstop for the container-recession emitter
        // (informational; no rating impact). Flags the authored same-colour blob the emitter
        // is forbidden to touch; the emitter itself prevents the unauthored-fill blob at add time.
        CONTAINER_FILL_RECESSION("containerFillRecession", COVERAGE_CHECKED, COVERAGE_NOT_APPLICABLE,
                ContextualTrigger.NONE),
        // A terminal route that departs an element face then runs parallel to and hugs that face
        // (perpendicular clearance below the stub minimum) — the visible hugging exit the raw
        // terminal-angle check misses (informational; no rating impact). Distinct from the
        // rating-bearing nonOrthogonalTerminals dimension, which stays checked.
        OFF_FACE_PARALLEL_TERMINALS("offFaceParallelTerminals", COVERAGE_CHECKED, COVERAGE_NOT_CHECKED,
                ContextualTrigger.NONE),
        // Element faces on which two or more connection terminals coincide (share a perimeter point
        // within the hub-port slot tolerance) — the same-face port collision the M5 hubPortQuality
        // metric misses on any face below its four-connection guard (informational; no rating impact;
        // the rating-bearing hubPortQuality dimension above stays checked).
        COINCIDENT_FACE_PORTS("coincidentFacePorts", COVERAGE_CHECKED, COVERAGE_NOT_CHECKED,
                ContextualTrigger.NONE),
        // Connections whose two stored bendpoint reconstructions disagree by more than the
        // representable-precision floor — a route computed for a geometry that has since moved
        // (informational; no rating impact). NOT_CHECKED on a single-object view, with the rest of
        // the connection family: a lone object can carry a self-referencing connection, so no
        // object count writes the mode off. A self-loop's source and target centres are the same
        // value, so the centre terms cancel and the drift reduces to |startX - endX| — zero for any
        // self-loop written correctly, since both offsets must describe one absolute point. That is
        // an argument about well-behaved writers, not about structure, and this enum requires the
        // weaker claim wherever applicability rests on such an argument.
        ANCHOR_DRIFT("anchorDrift", COVERAGE_CHECKED, COVERAGE_NOT_CHECKED,
                ContextualTrigger.NONE),
        // Connections doubling back through a sidestep too narrow to be a detour — the four-point
        // reversal the three-point zigzag predicate cannot express (informational; no rating
        // impact). NOT_CHECKED on a single-object view: the shape lives entirely on one connection's
        // own polyline, so a self-referencing connection on a lone object can exhibit it in full —
        // leave a face, step aside, re-enter — and nothing about a one-object view prevents it.
        LATERAL_JOG_REVERSALS("lateralJogReversals", COVERAGE_CHECKED, COVERAGE_NOT_CHECKED,
                ContextualTrigger.NONE);

        final String id;
        /** One of the {@code COVERAGE_*} levels — the coverage state this dimension declares. */
        final String coverage;
        /**
         * The level this dimension reports on a <em>single-object</em> view, where the assessor
         * does not run at all — one of {@link #COVERAGE_NOT_APPLICABLE} (the failure mode
         * structurally requires two or more view objects, so no single object can exhibit it)
         * or {@link #COVERAGE_NOT_CHECKED} (the mode IS reachable with one object — on its own
         * or via a self-referencing connection — but no detector ran, so absence of a finding
         * is not evidence of absence).
         *
         * <p>Declared per dimension at its definition site rather than derived by a builder
         * branch, so a dimension added later cannot compile without stating it and silently
         * inherit whatever a default happened to be. Where applicability is uncertain the
         * weaker claim {@code not-checked} is mandatory: it costs a consumer one unnecessary
         * render-verify, whereas {@code not-applicable} on a reachable mode is a false
         * all-clear.</p>
         */
        final String degenerateCoverage;
        /**
         * The run-scoped condition that downgrades this dimension to {@link #COVERAGE_PARTIAL},
         * or {@link ContextualTrigger#NONE} when its declared level is final on every run.
         *
         * <p>This is the authority for the permanent-versus-contextual classification: a dimension
         * is CONTEXTUALLY downgradable exactly when this is not {@code NONE}, and
         * {@link #buildCoverageMap} derives the downgrade from it rather than from a hand-written
         * branch listing the dimensions by name. Stating it at the definition site is what keeps
         * the classification from being copied into a second list that can then go stale — the
         * failure this field replaced.</p>
         */
        final ContextualTrigger contextualTrigger;

        CoverageDimension(String id, String coverage, String degenerateCoverage,
                ContextualTrigger contextualTrigger) {
            this.id = id;
            this.coverage = coverage;
            this.degenerateCoverage = degenerateCoverage;
            this.contextualTrigger = contextualTrigger;
        }
    }

    /**
     * Builds the per-dimension coverage map from the {@link CoverageDimension} registry: each
     * dimension reports its declared coverage level ({@code checked} / {@code partial} /
     * {@code not-checked}) verbatim. Insertion order follows the registry. The map is never null
     * and contains exactly one entry per registry dimension (informational only — no rating impact).
     *
     * <p>Three values are contextual. When {@code labelExceedsSegment} is true (this run carries at
     * least one connection label wider than its hosting segment), {@code labelOverlaps} is
     * downgraded from its declared {@code checked} to {@code partial}. A label that overruns its
     * segment can crowd a neighbour while still clearing it geometrically, so an overlap count of
     * zero does NOT certify that mode clean — the consumer must render-verify. When
     * {@code unmeasuredTitle} is true (this run carries a named, icon-bearing object whose title
     * width could not be measured — a native group, which is never measured, or a Grouping or
     * element whose measureText failed), {@code ownIconOverLabel} is downgraded the same way:
     * there was no title rectangle to test the icon against, so that object was never examined
     * and its zero certifies nothing. When
     * {@code unmeasuredParentBand} is true (this run carries a parent, with children, whose label
     * width was never measured), {@code parentLabelObscured} is downgraded too: that parent's title
     * band was sized as a single line however long the title is, so the clearance verdict returned
     * for it was never really tested. The declared level is the baseline; coverage may only
     * downgrade contextually, never silently upgrade.</p>
     */
    static Map<String, String> buildCoverageMap(boolean labelExceedsSegment,
                                                boolean unmeasuredTitle,
                                                boolean unmeasuredParentBand) {
        // Which run-scoped conditions actually fired. The mapping from a condition to the
        // dimension it downgrades is declared on the dimension itself
        // (CoverageDimension.contextualTrigger), so this loop never names a dimension: adding a
        // contextually-downgradable dimension later is a registry edit, and the classification
        // cannot drift from the map it produces because there is only the one statement of it.
        Set<ContextualTrigger> fired = EnumSet.noneOf(ContextualTrigger.class);
        if (labelExceedsSegment) {
            fired.add(ContextualTrigger.LABEL_EXCEEDS_SEGMENT);
        }
        if (unmeasuredTitle) {
            fired.add(ContextualTrigger.UNMEASURED_TITLE);
        }
        if (unmeasuredParentBand) {
            fired.add(ContextualTrigger.UNMEASURED_PARENT_BAND);
        }
        Map<String, String> coverage = new LinkedHashMap<>();
        for (CoverageDimension dim : CoverageDimension.values()) {
            // NONE is never added above, so a dimension declaring it can never be downgraded here.
            coverage.put(dim.id,
                    fired.contains(dim.contextualTrigger) ? COVERAGE_PARTIAL : dim.coverage);
        }
        return coverage;
    }

    /**
     * The dimensions this run downgraded CONTEXTUALLY — declared {@code checked} in the registry
     * and reported {@code partial} only because something on this particular view could not be
     * measured. Derived from the registry and the supplied map, in registry order.
     *
     * <p>Deliberately NOT every dimension reading {@code partial}. Two dimensions declare
     * {@code partial} permanently and a third declares {@code not-checked}, so every fully-assessed
     * run carries at least three non-{@code checked} entries before anything about the view is
     * considered. Those are a property of the code, identical on every response; repeating them as
     * though they were findings would bury the entries that ARE this run's news. The permanent set
     * is disclosed as a count instead, and {@code coverage} carries the detail.</p>
     *
     * @param coverage a coverage map as built by {@link #buildCoverageMap} or
     *                 {@link #buildDegenerateCoverageMap}; an unknown or absent id is simply not
     *                 matched, so a legacy empty map yields an empty list rather than throwing
     */
    static List<String> contextualPartialDimensions(Map<String, String> coverage) {
        List<String> ids = new ArrayList<>();
        for (CoverageDimension dim : contextuallyPartial(coverage)) {
            ids.add(dim.id);
        }
        return List.copyOf(ids);
    }

    /**
     * The same selection as {@link #contextualPartialDimensions}, as registry entries rather than
     * ids, so a caller needing a dimension's trigger or reason does not have to look the id back
     * up. Both forms read the one selection, so the published field and the prose describing it
     * cannot select differently.
     */
    private static List<CoverageDimension> contextuallyPartial(Map<String, String> coverage) {
        if (coverage == null || coverage.isEmpty()) {
            return List.of();
        }
        List<CoverageDimension> dimensions = new ArrayList<>();
        for (CoverageDimension dim : CoverageDimension.values()) {
            if (dim.contextualTrigger != ContextualTrigger.NONE
                    && COVERAGE_PARTIAL.equals(coverage.get(dim.id))) {
                dimensions.add(dim);
            }
        }
        return List.copyOf(dimensions);
    }

    /**
     * The one coverage map a run produced, wrapped so the prose and the published field cannot
     * diverge and so the argument cannot be miswired.
     *
     * <p>WHY A TYPE AND NOT THE BARE MAP. {@link #generateSuggestions} already takes 23 positional
     * arguments, and {@code ratingBreakdown} — also a {@code Map<String, String>} — is in scope one
     * line above its call site. Passing the coverage map as a bare {@code Map} would let
     * {@code ratingBreakdown} be handed over by mistake and still compile, returning plausible
     * nonsense; a purpose-built type makes that miswire a compile error instead of a silent
     * defect.</p>
     *
     * <p>Every figure the prose quotes is derived from this one map, so a sentence claiming N
     * dimensions were not fully examined is arithmetic over the very map the response publishes,
     * never a second count that could drift from it.</p>
     *
     * @param coverage the map exactly as built for this run; insertion order is registry order and
     *                 is contractual, so it is held as given rather than re-copied into a
     *                 hash-ordered map
     */
    record CoverageDeclaration(Map<String, String> coverage) {

        /** Total dimensions declared — the denominator the prose quotes. */
        int dimensionCount() {
            return coverage.size();
        }

        /**
         * Dimensions this run did NOT fully examine: every entry whose level is not
         * {@code checked}, whether that is a permanent {@code partial}, a contextual one, a
         * standing {@code not-checked} or a {@code not-applicable}. This is the number a caller
         * needs in order to read a zero correctly, and it is counted off the published map rather
         * than tracked alongside it.
         */
        int notFullyExaminedCount() {
            int count = 0;
            for (String level : coverage.values()) {
                if (!COVERAGE_CHECKED.equals(level)) {
                    count++;
                }
            }
            return count;
        }

        /**
         * This run's contextually-downgraded dimensions, in registry order.
         *
         * <p>The dimensions themselves rather than their ids, so the prose can read each one's
         * trigger — and its reason — directly off the registry entry instead of looking the id
         * back up. {@link #contextualPartialDimensions} is the id projection of this same list,
         * for the published field.</p>
         */
        List<CoverageDimension> contextualPartials() {
            return contextuallyPartial(coverage);
        }
    }

    /**
     * Builds the per-dimension coverage map for a <em>degenerate</em> view — one holding at most
     * one object, where the assessor never runs and no geometric comparison is possible. Same
     * invariants as {@link #buildCoverageMap}: never null, insertion order follows the registry,
     * exactly one entry per dimension, informational only. It exists as a sibling rather than a
     * branch inside {@code buildCoverageMap} so the fully-assessed path is untouched.
     *
     * <p>A degenerate view is silent, not clean: with no map at all a consumer cannot tell a
     * dimension that could not apply from one that was never evaluated, and a rating-bearing
     * detection suppressed by the short-circuit ({@code labelTruncations}, {@code offCanvas})
     * reads as a zero it never earned.</p>
     *
     * <p>{@code objectCount == 0} ⇒ every dimension is {@link #COVERAGE_NOT_APPLICABLE}: with no
     * objects there can be no connection either, so nothing is reachable. Otherwise each
     * dimension reports its own declared {@link CoverageDimension#degenerateCoverage} — a single
     * object can carry a self-referencing connection, so the whole connection family stays
     * {@link #COVERAGE_NOT_CHECKED} rather than being written off.</p>
     *
     * @param objectCount view objects in the degenerate view — elements, groups and notes alike;
     *                    must be 0 or 1
     * @throws IllegalArgumentException if {@code objectCount} is outside 0..1
     */
    static Map<String, String> buildDegenerateCoverageMap(int objectCount) {
        requireDegenerateObjectCount(objectCount);
        Map<String, String> coverage = new LinkedHashMap<>();
        for (CoverageDimension dim : CoverageDimension.values()) {
            coverage.put(dim.id,
                    objectCount == 0 ? COVERAGE_NOT_APPLICABLE : dim.degenerateCoverage);
        }
        return coverage;
    }

    /**
     * Rejects an object count no degenerate view can have. Both degenerate helpers branch on
     * {@code == 0} and treat everything else as the single-object case, so an out-of-range count
     * would otherwise be answered confidently and wrongly — a view of five objects would be told
     * it holds one, and told which dimensions "could not apply" when in truth all of them were
     * assessable. Silence dressed as an answer is the exact failure this coverage map exists to
     * remove, so the helpers refuse the question rather than answer it badly.
     */
    private static void requireDegenerateObjectCount(int objectCount) {
        if (objectCount < 0 || objectCount > 1) {
            throw new IllegalArgumentException(
                    "Degenerate coverage is defined only for a view of 0 or 1 objects, but got "
                            + objectCount + ". A view with more objects is fully assessed — use "
                            + "buildCoverageMap with the assessment's contextual flags instead.");
        }
    }

    /**
     * The suggestion text a degenerate view returns. Says "object", not "element": the count is
     * over every view object — elements, groups and notes alike — so a view holding one note or
     * one group would otherwise be described as having an element it does not contain.
     *
     * <p>Lives here rather than at the short-circuit so this response text, which an agent reads
     * as prose, is pinned by an executable test.</p>
     *
     * @param objectCount view objects in the degenerate view; must be 0 or 1
     * @throws IllegalArgumentException if {@code objectCount} is outside 0..1
     */
    static String degenerateSuggestion(int objectCount) {
        requireDegenerateObjectCount(objectCount);
        return objectCount == 0
                ? "View has no objects — layout assessment is not applicable."
                : "View has only one object — layout assessment is not applicable.";
    }

    /**
     * The object-local findings of a degenerate view, plus the coverage map that declares which
     * detectors actually ran to produce them.
     *
     * <p>{@code connectionCount} is a measured count, not an assessment: a lone object can carry a
     * self-referencing connection, and reporting a hard {@code 0} beside a coverage map that says
     * the connection dimensions are {@code not-checked} asserts as fact something no detector
     * established. The connection dimensions stay {@code not-checked} precisely because counting a
     * connection is not assessing it.</p>
     */
    record DegenerateAssessment(List<String> offCanvasWarnings,
            int labelTruncationCount, List<String> labelTruncations,
            int ownIconOverLabelCount, List<String> ownIconOverLabelDescriptions,
            int noteClipCount, List<String> noteClipDescriptions,
            int connectionCount, Map<String, String> coverage, List<String> suggestions) {}

    /**
     * Assesses a view holding at most one object by running exactly those detectors that are
     * computable on a single object, and declaring in {@code coverage} which ones did run.
     *
     * <p>Four detections are object-local — they inspect one object's own geometry and need no
     * second object to compare against: {@code offCanvas} and {@code labelTruncations} (both
     * rating-bearing on a normal run), plus the informational {@code ownIconOverLabel} and
     * {@code noteClip}. Suppressing them made a one-object view carrying a real, visible defect
     * report as unassessable, with every count a zero it never earned.</p>
     *
     * <p><b>The view still does not RATE.</b> Rating is deliberately left {@code not-applicable}:
     * {@link #computeAverageSpacing} and {@link #computeAlignmentScore} both return an explicit
     * no-data sentinel below two objects ({@code 0.0} and {@code 0}), and feeding those to the
     * rating would score a pristine one-object view {@code fair} on both layout axes purely for
     * having nothing to compare against — a sentinel laundered into a judgment. The findings are
     * therefore reported <em>beside</em> the non-rating, in the counts, the descriptions, and the
     * suggestion list, so a defect is visible without inventing a score for it.</p>
     *
     * <p>A dimension is upgraded out of {@code not-checked} only when its detector actually had an
     * object to look at, and is then reported at exactly the level the fully-assessed path would
     * report for the same node set — no better. Any residual imprecision (for instance that the
     * truncation detector skips groups) is a property of the main path too, not something this
     * path introduces; equating the two is what keeps the vocabulary meaning one thing.</p>
     *
     * @param nodes       the view's objects; must number 0 or 1
     * @param connections connections reconstructed for those objects — a self-referencing
     *                    connection on a lone object is real and is counted here
     * @throws IllegalArgumentException if {@code nodes} holds more than one object
     */
    DegenerateAssessment assessDegenerate(List<AssessmentNode> nodes,
            List<AssessmentConnection> connections) {
        requireDegenerateObjectCount(nodes.size());
        Map<String, String> coverage = buildDegenerateCoverageMap(nodes.size());
        int connectionCount = connections.size();
        if (nodes.isEmpty()) {
            // Nothing to look at, so nothing is upgraded: every dimension stays not-applicable.
            return new DegenerateAssessment(List.of(), 0, List.of(), 0, List.of(), 0, List.of(),
                    connectionCount, coverage, List.of(degenerateSuggestion(0)));
        }

        // Same split rule the fully-assessed path uses, so a dimension means the same thing here.
        List<AssessmentNode> layoutNodes = new ArrayList<>();
        List<AssessmentNode> noteNodes = new ArrayList<>();
        for (AssessmentNode node : nodes) {
            if (node.isNote()) {
                noteNodes.add(node);
            } else {
                layoutNodes.add(node);
            }
        }

        List<String> offCanvas = detectOffCanvas(layoutNodes);
        LabelTruncationResult truncation = detectLabelTruncation(layoutNodes);
        OwnIconOverLabelResult ownIcon = detectOwnIconOverLabel(layoutNodes);
        NoteClipResult noteClip = detectNoteTextClipping(noteNodes);

        // Upgrade ONLY the dimensions whose detector actually EXAMINED an object. Being handed a
        // non-empty list is not enough: a detector can skip the only object at its own entry guard
        // and return a clean zero having compared nothing. Claiming "checked" off the back of that
        // is the same false all-clear this map exists to prevent, so the tests below pin one node
        // shape per guard.
        Map<String, String> declared = new LinkedHashMap<>(coverage);
        if (!layoutNodes.isEmpty()) {
            // detectOffCanvas has no entry guard — it tests every layout node's coordinates.
            declared.put(CoverageDimension.OFF_CANVAS.id, CoverageDimension.OFF_CANVAS.coverage);
            // detectOwnIconOverLabel skips an object with no overlay icon, but that is a DECIDED
            // result (no icon means no collision is possible), not an unmeasured one. Its genuinely
            // unmeasured case is a named, icon-bearing object whose title width could not be
            // measured, which it reports itself.
            declared.put(CoverageDimension.OWN_ICON_OVER_LABEL.id,
                    ownIcon.unmeasuredTitle()
                            ? COVERAGE_PARTIAL
                            : CoverageDimension.OWN_ICON_OVER_LABEL.coverage);
        }
        if (labelTruncationExamined(layoutNodes)) {
            declared.put(CoverageDimension.LABEL_TRUNCATIONS.id,
                    CoverageDimension.LABEL_TRUNCATIONS.coverage);
        }
        if (noteClipExamined(noteNodes)) {
            declared.put(CoverageDimension.NOTE_CLIP.id, CoverageDimension.NOTE_CLIP.coverage);
        }

        return new DegenerateAssessment(offCanvas,
                truncation.count(), truncation.descriptions(),
                ownIcon.count(), ownIcon.descriptions(),
                noteClip.count(), noteClip.descriptions(),
                connectionCount, declared,
                degenerateSuggestions(nodes.size(), offCanvas, truncation, ownIcon, noteClip));
    }

    /**
     * Whether {@link #detectLabelTruncation} actually compared a label against its box for any of
     * these nodes, rather than skipping every one at its entry guard.
     *
     * <p>Mirrors that detector's guards for the degenerate case. A GROUP is skipped because the
     * detector cannot measure a title band; a box with no room beside the type icon, or a label of
     * unmeasured width, cannot be compared either — all three leave the mode unexamined. An object
     * with no name is different in kind: there is no label, so there is nothing to truncate and the
     * absence of a finding is a decided result rather than an unmeasured one.</p>
     *
     * <p>Where this is uncertain it answers false, because an unnecessary render-verify costs one
     * look while a false {@code checked} costs the guarantee.</p>
     */
    private static boolean labelTruncationExamined(List<AssessmentNode> layoutNodes) {
        for (AssessmentNode node : layoutNodes) {
            if (node.isGroup()) {
                continue;
            }
            if (node.name() == null || node.name().isEmpty()) {
                return true;
            }
            if (node.width() - TYPE_ICON_WIDTH > 0 && node.labelTextWidth() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@link #detectNoteTextClipping} actually measured any note. It skips a note whose
     * required height is unavailable ("no content / measurement unavailable"), so a view holding
     * only such a note has had nothing examined and must not report the dimension as covered.
     */
    private static boolean noteClipExamined(List<AssessmentNode> noteNodes) {
        for (AssessmentNode note : noteNodes) {
            if (note.noteRequiredHeight() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * The suggestion list for a degenerate view. The base line still says the layout is not rated,
     * because it is not — but where a detector found something, the findings follow it verbatim.
     * A rating-bearing defect that is suppressed from the rating AND absent from the prose is
     * invisible, which is the failure this path had; carrying the descriptions is what makes the
     * non-rating honest rather than silencing.
     */
    private static List<String> degenerateSuggestions(int objectCount, List<String> offCanvas,
            LabelTruncationResult truncation, OwnIconOverLabelResult ownIcon,
            NoteClipResult noteClip) {
        List<String> suggestions = new ArrayList<>();
        suggestions.add(degenerateSuggestion(objectCount));
        int findings = offCanvas.size() + truncation.count() + ownIcon.count() + noteClip.count();
        if (findings == 0) {
            return suggestions;
        }
        suggestions.add("Layout is not rated on a view holding one object, but " + findings
                + " object-level issue(s) were detected on that object and still need attention.");
        suggestions.addAll(offCanvas);
        suggestions.addAll(truncation.descriptions());
        suggestions.addAll(ownIcon.descriptions());
        suggestions.addAll(noteClip.descriptions());
        return suggestions;
    }

    /**
     * Computes the overall quality rating with per-metric breakdown.
     * Delegates to the breakdown-aware overload with {@code hasGroups=false} and
     * zero values for the M2-M5 + layout-tier inputs.
     *
     * @deprecated Use {@link #computeRatingWithBreakdown} to get both the rating
     *             and per-metric breakdown, and to enable grouped-view leniency.
     */
    @Deprecated
    String computeOverallRating(int overlaps, int crossings,
                                 double avgSpacing, int alignmentScore,
                                 int labelOverlapCount, int passThroughCount,
                                 int connectionCount) {
        return computeRatingWithBreakdown(overlaps, crossings, avgSpacing,
                alignmentScore, labelOverlapCount, passThroughCount,
                0, 0, connectionCount, false).rating();
    }

    /**
     * Computes the overall quality rating with per-metric breakdown — M6 two-dimensional model.
     *
     * <p>Backwards-compatible delegating overload (10-arg). Existing callers pass zeros for the
     * M2-M5 + layout-tier inputs; M6 promotions for parentLabelObscured and labelTruncation are then
     * inactive (count = 0). Use the 18-arg expanded overload to exercise the full M6 model.</p>
     */
    RatingResult computeRatingWithBreakdown(int overlaps, int crossings,
                                             double avgSpacing, int alignmentScore,
                                             int labelOverlapCount, int passThroughCount,
                                             int coincidentSegments, int nonOrthogonalTerminals,
                                             int connectionCount, boolean hasGroups) {
        return computeRatingWithBreakdown(overlaps, crossings, avgSpacing, alignmentScore,
                labelOverlapCount, passThroughCount, coincidentSegments, nonOrthogonalTerminals,
                connectionCount, hasGroups,
                0, 0, 0, 0,           // boundaryViolation, parentLabelObscured, offCanvas, labelTruncation
                0, 0, 0, 1.0);        // interior, zigzag, edgeCoincidence, hubPortQuality (1.0 = perfect)
    }

    /**
     * Computes the overall quality rating with per-metric breakdown (M6).
     *
     * <p><b>M6 model:</b> Each metric contributes an individual
     * rating ("pass"/"excellent"/"good"/"fair"/"poor"). The overall rating uses a two-dimensional
     * decomposition: a layout-tier rating (Tier 1L: overlaps, boundary, parentLabelObscured-promoted;
     * Tier 2L cap-fair: spacing, off-canvas; Tier 3L cap-good: alignment) AND a routing-tier rating
     * (Tier 1R: passThroughs, M2 interior, M3 zigzag, conn-vs-conn coincident; Tier 2R cap-fair: M1 nonOrth,
     * M4 edge-coincidence, M5 low hub-port quality, labelOverlap-promoted, labelTruncation-promoted;
     * Tier 3R cap-good: edge crossings). Combined {@code overall = worse(layoutRating, routingRating)}.
     * Per spec, layout is the prerequisite — a view with sibling overlaps is broken regardless
     * of routing quality.</p>
     *
     * @param hasGroups when true, crossing leniency applies if passThroughCount (cross-element only) &lt;= FAIR_MAX_PASS_THROUGHS
     */
    RatingResult computeRatingWithBreakdown(int overlaps, int crossings,
                                             double avgSpacing, int alignmentScore,
                                             int labelOverlapCount, int passThroughCount,
                                             int coincidentSegments, int nonOrthogonalTerminals,
                                             int connectionCount, boolean hasGroups,
                                             int boundaryViolationCount, int parentLabelObscuredCount,
                                             int offCanvasCount, int labelTruncationCount,
                                             int interiorTerminationCount, int zigzagCount,
                                             int connectionEdgeCoincidenceCount,
                                             double hubPortQualityScore) {
        return computeRatingWithBreakdown(overlaps, crossings, avgSpacing, alignmentScore,
                labelOverlapCount, passThroughCount, coincidentSegments, nonOrthogonalTerminals,
                connectionCount, hasGroups, boundaryViolationCount, parentLabelObscuredCount,
                offCanvasCount, labelTruncationCount, interiorTerminationCount, zigzagCount,
                connectionEdgeCoincidenceCount, hubPortQualityScore, false);
    }

    /**
     * Rating overload (19-arg) with the hub-to-neighbour crowding flag.
     *
     * <p>{@code hubNeighbourCrowded} adds a layout Tier-2L (cap-fair) breakdown entry so a
     * hub whose resized edge collapses a neighbouring spoke-row corridor can no longer rate
     * {@code good}. When false the entry is {@code pass} and the overall rating is unchanged
     * from the 18-arg form — every existing caller therefore keeps byte-identical output.</p>
     *
     * <p>Delegating overload: forwards {@code nonOrthogonalInteriorSegmentCount = 0} to the widest
     * form, so the non-orthogonal interior-segment entry is {@code pass} and the rating is unchanged
     * — every existing caller therefore keeps byte-identical output.</p>
     */
    RatingResult computeRatingWithBreakdown(int overlaps, int crossings,
                                             double avgSpacing, int alignmentScore,
                                             int labelOverlapCount, int passThroughCount,
                                             int coincidentSegments, int nonOrthogonalTerminals,
                                             int connectionCount, boolean hasGroups,
                                             int boundaryViolationCount, int parentLabelObscuredCount,
                                             int offCanvasCount, int labelTruncationCount,
                                             int interiorTerminationCount, int zigzagCount,
                                             int connectionEdgeCoincidenceCount,
                                             double hubPortQualityScore,
                                             boolean hubNeighbourCrowded) {
        return computeRatingWithBreakdown(overlaps, crossings, avgSpacing, alignmentScore,
                labelOverlapCount, passThroughCount, coincidentSegments, nonOrthogonalTerminals,
                connectionCount, hasGroups, boundaryViolationCount, parentLabelObscuredCount,
                offCanvasCount, labelTruncationCount, interiorTerminationCount, zigzagCount,
                connectionEdgeCoincidenceCount, hubPortQualityScore, hubNeighbourCrowded, 0);
    }

    /**
     * Rating overload (20-arg) with the non-orthogonal interior-segment count.
     *
     * <p>{@code nonOrthogonalInteriorSegmentCount} adds a routing Tier-2R (cap-fair) breakdown
     * entry, ratio-bucketed identically to the terminal sibling {@code nonOrthogonalTerminals}:
     * a route bending off-cardinal in its interior is just as visible as one bending at an
     * endpoint, so it costs the same routing tier. The routing tier combines its members by
     * {@code Math.max}, so a connection diagonal at both a terminal and an interior segment is
     * capped once, not twice. When the count is zero the entry is {@code pass} and the overall
     * rating is unchanged from the 19-arg form.</p>
     *
     * <p>Delegating overload: forwards {@code connectionThroughNoteCount = 0} to the widest form,
     * so the connection-through-note entry is {@code pass} and the rating is unchanged — every
     * existing caller therefore keeps byte-identical output.</p>
     */
    RatingResult computeRatingWithBreakdown(int overlaps, int crossings,
                                             double avgSpacing, int alignmentScore,
                                             int labelOverlapCount, int passThroughCount,
                                             int coincidentSegments, int nonOrthogonalTerminals,
                                             int connectionCount, boolean hasGroups,
                                             int boundaryViolationCount, int parentLabelObscuredCount,
                                             int offCanvasCount, int labelTruncationCount,
                                             int interiorTerminationCount, int zigzagCount,
                                             int connectionEdgeCoincidenceCount,
                                             double hubPortQualityScore,
                                             boolean hubNeighbourCrowded,
                                             int nonOrthogonalInteriorSegmentCount) {
        return computeRatingWithBreakdown(overlaps, crossings, avgSpacing, alignmentScore,
                labelOverlapCount, passThroughCount, coincidentSegments, nonOrthogonalTerminals,
                connectionCount, hasGroups, boundaryViolationCount, parentLabelObscuredCount,
                offCanvasCount, labelTruncationCount, interiorTerminationCount, zigzagCount,
                connectionEdgeCoincidenceCount, hubPortQualityScore, hubNeighbourCrowded,
                nonOrthogonalInteriorSegmentCount, 0);
    }

    /**
     * Rating overload (21-arg) with the connection-through-note/image count.
     *
     * <p>{@code connectionThroughNoteCount} adds a routing Tier-3R (cap-good) breakdown entry on
     * binary presence: a connection routed through a Note or image visual is an obstacle the
     * router failed to avoid — always jarring to the reader — so any single crossing nudges the
     * routing dimension. Presence, not magnitude: one crossing and three crossings both rate
     * {@code good}, and the Tier-3 cap holds it at {@code good} (never fair/poor). It is disjoint
     * from the element {@code passThroughs} entry (Tier-1R) by construction — notes/images are not
     * in the scoring node set — so the two never stack on the same crossing. When the count is zero
     * the entry is {@code pass} and the overall rating is unchanged from the 20-arg form.</p>
     *
     * <p>Delegating overload: forwards {@code offFaceParallelTerminalCount = 0} to the widest form,
     * so the off-face parallel-terminal entry is {@code pass} and the rating is unchanged — every
     * existing caller therefore keeps byte-identical output.</p>
     */
    RatingResult computeRatingWithBreakdown(int overlaps, int crossings,
                                             double avgSpacing, int alignmentScore,
                                             int labelOverlapCount, int passThroughCount,
                                             int coincidentSegments, int nonOrthogonalTerminals,
                                             int connectionCount, boolean hasGroups,
                                             int boundaryViolationCount, int parentLabelObscuredCount,
                                             int offCanvasCount, int labelTruncationCount,
                                             int interiorTerminationCount, int zigzagCount,
                                             int connectionEdgeCoincidenceCount,
                                             double hubPortQualityScore,
                                             boolean hubNeighbourCrowded,
                                             int nonOrthogonalInteriorSegmentCount,
                                             int connectionThroughNoteCount) {
        return computeRatingWithBreakdown(overlaps, crossings, avgSpacing, alignmentScore,
                labelOverlapCount, passThroughCount, coincidentSegments, nonOrthogonalTerminals,
                connectionCount, hasGroups, boundaryViolationCount, parentLabelObscuredCount,
                offCanvasCount, labelTruncationCount, interiorTerminationCount, zigzagCount,
                connectionEdgeCoincidenceCount, hubPortQualityScore, hubNeighbourCrowded,
                nonOrthogonalInteriorSegmentCount, connectionThroughNoteCount, 0);
    }

    /**
     * Full rating overload (22-arg) with the off-face parallel-terminal hug count.
     *
     * <p>{@code offFaceParallelTerminalCount} adds a routing Tier-2R (cap-fair) breakdown entry on
     * binary presence: a terminal route that departs an element face then runs parallel to and
     * hugging it (within {@link #OFF_FACE_MIN_STUB_PX}) is a plainly-visible defect that the
     * visible-length-guarded terminal metric {@code nonOrthogonalTerminals} suppresses as a
     * sub-pixel stub. Presence, not magnitude — any single hug caps routing at {@code fair} (never
     * {@code poor}), so a view whose ONLY routing defect is an off-face hug cannot read
     * {@code good}/{@code excellent} at the headline while the render plainly shows the hug. Disjoint
     * from {@code nonOrthogonalTerminals} (visible diagonal) and {@code nonOrthogonalInteriorSegments}
     * (off-cardinal mid segment) by construction; the routing tier combines the family by
     * {@code Math.max}, so a connection tripping two of them is capped once, not stacked. Where the
     * hug is layout-bound (a rolled-back egress lift, {@code EGRESS_LIFT_LAYOUT_BOUND}), the remedy is
     * spacing not re-routing — the collector's per-hug description already prescribes it. When the
     * count is zero the entry is {@code pass} and the overall rating is unchanged from the 21-arg
     * form.</p>
     */
    RatingResult computeRatingWithBreakdown(int overlaps, int crossings,
                                             double avgSpacing, int alignmentScore,
                                             int labelOverlapCount, int passThroughCount,
                                             int coincidentSegments, int nonOrthogonalTerminals,
                                             int connectionCount, boolean hasGroups,
                                             int boundaryViolationCount, int parentLabelObscuredCount,
                                             int offCanvasCount, int labelTruncationCount,
                                             int interiorTerminationCount, int zigzagCount,
                                             int connectionEdgeCoincidenceCount,
                                             double hubPortQualityScore,
                                             boolean hubNeighbourCrowded,
                                             int nonOrthogonalInteriorSegmentCount,
                                             int connectionThroughNoteCount,
                                             int offFaceParallelTerminalCount) {
        Map<String, String> breakdown = new LinkedHashMap<>();

        // 1. Overlaps rating (Tier 1L) — binary >0 → poor (sibling overlaps are tier-1L layout-severity)
        if (overlaps == 0) {
            breakdown.put("overlaps", "pass");
        } else {
            breakdown.put("overlaps", "poor");
        }

        // 2. Edge crossings rating (Tier 3R cap-good — density-aware)
        double crossingRatio = connectionCount > 0
                ? (double) crossings / connectionCount : crossings;
        String crossingRating;
        if (crossings < EXCELLENT_MAX_CROSSINGS) {
            crossingRating = "pass";
        } else if (crossings < GOOD_MAX_CROSSINGS) {
            crossingRating = "good";
        } else if (connectionCount > 0 && crossingRatio <= CROSSING_RATIO_GOOD) {
            // Views with low density (≤1.5 crossings/conn) rate "good"
            // even when absolute count exceeds GOOD_MAX_CROSSINGS
            crossingRating = "good";
        } else if (connectionCount > 0 && crossingRatio <= CROSSING_RATIO_MODERATE) {
            crossingRating = "fair";
        } else if (crossings < FAIR_MAX_CROSSINGS) {
            crossingRating = "fair";
        } else {
            crossingRating = "poor";
        }
        // Grouped-view leniency — one-tier boost (not unconditional floor).
        // Under M6, crossings already cap at "good" (Tier 3R), but the leniency still applies
        // to the breakdown rating for diagnostic clarity (a "fair"-rated breakdown with
        // grouped-view conditions becomes "good").
        if (hasGroups && overlaps == 0 && passThroughCount <= FAIR_MAX_PASS_THROUGHS
                && labelOverlapCount == 0 && alignmentScore > GOOD_MIN_ALIGNMENT
                && avgSpacing > GOOD_MIN_SPACING
                && ("fair".equals(crossingRating) || "poor".equals(crossingRating))) {
            crossingRating = "poor".equals(crossingRating) ? "fair" : "good";
        }
        breakdown.put("edgeCrossings", crossingRating);

        // 3. Spacing rating (Tier 2L cap-fair)
        if (avgSpacing > EXCELLENT_MIN_SPACING) {
            breakdown.put("spacing", "pass");
        } else if (avgSpacing > GOOD_MIN_SPACING) {
            breakdown.put("spacing", "good");
        } else {
            breakdown.put("spacing", "fair");
        }

        // 4. Alignment rating (Tier 3L cap-good)
        if (alignmentScore > EXCELLENT_MIN_ALIGNMENT) {
            breakdown.put("alignment", "pass");
        } else if (alignmentScore > GOOD_MIN_ALIGNMENT) {
            breakdown.put("alignment", "good");
        } else {
            breakdown.put("alignment", "fair");
        }

        // 5. Label overlaps rating (Tier 2R cap-fair — promoted from Tier 3R under M6)
        if (labelOverlapCount == 0) {
            breakdown.put("labelOverlaps", "pass");
        } else if (labelOverlapCount <= 2) {
            breakdown.put("labelOverlaps", "good");
        } else {
            breakdown.put("labelOverlaps", "fair");
        }

        // 6. Pass-throughs rating (Tier 1R)
        if (passThroughCount == 0) {
            breakdown.put("passThroughs", "pass");
        } else if (passThroughCount <= FAIR_MAX_PASS_THROUGHS) {
            breakdown.put("passThroughs", "fair");
        } else {
            breakdown.put("passThroughs", "poor");
        }

        // 7. Coincident segments rating (Tier 1R conn-vs-conn)
        if (coincidentSegments == 0) {
            breakdown.put("coincidentSegments", "pass");
        } else if (coincidentSegments <= GOOD_MAX_COINCIDENT) {
            breakdown.put("coincidentSegments", "good");
        } else if (coincidentSegments <= FAIR_MAX_COINCIDENT) {
            breakdown.put("coincidentSegments", "fair");
        } else {
            breakdown.put("coincidentSegments", "poor");
        }

        // 8. Non-orthogonal terminals rating (Tier 2R cap-fair — promoted from Tier 3R under M6, density-aware).
        // M1 corrected definition (visible post-clip segment) flows through `nonOrthogonalTerminals`.
        if (nonOrthogonalTerminals == 0) {
            breakdown.put("nonOrthogonalTerminals", "pass");
        } else if (connectionCount > 0) {
            double nonOrthRatio = (double) nonOrthogonalTerminals / connectionCount;
            if (nonOrthRatio <= NON_ORTH_RATIO_GOOD) {
                breakdown.put("nonOrthogonalTerminals", "good");
            } else if (nonOrthRatio <= NON_ORTH_RATIO_FAIR) {
                breakdown.put("nonOrthogonalTerminals", "fair");
            } else {
                breakdown.put("nonOrthogonalTerminals", "poor");
            }
        } else {
            // Zero connections but non-zero non-orth (edge case) — rate as fair
            breakdown.put("nonOrthogonalTerminals", "fair");
        }

        // 8b. Non-orthogonal interior segments rating (Tier 2R cap-fair — density-aware, mirrors the
        //     terminal sibling above). An off-cardinal mid-route hop is just as visible as one at an
        //     endpoint, so it shares the terminal sibling's ratio buckets and routing tier. The
        //     routing tier combines members by Math.max, so a connection diagonal at both a terminal
        //     and an interior segment lights both entries but is capped once.
        if (nonOrthogonalInteriorSegmentCount == 0) {
            breakdown.put("nonOrthogonalInteriorSegments", "pass");
        } else if (connectionCount > 0) {
            double interiorRatio = (double) nonOrthogonalInteriorSegmentCount / connectionCount;
            if (interiorRatio <= NON_ORTH_RATIO_GOOD) {
                breakdown.put("nonOrthogonalInteriorSegments", "good");
            } else if (interiorRatio <= NON_ORTH_RATIO_FAIR) {
                breakdown.put("nonOrthogonalInteriorSegments", "fair");
            } else {
                breakdown.put("nonOrthogonalInteriorSegments", "poor");
            }
        } else {
            // Zero connections but non-zero interior count (edge case) — rate as fair
            breakdown.put("nonOrthogonalInteriorSegments", "fair");
        }

        // 8b-ii. Off-face parallel-terminal hug rating (Tier 2R cap-fair — binary presence). A terminal
        //     route that departs an element face then runs parallel to and hugging it (within
        //     OFF_FACE_MIN_STUB_PX) is a plainly-visible defect that the visible-length-guarded
        //     terminal metric (nonOrthogonalTerminals) suppresses as a sub-pixel stub. Presence, not
        //     magnitude: any single hug caps routing at fair (never poor), so a view whose only
        //     routing defect is an off-face hug cannot read good/excellent at the headline while the
        //     render shows the hug. Disjoint from nonOrthogonalTerminals (visible diagonal) and
        //     nonOrthogonalInteriorSegments (off-cardinal mid segment) by construction; the routing
        //     tier combines the family by Math.max, so a connection tripping two of them is capped
        //     once. Where the hug is layout-bound (a rolled-back egress lift), the collector's per-hug
        //     description prescribes the spacing remedy — the rating stays binary.
        breakdown.put("offFaceParallelTerminals", offFaceParallelTerminalCount == 0 ? "pass" : "fair");

        // 8c. Connection-through-note/image rating (Tier 3R cap-good — binary presence). A line routed
        //     through a Note or image visual is an obstacle the router failed to avoid: always
        //     jarring to the reader, so any single crossing nudges routing to good. Presence, not
        //     magnitude — one crossing and three both rate good; the routing Tier-3 cap holds it at
        //     good (never fair/poor). Notes are excluded from the element pass-through scoring set;
        //     for image-bearing elements this rates the image RECT, which is clipped to the element
        //     box. A route crossing an element's image therefore also crosses its box: the routing
        //     tier takes the max, so the Tier-1R passThroughs entry dominates — no double penalty.
        breakdown.put("connectionThroughNote", connectionThroughNoteCount == 0 ? "pass" : "good");

        // 9. Boundary violations (Tier 1L — promoted: any violation is layout-Tier-1L)
        breakdown.put("boundaryViolations", boundaryViolationCount == 0 ? "pass" : "poor");

        // 10. Parent label obscured (Tier 1L — promoted from informational under M6)
        breakdown.put("parentLabelObscured", parentLabelObscuredCount == 0 ? "pass" : "poor");

        // 11. Off-canvas (Tier 2L cap-fair — was partial; explicit under M6)
        breakdown.put("offCanvas", offCanvasCount == 0 ? "pass" : "fair");

        // 12. Label truncation (Tier 2R cap-fair — promoted from info per M6)
        breakdown.put("labelTruncations", labelTruncationCount == 0 ? "pass" : "fair");

        // 13. Interior terminations (Tier 1R — M2)
        breakdown.put("interiorTerminations", interiorTerminationCount == 0 ? "pass" : "poor");

        // 14. Zigzags (Tier 1R — M3)
        breakdown.put("zigzags", zigzagCount == 0 ? "pass" : "poor");

        // 15. Edge-coincidence (Tier 2R cap-fair — M4; A-gated: Tier-1R escalation at
        //     count >= EDGE_COINCIDENCE_EGREGIOUS_MAX, see computeRoutingTierLevel)
        if (connectionEdgeCoincidenceCount == 0) {
            breakdown.put("connectionEdgeCoincidence", "pass");
        } else if (connectionEdgeCoincidenceCount <= EDGE_COINCIDENCE_GOOD_MAX) {
            breakdown.put("connectionEdgeCoincidence", "good");
        } else if (connectionEdgeCoincidenceCount <= EDGE_COINCIDENCE_FAIR_MAX) {
            breakdown.put("connectionEdgeCoincidence", "fair");
        } else {
            breakdown.put("connectionEdgeCoincidence", "poor");
        }

        // 16. Hub-port quality (Tier 2R cap-fair — M5). The band boundaries live in one place so
        //     the remedies keyed off this metric cannot come to disagree with the band published
        //     here about which views the metric has marked down.
        breakdown.put("hubPortQuality", hubPortQualityBand(hubPortQualityScore));

        // 17. Hub-to-neighbour crowding (Tier 2L cap-fair). A hub edge collapsing a neighbouring
        //     spoke-row corridor is a layout-spacing defect: it caps overall at fair (never
        //     poor), so a crowded resize can no longer rate good. Stays pass when not crowded,
        //     leaving every non-crowded view's rating untouched.
        breakdown.put("hubNeighbourCrowding", hubNeighbourCrowded ? "fair" : "pass");

        // M6: two-dimensional rating (layout-tier × routing-tier × worse combiner).
        int layoutLevel = computeLayoutTierLevel(breakdown);
        int routingLevel = computeRoutingTierLevel(breakdown, connectionEdgeCoincidenceCount);
        int overallLevel = Math.max(layoutLevel, routingLevel);
        String layoutRating = levelToRating(layoutLevel);
        String routingRating = levelToRating(routingLevel);
        String overall = levelToRating(overallLevel);
        breakdown.put("overall", overall);

        // De-noised headline: the same overall computation with the accepted-cosmetic
        // nonOrthogonalTerminals contribution removed. Diagonal terminal segments are the
        // straight-line signature of ELK layout (Tier-2R cap-fair, density-bucketed above);
        // they routinely push an otherwise-clean structure view to "fair", which trains a
        // consumer to ignore "fair" altogether. This companion value lets a consumer tell a
        // terminal-cosmetic-only "fair" (overall="fair", excluding="good"/"excellent") apart
        // from a "fair" carrying a real routing/layout defect (both values equal). Computed on
        // a COPY with the nonOrthogonalTerminals entry neutralised to "pass" and the existing
        // routing-tier method re-run verbatim, so it can never raise severity and can never
        // drift from the live tier logic; the live breakdown and "overall" are untouched.
        Map<String, String> denoised = new LinkedHashMap<>(breakdown);
        denoised.put("nonOrthogonalTerminals", "pass");
        int denoisedRoutingLevel = computeRoutingTierLevel(denoised, connectionEdgeCoincidenceCount);
        breakdown.put("overallExcludingAcceptedCosmetics",
                levelToRating(Math.max(layoutLevel, denoisedRoutingLevel)));

        return new RatingResult(overall, breakdown, layoutRating, routingRating);
    }

    /**
     * Layout-tier level under M6 (worse contribution wins, with per-tier caps).
     * <ul>
     *   <li><b>Tier 1L</b> (critical, no cap): overlaps, boundaryViolations, parentLabelObscured (promoted)</li>
     *   <li><b>Tier 2L</b> (cap fair=2): spacing, offCanvas, hubNeighbourCrowding</li>
     *   <li><b>Tier 3L</b> (cap good=1): alignment</li>
     * </ul>
     */
    private int computeLayoutTierLevel(Map<String, String> breakdown) {
        int tier1 = Math.max(Math.max(
                ratingLevel(breakdown.getOrDefault("overlaps", "pass")),
                ratingLevel(breakdown.getOrDefault("boundaryViolations", "pass"))),
                ratingLevel(breakdown.getOrDefault("parentLabelObscured", "pass")));
        int tier2 = Math.max(Math.max(
                ratingLevel(breakdown.getOrDefault("spacing", "pass")),
                ratingLevel(breakdown.getOrDefault("offCanvas", "pass"))),
                ratingLevel(breakdown.getOrDefault("hubNeighbourCrowding", "pass")));
        int tier3 = ratingLevel(breakdown.getOrDefault("alignment", "pass"));

        int level = tier1;
        level = Math.max(level, Math.min(tier2, 2));
        level = Math.max(level, Math.min(tier3, 1));
        return level;
    }

    /**
     * Routing-tier level under M6 (worse contribution wins, with per-tier caps).
     * <ul>
     *   <li><b>Tier 1R</b> (critical, no cap): passThroughs, M2 interior, M3 zigzag, conn-vs-conn coincident;
     *       <b>plus M4 edge-coincidence when the count is egregious</b>
     *       (&ge; {@link #EDGE_COINCIDENCE_EGREGIOUS_MAX} — A-gated escalation)</li>
     *   <li><b>Tier 2R</b> (cap fair=2): M1 nonOrth terminals, nonOrth interior segments,
     *       off-face parallel terminals (binary presence), M4 edge-coincidence (count &lt; EGREGIOUS),
     *       M5 low hub-port quality, labelOverlaps (promoted), labelTruncations (promoted)</li>
     *   <li><b>Tier 3R</b> (cap good=1): edge crossings, connectionThroughNote (binary presence)</li>
     * </ul>
     */
    private int computeRoutingTierLevel(Map<String, String> breakdown, int edgeCoincidenceCount) {
        int tier1 = Math.max(Math.max(Math.max(
                ratingLevel(breakdown.getOrDefault("passThroughs", "pass")),
                ratingLevel(breakdown.getOrDefault("interiorTerminations", "pass"))),
                ratingLevel(breakdown.getOrDefault("zigzags", "pass"))),
                ratingLevel(breakdown.getOrDefault("coincidentSegments", "pass")));
        // A-gated escalation (2026-05-21):
        // M4 connectionEdgeCoincidence is normally Tier-2R (cap-fair, see tier2 below). An EGREGIOUS
        // count escalates it to Tier-1R so an eye-obvious hug-storm drives overall="poor" instead of
        // being masked at "fair". Spares the common 1-5 forced-hug case. M4 is intentionally also left
        // in tier2 (harmless: tier2 caps at 2; the tier1 contribution dominates when this fires).
        if (edgeCoincidenceCount >= EDGE_COINCIDENCE_EGREGIOUS_MAX) {
            tier1 = Math.max(tier1,
                    ratingLevel(breakdown.getOrDefault("connectionEdgeCoincidence", "pass")));
        }
        int tier2 = Math.max(Math.max(Math.max(Math.max(Math.max(Math.max(
                ratingLevel(breakdown.getOrDefault("nonOrthogonalTerminals", "pass")),
                ratingLevel(breakdown.getOrDefault("connectionEdgeCoincidence", "pass"))),
                ratingLevel(breakdown.getOrDefault("hubPortQuality", "pass"))),
                ratingLevel(breakdown.getOrDefault("labelOverlaps", "pass"))),
                ratingLevel(breakdown.getOrDefault("labelTruncations", "pass"))),
                ratingLevel(breakdown.getOrDefault("nonOrthogonalInteriorSegments", "pass"))),
                ratingLevel(breakdown.getOrDefault("offFaceParallelTerminals", "pass")));
        // Tier 3R (cap good=1): edge crossings and connection-through-note share the band by
        // Math.max — connectionThroughNote is binary-good, so it nudges routing to good at worst.
        int tier3 = Math.max(
                ratingLevel(breakdown.getOrDefault("edgeCrossings", "pass")),
                ratingLevel(breakdown.getOrDefault("connectionThroughNote", "pass")));

        int level = tier1;
        level = Math.max(level, Math.min(tier2, 2));
        level = Math.max(level, Math.min(tier3, 1));
        return level;
    }

    /** Maps level (0..3) to rating string. 0 = excellent, 3 = poor. */
    private static String levelToRating(int level) {
        return switch (level) {
            case 0 -> "excellent";
            case 1 -> "good";
            case 2 -> "fair";
            default -> "poor";
        };
    }

    private int ratingLevel(String rating) {
        return switch (rating) {
            case "pass", "excellent" -> 0;
            case "good" -> 1;
            case "fair" -> 2;
            case "poor" -> 3;
            default -> 2; // unknown ratings treated conservatively as "fair"
        };
    }

    // ---- Non-Orthogonal Terminal Detection (M1 corrected post-clip) ----

    /**
     * Result of non-orthogonal terminal detection.
     *
     * <p>The flagged population is PARTITIONED into two disjoint subsets at the moment of
     * flagging, and every count below is derived from those subsets rather than from a
     * subtraction. A connection whose path holds exactly two points is a straight line between
     * two element centres — the ELK auto-layout signature — and belongs to
     * {@code zeroBendpointViolatorIds}; anything longer carries a stored route and belongs to
     * {@code routedViolatorIds}. The distinction is load-bearing because the two subsets have
     * OPPOSITE remedies: a zero-bendpoint connection has no routed body to preserve, while
     * re-routing the routed subset wholesale is exactly what the zero-bendpoint remedy warns
     * against.</p>
     *
     * <p>Classifying in both terminal branches is what makes the split trustworthy. The source
     * and target branches guard against DIFFERENT rectangles, so a two-point diagonal can be
     * suppressed on the source side and flagged on the target side; deriving the routed subset by
     * subtracting the zero-bendpoint count from the total would file such a connection as routed
     * and send the agent to re-route a route that does not exist.</p>
     *
     * <p>{@code violatorIds} is the UNION of the two subsets and keeps its whole-population
     * meaning. All three id sets are empty unless the caller asked for them; the counts are
     * reported either way.</p>
     */
    record NonOrthogonalTerminalResult(int count, Set<String> violatorIds, int zeroBendpointCount,
                                        Set<String> zeroBendpointViolatorIds,
                                        int routedCount, Set<String> routedViolatorIds) {}

    /**
     * Backwards-compatible delegating overload (no node lookup — falls back to geometric semantics).
     * Tests that don't pass synthetic nodes get pre-M1 behaviour for their unchanged paths.
     *
     * @deprecated Prefer the 3-arg overload for M1 post-clip correctness.
     */
    @Deprecated
    NonOrthogonalTerminalResult countNonOrthogonalTerminals(
            List<AssessmentConnection> connections, boolean collectViolatorIds) {
        return countNonOrthogonalTerminals(connections, List.of(), collectViolatorIds);
    }

    /**
     * Counts connections with at least one non-orthogonal terminal segment.
     *
     * <p><b>M1 corrected definition:</b> the terminal segment is
     * the portion of {@code [sourceAnchor → BP1]} (or {@code [BP_last → targetAnchor]}) that lies
     * <i>outside</i> the source/target element bounds. Archi clips connection rendering at the
     * perimeter; the geometric diagonal between an element-center sourceAnchor and an on-perimeter
     * BP1 has zero visible length and is therefore <b>not</b> counted as non-orthogonal.</p>
     *
     * <p>Implementation: when {@code path[1]} (or {@code path[size-2]}) lies on the perimeter or
     * strictly inside the source (or target) element rect, the visible segment is zero or
     * invisible — skip the non-orth flag. Otherwise apply the existing geometric test on
     * {@code [path[0], path[1]]}: the post-clip segment lies on the same line as the geometric
     * one, so its orthogonality angle is invariant.</p>
     *
     * <p><b>M1 minimum-visible-length guard (2026-04-27):</b>
     * when the visible (post-clip) segment length is &lt; {@link #VISIBLE_DIAGONAL_MIN_PX}, the
     * diagonal is sub-perceptible at typical Archi zoom levels and is silently skipped. This
     * calibrates the metric against hand-routed views (manual oracle
     * {@code id-3b2665e3ff6840708dbed2b3d1415613}) where Archi commonly stores manually-routed
     * BPs 1px off the perimeter face line, producing a 1.0–1.3px visible diagonal that the
     * geometric angle test would otherwise flag. The guard is purely additive — connections
     * with longer visible diagonals (e.g. the V4 manual oracle's APIM→CorpBank case at 320px
     * visible length) remain flagged because the diagonal IS visible to the user.</p>
     *
     * <p>When {@code layoutNodes} is empty or the source/target node cannot be resolved by ID,
     * BOTH the M1 perimeter check AND the visible-length guard collapse to no-ops:
     * {@link #isOnOrInsideElement} returns {@code false} for null elem (so the perimeter-skip
     * never fires) and {@link #visibleSegmentLength} returns {@link Double#POSITIVE_INFINITY}
     * for null elem (so the {@code >= VISIBLE_DIAGONAL_MIN_PX} guard always passes). The
     * conjunction then collapses to the legacy geometric test path. This preserves
     * backwards-compatibility for the @Deprecated 2-arg overload — note that the {@code +∞}
     * return for {@code visibleSegmentLength(null elem)} is load-bearing: returning {@code 0}
     * would make every legacy diagonal flag silently suppressed (round-1 regression on 9
     * pre-existing tests, fixed in round-2).</p>
     *
     * @param connections      connection paths to evaluate
     * @param layoutNodes      lookup for source/target rectangles (may be empty for legacy callers)
     * @param collectViolatorIds when true, populates the union violator set and both subset sets
     */
    NonOrthogonalTerminalResult countNonOrthogonalTerminals(
            List<AssessmentConnection> connections, List<AssessmentNode> layoutNodes,
            boolean collectViolatorIds) {
        Map<String, AssessmentNode> nodeById = new HashMap<>();
        for (AssessmentNode n : layoutNodes) {
            nodeById.put(n.id(), n);
        }
        // The two subsets are built unconditionally: they are what the counts are derived from,
        // so they cannot be gated on the caller wanting the ids. Only their EXPOSURE is gated.
        Set<String> zeroBpIds = new HashSet<>();
        Set<String> routedIds = new HashSet<>();
        for (AssessmentConnection conn : connections) {
            List<double[]> path = conn.pathPoints();
            if (path.size() < 2) continue;
            AssessmentNode source = nodeById.get(conn.sourceNodeId());
            AssessmentNode target = nodeById.get(conn.targetNodeId());

            // Source terminal — M1: skip when path[1] is on or inside source rect,
            // OR when the visible post-clip segment
            // is below the perceptibility threshold.
            boolean sourceVisibleNonOrth = false;
            if (!isOnOrInsideElement(path.get(1), source)
                    && isNonOrthogonal(path.get(0), path.get(1))
                    && visibleSegmentLength(path.get(0), path.get(1), source)
                            >= VISIBLE_DIAGONAL_MIN_PX) {
                sourceVisibleNonOrth = true;
            }
            if (sourceVisibleNonOrth) {
                // zero-bendpoint = 2-point path (source center + target center, no intermediate BPs)
                (path.size() == 2 ? zeroBpIds : routedIds).add(conn.id());
                continue;
            }
            // Target terminal — M1: skip when path[size-2] is on or inside target rect,
            // OR when the visible post-clip segment
            // is below the perceptibility threshold. Note the helper's anchor argument
            // is the target-side element-center (path[last]), bp is the outside BP
            // (path[last - 1]) — the convention is anchor=inside, bp=outside.
            int last = path.size() - 1;
            if (!isOnOrInsideElement(path.get(last - 1), target)
                    && isNonOrthogonal(path.get(last - 1), path.get(last))
                    && visibleSegmentLength(path.get(last), path.get(last - 1), target)
                            >= VISIBLE_DIAGONAL_MIN_PX) {
                // Classify here too. Reaching this branch on a 2-point path is NOT a
                // contradiction of the source branch above: the two guards clip against
                // different rectangles, so the source side can suppress a diagonal that the
                // target side flags. Such a path still carries no bendpoints.
                (path.size() == 2 ? zeroBpIds : routedIds).add(conn.id());
            }
        }
        Set<String> unionIds = new HashSet<>(zeroBpIds);
        unionIds.addAll(routedIds);
        return new NonOrthogonalTerminalResult(
                unionIds.size(),
                collectViolatorIds ? unionIds : Set.of(),
                zeroBpIds.size(),
                collectViolatorIds ? zeroBpIds : Set.of(),
                routedIds.size(),
                collectViolatorIds ? routedIds : Set.of());
    }

    /** Result of off-face parallel-terminal detection (informational; no rating impact). */
    record OffFaceParallelTerminalResult(int count, List<String> descriptions, Set<String> violatorIds) {}

    /**
     * Counts connections whose terminal route DEPARTS an element face and then immediately runs
     * <b>parallel to and hugging</b> that same face — the first exterior segment travels along the
     * departed face's axis with a perpendicular clearance below {@link #OFF_FACE_MIN_STUB_PX}.
     *
     * <p>This closes a blind spot in {@link #countNonOrthogonalTerminals}, which angle-tests only the
     * raw element-center → first-bendpoint stub. When a connection exits a face a fraction of a pixel
     * off-perimeter and turns to run parallel just below it, that exit stub is a sub-perceptible
     * diagonal suppressed by the {@link #VISIBLE_DIAGONAL_MIN_PX} guard, so the terminal detector sees
     * nothing — yet the parallel hugging trunk is plainly visible to a reader. This detector measures
     * the exit against the FACE THE ROUTE DEPARTS (via {@link #inferTerminalSlot}, which resolves the
     * face even when the bendpoint sits up to a pixel off the perimeter), not the raw segment angle.
     *
     * <p>Per connection (a route hugging a face at either terminal counts once); each offending
     * terminal contributes one description, so a both-ends offender yields one count and two
     * descriptions.
     *
     * <p><b>Rating-bearing.</b> The count enters the breakdown as {@code offFaceParallelTerminals}
     * and joins the routing Tier-2 chain, where <em>presence</em> rather than magnitude decides: any
     * nonzero count caps the routing tier at {@code fair} and can never drive it to {@code poor}.
     * Ratio-bucketing was rejected because a low hug-per-connection ratio would still read
     * {@code good} and defeat the point of detecting the hug at all. An earlier revision of this
     * javadoc described the count as informational only and never fed into the rating; that stopped
     * being true when the breakdown entry was added, and both the glossary and the published
     * {@code assess-layout} description already describe it as rating-bearing. What this count does
     * leave untouched is the separately rating-bearing {@code nonOrthogonalTerminalCount} and its
     * visible-length calibration — the two are distinct breakdown entries.
     *
     * <p>Disjoint in purpose from {@link #countNonOrthogonalTerminals} (raw terminal-segment angle)
     * and {@link #countNonOrthogonalInteriorSegments} (off-cardinal mid segments); the routing tier
     * combines the family with {@code Math.max}, so a connection tripping more than one of them is
     * capped once.
     *
     * <p>The {@link #OFF_FACE_MIN_STUB_PX} clearance is what separates a hugging exit from a legitimate
     * short orthogonal jog: a first segment that turns to run parallel within that many pixels of the
     * departed face reads as stuck to it, whereas a larger perpendicular departure reads as a clean
     * corner. The threshold is calibrated against rendered views.
     *
     * @see #countNonOrthogonalTerminals(List, List, boolean)
     * @see #inferTerminalSlot(double[], AssessmentNode)
     */
    OffFaceParallelTerminalResult countOffFaceParallelTerminals(
            List<AssessmentConnection> connections, List<AssessmentNode> layoutNodes,
            boolean collectViolatorIds) {
        Map<String, AssessmentNode> nodeById = new HashMap<>();
        for (AssessmentNode n : layoutNodes) {
            nodeById.put(n.id(), n);
        }
        int count = 0;
        List<String> descriptions = new ArrayList<>();
        Set<String> violatorIds = collectViolatorIds ? new HashSet<>() : Set.of();
        for (AssessmentConnection conn : connections) {
            List<double[]> path = conn.pathPoints();
            // Need a center, a first bendpoint, AND a trunk segment beyond it: at least 3 points.
            if (path.size() < 3) continue;
            int last = path.size() - 1;
            AssessmentNode source = nodeById.get(conn.sourceNodeId());
            AssessmentNode target = nodeById.get(conn.targetNodeId());

            // Source terminal: center=path[0], first bendpoint=path[1], trunk=path[1]→path[2].
            String srcDesc = describeOffFaceParallelTerminal(
                    conn, path.get(1), path.get(2), source, "source", connections, layoutNodes);
            // Target terminal: center=path[last], first bendpoint=path[last-1], trunk=path[last-1]→path[last-2].
            String tgtDesc = describeOffFaceParallelTerminal(
                    conn, path.get(last - 1), path.get(last - 2), target, "target", connections, layoutNodes);

            if (srcDesc != null || tgtDesc != null) {
                count++;
                if (collectViolatorIds) {
                    violatorIds.add(conn.id());
                }
                if (srcDesc != null && descriptions.size() < MAX_DESCRIPTIONS) {
                    descriptions.add(srcDesc);
                }
                if (tgtDesc != null && descriptions.size() < MAX_DESCRIPTIONS) {
                    descriptions.add(tgtDesc);
                }
            }
        }
        return new OffFaceParallelTerminalResult(count, descriptions, violatorIds);
    }

    /**
     * Evaluates one terminal of a connection for the off-face parallel hug. {@code bp} is the first
     * bendpoint outside the element (the exit point); {@code trunkEnd} is the next path point (so
     * {@code [bp, trunkEnd]} is the first exterior segment). Returns a description when the segment
     * runs parallel to the departed face within {@link #OFF_FACE_MIN_STUB_PX} of it, else null.
     * Returns null when {@code elem} is null (no face to measure against) or the departure face
     * cannot be resolved.
     *
     * <p>The remedy the description prescribes depends on the corridor beside the hug (measured
     * offline from {@code connections} / {@code layoutNodes} via {@link #offFaceLiftClearance}). When
     * even the local corridor is narrower than {@link #HEALTHY_PARALLEL_GAP_PX}, a healthy lift is
     * impossible → confident layout remedy (widen the corridor). Otherwise the assessor cannot know
     * offline whether the router will keep the lift or decline it for a view-wide reason a local
     * measurement cannot see, so it defers to {@code auto-route-connections} (which reports the
     * authoritative layout-bound signal) rather than promising a perpendicular re-route. Either remedy
     * also names the contested-hub-face lever (spread the connections) when the departed face carries
     * ≥2 connections ({@link #countConnectionTerminalsOnFace}), since widening a shared face's corridor
     * only re-crowds it on re-route. This branches the <em>text only</em>; the returned-or-null
     * decision, the caller's count, and the rating are unaffected.</p>
     */
    private String describeOffFaceParallelTerminal(
            AssessmentConnection conn, double[] bp, double[] trunkEnd, AssessmentNode elem,
            String side, List<AssessmentConnection> connections, List<AssessmentNode> layoutNodes) {
        if (elem == null) return null;
        TerminalSlot slot = inferTerminalSlot(bp, elem);
        if (slot == null) return null; // interior / ambiguous — no departure face
        Face face = slot.face();

        double dx = Math.abs(trunkEnd[0] - bp[0]);
        double dy = Math.abs(trunkEnd[1] - bp[1]);
        if (dx < 1e-9 && dy < 1e-9) return null; // zero-length trunk — no direction

        boolean horizontalFace = (face == Face.TOP || face == Face.BOTTOM);
        // Parallel to the face axis: near-cardinal AND oriented along the face's parallel axis
        // (horizontal for TOP/BOTTOM, vertical for LEFT/RIGHT). isNonOrthogonal already rejects
        // diagonals; the dx/dy comparison fixes the orientation.
        boolean parallel = !isNonOrthogonal(bp, trunkEnd)
                && (horizontalFace ? dx >= dy : dy >= dx);
        if (!parallel) return null;

        // Perpendicular clearance from the departed face LINE to the (parallel) trunk.
        double faceLine = switch (face) {
            case TOP -> elem.y();
            case BOTTOM -> elem.y() + elem.height();
            case LEFT -> elem.x();
            case RIGHT -> elem.x() + elem.width();
        };
        double stub = horizontalFace ? Math.abs(bp[1] - faceLine) : Math.abs(bp[0] - faceLine);
        if (stub >= OFF_FACE_MIN_STUB_PX) return null;

        String prefix = "Connection '" + conn.id() + "' " + conn.sourceNodeId() + " → "
                + conn.targetNodeId()
                + ": " + side + " terminal departs the " + face + " face then runs parallel to it"
                + " (" + String.format(java.util.Locale.ROOT, "%.1f", stub) + "px clearance, below "
                + String.format(java.util.Locale.ROOT, "%.0f", OFF_FACE_MIN_STUB_PX)
                + "px) — the route hugs the face it just exited";

        // Remedy honesty. The router lifts a hug off its face only when doing so keeps a healthy
        // parallel-connection gap. This assessor does not run the router, so it cannot state with
        // certainty that a re-route WILL clear the hug — the router's decision also depends on
        // view-wide gaps a local measurement cannot see. So:
        //   - When even the local corridor beside the hug is narrower than the router's floor, a
        //     healthy lift is impossible regardless of view-wide state → confidently prescribe the
        //     layout remedy (widen the corridor).
        //   - Otherwise the hug MIGHT be liftable, or might still be declined for a view-wide reason
        //     → defer to auto-route-connections, which reports the authoritative layout-bound signal
        //     (a rolled-back egress lift) rather than over-promising a perpendicular re-route.
        // A face shared by several connections is a contested hub face: widening the corridor alone
        // only relocates the crowding (the re-route re-piles the terminals onto the same face). When
        // ≥2 connections terminate on this face, name the durable remedy — spread them across the
        // element's other faces — so the advice does not over-promise a plain spacing bump.
        String spread = countConnectionTerminalsOnFace(elem, face, connections) >= 2
                ? " (this face carries several connections, so widening alone will just re-crowd it — "
                        + "spread them across the element's other faces, or give the element more room "
                        + "so its face ports separate)"
                : "";

        double liftRoom = offFaceLiftClearance(bp, trunkEnd, elem, face, conn, connections, layoutNodes);
        if (liftRoom < HEALTHY_PARALLEL_GAP_PX) {
            return prefix + "; the parallel corridor beside it is only "
                    + String.format(java.util.Locale.ROOT, "%.1f", liftRoom) + "px wide (a healthy "
                    + "lift needs " + String.format(java.util.Locale.ROOT, "%.0f", HEALTHY_PARALLEL_GAP_PX)
                    + "px), so re-routing cannot clear it — widen the corridor (increase element "
                    + "spacing on the crowded side)" + spread + " and re-route.";
        }
        return prefix + "; run auto-route-connections to lift it clear. If that declines the lift as "
                + "layout-bound (a rolled-back egress lift, warning EGRESS_LIFT_LAYOUT_BOUND), the "
                + "corridor cannot take the lift without crowding a parallel connection — increase "
                + "element spacing here instead" + spread + "; re-routing alone will not clear it.";
    }

    /**
     * Counts how many connection terminals land on {@code face} of {@code elem} — the fan-in on that
     * face. A count ≥ 2 marks a contested hub face where widening the corridor merely re-crowds the
     * same face on re-route, so the off-face remedy points at spreading the connections instead of a
     * plain spacing bump. Reuses {@link #inferTerminalSlot} on each connection's first exterior
     * bendpoint (source {@code path[1]} / target {@code path[size-2]}), the same terminals the hug
     * detector evaluates.
     */
    private int countConnectionTerminalsOnFace(AssessmentNode elem, Face face,
            List<AssessmentConnection> connections) {
        if (connections == null) {
            return 0;
        }
        int count = 0;
        for (AssessmentConnection c : connections) {
            List<double[]> path = c.pathPoints();
            if (path == null || path.size() < 2) {
                continue;
            }
            if (elem.id().equals(c.sourceNodeId())) {
                TerminalSlot s = inferTerminalSlot(path.get(1), elem);
                if (s != null && s.face() == face) {
                    count++;
                }
            }
            if (elem.id().equals(c.targetNodeId())) {
                TerminalSlot s = inferTerminalSlot(path.get(path.size() - 2), elem);
                if (s != null && s.face() == face) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Perpendicular room (px) available to lift a hugging terminal off {@code face} of {@code elem},
     * measured offline from the departed face line in the push direction (outward from the element)
     * to the nearest co-axial obstacle (another element's near edge) or co-axial connection run
     * whose extent overlaps the trunk's parallel span. Approximates the room the routing pass checks
     * before it commits an off-face egress lift: below {@link #HEALTHY_PARALLEL_GAP_PX} the router
     * declines the lift, making the hug layout-bound. The assessor never runs the router, so this is
     * a description-only proxy — it does not affect the count or the rating. Returns
     * {@link Double#POSITIVE_INFINITY} when nothing bounds the push. Reuses the parallelConnectionGap
     * segment model ({@code axis 0} = vertical run, {@code axis 1} = horizontal run).
     */
    private double offFaceLiftClearance(double[] bp, double[] trunkEnd, AssessmentNode elem, Face face,
            AssessmentConnection self, List<AssessmentConnection> connections,
            List<AssessmentNode> layoutNodes) {
        boolean horizontalFace = (face == Face.TOP || face == Face.BOTTOM);
        double faceLine = switch (face) {
            case TOP -> elem.y();
            case BOTTOM -> elem.y() + elem.height();
            case LEFT -> elem.x();
            case RIGHT -> elem.x() + elem.width();
        };
        // Push away from the element along the perpendicular axis; pushSign points outward.
        double bpPerp = horizontalFace ? bp[1] : bp[0];
        double pushSign = Math.signum(bpPerp - faceLine);
        if (pushSign == 0.0) {
            pushSign = (face == Face.TOP || face == Face.LEFT) ? -1.0 : 1.0;
        }
        // Trunk's span along the (parallel) face axis.
        double spanLow;
        double spanHigh;
        if (horizontalFace) {
            spanLow = Math.min(bp[0], trunkEnd[0]);
            spanHigh = Math.max(bp[0], trunkEnd[0]);
        } else {
            spanLow = Math.min(bp[1], trunkEnd[1]);
            spanHigh = Math.max(bp[1], trunkEnd[1]);
        }
        double clearance = Double.POSITIVE_INFINITY;

        // Nearest co-axial obstacle: another element whose parallel extent overlaps the trunk span
        // and whose near edge sits on the push side of the face line.
        if (layoutNodes != null) {
            for (AssessmentNode n : layoutNodes) {
                if (n == null || n.isNote() || n.id().equals(elem.id())) continue;
                double loPar;
                double hiPar;
                double nearEdge;
                if (horizontalFace) {
                    loPar = n.x();
                    hiPar = n.x() + n.width();
                    nearEdge = pushSign < 0 ? n.y() + n.height() : n.y();
                } else {
                    loPar = n.y();
                    hiPar = n.y() + n.height();
                    nearEdge = pushSign < 0 ? n.x() + n.width() : n.x();
                }
                if (Math.min(spanHigh, hiPar) - Math.max(spanLow, loPar) <= 0) continue;
                double d = pushSign * (nearEdge - faceLine);
                // >= 0 (not > 0): a neighbour flush on the face line is a 0px corridor, the tightest
                // possible — treat it as clearance 0, not "no neighbour" (mirrors computeAxisAggregates).
                if (d >= 0) clearance = Math.min(clearance, d);
            }
        }

        // Nearest co-axial connection run: another connection's axis-aligned segment that is parallel
        // to the trunk, overlaps its span, and sits on the push side. The hug's own connection is
        // excluded — the router protects NEIGHBOURING runs, not the segment being lifted.
        if (connections != null) {
            List<ParallelGapSegment> vSegs = new ArrayList<>();
            List<ParallelGapSegment> hSegs = new ArrayList<>();
            for (AssessmentConnection c : connections) {
                // Exclude the hug's own connection by identity first (robust even if ids are null),
                // then by id — the router protects NEIGHBOURING runs, not the segment being lifted.
                if (c == self || (c.id() != null && c.id().equals(self.id()))) continue;
                extractParallelGapSegments(c, vSegs, hSegs);
            }
            // horizontal trunk (horizontal face) is an H segment (axis 1); vertical trunk is V (axis 0)
            List<ParallelGapSegment> coaxial = horizontalFace ? hSegs : vSegs;
            for (ParallelGapSegment s : coaxial) {
                if (Math.min(spanHigh, s.spanHigh()) - Math.max(spanLow, s.spanLow()) <= 0) continue;
                double d = pushSign * (s.fixedCoord() - faceLine);
                if (d > 0) clearance = Math.min(clearance, d);
            }
        }
        return clearance;
    }

    /**
     * M1 helper: returns true if the bendpoint lies on the perimeter line of the element
     * (within {@link #PERIMETER_TOLERANCE_PX}px) or strictly inside the element's bounding rect.
     * When {@code elem} is null, returns false (caller falls back to legacy geometric test).
     *
     * <p>The "perimeter line" is the literal element edge (LEFT: x=elem.x; RIGHT: x=elem.x+w;
     * TOP: y=elem.y; BOTTOM: y=elem.y+h) — this matches the spec example where Archi stores
     * a bendpoint at (641, 259) on an element whose LEFT face is x=641. {@code RoutingPipeline}
     * uses a different 1px-offset convention internally (Layer 3 sub-package) — those are
     * deliberately distinct by design (inline duplicate, no Layer-3 cross-coupling).</p>
     */
    static boolean isOnOrInsideElement(double[] bp, AssessmentNode elem) {
        if (elem == null) return false;
        double x = bp[0];
        double y = bp[1];
        double left = elem.x();
        double right = elem.x() + elem.width();
        double top = elem.y();
        double bottom = elem.y() + elem.height();
        double tol = PERIMETER_TOLERANCE_PX;
        return x >= left - tol && x <= right + tol
                && y >= top - tol && y <= bottom + tol;
    }

    /**
     * M1 helper (2026-04-27): returns the Euclidean
     * length of the visible (post-clip) portion of segment {@code [anchor, bp]} against
     * {@code elem}. The visible portion runs from the perimeter clip-point to {@code bp}.
     *
     * <p>Convention: {@code anchor} is the element-center side of the terminal segment
     * (always strictly inside {@code elem} for ChopboxAnchor source/target anchors);
     * {@code bp} is the bendpoint outside the element. Caller already guards via
     * {@link #isOnOrInsideElement} on {@code bp} — this helper short-circuits to 0
     * when the guard's invariant ever fails (defense-in-depth).</p>
     *
     * <p><b>Null-elem semantics:</b> returns {@link Double#POSITIVE_INFINITY} when
     * {@code elem} is null. This is the legacy 2-arg overload path (no node lookup
     * available) — the +∞ return makes the {@code >= VISIBLE_DIAGONAL_MIN_PX} guard
     * in {@code countNonOrthogonalTerminals} a no-op, correctly collapsing to the
     * legacy geometric-only test. Returning 0 here would incorrectly suppress every
     * legacy non-orth flag.</p>
     *
     * @param anchor inside-the-element endpoint of the segment (typically the element center)
     * @param bp outside-the-element endpoint (typically the first/last bendpoint)
     * @param elem the source/target element rect (may be null for legacy callers)
     * @return visible segment length in pixels; {@code +∞} if elem null (legacy no-op);
     *         0 if bp on/inside elem (defense-in-depth — caller already short-circuits)
     */
    static double visibleSegmentLength(double[] anchor, double[] bp, AssessmentNode elem) {
        if (elem == null) return Double.POSITIVE_INFINITY;
        if (isOnOrInsideElement(bp, elem)) return 0.0;
        double[] clip = lineRectIntersection(anchor, bp, elem);
        if (clip == null) {
            // Degenerate fallback: anchor outside rect AND line misses rect entirely.
            // Return full segment length so M1 still flags long diagonals — the visible
            // segment, having no clip, IS the full segment.
            return Math.hypot(bp[0] - anchor[0], bp[1] - anchor[1]);
        }
        return Math.hypot(bp[0] - clip[0], bp[1] - clip[1]);
    }

    /**
     * M1 helper (2026-04-27): returns the perimeter
     * intersection point of segment {@code [a, b]} against rect {@code r}, or null if
     * the segment does not cross the perimeter at any {@code t} in {@code (0, 1]}.
     *
     * <p>For the standard M1 use case ({@code a} = element center, strictly inside;
     * {@code b} = outside), the segment crosses the perimeter exactly once and the
     * smallest valid {@code t} identifies the clip point. Algorithm: parametrize as
     * {@code P(t) = a + t * (b - a)}, intersect with each of the 4 face lines (skipping
     * any line whose normal is parallel to the segment), accept only intersections that
     * lie within the face's bounded extent, and return the smallest-{@code t} hit.</p>
     */
    static double[] lineRectIntersection(double[] a, double[] b, AssessmentNode r) {
        if (r == null) return null;
        double left = r.x();
        double right = r.x() + r.width();
        double top = r.y();
        double bottom = r.y() + r.height();
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];

        double bestT = Double.POSITIVE_INFINITY;
        double[] bestPoint = null;

        // LEFT face: x = left
        if (Math.abs(dx) > 1e-9) {
            double t = (left - a[0]) / dx;
            if (t > 1e-9 && t <= 1.0) {
                double y = a[1] + t * dy;
                if (y >= top && y <= bottom && t < bestT) {
                    bestT = t;
                    bestPoint = new double[]{left, y};
                }
            }
        }
        // RIGHT face: x = right
        if (Math.abs(dx) > 1e-9) {
            double t = (right - a[0]) / dx;
            if (t > 1e-9 && t <= 1.0) {
                double y = a[1] + t * dy;
                if (y >= top && y <= bottom && t < bestT) {
                    bestT = t;
                    bestPoint = new double[]{right, y};
                }
            }
        }
        // TOP face: y = top
        if (Math.abs(dy) > 1e-9) {
            double t = (top - a[1]) / dy;
            if (t > 1e-9 && t <= 1.0) {
                double x = a[0] + t * dx;
                if (x >= left && x <= right && t < bestT) {
                    bestT = t;
                    bestPoint = new double[]{x, top};
                }
            }
        }
        // BOTTOM face: y = bottom
        if (Math.abs(dy) > 1e-9) {
            double t = (bottom - a[1]) / dy;
            if (t > 1e-9 && t <= 1.0) {
                double x = a[0] + t * dx;
                if (x >= left && x <= right && t < bestT) {
                    bestT = t;
                    bestPoint = new double[]{x, bottom};
                }
            }
        }

        return bestPoint;
    }

    /**
     * M2 helper: returns true if the bendpoint lies <b>strictly inside</b> the element bounds —
     * NOT on the perimeter line and NOT outside. Strict inequalities (with a small tolerance to
     * exclude on-perimeter cases). When {@code elem} is null, returns false.
     */
    static boolean isStrictlyInside(double[] bp, AssessmentNode elem) {
        if (elem == null) return false;
        double tol = PERIMETER_TOLERANCE_PX;
        return bp[0] > elem.x() + tol
                && bp[0] < elem.x() + elem.width() - tol
                && bp[1] > elem.y() + tol
                && bp[1] < elem.y() + elem.height() - tol;
    }

    private boolean isNonOrthogonal(double[] p1, double[] p2) {
        double dx = Math.abs(p1[0] - p2[0]);
        double dy = Math.abs(p1[1] - p2[1]);
        if (dx < 1e-9 && dy < 1e-9) return false; // zero-length or near-zero segment
        // Angular detection — angle in [0°, 90°] quadrant
        double angleDeg = Math.toDegrees(Math.atan2(dy, dx));
        // Deviation from nearest cardinal axis (0° or 90°)
        double deviation = Math.min(angleDeg, 90.0 - angleDeg);
        return deviation > NON_ORTH_ANGLE_THRESHOLD;
    }

    // ---- M2: Interior-Termination Detection ----

    /** Result of M2 interior-termination detection. */
    record InteriorTerminationResult(int count, List<String> descriptions, Set<String> violatorIds) {}

    /**
     * Counts connections whose first or last bendpoint lies <b>strictly inside</b> the
     * source/target element bounds (M2). Strict inequalities — bendpoints on the perimeter
     * line do NOT count (those are handled by M1's post-clip definition). Connections with
     * fewer than 2 path points are skipped.
     *
     * <p>Spec live example: a connection whose {@code BP_last} coordinates fall inside the
     * target element rectangle indicates a routing failure where Archi's ChopboxAnchor face
     * selection couldn't resolve the terminal correctly. Tier 1R severity (peer with
     * passThroughs).</p>
     */
    InteriorTerminationResult countInteriorTerminations(
            List<AssessmentConnection> connections, List<AssessmentNode> layoutNodes,
            boolean collectViolatorIds) {
        Map<String, AssessmentNode> nodeById = new HashMap<>();
        for (AssessmentNode n : layoutNodes) {
            nodeById.put(n.id(), n);
        }
        int count = 0;
        List<String> descriptions = new ArrayList<>();
        Set<String> violatorIds = collectViolatorIds ? new HashSet<>() : Set.of();
        for (AssessmentConnection conn : connections) {
            List<double[]> path = conn.pathPoints();
            // Need at least source-center + one BP + target-center to assess a terminal BP.
            if (path.size() < 3) continue;
            AssessmentNode source = nodeById.get(conn.sourceNodeId());
            AssessmentNode target = nodeById.get(conn.targetNodeId());
            // path.get(0) = sourceAnchor (element center), path.get(size-1) = targetAnchor (element center).
            // The interior-termination signal is the FIRST BP after the source — path.get(1),
            // and the LAST BP before the target — path.get(size-2).
            boolean sourceInterior = isStrictlyInside(path.get(1), source);
            boolean targetInterior = isStrictlyInside(path.get(path.size() - 2), target);
            if (sourceInterior || targetInterior) {
                count++;
                String side = sourceInterior && targetInterior ? "source and target"
                        : sourceInterior ? "source" : "target";
                if (descriptions.size() < MAX_DESCRIPTIONS) {
                    descriptions.add("Connection '" + conn.id() + "' " + conn.sourceNodeId()
                            + " → " + conn.targetNodeId()
                            + ": interior termination on " + side);
                }
                if (collectViolatorIds) {
                    violatorIds.add(conn.id());
                }
            }
        }
        return new InteriorTerminationResult(count, descriptions, violatorIds);
    }

    // ---- M3: Zigzag / Reversal Detection ----

    /** Result of M3 zigzag detection. */
    record ZigzagResult(int count, List<String> descriptions, Set<String> violatorIds) {}

    /**
     * Counts connections containing at least one zigzag triple (M3). A zigzag is three
     * consecutive bendpoints {@code (bp_i, bp_{i+1}, bp_{i+2})} where either:
     * <ul>
     *   <li>all three share the same X within {@link #ZIGZAG_AXIS_TOLERANCE_PX} AND the Y-deltas
     *       between consecutive pairs have opposite signs both > {@link #ZIGZAG_MIN_DELTA_PX} in
     *       magnitude, OR</li>
     *   <li>symmetric: all three share the same Y AND X-deltas have opposite signs.</li>
     * </ul>
     * Counted as a binary defect per connection — one or more zigzag triples → +1 to count.
     *
     * <p>Spec live example: connection {@code id-3795e46e72a049e596b618b1ce948441} (API Mgmt →
     * Corporate Banking, Oracle C1) triple {@code (403,259) → (403,219) → (403,261)} flags
     * (x=403 shared, Δy = -40 then +42 — opposite signs both > 1px). Tier 1R severity.</p>
     *
     * <p>Classification precedence: connections whose IDs appear in {@code passThroughViolatorIds}
     * are skipped (passthrough takes precedence over zigzag) — the failed-detour-around-element
     * pattern produces a small reversal because the detour failed and passed through, and the
     * visually-correct label is passthrough-only.</p>
     *
     * <p><strong>{@code collectViolatorIds} does not gate this detector's violator set.</strong> The
     * set is a precedence skip-set consumed by {@link #countLateralJogReversals}, so emptying it when
     * the caller declines IDs would let one connection be counted under two reversal dimensions on
     * the rating-only path. The parameter is retained for signature symmetry with the sibling
     * detectors, and {@link #detectPassThroughs} — the first detector to become a skip-set — carries
     * the same now-ungated parameter for the same reason. The outer {@code assess} enrichment block
     * is what actually decides whether these IDs reach the response.
     *
     * @param collectViolatorIds retained for symmetry; does NOT gate the returned violator set
     * @see #detectPassThroughs(List, List, boolean)
     * @see #countLateralJogReversals(List, Set, Set, boolean)
     */
    ZigzagResult countZigzags(List<AssessmentConnection> connections,
                              Set<String> passThroughViolatorIds,
                              boolean collectViolatorIds) {
        Objects.requireNonNull(passThroughViolatorIds, "passThroughViolatorIds must not be null");
        int count = 0;
        List<String> descriptions = new ArrayList<>();
        // Violator IDs are collected unconditionally so the classification precedence guard in
        // countLateralJogReversals() works regardless of collectViolatorIds (which gates only the
        // outer assess() enrichment block) — the same reason detectPassThroughs() collects its own
        // unconditionally.
        Set<String> violatorIds = new HashSet<>();
        for (AssessmentConnection conn : connections) {
            if (passThroughViolatorIds.contains(conn.id())) continue;
            List<double[]> path = conn.pathPoints();
            if (path.size() < 3) continue;
            for (int i = 0; i < path.size() - 2; i++) {
                double[] a = path.get(i);
                double[] b = path.get(i + 1);
                double[] c = path.get(i + 2);
                if (isZigzagTriple(a, b, c)) {
                    count++;
                    if (descriptions.size() < MAX_DESCRIPTIONS) {
                        descriptions.add("Connection '" + conn.id() + "' " + conn.sourceNodeId()
                                + " → " + conn.targetNodeId() + ": zigzag/reversal at index " + i
                                + " (" + formatPoint(a) + " → " + formatPoint(b)
                                + " → " + formatPoint(c) + ")");
                    }
                    violatorIds.add(conn.id());
                    break; // binary defect per connection — one triple is enough
                }
            }
        }
        return new ZigzagResult(count, descriptions, violatorIds);
    }

    private static boolean isZigzagTriple(double[] a, double[] b, double[] c) {
        double tol = ZIGZAG_AXIS_TOLERANCE_PX;
        double minDelta = ZIGZAG_MIN_DELTA_PX;
        // Shared-X variant: all three points within tol of the same X, opposite-sign Y deltas.
        boolean sharedX = Math.abs(a[0] - b[0]) <= tol
                && Math.abs(b[0] - c[0]) <= tol;
        if (sharedX) {
            double dy1 = b[1] - a[1];
            double dy2 = c[1] - b[1];
            if (Math.abs(dy1) > minDelta && Math.abs(dy2) > minDelta
                    && Math.signum(dy1) != Math.signum(dy2)) {
                return true;
            }
        }
        // Shared-Y variant: symmetric.
        boolean sharedY = Math.abs(a[1] - b[1]) <= tol
                && Math.abs(b[1] - c[1]) <= tol;
        if (sharedY) {
            double dx1 = b[0] - a[0];
            double dx2 = c[0] - b[0];
            if (Math.abs(dx1) > minDelta && Math.abs(dx2) > minDelta
                    && Math.signum(dx1) != Math.signum(dx2)) {
                return true;
            }
        }
        return false;
    }

    private static String formatPoint(double[] p) {
        return "(" + Math.round(p[0]) + "," + Math.round(p[1]) + ")";
    }

    // ---- Anchor drift: a stored route whose geometry has moved underneath it ----

    /** Result of anchor-drift detection. */
    record AnchorDriftResult(int count, List<String> descriptions, Set<String> violatorIds) {}

    /**
     * Counts connections whose stored route was computed against a geometry that has since changed.
     *
     * <p>Archi stores every bendpoint twice — {@code startX/startY} relative to the source centre and
     * {@code endX/endY} relative to the target centre — and both describe the same absolute point at
     * the moment the route was written. Moving or resizing an endpoint afterwards leaves the stored
     * offsets untouched, so the two reconstructions drift apart by however far the endpoint travelled.
     * A connection flags when that disagreement exceeds {@link #ANCHOR_DRIFT_NOISE_FLOOR_PX} on
     * either axis. Binary per connection.
     *
     * <p>The drift itself is measured in {@code AssessmentCollector}, which is the last place it
     * exists: the collector blends the two reconstructions into one polyline, and every other
     * detector in this class is handed that blend. A drifted route is not a misshapen route — its
     * stored shape was correct when written — which is why no shape-based dimension can see it and
     * why the remedy is to re-route the connection rather than to straighten it.
     *
     * <p>What renders is not the blend, either. Archi weights each bendpoint by
     * {@code (i + 1) / (n + 1)} along the bendpoint list, so a drifted polyline is drawn
     * <em>sheared</em> — each point displaced by {@code (weight - 0.5) x drift}, most at the two
     * terminal bendpoints and least in the middle. That is why the terminal segments of a drifted
     * connection are the part a reader notices.
     *
     * @param collectViolatorIds if true, collects the offending connection IDs
     */
    AnchorDriftResult detectAnchorDrift(List<AssessmentConnection> connections,
                                        boolean collectViolatorIds) {
        int count = 0;
        List<String> descriptions = new ArrayList<>();
        Set<String> violatorIds = collectViolatorIds ? new LinkedHashSet<>() : Set.of();
        for (AssessmentConnection conn : connections) {
            double driftX = Math.abs(conn.anchorDriftX());
            double driftY = Math.abs(conn.anchorDriftY());
            if (driftX <= ANCHOR_DRIFT_NOISE_FLOOR_PX && driftY <= ANCHOR_DRIFT_NOISE_FLOOR_PX) {
                continue;
            }
            count++;
            if (descriptions.size() < MAX_DESCRIPTIONS) {
                descriptions.add("Connection '" + conn.id() + "' " + conn.sourceNodeId()
                        + " → " + conn.targetNodeId()
                        + ": stored route drifted from its anchors by "
                        + formatPx(driftX) + "px on x and " + formatPx(driftY) + "px on y"
                        + " (an endpoint moved or was resized after the route was written)");
            }
            if (collectViolatorIds) {
                violatorIds.add(conn.id());
            }
        }
        return new AnchorDriftResult(count, descriptions, violatorIds);
    }

    // ---- Lateral-jog reversal: two opposite arms joined by a sidestep too small to be a detour ----

    /** Result of lateral-jog-reversal detection. */
    record LateralJogReversalResult(int count, List<String> descriptions, Set<String> violatorIds) {}

    /**
     * Counts connections containing at least one <strong>lateral-jog reversal</strong> — a four-point
     * window {@code (a,b,c,d)} whose two outer arms run in opposite directions along the same axis,
     * separated by a perpendicular sidestep no wider than {@link #LATERAL_JOG_MAX_PX}. The route
     * doubles back through the corridor it just left, having stepped a few px aside first. Binary
     * per connection, matching {@link #countZigzags}' convention.
     *
     * <p>This is a genuinely different shape from a zigzag, not a zigzag under a looser tolerance.
     * {@link #countZigzags} tests three consecutive points for sharing <em>one</em> axis within
     * {@link #ZIGZAG_AXIS_TOLERANCE_PX}; the sidestep breaks every such triple, because the two arms
     * lie on two different parallel lines. Widening that tolerance to cover the jog would not express
     * this shape either — it would re-label legitimate few-px port offsets as reversals while still
     * describing a triple, when the pattern needs four points to state at all.
     *
     * <p>Classification precedence, applied in this order: a connection already reported by
     * {@code passThroughs} is skipped (the pass-through label is the visually correct one, as it is
     * for zigzags), then a connection already reported by {@code zigzags} is skipped, so a route is
     * never counted under two reversal dimensions. The remaining band is the one nothing else covers.
     *
     * @param passThroughViolatorIds pass-through violators, which take precedence
     * @param zigzagViolatorIds      zigzag violators, which take precedence
     * @param collectViolatorIds     if true, collects the offending connection IDs
     * @see #countZigzags(List, Set, boolean)
     */
    LateralJogReversalResult countLateralJogReversals(List<AssessmentConnection> connections,
                                                     Set<String> passThroughViolatorIds,
                                                     Set<String> zigzagViolatorIds,
                                                     boolean collectViolatorIds) {
        Objects.requireNonNull(passThroughViolatorIds, "passThroughViolatorIds must not be null");
        Objects.requireNonNull(zigzagViolatorIds, "zigzagViolatorIds must not be null");
        int count = 0;
        List<String> descriptions = new ArrayList<>();
        Set<String> violatorIds = collectViolatorIds ? new LinkedHashSet<>() : Set.of();
        for (AssessmentConnection conn : connections) {
            if (passThroughViolatorIds.contains(conn.id())) continue;
            if (zigzagViolatorIds.contains(conn.id())) continue;
            List<double[]> path = conn.pathPoints();
            if (path.size() < 4) continue;
            for (int i = 0; i < path.size() - 3; i++) {
                double[] a = path.get(i);
                double[] b = path.get(i + 1);
                double[] c = path.get(i + 2);
                double[] d = path.get(i + 3);
                if (isLateralJogReversal(a, b, c, d)) {
                    count++;
                    if (descriptions.size() < MAX_DESCRIPTIONS) {
                        descriptions.add("Connection '" + conn.id() + "' " + conn.sourceNodeId()
                                + " → " + conn.targetNodeId()
                                + ": lateral-jog reversal at index " + i
                                + " (" + formatExactPoint(a) + " → " + formatExactPoint(b)
                                + " → " + formatExactPoint(c) + " → " + formatExactPoint(d) + ")");
                    }
                    if (collectViolatorIds) {
                        violatorIds.add(conn.id());
                    }
                    break; // binary defect per connection — one window is enough
                }
            }
        }
        return new LateralJogReversalResult(count, descriptions, violatorIds);
    }

    /**
     * True when {@code (a,b,c,d)} is two opposite-direction arms on one axis joined by a
     * perpendicular sidestep that is real (non-zero — a zero jog is a duplicated point, which is the
     * redundant-bendpoint dimension's subject, not a reversal) and no wider than
     * {@link #LATERAL_JOG_MAX_PX}, above which the route is taking a deliberate detour.
     *
     * <p><strong>This predicate also consumes two of M3's constants</strong>, and deliberately so:
     * {@link #ZIGZAG_AXIS_TOLERANCE_PX} decides whether the arms and the jog are axis-aligned, and
     * {@link #ZIGZAG_MIN_DELTA_PX} is the minimum arm length below which an arm is noise rather than
     * travel. Both questions are identical to the ones M3 asks, so sharing the answers keeps the two
     * reversal dimensions calibrated together rather than drifting apart. The consequence is a real
     * coupling: retuning either constant for M3 retunes this detector too. Only
     * {@link #LATERAL_JOG_MAX_PX} is this dimension's own.
     */
    private static boolean isLateralJogReversal(double[] a, double[] b, double[] c, double[] d) {
        double tol = ZIGZAG_AXIS_TOLERANCE_PX;
        double minDelta = ZIGZAG_MIN_DELTA_PX;
        // Vertical arms joined by a horizontal jog.
        if (Math.abs(a[0] - b[0]) <= tol && Math.abs(b[1] - c[1]) <= tol
                && Math.abs(c[0] - d[0]) <= tol) {
            double jog = Math.abs(c[0] - b[0]);
            double arm1 = b[1] - a[1];
            double arm2 = d[1] - c[1];
            if (jog > 0.0 && jog <= LATERAL_JOG_MAX_PX
                    && Math.abs(arm1) > minDelta && Math.abs(arm2) > minDelta
                    && Math.signum(arm1) != Math.signum(arm2)) {
                return true;
            }
        }
        // Horizontal arms joined by a vertical jog: symmetric.
        if (Math.abs(a[1] - b[1]) <= tol && Math.abs(b[0] - c[0]) <= tol
                && Math.abs(c[1] - d[1]) <= tol) {
            double jog = Math.abs(c[1] - b[1]);
            double arm1 = b[0] - a[0];
            double arm2 = d[0] - c[0];
            if (jog > 0.0 && jog <= LATERAL_JOG_MAX_PX
                    && Math.abs(arm1) > minDelta && Math.abs(arm2) > minDelta
                    && Math.signum(arm1) != Math.signum(arm2)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Renders a point without discarding a half-pixel, unlike {@link #formatPoint}, which rounds to
     * the nearest integer.
     *
     * <p>An element centre is {@code x + width / 2}, so a reconstructed coordinate is half-integral
     * whenever the box dimension is odd — and that half pixel is load-bearing here: it is the same
     * representable-precision effect {@link #ANCHOR_DRIFT_NOISE_FLOOR_PX} is derived from. Rounding
     * {@code 467.5} to {@code 468} would report a coordinate the model does not hold, in a field
     * whose whole purpose is to carry the offending window as fact rather than as a flag.
     *
     * <p>{@link #formatPoint} keeps its rounding: it is shared by the older detectors, whose emitted
     * descriptions are pinned, and widening it would change their output for no gain.
     */
    private static String formatExactPoint(double[] p) {
        return "(" + formatPx(p[0]) + "," + formatPx(p[1]) + ")";
    }

    /** Renders a px measurement without a trailing {@code .0} on whole values. */
    private static String formatPx(double v) {
        return v == Math.rint(v) && !Double.isInfinite(v)
                ? String.valueOf((long) v)
                : String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    // ---- Redundant (collinear / removable) bendpoint detection ----

    /** Result of redundant-bendpoint detection. */
    record RedundantBendpointResult(int count, List<String> descriptions, Set<String> violatorIds) {}

    /**
     * Counts bendpoints that are <strong>redundant</strong> — collinear with their two
     * neighbours and lying between them, so deleting the point leaves the polyline visually
     * identical. A route littered with such points reads as noisy/over-engineered even though
     * every segment is orthogonal; this surfaces the "many unnecessary bendpoints" class.
     *
     * <p>For each interior point {@code b = path[i]} (its neighbours {@code a = path[i-1]},
     * {@code c = path[i+1]}) the point is redundant when BOTH hold:
     * <ul>
     *   <li><b>axis-aligned collinear:</b> the triple {@code a,b,c} lies within
     *       {@link #REDUNDANT_BENDPOINT_AXIS_COLLINEAR_EPSILON_PX} of a HORIZONTAL or VERTICAL
     *       line (thinner bounding-box extent ≤ ε). A point that is only <em>diagonally</em>
     *       near-collinear is a real orthogonal corner whose removal would diagonalise the route —
     *       the router keeps it, so it is not redundant; and</li>
     *   <li><b>between:</b> {@code b} lies within the axis-aligned bounding box of
     *       {@code a} and {@code c}, widened by ε on both axes for the same reconstruction-noise
     *       slack. Overshoots greater than ε are still rejected as out-and-back spikes; a sub-ε
     *       overshoot is within the noise floor and treated as redundant.</li>
     * </ul>
     * The "between" guard is load-bearing: a same-axis out-and-back spike (collinear but
     * overshooting a neighbour) is NOT redundant — removing it WOULD change the shape, and it
     * is the zigzag detector's concern, not this one.
     *
     * <p>Counting is <b>per redundant bendpoint</b> (a connection with three collinear kinks
     * contributes 3), unlike the binary-per-connection zigzag count, because the signal of
     * interest is the aggregate clutter. Every connection is evaluated — redundancy is an
     * intrinsic geometric property, independent of the pass-through/zigzag classification
     * precedence. <b>INFORMATIONAL ONLY</b> — the result is never fed into the rating.
     *
     * <p><b>Terminal egress-stub exclusion (node-aware overload).</b> A connection's
     * {@code pathPoints} are {@code [srcCenter, …bendpoints…, tgtCenter]}, so the FIRST triple
     * {@code (srcCenter, path[1], path[2])} and the LAST triple {@code (path[n-3], path[n-2],
     * tgtCenter)} straddle a terminal <em>egress port</em> — the first/last stored bendpoint. When
     * that port sits on its element's perimeter face (see {@link #isOnPerimeterFace}), it is a
     * router-pinned terminal anchor: the router keeps it to hold each connection to its distinct
     * along-face slot (terminal-anchoring / hub-port-distribution invariant), so it is NOT safely
     * removable and re-running auto-route will not drop it. The node-aware overload
     * {@link #countRedundantBendpoints(List, List, boolean)} therefore does not count such a port.
     * The discriminator is <em>geometric, not positional</em>: a first/last-window bendpoint that
     * lies OFF any face (e.g. far outside both boxes on a straight centre-to-centre line) is a
     * genuine interior-removable point and STILL counts — the face test, not the window index, is
     * what excludes. The legacy 2-arg overload supplies no nodes, so it applies no exclusion and
     * behaves exactly as before.
     *
     * @see #countZigzags(List, Set, boolean)
     * @see #isOnPerimeterFace(double[], AssessmentNode)
     */
    RedundantBendpointResult countRedundantBendpoints(List<AssessmentConnection> connections,
                                                      boolean collectViolatorIds) {
        // Backward-compatible overload: no element nodes → no face resolution → no terminal
        // egress-stub exclusion, so the count is exactly the raw collinear-bendpoint tally.
        return countRedundantBendpoints(connections, List.of(), collectViolatorIds);
    }

    /**
     * Node-aware variant of {@link #countRedundantBendpoints(List, boolean)} that excludes
     * router-pinned terminal egress-stub ports (a first/last bendpoint on its element's perimeter
     * face) from the count. See that method's javadoc for the exclusion rationale.
     */
    RedundantBendpointResult countRedundantBendpoints(List<AssessmentConnection> connections,
                                                      List<AssessmentNode> layoutNodes,
                                                      boolean collectViolatorIds) {
        Map<String, AssessmentNode> nodeById = new HashMap<>();
        for (AssessmentNode n : layoutNodes) {
            nodeById.put(n.id(), n);
        }
        int count = 0;
        List<String> descriptions = new ArrayList<>();
        Set<String> violatorIds = collectViolatorIds ? new HashSet<>() : Set.of();
        for (AssessmentConnection conn : connections) {
            List<double[]> path = conn.pathPoints();
            if (path.size() < 3) continue;
            AssessmentNode source = nodeById.get(conn.sourceNodeId());
            AssessmentNode target = nodeById.get(conn.targetNodeId());
            for (int i = 0; i < path.size() - 2; i++) {
                double[] a = path.get(i);
                double[] b = path.get(i + 1);
                double[] c = path.get(i + 2);
                if (isRedundantBendpoint(a, b, c)) {
                    // Terminal egress-stub exclusion: b is the source port (first window) or the
                    // target port (last window) AND sits on that element's perimeter face — a
                    // router-pinned terminal anchor, not a removable interior kink. Skip it. For a
                    // size-3 path i==0 and i==size-3 coincide, so either face excludes the lone port.
                    if (i == 0 && isOnPerimeterFace(b, source)) continue;
                    if (i == path.size() - 3 && isOnPerimeterFace(b, target)) continue;
                    count++;
                    if (descriptions.size() < MAX_DESCRIPTIONS) {
                        descriptions.add("Connection '" + conn.id() + "' " + conn.sourceNodeId()
                                + " → " + conn.targetNodeId() + ": redundant bendpoint at index "
                                + (i + 1) + " (" + formatPoint(a) + " → " + formatPoint(b)
                                + " → " + formatPoint(c) + ")");
                    }
                    if (collectViolatorIds) {
                        violatorIds.add(conn.id());
                    }
                }
            }
        }
        return new RedundantBendpointResult(count, descriptions, violatorIds);
    }

    /**
     * Whether a bendpoint sits on an element's perimeter face — i.e. it is a terminal egress port
     * pinned by the router (terminal-anchoring / hub-port-distribution), as opposed to an
     * interior-removable point that merely happens to fall in a connection's first/last triple
     * window. Reuses the {@link #inferFace} band predicate but with the wider
     * {@link #ON_FACE_STUB_TOLERANCE_PX} band: Archi stores router-attached ports up to ~1px off the
     * exact perimeter line, so the strict 0.5px band under-detects genuine egress stubs. Null-element-safe
     * (a connection whose source/target node is not resolvable yields {@code false} → no exclusion).
     */
    static boolean isOnPerimeterFace(double[] bp, AssessmentNode elem) {
        return elem != null && inferFace(bp, elem, ON_FACE_STUB_TOLERANCE_PX) != null;
    }

    private static boolean isRedundantBendpoint(double[] a, double[] b, double[] c) {
        double eps = REDUNDANT_BENDPOINT_AXIS_COLLINEAR_EPSILON_PX;
        double acLen = Math.hypot(c[0] - a[0], c[1] - a[1]);
        if (acLen < eps) {
            // Neighbours coincident within the reconstruction-noise floor — a degenerate hairpin,
            // not a straight run through b; treat as not redundant.
            return false;
        }
        // Axis-aligned collinearity: the triple {a,b,c} must lie within ε of a HORIZONTAL or
        // VERTICAL line. min(spanX, spanY) is the thinner extent of its bounding box; ≤ ε means the
        // three points share an axis. A point that is only DIAGONALLY near-collinear is a real
        // orthogonal corner — removing it would replace the L with a diagonal (a visible change),
        // and the router's exact axis-aligned removeCollinearPoints keeps it, so it is NOT redundant.
        double spanX = Math.max(a[0], Math.max(b[0], c[0])) - Math.min(a[0], Math.min(b[0], c[0]));
        double spanY = Math.max(a[1], Math.max(b[1], c[1])) - Math.min(a[1], Math.min(b[1], c[1]));
        if (Math.min(spanX, spanY) > eps) {
            return false; // diagonal jog / real corner — b turns the route
        }
        // Between: b within the neighbours' bounding box, widened by ε on both axes so the ±ε
        // reconstruction noise does not reject a genuinely axis-collinear leftover. A point PAST an
        // endpoint by more than ε (an out-and-back spike) is still rejected — removing it WOULD
        // change the shape; that overshoot is the zigzag detector's concern, not this one.
        boolean withinX = b[0] >= Math.min(a[0], c[0]) - eps && b[0] <= Math.max(a[0], c[0]) + eps;
        boolean withinY = b[1] >= Math.min(a[1], c[1]) - eps && b[1] <= Math.max(a[1], c[1]) + eps;
        return withinX && withinY;
    }

    // ---- Non-orthogonal interior (mid) segment detection ----

    /** Result of non-orthogonal interior-segment detection. */
    record NonOrthogonalInteriorSegmentResult(int count, List<String> descriptions, Set<String> violatorIds) {}

    /**
     * Counts connections that have at least one non-orthogonal <b>interior</b> (mid) segment —
     * a segment strictly between the source-terminal segment {@code [path[0], path[1]]} and the
     * target-terminal segment {@code [path[n-2], path[n-1]]} whose angle deviates from the
     * nearest cardinal axis by more than {@link #NON_ORTH_ANGLE_THRESHOLD} degrees.
     *
     * <p>This generalises {@link #countNonOrthogonalTerminals} — which angle-tests only the two
     * terminal segments — to the interior of the route, as a SEPARATE per-connection count.
     * The interior segments are the pairs {@code [path[i], path[i+1]]} for {@code i = 1 .. n-3};
     * a connection with fewer than 4 path points has no interior segment and contributes 0.
     *
     * <p>Reuses the same angular predicate {@link #isNonOrthogonal}. No perimeter/visible-length
     * guard is needed: an interior segment runs bendpoint-to-bendpoint and never crosses an
     * element boundary, so it is fully visible by construction — unlike a terminal segment, which
     * is clipped at the element edge (hence the terminal detector's post-clip machinery, which is
     * deliberately NOT replicated here).
     *
     * <p>Counting is <b>per connection</b> (mirroring the terminal count, so the two are directly
     * comparable); every offending interior segment contributes one description. The terminal and
     * interior segment sets are disjoint, so a connection may be counted by both detectors
     * independently.
     *
     * <p><b>Rating-bearing</b>, and by the same ratio buckets as
     * {@link #countNonOrthogonalTerminals} — <em>that</em> is the "terminal sibling" this detector
     * mirrors, not {@link #countOffFaceParallelTerminals}, which rates on binary presence and
     * deliberately rejects ratio bucketing. The count enters the breakdown as
     * {@code nonOrthogonalInteriorSegments} and joins the routing Tier-2 chain, which combines the family with {@code Math.max}, so a connection diagonal at both a
     * terminal and an interior segment lights both entries and is capped once. An earlier revision of
     * this javadoc called the count informational only and never fed into the rating; that stopped
     * being true when the breakdown entry was added, and it is recorded here because the identical
     * stale sentence sat on {@code countOffFaceParallelTerminals} in this same file.
     *
     * <p>Distinct from {@link #countInteriorTerminations} (a bendpoint strictly inside an element
     * rect) and {@link #countZigzags} (an opposite-sign reversal).
     *
     * @see #countNonOrthogonalTerminals(List, List, boolean)
     */
    NonOrthogonalInteriorSegmentResult countNonOrthogonalInteriorSegments(
            List<AssessmentConnection> connections, boolean collectViolatorIds) {
        int count = 0;
        List<String> descriptions = new ArrayList<>();
        Set<String> violatorIds = collectViolatorIds ? new HashSet<>() : Set.of();
        for (AssessmentConnection conn : connections) {
            List<double[]> path = conn.pathPoints();
            // Interior segments are [path[i], path[i+1]] for i = 1 .. n-3 (strictly between the
            // two terminal segments). They exist only when the path has at least 4 points.
            if (path.size() < 4) continue;
            boolean flagged = false;
            for (int i = 1; i <= path.size() - 3; i++) {
                double[] a = path.get(i);
                double[] b = path.get(i + 1);
                if (isNonOrthogonal(a, b)) {
                    flagged = true;
                    if (descriptions.size() < MAX_DESCRIPTIONS) {
                        descriptions.add("Connection '" + conn.id() + "' " + conn.sourceNodeId()
                                + " → " + conn.targetNodeId()
                                + ": non-orthogonal interior segment at index " + i
                                + " (" + formatPoint(a) + " → " + formatPoint(b) + ")");
                    }
                }
            }
            if (flagged) {
                count++;
                if (collectViolatorIds) {
                    violatorIds.add(conn.id());
                }
            }
        }
        return new NonOrthogonalInteriorSegmentResult(count, descriptions, violatorIds);
    }

    /** Result of the container-fill == child-fill backstop detection (informational; no rating impact). */
    record ContainerFillResult(int count, List<String> descriptions, Set<String> violatorIds) {}

    /**
     * Backstop detector for the container-recession emitter: counts CONTAINERS whose AUTHORED
     * (non-null) fill colour equals at least one of their direct nested children's fill colours —
     * the residual flat "blob" the emitter is contractually forbidden to touch (the emitter only
     * recedes UNauthored, null-fill parents at add time; an authored same-colour parent/child pair
     * can only be reached by an explicit caller fill, so it is surfaced here instead).
     *
     * <p>Pure attribute/containment scan over the node tree; a container is counted at most once
     * regardless of how many children match. Colour comparison is case-insensitive (Archi may
     * normalise hex case). Informational only — no rating impact (mirrors the other backstop
     * predicates). A null-fill container, or one with no same-fill child, never flags.</p>
     */
    ContainerFillResult countContainerFillEqualsChild(
            List<AssessmentNode> nodes, boolean collectViolatorIds) {
        int count = 0;
        List<String> descriptions = new ArrayList<>();
        Set<String> violatorIds = collectViolatorIds ? new HashSet<>() : Set.of();
        for (AssessmentNode parent : nodes) {
            String pFill = parent.fillColor();
            if (pFill == null) continue; // unauthored — the emitter handles recession; not a blob
            int matchingChildren = 0;
            for (AssessmentNode child : nodes) {
                if (!parent.id().equals(child.parentId())) continue; // direct children only
                String cFill = child.fillColor();
                if (cFill != null && pFill.equalsIgnoreCase(cFill)) {
                    matchingChildren++;
                }
            }
            if (matchingChildren > 0) {
                count++;
                if (descriptions.size() < MAX_DESCRIPTIONS) {
                    descriptions.add("Container '" + parent.name() + "' (" + parent.id()
                            + ") shares its fill " + pFill + " with " + matchingChildren
                            + " nested child element(s) — they merge into one flat block; give the"
                            + " container a distinct (lighter) fill so the children stand out.");
                }
                if (collectViolatorIds) {
                    violatorIds.add(parent.id());
                }
            }
        }
        return new ContainerFillResult(count, descriptions, violatorIds);
    }

    // ---- M4: Connection-vs-Element-Edge Coincidence ----

    /**
     * Result of M4 edge-coincidence detection. {@code count} is the rating-bearing per-connection
     * tally (number of connections with at least one edge-coincident segment); {@code violatorIds}
     * holds those connection ids. {@code grazedElementCount} / {@code grazedElementIds} are the
     * informational per-element enumeration — every distinct {@code (connection, element)} graze
     * across the view, so a single trunk grazing multiple element edges is fully reported without
     * disturbing the rating-bearing {@code count}.
     */
    record EdgeCoincidenceResult(int count, List<String> descriptions, Set<String> violatorIds,
            int grazedElementCount, Set<String> grazedElementIds) {}

    /**
     * Counts connection segments that "hug" an element's edge within
     * {@link #EDGE_COINCIDENCE_TOLERANCE_PX}px (M4). For each segment, classified as
     * horizontal or vertical (within 1px tolerance):
     * <ul>
     *   <li>Horizontal at {@code y_seg}: any element (including the connection's own
     *       source/target) with TOP or BOTTOM edge within {@link #EDGE_COINCIDENCE_TOLERANCE_PX}
     *       AND segment x-range overlapping element x-range by at least
     *       {@link #EDGE_COINCIDENCE_MIN_OVERLAP_PX}.</li>
     *   <li>Vertical: symmetric (any element with LEFT/RIGHT edges).</li>
     * </ul>
     * Includes the connection's own source/target faces — perpendicular terminal segments
     * are silent by construction (orientation mismatch in classification at lines above), so
     * any flag against source/target is a real parallel-coincident defect. Distinct from
     * {@code coincidentSegmentCount}, which is connection-vs-connection.
     *
     * <p>Spec live example: connection {@code id-74e3ee1e02a84721a3db682cb1b6fb24} (API Mgmt →
     * Internet Banking) horizontal segment at y=150 vs Internet Banking BOTTOM at y=148 (gap
     * 2px). Internet Banking IS the connection's target — under the post-removal rule
     * (2026-04-27), this is in scope. Tier 2R severity (cap fair).</p>
     */
    EdgeCoincidenceResult countConnectionEdgeCoincidence(
            List<AssessmentConnection> connections, List<AssessmentNode> layoutNodes,
            boolean collectViolatorIds) {
        int count = 0;
        int grazedElementCount = 0;
        List<String> descriptions = new ArrayList<>();
        Set<String> violatorIds = collectViolatorIds ? new HashSet<>() : Set.of();
        Set<String> grazedElementIds = collectViolatorIds ? new HashSet<>() : Set.of();
        for (AssessmentConnection conn : connections) {
            List<double[]> path = conn.pathPoints();
            if (path.size() < 2) continue;
            // Rating-bearing legacy tally: count++ / violatorIds.add(conn.id()) fire AT MOST ONCE
            // per connection (the first graze), guarded by legacyFlagged. The enumeration below no
            // longer breaks, so it walks every segment/element and records EACH grazed element —
            // a trunk grazing three element edges contributes 3 to grazedElementCount while the
            // legacy count stays 1. The two surfaces are deliberately decoupled.
            boolean legacyFlagged = false;
            Set<String> grazedThisConn = new LinkedHashSet<>();
            for (int i = 0; i < path.size() - 1; i++) {
                double[] s = path.get(i);
                double[] e = path.get(i + 1);
                boolean horizontal = Math.abs(s[1] - e[1]) <= ZIGZAG_AXIS_TOLERANCE_PX
                        && Math.abs(s[0] - e[0]) > ZIGZAG_AXIS_TOLERANCE_PX;
                boolean vertical = Math.abs(s[0] - e[0]) <= ZIGZAG_AXIS_TOLERANCE_PX
                        && Math.abs(s[1] - e[1]) > ZIGZAG_AXIS_TOLERANCE_PX;
                if (!horizontal && !vertical) continue;
                for (AssessmentNode elem : layoutNodes) {
                    boolean hugs = (horizontal && segmentHugsHorizontalEdge(s, e, elem))
                            || (vertical && segmentHugsVerticalEdge(s, e, elem));
                    if (!hugs) continue;
                    if (descriptions.size() < MAX_DESCRIPTIONS) {
                        descriptions.add("Connection '" + conn.id() + "' segment "
                                + formatPoint(s) + "→" + formatPoint(e)
                                + " hugs element '" + elem.id() + "' edge");
                    }
                    grazedThisConn.add(elem.id());
                    if (!legacyFlagged) {
                        count++;
                        legacyFlagged = true;
                        if (collectViolatorIds) {
                            violatorIds.add(conn.id());
                        }
                    }
                }
            }
            grazedElementCount += grazedThisConn.size();
            if (collectViolatorIds) {
                grazedElementIds.addAll(grazedThisConn);
            }
        }
        return new EdgeCoincidenceResult(count, descriptions, violatorIds,
                grazedElementCount, grazedElementIds);
    }

    private static boolean segmentHugsHorizontalEdge(double[] s, double[] e, AssessmentNode elem) {
        double y = (s[1] + e[1]) / 2.0;
        double topEdge = elem.y();
        double bottomEdge = elem.y() + elem.height();
        boolean nearEdge = Math.abs(y - topEdge) <= EDGE_COINCIDENCE_TOLERANCE_PX
                || Math.abs(y - bottomEdge) <= EDGE_COINCIDENCE_TOLERANCE_PX;
        if (!nearEdge) return false;
        double segLeft = Math.min(s[0], e[0]);
        double segRight = Math.max(s[0], e[0]);
        double overlap = Math.min(segRight, elem.x() + elem.width()) - Math.max(segLeft, elem.x());
        return overlap >= EDGE_COINCIDENCE_MIN_OVERLAP_PX;
    }

    private static boolean segmentHugsVerticalEdge(double[] s, double[] e, AssessmentNode elem) {
        double x = (s[0] + e[0]) / 2.0;
        double leftEdge = elem.x();
        double rightEdge = elem.x() + elem.width();
        boolean nearEdge = Math.abs(x - leftEdge) <= EDGE_COINCIDENCE_TOLERANCE_PX
                || Math.abs(x - rightEdge) <= EDGE_COINCIDENCE_TOLERANCE_PX;
        if (!nearEdge) return false;
        double segTop = Math.min(s[1], e[1]);
        double segBottom = Math.max(s[1], e[1]);
        double overlap = Math.min(segBottom, elem.y() + elem.height()) - Math.max(segTop, elem.y());
        return overlap >= EDGE_COINCIDENCE_MIN_OVERLAP_PX;
    }

    // ---- M5: Hub-Port Allocation Quality ----

    /** Result of M5 hub-port quality computation. {@code viewAggregate} = min (worst) hub-face quality. */
    record HubPortQualityResult(double viewAggregate,
                                List<LayoutAssessmentResult.HubFaceDetail> perFaceDetails,
                                Set<String> lowQualityElementIds) {}

    private enum Face {LEFT, RIGHT, TOP, BOTTOM}

    /**
     * Computes per-element-face hub-port allocation quality (M5). For each element, groups its
     * incoming + outgoing connection terminal endpoints by face (LEFT/RIGHT/TOP/BOTTOM). Any
     * face with at least {@link #M5_FACE_GUARD_MIN_CONNECTIONS} connections is a "hub face"; its
     * quality is {@code distinctSlots / connectionsOnFace} (slot = Y for LEFT/RIGHT, X for
     * TOP/BOTTOM, equality within {@link #HUB_PORT_SLOT_TOLERANCE_PX}px).
     *
     * <p>View aggregate is the minimum (worst) of per-hub-face qualities — NOT the mean — so a
     * single degraded face on an otherwise-healthy hub is surfaced honestly rather than averaged
     * away (2026-06-14). When no hub face exists, returns
     * 1.0 (no defect signal). Per-face details are populated only when {@code includeViolatorIds}
     * is true; otherwise the list is empty (avoids unnecessary allocation in the common path).</p>
     *
     * <p>Spec live example: API Mgmt BOTTOM face on Oracle view — 4 connections all at slot
     * X=792 → 1 distinct slot / 4 connections = quality 0.25. Tier 2R severity (threshold 0.5).</p>
     */
    HubPortQualityResult computeHubPortQuality(
            List<AssessmentConnection> connections, List<AssessmentNode> layoutNodes,
            boolean includeViolatorIds) {
        Map<String, AssessmentNode> nodeById = new HashMap<>();
        for (AssessmentNode n : layoutNodes) {
            nodeById.put(n.id(), n);
        }
        // Per-element-face: list of along-face slot coordinates.
        Map<String, Map<Face, List<Double>>> facesByElement = new HashMap<>();
        for (AssessmentConnection conn : connections) {
            List<double[]> path = conn.pathPoints();
            if (path.size() < 2) continue;
            recordTerminal(facesByElement, nodeById.get(conn.sourceNodeId()),
                    path.get(path.size() == 2 ? 0 : 1));
            recordTerminal(facesByElement, nodeById.get(conn.targetNodeId()),
                    path.get(path.size() == 2 ? path.size() - 1 : path.size() - 2));
        }
        List<Double> hubFaceQualities = new ArrayList<>();
        List<LayoutAssessmentResult.HubFaceDetail> details = new ArrayList<>();
        Set<String> lowQualityIds = new HashSet<>();
        for (Map.Entry<String, Map<Face, List<Double>>> e : facesByElement.entrySet()) {
            String elemId = e.getKey();
            for (Map.Entry<Face, List<Double>> faceEntry : e.getValue().entrySet()) {
                List<Double> slots = faceEntry.getValue();
                if (slots.size() < M5_FACE_GUARD_MIN_CONNECTIONS) continue;
                int distinct = countDistinctSlots(slots);
                double quality = (double) distinct / slots.size();
                hubFaceQualities.add(quality);
                if (includeViolatorIds) {
                    details.add(new LayoutAssessmentResult.HubFaceDetail(
                            elemId, faceEntry.getKey().name(), slots.size(), distinct, quality));
                }
                // The same band the remedy speaks for. The M5 suggestion tells the caller to
                // inspect violatorIds.hubPortLowQuality, and that entry is written only when this
                // set is non-empty — so a set that stopped at the "poor" edge would have sent
                // every fair-band caller to a field that is not in the response. Because the view
                // aggregate is the MINIMUM face quality, a face flagged here exists exactly when
                // the aggregate is below the good band: the two cannot disagree.
                if (!"pass".equals(hubPortQualityBand(quality))
                        && !"good".equals(hubPortQualityBand(quality))) {
                    lowQualityIds.add(elemId);
                }
            }
        }
        // (2026-06-14): the view aggregate is the
        // WORST hub face (min), NOT the mean across faces. The mean averaged a degraded face (e.g. a
        // one-sided egress fan-out at q≈0.71) together with healthy faces (q1.0), so a real one-sided
        // hub never reached the rating — "good" masked "fair" (live View-G IAM probe: RIGHT 7/5 q0.71
        // buoyed by LEFT 4/4 q1.0 → mean 0.86 "good"). min surfaces the degraded face honestly. A
        // legitimately-busy SYMMETRIC hub is unaffected (every face at the same quality → min == mean),
        // so this does not over-flag balanced hubs. M5 stays Tier-2R (capped at "fair"); see :1068.
        double aggregate = hubFaceQualities.isEmpty() ? 1.0
                : hubFaceQualities.stream().mapToDouble(Double::doubleValue).min().orElse(1.0);
        return new HubPortQualityResult(aggregate,
                includeViolatorIds ? details : List.of(),
                lowQualityIds);
    }

    /** Face + along-face slot coordinate for a terminal contribution to hub-port quality (M5). */
    private record TerminalSlot(Face face, double slot) {}

    private static void recordTerminal(Map<String, Map<Face, List<Double>>> facesByElement,
                                       AssessmentNode elem, double[] terminalBp) {
        if (elem == null || terminalBp == null) return;
        TerminalSlot ts = inferTerminalSlot(terminalBp, elem);
        if (ts == null) return;
        Map<Face, List<Double>> faces = facesByElement.computeIfAbsent(elem.id(), k -> new HashMap<>());
        List<Double> slots = faces.computeIfAbsent(ts.face(), k -> new ArrayList<>());
        slots.add(ts.slot());
    }

    /**
     * Determines the face + along-face slot for a terminal bendpoint (M5).
     *
     * <p>Three cases:
     * <ul>
     *   <li>BP on the element's perimeter line → use the BP coordinate directly as the slot.</li>
     *   <li>BP strictly inside the element → return null (interior termination is an M2 defect;
     *       the visible face is ambiguous so it should not contribute to face counts).</li>
     *   <li>BP outside the element → compute the clip-point of the segment from element-center
     *       to BP with the element's rect; the face that segment exits identifies the visible
     *       face, and the clip-point's along-face coordinate is the slot. Without this, M5
     *       would silently skip non-orthogonal terminals (M1-flagged), under-reporting hub
     *       congestion on real models.</li>
     * </ul>
     * Returns null when the slot cannot be determined.</p>
     */
    static TerminalSlot inferTerminalSlot(double[] bp, AssessmentNode elem) {
        Face face = inferFace(bp, elem);
        if (face != null) {
            double slot = (face == Face.LEFT || face == Face.RIGHT) ? bp[1] : bp[0];
            return new TerminalSlot(face, slot);
        }
        if (isStrictlyInside(bp, elem)) {
            return null; // Interior — face ambiguous; caller treats as non-contributor.
        }
        return clipSegmentToFace(elem, bp);
    }

    /**
     * Determines which face a terminal bendpoint sits on, given the element rect. Returns
     * null when the bendpoint is not on or within the element's perimeter band. Uses the strict
     * {@link #PERIMETER_TOLERANCE_PX} band (the M1/perimeter contract).
     */
    private static Face inferFace(double[] bp, AssessmentNode elem) {
        return inferFace(bp, elem, PERIMETER_TOLERANCE_PX);
    }

    /**
     * {@link #inferFace(double[], AssessmentNode)} with an explicit on-face tolerance, so a caller
     * that must tolerate a router-attached port stored a fraction past the exact perimeter line (see
     * {@link #ON_FACE_STUB_TOLERANCE_PX}) can widen the band without affecting the strict callers.
     */
    private static Face inferFace(double[] bp, AssessmentNode elem, double tol) {
        double left = elem.x();
        double right = elem.x() + elem.width();
        double top = elem.y();
        double bottom = elem.y() + elem.height();
        boolean onLeft = Math.abs(bp[0] - left) <= tol;
        boolean onRight = Math.abs(bp[0] - right) <= tol;
        boolean onTop = Math.abs(bp[1] - top) <= tol;
        boolean onBottom = Math.abs(bp[1] - bottom) <= tol;
        boolean withinHorizontal = bp[0] >= left - tol && bp[0] <= right + tol;
        boolean withinVertical = bp[1] >= top - tol && bp[1] <= bottom + tol;
        if (onLeft && withinVertical) return Face.LEFT;
        if (onRight && withinVertical) return Face.RIGHT;
        if (onTop && withinHorizontal) return Face.TOP;
        if (onBottom && withinHorizontal) return Face.BOTTOM;
        return null;
    }

    /**
     * Computes the clip-point of the segment from the element's center to the (exterior) bendpoint,
     * matching Archi's perimeter clipping behaviour. Returns the visible-face slot the segment
     * exits through, or null if no valid intersection (degenerate case — center coincides with BP).
     */
    private static TerminalSlot clipSegmentToFace(AssessmentNode elem, double[] bp) {
        double cx = elem.x() + elem.width() / 2.0;
        double cy = elem.y() + elem.height() / 2.0;
        double dx = bp[0] - cx;
        double dy = bp[1] - cy;
        if (Math.abs(dx) < 1e-9 && Math.abs(dy) < 1e-9) return null;
        double left = elem.x();
        double right = elem.x() + elem.width();
        double top = elem.y();
        double bottom = elem.y() + elem.height();
        Face bestFace = null;
        double bestT = Double.POSITIVE_INFINITY;
        double bestSlot = 0.0;
        if (dx < -1e-9) {
            double t = (left - cx) / dx;
            double y = cy + t * dy;
            if (t > 0 && t < bestT && y >= top - PERIMETER_TOLERANCE_PX
                    && y <= bottom + PERIMETER_TOLERANCE_PX) {
                bestT = t; bestFace = Face.LEFT; bestSlot = y;
            }
        }
        if (dx > 1e-9) {
            double t = (right - cx) / dx;
            double y = cy + t * dy;
            if (t > 0 && t < bestT && y >= top - PERIMETER_TOLERANCE_PX
                    && y <= bottom + PERIMETER_TOLERANCE_PX) {
                bestT = t; bestFace = Face.RIGHT; bestSlot = y;
            }
        }
        if (dy < -1e-9) {
            double t = (top - cy) / dy;
            double x = cx + t * dx;
            if (t > 0 && t < bestT && x >= left - PERIMETER_TOLERANCE_PX
                    && x <= right + PERIMETER_TOLERANCE_PX) {
                bestT = t; bestFace = Face.TOP; bestSlot = x;
            }
        }
        if (dy > 1e-9) {
            double t = (bottom - cy) / dy;
            double x = cx + t * dx;
            if (t > 0 && t < bestT && x >= left - PERIMETER_TOLERANCE_PX
                    && x <= right + PERIMETER_TOLERANCE_PX) {
                bestT = t; bestFace = Face.BOTTOM; bestSlot = x;
            }
        }
        return bestFace == null ? null : new TerminalSlot(bestFace, bestSlot);
    }

    private static int countDistinctSlots(List<Double> slots) {
        List<Double> sorted = new ArrayList<>(slots);
        sorted.sort(Double::compare);
        int distinct = 0;
        Double last = null;
        for (Double s : sorted) {
            if (last == null || Math.abs(s - last) > HUB_PORT_SLOT_TOLERANCE_PX) {
                distinct++;
                last = s;
            }
        }
        return distinct;
    }

    // ---- Coincident same-face port detection (informational; no rating impact) ----

    /**
     * Result of coincident-face-port detection. {@code count} = the number of element faces on which
     * two or more connection terminals land within {@link #HUB_PORT_SLOT_TOLERANCE_PX} of one another
     * (a visible port collision). Informational only — never fed into the rating.
     */
    record CoincidentFacePortResult(int count, List<String> descriptions, Set<String> violatorIds) {}

    /** A terminal port contribution carrying its owning connection id, for pair naming / violators. */
    private record FacePort(String connId, double slot) {}

    /**
     * Counts element faces on which two or more connection terminals coincide — their along-face slots
     * fall within {@link #HUB_PORT_SLOT_TOLERANCE_PX} of each other, so the ports overlap on the same
     * perimeter point.
     *
     * <p>This closes the blind spot in {@link #computeHubPortQuality}, whose
     * {@link #M5_FACE_GUARD_MIN_CONNECTIONS} face guard only evaluates a face once it carries four or
     * more connections. A face with two or three connections whose ports coincide is therefore never
     * scored — the hub-port quality aggregate reads a vacuous 1.0 — yet two edges leaving one perimeter
     * point are plainly visible, and are the contention root of the wall-hugs and manufactured terminal
     * jogs that appear on dense hubs. This detector groups terminals by face exactly as M5 does (via
     * {@link #inferTerminalSlot}, so it resolves the face even for an exterior or a fraction-off-perimeter
     * bendpoint) but WITHOUT the connection-count guard, so any face carrying a collision is enumerated
     * regardless of how few connections it holds.</p>
     *
     * <p><b>INFORMATIONAL ONLY</b> — never fed into the rating; the rating-bearing
     * {@code hubPortQualityScore} (M5) and its Tier-2R placement are left untouched. The overlap with M5
     * on faces of four or more connections is intentional: M5 <i>rates</i> the distinct-slot ratio, this
     * <i>enumerates</i> every colliding face — the same enumeration-vs-rated-tally relationship as
     * {@code edgeCoincidenceGrazedElementCount} vs the rating-bearing {@code connectionEdgeCoincidenceCount}.
     * A face is counted once no matter how many pairs collide on it; each counted face contributes one
     * description naming the element, the face, the connection/distinct-slot counts, and the colliding
     * connection ids. Terminals whose face cannot be resolved ({@link #inferTerminalSlot} returns null —
     * interior / ambiguous) do not contribute, consistent with M5.</p>
     *
     * @see #computeHubPortQuality(List, List, boolean)
     * @see #inferTerminalSlot(double[], AssessmentNode)
     */
    CoincidentFacePortResult countCoincidentFacePorts(
            List<AssessmentConnection> connections, List<AssessmentNode> layoutNodes,
            boolean includeViolatorIds) {
        Map<String, AssessmentNode> nodeById = new HashMap<>();
        for (AssessmentNode n : layoutNodes) {
            nodeById.put(n.id(), n);
        }
        // Per-element-face: list of (connectionId, along-face slot) ports.
        Map<String, Map<Face, List<FacePort>>> facesByElement = new HashMap<>();
        for (AssessmentConnection conn : connections) {
            List<double[]> path = conn.pathPoints();
            if (path.size() < 2) continue;
            recordTerminalWithId(facesByElement, nodeById.get(conn.sourceNodeId()), conn.id(),
                    path.get(path.size() == 2 ? 0 : 1));
            recordTerminalWithId(facesByElement, nodeById.get(conn.targetNodeId()), conn.id(),
                    path.get(path.size() == 2 ? path.size() - 1 : path.size() - 2));
        }
        int count = 0;
        List<String> descriptions = new ArrayList<>();
        Set<String> violatorIds = includeViolatorIds ? new HashSet<>() : Set.of();
        // Deterministic emission: sort element ids, then iterate faces in enum order, so the
        // description list and its MAX_DESCRIPTIONS truncation are stable across runs.
        List<String> elemIds = new ArrayList<>(facesByElement.keySet());
        Collections.sort(elemIds);
        for (String elemId : elemIds) {
            Map<Face, List<FacePort>> faces = facesByElement.get(elemId);
            for (Face face : Face.values()) {
                List<FacePort> ports = faces.get(face);
                if (ports == null || ports.size() < 2) continue;
                List<String> collidingIds = collidingConnectionIds(ports);
                if (collidingIds.isEmpty()) continue;
                count++;
                if (includeViolatorIds) {
                    violatorIds.addAll(collidingIds);
                }
                if (descriptions.size() < MAX_DESCRIPTIONS) {
                    AssessmentNode elem = nodeById.get(elemId);
                    String name = elem != null && elem.name() != null ? elem.name() : elemId;
                    List<Double> slotValues = new ArrayList<>();
                    for (FacePort p : ports) {
                        slotValues.add(p.slot());
                    }
                    int distinct = countDistinctSlots(slotValues);
                    descriptions.add("Element '" + name + "' has " + ports.size()
                            + " connections on its " + face.name() + " face collapsing to "
                            + distinct + " distinct port" + (distinct == 1 ? "" : "s")
                            + " (coincident: " + String.join(", ", collidingIds) + ")");
                }
            }
        }
        return new CoincidentFacePortResult(count, descriptions, violatorIds);
    }

    /**
     * Records a terminal contribution keyed by element face, retaining the owning connection id (unlike
     * {@link #recordTerminal}, which drops it) so a collision can name the connection pair. Leaves the
     * M5 {@link #recordTerminal} path untouched (byte-identical {@code hubPortQualityScore}).
     */
    private static void recordTerminalWithId(Map<String, Map<Face, List<FacePort>>> facesByElement,
                                             AssessmentNode elem, String connId, double[] terminalBp) {
        if (elem == null || terminalBp == null) return;
        TerminalSlot ts = inferTerminalSlot(terminalBp, elem);
        if (ts == null) return;
        Map<Face, List<FacePort>> faces = facesByElement.computeIfAbsent(elem.id(), k -> new HashMap<>());
        List<FacePort> ports = faces.computeIfAbsent(ts.face(), k -> new ArrayList<>());
        ports.add(new FacePort(connId, ts.slot()));
    }

    /**
     * Returns the connection ids that genuinely coincide on a face. Ports are clustered by slot using
     * the same greedy sweep as {@link #countDistinctSlots} (a port joins the current cluster when its
     * slot is within {@link #HUB_PORT_SLOT_TOLERANCE_PX} of that cluster's FIRST slot), and any cluster
     * holding two or more DISTINCT connections is a collision whose member ids are returned (in
     * ascending-slot, first-encounter order). Empty when every port sits on its own distinct slot.
     *
     * <p>Clustering — rather than an all-pairs test — keeps the named set consistent with the
     * distinct-slot count reported alongside it: an all-pairs union would take the transitive closure of
     * a chain (slots 0.0, 0.6, 1.1 with a 1.0 tolerance would name all three, though 0.0 and 1.1 are
     * 1.1px apart and never overlap). Requiring two or more distinct connections per cluster means a
     * single self-referencing connection ({@code source == target}, whose two terminals both register on
     * one face at one slot) is not mistaken for two contending connections.</p>
     */
    private static List<String> collidingConnectionIds(List<FacePort> ports) {
        List<FacePort> sorted = new ArrayList<>(ports);
        sorted.sort((a, b) -> Double.compare(a.slot(), b.slot()));
        List<String> colliding = new ArrayList<>();
        Set<String> named = new LinkedHashSet<>();
        int i = 0;
        while (i < sorted.size()) {
            double clusterFirstSlot = sorted.get(i).slot();
            Set<String> clusterConnIds = new LinkedHashSet<>();
            int j = i;
            while (j < sorted.size()
                    && sorted.get(j).slot() - clusterFirstSlot <= HUB_PORT_SLOT_TOLERANCE_PX) {
                clusterConnIds.add(sorted.get(j).connId());
                j++;
            }
            if (clusterConnIds.size() >= 2) {
                for (String id : clusterConnIds) {
                    if (named.add(id)) {
                        colliding.add(id);
                    }
                }
            }
            i = j;
        }
        return colliding;
    }

    // ---- R8: Corridor-Utilisation ----

    /** R8: minimum parallel-segment length (px) to count as a corridor-traversal segment. */
    static final double R8_MIN_PARALLEL_SEGMENT_LENGTH_PX = 30.0;

    /** R8: clearance band (px) per side; mirrors {@code ChannelNudgingPass.MIN_CLEARANCE_PX}. */
    static final double R8_MIN_CLEARANCE_PX = 10.0;

    /** R8: axis-parallel tolerance (px); Archi int-snaps bendpoints so 1.0 is sufficient. */
    static final double R8_AXIS_PARALLEL_TOLERANCE_PX = 1.0;

    /** Result of R8 corridor-utilisation computation. */
    record R8CorridorUtilisationResult(double viewAggregate,
                                        List<LayoutAssessmentResult.CorridorUtilisationDetail> perChannelDetails) {}

    /** Internal: one long parallel segment extracted from one connection's pathPoints. */
    private record R8Segment(int axis, double sharedCoord, double parStart, double parEnd,
                              String connectionId) {}

    /** Internal: a wall pair bracketing one R8 segment in the perpendicular axis. */
    private record R8WallPair(String lowId, String highId, double lowEdge, double highEdge) {}

    /**
     * Computes R8 corridor-utilisation score. Per-corridor
     * {@code spread_ratio = span / available} where occupants are long parallel segments
     * sharing the same wall pair; view aggregate is occupant-count-weighted mean. Returns
     * {@code 1.0} vacuously when no multi-occupant channel exists (mirrors
     * {@link #computeHubPortQuality}). Single-occupant channels and channels without
     * obstacle-bounded walls are skipped.
     */
    R8CorridorUtilisationResult computeR8CorridorUtilisation(
            List<AssessmentConnection> connections, List<AssessmentNode> layoutNodes,
            boolean includeViolatorIds) {
        // 1. Extract long parallel segments per connection (deterministic insertion order).
        List<R8Segment> segments = new ArrayList<>();
        for (AssessmentConnection conn : connections) {
            extractLongParallelSegments(conn, segments);
        }

        // 2. Group segments by corridor identity (wall pair). LinkedHashMap preserves
        // first-encounter order so per-channel detail emission and aggregate computation
        // are deterministic across runs (pinned-test
        // calibration requires deterministic algorithm output).
        Map<String, List<R8Segment>> occupantsByCorridor = new LinkedHashMap<>();
        Map<String, R8WallPair> wallsByCorridor = new HashMap<>();
        for (R8Segment seg : segments) {
            R8WallPair walls = findChannelWalls(seg, layoutNodes);
            if (walls == null) continue;
            String key = seg.axis() + "|" + walls.lowId() + "|" + walls.highId();
            occupantsByCorridor.computeIfAbsent(key, k -> new ArrayList<>()).add(seg);
            wallsByCorridor.putIfAbsent(key, walls);
        }

        // 3. Per-corridor spread_ratio + occupant-count-weighted view aggregate.
        List<LayoutAssessmentResult.CorridorUtilisationDetail> details = new ArrayList<>();
        double weightedSum = 0.0;
        int totalOccupants = 0;
        for (Map.Entry<String, List<R8Segment>> e : occupantsByCorridor.entrySet()) {
            List<R8Segment> occupants = e.getValue();
            if (occupants.size() < 2) continue;
            R8WallPair walls = wallsByCorridor.get(e.getKey());
            double available = walls.highEdge() - walls.lowEdge() - 2 * R8_MIN_CLEARANCE_PX;
            if (available <= 0) continue; // Degenerate corridor — walls within clearance band.

            double minCoord = Double.POSITIVE_INFINITY;
            double maxCoord = Double.NEGATIVE_INFINITY;
            for (R8Segment occ : occupants) {
                minCoord = Math.min(minCoord, occ.sharedCoord());
                maxCoord = Math.max(maxCoord, occ.sharedCoord());
            }
            double span = maxCoord - minCoord;
            // Clamp to [0.0, 1.0]: occupants spread wider than the post-clearance band
            // (span > available) indicates wall-hugging — already an M4 edge-coincidence
            // signal; R8 caps at 1.0 to keep the metric within its documented range.
            double spreadRatio = Math.min(span / available, 1.0);

            weightedSum += spreadRatio * occupants.size();
            totalOccupants += occupants.size();

            if (includeViolatorIds) {
                double occupantMidCoord = (minCoord + maxCoord) / 2.0;
                details.add(new LayoutAssessmentResult.CorridorUtilisationDetail(
                        occupants.get(0).axis(), occupantMidCoord,
                        walls.lowId(), walls.highId(),
                        occupants.size(), span, available, spreadRatio));
            }
        }
        double aggregate = totalOccupants > 0 ? weightedSum / totalOccupants : 1.0;
        return new R8CorridorUtilisationResult(aggregate, details);
    }

    /** axis = 0 for vertical (occupants share x), 1 for horizontal. */
    private void extractLongParallelSegments(AssessmentConnection conn, List<R8Segment> into) {
        List<double[]> path = conn.pathPoints();
        if (path == null || path.size() < 2) return;
        for (int i = 0; i < path.size() - 1; i++) {
            double[] p = path.get(i);
            double[] q = path.get(i + 1);
            double dx = Math.abs(q[0] - p[0]);
            double dy = Math.abs(q[1] - p[1]);
            if (dx < R8_AXIS_PARALLEL_TOLERANCE_PX && dy >= R8_MIN_PARALLEL_SEGMENT_LENGTH_PX) {
                into.add(new R8Segment(0, (p[0] + q[0]) / 2.0,
                        Math.min(p[1], q[1]), Math.max(p[1], q[1]), conn.id()));
            } else if (dy < R8_AXIS_PARALLEL_TOLERANCE_PX
                    && dx >= R8_MIN_PARALLEL_SEGMENT_LENGTH_PX) {
                into.add(new R8Segment(1, (p[1] + q[1]) / 2.0,
                        Math.min(p[0], q[0]), Math.max(p[0], q[0]), conn.id()));
            }
        }
    }

    /** Two-pass: prefer group walls (corridors are gaps between groups); fall back to any rect. */
    private R8WallPair findChannelWalls(R8Segment seg, List<AssessmentNode> layoutNodes) {
        R8WallPair groupWalls = scanWalls(seg, layoutNodes, true);
        if (groupWalls != null) return groupWalls;
        return scanWalls(seg, layoutNodes, false);
    }

    /** Returns null when either wall is missing (segment perimeter-bound on one side). */
    private R8WallPair scanWalls(R8Segment seg, List<AssessmentNode> layoutNodes,
                                  boolean groupsOnly) {
        String lowId = null, highId = null;
        double lowEdge = Double.NEGATIVE_INFINITY, highEdge = Double.POSITIVE_INFINITY;
        for (AssessmentNode n : layoutNodes) {
            if (n.isNote()) continue;
            if (groupsOnly && !n.isContainer()) continue;
            double left = n.x(), right = n.x() + n.width();
            double top = n.y(), bottom = n.y() + n.height();
            if (seg.axis() == 0) {
                // Vertical channel — walls are LEFT/RIGHT in x; perpendicular range is y.
                double overlap = Math.min(seg.parEnd(), bottom) - Math.max(seg.parStart(), top);
                if (overlap < 1.0) continue;
                if (right < seg.sharedCoord() && right > lowEdge) {
                    lowEdge = right;
                    lowId = n.id();
                }
                if (left > seg.sharedCoord() && left < highEdge) {
                    highEdge = left;
                    highId = n.id();
                }
            } else {
                // Horizontal channel — walls are TOP/BOTTOM in y; perpendicular range is x.
                double overlap = Math.min(seg.parEnd(), right) - Math.max(seg.parStart(), left);
                if (overlap < 1.0) continue;
                if (bottom < seg.sharedCoord() && bottom > lowEdge) {
                    lowEdge = bottom;
                    lowId = n.id();
                }
                if (top > seg.sharedCoord() && top < highEdge) {
                    highEdge = top;
                    highId = n.id();
                }
            }
        }
        if (lowId != null && highId != null) {
            return new R8WallPair(lowId, highId, lowEdge, highEdge);
        }
        return null;
    }

    // ---- Hub-to-neighbour crowding / clearance (2026-06-25) ----

    /**
     * Result of the hub-to-neighbour crowding computation.
     * {@code minClearance} is the smallest qualifying-face clearance (px) across all hubs
     * ({@link #NO_HUB_NEIGHBOUR_CLEARANCE} when none qualifies). {@code crowded} is true when
     * that minimum is non-negative and below {@link #CROWDING_FLOOR_PX}.
     */
    record HubNeighbourCrowdingResult(double minClearance, boolean crowded) {}

    /**
     * Computes the minimum clearance (px) from a detected hub's edge to its nearest spoke
     * row, and whether that clearance is crowded.
     *
     * <p>A hub is any non-group, non-note element with at least
     * {@link #HUB_DETECTION_THRESHOLD} incident connections. For each hub face
     * (TOP/BOTTOM/LEFT/RIGHT) the spoke neighbours are the distinct connected elements that
     * sit on the outward side of that face with overlapping perpendicular extent —
     * ancestor/descendant containment pairs are excluded (nested children are intentional,
     * not crowding). A face carrying at least {@link #CROWDING_MIN_ADJACENT_K} such neighbours
     * is a spoke row; its clearance is the minimum gap to those neighbours' facing edges.</p>
     *
     * <p>{@code minClearance} is reported for sparse hubs too (clearance &ge; floor) so the
     * next-step emitter can branch resize vs reposition; it is only the
     * {@link #NO_HUB_NEIGHBOUR_CLEARANCE} sentinel when no hub face qualifies. Pure geometry,
     * mirrors the wall-pair scan idiom of {@link #computeR8CorridorUtilisation}.</p>
     */
    HubNeighbourCrowdingResult computeHubNeighbourCrowding(
            List<AssessmentConnection> connections, List<AssessmentNode> layoutNodes) {
        Map<String, AssessmentNode> byId = new HashMap<>();
        for (AssessmentNode n : layoutNodes) {
            byId.put(n.id(), n);
        }
        // Connection degree + neighbour adjacency (self-loops and dangling endpoints skipped).
        Map<String, Integer> degree = new HashMap<>();
        Map<String, Set<String>> neighbours = new HashMap<>();
        for (AssessmentConnection c : connections) {
            String s = c.sourceNodeId();
            String t = c.targetNodeId();
            if (s == null || t == null || s.equals(t)) continue;
            degree.merge(s, 1, Integer::sum);
            degree.merge(t, 1, Integer::sum);
            neighbours.computeIfAbsent(s, k -> new HashSet<>()).add(t);
            neighbours.computeIfAbsent(t, k -> new HashSet<>()).add(s);
        }
        Set<String> containmentPairs = buildContainmentPairs(layoutNodes);

        double minClearance = NO_HUB_NEIGHBOUR_CLEARANCE;
        for (AssessmentNode hub : layoutNodes) {
            if (hub.isContainer() || hub.isNote()) continue;
            if (degree.getOrDefault(hub.id(), 0) < HUB_DETECTION_THRESHOLD) continue;
            double hubClearance = nearestSpokeRowClearance(
                    hub, neighbours.get(hub.id()), byId, containmentPairs);
            if (hubClearance < 0) continue;
            if (minClearance < 0 || hubClearance < minClearance) {
                minClearance = hubClearance;
            }
        }
        boolean crowded = minClearance >= 0 && minClearance < CROWDING_FLOOR_PX;
        return new HubNeighbourCrowdingResult(minClearance, crowded);
    }

    /**
     * Minimum clearance (px) from {@code hub}'s edges to its nearest qualifying spoke row,
     * or {@link #NO_HUB_NEIGHBOUR_CLEARANCE} when no face carries at least
     * {@link #CROWDING_MIN_ADJACENT_K} overlapping spoke neighbours. Faces with fewer than
     * K neighbours are ignored so a stray near neighbour does not drive the signal. A
     * neighbour overlapping the hub (negative gap) is clamped to 0 — that is the overlap
     * metric's territory, not crowding's.
     */
    private double nearestSpokeRowClearance(AssessmentNode hub, Set<String> hubNeighbours,
            Map<String, AssessmentNode> byId, Set<String> containmentPairs) {
        if (hubNeighbours == null || hubNeighbours.isEmpty()) {
            return NO_HUB_NEIGHBOUR_CLEARANCE;
        }
        double hubLeft = hub.x(), hubRight = hub.x() + hub.width();
        double hubTop = hub.y(), hubBottom = hub.y() + hub.height();
        // Per-face accumulators, indexed [BOTTOM, TOP, RIGHT, LEFT].
        int[] counts = new int[4];
        double[] minGap = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        for (String nid : hubNeighbours) {
            AssessmentNode n = byId.get(nid);
            if (n == null || n.isNote()) continue;
            if (isContainmentPair(hub, n, containmentPairs)) continue;
            double nLeft = n.x(), nRight = n.x() + n.width();
            double nTop = n.y(), nBottom = n.y() + n.height();
            double xOverlap = Math.min(hubRight, nRight) - Math.max(hubLeft, nLeft);
            double yOverlap = Math.min(hubBottom, nBottom) - Math.max(hubTop, nTop);
            // Strict > 0 on the shared-extent axis is intentional: a purely tangential neighbour
            // (extent touches the hub at a single pixel line, overlap == 0) shares no face span and
            // must not count toward a row. The four face tests are mutually exclusive — nTop >= hubBottom
            // forces yOverlap <= 0, so a neighbour can match at most one of BOTTOM/TOP/RIGHT/LEFT.
            if (xOverlap > 0 && nTop >= hubBottom) {          // BOTTOM face: neighbour below
                counts[0]++;
                minGap[0] = Math.min(minGap[0], nTop - hubBottom);
            }
            if (xOverlap > 0 && nBottom <= hubTop) {          // TOP face: neighbour above
                counts[1]++;
                minGap[1] = Math.min(minGap[1], hubTop - nBottom);
            }
            if (yOverlap > 0 && nLeft >= hubRight) {          // RIGHT face: neighbour right
                counts[2]++;
                minGap[2] = Math.min(minGap[2], nLeft - hubRight);
            }
            if (yOverlap > 0 && nRight <= hubLeft) {          // LEFT face: neighbour left
                counts[3]++;
                minGap[3] = Math.min(minGap[3], hubLeft - nRight);
            }
        }
        double clearance = NO_HUB_NEIGHBOUR_CLEARANCE;
        for (int f = 0; f < 4; f++) {
            if (counts[f] >= CROWDING_MIN_ADJACENT_K
                    && minGap[f] != Double.POSITIVE_INFINITY) {
                double g = Math.max(0.0, minGap[f]);
                if (clearance < 0 || g < clearance) clearance = g;
            }
        }
        return clearance;
    }

    // ---- parallelConnectionGap ----

    /**
     * Per-axis aggregate of nearest-parallel-overlapping-neighbour gaps for the
     * parallelConnectionGap metric. {@code mean / min / p10} are boxed because they
     * are {@code null} when {@code qualifyingSegmentCount == 0} (no segment had any
     * overlapping parallel neighbour in this axis). {@code violatorIds} is populated
     * only when {@code includeViolatorIds = true}; empty {@code Set.of()} otherwise.
     * A connection's id is added to {@code violatorIds} when at least one of its
     * segments produced a qualifying gap less than {@link #PARALLEL_GAP_NARROW_T2_PX}.
     */
    record ParallelConnectionGapAxis(int qualifyingSegmentCount, Double mean, Double min, Double p10,
                                      int narrowGapCount15, int narrowGapCount25, int narrowGapCount40,
                                      Set<String> violatorIds) {}

    /** Combined V (axis 0) + H (axis 1) result for {@link #computeParallelConnectionGap}. */
    record ParallelConnectionGapResult(ParallelConnectionGapAxis vAxis, ParallelConnectionGapAxis hAxis) {}

    /** Internal: one axis-aligned segment extracted from a connection's pathPoints. */
    private record ParallelGapSegment(int axis, double fixedCoord, double spanLow, double spanHigh,
                                       String connectionId) {}

    /**
     * Computes the parallelConnectionGap metric family (2026-05-12).
     *
     * <p>For each axis (V primary, H secondary): classify each bendpoint-pair segment
     * as V (Δx &lt; {@link #PARALLEL_GAP_AXIS_TOLERANCE_PX}, Δy ≥ tolerance) or H
     * (Δy &lt; tolerance, Δx ≥ tolerance), drop zero-length and diagonal pairs.
     * For each segment, find the nearest co-axial parallel segment whose span overlaps
     * (strict overlap &gt; 0); the gap is the perpendicular distance between fixed
     * coordinates (or 0 when the two fixed coordinates are identical and spans overlap).
     * Aggregate the per-segment gap list: arithmetic mean, min, 10th-percentile (linear
     * interpolation per Python {@code numpy.percentile}-equivalent), and narrow-gap
     * counts at thresholds 15/25/40 px.</p>
     *
     * <p>Calibration anchor: V4 manual gold view {@code id-3b2665e3ff6840708dbed2b3d1415613}
     * produced {@code V_p10 = 13.30} under {@link #PARALLEL_GAP_AXIS_TOLERANCE_PX} = 2.0;
     * monotonic owner-perception ordering on 4 reference views (V4 gold &gt; HH source
     * &gt; ST source) validates the metric as perception-aligned.</p>
     *
     * <p><b>INFORMATIONAL ONLY</b> — this metric does NOT contribute to
     * {@code computeRatingWithBreakdown} or {@code generateSuggestions}
     * (matches {@code corridorUtilisationScore} (R8) precedent). The narrow-corridor
     * defect class (5th unmeasured class) is
     * a structural-floor problem in the routing pipeline; rating-tying would mark all
     * views below the floor as poor regardless of agent improvements. Rating-tying is
     * deferred until the routing-pipeline narrow-corridor floor closure ships.</p>
     *
     * @see #computeR8CorridorUtilisation closest sibling metric
     */
    ParallelConnectionGapResult computeParallelConnectionGap(
            List<AssessmentConnection> connections, boolean includeViolatorIds) {
        List<ParallelGapSegment> vSegs = new ArrayList<>();
        List<ParallelGapSegment> hSegs = new ArrayList<>();
        for (AssessmentConnection conn : connections) {
            extractParallelGapSegments(conn, vSegs, hSegs);
        }
        ParallelConnectionGapAxis vAxis = computeAxisAggregates(vSegs, includeViolatorIds);
        ParallelConnectionGapAxis hAxis = computeAxisAggregates(hSegs, includeViolatorIds);
        return new ParallelConnectionGapResult(vAxis, hAxis);
    }

    /**
     * Translates the Python reference {@code extract_segments} (compute_parallel_gap.py
     * lines 75-102): classify each consecutive bendpoint pair as V or H per the axis
     * tolerance and append to the appropriate per-axis list. Diagonal and zero-length
     * pairs are dropped.
     */
    private void extractParallelGapSegments(AssessmentConnection conn,
                                             List<ParallelGapSegment> vSegs,
                                             List<ParallelGapSegment> hSegs) {
        List<double[]> path = conn.pathPoints();
        if (path == null || path.size() < 2) return;
        double tol = PARALLEL_GAP_AXIS_TOLERANCE_PX;
        for (int i = 0; i < path.size() - 1; i++) {
            double[] p = path.get(i);
            double[] q = path.get(i + 1);
            double dx = Math.abs(q[0] - p[0]);
            double dy = Math.abs(q[1] - p[1]);
            if (dx < tol && dy < tol) continue;
            if (dy < tol && dx >= tol) {
                double fixedY = (p[1] + q[1]) / 2.0;
                hSegs.add(new ParallelGapSegment(1, fixedY,
                        Math.min(p[0], q[0]), Math.max(p[0], q[0]), conn.id()));
            } else if (dx < tol && dy >= tol) {
                double fixedX = (p[0] + q[0]) / 2.0;
                vSegs.add(new ParallelGapSegment(0, fixedX,
                        Math.min(p[1], q[1]), Math.max(p[1], q[1]), conn.id()));
            }
        }
    }

    /**
     * Translates Python {@code nearest_parallel_gaps} (lines 105-134) +
     * {@code percentile} (lines 137-149). For each segment, scan all other co-axial
     * segments; if their spans overlap strictly, gap = |Δfixed| (or 0 when fixed
     * coordinates coincide). Take the minimum across overlapping neighbours; segments
     * with no overlapping neighbour are non-qualifying and contribute nothing.
     */
    private ParallelConnectionGapAxis computeAxisAggregates(List<ParallelGapSegment> segs,
                                                              boolean includeViolatorIds) {
        Set<String> violatorIds = includeViolatorIds ? new HashSet<>() : Set.of();
        if (segs.isEmpty()) {
            return new ParallelConnectionGapAxis(0, null, null, null, 0, 0, 0, violatorIds);
        }
        List<Double> gaps = new ArrayList<>();
        // Parallel list: gaps.get(k) was produced by gapOwners.get(k); used for violator-id
        // attribution (avoids re-deriving owner ids from minimum-gap during aggregation).
        List<String> gapOwners = new ArrayList<>();
        int n = segs.size();
        for (int i = 0; i < n; i++) {
            ParallelGapSegment s = segs.get(i);
            Double bestGap = null;
            for (int j = 0; j < n; j++) {
                if (j == i) continue;
                ParallelGapSegment s2 = segs.get(j);
                double overlap = Math.min(s.spanHigh(), s2.spanHigh())
                        - Math.max(s.spanLow(), s2.spanLow());
                if (overlap <= 0.0) continue;
                double gap = (s.fixedCoord() == s2.fixedCoord())
                        ? 0.0
                        : Math.abs(s.fixedCoord() - s2.fixedCoord());
                if (bestGap == null || gap < bestGap) bestGap = gap;
            }
            if (bestGap == null) continue;
            gaps.add(bestGap);
            gapOwners.add(s.connectionId());
        }
        int m = gaps.size();
        if (m == 0) {
            return new ParallelConnectionGapAxis(0, null, null, null, 0, 0, 0, violatorIds);
        }
        double sum = 0.0;
        double minV = Double.POSITIVE_INFINITY;
        int n15 = 0, n25 = 0, n40 = 0;
        for (int k = 0; k < m; k++) {
            double g = gaps.get(k);
            sum += g;
            if (g < minV) minV = g;
            if (g < PARALLEL_GAP_NARROW_T1_PX) n15++;
            if (g < PARALLEL_GAP_NARROW_T2_PX) {
                n25++;
                if (includeViolatorIds) {
                    violatorIds.add(gapOwners.get(k));
                }
            }
            if (g < PARALLEL_GAP_NARROW_T3_PX) n40++;
        }
        double mean = sum / m;
        double p10 = percentileP10(gaps);
        return new ParallelConnectionGapAxis(m, mean, minV, p10, n15, n25, n40, violatorIds);
    }

    /**
     * Linear-interpolation 10th-percentile matching Python {@code numpy.percentile}
     * default ({@code linear}). For a single element returns that element. For n &ge; 2:
     * {@code k = (n-1) × 0.10}; {@code f = floor(k)}; {@code c = min(f+1, n-1)};
     * {@code result = xs[f] + (xs[c] − xs[f]) × (k − f)}.
     */
    private static double percentileP10(List<Double> values) {
        List<Double> xs = new ArrayList<>(values);
        Collections.sort(xs);
        int n = xs.size();
        if (n == 1) return xs.get(0);
        double k = (n - 1) * 0.10;
        int f = (int) Math.floor(k);
        int c = Math.min(f + 1, n - 1);
        return xs.get(f) + (xs.get(c) - xs.get(f)) * (k - f);
    }

    // ---- Boundary Violation Detection ----

    /**
     * Result of boundary violation detection (adds violator IDs).
     *
     * <p>{@code violationCount} is the TRUE number of violations and is uncapped.
     * {@code descriptions} is capped at {@code MAX_DESCRIPTIONS}, so on a badly broken view
     * {@code descriptions().size()} understates the problem and must not be used as the count.</p>
     */
    record BoundaryViolationResult(List<String> descriptions, Set<String> violatorIds,
                                   int violationCount) {}

    BoundaryViolationResult detectBoundaryViolations(List<AssessmentNode> nodes,
                                                      boolean collectViolatorIds) {
        Map<String, AssessmentNode> nodeMap = new HashMap<>();
        for (AssessmentNode node : nodes) {
            nodeMap.put(node.id(), node);
        }

        List<String> violations = new ArrayList<>();
        int violationCount = 0;
        Set<String> violatorIds = collectViolatorIds ? new HashSet<>() : Set.of();
        for (AssessmentNode child : nodes) {
            if (child.parentId() == null) continue;

            AssessmentNode parent = nodeMap.get(child.parentId());
            if (parent == null) continue;

            // Both child and parent are in absolute coordinates,
            // so direct comparison is valid
            if (child.x() < parent.x()
                    || child.y() < parent.y()
                    || child.x() + child.width() > parent.x() + parent.width()
                    || child.y() + child.height() > parent.y() + parent.height()) {
                violationCount++;
                if (violations.size() < MAX_DESCRIPTIONS) {
                    // Element-to-element nesting has shipped, so a parent here is not always a
                    // group. Name it by what it is — the reader has to go and find it.
                    violations.add("Element '" + child.id()
                            + "' extends outside parent " + (parent.isGroup() ? "group" : "element")
                            + " '" + parent.id() + "'");
                }
                if (collectViolatorIds) {
                    violatorIds.add(child.id());
                }
            }
        }
        return new BoundaryViolationResult(violations, violatorIds, violationCount);
    }

    // ---- Connection Pass-Through Detection (Finding #3: exclude ancestor groups) ----

    /**
     * Result of pass-through detection separating cross-element and self-element counts.
     * The descriptions list contains both types for informational reporting.
     * The crossElementCount is used for rating penalty calculation.
     */
    record PassThroughResult(List<String> descriptions, int crossElementCount,
                             Set<String> violatorIds) {
        int totalCount() { return descriptions.size(); }
    }

    /**
     * Detects cross-element and self-element pass-throughs for all connections.
     *
     * <p><strong>Note:</strong> Cross-element violator IDs are always collected
     * unconditionally — {@code collectViolatorIds} is kept only for API compatibility
     * and is ignored inside this method. Unconditional collection is required so the
     * classification-precedence guard in {@link #countZigzags(List, Set, boolean)}
     * works in rating-only paths ({@code includeViolatorIds == false}).</p>
     *
     * @param connections        connections to inspect
     * @param nodes              all layout nodes (used to build ancestor/descendant exclusion sets)
     * @param collectViolatorIds ignored — kept for API stability; cross-element violator IDs
     *                           are always populated regardless of this flag
     * @return pass-through result; {@link PassThroughResult#crossElementCount()} used for
     *         rating; {@link PassThroughResult#violatorIds()} fed to
     *         {@link #countZigzags(List, Set, boolean)} as the precedence-guard skip-set
     */
    PassThroughResult detectPassThroughs(List<AssessmentConnection> connections,
                                     List<AssessmentNode> nodes,
                                     boolean collectViolatorIds) {
        // Build node map for ancestor lookups
        Map<String, AssessmentNode> nodeMap = new HashMap<>();
        for (AssessmentNode node : nodes) {
            nodeMap.put(node.id(), node);
        }

        List<String> descriptions = new ArrayList<>();
        int crossElementCount = 0;
        // Cross-element violator IDs are collected unconditionally so the classification
        // precedence guard in countZigzags() works regardless of collectViolatorIds (which
        // gates only the outer assess() enrichment block).
        Set<String> violatorIds = new HashSet<>();

        for (AssessmentConnection conn : connections) {
            // Collect IDs to exclude: source, target, and the ancestors and ALL descendants of
            // source/target whose rectangles still overlap that endpoint
            Set<String> excludeIds = new HashSet<>();
            excludeIds.add(conn.sourceNodeId());
            excludeIds.add(conn.targetNodeId());
            excludeIds.addAll(getAncestorIds(conn.sourceNodeId(), nodeMap));
            excludeIds.addAll(getAncestorIds(conn.targetNodeId(), nodeMap));
            // Exclude the descendants of source/target — connections from a parent element
            // naturally pass through contained children/grandchildren; not a real pass-through.
            // A descendant dragged clear of its endpoint is not contained, so that sentence does
            // not cover it and it counts like any other element — the same node detectBoundaryViolations
            // already reports as escaped.
            excludeIds.addAll(getDescendantIds(conn.sourceNodeId(), nodes));
            excludeIds.addAll(getDescendantIds(conn.targetNodeId(), nodes));

            // Clip path from element centers to element edges (visual fidelity)
            List<double[]> clippedPath = clipPathToVisualEdges(
                    conn.pathPoints(),
                    nodeMap.get(conn.sourceNodeId()),
                    nodeMap.get(conn.targetNodeId()));

            for (AssessmentNode node : nodes) {
                // Skip source, target, ancestors, descendants, and groups (transparent containers)
                if (excludeIds.contains(node.id()) || node.isContainer()) {
                    continue;
                }

                if (pathPassesThroughNode(clippedPath, node)) {
                    // Re-measured here rather than snapshotted at the top of the connection loop:
                    // a single connection can reach this add AND the self-element adds below, so a
                    // value read once per connection lets the list run past the cap. The count and
                    // the violator id below are deliberately OUTSIDE this gate — the cap governs
                    // the description list only, never the number the rating charges.
                    if (descriptions.size() < MAX_DESCRIPTIONS) {
                        descriptions.add("Connection '" + conn.id()
                                + "' passes through element '" + node.id() + "'");
                    }
                    crossElementCount++;
                    violatorIds.add(conn.id());
                    break; // Only report each connection once per element
                }
            }

            // Self-element pass-through detection: track in descriptions but NOT count
            // as cross-element. Two complementary checks:
            //   nonTerminalPassesThroughNode — clipped path, intermediate segments only
            //     (terminal segments naturally touch the endpoints by design).
            //   terminalSegmentOverPenetrates — unclipped path, terminal point only
            //     (stored final/first bendpoints past element center are pathological
            //      and missed by nonTerminalPassesThroughNode, which excludes the
            //      terminal segment from its loop window).
            if (descriptions.size() < MAX_DESCRIPTIONS && clippedPath.size() >= 3) {
                AssessmentNode tgtNode = nodeMap.get(conn.targetNodeId());
                if (tgtNode != null && !tgtNode.isContainer()) {
                    boolean tgtNonTermHit = nonTerminalPassesThroughNode(clippedPath, tgtNode, true);
                    boolean tgtOverPenetrate = terminalSegmentOverPenetrates(conn.pathPoints(), tgtNode, true);
                    if (tgtNonTermHit || tgtOverPenetrate) {
                        descriptions.add("Connection '" + conn.id()
                                + "' routes through its own target element '" + tgtNode.id() + "'");
                    }
                }

                AssessmentNode srcNode = nodeMap.get(conn.sourceNodeId());
                if (srcNode != null && !srcNode.isContainer()
                        && descriptions.size() < MAX_DESCRIPTIONS) {
                    boolean srcNonTermHit = nonTerminalPassesThroughNode(clippedPath, srcNode, false);
                    boolean srcOverPenetrate = terminalSegmentOverPenetrates(conn.pathPoints(), srcNode, false);
                    if (srcNonTermHit || srcOverPenetrate) {
                        descriptions.add("Connection '" + conn.id()
                                + "' routes through its own source element '" + srcNode.id() + "'");
                    }
                }
            }
        }
        return new PassThroughResult(descriptions, crossElementCount, violatorIds);
    }

    // ---- Connection-Through/Graze-Visual Detection (notes & images) ----

    /**
     * Result of connection-vs-visual detection. {@code count} is the number of
     * (connection, visual) interior PENETRATIONS across all Notes and image-bearing elements
     * (drives routing Tier-3R, cap-good); {@code grazeCount} is the number of border GRAZES —
     * routes touching a visual's outer band (the ring the {@link #PASS_THROUGH_INSET} shrink
     * discards), including visuals too small to inset (informational only, no rating impact).
     * Penetration and graze are mutually exclusive per (connection, visual), so the two counts
     * are disjoint by construction. {@code descriptions} / {@code grazeDescriptions} name each,
     * capped at {@link #MAX_DESCRIPTIONS}.
     */
    record ConnectionThroughVisualResult(int count, List<String> descriptions,
            int grazeCount, List<String> grazeDescriptions) {}

    /**
     * Detects connections whose route passes through a Note or an Image visual — clutter that
     * {@link #detectPassThroughs} does not report for Notes, which are split out of the scoring
     * node set. An element's rendered image rectangle is clipped to its element box, so a route
     * through an element's image also crosses that element's box; the two detectors then report
     * the same crossing, and the routing tier takes the worse of the two rather than charging
     * it twice.
     *
     * <p><strong>Notes:</strong> a Note has no source/target, so every connection is tested
     * against every Note's rectangle. <strong>Images:</strong> an image-bearing element's
     * RENDERED image rectangle (from {@link #estimateImageBounds}) is tested; the connection's
     * own source/target, and the ancestors and descendants that still overlap them, are excluded
     * so an image on a connection's own endpoint/container is not flagged — mirroring the element carve-out in
     * {@link #detectPassThroughs} (lines that build {@code excludeIds}).</p>
     *
     * <p>Geometry is the SAME predicate used for elements: {@link #clipPathToVisualEdges} +
     * {@link #pathPassesThroughNode} (with its {@link #PASS_THROUGH_INSET} shrink), so a route
     * merely grazing a visual's edge is treated no stricter and no looser than for an element,
     * and a visual smaller than the inset can never be flagged. The {@code count} drives a routing
     * Tier-3R (cap-good) breakdown entry on binary presence; it is tracked separately from
     * {@code connectionPassThroughs}, which it never perturbs.</p>
     */
    ConnectionThroughVisualResult detectConnectionThroughVisuals(
            List<AssessmentConnection> connections,
            List<AssessmentNode> layoutNodes,
            List<AssessmentNode> noteNodes) {
        Map<String, AssessmentNode> nodeMap = new HashMap<>();
        for (AssessmentNode node : layoutNodes) {
            nodeMap.put(node.id(), node);
        }

        int count = 0;
        List<String> descriptions = new ArrayList<>();
        int grazeCount = 0;
        List<String> grazeDescriptions = new ArrayList<>();
        for (AssessmentConnection conn : connections) {
            List<double[]> clippedPath = clipPathToVisualEdges(
                    conn.pathPoints(),
                    nodeMap.get(conn.sourceNodeId()),
                    nodeMap.get(conn.targetNodeId()));

            // Notes: no endpoint relationship — test every note's rectangle.
            for (AssessmentNode note : noteNodes) {
                if (pathPassesThroughNode(clippedPath, note)) {
                    count++;
                    if (descriptions.size() < MAX_DESCRIPTIONS) {
                        descriptions.add("Connection '" + conn.id()
                                + "' routes through note '" + note.id() + "'");
                    }
                } else if (pathIntersectsRect(clippedPath,
                        note.x(), note.y(), note.width(), note.height())) {
                    // Touches the note's outer band but not its inset interior — a border graze.
                    grazeCount++;
                    if (grazeDescriptions.size() < MAX_DESCRIPTIONS) {
                        grazeDescriptions.add("Connection '" + conn.id()
                                + "' grazes the border of note '" + note.id() + "'");
                    }
                }
            }

            // Images: an image on the connection's own source/target/ancestor/descendant is
            // not a pass-through — exclude the same id set the element loop excludes.
            Set<String> excludeIds = new HashSet<>();
            excludeIds.add(conn.sourceNodeId());
            excludeIds.add(conn.targetNodeId());
            excludeIds.addAll(getAncestorIds(conn.sourceNodeId(), nodeMap));
            excludeIds.addAll(getAncestorIds(conn.targetNodeId(), nodeMap));
            excludeIds.addAll(getDescendantIds(conn.sourceNodeId(), layoutNodes));
            excludeIds.addAll(getDescendantIds(conn.targetNodeId(), layoutNodes));
            for (AssessmentNode node : layoutNodes) {
                if (node.imagePath() == null || node.isContainer() || excludeIds.contains(node.id())) {
                    continue;
                }
                double[] imgBounds = estimateImageBounds(node);
                if (imgBounds == null) {
                    continue;
                }
                // Synthesise a geometry-only node carrying the image rectangle so the shared
                // pathPassesThroughNode predicate (and its inset) applies unchanged.
                AssessmentNode imageRect = new AssessmentNode(node.id(),
                        imgBounds[0], imgBounds[1], imgBounds[2], imgBounds[3],
                        null, false, false, null, 0.0, null, null, 0.0, 0.0, 0.0);
                if (pathPassesThroughNode(clippedPath, imageRect)) {
                    count++;
                    if (descriptions.size() < MAX_DESCRIPTIONS) {
                        descriptions.add("Connection '" + conn.id()
                                + "' routes through image of element '" + node.id() + "'");
                    }
                } else if (pathIntersectsRect(clippedPath,
                        imgBounds[0], imgBounds[1], imgBounds[2], imgBounds[3])) {
                    // Touches the image's outer band but not its inset interior — a border graze.
                    grazeCount++;
                    if (grazeDescriptions.size() < MAX_DESCRIPTIONS) {
                        grazeDescriptions.add("Connection '" + conn.id()
                                + "' grazes the border of image of element '" + node.id() + "'");
                    }
                }
            }
        }
        return new ConnectionThroughVisualResult(count, descriptions, grazeCount, grazeDescriptions);
    }

    /**
     * Checks if non-terminal segments of a path pass through a node.
     * For target elements, skips the last segment (which naturally enters the target).
     * For source elements, skips the first segment (which naturally exits the source).
     *
     * @param path     clipped path points
     * @param node     the source or target element to check
     * @param isTarget true if checking target element (skip last segment),
     *                 false if checking source element (skip first segment)
     * @return true if a non-terminal segment passes through the node
     */
    boolean nonTerminalPassesThroughNode(List<double[]> path, AssessmentNode node,
                                          boolean isTarget) {
        double insetX = node.x() + SELF_ELEMENT_INSET;
        double insetY = node.y() + SELF_ELEMENT_INSET;
        double insetW = node.width() - 2 * SELF_ELEMENT_INSET;
        double insetH = node.height() - 2 * SELF_ELEMENT_INSET;
        if (insetW <= 0 || insetH <= 0) return false;

        // For target: check segments 0..n-3 (skip last segment n-2..n-1)
        // For source: check segments 1..n-2 (skip first segment 0..1)
        int start = isTarget ? 0 : 1;
        int end = isTarget ? path.size() - 2 : path.size() - 1;

        for (int i = start; i < end; i++) {
            if (lineSegmentIntersectsRect(
                    path.get(i)[0], path.get(i)[1],
                    path.get(i + 1)[0], path.get(i + 1)[1],
                    insetX, insetY, insetW, insetH)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the connection's stored (pre-clip) path penetrates its own
     * source or target element body STRICTLY past the element's center along the
     * dominant entry axis. This catches terminal-segment over-penetration that
     * {@link #nonTerminalPassesThroughNode} misses because that method excludes
     * the terminal segment from its loop window (a natural-approach optimisation
     * that incorrectly suppresses detection when the stored terminal point sits
     * deep inside the element body).
     *
     * <p>For target-side checks ({@code isTarget=true}), the predicate fires when
     * the stored final point is inside the target body and lies STRICTLY past target
     * center along the segment's dominant entry axis. For source-side checks
     * ({@code isTarget=false}), it fires when the stored first point is inside the
     * source body and lies STRICTLY past source center along the segment's
     * dominant exit axis.
     *
     * <p>Strictness ({@code >} / {@code <}, not {@code >=} / {@code <=}) is required
     * because the universal Archi connection-anchor convention places the stored
     * first/last bendpoint AT the element center ({@link #clipPathToVisualEdges}
     * later transforms these to element edges for visual rendering). A non-strict
     * comparison would over-trigger on every center-anchored 3+ point path. The
     * detected pattern is therefore "terminal point past center" — i.e. the stored
     * bendpoint sits in the element's far half relative to the entry/exit direction,
     * which is pathological (no natural routing produces a stored bendpoint past
     * element center on the wrong side of the connection's natural anchor).
     *
     * @param unclippedPath stored path points (pre-clip — clipPathToVisualEdges NOT applied)
     * @param node          the source or target element to check
     * @param isTarget      true if checking target element (final point), false if source (first point)
     * @return true if the terminal point over-penetrates the element body past center
     */
    boolean terminalSegmentOverPenetrates(List<double[]> unclippedPath, AssessmentNode node,
                                          boolean isTarget) {
        if (unclippedPath.size() < 2) return false;
        double[] terminalPoint = isTarget
                ? unclippedPath.get(unclippedPath.size() - 1)
                : unclippedPath.get(0);
        double[] otherPoint = isTarget
                ? unclippedPath.get(unclippedPath.size() - 2)
                : unclippedPath.get(1);
        double tx = terminalPoint[0];
        double ty = terminalPoint[1];
        double minX = node.x();
        double maxX = node.x() + node.width();
        double minY = node.y();
        double maxY = node.y() + node.height();
        // Terminal must be inside element body (boundary inclusive)
        if (tx < minX || tx > maxX || ty < minY || ty > maxY) return false;
        double centerX = node.x() + node.width() / 2.0;
        double centerY = node.y() + node.height() / 2.0;
        double dx = Math.abs(tx - otherPoint[0]);
        double dy = Math.abs(ty - otherPoint[1]);
        // Over-penetration: terminal lies STRICTLY past element center along
        // the dominant entry axis. Strict inequalities preserve the
        // center-anchor convention (terminal AT center → not detected).
        if (dx >= dy) {
            // Horizontal-dominant. If approaching from west of terminal
            // (otherPoint.x < tx), over-penetration iff tx > centerX.
            // If approaching from east (otherPoint.x > tx), iff tx < centerX.
            return otherPoint[0] < tx ? tx > centerX : tx < centerX;
        } else {
            // Vertical-dominant.
            return otherPoint[1] < ty ? ty > centerY : ty < centerY;
        }
    }

    /**
     * Returns true if any segment of {@code path} intersects the rectangle, tested VERBATIM
     * (no {@link #PASS_THROUGH_INSET} shrink). Where {@link #pathPassesThroughNode} detects a
     * route penetrating a visual's inset interior, this detects a route touching the visual's
     * outer band — the border ring the inset discards. Used as the {@code else}-branch to
     * {@code pathPassesThroughNode} so a single (connection, visual) is classified as either an
     * interior penetration or a border graze, never both. Because the rect is not shrunk, a
     * visual too small to inset (where {@code pathPassesThroughNode} returns false) is still
     * caught here when a route crosses it.
     */
    private boolean pathIntersectsRect(List<double[]> path,
                                       double rx, double ry, double rw, double rh) {
        return GeometryUtils.pathIntersectsRect(path, rx, ry, rw, rh);
    }

    private boolean pathPassesThroughNode(List<double[]> path, AssessmentNode node) {
        // Shrink obstacle rect by PASS_THROUGH_INSET to absorb corner-arc imprecision. The policy
        // lives in GeometryUtils because the routing side discloses the same crossing on the call
        // that creates it, and the two must not be able to drift apart.
        return GeometryUtils.pathPassesThroughRect(path,
                node.x(), node.y(), node.width(), node.height(), PASS_THROUGH_INSET);
    }

    /**
     * Clips path endpoints from element centers to element perimeters.
     *
     * <p>Archi uses OrthogonalAnchor (default) which projects the reference point's
     * coordinate onto the nearest edge — fundamentally different from ChopboxAnchor's
     * ray-intersection approach. BendpointConnectionRouter uses the first bendpoint
     * as reference for the source anchor and the last bendpoint for the target anchor;
     * without bendpoints it falls back to the opposite endpoint's center.</p>
     */
    List<double[]> clipPathToVisualEdges(List<double[]> path,
                                          AssessmentNode srcNode,
                                          AssessmentNode tgtNode) {
        return GeometryUtils.clipPathToRectEdges(path, rectOf(srcNode), rectOf(tgtNode));
    }

    /** {@code [x, y, width, height]} for the shared clip, or null for an unresolved endpoint. */
    private static double[] rectOf(AssessmentNode node) {
        return node == null ? null
                : new double[]{node.x(), node.y(), node.width(), node.height()};
    }

    /**
     * Computes the perimeter exit point using Archi's OrthogonalAnchor model.
     *
     * <p>If the reference point's x or y falls within the element bounds,
     * the exit projects that coordinate onto the nearest edge (orthogonal exit).
     * For diagonal references (both x and y outside bounds), falls back to
     * ChopboxAnchor-style ray intersection since both anchors produce similar
     * results in corner zones.</p>
     */
    double[] orthogonalExitPoint(double rx, double ry, double rw, double rh,
                                  double refX, double refY) {
        return GeometryUtils.orthogonalExitPoint(rx, ry, rw, rh, refX, refY);
    }

    /**
     * Finds where a ray from (x1,y1) toward (x2,y2) exits the given rectangle.
     * Assumes (x1,y1) is inside the rectangle. Returns the exit point,
     * or null if the ray is degenerate (zero length).
     * Used as fallback for diagonal OrthogonalAnchor zones.
     */
    double[] rectExitPoint(double x1, double y1, double x2, double y2,
                            double rx, double ry, double rw, double rh) {
        return GeometryUtils.rectExitPoint(x1, y1, x2, y2, rx, ry, rw, rh);
    }

    /**
     * Tests if a line segment intersects an axis-aligned rectangle.
     * Delegates to {@link GeometryUtils#lineSegmentIntersectsRect(double, double, double, double, double, double, double, double)}.
     */
    static boolean lineSegmentIntersectsRect(double x1, double y1, double x2, double y2,
                                              double rx, double ry, double rw, double rh) {
        return GeometryUtils.lineSegmentIntersectsRect(x1, y1, x2, y2, rx, ry, rw, rh);
    }

    // ---- Off-Canvas Detection ----

    List<String> detectOffCanvas(List<AssessmentNode> nodes) {
        List<String> warnings = new ArrayList<>();
        for (AssessmentNode node : nodes) {
            if (warnings.size() >= MAX_DESCRIPTIONS) break;

            if (node.x() < 0 || node.y() < 0) {
                warnings.add("Element '" + node.id()
                        + "' is at negative coordinates (" + (int) node.x()
                        + ", " + (int) node.y() + ")");
            } else if (node.x() > OFF_CANVAS_THRESHOLD || node.y() > OFF_CANVAS_THRESHOLD
                    || node.x() + node.width() > OFF_CANVAS_THRESHOLD
                    || node.y() + node.height() > OFF_CANVAS_THRESHOLD) {
                warnings.add("Element '" + node.id()
                        + "' extends beyond canvas bounds at (" + (int) node.x()
                        + ", " + (int) node.y() + ")");
            }
        }
        return warnings;
    }

    // ---- Label Overlap Detection ----

    // Keep in sync with LabelClearance.CHAR_WIDTH etc.
    // (duplicated due to architecture boundary: model vs model.routing)
    /** Estimated character width in pixels (Archi's default ~11pt font). */
    static final double LABEL_CHAR_WIDTH = 8.0;
    /** Estimated character height in pixels. */
    static final double LABEL_CHAR_HEIGHT = 14.0;
    /** Horizontal padding around label text. */
    static final double LABEL_PADDING_X = 10.0;
    /** Vertical padding around label text. */
    static final double LABEL_PADDING_Y = 6.0;
    /**
     * Render-calibration factor for connection-label glyph width.
     * <p>The char-count model ({@code length() * LABEL_CHAR_WIDTH}) measures a label narrower than Archi
     * actually renders it, so the connection-label overlap detector under-flags label-on-element /
     * label-on-label on short, tight segments where the rendered glyph bleeds past the measured box.
     * <p><b>Evidence basis (informed approximation, not a directly measured edge-label value).</b> The
     * {@code 1.35} factor was observed in the labelTruncations calibration (see {@code detectLabelTruncation}
     * Javadoc), where Archi rendered an <i>element</i> box label ~1.35x wider than its SWT
     * {@code ElementSizer}-measured single-line width. That measurement was SWT-vs-rendered for box labels;
     * the connection-label width calibrated here is a pure char-count estimate (no SWT), so the transfer is
     * an informed approximation rather than a measured edge-label ratio. The connection-label font/zoom is
     * expected to exhibit a similar ratio; tune this factor against the live render if it over- or
     * under-flags (the value, not the placement, is the knob).
     * <p><b>Detection-only.</b> This factor is NOT applied by the routing reserver
     * ({@code LabelClearance} / {@code LabelWidthEstimator}), which deliberately reserve the raw estimate.
     * The detector is intentionally more render-accurate than the reserver — the reserver's mild
     * under-reservation is a separate, known property.
     */
    static final double LABEL_RENDER_WIDTH_FACTOR = 1.35;
    /** Inset margin applied to label bounds before overlap checks.
     *  Labels must overlap by at least this much on each side to count.
     *  Prevents false positives from estimated bounding boxes barely touching. */
    static final double LABEL_OVERLAP_INSET = 10.0;

    /** Proximity threshold in pixels for label-to-element and label-to-label near-miss detection.
     *  Labels within this distance of an element or another label (but not technically overlapping
     *  after inset) are flagged as proximity issues. */
    static final double LABEL_PROXIMITY_THRESHOLD = 5.0;

    /** Minimum fraction of a connection label's area that must overlap its OWN source/target box
     *  before it is flagged as rendered-on-its-endpoint. Asymmetric by design: a label always grazes
     *  the box it attaches to, so own-endpoint overlap is TOLERATED up to this fraction — whereas an
     *  overlap with an unrelated/third-party element uses the more sensitive inset rule. Calibrated
     *  against the live render: the Layout &amp; Routing Pipeline captions bleed ~31–37% onto their
     *  neighbouring boxes (flagged), a short label that fits its inter-box gap is 0% (ignored), and a
     *  label only lightly wider than its gap stays below this bar (ignored). This value is the tuning
     *  knob, not the placement. */
    static final double LABEL_OWN_ENDPOINT_OVERLAP_FRACTION = 0.30;

    /** Promoted (lowered) own-endpoint overlap bar for a label whose rendered width EXCEEDS its hosting
     *  segment. Such a label provably cannot fit the inter-endpoint span and necessarily drapes across the
     *  endpoint box(es); the symmetric bounds estimate then SPLITS that bleed between two neighbours, so each
     *  per-box fraction sits below the normal {@link #LABEL_OWN_ENDPOINT_OVERLAP_FRACTION} bar even though the
     *  label visibly covers the boxes — the wide-label-on-short-segment miss (e.g. a ~36-character Middle label
     *  centred on a ~200px segment, where each endpoint box captures only ~25% of the wide label's area: under the
     *  0.30 bar individually yet visibly on both boxes). The fraction-of-label metric is unreliable for such
     *  labels (it SHRINKS as the label widens), so the gate is "width exceeds the
     *  hosting segment" and this lower fraction is the trigger. Applied ONLY in that regime; a label that fits
     *  its segment keeps the tolerant 0.30 bar. Calibrated against the live render — this value, not the gate,
     *  is the tuning knob; over-flagging is the failure mode to guard. Detection-only (same property as
     *  {@link #LABEL_RENDER_WIDTH_FACTOR}: the routing reserver is untouched). */
    static final double LABEL_OWN_ENDPOINT_SHORT_SEGMENT_OVERLAP_FRACTION = 0.15;

    /** Minimum fraction of an ENDPOINT BOX's area that must sit under a connection's own label before it is
     *  flagged as rendered-on-its-endpoint — the box-normalised companion to
     *  {@link #LABEL_OWN_ENDPOINT_OVERLAP_FRACTION}. The label-area fraction is structurally unreachable for a
     *  genuinely tiny endpoint box (an ArchiMate Junction at its ~14x14 default): a label can FULLY enclose
     *  the box yet cover only ~0.13 of the much larger label's own area, so it never trips the 0.30 bar even
     *  though the text visibly sits on the box. This rule flags the inverse — the label covers a substantial
     *  fraction of the BOX — and is OR'd with the label-area rule so the two together catch both "wide label
     *  on a normal box" (label-area) and "any label fully over a tiny box" (box-coverage). Naturally
     *  self-limiting to small boxes: coverage &ge; this bar requires {@code boxArea &le; labelArea / bar}, so a
     *  label far smaller than a normal element box can never reach it (a normal box is never over-flagged).
     *  Calibrated against the live render — this value is the tuning knob; over-flagging tiny-ish boxes is the
     *  failure mode to guard. Detection-only (same property as {@link #LABEL_RENDER_WIDTH_FACTOR}). Mirrored in
     *  {@code LabelPositionOptimizer.LABEL_OWN_ENDPOINT_BOX_COVERAGE_FRACTION} so the offset FIX engages exactly
     *  where DETECTION flags. */
    static final double LABEL_OWN_ENDPOINT_BOX_COVERAGE_FRACTION = 0.6;

    /** Near-zero own-endpoint overlap bar used when the endpoint is an ArchiMate <em>Junction</em>, replacing the
     *  box-tolerant {@link #LABEL_OWN_ENDPOINT_OVERLAP_FRACTION} (and its short-segment promotion) for that endpoint
     *  only. A junction renders as a solid (dark/black) shape scaled to its bounds with NO usable interior — any
     *  label area on it is black-on-dark and unreadable, so the grazing tolerance that is correct for a normal box
     *  (whose whitespace/header a label may clip harmlessly) is wrong for a junction: <em>any</em> non-trivial
     *  overlap is a defect. This catches the case the label-area bar misses on an OVERSIZED junction (e.g. the
     *  120x55 default) where a small label grazes the fill at a label-area fraction just under 0.30 yet the render
     *  is clearly "on the fill"; the {@link #LABEL_OWN_ENDPOINT_BOX_COVERAGE_FRACTION} branch independently still
     *  catches a TINY (~14x14) junction fully under a label. The small non-zero floor (not 0) tolerates a 1px graze
     *  by a label that has genuinely cleared the junction. Calibrated against the live render — this value is the
     *  tuning knob; over-flagging a label that clears the junction is the failure mode to guard. Detection-only
     *  (same property as {@link #LABEL_RENDER_WIDTH_FACTOR}). */
    static final double LABEL_OWN_ENDPOINT_JUNCTION_OVERLAP_FRACTION = 0.05;

    /** Assumed rendered magnitude (px) of an applied "Label Offset" anchor, used to displace a label's
     *  estimated bounds before the own-endpoint overlap check so a label already lifted off its box is not
     *  re-reported as bleeding. The offset feature stores only a compass DIRECTION — the renderer fixes the
     *  magnitude — so this is an estimate; it mirrors the optimizer's {@code OFFSET_SCORING_DISTANCE} and is
     *  the tuning knob if the metric under- or over-credits an offset against the live render. */
    static final double LABEL_OFFSET_RENDER_ESTIMATE = 40.0;

    record LabelBounds(double x, double y, double width, double height, String connectionId) {}

    record LabelOverlapResult(int count, List<String> descriptions, int shortSegmentCount) {
        /** Backward-compatible constructor without shortSegmentCount. */
        LabelOverlapResult(int count, List<String> descriptions) {
            this(count, descriptions, 0);
        }
    }

    /**
     * Result of label-on-note detection. Informational only — does NOT affect the rating.
     * A connection's label can be positioned independently of its line, so this is distinct
     * from the route-vs-visual counts (a label may sit on a note while the route runs clear),
     * and distinct from {@link LabelOverlapResult} (which covers labels over non-note elements
     * and other labels, and feeds the rating). {@code violatorIds} are the note ids carrying a
     * label, surfaced only when violator collection is requested.
     */
    record LabelOnNoteResult(int count, List<String> descriptions, Set<String> violatorIds) {}

    record LabelOnGroupResult(int count, List<String> descriptions, Set<String> violatorIds) {}

    /**
     * Estimates the bounding box of a connection label based on its text position
     * along the path. Position 0=source (15%), 1=middle (50%), 2=target (85%).
     * Returns null if labelText is empty or path has fewer than 2 points.
     */
    LabelBounds estimateLabelBounds(AssessmentConnection conn) {
        String label = conn.labelText();
        if (label == null || label.isEmpty()) {
            return null;
        }
        List<double[]> path = conn.pathPoints();
        if (path.size() < 2) {
            return null;
        }

        // Calibrate the glyph run toward Archi's rendered width (~1.35x the char-count measure); the
        // constant padding chrome is not scaled. See LABEL_RENDER_WIDTH_FACTOR.
        double labelWidth = label.length() * LABEL_CHAR_WIDTH * LABEL_RENDER_WIDTH_FACTOR + LABEL_PADDING_X;
        double labelHeight = LABEL_CHAR_HEIGHT + LABEL_PADDING_Y;

        // Compute total path length
        double totalLength = 0;
        for (int i = 0; i < path.size() - 1; i++) {
            double dx = path.get(i + 1)[0] - path.get(i)[0];
            double dy = path.get(i + 1)[1] - path.get(i)[1];
            totalLength += Math.sqrt(dx * dx + dy * dy);
        }

        if (totalLength < 1.0) {
            return null;
        }

        // Determine position along path
        double fraction;
        switch (conn.textPosition()) {
            case 0:  fraction = 0.15; break; // source
            case 2:  fraction = 0.85; break; // target
            default: fraction = 0.50; break; // middle (default)
        }

        double targetDist = totalLength * fraction;

        // Walk path to find the point at targetDist
        double accumulated = 0;
        double cx = path.get(0)[0];
        double cy = path.get(0)[1];

        for (int i = 0; i < path.size() - 1; i++) {
            double dx = path.get(i + 1)[0] - path.get(i)[0];
            double dy = path.get(i + 1)[1] - path.get(i)[1];
            double segLen = Math.sqrt(dx * dx + dy * dy);
            if (accumulated + segLen >= targetDist) {
                double remaining = targetDist - accumulated;
                double t = (segLen > 0) ? remaining / segLen : 0;
                cx = path.get(i)[0] + dx * t;
                cy = path.get(i)[1] + dy * t;
                break;
            }
            accumulated += segLen;
        }

        // Center label at the computed point
        return new LabelBounds(
                cx - labelWidth / 2, cy - labelHeight / 2,
                labelWidth, labelHeight, conn.id());
    }

    /**
     * Counts label overlaps: labels overlapping nodes and labels overlapping other labels.
     */
    LabelOverlapResult countLabelOverlaps(List<AssessmentConnection> connections,
                                           List<AssessmentNode> nodes) {
        List<LabelBounds> allLabels = new ArrayList<>();
        for (AssessmentConnection conn : connections) {
            LabelBounds lb = estimateLabelBounds(conn);
            if (lb != null) {
                allLabels.add(lb);
            }
        }

        int count = 0;
        List<String> descriptions = new ArrayList<>();

        // Build node map for ancestor/descendant lookups
        Map<String, AssessmentNode> nodeMap = new HashMap<>();
        for (AssessmentNode node : nodes) {
            nodeMap.put(node.id(), node);
        }

        // Build per-connection exclusion sets: source, target, ancestors, descendants
        // (same logic as detectPassThroughs — labels naturally sit within ancestor groups)
        Map<String, Set<String>> connExcludeMap = new HashMap<>();
        for (AssessmentConnection conn : connections) {
            Set<String> excludeIds = new HashSet<>();
            excludeIds.add(conn.sourceNodeId());
            excludeIds.add(conn.targetNodeId());
            excludeIds.addAll(getAncestorIds(conn.sourceNodeId(), nodeMap));
            excludeIds.addAll(getAncestorIds(conn.targetNodeId(), nodeMap));
            excludeIds.addAll(getDescendantIds(conn.sourceNodeId(), nodes));
            excludeIds.addAll(getDescendantIds(conn.targetNodeId(), nodes));
            connExcludeMap.put(conn.id(), excludeIds);
        }

        // Check label-node overlaps and proximity (skip source, target, ancestors, descendants, and groups)
        // Apply inset margin to label bounds to avoid false positives from estimation error
        for (LabelBounds label : allLabels) {
            Set<String> excludeIds = connExcludeMap.getOrDefault(label.connectionId(), Set.of());
            for (AssessmentNode node : nodes) {
                // Skip source, target, ancestors, descendants, and groups (transparent containers)
                if (excludeIds.contains(node.id()) || node.isContainer()) {
                    continue;
                }
                if (insetRectOverlap(label, node.x(), node.y(), node.width(), node.height())) {
                    count++;
                    if (descriptions.size() < MAX_DESCRIPTIONS) {
                        descriptions.add("Label on connection '" + label.connectionId()
                                + "' overlaps element '" + node.id() + "'");
                    }
                } else if (isWithinProximity(label, node.x(), node.y(), node.width(), node.height())) {
                    // Label-to-element proximity detection
                    count++;
                    if (descriptions.size() < MAX_DESCRIPTIONS) {
                        descriptions.add("Label on connection '" + label.connectionId()
                                + "' is too close to element '" + node.id() + "'");
                    }
                }
            }
        }

        // Check label-label overlaps and near-misses (apply inset to both labels)
        for (int i = 0; i < allLabels.size(); i++) {
            for (int j = i + 1; j < allLabels.size(); j++) {
                LabelBounds a = allLabels.get(i);
                LabelBounds b = allLabels.get(j);
                if (insetRectOverlap(a, b.x(), b.y(), b.width(), b.height())) {
                    count++;
                    if (descriptions.size() < MAX_DESCRIPTIONS) {
                        descriptions.add("Label on connection '" + a.connectionId()
                                + "' overlaps label on connection '" + b.connectionId() + "'");
                    }
                } else if (isWithinProximity(a, b.x(), b.y(), b.width(), b.height())) {
                    // Label-to-label near-miss detection
                    count++;
                    if (descriptions.size() < MAX_DESCRIPTIONS) {
                        descriptions.add("Label on connection '" + a.connectionId()
                                + "' is too close to label on connection '" + b.connectionId() + "'");
                    }
                }
            }
        }

        // Own-endpoint detection: a label rendered ON its own source/target box.
        // The label-node loop above EXCLUDES source/target (a label always grazes the box it attaches
        // to, so a sensitive rule would flood). We add it back with a TOLERANT, asymmetric rule: flag
        // only when a substantial fraction (>= LABEL_OWN_ENDPOINT_OVERLAP_FRACTION) of the label's area
        // sits over the endpoint body — i.e. the text is genuinely on the box, not merely near it. A
        // label that fits its inter-box gap overlaps 0%; one only lightly wider stays below the bar.
        // Counted at most ONCE per label (a Middle label bleeding onto both neighbours is one problem),
        // naming the more-overlapped endpoint. Reuses the render-calibrated LabelBounds. Ordered after
        // the third-party/label descriptions so the more actionable ones keep priority within MAX_DESCRIPTIONS.
        Map<String, AssessmentConnection> connById = new HashMap<>();
        for (AssessmentConnection conn : connections) {
            connById.put(conn.id(), conn);
        }
        for (LabelBounds label : allLabels) {
            AssessmentConnection conn = connById.get(label.connectionId());
            if (conn == null) continue;
            String src = conn.sourceNodeId();
            String tgt = conn.targetNodeId();
            AssessmentNode srcNode = nodeMap.get(src);
            // Skip the target for a self-connection (source == target) to avoid double measuring.
            boolean hasTgt = tgt != null && !tgt.equals(src);
            AssessmentNode tgtNode = hasTgt ? nodeMap.get(tgt) : null;
            // Credit an applied "Label Offset": displace the estimated bounds by the rendered offset so a label
            // already lifted clear of its box is not re-counted. CENTER/unsupported leaves the bounds untouched
            // (today's behaviour); an insufficient offset still bleeds and is still flagged (truthful, not blunt).
            LabelBounds effective = offsetAdjustedBounds(label, conn.relativePosition());
            double fSrc = ownEndpointOverlapFraction(effective, srcNode);
            double fTgt = hasTgt ? ownEndpointOverlapFraction(effective, tgtNode) : 0.0;
            // Box-coverage companion: the label-area fraction above shrinks toward 0 as the endpoint box
            // shrinks, so a tiny junction fully under the label never trips the bar. Measure the inverse —
            // the fraction of the BOX area under the (offset-adjusted) label — and OR it in below. Self-
            // limiting to small boxes (a normal box is far larger than the label, so its coverage stays low).
            double cSrc = ownEndpointBoxCoverageFraction(effective, srcNode);
            double cTgt = hasTgt ? ownEndpointBoxCoverageFraction(effective, tgtNode) : 0.0;
            // A label whose RENDERED width exceeds its hosting segment cannot fit the inter-endpoint span and
            // necessarily drapes across the endpoint box(es). The symmetric estimate then splits that bleed
            // between two neighbours, so each per-box fraction stays under the normal 0.30 bar even though the
            // label visibly covers the boxes (the wide-label-on-short-segment miss). Promote (lower) the bar in
            // that regime only; a label that fits its segment keeps the tolerant 0.30 bar. See
            // LABEL_OWN_ENDPOINT_SHORT_SEGMENT_OVERLAP_FRACTION. Uses the un-offset label width vs the geometry's
            // hosting segment (offset shifts position, not width); fSrc/fTgt are already offset-credited above.
            double baseThreshold = (label.width() > hostingSegmentLength(conn))
                    ? LABEL_OWN_ENDPOINT_SHORT_SEGMENT_OVERLAP_FRACTION
                    : LABEL_OWN_ENDPOINT_OVERLAP_FRACTION;
            // A junction renders as a solid dark shape with no usable interior — any label area on it is
            // unreadable, so the box-tolerant bar (and its short-segment promotion) is wrong for a junction
            // endpoint: use the near-zero junction bar instead, PER ENDPOINT. The box-coverage branch below is
            // unchanged (it independently catches a tiny junction fully under a label). See
            // LABEL_OWN_ENDPOINT_JUNCTION_OVERLAP_FRACTION.
            double srcThreshold = (srcNode != null && srcNode.isJunction())
                    ? LABEL_OWN_ENDPOINT_JUNCTION_OVERLAP_FRACTION : baseThreshold;
            double tgtThreshold = (tgtNode != null && tgtNode.isJunction())
                    ? LABEL_OWN_ENDPOINT_JUNCTION_OVERLAP_FRACTION : baseThreshold;
            // An endpoint is "on" when EITHER its label-area fraction clears the (possibly promoted/junction) bar
            // OR the label covers a substantial fraction of its (tiny) box. Counted at most once per label.
            boolean srcOn = fSrc >= srcThreshold || cSrc >= LABEL_OWN_ENDPOINT_BOX_COVERAGE_FRACTION;
            boolean tgtOn = fTgt >= tgtThreshold || cTgt >= LABEL_OWN_ENDPOINT_BOX_COVERAGE_FRACTION;
            if (srcOn || tgtOn) {
                count++;
                // Name the triggering endpoint; when both fire (a Middle label bleeding onto both neighbours)
                // name the more-overlapped one by label-area — preserving the shipped naming order.
                String endpoint = (srcOn && tgtOn) ? (fSrc >= fTgt ? src : tgt) : (srcOn ? src : tgt);
                if (descriptions.size() < MAX_DESCRIPTIONS) {
                    descriptions.add("Label on connection '" + label.connectionId()
                            + "' is rendered on its own endpoint '" + endpoint + "'");
                }
            }
        }

        // Short-segment detection
        // When a label's hosting segment is shorter than the label width,
        // the label cannot fit regardless of position. Report specific guidance.
        int shortSegmentCount = 0;
        for (LabelBounds label : allLabels) {
            AssessmentConnection conn = connById.get(label.connectionId());
            if (conn == null) continue;
            List<double[]> path = conn.pathPoints();
            if (path.size() < 2) continue;

            // Intentionally the render-calibrated width (LabelBounds carries the LABEL_RENDER_WIDTH_FACTOR
            // value): the "exceeds segment length" guidance must judge whether the *rendered* glyph fits the
            // hosting segment, so it shares the same width yardstick as the overlap detection above.
            double labelWidth = label.width();

            // Find the hosting segment (the segment containing the label center point)
            double fraction;
            switch (conn.textPosition()) {
                case 0:  fraction = 0.15; break;
                case 2:  fraction = 0.85; break;
                default: fraction = 0.50; break;
            }

            double totalLength = 0;
            for (int i = 0; i < path.size() - 1; i++) {
                double dx = path.get(i + 1)[0] - path.get(i)[0];
                double dy = path.get(i + 1)[1] - path.get(i)[1];
                totalLength += Math.sqrt(dx * dx + dy * dy);
            }
            double targetDist = totalLength * fraction;

            // Walk path to find hosting segment
            double accumulated = 0;
            for (int i = 0; i < path.size() - 1; i++) {
                double dx = path.get(i + 1)[0] - path.get(i)[0];
                double dy = path.get(i + 1)[1] - path.get(i)[1];
                double segLen = Math.sqrt(dx * dx + dy * dy);
                if (accumulated + segLen >= targetDist || i == path.size() - 2) {
                    // This is the hosting segment
                    boolean isHorizontal = Math.abs(dy) < 2.0;
                    boolean isVertical = Math.abs(dx) < 2.0;

                    if (isHorizontal && segLen < labelWidth) {
                        // Horizontal segment too short for label
                        shortSegmentCount++;
                        if (descriptions.size() < MAX_DESCRIPTIONS) {
                            String srcName = conn.sourceNodeId();
                            String tgtName = conn.targetNodeId();
                            descriptions.add("Label on connection '" + label.connectionId()
                                    + "' exceeds segment length — increase spacing between "
                                    + srcName + " and " + tgtName);
                        }
                    } else if (isVertical) {
                        // Vertical segment: check if all 3 positions produce overlaps
                        // (label optimizer already ran — if we still have an overlap for this connection,
                        // it means all positions were exhausted)
                        Set<String> excludeIds = connExcludeMap.getOrDefault(label.connectionId(), Set.of());
                        boolean hasOverlap = false;
                        for (AssessmentNode node : nodes) {
                            if (excludeIds.contains(node.id()) || node.isContainer()) continue;
                            if (insetRectOverlap(label, node.x(), node.y(), node.width(), node.height())
                                    || isWithinProximity(label, node.x(), node.y(), node.width(), node.height())) {
                                hasOverlap = true;
                                break;
                            }
                        }
                        if (hasOverlap) {
                            if (descriptions.size() < MAX_DESCRIPTIONS) {
                                descriptions.add("Label on connection '" + label.connectionId()
                                        + "' has no clear label position — consider repositioning nearby elements");
                            }
                        }
                    }
                    break;
                }
                accumulated += segLen;
            }
        }

        return new LabelOverlapResult(count, descriptions, shortSegmentCount);
    }

    /**
     * Detects connection labels rendered on top of a Note's rectangle — the caption/legend
     * collision the route-vs-visual detectors structurally cannot see (a label is positioned
     * independently of the line, so it can land on a note while the route runs clear). The
     * scoring overlap detector ({@link #countLabelOverlaps}) never sees this: it is fed the
     * scoring node set, which excludes notes. This is the dedicated, informational arm.
     *
     * <p>Geometry reuses the shipped label-overlap primitive verbatim: each connection's
     * render-calibrated {@link LabelBounds} (from {@link #estimateLabelBounds}) is tested for
     * inset overlap ({@link #insetRectOverlap}) against each note's rectangle. A boolean overlap,
     * not a fractional one, so a small label fully inside a large note and a label over a note
     * smaller than itself both flag without dilution — no box-coverage companion is needed.
     * Overlap only (no proximity arm: a label merely beside a large caption note is not a defect).
     * Counted per {@code (label, note)} pair. Notes are never a connection's source/target,
     * ancestor, or descendant (notes are not connectable and cannot be parents), so no exclusion
     * set applies — every note is a candidate for every label.</p>
     *
     * <p>Informational only — the count never reaches the rating or suggestion calls.</p>
     */
    LabelOnNoteResult countLabelOnNote(List<AssessmentConnection> connections,
                                        List<AssessmentNode> noteNodes,
                                        boolean includeViolatorIds) {
        if (noteNodes.isEmpty()) {
            return new LabelOnNoteResult(0, List.of(), Set.of());
        }
        int count = 0;
        List<String> descriptions = new ArrayList<>();
        Set<String> violatorIds = includeViolatorIds ? new LinkedHashSet<>() : null;
        for (AssessmentConnection conn : connections) {
            LabelBounds label = estimateLabelBounds(conn);
            if (label == null) {
                continue;
            }
            for (AssessmentNode note : noteNodes) {
                if (insetRectOverlap(label, note.x(), note.y(), note.width(), note.height())) {
                    count++;
                    if (descriptions.size() < MAX_DESCRIPTIONS) {
                        descriptions.add("Label on connection '" + label.connectionId()
                                + "' overlaps note '" + note.id() + "'");
                    }
                    if (includeViolatorIds) {
                        violatorIds.add(note.id());
                    }
                }
            }
        }
        return new LabelOnNoteResult(count, descriptions,
                violatorIds == null ? Set.of() : violatorIds);
    }

    /**
     * Detects connection labels rendered on a visual Group's TITLE BAND — the title collision the
     * label-vs-element overlap detector ({@link #countLabelOverlaps}) cannot see because it skips
     * every group host wholesale (a group is a transparent container a label may legitimately sit
     * <em>within</em>, so testing the full group rectangle would flood with false positives). The one
     * region where a label IS a defect is the group's top title strip, where the group's own name
     * renders; a label landing there collides with that name and both become unreadable.
     *
     * <p>Geometry reuses the shipped label-overlap primitive verbatim, but against the title band
     * only: for each <em>named</em> group, the band is the top strip
     * {@code (group.x(), group.y(), group.width(), }{@link #estimateLabelBandHeight}{@code )} — the
     * same title-area model {@link #detectParentLabelObscuredByChild} uses for a parent's label row,
     * from the same helper, so the two agree on both the wrap and the clip by construction — and
     * each connection's render-calibrated {@link LabelBounds} (from {@link #estimateLabelBounds}) is
     * tested for inset overlap ({@link #insetRectOverlap}) against it. A label deep in the group BODY
     * (below the band) does NOT flag — this is precisely why the full-group flood does not occur.
     * Overlap only (no proximity arm). Counted per {@code (label, group)} pair. Unnamed groups have no
     * title to collide with and are skipped. No exclusion set: visual Groups are never a connection's
     * source/target (not connectable), and the band-only restriction already leaves ancestor-group
     * body labels alone, so every named group's title band is a candidate for every label. The band
     * height comes from {@link #estimateLabelBandHeight}, so a title that wraps gets the two-line
     * band it renders — and no more of it than the container itself is tall, since that helper clips
     * the band to the figure.</p>
     *
     * <p><b>Why that is not the fixed single line it used to be.</b> The old rationale — that
     * containers carry no measured {@code labelTextWidth}, so the multi-line doubling could never
     * apply — was true only while this detector's guard was {@code isGroup()}. It now admits every
     * container, and an ArchiMate {@code Grouping} IS measured ({@code AssessmentCollector} collects
     * label text for every non-group, non-note object), so the doubling is live and the claim went
     * stale. It also went wrong in the direction that hides defects: an SVG probe of a wrap-titled
     * {@code Grouping} put the second title row's ink 29 px below the box top, outside the 20 px
     * band this tested, so a connection label sitting on that second row went unflagged. A native
     * group still has no measured width and so never reaches the doubling — for that kind the old
     * reasoning holds on the WRAP. It does not hold on the CLIP: a container of any kind shorter
     * than its own band now gets a band cut to its own height, because nothing below the bottom edge
     * of a figure is that figure's title.</p>
     *
     * <p>Informational only — the count never reaches the rating or suggestion calls.</p>
     */
    LabelOnGroupResult countLabelOnGroup(List<AssessmentConnection> connections,
                                          List<AssessmentNode> layoutNodes,
                                          boolean includeViolatorIds) {
        int count = 0;
        List<String> descriptions = new ArrayList<>();
        Set<String> violatorIds = includeViolatorIds ? new LinkedHashSet<>() : null;
        for (AssessmentConnection conn : connections) {
            LabelBounds label = estimateLabelBounds(conn);
            if (label == null) {
                continue;
            }
            for (AssessmentNode node : layoutNodes) {
                // Only containers with a title to collide with — a label inside the container body
                // is normal, so the host rect is the top title strip, NOT the full rectangle.
                //
                // The height test is part of "has a title to collide with", not a separate concern:
                // a box with no positive height draws no figure, so it renders no title strip and
                // nothing can be on it. It is spelled `!(height > 0)` rather than `height <= 0`
                // because NaN fails every comparison — `NaN <= 0` is false and would let it through,
                // `!(NaN > 0)` is true and skips.
                if (!node.isContainer() || node.name() == null || node.name().isEmpty()
                        || !(node.height() > 0)) {
                    continue;
                }
                // The band is clipped to the container by estimateLabelBandHeight, for every
                // consumer. Do not re-add a local Math.min: a second clip would put two models of
                // one band back in this file, which is what this detector's own regression pin
                // exists to catch.
                //
                // But note WHY the guard above has to exist alongside that. The helper deliberately
                // leaves a non-positive or non-finite height UNCLIPPED, because clipping there would
                // rewrite a rating-bearing band on degenerate geometry. A local Math.min used to
                // give this detector that clip unconditionally — driving the band to 0, negative or
                // NaN, each of which makes insetRectOverlap false — so a degenerate container could
                // never flag. Removing it without the guard would have handed this detector a full
                // 20/40 px band on a figure that renders nothing, inventing findings in the
                // false-positive direction. The clip moved; the degenerate case had to move with it.
                double bandHeight = estimateLabelBandHeight(node);
                if (insetRectOverlap(label, node.x(), node.y(), node.width(), bandHeight)) {
                    count++;
                    if (descriptions.size() < MAX_DESCRIPTIONS) {
                        descriptions.add("Label on connection '" + label.connectionId()
                                + "' overlaps the title band of group '" + node.id() + "'");
                    }
                    if (includeViolatorIds) {
                        violatorIds.add(node.id());
                    }
                }
            }
        }
        return new LabelOnGroupResult(count, descriptions,
                violatorIds == null ? Set.of() : violatorIds);
    }

    /**
     * Length (px) of the path segment that hosts the label — the segment containing the label's position
     * point (the {@code textPosition} fraction: 0&rarr;0.15 source, 2&rarr;0.85 target, else 0.50 middle).
     * Used to decide whether a label provably cannot fit its hosting segment and so must drape onto its
     * endpoint box — see {@link #LABEL_OWN_ENDPOINT_SHORT_SEGMENT_OVERLAP_FRACTION}. Mirrors the hosting-
     * segment walk used by the short-segment guidance below. Returns 0 for a degenerate path (&lt;2 points).
     */
    private double hostingSegmentLength(AssessmentConnection conn) {
        List<double[]> path = conn.pathPoints();
        if (path == null || path.size() < 2) {
            return 0.0;
        }
        double fraction;
        switch (conn.textPosition()) {
            case 0:  fraction = 0.15; break;
            case 2:  fraction = 0.85; break;
            default: fraction = 0.50; break;
        }
        double totalLength = 0;
        for (int i = 0; i < path.size() - 1; i++) {
            double dx = path.get(i + 1)[0] - path.get(i)[0];
            double dy = path.get(i + 1)[1] - path.get(i)[1];
            totalLength += Math.sqrt(dx * dx + dy * dy);
        }
        double targetDist = totalLength * fraction;
        double accumulated = 0;
        for (int i = 0; i < path.size() - 1; i++) {
            double dx = path.get(i + 1)[0] - path.get(i)[0];
            double dy = path.get(i + 1)[1] - path.get(i)[1];
            double segLen = Math.sqrt(dx * dx + dy * dy);
            if (accumulated + segLen >= targetDist || i == path.size() - 2) {
                return segLen;
            }
            accumulated += segLen;
        }
        return 0.0; // unreachable: the i == path.size() - 2 guard above always returns within the loop
    }

    /**
     * Returns the label's estimated bounds displaced by an applied "Label Offset" anchor, or the bounds
     * unchanged when the anchor is {@link RelativePositionFeature#CENTER} (un-offset / unsupported platform).
     * The compass anchor is a bitmask (NORTH=1, SOUTH=4, WEST=8, EAST=16; diagonals OR-combine the two
     * cardinals — matching {@code LabelOffsetDirection}); the displacement magnitude is
     * {@link #LABEL_OFFSET_RENDER_ESTIMATE}. Used only by the own-endpoint check so a label already lifted off
     * its box is not re-reported as bleeding.
     */
    private LabelBounds offsetAdjustedBounds(LabelBounds label, int anchor) {
        if (anchor == RelativePositionFeature.CENTER) {
            return label;
        }
        double dx = 0;
        double dy = 0;
        if ((anchor & 1) != 0) dy -= 1;   // NORTH
        if ((anchor & 4) != 0) dy += 1;   // SOUTH
        if ((anchor & 8) != 0) dx -= 1;   // WEST
        if ((anchor & 16) != 0) dx += 1;  // EAST
        if (dx == 0 && dy == 0) {
            return label; // unknown/centre-equivalent anchor — leave bounds unchanged
        }
        return new LabelBounds(
                label.x() + dx * LABEL_OFFSET_RENDER_ESTIMATE,
                label.y() + dy * LABEL_OFFSET_RENDER_ESTIMATE,
                label.width(), label.height(), label.connectionId());
    }

    /**
     * Fraction (0..1) of the label's area that overlaps the given node — the TOLERANT own-endpoint
     * measure. A connection label naturally grazes the box it attaches to, so a small overlap is
     * normal/benign; only a substantial fraction ({@code >= LABEL_OWN_ENDPOINT_OVERLAP_FRACTION})
     * means the label text is rendered on the element body and competes with its fill/name. Groups
     * (transparent containers) and null nodes return 0. The label-to-third-party-element check uses
     * the more sensitive inset-overlap rule, not this one (asymmetric by design).
     */
    private double ownEndpointOverlapFraction(LabelBounds label, AssessmentNode node) {
        if (node == null || node.isContainer()) {
            return 0.0;
        }
        double labelArea = label.width() * label.height();
        if (labelArea <= 0) {
            return 0.0;
        }
        double ox = Math.min(label.x() + label.width(), node.x() + node.width())
                - Math.max(label.x(), node.x());
        double oy = Math.min(label.y() + label.height(), node.y() + node.height())
                - Math.max(label.y(), node.y());
        if (ox <= 0 || oy <= 0) {
            return 0.0;
        }
        return (ox * oy) / labelArea;
    }

    /**
     * Fraction (0..1) of the BOX's area that overlaps the given label — the box-normalised companion to
     * {@link #ownEndpointOverlapFraction}. Where the label-area measure shrinks toward 0 as the endpoint box
     * shrinks (missing a tiny junction fully under the label), this one rises toward 1, so the OR of the two
     * catches both regimes. Groups (transparent containers) and null nodes return 0. See
     * {@link #LABEL_OWN_ENDPOINT_BOX_COVERAGE_FRACTION}.
     */
    private double ownEndpointBoxCoverageFraction(LabelBounds label, AssessmentNode node) {
        if (node == null || node.isContainer()) {
            return 0.0;
        }
        double boxArea = node.width() * node.height();
        if (boxArea <= 0) {
            return 0.0;
        }
        double ox = Math.min(label.x() + label.width(), node.x() + node.width())
                - Math.max(label.x(), node.x());
        double oy = Math.min(label.y() + label.height(), node.y() + node.height())
                - Math.max(label.y(), node.y());
        if (ox <= 0 || oy <= 0) {
            return 0.0;
        }
        return (ox * oy) / boxArea;
    }

    /**
     * Checks if a label's inset bounding box overlaps another rectangle.
     * The label bounds are shrunk by LABEL_OVERLAP_INSET on each side to
     * avoid false positives from estimated bounding boxes barely touching.
     * The inset is capped at 1/3 of each dimension to prevent the bounds
     * from collapsing to zero (label height is typically only 20px).
     */
    private boolean insetRectOverlap(LabelBounds label,
                                      double x2, double y2, double w2, double h2) {
        double xInset = Math.min(LABEL_OVERLAP_INSET, label.width() / 3);
        double yInset = Math.min(LABEL_OVERLAP_INSET, label.height() / 3);
        double lx = label.x() + xInset;
        double ly = label.y() + yInset;
        double lw = label.width() - 2 * xInset;
        double lh = label.height() - 2 * yInset;
        if (lw <= 0 || lh <= 0) return false;
        return lx < x2 + w2 && lx + lw > x2 && ly < y2 + h2 && ly + lh > y2;
    }

    /**
     * Checks if a label's bounding box is within LABEL_PROXIMITY_THRESHOLD of another rectangle
     * without actually overlapping (after inset). This detects "near-miss" situations where
     * labels are too close to elements or other labels for comfortable reading.
     * <p>
     * Expands the target rectangle by the proximity threshold on each side, then checks
     * if the raw (non-inset) label bounds overlap the expanded rectangle.
     */
    private boolean isWithinProximity(LabelBounds label,
                                       double x2, double y2, double w2, double h2) {
        // Expand the target rectangle by the proximity threshold
        double ex = x2 - LABEL_PROXIMITY_THRESHOLD;
        double ey = y2 - LABEL_PROXIMITY_THRESHOLD;
        double ew = w2 + 2 * LABEL_PROXIMITY_THRESHOLD;
        double eh = h2 + 2 * LABEL_PROXIMITY_THRESHOLD;

        // Check if raw label bounds overlap the expanded rectangle
        return label.x() < ex + ew && label.x() + label.width() > ex
                && label.y() < ey + eh && label.y() + label.height() > ey;
    }

    // ---- Note Overlap Detection (informational, not penalizing) ----

    /** Result of note-overlap detection. Informational only — does not affect rating. */
    record NoteOverlapResult(int count, List<String> descriptions) {}

    // ---- Content bounding box ----

    /**
     * Computes the axis-aligned bounding box of all visual content.
     * Includes elements, groups, and notes — everything the user sees on the canvas.
     * Returns {@code null} if there are no nodes.
     */
    private ContentBounds computeContentBounds(List<AssessmentNode> allNodes) {
        if (allNodes.isEmpty()) {
            return null;
        }
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (AssessmentNode node : allNodes) {
            if (node.x() < minX) minX = node.x();
            if (node.y() < minY) minY = node.y();
            double right = node.x() + node.width();
            double bottom = node.y() + node.height();
            if (right > maxX) maxX = right;
            if (bottom > maxY) maxY = bottom;
        }
        return new ContentBounds(minX, minY, maxX - minX, maxY - minY);
    }

    /**
     * Detects overlaps between notes and non-note layout nodes.
     * These are informational only — they do NOT affect the quality rating.
     */
    NoteOverlapResult countNoteOverlaps(List<AssessmentNode> noteNodes,
                                         List<AssessmentNode> layoutNodes) {
        if (noteNodes.isEmpty()) {
            return new NoteOverlapResult(0, List.of());
        }
        int count = 0;
        List<String> descriptions = new ArrayList<>();
        for (AssessmentNode note : noteNodes) {
            for (AssessmentNode element : layoutNodes) {
                // Skip if note is a child of this container (contained notes are expected)
                if (element.isContainer() && note.parentId() != null
                        && note.parentId().equals(element.id())) {
                    continue;
                }
                if (rectanglesOverlap(note, element)) {
                    count++;
                    if (descriptions.size() < MAX_DESCRIPTIONS) {
                        // Reads isGroup where the skip above reads isContainer, deliberately — this
                        // is not a half-finished migration. The skip asks how the object RENDERS
                        // (transparent, so a note inside it is contained rather than colliding);
                        // this asks what to CALL it, and "group" is published vocabulary:
                        // get-view-contents reports a native group under `groups` and an ArchiMate
                        // Grouping among the elements. Calling a Grouping a "group" here would send
                        // a reader to a bucket that structurally cannot hold its id.
                        String targetType = element.isGroup() ? "group" : "element";
                        descriptions.add("Note '" + note.id()
                                + "' overlaps " + targetType + " '" + element.id() + "'");
                    }
                }
            }
        }
        return new NoteOverlapResult(count, descriptions);
    }

    /**
     * Detects notes whose text content is clipped by their box bounds — the box is
     * shorter than the wrapped height the content needs.
     *
     * <p><b>Informational only — does NOT affect the rating.</b> Mirrors the original
     * {@code NoteOverlapResult} contract.
     *
     * <p>The required height is pre-computed in {@link AssessmentCollector} via the same
     * {@code ElementSizer.fitTextBoxHeightToContent} call (and width inset) the note
     * auto-fit path uses, so this fires precisely when an author pinned a {@code height}
     * smaller than what the server's fit would have produced (the dogfood bug). A note created
     * without an explicit height is fitted, and so is one whose text or width is later changed
     * with the height omitted, so in both cases {@code noteRequiredHeight ==} box height and it
     * is not flagged. Only an explicitly pinned height can reach this detector. Real SWT glyph measurement underlies the pre-compute,
     * so — unlike {@code detectLabelTruncation} — no {@code LABEL_RENDER_WIDTH_FACTOR} is
     * applied here. The {@code MAX_NOTE_HEIGHT} clamp case (content genuinely needs >600px)
     * is out of scope; this flags the box-smaller-than-fitted-height case.
     */
    NoteClipResult detectNoteTextClipping(List<AssessmentNode> noteNodes) {
        int count = 0;
        List<String> descriptions = new ArrayList<>();
        for (AssessmentNode note : noteNodes) {
            double required = note.noteRequiredHeight();
            if (required <= 0) {
                continue; // no content / measurement unavailable
            }
            if (required > note.height() + NOTE_CLIP_TOLERANCE) {
                count++;
                if (descriptions.size() < MAX_DESCRIPTIONS) {
                    descriptions.add(String.format(
                            "Note '%s' text clips: content needs ~%.0fpx but box is %.0fpx "
                            + "(%dx%d note at (%.0f,%.0f)). Its height is pinned; re-send the "
                            + "note's text (or width) through update-view-object with the height "
                            + "omitted and the server re-fits it, or raise the height.",
                            note.id(), required, note.height(),
                            (int) note.width(), (int) note.height(),
                            note.x(), note.y()));
                }
            }
        }
        return new NoteClipResult(count, descriptions);
    }

    // ---- Informational Detection (label truncation, parent label obscured, image sibling overlap) ----

    /** Estimated type icon width in pixels (right-aligned in Archi elements). */
    static final double TYPE_ICON_WIDTH = 16.0;
    /**
     * Horizontal inset (px) between an element's edge and its title's glyph run.
     *
     * <p>This is Archi's own {@code getTextControlMarginWidth()}, which the figure hands to the
     * {@code GridLayout} that positions the title inside the figure. It is not a fitted number:
     * an SVG probe measured a LEFT-aligned run starting 4.6-5.1 px inside the box edge, the extra
     * fraction being the glyph's side bearing. Paired with {@link #TYPE_ICON_WIDTH} it also
     * reproduces the RIGHT-aligned case, where Archi narrows the text control by
     * {@code iconOffset - marginWidth} to keep the title clear of the type icon: measured
     * 21.7 px of clearance against the 20 px these two constants predict.</p>
     */
    static final double LABEL_MARGIN_X = 4.0;
    /** Estimated label area height for single-line labels. */
    static final double ESTIMATED_LABEL_HEIGHT = LABEL_CHAR_HEIGHT + LABEL_PADDING_Y; // 20px
    /** Estimated image icon size (width and height) for non-fill positions. */
    static final double IMAGE_ICON_SIZE = 24.0;
    /** Float slack (px) before a note's required height counts as clipped — absorbs off-by-one noise. */
    static final double NOTE_CLIP_TOLERANCE = 1.0;

    record LabelTruncationResult(int count, List<String> descriptions) {}
    /**
     * {@code unmeasuredParentBand} is true when the detector examined at least one parent whose
     * label width was never measured, so the title band it compared against was sized as a single
     * line regardless of how long the title actually is. Both unmeasured modes count — a visual
     * GROUP, which is excluded from measurement outright, and an ordinary element whose text
     * measurement failed — because they reach the detector as the same zero width. The dimension's
     * coverage downgrades to {@code partial} for that run: unlike a detector that abstains on an
     * object it cannot measure, this one still returns a verdict for it, so a zero would otherwise
     * read as certified clean on a parent whose clearance was never really tested.
     */
    record ParentLabelObscuredResult(int count, List<String> descriptions,
                                     boolean unmeasuredParentBand) {}
    record ImageSiblingOverlapResult(int count, List<String> descriptions) {}
    record OverlayIconCollisionResult(int count, List<String> descriptions) {}
    /**
     * {@code unmeasuredTitle} is true when the run held at least one named, icon-bearing object
     * whose title width could not be MEASURED — so there was no title rectangle to test its icon
     * against and the object was never actually examined. The dimension's coverage downgrades to
     * {@code partial} for that run rather than letting a zero read as certified clean.
     *
     * <p>The trigger is the missing measurement, not the object's kind. A native group is never
     * measured at all; a {@code Grouping} or a plain element normally IS measured and arrives here
     * only when {@code ElementSizer.measureText} failed. Anything else that reaches this state with
     * a name and an overlay icon is covered too, deliberately — the flag exists so an unexamined
     * object cannot be certified, and that is true whatever kind it turns out to be.</p>
     */
    record OwnIconOverLabelResult(int count, List<String> descriptions,
                                  boolean unmeasuredTitle) {}
    record NoteClipResult(int count, List<String> descriptions) {}

    /**
     * One measured metric, the published field that carries its count, and the count it reported.
     *
     * <p>{@code metric} is the id the prose prints. It is required to RESOLVE — the caller reading
     * it has to be able to look it up — but not to come from any one register: most of these ids
     * are coverage-dimension ids, a few are violator-id keys, and one is the published field name
     * itself, because that metric has no dimension id of its own. What is forbidden is an id that
     * resolves on no register at all, which is a token the caller can do nothing with.</p>
     *
     * <p>{@code field} is the component of {@link LayoutAssessmentResult} holding this count. It is
     * carried so the registry can be checked in the direction that actually protects it: every
     * count-valued component of the published result must be registered here or declared, in
     * {@link MetricFindings#NOT_MEASURED_AS_A_FINDING}, not to be a finding. Without it the two
     * sides cannot be matched at all — the id and the field name diverge unrecoverably for several
     * metrics ({@code redundantBendpoints} publishes as {@code connectionRedundantBendpointCount},
     * {@code containerFillRecession} as {@code containerFillEqualsChildCount}), so no naming rule
     * could derive one from the other.</p>
     */
    record MetricFinding(String metric, String field, int count) {}

    /**
     * Every count-valued metric this run measured, paired with the count it reported.
     *
     * <p>This exists because {@code suggestions.isEmpty()} is not the same question as "did this run
     * find anything". The suggestion list is built from the subset of metrics that have remedy prose,
     * so a run whose only findings are among the metrics without prose produces an empty list, and a
     * verdict sourced from that emptiness states that nothing was found on dimensions where something
     * was. Sourcing the verdict from the measured counts instead makes it answerable for every metric
     * at once, including metrics added later.</p>
     *
     * <p>Passed as one type-distinct carrier rather than as a further run of bare {@code int}
     * parameters, for the reason the detector-result argument beside it already records: a miswired
     * call site is then a compile error rather than a plausible wrong number.</p>
     *
     * <p><b>Counts only.</b> The six score-valued metrics (alignment, average spacing, hub-port
     * quality, corridor utilisation, hub-neighbour clearance and the parallel-gap 10th percentile)
     * are deliberately absent: zero is not their clean value — for three of them zero is the WORST
     * value — so {@code != 0} would invert their meaning. Each is banded by the rating and, where it
     * indicates a defect, already carries prose; the parallel-gap percentile is a measurement whose
     * companion narrow-gap COUNT is the finding, and that count is included here.</p>
     *
     * <p><b>{@code vAxisParallelGapP10} therefore gets no count-shaped prose either, and that is
     * permanent rather than pending.</b> It is a 10th percentile in pixels: its clean value is a
     * LARGE number and its worst is a small one, so a branch firing on {@code > 0} would emit a
     * remedy on every view that has parallel segments at all and stay silent on the one view where
     * the corridor has collapsed. The finding it would be reporting is already reported by
     * {@code vAxisParallelGapNarrow25Count}, which is carried here and does carry a remedy — the
     * remedy its own served block publishes AGAINST the percentile, since the two share one
     * coverage dimension. Nothing about this is waiting on a decision.</p>
     *
     * <p><b>Containment overlaps are excluded</b> because this file already declares them not to be a
     * defect: the suggestion it emits for them ends "No action needed." Counting them as a finding
     * would make the replacement sentence name something the same method calls expected.</p>
     *
     * <p><b>Orphaned connections are excluded</b> for a different reason again: this class does not
     * measure them. It passes a hard zero into its own result and the accessor merges the real
     * count in afterwards, so the value is not knowable here. It is also not one of the coverage
     * dimensions, so the verdict — which is scoped to the dimensions this run examined — does not
     * speak for it either way, and the response names it in its own step regardless.</p>
     *
     * <p><b>The H-axis parallel-gap narrow count is now carried</b>, and was excluded before only
     * because it had no top-level field in the published result — naming a finding the caller
     * cannot then look up would have repeated, one field along, the defect this prose exists to
     * stop. It was measured all along, on the same pass and through the same aggregation as its V
     * sibling, but lived only inside the detail record, which is null unless the caller asks for
     * violator ids. It is invisible to the reflective parity check for the same reason: that check
     * partitions top-level {@code int} components, and a value nested three levels down inside a
     * non-{@code int} component has no field of that type for it to see. Now that
     * {@code hAxisParallelGapNarrow25Count} exists at the top level, the condition the exclusion
     * named is discharged and the id resolves on a published register.</p>
     */
    record MetricFindings(List<MetricFinding> measured) {

        /**
         * The count-valued components of {@link LayoutAssessmentResult} deliberately NOT registered
         * as findings, each for a reason that would survive being read aloud to a caller.
         *
         * <p>This exists so the registry is answerable in the reverse direction. The measured list
         * plus this set must between them account for EVERY {@code int} component of the published
         * result: a count added to the result and forgotten in the list is then a red test, which
         * is the guarantee the construction site's comment makes. A count that is genuinely not a
         * finding is declared here rather than silently omitted, so the omission is reviewable.</p>
         *
         * <ul>
         *   <li>{@code containmentOverlapCount} — this class already declares containment overlaps
         *       not to be a defect; the suggestion it emits for them ends "No action needed."
         *       Counting them as a finding would name something the same method calls expected.</li>
         *   <li>{@code orphanedConnectionCount} — not measured here. This class passes a hard zero
         *       into its own result and the accessor merges the real count in afterwards, so the
         *       value is not knowable at this point. It is also not one of the coverage dimensions,
         *       so the verdict does not speak for it either way, and the response names it in its
         *       own step regardless.</li>
         *   <li>{@code connectionCount} — a denominator. It describes the view, not a finding on
         *       it, and every value of it is legitimate.</li>
         *   <li>{@code alignmentScore} — a score, not a count. Its clean value is 100 and its worst
         *       is 0, so {@code != 0} would invert its meaning; the rating bands it and the prose
         *       above already explains it below its threshold.</li>
         * </ul>
         */
        static final Set<String> NOT_MEASURED_AS_A_FINDING = Set.of(
                "containmentOverlapCount",
                "orphanedConnectionCount",
                "connectionCount",
                "alignmentScore");

        /** The metrics that reported a nonzero count, in the order they were measured. */
        List<MetricFinding> found() {
            List<MetricFinding> nonZero = new ArrayList<>();
            for (MetricFinding finding : measured) {
                if (finding.count() > 0) {
                    nonZero.add(finding);
                }
            }
            return nonZero;
        }
    }

    /**
     * The thirteen informational metrics that reported a count with no remedy of their own.
     *
     * <p>Every one of them is measured, published as a count, and declared {@code checked} in the
     * coverage map (with one permanent {@code partial}) — and until now none of them put a sentence
     * anywhere. A caller reading the prose as its only ground truth was told a dimension fired, by
     * the terminal disclosure, and given nothing to do about it; on a view that also carried an
     * explained defect it was not told even that.</p>
     *
     * <p>Bundled as one carrier of detector RESULTS rather than as thirteen more positional counts,
     * for the reason the two carriers beside it already record: each is a distinct type, so a
     * miswired call site is a compile error rather than a plausible wrong number. The results are
     * passed rather than the counts because each remedy names the description list that goes with
     * it, and the shortfall clause needs the size of that list as well as the count.</p>
     *
     * <p>The classification these metrics carry is {@code informational}, which governs the RATING
     * and not the prose. Two informational metrics — anchor drift and lateral-jog reversals — have
     * emitted prose all along, and the ruling this file already records for the suppressed-defect
     * case says why: a defect kept out of the rating AND absent from the prose is invisible, and
     * carrying the descriptions is what makes the non-rating honest rather than silencing.</p>
     */
    record InformationalFindings(OverlapResult overlaps,
                                 NoteOverlapResult noteOverlap,
                                 NoteClipResult noteClip,
                                 ImageSiblingOverlapResult imageSiblingOverlap,
                                 OverlayIconCollisionResult overlayIconCollision,
                                 EdgeCoincidenceResult edgeCoincidence,
                                 ParallelConnectionGapResult parallelGap,
                                 ConnectionThroughVisualResult throughVisual,
                                 RedundantBendpointResult redundantBendpoints,
                                 ContainerFillResult containerFill,
                                 LabelOnNoteResult labelOnNote,
                                 LabelOnGroupResult labelOnGroup,
                                 CoincidentFacePortResult coincidentFacePorts) {}

    /**
     * The metrics that move a rating while contributing nothing to the prose that explains it:
     * {@code parentLabelObscured}, {@code labelTruncations}, {@code nonOrthogonalInteriorSegments},
     * {@code offFaceParallelTerminals}, {@code connectionThroughNote}, {@code passThroughs} and
     * {@code hubNeighbourCrowding}.
     *
     * <p>Bundled into one carrier rather than appended as further positional arguments: each is a
     * distinct detector result, so a miswire stays a compile error, and the grouping records WHY
     * they travel together — a caller marked down by any of them was, before this, told nothing
     * about the cause. The members are NAMED rather than counted: a hand-maintained tally beside
     * the list it counts is the one claim a reader cannot check without recounting, and correcting
     * the number is the same defect with a later expiry date.</p>
     *
     * <p><b>{@code passThroughs} and {@code hubNeighbourCrowding} are the two that were still
     * silent after the rest were given prose</b>, and their silences were not the same defect.
     * A view whose ONLY finding was a pass-through published a downgraded rating beside "No defects
     * were found on the dimensions this run examined" — a false all-clear, because
     * {@code passThroughs} was in neither of the two sources that verdict reads. Hub-neighbour
     * crowding, which caps at {@code fair}, could reach the same all-clear alone and could also sit
     * unnamed beside a spacing sentence that truthfully called itself "one of" the limiters while
     * the co-equal limiter went unmentioned.</p>
     */
    record RatingBearingFindings(ParentLabelObscuredResult parentLabelObscured,
                                 LabelTruncationResult labelTruncations,
                                 NonOrthogonalInteriorSegmentResult nonOrthogonalInteriorSegments,
                                 OffFaceParallelTerminalResult offFaceParallelTerminals,
                                 ConnectionThroughVisualResult connectionThroughNote,
                                 PassThroughResult passThroughs,
                                 HubNeighbourCrowdingResult hubNeighbourCrowding) {}

    /**
     * Detects element labels that are truncated after word wrapping.
     * Archi word-wraps labels, so a label wider than the element can still fit
     * if it wraps to multiple lines within the available height.
     * Truncation is detected when the estimated wrapped height exceeds the element height.
     * Skips groups, notes, and elements with null/empty names.
     *
     * <p><b>Affects the rating.</b> Since M6 (2026-04-26) the resulting count is wired as a
     * Tier-2R cap-fair metric: a nonzero count caps {@code routingTier} (and, unless layoutTier is
     * already worse, the overall rating) at "fair" — see {@code computeRoutingTierLevel} and the
     * {@code labelTruncations} breakdown entry. It is NOT informational-only (the previous Javadoc
     * said so — that was true earlier but has been false since M6).
     *
     * <p><b>Calibration note</b> (spike 2026-06-14): the
     * {@code width - TYPE_ICON_WIDTH} horizontal budget and the vertical wrap model were validated
     * against live Archi rendering and found fail-safe and accurate. In the short-box
     * (forced-single-line) regime probed — the View-G shape, a 150x26 box — Archi rendered the label
     * roughly 1.35x wider than the ElementSizer-measured single-line width
     * ({@link AssessmentNode#labelTextWidth()}); a label measured at 151px truncated in boxes up to
     * 200px wide and fit single-line only near 210px. So this predicate is, if anything, mildly
     * under-conservative — not over-conservative — in that regime. Do NOT relax it toward "pass when
     * {@code width >= labelTextWidth}": that would under-flag boxes Archi actually truncates (verified:
     * a 141px label in a 150px box still truncates because the type icon consumes ~16px). The real
     * fix for over-tight boxes is taller/wider geometry (let the label wrap), not a looser predicate.
     */
    LabelTruncationResult detectLabelTruncation(List<AssessmentNode> nodes) {
        int count = 0;
        List<String> descriptions = new ArrayList<>();
        for (AssessmentNode node : nodes) {
            if (node.isGroup() || node.isNote() || node.name() == null || node.name().isEmpty()) {
                continue;
            }
            double availableWidth = node.width() - TYPE_ICON_WIDTH;
            if (availableWidth <= 0) {
                continue;
            }
            double textWidth = node.labelTextWidth();
            if (textWidth <= 0 || textWidth <= availableWidth) {
                continue; // fits on single line
            }
            // Archi word-wraps labels — estimate whether wrapped text overflows vertically
            int estimatedLines = (int) Math.ceil(textWidth / availableWidth);
            double neededHeight = estimatedLines * LABEL_CHAR_HEIGHT + LABEL_PADDING_Y;
            if (neededHeight > node.height()) {
                count++;
                if (descriptions.size() < MAX_DESCRIPTIONS) {
                    descriptions.add(String.format(
                            "Element '%s' label (~%d lines, %.0fpx wide) may be truncated in %dx%d element at (%.0f,%.0f)",
                            node.name(), estimatedLines, textWidth,
                            (int) node.width(), (int) node.height(),
                            node.x(), node.y()));
                }
            }
        }
        return new LabelTruncationResult(count, descriptions);
    }

    /**
     * Detects parents whose label text area is overlapped by their first (topmost) child.
     *
     * <p><b>Affects the rating.</b> Since M6 (2026-04-26) the resulting count is promoted to
     * layout <b>Tier-1L</b> (critical, no cap): {@code computeRatingWithBreakdown} records
     * {@code parentLabelObscured} as {@code "poor"} when the count is nonzero and folds it into the
     * Tier-1L level, so a single hit drives {@code layoutRating} to "poor" and vetoes the overall
     * rating; it is also weighted {@code ×6} in the tier-weighted quality score ({@code tierWeightedScore}
     * in {@code ArchiModelAccessorImpl}). This is harsher than the sibling {@code labelTruncation} metric,
     * which only caps routing at "fair" (Tier-2R). It is NOT informational-only (the previous Javadoc
     * said so — that was true earlier but has been false since M6).
     *
     * <p><b>The question this band answers: does a child start above where the parent's title stops
     * RENDERING?</b> Not "above where its title would need to reach" — {@link #estimateLabelBandHeight}
     * clips the band to the parent's own height, because Archi clips a figure's contents to the
     * figure and nothing below the bottom edge is this parent's title. So on a container shorter
     * than its own wrapped title, a child at relative y=25 in a 30 px box still flags (25 &lt; 30),
     * while a child at relative y=35 does not: that child is not inside the parent at all, and
     * {@link #detectBoundaryViolations} is already reporting it as having escaped. Both metrics are
     * uncapped Tier 1L and are combined by a max, so releasing that parent here cannot lift the
     * layout rating — the count moves, the verdict does not.
     *
     * <p><b>That hand-off holds for a child of any height, and fails for exactly one shape.</b>
     * The boundary check is a strict {@code >}, so a child with ZERO height sitting precisely on the
     * parent's bottom edge satisfies neither detector: the clipped band ends at that same edge and
     * the escape test needs to exceed it. Such a child draws nothing, which is why it is accepted
     * rather than chased — but it means a zero from both counts does not certify that no such object
     * is present, and any prose pairing the two metrics has to say so rather than claim the cover is
     * total.</p>
     *
     * <p>Do not "simplify" this into {@link #ownLabelBounds}, or the reverse. That one tests the
     * GLYPH RUN — the ink — because the defect it hunts is an icon landing on the letters; this one
     * tests the full band's DEPTH, because a child anywhere across the parent's width buries the
     * title. They share a clip, deliberately, and nothing else.</p>
     *
     * <p><b>The band is only as good as the parent's measured label width, and says so.</b> Unlike
     * the truncation detector, which discards a visual group at its entry guard, this one examines
     * every named parent — including one whose label width was never measured, where
     * {@link #estimateLabelBandHeight} cannot take its multi-line branch and returns a single-line
     * band however long the title is. Archi lays a group's title out in a wrapping text flow over
     * the whole group rectangle rather than confining it to the drawn tab, so a long multi-word
     * title really can occupy a second row that this band does not cover, and a child under that row
     * goes unflagged. Widening the band is not available here: the wrapped height is unknowable
     * without the measured width, and the wrap style is a user preference this code cannot read, so
     * a computed band would be a guess wearing a measurement's clothing. Such a run therefore sets
     * {@code unmeasuredParentBand}, which downgrades this dimension's coverage to {@code partial} so
     * a zero cannot be read as a certified all-clear. The count itself is unaffected — every parent
     * still gets the same verdict it always did.</p>
     */
    ParentLabelObscuredResult detectParentLabelObscuredByChild(List<AssessmentNode> nodes) {
        // Build parent→children map from parentId back-references
        Map<String, List<AssessmentNode>> childrenByParent = new LinkedHashMap<>();
        Map<String, AssessmentNode> nodeById = new LinkedHashMap<>();
        for (AssessmentNode node : nodes) {
            nodeById.put(node.id(), node);
            if (node.parentId() != null) {
                childrenByParent.computeIfAbsent(node.parentId(), k -> new ArrayList<>()).add(node);
            }
        }

        int count = 0;
        boolean unmeasuredParentBand = false;
        List<String> descriptions = new ArrayList<>();
        for (Map.Entry<String, List<AssessmentNode>> entry : childrenByParent.entrySet()) {
            AssessmentNode parent = nodeById.get(entry.getKey());
            if (parent == null || parent.name() == null || parent.name().isEmpty()) {
                continue;
            }
            // This parent IS examined below, but its band can only be sized from a measured label
            // width. Without one the band silently stays a single line however long the title is,
            // so the comparison still yields a verdict while never having tested the wrapped rows.
            // Recorded here — inside the examination — so being handed an unmeasurable object is
            // not mistaken for having examined it: a childless container never reaches this loop.
            if (parent.labelTextWidth() <= 0) {
                unmeasuredParentBand = true;
            }
            // Find child with smallest absolute y
            List<AssessmentNode> children = entry.getValue();
            double minChildY = Double.MAX_VALUE;
            for (AssessmentNode child : children) {
                if (child.y() < minChildY) {
                    minChildY = child.y();
                }
            }
            double labelHeight = estimateLabelBandHeight(parent);
            double labelBottom = parent.y() + labelHeight;
            if (minChildY < labelBottom) {
                count++;
                if (descriptions.size() < MAX_DESCRIPTIONS) {
                    double relativeChildY = minChildY - parent.y();
                    descriptions.add(String.format(
                            "Parent '%s' label obscured by child at y=%.0f (label needs %.0fpx, child starts at %.0fpx relative)",
                            parent.name(), minChildY, labelHeight, relativeChildY));
                }
            }
        }
        return new ParentLabelObscuredResult(count, descriptions, unmeasuredParentBand);
    }

    /**
     * Detects elements with images whose image bounding box overlaps a sibling element.
     * Informational only — does NOT affect rating.
     */
    ImageSiblingOverlapResult detectImageSiblingOverlap(List<AssessmentNode> nodes) {
        // Build sibling groups: nodes with same parentId (null = top-level)
        Map<String, List<AssessmentNode>> siblingGroups = new LinkedHashMap<>();
        for (AssessmentNode node : nodes) {
            String key = node.parentId() != null ? node.parentId() : "__top__";
            siblingGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(node);
        }

        int count = 0;
        List<String> descriptions = new ArrayList<>();
        for (List<AssessmentNode> siblings : siblingGroups.values()) {
            for (AssessmentNode node : siblings) {
                if (node.imagePath() == null) continue;
                double[] imgBounds = estimateImageBounds(node);
                if (imgBounds == null) continue;

                for (AssessmentNode sibling : siblings) {
                    if (sibling.id().equals(node.id())) continue;
                    if (rectanglesOverlap(imgBounds[0], imgBounds[1], imgBounds[2], imgBounds[3],
                            sibling.x(), sibling.y(), sibling.width(), sibling.height())) {
                        count++;
                        if (descriptions.size() < MAX_DESCRIPTIONS) {
                            descriptions.add(String.format(
                                    "Element '%s' image (%s) overlapped by sibling '%s' at (%.0f,%.0f)",
                                    node.name() != null ? node.name() : node.id(),
                                    node.imagePosition(), sibling.id(),
                                    sibling.x(), sibling.y()));
                        }
                        break; // one overlap per image element is enough
                    }
                }
            }
        }
        return new ImageSiblingOverlapResult(count, descriptions);
    }

    /**
     * Detects overlay icons that collide across a containment pair — an element's corner/edge
     * icon overlapping the icon of an element that contains it.
     *
     * <p>{@link #detectImageSiblingOverlap} buckets nodes by parent and compares only within a
     * bucket, so an ancestor and its descendant are never compared; this covers that axis. The
     * test is icon rect vs icon rect: ordinary nesting inside an iconed container is normal
     * layout and is never flagged. {@code fill} images are skipped — they are backgrounds whose
     * estimated rect is the whole element box, which would otherwise flag every descendant. Each
     * colliding pair is counted once (the ancestor chain is walked upward only).</p>
     *
     * <p>Informational only — does NOT affect rating. Note that icon visibility is not carried on
     * the assessment node, so an element whose image is set but suppressed is still examined —
     * the same approximation {@link #detectImageSiblingOverlap} makes.</p>
     */
    OverlayIconCollisionResult detectOverlayIconCollision(List<AssessmentNode> nodes) {
        // Null ids are skipped deliberately: a null key would be resolved as the "parent" of
        // every top-level node (whose parentId is also null), fabricating pairs between
        // unrelated elements. Ids are otherwise assumed unique (they are model object ids); a
        // duplicate would shadow the earlier node and could resolve an ancestor lookup into the
        // wrong subtree, which is accepted rather than defended against.
        Map<String, AssessmentNode> byId = new LinkedHashMap<>();
        for (AssessmentNode node : nodes) {
            if (node.id() != null) {
                byId.put(node.id(), node);
            }
        }

        int count = 0;
        List<String> descriptions = new ArrayList<>();
        for (AssessmentNode node : nodes) {
            double[] iconBounds = overlayIconBounds(node);
            if (iconBounds == null) continue;

            // Walk upward only, so each pair is counted once. The visited set bounds the walk
            // against a malformed model whose parent links form a cycle.
            Set<String> visited = new HashSet<>();
            visited.add(node.id());
            AssessmentNode ancestor = byId.get(node.parentId());
            while (ancestor != null && visited.add(ancestor.id())) {
                double[] ancestorBounds = overlayIconBounds(ancestor);
                if (ancestorBounds != null && rectanglesOverlap(
                        iconBounds[0], iconBounds[1], iconBounds[2], iconBounds[3],
                        ancestorBounds[0], ancestorBounds[1], ancestorBounds[2], ancestorBounds[3])) {
                    count++;
                    if (descriptions.size() < MAX_DESCRIPTIONS) {
                        descriptions.add(String.format(
                                "Element '%s' icon (%s) collides with the icon (%s) of containing element '%s'",
                                node.name() != null ? node.name() : node.id(), node.imagePosition(),
                                ancestor.imagePosition(),
                                ancestor.name() != null ? ancestor.name() : ancestor.id()));
                    }
                }
                ancestor = byId.get(ancestor.parentId());
            }
        }
        return new OverlayIconCollisionResult(count, descriptions);
    }

    /**
     * Detects elements whose own overlay icon is drawn on top of their own title label — the
     * specialization/profile glyph that buries the element name on a narrow box.
     *
     * <p>The two sibling image detectors cannot reach this pair. {@link #detectImageSiblingOverlap}
     * compares an icon against other BOXES in the same parent bucket, {@link #detectOverlayIconCollision}
     * against an ANCESTOR's icon; and because {@link #estimateImageBounds} clamps the icon rectangle
     * to its own element box, the icon and the title it covers are both inside that box and are
     * never compared. Archi draws the title in a band placed from the object's own features on
     * BOTH axes — horizontally by its {@code textAlignment}, vertically by its
     * {@code textPosition} ({@link #ownLabelBounds} has the geometry) — so a glyph anchored at the
     * corner the title happens to occupy lands on the name while both existing counts read
     * zero.</p>
     *
     * <p><b>The title's position is a per-object feature on BOTH axes, so this detector reads it
     * rather than assuming one.</b> Archi's figure hands the title control a single
     * {@code GridData} whose horizontal alignment comes from {@code getTextAlignment()} and whose
     * vertical comes from {@code getTextPosition()}, so the two axes are one mechanism and an
     * assumption on either invents collisions that do not render and misses ones that do. The
     * vertical default (TOP) is common enough that modelling it as a constant survived a long
     * time, but this server publishes a {@code verticalTextAlignment} parameter that sets it on any
     * object, so a centred or footed title is reachable through this server exactly as a
     * non-centred alignment is. Horizontally: a {@code Grouping}, group or note is stamped LEFT at creation
     * by Archi's palette and by this server alike; a plain element is left at CENTRE here while
     * Archi derives its default from a user preference; one this server created before it stamped
     * anything keeps CENTRE regardless; and this server's published {@code textAlignment}
     * parameter can set any value on any object — so the same element type reaches here with the
     * title in different places depending on who authored it and when. Assuming a fixed centring
     * both invents collisions that do not render and misses ones that do.</p>
     *
     * <p>{@code fill} images are skipped — a background is not an overlay glyph (the same guard
     * {@link #overlayIconBounds} applies). Counted once per element.</p>
     *
     * <p><b>A title this detector could not MEASURE is declared, not certified.</b> An element that
     * carries an overlay icon and a name but arrives with no measurable label width has no title
     * rectangle to test that icon against, so it was handed to this detector and never actually
     * examined. Such a run sets {@code unmeasuredTitle}, which downgrades this dimension's coverage
     * to {@code partial} so a zero cannot be read as a certified all-clear. The condition is about
     * MEASUREMENT and not about the object's KIND: a native group is never measured at all (label
     * text is collected only for non-group, non-note objects), while a {@code Grouping} or a plain
     * element normally IS measured and reaches this state only when {@code ElementSizer.measureText}
     * failed — the collector catches and logs that. Those routes leave the same unexamined icon, so
     * they declare it alike. The list is illustrative rather than exhaustive <b>on purpose</b>: the gate
     * asks only whether a named object with an icon lacked a measurable title, so any future kind
     * that reaches that state is covered without another edit here. (A Note is the one kind that can
     * reach it today without having a "title" concept at all, but it needs both a name — which this
     * server never sets on a note — and an overlay image, so it is not reachable in practice.)
     * Abstaining rather than guessing is also why no rectangle is fabricated from a missing width:
     * a made-up width would manufacture findings.</p>
     *
     * <p>Icon visibility is not carried on the assessment node, so an element whose image is set but
     * suppressed is still examined — the same approximation the two sibling icon detectors make.</p>
     *
     * <p>Informational only — does NOT affect rating.</p>
     */
    OwnIconOverLabelResult detectOwnIconOverLabel(List<AssessmentNode> nodes) {
        int count = 0;
        boolean unmeasuredTitle = false;
        List<String> descriptions = new ArrayList<>();
        for (AssessmentNode node : nodes) {
            double[] iconBounds = overlayIconBounds(node);
            if (iconBounds == null) continue;
            double[] labelBounds = ownLabelBounds(node);
            if (labelBounds == null) {
                // This node HAS an overlay icon and HAS a title, but no measurable title rectangle
                // to test that icon against — so it was handed to this detector and never actually
                // examined. Record the gap so coverage can declare it, rather than returning a
                // clean zero for a comparison that never happened.
                //
                // The predicate is about MEASUREMENT, not about KIND. Asking "is this a native
                // group?" answered only the case the detector was first written against and left
                // every other route to an unmeasured title certifying itself: a plain element whose
                // measureText threw (AssessmentCollector catches and logs it) reaches here with the
                // same zero width and no group flag. The sibling downgrade in
                // detectParentLabelObscuredByChild has the right shape and this now matches it.
                if (node.name() != null && !node.name().isEmpty()) {
                    unmeasuredTitle = true;
                }
                continue;
            }
            if (rectanglesOverlap(iconBounds[0], iconBounds[1], iconBounds[2], iconBounds[3],
                    labelBounds[0], labelBounds[1], labelBounds[2], labelBounds[3])) {
                count++;
                if (descriptions.size() < MAX_DESCRIPTIONS) {
                    descriptions.add(String.format(
                            "Element '%s' icon (%s) overlaps its own title label — the glyph is "
                            + "drawn over the element name in the %dx%d element at (%.0f,%.0f). "
                            + "The title is %s-aligned and sits at the %s of the box, so widen the "
                            + "element, move the icon clear of that area, or change THIS VIEW "
                            + "OBJECT's text alignment or vertical text alignment. Alignment is stored "
                            + "per view object, so correcting it here does not travel to other views "
                            + "showing the same element.",
                            node.name() != null ? node.name() : node.id(), node.imagePosition(),
                            (int) node.width(), (int) node.height(), node.x(), node.y(),
                            alignmentName(node.textAlignment()),
                            positionName(node.textPosition())));
                }
            }
        }
        return new OwnIconOverLabelResult(count, descriptions, unmeasuredTitle);
    }

    /**
     * Returns the height of the title band an object actually RENDERS: one label line, doubled when
     * the name is too wide for the available width and therefore wraps, then clipped to the object's
     * own height. Shared by {@link #countLabelOnGroup} and {@link #detectParentLabelObscuredByChild}
     * (which need only the band's depth) and {@link #ownLabelBounds} (which needs the full
     * rectangle), so the three cannot drift apart.
     *
     * <p><b>The clip belongs here and not at a call site.</b> Archi clips a figure's contents to the
     * figure, so a two-line band computed for a container shorter than two lines describes a region
     * below the bottom edge that never renders as this object's title — down there it is whatever
     * sits underneath. Measured at the render: a 250x30 {@code Grouping} swim-lane with a long title
     * produced a 40 px band reaching 10 px past its own bottom edge, and a foreign connection label
     * standing in that strip was blamed on this container's title. An earlier fix clamped only the
     * label-on-group call site, which left two models of one band in one file; this method is now
     * the one model, which is what the paragraph above has always promised.</p>
     *
     * <p><b>Why clipping cannot move the composite rating.</b> The clip narrows
     * {@link #detectParentLabelObscuredByChild}'s verdict only for a topmost child at or below its
     * parent's own bottom edge — and being the topmost, that means EVERY child of that parent is.
     * {@link #detectBoundaryViolations} flags exactly that, over the same node list, and both
     * metrics sit in the same uncapped critical layout tier, combined by a max. So a parent this
     * clip releases is still reported, by the metric that describes it correctly. What does move is
     * the reported count, its descriptions, and the weighted score and retry comparator that read
     * the count.</p>
     *
     * <p>Two guards, both deliberately narrow:</p>
     * <ul>
     *   <li>The wrap doubling is guarded on {@code availableWidth > 0}, so an element no wider than
     *       {@link #TYPE_ICON_WIDTH} always reports the single-line height however long its name is.
     *       That threshold is inherited verbatim from the rating-bearing parent-label detector and
     *       is left exactly as it was: at 16 px or less the box is icon-sized and carries no
     *       readable title, so the residual under-estimate is not worth perturbing a Tier 1L
     *       predicate to chase.</li>
     *   <li>The clip applies only to a usable height. A box with zero, negative or non-finite height
     *       renders no figure and therefore no title, and {@code Math.min} against such a value
     *       would rewrite the band on degenerate geometry this clip is not about — zero collapses it
     *       and {@code NaN} propagates, silently defeating every later comparison. {@code NaN > 0}
     *       is false, so those inputs keep the unclipped estimate they have always had.</li>
     * </ul>
     *
     * <p><b>That last guard is deliberately at odds with the physical model above, and the tension
     * is real.</b> If nothing below a figure's bottom edge is its title, then a figure of zero height
     * has no title at all and the honest band would be zero — not the largest band this method can
     * return. What is being protected is not the physics but the blast radius: a rating-bearing
     * predicate reads this value, and rewriting it for degenerate inputs would silently widen a
     * separately-deferred {@code NaN} defect into a new place. The consequence is that a consumer
     * which cannot tolerate a full band on a figure that renders nothing must say so at its OWN
     * seam — {@link #countLabelOnGroup} does exactly that, and its comment explains why. Handle the
     * degenerate case where its meaning is local; do not push it in here.</p>
     */
    private double estimateLabelBandHeight(AssessmentNode node) {
        double availableWidth = node.width() - TYPE_ICON_WIDTH;
        double band = (availableWidth > 0 && node.labelTextWidth() > availableWidth)
                ? ESTIMATED_LABEL_HEIGHT * 2  // multi-line wrap
                : ESTIMATED_LABEL_HEIGHT;
        return node.height() > 0 ? Math.min(band, node.height()) : band;
    }

    /**
     * The object's horizontal title alignment as the word an agent can act on. Uses the same
     * vocabulary this server's published {@code textAlignment} parameter accepts, so a caller told
     * the title is "left"-aligned can pass that word straight back to change it. An unrecognised
     * value reports "centre", matching how {@link #ownLabelBounds} treats it — the prose must never
     * describe a geometry the rectangle did not use.
     */
    private static String alignmentName(int textAlignment) {
        return switch (textAlignment) {
            case AssessmentNode.TEXT_ALIGNMENT_LEFT -> "left";
            case AssessmentNode.TEXT_ALIGNMENT_RIGHT -> "right";
            default -> "centre";
        };
    }

    /**
     * The object's vertical title position as the word an agent can act on — the counterpart of
     * {@link #alignmentName}, and for the same reason. Uses the vocabulary this server's published
     * {@code verticalTextAlignment} parameter accepts, so a caller told the title sits at the
     * "bottom" can pass that word straight back to change it. An unrecognised value reports "top",
     * matching how {@link #ownLabelBounds} treats it.
     *
     * <p>Naming it is not decoration. On a vertically-caused overlap the horizontal alignment plays
     * no part in the collision, so a description that named only the alignment would point the
     * caller at the wrong remedy — and the shipped tool description promises the description names
     * what it found.</p>
     */
    private static String positionName(int textPosition) {
        return switch (textPosition) {
            case AssessmentNode.TEXT_POSITION_CENTRE -> "centre";
            case AssessmentNode.TEXT_POSITION_BOTTOM -> "bottom";
            default -> "top";
        };
    }

    /**
     * Returns the absolute rectangle of the GLYPH RUN of an element's own title — the ink, not the
     * text control that holds it — or null when no rectangle can be claimed: the label width was
     * never measured, or the geometry it was handed is not a finite number. Both are the same
     * answer for the same reason — nothing was measured, so nothing is asserted — and the caller
     * reports them alike, as an unexamined title rather than a clean one.
     *
     * <p><b>Horizontal placement follows the object's own {@code textAlignment}.</b> Archi does not
     * centre every title: the figure hands its text control a {@code GridData} whose horizontal
     * alignment is read from {@code ITextAlignment.getTextAlignment()} per object, so the run sits
     * against the left inner edge, centred, or against the right inner edge accordingly. Two things
     * make the non-centred cases ordinary rather than exotic — a {@code Grouping}, group or note is
     * stamped LEFT at creation, by Archi's palette and by this server alike (a plain element is
     * not: this server leaves it CENTRE, while Archi reads a user preference for it), and this
     * server publishes a {@code textAlignment} parameter that sets any value on any object. An unconditional centring is therefore wrong for
     * the common case, not the rare one: measured on a 400 px box, a LEFT-aligned title's ink began
     * 4.6 px from the left edge while a centred model would have placed it 169 px away.</p>
     *
     * <p>Geometry, with {@link #LABEL_MARGIN_X} and {@link #TYPE_ICON_WIDTH} both taken from Archi's
     * own figure code rather than fitted (see those constants):</p>
     * <ul>
     *   <li>LEFT — run starts at {@code x + LABEL_MARGIN_X}.</li>
     *   <li>CENTRE — run centred on the element's mid-x (the historical model, unchanged).</li>
     *   <li>RIGHT — run ENDS at {@code x + width - LABEL_MARGIN_X - TYPE_ICON_WIDTH}: Archi narrows
     *       the control on this alignment only, to keep the title clear of the type icon.</li>
     * </ul>
     * An unrecognised alignment degrades to CENTRE rather than throwing — an informational
     * rectangle is not worth failing an assessment over.
     *
     * <p><b>Vertical placement follows the object's own {@code textPosition}, and it is the SAME
     * per-object mechanism.</b> Archi's figure hands the title control ONE
     * {@code GridData(horizontalAlignment, verticalAlignment, true, true)} and reads its two
     * arguments from {@code ITextAlignment.getTextAlignment()} and
     * {@code ITextPosition.getTextPosition()} respectively — so a title centred or footed in its
     * box is exactly as ordinary as a left-aligned one, and this server's published
     * {@code verticalTextAlignment} parameter sets it on any object. Measured at the render (Archi
     * 5.10, glyph ink read from the exported path outlines) the band sits in a cell inset by
     * {@code getTextControlMarginHeight()} = 4 px, anchored per position:</p>
     * <ul>
     *   <li>TOP — band starts at the object's own {@code y} (unchanged, and Archi's EMF default).</li>
     *   <li>CENTRE — band centred on the object's mid-y.</li>
     *   <li>BOTTOM — band ENDS at the object's own bottom edge.</li>
     * </ul>
     * An unrecognised position degrades to TOP, matching the EMF default.
     *
     * <p>The anchor is taken from the BOX edge rather than from Archi's 4 px inset cell. That is
     * the approximation the top-anchored model always made — a band at {@code y} rather than
     * {@code y + 4} — and it is kept, deliberately, so the change is the ANCHOR alone: the same
     * ~4 px slack now applies at whichever edge the title renders against instead of at the top
     * only. The band's height is chosen independently of its anchor, so a wrapped title grows from
     * its own edge rather than always downwards.</p>
     *
     * <p>The run is never wider than the element, and is clamped into the element box so a title
     * measured wider than its own box cannot produce a rectangle hanging outside it. The same holds
     * on the vertical axis, and it is inherited rather than written here:
     * {@link #estimateLabelBandHeight} clips the band it returns to the node's own height, so a band
     * anchored at ANY of the three positions stays inside the element — at the top it cannot reach
     * past the bottom edge, at the bottom it cannot reach back above the top one, and a centred
     * band is inside whenever it fits. This rectangle is therefore inside the element on BOTH axes.
     * A local {@code Math.min} on the height would be dead arithmetic and would put a second model
     * of the band in a file that deliberately holds one — but the coupling runs the other way too,
     * so anyone reverting the helper's clip takes this method's vertical contract with it.</p>
     *
     * <p>DEFENSIVE on that axis, exactly as on the horizontal one, and for the same reason: no count
     * can distinguish a clipped band from an unclipped one here. The only consumer,
     * {@link #detectOwnIconOverLabel}, tests this rectangle against {@link #estimateImageBounds},
     * which already clips the icon rect to the same element box — so whatever slice of band a
     * missing clip would put outside the figure can never intersect anything it is compared with,
     * and the vertical overlap test gives the same answer at either band height. The clip is here so
     * the contract stated above holds for a future consumer that REPORTS the rectangle rather than
     * testing it. A test asserting this changed a count would be green against a clip that never
     * fired.</p>
     *
     * <p><b>This answers a different question from {@link #countLabelOnGroup}, deliberately.</b>
     * That detector tests the full-width title BAND (the control rectangle) because a foreign
     * connection label may not sit anywhere in the strip where the container's name renders. This
     * one tests the GLYPH RUN, because the defect it looks for is an icon landing on the letters
     * themselves. Both models are correct for their own question and they are not interchangeable:
     * widening this one to the full band would flag nearly every top-anchored icon on every
     * container, and narrowing that one to the run would miss a label sitting in the empty half of
     * the strip. Do not "fix" either into the other.</p>
     */
    private double[] ownLabelBounds(AssessmentNode node) {
        double labelWidth = Math.min(node.labelTextWidth(), node.width());
        // Geometry that is not a number was not measured, so there is no rectangle to claim.
        //
        // The check is EXPLICIT rather than a positivity test, because a positivity test cannot
        // express it: NaN <= 0 is false, so a NaN width walked past the guard below and this method
        // returned a rectangle whose own coordinates were NaN. Nothing downstream noticed — every
        // comparison in rectanglesOverlap is also false against NaN — so the run reported zero
        // findings AND coverage "checked", certifying an icon that was never compared with
        // anything. The count was never the symptom; the false all-clear was. An infinity fails the
        // opposite way: its comparisons DO hold, so it would fabricate an overlap rather than hide
        // one. isFinite covers both.
        //
        // Three of the four numbers need checking, and labelWidth vouches for none of them:
        //   - x and y never reach the width guard at all. A finite width with a non-finite origin
        //     clears it outright, and the coordinate then poisons runX, clampedX and the rectangle.
        //   - width is MASKED by the Math.min above, in one direction only. min(100, NaN) is NaN
        //     and is caught, but min(100, Infinity) is 100 — finite, positive, and past the guard,
        //     after which the centring arithmetic sends clampedX to infinity. A guard on labelWidth
        //     alone therefore covers the NaN box width and misses the infinite one.
        // The height is NOT checked here, because whether it is needed at all depends on where the
        // title is anchored — see the second guard, below the alignment switch. It is not needed
        // for a top-anchored band: estimateLabelBandHeight derives the band from constants and its
        // comparisons already fall to the finite branch on a degenerate height, so it returns a
        // finite band whatever it is handed, and a top-anchored rectangle built on a degenerate
        // height is still entirely finite. Checking it unconditionally here would reject that case
        // too, changing a pre-existing behaviour this method does not own.
        //
        // DEFENSIVE: no production path can deliver a non-finite value here. All four construction
        // sites are integral at the source — Archi's IBounds is int-valued and degenerate boxes are
        // dropped before a node is built, ElementSizer's measured text width is an int, and the
        // nudge-reconstruction pair adds int deltas. The guard is worth having anyway because the
        // cost of being wrong is silent and unattributable: this method's only consumer reads a
        // null as "not examined" and reports it, whereas a NaN rectangle is indistinguishable from
        // a clean one in the response an agent acts on. It is also the shape a future caller is
        // most likely to break, since nothing in the signature says the doubles must be finite.
        if (!Double.isFinite(labelWidth) || labelWidth <= 0) return null;
        if (!Double.isFinite(node.x()) || !Double.isFinite(node.y())
                || !Double.isFinite(node.width())) {
            return null;
        }
        double runX = switch (node.textAlignment()) {
            case AssessmentNode.TEXT_ALIGNMENT_LEFT -> node.x() + LABEL_MARGIN_X;
            case AssessmentNode.TEXT_ALIGNMENT_RIGHT ->
                    node.x() + node.width() - LABEL_MARGIN_X - TYPE_ICON_WIDTH - labelWidth;
            default -> node.x() + (node.width() - labelWidth) / 2;
        };
        // Clamp into the element box: a run inset by the margins can be pushed outside it when the
        // measured title is nearly as wide as the box, and a rectangle outside the element is
        // geometry that never renders (Archi clips the figure's contents to the figure).
        // DEFENSIVE: no count distinguishes a clamped run from an unclamped one, because a title
        // that nearly fills its box overlaps a corner glyph either way. It is here so this method's
        // contract — "a rectangle inside the element" — holds for any future consumer that reports
        // the rectangle rather than just testing it. The tests say the same, rather than implying
        // an outcome they cannot observe.
        double clampedX = Math.max(node.x(), Math.min(runX, node.x() + node.width() - labelWidth));
        double bandHeight = estimateLabelBandHeight(node);
        // Vertical placement follows the object's own textPosition, for the same reason and through
        // the same mechanism as the horizontal one above — Archi builds ONE GridData for the title
        // control and reads its two alignments from getTextAlignment() and getTextPosition(). The
        // anchor is the edge the band is measured from; its height is chosen independently, so a
        // wrapped title grows from whichever edge it is anchored to.
        //
        // The two non-TOP anchors measure from the box's FAR edge, so they are the only arithmetic
        // in this method that reads the height DIRECTLY rather than through
        // estimateLabelBandHeight — and that makes them the one place the guards above do not
        // already cover. The height must therefore be usable before either can be computed:
        //   - non-finite: the band's own origin would go non-finite, handing back a rectangle that
        //     defeats every later comparison. That is precisely the false all-clear the first guard
        //     exists to prevent, arriving by a second route.
        //   - zero or negative: the band would be placed OUTSIDE the box, above its top edge — a
        //     direction a top-anchored band could never escape in. estimateLabelBandHeight
        //     deliberately leaves the band unclipped on such a height and says the degenerate case
        //     belongs at whichever seam its meaning is local to. This is that seam. Measured, this
        //     arm is defensive depth rather than an observable fix: the only consumer skips such a
        //     node earlier, because estimateImageBounds collapses the icon rectangle against a box
        //     with no height and returns no icon at all. It is guarded here so the contract holds
        //     for a future consumer, and so the two degenerate families are answered in one place.
        // A box with no usable height renders no figure and therefore no title to locate, so the
        // honest answer is the same one the guards above give: no rectangle is claimed. The TOP
        // anchor is untouched by this — it never reads the height, so it keeps the behaviour it has
        // always had on a degenerate box.
        int position = node.textPosition();
        boolean anchoredFromFarEdge = position == AssessmentNode.TEXT_POSITION_CENTRE
                || position == AssessmentNode.TEXT_POSITION_BOTTOM;
        if (anchoredFromFarEdge && (!Double.isFinite(node.height()) || node.height() <= 0)) {
            return null;
        }
        double bandY = switch (position) {
            case AssessmentNode.TEXT_POSITION_CENTRE ->
                    node.y() + (node.height() - bandHeight) / 2;
            case AssessmentNode.TEXT_POSITION_BOTTOM ->
                    node.y() + node.height() - bandHeight;
            default -> node.y();
        };
        return new double[]{clampedX, bandY, labelWidth, bandHeight};
    }

    /**
     * Returns the absolute icon rectangle for a node carrying a positioned overlay icon, or null
     * when the node has no image or the image is a {@code fill} background rather than an icon.
     */
    private double[] overlayIconBounds(AssessmentNode node) {
        if (node.imagePath() == null || "fill".equals(node.imagePosition())) return null;
        return estimateImageBounds(node);
    }

    /**
     * Estimates the absolute image bounding box for an element based on imagePosition.
     * Returns {x, y, width, height} in absolute coordinates, or null if position unknown.
     *
     * <p>The rectangle is CLAMPED to the element box. Archi clips an element's image to the
     * element's bounds, so an image whose natural size exceeds its element is cut off at the
     * box edge rather than drawn outside it — verified by rendering one image at a fixed
     * anchor against decreasing element heights, where the glyph is progressively cut while
     * its tile width stays constant (clipping, not scale-to-fit), on both the custom-image
     * and the specialization/profile icon paths. The rectangle that actually renders is
     * therefore the intersection of the anchored natural-size rectangle with the element box,
     * and any claim that an icon reaches outside its element is unreachable geometry.</p>
     */
    private double[] estimateImageBounds(AssessmentNode node) {
        String pos = node.imagePosition();
        if (pos == null) return null;
        double ex = node.x(), ey = node.y(), ew = node.width(), eh = node.height();
        // Use the image's true rendered (archive) size when known; fall back to the
        // fixed icon size when natural dimensions are unavailable (headless / no
        // archive). 'fill' ignores these and uses the element bounds below.
        double iw = node.imageNaturalWidth() > 0 ? node.imageNaturalWidth() : IMAGE_ICON_SIZE;
        double ih = node.imageNaturalHeight() > 0 ? node.imageNaturalHeight() : IMAGE_ICON_SIZE;

        double[] anchored = switch (pos) {
            case "fill" -> new double[]{ex, ey, ew, eh};
            case "top-left" -> new double[]{ex, ey, iw, ih};
            case "top-centre" -> new double[]{ex + ew / 2 - iw / 2, ey, iw, ih};
            case "top-right" -> new double[]{ex + ew - iw, ey, iw, ih};
            case "middle-left" -> new double[]{ex, ey + eh / 2 - ih / 2, iw, ih};
            case "middle-centre" -> new double[]{ex + ew / 2 - iw / 2, ey + eh / 2 - ih / 2, iw, ih};
            case "middle-right" -> new double[]{ex + ew - iw, ey + eh / 2 - ih / 2, iw, ih};
            case "bottom-left" -> new double[]{ex, ey + eh - ih, iw, ih};
            case "bottom-centre" -> new double[]{ex + ew / 2 - iw / 2, ey + eh - ih, iw, ih};
            case "bottom-right" -> new double[]{ex + ew - iw, ey + eh - ih, iw, ih};
            default -> null;
        };
        if (anchored == null) return null;

        // Clamp to the element box. Both the ORIGIN and the extent are clamped: a right- or
        // centre-anchored oversized image places its origin left of / above the box, so
        // bounding the extent alone would leave the rectangle outside the element. 'fill' is
        // unaffected — its rectangle already IS the element box, so this is a no-op for it.
        double x1 = Math.max(anchored[0], ex);
        double y1 = Math.max(anchored[1], ey);
        double x2 = Math.min(anchored[0] + anchored[2], ex + ew);
        double y2 = Math.min(anchored[1] + anchored[3], ey + eh);
        // A clamped rectangle with no extent on either axis is not a rectangle — nothing renders,
        // so report no image rather than a zero-area rectangle collapsed onto a line or a point.
        // That distinction matters: rectanglesOverlap uses strict inequalities, so a zero-width
        // rectangle whose pinned coordinate falls inside a sibling's span would otherwise register
        // as an overlap. This is reachable only for a degenerate element box (zero or negative
        // width/height) — for any element with a positive box the intersection with a positive-size
        // image always has positive extent, because the two always share the anchored corner.
        if (x2 - x1 <= 0 || y2 - y1 <= 0) return null;
        return new double[]{x1, y1, x2 - x1, y2 - y1};
    }

    /**
     * Checks if two rectangles overlap (axis-aligned, specified by x, y, width, height).
     */
    private boolean rectanglesOverlap(double x1, double y1, double w1, double h1,
                                      double x2, double y2, double w2, double h2) {
        return x1 < x2 + w2 && x1 + w1 > x2 && y1 < y2 + h2 && y1 + h1 > y2;
    }

    // ---- Suggestion Generation (Finding #7: performance warning, #11: named constants) ----

    /**
     * Names where the affected objects can be read, without over-claiming. The description list is
     * capped at {@link #MAX_DESCRIPTIONS} and this dimension has no violator-id key, so on a view
     * carrying more findings than the cap the remainder cannot be recovered from the response at
     * all — which the caller has to be told, rather than being pointed at a list that silently
     * stops short.
     */
    private static String ownIconDescriptionClause(int named, int total) {
        return descriptionClause("ownIconOverLabelDescriptions", false, named, total);
    }

    /**
     * The same clause for any dimension: which list names the objects, and — when the count exceeds
     * {@link #MAX_DESCRIPTIONS} — how many are left over and whether anything else can recover them.
     *
     * <p>{@code hasViolatorIdKey} decides the shortfall wording, and it is the caller's job to pass
     * what the violator-id registry actually holds for this dimension. A dimension WITH a key can
     * point the remainder at it (the key is populated from the detector's own id set, which the
     * description cap does not bound); a dimension WITHOUT one has to say the remainder is
     * recoverable from nothing, because it is not.</p>
     *
     * <p><b>The field is named as assess-layout's, not as a bare field name, and that is
     * load-bearing rather than decorative.</b> This suggestion list is republished verbatim by
     * {@code auto-layout-and-route} and {@code adjust-view-spacing}, whose result types carry no
     * description lists, no violator-id map and no coverage map at all. A bare "see
     * noteOverlapDescriptions" therefore sends a caller of those two tools to a field that is not
     * on the response in front of them, and {@code includeViolatorIds} is a parameter only
     * assess-layout takes. Naming the tool that publishes the field costs two words and makes the
     * pointer resolvable from every surface this sentence reaches — the same reason the terminal
     * disclosure and the coverage verdict below already say "assess-layout's own response" and
     * "assess-layout's coverage map" rather than naming a bare field.</p>
     */
    private static String descriptionClause(String descriptionField, boolean hasViolatorIdKey,
                                            int named, int total) {
        if (named == 0) {
            return "";
        }
        if (named < total) {
            return "; assess-layout's " + descriptionField + " names the first " + named
                    + " of them, and this"
                    + (hasViolatorIdKey
                            ? " dimension's violator-id list holds the rest — call assess-layout"
                                    + " with includeViolatorIds to recover the remaining "
                            : " dimension publishes no violator-id list, so the remaining ")
                    + (total - named)
                    + (hasViolatorIdKey ? ""
                            : (total - named == 1 ? " has" : " have")
                                    + " to be found in the render");
        }
        return "; see assess-layout's " + descriptionField + " for the objects affected";
    }

    /**
     * The form {@link #detectPassThroughs} writes for a CROSS-element crossing. Matched rather than
     * re-derived because the description list is the only place the two kinds of pass-through are
     * distinguishable after the fact, and the count the rating charges is one of them.
     */
    private static final String CROSS_ELEMENT_PASS_THROUGH_FORM = "' passes through element '";

    /**
     * How many of the published pass-through descriptions name a CROSS-element crossing — the only
     * kind the rating charges and the only kind the registry registers.
     *
     * <p><b>The list's SIZE answers a different question and cannot stand in for this.</b>
     * {@link #detectPassThroughs} writes two kinds of entry into one list: a cross-element crossing,
     * which is counted into {@code crossElementCount} and drives the rating, and a connection routed
     * through its own endpoint, which is published for visibility and deliberately unrated. The list
     * is also capped at {@link #MAX_DESCRIPTIONS}. So on a view carrying both kinds the size
     * over-states how many of the charged crossings are named, and past the cap it under-states —
     * and a shortfall clause built on it would tell the caller to look for a number of objects the
     * field does not hold. Counting the cross-element form is what lets the sentence name a
     * shortfall the caller can actually reconcile.</p>
     *
     * <p>The two strings are single-sourced by a pin rather than by a shared constant, because the
     * detector's signature and its result record are frozen for fourteen callers:
     * {@code SuggestionSeverityOrderTest} asserts that a description this detector produced still
     * matches this form, so a reword that took this count silently to zero is a red test.</p>
     */
    private static int namedCrossElementPassThroughs(List<String> descriptions) {
        if (descriptions == null) {
            return 0;
        }
        int named = 0;
        for (String description : descriptions) {
            if (description != null && description.contains(CROSS_ELEMENT_PASS_THROUGH_FORM)) {
                named++;
            }
        }
        return named;
    }

    // ---- Suggestion severity: which metric gives a sentence its rank, and what that band caps at ----

    /**
     * The breakdown entries that are OUTPUTS of the two tier folds rather than inputs to them.
     *
     * <p>Neither fold reads either one, so leaving them in a probe would be harmless — they are
     * excluded anyway, because a probe map that carries a stale headline beside the metric being
     * driven invites a future reader to believe the headline is what the fold consulted.</p>
     */
    private static final Set<String> RATING_FOLD_OUTPUTS =
            Set.of("overall", "overallExcludingAcceptedCosmetics");

    /** The four ordering groups a suggestion can belong to. Lower sorts earlier. */
    private static final int SUGGESTION_GROUP_QUALIFIER = 0;
    private static final int SUGGESTION_GROUP_RATED = 1;
    private static final int SUGGESTION_GROUP_OTHER = 2;
    private static final int SUGGESTION_GROUP_TERMINAL = 3;

    /**
     * One suggestion sentence, the ordering group it belongs to, and — for a rated sentence — the
     * breakdown key that gives it its severity.
     *
     * <p><b>The rank metric is NOT the same thing as the metric a sentence records as explained,
     * and the two are deliberately written separately.</b> The rank metric answers "which breakdown
     * key gives this sentence its severity?"; the explained id answers "which registered finding
     * does this sentence account for?". They coincide for most branches and part company for
     * several: four rating-bearing metrics ({@code spacing}, {@code alignment}, {@code offCanvas},
     * {@code hubPortQuality}) are score-valued or unregistered and so rank without ever being
     * recorded as explained; the diagonal-terminal family emits one or two sentences that all rank
     * on a single key while recording three ids; and one branch records an id while adding no
     * sentence at all. Fusing them into one value would either suppress a disclosure or invent a
     * rank, so they stay two independent writes.</p>
     */
    private record RankedSuggestion(int group, String rankMetric, String text) {}

    /**
     * The suggestion list as it is BUILT — each sentence tagged with the ordering group it belongs
     * to and, where it has one, the breakdown key that carries its severity.
     *
     * <p>Four add methods rather than one with a nullable argument, so every call site states in
     * one word which group its sentence is in and a miswired site is a name a reader can check
     * against the branch beside it.</p>
     */
    private static final class SuggestionList {
        private final List<RankedSuggestion> entries = new ArrayList<>();

        /** A sentence that qualifies the whole assessment rather than reporting a defect. */
        void addQualifier(String text) {
            entries.add(new RankedSuggestion(SUGGESTION_GROUP_QUALIFIER, null, text));
        }

        /** A sentence whose severity comes from the named breakdown key. */
        void addRated(String rankMetric, String text) {
            entries.add(new RankedSuggestion(SUGGESTION_GROUP_RATED, rankMetric, text));
        }

        /** A defect or informational sentence that no breakdown key ranks. */
        void add(String text) {
            entries.add(new RankedSuggestion(SUGGESTION_GROUP_OTHER, null, text));
        }

        /** One of the three closing disclosures, which always come last. */
        void addTerminal(String text) {
            entries.add(new RankedSuggestion(SUGGESTION_GROUP_TERMINAL, null, text));
        }

        /**
         * The number of sentences added so far — read by the coverage verdict, which compares it
         * against the expected-state note tally. Counts exactly what the plain list counted.
         */
        int size() {
            return entries.size();
        }

        List<RankedSuggestion> entries() {
            return entries;
        }
    }

    /**
     * What one metric's severity band is, and what that band costs THIS view.
     *
     * <p>{@code capLevel} is the level a probe at {@code poor} survives to — 3 for an uncapped
     * band, 2 for a cap-fair band, 1 for a cap-good band. {@code contribution} is the level the
     * metric's LIVE value survives to, which is what the ordering sorts on.</p>
     */
    private record MetricSeverity(String band, int capLevel, int contribution) {}

    /**
     * A breakdown with every key at {@code pass} except {@code metric}, which is set to
     * {@code value} — the single-metric probe both derivations below drive the live folds with.
     *
     * <p>Returns {@code null} when the breakdown does not carry the metric at all, so a sentence
     * naming something the rating model does not rate publishes no band rather than a guess.</p>
     */
    private Map<String, String> singleMetricProbe(String metric, String value,
                                                  Map<String, String> breakdown) {
        if (breakdown == null || metric == null || !breakdown.containsKey(metric)
                || RATING_FOLD_OUTPUTS.contains(metric)) {
            return null;
        }
        Map<String, String> probe = new LinkedHashMap<>();
        for (String key : breakdown.keySet()) {
            if (!RATING_FOLD_OUTPUTS.contains(key)) {
                probe.put(key, "pass");
            }
        }
        probe.put(metric, value);
        return probe;
    }

    /**
     * One metric's band and its capped contribution to this view, MEASURED by driving
     * {@link #computeLayoutTierLevel} and {@link #computeRoutingTierLevel} one metric at a time.
     *
     * <p><b>Derived, never tabulated.</b> A fourth hand-maintained copy of the bands would drift in
     * step with nothing: the folds are the only place the tiers are decided, so the only band a
     * sentence can honestly publish is the one the folds hand back. Driving one metric to
     * {@code poor} against an otherwise-clean probe makes the cap legible in the answer — an
     * uncapped band survives at 3, a cap-fair band is held at 2, a cap-good band at 1, and a
     * dimension that never reads the metric returns 0.</p>
     *
     * <p><b>A tier CAPS a contribution; it does not PIN it.</b> That is why the band and the
     * contribution are two probes and not one: a cap-fair metric sitting at {@code good} costs the
     * view {@code good}, not {@code fair}. Reading the band as the contribution would publish a
     * metric as the thing holding a view down whenever it was merely present.</p>
     *
     * <p><b>The edge-coincidence count is this run's, never a placeholder.</b>
     * {@code connectionEdgeCoincidence} is a cap-fair band below
     * {@link #EDGE_COINCIDENCE_EGREGIOUS_MAX} and escalates into the uncapped band at or above it,
     * so both probes have to be driven at the count the view actually carries; a hardcoded zero
     * would publish the wrong band on exactly the views where the metric matters most.</p>
     *
     * @return the metric's severity, or {@code null} when neither fold reads it
     */
    private MetricSeverity severityOf(String metric, Map<String, String> breakdown,
                                      int edgeCoincidenceCount) {
        Map<String, String> bandProbe = singleMetricProbe(metric, "poor", breakdown);
        if (bandProbe == null) {
            return null;
        }
        int bandLayout = computeLayoutTierLevel(bandProbe);
        int bandRouting = computeRoutingTierLevel(bandProbe, edgeCoincidenceCount);
        int capLevel = Math.max(bandLayout, bandRouting);
        if (capLevel == 0) {
            return null;
        }
        String dimension = bandLayout >= bandRouting ? "L" : "R";
        Map<String, String> liveProbe =
                singleMetricProbe(metric, breakdown.get(metric), breakdown);
        int contribution = Math.max(computeLayoutTierLevel(liveProbe),
                computeRoutingTierLevel(liveProbe, edgeCoincidenceCount));
        // Level 3 is the first band, level 1 the third: the published spellings run Tier 1 (most
        // severe) to Tier 3 (least), which is the inverse of the level scale.
        return new MetricSeverity("Tier " + (4 - capLevel) + dimension, capLevel, contribution);
    }

    /**
     * The clause a rated sentence carries: which band its metric sits in, what that band caps its
     * contribution at, and whether it is one of the metrics holding this view where it is.
     *
     * <p><b>It names the metric and the band inline and points at no field.</b> This list is
     * republished verbatim by {@code auto-layout-and-route} and {@code adjust-view-spacing}, and
     * one of those responses carries no rating breakdown at all — so "see the breakdown" would be
     * a pointer to something absent from the response in front of the reader. The same reason the
     * terminal disclosure and the coverage verdict below already name each metric and its count
     * inline.</p>
     *
     * <p><b>A limiter claim is arithmetic, never an impression.</b> A metric is one of the
     * limiters exactly when its capped contribution equals the level the view actually sits at.
     * Where two metrics tie there, both say "one of" — no sentence may claim to be the sole cause.
     * Where the view sits at the top level there is nothing to hold it down, so no limiter claim is
     * printed at all. That last arm is a GUARD, not a case reached today: every branch that emits a
     * rated sentence fires on a condition that also takes its metric off {@code pass}, so a rated
     * sentence and a top-level view cannot co-occur. It is kept because the cost of a threshold
     * moving underneath it is a sentence claiming a metric holds a view at "excellent", and an
     * assertion nothing measured is exactly what this clause must never publish.</p>
     */
    private static String severityClause(String metric, MetricSeverity severity, int overallLevel) {
        String cap = severity.capLevel() >= 3
                ? "uncapped"
                : "caps at '" + levelToRating(severity.capLevel()) + "'";
        String limiter;
        if (overallLevel == 0) {
            limiter = "";
        } else if (severity.contribution() == overallLevel) {
            limiter = "; one of the metrics holding this view at '"
                    + levelToRating(overallLevel) + "'";
        } else if (severity.contribution() == 0) {
            limiter = "; contributes nothing, so it is not what holds this view at '"
                    + levelToRating(overallLevel) + "'";
        } else {
            limiter = "; contributes '" + levelToRating(severity.contribution())
                    + "', not the '" + levelToRating(overallLevel) + "' this view sits at";
        }
        return " (" + metric + " — " + severity.band() + ", " + cap + limiter + ")";
    }

    /**
     * Annotates every rated sentence and returns the list in severity order.
     *
     * <p><b>Four stable groups.</b> The assessment qualifier stays first because it qualifies the
     * whole run rather than reporting a defect; the rated sentences follow, worst capped
     * contribution first; the sentences no breakdown key ranks keep their emission order; and the
     * three closing disclosures stay last, where a reader who has worked the list already expects
     * to find what nothing above accounted for. Within every group the original emission order
     * survives, so an equal-severity tie reads the way the code that produced it reads.</p>
     *
     * <p>The sort key is the CAPPED CONTRIBUTION, not the band. Ranking on the band would put a
     * cap-fair metric sitting at {@code good} above a cap-good metric sitting at {@code good},
     * which is the same misdirection this ordering exists to remove, one metric along.</p>
     *
     * <p>The view's own level is read from the published headline the rating fold already
     * committed to, so the limiter claim and the {@code overallRating} the caller sees cannot
     * disagree about where the view sits.</p>
     */
    private List<String> orderAndAnnotate(SuggestionList suggestions,
                                          Map<String, String> breakdown,
                                          int edgeCoincidenceCount) {
        int overallLevel = breakdown == null
                ? 0 : ratingLevel(breakdown.getOrDefault("overall", "excellent"));

        List<RankedSuggestion> ordered = new ArrayList<>(suggestions.entries().size());
        Map<String, Integer> severityKeys = new HashMap<>();
        for (RankedSuggestion entry : suggestions.entries()) {
            MetricSeverity severity =
                    severityOf(entry.rankMetric(), breakdown, edgeCoincidenceCount);
            if (severity == null) {
                ordered.add(entry);
                continue;
            }
            ordered.add(new RankedSuggestion(entry.group(), entry.rankMetric(),
                    entry.text() + severityClause(entry.rankMetric(), severity, overallLevel)));
            severityKeys.put(entry.rankMetric(), severity.contribution());
        }

        // List.sort is stable, so every group keeps its emission order and so does every tie
        // inside the rated group. A re-collect through a set or a map would lose exactly that.
        ordered.sort(Comparator.comparingInt(RankedSuggestion::group)
                .thenComparingInt(entry -> entry.group() == SUGGESTION_GROUP_RATED
                        ? -severityKeys.getOrDefault(entry.rankMetric(), 0) : 0));

        List<String> published = new ArrayList<>(ordered.size());
        for (RankedSuggestion entry : ordered) {
            published.add(entry.text());
        }
        return published;
    }

    private List<String> generateSuggestions(int overlaps, int crossings,
                                              double avgSpacing, int alignmentScore,
                                              int boundaryViolationCount, int offCanvasCount,
                                              int nodeCount, int labelOverlapCount,
                                              boolean hasGroups, int connectionCount,
                                              int coincidentSegmentCount,
                                              int nonOrthogonalTerminalCount,
                                              int shortSegmentCount,
                                              int containmentOverlapCount,
                                              int zeroBendpointNonOrthCount,
                                              int routedNonOrthCount,
                                              int interiorTerminationCount,
                                              int zigzagCount,
                                              int connectionEdgeCoincidenceCount,
                                              double hubPortQualityScore,
                                              int anchorDriftCount,
                                              int lateralJogReversalCount,
                                              OwnIconOverLabelResult ownIcon,
                                              CoverageDeclaration coverage,
                                              MetricFindings metricFindings,
                                              RatingBearingFindings ratingBearing,
                                              InformationalFindings informational,
                                              Map<String, String> ratingBreakdown) {
        SuggestionList suggestions = new SuggestionList();

        // Suggestions that report something the view is EXPECTED to contain. They are prose, so they
        // make the list non-empty, but they are not findings — a verdict blocked by one of them
        // withholds the coverage qualification from a view that has nothing wrong with it.
        int expectedStateNotes = 0;

        // The metrics this run's prose actually accounted for, recorded as each sentence is added.
        //
        // WHY THIS IS RECORDED PER RUN AND NOT READ FROM A TABLE OF WHICH METRICS HAVE PROSE.
        // Several branches below are threshold-gated, so whether a metric is explained is a fact
        // about this run and not about the metric. edgeCrossings is the clearest: it is measured,
        // registered, and has a remedy — but only above CROSSING_SUGGESTION_THRESHOLD, so a view
        // with three crossings has it in the findings and no sentence about it anywhere. A static
        // "these metrics carry prose" mapping would mark it explained and the disclosure would stay
        // silent about a finding nothing named. The spacing, alignment and hub-port branches are
        // gated the same way.
        //
        // The failure mode of forgetting to record a metric here is a redundant sentence naming
        // something the list already explained — visible, and correctable by the reader. The
        // failure mode of a table that claims a metric is explained when this run did not explain
        // it is silence. Only branches explaining a REGISTERED metric need recording; anything
        // uninstrumented falls through as unexplained, which is the safe direction by construction.
        Set<String> explained = new LinkedHashSet<>();

        // Finding #7: performance warning for large views
        if (nodeCount > LARGE_VIEW_WARNING_THRESHOLD) {
            suggestions.addQualifier("View has " + nodeCount + " elements (>" + LARGE_VIEW_WARNING_THRESHOLD
                    + ") — assessment metrics may be slow for very large views.");
        }

        // Group-aware suggestions.
        // Groups: suggest layout-within-group + auto-route-connections.
        // Non-grouped (flat or containment): auto-route-connections / auto-layout-and-route.
        // compute-layout (formerly layout-view) removed from all suggestion paths.
        if (hasGroups) {
            if (overlaps > 0) {
                suggestions.addRated("overlaps", "Found " + overlaps
                        + " overlapping element pairs — use layout-within-group"
                        + " with increased spacing to spread elements apart,"
                        + " then re-run auto-route-connections");
                explained.add("overlaps");
            }
            if (crossings > CROSSING_SUGGESTION_THRESHOLD) {
                double ratio = connectionCount > 0
                        ? (double) crossings / connectionCount : crossings;
                suggestions.addRated("edgeCrossings", "Found " + crossings
                        + " edge crossings (" + String.format("%.1f", ratio)
                        + " per connection) — increase element spacing within groups"
                        + " using layout-within-group and re-run auto-route-connections");
                explained.add("edgeCrossings");
            }
            if (avgSpacing < SPACING_SUGGESTION_THRESHOLD && overlaps == 0) {
                suggestions.addRated("spacing", "Average spacing is only " + Math.round(avgSpacing)
                        + "px — use adjust-view-spacing to increase gaps and improve"
                        + " routing quality, or manually increase spacing with"
                        + " layout-within-group then re-run auto-route-connections");
            }
        } else {
            // Flat or containment view: suggest layout-flat-view / auto-route / auto-layout-and-route
            if (overlaps > 0) {
                suggestions.addRated("overlaps", "Found " + overlaps
                        + " overlapping element pairs — use layout-flat-view to"
                        + " reposition elements with proper spacing, then"
                        + " auto-route-connections. Or use auto-layout-and-route"
                        + " for fully algorithmic positioning");
                explained.add("overlaps");
            }
            if (crossings > CROSSING_SUGGESTION_THRESHOLD) {
                suggestions.addRated("edgeCrossings", "Found " + crossings
                        + " edge crossings — try auto-route-connections first"
                        + " (preserves positions). If crossings persist, use"
                        + " layout-flat-view with increased spacing to reposition"
                        + " elements, then re-route. Use auto-layout-and-route"
                        + " with targetRating as a last resort");
                explained.add("edgeCrossings");
            }
            if (avgSpacing < SPACING_SUGGESTION_THRESHOLD && overlaps == 0) {
                suggestions.addRated("spacing", "Average spacing is only " + Math.round(avgSpacing)
                        + "px — use layout-flat-view with increased spacing"
                        + " to reposition elements, then auto-route-connections");
            }
        }
        if (alignmentScore < ALIGNMENT_SUGGESTION_THRESHOLD) {
            if (hasGroups) {
                suggestions.addRated("alignment", "Alignment score is " + alignmentScore
                        + "/100 — use layout-within-group to improve alignment within each group");
            } else {
                suggestions.addRated("alignment", "Alignment score is " + alignmentScore
                        + "/100 — use auto-layout-and-route for uniform alignment");
            }
        }
        if (boundaryViolationCount > 0) {
            suggestions.addRated("boundaryViolations", "Found " + boundaryViolationCount
                    + " elements extending outside their parent containers"
                    + " — resize the containers or reposition the elements");
            explained.add("boundaryViolations");
        }
        if (offCanvasCount > 0) {
            suggestions.addRated("offCanvas", "Found " + offCanvasCount
                    + " elements at negative or extreme coordinates"
                    + " — reposition to visible canvas area");
        }
        if (labelOverlapCount > 0) {
            if (hasGroups) {
                suggestions.addRated("labelOverlaps", labelOverlapCount + " connection labels overlap or are too close to elements or other labels"
                        + " — increase spacing within groups using layout-within-group"
                        + " and re-run auto-route-connections");
            } else {
                suggestions.addRated("labelOverlaps", labelOverlapCount + " connection labels overlap or are too close to elements or other labels"
                        + " — use auto-layout-and-route with increased spacing");
            }
            explained.add("labelOverlaps");
        }

        // Short-segment label suggestion (separate from general label overlap)
        if (shortSegmentCount > 0) {
            suggestions.add(shortSegmentCount + " connection labels exceed available segment length"
                    + " — increase element spacing");
        }

        // Non-orthogonal terminal suggestion with ELK-aware text
        if (nonOrthogonalTerminalCount > 0) {
            String elkSuffix = " — these are straight-line connections typical of ELK layout;"
                    + " a full re-route would likely increase crossings."
                    + " Run auto-route-connections with mode='terminals-only' to rectify"
                    + " terminal segments without touching the routed body";
            if (zeroBendpointNonOrthCount == nonOrthogonalTerminalCount) {
                // All non-orth connections are zero-bendpoint (ELK straight-line signature)
                suggestions.addRated("nonOrthogonalTerminals", nonOrthogonalTerminalCount + " connections have diagonal terminal segments"
                        + elkSuffix);
            } else if (zeroBendpointNonOrthCount > 0) {
                // Mixed: the flagged population splits into two halves whose remedies are
                // OPPOSITE, so each entry states which half it is and the total it is a half of.
                // Two entries opening with the same clause and no shared denominator read as two
                // unrelated findings, and an agent cannot then tell one partition from two
                // populations. Each entry also names the violator key carrying exactly its own
                // ids, and the precondition for receiving them, because the ids are what make the
                // remedy scopeable — an unscoped call is view-wide and would reach the other half.
                suggestions.addRated("nonOrthogonalTerminals", zeroBendpointNonOrthCount + " of " + nonOrthogonalTerminalCount
                        + " connections with diagonal terminal segments carry no bendpoints"
                        + elkSuffix
                        + ". Their connection IDs are listed under the violatorIds key"
                        + " nonOrthogonalTerminalsZeroBendpoint, returned when"
                        + " includeViolatorIds is true");
                suggestions.addRated("nonOrthogonalTerminals", routedNonOrthCount + " of " + nonOrthogonalTerminalCount
                        + " connections with diagonal terminal segments carry a routed body"
                        + " — pass exactly these connection IDs to auto-route-connections as"
                        + " connectionIds, so the re-route is confined to this half and every"
                        + " other connection on the view keeps its existing bendpoints;"
                        + " mode='terminals-only' additionally preserves each routed body."
                        + " Their connection IDs are listed under the violatorIds key"
                        + " nonOrthogonalTerminalsRouted, returned when includeViolatorIds is"
                        + " true");
            } else {
                // No zero-BP: all are routed connections
                suggestions.addRated("nonOrthogonalTerminals", nonOrthogonalTerminalCount + " connections have diagonal terminal segments"
                        + " — re-run auto-route-connections (or use mode='terminals-only'"
                        + " to preserve the routed body) to improve orthogonality");
            }
            // All three registered metrics of this family are accounted for by the branch taken:
            // the two halves are disjoint and sum to the total, so whichever of them is nonzero is
            // either named outright (the mixed case names both, with the total each is a half of)
            // or is the whole population the single sentence describes.
            explained.add("nonOrthogonalTerminals");
            explained.add("nonOrthogonalTerminalsZeroBendpoint");
            explained.add("nonOrthogonalTerminalsRouted");
        }

        // Coincident segment suggestion
        if (coincidentSegmentCount > 0) {
            if (hasGroups) {
                suggestions.addRated("coincidentSegments", coincidentSegmentCount + " overlapping connection segments detected"
                        + " — use adjust-view-spacing to increase element spacing and re-route"
                        + " in a single call, or manually increase spacing with"
                        + " layout-within-group then re-run auto-route-connections");
            } else {
                suggestions.addRated("coincidentSegments", coincidentSegmentCount + " overlapping connection segments detected"
                        + " — increase element spacing or use auto-layout-and-route"
                        + " to separate coincident paths");
            }
            explained.add("coincidentSegments");
        }

        // §10.4: Informational containment overlap note — clarifies that these are expected
        // ancestor-descendant overlaps (elements inside groups), not layout problems.
        if (containmentOverlapCount > 0) {
            suggestions.add(containmentOverlapCount
                    + " containment overlaps detected (expected — ancestor-descendant"
                    + " overlaps from elements inside groups, not layout problems). No action needed.");
            // Counted, not just added. This sentence says the view is behaving as intended, so it
            // must not be what stops the coverage qualification below from reaching the caller.
            expectedStateNotes++;
        }

        // M2: interior terminations.
        if (interiorTerminationCount > 0) {
            suggestions.addRated("interiorTerminations", interiorTerminationCount
                    + " connections terminate inside element bounds — check ChopboxAnchor"
                    + " face selection and re-run auto-route-connections");
            explained.add("interiorTerminations");
        }

        // M3: zigzag/reversal patterns.
        if (zigzagCount > 0) {
            suggestions.addRated("zigzags", zigzagCount
                    + " connections have zigzag/reversal patterns — re-run"
                    + " auto-route-connections; PathStraightener.eliminateReversals or"
                    + " removeCollinearPoints may need investigation");
            explained.add("zigzags");
        }

        // Anchor drift: the stored route no longer matches the geometry it was computed for.
        // Deliberately does NOT name the straightener — the stored shape is not the problem, so a
        // straightening pass cannot repair it. Re-routing recomputes against the current geometry.
        if (anchorDriftCount > 0) {
            suggestions.add(anchorDriftCount
                    + " connections have anchor drift — an endpoint moved or was resized after the"
                    + " route was written, so the drawn path no longer relates to the elements it"
                    + " was routed around; re-route the named connections to recompute them against"
                    + " the current geometry");
            explained.add("anchorDrift");
        }

        // Lateral-jog reversals: a route doubling back through a narrow sidestep.
        if (lateralJogReversalCount > 0) {
            suggestions.add(lateralJogReversalCount
                    + " connections double back through a sidestep narrower than "
                    + (int) LATERAL_JOG_MAX_PX
                    + "px — the route leaves a corridor and immediately re-enters it; re-run"
                    + " auto-route-connections");
            explained.add("lateralJogReversals");
        }

        // M4: connection-vs-element-edge coincidence.
        if (connectionEdgeCoincidenceCount > 0) {
            // The grazed-element companion is reported HERE rather than in a sentence of its own,
            // because it has no reachable standalone case: countConnectionEdgeCoincidence records a
            // graze and increments this tally inside the same block, so the companion is nonzero
            // only on a run where this count is nonzero too. A separate branch for it would be dead
            // code, and a second sentence on the runs where it did fire would report one connection
            // hugging one element twice. What the companion adds that this count cannot is the
            // number of DISTINCT element edges involved, so that number is stated only when the two
            // differ — where they agree it would repeat the count already in the sentence.
            int grazedElements = informational == null || informational.edgeCoincidence() == null
                    ? 0 : informational.edgeCoincidence().grazedElementCount();
            suggestions.addRated("connectionEdgeCoincidence", connectionEdgeCoincidenceCount
                    + (connectionEdgeCoincidenceCount == 1
                            ? " connection segment hugs an element edge within "
                            : " connection segments hug element edges within ")
                    + (int) EDGE_COINCIDENCE_TOLERANCE_PX
                    + "px — consider channel offset or increased element spacing"
                    + (grazedElements > connectionEdgeCoincidenceCount
                            ? ", which reaches " + grazedElements + " distinct element edges in"
                                    + " total, since one connection can hug several. Those element"
                                    + " IDs are under assess-layout's violatorIds key"
                                    + " edgeCoincidenceGrazedElements, returned when it is called"
                                    + " with includeViolatorIds, and that set is deduplicated"
                                    + " view-wide, so it can be smaller than the total"
                            : "")
                    + ". This dimension is declared partial by assess-layout: its detector examines"
                    + " only"
                    + " axis-aligned segments, so neither number speaks for diagonal segments at"
                    + " all — render-verify those");
            explained.add("connectionEdgeCoincidence");
            explained.add("edgeCoincidenceGrazedElements");
        }

        // M5: hub-port allocation quality. Both the "fair" and the "poor" band take a routing
        // tier off the view, so both are explained. Firing only on "poor" left the whole
        // quarter-wide "fair" interval capped and unexplained.
        String hubPortBand = hubPortQualityBand(hubPortQualityScore);
        if ("fair".equals(hubPortBand) || "poor".equals(hubPortBand)) {
            suggestions.addRated("hubPortQuality", "Hub-port allocation quality is "
                    + String.format("%.2f", hubPortQualityScore)
                    + " (rated " + hubPortBand + " — below the good band at "
                    + HUB_PORT_QUALITY_GOOD_THRESHOLD
                    + ") — terminal allocator failing to distribute connections across face slots;"
                    + " inspect violatorIds.hubPortLowQuality for affected elements");
        }

        // An element's own icon drawn over its own title. The count moves no rating, and that is
        // precisely why the prose has to carry it: a defect suppressed from the rating AND absent
        // from the prose is invisible. The degenerate single-object path already applies this
        // reasoning; this is the same rule on the path that actually runs. The detector RESULT is
        // passed rather than a bare count, so the argument stays type-distinct from the twenty-two
        // numeric parameters above and a miswired call site is a compile error rather than a
        // plausible wrong number.
        if (ownIcon != null && ownIcon.count() > 0) {
            // The description list is CAPPED, and this dimension publishes no violator-id key, so
            // on a view carrying more findings than the cap the remainder is recoverable by no
            // route at all. Pointing at the list as though it named them all would be the same
            // unverified claim this suggestion exists to stop making, one field along.
            int named = ownIcon.descriptions() == null ? 0 : ownIcon.descriptions().size();
            suggestions.add((ownIcon.count() == 1
                            ? "1 element has its own icon drawn over its own title label"
                            : ownIcon.count() + " elements have their own icon drawn over their"
                                    + " own title label")
                    + " — the element name is buried under the glyph. Widen the element, move the"
                    + " icon to a corner the title does not reach, or change the object's text"
                    + " alignment. Text alignment is a property of the VIEW OBJECT, not of the"
                    + " model element, so the correction must be repeated on every view that shows"
                    + " the element"
                    + ownIconDescriptionClause(named, ownIcon.count()));
            explained.add("ownIconOverLabel");
        }

        // The metrics that CAP OR VETO a rating while contributing nothing to the prose that
        // explains it. A caller marked down by one of them was, until now, told only what the rating
        // was — and, from the rating switch, to run a tool that cannot move the cause. Each remedy
        // below is the lever already published in this tool's own served description block; a remedy
        // invented here that disagreed with that block would fork the two surfaces.
        //
        // Named, never counted: a tally here would go stale the moment the block grew, which is
        // exactly what happened when the last two silent metrics were given the sentences below.
        //
        // Their informational siblings are deliberately NOT named here. That silence is the same
        // defect one step down in severity and it is tracked separately; what makes the metrics
        // below different is that the tool acts on them.
        if (ratingBearing != null) {
            ParentLabelObscuredResult parentLabel = ratingBearing.parentLabelObscured();
            if (parentLabel != null && parentLabel.count() > 0) {
                int named = parentLabel.descriptions() == null ? 0 : parentLabel.descriptions().size();
                suggestions.addRated("parentLabelObscured", (parentLabel.count() == 1
                                ? "1 parent's title label is overlapped by its topmost child"
                                : parentLabel.count() + " parents have their title label overlapped"
                                        + " by their topmost child")
                        + " — this drops the layout tier to 'poor' and vetoes the overall rating, so"
                        + " no view carrying it can rate 'good'. Move children down or increase parent"
                        + " top padding. Automated layout cannot clear it: the child is where its"
                        + " parent's title renders, which is a padding decision, not a routing one"
                        + descriptionClause("parentLabelObscuredDescriptions", false,
                                named, parentLabel.count()));
                explained.add("parentLabelObscured");
            }

            LabelTruncationResult truncations = ratingBearing.labelTruncations();
            if (truncations != null && truncations.count() > 0) {
                int named = truncations.descriptions() == null ? 0 : truncations.descriptions().size();
                suggestions.addRated("labelTruncations", (truncations.count() == 1
                                ? "1 element's label is truncated"
                                : truncations.count() + " element labels are truncated")
                        + " — the text exceeds the width available beside the type icon, so the name"
                        + " the reader sees is not the name the model holds. This caps the routing"
                        + " tier at 'fair'. Use resize-elements-to-fit or increase element width"
                        + descriptionClause("labelTruncations", false, named, truncations.count()));
                explained.add("labelTruncations");
            }

            NonOrthogonalInteriorSegmentResult interiorSegments =
                    ratingBearing.nonOrthogonalInteriorSegments();
            if (interiorSegments != null && interiorSegments.count() > 0) {
                int named = interiorSegments.descriptions() == null
                        ? 0 : interiorSegments.descriptions().size();
                suggestions.addRated("nonOrthogonalInteriorSegments", interiorSegments.count()
                        + (interiorSegments.count() == 1
                                ? " connection has an off-cardinal segment in the interior of its"
                                        + " route"
                                : " connections have an off-cardinal segment in the interior of"
                                        + " their routes")
                        + ", between the two terminal segments — a"
                        + " mid-route diagonal is as visible as one at an endpoint, and this caps the"
                        + " routing tier at 'fair'. Re-run auto-route-connections for clean orthogonal"
                        + " paths"
                        + descriptionClause("nonOrthogonalInteriorSegmentDescriptions", true,
                                named, interiorSegments.count()));
                explained.add("nonOrthogonalInteriorSegments");
            }

            OffFaceParallelTerminalResult offFace = ratingBearing.offFaceParallelTerminals();
            if (offFace != null && offFace.count() > 0) {
                int named = offFace.descriptions() == null ? 0 : offFace.descriptions().size();
                suggestions.addRated("offFaceParallelTerminals", offFace.count()
                        + (offFace.count() == 1
                                ? " connection departs an element face and then runs parallel to it,"
                                : " connections depart an element face and then run parallel to it,")
                        + " hugging it — any nonzero count caps the routing tier at 'fair', because a"
                        + " hug is visible however few there are. Push the first segment perpendicular"
                        + " off the face before turning; if auto-route-connections reports"
                        + " EGRESS_LIFT_LAYOUT_BOUND the hug cannot be routed away and the remedy is to"
                        + " spread the elements"
                        + descriptionClause("offFaceParallelTerminalDescriptions", true,
                                named, offFace.count()));
                explained.add("offFaceParallelTerminals");
            }

            ConnectionThroughVisualResult throughNote = ratingBearing.connectionThroughNote();
            if (throughNote != null && throughNote.count() > 0) {
                int named = throughNote.descriptions() == null ? 0 : throughNote.descriptions().size();
                suggestions.addRated("connectionThroughNote", throughNote.count()
                        + (throughNote.count() == 1
                                ? " connection runs straight through a note box or an element's"
                                        + " rendered image"
                                : " connections run straight through a note box or an element's"
                                        + " rendered image")
                        + " — this caps the routing tier at 'good' on presence alone. Reroute the"
                        + " connection or move the note/image clear"
                        + descriptionClause("connectionThroughNoteDescriptions", false,
                                named, throughNote.count()));
                explained.add("connectionThroughNote");
            }

            // Cross-element pass-throughs. Until this branch existed a view whose ONLY finding was
            // one of these published a downgraded rating beside "No defects were found on the
            // dimensions this run examined": the metric was in neither source the verdict reads —
            // no registry entry, so nothing in `found`, and no sentence, so nothing in the list.
            // Both sides are closed now, the sentence here and the registry entry at the top of
            // assess(), and either alone would have closed the verdict; both are needed because
            // they answer different questions, the sentence "what do I do about it" and the
            // registry "what did this run find".
            PassThroughResult passThroughs = ratingBearing.passThroughs();
            if (passThroughs != null && passThroughs.crossElementCount() > 0) {
                int crossedElements = passThroughs.crossElementCount();
                int named = namedCrossElementPassThroughs(passThroughs.descriptions());
                suggestions.addRated("passThroughs", (crossedElements == 1
                                ? "1 connection is drawn straight across an element it does not"
                                        + " connect to"
                                : crossedElements + " connections are drawn straight across elements"
                                        + " they"
                                        + " do not connect to")
                        + " — a line over an unrelated box reads as a relationship the model does"
                        + " not hold, and more than " + FAIR_MAX_PASS_THROUGHS + " such crossings"
                        + " rate this metric 'poor'. Re-run auto-route-connections, whose visibility-graph"
                        + " A* treats elements as obstacles; where the crossed element sits in the"
                        + " only corridor between the two endpoints no route can clear it, and the"
                        + " lever is room rather than routing — re-place the elements with"
                        + " auto-layout-and-route. Do NOT reach for a spacing tool here: inflation"
                        + " past the narrow-corridor floor INTRODUCES pass-throughs faster than it"
                        + " removes them, which is why the spacing tools carry an inflation-knee"
                        + " guard. This count is CROSS-element crossings only: assess-layout's"
                        + " connectionPassThroughs also names connections routed through their own"
                        + " endpoint, which are reported for visibility and excluded from the rating"
                        // The pointer is emitted on EVERY path, including the one where the
                        // description list names NONE of the charged crossings. That case is
                        // real: the list is shared with the unrated self-element pass-throughs
                        // and capped, so enough self-element entries early in connection order
                        // fill it before any cross-element crossing is described. Falling through
                        // to descriptionClause there would return an empty string and publish a
                        // count with nowhere to look it up — the same silently-incomplete defect
                        // this whole branch exists to close, one field along.
                        + (named == 0
                                ? "; assess-layout's connectionPassThroughs names none of them,"
                                        + " because that capped list is shared with the unrated"
                                        + " self-element pass-throughs — call assess-layout with"
                                        + " includeViolatorIds and read this dimension's"
                                        + " violator-id list instead: uncapped, cross-element"
                                        + " only, and the complete register of the "
                                        + crossedElements + " charged here"
                                : descriptionClause("connectionPassThroughs", true, named,
                                        crossedElements)));
                explained.add("passThroughs");
            }

            // Hub-to-neighbour crowding. NO explained.add and NO registry entry, and neither is an
            // oversight: the metric is score-valued, its clean value is a LARGE clearance or the
            // no-hub sentinel, so a registry entry keyed on "count != 0" would invert its meaning —
            // the same ruling MetricFindings already records for the other five score-valued
            // metrics. Recording an id that is registered nowhere would put a name into the
            // explained set that nothing can ever match it against.
            //
            // Gated on the BREAKDOWN rather than on a second reading of the clearance floor: the
            // fold has already decided whether this view is crowded, and a second copy of
            // CROWDING_FLOOR_PX here could come to disagree with the entry the rating published.
            // Only the DENSE arm of the handler's diagnostic prose can be reached from here — the
            // sparse arm is the case where the clearance is at or above the floor, which is
            // precisely the case this branch does not fire on — so the sparse lever is not written
            // out as an unreachable alternative.
            HubNeighbourCrowdingResult crowding = ratingBearing.hubNeighbourCrowding();
            if (crowding != null && ratingBreakdown != null
                    && ratingBreakdown.containsKey("hubNeighbourCrowding")
                    && !"pass".equals(ratingBreakdown.get("hubNeighbourCrowding"))) {
                // FLOOR, not round. The branch guarantees the clearance is below the floor as a
                // real number, but rounding to nearest can carry a 59.6 up to 60 and publish
                // "only 60px ... below the 60px clearance floor" — a sentence asserting that a
                // number is below itself. Rounding DOWN also never overstates a clearance the
                // sentence calls "only".
                suggestions.addRated("hubNeighbourCrowding", "A hub's edge is only "
                        + (long) Math.floor(crowding.minClearance())
                        + "px from the row of spokes facing it, below the "
                        + Math.round(CROWDING_FLOOR_PX) + "px clearance floor, so the corridor"
                        + " those neighbours route through has collapsed and enlarging the hub"
                        + " would crowd them further. Revert the hub to its normal size first — an"
                        + " oversized hub before ELK causes interior terminations — then run"
                        + " auto-layout-and-route to re-place the elements, then a FULL"
                        + " auto-route-connections, NOT terminals-only, which vetoes terminations"
                        + " landing inside the re-placed elements. Acceptance is"
                        + " render-authoritative: confirm with export-view and look, because the"
                        + " rating alone can score a crowded layout 'good'. The measured clearance"
                        + " is assess-layout's hubNeighbourClearanceMin");
            }
        }

        // The thirteen metrics that move NO rating and, until now, put no sentence anywhere. Each
        // remedy below is the lever already published in this tool's own served description block,
        // read from that block rather than inferred from the field name; a remedy invented here
        // that disagreed with the block would fork the two surfaces, and one naming the wrong tool
        // would be worse than the silence it replaces.
        //
        // One branch per metric rather than one sentence driven by a metric-to-lever lookup. The
        // remedy then sits beside the detector a reader can check it against, and the terminal
        // disclosure below stays a pure backstop for what nothing explained rather than becoming a
        // second prose surface for everything.
        if (informational != null) {
            OverlapResult overlapFindings = informational.overlaps();
            if (overlapFindings != null && overlapFindings.cousinCount() > 0) {
                // The informational COMPANION of overlaps, which is rated and has its own sentence
                // above. Its own served block says one visible collision between two nested objects
                // usually yields several pairs here, because each object also overlaps the other's
                // container — so on a view whose overlap sentence has already fired, a second
                // sentence reports one collision twice with a larger number. Suppressed there, and
                // counted as accounted for: the principal's prose is what explains the collision.
                if (overlaps > 0) {
                    explained.add("cousinOverlaps");
                } else {
                    int named = overlapFindings.cousinDescriptions() == null
                            ? 0 : overlapFindings.cousinDescriptions().size();
                    suggestions.add((overlapFindings.cousinCount() == 1
                                    ? "1 cross-branch overlapping pair"
                                    : overlapFindings.cousinCount()
                                            + " cross-branch overlapping pairs")
                            + (overlapFindings.cousinCount() == 1
                                    ? " — two objects in different containers with no ancestor"
                                    : " — objects in different containers with no ancestor")
                            + " relationship between them, which overlapCount reports under their"
                            + " containers' names rather than their own. Read this as a list of"
                            + " pairs to inspect, not as a count of distinct visible"
                            + " collisions: one"
                            + " collision between two nested objects usually yields several pairs,"
                            + " because each object also overlaps the other's container."
                            + " Reposition one object of each pair to separate them; where the two"
                            + " belong under one parent, nesting them there also brings the overlap"
                            + " under overlapCount, which IS rated"
                            + descriptionClause("cousinOverlaps", true,
                                    named, overlapFindings.cousinCount()));
                    explained.add("cousinOverlaps");
                }
            }

            NoteOverlapResult noteOverlap = informational.noteOverlap();
            if (noteOverlap != null && noteOverlap.count() > 0) {
                int named = noteOverlap.descriptions() == null
                        ? 0 : noteOverlap.descriptions().size();
                suggestions.add((noteOverlap.count() == 1
                                ? "1 note-over-object overlap was measured"
                                : noteOverlap.count() + " note-over-object overlaps were measured")
                        + " — a sticky note dropped on top of the diagram. The count is per (note,"
                        + " object) PAIR, not per note: one note lying across three elements"
                        + " reports 3 rather than 1, so this number can exceed the number of notes"
                        + " on the view. Move"
                        + " the note clear with update-view-object, or nest it inside the container"
                        + " it belongs to; a note whose parent is the container it sits in is"
                        + " deliberate placement and is not flagged"
                        + descriptionClause("noteOverlapDescriptions", false,
                                named, noteOverlap.count()));
                explained.add("noteOverlap");
            }

            NoteClipResult noteClip = informational.noteClip();
            if (noteClip != null && noteClip.count() > 0) {
                int named = noteClip.descriptions() == null ? 0 : noteClip.descriptions().size();
                suggestions.add((noteClip.count() == 1
                                ? "1 note has text needing more height than its box provides"
                                : noteClip.count() + " notes have text needing more height than"
                                        + " their boxes provide")
                        + ", so the content is clipped and the reader sees less than the note"
                        + " holds. Re-send the note's text (or its width) through update-view-object"
                        + " with height omitted and the server re-fits the height to the wrapped"
                        + " content; or raise the height, or reduce the font size. A clip can only"
                        + " arise from an explicitly pinned height, since an auto-fitted note is by"
                        + " construction tall enough"
                        + descriptionClause("noteClipDescriptions", false, named, noteClip.count()));
                explained.add("noteClip");
            }

            ImageSiblingOverlapResult imageSibling = informational.imageSiblingOverlap();
            if (imageSibling != null && imageSibling.count() > 0) {
                int named = imageSibling.descriptions() == null
                        ? 0 : imageSibling.descriptions().size();
                suggestions.add((imageSibling.count() == 1
                                ? "1 element has its image area overlapped by a sibling element"
                                : imageSibling.count() + " elements have their image area"
                                        + " overlapped by a sibling element")
                        + " — the custom image or specialization icon, sized from its true rendered"
                        + " dimensions, is covered by a neighbour. Increase element spacing,"
                        + " reposition the image, or shrink the icon"
                        + descriptionClause("imageSiblingOverlapDescriptions", false,
                                named, imageSibling.count()));
                explained.add("imageSiblingOverlap");
            }

            OverlayIconCollisionResult overlayIcon = informational.overlayIconCollision();
            if (overlayIcon != null && overlayIcon.count() > 0) {
                int named = overlayIcon.descriptions() == null
                        ? 0 : overlayIcon.descriptions().size();
                suggestions.add((overlayIcon.count() == 1
                                ? "1 element's overlay icon collides with the icon of an element"
                                        + " that contains it"
                                : overlayIcon.count() + " elements have an overlay icon colliding"
                                        + " with the icon of an element that contains them")
                        + " — a nested object and its zone both carrying an icon in the same"
                        + " corner. Ordinary nesting is not flagged, only icon-on-icon. Move the"
                        + " nested element, put one icon in a different corner, or shrink it"
                        + descriptionClause("overlayIconCollisionDescriptions", false,
                                named, overlayIcon.count()));
                explained.add("overlayIconCollision");
            }

            ParallelConnectionGapResult parallelGap = informational.parallelGap();
            if (parallelGap != null && parallelGap.vAxis() != null
                    && parallelGap.vAxis().narrowGapCount25() > 0) {
                int narrow = parallelGap.vAxis().narrowGapCount25();
                // The lever comes from this metric's own served block, which states outright that
                // convenience spacing tools cannot move a narrow-corridor floor. Naming
                // adjust-view-spacing here would be the wrong-lever failure that is worse than
                // silence. The block publishes its remedy against the percentile rather than
                // against this count, and the two share one dimension, so it applies to both.
                suggestions.add(narrow
                        + (narrow == 1
                                ? " vertical connection segment runs"
                                : " vertical connection segments run")
                        + " within 25px of the nearest parallel segment sharing its span — the"
                        + " narrow-corridor tail of the parallel-gap distribution, where routes"
                        + " read as one thick line rather than as separate edges. Convenience"
                        + " spacing tools cannot mitigate a narrow-corridor floor: redesign the"
                        + " topology (reduce hub fan-out, or split the view) or apply manual"
                        + " bendpoint surgery via update-view-connection. This dimension publishes"
                        + " no description list; the connection IDs are under assess-layout's"
                        + " violatorIds key parallelConnectionGapV, and the full per-axis detail is"
                        + " in its parallelConnectionGapDetail — both returned only when"
                        + " assess-layout is called with includeViolatorIds");
                explained.add("vAxisParallelGapNarrow25Count");
            }

            if (parallelGap != null && parallelGap.hAxis() != null
                    && parallelGap.hAxis().narrowGapCount25() > 0) {
                int narrow = parallelGap.hAxis().narrowGapCount25();
                // Same lever as the V arm above, and for the same published reason: the two axes
                // share one coverage dimension, so the block's ruling that convenience spacing
                // cannot move a narrow-corridor floor applies to both. Written as its own arm
                // rather than folded into the V branch because either axis can be narrow while the
                // other is clear, and a shared branch would report one count under both names.
                suggestions.add(narrow
                        + (narrow == 1
                                ? " horizontal connection segment runs"
                                : " horizontal connection segments run")
                        + " within 25px of the nearest parallel segment sharing its span — the"
                        + " narrow-corridor tail of the parallel-gap distribution, where routes"
                        + " read as one thick line rather than as separate edges. Convenience"
                        + " spacing tools cannot mitigate a narrow-corridor floor: redesign the"
                        + " topology (reduce hub fan-out, or split the view) or apply manual"
                        + " bendpoint surgery via update-view-connection. This dimension publishes"
                        + " no description list; the connection IDs are under assess-layout's"
                        + " violatorIds key parallelConnectionGapH, and the full per-axis detail is"
                        + " in its parallelConnectionGapDetail — both returned only when"
                        + " assess-layout is called with includeViolatorIds");
                explained.add("hAxisParallelGapNarrow25Count");
            }

            ConnectionThroughVisualResult grazesVisual = informational.throughVisual();
            if (grazesVisual != null && grazesVisual.grazeCount() > 0) {
                int named = grazesVisual.grazeDescriptions() == null
                        ? 0 : grazesVisual.grazeDescriptions().size();
                suggestions.add(grazesVisual.grazeCount()
                        + (grazesVisual.grazeCount() == 1
                                ? " connection touches or clips the BORDER of a note or an"
                                        + " element's rendered image"
                                : " connections touch or clip the BORDER of a note or an element's"
                                        + " rendered image")
                        + " — the outer band the interior pass-through test discards. Counted per"
                        + " connection and visual, and disjoint from connectionThroughNoteCount:"
                        + " one crossing is classified as exactly one of through or graze. Reroute"
                        + " the connection or move the note/image clear"
                        + descriptionClause("connectionGrazesVisualDescriptions", false,
                                named, grazesVisual.grazeCount()));
                explained.add("connectionGrazesVisual");
            }

            RedundantBendpointResult redundant = informational.redundantBendpoints();
            if (redundant != null && redundant.count() > 0) {
                int named = redundant.descriptions() == null ? 0 : redundant.descriptions().size();
                suggestions.add(redundant.count()
                        + (redundant.count() == 1
                                ? " bendpoint is collinear along a horizontal or vertical segment"
                                        + " and lies between its neighbours, so removing it would"
                                        + " not change the orthogonal route. The reported point is"
                                : " bendpoints are collinear along a horizontal or vertical segment"
                                        + " and lie between their neighbours, so removing them"
                                        + " would not change the orthogonal route. The reported"
                                        + " points are")
                        + " genuinely removable: near-collinear diagonal micro-jogs are"
                        + " excluded, because removing those would diagonalise an orthogonal"
                        + " segment, and so are terminal egress stubs, which the router pins for"
                        + " anchoring. Straighten the route or re-run auto-route-connections"
                        + descriptionClause("connectionRedundantBendpointDescriptions", true,
                                named, redundant.count()));
                explained.add("redundantBendpoints");
            }

            ContainerFillResult containerFill = informational.containerFill();
            if (containerFill != null && containerFill.count() > 0) {
                int named = containerFill.descriptions() == null
                        ? 0 : containerFill.descriptions().size();
                suggestions.add(containerFill.count()
                        + (containerFill.count() == 1
                                ? " container has an authored fill colour equal to a nested child's,"
                                        + " so the parent and its children merge into one flat"
                                        + " single-colour block. Give that container a distinct"
                                        + " (lighter) fill."
                                : " containers have an authored fill colour equal to a nested"
                                        + " child's, so each of them merges with its children into"
                                        + " one flat single-colour block. Give each a distinct"
                                        + " (lighter) fill.")
                        + " Only an explicit same-colour fill is flagged: placing a child inside a"
                        + " container whose fill is unauthored already recedes the parent to a"
                        + " backdrop"
                        + descriptionClause("containerFillEqualsChildDescriptions", true,
                                named, containerFill.count()));
                explained.add("containerFillRecession");
            }

            LabelOnNoteResult labelOnNote = informational.labelOnNote();
            if (labelOnNote != null && labelOnNote.count() > 0) {
                int named = labelOnNote.descriptions() == null
                        ? 0 : labelOnNote.descriptions().size();
                suggestions.add(labelOnNote.count()
                        + (labelOnNote.count() == 1
                                ? " connection label is rendered on a note's rectangle"
                                : " connection labels are rendered on a note's rectangle")
                        + " — the caption collision the route detectors cannot see, since a label"
                        + " is positioned independently of the line it belongs to. Reposition the"
                        + " label (apply a Label Offset, or run auto-route-connections) or move the"
                        + " note clear"
                        + descriptionClause("labelOnNoteDescriptions", true,
                                named, labelOnNote.count()));
                explained.add("labelOnNote");
            }

            LabelOnGroupResult labelOnGroup = informational.labelOnGroup();
            if (labelOnGroup != null && labelOnGroup.count() > 0) {
                int named = labelOnGroup.descriptions() == null
                        ? 0 : labelOnGroup.descriptions().size();
                suggestions.add(labelOnGroup.count()
                        + (labelOnGroup.count() == 1
                                ? " connection label is rendered on a container's title band"
                                : " connection labels are rendered on a container's title band")
                        + " — a native group or an ArchiMate Grouping alike, which the"
                        + " label-overlap detector cannot see because it skips containers wholesale"
                        + " as transparent. Only the top title strip is tested, so a label sitting"
                        + " in the container body is normal and is not flagged. Reposition the label"
                        + " or reroute the connection clear of the container title"
                        + descriptionClause("labelOnGroupDescriptions", true,
                                named, labelOnGroup.count()));
                explained.add("labelOnGroup");
            }

            CoincidentFacePortResult coincidentPorts = informational.coincidentFacePorts();
            if (coincidentPorts != null && coincidentPorts.count() > 0) {
                int named = coincidentPorts.descriptions() == null
                        ? 0 : coincidentPorts.descriptions().size();
                suggestions.add(coincidentPorts.count()
                        + (coincidentPorts.count() == 1
                                ? " element face carries two or more connection terminals colliding"
                                        + " onto one perimeter port"
                                : " element faces carry two or more connection terminals colliding"
                                        + " onto one perimeter port")
                        + ", so two or more edges appear to leave a single point. This is the"
                        + " blind spot"
                        + " in hubPortQualityScore, whose per-face guard only scores a face carrying"
                        + " four or more connections, so a face with two or three coincident"
                        + " terminals reads a vacuous 1.00 despite the collision. Spread the"
                        + " terminals across the face with auto-route-connections, which dissolves a"
                        + " coincident same-face pair on a low-degree element"
                        + descriptionClause("coincidentFacePortDescriptions", true,
                                named, coincidentPorts.count()));
                explained.add("coincidentFacePorts");
            }
        }

        // Whether any DEFECT was reported. Read before the coverage prose is appended, because
        // that prose is not a defect: a run that found nothing still gets the scoped verdict below,
        // and a run that found something must not.
        //
        // TWO independent sources, because the list alone answers a narrower question than the
        // verdict asks. Only the metrics with remedy prose can put anything in the list, so a run
        // whose findings are all among the metrics without prose leaves it empty — and a verdict
        // sourced from that emptiness told the caller nothing was found on dimensions that were
        // examined and did find something. The measured counts close that. The list is still read
        // as well, because a defect can be named by prose without any count moving: the score-valued
        // metrics (spacing, alignment, hub-port quality) and the view-size warning are all
        // threshold-driven, and none of them is a count the carrier could hold.
        //
        // The list is compared against the expected-state notes rather than against empty, so a
        // sentence that reports the view behaving as intended cannot suppress the qualification.
        List<MetricFinding> found = metricFindings.found();
        boolean noDefectsFound = found.isEmpty() && suggestions.size() == expectedStateNotes;

        // What this run measured MINUS what this run's prose accounted for. Sourced from the
        // recorded set rather than from the shape of the suggestion list, which is the change that
        // makes the disclosure reach a busy view: gating it on "no other prose exists at all" left
        // a finding with no remedy named nowhere the moment any explained defect fired beside it,
        // and an overlap co-occurring with a note overlap is the ordinary case, not a corner.
        List<MetricFinding> unexplained = new ArrayList<>();
        for (MetricFinding finding : found) {
            if (!explained.contains(finding.metric())) {
                unexplained.add(finding);
            }
        }

        if (!unexplained.isEmpty()) {
            // Something WAS found and no prose above accounted for it. Naming the metric and its
            // count inline is deliberate — the sentence has to stand on its own, because
            // auto-layout-and-route and adjust-view-spacing republish this list beside a response
            // that carries none of these fields, so a pointer to a field would be a pointer to
            // something the reader cannot see on three of the four surfaces it reaches.
            StringBuilder named = new StringBuilder();
            for (MetricFinding finding : unexplained) {
                if (named.length() > 0) {
                    named.append(", ");
                }
                named.append(finding.metric()).append(" (").append(finding.count()).append(")");
            }
            suggestions.addTerminal((unexplained.size() == 1
                            ? "1 dimension this run examined reported a finding that carries no"
                                    + " specific remedy above: "
                            : unexplained.size() + " dimensions this run examined reported findings"
                                    + " that carry no specific remedy above: ")
                    + named
                    + ". The count beside each name is what was found. Read the matching count and"
                    + " description fields in assess-layout's own response, and render-verify the"
                    + " objects behind them before treating this view as clean.");
        }

        if (noDefectsFound) {
            // NOT an all-clear. The claim is scoped to the dimensions this run actually examined,
            // and the number it could not certify is stated rather than left for the caller to
            // discover by opening `coverage` unprompted. An unqualified "layout quality is good"
            // asserted a clean whole view on every run — including one where a detector had
            // skipped an object and honestly reported zero — which is the false all-clear this
            // project forbids a detector from emitting, reached through the prose instead of
            // through a count.
            // The pointer names the TOOL that publishes the map, not just "the coverage map".
            // This list is republished by auto-layout-and-route and adjust-view-spacing, whose
            // responses carry no coverage map at all, so an unqualified "read the coverage map"
            // sends the caller to a field that is not in front of them on three of the four
            // surfaces this sentence reaches.
            suggestions.addTerminal("No defects were found on the dimensions this run examined. That is"
                    + " not a clean bill of health for the whole view: "
                    + coverage.notFullyExaminedCount() + " of " + coverage.dimensionCount()
                    + " coverage dimensions were not fully examined on this run, so a zero on"
                    + " those is not evidence of absence. The per-dimension detail is in"
                    + " assess-layout's coverage map; render-verify every dimension it does not"
                    + " mark \"checked\".");
        }

        // Emitted whether or not a defect was found, and deliberately NOT inside the branch above.
        // A contextual downgrade is this run's news — the dimension is declared fully covered and
        // was not certifiable only because something on THIS view could not be measured — and it
        // has to reach the caller even when the list already carries defects. It would otherwise
        // be unreportable for labelOverlaps in particular: that dimension downgrades on exactly
        // the condition that also emits the short-segment suggestion above, so its list is never
        // empty and a disclosure confined to the empty case could never name it.
        for (CoverageDimension dimension : coverage.contextualPartials()) {
            suggestions.addTerminal(contextualPartialSuggestion(dimension));
        }

        // ORDERING IS THE LAST THING THIS METHOD DOES, and that placement is load-bearing rather
        // than stylistic. Everything above reads the list in EMISSION order: noDefectsFound
        // compares its SIZE against the expected-state note tally, and the three terminal families
        // are appended on the strength of that comparison. Sorting earlier would change what
        // size() is being compared to at the moment the coverage verdict is decided, which can
        // flip the verdict on a view whose defects are unchanged.
        return orderAndAnnotate(suggestions, ratingBreakdown, connectionEdgeCoincidenceCount);
    }

    /**
     * The prose for one contextually-downgraded dimension: what could not be certified, why, and
     * what the caller must do instead of reading its count as a clean result.
     *
     * <p>The reason is read from the dimension's own {@link ContextualTrigger}, so the sentence and
     * the downgrade that produced it come from one declaration and cannot describe different
     * things.</p>
     */
    private static String contextualPartialSuggestion(CoverageDimension dimension) {
        // Takes the dimension, not its id. The id form needed a lookup that silently produced a
        // truncated sentence when nothing matched — a soft failure that reads as a complete
        // result. Reading the trigger off the dimension removes the lookup entirely, so the
        // reason clause cannot go missing.
        return "The " + dimension.id + " dimension could not be fully examined on this run, because "
                + dimension.contextualTrigger.reason
                + ". That dimension is declared \"partial\" rather than \"checked\" in"
                + " assess-layout's coverage map, so its count is honestly zero yet certifies"
                + " nothing — render-verify it before treating this view as clean on that"
                + " dimension.";
    }

    /**
     * Builds element/group context from {@code layoutNodes} and
     * invokes {@link CoincidentSegmentDiagnostic#emit} to log a per-pair
     * categorization. Called only when {@code -Darchi.mcp.diag.coincident=true}.
     */
    private void emitCoincidentDiagnostic(List<AssessmentConnection> connections,
                                          List<AssessmentNode> layoutNodes) {
        // Lookup: node id → rect.
        Map<String, CoincidentSegmentDiagnostic.ElementRect> rectById = new HashMap<>();
        List<CoincidentSegmentDiagnostic.GroupRect> topLevelGroups = new ArrayList<>();
        for (AssessmentNode n : layoutNodes) {
            rectById.put(n.id(), new CoincidentSegmentDiagnostic.ElementRect(
                    n.id(), n.x(), n.y(), n.width(), n.height()));
            if (n.isContainer() && n.parentId() == null) {
                topLevelGroups.add(new CoincidentSegmentDiagnostic.GroupRect(
                        n.id(), n.x(), n.y(), n.width(), n.height()));
            }
        }

        Map<Integer, String> connIds = new HashMap<>();
        Map<Integer, CoincidentSegmentDiagnostic.ElementRect> sources = new HashMap<>();
        Map<Integer, CoincidentSegmentDiagnostic.ElementRect> targets = new HashMap<>();
        for (int i = 0; i < connections.size(); i++) {
            AssessmentConnection c = connections.get(i);
            connIds.put(i, c.id());
            sources.put(i, rectById.get(c.sourceNodeId()));
            targets.put(i, rectById.get(c.targetNodeId()));
        }

        CoincidentSegmentDiagnostic.emit(coincidentDetector, connections,
                connIds, sources, targets, topLevelGroups);
    }
}
