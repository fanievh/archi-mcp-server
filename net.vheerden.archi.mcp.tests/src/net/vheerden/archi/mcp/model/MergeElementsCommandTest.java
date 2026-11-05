package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimatePackage;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.IJunction;
import com.archimatetool.model.IProfile;
import com.archimatetool.model.IProperty;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.MergeClusterRequest;
import net.vheerden.archi.mcp.response.dto.MergeElementsResultDto;
import net.vheerden.archi.mcp.response.dto.MergeElementsResultDto.Cluster;

/**
 * What {@code merge-elements} does to a model: re-points in place, removes the emptied duplicates,
 * reports what it found, and undoes exactly.
 *
 * <p>Most cases go through the accessor against a dispatcher that executes the command directly
 * and keeps it, so a case can undo and redo the very entry the stack would hold. The comparisons
 * use a structural snapshot of every object in the model (its container, its index among its
 * siblings, every persisted attribute and every persisted reference), so "unchanged" means
 * unchanged in everything the model would save, and a difference is listed in full rather than
 * stopping at the first.</p>
 */
public class MergeElementsCommandTest {

    private static final String SESSION = "merge-session";

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private final StubEditorModelManager models = new StubEditorModelManager();
    private IArchimateModel model;
    private IArchimateDiagramModel viewA;
    private IArchimateDiagramModel viewB;
    private ArchiModelAccessorImpl accessor;
    private Command lastDispatched;
    private Runnable beforeExecute;

    @Before
    public void setUp() {
        model = factory.createArchimateModel();
        model.setId("model-merge");
        model.setName("Merge Fixture");
        model.setDefaults();
        viewA = view("view-a", "View A");
        viewB = view("view-b", "View B");
        models.setModels(List.of(model));
        accessor = createAccessor(model);
    }

    @After
    public void tearDown() {
        accessor.dispose();
    }

    // ---- an undo or redo that fails partway ----------------------------------------------------

    /**
     * The stack drops an entry whose undo throws from both stacks, so the merge must not stay half
     * undone: every step is reverted past the one that fails, the box included, and the failure still
     * reaches the stack.
     */
    @Test
    public void shouldRevertEveryStep_pastOneThatFails_whenAnUndoFailsMidList() {
        IArchimateElement t = actor("t", "Customer");
        IArchimateElement s = actor("s", "Customer");
        IArchimateElement x = component("x", "Billing");
        IArchimateRelationship out = association("r-out", s, x);
        IDiagramModelArchimateObject box = place(viewA, s, "box-s", 0, 0, 120, 55);
        Map<String, String> before = snapshot(model, Set.of());
        merge(cluster("t", "s"));
        assertSame("fixture check: the box moved to the survivor", t, box.getArchimateElement());
        IllegalStateException failure = throwOnceWhenSourceBecomes(out, s);

        try {
            lastDispatched.undo();
            fail("the undo did not throw");
        } catch (IllegalStateException e) {
            assertSame(failure, e);
        }

        assertSame("the box, reverted after the failing end step, is back on s", s, box.getArchimateElement());
        assertSnapshot("the merge is fully undone", before, snapshot(model, Set.of()));
    }

    /**
     * A redo that fails partway puts back what it re-applied, the failing step included, so the merge
     * is not left half redone where no entry can reach it.
     */
    @Test
    public void shouldPutBackWhatItReapplied_whenARedoFailsMidList() {
        IArchimateElement t = actor("t", "Customer");
        IArchimateElement s = actor("s", "Customer");
        IArchimateElement x = component("x", "Billing");
        IArchimateRelationship out = association("r-out", s, x);
        IDiagramModelArchimateObject box = place(viewA, s, "box-s", 0, 0, 120, 55);
        Map<String, String> before = snapshot(model, Set.of());
        merge(cluster("t", "s"));
        lastDispatched.undo();
        IllegalStateException failure = throwOnceWhenSourceBecomes(out, t);

        try {
            lastDispatched.redo();
            fail("the redo did not throw");
        } catch (IllegalStateException e) {
            assertSame(failure, e);
        }

        assertSame("the box re-pointed before the failure is put back", s, box.getArchimateElement());
        assertSame("the failing end step is put back too", s, out.getSource());
        assertSnapshot("nothing of the merge is left", before, snapshot(model, Set.of()));
    }

    /** Throws the returned failure once, after {@code relationship}'s source has been set to {@code end}. */
    private static IllegalStateException throwOnceWhenSourceBecomes(IArchimateRelationship relationship,
            IArchimateConcept end) {
        IllegalStateException failure = new IllegalStateException("listener failed");
        relationship.eAdapters().add(new org.eclipse.emf.common.notify.impl.AdapterImpl() {
            private boolean armed = true;

            @Override
            public void notifyChanged(org.eclipse.emf.common.notify.Notification n) {
                if (armed && n.getFeature() == IArchimatePackage.eINSTANCE.getArchimateRelationship_Source()
                        && n.getNewValue() == end) {
                    armed = false;
                    throw failure;
                }
            }
        });
        return failure;
    }

    // ---- re-point in place --------------------------------------------------------------------

    @Test
    public void shouldKeepEveryIdLayoutAndStyle_andChangeOnlyWhatTheMergeOwns_whenMergingIntoTarget() {
        IArchimateElement t = actor("t", "Customer");
        IArchimateElement s = actor("s", "Customer");
        IArchimateElement x = component("x", "Billing");
        IArchimateElement y = component("y", "CRM");
        IArchimateRelationship out = association("r-out", s, x);
        out.setName("uses");
        out.setDocumentation("rel docs");
        property(out, "weight", "3");
        IArchimateRelationship in = association("r-in", y, s);
        IArchimateRelationship untouched = association("r-other", t, y);

        IDiagramModelArchimateObject group = place(viewA, y, "box-y", 10, 10, 400, 300);
        IDiagramModelArchimateObject boxS = place(group, s, "box-s", 20, 30, 120, 55);
        IDiagramModelArchimateObject child = place(boxS, x, "box-x-in-s", 5, 5, 60, 30);
        boxS.setFillColor("#ff0000");
        boxS.setFontColor("#00ff00");
        boxS.setLineColor("#0000ff");
        boxS.setAlpha(120);
        boxS.setTextAlignment(1);
        boxS.setTextPosition(2);
        boxS.setType(1);
        boxS.getFeatures().putString("labelExpression", "${name}!");
        IDiagramModelArchimateObject boxX = place(viewA, x, "box-x", 600, 30, 120, 55);
        IDiagramModelArchimateConnection drawn = connect(out, boxS, boxX, "conn-out");
        bendpoint(drawn, 10, 20);
        bendpoint(drawn, 30, 40);
        drawn.setTextPosition(0);
        drawn.getFeatures().putString("labelOffset", "5,6");
        IDiagramModelArchimateObject boxT = place(viewB, t, "box-t", 0, 0, 120, 55);
        IDiagramModelArchimateObject boxS2 = place(viewB, s, "box-s-b", 300, 0, 120, 55);
        place(viewB, y, "box-y-b", 600, 0, 120, 55);
        connect(untouched, boxT, place(viewB, y, "box-y-b2", 0, 300, 120, 55), "conn-other");

        Set<String> gone = Set.of("s");
        Map<String, String> before = snapshot(model, gone);

        MergeElementsResultDto result = merge(cluster("t", "s"));

        Map<String, String> after = snapshot(model, gone);
        Map<String, String> expected = new TreeMap<>(before);
        expected.remove("#s");
        expected.replace("#r-out", before.get("#r-out").replace("source->#s", "source->#t"));
        expected.replace("#r-in", before.get("#r-in").replace("target->#s", "target->#t"));
        expected.replace("#box-s", before.get("#box-s").replace("archimateElement->#s",
                "archimateElement->#t"));
        expected.replace("#box-s-b", before.get("#box-s-b").replace("archimateElement->#s",
                "archimateElement->#t"));
        assertSnapshot("only the two relationship ends and the two boxes may change, and the duplicate "
                + "must be gone; ids, nesting, sibling order, bounds, bendpoints, styling, label offset, "
                + "names, documentation and properties stay", expected, after);

        assertSame(t, out.getSource());
        assertSame(t, in.getTarget());
        assertSame(t, boxS.getArchimateElement());
        assertSame(t, boxS2.getArchimateElement());
        assertSame("the nested child stays nested", boxS, child.eContainer());
        assertNull("the duplicate is out of the model", s.getArchimateModel());
        assertTrue(s.getSourceRelationships().isEmpty());
        assertTrue(s.getTargetRelationships().isEmpty());
        assertTrue(s.getReferencingDiagramComponents().isEmpty());

        Cluster c = result.clusters().get(0);
        assertEquals(List.of("s"), c.mergedSourceIds());
        assertEquals(2, c.relationshipsRepointed());
        assertEquals(2, c.diagramObjectsRepointed());
        assertEquals(Map.of("s", "t"), result.idMap());
        assertEquals(List.of(), c.namesDropped());
    }

