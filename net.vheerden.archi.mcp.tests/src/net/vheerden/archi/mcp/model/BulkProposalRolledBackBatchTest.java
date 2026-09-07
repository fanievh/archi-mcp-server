package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.ViewGroupDto;

/**
 * Pins that a {@code bulk-mutate} proposal is refused once the batch its operations were resolved
 * against has been rolled back.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code bulk-mutate} is one of fourteen tools whose approval path stores a <em>frozen</em>
 * compound: the command built during the prepare phase is handed back verbatim on approve, rather
 * than the prepare being re-run. That is deliberate and documented — re-running a whole bulk pass
 * could produce something other than what the human reviewed, so those gates are honest only as
 * "apply exactly this, or reject it".</p>
 *
 * <p>What made the freeze unsafe was the tracked set the staleness guard vets on approve.
 * {@code bulk-mutate} was the only one of the fourteen that did not build that set by walking the
 * compound's child commands; it fingerprinted each operation's own <em>result</em> id instead. A
 * bulk of creates produces ids that resolve nowhere at propose time, so every one of them was
 * dropped and the guard vetted an empty set — it ran, found nothing to check, and returned fresh.
 * Roll the enclosing batch back in the review window and the frozen compound then executed against
 * a container that no longer existed, reporting {@code action: "placed"} for a view that gained
 * nothing.</p>
 *
 * <h2>Both routes</h2>
 *
 * <p>The hazard was described as needing the human to toggle approval on <em>during</em> an open
 * batch. It does not: an approved proposal joins the open batch's queue, so a session with approval
 * on throughout reaches the same state with no mode change at all. Both are pinned below, because a
 * fix that closed only the toggle route would leave the ordinary one open.</p>
 */
public class BulkProposalRolledBackBatchTest {

    private static final String SESSION = "bulk-proposal-rollback-session";

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private CommandStack stack;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private IBusinessActor actor;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Bulk Proposal Rollback Fixture");
        model.setId("model-bulk-proposal-rollback");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Target");
        diagrams.getElements().add(view);

        IFolder business = model.getFolder(FolderType.BUSINESS);
        actor = factory.createBusinessActor();
        actor.setId("actor-1");
        actor.setName("A");
        business.getElements().add(actor);

        stubModelManager.setModels(List.of(model));

