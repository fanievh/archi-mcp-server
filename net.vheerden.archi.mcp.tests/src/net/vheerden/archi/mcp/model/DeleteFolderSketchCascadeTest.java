package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.ISketchModel;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.DeleteResultDto;

/**
 * Pins that a force-delete of a folder claims EVERY kind of view the folder can hold,
 * not only {@link IArchimateDiagramModel}.
 *
 * <p>{@code ISketchModel} and {@code ICanvasModel} are SIBLINGS of
 * {@code IArchimateDiagramModel} — all three extend {@code IDiagramModel}. A type test
 * written against the ArchiMate subtype therefore skips them, and the skip is silent
 * and destructive rather than merely inaccurate: no sub-command is built, so nothing
 * scrubs the view's cross-view placeholders, yet
 * {@code DeleteFolderCommand.execute()} still detaches the folder from its parent and
 * EMF containment carries the unclaimed view out of the model with it. The result is a
 * view that disappears while the response reports zero, leaving a dangling
 * {@code IDiagramModelReference} that makes the saved file unopenable.</p>
 *
 * <p>The fixtures here use a sketch because a sketch can be built headlessly.
 * {@code ICanvasModel} lives in a bundle that is deliberately not on this classpath, so
 * canvas views are covered by testing the shared {@code IDiagramModel} supertype rather
 * than by naming them — see {@code ArchiModelAccessorImpl.deriveViewKind}. Canvas
 * behaviour is verified against a running Archi, not here.</p>
 *
 * <p>Every sketch in these fixtures is ATTACHED under the DIAGRAMS folder tree. A
 * detached view makes the placeholder assertions pass vacuously, because
 * {@code DeleteViewCommand.captureExternalPlaceholders} returns an empty list as soon
 * as {@code getArchimateModel()} is null.</p>
 *
 * <p>Pure standard JUnit (no OSGi / Plug-in Test).</p>
 */
public class DeleteFolderSketchCascadeTest {

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private IArchimateModel model;
    private IFolder diagrams;
    private IArchimateDiagramModel other;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Delete-Folder Sketch Cascade Fixture");
        model.setId("model-delete-folder-sketch-cascade");
        model.setDefaults();
        diagrams = model.getFolder(FolderType.DIAGRAMS);

        // The view that holds placeholders, and that must SURVIVE every delete here.
        other = factory.createArchimateDiagramModel();
        other.setId("view-other");
        other.setName("Other View");
        diagrams.getElements().add(other);

        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ---- the all-zeros case: a folder whose ONLY content is a sketch ----

    /**
     * The sharpest shape of the defect. With the type test written against the
     * ArchiMate subtype this folder produces an EMPTY sub-command list and six zero
     * counts, while {@code execute()} still removes the folder and the sketch inside
     * it — a destructive operation reporting that it destroyed nothing.
     *
     * <p>Both halves are asserted deliberately. The count alone would stay green under
     * a fix that counts the sketch without building a command for it, which is the
     * worse of the two failure modes: it would report honestly while still failing to
     * scrub placeholders.</p>
     */
    @Test
    public void shouldCountAndCommandTheSketch_whenFolderHoldsOnlyASketch() {
        IFolder sub = newSubfolder("folder-sketch-only", "Sketch Only");
        ISketchModel sketch = newSketchIn(sub, "sketch-solo", "Solo Sketch");

        PreparedMutation<DeleteResultDto> prepared =
                accessor.prepareDeleteFolder(sub.getId(), true);

        assertEquals("The sketch is counted as a removed view",
                Integer.valueOf(1), prepared.entity().viewsRemoved());

        List<Command> subCommands = subCommandsOf(prepared.command());
        assertEquals("Exactly one sub-command, for the sketch", 1, subCommands.size());
        assertTrue("The sub-command is a view delete",
                subCommands.get(0) instanceof DeleteViewCommand);
        assertSame("...and it targets the sketch itself",
                sketch, ((DeleteViewCommand) subCommands.get(0)).getView());
    }

