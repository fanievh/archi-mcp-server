package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.model.routing.ConnectionThroughNoteDetector;
import net.vheerden.archi.mcp.response.dto.StructuredWarningCodes;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;

/**
 * Whether the rectangle an annotation is about to occupy lands on a route that is already drawn.
 *
 * <p>The published ordering guidance for annotations is "place them <em>after</em>
 * {@code auto-route-connections}", and it is necessary without being sufficient: after routing is
 * exactly when there are corridors to land in. A measured case placed three annotations in the
 * prescribed order and still took four route crossings, recovered only by moving the objects
 * afterwards. Guidance transmitted once at schema time cannot say whether <em>this</em> rectangle
 * is on a route; only a measurement taken against the routes on the view at the moment of the call
 * can, which is what this is.</p>
 *
 * <h2>Severity is not the same for the three annotations</h2>
 * <p>The assessor splits view objects on "is it a note" alone. A note is excluded from the layout
 * node set, so a route crossing it lands in the informational {@code connectionThroughNoteCount}
 * and can cap the view at good. A view-reference and a standalone image are ordinary layout nodes,
 * so a route crossing either is a rated {@code connectionPassThroughs} that can drive the view to
 * poor. The two non-notes are therefore the damaging ones, and the message says which metric the
 * caller is about to land in so a cosmetic crossing can be told from one that moves the rating.</p>
 *
 * <h2>What is measured, and against what</h2>
 * <p>The routes are the ones stored on the view now, read through the same collector the assessor
 * reads, and the crossing test is {@link ConnectionThroughNoteDetector} at
 * {@link LayoutQualityAssessor#PASS_THROUGH_INSET} — the inset both affected metrics use, so this
 * warning and the assessment cannot disagree about what counts as a crossing. Deliberately NOT the
 * router's obstacle inflation, which grows rectangles outward and would flag routes that clear the
 * annotation on the canvas.</p>
 *
 * <p>The rectangle is the <strong>resolved</strong> one — after the note-height auto-fit, after
 * {@code position}/{@code gap} has overridden x/y, after the empty-view fallback — converted to
 * absolute canvas coordinates, because a nested annotation's stored bounds are relative to its
 * parent while every route is absolute. Testing the requested rectangle would warn about a
 * placement that never happens.</p>
 */
final class AnnotationCorridorWarning {

    private AnnotationCorridorWarning() {}

    /** The remedy is to move the annotation, never to re-route around it. */
    private static final String REMEDY_TOOL = "update-view-object";

    /**
     * What a queued placement adds. Byte-identical to what it has always said — it was correct and
     * it is shipped, so it is carried across unchanged rather than re-worded alongside its new
     * sibling.
     */
    private static final String QUEUED_CLAUSE =
            " Measured against the routes on the view as they stand now: nothing in"
            + " this call has been applied yet, and a command queued beside it can"
            + " still move an endpoint before this placement lands, so re-check with"
            + " assess-layout once the call has actually been applied — after"
            + " end-batch when a batch is open.";

    /**
     * What a placement awaiting a human's decision adds.
     *
     * <p>It carries one thing the queued clause does not, and it is the reason this arm could not
     * simply reuse that clause. A proposal is stored and replayed on approval, so the object this
     * warning is about <strong>does not exist yet and its id will be minted when the human
     * approves</strong> — the id in the response is a projection, not a handle. The remedy names
     * {@code update-view-object}, which takes an id, so a caller that runs it now addresses nothing
     * or, worse, something else. Saying "nothing has been applied" without saying that would leave
     * the caller holding a remedy it cannot use and no way to know why.</p>
     */
    private static final String AWAITING_APPROVAL_CLAUSE =
            " Measured against the routes on the view as they stand now: nothing in this call has"
            + " been applied yet, and the placement is waiting on the human's decision, so the"
            + " routes can still change before it lands. The " + REMEDY_TOOL + " remedy cannot be"
            + " run against this annotation until then: it does not exist on the view yet, and its"
            + " id is minted when the change is approved — the id in this response is a projection"
            + " of the proposal, not a handle. Re-check with assess-layout once it has been"
            + " approved and applied.";

