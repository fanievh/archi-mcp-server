package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for {@link ImageHelper} (Story C4).
 *
 * <p>Coverage calculation tests are pure geometry — no EMF required.
 * Apply/read round-trip tests require EMF and run as PDE JUnit.</p>
 */
public class ImageHelperTest {

    // ---- Coverage calculation ----

    @Test
    public void shouldCalculateCoverage_whenNormalDimensions() {
        // 16x16 icon on 120x55 element = 256/6600 = 3.879%
        double coverage = ImageHelper.calculateCoverage(16, 16, 120, 55);
        assertEquals(3.88, coverage, 0.01);
    }

    @Test
    public void shouldReturnZeroCoverage_whenElementHasZeroWidth() {
        assertEquals(0.0, ImageHelper.calculateCoverage(16, 16, 0, 55), 0.001);
    }

    @Test
    public void shouldReturnZeroCoverage_whenElementHasZeroHeight() {
        assertEquals(0.0, ImageHelper.calculateCoverage(16, 16, 120, 0), 0.001);
    }

    @Test
    public void shouldReturnZeroCoverage_whenElementHasNegativeWidth() {
        assertEquals(0.0, ImageHelper.calculateCoverage(16, 16, -10, 55), 0.001);
    }

    @Test
    public void shouldReturnZeroCoverage_whenImageHasZeroDimensions() {
        assertEquals(0.0, ImageHelper.calculateCoverage(0, 0, 120, 55), 0.001);
    }

    @Test
    public void shouldReturnZeroCoverage_whenImageHasNegativeWidth() {
        assertEquals(0.0, ImageHelper.calculateCoverage(-16, 16, 120, 55), 0.001);
    }

    @Test
    public void shouldReturnZeroCoverage_whenImageHasNegativeHeight() {
        assertEquals(0.0, ImageHelper.calculateCoverage(16, -16, 120, 55), 0.001);
    }

    @Test
    public void shouldReturnHighCoverage_whenLargeImage() {
        // 100x100 image on 120x55 = 10000/6600 = 151.5%
        double coverage = ImageHelper.calculateCoverage(100, 100, 120, 55);
        assertTrue(coverage > 100.0);
    }

    // ---- Coverage warning ----

    @Test
    public void shouldReturnNull_whenCoverageBelowThreshold() {
        assertNull(ImageHelper.coverageWarning(3.88));
    }

    @Test
    public void shouldReturnNull_whenCoverageExactly25() {
        assertNull(ImageHelper.coverageWarning(25.0));
    }

    @Test
    public void shouldReturnWarning_whenCoverageAbove25() {
        String warning = ImageHelper.coverageWarning(45.0);
        assertTrue(warning.contains("45.0%"));
        assertTrue(warning.contains("obscure element name"));
    }

    @Test
    public void shouldReturnWarning_whenCoverageOver100() {
        String warning = ImageHelper.coverageWarning(151.5);
        assertTrue(warning.contains("151.5%"));
    }

    // ---- Validation ----

    @Test
    public void shouldAcceptValidImageParams() {
        ImageParams params = new ImageParams("images/abc.png", "bottom-left", "always");
        ImageHelper.validateImageParams(params); // should not throw
    }

    @Test
    public void shouldAcceptNullFields() {
        ImageHelper.validateImageParams(ImageParams.NONE); // should not throw
    }

    @Test
    public void shouldAcceptEmptyImagePath() {
        ImageParams params = new ImageParams("", null, null);
        ImageHelper.validateImageParams(params); // empty string = clear, valid
    }

    @Test(expected = ModelAccessException.class)
    public void shouldThrow_whenInvalidPosition() {
        ImageParams params = new ImageParams(null, "invalid-pos", null);
        ImageHelper.validateImageParams(params);
    }

    @Test(expected = ModelAccessException.class)
    public void shouldThrow_whenInvalidShowIcon() {
        ImageParams params = new ImageParams(null, null, "invalid-icon");
        ImageHelper.validateImageParams(params);
    }
}
