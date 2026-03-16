package net.vheerden.archi.mcp.model;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.eclipse.gef.commands.Command;

import com.archimatetool.editor.model.commands.NonNotifyingCompoundCommand;

import net.vheerden.archi.mcp.response.dto.BatchStatusDto;
import net.vheerden.archi.mcp.response.dto.BatchSummaryDto;

/**
 * Per-session state for mutation operations (Story 7-1, extended Story 7-6).
 *
 * <p>Package-private — only used within the {@code model/} package by
 * {@link MutationDispatcher}. Tracks the current operational mode, queued
 * commands, batch timing, and approval state for a single session.</p>
 *
 * <p>Approval mode is an independent boolean flag orthogonal to the
 * GUI_ATTACHED/BATCH operational mode. When approval is active, mutations
 * are stored as proposals instead of being dispatched or queued.</p>
 *
 * <p>Thread safety: All public methods are synchronized. One session maps
 * to one Jetty thread at a time, but synchronization guards against any
 * concurrent access edge cases.</p>
 */
class MutationContext {

    static final int MAX_PENDING_PROPOSALS = 100;

    private OperationalMode mode = OperationalMode.GUI_ATTACHED;
    private final List<Command> commandQueue = new ArrayList<>();
    private final List<String> descriptions = new ArrayList<>();
    private int sequenceCounter = 0;
    private Instant batchStarted;
    private String batchDescription;

    // ---- Approval state (Story 7-6) ----
    private boolean approvalRequired = false;
    private final LinkedHashMap<String, PendingProposal> pendingProposals = new LinkedHashMap<>();
    private int proposalCounter = 0;

    // ---- Batch operations ----

    synchronized void beginBatch(String description) {
        if (mode == OperationalMode.BATCH) {
            throw new IllegalStateException("Already in batch mode");
        }
        mode = OperationalMode.BATCH;
        batchStarted = Instant.now();
        batchDescription = description;
    }

    synchronized int queueCommand(Command command, String description) {
        if (mode != OperationalMode.BATCH) {
            throw new IllegalStateException("Not in batch mode");
        }
        commandQueue.add(command);
        descriptions.add(description);
        return ++sequenceCounter;
    }

    synchronized NonNotifyingCompoundCommand buildCompoundCommand() {
        String label = batchDescription != null
                ? batchDescription
                : "Batch mutation (" + commandQueue.size() + " operations)";
        NonNotifyingCompoundCommand compound = new NonNotifyingCompoundCommand(label);
        commandQueue.forEach(compound::add);
        return compound;
    }

    synchronized BatchSummaryDto buildCommitSummary() {
        Duration elapsed = Duration.between(batchStarted, Instant.now());
        String duration = formatDuration(elapsed);
        return new BatchSummaryDto(
                commandQueue.size(),
                List.copyOf(descriptions),
                duration,
                false);
    }

    synchronized BatchSummaryDto buildRollbackSummary() {
        Duration elapsed = Duration.between(batchStarted, Instant.now());
        String duration = formatDuration(elapsed);
        return new BatchSummaryDto(
                commandQueue.size(),
                List.copyOf(descriptions),
                duration,
                true);
    }

    /**
     * Resets batch state. Approval state is NOT cleared — pending
     * proposals survive batch commit/rollback.
     */
    synchronized void reset() {
        mode = OperationalMode.GUI_ATTACHED;
        commandQueue.clear();
        descriptions.clear();
        sequenceCounter = 0;
        batchStarted = null;
        batchDescription = null;
    }

    synchronized OperationalMode getMode() {
        return mode;
    }

    synchronized int getQueuedCount() {
        return commandQueue.size();
    }

    synchronized BatchStatusDto getBatchStatus() {
        Boolean approval = approvalRequired ? Boolean.TRUE : null;
        Integer pendingCount = pendingProposals.isEmpty() ? null : pendingProposals.size();

        if (mode == OperationalMode.GUI_ATTACHED) {
            return new BatchStatusDto(
                    mode.name(), null, null, null,
                    approval, pendingCount);
        }
        return new BatchStatusDto(
                mode.name(),
                commandQueue.size(),
                List.copyOf(descriptions),
                batchStarted != null ? batchStarted.toString() : null,
                approval, pendingCount);
    }

    // ---- Approval operations (Story 7-6) ----

    synchronized void setApprovalRequired(boolean required) {
        this.approvalRequired = required;
    }

    synchronized boolean isApprovalRequired() {
        return approvalRequired;
    }

    /**
     * Stores a proposal and assigns it an ID.
     *
     * @param proposal the proposal (with null proposalId — will be replaced)
     * @return the assigned proposal ID
     * @throws IllegalStateException if max pending proposals reached
     */
    synchronized String storeProposal(PendingProposal proposal) {
        if (pendingProposals.size() >= MAX_PENDING_PROPOSALS) {
            throw new IllegalStateException(
                    "Maximum pending proposals reached (" + MAX_PENDING_PROPOSALS
                    + "). Approve or reject existing proposals before creating new ones.");
        }
        String id = "p-" + (++proposalCounter);
        PendingProposal withId = new PendingProposal(
                id, proposal.tool(), proposal.description(), proposal.command(),
                proposal.entity(), proposal.currentState(), proposal.proposedChanges(),
                proposal.validationSummary(), proposal.createdAt());
        pendingProposals.put(id, withId);
        return id;
    }

    synchronized PendingProposal getProposal(String proposalId) {
        return pendingProposals.get(proposalId);
    }

    synchronized PendingProposal removeProposal(String proposalId) {
        return pendingProposals.remove(proposalId);
    }

    synchronized List<PendingProposal> getPendingProposals() {
        return List.copyOf(pendingProposals.values());
    }

    synchronized int getPendingCount() {
        return pendingProposals.size();
    }

    synchronized void clearProposals() {
        pendingProposals.clear();
    }

    private String formatDuration(Duration duration) {
        long millis = duration.toMillis();
        if (millis < 1000) {
            return millis + "ms";
        }
        double seconds = millis / 1000.0;
        return String.format("%.1fs", seconds);
    }
}
