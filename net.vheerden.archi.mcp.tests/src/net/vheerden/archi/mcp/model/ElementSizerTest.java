package net.vheerden.archi.mcp.model;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for {@link ElementSizer} — pure-geometry dimension computation.
 * Tests the {@code computeDimensions} and {@code simulateWordWrap} methods
 * using synthetic {@link ElementSizer.FontMetrics} (no SWT runtime required).
 */
public class ElementSizerTest {

    // ---- Helper to create FontMetrics from word widths ----

    /**
     * Creates FontMetrics with realistic values: 14px line height, 4px space width.
     * textWidth is the sum of word widths + spaces (single-line extent).
     */
    private static ElementSizer.FontMetrics metricsFor(String label, int... wordWidths) {
        int spaceWidth = 4;
        int lineHeight = 14;
        int textWidth = 0;
        for (int i = 0; i < wordWidths.length; i++) {
            textWidth += wordWidths[i];
            if (i < wordWidths.length - 1) {
                textWidth += spaceWidth;
            }
        }
        return new ElementSizer.FontMetrics(textWidth, lineHeight, spaceWidth, wordWidths);
    }

    // ---- Short name / null / empty → default ----

    @Test
    public void computeAutoSize_nullLabel_returnsDefault() {
        int[] size = ElementSizer.computeAutoSize(null);
        assertEquals(ElementSizer.DEFAULT_WIDTH, size[0]);
        assertEquals(ElementSizer.DEFAULT_HEIGHT, size[1]);
    }

    @Test
    public void computeAutoSize_emptyLabel_returnsDefault() {
        int[] size = ElementSizer.computeAutoSize("");
        assertEquals(ElementSizer.DEFAULT_WIDTH, size[0]);
        assertEquals(ElementSizer.DEFAULT_HEIGHT, size[1]);
    }

    @Test
    public void computeDimensions_shortName_returnsDefault() {
        // "Server" = 6 chars, below threshold
        ElementSizer.FontMetrics metrics = metricsFor("Server", 42);
        int[] size = ElementSizer.computeDimensions("Server", metrics);
        assertEquals(ElementSizer.DEFAULT_WIDTH, size[0]);
        assertEquals(ElementSizer.DEFAULT_HEIGHT, size[1]);
    }

    @Test
    public void computeDimensions_exactly15Chars_returnsDefault() {
        // Exactly 15 chars = at threshold, returns default
        String label = "Exactly15Chars!"; // 15 chars
        assertEquals(15, label.length());
        ElementSizer.FontMetrics metrics = metricsFor(label, 100);
        int[] size = ElementSizer.computeDimensions(label, metrics);
        assertEquals(ElementSizer.DEFAULT_WIDTH, size[0]);
        assertEquals(ElementSizer.DEFAULT_HEIGHT, size[1]);
    }

    // ---- Long names grow both dimensions ----

    @Test
    public void computeDimensions_longName_growsBothDimensions() {
        // "Transaction Monitoring System" = 29 chars, 3 words
        String label = "Transaction Monitoring System";
        // Simulate: Transaction=77, Monitoring=70, System=42
        ElementSizer.FontMetrics metrics = metricsFor(label, 77, 70, 42);
        int[] size = ElementSizer.computeDimensions(label, metrics);
        assertTrue("Width should exceed default: " + size[0], size[0] > ElementSizer.DEFAULT_WIDTH);
        assertTrue("Height should exceed default: " + size[1], size[1] > ElementSizer.DEFAULT_HEIGHT);
    }

    // ---- Aspect ratio bounds ----

    @Test
    public void computeDimensions_aspectRatioWithinBounds_moderate() {
        // "Card Management System" = 22 chars
        String label = "Card Management System";
        ElementSizer.FontMetrics metrics = metricsFor(label, 28, 84, 42);
        int[] size = ElementSizer.computeDimensions(label, metrics);
        double ratio = (double) size[0] / size[1];
        assertTrue("Ratio should be >= 1.2: " + ratio, ratio >= ElementSizer.MIN_ASPECT_RATIO);
        assertTrue("Ratio should be <= 2.5: " + ratio, ratio <= ElementSizer.MAX_ASPECT_RATIO);
    }

