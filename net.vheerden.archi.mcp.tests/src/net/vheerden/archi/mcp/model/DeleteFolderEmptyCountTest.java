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
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IAssociationRelationship;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.DeleteResultDto;

/**
 * Pins the cascade counts reported by {@code prepareDeleteFolder} for an EMPTY folder.
 *
 * <p>An empty folder reports zero for all six cascade counts without running the
 * counting traversal. Those zeros are correct rather than merely unmeasured, and the
 * reason is a coupling that is invisible at the point the counts are built: a
 * non-empty folder cannot be deleted without {@code force}, because
 * {@code prepareDeleteFolder} throws {@code FOLDER_NOT_EMPTY} first. By the time the
 * traversal guard is evaluated, "not empty" already implies "force", so the traversal
 * is skipped only when the folder holds nothing to count.</p>
 *
 * <p>{@link #shouldRejectNonEmptyFolder_whenForceIsFalse()} pins the throw that makes
 * this true. Relaxing that guard without measuring would turn the zeros below into
 * false reports, so the two tests must be read together.</p>
 *
 * <p>The force-on-empty case is the asymmetric one: the three boxed counts switch from
 * null (omitted from JSON) to zero (present) purely on the flag, with identical
 * underlying reality.</p>
 *
 * <p>Drives the REAL accessor against a real EMF model, asserting on
 * {@code PreparedMutation.entity()} directly. Pure standard JUnit (no OSGi /
 * Plug-in Test).</p>
 */
public class DeleteFolderEmptyCountTest {

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private IArchimateModel model;
    private IFolder diagrams;
    private IFolder emptyFolder;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Delete-Folder Empty Count Fixture");
        model.setId("model-delete-folder-empty-counts");
        model.setDefaults();
        diagrams = model.getFolder(FolderType.DIAGRAMS);

        // A user subfolder with no elements and no subfolders. Filing it under an
        // existing folder matters: prepareDeleteFolder refuses folders whose
        // container is the model itself (the default ArchiMate layer folders).
        emptyFolder = factory.createFolder();
        emptyFolder.setName("Empty Subfolder");
        emptyFolder.setId("folder-empty");
        diagrams.getFolders().add(emptyFolder);

        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ---- the no-force path ----

    @Test
    public void shouldReportZeroCascadeCounts_whenDeletingEmptyFolderWithoutForce() {
        DeleteResultDto dto = accessor.prepareDeleteFolder(emptyFolder.getId(), false).entity();

        assertEquals("folder-empty", dto.id());
        assertEquals("Empty Subfolder", dto.name());
        assertEquals("Folder", dto.type());

        // Genuinely zero, not a suppressed measurement: the folder holds nothing.
        assertEquals("An empty folder cascades no relationships",
                0, dto.relationshipsRemoved());
        assertEquals("An empty folder cascades no view references",
                0, dto.viewReferencesRemoved());
        assertEquals("An empty folder cascades no view connections",
                0, dto.viewConnectionsRemoved());
    }

    @Test
    public void shouldOmitBoxedCascadeCounts_whenDeletingEmptyFolderWithoutForce() {
        DeleteResultDto dto = accessor.prepareDeleteFolder(emptyFolder.getId(), false).entity();

        // Null, not zero — these three are gated on force and vanish from JSON
        // without it, while the three primitive counts above stay present as 0.
        assertNull("elementsRemoved is omitted without force", dto.elementsRemoved());
        assertNull("viewsRemoved is omitted without force", dto.viewsRemoved());
        assertNull("foldersRemoved is omitted without force", dto.foldersRemoved());
    }

    // ---- the force-on-empty path: same reality, different wire shape ----

    @Test
    public void shouldPresentBoxedCountsAsZero_whenForcingAnEmptyFolder() {
        DeleteResultDto dto = accessor.prepareDeleteFolder(emptyFolder.getId(), true).entity();

        // Present and zero, where the no-force call omits them entirely. The folder
        // is identical in both calls; only the flag differs. Asserting against
        // Integer.valueOf keeps this distinct from the null case — an assertion
        // written as assertEquals(0, ...) would not distinguish them.
        assertNotNull("elementsRemoved is present with force", dto.elementsRemoved());
        assertEquals(Integer.valueOf(0), dto.elementsRemoved());
        assertEquals(Integer.valueOf(0), dto.viewsRemoved());
        assertEquals(Integer.valueOf(0), dto.foldersRemoved());

        assertEquals(0, dto.relationshipsRemoved());
        assertEquals(0, dto.viewReferencesRemoved());
        assertEquals(0, dto.viewConnectionsRemoved());
    }

    // ---- the guard the zeros depend on ----

