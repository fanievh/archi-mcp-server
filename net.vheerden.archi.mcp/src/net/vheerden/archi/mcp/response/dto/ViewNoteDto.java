package net.vheerden.archi.mcp.response.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Data Transfer Object for a text note on a view (Story 8-6, 11-2).
 *
 * <p>Notes are diagram-only objects (not ArchiMate model elements) used to
 * annotate design decisions, add comments, or provide context on diagrams.</p>
 *
 * <p><strong>Story 11-2:</strong> Added optional styling fields (fillColor,
 * lineColor, fontColor, opacity, lineWidth). Omitted from JSON when null.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ViewNoteDto(
    String viewObjectId,
    String content,
    int x,
    int y,
    int width,
    int height,
    String parentViewObjectId,
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
    public ViewNoteDto(
            String viewObjectId,
            String content,
            int x, int y, int width, int height,
            String parentViewObjectId) {
        this(viewObjectId, content, x, y, width, height,
                parentViewObjectId,
                null, null, null, null, null);
    }
}
