package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.common.notify.impl.AdapterImpl;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.model.commands.NonNotifyingCompoundCommand;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IProfile;

import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.response.dto.MergeClusterRequest;
import net.vheerden.archi.mcp.server.ApprovalService;

/**
 * An approved card whose rebuilt command is a compound, every part of which declines as it runs because
 * the model moved between the rebuild and the write. Nothing was written, so the card goes back, the
 * version stays, and no undo entry is left. When a part ran, the card is not put back.
 *
 * <p>The dispatcher runs the production stack-entry steps on a real {@link CommandStack}, with Archi's
 * notifying compound rebuilt as a plain one over the same instances, so no {@code Display} is needed.</p>
 */
public class ApprovedCompoundDeclineTest {

    private static final String SESSION = "default";

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private final MergeElementsCommandTest.StubEditorModelManager models =
            new MergeElementsCommandTest.StubEditorModelManager();
    private IArchimateModel model;
    private CommandStack stack;
    private Command earlier;
    private MutationDispatcher dispatcher;
    private ArchiModelAccessorImpl accessor;
    private ApprovalService approvalService;
    /** Runs on the dispatch path, after the approval's rebuild and before its command executes. */
    private Runnable beforeDispatch;
    private IArchimateElement first;
    private IArchimateElement second;
    private IProfile vip;
    private IProfile other;

    @Before
    public void setUp() {
        model = factory.createArchimateModel();
        model.setId("model-approved-compound-decline");
        model.setDefaults();
        models.setModels(List.of(model));
        stack = new CommandStack();
        model.setAdapter(CommandStack.class, stack);
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                executeEntry(stack, agentEntry(headless(command)));
            }

