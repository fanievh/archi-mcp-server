package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import com.archimatetool.model.IProfile;
import com.archimatetool.model.IProperty;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.response.dto.ChangeElementTypeResultDto;
import net.vheerden.archi.mcp.response.dto.ChangeElementTypeResultDto.RelationshipConverted;
import net.vheerden.archi.mcp.response.dto.ChangeElementTypeResultDto.SpecializationDropped;
import net.vheerden.archi.mcp.response.dto.ElementTypeChangeRequest;
import net.vheerden.archi.mcp.response.dto.RelationshipSemanticAttributes;

/**
 * A type change that turns the relationships it would break into Associations, at the model:
 * what each Association keeps and drops, what is re-pointed to it, which relationships are
 * converted and which are left alone, the order the two halves of the change run in, how the
 * whole is undone, redone and rolled back, and what the command does when the model has changed
 * between preparing the call and running it.
 *
 * <p>Archi's relationship rule and label provider cannot load in a plain JVM, and its note
 * preference lives in the running platform, so stand-ins for all three are passed.</p>
 */
public class ChangeElementTypeConversionTest {

    private static final IArchimatePackage P = IArchimatePackage.eINSTANCE;
    private static final String CONVERT = "to-association";

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private IArchimateModel model;
    private IArchimateDiagramModel viewA;
    private IArchimateDiagramModel viewB;

    /** "Source>Target:Relationship" triples the stand-in rule refuses. */
    private final Set<String> refused = new HashSet<>();
    private final ChangeElementTypeOperations.TypeRule rule = (source, target, relationship) ->
            !refused.contains(source.getName() + ">" + target.getName() + ":" + relationship.getName());
    private boolean noteOn;
    private int noteReads;

    @Before
    public void setUp() {
        model = factory.createArchimateModel();
        model.setId("model-convert");
        model.setDefaults();
        viewA = view("view-a");
        viewB = view("view-b");
    }

    // ---- the Association ------------------------------------------------------------------------

    @Test
    public void shouldTurnARelationshipTheNewTypeForbidsIntoAnAssociation_keepingItsIdAndPlace() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement x = component("x", "Billing");
        association("r-before", x, x);
        IArchimateRelationship old = serving("r", x, a);
        association("r-after", x, x);
        refused.add("ApplicationComponent>BusinessEvent:ServingRelationship");

        ChangeElementTypeResultDto result = execute(change("a", "BusinessEvent"));

        IArchimateRelationship now = relationship("r");
        assertNotSame(old, now);
        assertEquals(P.getAssociationRelationship(), now.eClass());
        assertFalse("an Association takes its default direction", ((IAssociationRelationship) now).isDirected());
        assertSame(x, now.getSource());
        assertSame(element("a"), now.getTarget());
        assertEquals("it takes the old relationship's place in its folder", List.of("r-before", "r", "r-after"),
                ids(model.getFolder(FolderType.RELATIONS)));
        assertNull("the old relationship is in no folder", old.eContainer());
        assertFalse(x.getSourceRelationships().contains(old));
        assertFalse(element("a").getTargetRelationships().contains(old));
        assertTrue(old.getReferencingDiagramComponents().isEmpty());

