package net.vheerden.archi.mcp.response.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Data Transfer Object for an embedded view-reference visual object on a view.
 *
 * <p>A view-reference is a typed {@code IDiagramModelReference} visual object
 * that embeds another ArchiMate view as a clickable thumbnail — the
 * agent-driven equivalent of Archi GUI's drag-view-onto-view operation. The
 * referenced view's <em>name</em> is read dynamically by Archi's figure at
 * render time ({@code referencedModel.getName()}), so this DTO intentionally
 * does NOT carry a {@code referencedViewName} field; renaming the referenced
 * view auto-updates every embedding visual without a separate mutation.</p>
 *
 * <p>It is also the row {@code get-view-contents} reads a view reference back as, in its
 * {@code viewReferences} array: one mapper builds both, so the two cannot describe the same reference
 * differently. A read carries no {@code structuredWarnings} and no {@code note}.</p>
 *
 * <p>Field {@code referencedViewId} is the id of the view the reference stores, and is omitted from JSON
 * (via {@code @JsonInclude(NON_NULL)}) when it stores none. It can name a view that is no longer in the
 * model, when the reference was left behind by something other than {@code delete-view}.</p>
 *
 * <p>{@code imagePath}, {@code imagePosition} and {@code showIcon} are the icon a user can give a
 * reference in Archi (or through {@code update-view-object}), spelled and read as on every other view
 * object row. Each is omitted at Archi's default, so a reference without an icon carries none of them.</p>
 *
 * <p>{@code labelExpression} is the template a user or {@code update-view-object} set to draw as the
 * reference's label, read as on every other view object row and omitted when it has none. Archi reads a
 * {@code ${property:KEY}} in it from the referenced view's properties.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmbeddedViewDto(
    String viewObjectId,
    String referencedViewId,
    int x,
    int y,
    int width,
    int height,
    String parentViewObjectId,
    String fillColor,
    String lineColor,
    String fontColor,
    Integer opacity,
    Integer lineWidth,
    String fontName,
    Integer fontSize,
    String fontStyle,
    String gradient,
    Boolean deriveLineColor,
    Integer outlineOpacity,
    String lineStyle,
    String textAlignment,
    /** Archi's text position: where the label sits within the reference's box. */
    String verticalTextAlignment,
    String imagePath,                              // the icon a user gave the reference; null when none
    String imagePosition,                          // null at the default, top-right
    String showIcon,                               // null at the default, if-no-image
    String note,
    /**
     * Placement-time disclosures for THIS call — currently the one that says the rectangle just
     * placed lands on a route already drawn on the view.
     *
     * <p>Present only on the response to a placement call, and on every arm of one: the entity is
     * built once in the prepare and is the same object the immediate response returns, the
     * proposal carries under {@code preview}, and the batched response projects — so approval
     * mode, which discards {@code nextSteps} wholesale, cannot lose it.</p>
     *
     * <p>A route crossing a view-reference is a RATED {@code connectionPassThroughs}, not the
     * informational note metric: the assessor splits view objects on "is it a note" alone, so a
     * view-reference is an ordinary layout node and a crossing can drive the view to poor.</p>
     */
    java.util.List<StructuredWarningDto> structuredWarnings,
    String labelExpression                         // the template drawn as its label; null when none
) {

    /** The shape before the row reported its label expression: none, omitted from JSON. */
    public EmbeddedViewDto(
            String viewObjectId, String referencedViewId,
            int x, int y, int width, int height,
            String parentViewObjectId,
            String fillColor, String lineColor, String fontColor,
            Integer opacity, Integer lineWidth,
            String fontName, Integer fontSize, String fontStyle, String gradient,
            Boolean deriveLineColor, Integer outlineOpacity, String lineStyle,
            String textAlignment, String verticalTextAlignment,
            String imagePath, String imagePosition, String showIcon, String note,
            java.util.List<StructuredWarningDto> structuredWarnings) {
        this(viewObjectId, referencedViewId, x, y, width, height, parentViewObjectId,
                fillColor, lineColor, fontColor, opacity, lineWidth,
                fontName, fontSize, fontStyle, gradient,
                deriveLineColor, outlineOpacity, lineStyle,
                textAlignment, verticalTextAlignment,
                imagePath, imagePosition, showIcon, note, structuredWarnings, null);
    }

    /**
     * The shape without a placement disclosure — every field a stored view-reference has, and no
     * warning about the call that put it there. Read paths and fixtures build this one; the
     * placement prepare builds the canonical form, because only a call that places can measure
     * what it landed on.
     */
    public EmbeddedViewDto(
            String viewObjectId, String referencedViewId,
            int x, int y, int width, int height,
            String parentViewObjectId,
            String fillColor, String lineColor, String fontColor,
            Integer opacity, Integer lineWidth,
            String fontName, Integer fontSize, String fontStyle, String gradient,
            Boolean deriveLineColor, Integer outlineOpacity, String lineStyle,
            String textAlignment, String verticalTextAlignment,
            String imagePath, String imagePosition, String showIcon, String note) {
        this(viewObjectId, referencedViewId, x, y, width, height, parentViewObjectId,
                fillColor, lineColor, fontColor, opacity, lineWidth,
                fontName, fontSize, fontStyle, gradient,
                deriveLineColor, outlineOpacity, lineStyle,
                textAlignment, verticalTextAlignment,
                imagePath, imagePosition, showIcon, note, null);
    }

    /** This row with {@code warnings} as its placement-time disclosures. */
    public EmbeddedViewDto withStructuredWarnings(java.util.List<StructuredWarningDto> warnings) {
        return new EmbeddedViewDto(viewObjectId, referencedViewId, x, y, width, height, parentViewObjectId,
                fillColor, lineColor, fontColor, opacity, lineWidth,
                fontName, fontSize, fontStyle, gradient,
                deriveLineColor, outlineOpacity, lineStyle,
                textAlignment, verticalTextAlignment,
                imagePath, imagePosition, showIcon, note, warnings, labelExpression);
    }

    /**
     * Convenience constructor without styling (back-compat with the bounds-only
     * shape). All styling fields default to null (omitted from JSON via NON_NULL).
     */
    public EmbeddedViewDto(
            String viewObjectId,
            String referencedViewId,
            int x, int y, int width, int height,
            String parentViewObjectId) {
        this(viewObjectId, referencedViewId, x, y, width, height,
                parentViewObjectId,
                null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }
}