            @Override
            protected void dispatchCommand(Command command) {
                if (beforeDispatch != null) {
                    beforeDispatch.run();
                }
                executeEntry(stack, agentEntry(headless(command)));
            }
        };
        dispatcher.setApprovalModeProvider(() -> true);
        accessor = new ArchiModelAccessorImpl(models, dispatcher);
        dispatcher.onModelActive(model);
        approvalService = new ApprovalService(dispatcher);

        first = actor("first");
        second = actor("second");
        vip = profile("VIP");
        other = profile("Other");
        first.getProfiles().add(vip);
        second.getProfiles().add(vip);

        // One ordinary entry first, saved, so "the stack is as it was" is not read off an empty stack.
        IArchimateElement before = actor("earlier");
        model.getFolder(FolderType.BUSINESS).getElements().remove(before);
        MutationDispatcher.executeEntry(stack, dispatcher.agentEntry(new Command("Add earlier") {
            @Override
            public void execute() {
                model.getFolder(FolderType.BUSINESS).getElements().add(before);
            }
        }));
        stack.markSaveLocation();
        earlier = stack.getUndoCommand();
    }

    @After
    public void tearDown() {
        accessor.dispose();
    }

    @Test
    public void shouldKeepTheCard_andLeaveNoEntryOrVersion_whenEveryPartOfAnApprovedForceDeleteDeclined() {
        String proposalId = proposeForceDelete();
        beforeDispatch = () -> {
            first.getProfiles().add(other);
            second.getProfiles().add(other);
        };
        String version = accessor.getModelVersion();

        try {
            approvalService.approve(SESSION, proposalId);
            fail("an approval whose every part declined must not report success");
        } catch (MutationException e) {
            assertTrue(e.getMessage(), e.getMessage().endsWith(" Nothing was changed. Reject this proposal and "
                    + "ask the agent to make the change again."));
        }

        assertEquals("both concepts still carry it", List.of(vip, other), first.getProfiles());
        assertEquals(List.of(vip, other), second.getProfiles());
        assertTrue("the specialization is still in the model", model.getProfiles().contains(vip));
        assertEquals("the card is put back", 1, dispatcher.getPendingProposalDtos(SESSION).size());
        assertEquals("no new version", version, accessor.getModelVersion());
        assertSame("the undo entry is still the earlier one", earlier, stack.getUndoCommand());
        assertEquals(1, stack.getCommands().length);
        assertFalse("the model is not marked changed", stack.isDirty());
    }

    @Test
    public void shouldNotRestoreTheCard_whenOnePartOfAnApprovedForceDeleteRanAndTheRestDeclined() {
        String proposalId = proposeForceDelete();
        beforeDispatch = () -> first.getProfiles().add(other);
        String version = accessor.getModelVersion();

        try {
            approvalService.approve(SESSION, proposalId);
            fail("an approval whose delete declined must not report success");
        } catch (MutationException e) {
            assertTrue(e.getMessage(), e.getMessage().endsWith(" Part of this change may have been applied. "
                    + "Check the model in Archi before going on."));
        }

        assertEquals("fixture check: the other usage was cleared", List.of(), second.getProfiles());
        assertTrue("the card is not put back", dispatcher.getPendingProposalDtos(SESSION).isEmpty());
        assertFalse("a new version", version.equals(accessor.getModelVersion()));
        assertEquals("one new entry", 2, stack.getCommands().length);
        assertTrue(stack.isDirty());
    }

    /**
     * An approved clone whose source was removed after the card was rebuilt is declined before its create
     * attaches the clone, so nothing was written and the card goes back.
     */
    @Test
    public void shouldKeepTheCard_andLeaveNoEntryOrVersion_whenAnApprovedClonesSourceWentBeforeItRan() {
        IFolder views = model.getFolder(FolderType.DIAGRAMS);
        IArchimateDiagramModel source = factory.createArchimateDiagramModel();
        source.setId("view-source");
        source.setName("Source");
        views.getElements().add(source);
        MutationResult<?> proposed = accessor.cloneView(SESSION, "view-source", "Copy", null);
        assertTrue("approval mode must queue a proposal, not apply", proposed.isProposal());
        beforeDispatch = () -> views.getElements().remove(source);
        String version = accessor.getModelVersion();

        try {
            approvalService.approve(SESSION, proposed.proposalContext().proposalId());
            fail("an approved clone that copied nothing must not report success");
        } catch (MutationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("'Copy' was not created: the view 'Source' it was "
                    + "to be copied from was removed from the model"));
            assertTrue(e.getMessage(), e.getMessage().endsWith(" Nothing was changed. Reject this proposal and "
                    + "ask the agent to make the change again."));
        }

        assertTrue("no clone", views.getElements().isEmpty());
        assertEquals("the card is put back", 1, dispatcher.getPendingProposalDtos(SESSION).size());
        assertEquals("no new version", version, accessor.getModelVersion());
        assertSame("the undo entry is still the earlier one", earlier, stack.getUndoCommand());
        assertEquals(1, stack.getCommands().length);
        assertFalse("the model is not marked changed", stack.isDirty());
    }

    /**
     * An approved merge that fails part-way and cannot put back all of its own writes fails the approval,
     * leaves no undo entry, and keeps its card off the queue, since part of it may be in the model.
     */
    @Test
    public void shouldFailTheApproval_andLeaveNoEntry_whenAnApprovedMergeCouldNotUndoItsOwnWrites() {
        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-a");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        IArchimateElement t = actor("t");
        IArchimateElement s = actor("s");
        place(view, s, "box-1");
        place(view, s, "box-2");
        place(view, t, "box-t");
        MutationResult<?> proposed = accessor.mergeElements(SESSION,
                List.of(new MergeClusterRequest("t", List.of("s"))), null, null, false, false);
        assertTrue("approval mode must queue a proposal, not apply", proposed.isProposal());
        beforeDispatch = () -> view.eAdapters().add(new AdapterImpl() {
            private int removes;
            private Object held;

            @Override
            public void notifyChanged(Notification n) {
                if (n.getEventType() == Notification.REMOVE && ++removes == 2) {
                    held = n.getOldValue();
                    throw new IllegalStateException("listener");
                }
                if (n.getEventType() == Notification.ADD && held != null && n.getNewValue() == held) {
                    throw new IllegalStateException("listener");
                }
            }
        });
        String version = accessor.getModelVersion();

        try {
            approvalService.approve(SESSION, proposed.proposalContext().proposalId());
            fail("an approved merge that left part of itself must not report success");
        } catch (MutationException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("merge-elements stopped and could not undo all of "
                    + "its own changes: listener"));
            assertTrue(e.getMessage(), e.getMessage().contains("may still be in the model"));
        }

        assertTrue("the card is not put back", dispatcher.getPendingProposalDtos(SESSION).isEmpty());
        assertSame("the undo entry is still the earlier one", earlier, stack.getUndoCommand());
        assertEquals(1, stack.getCommands().length);
        assertFalse("the model may have changed: a new version", version.equals(accessor.getModelVersion()));
    }

    /**
     * An approved removal whose box went after the card was rebuilt throws as it runs: the approval fails
     * saying part of it may still be in the model, leaves no undo entry, and keeps its card off the queue.
     */
    @Test
    public void shouldFailTheApproval_andLeaveNoEntry_whenAnApprovedRemoveThrowsAsItRuns() {
        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-a");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        place(view, actor("s"), "box-1");
        MutationResult<?> proposed = accessor.removeFromView(SESSION, "view-a", "box-1");
        assertTrue("approval mode must queue a proposal, not apply", proposed.isProposal());
        beforeDispatch = () -> view.getChildren().clear();
        String version = accessor.getModelVersion();

        try {
            approvalService.approve(SESSION, proposed.proposalContext().proposalId());
            fail("an approval whose command threw must not report success");
        } catch (MutationException e) {
            assertEquals("This change failed unexpectedly. Part of what it wrote may still be in the model, and "
                    + "Undo will not remove it; it does not mark the model as changed either. Check the model in "
                    + "Archi before going on.", e.getMessage());
        }

        assertTrue("the card is not put back", dispatcher.getPendingProposalDtos(SESSION).isEmpty());
        assertSame("the undo entry is still the earlier one", earlier, stack.getUndoCommand());
        assertEquals(1, stack.getCommands().length);
        assertFalse("the model may have changed: a new version", version.equals(accessor.getModelVersion()));
    }

    @Test
    public void shouldNotSuggestARetry_whenAnApprovedRemoveThrowsAsItRuns() {
        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-a");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        place(view, actor("s"), "box-1");
        MutationResult<?> proposed = accessor.removeFromView(SESSION, "view-a", "box-1");
        beforeDispatch = () -> view.getChildren().clear();

        try {
            approvalService.approve(SESSION, proposed.proposalContext().proposalId());
            fail("an approval whose command threw must not report success");
        } catch (MutationException e) {
            assertEquals("Part of this change may still be in the model and Undo will not remove it. Re-read the "
                    + "elements with get-element, and the views that showed them, before going on.",
                    e.getSuggestedCorrection());
        }
    }

    private void place(IArchimateDiagramModel view, IArchimateElement element, String id) {
        IDiagramModelArchimateObject box = factory.createDiagramModelArchimateObject();
        box.setId(id);
        box.setArchimateElement(element);
        box.setBounds(0, 0, 120, 55);
        view.getChildren().add(box);
    }

    private String proposeForceDelete() {
        MutationResult<?> result = accessor.deleteSpecialization(SESSION, "VIP", "BusinessActor", true);
        assertTrue("approval mode must queue a proposal, not apply", result.isProposal());
        return result.proposalContext().proposalId();
    }

    /**
     * Archi's notifying compound needs the editor's model manager, which the headless lane lacks: run its
     * children, the same instances, in a plain compound.
     */
    private static Command headless(Command command) {
        if (command.getClass() != NonNotifyingCompoundCommand.class) {
            return command;
        }
        CompoundCommand plain = new CompoundCommand(command.getLabel());
        for (Object child : ((CompoundCommand) command).getCommands()) {
            plain.add(headless((Command) child));
        }
        return plain;
    }

    private IProfile profile(String name) {
        IProfile profile = factory.createProfile();
        profile.setName(name);
        profile.setConceptType("BusinessActor");
        model.getProfiles().add(profile);
        return profile;
    }

    private IArchimateElement actor(String id) {
        IArchimateElement element = factory.createBusinessActor();
        element.setId(id);
        element.setName(id);
        model.getFolder(FolderType.BUSINESS).getElements().add(element);
        return element;
    }
}