    @Test
    public void shouldKeepTargetIdNameFolderAndFolderPosition() {
        IArchimateElement before = actor("before", "A");
        IArchimateElement t = actor("t", "Customer");
        IArchimateElement after = actor("after", "B");
        IArchimateElement s = actor("s", "Other name");
        int index = t.eContainer().eContents().indexOf(t);

        MergeElementsResultDto result = merge(cluster("t", "s"));

        assertEquals("t", t.getId());
        assertEquals("Customer", t.getName());
        assertSame(model.getFolder(FolderType.BUSINESS), t.eContainer());
        assertEquals(index, t.eContainer().eContents().indexOf(t));
        assertNotNull(before.getArchimateModel());
        assertNotNull(after.getArchimateModel());
        assertEquals("a duplicate with another name is reported, since that name is gone",
                List.of(new MergeElementsResultDto.NameDropped("s", "Other name")),
                result.clusters().get(0).namesDropped());
        assertNull(s.getArchimateModel());
    }

    /**
     * Giving a box another element notifies nothing, so an open diagram would keep drawing the
     * deleted duplicate. Taking the box out of its parent and putting it back is what the editor
     * hears; that notification is the redraw, and the box must land at the index it had. Undo
     * announces itself the same way.
     */
    @Test
    public void shouldAnnounceEachRepointedBoxToItsParent_atTheIndexItHad() {
        IArchimateElement t = actor("t", "Customer");
        IArchimateElement s = actor("s", "Customer");
        IArchimateElement x = actor("x", "Other");
        place(viewA, x, "box-x1", 0, 0, 120, 55);
        IDiagramModelArchimateObject box = place(viewA, s, "box-s", 200, 0, 120, 55);
        place(viewA, x, "box-x2", 400, 0, 120, 55);
        List<String> heard = new ArrayList<>();
        viewA.eAdapters().add(new org.eclipse.emf.common.notify.impl.AdapterImpl() {
            @Override
            public void notifyChanged(org.eclipse.emf.common.notify.Notification n) {
                if (n.getFeature() == IArchimatePackage.eINSTANCE.getDiagramModelContainer_Children()) {
                    Object subject = n.getEventType() == org.eclipse.emf.common.notify.Notification.ADD
                            ? n.getNewValue() : n.getOldValue();
                    heard.add((n.getEventType() == org.eclipse.emf.common.notify.Notification.ADD ? "add " : "remove ")
                            + ((IIdentifier) subject).getId() + " @" + n.getPosition());
                }
            }
        });

        merge(cluster("t", "s"));

        assertSame(t, box.getArchimateElement());
        assertEquals(List.of("remove box-s @1", "add box-s @1"), heard);
        heard.clear();
        lastDispatched.undo();
        assertSame(s, box.getArchimateElement());
        assertEquals(List.of("remove box-s @1", "add box-s @1"), heard);
    }

    @Test
    public void shouldLeaveARelationshipOnARelationshipAttached_whenTheRelationshipItHangsOnIsKept() {
        IArchimateElement t = actor("t", "Customer");
        IArchimateElement s = actor("s", "Customer");
        IArchimateElement x = component("x", "Billing");
        IArchimateElement note = component("n", "Note");
        IArchimateRelationship carrier = association("carrier", s, x);
        IArchimateRelationship hanging = association("hanging", note, carrier);

        merge(cluster("t", "s"));

        assertSame(t, carrier.getSource());
        assertSame(carrier, hanging.getTarget());
        assertNotNull("still in the model", hanging.getArchimateModel());
        assertTrue(carrier.getTargetRelationships().contains(hanging));
    }

    /**
     * Archi decides validity from the two ends' types (its matrix cannot load in a plain JVM, so
     * it is not called here). A merge of one type into the same type leaves every relationship
     * joining the same pair of types it joined before, which is why nothing valid becomes invalid.
     */
    @Test
    public void shouldLeaveEveryRelationshipJoiningTheSameTypesAsBefore() {
        actor("t", "Customer");
        IArchimateElement s = actor("s", "Customer");
        IArchimateElement role = role("role", "Buyer");
        IArchimateElement x = component("x", "Billing");
        relationship(IArchimatePackage.eINSTANCE.getAssignmentRelationship(), "assign", s, role);
        relationship(IArchimatePackage.eINSTANCE.getServingRelationship(), "serve", x, s);
        association("assoc", s, x);
        Map<String, String> typesBefore = endTypes();

        merge(cluster("t", "s"));

        assertEquals(typesBefore, endTypes());
    }

    @Test
    public void shouldRepointARelationshipBetweenTwoClusters_toBothTargets_notTreatItAsSelf() {
        IArchimateElement t1 = actor("t1", "A");
        IArchimateElement s1 = actor("s1", "A");
        IArchimateElement t2 = component("t2", "B");
        IArchimateElement s2 = component("s2", "B");
        IArchimateRelationship across = association("across", s1, s2);

        MergeElementsResultDto result = merge(cluster("t1", "s1"), cluster("t2", "s2"));

        assertSame(t1, across.getSource());
        assertSame(t2, across.getTarget());
        assertNotNull(across.getArchimateModel());
        assertEquals(1, result.clusters().get(0).relationshipsRepointed());
        assertEquals(1, result.clusters().get(1).relationshipsRepointed());
        assertEquals("counted once in the totals", 1, result.totals().relationshipsRepointed());
        assertEquals(List.of(), result.clusters().get(0).selfRelationshipsDropped());
        assertEquals(Map.of("s1", "t1", "s2", "t2"), result.idMap());
        assertEquals(2, result.totals().clusters());
        assertEquals(2, result.totals().sourcesMerged());
    }

