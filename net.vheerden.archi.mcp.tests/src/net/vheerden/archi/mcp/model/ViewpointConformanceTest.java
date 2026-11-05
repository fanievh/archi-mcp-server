package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.Test;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelNote;

import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;

/**
 * Pins which objects a view's viewpoint does not allow, and that the verdict never claims a check
 * it did not make. Runs outside OSGi, so it exercises the definitions-file reading the registry
 * falls back to there; the live gate exercises the registry itself.
 */
public class ViewpointConformanceTest {

    private static final IArchimateFactory F = IArchimateFactory.eINSTANCE;

    private static IArchimateDiagramModel view(String viewpoint) {
        IArchimateDiagramModel view = F.createArchimateDiagramModel();
        view.setViewpoint(viewpoint);
        return view;
    }

    private static IDiagramModelArchimateObject place(IDiagramModelContainer parent,
            IArchimateElement element, String id) {
        IDiagramModelArchimateObject object = F.createDiagramModelArchimateObject();
        object.setId(id);
        object.setArchimateElement(element);
        parent.getChildren().add(object);
        return object;
    }

    private static IDiagramModelArchimateConnection connect(IDiagramModelArchimateObject source,
            IDiagramModelArchimateObject target, IArchimateRelationship relationship, String id) {
        relationship.connect(source.getArchimateElement(), target.getArchimateElement());
        IDiagramModelArchimateConnection connection = F.createDiagramModelArchimateConnection();
        connection.setId(id);
        connection.setArchimateRelationship(relationship);
        connection.connect(source, target);
        return connection;
    }

    @Test
    public void aViewWithNoViewpoint_isNotApplicable_andCountsNothing() {
        IArchimateDiagramModel view = view("");
        place(view, F.createBusinessActor(), "actor");

        ViewpointConformance.Verdict verdict = ViewpointConformance.of(view);

        assertEquals(LayoutQualityAssessor.COVERAGE_NOT_APPLICABLE, verdict.coverage());
        assertNull("no viewpoint means nothing was examined, so no count", verdict.count());
    }

    @Test
    public void anIdArchiDoesNotDefine_isDrawnAsNoViewpoint_soNotApplicable() {
        IArchimateDiagramModel view = view("no_such_viewpoint");
        place(view, F.createBusinessActor(), "actor");

        assertEquals(LayoutQualityAssessor.COVERAGE_NOT_APPLICABLE,
                ViewpointConformance.of(view).coverage());
    }

    @Test
    public void aBusinessActorOnAnApplicationCooperationView_isCounted_andTheApplicationElementIsNot() {
        IArchimateDiagramModel view = view("application_cooperation");
        place(view, F.createBusinessActor(), "actor");
        place(view, F.createApplicationComponent(), "app");

        ViewpointConformance.Verdict verdict = ViewpointConformance.of(view);

        assertEquals("the definitions were read, so this is a real check",
                LayoutQualityAssessor.COVERAGE_CHECKED, verdict.coverage());
        assertEquals(List.of("actor"), verdict.nonConformingIds());
        assertEquals(Integer.valueOf(1), verdict.count());
    }

    @Test
    public void aConformingView_isCheckedWithAZeroCount() {
        IArchimateDiagramModel view = view("application_cooperation");
        place(view, F.createApplicationComponent(), "app");
        place(view, F.createLocation(), "where");

        ViewpointConformance.Verdict verdict = ViewpointConformance.of(view);

        assertEquals(LayoutQualityAssessor.COVERAGE_CHECKED, verdict.coverage());
        assertEquals(Integer.valueOf(0), verdict.count());
    }

    @Test
    public void aConnectionToAGhostedElement_isCountedWithIt_asArchiGhostsIt() {
        IArchimateDiagramModel view = view("application_cooperation");
        IDiagramModelArchimateObject actor = place(view, F.createBusinessActor(), "actor");
        IDiagramModelArchimateObject app = place(view, F.createApplicationComponent(), "app");
        IDiagramModelArchimateObject other = place(view, F.createApplicationComponent(), "app2");
        connect(actor, app, F.createServingRelationship(), "toActor");
        connect(app, other, F.createFlowRelationship(), "between");

        assertEquals("objects first, then the connection whose end is ghosted",
                List.of("actor", "toActor"), ViewpointConformance.of(view).nonConformingIds());
    }

