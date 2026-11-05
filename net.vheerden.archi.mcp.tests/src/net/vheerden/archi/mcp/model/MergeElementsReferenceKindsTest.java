package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.gef.commands.Command;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.ArchiPlugin;
import com.archimatetool.editor.model.commands.SetConceptTypeCommandFactory;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimatePackage;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelGroup;

import net.vheerden.archi.mcp.response.dto.MergeClusterRequest;

/**
 * The merge re-points an element's references in place, and the list of what refers to an element
 * is Archi's, not this server's. Archi's own record of that list is the command that changes an
 * element's type: it moves every reference from the old element to the new one. So this runs that
 * command on one model, the merge on an identical twin, and compares, kind by kind, which
 * references end up at the new element against which end up at the merge target.
 *
 * <p>The kinds are counted generically — every non-containment reference of every object in the
 * model, named by the class that declares it — so a kind Archi starts moving is counted
 * without this test naming it. A second check reads the metamodel itself: every reference an
 * element can be the value of must be held by the fixture, so a new kind in the metamodel fails
 * here until the fixture shows it.</p>
 *
 * <p>Only Archi's element sub-command runs, reached by name: the outer command then retypes
 * relationships the new type makes invalid, which needs Archi's relationship rules, and those load
 * only inside the running application. A renamed method fails here loudly, not quietly.</p>
 */
public class MergeElementsReferenceKindsTest {

    private static final IArchimateFactory F = IArchimateFactory.eINSTANCE;

    private boolean installedPlugin;

    @Before
    public void setUp() {
        if (ArchiPlugin.getInstance() == null) {
            // The diagram-object step reads its figure default from the plugin's preferences.
            new ArchiPlugin() {
                private final IPreferenceStore store = new PreferenceStore();

                @Override
                public IPreferenceStore getPreferenceStore() {
                    return store;
                }
            };
            installedPlugin = true;
        }
    }

    @After
    public void tearDown() {
        if (installedPlugin) {
            ArchiPlugin.INSTANCE = null;
        }
    }

    @Test
    public void shouldRepointEveryKindOfReference_ArchiMovesForAnElement() throws Exception {
        IArchimateModel archi = fixture();
        IArchimateModel twin = fixture();
        Map<String, Integer> before = census(archi, "dup");
        assertTrue("the fixture must hold references to the element: " + before, before.size() >= 3);

        IArchimateElement old = (IArchimateElement) find(archi, "dup");
        Command change = archiElementTypeChange(IArchimatePackage.eINSTANCE.getBusinessRole(), old);
        change.execute();
        IArchimateElement created = archi.getFolder(FolderType.BUSINESS).getElements().stream()
                .filter(e -> e instanceof IArchimateElement el && el != old && el.eClass()
                        == IArchimatePackage.eINSTANCE.getBusinessRole())
                .map(e -> (IArchimateElement) e).findFirst().orElseThrow();
        Map<String, Integer> moved = census(archi, created.getId());
        assertEquals("Archi moves every reference the old element had", before, moved);

        Map<String, Integer> targetBefore = census(twin, "keep");
        MergeElementsCommand merge = new MergeElementsCommand(twin,
                List.of(new MergeClusterRequest("keep", List.of("dup"))),
                MergeElementsOperations.options(null, null, false, false));
        merge.execute();
        Map<String, Integer> repointed = census(twin, "keep");
        targetBefore.forEach((kind, n) -> repointed.merge(kind, -n, Integer::sum));
        repointed.values().removeIf(n -> n == 0);

        assertEquals("every kind of reference Archi moves to the new element, the merge must move "
                + "to the target, as many of each (" + merge.getSkipReason() + ")", moved, repointed);
        assertNull(merge.getSkipReason());
    }

