package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.archimatetool.editor.model.IArchiveManager;
import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.IFolder;

/**
 * Integration regression pins for multi-view / compound delete cascade,
 * verified through the only sound oracle: serialize to {@code .archimate},
 * reload from disk, and scan for dangling EMF cross-references and ghost
 * view-objects.
 *
 * <p>In-memory EMF keeps a detached cross-reference resolving, so a defect is
 * invisible in-session; it surfaces only after save → reload (the failure mode
 * that broke {@code Routing Pipeline Comparison.archimate}). These tests extend
 * the single-view {@code DeleteViewCascadeIntegrationTest} /
 * {@code RemoveFromViewCascadeIntegrationTest} pins to the multi-view, folder-
 * force, and compound paths, driving the real accessor cascade discovery
 * ({@code prepareDeleteElement / prepareDeleteFolder}).</p>
 *
 * <p>Lives in the {@code model} package (mirroring
 * {@code RelationshipSemanticAttributesIntegrationTest}) for package-private
 * access to the accessor's prepare methods. Reload is guaranteed to come from
 * disk because the in-memory model is never registered with
 * {@code IEditorModelManager.INSTANCE}; each test also asserts
 * {@code assertNotSame}. Requires the OSGi/PDE runtime; guarded by
 * {@link Platform#isRunning()}.</p>
 */
