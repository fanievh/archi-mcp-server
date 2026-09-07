package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.response.dto.ApplyElementSpacingRecommendationsResultDto;

/**
 * The no-mutation arms of the spacing convenience tools: the paths that return before the control
 * loop is ever entered, and therefore must report no geometry at all.
 *
 * <p>These are the negatives for the resize report. A tool that answered a {@code dryRun} with a
 * populated {@code resizedElements} would be describing a canvas nobody changed — the exact failure
 * the report exists to prevent, pointed the other way. The list is absent here for a structural
 * reason rather than a filtered one: every one of these arms returns a null {@code adjustResult},
 * so there is nothing for the field to live inside.</p>
 *
 * <p>The COMMITTING arms cannot be driven from a test at all. {@code SpacingControlLoop.iterate}
 * executes each accepted command directly; for these tools that command wraps a
 * {@code NonNotifyingCompoundCommand} whose {@code execute()} reads
 * {@code IEditorModelManager.INSTANCE}, and initialising that field calls
 * {@code Platform.getInstanceLocation()}, which ASSERTS when no Eclipse application has been
 * initialised instead of returning the null its caller is written to tolerate. That is why the
 * committing side is pinned at the loop and projection instead
 * ({@code SpacingLoopResizeProjectionTest}), and why this class covers only what returns early.</p>
 */
public class SpacingConvenienceToolNoMutationPathTest {

    private static final String SESSION = "spacing-convenience-no-mutation-session";

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private CommandStack stack;
    private IArchimateModel model;
    private IArchimateDiagramModel view;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Spacing Convenience No-Mutation Fixture");
        model.setId("model-spacing-convenience-no-mutation");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("No Mutation");
        diagrams.getElements().add(view);

        IFolder business = model.getFolder(FolderType.BUSINESS);
        for (int i = 1; i <= 6; i++) {
            IBusinessActor a = factory.createBusinessActor();
            a.setId("actor-" + i);
            a.setName("Actor " + i);
            business.getElements().add(a);
        }

        IFolder relations = model.getFolder(FolderType.RELATIONS);
        for (int i = 1; i <= 5; i++) {
            IArchimateRelationship rel = factory.createAssociationRelationship();
            rel.setId("rel-" + i);
            rel.setSource((IBusinessActor) business.getElements().get(i - 1));
            rel.setTarget((IBusinessActor) business.getElements().get(i));
            relations.getElements().add(rel);
        }

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
            @Override
            public UndoRedoState undo(int steps) {
                for (int i = 0; i < steps && stack.canUndo(); i++) {
                    stack.undo();
                }
                return null;
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

    private String group(String label, int x, int y, int w, int h, String parentId) {
        return accessor.addGroupToView(SESSION, view.getId(), label, x, y, w, h, parentId, null, null)
                .entity().viewObjectId();
    }

    private String addAt(String actorId, int x, int y, int w, int h, String parentId) {
        return accessor.addToView(SESSION, view.getId(), actorId, x, y, w, h, false, parentId,
                null, null).entity().viewObject().viewObjectId();
    }

    private void connect(String relId, String src, String tgt) {
        accessor.addConnectionToView(SESSION, view.getId(), relId, src, tgt,
                null, null, null, null, null);
    }

    private static IDiagramModelObject find(IDiagramModelContainer container, String id) {
        for (IDiagramModelObject child : container.getChildren()) {
            if (id.equals(child.getId())) {
                return child;
            }
            if (child instanceof IDiagramModelContainer nested) {
                IDiagramModelObject hit = find(nested, id);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static int[] rect(IDiagramModelObject o) {
        IBounds b = o.getBounds();
        return new int[] { b.getX(), b.getY(), b.getWidth(), b.getHeight() };
    }

    /**
     * The dryRun arm, driven end to end. It is the representative of the family of pre-loop returns
     * — the others differ only in which guard fired — and it is the one that would be most tempting
     * to answer with a projection, since a preview is exactly what the caller asked for. It is not a
     * preview of geometry: nothing was computed and nothing was written.
     */
    @Test
    public void shouldReportNoGeometryAndChangeNothing_whenTheCallIsADryRun() {
        String g = group("Tight", 0, 0, 700, 700, null);
        String a = addAt("actor-1", 20, 40, 120, 55, g);
        String b = addAt("actor-2", 20, 135, 60, 55, g);
        String c = addAt("actor-3", 20, 230, 60, 55, g);
        connect("rel-1", a, b);
        connect("rel-2", b, c);

        int[] groupBefore = rect(find(view, g));
        int[] aBefore = rect(find(view, a));
        int[] bBefore = rect(find(view, b));

        ApplyElementSpacingRecommendationsResultDto dto =
                accessor.applyElementSpacingRecommendations(SESSION, view.getId(),
                        /*dryRun=*/ true, /*targetSpacingOverride=*/ 120, /*iterationBudget=*/ 3)
                        .entity();

        assertTrue("the fixture must actually reach the dryRun guard, not some other short-circuit. "
                + "terminationReason was: " + dto.terminationReason(),
                dto.terminationReason().startsWith("dry_run"));
        assertTrue("and it must have had something to recommend, or the negative is vacuous",
                dto.interElementDelta() > 0);
        assertNull("a dryRun computes no compound, so there is no adjustResult for a resize report "
                + "to live inside", dto.adjustResult());

        assertArrayEqualsRect("the group must be untouched", groupBefore, rect(find(view, g)));
        assertArrayEqualsRect("the first child must be untouched", aBefore, rect(find(view, a)));
        assertArrayEqualsRect("the second child must be untouched", bBefore, rect(find(view, b)));
        assertNotNull("the before-snapshot is still owed to the caller", dto.before());
        assertNull("but there is no after-state to report", dto.after());
    }

    private static void assertArrayEqualsRect(String message, int[] expected, int[] actual) {
        assertEquals(message + " (x)", expected[0], actual[0]);
        assertEquals(message + " (y)", expected[1], actual[1]);
        assertEquals(message + " (width)", expected[2], actual[2]);
        assertEquals(message + " (height)", expected[3], actual[3]);
    }

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
