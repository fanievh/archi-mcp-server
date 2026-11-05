package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.common.notify.impl.AdapterImpl;
import org.eclipse.emf.ecore.EObject;
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
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.IInfluenceRelationship;
import com.archimatetool.model.IJunction;
import com.archimatetool.model.IProfile;
import com.archimatetool.model.IProperty;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.response.dto.ChangeElementTypeResultDto.SpecializationDropped;
import net.vheerden.archi.mcp.response.dto.ChangeRelationshipTypeResultDto;
import net.vheerden.archi.mcp.response.dto.ElementTypeChangeRequest;
import net.vheerden.archi.mcp.response.dto.RelationshipSemanticAttributes;
import net.vheerden.archi.mcp.response.dto.RelationshipTypeChangeRequest;

/**
 * A relationship type change at the model: what the new relationship keeps, copies and drops,
 * what is re-pointed to it and where, how two listed relationships joined to each other change
 * together, how the whole is undone, redone and rolled back, and what the command does when the
 * model has changed between preparing the call and running it.
 *
 * <p>Archi's relationship rules cannot load their matrix in a plain JVM, so stand-ins are passed:
 * a type rule that refuses named triples and a concept rule that refuses a Junction end.</p>
 */
public class ChangeRelationshipTypeCommandTest {

    private static final IArchimatePackage P = IArchimatePackage.eINSTANCE;

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private IArchimateModel model;
    private IArchimateDiagramModel viewA;
    private IArchimateDiagramModel viewB;

    /** "Source>Target:Relationship" triples the stand-in type rule refuses. */
    private final Set<String> refused = new HashSet<>();
    private final ChangeElementTypeOperations.TypeRule rule = (source, target, relationship) ->
            !refused.contains(source.getName() + ">" + target.getName() + ":" + relationship.getName());
    private final MergeElementsPlan.RelationshipRule conceptRule = (source, target, type) ->
            !(source instanceof IJunction || target instanceof IJunction);

    @Before
    public void setUp() {
        model = factory.createArchimateModel();
        model.setId("model-retype");
        model.setDefaults();
        viewA = view("view-a");
        viewB = view("view-b");
    }

    // ---- the new relationship -------------------------------------------------------------------

    @Test
    public void shouldKeepTheIdAndFolderPlace_andRemoveTheOldRelationship_whenChangingARelationshipsType() {
        IArchimateElement x = component("x", "Billing");
        IArchimateElement y = component("y", "Ledger");
        association("r-before", x, y);
        IArchimateRelationship old = flow("r", x, y);
        association("r-after", x, y);

        ChangeRelationshipTypeResultDto result = execute(change("r", "TriggeringRelationship"));

        IArchimateRelationship now = relationship("r");
        assertNotSame(old, now);
        assertEquals(P.getTriggeringRelationship(), now.eClass());
        assertSame(x, now.getSource());
        assertSame(y, now.getTarget());
        assertEquals("it takes the old relationship's place in its folder", List.of("r-before", "r", "r-after"),
                ids(model.getFolder(FolderType.RELATIONS)));
        assertNull("the old relationship is in no folder", old.eContainer());
        assertFalse(x.getSourceRelationships().contains(old));
        assertFalse(y.getTargetRelationships().contains(old));
        assertTrue(x.getSourceRelationships().contains(now));
        assertTrue(y.getTargetRelationships().contains(now));
        assertTrue(old.getReferencingDiagramComponents().isEmpty());
        assertEquals(new ChangeRelationshipTypeResultDto.Change("r", "r", "", "FlowRelationship",
                "TriggeringRelationship", "x", "y", 0, 0, model.getFolder(FolderType.RELATIONS).getId(), null, null,
                null), result.changes().get(0));
        assertEquals(Map.of("r", "r"), result.idMap());
        assertEquals(new ChangeRelationshipTypeResultDto.Totals(1, 0, 0, 0, 0), result.totals());
    }

    @Test
    public void shouldKeepASubfolderAndItsIndex_whenTheRelationshipLivesInOne() {
        IArchimateElement x = component("x", "Billing");
        IArchimateElement y = component("y", "Ledger");
        IFolder sub = factory.createFolder();
        sub.setId("sub");
        model.getFolder(FolderType.RELATIONS).getFolders().add(sub);
        IArchimateRelationship first = factory.createAssociationRelationship();
        first.setId("r-first");
        first.connect(x, y);
        sub.getElements().add(first);
        IArchimateRelationship old = factory.createFlowRelationship();
        old.setId("r");
        old.connect(x, y);
        sub.getElements().add(old);

        ChangeRelationshipTypeResultDto result = execute(change("r", "TriggeringRelationship"));

        assertSame(sub, relationship("r").eContainer());
        assertEquals(List.of("r-first", "r"), ids(sub));
        assertEquals("sub", result.changes().get(0).folderId());
    }

