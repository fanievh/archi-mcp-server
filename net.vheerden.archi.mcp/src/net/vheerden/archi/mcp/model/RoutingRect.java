package net.vheerden.archi.mcp.model;

/**
 * Lightweight rectangle in absolute canvas coordinates for connection routing.
 * Public to allow access from {@code model.routing} subpackage.
 *
 * @param x      left edge (absolute canvas)
 * @param y      top edge (absolute canvas)
 * @param width  width in pixels
 * @param height height in pixels
 * @param id     optional identifier for tracing (e.g., view object ID); may be null
 */
public record RoutingRect(int x, int y, int width, int height, String id) {

    public int centerX() {
        return x + width / 2;
    }

    public int centerY() {
        return y + height / 2;
    }
}
