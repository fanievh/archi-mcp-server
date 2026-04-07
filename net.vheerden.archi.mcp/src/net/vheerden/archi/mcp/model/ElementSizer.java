package net.vheerden.archi.mcp.model;

import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * Computes element dimensions to fit label text using SWT font metrics.
 *
 * <p>Uses aspect-ratio-aware sizing: target 1.5:1 (width:height),
 * clamped to [1.2:1, 2.5:1]. Never shrinks below Archi defaults (120x55).
 * Short names (<=15 chars) return defaults unchanged.</p>
 *
 * <p>Called from handlers and accessor methods for auto-sizing elements.</p>
 */
public final class ElementSizer {

    /** Archi default element width. */
    static final int DEFAULT_WIDTH = 120;
    /** Archi default element height. */
    static final int DEFAULT_HEIGHT = 55;
    /** Short name threshold — names at or below this length return defaults. */
    static final int SHORT_NAME_THRESHOLD = 15;
    /** Target aspect ratio (width / height). */
    static final double TARGET_ASPECT_RATIO = 1.5;
    /** Minimum aspect ratio — don't go too square. */
    static final double MIN_ASPECT_RATIO = 1.2;
    /** Maximum aspect ratio — don't go too wide. */
    static final double MAX_ASPECT_RATIO = 2.5;
    /** Horizontal padding for icon space + text margins. */
    static final int HORIZONTAL_PADDING = 30;
    /** Vertical padding for top/bottom margins. */
    static final int VERTICAL_PADDING = 20;

    private ElementSizer() {}

    /**
     * Computes auto-size dimensions for an element label.
     *
     * @param labelText the element's display name (may be null or empty)
     * @return int array {width, height} in pixels
     * @throws ModelAccessException if SWT font metrics fail
     */
    public static int[] computeAutoSize(String labelText) {
        if (labelText == null || labelText.isEmpty() || labelText.length() <= SHORT_NAME_THRESHOLD) {
            return new int[] { DEFAULT_WIDTH, DEFAULT_HEIGHT };
        }

        FontMetrics metrics = measureText(labelText);
        return computeDimensions(labelText, metrics);
    }

    /**
     * Pure-geometry dimension computation — testable without SWT.
     * Given single-line text width, line height, and per-word widths,
     * computes aspect-ratio-aware element dimensions.
     */
    static int[] computeDimensions(String labelText, FontMetrics metrics) {
        if (labelText == null || labelText.isEmpty() || labelText.length() <= SHORT_NAME_THRESHOLD) {
            return new int[] { DEFAULT_WIDTH, DEFAULT_HEIGHT };
        }

        int singleLineWidth = metrics.textWidth + HORIZONTAL_PADDING;

        // Start with target aspect ratio: width = height * 1.5
        // Try progressively narrower widths to find one that fits the aspect ratio
        int bestWidth = singleLineWidth;
        int bestHeight = DEFAULT_HEIGHT;

        // Start from single-line width and narrow down
        for (int candidateWidth = singleLineWidth; candidateWidth >= DEFAULT_WIDTH; candidateWidth -= 5) {
            int contentWidth = candidateWidth - HORIZONTAL_PADDING;
            int lineCount = simulateWordWrap(labelText, metrics.wordWidths, metrics.spaceWidth, contentWidth);
            int candidateHeight = lineCount * metrics.lineHeight + VERTICAL_PADDING;
            candidateHeight = Math.max(candidateHeight, DEFAULT_HEIGHT);

            double ratio = (double) candidateWidth / candidateHeight;

            if (ratio >= MIN_ASPECT_RATIO && ratio <= MAX_ASPECT_RATIO) {
                // Valid ratio — pick closest to target
                double distToTarget = Math.abs(ratio - TARGET_ASPECT_RATIO);
                double bestRatio = (double) bestWidth / bestHeight;
                double bestDistToTarget = Math.abs(bestRatio - TARGET_ASPECT_RATIO);

                if (distToTarget < bestDistToTarget) {
                    bestWidth = candidateWidth;
                    bestHeight = candidateHeight;
                }
            }
        }

        // If no candidate hit the valid range, use single-line and clamp
        double finalRatio = (double) bestWidth / bestHeight;
        if (finalRatio < MIN_ASPECT_RATIO) {
            // Too square — widen
            bestWidth = (int) Math.ceil(bestHeight * MIN_ASPECT_RATIO);
        } else if (finalRatio > MAX_ASPECT_RATIO) {
            // Too wide — increase height
            bestHeight = (int) Math.ceil(bestWidth / MAX_ASPECT_RATIO);
        }

        // Floor enforcement
        bestWidth = Math.max(bestWidth, DEFAULT_WIDTH);
        bestHeight = Math.max(bestHeight, DEFAULT_HEIGHT);

        return new int[] { bestWidth, bestHeight };
    }

    /**
     * Simulates word wrapping at a given target width.
     *
     * @return number of lines the text would occupy
     */
    static int simulateWordWrap(String labelText, int[] wordWidths, int spaceWidth, int targetWidth) {
        String[] words = labelText.split("\\s+");
        if (words.length == 0) return 1;

        int lineCount = 1;
        int currentLineWidth = 0;

        for (int i = 0; i < words.length && i < wordWidths.length; i++) {
            int wordWidth = wordWidths[i];
            if (currentLineWidth > 0 && currentLineWidth + spaceWidth + wordWidth > targetWidth) {
                lineCount++;
                currentLineWidth = wordWidth;
            } else {
                currentLineWidth += (currentLineWidth > 0 ? spaceWidth : 0) + wordWidth;
            }
        }
        return lineCount;
    }

    /**
     * Measures text using SWT font metrics on the UI thread.
     */
    private static FontMetrics measureText(String labelText) {
        AtomicReference<FontMetrics> metricsRef = new AtomicReference<>();
        AtomicReference<RuntimeException> errorRef = new AtomicReference<>();

        Display.getDefault().syncExec(() -> {
            GC gc = null;
            try {
                gc = new GC(Display.getDefault());
                // Use system font — matches Archi's default element label font
                gc.setFont(Display.getDefault().getSystemFont());

                Point fullExtent = gc.textExtent(labelText);
                int spaceWidth = gc.textExtent(" ").x;
                int lineHeight = fullExtent.y;

                String[] words = labelText.split("\\s+");
                int[] wordWidths = new int[words.length];
                for (int i = 0; i < words.length; i++) {
                    wordWidths[i] = gc.textExtent(words[i]).x;
                }

                metricsRef.set(new FontMetrics(fullExtent.x, lineHeight, spaceWidth, wordWidths));
            } catch (Throwable t) {
                errorRef.set(new RuntimeException(t));
            } finally {
                if (gc != null) {
                    gc.dispose();
                }
            }
        });

        if (errorRef.get() != null) {
            throw new ModelAccessException(
                    "Font metrics measurement failed: " + errorRef.get().getMessage(),
                    errorRef.get(), ErrorCode.INTERNAL_ERROR);
        }

        return metricsRef.get();
    }

    /**
     * Holds measured font metrics for a label text.
     * Package-visible for testing.
     */
    record FontMetrics(int textWidth, int lineHeight, int spaceWidth, int[] wordWidths) {}
}