    @Test
    public void shouldMergeSeveralDuplicatesAndSeveralClustersInOneCall() {
        actor("t", "Customer");
        actor("s1", "Customer");
        actor("s2", "Customer");
        component("u", "Billing");
        component("v", "Billing");

        MergeElementsResultDto result = merge(cluster("t", "s1", "s2"), cluster("u", "v"));

        assertEquals(Map.of("s1", "t", "s2", "t", "v", "u"), result.idMap());
        assertNull(ArchimateModelUtils.getObjectByID(model, "s1"));
        assertNull(ArchimateModelUtils.getObjectByID(model, "s2"));
        assertNull(ArchimateModelUtils.getObjectByID(model, "v"));
        assertEquals(3, result.totals().sourcesMerged());
    }

    // ---- self-relationships -------------------------------------------------------------------

    @Test
    public void shouldDropASelfRelationshipWithItsConnections_byDefault() {
        IArchimateElement t = actor("t", "Customer");
        IArchimateElement s = actor("s", "Customer");
        IArchimateRelationship self = association("self", t, s);
        IDiagramModelArchimateObject boxT = place(viewA, t, "box-t", 0, 0, 120, 55);
        IDiagramModelArchimateObject boxS = place(viewA, s, "box-s", 300, 0, 120, 55);
        IDiagramModelArchimateConnection drawn = connect(self, boxT, boxS, "conn-self");

        MergeElementsResultDto result = merge(cluster("t", "s"));

        assertNull(self.getArchimateModel());
        assertNull(drawn.getDiagramModel());
        assertFalse(boxT.getSourceConnections().contains(drawn));
        assertFalse(boxS.getTargetConnections().contains(drawn));
        assertEquals(List.of(new MergeElementsResultDto.DroppedRelationship("self", "AssociationRelationship",
                MergeElementsPlan.DROPPED_BY_POLICY, List.of("conn-self"))),
                result.clusters().get(0).selfRelationshipsDropped());
        assertNull("kept is not reported under drop", result.clusters().get(0).selfRelationshipsKept());
        assertEquals(0, result.clusters().get(0).relationshipsRepointed());
        assertEquals("both boxes are kept and now show the target", t, boxS.getArchimateElement());
    }

    @Test
    public void shouldTreatARelationshipBetweenTwoDuplicatesAsSelf() {
        actor("t", "Customer");
        IArchimateElement s1 = actor("s1", "Customer");
        IArchimateElement s2 = actor("s2", "Customer");
        IArchimateRelationship between = association("between", s2, s1);

        MergeElementsResultDto result = merge(cluster("t", "s1", "s2"));

        assertNull(between.getArchimateModel());
        assertEquals("between", result.clusters().get(0).selfRelationshipsDropped().get(0).relationshipId());
    }

    @Test
    public void shouldKeepAValidSelfRelationship_andTheConnectionBetweenTheTwoBoxes_whenKeepIsAsked() {
        IArchimateElement t = actor("t", "Customer");
        IArchimateElement s = actor("s", "Customer");
        IArchimateRelationship self = association("self", s, t);
        IDiagramModelArchimateObject boxT = place(viewA, t, "box-t", 0, 0, 120, 55);
        IDiagramModelArchimateObject boxS = place(viewA, s, "box-s", 300, 0, 120, 55);
        IDiagramModelArchimateConnection drawn = connect(self, boxS, boxT, "conn-self");
        bendpoint(drawn, 1, 2);

        MergeElementsResultDto result = runCommand(keeping(), cluster("t", "s"));

        assertNotNull(self.getArchimateModel());
        assertSame(t, self.getSource());
        assertSame(t, self.getTarget());
        assertSame("the connection stays, drawn between the two boxes", boxS, drawn.getSource());
        assertSame(boxT, drawn.getTarget());
        assertEquals(1, drawn.getBendpoints().size());
        assertEquals(List.of("self"), result.clusters().get(0).selfRelationshipsKept());
        assertEquals(List.of(), result.clusters().get(0).selfRelationshipsDropped());
        assertEquals(1, result.clusters().get(0).relationshipsRepointed());
        assertEquals("the rule is asked about the target joined to itself, once for the preview and "
                + "once when the command runs", List.of("t -AssociationRelationship-> t",
                        "t -AssociationRelationship-> t"), asked);
    }

    @Test
    public void shouldDropASelfRelationshipArchiDoesNotAllow_asInvalidSelf_evenWhenKeepIsAsked() {
        IArchimateElement t = actor("t", "Customer");
        IArchimateElement s = actor("s", "Customer");
        // A model can hold a relationship Archi would not create (an import, an old file); the
        // merge must not leave one behind as a relationship of the target with itself.
        IArchimateRelationship invalid = relationship(IArchimatePackage.eINSTANCE.getRealizationRelationship(),
                "realizes", s, t);

        MergeElementsResultDto result = runCommand(keeping(), cluster("t", "s"));

        assertNull(invalid.getArchimateModel());
        assertEquals(MergeElementsPlan.INVALID_SELF,
                result.clusters().get(0).selfRelationshipsDropped().get(0).reason());
        assertEquals(List.of(), result.clusters().get(0).selfRelationshipsKept());
    }

    @Test
    public void shouldNotTouchASelfRelationshipTheTargetAlreadyHad() {
        IArchimateElement t = actor("t", "Customer");
        actor("s", "Customer");
        IArchimateRelationship own = association("own", t, t);

        MergeElementsResultDto result = merge(cluster("t", "s"));

        assertNotNull(own.getArchimateModel());
        assertEquals(List.of(), result.clusters().get(0).selfRelationshipsDropped());
    }

