package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;

import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.common.notify.impl.AdapterImpl;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.gef.commands.Command;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IAccessRelationship;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimatePackage;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IAssociationRelationship;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelArchimateComponent;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.IInfluenceRelationship;
import com.archimatetool.model.IProfile;
import com.archimatetool.model.IProperties;
import com.archimatetool.model.IProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.vheerden.archi.mcp.response.dto.MergeClusterRequest;
import net.vheerden.archi.mcp.response.dto.MergeElementsResultDto;
import net.vheerden.archi.mcp.response.dto.MergeElementsResultDto.Cluster;
import net.vheerden.archi.mcp.response.dto.MergeElementsResultDto.ParallelCollapsed;
import net.vheerden.archi.mcp.response.dto.MergeElementsResultDto.ParallelNotCollapsed;

/**
 * {@code merge-elements} with {@code collapseParallel}: which relationships the merge made
 * parallel are folded into one, what the survivor takes from each, what happens to their view
 * connections and to what hangs on them, and that one undo restores everything.
 *
 * <p>Most cases go through the accessor against a dispatcher that runs the command directly and
 * keeps it, so a case can undo and redo the very entry the stack would hold. After every write the
 * cases check that each view connection still draws a relationship whose ends are the concepts of
 * the two objects it joins, which is what Archi itself assumes of every connection.</p>
 */
public class MergeElementsCollapseParallelTest {

    private static final String SESSION = "collapse-session";
    private static final String ASSOCIATION = "AssociationRelationship";

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private final IArchimatePackage pkg = IArchimatePackage.eINSTANCE;
    private final MergeElementsCommandTest.StubEditorModelManager models =
            new MergeElementsCommandTest.StubEditorModelManager();
    private IArchimateModel model;
    private IArchimateDiagramModel viewA;
    private IArchimateDiagramModel viewB;
    private ArchiModelAccessorImpl accessor;
    private Command lastDispatched;

    @Before
    public void setUp() {
        model = factory.createArchimateModel();
        model.setId("model-collapse");
        model.setDefaults();
        viewA = view("view-a");
        viewB = view("view-b");
        models.setModels(List.of(model));
        accessor = createAccessor(model);
    }

    @After
    public void tearDown() {
        accessor.dispose();
    }

    // ---- off by default ------------------------------------------------------------------------

    @Test
    public void shouldOnlyReportParallels_andAddNoFoldFields_whenCollapseParallelIsOff() throws Exception {
        IArchimateElement t = actor("t");
        IArchimateElement s = actor("s");
        IArchimateElement x = component("x");
        IArchimateRelationship onTarget = association("on-target", t, x);
        IArchimateRelationship onDuplicate = association("on-duplicate", s, x);

        MergeElementsResultDto result = accessor.mergeElements(SESSION, List.of(cluster("t", "s")), null, null,
                false, false).entity();

        assertNotNull(onTarget.getArchimateModel());
        assertNotNull(onDuplicate.getArchimateModel());
        Cluster c = result.clusters().get(0);
        assertEquals(1, c.parallelRelationships().size());
        assertNull(c.parallelsCollapsed());
        assertNull(c.parallelsNotCollapsed());
        assertNull(result.relationshipIdMap());
        assertNull(result.totals().parallelRelationshipsCollapsed());
        assertFalse(result.collapseParallel());
        // A plain mapper, without the server's own null handling: the records must omit these.
        String json = new ObjectMapper().writeValueAsString(result);
        for (String absent : List.of("parallelsCollapsed", "parallelsNotCollapsed", "relationshipIdMap",
                "parallelRelationshipsCollapsed")) {
            assertFalse(absent + " must be omitted when collapseParallel is off: " + json, json.contains(absent));
        }
        assertTrue(json, json.contains("\"collapseParallel\":false"));
    }

    // ---- folding a compatible pair -------------------------------------------------------------

    @Test
    public void shouldFoldACompatiblePair_intoTheRelationshipAlreadyOnTheTarget() throws Exception {
        IArchimateElement t = actor("t");
        IArchimateElement s = actor("s");
        IArchimateElement x = component("x");
        // The duplicate's relationship comes first in model order; the target's still survives.
        IArchimateRelationship onDuplicate = association("on-duplicate", s, x);
        IArchimateRelationship onTarget = association("on-target", t, x);

        MergeElementsResultDto result = fold(cluster("t", "s"));

        assertNull("the folded relationship is deleted", onDuplicate.getArchimateModel());
        assertNotNull(onTarget.getArchimateModel());
        assertSame(t, onTarget.getSource());
        assertSame(x, onTarget.getTarget());
        assertFalse(t.getSourceRelationships().contains(onDuplicate));
        assertFalse(x.getTargetRelationships().contains(onDuplicate));
        Cluster c = result.clusters().get(0);
        assertEquals(List.of(new ParallelCollapsed("on-target", "on-duplicate", ASSOCIATION, List.of(),
                List.of(), List.of(), List.of(), List.of())), c.parallelsCollapsed());
        assertEquals(List.of(), c.parallelsNotCollapsed());
        assertEquals(List.of(), c.parallelRelationships());
        assertEquals(Map.of("on-duplicate", "on-target"), result.relationshipIdMap());
        assertEquals(Integer.valueOf(1), result.totals().parallelRelationshipsCollapsed());
        assertTrue(result.collapseParallel());
        String json = new ObjectMapper().writeValueAsString(result);
        assertTrue(json, json.contains("\"parallelRelationshipsCollapsed\":1"));
    }

    // ---- one incompatibility at a time ---------------------------------------------------------

    @Test
    public void shouldKeepBoth_whenTheNamesDiffer() {
        assertNotFolded(ASSOCIATION, (survivor, dropped) -> {
            survivor.setName("uses");
            dropped.setName("calls");
        }, "NAME_DIFFERS");
    }

    @Test
    public void shouldKeepBoth_whenTheDocumentationDiffers() {
        assertNotFolded(ASSOCIATION, (survivor, dropped) -> {
            survivor.setDocumentation("one");
            dropped.setDocumentation("two");
        }, "DOCUMENTATION_DIFFERS");
    }

    @Test
    public void shouldKeepBoth_whenOnePropertyListIsASupersetOfTheOther() {
        assertNotFolded(ASSOCIATION, (survivor, dropped) -> {
            property(survivor, "k", "v");
            property(dropped, "k", "v");
            property(dropped, "j", "w");
        }, "PROPERTIES_DIFFER");
    }

    @Test
    public void shouldKeepBoth_whenAKeyHoldsAnotherValue() {
        assertNotFolded(ASSOCIATION, (survivor, dropped) -> {
            property(survivor, "k", "v");
            property(dropped, "k", "w");
        }, "PROPERTIES_DIFFER");
    }

