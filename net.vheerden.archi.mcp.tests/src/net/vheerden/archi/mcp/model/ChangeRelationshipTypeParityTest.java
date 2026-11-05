package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.gef.commands.Command;
import org.junit.Test;

import com.archimatetool.editor.model.commands.SetConceptTypeCommandFactory;
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
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.IProfile;
import com.archimatetool.model.IProperty;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.response.dto.RelationshipTypeChangeRequest;

/**
 * The relationship type change against Archi's own. Archi's per-relationship type-change command
 * runs on one model and this tool's command on an identical twin; the two are compared kind by
 * kind for what points at the new relationship, then for what the new relationship carries, its
 * ends, each connection that draws it and each relationship attached to it. Only the declared
 * differences may differ, and each is asserted rather than skipped: this tool keeps the
 * relationship's id where Archi mints a new one, and keeps its place in its folder where Archi
 * appends it.
 *
 * <p>The kinds are the census {@link MergeElementsReferenceKindsTest} counts, every
 * non-containment reference named by its declaring class, and the fixture is checked to hold
 * every reference the metamodel lets point at a relationship.</p>
 *
 * <p>Archi builds its command only after asking its relationship rule, which cannot load in a
 * plain JVM, so the command is built here directly. Its last step asks the rule again about the
 * relationships attached to the new one and changes nothing; it is allowed to fail here, and only
 * with the rule unavailable. Neither side writes a documentation note.</p>
 */
public class ChangeRelationshipTypeParityTest {

    private static final IArchimateFactory F = IArchimateFactory.eINSTANCE;
    private static final IArchimatePackage P = IArchimatePackage.eINSTANCE;

    @Test
    public void shouldChangeAnAccessAsArchiDoes_droppingItsAccessType() throws Exception {
        assertParity(P.getAccessRelationship(), P.getTriggeringRelationship());
    }

    @Test
    public void shouldChangeADirectedAssociationAsArchiDoes_droppingItsDirection() throws Exception {
        assertParity(P.getAssociationRelationship(), P.getServingRelationship());
    }

    @Test
    public void shouldHoldEveryReferenceARelationshipCanBeTheValueOf_inTheFixture() {
        Set<String> possible = new TreeSet<>();
        EClass relationship = P.getAccessRelationship();
        for (EClassifier classifier : P.getEClassifiers()) {
            if (!(classifier instanceof EClass owner) || owner.isAbstract() || owner.isInterface()) {
                continue;
            }
            for (EReference reference : owner.getEAllReferences()) {
                if (MergeElementsReferenceKindsTest.counted(reference)
                        && reference.getEReferenceType().isSuperTypeOf(relationship)) {
                    possible.add(MergeElementsReferenceKindsTest.kind(reference));
                }
            }
        }
        assertEquals("a reference the metamodel lets point at a relationship, that the fixture does not hold, "
                + "is a kind neither side of the comparison would see",
                possible, MergeElementsReferenceKindsTest.census(fixture(P.getAccessRelationship()), "rel").keySet());
    }

