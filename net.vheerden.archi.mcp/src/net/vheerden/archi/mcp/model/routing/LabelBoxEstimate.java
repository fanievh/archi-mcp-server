package net.vheerden.archi.mcp.model.routing;

/**
 * The one size estimate for a connection label's box, shared by every consumer that sizes one.
 *
 * <p>A pure char-count model of Archi's default ~11pt font: {@value #CHAR_WIDTH} px per character,
 * {@value #CHAR_HEIGHT} px glyph height, plus padding. No SWT text measuring: the routing, layout and
 * assessment paths run headless.</p>
 *
 * <p>Two widths, named for what they are for, so a new consumer has to choose:</p>
 * <ul>
 *   <li>{@link #renderedWidth} — where a label is <b>scored</b> (the layout-quality assessor and the
 *       routing label optimizer). It applies {@link #RENDER_WIDTH_FACTOR}, so a label the optimizer
 *       picks as clear is measured by the assessor with the same box.</li>
 *   <li>{@link #reservedWidth} — where space is <b>reserved</b> (the router's label-clearance path
 *       shift, ELK edge-label spacing, hub sizing). It omits the factor: those consumers move geometry,
 *       and widening them would move routes and node spacing.</li>
 * </ul>
 *
 * <p>Null and empty labels are each caller's business: a scorer has no box for an empty label, and a
 * reserver of the label Archi shows (ELK edge-label spacing, hub sizing) reserves nothing for a blank one.</p>
 */
public final class LabelBoxEstimate {

    /** Estimated character width in pixels (Archi's default ~11pt font). */
    public static final double CHAR_WIDTH = 8.0;
    /** Estimated character height in pixels. */
    public static final double CHAR_HEIGHT = 14.0;
    /** Horizontal padding around label text. */
    public static final double PADDING_X = 10.0;
    /** Vertical padding around label text. */
    public static final double PADDING_Y = 6.0;
    /** Label box height: one line of text plus its vertical padding (20 px). */
    public static final double HEIGHT = CHAR_HEIGHT + PADDING_Y;

    /**
     * Render-calibration factor for connection-label glyph width.
     * <p>The char-count model ({@code length() * CHAR_WIDTH}) measures a label narrower than Archi
     * actually renders it, so a detector using it under-flags label-on-element / label-on-label on short,
     * tight segments where the rendered glyph bleeds past the measured box.
     * <p><b>Evidence basis (informed approximation, not a directly measured edge-label value).</b> The
     * {@code 1.35} factor was observed in the label-truncation calibration (see
     * {@code LayoutQualityAssessor.detectLabelTruncation}), where Archi rendered an <i>element</i> box label
     * ~1.35x wider than its SWT {@code ElementSizer}-measured single-line width. That measurement was
     * SWT-vs-rendered for box labels; the connection-label width calibrated here is a pure char-count
     * estimate (no SWT), so the transfer is an informed approximation rather than a measured edge-label
     * ratio. Tune this factor against the live render if it over- or under-flags (the value, not the
     * placement, is the knob).
     * <p><b>Where it applies.</b> It is applied wherever a label is scored (the assessor and the label
     * optimizer, through {@link #renderedWidth}) and omitted wherever space is reserved (the router's
     * label-clearance path shift, ELK edge-label spacing and hub sizing, through {@link #reservedWidth}),
     * because scoring must measure the box Archi draws while reserving moves routes and nodes, and the
     * reservers' mild under-reservation is a known, accepted property.
     */
    public static final double RENDER_WIDTH_FACTOR = 1.35;

    /**
     * Gap (px) Archi leaves between a Middle label and its reference point on the side an applied "Label
     * Offset" anchor names: Archi draws a Middle label with draw2d's {@code ConnectionLocator} and calls
     * {@code setGap(5)} on it. Not a tuning knob: it is the renderer's value.
     */
    public static final int OFFSET_RENDER_GAP = 5;

    private LabelBoxEstimate() {
    }

    /**
     * How far Archi moves a centred Middle label on one axis its "Label Offset" anchor names, for a label of
     * this size on that axis (its width for East or West, its height for North or South). draw2d's
     * {@code AbstractLocator.getNewBounds} halves the label figure's whole-pixel size (integer division) and
     * adds {@link #OFFSET_RENDER_GAP}, which leaves the label that gap clear of its reference point on the
     * named side. The size here is this estimate's, rounded to a whole pixel as the figure's is, so the
     * optimizer (which scores whole-pixel boxes) and the assessor (which keeps the unrounded width) move a
     * label by the same pixels. Exact for the renderer's rule; the size is still an estimate.
     *
     * @param size the label's estimated size on the moved axis, in px
     * @return the distance the label moves on that axis, in px
     */
    public static int drawnOffsetLift(double size) {
        return (int) Math.round(size) / 2 + OFFSET_RENDER_GAP;
    }

    /**
     * The width a label is scored with: the glyph run scaled by {@link #RENDER_WIDTH_FACTOR}, plus the
     * unscaled padding. For the assessor and the label optimizer.
     *
     * @param labelText label text, not null
     * @return estimated rendered width in px
     */
    public static double renderedWidth(String labelText) {
        return labelText.length() * CHAR_WIDTH * RENDER_WIDTH_FACTOR + PADDING_X;
    }

    /**
     * The width a label reserves: the raw char-count run plus padding, without the render factor. For
     * consumers that move geometry (route shifts, ELK spacing, hub sizing).
     *
     * @param labelText label text, not null
     * @return estimated reserved width in px
     */
    public static double reservedWidth(String labelText) {
        return labelText.length() * CHAR_WIDTH + PADDING_X;
    }
}
