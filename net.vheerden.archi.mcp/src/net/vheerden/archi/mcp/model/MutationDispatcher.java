package net.vheerden.archi.mcp.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.swt.widgets.Display;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.archimatetool.model.IArchimateModel;

import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.response.dto.BatchStatusDto;
import net.vheerden.archi.mcp.response.dto.BatchSummaryDto;
import net.vheerden.archi.mcp.response.dto.ProposalDto;

/**
 * Dispatches mutation commands to the ArchiMate model via CommandStack (Story 7-1).
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

    public MutationDispatcher(Supplier<IArchimateModel> modelSupplier) {
        this.modelSupplier = Objects.requireNonNull(modelSupplier, "modelSupplier must not be null");
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
        dispatchOnUiThread(() -> {
            CommandStack stack = getCommandStack(model);
            stack.execute(command);
            return null;
        });
    }

    // ---- Undo/Redo (Story 11-1) ----

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
        return dispatchOnUiThread(() -> {
            CommandStack stack = getCommandStack(model);
            List<String> labels = new ArrayList<>();
            for (int i = 0; i < steps; i++) {
                if (!stack.canUndo()) break;
                Command cmd = stack.getUndoCommand();
                if (cmd != null) {
                    labels.add(cmd.getLabel());
                }
                stack.undo();
            }
            return new UndoRedoState(labels, stack.canUndo(), stack.canRedo());
        });
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
        return dispatchOnUiThread(() -> {
            CommandStack stack = getCommandStack(model);
            List<String> labels = new ArrayList<>();
            for (int i = 0; i < steps; i++) {
                if (!stack.canRedo()) break;
                Command cmd = stack.getRedoCommand();
                if (cmd != null) {
                    labels.add(cmd.getLabel());
                }
                stack.redo();
            }
            return new UndoRedoState(labels, stack.canUndo(), stack.canRedo());
        });
    }

    /**
     * Internal state returned from undo/redo operations.
     */
    public record UndoRedoState(List<String> labels, boolean canUndo, boolean canRedo) {}

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
        logger.info("Beginning batch for session '{}'{}", sessionId,
                description != null ? ": " + description : "");
        MutationContext context = batchSessions.computeIfAbsent(
                sessionId, k -> new MutationContext());
        context.beginBatch(description);
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
            return summary;
        }

        int queuedCount = context.getQueuedCount();
        logger.info("Committing batch for session '{}' ({} queued commands)",
                sessionId, queuedCount);

        BatchSummaryDto summary = context.buildCommitSummary();

        if (queuedCount > 0) {
            Command compound = context.buildCompoundCommand();
            dispatchCommand(compound);
        }

        context.reset();
        return summary;
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
        MutationContext context = batchSessions.get(sessionId);
        if (context == null) {
            return new BatchStatusDto(
                    OperationalMode.GUI_ATTACHED.name(), null, null, null);
        }
        return context.getBatchStatus();
    }

    // ---- Approval management (Story 7-6) ----

    /**
     * Sets whether approval mode is active for a session.
     *
     * @param sessionId the session identifier
     * @param required true to enable approval mode, false to disable
     */
    public void setApprovalRequired(String sessionId, boolean required) {
        logger.info("Setting approval mode for session '{}': {}", sessionId, required);
        MutationContext context = batchSessions.computeIfAbsent(
                sessionId, k -> new MutationContext());
        context.setApprovalRequired(required);
    }

    /**
     * Checks if approval mode is active for a session.
     *
     * @param sessionId the session identifier
     * @return true if approval is required, false otherwise
     */
    public boolean isApprovalRequired(String sessionId) {
        MutationContext context = batchSessions.get(sessionId);
        return context != null && context.isApprovalRequired();
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
        String id = context.storeProposal(proposal);
        logger.debug("Stored proposal '{}' for session '{}': {}", id, sessionId, proposal.description());
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
        return context != null ? context.removeProposal(proposalId) : null;
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
     * Executes an approved proposal by dispatching its stored Command.
     * Respects the current batch/immediate mode.
     *
     * @param sessionId the session identifier
     * @param proposal the approved proposal to execute
     * @return batch sequence number if queued, null if dispatched immediately
     * @throws MutationException if dispatch fails (proposal may be stale)
     */
    public Integer executeProposal(String sessionId, PendingProposal proposal)
            throws MutationException {
        logger.info("Executing approved proposal '{}' for session '{}': {}",
                proposal.proposalId(), sessionId, proposal.description());
        OperationalMode mode = getMode(sessionId);
        if (mode == OperationalMode.BATCH) {
            return queueForBatch(sessionId, proposal.command(), proposal.description());
        }
        dispatchCommand(proposal.command());
        if (onImmediateDispatchCallback != null) {
            onImmediateDispatchCallback.run();
        }
        return null;
    }

    // ---- Handler-facing facade methods (Story 7-6) ----
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
     * Approves a single proposal: removes it from pending, executes the
     * stored Command, and returns the result with the entity DTO.
     *
     * @param sessionId  the session identifier
     * @param proposalId the proposal to approve
     * @return ApprovalResult with entity and optional batch sequence, or null if not found
     * @throws MutationException if command execution fails (proposal is stale)
     */
    public ApprovalResult approveProposal(String sessionId, String proposalId)
            throws MutationException {
        PendingProposal proposal = removeProposal(sessionId, proposalId);
        if (proposal == null) {
            return null;
        }
        logger.info("Approving proposal '{}' for session '{}': {}",
                proposalId, sessionId, proposal.description());
        Integer batchSeq = executeProposal(sessionId, proposal);
        return new ApprovalResult(
                proposal.entity(), batchSeq, proposal.tool(), proposal.description());
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
                proposal.createdAt().toString());
    }

    private ProposalDto toProposalDto(PendingProposal p) {
        return new ProposalDto(
                p.proposalId(), p.tool(), "pending",
                p.description(), p.currentState(), p.proposedChanges(),
                p.validationSummary(), p.createdAt().toString());
    }

    /**
     * Clears all session contexts. Called during server shutdown.
     */
    public void clearAllSessions() {
        batchSessions.clear();
        logger.debug("Cleared all mutation sessions (batch + approval)");
    }

    // ---- Internal dispatch ----

    /**
     * Dispatches a command via Display.syncExec + CommandStack.
     * Protected for test override (E2E dispatch tested in Story 7-2).
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