    private static void assertParity(EClass from, EClass to) throws Exception {
        IArchimateModel archi = fixture(from);
        IArchimateModel twin = fixture(from);
        IArchimateRelationship archiOld = (IArchimateRelationship) ArchimateModelUtils.getObjectByID(archi, "rel");
        IArchimateRelationship ourOld = (IArchimateRelationship) ArchimateModelUtils.getObjectByID(twin, "rel");
        Map<String, Integer> before = MergeElementsReferenceKindsTest.census(archi, "rel");
        assertTrue("the fixture must hold references to the relationship: " + before, before.size() >= 3);

        IArchimateRelationship archiNew = runArchi(archi, to);
        IArchimateRelationship ours = runOurs(twin, to);

        assertEquals("every kind of reference, as many of each, points at the new relationship on both sides",
                MergeElementsReferenceKindsTest.census(archi, archiNew.getId()),
                MergeElementsReferenceKindsTest.census(twin, ours.getId()));
        assertEquals(before, MergeElementsReferenceKindsTest.census(twin, ours.getId()));

        assertEquals("type", archiNew.eClass(), ours.eClass());
        assertEquals(to, ours.eClass());
        assertEquals("name", archiNew.getName(), ours.getName());
        assertEquals("documentation: no note on either side", archiNew.getDocumentation(), ours.getDocumentation());
        assertEquals("docs\r\nline", ours.getDocumentation());
        assertEquals("properties", pairs(archiNew), pairs(ours));
        assertEquals("features", features(archiNew), features(ours));
        assertEquals("specialization: dropped by both", archiNew.getProfiles(), ours.getProfiles());
        assertTrue(ours.getProfiles().isEmpty());
        assertEquals("attributes: lost by both, the new type's defaults on both sides", attributes(archiNew),
                attributes(ours));
        assertEquals("ends", describe(archiNew.getSource()) + ">" + describe(archiNew.getTarget()),
                describe(ours.getSource()) + ">" + describe(ours.getTarget()));
        for (String id : List.of("from-rel", "to-rel")) {
            assertEquals("relationship attached to it: " + id, ends(archi, id, archiNew), ends(twin, id, ours));
        }
        assertEquals("new>x", ends(twin, "from-rel", ours));
        assertEquals("x>new", ends(twin, "to-rel", ours));
        for (String id : List.of("conn-before", "conn", "conn-after", "conn-b")) {
            assertEquals("connection " + id, drawing(archi, id, archiNew), drawing(twin, id, ours));
        }
        assertEquals("draws the new one, bendpoints [5,6], at 1 of source and 1 of target",
                drawing(twin, "conn", ours));
        for (IArchimateRelationship old : List.of(archiOld, ourOld)) {
            assertNull("the old relationship is in no folder", old.eContainer());
            assertFalse(old.getSource().getSourceRelationships().contains(old));
            assertFalse(old.getTarget().getTargetRelationships().contains(old));
            assertTrue(old.getReferencingDiagramComponents().isEmpty());
            assertTrue(old.getSourceRelationships().isEmpty());
            assertTrue(old.getTargetRelationships().isEmpty());
        }

        // the declared differences
        assertNotEquals("Archi mints a new id", "rel", archiNew.getId());
        assertEquals("this tool keeps the id", "rel", ours.getId());
        assertEquals("Archi appends to the folder", List.of("first", "last", "from-rel", "to-rel", archiNew.getId()),
                ids((IFolder) archiNew.eContainer()));
        assertEquals("this tool keeps the relationship's place", List.of("first", "rel", "last", "from-rel", "to-rel"),
                ids((IFolder) ours.eContainer()));
    }

    // ---- the two sides ----

    private static IArchimateRelationship runArchi(IArchimateModel model, EClass type) throws Exception {
        IArchimateRelationship relationship = (IArchimateRelationship) ArchimateModelUtils.getObjectByID(model, "rel");
        Command change = archiRelationshipTypeChange(type, relationship);
        try {
            change.execute();
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            assertTrue("only the last step, which asks the unavailable rule, may fail: " + e,
                    Arrays.stream(e.getStackTrace()).anyMatch(f -> f.getMethodName().equals("changeInvalidRelations")));
        }
        IArchimateRelationship created = null;
        for (Object each : model.getFolder(FolderType.RELATIONS).getElements()) {
            if (each instanceof IArchimateRelationship candidate && candidate != relationship
                    && candidate.eClass() == type && "uses".equals(candidate.getName())) {
                created = candidate;
            }
        }
        assertTrue("Archi created the new relationship", created != null);
        return created;
    }

    /**
     * Archi's relationship type-change command, built as its factory builds it but without the
     * factory's check that the type is allowed, which needs the rule this JVM cannot load.
     */
    private static Command archiRelationshipTypeChange(EClass type, IArchimateRelationship relationship)
            throws Exception {
        for (int i = 1; i < 64; i++) {
            Class<?> candidate;
            try {
                candidate = Class.forName(SetConceptTypeCommandFactory.class.getName() + "$" + i);
            } catch (ClassNotFoundException e) {
                continue;
            }
            if (candidate.getEnclosingMethod() != null
                    && candidate.getEnclosingMethod().getName().equals("createSetRelationTypeCommand")) {
                Constructor<?> constructor = candidate.getDeclaredConstructor(EClass.class,
                        IArchimateRelationship.class, boolean.class);
                constructor.setAccessible(true);
                return (Command) constructor.newInstance(type, relationship, false);
            }
        }
        throw new AssertionError("Archi's relationship type-change command was not found");
    }

