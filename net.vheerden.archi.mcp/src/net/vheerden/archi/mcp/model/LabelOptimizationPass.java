package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.eclipse.gef.commands.Command;

import com.archimatetool.editor.model.commands.NonNotifyingCompoundCommand;

import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.model.routing.LabelPositionOptimizer;
import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;

/**
 * The standalone label-position optimization pass.
 *
 * <p>Reads the current EMF state (element positions and connection bendpoints), runs multi-trial
 * label position optimization over it, and returns the commands that apply the best positions
 * found. It re-routes nothing — only label text positions and the perpendicular label offset
 * change.</p>
 *
 * <p>Lives outside the accessor facade because it is a self-contained pass over the diagram: it
 * needs no accessor state, only the diagram it is given.</p>
 */
final class LabelOptimizationPass {

    private LabelOptimizationPass() {
        // static pass
    }

    /** Outcome of a label optimization pass. */
    static final class Result {
        final NonNotifyingCompoundCommand compound;
        final int labelsOptimized;
        final int trials;

        Result(NonNotifyingCompoundCommand compound, int labelsOptimized, int trials) {
            this.compound = compound;
            this.labelsOptimized = labelsOptimized;
            this.trials = trials;
        }
    }

    /**
     * Computes a standalone label optimization pass.
     * Reads current EMF state (element positions and connection bendpoints),
     * runs multi-trial label position optimization, and returns commands to
     * apply the best label positions found.
     *
     * <p>This method does NOT re-route connections — it only changes label
     * text positions via {@link SetTextPositionCommand}.</p>
     *
     * @param diagramModel the diagram to optimize labels for
     * @param trials       number of optimization trials to run
     * @return result with compound command and optimization counts, or null if no improvements
     */
    /**
     * Reconstructs a connection's stored bendpoints as absolute canvas points, in the frame the
     * rectangles below are built in.
     *
     * <p>A bendpoint is not stored as a canvas coordinate. Archi holds it twice, as an offset from
     * the source centre and an offset from the target centre, and an absolute point is recovered by
     * interpolating the two reconstructions at the weight the renderer uses. Handing the raw source
     * offset to the optimiser instead displaces every path point by the whole source centre, which
     * on any element away from the canvas origin is hundreds of pixels — and the element rectangles
     * it is scored against are absolute, so the two would be in different frames and the label
     * clearances computed from them would be meaningless.
     *
     * <p>Centres are derived from the same node bounds and the same truncation the rectangles use,
     * so the path and the obstacles provably share one frame.
     */
    static List<AbsoluteBendpointDto> absolutePathOf(IDiagramModelArchimateConnection conn,
                                                     AssessmentNode srcNode,
                                                     AssessmentNode tgtNode) {
        int srcCentreX = (int) srcNode.x() + (int) srcNode.width() / 2;
        int srcCentreY = (int) srcNode.y() + (int) srcNode.height() / 2;
        int tgtCentreX = (int) tgtNode.x() + (int) tgtNode.width() / 2;
        int tgtCentreY = (int) tgtNode.y() + (int) tgtNode.height() / 2;
        return ConnectionResponseBuilder.convertRelativeToAbsolute(
                ConnectionResponseBuilder.collectBendpoints(conn),
                srcCentreX, srcCentreY, tgtCentreX, tgtCentreY);
    }