    /**
     * This throw is what makes every zero asserted above honest. Because a non-empty
     * folder cannot get past it without force, the counting traversal is skipped only
     * for a folder that holds nothing to count — so the counts are correct rather than
     * merely unmeasured. Relaxing or removing this guard silently invalidates the three
     * tests above, and they will not notice: their fixture folder stays empty either
     * way. This test is the only sentinel for that coupling.
     */
    @Test
    public void shouldRejectNonEmptyFolder_whenForceIsFalse() {
        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-in-folder");
        view.setName("View In Folder");
        emptyFolder.getElements().add(view);

        try {
            accessor.prepareDeleteFolder(emptyFolder.getId(), false);
            fail("A non-empty folder must not be deletable without force");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.FOLDER_NOT_EMPTY, e.getErrorCode());
        }
    }

    @Test
    public void shouldCountContents_whenForcingANonEmptyFolder() {
        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-in-folder");
        view.setName("View In Folder");
        emptyFolder.getElements().add(view);

        DeleteResultDto dto = accessor.prepareDeleteFolder(emptyFolder.getId(), true).entity();

        // The counterpart to the empty case: with something to count, the traversal
        // runs and the numbers move. Without this the zeros above could pass against
        // an implementation that never counts anything at all.
        assertEquals(Integer.valueOf(1), dto.viewsRemoved());
        assertEquals(Integer.valueOf(0), dto.elementsRemoved());
        assertEquals(Integer.valueOf(0), dto.foldersRemoved());
    }

    // ---- approval-card contract (this story) ----

    /**
     * The human approval card for a force-cascade {@code delete-folder} must fold the real cascade
     * removals into the {@code description} the card renders, and carry the three always-present
     * primitive counts in {@code proposedChanges}. Fixture: a folder holding one view with one
     * connection, so exactly two counts are non-zero (1 view, 1 view connection). The proposal
     * must NOT execute (the folder survives until approved).
     */
    @Test
    public void shouldSurfaceCascadeCountsOnApprovalCard_whenForcingNonEmptyFolder() {
        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-in-folder");
        view.setName("View In Folder");
        emptyFolder.getElements().add(view);

        IBusinessActor a1 = actor("fa-1");
        IBusinessActor a2 = actor("fa-2");
        IDiagramModelArchimateObject o1 = place(view, a1, "fo-1");
        IDiagramModelArchimateObject o2 = place(view, a2, "fo-2");
        connect(a1, a2, o1, o2);

        accessor.getMutationDispatcher().setApprovalModeProvider(() -> true);
        MutationResult<DeleteResultDto> result =
                accessor.deleteFolder("default", emptyFolder.getId(), true);

        assertNotNull("Approval mode must produce a proposal, not execute", result.proposalContext());
        PendingProposal pending = accessor.getMutationDispatcher()
                .getProposal("default", result.proposalContext().proposalId());
        assertNotNull("the stored proposal must be retrievable", pending);

        // The description IS the card's visible row: only the non-zero removals appear.
        assertEquals("Delete folder: Empty Subfolder (force cascade: 1 view, 1 view connection)",
                pending.description());

        // The three primitive counts are always surfaced under force cascade.
        assertEquals(Integer.valueOf(0), pending.proposedChanges().get("relationshipsRemoved"));
        assertEquals(Integer.valueOf(0), pending.proposedChanges().get("viewReferencesRemoved"));
        assertEquals(Integer.valueOf(1), pending.proposedChanges().get("viewConnectionsRemoved"));
        assertEquals(Integer.valueOf(1), pending.proposedChanges().get("viewsRemoved"));
        assertEquals(Boolean.TRUE, pending.proposedChanges().get("force"));

        assertTrue("the folder is still present — approval defers execution",
                diagrams.getFolders().contains(emptyFolder));
    }

    /**
     * The counterpart: an empty folder deleted WITHOUT force cascades nothing, so the card shows no
     * cascade clause and the primitive counts are omitted from the raw payload (kept minimal).
     */
    @Test
    public void shouldShowNoCascadeClauseOnApprovalCard_whenDeletingEmptyFolderWithoutForce() {
        accessor.getMutationDispatcher().setApprovalModeProvider(() -> true);
        MutationResult<DeleteResultDto> result =
                accessor.deleteFolder("default", emptyFolder.getId(), false);

        assertNotNull(result.proposalContext());
        PendingProposal pending = accessor.getMutationDispatcher()
                .getProposal("default", result.proposalContext().proposalId());
        assertNotNull(pending);

        assertEquals("Delete folder: Empty Subfolder", pending.description());
        assertFalse("primitives omitted without force",
                pending.proposedChanges().containsKey("relationshipsRemoved"));
        assertFalse("primitives omitted without force",
                pending.proposedChanges().containsKey("viewConnectionsRemoved"));
        assertFalse("primitives omitted without force",
                pending.proposedChanges().containsKey("viewReferencesRemoved"));
    }

    // ---- helpers ----

    private IBusinessActor actor(String id) {
        IBusinessActor a = factory.createBusinessActor();
        a.setId(id);
        a.setName(id);
        model.getFolder(FolderType.BUSINESS).getElements().add(a);
        return a;
    }

    private IDiagramModelArchimateObject place(IArchimateDiagramModel view,
            IBusinessActor element, String id) {
        IDiagramModelArchimateObject dmo = factory.createDiagramModelArchimateObject();
        dmo.setId(id);
        dmo.setArchimateElement(element);
        dmo.setBounds(0, 0, 120, 55);
        view.getChildren().add(dmo);
        return dmo;
    }

    private void connect(IBusinessActor a1, IBusinessActor a2,
            IDiagramModelArchimateObject o1, IDiagramModelArchimateObject o2) {
        IAssociationRelationship rel = factory.createAssociationRelationship();
        rel.setId(a1.getId() + "-" + a2.getId() + "-rel");
        rel.connect(a1, a2);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);
        IDiagramModelArchimateConnection conn = factory.createDiagramModelArchimateConnection();
        conn.setId(o1.getId() + "-" + o2.getId() + "-conn");
        conn.setArchimateRelationship(rel);
        conn.connect(o1, o2);
    }

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
        @Override public void firePropertyChange(Object src, String p, Object oldV, Object newV) {}
    }
}