    /**
     * A sketch alongside an ArchiMate view: both are claimed, and the count is 2 rather
     * than the 1 a subtype-only test would report. Distinct expected values mean a fix
     * that claims only one of the two cannot pass.
     */
    @Test
    public void shouldCountBothViewKinds_whenFolderHoldsASketchAndAnArchimateView() {
        IFolder sub = newSubfolder("folder-mixed", "Mixed");
        newSketchIn(sub, "sketch-mixed", "Mixed Sketch");

        IArchimateDiagramModel archimate = factory.createArchimateDiagramModel();
        archimate.setId("view-mixed");
        archimate.setName("Mixed ArchiMate View");
        sub.getElements().add(archimate);

        DeleteResultDto dto = accessor.prepareDeleteFolder(sub.getId(), true).entity();

        assertEquals("Both the sketch and the ArchiMate view are counted",
                Integer.valueOf(2), dto.viewsRemoved());
    }

    // ---- the corruption half: placeholders pointing AT a deleted sketch ----

    /**
     * The half that makes this a data-corruption defect rather than a reporting one.
     * A placeholder in another view pointing at the sketch must be removed when the
     * folder cascade runs; left behind, it serializes as a dangling
     * {@code model="<deleted-id>"} attribute and the file cannot be reopened.
     */
    @Test
    public void shouldRemoveExternalPlaceholder_whenCascadeDeletesASketch() {
        IFolder sub = newSubfolder("folder-referenced", "Referenced");
        ISketchModel sketch = newSketchIn(sub, "sketch-referenced", "Referenced Sketch");
        other.getChildren().add(newRefTo(sketch));

        assertEquals("Fixture precondition: one placeholder points at the sketch",
                1, countExternalReferencesTo(sketch));

        Command cmd = accessor.prepareDeleteFolder(sub.getId(), true).command();
        cmd.execute();

        assertEquals("The placeholder pointing at the deleted sketch is gone",
                0, countExternalReferencesTo(sketch));
        assertFalse("The sketch itself is out of the model",
                diagrams.getFolders().contains(sub));
    }

    // ---- depth: the cascade recurses, so the arm must hold at every level ----

    /**
     * A sketch filed one level deeper. The cascade builds a FLAT sub-command list by
     * recursing into subfolders first, so the arm runs at every depth — but a nested
     * folder is also removed by its ancestor's containment detach, which is exactly how
     * a nested decision gets defeated elsewhere in this command. Pin it rather than
     * reason about it.
     */
    @Test
    public void shouldClaimSketch_whenFiledInANestedSubfolder() {
        IFolder outer = newSubfolder("folder-outer", "Outer");
        IFolder inner = factory.createFolder();
        inner.setId("folder-inner");
        inner.setName("Inner");
        outer.getFolders().add(inner);
        ISketchModel sketch = newSketchIn(inner, "sketch-nested", "Nested Sketch");
        other.getChildren().add(newRefTo(sketch));

        PreparedMutation<DeleteResultDto> prepared =
                accessor.prepareDeleteFolder(outer.getId(), true);

        assertEquals("The nested sketch is counted",
                Integer.valueOf(1), prepared.entity().viewsRemoved());
        assertEquals("The inner folder is counted",
                Integer.valueOf(1), prepared.entity().foldersRemoved());

        prepared.command().execute();
        assertEquals("The nested sketch's placeholder is scrubbed too",
                0, countExternalReferencesTo(sketch));
    }

    // ---- undo ----

    /**
     * Undo restores the folder wholesale, and the sketch's own command restores the
     * placeholder it removed. Verified rather than inherited: the claim that undo is
     * unaffected was made about the BROKEN behaviour, where the sketch had no command
     * at all. Adding one changes what undo has to reverse.
     *
     * <p>The mid-flight assertion is load-bearing. Without it this test passes on the
     * BROKEN build for the wrong reason: nothing removes the placeholder, so it is
     * trivially still there after a round-trip. Asserting that the placeholder actually
     * went away first is what makes the restore claim mean anything.</p>
     */
    @Test
    public void shouldRestoreSketchAndPlaceholder_onUndo() {
        IFolder sub = newSubfolder("folder-undo", "Undo");
        ISketchModel sketch = newSketchIn(sub, "sketch-undo", "Undo Sketch");
        other.getChildren().add(newRefTo(sketch));

        Command cmd = accessor.prepareDeleteFolder(sub.getId(), true).command();
        cmd.execute();
        assertEquals("Precondition for the restore claim: the delete really removed it",
                0, countExternalReferencesTo(sketch));

        cmd.undo();

        assertTrue("The folder is back under DIAGRAMS", diagrams.getFolders().contains(sub));
        assertTrue("The sketch is back in the folder", sub.getElements().contains(sketch));
        assertEquals("The external placeholder is restored",
                1, countExternalReferencesTo(sketch));
    }

