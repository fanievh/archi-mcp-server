package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Data Transfer Object for a visual grouping rectangle on a view.
 *
 * <p>Groups are diagram-only objects (not ArchiMate model elements) used to
 * visually organize related elements, label tiers, or annotate sections.
 * Elements can be nested inside groups using add-to-view with parentViewObjectId.</p>
 *
 * <p>Added optional styling fields (fillColor,
 * lineColor, fontColor, opacity, lineWidth). Omitted from JSON when null.</p>
 *
 * <p><strong>v1.6:</strong> Closes the read-back symmetry gap. Adds
 * {@code labelExpression} and {@code fontName}/{@code fontSize}/{@code fontStyle}/
 * {@code gradient}/{@code deriveLineColor}/{@code outlineOpacity}/{@code lineStyle}
 * so that {@code get-view-contents} surfaces every v1.5 styling field that the
 * write tools accept. {@code borderType} is intentionally absent — groups own the
 * orthogonal {@code figureType} (tabbed/rectangular) surface per the figureType/borderType disambiguation;
 * dogear/rectangle/none semantics belong on {@link ViewNoteDto} only. All fields are
 * omitted from JSON when null via NON_NULL.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ViewGroupDto(
    String viewObjectId,
    String label,
    int x,
    int y,
    int width,
    int height,
    String parentViewObjectId,
    List<String> childViewObjectIds,
    String fillColor,
    String lineColor,
    String fontColor,
    Integer opacity,
    Integer lineWidth,
    String imagePath,
    String imagePosition,
    String showIcon,
    String figureType,
    String textAlignment,
    String verticalTextAlignment,
    String labelExpression,
    String fontName,
    Integer fontSize,
    String fontStyle,
    String gradient,
    Boolean deriveLineColor,
    Integer outlineOpacity,
    String lineStyle,
    // add-group-to-view only: the parent the placement receded and the fill it now holds
    @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = RecededParentDto.NoneRecorded.class)
    RecededParentDto recededParent
) {

    /** The 27-field shape every read builds: no recede to report. */
    public ViewGroupDto(
            String viewObjectId, String label,
            int x, int y, int width, int height,
            String parentViewObjectId, List<String> childViewObjectIds,
            String fillColor, String lineColor, String fontColor,
            Integer opacity, Integer lineWidth,
            String imagePath, String imagePosition, String showIcon,
            String figureType, String textAlignment, String verticalTextAlignment,
            String labelExpression, String fontName, Integer fontSize, String fontStyle,
            String gradient, Boolean deriveLineColor, Integer outlineOpacity, String lineStyle) {
        this(viewObjectId, label, x, y, width, height,
                parentViewObjectId, childViewObjectIds,
                fillColor, lineColor, fontColor, opacity, lineWidth,
                imagePath, imagePosition, showIcon,
                figureType, textAlignment, verticalTextAlignment,
                labelExpression, fontName, fontSize, fontStyle, gradient, deriveLineColor, outlineOpacity,
                lineStyle, null);
    }

    /**
     * The 19-field shape {@code add-group-to-view} builds, with the parent it receded (or a report
     * recording none).
     */
    public ViewGroupDto(
            String viewObjectId, String label,
            int x, int y, int width, int height,
            String parentViewObjectId, List<String> childViewObjectIds,
            String fillColor, String lineColor, String fontColor,
            Integer opacity, Integer lineWidth,
            String imagePath, String imagePosition, String showIcon,
            String figureType, String textAlignment, String verticalTextAlignment,
            RecededParentDto recededParent) {
        this(viewObjectId, label, x, y, width, height,
                parentViewObjectId, childViewObjectIds,
                fillColor, lineColor, fontColor, opacity, lineWidth,
                imagePath, imagePosition, showIcon,
                figureType, textAlignment, verticalTextAlignment,
                null, null, null, null, null, null, null, null, recededParent);
    }

    /**
     * Constructor matching the prior 16-field shape
     * (styling + image fields, no figureType/textAlignment/verticalTextAlignment). Delegates to the
     * canonical 27-field constructor with eleven trailing nulls
     * (3 predecessor styling row + 8 v1.5 styling fields).
     */
    public ViewGroupDto(
            String viewObjectId, String label,
            int x, int y, int width, int height,
            String parentViewObjectId, List<String> childViewObjectIds,
            String fillColor, String lineColor, String fontColor,
            Integer opacity, Integer lineWidth,
            String imagePath, String imagePosition, String showIcon) {
        this(viewObjectId, label, x, y, width, height,
                parentViewObjectId, childViewObjectIds,
                fillColor, lineColor, fontColor, opacity, lineWidth,
                imagePath, imagePosition, showIcon,
                null, null, null,
                null, null, null, null, null, null, null, null);
    }

    /**
     * Constructor matching the prior 19-field shape
     * (predecessor styling row added figureType/textAlignment/verticalTextAlignment, but no
     * labelExpression / fontName / fontSize / fontStyle / gradient / deriveLineColor /
     * outlineOpacity / lineStyle). Delegates to the canonical 27-field constructor with eight
     * trailing nulls for the v1.5 styling fields. Preserves existing
     * prepareAddGroupToView call sites byte-identically.
     */
    public ViewGroupDto(
            String viewObjectId, String label,
            int x, int y, int width, int height,
            String parentViewObjectId, List<String> childViewObjectIds,
            String fillColor, String lineColor, String fontColor,
            Integer opacity, Integer lineWidth,
            String imagePath, String imagePosition, String showIcon,
            String figureType, String textAlignment, String verticalTextAlignment) {
        this(viewObjectId, label, x, y, width, height,
                parentViewObjectId, childViewObjectIds,
                fillColor, lineColor, fontColor, opacity, lineWidth,
                imagePath, imagePosition, showIcon,
                figureType, textAlignment, verticalTextAlignment,
                null, null, null, null, null, null, null, null);
    }

    /**
     * Constructor with styling but no image fields (backward compat).
     */
    public ViewGroupDto(
            String viewObjectId,
            String label,
            int x, int y, int width, int height,
            String parentViewObjectId,
            List<String> childViewObjectIds,
            String fillColor, String lineColor, String fontColor,
            Integer opacity, Integer lineWidth) {
        this(viewObjectId, label, x, y, width, height,
                parentViewObjectId, childViewObjectIds,
                fillColor, lineColor, fontColor, opacity, lineWidth,
                null, null, null);
    }

    /**
     * Convenience constructor without styling or image fields (backward compat).
     * All optional fields default to null (omitted from JSON via NON_NULL).
     */
    public ViewGroupDto(
            String viewObjectId,
            String label,
            int x, int y, int width, int height,
            String parentViewObjectId,
            List<String> childViewObjectIds) {
        this(viewObjectId, label, x, y, width, height,
                parentViewObjectId, childViewObjectIds,
                null, null, null, null, null);
    }
}
