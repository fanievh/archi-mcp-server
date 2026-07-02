package net.vheerden.archi.mcp.model;

/**
 * Value object bundling optional visual styling parameters. Extended with
 * {@code figureType} + {@code textAlignment} + {@code verticalTextAlignment};
 * later extended with typography ({@code fontName} / {@code fontSize} /
 * {@code fontStyle}), connection {@code lineStyle}, {@code gradient},
 * note {@code borderType}, {@code deriveLineColor}, and {@code outlineOpacity}.
 *
 * <p>Used to pass styling parameters through accessor method signatures
 * without bloating individual parameter lists. All fields are nullable:
 * null means "not specified" (leave unchanged), empty string for colours
 * and {@code fontName} means "clear to default".</p>
 *
 * @param fillColor             fill colour hex (#RRGGBB), empty string to clear, null = unchanged
 * @param lineColor             line/border colour hex (#RRGGBB), empty string to clear, null = unchanged
 * @param fontColor             font/text colour hex (#RRGGBB), empty string to clear, null = unchanged
 * @param opacity               opacity 0-255 (255 = fully opaque), null = unchanged
 * @param lineWidth             line width 1-3 (1=normal, 2=medium, 3=heavy), null = unchanged
 * @param figureType            figure type — "rectangular" or "tabbed", null = unchanged. Maps to
 *                              {@code IBorderType.setBorderType(int)} for {@code IDiagramModelGroup}
 *                              and {@code IDiagramModelArchimateObject.setType(int)} for ArchiMate elements
 * @param textAlignment         horizontal label alignment — "left", "centre" (UK) / "center" (US),
 *                              or "right", null = unchanged. Maps to {@code ITextAlignment.setTextAlignment(int)}
 * @param verticalTextAlignment vertical label position within the figure — "top", "centre" / "center",
 *                              or "bottom", null = unchanged. Maps to {@code ITextPosition.setTextPosition(int)}
 * @param fontName              font family name (e.g. "Segoe UI"), empty string to clear to system default,
 *                              null = unchanged. Merged into the composite font string via
 *                              {@code IFontAttribute.setFont(String)}
 * @param fontSize              font point size (positive int), null = unchanged. Merged into the composite font string
 * @param fontStyle             font style — "normal" / "bold" / "italic" / "bold-italic", null = unchanged.
 *                              Maps to SWT FontData style bitmask (NORMAL=0, BOLD=1, ITALIC=2, BOLD|ITALIC=3)
 * @param lineStyle             view-object outline line style — "solid" / "dashed" / "dotted" / "none",
 *                              null = unchanged. Applies to view objects only (per Archi 5.8 typed setter
 *                              {@code IDiagramModelObject.setLineStyle(int)}); silently ignored on connections.
 * @param gradient              gradient direction — "none" / "top-bottom" / "bottom-top" /
 *                              "left-right" / "right-left", null = unchanged. Maps to
 *                              {@code IDiagramModelObject.setGradient(int)} (-1=none, 0=Top, 1=Left, 2=Right, 3=Bottom)
 * @param borderType            note border type — "dogear" / "rectangle" / "none", null = unchanged.
 *                              Applies to notes only; silently ignored on other view objects.
 *                              Maps to {@code IBorderType.setBorderType(int)} on {@code IDiagramModelNote}
 *                              (BORDER_DOGEAR=0, BORDER_RECTANGLE=1, BORDER_NONE=2)
 * @param deriveLineColor       whether to derive the element's line colour from its fill colour
 *                              (Archi default: true), null = unchanged. Maps to
 *                              {@code IDiagramModelObject.setDeriveElementLineColor(boolean)}
 * @param outlineOpacity        line/outline opacity 0-255 (255 = fully opaque, Archi default), null = unchanged.
 *                              Maps to {@code IDiagramModelObject.setLineAlpha(int)}
 * @param recede                container-fill recession opt-out (add-to-view / add-group-to-view only):
 *                              null or true = auto-recede an unauthored (null-fill) parent the moment it
 *                              gains a nested child so the container reads as a backdrop rather than a flat
 *                              blob; false = suppress that auto-recede for this call. Has no effect on the
 *                              newly-created object's own styling (it governs the parent), and is ignored by
 *                              {@link StylingHelper#applyStylingToNewObject} and by add paths other than
 *                              add-to-view / add-group-to-view.
 */
