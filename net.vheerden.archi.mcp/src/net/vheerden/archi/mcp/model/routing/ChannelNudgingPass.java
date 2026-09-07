package net.vheerden.archi.mcp.model.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Channel-global ordered nudging post-pass.
 *
 * <p>Operates on the full route set produced by the A* routing pipeline, groups
 * axis-aligned segment runs by the <b>obstacle-bounded corridor</b> they occupy,
 * then assigns each run a track within its corridor with full multi-edge awareness.
 * The aim is to make wide-open corridors visibly used (single-occupant routes land
 * near corridor centrelines instead of grazing walls) and to fan parallel runs
 * sharing a corridor evenly across the channel rather than clustering on one wall.
 *
 * <p><b>Architectural commitment (load-bearing):</b> routing quality is a global
 * property of the route set, not a local property of individual edges or connections.
 * This class operates post-A* on routes-with-global-visibility, which is the layer
 * prescribed by Wybrow/Marriott/Stuckey 2009 §4 "Ordering and Nudging",
 * Hegemann &amp; Wolff 2023 §3-§4, and libavoid {@code orthogonal.cpp}'s
 * {@code nudgeOrthogonalRoutes}. Per-edge cost terms and
 * per-connection post-passes both failed to address the corridor-occupancy
 * problem because they operate at the wrong layer; this class is the first proposal
 * at the correct layer.
 *
 * <p><b>Key scheme:</b> Phase 1 groups runs by {@link ChannelKey} computed via
 * {@link CoincidentSegmentDetector#computeCorridorGap} per-segment. Two runs threading
 * the same obstacle pair produce identical keys regardless of their current
 * perpendicular coordinates — which is the load-bearing fix for the spike
 * falsification of the original rounded-sharedCoord key inherited from
 * {@link CorridorOccupancyTracker}. The occupancy tracker is not touched by this
 * class; its read API at {@code VisibilityGraphRouter:222} is preserved.
 *
 * <p><b>Pure geometry.</b> No EMF, no SWT, no PDE. All inputs are in-memory
 * DTOs and RoutingRects; all mutation is to the caller-supplied bendpoint lists.
 * Callable from standard JUnit tests without OSGi.
 *
 * <p><b>Per-route rollback.</b> Every nudge is attempted under a
 * snapshot. Three post-condition checks in order — terminal-alignment invariant,
 * obstacle clearance, no-new-coincident-pair — restore the snapshot on failure.
 * A route is never left in a "nudged-and-broken" state.
 *
 * <p><b>Diagnostic logging.</b> Per-decision INFO lines gated on the system property
 * {@value #DIAGNOSTIC_PROPERTY} (default off). Per-rollback INFO lines are
 * unconditional — rollbacks are rare in practice and matter for diagnostics.
 */
public class ChannelNudgingPass {

    private static final Logger logger = LoggerFactory.getLogger(ChannelNudgingPass.class);

    /** System property gating verbose per-decision diagnostic log lines (default off). */
    public static final String DIAGNOSTIC_PROPERTY = "archi.mcp.channelnudging.diagnostic";

    /**
     * Minimum nudge magnitude in pixels. A run whose current perpendicular coordinate
     * is within this distance of its ideal coordinate is not nudged — micro-shifts
     * below ~4px are visually invisible and not worth the rollback risk.
     */
    static final int MIN_NUDGE_PX = 4;

    /** Minimum clearance between a track and the corridor walls. Matches {@code RoutingPipeline.MIN_CLEARANCE}. */
    static final int MIN_CLEARANCE_PX = 10;

    /** Minimum spacing between adjacent tracks in a multi-occupant channel. Matches {@code EdgeNudger.DEFAULT_MIN_SPACING}. */
    static final int MIN_TRACK_SPACING_PX = 8;

    /** Maximum spacing between adjacent tracks — prevents fan-out spreading into adjacent corridors. */
    static final int MAX_TRACK_SPACING_PX = 30;

    /** Parallel-range overlap slack: runs touching at a single point are NOT in the same sub-channel. */
    static final int OVERLAP_SLACK = 0;

    /**
     * Minimum parallel length of a segment to be a nudge candidate. Segments shorter
     * than this are treated as inter-route fan-out micro-jogs produced by the A*
     * router under full-batch conditions (with corridor occupancy tracking),
     * not as corridor-body runs.
     *
     * <p><b>Rationale for 100 px:</b> empirically determined from the V3 API Management
     * Platform → Branch Teller pathology observed during live E2E.
     * Under full-batch routing, A* produces 40-57 px
     * terminal-adjacent jogs to separate co-exiting connections from the same source.
     * Phase 2 would otherwise nudge these micro-jogs to their channel midpoint,
     * creating visible hooks because the perpendicular "catch-up" segments at each end
     * dominate the segment being improved.
     *
     * <p>The 100 px threshold is chosen so it is (a) large enough to exclude the observed
     * 40-57 px fan-out jogs with margin, (b) small enough to admit legitimate interior
     * corridor bodies from V7 widened (480 px), BE→RelMgr (310 px), V4 middleware
     * (hundreds of px). A 101 px run IS admitted; a 99 px run is not. The threshold is
     * absolute, not ratio-based: a ratio rule like {@code 2 * |idealCoord - current| >
     * parallelLength} was considered but rejected because it over-rejects legitimate
     * long-distance nudges (T1: length 200, nudge 180 — ratio rule would skip, though
     * the test's neighbouring terminal approaches already extend 415 px so no hook is
     * created).
     *
     * <p>If future fixtures demonstrate that 100 is too tight or too loose, the value
     * should be re-tuned with a dedicated empirical study rather than adjusted ad-hoc.
     */
    static final int MIN_INTERIOR_SEGMENT_LENGTH_PX = 100;

    /** Axis of an axis-aligned segment. */
    enum Axis { H, V }

    /**
     * Obstacle-bounded channel identity. Replaces the rounded-sharedCoord key.
     * Two runs threading the same obstacle pair produce identical keys regardless
     * of their current perpendicular coordinates.
     */
    record ChannelKey(Axis axis, int gapLow, int gapHigh) {}

    /**
     * A single axis-aligned run of one route, tracked as a channel occupant.
     * Mutable {@link #currentSharedCoord} field reflects the perpendicular coordinate
     * after any applied nudge; {@link #bpIdxLo} and {@link #bpIdxHi} are the indices
     * of the two bendpoints bounding the run inside {@link #path}.
     */
    static final class RouteSegmentRun {
        final String connectionId;
        final List<AbsoluteBendpointDto> path;
        final int bpIdxLo;
        final int bpIdxHi;
        final Axis axis;
        final int parStart;
        final int parEnd;
        int currentSharedCoord;

        RouteSegmentRun(String connectionId, List<AbsoluteBendpointDto> path,
                        int bpIdxLo, int bpIdxHi, Axis axis,
                        int parStart, int parEnd, int currentSharedCoord) {
            this.connectionId = connectionId;
            this.path = path;
            this.bpIdxLo = bpIdxLo;
            this.bpIdxHi = bpIdxHi;
            this.axis = axis;
            this.parStart = parStart;
            this.parEnd = parEnd;
            this.currentSharedCoord = currentSharedCoord;
        }
    }

    /** A channel: a group of runs sharing an obstacle-bounded corridor. */
    static final class Channel {
        final ChannelKey key;
        final List<RouteSegmentRun> occupants = new ArrayList<>();

        Channel(ChannelKey key) {
            this.key = key;
        }

        int gapLow() {
            return key.gapLow();
        }

        int gapHigh() {
            return key.gapHigh();
        }
    }

    // --- Instance state ---

    private final CoincidentSegmentDetector gapHelper = new CoincidentSegmentDetector();

    /**
     * Track registry: for each channel we record which perpendicular coordinates have
     * already been allocated so the nearest-free-track query can avoid conflicts.
     * Key schemes are fully contained — there is no overlap with {@link CorridorOccupancyTracker}.
     */
    private final Map<ChannelKey, NavigableSet<Integer>> trackRegistry = new LinkedHashMap<>();

    /**
     * Global log of every successfully-nudged run across the entire pass, used by
     * {@link #introducesNewCoincidentPair} to detect cross-channel collisions — two
     * runs in <em>different</em> obstacle-bounded channels can still coincident-pair
     * if their post-nudge (axis, sharedCoord) match and their parallel ranges overlap
     * (widened; pre-fix the check only scanned same-channel occupants
     * and missed this case entirely).
     */
    private final List<NudgedRun> nudgedRunsLog = new ArrayList<>();

    /** Total nudges successfully applied during the current {@link #run} invocation. */
    private int nudgeCount;

    /** Total per-route rollbacks during the current {@link #run} invocation. */
    private int rollbackCount;

    /**
     * Immutable record of a successfully-nudged run, used for cross-channel collision
     * detection in {@link #introducesNewCoincidentPair}.
     */
    private record NudgedRun(String connectionId, Axis axis, int sharedCoord,
                             int parStart, int parEnd) {
    }

    public ChannelNudgingPass() {
    }

    public int getNudgeCount() { return nudgeCount; }

    public int getRollbackCount() { return rollbackCount; }

    /**
     * Run the channel nudging pass on all routes.
     *
     * <p>The two list arguments are index-parallel: {@code connections.get(i)} is the
     * endpoint record for the route {@code paths.get(i)}. Paths are mutated in-place;
     * on any per-route rollback the path is restored to its pre-nudge state.
     *
     * @param connections  per-route endpoint records (source, target, id)
     * @param paths        per-route bendpoint lists (mutated in place)
     * @param allObstacles every non-container view object — elements, notes and images alike
     *                     (for obstacle-bounded channel computation)
     * @return total number of nudges successfully applied across all routes
     */
    public int run(List<RoutingPipeline.ConnectionEndpoints> connections,
                   List<List<AbsoluteBendpointDto>> paths,
                   List<RoutingRect> allObstacles) {
        return run(connections, paths, allObstacles, List.of());
    }

    /**
     * Run the channel nudging pass on all routes, with inter-group corridor awareness.
     *
     * <p>When {@code topLevelGroupBounds} is non-empty, group boundaries tighten
     * the obstacle-bounded gap computed by {@link CoincidentSegmentDetector#computeCorridorGap}.
     * Segments in inter-group corridors (where no element obstacles bound the corridor)
     * get channel walls from adjacent group edges instead of the synthetic
     * {@code ±MAX_UNBOUNDED_EXTENT} default, causing them to share a single
     * {@link ChannelKey} and fan out across the corridor width.
     *
     * @param connections          per-route endpoint records (source, target, id)
     * @param paths                per-route bendpoint lists (mutated in place)
     * @param allObstacles         every non-container view object — elements, notes and images alike
     * @param topLevelGroupBounds  top-level group rectangles (no nested groups)
     * @return total number of nudges successfully applied across all routes
     */
    public int run(List<RoutingPipeline.ConnectionEndpoints> connections,
                   List<List<AbsoluteBendpointDto>> paths,
                   List<RoutingRect> allObstacles,
                   List<RoutingRect> topLevelGroupBounds) {
        nudgeCount = 0;
        rollbackCount = 0;
        trackRegistry.clear();
        nudgedRunsLog.clear();

        if (connections == null || paths == null || connections.size() != paths.size()) {
            return 0;
        }
        if (allObstacles == null) {
            allObstacles = Collections.emptyList();
        }
        if (topLevelGroupBounds == null) {
            topLevelGroupBounds = List.of();
        }

        // Phase 1 — group all axis-aligned runs into obstacle-bounded channels.
        List<Channel> channels = groupIntoChannels(connections, paths, allObstacles,
                topLevelGroupBounds);

        if (diagnosticEnabled()) {
            logger.info("Phase 1 complete: {} channels across {} routes",
                    channels.size(), paths.size());
        }

        // Phase 2 — deterministic iteration over channels; allocate tracks with
        // per-route rollback; apply nudges in place.
        allocateTracks(connections, paths, channels, allObstacles);

        logger.info("Channel nudging complete: {} nudges applied, {} rollbacks",
                nudgeCount, rollbackCount);

        return nudgeCount;
    }

    // =====================================================================
    // Phase 1 — channel grouping
    // =====================================================================

    /**
     * Extracts axis-aligned segment runs from every route, keys them by obstacle-bounded
     * channel, and sub-groups same-key runs by parallel-range overlap. Returns the full
     * flat list of channels in deterministic iteration order.
     */
    List<Channel> groupIntoChannels(List<RoutingPipeline.ConnectionEndpoints> connections,
                                    List<List<AbsoluteBendpointDto>> paths,
                                    List<RoutingRect> allObstacles) {
        return groupIntoChannels(connections, paths, allObstacles, List.of());
    }

    /**
     * Extracts axis-aligned segment runs from every route, keys them by obstacle-bounded
     * channel (tightened by group boundaries when available), and sub-groups same-key
     * runs by parallel-range overlap. Returns the full flat list of channels in
     * deterministic iteration order.
     *
     * <p>After {@code computeCorridorGap()} returns the element-level gap, each
     * group boundary is checked as a potential tighter bound. Group edges that fall
     * between the current gap bound and the segment's shared coordinate replace the
     * bound. This causes inter-group corridor segments to share a single
     * {@link ChannelKey} derived from group edges rather than the synthetic
     * {@code ±MAX_UNBOUNDED_EXTENT} default.
     */
    List<Channel> groupIntoChannels(List<RoutingPipeline.ConnectionEndpoints> connections,
                                    List<List<AbsoluteBendpointDto>> paths,
                                    List<RoutingRect> allObstacles,
                                    List<RoutingRect> topLevelGroupBounds) {
        // Step 1: collect runs by channel key.
        Map<ChannelKey, List<RouteSegmentRun>> byKey = new LinkedHashMap<>();

        for (int routeIdx = 0; routeIdx < paths.size(); routeIdx++) {
            List<AbsoluteBendpointDto> path = paths.get(routeIdx);
            if (path == null || path.size() < 2) {
                continue; // no internal segments
            }
            RoutingPipeline.ConnectionEndpoints conn = connections.get(routeIdx);
            String connectionId = conn.connectionId();

            for (int i = 0; i < path.size() - 1; i++) {
                AbsoluteBendpointDto a = path.get(i);
                AbsoluteBendpointDto b = path.get(i + 1);

                Axis axis;
                int sharedCoord;
                int parStart;
                int parEnd;
                boolean horizontal = (a.y() == b.y()) && (a.x() != b.x());
                boolean vertical = (a.x() == b.x()) && (a.y() != b.y());
                if (horizontal) {
                    axis = Axis.H;
                    sharedCoord = a.y();
                    parStart = Math.min(a.x(), b.x());
                    parEnd = Math.max(a.x(), b.x());
                } else if (vertical) {
                    axis = Axis.V;
                    sharedCoord = a.x();
                    parStart = Math.min(a.y(), b.y());
                    parEnd = Math.max(a.y(), b.y());
                } else {
                    continue; // diagonal or zero-length — skip
                }

                int[] gap = gapHelper.computeCorridorGap(
                        sharedCoord, axis == Axis.H, parStart, parEnd, allObstacles);
                if (gap == null) {
                    // Segment lies inside an obstacle — shouldn't happen post-A*. Skip defensively.
                    continue;
                }

                // Tighten gap bounds using top-level group boundaries.
                // Group edges that fall between the current bound and the segment's
                // shared coordinate replace the bound, giving inter-group corridor
                // segments a ChannelKey derived from real group walls instead of the
                // synthetic ±MAX_UNBOUNDED_EXTENT default.
                gap = tightenGapWithGroupBounds(gap, sharedCoord, axis == Axis.H,
                        topLevelGroupBounds);

                ChannelKey key = new ChannelKey(axis, gap[0], gap[1]);

                byKey.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new RouteSegmentRun(connectionId, path, i, i + 1,
                                axis, parStart, parEnd, sharedCoord));
            }
        }

        // Step 2: within each channel key, split into sub-channels by parallel-range overlap.
        // Two runs with the same ChannelKey but non-overlapping [parStart, parEnd] do NOT
        // compete for the same track — they occupy different portions of the corridor.
        List<Channel> channels = new ArrayList<>();
        for (Map.Entry<ChannelKey, List<RouteSegmentRun>> e : byKey.entrySet()) {
            ChannelKey key = e.getKey();
            List<RouteSegmentRun> runs = new ArrayList<>(e.getValue());
            runs.sort(Comparator.comparingInt(r -> r.parStart));

            Channel current = null;
            int currentParEndMax = Integer.MIN_VALUE;
            for (RouteSegmentRun run : runs) {
                if (current == null || run.parStart > currentParEndMax + OVERLAP_SLACK) {
                    if (current != null) channels.add(current);
                    current = new Channel(key);
                    current.occupants.add(run);
                    currentParEndMax = run.parEnd;
                } else {
                    current.occupants.add(run);
                    currentParEndMax = Math.max(currentParEndMax, run.parEnd);
                }
            }
            if (current != null) channels.add(current);
        }

        return channels;
    }

    /**
     * Tighten an element-level corridor gap using top-level group boundaries.
     *
     * <p>For each group boundary, checks whether either perpendicular edge falls
     * between the current gap bound and the segment's shared coordinate. If so,
     * records it as a candidate tighter bound. Tightening is only applied when
     * <b>both</b> a near-side and far-side group boundary are found — this ensures
     * the segment is in a genuine inter-group corridor bounded by two groups on
     * opposite sides, not just near a single group edge.
     *
     * <p>The both-sides gate prevents spurious corridors: e.g. on V7, groups
     * stacked vertically at different Y ranges would otherwise create Y-axis
     * corridors between them, changing channel keys for horizontal segments that
     * happen to be in the Y-gap. Requiring two facing group walls ensures only
     * real inter-group corridors (like the X-gap between L-to-R groups on V4)
     * produce tightened channels.
     *
     * @param gap            element-level gap as [nearBound, farBound]
     * @param sharedCoord    the segment's perpendicular coordinate
     * @param horizontal     true if the segment is horizontal (perpendicular = Y)
     * @param groupBounds    top-level group rectangles
     * @return tightened gap (may be the same array if no tightening occurred)
     */
    static int[] tightenGapWithGroupBounds(int[] gap, int sharedCoord, boolean horizontal,
                                            List<RoutingRect> groupBounds) {
        if (groupBounds == null || groupBounds.isEmpty()) {
            return gap;
        }

        int nearBound = gap[0];
        int farBound = gap[1];
        boolean nearTightened = false;
        boolean farTightened = false;

        for (RoutingRect group : groupBounds) {
            int perpStart, perpEnd;
            if (horizontal) {
                // Horizontal segment: perpendicular axis is Y
                perpStart = group.y();
                perpEnd = group.y() + group.height();
            } else {
                // Vertical segment: perpendicular axis is X
                perpStart = group.x();
                perpEnd = group.x() + group.width();
            }

            // Skip groups that contain the segment (segment is inside this group).
            // Group edges are not channel walls for segments inside them.
            if (perpStart <= sharedCoord && perpEnd >= sharedCoord) {
                continue;
            }

            // Group edge on the near side (lower perpendicular values):
            // perpEnd is the group's far edge, which forms the near wall of the corridor
            if (perpEnd <= sharedCoord && perpEnd > nearBound) {
                nearBound = perpEnd;
                nearTightened = true;
            }

            // Group edge on the far side (higher perpendicular values):
            // perpStart is the group's near edge, which forms the far wall of the corridor
            if (perpStart >= sharedCoord && perpStart < farBound) {
                farBound = perpStart;
                farTightened = true;
            }
        }

        // Only apply tightening when BOTH sides have group boundaries — this means
        // the segment is in a genuine inter-group corridor bounded by two groups.
        // A single-sided tightening would create spurious corridors near isolated
        // group edges (e.g. V7 horizontal segments between vertically-stacked groups).
        if (nearTightened && farTightened
                && (nearBound != gap[0] || farBound != gap[1])) {
            return new int[]{nearBound, farBound};
        }
        return gap;
    }

    // =====================================================================
    // Phase 2 — track allocation
    // =====================================================================

    /**
     * Walks channels in deterministic order; for each channel, either centres the
     * single occupant on the slack midpoint or fans out multiple occupants with even
     * spacing. Every nudge is attempted under per-route rollback.
     */
    void allocateTracks(List<RoutingPipeline.ConnectionEndpoints> connections,
                        List<List<AbsoluteBendpointDto>> paths,
                        List<Channel> channels,
                        List<RoutingRect> allObstacles) {
        // Build connection-id → endpoint record map for fast lookup during rollback.
        Map<String, RoutingPipeline.ConnectionEndpoints> connById = new LinkedHashMap<>();
        for (RoutingPipeline.ConnectionEndpoints c : connections) {
            connById.put(c.connectionId(), c);
        }

        // Deterministic channel iteration: sort by (axis, gapLow, gapHigh).
        channels.sort(Comparator
                .comparing((Channel c) -> c.key.axis())
                .thenComparingInt(c -> c.key.gapLow())
                .thenComparingInt(c -> c.key.gapHigh()));

        for (Channel channel : channels) {
            int midpoint = (channel.gapLow() + channel.gapHigh()) / 2;
            int available = channel.gapHigh() - channel.gapLow() - 2 * MIN_CLEARANCE_PX;

            if (diagnosticEnabled()) {
                logger.info("Considering corridor={}:[{},{}] axis={} runs={}",
                        channel.key.axis(), channel.gapLow(), channel.gapHigh(),
                        channel.key.axis(), channel.occupants.size());
            }

            if (channel.occupants.size() == 1) {
                RouteSegmentRun run = channel.occupants.get(0);
                if (Math.abs(midpoint - run.currentSharedCoord) < MIN_NUDGE_PX) {
                    continue; // already centred — no nudge needed
                }
                attemptNudge(run, midpoint, channel, connById.get(run.connectionId), allObstacles);
                continue;
            }

            // Multi-occupant: fan out with even spacing.
            int n = channel.occupants.size();
            if (available < (n - 1) * MIN_TRACK_SPACING_PX) {
                if (diagnosticEnabled()) {
                    logger.info("Oversubscribed corridor={}:[{},{}] occupants={} available={} — skip",
                            channel.key.axis(), channel.gapLow(), channel.gapHigh(), n, available);
                }
                continue; // oversubscribed — leave A* output alone for this group
            }

            int spacing = Math.max(MIN_TRACK_SPACING_PX, available / (n - 1));
            // Width-aware cap: scale the 30 px cap with corridor width so that
            // wide corridors (e.g. V4 producer corridor gap=250) get a modest
            // fan-out widening without disrupting dense sibling layouts.
            // divisor=7 gives cap=30 for gap≤216, cap=35 for gap=250,
            // cap=42 for gap=300 — calibrated to keep V7-widened (gap=254)
            // within the wall-buffer band the WCU pinned tests guarantee.
            int corridorWidth = available + 2 * MIN_CLEARANCE_PX;
            int dynamicCap = Math.max(MAX_TRACK_SPACING_PX, corridorWidth / 7);
            spacing = Math.min(spacing, dynamicCap);

            // Deterministic tie-break: sort occupants by connection ID lexicographic.
            List<RouteSegmentRun> sortedOccupants = new ArrayList<>(channel.occupants);
            sortedOccupants.sort(Comparator.comparing(r -> r.connectionId));

            for (int i = 0; i < sortedOccupants.size(); i++) {
                RouteSegmentRun run = sortedOccupants.get(i);
                double offset = (i - (n - 1) / 2.0) * spacing;
                int idealCoord = midpoint + (int) Math.round(offset);
                attemptNudge(run, idealCoord, channel, connById.get(run.connectionId), allObstacles);
            }
        }
    }

    /**
     * Per-route monotone rollback. Takes a snapshot, applies the nudge,
     * runs three post-condition checks in order, and restores the snapshot on any
     * failure. A route is never left in a "nudged-and-broken" state.
     */
    void attemptNudge(RouteSegmentRun run, int idealCoord, Channel channel,
                      RoutingPipeline.ConnectionEndpoints conn, List<RoutingRect> allObstacles) {
        if (conn == null) {
            return;
        }

        // Short-segment skip (V3 hook fix).
        // Segments whose parallel length is below MIN_INTERIOR_SEGMENT_LENGTH_PX are
        // treated as inter-route fan-out micro-jogs produced by the A* router under
        // full-batch conditions, NOT as corridor-body runs. Nudging them typically
        // forces the segment across the source/target center line, producing visible
        // hooks. Observed on V3 API Mgmt → Branch Teller (Seg 1 length 57, would-be
        // nudge 95 px).
        //
        // Legitimate "body" runs — V7 Retail Customer → Account Holder horizontal
        // run (length 480), V7 BE→RelMgr body (length 310), V4 middleware columns
        // (hundreds of px) — all exceed this threshold and remain nudge candidates.
        int parallelLength = run.parEnd - run.parStart;
        if (parallelLength < MIN_INTERIOR_SEGMENT_LENGTH_PX) {
            if (diagnosticEnabled()) {
                logger.info("Skip short segment route={} axis={} "
                        + "segLength={} < MIN_INTERIOR_SEGMENT_LENGTH_PX={}",
                        run.connectionId, run.axis, parallelLength,
                        MIN_INTERIOR_SEGMENT_LENGTH_PX);
            }
            return;
        }

        // Terminal-side-preservation skip (V3 ATM/Contact-Centre fix).
        // A nudge that moves a segment from one side of the source or target perpendicular
        // center to the other flips the adjacent terminal-approach segment's direction,
        // producing a visible direction reversal. Observed on V3 API Mgmt → ATM: horizontal
        // body at y=1308 (below source center y=1268) nudged to y=1253 (above), which flipped
        // the adjacent 40 px DOWN-jog into a 15 px UP-hook.
        //
        // Rule: reject the nudge when the signum of (coord - terminalPerpCoord) differs
        // between pre and post. This is the actual "same-side" invariant: it permits
        // nudges that start or end exactly on the terminal center line (zero-length
        // adjacent jog — a legitimate no-flip degenerate case), and rejects any nudge
        // that would strictly cross the center line to the opposite side.
        int preCoordCheck = run.currentSharedCoord;
        int srcPerp = run.axis == Axis.H ? conn.source().centerY() : conn.source().centerX();
        int tgtPerp = run.axis == Axis.H ? conn.target().centerY() : conn.target().centerX();
        if (crossesCenterLine(preCoordCheck, idealCoord, srcPerp)
                || crossesCenterLine(preCoordCheck, idealCoord, tgtPerp)) {
            if (diagnosticEnabled()) {
                logger.info("Skip terminal-crossing nudge route={} axis={} "
                        + "pre={} post={} srcPerp={} tgtPerp={}",
                        run.connectionId, run.axis, preCoordCheck, idealCoord,
                        srcPerp, tgtPerp);
            }
            return;
        }

        // Snapshot: both bendpoints at bpIdxLo and bpIdxHi.
        AbsoluteBendpointDto snapLo = run.path.get(run.bpIdxLo);
        AbsoluteBendpointDto snapHi = run.path.get(run.bpIdxHi);
        int preCoord = run.currentSharedCoord;

        // Apply nudge: set perpendicular coordinate of both bendpoints to idealCoord.
        AbsoluteBendpointDto newLo;
        AbsoluteBendpointDto newHi;
        if (run.axis == Axis.H) {
            newLo = new AbsoluteBendpointDto(snapLo.x(), idealCoord);
            newHi = new AbsoluteBendpointDto(snapHi.x(), idealCoord);
        } else {
            newLo = new AbsoluteBendpointDto(idealCoord, snapLo.y());
            newHi = new AbsoluteBendpointDto(idealCoord, snapHi.y());
        }
        run.path.set(run.bpIdxLo, newLo);
        run.path.set(run.bpIdxHi, newHi);
        run.currentSharedCoord = idealCoord;

        // Check 1 — terminal alignment invariant.
        if (!preservesTerminalAlignment(run.path,
                conn.source().centerX(), conn.source().centerY(),
                conn.target().centerX(), conn.target().centerY())) {
            rollback(run, snapLo, snapHi, preCoord, "terminal-alignment-violation");
            return;
        }

        // Check 2 — no new obstacle pass-through.
        if (introducesObstaclePassThrough(run.path, conn.source(), conn.target(), allObstacles)) {
            rollback(run, snapLo, snapHi, preCoord, "new-obstacle-violation");
            return;
        }

        // Check 3 — no new coincident-pair with any already-nudged run on the view.
        // Cross-channel check via the global nudgedRunsLog, not just current-channel occupants.
        if (introducesNewCoincidentPair(run)) {
            rollback(run, snapLo, snapHi, preCoord, "new-coincident-pair");
            return;
        }

        // Record allocated track in the registry and in the global nudged-runs log.
        trackRegistry.computeIfAbsent(channel.key, k -> new TreeSet<>()).add(idealCoord);
        nudgedRunsLog.add(new NudgedRun(run.connectionId, run.axis, run.currentSharedCoord,
                run.parStart, run.parEnd));
        nudgeCount++;

        if (diagnosticEnabled()) {
            logger.info("Allocate corridor={}:[{},{}] route={} track={} (was {})",
                    channel.key.axis(), channel.gapLow(), channel.gapHigh(),
                    run.connectionId, idealCoord, preCoord);
        }
    }

    private void rollback(RouteSegmentRun run,
                          AbsoluteBendpointDto snapLo, AbsoluteBendpointDto snapHi,
                          int preCoord, String reason) {
        run.path.set(run.bpIdxLo, snapLo);
        run.path.set(run.bpIdxHi, snapHi);
        run.currentSharedCoord = preCoord;
        rollbackCount++;
        // Per-rollback diagnostic at DEBUG to avoid noise on rollback-heavy views.
        // The run-summary INFO line emitted from run() reports the total rollback count.
        logger.debug("Rollback route={} reason={}", run.connectionId, reason);
    }

    // =====================================================================
    // Post-condition predicates
    // =====================================================================

    /**
     * Returns {@code true} iff the first bendpoint shares at least
     * one coordinate with the source centre AND the last bendpoint shares at least one
     * coordinate with the target centre — i.e., the terminal segment from source-centre
     * to {@code BP[0]} is orthogonal (vertical or horizontal), and the same for
     * {@code BP[last]} to target-centre. Replaces a pre-eligibility skip guard
     * (which excluded 21/22 paths on View 7 widened) with a post-condition assertion.
     *
     * <p>The OR (not XOR) is deliberate: if both coordinates match, {@code BP[0]} is at
     * the source centre and the terminal segment has zero length — still aligned. If
     * neither matches, {@code BP[0]} is diagonally displaced from the source and the
     * terminal segment is diagonal — not aligned.
     *
     * <p>This is the literal implementation of the spec:
     * "path.first().x == source.centerX || path.first().y == source.centerY".
     */
    static boolean preservesTerminalAlignment(List<AbsoluteBendpointDto> path,
                                              int sourceCenterX, int sourceCenterY,
                                              int targetCenterX, int targetCenterY) {
        if (path == null || path.isEmpty()) {
            return true;
        }
        AbsoluteBendpointDto first = path.get(0);
        AbsoluteBendpointDto last = path.get(path.size() - 1);

        boolean firstAligned = (first.x() == sourceCenterX) || (first.y() == sourceCenterY);
        boolean lastAligned  = (last.x()  == targetCenterX) || (last.y()  == targetCenterY);

        return firstAligned && lastAligned;
    }

    /**
     * Checks whether any segment of the full routed path (including terminal extensions
     * from source/target element centres) crosses an obstacle rectangle.
     */
    static boolean introducesObstaclePassThrough(List<AbsoluteBendpointDto> path,
                                                 RoutingRect source, RoutingRect target,
                                                 List<RoutingRect> allObstacles) {
        if (path == null || path.size() < 2) {
            return false;
        }
        // Build excluded set: source and target element IDs do not count as obstacles
        // for their own connection (the route legitimately starts and ends in them).
        Set<String> excludeIds = new HashSet<>();
        if (source != null && source.id() != null) excludeIds.add(source.id());
        if (target != null && target.id() != null) excludeIds.add(target.id());

        // Iterate stored-bp-internal segments (not terminal extensions — terminal alignment
        // is checked separately). For each segment, test against every obstacle with an
        // AABB early-exit to skip obstacles whose bounding box does not overlap the
        // segment's own bounding box (M5 perf fix).
        for (int i = 0; i < path.size() - 1; i++) {
            AbsoluteBendpointDto a = path.get(i);
            AbsoluteBendpointDto b = path.get(i + 1);
            int segXLo = Math.min(a.x(), b.x());
            int segXHi = Math.max(a.x(), b.x());
            int segYLo = Math.min(a.y(), b.y());
            int segYHi = Math.max(a.y(), b.y());
            for (RoutingRect obs : allObstacles) {
                if (obs.id() != null && excludeIds.contains(obs.id())) {
                    continue;
                }
                // AABB early-exit: if the obstacle's bounding box cannot overlap the
                // segment's bounding box, skip the full intersection test.
                int obsXLo = obs.x();
                int obsYLo = obs.y();
                int obsXHi = obsXLo + obs.width();
                int obsYHi = obsYLo + obs.height();
                if (segXHi < obsXLo || segXLo > obsXHi
                        || segYHi < obsYLo || segYLo > obsYHi) {
                    continue;
                }
                if (segmentIntersectsRect(a.x(), a.y(), b.x(), b.y(), obs)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks whether this run would coincident-pair with any already-nudged run on the
     * view — <em>across all channels</em>, not just the current one. Two runs form a
     * coincident pair iff they share the same axis + sharedCoord and their parallel
     * ranges overlap.
     *
     * <p>Widened: the check was originally scoped to
     * {@code channel.occupants}, which missed the case where two runs from different
     * obstacle-bounded channels ended up at the same post-nudge coordinate because
     * their channel midpoints collided. The check is now against the global
     * {@link #nudgedRunsLog} populated during the current pass.
     */
    private boolean introducesNewCoincidentPair(RouteSegmentRun thisRun) {
        for (NudgedRun other : nudgedRunsLog) {
            if (other.connectionId().equals(thisRun.connectionId)) continue;
            if (other.axis() != thisRun.axis) continue;
            if (other.sharedCoord() != thisRun.currentSharedCoord) continue;
            if (rangesOverlap(thisRun.parStart, thisRun.parEnd,
                              other.parStart(), other.parEnd())) {
                return true;
            }
        }
        return false;
    }

    private static boolean rangesOverlap(int aLo, int aHi, int bLo, int bHi) {
        return aLo < bHi && bLo < aHi;
    }

    /**
     * Returns true iff a nudge from {@code pre} to {@code post} strictly crosses the
     * {@code center} line — i.e. the segment's coordinate is on one side of {@code center}
     * before the nudge and on the <em>opposite</em> side afterward. Nudges that touch
     * {@code center} from one side (e.g. pre = center or post = center) do NOT count as
     * crossings: those are "degenerate no-flip" cases where the adjacent terminal jog
     * shrinks to zero length but does not reverse direction.
     *
     * <p>Mathematically: {@code signum(pre - center) != signum(post - center)} and both
     * signum values are non-zero.
     */
    static boolean crossesCenterLine(int pre, int post, int center) {
        int sPre = Integer.signum(pre - center);
        int sPost = Integer.signum(post - center);
        return sPre != 0 && sPost != 0 && sPre != sPost;
    }

    /**
     * Axis-aligned segment-vs-rectangle intersection test. Segments are assumed
     * horizontal or vertical (this pass never nudges diagonals).
     */
    static boolean segmentIntersectsRect(int x1, int y1, int x2, int y2, RoutingRect rect) {
        int rxLo = rect.x();
        int ryLo = rect.y();
        int rxHi = rect.x() + rect.width();
        int ryHi = rect.y() + rect.height();

        if (y1 == y2) {
            // Horizontal segment at y=y1, x in [min(x1,x2), max(x1,x2)]
            int sxLo = Math.min(x1, x2);
            int sxHi = Math.max(x1, x2);
            return y1 > ryLo && y1 < ryHi && sxHi > rxLo && sxLo < rxHi;
        }
        if (x1 == x2) {
            // Vertical segment at x=x1, y in [min(y1,y2), max(y1,y2)]
            int syLo = Math.min(y1, y2);
            int syHi = Math.max(y1, y2);
            return x1 > rxLo && x1 < rxHi && syHi > ryLo && syLo < ryHi;
        }
        // Diagonal segment — shouldn't happen post-orthogonality enforcement.
        return false;
    }

    private static boolean diagnosticEnabled() {
        return Boolean.getBoolean(DIAGNOSTIC_PROPERTY);
    }
}