    @Test
    public void shouldCountARepeatedProperty_asPartOfTheList() {
        assertNotFolded(ASSOCIATION, (survivor, dropped) -> {
            property(survivor, "k", "v");
            property(dropped, "k", "v");
            property(dropped, "k", "v");
        }, "PROPERTIES_DIFFER");
    }

    @Test
    public void shouldFold_whenTheSamePropertiesAreHeldInAnotherOrder() {
        IArchimateRelationship[] pair = pair(ASSOCIATION);
        property(pair[0], "k", "v");
        property(pair[0], "j", "w");
        property(pair[1], "j", "w");
        property(pair[1], "k", "v");

        MergeElementsResultDto result = fold(cluster("t", "s"));

        assertNull(pair[1].getArchimateModel());
        assertEquals(List.of("k=v", "j=w"), pairs(pair[0]));
        assertEquals(List.of(), result.clusters().get(0).parallelsCollapsed().get(0).filled());
    }

    @Test
    public void shouldKeepBoth_whenTheAccessTypesDiffer() {
        assertNotFolded("AccessRelationship", (survivor, dropped) -> {
            ((IAccessRelationship) survivor).setAccessType(IAccessRelationship.WRITE_ACCESS);
            ((IAccessRelationship) dropped).setAccessType(IAccessRelationship.READ_ACCESS);
        }, "ATTRIBUTE_DIFFERS");
    }

    @Test
    public void shouldKeepBoth_whenTheInfluenceStrengthsDiffer() {
        assertNotFolded("InfluenceRelationship", (survivor, dropped) -> {
            ((IInfluenceRelationship) survivor).setStrength("+");
            ((IInfluenceRelationship) dropped).setStrength("++");
        }, "ATTRIBUTE_DIFFERS");
    }

    @Test
    public void shouldKeepBoth_whenOneAssociationIsDirectedAndTheOtherIsNot() {
        assertNotFolded(ASSOCIATION, (survivor, dropped) -> {
            ((IAssociationRelationship) survivor).setDirected(true);
        }, "ATTRIBUTE_DIFFERS");
    }

    @Test
    public void shouldKeepBoth_whenTheSpecializationsDiffer() {
        assertNotFolded(ASSOCIATION, (survivor, dropped) -> {
            IProfile profile = factory.createProfile();
            profile.setName("Calls");
            profile.setConceptType(ASSOCIATION);
            model.getProfiles().add(profile);
            dropped.getProfiles().add(profile);
        }, "ATTRIBUTE_DIFFERS");
    }

    @Test
    public void shouldFold_whenTheAttributesAgree() {
        IArchimateRelationship[] pair = pair("AccessRelationship");
        ((IAccessRelationship) pair[0]).setAccessType(IAccessRelationship.READ_ACCESS);
        ((IAccessRelationship) pair[1]).setAccessType(IAccessRelationship.READ_ACCESS);

        fold(cluster("t", "s"));

        assertNull(pair[1].getArchimateModel());
    }

    @Test
    public void shouldListEveryReason_inTheirPublishedOrder() {
        assertNotFolded(ASSOCIATION, (survivor, dropped) -> {
            ((IAssociationRelationship) dropped).setDirected(true);
            property(survivor, "k", "v");
            property(dropped, "k", "w");
            survivor.setDocumentation("one");
            dropped.setDocumentation("two");
            survivor.setName("uses");
            dropped.setName("calls");
        }, "NAME_DIFFERS", "DOCUMENTATION_DIFFERS", "PROPERTIES_DIFFER", "ATTRIBUTE_DIFFERS");
    }

    // ---- what the survivor takes ---------------------------------------------------------------

    @Test
    public void shouldFillTheSurvivorsBlanks_withCopiesOfWhatTheFoldedOneHeld() {
        IArchimateRelationship[] pair = pair(ASSOCIATION);
        IArchimateRelationship survivor = pair[0];
        IArchimateRelationship dropped = pair[1];
        survivor.setName("   ");
        dropped.setName("uses");
        dropped.setDocumentation("Why.");
        IProperty first = property(dropped, "k", "v");
        IProperty second = property(dropped, "j", "w");

        MergeElementsResultDto result = fold(cluster("t", "s"));

        assertEquals("a whitespace-only name is blank", "uses", survivor.getName());
        assertEquals("Why.", survivor.getDocumentation());
        assertEquals(List.of("k=v", "j=w"), pairs(survivor));
        assertFalse("copies, never the folded one's own", survivor.getProperties().contains(first));
        assertEquals(List.of("name", "documentation", "properties"),
                result.clusters().get(0).parallelsCollapsed().get(0).filled());

        lastDispatched.undo();
        assertEquals("   ", survivor.getName());
        assertTrue(survivor.getDocumentation() == null || survivor.getDocumentation().isEmpty());
        assertEquals(List.of(), pairs(survivor));
        assertEquals("the folded one's list is intact after undo", List.of(first, second),
                List.copyOf(dropped.getProperties()));
    }

    // ---- which one survives --------------------------------------------------------------------

    @Test
    public void shouldPickTheSurvivorByCallOrder_whenNoneWasAlreadyOnTheTarget() {
        actor("t");
        actor("s1");
        actor("s2");
        IArchimateElement x = component("x");
        IArchimateRelationship fromSecond = association("from-s2", element("s2"), x);
        IArchimateRelationship fromFirstB = association("from-s1-b", element("s1"), x);
        IArchimateRelationship fromFirstA = association("from-s1-a", element("s1"), x);

        MergeElementsResultDto result = fold(cluster("t", "s1", "s2"));

        assertNotNull("the first source's first relationship in model order survives",
                fromFirstB.getArchimateModel());
        assertNull(fromFirstA.getArchimateModel());
        assertNull(fromSecond.getArchimateModel());
        assertEquals(Map.of("from-s1-a", "from-s1-b", "from-s2", "from-s1-b"), result.relationshipIdMap());
        assertEquals("folded in survivor order: by call position, then model order",
                List.of("from-s1-a", "from-s2"),
                result.clusters().get(0).parallelsCollapsed().stream().map(ParallelCollapsed::droppedId).toList());
    }

    @Test
    public void shouldPickTheSurvivorByMergesOrder_beforeModelOrder() {
        IArchimateElement t = actor("t");
        IArchimateElement s = actor("s");
        IArchimateElement u = component("u");
        IArchimateElement v = component("v");
        // Its moved end came from the second cluster's duplicate.
        IArchimateRelationship early = association("early", s, u);
        // Its moved end came from the first cluster's duplicate.
        IArchimateRelationship late = association("late", t, v);

        fold(cluster("u", "v"), cluster("t", "s"));

        assertNotNull(late.getArchimateModel());
        assertNull(early.getArchimateModel());
    }

