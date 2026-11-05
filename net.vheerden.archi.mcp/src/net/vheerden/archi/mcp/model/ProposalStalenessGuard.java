package net.vheerden.archi.mcp.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CommandStackEvent;
import org.eclipse.gef.commands.CommandStackEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.INameable;
import com.archimatetool.model.util.ArchimateModelUtils;

/**
 * Decides, at approve-time, whether a pending proposal went <strong>stale</strong> because the human
 * edited or removed an object the proposal targets during the (human-paced, minutes-long) review window
 * (retires the held-Command landmine that {@code ApprovalService}/{@code
 * PendingApprovalsView} previously only caught <em>after</em> a misapply).
 *
 * <p><strong>One listener per open model, one sequence per model.</strong> The guard registers a
 * {@link CommandStackEventListener} on the {@link CommandStack} of every model that is open, and removes
 * it when that model closes (no leak, no listener pointing at a disposed model). Making a different
 * model active never removes one, so a proposal pending on one model is still watched while work goes
 * on in another. Every post-change stack event (execute/undo/redo) advances that model's monotonic
 * sequence; when the event's command is <em>not</em> an {@link AgentAuthoredCommand} (the origin-tag
 * marker), it also advances that model's last-human sequence. That one {@code long} is the whole "did a
 * human touch this model since I proposed?" signal — no per-command object history is needed (GEF
 * commands expose none generically). A capture records which model's sequence it read, and is vetted
 * against that model's alone.</p>
 *
 * <p><strong>Staleness policy.</strong> A proposal is stale iff a target it
 * names either (i) no longer resolves (deleted/retyped away), (ii) has a changed
 * {@linkplain #fingerprint(EObject) attribute fingerprint} <em>and</em> a human command intervened since
 * propose, or (iii) — for a diagram-object target — has a changed
 * {@linkplain #boundsFingerprint(EObject) bounds fingerprint} (a pure drag/move) with a human intervening,
 * or (iv) — for a connection target — has a changed {@linkplain #routeFingerprint(EObject) route} (the
 * human redrew, cleared or added to its bendpoints) with a human intervening. A route is a containment
 * reference, so the attribute fingerprint never sees it, and a card approved over a redrawn route
 * would write its own route over the human's. Or (v) — again for a connection target — one of its ends
 * moved on the canvas with a human intervening: moved, resized, carried by a dragged parent, or replaced
 * by a reconnection. Archi stores each bendpoint as offsets from the ends' centres, so a frozen route
 * applied after an end moved no longer meets it. The ends are compared by position only, not tracked as
 * targets, so renaming an end does not refuse a card that only routes the connection.
 * An earlier policy used a deliberately <em>coarse</em> target set (single-ops re-resolve every target;
 * compounds tracked only the {@code viewId} / per-op entity ids, so a human edit to an untracked child
 * could slip). That gap is now closed: each <strong>compound</strong> proposal now
 * tracks the ids of the child view-objects/connections it actually touches (extracted at propose-time from
 * the built compound's typed child commands), and bounds are fingerprinted so a drag of a
 * tracked object also rejects-stale. Unrelated human edits still never touch a tracked
 * target, so they never trip staleness. An intervening <em>agent</em> command does not by itself mark
 * stale, by design: where the approval re-prepares, the rebuild re-resolves against what the agent did
 * and a hard conflict still rejects. Where it applies the compound frozen at propose time, approving
 * writes the card's values over the agent's own later change. Every update takes its undo snapshot
 * when it runs, so Undo then gives that change back.</p>
 *
 * <p><strong>Headless-testable cores.</strong> Sequence advancement runs against a real (Display-free)
 * {@link CommandStack}; the stale-vs-fresh logic is the pure {@link #decide} method over plain maps — both
 * exercised by {@code ProposalStalenessGuardTest}. The real {@code getAdapter(CommandStack.class)} wiring
 * and live human-edit detection are covered by live testing.</p>
 *
 * <p>Package-private, {@code model/}-only — imports GEF/EMF/Archi freely; no handler sees it.</p>
 */
