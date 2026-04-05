package net.vheerden.archi.mcp.response.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Data Transfer Object for visual position metadata of an element in a view.
 *
 * <p>Captures the view object ID, element ID, and x, y, width, height of
 * an element's visual representation in a diagram. The viewObjectId
 * uniquely identifies the diagram object on the view (distinct from the
 * model element ID), enabling connection placement via add-connection-to-view.</p>
 *
 * <p><strong>Story 8-6:</strong> Added parentViewObjectId to track elements
 * nested inside visual groups.</p>
 *
 * <p><strong>Story 11-2:</strong> Added optional styling fields (fillColor,
 * lineColor, fontColor, opacity, lineWidth). Omitted from JSON when null.</p>
 *
 * <p><strong>Story C4:</strong> Added optional image fields (imagePath,
 * imagePosition, showIcon). Omitted from JSON when null.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ViewNodeDto(
    String viewObjectId,
    String elementId,
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
    String imagePath,
    String imagePosition,
    String showIcon
) {
    /**
     * Convenience constructor without image fields (backward compat).
     */
    public ViewNodeDto(String viewObjectId, String elementId,
            int x, int y, int width, int height,
            String parentViewObjectId,
            String fillColor, String lineColor, String fontColor,
            Integer opacity, Integer lineWidth) {
        this(viewObjectId, elementId, x, y, width, height,
                parentViewObjectId, fillColor, lineColor, fontColor,
                opacity, lineWidth, null, null, null);
    }

    /**
     * Convenience constructor without styling or image fields (backward compat).
     */
    public ViewNodeDto(String viewObjectId, String elementId,
            int x, int y, int width, int height,
            String parentViewObjectId) {
        this(viewObjectId, elementId, x, y, width, height,
                parentViewObjectId, null, null, null, null, null);
    }

    /**
     * Convenience constructor without parentViewObjectId, styling, or image (top-level element).
     */
    public ViewNodeDto(String viewObjectId, String elementId,
            int x, int y, int width, int height) {
        this(viewObjectId, elementId, x, y, width, height, null);
    }
}