    @Test
    public void anElementNestedInAGroupOrAnElement_isFound() {
        IArchimateDiagramModel view = view("application_cooperation");
        IDiagramModelGroup group = F.createDiagramModelGroup();
        group.setId("group");
        view.getChildren().add(group);
        place(group, F.createBusinessRole(), "role");
        IDiagramModelArchimateObject app = place(view, F.createApplicationComponent(), "app");
        place(app, F.createBusinessObject(), "object");

        assertEquals(List.of("role", "object"), ViewpointConformance.of(view).nonConformingIds());
    }

    @Test
    public void notesGroupsJunctionsAndGroupings_areAlwaysAllowed() {
        IArchimateDiagramModel view = view("organization");
        IDiagramModelNote note = F.createDiagramModelNote();
        note.setId("note");
        view.getChildren().add(note);
        IDiagramModelGroup group = F.createDiagramModelGroup();
        group.setId("group");
        view.getChildren().add(group);
        place(view, F.createJunction(), "junction");
        place(view, F.createGrouping(), "grouping");

        ViewpointConformance.Verdict verdict = ViewpointConformance.of(view);

        assertEquals(LayoutQualityAssessor.COVERAGE_CHECKED, verdict.coverage());
        assertEquals(List.of(), verdict.nonConformingIds());
    }

    @Test
    public void everyViewpointArchiDefines_isReadFromItsDefinitions() {
        // A viewpoint the fallback could not read would answer not-applicable for its own id, which
        // reads as "no viewpoint". Every id the create-view surface accepts must be checkable.
        for (String id : ViewpointIds.known()) {
            IArchimateDiagramModel view = view(id);
            place(view, F.createApplicationComponent(), "app");
            assertEquals(id, LayoutQualityAssessor.COVERAGE_CHECKED,
                    ViewpointConformance.of(view).coverage());
        }
    }

    @Test
    public void aGroupTokenExpandsToItsWholeLayer_andAnUnlistedLayerIsRefused() {
        // business_process_cooperation lists $ApplicationElements$: every application type is in.
        for (org.eclipse.emf.ecore.EClass type : com.archimatetool.model.util.ArchimateModelUtils
                .getApplicationClasses()) {
            IArchimateDiagramModel view = view("business_process_cooperation");
            place(view, (IArchimateElement) F.create(type), "x");
            assertEquals(type.getName(), List.of(), ViewpointConformance.of(view).nonConformingIds());
        }
        IArchimateDiagramModel view = view("business_process_cooperation");
        place(view, F.createNode(), "node");
        assertEquals(List.of("node"), ViewpointConformance.of(view).nonConformingIds());
    }

    @Test
    public void allows_mirrorsArchisRule_forTheListsItIsGiven() {
        var pkg = com.archimatetool.model.IArchimatePackage.eINSTANCE;
        var none = java.util.Set.<org.eclipse.emf.ecore.EClass>of();
        assertTrue("an empty element list allows every element",
                ViewpointConformance.allows(none, none, pkg.getBusinessActor()));
        assertTrue("a non-concept is always allowed",
                ViewpointConformance.allows(java.util.Set.of(pkg.getNode()), none, pkg.getDiagramModelNote()));
        assertTrue("a Junction is always allowed",
                ViewpointConformance.allows(java.util.Set.of(pkg.getNode()), none, pkg.getJunction()));
        assertEquals(false,
                ViewpointConformance.allows(java.util.Set.of(pkg.getNode()), none, pkg.getBusinessActor()));
        assertTrue("an empty relationship list allows every relationship",
                ViewpointConformance.allows(java.util.Set.of(pkg.getNode()), none, pkg.getFlowRelationship()));
    }

