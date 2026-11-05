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
 * found. It re-routes nothing — only label text positions and the Label Offset anchor
 * change.</p>
 *
 * <p>Two entries share one input build, so both score the same view the same way:</p>
 * <ul>
 *   <li>{@link #compute} applies the best trial. Its caller, the label fallback of a targeted
 *       {@code auto-layout-and-route}, judges the result by re-assessing the layout.</li>
 *   <li>{@link #computeLabelsOnly} runs when a caller asks for it ({@code auto-route-connections}
 *       {@code mode: "labels-only"}) and has no assessment behind it, so it keeps the labels' current
 *       positions unless the best trial scores strictly lower by the optimizer's own measure.</li>
 * </ul>
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

    /**
     * Optimizes the positions and offsets of the view's shown labels over {@code trials} seeded trials.
     *
     * <p>Every ArchiMate connection between two objects goes to the optimizer: a shown label as a label, an
     * unlabelled connection and one whose label is hidden as a line only, so a label is scored off every line
     * it could be read as belonging to. A line-only entry gets no command and is not counted. Two kinds of line
     * stay out: a connection with a connection at an end (it has no endpoint box to draw from), and a plain
     * diagram connection, such as one to a Note.</p>
     *
     * @param queuedVisibility connection id → visibility a queued command will set, or null outside
     *                         batch mode; see {@link LabelVisibilityReadback#isShown}
     * @return the commands that apply the result, or null when nothing changes
     */
    static Result compute(IArchimateDiagramModel diagramModel, int trials, Map<String, Boolean> queuedVisibility) {
        Input input = buildInput(diagramModel, queuedVisibility);
        if (input == null) {
            return null;
        }

        LabelPositionOptimizer optimizer = new LabelPositionOptimizer();
        LabelPositionOptimizer.MultiTrialResult result = optimizer.optimizeMultiTrial(
                input.batchInput(), input.batchPaths(), input.allObstacles(), input.labelExcludeSets(),
                trials, input.rng(), input.offsetWritableIds());

        // An offset (or a reset of a stale one) needs no along-path change, so both must be empty to stop.
        if (result.changedPositions().isEmpty() && result.offsets().isEmpty()) {
            return null;
        }

        // Build SetTextPositionCommands for changed positions
        Map<String, IDiagramModelArchimateConnection> connLookup = new LinkedHashMap<>();
        for (IDiagramModelArchimateConnection archConn : input.batchConnections()) {
            connLookup.put(archConn.getId(), archConn);
        }

        List<Command> commands = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : result.changedPositions().entrySet()) {
            IDiagramModelArchimateConnection conn = connLookup.get(entry.getKey());
            if (conn != null && conn.getTextPosition() != entry.getValue()) {
                commands.add(new SetTextPositionCommand(conn, entry.getValue()));
            }
        }

        // Perpendicular "Label Offset" commands (Archi 5.10+); feature-guarded → emits nothing before 5.10.
        commands.addAll(LabelOffsetSupport.buildOffsetCommands(result.offsets(), connLookup, queuedVisibility));

        if (commands.isEmpty()) {
            return null;
        }

        NonNotifyingCompoundCommand compound =
                new NonNotifyingCompoundCommand("Label optimization fallback ("
                        + commands.size() + " labels, " + trials + " trials)");
        commands.forEach(compound::add);
        return new Result(compound, commands.size(), trials);
    }

    /**
     * Outcome of a labels-only pass.
     *
     * @param compound      the label commands, possibly empty; never null
     * @param labels        the shown labels the pass placed, 0 when the view has none
     * @param unchanged     true when the view has labels and no placement scored strictly lower than the labels as
     *                      they stand, so nothing changes: every label keeps its position and its anchor
     */
    record LabelsOnlyResult(NonNotifyingCompoundCommand compound, int labels, boolean unchanged) {}

    /**
     * Places the view's shown labels over the geometry as it stands, for a caller that asked for a labels-only pass.
     *
     * <p>It reads what {@link #compute} reads and runs the same {@code trials} seeded trials. It scores three
     * assignments by the optimizer's own measure ({@link LabelPositionOptimizer#scoreAssignment}), each with the anchors
     * it draws its labels at: the labels as they stand (their held anchors), the best trial, and the current positions
     * with the best anchor for each Middle label ({@link LabelPositionOptimizer#computeOffsetsForPositions}). The search
     * never tries the current assignment itself, so its best can score higher than what a hand-tuned view already
     * holds, or the same with labels moved:</p>
     * <ul>
     *   <li>the lower of the two new assignments is applied when it scores strictly lower than the labels as they
     *       stand; on a tie between them the best trial wins, as the search itself prefers a text position to a new
     *       anchor;</li>
     *   <li>otherwise nothing changes, so a second run over the result is a no-op.</li>
     * </ul>
     * <p>An anchor is offered only where it ships ({@link LabelOffsetSupport#offsetWritableIds}).</p>
     * <p>Only {@link SetTextPositionCommand} and {@link SetTextRelativePositionCommand} are built: no bendpoint, loop,
     * router type, bounds or label visibility changes.</p>
     *
     * @param queuedVisibility connection id → visibility a queued command will set, or null outside batch mode; see
     *                         {@link LabelVisibilityReadback#isShown}
     */
    static LabelsOnlyResult computeLabelsOnly(IArchimateDiagramModel diagramModel, int trials,
            Map<String, Boolean> queuedVisibility) {
        Input input = buildInput(diagramModel, queuedVisibility);
        if (input == null) {
            return new LabelsOnlyResult(new NonNotifyingCompoundCommand("Labels-only pass (0 labels)"), 0, false);
        }

        LabelPositionOptimizer optimizer = new LabelPositionOptimizer();
        LabelPositionOptimizer.MultiTrialResult best = optimizer.optimizeMultiTrial(
                input.batchInput(), input.batchPaths(), input.allObstacles(), input.labelExcludeSets(),
                trials, input.rng(), input.offsetWritableIds());

        Map<String, Integer> current = new LinkedHashMap<>();
        Map<String, List<AbsoluteBendpointDto>> pathsById = new LinkedHashMap<>();
        for (int i = 0; i < input.batchInput().size(); i++) {
            RoutingPipeline.ConnectionEndpoints conn = input.batchInput().get(i);
            current.put(conn.connectionId(), conn.textPosition());
            pathsById.put(conn.connectionId(), input.batchPaths().get(i));
        }
        double asTheyStand = optimizer.scoreAssignment(
                input.batchInput(), input.batchPaths(), input.allObstacles(), input.labelExcludeSets(), current);
        Map<String, Integer> anchorsInPlace = optimizer.computeOffsetsForPositions(input.batchInput(), pathsById,
                input.allObstacles(), input.labelExcludeSets(), current, input.offsetWritableIds());
        double inPlace = optimizer.scoreAssignment(input.batchInput(), input.batchPaths(), input.allObstacles(),
                input.labelExcludeSets(), current, anchorsInPlace);

        boolean moved = best.totalScore() <= inPlace && best.totalScore() < asTheyStand;
        boolean anchored = !moved && inPlace < asTheyStand;
        Map<String, Integer> positions = moved ? best.changedPositions() : Map.of();
        Map<String, Integer> offsets = moved ? best.offsets() : anchored ? anchorsInPlace : Map.of();
        List<Command> commands = LabelOffsetSupport.buildLabelCommands(
                positions, offsets, input.batchConnections(), queuedVisibility);

        NonNotifyingCompoundCommand compound = new NonNotifyingCompoundCommand("Labels-only pass ("
                + input.batchConnections().size() + " labels, " + trials + " trials)");
        commands.forEach(compound::add);
        return new LabelsOnlyResult(compound, input.batchConnections().size(), !moved && !anchored);
    }

    /**
     * The score {@link #computeLabelsOnly} compares against: every label at the position and Label Offset it holds,
     * by the optimizer's own measure ({@link LabelPositionOptimizer#scoreAssignment}), over the same input. Read
     * before and after a labels-only run, it shows the run made the view no worse by that measure.
     *
     * @return the score, or null when the view has no shown label to place
     */
    static Double labelsAsTheyStandScore(IArchimateDiagramModel diagramModel, Map<String, Boolean> queuedVisibility) {
        Input input = buildInput(diagramModel, queuedVisibility);
        if (input == null) {
            return null;
        }
        Map<String, Integer> current = new LinkedHashMap<>();
        input.batchInput().forEach(conn -> current.put(conn.connectionId(), conn.textPosition()));
        LabelPositionOptimizer optimizer = new LabelPositionOptimizer();
        return optimizer.scoreAssignment(input.batchInput(), input.batchPaths(), input.allObstacles(),
                input.labelExcludeSets(), current);
    }

    /**
     * The optimizer's inputs over the diagram as it stands, built one way for every entry so that each scores the
     * same view.
     */
    private record Input(List<RoutingPipeline.ConnectionEndpoints> batchInput,
            List<List<AbsoluteBendpointDto>> batchPaths, List<IDiagramModelArchimateConnection> batchConnections,
            List<RoutingRect> allObstacles, Map<String, Set<String>> labelExcludeSets,
            Set<String> offsetWritableIds) {

        /**
         * Seeded for reproducibility given the same EMF state. The seed counts the labels, not the line-only entries,
         * so the trial order a view's labels are shuffled in does not move when it gains or loses an unlabelled line.
         */
        Random rng() {
            return new Random(batchConnections.size() * 31L + allObstacles.size());
        }
    }

    /**
     * Builds the optimizer's inputs from the current EMF state, or null when the view has no shown label to place.
     *
     * <p>Every ArchiMate connection between two objects goes in: a shown label as a label, an unlabelled connection
     * and one whose label is hidden as a line only. A connection with a connection at an end and a plain diagram
     * connection stay out.</p>
     */
    private static Input buildInput(IArchimateDiagramModel diagramModel, Map<String, Boolean> queuedVisibility) {
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
            // An unlabelled connection, or one whose label is hidden (in the model, or by the batch), still
            // draws its line, and a label lying on that line reads as its label. So it goes in as a line
            // only: empty text, which the optimizer never places. It stays out of batchConnections, so no
            // command is ever built for it and it is never counted in labelsOptimized: a hidden label has
            // no position to optimise and no offset to apply.
            boolean lineOnly = labelText.isEmpty() || !LabelVisibilityReadback.isShown(archConn, queuedVisibility);

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
                    lineOnly ? "" : labelText, archConn.getTextPosition(), List.of(),
                    RelativePositionFeature.get(archConn)));
            batchPaths.add(path);
            if (!lineOnly) {
                batchConnections.add(archConn);
            }
        }

        if (batchConnections.isEmpty()) {
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

        return new Input(batchInput, batchPaths, batchConnections, allObstacles, labelExcludeSets,
                LabelOffsetSupport.offsetWritableIds(true, batchConnections, queuedVisibility));
    }
}
