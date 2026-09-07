package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.Test;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IGrouping;
import com.archimatetool.model.INode;

import net.vheerden.archi.mcp.response.dto.AssessLayoutResultDto;

/**
 * {@code assess-layout} must see an ArchiMate {@code Grouping} as the container it renders as.
 *
 * <p>A {@code Grouping} placed by {@code add-to-view} is an {@link IDiagramModelArchimateObject};
 * a group created by {@code add-group-to-view} is an {@link IDiagramModelGroup}. The assessor
 * asked only the second question, so on the view shape the technology and deployment guidance
 * prescribes — one {@code Grouping} per zone — it reported {@code hasGroups: false} on a canvas
 * full of populated zones, counted a transparent zone rectangle as a pass-through and as a label
 * overlap, and reported a fully-covered zero from a dimension whose detector had skipped every
 * zone it was supposed to examine.
 *
 * <p><b>Why every fixture is built twice.</b> Each view here is constructed with byte-identical
 * geometry, ids and names in both container kinds; the only difference is the concrete EMF type of
 * the zone. Any assessed difference between the two is therefore attributable to the container
 * kind and nothing else. That makes the native run a live negative control rather than a separate
 * assertion that could drift: if a change altered native behaviour, the parity tests fail from the
 * native side.
 *
 * <p><b>Why these drive the accessor and not the assessor.</b> {@code LayoutQualityAssessorTest}
 * builds {@link AssessmentNode} directly and passes the container flag in, so it cannot observe
 * where that flag comes from — a collector-level regression passes it vacuously. These tests go
 * through {@code ArchiModelAccessorImpl.assessLayout} against real EMF objects, so the collector
 * is inside the circuit.
 */
public class TopLevelGroupingAssessmentTest {

    private static final String VIEW_ID = "view-zones";

    /**
     * The one coverage dimension allowed to differ between the container kinds, and why.
     *
     * <p>{@code AssessmentCollector} measures a label's text width for everything that is not a
     * native group and not a note, so a {@code Grouping}'s title <em>is</em> measured and a native
     * group's is not. {@code parentLabelObscured} downgrades itself to {@code partial} for a run
     * carrying a parent whose label width was never measured, so a {@code Grouping} parent is
     * certified {@code checked} where the equivalent native group is only {@code partial}. The
     * {@code Grouping} view genuinely has the better coverage, and holding the two to parity would
     * demand the tool forget something it can measure — which is exactly why the container
     * predicate was added <em>beside</em> the native-group flag rather than replacing it.
     *
     * <p>This entry is here because it was MEASURED, not anticipated: the divergence appears only
     * on a run with a display (headless, the measurement fails for both kinds and they agree at
     * {@code partial}), so it is invisible to the lane this class runs in. Nothing else diverges —
     * an earlier guess at which components would need excusing named the wrong ones. Keep this
     * list to what a run actually shows, so a fixture change that introduces a new difference
     * fails here and gets decided rather than silently absorbed.
     *
     * <p>The set now scopes THREE surfaces rather than one, because the coverage map acquired two
     * projections: {@code contextualPartialDimensions}, the derived list of the dimensions a run
     * downgraded contextually, and the {@code suggestions} prose that names each of them. The
     * asymmetry excused here is a single measured fact about one dimension, so it must be excused
     * wherever that fact surfaces — but only there. Entries are withheld individually, by whether
     * they name an excused dimension, never by excusing a whole component.</p>
     */
    private static final Set<String> MEASUREMENT_DEPENDENT_COVERAGE =
            Set.of("parentLabelObscured");

    // ==================== the headline: a view of zones has groups ====================

    @Test
    public void shouldReportHasGroups_whenTheTopLevelHoldsOnlyGroupingElements() {
        assertTrue("a view whose top level holds only populated Grouping containers has groups",
                assess(Kind.GROUPING, Fixture.ZONES).hasGroups());
    }

    @Test
    public void shouldReportHasGroups_whenTheTopLevelHoldsNativeGroups() {
        assertTrue("negative control — native groups must keep reporting hasGroups",
                assess(Kind.NATIVE, Fixture.ZONES).hasGroups());
    }

    // ============ the coverage declaration: labelOnGroup must examine what it certifies ============

    @Test
    public void shouldCountALabelOnAGroupingTitleBand_asALabelOnGroup() {
        assertEquals("labelOnGroup declares itself fully covered, so it must examine a Grouping's"
                        + " title band rather than certify a zero it never looked for",
                1, assess(Kind.GROUPING, Fixture.LABEL_ON_TITLE_BAND).labelOnGroupCount());
    }

