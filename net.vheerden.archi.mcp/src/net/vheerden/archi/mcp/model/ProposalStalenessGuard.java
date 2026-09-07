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
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.INameable;
import com.archimatetool.model.util.ArchimateModelUtils;

/**
 * Decides, at approve-time, whether a pending proposal went <strong>stale</strong> because the human
 * edited or removed an object the proposal targets during the (human-paced, minutes-long) review window
 * (retires the held-Command landmine that {@code ApprovalService}/{@code
 * PendingApprovalsView} previously only caught <em>after</em> a misapply).
 *
 * <p><strong>One listener, monotonic sequence.</strong> The guard registers <em>exactly one</em>
 * {@link CommandStackEventListener} on the active model's {@link CommandStack}, re-registered on model
 * switch and removed on close (no leak, no listener pointing at a disposed model). Every
 * post-change stack event (execute/undo/redo) advances a monotonic {@link #sequence}; when the event's
 * command is <em>not</em> an {@link AgentAuthoredCommand} (the origin-tag marker), it also advances
 * {@link #lastHumanSequence}. That single {@code long} is the whole "did a human touch the model since I
 * proposed?" signal — no per-command object history is needed (GEF commands expose none generically).</p>
 *
 * <p><strong>Staleness policy.</strong> A proposal is stale iff a target it
 * names either (i) no longer resolves (deleted/retyped away), (ii) has a changed
 * {@linkplain #fingerprint(EObject) attribute fingerprint} <em>and</em> a human command intervened since
 * propose, or (iii) — for a diagram-object target — has a changed
 * {@linkplain #boundsFingerprint(EObject) bounds fingerprint} (a pure drag/move) with a human intervening.
 * An earlier policy used a deliberately <em>coarse</em> target set (single-ops re-resolve every target;
 * compounds tracked only the {@code viewId} / per-op entity ids, so a human edit to an untracked child
 * could slip). That gap is now closed: each <strong>compound</strong> proposal now
 * tracks the ids of the child view-objects/connections it actually touches (extracted at propose-time from
 * the built compound's typed child commands), and bounds are fingerprinted so a drag of a
 * tracked object also rejects-stale. Unrelated human edits still never touch a tracked
 * target, so they never trip staleness; an intervening <em>agent</em> command does not by itself mark
 * stale — the rebuild re-resolves and a hard conflict still rejects.</p>
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

    /** Monotonic count of post-change stack events seen since this guard was created. */
    private final AtomicLong sequence = new AtomicLong(0);
    /** The highest {@link #sequence} value at which a non-agent (human) command was observed. */
    private final AtomicLong lastHumanSequence = new AtomicLong(0);

    private CommandStack registeredStack;
    private CommandStackEventListener listener;

    ProposalStalenessGuard(Supplier<IArchimateModel> modelSupplier) {
        this.modelSupplier = Objects.requireNonNull(modelSupplier, "modelSupplier must not be null");
    }

    // ---- Lifecycle (driven by the accessor's model-lifecycle events) ----

    /**
     * Registers the single stack listener for {@code model}'s {@link CommandStack}, replacing any prior
     * registration (model switch). Tolerant of a model with no CommandStack adapter (bare EMF model in a
     * headless context) — it simply registers nothing, and {@link #vet} then relies on fingerprint
     * comparison alone.
     */
    synchronized void onModelActive(IArchimateModel model) {
        CommandStack stack = stackOf(model);
        if (stack == registeredStack) {
            return; // idempotent — same model/stack
        }
        unregister();
        if (stack == null) {
            return;
        }
        CommandStackEventListener l = this::onStackEvent;
        stack.addCommandStackEventListener(l);
        this.registeredStack = stack;
        this.listener = l;
        logger.debug("ProposalStalenessGuard registered on model '{}' CommandStack", model.getName());
    }

    /** Removes the stack listener (model closed, or accessor disposed). Idempotent. */
    synchronized void onModelInactive() {
        unregister();
    }

    private void unregister() {
        if (registeredStack != null && listener != null) {
            registeredStack.removeCommandStackEventListener(listener);
        }
        registeredStack = null;
        listener = null;
    }

    private void onStackEvent(CommandStackEvent event) {
        if (event == null || !event.isPostChangeEvent()) {
            return;
        }
        long s = sequence.incrementAndGet();
        Command cmd = event.getCommand();
        // Human GUI edits reach Archi's CommandStack directly and are NOT AgentAuthoredCommand; agent
        // mutations are wrapped at MutationDispatcher.dispatchImmediate. A defensive null command
        // counts as human (we cannot prove agent authorship).
        if (!(cmd instanceof AgentAuthoredCommand)) {
            lastHumanSequence.set(s); // s is strictly increasing, so set == max
        }
    }

    /** The current monotonic stack sequence (for capture and tests). */
    long currentSequence() {
        return sequence.get();
    }

    /** True iff a human (non-agent) command was observed after {@code capturedSequence}. */
    boolean humanInterveneSince(long capturedSequence) {
        return lastHumanSequence.get() > capturedSequence;
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
        if (targetIds == null || targetIds.isEmpty()) {
            return new StalenessCapture(sequence.get(), Map.of(), Map.of());
        }
        long seq = sequence.get();
        IArchimateModel model = modelSupplier.get();
        Map<String, String> fingerprints = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, String> bounds = new LinkedHashMap<>();
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
                }
            }
        }
        return new StalenessCapture(seq, fingerprints, names, bounds);
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
        if (capture == null || capture.targetIds().isEmpty()) {
            return StaleVerdict.fresh();
        }
        boolean humanIntervened = humanInterveneSince(capture.sequence());
        IArchimateModel model = modelSupplier.get();
        Map<String, String> currentFingerprints = new LinkedHashMap<>();
        Map<String, String> currentNames = new LinkedHashMap<>();
        Map<String, String> currentBounds = new LinkedHashMap<>();
        for (String id : capture.targetIds()) {
            EObject obj = (model != null) ? resolve(model, id, queuedResolver) : null;
            if (obj != null) {
                currentFingerprints.put(id, fingerprint(obj));
                currentNames.put(id, displayName(obj, id));
                String bfp = boundsFingerprint(obj);
                if (bfp != null) {
                    currentBounds.put(id, bfp);
                }
            }
        }
        return decide(humanIntervened, capture, currentFingerprints, currentNames, currentBounds);
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
     */
    static StaleVerdict decide(boolean humanIntervened, StalenessCapture capture,
            Map<String, String> currentFingerprints, Map<String, String> currentNames,
            Map<String, String> currentBounds) {
        for (String id : capture.targetIds()) {
            String proposeFp = capture.fingerprints().get(id);
            if (!currentFingerprints.containsKey(id)) {
                return StaleVerdict.stale(removedMessage(capture.names().getOrDefault(id, id)));
            }
            if (!Objects.equals(proposeFp, currentFingerprints.get(id)) && humanIntervened) {
                String name = currentNames.getOrDefault(id, capture.names().getOrDefault(id, id));
                return StaleVerdict.stale(editedMessage(name));
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
