package net.vheerden.archi.mcp.model;

import static com.archimatetool.model.IDiagramModelConnection.ARROW_FILL_SOURCE;
import static com.archimatetool.model.IDiagramModelConnection.ARROW_FILL_TARGET;
import static com.archimatetool.model.IDiagramModelConnection.ARROW_HOLLOW_SOURCE;
import static com.archimatetool.model.IDiagramModelConnection.ARROW_HOLLOW_TARGET;
import static com.archimatetool.model.IDiagramModelConnection.ARROW_LINE_SOURCE;
import static com.archimatetool.model.IDiagramModelConnection.ARROW_LINE_TARGET;
import static com.archimatetool.model.IDiagramModelConnection.LINE_DASHED;
import static com.archimatetool.model.IDiagramModelConnection.LINE_DOTTED;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;
import net.vheerden.archi.mcp.response.dto.NonArchimateConnectionDto;

/**
 * Reads a connection that is not an ArchiMate one into the row {@code get-view-contents} lists it with.
 *
 * <p>The colour, width and label-visibility values are read off the connection directly rather than
 * through the {@link StylingHelper} readers the ArchiMate rows use: those are typed to the ArchiMate
 * connection and shared by every path that reports one, and each is a single getter the base connection
 * also has. The label expression, label offset and text alignment are read through the connection-typed
 * readers, which accept any connection.</p>
 */
final class NonArchimateConnectionMapper {

    private NonArchimateConnectionMapper() {
    }

    static NonArchimateConnectionDto toDto(IDiagramModelConnection conn) {
        List<BendpointDto> bendpoints = bendpoints(conn);
        int type = conn.getType();
        return new NonArchimateConnectionDto(
                conn.getId(),
                conn.getSource().getId(),
                conn.getTarget().getId(),
                emptyToNull(conn.getName()),
                emptyToNull(conn.getDocumentation()),
                bendpoints,
                absoluteBendpoints(conn, bendpoints),
                conn.getTextPosition(),
                conn.getLineColor(),
                conn.getLineWidth() != 1 ? conn.getLineWidth() : null,
                conn.getFontColor(),
                conn.isNameVisible() ? null : Boolean.FALSE,
                StylingHelper.readConnectionFontName(conn),
                StylingHelper.readConnectionFontSize(conn),
                StylingHelper.readConnectionFontStyle(conn),
                StylingHelper.readConnectionLabelExpression(conn),
                StylingHelper.readConnectionRelativePosition(conn),
                lineStyle(type),
                arrowhead(type, ARROW_FILL_SOURCE, ARROW_LINE_SOURCE, ARROW_HOLLOW_SOURCE),
                arrowhead(type, ARROW_FILL_TARGET, ARROW_LINE_TARGET, ARROW_HOLLOW_TARGET),
                StylingHelper.readConnectionTextAlignment(conn),
                properties(conn));
    }

    /**
     * As on an element row: null when the connection has none, so a bare row reads as before. Shared
     * with the ArchiMate connection row, which reports a connection's properties the same way.
     */
    static List<Map<String, String>> properties(IDiagramModelConnection conn) {
        List<Map<String, String>> properties = DtoMapper.convertProperties(conn.getProperties());
        return properties.isEmpty() ? null : properties;
    }

    private static List<BendpointDto> bendpoints(IDiagramModelConnection conn) {
        if (conn.getBendpoints().isEmpty()) {
            return null;
        }
        List<BendpointDto> result = new ArrayList<>(conn.getBendpoints().size());
        for (IDiagramModelBendpoint bp : conn.getBendpoints()) {
            result.add(new BendpointDto(bp.getStartX(), bp.getStartY(), bp.getEndX(), bp.getEndY()));
        }
        return result;
    }

    /** Derived from both ends' centres, which a connection end does not have. */
    private static List<AbsoluteBendpointDto> absoluteBendpoints(IDiagramModelConnection conn,
            List<BendpointDto> bendpoints) {
        if (bendpoints == null || !(conn.getSource() instanceof IDiagramModelObject source)
                || !(conn.getTarget() instanceof IDiagramModelObject target)) {
            return null;
        }
        int[] sourceCentre = ConnectionResponseBuilder.computeAbsoluteCenter(source);
        int[] targetCentre = ConnectionResponseBuilder.computeAbsoluteCenter(target);
        return ConnectionResponseBuilder.convertRelativeToAbsolute(bendpoints,
                sourceCentre[0], sourceCentre[1], targetCentre[0], targetCentre[1]);
    }

    /** Dashed when both bits are set, as Archi's figure draws it. */
    private static String lineStyle(int type) {
        if ((type & LINE_DASHED) != 0) {
            return "dashed";
        }
        return (type & LINE_DOTTED) != 0 ? "dotted" : null;
    }

    /** One end's arrowhead, in the precedence Archi's figure draws them: fill, then line, then hollow. */
    private static String arrowhead(int type, int fill, int line, int hollow) {
        if ((type & fill) != 0) {
            return "fill";
        }
        if ((type & line) != 0) {
            return "line";
        }
        return (type & hollow) != 0 ? "hollow" : null;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