    @Test
    public void shouldCopyNameDocumentationPropertiesAndFeatures_withNoNote_andDropTheSpecializationAndAttribute() {
        IArchimateElement x = component("x", "Billing");
        IArchimateElement d = dataObject("d", "Invoice");
        IAccessRelationship old = factory.createAccessRelationship();
        relationship(old, "r", x, d);
        old.setAccessType(IAccessRelationship.READ_WRITE_ACCESS);
        old.setName("reads and writes");
        old.setDocumentation("line one\r\nline two  ");
        property(old, "k", "1");
        property(old, "k", "2");
        property(old, "other", null);
        old.getFeatures().putString("f1", "v1");
        IProfile profile = profile("profile-strong", "Strong", "AccessRelationship");
        old.getProfiles().add(profile);

        ChangeRelationshipTypeResultDto result = execute(change("r", "AssociationRelationship"));

        IArchimateRelationship now = relationship("r");
        assertEquals("reads and writes", now.getName());
        assertEquals("the documentation byte for byte, with no note", "line one\r\nline two  ",
                now.getDocumentation());
        assertEquals(List.of("k=1", "k=2", "other=null"), pairs(now));
        assertNotSame(old.getProperties().get(0), now.getProperties().get(0));
        assertEquals("the old relationship keeps its own properties for undo", 3, old.getProperties().size());
        assertEquals("v1", now.getFeatures().getString("f1", null));
        assertNotSame(old.getFeatures().get(0), now.getFeatures().get(0));
        assertTrue("the specialization is not carried over", now.getProfiles().isEmpty());
        assertTrue("the specialization stays in the model", model.getProfiles().contains(profile));
        ChangeRelationshipTypeResultDto.Change change = result.changes().get(0);
        assertEquals(new RelationshipSemanticAttributes("readwrite", null, null), change.attributesDropped());
        assertEquals(new SpecializationDropped("Strong", "AccessRelationship"), change.specializationDropped());
        assertEquals("reads and writes", change.name());
        assertEquals(new ChangeRelationshipTypeResultDto.Totals(1, 0, 0, 1, 1), result.totals());
    }

    @Test
    public void shouldGiveTheNewRelationshipItsTypesDefaultAttributes_andReportThemAsTheModelHoldsThem() {
        IArchimateElement x = component("x", "Billing");
        IArchimateElement d = dataObject("d", "Invoice");
        flow("to-access", x, d);
        flow("to-association", x, d);
        flow("to-influence", x, d);

        ChangeRelationshipTypeResultDto result = execute(change("to-access", "AccessRelationship"),
                change("to-association", "AssociationRelationship"), change("to-influence", "InfluenceRelationship"));

        assertEquals(IAccessRelationship.WRITE_ACCESS, ((IAccessRelationship) relationship("to-access")).getAccessType());
        assertFalse(((IAssociationRelationship) relationship("to-association")).isDirected());
        String strength = ((IInfluenceRelationship) relationship("to-influence")).getStrength();
        assertTrue(strength == null || strength.isEmpty());
        assertEquals(new RelationshipSemanticAttributes("write", null, null), result.changes().get(0).attributes());
        assertEquals(new RelationshipSemanticAttributes(null, false, null), result.changes().get(1).attributes());
        assertNull("an unset strength is not reported", result.changes().get(2).attributes());
        for (ChangeRelationshipTypeResultDto.Change change : result.changes()) {
            assertNull("a Flow carries no attribute to lose", change.attributesDropped());
        }
    }

    @Test
    public void shouldReportADroppedAttribute_onlyWhenItHeldSomethingToLose() {
        IArchimateElement x = component("x", "Billing");
        IArchimateElement y = component("y", "Ledger");
        IInfluenceRelationship strong = factory.createInfluenceRelationship();
        relationship(strong, "influence-set", x, y);
        strong.setStrength("++");
        IInfluenceRelationship blank = factory.createInfluenceRelationship();
        relationship(blank, "influence-blank", x, y);
        IInfluenceRelationship spaces = factory.createInfluenceRelationship();
        relationship(spaces, "influence-spaces", x, y);
        spaces.setStrength("  ");
        IAssociationRelationship directed = factory.createAssociationRelationship();
        relationship(directed, "association-directed", x, y);
        directed.setDirected(true);
        IAssociationRelationship undirected = factory.createAssociationRelationship();
        relationship(undirected, "association-undirected", x, y);

        ChangeRelationshipTypeResultDto result = execute(change("influence-set", "ServingRelationship"),
                change("influence-blank", "ServingRelationship"), change("association-directed", "ServingRelationship"),
                change("association-undirected", "ServingRelationship"), change("influence-spaces", "ServingRelationship"));

        assertEquals(new RelationshipSemanticAttributes(null, null, "++"), result.changes().get(0).attributesDropped());
        assertNull(result.changes().get(1).attributesDropped());
        assertEquals(new RelationshipSemanticAttributes(null, true, null), result.changes().get(2).attributesDropped());
        assertNull("an undirected Association loses nothing", result.changes().get(3).attributesDropped());
        assertNull("a blank strength holds nothing to lose", result.changes().get(4).attributesDropped());
        assertEquals(2, result.totals().attributesDropped());
    }

    // ---- what is re-pointed ---------------------------------------------------------------------

