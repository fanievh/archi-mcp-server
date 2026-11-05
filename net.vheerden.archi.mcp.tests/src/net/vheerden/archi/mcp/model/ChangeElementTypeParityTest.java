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
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceStore;
import org.eclipse.osgi.util.NLS;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.ArchiPlugin;
import com.archimatetool.editor.model.commands.SetConceptTypeCommandFactory;
import com.archimatetool.editor.preferences.IPreferenceConstants;
import com.archimatetool.model.FolderType;
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
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.IProfile;
import com.archimatetool.model.IProperty;
import com.archimatetool.model.util.ArchimateModelUtils;

import net.vheerden.archi.mcp.response.dto.ElementTypeChangeRequest;

/**
 * The type change against Archi's own. Archi's per-element type-change sub-command runs on one
 * model and this tool's command on an identical twin; the two are compared kind by kind for what
 * points at the new element, then for what the new element carries, its boxes' figures and where
 * it is filed. Only the declared differences may differ, and each is asserted rather than
 * skipped: this tool keeps the element's id where Archi mints a new one, and keeps the element's
 * place in its folder where Archi appends it.
 *
 * <p>The kinds are the census {@link MergeElementsReferenceKindsTest} counts, every
 * non-containment reference named by its declaring class, and the fixture is checked to hold
 * every reference the metamodel lets point at an element. Both sides read the default figure from
 * the same preference store, so the figure comparison also proves this tool reads Archi's
 * preference.</p>
 *
 * <p>A relationship the new type does not allow is compared against Archi's own relationship
 * type change, the command its editor runs for each such relationship once the user has agreed.
 * Archi builds that command only after asking its relationship rule, which cannot load in a plain
 * JVM, so the command is built here directly; the rule's answer for an Association is yes wherever
 * this runs. Its last step asks the rule again about the relationships attached to the new one and
 * changes nothing; it is allowed to fail here, and only with the rule unavailable. Archi's label
 * provider cannot load either, so Archi's documentation note is compared with Archi's own formula
 * and strings rather than by running its command with the note on.</p>
 */
public class ChangeElementTypeParityTest {

    private static final IArchimateFactory F = IArchimateFactory.eINSTANCE;
    private static final IArchimatePackage P = IArchimatePackage.eINSTANCE;

    private boolean installedPlugin;

    @Before
    public void setUp() {
        if (ArchiPlugin.getInstance() == null) {
            new ArchiPlugin() {
                private final IPreferenceStore store = new PreferenceStore();

                @Override
                public IPreferenceStore getPreferenceStore() {
                    return store;
                }
            };
            installedPlugin = true;
        }
        ArchiPlugin.getInstance().getPreferenceStore().setValue(
                IPreferenceConstants.DEFAULT_FIGURE_PREFIX + "BusinessRole", 1);
    }

    @After
    public void tearDown() {
        ArchiPlugin.getInstance().getPreferenceStore().setToDefault(
                IPreferenceConstants.DEFAULT_FIGURE_PREFIX + "BusinessRole");
        if (installedPlugin) {
            ArchiPlugin.INSTANCE = null;
        }
    }