    @Test
    public void shouldCountALabelOnANativeGroupTitleBand_asALabelOnGroup() {
        assertEquals("negative control", 1,
                assess(Kind.NATIVE, Fixture.LABEL_ON_TITLE_BAND).labelOnGroupCount());
    }

    @Test
    public void shouldNotAlsoCountThatLabelAsAnElementOverlap() {
        // The two dimensions document a division of labour: labelOverlaps skips container hosts
        // because labelOnGroup owns the title-band case. One label over one zone must land in
        // exactly one of them, or it is simultaneously a false positive and a false negative.
        assertEquals("a label over a Grouping must not be double-counted as an element overlap",
                0, assess(Kind.GROUPING, Fixture.LABEL_ON_TITLE_BAND).labelOverlapCount());
    }

    @Test
    public void shouldNotCountALabelOverANativeGroupAsAnElementOverlap() {
        assertEquals("negative control", 0,
                assess(Kind.NATIVE, Fixture.LABEL_ON_TITLE_BAND).labelOverlapCount());
    }

    // ============ the false-positive family: a transparent zone is not an obstruction ============

    @Test
    public void shouldNotCountAConnectionCrossingAGroupingAsAPassThrough() {
        AssessLayoutResultDto d = assess(Kind.GROUPING, Fixture.LABEL_ON_TITLE_BAND);
        assertEquals("a transparent zone is not an obstruction — the connection crosses no element",
                null, d.connectionPassThroughs());
    }

    @Test
    public void shouldNotCountAConnectionCrossingANativeGroupAsAPassThrough() {
        assertEquals("negative control", null,
                assess(Kind.NATIVE, Fixture.LABEL_ON_TITLE_BAND).connectionPassThroughs());
    }

    // ============ containers nested inside containers ============

    /**
     * A {@code Grouping} nested inside another {@code Grouping} must still read as a container.
     *
     * <p>The predicate the collector uses lives on a helper named for the <em>top-level</em>
     * arrangement family, so the obvious worry is that it encodes top-level-ness and would classify
     * a nested zone as a leaf — reintroducing the whole defect one level down. It does not: the
     * "top-level" restriction lives in that helper's collection methods, not in its predicate, and
     * the collector calls only the predicate. This test is what makes that a fact rather than a
     * reading, since no other fixture nests a container inside a container.
     */
    @Test
    public void shouldTreatAGroupingNestedInsideAGroupingAsAContainer() {
        assertParity(Fixture.NESTED_ZONES);
    }

    @Test
    public void shouldReportHasGroups_whenTheOnlyContainersAreNested() {
        assertTrue("a nested zone is still a container",
                assess(Kind.GROUPING, Fixture.NESTED_ZONES).hasGroups());
    }

    @Test
    public void shouldNotCountTheInnerZoneAsAnAlignmentParticipant() {
        // The alignment score filters containers out of the participant set. If a nested container
        // were misclassified as a leaf it would join that set and move the score, so this reads the
        // nested classification through a metric rather than through the flag alone.
        assertEquals("a nested container is filtered from alignment exactly as a top-level one is",
                assess(Kind.NATIVE, Fixture.NESTED_ZONES).alignmentScore(),
                assess(Kind.GROUPING, Fixture.NESTED_ZONES).alignmentScore());
    }

    // ==================== whole-response parity between the two kinds ====================

    @Test
    public void shouldAssessAGroupingViewIdenticallyToTheNativeGroupView_zones() {
        assertParity(Fixture.ZONES);
    }

    @Test
    public void shouldAssessAGroupingViewIdenticallyToTheNativeGroupView_labelOnTitleBand() {
        assertParity(Fixture.LABEL_ON_TITLE_BAND);
    }