    @Test
    public void shouldLeaveASecondRelationshipAlreadyOnTheTargetAlone() {
        IArchimateElement t = actor("t");
        IArchimateElement s = actor("s");
        IArchimateElement x = component("x");
        IArchimateRelationship first = association("first", t, x);
        IArchimateRelationship second = association("second", t, x);
        IArchimateRelationship moved = association("moved", s, x);

        MergeElementsResultDto result = fold(cluster("t", "s"));

        assertNotNull(first.getArchimateModel());
        assertNotNull("a parallel the target already had is not this merge's to fold", second.getArchimateModel());
        assertNull(moved.getArchimateModel());
        Cluster c = result.clusters().get(0);
        assertEquals(Map.of("moved", "first"), result.relationshipIdMap());
        assertEquals(List.of(), c.parallelsNotCollapsed());
        assertEquals(List.of(), c.parallelRelationships());
    }

    @Test
    public void shouldCompareEachLaterOneWithTheSurvivorAsAnEarlierFoldLeftIt() {
        IArchimateElement t = actor("t");
        actor("s1");
        actor("s2");
        IArchimateElement x = component("x");
        IArchimateRelationship survivor = association("survivor", t, x);
        IArchimateRelationship uses = association("uses", element("s1"), x);
        uses.setName("uses");
        IArchimateRelationship calls = association("calls", element("s2"), x);
        calls.setName("calls");

        MergeElementsResultDto result = fold(cluster("t", "s1", "s2"));

        assertEquals("uses", survivor.getName());
        assertNull(uses.getArchimateModel());
        assertNotNull(calls.getArchimateModel());
        Cluster c = result.clusters().get(0);
        assertEquals(List.of(new ParallelNotCollapsed("calls", "survivor", ASSOCIATION, List.of("NAME_DIFFERS"))),
                c.parallelsNotCollapsed());
        assertEquals(List.of("name"), c.parallelsCollapsed().get(0).filled());
        assertEquals("the kept one is still a parallel in the model", 1, c.parallelRelationships().size());
    }

    // ---- view connections ----------------------------------------------------------------------

    @Test
    public void shouldRepointAFoldedRelationshipsConnection_inPlace_andAnnounceIt() {
        IArchimateElement t = actor("t");
        IArchimateElement s = actor("s");
        IArchimateElement x = component("x");
        IArchimateElement y = component("y");
        IArchimateRelationship survivor = association("survivor", t, x);
        IArchimateRelationship dropped = association("dropped", s, x);
        IArchimateRelationship other = association("other", s, y);
        IDiagramModelArchimateObject boxS = place(viewA, s, "box-s");
        IDiagramModelArchimateObject boxX = place(viewA, x, "box-x");
        IDiagramModelArchimateObject boxY = place(viewA, y, "box-y");
        connect(other, boxS, boxY, "conn-other");
        IDiagramModelArchimateConnection drawn = connect(dropped, boxS, boxX, "conn-dropped");
        connect(association("other-2", s, x), boxS, boxX, "conn-other-2");
        bendpoint(drawn, 10, 20);
        bendpoint(drawn, 30, 40);
        drawn.getFeatures().putString("labelOffset", "5,6");
        drawn.setLineColor("#ff0000");
        int sourceIndex = boxS.getSourceConnections().indexOf(drawn);
        int targetIndex = boxX.getTargetConnections().indexOf(drawn);
        List<IDiagramModelBendpoint> bendpoints = List.copyOf(drawn.getBendpoints());
        List<String> heard = listen(boxS);

        MergeElementsResultDto result = fold(cluster("t", "s"));

        assertNull(dropped.getArchimateModel());
        assertSame("the same connection object", drawn, boxS.getSourceConnections().get(sourceIndex));
        assertEquals(targetIndex, boxX.getTargetConnections().indexOf(drawn));
        assertSame(survivor, drawn.getArchimateRelationship());
        assertEquals("conn-dropped", drawn.getId());
        assertEquals(bendpoints, List.copyOf(drawn.getBendpoints()));
        assertEquals("5,6", drawn.getFeatures().getString("labelOffset", null));
        assertEquals("#ff0000", drawn.getLineColor());
        assertTrue(survivor.getReferencingDiagramComponents().contains(drawn));
        assertFalse(dropped.getReferencingDiagramComponents().contains(drawn));
        assertTrue("an open view hears the connection leave and come back: " + heard,
                heard.contains("REMOVE conn-dropped") && heard.contains("ADD conn-dropped"));
        ParallelCollapsed collapsed = result.clusters().get(0).parallelsCollapsed().stream()
                .filter(f -> f.droppedId().equals("dropped")).findFirst().orElseThrow();
        assertEquals(List.of("conn-dropped"), collapsed.connectionsRepointed());
        assertEquals(List.of(), collapsed.connectionsRemoved());
        assertConsistent();
    }

    @Test
    public void shouldRemoveAFoldedRelationshipsConnection_whereTheSurvivorIsAlreadyDrawn() {
        actor("t");
        IArchimateElement s = actor("s");
        IArchimateElement x = component("x");
        IArchimateRelationship survivor = association("survivor", s, x);
        IArchimateRelationship dropped = association("dropped", s, x);
        IDiagramModelArchimateObject boxS = place(viewA, s, "box-s");
        IDiagramModelArchimateObject boxX = place(viewA, x, "box-x");
        IDiagramModelArchimateConnection kept = connect(survivor, boxS, boxX, "conn-survivor");
        IDiagramModelArchimateConnection gone = connect(dropped, boxS, boxX, "conn-dropped");

        MergeElementsResultDto result = fold(cluster("t", "s"));

        assertEquals(List.of(kept), List.copyOf(boxS.getSourceConnections()));
        assertEquals(List.of(kept), List.copyOf(boxX.getTargetConnections()));
        assertFalse(survivor.getReferencingDiagramComponents().contains(gone));
        ParallelCollapsed collapsed = result.clusters().get(0).parallelsCollapsed().get(0);
        assertEquals(List.of("conn-dropped"), collapsed.connectionsRemoved());
        assertEquals(List.of(), collapsed.connectionsRepointed());
        assertConsistent();
    }

