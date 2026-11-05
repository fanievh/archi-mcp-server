package net.vheerden.archi.mcp.model;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.swt.widgets.Display;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.eclipse.emf.ecore.EObject;

import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.model.exceptions.ProposalStaleException;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.BatchStatusDto;
import net.vheerden.archi.mcp.response.dto.BatchSummaryDto;
import net.vheerden.archi.mcp.response.dto.PendingProposalView;
import net.vheerden.archi.mcp.response.dto.ProposalDto;

/**
 * Dispatches mutation commands to the ArchiMate model via CommandStack.
 *
 * <p><strong>CRITICAL:</strong> ALL model mutations MUST go through
 * {@code CommandStack.execute(Command)}. Direct EMF modification corrupts
 * the model. Reference: forum.archimatetool.com topic 1285.</p>
 *
 * <p><strong>Threading model:</strong> Validation happens on the Jetty thread.
 * The minimal Command is dispatched via {@code Display.syncExec()} to the UI
 * thread for CommandStack.execute. Results are passed back via
 * {@link AtomicReference}.</p>
 *
 * <p>Manages per-session state via {@link MutationContext}: the operational
 * mode, GUI-attached (immediate) or batch (queued). Approval is not a third
 * mode but a global, human-owned gate tested first, which turns a mutation into
 * a proposal whichever mode the session is in ({@link DispatchArm#of}).</p>
 *
 * <p><strong>Layer 3 (Model Boundary):</strong> This class imports
 * {@code org.eclipse.gef.commands.*}, {@code org.eclipse.swt.widgets.Display},
 * and {@code com.archimatetool.model.*}. No handler may import these types.</p>
 */
