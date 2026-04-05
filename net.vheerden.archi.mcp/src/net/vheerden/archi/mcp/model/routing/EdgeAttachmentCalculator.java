package net.vheerden.archi.mcp.model.routing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.vheerden.archi.mcp.model.geometry.GeometryUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.vheerden.archi.mcp.model.RoutingRect;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Computes edge attachment points for connections at element perimeters (Story 10-16).
 * Pure-geometry class — no EMF/SWT dependencies.
 *
 * <p>After A* routing, path ordering, and edge nudging produce intermediate bendpoints,
 * this class adds terminal bendpoints at element faces. This ensures connections arrive
 * perpendicular to the element edge, eliminating "last-mile" diagonal micro-segments
 * caused by ChopboxAnchor clipping.</p>
 */
public class EdgeAttachmentCalculator {

    private static final Logger logger = LoggerFactory.getLogger(EdgeAttachmentCalculator.class);

    /** The four faces of a rectangle where connections can attach. */
    public enum Face {
        TOP, BOTTOM, LEFT, RIGHT
    }

    static final int DEFAULT_CORNER_MARGIN = 5;
    static final int DEFAULT_MIN_SPACING = 8;

    /** Hub threshold: elements with this many or more connections participate in face redistribution. */
    static final int DEFAULT_HUB_THRESHOLD = 6;

    /** Trigger redistribution when a single face has more than this ratio of an element's connections. */
    static final double MAX_FACE_LOAD_RATIO = 0.60;

    /** Distance outside element for redirect bendpoints during hub redistribution. */
    static final int REDIRECT_MARGIN = 12;

    private final int cornerMargin;
    private final int minSpacing;
    private final int hubThreshold;

    public EdgeAttachmentCalculator() {
        this(DEFAULT_CORNER_MARGIN, DEFAULT_MIN_SPACING, DEFAULT_HUB_THRESHOLD);
    }

    EdgeAttachmentCalculator(int cornerMargin, int minSpacing) {
        this(cornerMargin, minSpacing, DEFAULT_HUB_THRESHOLD);
    }

    EdgeAttachmentCalculator(int cornerMargin, int minSpacing, int hubThreshold) {
        this.cornerMargin = cornerMargin;
        this.minSpacing = minSpacing;
        this.hubThreshold = hubThreshold;
    }

    /**
     * Determines which face of an element a bendpoint approaches from,
     * based on which axis-aligned edge is closest to the bendpoint.
     *
     * @param element    the element rectangle
     * @param bendpointX x coordinate of the approaching bendpoint
     * @param bendpointY y coordinate of the approaching bendpoint
     * @return the face the bendpoint approaches
     */
    public static Face determineFace(RoutingRect element, int bendpointX, int bendpointY) {
        int cx = element.centerX();
        int cy = element.centerY();
        int dx = bendpointX - cx;
        int dy = bendpointY - cy;

        if (Math.abs(dx) > Math.abs(dy)) {
            // Horizontal approach
            return dx < 0 ? Face.LEFT : Face.RIGHT;
        } else {
            // Vertical approach (tie-breaks to vertical)
            return dy < 0 ? Face.TOP : Face.BOTTOM;
        }
    }

    /**
     * Computes an attachment point on an element face, distributed across the face
     * when multiple connections share it.
     *
     * @param element     the element rectangle
     * @param face        which face to attach to
     * @param index       0-based index of this connection on the face
     * @param totalOnFace total number of connections on this face
     * @return [x, y] coordinates on the element edge
     */
    public int[] computeAttachmentPoint(RoutingRect element, Face face, int index, int totalOnFace) {
        int x = element.x();
        int y = element.y();
        int w = element.width();
        int h = element.height();

        switch (face) {
            case TOP: {
                int attachY = y - 1;
                int attachX = computeDistributedPosition(x, w, index, totalOnFace);
                return new int[]{attachX, attachY};
            }
            case BOTTOM: {
                int attachY = y + h + 1;
                int attachX = computeDistributedPosition(x, w, index, totalOnFace);
                return new int[]{attachX, attachY};
            }
            case LEFT: {
                int attachX = x - 1;
                int attachY = computeDistributedPosition(y, h, index, totalOnFace);
                return new int[]{attachX, attachY};
            }
            case RIGHT: {
                int attachX = x + w + 1;
                int attachY = computeDistributedPosition(y, h, index, totalOnFace);
                return new int[]{attachX, attachY};
            }
            default:
                throw new IllegalArgumentException("Unknown face: " + face);
        }
    }

    /**
     * Distributes a position along a face edge.
     * For a single connection, returns the midpoint.
     * For multiple, distributes evenly with corner margin.
     */
    private int computeDistributedPosition(int edgeStart, int edgeLength, int index, int total) {
        if (total <= 1) {
            return edgeStart + edgeLength / 2;
        }
        // Usable range after corner margins
        int usableStart = edgeStart + cornerMargin;
        int usableLength = edgeLength - 2 * cornerMargin;
        if (usableLength <= 0) {
            usableStart = edgeStart;
            usableLength = edgeLength;
        }
        double spacing = (double) usableLength / (total + 1);
        if (spacing < minSpacing) {
            // Enforce minimum spacing: center the group with minSpacing gaps
            double neededWidth = (double) minSpacing * (total - 1);
            if (neededWidth <= edgeLength) {
                double center = edgeStart + edgeLength / 2.0;
                double groupStart = center - neededWidth / 2.0;
                return (int) Math.round(groupStart + (double) minSpacing * index);
            }
            // Face too narrow even for minSpacing — distribute evenly across full width
            spacing = (double) edgeLength / (total + 1);
            return (int) Math.round(edgeStart + spacing * (index + 1));
        }
        return (int) Math.round(usableStart + spacing * (index + 1));
    }