    // ---- the assessor folds the verdict in -----------------------------------------------------

    private static List<AssessmentNode> twoNodes() {
        return List.of(
                new AssessmentNode("a", 0, 0, 120, 55, null, false, false, null, 0.0, null, null, 0.0, 0.0, 0.0),
                new AssessmentNode("b", 300, 0, 120, 55, null, false, false, null, 0.0, null, null, 0.0, 0.0, 0.0));
    }

    @Test
    public void assess_withoutAVerdict_declaresTheDimensionNotChecked() {
        Map<String, String> coverage = new LayoutQualityAssessor()
                .assess(twoNodes(), List.of(), true).coverage();
        assertEquals(LayoutQualityAssessor.COVERAGE_NOT_CHECKED, coverage.get("viewpointConformance"));
    }

    @Test
    public void assess_withAVerdict_publishesItsLevelAndIds_andNoRatingMoves() {
        LayoutQualityAssessor assessor = new LayoutQualityAssessor();
        LayoutAssessmentResult without = assessor.assess(twoNodes(), List.of(), true);
        LayoutAssessmentResult with = assessor.assess(twoNodes(), List.of(), true,
                new ViewpointConformance.Verdict(LayoutQualityAssessor.COVERAGE_CHECKED, List.of("a")));

        assertEquals(LayoutQualityAssessor.COVERAGE_CHECKED, with.coverage().get("viewpointConformance"));
        assertEquals(java.util.Set.of("a"), with.violatorIds().get("viewpointNonConforming"));
        assertEquals(without.overallRating(), with.overallRating());
        assertEquals(without.layoutRating(), with.layoutRating());
        assertEquals(without.routingRating(), with.routingRating());
        assertEquals(without.ratingBreakdown(), with.ratingBreakdown());
    }

    @Test
    public void assessDegenerate_appliesTheVerdictToALoneObject_butAnEmptyViewStaysNotApplicable() {
        LayoutQualityAssessor assessor = new LayoutQualityAssessor();
        ViewpointConformance.Verdict checked =
                new ViewpointConformance.Verdict(LayoutQualityAssessor.COVERAGE_CHECKED, List.of("a"));

        assertEquals(LayoutQualityAssessor.COVERAGE_CHECKED, assessor.assessDegenerate(
                twoNodes().subList(0, 1), List.of(), checked).coverage().get("viewpointConformance"));
        assertEquals(LayoutQualityAssessor.COVERAGE_NOT_CHECKED, assessor.assessDegenerate(
                twoNodes().subList(0, 1), List.of()).coverage().get("viewpointConformance"));
        assertEquals(LayoutQualityAssessor.COVERAGE_NOT_APPLICABLE, assessor.assessDegenerate(
                List.of(), List.of(), checked).coverage().get("viewpointConformance"));
    }

    // ---- through the facade, onto the wire --------------------------------------------------

    private AssessLayoutResultDto assessThroughTheFacade(String viewpoint, int objects) {
        IArchimateModel model = F.createArchimateModel();
        model.setDefaults();
        IArchimateDiagramModel view = view(viewpoint);
        view.setId("v");
        model.getDefaultFolderForObject(view).getElements().add(view);
        IArchimateElement actor = F.createBusinessActor();
        IArchimateElement app = F.createApplicationComponent();
        model.getFolder(FolderType.BUSINESS).getElements().add(actor);
        model.getFolder(FolderType.APPLICATION).getElements().add(app);
        IDiagramModelArchimateObject a = place(view, actor, "actor");
        a.setBounds(0, 0, 120, 55);
        if (objects > 1) {
            IDiagramModelArchimateObject b = place(view, app, "app");
            b.setBounds(300, 0, 120, 55);
        }
        StubEditorModelManager mgr = new StubEditorModelManager();
        mgr.setModels(List.of(model));
        ArchiModelAccessorImpl accessor = newAccessor(mgr, model);
        try {
            return accessor.assessLayout("v", true);
        } finally {
            accessor.dispose();
        }
    }

