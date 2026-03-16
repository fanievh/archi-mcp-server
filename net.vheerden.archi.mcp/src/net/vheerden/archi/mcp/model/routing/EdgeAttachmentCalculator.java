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

    private final int cornerMargin;
    private final int minSpacing;

    public EdgeAttachmentCalculator() {
        this(DEFAULT_CORNER_MARGIN, DEFAULT_MIN_SPACING);
    }

    EdgeAttachmentCalculator(int cornerMargin, int minSpacing) {
        this.cornerMargin = cornerMargin;
        this.minSpacing = minSpacing;
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
    public Face determineFace(RoutingRect element, int bendpointX, int bendpointY) {
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

    /** Offset steps tried when the original alignment is blocked by an obstacle. */
    private static final int[] ALTERNATIVE_OFFSETS = {8, -8, 16, -16, 24, -24, 32, -32};

    /**
     * Ensures the segment between a terminal and its adjacent bendpoint is perpendicular
     * to the terminal's face. Inserts an alignment bendpoint if needed to create
     * an L-shaped approach. When the original alignment is blocked by an obstacle,
     * tries alternative offsets (±8, ±16, ±24, ±32) before falling back to skipping.
     *
     * <p><b>Note:</b> Alternative offsets shift the perpendicular coordinate, producing
     * near-perpendicular segments (e.g. 8px drift over ~100px = ~4.5° deviation).
     * This is a deliberate trade-off: a near-perpendicular approach that avoids obstacles
     * is preferred over skipping alignment entirely. Shifted alignment points are not
     * bounds-checked against source/target elements (which are excluded from the
     * obstacle list), so in rare cases the alignment may land near an element edge.</p>
     */
    private void ensurePerpendicularSegment(List<AbsoluteBendpointDto> bendpoints,
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
                // Try alternative offsets: ±8, ±16, ±24, ±32
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

            if (found) {
                if (isSource) {
                    bendpoints.add(terminalIndex + 1, bestAlignment);
                } else {
                    bendpoints.add(terminalIndex, bestAlignment);
                }
            } else {
                logger.debug("Skipping alignment at ({},{}) — all offsets blocked",
                        alignment.x(), alignment.y());
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
}
