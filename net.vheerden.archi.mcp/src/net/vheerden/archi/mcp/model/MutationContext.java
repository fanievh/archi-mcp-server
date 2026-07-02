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
 * Per-session state for mutation operations.
 *
 * <p>Package-private — only used within the {@code model/} package by
 * {@link MutationDispatcher}. Tracks the current operational mode, queued
 * commands, batch timing, and approval state for a single session.</p>
 *
 * <p>Pending <em>proposals</em> remain per-session here. The approval-mode
 * <em>bit</em>, however, is no longer per-session: it is a single
 * global, human-owned switch read through {@code ApprovalModeProvider} in
 * {@link MutationDispatcher}. This context therefore stores proposals but not the
 * mode flag.</p>
 *
 * <p>Thread safety: All public methods are synchronized. One session maps
 * to one Jetty thread at a time, but synchronization guards against any
 * concurrent access edge cases.</p>
 *
 * <p><strong>The absence of direct references from {@code ArchiModelAccessorImpl} is by design.</strong>
 * The model facade interacts with batch and proposal state <em>exclusively</em> through
 * {@link MutationDispatcher}, which owns one {@code MutationContext} per session (its
 * {@code batchSessions} map). A "find usages" of this class from the facade therefore
 * returns nothing — that is correct encapsulation of package-private per-session state,
 * not an indication that this class is unused. The live reference path is
 * facade&nbsp;→&nbsp;{@link MutationDispatcher}&nbsp;→&nbsp;{@code MutationContext}.</p>
 *
 * <p>This concrete per-session state holder is also distinct from any prospective
 * refactoring that would route the facade's mutation-preparation glue through a
 * context or parameter object: such a change would build upon this class, not
 * supersede it.</p>
 */
class MutationContext {

    static final int MAX_PENDING_PROPOSALS = 100;

    private OperationalMode mode = OperationalMode.GUI_ATTACHED;
    private final List<Command> commandQueue = new ArrayList<>();
    private final List<String> descriptions = new ArrayList<>();
    private int sequenceCounter = 0;
    private Instant batchStarted;
    private String batchDescription;
    // Agent-supplied intent for this batch (optional). Recorded but never depended on by the
    // server; kept distinct from batchDescription (the undo-history label) — never merged (design §4 D6).
    private String batchIntent;

    // ---- Approval state (mode bit globalised — proposals stay per-session) ----
    private final LinkedHashMap<String, PendingProposal> pendingProposals = new LinkedHashMap<>();
    private int proposalCounter = 0;

    // ---- Batch operations ----

    synchronized void beginBatch(String description) {
        beginBatch(description, null);
    }

    synchronized void beginBatch(String description, String intent) {
        if (mode == OperationalMode.BATCH) {
            throw new IllegalStateException("Already in batch mode");
        }
        mode = OperationalMode.BATCH;
        batchStarted = Instant.now();
        batchDescription = description;
        batchIntent = intent;
    }

    /** The agent-supplied batch intent, or null when none was given. */
    synchronized String getBatchIntent() {
        return batchIntent;
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
        batchIntent = null;
    }

    synchronized OperationalMode getMode() {
        return mode;
    }

    synchronized int getQueuedCount() {
        return commandQueue.size();
    }

    synchronized BatchStatusDto getBatchStatus() {
        // Approval bit is global — MutationDispatcher.getBatchStatus overlays it.
        Integer pendingCount = pendingProposals.isEmpty() ? null : pendingProposals.size();

        if (mode == OperationalMode.GUI_ATTACHED) {
            return new BatchStatusDto(
                    mode.name(), null, null, null,
                    null, pendingCount);
        }
        return new BatchStatusDto(
                mode.name(),
                commandQueue.size(),
                List.copyOf(descriptions),
                batchStarted != null ? batchStarted.toString() : null,
                null, pendingCount);
    }

    // ---- Approval operations (proposal storage only — mode bit globalised) ----

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
        // Arity-proof copy that stamps the id (record-arity discipline) — preserves the deferred rebuild
        // handle + staleness capture + all card fields without re-listing them here.
        PendingProposal withId = proposal.withProposalId(id);
        pendingProposals.put(id, withId);
        return id;
    }

    /**
     * Sweeps proposals older than {@code ttl} (Design §4 D5 — proposal expiry). Relieves the
     * {@link #MAX_PENDING_PROPOSALS hard cap} so abandoned proposals do not block new ones; non-destructive
     * to the model (a proposal is an un-applied request). The proposal currently being approved is never
     * passed here — {@link MutationDispatcher#approveProposal} removes it from this map <em>before</em>
     * rebuilding/dispatching, so it cannot be swept mid-approve.
     *
     * @param now the current instant (injected for testability)
     * @param ttl the time-to-live; proposals with {@code createdAt} older than this are removed
     * @return the proposal ids that were swept (empty if none)
     */
    synchronized List<String> sweepExpired(Instant now, Duration ttl) {
        List<String> swept = new ArrayList<>();
        var it = pendingProposals.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (isExpired(entry.getValue().createdAt(), now, ttl)) {
                swept.add(entry.getKey());
                it.remove();
            }
        }
        return swept;
    }

    /**
     * Pure TTL-expiry predicate. A proposal is expired when its age
     * ({@code now - createdAt}) strictly exceeds {@code ttl}. Null {@code createdAt} is treated as
     * not-expired (defensive — a proposal always has a timestamp).
     */
    static boolean isExpired(Instant createdAt, Instant now, Duration ttl) {
        if (createdAt == null || now == null || ttl == null) {
            return false;
        }
        return Duration.between(createdAt, now).compareTo(ttl) > 0;
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
