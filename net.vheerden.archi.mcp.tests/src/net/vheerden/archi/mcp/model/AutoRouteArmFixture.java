package net.vheerden.archi.mcp.model;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.INode;

import net.vheerden.archi.mcp.response.dto.AutoRouteResultDto;
import net.vheerden.archi.mcp.response.dto.StructuredWarningDto;

/**
 * Shared headless fixture for driving the REAL {@code auto-route-connections} accessor path on each
 * of the three dispatch arms.
 *
 * <p>Two properties make this worth sharing rather than re-deriving per suite. First, it is
 * genuinely headless despite touching EMF: it supplies its own {@link IEditorModelManager} stub and
 * a {@link MutationDispatcher} that executes commands in-process, so nothing reaches
 * {@code Display.syncExec} and the class stays in the default test lane. Second, and the reason it
 * exists at all, the arm is selected the same way the production gate reads it — approval through
 * the injected provider, batch through {@code beginBatch} — rather than by a test-only flag. A
 * fixture that faked the arm could not witness a disagreement between the arm a disclosure was
 * written for and the arm the call actually took.</p>
 *
 * <p>Elements, relationships and notes are deliberately left UNNAMED. {@code AssessmentCollector}
 * measures label text through SWT, and on a display-less runner that throws {@code SWTError} —
 * an {@code Error}, not an {@code Exception}, so the collector's own catch does not swallow it.
 * A named fixture therefore passes on a developer machine and errors in CI.</p>
 */
final class AutoRouteArmFixture {

    static final String SESSION = "auto-route-arm-session";
    static final String VIEW_ID = "view-auto-route-arm";

    /** Hosts are 55 tall at y=200, so their centres sit at y=227. */
    static final int CORRIDOR_Y = 227;

    private AutoRouteArmFixture() {}

    /** Which dispatch arm a driven call should take. */
    enum Arm { APPLIED, QUEUED, AWAITING_APPROVAL }

    /** A model plus the one view every fixture below builds into. */
    static final class Ctx {
        IArchimateModel model;
        IArchimateDiagramModel view;
    }

    // ==================== driving ====================

    /** Drives the full-mode orthogonal path on {@code arm}. */
    static AutoRouteResultDto route(Ctx c, Arm arm) {
        return route(c, arm, "orthogonal", false, null);
    }

    /** Drives {@code mode: "terminals-only"} on {@code arm}. */
    static AutoRouteResultDto routeTerminalsOnly(Ctx c, Arm arm) {
        return route(c, arm, "orthogonal", false, "terminals-only");
    }

    /** Drives {@code strategy: "clear"} on {@code arm} — the third route into the note emitter. */
    static AutoRouteResultDto routeCleared(Ctx c, Arm arm) {
        return route(c, arm, "clear", false, null);
    }

    /** Drives the autoNudge path on {@code arm}. */
    static AutoRouteResultDto routeWithNudge(Ctx c, Arm arm) {
        return route(c, arm, "orthogonal", true, null);
    }

    static AutoRouteResultDto route(Ctx c, Arm arm, String strategy, boolean autoNudge,
            String mode) {
        return driveFor(c, arm, strategy, autoNudge, mode, null).entity();
    }

    /** Drives {@code arm} with an explicit connection-id list, so a missing id can be requested. */
    static AutoRouteResultDto routeIds(Ctx c, Arm arm, String mode, List<String> connectionIds) {
        return driveFor(c, arm, "orthogonal", false, mode, connectionIds).entity();
    }

    /**
     * Returns the whole {@link MutationResult}, not just the entity, so a caller can cross-check the
     * arm the disclosure was written for against {@code isBatched()} / {@code isProposal()} — the
     * agreement this fixture exists to make observable.
     */
    static MutationResult<AutoRouteResultDto> driveFor(Ctx c, Arm arm, String strategy,
            boolean autoNudge, String mode) {
        return driveFor(c, arm, strategy, autoNudge, mode, null);
    }