    @Test
    public void shouldRepointTheFirstAndRemoveTheSecond_whenTheFoldedOneIsDrawnTwiceBetweenTwoBoxes() {
        actor("t");
        IArchimateElement s = actor("s");
        IArchimateElement x = component("x");
        IArchimateRelationship survivor = association("survivor", s, x);
        IArchimateRelationship dropped = association("dropped", s, x);
        IDiagramModelArchimateObject boxS = place(viewA, s, "box-s");
        IDiagramModelArchimateObject boxX = place(viewA, x, "box-x");
        IDiagramModelArchimateConnection first = connect(dropped, boxS, boxX, "conn-1");
        connect(dropped, boxS, boxX, "conn-2");

        ParallelCollapsed collapsed = fold(cluster("t", "s")).clusters().get(0).parallelsCollapsed().get(0);

        assertEquals(List.of("conn-1"), collapsed.connectionsRepointed());
        assertEquals(List.of("conn-2"), collapsed.connectionsRemoved());
        assertEquals(List.of(first), List.copyOf(boxS.getSourceConnections()));
        assertSame(survivor, first.getArchimateRelationship());
        assertConsistent();
    }

    @Test
    public void shouldNotCountASurvivorDrawnTheOtherWay_asDrawnThere() {
        actor("t");
        IArchimateElement s = actor("s");
        IArchimateRelationship survivor = association("loop-1", s, s);
        IArchimateRelationship dropped = association("loop-2", s, s);
        IDiagramModelArchimateObject one = place(viewA, s, "box-1");
        IDiagramModelArchimateObject two = place(viewA, s, "box-2");
        connect(survivor, one, two, "conn-forward");
        IDiagramModelArchimateConnection back = connect(dropped, two, one, "conn-back");

        ParallelCollapsed collapsed = fold(cluster("t", "s")).clusters().get(0).parallelsCollapsed().get(0);

        assertEquals(List.of("conn-back"), collapsed.connectionsRepointed());
        assertSame(survivor, back.getArchimateRelationship());
        assertSame(two, back.getSource());
        assertConsistent();
    }

    @Test
    public void shouldRepointALoopDrawnOnOneBox_keepingItOnceInEachOfTheBoxsLists() {
        IArchimateElement t = actor("t");
        IArchimateElement s = actor("s");
        IArchimateRelationship targetLoop = association("target-loop", t, t);
        association("duplicate-loop", s, s);
        IDiagramModelArchimateObject box = place(viewA, s, "box-s");
        IDiagramModelArchimateConnection before = connect(association("before", s, component("x")), box,
                place(viewA, element("x"), "box-x"), "conn-before");
        IDiagramModelArchimateConnection loop = connect((IArchimateRelationship) com.archimatetool.model.util
                .ArchimateModelUtils.getObjectByID(model, "duplicate-loop"), box, box, "conn-loop");
        List<IDiagramModelConnection> sourceOrder = List.copyOf(box.getSourceConnections());

        ParallelCollapsed collapsed = fold(cluster("t", "s")).clusters().get(0).parallelsCollapsed().get(0);

        assertEquals(List.of("conn-loop"), collapsed.connectionsRepointed());
        assertSame(targetLoop, loop.getArchimateRelationship());
        assertEquals(sourceOrder, List.copyOf(box.getSourceConnections()));
        assertEquals(List.of(loop), List.copyOf(box.getTargetConnections()));
        assertSame(before, box.getSourceConnections().get(0));
        assertConsistent();
    }

    // ---- what hangs on the folded relationship -------------------------------------------------

    @Test
    public void shouldMoveARelationshipOnTheFoldedOne_toTheSurvivor() {
        IArchimateRelationship[] pair = pair(ASSOCIATION);
        IArchimateElement y = component("y");
        IArchimateRelationship onIt = association("on-it", y, pair[1]);

        ParallelCollapsed collapsed = fold(cluster("t", "s")).clusters().get(0).parallelsCollapsed().get(0);

        assertSame(pair[0], onIt.getTarget());
        assertSame(y, onIt.getSource());
        assertEquals("on-it", onIt.getId());
        assertTrue(pair[0].getTargetRelationships().contains(onIt));
        assertEquals(List.of("on-it"), collapsed.relationshipsMoved());
    }

    @Test
    public void shouldReattachConnectionsOnARemovedConnection_toTheSurvivorsConnection() {
        actor("t");
        IArchimateElement s = actor("s");
        IArchimateElement x = component("x");
        IArchimateElement y = component("y");
        IArchimateRelationship survivor = association("survivor", s, x);
        IArchimateRelationship dropped = association("dropped", s, x);
        IArchimateRelationship onIt = association("on-it", y, dropped);
        IDiagramModelArchimateObject boxS = place(viewA, s, "box-s");
        IDiagramModelArchimateObject boxX = place(viewA, x, "box-x");
        IDiagramModelArchimateObject boxY = place(viewA, y, "box-y");
        IDiagramModelArchimateConnection kept = connect(survivor, boxS, boxX, "conn-survivor");
        IDiagramModelArchimateConnection gone = connect(dropped, boxS, boxX, "conn-dropped");
        IDiagramModelArchimateConnection hanging = connect(onIt, boxY, gone, "conn-on-it");
        bendpoint(hanging, 3, 4);
        IDiagramModelNote note = factory.createDiagramModelNote();
        note.setId("note");
        viewA.getChildren().add(note);
        IDiagramModelConnection noteLink = factory.createDiagramModelConnection();
        noteLink.setId("note-link");
        noteLink.connect(note, gone);

        ParallelCollapsed collapsed = fold(cluster("t", "s")).clusters().get(0).parallelsCollapsed().get(0);

        assertEquals(List.of("conn-dropped"), collapsed.connectionsRemoved());
        assertSame(kept, hanging.getTarget());
        assertSame(boxY, hanging.getSource());
        assertTrue(kept.getTargetConnections().contains(hanging));
        assertEquals(1, hanging.getBendpoints().size());
        assertSame(survivor, onIt.getTarget());
        assertSame(kept, noteLink.getTarget());
        assertTrue(kept.getTargetConnections().contains(noteLink));
        assertTrue(note.getSourceConnections().contains(noteLink));
        assertEquals(new TreeSet<>(List.of("conn-on-it", "note-link")), new TreeSet<>(collapsed.connectionsMoved()));
        assertEquals(List.of("on-it"), collapsed.relationshipsMoved());
        assertConsistent();
    }

