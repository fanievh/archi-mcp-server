package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests for {@link LabelWidthEstimator} — the headless char-count label-width
 * estimate shared with the crowding detector. Expected values use the detector
 * yardstick {@code len * 8.0 + 10.0}, rounded up.
 */
public class LabelWidthEstimatorTest {

	@Test
	public void shouldReturnZero_whenNull() {
		assertEquals(0, LabelWidthEstimator.estimateWidth(null));
	}

	@Test
	public void shouldReturnZero_whenEmpty() {
		assertEquals(0, LabelWidthEstimator.estimateWidth(""));
	}

	@Test
	public void shouldReturnZero_whenBlank() {
		assertEquals(0, LabelWidthEstimator.estimateWidth("   "));
	}

	@Test
	public void shouldEstimate_forRepresentativeLabels() {
		assertEquals(42, LabelWidthEstimator.estimateWidth("flow"));    // 4*8 + 10
		assertEquals(58, LabelWidthEstimator.estimateWidth("serves"));  // 6*8 + 10
		assertEquals(74, LabelWidthEstimator.estimateWidth("accesses")); // 8*8 + 10
		assertEquals(162, LabelWidthEstimator.estimateWidth("reads/writes config")); // 19*8 + 10
	}
}