    /** Which of the three annotation tools is placing, and therefore which metric is at stake. */
    enum Kind {
        NOTE("note", "connectionThroughNoteCount",
                "informational — it caps the view at good and does not drive it to poor"),
        VIEW_REFERENCE("view-reference", "connectionPassThroughs",
                "RATED — a pass-through can drive the view's rating to poor"),
        IMAGE("image", "connectionPassThroughs",
                "RATED — a pass-through can drive the view's rating to poor");

        private final String label;
        private final String metric;
        private final String severity;

        Kind(String label, String metric, String severity) {
            this.label = label;
            this.metric = metric;
            this.severity = severity;
        }
    }

    /**
     * The warning for an annotation about to be placed at {@code (x, y, width, height)} inside
     * {@code parent}, or {@code null} when nothing on the view crosses it.
     *
     * <p>Null on a clean placement is deliberate: an empty list published beside a clean result
     * would read as a finding, and the field is omitted from the response instead.</p>
     *
     * @param arm  which dispatch arm this placement is on. On a deferred arm the routes read here
     *             may still move before the placement lands, and the annotation itself does not
     *             exist yet — both are disclosed in the message rather than suppressed
     */
    static StructuredWarningDto detect(IArchimateDiagramModel view, IDiagramModelContainer parent,
            int x, int y, int width, int height, Kind kind, DispatchArm arm) {
        if (view == null || width <= 0 || height <= 0) {
            return null;
        }
        List<AssessmentNode> nodes = AssessmentCollector.collectAssessmentNodes(view);
        List<AssessmentConnection> connections =
                AssessmentCollector.collectAssessmentConnections(view, nodes);
        if (connections.isEmpty()) {
            return null;
        }

        double[] origin = absoluteOriginOf(parent, view, nodes);
        if (origin == null) {
            // The rectangle cannot be put into the routes' frame, so there is nothing to compare.
            // Measuring anyway would test a rectangle at the wrong place on the canvas and report
            // the result as if it had been measured — worse than saying nothing.
            return null;
        }
        ConnectionThroughNoteDetector.NoteRect rect = new ConnectionThroughNoteDetector.NoteRect(
                "pending-annotation", origin[0] + x, origin[1] + y, width, height);

        // The ids the annotation will sit inside once it is placed: its parent and every ancestor
        // above it. A connection that starts or ends on one of them does not count as crossing the
        // annotation for the RATED metric — see the exclusion note on Kind below.
        Set<String> hostChain = ancestorChainOf(parent, view, nodes);

        Map<String, AssessmentNode> nodeMap = new LinkedHashMap<>();
        for (AssessmentNode node : nodes) {
            nodeMap.put(node.id(), node);
        }
        Map<String, String> labelById = new LinkedHashMap<>();
        List<ConnectionThroughNoteDetector.RoutedPath> paths = new ArrayList<>();
        for (AssessmentConnection conn : connections) {
            AssessmentNode src = nodeMap.get(conn.sourceNodeId());
            AssessmentNode tgt = nodeMap.get(conn.targetNodeId());
            if (src == null || tgt == null) {
                continue;
            }
            // A note is measured against EVERY connection, because the metric that counts it does
            // the same: a note has no endpoint relationship, so nothing is carved out. The rated
            // metric does carve out — it skips a connection's own source and target together with
            // their ancestors and descendants — so an annotation nested inside an endpoint is not
            // a pass-through there. Testing it here anyway would promise a rating cost the
            // assessment will never charge.
            if (kind != Kind.NOTE
                    && (hostChain.contains(conn.sourceNodeId())
                            || hostChain.contains(conn.targetNodeId()))) {
                continue;
            }
            labelById.put(conn.id(), conn.labelText());
            paths.add(new ConnectionThroughNoteDetector.RoutedPath(
                    conn.id(), conn.pathPoints(), rectOf(src), rectOf(tgt)));
        }

        List<ConnectionThroughNoteDetector.NoteCrossing> crossings =
                ConnectionThroughNoteDetector.detect(
                        paths, List.of(rect), LayoutQualityAssessor.PASS_THROUGH_INSET);
        if (crossings.isEmpty()) {
            return null;
        }

        Set<String> connectionIds = new LinkedHashSet<>();
        StringBuilder pairs = new StringBuilder();
        for (ConnectionThroughNoteDetector.NoteCrossing crossing : crossings) {
            connectionIds.add(crossing.connectionId());
            if (pairs.length() > 0) {
                pairs.append("; ");
            }
            String label = labelById.get(crossing.connectionId());
            pairs.append("connection ");
            if (label != null && !label.isBlank()) {
                pairs.append('\'').append(label).append("' ");
            }
            pairs.append('(').append(crossing.connectionId()).append(')');
        }

        String message = "The " + kind.label + " being placed lands on " + crossings.size()
                + " route(s) already drawn on this view: " + pairs
                + ". Each crossing will be counted in " + kind.metric + ", which is "
                + kind.severity + ". Placing after auto-route-connections does not avoid this — "
                + "after routing is when the corridors exist to land in. Move the "
                + kind.label + " clear with " + REMEDY_TOOL
                + " and re-run assess-layout; move the annotation rather than the routes, because "
                + "re-routing around it costs the route quality the crossing was not worth."
                + switch (arm) {
                    case APPLIED -> "";
                    case QUEUED -> QUEUED_CLAUSE;
                    case AWAITING_APPROVAL -> AWAITING_APPROVAL_CLAUSE;
                };

        return new StructuredWarningDto(
                StructuredWarningCodes.ANNOTATION_PLACED_IN_ROUTED_CORRIDOR,
                message, REMEDY_TOOL, List.copyOf(connectionIds));
    }