    @Test
    public void shouldHoldEveryReferenceAnElementCanBeTheValueOf_inTheFixture() {
        Set<String> possible = new TreeSet<>();
        EClass element = IArchimatePackage.eINSTANCE.getBusinessActor();
        for (EClassifier classifier : IArchimatePackage.eINSTANCE.getEClassifiers()) {
            if (!(classifier instanceof EClass owner) || owner.isAbstract() || owner.isInterface()) {
                continue;
            }
            for (EReference reference : owner.getEAllReferences()) {
                if (counted(reference) && reference.getEReferenceType().isSuperTypeOf(element)) {
                    possible.add(kind(reference));
                }
            }
        }
        assertEquals("a reference the metamodel lets point at an element, that the fixture does not "
                + "hold, is a kind neither side of the comparison above would see",
                possible, census(fixture(), "dup").keySet());
    }

    // ---- fixture and census ----

    /**
     * One element, {@code dup}, held by one of every kind: a relationship's source end, another's
     * target end, a box on a view and a box nested in a group. {@code keep} is its same-type twin,
     * drawn nowhere, so what points at it after a merge came from {@code dup}.
     */
    private static IArchimateModel fixture() {
        IArchimateModel model = F.createArchimateModel();
        model.setDefaults();
        IArchimateElement dup = actor(model, "dup");
        actor(model, "keep");
        IArchimateElement other = actor(model, "other");
        relationship(model, "out", dup, other);
        relationship(model, "in", other, dup);

        IArchimateDiagramModel view = F.createArchimateDiagramModel();
        view.setId("view");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        view.getChildren().add(box("box", dup));
        IDiagramModelGroup group = F.createDiagramModelGroup();
        group.setId("group");
        view.getChildren().add(group);
        group.getChildren().add(box("nested", dup));
        return model;
    }

    private static IArchimateElement actor(IArchimateModel model, String id) {
        IArchimateElement el = F.createBusinessActor();
        el.setId(id);
        el.setName(id);
        model.getFolder(FolderType.BUSINESS).getElements().add(el);
        return el;
    }

    private static void relationship(IArchimateModel model, String id, IArchimateElement source,
            IArchimateElement target) {
        IArchimateRelationship rel = F.createAssociationRelationship();
        rel.setId(id);
        rel.connect(source, target);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);
    }

    private static IDiagramModelArchimateObject box(String id, IArchimateElement element) {
        IDiagramModelArchimateObject box = F.createDiagramModelArchimateObject();
        box.setId(id);
        box.setArchimateElement(element);
        box.setBounds(0, 0, 120, 55);
        return box;
    }

    private static EObject find(IArchimateModel model, String id) {
        return com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(model, id);
    }

    static boolean counted(EReference reference) {
        return !reference.isContainment() && !reference.isContainer() && !reference.isDerived()
                && !reference.isTransient() && reference.isChangeable();
    }

    /** A kind is named where the metamodel declares it, so every relationship type shares two. */
    static String kind(EReference reference) {
        return reference.getEContainingClass().getName() + "." + reference.getName();
    }

    /** How many references of each kind point at the object with this id. */
    static Map<String, Integer> census(IArchimateModel model, String id) {
        EObject subject = find(model, id);
        Map<String, Integer> kinds = new TreeMap<>();
        List<EObject> all = new ArrayList<>();
        model.eAllContents().forEachRemaining(all::add);
        for (EObject object : all) {
            for (EReference reference : object.eClass().getEAllReferences()) {
                if (!counted(reference)) {
                    continue;
                }
                Object value = object.eGet(reference);
                int n = 0;
                if (reference.isMany()) {
                    for (Object item : (List<?>) value) {
                        n += item == subject ? 1 : 0;
                    }
                } else if (value == subject) {
                    n = 1;
                }
                if (n > 0) {
                    kinds.merge(kind(reference), n, Integer::sum);
                }
            }
        }
        return kinds;
    }

    /** Archi's own sub-command for changing one element's type, without the relationship retyping. */
    static Command archiElementTypeChange(EClass type, IArchimateElement element) throws Exception {
        Method method = SetConceptTypeCommandFactory.class.getDeclaredMethod("createSetElementTypeCommand",
                EClass.class, IArchimateElement.class);
        method.setAccessible(true);
        Command command = (Command) method.invoke(null, type, element);
        assertTrue("Archi declined to change the type", command != null);
        return command;
    }
}