    @Test
    public void shouldLeaveAConnectionOnARepointedConnection_whereItIs() {
        actor("t");
        IArchimateElement s = actor("s");
        IArchimateElement x = component("x");
        IArchimateElement y = component("y");
        IArchimateRelationship survivor = association("survivor", element("t"), x);
        IArchimateRelationship dropped = association("dropped", s, x);
        IArchimateRelationship onIt = association("on-it", y, dropped);
        IDiagramModelArchimateConnection drawn = connect(dropped, place(viewA, s, "box-s"),
                place(viewA, x, "box-x"), "conn-dropped");
        IDiagramModelArchimateConnection hanging = connect(onIt, place(viewA, y, "box-y"), drawn, "conn-on-it");

        ParallelCollapsed collapsed = fold(cluster("t", "s")).clusters().get(0).parallelsCollapsed().get(0);

        assertEquals(List.of("conn-dropped"), collapsed.connectionsRepointed());
        assertSame(drawn, hanging.getTarget());
        assertSame(survivor, onIt.getTarget());
        assertEquals(List.of(), collapsed.connectionsMoved());
        assertConsistent();
    }

    @Test
    public void shouldReportButNotFold_aPairThatOnlyAMoveMadeParallel() {
        IArchimateRelationship[] pair = pair(ASSOCIATION);
        IArchimateElement y = component("y");
        IArchimateRelationship moved = association("moved", y, pair[1]);
        IArchimateRelationship already = association("already", y, pair[0]);

        MergeElementsResultDto result = fold(cluster("t", "s"));

        assertNotNull(moved.getArchimateModel());
        assertNotNull(already.getArchimateModel());
        assertSame(pair[0], moved.getTarget());
        Cluster c = result.clusters().get(0);
        assertEquals(List.of(new ParallelNotCollapsed("moved", "already", ASSOCIATION,
                List.of("CREATED_BY_COLLAPSE"))), c.parallelsNotCollapsed());
        assertEquals(1, c.parallelRelationships().size());
        assertEquals(new TreeSet<>(List.of("moved", "already")),
                new TreeSet<>(c.parallelRelationships().get(0).relationshipIds()));
    }

    @Test
    public void shouldEndAnAttachmentOnTheOtherGroupsSurvivor_whenBothItsEndsAreFolded() {
        IArchimateElement t = actor("t");
        IArchimateElement s = actor("s");
        IArchimateElement x = component("x");
        IArchimateElement z = component("z");
        IArchimateRelationship toX = association("to-x", t, x);
        IArchimateRelationship toZ = association("to-z", t, z);
        IArchimateRelationship droppedX = association("dropped-x", s, x);
        IArchimateRelationship droppedZ = association("dropped-z", s, z);
        IArchimateRelationship between = association("between", droppedX, droppedZ);

        MergeElementsResultDto result = fold(cluster("t", "s"));

        assertSame(toX, between.getSource());
        assertSame(toZ, between.getTarget());
        assertEquals(Map.of("dropped-x", "to-x", "dropped-z", "to-z"), result.relationshipIdMap());
        for (ParallelCollapsed collapsed : result.clusters().get(0).parallelsCollapsed()) {
            assertEquals(List.of("between"), collapsed.relationshipsMoved());
        }
        assertNull(droppedX.getArchimateModel());
        assertNull(droppedZ.getArchimateModel());
    }

    @Test
    public void shouldKeepBoth_whenARelationshipJoinsTheFoldedOneToTheSurvivor() {
        assertNotFolded(ASSOCIATION, (survivor, dropped) -> association("joining", survivor, dropped),
                "JOINED_TO_SURVIVOR");
        IArchimateRelationship joining = (IArchimateRelationship) com.archimatetool.model.util.ArchimateModelUtils
                .getObjectByID(model, "joining");
        assertEquals("survivor", joining.getSource().getId());
        assertEquals("dropped", joining.getTarget().getId());
    }

    @Test
    public void shouldKeepTheSecond_whenARelationshipJoinsItToOneAlreadyFoldedIntoTheSurvivor() {
        IArchimateElement t = actor("t");
        IArchimateElement s1 = actor("s1");
        IArchimateElement s2 = actor("s2");
        IArchimateElement x = component("x");
        IArchimateRelationship survivor = association("survivor", t, x);
        IArchimateRelationship first = association("first", s1, x);
        IArchimateRelationship second = association("second", s2, x);
        IArchimateRelationship joining = association("joining", first, second);

        MergeElementsResultDto result = fold(cluster("t", "s1", "s2"));

        assertEquals(Map.of("first", "survivor"), result.relationshipIdMap());
        assertNotNull(second.getArchimateModel());
        assertEquals(List.of(new ParallelNotCollapsed("second", "survivor", ASSOCIATION,
                List.of("JOINED_TO_SURVIVOR"))), result.clusters().get(0).parallelsNotCollapsed());
        assertSame(survivor, joining.getSource());
        assertSame(second, joining.getTarget());
    }

    @Test
    public void shouldNotSayAMoveMadeAPairParallel_whenTheyWereParallelOnTheFoldedOneAlready() {
        IArchimateRelationship[] pair = pair(ASSOCIATION);
        IArchimateElement y = component("y");
        association("on-it-1", y, pair[1]).setName("one");
        association("on-it-2", y, pair[1]).setName("two");

        MergeElementsResultDto result = fold(cluster("t", "s"));

        assertEquals(Map.of("dropped", "survivor"), result.relationshipIdMap());
        assertEquals(List.of(), result.clusters().get(0).parallelsNotCollapsed());
    }

    @Test
    public void shouldRemoveAConnection_whereTheConnectionItHangsOnIsReplacedByOneDrawingTheSurvivor() {
        actor("t");
        IArchimateElement s = actor("s");
        IArchimateElement w = component("w");
        IArchimateElement x = component("x");
        IArchimateElement x2 = component("x2");
        association("survivor", element("t"), w);
        IArchimateRelationship dropped = association("dropped", s, w);
        IArchimateRelationship onIt = association("on-it", dropped, x2);
        IArchimateRelationship onItAgain = association("on-it-again", dropped, x2);
        // First in model order, so their fold is planned before the one of what they hang on.
        ((com.archimatetool.model.IFolder) onIt.eContainer()).getElements().move(0, onItAgain);
        ((com.archimatetool.model.IFolder) onIt.eContainer()).getElements().move(0, onIt);
        IDiagramModelArchimateObject boxS = place(viewA, s, "box-s");
        IDiagramModelArchimateObject boxW = place(viewA, w, "box-w");
        IDiagramModelArchimateObject boxX2 = place(viewA, x2, "box-x2");
        place(viewA, x, "box-x");
        IDiagramModelArchimateConnection first = connect(dropped, boxS, boxW, "conn-1");
        IDiagramModelArchimateConnection second = connect(dropped, boxS, boxW, "conn-2");
        IDiagramModelArchimateConnection drawnOnFirst = connect(onIt, first, boxX2, "conn-on-it");
        IDiagramModelArchimateConnection drawnOnSecond = connect(onItAgain, second, boxX2, "conn-on-it-again");

        MergeElementsResultDto result = fold(cluster("t", "s"), cluster("x", "x2"));

        assertEquals(Map.of("dropped", "survivor", "on-it-again", "on-it"), result.relationshipIdMap());
        assertNull("the second copy is removed", second.getDiagramModel());
        assertNull("its connection would draw on-it a second time between the same two", drawnOnSecond.getDiagramModel());
        assertEquals(List.of(drawnOnFirst), List.copyOf(first.getSourceConnections()));
        assertEquals(List.of(drawnOnFirst), List.copyOf(boxX2.getTargetConnections()));
        assertConsistent();
    }

