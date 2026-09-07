package net.vheerden.archi.mcp.response.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Data Transfer Object for a text note on a view.
 *
 * <p>Notes are diagram-only objects (not ArchiMate model elements) used to
 * annotate design decisions, add comments, or provide context on diagrams.</p>
 *
 * <p>Added optional styling fields (fillColor,
 * lineColor, fontColor, opacity, lineWidth). Omitted from JSON when null.</p>
 *
 * <p><strong>v1.6:</strong> Closes the read-back symmetry gap. Adds
 * {@code labelExpression} and {@code fontName}/{@code fontSize}/{@code fontStyle}/
 * {@code gradient}/{@code borderType}/{@code deriveLineColor}/{@code outlineOpacity}/
 * {@code lineStyle} so that {@code get-view-contents} surfaces every v1.5
 * styling field that the write tools accept. {@code borderType} (dogear/rectangle/none)
 * is the note-specific surface; {@code figureType} remains absent per the
 * Notes do not expose figureType. All fields are omitted
 * from JSON when null via NON_NULL.</p>
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
    Integer lineWidth,
    String note,
    String imagePath,
    String imagePosition,
    String showIcon,
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
    /**
     * Placement-time disclosures for THIS call — currently the one that says the rectangle just
     * placed lands on a route already drawn on the view.
     *
     * <p>Present only on the response to a placement call, and on every arm of one: the entity is
     * built once in the prepare and is the same object the immediate response returns, the
     * proposal carries under {@code preview}, and the batched response projects — so approval
     * mode, which discards {@code nextSteps} wholesale, cannot lose it. That is why the
     * disclosure is a field here rather than a next step.</p>
     *
     * <p><strong>Absent on the read path.</strong> {@code get-view-contents} reports what is on
     * the view, not the outcome of a call that put it there, and a warning about a placement made
     * at some earlier time would be a claim about routes that have since changed. Null there, and
     * omitted from JSON.</p>
     */
    java.util.List<StructuredWarningDto> structuredWarnings
) {

    /**
     * Constructor matching the prior 16-field shape
     * (styling + note + image fields, no textAlignment/verticalTextAlignment). Delegates to the
     * canonical 27-field constructor with eleven trailing nulls
     * (2 predecessor styling row + 9 v1.5 styling fields). Notes do not surface
     * figureType is not exposed for notes.
     */
    public ViewNoteDto(
            String viewObjectId, String content,
            int x, int y, int width, int height,
            String parentViewObjectId,
            String fillColor, String lineColor, String fontColor,
            Integer opacity, Integer lineWidth,
            String note, String imagePath, String imagePosition, String showIcon) {
        this(viewObjectId, content, x, y, width, height,
                parentViewObjectId,
                fillColor, lineColor, fontColor, opacity, lineWidth,
                note, imagePath, imagePosition, showIcon,
                null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Constructor matching the prior 18-field shape
     * (predecessor styling row added textAlignment/verticalTextAlignment, but no
     * labelExpression / fontName / fontSize / fontStyle / gradient / borderType /
     * deriveLineColor / outlineOpacity / lineStyle). Delegates to the canonical 27-field
     * constructor with nine trailing nulls for the v1.5 styling fields. Preserves
     * existing prepareAddNoteToView call sites byte-identically.
     */
    public ViewNoteDto(
            String viewObjectId, String content,
            int x, int y, int width, int height,
            String parentViewObjectId,
            String fillColor, String lineColor, String fontColor,
            Integer opacity, Integer lineWidth,
            String note, String imagePath, String imagePosition, String showIcon,
            String textAlignment, String verticalTextAlignment) {
        this(viewObjectId, content, x, y, width, height,
                parentViewObjectId,
                fillColor, lineColor, fontColor, opacity, lineWidth,
                note, imagePath, imagePosition, showIcon,
                textAlignment, verticalTextAlignment,
                null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Full constructor without image fields (backward compat with styling + note).
     */
    public ViewNoteDto(
            String viewObjectId,
            String content,
            int x, int y, int width, int height,
            String parentViewObjectId,
            String fillColor, String lineColor, String fontColor,
            Integer opacity, Integer lineWidth,
            String note) {
        this(viewObjectId, content, x, y, width, height,
                parentViewObjectId,
                fillColor, lineColor, fontColor, opacity, lineWidth,
                note, null, null, null);
    }

    /**
     * Full constructor without note or image fields (backward compat with styling).
     */
    public ViewNoteDto(
            String viewObjectId,
            String content,
            int x, int y, int width, int height,
            String parentViewObjectId,
            String fillColor, String lineColor, String fontColor,
            Integer opacity, Integer lineWidth) {
        this(viewObjectId, content, x, y, width, height,
                parentViewObjectId,
                fillColor, lineColor, fontColor, opacity, lineWidth, null);
    }

    /**
     * Convenience constructor without styling, note, or image fields (backward compat).
     * All optional fields default to null (omitted from JSON via NON_NULL).
     */
    public ViewNoteDto(
            String viewObjectId,
            String content,
            int x, int y, int width, int height,
            String parentViewObjectId) {
        this(viewObjectId, content, x, y, width, height,
                parentViewObjectId,
                null, null, null, null, null, null);
    }
}