    /**
     * Every connection that drew the old relationship draws the new one, and nothing else about
     * the model differs: the snapshot keys objects by id, and the relationship keeps its id, so
     * only the relationship's own class may change.
     */
    @Test
    public void shouldKeepEveryConnectionAndItsPlace_whenChangingARelationshipsType() {
        IArchimateElement x = component("x", "Billing");
        IArchimateElement y = component("y", "Ledger");
        IArchimateRelationship before = association("r-first", x, y);
        IArchimateRelationship old = flow("r", x, y);
        IArchimateRelationship after = association("r-last", x, y);
        IDiagramModelArchimateObject boxX = place(viewA, x, "box-x", 0, 0, 120, 55);
        IDiagramModelArchimateObject boxY = place(viewA, y, "box-y", 300, 0, 120, 55);
        connect(before, boxX, boxY, "conn-first");
        IDiagramModelArchimateConnection drawn = connect(old, boxX, boxY, "conn-r");
        connect(after, boxX, boxY, "conn-last");
        bendpoint(drawn, 10, 20);
        bendpoint(drawn, 30, 40);
        drawn.getFeatures().putString("labelOffset", "5,6");
        drawn.setTextPosition(0);
        drawn.setLineColor("#ff0000");
        drawn.setLineWidth(3);
        IDiagramModelArchimateObject boxX2 = place(viewB, x, "box-x-b", 0, 0, 120, 55);
        IDiagramModelArchimateObject boxY2 = place(viewB, y, "box-y-b", 300, 0, 120, 55);
        IDiagramModelArchimateConnection second = connect(old, boxX2, boxY2, "conn-r-b");
        Map<String, String> snapshotBefore = MergeElementsCommandTest.snapshot(model, Set.of());

        ChangeRelationshipTypeResultDto result = execute(change("r", "TriggeringRelationship"));

        IArchimateRelationship now = relationship("r");
        assertSame(now, drawn.getArchimateRelationship());
        assertSame(now, second.getArchimateRelationship());
        assertEquals("its index in its source's list", 1, boxX.getSourceConnections().indexOf(drawn));
        assertEquals("its index in its target's list", 1, boxY.getTargetConnections().indexOf(drawn));
        assertSame(boxX, drawn.getSource());
        assertSame(boxY, drawn.getTarget());
        Map<String, String> expected = new TreeMap<>(snapshotBefore);
        expected.replace("#r", snapshotBefore.get("#r").replaceFirst("^FlowRelationship ", "TriggeringRelationship "));
        MergeElementsCommandTest.assertSnapshot("only the relationship's class changes", expected,
                MergeElementsCommandTest.snapshot(model, Set.of()));
        assertEquals(2, result.changes().get(0).connectionsRepointed());
        assertEquals(2, result.totals().connectionsRepointed());
    }