    // ---- what must NOT change ----

    /**
     * The no-force guard already counted sketches, because it measures
     * {@code folder.getElements().size()} without inspecting types. It was never part
     * of the defect and must not move.
     */
    @Test
    public void shouldStillRejectWithoutForce_whenFolderHoldsOnlyASketch() {
        IFolder sub = newSubfolder("folder-noforce", "No Force");
        newSketchIn(sub, "sketch-noforce", "No Force Sketch");

        try {
            accessor.prepareDeleteFolder(sub.getId(), false);
            fail("A folder holding a sketch is not empty and must not delete without force");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.FOLDER_NOT_EMPTY, e.getErrorCode());
        }
    }

    /**
     * The standalone delete-view tool stays narrow. Widening the folder cascade must not
     * widen the tool: sketches and canvases remain undeletable by id, which is the
     * documented, consistent behaviour across this server's ~48 other view-type tests.
     * Without this pin a later change could widen {@code prepareDeleteView} unnoticed.
     */
    @Test
    public void shouldStillRejectASketchById_whenDeletingAViewDirectly() {
        IFolder sub = newSubfolder("folder-direct", "Direct");
        ISketchModel sketch = newSketchIn(sub, "sketch-direct", "Direct Sketch");

        try {
            accessor.prepareDeleteView(sketch.getId());
            fail("delete-view must keep rejecting a sketch id");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.VIEW_NOT_FOUND, e.getErrorCode());
        }
    }

    // ---- helpers ----

    private IFolder newSubfolder(String id, String name) {
        IFolder sub = factory.createFolder();
        sub.setId(id);
        sub.setName(name);
        diagrams.getFolders().add(sub);
        return sub;
    }

    private ISketchModel newSketchIn(IFolder parent, String id, String name) {
        ISketchModel sketch = factory.createSketchModel();
        sketch.setId(id);
        sketch.setName(name);
        parent.getElements().add(sketch);
        return sketch;
    }

    private IDiagramModelReference newRefTo(IDiagramModel referenced) {
        IDiagramModelReference ref = factory.createDiagramModelReference();
        ref.setReferencedModel(referenced);
        ref.setBounds(0, 0, 185, 80);
        return ref;
    }

    private static List<Command> subCommandsOf(Command folderDelete) {
        return ((DeleteFolderCommand) folderDelete).getSubCommands();
    }

    /** Live placeholders pointing at {@code view} from outside it, anywhere under DIAGRAMS. */
    private int countExternalReferencesTo(IDiagramModel view) {
        int count = 0;
        for (Iterator<EObject> it = diagrams.eAllContents(); it.hasNext(); ) {
            EObject node = it.next();
            if (node instanceof IDiagramModelReference ref
                    && ref.getReferencedModel() == view
                    && !isInside(ref, view)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isInside(EObject node, EObject ancestor) {
        for (EObject cur = node.eContainer(); cur != null; cur = cur.eContainer()) {
            if (cur == ancestor) {
                return true;
            }
        }
        return false;
    }

    // ---- infrastructure ----

    private ArchiModelAccessorImpl createAccessorWithTestDispatcher(IArchimateModel testModel) {
        MutationDispatcher testDispatcher = new MutationDispatcher(() -> testModel) {
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
        testDispatcher.setApprovalModeProvider(() -> false);
        return new ArchiModelAccessorImpl(stubModelManager, testDispatcher);
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
        @Override public void firePropertyChange(Object source, String prop, Object oldV, Object newV) {}
    }
}
