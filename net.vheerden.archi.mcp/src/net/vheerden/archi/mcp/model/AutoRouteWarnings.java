package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.ecore.EObject;

import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.model.routing.ConnectionThroughNoteDetector;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.NudgedElementDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;

/**
 * Assembles the structured/free-text warnings the {@code auto-route-connections} response
 * surfaces. Kept out of the accessor facade so the facade's line-count ratchet is unaffected
 * by warning-message wording.
 */
final class AutoRouteWarnings {

    private AutoRouteWarnings() {}

    /** MCP tool the caller should run to widen a layout-bound corridor before re-routing. */
    private static final String SPACING_REMEDY_TOOL = "apply-spacing-recommendations";

    /**
     * MCP tool the caller should run to enumerate the connection IDs that actually exist on the
     * view. Deliberately not {@code auto-route-connections}: re-running the tool that just reported
     * the miss cannot resolve it — only supplying a correct ID can.
     */
    private static final String CONNECTION_LOOKUP_TOOL = "get-view-contents";

    /**
     * Emits the connection-not-found warning for {@code connectionIds} that are not on the view.
     * Appends one free-text line per missing ID (the long-standing per-ID surface) plus a single
     * aggregate {@link StructuredWarningDto} whose {@code remediationViolatorIds} lists every
     * missing ID, so callers can react without parsing free text.
     *
     * <p>The structured code is what lets the response distinguish this condition from the several
     * unrelated routing conditions that also write to {@code warnings}; without it a caller can only
     * guess from list-emptiness, which is wrong whenever any other warning fires.</p>
     *
     * <p><strong>Note the deliberate asymmetry with the sibling emitters.</strong> They raise one
     * free-text line and repeat it verbatim as the structured message. This one cannot: the per-ID
     * free-text lines are a long-standing published surface that must stay exactly as they are, while
     * the structured warning is aggregate so that {@code remediationViolatorIds} can carry every
     * missing ID in a single entry. So the free text and the structured message are different strings
     * by design. The invariant callers actually rely on still holds — a non-empty
     * {@code structuredWarnings} still implies a non-empty {@code warnings}.</p>
     *
     * <p>No-op when {@code missingConnectionIds} is empty.</p>
     *
     * @param missingConnectionIds requested connection IDs that were not found on the view
     * @param warnings             the free-text warnings accumulator (mutated)
     * @param structuredWarnings   the structured warnings accumulator (mutated)
     */
    static void emitConnectionsNotFound(List<String> missingConnectionIds, DispatchArm arm,
            List<String> warnings, List<StructuredWarningDto> structuredWarnings) {
        if (missingConnectionIds == null || missingConnectionIds.isEmpty()) {
            return;
        }
        for (String connId : missingConnectionIds) {
            warnings.add("Connection not found on view: " + connId);
        }
        structuredWarnings.add(new StructuredWarningDto(
                StructuredWarningCodes.CONNECTION_NOT_FOUND,
                connectionsNotFoundMessage(missingConnectionIds, switch (arm) {
                    case APPLIED -> " The remaining connections were routed normally.";
                    case QUEUED -> " Nothing has been applied: the routing of the remaining "
                            + "connections is queued in the open batch.";
                    case AWAITING_APPROVAL -> " Nothing has been applied: the routing of the "
                            + "remaining connections is waiting on the human's decision, so those "
                            + "routes will only exist once it is approved.";
                }),
                CONNECTION_LOOKUP_TOOL,
                List.copyOf(missingConnectionIds)));
    }

    /**
     * Composes the connection-not-found message from the missing ids and ONE outcome clause.
     *
     * <p>The miss itself is arm-independent: the ids were looked up against the view before
     * anything was dispatched, so the count and the list are established whatever happens to the
     * model next and are carried unchanged on every arm. What varies is the single sentence about
     * what became of the connections that DID resolve — and it is composed, never edited into a
     * finished sentence, so the clause cannot acquire a second meaning by being spliced.</p>
     */
    private static String connectionsNotFoundMessage(List<String> missingConnectionIds,
            String outcomeClause) {
        return missingConnectionIds.size() + " requested connection ID(s) were not found "
                + "on the view and were skipped: " + String.join(", ", missingConnectionIds)
                + "." + outcomeClause + " Run get-view-contents to list "
                + "the connection IDs that exist on this view.";
    }

