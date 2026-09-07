package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;

/**
 * A command that re-checks its own preconditions at the moment it executes, and
 * declines to run rather than applying a destructive change the caller never authorised.
 *
 * <p><strong>Why this exists.</strong> Mutations are prepared against the model as it
 * looks when the request arrives, but on the deferred paths — a queued batch, or a
 * multi-operation bulk request — every operation is prepared before any of them runs.
 * A precondition that read live model state at prepare time can therefore be false by
 * the time the command executes, because an earlier operation in the same request
 * changed the very thing it validated. Re-checking at prepare time cannot help: the
 * check has to happen after the preceding commands have applied their changes, which
 * means inside {@code execute()}.</p>
 *
 * <p><strong>Why decline rather than throw.</strong> A GEF {@code CommandStack} pushes a
 * command onto the undo stack only <em>after</em> {@code execute()} returns. Throwing
 * from the middle of a compound therefore leaves the commands that already ran applied
 * to the model but absent from the undo history — a change the user cannot reverse.
 * Declining keeps the compound intact, so everything that did run stays undoable as a
 * single entry.</p>
 *
 * <p>A skipped command must also make {@code undo()} and {@code redo()} inert, since it
 * changed nothing to reverse or replay.</p>
 */
interface CommitSkippableCommand {

    /**
     * @return a plain-language explanation of why this command declined to run, or
     *         {@code null} if it executed normally. Only meaningful after execution.
     */
    String getSkipReason();

    /**
     * Collects the reasons from every command that declined, walking into compounds.
     *
     * <p>Returns one reason per <em>declining command</em>, so a single operation that is itself a
     * compound — a force delete-specialization whose clear and whose profile-removal both refuse in
     * step — contributes several reasons. Callers that report per operation should group these with
     * {@link #collectSkipReasonsByOperation} (bulk-mutate) or join a queue entry's reasons
     * themselves (the batch path, which pairs each entry with the caller's own description).</p>
     *
     * @param command the dispatched command, possibly a compound
     * @return the reasons in execution order; empty when nothing declined
     */
    static List<String> collectSkipReasons(Command command) {
        List<String> reasons = new ArrayList<>();
        collectInto(command, reasons);
        return List.copyOf(reasons);
    }

    /**
     * Groups skip reasons by the operation that declined: one entry per direct child of
     * {@code command} that refused, with a child's several nested reasons joined into a single
     * line rather than reported as separate ones.
     *
     * <p>Used by {@code bulk-mutate}, whose dispatched compound holds one child per requested
     * operation. Without the grouping a single operation that declines coherently — its clear and
     * its profile-removal both refusing — would surface as two {@code skippedOperations} lines for
     * one operation, which cannot be reconciled against the operation count.</p>
     *
     * @param command the dispatched command; when a compound, its direct children are the operations
     * @return one joined reason per declining operation, in order; empty when nothing declined
     */
    static List<String> collectSkipReasonsByOperation(Command command) {
        List<String> perOperation = new ArrayList<>();
        if (command instanceof CompoundCommand compound) {
            for (Object child : compound.getCommands()) {
                List<String> reasons = collectSkipReasons((Command) child);
                if (!reasons.isEmpty()) {
                    perOperation.add(String.join("; ", reasons));
                }
            }
        } else {
            List<String> reasons = collectSkipReasons(command);
            if (!reasons.isEmpty()) {
                perOperation.add(String.join("; ", reasons));
            }
        }
        return List.copyOf(perOperation);
    }

    private static void collectInto(Command command, List<String> reasons) {
        if (command instanceof CommitSkippableCommand skippable && skippable.getSkipReason() != null) {
            reasons.add(skippable.getSkipReason());
        }
        if (command instanceof CompoundCommand compound) {
            for (Object child : compound.getCommands()) {
                collectInto((Command) child, reasons);
            }
        }
    }
}