    @Test
    public void shouldCascadeOverWhatHangsOnADroppedSelfRelationship_andReportEveryId() {
        IArchimateElement t = actor("t", "Customer");
        IArchimateElement s = actor("s", "Customer");
        IArchimateElement x = component("x", "Billing");
        IArchimateRelationship self = association("self", t, s);
        IArchimateRelationship onSelf = association("on-self", x, self);
        IArchimateRelationship onOnSelf = association("on-on-self", onSelf, x);
        IDiagramModelArchimateObject boxT = place(viewA, t, "box-t", 0, 0, 120, 55);
        IDiagramModelArchimateObject boxS = place(viewA, s, "box-s", 300, 0, 120, 55);
        IDiagramModelArchimateObject boxX = place(viewA, x, "box-x", 150, 200, 120, 55);
        IDiagramModelArchimateConnection selfDrawn = connect(self, boxT, boxS, "conn-self");
        IDiagramModelArchimateConnection onSelfDrawn = connectTo(onSelf, boxX, selfDrawn, "conn-on-self");
        IDiagramModelNote note = factory.createDiagramModelNote();
        note.setId("note");
        viewA.getChildren().add(note);
        IDiagramModelConnection noteLink = factory.createDiagramModelConnection();
        noteLink.setId("note-link");
        noteLink.connect(note, onSelfDrawn);

        MergeElementsResultDto result = merge(cluster("t", "s"));

        Cluster c = result.clusters().get(0);
        assertNull(self.getArchimateModel());
        assertNull(onSelf.getArchimateModel());
        assertNull(onOnSelf.getArchimateModel());
        assertEquals(List.of("on-self", "on-on-self"), c.cascadedRelationshipIds());
        assertEquals(new TreeSet<>(List.of("conn-on-self", "note-link")), new TreeSet<>(c.cascadedConnectionIds()));
        assertEquals(List.of("conn-self"), c.selfRelationshipsDropped().get(0).connectionIds());
        assertFalse("nothing left hanging on the note", note.getSourceConnections().contains(noteLink));
        assertFalse(boxX.getSourceConnections().contains(onSelfDrawn));
        assertNotNull("the boxes stay", boxX.getDiagramModel());
        assertNotNull(note.getDiagramModel());
    }

    @Test
    public void shouldAskArchisOwnRule_whenTheCallerAsksToKeepSelfRelationships() throws Exception {
        assertSame(MergeElementsPlan.ARCHI, MergeElementsOperations.options(null, "keep", false, false).rule());
        String source = java.nio.file.Files.readString(repoFile(
                "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/MergeElementsPlan.java"));
        assertTrue("production's rule is Archi's isValidRelationship and nothing else",
                source.contains("static final RelationshipRule ARCHI = ArchimateModelUtils::isValidRelationship;"));
    }

    @Test
    public void shouldNotAskTheRule_underTheDefaultDrop() {
        actor("t", "Customer");
        IArchimateElement s = actor("s", "Customer");
        association("self", s, (IArchimateElement) ArchimateModelUtils.getObjectByID(model, "t"));

        runCommand(new MergeElementsPlan.Options(MergeElementsPlan.PropertyPolicy.UNION,
                MergeElementsPlan.SelfPolicy.DROP, false, false, associationsOnly), cluster("t", "s"));

        assertEquals(List.of(), asked);
    }

    // ---- reports ------------------------------------------------------------------------------

    @Test
    public void shouldReportParallelRelationships_andKeepBoth() {
        IArchimateElement t = actor("t", "Customer");
        IArchimateElement s = actor("s", "Customer");
        IArchimateElement x = component("x", "Billing");
        IArchimateRelationship onTarget = association("on-target", t, x);
        IArchimateRelationship onDuplicate = association("on-duplicate", s, x);
        association("second-on-duplicate", s, x);
        // Another type, and the other direction: parallel to none of the three.
        relationship(IArchimatePackage.eINSTANCE.getServingRelationship(), "serving", x, s);
        // Parallel before the merge and untouched by it: not this merge's finding.
        association("old-1", x, t);
        association("old-2", x, t);

        MergeElementsResultDto result = merge(cluster("t", "s"));

        List<MergeElementsResultDto.ParallelPair> pairs = result.clusters().get(0).parallelRelationships();
        assertEquals("on-target, on-duplicate and second-on-duplicate now share both ends and type: three pairs",
                3, pairs.size());
        for (MergeElementsResultDto.ParallelPair pair : pairs) {
            assertEquals("AssociationRelationship", pair.type());
            assertFalse(pair.relationshipIds().contains("old-1"));
        }
        assertNotNull(onTarget.getArchimateModel());
        assertNotNull(onDuplicate.getArchimateModel());
        assertEquals(3, result.totals().parallelRelationshipPairs());
    }

    /**
     * A loop a duplicate already had is not a loop the merge creates: it moves to the target, as
     * the target's own loop stays, and is counted as re-pointed under either policy.
     */
    @Test
    public void shouldMoveADuplicatesOwnLoopToTheTarget_ratherThanDropIt() {
        IArchimateElement t = actor("t", "Customer");
        IArchimateElement s = actor("s", "Customer");
        IArchimateRelationship loop = association("loop", s, s);
        IDiagramModelArchimateObject box = place(viewA, s, "box-s", 0, 0, 120, 55);
        IDiagramModelArchimateConnection drawn = connectTo(loop, box, box, "conn-loop");

        MergeElementsResultDto.Cluster c = merge(cluster("t", "s")).clusters().get(0);

        assertNotNull(loop.getArchimateModel());
        assertSame(t, loop.getSource());
        assertSame(t, loop.getTarget());
        assertSame(box, drawn.getSource());
        assertEquals(1, c.relationshipsRepointed());
        assertEquals(List.of(), c.selfRelationshipsDropped());
    }

    /** Lineage from an earlier merge is kept whole: a list of ids, not one value of a key. */
    @Test
    public void shouldCopyADuplicatesMergedFromLineage_underUnion_withoutAConflict() {
        IArchimateElement t = actor("t", "Customer");
        IArchimateElement s = actor("s", "Customer");
        property(t, "mergedFrom", "older-t");
        property(s, "mergedFrom", "older-s");
        property(s, "mergedFrom", "older-t");

        MergeElementsResultDto.Cluster c = merge(cluster("t", "s")).clusters().get(0);

        assertEquals(List.of("mergedFrom=older-t", "mergedFrom=older-s"), pairs(t));
        assertEquals(List.of(), c.propertyConflicts());
    }

    @Test
    public void shouldHeadAnUnnamedDuplicatesDocumentation_withItsIdAlone() {
        IArchimateElement t = actor("t", "Customer");
        IArchimateElement s = actor("s", null);
        s.setDocumentation("Text.");
        t.setDocumentation("Own.");

        merge(cluster("t", "s"));

        assertEquals("Own.\n\nfrom [s]:\nText.", t.getDocumentation());
    }

    /**
     * The preview predicts the parallels from the unwritten model; the result reads them from the
     * written one. On a model nobody edits in between, the two must agree, pair for pair — and
     * reading the preview must leave every relationship list as it was, since those lists are the
     * model's own.
     */
    @Test
    public void shouldPreviewTheParallelsTheMergeWillReport_withoutTouchingTheModel() {
        IArchimateElement t = actor("t", "Customer");
        IArchimateElement s = actor("s", "Customer");
        IArchimateElement x = component("x", "Billing");
        association("on-target", t, x);
        association("on-duplicate", s, x);
        association("into-target", x, t);
        association("into-duplicate", x, s);
        // Two self-relationships the merge drops, each with one hanging on it: the preview must
        // predict a model without all four.
        association("on-self", association("self", s, t), x);
        association("on-self-too", x, association("self-back", t, s));
        Map<IArchimateConcept, List<String>> lists = new IdentityHashMap<>();
        for (IArchimateConcept concept : List.of(t, s, x)) {
            lists.put(concept, relationshipLists(concept));
        }

        PreparedMutation<MergeElementsResultDto> prepared = MergeElementsOperations.prepare(model,
                List.of(cluster("t", "s")), MergeElementsOperations.options(null, null, false, false));
        lists.forEach((concept, before) -> assertEquals("reading the preview must not change "
                + ((IIdentifier) concept).getId() + "'s relationship lists", before, relationshipLists(concept)));
        List<MergeElementsResultDto.ParallelPair> previewed =
                prepared.entity().clusters().get(0).parallelRelationships();

        prepared.command().execute();
        List<MergeElementsResultDto.ParallelPair> reported =
                ((MergeElementsCommand) prepared.command()).getResult().clusters().get(0).parallelRelationships();

        assertEquals(2, reported.size());
        assertEquals(reported, previewed);
    }

