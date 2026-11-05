package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;

/**
 * Where an element appears on a view, and whether a relationship already joins two of those
 * places, decided as Archi decides them when it draws a relationship.
 *
 * <p>An element may appear on a view more than once. Archi, dropping an element or dragging a
 * relationship onto a view, connects every occurrence of the related element, and skips a pair
 * only when the same relationship already runs from that source to that target. The direction
 * counts: a connection the other way round between the same two objects does not stop one being
 * drawn, and a connection for the relationship between two other objects does not either.</p>
 */
final class ViewOccurrences {

    private ViewOccurrences() {}

    /** Every object among {@code objects} that shows {@code element}, in the order given. */
    static List<IDiagramModelArchimateObject> of(Collection<IDiagramModelArchimateObject> objects,
            IArchimateConcept element) {
        List<IDiagramModelArchimateObject> found = new ArrayList<>();
        for (IDiagramModelArchimateObject object : objects) {
            if (object.getArchimateElement() != null
                    && object.getArchimateElement().getId().equals(element.getId())) {
                found.add(object);
            }
        }
        return found;
    }

    /**
     * True when a connection for the relationship {@code relationshipId} already runs from
     * {@code source} to {@code target}. Only that direction is checked; for a self-connection the
     * two ends are one object, so a second loop for the same relationship is seen.
     */
    static boolean joined(IConnectable source, IConnectable target, String relationshipId) {
        for (Object o : source.getSourceConnections()) {
            if (o instanceof IDiagramModelArchimateConnection conn && conn.getTarget() == target
                    && conn.getArchimateRelationship() != null
                    && conn.getArchimateRelationship().getId().equals(relationshipId)) {
                return true;
            }
        }
        return false;
    }
}
