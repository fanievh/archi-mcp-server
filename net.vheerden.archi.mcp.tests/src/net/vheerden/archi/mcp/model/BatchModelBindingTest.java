package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IIdentifier;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import net.vheerden.archi.mcp.handlers.HandlerRegistrar;
import net.vheerden.archi.mcp.handlers.SearchHandler;
import net.vheerden.archi.mcp.model.MutationDispatcher.UndoRedoState;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.session.SessionManager;
import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.BatchStatusDto;
import net.vheerden.archi.mcp.response.dto.BatchSummaryDto;
import net.vheerden.archi.mcp.response.dto.ProposalDto;

/**
 * Pins that a batch, and a proposal, are bound to the model that was active when they were made,
 * and that work bound to one model is refused rather than dispatched once another is active.
 *
 * <h2>The defect these tests guard</h2>
 *
 * <p>A queued command holds EMF references into the model it was <em>prepared</em> against, while
 * dispatch takes the {@code CommandStack} of whichever model is active at <em>commit</em>. With two
 * models open and the human opening the second mid-batch (which makes it active, last-opened-wins),
 * the commit used to mutate the first model and record the undo entry on the second model's stack.</p>
 *
 * <p><strong>Assert the stack, not the mutation.</strong> The write always lands in the prepared
 * model, before and after the fix alike, so "the element was created in A" cannot tell them apart.
 * What moves is which {@code CommandStack} holds the entry — so every test here reads both stacks.</p>
 *
 * <h2>Fixture</h2>
 *
 * <p>Two real models, each carrying its own {@code CommandStack} adapter, one session, and a
 * dispatcher whose dispatch resolves the stack exactly as production does: from the model the
 * accessor reports as active <em>at dispatch time</em>. The accessor's active-model field is read
 * reflectively because it is the very supplier production hands the dispatcher. Queued compounds
 * are rebuilt as plain GEF compounds, as in the other batch suites, because the production
 * {@code NonNotifyingCompoundCommand} needs the editor's model manager to execute.</p>
 */
public class BatchModelBindingTest {

    private static final String SESSION = "batch-model-binding-session";

    private IArchimateFactory factory;
    private StubEditorModelManager models;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private IArchimateModel modelA;
    private IArchimateModel modelB;
    private CommandStack stackA;
    private CommandStack stackB;
    private boolean approvalMode;
    /** Runs once at the start of the next dispatch, then clears itself. */
    private Runnable beforeDispatch;