    /**
     * Setting the relationship a connection draws notifies nothing an open diagram listens to; the
     * connection leaving and re-joining both ends' lists is what makes the diagram redraw it, so
     * the redraw is asserted by its notifications, at the index it had, on change and on undo.
     */
    @Test
    public void shouldAnnounceEachConnectionToBothEnds_atTheIndexItHad_onChangeAndOnUndo() {
        IArchimateElement x = component("x", "Billing");
        IArchimateElement y = component("y", "Ledger");
        IArchimateRelationship first = association("r-first", x, y);
        IArchimateRelationship old = flow("r", x, y);
        IDiagramModelArchimateObject boxX = place(viewA, x, "box-x", 0, 0, 120, 55);
        IDiagramModelArchimateObject boxY = place(viewA, y, "box-y", 300, 0, 120, 55);
        connect(first, boxX, boxY, "conn-first");
        IDiagramModelArchimateConnection drawn = connect(old, boxX, boxY, "conn-r");
        List<String> heard = new ArrayList<>();
        AdapterImpl listener = new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                Object feature = n.getFeature();
                String list = feature == P.getConnectable_SourceConnections() ? "source"
                        : feature == P.getConnectable_TargetConnections() ? "target" : null;
                if (list == null) {
                    return;
                }
                if (n.getEventType() == Notification.REMOVE && n.getOldValue() == drawn) {
                    heard.add(list + " remove " + n.getPosition());
                } else if (n.getEventType() == Notification.ADD && n.getNewValue() == drawn) {
                    heard.add(list + " add " + n.getPosition());
                } else if (n.getEventType() == Notification.MOVE && n.getNewValue() == drawn) {
                    heard.add(list + " move " + n.getPosition());
                }
            }
        };
        boxX.eAdapters().add(listener);
        boxY.eAdapters().add(listener);
        ChangeRelationshipTypeCommand command = command(change("r", "TriggeringRelationship"));

        command.execute();
        List<String> onChange = List.copyOf(heard);
        heard.clear();
        command.undo();

        assertTrue("the connection must leave and re-join its source's list: " + onChange,
                onChange.contains("source remove 1") && onChange.indexOf("source remove 1") < lastAdd(onChange, "source"));
        assertTrue("and its target's list: " + onChange,
                onChange.contains("target remove 1") && onChange.indexOf("target remove 1") < lastAdd(onChange, "target"));
        assertTrue("on undo as well: " + heard, heard.contains("source remove 1") && heard.contains("target remove 1")
                && lastAdd(heard, "source") >= 0 && lastAdd(heard, "target") >= 0);
        assertEquals(1, boxX.getSourceConnections().indexOf(drawn));
        assertEquals(1, boxY.getTargetConnections().indexOf(drawn));
        assertSame(old, drawn.getArchimateRelationship());
    }

    @Test
    public void shouldRepointEveryRelationshipAttachedToIt_bothWays() {
        IArchimateElement x = component("x", "Billing");
        IArchimateElement y = component("y", "Ledger");
        IArchimateElement z = component("z", "Archive");
        IArchimateRelationship old = flow("r", x, y);
        IArchimateRelationship fromIt = association("r-from", old, z);
        fromIt.setName("from it");
        IArchimateRelationship toIt = association("r-to", z, old);
        IDiagramModelArchimateObject boxX = place(viewA, x, "box-x", 0, 0, 120, 55);
        IDiagramModelArchimateObject boxY = place(viewA, y, "box-y", 300, 0, 120, 55);
        IDiagramModelArchimateObject boxZ = place(viewA, z, "box-z", 600, 0, 120, 55);
        IDiagramModelArchimateConnection drawn = connect(old, boxX, boxY, "conn-r");
        IDiagramModelArchimateConnection onConnection = factory.createDiagramModelArchimateConnection();
        onConnection.setId("conn-to");
        onConnection.setArchimateRelationship(toIt);
        onConnection.connect(boxZ, drawn);

        ChangeRelationshipTypeResultDto result = execute(change("r", "TriggeringRelationship"));

        IArchimateRelationship now = relationship("r");
        assertSame(now, fromIt.getSource());
        assertSame(z, fromIt.getTarget());
        assertSame(now, toIt.getTarget());
        assertEquals("from it", fromIt.getName());
        assertEquals(P.getAssociationRelationship(), fromIt.eClass());
        assertSame("an attached relationship is re-pointed, not replaced", fromIt, relationship("r-from"));
        assertTrue(old.getSourceRelationships().isEmpty());
        assertTrue(old.getTargetRelationships().isEmpty());
        assertSame("a connection on the connection keeps its end", drawn, onConnection.getTarget());
        assertEquals(2, result.changes().get(0).relationshipsRepointed());
        assertEquals(2, result.totals().relationshipsRepointed());
    }

    /**
     * Two listed relationships joined to each other both change, and end up joined as the new
     * objects, whichever is listed first: each write reads the other's ends as they then are.
     */
    @Test
    public void shouldChangeTwoJoinedRelationshipsTogether_whenTheAttachedOneIsListedFirst() {
        changeTwoJoinedRelationships(true);
    }

    @Test
    public void shouldChangeTwoJoinedRelationshipsTogether_whenTheAttachedOneIsListedSecond() {
        changeTwoJoinedRelationships(false);
    }

    private void changeTwoJoinedRelationships(boolean attachedFirst) {
        IArchimateElement x = component("x", "Billing");
        IArchimateElement y = component("y", "Ledger");
        IArchimateElement z = component("z", "Archive");
        IArchimateRelationship oldR = flow("r", x, y);
        IArchimateRelationship oldA = association("a", z, oldR);
        RelationshipTypeChangeRequest changeR = change("r", "TriggeringRelationship");
        RelationshipTypeChangeRequest changeA = change("a", "FlowRelationship");

        ChangeRelationshipTypeResultDto result = attachedFirst ? execute(changeA, changeR) : execute(changeR, changeA);

        IArchimateRelationship newR = relationship("r");
        IArchimateRelationship newA = relationship("a");
        assertNotSame(oldR, newR);
        assertNotSame(oldA, newA);
        assertEquals(P.getTriggeringRelationship(), newR.eClass());
        assertEquals(P.getFlowRelationship(), newA.eClass());
        assertSame("the new attached relationship joins the new relationship", newR, newA.getTarget());
        assertSame(z, newA.getSource());
        assertTrue(newR.getTargetRelationships().contains(newA));
        assertNull(oldR.eContainer());
        assertNull(oldA.eContainer());
        assertTrue(oldR.getTargetRelationships().isEmpty());
        assertEquals(attachedFirst ? List.of("a", "r") : List.of("r", "a"),
                result.changes().stream().map(ChangeRelationshipTypeResultDto.Change::relationshipId).toList());
        assertEquals("r", result.changes().get(attachedFirst ? 0 : 1).targetId());
    }

    /**
     * Every relationship at a Junction listed with one new type changes together, whichever is
     * listed first, and one undo puts every one back: the Junction is judged by the types the call
     * leaves, under a stand-in for Archi's own rule at a Junction.
     */
    @Test
    public void shouldChangeEveryRelationshipAtAJunction_andRestoreItWithOneUndo_whenTheOneOutIsListedFirst() {
        changeAWholeJunction(true);
    }

    @Test
    public void shouldChangeEveryRelationshipAtAJunction_andRestoreItWithOneUndo_whenTheOneOutIsListedLast() {
        changeAWholeJunction(false);
    }

    private void changeAWholeJunction(boolean outFirst) {
        IArchimateElement a = component("a", "Billing");
        IArchimateElement b = component("b", "Ledger");
        IArchimateElement c = dataObject("c", "Invoice");
        IJunction junction = factory.createJunction();
        junction.setId("J");
        model.getFolder(FolderType.OTHER).getElements().add(junction);
        IArchimateRelationship oldA = flow("r-a", a, junction);
        IArchimateRelationship oldB = flow("r-b", b, junction);
        IArchimateRelationship oldOut = flow("r-out", junction, c);
        IDiagramModelArchimateObject boxA = place(viewA, a, "box-a", 0, 0, 120, 55);
        IDiagramModelArchimateObject boxJ = place(viewA, junction, "box-j", 300, 0, 15, 15);
        IDiagramModelArchimateObject boxC = place(viewA, c, "box-c", 600, 0, 120, 55);
        IDiagramModelArchimateConnection drawn = connect(oldA, boxA, boxJ, "conn-a");
        bendpoint(drawn, 1, 2);
        connect(oldOut, boxJ, boxC, "conn-out");
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());
        Map<String, Set<String>> endsBefore = endLists();
        List<String> calls = new ArrayList<>();
        ChangeRelationshipTypeOperations ops = new ChangeRelationshipTypeOperations(sessionId -> model,
                () -> null, () -> { }, rule, ChangeRelationshipTypeOperationsTest.junctionRule(rule, calls));
        List<RelationshipTypeChangeRequest> changes = outFirst
                ? List.of(change("r-out", "TriggeringRelationship"), change("r-a", "TriggeringRelationship"),
                        change("r-b", "TriggeringRelationship"))
                : List.of(change("r-a", "TriggeringRelationship"), change("r-b", "TriggeringRelationship"),
                        change("r-out", "TriggeringRelationship"));
        ChangeRelationshipTypeCommand command = (ChangeRelationshipTypeCommand) ops.prepare(model, changes, null)
                .command();

        command.execute();

        assertNull(command.getSkipReason(), command.getSkipReason());
        for (String id : List.of("r-a", "r-b", "r-out")) {
            assertEquals(id, P.getTriggeringRelationship(), relationship(id).eClass());
        }
        assertEquals(List.of(relationship("r-out")), junction.getSourceRelationships());
        assertEquals(Set.of(relationship("r-a"), relationship("r-b")), Set.copyOf(junction.getTargetRelationships()));
        assertEquals(2, junction.getTargetRelationships().size());
        assertSame(relationship("r-a"), drawn.getArchimateRelationship());
        String seen = " [r-a:TriggeringRelationship, r-b:TriggeringRelationship, r-out:TriggeringRelationship]";
        assertEquals("asked once per listed relationship when prepared and again as it runs, each time seeing "
                + "every relationship at the Junction by its new type",
                List.of(changes.get(0), changes.get(1), changes.get(2), changes.get(0), changes.get(1),
                        changes.get(2)).stream().map(each -> asked(each.relationshipId()) + seen).toList(),
                calls);

        command.undo();

        MergeElementsCommandTest.assertSnapshot("one undo restores the model exactly", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
        assertEquals("every end holds the relationships it held", endsBefore, endLists());
        assertSame(oldA, relationship("r-a"));
        assertSame(oldB, relationship("r-b"));
        assertSame(oldOut, relationship("r-out"));
        assertSame(oldA, drawn.getArchimateRelationship());
    }

    private static String asked(String id) {
        return switch (id) {
            case "r-a" -> "a>J:TriggeringRelationship";
            case "r-b" -> "b>J:TriggeringRelationship";
            default -> "J>c:TriggeringRelationship";
        };
    }

    // ---- undo, redo -----------------------------------------------------------------------------

    @Test
    public void shouldRestoreTheModelExactly_withOneUndo() {
        IArchimateElement x = component("x", "Billing");
        IArchimateElement y = component("y", "Ledger");
        IArchimateElement d = dataObject("d", "Invoice");
        association("r-first", x, y);
        IAccessRelationship old = factory.createAccessRelationship();
        relationship(old, "r", x, d);
        old.setAccessType(IAccessRelationship.READ_WRITE_ACCESS);
        old.setDocumentation("docs");
        property(old, "k", "v");
        old.getFeatures().putString("f", "1");
        IProfile profile = profile("profile-strong", "Strong", "AccessRelationship");
        old.getProfiles().add(profile);
        IArchimateRelationship second = flow("r2", x, y);
        IArchimateRelationship fromIt = association("r-from", old, y);
        IArchimateRelationship toIt = association("r-to", y, old);
        association("r-last", x, y);
        IDiagramModelArchimateObject boxX = place(viewA, x, "box-x", 0, 0, 120, 55);
        IDiagramModelArchimateObject boxD = place(viewA, d, "box-d", 300, 0, 120, 55);
        IDiagramModelArchimateObject boxY = place(viewA, y, "box-y", 600, 0, 120, 55);
        connect(relationship("r-first"), boxX, boxY, "conn-first");
        IDiagramModelArchimateConnection drawn = connect(old, boxX, boxD, "conn-r");
        bendpoint(drawn, 1, 2);
        IDiagramModelArchimateConnection drawnSecond = connect(second, boxX, boxY, "conn-r2");
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());
        Map<String, Set<String>> endsBefore = endLists();
        ChangeRelationshipTypeCommand command = command(change("r", "AssociationRelationship"),
                change("r2", "TriggeringRelationship"));

        command.execute();
        assertNull(command.getSkipReason());
        IArchimateRelationship changed = relationship("r");
        command.undo();

        MergeElementsCommandTest.assertSnapshot("one undo restores the model exactly", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
        // An end's relationship lists are transient and re-built by reconnecting, which appends,
        // as Archi's own undo does; they are compared as sets.
        assertEquals("every end holds the relationships it held", endsBefore, endLists());
        assertSame("the old relationship object is back", old, relationship("r"));
        assertSame(second, relationship("r2"));
        assertSame(old, drawn.getArchimateRelationship());
        assertSame(second, drawnSecond.getArchimateRelationship());
        assertEquals(1, boxX.getSourceConnections().indexOf(drawn));
        assertEquals(0, boxD.getTargetConnections().indexOf(drawn));
        assertSame(old, fromIt.getSource());
        assertSame(old, toIt.getTarget());
        assertEquals(List.of(profile), old.getProfiles());
        assertEquals(IAccessRelationship.READ_WRITE_ACCESS, old.getAccessType());
        assertNull("nothing is left of the new relationship", changed.eContainer());
        assertTrue(changed.getSourceRelationships().isEmpty());
        assertTrue(changed.getTargetRelationships().isEmpty());
        assertTrue(changed.getReferencingDiagramComponents().isEmpty());
    }

    @Test
    public void shouldReplayTheRecordedChange_onRedo() {
        IArchimateElement x = component("x", "Billing");
        IArchimateElement y = component("y", "Ledger");
        IArchimateRelationship old = flow("r", x, y);
        IArchimateRelationship fromIt = association("r-from", old, x);
        IDiagramModelArchimateObject boxX = place(viewA, x, "box-x", 0, 0, 120, 55);
        IDiagramModelArchimateObject boxY = place(viewA, y, "box-y", 300, 0, 120, 55);
        IDiagramModelArchimateConnection drawn = connect(old, boxX, boxY, "conn-r");
        ChangeRelationshipTypeCommand command = command(change("r", "TriggeringRelationship"));
        command.execute();
        IArchimateRelationship changed = relationship("r");
        Map<String, String> after = MergeElementsCommandTest.snapshot(model, Set.of());
        Map<String, Set<String>> endsAfter = endLists();
        command.undo();
        // A redo that read the model again would see this and re-point it; a replay does not.
        IArchimateRelationship lateAttached = association("r-late", x, old);

        command.redo();

        assertSame("redo puts back the same relationship", changed, relationship("r"));
        assertSame(changed, drawn.getArchimateRelationship());
        assertSame(changed, fromIt.getSource());
        assertNull(old.eContainer());
        assertSame("redo replays the record and reads nothing", old, lateAttached.getTarget());
        model.getFolder(FolderType.RELATIONS).getElements().remove(lateAttached);
        lateAttached.disconnect();
        MergeElementsCommandTest.assertSnapshot("redo replays the change", after,
                MergeElementsCommandTest.snapshot(model, Set.of()));
        assertEquals(endsAfter, endLists());
    }

    // ---- drift between preparing and running -----------------------------------------------------

    @Test
    public void shouldWriteNothing_whenARelationshipsTypeChangedAfterPreparing() {
        IArchimateElement x = component("x", "Billing");
        IArchimateElement y = component("y", "Ledger");
        IArchimateRelationship old = flow("r", x, y);
        ChangeRelationshipTypeCommand command = command(change("r", "TriggeringRelationship"));
        // Someone replaced it with an Assignment of the same id meanwhile.
        IFolder relations = model.getFolder(FolderType.RELATIONS);
        relations.getElements().remove(old);
        old.disconnect();
        IArchimateRelationship replaced = relationship(factory.createAssignmentRelationship(), "r", x, y);
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());

        command.execute();

        assertEquals("change-relationship-type wrote nothing: r is now a AssignmentRelationship, not the "
                + "FlowRelationship the call was prepared against", command.getSkipReason());
        assertTrue(command.changedNothing());
        assertFalse(command.leftChangesItCouldNotUndo());
        assertNull(command.getResult());
        assertSame(replaced, relationship("r"));
        MergeElementsCommandTest.assertSnapshot("nothing written", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
    }

    @Test
    public void shouldWriteNothing_whenARelationshipWasRemovedAfterPreparing() {
        IArchimateElement x = component("x", "Billing");
        IArchimateElement y = component("y", "Ledger");
        IArchimateRelationship old = flow("r", x, y);
        flow("r2", x, y);
        ChangeRelationshipTypeCommand command = command(change("r2", "TriggeringRelationship"),
                change("r", "TriggeringRelationship"));
        model.getFolder(FolderType.RELATIONS).getElements().remove(old);
        old.disconnect();
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());

        command.execute();

        assertEquals("change-relationship-type wrote nothing: Relationship not found: r (changes[1])",
                command.getSkipReason());
        assertTrue(command.changedNothing());
        assertEquals("the relationship listed first is not changed either", P.getFlowRelationship(),
                relationship("r2").eClass());
        MergeElementsCommandTest.assertSnapshot("nothing written", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
    }

    /** An end's type changed after preparing so that ArchiMate no longer allows the new type. */
    @Test
    public void shouldWriteNothing_whenAnEndsNewTypeMakesTheChangeInvalidAfterPreparing() {
        IArchimateElement x = component("x", "Billing");
        IArchimateElement y = component("y", "Ledger");
        flow("r", x, y);
        refused.add("ApplicationComponent>ApplicationService:TriggeringRelationship");
        ChangeRelationshipTypeCommand command = command(change("r", "TriggeringRelationship"));
        ChangeElementTypeCommand endChange = (ChangeElementTypeCommand) new ChangeElementTypeOperations(
                sessionId -> model, () -> null, () -> { }, rule, type -> 0)
                .prepare(model, List.of(new ElementTypeChangeRequest("y", "ApplicationService")), null).command();
        endChange.execute();
        assertNull(endChange.getSkipReason());
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());

        command.execute();

        assertTrue(command.getSkipReason(), command.getSkipReason().startsWith("change-relationship-type wrote "
                + "nothing: The new types would leave 1 relationship that ArchiMate does not allow: r ("));
        assertTrue(command.changedNothing());
        MergeElementsCommandTest.assertSnapshot("nothing written", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
    }

    /** What was added after preparing is re-pointed too, and the result counts what was done. */
    @Test
    public void shouldRepointAConnectionAndAnAttachedRelationshipAddedAfterPreparing_andCountThem() {
        IArchimateElement x = component("x", "Billing");
        IArchimateElement y = component("y", "Ledger");
        IArchimateRelationship old = flow("r", x, y);
        connect(old, place(viewA, x, "box-x", 0, 0, 120, 55), place(viewA, y, "box-y", 300, 0, 120, 55), "conn-r");
        ChangeRelationshipTypeOperations operations = operations();
        PreparedMutation<ChangeRelationshipTypeResultDto> prepared = operations.prepare(model,
                List.of(change("r", "TriggeringRelationship")), null);
        assertEquals(1, prepared.entity().changes().get(0).connectionsRepointed());
        assertEquals(0, prepared.entity().changes().get(0).relationshipsRepointed());
        IDiagramModelArchimateConnection late = connect(old, place(viewB, x, "box-x-b", 0, 0, 120, 55),
                place(viewB, y, "box-y-b", 300, 0, 120, 55), "conn-late");
        IArchimateRelationship lateAttached = association("r-late", x, old);
        ChangeRelationshipTypeCommand command = (ChangeRelationshipTypeCommand) prepared.command();

        command.execute();

        IArchimateRelationship now = relationship("r");
        assertSame(now, late.getArchimateRelationship());
        assertSame(now, lateAttached.getTarget());
        assertEquals(2, command.getResult().changes().get(0).connectionsRepointed());
        assertEquals(1, command.getResult().changes().get(0).relationshipsRepointed());
        assertEquals(new ChangeRelationshipTypeResultDto.Totals(1, 2, 1, 0, 0), command.getResult().totals());
    }

    // ---- rollback -------------------------------------------------------------------------------

    /**
     * A failure while reading what draws the relationship, before anything is written, is a
     * decline like any other: nothing changed, and the reason says so.
     */
    @Test
    public void shouldDeclineWithNothingChanged_whenReadingItsConnectionsFails() {
        IArchimateElement x = component("x", "Billing");
        IArchimateElement y = component("y", "Ledger");
        IArchimateRelationship old = flow("r", x, y);
        boolean[] armed = {false};
        IDiagramModelArchimateConnection unreadable =
                new com.archimatetool.model.impl.DiagramModelArchimateConnection() {
            @Override
            public IArchimateRelationship getArchimateRelationship() {
                if (armed[0]) {
                    throw new IllegalStateException("unreadable");
                }
                return super.getArchimateRelationship();
            }
        };
        unreadable.setId("conn-r");
        unreadable.setArchimateRelationship(old);
        unreadable.connect(place(viewA, x, "box-x", 0, 0, 120, 55), place(viewA, y, "box-y", 300, 0, 120, 55));
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());
        ChangeRelationshipTypeCommand command = command(change("r", "TriggeringRelationship"));

        armed[0] = true;
        command.execute();
        armed[0] = false;

        assertNull(command.getResult());
        assertEquals("change-relationship-type stopped and undid its own changes: unreadable",
                command.getSkipReason());
        assertTrue(command.changedNothing());
        assertFalse(command.leftChangesItCouldNotUndo());
        assertSame(old, relationship("r"));
        assertEquals(before, MergeElementsCommandTest.snapshot(model, Set.of()));
    }

    @Test
    public void shouldUndoItsOwnWrites_whenAWriteFailsPartWay() {
        IArchimateElement x = component("x", "Billing");
        IArchimateElement y = component("y", "Ledger");
        IArchimateRelationship old = flow("r", x, y);
        IArchimateRelationship fromIt = association("r-from", old, x);
        IDiagramModelArchimateConnection drawn = connect(old, place(viewA, x, "box-x", 0, 0, 120, 55),
                place(viewA, y, "box-y", 300, 0, 120, 55), "conn-r");
        IFolder relations = model.getFolder(FolderType.RELATIONS);
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());
        Map<String, Set<String>> endsBefore = endLists();
        AdapterImpl failing = new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                if (n.getEventType() == Notification.REMOVE && n.getOldValue() == old) {
                    throw new IllegalStateException();
                }
            }
        };
        relations.eAdapters().add(failing);
        ChangeRelationshipTypeCommand command = command(change("r", "TriggeringRelationship"));

        command.execute();
        relations.eAdapters().remove(failing);

        assertNull(command.getResult());
        assertEquals("change-relationship-type stopped and undid its own changes: IllegalStateException",
                command.getSkipReason());
        assertTrue(command.changedNothing());
        assertFalse(command.leftChangesItCouldNotUndo());
        assertSame(old, relationship("r"));
        assertSame(old, drawn.getArchimateRelationship());
        assertSame(old, fromIt.getSource());
        MergeElementsCommandTest.assertSnapshot("rolled back", before, MergeElementsCommandTest.snapshot(model, Set.of()));
        assertEquals(endsBefore, endLists());
        command.undo();
        MergeElementsCommandTest.assertSnapshot("the stack's undo is then a no-op", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
    }

    /** An Error part-way is not swallowed, but what was written is undone before it goes on. */
    @Test
    public void shouldUndoItsOwnWrites_andRethrow_whenAWriteFailsWithAnError() {
        IArchimateElement x = component("x", "Billing");
        IArchimateElement y = component("y", "Ledger");
        IArchimateRelationship old = flow("r", x, y);
        IArchimateRelationship fromIt = association("r-from", old, x);
        IFolder relations = model.getFolder(FolderType.RELATIONS);
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());
        Error listener = new Error("listener");
        AdapterImpl failing = new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                if (n.getEventType() == Notification.REMOVE && n.getOldValue() == old) {
                    throw listener;
                }
            }
        };
        relations.eAdapters().add(failing);
        ChangeRelationshipTypeCommand command = command(change("r", "TriggeringRelationship"));

        try {
            command.execute();
            throw new IllegalStateException("the Error must reach the caller");
        } catch (Error e) {
            assertSame(listener, e);
        } finally {
            relations.eAdapters().remove(failing);
        }

        assertSame(old, relationship("r"));
        assertSame(old, fromIt.getSource());
        MergeElementsCommandTest.assertSnapshot("rolled back", before, MergeElementsCommandTest.snapshot(model, Set.of()));
    }

    /**
     * A revert that fails does not stop the others, and the reason says the change could not be
     * fully undone instead of claiming it was.
     */
    @Test
    public void shouldRunEveryRevert_whenOneOfThemFails_andSaySo() {
        IArchimateElement x = component("x", "Billing");
        IArchimateElement y = component("y", "Ledger");
        IArchimateRelationship old = flow("r", x, y);
        IArchimateRelationship fromIt = association("r-from", old, x);
        IDiagramModelArchimateConnection drawn = connect(old, place(viewA, x, "box-x", 0, 0, 120, 55),
                place(viewA, y, "box-y", 300, 0, 120, 55), "conn-r");
        IFolder relations = model.getFolder(FolderType.RELATIONS);
        AdapterImpl failing = new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                if (n.getOldValue() == old && n.getEventType() == Notification.REMOVE
                        || n.getNewValue() == old && n.getEventType() == Notification.ADD) {
                    throw new IllegalStateException("listener");
                }
            }
        };
        relations.eAdapters().add(failing);
        ChangeRelationshipTypeCommand command = command(change("r", "TriggeringRelationship"));

        command.execute();
        relations.eAdapters().remove(failing);

        assertEquals("change-relationship-type stopped and could not undo all of its own changes: listener; "
                + "undoing then failed with: listener", command.getSkipReason());
        assertFalse(command.changedNothing());
        assertTrue(command.leftChangesItCouldNotUndo());
        assertNull(command.getResult());
        assertSame("the reverts after the failing one still ran", old, drawn.getArchimateRelationship());
        assertSame(old, fromIt.getSource());
        assertEquals("only the old relationship is in its folder", List.of("r", "r-from"), ids(relations));
        assertSame(old, relationship("r"));
    }

    // ---- helpers --------------------------------------------------------------------------------

    private ChangeRelationshipTypeOperations operations() {
        return new ChangeRelationshipTypeOperations(sessionId -> model, () -> null, () -> { }, rule, conceptRule);
    }

    private ChangeRelationshipTypeCommand command(RelationshipTypeChangeRequest... changes) {
        return (ChangeRelationshipTypeCommand) operations().prepare(model, List.of(changes), null).command();
    }

    private ChangeRelationshipTypeResultDto execute(RelationshipTypeChangeRequest... changes) {
        ChangeRelationshipTypeCommand command = command(changes);
        command.execute();
        assertNull(command.getSkipReason(), command.getSkipReason());
        assertNotNull(command.getResult());
        return command.getResult();
    }

    private static int lastAdd(List<String> heard, String list) {
        int last = -1;
        for (int i = 0; i < heard.size(); i++) {
            if (heard.get(i).startsWith(list + " add ") || heard.get(i).startsWith(list + " move ")) {
                last = i;
            }
        }
        return last;
    }

    private Map<String, Set<String>> endLists() {
        return endLists(model);
    }

    /** Every concept's source and target relationships, by id, as sets. */
    static Map<String, Set<String>> endLists(IArchimateModel model) {
        Map<String, Set<String>> out = new TreeMap<>();
        for (var it = model.eAllContents(); it.hasNext();) {
            EObject each = it.next();
            if (each instanceof IArchimateConcept concept) {
                Set<String> source = new TreeSet<>();
                concept.getSourceRelationships().forEach(r -> source.add(r.getId()));
                Set<String> target = new TreeSet<>();
                concept.getTargetRelationships().forEach(r -> target.add(r.getId()));
                out.put(concept.getId() + " as source", source);
                out.put(concept.getId() + " as target", target);
            }
        }
        return out;
    }

    static RelationshipTypeChangeRequest change(String id, String type) {
        return new RelationshipTypeChangeRequest(id, type);
    }

    private IArchimateRelationship relationship(String id) {
        return (IArchimateRelationship) ArchimateModelUtils.getObjectByID(model, id);
    }

    private IArchimateDiagramModel view(String id) {
        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId(id);
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        return view;
    }

    private IProfile profile(String id, String name, String conceptType) {
        IProfile profile = factory.createProfile();
        profile.setId(id);
        profile.setName(name);
        profile.setConceptType(conceptType);
        model.getProfiles().add(profile);
        return profile;
    }

    private IArchimateElement component(String id, String name) {
        IArchimateElement element = factory.createApplicationComponent();
        element.setId(id);
        element.setName(name);
        model.getFolder(FolderType.APPLICATION).getElements().add(element);
        return element;
    }

    private IArchimateElement dataObject(String id, String name) {
        IArchimateElement element = factory.createDataObject();
        element.setId(id);
        element.setName(name);
        model.getFolder(FolderType.APPLICATION).getElements().add(element);
        return element;
    }

    private IArchimateRelationship association(String id, IArchimateConcept source, IArchimateConcept target) {
        return relationship(factory.createAssociationRelationship(), id, source, target);
    }

    private IArchimateRelationship flow(String id, IArchimateConcept source, IArchimateConcept target) {
        return relationship(factory.createFlowRelationship(), id, source, target);
    }

    private IArchimateRelationship relationship(IArchimateRelationship relationship, String id,
            IArchimateConcept source, IArchimateConcept target) {
        relationship.setId(id);
        relationship.connect(source, target);
        model.getFolder(FolderType.RELATIONS).getElements().add(relationship);
        return relationship;
    }

    private void property(com.archimatetool.model.IProperties owner, String key, String value) {
        IProperty property = factory.createProperty();
        property.setKey(key);
        property.setValue(value);
        owner.getProperties().add(property);
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
        IDiagramModelArchimateConnection connection = factory.createDiagramModelArchimateConnection();
        connection.setId(id);
        connection.setArchimateRelationship(relationship);
        connection.connect(source, target);
        return connection;
    }

    private void bendpoint(IDiagramModelArchimateConnection connection, int x, int y) {
        IDiagramModelBendpoint bendpoint = factory.createDiagramModelBendpoint();
        bendpoint.setStartX(x);
        bendpoint.setStartY(y);
        bendpoint.setEndX(-x);
        bendpoint.setEndY(-y);
        connection.getBendpoints().add(bendpoint);
    }

    private static List<String> pairs(IArchimateRelationship relationship) {
        return relationship.getProperties().stream().map(p -> p.getKey() + "=" + p.getValue()).toList();
    }

    private static List<String> ids(IFolder folder) {
        return folder.getElements().stream().map(e -> ((IIdentifier) e).getId()).toList();
    }
}
