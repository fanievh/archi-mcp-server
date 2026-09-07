package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.Platform;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.PaletteData;
import org.junit.After;
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
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.AddToViewResultDto;
import net.vheerden.archi.mcp.response.dto.DiagramImageDto;

/**
 * Pin tests — wires {@link ArchiModelAccessorImpl#validateImagePathExists}
 * (added for {@code add-image-to-view} / {@code create-specialization} /
 * {@code update-specialization}) into the four pre-existing prepare paths
 * ({@code prepareAddToView}, {@code prepareAddToViewDirect},
 * {@code prepareUpdateViewObject}, {@code prepareUpdateViewObjectDirect}).
 *
 * <p>Tests run as JUnit Plug-in Test (need the OSGi runtime so
 * {@link IArchiveManager.FACTORY} can create a real archive manager). Guarded
 * by {@link Platform#isRunning()} so non-PDE launches skip cleanly. Mirrors
 * {@link ArchiModelAccessorImplAddViewReferenceToViewTest}'s accessor-with-
 * test-dispatcher pattern + {@link DeleteViewCascadeIntegrationTest}'s
 * archive-manager attachment pattern.</p>
 */
public class ImagePathArchiveValidationTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private IArchimateModel model;
    private IArchimateDiagramModel targetView;
    private IBusinessActor actor;
    private String knownImagePath;

    @Before
    public void setUp() throws Exception {
        assumeTrue("requires PDE/OSGi runtime", Platform.isRunning());

        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Test 14-8.1");
        model.setId("model-14-8-1");
        model.setDefaults();

        IArchiveManager archiveManager =
                IArchiveManager.FACTORY.createArchiveManager(model);
        model.setAdapter(IArchiveManager.class, archiveManager);

        File pngFile = tempFolder.newFile("known.png");
        writeMinimalPng(pngFile);
        knownImagePath = archiveManager.addImageFromFile(pngFile);

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        targetView = factory.createArchimateDiagramModel();
        targetView.setId("view-target");
        targetView.setName("Target");
        diagrams.getElements().add(targetView);

        IFolder business = model.getFolder(FolderType.BUSINESS);
        actor = factory.createBusinessActor();
        actor.setId("actor-1");
        actor.setName("Test Actor");
        business.getElements().add(actor);

        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    /** Happy path: add-to-view with valid imagePath succeeds. */
    @Test
    public void shouldAcceptValidImagePath_addToView() {
        ImageParams imageParams = new ImageParams(knownImagePath, null, null);

        MutationResult<AddToViewResultDto> result = accessor.addToView(
                "test-session", targetView.getId(), actor.getId(),
                10, 10, 120, 60, false, null, null, imageParams);

        assertNotNull("Result should be non-null", result);
        assertEquals("Element placed on view",
                1, targetView.getChildren().size());
        IDiagramModelArchimateObject placed =
                (IDiagramModelArchimateObject) targetView.getChildren().get(0);
        assertEquals("imagePath written to IIconic",
                knownImagePath, placed.getImagePath());
    }

    /** Happy path: update-view-object with valid imagePath succeeds. */
    @Test
    public void shouldAcceptValidImagePath_updateViewObject() {
        MutationResult<AddToViewResultDto> addResult = accessor.addToView(
                "test-session", targetView.getId(), actor.getId(),
                10, 10, 120, 60, false, null, null, null);
        String viewObjectId = addResult.entity().viewObject().viewObjectId();

        ImageParams imageParams = new ImageParams(knownImagePath, null, null);
        accessor.updateViewObject(
                "test-session", viewObjectId,
                null, null, null, null, null, null, imageParams, null);

        IDiagramModelArchimateObject placed =
                (IDiagramModelArchimateObject) targetView.getChildren().get(0);
        assertEquals("imagePath updated via update-view-object",
                knownImagePath, placed.getImagePath());
    }

    /**
     * Regression-must-not-happen: empty-string clear sentinel
     * MUST bypass archive validation and clear the imagePath via
     * {@code setImagePath(null)} per {@code ImageHelper.applyImageToNewObject:82}.
     * If this fails, the validator is misfiring on empty-string and breaking
     * the schema's documented {@code "empty to remove"} contract
     * ({@code ViewPlacementHandler.java:1350}).
     */
    @Test
    public void shouldAcceptEmptyStringAsClear_updateViewObject() {
        MutationResult<AddToViewResultDto> addResult = accessor.addToView(
                "test-session", targetView.getId(), actor.getId(),
                10, 10, 120, 60, false, null, null,
                new ImageParams(knownImagePath, null, null));
        String viewObjectId = addResult.entity().viewObject().viewObjectId();
        IDiagramModelArchimateObject placed =
                (IDiagramModelArchimateObject) targetView.getChildren().get(0);
        assertEquals("pre-condition: imagePath set",
                knownImagePath, placed.getImagePath());

        accessor.updateViewObject(
                "test-session", viewObjectId,
                null, null, null, null, null, null,
                new ImageParams("", null, null), null);

        assertNull("Empty-string clears imagePath via setImagePath(null)",
                placed.getImagePath());
    }

    /** Reject: add-to-view with typo'd imagePath, no EMF mutation. */
    @Test
    public void shouldRejectInvalidImagePath_addToView_IMAGE_NOT_FOUND() {
        ImageParams imageParams = new ImageParams("images/doesnotexist.png", null, null);

        try {
            accessor.addToView(
                    "test-session", targetView.getId(), actor.getId(),
                    10, 10, 120, 60, false, null, null, imageParams);
            fail("Expected ModelAccessException for typo'd imagePath");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.IMAGE_NOT_FOUND, e.getErrorCode());
            assertTrue("Message should reference archive miss",
                    e.getMessage().contains("does not resolve to an image in the model archive"));
        }
        assertEquals("No EMF mutation on rejected add-to-view",
                0, targetView.getChildren().size());
    }

    /** Reject: update-view-object with typo'd imagePath, no EMF mutation. */
    @Test
    public void shouldRejectInvalidImagePath_updateViewObject_IMAGE_NOT_FOUND() {
        MutationResult<AddToViewResultDto> addResult = accessor.addToView(
                "test-session", targetView.getId(), actor.getId(),
                10, 10, 120, 60, false, null, null, null);
        String viewObjectId = addResult.entity().viewObject().viewObjectId();
        IDiagramModelArchimateObject placed =
                (IDiagramModelArchimateObject) targetView.getChildren().get(0);
        String originalImagePath = placed.getImagePath();

        ImageParams imageParams = new ImageParams("images/doesnotexist.png", null, null);
        try {
            accessor.updateViewObject(
                    "test-session", viewObjectId,
                    null, null, null, null, null, null, imageParams, null);
            fail("Expected ModelAccessException for typo'd imagePath");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.IMAGE_NOT_FOUND, e.getErrorCode());
            assertTrue("Message should reference archive miss",
                    e.getMessage().contains("does not resolve to an image in the model archive"));
        }
        assertEquals("imagePath unchanged on rejected update-view-object",
                originalImagePath, placed.getImagePath());
    }

    /**
     * Cross-surface byte-identity: the reject payload from
     * {@code add-to-view}, {@code update-view-object}, AND {@code add-image-to-view}
     * MUST be byte-identical (same code, message, suggestedCorrection) when
     * the same typo'd imagePath is passed. This is the mechanical proof of
     * the "byte-identical reject payload across surfaces" requirement.
     */
    @Test
    public void shouldProduceByteIdenticalRejectPayload_acrossAllThreeSurfaces() {
        String typo = "images/doesnotexist.png";
        ImageParams imageParams = new ImageParams(typo, null, null);

        ModelAccessException fromAddToView = captureReject(() -> accessor.addToView(
                "test-session", targetView.getId(), actor.getId(),
                10, 10, 120, 60, false, null, null, imageParams));

        MutationResult<AddToViewResultDto> seedResult = accessor.addToView(
                "test-session", targetView.getId(), actor.getId(),
                200, 200, 120, 60, false, null, null, null);
        String viewObjectId = seedResult.entity().viewObject().viewObjectId();
        ModelAccessException fromUpdateViewObject = captureReject(
                () -> accessor.updateViewObject(
                        "test-session", viewObjectId,
                        null, null, null, null, null, null, imageParams, null));

        ModelAccessException fromAddImageToView = captureReject(
                () -> accessor.addImageToView(
                        "test-session", targetView.getId(), typo,
                        300, 300, 64, 64, null, null, null, null));

        assertEquals("code identical: add-to-view vs add-image-to-view",
                fromAddImageToView.getErrorCode(), fromAddToView.getErrorCode());
        assertEquals("code identical: update-view-object vs add-image-to-view",
                fromAddImageToView.getErrorCode(), fromUpdateViewObject.getErrorCode());
        assertEquals("message identical: add-to-view vs add-image-to-view",
                fromAddImageToView.getMessage(), fromAddToView.getMessage());
        assertEquals("message identical: update-view-object vs add-image-to-view",
                fromAddImageToView.getMessage(), fromUpdateViewObject.getMessage());
        assertEquals("suggestedCorrection identical: add-to-view vs add-image-to-view",
                fromAddImageToView.getSuggestedCorrection(),
                fromAddToView.getSuggestedCorrection());
        assertEquals("suggestedCorrection identical: update-view-object vs add-image-to-view",
                fromAddImageToView.getSuggestedCorrection(),
                fromUpdateViewObject.getSuggestedCorrection());
    }

    /**
     * The add-image-to-view approval card names the destination view (targetView = "Target").
     * Lives here (not in ArchiModelAccessorImplTest) because the proposal builder runs only after the
     * archive imagePath validation passes, which needs the real {@link IArchiveManager} fixture set up
     * in this class. The other view-visual sites are covered in ArchiModelAccessorImplTest.
     */
    @Test
    public void shouldNameView_inAddImageToViewDescription_S6h() {
        accessor.getMutationDispatcher().setApprovalModeProvider(() -> true);

        MutationResult<DiagramImageDto> result = accessor.addImageToView(
                "test-session", targetView.getId(), knownImagePath,
                300, 300, 64, 64, null, null, null, null);

        assertNotNull("Approval mode should produce a proposal", result.proposalContext());
        PendingProposal pending = accessor.getMutationDispatcher().getProposal(
                "test-session", result.proposalContext().proposalId());
        assertNotNull(pending);
        assertEquals("add-image-to-view", pending.tool());
        assertEquals("Add image visual to view 'Target': " + knownImagePath,
                pending.description());
    }

    private ModelAccessException captureReject(Runnable action) {
        try {
            action.run();
            fail("Expected ModelAccessException with IMAGE_NOT_FOUND");
            return null;
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.IMAGE_NOT_FOUND, e.getErrorCode());
            return e;
        }
    }

    /**
     * Writes a 1×1 RGB PNG to {@code file} via SWT's {@link ImageLoader}.
     * The output is guaranteed to round-trip through SWT's own PNG decoder,
     * which is what Archi's {@code ArchiveManager.testImageBytesValid} uses
     * to gate {@code addImageFromFile}. A hand-rolled minimal PNG byte
     * sequence fails CRC/IDAT validation under SWT — generating the PNG
     * via SWT is the hermetic fix.
     */
    private void writeMinimalPng(File file) throws IOException {
        PaletteData palette = new PaletteData(0xff0000, 0x00ff00, 0x0000ff);
        ImageData data = new ImageData(1, 1, 24, palette);
        data.setPixel(0, 0, 0xffffff);
        ImageLoader loader = new ImageLoader();
        loader.data = new ImageData[] { data };
        try (FileOutputStream out = new FileOutputStream(file)) {
            loader.save(out, SWT.IMAGE_PNG);
        }
    }

    // ---- Test plumbing (mirror ArchiModelAccessorImplAddViewReferenceToViewTest) ----

    private ArchiModelAccessorImpl createAccessorWithTestDispatcher(
            IArchimateModel testModel) {
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
        return new ArchiModelAccessorImpl(stubModelManager, testDispatcher);
    }

    private static class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final List<PropertyChangeListener> listeners = new ArrayList<>();

        void setModels(List<IArchimateModel> models) {
            this.models = models;
        }

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
