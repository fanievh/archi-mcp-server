package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

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
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.ITextAlignment;
import com.archimatetool.model.ITextPosition;

import net.vheerden.archi.mcp.response.dto.ViewDto;

/**
 * Pins which text features survive a view clone.
 *
 * <p>Cloning is COPY semantics, not creation semantics: whatever horizontal and vertical title
 * placement the author chose on the source object is a deliberate styling decision, and the clone
 * must reproduce it rather than substitute a type default. The two features travel together in the
 * renderer — {@code textAlignment} places the title's glyph run horizontally and
 * {@code textPosition} selects the band it sits in — so a copy that carries one and drops the other
 * silently relocates every title in the cloned view.</p>
 *
 * <p>The fixture sets both features to explicitly non-default values on all three clonable object
 * kinds (ArchiMate object, native group, note) and asserts both survive. Non-default matters: a
 * fixture left at the default would agree with a copy step and with no copy step at all, and so
 * would pin nothing.</p>
 */
public class CloneViewTextFeatureCopyTest {

    private static final String SESSION = "clone-text-feature-session";

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private CommandStack stack;
    private IArchimateModel model;
    private IArchimateDiagramModel view;

    private IDiagramModelArchimateObject sourceObject;
    private IDiagramModelGroup sourceGroup;
    private IDiagramModelNote sourceNote;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Clone Text Feature Fixture");
        model.setId("model-clone-text-feature");
        model.setDefaults();

        IBusinessActor actor = factory.createBusinessActor();
        actor.setId("actor-a");
        actor.setName("Actor A");
        model.getFolder(FolderType.BUSINESS).getElements().add(actor);

        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Source View");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        // Built through EMF directly, not through the server: the subject is the COPY step, so the
        // fixture must not inherit whatever the creation path happens to stamp.
        sourceObject = factory.createDiagramModelArchimateObject();
        sourceObject.setId("obj-1");
        sourceObject.setArchimateElement(actor);
        sourceObject.setBounds(10, 10, 120, 55);
        sourceObject.setTextAlignment(ITextAlignment.TEXT_ALIGNMENT_RIGHT);
        sourceObject.setTextPosition(ITextPosition.TEXT_POSITION_BOTTOM);
        view.getChildren().add(sourceObject);

        sourceGroup = factory.createDiagramModelGroup();
        sourceGroup.setId("group-1");
        sourceGroup.setName("Holder");
        sourceGroup.setBounds(200, 10, 300, 200);
        sourceGroup.setTextAlignment(ITextAlignment.TEXT_ALIGNMENT_RIGHT);
        sourceGroup.setTextPosition(ITextPosition.TEXT_POSITION_CENTRE);
        view.getChildren().add(sourceGroup);

        sourceNote = factory.createDiagramModelNote();
        sourceNote.setId("note-1");
        sourceNote.setContent("A note");
        sourceNote.setBounds(10, 300, 180, 80);
        sourceNote.setTextAlignment(ITextAlignment.TEXT_ALIGNMENT_RIGHT);
        sourceNote.setTextPosition(ITextPosition.TEXT_POSITION_CENTRE);
        view.getChildren().add(sourceNote);

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
            // NonNotifyingCompoundCommand static-initialises IEditorModelManager, which cannot
            // initialise outside OSGi — rebuilding into a plain compound keeps the child commands
            // (the clone itself) intact while dropping only the notification suppression.
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

    // ---- helpers -------------------------------------------------------------------------------

    private IArchimateDiagramModel cloneAndFetch() {
        ViewDto cloned = accessor.cloneView(SESSION, view.getId(), "Cloned View", null).entity();
        assertNotNull("clone-view must return the cloned view", cloned);
        for (Object o : model.getFolder(FolderType.DIAGRAMS).getElements()) {
            if (o instanceof IArchimateDiagramModel dm && cloned.id().equals(dm.getId())) {
                return dm;
            }
        }
        throw new AssertionError("cloned view not found in the model: " + cloned.id());
    }

    private static IDiagramModelObject childOfKind(IArchimateDiagramModel v, Class<?> kind) {
        for (IDiagramModelObject child : v.getChildren()) {
            if (kind.isInstance(child)) {
                return child;
            }
        }
        throw new AssertionError("no cloned child of kind " + kind.getSimpleName());
    }

    // ---- textAlignment: the feature the copy step already carries -------------------------------

    @Test
    public void shouldPreserveExplicitTextAlignment_whenAnArchimateObjectIsCloned() {
        IDiagramModelObject clonedObject =
                childOfKind(cloneAndFetch(), IDiagramModelArchimateObject.class);
        assertEquals("a deliberate RIGHT alignment must survive the clone",
                ITextAlignment.TEXT_ALIGNMENT_RIGHT,
                ((ITextAlignment) clonedObject).getTextAlignment());
    }

    @Test
    public void shouldPreserveExplicitTextAlignment_whenAGroupIsCloned() {
        IDiagramModelObject clonedGroup = childOfKind(cloneAndFetch(), IDiagramModelGroup.class);
        assertEquals("a deliberate RIGHT alignment must survive the clone",
                ITextAlignment.TEXT_ALIGNMENT_RIGHT,
                ((ITextAlignment) clonedGroup).getTextAlignment());
    }

    @Test
    public void shouldPreserveExplicitTextAlignment_whenANoteIsCloned() {
        IDiagramModelObject clonedNote = childOfKind(cloneAndFetch(), IDiagramModelNote.class);
        assertEquals("a deliberate RIGHT alignment must survive the clone",
                ITextAlignment.TEXT_ALIGNMENT_RIGHT,
                ((ITextAlignment) clonedNote).getTextAlignment());
    }

    // ---- textPosition: the feature the copy step drops -------------------------------------------

    @Test
    public void shouldPreserveExplicitTextPosition_whenAnArchimateObjectIsCloned() {
        IDiagramModelObject clonedObject =
                childOfKind(cloneAndFetch(), IDiagramModelArchimateObject.class);
        assertEquals("a deliberate BOTTOM text position must survive the clone",
                ITextPosition.TEXT_POSITION_BOTTOM,
                ((ITextPosition) clonedObject).getTextPosition());
    }

    @Test
    public void shouldPreserveExplicitTextPosition_whenAGroupIsCloned() {
        IDiagramModelObject clonedGroup = childOfKind(cloneAndFetch(), IDiagramModelGroup.class);
        assertEquals("a deliberate CENTRE text position must survive the clone",
                ITextPosition.TEXT_POSITION_CENTRE,
                ((ITextPosition) clonedGroup).getTextPosition());
    }

    @Test
    public void shouldPreserveExplicitTextPosition_whenANoteIsCloned() {
        IDiagramModelObject clonedNote = childOfKind(cloneAndFetch(), IDiagramModelNote.class);
        assertEquals("a deliberate CENTRE text position must survive the clone",
                ITextPosition.TEXT_POSITION_CENTRE,
                ((ITextPosition) clonedNote).getTextPosition());
    }

    private static class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final List<PropertyChangeListener> listeners = new ArrayList<>();

        void setModels(List<IArchimateModel> models) { this.models = models; }

        @Override public List<IArchimateModel> getModels() { return models; }
        @Override public void addPropertyChangeListener(PropertyChangeListener listener) {
            listeners.add(listener);
        }
        @Override public void removePropertyChangeListener(PropertyChangeListener listener) {
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