final class ProposalStalenessGuard {

    private static final Logger logger = LoggerFactory.getLogger(ProposalStalenessGuard.class);

    private final Supplier<IArchimateModel> modelSupplier;

    /** One open model's stack listener and sequences. */
    private static final class Track {
        /** Identifies this track in a capture; never reused, never 0. */
        final long id;
        final CommandStack stack;
        final CommandStackEventListener listener;
        /** Monotonic count of post-change stack events seen on this model. */
        final AtomicLong sequence = new AtomicLong(0);
        /** The highest {@link #sequence} value at which a non-agent (human) command was observed. */
        final AtomicLong lastHumanSequence = new AtomicLong(0);

        Track(long id, CommandStack stack, java.util.function.Function<Track, CommandStackEventListener> listener) {
            this.id = id;
            this.stack = stack;
            this.listener = listener.apply(this);
        }
    }

    /** The track of every open model with a stack, by instance. Guarded by {@code this}. */
    private final Map<IArchimateModel, Track> tracks = new java.util.IdentityHashMap<>();
    private long lastTrackId;

    ProposalStalenessGuard(Supplier<IArchimateModel> modelSupplier) {
        this.modelSupplier = Objects.requireNonNull(modelSupplier, "modelSupplier must not be null");
    }

    // ---- Lifecycle (driven by the accessor's model-lifecycle events) ----

    /**
     * Starts watching {@code model}'s {@link CommandStack}, keeping every other model's watch. Called
     * on the lifecycle thread when a model opens or becomes active, and at start for every model
     * already open. Idempotent. Tolerant of a model with no CommandStack adapter (bare EMF model in a
     * headless context) — it simply registers nothing, and {@link #vet} then relies on fingerprint
     * comparison alone.
     */
    synchronized void onModelActive(IArchimateModel model) {
        CommandStack stack = stackOf(model);
        Track existing = tracks.get(model);
        if (stack == null || (existing != null && existing.stack == stack)) {
            return;
        }
        if (existing != null) {
            existing.stack.removeCommandStackEventListener(existing.listener);
        }
        Track track = new Track(++lastTrackId, stack, t -> event -> onStackEvent(t, event));
        stack.addCommandStackEventListener(track.listener);
        tracks.put(model, track);
        logger.debug("ProposalStalenessGuard watching model '{}' CommandStack", model.getName());
    }

    /** Stops watching one model (it closed). Idempotent. */
    synchronized void onModelClosed(IArchimateModel model) {
        Track track = tracks.remove(model);
        if (track != null) {
            track.stack.removeCommandStackEventListener(track.listener);
        }
    }

    /** Stops watching every model (accessor disposed, or none left open). Idempotent. */
    synchronized void onModelInactive() {
        for (Track track : tracks.values()) {
            track.stack.removeCommandStackEventListener(track.listener);
        }
        tracks.clear();
    }

    private static void onStackEvent(Track track, CommandStackEvent event) {
        if (event == null || !event.isPostChangeEvent()) {
            return;
        }
        long s = track.sequence.incrementAndGet();
        Command cmd = event.getCommand();
        // Human GUI edits reach Archi's CommandStack directly and are NOT AgentAuthoredCommand; agent
        // mutations are wrapped at MutationDispatcher.dispatchImmediate. A defensive null command
        // counts as human (we cannot prove agent authorship).
        if (!(cmd instanceof AgentAuthoredCommand)) {
            track.lastHumanSequence.set(s); // s is strictly increasing, so set == max
        }
    }

    private synchronized Track trackOf(IArchimateModel model) {
        return model == null ? null : tracks.get(model);
    }

    /** How many models are watched (test accessor). */
    synchronized int watchedCount() {
        return tracks.size();
    }

    /** The current monotonic stack sequence of the supplier's model (for tests). */
    long currentSequence() {
        Track track = trackOf(modelSupplier.get());
        return track == null ? 0L : track.sequence.get();
    }