    // ---- interplay with the rest of the merge -------------------------------------------------

    @Test
    public void shouldNeverFoldARelationshipTheSelfRelationshipDropRemoves() {
        IArchimateElement t = actor("t");
        IArchimateElement s = actor("s");
        IArchimateRelationship self = association("self", s, t);
        IArchimateRelationship fromDuplicate = association("from-duplicate", s, self);
        IArchimateRelationship fromTarget = association("from-target", t, self);

        MergeElementsResultDto result = fold(cluster("t", "s"));

        assertNull(fromDuplicate.getArchimateModel());
        assertNull(fromTarget.getArchimateModel());
        Cluster c = result.clusters().get(0);
        assertEquals(List.of(), c.parallelsCollapsed());
        assertEquals(Map.of(), result.relationshipIdMap());
        assertEquals(new TreeSet<>(List.of("from-duplicate", "from-target")), new TreeSet<>(c.cascadedRelationshipIds()));
    }

    @Test
    public void shouldFoldAKeptSelfRelationship_intoTheTargetsOwnLoop() {
        IArchimateElement t = actor("t");
        IArchimateElement s = actor("s");
        IArchimateRelationship loop = association("loop", t, t);
        IArchimateRelationship self = association("self", s, t);

        MergeElementsResultDto result = runCommand(new MergeElementsPlan.Options(
                MergeElementsPlan.PropertyPolicy.UNION, MergeElementsPlan.SelfPolicy.KEEP, false, true,
                (source, target, type) -> true), cluster("t", "s"));

        assertNotNull(loop.getArchimateModel());
        assertNull(self.getArchimateModel());
        assertEquals(Map.of("self", "loop"), result.relationshipIdMap());
    }

    @Test
    public void shouldFoldADuplicatesOwnLoop_intoTheTargetsOwnLoop() {
        IArchimateElement t = actor("t");
        IArchimateElement s = actor("s");
        IArchimateRelationship targetLoop = association("target-loop", t, t);
        IArchimateRelationship duplicateLoop = association("duplicate-loop", s, s);

        MergeElementsResultDto result = fold(cluster("t", "s"));

        assertNotNull(targetLoop.getArchimateModel());
        assertNull(duplicateLoop.getArchimateModel());
        assertEquals(Map.of("duplicate-loop", "target-loop"), result.relationshipIdMap());
    }

    @Test
    public void shouldReportAFoldAcrossTwoClusters_underTheFirstClusterInTheCall() {
        IArchimateElement t = actor("t");
        IArchimateElement s = actor("s");
        IArchimateElement u = component("u");
        IArchimateElement v = component("v");
        association("survivor", t, u);
        association("dropped", s, v);

        MergeElementsResultDto result = fold(cluster("u", "v"), cluster("t", "s"));

        assertEquals(1, result.clusters().get(0).parallelsCollapsed().size());
        assertEquals(List.of(), result.clusters().get(1).parallelsCollapsed());
    }

    @Test
    public void shouldNotCountAFoldedRelationshipAsRepointed_andSumTheTotals() {
        IArchimateElement t = actor("t");
        IArchimateElement s = actor("s");
        IArchimateElement x = component("x");
        IArchimateElement y = component("y");
        association("survivor", t, x);
        association("dropped", s, x);
        association("moved", s, y);
        IArchimateElement u = component("u");
        IArchimateElement v = component("v");
        association("u-survivor", u, y);
        association("u-dropped", v, y);

        MergeElementsResultDto result = fold(cluster("t", "s"), cluster("u", "v"));

        assertEquals(1, result.clusters().get(0).relationshipsRepointed());
        assertEquals(0, result.clusters().get(1).relationshipsRepointed());
        assertEquals(1, result.totals().relationshipsRepointed());
        assertEquals(Integer.valueOf(2), result.totals().parallelRelationshipsCollapsed());
        int sum = 0;
        for (Cluster c : result.clusters()) {
            sum += c.parallelsCollapsed().size();
        }
        assertEquals(2, sum);
    }

    // ---- undo, redo, preview, execute-time -----------------------------------------------------

    @Test
    public void shouldKeepEveryConnectionConsistent_andUndoExactly_andRedoTheSameFolds() {
        buildEverything();
        Map<String, String> before = snapshot();

        MergeElementsResultDto result = fold(cluster("t", "s1", "s2"));
        assertFalse("precondition: something folded", result.relationshipIdMap().isEmpty());
        assertConsistent();
        Map<String, String> merged = snapshot();

        lastDispatched.undo();
        assertConsistent();
        MergeElementsCommandTest.assertSnapshot("one undo restores everything the folds changed", before,
                snapshot());

        lastDispatched.redo();
        assertConsistent();
        MergeElementsCommandTest.assertSnapshot("redo replays the same folds", merged, snapshot());

        lastDispatched.undo();
        MergeElementsCommandTest.assertSnapshot("and undo again", before, snapshot());
    }

    @Test
    public void shouldPreviewExactlyTheFoldsTheCommandMakes_andListEveryRemovalForApproval() {
        buildEverything();

        PreparedMutation<MergeElementsResultDto> prepared = MergeElementsOperations.prepare(model,
                List.of(cluster("t", "s1", "s2")), MergeElementsOperations.options(null, null, false, true));
        MergeElementsResultDto previewed = prepared.entity();
        MergeElementsCommand command = (MergeElementsCommand) prepared.command();
        Set<String> listed = command.getListedRemovals();

        command.execute();
        assertNull(command.getSkipReason());
        MergeElementsResultDto done = command.getResult();

        assertFalse("precondition: the fixture folds", done.relationshipIdMap().isEmpty());
        assertEquals(done.relationshipIdMap(), previewed.relationshipIdMap());
        assertEquals(done.totals(), previewed.totals());
        for (int i = 0; i < done.clusters().size(); i++) {
            Cluster want = done.clusters().get(i);
            Cluster got = previewed.clusters().get(i);
            assertEquals(want.parallelsCollapsed(), got.parallelsCollapsed());
            assertEquals(want.parallelsNotCollapsed(), got.parallelsNotCollapsed());
            assertEquals(want.parallelRelationships(), got.parallelRelationships());
            assertEquals(want.relationshipsRepointed(), got.relationshipsRepointed());
        }
        for (ParallelCollapsed collapsed : done.clusters().get(0).parallelsCollapsed()) {
            assertTrue(collapsed.droppedId() + " is listed for approval", listed.contains(collapsed.droppedId()));
            assertTrue(listed.containsAll(collapsed.connectionsRemoved()));
        }
    }

