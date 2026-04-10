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

    // ---- computeLabelHeightFromMetrics (B50) ----

    @Test
    public void computeLabelHeight_singleLine_returnsApprox25() {
        // "Server" at wide element (200px) — fits single line
        String label = "Application Server";
        // Words: Application=70, Server=42 → single-line width = 70+4+42 = 116
        ElementSizer.FontMetrics metrics = metricsFor(label, 70, 42);
        // Element width 200, content width = 200 - 30 (HORIZONTAL_PADDING) = 170 — fits in one line
        int height = ElementSizer.computeLabelHeightFromMetrics(label, metrics, 200);
        // Expected: 1 * 14 + 6 + 5 = 25
        assertEquals(25, height);
    }

    @Test
    public void computeLabelHeight_multiLine_exceedsDefault() {
        // "Payment Processing Engine" at 142px width — wraps to 2+ lines
        String label = "Payment Processing Engine";
        // Words: Payment=49, Processing=70, Engine=42
        ElementSizer.FontMetrics metrics = metricsFor(label, 49, 70, 42);
        // Element width 142, content width = 142 - 30 = 112
        // Line 1: "Payment" (49), "Processing" fits? 49+4+70=123 > 112 → wrap
        // Line 2: "Processing" (70), "Engine" fits? 70+4+42=116 > 112 → wrap
        // Line 3: "Engine" (42)
        // → 3 lines
        int height = ElementSizer.computeLabelHeightFromMetrics(label, metrics, 142);
        // Expected: 3 * 14 + 6 + 5 = 53
        assertTrue("Multi-line label height should exceed 25px: " + height, height > 25);
        assertEquals(53, height);
    }

    @Test
    public void computeLabelHeight_nullLabel_returnsDefault25() {
        int height = ElementSizer.computeLabelHeightFromMetrics(null, null, 200);
        assertEquals(ElementSizer.DEFAULT_LABEL_HEIGHT, height);
    }

    @Test
    public void computeLabelHeight_emptyLabel_returnsDefault25() {
        int height = ElementSizer.computeLabelHeightFromMetrics("", null, 200);
        assertEquals(ElementSizer.DEFAULT_LABEL_HEIGHT, height);
    }

    @Test
    public void computeLabelHeight_veryLongName_threeOrMoreLines() {
        // "Enterprise Application Integration Platform" at narrow width
        String label = "Enterprise Application Integration Platform";
        // Words: Enterprise=63, Application=70, Integration=70, Platform=49
        ElementSizer.FontMetrics metrics = metricsFor(label, 63, 70, 70, 49);
        // Element width 120, content width = 120 - 30 = 90
        // Line 1: "Enterprise" (63), "Application" 63+4+70=137>90 → wrap
        // Line 2: "Application" (70), "Integration" 70+4+70=144>90 → wrap
        // Line 3: "Integration" (70), "Platform" 70+4+49=123>90 → wrap
        // Line 4: "Platform" (49)
        // → 4 lines
        int height = ElementSizer.computeLabelHeightFromMetrics(label, metrics, 120);
        // Expected: 4 * 14 + 6 + 5 = 67
        assertEquals(67, height);
        assertTrue("Should be proportionally taller than 2-line", height > 53);
    }

    @Test
    public void computeLabelHeight_wideElement_singleLine() {
        // Same long name but at very wide element — fits single line
        String label = "Payment Processing Engine";
        ElementSizer.FontMetrics metrics = metricsFor(label, 49, 70, 42);
        // Element width 300, content width = 300 - 30 = 270
        // Single line: 49+4+70+4+42 = 169 < 270 → 1 line
        int height = ElementSizer.computeLabelHeightFromMetrics(label, metrics, 300);
        assertEquals(25, height);
    }

    @Test
    public void computeLabelHeight_zeroOrNegativeContentWidth_returnsDefault() {
        String label = "Test Label Here!";
        ElementSizer.FontMetrics metrics = metricsFor(label, 28, 35, 28, 7);
        // Element width = HORIZONTAL_PADDING exactly → content width = 0
        int height = ElementSizer.computeLabelHeightFromMetrics(label, metrics, ElementSizer.HORIZONTAL_PADDING);
        assertEquals(ElementSizer.DEFAULT_LABEL_HEIGHT, height);
    }

    @Test
    public void computeLabelHeight_shortName_returnsDefault() {
        // Names <= SHORT_NAME_THRESHOLD (15 chars) should fast-path to DEFAULT_LABEL_HEIGHT
        // "DB" is 2 chars — well under threshold
        // computeLabelHeightFromMetrics still needs to handle short names for direct calls
        String label = "DB Server";  // 9 chars, under threshold
        ElementSizer.FontMetrics metrics = metricsFor(label, 14, 42);
        int height = ElementSizer.computeLabelHeightFromMetrics(label, metrics, 200);
        // Single line: 1 * 14 + 6 + 5 = 25
        assertEquals(25, height);
    }
}
