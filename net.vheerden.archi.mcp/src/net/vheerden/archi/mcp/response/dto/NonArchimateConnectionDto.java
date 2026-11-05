package net.vheerden.archi.mcp.response.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A connection drawn on a view that is not an ArchiMate one: no relationship stands behind it. Archi
 * draws these from or to a note, a group, an image or a view reference, and between any two objects or
 * connections when the user picks the plain "Connection" tool. An ArchiMate connection whose relationship
 * is missing, which only a damaged model holds, is listed as one too: no relationship stands behind it
 * either.
 *
 * <p>Uses the {@link ViewConnectionDto} names wherever the concept is the same. Each end id names a view
 * object <em>or</em> a connection, as Archi allows either. There are no anchors or render faces: those
 * are derived for ArchiMate figures only, and would be guesses here.</p>
 *
 * <p>The row is read-only: no tool writes a plain connection's styling, label settings or properties,
 * so they are reported as Archi or a script left them.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NonArchimateConnectionDto(
    String viewConnectionId,
    String sourceViewObjectId,
    String targetViewObjectId,
    String name,                                   // the connection's own label; null when empty
    String documentation,                          // null when empty
    List<BendpointDto> bendpoints,                 // the stored relative values; null when none
    /**
     * The bendpoints on the canvas, derived as they are for an ArchiMate connection. Null when the
     * connection has none, or when either end is a connection rather than a view object.
     */
    List<AbsoluteBendpointDto> absoluteBendpoints,
    Integer textPosition,                          // 0 source, 1 middle, 2 target
    String lineColor,                              // #RRGGBB; null when default
    Integer lineWidth,                             // null when 1
    String fontColor,                              // #RRGGBB; null when default
    Boolean nameVisible,                           // FALSE when the label is hidden; null when shown
    String fontName,
    Integer fontSize,
    String fontStyle,
    String labelExpression,                        // the label template; null when none
    Integer relativePosition,                      // the label offset anchor; null when centred
    String lineStyle,                              // "dashed" or "dotted"; null when solid
    String sourceArrowhead,                        // "fill", "hollow" or "line"; null when none
    String targetArrowhead,                        // "fill", "hollow" or "line"; null when none
    String textAlignment,                          // "left" or "right"; null when centred
    List<Map<String, String>> properties           // key/value rows as on an element; null when none
) {
}