    /** True iff a human (non-agent) command was observed on the supplier's model after {@code capturedSequence}. */
    boolean humanInterveneSince(long capturedSequence) {
        Track track = trackOf(modelSupplier.get());
        return track != null && track.lastHumanSequence.get() > capturedSequence;
    }

    /**
     * Whether a human command was observed on {@code model} after the capture. A capture taken on
     * another model's track (a reopened file is a new instance), or on a track {@code model} no longer
     * has, cannot be compared, so it counts as intervened and the fingerprints decide. A capture with no
     * track recorded is read against {@code model}'s track, and a model never watched reports none.
     */
    private boolean humanInterveneSince(StalenessCapture capture, IArchimateModel model) {
        Track track = trackOf(model);
        if (track == null) {
            return capture.trackId() != 0L; // watched at propose, unwatched now: cannot prove no human edit
        }
        if (capture.trackId() != 0L && capture.trackId() != track.id) {
            return true;
        }
        return track.lastHumanSequence.get() > capture.sequence();
    }

    // ---- Propose-time capture ----

    /**
     * Snapshots the current sequence and a fingerprint + display name for each resolvable target
     * id. Ids that resolve to nothing at all (or a null model) are skipped.
     *
     * <p>A target may be resolvable in two ways. Most are in containment. Some are not yet: a
     * prepare can resolve its target against an open batch's command queue, giving the caller a
     * real id for an object that joins containment only at commit. Both are fingerprinted here,
     * through {@code queuedResolver}, because the object is the same object either way — the queue
     * is simply where it lives for the moment.</p>
     *
     * <p>Fingerprinting it, rather than recording the id with a placeholder, is what keeps the
     * approve-time comparison honest. {@link #decide} reads a tracked id with no propose-time
     * fingerprint as removed, so a placeholder would refuse a target that had committed perfectly
     * normally with a message saying it no longer exists.</p>
     *
     * @param queuedResolver resolves an id against the caller's open batch, or null for no queue
     */
    StalenessCapture capture(java.util.Set<String> targetIds,
            java.util.function.Function<String, EObject> queuedResolver) {
        return capture(targetIds, queuedResolver, modelSupplier.get());
    }

    /** As {@link #capture(java.util.Set, java.util.function.Function)}, against {@code model}. */
    StalenessCapture capture(java.util.Set<String> targetIds,
            java.util.function.Function<String, EObject> queuedResolver, IArchimateModel model) {
        Track track = trackOf(model);
        long seq = track == null ? 0L : track.sequence.get();
        long trackId = track == null ? 0L : track.id;
        if (targetIds == null || targetIds.isEmpty()) {
            return new StalenessCapture(seq, Map.of(), Map.of(), Map.of(), trackId);
        }
        Map<String, String> fingerprints = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, String> bounds = new LinkedHashMap<>();
        Map<String, StalenessCapture.Connection> connections = new LinkedHashMap<>();
        if (model != null) {
            for (String id : targetIds) {
                if (id == null) {
                    continue;
                }
                EObject obj = resolve(model, id, queuedResolver);
                if (obj != null) {
                    fingerprints.put(id, fingerprint(obj));
                    names.put(id, displayName(obj, id));
                    String bfp = boundsFingerprint(obj); // null for non-diagram targets — skipped
                    if (bfp != null) {
                        bounds.put(id, bfp);
                    }
                    StalenessCapture.Connection conn = connectionShape(obj, id); // null unless a connection
                    if (conn != null) {
                        connections.put(id, conn);
                    }
                }
            }
        }
        return new StalenessCapture(seq, fingerprints, names, bounds, trackId, connections);
    }

    // ---- Approve-time staleness vetting ----

