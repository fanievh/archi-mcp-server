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
 * <p>Field {@code referencedViewId} may be omitted from JSON (via
 * {@code @JsonInclude(NON_NULL)}) if Archi's EMF model has cleaned the
 * cross-reference after a delete-cascade.</p>
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
    String verticalTextAlignment,
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
    java.util.List<StructuredWarningDto> structuredWarnings
) {

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
            String textAlignment, String verticalTextAlignment, String note) {
        this(viewObjectId, referencedViewId, x, y, width, height, parentViewObjectId,
                fillColor, lineColor, fontColor, opacity, lineWidth,
                fontName, fontSize, fontStyle, gradient,
                deriveLineColor, outlineOpacity, lineStyle,
                textAlignment, verticalTextAlignment, note, null);
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
                null, null);
    }
}