    /**
     * Records that the routing pass threw on degenerate geometry after the connection-not-found
     * warning had already been raised, and repairs that warning.
     *
     * <p>The connection-not-found message states what became of the connections that resolved. On
     * this path nothing became of them: the router threw, the early return carries no commands, and
     * the response would otherwise say "The remaining connections were routed normally" beside a
     * failure notice about the very same call. That contradiction is arm-INDEPENDENT — it is
     * equally false on an applied call — which is why it is repaired here rather than by the arm
     * selection above.</p>
     *
     * <p>The entry is RECOMPOSED from the ids it already carries in
     * {@code remediationViolatorIds}, not string-edited: the ids are the evidence, and rebuilding
     * the sentence around them is what keeps one clause from being spliced into another. The
     * per-ID free-text lines are untouched, because each of them is still true.</p>
     */
    static void emitRoutingFailedDegenerateGeometry(List<String> warnings,
            List<StructuredWarningDto> structuredWarnings) {
        warnings.add("Routing failed due to degenerate element geometry (overlapping or "
                + "zero-gap elements). Use layout-flat-view or layout-within-group to "
                + "separate elements first, then re-route.");
        for (int i = 0; i < structuredWarnings.size(); i++) {
            StructuredWarningDto existing = structuredWarnings.get(i);
            if (StructuredWarningCodes.CONNECTION_NOT_FOUND.equals(existing.code())) {
                structuredWarnings.set(i, new StructuredWarningDto(
                        existing.code(),
                        connectionsNotFoundMessage(existing.remediationViolatorIds(),
                                " Routing then failed on the remaining connections, so none of "
                                + "them were routed either."),
                        existing.remediationTool(),
                        existing.remediationViolatorIds()));
            }
        }
    }

    /**
     * Emits the layout-bound egress-lift warning when the terminal-clearance pass rolled back at
     * least one off-face lift. The router generated the lift(s) and then declined them because
     * applying them would narrow a parallel-connection gap below its healthy floor — a correct,
     * layout-bound decline. This surfaces that otherwise-silent decision so the caller widens the
     * corridor (the only remedy) instead of re-running the router (a no-op). Appends both a
     * machine-parseable {@link StructuredWarningDto} and a mirrored free-text line for back-compat.
     * No-op when {@code egressRolledBack <= 0}.
     *
     * @param egressRolledBack  rolled-back off-face egress-lift count from the routing result
     * @param warnings          the free-text warnings accumulator (mutated)
     * @param structuredWarnings the structured warnings accumulator (mutated)
     */
    static void emitEgressLiftLayoutBound(int egressRolledBack, DispatchArm arm,
            List<String> warnings, List<StructuredWarningDto> structuredWarnings) {
        if (egressRolledBack <= 0) {
            return;
        }
        String message = egressRolledBack + " off-face terminal hug(s) could not be cleared without "
                + "narrowing a parallel-connection gap below the 15px healthy floor, so the router "
                + switch (arm) {
                    case APPLIED -> "kept the hug(s) in place.";
                    case QUEUED -> "will keep the hug(s) in place. Nothing has been applied: this "
                            + "routing is queued in the open batch.";
                    case AWAITING_APPROVAL -> "will keep the hug(s) in place. Nothing has been "
                            + "applied: this routing is waiting on the human's decision, so the "
                            + "hug(s) will only exist once it is approved.";
                }
                + EGRESS_LIFT_CORRIDOR_REMEDY;
        emitMirrored(StructuredWarningCodes.EGRESS_LIFT_LAYOUT_BOUND, message, SPACING_REMEDY_TOOL,
                List.of(), warnings, structuredWarnings);
    }

    /**
     * The corridor remedy, which is a statement about the LAYOUT rather than about recovering this
     * call, and therefore holds identically on all three arms. Kept as one constant so widening the
     * corridor cannot come to mean two different things depending on which arm composed it.
     */
    private static final String EGRESS_LIFT_CORRIDOR_REMEDY =
            " This is layout-bound: increase element spacing in the affected corridor and re-route "
            + "— re-routing alone will not clear it.";

