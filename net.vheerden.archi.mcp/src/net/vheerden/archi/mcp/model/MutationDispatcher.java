package net.vheerden.archi.mcp.model;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
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

import net.vheerden.archi.mcp.model.exceptions.MutationException;
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
 * <p>Manages per-session state via {@link MutationContext}, supporting
 * GUI-attached (immediate), batch (queued), and approval (proposed)
 * operational modes.</p>
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
     * The staleness guard — one {@code CommandStack} listener over the active model, re-resolving and
     * fingerprinting a proposal's targets so approve rejects-stale rather than misapplying a frozen command.
     */
    private final ProposalStalenessGuard stalenessGuard;

    /** The shared "stored request → fresh command + preconditions" rebuilder used by the approve path. */
    private final ProposalBuilder proposalBuilder = new ProposalBuilder();

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
    }

    // ---- Staleness-guard lifecycle (driven by the accessor's model events) ----

    /**
     * Registers the staleness guard's single {@code CommandStack} listener for the now-active model.
     * Called by the accessor when a model becomes active / on model switch. Idempotent.
     */
    public void onModelActive(IArchimateModel model) {
        stalenessGuard.onModelActive(model);
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
        return stalenessGuard.capture(targetIds, id -> queuedAny(sessionId, id));
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
        IArchimateModel model = requireModel();
        // Stamp authorship at the single admission chokepoint. Every agent
        // mutation — immediate single ops, the batch-commit compound, and approved-proposal
        // executes — funnels here, so wrapping once tags them all. Human GUI edits reach
        // Archi's CommandStack directly and stay untagged. One wrapper == one stack entry,
        // preserving the speculative undo(undoCount) arithmetic.
        AgentAuthoredCompoundCommand tagged = new AgentAuthoredCompoundCommand(command);
        dispatchOnUiThread(() -> {
            CommandStack stack = getCommandStack(model);
            stack.execute(tagged);
            return null;
        });
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
        IArchimateModel model = requireModel();
        return dispatchOnUiThread(() -> scopedUndo(getCommandStack(model), steps));
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
                labels.add(cmd.getLabel());
                stack.undo();
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
        IArchimateModel model = requireModel();
        return dispatchOnUiThread(() -> scopedRedo(getCommandStack(model), steps));
    }

    /**
     * Pure, {@code Display}-free decision core for the scoped agent redo (symmetric
     * to {@link #scopedUndo}). Re-applies <strong>only</strong> agent-authored redo entries: if the
     * top redo entry is human-authored (e.g. the human used native undo on their own edit), perform
     * zero redos and return {@link #BLOCK_REASON_HUMAN_EDIT}; for {@code steps > 1}, stop without
     * error at the first human redo entry. A human change must never be re-applied — or evaporated —
     * by the agent. An empty/exhausted redo stack returns zero with no block reason.
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
                labels.add(cmd.getLabel());
                stack.redo();
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
                description != null ? ": " + description : "");
        MutationContext context = batchSessions.computeIfAbsent(
                sessionId, k -> new MutationContext());
        context.beginBatch(description, intent);
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
        MutationContext context = getActiveContext(sessionId);

        if (!commit) {
            logger.info("Rolling back batch for session '{}' ({} queued commands)",
                    sessionId, context.getQueuedCount());
            BatchSummaryDto summary = context.buildRollbackSummary();
            context.reset();
            removeBatchSessionIfEmpty(sessionId);
            return summary;
        }

        int queuedCount = context.getQueuedCount();
        logger.info("Committing batch for session '{}' ({} queued commands)",
                sessionId, queuedCount);

        if (queuedCount > 0) {
            Command compound = context.buildCompoundCommand();
            dispatchCommand(compound);
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
     */
    public int queueForBatch(String sessionId, Command command, String description) {
        MutationContext context = getActiveContext(sessionId);
        int seq = context.queueCommand(command, description);
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
     * names no queued container — including a queued note, image or view-reference — leaving the
     * caller's ordinary live lookup (and its not-found error) in charge.</p>
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
        return context == null ? null : context.queuedContainer(parentViewObjectId);
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
        return context == null ? null : context.queuedConnectionEnd(viewObjectId, view);
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
                base.batchStarted(), approval, base.pendingApprovalCount());
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
        MutationContext context = batchSessions.computeIfAbsent(
                sessionId, k -> new MutationContext());
        // Sweep abandoned proposals first so TTL relieves the hard cap before this store.
        List<String> swept = context.sweepExpired(Instant.now(), PROPOSAL_TTL);
        if (!swept.isEmpty()) {
            logger.info("Swept {} expired proposal(s) from session '{}' on propose: {}",
                    swept.size(), sessionId, swept);
        }
        String id = context.storeProposal(proposal);
        logger.debug("Stored proposal '{}' for session '{}': {}", id, sessionId, proposal.description());
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
    private Integer dispatchApproved(String sessionId, Command command, String description)
            throws MutationException {
        OperationalMode mode = getMode(sessionId);
        if (mode == OperationalMode.BATCH) {
            return queueForBatch(sessionId, command, description);
        }
        dispatchCommand(command);
        if (onImmediateDispatchCallback != null) {
            onImmediateDispatchCallback.run();
        }
        return null;
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
     * @throws MutationException if the proposal is stale or its command cannot be rebuilt (nothing dispatched)
     */
    public ApprovalResult approveProposal(String sessionId, String proposalId)
            throws MutationException {
        PendingProposal proposal = getProposal(sessionId, proposalId);
        if (proposal == null) {
            return null;
        }
        logger.info("Approving proposal '{}' for session '{}': {}",
                proposalId, sessionId, proposal.description());
        // Store-the-request approve path — re-resolve + re-check + build fresh, never run a frozen
        // command, and DON'T remove the proposal until the rebuild succeeds (so a stale rejection keeps the
        // card on screen with its named reason). (1) Reject-stale if the human edited/removed a
        // targeted object since propose, naming what they touched. (2) Rebuild fresh against the
        // CURRENT model via the shared ProposalBuilder (same prepareXxx the immediate path runs, so no
        // drift); an unrebuildable target also surfaces as stale here. (2b) Refuse if the rebuilt command's
        // blast radius no longer matches the one printed on the card. (3) Only now remove from the queue
        // and dispatch through the same seam as immediate ops, preserving the agent-authored tag.
        ProposalStalenessGuard.StaleVerdict verdict = stalenessGuard.vet(proposal.capture(),
                id -> queuedAny(sessionId, id));
        if (verdict.stale()) {
            logger.info("Proposal '{}' rejected as stale (kept in queue): {}", proposalId, verdict.reason());
            throw new MutationException(verdict.reason());
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
            throw new MutationException(divergence);
        }
        removeProposal(sessionId, proposalId); // commit point: fresh command built, now drain + dispatch
        Integer batchSeq = dispatchApproved(sessionId, fresh.command(), proposal.description());
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
                proposal.effectDescription(), proposal.intent());
    }

    private ProposalDto toProposalDto(PendingProposal p) {
        return new ProposalDto(
                p.proposalId(), p.tool(), "pending",
                p.description(), p.currentState(), p.proposedChanges(),
                p.validationSummary(), p.createdAt().toString(),
                p.effectDescription(), p.intent());
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
            throw new MutationException("Mutation failed on UI thread", ex);
        }
        return result.get();
    }

    private IArchimateModel requireModel() throws MutationException {
        IArchimateModel model = modelSupplier.get();
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

    private MutationContext getActiveContext(String sessionId) {
        MutationContext context = batchSessions.get(sessionId);
        if (context == null || context.getMode() != OperationalMode.BATCH) {
            throw new IllegalStateException("No active batch for session '" + sessionId + "'");
        }
        return context;
    }
}