public record StylingParams(
    String fillColor,
    String lineColor,
    String fontColor,
    Integer opacity,
    Integer lineWidth,
    String figureType,
    String textAlignment,
    String verticalTextAlignment,
    String fontName,
    Integer fontSize,
    String fontStyle,
    String lineStyle,
    String gradient,
    String borderType,
    Boolean deriveLineColor,
    Integer outlineOpacity,
    Boolean recede
) {

    /** An empty StylingParams indicating no styling changes. */
    public static final StylingParams NONE =
            new StylingParams(null, null, null, null, null, null, null, null,
                              null, null, null, null, null, null, null, null);

    /**
     * Back-compatibility convenience constructor matching the original 5-field record
     * shape. Delegates to the 16-field canonical constructor with
     * trailing nulls. Preserves existing
     * {@code new StylingParams(fill, line, font, opacity, lineWidth)} call sites byte-identically.
     */
    public StylingParams(String fillColor, String lineColor, String fontColor,
                         Integer opacity, Integer lineWidth) {
        this(fillColor, lineColor, fontColor, opacity, lineWidth, null, null, null,
             null, null, null, null, null, null, null, null);
    }

    /**
     * Back-compatibility convenience constructor matching the post-predecessor 8-field
     * record shape. Delegates to the
     * 16-field canonical constructor with trailing nulls. Preserves existing
     * {@code new StylingParams(fill, line, font, opacity, lineWidth, figureType, textAlignment, verticalTextAlignment)}
     * call sites byte-identically.
     */
    public StylingParams(String fillColor, String lineColor, String fontColor,
                         Integer opacity, Integer lineWidth,
                         String figureType, String textAlignment, String verticalTextAlignment) {
        this(fillColor, lineColor, fontColor, opacity, lineWidth,
             figureType, textAlignment, verticalTextAlignment,
             null, null, null, null, null, null, null, null);
    }

    /**
     * Back-compatibility constructor matching the pre-{@code recede} 16-field record shape.
     * Delegates to the 17-field canonical constructor with {@code recede = null} (= default
     * auto-recede). Preserves every existing 16-arg {@code new StylingParams(...)} call site
     * byte-identically (including the chained {@code this(...)} delegations from the 5-/8-field
     * convenience constructors and the {@link #NONE} constant), so only callers that genuinely
     * carry a {@code recede} opt-out use the canonical 17-field form.
     */
    public StylingParams(String fillColor, String lineColor, String fontColor,
                         Integer opacity, Integer lineWidth, String figureType,
                         String textAlignment, String verticalTextAlignment,
                         String fontName, Integer fontSize, String fontStyle,
                         String lineStyle, String gradient, String borderType,
                         Boolean deriveLineColor, Integer outlineOpacity) {
        this(fillColor, lineColor, fontColor, opacity, lineWidth, figureType,
             textAlignment, verticalTextAlignment, fontName, fontSize, fontStyle,
             lineStyle, gradient, borderType, deriveLineColor, outlineOpacity, null);
    }

    /**
     * Returns true if at least one styling parameter is specified (non-null).
     *
     * <p>Deliberately excludes {@code recede}: it is not styling applied to the object itself
     * (it governs the parent container's fill), so it must not trip the
     * {@link StylingHelper#applyStylingToNewObject} early-return guard.</p>
     */
    public boolean hasAnyValue() {
        return fillColor != null || lineColor != null || fontColor != null
            || opacity != null || lineWidth != null
            || figureType != null || textAlignment != null || verticalTextAlignment != null
            || fontName != null || fontSize != null || fontStyle != null
            || lineStyle != null || gradient != null || borderType != null
            || deriveLineColor != null || outlineOpacity != null;
    }
}