    /**
     * Vets a captured proposal against the <em>current</em> model: re-resolves each tracked target and
     * compares fingerprints, factoring in whether a human command intervened. Returns a fresh verdict, or
     * a stale verdict carrying the plain-language, target-named reason.
     *
     * <p>Re-resolves through the same two places {@link #capture} looked, and must: a target that was
     * queued at propose time can be approved before the batch commits, and reading only containment
     * would find nothing and report as removed something that is about to exist.</p>
     *
     * @param queuedResolver resolves an id against the caller's open batch, or null for no queue
     */
    StaleVerdict vet(StalenessCapture capture,
            java.util.function.Function<String, EObject> queuedResolver) {
        return vet(capture, queuedResolver, modelSupplier.get());
    }

    /** As {@link #vet(StalenessCapture, java.util.function.Function)}, against {@code model}. */
    StaleVerdict vet(StalenessCapture capture,
            java.util.function.Function<String, EObject> queuedResolver, IArchimateModel model) {
        if (capture == null || capture.targetIds().isEmpty()) {
            return StaleVerdict.fresh();
        }
        boolean humanIntervened = humanInterveneSince(capture, model);
        Map<String, String> currentFingerprints = new LinkedHashMap<>();
        Map<String, String> currentNames = new LinkedHashMap<>();
        Map<String, String> currentBounds = new LinkedHashMap<>();
        Map<String, StalenessCapture.Connection> currentConnections = new LinkedHashMap<>();
        for (String id : capture.targetIds()) {
            EObject obj = (model != null) ? resolve(model, id, queuedResolver) : null;
            if (obj != null) {
                currentFingerprints.put(id, fingerprint(obj));
                currentNames.put(id, displayName(obj, id));
                String bfp = boundsFingerprint(obj);
                if (bfp != null) {
                    currentBounds.put(id, bfp);
                }
                StalenessCapture.Connection conn = connectionShape(obj, id);
                if (conn != null) {
                    currentConnections.put(id, conn);
                }
            }
        }
        return decide(humanIntervened, capture, currentFingerprints, currentNames, currentBounds,
                currentConnections);
    }

    /**
     * Pure stale-vs-fresh decision core (no model/EMF access — feed it already-resolved maps). Covered
     * directly by {@code ProposalStalenessGuardTest} (cases a–e):
     * <ul>
     *   <li>a target id absent from {@code currentFingerprints} ⇒ stale (removed/retyped away);</li>
     *   <li>a target whose fingerprint changed ⇒ stale <em>only if</em> a human intervened; an
     *       agent-only intervening change rebuilds cleanly (fresh);</li>
     *   <li>all targets unchanged ⇒ fresh (no spurious rejection on unrelated edits).</li>
     * </ul>
     */
    /**
     * Back-compat 4-arg form (record-arity discipline — delegates to the 5-arg, never diverges). It passes
     * an empty {@code currentBounds}, so the move-check is <em>inert</em>: it is correct only for a
     * {@code capture} whose {@code bounds()} map is also empty (every attribute-only capture, and any
     * non-diagram-target capture). <strong>Do not call this overload with a {@code capture} that carries a
     * populated bounds map</strong> — it would silently skip move-staleness; the {@link #vet} path uses the
     * 5-arg form. Retained for the existing {@code decide}-core unit tests, which build attribute-only captures.
     * The same holds for the route check: this overload passes no current connections, so it is correct
     * only for a {@code capture} whose {@code connections()} map is empty.
     */
    static StaleVerdict decide(boolean humanIntervened, StalenessCapture capture,
            Map<String, String> currentFingerprints, Map<String, String> currentNames) {
        return decide(humanIntervened, capture, currentFingerprints, currentNames, Map.of());
    }