    private static List<String> relationshipLists(IArchimateConcept concept) {
        List<String> ids = new ArrayList<>();
        concept.getSourceRelationships().forEach(r -> ids.add("out " + r.getId()));
        concept.getTargetRelationships().forEach(r -> ids.add("in " + r.getId()));
        return ids;
    }

    @Test
    public void shouldReportAViewThatNowShowsTheTargetTwice_includingANestedBox() {
        IArchimateElement t = actor("t", "Customer");
        IArchimateElement s = actor("s", "Customer");
        IDiagramModelArchimateObject boxT = place(viewA, t, "box-t", 0, 0, 400, 300);
        IDiagramModelArchimateObject nested = place(boxT, s, "box-s", 10, 30, 120, 55);
        place(viewB, s, "box-s-alone", 0, 0, 120, 55);

        MergeElementsResultDto result = merge(cluster("t", "s"));

        assertEquals(List.of(new MergeElementsResultDto.DoublePlacement("view-a", "View A",
                List.of("box-t", "box-s"))), result.clusters().get(0).doublePlacements());
        assertSame("every box is kept", boxT, nested.eContainer());
        assertSame(t, nested.getArchimateElement());
    }

    @Test
    public void shouldReportASpecializationTheTargetDoesNotShare_withoutCopyingIt() {
        IArchimateElement t = actor("t", "Customer");
        IArchimateElement s = actor("s", "Customer");
        IProfile vip = factory.createProfile();
        vip.setName("VIP");
        vip.setConceptType(t.eClass().getName());
        model.getProfiles().add(vip);
        s.getProfiles().add(vip);

        MergeElementsResultDto result = merge(cluster("t", "s"));

        assertEquals(List.of(new MergeElementsResultDto.SpecializationDiffers("s", "VIP", null)),
                result.clusters().get(0).specializationDiffers());
        assertTrue("the target keeps its own specialization", t.getProfiles().isEmpty());
    }

    // ---- documentation and properties --------------------------------------------------------

    @Test
    public void shouldUnionProperties_skippingExactCopies_copyingMissingKeys_andReportingConflicts() {
        IArchimateElement t = actor("t", "Customer");
        IArchimateElement s = actor("s", "Customer");
        property(t, "tier", "gold");
        property(t, "owner", "ann");
        IProperty sameExact = property(s, "tier", "gold");
        property(s, "owner", "bob");
        IProperty missing = property(s, "region", "emea");
        property(s, "region", "apac");

        MergeElementsResultDto result = merge(cluster("t", "s"));

        assertEquals(List.of("tier=gold", "owner=ann", "region=emea"), pairs(t));
        Cluster c = result.clusters().get(0);
        assertEquals(1, c.propertiesCopied());
        assertEquals(List.of(new MergeElementsResultDto.PropertyConflict("owner", "ann", "bob", "s"),
                new MergeElementsResultDto.PropertyConflict("region", "emea", "apac", "s")),
                c.propertyConflicts());
        assertNull("discarded is reported under keep-target only", c.propertiesDiscarded());
        assertNotSame("a copy, never the source's own property", missing, t.getProperties().get(2));
        assertTrue("the source still owns its property", s.getProperties().contains(missing));
        assertTrue(s.getProperties().contains(sameExact));
    }

    @Test
    public void shouldKeepTheTargetsPropertiesAndDocumentation_andCountWhatIsDiscarded_underKeepTarget() {
        IArchimateElement t = actor("t", "Customer");
        IArchimateElement s = actor("s", "Customer");
        t.setDocumentation("target docs");
        property(t, "tier", "gold");
        s.setDocumentation("source docs");
        property(s, "tier", "silver");
        property(s, "region", "emea");

        MergeElementsResultDto result = accessor.mergeElements(SESSION, List.of(cluster("t", "s")),
                "keep-target", null, false, false).entity();

        assertEquals("target docs", t.getDocumentation());
        assertEquals(List.of("tier=gold"), pairs(t));
        Cluster c = result.clusters().get(0);
        assertEquals(Integer.valueOf(2), c.propertiesDiscarded());
        assertNull("no conflicts are looked for under keep-target", c.propertyConflicts());
        assertNull("documentation is not merged under keep-target", c.documentationAppended());
        assertEquals(0, c.propertiesCopied());
    }

    @Test
    public void shouldCopyEverySourceProperty_keepingDuplicateKeys_underAppend() {
        IArchimateElement t = actor("t", "Customer");
        IArchimateElement s = actor("s", "Customer");
        property(t, "tier", "gold");
        property(s, "tier", "gold");
        property(s, "tier", "silver");

        MergeElementsResultDto result = accessor.mergeElements(SESSION, List.of(cluster("t", "s")),
                "append", null, false, false).entity();

        assertEquals(List.of("tier=gold", "tier=gold", "tier=silver"), pairs(t));
        assertEquals(2, result.clusters().get(0).propertiesCopied());
        assertNull("conflicts are a union finding", result.clusters().get(0).propertyConflicts());
    }

    @Test
    public void shouldAppendOnlyDocumentationThatSaysSomethingNew_withWhereItCameFrom() {
        IArchimateElement t = actor("t", "Customer");
        IArchimateElement same = actor("same", "Customer");
        IArchimateElement first = actor("first", "Client");
        IArchimateElement repeat = actor("repeat", "Customer");
        IArchimateElement blank = actor("blank", "Customer");
        t.setDocumentation("A buyer.");
        same.setDocumentation("A buyer.");
        first.setDocumentation("Pays monthly.");
        repeat.setDocumentation("Pays monthly.");
        blank.setDocumentation("   ");

        MergeElementsResultDto result = merge(cluster("t", "same", "first", "repeat", "blank"));

        assertEquals("A buyer.\n\nfrom Client [first]:\nPays monthly.", t.getDocumentation());
        assertEquals(Integer.valueOf(1), result.clusters().get(0).documentationAppended());
    }

    @Test
    public void shouldNotLeadWithABlankLine_whenTheTargetHasNoDocumentation() {
        IArchimateElement t = actor("t", "Customer");
        IArchimateElement s = actor("s", "Customer");
        s.setDocumentation("Pays monthly.");

        merge(cluster("t", "s"));

        assertEquals("from Customer [s]:\nPays monthly.", t.getDocumentation());
    }

