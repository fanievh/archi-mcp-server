package net.vheerden.archi.mcp.model.routing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * Tracks corridor occupancy across sequentially routed connections (B47).
 * Pure-geometry class — no EMF/SWT dependencies.
 *
 * <p>After each connection is routed, its path is recorded. Later connections
 * can query occupancy for any corridor to get a count of how many prior
 * connections already use that corridor. This enables the A* router to
 * apply a multiplicative cost penalty for occupied corridors, encouraging
 * route diversity and reducing coincident segments.</p>
 *
 * <p>Corridor keys use the same format as {@link CoincidentSegmentDetector}
 * and {@link PathOrderer}: {@code "H:y"} for horizontal corridors and
 * {@code "V:x"} for vertical corridors, with tolerance-aware grouping.</p>
 */
public class CorridorOccupancyTracker {

    /** Coordinate tolerance for corridor grouping — matches CoincidentSegmentDetector. */
    static final int COORDINATE_TOLERANCE = 2;

    /** Corridor key → number of paths that use this corridor. */
    private final Map<String, Integer> corridorOccupancy = new HashMap<>();

    /** Total number of paths recorded. */
    private int recordedPathCount = 0;

    /**
     * Records a routed path, incrementing occupancy for each axis-aligned corridor segment.
     * Diagonal segments (non-axis-aligned) are ignored.
     *
     * <p>The full path is reconstructed by prepending the source center and appending
     * the target center to the intermediate bendpoints.</p>
     *
     * @param bendpoints   intermediate bendpoints (excluding source/target centers)
     * @param sourceCenter [x, y] of the source element center
     * @param targetCenter [x, y] of the target element center
     */
    public void recordPath(List<AbsoluteBendpointDto> bendpoints, int[] sourceCenter, int[] targetCenter) {
        // Reconstruct full path: source → bendpoints → target
        List<int[]> fullPath = new ArrayList<>(bendpoints.size() + 2);
        fullPath.add(sourceCenter);
        for (AbsoluteBendpointDto bp : bendpoints) {
            fullPath.add(new int[]{bp.x(), bp.y()});
        }
        fullPath.add(targetCenter);

        // Extract axis-aligned segments and increment corridor occupancy
        for (int i = 0; i < fullPath.size() - 1; i++) {
            int[] from = fullPath.get(i);
            int[] to = fullPath.get(i + 1);

            boolean horizontal = from[1] == to[1];
            boolean vertical = from[0] == to[0];

            if (horizontal && from[0] != to[0]) {
                // Horizontal segment: shared y-coordinate
                String key = resolveCorridorKey(true, from[1]);
                corridorOccupancy.merge(key, 1, Integer::sum);
            } else if (vertical && from[1] != to[1]) {
                // Vertical segment: shared x-coordinate
                String key = resolveCorridorKey(false, from[0]);
                corridorOccupancy.merge(key, 1, Integer::sum);
            }
            // Diagonal segments (neither horizontal nor vertical) are ignored
        }

        recordedPathCount++;
    }

    /**
     * Returns the occupancy count for the corridor containing the given edge.
     * Returns 0 if the edge is diagonal or the corridor has not been used.
     *
     * @param x1 start x
     * @param y1 start y
     * @param x2 end x
     * @param y2 end y
     * @return number of prior paths using this corridor, or 0
     */
    public int getOccupancy(int x1, int y1, int x2, int y2) {
        boolean horizontal = y1 == y2;
        boolean vertical = x1 == x2;

        if (horizontal && x1 != x2) {
            String key = resolveCorridorKey(true, y1);
            return corridorOccupancy.getOrDefault(key, 0);
        } else if (vertical && y1 != y2) {
            String key = resolveCorridorKey(false, x1);
            return corridorOccupancy.getOrDefault(key, 0);
        }

        return 0; // Diagonal or zero-length edge
    }

    /**
     * Resolves a corridor key with tolerance-aware grouping.
     * Matches the pattern in {@link PathOrderer#findOrCreateGroupKey}.
     *
     * @param horizontal    true for horizontal corridors (H:y), false for vertical (V:x)
     * @param sharedCoordinate the shared y (horizontal) or x (vertical) coordinate
     * @return corridor key string, e.g. "H:200" or "V:150"
     */
    String resolveCorridorKey(boolean horizontal, int sharedCoordinate) {
        String prefix = horizontal ? "H:" : "V:";

        for (String key : corridorOccupancy.keySet()) {
            if (!key.startsWith(prefix)) continue;
            int groupCoord = Integer.parseInt(key.substring(2));
            if (Math.abs(sharedCoordinate - groupCoord) <= COORDINATE_TOLERANCE) {
                return key;
            }
        }

        return prefix + sharedCoordinate;
    }

    /**
     * Returns the total number of paths recorded.
     */
    public int getRecordedPathCount() {
        return recordedPathCount;
    }

    /**
     * Returns a copy of the corridor occupancy map (for testing/debugging).
     */
    public Map<String, Integer> getCorridorOccupancy() {
        return new HashMap<>(corridorOccupancy);
    }
}
