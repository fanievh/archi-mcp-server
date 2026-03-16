package net.vheerden.archi.mcp.model.routing;

/**
 * Node in the orthogonal visibility graph (Story 10-6a).
 * Pure-geometry record — no EMF dependencies.
 *
 * @param x    x-coordinate in absolute canvas space
 * @param y    y-coordinate in absolute canvas space
 * @param type classification of this node
 */
public record VisNode(int x, int y, NodeType type) {

    public enum NodeType {
        /** Corner of an expanded obstacle rectangle. */
        OBSTACLE_CORNER,
        /** Connection source or target port. */
        PORT,
        /** Intersection of horizontal/vertical scan lines. */
        SCAN_INTERSECTION
    }
}
