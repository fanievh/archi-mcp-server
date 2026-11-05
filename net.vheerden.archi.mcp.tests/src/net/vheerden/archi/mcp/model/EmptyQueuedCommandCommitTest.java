package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModelArchimateObject;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.BatchSummaryDto;

/**
 * Pins that a batch commit runs what the batch queued, or refuses with the batch still open; it never
 * reports success for a commit that ran nothing.
 *
 * <p>The commit executes the whole queue as one compound, and GEF's command stack silently skips a
 * compound that cannot execute: a compound with no commands in it, or one holding any command that
 * cannot execute. A single empty operation in the queue therefore dropped every other operation of the
 * batch while the commit still answered {@code rolledBack: false}. An operation with nothing to change
 * is now queued as a command that changes nothing, so the rest of the batch runs; and a queued command
 * that still cannot execute stops the commit before anything runs, naming the operation.</p>
 *
 * <p>Headless: a real GEF {@link CommandStack}, queued compounds rebuilt into plain GEF compounds.</p>
 */
public class EmptyQueuedCommandCommitTest {

    private static final String SESSION = "empty-queued-command-session";

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private IArchimateModel model;
    private IDiagramModelArchimateObject a;
    private MutationDispatcher dispatcher;
    private ArchiModelAccessorImpl accessor;
    private CommandStack stack;

