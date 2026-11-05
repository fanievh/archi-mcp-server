package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.common.notify.impl.AdapterImpl;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimatePackage;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.IProfile;
import com.archimatetool.model.IProperty;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.response.dto.ChangeElementTypeResultDto;
import net.vheerden.archi.mcp.response.dto.ChangeElementTypeResultDto.Change;
import net.vheerden.archi.mcp.response.dto.ElementTypeChangeRequest;

/**
 * The type change at the model: what the new element keeps, what is re-pointed to it and left
 * otherwise untouched, where it is filed, how it is undone and redone, and what the command does
 * when the model has changed between preparing the call and running it. Archi's relationship rule
 * cannot load in a plain JVM, so a stand-in that refuses named type triples is passed instead,
 * and every question it is asked is recorded.
 */
public class ChangeElementTypeCommandTest {

    private static final IArchimatePackage P = IArchimatePackage.eINSTANCE;

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private IArchimateModel model;
    private IArchimateDiagramModel viewA;
    private IArchimateDiagramModel viewB;

    /** "Source>Target:Relationship" triples the stand-in rule refuses. */
    private final Set<String> refused = new HashSet<>();
    private final List<String> asked = new ArrayList<>();
    private final ChangeElementTypeOperations.TypeRule rule = (source, target, relationship) -> {
        String triple = source.getName() + ">" + target.getName() + ":" + relationship.getName();
        asked.add(triple);
        return !refused.contains(triple);
    };
    private final Map<String, Integer> figures = new HashMap<>();

    @Before
    public void setUp() {
        model = factory.createArchimateModel();
        model.setId("model-change-type");
        model.setDefaults();
        viewA = view("view-a");
        viewB = view("view-b");
    }

    // ---- the new element ------------------------------------------------------------------------

    @Test
    public void shouldKeepTheIdAndRemoveTheOldElement_whenChangingTheType() {
        IArchimateElement old = actor("a", "Customer");

        ChangeElementTypeResultDto result = execute(change("a", "BusinessRole"));

        IArchimateElement now = element("a");
        assertNotSame(old, now);
        assertEquals(P.getBusinessRole(), now.eClass());
        assertEquals("a", now.getId());
        assertNull("the old element is in no folder", old.eContainer());
        assertTrue(old.getSourceRelationships().isEmpty());
        assertTrue(old.getTargetRelationships().isEmpty());
        assertTrue(old.getReferencingDiagramComponents().isEmpty());
        assertEquals(Map.of("a", "a"), result.idMap());
        Change c = result.changes().get(0);
        assertEquals("a", c.elementId());
        assertEquals("a", c.resultingId());
        assertEquals("Customer", c.name());
        assertEquals("BusinessActor", c.fromType());
        assertEquals("BusinessRole", c.toType());
    }

    @Test
    public void shouldCopyNameDocumentationPropertiesAndFeatures_asCopiesTheOldElementKeeps() {
        IArchimateElement old = actor("a", "Customer");
        old.setDocumentation("line one\r\nline two  ");
        property(old, "k", "1");
        property(old, "k", "2");
        property(old, "other", null);
        old.getFeatures().putString("f1", "v1");

        execute(change("a", "BusinessRole"));

        IArchimateElement now = element("a");
        assertEquals("Customer", now.getName());
        assertEquals("line one\r\nline two  ", now.getDocumentation());
        assertEquals(List.of("k=1", "k=2", "other=null"), pairs(now));
        assertEquals("v1", now.getFeatures().getString("f1", null));
        for (int i = 0; i < 3; i++) {
            assertNotSame("a property is copied, not moved", old.getProperties().get(i), now.getProperties().get(i));
        }
        now.getProperties().get(0).setValue("changed");
        assertEquals("the old element keeps its own properties for undo", List.of("k=1", "k=2", "other=null"),
                pairs(old));
        assertEquals(1, old.getFeatures().size());
    }

    @Test
    public void shouldDropAndReportTheSpecialization_andLeaveItInTheModel() {
        IArchimateElement old = actor("a", "Customer");
        IProfile profile = factory.createProfile();
        profile.setId("profile-vip");
        profile.setName("VIP");
        profile.setConceptType("BusinessActor");
        model.getProfiles().add(profile);
        old.getProfiles().add(profile);

        ChangeElementTypeResultDto result = execute(change("a", "BusinessRole"));

        assertTrue(element("a").getProfiles().isEmpty());
        assertTrue("the specialization stays in the model", model.getProfiles().contains(profile));
        assertEquals(new ChangeElementTypeResultDto.SpecializationDropped("VIP", "BusinessActor"),
                result.changes().get(0).specializationDropped());
        assertEquals(1, result.totals().specializationsDropped());
    }

    @Test
    public void shouldOmitTheSpecializationAndFigureFields_whenThereIsNothingToReport() {
        actor("a", "Customer");
        Change c = execute(change("a", "BusinessRole")).changes().get(0);
        assertNull(c.specializationDropped());
        assertNull(c.figureTypesReset());
    }

    // ---- what is re-pointed ---------------------------------------------------------------------