    /**
     * The absolute canvas origin of {@code parent}'s content box.
     *
     * <p>{@code (0, 0)} when the annotation is going onto the view itself. Otherwise the parent's
     * own absolute position, which the collector has already accumulated down the containment
     * chain — recomputing it here would be a second walk that could disagree with the one the
     * routes were built from.</p>
     *
     * <p><strong>{@code null} when the parent exists but is not on the live view yet.</strong> A
     * batch or a bulk call can name a container it created moments earlier in the same call: the
     * accessor resolves that to a real object, but one nothing has attached to the view, so the
     * collector has never seen it and its absolute position is genuinely unknown here. Returning
     * an origin of zero for that case would silently reinterpret the annotation's PARENT-RELATIVE
     * coordinates as absolute ones and compare a rectangle from somewhere else on the canvas
     * against the routes — inventing a crossing or hiding one, either way under a message that
     * says the routes were measured. Declining is the only honest answer, and it is distinct from
     * the {@code (0, 0)} above, which is a real origin rather than a missing one.</p>
     */
    private static double[] absoluteOriginOf(IDiagramModelContainer parent,
            IArchimateDiagramModel view, List<AssessmentNode> nodes) {
        if (parent == null || parent == view || !(parent instanceof IDiagramModelObject parentObj)) {
            return new double[]{0.0, 0.0};
        }
        for (AssessmentNode node : nodes) {
            if (node.id().equals(parentObj.getId())) {
                return new double[]{node.x(), node.y()};
            }
        }
        return null;
    }

    /**
     * The annotation's prospective parent and every ancestor above it, by view-object id.
     *
     * <p>Empty when the annotation goes onto the view itself. This is the set the rated
     * pass-through metric would treat the annotation as a descendant of, which is why a
     * connection terminating on any of them is not a crossing for the two non-note kinds.</p>
     */
    private static Set<String> ancestorChainOf(IDiagramModelContainer parent,
            IArchimateDiagramModel view, List<AssessmentNode> nodes) {
        Set<String> chain = new LinkedHashSet<>();
        if (parent == null || parent == view || !(parent instanceof IDiagramModelObject parentObj)) {
            return chain;
        }
        Map<String, AssessmentNode> byId = new LinkedHashMap<>();
        for (AssessmentNode node : nodes) {
            byId.put(node.id(), node);
        }
        String current = parentObj.getId();
        while (current != null && chain.add(current)) {
            AssessmentNode node = byId.get(current);
            current = (node != null) ? node.parentId() : null;
        }
        return chain;
    }

    /** The endpoint rectangle the detector clips the polyline against: {x, y, width, height}. */
    private static double[] rectOf(AssessmentNode node) {
        return new double[]{node.x(), node.y(), node.width(), node.height()};
    }
}