    @Test
    public void shouldKeepAPlannedFold_whenAHumanMadeItIncompatibleBeforeItRan() {
        IArchimateRelationship[] pair = pair(ASSOCIATION);
        pair[0].setName("uses");
        PreparedMutation<MergeElementsResultDto> prepared = MergeElementsOperations.prepare(model,
                List.of(cluster("t", "s")), MergeElementsOperations.options(null, null, false, true));
        assertEquals(1, prepared.entity().clusters().get(0).parallelsCollapsed().size());

        pair[1].setName("calls");
        MergeElementsCommand command = (MergeElementsCommand) prepared.command();
        command.execute();

        assertNull(command.getSkipReason());
        assertNotNull(pair[1].getArchimateModel());
        Cluster c = command.getResult().clusters().get(0);
        assertEquals(List.of(), c.parallelsCollapsed());
        assertEquals(List.of("NAME_DIFFERS"), c.parallelsNotCollapsed().get(0).reasons());
    }

    @Test
    public void shouldWriteNothing_whenAHumanMadeAnotherPairFoldableBeforeItRan() {
        IArchimateRelationship[] pair = pair(ASSOCIATION);
        pair[0].setName("uses");
        pair[1].setName("calls");
        PreparedMutation<MergeElementsResultDto> prepared = MergeElementsOperations.prepare(model,
                List.of(cluster("t", "s")), MergeElementsOperations.options(null, null, false, true));
        assertEquals(List.of(), prepared.entity().clusters().get(0).parallelsCollapsed());

        pair[1].setName(null);
        Map<String, String> before = snapshot();
        MergeElementsCommand command = (MergeElementsCommand) prepared.command();
        command.execute();

        assertNotNull(command.getSkipReason());
        assertTrue(command.getSkipReason(), command.getSkipReason().contains("did not list")
                && command.getSkipReason().contains(pair[1].getId()));
        MergeElementsCommandTest.assertSnapshot("declined means unchanged", before, snapshot());
    }

    // ---- fixture -------------------------------------------------------------------------------

    /**
     * Folds of every kind in one cluster of two duplicates, across two views: a connection
     * re-pointed and one removed, a relationship and two connections that hang on a removed one,
     * a pair kept for its name, a pair only a move made parallel, and an attachment between two
     * folded relationships.
     */
    private void buildEverything() {
        IArchimateElement t = actor("t");
        IArchimateElement s1 = actor("s1");
        IArchimateElement s2 = actor("s2");
        IArchimateElement x = component("x");
        IArchimateElement y = component("y");
        IArchimateElement z = component("z");
        IArchimateRelationship toX = association("to-x", t, x);
        IArchimateRelationship s1x = association("s1-x", s1, x);
        s1x.setName("serves");
        s1x.setDocumentation("docs");
        property(s1x, "k", "v");
        IArchimateRelationship s1xTwin = association("s1-x-twin", s1, x);
        IArchimateRelationship s2x = association("s2-x", s2, x);
        s2x.setName("differs");
        IArchimateRelationship s1z = association("s1-z", s1, z);
        association("to-z", t, z);
        IArchimateRelationship onTwin = association("on-twin", y, s1xTwin);
        association("on-survivor", y, toX);
        association("between", s1x, s1z);

        IDiagramModelArchimateObject boxS1 = place(viewA, s1, "box-s1");
        IDiagramModelArchimateObject boxX = place(viewA, x, "box-x");
        IDiagramModelArchimateObject boxY = place(viewA, y, "box-y");
        IDiagramModelArchimateConnection c1 = connect(s1x, boxS1, boxX, "conn-s1-x");
        bendpoint(c1, 5, 6);
        IDiagramModelArchimateConnection twin = connect(s1xTwin, boxS1, boxX, "conn-twin");
        connect(onTwin, boxY, twin, "conn-on-twin");
        IDiagramModelNote note = factory.createDiagramModelNote();
        note.setId("note");
        viewA.getChildren().add(note);
        IDiagramModelConnection noteLink = factory.createDiagramModelConnection();
        noteLink.setId("note-link");
        noteLink.connect(note, twin);
        IDiagramModelArchimateObject boxS2 = place(viewB, s2, "box-s2");
        IDiagramModelArchimateObject boxX2 = place(viewB, x, "box-x2");
        connect(s2x, boxS2, boxX2, "conn-s2-x");
        connect(s1z, place(viewB, s1, "box-s1-b"), place(viewB, z, "box-z"), "conn-s1-z");
    }

    private IArchimateRelationship[] pair(String typeName) {
        IArchimateElement t = actor("t");
        IArchimateElement s = actor("s");
        IArchimateElement x = component("x");
        EClass type = (EClass) pkg.getEClassifier(typeName);
        return new IArchimateRelationship[] {relationship(type, "survivor", t, x),
                relationship(type, "dropped", s, x)};
    }

    private void assertNotFolded(String typeName, BiConsumer<IArchimateRelationship, IArchimateRelationship> setUp,
            String... reasons) {
        IArchimateRelationship[] pair = pair(typeName);
        setUp.accept(pair[0], pair[1]);

        MergeElementsResultDto result = fold(cluster("t", "s"));

        assertNotNull(pair[0].getArchimateModel());
        assertNotNull("kept: " + List.of(reasons), pair[1].getArchimateModel());
        Cluster c = result.clusters().get(0);
        assertEquals(List.of(), c.parallelsCollapsed());
        assertEquals(List.of(new ParallelNotCollapsed("dropped", "survivor", typeName, List.of(reasons))),
                c.parallelsNotCollapsed());
        assertEquals(Map.of(), result.relationshipIdMap());
        assertEquals(1, c.parallelRelationships().size());
    }