public class MutationDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(MutationDispatcher.class);

    private final Supplier<IArchimateModel> modelSupplier;
    private final ConcurrentHashMap<String, MutationContext> batchSessions = new ConcurrentHashMap<>();
    private Runnable onImmediateDispatchCallback;

    /**
     * Model instances observed closing, by epoch, each with its model id. Recorded from the
     * accessor's model-removed event and never cleared: a reopened file is a new instance. Closure is
     * per instance because two open copies of one file share an id, and closing one of them says
     * nothing about the other. Empty until a close is observed, so a dispatcher that never sees model
     * lifecycle events treats every bound model as open.
     */
    private final Map<Long, String> closedInstances = new ConcurrentHashMap<>();

    /**
     * An epoch per model <em>instance</em> the dispatcher has observed, assigned on first sight and
     * never reused. Queued commands reference an instance, and an id does not name one: a copied
     * file keeps its id and Archi opens both copies at once, and a reopened file is a new instance
     * with the old id. Weak keys, so a closed model is not held. Kept here rather than in the model's
     * own adapter map, which is an unsynchronised map the UI thread reads while epochs are first
     * assigned from request threads. Guarded by itself, and a leaf lock: it may be taken while a
     * session context's monitor is held (as {@link #queueForBatch} does), so nothing may call into a
     * {@link MutationContext} while holding it.
     */
    private final Map<IArchimateModel, Long> instanceEpochs = new WeakHashMap<>();
    private long lastEpoch;

    /**
     * Each open model instance's latest activation, as a rising tick, so that closing the active model
     * can return to the one active before it. Starts empty at each server start; weak keys, so a closed
     * model is not held. Guarded by itself, and a leaf lock: nothing is called while holding it, and it
     * is never taken while {@link #instanceEpochs} is held.
     */
    private final Map<IArchimateModel, Long> activations = new WeakHashMap<>();
    private long lastActivation;

    /**
     * The model instance the call on this thread was admitted against, or null. Set by {@link #admit}
     * and {@link #admitStackOperation}.
     *
     * <p>Consumed (read, then removed) by {@link #queueForBatch} and
     * {@link #storeProposal(String, PendingProposal)}, so the queue and the proposal stamp see the
     * model the call prepared against rather than whichever is active by then. Consuming on read
     * assumes an admitted call queues or stores at most once, which holds for every mutating tool
     * today; a call that queued twice would have its second command checked against the batch's
     * binding alone.</p>
     *
     * <p>Read without consuming by {@link #currentDispatchGuard} and {@link #stackOperation}, because
     * the layout tools dispatch and undo many times in one call: every immediate dispatch, undo and
     * redo of the call is bound to the admitted instance. The tool registry clears it when the call
     * exits ({@link #endCall}), and every path that reaches a queue, a store or a stack operation
     * without being admitted, such as the approval dock, also clears it first.</p>
     */
    private final ThreadLocal<Admission> admission = new ThreadLocal<>();

    /**
     * One admitted call. {@code instance} is weak so a closed model is not held by an idle pooled
     * thread. The mutable fields are written only on the UI thread, inside the runnables that resolve
     * a stack, so they need no lock.
     */
    private static final class Admission {
        final long epoch;
        final String modelId;
        final String modelName;
        final String operation;
        final WeakReference<IArchimateModel> instance;
        /** An undo or redo tool, which prepares nothing; else a mutating call. */
        final boolean stackOperation;
        /**
         * Whether the call resolved its model as a tool call (by address, batch, default or as the only
         * model open) rather than following the model active in Archi. A bound call runs on its own
         * instance while that is open, whichever model is active.
         */
        final boolean bound;
        /** Whether the guard has seen this call dispatch, so its stamped entries are all it owns. */
        boolean dispatched;
        /** Marks the stack entries this call makes, so the call can tell its own from any other's. */
        final CallStamp stamp;
        /** The entries this call undid itself, most recent last: the only ones it may redo. */
        final List<Command> undoneByCall = new ArrayList<>();

        Admission(long epoch, IArchimateModel model, String operation, boolean stackOperation, String sessionId,
                boolean bound) {
            this.epoch = epoch;
            this.bound = bound;
            this.stamp = new CallStamp(sessionId);
            this.modelId = model.getId();
            this.modelName = model.getName();
            this.operation = operation;
            this.instance = new WeakReference<>(model);
            this.stackOperation = stackOperation;
        }

        /** The admitted instance while it is open, else null. */
        IArchimateModel openInstance(MutationDispatcher dispatcher) {
            IArchimateModel model = instance.get();
            return model == null || dispatcher.isClosed(epoch) ? null : model;
        }
    }

    /**
     * The owner of the stack entries one admitted call makes. Compared only by identity: two calls of
     * one session are two owners, so neither undoes the other's entries. The session id is carried,
     * not compared.
     */
    static final class CallStamp {
        final String sessionId;

        CallStamp(String sessionId) {
            this.sessionId = sessionId;
        }
    }

    /**
     * The binding check for the dispatch this thread is about to make, or null for an unbound one.
     * Set around a bound dispatch (a batch commit, an approved proposal) and read by
     * {@link #dispatchImmediate} on the calling thread, which hands it to the UI-thread runnable. The
     * check then runs again in the same runnable that resolves the {@code CommandStack}: models open
     * and close on the UI thread, so a switch can no longer fall between the check and the stack.
     */
    private final ThreadLocal<Function<IArchimateModel, ModelAccessException>> dispatchGuard =
            new ThreadLocal<>();

    /**
     * The owner stamped on the stack entry of the explicitly guarded dispatch this thread is about to
     * make: a fresh stamp per batch commit or approved proposal, carrying the session it was made for.
     * A fresh stamp is never any call's own, so no call's rollback ever undoes one.
     */
    private final ThreadLocal<CallStamp> dispatchOwner = new ThreadLocal<>();

    /**
     * Set by {@link #dispatchImmediateOrDecline} around its dispatch and read by {@link #agentEntry} on the
     * same thread: the entry it builds undoes a delegate that declined partway.
     */
    private final ThreadLocal<Boolean> rollBackOnDecline = new ThreadLocal<>();

    /**
     * A dispatch binding that names its own target instance rather than following the model active in
     * Archi. Its target is resolved on the UI thread, in the same runnable that takes the stack; null
     * once that instance has closed, which the check then refuses.
     */
    interface BoundGuard extends Function<IArchimateModel, ModelAccessException> {
        IArchimateModel target();
    }

    private static BoundGuard boundGuard(Supplier<IArchimateModel> target,
            Function<IArchimateModel, ModelAccessException> check) {
        return new BoundGuard() {
            @Override
            public IArchimateModel target() {
                return target.get();
            }

            @Override
            public ModelAccessException apply(IArchimateModel model) {
                return check.apply(model);
            }
        };
    }

    /**
     * Per-thread nesting depth of an open "silent measurement" window. While
     * &gt; 0 <em>on the calling thread</em>, that thread has declared that any
     * model-content change it performs now is part of a net-zero measurement
     * (the route-normalized baseline probe routes a throwaway copy to measure
     * it) and must NOT advance the model-changed signal.
     *
     * <p><strong>Thread-scoped deliberately:</strong> the window must never
     * suppress a REAL mutation performed concurrently by another thread. Since
     * the ecore change listener fires synchronously on the thread that mutates,
     * a {@link ThreadLocal} guard is only consulted for changes originating on
     * the same thread that opened the window — a different thread's live
     * mutation still bumps the version correctly. Re-entrant because the
     * composer runs the probe once per arm; clamped at 0 so an unmatched
     * {@code end} cannot drive it negative.</p>
     */
    private final ThreadLocal<Integer> silentMeasurementDepth =
            ThreadLocal.withInitial(() -> 0);

    /**
     * Time-to-live for abandoned pending proposals (30 min).
     * Proposals older than this are swept on {@link #listAllPending} and on propose so the per-session
     * queue does not fill to the {@link MutationContext#MAX_PENDING_PROPOSALS hard cap}. Expiry is
     * surfaced (logged + queue-changed), never destructive to the model.
     */
    static final Duration PROPOSAL_TTL = Duration.ofMinutes(30);

    /**
     * The staleness guard — one {@code CommandStack} listener per open model, re-resolving and
     * fingerprinting a proposal's targets so approve rejects-stale rather than misapplying a frozen command.
     */
    private final ProposalStalenessGuard stalenessGuard;

    /** The shared "stored request → fresh command + preconditions" rebuilder used by the approve path. */
    private final ProposalBuilder proposalBuilder = new ProposalBuilder();

    /** Which open model each tool call acts on. */
    private final ModelSelection selection;

    /**
     * Listeners notified whenever the pending-approval queue changes. The dispatcher
     * fires these after every queue-mutating point so the Pending Approvals dock view can rebuild.
     * {@link CopyOnWriteArrayList} so add/remove/iterate are lock-free and a listener registering
     * or unregistering during a fire never throws {@code ConcurrentModificationException}.
     */
    private final CopyOnWriteArrayList<ApprovalQueueListener> queueListeners = new CopyOnWriteArrayList<>();

    /**
     * The global, human-owned approval-mode source. Defaults to a fail-safe
     * GATED provider so an unwired dispatcher refuses to apply silently — the server/UI
     * bootstrap replaces it with a lambda reading the human-owned {@code ApprovalMode}.
     *
     * <p>{@code volatile}: written once from the UI/bootstrap thread via
     * {@link #setApprovalModeProvider}, read on every Jetty request thread — the keyword
     * supplies the happens-before edge so request threads always see the wired provider.</p>
     */
    private volatile ApprovalModeProvider approvalModeProvider = () -> true;

    public MutationDispatcher(Supplier<IArchimateModel> modelSupplier) {
        this.modelSupplier = Objects.requireNonNull(modelSupplier, "modelSupplier must not be null");
        this.stalenessGuard = new ProposalStalenessGuard(modelSupplier);
        this.selection = new ModelSelection(modelSupplier, this::epochOf, this::batchInstance, this::isClosed);
    }

    /** Which open model each tool call acts on. */
    public ModelSelection selection() {
        return selection;
    }

    /** Sets where the open models are listed and how a model's unsaved state is read. */
    void setOpenModels(Supplier<List<IArchimateModel>> openModels,
            java.util.function.Predicate<IArchimateModel> dirty) {
        selection.setOpenModels(openModels, dirty);
    }

    /** The open instance a session's open batch is bound to, or null. */
    private IArchimateModel batchInstance(String sessionId) {
        MutationContext context = batchSessions.get(sessionId);
        if (context == null || context.getMode() != OperationalMode.BATCH) {
            return null;
        }
        long bound = context.getBoundModelEpoch();
        return bound == 0L ? null : openInstance(bound);
    }

    /**
     * The epoch of this model instance, assigned on first observation. Never 0, which stands for
     * "no instance recorded".
     */
    long epochOf(IArchimateModel model) {
        synchronized (instanceEpochs) {
            return instanceEpochs.computeIfAbsent(model, m -> ++lastEpoch);
        }
    }

    // ---- Staleness-guard lifecycle (driven by the accessor's model events) ----

    /**
     * Observes a model that is open, without making it active: it gets an epoch and the staleness guard
     * watches its {@code CommandStack}. Called by the accessor at start for every model already open, so
     * a model that is addressed without ever being made active is watched too.
     */
    void observeOpen(IArchimateModel model) {
        if (model != null) {
            epochOf(model);
            stalenessGuard.onModelActive(model);
        }
    }

    /**
     * Watches the now-active model's {@code CommandStack} for the staleness guard, keeping every other
     * open model's watch. Called by the accessor when a model becomes active / on model switch. Idempotent.
     */
    public void onModelActive(IArchimateModel model) {
        if (model != null) {
            epochOf(model); // observed: an open instance with this id now exists
            synchronized (activations) {
                activations.put(model, ++lastActivation);
            }
        }
        stalenessGuard.onModelActive(model);
    }

    /**
     * Records that a model instance was closed in Archi, whether or not it was the active one. A
     * batch or proposal bound to it is then refused with {@link ErrorCode#BOUND_MODEL_CLOSED} rather
     * than as a mismatch, because the remedy is to reopen it, not to switch to it; and a batch that
     * held queued work at this moment stays refused even after a reopen, since its commands reference
     * the closed instance. Another open copy with the same id is unaffected.
     */
    public void onModelClosed(IArchimateModel model) {
        if (model == null) {
            return;
        }
        synchronized (activations) {
            activations.remove(model);
        }
        stalenessGuard.onModelClosed(model);
        selection.onModelClosed(model);
        if (model.getId() == null) {
            return;
        }
        long epoch = epochOf(model);
        closedInstances.put(epoch, model.getId());
        for (MutationContext context : batchSessions.values()) {
            context.onModelClosed(epoch);
        }
    }

    /**
     * The model to make active after {@code closing}, the active one, has closed: the most recently
     * active of {@code remaining}, else the first listed when none of them was ever active, else null.
     * {@code closing} is never chosen, even when it is still listed.
     */
    public IArchimateModel fallbackAfterClose(List<IArchimateModel> remaining, IArchimateModel closing) {
        if (remaining == null) {
            return null;
        }
        IArchimateModel first = null;
        IArchimateModel latest = null;
        long latestTick = 0;
        synchronized (activations) {
            for (IArchimateModel model : remaining) {
                if (model == null || model == closing) {
                    continue;
                }
                if (first == null) {
                    first = model;
                }
                Long tick = activations.get(model);
                if (tick != null && tick > latestTick) {
                    latest = model;
                    latestTick = tick;
                }
            }
        }
        return latest != null ? latest : first;
    }

    /** Removes the staleness guard's stack listener (active model closed, or accessor disposed). */
    public void onModelInactive() {
        stalenessGuard.onModelInactive();
    }

    /**
     * Captures the propose-time staleness snapshot (stack sequence + per-target fingerprints + names) for
     * the given target ids. Called by the accessor at each propose site; the result is stored on the
     * {@link PendingProposal} and compared at approve. Empty/null targets ⇒ an always-fresh capture.
     *
     * <p>Targets are resolved against containment and, failing that, against the session's own open
     * batch. A target the batch has queued is a real object with a real id that a prepare has
     * already accepted, and it becomes editable the moment the batch commits — which can happen
     * while the card is still on screen. Fingerprinting it is what lets the approve-time comparison
     * see an edit made in that window instead of treating the proposal as having nothing to check.</p>
     */
    StalenessCapture captureStaleness(String sessionId, Set<String> targetIds) {
        return stalenessGuard.capture(targetIds, id -> queuedAny(sessionId, id),
                selection.peekModelForCall(modelSupplier.get()));
    }

    /**
     * Resolves an id against anything the session's open batch has queued, of any kind.
     *
     * <p>Unlike its typed siblings, which exist so that naming the wrong kind still fails. The
     * staleness snapshot has no expected kind: it is asking whether the id names something this
     * request has made, so it can fingerprint it and compare later.</p>
     */
    private EObject queuedAny(String sessionId, String id) {
        MutationContext context = batchSessions.get(sessionId);
        return context == null ? null : context.queuedAny(id);
    }

    /**
     * Injects the global approval-mode source. Called once at server/UI bootstrap
     * with a lambda reading the human-owned {@code ApprovalMode} holder; tests pass a constant.
     *
     * <p>This sets the <em>source</em> of the read-only bit, not the bit itself — there is no
     * MCP-callable path to flip approval mode (the only robust guard is non-existence).</p>
     *
     * @param provider the read-only approval-mode source (must not be null)
     */
    public void setApprovalModeProvider(ApprovalModeProvider provider) {
        this.approvalModeProvider = Objects.requireNonNull(provider, "approvalModeProvider must not be null");
    }

    /**
     * Sets a callback invoked after each immediate command dispatch.
     * Used by ArchiModelAccessorImpl to increment the version counter
     * when proposals are approved and dispatched immediately.
     *
     * @param callback the callback to invoke, or null to clear
     */
    void setOnImmediateDispatchCallback(Runnable callback) {
        this.onImmediateDispatchCallback = callback;
    }

    // ---- Silent measurement window ----

    /**
     * Opens a silent measurement window (re-entrant). While any window is open,
     * {@link #isSilentMeasurementActive()} returns {@code true} and the model
     * content-change version bump is suppressed for the net-zero measurement.
     * Must be paired with {@link #endSilentMeasurement()} in a {@code finally}.
     */
    public void beginSilentMeasurement() {
        silentMeasurementDepth.set(silentMeasurementDepth.get() + 1);
    }

    /**
     * Closes the innermost silent measurement window on the calling thread.
     * Clamped at 0 — an unmatched call is a no-op rather than driving the depth
     * negative. Resets the thread-local to its initial value when the outermost
     * window closes so pooled threads carry no residue.
     */
    public void endSilentMeasurement() {
        int depth = silentMeasurementDepth.get();
        if (depth <= 1) {
            silentMeasurementDepth.remove();
        } else {
            silentMeasurementDepth.set(depth - 1);
        }
    }

    /**
     * @return {@code true} while a silent measurement window is open on the
     *         calling thread. Windows opened by other threads are never visible.
     */
    public boolean isSilentMeasurementActive() {
        return silentMeasurementDepth.get() > 0;
    }

    // ---- Approval-queue change notification ----

    /**
     * Registers a listener notified whenever the pending-approval queue changes. Idempotent-safe
     * for callers that re-register; the Pending Approvals view adds itself on open and removes on
     * dispose.
     *
     * @param listener the listener to add (ignored if null)
     */
    public void addQueueListener(ApprovalQueueListener listener) {
        if (listener != null) {
            queueListeners.addIfAbsent(listener);
        }
    }

    /**
     * Removes a previously registered queue listener.
     *
     * @param listener the listener to remove (no-op if null or not registered)
     */
    public void removeQueueListener(ApprovalQueueListener listener) {
        if (listener != null) {
            queueListeners.remove(listener);
        }
    }

    /**
     * Notifies all queue listeners that the pending-approval queue changed. Fired <em>outside</em>
     * any session lock and after the queue mutation has completed. Each callback is guarded so a
     * throwing listener cannot break the mutation path it fired from (the model layer must never be
     * destabilised by a misbehaving UI observer).
     */
    private void fireQueueChanged() {
        for (ApprovalQueueListener listener : queueListeners) {
            try {
                listener.onQueueChanged();
            } catch (RuntimeException e) {
                logger.warn("Approval-queue listener threw during onQueueChanged (ignored)", e);
            }
        }
    }

    // ---- Immediate dispatch (GUI-attached mode) ----

    /**
     * Dispatches a command immediately via Display.syncExec + CommandStack.
     * Used in GUI-attached mode for real-time model updates.
     *
     * @param command the GEF command to execute
     * @throws MutationException if dispatch fails
     */
    public void dispatchImmediate(Command command) throws MutationException {
        logger.info("Dispatching immediate command: {}", command.getLabel());
        // An admitted dispatch resolves its model in the guard, which refuses when none is active.
        IArchimateModel model = dispatchGuard.get() == null && admission.get() != null ? null : requireModel();
        // Stamp authorship at the single admission chokepoint. Every agent
        // mutation — immediate single ops, the batch-commit compound, and approved-proposal
        // executes — funnels here, so wrapping once tags them all. Human GUI edits reach
        // Archi's CommandStack directly and stay untagged. One wrapper == one stack entry,
        // preserving the speculative undo(undoCount) arithmetic.
        AgentAuthoredCompoundCommand tagged = agentEntry(command);
        Function<IArchimateModel, ModelAccessException> guard = currentDispatchGuard();
        dispatchOnUiThread(() -> {
            executeEntry(guardedStack(model, guard), tagged);
            return null;
        });
    }

    /**
     * Dispatches {@code command} as one immediate call, and refuses the call when anything in it declined.
     * A decline that wrote part of the change is undone in the same UI-thread run first, so a refused call
     * leaves no undo entry and the model as it was. Live, that write and undo still move the model version.
     *
     * <p>Not for the approve path, which needs a normal return to put the card back, nor for bulk-mutate,
     * which keeps the rest of the call and reports each declined operation.</p>
     *
     * @throws ModelAccessException {@code MUTATION_FAILED} carrying the skip reasons, when nothing was changed
     * @throws MutationException when a partial write could not be undone
     */
    void dispatchImmediateOrDecline(Command command) throws MutationException {
        rollBackOnDecline.set(Boolean.TRUE);
        try {
            dispatchImmediate(command);
        } finally {
            rollBackOnDecline.remove();
        }
        List<String> reasons = CommitSkippableCommand.collectSkipReasons(command);
        if (!reasons.isEmpty()) {
            throw new ModelAccessException(String.join("; ", reasons),
                    ErrorCode.MUTATION_FAILED, null,
                    "Nothing was changed. Read the model again and repeat the call if it is still wanted.", null);
        }
    }

    /**
     * Queues {@code command} in a batch, else dispatches it at once, keeping what ran when part of it
     * declined: the caller reports each declined operation. For bulk-mutate.
     *
     * @return the batch sequence number if queued, null if dispatched
     */
    Integer dispatchOrQueueKeepingDeclines(String sessionId, Command command, String description)
            throws MutationException {
        if (getMode(sessionId) == OperationalMode.BATCH) {
            return queueForBatch(sessionId, command, description);
        }
        dispatchImmediate(command);
        return null;
    }

    /**
     * Executes {@code entry} on {@code stack}. Runs on the UI thread. A delegate that declined with
     * nothing written leaves no entry on the stack and returns normally, so the caller reads its skip
     * reason as before.
     */
    static void executeEntry(CommandStack stack, AgentAuthoredCompoundCommand entry) {
        try {
            stack.execute(entry);
        } catch (AgentAuthoredCompoundCommand.Declined declined) {
            // nothing was written and GEF pushed nothing: no entry is the right record of it
        }
    }

    /**
     * The stack entry for {@code command}, built on the calling thread. Owned by this thread's
     * admitted call, unless an explicit guard is set: a batch commit or an approved proposal is no
     * single call's work. Lets a test double of {@link #dispatchCommand} build its entry exactly as
     * production does.
     *
     * <p>An immediate entry that fails as it runs moves the model version, since part of it may still be
     * in the model; a batch commit and an approval move it themselves.</p>
     */
    AgentAuthoredCompoundCommand agentEntry(Command command) {
        boolean rollBack = rollBackOnDecline.get() != null;
        if (dispatchGuard.get() != null) {
            return new AgentAuthoredCompoundCommand(command, dispatchOwner.get(), rollBack);
        }
        Admission admitted = admission.get();
        return new AgentAuthoredCompoundCommand(command, admitted != null ? admitted.stamp : null, rollBack,
                versionMoveForThisCall());
    }

    /**
     * {@link #onImmediateDispatchCallback} bound to this thread's call scope, or null when none is set. The
     * entry runs on the UI thread, which has no call scope of its own, so the version moved is that of the
     * model this call acts on rather than whichever is active.
     */
    private Runnable versionMoveForThisCall() {
        Runnable callback = onImmediateDispatchCallback;
        if (callback == null) {
            return null;
        }
        ModelSelection.Call call = selection.scope();
        return () -> {
            ModelSelection.Call outer = selection.adopt(call);
            try {
                callback.run();
            } finally {
                selection.restore(outer);
            }
        };
    }

    /**
     * The stack a dispatch executes on. Unbound: the model resolved by the caller, as before. Guarded:
     * the guard's own target when it names one ({@link BoundGuard}), else the model active at this
     * moment, provided the guard accepts it — called on the UI thread, so the check and the stack it
     * vouches for are read with no model event able to come between them.
     */
    CommandStack guardedStack(IArchimateModel resolved,
            Function<IArchimateModel, ModelAccessException> guard) throws MutationException {
        if (guard == null) {
            return getCommandStack(resolved);
        }
        IArchimateModel current = guard instanceof BoundGuard bound ? bound.target() : modelSupplier.get();
        ModelAccessException refusal = guard.apply(current);
        if (refusal != null) {
            throw refusal;
        }
        return getCommandStack(current);
    }

    /**
     * Runs {@link #dispatchCommand} with {@code guard} as this thread's {@link #dispatchGuard}, its
     * stack entry owned by a fresh stamp for {@code sessionId}.
     */
    private void dispatchGuarded(Command command, Function<IArchimateModel, ModelAccessException> guard,
            String sessionId) throws MutationException {
        dispatchGuard.set(guard);
        dispatchOwner.set(new CallStamp(sessionId));
        try {
            dispatchCommand(command);
        } finally {
            dispatchGuard.remove();
            dispatchOwner.remove();
        }
    }

    /** The commit-time binding check for this batch, for use as a {@link #dispatchGuard}. */
    private Function<IArchimateModel, ModelAccessException> commitGuard(MutationContext context) {
        if (context.isBoundModelAddressed()) {
            return boundGuard(() -> bindingTarget(context), target -> boundModelRefusal(context, target, "Commit"));
        }
        return target -> boundModelRefusal(context, target, "Commit");
    }

    /**
     * What a batch's binding is checked against. A batch bound to the model its begin call addressed
     * is checked against its own instance, or, once that has closed, against the one open instance
     * with its id (which an empty batch may rebind to); any other batch against the model active now.
     */
    private IArchimateModel bindingTarget(MutationContext context) {
        if (!context.isBoundModelAddressed()) {
            return modelSupplier.get();
        }
        IArchimateModel own = openInstance(context.getBoundModelEpoch());
        return own != null ? own : soleOpenInstanceWithId(context.getBoundModelId());
    }

    /** The one open, observed instance with this id, or null when there is none or more than one. */
    private IArchimateModel soleOpenInstanceWithId(String modelId) {
        IArchimateModel found = null;
        synchronized (instanceEpochs) {
            for (Map.Entry<IArchimateModel, Long> entry : instanceEpochs.entrySet()) {
                if (Objects.equals(modelId, entry.getKey().getId()) && !isClosed(entry.getValue())) {
                    if (found != null) {
                        return null;
                    }
                    found = entry.getKey();
                }
            }
        }
        return found;
    }

    /** The commit-time binding check for a session's open batch (for test doubles of dispatch). */
    Function<IArchimateModel, ModelAccessException> commitGuard(String sessionId) {
        return commitGuard(getActiveContext(sessionId));
    }

    /**
     * The binding check of the dispatch in progress on this thread, or null. Lets a test double of
     * {@link #dispatchCommand} resolve its stack through {@link #guardedStack} exactly as production does.
     *
     * <p>An explicit guard (a batch commit, an approved proposal) wins. Otherwise a dispatch made by an
     * admitted call is bound to the instance the call was admitted against: the call prepared its
     * command there, so the command's undo entry belongs on that instance's stack and nowhere else.
     * Unbound when neither applies.</p>
     */
    Function<IArchimateModel, ModelAccessException> currentDispatchGuard() {
        Function<IArchimateModel, ModelAccessException> explicit = dispatchGuard.get();
        if (explicit != null) {
            return explicit;
        }
        Admission admitted = admission.get();
        if (admitted == null) {
            return null;
        }
        if (admitted.bound) {
            return boundGuard(() -> admitted.openInstance(this), active -> admittedDispatchRefusal(admitted, active));
        }
        return active -> admittedDispatchRefusal(admitted, active);
    }

    /**
     * The refusal for a dispatch of an admitted call with {@code active} active, or null when the
     * admitted instance is open and active. Runs on the UI thread inside {@link #guardedStack}.
     *
     * <p>On a refusal the call's own earlier entries are undone first, on the admitted instance's own
     * stack, so that a refused call leaves the model as it found it; the message says how far that got.
     * A closed instance has nothing left to undo.</p>
     */
    private ModelAccessException admittedDispatchRefusal(Admission admitted, IArchimateModel active) {
        admitted.dispatched = true; // from here on the call owns exactly the entries stamped with it
        IArchimateModel instance = admitted.openInstance(this);
        if (instance != null && instance == active) {
            return null;
        }
        String outcome;
        if (instance == null) {
            outcome = "Nothing was applied to any open model.";
        } else {
            outcome = rollBackOwnEntries(admitted, instance);
        }
        return admittedRefusal(admitted, active, "'" + admitted.operation + "'", outcome, "");
    }

    /**
     * Undoes the admitted call's own entries on its instance's stack, stopping at the first entry the
     * call did not make, and says what happened. The call may not redo what a refusal rolled back.
     * An entry whose undo fails stops it, and the refusal says so in place of "Nothing was changed".
     */
    private String rollBackOwnEntries(Admission admitted, IArchimateModel instance) {
        CommandStack stack = getCommandStack(instance);
        if (ownedCount(stack, admitted.stamp) == 0) {
            return "Nothing was changed.";
        }
        String model = describeModel(admitted.modelName, admitted.modelId);
        int undone;
        try {
            undone = undoOwnEntries(admitted, stack, Integer.MAX_VALUE).labels().size();
        } catch (AgentAuthoredCompoundCommand.StackStepFailed failed) {
            admitted.undoneByCall.clear();
            int left = ownedCount(stack, admitted.stamp);
            return "Undoing the call's earlier changes to " + model + " failed: " + failed.getMessage()
                    + (left == 0 ? "" : " " + left + " more of the call's change(s) were not undone. Undo them in "
                            + "Archi if they are not wanted.");
        }
        admitted.undoneByCall.clear();
        int kept = ownedCount(stack, admitted.stamp);
        if (kept == 0) {
            return "Nothing was changed: the call's " + undone + " earlier change(s) to " + model + " were undone.";
        }
        Command top = stack.getUndoCommand();
        String why = top == null || ownedBy(top, admitted.stamp) ? ""
                : top instanceof AgentAuthoredCommand
                        ? ", because another agent change sits above them in that model's undo history"
                        : ", because a human edit sits above them in that model's undo history";
        return kept + " of the call's " + (undone + kept) + " earlier change(s) to " + model
                + " could not be undone" + why + " (" + undone + " were undone). Undo them in Archi "
                + "if they are not wanted.";
    }

    /**
     * Undoes up to {@code steps} entries while the undo top is the call's own, so neither a human edit
     * nor another call's change is crossed, and an earlier call's work is never reached. Records each
     * as undone by the call. Reports a human-edit block only when one stopped it before any undo, as
     * {@link #scopedUndo} does. An entry whose undo fails stops it, naming the entries undone before it.
     */
    private static UndoRedoState undoOwnEntries(Admission admitted, CommandStack stack, int steps) {
        List<String> labels = new ArrayList<>();
        while (labels.size() < steps && stack.canUndo() && ownedBy(stack.getUndoCommand(), admitted.stamp)) {
            Command top = stack.getUndoCommand();
            UndoRedoState step;
            try {
                step = scopedUndo(stack, 1);
            } catch (AgentAuthoredCompoundCommand.StackStepFailed failed) {
                throw failed.after(labels);
            }
            if (step.labels().isEmpty()) {
                break;
            }
            labels.addAll(step.labels());
            admitted.undoneByCall.add(top);
        }
        String blockedReason = labels.isEmpty() && stack.canUndo()
                && !(stack.getUndoCommand() instanceof AgentAuthoredCommand) ? BLOCK_REASON_HUMAN_EDIT : null;
        return new UndoRedoState(labels, stack.canUndo(), stack.canRedo(), blockedReason);
    }

    /**
     * Redoes up to {@code steps} entries while the redo top is the entry the call most recently undid
     * itself, so a change the human undid is never re-applied by the call. An entry whose redo fails
     * stops it, naming the entries redone before it; the stack has dropped that entry, so the call no
     * longer counts it as its own to redo.
     */
    private static UndoRedoState redoOwnUndos(Admission admitted, CommandStack stack, int steps) {
        List<String> labels = new ArrayList<>();
        List<Command> undone = admitted.undoneByCall;
        while (labels.size() < steps && !undone.isEmpty() && stack.canRedo()
                && stack.getRedoCommand() == undone.get(undone.size() - 1)) {
            UndoRedoState step;
            try {
                step = scopedRedo(stack, 1);
            } catch (AgentAuthoredCompoundCommand.StackStepFailed failed) {
                undone.remove(undone.size() - 1); // the stack dropped it: it is no longer the call's to redo
                throw failed.after(labels);
            }
            if (step.labels().isEmpty()) {
                break;
            }
            labels.addAll(step.labels());
            undone.remove(undone.size() - 1);
        }
        return new UndoRedoState(labels, stack.canUndo(), stack.canRedo());
    }

    /** Whether {@code command} is a stack entry made by the call stamped {@code stamp}. */
    private static boolean ownedBy(Object command, CallStamp stamp) {
        return command instanceof AgentAuthoredCompoundCommand entry && entry.getOwner() == stamp;
    }

    /**
     * The entries on the undo side of {@code stack} made by the call stamped {@code stamp}. An entry
     * the human undid natively sits on the redo side, and a command that declined to run, or declined
     * and vouched it changed nothing, never reached the stack, so neither counts. {@code getCommands()}
     * lists the undo side bottom to top, then the redo side.
     */
    private static int ownedCount(CommandStack stack, CallStamp stamp) {
        int owned = 0;
        Command top = stack.getUndoCommand();
        if (top == null) {
            return 0;
        }
        for (Object each : stack.getCommands()) {
            if (ownedBy(each, stamp)) {
                owned++;
            }
            if (each == top) {
                break;
            }
        }
        return owned;
    }

    // ---- Undo/Redo ----

    /**
     * Undoes the specified number of operations from the command stack.
     *
     * @param steps number of operations to undo (must be >= 1)
     * @return list of command labels that were undone
     * @throws MutationException if undo fails
     */
    public UndoRedoState undo(int steps) throws MutationException {
        logger.info("Undo requested: {} steps", steps);
        Supplier<UndoRedoState> operation = stackOperation(steps, true);
        return dispatchOnUiThread(operation::get);
    }

    /**
     * The undo or redo this thread is about to make, resolved on the calling thread and run on the UI
     * thread. Lets a test double of {@link #undo} and {@link #redo} resolve the stack exactly as
     * production does.
     *
     * <ul>
     *   <li>Not admitted: the active model's stack, as resolved now.</li>
     *   <li>The undo or redo tool: the active model's stack, refused unless that is still the instance
     *       the call was admitted against. The agent's undo is a write, and a write never lands in a
     *       model the human moved to.</li>
     *   <li>A mutating call reverting its own trial dispatches: the admitted instance's own stack, even
     *       when another model has become active. Undo stops at the first entry the call did not make,
     *       and redo re-applies only what the call itself undid. A no-op once the instance has closed,
     *       since its entries went with it.</li>
     * </ul>
     */
    Supplier<UndoRedoState> stackOperation(int steps, boolean undo) {
        Admission admitted = admission.get();
        if (admitted == null) {
            IArchimateModel model = requireModel();
            return () -> scoped(getCommandStack(model), steps, undo);
        }
        if (admitted.stackOperation) {
            return () -> {
                IArchimateModel instance = admitted.openInstance(this);
                IArchimateModel active = admitted.bound ? instance : modelSupplier.get();
                if (instance == null || instance != active) {
                    throw admittedRefusal(admitted, active, "'" + admitted.operation + "'",
                            "Nothing was " + (undo ? "undone" : "redone") + ".", "");
                }
                return scoped(getCommandStack(instance), steps, undo);
            };
        }
        return () -> {
            IArchimateModel instance = admitted.openInstance(this);
            if (instance == null) {
                logger.info("{} of {} step(s) skipped: model {} was closed while the call was in progress",
                        undo ? "Undo" : "Redo", steps, describeModel(admitted.modelName, admitted.modelId));
                return new UndoRedoState(List.of(), false, false);
            }
            CommandStack stack = getCommandStack(instance);
            if (!admitted.dispatched) {
                return scoped(stack, steps, undo); // the call has made no entry of its own yet
            }
            return undo ? undoOwnEntries(admitted, stack, steps) : redoOwnUndos(admitted, stack, steps);
        };
    }

    private static UndoRedoState scoped(CommandStack stack, int steps, boolean undo) {
        return undo ? scopedUndo(stack, steps) : scopedRedo(stack, steps);
    }

    /**
     * Pure, {@code Display}-free decision core for the scoped agent undo (never crosses a human edit — the
     * "betrayal" guard). Undoes <strong>only</strong> agent-authored top-of-stack entries, LIFO:
     *
     * <ul>
     *   <li>If the very top of the undo stack is <strong>not</strong> agent-authored, perform
     *       <strong>zero</strong> undos and return {@link UndoRedoState#blockedReason()} =
     *       {@link #BLOCK_REASON_HUMAN_EDIT} (the horror-moment guard: the agent must never
     *       silently evaporate the human's hand-work — to revert it the agent submits a new
     *       proposal).</li>
     *   <li>For {@code steps > 1}, undo consecutive agent entries and <strong>stop without
     *       error</strong> at the first human-authored entry (never cross a human edit),
     *       returning the labels actually undone and <strong>no</strong> block reason
     *       (partial progress was made).</li>
     *   <li>An empty/exhausted stack returns zero undos and <strong>no</strong> block reason —
     *       distinct from the human-blocked case.</li>
     *   <li>An entry whose undo fails throws {@link AgentAuthoredCompoundCommand.StackStepFailed}, naming
     *       the entries undone before it, which are not lost. The stack has dropped the failing entry
     *       from both its stacks.</li>
     * </ul>
     *
     * <p>Extracted out of the {@code Display}-bound {@link #undo(int)} so the betrayal guard is
     * fully unit-testable against a real headless {@code CommandStack} (instantiable with no
     * {@code Display}).</p>
     *
     * @param stack the command stack to operate on
     * @param steps the number of operations requested
     * @return the resulting state, including a nullable block reason
     */
    static UndoRedoState scopedUndo(CommandStack stack, int steps) {
        List<String> labels = new ArrayList<>();
        String blockedReason = null;
        for (int i = 0; i < steps; i++) {
            if (!stack.canUndo()) break;
            Command cmd = stack.getUndoCommand();
            if (cmd instanceof AgentAuthoredCommand) {
                String label = cmd.getLabel();
                try {
                    stack.undo();
                } catch (AgentAuthoredCompoundCommand.StackStepFailed failed) {
                    throw failed.after(labels);
                }
                labels.add(label);
            } else {
                // Top is the human's (or, defensively, an unexpected null). Stop here — never
                // cross a human edit. Flag the betrayal refusal only when zero progress was made.
                if (labels.isEmpty() && cmd != null) {
                    blockedReason = BLOCK_REASON_HUMAN_EDIT;
                }
                break;
            }
        }
        return new UndoRedoState(labels, stack.canUndo(), stack.canRedo(), blockedReason);
    }

    /**
     * Redoes the specified number of previously undone operations.
     *
     * @param steps number of operations to redo (must be >= 1)
     * @return list of command labels that were redone
     * @throws MutationException if redo fails
     */
    public UndoRedoState redo(int steps) throws MutationException {
        logger.info("Redo requested: {} steps", steps);
        Supplier<UndoRedoState> operation = stackOperation(steps, false);
        return dispatchOnUiThread(operation::get);
    }

    /**
     * Pure, {@code Display}-free decision core for the scoped agent redo (symmetric
     * to {@link #scopedUndo}). Re-applies <strong>only</strong> agent-authored redo entries: if the
     * top redo entry is human-authored (e.g. the human used native undo on their own edit), perform
     * zero redos and return {@link #BLOCK_REASON_HUMAN_EDIT}; for {@code steps > 1}, stop without
     * error at the first human redo entry. A human change must never be re-applied — or evaporated —
     * by the agent. An empty/exhausted redo stack returns zero with no block reason. An entry whose redo
     * fails throws {@link AgentAuthoredCompoundCommand.StackStepFailed}, naming the entries redone before it.
     *
     * @param stack the command stack to operate on
     * @param steps the number of operations requested
     * @return the resulting state, including a nullable block reason
     */
    static UndoRedoState scopedRedo(CommandStack stack, int steps) {
        List<String> labels = new ArrayList<>();
        String blockedReason = null;
        for (int i = 0; i < steps; i++) {
            if (!stack.canRedo()) break;
            Command cmd = stack.getRedoCommand();
            if (cmd instanceof AgentAuthoredCommand) {
                String label = cmd.getLabel();
                try {
                    stack.redo();
                } catch (AgentAuthoredCompoundCommand.StackStepFailed failed) {
                    throw failed.after(labels);
                }
                labels.add(label);
            } else {
                if (labels.isEmpty() && cmd != null) {
                    blockedReason = BLOCK_REASON_HUMAN_EDIT;
                }
                break;
            }
        }
        return new UndoRedoState(labels, stack.canUndo(), stack.canRedo(), blockedReason);
    }

    /**
     * Block-reason marker meaning "refused because the top-of-stack entry is the human's"
     * (the betrayal guard). Carried on {@link UndoRedoState#blockedReason()} so the
     * handler can emit a distinct diagnostic rather than collapsing it into "Nothing to undo".
     */
    public static final String BLOCK_REASON_HUMAN_EDIT = "HUMAN_EDIT";

    /**
     * Internal state returned from undo/redo operations.
     *
     * @param labels        labels of the operations actually undone/redone
     * @param canUndo       whether the stack can undo after the operation
     * @param canRedo       whether the stack can redo after the operation
     * @param blockedReason nullable reason the operation was refused with zero progress
     *                      ({@link #BLOCK_REASON_HUMAN_EDIT}); {@code null} when the stack was
     *                      simply empty/exhausted or work was performed
     */
    public record UndoRedoState(List<String> labels, boolean canUndo, boolean canRedo, String blockedReason) {

        /**
         * Back-compat convenience constructor at the prior canonical arity: no block
         * reason. Retained so any existing caller compiles unchanged (record-arity discipline).
         */
        public UndoRedoState(List<String> labels, boolean canUndo, boolean canRedo) {
            this(labels, canUndo, canRedo, null);
        }
    }

    // ---- Batch management ----

    /**
     * Starts batch mode for a session. Subsequent mutations will be queued
     * instead of applied immediately.
     *
     * @param sessionId the session identifier
     * @param description optional batch description
     * @throws IllegalStateException if session is already in batch mode
     */
    public void beginBatch(String sessionId, String description) {
        beginBatch(sessionId, description, null);
    }

    /**
     * As {@link #beginBatch(String, String)} but carrying the optional agent-supplied {@code intent}.
     * The intent is recorded on the session's {@link MutationContext} ({@code batchIntent})
     * and kept distinct from {@code description} (the undo-history label) — never merged.
     * The server never depends on it; {@code intent} is never logged at INFO (agent free-text).
     */
    public void beginBatch(String sessionId, String description, String intent) {
        logger.info("Beginning batch for session '{}'{}", sessionId,
                description != null ? ": " + CallerText.logged(description) : ""); // the caller's own text
        // Bind the batch to the model this call resolved (the one it addressed, inside a tool call) or,
        // outside one, the model active now: the one every queued command will be prepared against and
        // hold references into. An addressed batch commits into that instance whichever model is
        // active; one that followed the active model is refused if a different model is active then.
        IArchimateModel model = selection.peekModelForCall(modelSupplier.get());
        if (model != null && selection.inScope() && isClosed(epochOf(model))) {
            throw new ModelAccessException("begin-batch refused: model " + describeModel(model.getName(),
                    model.getId()) + " was closed in Archi while the call was in progress. No batch was "
                    + "started.", ErrorCode.BOUND_MODEL_CLOSED, null,
                    "Reopen the model in Archi, then retry begin-batch (get-models lists what is open).", null);
        }
        MutationContext context = batchSessions.computeIfAbsent(
                sessionId, k -> new MutationContext());
        context.beginBatch(description, intent, model != null ? model.getId() : null,
                model != null ? model.getName() : null, model != null ? epochOf(model) : 0L,
                selection.inScope());
    }

    /**
     * Ends batch mode for a session, either committing or rolling back.
     *
     * @param sessionId the session identifier
     * @param commit true to commit all queued mutations, false to rollback
     * @return summary of the batch operation
     * @throws IllegalStateException if session is not in batch mode
     * @throws MutationException if commit dispatch fails
     */
    public BatchSummaryDto endBatch(String sessionId, boolean commit) throws MutationException {
        // Not admitted: the commit dispatches under its own guard, and an admission left on this
        // thread by an earlier call must not bind anything this thread does next.
        admission.remove();
        MutationContext context = getActiveContext(sessionId);

        if (!commit) {
            logger.info("Rolling back batch for session '{}' ({} queued commands)",
                    sessionId, context.getQueuedCount());
            // A rollback dispatches nothing, so it cannot write into the wrong model and is allowed
            // even when the bound model is no longer active — a diverged batch must always be
            // discardable. The summary then names the bound model, so the discard is attributable.
            BatchSummaryDto summary = context.buildRollbackSummary();
            IArchimateModel active = bindingTarget(context);
            rebindIfBoundInstanceClosed(context, active);
            if (boundModelRefusal(context, active, "Rollback") != null) {
                summary = new BatchSummaryDto(summary.operationCount(), summary.descriptions(),
                        summary.duration(), summary.rolledBack(), summary.skippedOperations(),
                        context.getBoundModelId(), context.getBoundModelName());
            }
            context.reset();
            removeBatchSessionIfEmpty(sessionId);
            return summary;
        }

        int queuedCount = context.getQueuedCount();
        logger.info("Committing batch for session '{}' ({} queued commands)",
                sessionId, queuedCount);

        // The compound mutates the model its commands were prepared against, but dispatch takes
        // the CommandStack of whichever model is active now. If those differ, the write and its
        // undo entry would land in different models — refuse, leaving the queue intact. Refused even
        // with nothing queued, so a commit never reports success for a batch bound elsewhere.
        IArchimateModel active = bindingTarget(context);
        rebindIfBoundInstanceClosed(context, active);
        requireBatchBoundTo(context, active, "Commit");
        if (queuedCount > 0) {
            Command compound = context.buildCompoundCommand();
            // GEF's stack runs nothing, and says nothing, for a compound that cannot execute. Refuse
            // instead, with the queue intact, as the binding refusal above does.
            if (!compound.canExecute()) {
                ModelAccessException refusal = context.unexecutableCommitRefusal();
                logger.info("{}", CallerText.loggedMessage(refusal.getMessage()));
                throw refusal;
            }
            try {
                dispatchGuarded(compound, commitGuard(context), sessionId);
            } catch (AgentAuthoredCompoundCommand.ChangesLeft | AgentAuthoredCompoundCommand.Failed failure) {
                // The rest of the batch was undone with it. Its commands have run, so a retry would run
                // them again over what was left: the batch ends here, whatever happens while reporting it.
                try {
                    if (onImmediateDispatchCallback != null) {
                        onImmediateDispatchCallback.run(); // part of it may still be in the model: a new version
                    }
                    MutationException ended = failure instanceof AgentAuthoredCompoundCommand.Failed thrown
                            ? thrown.inBatch(context.describeQueued(thrown.getFailedAt()))
                            : ((AgentAuthoredCompoundCommand.ChangesLeft) failure).inBatch(
                                    context.describeChangesLeft());
                    logger.error("Batch for session '{}' failed at commit and has ended: {}", sessionId,
                            CallerText.loggedMessage(ended.getMessage()));
                    throw ended;
                } finally {
                    context.reset();
                    removeBatchSessionIfEmpty(sessionId);
                }
            }
        }

        // Built after dispatch, not before: a command that declines to run at commit time
        // only reports that once it has actually been given the chance to run.
        BatchSummaryDto summary = context.buildCommitSummary();
        if (summary.skippedOperations() != null) {
            logger.warn("Batch for session '{}' committed with {} skipped operation(s): {}",
                    sessionId, summary.skippedOperations().size(), summary.skippedOperations());
        }

        context.reset();
        removeBatchSessionIfEmpty(sessionId);
        return summary;
    }

    /**
     * Drops the session's {@link #batchSessions} entry once a batch has ended,
     * <strong>but only when the context holds no pending proposals</strong>. Proposals live in the SAME
     * {@link MutationContext} as the batch ({@code storeProposal} → {@code computeIfAbsent} on this map),
     * and {@link MutationContext#reset()} deliberately preserves them; removing unconditionally would
     * silently drop the human's pending-approval queue. After {@code reset()} the mode is already
     * {@code GUI_ATTACHED}, so the pending-count is the only meaningful guard.
     *
     * <p>The check-and-remove is atomic: {@link ConcurrentHashMap#compute} holds the bin lock across the
     * {@code getPendingCount()} read, so a concurrent {@code storeProposal} (which {@code computeIfAbsent}s
     * the same key) cannot interleave between the check and the removal — no check-then-remove race that
     * could evaporate a just-stored proposal. Returning {@code null} from {@code compute} removes the entry.</p>
     */
    private void removeBatchSessionIfEmpty(String sessionId) {
        batchSessions.compute(sessionId, (k, ctx) ->
                (ctx == null || ctx.getPendingCount() == 0) ? null : ctx);
    }

    /**
     * @return number of live per-session batch/proposal contexts (test accessor).
     */
    int batchSessionCount() {
        return batchSessions.size();
    }

    /**
     * Queues a command for batch execution.
     *
     * @param sessionId the session identifier
     * @param command the GEF command to queue
     * @param description human-readable description of the mutation
     * @return the batch sequence number for this command
     * @throws IllegalStateException if session is not in batch mode
     * @throws BatchClosingCommand.Refusal if a command queued earlier closes the batch to this one
     */
    public int queueForBatch(String sessionId, Command command, String description) {
        return queueForBatch(sessionId, command, description, null);
    }

    /**
     * As {@link #queueForBatch(String, Command, String)}, naming {@code approvedTool} as the tool
     * queuing the command when no call admitted it (an approved proposal's rebuilt command).
     */
    private int queueForBatch(String sessionId, Command command, String description, String approvedTool) {
        Admission admitted = consumeMutationAdmission();
        MutationContext context = getActiveContext(sessionId);
        String refused = "Queuing '" + description + "'";
        int seq;
        // Every command entering the queue passes here, including an approved proposal's rebuilt
        // command, so this is where a command prepared against another model is kept out of it. The
        // checks and the enqueue hold the context's lock, so an empty batch cannot be rebound to a
        // reopened instance between them. No rebind here: a command already prepared against a
        // closed instance must not follow its id to the new one.
        synchronized (context) {
            // A bound call is checked against its own instance; a command that was not admitted (an
            // approved proposal's) against the model its approval bound this thread to, else the active one.
            IArchimateModel active = admitted != null && admitted.bound ? admitted.openInstance(this)
                    : selection.peekModelForCall(modelSupplier.get());
            if (admitted != null) {
                requireAdmittedInstance(admitted, active, refused, context);
            }
            requireBatchBoundTo(context, active, refused);
            // A command queued earlier may close the batch to this one (BatchClosingCommand): it
            // would run after a command that removes what it was prepared against. Checked here,
            // with the enqueue, so no tool can bypass it and no other call can slip in between.
            BatchClosingCommand.Refusal closed = context.refuseQueuing(command,
                    admitted != null ? admitted.operation : approvedTool);
            if (closed != null) {
                throw closed;
            }
            // An operation with nothing to change would stop the whole commit from running
            // (ChangesNothingCommand says why); it keeps its place in the queue as nothing instead.
            seq = context.queueCommand(ChangesNothingCommand.holdsNothing(command)
                    ? new ChangesNothingCommand(command.getLabel()) : command, description);
        }
        logger.debug("Queued command #{} for session '{}': {}", seq, sessionId, description);
        return seq;
    }

    /**
     * Resolves a parent view-object id against the containers a session's open batch has queued
     * but not yet executed.
     *
     * <p>A queued {@code add-group-to-view} / {@code add-to-view} leaves its object detached until
     * the batch commits, so the id handed back to the caller is not yet findable by walking the
     * view. Callers preparing a later operation in that same batch use this to pre-resolve the
     * parent, which is the same bridge the bulk path builds from its batch-created-parent maps.</p>
     *
     * <p>Returns null whenever the session is not in batch mode, so every non-batch caller keeps
     * its existing behaviour by construction rather than by argument. Returns null too when the id
     * names no queued container. An id naming a queued note, image or view-reference is refused by
     * name ({@link QueuedObjectRefusals#refuseAsParent}); any other id is left to the caller's ordinary
     * live lookup and its not-found error.</p>
     *
     * <p>The batch-mode test is delegated to the context rather than performed here, so that one
     * map read and one locked context call decide the whole answer. A batch spans several requests
     * on different Jetty threads, and the transport imposes no per-session serialisation, so a
     * second lookup of the session could straddle a concurrent {@code endBatch} and return null for
     * a container the mode check had just proved was queued.</p>
     *
     * @param sessionId           the session identifier
     * @param parentViewObjectId  the requested parent id, may be null
     * @return the queued container, or null when there is none to resolve
     */
    IDiagramModelContainer queuedParentContainer(String sessionId, String parentViewObjectId) {
        MutationContext context = batchSessions.get(sessionId);
        if (context == null) {
            return null;
        }
        IDiagramModelContainer container = context.queuedContainer(parentViewObjectId);
        if (container == null) {
            // A queued note, image or view reference: refused by name rather than left to the live
            // lookup, which cannot see it and would answer "not found". A batch ending between the
            // two reads makes the second answer null, which keeps the old not-found path.
            QueuedObjectRefusals.refuseAsParent(context.queuedViewObject(parentViewObjectId), parentViewObjectId);
        }
        return container;
    }

    /**
     * Resolves a view-object id against the objects a session's open batch has queued but not yet
     * executed, returning the object together with the container it is destined for.
     *
     * <p>Where {@link #queuedParentContainer(String, String)} answers "may this id be a parent",
     * this answers "which object does this id name" — so it admits notes, images and
     * view-references as well as groups and elements. Callers preparing an update in the same batch
     * use it in place of a live lookup, which cannot see an object whose add has not executed.</p>
     *
     * <p>Reads {@code batchSessions} exactly once, for the same reason as its sibling: a mode test
     * and a queue scan split across two reads could be separated by a concurrent {@code end-batch},
     * making the second read miss an object the first had just proved was queued. The mode test is
     * inside {@link MutationContext#queuedViewObject(String)}, under that context's own lock.</p>
     *
     * @param sessionId    the session identifier
     * @param viewObjectId the requested object id, may be null
     * @return the queued object and its destined parent, or null when there is none to resolve
     */
    QueuedViewObject queuedViewObject(String sessionId, String viewObjectId) {
        MutationContext context = batchSessions.get(sessionId);
        return context == null ? null : context.queuedViewObject(viewObjectId);
    }

    /**
     * Resolves a view id against the views a session's open batch has queued but not yet created.
     *
     * <p>Its siblings above answer for objects an {@code add-*-to-view} builds; this answers for the
     * diagram itself. {@code create-view} hands back a real id at prepare time but attaches the view
     * to its folder only when the command executes, which inside a batch is at commit, so a later
     * {@code add-*-to-view} naming that id cannot find it by walking committed containment. Callers
     * pass the result into the {@code batchView} slot the bulk path already fills from its
     * back-reference maps.</p>
     *
     * <p>Reads {@code batchSessions} exactly once, and the mode test lives inside
     * {@link MutationContext#queuedCreatedView(String)} under that context's own lock — same TOCTOU
     * reasoning as its siblings: a mode test and a queue scan split across two reads could be
     * separated by a concurrent {@code end-batch}, making the second read miss a view the first had
     * just proved was queued.</p>
     *
     * @param sessionId the session identifier
     * @param viewId    the requested view id, may be null
     * @return the queued view, or null when there is none to resolve
     */
    IArchimateDiagramModel queuedCreatedView(String sessionId, String viewId) {
        MutationContext context = batchSessions.get(sessionId);
        return context == null ? null : context.queuedCreatedView(viewId);
    }

    /**
     * The folder a session's open batch will add a queued view to, or null when it queues no create
     * for that id.
     *
     * <p>The folder-side sibling of {@link #queuedCreatedView(String, String)}: a queued view has no
     * container until the batch commits, so a caller that needs to know where it will go reads it off
     * the queued create instead. {@code clone-view} uses it to place a clone of a view the same batch
     * creates in that view's folder, as it does for a view already in the model.</p>
     *
     * <p>Reads {@code batchSessions} exactly once, and the mode test lives inside
     * {@link MutationContext#queuedCreatedViewFolder(String)} under that context's own lock — same
     * TOCTOU reasoning as its siblings.</p>
     *
     * @param sessionId the session identifier
     * @param viewId    the queued view's id, may be null
     * @return the folder its create will add it to, or null when there is none to resolve
     */
    IFolder queuedCreatedViewFolder(String sessionId, String viewId) {
        MutationContext context = batchSessions.get(sessionId);
        return context == null ? null : context.queuedCreatedViewFolder(viewId);
    }

    /**
     * Resolves an element id against the elements a session's open batch has queued but not yet
     * created.
     *
     * <p>The model-object sibling of {@link #queuedCreatedView(String, String)}: {@code
     * create-element} also hands back a real id at prepare time while deferring the folder
     * attachment to commit, so an {@code add-to-view} naming a just-created element could not find
     * it either. Together the two close the gap that stopped an agent building a view from scratch
     * inside one batch.</p>
     *
     * <p>Reads {@code batchSessions} exactly once, and the mode test lives inside
     * {@link MutationContext#queuedCreatedElement(String)} under that context's own lock — same
     * TOCTOU reasoning as its siblings.</p>
     *
     * @param sessionId the session identifier
     * @param elementId the requested element id, may be null
     * @return the queued element, or null when there is none to resolve
     */
    IArchimateElement queuedCreatedElement(String sessionId, String elementId) {
        MutationContext context = batchSessions.get(sessionId);
        return context == null ? null : context.queuedCreatedElement(elementId);
    }

    /**
     * Resolves a relationship id against the relationships a session's open batch has queued but
     * not yet created.
     *
     * <p>Completes the create-then-use family for connections. {@code create-relationship} defers
     * its {@code connect()} and its folder attachment to commit, so an {@code add-connection-to-view}
     * naming a just-created relationship found nothing in containment and refused — a batch could
     * make a relationship and not draw it.</p>
     *
     * <p>Reads {@code batchSessions} exactly once, and the mode test lives inside
     * {@link MutationContext#queuedCreatedRelationship(String)} under that context's own lock —
     * same TOCTOU reasoning as its siblings.</p>
     *
     * @param sessionId      the session identifier
     * @param relationshipId the requested relationship id, may be null
     * @return the queued relationship, or null when there is none to resolve
     */
    IArchimateRelationship queuedCreatedRelationship(String sessionId, String relationshipId) {
        MutationContext context = batchSessions.get(sessionId);
        return context == null ? null : context.queuedCreatedRelationship(relationshipId);
    }

    /**
     * The element at a relationship's source end, whether the relationship is live or still queued.
     *
     * <p>A live relationship answers for itself. A queued one cannot: {@code create-relationship}
     * defers {@code connect()} to commit, so its own source and target read null for the whole
     * window in which an agent can name it — and a caller reading them directly gets a
     * {@code NullPointerException} rather than a diagnosis. The ends come off the queued create
     * instead, which knows exactly what it is going to connect.</p>
     *
     * @param sessionId    the session identifier
     * @param relationship the relationship to read, live or queued
     * @return the source element, or null when neither the relationship nor a queued create knows it
     */
    IArchimateElement relationshipSource(String sessionId, IArchimateRelationship relationship) {
        if (relationship.getSource() instanceof IArchimateElement live) {
            return live;
        }
        MutationContext context = batchSessions.get(sessionId);
        return context == null ? null : context.queuedRelationshipEnd(relationship.getId(), true);
    }

    /** The target end, for the same reason as {@link #relationshipSource(String, IArchimateRelationship)}. */
    IArchimateElement relationshipTarget(String sessionId, IArchimateRelationship relationship) {
        if (relationship.getTarget() instanceof IArchimateElement live) {
            return live;
        }
        MutationContext context = batchSessions.get(sessionId);
        return context == null ? null : context.queuedRelationshipEnd(relationship.getId(), false);
    }

    /**
     * Resolves a view-object id against the queued objects that are eligible to be a
     * <em>connection endpoint on {@code view}</em>.
     *
     * <p>{@link #queuedViewObject(String, String)} answers "which object does this id name", and
     * admits groups, notes, images and view-references along with elements, on any view. An
     * endpoint has to be an {@link IDiagramModelArchimateObject} — it is the element behind the
     * object that the relationship is validated against — and it has to be destined for the view
     * the connection is being drawn on, which is the scope the live lookup this stands in for has
     * always had.</p>
     *
     * <p>Both narrowings live below the call site so a queued group, or an object this batch is
     * placing on a different view, keeps taking the ordinary not-found path with the message it has
     * always had. A lookup that quietly widened either the accepted kinds or the accepted view
     * would be worse than the throw: the second would build a connection whose two ends land on
     * different diagrams.</p>
     *
     * <p>Reads {@code batchSessions} exactly once, and the mode test lives inside
     * {@link MutationContext#queuedConnectionEnd(String, IArchimateDiagramModel)} under that
     * context's own lock — same TOCTOU reasoning as its siblings.</p>
     *
     * @param sessionId    the session identifier
     * @param viewObjectId the requested object id, may be null
     * @param view         the view the connection is being drawn on
     * @return the queued object when it can be an endpoint on {@code view}, else null
     */
    IDiagramModelArchimateObject queuedConnectionEnd(String sessionId, String viewObjectId,
            IArchimateDiagramModel view) {
        MutationContext context = batchSessions.get(sessionId);
        if (context == null) {
            return null;
        }
        IDiagramModelArchimateObject end = context.queuedConnectionEnd(viewObjectId, view);
        if (end == null) {
            QueuedObjectRefusals.refuseAsConnectionEnd(context.queuedViewObject(viewObjectId), viewObjectId);
        }
        return end;
    }

    /**
     * Refuses removing an object this session's open batch has queued for placement: it is detached
     * until the batch commits, so the live lookup would answer "not found" for an id an earlier call of
     * the batch handed back. Does nothing outside batch mode or for an object already on the view.
     */
    void refuseRemovingQueued(String sessionId, String viewObjectId) {
        MutationContext context = batchSessions.get(sessionId);
        if (context != null) {
            QueuedObjectRefusals.refuseRemoving(context.queuedViewObject(viewObjectId), viewObjectId);
        }
    }

    /**
     * Resolves a connection id against the connections a session's open batch has queued but not
     * yet connected.
     *
     * <p>The last of the family: {@code add-connection-to-view} hands back a real id at prepare
     * time but joins the connection to its endpoints only when the command executes, which inside a
     * batch is at commit, so a later {@code update-view-connection} naming that id cannot find it
     * by walking committed containment. Callers pass the result into the {@code batchConnection}
     * slot the bulk path already fills from its back-reference maps.</p>
     *
     * <p>Reads {@code batchSessions} exactly once, and the mode test lives inside
     * {@link MutationContext#queuedViewConnection(String)} under that context's own lock — same
     * TOCTOU reasoning as its siblings: a mode test and a queue scan split across two reads could
     * be separated by a concurrent {@code end-batch}, making the second read miss a connection the
     * first had just proved was queued.</p>
     *
     * @param sessionId        the session identifier
     * @param viewConnectionId the requested connection id, may be null
     * @return the queued connection, or null when there is none to resolve
     */
    IDiagramModelArchimateConnection queuedViewConnection(String sessionId, String viewConnectionId) {
        MutationContext context = batchSessions.get(sessionId);
        return context == null ? null : context.queuedViewConnection(viewConnectionId);
    }

    /**
     * The {@code object id → destined parent} containment a session's open batch has queued but not
     * yet executed, or null when the session is not in a batch.
     *
     * <p>Its siblings above answer about one id; a parent-fit cascade needs the whole picture,
     * because growing a queued group to fit its child asks the same question again of that group's
     * own container, and again above it. Handing the walk this map lets it climb through however
     * many queued hops the batch has built, off one scan.</p>
     *
     * <p>Reads {@code batchSessions} exactly once, and the mode test lives inside
     * {@link MutationContext#queuedParents()} under that context's own lock — same TOCTOU reasoning
     * as its siblings.</p>
     *
     * @param sessionId the session identifier
     * @return the queued containment, or null when the session has no open batch
     */
    Map<String, IDiagramModelContainer> queuedParents(String sessionId) {
        MutationContext context = batchSessions.get(sessionId);
        return context == null ? null : context.queuedParents();
    }

    /**
     * The {@code object id → effective bounds} a session's open batch has queued but not yet
     * executed, or null when the session is not in a batch.
     *
     * <p>The geometry counterpart of {@link #queuedParents(String)}. A prepare inside a batch reads
     * a model where none of the batch's own commands have run, so an object an earlier operation
     * re-sized still reports its pre-batch bounds; two operations growing one shared container both
     * measure from that stale value and the later overwrites the earlier. Measuring against this map
     * instead makes the earlier operation's result a floor for the later one.</p>
     *
     * <p>Reads {@code batchSessions} exactly once, and the mode test lives inside
     * {@link MutationContext#queuedBounds()} under that context's own lock — same TOCTOU reasoning
     * as its siblings.</p>
     *
     * @param sessionId the session identifier
     * @return the queued geometry, or null when the session has no open batch
     */
    Map<String, int[]> queuedBounds(String sessionId) {
        MutationContext context = batchSessions.get(sessionId);
        return context == null ? null : context.queuedBounds();
    }

    /**
     * The {@code connection id → label visibility} a session's open batch has queued but not yet
     * executed, or null when the session is not in a batch.
     *
     * <p>The label-visibility counterpart of {@link #queuedBounds(String)}, and it exists for the
     * same prepare/execute reason: a label an earlier queued operation hides still reads as visible
     * to a routing pass built later in the same batch.</p>
     *
     * @param sessionId the session identifier
     * @return the queued label visibility, or null when the session has no open batch
     */
    Map<String, Boolean> queuedLabelVisibility(String sessionId) {
        MutationContext context = batchSessions.get(sessionId);
        return context == null ? null : context.queuedLabelVisibility();
    }

    /**
     * {@link #queuedLabelVisibility(String)} for the session whose tool call this thread is running,
     * or null when no call is admitted on this thread or its session has no open batch.
     *
     * <p>Lets a routing pass several layers inside a call read the label visibility its own batch
     * will commit, without every method between the tool and the router carrying the session id. The
     * admission is the same per-call binding every immediate dispatch of the call already reads.</p>
     */
    Map<String, Boolean> queuedLabelVisibilityOfCall() {
        Admission admitted = admission.get();
        return admitted == null || admitted.stamp.sessionId == null
                ? null : queuedLabelVisibility(admitted.stamp.sessionId);
    }

    /**
     * Every non-compound command a session's open batch has queued, in queue order, or null when
     * the session is not in a batch. See {@link MutationContext#queuedLeafCommands()}.
     *
     * @param sessionId the session identifier
     * @return the queued leaf commands, or null when the session has no open batch
     */
    List<Command> queuedLeafCommands(String sessionId) {
        MutationContext context = batchSessions.get(sessionId);
        return context == null ? null : context.queuedLeafCommands();
    }

    /**
     * The {@code object id → anchor} a session's open batch has queued but not yet applied, or null
     * when the session is not in a batch.
     *
     * <p>The third of the same family. {@link #queuedBounds(String)} answers where the batch has put
     * things; this answers what the batch has declared about the things whose position is not a
     * number but a relationship. An anchor is persisted on the anchored object as feature entries
     * written by that object's own command at {@code execute()}, so while the batch is open the
     * commit-time cascade — which reads those features to find who must follow a target it is about
     * to move — cannot see an anchor the batch has queued and therefore emits no move for it.</p>
     *
     * <p>Reads {@code batchSessions} exactly once, and the mode test lives inside
     * {@link MutationContext#queuedAnchors()} under that context's own lock — same TOCTOU reasoning
     * as its siblings.</p>
     *
     * @param sessionId the session identifier
     * @return the queued anchors, or null when the session has no open batch
     */
    Map<String, String[]> queuedAnchors(String sessionId) {
        MutationContext context = batchSessions.get(sessionId);
        return context == null ? null : context.queuedAnchors();
    }

    /**
     * Returns the current operational mode for a session.
     *
     * @param sessionId the session identifier
     * @return the operational mode (GUI_ATTACHED if no batch context exists)
     */
    public OperationalMode getMode(String sessionId) {
        MutationContext context = batchSessions.get(sessionId);
        if (context == null) {
            return OperationalMode.GUI_ATTACHED;
        }
        return context.getMode();
    }

    /**
     * Returns the batch status for a session.
     *
     * @param sessionId the session identifier
     * @return batch status DTO
     */
    public BatchStatusDto getBatchStatus(String sessionId) {
        // Approval mode is now global — overlay it onto the per-session batch/queue status.
        Boolean approval = approvalModeProvider.isApprovalModeOn() ? Boolean.TRUE : null;
        MutationContext context = batchSessions.get(sessionId);
        if (context == null) {
            return new BatchStatusDto(
                    OperationalMode.GUI_ATTACHED.name(), null, null, null, approval, null);
        }
        BatchStatusDto base = context.getBatchStatus();
        return new BatchStatusDto(
                base.mode(), base.queuedCount(), base.queuedDescriptions(),
                base.batchStarted(), approval, base.pendingApprovalCount(),
                base.boundModelId(), base.boundModelName());
    }

    // ---- Approval management (globalised) ----

    /**
     * Checks if approval mode is active. The bit is now <strong>global</strong> (one human,
     * one desktop, one gate), read from the injected {@link ApprovalModeProvider}.
     *
     * <p>The {@code sessionId} parameter is retained so the ~45 {@code ArchiModelAccessorImpl}
     * gate-check call-sites stay byte-identical (god-object ratchet), but it is
     * <em>ignored</em> for the gate decision: every session sees the same human-owned mode.</p>
     *
     * @param sessionId the session identifier (ignored — the gate is global)
     * @return true if approval is required, false otherwise
     */
    public boolean isApprovalRequired(String sessionId) {
        return approvalModeProvider.isApprovalModeOn();
    }

    /**
     * Resolves which dispatch arm a mutation submitted on this session will take.
     *
     * <p><strong>The ordering is the contract, not an implementation detail.</strong> Approval is
     * checked first because the accessor's gates are ordered that way: every tool tests
     * {@code isApprovalRequired} and returns a proposal <em>before</em> it reaches
     * {@code dispatchOrQueue}, so a session that is both in approval mode and inside an open batch
     * proposes rather than queues. Reading the batch mode first would invert that and hand every
     * such call a disclosure describing a queue entry that was never made.</p>
     *
     * <p>Both reads are pure session-mode lookups with no side effects, which is what lets a
     * disclosure be composed for the right arm at the moment the finding is measured rather than
     * re-derived at the return.</p>
     *
     * <p><strong>This is a read, not a reservation, and the caller owns the gap.</strong> No code
     * path between a caller's emit and its own dispatch changes the session mode — but the approval
     * bit is not owned by the code. {@code ApprovalMode} holds it {@code volatile} precisely
     * because the UI thread writes it while Jetty threads read it, so a human toggling the gate can
     * move it underneath a computation already in flight; on a large view that window is seconds,
     * not microseconds. Nothing here pins the value for the duration of a request. A caller that
     * resolves the arm early and then gates on a second, later read of the same flag is relying on
     * the human not to touch it mid-call — which is a real, if narrow, way for a disclosure to
     * describe one arm on a response that took the other. Closing it means snapshotting the arm
     * once per request and having the dispatch gate consult that snapshot rather than the flag.</p>
     *
     * @param sessionId the session identifier
     * @return the arm a mutation dispatched on this session would take right now
     */
    public DispatchArm armFor(String sessionId) {
        return DispatchArm.of(isApprovalRequired(sessionId),
                getMode(sessionId) == OperationalMode.BATCH);
    }

    /**
     * Stores a proposal for a session.
     *
     * @param sessionId the session identifier
     * @param proposal the proposal to store
     * @return the assigned proposal ID
     */
    public String storeProposal(String sessionId, PendingProposal proposal) {
        // Bind the proposal to the model it was prepared against: the one the call was admitted on,
        // or, on a path that is not admitted, the one active now. Approve rebuilds against whichever
        // model is active then, so it is refused if that is another one.
        Admission admitted = consumeMutationAdmission();
        if (proposal.modelId() == null) {
            if (admitted != null) {
                proposal = proposal.withModel(admitted.modelId, admitted.modelName, admitted.epoch, admitted.bound);
            } else {
                IArchimateModel model = selection.peekModelForCall(modelSupplier.get());
                if (model != null) {
                    proposal = proposal.withModel(model.getId(), model.getName(), epochOf(model),
                            selection.inScope());
                }
            }
        }
        MutationContext context = batchSessions.computeIfAbsent(
                sessionId, k -> new MutationContext());
        // A proposal that approval would queue after a batch-closing command is refused now, so the
        // agent learns the batch is closed instead of the human meeting the refusal on approve.
        BatchClosingCommand.Refusal closed = context.refuseProposing(proposal.tool());
        if (closed != null) {
            throw closed;
        }
        // Sweep abandoned proposals first so TTL relieves the hard cap before this store.
        List<String> swept = context.sweepExpired(Instant.now(), PROPOSAL_TTL);
        if (!swept.isEmpty()) {
            logger.info("Swept {} expired proposal(s) from session '{}' on propose: {}",
                    swept.size(), sessionId, swept);
        }
        String id = context.storeProposal(proposal);
        logger.debug("Stored proposal '{}' for session '{}': {}", CallerText.logged(id), sessionId, proposal.description());
        fireQueueChanged();
        return id;
    }

    /**
     * Stores a proposal from individual fields. Public API for callers that
     * cannot access package-private {@link PendingProposal}.
     *
     * @param sessionId         the session identifier
     * @param tool              the MCP tool name (e.g., "create-element")
     * @param description       human-readable description
     * @param command           the GEF Command ready for execution
     * @param entity            the DTO representing the proposed result
     * @param currentState      snapshot of current state (null for creates)
     * @param proposedChanges   map of proposed field changes
     * @param validationSummary validation result summary
     * @param createdAt         timestamp when the proposal was created
     * @return the assigned proposal ID
     */
    public String storeProposal(String sessionId, String tool, String description,
            Command command, Object entity, Map<String, Object> currentState,
            Map<String, Object> proposedChanges, String validationSummary,
            Instant createdAt) {
        admission.remove(); // not an admitted path: stamp from the active model, never a stale admission
        PendingProposal proposal = new PendingProposal(
                null, tool, description, command, entity,
                currentState, proposedChanges, validationSummary, createdAt);
        return storeProposal(sessionId, proposal);
    }

    /**
     * Retrieves a proposal by ID.
     *
     * @param sessionId the session identifier
     * @param proposalId the proposal ID
     * @return the proposal, or null if not found
     */
    public PendingProposal getProposal(String sessionId, String proposalId) {
        MutationContext context = batchSessions.get(sessionId);
        return context != null ? context.getProposal(proposalId) : null;
    }

    /**
     * Removes a proposal by ID.
     *
     * @param sessionId the session identifier
     * @param proposalId the proposal ID
     * @return the removed proposal, or null if not found
     */
    public PendingProposal removeProposal(String sessionId, String proposalId) {
        MutationContext context = batchSessions.get(sessionId);
        PendingProposal removed = context != null ? context.removeProposal(proposalId) : null;
        if (removed != null) {
            // One fire here covers approve and reject too — both route through removeProposal.
            // Fired only on an actual removal so a miss (already-drained proposal) is silent.
            fireQueueChanged();
        }
        return removed;
    }

    /**
     * Gets all pending proposals for a session.
     *
     * @param sessionId the session identifier
     * @return list of pending proposals (empty if none)
     */
    public List<PendingProposal> getPendingProposals(String sessionId) {
        MutationContext context = batchSessions.get(sessionId);
        return context != null ? context.getPendingProposals() : List.of();
    }

    /**
     * Dispatches an already-rebuilt approved command, respecting the current batch/immediate mode.
     * Immediate mode bumps the version counter via {@link #onImmediateDispatchCallback}; BATCH mode
     * queues the command (parity with the previous {@code executeProposal} BATCH branch).
     *
     * @param sessionId   the session identifier
     * @param command     the fresh command rebuilt by {@link ProposalBuilder}
     * @param description the proposal description (batch queue label)
     * @return batch sequence number if queued, null if dispatched immediately
     * @throws MutationException if dispatch fails
     */
    private Integer dispatchApproved(String sessionId, PendingProposal proposal, Command command,
            String description) throws MutationException {
        OperationalMode mode = getMode(sessionId);
        if (mode == OperationalMode.BATCH) {
            return queueForBatch(sessionId, command, description, proposal.tool());
        }
        Function<IArchimateModel, ModelAccessException> check = target -> proposalRefusal(proposal, target);
        try {
            dispatchGuarded(command, proposal.modelAddressed() ? boundGuard(() -> proposalTarget(proposal), check)
                    : check, sessionId);
        } catch (AgentAuthoredCompoundCommand.ChangesLeft | AgentAuthoredCompoundCommand.Failed failure) {
            // Part of it may still be in the model: a new version.
            if (onImmediateDispatchCallback != null) {
                onImmediateDispatchCallback.run();
            }
            throw failure;
        }
        // A command that declined with nothing written left the model as it was: no new version.
        if (onImmediateDispatchCallback != null && !changedNothing(command)) {
            onImmediateDispatchCallback.run();
        }
        return null;
    }

    /** Whether {@code command}, or every part of it, declined and vouches it left the model as it was. */
    private static boolean changedNothing(Command command) {
        return CommitSkippableCommand.vouchesNothingChanged(command);
    }

    // ---- Handler-facing facade methods ----
    // These methods convert package-private PendingProposal to public DTOs,
    // keeping PendingProposal hidden from the handlers layer.

    /**
     * Returns pending proposals as DTOs for the handler layer.
     *
     * @param sessionId the session identifier
     * @return list of ProposalDto summaries (empty if none)
     */
    public List<ProposalDto> getPendingProposalDtos(String sessionId) {
        List<PendingProposal> proposals = getPendingProposals(sessionId);
        List<ProposalDto> dtos = new ArrayList<>();
        for (PendingProposal p : proposals) {
            dtos.add(toProposalDto(p));
        }
        return dtos;
    }

    /**
     * Returns the union of pending proposals across <em>all</em> sessions for the Pending Approvals
     * dock view. Each entry carries its {@code sessionId} so the view can route
     * Approve/Reject back to the right session — the gate is global (one human, one queue) even
     * though storage stays per-session.
     *
     * <p>Ordered by {@code createdAt} <strong>ascending</strong> (oldest first) so the human works
     * the queue top-down in the order the agent built it — also the order most likely to satisfy
     * inter-proposal dependencies. Sorting is on the {@link Instant} (not the ISO string) to avoid
     * the lexicographic hazard of variable fractional-second digits in {@code Instant.toString()}.</p>
     *
     * <p>Sweeps proposals older than {@link #PROPOSAL_TTL} from each session before listing, so
     * abandoned proposals stop counting against the hard cap and drop out of the human's view. Expiry is
     * surfaced via an INFO log, the proposal's disappearance from this live list, and — when a sweep
     * actually removed something — an {@code ApprovalQueueListener} fire so the dock repaints even if the
     * sweep was triggered by a non-dock caller. Non-destructive to the model.</p>
     *
     * @return all sessions' pending proposals as {@link PendingProposalView}, oldest first (empty if none)
     */
    public List<PendingProposalView> listAllPending() {
        Instant now = Instant.now();
        boolean anySwept = false;
        record Pair(String sessionId, PendingProposal proposal) {}
        List<Pair> pairs = new ArrayList<>();
        for (Map.Entry<String, MutationContext> entry : batchSessions.entrySet()) {
            String sessionId = entry.getKey();
            List<String> swept = entry.getValue().sweepExpired(now, PROPOSAL_TTL);
            if (!swept.isEmpty()) {
                anySwept = true;
                logger.info("Swept {} expired proposal(s) from session '{}' on list: {}",
                        swept.size(), sessionId, swept);
            }
            for (PendingProposal p : entry.getValue().getPendingProposals()) {
                pairs.add(new Pair(sessionId, p));
            }
        }
        // A TTL sweep is a queue mutation — notify listeners so the dock repaints even when the sweep was
        // triggered by a non-dock caller (e.g. the agent's list-pending-approvals), surfacing the expiry
        // rather than leaving an expired card on screen until the next dock-initiated refresh. Fired
        // only on an actual sweep; the returned list below already reflects post-sweep state, and the dock's
        // re-entrant rebuild→listAllPending finds nothing left to sweep, so this terminates in one cycle.
        if (anySwept) {
            fireQueueChanged();
        }
        pairs.sort(Comparator.comparing(pair -> pair.proposal().createdAt()));
        List<PendingProposalView> views = new ArrayList<>(pairs.size());
        for (Pair pair : pairs) {
            views.add(new PendingProposalView(pair.sessionId(), toProposalDto(pair.proposal())));
        }
        return views;
    }

    /**
     * Approves a single proposal: re-resolves and rebuilds its command fresh against the current model,
     * dispatches it, and returns the result with the entity DTO.
     *
     * <p><strong>Vet/rebuild before removing.</strong> The proposal is <em>peeked</em>, not removed,
     * until a fresh command has been successfully built. A stale verdict (the human edited/removed a
     * targeted object) or an unrebuildable target therefore <strong>leaves the proposal in the queue</strong>
     * and throws — so the Pending Approvals card stays put with its plain-language, target-named reason for
     * the human to read and Reject, rather than the card vanishing on the queue-changed repaint before the
     * reason can be seen. Only a successful rebuild removes the proposal and dispatches it.</p>
     *
     * <p><strong>And the rebuilt command must still match the card.</strong> Three artefacts have to agree
     * for this gate to mean anything: the card the human read, the command that runs, and the model both
     * were measured against. The staleness guard reconciles the last two; it cannot reconcile the first,
     * because the card is a propose-time photograph and the guard only fingerprints a target's own
     * attributes. A destructive proposal whose cascade grew during the review window therefore vets as
     * fresh while rebuilding into something bigger than the human authorised — so it is checked explicitly
     * and refused. The gate is only as honest as the narrowest of the three. The check compares counts,
     * not identities, so a cascade whose membership changed without changing size still passes; see
     * {@link DeleteApprovalCardText#cascadeDivergence}.</p>
     *
     * @param sessionId  the session identifier
     * @param proposalId the proposal to approve
     * @return ApprovalResult with entity and optional batch sequence, or null if not found
     * @throws MutationException if the proposal is stale or its command cannot be rebuilt (nothing dispatched),
     *         or if the dispatched command declined as it ran; the card is kept only when the command
     *         vouches that it changed nothing
     */
    public ApprovalResult approveProposal(String sessionId, String proposalId)
            throws MutationException {
        // An approval is never admitted: it acts on a stored request, on the MCP or the UI thread. A
        // value left on this thread by an earlier call must not reach the queue it may dispatch into.
        admission.remove();
        PendingProposal proposal = getProposal(sessionId, proposalId);
        if (proposal == null) {
            return null;
        }
        logger.info("Approving proposal '{}' for session '{}': {}",
                proposalId, sessionId, proposal.description());
        // (0) Refuse before anything is rebuilt if the proposal's model cannot be the target: closed, or,
        // for one that followed the active model, no longer active. The rebuild would otherwise resolve
        // against the wrong model. Thrown before removeProposal, so the card stays. An addressed
        // proposal targets its own instance whichever model is active.
        IArchimateModel active = proposal.modelAddressed() ? proposalTarget(proposal) : modelSupplier.get();
        ModelAccessException early = proposalRefusal(proposal, active);
        // An approval made while this session has a batch open is queued into it, and the queue
        // refuses a command for another model only after the proposal is removed below. Check that
        // here too, so the refusal keeps the card on screen like every other approve-time refusal.
        MutationContext batch = batchSessions.get(sessionId);
        if (early == null && batch != null && batch.getMode() == OperationalMode.BATCH) {
            rebindIfBoundInstanceClosed(batch, active);
            early = boundModelRefusal(batch, active, "Approving " + proposalId + " into the open batch");
        }
        if (early != null) {
            throw approverRefusal(proposalId, early);
        }
        // Everything below resolves its model through the selection, so bind it to the target: the
        // rebuild, the staleness check and the dispatch then all act on the same instance.
        ModelSelection.Call outer = selection.bind(sessionId, active);
        try {
            return approveBound(sessionId, proposalId, proposal, active);
        } finally {
            selection.restore(outer);
        }
    }

    private ApprovalResult approveBound(String sessionId, String proposalId, PendingProposal proposal,
            IArchimateModel target) throws MutationException {
        // Store-the-request approve path — re-resolve + re-check + build fresh, never run a frozen
        // command, and DON'T remove the proposal until the rebuild succeeds (so a stale rejection keeps the
        // card on screen with its named reason). (1) Reject-stale if the human edited/removed a
        // targeted object since propose, naming what they touched. (2) Rebuild fresh against the
        // CURRENT model via the shared ProposalBuilder (same prepareXxx the immediate path runs, so no
        // drift); an unrebuildable target also surfaces as stale here. (2b) Refuse if the rebuilt command's
        // blast radius no longer matches the one printed on the card. (3) Only now remove from the queue
        // and dispatch through the same seam as immediate ops, preserving the agent-authored tag.
        ProposalStalenessGuard.StaleVerdict verdict = stalenessGuard.vet(proposal.capture(),
                id -> queuedAny(sessionId, id), target);
        if (verdict.stale()) {
            logger.info("Proposal '{}' rejected as stale (kept in queue): {}", proposalId, verdict.reason());
            throw new ProposalStaleException(verdict.reason());
        }
        PreparedMutation<?> fresh = proposalBuilder.rebuild(proposal); // may throw stale — proposal still queued
        // (2b) The card the human read is a propose-time photograph; the command just rebuilt measured the
        // model again. For a destructive proposal those two can disagree in the direction that removes more
        // than was authorised, and the staleness guard cannot see it (an attribute fingerprint does not move
        // when a folder gains contents). Refuse rather than silently over-delete. Thrown BEFORE
        // removeProposal for the same reason as the arms above: the card must stay on screen with its reason.
        String divergence = DeleteApprovalCardText.cascadeDivergence(proposal.proposedChanges(), fresh.entity());
        if (divergence != null) {
            logger.info("Proposal '{}' refused (kept in queue): the reviewed card no longer describes what "
                    + "approving would do: {}", proposalId, divergence);
            throw new ProposalStaleException(divergence);
        }
        removeProposal(sessionId, proposalId); // commit point: fresh command built, now drain + dispatch
        Integer batchSeq;
        try {
            batchSeq = dispatchApproved(sessionId, proposal, fresh.command(), proposal.description());
        } catch (ModelAccessException late) {
            if (!isBindingRefusal(late) && !(late instanceof BatchClosingCommand.Refusal)) {
                throw late;
            }
            // A model switched after the checks above, or the open batch is closed to this command —
            // the queue or the UI-thread guard refused before anything ran. Put the card back so the
            // human still sees it, with the reason.
            restoreProposal(sessionId, proposal);
            throw approverRefusal(proposalId, late);
        }
        // (4) A dispatched command can still decline as it runs, when the model moved in a way the card
        // did not cover. Say so instead of "approved". The card goes back only when the command vouches
        // it wrote nothing; otherwise part of it may have run, and the card no longer describes the model.
        // A command queued into an open batch has not run: its decline is reported at commit.
        if (batchSeq == null) {
            List<String> reasons = CommitSkippableCommand.collectSkipReasons(fresh.command());
            if (!reasons.isEmpty()) {
                String reason = String.join("; ", reasons);
                if (!reason.endsWith(".")) {
                    reason += ".";
                }
                logger.warn("Approved proposal '{}' declined as it ran: {}", proposalId, reason);
                if (changedNothing(fresh.command())) {
                    restoreProposal(sessionId, proposal);
                    throw new MutationException(reason + " Nothing was changed. Reject this proposal and ask "
                            + "the agent to make the change again.");
                }
                throw new MutationException(reason + " Part of this change may have been applied. Check the "
                        + "model in Archi before going on.");
            }
        }
        // Prefer the freshly re-resolved entity (e.g. a create's real new id) over the propose-time DTO.
        Object entity = fresh.entity() != null ? fresh.entity() : proposal.entity();
        return new ApprovalResult(entity, batchSeq, proposal.tool(), proposal.description());
    }

    /**
     * Rejects a single proposal: removes it from pending and returns
     * a DTO summary for the response.
     *
     * @param sessionId  the session identifier
     * @param proposalId the proposal to reject
     * @return ProposalDto with status "rejected", or null if not found
     */
    public ProposalDto rejectProposal(String sessionId, String proposalId) {
        PendingProposal proposal = removeProposal(sessionId, proposalId);
        if (proposal == null) {
            return null;
        }
        logger.info("Rejecting proposal '{}' for session '{}': {}",
                proposalId, sessionId, proposal.description());
        return new ProposalDto(
                proposal.proposalId(), proposal.tool(), "rejected",
                proposal.description(), proposal.currentState(),
                proposal.proposedChanges(), proposal.validationSummary(),
                proposal.createdAt().toString(),
                proposal.effectDescription(), proposal.intent(),
                proposal.modelId(), proposal.modelName());
    }

    private ProposalDto toProposalDto(PendingProposal p) {
        return new ProposalDto(
                p.proposalId(), p.tool(), "pending",
                p.description(), p.currentState(), p.proposedChanges(),
                p.validationSummary(), p.createdAt().toString(),
                p.effectDescription(), p.intent(), p.modelId(), p.modelName());
    }

    /**
     * Clears all session contexts. Called during server shutdown.
     */
    public void clearAllSessions() {
        batchSessions.clear();
        logger.debug("Cleared all mutation sessions (batch + approval)");
        fireQueueChanged();
    }

    // ---- Internal dispatch ----

    /**
     * Dispatches a command via Display.syncExec + CommandStack.
     * Protected for test override.
     *
     * @param command the command to dispatch
     * @throws MutationException if dispatch fails
     */
    protected void dispatchCommand(Command command) throws MutationException {
        dispatchImmediate(command);
    }

    private <T> T dispatchOnUiThread(java.util.concurrent.Callable<T> work) throws MutationException {
        Display display = Display.getDefault();
        if (display == null) {
            throw new MutationException(
                    "No display available — headless mode not supported for mutations");
        }

        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();

        display.syncExec(() -> {
            try {
                result.set(work.call());
            } catch (Exception e) {
                error.set(e);
            }
        });

        if (error.get() != null) {
            Exception ex = error.get();
            if (ex instanceof MutationException me) {
                throw me;
            }
            if (ex instanceof ModelAccessException refusal && isBindingRefusal(refusal)) {
                throw refusal; // a guard refusal from guardedStack: nothing ran, keep it structured
            }
            throw new MutationException("Mutation failed on UI thread", ex);
        }
        return result.get();
    }

    private IArchimateModel requireModel() throws MutationException {
        IArchimateModel model = selection.peekModelForCall(modelSupplier.get());
        if (model == null) {
            throw new MutationException("No model loaded — cannot execute mutation");
        }
        return model;
    }

    private CommandStack getCommandStack(IArchimateModel model) throws MutationException {
        Object adapter = model.getAdapter(CommandStack.class);
        if (!(adapter instanceof CommandStack stack)) {
            throw new MutationException("CommandStack not available for model");
        }
        return stack;
    }

    // ---- Model binding ----

    /**
     * Admits a mutating call against {@code model} for this session, returning the model unchanged.
     *
     * <p>Called at a mutating tool's entry, before anything is prepared, with the model that call
     * captured and will prepare against. A session whose open batch is bound to a different model
     * is refused here, naming the operation, so the caller learns of the divergence at its first
     * call rather than at commit — and before a prepare against the wrong model can fail in some
     * way that does not name the cause. Outside a batch this is a pass-through.</p>
     *
     * @param sessionId the session identifier
     * @param operation the tool operation being admitted, for the refusal message
     * @param model     the model the call captured
     * @return {@code model}
     * @throws ModelAccessException with {@link ErrorCode#BOUND_MODEL_NOT_ACTIVE} or
     *         {@link ErrorCode#BOUND_MODEL_CLOSED} when the batch is bound elsewhere
     */
    public IArchimateModel admit(String sessionId, String operation, IArchimateModel model) {
        admission.remove();
        selection.requireUnambiguous(operation);
        requireAdmissible(sessionId, operation, model);
        if (model != null) {
            admission.set(new Admission(epochOf(model), model, operation, false, sessionId, selection.inScope()));
        }
        return model;
    }

    /**
     * As {@link #admit}, for an operation that acts on the active model's {@code CommandStack}
     * directly and prepares nothing that is later queued or stored (undo, redo). Refused exactly as
     * a mutating call would be. The admitted instance binds the {@link #undo} or {@link #redo} that
     * follows, which is refused if another model became active in between; a queue or a store never
     * reads it.
     */
    public void admitStackOperation(String sessionId, String operation, IArchimateModel model) {
        admission.remove();
        selection.requireUnambiguous(operation);
        requireAdmissible(sessionId, operation, model);
        requireNotShared(sessionId, operation, model);
        if (model != null) {
            admission.set(new Admission(epochOf(model), model, operation, true, sessionId, selection.inScope()));
        }
    }

    /**
     * Ends the tool call on this thread: nothing it was admitted against stays bound to the thread.
     * Run by the tool registry once per call, after the handler returns or throws.
     */
    public void endCall() {
        admission.remove();
    }

    /**
     * Refuses an undo or redo while another session is working on the same model. Archi keeps one undo
     * history per model, not per session, so the step could reverse the other session's change. Read
     * against each session's latest call, so a session that has moved to another model stops counting.
     */
    private void requireNotShared(String sessionId, String operation, IArchimateModel model) {
        if (model == null || !selection.inScope()) {
            return;
        }
        int others = selection.otherSessionsHolding(sessionId, model);
        if (others == 0) {
            return;
        }
        String name = describeModel(model.getName(), model.getId());
        ModelAccessException refusal = new ModelAccessException("'" + operation + "' refused: model " + name
                + " is shared — " + others + " other session(s) are working on it. Archi keeps one undo history "
                + "per model, not per session, so this step could reverse another session's change. Nothing was "
                + (operation.equals("redo") ? "redone." : "undone."), ErrorCode.MODEL_SHARED, null,
                "This is available again once no other session is working on " + name + " (a session stops "
                        + "counting once its calls act on another model). To revert your own change now, make a "
                        + "new change that restores the earlier state.", null);
        logger.info("{}", CallerText.loggedMessage(refusal.getMessage()));
        throw refusal;
    }

    /** Removes this thread's admission, returning it when it is a mutating call's. */
    private Admission consumeMutationAdmission() {
        Admission admitted = admission.get();
        admission.remove();
        return admitted == null || admitted.stackOperation ? null : admitted;
    }

    private void requireAdmissible(String sessionId, String operation, IArchimateModel model) {
        MutationContext context = batchSessions.get(sessionId);
        if (context != null && context.getMode() == OperationalMode.BATCH) {
            rebindIfBoundInstanceClosed(context, model);
            if (selection.inScope()) {
                requireBatchModel(context, model, operation);
            }
            requireBatchBoundTo(context, model, "'" + operation + "'");
        }
    }

    /**
     * Refuses a tool call that resolved a different model from the one its session's open batch is
     * bound to, while that instance is open: the call named another model inside the batch. A closed
     * binding is left to {@link #requireBatchBoundTo}, whose refusal says so.
     */
    private void requireBatchModel(MutationContext context, IArchimateModel model, String operation) {
        IArchimateModel bound = openInstance(context.getBoundModelEpoch());
        if (bound == null || bound == model) {
            return;
        }
        String boundName = describeModel(context.getBoundModelName(), context.getBoundModelId());
        String called = model != null ? describeModel(model.getName(), model.getId()) : "(none)";
        if (model != null && Objects.equals(model.getId(), bound.getId())) {
            boundName += " " + describeCopy(bound);
            called += " " + describeCopy(model);
        }
        List<String> queued = context.getQueuedDescriptions();
        ModelAccessException refusal = new ModelAccessException("'" + operation + "' refused: this call "
                + "addresses model " + called + ", but this session's batch is bound to model " + boundName
                + ". Nothing was queued, and the " + queued.size() + " operation(s) already queued are "
                + "unchanged.", ErrorCode.BATCH_MODEL_MISMATCH,
                queued.isEmpty() ? "No operations are queued." : "Queued operations (" + queued.size() + "): "
                        + enumerate(queued),
                "Either end the batch first (end-batch commits it; end-batch rollback=true discards it) and "
                        + "then retry, or correct the call: pass the batch's model id " + context.getBoundModelId()
                        + " as modelId, or omit modelId, which defaults to the batch's model.", null);
        logger.info("{}", CallerText.loggedMessage(refusal.getMessage()));
        throw refusal;
    }

    /**
     * The instance an addressed proposal is approved into: the one it was proposed on while that is
     * open, else the one open instance with its id (a request rebuilds correctly against a reopened
     * file); null when there is none, or more than one.
     */
    private IArchimateModel proposalTarget(PendingProposal proposal) {
        IArchimateModel own = proposal.modelEpoch() != 0L ? openInstance(proposal.modelEpoch()) : null;
        return own != null ? own : soleOpenInstanceWithId(proposal.modelId());
    }

    /** How many open, observed instances carry this id. */
    private int openInstancesWithId(String modelId) {
        int count = 0;
        synchronized (instanceEpochs) {
            for (Map.Entry<IArchimateModel, Long> entry : instanceEpochs.entrySet()) {
                if (Objects.equals(modelId, entry.getKey().getId()) && !isClosed(entry.getValue())) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Rebinds an <em>empty</em> batch whose bound instance has closed to {@code target}, when that is
     * an open instance with the same id. Its queue references no instance, so nothing is re-pointed;
     * this is what lets an empty batch outlive a close and reopen of its file. Only ever called on
     * the entry side (admission, approval, end of batch) and never from {@link #queueForBatch}: a call
     * admitted before the close must meet the strict check there, not follow the id to the new
     * instance.
     */
    private void rebindIfBoundInstanceClosed(MutationContext context, IArchimateModel target) {
        long bound = context.getBoundModelEpoch();
        if (target == null || !isClosed(bound) || !Objects.equals(target.getId(), context.getBoundModelId())) {
            return;
        }
        long targetEpoch = epochOf(target);
        if (!isClosed(targetEpoch) && context.rebindEmpty(bound, targetEpoch, target.getName())) {
            logger.info("Empty batch rebound from a closed instance of model '{}' to the open one",
                    context.getBoundModelId());
        }
    }

    /**
     * Refuses a command whose call was admitted against a different instance from the one active
     * now: the model it was prepared against was closed, or another model was made active, while
     * the call was in progress.
     */
    private void requireAdmittedInstance(Admission admitted, IArchimateModel active, String refused,
            MutationContext context) {
        if (active != null && epochOf(active) == admitted.epoch && !isClosed(admitted.epoch)) {
            return;
        }
        throw admittedRefusal(admitted, active, refused, "Nothing was queued, and the "
                + context.getQueuedCount() + " operation(s) already queued are unchanged.",
                "; or discard this batch with end-batch rollback=true");
    }

    /**
     * The refusal for an admitted call whose instance closed, or stopped being the active one, while
     * the call was in progress. {@code outcome} states what the refusal left behind and must be true;
     * {@code batchRemedy} is appended to the remedy when a batch is involved.
     */
    private ModelAccessException admittedRefusal(Admission admitted, IArchimateModel active, String refused,
            String outcome, String batchRemedy) {
        String prepared = describeModel(admitted.modelName, admitted.modelId);
        String subject = refused + " refused: this call was " + (admitted.stackOperation ? "made" : "prepared")
                + " against model " + prepared;
        ModelAccessException refusal;
        if (isClosed(admitted.epoch) || admitted.instance.get() == null) {
            refusal = new ModelAccessException(subject + ", which was closed in Archi while the call was in "
                    + "progress. " + outcome, ErrorCode.BOUND_MODEL_CLOSED, null,
                    (admitted.stackOperation
                            ? "Its changes closed with it; confirm which model is active with get-model-info "
                                    + "before undoing or redoing anything there"
                            : "Retry the call, which prepares it against the model open now (reopen " + prepared
                                    + " in Archi first if it is not open)") + batchRemedy + ".", null);
        } else {
            String now = active != null ? describeModel(active.getName(), active.getId()) : null;
            refusal = new ModelAccessException(subject + ", but " + (now != null ? "the active model became "
                    + now : "no model is active") + " while the call was in progress. " + outcome,
                    ErrorCode.BOUND_MODEL_NOT_ACTIVE, null,
                    switchBackRemedy(prepared, now) + ", then retry" + batchRemedy + ".", null);
        }
        logger.info("{}", CallerText.loggedMessage(refusal.getMessage()));
        return refusal;
    }

    private boolean isClosed(long epoch) {
        return epoch != 0L && closedInstances.containsKey(epoch);
    }

    /** The open instance with this epoch, or null when it has closed or is no longer referenced. */
    private IArchimateModel openInstance(long epoch) {
        if (isClosed(epoch)) {
            return null;
        }
        synchronized (instanceEpochs) {
            for (Map.Entry<IArchimateModel, Long> entry : instanceEpochs.entrySet()) {
                if (entry.getValue() == epoch) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    /**
     * Whether a model with this id has been seen closing and no instance with it is open now. The
     * id-level view of closure, derived from the per-instance record: it is what a refusal names
     * when the active model has a different id, and the remedy is to reopen the file.
     */
    private boolean closedWithNoOpenCopy(String modelId) {
        if (!closedInstances.containsValue(modelId)) {
            return false;
        }
        synchronized (instanceEpochs) {
            for (Map.Entry<IArchimateModel, Long> entry : instanceEpochs.entrySet()) {
                if (modelId.equals(entry.getKey().getId()) && !isClosed(entry.getValue())) {
                    return false;
                }
            }
        }
        return true;
    }

    private void requireBatchBoundTo(MutationContext context, IArchimateModel target, String refused) {
        ModelAccessException refusal = boundModelRefusal(context, target, refused);
        if (refusal != null) {
            logger.info("{}", CallerText.loggedMessage(refusal.getMessage()));
            throw refusal;
        }
    }

    /**
     * The refusal for doing {@code refused} on this batch with {@code target} active, or null when
     * the batch may proceed (bound to {@code target}, or not bound to any model).
     */
    private ModelAccessException boundModelRefusal(MutationContext context, IArchimateModel target,
            String refused) {
        String boundId = context.getBoundModelId();
        if (boundId == null) {
            return null;
        }
        String bound = describeModel(context.getBoundModelName(), boundId);
        List<String> queued = context.getQueuedDescriptions();
        String queuedDetails = queued.isEmpty() ? "No operations are queued."
                : "Queued operations (" + queued.size() + "): " + enumerate(queued);
        long boundEpoch = context.getBoundModelEpoch();
        // Queued work bound to a closed instance is orphaned whether the close was seen with the work
        // already queued or the work reached the queue in the moment after it.
        if (context.isBoundModelOrphaned() || (isClosed(boundEpoch) && !queued.isEmpty())) {
            return new ModelAccessException(refused + " refused: this batch is bound to model " + bound
                    + ", which was closed while the batch held " + queued.size()
                    + " queued operation(s). Those operations belong to the closed copy of the model and "
                    + "cannot be committed, even after it is reopened. Nothing was applied to any model.",
                    ErrorCode.BOUND_MODEL_CLOSED, queuedDetails,
                    "Discard this batch with end-batch rollback=true, reopen " + bound
                            + " in Archi if it is not open, and replay the operations listed in details "
                            + "in a new batch.", null);
        }
        if (target != null && boundId.equals(target.getId())) {
            if (boundEpoch == 0L || epochOf(target) == boundEpoch) {
                return null;
            }
            if (isClosed(boundEpoch)) {
                // Only reachable where an empty batch may not be rebound: the queue, mid-call.
                return new ModelAccessException(refused + " refused: this batch is bound to a copy of "
                        + "model " + bound + " that has been closed in Archi; the open model with that id "
                        + "is a different copy. Nothing was applied to any model.",
                        ErrorCode.BOUND_MODEL_CLOSED, queuedDetails,
                        "Retry the call, which binds the empty batch to the open copy; or discard this "
                                + "batch with end-batch rollback=true.", null);
            }
            String boundCopy = describeCopy(openInstance(boundEpoch));
            String activeCopy = describeCopy(target);
            return new ModelAccessException(refused + " refused: this batch is bound to model " + bound
                    + " " + boundCopy + ", but the active model is another open copy with the same id, "
                    + activeCopy + ". Nothing was applied to either copy, and the " + queued.size()
                    + " operation(s) already queued are unchanged.",
                    ErrorCode.BOUND_MODEL_NOT_ACTIVE, queuedDetails,
                    sameIdRemedy(boundCopy, activeCopy) + ", then retry; or discard this batch with "
                            + "end-batch rollback=true.", null);
        }
        if (closedWithNoOpenCopy(boundId)) {
            return new ModelAccessException(refused + " refused: this batch is bound to model " + bound
                    + ", which has been closed in Archi. Nothing was applied to any model.",
                    ErrorCode.BOUND_MODEL_CLOSED, queuedDetails,
                    "Reopen " + bound + " in Archi, then retry; or discard this batch with "
                            + "end-batch rollback=true.", null);
        }
        if (context.isBoundModelAddressed()) {
            // Its own instance is gone and no single open copy can stand in for it.
            boolean copies = openInstancesWithId(boundId) > 1;
            return new ModelAccessException(refused + " refused: this batch is bound to model " + bound
                    + (copies ? ", whose instance was closed, and that id is now open more than once"
                            : ", which is no longer open in Archi") + ". Nothing was applied to any model.",
                    ErrorCode.BOUND_MODEL_CLOSED, queuedDetails,
                    (copies ? "Close the copies you do not mean in Archi, then retry"
                            : "Reopen " + bound + " in Archi, then retry")
                            + "; or discard this batch with end-batch rollback=true.", null);
        }
        String active = target != null ? describeModel(target.getName(), target.getId()) : null;
        return new ModelAccessException(refused + " refused: this batch is bound to model " + bound
                + (active != null ? ", but the active model is now " + active : ", but no model is active")
                + ". Nothing was applied to either model, and the " + queued.size()
                + " operation(s) already queued are unchanged.",
                ErrorCode.BOUND_MODEL_NOT_ACTIVE, queuedDetails,
                switchBackRemedy(bound, active) + ", then retry; or discard this batch with "
                        + "end-batch rollback=true.", null);
    }

    /**
     * The refusal for approving this proposal with {@code target} active, or null when it may proceed
     * (its model is active and open, or it is bound to no model).
     */
    private ModelAccessException proposalRefusal(PendingProposal proposal, IArchimateModel target) {
        String boundId = proposal.modelId();
        if (boundId == null) {
            return null;
        }
        String bound = describeModel(proposal.modelName(), boundId);
        String subject = "This change was proposed against model " + bound;
        long stamped = proposal.modelEpoch();
        if (target != null && boundId.equals(target.getId())) {
            // A proposal stores the request and rebuilds against the live instance, so once the
            // instance it was made on has closed, the open one with its id is the right target. While
            // that instance is still open, though, another copy being active is a divergence.
            if (stamped == 0L || stamped == epochOf(target) || isClosed(stamped)) {
                return null;
            }
            String stampedCopy = describeCopy(openInstance(stamped));
            String activeCopy = describeCopy(target);
            return new ModelAccessException(subject + " " + stampedCopy + ", but the active model is "
                    + "another open copy with the same id, " + activeCopy + ". Nothing was applied to "
                    + "either copy.", ErrorCode.BOUND_MODEL_NOT_ACTIVE, null,
                    sameIdRemedy(stampedCopy, activeCopy) + ", then approve; or reject this proposal.",
                    null);
        }
        if (closedWithNoOpenCopy(boundId)) {
            return new ModelAccessException(subject + ", which has been closed in Archi. Nothing was "
                    + "applied to any model.", ErrorCode.BOUND_MODEL_CLOSED, null,
                    "Reopen " + bound + ", then approve; or reject this proposal.", null);
        }
        if (proposal.modelAddressed()) {
            boolean copies = openInstancesWithId(boundId) > 1;
            return new ModelAccessException(subject + (copies ? ", whose instance was closed, and that id is "
                    + "now open more than once" : ", which is no longer open in Archi") + ". Nothing was "
                    + "applied to any model.", ErrorCode.BOUND_MODEL_CLOSED, null,
                    (copies ? "Close the copies you do not mean" : "Reopen " + bound)
                            + ", then approve; or reject this proposal.", null);
        }
        String active = target != null ? describeModel(target.getName(), target.getId()) : null;
        return new ModelAccessException(subject
                + (active != null ? ", but the active model is now " + active : ", but no model is active")
                + ". Nothing was applied to either model.", ErrorCode.BOUND_MODEL_NOT_ACTIVE, null,
                switchBackRemedy(bound, active) + ", then approve; or reject this proposal.", null);
    }

    /**
     * An approve-time refusal as the human at the Pending Approvals card sees it. The view shows a
     * {@link MutationException}'s message, so the remedy goes into it — written for the approver: a
     * batch refusal's own remedy (roll the batch back) is the agent's to act on, not theirs.
     */
    private MutationException approverRefusal(String proposalId, ModelAccessException refusal) {
        String remedy;
        if (refusal instanceof BatchClosingCommand.Refusal
                && refusal.getErrorCode() == ErrorCode.BATCH_ALREADY_ACTIVE) {
            remedy = "Ask the agent to end its batch, then approve; or reject this proposal.";
        } else if (refusal instanceof BatchClosingCommand.Refusal
                && refusal.getErrorCode() == ErrorCode.VIEW_OBJECT_NOT_FOUND) {
            remedy = "Reject this proposal: an operation already queued in the agent's batch takes that object off "
                    + "the view when the batch commits.";
        } else if (refusal instanceof BatchClosingCommand.Refusal) {
            remedy = "Reject this proposal: the merge already queued in the agent's batch deletes that element "
                    + "when the batch commits, so this proposal cannot be approved as it stands.";
        } else if (refusal.getMessage().contains("into the open batch")) {
            remedy = refusal.getErrorCode() == ErrorCode.BOUND_MODEL_CLOSED
                    ? "Ask the agent to roll back its batch, then approve; or reject this proposal."
                    : "Make the batch's model active again in Archi (confirm with get-model-info), or ask "
                            + "the agent to roll back its batch, then approve; or reject this proposal.";
        } else {
            remedy = refusal.getSuggestedCorrection();
        }
        String reason = refusal.getMessage() + " " + remedy;
        logger.info("Proposal '{}' refused (kept in queue): {}", proposalId, reason);
        return new MutationException(reason);
    }

    private static boolean isBindingRefusal(ModelAccessException e) {
        return e.getErrorCode() == ErrorCode.BOUND_MODEL_NOT_ACTIVE
                || e.getErrorCode() == ErrorCode.BOUND_MODEL_CLOSED;
    }

    /** Returns a proposal removed for dispatch to its session's queue, under the same id. */
    private void restoreProposal(String sessionId, PendingProposal proposal) {
        batchSessions.computeIfAbsent(sessionId, k -> new MutationContext()).restoreProposal(proposal);
        fireQueueChanged();
    }

    /**
     * How the human makes the bound model active again. Archi offers no "make active" action: the
     * server follows the most recently opened model, and on closing the active one falls back to
     * another open model, which is not necessarily the bound one — hence the confirmation step.
     */
    private static String switchBackRemedy(String bound, String active) {
        return (active != null ? "Close " + active + " in Archi so that " + bound + " becomes active again"
                : "Make " + bound + " the active model in Archi")
                + " (confirm with get-model-info)";
    }

    /**
     * How the human makes one copy of a model active again when another copy with the same id is
     * active. Id and name are identical across copies, so only the files tell them apart.
     */
    private static String sameIdRemedy(String boundCopy, String activeCopy) {
        if (boundCopy.equals(activeCopy)) {
            // Neither copy has a file, so nothing the server can print tells them apart.
            return "Both copies are unsaved, so they cannot be told apart here: save the copy this work "
                    + "belongs to under a file name, make it the active model in Archi";
        }
        return "Make the copy " + boundCopy + " the active model again in Archi, for example by closing "
                + "the copy " + activeCopy + " (save it first if it holds changes you want to keep)";
    }

    /**
     * Names one copy of a model by its file. Two open copies share their id and usually their name,
     * so the file is the only thing that tells them apart.
     */
    private static String describeCopy(IArchimateModel model) {
        if (model == null) {
            return "(no longer open)";
        }
        return model.getFile() != null ? "at " + model.getFile().getPath() : "(unsaved, no file)";
    }

    private static String describeModel(String name, String id) {
        return "'" + (name != null ? name : "(unnamed)") + "' (id " + id + ")";
    }

    private static String enumerate(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            sb.append(i == 0 ? "" : "; ").append(i + 1).append(". ").append(items.get(i));
        }
        return sb.toString();
    }

    private MutationContext getActiveContext(String sessionId) {
        MutationContext context = batchSessions.get(sessionId);
        if (context == null || context.getMode() != OperationalMode.BATCH) {
            throw new IllegalStateException("No active batch for session '" + sessionId + "'");
        }
        return context;
    }
}
