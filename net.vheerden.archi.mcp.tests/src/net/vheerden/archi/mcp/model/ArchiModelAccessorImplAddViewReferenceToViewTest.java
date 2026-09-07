package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
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
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.EmbeddedViewDto;

/**
 * Tests for {@link ArchiModelAccessorImpl#addViewReferenceToView}.
 * Real EMF via {@link IArchimateFactory#eINSTANCE}; runs
 * as a JUnit Plug-in Test in the OSGi runtime.
 *
 * <p>The accessor under test is wired with a minimal {@link IEditorModelManager}
 * stub plus a synchronous test {@link MutationDispatcher} that bypasses
 * {@code Display.syncExec} and {@code CommandStack} (mirror of
 * {@code ArchiModelAccessorImplTest}'s {@code createAccessorWithTestDispatcher}
 * pattern). The DTO + command + validation paths are exercised end-to-end
 * against real EMF objects.</p>
 *
 * <p>The empirical live-Archi run is the broader integration
 * gate — these tests cover the EMF assembly + validation contract.</p>
 */
public class ArchiModelAccessorImplAddViewReferenceToViewTest {

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private IArchimateModel model;
    private IArchimateDiagramModel targetView;
    private IArchimateDiagramModel referencedView;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Test 14-6");
        model.setId("model-14-6");
        model.setDefaults();
        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);

        targetView = factory.createArchimateDiagramModel();
        targetView.setId("view-target");
        targetView.setName("Target");
        diagrams.getElements().add(targetView);

        referencedView = factory.createArchimateDiagramModel();
        referencedView.setId("view-referenced");
        referencedView.setName("Referenced");
        diagrams.getElements().add(referencedView);

        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    @Test
    public void shouldCreateViewReferenceObject_withSetReferencedModel() {
        MutationResult<EmbeddedViewDto> result = accessor.addViewReferenceToView(
                "test-session", targetView.getId(), referencedView.getId(),
                100, 200, 185, 80, null, null);

        assertNotNull(result);
        assertEquals(referencedView.getId(), result.entity().referencedViewId());
        assertEquals(1, targetView.getChildren().size());
        assertTrue("Child is an IDiagramModelReference",
                targetView.getChildren().get(0) instanceof IDiagramModelReference);
        IDiagramModelReference ref =
                (IDiagramModelReference) targetView.getChildren().get(0);
        assertSame("setReferencedModel wired the source view",
                referencedView, ref.getReferencedModel());
    }

    @Test
    public void shouldPlaceUnderParentGroup() {
        IDiagramModelGroup group = factory.createDiagramModelGroup();
        group.setId("vo-group");
        group.setName("Group A");
        group.setBounds(10, 10, 400, 300);
        targetView.getChildren().add(group);

        MutationResult<EmbeddedViewDto> result = accessor.addViewReferenceToView(
                "test-session", targetView.getId(), referencedView.getId(),
                30, 30, 185, 80, group.getId(), null);

        assertEquals("vo-group", result.entity().parentViewObjectId());
        assertEquals(1, group.getChildren().size());
        assertTrue("View-reference is nested inside the group",
                group.getChildren().get(0) instanceof IDiagramModelReference);
        assertEquals("Target view top-level still has only the group",
                1, targetView.getChildren().size());
    }

    @Test
    public void shouldApplyGStyling() {
        StylingParams styling = new StylingParams(
                "#FFE4B5", "#8B4513", "#000000", 255, 2,
                null, "centre", "top",
                "Arial", 14, "bold",
                "dashed", "top-bottom", null, false, 128);

        MutationResult<EmbeddedViewDto> result = accessor.addViewReferenceToView(
                "test-session", targetView.getId(), referencedView.getId(),
                50, 50, 185, 80, null, styling);

        EmbeddedViewDto dto = result.entity();
        assertEquals("#FFE4B5", dto.fillColor());
        assertEquals("#8B4513", dto.lineColor());
        // G5 surface read-back via shared StylingHelper
        assertEquals("Arial", dto.fontName());
        assertEquals(Integer.valueOf(14), dto.fontSize());
        assertEquals("bold", dto.fontStyle());
        assertEquals("dashed", dto.lineStyle());
        assertEquals("top-bottom", dto.gradient());
    }

    @Test
    public void shouldReturnAccurateDto() {
        MutationResult<EmbeddedViewDto> result = accessor.addViewReferenceToView(
                "test-session", targetView.getId(), referencedView.getId(),
                100, 200, 185, 80, null, null);

        EmbeddedViewDto dto = result.entity();
        assertNotNull("viewObjectId populated", dto.viewObjectId());
        assertEquals(referencedView.getId(), dto.referencedViewId());
        assertEquals(100, dto.x());
        assertEquals(200, dto.y());
        assertEquals(185, dto.width());
        assertEquals(80, dto.height());
        assertNull("Top-level — parentViewObjectId omitted",
                dto.parentViewObjectId());
        assertNull("note absent when no auto-placement annotation", dto.note());
    }

    @Test
    public void shouldRejectReferencedViewMissing() {
        try {
            accessor.addViewReferenceToView(
                    "test-session", targetView.getId(), "does-not-exist",
                    null, null, null, null, null, null);
            fail("Expected ModelAccessException for missing referencedViewId");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.VIEW_NOT_FOUND, e.getErrorCode());
            assertTrue(e.getMessage().contains("not found")
                    || e.getMessage().contains("not an ArchiMate view"));
        }
    }

    @Test
    public void shouldRejectReferencedViewNotArchimate() {
        // The DIAGRAMS folder ID resolves to an IFolder — not an
        // IArchimateDiagramModel — so it must be rejected (default scope).
        IFolder diagramsFolder = model.getFolder(FolderType.DIAGRAMS);

        try {
            accessor.addViewReferenceToView(
                    "test-session", targetView.getId(), diagramsFolder.getId(),
                    null, null, null, null, null, null);
            fail("Expected ModelAccessException for folder as referencedViewId");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.VIEW_NOT_FOUND, e.getErrorCode());
        }
    }

    @Test
    public void shouldHandleAutoPlacement() {
        MutationResult<EmbeddedViewDto> result = accessor.addViewReferenceToView(
                "test-session", targetView.getId(), referencedView.getId(),
                null, null, null, null, null, null);

        EmbeddedViewDto dto = result.entity();
        assertEquals("Default width pinned to 185 per Task 0.8",
                185, dto.width());
        assertEquals("Default height pinned to 80 per Task 0.8",
                80, dto.height());
        // Auto-placement yields non-negative coordinates
        assertTrue("x >= 0 for auto-placement", dto.x() >= 0);
        assertTrue("y >= 0 for auto-placement", dto.y() >= 0);
    }

    @Test
    public void shouldRejectInvalidBounds() {
        try {
            accessor.addViewReferenceToView(
                    "test-session", targetView.getId(), referencedView.getId(),
                    50, 50, 0, 80, null, null);
            fail("Expected ModelAccessException for non-positive width");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
    }

    @Test
    public void shouldRejectXWithoutY() {
        try {
            accessor.addViewReferenceToView(
                    "test-session", targetView.getId(), referencedView.getId(),
                    50, null, null, null, null, null);
            fail("Expected ModelAccessException for x without y");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue(e.getMessage().contains("x and y"));
        }
    }

    // ---- Test plumbing (minimal stub IEditorModelManager + synchronous dispatcher) ----

    /**
     * Mirrors {@code createAccessorWithTestDispatcher} in
     * {@link ArchiModelAccessorImplTest}. Bypasses {@code Display.syncExec} +
     * {@code CommandStack} so commands run synchronously in JUnit.
     */
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
        // The dispatcher now defaults to GATED (fail-safe). These tests exercise the
        // immediate-apply path, so opt approval OFF explicitly (production wires the human bit).
        testDispatcher.setApprovalModeProvider(() -> false);
        return new ArchiModelAccessorImpl(stubModelManager, testDispatcher);
    }

    /**
     * Stub {@link IEditorModelManager} — only implements methods used by
     * {@link ArchiModelAccessorImpl} construction + model lookup. Mirrors
     * {@code ArchiModelAccessorImplTest.StubEditorModelManager}.
     */
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
        @Override public void firePropertyChange(Object src, String p, Object oldV, Object newV) {}
    }
}
