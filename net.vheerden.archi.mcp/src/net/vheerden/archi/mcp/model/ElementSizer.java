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
    /** Vertical padding for label text area (top + bottom of text). */
    static final int LABEL_VERTICAL_PADDING = 6;
    /** Clearance between label bottom and first child element. */
    static final int LABEL_CHILD_CLEARANCE = 5;
    /** Default containment label top for null/empty/short labels (backward compatible). */
    static final int DEFAULT_LABEL_HEIGHT = 25;
    /**
     * Combined horizontal text inset (left + right) for note and group label-band sizing.
     * Subtracted from the outer box width by the {@code fitTextBoxHeightToContent} callers
     * to derive the actual text-wrap content width — Archi's figure renderer reserves a
     * small horizontal margin inside the box, so the wrap-prediction width must be slightly
     * narrower than the outer box width to avoid systematic line-count under-estimation.
     * Calibrated conservatively (errs toward over-grow).
     */
    static final int HORIZONTAL_TEXT_INSET = 12;
    /**
     * Clamp ceiling for autosized note bodies. Pathological wall-of-text degrades to this
     * height; content beyond the clamp keeps today's "clipped" behaviour.
     */
    static final int MAX_NOTE_HEIGHT = 600;
    /**
     * Clamp ceiling for autosized group label-bands. Same degrade semantics as
     * {@link #MAX_NOTE_HEIGHT}, with a larger budget to accommodate longer descriptive
     * group labels.
     */
    static final int MAX_GROUP_LABEL_BAND = 800;

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
     * Computes the vertical space needed for a parent element's label text area,
     * accounting for word-wrap at the given element width.
     *
     * <p>Used by {@code resizeElementsToFit} Pass 2 to dynamically reserve label space
     * above children, replacing the fixed {@code CONTAINMENT_LABEL_TOP = 25} constant.</p>
     *
     * @param labelText the parent element's display name (may be null or empty)
     * @param elementWidth the resolved parent element width in pixels
     * @return label height in pixels (~25px for single-line, more for multi-line)
     */
    public static int computeLabelHeight(String labelText, int elementWidth) {
        if (labelText == null || labelText.isEmpty() || labelText.length() <= SHORT_NAME_THRESHOLD) {
            return DEFAULT_LABEL_HEIGHT;
        }

        FontMetrics metrics = measureText(labelText);
        return computeLabelHeightFromMetrics(labelText, metrics, elementWidth);
    }

    /**
     * Computes a fitted height for a text-bearing box (note body, group label-band).
     *
     * <p>Used by {@code prepareAddNoteToView} (note body) and {@code prepareAddGroupToView}
     * (group label-band) to reserve room for descriptive text when the caller did not
     * pin an explicit {@code height}. Mirrors the {@link #computeLabelHeight(String, int)}
     * → {@link #computeLabelHeightFromMetrics(String, FontMetrics, int)} pattern: SWT
     * measurement here, pure-geometry math in the {@code FromMetrics} overload.</p>
     *
     * <p>Formula: {@code lineCount = simulateWordWrap(text, ..., width)},
     * {@code height = lineCount × lineHeight + padding}, then clamped to
     * {@code [minHeight, maxHeight]}. Pathological wall-of-text degrades to
     * {@code maxHeight} (content beyond clamp keeps today's clipped behaviour).</p>
     *
     * @param text the content (note body or group label); null/empty returns {@code minHeight}
     * @param width box content width in pixels (used as wrap width directly)
     * @param padding additional vertical padding (combined top+bottom margin; e.g.
     *                {@link #LABEL_VERTICAL_PADDING})
     * @param minHeight floor — height never goes below this (typically the box's
     *                  {@code DEFAULT_*_HEIGHT}). Preserves back-compat for short content.
     * @param maxHeight ceiling — pathological cases degrade to this
     * @return fitted height in pixels, clamped to {@code [minHeight, maxHeight]}
     */
    static int fitTextBoxHeightToContent(String text, int width, int padding,
                                         int minHeight, int maxHeight) {
        if (text == null || text.isEmpty()) {
            return minHeight;
        }
        FontMetrics metrics = measureText(text);
        return fitTextBoxHeightToContentFromMetrics(text, width, metrics, padding,
                                                   minHeight, maxHeight);
    }

    /**
     * Pure-geometry overload — testable without SWT. Headless callers construct a fixed
     * {@link FontMetrics} record and call this directly; mirrors
     * {@link #computeLabelHeightFromMetrics(String, FontMetrics, int)}.
     *
     * <p><strong>Forced line breaks:</strong> any explicit {@code '\n'} character in
     * {@code text} adds one line beyond the horizontal-wrap count (errs toward over-grow
     * — never under-grow). Callers that pre-interpret escape sequences must do so before
     * calling, otherwise an un-interpreted {@code "\\n"} (literal backslash-n) will not
     * be counted as a break.</p>
     *
     * <p><strong>Precondition (review M2):</strong> {@code metrics.wordWidths} should be
     * sized to {@code text.split("\\s+").length}. {@link #measureText(String)} produces a
     * conforming record; ad-hoc callers (tests) MUST match this contract or
     * {@code simulateWordWrap} will silently truncate at the shorter length and the
     * computed height may be smaller than reality.</p>
     *
     * <p>For {@code width <= 0}, returns {@code minHeight} (no useful wrap possible).</p>
     */
    static int fitTextBoxHeightToContentFromMetrics(String text, int width, FontMetrics metrics,
                                                    int padding, int minHeight, int maxHeight) {
        if (text == null || text.isEmpty() || metrics == null || width <= 0) {
            return minHeight;
        }
        assert metrics.wordWidths.length == text.split("\\s+").length
                : "FontMetrics.wordWidths length must match text word count";
        int wrapLineCount = simulateWordWrap(text, metrics.wordWidths, metrics.spaceWidth, width);
        int forcedBreaks = countNewlines(text);
        int totalLineCount = wrapLineCount + forcedBreaks;
        int height = totalLineCount * metrics.lineHeight + padding;
        return Math.max(minHeight, Math.min(maxHeight, height));
    }

    /** Counts explicit {@code '\n'} characters in the given text (review M1). */
    private static int countNewlines(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    /**
     * Pure-geometry label height computation — testable without SWT.
     */
    static int computeLabelHeightFromMetrics(String labelText, FontMetrics metrics, int elementWidth) {
        if (labelText == null || labelText.isEmpty()) {
            return DEFAULT_LABEL_HEIGHT;
        }

        int contentWidth = elementWidth - HORIZONTAL_PADDING;
        if (contentWidth <= 0) {
            return DEFAULT_LABEL_HEIGHT;
        }

        int lineCount = simulateWordWrap(labelText, metrics.wordWidths, metrics.spaceWidth, contentWidth);
        return lineCount * metrics.lineHeight + LABEL_VERTICAL_PADDING + LABEL_CHILD_CLEARANCE;
    }

    /**
     * Computes <strong>compact wrap-fit</strong> dimensions for a leaf element: keep the box at a
     * fixed width and grow its height so the label wraps and fits, instead of widening to a
     * single line (which {@link #computeDimensions} does). This is the lever for embedded
     * ApplicationFunctions in a dense grid — preserving the grid pitch (width) while letting the
     * label wrap to a second line keeps neighbours from shifting.
     *
     * <p>Story {@code g-view-embedded-function-box-sizing-for-wrap}: the canonical View-G functions
     * sit in 150×26 boxes too short to wrap their 141–193px labels, so Archi clips them. Keeping the
     * 150 width and growing the height to the wrapped-label height clears the truncation with zero
     * horizontal shift. Unlike {@link #computeDimensions}, there is no aspect-ratio search and no
     * single-line widening — the width the caller passes is returned unchanged.</p>
     *
     * <p><strong>No {@code DEFAULT_HEIGHT} floor.</strong> Unlike {@link #computeAutoSize}, this
     * method does NOT clamp the returned height up to {@code DEFAULT_HEIGHT} (55) — a single-line
     * label yields {@code lineHeight + VERTICAL_PADDING} (~36px), which can be below 55. This is by
     * design: wrap-fit is a grow-only lever, and the intended caller
     * ({@code resizeElementsToFit} with {@code wrapFit=true}) applies the floor itself via
     * {@code Math.max(currentHeight, wrapFitHeight)} so an existing box is never shrunk. Direct
     * callers that need a 55px floor must apply it themselves.</p>
     *
     * @param labelText  the element's display name (may be null or empty)
     * @param fixedWidth the box width to preserve (typically the element's current width)
     * @return int array {fixedWidth, height} in pixels — width is returned verbatim
     * @throws ModelAccessException if SWT font metrics fail
     */
    public static int[] computeWrapFitDimensions(String labelText, int fixedWidth) {
        if (labelText == null || labelText.isEmpty()) {
            return new int[] { fixedWidth, DEFAULT_HEIGHT };
        }
        FontMetrics metrics = measureText(labelText);
        return computeWrapFitDimensionsFromMetrics(labelText, metrics, fixedWidth);
    }

    /**
     * Pure-geometry wrap-fit computation — testable without SWT. Mirrors the
     * {@link #computeWrapFitDimensions(String, int)} → {@code FromMetrics} split used by the other
     * sizers (SWT measurement in the public method, pure math here).
     *
     * <p>Formula: {@code contentWidth = fixedWidth − HORIZONTAL_PADDING};
     * {@code lineCount = simulateWordWrap(label, …, contentWidth)};
     * {@code height = lineCount × lineHeight + VERTICAL_PADDING}. Width is returned verbatim.
     * For a degenerate {@code contentWidth <= 0} (fixedWidth not wider than the horizontal padding),
     * returns {@code {fixedWidth, DEFAULT_HEIGHT}} — no useful wrap possible.</p>
     */
    static int[] computeWrapFitDimensionsFromMetrics(String labelText, FontMetrics metrics, int fixedWidth) {
        if (labelText == null || labelText.isEmpty() || metrics == null) {
            return new int[] { fixedWidth, DEFAULT_HEIGHT };
        }
        int contentWidth = fixedWidth - HORIZONTAL_PADDING;
        if (contentWidth <= 0) {
            return new int[] { fixedWidth, DEFAULT_HEIGHT };
        }
        int lineCount = simulateWordWrap(labelText, metrics.wordWidths, metrics.spaceWidth, contentWidth);
        int height = lineCount * metrics.lineHeight + VERTICAL_PADDING;
        return new int[] { fixedWidth, height };
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
    static FontMetrics measureText(String labelText) {
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
                // Single-line height. textExtent(labelText).y is the WHOLE-BLOCK height when
                // labelText contains line breaks (SWT honours '\n'), so using it here would be
                // multiplied again by the line count in the lineCount*lineHeight consumers,
                // making multi-line boxes grow quadratically. "Ag" gives the font's
                // ascender+descender reference, which is glyph-independent and therefore
                // identical to fullExtent.y for any single-line input (no regression there).
                int lineHeight = gc.textExtent("Ag").y;

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
