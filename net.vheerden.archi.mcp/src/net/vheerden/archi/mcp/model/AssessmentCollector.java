package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;

/**
 * Collects assessment nodes and connections from diagram models for
 * layout quality assessment and routing.
 *
 * <p>Extracted from ArchiModelAccessorImpl (Story 12-4) to improve cohesion.
 * Package-visible — only ArchiModelAccessorImpl should use this class.</p>
 */
final class AssessmentCollector {

    private AssessmentCollector() {}

    private static final Logger logger = LoggerFactory.getLogger(AssessmentCollector.class);

    static List<AssessmentNode> collectAssessmentNodes(
            IArchimateDiagramModel diagramModel) {
        List<AssessmentNode> nodes = new ArrayList<>();
        collectAssessmentNodesRecursive(diagramModel, null, 0.0, 0.0, nodes);
        return nodes;
    }

    static void collectAssessmentNodesRecursive(
            IDiagramModelContainer container, String parentId,
            double parentOffsetX, double parentOffsetY,
            List<AssessmentNode> nodes) {
        for (IDiagramModelObject child : container.getChildren()) {
            IBounds bounds = child.getBounds();
            double w = bounds.getWidth();
            double h = bounds.getHeight();
            if (w <= 0 || h <= 0) {
                logger.warn("Skipping element '{}' (id={}) with zero/negative bounds: w={}, h={}",
                        child.getName(), child.getId(), w, h);
                continue;
            }
            double absX = bounds.getX() + parentOffsetX;
            double absY = bounds.getY() + parentOffsetY;
            boolean isGroup = child instanceof IDiagramModelGroup;
            boolean isNote = child instanceof IDiagramModelNote;
            nodes.add(new AssessmentNode(child.getId(),
                    absX, absY, w, h, parentId, isGroup, isNote));

            if (child instanceof IDiagramModelContainer nested) {
                collectAssessmentNodesRecursive(nested, child.getId(),
                        absX, absY, nodes);
            }
        }
    }

    static List<AssessmentConnection> collectAssessmentConnections(
            IArchimateDiagramModel diagramModel,
            List<AssessmentNode> nodes) {
        Map<String, AssessmentNode> nodeMap = new LinkedHashMap<>();
        for (AssessmentNode node : nodes) {
            nodeMap.put(node.id(), node);
        }

        List<AssessmentConnection> connections = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (IDiagramModelConnection conn : collectAllConnections(diagramModel)) {
            if (!seen.add(conn.getId())) continue;

            IConnectable source = conn.getSource();
            IConnectable target = conn.getTarget();
            if (!(source instanceof IDiagramModelObject)
                    || !(target instanceof IDiagramModelObject)) {
                continue;
            }

            AssessmentNode srcNode = nodeMap.get(source.getId());
            AssessmentNode tgtNode = nodeMap.get(target.getId());
            if (srcNode == null || tgtNode == null) continue;

            double srcCenterX = srcNode.x() + srcNode.width() / 2;
            double srcCenterY = srcNode.y() + srcNode.height() / 2;
            double tgtCenterX = tgtNode.x() + tgtNode.width() / 2;
            double tgtCenterY = tgtNode.y() + tgtNode.height() / 2;

            List<double[]> pathPoints = new ArrayList<>();
            pathPoints.add(new double[]{srcCenterX, srcCenterY});

            for (IDiagramModelBendpoint bp : conn.getBendpoints()) {
                double absX = (bp.getStartX() + srcCenterX
                        + bp.getEndX() + tgtCenterX) / 2;
                double absY = (bp.getStartY() + srcCenterY
                        + bp.getEndY() + tgtCenterY) / 2;
                pathPoints.add(new double[]{absX, absY});
            }

            pathPoints.add(new double[]{tgtCenterX, tgtCenterY});

            String labelText = "";
            if (conn instanceof IDiagramModelArchimateConnection archConn) {
                IArchimateRelationship rel = archConn.getArchimateRelationship();
                labelText = (rel != null && rel.getName() != null) ? rel.getName() : "";
            }
            int textPosition = conn.getTextPosition();

            connections.add(new AssessmentConnection(
                    conn.getId(), source.getId(), target.getId(), pathPoints,
                    labelText, textPosition));
        }

        return connections;
    }

    static List<IDiagramModelConnection> collectAllConnections(
            IArchimateDiagramModel diagramModel) {
        Set<String> seen = new HashSet<>();
        List<IDiagramModelConnection> result = new ArrayList<>();
        collectConnectionsRecursive(diagramModel, seen, result);
        return result;
    }

    private static void collectConnectionsRecursive(
            IDiagramModelContainer container,
            Set<String> seen,
            List<IDiagramModelConnection> result) {
        for (IDiagramModelObject child : container.getChildren()) {
            for (IDiagramModelConnection conn : child.getSourceConnections()) {
                if (seen.add(conn.getId())) {
                    result.add(conn);
                }
            }
            for (IDiagramModelConnection conn : child.getTargetConnections()) {
                if (seen.add(conn.getId())) {
                    result.add(conn);
                }
            }
            if (child instanceof IDiagramModelContainer nested) {
                collectConnectionsRecursive(nested, seen, result);
            }
        }
    }
}
