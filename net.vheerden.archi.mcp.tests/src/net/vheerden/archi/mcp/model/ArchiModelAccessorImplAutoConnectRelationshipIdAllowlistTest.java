package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import com.archimatetool.model.IApplicationComponent;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.AutoConnectResultDto;

/**
 * Pin tests for the relationship-ID allow-list in
 * {@link ArchiModelAccessorImpl#autoConnectView}.
 *
 * <p>The allow-list lets a caller draw a precise, curated slice of a view — e.g.
 * a directional producer&#8594;hub&#8594;consumer flow — that the type filter cannot
 * express, because two relationships of the same type between the same pair of
 * elements in opposite directions are indistinguishable by type. The
 * directional-slice test ({@code shouldDrawOnlyAllowListedDirection}) is the core
 * bug reproducer: without the ID guard both directions are drawn.
 *
 * <p>Real EMF via {@link IArchimateFactory#eINSTANCE}; runs as a JUnit Plug-in
 * Test. Plumbing mirrors {@code ArchiModelAccessorImplAutoConnectAncestorSkipTest}.
 */
public class ArchiModelAccessorImplAutoConnectRelationshipIdAllowlistTest {

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ------------------------------------------------------------------
    // Allow-list selects exactly one of several relationships
    // between the same pair.
    // ------------------------------------------------------------------

    @Test
    public void shouldDrawOnlyAllowListedRelationship_whenPairHasMany() {
        // Three relationships across the A/B pair; allow-list only the middle one.
        IArchimateModel model = buildFlatModel();
        addServing(model, "rel-1", "el-A", "el-B");
        addFlow(model, "rel-2", "el-A", "el-B");
        addServing(model, "rel-3", "el-B", "el-A");
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);
        placeFlat("el-A", "el-B");

        MutationResult<AutoConnectResultDto> result = accessor.autoConnectView(
                "default", "view-001", null, null, List.of("rel-2"), null, null);