    /**
     * Applies edge attachment terminal bendpoints to all routed connections.
     *
     * <p>For each connection, prepends a source-face terminal bendpoint and appends
     * a target-face terminal bendpoint. When multiple connections share a face,
     * attachment points are distributed across the face.</p>
     *
     * @param connectionIds  connection identifiers
     * @param bendpointLists mutable bendpoint lists per connection (modified in place)
     * @param connections    connection endpoint data for source/target rectangles
     */
    public void applyEdgeAttachments(
            List<String> connectionIds,
            List<List<AbsoluteBendpointDto>> bendpointLists,
            List<RoutingPipeline.ConnectionEndpoints> connections) {

        logger.info("Applying edge attachments to {} connections", connectionIds.size());

        // Phase 1: Determine faces for all connections
        Face[] sourceFaces = new Face[connectionIds.size()];
        Face[] targetFaces = new Face[connectionIds.size()];

        for (int i = 0; i < connectionIds.size(); i++) {
            List<AbsoluteBendpointDto> bendpoints = bendpointLists.get(i);
            RoutingPipeline.ConnectionEndpoints conn = connections.get(i);
            RoutingRect source = conn.source();
            RoutingRect target = conn.target();

            // Determine source exit face
            if (!bendpoints.isEmpty()) {
                AbsoluteBendpointDto firstBp = bendpoints.get(0);
                sourceFaces[i] = determineFace(source, firstBp.x(), firstBp.y());
            } else {
                sourceFaces[i] = determineFace(source, target.centerX(), target.centerY());
            }

            // Determine target entry face
            if (!bendpoints.isEmpty()) {
                AbsoluteBendpointDto lastBp = bendpoints.get(bendpoints.size() - 1);
                targetFaces[i] = determineFace(target, lastBp.x(), lastBp.y());
            } else {
                targetFaces[i] = determineFace(target, source.centerX(), source.centerY());
            }
        }

        // Phase 1.1: Face load balancing for hub elements (Story backlog-b9)
        redistributeHubFaces(connectionIds, bendpointLists, connections, sourceFaces, targetFaces);

        // Phase 1.2: Natural approach direction correction (Stories backlog-b32, backlog-b46)
        // When source and target are aligned on one axis (dominant axis > 1.2x minor axis),
        // prefer the natural entry direction over the bendpoint-derived face.
        // Hub elements are skipped for weak alignments (1.2:1-2:1) but corrected for strong (2:1+).
        correctApproachDirection(connectionIds, connections, sourceFaces, targetFaces);

        // Phase 1.3: Self-element pass-through face validation (Story backlog-b35)
        // ORDERING CONSTRAINT: Must run AFTER Phase 1.1 (hub) and 1.2 (approach) which may
        // change faces, and BEFORE Phase 1.5 (face groups) which consumes the final faces.
        // After all face adjustments (hub redistribution, natural approach), validate that
        // the assigned faces don't cause the path to clip through its own source/target element.
        validateFacesForSelfPassThrough(connectionIds, bendpointLists, connections,
                sourceFaces, targetFaces);

        // Phase 1.4: Re-apply strong-alignment face corrections after B35 (Story backlog-b46)
        // TECH DEBT: This is a workaround for B35's hasSelfPassThrough() using trial paths that lack
        // ensurePerpendicularSegment alignment BPs, causing false-positive pass-through detection.
        // The proper fix is to make B35's trial path include alignment BPs. Until then, Phase 1.4
        // re-corrects only strong-alignment hub connections that B35 falsely reverted.
        // Only strong alignments (2:1+) are re-corrected — weaker B46 corrections stay reverted.
        correctApproachDirection(connectionIds, connections, sourceFaces, targetFaces, true);

        // Phase 1.5: Build unified face groups (Story 10-28)
        // Key: elementId + "|" + face → list of connection indices (both inbound and outbound)
        Map<String, List<Integer>> unifiedFaceGroups = new HashMap<>();

        for (int i = 0; i < connectionIds.size(); i++) {
            RoutingPipeline.ConnectionEndpoints conn = connections.get(i);

            String sourceKey = conn.source().id() + "|" + sourceFaces[i];
            unifiedFaceGroups.computeIfAbsent(sourceKey, k -> new ArrayList<>()).add(i);

            String targetKey = conn.target().id() + "|" + targetFaces[i];
            unifiedFaceGroups.computeIfAbsent(targetKey, k -> new ArrayList<>()).add(i);
        }

        // Phase 2: Sort unified face groups by perpendicular approach coordinate
        // and build index map for O(1) lookup in Phase 3
        Map<String, Map<Integer, Integer>> faceIndexMaps = new HashMap<>();
        for (Map.Entry<String, List<Integer>> entry : unifiedFaceGroups.entrySet()) {
            String key = entry.getKey();
            List<Integer> indices = entry.getValue();
            if (indices.size() > 1) {
                // Determine if each connection is source or target for this element+face
                String elementId = key.substring(0, key.lastIndexOf('|'));
                indices.sort((a, b) -> {
                    int coordA = getUnifiedApproachCoordinate(a, elementId, bendpointLists, connections,
                            sourceFaces, targetFaces);
                    int coordB = getUnifiedApproachCoordinate(b, elementId, bendpointLists, connections,
                            sourceFaces, targetFaces);
                    return Integer.compare(coordA, coordB);
                });
            }
            // Build connectionIndex → position map
            Map<Integer, Integer> indexMap = new HashMap<>();
            for (int pos = 0; pos < indices.size(); pos++) {
                indexMap.put(indices.get(pos), pos);
            }
            faceIndexMaps.put(key, indexMap);
        }

        // Phase 3: Apply terminal bendpoints
        for (int i = 0; i < connectionIds.size(); i++) {
            List<AbsoluteBendpointDto> bendpoints = bendpointLists.get(i);
            RoutingPipeline.ConnectionEndpoints conn = connections.get(i);

            // Source attachment — look up from unified group for source element+face
            String sourceKey = conn.source().id() + "|" + sourceFaces[i];
            List<Integer> sourceGroup = unifiedFaceGroups.get(sourceKey);
            int sourceIndex = faceIndexMaps.get(sourceKey).get(i);
            int[] sourceAttach = computeAttachmentPoint(
                    conn.source(), sourceFaces[i], sourceIndex, sourceGroup.size());

            // Target attachment — look up from unified group for target element+face
            String targetKey = conn.target().id() + "|" + targetFaces[i];
            List<Integer> targetGroup = unifiedFaceGroups.get(targetKey);
            int targetIndex = faceIndexMaps.get(targetKey).get(i);
            int[] targetAttach = computeAttachmentPoint(
                    conn.target(), targetFaces[i], targetIndex, targetGroup.size());

            // Prepend source terminal bendpoint
            bendpoints.add(0, new AbsoluteBendpointDto(sourceAttach[0], sourceAttach[1]));
            // Append target terminal bendpoint
            bendpoints.add(new AbsoluteBendpointDto(targetAttach[0], targetAttach[1]));

            // Ensure perpendicular source exit segment
            if (bendpoints.size() >= 2) {
                ensurePerpendicularSegment(bendpoints, 0, 1, sourceFaces[i], true,
                        conn.obstacles());
            }
            // Ensure perpendicular target entry segment
            if (bendpoints.size() >= 2) {
                ensurePerpendicularSegment(bendpoints, bendpoints.size() - 1,
                        bendpoints.size() - 2, targetFaces[i], false,
                        conn.obstacles());
            }

            logger.debug("Connection {}: source face={} attach=({},{}), target face={} attach=({},{})",
                    connectionIds.get(i), sourceFaces[i], sourceAttach[0], sourceAttach[1],
                    targetFaces[i], targetAttach[0], targetAttach[1]);
        }
    }