    @Test
    public void shouldRepointEveryKindOfReference_andCarryWhatArchiCarries_withinTheLayer() throws Exception {
        IArchimateModel archi = fixture();
        IArchimateModel twin = fixture();
        Map<String, Integer> before = MergeElementsReferenceKindsTest.census(archi, "el");
        assertTrue("the fixture must hold references to the element: " + before, before.size() >= 3);

        IArchimateElement archiNew = runArchi(archi, P.getBusinessRole());
        IArchimateElement ours = runOurs(twin, "BusinessRole");

        assertEquals("every kind of reference, as many of each, points at the new element on both sides",
                MergeElementsReferenceKindsTest.census(archi, archiNew.getId()),
                MergeElementsReferenceKindsTest.census(twin, ours.getId()));
        assertEquals(before, MergeElementsReferenceKindsTest.census(twin, ours.getId()));
        assertSameCarried(archiNew, ours);
        assertEquals("figure: Archi's default-figure preference on both sides", figures(archi), figures(twin));
        assertEquals(List.of(1, 1), figures(twin));

        assertEquals("folder: both keep a subfolder of the new type's layer", "people",
                ((IFolder) archiNew.eContainer()).getId());
        assertEquals("people", ((IFolder) ours.eContainer()).getId());

        // the declared differences
        assertNotEquals("Archi mints a new id", "el", archiNew.getId());
        assertEquals("this tool keeps the id", "el", ours.getId());
        assertEquals("Archi appends to the folder", List.of("first", "last", archiNew.getId()),
                ids((IFolder) archiNew.eContainer()));
        assertEquals("this tool keeps the element's place", List.of("first", "el", "last"),
                ids((IFolder) ours.eContainer()));
    }

    @Test
    public void shouldMoveToTheNewLayersDefaultFolder_asArchiDoes() throws Exception {
        IArchimateModel archi = fixture();
        IArchimateModel twin = fixture();

        IArchimateElement archiNew = runArchi(archi, P.getApplicationComponent());
        IArchimateElement ours = runOurs(twin, "ApplicationComponent");

        assertEquals(archi.getFolder(FolderType.APPLICATION), archiNew.eContainer());
        assertEquals(twin.getFolder(FolderType.APPLICATION), ours.eContainer());
        assertEquals("both append to the default folder",
                ids((IFolder) archiNew.eContainer()).indexOf(archiNew.getId()),
                ids((IFolder) ours.eContainer()).indexOf(ours.getId()));
        assertEquals(MergeElementsReferenceKindsTest.census(archi, archiNew.getId()),
                MergeElementsReferenceKindsTest.census(twin, ours.getId()));
        assertSameCarried(archiNew, ours);
        assertEquals("an unset default figure reads as 0 on both sides", figures(archi), figures(twin));
    }

    @Test
    public void shouldHoldEveryReferenceAnElementCanBeTheValueOf_inTheFixture() {
        Set<String> possible = new TreeSet<>();
        EClass element = P.getBusinessActor();
        for (EClassifier classifier : P.getEClassifiers()) {
            if (!(classifier instanceof EClass owner) || owner.isAbstract() || owner.isInterface()) {
                continue;
            }
            for (EReference reference : owner.getEAllReferences()) {
                if (MergeElementsReferenceKindsTest.counted(reference)
                        && reference.getEReferenceType().isSuperTypeOf(element)) {
                    possible.add(MergeElementsReferenceKindsTest.kind(reference));
                }
            }
        }
        assertEquals("a reference the metamodel lets point at an element, that the fixture does not hold, "
                + "is a kind neither side of the comparison would see",
                possible, MergeElementsReferenceKindsTest.census(fixture(), "el").keySet());
    }