    @Test
    public void shouldRecordEachMergedIdOnTheTarget_whenAsked() {
        IArchimateElement t = actor("t", "Customer");
        actor("s1", "Customer");
        IArchimateElement s2 = actor("s2", "Customer");
        property(s2, "mergedFrom", "older");

        MergeElementsResultDto result = accessor.mergeElements(SESSION, List.of(cluster("t", "s1", "s2")),
                "keep-target", null, true, false).entity();

        assertEquals(List.of("mergedFrom=s1", "mergedFrom=s2"), pairs(t));
        assertTrue(result.recordMergedIds());
    }

    // ---- undo, redo, execute-time ------------------------------------------------------------

    @Test
    public void shouldRestoreTheModelExactly_onOneUndo_andReplayTheSameMerge_onRedo() {
        buildEverything();
        Map<String, String> before = snapshot(model, Set.of());

        merge(cluster("t", "s1", "s2"), cluster("u", "v"));
        Map<String, String> merged = snapshot(model, Set.of());
        assertFalse("precondition: the merge changed the model", before.equals(merged));

        lastDispatched.undo();
        assertSnapshot("one undo must restore every id, end, parent, sibling index, bounds, bendpoint, "
                + "property list and documentation", before, snapshot(model, Set.of()));
        assertNotNull(ArchimateModelUtils.getObjectByID(model, "s1"));
        assertSame(ArchimateModelUtils.getObjectByID(model, "s1"),
                ((IArchimateRelationship) ArchimateModelUtils.getObjectByID(model, "r1")).getSource());

        lastDispatched.redo();
        assertSnapshot("redo must reproduce the merge exactly", merged, snapshot(model, Set.of()));

        lastDispatched.undo();
        assertSnapshot("and undo again", before, snapshot(model, Set.of()));
    }

    @Test
    public void shouldRepointARelationshipAddedToADuplicateAfterPreparing_whenTheCommandRuns() {
        IArchimateElement t = actor("t", "Customer");
        IArchimateElement s = actor("s", "Customer");
        IArchimateElement x = component("x", "Billing");
        association("on-target", t, x);
        PreparedMutation<MergeElementsResultDto> prepared = MergeElementsOperations.prepare(model,
                List.of(cluster("t", "s")), MergeElementsOperations.options(null, null, false, false));
        assertEquals(0, prepared.entity().clusters().get(0).relationshipsRepointed());

        // A human adds a relationship to the duplicate while the proposal waits.
        IArchimateRelationship late = association("late", s, x);
        prepared.command().execute();

        assertSame(t, late.getSource());
        MergeElementsResultDto done = ((MergeElementsCommand) prepared.command()).getResult();
        assertEquals(1, done.clusters().get(0).relationshipsRepointed());
        assertEquals("the parallel it creates is measured on the written model",
                1, done.clusters().get(0).parallelRelationships().size());
        assertNull(s.getArchimateModel());
    }

    @Test
    public void shouldReportWhatTheCommandDid_notWhatWasPrepared_onAnImmediateCall() {
        IArchimateElement t = actor("t", "Customer");
        IArchimateElement s = actor("s", "Customer");
        IArchimateElement x = component("x", "Billing");
        association("on-target", t, x);
        beforeExecute = () -> association("late", s, x);

        MergeElementsResultDto result = merge(cluster("t", "s"));

        assertEquals(1, result.clusters().get(0).relationshipsRepointed());
        assertEquals(1, result.clusters().get(0).parallelRelationships().size());
    }

    @Test
    public void shouldWriteNothing_andSayWhy_whenADuplicateIsGoneWhenTheCommandRuns() {
        actor("t", "Customer");
        IArchimateElement s = actor("s", "Customer");
        PreparedMutation<MergeElementsResultDto> prepared = MergeElementsOperations.prepare(model,
                List.of(cluster("t", "s")), MergeElementsOperations.options(null, null, false, false));
        model.getFolder(FolderType.BUSINESS).getElements().remove(s);
        Map<String, String> before = snapshot(model, Set.of());

        MergeElementsCommand command = (MergeElementsCommand) prepared.command();
        command.execute();

        assertNotNull(command.getSkipReason());
        assertTrue(command.getSkipReason(), command.getSkipReason().contains("Element not found: s"));
        assertNull(command.getResult());
        assertTrue(command.canExecute());
        command.undo();
        command.redo();
        assertSnapshot("a declined merge writes nothing, and its undo and redo do nothing", before,
                snapshot(model, Set.of()));
    }

    /**
     * The merge vouches that it changed nothing only after a decline that wrote nothing, and a
     * later run that writes clears it.
     */
    @Test
    public void shouldSayItChangedNothing_onlyAfterADeclineThatWroteNothing() {
        IArchimateElement t = actor("t", "Customer");
        IArchimateElement s = actor("s", "Customer");
        MergeElementsCommand command = (MergeElementsCommand) MergeElementsOperations.prepare(model,
                List.of(cluster("t", "s")), MergeElementsOperations.options(null, null, false, false), Set.of())
                .command();
        assertFalse("not before it has run", command.changedNothing());
        IArchimateRelationship late = association("late-self", s, t);

        command.execute();

        assertTrue(command.getSkipReason(), command.getSkipReason().contains("the proposal did not list"));
        assertTrue("an unlisted removal refused before any write", command.changedNothing());
        late.disconnect();
        model.getFolder(FolderType.RELATIONS).getElements().remove(late);

        command.execute();

        assertNull(command.getSkipReason());
        assertFalse("a run that wrote clears it", command.changedNothing());

        actor("u", "Billing");
        IArchimateElement v = actor("v", "Billing");
        MergeElementsCommand gone = (MergeElementsCommand) MergeElementsOperations.prepare(model,
                List.of(cluster("u", "v")), MergeElementsOperations.options(null, null, false, false)).command();
        model.getFolder(FolderType.BUSINESS).getElements().remove(v);
        gone.execute();
        assertTrue(gone.getSkipReason(), gone.getSkipReason().contains("Element not found: v"));
        assertTrue("a duplicate gone before any write", gone.changedNothing());
    }

    @Test
    public void shouldBeOneStackEntry_whenDispatched() {
        actor("t", "Customer");
        actor("s", "Customer");
        merge(cluster("t", "s"));
        assertTrue("one command, not a compound of parts the stack could split",
                lastDispatched instanceof MergeElementsCommand);
    }

    // ---- refused, model unchanged -------------------------------------------------------------

    @Test
    public void shouldRefuseADuplicateOfAnotherType_andLeaveTheModelUnchanged() {
        actor("t", "Customer");
        role("s", "Customer");
        Map<String, String> before = snapshot(model, Set.of());

        ModelAccessException e = refusal(() -> merge(cluster("t", "s")));

        assertEquals(ErrorCode.INVALID_ELEMENT_TYPE, e.getErrorCode());
        assertTrue(e.getMessage(), e.getMessage().contains("BusinessRole")
                && e.getMessage().contains("BusinessActor"));
        assertTrue(e.getSuggestedCorrection(), e.getSuggestedCorrection().contains("Change its type first"));
        assertSnapshot("refused means unchanged", before, snapshot(model, Set.of()));
        assertNull("nothing was dispatched", lastDispatched);
    }