    /** Offset steps tried when the original alignment is blocked by an obstacle.
     *  Extended range (B28): ±48, ±64, ±96 provide wider search before forced fallback. */
    private static final int[] ALTERNATIVE_OFFSETS = {8, -8, 16, -16, 24, -24, 32, -32,
            48, -48, 64, -64, 96, -96};

    /**
     * Ensures the segment between a terminal and its adjacent bendpoint is perpendicular
     * to the terminal's face. Inserts an alignment bendpoint if needed to create
     * an L-shaped approach. When the original alignment is blocked by an obstacle,
     * tries alternative offsets (±8 through ±96) before force-inserting at offset=0.
     *
     * <p><b>B28 change:</b> No longer silently skips alignment when all offsets are blocked.
     * Instead, force-inserts the alignment at offset=0. A perpendicular segment that crosses
     * an obstacle is preferable to a diagonal — downstream stages
     * ({@code removeObstacleViolations}, {@code enforceSegmentClearance}) will correct
     * the crossing.</p>
     *
     * <p><b>Note:</b> Alternative offsets shift the perpendicular coordinate, producing
     * near-perpendicular segments (e.g. 8px drift over ~100px = ~4.5° deviation).
     * This is a deliberate trade-off: a near-perpendicular approach that avoids obstacles
     * is preferred over skipping alignment entirely. Shifted alignment points are not
     * bounds-checked against source/target elements (which are excluded from the
     * obstacle list), so in rare cases the alignment may land near an element edge.</p>
     */
    void ensurePerpendicularSegment(List<AbsoluteBendpointDto> bendpoints,
            int terminalIndex, int adjacentIndex, Face face, boolean isSource,
            List<RoutingRect> obstacles) {
        AbsoluteBendpointDto terminal = bendpoints.get(terminalIndex);
        AbsoluteBendpointDto adjacent = bendpoints.get(adjacentIndex);

        boolean needsAlignment;
        AbsoluteBendpointDto alignment;

        if (face == Face.TOP || face == Face.BOTTOM) {
            // Perpendicular approach is vertical → need same X
            needsAlignment = terminal.x() != adjacent.x();
            alignment = new AbsoluteBendpointDto(terminal.x(), adjacent.y());
        } else {
            // Perpendicular approach is horizontal → need same Y
            needsAlignment = terminal.y() != adjacent.y();
            alignment = new AbsoluteBendpointDto(adjacent.x(), terminal.y());
        }

        if (needsAlignment) {
            // Try original alignment first (offset = 0)
            AbsoluteBendpointDto bestAlignment = alignment;
            boolean found = !isAlignmentBlocked(terminal, alignment, adjacent, obstacles);

            if (!found) {
                // Try alternative offsets: ±8 through ±96
                for (int offset : ALTERNATIVE_OFFSETS) {
                    AbsoluteBendpointDto alt;
                    if (face == Face.TOP || face == Face.BOTTOM) {
                        alt = new AbsoluteBendpointDto(alignment.x() + offset, alignment.y());
                    } else {
                        alt = new AbsoluteBendpointDto(alignment.x(), alignment.y() + offset);
                    }
                    if (!isAlignmentBlocked(terminal, alt, adjacent, obstacles)) {
                        bestAlignment = alt;
                        found = true;
                        logger.debug("Using alternative alignment offset {} at ({},{})",
                                offset, alt.x(), alt.y());
                        break;
                    }
                }
            }

            if (!found) {
                // B28: Force-insert at original alignment (offset=0) — downstream stages
                // (removeObstacleViolations, enforceSegmentClearance) will handle any
                // resulting obstacle crossing. A perpendicular segment that crosses
                // is better than a diagonal segment.
                bestAlignment = alignment;
                logger.debug("Force-inserting alignment at ({},{}) — all {} offsets blocked, "
                        + "relying on downstream obstacle correction",
                        alignment.x(), alignment.y(), ALTERNATIVE_OFFSETS.length);
            }
            // Always insert alignment (either found clear offset or forced)
            if (isSource) {
                bendpoints.add(terminalIndex + 1, bestAlignment);
            } else {
                bendpoints.add(terminalIndex, bestAlignment);
            }
        }
    }

