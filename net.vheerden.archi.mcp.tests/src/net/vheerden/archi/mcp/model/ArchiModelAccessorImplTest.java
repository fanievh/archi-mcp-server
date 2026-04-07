package net.vheerden.archi.mcp.model;

import static org.junit.Assert.*;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.impl.MinimalEObjectImpl;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import com.archimatetool.editor.model.commands.NonNotifyingCompoundCommand;
import org.junit.Assume;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IBusinessProcess;
import com.archimatetool.model.IApplicationComponent;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFeaturesEList;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IMetadata;
import com.archimatetool.model.IProfile;
import com.archimatetool.model.IProperty;
import com.archimatetool.model.util.IModelContentListener;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;
import net.vheerden.archi.mcp.response.dto.AbsoluteBendpointDto;
import net.vheerden.archi.mcp.response.dto.AddToViewResultDto;
import net.vheerden.archi.mcp.response.dto.ApplyViewLayoutResultDto;
import net.vheerden.archi.mcp.response.dto.AutoConnectResultDto;
import net.vheerden.archi.mcp.response.dto.BendpointDto;
import net.vheerden.archi.mcp.response.dto.RemoveFromViewResultDto;
import net.vheerden.archi.mcp.response.dto.ResizeElementsResultDto;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.model.MutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.ClearViewResultDto;
import net.vheerden.archi.mcp.response.dto.ViewGroupDto;
import net.vheerden.archi.mcp.response.dto.ViewNoteDto;
import net.vheerden.archi.mcp.response.dto.BulkOperationFailure;
import net.vheerden.archi.mcp.response.dto.BulkOperationResult;
import net.vheerden.archi.mcp.response.dto.DuplicateCandidate;
import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.FolderDto;
import net.vheerden.archi.mcp.response.dto.FolderTreeDto;
import net.vheerden.archi.mcp.response.dto.LayoutViewResultDto;
import net.vheerden.archi.mcp.response.dto.LayoutWithinGroupResultDto;
import net.vheerden.archi.mcp.response.dto.ModelInfoDto;
import net.vheerden.archi.mcp.response.dto.RelationshipDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;
import net.vheerden.archi.mcp.response.dto.ViewContentsDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionSpec;
import net.vheerden.archi.mcp.response.dto.ViewObjectDto;
import net.vheerden.archi.mcp.response.dto.ViewPositionSpec;
import net.vheerden.archi.mcp.response.dto.ViewDto;

/**
 * Tests for {@link ArchiModelAccessorImpl}.
 *
 * <p>Uses a stub {@link IEditorModelManager} to avoid ArchimateTool runtime dependency.
 * Tests cover model detection, model switching, version tracking, listener notifications,
 * and query methods (getElementById, getModelInfo, getViews, getViewContents).</p>
 *
 * <p>Query method tests use {@link IArchimateFactory#eINSTANCE} to create real EMF model
 * objects with proper containment, enabling {@code eAllContents()} traversal.</p>
 */
public class ArchiModelAccessorImplTest {

    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private TestModelChangeListener changeListener;

    @Before
    public void setUp() {
        stubModelManager = new StubEditorModelManager();
        changeListener = new TestModelChangeListener();
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ---- isModelLoaded tests ----

    @Test
    public void shouldReturnFalse_whenNoModelLoaded() {
        stubModelManager.setModels(Collections.emptyList());
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        assertFalse(accessor.isModelLoaded());
    }

    @Test
    public void shouldReturnTrue_whenModelLoaded() {
        StubArchimateModel model = new StubArchimateModel("test-id", "Test Model");
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        assertTrue(accessor.isModelLoaded());
    }

    // ---- getCurrentModelName tests ----

    @Test
    public void shouldReturnModelName_whenModelLoaded() {
        StubArchimateModel model = new StubArchimateModel("id-123", "My Architecture");
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        assertTrue(accessor.getCurrentModelName().isPresent());
        assertEquals("My Architecture", accessor.getCurrentModelName().get());
    }

    @Test
    public void shouldReturnEmpty_whenNoModelForGetName() {
        stubModelManager.setModels(Collections.emptyList());
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        assertFalse(accessor.getCurrentModelName().isPresent());
    }

    // ---- getCurrentModelId tests ----

    @Test
    public void shouldReturnModelId_whenModelLoaded() {
        StubArchimateModel model = new StubArchimateModel("abc-456", "Test Model");
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        assertTrue(accessor.getCurrentModelId().isPresent());
        assertEquals("abc-456", accessor.getCurrentModelId().get());
    }

    // ---- getModelVersion tests ----

    @Test
    public void shouldReturnModelVersion_whenModelLoaded() {
        StubArchimateModel model = new StubArchimateModel("id-1", "Model");
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        assertNotNull(accessor.getModelVersion());
    }

    @Test
    public void shouldReturnNull_whenNoModelForGetVersion() {
        stubModelManager.setModels(Collections.emptyList());
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        assertNull(accessor.getModelVersion());
    }

    @Test
    public void shouldIncrementVersion_whenModelContentChanges() {
        StubArchimateModel model = new StubArchimateModel("id-1", "Model");
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        String versionBefore = accessor.getModelVersion();

        // Simulate ECORE_EVENT (model content change)
        stubModelManager.firePropertyChange(
                IEditorModelManager.PROPERTY_ECORE_EVENT, null, null);

        String versionAfter = accessor.getModelVersion();
        assertNotEquals(versionBefore, versionAfter);
    }

    // ---- Model switch tests ----

    @Test
    public void shouldUpdateModel_whenModelSwitched() {
        StubArchimateModel model1 = new StubArchimateModel("id-1", "First Model");
        stubModelManager.setModels(List.of(model1));
        accessor = new ArchiModelAccessorImpl(stubModelManager);
        accessor.addModelChangeListener(changeListener);

        assertEquals("First Model", accessor.getCurrentModelName().get());

        // Simulate opening a new model
        StubArchimateModel model2 = new StubArchimateModel("id-2", "Second Model");
        stubModelManager.firePropertyChange(
                IEditorModelManager.PROPERTY_MODEL_OPENED, null, model2);

        assertEquals("Second Model", accessor.getCurrentModelName().get());
        assertEquals("id-2", accessor.getCurrentModelId().get());

        // Verify listener was notified
        assertEquals(1, changeListener.events.size());
        assertEquals("Second Model", changeListener.events.get(0).modelName);
        assertEquals("id-2", changeListener.events.get(0).modelId);
    }

    @Test
    public void shouldDetectNewModel_whenOpenedFromEmpty() {
        stubModelManager.setModels(Collections.emptyList());
        accessor = new ArchiModelAccessorImpl(stubModelManager);
        accessor.addModelChangeListener(changeListener);

        assertFalse(accessor.isModelLoaded());

        // Simulate opening a model
        StubArchimateModel model = new StubArchimateModel("id-new", "New Model");
        stubModelManager.firePropertyChange(
                IEditorModelManager.PROPERTY_MODEL_LOADED, null, model);

        assertTrue(accessor.isModelLoaded());
        assertEquals("New Model", accessor.getCurrentModelName().get());

        // Verify listener was notified
        assertEquals(1, changeListener.events.size());
        assertEquals("New Model", changeListener.events.get(0).modelName);
    }

    @Test
    public void shouldClearModel_whenLastModelRemoved() {
        StubArchimateModel model = new StubArchimateModel("id-1", "Only Model");
        stubModelManager.setModels(new ArrayList<>(List.of(model)));
        accessor = new ArchiModelAccessorImpl(stubModelManager);
        accessor.addModelChangeListener(changeListener);

        assertTrue(accessor.isModelLoaded());

        // Simulate removing the model — manager now returns empty list
        stubModelManager.setModels(Collections.emptyList());
        stubModelManager.firePropertyChange(
                IEditorModelManager.PROPERTY_MODEL_REMOVED, model, null);

        assertFalse(accessor.isModelLoaded());
        assertFalse(accessor.getCurrentModelName().isPresent());

        // Verify listener was notified with null values
        assertEquals(1, changeListener.events.size());
        assertNull(changeListener.events.get(0).modelName);
        assertNull(changeListener.events.get(0).modelId);
    }

    @Test
    public void shouldSwitchToRemainingModel_whenActiveModelRemoved() {
        StubArchimateModel model1 = new StubArchimateModel("id-1", "Model A");
        StubArchimateModel model2 = new StubArchimateModel("id-2", "Model B");
        stubModelManager.setModels(new ArrayList<>(List.of(model1, model2)));
        accessor = new ArchiModelAccessorImpl(stubModelManager);
        accessor.addModelChangeListener(changeListener);

        assertEquals("Model A", accessor.getCurrentModelName().get());

        // Remove model1 — model2 remains
        stubModelManager.setModels(new ArrayList<>(List.of(model2)));
        stubModelManager.firePropertyChange(
                IEditorModelManager.PROPERTY_MODEL_REMOVED, model1, null);

        assertTrue(accessor.isModelLoaded());
        assertEquals("Model B", accessor.getCurrentModelName().get());

        // Verify listener notified about switch to Model B
        assertEquals(1, changeListener.events.size());
        assertEquals("Model B", changeListener.events.get(0).modelName);
    }

    // ---- getCurrentModelId edge case tests ----

    @Test
    public void shouldReturnEmptyModelId_whenNoModelLoaded() {
        stubModelManager.setModels(Collections.emptyList());
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        assertFalse(accessor.getCurrentModelId().isPresent());
    }

    // ---- Version tracking tests ----

    @Test
    public void shouldReturnInitialVersion_whenModelLoaded() {
        StubArchimateModel model = new StubArchimateModel("id-1", "Model");
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        // setActiveModel() is called once during detectActiveModel(), so version = "1"
        assertEquals("1", accessor.getModelVersion());
    }

    @Test
    public void shouldIncrementVersion_whenModelSwitched() {
        StubArchimateModel model1 = new StubArchimateModel("id-1", "Model A");
        stubModelManager.setModels(List.of(model1));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        String versionBefore = accessor.getModelVersion();

        // Simulate model switch
        StubArchimateModel model2 = new StubArchimateModel("id-2", "Model B");
        stubModelManager.firePropertyChange(
                IEditorModelManager.PROPERTY_MODEL_OPENED, null, model2);

        String versionAfter = accessor.getModelVersion();
        long before = Long.parseLong(versionBefore);
        long after = Long.parseLong(versionAfter);
        assertTrue("Version should increment on model switch", after > before);
    }

    // ---- removeModelChangeListener tests ----

    @Test
    public void shouldNotNotifyListener_afterRemoval() {
        StubArchimateModel model = new StubArchimateModel("id-1", "Model");
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);
        accessor.addModelChangeListener(changeListener);

        // Remove the listener
        accessor.removeModelChangeListener(changeListener);

        // Fire a model switch event
        StubArchimateModel model2 = new StubArchimateModel("id-2", "Model B");
        stubModelManager.firePropertyChange(
                IEditorModelManager.PROPERTY_MODEL_OPENED, null, model2);

        // Listener should NOT have been notified
        assertEquals(0, changeListener.events.size());
    }

    // ---- Listener exception isolation tests ----

    @Test
    public void shouldNotifyRemainingListeners_whenOneThrows() {
        StubArchimateModel model = new StubArchimateModel("id-1", "Model");
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        // Add a throwing listener first, then a normal one
        ModelChangeListener throwingListener = (name, id) -> {
            throw new RuntimeException("Listener failure");
        };
        accessor.addModelChangeListener(throwingListener);
        accessor.addModelChangeListener(changeListener);

        // Fire a model switch — both listeners should be attempted
        StubArchimateModel model2 = new StubArchimateModel("id-2", "Model B");
        stubModelManager.firePropertyChange(
                IEditorModelManager.PROPERTY_MODEL_OPENED, null, model2);

        // The second listener should still receive the event
        assertEquals(1, changeListener.events.size());
        assertEquals("Model B", changeListener.events.get(0).modelName());
    }

    // ---- NoModelLoadedException tests ----

    @Test(expected = NoModelLoadedException.class)
    public void shouldThrowNoModelLoaded_whenGetElementByIdWithNoModel() {
        stubModelManager.setModels(Collections.emptyList());
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        accessor.getElementById("some-id");
    }

    @Test(expected = NoModelLoadedException.class)
    public void shouldThrowNoModelLoaded_whenGetModelInfoWithNoModel() {
        stubModelManager.setModels(Collections.emptyList());
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        accessor.getModelInfo();
    }

    @Test(expected = NoModelLoadedException.class)
    public void shouldThrowNoModelLoaded_whenGetViewsWithNoModel() {
        stubModelManager.setModels(Collections.emptyList());
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        accessor.getViews(null);
    }

    @Test(expected = NoModelLoadedException.class)
    public void shouldThrowNoModelLoaded_whenGetViewContentsWithNoModel() {
        stubModelManager.setModels(Collections.emptyList());
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        accessor.getViewContents("view-1");
    }

    @Test
    public void shouldHaveCorrectErrorCodeAndMessage_whenNoModelLoaded() {
        stubModelManager.setModels(Collections.emptyList());
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        try {
            accessor.getElementById("some-id");
            fail("Expected NoModelLoadedException");
        } catch (NoModelLoadedException e) {
            assertEquals(NoModelLoadedException.ERROR_CODE, e.getErrorCode());
            assertEquals(NoModelLoadedException.DEFAULT_MESSAGE, e.getMessage());
        }
    }

    // ---- getElementById tests (using real EMF model objects) ----

    @Test
    public void shouldReturnElement_whenFoundById() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        Optional<ElementDto> result = accessor.getElementById("ba-001");