    @Test
    public void shouldRefuseAnAndJunctionMergedIntoAnOrJunction() {
        IJunction and = factory.createJunction();
        and.setId("and");
        and.setType(IJunction.AND_JUNCTION_TYPE);
        IJunction or = factory.createJunction();
        or.setId("or");
        or.setType(IJunction.OR_JUNCTION_TYPE);
        model.getFolder(FolderType.OTHER).getElements().add(and);
        model.getFolder(FolderType.OTHER).getElements().add(or);

        ModelAccessException e = refusal(() -> merge(cluster("and", "or")));

        assertEquals(ErrorCode.INVALID_ELEMENT_TYPE, e.getErrorCode());
        assertTrue(e.getMessage(), e.getMessage().contains("junction"));
        assertNotNull(or.getArchimateModel());
    }

    // ---- fixture ------------------------------------------------------------------------------

    /** A bit of everything the merge touches, across two clusters and two views. */
    private void buildEverything() {
        IArchimateElement t = actor("t", "Customer");
        IArchimateElement s1 = actor("s1", "Customer");
        IArchimateElement s2 = actor("s2", "Client");
        IArchimateElement u = component("u", "Billing");
        IArchimateElement v = component("v", "Billing");
        IArchimateElement w = component("w", "CRM");
        t.setDocumentation("target");
        s1.setDocumentation("one");
        property(t, "tier", "gold");
        property(s1, "tier", "silver");
        property(s1, "region", "emea");
        property(s2, "owner", "ann");
        IArchimateRelationship r1 = association("r1", s1, w);
        association("r2", w, s2);
        IArchimateRelationship self = association("self", s1, t);
        association("on-self", w, self);
        association("across", s2, v);
        association("parallel", t, w);
        IDiagramModelArchimateObject group = place(viewA, w, "box-w", 0, 0, 500, 400);
        IDiagramModelArchimateObject boxS1 = place(group, s1, "box-s1", 20, 30, 120, 55);
        place(boxS1, u, "box-u-in-s1", 5, 25, 60, 25);
        IDiagramModelArchimateObject boxT = place(viewA, t, "box-t", 600, 0, 120, 55);
        IDiagramModelArchimateObject boxW2 = place(viewA, w, "box-w2", 600, 300, 120, 55);
        IDiagramModelArchimateConnection c1 = connect(r1, boxS1, boxW2, "conn-r1");
        bendpoint(c1, 7, 8);
        IDiagramModelArchimateConnection cs = connect(self, boxS1, boxT, "conn-self");
        bendpoint(cs, 1, 1);
        connectTo((IArchimateRelationship) ArchimateModelUtils.getObjectByID(model, "on-self"),
                boxW2, cs, "conn-on-self");
        IDiagramModelArchimateObject boxV = place(viewB, v, "box-v", 0, 0, 120, 55);
        IDiagramModelArchimateObject boxS2 = place(viewB, s2, "box-s2", 300, 0, 120, 55);
        place(viewB, u, "box-u", 600, 0, 120, 55);
        connect((IArchimateRelationship) ArchimateModelUtils.getObjectByID(model, "across"), boxS2, boxV,
                "conn-across");
        boxS2.setFillColor("#123456");
    }

    private IArchimateDiagramModel view(String id, String name) {
        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId(id);
        view.setName(name);
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        return view;
    }

    private IArchimateElement actor(String id, String name) {
        return element(factory.createBusinessActor(), id, name, FolderType.BUSINESS);
    }

    private IArchimateElement role(String id, String name) {
        return element(factory.createBusinessRole(), id, name, FolderType.BUSINESS);
    }

    private IArchimateElement component(String id, String name) {
        return element(factory.createApplicationComponent(), id, name, FolderType.APPLICATION);
    }

    private IArchimateElement element(IArchimateElement element, String id, String name, FolderType folder) {
        element.setId(id);
        element.setName(name);
        model.getFolder(folder).getElements().add(element);
        return element;
    }

    private IArchimateRelationship association(String id, IArchimateConcept source, IArchimateConcept target) {
        return relationship(IArchimatePackage.eINSTANCE.getAssociationRelationship(), id, source, target);
    }

    private IArchimateRelationship relationship(org.eclipse.emf.ecore.EClass type, String id,
            IArchimateConcept source, IArchimateConcept target) {
        IArchimateRelationship relationship = (IArchimateRelationship) factory.create(type);
        relationship.setId(id);
        relationship.connect(source, target);
        model.getFolder(FolderType.RELATIONS).getElements().add(relationship);
        return relationship;
    }

    private IProperty property(com.archimatetool.model.IProperties owner, String key, String value) {
        IProperty property = factory.createProperty();
        property.setKey(key);
        property.setValue(value);
        owner.getProperties().add(property);
        return property;
    }

    private IDiagramModelArchimateObject place(IDiagramModelContainer parent, IArchimateElement element,
            String id, int x, int y, int width, int height) {
        IDiagramModelArchimateObject box = factory.createDiagramModelArchimateObject();
        box.setId(id);
        box.setArchimateElement(element);
        box.setBounds(x, y, width, height);
        parent.getChildren().add(box);
        return box;
    }

    private IDiagramModelArchimateConnection connect(IArchimateRelationship relationship,
            IDiagramModelArchimateObject source, IDiagramModelArchimateObject target, String id) {
        return connectTo(relationship, source, target, id);
    }

    private IDiagramModelArchimateConnection connectTo(IArchimateRelationship relationship,
            IConnectable source, IConnectable target, String id) {
        IDiagramModelArchimateConnection connection = factory.createDiagramModelArchimateConnection();
        connection.setId(id);
        connection.setArchimateRelationship(relationship);
        connection.connect(source, target);
        return connection;
    }

    private void bendpoint(IDiagramModelConnection connection, int x, int y) {
        IDiagramModelBendpoint bendpoint = factory.createDiagramModelBendpoint();
        bendpoint.setStartX(x);
        bendpoint.setStartY(y);
        bendpoint.setEndX(-x);
        bendpoint.setEndY(-y);
        connection.getBendpoints().add(bendpoint);
    }

    private List<IArchimateRelationship> relationships() {
        List<IArchimateRelationship> out = new ArrayList<>();
        model.getFolder(FolderType.RELATIONS).eAllContents().forEachRemaining(o -> {
            if (o instanceof IArchimateRelationship relationship) {
                out.add(relationship);
            }
        });
        return out;
    }

    private Map<String, String> endTypes() {
        Map<String, String> out = new LinkedHashMap<>();
        for (IArchimateRelationship relationship : relationships()) {
            out.put(relationship.getId(), relationship.getSource().eClass().getName() + " -"
                    + relationship.eClass().getName() + "-> " + relationship.getTarget().eClass().getName());
        }
        return out;
    }