    @Before
    public void setUp() {
        model = factory.createArchimateModel();
        model.setId("model-empty-queued-command");
        model.setDefaults();
        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        IArchimateElement el = factory.createApplicationComponent();
        el.setId("el-a");
        model.getFolder(FolderType.APPLICATION).getElements().add(el);
        a = factory.createDiagramModelArchimateObject();
        a.setId("vo-a");
        a.setArchimateElement(el);
        a.setBounds(400, 0, 120, 60);
        view.getChildren().add(a);

        AutoRouteArmFixture.StubEditorModelManager mgr = new AutoRouteArmFixture.StubEditorModelManager();
        mgr.setModels(List.of(model));
        stack = new CommandStack();
        model.setAdapter(CommandStack.class, stack);
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                MutationDispatcher.executeEntry(stack, agentEntry(toPlainCompound(command)));
            }
            @Override
            protected void dispatchCommand(Command command) {
                MutationDispatcher.executeEntry(stack, agentEntry(toPlainCompound(command)));
            }
        };
        dispatcher.setApprovalModeProvider(() -> false);
        accessor = new ArchiModelAccessorImpl(mgr, dispatcher);
        dispatcher.onModelActive(model);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ---- an operation with nothing to change ---------------------------------------------------------

    @Test
    public void commit_shouldRunTheRestOfTheBatch_whenAnOperationWithNothingToChangeIsQueued() throws Exception {
        dispatcher.beginBatch(SESSION, "move, then nothing");
        move();
        int seq = dispatcher.queueForBatch(SESSION, new CompoundCommand("nothing"), "Nothing to change");

        assertEquals("the empty operation keeps the sequence number the caller was told", 2, seq);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals("the move ran", 10, a.getBounds().getX());
        assertFalse(summary.rolledBack());
        assertEquals(2, summary.operationCount());
        assertEquals(List.of("Update view object bounds: vo-a", "Nothing to change"), summary.descriptions());
        assertNull("nothing declined", summary.skippedOperations());
        assertNull("something was applied", summary.nothingApplied());
        assertTrue("the batch is one undo entry", stack.canUndo());
    }

    @Test
    public void commit_shouldRunTheRestOfTheBatch_whenTheEmptyOperationIsQueuedFirst() throws Exception {
        dispatcher.beginBatch(SESSION, "nothing, then move");
        dispatcher.queueForBatch(SESSION, new CompoundCommand("nothing"), "Nothing to change");
        move();

        dispatcher.endBatch(SESSION, true);

        assertEquals(10, a.getBounds().getX());
    }

    @Test
    public void commit_shouldRunTheRestOfTheBatch_whenAnOperationHoldsOnlyEmptyCompounds() throws Exception {
        CompoundCommand nested = new CompoundCommand("outer");
        nested.add(new CompoundCommand("inner"));
        dispatcher.beginBatch(SESSION, "move, then nested nothing");
        move();
        dispatcher.queueForBatch(SESSION, nested, "Nested nothing");

        dispatcher.endBatch(SESSION, true);

        assertEquals(10, a.getBounds().getX());
    }

    @Test
    public void commit_shouldUndoAndRedoTheBatch_whenItHeldAnOperationWithNothingToChange() throws Exception {
        dispatcher.beginBatch(SESSION, "move, then nothing");
        move();
        dispatcher.queueForBatch(SESSION, new CompoundCommand("nothing"), "Nothing to change");
        dispatcher.endBatch(SESSION, true);

        stack.undo();
        assertEquals("undo puts the move back", 400, a.getBounds().getX());
        stack.redo();
        assertEquals("redo replays it", 10, a.getBounds().getX());
    }

    @Test
    public void commit_shouldSayNothingWasApplied_andLeaveNoUndoEntry_whenEveryOperationHadNothingToChange()
            throws Exception {
        dispatcher.beginBatch(SESSION, "nothing at all");
        dispatcher.queueForBatch(SESSION, new CompoundCommand("nothing"), "Nothing to change");
        dispatcher.queueForBatch(SESSION, new CompoundCommand("nothing either"), "Nothing to change either");

        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals(Boolean.TRUE, summary.nothingApplied());
        assertFalse(summary.rolledBack());
        assertEquals(2, summary.operationCount());
        assertNull(summary.skippedOperations());
        assertFalse("no empty undo entry", stack.canUndo());
    }

    @Test
    public void queue_shouldKeepAnOperationThatChangesSomething_asItWasQueued() {
        CompoundCommand real = new CompoundCommand("real");
        real.add(new Command("leaf") { });
        dispatcher.beginBatch(SESSION, "one real");
        dispatcher.queueForBatch(SESSION, real, "Real");

        assertEquals("the one leaf is still the queued work", 1, dispatcher.queuedLeafCommands(SESSION).size());
        assertTrue(dispatcher.queuedLeafCommands(SESSION).get(0).getLabel().equals("leaf"));
    }

    // ---- an operation that cannot execute --------------------------------------------------------------

    @Test
    public void commit_shouldRefuseWithTheBatchStillOpen_whenAQueuedOperationCannotExecute() throws Exception {
        dispatcher.beginBatch(SESSION, "move, then something that cannot run");
        move();
        dispatcher.queueForBatch(SESSION, new Command("cannot run") {
            @Override
            public boolean canExecute() {
                return false;
            }
        }, "Something that cannot run");

        try {
            dispatcher.endBatch(SESSION, true);
            fail("a commit that would run nothing must not report success");
        } catch (ModelAccessException refusal) {
            assertEquals(ErrorCode.MUTATION_FAILED, refusal.getErrorCode());
            assertTrue(refusal.getMessage(), refusal.getMessage().contains(
                    "operation 2 (Something that cannot run) cannot be executed"));
            assertTrue(refusal.getMessage(), refusal.getMessage().contains("Nothing was applied"));
            assertTrue(refusal.getSuggestedCorrection(),
                    refusal.getSuggestedCorrection().contains("end-batch rollback=true"));
        }

        assertEquals("nothing ran", 400, a.getBounds().getX());
        assertFalse(stack.canUndo());
        assertEquals("the batch is still open", OperationalMode.BATCH, dispatcher.getMode(SESSION));
        assertEquals("the queue is intact", 2, (int) dispatcher.getBatchStatus(SESSION).queuedCount());
        BatchSummaryDto discarded = dispatcher.endBatch(SESSION, false);
        assertTrue("it can be discarded", discarded.rolledBack());
    }

    @Test
    public void commit_shouldBlameNoOperation_whenEachCanRunButTheBatchAsBuiltCannot() throws Exception {
        dispatcher.beginBatch(SESSION, "move, then a command that cannot run the first time it is asked");
        move();
        dispatcher.queueForBatch(SESSION, new Command("fickle") {
            private int asked;

            @Override
            public boolean canExecute() {
                return asked++ > 0; // refuses the commit's own check, answers yes when asked again
            }
        }, "Fickle");

        try {
            dispatcher.endBatch(SESSION, true);
            fail("a commit that would run nothing must not report success");
        } catch (ModelAccessException refusal) {
            assertEquals(ErrorCode.MUTATION_FAILED, refusal.getErrorCode());
            assertTrue(refusal.getMessage(), refusal.getMessage().contains(
                    "the batch cannot be executed as built, though each operation can on its own"));
            assertFalse(refusal.getMessage(), refusal.getMessage().contains("operation 2"));
            assertFalse(refusal.getSuggestedCorrection(), refusal.getSuggestedCorrection().contains("without that one"));
        }
        assertEquals("nothing ran", 400, a.getBounds().getX());
        assertTrue("it can be discarded", dispatcher.endBatch(SESSION, false).rolledBack());
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    private void move() {
        accessor.updateViewObject(SESSION, "vo-a", 10, 10, null, null, null, null, null, null, null, null, null,
                null);
    }

    private static Command toPlainCompound(Command command) {
        if (command instanceof RequireAttachedContainerCommand guard) {
            return guard.withGuarded(toPlainCompound(guard.getGuarded()));
        }
        if (command instanceof CompoundCommand compound) {
            CompoundCommand plain = new CompoundCommand(compound.getLabel());
            for (Object child : compound.getCommands()) {
                plain.add(toPlainCompound((Command) child));
            }
            return plain;
        }
        return command;
    }
}