    private static IArchimateRelationship runOurs(IArchimateModel model, EClass type) {
        IArchimateRelationship old = (IArchimateRelationship) ArchimateModelUtils.getObjectByID(model, "rel");
        ChangeRelationshipTypeOperations ops = new ChangeRelationshipTypeOperations(sessionId -> model, () -> null,
                () -> { }, (source, target, relationship) -> true, (source, target, relationship) -> true);
        ChangeRelationshipTypeCommand command = (ChangeRelationshipTypeCommand) ops.prepare(model,
                List.of(new RelationshipTypeChangeRequest("rel", type.getName())), null).command();
        command.execute();
        assertNull(command.getSkipReason());
        assertNull("this tool removes the old relationship", old.eContainer());
        return (IArchimateRelationship) ArchimateModelUtils.getObjectByID(model, "rel");
    }

    // ---- fixture ----

    /**
     * {@code x} to {@code el} through {@code rel}, a relationship of {@code type} between two
     * others in the Relations folder, carrying a name, documentation, a property, a feature, a
     * specialization and its type's attribute set away from the default; one relationship attached
     * to it at each end; and four connections drawing relationships between the two boxes,
     * {@code conn} drawing {@code rel} with a bendpoint between two others, and {@code conn-b}
     * drawing it on a second view.
     */
    private static IArchimateModel fixture(EClass type) {
        IArchimateModel model = F.createArchimateModel();
        model.setDefaults();
        IArchimateElement el = F.createBusinessObject();
        el.setId("el");
        el.setName("Invoice");
        model.getFolder(FolderType.BUSINESS).getElements().add(el);
        IArchimateElement x = F.createApplicationComponent();
        x.setId("x");
        x.setName("Billing");
        model.getFolder(FolderType.APPLICATION).getElements().add(x);
        IFolder relations = model.getFolder(FolderType.RELATIONS);
        IArchimateRelationship first = relationship(model, "first", x, el);
        IArchimateRelationship rel = (IArchimateRelationship) F.create(type);
        rel.setId("rel");
        rel.setName("uses");
        rel.setDocumentation("docs\r\nline");
        rel.connect(x, el);
        relations.getElements().add(rel);
        if (rel instanceof IAccessRelationship access) {
            access.setAccessType(IAccessRelationship.READ_WRITE_ACCESS);
        }
        if (rel instanceof IAssociationRelationship association) {
            association.setDirected(true);
        }
        IArchimateRelationship last = relationship(model, "last", x, el);
        IProperty property = F.createProperty();
        property.setKey("k");
        property.setValue("v");
        rel.getProperties().add(property);
        rel.getFeatures().putString("f", "1");
        IProfile profile = F.createProfile();
        profile.setId("profile-gold");
        profile.setName("Gold");
        profile.setConceptType(type.getName());
        model.getProfiles().add(profile);
        rel.getProfiles().add(profile);
        IArchimateRelationship fromRel = F.createAssociationRelationship();
        fromRel.setId("from-rel");
        fromRel.connect(rel, x);
        relations.getElements().add(fromRel);
        IArchimateRelationship toRel = F.createAssociationRelationship();
        toRel.setId("to-rel");
        toRel.connect(x, rel);
        relations.getElements().add(toRel);

        IArchimateDiagramModel view = F.createArchimateDiagramModel();
        view.setId("view");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        IDiagramModelArchimateObject boxX = box("box-x", x);
        view.getChildren().add(boxX);
        IDiagramModelArchimateObject boxEl = box("box-el", el);
        view.getChildren().add(boxEl);
        draw("conn-before", first, boxX, boxEl);
        IDiagramModelArchimateConnection drawn = draw("conn", rel, boxX, boxEl);
        IDiagramModelBendpoint bendpoint = F.createDiagramModelBendpoint();
        bendpoint.setStartX(5);
        bendpoint.setStartY(6);
        drawn.getBendpoints().add(bendpoint);
        draw("conn-after", last, boxX, boxEl);
        IArchimateDiagramModel second = F.createArchimateDiagramModel();
        second.setId("view-b");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(second);
        IDiagramModelArchimateObject boxX2 = box("box-x-b", x);
        second.getChildren().add(boxX2);
        IDiagramModelArchimateObject boxEl2 = box("box-el-b", el);
        second.getChildren().add(boxEl2);
        draw("conn-b", rel, boxX2, boxEl2);
        return model;
    }