    @Before
    public void setUp() throws Exception {
        factory = IArchimateFactory.eINSTANCE;
        modelA = newModel("model-a", "Model A");
        modelB = newModel("model-b", "Model B");
        stackA = (CommandStack) modelA.getAdapter(CommandStack.class);
        stackB = (CommandStack) modelB.getAdapter(CommandStack.class);

        models = new StubEditorModelManager();
        models.setModels(new ArrayList<>(List.of(modelA)));

        Field active = ArchiModelAccessorImpl.class.getDeclaredField("activeModel");
        active.setAccessible(true);
        dispatcher = new MutationDispatcher(() -> activeOf(active)) {
            @Override
            public void dispatchImmediate(Command command) {
                dispatchOnActiveStack(command);
            }
            @Override
            protected void dispatchCommand(Command command) {
                dispatchOnActiveStack(command);
            }
            @Override
            public UndoRedoState undo(int steps) {
                return stackOperation(steps, true).get();   // production's resolver, minus the UI hop
            }
            @Override
            public UndoRedoState redo(int steps) {
                return stackOperation(steps, false).get();
            }
        };
        approvalMode = false;
        dispatcher.setApprovalModeProvider(() -> approvalMode);
        accessor = new ArchiModelAccessorImpl(models, dispatcher);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ---- fixture helpers ----------------------------------------------------------------------

    private IArchimateModel newModel(String id, String name) {
        IArchimateModel model = factory.createArchimateModel();
        model.setId(id);
        model.setName(name);
        model.setDefaults();
        model.setAdapter(CommandStack.class, new CommandStack());
        return model;
    }

    private IArchimateModel activeOf(Field active) {
        try {
            return accessor == null ? null : (IArchimateModel) active.get(accessor);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * What production does: execute on the CommandStack of the model active right now, resolved
     * through the dispatcher's own guarded lookup so a bound dispatch is re-checked at that moment.
     */
    private void dispatchOnActiveStack(Command command) {
        Runnable hook = beforeDispatch;
        beforeDispatch = null;   // one-shot
        if (hook != null) {
            hook.run();   // the human acts in Archi after the call was prepared, before it dispatches
        }
        IArchimateModel target = activeModelInstance();
        assertNotNull("dispatch reached with no active model", target);
        CommandStack stack = dispatcher.guardedStack(target, dispatcher.currentDispatchGuard());
        stack.execute(dispatcher.agentEntry(toPlainCompound(command)));
    }

    private IArchimateModel activeModelInstance() {
        try {
            Field active = ArchiModelAccessorImpl.class.getDeclaredField("activeModel");
            active.setAccessible(true);
            return (IArchimateModel) active.get(accessor);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Command toPlainCompound(Command command) {
        if (command instanceof CompoundCommand compound) {
            CompoundCommand plain = new CompoundCommand(compound.getLabel());
            for (Object child : compound.getCommands()) {
                plain.add(toPlainCompound((Command) child));
            }
            return plain;
        }
        return command;
    }

    /** The human opens a model in Archi: it joins the open set and becomes active. */
    private void open(IArchimateModel model) {
        if (!models.getModels().contains(model)) {
            models.getModels().add(model);
        }
        models.firePropertyChange(IEditorModelManager.PROPERTY_MODEL_OPENED, null, model);
    }

    /**
     * The human makes a new model with File &gt; New, announced as Archi announces it: the model is
     * registered (joins the open set, its stack already made) and then "created" fires as
     * (null, model) — never "opened" (EditorModelManager.createNewModel → registerModel).
     */
    private void create(IArchimateModel model) {
        if (!models.getModels().contains(model)) {
            models.getModels().add(model);
        }
        models.firePropertyChange(IEditorModelManager.PROPERTY_MODEL_CREATED, null, model);
    }

    /**
     * The human closes a model in Archi, announced as Archi announces it: the model is the event's
     * NEW value and the old value is null (EditorModelManager.closeModel).
     */
    private void close(IArchimateModel model) {
        models.getModels().remove(model);
        models.firePropertyChange(IEditorModelManager.PROPERTY_MODEL_REMOVED, null, model);
    }

    private String createActor(String name) {
        return accessor.createElement(SESSION, "BusinessActor", name, null, null, null, null)
                .entity().id();
    }

    private static boolean holdsElement(IArchimateModel model, String id) {
        for (Object each : model.getFolder(FolderType.BUSINESS).getElements()) {
            if (each instanceof IIdentifier identified && id.equals(identified.getId())) {
                return true;
            }
        }
        return false;
    }

    private static int entries(CommandStack stack) {
        return stack.getCommands().length;
    }

    private ModelAccessException commitRefusal() {
        try {
            dispatcher.endBatch(SESSION, true);
        } catch (ModelAccessException e) {
            return e;
        }
        fail("commit was expected to be refused");
        return null;
    }

    // ---- binding ------------------------------------------------------------------------------

    @Test
    public void shouldBindTheBatchToTheActiveModel_whenTheBatchBegins() {
        dispatcher.beginBatch(SESSION, "work", null);

        BatchStatusDto status = dispatcher.getBatchStatus(SESSION);
        assertEquals("model-a", status.boundModelId());
        assertEquals("Model A", status.boundModelName());
    }

    @Test
    public void shouldHoldNoModelReference_inTheBatchContext() {
        for (Field field : MutationContext.class.getDeclaredFields()) {
            assertFalse("MutationContext." + field.getName() + " holds a model object; the binding "
                    + "must be the id, because a reopened model is a new instance with the same id",
                    IArchimateModel.class.isAssignableFrom(field.getType()));
        }
    }

    @Test
    public void shouldKeepTheStampResolvable_whenTheBoundModelIsClosedAndReopenedWithNothingQueued() {
        dispatcher.beginBatch(SESSION, "work", null);
        close(modelA);
        IArchimateModel reopened = newModel("model-a", "Model A");
        open(reopened);

        assertEquals("model-a", dispatcher.getBatchStatus(SESSION).boundModelId());
        String id = createActor("After Reopen");
        dispatcher.endBatch(SESSION, true);

        assertTrue(holdsElement(reopened, id));
        assertEquals(1, entries((CommandStack) reopened.getAdapter(CommandStack.class)));
        assertEquals(0, entries(stackA));
    }

    @Test
    public void shouldCommitOntoTheBoundModelsStack_whenTheBoundModelStaysActive() {
        open(modelB);
        close(modelB);   // back to A, the only model left open
        dispatcher.beginBatch(SESSION, "work", null);
        String id = createActor("Stays");

        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertTrue(holdsElement(modelA, id));
        assertEquals(1, entries(stackA));
        assertEquals(0, entries(stackB));
        assertNull("an undiverged commit names no bound model", summary.boundModelId());
    }

    // ---- commit refused after a switch --------------------------------------------------------

    @Test
    public void shouldRefuseTheCommitAndLeaveBothStacksEmpty_whenAnotherModelBecameActiveAfterTheLastCall() {
        dispatcher.beginBatch(SESSION, "work", null);
        String first = createActor("First");
        String second = createActor("Second");
        open(modelB);   // no call is made after this, so only the commit check can catch it

        ModelAccessException refusal = null;
        try {
            dispatcher.endBatch(SESSION, true);
        } catch (ModelAccessException e) {
            refusal = e;
        }

        // Stacks first: without the check the commit "succeeds", and what shows the defect is
        // where its undo entry went — onto B's stack, for a write that landed in A.
        assertEquals("the undo entry must not land on the newly active model's stack", 0, entries(stackB));
        assertEquals(0, entries(stackA));
        assertNotNull("commit was expected to be refused", refusal);
        assertEquals(ErrorCode.BOUND_MODEL_NOT_ACTIVE, refusal.getErrorCode());
        assertFalse(holdsElement(modelA, first));
        assertFalse(holdsElement(modelA, second));
        assertFalse(holdsElement(modelB, first));
        BatchStatusDto status = dispatcher.getBatchStatus(SESSION);
        assertEquals("BATCH", status.mode());
        assertEquals(Integer.valueOf(2), status.queuedCount());
    }

    @Test
    public void shouldNameBothModelsAndTheRemedy_whenTheCommitIsRefused() {
        dispatcher.beginBatch(SESSION, "work", null);
        createActor("First");
        open(modelB);

        ModelAccessException refusal = commitRefusal();

        String message = refusal.getMessage();
        assertTrue(message, message.contains("'Model A' (id model-a)"));
        assertTrue(message, message.contains("'Model B' (id model-b)"));
        assertTrue(refusal.getSuggestedCorrection(), refusal.getSuggestedCorrection().contains("rollback=true"));
        assertTrue(refusal.getSuggestedCorrection(),
                refusal.getSuggestedCorrection().contains("Close 'Model B' (id model-b)"));
        assertTrue(refusal.getDetails(), refusal.getDetails().contains("Create BusinessActor: First"));
    }

    @Test
    public void shouldCommitTheKeptQueueOntoTheBoundStack_whenTheBoundModelIsActiveAgain() {
        dispatcher.beginBatch(SESSION, "work", null);
        String id = createActor("Kept");
        open(modelB);
        commitRefusal();

        close(modelB);   // A is the remaining open model, so it becomes active again
        dispatcher.endBatch(SESSION, true);

        assertTrue(holdsElement(modelA, id));
        assertEquals(1, entries(stackA));
        assertEquals(0, entries(stackB));
    }

    @Test
    public void shouldRefuseTheBatchAndThenCommitOntoTheBoundStack_whenTheHumanMakesANewModelAndClosesIt() {
        dispatcher.beginBatch(SESSION, "work", null);
        String id = createActor("Before New");
        IArchimateModel made = newModel("model-new", "New Model");
        CommandStack stackNew = (CommandStack) made.getAdapter(CommandStack.class);
        create(made);   // File > New: the new blank model becomes active

        try {
            createActor("After New");
            fail("a call made while a new model is active must be refused");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BOUND_MODEL_NOT_ACTIVE, e.getErrorCode());
            assertTrue(e.getMessage(), e.getMessage().contains("'Model A' (id model-a)"));
            assertTrue(e.getMessage(), e.getMessage().contains("'New Model' (id model-new)"));
            assertTrue(e.getSuggestedCorrection(),
                    e.getSuggestedCorrection().contains("Close 'New Model' (id model-new)"));
        }
        ModelAccessException refusal = commitRefusal();
        assertEquals(ErrorCode.BOUND_MODEL_NOT_ACTIVE, refusal.getErrorCode());
        assertEquals(Integer.valueOf(1), dispatcher.getBatchStatus(SESSION).queuedCount());

        close(made);   // A is the remaining open model, so it becomes active again
        dispatcher.endBatch(SESSION, true);

        assertTrue(holdsElement(modelA, id));
        assertEquals(1, entries(stackA));
        assertEquals(0, entries(stackNew));
    }

    // ---- first diverged call refused ----------------------------------------------------------

    @Test
    public void shouldRefuseTheFirstMutatingCallNamingTheTool_whenAnotherModelBecameActive() {
        dispatcher.beginBatch(SESSION, "work", null);
        createActor("Before Switch");
        open(modelB);

        try {
            createActor("After Switch");
            fail("a mutating call against another model must be refused while the batch is open");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BOUND_MODEL_NOT_ACTIVE, e.getErrorCode());
            assertTrue(e.getMessage(), e.getMessage().startsWith("'create-element' refused"));
        }

        BatchStatusDto status = dispatcher.getBatchStatus(SESSION);
        assertEquals(Integer.valueOf(1), status.queuedCount());
        assertEquals(List.of("Create BusinessActor: Before Switch"), status.queuedDescriptions());
        assertEquals(0, entries(stackA));
        assertEquals(0, entries(stackB));
    }

    @Test
    public void shouldKeepACommandForAnotherModelOutOfTheQueue_whenItReachesTheQueueDirectly() {
        dispatcher.beginBatch(SESSION, "work", null);
        open(modelB);

        try {
            dispatcher.queueForBatch(SESSION, new Command("stray") { }, "Stray");
            fail("the queue must not admit work while another model is active");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BOUND_MODEL_NOT_ACTIVE, e.getErrorCode());
        }
        assertEquals(Integer.valueOf(0), dispatcher.getBatchStatus(SESSION).queuedCount());
    }

    // ---- rollback -----------------------------------------------------------------------------

    @Test
    public void shouldRollBackAndNameTheBoundModel_whenItIsNotTheActiveOne() {
        dispatcher.beginBatch(SESSION, "work", null);
        createActor("Discarded");
        open(modelB);

        BatchSummaryDto summary = dispatcher.endBatch(SESSION, false);

        assertTrue(summary.rolledBack());
        assertEquals("model-a", summary.boundModelId());
        assertEquals("Model A", summary.boundModelName());
        assertEquals(0, entries(stackA));
        assertEquals(0, entries(stackB));
        assertEquals("GUI_ATTACHED", dispatcher.getBatchStatus(SESSION).mode());
    }

    @Test
    public void shouldNotNameABoundModel_whenARollbackHasNotDiverged() {
        dispatcher.beginBatch(SESSION, "work", null);
        createActor("Discarded");

        BatchSummaryDto summary = dispatcher.endBatch(SESSION, false);

        assertNull(summary.boundModelId());
        assertNull(summary.boundModelName());
    }

    // ---- closed model -------------------------------------------------------------------------

    @Test
    public void shouldRefuseWithItsOwnReasonAndListTheQueue_whenTheBoundModelWasClosed() {
        dispatcher.beginBatch(SESSION, "work", null);
        createActor("One");
        createActor("Two");
        open(modelB);
        close(modelA);   // not the active model, so nothing else moves

        ModelAccessException refusal = commitRefusal();

        assertEquals(ErrorCode.BOUND_MODEL_CLOSED, refusal.getErrorCode());
        assertTrue(refusal.getMessage(), refusal.getMessage().contains("was closed"));
        assertFalse(refusal.getMessage(), refusal.getMessage().contains("active model is now"));
        assertTrue(refusal.getSuggestedCorrection(), refusal.getSuggestedCorrection().contains("reopen"));
        assertTrue(refusal.getDetails(), refusal.getDetails()
                .contains("Queued operations (2): 1. Create BusinessActor: One; 2. Create BusinessActor: Two"));
        assertEquals(0, entries(stackA));
        assertEquals(0, entries(stackB));
    }

    @Test
    public void shouldRefuseAsClosed_whenTheBoundModelWasClosedAndNoModelIsOpen() {
        dispatcher.beginBatch(SESSION, "work", null);
        createActor("Orphan");
        close(modelA);

        assertFalse(accessor.isModelLoaded());
        assertEquals(ErrorCode.BOUND_MODEL_CLOSED, commitRefusal().getErrorCode());
        assertEquals(0, entries(stackA));
    }

    @Test
    public void shouldStayRefused_whenTheBoundModelIsReopenedAfterClosingWithQueuedWork() {
        dispatcher.beginBatch(SESSION, "work", null);
        String id = createActor("Into The Closed Copy");
        close(modelA);
        IArchimateModel reopened = newModel("model-a", "Model A");
        open(reopened);

        ModelAccessException refusal = commitRefusal();

        assertEquals(ErrorCode.BOUND_MODEL_CLOSED, refusal.getErrorCode());
        assertEquals("the reopened instance's stack must not record a write into the closed copy",
                0, entries((CommandStack) reopened.getAdapter(CommandStack.class)));
        assertFalse(holdsElement(modelA, id));
        assertFalse(holdsElement(reopened, id));
        assertTrue(dispatcher.endBatch(SESSION, false).rolledBack());
    }

    // ---- proposals ----------------------------------------------------------------------------

    private PendingProposal countingProposal(AtomicInteger rebuilds) {
        return new PendingProposal(null, "create-element", "Create element: Proposed",
                () -> {
                    rebuilds.incrementAndGet();
                    return new PreparedMutation<>(new Command("proposed") { }, null, null);
                },
                StalenessCapture.EMPTY, null, null, null, "ok", Instant.now(), null, null);
    }

    @Test
    public void shouldStampTheProposalWithTheActiveModel_whenItIsStored() {
        approvalMode = true;
        createActor("Proposed");

        PendingProposal stored = dispatcher.getProposal(SESSION, "p-1");
        assertEquals("model-a", stored.modelId());
        assertEquals("Model A", stored.modelName());
        ProposalDto dto = dispatcher.getPendingProposalDtos(SESSION).get(0);
        assertEquals("model-a", dto.modelId());
        assertEquals("Model A", dto.modelName());
    }

    @Test
    public void shouldKeepTheModel_whenTheProposalIdIsStamped() {
        PendingProposal bound = countingProposal(new AtomicInteger()).withModel("model-x", "Model X");

        PendingProposal withId = bound.withProposalId("p-9");

        assertEquals("model-x", withId.modelId());
        assertEquals("Model X", withId.modelName());
    }

    @Test
    public void shouldRefuseApprovalWithoutRebuilding_whenTheProposalsModelIsNotActive() {
        AtomicInteger rebuilds = new AtomicInteger();
        String id = dispatcher.storeProposal(SESSION, countingProposal(rebuilds));
        open(modelB);

        try {
            dispatcher.approveProposal(SESSION, id);
            fail("approving into another model must be refused");
        } catch (MutationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("'Model A' (id model-a)"));
            assertTrue(e.getMessage(), e.getMessage().contains("'Model B' (id model-b)"));
        }
        assertEquals("no rebuild may run against the wrong model", 0, rebuilds.get());
        assertNotNull("the card stays in the queue", dispatcher.getProposal(SESSION, id));
        assertEquals(0, entries(stackA));
        assertEquals(0, entries(stackB));
    }

    @Test
    public void shouldRefuseApprovalAsClosed_whenTheProposalsModelWasClosed() {
        AtomicInteger rebuilds = new AtomicInteger();
        String id = dispatcher.storeProposal(SESSION, countingProposal(rebuilds));
        open(modelB);
        close(modelA);

        try {
            dispatcher.approveProposal(SESSION, id);
            fail("approving into a closed model must be refused");
        } catch (MutationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("has been closed"));
            assertTrue(e.getMessage(), e.getMessage().contains("Reopen 'Model A'"));
        }
        assertEquals(0, rebuilds.get());
        assertNotNull(dispatcher.getProposal(SESSION, id));
    }