    static Result compute(IArchimateDiagramModel diagramModel, int trials) {

        List<AssessmentNode> nodes = AssessmentCollector.collectAssessmentNodes(diagramModel);
        List<IDiagramModelConnection> allConnections =
                AssessmentCollector.collectAllConnections(diagramModel);
        if (allConnections.isEmpty()) {
            return null;
        }

        // Build node map for ancestor/child lookups
        Map<String, AssessmentNode> nodeMap = new LinkedHashMap<>();
        for (AssessmentNode node : nodes) {
            nodeMap.put(node.id(), node);
        }

        // Build optimizer inputs: ConnectionEndpoints + paths from current EMF state
        List<RoutingPipeline.ConnectionEndpoints> batchInput = new ArrayList<>();
        List<List<AbsoluteBendpointDto>> batchPaths = new ArrayList<>();
        List<IDiagramModelArchimateConnection> batchConnections = new ArrayList<>();

        for (IDiagramModelConnection conn : allConnections) {
            if (!(conn instanceof IDiagramModelArchimateConnection archConn)) {
                continue;
            }

            IConnectable srcConn = conn.getSource();
            IConnectable tgtConn = conn.getTarget();
            if (!(srcConn instanceof IDiagramModelObject)
                    || !(tgtConn instanceof IDiagramModelObject)) {
                continue;
            }

            AssessmentNode srcNode = nodeMap.get(srcConn.getId());
            AssessmentNode tgtNode = nodeMap.get(tgtConn.getId());
            if (srcNode == null || tgtNode == null) {
                continue;
            }

            // Extract label text
            String labelText = "";
            IArchimateRelationship connRel = archConn.getArchimateRelationship();
            if (connRel != null && connRel.getName() != null) {
                labelText = connRel.getName();
            }
            if (labelText.isEmpty()) {
                continue; // skip unlabeled connections
            }
            if (!archConn.isNameVisible()) {
                // A hidden label renders nothing, so it has no position to optimise and no offset to
                // apply. Including it would emit commands that change nothing visible and inflate the
                // reported labelsOptimized count with labels the caller cannot see.
                continue;
            }

            RoutingRect srcRect = new RoutingRect(
                    (int) srcNode.x(), (int) srcNode.y(),
                    (int) srcNode.width(), (int) srcNode.height(),
                    srcNode.id());
            RoutingRect tgtRect = new RoutingRect(
                    (int) tgtNode.x(), (int) tgtNode.y(),
                    (int) tgtNode.width(), (int) tgtNode.height(),
                    tgtNode.id());

            List<AbsoluteBendpointDto> path = absolutePathOf(archConn, srcNode, tgtNode);

            batchInput.add(new RoutingPipeline.ConnectionEndpoints(
                    archConn.getId(), srcRect, tgtRect, List.of(),
                    labelText, archConn.getTextPosition()));
            batchPaths.add(path);
            batchConnections.add(archConn);
        }

        if (batchInput.isEmpty()) {
            return null;
        }

        // Build obstacle list (non-container elements). Must use the same container predicate the
        // assessor's label-overlap detector uses: a label may legitimately sit inside a
        // transparent container, so working to route one around a zone would chase a collision
        // the assessment does not count.
        List<RoutingRect> allObstacles = new ArrayList<>();
        for (AssessmentNode node : nodes) {
            if (!node.isContainer()) {
                allObstacles.add(new RoutingRect(
                        (int) node.x(), (int) node.y(),
                        (int) node.width(), (int) node.height(),
                        node.id()));
            }
        }

        // Build per-connection label exclusion sets
        Map<String, Set<String>> labelExcludeSets = new LinkedHashMap<>();
        for (RoutingPipeline.ConnectionEndpoints conn : batchInput) {
            Set<String> excludeIds = new HashSet<>();
            if (conn.source().id() != null) {
                excludeIds.add(conn.source().id());
                excludeIds.addAll(RoutingExcludeSets.ancestorIds(conn.source().id(), nodeMap));
                excludeIds.addAll(RoutingExcludeSets.descendantIds(conn.source().id(), nodes));
            }
            if (conn.target().id() != null) {
                excludeIds.add(conn.target().id());
                excludeIds.addAll(RoutingExcludeSets.ancestorIds(conn.target().id(), nodeMap));
                excludeIds.addAll(RoutingExcludeSets.descendantIds(conn.target().id(), nodes));
            }
            labelExcludeSets.put(conn.connectionId(), excludeIds);
        }

        // Run multi-trial optimization (seeded for reproducibility given same EMF state)
        LabelPositionOptimizer optimizer = new LabelPositionOptimizer();
        LabelPositionOptimizer.MultiTrialResult result = optimizer.optimizeMultiTrial(
                batchInput, batchPaths, allObstacles, labelExcludeSets,
                trials, new Random(batchInput.size() * 31L + allObstacles.size()));

        // Offsets are metric-neutral (own-endpoint bleed clears with NO along-path change) → need BOTH empty.
        if (result.changedPositions().isEmpty() && result.offsets().isEmpty()) {
            return null;
        }

        // Build SetTextPositionCommands for changed positions
        Map<String, IDiagramModelArchimateConnection> connLookup = new LinkedHashMap<>();
        for (IDiagramModelArchimateConnection archConn : batchConnections) {
            connLookup.put(archConn.getId(), archConn);
        }

        List<Command> commands = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : result.changedPositions().entrySet()) {
            IDiagramModelArchimateConnection conn = connLookup.get(entry.getKey());
            if (conn != null && conn.getTextPosition() != entry.getValue()) {
                commands.add(new SetTextPositionCommand(conn, entry.getValue()));
            }
        }

        // Perpendicular "Label Offset" commands (Archi 5.10+); feature-guarded → emits nothing on 5.7.
        commands.addAll(LabelOffsetSupport.buildOffsetCommands(result.offsets(), connLookup));

        if (commands.isEmpty()) {
            return null;
        }

        NonNotifyingCompoundCommand compound =
                new NonNotifyingCompoundCommand("Label optimization fallback ("
                        + commands.size() + " labels, " + trials + " trials)");
        commands.forEach(compound::add);
        return new Result(compound, commands.size(), trials);
    }
}
