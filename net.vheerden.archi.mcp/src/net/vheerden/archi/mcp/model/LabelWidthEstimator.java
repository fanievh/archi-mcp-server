package net.vheerden.archi.mcp.model;

import net.vheerden.archi.mcp.model.routing.LabelBoxEstimate;

/**
 * Estimates the width an ELK edge label reserves, in pixels: the shared
 * {@link LabelBoxEstimate#reservedWidth} (the raw char-count estimate) rounded up.
 *
 * <p>This is a <b>reservation</b>, so it omits
 * {@link LabelBoxEstimate#RENDER_WIDTH_FACTOR}, which only the consumers that
 * score a label apply (the layout-quality assessor and the routing label
 * optimizer). Widening it would change node spacing; the resulting mild
 * under-reservation is a known, accepted property. This is a headless-safe
 * estimate (no SWT glyph measurement), matching the layout path's no-SWT
 * constraint.</p>
 */
final class LabelWidthEstimator {

	private LabelWidthEstimator() {
	}

	/**
	 * Estimated label width in px, or {@code 0} for a null/blank label. A blank
	 * label reserves no space — this is how a manually suppressed label stays an
	 * effective escape hatch (a suppressed edge does not push elements apart).
	 *
	 * @param labelText the resolved, displayed label text (may be null/blank)
	 * @return estimated width in px, never negative
	 */
	static int estimateWidth(String labelText) {
		if (labelText == null || labelText.isBlank()) {
			return 0;
		}
		return (int) Math.ceil(LabelBoxEstimate.reservedWidth(labelText));
	}
}