        AutoConnectResultDto dto = result.entity();
        assertEquals("Only the allow-listed relationship is drawn",
                1, dto.connectionsCreated());
        assertEquals("relationshipIdsConnected is exactly the allow-listed ID",
                List.of("rel-2"), dto.relationshipIdsConnected());
    }

    // ------------------------------------------------------------------
    // Core case — directional slice: two same-type opposite-direction
    // relationships; allow-list only the forward ID. Without the guard
    // both would be drawn (the exact bug this story fixes).
    // ------------------------------------------------------------------

    @Test
    public void shouldDrawOnlyAllowListedDirection_sameTypeReverseFlowExcluded() {
        IArchimateModel model = buildFlatModel();
        addServing(model, "rel-fwd", "el-A", "el-B");
        addServing(model, "rel-rev", "el-B", "el-A");
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);
        placeFlat("el-A", "el-B");

        MutationResult<AutoConnectResultDto> result = accessor.autoConnectView(
                "default", "view-001", null, null, List.of("rel-fwd"), null, null);

        AutoConnectResultDto dto = result.entity();
        assertEquals("Only the forward direction is drawn", 1, dto.connectionsCreated());
        assertEquals("The forward relationship is the one connected",
                List.of("rel-fwd"), dto.relationshipIdsConnected());
        assertFalse("The same-type reverse flow is excluded",
                dto.relationshipIdsConnected().contains("rel-rev"));
    }

    // ------------------------------------------------------------------
    // AND (intersection) with relationshipTypes, not OR.
    // Allow-listed Serving + a type filter of Flow → nothing matches both.
    // Under OR semantics this would draw 2; asserting 0 proves AND.
    // ------------------------------------------------------------------

    @Test
    public void shouldIntersectWithTypeFilter_notUnion() {
        IArchimateModel model = buildFlatModel();
        addServing(model, "rel-serv", "el-A", "el-B");
        addFlow(model, "rel-flow", "el-A", "el-B");
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);
        placeFlat("el-A", "el-B");

        MutationResult<AutoConnectResultDto> result = accessor.autoConnectView(
                "default", "view-001", null,
                List.of("FlowRelationship"), List.of("rel-serv"), null, null);

        AutoConnectResultDto dto = result.entity();
        // rel-serv is allow-listed but wrong type; rel-flow is right type but
        // not allow-listed. Intersection is empty.
        assertEquals("Intersection of allow-list and type filter is empty",
                0, dto.connectionsCreated());
        assertTrue(dto.relationshipIdsConnected().isEmpty());
    }

    // ------------------------------------------------------------------
    // An unknown/hallucinated ID fails fast with RELATIONSHIP_NOT_FOUND
    // before any connection is created.
    // ------------------------------------------------------------------

    @Test
    public void shouldThrowRelationshipNotFound_whenAllowListIdUnknown() {
        IArchimateModel model = buildFlatModel();
        addServing(model, "rel-real", "el-A", "el-B");
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);
        placeFlat("el-A", "el-B");

        try {
            accessor.autoConnectView("default", "view-001", null, null,
                    List.of("rel-real", "rel-nope"), null, null);
            fail("Expected ModelAccessException for the unknown allow-list ID");
        } catch (ModelAccessException e) {
            assertEquals("Fails with RELATIONSHIP_NOT_FOUND",
                    ErrorCode.RELATIONSHIP_NOT_FOUND, e.getErrorCode());
            assertTrue("Message names the offending ID",
                    e.getMessage().contains("rel-nope"));
        }

        // No partial mutation: the real relationship was NOT drawn either.
        IArchimateDiagramModel view = firstView(model);
        assertTrue("No connections created on the failed call",
                view.getChildren().stream().noneMatch(
                        c -> !c.getSourceConnections().isEmpty()));
    }

    // ------------------------------------------------------------------
    // A valid allow-listed ID that is not drawable here (endpoint not
    // on the view) is skipped silently, not an error.
    // ------------------------------------------------------------------

    @Test
    public void shouldSkipSilently_whenAllowListedEndpointNotOnView() {
        IArchimateModel model = buildFlatModel();
        addServing(model, "rel-ab", "el-A", "el-B");
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);
        // Place only el-A; el-B is absent from the view.
        accessor.addToView("default", "view-001", "el-A",
                50, 50, 120, 55, false, null, null, null);

        MutationResult<AutoConnectResultDto> result = accessor.autoConnectView(
                "default", "view-001", null, null, List.of("rel-ab"), null, null);

        AutoConnectResultDto dto = result.entity();
        assertEquals("Undrawable allow-listed ID is skipped, not drawn",
                0, dto.connectionsCreated());
        assertTrue(dto.relationshipIdsConnected().isEmpty());
    }

    // ------------------------------------------------------------------
    // An empty allow-list behaves exactly like omitting it (no filter).
    // (The null case is pinned by the ancestor-skip suite's baseline test.)
    // ------------------------------------------------------------------

    @Test
    public void shouldBehaveAsNoFilter_whenAllowListEmpty() {
        IArchimateModel model = buildFlatModel();
        addServing(model, "rel-1", "el-A", "el-B");
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);
        placeFlat("el-A", "el-B");

        MutationResult<AutoConnectResultDto> result = accessor.autoConnectView(
                "default", "view-001", null, null, List.of(), null, null);

        AutoConnectResultDto dto = result.entity();
        assertEquals("Empty allow-list is treated as no filter",
                1, dto.connectionsCreated());
        assertEquals(List.of("rel-1"), dto.relationshipIdsConnected());
    }

    // ------------------------------------------------------------------
    // Fixture builders
    // ------------------------------------------------------------------

    /** Two ApplicationComponents (el-A, el-B) + an empty view-001; no relationships. */
    private IArchimateModel buildFlatModel() {
        IArchimateModel model = factory.createArchimateModel();
        model.setName("Allow-list fixture");
        model.setId("model-allowlist");
        model.setDefaults();

        IApplicationComponent a = factory.createApplicationComponent();
        a.setId("el-A");
        a.setName("Element A");
        model.getFolder(FolderType.APPLICATION).getElements().add(a);

        IApplicationComponent b = factory.createApplicationComponent();
        b.setId("el-B");
        b.setName("Element B");
        model.getFolder(FolderType.APPLICATION).getElements().add(b);

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-001");
        view.setName("Main");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        return model;
    }

    private void addServing(IArchimateModel model, String id, String srcId, String tgtId) {
        IArchimateRelationship rel = factory.createServingRelationship();
        rel.setId(id);
        rel.connect(lookupElement(model, srcId), lookupElement(model, tgtId));
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);
    }

    private void addFlow(IArchimateModel model, String id, String srcId, String tgtId) {
        IArchimateRelationship rel = factory.createFlowRelationship();
        rel.setId(id);
        rel.connect(lookupElement(model, srcId), lookupElement(model, tgtId));
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);
    }

    private com.archimatetool.model.IArchimateElement lookupElement(
            IArchimateModel model, String id) {
        for (Object o : model.getFolder(FolderType.APPLICATION).getElements()) {
            if (o instanceof com.archimatetool.model.IArchimateElement el
                    && id.equals(el.getId())) {
                return el;
            }
        }
        throw new IllegalStateException("fixture element not found: " + id);
    }

    /** Place two elements flat (direct view children, no nesting). */
    private void placeFlat(String idA, String idB) {
        accessor.addToView("default", "view-001", idA,
                50, 50, 120, 55, false, null, null, null);
        accessor.addToView("default", "view-001", idB,
                300, 50, 120, 55, false, null, null, null);
    }

    private IArchimateDiagramModel firstView(IArchimateModel model) {
        for (Object o : model.getFolder(FolderType.DIAGRAMS).getElements()) {
            if (o instanceof IArchimateDiagramModel view) {
                return view;
            }
        }
        throw new IllegalStateException("no view in fixture");
    }

    // ------------------------------------------------------------------
    // Test plumbing — mirror of the ancestor-skip suite.
    // ------------------------------------------------------------------

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
        testDispatcher.setApprovalModeProvider(() -> false);
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
        @Override public void firePropertyChange(Object src, String p, Object oldV, Object newV) {}
    }
}