    static MutationResult<AutoRouteResultDto> driveFor(Ctx c, Arm arm, String strategy,
            boolean autoNudge, String mode, List<String> connectionIds) {
        StubEditorModelManager mgr = new StubEditorModelManager();
        mgr.setModels(List.of(c.model));
        MutationDispatcher dispatcher = newDispatcher(c.model);
        dispatcher.setApprovalModeProvider(() -> arm == Arm.AWAITING_APPROVAL);
        if (arm == Arm.QUEUED) {
            dispatcher.beginBatch(SESSION, "arm fixture batch");
        }
        ArchiModelAccessorImpl accessor = new ArchiModelAccessorImpl(mgr, dispatcher);
        try {
            return accessor.autoRouteConnections(SESSION, VIEW_ID, connectionIds, strategy, false,
                    autoNudge, 0, 0, mode);
        } finally {
            accessor.dispose();
        }
    }

    // ==================== reading the response ====================

    /** The first structured warning carrying {@code code}, or {@code null} when absent. */
    static StructuredWarningDto structured(AutoRouteResultDto dto, String code) {
        for (StructuredWarningDto w : dto.structuredWarnings()) {
            if (code.equals(w.code())) {
                return w;
            }
        }
        return null;
    }

    // ==================== fixtures ====================

    /** One horizontal route; one note straddling it. */
    static Ctx oneNoteInCorridor() {
        Ctx c = newView();
        addHost(c, "hl", 0, 200, 120, 55);
        addHost(c, "hr", 800, 200, 120, 55);
        addNote(c, "obj-note-a", 380, CORRIDOR_Y - 57, 200, 120);
        connect(c, "conn-a", "rel-a", "obj-hl-n", "obj-hr-n");
        return c;
    }

    /**
     * A connection carrying an interior bendpoint well below the endpoint line, so both terminal
     * segments leave their elements diagonally — the shape terminals-only exists to rectify. A wide
     * note straddles the resulting path, so the same emitter fires on the terminals-only split.
     */
    static Ctx diagonalTerminalsThroughANote() {
        Ctx c = newView();
        addHost(c, "hl", 0, 200, 120, 55);
        addHost(c, "hr", 800, 200, 120, 55);
        addNote(c, "obj-note-a", 380, CORRIDOR_Y - 57, 200, 120);
        connect(c, "conn-a", "rel-a", "obj-hl-n", "obj-hr-n");
        // Absolute (460,420) expressed relative to both endpoint centres (60,227) and (860,227).
        setBendpoint(c, 460 - 60, 420 - 227, 460 - 860, 420 - 227);
        return c;
    }

    /**
     * Two sibling elements whose bounding boxes overlap, with a route between a third pair. The
     * overlap is a plain sibling one — neither element is the other's parent — which is what the
     * autoNudge pre-gate refuses to nudge through.
     */
    static Ctx overlappingSiblings() {
        Ctx c = newView();
        addHost(c, "hl", 0, 200, 120, 55);
        addHost(c, "hr", 800, 200, 120, 55);
        addHost(c, "ov1", 300, 400, 120, 55);
        addHost(c, "ov2", 340, 420, 120, 55);
        connect(c, "conn-a", "rel-a", "obj-hl-n", "obj-hr-n");
        return c;
    }

    /**
     * The overlapping-sibling fixture with a note straddling the routed corridor, so ONE call
     * carries both the arm-independent overlap-skip code and an arm-dependent note-crossing code.
     *
     * <p>That pairing is the point. A test asserting that the overlap-skip message is identical on
     * all three arms proves nothing on its own — a harness that silently drove the same arm three
     * times would pass it just as well. The second code is the positive control: it must DIFFER
     * across the same three responses, from the same fixture, in the same test.</p>
     */
    static Ctx overlappingSiblingsWithNoteInCorridor() {
        Ctx c = overlappingSiblings();
        addNote(c, "obj-note-a", 380, CORRIDOR_Y - 57, 200, 120);
        return c;
    }