    @Test
    public void shouldConvertARelationshipAsArchiDoes_keepingWhatArchiKeeps() throws Exception {
        IArchimateModel archi = conversionFixture();
        IArchimateModel twin = conversionFixture();
        IArchimateRelationship archiOld = (IArchimateRelationship) ArchimateModelUtils.getObjectByID(archi, "rel");
        IArchimateRelationship ourOld = (IArchimateRelationship) ArchimateModelUtils.getObjectByID(twin, "rel");

        IArchimateRelationship archiNew = runArchiConversion(archi, false);
        IArchimateRelationship ours = runOurConversion(twin, false, type -> "unused");

        assertEquals("type", archiNew.eClass(), ours.eClass());
        assertEquals("direction: Archi's default on both sides", ((IAssociationRelationship) archiNew).isDirected(),
                ((IAssociationRelationship) ours).isDirected());
        assertEquals("name", archiNew.getName(), ours.getName());
        assertEquals("documentation, note off", archiNew.getDocumentation(), ours.getDocumentation());
        assertEquals("docs\r\nline", ours.getDocumentation());
        assertEquals("properties", pairs(archiNew), pairs(ours));
        assertEquals("features", features(archiNew), features(ours));
        assertEquals("specialization: dropped by both", archiNew.getProfiles(), ours.getProfiles());
        assertTrue(ours.getProfiles().isEmpty());
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

    @Test
    public void shouldWriteArchisDocumentationNote_byteForByte() throws Exception {
        String serving = com.archimatetool.editor.ui.factory.relationships.Messages.ServingRelationshipUIProvider_0;
        assertEquals("Archi's name for the type", "Serving relation", serving);
        for (String documentation : List.of("docs\r\nline", "")) {
            IArchimateModel twin = conversionFixture();
            ((IArchimateRelationship) ArchimateModelUtils.getObjectByID(twin, "rel")).setDocumentation(documentation);
            // Archi's own formula, with Archi's own strings
            String archi = NLS.bind(com.archimatetool.editor.model.commands.Messages.SetConceptTypeCommandFactory_2
                    + (documentation.isEmpty() ? "" : "\n\n"), serving) + documentation;

            IArchimateRelationship ours = runOurConversion(twin, true,
                    type -> type == P.getServingRelationship() ? serving : "not the old type");

            assertEquals(archi, ours.getDocumentation());
        }
        assertEquals("(Changed from Serving relation)\n\ndocs\r\nline", NLS.bind(
                com.archimatetool.editor.model.commands.Messages.SetConceptTypeCommandFactory_2 + "\n\n",
                serving) + "docs\r\nline");
    }

    // ---- the two sides ----

    /**
     * Archi's way: the element's type change, then Archi's relationship type change on the
     * relationship the new type does not allow, as its editor runs them once the user agrees.
     */
    private static IArchimateRelationship runArchiConversion(IArchimateModel model, boolean note) throws Exception {
        IArchimateElement old = (IArchimateElement) ArchimateModelUtils.getObjectByID(model, "el");
        MergeElementsReferenceKindsTest.archiElementTypeChange(P.getBusinessEvent(), old).execute();
        IArchimateRelationship relationship = (IArchimateRelationship) ArchimateModelUtils.getObjectByID(model, "rel");
        Command convert = archiRelationshipTypeChange(P.getAssociationRelationship(), relationship, note);
        try {
            convert.execute();
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            assertTrue("only the last step, which asks the unavailable rule, may fail: " + e,
                    Arrays.stream(e.getStackTrace()).anyMatch(f -> f.getMethodName().equals("changeInvalidRelations")));
        }
        IArchimateRelationship created = null;
        for (Object each : model.getFolder(FolderType.RELATIONS).getElements()) {
            if (each instanceof IArchimateRelationship candidate && candidate != relationship
                    && "serves".equals(candidate.getName())) {
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
    private static Command archiRelationshipTypeChange(EClass type, IArchimateRelationship relationship,
            boolean note) throws Exception {
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
                return (Command) constructor.newInstance(type, relationship, note);
            }
        }
        throw new AssertionError("Archi's relationship type-change command was not found");
    }

    private IArchimateRelationship runOurConversion(IArchimateModel model, boolean note,
            java.util.function.Function<EClass, String> label) {
        ChangeElementTypeOperations ops = new ChangeElementTypeOperations(sessionId -> model, () -> null,
                () -> { }, (source, target, relationship) -> !(relationship == P.getServingRelationship()
                        && target == P.getBusinessEvent()),
                ChangeElementTypeOperations.ARCHI_FIGURE, () -> note, label);
        ChangeElementTypeCommand command = (ChangeElementTypeCommand) ops.prepare(model,
                List.of(new ElementTypeChangeRequest("el", "BusinessEvent")), "to-association", null, null).command();
        command.execute();
        assertNull(command.getSkipReason());
        assertEquals(1, command.getResult().totals().relationshipsConverted());
        return (IArchimateRelationship) ArchimateModelUtils.getObjectByID(model, "rel");
    }

    private static IArchimateElement runArchi(IArchimateModel model, EClass type) throws Exception {
        IArchimateElement old = (IArchimateElement) ArchimateModelUtils.getObjectByID(model, "el");
        Command change = MergeElementsReferenceKindsTest.archiElementTypeChange(type, old);
        change.execute();
        assertNull("Archi removes the old element", old.eContainer());
        IArchimateElement created = null;
        for (var it = model.eAllContents(); it.hasNext();) {
            if (it.next() instanceof IArchimateElement each && each.eClass() == type && each != old
                    && "Customer".equals(each.getName())) {
                created = each;
            }
        }
        assertTrue("Archi created the new element", created != null);
        return created;
    }

    private static IArchimateElement runOurs(IArchimateModel model, String type) {
        IArchimateElement old = (IArchimateElement) ArchimateModelUtils.getObjectByID(model, "el");
        ChangeElementTypeOperations ops = new ChangeElementTypeOperations(sessionId -> model, () -> null,
                () -> { }, (source, target, relationship) -> true, ChangeElementTypeOperations.ARCHI_FIGURE);
        Command command = ops.prepare(model, List.of(new ElementTypeChangeRequest("el", type)), null).command();
        command.execute();
        assertNull(((ChangeElementTypeCommand) command).getSkipReason());
        assertNull("this tool removes the old element", old.eContainer());
        return (IArchimateElement) ArchimateModelUtils.getObjectByID(model, "el");
    }

    private static void assertSameCarried(IArchimateElement archi, IArchimateElement ours) {
        assertEquals("type", archi.eClass(), ours.eClass());
        assertEquals("name", archi.getName(), ours.getName());
        assertEquals("documentation", archi.getDocumentation(), ours.getDocumentation());
        assertEquals("properties", pairs(archi), pairs(ours));
        assertEquals("features", features(archi), features(ours));
        assertEquals("specialization: dropped by both", archi.getProfiles().size(), ours.getProfiles().size());
        assertTrue(ours.getProfiles().isEmpty());
    }

    // ---- fixture ----

    /**
     * One element, {@code el}, in a subfolder of the Business folder between two others, held by
     * one of every kind: a relationship's source end, another's target end, a box on a view with a
     * connection and a bendpoint, and a box nested in a group. It carries a property, a feature,
     * documentation and a specialization.
     */
    private static IArchimateModel fixture() {
        IArchimateModel model = F.createArchimateModel();
        model.setDefaults();
        IFolder people = F.createFolder();
        people.setId("people");
        model.getFolder(FolderType.BUSINESS).getFolders().add(people);
        actor(people, "first", "First");
        IArchimateElement el = actor(people, "el", "Customer");
        actor(people, "last", "Last");
        IArchimateElement other = actor(model.getFolder(FolderType.BUSINESS), "other", "Other");
        IArchimateElement filler = F.createApplicationComponent();
        filler.setId("app-existing");
        model.getFolder(FolderType.APPLICATION).getElements().add(filler);
        el.setDocumentation("docs\r\nline");
        IProperty property = F.createProperty();
        property.setKey("k");
        property.setValue("v");
        el.getProperties().add(property);
        el.getFeatures().putString("f", "1");
        IProfile profile = F.createProfile();
        profile.setId("profile-vip");
        profile.setName("VIP");
        profile.setConceptType("BusinessActor");
        model.getProfiles().add(profile);
        el.getProfiles().add(profile);
        IArchimateRelationship out = relationship(model, "out", el, other);
        relationship(model, "in", other, el);

        IArchimateDiagramModel view = F.createArchimateDiagramModel();
        view.setId("view");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        IDiagramModelArchimateObject box = box("box", el);
        view.getChildren().add(box);
        IDiagramModelArchimateObject otherBox = box("other-box", other);
        view.getChildren().add(otherBox);
        IDiagramModelArchimateConnection drawn = F.createDiagramModelArchimateConnection();
        drawn.setId("conn");
        drawn.setArchimateRelationship(out);
        drawn.connect(box, otherBox);
        IDiagramModelBendpoint bendpoint = F.createDiagramModelBendpoint();
        bendpoint.setStartX(5);
        drawn.getBendpoints().add(bendpoint);
        IDiagramModelGroup group = F.createDiagramModelGroup();
        group.setId("group");
        view.getChildren().add(group);
        group.getChildren().add(box("nested", el));
        return model;
    }

    /**
     * {@code el} served by {@code x} through {@code rel}, a Serving relationship between two
     * others in the Relations folder, carrying a name, documentation, a property, a feature and a
     * specialization; one relationship attached to it at each end; and four connections drawing
     * relationships between the two boxes, {@code conn} drawing {@code rel} with a bendpoint
     * between two others, and {@code conn-b} drawing it on a second view.
     */
    private static IArchimateModel conversionFixture() {
        IArchimateModel model = F.createArchimateModel();
        model.setDefaults();
        IArchimateElement el = actor(model.getFolder(FolderType.BUSINESS), "el", "Customer");
        IArchimateElement x = F.createApplicationComponent();
        x.setId("x");
        x.setName("Billing");
        model.getFolder(FolderType.APPLICATION).getElements().add(x);
        IFolder relations = model.getFolder(FolderType.RELATIONS);
        IArchimateRelationship first = relationship(model, "first", x, el);
        IArchimateRelationship rel = F.createServingRelationship();
        rel.setId("rel");
        rel.setName("serves");
        rel.setDocumentation("docs\r\nline");
        rel.connect(x, el);
        relations.getElements().add(rel);
        IArchimateRelationship last = relationship(model, "last", x, el);
        IProperty property = F.createProperty();
        property.setKey("k");
        property.setValue("v");
        rel.getProperties().add(property);
        rel.getFeatures().putString("f", "1");
        IProfile profile = F.createProfile();
        profile.setId("profile-gold");
        profile.setName("Gold");
        profile.setConceptType("ServingRelationship");
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

    /** A concept by type and name, which survives Archi's new element id. */
    private static String describe(IArchimateConcept concept) {
        return concept.eClass().getName() + " " + concept.getName();
    }

    /** A relationship's ends, with the converted relationship read as {@code new}. */
    private static String ends(IArchimateModel model, String id, IArchimateRelationship converted) {
        IArchimateRelationship relationship = (IArchimateRelationship) ArchimateModelUtils.getObjectByID(model, id);
        return (relationship.getSource() == converted ? "new" : relationship.getSource().getId()) + ">"
                + (relationship.getTarget() == converted ? "new" : relationship.getTarget().getId());
    }

    /** What a connection draws, its bendpoints, and its index in both ends' lists. */
    private static String drawing(IArchimateModel model, String id, IArchimateRelationship converted) {
        IDiagramModelArchimateConnection connection =
                (IDiagramModelArchimateConnection) ArchimateModelUtils.getObjectByID(model, id);
        IArchimateRelationship drawn = connection.getArchimateRelationship();
        List<String> bendpoints = new ArrayList<>();
        for (IDiagramModelBendpoint each : connection.getBendpoints()) {
            bendpoints.add(each.getStartX() + "," + each.getStartY());
        }
        return "draws " + (drawn == converted ? "the new one" : drawn.getId()) + ", bendpoints " + bendpoints
                + ", at " + connection.getSource().getSourceConnections().indexOf(connection) + " of source and "
                + connection.getTarget().getTargetConnections().indexOf(connection) + " of target";
    }

    private static IArchimateElement actor(IFolder folder, String id, String name) {
        IArchimateElement el = F.createBusinessActor();
        el.setId(id);
        el.setName(name);
        folder.getElements().add(el);
        return el;
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

    private static List<Integer> figures(IArchimateModel model) {
        List<Integer> out = new ArrayList<>();
        for (String id : List.of("box", "nested")) {
            out.add(((IDiagramModelArchimateObject) ArchimateModelUtils.getObjectByID(model, id)).getType());
        }
        return out;
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
