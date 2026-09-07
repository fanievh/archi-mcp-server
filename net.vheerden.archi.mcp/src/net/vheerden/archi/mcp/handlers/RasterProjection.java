package net.vheerden.archi.mcp.handlers;

import net.vheerden.archi.mcp.model.ContentBounds;

/**
 * The raster a view export will allocate, computed from the view's content
 * bounds and the requested scale without allocating anything.
 *
 * <p>Pure arithmetic over a value type: no SWT, no EMF, no rendering. That is
 * deliberate. The allocation this guards cannot run outside a display, so
 * placing the arithmetic here — at the handler boundary — is what makes the
 * refusal testable at all.</p>
 *
 * <p><strong>The formula.</strong> Archi's raster renderer expands the diagram's
 * minimum bounds by {@code margin / scale} on every side and then multiplies by
 * the scale, taking the integer part at both steps:</p>
 *
 * <pre>
 *   m = (int)(margin / scale)
 *   W = (int)((contentWidth  + 2m) * scale)
 *   H = (int)((contentHeight + 2m) * scale)
 * </pre>
 *
 * <p>The inner truncation matters: the expansion is applied through an integer
 * overload, so a margin that does not divide the scale evenly loses its
 * fraction before it is ever scaled. Treating the expansion as exact
 * over-states the result by a pixel on some scales and understates the risk of
 * a frame error being mistaken for a rounding one.</p>
 *
 * <p><strong>Where the residual runs.</strong> The bounds this is given exclude
 * notes and cover connections only through their stored waypoints, whereas the
 * renderer measures every printable figure. Across the eleven views of the
 * reference corpus the projection matched the rendered image exactly on eight
 * and fell short on three, by at most 18 pixels on one axis — never over. The
 * error therefore runs toward under-stating the allocation, which is the unsafe
 * direction, and is absorbed by
 * {@link PhysicalMemoryBudget#CONCURRENT_RASTER_COPIES}. The content extent is
 * rounded up here for the same reason.</p>
 */
public record RasterProjection(int width, int height, long bytes) {

    /**
     * Bytes per pixel of the allocated bitmap. Derived from the failure this
     * guard exists to prevent: a 13574 x 13096 render asked the operating
     * system for 711,060,416 bytes, which is exactly four bytes per pixel.
     * Held as a constant rather than read from the display depth — a lower
     * depth would only make the true figure smaller, so four is conservative.
     */
    static final int BYTES_PER_PIXEL = 4;

    /** Margin, in pixels, that the export path asks the renderer to add. */
    static final int RENDER_MARGIN_PX = 10;

    /**
     * Edge length the renderer falls back to for a view with no content. The
     * fallback rectangle replaces the bounds outright, so the margin expansion
     * is not applied to it.
     */
    static final int EMPTY_VIEW_EDGE_PX = 100;

    /**
     * Projects the raster for a view at a given scale.
     *
     * @param bounds the view's content bounds, or {@code null} for an empty view
     * @param scale  the requested scale; must be finite and greater than zero
     * @return the projected dimensions and byte count
     */
    static RasterProjection project(ContentBounds bounds, double scale) {
        int w;
        int h;
        if (bounds == null) {
            // An empty view renders as a fixed blank square, with no margin.
            w = (int) (EMPTY_VIEW_EDGE_PX * scale);
            h = (int) (EMPTY_VIEW_EDGE_PX * scale);
        } else {
            long contentWidth = extentOf(bounds.width());
            long contentHeight = extentOf(bounds.height());
            long margin = (long) (RENDER_MARGIN_PX / scale);
            w = (int) ((contentWidth + 2L * margin) * scale);
            h = (int) ((contentHeight + 2L * margin) * scale);
        }
        w = Math.max(w, 0);
        h = Math.max(h, 0);
        return new RasterProjection(w, h, byteCount(w, h));
    }

    /**
     * Converts one axis of the content bounds into the whole-pixel extent to
     * project from.
     *
     * <p>Rounds up. Bendpoint extents make these bounds fractional, and the frame
     * they are measured in already runs short of what the renderer measures, so
     * the fraction is spent on the safe side rather than truncated away.</p>
     *
     * <p>An extent that is not a finite, non-negative number cannot be projected
     * at all. Rather than let {@code (long) Math.ceil(NaN)} quietly become zero —
     * which would report a raster of no size and wave the render through — such a
     * value is treated as the largest extent there is, so the guard refuses. A
     * projection it cannot compute must fail closed, not open.</p>
     */
    private static long extentOf(double boundsExtent) {
        if (!Double.isFinite(boundsExtent) || boundsExtent < 0.0) {
            return Integer.MAX_VALUE;
        }
        return (long) Math.ceil(boundsExtent);
    }

    /**
     * Byte count of a raster, saturating instead of wrapping.
     *
     * <p>The pixel product alone cannot overflow — two {@code int}s multiplied as
     * {@code long}s reach at most about 4.6e18, inside {@code Long.MAX_VALUE} —
     * but multiplying by the bytes per pixel can, and a wrapped product comes out
     * <em>negative</em>. That is the one arithmetic result this class must never
     * produce: every caller compares the byte count against a budget with
     * {@code <=}, so a negative figure reads as "fits" and waves through exactly
     * the allocation the guard exists to refuse. Saturating at
     * {@code Long.MAX_VALUE} keeps an unrepresentable raster refused.</p>
     */
    private static long byteCount(int width, int height) {
        long pixels = (long) width * (long) height;
        if (pixels > Long.MAX_VALUE / BYTES_PER_PIXEL) {
            return Long.MAX_VALUE;
        }
        return pixels * BYTES_PER_PIXEL;
    }

    /**
     * Returns the largest scale, in hundredths, within the accepted range whose
     * projection fits the given budget, or {@code 0.0} when no accepted scale does.
     *
     * <p>Searched by descending trial rather than solved algebraically: the margin
     * expansion truncates against the scale, so the byte count is a step function
     * of it and an inverted closed form would not land on a value that actually
     * fits. Every candidate returned has been checked by the same projection the
     * guard rejects with, so a suggested scale is one the guard will accept.</p>
     *
     * @param bounds      the view's content bounds, or {@code null} for an empty view
     * @param budgetBytes the byte budget one bitmap may claim
     * @param minScale    the smallest scale the tool accepts
     * @param maxScale    the largest scale the tool accepts
     * @return a scale that fits, or {@code 0.0} if none does
     */
    static double largestScaleWithin(ContentBounds bounds, long budgetBytes,
            double minScale, double maxScale) {
        int floorHundredths = (int) Math.round(minScale * 100);
        for (int hundredths = (int) Math.floor(maxScale * 100);
                hundredths >= floorHundredths; hundredths--) {
            double candidate = hundredths / 100.0;
            // Bound the candidate against the range itself rather than trusting the
            // loop limits. Those limits are derived from doubles that need not land
            // on a whole hundredth, so a caller passing a bound that is not one
            // could otherwise be handed back a scale just outside the range it
            // said it accepts — and this value is published as a retry the caller
            // is told will work.
            if (candidate < minScale || candidate > maxScale) {
                continue;
            }
            if (project(bounds, candidate).bytes() <= budgetBytes) {
                return candidate;
            }
        }
        return 0.0;
    }
}
