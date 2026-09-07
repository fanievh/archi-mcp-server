package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.junit.Test;

/**
 * Headless tests for the {@link AgentAuthoredCommand} origin tag /
 * {@link AgentAuthoredCompoundCommand} wrapper (origin tagging) and the scoped-undo/redo "betrayal" guard core
 * ({@link MutationDispatcher#scopedUndo}/{@link MutationDispatcher#scopedRedo}).
 *
 * <p>All cases run against a <strong>real</strong> {@link CommandStack}, which is instantiable with
 * no {@code Display} — only {@code MutationDispatcher.dispatchOnUiThread} needs one, which is why the
 * decision logic is extracted into the pure static {@code scopedUndo/scopedRedo} this test exercises.</p>
 */
public class AgentAuthoredCommandTest {

	// ---- The wrapper ----

	@Test
	public void shouldBeInstanceOfMarker_whenWrapped() {
		RecordingCommand delegate = new RecordingCommand("Create Element: MyActor");
		AgentAuthoredCompoundCommand wrapped = new AgentAuthoredCompoundCommand(delegate);
		assertTrue("wrapper must be an AgentAuthoredCommand",
				wrapped instanceof AgentAuthoredCommand);
	}

	@Test
	public void shouldNotBeInstanceOfMarker_whenPlainCommand() {
		Command plain = new RecordingCommand("Human edit");
		assertFalse("a plain command is not agent-authored",
				plain instanceof AgentAuthoredCommand);
	}

	@Test
	public void shouldReturnDelegateLabel_fromGetLabel() {
		RecordingCommand delegate = new RecordingCommand("Apply Positions");
		AgentAuthoredCompoundCommand wrapped = new AgentAuthoredCompoundCommand(delegate);
		assertEquals("Apply Positions", wrapped.getLabel());
	}

	@Test
	public void shouldExposeDelegate() {
		RecordingCommand delegate = new RecordingCommand("X");
		AgentAuthoredCompoundCommand wrapped = new AgentAuthoredCompoundCommand(delegate);
		assertSame(delegate, wrapped.getDelegate());
	}

	@Test
	public void shouldInvokeDelegate_onExecuteUndoRedo() {
		RecordingCommand delegate = new RecordingCommand("X");
		AgentAuthoredCompoundCommand wrapped = new AgentAuthoredCompoundCommand(delegate);

		wrapped.execute();
		assertEquals("execute delegates to child", 1, delegate.executeCount);

		wrapped.undo();
		assertEquals("undo delegates to child", 1, delegate.undoCount);

		wrapped.redo();
		assertEquals("redo delegates to child", 1, delegate.redoCount);
	}

	@Test
	public void shouldDelegateCanUndo_whenDelegateCannotUndo() {
		// The wrapper must behave identically to the delegate — including canUndo().
		// A plain CompoundCommand with one child returns false iff the child returns false.
		RecordingCommand blocking = new RecordingCommand("X");
		blocking.canUndo = false;
		AgentAuthoredCompoundCommand wrapped = new AgentAuthoredCompoundCommand(blocking);
		assertFalse("wrapper.canUndo() yields to the delegate's false", wrapped.canUndo());

		RecordingCommand ok = new RecordingCommand("Y");
		assertTrue("wrapper.canUndo() is true when the delegate allows it",
				new AgentAuthoredCompoundCommand(ok).canUndo());
	}

	// ---- Scoped undo ----

	@Test
	public void shouldRefuseWithBlockReason_whenUndoTopIsHuman() {
		CommandStack stack = new CommandStack();
		stack.execute(agent("Agent op"));
		stack.execute(human("Human box"));      // human is on top

		MutationDispatcher.UndoRedoState state = MutationDispatcher.scopedUndo(stack, 1);

		assertTrue("zero undos performed", state.labels().isEmpty());
		assertEquals("betrayal block reason set",
				MutationDispatcher.BLOCK_REASON_HUMAN_EDIT, state.blockedReason());
		assertTrue("human entry survives — still undoable natively", state.canUndo());
	}

	@Test
	public void shouldStopAtFirstHumanBoundary_whenMultiStepUndo() {
		CommandStack stack = new CommandStack();
		stack.execute(human("Human base"));     // deepest
		stack.execute(agent("Agent A"));
		stack.execute(agent("Agent B"));         // top

		// steps=5 but only the two agent entries above the human may be undone.
		MutationDispatcher.UndoRedoState state = MutationDispatcher.scopedUndo(stack, 5);

		assertEquals("undo both agent entries, stop at the human",
				java.util.List.of("Agent B", "Agent A"), state.labels());
		assertNull("partial progress made — no block reason", state.blockedReason());
		assertTrue("the human entry remains on the stack", state.canUndo());
	}

	@Test
	public void shouldUndoFully_whenAllAgentAuthored() {
		CommandStack stack = new CommandStack();
		stack.execute(agent("Agent A"));
		stack.execute(agent("Agent B"));
		stack.execute(agent("Agent C"));

		MutationDispatcher.UndoRedoState state = MutationDispatcher.scopedUndo(stack, 3);

		assertEquals(java.util.List.of("Agent C", "Agent B", "Agent A"), state.labels());
		assertNull(state.blockedReason());
		assertFalse("stack fully unwound", state.canUndo());
		assertTrue(state.canRedo());
	}

