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
import com.archimatetool.model.IArchimateConcept;
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
import com.archimatetool.model.ITextAlignment;
import com.archimatetool.model.ITextPosition;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import net.vheerden.archi.mcp.response.dto.EmbeddedViewDto;
import net.vheerden.archi.mcp.response.dto.ViewGroupDto;
import net.vheerden.archi.mcp.response.dto.ViewNoteDto;
import net.vheerden.archi.mcp.response.dto.BulkOperationFailure;
import net.vheerden.archi.mcp.response.dto.BulkOperationResult;
import net.vheerden.archi.mcp.response.dto.DeleteResultDto;
import net.vheerden.archi.mcp.response.dto.DuplicateCandidate;
import net.vheerden.archi.mcp.response.dto.ElementDto;
import net.vheerden.archi.mcp.response.dto.FolderDto;
import net.vheerden.archi.mcp.response.dto.FolderTreeDto;
import net.vheerden.archi.mcp.response.dto.LayoutWithinGroupResultDto;
import net.vheerden.archi.mcp.response.dto.ModelInfoDto;
import net.vheerden.archi.mcp.response.dto.RelationshipDto;
import net.vheerden.archi.mcp.response.dto.ViewConnectionDto;
import net.vheerden.archi.mcp.response.dto.ViewContentsDto;
import net.vheerden.archi.mcp.response.dto.ViewNodeDto;
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

    // ---- getModelInfo read-side parity tests ----

    @Test
    public void shouldReturnModelInfoDtoWithPurpose_whenGetModelInfo_AC11() {
        IArchimateModel model = createEmptyModel();
        model.setPurpose("Strategic enterprise architecture");
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        ModelInfoDto info = accessor.getModelInfo();
        assertEquals("Strategic enterprise architecture", info.purpose());
    }

    @Test
    public void shouldReturnModelInfoDtoWithProperties_whenGetModelInfo_AC11() {
        IArchimateModel model = createEmptyModel();
        IProperty p1 = IArchimateFactory.eINSTANCE.createProperty();
        p1.setKey("Author");
        p1.setValue("Jane Doe");
        model.getProperties().add(p1);
        IProperty p2 = IArchimateFactory.eINSTANCE.createProperty();
        p2.setKey("Tag");
        p2.setValue("draft");
        model.getProperties().add(p2);
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        ModelInfoDto info = accessor.getModelInfo();
        assertNotNull(info.properties());
        assertEquals("Jane Doe", info.properties().get("Author"));
        assertEquals("draft", info.properties().get("Tag"));
    }

    @Test
    public void shouldExtendModelInfoDto_byteIdenticalWhenLegacy_AC7() {
        // Empty-Archi-default model has null purpose and empty properties EList —
        // the build path normalises empty/empty-list → null so legacy callers
        // (and Jackson with @JsonInclude(NON_NULL)) see the same 8-field shape
        // they did before the purpose/properties extension.
        IArchimateModel model = createEmptyModel();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        ModelInfoDto info = accessor.getModelInfo();
        assertEquals("Empty Model", info.name());
        assertNull("legacy: purpose must be null on a default model", info.purpose());
        assertNull("legacy: properties must be null when EList is empty", info.properties());
        // Existing 8-field assertions continue to pass byte-identically:
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

    // ---- getViewContents v1.5 styling read-back ----

    /**
     * Shared NON_NULL-configured Jackson mapper, used by the omission assertions below
     * to confirm the wire payload omits styling fields when the EMF source is at Archi default.
     * Mirrors the production ResponseFormatter configuration at
     * {@code net.vheerden.archi.mcp.response.ResponseFormatter:40-41}.
     */
    private static final ObjectMapper C3_JSON_MAPPER =
            new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private static String c3SerializeJson(Object dto) {
        try {
            return C3_JSON_MAPPER.writeValueAsString(dto);
        } catch (Exception e) {
            throw new AssertionError("Jackson serialization failed: " + e.getMessage(), e);
        }
    }

    /** Omission: v1.5 styling fields surface on a styled element; defaults omitted. */
    @Test
    public void getViewContents_shouldSurfaceV15StylingFields_onElement() {
        IArchimateModel model = createTestModelWithViewContents();

        // Pull the styled view-object (first child = actorVisual fixture)
        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS)
                .getElements().get(0);
        IDiagramModelArchimateObject actorVisual =
                (IDiagramModelArchimateObject) view.getChildren().get(0);

        // Apply v1.5 styling surface directly on the EMF object — read path must surface these.
        actorVisual.setGradient(0);                                     // → "top-bottom"
        actorVisual.setLineAlpha(128);                                  // → 128 (non-default)
        actorVisual.setLineStyle(IDiagramModelObject.LINE_STYLE_DASHED);// → "dashed"
        actorVisual.setDeriveElementLineColor(false);                   // → Boolean.FALSE
        actorVisual.setFont("Segoe UI|12|1");                           // → name/size/style(bold)
        actorVisual.setTextAlignment(ITextAlignment.TEXT_ALIGNMENT_LEFT);   // → "left"
        actorVisual.setTextPosition(ITextPosition.TEXT_POSITION_CENTRE);    // → "centre"
        actorVisual.getFeatures().putString("labelExpression",
                "${name}\n[${property:Lifecycle}]", null);

        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        Optional<ViewContentsDto> result = accessor.getViewContents("view-001");

        assertTrue(result.isPresent());
        List<ViewNodeDto> vm = result.get().visualMetadata();
        assertEquals(2, vm.size());

        // Styled element (actorVisual) — index 0
        ViewNodeDto styled = vm.get(0);
        assertEquals("top-bottom", styled.gradient());
        assertEquals(Integer.valueOf(128), styled.outlineOpacity());
        assertEquals("dashed", styled.lineStyle());
        assertEquals(Boolean.FALSE, styled.deriveLineColor());
        assertEquals("Segoe UI", styled.fontName());
        assertEquals(Integer.valueOf(12), styled.fontSize());
        assertEquals("bold", styled.fontStyle());
        assertEquals("left", styled.textAlignment());
        assertEquals("centre", styled.verticalTextAlignment());
        assertEquals("${name}\n[${property:Lifecycle}]", styled.labelExpression());

        // Omission pin: the unstyled sibling (compVisual at index 1) must serialise
        // to JSON without any of the new styling field names — wire-cost stays at zero for defaults.
        ViewNodeDto unstyled = vm.get(1);
        assertNull(unstyled.gradient());
        assertNull(unstyled.outlineOpacity());
        assertNull(unstyled.lineStyle());
        assertNull(unstyled.deriveLineColor());
        assertNull(unstyled.fontName());
        assertNull(unstyled.labelExpression());
        String json = c3SerializeJson(unstyled);
        assertFalse("default-styled JSON must omit gradient: " + json,
                json.contains("\"gradient\""));
        assertFalse("default-styled JSON must omit labelExpression: " + json,
                json.contains("\"labelExpression\""));
        assertFalse("default-styled JSON must omit fontName: " + json,
                json.contains("\"fontName\""));
        assertFalse("default-styled JSON must omit lineStyle: " + json,
                json.contains("\"lineStyle\""));
        assertFalse("default-styled JSON must omit deriveLineColor: " + json,
                json.contains("\"deriveLineColor\""));
        assertFalse("default-styled JSON must omit outlineOpacity: " + json,
                json.contains("\"outlineOpacity\""));
    }

    /** Omission: v1.5 styling fields surface on a styled group; defaults omitted. */
    @Test
    public void getViewContents_shouldSurfaceV15StylingFields_onGroup() {
        IArchimateModel model = createTestModelWithViewContents();
        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS)
                .getElements().get(0);

        // Styled group
        IDiagramModelGroup styledGroup = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        styledGroup.setId("grp-styled-1");
        styledGroup.setName("Styled Group");
        styledGroup.setBounds(500, 100, 200, 200);
        styledGroup.setBorderType(IDiagramModelGroup.BORDER_RECTANGLE); // → "rectangular"
        styledGroup.setGradient(1);                                     // → "left-right"
        styledGroup.setLineAlpha(200);                                  // → 200
        styledGroup.setLineStyle(IDiagramModelObject.LINE_STYLE_DOTTED);// → "dotted"
        styledGroup.setDeriveElementLineColor(false);                   // → Boolean.FALSE
        styledGroup.setFont("Verdana|14|2");                            // → italic
        styledGroup.getFeatures().putString("labelExpression",
                "${name} (${property:Tier})", null);
        view.getChildren().add(styledGroup);

        // Default-styled group for omission pin
        IDiagramModelGroup defaultGroup = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        defaultGroup.setId("grp-default-1");
        defaultGroup.setName("Default Group");
        defaultGroup.setBounds(800, 100, 200, 200);
        view.getChildren().add(defaultGroup);

        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        Optional<ViewContentsDto> result = accessor.getViewContents("view-001");
        assertTrue(result.isPresent());
        List<ViewGroupDto> groups = result.get().groups();
        assertEquals(2, groups.size());

        ViewGroupDto styled = groups.stream()
                .filter(g -> "grp-styled-1".equals(g.viewObjectId()))
                .findFirst().orElseThrow();
        assertEquals("rectangular", styled.figureType());
        assertEquals("left-right", styled.gradient());
        assertEquals(Integer.valueOf(200), styled.outlineOpacity());
        assertEquals("dotted", styled.lineStyle());
        assertEquals(Boolean.FALSE, styled.deriveLineColor());
        assertEquals("Verdana", styled.fontName());
        assertEquals(Integer.valueOf(14), styled.fontSize());
        assertEquals("italic", styled.fontStyle());
        assertEquals("${name} (${property:Tier})", styled.labelExpression());

        // Omission
        ViewGroupDto unstyled = groups.stream()
                .filter(g -> "grp-default-1".equals(g.viewObjectId()))
                .findFirst().orElseThrow();
        assertNull(unstyled.figureType());
        assertNull(unstyled.gradient());
        assertNull(unstyled.labelExpression());
        assertNull(unstyled.fontName());
        String json = c3SerializeJson(unstyled);
        assertFalse("default-styled group JSON must omit gradient: " + json,
                json.contains("\"gradient\""));
        assertFalse("default-styled group JSON must omit labelExpression: " + json,
                json.contains("\"labelExpression\""));
        assertFalse("default-styled group JSON must omit lineStyle: " + json,
                json.contains("\"lineStyle\""));
        assertFalse("default-styled group JSON must omit figureType: " + json,
                json.contains("\"figureType\""));
        assertFalse("default-styled group JSON must omit fontName: " + json,
                json.contains("\"fontName\""));
    }

    /** Omission: v1.5 styling + note-only borderType surface; defaults omitted. */
    @Test
    public void getViewContents_shouldSurfaceV15StylingFields_onNote() {
        IArchimateModel model = createTestModelWithViewContents();
        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS)
                .getElements().get(0);

        // Styled note
        IDiagramModelNote styledNote = IArchimateFactory.eINSTANCE.createDiagramModelNote();
        styledNote.setId("note-styled-1");
        styledNote.setContent("Author note");
        styledNote.setBounds(500, 400, 200, 80);
        styledNote.setBorderType(IDiagramModelNote.BORDER_RECTANGLE); // → "rectangle"
        styledNote.setGradient(3);                                    // → "bottom-top"
        styledNote.setLineAlpha(64);                                  // → 64
        styledNote.setLineStyle(IDiagramModelObject.LINE_STYLE_DASHED); // → "dashed"
        styledNote.setFont("Tahoma|10|1");                            // → name/size/style(bold)
        styledNote.getFeatures().putString("labelExpression",
                "${property:Author}", null);
        view.getChildren().add(styledNote);

        // Default-styled note for omission pin
        IDiagramModelNote defaultNote = IArchimateFactory.eINSTANCE.createDiagramModelNote();
        defaultNote.setId("note-default-1");
        defaultNote.setContent("Plain note");
        defaultNote.setBounds(800, 400, 200, 80);
        view.getChildren().add(defaultNote);

        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        Optional<ViewContentsDto> result = accessor.getViewContents("view-001");
        assertTrue(result.isPresent());
        List<ViewNoteDto> notes = result.get().notes();
        assertEquals(2, notes.size());

        ViewNoteDto styled = notes.stream()
                .filter(n -> "note-styled-1".equals(n.viewObjectId()))
                .findFirst().orElseThrow();
        assertEquals("rectangle", styled.borderType());
        assertEquals("bottom-top", styled.gradient());
        assertEquals(Integer.valueOf(64), styled.outlineOpacity());
        assertEquals("dashed", styled.lineStyle());
        assertEquals("Tahoma", styled.fontName());
        assertEquals(Integer.valueOf(10), styled.fontSize());
        assertEquals("bold", styled.fontStyle());
        assertEquals("${property:Author}", styled.labelExpression());

        // Omission
        ViewNoteDto unstyled = notes.stream()
                .filter(n -> "note-default-1".equals(n.viewObjectId()))
                .findFirst().orElseThrow();
        assertNull(unstyled.borderType());
        assertNull(unstyled.gradient());
        assertNull(unstyled.labelExpression());
        assertNull(unstyled.fontName());
        String json = c3SerializeJson(unstyled);
        assertFalse("default-styled note JSON must omit borderType: " + json,
                json.contains("\"borderType\""));
        assertFalse("default-styled note JSON must omit gradient: " + json,
                json.contains("\"gradient\""));
        assertFalse("default-styled note JSON must omit labelExpression: " + json,
                json.contains("\"labelExpression\""));
        assertFalse("default-styled note JSON must omit lineStyle: " + json,
                json.contains("\"lineStyle\""));
        assertFalse("default-styled note JSON must omit fontName: " + json,
                json.contains("\"fontName\""));
    }

    /** Omission: connection typography + labelExpression surface; defaults omitted. */
    @Test
    public void getViewContents_shouldSurfaceV15StylingFields_onConnection() {
        IArchimateModel model = createTestModelWithViewContents();
        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS)
                .getElements().get(0);

        // The fixture connection (created in createTestModelWithViewContents but not attached
        // to the view's children list). Style it explicitly and attach it now.
        IDiagramModelArchimateObject actorVisual =
                (IDiagramModelArchimateObject) view.getChildren().get(0);
        IDiagramModelArchimateObject compVisual =
                (IDiagramModelArchimateObject) view.getChildren().get(1);

        // Styled connection
        IDiagramModelArchimateConnection styledConn = IArchimateFactory.eINSTANCE
                .createDiagramModelArchimateConnection();
        styledConn.setId("conn-styled-1");
        styledConn.setArchimateRelationship((IArchimateRelationship) model
                .getFolder(FolderType.RELATIONS).getElements().get(0));
        styledConn.setFont("Verdana|11|2");                 // → italic
        styledConn.getFeatures().putString("labelExpression",
                "${name} via ${property:Channel}", null);
        styledConn.connect(compVisual, actorVisual);

        // Default-styled second connection for omission pin (reuses same relationship —
        // de-dup is on relationship-id, but each visual connection carries its own viewConnectionId)
        IDiagramModelArchimateConnection defaultConn = IArchimateFactory.eINSTANCE
                .createDiagramModelArchimateConnection();
        defaultConn.setId("conn-default-1");
        defaultConn.setArchimateRelationship((IArchimateRelationship) model
                .getFolder(FolderType.RELATIONS).getElements().get(0));
        defaultConn.connect(compVisual, actorVisual);

        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        Optional<ViewContentsDto> result = accessor.getViewContents("view-001");
        assertTrue(result.isPresent());
        List<ViewConnectionDto> conns = result.get().connections();
        // Fixture creates 1 latent unstyled connection (createTestModelWithViewContents) +
        // 2 added here → at least 2 from this test; >= 2 keeps the assertion robust to
        // future fixture changes.
        assertTrue("expected at least 2 connections, found " + conns.size(),
                conns.size() >= 2);

        ViewConnectionDto styled = conns.stream()
                .filter(c -> "conn-styled-1".equals(c.viewConnectionId()))
                .findFirst().orElseThrow();
        assertEquals("Verdana", styled.fontName());
        assertEquals(Integer.valueOf(11), styled.fontSize());
        assertEquals("italic", styled.fontStyle());
        assertEquals("${name} via ${property:Channel}", styled.labelExpression());

        // Omission
        ViewConnectionDto unstyled = conns.stream()
                .filter(c -> "conn-default-1".equals(c.viewConnectionId()))
                .findFirst().orElseThrow();
        assertNull(unstyled.fontName());
        assertNull(unstyled.fontSize());
        assertNull(unstyled.fontStyle());
        assertNull(unstyled.labelExpression());
        String json = c3SerializeJson(unstyled);
        assertFalse("default-styled connection JSON must omit fontName: " + json,
                json.contains("\"fontName\""));
        assertFalse("default-styled connection JSON must omit fontSize: " + json,
                json.contains("\"fontSize\""));
        assertFalse("default-styled connection JSON must omit fontStyle: " + json,
                json.contains("\"fontStyle\""));
        assertFalse("default-styled connection JSON must omit labelExpression: " + json,
                json.contains("\"labelExpression\""));
    }

    /**
     * Omission (runs on every platform): a connection with no Label Offset surfaces
     * {@code relativePosition == null} and the field is omitted from JSON — byte-identical wire
     * whether the platform lacks the feature (older Archi) or the anchor is the un-offset default.
     */
    @Test
    public void getViewContents_shouldOmitRelativePosition_whenUnset() {
        IArchimateModel model = createTestModelWithViewContents();
        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS)
                .getElements().get(0);
        IDiagramModelArchimateObject actorVisual =
                (IDiagramModelArchimateObject) view.getChildren().get(0);
        IDiagramModelArchimateObject compVisual =
                (IDiagramModelArchimateObject) view.getChildren().get(1);

        IDiagramModelArchimateConnection conn = IArchimateFactory.eINSTANCE
                .createDiagramModelArchimateConnection();
        conn.setId("conn-nooffset-1");
        conn.setArchimateRelationship((IArchimateRelationship) model
                .getFolder(FolderType.RELATIONS).getElements().get(0));
        // connect() registers the connection in compVisual.getSourceConnections()
        // (which collectConnections iterates) — no view.getChildren().add() needed.
        conn.connect(compVisual, actorVisual);

        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        Optional<ViewContentsDto> result = accessor.getViewContents("view-001");
        assertTrue(result.isPresent());
        ViewConnectionDto dto = result.get().connections().stream()
                .filter(c -> "conn-nooffset-1".equals(c.viewConnectionId()))
                .findFirst().orElseThrow();
        assertNull("un-offset connection must read relativePosition as null", dto.relativePosition());
        String json = c3SerializeJson(dto);
        assertFalse("un-offset connection JSON must omit relativePosition: " + json,
                json.contains("\"relativePosition\""));
    }

    /**
     * Round-trip (feature-gated): with the Label Offset feature present, an anchor set on a connection
     * surfaces through {@code get-view-contents} as its exact bitmask. Assumption-skipped on an older
     * target. This is the programmatic verification channel the offset otherwise lacks (neither
     * {@code assess-layout} nor {@code export-view} reflect it).
     */
    @Test
    public void getViewContents_shouldSurfaceRelativePosition_whenOffsetSet() {
        IArchimateModel model = createTestModelWithViewContents();
        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS)
                .getElements().get(0);
        IDiagramModelArchimateObject actorVisual =
                (IDiagramModelArchimateObject) view.getChildren().get(0);
        IDiagramModelArchimateObject compVisual =
                (IDiagramModelArchimateObject) view.getChildren().get(1);

        IDiagramModelArchimateConnection conn = IArchimateFactory.eINSTANCE
                .createDiagramModelArchimateConnection();
        Assume.assumeTrue("Label-offset feature is only present on newer platforms",
                RelativePositionFeature.isSupported(conn));

        final int south = 4;
        conn.setId("conn-offset-1");
        conn.setArchimateRelationship((IArchimateRelationship) model
                .getFolder(FolderType.RELATIONS).getElements().get(0));
        // connect() registers the connection in compVisual.getSourceConnections()
        // (which collectConnections iterates) — no view.getChildren().add() needed.
        conn.connect(compVisual, actorVisual);
        RelativePositionFeature.set(conn, south);

        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        Optional<ViewContentsDto> result = accessor.getViewContents("view-001");
        assertTrue(result.isPresent());
        ViewConnectionDto dto = result.get().connections().stream()
                .filter(c -> "conn-offset-1".equals(c.viewConnectionId()))
                .findFirst().orElseThrow();
        assertEquals("offset connection must surface its anchor bitmask",
                Integer.valueOf(south), dto.relativePosition());
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

    // ---- createElement tests ----

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
                "Some documentation", Map.of("status", "active"), null, null);

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

    // ---- createRelationship tests ----

    @Test
    public void shouldCreateRelationship_withValidTypes() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            // ServingRelationship from ApplicationComponent to BusinessProcess (valid)
            MutationResult<RelationshipDto> result = accessor.createRelationship(
                    "default", "ServingRelationship", "ac-001", "bp-001", "serves", null);

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
                    "nonexistent", "bp-001", null, null);
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
                    "ac-001", "nonexistent", null, null);
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
                    "ba-001", "ac-001", null, null);
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
                    "ac-001", "bp-001", null, null);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_RELATIONSHIP_TYPE, e.getErrorCode());
        }
    }

    @Test(expected = NoModelLoadedException.class)
    public void shouldThrowNoModelLoaded_whenCreateRelationshipWithNoModel() {
        stubModelManager.setModels(Collections.emptyList());
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        accessor.createRelationship("default", "ServingRelationship", "s1", "t1", null, null);
    }

    // ---- duplicate relationship prevention tests ----

    @Test
    public void shouldReturnExistingRelationship_whenDuplicateCreated() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            // The test model already has a ServingRelationship from ac-001 to bp-001 (rel-001)
            MutationResult<RelationshipDto> result = accessor.createRelationship(
                    "default", "ServingRelationship", "ac-001", "bp-001", null, null);

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
                    "default", "ServingRelationship", "ac-001", "bp-001", "different name", null);

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
                    "default", "AssociationRelationship", "ac-001", "bp-001", null, null);

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
                    "default", "ServingRelationship", "ac-001", "ba-001", null, null);

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
        // Within-batch dedup no longer works (connect() deferred to command execution).
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
            // both create independently (no within-batch dedup)
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
    public void shouldFanOutSetViewLabelExpression_throughBulkDispatch() {
        // Covers the ArchiModelAccessorImpl wiring (prepareOperation case + buildOperationResult
        // arm) that the command-level test cannot reach: one op fans out to every named element
        // on the view and the per-op result carries the view name + applied/skipped counts.
        // The fixture view "view-001" holds two named element objects (User, Web App); the latent
        // connection is not a child, so appliedCount=2 / skippedCount=0.
        IArchimateModel model = createTestModelWithViewContents();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            List<BulkOperation> operations = List.of(
                    new BulkOperation("set-view-label-expression", Map.of(
                            "viewId", "view-001",
                            "labelExpression", "${name} ${property:evidenceMark}")));

            BulkMutationResult result = accessor.executeBulk("default", operations, null, false);

            assertNotNull(result);
            assertEquals(1, result.operations().size());
            BulkOperationResult op = result.operations().get(0);
            assertEquals("set-view-label-expression", op.tool());
            assertEquals("updated", op.action());
            assertEquals("view-001", op.entityId());
            assertEquals("DiagramModel", op.entityType());
            assertEquals("Main View", op.entityName());
            assertEquals(Integer.valueOf(2), op.appliedCount());
            assertEquals(Integer.valueOf(0), op.skippedCount());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assume.assumeTrue("Requires OSGi runtime", false);
        }
    }

    @Test
    public void shouldCreateIndependentBackReferences_forDuplicateRelationshipsInBatch() {
        // Within-batch dedup no longer works. Each relationship gets its own ID.
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
            // both have distinct entity IDs (no within-batch dedup)
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
        // Within-batch dedup no longer works — both report "created"
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
            // both report "created" (no within-batch dedup)
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
            accessor.getMutationDispatcher().setApprovalModeProvider(() -> true);

            // The test model already has a ServingRelationship from ac-001 to bp-001 (rel-001)
            MutationResult<RelationshipDto> result = accessor.createRelationship(
                    "default", "ServingRelationship", "ac-001", "bp-001", null, null);

            assertNotNull(result);
            assertEquals("rel-001", result.entity().id());
            assertTrue("Should flag as already existed", result.entity().alreadyExisted());
            // Dedup should short-circuit BEFORE approval gate — no proposal context
            assertNull("Dedup should bypass approval gate", result.proposalContext());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assume.assumeTrue("Requires OSGi runtime for RelationshipsMatrix", false);
        }
    }

    // ---- createView tests ----

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

    // ---- updateElement tests ----

    @Test
    public void shouldUpdateElementName_whenNameProvided() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ElementDto> result = accessor.updateElement(
                "default", "ba-001", "New Customer Name", null, null, null);

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
                "default", "ba-001", null, "Updated documentation", null, null);

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
                "default", "ba-001", null, null, Map.of("status", "active"), null);

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
            accessor.updateElement("default", "nonexistent-id", "Name", null, null, null);
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
            accessor.updateElement("default", "ba-001", null, null, null, null);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
    }

    @Test(expected = NoModelLoadedException.class)
    public void shouldThrowNoModelLoaded_whenUpdateElementWithNoModel() {
        stubModelManager.setModels(Collections.emptyList());
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        accessor.updateElement("default", "some-id", "Name", null, null, null);
    }

    // ---- findDuplicates / findExactMatch tests ----

    @Test
    public void shouldFindDuplicates_whenSameNameExists() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        // Exact name match scores 1.0 — well above the 0.7 threshold
        List<DuplicateCandidate> duplicates = accessor.findDuplicates("BusinessActor", "Customer", null);

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
                "BusinessActor", "Completely Unrelated Name", null);

        assertTrue("Should return empty when no duplicates", duplicates.isEmpty());
    }

    @Test
    public void shouldFilterByType_whenFindingDuplicates() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        // "Customer" exists as BusinessActor, not ApplicationComponent
        List<DuplicateCandidate> duplicates = accessor.findDuplicates(
                "ApplicationComponent", "Customer", null);

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

    // ---- Specialization tests ----

    @Test
    public void shouldCreateElement_withSpecialization() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ElementDto> result = accessor.createElement(
                "default", "BusinessActor", "VIP Customer", null, null, null, "VIP");

        assertNotNull(result.entity());
        assertEquals("BusinessActor", result.entity().type());
        assertEquals("VIP Customer", result.entity().name());
        // Response DTO must reflect the assigned specialization (C3b H1 regression guard)
        assertEquals("VIP", result.entity().specialization());
        // Profile should be auto-created in the model
        assertEquals(1, model.getProfiles().size());
        assertEquals("VIP", model.getProfiles().get(0).getName());
        assertEquals("BusinessActor", model.getProfiles().get(0).getConceptType());
    }

    @Test
    public void shouldReuseExistingProfile_whenSpecializationAlreadyExists() {
        IArchimateModel model = createTestModel();
        // Pre-create a profile
        IProfile existingProfile = IArchimateFactory.eINSTANCE.createProfile();
        existingProfile.setName("VIP");
        existingProfile.setConceptType("BusinessActor");
        model.getProfiles().add(existingProfile);

        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        accessor.createElement("default", "BusinessActor", "Customer A", null, null, null, "VIP");
        accessor.createElement("default", "BusinessActor", "Customer B", null, null, null, "vip"); // case-insensitive

        // Should still be only 1 profile (no duplicates)
        assertEquals(1, model.getProfiles().size());
    }

    @Test
    public void shouldCreateSeparateProfiles_forDifferentConceptTypes() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        accessor.createElement("default", "BusinessActor", "Actor X", null, null, null, "Premium");
        accessor.createElement("default", "ApplicationComponent", "App X", null, null, null, "Premium");

        // Two profiles with same name but different conceptType
        assertEquals(2, model.getProfiles().size());
    }

    @Test
    public void shouldNotFindDuplicate_whenSameNameDifferentSpecialization() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Create a specialized "Customer" actor
        accessor.createElement("default", "BusinessActor", "Premium Customer", null, null, null, "VIP");

        // findDuplicates with different specialization should NOT find it
        List<DuplicateCandidate> duplicates = accessor.findDuplicates(
                "BusinessActor", "Premium Customer", "Standard");
        assertTrue("Different specialization → not duplicate", duplicates.isEmpty());

        // findDuplicates with null specialization should NOT find it (existing element has VIP profile)
        duplicates = accessor.findDuplicates("BusinessActor", "Premium Customer", null);
        assertTrue("Null vs VIP → not duplicate", duplicates.isEmpty());

        // findDuplicates with matching specialization SHOULD find it
        duplicates = accessor.findDuplicates("BusinessActor", "Premium Customer", "VIP");
        assertFalse("Matching specialization → IS duplicate", duplicates.isEmpty());
    }

    @Test
    public void shouldFindDuplicate_whenBothUnspecialized() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Existing "Customer" in test model has no specialization
        List<DuplicateCandidate> duplicates = accessor.findDuplicates(
                "BusinessActor", "Customer", null);

        assertFalse("Both unspecialized → IS duplicate", duplicates.isEmpty());
        assertEquals("ba-001", duplicates.get(0).id());
    }

    @Test
    public void shouldUpdateElementSpecialization_whenAssigning() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ElementDto> result =
                accessor.updateElement("default", "ba-001", null, null, null, "VIP");

        IBusinessActor ba = (IBusinessActor) com.archimatetool.model.util.ArchimateModelUtils
                .getObjectByID(model, "ba-001");
        assertEquals(1, ba.getProfiles().size());
        assertEquals("VIP", ba.getProfiles().get(0).getName());
        // Response DTO reflects post-mutation state — built after dispatch executes
        assertEquals("VIP", result.entity().specialization());
    }

    @Test
    public void shouldReplaceExistingProfiles_whenUpdatingSpecialization() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Assign first specialization
        accessor.updateElement("default", "ba-001", null, null, null, "VIP");
        // Replace with different specialization
        MutationResult<ElementDto> result =
                accessor.updateElement("default", "ba-001", null, null, null, "Internal");

        IBusinessActor ba = (IBusinessActor) com.archimatetool.model.util.ArchimateModelUtils
                .getObjectByID(model, "ba-001");
        assertEquals("Should have only one profile (replace semantics)",
                1, ba.getProfiles().size());
        assertEquals("Internal", ba.getProfiles().get(0).getName());
        assertEquals("Internal", result.entity().specialization());
    }

    @Test
    public void shouldClearAllProfiles_whenSpecializationIsEmptyString() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Assign specialization first
        accessor.updateElement("default", "ba-001", null, null, null, "VIP");
        // Clear with empty string
        accessor.updateElement("default", "ba-001", null, null, null, "");

        IBusinessActor ba = (IBusinessActor) com.archimatetool.model.util.ArchimateModelUtils
                .getObjectByID(model, "ba-001");
        assertTrue("Profiles should be cleared", ba.getProfiles().isEmpty());
    }

    @Test
    public void shouldNotChangeProfiles_whenSpecializationIsNull() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Assign specialization first
        accessor.updateElement("default", "ba-001", null, null, null, "VIP");
        // Update name only — specialization null means "leave unchanged"
        accessor.updateElement("default", "ba-001", "New Name", null, null, null);

        IBusinessActor ba = (IBusinessActor) com.archimatetool.model.util.ArchimateModelUtils
                .getObjectByID(model, "ba-001");
        assertEquals("Profile should still be present", 1, ba.getProfiles().size());
        assertEquals("VIP", ba.getProfiles().get(0).getName());
        assertEquals("New Name", ba.getName());
    }

    @Test
    public void shouldHandleSpecializationOnUpdateRelationship() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<RelationshipDto> result =
                accessor.updateRelationship("default", "rel-001", null, null, null, "Critical Path");

        IArchimateRelationship rel = (IArchimateRelationship) com.archimatetool.model.util
                .ArchimateModelUtils.getObjectByID(model, "rel-001");
        assertEquals(1, rel.getProfiles().size());
        assertEquals("Critical Path", rel.getProfiles().get(0).getName());
        assertEquals("Critical Path", result.entity().specialization());
    }

    @Test
    public void shouldHandleClearOnUnspecializedConcept_withoutFailure() {
        // C3b M2: clearing specialization on a concept that has no profiles and no
        // other field updates produced an empty compound command in the original
        // implementation, which CompoundCommand.canExecute() rejects.
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // ba-001 has no profiles initially. Clearing should be a clean no-op.
        MutationResult<ElementDto> result =
                accessor.updateElement("default", "ba-001", null, null, null, "");

        assertNotNull("Empty-compound clear must not throw", result);
        IBusinessActor ba = (IBusinessActor) com.archimatetool.model.util.ArchimateModelUtils
                .getObjectByID(model, "ba-001");
        assertTrue(ba.getProfiles().isEmpty());
    }

    @Test
    public void shouldCreateRelationship_withSpecialization_andEchoInDto() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            MutationResult<RelationshipDto> result = accessor.createRelationship(
                    "default", "AssociationRelationship", "ac-001", "ba-001", "assoc", "Critical");

            // Response DTO must reflect the assigned specialization (C3b H1 regression guard)
            assertEquals("Critical", result.entity().specialization());
            assertEquals(1, model.getProfiles().size());
            assertEquals("Critical", model.getProfiles().get(0).getName());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            // RelationshipsMatrix requires OSGi bundle — validated via E2E / Plug-in tests
            Assume.assumeTrue("Requires OSGi runtime for RelationshipsMatrix", false);
        }
    }

    @Test
    public void shouldNotTreatDifferentlySpecializedRelationshipsAsDuplicates() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            // Create a specialized association between ac-001 and ba-001
            MutationResult<RelationshipDto> first = accessor.createRelationship(
                    "default", "AssociationRelationship", "ac-001", "ba-001", "first", "Strong");
            assertFalse("First create should NOT be a duplicate",
                    first.entity().alreadyExisted());

            // Create the same (type, source, target) but with a different specialization —
            // C3b H2: should NOT be detected as duplicate
            MutationResult<RelationshipDto> second = accessor.createRelationship(
                    "default", "AssociationRelationship", "ac-001", "ba-001", "second", "Weak");
            assertFalse("Different specialization → not a duplicate",
                    second.entity().alreadyExisted());
            assertEquals("Weak", second.entity().specialization());

            // Re-creating with the SAME specialization SHOULD be detected as duplicate
            MutationResult<RelationshipDto> third = accessor.createRelationship(
                    "default", "AssociationRelationship", "ac-001", "ba-001", "third", "Strong");
            assertTrue("Matching specialization → IS duplicate",
                    third.entity().alreadyExisted());
            // Existing-relationship DTO must preserve the specialization (C3b H2)
            assertEquals("Strong", third.entity().specialization());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            // RelationshipsMatrix requires OSGi bundle — validated via E2E / Plug-in tests
            Assume.assumeTrue("Requires OSGi runtime for RelationshipsMatrix", false);
        }
    }

    // Note: Compound command undo behavior (reversing both profile add and element add)
    // is verified directly in ApplySpecializationCommandTest and ClearSpecializationCommandTest
    // at the command-unit level. Integration testing through accessor.undo(1) requires a real
    // CommandStack which the test dispatcher does not provide.

    // ---- Specialization profile management tests ----

    @Test
    public void shouldCreateSpecialization_whenNew() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<Map<String, Object>> result = accessor.createSpecialization(
                "default", "Cloud Server", "Node");

        assertNotNull(result.entity());
        assertEquals("Cloud Server", result.entity().get("name"));
        assertEquals("Node", result.entity().get("conceptType"));
        assertEquals(Boolean.TRUE, result.entity().get("created"));
        assertEquals("Technology", result.entity().get("conceptTypeLayer"));
        assertEquals(1, model.getProfiles().size());
        assertEquals("Cloud Server", model.getProfiles().get(0).getName());
        assertEquals("Node", model.getProfiles().get(0).getConceptType());
    }

    @Test
    public void shouldReturnExistingProfile_whenSpecializationAlreadyExists() {
        IArchimateModel model = createTestModel();
        IProfile existing = IArchimateFactory.eINSTANCE.createProfile();
        existing.setName("Cloud Server");
        existing.setConceptType("Node");
        model.getProfiles().add(existing);
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // case-insensitive name lookup
        MutationResult<Map<String, Object>> result = accessor.createSpecialization(
                "default", "cloud server", "Node");

        assertEquals(Boolean.FALSE, result.entity().get("created"));
        assertEquals("Cloud Server", result.entity().get("name")); // canonical case preserved
        assertEquals(1, model.getProfiles().size()); // no duplicate
    }

    @Test
    public void shouldRejectInvalidConceptType_onCreateSpecialization() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            accessor.createSpecialization("default", "Foo", "NotAnArchimateType");
            org.junit.Assert.fail("Should have thrown ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(net.vheerden.archi.mcp.response.ErrorCode.INVALID_PARAMETER,
                    e.getErrorCode());
            assertTrue(e.getMessage().contains("NotAnArchimateType"));
        }
    }

    @Test
    public void shouldRejectAbstractConceptType_onCreateSpecialization() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            accessor.createSpecialization("default", "Foo", "ArchimateElement");
            org.junit.Assert.fail("Should have thrown ModelAccessException for abstract type");
        } catch (ModelAccessException e) {
            assertEquals(net.vheerden.archi.mcp.response.ErrorCode.INVALID_PARAMETER,
                    e.getErrorCode());
            assertTrue("Error should mention abstract", e.getMessage().contains("abstract"));
        }
    }

    @Test
    public void shouldRejectBlankName_onCreateSpecialization() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            accessor.createSpecialization("default", "  ", "Node");
            org.junit.Assert.fail("Should have thrown for blank name");
        } catch (ModelAccessException e) {
            assertEquals(net.vheerden.archi.mcp.response.ErrorCode.INVALID_PARAMETER,
                    e.getErrorCode());
        }
    }

    @Test
    public void shouldRenameSpecialization_whenUpdated() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);
        accessor.createSpecialization("default", "Old Name", "Node");

        MutationResult<Map<String, Object>> result = accessor.updateSpecialization(
                "default", "Old Name", "Node", "New Name");

        assertEquals("New Name", result.entity().get("name"));
        assertEquals(1, model.getProfiles().size());
        assertEquals("New Name", model.getProfiles().get(0).getName());
    }

    @Test
    public void shouldRejectRename_whenTargetNameAlreadyExists() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);
        accessor.createSpecialization("default", "First", "Node");
        accessor.createSpecialization("default", "Second", "Node");

        try {
            accessor.updateSpecialization("default", "First", "Node", "Second");
            org.junit.Assert.fail("Should refuse to merge into existing profile");
        } catch (ModelAccessException e) {
            assertEquals(net.vheerden.archi.mcp.response.ErrorCode.INVALID_PARAMETER,
                    e.getErrorCode());
            assertTrue(e.getMessage().contains("Second"));
        }
        // Both profiles should still exist
        assertEquals(2, model.getProfiles().size());
    }

    @Test
    public void shouldReturnNotFound_whenRenamingMissingSpecialization() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            accessor.updateSpecialization("default", "Ghost", "Node", "Anything");
            org.junit.Assert.fail("Should throw NOT_FOUND");
        } catch (ModelAccessException e) {
            assertEquals(net.vheerden.archi.mcp.response.ErrorCode.OBJECT_NOT_FOUND,
                    e.getErrorCode());
        }
    }

    @Test
    public void shouldDeleteUnusedSpecialization() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);
        accessor.createSpecialization("default", "Orphan", "Node");
        assertEquals(1, model.getProfiles().size());

        MutationResult<Map<String, Object>> result = accessor.deleteSpecialization(
                "default", "Orphan", "Node", false);

        assertEquals(Boolean.TRUE, result.entity().get("deleted"));
        assertEquals(0, ((Number) result.entity().get("clearedFromConcepts")).intValue());
        assertEquals(0, model.getProfiles().size());
    }

    @Test
    public void shouldRefuseDelete_whenSpecializationInUse() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);
        // Create an element with the specialization (auto-creates the profile)
        accessor.createElement("default", "BusinessActor", "VIP Customer",
                null, null, null, "VIP");
        assertEquals(1, model.getProfiles().size());

        try {
            accessor.deleteSpecialization("default", "VIP", "BusinessActor", false);
            org.junit.Assert.fail("Should refuse delete when in use");
        } catch (ModelAccessException e) {
            assertEquals(net.vheerden.archi.mcp.response.ErrorCode.INVALID_PARAMETER,
                    e.getErrorCode());
            assertTrue("Error should mention usage count",
                    e.getMessage().contains("1 usage"));
        }
        assertEquals("Profile must still exist", 1, model.getProfiles().size());
    }

    @Test
    public void shouldForceDeleteSpecialization_andClearAllReferences() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);
        accessor.createElement("default", "BusinessActor", "Customer A",
                null, null, null, "VIP");
        accessor.createElement("default", "BusinessActor", "Customer B",
                null, null, null, "VIP");

        MutationResult<Map<String, Object>> result = accessor.deleteSpecialization(
                "default", "VIP", "BusinessActor", true);

        assertEquals(Boolean.TRUE, result.entity().get("deleted"));
        assertEquals(2, ((Number) result.entity().get("clearedFromConcepts")).intValue());
        assertEquals("Profile should be removed from model", 0, model.getProfiles().size());

        // Both elements should have their profile reference cleared
        for (IArchimateElement el : extractAllElements(model)) {
            if ("Customer A".equals(el.getName()) || "Customer B".equals(el.getName())) {
                assertTrue("Element profile reference should be cleared",
                        el.getProfiles().isEmpty());
            }
        }
    }

    @Test
    public void shouldRefuseForceDelete_whenAnyConceptHasMultipleProfiles() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Create an element with two profiles directly via the model
        accessor.createElement("default", "BusinessActor", "Multi Customer",
                null, null, null, "VIP");
        // Manually attach a second profile to simulate the multi-profile case
        IBusinessActor multi = null;
        for (IArchimateElement el : extractAllElements(model)) {
            if ("Multi Customer".equals(el.getName())) {
                multi = (IBusinessActor) el;
                break;
            }
        }
        assertNotNull(multi);
        IProfile second = IArchimateFactory.eINSTANCE.createProfile();
        second.setName("Premium");
        second.setConceptType("BusinessActor");
        model.getProfiles().add(second);
        multi.getProfiles().add(second);
        assertEquals(2, multi.getProfiles().size());

        try {
            accessor.deleteSpecialization("default", "VIP", "BusinessActor", true);
            org.junit.Assert.fail("Should refuse force-delete when concept has multiple profiles");
        } catch (ModelAccessException e) {
            assertEquals(net.vheerden.archi.mcp.response.ErrorCode.INVALID_PARAMETER,
                    e.getErrorCode());
            assertTrue("Error should name the offending concept",
                    e.getMessage().contains("Multi Customer"));
        }
        // Both profiles should still exist; concept untouched
        assertEquals(2, model.getProfiles().size());
        assertEquals(2, multi.getProfiles().size());
    }

    @Test
    public void shouldReturnUsage_withElementAndRelationshipBuckets() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);
        accessor.createElement("default", "BusinessActor", "Customer A",
                null, null, null, "VIP");
        accessor.createElement("default", "BusinessActor", "Customer B",
                null, null, null, "VIP");

        Map<String, Object> usage = accessor.getSpecializationUsage("VIP", "BusinessActor");

        assertEquals("VIP", usage.get("name"));
        assertEquals("BusinessActor", usage.get("conceptType"));
        assertEquals("Business", usage.get("conceptTypeLayer"));
        assertEquals(2, ((Number) usage.get("totalUsageCount")).intValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> elements = (List<Map<String, Object>>) usage.get("elements");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> relationships = (List<Map<String, Object>>) usage.get("relationships");
        assertEquals(2, elements.size());
        assertEquals(0, relationships.size());
        for (Map<String, Object> entry : elements) {
            assertEquals("BusinessActor", entry.get("type"));
            assertNotNull(entry.get("id"));
            assertNotNull(entry.get("name"));
        }
    }

    @Test
    public void shouldReturnNotFound_forUsageOfMissingSpecialization() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            accessor.getSpecializationUsage("Ghost", "Node");
            org.junit.Assert.fail("Should throw NOT_FOUND");
        } catch (ModelAccessException e) {
            assertEquals(net.vheerden.archi.mcp.response.ErrorCode.OBJECT_NOT_FOUND,
                    e.getErrorCode());
        }
    }

    @Test
    public void shouldReflectSpecializationCount_inModelInfo_afterCreateAndDelete() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        int initial = accessor.getModelInfo().specializationCount();

        accessor.createSpecialization("default", "Cloud Server", "Node");
        assertEquals(initial + 1, accessor.getModelInfo().specializationCount());

        accessor.deleteSpecialization("default", "Cloud Server", "Node", false);
        assertEquals(initial, accessor.getModelInfo().specializationCount());
    }

    // ---- C3c bulk-mutate dispatch coverage (Review-fix H1) ----

    @Test
    public void shouldExecuteBulk_withSpecializationLifecycle() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // create-spec → create-element-with-inline-spec → update-spec → delete-spec(force)
        // Exercises every new switch branch in prepareOperation() plus the
        // force-flag fix (force passed as a real Boolean — happy path for the bulk dispatcher).
        List<BulkOperation> ops = List.of(
                new BulkOperation("create-specialization",
                        Map.of("name", "Cloud Server", "conceptType", "Node")),
                new BulkOperation("create-element",
                        Map.of("type", "Node", "name", "ec2-prod-01",
                                "specialization", "Cloud Server")),
                new BulkOperation("update-specialization",
                        Map.of("name", "Cloud Server", "conceptType", "Node",
                                "newName", "Cloud VM")),
                new BulkOperation("delete-specialization",
                        Map.of("name", "Cloud VM", "conceptType", "Node",
                                "force", true))
        );

        BulkMutationResult result = accessor.executeBulk("default", ops, null, false);

        assertNotNull(result);
        assertTrue("All four operations should succeed", result.allSucceeded());
        assertEquals(4, result.totalOperations());

        // Op 0: create-specialization → "created" + Specialization:Node
        assertEquals("create-specialization", result.operations().get(0).tool());
        assertEquals("created", result.operations().get(0).action());
        assertEquals("Specialization:Node", result.operations().get(0).entityType());
        assertEquals("Cloud Server", result.operations().get(0).entityName());

        // Op 1: create-element with inline specialization
        assertEquals("create-element", result.operations().get(1).tool());
        assertEquals("Node", result.operations().get(1).entityType());

        // Op 2: update-specialization → "updated" action
        assertEquals("update-specialization", result.operations().get(2).tool());
        assertEquals("updated", result.operations().get(2).action());
        assertEquals("Specialization:Node", result.operations().get(2).entityType());
        // Entity name reflects the new name (DTO is built post-rename)
        assertEquals("Cloud VM", result.operations().get(2).entityName());

        // Op 3: delete-specialization with force=true → "deleted" action
        assertEquals("delete-specialization", result.operations().get(3).tool());
        assertEquals("deleted", result.operations().get(3).action());

        // Profile catalog should be empty after the cascade delete
        assertEquals("Profile should be removed from model", 0, model.getProfiles().size());
    }

    // ---- Bulk specialization deduplication (discovered E2E 2026-04-09) ----
    //
    // Bug: bulk-mutate runs all prepare methods first (building commands) before
    // dispatching any. Without per-batch profile caching, the second and later
    // prepares in a batch that all reference the same NEW specialization each call
    // getProfileByNameAndType() — which still returns null because the first
    // prepare's command hasn't executed yet — and create their own duplicate
    // IProfile instances. The result is N shadow profiles in model.getProfiles()
    // where one was intended, breaking list-specializations, update-specialization,
    // delete-specialization, and get-specialization-usage.
    //
    // Fix: ArchiModelAccessorImpl.bulkProfileCache (ThreadLocal<Map>) — populated
    // by resolveOrCreateProfile during prepare, scoped to executeBulk call.

    @Test
    public void shouldDeduplicate_whenBulkCreateSharesNewSpecialization() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Create 5 elements all referencing the SAME new specialization in one bulk batch.
        List<BulkOperation> ops = List.of(
                new BulkOperation("create-element",
                        Map.of("type", "Node", "name", "ec2-prod-01", "specialization", "Cloud Server")),
                new BulkOperation("create-element",
                        Map.of("type", "Node", "name", "ec2-prod-02", "specialization", "Cloud Server")),
                new BulkOperation("create-element",
                        Map.of("type", "Node", "name", "ec2-prod-03", "specialization", "Cloud Server")),
                new BulkOperation("create-element",
                        Map.of("type", "Node", "name", "ec2-prod-04", "specialization", "Cloud Server")),
                new BulkOperation("create-element",
                        Map.of("type", "Node", "name", "ec2-prod-05", "specialization", "Cloud Server"))
        );

        BulkMutationResult result = accessor.executeBulk("default", ops, null, false);

        assertTrue("All five operations should succeed", result.allSucceeded());
        // Pre-fix: model.getProfiles().size() == 5 (one shadow per element).
        // Post-fix: exactly one Profile shared across all five elements.
        assertEquals("Bulk batch must produce exactly one IProfile for one new specialization",
                1, model.getProfiles().size());
        IProfile profile = model.getProfiles().get(0);
        assertEquals("Cloud Server", profile.getName());
        assertEquals("Node", profile.getConceptType());
        // Usage count from the model's perspective — every element holds the SAME profile reference
        assertEquals("All five elements should share the single profile",
                5, com.archimatetool.model.util.ArchimateModelUtils.findProfileUsage(profile).size());
    }

    @Test
    public void shouldDeduplicate_perKey_whenBulkCreateMixesTwoNewSpecializations() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // 3 elements with "Cloud Server", 3 with "Edge Device" — distinct keys must each
        // collapse to one profile, not six.
        List<BulkOperation> ops = List.of(
                new BulkOperation("create-element",
                        Map.of("type", "Node", "name", "ec2-01", "specialization", "Cloud Server")),
                new BulkOperation("create-element",
                        Map.of("type", "Node", "name", "ec2-02", "specialization", "Cloud Server")),
                new BulkOperation("create-element",
                        Map.of("type", "Device", "name", "iot-gw-01", "specialization", "Edge Device")),
                new BulkOperation("create-element",
                        Map.of("type", "Node", "name", "ec2-03", "specialization", "Cloud Server")),
                new BulkOperation("create-element",
                        Map.of("type", "Device", "name", "iot-gw-02", "specialization", "Edge Device")),
                new BulkOperation("create-element",
                        Map.of("type", "Device", "name", "iot-gw-03", "specialization", "Edge Device"))
        );

        BulkMutationResult result = accessor.executeBulk("default", ops, null, false);

        assertTrue("All six operations should succeed", result.allSucceeded());
        assertEquals("Two distinct specialization keys → exactly two profiles",
                2, model.getProfiles().size());
    }

    @Test
    public void shouldReuseExistingProfile_whenBulkCreateReferencesPreExistingSpecialization() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Pre-create the specialization (single-call path) so it lives in the model.
        accessor.createElement("default", "Node", "seed-node",
                null, null, null, "Cloud Server");
        assertEquals(1, model.getProfiles().size());

        // Now bulk-create 5 more elements referencing the SAME existing specialization.
        List<BulkOperation> ops = List.of(
                new BulkOperation("create-element",
                        Map.of("type", "Node", "name", "ec2-a", "specialization", "Cloud Server")),
                new BulkOperation("create-element",
                        Map.of("type", "Node", "name", "ec2-b", "specialization", "Cloud Server")),
                new BulkOperation("create-element",
                        Map.of("type", "Node", "name", "ec2-c", "specialization", "Cloud Server")),
                new BulkOperation("create-element",
                        Map.of("type", "Node", "name", "ec2-d", "specialization", "Cloud Server")),
                new BulkOperation("create-element",
                        Map.of("type", "Node", "name", "ec2-e", "specialization", "Cloud Server"))
        );

        BulkMutationResult result = accessor.executeBulk("default", ops, null, false);

        assertTrue("All five operations should succeed", result.allSucceeded());
        // Still exactly one profile — no shadows created during the bulk batch.
        assertEquals("Bulk batch must reuse the pre-existing profile, not shadow it",
                1, model.getProfiles().size());
        IProfile profile = model.getProfiles().get(0);
        assertEquals("Cloud Server", profile.getName());
        assertEquals("All six elements (1 seed + 5 bulk) should share the profile",
                6, com.archimatetool.model.util.ArchimateModelUtils.findProfileUsage(profile).size());
    }

    @Test
    public void shouldDeduplicate_whenBulkCreateRelationshipsShareNewSpecialization() {
        // Pre-flight: this test exercises create-relationship which calls
        // ArchimateModelUtils.isValidRelationship → triggers RelationshipsMatrix.<clinit>.
        // In plain-JUnit mode (no OSGi), RelationshipsMatrix.loadKeyLetters() NPEs because
        // it expects bundle-loaded resources. The same issue affects pre-existing tests
        // shouldCreateRelationship_withSpecialization_andEchoInDto and
        // shouldNotTreatDifferentlySpecializedRelationshipsAsDuplicates. Skip gracefully
        // when this constraint applies — the test runs cleanly via the PDE Plug-in Test
        // launcher. The element-based dedup tests above already prove the cache mechanism;
        // resolveOrCreateProfile is shared between prepareCreateElement and
        // prepareCreateRelationship, so element-coverage transitively covers the
        // relationship path's cache interaction.
        try {
            IArchimateModel model = createTestModel();
            stubModelManager.setModels(List.of(model));
            accessor = createAccessorWithTestDispatcher(model);

            // Pre-create endpoints (single-call path) so source/target IDs exist.
            ElementDto a = accessor.createElement("default", "BusinessActor", "Source A",
                    null, null, null, null).entity();
            ElementDto b = accessor.createElement("default", "BusinessActor", "Target B",
                    null, null, null, null).entity();
            ElementDto c = accessor.createElement("default", "BusinessActor", "Target C",
                    null, null, null, null).entity();
            ElementDto d = accessor.createElement("default", "BusinessActor", "Target D",
                    null, null, null, null).entity();
            // No profiles yet — only elements
            assertEquals(0, model.getProfiles().size());

            // Bulk-create 3 AssociationRelationships all sharing the SAME new specialization.
            List<BulkOperation> ops = List.of(
                    new BulkOperation("create-relationship",
                            Map.of("type", "AssociationRelationship", "sourceId", a.id(), "targetId", b.id(),
                                    "specialization", "Critical Path")),
                    new BulkOperation("create-relationship",
                            Map.of("type", "AssociationRelationship", "sourceId", a.id(), "targetId", c.id(),
                                    "specialization", "Critical Path")),
                    new BulkOperation("create-relationship",
                            Map.of("type", "AssociationRelationship", "sourceId", a.id(), "targetId", d.id(),
                                    "specialization", "Critical Path"))
            );

            BulkMutationResult result = accessor.executeBulk("default", ops, null, false);

            assertTrue("All three operations should succeed", result.allSucceeded());
            assertEquals("Bulk relationship batch must produce exactly one IProfile",
                    1, model.getProfiles().size());
            IProfile profile = model.getProfiles().get(0);
            assertEquals("Critical Path", profile.getName());
            assertEquals("AssociationRelationship", profile.getConceptType());
            assertEquals("All three relationships should share the single profile",
                    3, com.archimatetool.model.util.ArchimateModelUtils.findProfileUsage(profile).size());
        } catch (NoClassDefFoundError | ExceptionInInitializerError e) {
            // Pre-existing infrastructure issue — see method-level comment.
            org.junit.Assume.assumeNoException(
                    "Skipped: RelationshipsMatrix requires OSGi runtime (run via PDE Plug-in Test)", e);
        }
    }

    @Test
    public void shouldNotShareProfilesAcrossSeparateBulkBatches() {
        // Regression guard: the ThreadLocal cache must be cleared between executeBulk
        // calls. Two consecutive bulk batches each creating a single element with the
        // same specialization name should converge on one shared profile via the
        // model lookup path (getProfileByNameAndType), not via stale cache leakage.
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        BulkMutationResult firstBatch = accessor.executeBulk("default", List.of(
                new BulkOperation("create-element",
                        Map.of("type", "Node", "name", "n1", "specialization", "Cloud Server"))
        ), null, false);
        assertTrue(firstBatch.allSucceeded());
        assertEquals(1, model.getProfiles().size());

        BulkMutationResult secondBatch = accessor.executeBulk("default", List.of(
                new BulkOperation("create-element",
                        Map.of("type", "Node", "name", "n2", "specialization", "Cloud Server"))
        ), null, false);
        assertTrue(secondBatch.allSucceeded());

        // Still one profile — second batch found it via model lookup, not shadowed it.
        assertEquals("Second batch must reuse profile from first batch via model lookup",
                1, model.getProfiles().size());
        assertEquals(2, com.archimatetool.model.util.ArchimateModelUtils.findProfileUsage(model.getProfiles().get(0)).size());
    }

    @Test
    public void shouldAcceptBulk_deleteSpecialization_withStringForceParam() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Pre-create a specialization that has a usage so that force=true is required.
        accessor.createElement("default", "BusinessActor", "Customer A",
                null, null, null, "VIP");
        assertEquals(1, model.getProfiles().size());

        // Pass force as the *string* "true" — JSON-coerced clients commonly do this.
        // Pre-fix this fell through to the refuse-on-use path because the bulk dispatch
        // used Boolean.TRUE.equals(params.get("force")) which only matches a real Boolean.
        List<BulkOperation> ops = List.of(
                new BulkOperation("delete-specialization",
                        Map.of("name", "VIP", "conceptType", "BusinessActor",
                                "force", "true"))
        );

        BulkMutationResult result = accessor.executeBulk("default", ops, null, false);

        assertTrue("Force=\"true\" string must be honoured by bulk dispatch",
                result.allSucceeded());
        assertEquals("deleted", result.operations().get(0).action());
        assertEquals(0, model.getProfiles().size());
    }

    // ---- server-owned effect text + structured bulk ops + batch intent ----
    // (OSGi-gated: relationship validation needs RelationshipsMatrix; run on the --swt/PDE lane.)

    @Test
    public void shouldNameEndpoints_inCreateRelationshipEffectDescription() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);
        accessor.getMutationDispatcher().setApprovalModeProvider(() -> true);

        try {
            // Customer (ba-001) → Order System (ac-001): no existing relationship, so not a dup.
            MutationResult<RelationshipDto> result = accessor.createRelationship(
                    "default", "ServingRelationship", "ba-001", "ac-001", null, null);

            assertNotNull("Approval mode should produce a proposal", result.proposalContext());
            PendingProposal pending = accessor.getMutationDispatcher().getProposal(
                    "default", result.proposalContext().proposalId());
            assertNotNull(pending);
            assertEquals("create-relationship", pending.tool());
            assertEquals("Create ServingRelationship: 'Customer' → 'Order System'",
                    pending.effectDescription());
            assertNull("create-relationship carries no intent", pending.intent());
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assume.assumeTrue("Requires OSGi runtime for RelationshipsMatrix", false);
        }
    }

    @Test
    public void shouldNameEndpointsAndCascade_inDeleteRelationshipEffectDescription() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);
        accessor.getMutationDispatcher().setApprovalModeProvider(() -> true);

        // rel-001: ServingRelationship Order System (ac-001) → Order Processing (bp-001).
        MutationResult<DeleteResultDto> result = accessor.deleteRelationship("default", "rel-001");

        assertNotNull("Approval mode should produce a proposal", result.proposalContext());
        PendingProposal pending = accessor.getMutationDispatcher().getProposal(
                "default", result.proposalContext().proposalId());
        assertNotNull(pending);
        assertEquals("delete-relationship", pending.tool());
        String effect = pending.effectDescription();
        assertNotNull(effect);
        assertTrue("names the source", effect.contains("'Order System'"));
        assertTrue("names the target", effect.contains("'Order Processing'"));
        assertTrue("carries the cascade consequence", effect.contains("cascade"));
    }

    // ---- server-owned effect text for the VISUAL-connection path ----
    // The relationship pre-exists on the view path → resolves cleanly pre-execute (no deferred-connect trap).
    // OSGi-gated like every addToView/GEF test in this class (run on the --swt/PDE lane).

    @Test
    public void shouldNameEndpoints_inAddConnectionToViewEffectDescription() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Place both endpoints with the gate OFF (direct execute), then turn the gate ON so the
        // connection itself is proposed.
        MutationResult<AddToViewResultDto> src = accessor.addToView(
                "default", "view-001", "ac-001", 50, 50, 120, 55, false, null, null, null);
        MutationResult<AddToViewResultDto> tgt = accessor.addToView(
                "default", "view-001", "bp-001", 250, 50, 120, 55, false, null, null, null);
        accessor.getMutationDispatcher().setApprovalModeProvider(() -> true);

        MutationResult<ViewConnectionDto> result = accessor.addConnectionToView(
                "default", "view-001", "rel-001",
                src.entity().viewObject().viewObjectId(),
                tgt.entity().viewObject().viewObjectId(), null, null, null, null, null);

        assertNotNull("Approval mode should produce a proposal", result.proposalContext());
        PendingProposal pending = accessor.getMutationDispatcher().getProposal(
                "default", result.proposalContext().proposalId());
        assertNotNull(pending);
        assertEquals("add-connection-to-view", pending.tool());
        // rel-001: ServingRelationship Order System (ac-001) → Order Processing (bp-001).
        // the effectDescription now also names the destination view (view-001 = "Main View").
        assertEquals("Add connection ServingRelationship: 'Order System' → 'Order Processing' "
                + "to view 'Main View'",
                pending.effectDescription());
    }

    @Test
    public void shouldNameEndpoints_inUpdateViewConnectionEffectDescription() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Place endpoints + the connection with the gate OFF, then gate ON for the bendpoint update.
        MutationResult<AddToViewResultDto> src = accessor.addToView(
                "default", "view-001", "ac-001", 50, 50, 120, 55, false, null, null, null);
        MutationResult<AddToViewResultDto> tgt = accessor.addToView(
                "default", "view-001", "bp-001", 250, 50, 120, 55, false, null, null, null);
        MutationResult<ViewConnectionDto> conn = accessor.addConnectionToView(
                "default", "view-001", "rel-001",
                src.entity().viewObject().viewObjectId(),
                tgt.entity().viewObject().viewObjectId(), null, null, null, null, null);
        accessor.getMutationDispatcher().setApprovalModeProvider(() -> true);

        MutationResult<ViewConnectionDto> result = accessor.updateViewConnection(
                "default", conn.entity().viewConnectionId(),
                List.of(new BendpointDto(30, 0, -30, 0)), null, null, null, null);

        assertNotNull("Approval mode should produce a proposal", result.proposalContext());
        PendingProposal pending = accessor.getMutationDispatcher().getProposal(
                "default", result.proposalContext().proposalId());
        assertNotNull(pending);
        assertEquals("update-view-connection", pending.tool());
        // the effectDescription now also names the view (view-001 = "Main View").
        assertEquals("Update connection ServingRelationship: 'Order System' → 'Order Processing' "
                + "in view 'Main View'",
                pending.effectDescription());
    }

    // ---- every view-visual ADD/UPDATE/DELETE proposal names the destination view ----
    // view-001 = "Main View". OSGi-gated like the visual-connection block (addToView needs GEF → --swt/PDE lane).
    // The rendered field is the mechanical `description` for the non-connection sites (the card's
    // singleRow falls back to it when effectDescription is null); exact-string assertEquals is the guard.

    @Test
    public void shouldNameView_inAddToViewDescription() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);
        accessor.getMutationDispatcher().setApprovalModeProvider(() -> true);

        MutationResult<AddToViewResultDto> result = accessor.addToView(
                "default", "view-001", "ac-001", 50, 50, 120, 55, false, null, null, null);

        PendingProposal pending = accessor.getMutationDispatcher().getProposal(
                "default", result.proposalContext().proposalId());
        assertNotNull(pending);
        assertEquals("add-to-view", pending.tool());
        assertEquals("Add ApplicationComponent 'Order System' to view 'Main View'",
                pending.description());
    }

    @Test
    public void shouldNameView_inAddGroupToViewDescription() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);
        accessor.getMutationDispatcher().setApprovalModeProvider(() -> true);

        MutationResult<ViewGroupDto> result = accessor.addGroupToView(
                "default", "view-001", "Cluster A", 10, 10, 200, 100, null, null, null);

        PendingProposal pending = accessor.getMutationDispatcher().getProposal(
                "default", result.proposalContext().proposalId());
        assertNotNull(pending);
        assertEquals("add-group-to-view", pending.tool());
        assertEquals("Add group 'Cluster A' to view 'Main View'", pending.description());
    }

    @Test
    public void shouldNameView_inAddNoteToViewDescription() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);
        accessor.getMutationDispatcher().setApprovalModeProvider(() -> true);

        MutationResult<ViewNoteDto> result = accessor.addNoteToView(
                "default", "view-001", "Hello note", null, null, 10, 10, 150, 60, null, null, null);

        PendingProposal pending = accessor.getMutationDispatcher().getProposal(
                "default", result.proposalContext().proposalId());
        assertNotNull(pending);
        assertEquals("add-note-to-view", pending.tool());
        // injects the name before the colon so the payload stays readable.
        assertEquals("Add note to view 'Main View': Hello note", pending.description());
    }

    @Test
    public void shouldNameView_inAddViewReferenceToViewDescription() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Create a second view (gate OFF → direct execute) to reference, then gate ON.
        MutationResult<ViewDto> ref = accessor.createView("default", "Detail View", null, null, null);
        String referencedViewId = ref.entity().id();
        accessor.getMutationDispatcher().setApprovalModeProvider(() -> true);

        MutationResult<EmbeddedViewDto> result = accessor.addViewReferenceToView(
                "default", "view-001", referencedViewId, 10, 10, 160, 90, null, null);

        PendingProposal pending = accessor.getMutationDispatcher().getProposal(
                "default", result.proposalContext().proposalId());
        assertNotNull(pending);
        assertEquals("add-view-reference-to-view", pending.tool());
        assertEquals("Add view-reference to view 'Main View': " + referencedViewId,
                pending.description());
    }

    @Test
    public void shouldNameView_inUpdateViewObjectDescription() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Place an object (gate OFF), then gate ON for the bounds update.
        MutationResult<AddToViewResultDto> placed = accessor.addToView(
                "default", "view-001", "ac-001", 50, 50, 120, 55, false, null, null, null);
        accessor.getMutationDispatcher().setApprovalModeProvider(() -> true);

        MutationResult<ViewObjectDto> result = accessor.updateViewObject(
                "default", placed.entity().viewObject().viewObjectId(),
                100, 100, null, null, null, null, null, null);

        PendingProposal pending = accessor.getMutationDispatcher().getProposal(
                "default", result.proposalContext().proposalId());
        assertNotNull(pending);
        assertEquals("update-view-object", pending.tool());
        assertEquals("Update view object bounds for ApplicationComponent 'Order System' "
                + "in view 'Main View'", pending.description());
    }

    @Test
    public void shouldNameView_inRemoveFromViewDescription_objectBranch() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Place an object (gate OFF, no connections → cascade 0), then gate ON for the removal.
        MutationResult<AddToViewResultDto> placed = accessor.addToView(
                "default", "view-001", "ac-001", 50, 50, 120, 55, false, null, null, null);
        accessor.getMutationDispatcher().setApprovalModeProvider(() -> true);

        MutationResult<RemoveFromViewResultDto> result = accessor.removeFromView(
                "default", "view-001", placed.entity().viewObject().viewObjectId());

        PendingProposal pending = accessor.getMutationDispatcher().getProposal(
                "default", result.proposalContext().proposalId());
        assertNotNull(pending);
        assertEquals("remove-from-view", pending.tool());
        assertEquals("Remove ApplicationComponent 'Order System' from view 'Main View'",
                pending.description());
    }

    @Test
    public void shouldNameView_inRemoveFromViewDescription_connectionBranch() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Place src + tgt + connection (gate OFF), then gate ON for the connection removal.
        MutationResult<AddToViewResultDto> src = accessor.addToView(
                "default", "view-001", "ac-001", 50, 50, 120, 55, false, null, null, null);
        MutationResult<AddToViewResultDto> tgt = accessor.addToView(
                "default", "view-001", "bp-001", 250, 50, 120, 55, false, null, null, null);
        MutationResult<ViewConnectionDto> conn = accessor.addConnectionToView(
                "default", "view-001", "rel-001",
                src.entity().viewObject().viewObjectId(),
                tgt.entity().viewObject().viewObjectId(), null, null, null, null, null);
        accessor.getMutationDispatcher().setApprovalModeProvider(() -> true);

        MutationResult<RemoveFromViewResultDto> result = accessor.removeFromView(
                "default", "view-001", conn.entity().viewConnectionId());

        PendingProposal pending = accessor.getMutationDispatcher().getProposal(
                "default", result.proposalContext().proposalId());
        assertNotNull(pending);
        assertEquals("remove-from-view", pending.tool());
        assertEquals("Remove connection (ServingRelationship) from view 'Main View'",
                pending.description());
    }

    @Test
    public void shouldDegradeToBareText_inAddToView_whenViewNameBlank() {
        // via the viewNameClause path (dangling "to view" token → ""): a blank view name
        // yields the un-named text — never "to view ''", never an NPE.
        IArchimateModel model = createTestModel();
        ((IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS)
                .getElements().get(0)).setName("");
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);
        accessor.getMutationDispatcher().setApprovalModeProvider(() -> true);

        MutationResult<AddToViewResultDto> result = accessor.addToView(
                "default", "view-001", "ac-001", 50, 50, 120, 55, false, null, null, null);

        PendingProposal pending = accessor.getMutationDispatcher().getProposal(
                "default", result.proposalContext().proposalId());
        assertNotNull(pending);
        assertEquals("Add ApplicationComponent 'Order System' to view", pending.description());
    }

    @Test
    public void shouldDegradeToBareText_inUpdateViewObject_whenViewNameBlank() {
        // via the OTHER degradation path: viewPhrase returns null and the inline ternary
        // appends nothing — the text must end with the bare element name, no trailing " in view".
        IArchimateModel model = createTestModel();
        ((IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS)
                .getElements().get(0)).setName("");
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<AddToViewResultDto> placed = accessor.addToView(
                "default", "view-001", "ac-001", 50, 50, 120, 55, false, null, null, null);
        accessor.getMutationDispatcher().setApprovalModeProvider(() -> true);

        MutationResult<ViewObjectDto> result = accessor.updateViewObject(
                "default", placed.entity().viewObject().viewObjectId(),
                100, 100, null, null, null, null, null, null);

        PendingProposal pending = accessor.getMutationDispatcher().getProposal(
                "default", result.proposalContext().proposalId());
        assertNotNull(pending);
        String desc = pending.description();
        assertEquals("Update view object bounds for ApplicationComponent 'Order System'", desc);
        assertFalse("no dangling ' in view' / trailing space on degradation",
                desc.endsWith("view") || desc.endsWith(" "));
    }

    @Test
    public void shouldEmitStructuredNamedOpsAndIntent_forBulkMutateInApprovalMode() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);
        accessor.getMutationDispatcher().setApprovalModeProvider(() -> true);

        try {
            List<BulkOperation> operations = List.of(
                    new BulkOperation("create-element", Map.of(
                            "type", "ApplicationComponent", "name", "Payment Gateway")),
                    new BulkOperation("create-relationship", Map.of(
                            "type", "ServingRelationship",
                            "sourceId", "ac-001", "targetId", "bp-001")));

            BulkMutationResult result = accessor.executeBulk(
                    "default", operations, null, false, "Wire the payments path");

            assertNotNull("Approval mode should produce a proposal", result.proposalContext());
            PendingProposal pending = accessor.getMutationDispatcher().getProposal(
                    "default", result.proposalContext().proposalId());
            assertNotNull(pending);
            // Intent persisted on the single bulk proposal (and never depended on by the server).
            assertEquals("Wire the payments path", pending.intent());

            // operations is now a structured List<Map> carrying name/type + relationship src→target.
            Object ops = pending.proposedChanges().get("operations");
            assertTrue("operations is a structured list", ops instanceof List);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> opList = (List<Map<String, Object>>) ops;
            assertEquals(2, opList.size());

            Map<String, Object> elementOp = opList.get(0);
            assertEquals("create-element", elementOp.get("tool"));
            assertEquals("Payment Gateway", elementOp.get("name"));
            assertEquals("ApplicationComponent", elementOp.get("type"));

            Map<String, Object> relOp = opList.get(1);
            assertEquals("create-relationship", relOp.get("tool"));
            // Source→target resolved to NAMES from the just-created relationship (not ids).
            assertEquals("Order System", relOp.get("source"));
            assertEquals("Order Processing", relOp.get("target"));
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            Assume.assumeTrue("Requires OSGi runtime for RelationshipsMatrix", false);
        }
    }

    // ---- C3c approval-mode coverage (Review-fix M2) ----

    @Test
    public void shouldReturnProposal_whenCreateSpecialization_inApprovalMode() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);
        accessor.getMutationDispatcher().setApprovalModeProvider(() -> true);

        MutationResult<Map<String, Object>> result = accessor.createSpecialization(
                "default", "Cloud Server", "Node");

        assertNotNull("Approval mode should produce a proposal context",
                result.proposalContext());
        assertTrue("Description should mention the new specialization",
                result.proposalContext().description().contains("Cloud Server"));
        assertTrue("Description should mention the conceptType",
                result.proposalContext().description().contains("Node"));

        // proposedChanges payload must surface name + conceptType so the approval
        // preview is meaningful (this was the C3b silent-break risk called out in retro).
        // Test fragment shares the model package — package-private PendingProposal access OK.
        PendingProposal pending = accessor.getMutationDispatcher().getProposal(
                "default", result.proposalContext().proposalId());
        assertNotNull("Pending proposal should be retrievable from dispatcher", pending);
        assertEquals("create-specialization", pending.tool());
        assertEquals("Cloud Server", pending.proposedChanges().get("name"));
        assertEquals("Node", pending.proposedChanges().get("conceptType"));

        // Profile must NOT be in the model yet — proposal is not committed
        assertEquals("Profile must not be added until approval", 0,
                model.getProfiles().size());
    }

    @Test
    public void shouldReturnProposal_whenUpdateSpecialization_inApprovalMode() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);
        // Create profile in immediate mode, then flip into approval mode for the update
        accessor.createSpecialization("default", "Old Name", "Node");
        accessor.getMutationDispatcher().setApprovalModeProvider(() -> true);

        MutationResult<Map<String, Object>> result = accessor.updateSpecialization(
                "default", "Old Name", "Node", "New Name");

        assertNotNull(result.proposalContext());
        PendingProposal pending = accessor.getMutationDispatcher().getProposal(
                "default", result.proposalContext().proposalId());
        assertNotNull(pending);
        assertEquals("update-specialization", pending.tool());
        assertEquals("Old Name", pending.proposedChanges().get("name"));
        assertEquals("Node", pending.proposedChanges().get("conceptType"));
        assertEquals("New Name", pending.proposedChanges().get("newName"));

        // Profile must still have its original name — rename is not committed
        assertEquals("Old Name", model.getProfiles().get(0).getName());
    }

    @Test
    public void shouldReturnProposal_whenDeleteSpecialization_inApprovalMode() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);
        accessor.createElement("default", "BusinessActor", "Customer A",
                null, null, null, "VIP");
        accessor.getMutationDispatcher().setApprovalModeProvider(() -> true);

        MutationResult<Map<String, Object>> result = accessor.deleteSpecialization(
                "default", "VIP", "BusinessActor", true);

        assertNotNull(result.proposalContext());
        PendingProposal pending = accessor.getMutationDispatcher().getProposal(
                "default", result.proposalContext().proposalId());
        assertNotNull(pending);
        assertEquals("delete-specialization", pending.tool());
        assertEquals("VIP", pending.proposedChanges().get("name"));
        assertEquals("BusinessActor", pending.proposedChanges().get("conceptType"));
        assertEquals(Boolean.TRUE, pending.proposedChanges().get("force"));
        // clearedFromConcepts should be surfaced in the preview so the approver can
        // see the blast radius before approving
        assertEquals(1, ((Number) pending.proposedChanges().get("clearedFromConcepts")).intValue());

        // Profile + concept must still be intact — proposal is not committed
        assertEquals(1, model.getProfiles().size());
    }

    @Test
    public void shouldEnumerateOtherProfiles_inMultiProfileGuardError() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        accessor.createElement("default", "BusinessActor", "Multi Customer",
                null, null, null, "VIP");
        IBusinessActor multi = null;
        for (IArchimateElement el : extractAllElements(model)) {
            if ("Multi Customer".equals(el.getName())) {
                multi = (IBusinessActor) el;
                break;
            }
        }
        assertNotNull(multi);
        IProfile second = IArchimateFactory.eINSTANCE.createProfile();
        second.setName("Premium");
        second.setConceptType("BusinessActor");
        model.getProfiles().add(second);
        multi.getProfiles().add(second);

        try {
            accessor.deleteSpecialization("default", "VIP", "BusinessActor", true);
            org.junit.Assert.fail("Should refuse force-delete when concept has multiple profiles");
        } catch (ModelAccessException e) {
            assertTrue("Error should name the offending concept",
                    e.getMessage().contains("Multi Customer"));
            assertTrue("Error should enumerate the at-risk *other* profile name (Premium)",
                    e.getMessage().contains("Premium"));
        }
    }

    /** Helper: collect all elements from all folders recursively for assertions. */
    private List<IArchimateElement> extractAllElements(IArchimateModel model) {
        List<IArchimateElement> all = new ArrayList<>();
        for (com.archimatetool.model.IFolder folder : model.getFolders()) {
            collectElements(folder, all);
        }
        return all;
    }

    private void collectElements(com.archimatetool.model.IFolder folder,
            List<IArchimateElement> out) {
        for (org.eclipse.emf.ecore.EObject obj : folder.getElements()) {
            if (obj instanceof IArchimateElement el) {
                out.add(el);
            }
        }
        for (com.archimatetool.model.IFolder sub : folder.getFolders()) {
            collectElements(sub, out);
        }
    }

    // ---- executeBulk tests ----

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

    // ---- executeBulk with continueOnError ----

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

    // ---- executeBulk with view tools ----

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

    // ---- executeBulk back-reference create-view tests ----

    @Test
    public void shouldExecuteBulk_shouldHandleBackRef_createViewThenAddToView() {
        // 1708 reproducer — create-view at [0],
        // add-to-view with viewId: "$0.id" at [1]. Op N+1 referencing $N
        // where N is create-view must succeed end-to-end.
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        List<BulkOperation> ops = List.of(
                new BulkOperation("create-view",
                        Map.of("name", "Test View 14-13", "viewpoint", "physical")),
                new BulkOperation("add-to-view",
                        Map.of("viewId", "$0.id", "elementId", "ba-001",
                                "x", 100, "y", 100))
        );

        BulkMutationResult result = accessor.executeBulk("default", ops, null, false);

        assertNotNull(result);
        assertTrue(result.allSucceeded());
        assertEquals(2, result.totalOperations());
        assertEquals("created", result.operations().get(0).action());
        assertEquals("placed", result.operations().get(1).action());
    }

    @Test
    public void shouldRejectBackRef_whenReferencesSelf() {
        // self-reference at op 0 (refIndex-1 = -1 is not a valid suggestion).
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        List<BulkOperation> ops = List.of(
                new BulkOperation("create-element",
                        Map.of("type", "ApplicationComponent", "name", "$0.id"))
        );

        try {
            accessor.executeBulk("default", ops, null, false);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BULK_VALIDATION_FAILED, e.getErrorCode());
            assertTrue("expected self-ref phrasing, got: " + e.getMessage(),
                    e.getMessage().contains("references the current operation itself"));
            assertTrue("expected index 0 in message, got: " + e.getMessage(),
                    e.getMessage().contains("index 0"));
            assertFalse("expected NO 'Did you mean' suggestion for refIndex=0, got: " + e.getMessage(),
                    e.getMessage().contains("Did you mean"));
        }
    }

    @Test
    public void shouldRejectBackRef_whenReferencesSelf_includesSuggestion() {
        // self-reference at op 1 — suggestion points to $0.id.
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        List<BulkOperation> ops = List.of(
                new BulkOperation("create-element",
                        Map.of("type", "ApplicationComponent", "name", "First")),
                new BulkOperation("create-element",
                        Map.of("type", "ApplicationComponent", "name", "$1.id"))
        );

        try {
            accessor.executeBulk("default", ops, null, false);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.BULK_VALIDATION_FAILED, e.getErrorCode());
            assertTrue("expected self-ref phrasing, got: " + e.getMessage(),
                    e.getMessage().contains("references the current operation itself"));
            assertTrue("expected index 1 in message, got: " + e.getMessage(),
                    e.getMessage().contains("index 1"));
            assertTrue("expected 'Did you mean '$0.id'' suggestion, got: " + e.getMessage(),
                    e.getMessage().contains("Did you mean '$0.id'"));
        }
    }

    // ---- executeBulk group/note back-reference tests ----

    @Test
    public void shouldExecuteBulk_shouldNestElementInGroup_viaBackRef() {
        // add-group-to-view at [0], add-to-view with parentViewObjectId: "$0.id"
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
        // add-group-to-view at [0], add-group-to-view at [1] with parentViewObjectId: "$0.id"
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
        // add-group-to-view at [0], add-note-to-view at [1] with parentViewObjectId: "$0.id"
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

    // ---- addToView tests ----

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

    // ---- addConnectionToView tests ----

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

    // ---- updateViewObject tests ----

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
                "default", viewObjectId, 200, 300, 180, 80, null, null, null, null);

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
                "default", viewObjectId, 200, null, null, 80, null, null, null, null);

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
            accessor.updateViewObject("default", viewObjectId, null, null, null, null, null, null, null, null);
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
            accessor.updateViewObject("default", "nonexistent", 100, 100, null, null, null, null, null, null);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.VIEW_OBJECT_NOT_FOUND, e.getErrorCode());
        }
    }

    // ---- updateViewConnection tests ----

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

    // ---- removeFromView tests ----

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

    // ---- clearView tests ----

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

    // ---- Coordinate conversion tests ----

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

    // ---- computeAbsoluteCenter tests ----

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

    // ---- apply-positions tests ----

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
        // Verifies no hardcoded operation cap.
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

    // ---- Note/group escape conversion tests ----

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

    // ---- getContentBounds tests ----

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

    // ---- Text-bearing box autosize tests ----
    //
    // Cover the new sizing behaviour in prepareAddNoteToView / prepareAddGroupToView when
    // the caller passes height=null. Helper-side math is unit-tested in ElementSizerTest;
    // these tests pin the wiring at the accessor boundary (DTO-observable height).

    @Test
    public void addNoteToView_shouldRespectExplicitHeight_whenProvided_AC2() {
        // explicit height passed by caller → bounds exactly as passed (unchanged behaviour).
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewNoteDto> result = accessor.addNoteToView(
                "default", "view-001", "Any content here",
                null, null, 50, 50, 220, 150, null, null, null);

        assertNotNull(result);
        assertEquals("Explicit height must pass through unchanged", 150, result.entity().height());
        assertEquals("Explicit width must pass through unchanged", 220, result.entity().width());
    }

    @Test
    public void addNoteToView_shouldKeepDefaultHeight_whenContentShort_AC3() {
        // empty/one-line content → height clamps to DEFAULT_NOTE_HEIGHT (80) floor.
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewNoteDto> result = accessor.addNoteToView(
                "default", "view-001", "Title",
                null, null, 50, 50, null, null, null, null, null);

        assertNotNull(result);
        assertEquals("Short content must clamp to DEFAULT_NOTE_HEIGHT", 80, result.entity().height());
    }

    @Test
    public void addNoteToView_shouldFitHeightToLongContent_whenHeightNull_AC1() {
        // 6–8 line title + no explicit height → height grows past DEFAULT_NOTE_HEIGHT.
        // Use the Retail Bank prompt's exact title style — long descriptive content at the
        // default 185px width wraps to ~6 lines.
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        String longTitle =
                "A — Business Architecture: customer journeys, services, products, "
              + "and the banking products offered by the retail bank organization "
              + "across digital and branch channels.";

        MutationResult<ViewNoteDto> result = accessor.addNoteToView(
                "default", "view-001", longTitle,
                null, null, 50, 50, null, null, null, null, null);

        assertNotNull(result);
        assertTrue("Long-content note must grow past DEFAULT_NOTE_HEIGHT (80): got "
                + result.entity().height(), result.entity().height() > 80);
        assertTrue("Long-content note must not exceed MAX_NOTE_HEIGHT (600): got "
                + result.entity().height(), result.entity().height() <= 600);
    }

    @Test
    public void addNoteToView_shouldAutofitMultilineNoteLinearly_whenHeightOmitted() {
        // Red-on-revert pin for the measureText single-line-height fix.
        // A note whose content is several explicit '\n'-separated short lines, with height
        // omitted, must auto-fit to a height that grows LINEARLY with the line count.
        // Before the fix, measureText set lineHeight = textExtent(wholeBlock).y (≈ N×trueLineHeight)
        // and fitTextBoxHeightToContent multiplied that by the line count again, so a 10-line note
        // ballooned past 600 and clamped exactly to MAX_NOTE_HEIGHT. After the fix lineHeight is a
        // single line, so the same note resolves to roughly N×trueLineHeight (well under 400).
        // The pin runs in the PDE/real-EMF lane because measureText needs an SWT Display; the
        // pure-geometry ElementSizerTest constructs FontMetrics directly and cannot exercise it.
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        StringBuilder tenLines = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            if (i > 1) {
                tenLines.append('\n');
            }
            tenLines.append("Line ").append(i);
        }

        MutationResult<ViewNoteDto> result = accessor.addNoteToView(
                "default", "view-001", tenLines.toString(),
                null, null, 50, 50, 220, null, null, null, null);

        assertNotNull(result);
        int h = result.entity().height();
        // Grows past the floor (it is multi-line) ...
        assertTrue("Multi-line note must grow past DEFAULT_NOTE_HEIGHT (80): got " + h, h > 80);
        // ... but LINEARLY, nowhere near the quadratic-then-clamped 600. A 10-line note at any
        // ordinary font line-height (~13–25px) lands well below 400; the pre-fix value was 600.
        assertTrue("Multi-line note must grow linearly, not quadratically/clamped (expected < 400): got "
                + h, h < 400);
        assertTrue("Multi-line note must not reach MAX_NOTE_HEIGHT clamp (600): got " + h, h < 600);
    }

    @Test
    public void addGroupToView_shouldKeepDefaultHeight_whenShortLabel_AC15() {
        // Pin: short label + height==null + width==null (default 300×200) MUST
        // produce setBounds(x, y, w, 200) with 200 literally — byte-identical to today.
        // This guards against the helper silently bumping every default-height group
        // taller via a max(200, labelBandHeight) expression that could return 200+ε.
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> result = accessor.addGroupToView(
                "default", "view-001", "Banking Products",
                50, 50, null, null, null, null, null);

        assertNotNull(result);
        assertEquals("AC-15: short-label default-height group must stay at 200 literally",
                200, result.entity().height());
        assertEquals("AC-15: short-label default-width group must stay at 300 literally",
                300, result.entity().width());
    }

    @Test
    public void addGroupToView_shouldGrowHeight_whenLongLabel_AC7() {
        // long label + height==null → label band reserves room → resolvedHeight > 200.
        // At default group width (300 px), each individually-wide word forces its own wrapped
        // line. With ~14+ wrapped lines the raw band exceeds the 200-px minHeight floor and
        // the helper returns the actual computed height (no short-label short-circuit). 30 long
        // words guarantee enough lines to clear 200 px on the real macOS system font.
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        String longWord = "PleaseMakeThisWordExtremelyLongForTestingPurposesOfWrap";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append(longWord).append(i).append(' ');
        }
        String longLabel = sb.toString().trim();

        MutationResult<ViewGroupDto> result = accessor.addGroupToView(
                "default", "view-001", longLabel,
                50, 50, null, null, null, null, null);

        assertNotNull(result);
        assertTrue("AC-7: long-label group must grow past DEFAULT_GROUP_HEIGHT (200): got "
                + result.entity().height(), result.entity().height() > 200);
        assertTrue("AC-7: long-label group must not exceed MAX_GROUP_LABEL_BAND (800): got "
                + result.entity().height(), result.entity().height() <= 800);
    }

    @Test
    public void addGroupToView_shouldRespectExplicitHeight_whenProvided_AC7BackCompat() {
        // back-compat: caller-pinned height wins even when label would otherwise grow it.
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        String longLabel =
                "Retail Bank Customer Journeys and Channel Services covering digital "
              + "branch mobile and back-office capabilities";

        MutationResult<ViewGroupDto> result = accessor.addGroupToView(
                "default", "view-001", longLabel,
                50, 50, 400, 250, null, null, null);

        assertNotNull(result);
        assertEquals("Explicit group height must pass through unchanged",
                250, result.entity().height());
        assertEquals("Explicit group width must pass through unchanged",
                400, result.entity().width());
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
                "default", noteVoId, null, null, null, null, "Updated\\nContent", null, null, null);

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

    // ---- connectionRouterType tests ----

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

    // ---- bulk-mutate connectionRouterType tests ----

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

    // ---- Layout within group tests ----

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

    // ---- autoWidth tests ----

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

    // ---- Grid columns tests ----

    @Test
    public void layoutWithinGroup_shouldUseExplicitColumnCount() {
        // explicit columns=2 with 3 elements → 2 columns, 2 rows
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
        // columns=20 with 3 elements → capped to 3 (single row)
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
        // columns=null → auto-detect from group width (no regression)
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

    // ---- Recursive resize tests ----

    @Test
    public void layoutWithinGroup_shouldRecursivelyResizeParentGroup() {
        // recursive=true resizes ancestor groups
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
        // recursive=false → only target group resized
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
        // top-level group with recursive=true → no ancestors to resize
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

    // ---- layoutWithinGroup polymorphic container extension ----

    /**
     * ApplicationComponent containing ApplicationFunctions accepts row arrangement.
     * Replaces the v1.5 VIEW_OBJECT_NOT_FOUND rejection with successful layout.
     */
    @Test
    public void layoutWithinGroup_shouldAcceptApplicationComponentContainer_rowArrangement() {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = createTestModel();

        // Dedicated ApplicationComponent for this test (avoids fixture-order coupling on ac-001)
        IArchimateElement compElem = factory.createApplicationComponent();
        compElem.setId("ac-c1-row");
        compElem.setName("Order Service");
        model.getFolder(FolderType.APPLICATION).getElements().add(compElem);

        // Add 3 ApplicationFunction children to the application folder
        IArchimateElement func1 = factory.createApplicationFunction();
        func1.setId("af-001");
        func1.setName("Capture Order");
        model.getFolder(FolderType.APPLICATION).getElements().add(func1);
        IArchimateElement func2 = factory.createApplicationFunction();
        func2.setId("af-002");
        func2.setName("Validate Order");
        model.getFolder(FolderType.APPLICATION).getElements().add(func2);
        IArchimateElement func3 = factory.createApplicationFunction();
        func3.setId("af-003");
        func3.setName("Persist Order");
        model.getFolder(FolderType.APPLICATION).getElements().add(func3);

        // Get the empty view and attach an ApplicationComponent visual with nested functions
        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS)
                .getElements().get(0);
        IDiagramModelArchimateObject compVo = factory.createDiagramModelArchimateObject();
        compVo.setId("comp-vo-1");
        compVo.setArchimateElement(compElem);
        compVo.setBounds(50, 50, 400, 200);
        view.getChildren().add(compVo);

        // Nest 3 ApplicationFunctions inside compVo
        IDiagramModelArchimateObject f1Vo = factory.createDiagramModelArchimateObject();
        f1Vo.setId("af-vo-1");
        f1Vo.setArchimateElement(func1);
        f1Vo.setBounds(5, 5, 120, 50);
        compVo.getChildren().add(f1Vo);
        IDiagramModelArchimateObject f2Vo = factory.createDiagramModelArchimateObject();
        f2Vo.setId("af-vo-2");
        f2Vo.setArchimateElement(func2);
        f2Vo.setBounds(5, 60, 120, 50);
        compVo.getChildren().add(f2Vo);
        IDiagramModelArchimateObject f3Vo = factory.createDiagramModelArchimateObject();
        f3Vo.setId("af-vo-3");
        f3Vo.setArchimateElement(func3);
        f3Vo.setBounds(5, 115, 120, 50);
        compVo.getChildren().add(f3Vo);

        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<LayoutWithinGroupResultDto> result =
                accessor.layoutWithinGroup("default", "view-001", "comp-vo-1",
                        "row", 40, 10, 120, 50, false, false, null, false);

        assertNotNull(result);
        assertEquals("view-001", result.entity().viewId());
        assertEquals("comp-vo-1", result.entity().groupViewObjectId());
        assertEquals("row", result.entity().arrangement());
        assertEquals(3, result.entity().elementsRepositioned());
        // Row layout: startX=padding=10, startY=padding+GROUP_LABEL_HEIGHT(24)=34
        assertEquals("First function x", 10, f1Vo.getBounds().getX());
        assertEquals("First function y", 34, f1Vo.getBounds().getY());
        assertEquals("Second function x", 10 + 120 + 40, f2Vo.getBounds().getX());
        assertEquals("Second function y", 34, f2Vo.getBounds().getY());
        assertEquals("Third function x", 10 + 2 * (120 + 40), f3Vo.getBounds().getX());
    }

    /**
     * ApplicationComponent containing ApplicationFunctions accepts grid arrangement
     * with explicit columns=2.
     */
    @Test
    public void layoutWithinGroup_shouldAcceptApplicationComponentContainer_gridArrangement() {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = createTestModel();

        // Dedicated ApplicationComponent for this test (avoids fixture-order coupling on ac-001)
        IArchimateElement compElem = factory.createApplicationComponent();
        compElem.setId("ac-c1-grid");
        compElem.setName("Order Service Grid");
        model.getFolder(FolderType.APPLICATION).getElements().add(compElem);

        IArchimateElement func1 = factory.createApplicationFunction();
        func1.setId("af-001");
        func1.setName("Func A");
        model.getFolder(FolderType.APPLICATION).getElements().add(func1);
        IArchimateElement func2 = factory.createApplicationFunction();
        func2.setId("af-002");
        func2.setName("Func B");
        model.getFolder(FolderType.APPLICATION).getElements().add(func2);
        IArchimateElement func3 = factory.createApplicationFunction();
        func3.setId("af-003");
        func3.setName("Func C");
        model.getFolder(FolderType.APPLICATION).getElements().add(func3);

        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS)
                .getElements().get(0);
        IDiagramModelArchimateObject compVo = factory.createDiagramModelArchimateObject();
        compVo.setId("comp-vo-grid");
        compVo.setArchimateElement(compElem);
        compVo.setBounds(0, 0, 400, 300);
        view.getChildren().add(compVo);

        IDiagramModelArchimateObject f1Vo = factory.createDiagramModelArchimateObject();
        f1Vo.setArchimateElement(func1);
        f1Vo.setBounds(0, 0, 120, 50);
        compVo.getChildren().add(f1Vo);
        IDiagramModelArchimateObject f2Vo = factory.createDiagramModelArchimateObject();
        f2Vo.setArchimateElement(func2);
        f2Vo.setBounds(0, 0, 120, 50);
        compVo.getChildren().add(f2Vo);
        IDiagramModelArchimateObject f3Vo = factory.createDiagramModelArchimateObject();
        f3Vo.setArchimateElement(func3);
        f3Vo.setBounds(0, 0, 120, 50);
        compVo.getChildren().add(f3Vo);

        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<LayoutWithinGroupResultDto> result =
                accessor.layoutWithinGroup("default", "view-001", "comp-vo-grid",
                        "grid", 40, 10, 120, 50, false, false, 2, false);

        assertNotNull(result);
        assertEquals("grid", result.entity().arrangement());
        assertEquals(3, result.entity().elementsRepositioned());
        assertNotNull("columnsUsed populated for grid arrangement", result.entity().columnsUsed());
        assertEquals("Explicit columns=2 should be used", Integer.valueOf(2),
                result.entity().columnsUsed());
    }

    /**
     * Node containing SystemSoftware + Artifacts accepts grid arrangement (auto-columns).
     */
    @Test
    public void layoutWithinGroup_shouldAcceptNodeContainer_gridArrangement() {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = createTestModel();

        IArchimateElement node = factory.createNode();
        node.setId("node-001");
        node.setName("App Server");
        model.getFolder(FolderType.TECHNOLOGY).getElements().add(node);
        IArchimateElement sysSoft = factory.createSystemSoftware();
        sysSoft.setId("sys-001");
        sysSoft.setName("Tomcat 10");
        model.getFolder(FolderType.TECHNOLOGY).getElements().add(sysSoft);
        IArchimateElement artifact1 = factory.createArtifact();
        artifact1.setId("art-001");
        artifact1.setName("orders.war");
        model.getFolder(FolderType.TECHNOLOGY).getElements().add(artifact1);
        IArchimateElement artifact2 = factory.createArtifact();
        artifact2.setId("art-002");
        artifact2.setName("config.yaml");
        model.getFolder(FolderType.TECHNOLOGY).getElements().add(artifact2);

        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS)
                .getElements().get(0);
        IDiagramModelArchimateObject nodeVo = factory.createDiagramModelArchimateObject();
        nodeVo.setId("node-vo-1");
        nodeVo.setArchimateElement(node);
        nodeVo.setBounds(100, 100, 500, 400);
        view.getChildren().add(nodeVo);

        IDiagramModelArchimateObject ssVo = factory.createDiagramModelArchimateObject();
        ssVo.setArchimateElement(sysSoft);
        ssVo.setBounds(0, 0, 120, 50);
        nodeVo.getChildren().add(ssVo);
        IDiagramModelArchimateObject a1Vo = factory.createDiagramModelArchimateObject();
        a1Vo.setArchimateElement(artifact1);
        a1Vo.setBounds(0, 0, 120, 50);
        nodeVo.getChildren().add(a1Vo);
        IDiagramModelArchimateObject a2Vo = factory.createDiagramModelArchimateObject();
        a2Vo.setArchimateElement(artifact2);
        a2Vo.setBounds(0, 0, 120, 50);
        nodeVo.getChildren().add(a2Vo);

        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<LayoutWithinGroupResultDto> result =
                accessor.layoutWithinGroup("default", "view-001", "node-vo-1",
                        "grid", 40, 10, 120, 50, false, false, null, false);

        assertNotNull(result);
        assertEquals("grid", result.entity().arrangement());
        assertEquals(3, result.entity().elementsRepositioned());
        assertNotNull("columnsUsed auto-derived from container width",
                result.entity().columnsUsed());
    }

    /**
     * A note view-object is rejected as a container with VIEW_OBJECT_NOT_FOUND.
     * Notes implement IDiagramModelContainer in EMF but are excluded per project-context.md.
     */
    @Test
    public void layoutWithinGroup_shouldRejectNoteAsContainer_returnsViewObjectNotFound() {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = createTestModel();

        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS)
                .getElements().get(0);
        IDiagramModelNote note = factory.createDiagramModelNote();
        note.setId("note-1");
        note.setBounds(50, 50, 200, 100);
        view.getChildren().add(note);

        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            accessor.layoutWithinGroup("default", "view-001", "note-1",
                    "row", null, null, null, null, false, false, null, false);
            fail("Expected ModelAccessException for note-as-container rejection");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.VIEW_OBJECT_NOT_FOUND, e.getErrorCode());
            assertTrue("Message should describe layout-eligible containers, was: " + e.getMessage(),
                    e.getMessage().contains("layout-eligible container"));
        }
    }

    /**
     * An ArchiMate-element container with no children rejects with INVALID_PARAMETER.
     * Message generalized from "Group has no children" to "Container has no children".
     */
    @Test
    public void layoutWithinGroup_shouldRejectArchiMateContainerWithNoChildren_returnsInvalidParameter() {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = createTestModel();

        // Dedicated ApplicationComponent for this test (avoids fixture-order coupling on ac-001)
        IArchimateElement emptyCompElem = factory.createApplicationComponent();
        emptyCompElem.setId("ac-c1-empty");
        emptyCompElem.setName("Empty Component");
        model.getFolder(FolderType.APPLICATION).getElements().add(emptyCompElem);

        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS)
                .getElements().get(0);
        IDiagramModelArchimateObject emptyCompVo = factory.createDiagramModelArchimateObject();
        emptyCompVo.setId("comp-empty");
        emptyCompVo.setArchimateElement(emptyCompElem);
        emptyCompVo.setBounds(50, 50, 200, 100);
        view.getChildren().add(emptyCompVo);

        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            accessor.layoutWithinGroup("default", "view-001", "comp-empty",
                    "row", null, null, null, null, false, false, null, false);
            fail("Expected ModelAccessException for empty-container rejection");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            assertEquals("Container has no children to layout", e.getMessage());
        }
    }

    // ---- arrange-groups tests ----

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

    // ---- arrange-groups direction tests ----

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

    // ---- arrange-groups standalone-element-lane tests ----

    /**
     * Test fixture builder for the View-H-shape (hub-and-spoke):
     * 2 groups + 1 standalone hub Node connected to elements in both groups.
     * Returns the assembled model; caller wires accessor + invokes arrangeGroups.
     */
    private IArchimateModel createViewHHubAndSpokeFixture(
            int leftGroupWidth, int leftGroupHeight,
            int rightGroupWidth, int rightGroupHeight,
            int hubWidth, int hubHeight) {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setName("View-H Hub-and-Spoke Fixture");
        model.setId("model-vh");
        model.setDefaults();

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-vh");
        view.setName("View H");

        // Producers group (left): 1 element
        IDiagramModelGroup producers = factory.createDiagramModelGroup();
        producers.setId("g-producers"); producers.setName("Producers");
        producers.setBounds(0, 0, leftGroupWidth, leftGroupHeight);
        IArchimateElement elemProducer = factory.createApplicationComponent();
        elemProducer.setId("e-producer"); elemProducer.setName("Producer");
        model.getFolder(FolderType.APPLICATION).getElements().add(elemProducer);
        IDiagramModelArchimateObject voProducer = factory.createDiagramModelArchimateObject();
        voProducer.setId("vo-producer");
        voProducer.setArchimateElement(elemProducer);
        voProducer.setBounds(10, 34, 120, 55);
        producers.getChildren().add(voProducer);

        // Consumers group (right): 1 element
        IDiagramModelGroup consumers = factory.createDiagramModelGroup();
        consumers.setId("g-consumers"); consumers.setName("Consumers");
        consumers.setBounds(0, 0, rightGroupWidth, rightGroupHeight);
        IArchimateElement elemConsumer = factory.createApplicationComponent();
        elemConsumer.setId("e-consumer"); elemConsumer.setName("Consumer");
        model.getFolder(FolderType.APPLICATION).getElements().add(elemConsumer);
        IDiagramModelArchimateObject voConsumer = factory.createDiagramModelArchimateObject();
        voConsumer.setId("vo-consumer");
        voConsumer.setArchimateElement(elemConsumer);
        voConsumer.setBounds(10, 34, 120, 55);
        consumers.getChildren().add(voConsumer);

        // Hub: standalone Node (Technology layer) at top level — NOT inside any group
        IArchimateElement hubElem = factory.createNode();
        hubElem.setId("e-hub"); hubElem.setName("Enterprise Integration Bus");
        model.getFolder(FolderType.TECHNOLOGY).getElements().add(hubElem);
        IDiagramModelArchimateObject voHub = factory.createDiagramModelArchimateObject();
        voHub.setId("vo-hub");
        voHub.setArchimateElement(hubElem);
        voHub.setBounds(500, 500, hubWidth, hubHeight);

        view.getChildren().add(producers);
        view.getChildren().add(consumers);
        view.getChildren().add(voHub); // standalone — top-level

        // Connections: producer→hub and hub→consumer (so hub connects to elements
        // in BOTH groups → qualifies for ≥ 2-groups predicate)
        IArchimateRelationship rel1 = factory.createFlowRelationship();
        rel1.setId("r-producer-hub"); rel1.setSource(elemProducer); rel1.setTarget(hubElem);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel1);
        var conn1 = factory.createDiagramModelArchimateConnection();
        conn1.setArchimateRelationship(rel1);
        conn1.connect(voProducer, voHub);

        IArchimateRelationship rel2 = factory.createServingRelationship();
        rel2.setId("r-hub-consumer"); rel2.setSource(hubElem); rel2.setTarget(elemConsumer);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel2);
        var conn2 = factory.createDiagramModelArchimateConnection();
        conn2.setArchimateRelationship(rel2);
        conn2.connect(voHub, voConsumer);

        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        return model;
    }

    /**
     * View H equivalent: hub-and-spoke. `arrange-groups arrangement: "topology",
     * direction: "horizontal"` places the standalone hub Node centred between the two
     * groups in the reserved inter-group lane with {@code resolvedSpacing} clearance.
     */
    @Test
    public void arrangeGroups_viewHHubAndSpoke_placesHubCentredBetweenGroups() {
        IArchimateModel model = createViewHHubAndSpokeFixture(
                /*leftW*/ 200, /*leftH*/ 150,
                /*rightW*/ 200, /*rightH*/ 150,
                /*hubW*/ 260, /*hubH*/ 280); // View-H actual hub size (post recipe step-8 resize)
        stubModelManager.setModels(List.of(model));
        ArchiModelAccessorImpl acc = createAccessorWithTestDispatcher(model);

        int spacing = 40;
        MutationResult<net.vheerden.archi.mcp.response.dto.ArrangeGroupsResultDto> result =
                acc.arrangeGroups("default", "view-vh", "topology", null, spacing, null, "horizontal");

        assertNotNull(result);
        assertEquals(2, result.entity().groupsPositioned());
        assertEquals("topology", result.entity().arrangement());
        assertEquals("Hub should be classified as a qualifier and placed.",
                1, result.entity().standaloneElementsPlaced());

        // Find groups + hub by id
        IArchimateDiagramModel view = (IArchimateDiagramModel)
                model.getFolder(FolderType.DIAGRAMS).getElements().get(0);
        IDiagramModelObject producers = findChildById(view, "g-producers");
        IDiagramModelObject consumers = findChildById(view, "g-consumers");
        IDiagramModelObject hub = findChildById(view, "vo-hub");
        assertNotNull("Producers group", producers);
        assertNotNull("Consumers group", consumers);
        assertNotNull("Hub", hub);

        // Producers should be at startX=20 (ARRANGE_GROUPS_ORIGIN); consumers should follow
        // after Producers.width + lane (hub.width + 2*spacing) = 200 + (260 + 80) = 540
        // So consumers.x = 20 + 200 + 340 = 560
        assertEquals("Producers at origin", 20, producers.getBounds().getX());
        assertEquals("Producers + lane → consumers", 560, consumers.getBounds().getX());
        // Both groups on same row y=20
        assertEquals(20, producers.getBounds().getY());
        assertEquals(20, consumers.getBounds().getY());

        // Hub: centred horizontally in lane.
        // Lane spans [producers.right..consumers.left] = [220..560]
        // Hub.x = lane.left + spacing = 220 + 40 = 260
        assertEquals("Hub centred horizontally with spacing clearance",
                260, hub.getBounds().getX());
        // Hub.y: vertically centred in union of group bounds.
        // unionTop=20, unionBottom=20+150=170, unionMidY=95. Hub.y = 95 - hubHeight/2 = 95 - 140 = -45
        assertEquals("Hub centred vertically in union", -45, hub.getBounds().getY());
        // Hub width/height preserved
        assertEquals(260, hub.getBounds().getWidth());
        assertEquals(280, hub.getBounds().getHeight());

        // Hub does NOT overlap either group horizontally
        int hubLeft = hub.getBounds().getX();
        int hubRight = hubLeft + hub.getBounds().getWidth();
        int producersRight = producers.getBounds().getX() + producers.getBounds().getWidth();
        int consumersLeft = consumers.getBounds().getX();
        assertTrue("Hub.left >= Producers.right + spacing", hubLeft >= producersRight);
        assertTrue("Hub.right + spacing <= Consumers.left", hubRight + spacing <= consumersLeft + 1); // +1 for off-by-one tolerance
    }

    /**
     * View J equivalent: technology-deployment with a standalone Path element
     * between zones. The Path bounds end up between zone-A right-edge and zone-B
     * left-edge with no overlap. Routing quality verification is owner-side empirical
     * post-merge (the unit lane cannot run the full routing pipeline without OSGi).
     */
    @Test
    public void arrangeGroups_viewJZonePath_placesPathInInterZoneCorridor() {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setName("View-J Zone-Path Fixture");
        model.setId("model-vj");
        model.setDefaults();

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-vj");
        view.setName("View J");

        // Zone A (left): 1 EKS-equivalent element
        IDiagramModelGroup zoneA = factory.createDiagramModelGroup();
        zoneA.setId("g-zoneA"); zoneA.setName("Availability Zone A");
        zoneA.setBounds(0, 0, 250, 200);
        IArchimateElement elemEks = factory.createNode();
        elemEks.setId("e-eks"); elemEks.setName("EKS");
        model.getFolder(FolderType.TECHNOLOGY).getElements().add(elemEks);
        IDiagramModelArchimateObject voEks = factory.createDiagramModelArchimateObject();
        voEks.setId("vo-eks");
        voEks.setArchimateElement(elemEks);
        voEks.setBounds(10, 34, 120, 55);
        zoneA.getChildren().add(voEks);

        // Zone B (right): 1 Mainframe element
        IDiagramModelGroup zoneB = factory.createDiagramModelGroup();
        zoneB.setId("g-zoneB"); zoneB.setName("On-Premises Data Centre");
        zoneB.setBounds(0, 0, 250, 200);
        IArchimateElement elemMainframe = factory.createNode();
        elemMainframe.setId("e-mainframe"); elemMainframe.setName("Mainframe");
        model.getFolder(FolderType.TECHNOLOGY).getElements().add(elemMainframe);
        IDiagramModelArchimateObject voMainframe = factory.createDiagramModelArchimateObject();
        voMainframe.setId("vo-mainframe");
        voMainframe.setArchimateElement(elemMainframe);
        voMainframe.setBounds(10, 34, 120, 55);
        zoneB.getChildren().add(voMainframe);

        // Site-to-Site VPN: standalone Path at top level (narrow horizontal rectangle)
        IArchimateElement vpnElem = factory.createPath();
        vpnElem.setId("e-vpn"); vpnElem.setName("Site-to-Site VPN");
        model.getFolder(FolderType.TECHNOLOGY).getElements().add(vpnElem);
        IDiagramModelArchimateObject voVpn = factory.createDiagramModelArchimateObject();
        voVpn.setId("vo-vpn");
        voVpn.setArchimateElement(vpnElem);
        voVpn.setBounds(900, 900, 100, 40);

        view.getChildren().add(zoneA);
        view.getChildren().add(zoneB);
        view.getChildren().add(voVpn);

        // Associations: VPN ↔ EKS and VPN ↔ Mainframe (VPN qualifies via ≥ 2 group connections)
        IArchimateRelationship rel1 = factory.createAssociationRelationship();
        rel1.setId("r-vpn-eks"); rel1.setSource(vpnElem); rel1.setTarget(elemEks);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel1);
        var conn1 = factory.createDiagramModelArchimateConnection();
        conn1.setArchimateRelationship(rel1);
        conn1.connect(voVpn, voEks);

        IArchimateRelationship rel2 = factory.createAssociationRelationship();
        rel2.setId("r-vpn-mainframe"); rel2.setSource(vpnElem); rel2.setTarget(elemMainframe);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel2);
        var conn2 = factory.createDiagramModelArchimateConnection();
        conn2.setArchimateRelationship(rel2);
        conn2.connect(voVpn, voMainframe);

        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        stubModelManager.setModels(List.of(model));
        ArchiModelAccessorImpl acc = createAccessorWithTestDispatcher(model);

        MutationResult<net.vheerden.archi.mcp.response.dto.ArrangeGroupsResultDto> result =
                acc.arrangeGroups("default", "view-vj", "topology", null, 40, null, "horizontal");

        assertNotNull(result);
        assertEquals(2, result.entity().groupsPositioned());
        assertEquals(1, result.entity().standaloneElementsPlaced());

        IDiagramModelObject zaPlaced = findChildById(view, "g-zoneA");
        IDiagramModelObject zbPlaced = findChildById(view, "g-zoneB");
        IDiagramModelObject vpnPlaced = findChildById(view, "vo-vpn");

        // Path lies geometrically BETWEEN zone-A right-edge and zone-B left-edge, non-overlapping.
        // Geometric assertion (the unit-test contract).
        int zaRight = zaPlaced.getBounds().getX() + zaPlaced.getBounds().getWidth();
        int zbLeft = zbPlaced.getBounds().getX();
        int vpnLeft = vpnPlaced.getBounds().getX();
        int vpnRight = vpnLeft + vpnPlaced.getBounds().getWidth();
        assertTrue("VPN.left >= ZoneA.right (no overlap with ZoneA)",
                vpnLeft >= zaRight);
        assertTrue("VPN.right <= ZoneB.left (no overlap with ZoneB)",
                vpnRight <= zbLeft);
        // VPN is in the inter-zone corridor (lane geometry)
        assertTrue("VPN strictly between zones (corridor)",
                vpnLeft > zaRight && vpnRight < zbLeft);
    }

    /**
     * Back-compat: a view with zero qualifying standalone elements (only groups
     * and a Note) produces the same group positions as the pre-fix implementation.
     * DTO may gain a {@code standaloneElementsPlaced=0} field; the contract pin is
     * GROUP positions, not full DTO equality.
     */
    @Test
    public void arrangeGroups_backCompatNoQualifier_byteIdenticalGroupPositions() {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setName("Back-Compat Fixture");
        model.setId("model-bc");
        model.setDefaults();

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-bc");
        view.setName("Back-Compat View");

        // 2 groups, each with 1 element inside
        IDiagramModelGroup gA = factory.createDiagramModelGroup();
        gA.setId("g-A"); gA.setName("A");
        gA.setBounds(0, 0, 200, 150);
        IArchimateElement elA = factory.createBusinessActor();
        elA.setId("e-A"); elA.setName("A1");
        model.getFolder(FolderType.BUSINESS).getElements().add(elA);
        IDiagramModelArchimateObject voA = factory.createDiagramModelArchimateObject();
        voA.setId("vo-A");
        voA.setArchimateElement(elA);
        voA.setBounds(10, 34, 120, 55);
        gA.getChildren().add(voA);

        IDiagramModelGroup gB = factory.createDiagramModelGroup();
        gB.setId("g-B"); gB.setName("B");
        gB.setBounds(0, 0, 200, 150);
        IArchimateElement elB = factory.createBusinessProcess();
        elB.setId("e-B"); elB.setName("B1");
        model.getFolder(FolderType.BUSINESS).getElements().add(elB);
        IDiagramModelArchimateObject voB = factory.createDiagramModelArchimateObject();
        voB.setId("vo-B");
        voB.setArchimateElement(elB);
        voB.setBounds(10, 34, 120, 55);
        gB.getChildren().add(voB);

        // A standalone Note at top level (Notes are explicitly NOT qualifiers per the predicate).
        IDiagramModelNote note = factory.createDiagramModelNote();
        note.setId("n-title"); note.setContent("View Title");
        note.setBounds(0, 0, 200, 80);

        view.getChildren().add(gA);
        view.getChildren().add(gB);
        view.getChildren().add(note);

        // Inter-group connection so topology ordering has something to work with
        IArchimateRelationship rel = factory.createServingRelationship();
        rel.setId("r-ab"); rel.setSource(elA); rel.setTarget(elB);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);
        var conn = factory.createDiagramModelArchimateConnection();
        conn.setArchimateRelationship(rel);
        conn.connect(voA, voB);

        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        stubModelManager.setModels(List.of(model));
        ArchiModelAccessorImpl acc = createAccessorWithTestDispatcher(model);

        MutationResult<net.vheerden.archi.mcp.response.dto.ArrangeGroupsResultDto> result =
                acc.arrangeGroups("default", "view-bc", "topology", null, 40, null, "horizontal");

        assertNotNull(result);
        assertEquals(2, result.entity().groupsPositioned());
        // Contract: zero qualifiers placed.
        assertEquals("Note is not a qualifier; zero placements expected",
                0, result.entity().standaloneElementsPlaced());

        // GROUP positions match the pre-fix expected layout: row y=20, x=20 + 200 + 40 = 260
        IDiagramModelObject gAPlaced = findChildById(view, "g-A");
        IDiagramModelObject gBPlaced = findChildById(view, "g-B");
        assertEquals("Group A at origin x", 20, gAPlaced.getBounds().getX());
        assertEquals("Group A row y", 20, gAPlaced.getBounds().getY());
        assertEquals("Group B at origin + width + spacing", 260, gBPlaced.getBounds().getX());
        assertEquals("Group B row y", 20, gBPlaced.getBounds().getY());

        // Note bounds should be unchanged (we did NOT reposition it).
        IDiagramModelObject notePlaced = findChildById(view, "n-title");
        assertNotNull("Note still in view", notePlaced);
        assertEquals("Note.x untouched", 0, notePlaced.getBounds().getX());
        assertEquals("Note.y untouched", 0, notePlaced.getBounds().getY());
        assertEquals("Note.width untouched", 200, notePlaced.getBounds().getWidth());
        assertEquals("Note.height untouched", 80, notePlaced.getBounds().getHeight());
    }

    /**
     * mode='grouped' parity: the embedded {@code computeGroupedLayoutPass}
     * site at {@code ArchiModelAccessorImpl.java:4867+} uses the same shared
     * helper as the user-facing {@code arrangeGroups} path. This pin asserts
     * the helper is invoked from both call sites — proven structurally by both
     * sites calling {@code ArrangeGroupsStandaloneLane.classify} /
     * {@code assignToGaps} / {@code computeLaneSizes} / {@code placeQualifiers}.
     *
     * <p>Live {@code auto-layout-and-route mode: "grouped"} integration verification
     * is empirical (the JUnit pins are the contract). This pin verifies the parity
     * contract surface via the helper's classify+placeQualifiers behaviour on
     * the View-H-shape fixture with the SAME inputs that
     * computeGroupedLayoutPass passes (using virtualGroupBounds dimensions
     * instead of group.getBounds()).</p>
     */
    @Test
    public void arrangeGroups_groupedModeParity_helperHandlesVirtualGroupBoundsIdentically() {
        // Build the View-H fixture
        IArchimateModel model = createViewHHubAndSpokeFixture(
                /*leftW*/ 200, /*leftH*/ 150,
                /*rightW*/ 200, /*rightH*/ 150,
                /*hubW*/ 260, /*hubH*/ 280);
        IArchimateDiagramModel view = (IArchimateDiagramModel)
                model.getFolder(FolderType.DIAGRAMS).getElements().get(0);

        // Directly exercise the helper with the same shape that
        // computeGroupedLayoutPass uses (post-resize virtual dimensions).
        List<IDiagramModelGroup> orderedGroups = new ArrayList<>();
        for (IDiagramModelObject child : view.getChildren()) {
            if (child instanceof IDiagramModelGroup g) orderedGroups.add(g);
        }
        assertEquals(2, orderedGroups.size());

        // Helper keys lookups by wrapper (IDiagramModelObject) ID, not underlying element ID.
        java.util.Map<String, String> elementToGroup = new java.util.HashMap<>();
        elementToGroup.put("vo-producer", "g-producers");
        elementToGroup.put("vo-consumer", "g-consumers");

        List<ArrangeGroupsStandaloneLane.QualifyingStandaloneElement> qualifiers =
                ArrangeGroupsStandaloneLane.classify(
                        view.getChildren(), orderedGroups, elementToGroup);
        assertEquals("Hub qualifies (connected to elements in both groups)",
                1, qualifiers.size());
        assertEquals("vo-hub", qualifiers.get(0).element().getId());

        java.util.Map<Integer, List<ArrangeGroupsStandaloneLane.QualifyingStandaloneElement>>
                gapAssignments = ArrangeGroupsStandaloneLane.assignToGaps(qualifiers, orderedGroups);
        assertEquals("Single gap (between 2 groups) → hub assigned to gap 0",
                1, gapAssignments.size());
        assertTrue("Gap 0 holds the hub", gapAssignments.containsKey(0));

        // Virtual bounds (simulate computeGroupedLayoutPass post-resize):
        // groups grew to 240x180 after their internal layout pass.
        List<int[]> virtualGroupDims = List.of(
                new int[]{240, 180}, new int[]{240, 180});
        int spacing = 30;
        List<Integer> laneSizes = ArrangeGroupsStandaloneLane.computeLaneSizes(
                gapAssignments, orderedGroups.size(), spacing, true);
        // lane size = hub.width + 2*spacing = 260 + 60 = 320
        assertEquals(1, laneSizes.size());
        assertEquals(320, (int) laneSizes.get(0));

        // Positions assuming row layout starting at (20, 20):
        // Group 0 at (20, 20); Group 1 at (20 + 240 + 320, 20) = (580, 20)
        List<int[]> positions = List.of(
                new int[]{20, 20}, new int[]{580, 20});
        List<ArrangeGroupsStandaloneLane.QualifierPlacement> placements =
                ArrangeGroupsStandaloneLane.placeQualifiers(
                        gapAssignments, orderedGroups.size(),
                        positions, virtualGroupDims, spacing, true);
        assertEquals(1, placements.size());
        ArrangeGroupsStandaloneLane.QualifierPlacement hubP = placements.get(0);
        // Hub.x = lane.left + spacing = (20 + 240) + 30 = 290
        assertEquals("Hub.x centred horizontally with virtual-group dims", 290, hubP.x());
        // Hub.y = unionMidY - hub.height/2 = (20 + (20+180))/2 - 140 = 110 - 140 = -30
        assertEquals("Hub.y centred vertically over taller virtual groups", -30, hubP.y());
        assertEquals(260, hubP.width());
        assertEquals(280, hubP.height());
    }

    /**
     * Sonnet 4.6 code-review F-1 pin: a top-level Device wired to ≥ 2 zone groups
     * qualifies for lane placement (Device is a sibling of Node in the Archi
     * metamodel — both extend ITechnologyElement directly — so the predicate must
     * test both interfaces, not rely on subtype inheritance).
     */
    @Test
    public void arrangeGroups_topologyDeviceQualifier_placesDeviceInLane() {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;
        IArchimateModel model = factory.createArchimateModel();
        model.setName("Device Qualifier Fixture");
        model.setId("model-dev");
        model.setDefaults();

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-dev");
        view.setName("Device Qualifier View");

        IDiagramModelGroup zoneA = factory.createDiagramModelGroup();
        zoneA.setId("g-zoneA-dev"); zoneA.setName("Zone A");
        zoneA.setBounds(0, 0, 200, 150);
        IArchimateElement nodeA = factory.createNode();
        nodeA.setId("e-nodeA"); nodeA.setName("Node A");
        model.getFolder(FolderType.TECHNOLOGY).getElements().add(nodeA);
        IDiagramModelArchimateObject voNodeA = factory.createDiagramModelArchimateObject();
        voNodeA.setId("vo-nodeA");
        voNodeA.setArchimateElement(nodeA);
        voNodeA.setBounds(10, 34, 120, 55);
        zoneA.getChildren().add(voNodeA);

        IDiagramModelGroup zoneB = factory.createDiagramModelGroup();
        zoneB.setId("g-zoneB-dev"); zoneB.setName("Zone B");
        zoneB.setBounds(0, 0, 200, 150);
        IArchimateElement nodeB = factory.createNode();
        nodeB.setId("e-nodeB"); nodeB.setName("Node B");
        model.getFolder(FolderType.TECHNOLOGY).getElements().add(nodeB);
        IDiagramModelArchimateObject voNodeB = factory.createDiagramModelArchimateObject();
        voNodeB.setId("vo-nodeB");
        voNodeB.setArchimateElement(nodeB);
        voNodeB.setBounds(10, 34, 120, 55);
        zoneB.getChildren().add(voNodeB);

        // Standalone Device at top level — the qualifier under test.
        IArchimateElement deviceHub = factory.createDevice();
        deviceHub.setId("e-device-hub"); deviceHub.setName("Network Switch");
        model.getFolder(FolderType.TECHNOLOGY).getElements().add(deviceHub);
        IDiagramModelArchimateObject voDevice = factory.createDiagramModelArchimateObject();
        voDevice.setId("vo-device-hub");
        voDevice.setArchimateElement(deviceHub);
        voDevice.setBounds(500, 500, 180, 80);

        view.getChildren().add(zoneA);
        view.getChildren().add(zoneB);
        view.getChildren().add(voDevice);

        IArchimateRelationship rel1 = factory.createAssociationRelationship();
        rel1.setId("r-dev-a"); rel1.setSource(deviceHub); rel1.setTarget(nodeA);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel1);
        var conn1 = factory.createDiagramModelArchimateConnection();
        conn1.setArchimateRelationship(rel1);
        conn1.connect(voDevice, voNodeA);

        IArchimateRelationship rel2 = factory.createAssociationRelationship();
        rel2.setId("r-dev-b"); rel2.setSource(deviceHub); rel2.setTarget(nodeB);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel2);
        var conn2 = factory.createDiagramModelArchimateConnection();
        conn2.setArchimateRelationship(rel2);
        conn2.connect(voDevice, voNodeB);

        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        stubModelManager.setModels(List.of(model));
        ArchiModelAccessorImpl acc = createAccessorWithTestDispatcher(model);

        MutationResult<net.vheerden.archi.mcp.response.dto.ArrangeGroupsResultDto> result =
                acc.arrangeGroups("default", "view-dev", "topology", null, 40, null, "horizontal");

        assertNotNull(result);
        assertEquals(2, result.entity().groupsPositioned());
        assertEquals("Device must qualify alongside Node/Path/CommunicationNetwork",
                1, result.entity().standaloneElementsPlaced());

        IDiagramModelObject devicePlaced = findChildById(view, "vo-device-hub");
        IDiagramModelObject zaPlaced = findChildById(view, "g-zoneA-dev");
        IDiagramModelObject zbPlaced = findChildById(view, "g-zoneB-dev");
        int zaRight = zaPlaced.getBounds().getX() + zaPlaced.getBounds().getWidth();
        int zbLeft = zbPlaced.getBounds().getX();
        assertTrue("Device strictly between zones",
                devicePlaced.getBounds().getX() > zaRight
                        && devicePlaced.getBounds().getX() + devicePlaced.getBounds().getWidth() < zbLeft);
    }

    /**
     * Sonnet 4.6 code-review F-3 pin: topology+columns produces a 2D grid, and the
     * "between" semantics for standalone-element lane placement are not well-defined
     * for a grid. The implementation must skip qualifier classification cleanly when
     * columns is specified, producing standaloneElementsPlaced=0 (NOT silently
     * classify-then-drop). The standalone element keeps its source position.
     */
    @Test
    public void arrangeGroups_topologyWithColumns_skipsQualifierLaneCleanly() {
        // Reuse the View H fixture (with a qualifying hub Node) but pass columns=2
        // → topology rewrites to grid; qualifier classification must be skipped.
        IArchimateModel model = createViewHHubAndSpokeFixture(
                200, 150, 200, 150, 260, 280);
        stubModelManager.setModels(List.of(model));
        ArchiModelAccessorImpl acc = createAccessorWithTestDispatcher(model);

        int originalHubX = 500;
        int originalHubY = 500;

        MutationResult<net.vheerden.archi.mcp.response.dto.ArrangeGroupsResultDto> result =
                acc.arrangeGroups("default", "view-vh", "topology", 2, 40, null, "horizontal");

        assertNotNull(result);
        // Grid path engaged
        assertEquals("topology", result.entity().arrangement());
        assertEquals(Integer.valueOf(2), result.entity().columnsUsed());
        // Hub NOT placed in a lane (no lane in grid layout)
        assertEquals("topology+columns → grid: qualifier classification skipped, no placements",
                0, result.entity().standaloneElementsPlaced());

        // Hub stays at its source position (no UpdateViewObjectCommand emitted for it)
        IArchimateDiagramModel view = (IArchimateDiagramModel)
                model.getFolder(FolderType.DIAGRAMS).getElements().get(0);
        IDiagramModelObject hub = findChildById(view, "vo-hub");
        assertNotNull(hub);
        assertEquals("Hub source x unchanged", originalHubX, hub.getBounds().getX());
        assertEquals("Hub source y unchanged", originalHubY, hub.getBounds().getY());
    }

    /** Helper: find a view child by id. Used by lane-placement assertions. */
    private static IDiagramModelObject findChildById(
            IArchimateDiagramModel view, String id) {
        for (IDiagramModelObject child : view.getChildren()) {
            if (id.equals(child.getId())) return child;
        }
        return null;
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

    // ---- Folder-layer validation tests ----

    @Test
    public void shouldThrowFolderLayerMismatch_whenJunctionInApplicationFolder() {
        // The literal dogfood bug: a Junction (an "Other"-folder concept) placed under the
        // Application layer was silently accepted, then made the model un-saveable in Archi.
        IArchimateModel model = createTestModelWithDefaultFolders();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        IArchimateConcept junction = IArchimateFactory.eINSTANCE.createJunction();
        IFolder appRoot = model.getFolder(FolderType.APPLICATION);

        try {
            accessor.validateFolderLayerMatch(junction, appRoot);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.FOLDER_LAYER_MISMATCH, e.getErrorCode());
            assertTrue(e.getMessage().contains("Junction"));
            assertTrue(e.getMessage().contains("Other"));
            assertTrue(e.getMessage().contains("Application"));
        }
    }

    @Test
    public void shouldSucceedValidation_whenJunctionInOtherFolder() {
        IArchimateModel model = createTestModelWithDefaultFolders();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        IArchimateConcept junction = IArchimateFactory.eINSTANCE.createJunction();
        IFolder otherRoot = model.getFolder(FolderType.OTHER);

        // Should not throw — the Other folder IS the Junction's governing folder.
        accessor.validateFolderLayerMatch(junction, otherRoot);
    }

    @Test
    public void shouldThrowFolderLayerMismatch_whenGroupingInBusinessFolder() {
        IArchimateModel model = createTestModelWithDefaultFolders();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        IArchimateConcept grouping = IArchimateFactory.eINSTANCE.createGrouping();
        IFolder businessRoot = model.getFolder(FolderType.BUSINESS);

        try {
            accessor.validateFolderLayerMatch(grouping, businessRoot);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.FOLDER_LAYER_MISMATCH, e.getErrorCode());
            assertTrue(e.getMessage().contains("Grouping"));
        }
    }

    @Test
    public void shouldThrowFolderLayerMismatch_whenLocationInBusinessFolder() {
        IArchimateModel model = createTestModelWithDefaultFolders();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        IArchimateConcept location = IArchimateFactory.eINSTANCE.createLocation();
        IFolder businessRoot = model.getFolder(FolderType.BUSINESS);

        try {
            accessor.validateFolderLayerMatch(location, businessRoot);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.FOLDER_LAYER_MISMATCH, e.getErrorCode());
            assertTrue(e.getMessage().contains("Location"));
            assertTrue(e.getMessage().contains("Other"));
        }
    }

    @Test
    public void shouldThrowFolderLayerMismatch_whenRelationshipInBusinessFolder() {
        // Relationships belong in the Relations folder; delegating to Archi's own picker
        // means the validator covers relationships for free.
        IArchimateModel model = createTestModelWithDefaultFolders();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        IArchimateConcept rel = IArchimateFactory.eINSTANCE.createAssociationRelationship();
        IFolder businessRoot = model.getFolder(FolderType.BUSINESS);

        try {
            accessor.validateFolderLayerMatch(rel, businessRoot);
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.FOLDER_LAYER_MISMATCH, e.getErrorCode());
            assertTrue(e.getMessage().contains("Relations"));
        }
    }

    @Test
    public void shouldSucceedValidation_whenRelationshipInRelationsFolder() {
        IArchimateModel model = createTestModelWithDefaultFolders();
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        IArchimateConcept rel = IArchimateFactory.eINSTANCE.createAssociationRelationship();
        IFolder relationsRoot = model.getFolder(FolderType.RELATIONS);

        // Should not throw — relationships belong in the Relations folder.
        accessor.validateFolderLayerMatch(rel, relationsRoot);
    }

    @Test
    public void shouldThrowFolderLayerMismatch_whenMovingElementToWrongLayer() {
        // move-to-folder must reject the same violation create-element does (sibling symmetry).
        IArchimateModel model = createTestModelWithDefaultFolders();
        IArchimateElement actor = IArchimateFactory.eINSTANCE.createBusinessActor();
        actor.setId("move-actor-1");
        actor.setName("Actor");
        model.getFolder(FolderType.BUSINESS).getElements().add(actor);
        IFolder appSub = IArchimateFactory.eINSTANCE.createFolder();
        appSub.setName("App Sub");
        appSub.setType(FolderType.USER);
        appSub.setId("app-sub-1");
        model.getFolder(FolderType.APPLICATION).getFolders().add(appSub);
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        try {
            accessor.prepareMoveToFolder("move-actor-1", "app-sub-1");
            fail("Expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.FOLDER_LAYER_MISMATCH, e.getErrorCode());
            assertTrue(e.getMessage().contains("BusinessActor"));
        }
    }

    @Test
    public void shouldSucceedMove_whenElementStaysWithinCorrectLayer() {
        IArchimateModel model = createTestModelWithDefaultFolders();
        IArchimateElement actor = IArchimateFactory.eINSTANCE.createBusinessActor();
        actor.setId("move-actor-2");
        actor.setName("Actor");
        model.getFolder(FolderType.BUSINESS).getElements().add(actor);
        IFolder bizSub = IArchimateFactory.eINSTANCE.createFolder();
        bizSub.setName("People");
        bizSub.setType(FolderType.USER);
        bizSub.setId("biz-sub-1");
        model.getFolder(FolderType.BUSINESS).getFolders().add(bizSub);
        stubModelManager.setModels(List.of(model));
        accessor = new ArchiModelAccessorImpl(stubModelManager);

        // Should not throw — staying within the Business layer.
        accessor.prepareMoveToFolder("move-actor-2", "biz-sub-1");
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

    // ---- computeOptimizeGroupOrderPass / computeAutoRoutePass tests ----

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

    // ---- findLimitingFactor / getRemediation ----

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
        // Verify all 16 known factors produce non-null, distinct remediation texts
        // (existing 6; new 8 for the M6-tiers realignment;
        //  +2 cleanup: coincidentSegments + nonOrthogonalTerminals — code-review finding 2026-04-29)
        String[] factors = {"labelOverlaps", "overlaps", "edgeCrossings",
                "passThroughs", "spacing", "alignment",
                "boundaryViolations", "parentLabelObscured", "offCanvas", "labelTruncations",
                "interiorTerminations", "zigzags", "connectionEdgeCoincidence", "hubPortQuality",
                "coincidentSegments", "nonOrthogonalTerminals"};
        String defaultText = ArchiModelAccessorImpl.getRemediation("unknownMetric");
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String factor : factors) {
            String remediation = ArchiModelAccessorImpl.getRemediation(factor);
            assertNotNull("Remediation for " + factor + " should not be null", remediation);
            assertNotEquals("Remediation for " + factor + " must not fall back to default",
                    defaultText, remediation);
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
                0, List.of(), false, 0, 0, null,
                0, List.of(), 0, List.of(), 0, List.of(), null, List.of(),
                // Assessor.Redesign M2-M6 (defaults — test does not exercise new metrics)
                0, List.of(), 0, List.of(), 0, List.of(), 1.0, List.of(),
                "fair", "fair",
                // R8 (defaults — test does not exercise new metric)
                1.0, List.of());
    }

    /**
     * Builds a minimal AssessLayoutResultDto for tier-weighted score tests.
     * Accepts all Tier-1/2/3 metric fields needed for scoring and veto tests.
     * <p>5-arg overload: existing earlier callers — M6 metrics + Tier-1L promotions
     * default to "no defects" (interiorTerminationCount=0, zigzagCount=0,
     * connectionEdgeCoincidenceCount=0, hubPortQualityScore=1.0,
     * boundaryViolations=[], parentLabelObscuredCount=0, labelOverlapCount=0,
     * labelTruncationCount=0). Existing tests' weight-arithmetic is unchanged in
     * structure but assertion values are re-baselined for the M6-tiers realignment.
     */
    private AssessLayoutResultDto buildScoringAssessment(
            int overlapCount, List<String> connectionPassThroughs,
            int coincidentSegmentCount, int edgeCrossingCount,
            int nonOrthogonalTerminalCount) {
        return buildScoringAssessmentWithM6(
                overlapCount, connectionPassThroughs,
                coincidentSegmentCount, edgeCrossingCount, nonOrthogonalTerminalCount,
                0, 0, 0, 1.0,
                List.of(), 0,
                0, 0);
    }

    /**
     * Builds a minimal AssessLayoutResultDto for M6-aware tier-weighted score + veto tests.
     * Accepts every metric input the M6 weight schedule + Tier-1 veto consume.
     */
    private AssessLayoutResultDto buildScoringAssessmentWithM6(
            int overlapCount, List<String> connectionPassThroughs,
            int coincidentSegmentCount, int edgeCrossingCount,
            int nonOrthogonalTerminalCount,
            int interiorTerminationCount, int zigzagCount,
            int connectionEdgeCoincidenceCount, double hubPortQualityScore,
            List<String> boundaryViolations, int parentLabelObscuredCount,
            int labelOverlapCount, int labelTruncationCount) {
        return new AssessLayoutResultDto(
                "v-1", 5, 3,
                overlapCount, 0, edgeCrossingCount, 0.0,
                50.0, 80, "fair", Map.of(),
                List.of(), boundaryViolations, connectionPassThroughs, List.of(),
                labelOverlapCount, List.of(), 0, List.of(),
                0, List.of(), false, coincidentSegmentCount, nonOrthogonalTerminalCount, null,
                labelTruncationCount, List.of(), parentLabelObscuredCount, List.of(),
                0, List.of(), null, List.of(),
                // Assessor.Redesign M2-M6
                interiorTerminationCount, List.of(), zigzagCount, List.of(),
                connectionEdgeCoincidenceCount, List.of(), hubPortQualityScore, List.of(),
                "fair", "fair",
                // R8 (defaults — test does not exercise new metric)
                1.0, List.of());
    }

    // ---- tierWeightedScore / hasTier1Regression ----

    @Test
    public void tierWeightedScore_shouldWeightTier1HigherThanTier2() {
        // M6 weights: 1 overlap (Tier 1L weight 10) = score 10;
        //             4 crossings (Tier 3R weight 1 under M6) = score 4.
        // Tier 1L single overlap should outscore 4 Tier 3R crossings.
        AssessLayoutResultDto oneOverlap = buildScoringAssessment(1, List.of(), 0, 0, 0);
        AssessLayoutResultDto fourCrossings = buildScoringAssessment(0, List.of(), 0, 4, 0);
        assertTrue("1 overlap (10) should score higher than 4 crossings (4) under M6",
                ArchiModelAccessorImpl.tierWeightedScore(oneOverlap)
                        > ArchiModelAccessorImpl.tierWeightedScore(fourCrossings));
    }

    @Test
    public void tierWeightedScore_shouldHandleNullPassThroughs() {
        // null connectionPassThroughs should be treated as 0
        AssessLayoutResultDto nullPt = buildScoringAssessment(0, null, 0, 5, 0);
        assertEquals(5, ArchiModelAccessorImpl.tierWeightedScore(nullPt)); // 5 crossings * 1 (M6)
    }

    @Test
    public void tierWeightedScore_shouldIncludeAllTiers() {
        // M6 weights: 2 overlaps(20) + 1 PT(8) + 3 coincident(18) + 4 nonOrth(12) + 5 crossings(5) = 63
        // (nonOrth promoted to Tier 2R ×3; crossings demoted to Tier 3R ×1)
        AssessLayoutResultDto all = buildScoringAssessment(2, List.of("pt1"), 3, 5, 4);
        assertEquals(63, ArchiModelAccessorImpl.tierWeightedScore(all));
    }

    @Test
    public void hasTier1Regression_shouldReturnTrue_whenPTIncreases() {
        // PT 0→1, crossings decrease 10→5 — veto should still fire
        AssessLayoutResultDto current = buildScoringAssessment(0, List.of("pt1"), 0, 5, 0);
        AssessLayoutResultDto best = buildScoringAssessment(0, List.of(), 0, 10, 0);
        assertTrue("PT increase should trigger veto even with fewer crossings",
                ArchiModelAccessorImpl.hasTier1Regression(current, best));
    }

    @Test
    public void hasTier1Regression_shouldReturnFalse_whenPTSameAndCrossingsDecrease() {
        // PT stays 2→2, crossings decrease 10→5 — no veto
        AssessLayoutResultDto current = buildScoringAssessment(0, List.of("a", "b"), 0, 5, 0);
        AssessLayoutResultDto best = buildScoringAssessment(0, List.of("a", "b"), 0, 10, 0);
        assertFalse("Same PT count with fewer crossings should not veto",
                ArchiModelAccessorImpl.hasTier1Regression(current, best));
    }

    @Test
    public void hasTier1Regression_shouldReturnFalse_whenBestIsNull() {
        // First iteration — no baseline to regress against
        AssessLayoutResultDto current = buildScoringAssessment(1, List.of("pt1"), 2, 5, 3);
        assertFalse("Null best (first iteration) should never veto",
                ArchiModelAccessorImpl.hasTier1Regression(current, null));
    }

    @Test
    public void hasTier1Regression_shouldDetectOverlapRegression() {
        AssessLayoutResultDto current = buildScoringAssessment(3, List.of(), 0, 0, 0);
        AssessLayoutResultDto best = buildScoringAssessment(2, List.of(), 0, 0, 0);
        assertTrue("Overlap increase should trigger veto",
                ArchiModelAccessorImpl.hasTier1Regression(current, best));
    }

    @Test
    public void hasTier1Regression_shouldDetectCoincidentRegression() {
        AssessLayoutResultDto current = buildScoringAssessment(0, List.of(), 5, 0, 0);
        AssessLayoutResultDto best = buildScoringAssessment(0, List.of(), 3, 0, 0);
        assertTrue("Coincident segment increase should trigger veto",
                ArchiModelAccessorImpl.hasTier1Regression(current, best));
    }

    @Test
    public void hasTier1Regression_shouldHandleNullPassThroughsOnBothSides() {
        // Both null — no regression
        AssessLayoutResultDto current = buildScoringAssessment(0, null, 0, 5, 0);
        AssessLayoutResultDto best = buildScoringAssessment(0, null, 0, 10, 0);
        assertFalse("Both null PT should not veto",
                ArchiModelAccessorImpl.hasTier1Regression(current, best));
    }

    // ---- M6 weight-coverage ----

    @Test
    public void tierWeightedScore_shouldWeightInteriorTerminationsAsTier1R() {
        // 3 interior terminations × Tier 1R weight 8 = 24
        AssessLayoutResultDto threeInterior = buildScoringAssessmentWithM6(
                0, List.of(), 0, 0, 0,
                3, 0, 0, 1.0,
                List.of(), 0,
                0, 0);
        assertEquals(24, ArchiModelAccessorImpl.tierWeightedScore(threeInterior));
    }

    @Test
    public void tierWeightedScore_shouldWeightZigzagsAsTier1R() {
        // 2 zigzags × Tier 1R weight 8 = 16
        AssessLayoutResultDto twoZigzags = buildScoringAssessmentWithM6(
                0, List.of(), 0, 0, 0,
                0, 2, 0, 1.0,
                List.of(), 0,
                0, 0);
        assertEquals(16, ArchiModelAccessorImpl.tierWeightedScore(twoZigzags));
    }

    @Test
    public void tierWeightedScore_shouldWeightConnectionEdgeCoincidenceAsTier2R() {
        // 4 edge-coincidences × Tier 2R weight 3 = 12
        AssessLayoutResultDto fourEdgeCoinc = buildScoringAssessmentWithM6(
                0, List.of(), 0, 0, 0,
                0, 0, 4, 1.0,
                List.of(), 0,
                0, 0);
        assertEquals(12, ArchiModelAccessorImpl.tierWeightedScore(fourEdgeCoinc));
    }

    @Test
    public void tierWeightedScore_shouldWeightLowHubPortQualityAsBinaryTier2R() {
        // hubPortQualityScore=0.25 (below FAIR threshold 0.5) → 1 × weight 2 = 2
        AssessLayoutResultDto lowHubPort = buildScoringAssessmentWithM6(
                0, List.of(), 0, 0, 0,
                0, 0, 0, 0.25,
                List.of(), 0,
                0, 0);
        assertEquals(2, ArchiModelAccessorImpl.tierWeightedScore(lowHubPort));

        // hubPortQualityScore=0.75 (above FAIR threshold) → 0 × weight 2 = 0
        AssessLayoutResultDto goodHubPort = buildScoringAssessmentWithM6(
                0, List.of(), 0, 0, 0,
                0, 0, 0, 0.75,
                List.of(), 0,
                0, 0);
        assertEquals(0, ArchiModelAccessorImpl.tierWeightedScore(goodHubPort));
    }

    // ---- M6 veto-coverage ----

    @Test
    public void hasTier1Regression_shouldDetectInteriorTerminationRegression() {
        // M2 interiorTermination 0 → 1 should veto (Tier 1R)
        AssessLayoutResultDto current = buildScoringAssessmentWithM6(
                0, List.of(), 0, 0, 0,
                1, 0, 0, 1.0,
                List.of(), 0,
                0, 0);
        AssessLayoutResultDto best = buildScoringAssessment(0, List.of(), 0, 0, 0);
        assertTrue("Interior termination regression should trigger Tier 1R veto",
                ArchiModelAccessorImpl.hasTier1Regression(current, best));
    }

    @Test
    public void hasTier1Regression_shouldDetectZigzagRegression() {
        // M3 zigzag 0 → 1 should veto (Tier 1R)
        AssessLayoutResultDto current = buildScoringAssessmentWithM6(
                0, List.of(), 0, 0, 0,
                0, 1, 0, 1.0,
                List.of(), 0,
                0, 0);
        AssessLayoutResultDto best = buildScoringAssessment(0, List.of(), 0, 0, 0);
        assertTrue("Zigzag regression should trigger Tier 1R veto",
                ArchiModelAccessorImpl.hasTier1Regression(current, best));
    }

    @Test
    public void hasTier1Regression_shouldDetectBoundaryViolationRegression() {
        // boundaryViolations [] → ["v1"] should veto (Tier 1L)
        AssessLayoutResultDto current = buildScoringAssessmentWithM6(
                0, List.of(), 0, 0, 0,
                0, 0, 0, 1.0,
                List.of("v1"), 0,
                0, 0);
        AssessLayoutResultDto best = buildScoringAssessment(0, List.of(), 0, 0, 0);
        assertTrue("Boundary violation regression should trigger Tier 1L veto",
                ArchiModelAccessorImpl.hasTier1Regression(current, best));
    }

    @Test
    public void hasTier1Regression_shouldDetectParentLabelObscuredRegression() {
        // parentLabelObscured 0 → 1 should veto (Tier 1L promoted)
        AssessLayoutResultDto current = buildScoringAssessmentWithM6(
                0, List.of(), 0, 0, 0,
                0, 0, 0, 1.0,
                List.of(), 1,
                0, 0);
        AssessLayoutResultDto best = buildScoringAssessment(0, List.of(), 0, 0, 0);
        assertTrue("Parent-label-obscured regression should trigger Tier 1L veto",
                ArchiModelAccessorImpl.hasTier1Regression(current, best));
    }

    @Test
    public void hasTier1Regression_shouldNotDetectTier2RRegressions() {
        // Negative confirmation: Tier 2R + Tier 3R regressions must NOT veto.
        // nonOrth 0→5, edgeCoincidence 0→5, labelOverlap 0→5, edgeCrossings 0→100;
        // every Tier-1 metric flat between current and best.
        AssessLayoutResultDto current = buildScoringAssessmentWithM6(
                0, List.of(), 0, 100, 5,
                0, 0, 5, 1.0,
                List.of(), 0,
                5, 0);
        AssessLayoutResultDto best = buildScoringAssessmentWithM6(
                0, List.of(), 0, 0, 0,
                0, 0, 0, 1.0,
                List.of(), 0,
                0, 0);
        assertFalse("Tier 2R / Tier 3R regressions must NOT veto — Tier 1 only",
                ArchiModelAccessorImpl.hasTier1Regression(current, best));
    }

    // ---- getMetricCount mapping ----

    @Test
    public void getMetricCount_shouldReturnCorrectCount_forEachFactor() {
        // Build an assessment with known counts for every factor
        // Fields: viewId, elementCount, connectionCount,
        //   overlapCount, containmentOverlaps, edgeCrossingCount, crossingsPerConnection,
        //   averageSpacing, alignmentScore, overallRating, ratingBreakdown,
        //   overlaps, boundaryViolations, connectionPassThroughs, offCanvasWarnings,
        //   labelOverlapCount, labelOverlaps, orphanedConnections, orphanedConnectionDescriptions,
        //   noteOverlapCount, noteOverlapDescriptions, hasGroups,
        //   coincidentSegmentCount, nonOrthogonalTerminalCount, contentBounds,
        //   labelTruncationCount, labelTruncations, parentLabelObscuredCount,
        //   parentLabelObscuredDescriptions, imageSiblingOverlapCount,
        //   imageSiblingOverlapDescriptions, violatorIds, suggestions
        AssessLayoutResultDto assessment = new AssessLayoutResultDto(
                "v-1", 10, 8,
                3, 0, 7, 0.0,
                50.0, 80, "fair", Map.of(),
                List.of(), List.of("v1"),
                List.of("pt1", "pt2"), List.of("o1", "o2"),
                5, List.of(), 0, List.of(),
                0, List.of(), false,
                4, 6, null,
                3, List.of(), 2, List.of(), 0, List.of(), null, List.of(),
                // Assessor.Redesign M2-M6 — non-zero values exercise the new mappings
                4, List.of(), 5, List.of(), 6, List.of(), 0.25, List.of(),
                "fair", "fair",
                // R8 (defaults — test does not exercise new metric)
                1.0, List.of());

        assertEquals(3, ArchiModelAccessorImpl.getMetricCount("overlaps", assessment));
        assertEquals(7, ArchiModelAccessorImpl.getMetricCount("edgeCrossings", assessment));
        assertEquals(2, ArchiModelAccessorImpl.getMetricCount("passThroughs", assessment));
        assertEquals(5, ArchiModelAccessorImpl.getMetricCount("labelOverlaps", assessment));
        assertEquals(4, ArchiModelAccessorImpl.getMetricCount("coincidentSegments", assessment));
        assertEquals(6, ArchiModelAccessorImpl.getMetricCount("nonOrthogonalTerminals", assessment));
        assertEquals(0, ArchiModelAccessorImpl.getMetricCount("spacing", assessment));
        assertEquals(0, ArchiModelAccessorImpl.getMetricCount("alignment", assessment));

        // M6 + Tier-1L promotions
        assertEquals(1, ArchiModelAccessorImpl.getMetricCount("boundaryViolations", assessment));
        assertEquals(2, ArchiModelAccessorImpl.getMetricCount("parentLabelObscured", assessment));
        assertEquals(2, ArchiModelAccessorImpl.getMetricCount("offCanvas", assessment));
        assertEquals(3, ArchiModelAccessorImpl.getMetricCount("labelTruncations", assessment));
        assertEquals(4, ArchiModelAccessorImpl.getMetricCount("interiorTerminations", assessment));
        assertEquals(5, ArchiModelAccessorImpl.getMetricCount("zigzags", assessment));
        assertEquals(6, ArchiModelAccessorImpl.getMetricCount("connectionEdgeCoincidence", assessment));
        // hubPortQualityScore=0.25 < FAIR threshold (0.5) → binary 1
        assertEquals(1, ArchiModelAccessorImpl.getMetricCount("hubPortQuality", assessment));
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
                // Recursive decomposition: bulk-mutate produces a NonNotifyingCompoundCommand
                // that may itself contain inner NonNotifyingCompoundCommand instances when
                // create-element / create-relationship use inline specializations
                // (ApplySpecializationCommand + CreateXxxCommand pair). Calling .execute()
                // directly on the inner compound would re-trigger IEditorModelManager's
                // static-init bomb. Recurse so we always reach leaf commands.
                if (command instanceof CompoundCommand compound) {
                    for (Object cmd : compound.getCommands()) {
                        executeDecomposed((Command) cmd);
                    }
                } else {
                    command.execute();
                }
            }
        };
        // MutationDispatcher's default approvalModeProvider is fail-safe () -> true
        // (approval ON). In production the OSGi bootstrap replaces it with the human-owned
        // ApprovalMode reader; here we mirror the GUI-attached default (approval OFF) so mutation
        // tests execute immediately. Approval-mode tests override this with () -> true explicitly.
        testDispatcher.setApprovalModeProvider(() -> false);
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

    // ---- Orphaned Relationship Structural Fix tests ----

    @Test
    public void shouldSkipOrphanedRelationship_whenAutoConnecting() {
        // auto-connect containment guard
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
        // auto-connect-view tool containment guard (distinct from addToView autoConnect)
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
                "default", "view-001", null, null, null, null);

        assertNotNull(result);
        // Only the contained relationship (rel-001: ac-001 -> bp-001) should produce a connection
        assertEquals(1, result.entity().connectionsCreated());
    }

    @Test
    public void shouldHandleOrphanedRelationship_whenDeletingElement() {
        // delete element NPE guard
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
        // deferred connect — verify via createRelationship
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
                    "ba-001", "bp-001", "test-assoc", null);

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

    // ---- resizeElementsToFit tests ----

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

    // ---- Dynamic label height / child shift tests ----

    @Test
    public void resizeElementsToFit_shouldShiftChildrenDown_whenParentLabelMultiLine() {
        // Parent "Payment Processing Engine" at narrow width → multi-line label
        // Child at y=25 should be shifted down to clear the label area
        IArchimateModel model = createTestModelForResizeB50();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Add parent at narrow width (142px) — forces multi-line label wrap
        MutationResult<AddToViewResultDto> parentResult =
                accessor.addToView("default", "view-resize-b50", "el-multiline-parent", 50, 50, 142, 200, false, null, null, null);
        String parentVoId = parentResult.entity().viewObject().viewObjectId();

        // Add child inside parent at y=25 (relative to parent) — overlaps multi-line label
        accessor.addToView("default", "view-resize-b50", "el-child", 10, 25, null, null, false, parentVoId, null, null);

        MutationResult<ResizeElementsResultDto> result =
                accessor.resizeElementsToFit("default", "view-resize-b50", null);

        assertNotNull(result);

        // Verify the child was shifted: find parent view object and check child y
        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS).getElements().get(0);
        IDiagramModelArchimateObject parentVo = findViewObject(view, parentVoId);
        assertNotNull("Parent view object should exist", parentVo);

        // Child should have been shifted down — y should be > 25
        IDiagramModelArchimateObject childVo =
                (IDiagramModelArchimateObject) parentVo.getChildren().get(0);
        assertTrue("Child should be shifted down from y=25, actual y=" + childVo.getBounds().getY(),
                childVo.getBounds().getY() > 25);
    }

    @Test
    public void resizeElementsToFit_shouldNotShiftChildren_whenParentLabelSingleLine() {
        // Short parent name at wide width → single line → ~25px label height
        // Child at y=25 should NOT be shifted
        IArchimateModel model = createTestModelForResizeB50();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Add parent with short name at wide width
        MutationResult<AddToViewResultDto> parentResult =
                accessor.addToView("default", "view-resize-b50", "el-short-parent", 50, 50, 250, 200, false, null, null, null);
        String parentVoId = parentResult.entity().viewObject().viewObjectId();

        // Add child at y=25 — should be fine for single-line label
        accessor.addToView("default", "view-resize-b50", "el-child", 10, 25, null, null, false, parentVoId, null, null);

        MutationResult<ResizeElementsResultDto> result =
                accessor.resizeElementsToFit("default", "view-resize-b50", null);

        assertNotNull(result);

        // Child y should remain at 25 (no shift needed for single-line label)
        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS).getElements().get(0);
        IDiagramModelArchimateObject parentVo = findViewObject(view, parentVoId);
        assertNotNull(parentVo);
        IDiagramModelArchimateObject childVo =
                (IDiagramModelArchimateObject) parentVo.getChildren().get(0);
        assertEquals("Child should stay at y=25 for single-line parent label",
                25, childVo.getBounds().getY());
    }

    @Test
    public void resizeElementsToFit_shouldHandleNestedContainment_withDynamicLabelHeight() {
        // Grandparent with multi-line label → inner parent also gets dynamic height
        IArchimateModel model = createTestModelForResizeB50();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Add grandparent with multi-line name at narrow width
        MutationResult<AddToViewResultDto> grandparentResult =
                accessor.addToView("default", "view-resize-b50", "el-multiline-parent", 50, 50, 142, 300, false, null, null, null);
        String grandparentVoId = grandparentResult.entity().viewObject().viewObjectId();

        // Add inner parent inside grandparent at y=25 (may overlap grandparent's multi-line label)
        MutationResult<AddToViewResultDto> innerParentResult =
                accessor.addToView("default", "view-resize-b50", "el-short-parent", 10, 25, 120, 100, false, grandparentVoId, null, null);
        String innerParentVoId = innerParentResult.entity().viewObject().viewObjectId();

        // Add leaf child inside inner parent
        accessor.addToView("default", "view-resize-b50", "el-child", 10, 25, null, null, false, innerParentVoId, null, null);

        MutationResult<ResizeElementsResultDto> result =
                accessor.resizeElementsToFit("default", "view-resize-b50", null);

        assertNotNull(result);

        // Verify grandparent's inner parent was shifted down (grandparent has multi-line label)
        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS).getElements().get(0);
        IDiagramModelArchimateObject grandparentVo = findViewObject(view, grandparentVoId);
        assertNotNull("Grandparent should exist", grandparentVo);

        // Inner parent (child of grandparent) should have been shifted if grandparent label is multi-line
        IDiagramModelArchimateObject innerParentVo = findViewObject(view, innerParentVoId);
        assertNotNull("Inner parent should exist", innerParentVo);
        assertTrue("Inner parent should be shifted down from y=25, actual y=" + innerParentVo.getBounds().getY(),
                innerParentVo.getBounds().getY() >= 25);
    }

    @Test
    public void resizeElementsToFit_shouldNeverShrinkParentHeight() {
        // Parent already taller than needed → height must not decrease
        IArchimateModel model = createTestModelForResizeB50();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Add parent with generous height (500px)
        MutationResult<AddToViewResultDto> parentResult =
                accessor.addToView("default", "view-resize-b50", "el-short-parent", 50, 50, 250, 500, false, null, null, null);
        String parentVoId = parentResult.entity().viewObject().viewObjectId();

        // Add small child
        accessor.addToView("default", "view-resize-b50", "el-child", 10, 30, 80, 40, false, parentVoId, null, null);

        MutationResult<ResizeElementsResultDto> result =
                accessor.resizeElementsToFit("default", "view-resize-b50", null);

        assertNotNull(result);

        // Parent height should not have shrunk below 500
        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS).getElements().get(0);
        IDiagramModelArchimateObject parentVo = findViewObject(view, parentVoId);
        assertNotNull(parentVo);
        assertTrue("Parent height should not shrink below original 500, actual=" + parentVo.getBounds().getHeight(),
                parentVo.getBounds().getHeight() >= 500);
    }

    private IDiagramModelArchimateObject findViewObject(IArchimateDiagramModel view, String viewObjectId) {
        for (IDiagramModelObject obj : view.getChildren()) {
            if (obj instanceof IDiagramModelArchimateObject ao && ao.getId().equals(viewObjectId)) {
                return ao;
            }
        }
        // Search nested
        for (IDiagramModelObject obj : view.getChildren()) {
            if (obj instanceof IDiagramModelArchimateObject ao) {
                for (IDiagramModelObject child : ao.getChildren()) {
                    if (child instanceof IDiagramModelArchimateObject cao && cao.getId().equals(viewObjectId)) {
                        return cao;
                    }
                }
            }
        }
        return null;
    }

    private IArchimateModel createTestModelForResizeB50() {
        IArchimateFactory factory = IArchimateFactory.eINSTANCE;

        IArchimateModel model = factory.createArchimateModel();
        model.setName("Resize B50 Test Model");
        model.setId("model-resize-b50");
        model.setDefaults();

        // Multi-line parent: "Payment Processing Engine" wraps at narrow widths
        IApplicationComponent multiLineParent = factory.createApplicationComponent();
        multiLineParent.setId("el-multiline-parent");
        multiLineParent.setName("Payment Processing Engine");
        model.getFolder(FolderType.APPLICATION).getElements().add(multiLineParent);

        // Short-name parent: "API Gateway" fits single line
        IApplicationComponent shortParent = factory.createApplicationComponent();
        shortParent.setId("el-short-parent");
        shortParent.setName("API Gateway");
        model.getFolder(FolderType.APPLICATION).getElements().add(shortParent);

        // Child element
        IApplicationComponent child = factory.createApplicationComponent();
        child.setId("el-child");
        child.setName("Worker Service");
        model.getFolder(FolderType.APPLICATION).getElements().add(child);

        // View
        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-resize-b50");
        view.setName("Resize B50 Test View");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        return model;
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

    // ============================================================
    // W2 — cloud-icon container-node-collision (2026-05-20).
    // Accessor-level integration pins for the icon-band parent-resize
    // lever at the CREATION moment (`prepareAddToView`) AND
    // the MUTATION moment (`prepareUpdateViewObject`), covering
    // Case A + Case B.
    // ============================================================

    @Test
    public void w2_creationMoment_growsParentByIconBandWhenChildOccupiesBottomLeft() {
        // CREATION pin (Option A path): parent has `imagePosition:bottom-left`
        // set at creation; child is added with `parentViewObjectId = parent.id`
        // and lands in the bottom-left corner → parent grows by ICON_BAND_HEIGHT.
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Parent container — group with bottom-left icon, 200×100.
        MutationResult<ViewGroupDto> parentResult = accessor.addGroupToView(
                "default", "view-001", "Container", 50, 50, 200, 100,
                null, null, new ImageParams(null, "bottom-left", null));
        String parentId = parentResult.entity().viewObjectId();

        // New child positioned in the bottom-left band (parent-relative coords).
        accessor.addToView("default", "view-001", "ba-001",
                10, 80, 30, 15, false, parentId, null, null);

        IDiagramModelGroup parent = (IDiagramModelGroup)
                com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, parentId);
        // Parent grew by exactly ICON_BAND_HEIGHT (24). Explicit assertEquals,
        // NOT assertTrue(>=), so a silent `max(...)` regression cannot pass.
        assertEquals("W2 CREATION moment: parent grows by ICON_BAND_HEIGHT (=24)",
                100 + ImageHelper.ICON_BAND_HEIGHT,
                parent.getBounds().getHeight());
        // X/Y/Width untouched — only height grew.
        assertEquals(50, parent.getBounds().getX());
        assertEquals(50, parent.getBounds().getY());
        assertEquals(200, parent.getBounds().getWidth());
    }

    @Test
    public void w2_mutationMoment_growsParentByIconBandWhenSettingBottomLeftOnContainerWithChildren() {
        // MUTATION pin (Option A path): parent already has a child placed;
        // a later update-view-object call sets `imagePosition:bottom-left`
        // → the MUTATION-moment lever grows the parent by ICON_BAND_HEIGHT.
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Parent — group with NO image, 200×100.
        MutationResult<ViewGroupDto> parentResult = accessor.addGroupToView(
                "default", "view-001", "Container", 50, 50, 200, 100,
                null, null, null);
        String parentId = parentResult.entity().viewObjectId();
        // Child placed inside parent in the bottom-left band.
        accessor.addToView("default", "view-001", "ba-001",
                10, 80, 30, 15, false, parentId, null, null);

        IDiagramModelGroup parent = (IDiagramModelGroup)
                com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, parentId);
        // Sanity: before the update, parent height is still 100 (no W2 fire yet —
        // the parent has no image at creation).
        assertEquals(100, parent.getBounds().getHeight());

        // Update the parent: set imagePosition to bottom-left. NOTHING else changes.
        accessor.updateViewObject("default", parentId,
                null, null, null, null, null,
                null, new ImageParams(null, "bottom-left", null), null);

        // Parent grew by exactly ICON_BAND_HEIGHT — Case B firing path.
        assertEquals("W2 MUTATION moment: parent grows by ICON_BAND_HEIGHT (=24)",
                100 + ImageHelper.ICON_BAND_HEIGHT,
                parent.getBounds().getHeight());
        assertEquals(50, parent.getBounds().getX());
        assertEquals(50, parent.getBounds().getY());
        assertEquals(200, parent.getBounds().getWidth());
    }

    @Test
    public void w2_leafElementWithBottomLeftIcon_isByteIdenticalToToday() {
        // leaf no-regression pin: a leaf element with `imagePosition:bottom-left`
        // (NO `parentViewObjectId`) must produce byte-identical bounds — the W2
        // lever short-circuits because `parentContainer` resolves to the view
        // itself (not a real `IDiagramModelObject` parent).
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<AddToViewResultDto> result = accessor.addToView(
                "default", "view-001", "ba-001",
                50, 50, 120, 55, false, null, null,
                new ImageParams(null, "bottom-left", null));

        // Bit-for-bit pre-W2 bounds.
        assertEquals(50, result.entity().viewObject().x());
        assertEquals(50, result.entity().viewObject().y());
        assertEquals(120, result.entity().viewObject().width());
        assertEquals(55, result.entity().viewObject().height());
        // And the image-position is still bottom-left (round-trip readback).
        assertEquals("bottom-left", result.entity().viewObject().imagePosition());
    }

    @Test
    public void w2_containerWithoutImage_isByteIdenticalToToday_AC14CaseA() {
        // Case A pin: parent has NO image at all (no path, no position, no showIcon)
        // → the W2 lever short-circuits BEFORE the predicate even runs.
        // Parent + nested child bounds are bit-for-bit identical to today.
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> parentResult = accessor.addGroupToView(
                "default", "view-001", "Container", 50, 50, 200, 100,
                null, null, null);
        String parentId = parentResult.entity().viewObjectId();
        accessor.addToView("default", "view-001", "ba-001",
                10, 80, 30, 15, false, parentId, null, null);

        IDiagramModelGroup parent = (IDiagramModelGroup)
                com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, parentId);
        // Bit-for-bit identical — no W2 fire.
        assertEquals(50, parent.getBounds().getX());
        assertEquals(50, parent.getBounds().getY());
        assertEquals(200, parent.getBounds().getWidth());
        assertEquals(100, parent.getBounds().getHeight());
    }

    @Test
    public void w2_containerWithIconButCornerEmpty_isByteIdenticalToToday_AC14CaseB() {
        // Case B pin: parent has `imagePosition:bottom-left` AND a child
        // placed entirely in the TOP half (corner empty) → predicate returns
        // false → lever short-circuits → bounds bit-for-bit identical to today.
        // This test catches a silent `max(...)` regression: the assertion is
        // assertEquals, NOT assertTrue(>=).
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> parentResult = accessor.addGroupToView(
                "default", "view-001", "Container", 50, 50, 200, 100,
                null, null, new ImageParams(null, "bottom-left", null));
        String parentId = parentResult.entity().viewObjectId();
        // Child in the TOP half — does NOT occupy bottom-left band (band is x=0..24, y=76..100).
        accessor.addToView("default", "view-001", "ba-001",
                10, 10, 30, 30, false, parentId, null, null);

        IDiagramModelGroup parent = (IDiagramModelGroup)
                com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, parentId);
        // BYTE-IDENTICAL — height literally 100, NOT 100+ε.
        assertEquals("AC-14 Case B: child-elsewhere → byte-identical parent height",
                100, parent.getBounds().getHeight());
        assertEquals(50, parent.getBounds().getX());
        assertEquals(50, parent.getBounds().getY());
        assertEquals(200, parent.getBounds().getWidth());
    }

    @Test
    public void w2_mutationMoment_doesNotFireWhenContainerHasNoChildren() {
        // Case B at the MUTATION moment: setting `imagePosition:bottom-left`
        // on an EMPTY container (no children yet) → vacuous predicate → no fire
        // → bounds byte-identical.
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> parentResult = accessor.addGroupToView(
                "default", "view-001", "Container", 50, 50, 200, 100,
                null, null, null);
        String parentId = parentResult.entity().viewObjectId();

        // No children added. Now set imagePosition via update.
        accessor.updateViewObject("default", parentId,
                null, null, null, null, null,
                null, new ImageParams(null, "bottom-left", null), null);

        IDiagramModelGroup parent = (IDiagramModelGroup)
                com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, parentId);
        // Byte-identical — no child occupies the corner.
        assertEquals(100, parent.getBounds().getHeight());
    }

    @Test
    public void w2_creationMoment_doesNotFireForNonCornerImagePosition() {
        // byte-identical: parent with non-corner imagePosition (middle-right=5)
        // → W2 lever does NOT fire → parent bounds unchanged.
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> parentResult = accessor.addGroupToView(
                "default", "view-001", "Container", 50, 50, 200, 100,
                null, null, new ImageParams(null, "middle-right", null));
        String parentId = parentResult.entity().viewObjectId();
        accessor.addToView("default", "view-001", "ba-001",
                10, 80, 30, 15, false, parentId, null, null);

        IDiagramModelGroup parent = (IDiagramModelGroup)
                com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, parentId);
        // No W2 fire — non-corner imagePosition.
        assertEquals(100, parent.getBounds().getHeight());
    }

    @Test
    public void w2_creationMoment_doesNotFireForDefaultTopRightImagePosition() {
        // parent without any image params at all means imagePosition
        // reads back as the Archi default (2 = top-right). The W2 accessor lever
        // restricts firing to non-default corners (6, 8) — so even when a child
        // would overlap the top-right area, byte-identical bounds are preserved.
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> parentResult = accessor.addGroupToView(
                "default", "view-001", "Container", 50, 50, 200, 100,
                null, null, null);
        String parentId = parentResult.entity().viewObjectId();
        // Child in top-right area — would collide IF the lever fired on default top-right.
        accessor.addToView("default", "view-001", "ba-001",
                180, 5, 15, 15, false, parentId, null, null);

        IDiagramModelGroup parent = (IDiagramModelGroup)
                com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, parentId);
        // Byte-identical — the W2 lever does NOT fire on default top-right.
        assertEquals(100, parent.getBounds().getHeight());
    }

    @Test
    public void w2_mutationMoment_growsParentByIconBandForBottomRightIcon() {
        // symmetric to bottom-left: bottom-right (position 8) fires the
        // same lever via the same predicate — band at (parentW-24)..parentW,
        // (parentH-24)..parentH.
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> parentResult = accessor.addGroupToView(
                "default", "view-001", "Container", 50, 50, 200, 100,
                null, null, null);
        String parentId = parentResult.entity().viewObjectId();
        // Child in bottom-right corner (parent-relative): x=180..195, y=80..95.
        accessor.addToView("default", "view-001", "ba-001",
                180, 80, 15, 15, false, parentId, null, null);

        accessor.updateViewObject("default", parentId,
                null, null, null, null, null,
                null, new ImageParams(null, "bottom-right", null), null);

        IDiagramModelGroup parent = (IDiagramModelGroup)
                com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, parentId);
        assertEquals(100 + ImageHelper.ICON_BAND_HEIGHT, parent.getBounds().getHeight());
    }

    @Test
    public void w2_creationMoment_doesNotFireForTopCornerImagePosition() {
        // MVP scope: top-left (position 0) is recognised by the pure-geometry
        // predicate but the accessor-layer fires for bottom corners ONLY (would
        // require child-shift to fix top-corner — deferred). Byte-identical.
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ViewGroupDto> parentResult = accessor.addGroupToView(
                "default", "view-001", "Container", 50, 50, 200, 100,
                null, null, new ImageParams(null, "top-left", null));
        String parentId = parentResult.entity().viewObjectId();
        // Child in top-left band would collide IF top-left fired the lever.
        accessor.addToView("default", "view-001", "ba-001",
                5, 5, 30, 15, false, parentId, null, null);

        IDiagramModelGroup parent = (IDiagramModelGroup)
                com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, parentId);
        // Byte-identical — top corner deferred.
        assertEquals(100, parent.getBounds().getHeight());
    }

    @Test
    public void w2_creationMoment_AC3_grandparentGroupCascadesWhenIconBandGrowthExceedsGrandparentBounds() {
        // Review finding (adversarial review, 2026-05-20): pin that
        // the CREATION-moment lever flows through `resizeParentGroupIfNeeded` so
        // the grandparent group grows when the now-taller icon-bearing parent
        // would exceed grandparent bounds. Mirrors the cascade requirement
        // and the existing MUTATION-moment cascade behaviour.
        //
        // 3-level nesting: grandparent group → icon-bearing parent (bottom-left)
        //                                    → new child in the icon band.
        // The new child triggers the W2 lever → parent grows by ICON_BAND_HEIGHT.
        // Grandparent is sized tightly to fit parent + DEFAULT_GROUP_PADDING (10);
        // after W2, the parent's bottom edge exceeds the grandparent's bottom edge,
        // so the cascade MUST resize the grandparent as well.
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        // Grandparent group: tight bounds — 220×120 holds a 200×100 parent at (10,10) inside.
        MutationResult<ViewGroupDto> grandparentResult = accessor.addGroupToView(
                "default", "view-001", "Grandparent", 10, 10, 220, 120,
                null, null, null);
        String grandparentId = grandparentResult.entity().viewObjectId();

        // Icon-bearing parent placed inside grandparent (parentViewObjectId = grandparentId).
        MutationResult<ViewGroupDto> parentResult = accessor.addGroupToView(
                "default", "view-001", "Container", 10, 10, 200, 100,
                grandparentId, null, new ImageParams(null, "bottom-left", null));
        String parentId = parentResult.entity().viewObjectId();

        // Child in the bottom-left icon band of the parent — triggers the W2 lever.
        accessor.addToView("default", "view-001", "ba-001",
                10, 80, 30, 15, false, parentId, null, null);

        IDiagramModelGroup parent = (IDiagramModelGroup)
                com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, parentId);
        IDiagramModelGroup grandparent = (IDiagramModelGroup)
                com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, grandparentId);

        // Parent grew by exactly ICON_BAND_HEIGHT (=24).
        assertEquals("W2 CREATION moment grew the icon-bearing parent by ICON_BAND_HEIGHT",
                100 + ImageHelper.ICON_BAND_HEIGHT, parent.getBounds().getHeight());

        // Cascade fix verification: grandparent cascaded — its height grew to fit the
        // now-taller parent. Required grandparent height ≥ parent.y (10) +
        // parent.h (124) + DEFAULT_GROUP_PADDING (10) = 144.
        assertTrue("Grandparent group cascaded: height grew to >= 144 (was 120 pre-W2)",
                grandparent.getBounds().getHeight() >= 144);
    }

    // ---- Literal HTML/XML entity rejection on names / labels / expressions ----
    //
    // Wire tests: the entity grammar itself is exhaustively unit-tested in
    // InputValidationTest; these pin the delegation at each accessor entry point.
    // Each REJECT proves the guard fires (INVALID_PARAMETER, model unmutated); the
    // create-element ACCEPT proves a bare ampersand is stored byte-identically.

    @Test
    public void rejectsLiteralEntity_inCreateElementName() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            accessor.createElement("default", "BusinessActor", "R &amp; D", null, null, null, null);
            fail("expected rejection of literal &amp; in element name");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
    }

    @Test
    public void acceptsBareAmpersand_inCreateElementName_storedByteIdentical() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        MutationResult<ElementDto> result = accessor.createElement(
                "default", "BusinessActor", "R & D", null, null, null, null);

        // Bare ampersand is not an entity token: accepted and stored verbatim (single &).
        assertEquals("R & D", result.entity().name());
    }

    @Test
    public void rejectsLiteralEntity_inCreateViewName() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            accessor.createView("default", "&lt;bad&gt;", null, null, null);
            fail("expected rejection of literal entities in view name");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
    }

    @Test
    public void rejectsLiteralEntity_inUpdateElementName() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            accessor.updateElement("default", "ba-001", "Acme &amp; Co", null, null, null);
            fail("expected rejection of literal &amp; in update-element name");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
    }

    @Test
    public void rejectsLiteralEntity_inAddGroupToViewLabel() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            accessor.addGroupToView("default", "view-001", "Team &amp; Ops",
                    50, 50, null, null, null, null, null);
            fail("expected rejection of literal &amp; in group label");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
    }

    @Test
    public void rejectsLiteralEntity_inUpdateViewObjectText() {
        // Use a pre-populated view (pure EMF, no OSGi command execution); the guard rejects
        // at prepare time, before the command is ever dispatched.
        IArchimateModel model = createTestModelWithViewContents();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);
        String voId = firstViewObjectId(model);

        try {
            accessor.updateViewObject("default", voId, null, null, null, null,
                    "A &amp; B", null, null, null);
            fail("expected rejection of literal &amp; in view-object text");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
    }

    @Test
    public void rejectsLiteralEntity_inUpdateViewObjectLabelExpression() {
        IArchimateModel model = createTestModelWithViewContents();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);
        String voId = firstViewObjectId(model);

        try {
            // x provided so the update reaches the command construction where the
            // labelExpression guard fires.
            accessor.updateViewObject("default", voId, 60, null, null, null,
                    null, null, null, "${name} &amp; x");
            fail("expected rejection of literal &amp; in labelExpression");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
    }

    /** First diagram view-object id on view-001 of a createTestModelWithViewContents() model. */
    private static String firstViewObjectId(IArchimateModel model) {
        IArchimateDiagramModel view = (IArchimateDiagramModel) model.getFolder(FolderType.DIAGRAMS)
                .getElements().get(0);
        return view.getChildren().get(0).getId();
    }

    @Test
    public void rejectsLiteralEntity_inSetViewLabelExpression_viaBulk() {
        IArchimateModel model = createTestModelWithViewContents();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        List<BulkOperation> operations = List.of(
                new BulkOperation("set-view-label-expression", Map.of(
                        "viewId", "view-001",
                        "labelExpression", "${name} &amp; ${property:evidenceMark}")));

        // The labelExpression guard is pure Java in SetViewLabelExpressionCommand.prepare and
        // runs before any view mutation, so no OSGi/display is required to prove rejection.
        try {
            accessor.executeBulk("default", operations, null, false);
            fail("expected rejection of literal &amp; in set-view-label-expression template");
        } catch (ModelAccessException e) {
            // Raw reject surfaces as INVALID_PARAMETER; the bulk driver may re-wrap it as
            // BULK_VALIDATION_FAILED — either proves the guard fired before any mutation.
            assertTrue("guard must fire (INVALID_PARAMETER or wrapped BULK_VALIDATION_FAILED)",
                    e.getErrorCode() == ErrorCode.INVALID_PARAMETER
                            || e.getErrorCode() == ErrorCode.BULK_VALIDATION_FAILED);
        }
    }

    @Test
    public void rejectsLiteralEntity_inUpdateModelName() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        try {
            accessor.updateModel("default", "Acme &amp;amp; Co", null, null);
            fail("expected rejection of literal entity in model name");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
    }

    @Test
    public void rejectsLiteralEntity_inUpdateSpecializationNewName() {
        IArchimateModel model = createTestModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);

        accessor.createSpecialization("default", "Old Name", "Node");
        try {
            accessor.updateSpecialization("default", "Old Name", "Node", "New &amp; Name", null, false);
            fail("expected rejection of literal &amp; in specialization newName");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
    }
}
