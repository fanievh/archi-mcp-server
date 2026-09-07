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
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.response.ErrorCode;

/**
 * The {@code labelPolicy} contract on {@code auto-layout-and-route}.
 *
 * <p>The load-bearing case is the <strong>refusal</strong>. Flat mode without a {@code targetRating}
 * applies ELK's own edge routes and never runs the label optimizer, so it holds no evidence about
 * where a label can sit. Accepting the policy there would produce a call that looks like it hid
 * labels and did nothing at all — the silent no-op this design explicitly rejected. It must fail
 * loudly instead, and it must keep failing: a future refactor that quietly starts ignoring the
 * parameter would otherwise look green.</p>
 */
public class AutoLayoutAndRouteLabelPolicyTest {

    private static final String SESSION = "auto-layout-label-policy-session";
    private static final String AUTO_HIDE = "auto-hide-on-collision";

    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private CommandStack stack;
    private IArchimateModel model;
    private IArchimateDiagramModel view;

    @Before
    public void setUp() {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Label Policy Fixture");
        model.setId("model-label-policy");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Flow");
        diagrams.getElements().add(view);

        IFolder business = model.getFolder(FolderType.BUSINESS);
        for (int i = 1; i <= 3; i++) {
            IBusinessActor a = factory.createBusinessActor();
            a.setId("actor-" + i);
            a.setName("Actor " + i);
            business.getElements().add(a);
        }
        IArchimateRelationship rel = factory.createAssociationRelationship();
        rel.setId("rel-1");
        rel.setName("flows to");
        rel.setSource((IBusinessActor) business.getElements().get(0));
        rel.setTarget((IBusinessActor) business.getElements().get(1));
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);

        stubModelManager.setModels(List.of(model));

        stack = new CommandStack();
        MutationDispatcher dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                stack.execute(toPlainCompound(command));
            }
            @Override
            protected void dispatchCommand(Command command) {
                stack.execute(toPlainCompound(command));
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

        accessor.addToView(SESSION, view.getId(), "actor-1", 10, 10, 120, 55, false, null, null, null);
        accessor.addToView(SESSION, view.getId(), "actor-2", 400, 10, 120, 55, false, null, null, null);

        // Draw the relationship on the view: auto-route-connections returns early on a view with no
        // connections, which would make the terminals-only guard unreachable and the test vacuous.
        accessor.autoConnectView(SESSION, view.getId(), null, null, null, null, null);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    @Test
    public void shouldRejectAutoHide_whenFlatModeHasNoTargetRatingAndThereforeNoRoutingPass() {
        try {
            accessor.autoLayoutAndRoute(SESSION, view.getId(), "flat", "DOWN", 50, null, AUTO_HIDE);
            fail("flat mode without targetRating runs no label optimizer and must not accept the policy");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue("the error must name the parameter it is rejecting",
                    e.getMessage().contains("labelPolicy"));
            assertNotNull("the caller must be told how to proceed", e.getSuggestedCorrection());
            assertTrue("the remedy must name a path that DOES route",
                    e.getSuggestedCorrection().contains("targetRating")
                            || e.getSuggestedCorrection().contains("grouped"));
        }
    }

    @Test
    public void shouldNotReject_whenLabelPolicyIsOmittedOnTheSamePath() {
        // Default-off: the identical call without the parameter must be unaffected. This is what
        // proves the refusal is scoped to the opt-in and is not a new restriction on existing callers.
        assertNotNull(accessor.autoLayoutAndRoute(SESSION, view.getId(), "flat", "DOWN", 50, null, null));
    }

    @Test
    public void shouldNotReject_whenPolicyIsExplicitlyKeepOnTheSamePath() {
        assertNotNull(accessor.autoLayoutAndRoute(SESSION, view.getId(), "flat", "DOWN", 50, null, "keep"));
    }

    @Test
    public void shouldRejectAnUnrecognisedLabelPolicy_ratherThanSilentlyDefaulting() {
        try {
            accessor.autoLayoutAndRoute(SESSION, view.getId(), "flat", "DOWN", 50, null, "auto-hide");
            fail("an unrecognised labelPolicy must be rejected, not treated as the default");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue(e.getMessage().contains("labelPolicy"));
        }
    }

    @Test
    public void shouldValidateThePolicyBeforeRejectingTheRoutingPass() {
        // Ordering matters: a bad value is a bad value regardless of mode, and the caller should be
        // told which of the two problems they actually have.
        try {
            accessor.autoLayoutAndRoute(SESSION, view.getId(), "flat", "DOWN", 50, null, "nonsense");
            fail("expected rejection");
        } catch (ModelAccessException e) {
            assertTrue("the unrecognised-value error must not be masked by the routing-pass guard",
                    e.getMessage().contains("Invalid labelPolicy"));
        }
    }

    // --- auto-route-connections: terminals-only runs no label optimizer either ---

    @Test
    public void shouldRejectAutoHide_whenAutoRouteRunsInTerminalsOnlyMode() {
        // terminals-only adjusts terminal segments and never invokes the label optimizer, so it
        // holds no evidence about label positions. Accepting the policy there would be the same
        // silent no-op the flat-layout path already refuses — this pins the third such path, which
        // the original design ruling did not enumerate.
        try {
            accessor.autoRouteConnections(SESSION, view.getId(), null, "orthogonal", false,
                    false, 20, 50, "terminals-only", true, AUTO_HIDE);
            fail("terminals-only runs no label optimizer and must not accept the policy");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue(e.getMessage().contains("labelPolicy"));
            assertTrue("the error must name the mode it is refusing",
                    e.getMessage().contains("terminals-only"));
            assertNotNull("the caller must be told how to proceed", e.getSuggestedCorrection());
        }
    }

    @Test
    public void shouldNotRejectTerminalsOnly_whenLabelPolicyIsOmitted() {
        // The refusal is scoped to the opt-in: existing terminals-only callers are unaffected.
        assertNotNull(accessor.autoRouteConnections(SESSION, view.getId(), null, "orthogonal", false,
                false, 20, 50, "terminals-only", true, null));
    }

    /** Minimal in-memory model manager — the local idiom the sibling accessor tests use. */
    private static class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final List<PropertyChangeListener> listeners = new ArrayList<>();

        void setModels(List<IArchimateModel> models) { this.models = models; }

        @Override public List<IArchimateModel> getModels() { return models; }
        @Override public void addPropertyChangeListener(PropertyChangeListener l) { listeners.add(l); }
        @Override public void removePropertyChangeListener(PropertyChangeListener l) { listeners.remove(l); }
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
