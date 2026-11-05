package net.vheerden.archi.mcp.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.editor.model.commands.NonNotifyingCompoundCommand;

import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * Inert single-child wrapper that stamps a command as {@link AgentAuthoredCommand}
 * at admission.
 *
 * <p>Extends the <strong>plain</strong> GEF {@link CompoundCommand} — deliberately
 * <em>not</em> Archi's {@code NonNotifyingCompoundCommand}, which overrides
 * {@code execute/undo/redo} to fire {@code IEditorModelManager} {@code ECORE_EVENTS_START/END}
 * notifications. Extending that would inject notification-suppression around every single
 * immediate op (a behaviour change for every mutation). Plain {@code CompoundCommand} with a
 * single child is behaviourally transparent while every command in it succeeds:
 * {@code execute/undo/redo/canExecute/canUndo} then act as the one child would. Where a command
 * declines or throws, it departs from that, as the paragraphs below say.</p>
 *
 * <p>It wraps <strong>exactly one</strong> delegate command and represents <strong>one</strong>
 * stack entry for each dispatch that wrote, preserving the {@code 1 dispatch == 1 stack entry}
 * invariant the speculative undo/redo arithmetic in {@code ArchiModelAccessorImpl} depends on.
 * {@link #getLabel()} returns the delegate's label so the undo/redo menu and the MCP tool labels
 * are unchanged.</p>
 *
 * <p>A delegate that declined and vouches it changed nothing
 * ({@link CommitSkippableCommand#vouchesNothingChanged}) makes {@link #execute()} throw {@link Declined},
 * so GEF's {@code CommandStack} pushes no entry and leaves the dirty state alone; the dispatcher
 * catches it around {@code stack.execute}. A compound delegate vouches when every part of it does; a
 * skippable compound answers for itself. GEF has already flushed the redo history and fired
 * {@code PRE_EXECUTE} before {@code execute()} runs, so a declined dispatch still clears Redo.</p>
 *
 * <p>An entry built to roll back (a single immediate call) also undoes a delegate that declined
 * without that vouch, in the same run, and then throws {@link Declined}: the call either applies
 * whole or leaves the model as it was. Other entries keep what ran, as one entry, except as below.</p>
 *
 * <p>A command that declined and could not undo all of what it wrote
 * ({@link CommitSkippableCommand#leftChangesItCouldNotUndo}) fails the entry in every mode: the rest of the
 * delegate is undone and {@link ChangesLeft} reaches the caller, with nothing pushed. No entry could undo
 * what that command left, so one Undo could no longer return the model to where it was.</p>
 *
 * <p>A command that throws as it runs fails the entry in every mode too. The entry runs its delegate itself
 * ({@link Run}), opening GEF's and Archi's own compounds at every level, so everything that completed before
 * the command that threw is undone, latest first; Archi's notifying compounds it opened get their end
 * notice after that. {@link Failed} then reaches the caller in place of what was thrown, which is logged,
 * with nothing pushed. The command that threw is not undone, so part of what it wrote may be left, as may
 * what ran inside a compound of any other class before it threw.</p>
 *
 * <p>An undo or a redo that fails partway leaves the entry not applied, as far as it can. GEF's
 * {@code CommandStack} drops an entry whose {@code undo()} or {@code redo()} throws from both its stacks, so
 * after the throw the history believes the entry is not applied, and its top is the entry before. An undo
 * therefore carries on past a command that fails and undoes every other one, latest first; a redo stops at
 * the command that fails and undoes what it had redone, latest first. Both open the same compounds a run
 * opens and give Archi's notifying compounds their end notice whatever failed, then throw
 * {@link StackStepFailed}: the failure must still reach the stack, or GEF would move the entry to the other
 * stack as though it had succeeded.</p>
 *
 * <p>Putting a failed undo back, so the model stays as it was before the Undo, is rejected. The entry is
 * already off the stack, so the next Undo would reverse an earlier entry beneath a change still in the
 * model: an entry that created an element, under an entry that connected a relationship to it, would then
 * delete the element and leave the relationship pointing at nothing.</p>
 */
public final class AgentAuthoredCompoundCommand extends CompoundCommand implements AgentAuthoredCommand {

	private static final Logger logger = LoggerFactory.getLogger(AgentAuthoredCompoundCommand.class);

	/** How every failure that leaves part of a change in the model ends. */
	private static final String UNDO_WILL_NOT = "Undo will not remove it; it does not mark the model as changed "
			+ "either. Check the model in Archi before going on.";

	/** What a caller is told to do after a failure that may have left part of a change in the model. */
	private static final String MAY_STILL_BE = "Part of this change may still be in the model and Undo will not "
			+ "remove it. Re-read the elements with get-element, and the views that showed them, before going on.";

	private final Command delegate;

	/**
	 * The tool call that made this entry, or null when no single call owns it (a batch commit, an
	 * approved proposal). Inert: compared by identity to tell a call's own entries from another's,
	 * and never consulted by {@code execute}, {@code undo} or {@code redo}.
	 */
	private final Object owner;

	/** Whether a decline that cannot vouch it changed nothing is undone here rather than kept. */
	private final boolean rollBack;

	/** Run once when this entry fails as it runs, after the put-back; or null. */
	private final Runnable onFailed;

	/**
	 * Fires Archi's model notices for a notifying compound the run opens. Archi's editor model manager by
	 * default; the headless lane has none, so tests record the notices instead.
	 */
	static Notices notices = (source, property) -> IEditorModelManager.INSTANCE.firePropertyChange(source,
			property, false, true);

	/** Where the run sends the start and end notices of a notifying compound. */
	interface Notices {
		void fire(Command source, String property);
	}

	/**
	 * Wraps {@code delegate} as the single child of this compound, owned by no call.
	 *
	 * @param delegate the agent-authored command to tag and execute (must not be null)
	 */
	public AgentAuthoredCompoundCommand(Command delegate) {
		this(delegate, null);
	}

	/**
	 * Wraps {@code delegate} as the single child of this compound, owned by {@code owner}.
	 *
	 * @param delegate the agent-authored command to tag and execute (must not be null)
	 * @param owner    the call that made the entry, or null
	 */
	AgentAuthoredCompoundCommand(Command delegate, Object owner) {
		this(delegate, owner, false);
	}

	/**
	 * As {@link #AgentAuthoredCompoundCommand(Command, Object)}, undoing a delegate that declined
	 * partway when {@code rollBack} is set.
	 */
	AgentAuthoredCompoundCommand(Command delegate, Object owner, boolean rollBack) {
		this(delegate, owner, rollBack, null);
	}

	/**
	 * As {@link #AgentAuthoredCompoundCommand(Command, Object, boolean)}, running {@code onFailed} once when
	 * the entry fails as it runs, after what ran has been put back.
	 */
	AgentAuthoredCompoundCommand(Command delegate, Object owner, boolean rollBack, Runnable onFailed) {
		// Seed the CompoundCommand's own label with the delegate's for GEF debug/toString; the
		// authoritative label is always the dynamic getLabel() override below (so it stays correct
		// even if the delegate's label changes after construction).
		super(Objects.requireNonNull(delegate, "delegate must not be null").getLabel());
		this.delegate = delegate;
		this.owner = owner;
		this.rollBack = rollBack;
		this.onFailed = onFailed;
		add(delegate);
	}

	/** The call that made this entry, or null. */
	Object getOwner() {
		return owner;
	}

	/**
	 * @return the single wrapped command (the agent's actual mutation)
	 */
	public Command getDelegate() {
		return delegate;
	}

	/**
	 * Runs the delegate, then throws {@link Declined} when it declined with nothing written, which
	 * keeps this entry off the stack. In roll-back mode a delegate that declined after writing is
	 * undone first; a failed undo throws a {@link MutationException} instead, since the model may
	 * then be partly changed. A delegate with a command that left changes it could not undo is undone
	 * in any mode, and {@link ChangesLeft} thrown. A command that throws is answered with {@link Failed},
	 * after everything that completed before it is undone.
	 */
	@Override
	public void execute() {
		Run run = new Run();
		try {
			run.run(delegate);
		} catch (RuntimeException | Error thrown) {
			throw failed(run, thrown);
		}
		List<String> left = CommitSkippableCommand.collectReasonsForChangesLeft(delegate);
		if (!left.isEmpty()) {
			Throwable undoFailure = null;
			try {
				super.undo();
			} catch (RuntimeException | Error e) {
				logger.error("Could not undo '{}' after a command in it left changes it could not undo",
						getLabel(), e);
				undoFailure = e;
			}
			throw ChangesLeft.of(left, undoFailure);
		}
		if (CommitSkippableCommand.vouchesNothingChanged(delegate)) {
			throw new Declined();
		}
		if (rollBack && !CommitSkippableCommand.collectSkipReasons(delegate).isEmpty()) {
			try {
				super.undo();
			} catch (RuntimeException e) {
				logger.error("Could not undo '{}' after it declined partway; the model may be partly changed",
						getLabel(), e);
				throw new MutationException("This change stopped partway and what it had written could not be "
						+ "put back, so part of it may be in the model. Check the model in Archi before going on.", e);
			}
			throw new Declined();
		}
	}

	/**
	 * Puts back what {@code run} completed before {@code thrown}, ends the notifying compounds it opened, runs
	 * the failure callback, and builds what reaches the caller in place of what was thrown. Nothing done here
	 * after the put-back may replace that: a notice or callback that fails is logged and suppressed on it.
	 */
	private Failed failed(Run run, Throwable thrown) {
		Throwable putBackFailure = run.putBack();
		logger.error("'{}' failed unexpectedly as it ran", getLabel(), thrown);
		if (putBackFailure != null) {
			logger.error("Could not put back what ran before '{}' failed", getLabel(), putBackFailure);
			if (putBackFailure != thrown) {
				thrown.addSuppressed(putBackFailure);
			}
		}
		for (Throwable late : run.endOpened()) {
			logger.warn("Archi's end notice failed after '{}' failed", getLabel(), late);
			suppress(thrown, late);
		}
		if (onFailed != null) {
			try {
				onFailed.run();
			} catch (RuntimeException | Error late) {
				logger.warn("The failure callback failed after '{}' failed", getLabel(), late);
				suppress(thrown, late);
			}
		}
		return Failed.of(thrown, batchOf(delegate) != null ? run.batchAt : -1, putBackFailure == null);
	}

	private static void suppress(Throwable on, Throwable other) {
		if (other != on) {
			on.addSuppressed(other);
		}
	}

	/**
	 * Undoes the delegate, opening the same compounds a run opens, latest first, and carries on past a
	 * command whose undo fails. A failure then throws {@link StackStepFailed}, so the stack drops this entry
	 * from both its stacks, which leaves the model as the stack now believes it is: not applied.
	 */
	@Override
	public void undo() {
		Failures failures = new Failures();
		undoWalk(delegate, failures);
		if (failures.first != null) {
			logger.error("Undoing '{}' failed unexpectedly", getLabel(), failures.first);
			// A command that carries on past its own failures (recorded steps) throws one with the rest
			// suppressed on it: then more than the one failure was met, so the rest was not all reversed.
			throw new StackStepFailed(true, getLabel(),
					failures.count == 1 && failures.first.getSuppressed().length == 0, failures.first);
		}
	}

	/**
	 * Redoes the delegate as a run does, opening the same compounds. A command whose redo fails stops it:
	 * what was redone before it is undone, latest first, and {@link StackStepFailed} is thrown, so the stack
	 * drops this entry and the model is left not applied. The command that failed is not undone, as on a
	 * run, unless it puts itself back, as a refactoring's recorded steps do.
	 */
	@Override
	public void redo() {
		Run run = new Run(true);
		try {
			run.run(delegate);
		} catch (RuntimeException | Error thrown) {
			// Read before anything is suppressed on it here: a command that put itself back and failed doing
			// so (recorded steps) has already suppressed that failure on what it threw.
			boolean ownPutBackFailed = thrown.getSuppressed().length > 0;
			Throwable putBackFailure = run.putBack();
			for (Throwable late : run.endOpened()) {
				logger.warn("Archi's end notice failed after redoing '{}' failed", getLabel(), late);
				RecordedSteps.suppress(thrown, late);
			}
			RecordedSteps.suppress(thrown, putBackFailure);
			logger.error("Redoing '{}' failed unexpectedly", getLabel(), thrown);
			throw new StackStepFailed(false, getLabel(), putBackFailure == null && !ownPutBackFailed, thrown);
		}
	}

	/** The failures met on a walk that carries on: the first, an {@code Error} preferred, and how many. */
	private static final class Failures {
		private Throwable first;
		private int count;

		void add(Throwable failure) {
			count++;
			if (first == null) {
				first = failure;
			} else if (failure instanceof Error && !(first instanceof Error)) {
				RecordedSteps.suppress(failure, first);
				first = failure;
			} else {
				RecordedSteps.suppress(first, failure);
			}
		}
	}

	/** Whether a run opens {@code command} rather than running it as one command. */
	private static boolean opens(Command command) {
		Class<?> type = command.getClass();
		return type == CompoundCommand.class || type == NonNotifyingCompoundCommand.class
				|| type == TrackedCompoundCommand.class;
	}

	/**
	 * Undoes {@code command}, latest first, as its own {@code undo()} would, opening the compounds a run
	 * opens and carrying on past a command that fails. Archi's notifying compound gets its start notice
	 * before its commands and its end notice after them, whatever failed. Never throws: every failure,
	 * notices included, goes to {@code failures}.
	 */
	private static void undoWalk(Command command, Failures failures) {
		if (!opens(command)) {
			try {
				command.undo();
			} catch (RuntimeException | Error e) {
				failures.add(e);
			}
			return;
		}
		boolean notifying = command.getClass() == NonNotifyingCompoundCommand.class;
		if (notifying) {
			notice(command, IEditorModelManager.PROPERTY_ECORE_EVENTS_START, failures);
		}
		List<?> commands = ((CompoundCommand) command).getCommands();
		for (int i = commands.size() - 1; i >= 0; i--) {
			undoWalk((Command) commands.get(i), failures);
		}
		if (notifying) {
			notice(command, IEditorModelManager.PROPERTY_ECORE_EVENTS_END, failures);
		}
	}

	private static void notice(Command source, String property, Failures failures) {
		try {
			notices.fire(source, property);
		} catch (RuntimeException | Error e) {
			failures.add(e);
		}
	}

	/** The batch compound, held directly or as the single child of Archi's notifying compound; or null. */
	private static TrackedCompoundCommand batchOf(Command command) {
		if (command instanceof TrackedCompoundCommand batch) {
			return batch;
		}
		if (command instanceof CompoundCommand compound && compound.size() == 1
				&& compound.getCommands().get(0) instanceof TrackedCompoundCommand batch) {
			return batch;
		}
		return null;
	}

	/**
	 * One run of a delegate that records how far it got, so that a command that throws leaves nothing that
	 * completed before it.
	 *
	 * <p>A compound whose class is exactly GEF's {@link CompoundCommand}, Archi's
	 * {@link NonNotifyingCompoundCommand} or {@link TrackedCompoundCommand} is opened: its commands run in
	 * order, as its own {@code execute()} would run them. For Archi's notifying compound the run fires the
	 * same start and end notices, from the compound itself, before and after its commands, as Archi's
	 * {@code execute()} does. Archi fires the end notice only when the commands return, so a throw would
	 * leave its model views buffering every later change; the run owes that notice, and pays it after the
	 * put-back. Any other command, a compound subclass with a run of its own included, runs through its own
	 * {@code execute()} and counts as one command: what ran inside it before it threw cannot be seen.</p>
	 *
	 * <p>A run that completes has done exactly what {@code CompoundCommand.execute()} does. A redo is the same
	 * run with each command's {@code redo()} in place of its {@code execute()}, which is what
	 * {@code CompoundCommand.redo()} does. The entry's undo walks the same compounds the other way
	 * ({@link #undoWalk}), which is what {@code CompoundCommand.undo()} does while nothing fails.</p>
	 */
	private static final class Run {

		/** Whether each command is redone rather than executed. */
		private final boolean redo;

		/** What completed, in order: commands, and opened compounds whose commands all completed. */
		private final List<Command> completed = new ArrayList<>();

		/** The notifying compounds opened whose end notice is still owed, innermost first. */
		private final Deque<Command> opened = new ArrayDeque<>();

		/** The index in the batch of the command running when the run stopped, or -1. */
		private int batchAt = -1;

		Run() {
			this(false);
		}

		Run(boolean redo) {
			this.redo = redo;
		}

		void run(Command command) {
			Class<?> type = command.getClass();
			if (!opens(command)) {
				if (redo) {
					command.redo();
				} else {
					command.execute();
				}
				completed.add(command);
				return;
			}
			int first = completed.size();
			boolean notifying = type == NonNotifyingCompoundCommand.class;
			if (notifying) {
				notices.fire(command, IEditorModelManager.PROPERTY_ECORE_EVENTS_START);
				opened.push(command);
			}
			List<?> commands = ((CompoundCommand) command).getCommands();
			for (int i = 0; i < commands.size(); i++) {
				if (type == TrackedCompoundCommand.class) {
					batchAt = i;
				}
				run((Command) commands.get(i));
			}
			if (notifying) {
				opened.pop();
				notices.fire(command, IEditorModelManager.PROPERTY_ECORE_EVENTS_END);
			}
			completed.subList(first, completed.size()).clear();
			completed.add(command);
		}

		/**
		 * Undoes what completed, latest first, carrying on past an undo that fails, inside a completed
		 * compound too ({@link #undoWalk}).
		 *
		 * @return the first failure, an {@code Error} preferred, with every other one suppressed on it, or
		 *         null when all was undone
		 */
		Throwable putBack() {
			Failures failures = new Failures();
			for (int i = completed.size() - 1; i >= 0; i--) {
				undoWalk(completed.get(i), failures);
			}
			completed.clear();
			return failures.first;
		}

		/** Fires the end notice owed to each opened notifying compound, innermost first; returns the failures. */
		List<Throwable> endOpened() {
			List<Throwable> failures = new ArrayList<>();
			while (!opened.isEmpty()) {
				try {
					notices.fire(opened.pop(), IEditorModelManager.PROPERTY_ECORE_EVENTS_END);
				} catch (RuntimeException | Error e) {
					failures.add(e);
				}
			}
			return failures;
		}
	}

	/**
	 * Returns the delegate's label so undo/redo menus and MCP tool labels are unchanged
	 * (read dynamically rather than relying on the label captured at construction).
	 */
	@Override
	public String getLabel() {
		return delegate.getLabel();
	}

	/**
	 * Thrown out of {@link #execute()} when a command left changes it could not undo: the rest of the entry
	 * has been put back and nothing is pushed, since no entry could undo what is left. Reaches the caller.
	 */
	static final class ChangesLeft extends MutationException {
		private static final long serialVersionUID = 1L;

		private static final String LEFT = "What it had written could not all be put back, so part of it may "
				+ "still be in the model, and " + UNDO_WILL_NOT;

		private final List<String> reasons;
		private final boolean restPutBack;

		private ChangesLeft(String said, List<String> reasons, boolean restPutBack, Throwable cause) {
			super(said + " " + LEFT, cause);
			this.reasons = List.copyOf(reasons);
			this.restPutBack = restPutBack;
		}

		/** For an entry: the commands' reasons, and whether putting back the rest of it failed too. */
		static ChangesLeft of(List<String> reasons, Throwable undoFailure) {
			String said = sentence(String.join("; ", reasons));
			if (undoFailure != null) {
				said += " Putting back the rest of this change also failed, so more of it may be in the model too.";
			}
			return new ChangesLeft(said, reasons, undoFailure == null, undoFailure);
		}

		/** The same failure at a batch commit, naming each operation by its queued description. */
		ChangesLeft inBatch(List<String> described) {
			String said = sentence(String.join("; ", described)) + (restPutBack
					? " Every other operation in the batch was put back, and the batch has ended."
					: " Putting back the other operations in the batch also failed, so some of them may be in the "
							+ "model too, and the batch has ended.");
			return new ChangesLeft(said, reasons, restPutBack, this);
		}

		/** This failure as a single call's refusal, which must not say that nothing was changed. */
		ModelAccessException refusal() {
			return new ModelAccessException(getMessage(), ErrorCode.MUTATION_FAILED, null, MAY_STILL_BE, null);
		}

		/** The skip reasons of the commands that left changes, in execution order. */
		List<String> getReasons() {
			return reasons;
		}

		private static String sentence(String text) {
			return text.endsWith(".") ? text : text + ".";
		}
	}

	/**
	 * Thrown out of {@link #execute()} in place of what a command threw as it ran, which is its cause: the
	 * batch commands that completed before it have been put back and nothing is pushed. Reaches the caller.
	 * Never carries the cause's message, which is internal.
	 */
	static final class Failed extends MutationException {
		private static final long serialVersionUID = 1L;

		private static final String HEDGE = "Part of what it wrote may still be in the model, and " + UNDO_WILL_NOT;

		private final int failedAt;
		private final boolean restPutBack;

		private Failed(String said, int failedAt, boolean restPutBack, Throwable cause) {
			super(said + " " + HEDGE, cause);
			this.failedAt = failedAt;
			this.restPutBack = restPutBack;
		}

		/** For an entry: what was thrown, where in a batch, and whether what ran before it was put back. */
		static Failed of(Throwable thrown, int failedAt, boolean restPutBack) {
			return new Failed(restPutBack ? "This change failed unexpectedly."
					: "This change failed unexpectedly, and putting back what ran before the failure also failed, "
							+ "so more of it may be in the model too.", failedAt, restPutBack, thrown);
		}

		/** The same failure at a batch commit, naming the operation that failed by its queued description. */
		Failed inBatch(String described) {
			return new Failed(described + " failed unexpectedly." + (restPutBack
					? " Every operation queued before it was put back, and the batch has ended."
					: " Putting back the operations queued before it also failed, so some of them may be in the "
							+ "model too, and the batch has ended."), failedAt, restPutBack, this);
		}

		/** The index in the batch of the command that threw, or -1 outside a batch. */
		int getFailedAt() {
			return failedAt;
		}

		/** Never a retry: part of the change may be in the model, and a retry would apply it again. */
		@Override
		public String getSuggestedCorrection() {
			return MAY_STILL_BE;
		}
	}

	/**
	 * Thrown out of {@link #undo()} or {@link #redo()} when a command in the entry failed, in place of what it
	 * threw, which is its cause. The stack has dropped the entry from both its stacks, so it can no longer be
	 * undone or redone. Reaches the caller, naming the entries the same call undid or redid before it, which
	 * the loops that walk the stack add as the failure passes them. Never carries the cause's message.
	 */
	static final class StackStepFailed extends MutationException {
		private static final long serialVersionUID = 1L;

		private final boolean undo;
		private final String label;
		private final boolean restReversed;
		private final List<String> doneBefore = new ArrayList<>();

		StackStepFailed(boolean undo, String label, boolean restReversed, Throwable cause) {
			super(null, cause);
			this.undo = undo;
			this.label = label;
			this.restReversed = restReversed;
		}

		/** Adds {@code labels}, the entries undone or redone before the ones already named, and returns this. */
		StackStepFailed after(List<String> labels) {
			doneBefore.addAll(0, labels);
			return this;
		}

		/** The entries the call undid or redid before the one that failed, in order. */
		List<String> getDoneBefore() {
			return List.copyOf(doneBefore);
		}

		@Override
		public String getMessage() {
			String done = doneBefore.isEmpty() ? ""
					: (undo ? "Undid " : "Redid ") + "'" + String.join("', '", doneBefore) + "'. ";
			String rest = undo
					? (restReversed ? "Everything else in it was reversed, but part of it may still be in the model."
							: "Reversing the rest of it also failed, so more of it may still be in the model.")
					: (restReversed ? "What it had redone was put back, but part of it may still be in the model."
							: "Putting back what it had redone also failed, so more of it may be in the model.");
			return done + (undo ? "Undoing '" : "Redoing '") + label + "' failed unexpectedly, and it is no "
					+ "longer on Undo or Redo. " + rest;
		}

		/** Never a retry: the entry is gone from the history, and part of it may be in the model. */
		@Override
		public String getSuggestedCorrection() {
			return MAY_STILL_BE;
		}
	}

	/**
	 * Thrown out of {@link #execute()} so the stack does not record a dispatch that changed nothing.
	 * Caught by the dispatcher around {@code stack.execute}; never reaches a caller.
	 */
	static final class Declined extends RuntimeException {
		private static final long serialVersionUID = 1L;

		Declined() {
			super(null, null, false, false);
		}
	}
}
