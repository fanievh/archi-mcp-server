package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Data Transfer Object for a visual grouping rectangle on a view (Story 8-6, 11-2).
 *
 * <p>Groups are diagram-only objects (not ArchiMate model elements) used to
 * visually organize related elements, label tiers, or annotate sections.
 * Elements can be nested inside groups using add-to-view with parentViewObjectId.</p>
 *
 * <p><strong>Story 11-2:</strong> Added optional styling fields (fillColor,
 * lineColor, fontColor, opacity, lineWidth). Omitted from JSON when null.</p>
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
    String showIcon
) {

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
