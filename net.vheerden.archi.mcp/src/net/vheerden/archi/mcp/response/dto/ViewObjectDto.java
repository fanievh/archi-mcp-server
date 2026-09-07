package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Data Transfer Object for a visual element placed on a view.
 *
 * <p>Represents the created or found diagram object on a view, including
 * its unique view object ID, the referenced model element, position/size,
 * and optional visual styling properties.</p>
 *
 * <p>Added optional styling fields (fillColor,
 * lineColor, fontColor, opacity, lineWidth). These are omitted from JSON
 * when null (i.e., when the object uses Archi's default styling).</p>
 *
 * <p>Added optional {@code labelExpression}
 * field — Archi's per-view-object dynamic label template (e.g. {@code "${name}"},
 * {@code "${property:Owner}"}). Omitted from JSON when null (no label expression set).</p>
 *
 * <p>Added optional typography fields
 * ({@code fontName}, {@code fontSize}, {@code fontStyle}), {@code gradient},
 * {@code borderType} (note-specific), {@code deriveLineColor}, and
 * {@code outlineOpacity}. All omitted from JSON when at Archi default.</p>
 *
 * <p>The last two fields describe objects <em>other than this one</em> that the same mutation
 * changed, and they are populated only where a mutation could have changed them. Resizing an object
 * displaces everything anchored to it, and pushes the group around it wider when the new rectangle
 * no longer fits — neither is named in the request, and the client is an agent that cannot see the
 * canvas. Read paths construct this record without them, so both lists are empty there and, being
 * {@code NON_EMPTY}, absent from the wire entirely: the shape a reader receives is unchanged.</p>
 *
 * <p>{@code parentViewObjectId} names the container this object sits in, spelt the way the read
 * path already spells it. It is what makes {@code x} and {@code y} above readable at all: those are
 * relative to the immediate parent's top-left corner whenever there is one, so without the parent
 * they are a frame with no origin. Null and omitted for an object on the view itself, which is the
 * shape every existing response for a top-level object already had.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ViewObjectDto(
    String viewObjectId,
    String elementId,
    String elementName,
    String elementType,
    int x,
    int y,
    int width,
    int height,
    String fillColor,
    String lineColor,
    String fontColor,
    Integer opacity,
    Integer lineWidth,
    String imagePath,
    String imagePosition,
    String showIcon,
    Double imageCoveragePercent,
    String imageCoverageWarning,
    String figureType,
    String textAlignment,
    String verticalTextAlignment,
    String labelExpression,
    String fontName,
    Integer fontSize,
    String fontStyle,
    String gradient,
    String borderType,
    Boolean deriveLineColor,
    Integer outlineOpacity,
    String lineStyle,
    String anchorTarget,
    String anchorEdge,
    Integer anchorDx,
    Integer anchorDy,
    String parentViewObjectId,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<MovedViewObjectDto> movedObjects,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<MovedViewObjectDto> resizedAncestors
) {

    /** Never null: the canonical constructor normalizes null lists to empty. */
    public ViewObjectDto {
        movedObjects = movedObjects != null ? movedObjects : List.of();
        resizedAncestors = resizedAncestors != null ? resizedAncestors : List.of();
    }

    /**
     * Constructor matching the prior 34-field shape (no parent, no displaced-object fields).
     * Delegates with a null parent and two empty lists, all three omitted from JSON — so every
     * existing construction site, read paths included, serializes byte-identically to before the
     * fields existed.
     */
    public ViewObjectDto(
            String viewObjectId, String elementId, String elementName, String elementType,
            int x, int y, int width, int height,
            String fillColor, String lineColor, String fontColor,
            Integer opacity, Integer lineWidth,
            String imagePath, String imagePosition, String showIcon,
            Double imageCoveragePercent, String imageCoverageWarning,
            String figureType, String textAlignment, String verticalTextAlignment,
            String labelExpression,
            String fontName, Integer fontSize, String fontStyle,
            String gradient, String borderType, Boolean deriveLineColor,
            Integer outlineOpacity, String lineStyle,
            String anchorTarget, String anchorEdge, Integer anchorDx, Integer anchorDy) {
        this(viewObjectId, elementId, elementName, elementType,
                x, y, width, height,
                fillColor, lineColor, fontColor, opacity, lineWidth,
                imagePath, imagePosition, showIcon,
                imageCoveragePercent, imageCoverageWarning,
                figureType, textAlignment, verticalTextAlignment,
                labelExpression,
                fontName, fontSize, fontStyle,
                gradient, borderType, deriveLineColor, outlineOpacity, lineStyle,
                anchorTarget, anchorEdge, anchorDx, anchorDy,
                null, List.of(), List.of());
    }

    /**
     * Constructor for a fresh placement that names the container it landed in.
     *
     * <p>The 30-field shape plus the parent: an object being added has no anchor to another object
     * and has displaced nothing, so those five delegate as null and two empty lists. This is the
     * shape the add path builds, and naming the container is the whole point of it — the x/y two
     * arguments earlier are relative to that container whenever there is one.</p>
     */
    public ViewObjectDto(
            String viewObjectId, String elementId, String elementName, String elementType,
            int x, int y, int width, int height,
            String fillColor, String lineColor, String fontColor,
            Integer opacity, Integer lineWidth,
            String imagePath, String imagePosition, String showIcon,
            Double imageCoveragePercent, String imageCoverageWarning,
            String figureType, String textAlignment, String verticalTextAlignment,
            String labelExpression,
            String fontName, Integer fontSize, String fontStyle,
            String gradient, String borderType, Boolean deriveLineColor,
            Integer outlineOpacity, String lineStyle,
            String parentViewObjectId) {
        this(viewObjectId, elementId, elementName, elementType,
                x, y, width, height,
                fillColor, lineColor, fontColor, opacity, lineWidth,
                imagePath, imagePosition, showIcon,
                imageCoveragePercent, imageCoverageWarning,
                figureType, textAlignment, verticalTextAlignment,
                labelExpression,
                fontName, fontSize, fontStyle,
                gradient, borderType, deriveLineColor, outlineOpacity, lineStyle,
                null, null, null, null,
                parentViewObjectId, List.of(), List.of());
    }

    /**
     * Constructor matching the shape that predates parent reporting but carries the displaced-object
     * lists. Delegates with a null parent, omitted from JSON, so a call site that has not yet
     * learned to name a container keeps serializing exactly as it did.
     */
    public ViewObjectDto(
            String viewObjectId, String elementId, String elementName, String elementType,
            int x, int y, int width, int height,
            String fillColor, String lineColor, String fontColor,
            Integer opacity, Integer lineWidth,
            String imagePath, String imagePosition, String showIcon,
            Double imageCoveragePercent, String imageCoverageWarning,
            String figureType, String textAlignment, String verticalTextAlignment,
            String labelExpression,
            String fontName, Integer fontSize, String fontStyle,
            String gradient, String borderType, Boolean deriveLineColor,
            Integer outlineOpacity, String lineStyle,
            String anchorTarget, String anchorEdge, Integer anchorDx, Integer anchorDy,
            List<MovedViewObjectDto> movedObjects, List<MovedViewObjectDto> resizedAncestors) {
        this(viewObjectId, elementId, elementName, elementType,
                x, y, width, height,
                fillColor, lineColor, fontColor, opacity, lineWidth,
                imagePath, imagePosition, showIcon,
                imageCoveragePercent, imageCoverageWarning,
                figureType, textAlignment, verticalTextAlignment,
                labelExpression,
                fontName, fontSize, fontStyle,
                gradient, borderType, deriveLineColor, outlineOpacity, lineStyle,
                anchorTarget, anchorEdge, anchorDx, anchorDy,
                null, movedObjects, resizedAncestors);
    }

    /**
     * Constructor matching the prior 30-field shape (no anchor fields). Delegates to the
     * canonical 34-field constructor with four trailing nulls (an un-anchored object omits
     * the anchor fields from JSON via NON_NULL). Preserves existing call sites byte-identically.
     */
    public ViewObjectDto(
            String viewObjectId, String elementId, String elementName, String elementType,
            int x, int y, int width, int height,
            String fillColor, String lineColor, String fontColor,
            Integer opacity, Integer lineWidth,
            String imagePath, String imagePosition, String showIcon,
            Double imageCoveragePercent, String imageCoverageWarning,
            String figureType, String textAlignment, String verticalTextAlignment,
            String labelExpression,
            String fontName, Integer fontSize, String fontStyle,
            String gradient, String borderType, Boolean deriveLineColor,
            Integer outlineOpacity, String lineStyle) {
        this(viewObjectId, elementId, elementName, elementType,
                x, y, width, height,
                fillColor, lineColor, fontColor, opacity, lineWidth,
                imagePath, imagePosition, showIcon,
                imageCoveragePercent, imageCoverageWarning,
                figureType, textAlignment, verticalTextAlignment,
                labelExpression,
                fontName, fontSize, fontStyle,
                gradient, borderType, deriveLineColor, outlineOpacity, lineStyle,
                null, null, null, null);
    }

    /**
     * Constructor matching the 22-field shape (no typography/
     * gradient/borderType/deriveLineColor/outlineOpacity/lineStyle fields). Delegates to the canonical
     * 30-field constructor with eight trailing nulls. Preserves existing call sites byte-identically.
     */
    public ViewObjectDto(
            String viewObjectId, String elementId, String elementName, String elementType,
            int x, int y, int width, int height,
            String fillColor, String lineColor, String fontColor,
            Integer opacity, Integer lineWidth,
            String imagePath, String imagePosition, String showIcon,
            Double imageCoveragePercent, String imageCoverageWarning,
            String figureType, String textAlignment, String verticalTextAlignment,
            String labelExpression) {
        this(viewObjectId, elementId, elementName, elementType,
                x, y, width, height,
                fillColor, lineColor, fontColor, opacity, lineWidth,
                imagePath, imagePosition, showIcon,
                imageCoveragePercent, imageCoverageWarning,
                figureType, textAlignment, verticalTextAlignment,
                labelExpression,
                null, null, null, null, null, null, null, null);
    }

    /**
     * Constructor matching the prior 21-field shape (no labelExpression).
     * Delegates to the canonical 30-field constructor with nine trailing nulls.
     */
    public ViewObjectDto(
            String viewObjectId, String elementId, String elementName, String elementType,
            int x, int y, int width, int height,
            String fillColor, String lineColor, String fontColor,
            Integer opacity, Integer lineWidth,
            String imagePath, String imagePosition, String showIcon,
            Double imageCoveragePercent, String imageCoverageWarning,
            String figureType, String textAlignment, String verticalTextAlignment) {
        this(viewObjectId, elementId, elementName, elementType,
                x, y, width, height,
                fillColor, lineColor, fontColor, opacity, lineWidth,
                imagePath, imagePosition, showIcon,
                imageCoveragePercent, imageCoverageWarning,
                figureType, textAlignment, verticalTextAlignment,
                null,
                null, null, null, null, null, null, null, null);
    }

    /**
     * Constructor matching the prior 18-field shape
     * (styling + image fields, no figureType/textAlignment/verticalTextAlignment). Delegates to the
     * canonical constructor with trailing nulls.
     */
    public ViewObjectDto(
            String viewObjectId, String elementId, String elementName, String elementType,
            int x, int y, int width, int height,
            String fillColor, String lineColor, String fontColor,
            Integer opacity, Integer lineWidth,
            String imagePath, String imagePosition, String showIcon,
            Double imageCoveragePercent, String imageCoverageWarning) {
        this(viewObjectId, elementId, elementName, elementType,
                x, y, width, height,
                fillColor, lineColor, fontColor, opacity, lineWidth,
                imagePath, imagePosition, showIcon,
                imageCoveragePercent, imageCoverageWarning,
                null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    /**
     * Constructor with styling but no image fields (backward compat).
     */
    public ViewObjectDto(
            String viewObjectId,
            String elementId,
            String elementName,
            String elementType,
            int x, int y, int width, int height,
            String fillColor, String lineColor, String fontColor,
            Integer opacity, Integer lineWidth) {
        this(viewObjectId, elementId, elementName, elementType,
                x, y, width, height,
                fillColor, lineColor, fontColor, opacity, lineWidth,
                null, null, null, null, null);
    }

    /**
     * Convenience constructor without styling or image fields (backward compat).
     * All optional fields default to null (omitted from JSON via NON_NULL).
     */
    public ViewObjectDto(
            String viewObjectId,
            String elementId,
            String elementName,
            String elementType,
            int x, int y, int width, int height) {
        this(viewObjectId, elementId, elementName, elementType,
                x, y, width, height, null, null, null, null, null);
    }
}
