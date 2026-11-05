package net.vheerden.archi.mcp.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;

import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.model.routing.FailedConnection;
import net.vheerden.archi.mcp.model.routing.MoveRecommendation;

/**
 * The view-object names the {@code auto-route-connections} response prints beside the ids it
 * reports: the two ends of each failed connection, and each element a move recommendation names.
 *
 * <p>Pure name resolution, read from the model as it stands; nothing here decides what is reported.
 * Kept out of the accessor facade so its line-count ratchet does not carry a lookup table.</p>
 */
final class AutoRouteNameLookup {

    private AutoRouteNameLookup() {
    }

    /**
     * View-object id to display name for every end of a targeted connection and every element a
     * recommendation names. Empty when the call has neither a failed connection nor a
     * recommendation to report, because nothing reads the map then.
     *
     * <p>A recommendation's element id is the view-object id the router obstacle carried, so an id
     * that is not an end of a targeted connection is resolved through the model.</p>
     */
    static Map<String, String> viewObjectNames(IArchimateModel model,
            List<IDiagramModelConnection> targetConnections,
            List<FailedConnection> failedConnections,
            List<MoveRecommendation> moveRecommendations) {
        Map<String, String> names = new LinkedHashMap<>();
        if (failedConnections.isEmpty() && moveRecommendations.isEmpty()) {
            return names;
        }
        for (IDiagramModelConnection conn : targetConnections) {
            if (conn.getSource() instanceof IDiagramModelObject src) {
                names.put(src.getId(), src.getName());
            }
            if (conn.getTarget() instanceof IDiagramModelObject tgt) {
                names.put(tgt.getId(), tgt.getName());
            }
        }
        for (MoveRecommendation rec : moveRecommendations) {
            if (!names.containsKey(rec.elementId())) {
                EObject obj = ArchimateModelUtils.getObjectByID(model, rec.elementId());
                if (obj instanceof IDiagramModelObject dmo) {
                    names.put(rec.elementId(), dmo.getName());
                }
            }
        }
        return names;
    }
}
