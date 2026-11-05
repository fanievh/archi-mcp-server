package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
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
import com.archimatetool.model.IArchimatePackage;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateObject;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.AutoConnectResultDto;

/**
 * An abstract relationship type names no relationship anyone can create or draw. create-relationship
 * and the auto-connect-view type filter refuse it as the caller's mistake, before the endpoints are
 * judged and before anything is written or proposed; every concrete type is still accepted.
 *
 * <p>The refusal comes before the relationship-rules lookup, so these run without the OSGi bundle.</p>
 */
public class AbstractRelationshipTypeTest {

    private static final String ABSTRACT_TAIL = " is abstract: it groups relationship types and names none of them";

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private IArchimateModel model;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();
        model = buildServingModel();
        stubModelManager.setModels(List.of(model));
        accessor = createAccessorWithTestDispatcher(model);
        accessor.addToView("default", "view-001", "el-A", 50, 50, 120, 55, false, null, null, null);
        accessor.addToView("default", "view-001", "el-B", 300, 50, 120, 55, false, null, null, null);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    /**
     * Every relationship class in the package: each abstract one is refused by name, by create-relationship
     * and as an auto-connect filter, and each concrete one is accepted as a filter, so an abstract test that
     * reached too far would fail here too.
     */
    @Test
    public void shouldRefuseEveryAbstractRelationshipClass_andAcceptEveryConcreteOneAsAFilter() {
        EClass relationship = IArchimatePackage.eINSTANCE.getArchimateRelationship();
        List<String> wrong = new ArrayList<>();
        int abstractCount = 0;
        int concreteCount = 0;
        for (EClassifier classifier : IArchimatePackage.eINSTANCE.getEClassifiers()) {
            if (!(classifier instanceof EClass c) || !relationship.isSuperTypeOf(c)) {
                continue;
            }
            String type = c.getName();
            if (c.isAbstract() || c.isInterface()) {
                abstractCount++;
                String want = "Invalid ArchiMate relationship type: " + type + ABSTRACT_TAIL;
                try {
                    accessor.createRelationship("default", type, "el-A", "el-B", null, null);
                    wrong.add(type + ": created");
                } catch (ModelAccessException e) {
                    if (e.getErrorCode() != ErrorCode.INVALID_RELATIONSHIP_TYPE || !want.equals(e.getMessage())) {
                        wrong.add(type + " create: " + e.getErrorCode() + " " + e.getMessage());
                    }
                } catch (RuntimeException | LinkageError e) {
                    wrong.add(type + " create: " + e);
                }
                try {
                    accessor.autoConnectView("default", "view-001", null, List.of(type), null, null, null);
                    wrong.add(type + ": accepted as a filter");
                } catch (ModelAccessException e) {
                    if (e.getErrorCode() != ErrorCode.INVALID_RELATIONSHIP_TYPE || !want.equals(e.getMessage())) {
                        wrong.add(type + " filter: " + e.getErrorCode() + " " + e.getMessage());
                    }
                } catch (RuntimeException e) {
                    wrong.add(type + " filter: " + e);
                }
            } else {
                concreteCount++;
                try {
                    if (RelationshipTypes.resolve(type) != c) {
                        wrong.add(type + ": resolved to another class");
                    }
                } catch (RuntimeException e) {
                    wrong.add(type + ": refused as a relationship type " + e);
                }
                try {
                    accessor.autoConnectView("default", "view-001", null, List.of(type), null, null, null);
                } catch (RuntimeException e) {
                    wrong.add(type + ": refused as a filter " + e);
                }
            }
        }
        assertTrue(wrong.size() + " relationship classes answered wrongly:\n" + String.join("\n", wrong),
                wrong.isEmpty());
        String recount = " (Archi 5.10's count; a new Archi release that adds or drops relationship classes changes"
                + " it: re-count, the per-class checks above still apply)";
        assertEquals("abstract relationship classes" + recount, 5, abstractCount);
        assertEquals("concrete relationship classes" + recount, 11, concreteCount);
    }

    @Test
    public void autoConnect_shouldRefuseAnAbstractFilter_drawingNothing() {
        String versionBefore = accessor.getModelVersion();

        ModelAccessException e = autoConnectRefusal(List.of("ArchimateRelationship"));
        assertEquals(ErrorCode.INVALID_RELATIONSHIP_TYPE, e.getErrorCode());
        assertEquals("Invalid ArchiMate relationship type: ArchimateRelationship" + ABSTRACT_TAIL, e.getMessage());
        assertEquals(0, connectionCount());
        assertEquals(versionBefore, accessor.getModelVersion());
    }

