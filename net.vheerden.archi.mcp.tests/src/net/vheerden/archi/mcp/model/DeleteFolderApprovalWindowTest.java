package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.response.dto.ProposalDto;
import net.vheerden.archi.mcp.server.ApprovalService;

/**
 * Pins what the human is told when the model moves <em>underneath a pending folder-delete proposal</em>
 * — the review window between reading an approval card and clicking Approve.
 *
 * <p><strong>One human gesture, two paths.</strong> Both tests below stage the identical gesture: a
 * proposal to delete the folder {@code Ops} is queued, and while the card sits in the dock the human
 * drags more content into {@code Ops} through Archi's own UI (a real, non-agent {@code CommandStack}
 * command — so {@code humanIntervened} is genuinely true, and the folder's <em>attribute</em>
 * fingerprint is genuinely unchanged, because containment is an {@code EReference} and is deliberately
 * not fingerprinted). The {@code force} flag then splits the outcome:</p>
 * <ul>
 *   <li>{@code force: false} — the rebuild's {@code prepareDeleteFolder} refuses with
 *       {@code FOLDER_NOT_EMPTY}, which carries its own actionable remedy. The refusal is correct; the
 *       question this test pins is whether the human is told the <em>real reason</em> or a generic
 *       "something changed" sentence that destroys the remedy.</li>
 *   <li>{@code force: true} — the rebuild recomputes a <em>larger</em> cascade than the card described.
 *       The question this test pins is whether more can be deleted than the human authorised.</li>
 * </ul>
 *
 * <p><strong>Driven through {@link ApprovalService}, not through {@code ProposalBuilder}.</strong>
 * Asserting the builder's throw would prove the message was <em>produced</em>, not that it
 * <em>reaches</em> the seam the Pending Approvals dock calls — {@code doApprove} renders exactly the
 * {@link MutationException#getMessage()} that comes back from {@link ApprovalService#approve}. The
 * whole chain is real: real EMF model, real accessor, real proposal, real deferred rebuild handle, real
 * {@code prepareDeleteFolder}.</p>
 *
 * <p>Pure standard JUnit (no OSGi / Plug-in Test) — the model carries a {@link CommandStack} via
 * {@code setAdapter} purely to drive the staleness guard's listener headlessly.</p>
 */
public class DeleteFolderApprovalWindowTest {

    private static final String SESSION = "default";

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private ApprovalService approvalService;
    private IArchimateModel model;
    private CommandStack stack;
    private IFolder ops;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Delete-Folder Approval Window Fixture");
        model.setId("model-delete-folder-approval-window");
        model.setDefaults();

        // A user subfolder: prepareDeleteFolder refuses folders whose container is the model itself.
        ops = factory.createFolder();
        ops.setName("Ops");
        ops.setId("folder-ops");
        model.getFolder(FolderType.BUSINESS).getFolders().add(ops);

        // The two elements the human will read on the card.
        ops.getElements().add(actor("actor-1", "Payments Ops"));
        ops.getElements().add(actor("actor-2", "Card Ops"));

        // A real, Display-free CommandStack so a human edit during the review window is observable.
        stack = new CommandStack();
        model.setAdapter(CommandStack.class, stack);

        stubModelManager.setModels(List.of(model));
        accessor = createApprovalModeAccessor(model);
        dispatcher.onModelActive(model);
        approvalService = new ApprovalService(dispatcher);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ---- force: false — the right refusal, and the reason the human is given for it ----

    @Test
    public void shouldTellTheHumanTheRealReason_whenContentArrivesUnderANonForceDelete() {
        // Propose while the folder is EMPTY, which is the only way a non-force delete prepares at all.
        ops.getElements().clear();
        String proposalId = propose(false);

        // The human drops one element into Ops while the card sits in the dock.
        humanDropsIntoOps(actor("actor-late", "Settlement Ops"));

        try {
            approvalService.approve(SESSION, proposalId);
            fail("a non-force delete of a now-non-empty folder must be refused");
        } catch (MutationException e) {
            // The refusal is right either way. What is pinned here is WHICH sentence reaches the dock:
            // the domain reason with its remedy, not the generic "a targeted object was changed" line.
            assertEquals("the FOLDER_NOT_EMPTY sentence reaches the dock verbatim",
                    "Folder 'Ops' is not empty: 1 element(s), 0 subfolder(s). "
                            + "Use force: true to cascade-delete all contents.",
                    e.getMessage());
        }
        assertTrue("nothing was deleted", model.getFolder(FolderType.BUSINESS).getFolders().contains(ops));
    }

