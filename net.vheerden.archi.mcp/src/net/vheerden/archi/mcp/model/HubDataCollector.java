package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;

/**
 * The single walk that answers "how many connections does this view object carry?", and the
 * fan-out precondition derived from it.
 *
 * <p>Two tools publish a per-element connection count for the same view — {@code detect-hub-elements}
 * enumerates them and {@code assess-layout} reports which of them are too small for their fan-out —
 * and an agent reads the two in consecutive calls. Two independent walks would eventually disagree
 * on a number the agent has no way to arbitrate, so both read {@link #collect} and the agreement is
 * structural rather than merely tested.</p>
 *
 * <h2>What a connection count means here</h2>
 * <p>Every ArchiMate connection on the view contributes <strong>one to each of its two endpoint
 * ids</strong>. Three consequences are worth stating because each is a place two counters normally
 * drift apart:</p>
 * <ul>
 *   <li>A <strong>self-loop</strong> has both endpoints on the same object, so it contributes
 *       <strong>two</strong> to that object.</li>
 *   <li>A <strong>plain diagram connection</strong> — the line Archi lets you draw to a Note — is
 *       NOT an ArchiMate connection and contributes nothing. So this count can be lower than the
 *       number of lines visibly touching the object.</li>
 *   <li>The count is over the <strong>view</strong>, not the model: a relationship that exists in
 *       the model but was never placed on this view is not here.</li>
 * </ul>
 *
 * <p>This is a different question from the assessor's own hub degree, which skips self-loops
 * because a loop has no neighbour to crowd, and from the hub-port quality face guard, which counts
 * terminals on one face. Three counts, three jobs; this one is the published one.</p>
 */
final class HubDataCollector {

    private HubDataCollector() {}

    /**
     * Collects hub detection data by traversing view objects and counting connections.
     * Also tracks the maximum connection label width per view object for label-aware sizing.
     *
     * @return total connection count on the view
     */
    static int collect(IDiagramModelContainer container,
                       Map<String, Integer> connectionCounts,
                       Map<String, IDiagramModelArchimateObject> viewObjectMap,
                       Map<String, Integer> maxLabelWidths) {
        int totalConnections = 0;
        for (IDiagramModelObject child : container.getChildren()) {
            if (child instanceof IDiagramModelArchimateObject archimateObject) {
                IArchimateElement element = archimateObject.getArchimateElement();
                if (element != null) {
                    viewObjectMap.put(archimateObject.getId(), archimateObject);

                    // Count connections where this object is source
                    for (IDiagramModelConnection conn : archimateObject.getSourceConnections()) {
                        if (conn instanceof IDiagramModelArchimateConnection archConn) {
                            String sourceId = conn.getSource().getId();
                            String targetId = conn.getTarget().getId();
                            connectionCounts.merge(sourceId, 1, Integer::sum);
                            connectionCounts.merge(targetId, 1, Integer::sum);
                            totalConnections++;

                            // Track max label width per element (both ends)
                            String relName = archConn.getArchimateRelationship() != null
                                    ? archConn.getArchimateRelationship().getName() : null;
                            if (relName != null && !relName.isEmpty()) {
                                int labelWidth = (int) Math.ceil(relName.length() * 8.0 + 10.0);
                                maxLabelWidths.merge(sourceId, labelWidth, Math::max);
                                maxLabelWidths.merge(targetId, labelWidth, Math::max);
                            }
                        }
                    }
                }
            }

            // Recurse into containers (groups and nested elements)
            if (child instanceof IDiagramModelContainer nestedContainer) {
                totalConnections += collect(nestedContainer, connectionCounts,
                        viewObjectMap, maxLabelWidths);
            }
        }
        return totalConnections;
    }

    /**
     * The elements on {@code view} that carry a fan-out large enough to need room for its ports
     * and are currently too small to hold them, each with the box it has and the box it needs.
     *
     * <p>The requirement is the <strong>absolute floor</strong>
     * {@link SpacingControlLoop#requiredHubMinWidthPx(int)} /
     * {@link SpacingControlLoop#requiredHubMinHeightPx(int)}, reused rather than restated. It is
     * deliberately NOT the sizing suggestion {@code detect-hub-elements} emits, which is a growth
     * term off the element's <em>current</em> size: an element resized to that suggestion
     * immediately generates a larger one, so a precondition built on it would report every hub as
     * unmet forever, including one the caller had just sized correctly. A floor can be met and
     * stay met, which is the only thing a precondition can be built on.</p>
     *
     * <p>Candidates are element view-objects only. An ArchiMate {@code Grouping} is excluded even
     * though it is an element view-object and can carry relationships: it renders as a transparent
     * zone whose size is set by what it holds, so a fan-out floor would fight the containment that
     * actually determines its box. Notes and native view groups never enter {@link #collect} at
     * all. This mirrors the container/note guard the assessor's own hub scan applies.</p>
     *
     * <p>Returns an empty list when nothing is unmet, so an empty result means "measured, all
     * hubs adequate" rather than "not looked at".</p>
     */
    static List<AssessLayoutResultDto.HubPreconditionDto> unmetHubPreconditions(
            IDiagramModelContainer view) {
        Map<String, Integer> connectionCounts = new HashMap<>();
        Map<String, IDiagramModelArchimateObject> viewObjectMap = new HashMap<>();
        collect(view, connectionCounts, viewObjectMap, new HashMap<>());

        List<AssessLayoutResultDto.HubPreconditionDto> unmet = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : connectionCounts.entrySet()) {
            IDiagramModelArchimateObject obj = viewObjectMap.get(entry.getKey());
            if (obj == null || TopLevelGroupTargets.isGroupingZone(obj)) {
                continue;
            }
            int connections = entry.getValue();
            if (connections <= SpacingControlLoop.DENSITY_HUB_FANOUT_CONN_THRESHOLD) {
                continue;
            }
            int requiredWidth = SpacingControlLoop.requiredHubMinWidthPx(connections);
            int requiredHeight = SpacingControlLoop.requiredHubMinHeightPx(connections);
            IBounds bounds = obj.getBounds();
            if (bounds == null) {
                continue;
            }
            int width = bounds.getWidth();
            int height = bounds.getHeight();
            if (width >= requiredWidth && height >= requiredHeight) {
                continue;
            }
            IArchimateElement element = obj.getArchimateElement();
            unmet.add(new AssessLayoutResultDto.HubPreconditionDto(
                    element != null ? element.getId() : null,
                    obj.getId(),
                    element != null ? element.getName() : null,
                    connections, width, height, requiredWidth, requiredHeight));
        }
        unmet.sort((a, b) -> {
            int byCount = Integer.compare(b.connectionCount(), a.connectionCount());
            return byCount != 0 ? byCount : a.viewObjectId().compareTo(b.viewObjectId());
        });
        return unmet;
    }
}
