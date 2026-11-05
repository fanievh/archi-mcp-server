package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelObject;

import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.model.routing.RoutingPipeline;
import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;

/**
 * A headless model, view, real GEF {@link CommandStack}, dispatcher and accessor for driving a
 * proposal through review and approval, with a "human" who edits the model on the same stack.
 *
 * <p>The human is any command that is not an {@link AgentAuthoredCommand}, executed on the model's
 * stack, which the staleness guard watches. Editing the EMF object without a command would never
 * advance the guard's last-human sequence, so it would exercise the agent-only arm instead.</p>
 *
 * <p>The dispatcher wraps what it runs in {@link AgentAuthoredCompoundCommand} over a plain GEF
 * compound, the idiom of {@code SelfConnectionAccessorTest}: a non-notifying compound cannot
 * class-initialise without a display.</p>
 */
final class ApprovalStalenessFixture {

    static final String SESSION = "approval-staleness-session";

    final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    final IArchimateModel model;
    final IArchimateDiagramModel view;
    final CommandStack stack = new CommandStack();
    final MutationDispatcher dispatcher;
    final ArchiModelAccessorImpl accessor;
    boolean approvalRequired;

    ApprovalStalenessFixture() {
        model = factory.createArchimateModel();
        model.setId("model-approval-staleness");
        model.setDefaults();
        view = factory.createArchimateDiagramModel();
        view.setId("view-approval");
        view.setName("Approval");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        model.setAdapter(CommandStack.class, stack);
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                stack.execute(new AgentAuthoredCompoundCommand(toPlainCompound(command)));
            }
            @Override
            protected void dispatchCommand(Command command) {
                stack.execute(new AgentAuthoredCompoundCommand(toPlainCompound(command)));
            }
        };
        dispatcher.setApprovalModeProvider(() -> approvalRequired);
        StubEditorModelManager manager = new StubEditorModelManager();
        manager.models = List.of(model);
        accessor = new ArchiModelAccessorImpl(manager, dispatcher);
        dispatcher.onModelActive(model);
    }

    void dispose() {
        accessor.dispose();
    }

    // ---- the standard pair: A [400,0,120,60] -> B [700,200,120,60], no bendpoints --------------

    record Pair(IDiagramModelArchimateObject a, IDiagramModelArchimateObject b,
            IDiagramModelArchimateConnection connection) {}

    Pair pair() {
        IArchimateElement ea = element("el-a", "Ledger");
        IArchimateElement eb = element("el-b", "Billing");
        IDiagramModelArchimateObject a = object("vo-a", ea, view, 400, 0, 120, 60);
        IDiagramModelArchimateObject b = object("vo-b", eb, view, 700, 200, 120, 60);
        return new Pair(a, b, connection("conn-ab", relationship("rel-ab", ea, eb), a, b));
    }

    // ---- proposals --------------------------------------------------------------------------

    /** Proposes auto-route of one connection ({@code mode} null for the full route) and returns its id. */
    String proposeRoute(String connectionId, String mode) {
        approvalRequired = true;
        MutationResult<AutoRouteResultDto> result;
        try {
            result = accessor.autoRouteConnections(SESSION, view.getId(), List.of(connectionId), null,
                    false, false, RoutingPipeline.DEFAULT_SNAP_THRESHOLD,
                    RoutingPipeline.DEFAULT_PERIMETER_MARGIN, mode);
        } finally {
            approvalRequired = false;
        }
        assertTrue(mode + ": auto-route must be held for review", result.isProposal());
        return result.proposalContext().proposalId();
    }

    /** Proposes an {@code update-view-connection} that sets the given bendpoints. */
    String proposeConnectionUpdate(String connectionId, List<BendpointDto> bendpoints) {
        approvalRequired = true;
        MutationResult<?> result;
        try {
            result = accessor.updateViewConnection(SESSION, connectionId, bendpoints, null, null, null, null);
        } finally {
            approvalRequired = false;
        }
        assertTrue("update-view-connection must be held for review", result.isProposal());
        return result.proposalContext().proposalId();
    }

    /** Approves, answering the refusal message, or null when the approval went through. */
    String approveOrRefusal(String proposalId) {
        try {
            dispatcher.approveProposal(SESSION, proposalId);
            return null;
        } catch (MutationException e) {
            return e.getMessage();
        }
    }

    /** Approves, answering the refusal, or null when the approval went through. */
    MutationException approveRefusal(String proposalId) {
        try {
            dispatcher.approveProposal(SESSION, proposalId);
            return null;
        } catch (MutationException e) {
            return e;
        }
    }

    boolean stillQueued(String proposalId) {
        return dispatcher.getPendingProposals(SESSION).stream()
                .anyMatch(p -> p.proposalId().equals(proposalId));
    }

    // ---- the human --------------------------------------------------------------------------

    /** The human redraws a connection's route (not agent-authored, so the guard sees a human). */
    void humanRoute(IDiagramModelArchimateConnection connection, List<BendpointDto> route) {
        stack.execute(new UpdateViewConnectionCommand(connection,
                ConnectionResponseBuilder.createEmfBendpoints(route)));
        assertEquals("precondition: the human's route landed", route, route(connection));
    }

    /** The human moves or resizes a view object. */
    void humanBounds(IDiagramModelObject object, int x, int y, int width, int height) {
        int[] old = {object.getBounds().getX(), object.getBounds().getY(),
                object.getBounds().getWidth(), object.getBounds().getHeight()};
        stack.execute(human("Move " + object.getId(),
                () -> object.setBounds(x, y, width, height),
                () -> object.setBounds(old[0], old[1], old[2], old[3])));
    }

    /** A command a human ran: anything that is not agent-authored. */
    static Command human(String label, Runnable execute, Runnable undo) {
        return new Command(label) {
            @Override public void execute() { execute.run(); }
            @Override public void undo() { undo.run(); }
        };
    }

    static List<BendpointDto> route(IDiagramModelArchimateConnection connection) {
        return ConnectionResponseBuilder.collectBendpoints(connection);
    }

    // ---- model building ---------------------------------------------------------------------

    IArchimateElement element(String id, String name) {
        IArchimateElement el = factory.createApplicationComponent();
        el.setId(id);
        el.setName(name);
        model.getFolder(FolderType.APPLICATION).getElements().add(el);
        return el;
    }

    IArchimateRelationship relationship(String id, IArchimateElement source, IArchimateElement target) {
        IArchimateRelationship rel = factory.createAssociationRelationship();
        rel.setId(id);
        rel.setSource(source);
        rel.setTarget(target);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);
        return rel;
    }

    IDiagramModelArchimateObject object(String id, IArchimateElement el, IDiagramModelContainer parent,
            int x, int y, int width, int height) {
        IDiagramModelArchimateObject obj = factory.createDiagramModelArchimateObject();
        obj.setId(id);
        obj.setArchimateElement(el);
        obj.setBounds(x, y, width, height);
        parent.getChildren().add(obj);
        return obj;
    }

    IDiagramModelGroup group(String id, String name, int x, int y, int width, int height) {
        IDiagramModelGroup group = factory.createDiagramModelGroup();
        group.setId(id);
        group.setName(name);
        group.setBounds(x, y, width, height);
        view.getChildren().add(group);
        return group;
    }

    static IDiagramModelArchimateConnection connection(String id, IArchimateRelationship rel,
            IDiagramModelArchimateObject source, IDiagramModelArchimateObject target) {
        IDiagramModelArchimateConnection conn = IArchimateFactory.eINSTANCE.createDiagramModelArchimateConnection();
        conn.setId(id);
        conn.setArchimateRelationship(rel);
        conn.connect(source, target);
        return conn;
    }

    static Command toPlainCompound(Command command) {
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

    private static final class StubEditorModelManager implements IEditorModelManager {
        List<IArchimateModel> models = new ArrayList<>();

        @Override public List<IArchimateModel> getModels() { return models; }
        @Override public void addPropertyChangeListener(PropertyChangeListener listener) {}
        @Override public void removePropertyChangeListener(PropertyChangeListener listener) {}
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