    // ---- force: true — the card's blast radius versus the one that actually runs ----

    @Test
    public void shouldNotDeleteMoreThanTheCardDescribed_whenContentArrivesUnderAForceDelete() {
        String proposalId = propose(true);

        // What the human reads before deciding. Both the visible sentence and the raw payload say TWO.
        ProposalDto card = onlyCard();
        assertEquals("Delete folder: Ops (force cascade: 2 elements)", card.description());
        assertEquals("the card's raw payload agrees with its sentence",
                2, card.proposedChanges().get("elementsRemoved"));

        // The human drags three more elements into Ops while the card sits in the dock. The folder's
        // attribute fingerprint does not change (containment is an EReference), so the staleness guard
        // returns FRESH and the deferred handle re-prepares a cascade over FIVE elements.
        humanDropsIntoOps(actor("actor-3", "Fraud Ops"),
                actor("actor-4", "Treasury Ops"),
                actor("actor-5", "Clearing Ops"));
        assertEquals("fixture check: the cascade the rebuild will compute is now larger",
                5, ops.getElements().size());

        // THE PINNED CONDITION: the number the card described versus the number actually removed. A
        // silent success here means the human authorised the deletion of 2 elements and 5 were deleted.
        try {
            approvalService.approve(SESSION, proposalId);
            fail("approving must not delete a larger cascade than the card described");
        } catch (MutationException e) {
            assertEquals("the human is told exactly what changed and by how much",
                    "This proposal can no longer be applied as reviewed: it was approved for 2 elements, "
                            + "but applying it now would remove 5 elements. "
                            + "Reject it and ask the agent to retry.",
                    e.getMessage());
        }

        assertTrue("the folder is untouched", model.getFolder(FolderType.BUSINESS).getFolders().contains(ops));
        assertEquals("no element was removed", 5, ops.getElements().size());
        assertEquals("the card stays on screen so the human can read the reason and Reject",
                1, dispatcher.getPendingProposalDtos(SESSION).size());
    }

    @Test
    public void shouldApplyNormally_whenTheCascadeStillMatchesTheCard() {
        // The non-regression twin: an untouched review window must still approve cleanly. Without this,
        // the check above could "pass" by refusing everything.
        String proposalId = propose(true);
        assertEquals("Delete folder: Ops (force cascade: 2 elements)", onlyCard().description());

        approvalService.approve(SESSION, proposalId);

        assertFalse("the folder is deleted as reviewed",
                model.getFolder(FolderType.BUSINESS).getFolders().contains(ops));
        assertTrue("the queue drained", dispatcher.getPendingProposalDtos(SESSION).isEmpty());
    }

    @Test
    public void shouldDetectDivergence_regardlessOfWhoCausedIt() {
        // The count check is deliberately cause-agnostic. The staleness guard forgives an intervening
        // AGENT command because a re-preparing handle re-resolves against it — but re-resolving is
        // exactly what grows the cascade here, so forgiveness must not extend to the card's numbers.
        // Nobody is reviewing a second time either way: the human is still reading a card that says 2.
        String proposalId = propose(true);

        ops.getElements().add(actor("actor-agent", "Agent-added Ops"));  // no CommandStack event at all

        try {
            approvalService.approve(SESSION, proposalId);
            fail("a grown cascade must be refused whoever grew it");
        } catch (MutationException e) {
            assertTrue("names the growth", e.getMessage().contains("would remove 3 elements"));
        }
    }