    @Test
    public void assessLayout_publishesTheCountTheIdsAndTheLevel() {
        AssessLayoutResultDto dto = assessThroughTheFacade("application_cooperation", 2);

        assertEquals(Integer.valueOf(1), dto.viewpointNonConformingCount());
        assertEquals(List.of("actor"), dto.violatorIds().get("viewpointNonConforming"));
        assertEquals(LayoutQualityAssessor.COVERAGE_CHECKED, dto.coverage().get("viewpointConformance"));
    }

    @Test
    public void assessLayout_onAViewWithNoViewpoint_omitsTheCount() {
        AssessLayoutResultDto dto = assessThroughTheFacade("", 2);

        assertNull(dto.viewpointNonConformingCount());
        assertEquals(LayoutQualityAssessor.COVERAGE_NOT_APPLICABLE, dto.coverage().get("viewpointConformance"));
    }

    @Test
    public void assessLayout_onALoneOutOfViewpointObject_stillCountsIt() {
        AssessLayoutResultDto dto = assessThroughTheFacade("application_cooperation", 1);

        assertEquals(Integer.valueOf(1), dto.viewpointNonConformingCount());
        assertEquals(LayoutQualityAssessor.COVERAGE_CHECKED, dto.coverage().get("viewpointConformance"));
    }

    private ArchiModelAccessorImpl newAccessor(StubEditorModelManager mgr, IArchimateModel target) {
        MutationDispatcher testDispatcher = new MutationDispatcher(() -> target) {
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
        return new ArchiModelAccessorImpl(mgr, testDispatcher);
    }

    /** Minimal {@link IEditorModelManager} — only model registration and listener plumbing. */
    private static class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final Set<PropertyChangeListener> listeners = new LinkedHashSet<>();

        void setModels(List<IArchimateModel> models) {
            this.models = models;
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

        @SuppressWarnings("unused")
        void firePropertyChange(String propertyName, Object oldValue, Object newValue) {
            PropertyChangeEvent evt = new PropertyChangeEvent(this, propertyName, oldValue, newValue);
            for (PropertyChangeListener listener : new ArrayList<>(listeners)) {
                listener.propertyChange(evt);
            }
        }

        @Override public IArchimateModel createNewModel() { return null; }
        @Override public void registerModel(IArchimateModel model) {}
        @Override public IArchimateModel openModel(File file) { return null; }
        @Override public void openModel(IArchimateModel model) {}
        @Override public IArchimateModel loadModel(File file) { return null; }
        @Override public IArchimateModel load(File file) throws IOException { return null; }
        @Override public boolean closeModel(IArchimateModel model) throws IOException { return false; }
        @Override public boolean closeModel(IArchimateModel model, boolean askSave) throws IOException { return false; }
        @Override public boolean isModelLoaded(File file) { return false; }
        @Override public boolean isModelDirty(IArchimateModel model) { return false; }
        @Override public boolean saveModel(IArchimateModel model) throws IOException { return false; }
        @Override public boolean saveModelAs(IArchimateModel model) throws IOException { return false; }
        @Override public void saveState() throws IOException {}
        @Override public void firePropertyChange(Object source, String prop, Object oldValue, Object newValue) {}
    }

    @Test
    public void aFailureWhileWalkingTheView_isAnsweredNotChecked_notThrown() {
        // The count rides on assess-layout, so a failure here must not take the assessment down.
        IArchimateDiagramModel broken = (IArchimateDiagramModel) java.lang.reflect.Proxy.newProxyInstance(
                IArchimateDiagramModel.class.getClassLoader(), new Class<?>[] {IArchimateDiagramModel.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getViewpoint")) {
                        return "application_cooperation";
                    }
                    throw new IllegalStateException("broken view");
                });
        ViewpointConformance.Verdict verdict = ViewpointConformance.of(broken);
        assertEquals(LayoutQualityAssessor.COVERAGE_NOT_CHECKED, verdict.coverage());
        assertEquals(List.of(), verdict.nonConformingIds());
        assertEquals(null, verdict.count());
    }
}