    /**
     * Stands in for Archi's matrix, which loads from the running platform and cannot answer in a
     * plain JVM: an association may join anything, nothing else may join an element to itself.
     * Records what it was asked.
     */
    private final List<String> asked = new ArrayList<>();
    private final MergeElementsPlan.RelationshipRule associationsOnly = (source, target, type) -> {
        asked.add(((IArchimateElement) source).getId() + " -" + type.getName() + "-> "
                + ((IArchimateElement) target).getId());
        return type == IArchimatePackage.eINSTANCE.getAssociationRelationship();
    };

    private MergeElementsPlan.Options keeping() {
        return new MergeElementsPlan.Options(MergeElementsPlan.PropertyPolicy.UNION,
                MergeElementsPlan.SelfPolicy.KEEP, false, false, associationsOnly);
    }

    /** Prepares and runs the command directly, as the stack would, with {@code options}. */
    private MergeElementsResultDto runCommand(MergeElementsPlan.Options options, MergeClusterRequest... clusters) {
        PreparedMutation<MergeElementsResultDto> prepared = MergeElementsOperations.prepare(model,
                List.of(clusters), options);
        lastDispatched = prepared.command();
        prepared.command().execute();
        MergeElementsCommand command = (MergeElementsCommand) prepared.command();
        assertNull(command.getSkipReason());
        return command.getResult();
    }

    private static List<String> pairs(IArchimateElement element) {
        return element.getProperties().stream().map(p -> p.getKey() + "=" + p.getValue()).toList();
    }

    private static java.nio.file.Path repoFile(String relative) {
        java.nio.file.Path dir = java.nio.file.Path.of("").toAbsolutePath();
        while (dir != null && !java.nio.file.Files.exists(dir.resolve(relative))) {
            dir = dir.getParent();
        }
        assertNotNull("repository root not found above the working directory", dir);
        return dir.resolve(relative);
    }

    private static MergeClusterRequest cluster(String targetId, String... sourceIds) {
        return new MergeClusterRequest(targetId, List.of(sourceIds));
    }

    private MergeElementsResultDto merge(MergeClusterRequest... clusters) {
        MutationResult<MergeElementsResultDto> result = accessor.mergeElements(SESSION, List.of(clusters),
                null, null, false, false);
        assertNull("an immediate call", result.proposalContext());
        return result.entity();
    }

    private static ModelAccessException refusal(Runnable call) {
        try {
            call.run();
        } catch (ModelAccessException e) {
            return e;
        }
        fail("expected a refusal");
        return null;
    }

    // ---- structural snapshot ------------------------------------------------------------------

    /**
     * Every object in the model, keyed by id (or by its place under the nearest object with one),
     * with its container, its index among its siblings, and every persisted attribute and
     * reference. Siblings whose id is in {@code ignored} are left out of the index count, so the
     * survivors of a deletion compare by the order they keep among themselves.
     */
    static Map<String, String> snapshot(IArchimateModel model, Set<String> ignored) {
        List<EObject> all = new ArrayList<>();
        all.add(model);
        model.eAllContents().forEachRemaining(all::add);
        Map<EObject, String> keys = new IdentityHashMap<>();
        for (EObject each : all) {
            keys.put(each, keyOf(each, keys, ignored));
        }
        Map<String, String> out = new TreeMap<>();
        for (EObject each : all) {
            StringBuilder line = new StringBuilder(each.eClass().getName());
            if (each.eContainer() != null) {
                line.append(" in ").append(keys.get(each.eContainer())).append('.')
                        .append(each.eContainingFeature().getName()).append('[')
                        .append(index(each, ignored)).append(']');
            }
            for (EAttribute attribute : each.eClass().getEAllAttributes()) {
                if (!attribute.isTransient() && !attribute.isDerived()) {
                    line.append(' ').append(attribute.getName()).append('=').append(each.eGet(attribute));
                }
            }
            for (EReference reference : each.eClass().getEAllReferences()) {
                if (reference.isContainment() || reference.isContainer() || reference.isTransient()
                        || reference.isDerived()) {
                    continue;
                }
                Object value = each.eGet(reference);
                line.append(' ').append(reference.getName()).append("->");
                if (value instanceof List<?> list) {
                    for (Object item : list) {
                        line.append(keys.getOrDefault(item, "<outside>")).append(',');
                    }
                } else {
                    line.append(value == null ? "null" : keys.getOrDefault(value, "<outside>"));
                }
            }
            out.put(keys.get(each), line.toString());
        }
        return out;
    }

    private static String keyOf(EObject each, Map<EObject, String> keys, Set<String> ignored) {
        if (each instanceof IIdentifier identified && identified.getId() != null) {
            return "#" + identified.getId();
        }
        if (each.eContainer() == null) {
            return "<root>";
        }
        return keys.get(each.eContainer()) + "/" + each.eContainingFeature().getName() + "["
                + index(each, ignored) + "]";
    }

    private static int index(EObject each, Set<String> ignored) {
        Object siblings = each.eContainer().eGet(each.eContainingFeature());
        if (!(siblings instanceof List<?> list)) {
            return 0;
        }
        int i = 0;
        for (Object sibling : list) {
            if (sibling == each) {
                return i;
            }
            if (!(sibling instanceof IIdentifier identified && ignored.contains(identified.getId()))) {
                i++;
            }
        }
        return -1;
    }

    /** Fails listing every difference, not the first. */
    static void assertSnapshot(String message, Map<String, String> expected, Map<String, String> actual) {
        List<String> differences = new ArrayList<>();
        Set<String> keys = new TreeSet<>(expected.keySet());
        keys.addAll(actual.keySet());
        for (String key : keys) {
            String want = expected.get(key);
            String got = actual.get(key);
            if (want == null) {
                differences.add("unexpected " + key + ": " + got);
            } else if (got == null) {
                differences.add("missing " + key + ": " + want);
            } else if (!want.equals(got)) {
                differences.add("changed " + key + "\n    want " + want + "\n    got  " + got);
            }
        }
        assertTrue(message + "\n  " + String.join("\n  ", differences), differences.isEmpty());
    }

    // ---- plumbing -----------------------------------------------------------------------------

    private ArchiModelAccessorImpl createAccessor(IArchimateModel testModel) {
        MutationDispatcher dispatcher = new MutationDispatcher(() -> testModel) {
            @Override
            public void dispatchImmediate(Command command) {
                run(command);
            }

            @Override
            protected void dispatchCommand(Command command) {
                run(command);
            }

            private void run(Command command) {
                if (beforeExecute != null) {
                    beforeExecute.run();
                }
                lastDispatched = command;
                if (command instanceof CompoundCommand compound) {
                    compound.execute();
                } else {
                    command.execute();
                }
            }
        };
        dispatcher.setApprovalModeProvider(() -> false);
        return new ArchiModelAccessorImpl(models, dispatcher);
    }

    static class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> list = new ArrayList<>();
        private final List<PropertyChangeListener> listeners = new ArrayList<>();

        void setModels(List<IArchimateModel> models) { this.list = models; }

        @Override public List<IArchimateModel> getModels() { return list; }
        @Override public void addPropertyChangeListener(PropertyChangeListener listener) { listeners.add(listener); }
        @Override public void removePropertyChangeListener(PropertyChangeListener listener) { listeners.remove(listener); }
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
