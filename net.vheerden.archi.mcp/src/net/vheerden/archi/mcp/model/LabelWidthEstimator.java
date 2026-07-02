package net.vheerden.archi.mcp.model;

/**
 * Estimates the rendered width of a connection/edge label in pixels using the
 * same char-count yardstick the layout-quality crowding detector measures with
 * ({@link LayoutQualityAssessor#LABEL_CHAR_WIDTH} /
 * {@link LayoutQualityAssessor#LABEL_PADDING_X}).
 *
 * <p>Reusing the detector's base constants — rather than introducing a third,
 * independent width model — keeps the reserver and the crowding detector on one
 * char-count yardstick. Note the two are <b>not</b> identical: the detector
 * additionally applies {@link LayoutQualityAssessor#LABEL_RENDER_WIDTH_FACTOR}
 * (a render-calibration the reserver deliberately omits), so the reserver
 * reserves the raw estimate while the detector measures render-calibrated width.
 * The reserver's resulting mild under-reservation is a known, accepted property
 * (see {@code LABEL_RENDER_WIDTH_FACTOR} Javadoc). This is a headless-safe
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
		return (int) Math.ceil(labelText.length() * LayoutQualityAssessor.LABEL_CHAR_WIDTH
				+ LayoutQualityAssessor.LABEL_PADDING_X);
	}
}