    /** A route whose requested id list will contain an id the view does not carry. */
    static Ctx plainRoute() {
        Ctx c = newView();
        addHost(c, "hl", 0, 200, 120, 55);
        addHost(c, "hr", 800, 200, 120, 55);
        connect(c, "conn-a", "rel-a", "obj-hl-n", "obj-hr-n");
        return c;
    }

    // ==================== fixture plumbing ====================

    static Ctx newView() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        Ctx c = new Ctx();
        c.model = f.createArchimateModel();
        c.model.setName("AutoRouteArm");
        c.model.setId("model-auto-route-arm");
        c.model.setDefaults();
        c.view = f.createArchimateDiagramModel();
        c.view.setId(VIEW_ID);
        c.view.setName("AutoRouteArm");
        c.model.getFolder(FolderType.DIAGRAMS).getElements().add(c.view);
        return c;
    }

    static void addHost(Ctx c, String id, int x, int y, int w, int h) {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        INode node = f.createNode();
        node.setId(id + "-n");
        c.model.getFolder(FolderType.TECHNOLOGY).getElements().add(node);
        IDiagramModelArchimateObject obj = f.createDiagramModelArchimateObject();
        obj.setId("obj-" + id + "-n");
        obj.setArchimateConcept(node);
        obj.setBounds(x, y, w, h);
        c.view.getChildren().add(obj);
    }

    static void addNote(Ctx c, String id, int x, int y, int w, int h) {
        IDiagramModelNote note = IArchimateFactory.eINSTANCE.createDiagramModelNote();
        note.setId(id);
        note.setBounds(x, y, w, h);
        c.view.getChildren().add(note);
    }

    /**
     * Built directly rather than through {@code createRelationship}: that path consults Archi's
     * static validity matrix, which needs an OSGi context this fixture does not have.
     */
    static void connect(Ctx c, String connId, String relId, String sourceObjId,
            String targetObjId) {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IDiagramModelArchimateObject src = findObject(c.view, sourceObjId);
        IDiagramModelArchimateObject tgt = findObject(c.view, targetObjId);
        IArchimateRelationship rel = f.createAssociationRelationship();
        rel.setId(relId);
        rel.setSource(src.getArchimateConcept());
        rel.setTarget(tgt.getArchimateConcept());
        c.model.getFolder(FolderType.RELATIONS).getElements().add(rel);

        IDiagramModelArchimateConnection conn = f.createDiagramModelArchimateConnection();
        conn.setId(connId);
        conn.setArchimateRelationship(rel);
        conn.connect(src, tgt);
    }

    static void setBendpoint(Ctx c, int startX, int startY, int endX, int endY) {
        IDiagramModelBendpoint bp = IArchimateFactory.eINSTANCE.createDiagramModelBendpoint();
        bp.setStartX(startX);
        bp.setStartY(startY);
        bp.setEndX(endX);
        bp.setEndY(endY);
        findConnection(c.view).getBendpoints().add(bp);
    }

    static IDiagramModelConnection findConnection(IDiagramModelContainer container) {
        for (IDiagramModelObject child : container.getChildren()) {
            for (IDiagramModelConnection conn : child.getSourceConnections()) {
                return conn;
            }
        }
        throw new AssertionError("no connection on the view");
    }

    static IDiagramModelArchimateObject findObject(IDiagramModelContainer container, String id) {
        for (IDiagramModelObject child : container.getChildren()) {
            if (id.equals(child.getId()) && child instanceof IDiagramModelArchimateObject o) {
                return o;
            }
        }
        throw new AssertionError("no view object with id " + id);
    }

    // ==================== harness ====================

    static MutationDispatcher newDispatcher(IArchimateModel target) {
        return new MutationDispatcher(() -> target) {
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
    }

    static final class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final Set<PropertyChangeListener> listeners = new LinkedHashSet<>();

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