    /**
     * Every view connection draws a relationship whose ends are the concepts of the two objects
     * it joins, and is listed by that relationship. Every mismatch is listed, not the first.
     */
    private void assertConsistent() {
        List<String> wrong = new ArrayList<>();
        for (IDiagramModel view : model.getDiagramModels()) {
            view.eAllContents().forEachRemaining(each -> {
                if (each instanceof IDiagramModelArchimateConnection connection) {
                    IArchimateRelationship relationship = connection.getArchimateRelationship();
                    IArchimateConcept source = ((IDiagramModelArchimateComponent) connection.getSource())
                            .getArchimateConcept();
                    IArchimateConcept target = ((IDiagramModelArchimateComponent) connection.getTarget())
                            .getArchimateConcept();
                    if (relationship.getArchimateModel() == null) {
                        wrong.add(connection.getId() + " draws " + relationship.getId() + ", which is not in the model");
                    }
                    if (relationship.getSource() != source || relationship.getTarget() != target) {
                        wrong.add(connection.getId() + " draws " + relationship.getId() + " "
                                + id(relationship.getSource()) + "->" + id(relationship.getTarget())
                                + " between " + id(source) + " and " + id(target));
                    }
                    if (!relationship.getReferencingDiagramComponents().contains(connection)) {
                        wrong.add(connection.getId() + " is not listed by " + relationship.getId());
                    }
                }
            });
        }
        assertTrue("inconsistent connections:\n  " + String.join("\n  ", wrong), wrong.isEmpty());
    }

    /**
     * The structural snapshot of the merge tests, plus what it cannot see because Archi keeps it
     * in transient lists: each concept's relationship lists and each relationship's connections.
     */
    private Map<String, String> snapshot() {
        Map<String, String> out = new TreeMap<>(MergeElementsCommandTest.snapshot(model, Set.of()));
        model.eAllContents().forEachRemaining(each -> {
            if (each instanceof IArchimateConcept concept) {
                List<String> lists = new ArrayList<>();
                concept.getSourceRelationships().forEach(r -> lists.add("out " + r.getId()));
                concept.getTargetRelationships().forEach(r -> lists.add("in " + r.getId()));
                lists.sort(null);
                List<String> drawn = new ArrayList<>();
                concept.getReferencingDiagramComponents().forEach(c -> drawn.add(c.getId()));
                drawn.sort(null);
                out.put("~" + concept.getId(), lists + " drawn " + drawn);
            }
            if (each instanceof IConnectable connectable && connectable instanceof IIdentifier identified) {
                List<String> in = new ArrayList<>();
                connectable.getTargetConnections().forEach(c -> in.add(c.getId()));
                List<String> outs = new ArrayList<>();
                connectable.getSourceConnections().forEach(c -> outs.add(c.getId()));
                out.put("~~" + identified.getId(), "out " + outs + " in " + in);
            }
        });
        return out;
    }

    private static String id(Object concept) {
        return concept instanceof IIdentifier identified ? identified.getId() : String.valueOf(concept);
    }

    /** What an open view of {@code box} hears about its outgoing connections. */
    private static List<String> listen(IDiagramModelArchimateObject box) {
        List<String> heard = new ArrayList<>();
        box.eAdapters().add(new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                if (n.getFeature() instanceof EStructuralFeature feature
                        && feature.getName().equals("sourceConnections")) {
                    if (n.getEventType() == Notification.ADD) {
                        heard.add("ADD " + id(n.getNewValue()));
                    } else if (n.getEventType() == Notification.REMOVE) {
                        heard.add("REMOVE " + id(n.getOldValue()));
                    }
                }
            }
        });
        return heard;
    }

    private IArchimateDiagramModel view(String id) {
        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId(id);
        view.setName(id);
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        return view;
    }

    private IArchimateElement actor(String id) {
        IArchimateElement element = factory.createBusinessActor();
        element.setId(id);
        element.setName("Customer");
        model.getFolder(FolderType.BUSINESS).getElements().add(element);
        return element;
    }

    private IArchimateElement component(String id) {
        IArchimateElement element = factory.createApplicationComponent();
        element.setId(id);
        element.setName(id);
        model.getFolder(FolderType.APPLICATION).getElements().add(element);
        return element;
    }

    private IArchimateElement element(String id) {
        return (IArchimateElement) com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, id);
    }

    private IArchimateRelationship association(String id, IArchimateConcept source, IArchimateConcept target) {
        return relationship(pkg.getAssociationRelationship(), id, source, target);
    }

    private IArchimateRelationship relationship(EClass type, String id, IArchimateConcept source,
            IArchimateConcept target) {
        IArchimateRelationship relationship = (IArchimateRelationship) factory.create(type);
        relationship.setId(id);
        relationship.connect(source, target);
        model.getFolder(FolderType.RELATIONS).getElements().add(relationship);
        return relationship;
    }

    private IProperty property(IProperties owner, String key, String value) {
        IProperty property = factory.createProperty();
        property.setKey(key);
        property.setValue(value);
        owner.getProperties().add(property);
        return property;
    }

    private static List<String> pairs(IProperties owner) {
        return owner.getProperties().stream().map(p -> p.getKey() + "=" + p.getValue()).toList();
    }

    private IDiagramModelArchimateObject place(IDiagramModelContainer parent, IArchimateElement element, String id) {
        IDiagramModelArchimateObject box = factory.createDiagramModelArchimateObject();
        box.setId(id);
        box.setArchimateElement(element);
        box.setBounds(0, 0, 120, 55);
        parent.getChildren().add(box);
        return box;
    }

    private IDiagramModelArchimateConnection connect(IArchimateRelationship relationship, IConnectable source,
            IConnectable target, String id) {
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
        connection.getBendpoints().add(bendpoint);
    }

    private static MergeClusterRequest cluster(String targetId, String... sourceIds) {
        return new MergeClusterRequest(targetId, List.of(sourceIds));
    }

    private MergeElementsResultDto fold(MergeClusterRequest... clusters) {
        MutationResult<MergeElementsResultDto> result = accessor.mergeElements(SESSION, List.of(clusters), null,
                null, false, true);
        assertNull("an immediate call", result.proposalContext());
        assertTrue(lastDispatched instanceof MergeElementsCommand);
        return result.entity();
    }

    private MergeElementsResultDto runCommand(MergeElementsPlan.Options options, MergeClusterRequest... clusters) {
        PreparedMutation<MergeElementsResultDto> prepared = MergeElementsOperations.prepare(model,
                List.of(clusters), options);
        lastDispatched = prepared.command();
        prepared.command().execute();
        MergeElementsCommand command = (MergeElementsCommand) prepared.command();
        assertNull(command.getSkipReason());
        return command.getResult();
    }

    private ArchiModelAccessorImpl createAccessor(IArchimateModel testModel) {
        MutationDispatcher dispatcher = new MutationDispatcher(() -> testModel) {
            @Override
            public void dispatchImmediate(Command command) {
                lastDispatched = command;
                command.execute();
            }

            @Override
            protected void dispatchCommand(Command command) {
                lastDispatched = command;
                command.execute();
            }
        };
        dispatcher.setApprovalModeProvider(() -> false);
        return new ArchiModelAccessorImpl(models, dispatcher);
    }
}