    @Test
    public void shouldApproveOntoTheProposalsModelStack_whenThatModelIsActive() {
        approvalMode = true;
        createActor("Approved");
        approvalMode = false;

        dispatcher.approveProposal(SESSION, "p-1");

        assertEquals(1, entries(stackA));
        assertEquals(0, entries(stackB));
    }

    @Test
    public void shouldRefuseApprovalIntoAnOpenBatchBoundElsewhere_andKeepTheCard() {
        open(modelB);
        approvalMode = true;
        createActor("Proposed On B");   // stamped to B
        approvalMode = false;
        open(modelA);
        dispatcher.beginBatch(SESSION, "work", null);   // bound to A
        open(modelB);                                   // B active again: the proposal's own model

        try {
            dispatcher.approveProposal(SESSION, "p-1");
            fail("an approval queued into a batch bound to another model must be refused");
        } catch (MutationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("into the open batch"));
            assertTrue("the approver's remedy, not the agent's: " + e.getMessage(),
                    e.getMessage().contains("ask the agent to roll back its batch"));
            assertFalse(e.getMessage(), e.getMessage().contains("end-batch rollback=true"));
        }
        assertNotNull("the card stays in the queue", dispatcher.getProposal(SESSION, "p-1"));
        assertEquals(Integer.valueOf(0), dispatcher.getBatchStatus(SESSION).queuedCount());
    }

    @Test
    public void shouldPutTheCardBack_whenTheModelSwitchesBetweenTheChecksAndTheDispatch() {
        AtomicInteger rebuilds = new AtomicInteger();
        PendingProposal switching = new PendingProposal(null, "create-element", "Create element: Racy",
                () -> {
                    rebuilds.incrementAndGet();
                    open(modelB);   // the human opens B after approve's checks passed, before dispatch
                    return new PreparedMutation<>(new Command("racy") { }, null, null);
                },
                StalenessCapture.EMPTY, null, null, null, "ok", Instant.now(), null, null);
        String id = dispatcher.storeProposal(SESSION, switching);

        try {
            dispatcher.approveProposal(SESSION, id);
            fail("a dispatch whose model switched under it must be refused");
        } catch (MutationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("'Model B' (id model-b)"));
        }
        assertEquals(1, rebuilds.get());
        assertNotNull("the card is back in the queue under its id", dispatcher.getProposal(SESSION, id));
        assertEquals(0, entries(stackA));
        assertEquals(0, entries(stackB));
    }

    @Test
    public void shouldRefuseOnTheDispatchingStack_whenTheBatchsModelIsNoLongerActive() {
        dispatcher.beginBatch(SESSION, "work", null);
        createActor("Queued");
        java.util.function.Function<IArchimateModel, ModelAccessException> guard =
                dispatcher.commitGuard(SESSION);
        open(modelB);

        try {
            dispatcher.guardedStack(modelA, guard);
            fail("the stack lookup must refuse once another model is active");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BOUND_MODEL_NOT_ACTIVE, e.getErrorCode());
        }
        close(modelB);
        assertEquals(stackA, dispatcher.guardedStack(modelA, guard));
        assertEquals("an unbound dispatch keeps the caller's model", stackB,
                dispatcher.guardedStack(modelB, null));
    }

    @Test
    public void shouldRefuseAnEmptyCommit_whenAnotherModelIsActive() {
        dispatcher.beginBatch(SESSION, "work", null);
        open(modelB);

        assertEquals(ErrorCode.BOUND_MODEL_NOT_ACTIVE, commitRefusal().getErrorCode());
        assertEquals("BATCH", dispatcher.getBatchStatus(SESSION).mode());
    }

    @Test
    public void shouldRefuseTheSpacingRecommendationTools_whenAnotherModelBecameActive() {
        dispatcher.beginBatch(SESSION, "work", null);
        open(modelB);

        try {
            accessor.applyElementSpacingRecommendations(SESSION, "any-view", false, null);
            fail("apply-element-spacing-recommendations must be admitted against the batch's model");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BOUND_MODEL_NOT_ACTIVE, e.getErrorCode());
            assertTrue(e.getMessage(), e.getMessage().startsWith("'apply-element-spacing-recommendations'"));
        }
    }

    @Test
    public void shouldRefuseAnImageImport_whenAnotherModelBecameActive() {
        dispatcher.beginBatch(SESSION, "work", null);
        open(modelB);

        try {
            accessor.addImageToModel(SESSION, new byte[] { 1, 2, 3 }, "logo.png");
            fail("an image import writes at once, so it must not land in a model the batch never addressed");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BOUND_MODEL_NOT_ACTIVE, e.getErrorCode());
            assertTrue(e.getMessage(), e.getMessage().startsWith("'add-image-to-model'"));
        }
    }

    // ---- same-id copies: the binding is to an instance, not an id -----------------------------
    //
    // A copied .archimate file keeps its model id, and Archi opens both copies at once (it dedupes
    // open models by file only). Element ids are copied too, so "the element exists in X" passes in
    // either outcome: these tests read both stacks and each instance's own folder.

    private static final File ORIGINAL_FILE = new File("/models/meridian.archimate");
    private static final File COPY_FILE = new File("/models/meridian-copy.archimate");

    /** A second open copy of model A: same id, same name, its own file and stack. */
    private IArchimateModel copyOfA() {
        modelA.setFile(ORIGINAL_FILE);
        IArchimateModel copy = newModel("model-a", "Model A");
        copy.setFile(COPY_FILE);
        return copy;
    }

    private static CommandStack stackOf(IArchimateModel model) {
        return (CommandStack) model.getAdapter(CommandStack.class);
    }

    @Test
    public void shouldGiveEachInstanceItsOwnEpoch_evenWhenTheyShareAnId() {
        IArchimateModel copy = copyOfA();
        IArchimateModel reopened = newModel("model-a", "Model A");

        long original = dispatcher.epochOf(modelA);

        assertEquals("an instance keeps its epoch", original, dispatcher.epochOf(modelA));
        assertTrue("two open copies with one id are two instances", dispatcher.epochOf(copy) != original);
        assertTrue("a reopened file is a new instance", dispatcher.epochOf(reopened) != original);
        assertTrue(dispatcher.epochOf(reopened) != dispatcher.epochOf(copy));
    }

    @Test
    public void shouldRecordTheBoundInstanceInternally_withoutPublishingIt() {
        MutationContext context = new MutationContext();
        context.beginBatch("work", null, "model-a", "Model A", 7L);
        assertEquals(7L, context.getBoundModelEpoch());

        context.reset();

        assertEquals(0L, context.getBoundModelEpoch());
        for (RecordComponent component : BatchStatusDto.class.getRecordComponents()) {
            assertFalse("the epoch is internal and must not reach the wire: " + component.getName(),
                    component.getName().toLowerCase().contains("epoch"));
        }
    }

    @Test
    public void shouldKeepTheBatch_whenTheOtherCopyIsClosedWhileTheBoundOneStaysOpen() {
        IArchimateModel copy = copyOfA();
        dispatcher.beginBatch(SESSION, "work", null);   // bound to the original
        String id = createActor("Kept");
        open(copy);
        open(modelA);   // the original is active again
        close(copy);    // closing the OTHER copy must not mark the batch's instance closed

        dispatcher.endBatch(SESSION, true);

        assertTrue(holdsElement(modelA, id));
        assertEquals(1, entries(stackA));
        assertEquals(0, entries(stackOf(copy)));
    }

    @Test
    public void shouldNotOrphanTheBatch_whenTheActiveCopyIsClosedAndTheBoundOneTakesOver() {
        IArchimateModel copy = copyOfA();
        dispatcher.beginBatch(SESSION, "work", null);
        String id = createActor("Kept");
        open(copy);
        close(copy);    // the original is the only model left, so it becomes active again

        dispatcher.endBatch(SESSION, true);

        assertTrue(holdsElement(modelA, id));
        assertEquals(1, entries(stackA));
        assertEquals(0, entries(stackOf(copy)));
    }

    @Test
    public void shouldAdmitTheNextCall_whenTheOtherCopyIsClosedWithNothingQueued() {
        IArchimateModel copy = copyOfA();
        dispatcher.beginBatch(SESSION, "work", null);
        open(copy);
        open(modelA);
        close(copy);

        String id = createActor("After The Copy Closed");
        dispatcher.endBatch(SESSION, true);

        assertTrue(holdsElement(modelA, id));
        assertEquals(1, entries(stackA));
    }

    @Test
    public void shouldRefuseTheFirstCallNamingBothFiles_whenAnotherCopyWithTheSameIdBecameActive() {
        IArchimateModel copy = copyOfA();
        dispatcher.beginBatch(SESSION, "work", null);
        createActor("Before The Copy");
        open(copy);

        try {
            createActor("Into The Copy");
            fail("a call against another open copy of the bound model must be refused");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BOUND_MODEL_NOT_ACTIVE, e.getErrorCode());
            String message = e.getMessage();
            assertTrue(message, message.startsWith("'create-element' refused"));
            assertTrue(message, message.contains("another open copy with the same id"));
            assertTrue(message, message.contains(ORIGINAL_FILE.getPath()));
            assertTrue(message, message.contains(COPY_FILE.getPath()));
        }

        assertEquals(List.of("Create BusinessActor: Before The Copy"),
                dispatcher.getBatchStatus(SESSION).queuedDescriptions());
        assertEquals(0, entries(stackA));
        assertEquals(0, entries(stackOf(copy)));
    }

    @Test
    public void shouldSayUnsaved_whenACopyHasNoFile() {
        modelA.setFile(ORIGINAL_FILE);
        IArchimateModel unsaved = newModel("model-a", "Model A");
        dispatcher.beginBatch(SESSION, "work", null);
        open(unsaved);

        try {
            createActor("Into The Unsaved Copy");
            fail("expected a refusal");
        } catch (ModelAccessException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("unsaved"));
            assertTrue(e.getMessage(), e.getMessage().contains(ORIGINAL_FILE.getPath()));
        }
    }

    @Test
    public void shouldWarnBeforeAdvisingToCloseTheActiveCopy() {
        IArchimateModel copy = copyOfA();
        dispatcher.beginBatch(SESSION, "work", null);
        open(copy);

        try {
            createActor("Into The Copy");
            fail("expected a refusal");
        } catch (ModelAccessException e) {
            assertTrue(e.getSuggestedCorrection(), e.getSuggestedCorrection()
                    .contains("closing the copy at " + COPY_FILE.getPath() + " (save it first"));
        }
    }

    @Test
    public void shouldSayTheCopiesCannotBeToldApart_whenNeitherHasAFile() {
        IArchimateModel unsaved = newModel("model-a", "Model A");   // modelA has no file either
        dispatcher.beginBatch(SESSION, "work", null);
        open(unsaved);

        try {
            createActor("Into The Other Unsaved Copy");
            fail("expected a refusal");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BOUND_MODEL_NOT_ACTIVE, e.getErrorCode());
            assertTrue(e.getSuggestedCorrection(),
                    e.getSuggestedCorrection().startsWith("Both copies are unsaved"));
        }
    }

    @Test
    public void shouldTakeTheReopenedModelsName_whenAnEmptyBatchIsRebound() {
        dispatcher.beginBatch(SESSION, "work", null);
        close(modelA);
        IArchimateModel renamed = newModel("model-a", "Model A Renamed");
        open(renamed);

        createActor("After Reopen");

        assertEquals("Model A Renamed", dispatcher.getBatchStatus(SESSION).boundModelName());
    }

    @Test
    public void shouldRefuseTheCommitAndKeepTheQueue_whenAnotherCopyBecameActiveAfterTheLastCall() {
        IArchimateModel copy = copyOfA();
        dispatcher.beginBatch(SESSION, "work", null);
        String id = createActor("Queued");
        open(copy);

        ModelAccessException refusal = commitRefusal();

        assertEquals("the undo entry must not land on the other copy's stack", 0, entries(stackOf(copy)));
        assertEquals(0, entries(stackA));
        assertEquals(ErrorCode.BOUND_MODEL_NOT_ACTIVE, refusal.getErrorCode());
        assertTrue(refusal.getMessage(), refusal.getMessage().contains(COPY_FILE.getPath()));
        assertFalse(holdsElement(modelA, id));
        assertFalse(holdsElement(copy, id));
        assertEquals(Integer.valueOf(1), dispatcher.getBatchStatus(SESSION).queuedCount());
    }

    @Test
    public void shouldRefuseOnTheDispatchingStack_whenAnotherCopyIsActive() {
        IArchimateModel copy = copyOfA();
        dispatcher.beginBatch(SESSION, "work", null);
        createActor("Queued");
        java.util.function.Function<IArchimateModel, ModelAccessException> guard =
                dispatcher.commitGuard(SESSION);
        open(copy);

        try {
            dispatcher.guardedStack(modelA, guard);
            fail("the stack lookup must refuse once another copy is active");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BOUND_MODEL_NOT_ACTIVE, e.getErrorCode());
        }
        open(modelA);
        assertEquals(stackA, dispatcher.guardedStack(modelA, guard));
    }

    @Test
    public void shouldCommitOntoTheBoundCopysStack_whenItIsActiveAgain() {
        IArchimateModel copy = copyOfA();
        dispatcher.beginBatch(SESSION, "work", null);
        String id = createActor("Kept");
        open(copy);
        commitRefusal();

        open(modelA);
        dispatcher.endBatch(SESSION, true);

        assertTrue(holdsElement(modelA, id));
        assertEquals(1, entries(stackA));
        assertEquals(0, entries(stackOf(copy)));
    }

    @Test
    public void shouldStampTheProposalWithItsInstance_andKeepItThroughTheIdStamp() {
        approvalMode = true;
        createActor("Proposed");

        PendingProposal stored = dispatcher.getProposal(SESSION, "p-1");
        assertEquals(dispatcher.epochOf(modelA), stored.modelEpoch());
        assertEquals(stored.modelEpoch(), stored.withProposalId("p-9").modelEpoch());
        assertEquals(5L, stored.withModel("model-x", "Model X", 5L).modelEpoch());
    }

    @Test
    public void shouldRefuseApprovalNamingBothFiles_whenAnotherCopyIsActive_andKeepTheCard() {
        IArchimateModel copy = copyOfA();
        AtomicInteger rebuilds = new AtomicInteger();
        String id = dispatcher.storeProposal(SESSION, countingProposal(rebuilds));
        open(copy);

        try {
            dispatcher.approveProposal(SESSION, id);
            fail("approving into another open copy must be refused");
        } catch (MutationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(ORIGINAL_FILE.getPath()));
            assertTrue(e.getMessage(), e.getMessage().contains(COPY_FILE.getPath()));
            assertTrue("the remedy is the approver's: " + e.getMessage(), e.getMessage().contains("reject"));
        }
        assertEquals(0, rebuilds.get());
        assertNotNull("the card stays in the queue", dispatcher.getProposal(SESSION, id));
        assertEquals(0, entries(stackA));
        assertEquals(0, entries(stackOf(copy)));
    }

    @Test
    public void shouldApproveAgainstTheLiveInstance_whenTheStampedOneWasClosedAndTheIdIsOpenAgain() {
        approvalMode = true;
        createActor("Proposed");
        approvalMode = false;
        close(modelA);
        IArchimateModel reopened = newModel("model-a", "Model A");
        open(reopened);

        dispatcher.approveProposal(SESSION, "p-1");

        assertEquals(1, entries(stackOf(reopened)));
        assertEquals(0, entries(stackA));
    }

    // ---- the admitted instance is carried through the call ------------------------------------

    @Test
    public void shouldRefuseToQueue_whenTheAdmittedInstanceClosedAndReopenedBeforeTheCallQueued() {
        dispatcher.beginBatch(SESSION, "work", null);   // empty: nothing is orphaned by a close
        dispatcher.admit(SESSION, "create-element", modelA);   // the call is admitted on instance 1 ...
        close(modelA);
        IArchimateModel reopened = newModel("model-a", "Model A");
        open(reopened);                                        // ... which is closed and reopened

        try {
            dispatcher.queueForBatch(SESSION, new Command("prepared against instance 1") { },
                    "Create BusinessActor: In Flight");
            fail("a command prepared against a closed instance must not enter the queue");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BOUND_MODEL_CLOSED, e.getErrorCode());
            assertTrue(e.getMessage(), e.getMessage().contains("In Flight"));
            // Refused by the admitted-instance check itself, not by the batch's own closed binding.
            assertTrue(e.getMessage(), e.getMessage().contains("this call was prepared against model"));
            assertTrue(e.getSuggestedCorrection(),
                    e.getSuggestedCorrection().startsWith("Retry the call, which prepares it against the model open now"));
        }
        assertEquals(Integer.valueOf(0), dispatcher.getBatchStatus(SESSION).queuedCount());
        assertEquals(0, entries(stackOf(reopened)));

        String id = createActor("Fresh Call");   // admitted on instance 2, so the empty batch rebinds
        dispatcher.endBatch(SESSION, true);

        assertTrue(holdsElement(reopened, id));
        assertEquals(1, entries(stackOf(reopened)));
        assertEquals(0, entries(stackA));
    }

    @Test
    public void shouldStampTheProposalWithTheAdmittedModel_whenTheActiveOneChangedBeforeTheStore() {
        AtomicInteger rebuilds = new AtomicInteger();
        dispatcher.admit(SESSION, "create-element", modelA);
        open(modelB);   // the human switches models between admission and store

        String id = dispatcher.storeProposal(SESSION, countingProposal(rebuilds));

        PendingProposal stored = dispatcher.getProposal(SESSION, id);
        assertEquals("model-a", stored.modelId());
        assertEquals("Model A", stored.modelName());
        assertEquals(dispatcher.epochOf(modelA), stored.modelEpoch());
        try {
            dispatcher.approveProposal(SESSION, id);
            fail("approving while another model is active must be refused");
        } catch (MutationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("'Model B' (id model-b)"));
        }
        assertEquals(0, rebuilds.get());
        assertEquals(0, entries(stackB));
    }

    @Test
    public void shouldNotReadAnEarlierCallsInstance_whenAnApprovalFollowsAFailedCallOnTheSameThread() {
        approvalMode = true;
        createActor("Proposed");   // p-1, stamped on instance 1
        approvalMode = false;
        try {
            accessor.createElement(SESSION, "NotAnArchiMateType", "Bad", null, null, null, null);
            fail("the call was expected to fail validation after admission");
        } catch (ModelAccessException expected) {
            // admitted on instance 1, then failed before anything was queued or stored
        }
        close(modelA);
        IArchimateModel reopened = newModel("model-a", "Model A");
        open(reopened);
        dispatcher.beginBatch(SESSION, "work", null);   // bound to instance 2

        dispatcher.approveProposal(SESSION, "p-1");   // same thread: must not see instance 1

        assertEquals(Integer.valueOf(1), dispatcher.getBatchStatus(SESSION).queuedCount());
        dispatcher.endBatch(SESSION, true);
        assertEquals(1, entries(stackOf(reopened)));
    }

    @Test
    public void shouldRefuseTheEarlierCall_whenALaterCallRebindsTheEmptyBatchAfterAReopen() throws Exception {
        dispatcher.beginBatch(SESSION, "work", null);
        java.util.concurrent.CountDownLatch admitted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch reopenedAndRebound = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> earlier = new java.util.concurrent.atomic.AtomicReference<>();
        Thread callA = new Thread(() -> {
            dispatcher.admit(SESSION, "create-element", modelA);   // admitted before the close
            admitted.countDown();
            try {
                reopenedAndRebound.await();
                dispatcher.queueForBatch(SESSION, new Command("prepared against instance 1") { },
                        "Create BusinessActor: Call A");
            } catch (Throwable t) {
                earlier.set(t);
            }
        });
        callA.setDaemon(true);
        callA.start();
        admitted.await();
        close(modelA);
        IArchimateModel reopened = newModel("model-a", "Model A");
        open(reopened);
        createActor("Call B");   // admitted after the reopen: rebinds the empty batch and queues
        reopenedAndRebound.countDown();
        callA.join(5000);
        assertFalse("call A did not finish", callA.isAlive());

        assertTrue("call A must be refused, got " + earlier.get(),
                earlier.get() instanceof ModelAccessException refusal
                        && refusal.getErrorCode() == ErrorCode.BOUND_MODEL_CLOSED);
        assertEquals(List.of("Create BusinessActor: Call B"),
                dispatcher.getBatchStatus(SESSION).queuedDescriptions());
        dispatcher.endBatch(SESSION, true);
        assertEquals(1, entries(stackOf(reopened)));
        assertEquals(0, entries(stackA));
    }

    // ---- undo and redo are held to the session's batch ----------------------------------------
    //
    // Undo acts on the active model's stack. While this session's batch is bound elsewhere, the
    // agent believes it is working on the bound model, so popping the active one is a wrong-model
    // write: it may revert the human's work or an earlier agent change made in that model.

    @Test
    public void shouldRefuseUndoAndRedoWithoutTouchingEitherStack_whenTheBatchIsBoundToAnotherModel() {
        open(modelB);
        createActor("Made In B");   // an agent change on B's stack, outside any batch
        open(modelA);
        dispatcher.beginBatch(SESSION, "work", null);   // bound to A
        open(modelB);

        for (String operation : List.of("undo", "redo")) {
            try {
                if (operation.equals("undo")) {
                    accessor.undo(SESSION, 1);
                } else {
                    accessor.redo(SESSION, 1);
                }
                fail(operation + " must be refused while the batch is bound to another model");
            } catch (ModelAccessException e) {
                assertEquals(ErrorCode.BOUND_MODEL_NOT_ACTIVE, e.getErrorCode());
                assertTrue(e.getMessage(), e.getMessage().startsWith("'" + operation + "' refused"));
            }
        }
        assertEquals("nothing may be popped from the active model's stack", 1, entries(stackB));
        assertTrue(stackB.canUndo());
        assertEquals(0, entries(stackA));
    }

    @Test
    public void shouldRefuseUndoNamingBothFiles_whenAnotherCopyOfTheBoundModelIsActive() {
        IArchimateModel copy = copyOfA();
        dispatcher.beginBatch(SESSION, "work", null);
        open(copy);

        try {
            accessor.undo(SESSION, 1);
            fail("undo must be refused while another copy is active");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BOUND_MODEL_NOT_ACTIVE, e.getErrorCode());
            assertTrue(e.getMessage(), e.getMessage().contains(ORIGINAL_FILE.getPath()));
            assertTrue(e.getMessage(), e.getMessage().contains(COPY_FILE.getPath()));
        }
    }

    @Test
    public void shouldRefuseUndoAsClosed_whenTheBoundModelWasClosedWithQueuedWork() {
        dispatcher.beginBatch(SESSION, "work", null);
        createActor("Queued");
        open(modelB);
        close(modelA);

        try {
            accessor.undo(SESSION, 1);
            fail("undo must be refused while the batch's model is closed");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BOUND_MODEL_CLOSED, e.getErrorCode());
        }
    }

    @Test
    public void shouldAdmitUndo_whenNoBatchIsOpenOrTheBatchIsBoundToTheActiveModel() {
        open(modelB);
        dispatcher.admitStackOperation(SESSION, "undo", modelB);   // no batch: unchanged

        open(modelA);
        dispatcher.beginBatch(SESSION, "work", null);
        dispatcher.admitStackOperation(SESSION, "undo", modelA);   // undiverged batch: still allowed
        dispatcher.admitStackOperation("another-session", "undo", modelB);   // a session without a batch

        BatchStatusDto status = dispatcher.getBatchStatus(SESSION);
        assertEquals("BATCH", status.mode());
        assertEquals("model-a", status.boundModelId());
        assertEquals(Integer.valueOf(0), status.queuedCount());
        assertEquals(dispatcher.epochOf(modelA), boundEpoch());
    }

    /** The epoch this session's batch is bound to, read from its context. */
    private long boundEpoch() {
        try {
            Field sessions = MutationDispatcher.class.getDeclaredField("batchSessions");
            sessions.setAccessible(true);
            return ((java.util.Map<?, ?>) sessions.get(dispatcher)).get(SESSION) instanceof MutationContext c
                    ? c.getBoundModelEpoch() : 0L;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void shouldCarryNoAdmittedModel_outOfAStackOperation() {
        approvalMode = true;
        dispatcher.admit(SESSION, "create-element", modelA);
        open(modelB);
        dispatcher.admitStackOperation(SESSION, "undo", modelB);   // replaces, and carries nothing

        String id = dispatcher.storeProposal(SESSION, countingProposal(new AtomicInteger()));

        assertEquals("a stack operation must not leave the earlier call's model behind",
                "model-b", dispatcher.getProposal(SESSION, id).modelId());
    }

    // ---- an immediate call is bound to the model it was admitted against ----------------------
    //
    // A call prepares against the model it captured, then dispatches; the human can close or open a
    // model in between (seconds, for the layout tools). The dispatch must land on the stack of the
    // model the call prepared against, or be refused — never on whichever model is active by then.

    /** Both models open, A active: B joins the open set without being announced as opened. */
    private void bothOpenWithAActive() {
        models.getModels().add(modelB);
        assertEquals(modelA, activeModelInstance());
    }

    private static Command probe(String label) {
        return new Command(label) { };
    }

    /** An agent-authored entry made on a stack directly, as an earlier call would leave one. */
    private static Command agentEntry(CommandStack stack, String label) {
        Command entry = new AgentAuthoredCompoundCommand(probe(label));
        stack.execute(entry);
        return entry;
    }

    @Test
    public void shouldRefuseAsClosedAndLeaveTheFallbackStackEmpty_whenTheModelClosesBeforeTheDispatch() {
        bothOpenWithAActive();
        beforeDispatch = () -> close(modelA);   // B becomes active as the fallback

        try {
            createActor("In Flight");
            fail("a call whose model closed before it dispatched must be refused, not succeed on B");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BOUND_MODEL_CLOSED, e.getErrorCode());
            assertTrue(e.getMessage(), e.getMessage().contains("'Model A' (id model-a)"));
            assertTrue(e.getMessage(), e.getMessage().contains("Nothing was applied to any open model."));
        }
        assertEquals("the write must not get an undo entry on the fallback model", 0, entries(stackB));
    }

    @Test
    public void shouldRefuseNamingBothModelsAndLeaveBothStacksUnchanged_whenAnotherModelOpensBeforeTheDispatch() {
        bothOpenWithAActive();
        beforeDispatch = () -> open(modelB);

        try {
            createActor("In Flight");
            fail("a call whose model stopped being active before it dispatched must be refused");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BOUND_MODEL_NOT_ACTIVE, e.getErrorCode());
            assertTrue(e.getMessage(), e.getMessage().startsWith("'create-element' refused"));
            assertTrue(e.getMessage(), e.getMessage().contains("'Model A' (id model-a)"));
            assertTrue(e.getMessage(), e.getMessage().contains("'Model B' (id model-b)"));
            assertTrue(e.getMessage(), e.getMessage().contains("Nothing was changed"));
            assertTrue(e.getSuggestedCorrection(),
                    e.getSuggestedCorrection().contains("Close 'Model B' (id model-b)"));
        }
        assertEquals(0, entries(stackA));
        assertEquals(0, entries(stackB));
    }

    @Test
    public void shouldUndoTheCallsOwnEarlierEntries_whenALaterDispatchOfTheSameCallIsRefused() {
        bothOpenWithAActive();
        Command earlier = agentEntry(stackA, "an earlier call's change");
        dispatcher.admit(SESSION, "probe", modelA);
        dispatcher.dispatchImmediate(probe("trial 1"));
        open(modelB);

        try {
            dispatcher.dispatchImmediate(probe("trial 2"));
            fail("the second dispatch must be refused");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BOUND_MODEL_NOT_ACTIVE, e.getErrorCode());
            assertTrue(e.getMessage(), e.getMessage().contains("1 earlier change(s)"));
            assertTrue(e.getMessage(), e.getMessage().contains("were undone"));
            assertTrue(e.getMessage(), e.getMessage().contains("Nothing was changed"));
        }
        assertEquals("A is back at its depth before the call, and not below it",
                earlier, stackA.getUndoCommand());
        assertTrue("the call's own entry moved to A's redo side", stackA.canRedo());
        assertEquals(0, entries(stackB));
    }

    @Test
    public void shouldSayTheRollbackFailed_andNotThatNothingChanged_whenUndoingTheCallsOwnEntryFails() {
        bothOpenWithAActive();
        dispatcher.admit(SESSION, "probe", modelA);
        dispatcher.dispatchImmediate(probe("trial 1"));
        dispatcher.dispatchImmediate(new Command("trial 2") {
            @Override
            public void undo() {
                throw new IllegalStateException("internal detail");
            }
        });
        open(modelB);

        try {
            dispatcher.dispatchImmediate(probe("trial 3"));
            fail("the third dispatch must be refused");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BOUND_MODEL_NOT_ACTIVE, e.getErrorCode());
            assertTrue(e.getMessage(), e.getMessage().contains("'probe' refused"));
            assertTrue(e.getMessage(), e.getMessage().contains("Undoing the call's earlier changes to 'Model A' "
                    + "(id model-a) failed: Undoing 'trial 2' failed unexpectedly"));
            assertTrue(e.getMessage(), e.getMessage().contains("1 more of the call's change(s) were not undone"));
            assertFalse(e.getMessage(), e.getMessage().contains("Nothing was changed"));
            assertFalse(e.getMessage(), e.getMessage().contains("internal detail"));
        }
        assertEquals("the entry that failed left the history; the one below it is still applied",
                "trial 1", stackA.getUndoCommand().getLabel());
        assertFalse(stackA.canRedo());
        assertEquals(0, entries(stackB));
    }

    @Test
    public void shouldStopTheRollbackAtAHumanEditAndSaySo_whenTheHumanEditedAboveTheCallsEntry() {
        bothOpenWithAActive();
        dispatcher.admit(SESSION, "probe", modelA);
        dispatcher.dispatchImmediate(probe("trial 1"));
        stackA.execute(probe("human edit"));   // not agent-authored
        open(modelB);

        try {
            dispatcher.dispatchImmediate(probe("trial 2"));
            fail("the second dispatch must be refused");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BOUND_MODEL_NOT_ACTIVE, e.getErrorCode());
            assertTrue(e.getMessage(), e.getMessage().contains("could not be undone"));
            assertTrue(e.getMessage(), e.getMessage().contains("human edit"));
            assertFalse(e.getMessage(), e.getMessage().contains("Nothing was changed"));
        }
        assertEquals("human edit", stackA.getUndoCommand().getLabel());
        assertEquals("A keeps the call's entry and the human edit", 2, entries(stackA));
        assertFalse(stackA.canRedo());
        assertEquals(0, entries(stackB));
    }

    @Test
    public void shouldUndoOnTheAdmittedModelsOwnStack_whenAnInternalRollbackRunsAfterAnotherModelOpened() {
        bothOpenWithAActive();
        Command bEntry = agentEntry(stackB, "an earlier change in B");
        dispatcher.admit(SESSION, "probe", modelA);
        dispatcher.dispatchImmediate(probe("trial 1"));
        open(modelB);

        UndoRedoState state = dispatcher.undo(1);   // a speculative loop reverting its own trial

        assertEquals(1, state.labels().size());
        assertFalse("A's trial entry is popped", stackA.canUndo());
        assertTrue(stackA.canRedo());
        assertEquals("B is untouched", bEntry, stackB.getUndoCommand());
        assertFalse(stackB.canRedo());
    }

    @Test
    public void shouldMakeTheInternalRollbackANoOp_whenTheAdmittedModelClosed() {
        bothOpenWithAActive();
        Command bEntry = agentEntry(stackB, "an earlier change in B");
        dispatcher.admit(SESSION, "probe", modelA);
        dispatcher.dispatchImmediate(probe("trial 1"));
        close(modelA);   // B becomes active as the fallback

        UndoRedoState state = dispatcher.undo(1);

        assertTrue(state.labels().isEmpty());
        assertEquals("nothing is popped from the fallback model", bEntry, stackB.getUndoCommand());
        assertFalse(stackB.canRedo());
    }

    @Test
    public void shouldNotUndoBelowTheCall_whenAnInternalRollbackFollowsARefusalThatAlreadyRolledBack() {
        bothOpenWithAActive();
        Command earlier = agentEntry(stackA, "an earlier call's change");
        dispatcher.admit(SESSION, "probe", modelA);
        dispatcher.dispatchImmediate(probe("trial 1"));
        open(modelB);
        try {
            dispatcher.dispatchImmediate(probe("trial 2"));
            fail("the second dispatch must be refused");
        } catch (ModelAccessException expected) {
            // the refusal rolled back trial 1
        }

        dispatcher.undo(1);   // a finally block reverting the trial the refusal already reverted

        assertEquals("an earlier call's change must survive", earlier, stackA.getUndoCommand());
    }

    @Test
    public void shouldRefusePublicUndoAndLeaveBothStacks_whenAnotherModelOpenedAfterItWasAdmitted() {
        bothOpenWithAActive();
        Command aEntry = agentEntry(stackA, "a change in A");
        Command bEntry = agentEntry(stackB, "a change in B");
        dispatcher.admitStackOperation(SESSION, "undo", modelA);
        open(modelB);

        try {
            dispatcher.undo(1);
            fail("the agent's undo must not act on a model the human moved to");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BOUND_MODEL_NOT_ACTIVE, e.getErrorCode());
            assertTrue(e.getMessage(), e.getMessage().startsWith("'undo' refused"));
            assertTrue(e.getMessage(), e.getMessage().contains("'Model B' (id model-b)"));
        }
        assertEquals(aEntry, stackA.getUndoCommand());
        assertFalse(stackA.canRedo());
        assertEquals(bEntry, stackB.getUndoCommand());
        assertFalse(stackB.canRedo());
    }

    @Test
    public void shouldNotClaimAnUndo_whenTheCallsEarlierCommandDeclinedToRun() {
        bothOpenWithAActive();
        dispatcher.admit(SESSION, "probe", modelA);
        dispatcher.dispatchImmediate(new Command("declines") {
            @Override
            public boolean canExecute() {
                return false;   // the stack skips it: no entry is pushed
            }
        });
        open(modelB);

        try {
            dispatcher.dispatchImmediate(probe("trial 2"));
            fail("the second dispatch must be refused");
        } catch (ModelAccessException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Nothing was changed."));
            assertFalse("nothing was undone, so the refusal must not say so: " + e.getMessage(),
                    e.getMessage().contains("were undone"));
        }
        assertEquals(0, entries(stackA));
    }

    @Test
    public void shouldNotUndoAnEarlierCall_whenTheHumanUndidTheCallsEntryAndTheOneBelowIt() {
        bothOpenWithAActive();
        Command first = agentEntry(stackA, "an earlier call's first change");
        agentEntry(stackA, "an earlier call's second change");
        dispatcher.admit(SESSION, "probe", modelA);
        dispatcher.dispatchImmediate(probe("trial 1"));
        stackA.undo();   // the human undoes the call's entry natively ...
        stackA.undo();   // ... and the earlier call's second change beneath it
        open(modelB);

        try {
            dispatcher.dispatchImmediate(probe("trial 2"));
            fail("the second dispatch must be refused");
        } catch (ModelAccessException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Nothing was changed."));
        }
        assertEquals("the rollback must stop at the call's own baseline, wherever the human left the stack",
                first, stackA.getUndoCommand());
    }

    @Test
    public void shouldNotUndoAnEarlierCall_whenAnInternalUndoFollowsARefusedFirstDispatch() {
        bothOpenWithAActive();
        Command earlier = agentEntry(stackA, "an earlier call's change");
        dispatcher.admit(SESSION, "probe", modelA);
        open(modelB);
        try {
            dispatcher.dispatchImmediate(probe("trial 1"));
            fail("the first dispatch must be refused");
        } catch (ModelAccessException expected) {
            // a caller reverting its trials regardless, as a finally block would
        }

        dispatcher.undo(1);

        assertEquals("the call made no entry, so its undo has nothing of its own to revert",
                earlier, stackA.getUndoCommand());
    }

    // ---- no admission outlives its call --------------------------------------------------------

    @Test
    public void shouldLeaveNoAdmissionBehind_whenABatchCommitsOnAThreadAnEarlierCallWasAdmittedOn() {
        open(modelB);
        dispatcher.beginBatch(SESSION, "work", null);   // bound to B
        createActor("Queued");
        open(modelA);
        dispatcher.admit("another-session", "create-element", modelA);   // a call that failed after admission
        open(modelB);

        dispatcher.endBatch(SESSION, true);   // not admitted: lands on B, as today
        assertEquals(1, entries(stackB));
        dispatcher.undo(1);   // the next stack operation on this thread resolves B, as today

        assertFalse("the commit must be undone from B, not steered to A by a stale admission", stackB.canUndo());
        assertEquals(0, entries(stackA));
    }

    @Test
    public void shouldLeaveNoAdmissionBehind_whenAnApprovalDispatchesOnAThreadAnEarlierCallWasAdmittedOn() {
        open(modelB);
        approvalMode = true;
        createActor("Proposed");   // stamped on B
        approvalMode = false;
        dispatcher.admit("another-session", "create-element", modelA);   // A is open, not active

        dispatcher.approveProposal(SESSION, dispatcher.getPendingProposals(SESSION).get(0).proposalId());
        assertEquals(1, entries(stackB));
        dispatcher.undo(1);

        assertFalse(stackB.canUndo());
        assertEquals(0, entries(stackA));
    }

    @Test
    public void shouldBindTheNextCallToItsOwnModel_whenAnEarlierCallOnTheThreadWasAdmittedOnAnother() {
        bothOpenWithAActive();
        dispatcher.admit(SESSION, "probe", modelA);
        dispatcher.dispatchImmediate(probe("trial 1"));   // the earlier call, complete
        open(modelB);

        String id = createActor("Next Call");   // admitted on B: not refused, not rolled back

        assertTrue(holdsElement(modelB, id));
        assertEquals(1, entries(stackB));
        assertEquals("the earlier call's entry stays on A", 1, entries(stackA));
        assertTrue(stackA.canUndo());
    }

    // ---- the admission ends with its tool call -------------------------------------------------

    private static McpSchema.CallToolResult textResult(String text) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(text)))
                .isError(false)
                .build();
    }

    private static McpServerFeatures.SyncToolSpecification toolNamed(CommandRegistry registry, String name) {
        return registry.getToolSpecifications().stream()
                .filter(spec -> name.equals(spec.tool().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Tool not registered: " + name));
    }

    @Test
    public void shouldLeaveNoAdmissionBehind_whenAToolCallAdmitsAndReturnsWithoutDispatching() {
        bothOpenWithAActive();
        CommandRegistry registry = new CommandRegistry();
        registry.setCallExitHook(dispatcher::endCall);
        registry.registerTool(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder().name("admits-only").description("admits-only")
                        .inputSchema(new McpSchema.JsonSchema("object", java.util.Map.of(), null, null, null, null))
                        .build())
                .callHandler((exchange, request) -> {
                    dispatcher.admit(SESSION, "probe", modelA);   // admits, never dispatches
                    return textResult("done");
                })
                .build());

        toolNamed(registry, "admits-only").callHandler().apply(null, null);
        open(modelB);
        dispatcher.dispatchImmediate(probe("unadmitted"));   // the next work on this thread, not admitted

        assertEquals("the unadmitted dispatch lands on the active model", 1, entries(stackB));
        assertEquals(0, entries(stackA));
    }

    @Test
    public void shouldClearTheAdmissionAtToolExit_whenTheToolsAreWiredAsProductionWiresThem() {
        bothOpenWithAActive();
        CommandRegistry registry = new CommandRegistry();
        HandlerRegistrar.registerAll(accessor, new ResponseFormatter(), registry,
                new SessionManager(SearchHandler.VALID_TYPES, SearchHandler.VALID_LAYERS));

        McpSchema.CallToolResult result = toolNamed(registry, "create-element").callHandler().apply(null,
                new McpSchema.CallToolRequest("create-element",
                        // Two models are open, so a change must name its model or be refused.
                        java.util.Map.of("type", "BusinessActor", "name", "Wired", "modelId", "model-a")));
        assertFalse(String.valueOf(result.content()), Boolean.TRUE.equals(result.isError()));
        assertEquals(1, entries(stackA));
        open(modelB);
        dispatcher.dispatchImmediate(probe("unadmitted"));

        assertEquals("the unadmitted dispatch lands on the active model", 1, entries(stackB));
        assertEquals("the tool's own entry stays on A", 1, entries(stackA));
    }

    // ---- a call undoes and redoes only its own entries ------------------------------------------

    /** The undo side of a stack, bottom to top, as labels. */
    private static List<String> undoSide(CommandStack stack) {
        List<String> labels = new ArrayList<>();
        if (!stack.canUndo()) {
            return labels;
        }
        for (Object each : stack.getCommands()) {
            labels.add(((Command) each).getLabel());
            if (each == stack.getUndoCommand()) {
                break;
            }
        }
        return labels;
    }

    private void assertRefusedPastAnotherAgentChange(String trial) {
        try {
            dispatcher.dispatchImmediate(probe(trial));
            fail("the dispatch must be refused");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BOUND_MODEL_NOT_ACTIVE, e.getErrorCode());
            assertTrue(e.getMessage(), e.getMessage().contains("1 of the call's 2 earlier change(s)"));
            assertTrue(e.getMessage(), e.getMessage().contains("another agent change"));
            assertFalse(e.getMessage(), e.getMessage().contains("Nothing was changed"));
            assertFalse(e.getMessage(), e.getMessage().contains("human edit"));
        }
    }

    @Test
    public void shouldStopTheRollbackAtAnotherAgentChangeAndSaySo_whenOneSitsBetweenTheCallsEntries() {
        bothOpenWithAActive();
        dispatcher.admit(SESSION, "probe", modelA);
        dispatcher.dispatchImmediate(probe("X1"));
        agentEntry(stackA, "F");   // another call's work: a batch commit, an approved proposal
        dispatcher.dispatchImmediate(probe("X2"));
        open(modelB);

        assertRefusedPastAnotherAgentChange("X3");

        assertEquals("X2 is undone; F and X1 are kept", List.of("X1", "F"), undoSide(stackA));
        assertEquals(0, entries(stackB));
    }

    @Test
    public void shouldStopTheRollbackAtAnotherCallOfTheSameSession_whenOneSitsBetweenTheCallsEntries()
            throws Exception {
        bothOpenWithAActive();
        dispatcher.admit(SESSION, "probe", modelA);
        dispatcher.dispatchImmediate(probe("X1"));
        Throwable[] failure = new Throwable[1];
        Thread other = new Thread(() -> {
            try {
                dispatcher.admit(SESSION, "probe", modelA);   // a second call of the same session
                dispatcher.dispatchImmediate(probe("F"));
            } catch (Throwable t) {
                failure[0] = t;
            }
        });
        other.start();
        other.join();
        assertNull(String.valueOf(failure[0]), failure[0]);
        dispatcher.dispatchImmediate(probe("X2"));
        open(modelB);

        assertRefusedPastAnotherAgentChange("X3");

        assertEquals("X2 is undone; F and X1 are kept", List.of("X1", "F"), undoSide(stackA));
    }

    @Test
    public void shouldStopTheInternalUndoAtAnotherAgentChange_whenOneSitsBetweenTheCallsEntries() {
        bothOpenWithAActive();
        dispatcher.admit(SESSION, "probe", modelA);
        dispatcher.dispatchImmediate(probe("X1"));
        agentEntry(stackA, "F");
        dispatcher.dispatchImmediate(probe("X2"));

        UndoRedoState state = dispatcher.undo(2);

        assertEquals(List.of("X2"), state.labels());
        assertEquals(List.of("X1", "F"), undoSide(stackA));
    }

    @Test
    public void shouldReportNoBlock_whenTheInternalUndoUndidAllTheCallOwnsAboveAHumanEdit() {
        bothOpenWithAActive();
        stackA.execute(new Command("human edit") { });
        dispatcher.admit(SESSION, "probe", modelA);
        dispatcher.dispatchImmediate(probe("X1"));

        UndoRedoState state = dispatcher.undo(3);

        assertEquals(List.of("X1"), state.labels());
        assertNull("progress was made, so nothing blocked it", state.blockedReason());
        assertEquals("human edit", stackA.getUndoCommand().getLabel());
    }

    @Test
    public void shouldReportAHumanEditBlock_whenOneSitsAboveTheCallsOnlyEntry() {
        bothOpenWithAActive();
        dispatcher.admit(SESSION, "probe", modelA);
        dispatcher.dispatchImmediate(probe("X1"));
        stackA.execute(new Command("human edit") { });

        UndoRedoState state = dispatcher.undo(1);

        assertTrue(state.labels().isEmpty());
        assertEquals(MutationDispatcher.BLOCK_REASON_HUMAN_EDIT, state.blockedReason());
        assertEquals("human edit", stackA.getUndoCommand().getLabel());
    }

    @Test
    public void shouldNotRedoAChangeTheHumanUndid_whenTheCallRedoes() {
        bothOpenWithAActive();
        dispatcher.admit(SESSION, "probe", modelA);
        dispatcher.dispatchImmediate(probe("X1"));
        stackA.undo();   // the human undoes the call's entry natively

        UndoRedoState state = dispatcher.redo(1);

        assertTrue(state.labels().isEmpty());
        assertFalse(stackA.canUndo());
        assertEquals("X1", stackA.getRedoCommand().getLabel());
    }

    @Test
    public void shouldNotRedoPastAChangeTheHumanUndid_whenTheCallUndidAnEntryBeneathIt() {
        bothOpenWithAActive();
        dispatcher.admit(SESSION, "probe", modelA);
        dispatcher.dispatchImmediate(probe("X1"));
        dispatcher.dispatchImmediate(probe("X2"));
        dispatcher.undo(1);   // the call undoes X2
        stackA.undo();        // the human undoes X1 natively

        UndoRedoState state = dispatcher.redo(2);

        assertTrue("the redo top is X1, which the call did not undo", state.labels().isEmpty());
        assertFalse(stackA.canUndo());
        assertEquals("X1", stackA.getRedoCommand().getLabel());
    }

    @Test
    public void shouldRedoAndThenRollBackItsOwnUndo_whenTheCallRedoesWhatItUndid() {
        bothOpenWithAActive();
        Command earlier = agentEntry(stackA, "an earlier call's change");
        dispatcher.admit(SESSION, "probe", modelA);
        dispatcher.dispatchImmediate(probe("X1"));
        dispatcher.undo(1);

        UndoRedoState state = dispatcher.redo(1);

        assertEquals(List.of("X1"), state.labels());
        open(modelB);
        try {
            dispatcher.dispatchImmediate(probe("X2"));
            fail("the dispatch must be refused");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BOUND_MODEL_NOT_ACTIVE, e.getErrorCode());
        }
        assertEquals("A is back at its depth before the call", earlier, stackA.getUndoCommand());
    }

    // ---- the staleness guard is per model ------------------------------------------------------

    private static com.archimatetool.model.IArchimateElement actorIn(IArchimateModel model, String name) {
        com.archimatetool.model.IArchimateElement actor = IArchimateFactory.eINSTANCE.createBusinessActor();
        actor.setName(name);
        model.getFolder(FolderType.BUSINESS).getElements().add(actor);
        return actor;
    }

    private static Command rename(com.archimatetool.model.IArchimateElement element, String to) {
        return new Command("rename") {
            private String from;
            @Override
            public void execute() {
                from = element.getName();
                element.setName(to);
            }
            @Override
            public void undo() {
                element.setName(from);
            }
        };
    }

    /**
     * A proposal pending on model A stays watched after model B becomes active: a human edit to its
     * target on A still marks it stale. A single listener re-registered on the active model missed it.
     */
    @Test
    public void shouldStillSeeAHumanEditOnA_afterBBecomesActive() {
        com.archimatetool.model.IArchimateElement target = actorIn(modelA, "Target");
        ProposalStalenessGuard guard = new ProposalStalenessGuard(() -> modelA);
        guard.onModelActive(modelA);
        StalenessCapture capture = guard.capture(java.util.Set.of(target.getId()), null, modelA);

        guard.onModelActive(modelB);
        stackA.execute(rename(target, "Edited by the human"));

        assertTrue("the human's edit on A is seen after B became active",
                guard.vet(capture, null, modelA).stale());
        assertEquals(2, guard.watchedCount());
        guard.onModelInactive();
    }

    /**
     * A human edit on model B never counts against a proposal on model A: with one shared sequence,
     * B's human edit made an agent-only change on A read as a human intervention, falsely stale.
     */
    @Test
    public void shouldNotCountAHumanEditOnB_againstAProposalOnA() {
        com.archimatetool.model.IArchimateElement target = actorIn(modelA, "Target");
        com.archimatetool.model.IArchimateElement other = actorIn(modelB, "Other");
        ProposalStalenessGuard guard = new ProposalStalenessGuard(() -> modelA);
        guard.onModelActive(modelA);
        guard.onModelActive(modelB);
        StalenessCapture capture = guard.capture(java.util.Set.of(target.getId()), null, modelA);

        stackB.execute(rename(other, "Edited by the human on B"));
        stackA.execute(new AgentAuthoredCompoundCommand(rename(target, "Edited by an agent"), null));

        assertFalse("only A's own history decides", guard.vet(capture, null, modelA).stale());
        guard.onModelInactive();
    }

    /**
     * A capture taken on a watched model, vetted against a model with no watch left, cannot prove no
     * human intervened — so a changed target is stale rather than quietly fresh.
     */
    @Test
    public void shouldTreatAMissingTrackAsIntervened_whenTheCaptureHadOne() {
        com.archimatetool.model.IArchimateElement target = actorIn(modelA, "Target");
        ProposalStalenessGuard guard = new ProposalStalenessGuard(() -> modelA);
        guard.onModelActive(modelA);
        StalenessCapture capture = guard.capture(java.util.Set.of(target.getId()), null, modelA);

        guard.onModelClosed(modelA);
        target.setName("Changed while unwatched");

        assertTrue(guard.vet(capture, null, modelA).stale());
    }

    @Test
    public void shouldStopWatchingOnlyTheClosedModel() {
        ProposalStalenessGuard guard = new ProposalStalenessGuard(() -> modelA);
        guard.onModelActive(modelA);
        guard.onModelActive(modelB);

        guard.onModelClosed(modelB);

        assertEquals(1, guard.watchedCount());
        guard.onModelInactive();
        assertEquals(0, guard.watchedCount());
    }

    // ---- stub ---------------------------------------------------------------------------------

    /** Minimal {@link IEditorModelManager} stub — models plus listener registration only. */
    private static final class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final List<PropertyChangeListener> listeners = new ArrayList<>();

        void setModels(List<IArchimateModel> models) {
            this.models = models;
        }

        @Override
        public List<IArchimateModel> getModels() {
            return models;
        }

        @Override
        public void addPropertyChangeListener(PropertyChangeListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removePropertyChangeListener(PropertyChangeListener listener) {
            listeners.remove(listener);
        }

        void firePropertyChange(String propertyName, Object oldValue, Object newValue) {
            PropertyChangeEvent evt = new PropertyChangeEvent(this, propertyName, oldValue, newValue);
            for (PropertyChangeListener listener : new ArrayList<>(listeners)) {
                listener.propertyChange(evt);
            }
        }

        @Override public IArchimateModel createNewModel() { return null; }
        @Override public void registerModel(IArchimateModel model) {}
        @Override public IArchimateModel openModel(File file) { return null; }
        @Override public void openModel(IArchimateModel model) {}
        @Override public IArchimateModel loadModel(File file) { return null; }
        @Override public IArchimateModel load(File file) throws IOException { return null; }
        @Override public boolean closeModel(IArchimateModel model) throws IOException { return false; }
        @Override public boolean closeModel(IArchimateModel model, boolean askSave) throws IOException { return false; }
        @Override public boolean isModelLoaded(File file) { return false; }
        @Override public boolean isModelDirty(IArchimateModel model) { return false; }
        @Override public boolean saveModel(IArchimateModel model) throws IOException { return false; }
        @Override public boolean saveModelAs(IArchimateModel model) throws IOException { return false; }
        @Override public void saveState() throws IOException {}
        @Override public void firePropertyChange(Object source, String prop, Object oldValue, Object newValue) {}
    }
}