        stack = new CommandStack();
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                stack.execute(new AgentAuthoredCompoundCommand(toPlainCompound(command)));
            }
            @Override
            protected void dispatchCommand(Command command) {
                stack.execute(new AgentAuthoredCompoundCommand(toPlainCompound(command)));
            }
            private Command toPlainCompound(Command command) {
                if (command instanceof CompoundCommand compound) {
                    CompoundCommand plain = new CompoundCommand(compound.getLabel());
                    for (Object child : compound.getCommands()) {
                        plain.add(toPlainCompound((Command) child));
                    }
                    return plain;
                }
                return command;
            }
        };
        dispatcher.setApprovalModeProvider(() -> false);
        accessor = new ArchiModelAccessorImpl(stubModelManager, dispatcher);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Route B — approval on throughout. No mode change anywhere: an approved proposal simply joins
    // the open batch's queue, which is what puts a queued-and-then-rolled-back parent in reach.
    // ------------------------------------------------------------------------------------------

    @Test
    public void shouldRefuseTheBulkProposalAsStale_whenTheBatchThatQueuedItsParentRolledBack()
            throws Exception {
        dispatcher.setApprovalModeProvider(() -> true);
        dispatcher.beginBatch(SESSION, "queue a group under approval");

        String queuedGroup = approveTheGroupProposal();

        BulkMutationResult bulk = placeActorInside(queuedGroup);
        String bulkProposalId = bulk.proposalContext().proposalId();

        dispatcher.endBatch(SESSION, false);

        assertBulkProposalRefused(bulkProposalId, "Queued");
        assertEquals("and the view must have gained nothing", 0, view.getChildren().size());
    }

    // ------------------------------------------------------------------------------------------
    // Route A — approval toggled on mid-batch, the originally described route. It reaches the same
    // frozen compound by a different door and must refuse identically.
    // ------------------------------------------------------------------------------------------

    @Test
    public void shouldRefuseTheBulkProposalAsStale_whenApprovalWasToggledOnMidBatch()
            throws Exception {
        dispatcher.beginBatch(SESSION, "queue a group, then turn approval on");

        String queuedGroup = accessor.addGroupToView(SESSION, view.getId(), "Queued",
                0, 0, 400, 400, null, null, null).entity().viewObjectId();

        dispatcher.setApprovalModeProvider(() -> true);

        BulkMutationResult bulk = placeActorInside(queuedGroup);
        String bulkProposalId = bulk.proposalContext().proposalId();

        dispatcher.setApprovalModeProvider(() -> false);
        dispatcher.endBatch(SESSION, false);

        assertBulkProposalRefused(bulkProposalId, "Queued");
        assertEquals("and the view must have gained nothing", 0, view.getChildren().size());
    }

    // ------------------------------------------------------------------------------------------
    // The negative control. Widening a tracked set makes more ways to reject-stale, so a pin on the
    // rollback alone cannot see a proposal that now refuses when it should still apply.
    // ------------------------------------------------------------------------------------------

    @Test
    public void shouldStillApproveTheBulkProposal_whenTheEnclosingBatchCommits() throws Exception {
        dispatcher.setApprovalModeProvider(() -> true);
        dispatcher.beginBatch(SESSION, "queue a group under approval");

        String queuedGroup = approveTheGroupProposal();

        BulkMutationResult bulk = placeActorInside(queuedGroup);
        String bulkProposalId = bulk.proposalContext().proposalId();

        // Approve the bulk while the batch is still open — the group is queued, not removed.
        ApprovalResult approved = dispatcher.approveProposal(SESSION, bulkProposalId);
        assertNotNull("a proposal whose parent is still queued must approve, not reject", approved);

        dispatcher.setApprovalModeProvider(() -> false);
        dispatcher.endBatch(SESSION, true);

        assertEquals("the committed batch must land the group on the view",
                1, view.getChildren().size());
    }

    // ---- helpers ------------------------------------------------------------------------------

    /**
     * Adds a group under approval mode and approves it, so it ends up on the open batch's queue —
     * the route-B step that needs no mode change.
     *
     * <p>The id returned is the <em>approved</em> one, not the proposed one. Approving re-runs the
     * prepare against the current model, which builds a fresh group object with a fresh id; the
     * propose-time id names an object that was never queued and would fail to resolve for reasons
     * that have nothing to do with what this class is testing.</p>
     */
    private String approveTheGroupProposal() throws Exception {
        MutationResult<ViewGroupDto> proposed = accessor.addGroupToView(
                SESSION, view.getId(), "Queued", 0, 0, 400, 400, null, null, null);
        assertTrue("the group add must be stored as a proposal", proposed.isProposal());
        ApprovalResult approved = dispatcher.approveProposal(
                SESSION, proposed.proposalContext().proposalId());
        assertNotNull("the group proposal must approve", approved);
        assertTrue("the approved result must carry the group it queued",
                approved.entity() instanceof ViewGroupDto);
        return ((ViewGroupDto) approved.entity()).viewObjectId();
    }

    /** A single-operation bulk placing the actor inside the given (queued) container. */
    private BulkMutationResult placeActorInside(String parentViewObjectId) {
        BulkMutationResult bulk = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("add-to-view",
                        Map.of("viewId", view.getId(), "elementId", actor.getId(),
                                "x", 20, "y", 20, "width", 100, "height", 60,
                                "parentViewObjectId", parentViewObjectId))),
                "place inside the queued group", false);
        assertNotNull("the bulk must be stored as a proposal under approval mode",
                bulk.proposalContext());
        return bulk;
    }

    private void assertBulkProposalRefused(String proposalId, String removedObjectName) {
        try {
            ApprovalResult applied = dispatcher.approveProposal(SESSION, proposalId);
            fail("expected the proposal to be refused as stale after the batch rolled back, but it "
                    + "reported success: " + applied.entity());
        } catch (MutationException e) {
            assertTrue("the refusal must name the object that went away, not just say 'stale' — a "
                    + "human deciding whether to re-run needs to know what changed underneath: "
                    + e.getMessage(),
                    e.getMessage().contains(removedObjectName));
        }
    }

    // ---- test plumbing ------------------------------------------------------------------------

    private static class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final List<PropertyChangeListener> listeners = new ArrayList<>();

        void setModels(List<IArchimateModel> models) {
            this.models = models;
        }

        @Override
        public List<IArchimateModel> getModels() { return models; }

        @Override
        public void addPropertyChangeListener(PropertyChangeListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removePropertyChangeListener(PropertyChangeListener listener) {
            listeners.remove(listener);
        }

        @Override public IArchimateModel createNewModel() { return null; }
        @Override public void registerModel(IArchimateModel m) {}
        @Override public IArchimateModel openModel(File file) { return null; }
        @Override public void openModel(IArchimateModel m) {}
        @Override public IArchimateModel loadModel(File file) { return null; }
        @Override public IArchimateModel load(File file) throws IOException { return null; }
        @Override public boolean closeModel(IArchimateModel m) throws IOException { return false; }
        @Override public boolean closeModel(IArchimateModel m, boolean askSave) throws IOException { return false; }
        @Override public boolean isModelLoaded(File file) { return false; }
        @Override public boolean isModelDirty(IArchimateModel m) { return false; }
        @Override public boolean saveModel(IArchimateModel m) throws IOException { return false; }
        @Override public boolean saveModelAs(IArchimateModel m) throws IOException { return false; }
        @Override public void saveState() throws IOException {}
        @Override public void firePropertyChange(Object src, String p, Object oldV, Object newV) {}
    }
}
