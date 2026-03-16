package net.vheerden.archi.mcp.response.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Data Transfer Object for a visual element placed on a view (Story 7-7, 11-2).
 *
 * <p>Represents the created or found diagram object on a view, including
 * its unique view object ID, the referenced model element, position/size,
 * and optional visual styling properties.</p>
 *
 * <p><strong>Story 11-2:</strong> Added optional styling fields (fillColor,
 * lineColor, fontColor, opacity, lineWidth). These are omitted from JSON
 * when null (i.e., when the object uses Archi's default styling).</p>
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
    Integer lineWidth
) {

    /**
     * Convenience constructor without styling fields (backward compat).
     * Styling fields default to null (omitted from JSON via NON_NULL).
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