        assertTrue(result.isPresent());
        ElementDto dto = result.get();
        assertEquals("ba-001", dto.id());
        assertEquals("Customer", dto.name());
        assertEquals("BusinessActor", dto.type());
        assertEquals("Business", dto.layer());
    }

    @Test
    public void shouldReturnEmpty_whenElementNotFound() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        Optional<ElementDto> result = accessor.getElementById("nonexistent-id");

        assertFalse(result.isPresent());
    }

    @Test
    public void shouldReturnEmpty_whenIdMatchesNonElement() {
        // If the ID matches a relationship or folder (not an element), return empty
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        // "rel-001" is a relationship ID, not an element
        Optional<ElementDto> result = accessor.getElementById("rel-001");

        assertFalse(result.isPresent());
    }

    @Test
    public void shouldIncludeDocumentation_whenPresent() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        Optional<ElementDto> result = accessor.getElementById("ba-001");

        assertTrue(result.isPresent());
        assertEquals("The primary customer actor", result.get().documentation());
    }

    @Test
    public void shouldIncludeProperties_whenPresent() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        Optional<ElementDto> result = accessor.getElementById("ba-001");

        assertTrue(result.isPresent());
        assertNotNull(result.get().properties());
        assertEquals(1, result.get().properties().size());
        assertEquals("owner", result.get().properties().get(0).get("key"));
        assertEquals("team-alpha", result.get().properties().get(0).get("value"));
    }

    @Test
    public void shouldResolveApplicationLayer() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        Optional<ElementDto> result = accessor.getElementById("ac-001");

        assertTrue(result.isPresent());
        assertEquals("Application", result.get().layer());
        assertEquals("ApplicationComponent", result.get().type());
    }

    // ---- getModelInfo tests ----

    @Test
    public void shouldReturnModelInfo_withCorrectCounts() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        ModelInfoDto info = accessor.getModelInfo();

        assertEquals("Test Architecture", info.name());
        assertEquals(3, info.elementCount()); // 2 business + 1 application
        assertEquals(1, info.relationshipCount());
        assertEquals(1, info.viewCount());
    }

    @Test
    public void shouldReturnTypeDistribution() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        ModelInfoDto info = accessor.getModelInfo();

        assertNotNull(info.elementTypeDistribution());
        assertEquals(Integer.valueOf(1), info.elementTypeDistribution().get("BusinessActor"));
        assertEquals(Integer.valueOf(1), info.elementTypeDistribution().get("BusinessProcess"));
        assertEquals(Integer.valueOf(1), info.elementTypeDistribution().get("ApplicationComponent"));
    }

    @Test
    public void shouldReturnModelInfo_forEmptyModel() {
        IArchimateModel model = createEmptyModel();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        ModelInfoDto info = accessor.getModelInfo();

        assertEquals("Empty Model", info.name());
        assertEquals(0, info.elementCount());
        assertEquals(0, info.relationshipCount());
        assertEquals(0, info.viewCount());
        assertTrue(info.elementTypeDistribution().isEmpty());
    }

    // ---- getViews tests ----

    @Test
    public void shouldReturnAllViews_whenNoFilter() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        List<ViewDto> views = accessor.getViews(null);

        assertEquals(1, views.size());
        assertEquals("view-001", views.get(0).id());
        assertEquals("Main View", views.get(0).name());
    }

    @Test
    public void shouldReturnEmptyList_whenNoViewsMatchFilter() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        List<ViewDto> views = accessor.getViews("nonexistent_viewpoint");

        assertTrue(views.isEmpty());
    }

    @Test
    public void shouldReturnEmptyList_forEmptyModel() {
        IArchimateModel model = createEmptyModel();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        List<ViewDto> views = accessor.getViews(null);

        assertTrue(views.isEmpty());
    }

    @Test
    public void shouldIncludeFolderPath_inViewDto() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        List<ViewDto> views = accessor.getViews(null);

        assertEquals(1, views.size());
        assertNotNull(views.get(0).folderPath());
        // Should include the Diagrams folder name
        assertTrue(views.get(0).folderPath().contains("Views"));
    }

    // ---- getViewContents tests ----

    @Test
    public void shouldReturnViewContents_forValidView() {
        IArchimateModel model = createTestModelWithViewContents();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        Optional<ViewContentsDto> result = accessor.getViewContents("view-001");

        assertTrue(result.isPresent());
        ViewContentsDto contents = result.get();
        assertEquals("view-001", contents.viewId());
        assertEquals("Main View", contents.viewName());
        assertFalse(contents.elements().isEmpty());
    }

    @Test
    public void shouldReturnEmpty_whenViewNotFound() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        Optional<ViewContentsDto> result = accessor.getViewContents("nonexistent-view");

        assertFalse(result.isPresent());
    }

    @Test
    public void shouldIncludeVisualMetadata_inViewContents() {
        IArchimateModel model = createTestModelWithViewContents();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        Optional<ViewContentsDto> result = accessor.getViewContents("view-001");

        assertTrue(result.isPresent());
        ViewContentsDto contents = result.get();
        assertFalse(contents.visualMetadata().isEmpty());
        assertEquals(100, contents.visualMetadata().get(0).x());
        assertEquals(200, contents.visualMetadata().get(0).y());
    }

    @Test
    public void shouldIncludeRelationships_inViewContents() {
        IArchimateModel model = createTestModelWithViewContents();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        Optional<ViewContentsDto> result = accessor.getViewContents("view-001");

        assertTrue(result.isPresent());
        ViewContentsDto contents = result.get();
        assertFalse(contents.relationships().isEmpty());
        assertEquals("ServingRelationship", contents.relationships().get(0).type());
    }

    // ---- getRootFolders tests ----

    @Test
    public void shouldReturnRootFolders_whenModelLoaded() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        List<FolderDto> roots = accessor.getRootFolders();

        // setDefaults() creates 9 standard root folders
        assertEquals(9, roots.size());
        // Business folder should have 2 elements (ba-001, bp-001)
        FolderDto business = roots.stream()
                .filter(f -> "BUSINESS".equals(f.type()))
                .findFirst().orElseThrow();
        assertEquals("Business", business.name());
        assertEquals(2, business.elementCount());
    }

    @Test(expected = NoModelLoadedException.class)
    public void shouldThrowNoModelLoaded_whenGetRootFoldersWithNoModel() {
        stubModelManager.setModels(Collections.emptyList());
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        accessor.getRootFolders();
    }

    // ---- getFolderById tests ----

    @Test
    public void shouldReturnFolder_whenFoundById() {
        IArchimateModel model = createTestModelWithSubfolders();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        Optional<FolderDto> result = accessor.getFolderById("subfolder-001");

        assertTrue(result.isPresent());
        assertEquals("Core Processes", result.get().name());
        assertEquals("USER", result.get().type());
    }

    @Test
    public void shouldReturnEmpty_whenFolderNotFoundById() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        Optional<FolderDto> result = accessor.getFolderById("nonexistent-folder");

        assertFalse(result.isPresent());
    }

    // ---- getFolderChildren tests ----

    @Test
    public void shouldReturnChildren_whenParentHasSubfolders() {
        IArchimateModel model = createTestModelWithSubfolders();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        // Get Business folder ID first
        List<FolderDto> roots = accessor.getRootFolders();
        FolderDto business = roots.stream()
                .filter(f -> "BUSINESS".equals(f.type()))
                .findFirst().orElseThrow();

        List<FolderDto> children = accessor.getFolderChildren(business.id());

        assertEquals(1, children.size());
        assertEquals("Core Processes", children.get(0).name());
    }

    @Test
    public void shouldReturnEmptyList_whenParentHasNoSubfolders() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        // Application folder has no subfolders in the basic test model
        List<FolderDto> roots = accessor.getRootFolders();
        FolderDto application = roots.stream()
                .filter(f -> "APPLICATION".equals(f.type()))
                .findFirst().orElseThrow();

        List<FolderDto> children = accessor.getFolderChildren(application.id());

        assertTrue(children.isEmpty());
    }

    @Test
    public void shouldReturnEmptyList_whenFolderIdNotFoundForChildren() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        List<FolderDto> children = accessor.getFolderChildren("nonexistent");

        assertTrue(children.isEmpty());
    }

    // ---- getFolderTree tests ----

    @Test
    public void shouldReturnFullTree_whenNoRootId() {
        IArchimateModel model = createTestModelWithSubfolders();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        List<FolderTreeDto> tree = accessor.getFolderTree(null, 0);

        // 9 root folders
        assertEquals(9, tree.size());
        // Business folder should have children
        FolderTreeDto business = tree.stream()
                .filter(f -> "BUSINESS".equals(f.type()))
                .findFirst().orElseThrow();
        assertNotNull(business.children());
        assertEquals(1, business.children().size());
        assertEquals("Core Processes", business.children().get(0).name());
    }

    @Test
    public void shouldReturnSubtree_whenRootIdProvided() {
        IArchimateModel model = createTestModelWithSubfolders();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        List<FolderDto> roots = accessor.getRootFolders();
        FolderDto business = roots.stream()
                .filter(f -> "BUSINESS".equals(f.type()))
                .findFirst().orElseThrow();

        List<FolderTreeDto> tree = accessor.getFolderTree(business.id(), 0);

        assertEquals(1, tree.size());
        assertEquals("Business", tree.get(0).name());
        assertNotNull(tree.get(0).children());
    }

    @Test
    public void shouldLimitTreeDepth_whenMaxDepthProvided() {
        IArchimateModel model = createTestModelWithSubfolders();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        // Depth 1 should not include children of children
        List<FolderTreeDto> tree = accessor.getFolderTree(null, 1);

        FolderTreeDto business = tree.stream()
                .filter(f -> "BUSINESS".equals(f.type()))
                .findFirst().orElseThrow();
        // At depth 1, children of Business should be shown but not their children
        assertNotNull(business.children());
        assertEquals(1, business.children().size());
        // The subfolder "Core Processes" has a nested subfolder, which should be omitted at depth 1
        assertNull(business.children().get(0).children());
    }

    @Test
    public void shouldReturnEmptyList_whenRootIdNotFoundForTree() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        List<FolderTreeDto> tree = accessor.getFolderTree("nonexistent", 0);

        assertTrue(tree.isEmpty());
    }

    // ---- searchFolders tests ----

    @Test
    public void shouldFindFolders_whenNameMatchesCaseInsensitive() {
        IArchimateModel model = createTestModelWithSubfolders();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        List<FolderDto> results = accessor.searchFolders("business");

        // Should match "Business" root folder
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(f -> "Business".equals(f.name())));
    }

    @Test
    public void shouldFindNestedFolders_whenNameMatches() {
        IArchimateModel model = createTestModelWithSubfolders();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        List<FolderDto> results = accessor.searchFolders("Core");

        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(f -> "Core Processes".equals(f.name())));
    }

    @Test
    public void shouldReturnEmptyList_whenNoFoldersMatchSearch() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        List<FolderDto> results = accessor.searchFolders("zzz_nonexistent");

        assertTrue(results.isEmpty());
    }

    // ---- folder path tests ----

    @Test
    public void shouldBuildCorrectPath_forSubfolder() {
        IArchimateModel model = createTestModelWithSubfolders();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        Optional<FolderDto> result = accessor.getFolderById("subfolder-001");

        assertTrue(result.isPresent());
        // Path should include parent folder name
        assertTrue(result.get().path().contains("Business"));
        assertTrue(result.get().path().contains("Core Processes"));
    }

    // ---- dispose tests ----

    @Test
    public void shouldUnregisterListener_whenDisposed() {
        StubArchimateModel model = new StubArchimateModel("id-1", "Model");
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        assertTrue(stubModelManager.hasPropertyChangeListener());

        accessor.dispose();

        assertFalse(stubModelManager.hasPropertyChangeListener());
        assertFalse(accessor.isModelLoaded());
    }

    @Test
    public void shouldIgnoreEvents_afterDispose() {
        StubArchimateModel model = new StubArchimateModel("id-1", "Model");
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);
        accessor.addModelChangeListener(changeListener);

        accessor.dispose();

        // Fire event after dispose — should be ignored
        StubArchimateModel model2 = new StubArchimateModel("id-2", "Model 2");
        accessor.propertyChange(new PropertyChangeEvent(
                this, IEditorModelManager.PROPERTY_MODEL_OPENED, null, model2));

        assertEquals(0, changeListener.events.size());
    }

    // ---- createElement tests (Story 7-2) ----

    @Test
    public void shouldCreateElement_withValidType() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ElementDto> result = accessor.createElement(
                "default", "BusinessActor", "New Actor", null, null, null, null);

        assertNotNull(result);
        assertNotNull(result.entity());
        assertEquals("BusinessActor", result.entity().type());
        assertEquals("New Actor", result.entity().name());
        assertEquals("Business", result.entity().layer());
        assertNull(result.batchSequenceNumber());
    }

    @Test
    public void shouldCreateElement_withDocumentationAndProperties() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ElementDto> result = accessor.createElement(
                "default", "ApplicationComponent", "Test App",
                "Some documentation", Map.of("status", "active"), null);

        assertNotNull(result.entity());
        assertEquals("ApplicationComponent", result.entity().type());
        assertEquals("Test App", result.entity().name());
        assertEquals("Some documentation", result.entity().documentation());
        assertNotNull(result.entity().properties());
        assertEquals(1, result.entity().properties().size());
    }

    @Test
    public void shouldThrowInvalidElementType_forUnknownType() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            accessor.createElement("default", "NotARealType", "Test", null, null, null, null);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_ELEMENT_TYPE, e.getErrorCode());
        }
    }

    @Test
    public void shouldThrowInvalidElementType_forRelationshipType() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            accessor.createElement("default", "ServingRelationship", "Test", null, null, null, null);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_ELEMENT_TYPE, e.getErrorCode());
        }
    }

    @Test(expected = NoModelLoadedException.class)
    public void shouldThrowNoModelLoaded_whenCreateElementWithNoModel() {
        stubModelManager.setModels(Collections.emptyList());
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        accessor.createElement("default", "BusinessActor", "Test", null, null, null, null);
    }

    // ---- createRelationship tests (Story 7-2) ----

    @Test
    public void shouldCreateRelationship_withValidTypes() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            // ServingRelationship from ApplicationComponent to BusinessProcess (valid)
            MutationResult<RelationshipDto> result = accessor.createRelationship(
                    "default", "ServingRelationship", "ac-001", "bp-001", "serves");

            assertNotNull(result);
            assertNotNull(result.entity());
            assertEquals("ServingRelationship", result.entity().type());
            assertEquals("serves", result.entity().name());
            assertEquals("ac-001", result.entity().sourceId());
            assertEquals("bp-001", result.entity().targetId());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            // RelationshipsMatrix requires OSGi bundle — validated via E2E tests
            Assume.assumeTrue("Requires OSGi runtime for RelationshipsMatrix", false);
        }
    }

    @Test
    public void shouldThrowSourceNotFound_forInvalidSourceId() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            accessor.createRelationship("default", "ServingRelationship",
                    "nonexistent", "bp-001", null);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.SOURCE_ELEMENT_NOT_FOUND, e.getErrorCode());
        }
    }

    @Test
    public void shouldThrowTargetNotFound_forInvalidTargetId() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            accessor.createRelationship("default", "ServingRelationship",
                    "ac-001", "nonexistent", null);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.TARGET_ELEMENT_NOT_FOUND, e.getErrorCode());
        }
    }

    @Test
    public void shouldThrowRelationshipNotAllowed_forInvalidCombination() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            // CompositionRelationship between BusinessActor and ApplicationComponent
            // is not a valid ArchiMate combination
            accessor.createRelationship("default", "CompositionRelationship",
                    "ba-001", "ac-001", null);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.RELATIONSHIP_NOT_ALLOWED, e.getErrorCode());
            assertNotNull("Should include valid alternatives in details", e.getDetails());
            assertNotNull("Should include correction suggestion", e.getSuggestedCorrection());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            // RelationshipsMatrix requires OSGi bundle — validated via E2E tests
            Assume.assumeTrue("Requires OSGi runtime for RelationshipsMatrix", false);
        }
    }

    @Test
    public void shouldThrowInvalidRelationshipType_forUnknownType() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            accessor.createRelationship("default", "NotARealRelationship",
                    "ac-001", "bp-001", null);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_RELATIONSHIP_TYPE, e.getErrorCode());
        }
    }

    @Test(expected = NoModelLoadedException.class)
    public void shouldThrowNoModelLoaded_whenCreateRelationshipWithNoModel() {
        stubModelManager.setModels(Collections.emptyList());
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        accessor.createRelationship("default", "ServingRelationship", "s1", "t1", null);
    }

    // ---- duplicate relationship prevention tests (backlog-b11) ----

    @Test
    public void shouldReturnExistingRelationship_whenDuplicateCreated() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            // The test model already has a ServingRelationship from ac-001 to bp-001 (rel-001)
            MutationResult<RelationshipDto> result = accessor.createRelationship(
                    "default", "ServingRelationship", "ac-001", "bp-001", null);

            assertNotNull(result);
            assertNotNull(result.entity());
            assertEquals("rel-001", result.entity().id());
            assertEquals("ServingRelationship", result.entity().type());
            assertEquals("ac-001", result.entity().sourceId());
            assertEquals("bp-001", result.entity().targetId());
            assertTrue("Should flag as already existed", result.entity().alreadyExisted());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assume.assumeTrue("Requires OSGi runtime for RelationshipsMatrix", false);
        }
    }

    @Test
    public void shouldReturnExistingRelationship_whenDuplicateWithDifferentName() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            // Same type, source, target as existing rel-001 but different name
            MutationResult<RelationshipDto> result = accessor.createRelationship(
                    "default", "ServingRelationship", "ac-001", "bp-001", "different name");

            assertNotNull(result);
            assertEquals("rel-001", result.entity().id());
            assertTrue("Name differs but should still deduplicate", result.entity().alreadyExisted());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assume.assumeTrue("Requires OSGi runtime for RelationshipsMatrix", false);
        }
    }

    @Test
    public void shouldCreateNewRelationship_whenDifferentType() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            // Existing is ServingRelationship from ac-001 to bp-001
            // AssociationRelationship between same elements should create new
            MutationResult<RelationshipDto> result = accessor.createRelationship(
                    "default", "AssociationRelationship", "ac-001", "bp-001", null);

            assertNotNull(result);
            assertNotEquals("Should be new relationship, not existing rel-001",
                    "rel-001", result.entity().id());
            assertEquals("AssociationRelationship", result.entity().type());
            assertFalse("Should not flag as already existed", result.entity().alreadyExisted());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assume.assumeTrue("Requires OSGi runtime for RelationshipsMatrix", false);
        }
    }

    @Test
    public void shouldCreateNewRelationship_whenDifferentTarget() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            // Existing is ServingRelationship from ac-001 to bp-001
            // Same type from ac-001 to ba-001 (different target) should create new
            MutationResult<RelationshipDto> result = accessor.createRelationship(
                    "default", "ServingRelationship", "ac-001", "ba-001", null);

            assertNotNull(result);
            assertNotEquals("Should be new relationship, not existing rel-001",
                    "rel-001", result.entity().id());
            assertFalse("Should not flag as already existed", result.entity().alreadyExisted());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assume.assumeTrue("Requires OSGi runtime for RelationshipsMatrix", false);
        }
    }

    @Test
    public void shouldCreateBothRelationships_whenDuplicateInSameBatch() {
        // B19: within-batch dedup no longer works (connect() deferred to command execution).
        // Each create-relationship in the same batch creates a separate object.
        // Cross-batch dedup still works correctly.
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            List<BulkOperation> operations = List.of(
                    new BulkOperation("create-relationship", Map.of(
                            "type", "AssociationRelationship",
                            "sourceId", "ac-001",
                            "targetId", "ba-001")),
                    new BulkOperation("create-relationship", Map.of(
                            "type", "AssociationRelationship",
                            "sourceId", "ac-001",
                            "targetId", "ba-001"))
            );

            BulkMutationResult result = accessor.executeBulk("default", operations, null, false);

            assertNotNull(result);
            assertEquals(2, result.operations().size());
            // B19: both create independently (no within-batch dedup)
            String firstId = result.operations().get(0).entityId();
            String secondId = result.operations().get(1).entityId();
            assertNotEquals("B19: within-batch creates separate objects", firstId, secondId);
            assertEquals("created", result.operations().get(0).action());
            assertEquals("created", result.operations().get(1).action());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assume.assumeTrue("Requires OSGi runtime for RelationshipsMatrix", false);
        }
    }

    @Test
    public void shouldCreateIndependentBackReferences_forDuplicateRelationshipsInBatch() {
        // B19: within-batch dedup no longer works. Each relationship gets its own ID.
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            List<BulkOperation> operations = List.of(
                    new BulkOperation("create-relationship", Map.of(
                            "type", "AssociationRelationship",
                            "sourceId", "ac-001",
                            "targetId", "ba-001")),
                    new BulkOperation("create-relationship", Map.of(
                            "type", "AssociationRelationship",
                            "sourceId", "ac-001",
                            "targetId", "ba-001"))
            );

            BulkMutationResult result = accessor.executeBulk("default", operations, null, false);

            assertNotNull(result);
            assertEquals(2, result.operations().size());
            // B19: both have distinct entity IDs (no within-batch dedup)
            assertNotNull("First op should have entity ID", result.operations().get(0).entityId());
            assertNotNull("Second op should have entity ID", result.operations().get(1).entityId());
            assertNotEquals("B19: separate relationship objects",
                    result.operations().get(0).entityId(),
                    result.operations().get(1).entityId());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assume.assumeTrue("Requires OSGi runtime for RelationshipsMatrix", false);
        }
    }

    @Test
    public void shouldReportCreatedAction_forBothDuplicateRelationshipsInBatch() {
        // B19: within-batch dedup no longer works — both report "created"
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            List<BulkOperation> operations = List.of(
                    new BulkOperation("create-relationship", Map.of(
                            "type", "AssociationRelationship",
                            "sourceId", "ac-001",
                            "targetId", "ba-001")),
                    new BulkOperation("create-relationship", Map.of(
                            "type", "AssociationRelationship",
                            "sourceId", "ac-001",
                            "targetId", "ba-001"))
            );

            BulkMutationResult result = accessor.executeBulk("default", operations, null, false);

            assertNotNull(result);
            assertEquals(2, result.operations().size());
            // B19: both report "created" (no within-batch dedup)
            assertEquals("created", result.operations().get(0).action());
            assertEquals("created", result.operations().get(1).action());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assume.assumeTrue("Requires OSGi runtime for RelationshipsMatrix", false);
        }
    }

    @Test
    public void shouldBypassApprovalGate_whenDuplicateRelationshipDetected() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            // Enable approval mode
            accessor.getMutationDispatcher().setApprovalRequired("default", true);

            // The test model already has a ServingRelationship from ac-001 to bp-001 (rel-001)
            MutationResult<RelationshipDto> result = accessor.createRelationship(
                    "default", "ServingRelationship", "ac-001", "bp-001", null);

            assertNotNull(result);
            assertEquals("rel-001", result.entity().id());
            assertTrue("Should flag as already existed", result.entity().alreadyExisted());
            // Dedup should short-circuit BEFORE approval gate — no proposal context
            assertNull("Dedup should bypass approval gate", result.proposalContext());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assume.assumeTrue("Requires OSGi runtime for RelationshipsMatrix", false);
        }
    }

    // ---- createView tests (Story 7-2) ----

    @Test
    public void shouldCreateView_withNameOnly() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewDto> result = accessor.createView(
                "default", "Test View", null, null, null);

        assertNotNull(result);
        assertNotNull(result.entity());
        assertEquals("Test View", result.entity().name());
        assertNull(result.entity().viewpointType());
    }

    @Test
    public void shouldCreateView_withViewpoint() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewDto> result = accessor.createView(
                "default", "Layered View", "layered", null, null);

        assertNotNull(result.entity());
        assertEquals("Layered View", result.entity().name());
        assertEquals("layered", result.entity().viewpointType());
    }

    @Test(expected = NoModelLoadedException.class)
    public void shouldThrowNoModelLoaded_whenCreateViewWithNoModel() {
        stubModelManager.setModels(Collections.emptyList());
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        accessor.createView("default", "Test View", null, null, null);
    }

    // ---- updateElement tests (Story 7-3) ----

    @Test
    public void shouldUpdateElementName_whenNameProvided() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ElementDto> result = accessor.updateElement(
                "default", "ba-001", "New Customer Name", null, null);

        assertNotNull(result);
        assertNotNull(result.entity());
        assertEquals("ba-001", result.entity().id());
        assertEquals("New Customer Name", result.entity().name());
        assertNull(result.batchSequenceNumber());
    }

    @Test
    public void shouldUpdateElementDocumentation_whenDocProvided() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ElementDto> result = accessor.updateElement(
                "default", "ba-001", null, "Updated documentation", null);

        assertNotNull(result.entity());
        assertEquals("Updated documentation", result.entity().documentation());
        // Name should remain unchanged
        assertEquals("Customer", result.entity().name());
    }

    @Test
    public void shouldMergeProperties_whenPropertiesProvided() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // ba-001 already has property "owner"="team-alpha"
        // Add new property "status"="active"
        MutationResult<ElementDto> result = accessor.updateElement(
                "default", "ba-001", null, null, Map.of("status", "active"));

        assertNotNull(result.entity());
        assertNotNull(result.entity().properties());
        assertEquals(2, result.entity().properties().size());
    }

    @Test
    public void shouldThrowElementNotFound_forInvalidId() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            accessor.updateElement("default", "nonexistent-id", "Name", null, null);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.ELEMENT_NOT_FOUND, e.getErrorCode());
        }
    }

    @Test
    public void shouldThrowInvalidParameter_whenNoFieldsToUpdate() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            accessor.updateElement("default", "ba-001", null, null, null);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
    }

    @Test(expected = NoModelLoadedException.class)
    public void shouldThrowNoModelLoaded_whenUpdateElementWithNoModel() {
        stubModelManager.setModels(Collections.emptyList());
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        accessor.updateElement("default", "some-id", "Name", null, null);
    }

    // ---- findDuplicates / findExactMatch tests ----

    @Test
    public void shouldFindDuplicates_whenSameNameExists() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        // Exact name match scores 1.0 — well above the 0.7 threshold
        List<DuplicateCandidate> duplicates = accessor.findDuplicates("BusinessActor", "Customer");

        assertFalse("Should find duplicates for matching name", duplicates.isEmpty());
        assertEquals("ba-001", duplicates.get(0).id());
        assertEquals("Customer", duplicates.get(0).name());
        assertTrue("Similarity score should be above threshold",
                duplicates.get(0).similarityScore() >= 0.7);
    }

    @Test
    public void shouldReturnEmpty_whenNoDuplicatesExist() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        List<DuplicateCandidate> duplicates = accessor.findDuplicates(
                "BusinessActor", "Completely Unrelated Name");

        assertTrue("Should return empty when no duplicates", duplicates.isEmpty());
    }

    @Test
    public void shouldFilterByType_whenFindingDuplicates() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        // "Customer" exists as BusinessActor, not ApplicationComponent
        List<DuplicateCandidate> duplicates = accessor.findDuplicates(
                "ApplicationComponent", "Customer");

        assertTrue("Should return empty when type doesn't match", duplicates.isEmpty());
    }

    @Test
    public void shouldFindExactMatch_whenTypeAndNameMatch() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        Optional<ElementDto> match = accessor.findExactMatch("BusinessActor", "Customer");

        assertTrue("Should find exact match", match.isPresent());
        assertEquals("ba-001", match.get().id());
        assertEquals("Customer", match.get().name());
    }

    @Test
    public void shouldReturnEmpty_whenExactMatchNotFound() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        Optional<ElementDto> match = accessor.findExactMatch("BusinessActor", "NonExistent");

        assertFalse("Should return empty when no exact match", match.isPresent());
    }

    @Test
    public void shouldBeCaseInsensitive_forExactMatch() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        Optional<ElementDto> match = accessor.findExactMatch("BusinessActor", "customer");

        assertTrue("Should find match case-insensitively", match.isPresent());
        assertEquals("ba-001", match.get().id());
    }

    // ---- executeBulk tests (Story 7-5) ----

    @Test
    public void shouldExecuteBulk_withAllCreateElements() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        List<BulkOperation> ops = List.of(
                new BulkOperation("create-element",
                        Map.of("type", "BusinessActor", "name", "Actor 1")),
                new BulkOperation("create-element",
                        Map.of("type", "BusinessProcess", "name", "Process 1")),
                new BulkOperation("create-element",
                        Map.of("type", "ApplicationComponent", "name", "Component 1"))
        );

        BulkMutationResult result = accessor.executeBulk("default", ops, null, false);

        assertNotNull(result);
        assertTrue(result.allSucceeded());
        assertEquals(3, result.totalOperations());
        assertEquals(3, result.operations().size());
        assertNull(result.batchSequenceNumber());
        assertFalse(result.isBatched());

        // Verify per-operation results
        assertEquals(0, result.operations().get(0).index());
        assertEquals("create-element", result.operations().get(0).tool());
        assertEquals("created", result.operations().get(0).action());
        assertEquals("BusinessActor", result.operations().get(0).entityType());
        assertEquals("Actor 1", result.operations().get(0).entityName());
        assertNotNull(result.operations().get(0).entityId());

        assertEquals("BusinessProcess", result.operations().get(1).entityType());
        assertEquals("ApplicationComponent", result.operations().get(2).entityType());
    }

    @Test
    public void shouldExecuteBulk_withBackReferencesInRelationship() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            List<BulkOperation> ops = List.of(
                    new BulkOperation("create-element",
                            Map.of("type", "ApplicationComponent", "name", "Source App")),
                    new BulkOperation("create-element",
                            Map.of("type", "ApplicationComponent", "name", "Target App")),
                    new BulkOperation("create-relationship",
                            Map.of("type", "ServingRelationship",
                                    "sourceId", "$0.id",
                                    "targetId", "$1.id"))
            );

            BulkMutationResult result = accessor.executeBulk("default", ops, null, false);

            assertNotNull(result);
            assertTrue(result.allSucceeded());
            assertEquals(3, result.totalOperations());
            assertEquals("created", result.operations().get(2).action());
            assertEquals("ServingRelationship", result.operations().get(2).entityType());

            // Verify the relationship was created with correct source/target
            String sourceId = result.operations().get(0).entityId();
            String targetId = result.operations().get(1).entityId();
            assertNotNull(sourceId);
            assertNotNull(targetId);
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            // RelationshipsMatrix requires OSGi bundle
            Assume.assumeTrue("Requires OSGi runtime for RelationshipsMatrix", false);
        }
    }

    @Test
    public void shouldExecuteBulk_withCreateView() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        List<BulkOperation> ops = List.of(
                new BulkOperation("create-view",
                        Map.of("name", "New Architecture View"))
        );

        BulkMutationResult result = accessor.executeBulk("default", ops, null, false);

        assertNotNull(result);
        assertTrue(result.allSucceeded());
        assertEquals(1, result.totalOperations());
        assertEquals("created", result.operations().get(0).action());
        assertEquals("ArchimateDiagramModel", result.operations().get(0).entityType());
        assertEquals("New Architecture View", result.operations().get(0).entityName());
    }

    @Test
    public void shouldExecuteBulk_withUpdateElement() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        List<BulkOperation> ops = List.of(
                new BulkOperation("update-element",
                        Map.of("id", "ba-001", "name", "Renamed Customer"))
        );

        BulkMutationResult result = accessor.executeBulk("default", ops, null, false);

        assertNotNull(result);
        assertTrue(result.allSucceeded());
        assertEquals(1, result.totalOperations());
        assertEquals("updated", result.operations().get(0).action());
        assertEquals("ba-001", result.operations().get(0).entityId());
    }

    @Test
    public void shouldFailBulk_whenOperationHasInvalidType() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        List<BulkOperation> ops = List.of(
                new BulkOperation("create-element",
                        Map.of("type", "BusinessActor", "name", "Valid")),
                new BulkOperation("create-element",
                        Map.of("type", "FakeType", "name", "Invalid"))
        );

        try {
            accessor.executeBulk("default", ops, null, false);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BULK_VALIDATION_FAILED, e.getErrorCode());
            assertTrue(e.getMessage().contains("Operation 1"));
            assertTrue(e.getMessage().contains("create-element"));
        }
    }

    @Test
    public void shouldFailBulk_whenForwardBackReference() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        List<BulkOperation> ops = List.of(
                new BulkOperation("create-element",
                        Map.of("type", "BusinessActor", "name", "Actor",
                                "documentation", "$1.id")),
                new BulkOperation("create-element",
                        Map.of("type", "BusinessProcess", "name", "Process"))
        );

        try {
            accessor.executeBulk("default", ops, null, false);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BULK_VALIDATION_FAILED, e.getErrorCode());
            assertTrue(e.getMessage().contains("Operation 0"));
            assertTrue(e.getMessage().contains("future operation"));
        }
    }

    @Test
    public void shouldFailBulk_whenBackReferenceToUpdateOperation() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        List<BulkOperation> ops = List.of(
                new BulkOperation("update-element",
                        Map.of("id", "ba-001", "name", "Updated")),
                new BulkOperation("create-relationship",
                        Map.of("type", "ServingRelationship",
                                "sourceId", "$0.id",
                                "targetId", "bp-001"))
        );

        try {
            accessor.executeBulk("default", ops, null, false);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BULK_VALIDATION_FAILED, e.getErrorCode());
            assertTrue(e.getMessage().contains("Operation 1"));
            assertTrue(e.getMessage().contains("update-element"));
        }
    }

    @Test
    public void shouldFailBulk_whenInvalidBackReferenceIndex() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        List<BulkOperation> ops = List.of(
                new BulkOperation("create-element",
                        Map.of("type", "BusinessActor", "name", "Actor")),
                new BulkOperation("create-relationship",
                        Map.of("type", "ServingRelationship",
                                "sourceId", "$5.id",
                                "targetId", "$0.id"))
        );

        try {
            accessor.executeBulk("default", ops, null, false);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BULK_VALIDATION_FAILED, e.getErrorCode());
            assertTrue(e.getMessage().contains("Operation 1"));
        }
    }

    @Test
    public void shouldFailBulk_whenMissingRequiredParam() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        List<BulkOperation> ops = List.of(
                new BulkOperation("create-element",
                        Map.of("name", "No Type")) // missing 'type'
        );

        try {
            accessor.executeBulk("default", ops, null, false);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BULK_VALIDATION_FAILED, e.getErrorCode());
            assertTrue(e.getMessage().contains("Operation 0"));
        }
    }

    @Test(expected = NoModelLoadedException.class)
    public void shouldThrowNoModelLoaded_whenExecuteBulkWithNoModel() {
        stubModelManager.setModels(Collections.emptyList());
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        accessor.executeBulk("default", List.of(
                new BulkOperation("create-element",
                        Map.of("type", "BusinessActor", "name", "Test"))), null, false);
    }

    @Test
    public void shouldExecuteBulk_singleOperation() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        List<BulkOperation> ops = List.of(
                new BulkOperation("create-element",
                        Map.of("type", "BusinessActor", "name", "Solo Actor"))
        );

        BulkMutationResult result = accessor.executeBulk("default", ops, null, false);

        assertNotNull(result);
        assertTrue(result.allSucceeded());
        assertEquals(1, result.totalOperations());
        assertEquals("Solo Actor", result.operations().get(0).entityName());
    }

    @Test
    public void shouldFailBulk_whenElementNotFoundForUpdate() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        List<BulkOperation> ops = List.of(
                new BulkOperation("update-element",
                        Map.of("id", "nonexistent", "name", "New Name"))
        );

        try {
            accessor.executeBulk("default", ops, null, false);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BULK_VALIDATION_FAILED, e.getErrorCode());
            assertTrue(e.getMessage().contains("Operation 0"));
        }
    }

    @Test
    public void shouldExecuteBulk_withMixedCreateAndUpdate() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        List<BulkOperation> ops = List.of(
                new BulkOperation("create-element",
                        Map.of("type", "BusinessActor", "name", "New Actor")),
                new BulkOperation("update-element",
                        Map.of("id", "ba-001", "name", "Updated Customer"))
        );

        BulkMutationResult result = accessor.executeBulk("default", ops, null, false);

        assertNotNull(result);
        assertTrue(result.allSucceeded());
        assertEquals(2, result.totalOperations());
        assertEquals("created", result.operations().get(0).action());
        assertEquals("updated", result.operations().get(1).action());
    }

    @Test
    public void shouldFailBulk_midwayWithNoMutationsApplied() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Count elements before
        long elementCountBefore = model.getFolder(FolderType.BUSINESS)
                .getElements().size();

        List<BulkOperation> ops = List.of(
                new BulkOperation("create-element",
                        Map.of("type", "BusinessActor", "name", "Will Not Persist")),
                new BulkOperation("create-element",
                        Map.of("type", "FakeType", "name", "Fails Here")),
                new BulkOperation("create-element",
                        Map.of("type", "BusinessProcess", "name", "Never Reached"))
        );

        try {
            accessor.executeBulk("default", ops, null, false);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BULK_VALIDATION_FAILED, e.getErrorCode());
            assertTrue(e.getMessage().contains("Operation 1"));
        }

        // Verify no elements were added
        long elementCountAfter = model.getFolder(FolderType.BUSINESS)
                .getElements().size();
        assertEquals("No mutations should be applied on validation failure",
                elementCountBefore, elementCountAfter);
    }

    // ---- executeBulk with continueOnError (Story 11-9) ----

    @Test
    public void shouldExecuteBulk_continueOnError_middleOperationFails() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        long elementCountBefore = model.getFolder(FolderType.BUSINESS)
                .getElements().size();

        List<BulkOperation> ops = List.of(
                new BulkOperation("create-element",
                        Map.of("type", "BusinessActor", "name", "Actor A")),
                new BulkOperation("create-element",
                        Map.of("type", "FakeType", "name", "Fails Here")),
                new BulkOperation("create-element",
                        Map.of("type", "BusinessProcess", "name", "Process B"))
        );

        BulkMutationResult result = accessor.executeBulk("default", ops, null, true);

        assertNotNull(result);
        assertFalse("allSucceeded should be false", result.allSucceeded());
        assertEquals(3, result.totalOperations());
        assertEquals(2, result.operations().size());
        assertEquals(1, result.failedOperations().size());

        // Verify succeeded operations
        assertEquals(0, result.operations().get(0).index());
        assertEquals("create-element", result.operations().get(0).tool());
        assertEquals("Actor A", result.operations().get(0).entityName());
        assertEquals(2, result.operations().get(1).index());
        assertEquals("create-element", result.operations().get(1).tool());
        assertEquals("Process B", result.operations().get(1).entityName());

        // Verify failed operation
        BulkOperationFailure failure = result.failedOperations().get(0);
        assertEquals(1, failure.index());
        assertEquals("create-element", failure.tool());
        assertNotNull(failure.errorCode());
        assertTrue(failure.message().contains("FakeType"));

        // Verify 2 elements were added (not 3)
        long elementCountAfter = model.getFolder(FolderType.BUSINESS)
                .getElements().size();
        assertEquals(elementCountBefore + 2, elementCountAfter);
    }

    @Test
    public void shouldExecuteBulk_continueOnError_backReferenceCascade() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // This test uses back-references to a failed op — the cascade check
        // happens BEFORE relationship validation, so no OSGi dependency.
        List<BulkOperation> ops = List.of(
                new BulkOperation("create-element",
                        Map.of("type", "BusinessActor", "name", "Actor A")),
                new BulkOperation("create-element",
                        Map.of("type", "FakeType", "name", "Fails Here")),
                new BulkOperation("create-relationship",
                        Map.of("type", "AssociationRelationship",
                                "sourceId", "$0.id",
                                "targetId", "$1.id")),
                new BulkOperation("create-element",
                        Map.of("type", "BusinessProcess", "name", "Unrelated Process"))
        );

        BulkMutationResult result = accessor.executeBulk("default", ops, null, true);

        assertNotNull(result);
        assertFalse(result.allSucceeded());
        assertEquals(4, result.totalOperations());
        // Operations 0 and 3 succeed, operations 1 and 2 fail
        assertEquals(2, result.operations().size());
        assertEquals(2, result.failedOperations().size());

        // Verify operation 2 failed due to back-reference cascade
        BulkOperationFailure cascadeFailure = result.failedOperations().stream()
                .filter(f -> f.index() == 2).findFirst().orElse(null);
        assertNotNull("Operation 2 should fail due to back-reference cascade", cascadeFailure);
        assertEquals("BACK_REFERENCE_FAILED", cascadeFailure.errorCode());
        assertTrue(cascadeFailure.message().contains("$1.id"));
        assertTrue(cascadeFailure.message().contains("operation 1 failed"));

        // Verify unrelated operation 3 succeeded
        assertEquals(3, result.operations().get(1).index());
        assertEquals("Unrelated Process", result.operations().get(1).entityName());
    }

    @Test
    public void shouldExecuteBulk_continueOnError_allOperationsFail() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        long elementCountBefore = model.getFolder(FolderType.BUSINESS)
                .getElements().size();

        List<BulkOperation> ops = List.of(
                new BulkOperation("create-element",
                        Map.of("type", "FakeType1", "name", "Fails 1")),
                new BulkOperation("create-element",
                        Map.of("type", "FakeType2", "name", "Fails 2"))
        );

        BulkMutationResult result = accessor.executeBulk("default", ops, null, true);

        assertNotNull(result);
        assertFalse(result.allSucceeded());
        assertEquals(2, result.totalOperations());
        assertEquals(0, result.operations().size());
        assertEquals(2, result.failedOperations().size());

        // Verify no model change
        long elementCountAfter = model.getFolder(FolderType.BUSINESS)
                .getElements().size();
        assertEquals("No mutations should be applied when all operations fail",
                elementCountBefore, elementCountAfter);
    }

    @Test
    public void shouldExecuteBulk_continueOnError_allOperationsSucceed() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        List<BulkOperation> ops = List.of(
                new BulkOperation("create-element",
                        Map.of("type", "BusinessActor", "name", "Actor A")),
                new BulkOperation("create-element",
                        Map.of("type", "BusinessProcess", "name", "Process B"))
        );

        BulkMutationResult result = accessor.executeBulk("default", ops, null, true);

        assertNotNull(result);
        assertTrue("allSucceeded should be true when all operations succeed",
                result.allSucceeded());
        assertEquals(2, result.totalOperations());
        assertEquals(2, result.operations().size());
        assertTrue("failedOperations should be empty",
                result.failedOperations().isEmpty());
    }

    @Test
    public void shouldExecuteBulk_continueOnError_responseHasCorrectIndices() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        List<BulkOperation> ops = List.of(
                new BulkOperation("create-element",
                        Map.of("type", "BusinessActor", "name", "Actor A")),
                new BulkOperation("create-element",
                        Map.of("type", "FakeType", "name", "Fails")),
                new BulkOperation("create-element",
                        Map.of("type", "BusinessProcess", "name", "Process B")),
                new BulkOperation("create-element",
                        Map.of("type", "AnotherFake", "name", "Also Fails")),
                new BulkOperation("create-element",
                        Map.of("type", "BusinessRole", "name", "Role C"))
        );

        BulkMutationResult result = accessor.executeBulk("default", ops, null, true);

        // Succeeded: indices 0, 2, 4
        assertEquals(3, result.operations().size());
        assertEquals(0, result.operations().get(0).index());
        assertEquals(2, result.operations().get(1).index());
        assertEquals(4, result.operations().get(2).index());

        // Failed: indices 1, 3
        assertEquals(2, result.failedOperations().size());
        assertEquals(1, result.failedOperations().get(0).index());
        assertEquals(3, result.failedOperations().get(1).index());
    }

    @Test
    public void shouldExecuteBulk_continueOnError_backReferenceCascadeDoesNotAffectUnrelated() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Op 0: create element (succeeds)
        // Op 1: create element with invalid type (fails)
        // Op 2: create relationship referencing op 1 (cascade fails)
        // Op 3: create element (succeeds — no dependency on op 1)
        // Op 4: create relationship between op 0 and op 3 (succeeds — no dependency on failed ops)
        List<BulkOperation> ops = List.of(
                new BulkOperation("create-element",
                        Map.of("type", "BusinessActor", "name", "Actor A")),
                new BulkOperation("create-element",
                        Map.of("type", "FakeType", "name", "Fails")),
                new BulkOperation("create-relationship",
                        Map.of("type", "AssociationRelationship",
                                "sourceId", "$0.id",
                                "targetId", "$1.id")),
                new BulkOperation("create-element",
                        Map.of("type", "BusinessProcess", "name", "Process B")),
                new BulkOperation("create-relationship",
                        Map.of("type", "AssociationRelationship",
                                "sourceId", "$0.id",
                                "targetId", "$3.id"))
        );

        try {
            BulkMutationResult result = accessor.executeBulk("default", ops, null, true);

            // Succeeded: ops 0, 3, 4
            assertEquals(3, result.operations().size());
            assertEquals(0, result.operations().get(0).index());
            assertEquals(3, result.operations().get(1).index());
            assertEquals(4, result.operations().get(2).index());

            // Failed: ops 1 (validation), 2 (cascade)
            assertEquals(2, result.failedOperations().size());
            assertEquals(1, result.failedOperations().get(0).index());
            assertEquals(2, result.failedOperations().get(1).index());
            assertEquals("BACK_REFERENCE_FAILED",
                    result.failedOperations().get(1).errorCode());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assume.assumeTrue("Requires OSGi runtime for RelationshipsMatrix", false);
        }
    }

    @Test
    public void shouldExecuteBulk_continueOnErrorFalse_preservesAllOrNothing() {
        // Verify that default (false) still works exactly as before
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        long elementCountBefore = model.getFolder(FolderType.BUSINESS)
                .getElements().size();

        List<BulkOperation> ops = List.of(
                new BulkOperation("create-element",
                        Map.of("type", "BusinessActor", "name", "Will Not Persist")),
                new BulkOperation("create-element",
                        Map.of("type", "FakeType", "name", "Fails Here"))
        );

        try {
            accessor.executeBulk("default", ops, null, false);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BULK_VALIDATION_FAILED, e.getErrorCode());
        }

        long elementCountAfter = model.getFolder(FolderType.BUSINESS)
                .getElements().size();
        assertEquals("No mutations should be applied on validation failure",
                elementCountBefore, elementCountAfter);
    }

    // ---- executeBulk with view tools (Story 8-0b) ----

    @Test
    public void shouldExecuteBulk_shouldPlaceElementOnView_withAddToView() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        List<BulkOperation> ops = List.of(
                new BulkOperation("add-to-view",
                        Map.of("viewId", "view-001", "elementId", "ba-001",
                                "x", 100, "y", 200))
        );

        BulkMutationResult result = accessor.executeBulk("default", ops, null, false);

        assertNotNull(result);
        assertTrue(result.allSucceeded());
        assertEquals(1, result.totalOperations());
        BulkOperationResult opResult = result.operations().get(0);
        assertEquals(0, opResult.index());
        assertEquals("add-to-view", opResult.tool());
        assertEquals("placed", opResult.action());
        assertEquals("BusinessActor", opResult.entityType());
        assertEquals("Customer", opResult.entityName());
        assertNotNull(opResult.entityId());
    }

    @Test
    public void shouldExecuteBulk_shouldCreateConnectionOnView_withAddConnectionToView() {
        IArchimateModel model = createTestModelWithViewContents();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Get view object IDs from the model
        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS)
                .getElements().get(0);
        IDiagramModelArchimateObject actorVisual = (IDiagramModelArchimateObject) view.getChildren().get(0);
        IDiagramModelArchimateObject compVisual = (IDiagramModelArchimateObject) view.getChildren().get(1);

        // Remove existing connection so we can re-add it
        // First, create a fresh view with these objects but no connections
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateModel freshModel = createTestModelWithViewContents();
        IArchimateDiagramModel freshView = (IArchimateDiagramModel) freshModel.getFolder(FolderType.DIAGRAMS)
                .getElements().get(0);
        // Disconnect the existing connection
        IDiagramModelArchimateObject freshActor = (IDiagramModelArchimateObject) freshView.getChildren().get(0);
        IDiagramModelArchimateObject freshComp = (IDiagramModelArchimateObject) freshView.getChildren().get(1);
        // Remove source connections from compVisual (the connection source)
        freshComp.getSourceConnections().clear();
        freshActor.getTargetConnections().clear();

        stubModelManager.setModels(List.of(freshModel));
        accessor = createAccessorWithTestDispatcher(freshModel);

        List<BulkOperation> ops = List.of(
                new BulkOperation("add-connection-to-view",
                        Map.of("viewId", "view-001", "relationshipId", "rel-100",
                                "sourceViewObjectId", freshComp.getId(),
                                "targetViewObjectId", freshActor.getId()))
        );

        BulkMutationResult result = accessor.executeBulk("default", ops, null, false);

        assertNotNull(result);
        assertTrue(result.allSucceeded());
        assertEquals(1, result.totalOperations());
        BulkOperationResult opResult = result.operations().get(0);
        assertEquals("add-connection-to-view", opResult.tool());
        assertEquals("connected", opResult.action());
        assertEquals("ServingRelationship", opResult.entityType());
        assertNotNull(opResult.entityId());
    }

    @Test
    public void shouldExecuteBulk_shouldRemoveFromView() {
        IArchimateModel model = createTestModelWithViewContents();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS)
                .getElements().get(0);
        IDiagramModelArchimateObject actorVisual = (IDiagramModelArchimateObject) view.getChildren().get(0);

        List<BulkOperation> ops = List.of(
                new BulkOperation("remove-from-view",
                        Map.of("viewId", "view-001", "viewObjectId", actorVisual.getId()))
        );

        BulkMutationResult result = accessor.executeBulk("default", ops, null, false);

        assertNotNull(result);
        assertTrue(result.allSucceeded());
        assertEquals(1, result.totalOperations());
        BulkOperationResult opResult = result.operations().get(0);
        assertEquals("remove-from-view", opResult.tool());
        assertEquals("removed", opResult.action());
        assertEquals("viewObject", opResult.entityType());
    }

    @Test
    public void shouldExecuteBulk_shouldUpdateViewObject() {
        IArchimateModel model = createTestModelWithViewContents();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS)
                .getElements().get(0);
        IDiagramModelArchimateObject compVisual = (IDiagramModelArchimateObject) view.getChildren().get(1);

        List<BulkOperation> ops = List.of(
                new BulkOperation("update-view-object",
                        Map.of("viewObjectId", compVisual.getId(), "x", 500, "y", 300))
        );

        BulkMutationResult result = accessor.executeBulk("default", ops, null, false);

        assertNotNull(result);
        assertTrue(result.allSucceeded());
        assertEquals(1, result.totalOperations());
        BulkOperationResult opResult = result.operations().get(0);
        assertEquals("update-view-object", opResult.tool());
        assertEquals("updated", opResult.action());
        assertEquals("ApplicationComponent", opResult.entityType());
        assertEquals("Web App", opResult.entityName());
    }

    @Test
    public void shouldExecuteBulk_shouldUpdateViewConnection() {
        IArchimateModel model = createTestModelWithViewContents();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS)
                .getElements().get(0);
        IDiagramModelArchimateObject compVisual = (IDiagramModelArchimateObject) view.getChildren().get(1);

        // Find the connection from compVisual
        IDiagramModelArchimateConnection conn = null;
        for (Object c : compVisual.getSourceConnections()) {
            if (c instanceof IDiagramModelArchimateConnection ac) {
                conn = ac;
                break;
            }
        }
        assertNotNull("Test model should have a connection", conn);

        List<BulkOperation> ops = List.of(
                new BulkOperation("update-view-connection",
                        Map.of("viewConnectionId", conn.getId(),
                                "bendpoints", List.of(
                                        Map.of("startX", 10, "startY", 20, "endX", 30, "endY", 40))))
        );

        BulkMutationResult result = accessor.executeBulk("default", ops, null, false);

        assertNotNull(result);
        assertTrue(result.allSucceeded());
        assertEquals(1, result.totalOperations());
        BulkOperationResult opResult = result.operations().get(0);
        assertEquals("update-view-connection", opResult.tool());
        assertEquals("updated", opResult.action());
        assertEquals("ServingRelationship", opResult.entityType());
    }

    @Test
    public void shouldExecuteBulk_shouldSupportBackRef_createElementThenAddToView() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        List<BulkOperation> ops = List.of(
                new BulkOperation("create-element",
                        Map.of("type", "BusinessProcess", "name", "New Process")),
                new BulkOperation("add-to-view",
                        Map.of("viewId", "view-001", "elementId", "$0.id",
                                "x", 50, "y", 50))
        );

        BulkMutationResult result = accessor.executeBulk("default", ops, null, false);

        assertNotNull(result);
        assertTrue(result.allSucceeded());
        assertEquals(2, result.totalOperations());

        BulkOperationResult createResult = result.operations().get(0);
        assertEquals("created", createResult.action());
        assertEquals("BusinessProcess", createResult.entityType());

        BulkOperationResult placeResult = result.operations().get(1);
        assertEquals("placed", placeResult.action());
        assertEquals("BusinessProcess", placeResult.entityType());
        assertEquals("New Process", placeResult.entityName());
        assertNotNull(placeResult.entityId());
    }

    @Test
    public void shouldExecuteBulk_shouldSupportBackRef_addToViewThenAddConnection() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Place two existing elements on a view, then connect them
        List<BulkOperation> ops = List.of(
                new BulkOperation("add-to-view",
                        Map.of("viewId", "view-001", "elementId", "ac-001",
                                "x", 100, "y", 100)),
                new BulkOperation("add-to-view",
                        Map.of("viewId", "view-001", "elementId", "bp-001",
                                "x", 300, "y", 100)),
                new BulkOperation("add-connection-to-view",
                        Map.of("viewId", "view-001", "relationshipId", "rel-001",
                                "sourceViewObjectId", "$0.id",
                                "targetViewObjectId", "$1.id"))
        );

        BulkMutationResult result = accessor.executeBulk("default", ops, null, false);

        assertNotNull(result);
        assertTrue(result.allSucceeded());
        assertEquals(3, result.totalOperations());

        assertEquals("placed", result.operations().get(0).action());
        assertEquals("placed", result.operations().get(1).action());
        assertEquals("connected", result.operations().get(2).action());
        assertEquals("ServingRelationship", result.operations().get(2).entityType());
    }

    @Test
    public void shouldExecuteBulk_shouldSupportMixedModelAndViewOps() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            // Full pipeline: create 2 elements, create relationship, place both, connect
            List<BulkOperation> ops = List.of(
                    new BulkOperation("create-element",
                            Map.of("type", "ApplicationComponent", "name", "Service A")),
                    new BulkOperation("create-element",
                            Map.of("type", "ApplicationComponent", "name", "Service B")),
                    new BulkOperation("create-relationship",
                            Map.of("type", "ServingRelationship",
                                    "sourceId", "$0.id", "targetId", "$1.id")),
                    new BulkOperation("add-to-view",
                            Map.of("viewId", "view-001", "elementId", "$0.id",
                                    "x", 100, "y", 100)),
                    new BulkOperation("add-to-view",
                            Map.of("viewId", "view-001", "elementId", "$1.id",
                                    "x", 300, "y", 100)),
                    new BulkOperation("add-connection-to-view",
                            Map.of("viewId", "view-001", "relationshipId", "$2.id",
                                    "sourceViewObjectId", "$3.id",
                                    "targetViewObjectId", "$4.id"))
            );

            BulkMutationResult result = accessor.executeBulk("default", ops, null, false);

            assertNotNull(result);
            assertTrue(result.allSucceeded());
            assertEquals(6, result.totalOperations());
            assertEquals("created", result.operations().get(0).action());
            assertEquals("created", result.operations().get(1).action());
            assertEquals("created", result.operations().get(2).action());
            assertEquals("placed", result.operations().get(3).action());
            assertEquals("placed", result.operations().get(4).action());
            assertEquals("connected", result.operations().get(5).action());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            // RelationshipsMatrix requires OSGi bundle — validated via E2E tests
            Assume.assumeTrue("Requires OSGi runtime for RelationshipsMatrix", false);
        }
    }

    @Test
    public void shouldFailBulk_shouldRejectBackRefToRemoveOp() {
        IArchimateModel model = createTestModelWithViewContents();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS)
                .getElements().get(0);
        IDiagramModelArchimateObject actorVisual = (IDiagramModelArchimateObject) view.getChildren().get(0);

        List<BulkOperation> ops = List.of(
                new BulkOperation("remove-from-view",
                        Map.of("viewId", "view-001", "viewObjectId", actorVisual.getId())),
                new BulkOperation("update-view-object",
                        Map.of("viewObjectId", "$0.id", "x", 500))
        );

        try {
            accessor.executeBulk("default", ops, null, false);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BULK_VALIDATION_FAILED, e.getErrorCode());
            assertTrue(e.getMessage().contains("remove-from-view"));
        }
    }

    @Test
    public void shouldFailBulk_shouldRejectBackRefToUpdateViewObjectOp() {
        IArchimateModel model = createTestModelWithViewContents();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS)
                .getElements().get(0);
        IDiagramModelArchimateObject compVisual = (IDiagramModelArchimateObject) view.getChildren().get(1);

        List<BulkOperation> ops = List.of(
                new BulkOperation("update-view-object",
                        Map.of("viewObjectId", compVisual.getId(), "x", 500)),
                new BulkOperation("add-connection-to-view",
                        Map.of("viewId", "view-001", "relationshipId", "rel-100",
                                "sourceViewObjectId", "$0.id",
                                "targetViewObjectId", compVisual.getId()))
        );

        try {
            accessor.executeBulk("default", ops, null, false);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BULK_VALIDATION_FAILED, e.getErrorCode());
            assertTrue(e.getMessage().contains("update-view-object"));
        }
    }

    // ---- executeBulk back-reference fix tests (adversarial code review) ----

    @Test
    public void shouldExecuteBulk_shouldHandleBackRef_addConnectionWithBackRefRelationship() {
        // C1 fix: create-element x2, create-relationship ($0,$1), add-to-view x2,
        // add-connection-to-view with relationshipId: "$2.id"
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            List<BulkOperation> ops = List.of(
                    new BulkOperation("create-element",
                            Map.of("type", "ApplicationComponent", "name", "Svc Alpha")),
                    new BulkOperation("create-element",
                            Map.of("type", "ApplicationComponent", "name", "Svc Beta")),
                    new BulkOperation("create-relationship",
                            Map.of("type", "ServingRelationship",
                                    "sourceId", "$0.id", "targetId", "$1.id")),
                    new BulkOperation("add-to-view",
                            Map.of("viewId", "view-001", "elementId", "$0.id",
                                    "x", 100, "y", 100)),
                    new BulkOperation("add-to-view",
                            Map.of("viewId", "view-001", "elementId", "$1.id",
                                    "x", 300, "y", 100)),
                    new BulkOperation("add-connection-to-view",
                            Map.of("viewId", "view-001", "relationshipId", "$2.id",
                                    "sourceViewObjectId", "$3.id",
                                    "targetViewObjectId", "$4.id"))
            );

            BulkMutationResult result = accessor.executeBulk("default", ops, null, false);

            assertNotNull(result);
            assertTrue(result.allSucceeded());
            assertEquals(6, result.totalOperations());
            assertEquals("created", result.operations().get(0).action());
            assertEquals("created", result.operations().get(1).action());
            assertEquals("created", result.operations().get(2).action());
            assertEquals("placed", result.operations().get(3).action());
            assertEquals("placed", result.operations().get(4).action());
            assertEquals("connected", result.operations().get(5).action());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assume.assumeTrue("Requires OSGi runtime for RelationshipsMatrix", false);
        }
    }

    @Test
    public void shouldExecuteBulk_shouldHandleBackRef_addToViewThenUpdateViewObject() {
        // H2 fix: add-to-view at [0], update-view-object with viewObjectId: "$0.id"
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        List<BulkOperation> ops = List.of(
                new BulkOperation("add-to-view",
                        Map.of("viewId", "view-001", "elementId", "ba-001",
                                "x", 50, "y", 50)),
                new BulkOperation("update-view-object",
                        Map.of("viewObjectId", "$0.id", "x", 200, "y", 300))
        );

        BulkMutationResult result = accessor.executeBulk("default", ops, null, false);

        assertNotNull(result);
        assertTrue(result.allSucceeded());
        assertEquals(2, result.totalOperations());
        assertEquals("placed", result.operations().get(0).action());
        assertEquals("updated", result.operations().get(1).action());
        assertEquals("BusinessActor", result.operations().get(1).entityType());
    }

    @Test
    public void shouldExecuteBulk_shouldHandleBackRef_addConnectionThenUpdateViewConnection() {
        // H1 fix: add-to-view x2, add-connection-to-view at [2],
        // update-view-connection with viewConnectionId: "$2.id"
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        List<BulkOperation> ops = List.of(
                new BulkOperation("add-to-view",
                        Map.of("viewId", "view-001", "elementId", "ac-001",
                                "x", 100, "y", 100)),
                new BulkOperation("add-to-view",
                        Map.of("viewId", "view-001", "elementId", "bp-001",
                                "x", 300, "y", 100)),
                new BulkOperation("add-connection-to-view",
                        Map.of("viewId", "view-001", "relationshipId", "rel-001",
                                "sourceViewObjectId", "$0.id",
                                "targetViewObjectId", "$1.id")),
                new BulkOperation("update-view-connection",
                        Map.of("viewConnectionId", "$2.id",
                                "bendpoints", List.of(
                                        Map.of("startX", 10, "startY", 20,
                                                "endX", 30, "endY", 40))))
        );

        BulkMutationResult result = accessor.executeBulk("default", ops, null, false);

        assertNotNull(result);
        assertTrue(result.allSucceeded());
        assertEquals(4, result.totalOperations());
        assertEquals("placed", result.operations().get(0).action());
        assertEquals("placed", result.operations().get(1).action());
        assertEquals("connected", result.operations().get(2).action());
        assertEquals("updated", result.operations().get(3).action());
        assertEquals("ServingRelationship", result.operations().get(3).entityType());
    }

    @Test
    public void shouldAllowDuplicateElementPlacement_whenBulkExecuted() {
        // Duplicate element placement on the same view is allowed (multiple visual representations)
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        List<BulkOperation> ops = List.of(
                new BulkOperation("add-to-view",
                        Map.of("viewId", "view-001", "elementId", "ba-001",
                                "x", 50, "y", 50)),
                new BulkOperation("add-to-view",
                        Map.of("viewId", "view-001", "elementId", "ba-001",
                                "x", 200, "y", 200))
        );

        BulkMutationResult result = accessor.executeBulk("default", ops, null, false);

        assertNotNull(result);
        assertTrue(result.allSucceeded());
        assertEquals(2, result.totalOperations());
        assertEquals("placed", result.operations().get(0).action());
        assertEquals("placed", result.operations().get(1).action());
    }

    @Test
    public void shouldFailBulk_shouldRejectMalformedBendpointKeys() {
        // M1 fix: add-connection-to-view with misspelled bendpoint keys
        IArchimateModel model = createTestModelWithViewContents();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS)
                .getElements().get(0);
        IDiagramModelArchimateObject actorVisual = (IDiagramModelArchimateObject) view.getChildren().get(0);
        IDiagramModelArchimateObject compVisual = (IDiagramModelArchimateObject) view.getChildren().get(1);

        // Remove existing connection to allow re-adding
        compVisual.getSourceConnections().clear();
        actorVisual.getTargetConnections().clear();

        // Misspelled key "start_x" instead of "startX"
        List<BulkOperation> ops = List.of(
                new BulkOperation("add-connection-to-view",
                        Map.of("viewId", "view-001", "relationshipId", "rel-100",
                                "sourceViewObjectId", compVisual.getId(),
                                "targetViewObjectId", actorVisual.getId(),
                                "bendpoints", List.of(
                                        Map.of("start_x", 10, "startY", 20,
                                                "endX", 30, "endY", 40))))
        );

        try {
            accessor.executeBulk("default", ops, null, false);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BULK_VALIDATION_FAILED, e.getErrorCode());
            assertTrue(e.getMessage().contains("Missing required bendpoint field"));
        }
    }

    // ---- executeBulk group/note back-reference tests (Story 9-8 code review) ----

    @Test
    public void shouldExecuteBulk_shouldNestElementInGroup_viaBackRef() {
        // Story 9-8: add-group-to-view at [0], add-to-view with parentViewObjectId: "$0.id"
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        List<BulkOperation> ops = List.of(
                new BulkOperation("add-group-to-view",
                        Map.of("viewId", "view-001", "label", "Test Group",
                                "x", 10, "y", 10, "width", 300, "height", 200)),
                new BulkOperation("add-to-view",
                        Map.of("viewId", "view-001", "elementId", "ba-001",
                                "parentViewObjectId", "$0.id",
                                "x", 20, "y", 30))
        );

        BulkMutationResult result = accessor.executeBulk("default", ops, null, false);

        assertNotNull(result);
        assertTrue(result.allSucceeded());
        assertEquals(2, result.totalOperations());
        assertEquals("placed", result.operations().get(0).action());
        assertEquals("placed", result.operations().get(1).action());

        // Verify EMF nesting: element should be child of group
        IArchimateDiagramModel view = (IArchimateDiagramModel)
                com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, "view-001");
        assertEquals("View should have 1 top-level child (the group)",
                1, view.getChildren().size());
        IDiagramModelObject topChild = view.getChildren().get(0);
        assertTrue("Top child should be a group", topChild instanceof IDiagramModelGroup);
        IDiagramModelGroup group = (IDiagramModelGroup) topChild;
        assertEquals("Group should have 1 child (the element)",
                1, group.getChildren().size());
    }

    @Test
    public void shouldExecuteBulk_shouldNestGroupInGroup_viaBackRef() {
        // Story 9-8: add-group-to-view at [0], add-group-to-view at [1] with parentViewObjectId: "$0.id"
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        List<BulkOperation> ops = List.of(
                new BulkOperation("add-group-to-view",
                        Map.of("viewId", "view-001", "label", "Outer Group",
                                "x", 10, "y", 10, "width", 400, "height", 300)),
                new BulkOperation("add-group-to-view",
                        Map.of("viewId", "view-001", "label", "Inner Group",
                                "parentViewObjectId", "$0.id",
                                "x", 20, "y", 20, "width", 200, "height", 150))
        );

        BulkMutationResult result = accessor.executeBulk("default", ops, null, false);

        assertNotNull(result);
        assertTrue(result.allSucceeded());
        assertEquals(2, result.totalOperations());

        // Verify EMF nesting: inner group should be child of outer group
        IArchimateDiagramModel view = (IArchimateDiagramModel)
                com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, "view-001");
        assertEquals("View should have 1 top-level child (outer group)",
                1, view.getChildren().size());
        IDiagramModelObject topChild = view.getChildren().get(0);
        assertTrue("Top child should be a group", topChild instanceof IDiagramModelGroup);
        IDiagramModelGroup outerGroup = (IDiagramModelGroup) topChild;
        assertEquals("Outer group should have 1 child (inner group)",
                1, outerGroup.getChildren().size());
        assertTrue("Child should also be a group",
                outerGroup.getChildren().get(0) instanceof IDiagramModelGroup);
    }

    @Test
    public void shouldExecuteBulk_shouldNestNoteInGroup_viaBackRef() {
        // Story 9-8: add-group-to-view at [0], add-note-to-view at [1] with parentViewObjectId: "$0.id"
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        List<BulkOperation> ops = List.of(
                new BulkOperation("add-group-to-view",
                        Map.of("viewId", "view-001", "label", "Note Group",
                                "x", 10, "y", 10, "width", 300, "height", 200)),
                new BulkOperation("add-note-to-view",
                        Map.of("viewId", "view-001", "content", "A note inside group",
                                "parentViewObjectId", "$0.id",
                                "x", 20, "y", 30))
        );

        BulkMutationResult result = accessor.executeBulk("default", ops, null, false);

        assertNotNull(result);
        assertTrue(result.allSucceeded());
        assertEquals(2, result.totalOperations());
        assertEquals("placed", result.operations().get(0).action());
        assertEquals("placed", result.operations().get(1).action());

        // Verify EMF nesting: note should be child of group
        IArchimateDiagramModel view = (IArchimateDiagramModel)
                com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, "view-001");
        assertEquals("View should have 1 top-level child (the group)",
                1, view.getChildren().size());
        IDiagramModelObject topChild = view.getChildren().get(0);
        assertTrue("Top child should be a group", topChild instanceof IDiagramModelGroup);
        IDiagramModelGroup group = (IDiagramModelGroup) topChild;
        assertEquals("Group should have 1 child (the note)",
                1, group.getChildren().size());
        assertTrue("Child should be a note",
                group.getChildren().get(0) instanceof IDiagramModelNote);
    }

    // ---- addToView tests (Story 7-7) ----

    @Test
    public void shouldAddElementToView_withExplicitCoordinates() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<AddToViewResultDto> result = accessor.addToView(
                "default", "view-001", "ba-001", 100, 200, 150, 60, false, null, null, null);

        assertNotNull(result);
        assertNotNull(result.entity());
        assertNotNull(result.entity().viewObject());
        assertEquals("ba-001", result.entity().viewObject().elementId());
        assertEquals("Customer", result.entity().viewObject().elementName());
        assertEquals("BusinessActor", result.entity().viewObject().elementType());
        assertEquals(100, result.entity().viewObject().x());
        assertEquals(200, result.entity().viewObject().y());
        assertEquals(150, result.entity().viewObject().width());
        assertEquals(60, result.entity().viewObject().height());
        assertNull(result.entity().autoConnections());
    }

    @Test
    public void shouldAddElementToView_withAutoPlacementOnEmptyView() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<AddToViewResultDto> result = accessor.addToView(
                "default", "view-001", "ba-001", null, null, null, null, false, null, null, null);

        assertNotNull(result);
        // Empty view → START_X=50, START_Y=50
        assertEquals(50, result.entity().viewObject().x());
        assertEquals(50, result.entity().viewObject().y());
        // Default dimensions
        assertEquals(120, result.entity().viewObject().width());
        assertEquals(55, result.entity().viewObject().height());
    }

    @Test
    public void shouldAddElementToView_withAutoPlacementNextToExisting() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Place first element
        accessor.addToView("default", "view-001", "ba-001", 50, 50, 120, 55, false, null, null, null);

        // Place second element — should go to the right of the first
        MutationResult<AddToViewResultDto> result = accessor.addToView(
                "default", "view-001", "bp-001", null, null, null, null, false, null, null, null);

        assertNotNull(result);
        // Should be placed right of first: 50 + 120 + 30 = 200
        assertEquals(200, result.entity().viewObject().x());
        assertEquals(50, result.entity().viewObject().y());
    }

    @Test
    public void shouldAddElementToView_withDefaultDimensions() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<AddToViewResultDto> result = accessor.addToView(
                "default", "view-001", "ba-001", 100, 200, null, null, false, null, null, null);

        assertEquals(120, result.entity().viewObject().width());
        assertEquals(55, result.entity().viewObject().height());
    }

    @Test
    public void shouldAutoConnect_whenRelationshipsExist() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Place ac-001 (Order System) first
        accessor.addToView("default", "view-001", "ac-001", 50, 50, 120, 55, false, null, null, null);

        // Place bp-001 (Order Processing) with autoConnect — has serving relationship from ac-001
        MutationResult<AddToViewResultDto> result = accessor.addToView(
                "default", "view-001", "bp-001", 250, 50, 120, 55, true, null, null, null);

        assertNotNull(result.entity().autoConnections());
        assertEquals(1, result.entity().autoConnections().size());
        assertEquals("rel-001", result.entity().autoConnections().get(0).relationshipId());
    }

    @Test
    public void shouldAllowSameElementMultipleTimesOnView() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Place element first
        MutationResult<AddToViewResultDto> first = accessor.addToView(
                "default", "view-001", "ba-001", 50, 50, 120, 55, false, null, null, null);

        // Place same element again at different position — should succeed
        MutationResult<AddToViewResultDto> second = accessor.addToView(
                "default", "view-001", "ba-001", 100, 100, 120, 55, false, null, null, null);

        // Both should return distinct view object IDs
        String firstId = first.entity().viewObject().viewObjectId();
        String secondId = second.entity().viewObject().viewObjectId();
        assertNotNull(firstId);
        assertNotNull(secondId);
        assertNotEquals("Should create separate view objects", firstId, secondId);
    }

    @Test
    public void shouldThrowViewNotFound_forAddToView() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            accessor.addToView("default", "nonexistent", "ba-001", 50, 50, 120, 55, false, null, null, null);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.VIEW_NOT_FOUND, e.getErrorCode());
        }
    }

    @Test
    public void shouldThrowElementNotFound_forAddToView() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            accessor.addToView("default", "view-001", "nonexistent", 50, 50, 120, 55, false, null, null, null);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.ELEMENT_NOT_FOUND, e.getErrorCode());
        }
    }

    @Test
    public void shouldThrowInvalidParameter_whenPartialCoordinates() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            accessor.addToView("default", "view-001", "ba-001", 50, null, 120, 55, false, null, null, null);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue(e.getMessage().contains("Both x and y"));
        }
    }

    @Test
    public void shouldAutoPlacement_wrapToNewRow() {
        IArchimateModel model = createTestModelForAutoPlacement();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Fill row: place elements at x=50, 200, 350, 500, 650 (width=120, gap=30)
        // Next auto-placed at 650+120+30=800 > MAX_ROW_WIDTH → wraps
        MutationResult<AddToViewResultDto> result = accessor.addToView(
                "default", "view-ap", "elem-ap-6", null, null, null, null, false, null, null, null);

        assertNotNull(result);
        assertEquals(50, result.entity().viewObject().x());
        // Should be on next row: maxBottomY (50+55=105) + V_GAP (30) = 135
        assertEquals(135, result.entity().viewObject().y());
    }

    // ---- addConnectionToView tests (Story 7-7) ----

    @Test
    public void shouldAddConnectionToView_basic() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Place both elements
        MutationResult<AddToViewResultDto> sourceResult = accessor.addToView(
                "default", "view-001", "ac-001", 50, 50, 120, 55, false, null, null, null);
        MutationResult<AddToViewResultDto> targetResult = accessor.addToView(
                "default", "view-001", "bp-001", 250, 50, 120, 55, false, null, null, null);

        String sourceVoId = sourceResult.entity().viewObject().viewObjectId();
        String targetVoId = targetResult.entity().viewObject().viewObjectId();

        MutationResult<ViewConnectionDto> result = accessor.addConnectionToView(
                "default", "view-001", "rel-001", sourceVoId, targetVoId, null, null, null, null, null);

        assertNotNull(result);
        assertNotNull(result.entity());
        assertEquals("rel-001", result.entity().relationshipId());
        assertEquals("ServingRelationship", result.entity().relationshipType());
        assertEquals(sourceVoId, result.entity().sourceViewObjectId());
        assertEquals(targetVoId, result.entity().targetViewObjectId());
        assertNull(result.entity().bendpoints());
    }

    @Test
    public void shouldAddConnectionToView_withBendpoints() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<AddToViewResultDto> sourceResult = accessor.addToView(
                "default", "view-001", "ac-001", 50, 50, 120, 55, false, null, null, null);
        MutationResult<AddToViewResultDto> targetResult = accessor.addToView(
                "default", "view-001", "bp-001", 250, 50, 120, 55, false, null, null, null);

        List<BendpointDto> bps = List.of(
                new BendpointDto(10, 20, 30, 40),
                new BendpointDto(50, 60, 70, 80));

        MutationResult<ViewConnectionDto> result = accessor.addConnectionToView(
                "default", "view-001", "rel-001",
                sourceResult.entity().viewObject().viewObjectId(),
                targetResult.entity().viewObject().viewObjectId(), bps, null, null, null, null);

        assertNotNull(result.entity().bendpoints());
        assertEquals(2, result.entity().bendpoints().size());
    }

    @Test
    public void shouldAddConnectionToView_reversedDirection() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Place elements — relationship goes ac-001 → bp-001
        // But we pass them in reversed order (bp-001 as source, ac-001 as target)
        MutationResult<AddToViewResultDto> r1 = accessor.addToView(
                "default", "view-001", "bp-001", 50, 50, 120, 55, false, null, null, null);
        MutationResult<AddToViewResultDto> r2 = accessor.addToView(
                "default", "view-001", "ac-001", 250, 50, 120, 55, false, null, null, null);

        // Reversed direction should be allowed
        MutationResult<ViewConnectionDto> result = accessor.addConnectionToView(
                "default", "view-001", "rel-001",
                r1.entity().viewObject().viewObjectId(),
                r2.entity().viewObject().viewObjectId(), null, null, null, null, null);

        assertNotNull(result);
        assertEquals("rel-001", result.entity().relationshipId());
    }

    @Test
    public void shouldThrowRelationshipNotFound_forAddConnection() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<AddToViewResultDto> r1 = accessor.addToView(
                "default", "view-001", "ac-001", 50, 50, 120, 55, false, null, null, null);
        MutationResult<AddToViewResultDto> r2 = accessor.addToView(
                "default", "view-001", "bp-001", 250, 50, 120, 55, false, null, null, null);

        try {
            accessor.addConnectionToView("default", "view-001", "nonexistent",
                    r1.entity().viewObject().viewObjectId(),
                    r2.entity().viewObject().viewObjectId(), null, null, null, null, null);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.RELATIONSHIP_NOT_FOUND, e.getErrorCode());
        }
    }

    @Test
    public void shouldThrowViewObjectNotFound_forAddConnection() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<AddToViewResultDto> r1 = accessor.addToView(
                "default", "view-001", "ac-001", 50, 50, 120, 55, false, null, null, null);

        try {
            accessor.addConnectionToView("default", "view-001", "rel-001",
                    r1.entity().viewObject().viewObjectId(), "nonexistent", null, null, null, null, null);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.VIEW_OBJECT_NOT_FOUND, e.getErrorCode());
        }
    }

    @Test
    public void shouldThrowRelationshipMismatch_forAddConnection() {
        IArchimateModel model = createTestModel();
        // Add another element and relationship for mismatch test
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateElement extra = factory.createBusinessActor();
        extra.setId("extra-001");
        extra.setName("Extra");
        model.getFolder(FolderType.BUSINESS).getElements().add(extra);

        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Place ba-001 and extra-001 on view
        MutationResult<AddToViewResultDto> r1 = accessor.addToView(
                "default", "view-001", "ba-001", 50, 50, 120, 55, false, null, null, null);
        MutationResult<AddToViewResultDto> r2 = accessor.addToView(
                "default", "view-001", "extra-001", 250, 50, 120, 55, false, null, null, null);

        // rel-001 connects ac-001→bp-001, but we reference ba-001 and extra-001
        try {
            accessor.addConnectionToView("default", "view-001", "rel-001",
                    r1.entity().viewObject().viewObjectId(),
                    r2.entity().viewObject().viewObjectId(), null, null, null, null, null);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.RELATIONSHIP_MISMATCH, e.getErrorCode());
        }
    }

    @Test
    public void shouldThrowConnectionAlreadyOnView() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<AddToViewResultDto> r1 = accessor.addToView(
                "default", "view-001", "ac-001", 50, 50, 120, 55, false, null, null, null);
        MutationResult<AddToViewResultDto> r2 = accessor.addToView(
                "default", "view-001", "bp-001", 250, 50, 120, 55, false, null, null, null);

        String srcVo = r1.entity().viewObject().viewObjectId();
        String tgtVo = r2.entity().viewObject().viewObjectId();

        // First connection succeeds
        accessor.addConnectionToView("default", "view-001", "rel-001", srcVo, tgtVo, null, null, null, null, null);

        // Second connection for same relationship should fail
        try {
            accessor.addConnectionToView("default", "view-001", "rel-001", srcVo, tgtVo, null, null, null, null, null);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.CONNECTION_ALREADY_ON_VIEW, e.getErrorCode());
        }
    }

    // ---- updateViewObject tests (Story 7-8) ----

    @Test
    public void shouldUpdateViewObject_fullBounds() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Place element on view first
        MutationResult<AddToViewResultDto> addResult = accessor.addToView(
                "default", "view-001", "ba-001", 50, 50, 120, 55, false, null, null, null);
        String viewObjectId = addResult.entity().viewObject().viewObjectId();

        // Update all bounds
        MutationResult<ViewObjectDto> result = accessor.updateViewObject(
                "default", viewObjectId, 200, 300, 180, 80, null, null, null);

        assertNotNull(result);
        assertEquals(200, result.entity().x());
        assertEquals(300, result.entity().y());
        assertEquals(180, result.entity().width());
        assertEquals(80, result.entity().height());
    }

    @Test
    public void shouldUpdateViewObject_partialBounds() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<AddToViewResultDto> addResult = accessor.addToView(
                "default", "view-001", "ba-001", 50, 60, 120, 55, false, null, null, null);
        String viewObjectId = addResult.entity().viewObject().viewObjectId();

        // Update only x and height, leave y and width unchanged
        MutationResult<ViewObjectDto> result = accessor.updateViewObject(
                "default", viewObjectId, 200, null, null, 80, null, null, null);

        assertNotNull(result);
        assertEquals(200, result.entity().x());
        assertEquals(60, result.entity().y());  // unchanged
        assertEquals(120, result.entity().width());  // unchanged
        assertEquals(80, result.entity().height());
    }

    @Test
    public void shouldRejectUpdateViewObject_whenNoBoundsProvided() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<AddToViewResultDto> addResult = accessor.addToView(
                "default", "view-001", "ba-001", 50, 50, 120, 55, false, null, null, null);
        String viewObjectId = addResult.entity().viewObject().viewObjectId();

        try {
            accessor.updateViewObject("default", viewObjectId, null, null, null, null, null, null, null);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
    }

    @Test
    public void shouldRejectUpdateViewObject_whenViewObjectNotFound() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            accessor.updateViewObject("default", "nonexistent", 100, 100, null, null, null, null, null);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.VIEW_OBJECT_NOT_FOUND, e.getErrorCode());
        }
    }

    // ---- updateViewConnection tests (Story 7-8) ----

    @Test
    public void shouldUpdateViewConnection_replaceBendpoints() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Place two elements and create connection
        MutationResult<AddToViewResultDto> r1 = accessor.addToView(
                "default", "view-001", "ac-001", 50, 50, 120, 55, false, null, null, null);
        MutationResult<AddToViewResultDto> r2 = accessor.addToView(
                "default", "view-001", "bp-001", 250, 50, 120, 55, false, null, null, null);
        String srcVo = r1.entity().viewObject().viewObjectId();
        String tgtVo = r2.entity().viewObject().viewObjectId();

        MutationResult<ViewConnectionDto> connResult = accessor.addConnectionToView(
                "default", "view-001", "rel-001", srcVo, tgtVo, null, null, null, null, null);
        String connId = connResult.entity().viewConnectionId();

        // Update bendpoints
        List<BendpointDto> newBendpoints = List.of(new BendpointDto(30, 0, -30, 0));
        MutationResult<ViewConnectionDto> result = accessor.updateViewConnection(
                "default", connId, newBendpoints, null, null, null, null);

        assertNotNull(result);
        assertEquals(1, result.entity().bendpoints().size());
        assertEquals(30, result.entity().bendpoints().get(0).startX());
    }

    @Test
    public void shouldUpdateViewConnection_clearBendpoints() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<AddToViewResultDto> r1 = accessor.addToView(
                "default", "view-001", "ac-001", 50, 50, 120, 55, false, null, null, null);
        MutationResult<AddToViewResultDto> r2 = accessor.addToView(
                "default", "view-001", "bp-001", 250, 50, 120, 55, false, null, null, null);
        String srcVo = r1.entity().viewObject().viewObjectId();
        String tgtVo = r2.entity().viewObject().viewObjectId();

        // Create with bendpoints
        List<BendpointDto> bps = List.of(new BendpointDto(30, 0, -30, 0));
        MutationResult<ViewConnectionDto> connResult = accessor.addConnectionToView(
                "default", "view-001", "rel-001", srcVo, tgtVo, bps, null, null, null, null);
        String connId = connResult.entity().viewConnectionId();

        // Clear bendpoints with empty list
        MutationResult<ViewConnectionDto> result = accessor.updateViewConnection(
                "default", connId, List.of(), null, null, null, null);

        assertNotNull(result);
        // Empty bendpoints are represented as null (omitted from JSON via @JsonInclude NON_NULL)
        assertNull(result.entity().bendpoints());
    }

    @Test
    public void shouldRejectUpdateViewConnection_whenConnectionNotFound() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            accessor.updateViewConnection("default", "nonexistent", List.of(), null, null, null, null);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.VIEW_OBJECT_NOT_FOUND, e.getErrorCode());
        }
    }

    // ---- removeFromView tests (Story 7-8) ----

    @Test
    public void shouldRemoveElementFromView() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<AddToViewResultDto> addResult = accessor.addToView(
                "default", "view-001", "ba-001", 50, 50, 120, 55, false, null, null, null);
        String viewObjectId = addResult.entity().viewObject().viewObjectId();

        MutationResult<RemoveFromViewResultDto> result = accessor.removeFromView(
                "default", "view-001", viewObjectId);

        assertNotNull(result);
        assertEquals(viewObjectId, result.entity().removedObjectId());
        assertEquals("viewObject", result.entity().removedObjectType());
    }

    @Test
    public void shouldRemoveElementFromView_withCascadeConnections() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Place two elements and connect them
        MutationResult<AddToViewResultDto> r1 = accessor.addToView(
                "default", "view-001", "ac-001", 50, 50, 120, 55, false, null, null, null);
        MutationResult<AddToViewResultDto> r2 = accessor.addToView(
                "default", "view-001", "bp-001", 250, 50, 120, 55, false, null, null, null);
        String srcVo = r1.entity().viewObject().viewObjectId();
        String tgtVo = r2.entity().viewObject().viewObjectId();

        MutationResult<ViewConnectionDto> connResult = accessor.addConnectionToView(
                "default", "view-001", "rel-001", srcVo, tgtVo, null, null, null, null, null);
        String connId = connResult.entity().viewConnectionId();

        // Remove source element — should cascade-remove the connection
        MutationResult<RemoveFromViewResultDto> result = accessor.removeFromView(
                "default", "view-001", srcVo);

        assertNotNull(result);
        assertEquals(srcVo, result.entity().removedObjectId());
        assertEquals("viewObject", result.entity().removedObjectType());
        assertNotNull(result.entity().cascadeRemovedConnectionIds());
        assertTrue(result.entity().cascadeRemovedConnectionIds().contains(connId));
    }

    @Test
    public void shouldRemoveConnectionFromView() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<AddToViewResultDto> r1 = accessor.addToView(
                "default", "view-001", "ac-001", 50, 50, 120, 55, false, null, null, null);
        MutationResult<AddToViewResultDto> r2 = accessor.addToView(
                "default", "view-001", "bp-001", 250, 50, 120, 55, false, null, null, null);
        String srcVo = r1.entity().viewObject().viewObjectId();
        String tgtVo = r2.entity().viewObject().viewObjectId();

        MutationResult<ViewConnectionDto> connResult = accessor.addConnectionToView(
                "default", "view-001", "rel-001", srcVo, tgtVo, null, null, null, null, null);
        String connId = connResult.entity().viewConnectionId();

        // Remove connection — elements should remain
        MutationResult<RemoveFromViewResultDto> result = accessor.removeFromView(
                "default", "view-001", connId);

        assertNotNull(result);
        assertEquals(connId, result.entity().removedObjectId());
        assertEquals("viewConnection", result.entity().removedObjectType());
        assertNull(result.entity().cascadeRemovedConnectionIds());
    }

    @Test
    public void shouldRejectRemoveFromView_whenViewObjectNotFound() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            accessor.removeFromView("default", "view-001", "nonexistent");
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.VIEW_OBJECT_NOT_FOUND, e.getErrorCode());
        }
    }

    @Test
    public void shouldRejectRemoveFromView_whenViewNotFound() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            accessor.removeFromView("default", "nonexistent", "some-id");
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.VIEW_NOT_FOUND, e.getErrorCode());
        }
    }

    // ---- clearView tests (Story 8-0c) ----

    @Test
    public void shouldClearAllVisualElements() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Place two elements and a connection
        MutationResult<AddToViewResultDto> r1 = accessor.addToView(
                "default", "view-001", "ac-001", 50, 50, 120, 55, false, null, null, null);
        MutationResult<AddToViewResultDto> r2 = accessor.addToView(
                "default", "view-001", "bp-001", 250, 50, 120, 55, false, null, null, null);
        String srcVo = r1.entity().viewObject().viewObjectId();
        String tgtVo = r2.entity().viewObject().viewObjectId();
        accessor.addConnectionToView("default", "view-001", "rel-001", srcVo, tgtVo, null, null, null, null, null);

        // Clear the view
        MutationResult<ClearViewResultDto> result = accessor.clearView("default", "view-001");

        assertNotNull(result);
        assertEquals("view-001", result.entity().viewId());
        assertEquals("Main View", result.entity().viewName());
        assertEquals(2, result.entity().elementsRemoved());
        assertEquals(1, result.entity().connectionsRemoved());
    }

    @Test
    public void shouldReturnZeroCounts_forEmptyView() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // View is empty — no elements placed
        MutationResult<ClearViewResultDto> result = accessor.clearView("default", "view-001");

        assertNotNull(result);
        assertEquals("view-001", result.entity().viewId());
        assertEquals(0, result.entity().elementsRemoved());
        assertEquals(0, result.entity().connectionsRemoved());
    }

    @Test
    public void shouldRejectClearView_whenViewNotFound() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            accessor.clearView("default", "nonexistent");
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.VIEW_NOT_FOUND, e.getErrorCode());
        }
    }

    @Test
    public void shouldClearViewInBulk() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Place an element first
        accessor.addToView("default", "view-001", "ba-001", 50, 50, 120, 55, false, null, null, null);

        // Clear via bulk-mutate
        List<BulkOperation> ops = List.of(
                new BulkOperation("clear-view", Map.of("viewId", "view-001")));
        BulkMutationResult result = accessor.executeBulk("default", ops, "Clear test", false);

        assertNotNull(result);
        assertTrue(result.allSucceeded());
        assertEquals(1, result.totalOperations());
        assertEquals("cleared", result.operations().get(0).action());
        assertEquals("view", result.operations().get(0).entityType());
        assertEquals("Main View", result.operations().get(0).entityName());
    }

    @Test
    public void clearViewShouldNotBeBackReferenceable() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Place an element, clear, then try to back-reference $0.id
        accessor.addToView("default", "view-001", "ba-001", 50, 50, 120, 55, false, null, null, null);

        // clear-view produces a viewId in entityId, not a new entity ID
        // So $0.id would resolve to the viewId itself — test that bulk works
        List<BulkOperation> ops = List.of(
                new BulkOperation("clear-view", Map.of("viewId", "view-001")),
                new BulkOperation("create-element", Map.of(
                        "type", "BusinessActor", "name", "New Actor")));
        BulkMutationResult result = accessor.executeBulk("default", ops, "Clear and create", false);

        assertNotNull(result);
        assertTrue(result.allSucceeded());
        assertEquals(2, result.totalOperations());
    }

    // ---- Coordinate conversion tests (Story 8-0d) ----

    @Test
    public void shouldConvertAbsoluteToRelativeBendpoints() {
        // Source center at (110, 77), target center at (310, 77)
        // Absolute point at (200, 50)
        // Expected: startX = 200-110 = 90, startY = 50-77 = -27
        //           endX = 200-310 = -110, endY = 50-77 = -27
        List<AbsoluteBendpointDto> absolute = List.of(new AbsoluteBendpointDto(200, 50));

        List<BendpointDto> relative = ArchiModelAccessorImpl.convertAbsoluteToRelative(
                absolute, 110, 77, 310, 77);

        assertEquals(1, relative.size());
        assertEquals(90, relative.get(0).startX());
        assertEquals(-27, relative.get(0).startY());
        assertEquals(-110, relative.get(0).endX());
        assertEquals(-27, relative.get(0).endY());
    }

    @Test
    public void shouldConvertRelativeToAbsoluteBendpoints() {
        // Source center at (110, 77), target center at (310, 77)
        // Relative point: startX=90, startY=-27, endX=-110, endY=-27
        // absX = (90 + 110 + (-110) + 310) / 2 = 200
        // absY = (-27 + 77 + (-27) + 77) / 2 = 50
        List<BendpointDto> relative = List.of(new BendpointDto(90, -27, -110, -27));

        List<AbsoluteBendpointDto> absolute = ArchiModelAccessorImpl.convertRelativeToAbsolute(
                relative, 110, 77, 310, 77);

        assertEquals(1, absolute.size());
        assertEquals(200, absolute.get(0).x());
        assertEquals(50, absolute.get(0).y());
    }

    @Test
    public void shouldRoundTripAbsoluteToRelativeAndBack() {
        // Source center at (100, 200), target center at (400, 300)
        List<AbsoluteBendpointDto> originalAbsolute = List.of(
                new AbsoluteBendpointDto(250, 150),
                new AbsoluteBendpointDto(350, 280));

        // Convert absolute -> relative -> absolute
        List<BendpointDto> relative = ArchiModelAccessorImpl.convertAbsoluteToRelative(
                originalAbsolute, 100, 200, 400, 300);
        List<AbsoluteBendpointDto> roundTripped = ArchiModelAccessorImpl.convertRelativeToAbsolute(
                relative, 100, 200, 400, 300);

        assertEquals(originalAbsolute.size(), roundTripped.size());
        for (int i = 0; i < originalAbsolute.size(); i++) {
            assertEquals("x[" + i + "]", originalAbsolute.get(i).x(), roundTripped.get(i).x());
            assertEquals("y[" + i + "]", originalAbsolute.get(i).y(), roundTripped.get(i).y());
        }
    }

    @Test
    public void shouldHandleMultipleBendpointsInConversion() {
        List<AbsoluteBendpointDto> absolute = List.of(
                new AbsoluteBendpointDto(100, 100),
                new AbsoluteBendpointDto(200, 200),
                new AbsoluteBendpointDto(300, 300));

        List<BendpointDto> relative = ArchiModelAccessorImpl.convertAbsoluteToRelative(
                absolute, 50, 50, 350, 350);

        assertEquals(3, relative.size());
        // Point 1: (100-50, 100-50, 100-350, 100-350) = (50, 50, -250, -250)
        assertEquals(50, relative.get(0).startX());
        assertEquals(50, relative.get(0).startY());
        assertEquals(-250, relative.get(0).endX());
        assertEquals(-250, relative.get(0).endY());
    }

    @Test
    public void shouldHandleEmptyListInConversion() {
        List<BendpointDto> relative = ArchiModelAccessorImpl.convertAbsoluteToRelative(
                List.of(), 100, 200, 300, 400);
        assertTrue(relative.isEmpty());

        List<AbsoluteBendpointDto> absolute = ArchiModelAccessorImpl.convertRelativeToAbsolute(
                List.of(), 100, 200, 300, 400);
        assertTrue(absolute.isEmpty());
    }

    // ---- computeAbsoluteCenter tests (Story 10.15) ----

    @Test
    public void shouldComputeAbsoluteCenter_topLevelElement() {
        // Top-level element: local = absolute (parent is IDiagramModel, not IDiagramModelObject)
        IArchimateDiagramModel view = IArchimateFactory.eINSTANCE.createArchimateDiagramModel();
        IDiagramModelArchimateObject vo = IArchimateFactory.eINSTANCE.createDiagramModelArchimateObject();
        IArchimateElement element = IArchimateFactory.eINSTANCE.createBusinessActor();
        vo.setArchimateElement(element);
        vo.setBounds(100, 200, 120, 55);
        view.getChildren().add(vo);

        int[] center = ArchiModelAccessorImpl.computeAbsoluteCenter(vo);

        // center = (100 + 120/2, 200 + 55/2) = (160, 227)
        assertEquals(160, center[0]);
        assertEquals(227, center[1]);
    }

    @Test
    public void shouldComputeAbsoluteCenter_nestedInOneGroup() {
        // Element at local (30, 30, 140, 55) inside group at (20, 360)
        // Absolute center = (30 + 20 + 140/2, 30 + 360 + 55/2) = (120, 417)
        IArchimateDiagramModel view = IArchimateFactory.eINSTANCE.createArchimateDiagramModel();
        IDiagramModelGroup group = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        group.setBounds(20, 360, 300, 200);
        view.getChildren().add(group);

        IDiagramModelArchimateObject vo = IArchimateFactory.eINSTANCE.createDiagramModelArchimateObject();
        IArchimateElement element = IArchimateFactory.eINSTANCE.createBusinessActor();
        vo.setArchimateElement(element);
        vo.setBounds(30, 30, 140, 55);
        group.getChildren().add(vo);

        int[] center = ArchiModelAccessorImpl.computeAbsoluteCenter(vo);

        // absolute center: x = 30 + 20 + 70 = 120, y = 30 + 360 + 27 = 417
        assertEquals(120, center[0]);
        assertEquals(417, center[1]);
    }

    @Test
    public void shouldComputeAbsoluteCenter_nestedTwoLevels() {
        // Element at local (10, 10, 100, 50) inside inner group at (50, 50) inside outer group at (100, 200)
        IArchimateDiagramModel view = IArchimateFactory.eINSTANCE.createArchimateDiagramModel();
        IDiagramModelGroup outerGroup = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        outerGroup.setBounds(100, 200, 400, 400);
        view.getChildren().add(outerGroup);

        IDiagramModelGroup innerGroup = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        innerGroup.setBounds(50, 50, 300, 300);
        outerGroup.getChildren().add(innerGroup);

        IDiagramModelArchimateObject vo = IArchimateFactory.eINSTANCE.createDiagramModelArchimateObject();
        IArchimateElement element = IArchimateFactory.eINSTANCE.createBusinessActor();
        vo.setArchimateElement(element);
        vo.setBounds(10, 10, 100, 50);
        innerGroup.getChildren().add(vo);

        int[] center = ArchiModelAccessorImpl.computeAbsoluteCenter(vo);

        // absolute center: x = 10 + 50 + 100 + 50 = 210, y = 10 + 50 + 200 + 25 = 285
        assertEquals(210, center[0]);
        assertEquals(285, center[1]);
    }

    @Test
    public void shouldComputeAbsoluteCenter_matchesAC2Example() {
        // AC#2: element at local bounds (30, 30, 140, 55) inside group at absolute (20, 360)
        // Expected: sourceAnchor (90, 387)  — wait, AC says (90, 387):
        //   x = 30 + 20 + 140/2 = 30 + 20 + 70 = 120... but AC says 90.
        // Re-reading AC#2: "absolute = local + parent offset: x = 30 + 20 + 140/2 = 90"
        // That math: 30 + 20 + 70 = 120, not 90. The AC example has a typo (30+20=50, +70=120).
        // But the formula in AC#2 reads as: x = localX + parentOffsetX + width/2
        // Let's test the CORRECT math: 30 + 20 + 70 = 120, 30 + 360 + 27 = 417
        // (The AC example arithmetic is wrong; the formula and implementation are correct.)
        IArchimateDiagramModel view = IArchimateFactory.eINSTANCE.createArchimateDiagramModel();
        IDiagramModelGroup group = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        group.setBounds(20, 360, 300, 200);
        view.getChildren().add(group);

        IDiagramModelArchimateObject vo = IArchimateFactory.eINSTANCE.createDiagramModelArchimateObject();
        IArchimateElement element = IArchimateFactory.eINSTANCE.createBusinessProcess();
        vo.setArchimateElement(element);
        vo.setBounds(30, 30, 140, 55);
        group.getChildren().add(vo);

        int[] center = ArchiModelAccessorImpl.computeAbsoluteCenter(vo);

        assertEquals(120, center[0]); // 30 + 20 + 70
        assertEquals(417, center[1]); // 30 + 360 + 27 (int division: 55/2 = 27)
    }

    // ---- apply-positions tests (Story 9-0a, renamed 11-8) ----

    @Test
    public void applyViewLayout_shouldUpdatePositions() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Place 3 elements on view
        MutationResult<AddToViewResultDto> r1 = accessor.addToView(
                "default", "view-001", "ba-001", 50, 50, 120, 55, false, null, null, null);
        MutationResult<AddToViewResultDto> r2 = accessor.addToView(
                "default", "view-001", "bp-001", 200, 50, 120, 55, false, null, null, null);
        MutationResult<AddToViewResultDto> r3 = accessor.addToView(
                "default", "view-001", "ac-001", 350, 50, 120, 55, false, null, null, null);

        String vo1 = r1.entity().viewObject().viewObjectId();
        String vo2 = r2.entity().viewObject().viewObjectId();
        String vo3 = r3.entity().viewObject().viewObjectId();

        // Apply layout with new positions
        List<ViewPositionSpec> positions = List.of(
                new ViewPositionSpec(vo1, 100, 100, null, null),
                new ViewPositionSpec(vo2, 300, 100, null, null),
                new ViewPositionSpec(vo3, 500, 100, 150, 70));

        MutationResult<ApplyViewLayoutResultDto> result = accessor.applyViewLayout(
                "default", "view-001", positions, null, null);

        assertNotNull(result);
        assertEquals("view-001", result.entity().viewId());
        assertEquals(3, result.entity().positionsUpdated());
        assertEquals(0, result.entity().connectionsUpdated());
        assertEquals(3, result.entity().totalOperations());
    }

    @Test
    public void applyViewLayout_shouldUpdateConnections() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Place elements and create connection
        MutationResult<AddToViewResultDto> r1 = accessor.addToView(
                "default", "view-001", "ac-001", 50, 50, 120, 55, false, null, null, null);
        MutationResult<AddToViewResultDto> r2 = accessor.addToView(
                "default", "view-001", "bp-001", 250, 50, 120, 55, false, null, null, null);
        String srcVo = r1.entity().viewObject().viewObjectId();
        String tgtVo = r2.entity().viewObject().viewObjectId();

        MutationResult<ViewConnectionDto> connResult = accessor.addConnectionToView(
                "default", "view-001", "rel-001", srcVo, tgtVo, null, null, null, null, null);
        String connId = connResult.entity().viewConnectionId();

        // Apply layout with connection bendpoints using absolute coordinates
        List<AbsoluteBendpointDto> absBps = List.of(new AbsoluteBendpointDto(150, 120));
        List<ViewConnectionSpec> connections = List.of(
                new ViewConnectionSpec(connId, null, absBps));

        MutationResult<ApplyViewLayoutResultDto> result = accessor.applyViewLayout(
                "default", "view-001", null, connections, null);

        assertNotNull(result);
        assertEquals(0, result.entity().positionsUpdated());
        assertEquals(1, result.entity().connectionsUpdated());
        assertEquals(1, result.entity().totalOperations());
    }

    @Test
    public void applyViewLayout_shouldHandleMixedPositionsAndConnections() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Place elements and create connection
        MutationResult<AddToViewResultDto> r1 = accessor.addToView(
                "default", "view-001", "ac-001", 50, 50, 120, 55, false, null, null, null);
        MutationResult<AddToViewResultDto> r2 = accessor.addToView(
                "default", "view-001", "bp-001", 250, 50, 120, 55, false, null, null, null);
        String vo1 = r1.entity().viewObject().viewObjectId();
        String vo2 = r2.entity().viewObject().viewObjectId();

        MutationResult<ViewConnectionDto> connResult = accessor.addConnectionToView(
                "default", "view-001", "rel-001", vo1, vo2, null, null, null, null, null);
        String connId = connResult.entity().viewConnectionId();

        // Apply layout with both positions and connections
        List<ViewPositionSpec> positions = List.of(
                new ViewPositionSpec(vo1, 100, 200, null, null),
                new ViewPositionSpec(vo2, 400, 200, null, null));
        List<BendpointDto> bps = List.of(new BendpointDto(0, -50, 0, -50));
        List<ViewConnectionSpec> connections = List.of(
                new ViewConnectionSpec(connId, bps, null));

        MutationResult<ApplyViewLayoutResultDto> result = accessor.applyViewLayout(
                "default", "view-001", positions, connections, "Test layout");

        assertNotNull(result);
        assertEquals(2, result.entity().positionsUpdated());
        assertEquals(1, result.entity().connectionsUpdated());
        assertEquals(3, result.entity().totalOperations());
    }

    @Test
    public void applyViewLayout_shouldHandleLargeLayout() {
        // Verifies no hardcoded operation cap (AC4).
        // Uses 3 distinct elements with repeated repositions to exceed bulk-mutate's 50 limit.
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Place all 3 available elements
        MutationResult<AddToViewResultDto> r1 = accessor.addToView(
                "default", "view-001", "ba-001", 50, 50, 120, 55, false, null, null, null);
        MutationResult<AddToViewResultDto> r2 = accessor.addToView(
                "default", "view-001", "bp-001", 200, 50, 120, 55, false, null, null, null);
        MutationResult<AddToViewResultDto> r3 = accessor.addToView(
                "default", "view-001", "ac-001", 350, 50, 120, 55, false, null, null, null);
        String vo1 = r1.entity().viewObject().viewObjectId();
        String vo2 = r2.entity().viewObject().viewObjectId();
        String vo3 = r3.entity().viewObject().viewObjectId();
        String[] voIds = { vo1, vo2, vo3 };

        // Build 60 position entries cycling across the 3 elements (last-write-wins per element)
        List<ViewPositionSpec> positions = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            positions.add(new ViewPositionSpec(voIds[i % 3], 10 + i, 10 + i, null, null));
        }

        MutationResult<ApplyViewLayoutResultDto> result = accessor.applyViewLayout(
                "default", "view-001", positions, null, null);

        assertNotNull(result);
        assertEquals(60, result.entity().positionsUpdated());
        assertEquals(60, result.entity().totalOperations());
    }

    @Test
    public void applyViewLayout_shouldFailOnExcessiveOperationCount() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Build positions array exceeding MAX_LAYOUT_OPERATIONS
        // Use a valid viewObjectId so the error is about count, not invalid ID
        MutationResult<AddToViewResultDto> r1 = accessor.addToView(
                "default", "view-001", "ba-001", 50, 50, 120, 55, false, null, null, null);
        String voId = r1.entity().viewObject().viewObjectId();

        List<ViewPositionSpec> positions = new ArrayList<>();
        for (int i = 0; i < ArchiModelAccessorImpl.MAX_LAYOUT_OPERATIONS + 1; i++) {
            positions.add(new ViewPositionSpec(voId, i, i, null, null));
        }

        try {
            accessor.applyViewLayout("default", "view-001", positions, null, null);
            fail("Should throw ModelAccessException for excessive operation count");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue(e.getMessage().contains("exceeds maximum"));
        }
    }

    @Test
    public void applyViewLayout_shouldFailOnInvalidViewObjectId() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        List<ViewPositionSpec> positions = List.of(
                new ViewPositionSpec("nonexistent-vo", 100, 100, null, null));

        try {
            accessor.applyViewLayout("default", "view-001", positions, null, null);
            fail("Should throw ModelAccessException for invalid viewObjectId");
        } catch (ModelAccessException e) {
            assertTrue(e.getMessage().contains("Position entry [0]"));
            assertTrue(e.getMessage().contains("nonexistent-vo"));
        }
    }

    @Test
    public void applyViewLayout_shouldFailOnInvalidViewConnectionId() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        List<ViewConnectionSpec> connections = List.of(
                new ViewConnectionSpec("nonexistent-conn", List.of(), null));

        try {
            accessor.applyViewLayout("default", "view-001", null, connections, null);
            fail("Should throw ModelAccessException for invalid viewConnectionId");
        } catch (ModelAccessException e) {
            assertTrue(e.getMessage().contains("Connection entry [0]"));
            assertTrue(e.getMessage().contains("nonexistent-conn"));
        }
    }

    @Test
    public void applyViewLayout_shouldFailOnEmptyArrays() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            accessor.applyViewLayout("default", "view-001", List.of(), List.of(), null);
            fail("Should throw ModelAccessException for empty arrays");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue(e.getMessage().contains("At least one"));
        }
    }

    @Test
    public void applyViewLayout_shouldFailOnViewNotFound() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        List<ViewPositionSpec> positions = List.of(
                new ViewPositionSpec("vo-1", 100, 100, null, null));

        try {
            accessor.applyViewLayout("default", "nonexistent-view", positions, null, null);
            fail("Should throw ModelAccessException for view not found");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.VIEW_NOT_FOUND, e.getErrorCode());
        }
    }

    @Test
    public void applyViewLayout_shouldClearBendpointsWhenNeitherProvided() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Place elements and create connection with initial bendpoints
        MutationResult<AddToViewResultDto> r1 = accessor.addToView(
                "default", "view-001", "ac-001", 50, 50, 120, 55, false, null, null, null);
        MutationResult<AddToViewResultDto> r2 = accessor.addToView(
                "default", "view-001", "bp-001", 250, 50, 120, 55, false, null, null, null);
        String srcVo = r1.entity().viewObject().viewObjectId();
        String tgtVo = r2.entity().viewObject().viewObjectId();

        // Create connection with bendpoints
        List<BendpointDto> initialBps = List.of(new BendpointDto(0, -50, 0, -50));
        MutationResult<ViewConnectionDto> connResult = accessor.addConnectionToView(
                "default", "view-001", "rel-001", srcVo, tgtVo, initialBps, null, null, null, null);
        String connId = connResult.entity().viewConnectionId();

        // Apply layout with neither bendpoints nor absoluteBendpoints → clear
        List<ViewConnectionSpec> connections = List.of(
                new ViewConnectionSpec(connId, null, null));

        MutationResult<ApplyViewLayoutResultDto> result = accessor.applyViewLayout(
                "default", "view-001", null, connections, null);

        assertNotNull(result);
        assertEquals(1, result.entity().connectionsUpdated());
    }

    @Test
    public void applyViewLayout_shouldFailOnNullPositionsAndNullConnections() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            accessor.applyViewLayout("default", "view-001", null, null, null);
            fail("Should throw ModelAccessException when both are null");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
    }

    // ---- Note/group escape conversion tests (Story 9-0b) ----

    @Test
    public void addNoteToView_shouldConvertEscapedNewlines() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewNoteDto> result = accessor.addNoteToView(
                "default", "view-001", "Line 1\\nLine 2", null, null, 50, 50, null, null, null, null, null);

        assertNotNull(result);
        assertEquals("Line 1\nLine 2", result.entity().content());
    }

    @Test
    public void addNoteToView_shouldPreserveActualNewlines() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Real newline (U+000A) should pass through unchanged — no double conversion
        MutationResult<ViewNoteDto> result = accessor.addNoteToView(
                "default", "view-001", "Line 1\nLine 2", null, null, 50, 50, null, null, null, null, null);

        assertNotNull(result);
        assertEquals("Line 1\nLine 2", result.entity().content());
    }

    // ---- getContentBounds tests (Story B16) ----

    @Test
    public void getContentBounds_shouldReturnBoundsForPopulatedView() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Add an element to the view to give it content
        accessor.addToView("default", "view-001", "ba-001", 100, 200, 120, 55,
                false, null, null, null);

        ContentBounds bounds = accessor.getContentBounds("view-001");
        assertNotNull("Content bounds should not be null for populated view", bounds);
        assertTrue("Width should be positive", bounds.width() > 0);
        assertTrue("Height should be positive", bounds.height() > 0);
    }

    @Test
    public void getContentBounds_shouldReturnNullForEmptyView() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Create a fresh empty view
        MutationResult<ViewDto> viewResult = accessor.createView(
                "default", "Empty View", "EmptyViewpoint", null, null);
        String emptyViewId = viewResult.entity().id();

        ContentBounds bounds = accessor.getContentBounds(emptyViewId);
        assertNull("Content bounds should be null for empty view", bounds);
    }

    @Test
    public void getContentBounds_shouldExcludeNotesFromBounds() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Add an element at known coordinates
        accessor.addToView("default", "view-001", "ba-001", 100, 200, 120, 55,
                false, null, null, null);

        ContentBounds boundsBeforeNote = accessor.getContentBounds("view-001");
        assertNotNull(boundsBeforeNote);

        // Add a note far away from the element
        accessor.addNoteToView("default", "view-001", "Far away note",
                null, null, 1000, 1000, null, null, null, null, null);

        ContentBounds boundsAfterNote = accessor.getContentBounds("view-001");
        assertNotNull(boundsAfterNote);

        // Bounds should be the same — notes are excluded
        assertEquals("X should be unchanged after adding note",
                boundsBeforeNote.x(), boundsAfterNote.x(), 0.001);
        assertEquals("Y should be unchanged after adding note",
                boundsBeforeNote.y(), boundsAfterNote.y(), 0.001);
        assertEquals("Width should be unchanged after adding note",
                boundsBeforeNote.width(), boundsAfterNote.width(), 0.001);
        assertEquals("Height should be unchanged after adding note",
                boundsBeforeNote.height(), boundsAfterNote.height(), 0.001);
    }

    @Test
    public void addGroupToView_shouldConvertEscapedNewlines() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> result = accessor.addGroupToView(
                "default", "view-001", "Group\\nLabel", 50, 50, null, null, null, null, null);

        assertNotNull(result);
        assertEquals("Group\nLabel", result.entity().label());
    }

    @Test
    public void updateViewObject_shouldConvertEscapedNewlinesOnNote() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Create a note first
        MutationResult<ViewNoteDto> noteResult = accessor.addNoteToView(
                "default", "view-001", "Original", null, null, 50, 50, null, null, null, null, null);
        String noteVoId = noteResult.entity().viewObjectId();

        // Update note text with escaped newlines
        MutationResult<ViewObjectDto> result = accessor.updateViewObject(
                "default", noteVoId, null, null, null, null, "Updated\\nContent", null, null);

        assertNotNull(result);

        // Verify via EMF object that escape conversion was applied to the stored content
        IArchimateDiagramModel view = (IArchimateDiagramModel)
                com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, "view-001");
        IDiagramModelNote emfNote = (IDiagramModelNote)
                view.getChildren().stream()
                        .filter(c -> c.getId().equals(noteVoId))
                        .findFirst().orElseThrow();
        assertEquals("Updated\nContent", emfNote.getContent());
    }

    @Test
    public void bulkMutate_shouldConvertEscapedNewlinesInNote() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        List<BulkOperation> operations = List.of(
                new BulkOperation("add-note-to-view",
                        Map.of("viewId", "view-001",
                                "content", "A\\nB",
                                "x", 50, "y", 50)));

        BulkMutationResult result = accessor.executeBulk(
                "default", operations, null, false);

        assertNotNull(result);
        assertTrue(result.allSucceeded());
        assertEquals(1, result.operations().size());
        assertEquals("add-note-to-view", result.operations().get(0).tool());

        // Verify via EMF that escape conversion was applied in the bulk path
        IArchimateDiagramModel view = (IArchimateDiagramModel)
                com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, "view-001");
        IDiagramModelNote emfNote = view.getChildren().stream()
                .filter(c -> c instanceof IDiagramModelNote)
                .map(c -> (IDiagramModelNote) c)
                .findFirst().orElseThrow();
        assertEquals("A\nB", emfNote.getContent());
    }

    // ---- connectionRouterType tests (Story 9-0c) ----

    @Test
    public void createView_shouldSetManhattanRouterType() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewDto> result = accessor.createView(
                "default", "Manhattan View", null, null, "manhattan");

        assertNotNull(result);
        assertNotNull(result.entity());
        assertEquals("Manhattan View", result.entity().name());
        assertEquals("manhattan", result.entity().connectionRouterType());
    }

    @Test
    public void createView_shouldUseDefaultRouterTypeWhenOmitted() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewDto> result = accessor.createView(
                "default", "Default View", null, null, null);

        assertNotNull(result);
        assertNotNull(result.entity());
        // Default router type (manual/bendpoint) is omitted (null) from DTO
        assertNull(result.entity().connectionRouterType());
    }

    @Test
    public void createView_shouldRejectInvalidRouterType() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            accessor.createView("default", "Bad View", null, null, "diagonal");
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue(e.getMessage().contains("diagonal"));
            assertTrue(e.getMessage().contains("manhattan"));
        }
    }

    @Test
    public void createView_shouldSetManualRouterTypeExplicitly() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewDto> result = accessor.createView(
                "default", "Manual View", null, null, "manual");

        assertNotNull(result);
        // Explicit "manual" sets default — omitted from DTO
        assertNull(result.entity().connectionRouterType());
    }

    @Test
    public void createView_shouldIncludeManhattanRouterTypeInViewDto() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewDto> result = accessor.createView(
                "default", "Router DTO View", null, null, "manhattan");

        // Verify DTO field is populated
        assertEquals("manhattan", result.entity().connectionRouterType());

        // Verify the actual EMF object was set
        IArchimateDiagramModel emfView = (IArchimateDiagramModel)
                com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(
                        model, result.entity().id());
        assertNotNull(emfView);
        assertEquals(IDiagramModel.CONNECTION_ROUTER_MANHATTAN, emfView.getConnectionRouterType());
    }

    @Test
    public void getViews_shouldIncludeRouterTypeForManhattanViews() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Create a manhattan view
        accessor.createView("default", "Manhattan View", null, null, "manhattan");

        // Get all views — should include router type for the manhattan view
        List<ViewDto> views = accessor.getViews(null);
        ViewDto manhattanView = views.stream()
                .filter(v -> "Manhattan View".equals(v.name()))
                .findFirst().orElseThrow();
        assertEquals("manhattan", manhattanView.connectionRouterType());

        // The original "Main View" should have null router type (default)
        ViewDto defaultView = views.stream()
                .filter(v -> "Main View".equals(v.name()))
                .findFirst().orElseThrow();
        assertNull(defaultView.connectionRouterType());
    }

    @Test
    public void getViewContents_shouldIncludeRouterTypeForManhattanView() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Create a manhattan view
        MutationResult<ViewDto> createResult = accessor.createView(
                "default", "Manhattan Contents View", null, null, "manhattan");
        String viewId = createResult.entity().id();

        // Get view contents
        Optional<ViewContentsDto> contents = accessor.getViewContents(viewId);
        assertTrue(contents.isPresent());
        assertEquals("manhattan", contents.get().connectionRouterType());
    }

    @Test
    public void getViewContents_shouldOmitRouterTypeForDefaultView() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // view-001 has default routing
        Optional<ViewContentsDto> contents = accessor.getViewContents("view-001");
        assertTrue(contents.isPresent());
        assertNull(contents.get().connectionRouterType());
    }

    @Test
    public void updateView_shouldSetManhattanRouterType() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Create a view with default routing first
        MutationResult<ViewDto> created = accessor.createView(
                "default", "Update RT View", null, null, null);
        String viewId = created.entity().id();
        assertNull(created.entity().connectionRouterType());

        // Update to manhattan
        MutationResult<ViewDto> result = accessor.updateView(
                "default", viewId, null, null, null, null, "manhattan");

        assertNotNull(result);
        assertEquals("manhattan", result.entity().connectionRouterType());

        // Verify EMF object
        IArchimateDiagramModel emfView = (IArchimateDiagramModel)
                com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, viewId);
        assertEquals(IDiagramModel.CONNECTION_ROUTER_MANHATTAN, emfView.getConnectionRouterType());
    }

    @Test
    public void updateView_shouldRevertToDefaultWithManual() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Create a manhattan view
        MutationResult<ViewDto> created = accessor.createView(
                "default", "Revert Manual View", null, null, "manhattan");
        String viewId = created.entity().id();
        assertEquals("manhattan", created.entity().connectionRouterType());

        // Update with "manual" to revert
        MutationResult<ViewDto> result = accessor.updateView(
                "default", viewId, null, null, null, null, "manual");

        assertNotNull(result);
        assertNull(result.entity().connectionRouterType());

        // Verify EMF object reverted to BENDPOINT
        IArchimateDiagramModel emfView = (IArchimateDiagramModel)
                com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, viewId);
        assertEquals(IDiagramModel.CONNECTION_ROUTER_BENDPOINT, emfView.getConnectionRouterType());
    }

    @Test
    public void updateView_shouldRevertToDefaultWithEmptyString() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Create a manhattan view
        MutationResult<ViewDto> created = accessor.createView(
                "default", "Revert Empty View", null, null, "manhattan");
        String viewId = created.entity().id();
        assertEquals("manhattan", created.entity().connectionRouterType());

        // Update with "" to clear/revert
        MutationResult<ViewDto> result = accessor.updateView(
                "default", viewId, null, null, null, null, "");

        assertNotNull(result);
        assertNull(result.entity().connectionRouterType());

        // Verify EMF object reverted to BENDPOINT
        IArchimateDiagramModel emfView = (IArchimateDiagramModel)
                com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, viewId);
        assertEquals(IDiagramModel.CONNECTION_ROUTER_BENDPOINT, emfView.getConnectionRouterType());
    }

    @Test
    public void updateView_shouldRejectInvalidRouterType() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            accessor.updateView("default", "view-001", null, null, null, null, "diagonal");
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue(e.getMessage().contains("diagonal"));
            assertTrue(e.getMessage().contains("manhattan"));
        }
    }

    // ---- bulk-mutate connectionRouterType tests (Story 9-0c, AC8) ----

    @Test
    public void bulkMutate_shouldCreateViewWithManhattanRouterType() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        List<BulkOperation> ops = List.of(
                new BulkOperation("create-view", Map.of(
                        "name", "Bulk Manhattan View",
                        "connectionRouterType", "manhattan")));
        BulkMutationResult result = accessor.executeBulk("default", ops, "Bulk create", false);

        assertNotNull(result);
        assertTrue(result.allSucceeded());
        assertEquals(1, result.totalOperations());
        assertEquals("created", result.operations().get(0).action());

        // Verify the created view has manhattan routing
        String viewId = result.operations().get(0).entityId();
        IArchimateDiagramModel emfView = (IArchimateDiagramModel)
                com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, viewId);
        assertNotNull(emfView);
        assertEquals(IDiagramModel.CONNECTION_ROUTER_MANHATTAN, emfView.getConnectionRouterType());
    }

    @Test
    public void bulkMutate_shouldUpdateViewWithManhattanRouterType() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // view-001 exists with default routing
        List<BulkOperation> ops = List.of(
                new BulkOperation("update-view", Map.of(
                        "viewId", "view-001",
                        "connectionRouterType", "manhattan")));
        BulkMutationResult result = accessor.executeBulk("default", ops, "Bulk update", false);

        assertNotNull(result);
        assertTrue(result.allSucceeded());
        assertEquals(1, result.totalOperations());
        assertEquals("updated", result.operations().get(0).action());

        // Verify the view now has manhattan routing
        IArchimateDiagramModel emfView = (IArchimateDiagramModel)
                com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, "view-001");
        assertEquals(IDiagramModel.CONNECTION_ROUTER_MANHATTAN, emfView.getConnectionRouterType());
    }

    // ---- layoutView tests (Story 9-1) ----

    @Test
    public void layoutView_shouldApplyTreeLayout() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Place 3 elements on view
        accessor.addToView("default", "view-001", "ba-001", 50, 50, 120, 55, false, null, null, null);
        accessor.addToView("default", "view-001", "bp-001", 50, 50, 120, 55, false, null, null, null);
        accessor.addToView("default", "view-001", "ac-001", 50, 50, 120, 55, false, null, null, null);

        MutationResult<LayoutViewResultDto> result =
                accessor.layoutView("default", "view-001", "tree", null, null);

        assertNotNull(result);
        assertEquals("view-001", result.entity().viewId());
        assertEquals("tree", result.entity().algorithmUsed());
        assertNull(result.entity().presetUsed());
        assertEquals(3, result.entity().elementsRepositioned());
    }

    @Test
    public void layoutView_shouldApplyPresetLayout() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        accessor.addToView("default", "view-001", "ba-001", 50, 50, 120, 55, false, null, null, null);
        accessor.addToView("default", "view-001", "bp-001", 200, 50, 120, 55, false, null, null, null);

        MutationResult<LayoutViewResultDto> result =
                accessor.layoutView("default", "view-001", null, "compact", null);

        assertNotNull(result);
        assertEquals("grid", result.entity().algorithmUsed());
        assertEquals("compact", result.entity().presetUsed());
        assertEquals(2, result.entity().elementsRepositioned());
    }

    @Test
    public void layoutView_shouldClearConnectionBendpoints() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Place elements and create connection
        MutationResult<AddToViewResultDto> r1 = accessor.addToView(
                "default", "view-001", "ac-001", 50, 50, 120, 55, false, null, null, null);
        MutationResult<AddToViewResultDto> r2 = accessor.addToView(
                "default", "view-001", "bp-001", 250, 50, 120, 55, false, null, null, null);

        String vo1 = r1.entity().viewObject().viewObjectId();
        String vo2 = r2.entity().viewObject().viewObjectId();

        accessor.addConnectionToView("default", "view-001", "rel-001",
                vo1, vo2, null, null, null, null, null);

        MutationResult<LayoutViewResultDto> result =
                accessor.layoutView("default", "view-001", "tree", null, null);

        assertNotNull(result);
        assertTrue("Should have cleared connections",
                result.entity().connectionsCleared() > 0);
    }

    @Test(expected = ModelAccessException.class)
    public void layoutView_shouldFailOnViewNotFound() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        accessor.layoutView("default", "nonexistent-view", "tree", null, null);
    }

    @Test(expected = ModelAccessException.class)
    public void layoutView_shouldFailOnBothAlgorithmAndPreset() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        accessor.addToView("default", "view-001", "ba-001", 50, 50, 120, 55, false, null, null, null);
        accessor.layoutView("default", "view-001", "tree", "compact", null);
    }

    @Test(expected = ModelAccessException.class)
    public void layoutView_shouldFailOnNeitherAlgorithmNorPreset() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        accessor.addToView("default", "view-001", "ba-001", 50, 50, 120, 55, false, null, null, null);
        accessor.layoutView("default", "view-001", null, null, null);
    }

    @Test(expected = ModelAccessException.class)
    public void layoutView_shouldFailOnEmptyView() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // View exists but has no elements
        accessor.layoutView("default", "view-001", "tree", null, null);
    }

    @Test
    public void layoutView_shouldFailOnInvalidAlgorithm() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        accessor.addToView("default", "view-001", "ba-001", 50, 50, 120, 55, false, null, null, null);
        try {
            accessor.layoutView("default", "view-001", "banana", null, null);
            fail("Should have thrown ModelAccessException");
        } catch (ModelAccessException e) {
            assertTrue("Error should list valid algorithms",
                    e.getMessage().contains("tree") && e.getMessage().contains("spring"));
        }
    }

    @Test
    public void layoutView_shouldPassSpacingToAlgorithm() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        accessor.addToView("default", "view-001", "ba-001", 50, 50, 120, 55, false, null, null, null);
        accessor.addToView("default", "view-001", "bp-001", 200, 50, 120, 55, false, null, null, null);

        MutationResult<LayoutViewResultDto> result =
                accessor.layoutView("default", "view-001", "grid", null,
                        Map.of("spacing", 80));

        assertNotNull(result);
        assertEquals(2, result.entity().elementsRepositioned());
    }

    // ---- Layout within group tests (Story 9-9) ----

    @Test
    public void layoutWithinGroup_shouldApplyRowArrangement() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Create group, then add elements inside it
        MutationResult<ViewGroupDto> groupResult = accessor.addGroupToView(
                "default", "view-001", "Test Group", 50, 50, 400, 200, null, null, null);
        String groupVoId = groupResult.entity().viewObjectId();

        accessor.addToView("default", "view-001", "ba-001", 0, 0, 120, 55, false, groupVoId, null, null);
        accessor.addToView("default", "view-001", "bp-001", 0, 0, 120, 55, false, groupVoId, null, null);
        accessor.addToView("default", "view-001", "ac-001", 0, 0, 120, 55, false, groupVoId, null, null);

        MutationResult<LayoutWithinGroupResultDto> result =
                accessor.layoutWithinGroup("default", "view-001", groupVoId,
                        "row", null, null, null, null, false, false, null, false);

        assertNotNull(result);
        assertEquals("view-001", result.entity().viewId());
        assertEquals(groupVoId, result.entity().groupViewObjectId());
        assertEquals("row", result.entity().arrangement());
        assertEquals(3, result.entity().elementsRepositioned());
        assertFalse(result.entity().groupResized());
        assertNull(result.entity().newGroupWidth());
        assertNull(result.entity().newGroupHeight());
    }

    @Test
    public void layoutWithinGroup_shouldApplyColumnArrangement() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> groupResult = accessor.addGroupToView(
                "default", "view-001", "Test Group", 50, 50, 400, 400, null, null, null);
        String groupVoId = groupResult.entity().viewObjectId();

        accessor.addToView("default", "view-001", "ba-001", 0, 0, 120, 55, false, groupVoId, null, null);
        accessor.addToView("default", "view-001", "bp-001", 0, 0, 120, 55, false, groupVoId, null, null);

        MutationResult<LayoutWithinGroupResultDto> result =
                accessor.layoutWithinGroup("default", "view-001", groupVoId,
                        "column", null, null, null, null, false, false, null, false);

        assertNotNull(result);
        assertEquals("column", result.entity().arrangement());
        assertEquals(2, result.entity().elementsRepositioned());
    }

    @Test
    public void layoutWithinGroup_shouldApplyGridArrangement() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> groupResult = accessor.addGroupToView(
                "default", "view-001", "Test Group", 50, 50, 400, 400, null, null, null);
        String groupVoId = groupResult.entity().viewObjectId();

        accessor.addToView("default", "view-001", "ba-001", 0, 0, 120, 55, false, groupVoId, null, null);
        accessor.addToView("default", "view-001", "bp-001", 0, 0, 120, 55, false, groupVoId, null, null);
        accessor.addToView("default", "view-001", "ac-001", 0, 0, 120, 55, false, groupVoId, null, null);

        MutationResult<LayoutWithinGroupResultDto> result =
                accessor.layoutWithinGroup("default", "view-001", groupVoId,
                        "grid", null, null, null, null, false, false, null, false);

        assertNotNull(result);
        assertEquals("grid", result.entity().arrangement());
        assertEquals(3, result.entity().elementsRepositioned());
    }

    @Test
    public void layoutWithinGroup_shouldAutoResizeGroup() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> groupResult = accessor.addGroupToView(
                "default", "view-001", "Test Group", 50, 50, 400, 200, null, null, null);
        String groupVoId = groupResult.entity().viewObjectId();

        accessor.addToView("default", "view-001", "ba-001", 0, 0, 120, 55, false, groupVoId, null, null);
        accessor.addToView("default", "view-001", "bp-001", 0, 0, 120, 55, false, groupVoId, null, null);

        MutationResult<LayoutWithinGroupResultDto> result =
                accessor.layoutWithinGroup("default", "view-001", groupVoId,
                        "row", null, null, null, null, true, false, null, false);

        assertNotNull(result);
        assertTrue(result.entity().groupResized());
        assertNotNull(result.entity().newGroupWidth());
        assertNotNull(result.entity().newGroupHeight());
        assertTrue("Group width should be positive", result.entity().newGroupWidth() > 0);
        assertTrue("Group height should be positive", result.entity().newGroupHeight() > 0);
    }

    @Test(expected = ModelAccessException.class)
    public void layoutWithinGroup_shouldFailOnViewNotFound() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        accessor.layoutWithinGroup("default", "nonexistent-view", "some-group",
                "row", null, null, null, null, false, false, null, false);
    }

    @Test(expected = ModelAccessException.class)
    public void layoutWithinGroup_shouldFailOnGroupNotFound() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        accessor.layoutWithinGroup("default", "view-001", "nonexistent-group",
                "row", null, null, null, null, false, false, null, false);
    }

    @Test(expected = ModelAccessException.class)
    public void layoutWithinGroup_shouldFailOnInvalidArrangement() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> groupResult = accessor.addGroupToView(
                "default", "view-001", "Test Group", 50, 50, 400, 200, null, null, null);
        String groupVoId = groupResult.entity().viewObjectId();

        accessor.addToView("default", "view-001", "ba-001", 0, 0, 120, 55, false, groupVoId, null, null);

        accessor.layoutWithinGroup("default", "view-001", groupVoId,
                "circular", null, null, null, null, false, false, null, false);
    }

    @Test(expected = ModelAccessException.class)
    public void layoutWithinGroup_shouldFailOnEmptyGroup() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> groupResult = accessor.addGroupToView(
                "default", "view-001", "Empty Group", 50, 50, 400, 200, null, null, null);
        String groupVoId = groupResult.entity().viewObjectId();

        accessor.layoutWithinGroup("default", "view-001", groupVoId,
                "row", null, null, null, null, false, false, null, false);
    }

    @Test
    public void layoutWithinGroup_shouldRespectCustomSpacingAndPadding() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> groupResult = accessor.addGroupToView(
                "default", "view-001", "Test Group", 50, 50, 600, 200, null, null, null);
        String groupVoId = groupResult.entity().viewObjectId();

        accessor.addToView("default", "view-001", "ba-001", 0, 0, 120, 55, false, groupVoId, null, null);
        accessor.addToView("default", "view-001", "bp-001", 0, 0, 120, 55, false, groupVoId, null, null);

        MutationResult<LayoutWithinGroupResultDto> result =
                accessor.layoutWithinGroup("default", "view-001", groupVoId,
                        "row", 30, 20, null, null, false, false, null, false);

        assertNotNull(result);
        assertEquals(2, result.entity().elementsRepositioned());
    }

    @Test
    public void layoutWithinGroup_shouldRespectCustomElementDimensions() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> groupResult = accessor.addGroupToView(
                "default", "view-001", "Test Group", 50, 50, 600, 300, null, null, null);
        String groupVoId = groupResult.entity().viewObjectId();

        accessor.addToView("default", "view-001", "ba-001", 0, 0, 120, 55, false, groupVoId, null, null);
        accessor.addToView("default", "view-001", "bp-001", 0, 0, 120, 55, false, groupVoId, null, null);

        MutationResult<LayoutWithinGroupResultDto> result =
                accessor.layoutWithinGroup("default", "view-001", groupVoId,
                        "row", null, null, 150, 70, true, false, null, false);

        assertNotNull(result);
        assertEquals(2, result.entity().elementsRepositioned());
        assertTrue(result.entity().groupResized());
    }

    @Test(expected = ModelAccessException.class)
    public void layoutWithinGroup_shouldRejectNegativeElementWidth() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> groupResult = accessor.addGroupToView(
                "default", "view-001", "Test Group", 50, 50, 400, 200, null, null, null);
        String groupVoId = groupResult.entity().viewObjectId();

        accessor.addToView("default", "view-001", "ba-001", 0, 0, 120, 55, false, groupVoId, null, null);

        accessor.layoutWithinGroup("default", "view-001", groupVoId,
                "row", null, null, -10, null, false, false, null, false);
    }

    @Test(expected = ModelAccessException.class)
    public void layoutWithinGroup_shouldRejectNegativeElementHeight() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> groupResult = accessor.addGroupToView(
                "default", "view-001", "Test Group", 50, 50, 400, 200, null, null, null);
        String groupVoId = groupResult.entity().viewObjectId();

        accessor.addToView("default", "view-001", "ba-001", 0, 0, 120, 55, false, groupVoId, null, null);

        accessor.layoutWithinGroup("default", "view-001", groupVoId,
                "row", null, null, null, -10, false, false, null, false);
    }

    @Test
    public void layoutWithinGroup_shouldAcceptZeroSpacingAndPadding() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> groupResult = accessor.addGroupToView(
                "default", "view-001", "Test Group", 50, 50, 400, 200, null, null, null);
        String groupVoId = groupResult.entity().viewObjectId();

        accessor.addToView("default", "view-001", "ba-001", 0, 0, 120, 55, false, groupVoId, null, null);
        accessor.addToView("default", "view-001", "bp-001", 0, 0, 120, 55, false, groupVoId, null, null);

        MutationResult<LayoutWithinGroupResultDto> result =
                accessor.layoutWithinGroup("default", "view-001", groupVoId,
                        "row", 0, 0, null, null, false, false, null, false);

        assertNotNull(result);
        assertEquals(2, result.entity().elementsRepositioned());
    }

    @Test(expected = ModelAccessException.class)
    public void layoutWithinGroup_shouldRejectNegativeSpacing() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> groupResult = accessor.addGroupToView(
                "default", "view-001", "Test Group", 50, 50, 400, 200, null, null, null);
        String groupVoId = groupResult.entity().viewObjectId();

        accessor.addToView("default", "view-001", "ba-001", 0, 0, 120, 55, false, groupVoId, null, null);

        accessor.layoutWithinGroup("default", "view-001", groupVoId,
                "row", -5, null, null, null, false, false, null, false);
    }

    @Test(expected = ModelAccessException.class)
    public void layoutWithinGroup_shouldRejectNegativePadding() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> groupResult = accessor.addGroupToView(
                "default", "view-001", "Test Group", 50, 50, 400, 200, null, null, null);
        String groupVoId = groupResult.entity().viewObjectId();

        accessor.addToView("default", "view-001", "ba-001", 0, 0, 120, 55, false, groupVoId, null, null);

        accessor.layoutWithinGroup("default", "view-001", groupVoId,
                "row", null, -5, null, null, false, false, null, false);
    }

    // ---- autoWidth tests (Story 11-14) ----

    @Test
    public void layoutWithinGroup_shouldAutoWidthComputeDifferentWidths() {
        // AC #1: autoWidth computes different widths for short/long names
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> groupResult = accessor.addGroupToView(
                "default", "view-001", "Test Group", 50, 50, 600, 200, null, null, null);
        String groupVoId = groupResult.entity().viewObjectId();

        // "Customer" (8 chars) -> 8*7+30 = 86px
        accessor.addToView("default", "view-001", "ba-001", 0, 0, 120, 55, false, groupVoId, null, null);
        // "Order Processing" (16 chars) -> 16*7+30 = 142px
        accessor.addToView("default", "view-001", "bp-001", 0, 0, 120, 55, false, groupVoId, null, null);

        MutationResult<LayoutWithinGroupResultDto> result =
                accessor.layoutWithinGroup("default", "view-001", groupVoId,
                        "row", null, null, null, null, false, true, null, false);

        assertNotNull(result);
        assertTrue("Should report autoWidth used", result.entity().autoWidth());
        assertEquals(2, result.entity().elementsRepositioned());
    }

    @Test
    public void layoutWithinGroup_shouldAutoWidthWithColumnArrangement() {
        // AC #1: autoWidth with column arrangement produces variable widths
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> groupResult = accessor.addGroupToView(
                "default", "view-001", "Test Group", 50, 50, 600, 400, null, null, null);
        String groupVoId = groupResult.entity().viewObjectId();

        accessor.addToView("default", "view-001", "ba-001", 0, 0, 120, 55, false, groupVoId, null, null);
        accessor.addToView("default", "view-001", "bp-001", 0, 0, 120, 55, false, groupVoId, null, null);

        MutationResult<LayoutWithinGroupResultDto> result =
                accessor.layoutWithinGroup("default", "view-001", groupVoId,
                        "column", null, null, null, null, false, true, null, false);

        assertNotNull(result);
        assertTrue(result.entity().autoWidth());
        assertEquals(2, result.entity().elementsRepositioned());
    }

    @Test
    public void layoutWithinGroup_shouldAutoWidthWithElementWidthOverride() {
        // AC #2: elementWidth takes precedence over autoWidth
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> groupResult = accessor.addGroupToView(
                "default", "view-001", "Test Group", 50, 50, 600, 200, null, null, null);
        String groupVoId = groupResult.entity().viewObjectId();

        accessor.addToView("default", "view-001", "ba-001", 0, 0, 120, 55, false, groupVoId, null, null);
        accessor.addToView("default", "view-001", "bp-001", 0, 0, 120, 55, false, groupVoId, null, null);

        MutationResult<LayoutWithinGroupResultDto> result =
                accessor.layoutWithinGroup("default", "view-001", groupVoId,
                        "row", null, null, 150, null, false, true, null, false);

        assertNotNull(result);
        // autoWidth should be false in DTO because elementWidth overrides it
        assertFalse("elementWidth should override autoWidth", result.entity().autoWidth());
    }

    @Test
    public void layoutWithinGroup_shouldAutoWidthWithGridUniformWidth() {
        // AC #5: grid uses widest auto-width as uniform column width
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> groupResult = accessor.addGroupToView(
                "default", "view-001", "Test Group", 50, 50, 600, 400, null, null, null);
        String groupVoId = groupResult.entity().viewObjectId();

        // "Customer" (8 chars) and "Order Processing" (16 chars)
        accessor.addToView("default", "view-001", "ba-001", 0, 0, 120, 55, false, groupVoId, null, null);
        accessor.addToView("default", "view-001", "bp-001", 0, 0, 120, 55, false, groupVoId, null, null);
        accessor.addToView("default", "view-001", "ac-001", 0, 0, 120, 55, false, groupVoId, null, null);

        MutationResult<LayoutWithinGroupResultDto> result =
                accessor.layoutWithinGroup("default", "view-001", groupVoId,
                        "grid", null, null, null, null, false, true, null, false);

        assertNotNull(result);
        assertTrue(result.entity().autoWidth());
        assertEquals(3, result.entity().elementsRepositioned());
    }

    @Test
    public void layoutWithinGroup_shouldAutoWidthWithAutoResize() {
        // AC #1: autoWidth + autoResize → group resizes to fit auto-widths
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> groupResult = accessor.addGroupToView(
                "default", "view-001", "Test Group", 50, 50, 200, 100, null, null, null);
        String groupVoId = groupResult.entity().viewObjectId();

        accessor.addToView("default", "view-001", "ba-001", 0, 0, 120, 55, false, groupVoId, null, null);
        accessor.addToView("default", "view-001", "bp-001", 0, 0, 120, 55, false, groupVoId, null, null);

        MutationResult<LayoutWithinGroupResultDto> result =
                accessor.layoutWithinGroup("default", "view-001", groupVoId,
                        "row", null, null, null, null, true, true, null, false);

        assertNotNull(result);
        assertTrue(result.entity().autoWidth());
        assertTrue(result.entity().groupResized());
        assertNotNull(result.entity().newGroupWidth());
        assertTrue("Group width should accommodate auto-widths",
                result.entity().newGroupWidth() > 0);
    }

    @Test
    public void computeAutoWidth_shouldComputeCorrectWidthFromName() {
        // Direct test of the character-count heuristic: (charCount * 8) + 30
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> groupResult = accessor.addGroupToView(
                "default", "view-001", "Test Group", 50, 50, 600, 200, null, null, null);
        String groupVoId = groupResult.entity().viewObjectId();
        accessor.addToView("default", "view-001", "ba-001", 0, 0, 120, 55, false, groupVoId, null, null);

        IDiagramModelGroup group = (IDiagramModelGroup)
                com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, groupVoId);
        IDiagramModelObject child = group.getChildren().get(0);

        // "Customer" = 8 chars → (8 * 8) + 30 = 94px
        int width = accessor.computeAutoWidth(child);
        assertEquals("Customer (8 chars) should be (8*8)+30=94", 94, width);
    }

    @Test
    public void computeAutoWidth_shouldApplyMinimumWidthFloor() {
        // AC #4: very short names should floor to MIN_AUTO_WIDTH (60px)
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setName("Test");
        model.setId("model-floor");
        model.setDefaults();

        IBusinessActor actor = factory.createBusinessActor();
        actor.setId("short-001");
        actor.setName("AB"); // 2 chars → (2*7)+30 = 44px → should floor to 60px
        model.getFolder(FolderType.BUSINESS).getElements().add(actor);

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-floor");
        view.setName("Floor Test View");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> groupResult = accessor.addGroupToView(
                "default", "view-floor", "Test Group", 50, 50, 600, 200, null, null, null);
        String groupVoId = groupResult.entity().viewObjectId();
        accessor.addToView("default", "view-floor", "short-001", 0, 0, 120, 55, false, groupVoId, null, null);

        IDiagramModelGroup group = (IDiagramModelGroup)
                com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, groupVoId);
        IDiagramModelObject child = group.getChildren().get(0);

        // "AB" = 2 chars → (2*7)+30 = 44px → floored to 60px
        int width = accessor.computeAutoWidth(child);
        assertEquals("Short name should floor to MIN_AUTO_WIDTH",
                ArchiModelAccessorImpl.MIN_AUTO_WIDTH, width);
    }

    @Test
    public void computeAutoWidth_shouldReturnDefaultForNullName() {
        // AC #3: null/empty name → DEFAULT_ELEMENT_WIDTH (120px)
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setName("Test");
        model.setId("model-null");
        model.setDefaults();

        IBusinessActor actor = factory.createBusinessActor();
        actor.setId("null-001");
        actor.setName(null); // null name
        model.getFolder(FolderType.BUSINESS).getElements().add(actor);

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-null");
        view.setName("Null Test View");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> groupResult = accessor.addGroupToView(
                "default", "view-null", "Test Group", 50, 50, 600, 200, null, null, null);
        String groupVoId = groupResult.entity().viewObjectId();
        accessor.addToView("default", "view-null", "null-001", 0, 0, 120, 55, false, groupVoId, null, null);

        IDiagramModelGroup group = (IDiagramModelGroup)
                com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, groupVoId);
        IDiagramModelObject child = group.getChildren().get(0);

        int width = accessor.computeAutoWidth(child);
        assertEquals("Null name should return DEFAULT_ELEMENT_WIDTH",
                ArchiModelAccessorImpl.DEFAULT_ELEMENT_WIDTH, width);
    }

    @Test
    public void computeAutoWidth_shouldReturnDefaultForEmptyName() {
        // AC #3: empty name → DEFAULT_ELEMENT_WIDTH (120px)
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setName("Test");
        model.setId("model-empty");
        model.setDefaults();

        IBusinessActor actor = factory.createBusinessActor();
        actor.setId("empty-001");
        actor.setName(""); // empty name
        model.getFolder(FolderType.BUSINESS).getElements().add(actor);

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-empty");
        view.setName("Empty Test View");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> groupResult = accessor.addGroupToView(
                "default", "view-empty", "Test Group", 50, 50, 600, 200, null, null, null);
        String groupVoId = groupResult.entity().viewObjectId();
        accessor.addToView("default", "view-empty", "empty-001", 0, 0, 120, 55, false, groupVoId, null, null);

        IDiagramModelGroup group = (IDiagramModelGroup)
                com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, groupVoId);
        IDiagramModelObject child = group.getChildren().get(0);

        int width = accessor.computeAutoWidth(child);
        assertEquals("Empty name should return DEFAULT_ELEMENT_WIDTH",
                ArchiModelAccessorImpl.DEFAULT_ELEMENT_WIDTH, width);
    }

    // ---- Grid columns tests (Story 11-18) ----

    @Test
    public void layoutWithinGroup_shouldUseExplicitColumnCount() {
        // AC1: explicit columns=2 with 3 elements → 2 columns, 2 rows
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> groupResult = accessor.addGroupToView(
                "default", "view-001", "Grid Group", 50, 50, 600, 400, null, null, null);
        String groupVoId = groupResult.entity().viewObjectId();

        accessor.addToView("default", "view-001", "ba-001", 0, 0, 120, 55, false, groupVoId, null, null);
        accessor.addToView("default", "view-001", "bp-001", 0, 0, 120, 55, false, groupVoId, null, null);
        accessor.addToView("default", "view-001", "ac-001", 0, 0, 120, 55, false, groupVoId, null, null);

        MutationResult<LayoutWithinGroupResultDto> result =
                accessor.layoutWithinGroup("default", "view-001", groupVoId,
                        "grid", null, null, null, null, false, false, 2, false);

        assertNotNull(result);
        assertEquals(3, result.entity().elementsRepositioned());
        assertEquals(Integer.valueOf(2), result.entity().columnsUsed());
    }

    @Test
    public void layoutWithinGroup_shouldCapColumnsAtElementCount() {
        // AC1: columns=20 with 3 elements → capped to 3 (single row)
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> groupResult = accessor.addGroupToView(
                "default", "view-001", "Cap Group", 50, 50, 600, 400, null, null, null);
        String groupVoId = groupResult.entity().viewObjectId();

        accessor.addToView("default", "view-001", "ba-001", 0, 0, 120, 55, false, groupVoId, null, null);
        accessor.addToView("default", "view-001", "bp-001", 0, 0, 120, 55, false, groupVoId, null, null);
        accessor.addToView("default", "view-001", "ac-001", 0, 0, 120, 55, false, groupVoId, null, null);

        MutationResult<LayoutWithinGroupResultDto> result =
                accessor.layoutWithinGroup("default", "view-001", groupVoId,
                        "grid", null, null, null, null, false, false, 20, false);

        assertNotNull(result);
        assertEquals(3, result.entity().elementsRepositioned());
        assertEquals(Integer.valueOf(3), result.entity().columnsUsed());
    }

    @Test
    public void layoutWithinGroup_shouldAutoDetectColumnsWhenNull() {
        // AC1: columns=null → auto-detect from group width (no regression)
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> groupResult = accessor.addGroupToView(
                "default", "view-001", "Auto Group", 50, 50, 400, 200, null, null, null);
        String groupVoId = groupResult.entity().viewObjectId();

        accessor.addToView("default", "view-001", "ba-001", 0, 0, 120, 55, false, groupVoId, null, null);
        accessor.addToView("default", "view-001", "bp-001", 0, 0, 120, 55, false, groupVoId, null, null);
        accessor.addToView("default", "view-001", "ac-001", 0, 0, 120, 55, false, groupVoId, null, null);

        MutationResult<LayoutWithinGroupResultDto> result =
                accessor.layoutWithinGroup("default", "view-001", groupVoId,
                        "grid", null, null, null, null, false, false, null, false);

        assertNotNull(result);
        assertNotNull("columnsUsed should be reported for grid", result.entity().columnsUsed());
        assertTrue("columnsUsed should be positive", result.entity().columnsUsed() > 0);
    }

    @Test(expected = ModelAccessException.class)
    public void layoutWithinGroup_shouldRejectZeroColumns() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> groupResult = accessor.addGroupToView(
                "default", "view-001", "Zero Group", 50, 50, 400, 200, null, null, null);
        String groupVoId = groupResult.entity().viewObjectId();

        accessor.addToView("default", "view-001", "ba-001", 0, 0, 120, 55, false, groupVoId, null, null);

        accessor.layoutWithinGroup("default", "view-001", groupVoId,
                "grid", null, null, null, null, false, false, 0, false);
    }

    // ---- Recursive resize tests (Story 11-18) ----

    @Test
    public void layoutWithinGroup_shouldRecursivelyResizeParentGroup() {
        // AC2: recursive=true resizes ancestor groups
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Create outer group
        MutationResult<ViewGroupDto> outerResult = accessor.addGroupToView(
                "default", "view-001", "Outer Group", 50, 50, 200, 100, null, null, null);
        String outerVoId = outerResult.entity().viewObjectId();

        // Create inner group nested inside outer
        MutationResult<ViewGroupDto> innerResult = accessor.addGroupToView(
                "default", "view-001", "Inner Group", 10, 34, 150, 60, outerVoId, null, null);
        String innerVoId = innerResult.entity().viewObjectId();

        // Add elements to inner group
        accessor.addToView("default", "view-001", "ba-001", 0, 0, 120, 55, false, innerVoId, null, null);
        accessor.addToView("default", "view-001", "bp-001", 0, 0, 120, 55, false, innerVoId, null, null);

        MutationResult<LayoutWithinGroupResultDto> result =
                accessor.layoutWithinGroup("default", "view-001", innerVoId,
                        "row", null, null, null, null, true, false, null, true);

        assertNotNull(result);
        assertTrue("Group should be resized", result.entity().groupResized());
        assertEquals("One ancestor (outer) should be resized", 1, result.entity().ancestorsResized());
    }

    @Test
    public void layoutWithinGroup_shouldNotResizeAncestorsWhenRecursiveFalse() {
        // AC2: recursive=false → only target group resized
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> outerResult = accessor.addGroupToView(
                "default", "view-001", "Outer Group", 50, 50, 200, 100, null, null, null);
        String outerVoId = outerResult.entity().viewObjectId();

        MutationResult<ViewGroupDto> innerResult = accessor.addGroupToView(
                "default", "view-001", "Inner Group", 10, 34, 150, 60, outerVoId, null, null);
        String innerVoId = innerResult.entity().viewObjectId();

        accessor.addToView("default", "view-001", "ba-001", 0, 0, 120, 55, false, innerVoId, null, null);
        accessor.addToView("default", "view-001", "bp-001", 0, 0, 120, 55, false, innerVoId, null, null);

        MutationResult<LayoutWithinGroupResultDto> result =
                accessor.layoutWithinGroup("default", "view-001", innerVoId,
                        "row", null, null, null, null, true, false, null, false);

        assertNotNull(result);
        assertTrue("Group should be resized", result.entity().groupResized());
        assertEquals("No ancestors should be resized", 0, result.entity().ancestorsResized());
    }

    @Test
    public void layoutWithinGroup_shouldHandleTopLevelGroupRecursive() {
        // AC2: top-level group with recursive=true → no ancestors to resize
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> groupResult = accessor.addGroupToView(
                "default", "view-001", "Top Group", 50, 50, 200, 100, null, null, null);
        String groupVoId = groupResult.entity().viewObjectId();

        accessor.addToView("default", "view-001", "ba-001", 0, 0, 120, 55, false, groupVoId, null, null);
        accessor.addToView("default", "view-001", "bp-001", 0, 0, 120, 55, false, groupVoId, null, null);

        MutationResult<LayoutWithinGroupResultDto> result =
                accessor.layoutWithinGroup("default", "view-001", groupVoId,
                        "row", null, null, null, null, true, false, null, true);

        assertNotNull(result);
        assertTrue("Group should be resized", result.entity().groupResized());
        assertEquals("No ancestors for top-level group", 0, result.entity().ancestorsResized());
    }

    // ---- arrange-groups tests (Story 11-20) ----

    private IArchimateModel createTestModelWithGroups(int groupCount, int groupWidth, int groupHeight) {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setName("Arrange Groups Test");
        model.setId("model-ag");
        model.setDefaults();

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-ag");
        view.setName("Groups View");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        for (int i = 0; i < groupCount; i++) {
            IDiagramModelGroup group = factory.createDiagramModelGroup();
            group.setName("Group " + (i + 1));
            group.setBounds(0, 0, groupWidth, groupHeight);
            view.getChildren().add(group);
        }
        return model;
    }

    @Test
    public void arrangeGroups_gridArrangement_shouldPositionInGrid() {
        IArchimateModel model = createTestModelWithGroups(6, 200, 150);
        stubModelManager.setModels(List.of(model));
        ArchiModelAccessorImpl acc = createAccessorWithTestDispatcher(model);

        MutationResult<net.vheerden.archi.mcp.response.dto.ArrangeGroupsResultDto> result =
                acc.arrangeGroups("default", "view-ag", "grid", 3, 40, null, null);

        assertNotNull(result);
        assertEquals(6, result.entity().groupsPositioned());
        assertEquals("grid", result.entity().arrangement());
        assertEquals(Integer.valueOf(3), result.entity().columnsUsed());

        // Verify no overlaps: each group should have distinct position
        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS).getElements().get(0);
        List<IDiagramModelObject> children = new ArrayList<>(view.getChildren());
        // Row 0: positions 0,1,2 should be at y=20
        assertEquals(20, children.get(0).getBounds().getY());
        assertEquals(20, children.get(1).getBounds().getY());
        assertEquals(20, children.get(2).getBounds().getY());
        // Row 1: positions 3,4,5 should be at y=20+150+40=210
        assertEquals(210, children.get(3).getBounds().getY());
        assertEquals(210, children.get(4).getBounds().getY());
        assertEquals(210, children.get(5).getBounds().getY());
        // X positions: 20, 20+200+40=260, 260+200+40=500
        assertEquals(20, children.get(0).getBounds().getX());
        assertEquals(260, children.get(1).getBounds().getX());
        assertEquals(500, children.get(2).getBounds().getX());
    }

    @Test
    public void arrangeGroups_rowArrangement_shouldPositionHorizontally() {
        IArchimateModel model = createTestModelWithGroups(4, 200, 150);
        stubModelManager.setModels(List.of(model));
        ArchiModelAccessorImpl acc = createAccessorWithTestDispatcher(model);

        MutationResult<net.vheerden.archi.mcp.response.dto.ArrangeGroupsResultDto> result =
                acc.arrangeGroups("default", "view-ag", "row", null, 40, null, null);

        assertNotNull(result);
        assertEquals(4, result.entity().groupsPositioned());
        assertEquals("row", result.entity().arrangement());
        assertNull(result.entity().columnsUsed());

        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS).getElements().get(0);
        List<IDiagramModelObject> children = new ArrayList<>(view.getChildren());
        // All on same row (y=20)
        for (IDiagramModelObject child : children) {
            assertEquals(20, child.getBounds().getY());
        }
        // X: 20, 260, 500, 740
        assertEquals(20, children.get(0).getBounds().getX());
        assertEquals(260, children.get(1).getBounds().getX());
        assertEquals(500, children.get(2).getBounds().getX());
        assertEquals(740, children.get(3).getBounds().getX());
    }

    @Test
    public void arrangeGroups_columnArrangement_shouldPositionVertically() {
        IArchimateModel model = createTestModelWithGroups(4, 200, 150);
        stubModelManager.setModels(List.of(model));
        ArchiModelAccessorImpl acc = createAccessorWithTestDispatcher(model);

        MutationResult<net.vheerden.archi.mcp.response.dto.ArrangeGroupsResultDto> result =
                acc.arrangeGroups("default", "view-ag", "column", null, 40, null, null);

        assertNotNull(result);
        assertEquals(4, result.entity().groupsPositioned());
        assertEquals("column", result.entity().arrangement());

        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS).getElements().get(0);
        List<IDiagramModelObject> children = new ArrayList<>(view.getChildren());
        // All on same column (x=20)
        for (IDiagramModelObject child : children) {
            assertEquals(20, child.getBounds().getX());
        }
        // Y: 20, 210, 400, 590
        assertEquals(20, children.get(0).getBounds().getY());
        assertEquals(210, children.get(1).getBounds().getY());
        assertEquals(400, children.get(2).getBounds().getY());
        assertEquals(590, children.get(3).getBounds().getY());
    }

    @Test
    public void arrangeGroups_selectiveGroupIds_shouldOnlyArrangeSpecified() {
        IArchimateModel model = createTestModelWithGroups(4, 200, 150);
        stubModelManager.setModels(List.of(model));
        ArchiModelAccessorImpl acc = createAccessorWithTestDispatcher(model);

        // Set initial positions for groups 2 and 3 (indices 2,3) to something non-zero
        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS).getElements().get(0);
        List<IDiagramModelObject> children = new ArrayList<>(view.getChildren());
        children.get(2).setBounds(500, 500, 200, 150);
        children.get(3).setBounds(700, 700, 200, 150);

        // Arrange only first two groups
        List<String> groupIds = List.of(children.get(0).getId(), children.get(1).getId());
        MutationResult<net.vheerden.archi.mcp.response.dto.ArrangeGroupsResultDto> result =
                acc.arrangeGroups("default", "view-ag", "row", null, 40, groupIds, null);

        assertEquals(2, result.entity().groupsPositioned());

        // Groups 2 and 3 should remain at their original positions
        assertEquals(500, children.get(2).getBounds().getX());
        assertEquals(500, children.get(2).getBounds().getY());
        assertEquals(700, children.get(3).getBounds().getX());
        assertEquals(700, children.get(3).getBounds().getY());
    }

    @Test
    public void arrangeGroups_invalidArrangement_shouldThrow() {
        IArchimateModel model = createTestModelWithGroups(2, 200, 150);
        stubModelManager.setModels(List.of(model));
        ArchiModelAccessorImpl acc = createAccessorWithTestDispatcher(model);

        try {
            acc.arrangeGroups("default", "view-ag", "diagonal", null, null, null, null);
            fail("Should throw ModelAccessException for invalid arrangement");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
    }

    @Test
    public void arrangeGroups_negativeSpacing_shouldThrow() {
        IArchimateModel model = createTestModelWithGroups(2, 200, 150);
        stubModelManager.setModels(List.of(model));
        ArchiModelAccessorImpl acc = createAccessorWithTestDispatcher(model);

        try {
            acc.arrangeGroups("default", "view-ag", "row", null, -10, null, null);
            fail("Should throw ModelAccessException for negative spacing");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
    }

    @Test
    public void arrangeGroups_viewNotFound_shouldThrow() {
        IArchimateModel model = createTestModelWithGroups(2, 200, 150);
        stubModelManager.setModels(List.of(model));
        ArchiModelAccessorImpl acc = createAccessorWithTestDispatcher(model);

        try {
            acc.arrangeGroups("default", "nonexistent-view", "row", null, null, null, null);
            fail("Should throw ModelAccessException for missing view");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.VIEW_NOT_FOUND, e.getErrorCode());
        }
    }

    @Test
    public void arrangeGroups_columnsLessThanOne_shouldThrow() {
        IArchimateModel model = createTestModelWithGroups(4, 200, 150);
        stubModelManager.setModels(List.of(model));
        ArchiModelAccessorImpl acc = createAccessorWithTestDispatcher(model);

        try {
            acc.arrangeGroups("default", "view-ag", "grid", 0, null, null, null);
            fail("Should throw ModelAccessException for columns < 1");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue(e.getMessage().contains("columns"));
        }
    }

    @Test
    public void arrangeGroups_noGroupsInView_shouldThrow() {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setName("No Groups Test");
        model.setId("model-ng");
        model.setDefaults();

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-ng");
        view.setName("Empty View");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        stubModelManager.setModels(List.of(model));
        ArchiModelAccessorImpl acc = createAccessorWithTestDispatcher(model);

        try {
            acc.arrangeGroups("default", "view-ng", "row", null, null, null, null);
            fail("Should throw ModelAccessException for no groups");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue(e.getMessage().contains("No top-level groups"));
        }
    }

    @Test
    public void arrangeGroups_groupIdNotFound_shouldThrow() {
        IArchimateModel model = createTestModelWithGroups(2, 200, 150);
        stubModelManager.setModels(List.of(model));
        ArchiModelAccessorImpl acc = createAccessorWithTestDispatcher(model);

        try {
            acc.arrangeGroups("default", "view-ag", "row", null, null,
                    List.of("nonexistent-group-id"), null);
            fail("Should throw ModelAccessException for missing group ID");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.VIEW_OBJECT_NOT_FOUND, e.getErrorCode());
        }
    }

    @Test
    public void arrangeGroups_nestedGroupId_shouldThrow() {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setName("Nested Group Test");
        model.setId("model-nest");
        model.setDefaults();

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-nest");
        view.setName("Nested Groups View");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        IDiagramModelGroup parentGroup = factory.createDiagramModelGroup();
        parentGroup.setName("Parent");
        parentGroup.setBounds(0, 0, 400, 300);
        view.getChildren().add(parentGroup);

        IDiagramModelGroup nestedGroup = factory.createDiagramModelGroup();
        nestedGroup.setName("Nested");
        nestedGroup.setBounds(10, 30, 200, 150);
        parentGroup.getChildren().add(nestedGroup);

        stubModelManager.setModels(List.of(model));
        ArchiModelAccessorImpl acc = createAccessorWithTestDispatcher(model);

        try {
            acc.arrangeGroups("default", "view-nest", "row", null, null,
                    List.of(nestedGroup.getId()), null);
            fail("Should throw ModelAccessException for nested group");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertTrue(e.getMessage().contains("not a top-level group"));
        }
    }

    @Test
    public void arrangeGroups_varyingSizes_shouldMaintainSpacing() {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setName("Vary Size Test");
        model.setId("model-vs");
        model.setDefaults();

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-vs");
        view.setName("Varying Sizes");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        // Groups of different widths
        IDiagramModelGroup g1 = factory.createDiagramModelGroup();
        g1.setName("Small"); g1.setBounds(0, 0, 100, 80);
        IDiagramModelGroup g2 = factory.createDiagramModelGroup();
        g2.setName("Medium"); g2.setBounds(0, 0, 200, 120);
        IDiagramModelGroup g3 = factory.createDiagramModelGroup();
        g3.setName("Large"); g3.setBounds(0, 0, 300, 160);
        view.getChildren().add(g1);
        view.getChildren().add(g2);
        view.getChildren().add(g3);

        stubModelManager.setModels(List.of(model));
        ArchiModelAccessorImpl acc = createAccessorWithTestDispatcher(model);

        acc.arrangeGroups("default", "view-vs", "row", null, 50, null, null);

        // Verify: x positions based on each group's actual width + spacing
        assertEquals(20, g1.getBounds().getX()); // origin
        assertEquals(170, g2.getBounds().getX()); // 20 + 100 + 50
        assertEquals(420, g3.getBounds().getX()); // 170 + 200 + 50

        // Widths preserved
        assertEquals(100, g1.getBounds().getWidth());
        assertEquals(200, g2.getBounds().getWidth());
        assertEquals(300, g3.getBounds().getWidth());
    }

    // ---- arrange-groups direction tests (Story B18) ----

    @Test
    public void arrangeGroups_topologyHorizontal_shouldPositionInRow() {
        // Create model with 3 groups and inter-group connections for topology ordering
        IArchimateModel model = createTestModelWithGroups(3, 200, 150);
        stubModelManager.setModels(List.of(model));
        ArchiModelAccessorImpl acc = createAccessorWithTestDispatcher(model);

        MutationResult<net.vheerden.archi.mcp.response.dto.ArrangeGroupsResultDto> result =
                acc.arrangeGroups("default", "view-ag", "topology", null, 40, null, "horizontal");

        assertNotNull(result);
        assertEquals(3, result.entity().groupsPositioned());
        assertEquals("topology", result.entity().arrangement());

        // Verify horizontal layout: all groups on same row (y=20), different x positions
        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS).getElements().get(0);
        List<IDiagramModelObject> children = new ArrayList<>(view.getChildren());
        for (IDiagramModelObject child : children) {
            assertEquals(20, child.getBounds().getY());
        }
        // X positions should be sequential: 20, 260, 500
        assertEquals(20, children.get(0).getBounds().getX());
        assertEquals(260, children.get(1).getBounds().getX());
        assertEquals(500, children.get(2).getBounds().getX());
    }

    @Test
    public void arrangeGroups_topologyVertical_shouldPositionInColumn() {
        IArchimateModel model = createTestModelWithGroups(3, 200, 150);
        stubModelManager.setModels(List.of(model));
        ArchiModelAccessorImpl acc = createAccessorWithTestDispatcher(model);

        MutationResult<net.vheerden.archi.mcp.response.dto.ArrangeGroupsResultDto> result =
                acc.arrangeGroups("default", "view-ag", "topology", null, 40, null, "vertical");

        assertNotNull(result);
        assertEquals("topology", result.entity().arrangement());

        // Verify vertical layout: all groups on same column (x=20), different y positions
        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS).getElements().get(0);
        List<IDiagramModelObject> children = new ArrayList<>(view.getChildren());
        for (IDiagramModelObject child : children) {
            assertEquals(20, child.getBounds().getX());
        }
        assertEquals(20, children.get(0).getBounds().getY());
        assertEquals(210, children.get(1).getBounds().getY());
        assertEquals(400, children.get(2).getBounds().getY());
    }

    @Test
    public void arrangeGroups_topologyHorizontalWithColumns_shouldIgnoreDirection() {
        IArchimateModel model = createTestModelWithGroups(4, 200, 150);
        stubModelManager.setModels(List.of(model));
        ArchiModelAccessorImpl acc = createAccessorWithTestDispatcher(model);

        MutationResult<net.vheerden.archi.mcp.response.dto.ArrangeGroupsResultDto> result =
                acc.arrangeGroups("default", "view-ag", "topology", 2, 40, null, "horizontal");

        assertNotNull(result);
        assertEquals("topology", result.entity().arrangement());
        assertEquals(Integer.valueOf(2), result.entity().columnsUsed());

        // Should use grid (2 columns) regardless of direction=horizontal
        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS).getElements().get(0);
        List<IDiagramModelObject> children = new ArrayList<>(view.getChildren());
        // Row 0: y=20 for first two
        assertEquals(20, children.get(0).getBounds().getY());
        assertEquals(20, children.get(1).getBounds().getY());
        // Row 1: y=210 for next two
        assertEquals(210, children.get(2).getBounds().getY());
        assertEquals(210, children.get(3).getBounds().getY());
    }

    @Test
    public void arrangeGroups_nonTopologyWithDirection_shouldIgnoreDirection() {
        IArchimateModel model = createTestModelWithGroups(3, 200, 150);
        stubModelManager.setModels(List.of(model));
        ArchiModelAccessorImpl acc = createAccessorWithTestDispatcher(model);

        // Use "column" arrangement with direction="horizontal" — direction should be ignored
        MutationResult<net.vheerden.archi.mcp.response.dto.ArrangeGroupsResultDto> result =
                acc.arrangeGroups("default", "view-ag", "column", null, 40, null, "horizontal");

        assertNotNull(result);
        assertEquals("column", result.entity().arrangement());

        // Should still be column layout (vertical), direction ignored
        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS).getElements().get(0);
        List<IDiagramModelObject> children = new ArrayList<>(view.getChildren());
        for (IDiagramModelObject child : children) {
            assertEquals(20, child.getBounds().getX());
        }
        assertEquals(20, children.get(0).getBounds().getY());
        assertEquals(210, children.get(1).getBounds().getY());
        assertEquals(400, children.get(2).getBounds().getY());
    }

    @Test
    public void arrangeGroups_topologyDefaultDirection_shouldPositionInColumn() {
        // Topology with null direction should default to column (vertical)
        IArchimateModel model = createTestModelWithGroups(3, 200, 150);
        stubModelManager.setModels(List.of(model));
        ArchiModelAccessorImpl acc = createAccessorWithTestDispatcher(model);

        MutationResult<net.vheerden.archi.mcp.response.dto.ArrangeGroupsResultDto> result =
                acc.arrangeGroups("default", "view-ag", "topology", null, 40, null, null);

        assertNotNull(result);
        assertEquals("topology", result.entity().arrangement());

        // Default direction = vertical = column layout
        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS).getElements().get(0);
        List<IDiagramModelObject> children = new ArrayList<>(view.getChildren());
        for (IDiagramModelObject child : children) {
            assertEquals(20, child.getBounds().getX());
        }
    }

    @Test
    public void arrangeGroups_topologyHorizontalWithConnections_shouldPositionInRow() {
        // Create model with 3 groups and inter-group connections for non-trivial topology ordering
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setName("Topology Direction Test");
        model.setId("model-td");
        model.setDefaults();

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-ag");
        view.setName("Topology Direction View");

        // Group A with one element
        IDiagramModelGroup groupA = factory.createDiagramModelGroup();
        groupA.setId("groupA"); groupA.setName("Group A");
        groupA.setBounds(0, 0, 200, 150);
        IArchimateElement elemA1 = factory.createBusinessActor();
        elemA1.setId("eA1"); elemA1.setName("A1");
        model.getFolder(FolderType.BUSINESS).getElements().add(elemA1);
        IDiagramModelArchimateObject voA1 = factory.createDiagramModelArchimateObject();
        voA1.setArchimateElement(elemA1);
        voA1.setBounds(10, 34, 120, 55);
        groupA.getChildren().add(voA1);

        // Group B with one element
        IDiagramModelGroup groupB = factory.createDiagramModelGroup();
        groupB.setId("groupB"); groupB.setName("Group B");
        groupB.setBounds(0, 0, 200, 150);
        IArchimateElement elemB1 = factory.createBusinessProcess();
        elemB1.setId("eB1"); elemB1.setName("B1");
        model.getFolder(FolderType.BUSINESS).getElements().add(elemB1);
        IDiagramModelArchimateObject voB1 = factory.createDiagramModelArchimateObject();
        voB1.setArchimateElement(elemB1);
        voB1.setBounds(10, 34, 120, 55);
        groupB.getChildren().add(voB1);

        // Group C with one element
        IDiagramModelGroup groupC = factory.createDiagramModelGroup();
        groupC.setId("groupC"); groupC.setName("Group C");
        groupC.setBounds(0, 0, 200, 150);
        IArchimateElement elemC1 = factory.createApplicationComponent();
        elemC1.setId("eC1"); elemC1.setName("C1");
        model.getFolder(FolderType.APPLICATION).getElements().add(elemC1);
        IDiagramModelArchimateObject voC1 = factory.createDiagramModelArchimateObject();
        voC1.setArchimateElement(elemC1);
        voC1.setBounds(10, 34, 120, 55);
        groupC.getChildren().add(voC1);

        view.getChildren().add(groupA);
        view.getChildren().add(groupB);
        view.getChildren().add(groupC);

        // Inter-group connections: A→B and B→C (chain topology)
        IArchimateRelationship rel1 = factory.createServingRelationship();
        rel1.setId("rel-ab"); rel1.setSource(elemA1); rel1.setTarget(elemB1);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel1);
        var conn1 = factory.createDiagramModelArchimateConnection();
        conn1.setArchimateRelationship(rel1);
        conn1.connect(voA1, voB1);

        IArchimateRelationship rel2 = factory.createServingRelationship();
        rel2.setId("rel-bc"); rel2.setSource(elemB1); rel2.setTarget(elemC1);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel2);
        var conn2 = factory.createDiagramModelArchimateConnection();
        conn2.setArchimateRelationship(rel2);
        conn2.connect(voB1, voC1);

        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        stubModelManager.setModels(List.of(model));
        ArchiModelAccessorImpl acc = createAccessorWithTestDispatcher(model);

        MutationResult<net.vheerden.archi.mcp.response.dto.ArrangeGroupsResultDto> result =
                acc.arrangeGroups("default", "view-ag", "topology", null, 40, null, "horizontal");

        assertNotNull(result);
        assertEquals(3, result.entity().groupsPositioned());
        assertEquals("topology", result.entity().arrangement());

        // Verify horizontal layout: all groups on same row (y=20), sequential x positions
        List<IDiagramModelObject> children = new ArrayList<>(view.getChildren());
        for (IDiagramModelObject child : children) {
            assertEquals("All groups should be on same row", 20, child.getBounds().getY());
        }
        // X positions should be sequential (exact values depend on topology ordering,
        // but each group should be at a distinct x > previous)
        int prevX = -1;
        for (IDiagramModelObject child : children) {
            assertTrue("Groups should be positioned left-to-right",
                    child.getBounds().getX() > prevX);
            prevX = child.getBounds().getX();
        }
    }

    @Test
    public void arrangeGroups_topologyInvalidDirection_shouldDefaultToColumn() {
        // Invalid direction value should silently default to vertical (column) layout
        IArchimateModel model = createTestModelWithGroups(3, 200, 150);
        stubModelManager.setModels(List.of(model));
        ArchiModelAccessorImpl acc = createAccessorWithTestDispatcher(model);

        MutationResult<net.vheerden.archi.mcp.response.dto.ArrangeGroupsResultDto> result =
                acc.arrangeGroups("default", "view-ag", "topology", null, 40, null, "diagonal");

        assertNotNull(result);
        assertEquals("topology", result.entity().arrangement());

        // Should default to column (vertical) layout — direction silently ignored
        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS).getElements().get(0);
        List<IDiagramModelObject> children = new ArrayList<>(view.getChildren());
        for (IDiagramModelObject child : children) {
            assertEquals("Invalid direction should default to vertical (same x)",
                    20, child.getBounds().getX());
        }
        // Y positions should be sequential (column layout)
        assertEquals(20, children.get(0).getBounds().getY());
        assertEquals(210, children.get(1).getBounds().getY());
        assertEquals(400, children.get(2).getBounds().getY());
    }

    // ---- Test model builders ----

    /**
     * Creates a test model with multiple elements for auto-placement wrapping test.
     */
    private IArchimateModel createTestModelForAutoPlacement() {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setName("Auto-Placement Test");
        model.setId("model-ap");
        model.setDefaults();

        // Create 6 elements
        for (int i = 1; i <= 6; i++) {
            IArchimateElement elem = factory.createBusinessActor();
            elem.setId("elem-ap-" + i);
            elem.setName("Element " + i);
            model.getFolder(FolderType.BUSINESS).getElements().add(elem);
        }

        // View
        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-ap");
        view.setName("AP View");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        // Place 5 elements manually at x=50, 200, 350, 500, 650
        int[] xPositions = {50, 200, 350, 500, 650};
        for (int i = 0; i < 5; i++) {
            IDiagramModelArchimateObject vo = factory.createDiagramModelArchimateObject();
            vo.setArchimateElement((IArchimateElement)
                    com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(
                            model, "elem-ap-" + (i + 1)));
            vo.setBounds(xPositions[i], 50, 120, 55);
            view.getChildren().add(vo);
        }

        return model;
    }

    /**
     * Creates a test model with elements, a relationship, and a view (no view objects).
     * Uses IArchimateFactory.eINSTANCE for proper EMF containment.
     */
    private IArchimateModel createTestModel() {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;

        IArchimateModel model = factory.createArchimateModel();
        model.setName("Test Architecture");
        model.setId("model-001");
        model.setDefaults();

        // Business elements
        IBusinessActor actor = factory.createBusinessActor();
        actor.setId("ba-001");
        actor.setName("Customer");
        actor.setDocumentation("The primary customer actor");
        IProperty prop = factory.createProperty("owner", "team-alpha");
        actor.getProperties().add(prop);
        model.getFolder(FolderType.BUSINESS).getElements().add(actor);

        IBusinessProcess process = factory.createBusinessProcess();
        process.setId("bp-001");
        process.setName("Order Processing");
        model.getFolder(FolderType.BUSINESS).getElements().add(process);

        // Application element
        IApplicationComponent appComp = factory.createApplicationComponent();
        appComp.setId("ac-001");
        appComp.setName("Order System");
        model.getFolder(FolderType.APPLICATION).getElements().add(appComp);

        // Relationship
        IArchimateRelationship serving = factory.createServingRelationship();
        serving.setId("rel-001");
        serving.setName("serves");
        serving.connect(appComp, process);
        model.getFolder(FolderType.RELATIONS).getElements().add(serving);

        // View (diagram without visual objects — for getViews tests)
        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-001");
        view.setName("Main View");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        return model;
    }

    /**
     * Creates a test model with a view containing visual objects and connections.
     * Used for getViewContents tests.
     */
    private IArchimateModel createTestModelWithViewContents() {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;

        IArchimateModel model = factory.createArchimateModel();
        model.setName("View Test Model");
        model.setId("model-002");
        model.setDefaults();

        // Elements
        IBusinessActor actor = factory.createBusinessActor();
        actor.setId("ba-100");
        actor.setName("User");
        model.getFolder(FolderType.BUSINESS).getElements().add(actor);

        IApplicationComponent comp = factory.createApplicationComponent();
        comp.setId("ac-100");
        comp.setName("Web App");
        model.getFolder(FolderType.APPLICATION).getElements().add(comp);

        // Relationship
        IArchimateRelationship serving = factory.createServingRelationship();
        serving.setId("rel-100");
        serving.setName("serves");
        serving.connect(comp, actor);
        model.getFolder(FolderType.RELATIONS).getElements().add(serving);

        // View with visual objects
        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-001");
        view.setName("Main View");

        // Visual object for actor
        IDiagramModelArchimateObject actorVisual = factory.createDiagramModelArchimateObject();
        actorVisual.setArchimateElement(actor);
        actorVisual.setBounds(100, 200, 120, 55);
        view.getChildren().add(actorVisual);

        // Visual object for component
        IDiagramModelArchimateObject compVisual = factory.createDiagramModelArchimateObject();
        compVisual.setArchimateElement(comp);
        compVisual.setBounds(300, 200, 120, 55);
        view.getChildren().add(compVisual);

        // Visual connection
        var connection = factory.createDiagramModelArchimateConnection();
        connection.setArchimateRelationship(serving);
        connection.connect(compVisual, actorVisual);
        view.getChildren(); // ensure containment is set

        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        return model;
    }

    /**
     * Creates a model with default folders but no elements, views, or relationships.
     */
    private IArchimateModel createEmptyModel() {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;

        IArchimateModel model = factory.createArchimateModel();
        model.setName("Empty Model");
        model.setId("model-empty");
        model.setDefaults();

        return model;
    }

    /**
     * Creates a test model with subfolders for folder navigation tests.
     * Business folder gets a "Core Processes" subfolder with a nested "Internal" subfolder.
     */
    private IArchimateModel createTestModelWithSubfolders() {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;

        IArchimateModel model = factory.createArchimateModel();
        model.setName("Subfolder Test Model");
        model.setId("model-subfolder");
        model.setDefaults();

        // Add element to Business folder
        IBusinessActor actor = factory.createBusinessActor();
        actor.setId("ba-sub-001");
        actor.setName("Customer");
        model.getFolder(FolderType.BUSINESS).getElements().add(actor);

        // Add subfolder "Core Processes" under Business
        IFolder coreProcesses = factory.createFolder();
        coreProcesses.setId("subfolder-001");
        coreProcesses.setName("Core Processes");
        coreProcesses.setType(FolderType.USER);
        model.getFolder(FolderType.BUSINESS).getFolders().add(coreProcesses);

        // Add element to subfolder
        IBusinessProcess process = factory.createBusinessProcess();
        process.setId("bp-sub-001");
        process.setName("Order Processing");
        coreProcesses.getElements().add(process);

        // Add nested subfolder "Internal" under "Core Processes"
        IFolder internal = factory.createFolder();
        internal.setId("subfolder-002");
        internal.setName("Internal");
        internal.setType(FolderType.USER);
        coreProcesses.getFolders().add(internal);

        return model;
    }

    // ---- Folder-layer validation tests (Story 10-13) ----

    @Test
    public void shouldReturnStrategyFolderType_forCapability() {
        stubModelManager.setModels(Collections.emptyList());
        accessor = new ArchiModelAccessorImpl(stubModelManager);
        assertEquals(FolderType.STRATEGY,
                accessor.getExpectedFolderType(IArchimateFactory.eINSTANCE.createCapability()));
    }

    @Test
    public void shouldReturnStrategyFolderType_forValueStream() {
        stubModelManager.setModels(Collections.emptyList());
        accessor = new ArchiModelAccessorImpl(stubModelManager);
        assertEquals(FolderType.STRATEGY,
                accessor.getExpectedFolderType(IArchimateFactory.eINSTANCE.createValueStream()));
    }

    @Test
    public void shouldReturnBusinessFolderType_forBusinessActor() {
        stubModelManager.setModels(Collections.emptyList());
        accessor = new ArchiModelAccessorImpl(stubModelManager);
        assertEquals(FolderType.BUSINESS,
                accessor.getExpectedFolderType(IArchimateFactory.eINSTANCE.createBusinessActor()));
    }

    @Test
    public void shouldReturnApplicationFolderType_forApplicationComponent() {
        stubModelManager.setModels(Collections.emptyList());
        accessor = new ArchiModelAccessorImpl(stubModelManager);
        assertEquals(FolderType.APPLICATION,
                accessor.getExpectedFolderType(IArchimateFactory.eINSTANCE.createApplicationComponent()));
    }

    @Test
    public void shouldReturnTechnologyFolderType_forNode() {
        stubModelManager.setModels(Collections.emptyList());
        accessor = new ArchiModelAccessorImpl(stubModelManager);
        assertEquals(FolderType.TECHNOLOGY,
                accessor.getExpectedFolderType(IArchimateFactory.eINSTANCE.createNode()));
    }

    @Test
    public void shouldReturnMotivationFolderType_forGoal() {
        stubModelManager.setModels(Collections.emptyList());
        accessor = new ArchiModelAccessorImpl(stubModelManager);
        assertEquals(FolderType.MOTIVATION,
                accessor.getExpectedFolderType(IArchimateFactory.eINSTANCE.createGoal()));
    }

    @Test
    public void shouldReturnImplMigrationFolderType_forWorkPackage() {
        stubModelManager.setModels(Collections.emptyList());
        accessor = new ArchiModelAccessorImpl(stubModelManager);
        assertEquals(FolderType.IMPLEMENTATION_MIGRATION,
                accessor.getExpectedFolderType(IArchimateFactory.eINSTANCE.createWorkPackage()));
    }

    @Test
    public void shouldReturnRootFolder_whenAlreadyRoot() {
        IArchimateModel model = createTestModelWithDefaultFolders();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        IFolder businessRoot = model.getFolder(FolderType.BUSINESS);
        assertSame(businessRoot, accessor.getRootFolder(businessRoot));
    }

    @Test
    public void shouldReturnRootFolder_forSubfolder() {
        IArchimateModel model = createTestModelWithDefaultFolders();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        IFolder businessRoot = model.getFolder(FolderType.BUSINESS);
        IFolder sub = IArchimateFactory.eINSTANCE.createFolder();
        sub.setName("Sub");
        sub.setType(FolderType.USER);
        businessRoot.getFolders().add(sub);

        assertSame(businessRoot, accessor.getRootFolder(sub));
    }

    @Test
    public void shouldReturnRootFolder_forNestedSubfolder() {
        IArchimateModel model = createTestModelWithDefaultFolders();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        IFolder strategyRoot = model.getFolder(FolderType.STRATEGY);
        IFolder sub1 = IArchimateFactory.eINSTANCE.createFolder();
        sub1.setName("L1");
        sub1.setType(FolderType.USER);
        strategyRoot.getFolders().add(sub1);
        IFolder sub2 = IArchimateFactory.eINSTANCE.createFolder();
        sub2.setName("L2");
        sub2.setType(FolderType.USER);
        sub1.getFolders().add(sub2);

        assertSame(strategyRoot, accessor.getRootFolder(sub2));
    }

    @Test
    public void shouldThrowFolderLayerMismatch_whenCapabilityInBusinessFolder() {
        IArchimateModel model = createTestModelWithDefaultFolders();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        IArchimateElement capability = IArchimateFactory.eINSTANCE.createCapability();
        capability.setName("Test Capability");
        IFolder businessRoot = model.getFolder(FolderType.BUSINESS);

        try {
            accessor.validateFolderLayerMatch(capability, businessRoot);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.FOLDER_LAYER_MISMATCH, e.getErrorCode());
            assertTrue(e.getMessage().contains("Capability"));
            assertTrue(e.getMessage().contains("Strategy"));
            assertTrue(e.getMessage().contains("Business"));
        }
    }

    @Test
    public void shouldThrowFolderLayerMismatch_whenBusinessActorInStrategyFolder() {
        IArchimateModel model = createTestModelWithDefaultFolders();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        IArchimateElement actor = IArchimateFactory.eINSTANCE.createBusinessActor();
        actor.setName("Test Actor");
        IFolder strategyRoot = model.getFolder(FolderType.STRATEGY);

        try {
            accessor.validateFolderLayerMatch(actor, strategyRoot);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.FOLDER_LAYER_MISMATCH, e.getErrorCode());
            assertTrue(e.getMessage().contains("BusinessActor"));
            assertTrue(e.getMessage().contains("Business"));
            assertTrue(e.getMessage().contains("Strategy"));
        }
    }

    @Test
    public void shouldSucceedValidation_whenCapabilityInStrategySubfolder() {
        IArchimateModel model = createTestModelWithDefaultFolders();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        IArchimateElement capability = IArchimateFactory.eINSTANCE.createCapability();
        IFolder strategyRoot = model.getFolder(FolderType.STRATEGY);
        IFolder sub = IArchimateFactory.eINSTANCE.createFolder();
        sub.setName("Capabilities");
        sub.setType(FolderType.USER);
        strategyRoot.getFolders().add(sub);

        // Should not throw
        accessor.validateFolderLayerMatch(capability, sub);
    }

    @Test
    public void shouldSucceedValidation_whenCapabilityInStrategyRootFolder() {
        IArchimateModel model = createTestModelWithDefaultFolders();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        IArchimateElement capability = IArchimateFactory.eINSTANCE.createCapability();
        IFolder strategyRoot = model.getFolder(FolderType.STRATEGY);

        // Should not throw
        accessor.validateFolderLayerMatch(capability, strategyRoot);
    }

    @Test
    public void shouldThrowFolderLayerMismatch_whenCapabilityInBusinessSubfolder() {
        IArchimateModel model = createTestModelWithDefaultFolders();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        IArchimateElement capability = IArchimateFactory.eINSTANCE.createCapability();
        IFolder businessRoot = model.getFolder(FolderType.BUSINESS);
        IFolder sub = IArchimateFactory.eINSTANCE.createFolder();
        sub.setName("Capabilities");
        sub.setType(FolderType.USER);
        businessRoot.getFolders().add(sub);

        try {
            accessor.validateFolderLayerMatch(capability, sub);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.FOLDER_LAYER_MISMATCH, e.getErrorCode());
            assertTrue(e.getMessage().contains("Capabilities"));
            assertTrue(e.getMessage().contains("Strategy"));
            assertTrue(e.getMessage().contains("Business"));
        }
    }

    @Test
    public void shouldIncludeDescriptiveErrorInfo_onFolderLayerMismatch() {
        IArchimateModel model = createTestModelWithDefaultFolders();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        IArchimateElement goal = IArchimateFactory.eINSTANCE.createGoal();
        IFolder appRoot = model.getFolder(FolderType.APPLICATION);
        IFolder sub = IArchimateFactory.eINSTANCE.createFolder();
        sub.setName("Goals Here");
        sub.setType(FolderType.USER);
        appRoot.getFolders().add(sub);

        try {
            accessor.validateFolderLayerMatch(goal, sub);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.FOLDER_LAYER_MISMATCH, e.getErrorCode());
            assertTrue("Should contain folder name", e.getMessage().contains("Goals Here"));
            assertTrue("Should contain expected layer", e.getMessage().contains("Motivation"));
            assertTrue("Should contain actual layer", e.getMessage().contains("Application"));
            assertNotNull(e.getSuggestedCorrection());
            assertTrue(e.getSuggestedCorrection().contains("omit folderId"));
        }
    }

    @Test
    public void shouldSucceedValidation_whenWorkPackageInImplMigrationFolder() {
        IArchimateModel model = createTestModelWithDefaultFolders();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        IArchimateElement wp = IArchimateFactory.eINSTANCE.createWorkPackage();
        IFolder implRoot = model.getFolder(FolderType.IMPLEMENTATION_MIGRATION);

        // Should not throw
        accessor.validateFolderLayerMatch(wp, implRoot);
    }

    private IArchimateModel createTestModelWithDefaultFolders() {
        IArchimateModel model = IArchimateFactory.eINSTANCE.createArchimateModel();
        model.setName("Validation Test");
        model.setId("model-validation");
        model.setDefaults();
        return model;
    }

    // ---- Story 11-27: computeOptimizeGroupOrderPass / computeAutoRoutePass tests ----

    /**
     * Helper: creates a view with two groups, each containing 3 elements,
     * and inter-group connections arranged to produce crossings.
     * Returns [model, view] for use with computeOptimizeGroupOrderPass.
     */
    private Object[] createGroupedViewWithCrossings() {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;

        IArchimateModel model = factory.createArchimateModel();
        model.setName("Test Model");
        model.setId("model-grouped");
        model.setDefaults();

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-grouped");
        view.setName("Grouped View");

        // Group A (left, at x=0 y=0)
        IDiagramModelGroup groupA = factory.createDiagramModelGroup();
        groupA.setId("groupA");
        groupA.setName("Group A");
        groupA.setBounds(0, 0, 200, 250);

        // Elements A1, A2, A3 stacked vertically in Group A
        IArchimateElement elemA1 = factory.createBusinessActor();
        elemA1.setId("elemA1"); elemA1.setName("A1");
        model.getFolder(FolderType.BUSINESS).getElements().add(elemA1);
        IDiagramModelArchimateObject voA1 = factory.createDiagramModelArchimateObject();
        voA1.setArchimateElement(elemA1);
        voA1.setBounds(10, 34, 120, 55);
        groupA.getChildren().add(voA1);

        IArchimateElement elemA2 = factory.createBusinessActor();
        elemA2.setId("elemA2"); elemA2.setName("A2");
        model.getFolder(FolderType.BUSINESS).getElements().add(elemA2);
        IDiagramModelArchimateObject voA2 = factory.createDiagramModelArchimateObject();
        voA2.setArchimateElement(elemA2);
        voA2.setBounds(10, 109, 120, 55);
        groupA.getChildren().add(voA2);

        IArchimateElement elemA3 = factory.createBusinessActor();
        elemA3.setId("elemA3"); elemA3.setName("A3");
        model.getFolder(FolderType.BUSINESS).getElements().add(elemA3);
        IDiagramModelArchimateObject voA3 = factory.createDiagramModelArchimateObject();
        voA3.setArchimateElement(elemA3);
        voA3.setBounds(10, 184, 120, 55);
        groupA.getChildren().add(voA3);

        // Group B (right, at x=300 y=0)
        IDiagramModelGroup groupB = factory.createDiagramModelGroup();
        groupB.setId("groupB");
        groupB.setName("Group B");
        groupB.setBounds(300, 0, 200, 250);

        IArchimateElement elemB1 = factory.createBusinessProcess();
        elemB1.setId("elemB1"); elemB1.setName("B1");
        model.getFolder(FolderType.BUSINESS).getElements().add(elemB1);
        IDiagramModelArchimateObject voB1 = factory.createDiagramModelArchimateObject();
        voB1.setArchimateElement(elemB1);
        voB1.setBounds(10, 34, 120, 55);
        groupB.getChildren().add(voB1);

        IArchimateElement elemB2 = factory.createBusinessProcess();
        elemB2.setId("elemB2"); elemB2.setName("B2");
        model.getFolder(FolderType.BUSINESS).getElements().add(elemB2);
        IDiagramModelArchimateObject voB2 = factory.createDiagramModelArchimateObject();
        voB2.setArchimateElement(elemB2);
        voB2.setBounds(10, 109, 120, 55);
        groupB.getChildren().add(voB2);

        IArchimateElement elemB3 = factory.createBusinessProcess();
        elemB3.setId("elemB3"); elemB3.setName("B3");
        model.getFolder(FolderType.BUSINESS).getElements().add(elemB3);
        IDiagramModelArchimateObject voB3 = factory.createDiagramModelArchimateObject();
        voB3.setArchimateElement(elemB3);
        voB3.setBounds(10, 184, 120, 55);
        groupB.getChildren().add(voB3);

        view.getChildren().add(groupA);
        view.getChildren().add(groupB);

        // Create crossing connections: A1→B3, A2→B2, A3→B1
        IArchimateRelationship rel1 = factory.createServingRelationship();
        rel1.setId("rel1"); rel1.setSource(elemA1); rel1.setTarget(elemB3);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel1);
        var conn1 = factory.createDiagramModelArchimateConnection();
        conn1.setArchimateRelationship(rel1);
        conn1.connect(voA1, voB3);

        IArchimateRelationship rel2 = factory.createServingRelationship();
        rel2.setId("rel2"); rel2.setSource(elemA2); rel2.setTarget(elemB2);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel2);
        var conn2 = factory.createDiagramModelArchimateConnection();
        conn2.setArchimateRelationship(rel2);
        conn2.connect(voA2, voB2);

        IArchimateRelationship rel3 = factory.createServingRelationship();
        rel3.setId("rel3"); rel3.setSource(elemA3); rel3.setTarget(elemB1);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel3);
        var conn3 = factory.createDiagramModelArchimateConnection();
        conn3.setArchimateRelationship(rel3);
        conn3.connect(voA3, voB1);

        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        return new Object[]{model, view};
    }

    @Test
    public void shouldReturnCompound_whenGroupedViewWithCrossings() {
        Object[] result = createGroupedViewWithCrossings();
        IArchimateModel model = (IArchimateModel) result[0];
        IArchimateDiagramModel view = (IArchimateDiagramModel) result[1];

        ArchiModelAccessorImpl accessor = createAccessorWithTestDispatcher(model);
        ArchiModelAccessorImpl.OptimizeGroupOrderPassResult passResult =
                accessor.computeOptimizeGroupOrderPass(view, model, "DOWN");

        assertNotNull("Should return result for grouped view with crossings", passResult);
        assertTrue("Compound should have commands", passResult.compound.size() > 0);
        assertTrue("Position count should be positive", passResult.positionCount > 0);
    }

    @Test
    public void shouldUseRowArrangement_whenHorizontalDirection() {
        Object[] result = createGroupedViewWithCrossings();
        IArchimateModel model = (IArchimateModel) result[0];
        IArchimateDiagramModel view = (IArchimateDiagramModel) result[1];

        ArchiModelAccessorImpl accessor = createAccessorWithTestDispatcher(model);

        // RIGHT direction should use row arrangement
        ArchiModelAccessorImpl.OptimizeGroupOrderPassResult rightResult =
                accessor.computeOptimizeGroupOrderPass(view, model, "RIGHT");
        assertNotNull("Should return result for RIGHT direction", rightResult);
        assertTrue("Should have position commands for RIGHT", rightResult.positionCount > 0);

        // LEFT direction should also use row arrangement
        ArchiModelAccessorImpl.OptimizeGroupOrderPassResult leftResult =
                accessor.computeOptimizeGroupOrderPass(view, model, "LEFT");
        assertNotNull("Should return result for LEFT direction", leftResult);
        assertTrue("Should have position commands for LEFT", leftResult.positionCount > 0);
    }

    @Test
    public void shouldReturnNull_whenFlatViewNoGroups() {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setName("Flat Model"); model.setId("model-flat"); model.setDefaults();

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-flat"); view.setName("Flat View");

        // Add elements directly to view (no groups)
        IArchimateElement elem1 = factory.createBusinessActor();
        elem1.setId("e1"); elem1.setName("Elem1");
        model.getFolder(FolderType.BUSINESS).getElements().add(elem1);
        IDiagramModelArchimateObject vo1 = factory.createDiagramModelArchimateObject();
        vo1.setArchimateElement(elem1);
        vo1.setBounds(50, 50, 120, 55);
        view.getChildren().add(vo1);

        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        ArchiModelAccessorImpl accessor = createAccessorWithTestDispatcher(model);
        ArchiModelAccessorImpl.OptimizeGroupOrderPassResult passResult =
                accessor.computeOptimizeGroupOrderPass(view, model, "DOWN");

        assertNull("Should return null for flat view with no groups", passResult);
    }

    @Test
    public void shouldReturnNull_whenGroupsButNoInterGroupConnections() {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setName("No Connections"); model.setId("model-noconn"); model.setDefaults();

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-noconn"); view.setName("No Connections View");

        IDiagramModelGroup groupA = factory.createDiagramModelGroup();
        groupA.setId("gA"); groupA.setName("Group A"); groupA.setBounds(0, 0, 200, 150);

        IArchimateElement elem1 = factory.createBusinessActor();
        elem1.setId("e1"); elem1.setName("E1");
        model.getFolder(FolderType.BUSINESS).getElements().add(elem1);
        IDiagramModelArchimateObject vo1 = factory.createDiagramModelArchimateObject();
        vo1.setArchimateElement(elem1);
        vo1.setBounds(10, 34, 120, 55);
        groupA.getChildren().add(vo1);

        IDiagramModelGroup groupB = factory.createDiagramModelGroup();
        groupB.setId("gB"); groupB.setName("Group B"); groupB.setBounds(300, 0, 200, 150);

        IArchimateElement elem2 = factory.createBusinessProcess();
        elem2.setId("e2"); elem2.setName("E2");
        model.getFolder(FolderType.BUSINESS).getElements().add(elem2);
        IDiagramModelArchimateObject vo2 = factory.createDiagramModelArchimateObject();
        vo2.setArchimateElement(elem2);
        vo2.setBounds(10, 34, 120, 55);
        groupB.getChildren().add(vo2);

        view.getChildren().add(groupA);
        view.getChildren().add(groupB);
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        ArchiModelAccessorImpl accessor = createAccessorWithTestDispatcher(model);
        ArchiModelAccessorImpl.OptimizeGroupOrderPassResult passResult =
                accessor.computeOptimizeGroupOrderPass(view, model, "DOWN");

        assertNull("Should return null when no inter-group connections", passResult);
    }

    @Test
    public void shouldReturnNull_whenReorderDoesNotImprove() {
        // Create view where elements are already in optimal order
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setName("Optimal"); model.setId("model-optimal"); model.setDefaults();

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-optimal"); view.setName("Optimal View");

        IDiagramModelGroup groupA = factory.createDiagramModelGroup();
        groupA.setId("gA"); groupA.setName("Group A"); groupA.setBounds(0, 0, 200, 250);

        IDiagramModelGroup groupB = factory.createDiagramModelGroup();
        groupB.setId("gB"); groupB.setName("Group B"); groupB.setBounds(300, 0, 200, 250);

        // A1, A2 in Group A; B1, B2 in Group B
        // Parallel connections: A1→B1, A2→B2 (zero crossings — already optimal)
        IArchimateElement eA1 = factory.createBusinessActor();
        eA1.setId("eA1"); eA1.setName("A1");
        model.getFolder(FolderType.BUSINESS).getElements().add(eA1);
        IDiagramModelArchimateObject voA1 = factory.createDiagramModelArchimateObject();
        voA1.setArchimateElement(eA1);
        voA1.setBounds(10, 34, 120, 55);
        groupA.getChildren().add(voA1);

        IArchimateElement eA2 = factory.createBusinessActor();
        eA2.setId("eA2"); eA2.setName("A2");
        model.getFolder(FolderType.BUSINESS).getElements().add(eA2);
        IDiagramModelArchimateObject voA2 = factory.createDiagramModelArchimateObject();
        voA2.setArchimateElement(eA2);
        voA2.setBounds(10, 109, 120, 55);
        groupA.getChildren().add(voA2);

        IArchimateElement eB1 = factory.createBusinessProcess();
        eB1.setId("eB1"); eB1.setName("B1");
        model.getFolder(FolderType.BUSINESS).getElements().add(eB1);
        IDiagramModelArchimateObject voB1 = factory.createDiagramModelArchimateObject();
        voB1.setArchimateElement(eB1);
        voB1.setBounds(10, 34, 120, 55);
        groupB.getChildren().add(voB1);

        IArchimateElement eB2 = factory.createBusinessProcess();
        eB2.setId("eB2"); eB2.setName("B2");
        model.getFolder(FolderType.BUSINESS).getElements().add(eB2);
        IDiagramModelArchimateObject voB2 = factory.createDiagramModelArchimateObject();
        voB2.setArchimateElement(eB2);
        voB2.setBounds(10, 109, 120, 55);
        groupB.getChildren().add(voB2);

        view.getChildren().add(groupA);
        view.getChildren().add(groupB);

        // Parallel connections (no crossings)
        IArchimateRelationship rel1 = factory.createServingRelationship();
        rel1.setId("r1"); rel1.setSource(eA1); rel1.setTarget(eB1);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel1);
        var conn1 = factory.createDiagramModelArchimateConnection();
        conn1.setArchimateRelationship(rel1);
        conn1.connect(voA1, voB1);

        IArchimateRelationship rel2 = factory.createServingRelationship();
        rel2.setId("r2"); rel2.setSource(eA2); rel2.setTarget(eB2);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel2);
        var conn2 = factory.createDiagramModelArchimateConnection();
        conn2.setArchimateRelationship(rel2);
        conn2.connect(voA2, voB2);

        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        ArchiModelAccessorImpl accessor = createAccessorWithTestDispatcher(model);
        ArchiModelAccessorImpl.OptimizeGroupOrderPassResult passResult =
                accessor.computeOptimizeGroupOrderPass(view, model, "DOWN");

        assertNull("Should return null when reorder doesn't improve crossings", passResult);
    }

    @Test
    public void shouldRouteConnections_whenComputeAutoRoutePass() {
        Object[] result = createGroupedViewWithCrossings();
        IArchimateModel model = (IArchimateModel) result[0];
        IArchimateDiagramModel view = (IArchimateDiagramModel) result[1];

        ArchiModelAccessorImpl accessor = createAccessorWithTestDispatcher(model);
        ArchiModelAccessorImpl.AutoRoutePassResult passResult =
                accessor.computeAutoRoutePass("view-grouped", view, model);

        assertNotNull("Should return result for view with connections", passResult);
        assertTrue("Compound should have commands", passResult.compound.size() > 0);
        assertTrue("Routed count should be positive", passResult.routedCount > 0);
    }

    @Test
    public void shouldMergeCompounds_whenAllPhasesProduceCommands() {
        // Verifies the compound merging pattern used in executeQualityTargetLoop.
        // Tests NonNotifyingCompoundCommand.add() mechanics — the loop itself
        // requires full EMF/OSGi runtime and is tested via E2E integration tests.
        NonNotifyingCompoundCommand elkCompound =
                new NonNotifyingCompoundCommand("ELK");
        Command elkCmd = new Command("elk-pos") {};
        elkCompound.add(elkCmd);

        NonNotifyingCompoundCommand optimizeCompound =
                new NonNotifyingCompoundCommand("Optimize");
        Command optCmd = new Command("opt-pos") {};
        optimizeCompound.add(optCmd);

        NonNotifyingCompoundCommand routeCompound =
                new NonNotifyingCompoundCommand("Route");
        Command routeCmd = new Command("route-bp") {};
        routeCompound.add(routeCmd);

        // Merge all compounds (same pattern as executeQualityTargetLoop)
        NonNotifyingCompoundCommand merged =
                new NonNotifyingCompoundCommand(elkCompound.getLabel());
        for (Object cmd : elkCompound.getCommands()) {
            merged.add((Command) cmd);
        }
        for (Object cmd : optimizeCompound.getCommands()) {
            merged.add((Command) cmd);
        }
        for (Object cmd : routeCompound.getCommands()) {
            merged.add((Command) cmd);
        }

        assertEquals("Merged compound should contain all 3 commands", 3, merged.size());
        assertEquals("ELK", merged.getLabel());
    }

    // ---- findLimitingFactor / getRemediation (backlog-b13 code review) ----

    @Test
    public void findLimitingFactor_shouldSelectWorstMetric() {
        // overlaps=poor, edgeCrossings=fair, labelOverlaps=good → overlaps is worst
        AssessLayoutResultDto assessment = buildAssessment(
                Map.of("overlaps", "poor", "edgeCrossings", "fair",
                        "labelOverlaps", "good", "overall", "poor"),
                3, 5, 1);
        assertEquals("overlaps", ArchiModelAccessorImpl.findLimitingFactor(assessment));
    }

    @Test
    public void findLimitingFactor_shouldBreakTieByCount() {
        // overlaps=fair (count 2), edgeCrossings=fair (count 10) → edgeCrossings wins tie
        AssessLayoutResultDto assessment = buildAssessment(
                Map.of("overlaps", "fair", "edgeCrossings", "fair", "overall", "fair"),
                2, 10, 0);
        assertEquals("edgeCrossings", ArchiModelAccessorImpl.findLimitingFactor(assessment));
    }

    @Test
    public void findLimitingFactor_shouldSkipPassRatings() {
        // overlaps=pass, edgeCrossings=fair → edgeCrossings (pass is skipped)
        AssessLayoutResultDto assessment = buildAssessment(
                Map.of("overlaps", "pass", "edgeCrossings", "fair", "overall", "fair"),
                0, 5, 0);
        assertEquals("edgeCrossings", ArchiModelAccessorImpl.findLimitingFactor(assessment));
    }

    @Test
    public void findLimitingFactor_shouldSkipOverallEntry() {
        // Only "overall" has a bad rating — should return null (no metric to blame)
        AssessLayoutResultDto assessment = buildAssessment(
                Map.of("overlaps", "pass", "edgeCrossings", "pass",
                        "labelOverlaps", "pass", "overall", "fair"),
                0, 0, 0);
        assertNull(ArchiModelAccessorImpl.findLimitingFactor(assessment));
    }

    @Test
    public void getRemediation_shouldReturnSpecificTextForEachFactor() {
        // Verify all 6 known factors produce non-null, distinct remediation texts
        String[] factors = {"labelOverlaps", "overlaps", "edgeCrossings",
                "passThroughs", "spacing", "alignment"};
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String factor : factors) {
            String remediation = ArchiModelAccessorImpl.getRemediation(factor);
            assertNotNull("Remediation for " + factor + " should not be null", remediation);
            assertTrue("Remediation for " + factor + " should be unique",
                    seen.add(remediation));
        }
    }

    @Test
    public void getRemediation_shouldReturnFallbackForUnknownFactor() {
        String remediation = ArchiModelAccessorImpl.getRemediation("unknownMetric");
        assertNotNull("Unknown factor should get a fallback remediation", remediation);
        assertTrue(remediation.contains("assess-layout"));
    }

    @Test
    public void getMetricCount_shouldMapMetricsToAssessmentFields() {
        AssessLayoutResultDto assessment = buildAssessment(
                Map.of("overlaps", "poor", "edgeCrossings", "fair",
                        "labelOverlaps", "fair", "overall", "poor"),
                4, 7, 2);
        assertEquals(4, ArchiModelAccessorImpl.getMetricCount("overlaps", assessment));
        assertEquals(7, ArchiModelAccessorImpl.getMetricCount("edgeCrossings", assessment));
        assertEquals(2, ArchiModelAccessorImpl.getMetricCount("labelOverlaps", assessment));
        assertEquals(0, ArchiModelAccessorImpl.getMetricCount("spacing", assessment));
        assertEquals(0, ArchiModelAccessorImpl.getMetricCount("alignment", assessment));
    }

    /**
     * Builds a minimal AssessLayoutResultDto for limiting factor tests.
     */
    private AssessLayoutResultDto buildAssessment(
            Map<String, String> ratingBreakdown,
            int overlapCount, int edgeCrossingCount, int labelOverlapCount) {
        return new AssessLayoutResultDto(
                "v-1", 5, 3,
                overlapCount, 0, edgeCrossingCount, 0.0,
                50.0, 80, "fair", ratingBreakdown,
                List.of(), List.of(), List.of(), List.of(),
                labelOverlapCount, List.of(), 0, List.of(),
                0, List.of(), false, 0, 0, null, List.of());
    }

    // ---- Test helpers ----

    /**
     * Creates an accessor with a test-friendly MutationDispatcher that
     * bypasses Display.syncExec() and CommandStack, executing commands directly.
     */
    private ArchiModelAccessorImpl createAccessorWithTestDispatcher(IArchimateModel model) {
        MutationDispatcher testDispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                // Execute directly — bypasses Display.syncExec + CommandStack.
                // Decompose compound commands to avoid NonNotifyingCompoundCommand's
                // internal IEditorModelManager reference (requires OSGi runtime).
                executeDecomposed(command);
            }
            @Override
            protected void dispatchCommand(Command command) {
                executeDecomposed(command);
            }
            private void executeDecomposed(Command command) {
                if (command instanceof CompoundCommand compound) {
                    for (Object cmd : compound.getCommands()) {
                        ((Command) cmd).execute();
                    }
                } else {
                    command.execute();
                }
            }
        };
        return new ArchiModelAccessorImpl(stubModelManager, testDispatcher);
    }

    /**
     * Records model change events for assertion.
     */
    private static class TestModelChangeListener implements ModelChangeListener {
        final List<ModelChangeEvent> events = new ArrayList<>();

        @Override
        public void onModelChanged(String modelName, String modelId) {
            events.add(new ModelChangeEvent(modelName, modelId));
        }
    }

    private record ModelChangeEvent(String modelName, String modelId) {}

    /**
     * Stub implementation of IEditorModelManager for testing.
     * Only implements methods used by ArchiModelAccessorImpl.
     */
    private static class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final List<PropertyChangeListener> listeners = new ArrayList<>();

        void setModels(List<IArchimateModel> models) {
            this.models = models;
        }

        boolean hasPropertyChangeListener() {
            return !listeners.isEmpty();
        }

        void firePropertyChange(String propertyName, Object oldValue, Object newValue) {
            PropertyChangeEvent evt = new PropertyChangeEvent(this, propertyName, oldValue, newValue);
            // Copy list to avoid ConcurrentModificationException
            for (PropertyChangeListener listener : new ArrayList<>(listeners)) {
                listener.propertyChange(evt);
            }
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

        // ---- Unused IEditorModelManager methods (required by interface, matching Archi 5.8) ----

        @Override
        public IArchimateModel createNewModel() { return null; }

        @Override
        public void registerModel(IArchimateModel model) {}

        @Override
        public IArchimateModel openModel(File file) { return null; }

        @Override
        public void openModel(IArchimateModel model) {}

        @Override
        public IArchimateModel loadModel(File file) { return null; }

        @Override
        public IArchimateModel load(File file) throws IOException { return null; }

        @Override
        public boolean closeModel(IArchimateModel model) throws IOException { return false; }

        @Override
        public boolean closeModel(IArchimateModel model, boolean askSave) throws IOException { return false; }

        @Override
        public boolean isModelLoaded(File file) { return false; }

        @Override
        public boolean isModelDirty(IArchimateModel model) { return false; }

        @Override
        public boolean saveModel(IArchimateModel model) throws IOException { return false; }

        @Override
        public boolean saveModelAs(IArchimateModel model) throws IOException { return false; }

        @Override
        public void saveState() throws IOException {}

        @Override
        public void firePropertyChange(Object source, String prop, Object oldValue, Object newValue) {}
    }

    /**
     * Minimal stub of IArchimateModel for testing model lifecycle (not query methods).
     * Extends MinimalEObjectImpl to satisfy all EObject/Notifier abstract methods.
     * Only implements getName() and getId() for ArchiModelAccessorImpl lifecycle tests.
     *
     * <p>Query method tests use {@link IArchimateFactory#eINSTANCE} instead for proper
     * EMF containment (required by ArchimateModelUtils.getObjectByID).</p>
     */
    private static class StubArchimateModel extends MinimalEObjectImpl implements IArchimateModel {
        private final String id;
        private final String name;

        StubArchimateModel(String id, String name) {
            this.id = id;
            this.name = name;
        }

        // ---- Methods used by ArchiModelAccessorImpl ----

        @Override public String getId() { return id; }
        @Override public String getName() { return name; }

        // ---- Unused IArchimateModel methods (required by interface) ----

        @Override public void setId(String value) { throw new UnsupportedOperationException(); }
        @Override public void setName(String value) { throw new UnsupportedOperationException(); }
        @Override public String getPurpose() { return null; }
        @Override public void setPurpose(String value) { }
        @Override public File getFile() { return null; }
        @Override public void setFile(File value) { }
        @Override public String getVersion() { return null; }
        @Override public void setVersion(String value) { }
        @Override public IMetadata getMetadata() { return null; }
        @Override public void setMetadata(IMetadata value) { }
        @Override public EList<IProfile> getProfiles() { return new BasicEList<>(); }
        @Override public void setDefaults() { }
        @Override public IFolder getDefaultFolderForObject(EObject object) { return null; }
        @Override public IDiagramModel getDefaultDiagramModel() { return null; }
        @Override public EList<IDiagramModel> getDiagramModels() { return new BasicEList<>(); }
        @Override public IFolder getFolder(FolderType type) { return null; }
        @Override public boolean addModelContentListener(IModelContentListener listener) { return false; }
        @Override public boolean removeModelContentListener(IModelContentListener listener) { return false; }
        @Override public void dispose() { }

        // ---- IFolderContainer ----
        @Override public EList<IFolder> getFolders() { return new BasicEList<>(); }

        // ---- IArchimateModelObject ----
        @Override public IArchimateModel getArchimateModel() { return this; }

        // ---- IAdapter ----
        @Override public Object getAdapter(Object adapter) { return null; }
        @Override public void setAdapter(Object adapter, Object object) { }

        // ---- IFeatures ----
        @Override public IFeaturesEList getFeatures() { return null; }

        // ---- IProperties ----
        @Override public EList<IProperty> getProperties() { return new BasicEList<>(); }
    }

    // ---- B19: Orphaned Relationship Structural Fix tests ----

    @Test
    public void shouldSkipOrphanedRelationship_whenAutoConnecting() {
        // B19 AC-2: auto-connect containment guard
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Create an orphaned relationship: connected but NOT in containment tree
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateRelationship orphan = factory.createAssociationRelationship();
        orphan.setId("rel-orphan-001");
        // connect() sets up EMF cross-references but we don't add to folder
        IArchimateElement source = (IArchimateElement) model.getFolder(FolderType.APPLICATION)
                .getElements().get(0); // ac-001
        IArchimateElement target = (IArchimateElement) model.getFolder(FolderType.BUSINESS)
                .getElements().get(1); // bp-001
        orphan.connect(source, target);
        // NOT added to Relations folder — orphan.eContainer() == null

        // Place source element on view
        accessor.addToView("default", "view-001", "ac-001", 50, 50, 120, 55, false, null, null, null);

        // Place target with autoConnect — should only connect the contained rel-001,
        // NOT the orphaned rel-orphan-001
        MutationResult<AddToViewResultDto> result = accessor.addToView(
                "default", "view-001", "bp-001", 250, 50, 120, 55, true, null, null, null);

        assertNotNull(result.entity().autoConnections());
        // Only the contained relationship (rel-001) should produce a connection
        assertEquals(1, result.entity().autoConnections().size());
        assertEquals("rel-001", result.entity().autoConnections().get(0).relationshipId());
    }

    @Test
    public void shouldSkipOrphanedRelationship_whenAutoConnectViewCalled() {
        // B19 AC-2: auto-connect-view tool containment guard (distinct from addToView autoConnect)
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Create an orphaned relationship: connected but NOT in containment tree
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateRelationship orphan = factory.createAssociationRelationship();
        orphan.setId("rel-orphan-003");
        IArchimateElement source = (IArchimateElement) model.getFolder(FolderType.APPLICATION)
                .getElements().get(0); // ac-001
        IArchimateElement target = (IArchimateElement) model.getFolder(FolderType.BUSINESS)
                .getElements().get(1); // bp-001
        orphan.connect(source, target);
        // NOT added to Relations folder — orphan.eContainer() == null

        // Place both elements on view without autoConnect
        accessor.addToView("default", "view-001", "ac-001", 50, 50, 120, 55, false, null, null, null);
        accessor.addToView("default", "view-001", "bp-001", 250, 50, 120, 55, false, null, null, null);

        // Call auto-connect-view — should only connect the contained rel-001,
        // NOT the orphaned rel-orphan-003
        MutationResult<AutoConnectResultDto> result = accessor.autoConnectView(
                "default", "view-001", null, null, null);

        assertNotNull(result);
        // Only the contained relationship (rel-001: ac-001 -> bp-001) should produce a connection
        assertEquals(1, result.entity().connectionsCreated());
    }

    @Test
    public void shouldHandleOrphanedRelationship_whenDeletingElement() {
        // B19 AC-6: delete element NPE guard
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Create an orphaned relationship referencing ba-001
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateRelationship orphan = factory.createAssociationRelationship();
        orphan.setId("rel-orphan-002");
        IArchimateElement ba001 = (IArchimateElement) model.getFolder(FolderType.BUSINESS)
                .getElements().get(0); // ba-001 (Customer)
        IArchimateElement bp001 = (IArchimateElement) model.getFolder(FolderType.BUSINESS)
                .getElements().get(1); // bp-001 (Order Processing)
        orphan.connect(ba001, bp001);
        // NOT added to folder — orphan.eContainer() == null

        // Delete ba-001 — should NOT NPE on orphaned relationship
        try {
            accessor.deleteElement("default", "ba-001");
            // If we get here, the element was deleted without NPE — success
        } catch (NullPointerException e) {
            fail("B19: deleteElement should not NPE on orphaned relationships");
        }
    }

    @Test
    public void shouldNotConnectRelationship_beforeCommandExecution() {
        // B19 AC-1: deferred connect — verify via createRelationship
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));

        // Use a dispatcher that captures the command WITHOUT executing it
        final Command[] capturedCommand = new Command[1];
        MutationDispatcher captureDispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                capturedCommand[0] = command;
                // DO NOT execute — simulates preparation without execution
            }
            @Override
            protected void dispatchCommand(Command command) {
                capturedCommand[0] = command;
            }
        };
        accessor = new ArchiModelAccessorImpl(stubModelManager, captureDispatcher);

        try {
            accessor.createRelationship("default", "AssociationRelationship",
                    "ba-001", "bp-001", "test-assoc");

            // The relationship should NOT appear in source's cross-references
            // because connect() was deferred to command execution (which we skipped)
            IArchimateElement ba001 = (IArchimateElement) model.getFolder(FolderType.BUSINESS)
                    .getElements().get(0);
            boolean foundOrphan = false;
            for (IArchimateRelationship rel : ba001.getSourceRelationships()) {
                if ("test-assoc".equals(rel.getName())
                        && "AssociationRelationship".equals(rel.eClass().getName())) {
                    foundOrphan = true;
                    break;
                }
            }
            assertFalse("B19: relationship should NOT be in cross-references before "
                    + "command execution", foundOrphan);
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assume.assumeTrue("Requires OSGi runtime for RelationshipsMatrix", false);
        }
    }

    // ---- resizeElementsToFit tests (Story B48) ----

    @Test
    public void resizeElementsToFit_shouldResizeLongNameElements() {
        IArchimateModel model = createTestModelForResize();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Add elements to view first
        accessor.addToView("default", "view-resize", "el-long", 50, 50, null, null, false, null, null, null);

        MutationResult<ResizeElementsResultDto> result =
                accessor.resizeElementsToFit("default", "view-resize", null);

        assertNotNull(result);
        assertNotNull(result.entity());
        // The long name element (>15 chars) should have been resized
        assertTrue("Should have resized at least 1 element",
                result.entity().resizedCount() >= 1);
    }

    @Test
    public void resizeElementsToFit_shouldNotResizeShortNames() {
        IArchimateModel model = createTestModelForResize();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Add short-name element to view
        accessor.addToView("default", "view-resize", "el-short", 50, 50, null, null, false, null, null, null);

        MutationResult<ResizeElementsResultDto> result =
                accessor.resizeElementsToFit("default", "view-resize", null);

        assertNotNull(result);
        // Short name ("Server") should keep defaults → unchanged
        assertEquals(0, result.entity().resizedCount());
        assertEquals(1, result.entity().unchangedCount());
    }

    @Test
    public void resizeElementsToFit_shouldHandleNestedContainment() {
        IArchimateModel model = createTestModelForResize();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Add parent element, then child inside it
        MutationResult<AddToViewResultDto> parentResult =
                accessor.addToView("default", "view-resize", "el-parent", 50, 50, 300, 200, false, null, null, null);
        String parentVoId = parentResult.entity().viewObject().viewObjectId();

        accessor.addToView("default", "view-resize", "el-long", 10, 30, null, null, false, parentVoId, null, null);

        MutationResult<ResizeElementsResultDto> result =
                accessor.resizeElementsToFit("default", "view-resize", null);

        assertNotNull(result);
        // Both parent and child should be processed
        assertTrue("Should process elements",
                result.entity().resizedCount() + result.entity().unchangedCount() >= 2);
    }

    @Test
    public void resizeElementsToFit_shouldFilterByElementIds() {
        IArchimateModel model = createTestModelForResize();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Add two elements
        MutationResult<AddToViewResultDto> r1 =
                accessor.addToView("default", "view-resize", "el-long", 50, 50, null, null, false, null, null, null);
        accessor.addToView("default", "view-resize", "el-short", 200, 50, null, null, false, null, null, null);

        // Only resize the first one
        String firstVoId = r1.entity().viewObject().viewObjectId();
        MutationResult<ResizeElementsResultDto> result =
                accessor.resizeElementsToFit("default", "view-resize", List.of(firstVoId));

        assertNotNull(result);
        // Should only process 1 element (the filtered one)
        assertEquals(1, result.entity().resizedCount() + result.entity().unchangedCount());
    }

    private IArchimateModel createTestModelForResize() {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;

        IArchimateModel model = factory.createArchimateModel();
        model.setName("Resize Test Model");
        model.setId("model-resize");
        model.setDefaults();

        // Long name element (>15 chars — triggers auto-sizing)
        IApplicationComponent longName = factory.createApplicationComponent();
        longName.setId("el-long");
        longName.setName("Transaction Monitoring System");
        model.getFolder(FolderType.APPLICATION).getElements().add(longName);

        // Short name element (<=15 chars — keeps defaults)
        IApplicationComponent shortName = factory.createApplicationComponent();
        shortName.setId("el-short");
        shortName.setName("Server");
        model.getFolder(FolderType.APPLICATION).getElements().add(shortName);

        // Parent element for containment test
        IApplicationComponent parent = factory.createApplicationComponent();
        parent.setId("el-parent");
        parent.setName("Integration Platform");
        model.getFolder(FolderType.APPLICATION).getElements().add(parent);

        // View
        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-resize");
        view.setName("Resize Test View");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        return model;
    }
}
