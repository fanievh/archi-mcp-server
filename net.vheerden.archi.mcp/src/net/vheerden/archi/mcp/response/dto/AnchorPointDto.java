package net.vheerden.archi.mcp.response.dto;

/**
 * Data Transfer Object for a connection anchor reference point (Story 8-0d).
 *
 * <p>Represents the <strong>center point</strong> of a view object, used as
 * the anchor reference for connection routing calculations. This is the same
 * reference point that Archi's GEF {@code ChopboxAnchor} uses internally to
 * compute the actual perimeter intersection at render time.</p>
 *
 * <p><strong>Important:</strong> These coordinates are NOT where the connection
 * line visually meets the element edge. Archi computes the visual attachment
 * point (bounding-box perimeter intersection) at render time using the
 * {@code ChopboxAnchor} algorithm. The center point is provided because it is
 * the reference needed for the relative bendpoint formula:
 * {@code absoluteX = (sourceCenter.x + startX) * (1 - weight) + weight * (targetCenter.x + endX)}.
 * </p>
 *
 * <p>Coordinates are absolute canvas coordinates. For elements nested inside
 * groups, parent offsets are accumulated to produce absolute positions.
 * (Fixed in Story 10.15 to use absolute canvas space.)</p>
 *
 * <p><strong>Note:</strong> For elements with zero width or height
 * (e.g., collapsed/minimized elements), the "center" will equal
 * the element's origin position since {@code width/2 == 0}.</p>
 */
public record AnchorPointDto(int x, int y) {}