    /**
     * Everything that referred to the old element now refers to the new one, and nothing else in
     * the model differs: the snapshot keys objects by id, and the element keeps its id, so the only
     * lines that may change are the element's own (its class, its specialization) and the figure
     * of its boxes.
     */
    @Test
    public void shouldKeepEveryRelationshipBoxAndConnection_andChangeOnlyTheTypeAndFigure() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement x = component("x", "Billing");
        IArchimateRelationship out = association("r-out", a, x);
        out.setName("uses");
        out.setDocumentation("rel docs");
        property(out, "weight", "3");
        IArchimateRelationship in = association("r-in", x, a);
        IArchimateRelationship self = association("r-self", a, a);
        IArchimateRelationship onRelationship = association("r-on-rel", a, in);
        IArchimateRelationship untouched = association("r-other", x, x);

        IDiagramModelArchimateObject group = place(viewA, x, "box-x-outer", 10, 10, 400, 300);
        IDiagramModelArchimateObject boxA = place(group, a, "box-a", 20, 30, 120, 55);
        IDiagramModelArchimateObject child = place(boxA, x, "box-x-in-a", 5, 5, 60, 30);
        boxA.setFillColor("#ff0000");
        boxA.setAlpha(120);
        boxA.setTextPosition(2);
        boxA.setType(1);
        boxA.getFeatures().putString("labelExpression", "${name}!");
        IDiagramModelArchimateObject boxX = place(viewA, x, "box-x", 600, 30, 120, 55);
        IDiagramModelArchimateConnection drawn = connect(out, boxA, boxX, "conn-out");
        bendpoint(drawn, 10, 20);
        drawn.getFeatures().putString("labelOffset", "5,6");
        IDiagramModelArchimateObject boxA2 = place(viewB, a, "box-a-b", 300, 0, 120, 55);
        connect(self, boxA2, boxA2, "conn-self");

        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());

        ChangeElementTypeResultDto result = execute(change("a", "BusinessRole"));

        IArchimateElement now = element("a");
        Map<String, String> expected = new TreeMap<>(before);
        expected.replace("#a", before.get("#a").replaceFirst("^BusinessActor ", "BusinessRole "));
        expected.replace("#box-a", before.get("#box-a").replace(" type=1 ", " type=0 "));
        MergeElementsCommandTest.assertSnapshot("only the element's class and the changed box's figure may differ",
                expected, MergeElementsCommandTest.snapshot(model, Set.of()));

        assertSame(now, out.getSource());
        assertSame(now, in.getTarget());
        assertSame(now, self.getSource());
        assertSame(now, self.getTarget());
        assertSame(now, onRelationship.getSource());
        assertSame(in, onRelationship.getTarget());
        assertSame(x, untouched.getSource());
        assertSame(now, boxA.getArchimateElement());
        assertSame(now, boxA2.getArchimateElement());
        assertSame(boxA, child.eContainer());
        assertSame(boxA, drawn.getSource());

        Change c = result.changes().get(0);
        assertEquals(4, c.relationshipsRepointed());
        assertEquals(2, c.diagramObjectsRepointed());
        assertEquals(List.of(new ChangeElementTypeResultDto.FigureTypeReset("box-a", "view-a", 1, 0)),
                c.figureTypesReset());
        assertEquals(new ChangeElementTypeResultDto.Totals(1, 4, 2, 1, 0, 0, 0), result.totals());
    }

    @Test
    public void shouldGiveEveryBoxTheNewTypesDefaultFigure_fromTheFigureSource() {
        IArchimateElement a = actor("a", "Customer");
        IDiagramModelArchimateObject alternate = place(viewA, a, "box-1", 0, 0, 120, 55);
        alternate.setType(1);
        IDiagramModelArchimateObject plain = place(viewB, a, "box-2", 0, 0, 120, 55);
        figures.put("BusinessRole", 1);

        ChangeElementTypeResultDto result = execute(change("a", "BusinessRole"));

        assertEquals(1, alternate.getType());
        assertEquals(1, plain.getType());
        assertEquals("only the box whose figure changed is reported",
                List.of(new ChangeElementTypeResultDto.FigureTypeReset("box-2", "view-b", 0, 1)),
                result.changes().get(0).figureTypesReset());
    }

    /**
     * Giving a box another element notifies nothing, so an open diagram would keep drawing the old
     * element. Taking the box out of its parent and putting it back is what the editor hears, at
     * the index it had. Undo announces itself the same way.
     */
    @Test
    public void shouldAnnounceEachBoxToItsParent_atTheIndexItHad_onChangeAndOnUndo() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement x = component("x", "Other");
        place(viewA, x, "box-x1", 0, 0, 120, 55);
        IDiagramModelArchimateObject box = place(viewA, a, "box-a", 200, 0, 120, 55);
        place(viewA, x, "box-x2", 400, 0, 120, 55);
        List<String> heard = new ArrayList<>();
        viewA.eAdapters().add(new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                if (n.getFeature() == P.getDiagramModelContainer_Children()) {
                    Object subject = n.getEventType() == Notification.ADD ? n.getNewValue() : n.getOldValue();
                    heard.add((n.getEventType() == Notification.ADD ? "add " : "remove ")
                            + ((IIdentifier) subject).getId() + " @" + n.getPosition());
                }
            }
        });
        ChangeElementTypeCommand command = command(change("a", "BusinessRole"));

        command.execute();

        assertEquals(List.of("remove box-a @1", "add box-a @1"), heard);
        heard.clear();
        command.undo();
        assertSame(a, box.getArchimateElement());
        assertEquals(List.of("remove box-a @1", "add box-a @1"), heard);
    }

    @Test
    public void shouldChangeTwoRelatedElementsTogether_judgingTheirRelationshipByBothNewTypes() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement b = actor("b", "Clerk");
        IArchimateRelationship between = assignment("r-ab", a, b);
        refused.add("BusinessActor>BusinessRole:AssignmentRelationship");
        refused.add("BusinessRole>BusinessActor:AssignmentRelationship");

        ChangeElementTypeResultDto result = execute(change("a", "BusinessRole"), change("b", "BusinessRole"));

        assertEquals("the rule is asked once, about the types both ends will have",
                List.of("BusinessRole>BusinessRole:AssignmentRelationship"), asked);
        assertSame(element("a"), between.getSource());
        assertSame(element("b"), between.getTarget());
        assertEquals(P.getBusinessRole(), element("a").eClass());
        assertEquals(P.getBusinessRole(), element("b").eClass());
        assertEquals("a relationship joining two changed elements is counted once", 1,
                result.totals().relationshipsRepointed());
        assertEquals(1, result.changes().get(0).relationshipsRepointed());
        assertEquals(1, result.changes().get(1).relationshipsRepointed());
        assertNull(a.eContainer());
        assertNull(b.eContainer());
    }

    // ---- the folder -----------------------------------------------------------------------------

    @Test
    public void shouldKeepTheFolderAndTheIndex_whenTheFolderIsTheNewTypesDefault() {
        actor("before", "B");
        actor("a", "Customer");
        actor("after", "C");
        IFolder business = model.getFolder(FolderType.BUSINESS);

        Change c = execute(change("a", "BusinessRole")).changes().get(0);

        assertEquals(List.of("before", "a", "after"), ids(business));
        assertEquals(business.getId(), c.folderId());
        assertFalse(c.folderChanged());
    }

    @Test
    public void shouldKeepASubfolderOfTheNewTypesDefault_atTheIndex() {
        IFolder people = subfolder(model.getFolder(FolderType.BUSINESS), "people");
        actor(people, "p0", "First");
        actor(people, "a", "Customer");
        actor(people, "p2", "Last");

        Change c = execute(change("a", "BusinessRole")).changes().get(0);

        assertEquals(List.of("p0", "a", "p2"), ids(people));
        assertEquals("people", c.folderId());
        assertFalse(c.folderChanged());
    }

    @Test
    public void shouldMoveToTheEndOfTheNewTypesDefaultFolder_whenTheOldFolderIsInAnotherLayer() {
        IFolder people = subfolder(model.getFolder(FolderType.BUSINESS), "people");
        actor(people, "a", "Customer");
        IFolder application = model.getFolder(FolderType.APPLICATION);
        component("c0", "Existing");

        ChangeElementTypeResultDto result = execute(change("a", "ApplicationComponent"));

        assertEquals(List.of("c0", "a"), ids(application));
        assertEquals(List.of(), ids(people));
        Change c = result.changes().get(0);
        assertEquals(application.getId(), c.folderId());
        assertTrue(c.folderChanged());
        assertEquals(1, result.totals().foldersChanged());
    }

    // ---- undo and redo --------------------------------------------------------------------------

    @Test
    public void shouldRestoreTheModelExactly_withOneUndo() {
        IArchimateElement a = actor("a", "Customer");
        actor("after", "C");
        IArchimateElement x = component("x", "Billing");
        IArchimateRelationship out = association("r-out", a, x);
        IArchimateRelationship self = association("r-self", a, a);
        IProfile profile = factory.createProfile();
        profile.setId("profile-vip");
        profile.setName("VIP");
        profile.setConceptType("BusinessActor");
        model.getProfiles().add(profile);
        a.getProfiles().add(profile);
        property(a, "k", "v");
        IDiagramModelArchimateObject box = place(viewA, a, "box-a", 0, 0, 120, 55);
        box.setType(1);
        IDiagramModelArchimateObject boxX = place(viewA, x, "box-x", 300, 0, 120, 55);
        connect(out, box, boxX, "conn-out");
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());
        ChangeElementTypeCommand command = command(change("a", "ApplicationComponent"));

        command.execute();
        IArchimateElement created = element("a");
        command.undo();

        MergeElementsCommandTest.assertSnapshot("one undo restores the model exactly", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
        assertSame("the old element object is back", a, element("a"));
        assertSame(a, out.getSource());
        assertSame(a, self.getSource());
        assertSame(a, self.getTarget());
        assertSame(a, box.getArchimateElement());
        assertEquals(1, box.getType());
        assertEquals(0, model.getFolder(FolderType.BUSINESS).getElements().indexOf(a));
        assertNull("nothing is left of the new element", created.eContainer());
        assertTrue(created.getSourceRelationships().isEmpty());
        assertTrue(created.getTargetRelationships().isEmpty());
        assertTrue(created.getReferencingDiagramComponents().isEmpty());
    }

    @Test
    public void shouldPutTheOldElementBackAtItsPlace_withOneUndo_whenItStaysInItsFolder() {
        IArchimateElement before = actor("before", "A");
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement after = actor("after", "C");
        ChangeElementTypeCommand command = command(change("a", "BusinessRole"));

        command.execute();
        command.undo();

        assertEquals(List.of(before, a, after), model.getFolder(FolderType.BUSINESS).getElements());
    }

    @Test
    public void shouldReplayTheRecordedChange_onRedo() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement x = component("x", "Billing");
        IArchimateRelationship out = association("r-out", a, x);
        IDiagramModelArchimateObject box = place(viewA, a, "box-a", 0, 0, 120, 55);
        ChangeElementTypeCommand command = command(change("a", "BusinessRole"));
        command.execute();
        IArchimateElement created = element("a");
        Map<String, String> changed = MergeElementsCommandTest.snapshot(model, Set.of());
        command.undo();
        // A relationship added after the undo is not part of what the command recorded.
        IArchimateRelationship late = association("r-late", x, a);

        command.redo();

        assertSame("redo puts back the same new element", created, element("a"));
        assertSame(created, out.getSource());
        assertSame(created, box.getArchimateElement());
        assertSame("redo does not read the model again", a, late.getTarget());
        Map<String, String> now = MergeElementsCommandTest.snapshot(model, Set.of());
        Map<String, String> expected = new TreeMap<>(changed);
        expected.put("#r-late", now.get("#r-late"));
        MergeElementsCommandTest.assertSnapshot("redo leaves the model as the first execute did", expected, now);
    }

    // ---- decided when it runs -------------------------------------------------------------------

    @Test
    public void shouldRepointARelationshipAddedAfterPreparing_whenItIsValid() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement x = component("x", "Billing");
        ChangeElementTypeCommand command = command(change("a", "BusinessRole"));
        IArchimateRelationship late = association("r-late", a, x);

        command.execute();

        assertNull(command.getSkipReason());
        assertSame(element("a"), late.getSource());
        assertEquals(1, command.getResult().changes().get(0).relationshipsRepointed());
    }

    @Test
    public void shouldWriteNothing_whenARelationshipAddedAfterPreparingWouldBeInvalid() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement x = component("x", "Billing");
        ChangeElementTypeCommand command = command(change("a", "BusinessRole"));
        assignment("r-late", a, x);
        refused.add("BusinessRole>ApplicationComponent:AssignmentRelationship");
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());

        command.execute();

        MergeElementsCommandTest.assertSnapshot("nothing written", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
        assertSame(a, element("a"));
        assertNull(command.getResult());
        assertTrue(command.getSkipReason(), command.getSkipReason().startsWith("change-element-type wrote nothing: ")
                && command.getSkipReason().contains("r-late (AssignmentRelationship from a as BusinessRole"));
    }

    @Test
    public void shouldWriteNothing_whenAnElementsTypeChangedAfterPreparing() {
        actor("a", "Customer");
        ChangeElementTypeCommand first = command(change("a", "BusinessRole"));
        command(change("a", "BusinessCollaboration")).execute();
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());

        first.execute();

        MergeElementsCommandTest.assertSnapshot("nothing written", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
        assertEquals("change-element-type wrote nothing: a is now a BusinessCollaboration, not the BusinessActor "
                + "the call was prepared against", first.getSkipReason());
    }

    @Test
    public void shouldWriteNothing_whenAnElementWasRemovedAfterPreparing() {
        IArchimateElement a = actor("a", "Customer");
        ChangeElementTypeCommand command = command(change("a", "BusinessRole"));
        model.getFolder(FolderType.BUSINESS).getElements().remove(a);

        command.execute();

        assertNull(command.getResult());
        assertTrue(command.getSkipReason(), command.getSkipReason().contains("Element not found: a"));
    }

    /**
     * The command vouches that it changed nothing only after a decline that wrote nothing, and a
     * later run that writes clears it.
     */
    @Test
    public void shouldSayItChangedNothing_onlyAfterADeclineThatWroteNothing() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement x = component("x", "Billing");
        ChangeElementTypeCommand command = command(change("a", "BusinessRole"));
        assertFalse("not before it has run", command.changedNothing());
        IArchimateRelationship late = assignment("r-late", a, x);
        refused.add("BusinessRole>ApplicationComponent:AssignmentRelationship");

        command.execute();

        assertNotNull(command.getSkipReason());
        assertTrue("an invalid relationship refused before any write", command.changedNothing());
        late.disconnect();
        model.getFolder(FolderType.RELATIONS).getElements().remove(late);

        command.execute();

        assertNull(command.getSkipReason());
        assertFalse("a run that wrote clears it", command.changedNothing());

        ChangeElementTypeCommand retyped = command(change("x", "Node"));
        command(change("x", "Device")).execute();
        retyped.execute();
        assertTrue(retyped.getSkipReason(), retyped.getSkipReason().contains("is now a Device"));
        assertTrue("a type changed since preparing", retyped.changedNothing());
    }

    /**
     * A write that fails part-way is undone inside the command, so the stack entry that follows
     * changes nothing; the reason it gives never prints {@code null}.
     */
    @Test
    public void shouldUndoItsOwnWrites_whenAWriteFailsPartWay() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement x = component("x", "Billing");
        IArchimateRelationship out = association("r-out", a, x);
        place(viewA, a, "box-a", 0, 0, 120, 55);
        IFolder business = model.getFolder(FolderType.BUSINESS);
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());
        AdapterImpl failing = new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                if (n.getEventType() == Notification.REMOVE && n.getOldValue() == a) {
                    throw new IllegalStateException();
                }
            }
        };
        business.eAdapters().add(failing);
        ChangeElementTypeCommand command = command(change("a", "BusinessRole"));

        command.execute();
        business.eAdapters().remove(failing);

        assertNull(command.getResult());
        assertEquals("change-element-type stopped and undid its own changes: IllegalStateException",
                command.getSkipReason());
        assertSame(a, element("a"));
        assertSame(a, out.getSource());
        MergeElementsCommandTest.assertSnapshot("rolled back", before, MergeElementsCommandTest.snapshot(model, Set.of()));
        command.undo();
        MergeElementsCommandTest.assertSnapshot("the stack's undo is then a no-op", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
    }

    /** Undoing its own writes in full counts as changing nothing; a partial undo does not. */
    @Test
    public void shouldSayItChangedNothing_whenItUndidItsOwnWrites_butNotWhenItCouldNotUndoAll() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement x = component("x", "Billing");
        association("r-out", a, x);
        place(viewA, a, "box-a", 0, 0, 120, 55);
        IFolder business = model.getFolder(FolderType.BUSINESS);
        AdapterImpl onRemove = new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                if (n.getEventType() == Notification.REMOVE && n.getOldValue() == a) {
                    throw new IllegalStateException();
                }
            }
        };
        business.eAdapters().add(onRemove);
        ChangeElementTypeCommand undone = command(change("a", "BusinessRole"));
        undone.execute();
        business.eAdapters().remove(onRemove);

        assertTrue(undone.getSkipReason(), undone.getSkipReason().startsWith(
                "change-element-type stopped and undid its own changes"));
        assertTrue(undone.changedNothing());

        AdapterImpl onRemoveAndReAdd = new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                if (n.getOldValue() == a && n.getEventType() == Notification.REMOVE
                        || n.getNewValue() == a && n.getEventType() == Notification.ADD) {
                    throw new IllegalStateException("listener");
                }
            }
        };
        business.eAdapters().add(onRemoveAndReAdd);
        ChangeElementTypeCommand partial = command(change("a", "BusinessRole"));
        partial.execute();
        business.eAdapters().remove(onRemoveAndReAdd);

        assertTrue(partial.getSkipReason(), partial.getSkipReason().startsWith(
                "change-element-type stopped and could not undo all"));
        assertFalse(partial.changedNothing());
    }

    /**
     * Only a partial undo leaves changes it could not undo: a full undo, a decline before any write
     * and a run that wrote do not, and each run starts from a clean answer.
     */
    @Test
    public void shouldSayItLeftChanges_onlyWhenItCouldNotUndoAllOfItsOwnWrites() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement x = component("x", "Billing");
        association("r-out", a, x);
        place(viewA, a, "box-a", 0, 0, 120, 55);
        IFolder business = model.getFolder(FolderType.BUSINESS);
        ChangeElementTypeCommand partial = command(change("a", "BusinessRole"));
        assertFalse("not before it has run", partial.leftChangesItCouldNotUndo());
        AdapterImpl onRemoveAndReAdd = new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                if (n.getOldValue() == a && n.getEventType() == Notification.REMOVE
                        || n.getNewValue() == a && n.getEventType() == Notification.ADD) {
                    throw new IllegalStateException("listener");
                }
            }
        };
        business.eAdapters().add(onRemoveAndReAdd);
        partial.execute();
        business.eAdapters().remove(onRemoveAndReAdd);
        assertTrue(partial.getSkipReason(), partial.getSkipReason().startsWith(
                "change-element-type stopped and could not undo all"));
        assertTrue("a partial undo", partial.leftChangesItCouldNotUndo());

        ChangeElementTypeCommand declined = command(change("a", "BusinessRole"));
        partial.execute();
        assertNull("fixture check: the second run wrote", partial.getSkipReason());
        assertFalse("a later run starts from a clean answer", partial.leftChangesItCouldNotUndo());

        declined.execute();
        assertTrue(declined.getSkipReason(), declined.getSkipReason().startsWith("change-element-type wrote nothing"));
        assertFalse("a decline before any write", declined.leftChangesItCouldNotUndo());

        IArchimateElement b = actor("b", "Clerk");
        AdapterImpl onRemove = new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                if (n.getEventType() == Notification.REMOVE && n.getOldValue() == b) {
                    throw new IllegalStateException();
                }
            }
        };
        business.eAdapters().add(onRemove);
        ChangeElementTypeCommand undone = command(change("b", "BusinessRole"));
        undone.execute();
        business.eAdapters().remove(onRemove);
        assertTrue(undone.getSkipReason(), undone.getSkipReason().startsWith(
                "change-element-type stopped and undid its own changes"));
        assertFalse("a full undo", undone.leftChangesItCouldNotUndo());
    }

    /** An Error part-way is not swallowed, but what was written is undone before it goes on. */
    @Test
    public void shouldUndoItsOwnWrites_andRethrow_whenAWriteFailsWithAnError() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement x = component("x", "Billing");
        IArchimateRelationship out = association("r-out", a, x);
        place(viewA, a, "box-a", 0, 0, 120, 55);
        IFolder business = model.getFolder(FolderType.BUSINESS);
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());
        Error listener = new Error("listener");
        AdapterImpl failing = new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                if (n.getEventType() == Notification.REMOVE && n.getOldValue() == a) {
                    throw listener;
                }
            }
        };
        business.eAdapters().add(failing);
        ChangeElementTypeCommand command = command(change("a", "BusinessRole"));

        try {
            command.execute();
            throw new IllegalStateException("the Error must reach the caller");
        } catch (Error e) {
            assertSame(listener, e);
        } finally {
            business.eAdapters().remove(failing);
        }

        assertSame(a, element("a"));
        assertSame(a, out.getSource());
        MergeElementsCommandTest.assertSnapshot("rolled back", before, MergeElementsCommandTest.snapshot(model, Set.of()));
    }

    /**
     * A revert that fails does not stop the others, and the reason says the change could not be
     * fully undone instead of claiming it was.
     */
    @Test
    public void shouldRunEveryRevert_whenOneOfThemFails_andSaySo() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement x = component("x", "Billing");
        IArchimateRelationship out = association("r-out", a, x);
        IDiagramModelArchimateObject box = place(viewA, a, "box-a", 0, 0, 120, 55);
        IFolder business = model.getFolder(FolderType.BUSINESS);
        AdapterImpl failing = new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                if (n.getOldValue() == a && n.getEventType() == Notification.REMOVE
                        || n.getNewValue() == a && n.getEventType() == Notification.ADD) {
                    throw new IllegalStateException("listener");
                }
            }
        };
        business.eAdapters().add(failing);
        ChangeElementTypeCommand command = command(change("a", "BusinessRole"));

        command.execute();
        business.eAdapters().remove(failing);

        assertEquals("change-element-type stopped and could not undo all of its own changes: listener; "
                + "undoing then failed with: listener", command.getSkipReason());
        assertSame("the reverts after the failing one still ran", a, out.getSource());
        assertSame(a, box.getArchimateElement());
        assertEquals("only the old element is in its folder", List.of(a), business.getElements());
    }

    /**
     * An Error from a revert does not stop the reverts recorded before it, and reaches the caller
     * with the failure that started the rollback suppressed on it.
     */
    @Test
    public void shouldRunEveryRevert_andRethrow_whenARevertFailsWithAnError() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement x = component("x", "Billing");
        IArchimateRelationship out = association("r-out", a, x);
        IDiagramModelArchimateObject box = place(viewA, a, "box-a", 0, 0, 120, 55);
        IFolder business = model.getFolder(FolderType.BUSINESS);
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());
        IllegalStateException write = new IllegalStateException("write");
        Error revert = new Error("revert");
        AdapterImpl failing = new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                if (n.getEventType() == Notification.REMOVE && n.getOldValue() == a) {
                    throw write;
                }
                if (n.getEventType() == Notification.ADD && n.getNewValue() == a) {
                    throw revert;
                }
            }
        };
        business.eAdapters().add(failing);
        ChangeElementTypeCommand command = command(change("a", "BusinessRole"));

        try {
            command.execute();
            throw new IllegalStateException("the Error must reach the caller");
        } catch (Error e) {
            assertSame(revert, e);
            assertEquals(List.of(write), List.of(e.getSuppressed()));
        } finally {
            business.eAdapters().remove(failing);
        }

        assertNull(command.getResult());
        assertSame(a, element("a"));
        assertSame(a, out.getSource());
        assertSame(a, box.getArchimateElement());
        assertEquals("only the old element is in its folder", List.of(a), business.getElements());
        MergeElementsCommandTest.assertSnapshot("every revert ran", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
        command.undo();
        MergeElementsCommandTest.assertSnapshot("the stack's undo is then a no-op", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
    }

    /**
     * A listener that throws one Error instance on a write and again on its revert gets that same
     * instance back, not a failure to suppress an exception on itself.
     */
    @Test
    public void shouldRethrowTheSameError_whenTheWriteAndItsRevertFailWithIt() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement x = component("x", "Billing");
        association("r-out", a, x);
        place(viewA, a, "box-a", 0, 0, 120, 55);
        IFolder business = model.getFolder(FolderType.BUSINESS);
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());
        Error listener = new Error("listener");
        List<Integer> thrown = new ArrayList<>();
        AdapterImpl failing = new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                if (n.getOldValue() == a && n.getEventType() == Notification.REMOVE
                        || n.getNewValue() == a && n.getEventType() == Notification.ADD) {
                    thrown.add(n.getEventType());
                    throw listener;
                }
            }
        };
        business.eAdapters().add(failing);
        ChangeElementTypeCommand command = command(change("a", "BusinessRole"));

        try {
            command.execute();
            throw new IllegalStateException("the Error must reach the caller");
        } catch (Error e) {
            assertSame(listener, e);
            assertEquals(0, e.getSuppressed().length);
        } finally {
            business.eAdapters().remove(failing);
        }

        assertEquals("the write and its revert both threw", List.of(Notification.REMOVE, Notification.ADD), thrown);
        MergeElementsCommandTest.assertSnapshot("every revert ran", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
    }

    /**
     * When one revert fails with an exception and a later one with an Error, the Error reaches the
     * caller, carrying the earlier revert's failure and then the write's.
     */
    @Test
    public void shouldRethrowTheRevertsError_withEarlierFailuresSuppressed_whenARevertFailsWithAnErrorAfterAnother() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement x = component("x", "Billing");
        association("r-out", a, x);
        place(viewA, a, "box-a", 0, 0, 120, 55);
        IFolder business = model.getFolder(FolderType.BUSINESS);
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());
        IllegalStateException write = new IllegalStateException("write");
        IllegalStateException revert = new IllegalStateException("revert");
        Error lastRevert = new Error("last revert");
        AdapterImpl failing = new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                if (n.getEventType() == Notification.REMOVE && n.getOldValue() == a) {
                    throw write;
                }
                if (n.getEventType() == Notification.ADD && n.getNewValue() == a) {
                    throw revert;
                }
                if (n.getEventType() == Notification.REMOVE
                        && n.getOldValue() instanceof IArchimateElement replacement
                        && replacement != a && "a".equals(replacement.getId())) {
                    throw lastRevert;
                }
            }
        };
        business.eAdapters().add(failing);
        ChangeElementTypeCommand command = command(change("a", "BusinessRole"));

        try {
            command.execute();
            throw new IllegalStateException("the Error must reach the caller");
        } catch (Error e) {
            assertSame(lastRevert, e);
            assertEquals(List.of(revert, write), List.of(e.getSuppressed()));
        } finally {
            business.eAdapters().remove(failing);
        }

        assertNull(command.getResult());
        MergeElementsCommandTest.assertSnapshot("every revert ran", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
    }

    /**
     * When one revert fails with an Error and a later one with an exception, the first Error still
     * reaches the caller, carrying the later revert's failure and then the write's.
     */
    @Test
    public void shouldRethrowTheRevertsError_withLaterFailuresSuppressed_whenARevertFailsWithAnExceptionAfterIt() {
        IllegalStateException write = new IllegalStateException("write");
        Error revert = new Error("revert");
        IllegalStateException lastRevert = new IllegalStateException("last revert");

        Error e = changeWhileRevertsFail(List.of("a"), write, revert, lastRevert);

        assertSame(revert, e);
        assertEquals(List.of(lastRevert, write), List.of(e.getSuppressed()));
        assertEquals(0, lastRevert.getSuppressed().length);
        assertEquals(0, write.getSuppressed().length);
    }

    /**
     * When two reverts fail with an Error, the first one to fail reaches the caller, with the second
     * and then the write's exception suppressed on it.
     */
    @Test
    public void shouldRethrowTheFirstRevertsError_withTheSecondSuppressed_whenTwoRevertsFailWithAnError() {
        IllegalStateException write = new IllegalStateException("write");
        Error revert = new Error("revert");
        Error lastRevert = new Error("last revert");

        Error e = changeWhileRevertsFail(List.of("a"), write, revert, lastRevert);

        assertSame(revert, e);
        assertEquals(List.of(lastRevert, write), List.of(e.getSuppressed()));
        assertEquals(0, lastRevert.getSuppressed().length);
        assertEquals(0, write.getSuppressed().length);
    }

    /**
     * A listener that throws the write's exception again from a revert, before a later revert fails
     * with an Error, finds that exception suppressed on the Error once.
     */
    @Test
    public void shouldSuppressTheWritesFailureOnce_whenARevertRethrowsIt_andALaterRevertFailsWithAnError() {
        IllegalStateException write = new IllegalStateException("write");
        Error lastRevert = new Error("last revert");

        Error e = changeWhileRevertsFail(List.of("a"), write, write, lastRevert);

        assertSame(lastRevert, e);
        assertEquals(List.of(write), List.of(e.getSuppressed()));
        assertEquals(0, write.getSuppressed().length);
    }

    /**
     * When the write fails with an Error, a revert with another Error and a later revert with the
     * write's Error again, the write's Error reaches the caller with the revert's suppressed on it,
     * and the revert's Error does not hold the write's in turn.
     */
    @Test
    public void shouldNotSuppressTheThrownErrorOnTheRevertsError_whenALaterRevertRethrowsTheWritesError() {
        Error write = new Error("write");
        Error revert = new Error("revert");

        Error e = changeWhileRevertsFail(List.of("a"), write, revert, write);

        assertSame(write, e);
        assertEquals(List.of(revert), List.of(e.getSuppressed()));
        assertEquals(0, revert.getSuppressed().length);
    }

    /**
     * A revert's exception that a listener throws again from a later revert stays suppressed only
     * where it first landed, on the failure of the revert before it.
     */
    @Test
    public void shouldSuppressARevertsFailureOnce_whenALaterRevertRethrowsIt() {
        IllegalStateException write = new IllegalStateException("write");
        IllegalStateException first = new IllegalStateException("first revert");
        IllegalStateException second = new IllegalStateException("second revert");
        Error error = new Error("error");

        Error e = changeWhileRevertsFail(List.of("a", "b"), write, first, second, error, second);

        assertSame(error, e);
        assertEquals(List.of(first, write), List.of(e.getSuppressed()));
        assertEquals(List.of(second), List.of(first.getSuppressed()));
        assertEquals(0, second.getSuppressed().length);
        assertEquals(0, write.getSuppressed().length);
    }

    /**
     * A revert that fails with the write's own exception, and nothing else, still counts as a failed
     * undo: the change says it could not undo all of its changes.
     */
    @Test
    public void shouldSayItCouldNotUndoAll_whenTheOnlyRevertFailureIsTheWritesExceptionAgain() {
        IArchimateElement a = actor("a", "Customer");
        IArchimateElement x = component("x", "Billing");
        association("r-out", a, x);
        place(viewA, a, "box-a", 0, 0, 120, 55);
        IFolder business = model.getFolder(FolderType.BUSINESS);
        IllegalStateException write = new IllegalStateException("write");
        List<Integer> thrown = new ArrayList<>();
        AdapterImpl failing = new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                if (n.getOldValue() == a && n.getEventType() == Notification.REMOVE
                        || n.getNewValue() == a && n.getEventType() == Notification.ADD) {
                    thrown.add(n.getEventType());
                    throw write;
                }
            }
        };
        business.eAdapters().add(failing);
        ChangeElementTypeCommand command = command(change("a", "BusinessRole"));

        try {
            command.execute();
        } finally {
            business.eAdapters().remove(failing);
        }

        assertEquals("fixture check: the write and its revert both threw",
                List.of(Notification.REMOVE, Notification.ADD), thrown);
        assertEquals("change-element-type stopped and could not undo all of its own changes: write"
                + "; undoing then failed with: write", command.getSkipReason());
        assertTrue(command.leftChangesItCouldNotUndo());
        assertFalse(command.changedNothing());
        assertNull(command.getResult());
        assertEquals(0, write.getSuppressed().length);
    }

    // ---- helpers --------------------------------------------------------------------------------

    /**
     * Changes each of {@code ids}, a Business Actor related to and drawn beside one component, to a
     * Business Role, while a Business-folder listener fails the last removal of an old element with
     * {@code write} and then each rollback notification that adds or removes an element with the next
     * of {@code reverts}: the old element's re-add, then its replacement's removal, latest element
     * first. Checks the listener fired where intended and every revert ran, and returns the Error the
     * caller got.
     */
    private Error changeWhileRevertsFail(List<String> ids, Throwable write, Throwable... reverts) {
        IArchimateElement x = component("x", "Billing");
        List<IArchimateElement> olds = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            IArchimateElement old = actor(ids.get(i), "Customer " + i);
            association("r-" + ids.get(i), old, x);
            place(viewA, old, "box-" + ids.get(i), 0, 70 * i, 120, 55);
            olds.add(old);
        }
        IFolder business = model.getFolder(FolderType.BUSINESS);
        Map<String, String> before = MergeElementsCommandTest.snapshot(model, Set.of());
        List<Object> removedOld = new ArrayList<>();
        List<String> revertsThrown = new ArrayList<>();
        AdapterImpl failing = new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                if (removedOld.size() < ids.size()) {
                    if (n.getEventType() == Notification.REMOVE && olds.contains(n.getOldValue())) {
                        removedOld.add(n.getOldValue());
                        if (removedOld.size() == ids.size()) {
                            throw unchecked(write);
                        }
                    }
                    return;
                }
                Object element = n.getEventType() == Notification.ADD ? n.getNewValue()
                        : n.getEventType() == Notification.REMOVE ? n.getOldValue() : null;
                if (element instanceof IArchimateElement each && revertsThrown.size() < reverts.length) {
                    revertsThrown.add((n.getEventType() == Notification.ADD ? "add " : "remove ")
                            + (olds.contains(each) ? "old " : "new ") + each.getId());
                    throw unchecked(reverts[revertsThrown.size() - 1]);
                }
            }
        };
        business.eAdapters().add(failing);
        ChangeElementTypeCommand command = command(
                ids.stream().map(id -> change(id, "BusinessRole")).toArray(ElementTypeChangeRequest[]::new));

        Error caught;
        try {
            command.execute();
            throw new IllegalStateException("the Error must reach the caller");
        } catch (Error e) {
            caught = e;
        } finally {
            business.eAdapters().remove(failing);
        }

        assertEquals("fixture check: every old element removed", ids.size(), removedOld.size());
        List<String> expected = new ArrayList<>();
        for (int i = removedOld.size() - 1; i >= 0; i--) {
            String id = ((IArchimateElement) removedOld.get(i)).getId();
            expected.add("add old " + id);
            expected.add("remove new " + id);
        }
        assertEquals("fixture check: every listed revert threw, latest first", expected, revertsThrown);
        assertNull(command.getResult());
        MergeElementsCommandTest.assertSnapshot("every revert ran", before,
                MergeElementsCommandTest.snapshot(model, Set.of()));
        return caught;
    }

    /** {@code failure} as something a listener can throw: it is a RuntimeException or an Error. */
    private static RuntimeException unchecked(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        return (RuntimeException) failure;
    }

    private ChangeElementTypeOperations operations() {
        return new ChangeElementTypeOperations(sessionId -> model, () -> null, () -> { }, rule,
                type -> figures.getOrDefault(type.getName(), 0));
    }

    private ChangeElementTypeCommand command(ElementTypeChangeRequest... changes) {
        return (ChangeElementTypeCommand) operations().prepare(model, List.of(changes), null).command();
    }

    private ChangeElementTypeResultDto execute(ElementTypeChangeRequest... changes) {
        asked.clear();
        ChangeElementTypeCommand command = command(changes);
        asked.clear();
        command.execute();
        assertNull(command.getSkipReason(), command.getSkipReason());
        return command.getResult();
    }

    private static ElementTypeChangeRequest change(String id, String type) {
        return new ElementTypeChangeRequest(id, type);
    }

    private IArchimateElement element(String id) {
        return (IArchimateElement) ArchimateModelUtils.getObjectByID(model, id);
    }

    private IArchimateDiagramModel view(String id) {
        IArchimateDiagramModel view = factory.createArchimateDiagramModel();
        view.setId(id);
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        return view;
    }

    private IFolder subfolder(IFolder parent, String id) {
        IFolder folder = factory.createFolder();
        folder.setId(id);
        folder.setName(id);
        parent.getFolders().add(folder);
        return folder;
    }

    private IArchimateElement actor(String id, String name) {
        return actor(model.getFolder(FolderType.BUSINESS), id, name);
    }

    private IArchimateElement actor(IFolder folder, String id, String name) {
        IArchimateElement element = factory.createBusinessActor();
        element.setId(id);
        element.setName(name);
        folder.getElements().add(element);
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

    private IArchimateRelationship assignment(String id, IArchimateConcept source, IArchimateConcept target) {
        return relationship(factory.createAssignmentRelationship(), id, source, target);
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

    private static List<String> pairs(IArchimateElement element) {
        return element.getProperties().stream().map(p -> p.getKey() + "=" + p.getValue()).toList();
    }

    private static List<String> ids(IFolder folder) {
        return folder.getElements().stream().map(e -> ((IIdentifier) e).getId()).toList();
    }
}