    /**
     * Every published component of the response must agree between the two container kinds, save
     * the measured-title asymmetry declared above. Reports every disagreement at once rather than
     * failing on the first, so a regression is diagnosed in one run.
     */
    private void assertParity(Fixture fixture) {
        AssessLayoutResultDto nativeResult = assess(Kind.NATIVE, fixture);
        AssessLayoutResultDto groupingResult = assess(Kind.GROUPING, fixture);
        StringBuilder differences = new StringBuilder();
        for (RecordComponent rc : AssessLayoutResultDto.class.getRecordComponents()) {
            // The coverage map is compared entry by entry rather than whole, so one dimension can
            // be excused by name without excusing the other thirty-five alongside it.
            if ("coverage".equals(rc.getName())) {
                appendCoverageDifferences(nativeResult, groupingResult, differences);
                continue;
            }
            // Two components are PROJECTIONS of the coverage map: the derived list of contextual
            // downgrades, and the suggestion prose that names them. A difference in the excused
            // dimension therefore reaches all three surfaces, and excusing it in the map alone
            // would leave the same measured, legitimate asymmetry failing here twice over.
            //
            // Scoped, not blanket. Only the entries attributable to an excused dimension are
            // withheld; every other suggestion and every other named dimension is still compared,
            // so a real prose divergence between the two container kinds still fails.
            String a = String.valueOf(withoutExcusedDimensions(read(rc, nativeResult)));
            String b = String.valueOf(withoutExcusedDimensions(read(rc, groupingResult)));
            if (!a.equals(b)) {
                differences.append("\n  ").append(rc.getName())
                        .append("\n    native   = ").append(a)
                        .append("\n    grouping = ").append(b);
            }
        }
        assertEquals("assess-layout must not answer differently for two views that differ only in"
                + " which container kind holds identical geometry (" + fixture + "):"
                + differences, 0, differences.length());
    }

    /**
     * A component value with any entry naming a measurement-dependent dimension removed.
     *
     * <p>Applies only to list-valued components, which is where the projections live; every other
     * value is returned untouched, so a scalar cannot be silently excused by this filter. A string
     * that does not mention an excused dimension survives, so the filter cannot swallow an
     * unrelated difference that happens to sit in the same list.</p>
     */
    private static Object withoutExcusedDimensions(Object value) {
        if (!(value instanceof List<?> entries)) {
            return value;
        }
        List<Object> kept = new ArrayList<>();
        for (Object entry : entries) {
            String text = String.valueOf(entry);
            boolean excused = false;
            for (String dimension : MEASUREMENT_DEPENDENT_COVERAGE) {
                if (text.contains(dimension)) {
                    excused = true;
                    break;
                }
            }
            if (!excused) {
                kept.add(entry);
            }
        }
        return kept;
    }

    /** Coverage compared per dimension, excusing only the measurement-dependent one. */
    private void appendCoverageDifferences(AssessLayoutResultDto nativeResult,
            AssessLayoutResultDto groupingResult, StringBuilder differences) {
        Map<String, String> nativeCoverage = nativeResult.coverage();
        Map<String, String> groupingCoverage = groupingResult.coverage();
        Set<String> dimensions = new TreeSet<>(nativeCoverage.keySet());
        dimensions.addAll(groupingCoverage.keySet());
        for (String dimension : dimensions) {
            if (MEASUREMENT_DEPENDENT_COVERAGE.contains(dimension)) {
                continue;
            }
            String a = nativeCoverage.get(dimension);
            String b = groupingCoverage.get(dimension);
            if (!String.valueOf(a).equals(String.valueOf(b))) {
                differences.append("\n  coverage.").append(dimension)
                        .append("\n    native   = ").append(a)
                        .append("\n    grouping = ").append(b);
            }
        }
    }

    private static Object read(RecordComponent rc, Object o) {
        try {
            return rc.getAccessor().invoke(o);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not read " + rc.getName(), e);
        }
    }

    // ==================== fixtures ====================

    private enum Kind { NATIVE, GROUPING }

    private enum Fixture { ZONES, LABEL_ON_TITLE_BAND, NESTED_ZONES }

    /**
     * An outer zone holding an inner zone, which in turn holds the only leaf on the view — the
     * containment depth the shipped deployment guidance produces (cloud → account → subnet) and the
     * one shape that distinguishes a container predicate from a top-level one.
     */
    private IArchimateModel nestedZonesFixture(Kind kind) {
        Ctx c = newView();
        IDiagramModelObject outer = addZone(c, kind, "Outer Zone", "zo", 0, 0, 420, 300);
        IDiagramModelObject inner = addNestedZone(c, kind, outer, "Inner Zone", "zi", 30, 60, 320, 200);
        addHost(c, inner, "Inner Host", "hi", 20, 60, 120, 55);
        addTopLevelHost(c, "Outside Host", "ho", 600, 100, 120, 55);
        connect(c, "obj-hi-n", "obj-ho-n", "reaches");
        return c.model;
    }

