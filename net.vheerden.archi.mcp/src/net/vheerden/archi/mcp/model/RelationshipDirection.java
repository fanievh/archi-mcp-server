package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;

import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;

/**
 * Keeps a connection running the way its relationship runs.
 *
 * <p>A connection may be asked for from either end: from the relationship's target's object to
 * its source's. Archi does not draw such a connection as asked. Connecting it re-points the
 * relationship in the model to the connected elements, so the relationship would turn round for
 * every view, and each connection already drawn for it would then contradict it. Archi itself
 * only ever draws a relationship from an occurrence of its source to an occurrence of its target.
 * A connection asked for the other way round is therefore drawn the relationship's way: its two
 * ends exchanged, and with them the route and the label position the caller gave, so that the
 * line on the canvas is the one asked for.</p>
 */
final class RelationshipDirection {

    private RelationshipDirection() {}

    /**
     * True when a connection from {@code source} to {@code target} would run against a
     * relationship from {@code relSource} to {@code relTarget}: its source shows the
     * relationship's target, and its target the relationship's source. Never true for a
     * relationship from an element to itself.
     */
    static boolean runsBackwards(IArchimateConcept relSource, IArchimateConcept relTarget,
            IDiagramModelArchimateObject source, IDiagramModelArchimateObject target) {
        return relSource != null && relTarget != null
                && !relSource.getId().equals(relTarget.getId())
                && shows(source, relTarget) && shows(target, relSource);
    }

    private static boolean shows(IDiagramModelArchimateObject object, IArchimateConcept concept) {
        return object.getArchimateElement() != null && object.getArchimateElement().getId().equals(concept.getId());
    }

    /**
     * The same relative route seen from the other end: the points in reverse order, each with its
     * offset from the source and its offset from the target exchanged. Null stays null.
     */
    static List<BendpointDto> reversed(List<BendpointDto> bendpoints) {
        if (bendpoints == null) {
            return null;
        }
        List<BendpointDto> reversed = new ArrayList<>(bendpoints.size());
        for (BendpointDto bp : bendpoints) {
            reversed.add(new BendpointDto(bp.endX(), bp.endY(), bp.startX(), bp.startY()));
        }
        Collections.reverse(reversed);
        return reversed;
    }

    /** The same canvas points in reverse order. Null stays null. */
    static List<AbsoluteBendpointDto> reversedAbsolute(List<AbsoluteBendpointDto> bendpoints) {
        if (bendpoints == null) {
            return null;
        }
        List<AbsoluteBendpointDto> reversed = new ArrayList<>(bendpoints);
        Collections.reverse(reversed);
        return reversed;
    }

    /** A label placed near one end, placed near the same object once the ends are exchanged. */
    static Integer reversedTextPosition(Integer textPosition) {
        if (textPosition == null) {
            return null;
        }
        return switch (textPosition) {
        case IDiagramModelConnection.CONNECTION_TEXT_POSITION_SOURCE ->
                IDiagramModelConnection.CONNECTION_TEXT_POSITION_TARGET;
        case IDiagramModelConnection.CONNECTION_TEXT_POSITION_TARGET ->
                IDiagramModelConnection.CONNECTION_TEXT_POSITION_SOURCE;
        default -> textPosition;
        };
    }
}