    @Test
    public void autoConnect_shouldRefuseAMixedFilter_withoutDrawingItsConcreteType() {
        String versionBefore = accessor.getModelVersion();

        ModelAccessException e = autoConnectRefusal(List.of("ServingRelationship", "StructuralRelationship"));
        assertEquals(ErrorCode.INVALID_RELATIONSHIP_TYPE, e.getErrorCode());
        assertEquals("Invalid ArchiMate relationship type: StructuralRelationship" + ABSTRACT_TAIL, e.getMessage());
        assertEquals("the Serving relationship may not be drawn either", 0, connectionCount());
        assertEquals(versionBefore, accessor.getModelVersion());
    }

    @Test
    public void autoConnect_shouldStillDrawAConcreteFilter() {
        MutationResult<AutoConnectResultDto> result = accessor.autoConnectView(
                "default", "view-001", null, List.of("ServingRelationship"), null, null, null);

        assertEquals(1, result.entity().connectionsCreated());
        assertEquals(1, connectionCount());
    }

    @Test
    public void shouldRefuseAnAbstractRelationshipType_beforeAnyApprovalCardIsStored() {
        accessor.getMutationDispatcher().setApprovalModeProvider(() -> true);
        String want = "Invalid ArchiMate relationship type: ArchimateRelationship" + ABSTRACT_TAIL;

        ModelAccessException created = null;
        try {
            accessor.createRelationship("default", "ArchimateRelationship", "el-A", "el-B", null, null);
        } catch (ModelAccessException e) {
            created = e;
        }
        assertTrue("create-relationship was not refused", created != null);
        assertEquals(ErrorCode.INVALID_RELATIONSHIP_TYPE, created.getErrorCode());
        assertEquals(want, created.getMessage());

        ModelAccessException connected = autoConnectRefusal(List.of("ArchimateRelationship"));
        assertEquals(ErrorCode.INVALID_RELATIONSHIP_TYPE, connected.getErrorCode());
        assertEquals(want, connected.getMessage());
        assertTrue(accessor.getMutationDispatcher().getPendingProposals("default").isEmpty());

        accessor.autoConnectView("default", "view-001", null, List.of("ServingRelationship"), null, null, null);
        assertEquals("a concrete filter in the same mode is proposed, so the empty list above is not vacuous",
                1, accessor.getMutationDispatcher().getPendingProposals("default").size());
    }

    private ModelAccessException autoConnectRefusal(List<String> types) {
        MutationResult<AutoConnectResultDto> result;
        try {
            result = accessor.autoConnectView("default", "view-001", null, types, null, null, null);
        } catch (ModelAccessException e) {
            return e;
        }
        throw new AssertionError(types + " was accepted: connectionsCreated "
                + (result.entity() != null ? result.entity().connectionsCreated() : "(proposed)"));
    }

    private int connectionCount() {
        int count = 0;
        for (Object o : model.getFolder(FolderType.DIAGRAMS).getElements()) {
            if (o instanceof IArchimateDiagramModel view) {
                for (Object child : view.getChildren()) {
                    if (child instanceof IDiagramModelArchimateObject object) {
                        count += object.getSourceConnections().size();
                    }
                }
            }
        }
        return count;
    }

    /** Two application components (el-A, el-B), a Serving relationship A to B, and an empty view-001. */
    private IArchimateModel buildServingModel() {
        IArchimateModel m = factory.createArchimateModel();
        m.setName("Abstract relationship type fixture");
        m.setId("model-abstract-relationship-type");
        m.setDefaults();

        IApplicationComponent a = factory.createApplicationComponent();
        a.setId("el-A");
        a.setName("Element A");
        m.getFolder(FolderType.APPLICATION).getElements().add(a);

        IApplicationComponent b = factory.createApplicationComponent();
        b.setId("el-B");
        b.setName("Element B");
        m.getFolder(FolderType.APPLICATION).getElements().add(b);

        IArchimateRelationship serving = factory.createServingRelationship();
        serving.setId("rel-AB");
        serving.connect(a, b);
        m.getFolder(FolderType.RELATIONS).getElements().add(serving);

        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId("view-001");
        view.setName("Main");
        m.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        return m;
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