    /**
     * Three zones side by side, each holding one host, with a relationship from the leftmost host
     * to the rightmost. The straight path crosses the middle zone <em>and</em> the host inside it,
     * so this view has a genuine pass-through either way — it is the shape that exposes
     * {@code hasGroups} and the suggestion prose without confounding them with a false positive.
     */
    private IArchimateModel zonesFixture(Kind kind) {
        Ctx c = newView();
        IDiagramModelObject a = addZone(c, kind, "Zone A", "za", 0, 0, 220, 160);
        addHost(c, a, "Zone A Host", "za", 20, 60, 120, 55);
        IDiagramModelObject b = addZone(c, kind, "Zone B Middle", "zb", 300, 0, 220, 160);
        addHost(c, b, "Zone B Host", "zb", 20, 60, 120, 55);
        IDiagramModelObject d = addZone(c, kind, "Zone C", "zc", 600, 0, 220, 160);
        addHost(c, d, "Zone C Host", "zc", 20, 60, 120, 55);
        connect(c, "obj-za-n", "obj-zc-n", "carries traffic to");
        return c.model;
    }

    /**
     * Two hosts at the far left and far right of the canvas, wired together, with an empty-topped
     * zone straddling the midpoint of their straight path. The path runs across the zone's title
     * band and above the zone's only child, so the label lands on the title band and the line
     * crosses no element at all.
     *
     * <p>The geometry is derived from the detector's own constants rather than guessed: the label
     * box is centred on the path midpoint and is {@code ESTIMATED_LABEL_HEIGHT} tall, and the band
     * the detector tests is the zone's top {@code ESTIMATED_LABEL_HEIGHT} strip — so putting both
     * host centres on the band's mid-line puts the label squarely inside it.
     */
    private IArchimateModel labelOnTitleBandFixture(Kind kind) {
        Ctx c = newView();
        int zoneTop = 190;
        int bandMid = zoneTop + (int) (LayoutQualityAssessor.ESTIMATED_LABEL_HEIGHT / 2);
        int hostHeight = 40;
        int hostTop = bandMid - hostHeight / 2;

        addTopLevelHost(c, "Left Host", "hl", 40, hostTop, 120, hostHeight);
        addTopLevelHost(c, "Right Host", "hr", 740, hostTop, 120, hostHeight);

        IDiagramModelObject zone = addZone(c, kind, "Zone T", "zt", 350, zoneTop, 220, 160);
        // Low enough that the horizontal path across the band misses it entirely.
        addHost(c, zone, "Zone T Host", "zt", 20, 90, 120, 50);

        connect(c, "obj-hl-n", "obj-hr-n", "on");
        return c.model;
    }

    // ==================== EMF construction ====================

    private static final class Ctx {
        IArchimateModel model;
        IArchimateDiagramModel view;
    }

    private Ctx newView() {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        Ctx c = new Ctx();
        c.model = f.createArchimateModel();
        c.model.setName("Zone Assessment");
        c.model.setId("model-zones");
        c.model.setDefaults();
        c.view = f.createArchimateDiagramModel();
        c.view.setId(VIEW_ID);
        c.view.setName("Zones");
        c.model.getFolder(FolderType.DIAGRAMS).getElements().add(c.view);
        return c;
    }

    /** A zone of the requested kind. Same id, name and bounds whichever kind is built. */
    private IDiagramModelObject addZone(Ctx c, Kind kind, String name, String id,
            int x, int y, int w, int h) {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IDiagramModelObject zone;
        if (kind == Kind.NATIVE) {
            IDiagramModelGroup g = f.createDiagramModelGroup();
            g.setId("obj-" + id);
            g.setName(name);
            zone = g;
        } else {
            IGrouping concept = f.createGrouping();
            concept.setId(id);
            concept.setName(name);
            c.model.getFolder(FolderType.OTHER).getElements().add(concept);
            IDiagramModelArchimateObject o = f.createDiagramModelArchimateObject();
            o.setId("obj-" + id);
            o.setArchimateConcept(concept);
            zone = o;
        }
        zone.setBounds(x, y, w, h);
        c.view.getChildren().add(zone);
        return zone;
    }

    /** As {@link #addZone}, but nested inside an existing container rather than on the view. */
    private IDiagramModelObject addNestedZone(Ctx c, Kind kind, IDiagramModelObject parent,
            String name, String id, int x, int y, int w, int h) {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IDiagramModelObject zone;
        if (kind == Kind.NATIVE) {
            IDiagramModelGroup g = f.createDiagramModelGroup();
            g.setId("obj-" + id);
            g.setName(name);
            zone = g;
        } else {
            IGrouping concept = f.createGrouping();
            concept.setId(id);
            concept.setName(name);
            c.model.getFolder(FolderType.OTHER).getElements().add(concept);
            IDiagramModelArchimateObject o = f.createDiagramModelArchimateObject();
            o.setId("obj-" + id);
            o.setArchimateConcept(concept);
            zone = o;
        }
        zone.setBounds(x, y, w, h);
        ((IDiagramModelContainer) parent).getChildren().add(zone);
        return zone;
    }

