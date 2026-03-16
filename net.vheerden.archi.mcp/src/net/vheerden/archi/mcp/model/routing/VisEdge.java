package net.vheerden.archi.mcp.model.routing;

/**
 * Directed edge in the orthogonal visibility graph (Story 10-6a).
 * Pure-geometry record — no EMF dependencies.
 *
 * @param target    the node this edge leads to
 * @param distance  Euclidean distance (always axis-aligned, so == Manhattan distance)
 * @param direction cardinal direction from source node to target node
 */
public record VisEdge(VisNode target, double distance, Direction direction) {

    public enum Direction {
        UP, DOWN, LEFT, RIGHT
    }
}