    @Test
    public void computeDimensions_aspectRatioWithinBounds_long() {
        // "API Routing & Throttling & Load Balancing" = 42 chars
        String label = "API Routing & Throttling & Load Balancing";
        ElementSizer.FontMetrics metrics = metricsFor(label, 21, 49, 7, 70, 7, 28, 56);
        int[] size = ElementSizer.computeDimensions(label, metrics);
        double ratio = (double) size[0] / size[1];
        assertTrue("Ratio should be >= 1.2: " + ratio, ratio >= ElementSizer.MIN_ASPECT_RATIO);
        assertTrue("Ratio should be <= 2.5: " + ratio, ratio <= ElementSizer.MAX_ASPECT_RATIO);
    }

    @Test
    public void computeDimensions_veryLongName_respectsMaxAspectRatio() {
        // Very long single-word name forces wide layout
        String label = "VeryLongElementNameThatShouldNotBeExcessivelyWide";
        ElementSizer.FontMetrics metrics = metricsFor(label, 340);
        int[] size = ElementSizer.computeDimensions(label, metrics);
        double ratio = (double) size[0] / size[1];
        assertTrue("Ratio should be <= 2.5: " + ratio, ratio <= ElementSizer.MAX_ASPECT_RATIO);
    }

    // ---- Minimum size enforcement ----

    @Test
    public void computeDimensions_neverBelowMinimumWidth() {
        // 16-char name just above threshold but short words
        String label = "A Tiny Element X"; // 16 chars
        ElementSizer.FontMetrics metrics = metricsFor(label, 7, 28, 49, 7);
        int[] size = ElementSizer.computeDimensions(label, metrics);
        assertTrue("Width should be >= 120: " + size[0], size[0] >= ElementSizer.DEFAULT_WIDTH);
    }

    @Test
    public void computeDimensions_neverBelowMinimumHeight() {
        String label = "A Tiny Element X"; // 16 chars
        ElementSizer.FontMetrics metrics = metricsFor(label, 7, 28, 49, 7);
        int[] size = ElementSizer.computeDimensions(label, metrics);
        assertTrue("Height should be >= 55: " + size[1], size[1] >= ElementSizer.DEFAULT_HEIGHT);
    }

    // ---- Target aspect ratio ----

    @Test
    public void computeDimensions_moderateName_targetsAspectRatio() {
        // "Customer Profiling" = 18 chars, moderate length
        String label = "Customer Profiling";
        ElementSizer.FontMetrics metrics = metricsFor(label, 56, 56);
        int[] size = ElementSizer.computeDimensions(label, metrics);
        double ratio = (double) size[0] / size[1];
        // Should be reasonably close to 1.5 (within the valid range)
        assertTrue("Ratio should be >= 1.2: " + ratio, ratio >= ElementSizer.MIN_ASPECT_RATIO);
        assertTrue("Ratio should be <= 2.5: " + ratio, ratio <= ElementSizer.MAX_ASPECT_RATIO);
    }

    // ---- simulateWordWrap ----

    @Test
    public void simulateWordWrap_allFitsSingleLine() {
        String label = "Short Label";
        int[] wordWidths = { 35, 35 };
        int spaceWidth = 4;
        // Target width 200 — both words fit: 35 + 4 + 35 = 74
        int lines = ElementSizer.simulateWordWrap(label, wordWidths, spaceWidth, 200);
        assertEquals(1, lines);
    }

    @Test
    public void simulateWordWrap_wrapAtSecondWord() {
        String label = "Word1 Word2";
        int[] wordWidths = { 50, 50 };
        int spaceWidth = 4;
        // Target width 80 — Word1 (50) fits, Word2 won't fit (50+4+50=104 > 80)
        int lines = ElementSizer.simulateWordWrap(label, wordWidths, spaceWidth, 80);
        assertEquals(2, lines);
    }

    @Test
    public void simulateWordWrap_multipleWraps() {
        String label = "One Two Three Four";
        int[] wordWidths = { 30, 30, 40, 30 };
        int spaceWidth = 4;
        // Target width 60:
        // Line 1: "One Two" = 30 + 4 + 30 = 64 > 60 → wrap after "One" (30)
        // Actually: "One" fits (30), "Two" doesn't fit (30+4+30=64>60) → new line
        // Line 2: "Two" (30), "Three" doesn't fit (30+4+40=74>60) → new line
        // Line 3: "Three" (40), "Four" doesn't fit (40+4+30=74>60) → new line
        // Line 4: "Four" (30)
        int lines = ElementSizer.simulateWordWrap(label, wordWidths, spaceWidth, 60);
        assertEquals(4, lines);
    }

    @Test
    public void simulateWordWrap_emptyWordsArray() {
        int lines = ElementSizer.simulateWordWrap("", new int[0], 4, 100);
        assertEquals(1, lines);
    }
}