    /**
     * Checks whether an alignment bendpoint creates segments that intersect any obstacle.
     * Tests both the terminal→alignment and alignment→adjacent segments.
     */
    private boolean isAlignmentBlocked(AbsoluteBendpointDto terminal,
            AbsoluteBendpointDto alignment, AbsoluteBendpointDto adjacent,
            List<RoutingRect> obstacles) {
        for (RoutingRect obs : obstacles) {
            if (lineSegmentIntersectsRect(
                    terminal.x(), terminal.y(), alignment.x(), alignment.y(),
                    obs.x(), obs.y(), obs.width(), obs.height())) {
                return true;
            }
            if (lineSegmentIntersectsRect(
                    alignment.x(), alignment.y(), adjacent.x(), adjacent.y(),
                    obs.x(), obs.y(), obs.width(), obs.height())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tests if a line segment intersects an axis-aligned rectangle.
     * Delegates to {@link GeometryUtils#lineSegmentIntersectsRect(int, int, int, int, int, int, int, int)}.
     */
    static boolean lineSegmentIntersectsRect(int x1, int y1, int x2, int y2,
                                              int rx, int ry, int rw, int rh) {
        return GeometryUtils.lineSegmentIntersectsRect(x1, y1, x2, y2, rx, ry, rw, rh);
    }

    /**
     * Gets the approach coordinate for a connection in a unified face group.
     * Determines whether the connection uses this element as source or target
     * based on the elementId, then returns the perpendicular approach coordinate.
     */
    private int getUnifiedApproachCoordinate(int connectionIndex, String elementId,
            List<List<AbsoluteBendpointDto>> bendpointLists,
            List<RoutingPipeline.ConnectionEndpoints> connections,
            Face[] sourceFaces, Face[] targetFaces) {
        List<AbsoluteBendpointDto> bendpoints = bendpointLists.get(connectionIndex);
        RoutingPipeline.ConnectionEndpoints conn = connections.get(connectionIndex);

        boolean isSource = elementId.equals(conn.source().id());
        Face face = isSource ? sourceFaces[connectionIndex] : targetFaces[connectionIndex];

        if (isSource) {
            if (!bendpoints.isEmpty()) {
                AbsoluteBendpointDto first = bendpoints.get(0);
                return (face == Face.TOP || face == Face.BOTTOM) ? first.x() : first.y();
            }
            return (face == Face.TOP || face == Face.BOTTOM)
                    ? conn.target().centerX() : conn.target().centerY();
        } else {
            if (!bendpoints.isEmpty()) {
                AbsoluteBendpointDto last = bendpoints.get(bendpoints.size() - 1);
                return (face == Face.TOP || face == Face.BOTTOM) ? last.x() : last.y();
            }
            return (face == Face.TOP || face == Face.BOTTOM)
                    ? conn.source().centerX() : conn.source().centerY();
        }
    }

    // =========================================================================
    // Phase 1.1: Hub port distribution (Story backlog-b9)
    // =========================================================================

    /**
     * Redistributes connections on overloaded faces of hub elements to adjacent faces.
     * Only elements with {@code hubThreshold} or more total connections participate.
     * For each overloaded face (>60% of element's connections), excess connections
     * are moved to adjacent faces based on their approach angle quadrant.
     * Tracks dynamic face counts to prevent target faces from becoming overloaded.
     */
    private void redistributeHubFaces(
            List<String> connectionIds,
            List<List<AbsoluteBendpointDto>> bendpointLists,
            List<RoutingPipeline.ConnectionEndpoints> connections,
            Face[] sourceFaces, Face[] targetFaces) {

        // Count connections per element (both as source and target)
        Map<String, List<int[]>> elementConnections = new HashMap<>();
        // Each int[] = {connectionIndex, 0=source/1=target}

        for (int i = 0; i < connectionIds.size(); i++) {
            RoutingPipeline.ConnectionEndpoints conn = connections.get(i);
            elementConnections.computeIfAbsent(conn.source().id(), k -> new ArrayList<>())
                    .add(new int[]{i, 0});
            elementConnections.computeIfAbsent(conn.target().id(), k -> new ArrayList<>())
                    .add(new int[]{i, 1});
        }

        // Build element lookup map (avoids O(N) scan per hub element)
        Map<String, RoutingRect> elementLookup = new HashMap<>();
        for (RoutingPipeline.ConnectionEndpoints conn : connections) {
            elementLookup.putIfAbsent(conn.source().id(), conn.source());
            elementLookup.putIfAbsent(conn.target().id(), conn.target());
        }

        for (Map.Entry<String, List<int[]>> entry : elementConnections.entrySet()) {
            List<int[]> connRefs = entry.getValue();
            int totalConns = connRefs.size();

            if (totalConns < hubThreshold) {
                continue; // Not a hub element
            }

            // Count connections per face for this element
            Map<Face, List<int[]>> faceLoads = new HashMap<>();
            for (Face f : Face.values()) {
                faceLoads.put(f, new ArrayList<>());
            }

            for (int[] ref : connRefs) {
                int connIdx = ref[0];
                boolean isSource = ref[1] == 0;
                Face face = isSource ? sourceFaces[connIdx] : targetFaces[connIdx];
                faceLoads.get(face).add(ref);
            }

            // Track dynamic face counts for capacity checking
            Map<Face, Integer> dynamicCounts = new HashMap<>();
            for (Face f : Face.values()) {
                dynamicCounts.put(f, faceLoads.get(f).size());
            }

            // Count active faces (faces with at least one connection)
            int activeFaces = 0;
            for (List<int[]> fl : faceLoads.values()) {
                if (!fl.isEmpty()) activeFaces++;
            }
            if (activeFaces < 2) {
                // Only one face used — need at least 2 to redistribute
                activeFaces = 2;
            }

            int maxPerFace = (int) Math.ceil((double) totalConns / activeFaces);

            String elementId = entry.getKey();
            RoutingRect element = elementLookup.get(elementId);
            if (element == null) continue;

            // Check each face for overload
            for (Face face : Face.values()) {
                List<int[]> faceConns = faceLoads.get(face);
                if (faceConns.size() <= maxPerFace) continue;
                if ((double) faceConns.size() / totalConns <= MAX_FACE_LOAD_RATIO) continue;

                int excess = faceConns.size() - maxPerFace;
                if (excess <= 0) continue;

                // Sort by distance from face midpoint (farthest first = best candidates to move)
                sortByDistanceFromFaceMidpoint(faceConns, face, element,
                        bendpointLists, connections, sourceFaces, targetFaces);

                // Redistribute the farthest connections to adjacent faces
                int alternateCounter = 0;
                for (int m = 0; m < excess && m < faceConns.size(); m++) {
                    int[] ref = faceConns.get(m);
                    int connIdx = ref[0];
                    boolean isSource = ref[1] == 0;

                    Face newFace = selectAdjacentFace(face, connIdx, isSource,
                            bendpointLists, connections, alternateCounter);

                    if (newFace == face) continue; // No suitable adjacent face

                    // Check target face capacity before redistribution
                    if (dynamicCounts.get(newFace) >= maxPerFace) continue;

                    // Try redirect with obstacle check — skip if blocked
                    boolean redirected = insertRedirectBendpoint(connIdx, isSource, face, newFace,
                            element, bendpointLists, connections);

                    if (!redirected) continue;

                    // Apply reassignment
                    if (isSource) {
                        sourceFaces[connIdx] = newFace;
                    } else {
                        targetFaces[connIdx] = newFace;
                    }

                    // Update dynamic counts
                    dynamicCounts.merge(face, -1, Integer::sum);
                    dynamicCounts.merge(newFace, 1, Integer::sum);

                    logger.debug("Hub redistribution: conn {} moved from {} to {} on element {}",
                            connectionIds.get(connIdx), face, newFace, elementId);

                    alternateCounter++;
                }
            }
        }
    }

    /**
     * Corrects terminal approach direction when it contradicts the natural source-to-target
     * alignment. When elements are aligned on one axis (dominant axis offset > 1.2x
     * minor axis offset), the approach should use the dominant axis direction.
     * B46 relaxed the threshold from 2:1 to 1.2:1 to handle diagonal-gap cases.
     *
     * <p>For example, if source is nearly directly above target (large dy, small dx),
     * the target should be entered from TOP, not from LEFT/RIGHT — even if the A* path's
     * last bendpoint happens to approach from the side.
     *
     * <p>Hub elements (≥hubThreshold connections) are skipped for weak alignments (1.2:1 to 2:1)
     * to preserve hub redistribution. For strong alignments (2:1+), hubs are still corrected
     * because the wrong face is visually jarring at high ratios.
     *
     * @param connectionIds  connection IDs for logging
     * @param connections    connection endpoints
     * @param sourceFaces    source face array (modified in place)
     * @param targetFaces    target face array (modified in place)
     */
    void correctApproachDirection(
            List<String> connectionIds,
            List<RoutingPipeline.ConnectionEndpoints> connections,
            Face[] sourceFaces, Face[] targetFaces) {
        correctApproachDirection(connectionIds, connections, sourceFaces, targetFaces, false);
    }

    /**
     * @param strongHubOnly when true, only correct hub elements with strong alignment (2:1+).
     *                      Used by Phase 1.4 to re-apply hub corrections that B35 falsely reverted.
     *                      Non-hub corrections reverted by B35 are kept because B35's pass-through
     *                      detection is accurate for non-hub paths.
     */
    void correctApproachDirection(
            List<String> connectionIds,
            List<RoutingPipeline.ConnectionEndpoints> connections,
            Face[] sourceFaces, Face[] targetFaces, boolean strongHubOnly) {

        // Count connections per element to identify hubs
        Map<String, Integer> elementConnectionCounts = new HashMap<>();
        for (RoutingPipeline.ConnectionEndpoints conn : connections) {
            elementConnectionCounts.merge(conn.source().id(), 1, Integer::sum);
            elementConnectionCounts.merge(conn.target().id(), 1, Integer::sum);
        }

        int corrections = 0;
        for (int i = 0; i < connections.size(); i++) {
            RoutingPipeline.ConnectionEndpoints conn = connections.get(i);
            RoutingRect source = conn.source();
            RoutingRect target = conn.target();

            int dx = Math.abs(target.centerX() - source.centerX());
            int dy = Math.abs(target.centerY() - source.centerY());

            // Skip if not aligned on one axis (need 6:5 = 1.2:1 ratio minimum)
            // B46: relaxed from 2:1 to catch diagonal-gap cases where one axis
            // dominates by 20%+ but the old threshold skipped correction entirely
            if (5 * dy <= 6 * dx && 5 * dx <= 6 * dy) {
                continue;
            }

            // Note: "nearlyVertical" includes the B46 diagonal-gap range (1.2:1+), not just B32's 2:1+
            boolean nearlyVertical = 5 * dy > 6 * dx;
            // B46: distinguish diagonal-gap corrections (1.2:1 to 2:1) from B32 strong corrections (2:1+)
            boolean strongAlignment = nearlyVertical ? dy > 2 * dx : dx > 2 * dy;

            // Phase 1.4 only re-corrects strong-alignment hub connections
            if (strongHubOnly && !strongAlignment) {
                continue;
            }

            String logPrefix = strongAlignment ? "B32" : "B46";

            // Correct target face if it contradicts natural alignment
            // B46: hubs skipped for weak alignments (1.2:1-2:1); corrected for strong (2:1+)
            // Phase 1.4 (strongHubOnly): only re-corrects hub targets with strong alignment
            boolean targetIsHub = elementConnectionCounts.getOrDefault(target.id(), 0) >= hubThreshold;
            boolean shouldCorrectTarget = strongHubOnly
                    ? (targetIsHub && strongAlignment)
                    : (!targetIsHub || strongAlignment);
            if (shouldCorrectTarget) {
                Face naturalTargetFace = getNaturalFace(nearlyVertical, source, target, false);
                if (targetFaces[i] != naturalTargetFace
                        && contradicts(targetFaces[i], nearlyVertical)) {
                    logger.debug("{} correction: conn {} target face {} → {} (natural alignment{})",
                            logPrefix, connectionIds.get(i), targetFaces[i], naturalTargetFace,
                            targetIsHub ? ", hub override" : "");
                    targetFaces[i] = naturalTargetFace;
                    corrections++;
                }
            }

            // Correct source face if it contradicts natural alignment
            // Phase 1.4 (strongHubOnly): only re-corrects hub sources with strong alignment
            boolean sourceIsHub = elementConnectionCounts.getOrDefault(source.id(), 0) >= hubThreshold;
            boolean shouldCorrectSource = strongHubOnly
                    ? (sourceIsHub && strongAlignment)
                    : (!sourceIsHub || strongAlignment);
            if (shouldCorrectSource) {
                Face naturalSourceFace = getNaturalFace(nearlyVertical, source, target, true);
                if (sourceFaces[i] != naturalSourceFace
                        && contradicts(sourceFaces[i], nearlyVertical)) {
                    logger.debug("{} correction: conn {} source face {} → {} (natural alignment{})",
                            logPrefix, connectionIds.get(i), sourceFaces[i], naturalSourceFace,
                            sourceIsHub ? ", hub override" : "");
                    sourceFaces[i] = naturalSourceFace;
                    corrections++;
                }
            }
        }

        if (corrections > 0) {
            logger.info("B32/B46: Corrected {} approach direction(s) to natural alignment{}", corrections,
                    strongHubOnly ? " (hub-only re-pass)" : "");
        }
    }

    /**
     * Returns the natural face for an element given the source-to-target alignment.
     *
     * @param nearlyVertical true if elements are nearly vertically aligned
     * @param source         source element
     * @param target         target element
     * @param isSource       true if computing face for source element, false for target
     * @return the natural face for the alignment direction
     */
    private static Face getNaturalFace(boolean nearlyVertical, RoutingRect source,
            RoutingRect target, boolean isSource) {
        if (nearlyVertical) {
            // Source above target → source exits BOTTOM, target enters TOP
            // Source below target → source exits TOP, target enters BOTTOM
            boolean sourceAbove = source.centerY() < target.centerY();
            if (isSource) {
                return sourceAbove ? Face.BOTTOM : Face.TOP;
            } else {
                return sourceAbove ? Face.TOP : Face.BOTTOM;
            }
        } else {
            // Nearly horizontal: source left of target → source exits RIGHT, target enters LEFT
            boolean sourceLeft = source.centerX() < target.centerX();
            if (isSource) {
                return sourceLeft ? Face.RIGHT : Face.LEFT;
            } else {
                return sourceLeft ? Face.LEFT : Face.RIGHT;
            }
        }
    }

    /**
     * Checks if the current face contradicts the natural alignment axis.
     * A vertical alignment is contradicted by LEFT/RIGHT faces.
     * A horizontal alignment is contradicted by TOP/BOTTOM faces.
     */
    private static boolean contradicts(Face face, boolean nearlyVertical) {
        if (nearlyVertical) {
            return face == Face.LEFT || face == Face.RIGHT;
        } else {
            return face == Face.TOP || face == Face.BOTTOM;
        }
    }

    // =========================================================================
    // Phase 1.3: Self-element pass-through face validation (Story backlog-b35)
    // =========================================================================

    /** Inset distance for self-element pass-through detection (matches RoutingPipeline and assessor). */
    static final int SELF_ELEMENT_INSET = 5;

    /**
     * Validates assigned faces for self-element pass-throughs and reassigns faces when needed.
     * Runs after Phase 1.2 (natural approach direction) and before Phase 1.5 (unified face groups).
     *
     * <p>For each connection, builds a trial full path with the current face assignments and checks
     * if the path clips through its own source or target element. If a pass-through is detected,
     * tries alternative faces in angular proximity order (nearest angle to other element first).
     * If no clean alternative exists, keeps the original face (Phase B in RoutingPipeline catches it).
     */
    void validateFacesForSelfPassThrough(
            List<String> connectionIds,
            List<List<AbsoluteBendpointDto>> bendpointLists,
            List<RoutingPipeline.ConnectionEndpoints> connections,
            Face[] sourceFaces, Face[] targetFaces) {

        int corrections = 0;

        for (int i = 0; i < connectionIds.size(); i++) {
            List<AbsoluteBendpointDto> bendpoints = bendpointLists.get(i);
            RoutingPipeline.ConnectionEndpoints conn = connections.get(i);
            RoutingRect source = conn.source();
            RoutingRect target = conn.target();

            // Check and fix source face
            if (hasSelfPassThrough(bendpoints, source, target, sourceFaces[i], targetFaces[i], true)) {
                Face newFace = findCleanAlternativeFace(bendpoints, source, target,
                        sourceFaces[i], targetFaces[i], true);
                if (newFace != null) {
                    logger.debug("B35 Phase A: conn {} source face {} → {} (self-PT eliminated)",
                            connectionIds.get(i), sourceFaces[i], newFace);
                    sourceFaces[i] = newFace;
                    corrections++;
                }
            }

            // Check and fix target face (using potentially updated source face)
            if (hasSelfPassThrough(bendpoints, source, target, sourceFaces[i], targetFaces[i], false)) {
                Face newFace = findCleanAlternativeFace(bendpoints, source, target,
                        sourceFaces[i], targetFaces[i], false);
                if (newFace != null) {
                    logger.debug("B35 Phase A: conn {} target face {} → {} (self-PT eliminated)",
                            connectionIds.get(i), targetFaces[i], newFace);
                    targetFaces[i] = newFace;
                    corrections++;
                }
            }
        }

        if (corrections > 0) {
            logger.info("B35 Phase A: Corrected {} face(s) to avoid self-element pass-throughs", corrections);
        }
    }

    /**
     * Checks if a trial path with the given faces would cause a self-element pass-through.
     *
     * @param bendpoints  intermediate bendpoints (no terminal BPs yet)
     * @param source      source element rectangle
     * @param target      target element rectangle
     * @param sourceFace  current source face assignment
     * @param targetFace  current target face assignment
     * @param checkSource true to check source element, false for target
     * @return true if a self-element pass-through is detected
     */
    boolean hasSelfPassThrough(List<AbsoluteBendpointDto> bendpoints,
            RoutingRect source, RoutingRect target,
            Face sourceFace, Face targetFace, boolean checkSource) {

        List<int[]> fullPath = buildTrialPath(bendpoints, source, target, sourceFace, targetFace);
        if (fullPath.size() < 3) return false;

        RoutingRect element = checkSource ? source : target;
        int ix = element.x() + SELF_ELEMENT_INSET;
        int iy = element.y() + SELF_ELEMENT_INSET;
        int iw = element.width() - 2 * SELF_ELEMENT_INSET;
        int ih = element.height() - 2 * SELF_ELEMENT_INSET;
        if (iw <= 0 || ih <= 0) return false;

        // Skip first segment for source (sourceCenter→sourceTerminal naturally crosses source)
        // Skip last segment for target (targetTerminal→targetCenter naturally crosses target)
        int start = checkSource ? 1 : 0;
        int end = checkSource ? fullPath.size() - 1 : fullPath.size() - 2;

        for (int s = start; s < end; s++) {
            int[] a = fullPath.get(s);
            int[] b = fullPath.get(s + 1);
            if (lineSegmentIntersectsRect(a[0], a[1], b[0], b[1], ix, iy, iw, ih)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds a trial full path with current face assignments for pass-through checking.
     * Path: [sourceCenter, sourceTerminalBP, ...intermediates..., targetTerminalBP, targetCenter]
     *
     * <p>Trial terminal BPs use face midpoints (index=0, total=1) since the actual
     * distribution across shared faces happens later in Phase 3.
     */
    private List<int[]> buildTrialPath(List<AbsoluteBendpointDto> bendpoints,
            RoutingRect source, RoutingRect target,
            Face sourceFace, Face targetFace) {
        int[] sourceTerminal = computeAttachmentPoint(source, sourceFace, 0, 1);
        int[] targetTerminal = computeAttachmentPoint(target, targetFace, 0, 1);

        List<int[]> path = new ArrayList<>();
        path.add(new int[]{source.centerX(), source.centerY()});
        path.add(sourceTerminal);
        for (AbsoluteBendpointDto bp : bendpoints) {
            path.add(new int[]{bp.x(), bp.y()});
        }
        path.add(targetTerminal);
        path.add(new int[]{target.centerX(), target.centerY()});
        return path;
    }

    /**
     * Finds an alternative face that eliminates a self-element pass-through.
     * Tries faces in angular proximity order (nearest angle to other element first),
     * consistent with {@link RoutingPipeline#calculateAlternativeEdgePorts}.
     *
     * @return the clean alternative face, or null if no alternative eliminates the pass-through
     */
    private Face findCleanAlternativeFace(List<AbsoluteBendpointDto> bendpoints,
            RoutingRect source, RoutingRect target,
            Face currentSourceFace, Face currentTargetFace, boolean fixSource) {

        RoutingRect element = fixSource ? source : target;
        RoutingRect other = fixSource ? target : source;
        Face currentFace = fixSource ? currentSourceFace : currentTargetFace;

        Face[] alternatives = getAlternativeFacesInAngularOrder(element, other, currentFace);

        for (Face candidateFace : alternatives) {
            Face trialSourceFace = fixSource ? candidateFace : currentSourceFace;
            Face trialTargetFace = fixSource ? currentTargetFace : candidateFace;

            if (!hasSelfPassThrough(bendpoints, source, target,
                    trialSourceFace, trialTargetFace, fixSource)) {
                return candidateFace;
            }
        }

        return null; // No clean alternative — Phase B will handle it
    }

    /**
     * Returns alternative faces sorted by angular proximity to the other element.
     * Same ordering logic as {@link RoutingPipeline#calculateAlternativeEdgePorts}:
     * computes angle from element center to other element center, then sorts faces
     * by angular distance from that target angle (nearest first).
     */
    Face[] getAlternativeFacesInAngularOrder(RoutingRect element, RoutingRect other, Face currentFace) {
        int ecx = element.centerX(), ecy = element.centerY();
        int ocx = other.centerX(), ocy = other.centerY();
        double targetAngle = Math.atan2(ocy - ecy, ocx - ecx);

        record FaceWithAngle(Face face, double angleDist) {}
        List<FaceWithAngle> alternatives = new ArrayList<>();

        for (Face f : Face.values()) {
            if (f == currentFace) continue;
            double faceAngle = getFaceAngle(f);
            double diff = Math.abs(faceAngle - targetAngle);
            if (diff > Math.PI) diff = 2 * Math.PI - diff;
            alternatives.add(new FaceWithAngle(f, diff));
        }

        alternatives.sort((a, b) -> Double.compare(a.angleDist(), b.angleDist()));

        Face[] result = new Face[alternatives.size()];
        for (int i = 0; i < alternatives.size(); i++) {
            result[i] = alternatives.get(i).face();
        }
        return result;
    }

    /**
     * Returns the representative angle for a face direction (from element center outward).
     */
    static double getFaceAngle(Face face) {
        switch (face) {
            case RIGHT:  return 0;
            case BOTTOM: return Math.PI / 2;
            case LEFT:   return Math.PI;
            case TOP:    return -Math.PI / 2;
            default:     return 0;
        }
    }

    /**
     * Selects the best adjacent face for redistribution based on the connection's
     * approach angle quadrant. Only 90-degree adjacent faces are considered
     * (never opposite faces — no 180-degree redirects). When the approach is
     * exactly centered (dx==0 or dy==0), alternates between the two adjacent faces.
     */
    private Face selectAdjacentFace(Face currentFace, int connIdx, boolean isSource,
            List<List<AbsoluteBendpointDto>> bendpointLists,
            List<RoutingPipeline.ConnectionEndpoints> connections,
            int alternateCounter) {

        RoutingPipeline.ConnectionEndpoints conn = connections.get(connIdx);
        RoutingRect element = isSource ? conn.source() : conn.target();
        List<AbsoluteBendpointDto> bendpoints = bendpointLists.get(connIdx);

        // Get the approach point (first BP for source, last BP for target)
        int approachX, approachY;
        if (isSource) {
            if (!bendpoints.isEmpty()) {
                approachX = bendpoints.get(0).x();
                approachY = bendpoints.get(0).y();
            } else {
                RoutingRect other = conn.target();
                approachX = other.centerX();
                approachY = other.centerY();
            }
        } else {
            if (!bendpoints.isEmpty()) {
                AbsoluteBendpointDto last = bendpoints.get(bendpoints.size() - 1);
                approachX = last.x();
                approachY = last.y();
            } else {
                RoutingRect other = conn.source();
                approachX = other.centerX();
                approachY = other.centerY();
            }
        }

        int cx = element.centerX();
        int cy = element.centerY();
        int dx = approachX - cx;
        int dy = approachY - cy;

        switch (currentFace) {
            case BOTTOM:
                return dx < 0 ? Face.LEFT : (dx > 0 ? Face.RIGHT :
                        (alternateCounter % 2 == 0 ? Face.LEFT : Face.RIGHT));
            case TOP:
                return dx < 0 ? Face.LEFT : (dx > 0 ? Face.RIGHT :
                        (alternateCounter % 2 == 0 ? Face.LEFT : Face.RIGHT));
            case LEFT:
                return dy < 0 ? Face.TOP : (dy > 0 ? Face.BOTTOM :
                        (alternateCounter % 2 == 0 ? Face.TOP : Face.BOTTOM));
            case RIGHT:
                return dy < 0 ? Face.TOP : (dy > 0 ? Face.BOTTOM :
                        (alternateCounter % 2 == 0 ? Face.TOP : Face.BOTTOM));
            default:
                return currentFace;
        }
    }

    /**
     * Sorts connection references by distance from the face midpoint (farthest first).
     * Connections farthest from the midpoint are the best candidates for redistribution
     * because they are the least "natural" fit for the current face.
     */
    private void sortByDistanceFromFaceMidpoint(List<int[]> faceConns, Face face,
            RoutingRect element,
            List<List<AbsoluteBendpointDto>> bendpointLists,
            List<RoutingPipeline.ConnectionEndpoints> connections,
            Face[] sourceFaces, Face[] targetFaces) {

        int midX = element.centerX();
        int midY = element.centerY();

        faceConns.sort((a, b) -> {
            int distA = getApproachDistance(a, face, midX, midY, bendpointLists, connections);
            int distB = getApproachDistance(b, face, midX, midY, bendpointLists, connections);
            return Integer.compare(distB, distA); // Farthest first
        });
    }

    /**
     * Gets the distance of a connection's approach point from the face midpoint along the face axis.
     */
    private int getApproachDistance(int[] ref, Face face, int midX, int midY,
            List<List<AbsoluteBendpointDto>> bendpointLists,
            List<RoutingPipeline.ConnectionEndpoints> connections) {

        int connIdx = ref[0];
        boolean isSource = ref[1] == 0;
        List<AbsoluteBendpointDto> bendpoints = bendpointLists.get(connIdx);
        RoutingPipeline.ConnectionEndpoints conn = connections.get(connIdx);

        int approachCoord;
        if (face == Face.TOP || face == Face.BOTTOM) {
            // Perpendicular to vertical faces = X coordinate
            if (isSource && !bendpoints.isEmpty()) {
                approachCoord = bendpoints.get(0).x();
            } else if (!isSource && !bendpoints.isEmpty()) {
                approachCoord = bendpoints.get(bendpoints.size() - 1).x();
            } else {
                RoutingRect other = isSource ? conn.target() : conn.source();
                approachCoord = other.centerX();
            }
            return Math.abs(approachCoord - midX);
        } else {
            // Perpendicular to horizontal faces = Y coordinate
            if (isSource && !bendpoints.isEmpty()) {
                approachCoord = bendpoints.get(0).y();
            } else if (!isSource && !bendpoints.isEmpty()) {
                approachCoord = bendpoints.get(bendpoints.size() - 1).y();
            } else {
                RoutingRect other = isSource ? conn.target() : conn.source();
                approachCoord = other.centerY();
            }
            return Math.abs(approachCoord - midY);
        }
    }

    /**
     * Inserts a redirect bendpoint when a connection is redistributed to an adjacent face.
     * Creates an L-shaped approach: the connection continues on its original approach path,
     * then turns 90 degrees to enter the new face perpendicularly.
     * Checks obstacle intersection before inserting — returns false if blocked.
     *
     * @return true if the redirect was inserted, false if an obstacle blocks the redirect segment
     */
    private boolean insertRedirectBendpoint(int connIdx, boolean isSource,
            Face originalFace, Face newFace, RoutingRect element,
            List<List<AbsoluteBendpointDto>> bendpointLists,
            List<RoutingPipeline.ConnectionEndpoints> connections) {

        List<AbsoluteBendpointDto> bendpoints = bendpointLists.get(connIdx);
        if (bendpoints.isEmpty()) return true;

        List<RoutingRect> obstacles = connections.get(connIdx).obstacles();

        int redirectX, redirectY;

        if (isSource) {
            // Source side: redirect is near the first intermediate bendpoint
            AbsoluteBendpointDto firstBp = bendpoints.get(0);

            if (newFace == Face.LEFT || newFace == Face.RIGHT) {
                // Redirecting to a vertical face — redirect X is outside that face
                redirectX = (newFace == Face.RIGHT)
                        ? element.x() + element.width() + REDIRECT_MARGIN
                        : element.x() - REDIRECT_MARGIN;
                redirectY = firstBp.y();
            } else {
                // Redirecting to a horizontal face — redirect Y is outside that face
                redirectX = firstBp.x();
                redirectY = (newFace == Face.BOTTOM)
                        ? element.y() + element.height() + REDIRECT_MARGIN
                        : element.y() - REDIRECT_MARGIN;
            }

            // Check redirect segment against obstacles
            for (RoutingRect obs : obstacles) {
                if (lineSegmentIntersectsRect(
                        firstBp.x(), firstBp.y(), redirectX, redirectY,
                        obs.x(), obs.y(), obs.width(), obs.height())) {
                    logger.debug("Redirect blocked by obstacle at ({},{}) for conn {}",
                            obs.x(), obs.y(), connIdx);
                    return false;
                }
            }

            bendpoints.add(0, new AbsoluteBendpointDto(redirectX, redirectY));
        } else {
            // Target side: redirect is near the last intermediate bendpoint
            AbsoluteBendpointDto lastBp = bendpoints.get(bendpoints.size() - 1);

            if (newFace == Face.LEFT || newFace == Face.RIGHT) {
                redirectX = (newFace == Face.RIGHT)
                        ? element.x() + element.width() + REDIRECT_MARGIN
                        : element.x() - REDIRECT_MARGIN;
                redirectY = lastBp.y();
            } else {
                redirectX = lastBp.x();
                redirectY = (newFace == Face.BOTTOM)
                        ? element.y() + element.height() + REDIRECT_MARGIN
                        : element.y() - REDIRECT_MARGIN;
            }

            // Check redirect segment against obstacles
            for (RoutingRect obs : obstacles) {
                if (lineSegmentIntersectsRect(
                        lastBp.x(), lastBp.y(), redirectX, redirectY,
                        obs.x(), obs.y(), obs.width(), obs.height())) {
                    logger.debug("Redirect blocked by obstacle at ({},{}) for conn {}",
                            obs.x(), obs.y(), connIdx);
                    return false;
                }
            }

            bendpoints.add(new AbsoluteBendpointDto(redirectX, redirectY));
        }
        return true;
    }

}