public class MultiViewCompoundDeleteCascadeIntegrationTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;

    @Before
    public void requireOsgiRuntime() {
        assumeTrue("requires PDE/OSGi runtime", Platform.isRunning());
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();
    }

    // ---- compound delete of two views, one holding a placeholder ----

    @Test
    public void shouldNotProduceDanglingReferences_whenCompoundDeletesBothViewsAndSerialized()
            throws Exception {
        IArchimateModel model = newModel("Compound Delete Pin");
        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);

        IArchimateDiagramModel viewA = addView(diagrams, "view-a", "View A");
        IArchimateDiagramModel viewB = addView(diagrams, "view-b", "View B");

        IDiagramModelReference placeholder = factory.createDiagramModelReference();
        placeholder.setReferencedModel(viewA);
        placeholder.setBounds(0, 0, 185, 80);
        viewB.getChildren().add(placeholder);

        // Delete BOTH views in one compound, holding-view-first ([B, A]) — the
        // ordering most likely to mishandle the placeholder cascade.
        int idxB = diagrams.getElements().indexOf(viewB);
        int idxA = diagrams.getElements().indexOf(viewA);
        CompoundCommand compound = new CompoundCommand("Delete both views");
        compound.add(new DeleteViewCommand(viewB, diagrams, idxB));
        compound.add(new DeleteViewCommand(viewA, diagrams, idxA));

        IArchimateModel reloaded = saveExecuteReload(model, compound::execute);
        try {
            assertDanglingReferenceCount(reloaded, 0);
        } finally {
            closeQuietly(reloaded);
        }
    }

    // ---- folder-force delete of a view referenced by a sibling ----

    @Test
    public void shouldNotProduceDanglingReferences_whenFolderForceDeletesViewWithSiblingPlaceholder()
            throws Exception {
        IArchimateModel model = newModel("Folder Force Pin");
        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);

        IFolder sub = factory.createFolder();
        sub.setId("folder-sub");
        sub.setName("Subfolder");
        diagrams.getFolders().add(sub);

        IArchimateDiagramModel referenced = factory.createArchimateDiagramModel();
        referenced.setId("view-ref");
        referenced.setName("Referenced View");
        sub.getElements().add(referenced);

        IArchimateDiagramModel sibling = addView(diagrams, "view-sibling", "Sibling View");
        IDiagramModelReference placeholder = factory.createDiagramModelReference();
        placeholder.setReferencedModel(referenced);
        placeholder.setBounds(0, 0, 185, 80);
        sibling.getChildren().add(placeholder);

        accessor = accessorFor(model);
        Command cmd = accessor.prepareDeleteFolder(sub.getId(), true).command();
        IArchimateModel reloaded = saveExecuteReload(model, cmd::execute);
        try {
            assertDanglingReferenceCount(reloaded, 0);
        } finally {
            closeQuietly(reloaded);
        }
    }

    // ---- element placed in two views, deleted, round-trip clean ----

    @Test
    public void shouldNotProduceGhostsOrDanglingRefs_whenMultiViewElementDeletedAndSerialized()
            throws Exception {
        IArchimateModel model = newModel("Multi-View Element Pin");
        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);

        IArchimateDiagramModel viewA = addView(diagrams, "view-a", "View A");
        IArchimateDiagramModel viewB = addView(diagrams, "view-b", "View B");

        IBusinessActor actor = factory.createBusinessActor();
        actor.setId("actor-shared");
        actor.setName("Shared Actor");
        model.getFolder(FolderType.BUSINESS).getElements().add(actor);

        addArchimateObject(viewA, actor, "dmo-a");
        addArchimateObject(viewB, actor, "dmo-b");

        accessor = accessorFor(model);
        Command cmd = accessor.prepareDeleteElement(actor.getId()).command();
        IArchimateModel reloaded = saveExecuteReload(model, cmd::execute);
        try {
            // No ghost archimate-object: every IDiagramModelArchimateObject must
            // resolve a non-proxy element, and the deleted element id appears nowhere.
            int ghostCount = 0;
            boolean deletedIdSeen = false;
            IFolder reloadedDiagrams = reloaded.getFolder(FolderType.DIAGRAMS);
            for (Iterator<EObject> iter = reloadedDiagrams.eAllContents(); iter.hasNext(); ) {
                EObject node = iter.next();
                if (node instanceof IDiagramModelArchimateObject dmo) {
                    if (dmo.getArchimateElement() == null || dmo.getArchimateElement().eIsProxy()) {
                        ghostCount++;
                    } else if ("actor-shared".equals(dmo.getArchimateElement().getId())) {
                        deletedIdSeen = true;
                    }
                }
            }
            assertEquals("No ghost diagram objects after multi-view element delete + reload",
                    0, ghostCount);
            assertFalse("Deleted element must not be referenced by any reloaded view object",
                    deletedIdSeen);

            assertDanglingReferenceCount(reloaded, 0);
        } finally {
            closeQuietly(reloaded);
        }
    }

    // ---- shared round-trip plumbing (mirrors DeleteViewCascadeIntegrationTest) ----

    private IArchimateModel newModel(String name) {
        IArchimateModel model = factory.createArchimateModel();
        model.setDefaults();
        model.setName(name);
        return model;
    }

    private IArchimateDiagramModel addView(IFolder diagrams, String id, String name) {
        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId(id);
        view.setName(name);
        diagrams.getElements().add(view);
        return view;
    }

    private void addArchimateObject(IArchimateDiagramModel view,
            com.archimatetool.model.IArchimateElement element, String id) {
        IDiagramModelArchimateObject dmo = factory.createDiagramModelArchimateObject();
        dmo.setId(id);
        dmo.setArchimateElement(element);
        dmo.setBounds(10, 10, 120, 55);
        view.getChildren().add(dmo);
    }

    /**
     * Saves the model, runs the mutation, saves again, then reloads from disk
     * and returns the reloaded model. The in-memory model is never registered
     * with {@code IEditorModelManager.INSTANCE}, so the reload is a genuine disk
     * read (asserted via {@code assertNotSame}).
     */
    private IArchimateModel saveExecuteReload(IArchimateModel model, Runnable mutation)
            throws IOException {
        IArchiveManager archiveManager = IArchiveManager.FACTORY.createArchiveManager(model);
        model.setAdapter(IArchiveManager.class, archiveManager);

        File savedFile = new File(tempFolder.getRoot(), "multi-view-cascade-pin.archimate");
        model.setFile(savedFile);

        mutation.run();

        archiveManager.saveModel();
        assertTrue("Saved file should exist", savedFile.exists());

        // The in-memory model was never registered with the real manager, so it
        // holds no cached graph to hand back — loadModel must deserialize from disk.
        // (We deliberately do NOT closeModel(model) first, as the template does not:
        // closeModel on an unregistered model would return false. This pair of
        // guarantees replaces that guard.)
        assertFalse("In-memory model must not be adopted by the real manager",
                IEditorModelManager.INSTANCE.getModels().contains(model));

        IArchimateModel reloaded = IEditorModelManager.INSTANCE.loadModel(savedFile);
        assertNotNull("Reload should succeed", reloaded);
        assertNotSame("Reload must be a fresh disk read, not the cached in-memory graph",
                model, reloaded);
        return reloaded;
    }

    private void assertDanglingReferenceCount(IArchimateModel reloaded, int expected) {
        int danglingCount = 0;
        IFolder reloadedDiagrams = reloaded.getFolder(FolderType.DIAGRAMS);
        for (Iterator<EObject> iter = reloadedDiagrams.eAllContents(); iter.hasNext(); ) {
            EObject node = iter.next();
            if (node instanceof IDiagramModelReference ref) {
                if (ref.getReferencedModel() == null || ref.getReferencedModel().eIsProxy()) {
                    danglingCount++;
                }
            }
        }
        assertEquals("No dangling IDiagramModelReference cross-references after reload",
                expected, danglingCount);
    }

    /** Best-effort cleanup; closing a reloaded model unloads it from the manager. */
    private void closeQuietly(IArchimateModel reloaded) {
        if (reloaded == null) {
            return;
        }
        try {
            IEditorModelManager.INSTANCE.closeModel(reloaded);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private ArchiModelAccessorImpl accessorFor(IArchimateModel model) {
        stubModelManager.setModels(List.of(model));
        MutationDispatcher testDispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) { command.execute(); }
            @Override
            protected void dispatchCommand(Command command) { command.execute(); }
        };
        testDispatcher.setApprovalModeProvider(() -> false);
        return new ArchiModelAccessorImpl(stubModelManager, testDispatcher);
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