    private void addHost(Ctx c, IDiagramModelObject parent, String name, String id,
            int x, int y, int w, int h) {
        IDiagramModelArchimateObject host = newHost(c, name, id, x, y, w, h);
        ((IDiagramModelContainer) parent).getChildren().add(host);
    }

    private void addTopLevelHost(Ctx c, String name, String id, int x, int y, int w, int h) {
        c.view.getChildren().add(newHost(c, name, id, x, y, w, h));
    }

    private IDiagramModelArchimateObject newHost(Ctx c, String name, String id,
            int x, int y, int w, int h) {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        INode node = f.createNode();
        node.setId(id + "-n");
        node.setName(name);
        c.model.getFolder(FolderType.TECHNOLOGY).getElements().add(node);
        IDiagramModelArchimateObject obj = f.createDiagramModelArchimateObject();
        obj.setId("obj-" + id + "-n");
        obj.setArchimateConcept(node);
        obj.setBounds(x, y, w, h);
        return obj;
    }

    /**
     * An ArchiMate connection carrying a named relationship, so the assessor has a label box to
     * position. Built directly rather than through {@code createRelationship}: that path consults
     * Archi's static validity matrix, which needs an OSGi context this fixture does not have,
     * while everything under test reads only the view's connection graph.
     */
    private void connect(Ctx c, String sourceObjId, String targetObjId, String relationshipName) {
        IArchimateFactory f = IArchimateFactory.eINSTANCE;
        IDiagramModelArchimateObject src = findObject(c, sourceObjId);
        IDiagramModelArchimateObject tgt = findObject(c, targetObjId);
        IArchimateRelationship rel = f.createAssociationRelationship();
        rel.setId("rel-" + sourceObjId + "-" + targetObjId);
        rel.setName(relationshipName);
        rel.setSource(src.getArchimateConcept());
        rel.setTarget(tgt.getArchimateConcept());
        c.model.getFolder(FolderType.RELATIONS).getElements().add(rel);

        IDiagramModelArchimateConnection conn = f.createDiagramModelArchimateConnection();
        conn.setId("conn-" + sourceObjId + "-" + targetObjId);
        conn.setArchimateRelationship(rel);
        conn.connect(src, tgt);
    }

    private IDiagramModelArchimateObject findObject(Ctx c, String id) {
        IDiagramModelArchimateObject found = findObject(c.view, id);
        if (found == null) {
            throw new AssertionError("no view object with id " + id);
        }
        return found;
    }

    private IDiagramModelArchimateObject findObject(IDiagramModelContainer container, String id) {
        for (IDiagramModelObject child : container.getChildren()) {
            if (id.equals(child.getId()) && child instanceof IDiagramModelArchimateObject o) {
                return o;
            }
            if (child instanceof IDiagramModelContainer nested) {
                IDiagramModelArchimateObject found = findObject(nested, id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // ==================== harness ====================

    private AssessLayoutResultDto assess(Kind kind, Fixture fixture) {
        IArchimateModel model = switch (fixture) {
            case ZONES -> zonesFixture(kind);
            case LABEL_ON_TITLE_BAND -> labelOnTitleBandFixture(kind);
            case NESTED_ZONES -> nestedZonesFixture(kind);
        };
        StubEditorModelManager mgr = new StubEditorModelManager();
        mgr.setModels(List.of(model));
        ArchiModelAccessorImpl accessor = newAccessor(mgr, model);
        try {
            return accessor.assessLayout(VIEW_ID, true);
        } finally {
            accessor.dispose();
        }
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

    /** Guards against a fixture drifting so far that a test asserts on an empty view. */
    @Test
    public void fixturesShouldBeNonDegenerate() {
        assertFalse("zones fixture must carry a connection",
                assess(Kind.GROUPING, Fixture.ZONES).connectionCount() == 0);
        assertEquals("label fixture must carry exactly the four objects it reasons about",
                4, assess(Kind.GROUPING, Fixture.LABEL_ON_TITLE_BAND).elementCount());
    }
}