        RelationshipConverted converted = result.changes().get(0).relationshipsConverted().get(0);
        assertEquals(new RelationshipConverted("r", "r", "ServingRelationship", "AssociationRelationship", "x", "a",
                0, null, null, null), converted);
        assertEquals(1, result.totals().relationshipsConverted());
        assertEquals("a converted relationship is still counted as re-pointed", 1,
                result.changes().get(0).relationshipsRepointed());
    }

    @Test
    public void shouldCopyNameDocumentationPropertiesAndFeatures_andDropTheSpecializationAndTypeAttributes() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement x = component("x", "Billing");
        IAccessRelationship old = factory.createAccessRelationship();
        relationship(old, "r", a, x);
        old.setAccessType(IAccessRelationship.READ_ACCESS);
        old.setName("reads");
        old.setDocumentation("line one\r\nline two  ");
        property(old, "k", "1");
        property(old, "k", "2");
        property(old, "other", null);
        old.getFeatures().putString("f1", "v1");
        IProfile profile = profile("profile-strong", "Strong", "AccessRelationship");
        old.getProfiles().add(profile);
        refused.add("BusinessEvent>ApplicationComponent:AccessRelationship");

        ChangeElementTypeResultDto result = execute(change("a", "BusinessEvent"));

        IArchimateRelationship now = relationship("r");
        assertEquals("reads", now.getName());
        assertEquals("line one\r\nline two  ", now.getDocumentation());
        assertEquals(List.of("k=1", "k=2", "other=null"), pairs(now));
        assertEquals("v1", now.getFeatures().getString("f1", null));
        for (int i = 0; i < 3; i++) {
            assertNotSame("a property is copied, not moved", old.getProperties().get(i), now.getProperties().get(i));
        }
        assertEquals("the old relationship keeps its own properties for undo", 3, old.getProperties().size());
        assertTrue(now.getProfiles().isEmpty());
        assertTrue("the specialization stays in the model", model.getProfiles().contains(profile));
        RelationshipConverted converted = result.changes().get(0).relationshipsConverted().get(0);
        assertEquals(new RelationshipSemanticAttributes("read", null, null), converted.attributesDropped());
        assertEquals(new SpecializationDropped("Strong", "AccessRelationship"), converted.specializationDropped());
        assertEquals("AccessRelationship", converted.fromType());
    }

    @Test
    public void shouldReportAnInfluenceStrength_onlyWhenItWasSet() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement x = component("x", "Billing");
        IInfluenceRelationship strong = factory.createInfluenceRelationship();
        relationship(strong, "r-strong", x, a);
        strong.setStrength("++");
        IInfluenceRelationship blank = factory.createInfluenceRelationship();
        relationship(blank, "r-blank", x, a);
        refused.add("ApplicationComponent>BusinessEvent:InfluenceRelationship");

        List<RelationshipConverted> converted = execute(change("a", "BusinessEvent")).changes().get(0)
                .relationshipsConverted();

        assertEquals(List.of("r-strong", "r-blank"), converted.stream().map(RelationshipConverted::relationshipId).toList());
        assertEquals(new RelationshipSemanticAttributes(null, null, "++"), converted.get(0).attributesDropped());
        assertNull("nothing was dropped", converted.get(1).attributesDropped());
    }

    // ---- what is re-pointed ---------------------------------------------------------------------

    /**
     * Every connection that drew the old relationship draws the Association, and nothing else
     * about the view differs: the snapshot keys objects by id, and the relationship keeps its id,
     * so only the relationship's own line (its class and attributes) may change.
     */
    @Test
    public void shouldRedrawEveryConnection_keepingItsIdEndsBendpointsStyleAndIndexes() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement x = component("x", "Billing");
        IArchimateRelationship before = association("r-first", x, a);
        IArchimateRelationship old = serving("r", x, a);
        IArchimateRelationship after = association("r-last", x, a);
        IDiagramModelArchimateObject boxX = place(viewA, x, "box-x", 0, 0, 120, 55);
        IDiagramModelArchimateObject boxA = place(viewA, a, "box-a", 300, 0, 120, 55);
        connect(before, boxX, boxA, "conn-first");
        IDiagramModelArchimateConnection drawn = connect(old, boxX, boxA, "conn-r");
        connect(after, boxX, boxA, "conn-last");
        bendpoint(drawn, 10, 20);
        bendpoint(drawn, 30, 40);
        drawn.getFeatures().putString("labelOffset", "5,6");
        drawn.setTextPosition(0);
        drawn.setLineColor("#ff0000");
        drawn.setLineWidth(3);
        IDiagramModelArchimateObject boxX2 = place(viewB, x, "box-x-b", 0, 0, 120, 55);
        IDiagramModelArchimateObject boxA2 = place(viewB, a, "box-a-b", 300, 0, 120, 55);
        IDiagramModelArchimateConnection second = connect(old, boxX2, boxA2, "conn-r-b");
        refused.add("ApplicationComponent>BusinessEvent:ServingRelationship");
        Map<String, String> snapshotBefore = MergeElementsCommandTest.snapshot(model, Set.of());

        ChangeElementTypeResultDto result = execute(change("a", "BusinessEvent"));

        IArchimateRelationship now = relationship("r");
        assertSame(now, drawn.getArchimateRelationship());
        assertSame(now, second.getArchimateRelationship());
        assertEquals("its index in its source's list", 1, boxX.getSourceConnections().indexOf(drawn));
        assertEquals("its index in its target's list", 1, boxA.getTargetConnections().indexOf(drawn));
        assertSame(boxX, drawn.getSource());
        assertSame(boxA, drawn.getTarget());
        Map<String, String> expected = new TreeMap<>(snapshotBefore);
        expected.replace("#a", snapshotBefore.get("#a").replaceFirst("^BusinessActor ", "BusinessEvent "));
        expected.replace("#r", snapshotBefore.get("#r").replaceFirst("^ServingRelationship ", "AssociationRelationship ")
                .replace(" profiles->", " directed=false profiles->"));
        MergeElementsCommandTest.assertSnapshot("only the relationship's class changes", expected,
                MergeElementsCommandTest.snapshot(model, Set.of()));
        assertEquals(2, result.changes().get(0).relationshipsConverted().get(0).connectionsRepointed());
    }

    /**
     * Setting the relationship a connection draws notifies nothing an open diagram listens to; the
     * connection leaving and re-joining both ends' lists is what makes the diagram redraw it, so
     * the redraw is asserted by its notifications, at the index it had, on change and on undo.
     */
    @Test
    public void shouldAnnounceEachConnectionToBothEnds_atTheIndexItHad_onChangeAndOnUndo() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement x = component("x", "Billing");
        IArchimateRelationship first = association("r-first", x, a);
        IArchimateRelationship old = serving("r", x, a);
        IDiagramModelArchimateObject boxX = place(viewA, x, "box-x", 0, 0, 120, 55);
        IDiagramModelArchimateObject boxA = place(viewA, a, "box-a", 300, 0, 120, 55);
        connect(first, boxX, boxA, "conn-first");
        IDiagramModelArchimateConnection drawn = connect(old, boxX, boxA, "conn-r");
        refused.add("ApplicationComponent>BusinessEvent:ServingRelationship");
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
        boxA.eAdapters().add(listener);
        ChangeElementTypeCommand command = command(CONVERT, change("a", "BusinessEvent"));

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
        assertEquals(1, boxA.getTargetConnections().indexOf(drawn));
        assertSame(old, drawn.getArchimateRelationship());
    }

    @Test
    public void shouldRepointEveryRelationshipAttachedToTheConvertedOne_bothWays() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement x = component("x", "Billing");
        IArchimateElement y = component("y", "Ledger");
        IArchimateRelationship old = serving("r", x, a);
        IArchimateRelationship fromIt = association("r-from", old, y);
        fromIt.setName("from it");
        IArchimateRelationship toIt = association("r-to", y, old);
        IDiagramModelArchimateObject boxX = place(viewA, x, "box-x", 0, 0, 120, 55);
        IDiagramModelArchimateObject boxA = place(viewA, a, "box-a", 300, 0, 120, 55);
        IDiagramModelArchimateObject boxY = place(viewA, y, "box-y", 600, 0, 120, 55);
        IDiagramModelArchimateConnection drawn = connect(old, boxX, boxA, "conn-r");
        IDiagramModelArchimateConnection onConnection = factory.createDiagramModelArchimateConnection();
        onConnection.setId("conn-to");
        onConnection.setArchimateRelationship(toIt);
        onConnection.connect(boxY, drawn);
        refused.add("ApplicationComponent>BusinessEvent:ServingRelationship");

        execute(change("a", "BusinessEvent"));

        IArchimateRelationship now = relationship("r");
        assertSame(now, fromIt.getSource());
        assertSame(y, fromIt.getTarget());
        assertSame(now, toIt.getTarget());
        assertEquals("from it", fromIt.getName());
        assertEquals(P.getAssociationRelationship(), fromIt.eClass());
        assertSame("an attached relationship is re-pointed, not replaced", fromIt, relationship("r-from"));
        assertTrue(old.getSourceRelationships().isEmpty());
        assertTrue(old.getTargetRelationships().isEmpty());
        assertSame("a connection on the connection keeps its end", drawn, onConnection.getTarget());
    }

    // ---- which are converted --------------------------------------------------------------------

    @Test
    public void shouldLeaveAValidRelationshipAlone_evenWhenItJoinsAChangedElement() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement x = component("x", "Billing");
        IArchimateRelationship valid = association("r-valid", x, a);
        serving("r", x, a);
        refused.add("ApplicationComponent>BusinessEvent:ServingRelationship");

        ChangeElementTypeResultDto result = execute(change("a", "BusinessEvent"));

        assertSame(valid, relationship("r-valid"));
        assertEquals(List.of("r"), convertedIds(result));
        assertEquals(2, result.changes().get(0).relationshipsRepointed());
    }

    @Test
    public void shouldConvertASelfLoopOnce() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateRelationship loop = factory.createTriggeringRelationship();
        relationship(loop, "r-loop", a, a);
        refused.add("BusinessEvent>BusinessEvent:TriggeringRelationship");

        ChangeElementTypeResultDto result = execute(change("a", "BusinessEvent"));

        IArchimateRelationship now = relationship("r-loop");
        assertEquals(P.getAssociationRelationship(), now.eClass());
        assertSame(element("a"), now.getSource());
        assertSame(element("a"), now.getTarget());
        assertEquals(List.of("r-loop"), convertedIds(result));
        assertEquals(1, element("a").getSourceRelationships().size());
        assertEquals(1, element("a").getTargetRelationships().size());
    }

    /**
     * A relationship between two listed elements is judged by both new types, converted once,
     * listed under the first of the two in the call, and only after both elements have their
     * new type, so that the Association joins the new elements.
     */
    @Test
    public void shouldConvertARelationshipBetweenTwoListedElementsOnce_afterBothHaveTheirNewType() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement b = actor("b", "Clerk");
        IArchimateRelationship old = factory.createAssignmentRelationship();
        relationship(old, "r", a, b);
        // invalid only once both ends have changed
        refused.add("BusinessEvent>BusinessProcess:AssignmentRelationship");

        ChangeElementTypeResultDto result = execute(change("b", "BusinessProcess"), change("a", "BusinessEvent"));

        IArchimateRelationship now = relationship("r");
        assertEquals(P.getAssociationRelationship(), now.eClass());
        assertSame(element("a"), now.getSource());
        assertSame(element("b"), now.getTarget());
        assertNotSame(a, element("a"));
        assertNotSame(b, element("b"));
        assertEquals("listed under the first listed element", List.of("r"), convertedIds(result));
        assertEquals("b", result.changes().get(0).elementId());
        assertNull(result.changes().get(1).relationshipsConverted());
        assertEquals(1, result.totals().relationshipsConverted());
        assertEquals("a", result.changes().get(0).relationshipsConverted().get(0).sourceId());
    }

    @Test
    public void shouldConvertInTheOrderTheFolderHoldsTheRelationships_notTheOrderTheElementsAreListed() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement b = actor("b", "Clerk");
        IArchimateElement x = component("x", "Billing");
        serving("r-first", x, b);
        serving("r-second", x, a);
        refused.add("ApplicationComponent>BusinessEvent:ServingRelationship");

        // a is listed first, so reading element by element would give r-second before r-first
        List<IArchimateRelationship> conversions = ChangeElementTypeOperations.resolve(model,
                List.of(change("a", "BusinessEvent"), change("b", "BusinessEvent")), CONVERT, rule).conversions();

        assertEquals(List.of("r-first", "r-second"), conversions.stream().map(IArchimateRelationship::getId).toList());
    }

    @Test
    public void shouldOmitTheConvertedList_whenNoneIsConverted() {
        IArchimateElement a = actor("a", "Customer");
        association("r", a, a);

        ChangeElementTypeResultDto result = execute(change("a", "BusinessRole"));

        assertNull(result.changes().get(0).relationshipsConverted());
        assertEquals(0, result.totals().relationshipsConverted());
    }

    // ---- the documentation note -----------------------------------------------------------------

    @Test
    public void shouldAddArchisNote_whenThePreferenceIsOn_aboveTheDocumentation() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement x = component("x", "Billing");
        serving("r-doc", x, a).setDocumentation("serves\nthe customer");
        serving("r-blank", x, a);
        refused.add("ApplicationComponent>BusinessEvent:ServingRelationship");
        noteOn = true;

        List<RelationshipConverted> converted = execute(change("a", "BusinessEvent")).changes().get(0)
                .relationshipsConverted();

        assertEquals("(Changed from Serving relation)\n\nserves\nthe customer", relationship("r-doc").getDocumentation());
        assertEquals("(Changed from Serving relation)", relationship("r-blank").getDocumentation());
        assertEquals(Boolean.TRUE, converted.get(0).documentationNoteAdded());
        assertEquals(Boolean.TRUE, converted.get(1).documentationNoteAdded());
    }

    @Test
    public void shouldKeepTheDocumentationByteForByte_whenThePreferenceIsOff() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement x = component("x", "Billing");
        serving("r-doc", x, a).setDocumentation("serves\nthe customer");
        refused.add("ApplicationComponent>BusinessEvent:ServingRelationship");

        RelationshipConverted converted = execute(change("a", "BusinessEvent")).changes().get(0)
                .relationshipsConverted().get(0);

        assertEquals("serves\nthe customer", relationship("r-doc").getDocumentation());
        assertNull(converted.documentationNoteAdded());
    }

    /** The preference is read when the change runs, so an approved card follows it as it is then. */
    @Test
    public void shouldReadThePreferenceWhenItRuns_andOnlyWhenItConverts() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement x = component("x", "Billing");
        serving("r", x, a);
        refused.add("ApplicationComponent>BusinessEvent:ServingRelationship");
        ChangeElementTypeCommand command = command(CONVERT, change("a", "BusinessEvent"));
        assertEquals("preparing does not read it", 0, noteReads);
        noteOn = true;

        command.execute();

        assertEquals("(Changed from Serving relation)", relationship("r").getDocumentation());
        assertEquals(1, noteReads);

        IArchimateElement b = actor("b", "Clerk");
        noteReads = 0;
        command(CONVERT, change("b", "BusinessRole")).execute();
        assertEquals("a change that converts nothing does not read it", 0, noteReads);
        assertEquals(P.getBusinessRole(), element("b").eClass());
        assertNotSame(b, element("b"));
    }

    // ---- drift between preparing and running -----------------------------------------------------

    @Test
    public void shouldWriteNothing_whenARelationshipItWasNotPreparedToConvertBecameInvalid() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement x = component("x", "Billing");
        serving("r1", x, a);
        relationship(factory.createFlowRelationship(), "r2", x, a);
        refused.add("ApplicationComponent>BusinessEvent:ServingRelationship");
        ChangeElementTypeCommand command = command(CONVERT, change("a", "BusinessEvent"));
        // r2 was valid when the call was prepared, and the new type no longer allows it
        refused.add("ApplicationComponent>BusinessEvent:FlowRelationship");
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());

        command.execute();

        assertNull(command.getResult());
        assertEquals("change-element-type wrote nothing: the change would also convert r2 to an Association, "
                + "which the call was not prepared to convert", command.getSkipReason());
        MergeElementsCommandTest.assertSnapshot("nothing written", before, MergeElementsCommandTest.snapshot(model, Set.of()));
        assertSame(a, element("a"));
    }

    @Test
    public void shouldSayItChangedNothing_whenItWouldConvertARelationshipItWasNotPreparedFor() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement x = component("x", "Billing");
        serving("r1", x, a);
        relationship(factory.createFlowRelationship(), "r2", x, a);
        refused.add("ApplicationComponent>BusinessEvent:ServingRelationship");
        ChangeElementTypeCommand command = command(CONVERT, change("a", "BusinessEvent"));
        refused.add("ApplicationComponent>BusinessEvent:FlowRelationship");

        command.execute();

        assertTrue(command.getSkipReason(), command.getSkipReason().contains("not prepared to convert"));
        assertTrue(command.changedNothing());
    }

    @Test
    public void shouldConvertOnlyWhatIsStillThere_whenAPreparedConversionWasDeleted() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement x = component("x", "Billing");
        serving("r1", x, a);
        IArchimateRelationship r2 = serving("r2", x, a);
        refused.add("ApplicationComponent>BusinessEvent:ServingRelationship");
        ChangeElementTypeCommand command = command(CONVERT, change("a", "BusinessEvent"));
        model.getFolder(FolderType.RELATIONS).getElements().remove(r2);
        r2.disconnect();

        command.execute();

        assertNull(command.getSkipReason());
        assertEquals(List.of("r1"), convertedIds(command.getResult()));
        assertEquals(1, command.getResult().totals().relationshipsConverted());
        assertEquals(P.getAssociationRelationship(), relationship("r1").eClass());
    }

    @Test
    public void shouldConvertNothing_whenThePreparedConversionBecameValid_butStillChangeTheElement() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement x = component("x", "Billing");
        IArchimateRelationship r1 = serving("r1", x, a);
        refused.add("ApplicationComponent>BusinessEvent:ServingRelationship");
        ChangeElementTypeCommand command = command(CONVERT, change("a", "BusinessEvent"));
        refused.clear();

        command.execute();

        assertNull(command.getSkipReason());
        assertNull(command.getResult().changes().get(0).relationshipsConverted());
        assertEquals(0, command.getResult().totals().relationshipsConverted());
        assertSame("the relationship is kept as it was", r1, relationship("r1"));
        assertEquals(P.getBusinessEvent(), element("a").eClass());
    }

    // ---- undo, redo and rollback -----------------------------------------------------------------

    @Test
    public void shouldRestoreTheModelExactly_withOneUndo() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement b = actor("b", "Clerk");
        IArchimateElement x = component("x", "Billing");
        association("r-first", x, a);
        IAccessRelationship old = factory.createAccessRelationship();
        relationship(old, "r", a, x);
        old.setAccessType(IAccessRelationship.READ_WRITE_ACCESS);
        old.setDocumentation("docs");
        property(old, "k", "v");
        IProfile profile = profile("profile-strong", "Strong", "AccessRelationship");
        old.getProfiles().add(profile);
        IArchimateRelationship loop = factory.createTriggeringRelationship();
        relationship(loop, "r-loop", a, a);
        IArchimateRelationship between = factory.createAssignmentRelationship();
        relationship(between, "r-ab", a, b);
        IArchimateRelationship fromIt = association("r-from", old, x);
        IArchimateRelationship toIt = association("r-to", x, old);
        association("r-last", x, a);
        IDiagramModelArchimateObject boxA = place(viewA, a, "box-a", 0, 0, 120, 55);
        IDiagramModelArchimateObject boxX = place(viewA, x, "box-x", 300, 0, 120, 55);
        connect(relationship("r-first"), boxA, boxX, "conn-first");
        IDiagramModelArchimateConnection drawn = connect(old, boxA, boxX, "conn-r");
        bendpoint(drawn, 1, 2);
        connect(relationship("r-last"), boxX, boxA, "conn-last");
        connect(loop, boxA, boxA, "conn-loop");
        refused.add("BusinessEvent>ApplicationComponent:AccessRelationship");
        refused.add("BusinessEvent>BusinessEvent:TriggeringRelationship");
        refused.add("BusinessEvent>BusinessProcess:AssignmentRelationship");
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());
        Map<String, Set<String>> endsBefore = endLists();
        ChangeElementTypeCommand command = command(CONVERT, change("a", "BusinessEvent"), change("b", "BusinessProcess"));

        command.execute();
        assertNull(command.getSkipReason());
        assertEquals(List.of("r", "r-loop", "r-ab"), convertedIds(command.getResult()));
        IArchimateRelationship converted = relationship("r");
        command.undo();

        MergeElementsCommandTest.assertSnapshot("one undo restores the model exactly", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
        // An end's relationship lists are transient and re-built by reconnecting, which appends,
        // as Archi's own undo does; they are compared as sets.
        assertEquals("every end holds the relationships it held", endsBefore, endLists());
        assertSame("the old relationship object is back", old, relationship("r"));
        assertSame(loop, relationship("r-loop"));
        assertSame(between, relationship("r-ab"));
        assertSame(old, drawn.getArchimateRelationship());
        assertEquals(1, boxA.getSourceConnections().indexOf(drawn));
        assertEquals(1, boxX.getTargetConnections().indexOf(drawn));
        assertSame(old, fromIt.getSource());
        assertSame(old, toIt.getTarget());
        assertSame(a, old.getSource());
        assertEquals(List.of(profile), old.getProfiles());
        assertNull("nothing is left of the Association", converted.eContainer());
        assertTrue(converted.getSourceRelationships().isEmpty());
        assertTrue(converted.getTargetRelationships().isEmpty());
        assertTrue(converted.getReferencingDiagramComponents().isEmpty());
    }

    @Test
    public void shouldReplayTheRecordedConversion_onRedo() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement x = component("x", "Billing");
        IArchimateRelationship old = serving("r", x, a);
        IArchimateRelationship fromIt = association("r-from", old, x);
        IDiagramModelArchimateObject boxX = place(viewA, x, "box-x", 0, 0, 120, 55);
        IDiagramModelArchimateObject boxA = place(viewA, a, "box-a", 300, 0, 120, 55);
        IDiagramModelArchimateConnection drawn = connect(old, boxX, boxA, "conn-r");
        refused.add("ApplicationComponent>BusinessEvent:ServingRelationship");
        ChangeElementTypeCommand command = command(CONVERT, change("a", "BusinessEvent"));
        command.execute();
        IArchimateRelationship converted = relationship("r");
        Map<String, String> changed = MergeElementsCommandTest.snapshot(model, Set.of());
        Map<String, Set<String>> endsChanged = endLists();
        command.undo();

        command.redo();

        assertSame("redo puts back the same Association", converted, relationship("r"));
        assertSame(converted, drawn.getArchimateRelationship());
        assertSame(converted, fromIt.getSource());
        assertNull(old.eContainer());
        MergeElementsCommandTest.assertSnapshot("redo replays the change", changed,
                MergeElementsCommandTest.snapshot(model, Set.of()));
        assertEquals(endsChanged, endLists());
    }

    /**
     * A write that fails while relationships are being converted is undone inside the command
     * with everything written before it, the element changes included.
     */
    @Test
    public void shouldUndoItsOwnWrites_whenAConversionFailsPartWay() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement x = component("x", "Billing");
        IArchimateRelationship old = serving("r", x, a);
        IArchimateRelationship fromIt = association("r-from", old, x);
        IDiagramModelArchimateObject boxX = place(viewA, x, "box-x", 0, 0, 120, 55);
        IDiagramModelArchimateObject boxA = place(viewA, a, "box-a", 300, 0, 120, 55);
        IDiagramModelArchimateConnection drawn = connect(old, boxX, boxA, "conn-r");
        refused.add("ApplicationComponent>BusinessEvent:ServingRelationship");
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
        ChangeElementTypeCommand command = command(CONVERT, change("a", "BusinessEvent"));

        command.execute();
        relations.eAdapters().remove(failing);

        assertNull(command.getResult());
        assertEquals("change-element-type stopped and undid its own changes: IllegalStateException",
                command.getSkipReason());
        assertSame(a, element("a"));
        assertSame(old, relationship("r"));
        assertSame(old, drawn.getArchimateRelationship());
        assertSame(old, fromIt.getSource());
        MergeElementsCommandTest.assertSnapshot("rolled back", before, MergeElementsCommandTest.snapshot(model, Set.of()));
        assertEquals(endsBefore, endLists());
        command.undo();
        MergeElementsCommandTest.assertSnapshot("the stack's undo is then a no-op", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
    }

    // ---- helpers --------------------------------------------------------------------------------

    private ChangeElementTypeOperations operations() {
        return new ChangeElementTypeOperations(sessionId -> model, () -> null, () -> { }, rule, type -> 0,
                () -> {
                    noteReads++;
                    return noteOn;
                }, type -> type.getName().replace("Relationship", " relation"));
    }

    private ChangeElementTypeCommand command(String invalidRelationships, ElementTypeChangeRequest... changes) {
        return (ChangeElementTypeCommand) operations().prepare(model, List.of(changes), invalidRelationships, null, null)
                .command();
    }

    private ChangeElementTypeResultDto execute(ElementTypeChangeRequest... changes) {
        ChangeElementTypeCommand command = command(CONVERT, changes);
        command.execute();
        assertNull(command.getSkipReason(), command.getSkipReason());
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

    private static List<String> convertedIds(ChangeElementTypeResultDto result) {
        List<String> out = new ArrayList<>();
        for (ChangeElementTypeResultDto.Change change : result.changes()) {
            if (change.relationshipsConverted() != null) {
                change.relationshipsConverted().forEach(c -> out.add(c.relationshipId()));
            }
        }
        return out;
    }

    /** Every concept's source and target relationships, by id, as sets. */
    private Map<String, Set<String>> endLists() {
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

    private static ElementTypeChangeRequest change(String id, String type) {
        return new ElementTypeChangeRequest(id, type);
    }

    private IArchimateElement element(String id) {
        return (IArchimateElement) ArchimateModelUtils.getObjectByID(model, id);
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

    private IArchimateElement actor(String id, String name) {
        IArchimateElement element = factory.createBusinessActor();
        element.setId(id);
        element.setName(name);
        model.getFolder(FolderType.BUSINESS).getElements().add(element);
        return element;
    }

    private IArchimateElement component(String id, String name) {
        IArchimateElement element = factory.createApplicationComponent();
        element.setId(id);
        element.setName(name);
        model.getFolder(FolderType.APPLICATION).getElements().add(element);
        return element;
    }

    private IArchimateRelationship association(String id, IArchimateConcept source, IArchimateConcept target) {
        return relationship(factory.createAssociationRelationship(), id, source, target);
    }

    private IArchimateRelationship serving(String id, IArchimateConcept source, IArchimateConcept target) {
        return relationship(factory.createServingRelationship(), id, source, target);
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