    /**
     * Pure stale-vs-fresh core, extended with a bounds/move check that is
     * <em>orthogonal</em> to the attribute fingerprint:
     * <ul>
     *   <li>a tracked id absent from {@code currentFingerprints} ⇒ stale (removed);</li>
     *   <li>an <em>attribute</em> fingerprint change with a human intervening ⇒ stale ({@code editedMessage});</li>
     *   <li>a <em>bounds</em> change (pure move/drag) with attributes unchanged and a human intervening ⇒
     *       stale ({@code movedMessage}). Only diagram-object targets carry a bounds entry, so non-diagram
     *       targets are never move-vetted.</li>
     * </ul>
     * Attribute edits take precedence over the move message when both changed (an edit subsumes a move).
     *
     * <p>Back-compat form: it passes no current connections, so the route and end checks of the full
     * form below are <em>inert</em>. <strong>Do not call it with a {@code capture} whose
     * {@code connections()} map is populated</strong> — it would silently skip both; {@link #vet} uses
     * the full form.</p>
     */
    static StaleVerdict decide(boolean humanIntervened, StalenessCapture capture,
            Map<String, String> currentFingerprints, Map<String, String> currentNames,
            Map<String, String> currentBounds) {
        return decide(humanIntervened, capture, currentFingerprints, currentNames, currentBounds, Map.of());
    }

    /**
     * The full stale-vs-fresh core: the bounds form above, plus a route check for connection targets,
     * orthogonal to both. Per tracked id, in this order of precedence: removed; attributes edited by a
     * human (an edit subsumes everything else); route changed by a human ({@code routeEditedMessage},
     * naming the connection as it was labelled at propose); an end of a connection moved, or is no
     * longer an end, with a human intervening ({@code endMovedMessage}); bounds changed by a human
     * ({@code movedMessage}). A connection carries no bounds, so the last never competes with the two
     * connection checks for one id.
     * A connection with no entry in {@code currentConnections} is not route-checked (the back-compat
     * overloads pass none).
     */
    static StaleVerdict decide(boolean humanIntervened, StalenessCapture capture,
            Map<String, String> currentFingerprints, Map<String, String> currentNames,
            Map<String, String> currentBounds, Map<String, StalenessCapture.Connection> currentConnections) {
        for (String id : capture.targetIds()) {
            String proposeFp = capture.fingerprints().get(id);
            if (!currentFingerprints.containsKey(id)) {
                return StaleVerdict.stale(removedMessage(capture.names().getOrDefault(id, id)));
            }
            if (!Objects.equals(proposeFp, currentFingerprints.get(id)) && humanIntervened) {
                String name = currentNames.getOrDefault(id, capture.names().getOrDefault(id, id));
                return StaleVerdict.stale(editedMessage(name));
            }
            // A tracked connection whose route the human redrew, cleared or added to.
            StalenessCapture.Connection proposeConn = capture.connections().get(id);
            StalenessCapture.Connection currentConn = currentConnections.get(id);
            if (proposeConn != null && currentConn != null && humanIntervened
                    && !Objects.equals(proposeConn.route(), currentConn.route())) {
                return StaleVerdict.stale(routeEditedMessage(proposeConn.label()));
            }
            // An end of a tracked connection that moved on the canvas, or was reconnected away.
            if (proposeConn != null && currentConn != null && humanIntervened) {
                // Source first, then target: the record's maps do not keep an order, and when both
                // changed (a swap) the message must not depend on hashing.
                for (String role : java.util.List.of(StalenessCapture.Connection.SOURCE,
                        StalenessCapture.Connection.TARGET)) {
                    String proposeEnd = proposeConn.endBounds().get(role);
                    if (proposeEnd != null && !Objects.equals(proposeEnd, currentConn.endBounds().get(role))) {
                        String endName = proposeConn.endNames().getOrDefault(role, role);
                        return StaleVerdict.stale(endMovedMessage(endName, proposeConn.label()));
                    }
                }
            }
            // A tracked diagram object the human dragged (bounds changed, attributes did not).
            String proposeBounds = capture.bounds().get(id);
            if (proposeBounds != null && humanIntervened
                    && !Objects.equals(proposeBounds, currentBounds.get(id))) {
                String name = currentNames.getOrDefault(id, capture.names().getOrDefault(id, id));
                return StaleVerdict.stale(movedMessage(name));
            }
        }
        return StaleVerdict.fresh();
    }

    static String editedMessage(String name) {
        return "This proposal is stale because you edited '" + name
                + "' after the agent proposed it. Reject it and ask the agent to retry.";
    }

