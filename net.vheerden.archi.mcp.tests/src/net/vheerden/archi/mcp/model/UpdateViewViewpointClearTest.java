package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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

import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;

/**
 * Pins the value {@code update-view} writes when a caller clears a view's viewpoint with
 * {@code viewpoint: ""}, on every path that reaches the write.
 *
 * <p>Archi's "no viewpoint" is the empty string: it is the model's default for the attribute, and
 * it is what the GUI's "None" choice writes. A {@code null} viewpoint is a state the GUI cannot
 * produce, and Archi's {@code ViewpointAction} dereferences the value unguarded while the view's
 * editor is being built, so a view holding {@code null} cannot be opened.</p>
 *
 * <p>The response cannot carry this: both values read back as an absent {@code viewpointType}, so
 * every assertion here is made on the EMF object, never on the returned DTO.</p>
 *
 * <p>Commands run on a real GEF {@link CommandStack}, so the undo assertions exercise the stack
 * rather than a direct {@code undo()} call.</p>
 */
public class UpdateViewViewpointClearTest {

    private static final String SESSION = "viewpoint-clear-session";
    private static final String VIEW_ID = "view-1";

    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private CommandStack stack;
    private IArchimateModel model;
    private IArchimateDiagramModel view;

    @Before
    public void setUp() {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        model = factory.createArchimateModel();
        model.setName("Viewpoint Clear Fixture");
        model.setId("model-viewpoint-clear");
        model.setDefaults();

        view = factory.createArchimateDiagramModel();
        view.setId(VIEW_ID);
        view.setName("Layered View");
        view.setViewpoint("layered");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        stubModelManager = new StubEditorModelManager();
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

    @Test
    public void shouldWriteEmptyString_whenUpdateViewClearsViewpoint() {
        accessor.updateView(SESSION, VIEW_ID, null, "", null, null, null);

        assertEquals("", view.getViewpoint());
    }

    @Test
    public void shouldRestorePriorViewpoint_whenClearIsUndoneOnTheStack() {
        accessor.updateView(SESSION, VIEW_ID, null, "", null, null, null);
        assertTrue("the clear must be on the undo stack", stack.canUndo());

        stack.undo();
        assertEquals("layered", view.getViewpoint());

        stack.redo();
        assertEquals("", view.getViewpoint());
    }

    @Test
    public void shouldWriteEmptyString_whenBulkMutateClearsViewpoint() {
        BulkMutationResult result = accessor.executeBulk(SESSION,
                List.of(new BulkOperation("update-view",
                        Map.<String, Object>of("viewId", VIEW_ID, "viewpoint", ""))),
                "clear viewpoint", false);

        assertEquals(1, result.operations().size());
        assertEquals("", view.getViewpoint());
    }

    @Test
    public void shouldWriteEmptyString_whenBatchedClearIsCommitted() throws Exception {
        dispatcher.beginBatch(SESSION, "clear viewpoint in a batch");
        accessor.updateView(SESSION, VIEW_ID, null, "", null, null, null);
        assertEquals("a queued clear must not touch the model before commit",
                "layered", view.getViewpoint());

        dispatcher.endBatch(SESSION, true);

        assertEquals("", view.getViewpoint());
    }

    @Test
    public void shouldRepairLegacyNull_whenViewpointIsClearedAgain() {
        // A view left holding null by an earlier write is repaired by clearing it again.
        view.setViewpoint(null);

        accessor.updateView(SESSION, VIEW_ID, null, "", null, null, null);

        assertEquals("", view.getViewpoint());
    }

    /**
     * Minimal {@link IEditorModelManager} stub: only {@code setModels()} / {@code getModels()} and
     * listener registration are meaningful.
     */
    private static class StubEditorModelManager implements IEditorModelManager {
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
