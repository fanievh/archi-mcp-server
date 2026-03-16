package net.vheerden.archi.mcp.model;

/**
 * Value object bundling optional visual styling parameters (Story 11-2).
 *
 * <p>Used to pass styling parameters through accessor method signatures
 * without bloating individual parameter lists. All fields are nullable:
 * null means "not specified" (leave unchanged), empty string for colours
 * means "clear to default".</p>
 *
 * @param fillColor fill colour hex (#RRGGBB), empty string to clear, null = unchanged
 * @param lineColor line/border colour hex (#RRGGBB), empty string to clear, null = unchanged
 * @param fontColor font/text colour hex (#RRGGBB), empty string to clear, null = unchanged
 * @param opacity   opacity 0-255 (255 = fully opaque), null = unchanged
 * @param lineWidth line width 1-3 (1=normal, 2=medium, 3=heavy), null = unchanged
 */
public record StylingParams(
    String fillColor,
    String lineColor,
    String fontColor,
    Integer opacity,
    Integer lineWidth
) {

    /** An empty StylingParams indicating no styling changes. */
    public static final StylingParams NONE = new StylingParams(null, null, null, null, null);

    /**
     * Returns true if at least one styling parameter is specified (non-null).
     */
    public boolean hasAnyValue() {
        return fillColor != null || lineColor != null || fontColor != null
            || opacity != null || lineWidth != null;
    }
}