    private static IDiagramModelArchimateConnection draw(String id, IArchimateRelationship relationship,
            IDiagramModelArchimateObject source, IDiagramModelArchimateObject target) {
        IDiagramModelArchimateConnection connection = F.createDiagramModelArchimateConnection();
        connection.setId(id);
        connection.setArchimateRelationship(relationship);
        connection.connect(source, target);
        return connection;
    }

    /** A concept by type and name, which survives Archi's new id. */
    private static String describe(IArchimateConcept concept) {
        return concept.eClass().getName() + " " + concept.getName();
    }

    /** A relationship's ends, with the changed relationship read as {@code new}. */
    private static String ends(IArchimateModel model, String id, IArchimateRelationship changed) {
        IArchimateRelationship relationship = (IArchimateRelationship) ArchimateModelUtils.getObjectByID(model, id);
        return (relationship.getSource() == changed ? "new" : relationship.getSource().getId()) + ">"
                + (relationship.getTarget() == changed ? "new" : relationship.getTarget().getId());
    }

    /** What a connection draws, its bendpoints, and its index in both ends' lists. */
    private static String drawing(IArchimateModel model, String id, IArchimateRelationship changed) {
        IDiagramModelArchimateConnection connection =
                (IDiagramModelArchimateConnection) ArchimateModelUtils.getObjectByID(model, id);
        IArchimateRelationship drawn = connection.getArchimateRelationship();
        List<String> bendpoints = new ArrayList<>();
        for (IDiagramModelBendpoint each : connection.getBendpoints()) {
            bendpoints.add(each.getStartX() + "," + each.getStartY());
        }
        return "draws " + (drawn == changed ? "the new one" : drawn.getId()) + ", bendpoints " + bendpoints
                + ", at " + connection.getSource().getSourceConnections().indexOf(connection) + " of source and "
                + connection.getTarget().getTargetConnections().indexOf(connection) + " of target";
    }

    /** Every attribute the relationship's own class declares, by name. */
    private static String attributes(IArchimateRelationship relationship) {
        List<String> out = new ArrayList<>();
        for (var attribute : relationship.eClass().getEAttributes()) {
            out.add(attribute.getName() + "=" + relationship.eGet(attribute));
        }
        return out.toString();
    }

    private static IArchimateRelationship relationship(IArchimateModel model, String id, IArchimateElement source,
            IArchimateElement target) {
        IArchimateRelationship rel = F.createAssociationRelationship();
        rel.setId(id);
        rel.connect(source, target);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);
        return rel;
    }

    private static IDiagramModelArchimateObject box(String id, IArchimateElement element) {
        IDiagramModelArchimateObject box = F.createDiagramModelArchimateObject();
        box.setId(id);
        box.setArchimateElement(element);
        box.setBounds(0, 0, 120, 55);
        return box;
    }

    private static List<String> pairs(com.archimatetool.model.IProperties concept) {
        return concept.getProperties().stream().map(p -> p.getKey() + "=" + p.getValue()).toList();
    }

    private static List<String> features(com.archimatetool.model.IFeatures concept) {
        return concept.getFeatures().stream().map(f -> f.getName() + "=" + f.getValue()).toList();
    }

    private static List<String> ids(IFolder folder) {
        return folder.getElements().stream().map(e -> ((IIdentifier) e).getId()).toList();
    }
}