	@Test
	public void shouldReturnNoBlockReason_whenUndoStackEmpty() {
		CommandStack stack = new CommandStack();

		MutationDispatcher.UndoRedoState state = MutationDispatcher.scopedUndo(stack, 1);

		assertTrue("nothing undone", state.labels().isEmpty());
		assertNull("empty stack is distinct from human-blocked — no block reason",
				state.blockedReason());
		assertFalse(state.canUndo());
	}

	@Test
	public void shouldStopAtHuman_evenWhenMoreAgentEntriesBelow() {
		// Agent (deep) · Human (mid) · Agent (top): undoing the top agent then hitting the human
		// must NOT reach the deeper agent entry — never cross a human edit.
		CommandStack stack = new CommandStack();
		stack.execute(agent("Agent deep"));
		stack.execute(human("Human mid"));
		stack.execute(agent("Agent top"));

		MutationDispatcher.UndoRedoState state = MutationDispatcher.scopedUndo(stack, 3);

		assertEquals(java.util.List.of("Agent top"), state.labels());
		assertNull(state.blockedReason());
	}

	// ---- Scoped redo (symmetric) ----

	@Test
	public void shouldRefuseWithBlockReason_whenRedoTopIsHuman() {
		CommandStack stack = new CommandStack();
		stack.execute(human("Human box"));
		stack.undo();                            // native undo → human sits on top of redo

		MutationDispatcher.UndoRedoState state = MutationDispatcher.scopedRedo(stack, 1);

		assertTrue("zero redos performed", state.labels().isEmpty());
		assertEquals(MutationDispatcher.BLOCK_REASON_HUMAN_EDIT, state.blockedReason());
		assertTrue("human change still redoable natively", state.canRedo());
	}

	@Test
	public void shouldRedoFully_whenAllAgentAuthored() {
		CommandStack stack = new CommandStack();
		stack.execute(agent("Agent A"));
		stack.execute(agent("Agent B"));
		stack.undo();
		stack.undo();                            // redo stack (java.util.Stack): A on top (last undone), B below

		MutationDispatcher.UndoRedoState state = MutationDispatcher.scopedRedo(stack, 2);

		assertEquals(java.util.List.of("Agent A", "Agent B"), state.labels());
		assertNull(state.blockedReason());
		assertFalse(state.canRedo());
	}

	@Test
	public void shouldStopAtFirstHumanBoundary_whenMultiStepRedo() {
		// Execute A1(agent), H(human), A2(agent); undo all three → redo stack has A1 on top
		// (last undone), then H, then A2 at the bottom. So redo gives A1 first, then hits H.
		CommandStack stack = new CommandStack();
		stack.execute(agent("Agent A1"));
		stack.execute(human("Human H"));
		stack.execute(agent("Agent A2"));
		stack.undo();
		stack.undo();
		stack.undo();

		MutationDispatcher.UndoRedoState state = MutationDispatcher.scopedRedo(stack, 3);

		assertEquals("redo the top agent entry, stop at the human",
				java.util.List.of("Agent A1"), state.labels());
		assertNull("partial progress — no block reason", state.blockedReason());
		assertTrue("the human redo entry remains", state.canRedo());
	}

	@Test
	public void shouldReturnNoBlockReason_whenRedoStackEmpty() {
		CommandStack stack = new CommandStack();

		MutationDispatcher.UndoRedoState state = MutationDispatcher.scopedRedo(stack, 1);

		assertTrue(state.labels().isEmpty());
		assertNull(state.blockedReason());
		assertFalse(state.canRedo());
	}

	// ---- steps=0 boundary (handler clamps to >=1, but the core must be sane directly) ----

	@Test
	public void shouldNoOp_whenStepsZero() {
		CommandStack stack = new CommandStack();
		stack.execute(agent("Agent A"));     // an agent entry is available to undo

		MutationDispatcher.UndoRedoState undo = MutationDispatcher.scopedUndo(stack, 0);
		assertTrue("zero steps undoes nothing", undo.labels().isEmpty());
		assertNull("no progress is not a human block", undo.blockedReason());
		assertTrue("the entry is left intact", undo.canUndo());

		MutationDispatcher.UndoRedoState redo = MutationDispatcher.scopedRedo(stack, 0);
		assertTrue(redo.labels().isEmpty());
		assertNull(redo.blockedReason());
	}

	// ---- helpers ----

	/** An agent-authored stack entry: a recording command wrapped at admission. */
	private static AgentAuthoredCompoundCommand agent(String label) {
		return new AgentAuthoredCompoundCommand(new RecordingCommand(label));
	}

	/** A human stack entry: a plain (untagged) command, as Archi's GUI would push. */
	private static Command human(String label) {
		return new RecordingCommand(label);
	}

	/** A no-op GEF command that records execute/undo/redo invocations. */
	private static final class RecordingCommand extends Command {
		int executeCount;
		int undoCount;
		int redoCount;
		boolean canUndo = true;

		RecordingCommand(String label) {
			super(label);
		}

		@Override
		public boolean canUndo() {
			return canUndo;
		}

		@Override
		public void execute() {
			executeCount++;
		}

		@Override
		public void undo() {
			undoCount++;
		}

		@Override
		public void redo() {
			redoCount++;
		}
	}
}
