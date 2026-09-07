package net.vheerden.archi.mcp.model;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.ISketchModel;

import net.vheerden.archi.mcp.response.dto.ConceptUsageDto;

/**
 * Tests for {@link ArchiModelAccessorImpl#buildConceptUsageDto(com.archimatetool.model.IArchimateConcept)}
 * Uses real EMF objects via {@link IArchimateFactory#eINSTANCE} —
 * runs as JUnit Plug-in Test in the OSGi runtime.
 *
 * <p>The accessor-impl test calls the package-private helper directly with
 * synthetic models, so we don't need a real IEditorModelManager.</p>
 */
public class ArchiModelAccessorImplFindConceptUsageTest {

    private IArchimateFactory factory;
    private IArchimateModel model;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        model = factory.createArchimateModel();
        model.setDefaults();
    }

    @Test
    public void shouldFindUsageForElementOnSingleView() {
        IArchimateElement comp = factory.createApplicationComponent();
        comp.setName("MyComponent");
        model.getFolder(FolderType.APPLICATION).getElements().add(comp);

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setName("MyView");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        IDiagramModelArchimateObject visual = factory.createDiagramModelArchimateObject();
        visual.setArchimateElement(comp);
        visual.setBounds(0, 0, 120, 60);
        view.getChildren().add(visual);

        ConceptUsageDto dto = ArchiModelAccessorImpl.buildConceptUsageDto(comp);

        assertEquals(1, dto.viewReferenceCount());
        assertEquals(1, dto.visualReferenceCount());
        assertEquals("MyView", dto.viewReferences().get(0).viewName());
        assertEquals("element", dto.conceptKind());
        assertEquals("ApplicationComponent", dto.conceptType());
        assertEquals("object", dto.viewReferences().get(0).visualObjects().get(0).kind());
        assertNull("embeddingViewReferences null in v1", dto.embeddingViewReferences());
    }

    @Test
    public void shouldFindUsageForElementOnMultipleViews() {
        IArchimateElement comp = factory.createApplicationComponent();
        comp.setName("Shared");
        model.getFolder(FolderType.APPLICATION).getElements().add(comp);

        for (int i = 0; i < 3; i++) {
            IArchimateDiagramModel view = factory.createArchimateDiagramModel();
            view.setName("View " + i);
            model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
            IDiagramModelArchimateObject visual = factory.createDiagramModelArchimateObject();
            visual.setArchimateElement(comp);
            visual.setBounds(0, 0, 120, 60);
            view.getChildren().add(visual);
        }

        ConceptUsageDto dto = ArchiModelAccessorImpl.buildConceptUsageDto(comp);

        assertEquals(3, dto.viewReferenceCount());
        assertEquals(3, dto.visualReferenceCount());
    }

    @Test
    public void shouldFindUsageForElementPlacedTwiceOnOneView() {
        IArchimateElement comp = factory.createApplicationComponent();
        comp.setName("Twice");
        model.getFolder(FolderType.APPLICATION).getElements().add(comp);

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setName("OneView");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        for (int i = 0; i < 2; i++) {
            IDiagramModelArchimateObject visual = factory.createDiagramModelArchimateObject();
            visual.setArchimateElement(comp);
            visual.setBounds(i * 200, 0, 120, 60);
            view.getChildren().add(visual);
        }

        ConceptUsageDto dto = ArchiModelAccessorImpl.buildConceptUsageDto(comp);

        assertEquals(1, dto.viewReferenceCount());
        assertEquals(2, dto.visualReferenceCount());
        assertEquals(2, dto.viewReferences().get(0).visualObjects().size());
    }

    @Test
    public void shouldFindUsageForRelationship() {
        IArchimateElement src = factory.createApplicationComponent();
        src.setName("Src");
        IArchimateElement tgt = factory.createApplicationComponent();
        tgt.setName("Tgt");
        model.getFolder(FolderType.APPLICATION).getElements().add(src);
        model.getFolder(FolderType.APPLICATION).getElements().add(tgt);

        IArchimateRelationship rel = factory.createServingRelationship();
        rel.setSource(src);
        rel.setTarget(tgt);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setName("V");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        IDiagramModelArchimateObject srcVisual = factory.createDiagramModelArchimateObject();
        srcVisual.setArchimateElement(src);
        srcVisual.setBounds(0, 0, 120, 60);
        view.getChildren().add(srcVisual);
        IDiagramModelArchimateObject tgtVisual = factory.createDiagramModelArchimateObject();
        tgtVisual.setArchimateElement(tgt);
        tgtVisual.setBounds(300, 0, 120, 60);
        view.getChildren().add(tgtVisual);

        IDiagramModelArchimateConnection conn = factory.createDiagramModelArchimateConnection();
        conn.setArchimateRelationship(rel);
        conn.connect(srcVisual, tgtVisual);

        ConceptUsageDto dto = ArchiModelAccessorImpl.buildConceptUsageDto(rel);

        assertEquals("relationship", dto.conceptKind());
        assertEquals(1, dto.viewReferenceCount());
        assertEquals(1, dto.visualReferenceCount());
        assertEquals("connection", dto.viewReferences().get(0).visualObjects().get(0).kind());
    }

    @Test
    public void shouldReturnEmptyForOrphanElement() {
        IArchimateElement comp = factory.createApplicationComponent();
        comp.setName("Orphan");
        model.getFolder(FolderType.APPLICATION).getElements().add(comp);

        ConceptUsageDto dto = ArchiModelAccessorImpl.buildConceptUsageDto(comp);

        assertEquals(0, dto.viewReferenceCount());
        assertEquals(0, dto.visualReferenceCount());
        assertTrue(dto.viewReferences().isEmpty());
    }

    @Test
    public void shouldReturnEmptyForOrphanRelationship() {
        IArchimateElement src = factory.createApplicationComponent();
        IArchimateElement tgt = factory.createApplicationComponent();
        model.getFolder(FolderType.APPLICATION).getElements().add(src);
        model.getFolder(FolderType.APPLICATION).getElements().add(tgt);

        IArchimateRelationship rel = factory.createServingRelationship();
        rel.setSource(src);
        rel.setTarget(tgt);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);

        ConceptUsageDto dto = ArchiModelAccessorImpl.buildConceptUsageDto(rel);

        assertEquals("relationship", dto.conceptKind());
        assertEquals(0, dto.viewReferenceCount());
        assertEquals(0, dto.visualReferenceCount());
    }

    @Test
    public void shouldOrderViewReferencesByName() {
        IArchimateElement comp = factory.createApplicationComponent();
        model.getFolder(FolderType.APPLICATION).getElements().add(comp);

        // Add views in order Z, A, M — expect output A, M, Z
        for (String name : new String[] {"Zeta", "Alpha", "Mid"}) {
            IArchimateDiagramModel view = factory.createArchimateDiagramModel();
            view.setName(name);
            model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
            IDiagramModelArchimateObject visual = factory.createDiagramModelArchimateObject();
            visual.setArchimateElement(comp);
            visual.setBounds(0, 0, 120, 60);
            view.getChildren().add(visual);
        }

        ConceptUsageDto dto = ArchiModelAccessorImpl.buildConceptUsageDto(comp);

        assertEquals("Alpha", dto.viewReferences().get(0).viewName());
        assertEquals("Mid", dto.viewReferences().get(1).viewName());
        assertEquals("Zeta", dto.viewReferences().get(2).viewName());
    }

    @Test
    public void shouldOrderVisualObjectsByViewObjectId() {
        IArchimateElement comp = factory.createApplicationComponent();
        model.getFolder(FolderType.APPLICATION).getElements().add(comp);

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setName("V");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        // Place three visuals with explicit, non-sorted IDs to make the assertion
        // falsifiable (EMF-generated UUIDs would otherwise be opaque).
        String[] insertOrder = {"zzz-obj", "aaa-obj", "mmm-obj"};
        for (String id : insertOrder) {
            IDiagramModelArchimateObject visual = factory.createDiagramModelArchimateObject();
            visual.setArchimateElement(comp);
            visual.setBounds(0, 0, 120, 60);
            visual.setId(id);
            view.getChildren().add(visual);
        }

        ConceptUsageDto dto = ArchiModelAccessorImpl.buildConceptUsageDto(comp);
        var visuals = dto.viewReferences().get(0).visualObjects();
        assertEquals(3, visuals.size());
        assertEquals("aaa-obj", visuals.get(0).viewObjectId());
        assertEquals("mmm-obj", visuals.get(1).viewObjectId());
        assertEquals("zzz-obj", visuals.get(2).viewObjectId());
    }

    @Test
    public void shouldHandleUnnamedView_sortsAsEmptyString() {
        IArchimateElement comp = factory.createApplicationComponent();
        model.getFolder(FolderType.APPLICATION).getElements().add(comp);

        IArchimateDiagramModel unnamed = factory.createArchimateDiagramModel();
        // Don't setName — defaults to null in some EMF paths, "" in others
        model.getFolder(FolderType.DIAGRAMS).getElements().add(unnamed);
        IDiagramModelArchimateObject visual = factory.createDiagramModelArchimateObject();
        visual.setArchimateElement(comp);
        visual.setBounds(0, 0, 120, 60);
        unnamed.getChildren().add(visual);

        IArchimateDiagramModel named = factory.createArchimateDiagramModel();
        named.setName("Alpha");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(named);
        IDiagramModelArchimateObject namedVisual = factory.createDiagramModelArchimateObject();
        namedVisual.setArchimateElement(comp);
        namedVisual.setBounds(0, 0, 120, 60);
        named.getChildren().add(namedVisual);

        ConceptUsageDto dto = ArchiModelAccessorImpl.buildConceptUsageDto(comp);

        // The unnamed view's viewName is normalised to "" (empty string sorts FIRST
        // lexically — before "Alpha"). Pin the deterministic order.
        assertEquals("", dto.viewReferences().get(0).viewName());
        assertEquals("Alpha", dto.viewReferences().get(1).viewName());
    }

    @Test
    public void shouldHandleSketchViewReference() {
        // Sketch views can't contain ArchiMate elements as IDiagramModelArchimateObject
        // children directly (Sketch uses sketch-specific objects). The viewKind
        // discrimination is exercised via deriveViewKind() helper instead.
        ISketchModel sketch = factory.createSketchModel();
        sketch.setName("Sketch");
        assertEquals("sketch", ArchiModelAccessorImpl.deriveViewKind(sketch));
    }

    @Test
    public void shouldReturnArchimateViewKind_forArchimateDiagramModel() {
        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        assertEquals("archimate", ArchiModelAccessorImpl.deriveViewKind(view));
    }

    @Test
    public void shouldPopulateViewpointType_whenSet() {
        IArchimateElement comp = factory.createApplicationComponent();
        model.getFolder(FolderType.APPLICATION).getElements().add(comp);

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setName("V");
        view.setViewpoint("Application Cooperation");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        IDiagramModelArchimateObject visual = factory.createDiagramModelArchimateObject();
        visual.setArchimateElement(comp);
        visual.setBounds(0, 0, 120, 60);
        view.getChildren().add(visual);

        ConceptUsageDto dto = ArchiModelAccessorImpl.buildConceptUsageDto(comp);
        assertEquals("Application Cooperation",
                dto.viewReferences().get(0).viewpointType());
    }

    @Test
    public void shouldOmitEmptyViewpointType() {
        IArchimateElement comp = factory.createApplicationComponent();
        model.getFolder(FolderType.APPLICATION).getElements().add(comp);

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setName("V");
        // Don't set viewpoint — it's typically empty string by default
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        IDiagramModelArchimateObject visual = factory.createDiagramModelArchimateObject();
        visual.setArchimateElement(comp);
        visual.setBounds(0, 0, 120, 60);
        view.getChildren().add(visual);

        ConceptUsageDto dto = ArchiModelAccessorImpl.buildConceptUsageDto(comp);
        assertNull("empty/null viewpoint normalised to null",
                dto.viewReferences().get(0).viewpointType());
    }
}
