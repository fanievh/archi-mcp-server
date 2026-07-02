package net.vheerden.archi.mcp.response.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Data Transfer Object for a visual connection on a view.
 *
 * <p>Represents a connection between two view objects, linked to a model
 * relationship. The optional bendpoints list defines routing control points;
 * null means a straight line (omitted from JSON via NON_NULL).</p>
 *
 * <p>Added absolute bendpoint coordinates and
 * anchor points for easier LLM consumption. The server converts between
 * absolute canvas coordinates and Archi's native relative-offset format.</p>
 *
 * <p>Added optional connection styling fields
 * (lineColor, lineWidth, fontColor). Omitted from JSON when null.</p>
 *
 * <p>Added optional typography fields
 * ({@code fontName}, {@code fontSize}, {@code fontStyle}). Note: {@code lineStyle} is a
 * view-object property in Archi 5.8 (see {@link ViewObjectDto}), NOT a connection
 * property — empirical correction.</p>
 *
 * <p><strong>v1.6:</strong> Added {@code labelExpression} so that
 * {@code get-view-contents} surfaces the connection labelExpression set via Archi GUI
 * or future write-side tooling. Verified via {@code javap} that
 * {@code IDiagramModelConnection} transitively implements {@code IFeatures} via
 * {@code IConnectable → IDiagramModelComponent → IArchimateModelObject → IFeatures}.
 * Other view-object-only fields ({@code gradient}, {@code borderType},
 * {@code deriveLineColor}, {@code outlineOpacity}, {@code lineStyle}) remain absent
 * from connections — those are typed setters on {@code IDiagramModelObject} only.</p>
 *
 * <p>Added {@code relativePosition} so {@code get-view-contents} surfaces a connection's
 * label-offset anchor (the "Label Offset" available on newer Archi: a compass bitmask offsetting a
 * {@code Middle} label off its own line/endpoint). The value is the platform's own anchor bitmask
 * ({@code NORTH=1}, {@code SOUTH=4}, {@code WEST=8}, {@code EAST=16}, {@code NE=17}, {@code NW=9},
 * {@code SE=20}, {@code SW=12}). It is {@code null} — and so omitted from JSON via NON_NULL — when the
 * running platform lacks the feature (older Archi) or when the anchor is the un-offset default
 * ({@code CENTER=2}), keeping the wire byte-identical to before on both older platforms and un-offset
 * labels. This is the read-back channel for a label offset that is otherwise invisible to
 * MCP-side verification (neither {@code assess-layout} nor {@code export-view} reflect it).</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ViewConnectionDto(
    String viewConnectionId,
    String relationshipId,
    String relationshipType,
    String sourceViewObjectId,
    String targetViewObjectId,
    List<BendpointDto> bendpoints,
    List<AbsoluteBendpointDto> absoluteBendpoints,
    AnchorPointDto sourceAnchor,
    AnchorPointDto targetAnchor,
    Integer textPosition,
    String lineColor,
    Integer lineWidth,
    String fontColor,
    Boolean nameVisible,
    String fontName,
    Integer fontSize,
    String fontStyle,
    String labelExpression,
    Integer relativePosition
) {

    /**
     * Constructor matching the prior 18-field shape (with labelExpression but no
     * relativePosition). Delegates to the canonical 19-field constructor with one trailing null
     * for relativePosition. Preserves every existing 18-arg call site (collect/read-back,
     * prepare-update-connection, bulk-mutate, and the shorter convenience constructors that
     * delegate through this arity) byte-identically.
     */
    public ViewConnectionDto(
            String viewConnectionId,
            String relationshipId,
            String relationshipType,
            String sourceViewObjectId,
            String targetViewObjectId,
            List<BendpointDto> bendpoints,
            List<AbsoluteBendpointDto> absoluteBendpoints,
            AnchorPointDto sourceAnchor,
            AnchorPointDto targetAnchor,
            Integer textPosition,
            String lineColor,
            Integer lineWidth,
            String fontColor,
            Boolean nameVisible,
            String fontName,
            Integer fontSize,
            String fontStyle,
            String labelExpression) {
        this(viewConnectionId, relationshipId, relationshipType,
                sourceViewObjectId, targetViewObjectId, bendpoints,
                absoluteBendpoints, sourceAnchor, targetAnchor, textPosition,
                lineColor, lineWidth, fontColor, nameVisible,
                fontName, fontSize, fontStyle, labelExpression, null);
    }

    /**
     * Constructor matching the prior 14-field shape (no typography).
     * Delegates to the canonical 18-field constructor with four trailing nulls
     * (3 typography fields + 1 labelExpression).
     * Preserves existing call sites byte-identically.
     */
    public ViewConnectionDto(
            String viewConnectionId,
            String relationshipId,
            String relationshipType,
            String sourceViewObjectId,
            String targetViewObjectId,
            List<BendpointDto> bendpoints,
            List<AbsoluteBendpointDto> absoluteBendpoints,
            AnchorPointDto sourceAnchor,
            AnchorPointDto targetAnchor,
            Integer textPosition,
            String lineColor,
            Integer lineWidth,
            String fontColor,
            Boolean nameVisible) {
        this(viewConnectionId, relationshipId, relationshipType,
                sourceViewObjectId, targetViewObjectId, bendpoints,
                absoluteBendpoints, sourceAnchor, targetAnchor, textPosition,
                lineColor, lineWidth, fontColor, nameVisible,
                null, null, null, null);
    }

    /**
     * Constructor matching the prior 17-field shape (with typography
     * but no labelExpression). Delegates to the canonical 18-field constructor with one
     * trailing null for labelExpression. Preserves existing prepare-update-connection /
     * bulk-mutate connection call sites byte-identically.
     */
    public ViewConnectionDto(
            String viewConnectionId,
            String relationshipId,
            String relationshipType,
            String sourceViewObjectId,
            String targetViewObjectId,
            List<BendpointDto> bendpoints,
            List<AbsoluteBendpointDto> absoluteBendpoints,
            AnchorPointDto sourceAnchor,
            AnchorPointDto targetAnchor,
            Integer textPosition,
            String lineColor,
            Integer lineWidth,
            String fontColor,
            Boolean nameVisible,
            String fontName,
            Integer fontSize,
            String fontStyle) {
        this(viewConnectionId, relationshipId, relationshipType,
                sourceViewObjectId, targetViewObjectId, bendpoints,
                absoluteBendpoints, sourceAnchor, targetAnchor, textPosition,
                lineColor, lineWidth, fontColor, nameVisible,
                fontName, fontSize, fontStyle, null);
    }

    /**
     * Convenience constructor without absolute bendpoint or styling fields.
     * New fields default to null (omitted from JSON via NON_NULL).
     */
    public ViewConnectionDto(
            String viewConnectionId,
            String relationshipId,
            String relationshipType,
            String sourceViewObjectId,
            String targetViewObjectId,
            List<BendpointDto> bendpoints) {
        this(viewConnectionId, relationshipId, relationshipType,
                sourceViewObjectId, targetViewObjectId, bendpoints,
                null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    /**
     * Convenience constructor without styling fields.
     * Styling fields default to null (omitted from JSON via NON_NULL).
     */
    public ViewConnectionDto(
            String viewConnectionId,
            String relationshipId,
            String relationshipType,
            String sourceViewObjectId,
            String targetViewObjectId,
            List<BendpointDto> bendpoints,
            List<AbsoluteBendpointDto> absoluteBendpoints,
            AnchorPointDto sourceAnchor,
            AnchorPointDto targetAnchor,
            Integer textPosition) {
        this(viewConnectionId, relationshipId, relationshipType,
                sourceViewObjectId, targetViewObjectId, bendpoints,
                absoluteBendpoints, sourceAnchor, targetAnchor, textPosition,
                null, null, null, null,
                null, null, null, null);
    }
}
