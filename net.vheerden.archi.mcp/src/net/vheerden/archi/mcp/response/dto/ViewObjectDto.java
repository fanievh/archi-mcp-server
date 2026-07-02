package net.vheerden.archi.mcp.response.dto;

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
    Integer anchorDy
) {

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