    /**
     * Emits the net-zero disclosure for elements that {@code autoNudge} moved and that ended at the
     * position they started from.
     *
     * <p>A zero-displacement row in {@code nudgedElements} made the response contradict itself: the
     * connection the move targeted was still in {@code failed} and the identical move was still in
     * {@code recommendations}, while {@code nextSteps} counted the element as nudged. The row is now
     * filtered out of the reported list, and this code is what stops that filtering from turning a
     * visible contradiction into a silence.</p>
     *
     * <p><strong>The message states the outcome and declines to name a mechanism.</strong> A move
     * reversed by a later iteration and a move fully absorbed by the parent-containment clamp both
     * sum to zero and are indistinguishable in the accumulated map, so claiming either would be a
     * false statement whenever the other was the cause. It says so explicitly rather than leaving
     * the caller to assume the loop oscillated.</p>
     *
     * <p><strong>Cardinality is one entry per call</strong>, naming every element and carrying every
     * id in {@code remediationViolatorIds}, never truncated. The remedy is a spacing lever and
     * deliberately not {@code auto-route-connections}: re-running it recomputes the same
     * recommendation against the same geometry and reproduces the same zero.</p>
     *
     * <p>No-op when {@code netZeroElements} is empty.</p>
     *
     * @param netZeroElements    elements whose cumulative nudge displacement was {@code (0, 0)}
     * @param warnings           the free-text warnings accumulator (mutated)
     * @param structuredWarnings the structured warnings accumulator (mutated)
     */
    static void emitNetZeroNudge(List<NudgedElementDto> netZeroElements, DispatchArm arm,
            List<String> warnings, List<StructuredWarningDto> structuredWarnings) {
        if (netZeroElements == null || netZeroElements.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<>();
        StringBuilder named = new StringBuilder();
        for (NudgedElementDto element : netZeroElements) {
            ids.add(element.viewObjectId());
            if (named.length() > 0) {
                named.append(", ");
            }
            String name = element.elementName();
            named.append(name != null && !name.isBlank() ? name : element.viewObjectId())
                    .append(" (").append(element.viewObjectId()).append(')');
        }
        String message = netZeroElements.size() + " element(s) processed by autoNudge "
                + switch (arm) {
                    case APPLIED -> "ended at their starting position (net displacement 0,0) and "
                            + "are therefore not reported in nudgedElements: " + named
                            + ". The recommended move did not take effect for them, so whatever it "
                            + "was meant to unblock may still be unresolved — check the failed "
                            + "array.";
                    case QUEUED -> "will end at their starting position (net displacement 0,0) and "
                            + "are therefore not reported in nudgedElements: " + named
                            + ". Nothing has been applied: this routing is queued in the open "
                            + "batch. The recommended move will not take effect for them even once "
                            + "it is committed, so whatever it was meant to unblock will still be "
                            + "unresolved — check the failed array.";
                    case AWAITING_APPROVAL -> "will end at their starting position (net "
                            + "displacement 0,0) and are therefore not reported in nudgedElements: "
                            + named + ". Nothing has been applied: this routing is waiting on the "
                            + "human's decision. The recommended move will not take effect for them "
                            + "even once it is approved, so whatever it was meant to unblock will "
                            + "still be unresolved — check the failed array.";
                }
                + NET_ZERO_MECHANISM_AND_REMEDY;
        emitMirrored(StructuredWarningCodes.AUTO_NUDGE_NET_ZERO, message, SPACING_REMEDY_TOOL,
                List.copyOf(ids), warnings, structuredWarnings);
    }

    /**
     * What the response declines to claim, and the spacing lever that resolves it. Both are
     * arm-independent: the two producers of a zero are indistinguishable in the accumulated map
     * whatever becomes of the commands, and widening the spacing is a layout corrective rather than
     * a recovery of this call.
     */
    private static final String NET_ZERO_MECHANISM_AND_REMEDY =
            " This response does not distinguish a move absorbed by the parent-containment clamp "
            + "from one a later iteration reversed. Re-routing will reproduce the same result: "
            + "increase the spacing around these elements so a recommended move has room to take "
            + "effect.";

    /** MCP tool that moves a note by id — the remedy for a route the caller wants off a note. */
    private static final String NOTE_MOVE_REMEDY_TOOL = "update-view-object";

    /**
     * Emits the note-crossing disclosure for routes this pass applied straight through a note.
     *
     * <p>A note is deliberately not a routing obstacle, so this is a <strong>disclosure, not a
     * violation</strong>: nothing was broken and the router did exactly what its description says
     * it does. It does not belong in {@code violations}, whose only reason code speaks about a
     * crossed <em>element</em> and would be a false statement about a note. What was missing is
     * per-call attribution — the description warns at schema time that <em>a</em> crossing can
     * happen, and never says which one did.</p>
     *
     * <p><strong>Cardinality is one entry per call</strong>, naming every (connection, note) pair
     * and carrying the pair count in the message. Not one entry per crossing, and not one per
     * connection. {@code assess-layout} counts per (connection × visual), so a caller comparing
     * {@code structuredWarnings.length} against {@code connectionThroughNoteCount} would otherwise
     * read a disagreement that is not one; stating the pair count in the message is what makes the
     * two shapes reconcilable. The violator list and the message are <strong>never truncated</strong> —
     * a caller that cannot see the canvas must be able to act on each note individually.</p>
     *
     * <p>{@code remediationViolatorIds} carries the NOTE ids, de-duplicated in first-crossed order
     * (connections in routing order, notes in view order within each connection), because
     * the note is what the caller moves. The connection is named in the message by its label and
     * id; a note has no name to give — {@code IDiagramModelNote} carries content, not a name — so
     * it is named by id, which is also the vocabulary {@code assess-layout}'s own descriptions use
     * and the argument {@code update-view-object} takes.</p>
     *
     * <p>No-op when nothing was crossed: a view whose routes clear every note, and equally a view
     * with no notes at all, must gain no entry, no free-text line and no empty list.</p>
     *
     * <p><strong>Call this at the LAST point the routes can still change, never earlier.</strong>
     * On the orthogonal path that is after the auto-nudge pass, not before it: the nudge merges
     * freshly-routed connections into the routed-path map, rewrites the bendpoints of any
     * connection whose endpoint it moved, and re-collects the assessment nodes at the nudged
     * positions. A disclosure taken before it describes geometry the same call is about to
     * replace, and it is wrong in both directions — a crossing the nudge cleared would still be
     * named, and one the nudge introduced would not be.</p>
     *
     * @param routedPaths        absolute bendpoints per routed connection id, as applied
     * @param assessmentConns    the view's connections, read for endpoint ids and label text
     * @param nodes              the view's assessment nodes; notes and endpoint rectangles alike
     * @param warnings           the free-text warnings accumulator (mutated)
     * @param structuredWarnings the structured warnings accumulator (mutated)
     */
    static void emitConnectionThroughNote(
            Map<String, List<AbsoluteBendpointDto>> routedPaths,
            List<AssessmentConnection> assessmentConns,
            List<AssessmentNode> nodes,
            DispatchArm arm,
            List<String> warnings,
            List<StructuredWarningDto> structuredWarnings) {
        if (routedPaths == null || routedPaths.isEmpty() || nodes == null
                || assessmentConns == null) {
            return;
        }
        List<ConnectionThroughNoteDetector.NoteRect> notes = new ArrayList<>();
        Map<String, AssessmentNode> nodeMap = new LinkedHashMap<>();
        for (AssessmentNode node : nodes) {
            nodeMap.put(node.id(), node);
            if (node.isNote()) {
                notes.add(new ConnectionThroughNoteDetector.NoteRect(
                        node.id(), node.x(), node.y(), node.width(), node.height()));
            }
        }
        if (notes.isEmpty()) {
            return;
        }

        Map<String, String> labelById = new LinkedHashMap<>();
        List<ConnectionThroughNoteDetector.RoutedPath> paths = new ArrayList<>();
        for (AssessmentConnection conn : assessmentConns) {
            List<AbsoluteBendpointDto> bendpoints = routedPaths.get(conn.id());
            if (bendpoints == null) {
                continue; // not routed by this pass
            }
            AssessmentNode src = nodeMap.get(conn.sourceNodeId());
            AssessmentNode tgt = nodeMap.get(conn.targetNodeId());
            if (src == null || tgt == null) {
                continue;
            }
            labelById.put(conn.id(), conn.labelText());
            paths.add(new ConnectionThroughNoteDetector.RoutedPath(
                    conn.id(), centreAnchoredPath(src, tgt, bendpoints), rectOf(src), rectOf(tgt)));
        }

        List<ConnectionThroughNoteDetector.NoteCrossing> crossings =
                ConnectionThroughNoteDetector.detect(
                        paths, notes, LayoutQualityAssessor.PASS_THROUGH_INSET);
        if (crossings.isEmpty()) {
            return;
        }

        List<String> noteIds = new ArrayList<>();
        StringBuilder pairs = new StringBuilder();
        for (ConnectionThroughNoteDetector.NoteCrossing crossing : crossings) {
            if (!noteIds.contains(crossing.noteId())) {
                noteIds.add(crossing.noteId());
            }
            if (pairs.length() > 0) {
                pairs.append("; ");
            }
            String label = labelById.get(crossing.connectionId());
            pairs.append("connection ");
            if (label != null && !label.isBlank()) {
                pairs.append('\'').append(label).append("' ");
            }
            pairs.append('(').append(crossing.connectionId()).append(") through note '")
                    .append(crossing.noteId()).append('\'');
        }

        String message = crossings.size()
                + switch (arm) {
                    case APPLIED -> " applied route(s) pass through a note: " + pairs
                            + ". A note is not a routing obstacle, so these routes were applied "
                            + "through it deliberately rather than failing. These are the "
                            + "crossings THIS CALL applied";
                    case QUEUED -> " route(s) computed by this call pass through a note: " + pairs
                            + ". A note is not a routing obstacle, so these routes will be applied "
                            + "through it deliberately rather than failing. Nothing has been "
                            + "applied: this routing is queued in the open batch. These are the "
                            + "crossings THIS CALL will apply once the batch is committed";
                    case AWAITING_APPROVAL -> " route(s) computed by this call pass through a "
                            + "note: " + pairs + ". A note is not a routing obstacle, so these "
                            + "routes will be applied through it deliberately rather than failing. "
                            + "Nothing has been applied: this routing is waiting on the human's "
                            + "decision. These are the crossings THIS CALL will apply once it is "
                            + "approved";
                }
                + THROUGH_NOTE_RECONCILIATION_AND_REMEDY;
        emitMirrored(StructuredWarningCodes.CONNECTION_ROUTED_THROUGH_NOTE, message,
                NOTE_MOVE_REMEDY_TOOL, List.copyOf(noteIds), warnings, structuredWarnings);
    }

    /**
     * How this per-call figure reconciles with {@code assess-layout}'s whole-view one, and the
     * note-moving remedy. Both hold on every arm: the reconciliation describes two counting shapes
     * rather than two model states, and {@code update-view-object} moves a DIFFERENT, already
     * existing object — it is not a recovery of this call, so it does not vary with the arm.
     */
    private static final String THROUGH_NOTE_RECONCILIATION_AND_REMEDY =
            ", counted per (connection, note) pair on the same geometry assess-layout uses; its "
            + "connectionThroughNoteCount is a whole-view figure and also counts notes crossed by "
            + "connections this call did not route, plus element images, so it can be higher. Move "
            + "the note(s) clear with " + NOTE_MOVE_REMEDY_TOOL + " and re-assess; moving a note "
            + "changes the routes around it, so position notes after routing.";

    /**
     * Emits a free-text warning for each set of non-container elements sharing an identical
     * position.
     *
     * <p>Stacked elements are degenerate routing input — every route between them starts and ends
     * in the same place — so the router's output is meaningless before the layout is separated.
     * Elements are named rather than listed by id, resolved through the model, because the caller
     * cannot see the canvas and an id tells it nothing about which boxes to pull apart.
     *
     * <p>Free text only, deliberately: this is pre-existing published output with no structured
     * code, and giving it one here would be a response-contract change unrelated to the caller
     * that prompted the extraction.
     */
    static void emitStackedElements(List<AssessmentNode> nodes, IArchimateModel model,
            List<String> warnings) {
        Map<String, List<String>> positionMap = new LinkedHashMap<>();
        for (AssessmentNode node : nodes) {
            if (!node.isContainer()) {
                String posKey = (int) node.x() + "," + (int) node.y();
                // Resolve element name via model for meaningful warning messages
                String nodeName = node.id();
                EObject nodeObj = ArchimateModelUtils.getObjectByID(model, node.id());
                if (nodeObj instanceof IDiagramModelObject dmo && dmo.getName() != null) {
                    nodeName = dmo.getName();
                }
                positionMap.computeIfAbsent(posKey, k -> new ArrayList<>()).add(nodeName);
            }
        }
        for (Map.Entry<String, List<String>> entry : positionMap.entrySet()) {
            if (entry.getValue().size() > 1) {
                warnings.add("Stacked elements at position (" + entry.getKey()
                        + "): " + entry.getValue()
                        + ". Run layout-within-group first to separate them for cleaner routing.");
            }
        }
    }

    /**
     * Emits the same disclosure for {@code strategy: "clear"}, which applies straight
     * centre-to-centre lines by removing every bendpoint.
     *
     * <p>Easy to overlook, and the wrong way round: clearing a route makes a note crossing MORE
     * likely, not less. There is no pathfinder left to steer the line anywhere, so it goes
     * straight through whatever sits between the endpoints. An empty bendpoint list is not the
     * absence of an applied route — it IS the applied route, and {@code assess-layout} scores it
     * exactly that way.
     *
     * <p>Collects the view's own geometry rather than taking it from the caller, because the clear
     * branch never builds an obstacle set and so has no node list of its own to pass.
     *
     * @param targetConnections the connections this call cleared
     */
    static void emitClearedRoutesThroughNote(List<IDiagramModelConnection> targetConnections,
            IArchimateDiagramModel diagramModel, DispatchArm arm,
            List<String> warnings, List<StructuredWarningDto> structuredWarnings) {
        if (targetConnections == null || targetConnections.isEmpty()) {
            return;
        }
        Map<String, List<AbsoluteBendpointDto>> clearedPaths = new LinkedHashMap<>();
        for (IDiagramModelConnection conn : targetConnections) {
            if (conn instanceof IDiagramModelArchimateConnection archConn) {
                clearedPaths.put(archConn.getId(), List.of());
            }
        }
        List<AssessmentNode> nodes = AssessmentCollector.collectAssessmentNodes(diagramModel);
        emitConnectionThroughNote(clearedPaths,
                AssessmentCollector.collectAssessmentConnections(diagramModel, nodes),
                nodes, arm, warnings, structuredWarnings);
    }

    /**
     * Rebuilds the path the router applied as {@code [sourceCentre, …bendpoints…, targetCentre]} —
     * the same shape {@code AssessmentCollector} produces when it reads the applied bendpoints back
     * out of the model, so the crossing verdict does not depend on which side of the write it is
     * taken from. The endpoints are element CENTRES here; the detector clips them to the perimeter,
     * which it must, because those first and last segments are never drawn.
     */
    private static List<double[]> centreAnchoredPath(AssessmentNode src, AssessmentNode tgt,
            List<AbsoluteBendpointDto> bendpoints) {
        List<double[]> path = new ArrayList<>(bendpoints.size() + 2);
        path.add(new double[]{src.x() + src.width() / 2, src.y() + src.height() / 2});
        for (AbsoluteBendpointDto bendpoint : bendpoints) {
            path.add(new double[]{bendpoint.x(), bendpoint.y()});
        }
        path.add(new double[]{tgt.x() + tgt.width() / 2, tgt.y() + tgt.height() / 2});
        return path;
    }

    /**
     * Writes ONE composed string to both sinks.
     *
     * <p>The two sinks are not a primary and a legacy copy — they are the same disclosure read by
     * two kinds of caller, and a caller triaging on the structured field must not be told something
     * the free-text field denies. Writing them from a single local is what makes a divergence
     * between them unrepresentable rather than merely unlikely; the previous shape repeated the
     * variable at two call sites, which is one edit away from rescoping only one of them.</p>
     */
    private static void emitMirrored(String code, String message, String remediationTool,
            List<String> violatorIds, List<String> warnings,
            List<StructuredWarningDto> structuredWarnings) {
        warnings.add(message);
        structuredWarnings.add(new StructuredWarningDto(code, message, remediationTool,
                violatorIds));
    }

    /** {@code [x, y, width, height]} for the detector's perimeter clip. */
    private static double[] rectOf(AssessmentNode node) {
        return new double[]{node.x(), node.y(), node.width(), node.height()};
    }

    /**
     * Resolves the display name for a crossed element ID.
     * Checks the viewObjectNameMap first, then falls back to model lookup.
     */
    static String resolveCrossedElementName(String crossedId,
            Map<String, String> viewObjectNameMap, IArchimateModel model) {
        if (crossedId == null) {
            return null;
        }
        String name = viewObjectNameMap.get(crossedId);
        if (name != null) {
            return name;
        }
        EObject obj = ArchimateModelUtils.getObjectByID(model, crossedId);
        if (obj instanceof IDiagramModelObject dmo) {
            return dmo.getName();
        }
        return crossedId; // fallback to ID if name can't be resolved
    }
}