    static String movedMessage(String name) {
        return "This proposal is stale because you moved '" + name
                + "' after the agent proposed it. Reject it and ask the agent to retry.";
    }

    /** @param connection the connection's label, already quoted (see {@link StalenessCapture.Connection#label()}) */
    static String routeEditedMessage(String connection) {
        return "This proposal is stale because you edited the route of " + connection
                + " after the agent proposed it. Reject it and ask the agent to retry.";
    }

    /**
     * @param end        the end's display name
     * @param connection the connection's label, already quoted
     */
    static String endMovedMessage(String end, String connection) {
        return "This proposal is stale because '" + end + "', an end of the connection " + connection
                + ", moved after the agent proposed it. Reject it and ask the agent to retry.";
    }

    static String removedMessage(String name) {
        return "This proposal is stale because '" + name
                + "' was removed or replaced after the agent proposed it. Reject it and ask the agent to retry.";
    }

    // ---- Helpers ----

    private CommandStack stackOf(IArchimateModel model) {
        if (model == null) {
            return null;
        }
        Object adapter = model.getAdapter(CommandStack.class);
        return (adapter instanceof CommandStack stack) ? stack : null;
    }

    /**
     * A change-sensitive fingerprint of an object's own changeable, non-derived <em>attributes</em>
     * (name, documentation, type discriminators, …). Containment/cross-references are excluded — they can
     * be cyclic and huge, and this targets the human edits (rename, retype, edit). A diagram
     * object's visual <em>bounds</em> are deliberately NOT folded in here — an {@code IBounds} is a
     * containment reference, not an {@code EAttribute}, so the loop below skips it; pure moves are tracked
     * orthogonally by {@link #boundsFingerprint(EObject)} so a rename and a drag stay
     * distinguishable.
     */
    /**
     * An id's object, from containment or — failing that — from the caller's open batch queue.
     *
     * <p>The single place that knows a target can live in either, so the propose-time snapshot and
     * the approve-time comparison cannot disagree about whether a target exists. Splitting them is
     * how one side comes to see a removal the other never saw.</p>
     */
    private static EObject resolve(IArchimateModel model, String id,
            java.util.function.Function<String, EObject> queuedResolver) {
        EObject obj = ArchimateModelUtils.getObjectByID(model, id);
        return (obj != null || queuedResolver == null) ? obj : queuedResolver.apply(id);
    }

    static String fingerprint(EObject obj) {
        if (obj == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(obj.eClass().getName());
        for (EStructuralFeature feature : obj.eClass().getEAllStructuralFeatures()) {
            if (feature instanceof EAttribute && !feature.isDerived() && !feature.isTransient()) {
                sb.append('\u0001').append(feature.getName()).append('=').append(obj.eGet(feature));
            }
        }
        return sb.toString();
    }

    /**
     * A change-sensitive fingerprint of a diagram object's visual <em>bounds</em> (x/y/width/height), or
     * {@code null} for any non-{@link IDiagramModelObject} target (which has no bounds). Tracked separately
     * from {@link #fingerprint(EObject)} so a pure drag/move is distinguishable from an attribute edit
     * Side-effect-free and null-safe.
     */
    static String boundsFingerprint(EObject obj) {
        if (obj instanceof IDiagramModelObject dmo) {
            IBounds b = dmo.getBounds();
            if (b != null) {
                return b.getX() + "," + b.getY() + "," + b.getWidth() + "," + b.getHeight();
            }
        }
        return null;
    }

    /**
     * A connection's bendpoints in order, each as {@code startX,startY,endX,endY}, joined by {@code |},
     * or {@code null} for anything that is not a connection. A connection with no bendpoints answers
     * the empty string, so clearing a route and adding a first point both change it.
     */
    static String routeFingerprint(EObject obj) {
        if (!(obj instanceof IDiagramModelConnection conn)) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (IDiagramModelBendpoint bp : conn.getBendpoints()) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(bp.getStartX()).append(',').append(bp.getStartY()).append(',')
                    .append(bp.getEndX()).append(',').append(bp.getEndY());
        }
        return sb.toString();
    }