    /**
     * A COMPENSATING SWAP IS NOT DETECTED. Recorded, not repaired.
     *
     * <p>The approve-time check compares the card's cascade <em>counts</em> against the rebuilt
     * command's. If one element leaves the folder and another arrives during the review window, the
     * count is unchanged and the approval proceeds — destroying an element that was never on the card.
     *
     * <p>Neither half of the existing machinery can see it. The counts are equal, so the card check
     * passes. And the staleness guard cannot help even if the departed element were tracked: it checks
     * whether a target still <em>exists</em>, and an element moved to another folder still resolves
     * perfectly well. Catching this needs the cascade's <em>set membership</em> captured at propose and
     * re-derived at approve — new state and a new traversal, not a tightening of either existing check.
     *
     * <p>Asserted as it stands so nothing above reads as coverage of it.</p>
     */
    @Test
    public void shouldNotDetectACompensatingSwap_knownResidual() {
        String proposalId = propose(true);
        assertEquals("Delete folder: Ops (force cascade: 2 elements)", onlyCard().description());

        IBusinessActor departing = (IBusinessActor) ops.getElements().get(0);
        IBusinessActor arriving = actor("actor-swapped-in", "Never On The Card");
        stack.execute(new Command("Swap folder contents") {
            @Override
            public void execute() {
                ops.getElements().remove(departing);
                model.getFolder(FolderType.BUSINESS).getElements().add(departing);
                ops.getElements().add(arriving);
            }
        });
        assertEquals("fixture check: the count is unchanged, the set is not", 2, ops.getElements().size());

        approvalService.approve(SESSION, proposalId);

        assertFalse("the folder is deleted", model.getFolder(FolderType.BUSINESS).getFolders().contains(ops));
        assertNull("an element the human never saw on the card was deleted with it",
                arriving.eContainer());
    }

    @Test
    public void shouldNotClaimReviewedOrReject_onARePreparingProposal() {
        // delete-folder stores a handle that re-invokes prepareDeleteFolder at approve, so its card must
        // NOT carry the reviewed-or-reject statement the frozen-compound tools carry. The statement is
        // only honest where nothing is recomputed; claiming it here would be the inverse lie.
        propose(true);

        assertEquals("Folder ready for deletion.", onlyCard().validationSummary());
    }

    // ---- helpers ----

    /** The single queued approval card, as the dock would read it. */
    private ProposalDto onlyCard() {
        List<ProposalDto> pending = dispatcher.getPendingProposalDtos(SESSION);
        assertEquals("exactly one proposal queued", 1, pending.size());
        return pending.get(0);
    }

    /** Queues a delete-folder proposal for {@link #ops} and returns its id. */
    private String propose(boolean force) {
        MutationResult<?> result = accessor.deleteFolder(SESSION, ops.getId(), force);
        assertTrue("approval mode must queue a proposal, not apply", result.isProposal());
        ProposalContext ctx = result.proposalContext();
        assertNotNull(ctx);
        return ctx.proposalId();
    }

    /**
     * Simulates the human dragging content into {@code Ops} in Archi's model tree: a real command on the
     * real {@link CommandStack} that is NOT an {@code AgentAuthoredCommand}, so the staleness guard sees a
     * genuine human intervention. The folder's own attributes are untouched by design — that is precisely
     * the state under test.
     */
    private void humanDropsIntoOps(IBusinessActor... arrivals) {
        stack.execute(new Command("Drag into Ops") {
            @Override
            public void execute() {
                for (IBusinessActor arrival : arrivals) {
                    ops.getElements().add(arrival);
                }
            }
        });
    }

    private IBusinessActor actor(String id, String name) {
        IBusinessActor a = factory.createBusinessActor();
        a.setId(id);
        a.setName(name);
        return a;
    }

    private ArchiModelAccessorImpl createApprovalModeAccessor(IArchimateModel testModel) {
        dispatcher = new MutationDispatcher(() -> testModel) {
            @Override
            public void dispatchImmediate(Command command) {
                executeDecomposed(command);
            }
            @Override
            protected void dispatchCommand(Command command) {
                executeDecomposed(command);
            }
            private void executeDecomposed(Command command) {
                if (command instanceof CompoundCommand compound) {
                    for (Object cmd : compound.getCommands()) {
                        executeDecomposed((Command) cmd);
                    }
                } else {
                    command.execute();
                }
            }
        };
        dispatcher.setApprovalModeProvider(() -> true);
        return new ArchiModelAccessorImpl(stubModelManager, dispatcher);
    }

    private static class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final List<PropertyChangeListener> listeners = new ArrayList<>();

        void setModels(List<IArchimateModel> models) { this.models = models; }

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