    /** A connection target's route and label, or null for anything that is not a connection. */
    private static StalenessCapture.Connection connectionShape(EObject obj, String id) {
        if (!(obj instanceof IDiagramModelConnection conn)) {
            return null;
        }
        Map<String, String> endBounds = new LinkedHashMap<>();
        Map<String, String> endNames = new LinkedHashMap<>();
        putEnd(endBounds, endNames, StalenessCapture.Connection.SOURCE, conn.getSource());
        putEnd(endBounds, endNames, StalenessCapture.Connection.TARGET, conn.getTarget());
        return new StalenessCapture.Connection(routeFingerprint(conn), connectionLabel(conn, id),
                endBounds, endNames);
    }

    /**
     * Records one end by its role: which object it is and, for a view object, where it sits on the
     * canvas. Keyed by role rather than by id, so a reconnection that leaves the set of ids the same —
     * a self-connection moved off one object, or a source and target swapped — still changes the
     * entry, since Archi measures a bendpoint's start offsets from the source and its end offsets from
     * the target. An end that is a connection is recorded by id only (it has no bounds), so
     * reconnecting it onto an object counts; where that connection is drawn is not compared. A role
     * with no end — a connection a batch has queued is not connected until the batch commits — is not
     * recorded, so there is nothing to compare once it is.
     */
    private static void putEnd(Map<String, String> endBounds, Map<String, String> endNames, String role,
            Object end) {
        if (!(end instanceof com.archimatetool.model.IDiagramModelComponent component)) {
            return;
        }
        String endId = String.valueOf(component.getId());
        String position = absoluteBoundsFingerprint(end);
        endBounds.put(role, position == null ? endId : endId + "@" + position);
        endNames.put(role, String.valueOf(displayName(component, endId)));
    }

    /**
     * Where a view object sits on the canvas — its bounds with every ancestor's origin added — or
     * {@code null} for anything that is not a view object with bounds. Relative bounds would miss the
     * move that breaks a route most often: a human dragging the group that holds an end. No end read
     * here is ever detached: a connection to an object a batch has queued is itself queued, and a
     * queued connection has no ends until the batch commits (see {@code putEnd}).
     */
    static String absoluteBoundsFingerprint(Object obj) {
        if (!(obj instanceof IDiagramModelObject dmo) || dmo.getBounds() == null) {
            return null;
        }
        int[] b = ConnectionResponseBuilder.computeAbsoluteBounds(dmo);
        return b[0] + "," + b[1] + "," + b[2] + "," + b[3];
    }

    /**
     * A connection as a human would recognise it, quoted: its name, or else its two ends,
     * {@code 'source' → 'target'}. Most connections carry no name of their own, and an id on a card
     * tells the human nothing. Only the route and end messages use it; the edited and removed
     * messages keep naming a connection by {@link #displayName}.
     */
    static String connectionLabel(IDiagramModelConnection conn, String fallbackId) {
        String name = conn.getName();
        if (name != null && !name.isBlank()) {
            return "'" + name + "'";
        }
        if (conn.getSource() == null || conn.getTarget() == null) {
            return "'" + fallbackId + "'";
        }
        return "'" + displayName(conn.getSource(), conn.getSource().getId()) + "' \u2192 '"
                + displayName(conn.getTarget(), conn.getTarget().getId()) + "'";
    }

    static String displayName(EObject obj, String fallbackId) {
        if (obj instanceof INameable nameable) {
            String name = nameable.getName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return fallbackId;
    }

    /**
     * The outcome of vetting a proposal at approve-time: either {@link #fresh()} (rebuild + dispatch) or
     * {@link #stale(String)} carrying a plain-language, target-named reason for the human.
     */
    record StaleVerdict(boolean stale, String reason) {
        static StaleVerdict fresh() {
            return new StaleVerdict(false, null);
        }

        static StaleVerdict stale(String reason) {
            return new StaleVerdict(true, reason);
        }
    }
}
