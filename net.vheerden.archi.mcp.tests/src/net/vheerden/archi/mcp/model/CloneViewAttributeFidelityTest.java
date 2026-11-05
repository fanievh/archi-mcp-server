package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.common.notify.impl.AdapterImpl;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimatePackage;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModelArchimateComponent;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IDiagramModelImage;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.IFeature;
import com.archimatetool.model.IProperty;
import com.archimatetool.model.ITextAlignment;
import com.archimatetool.model.util.ArchimateModelUtils;
import com.archimatetool.model.util.ArchimateResourceFactory;

/**
 * Pins that {@code clone-view} copies every attribute, feature and property a view object or
 * connection holds, as Archi's own Duplicate does, rather than the hand-picked subset it used to.
 *
 * <p>Archi duplicates a view with an EMF copy, which carries every attribute that is set and every
 * contained value: the feature map (label expressions, gradients, line styles, outline opacity,
 * derived line colours, legends, label positions, and this server's anchors), properties, borders,
 * names and connection documentation. Each fix pin below sets its values to something other than
 * the default, so a dropped field is visible, and asserts the copy against literal values.</p>
 *
 * <p>Three things are deliberately not copied as stored: the id, which every copy gets anew; the
 * name of an ArchiMate object or connection, and of a view reference, which Archi reads from (and
 * writes to) the concept or the referenced view, so copying it would write to the model; and an
 * anchor to another object on the view, which names the copy of that object instead.</p>
 *
 * <p>Same headless idiom as {@code CloneViewReferencesAndImagesTest}.</p>
 */
public class CloneViewAttributeFidelityTest {

    private static final String SESSION = "clone-view-attribute-fidelity-session";

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private IArchimateDiagramModel w;
    private MutationDispatcher dispatcher;
    private ArchiModelAccessorImpl accessor;
    private CommandStack stack;
    private IArchimateElement elA;
    private IArchimateElement elB;
    private IDiagramModelArchimateObject a;
    private IDiagramModelArchimateObject b;
    private IArchimateRelationship ab;
    private IDiagramModelArchimateConnection connectionAb;

    @Before
    public void setUp() throws Exception {
        model = factory.createArchimateModel();
        model.setId("model-clone-view-attribute-fidelity");
        model.setDefaults();
        view = factory.createArchimateDiagramModel();
        view.setId("view-source");
        view.setName("Source");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        w = factory.createArchimateDiagramModel();
        w.setId("view-w");
        w.setName("W");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(w);
        elA = element("el-a");
        elB = element("el-b");
        a = object(view, "vo-a", elA, 400, 0, 120, 60);
        b = object(view, "vo-b", elB, 700, 200, 120, 60);
        ab = factory.createAssociationRelationship();
        ab.setId("rel-ab");
        ab.setName("a to b");
        ab.setSource(elA);
        ab.setTarget(elB);
        model.getFolder(FolderType.RELATIONS).getElements().add(ab);
        connectionAb = factory.createDiagramModelArchimateConnection();
        connectionAb.setId("conn-ab");
        connectionAb.setArchimateRelationship(ab);
        connectionAb.connect(a, b);

        AutoRouteArmFixture.StubEditorModelManager mgr = new AutoRouteArmFixture.StubEditorModelManager();
        mgr.setModels(List.of(model));
        stack = new CommandStack();
        model.setAdapter(CommandStack.class, stack);
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                stack.execute(new AgentAuthoredCompoundCommand(toPlainCompound(command)));
            }
            @Override
            protected void dispatchCommand(Command command) {
                stack.execute(new AgentAuthoredCompoundCommand(toPlainCompound(command)));
            }
        };
        dispatcher.setApprovalModeProvider(() -> false);
        accessor = new ArchiModelAccessorImpl(mgr, dispatcher);
        dispatcher.onModelActive(model);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ---- every value is copied ----------------------------------------------------------------------

    @Test
    public void cloneView_shouldCopyTheLabelExpressionAndEveryStyleFeature_whenTheSourceObjectHasThem() {
        styleA();

        IArchimateDiagramModel clone = clone(cloneView());

        IDiagramModelArchimateObject copied = objectFor(clone, elA);
        assertNotSame(a, copied);
        assertNotEquals(a.getId(), copied.getId());
        assertEquals("${name} X", copied.getFeatures().getString("labelExpression", null));
        assertEquals(1, copied.getGradient());
        assertEquals(IDiagramModelObject.LINE_STYLE_DASHED, copied.getLineStyle());
        assertEquals(128, copied.getLineAlpha());
        assertFalse(copied.getDeriveElementLineColor());
        assertEquals("#123456", copied.getIconColor());
        assertFeaturesAreNewObjects(a.getFeatures(), copied.getFeatures());
    }

    @Test
    public void cloneView_shouldCopyTheNoteBorderPropertiesNameAndLegend_whenTheSourceNoteHasThem() {
        IDiagramModelNote note = dressedNote();

        IArchimateDiagramModel clone = clone(cloneView());

        IDiagramModelNote copied = only(clone, IDiagramModelNote.class);
        assertNotSame(note, copied);
        assertNotEquals(note.getId(), copied.getId());
        assertEquals("note text", copied.getContent());
        assertEquals(IDiagramModelNote.BORDER_RECTANGLE, copied.getBorderType());
        assertProperty(note.getProperties(), copied.getProperties(), "nk", "nv");
        assertEquals("note name", copied.getName());
        assertTrue("the note is a legend", copied.isLegend());
        assertFeaturesAreNewObjects(note.getFeatures(), copied.getFeatures());
    }

    @Test
    public void cloneView_shouldCopyTheGroupBorderAndProperties_whenTheSourceGroupHasThem() {
        accessor.addGroupToView(SESSION, view.getId(), "G", 10, 500, 400, 200, null, null, null);
        IDiagramModelGroup group = only(view, IDiagramModelGroup.class);
        group.setBorderType(IDiagramModelGroup.BORDER_RECTANGLE);
        group.getProperties().add(property("gk", "gv"));

        IArchimateDiagramModel clone = clone(cloneView());

        IDiagramModelGroup copied = only(clone, IDiagramModelGroup.class);
        assertNotSame(group, copied);
        assertNotEquals(group.getId(), copied.getId());
        assertEquals("G", copied.getName());
        assertEquals(IDiagramModelGroup.BORDER_RECTANGLE, copied.getBorderType());
        assertProperty(group.getProperties(), copied.getProperties(), "gk", "gv");
    }

    @Test
    public void cloneView_shouldCopyTheImageName_whenTheSourceImageHasOne() {
        IDiagramModelImage image = factory.createDiagramModelImage();
        image.setBounds(900, 600, 64, 48);
        image.setName("img name");
        view.getChildren().add(image);

        IArchimateDiagramModel clone = clone(cloneView());

        IDiagramModelImage copied = only(clone, IDiagramModelImage.class);
        assertNotSame(image, copied);
        assertEquals("img name", copied.getName());
    }

    @Test
    public void cloneView_shouldCopyTheConnectionDocumentationPropertiesAlignmentAndLabel_whenTheSourceHasThem() {
        dressConnectionAb();

        IArchimateDiagramModel clone = clone(cloneView());

        IDiagramModelArchimateConnection copied = connectionFor(objectFor(clone, elA), ab);
        assertNotSame(connectionAb, copied);
        assertNotEquals(connectionAb.getId(), copied.getId());
        assertSame("the same relationship", ab, copied.getArchimateRelationship());
        assertEquals("conn doc", copied.getDocumentation());
        assertProperty(connectionAb.getProperties(), copied.getProperties(), "ck", "cv");
        assertEquals(ITextAlignment.TEXT_ALIGNMENT_LEFT, copied.getTextAlignment());
        assertEquals("${name} C", copied.getFeatures().getString("labelExpression", null));
        assertFalse("the label stays hidden", copied.isNameVisible());
        assertFeaturesAreNewObjects(connectionAb.getFeatures(), copied.getFeatures());
    }

    @Test
    public void cloneView_shouldCopyTheLabelPosition_whenTheSourceConnectionHasOne() {
        Assume.assumeTrue("this Archi has no connection label position", RelativePositionFeature.isSupported(connectionAb));
        RelativePositionFeature.set(connectionAb, 1);

        IArchimateDiagramModel clone = clone(cloneView());

        assertEquals(1, RelativePositionFeature.get(connectionFor(objectFor(clone, elA), ab)));
    }

    @Test
    public void cloneView_shouldCopyTheGenericConnectionLabelAlignmentAndVisibility_whenTheSourceHasThem() {
        IDiagramModelNote note = note(view, 10, 300);
        IDiagramModelConnection generic = genericConnection(note, a);

        IArchimateDiagramModel clone = clone(cloneView());

        IDiagramModelConnection copied = only(clone, IDiagramModelNote.class).getSourceConnections().get(0);
        assertNotSame(generic, copied);
        assertNotEquals(generic.getId(), copied.getId());
        assertEquals("generic label", copied.getName());
        assertEquals(ITextAlignment.TEXT_ALIGNMENT_RIGHT, copied.getTextAlignment());
        assertFalse("the label stays hidden", copied.isNameVisible());
        assertSame(objectFor(clone, elA), copied.getTarget());
    }

    @Test
    public void cloneView_shouldCopyTheViewFeatures_whenTheSourceViewHasThem() {
        view.getFeatures().putString("viewFeature", "vf");

        IArchimateDiagramModel clone = clone(cloneView());

        assertEquals("vf", clone.getFeatures().getString("viewFeature", null));
        assertFeaturesAreNewObjects(view.getFeatures(), clone.getFeatures());
        assertEquals("the source keeps its own", "vf", view.getFeatures().getString("viewFeature", null));
    }

    // ---- the property: nothing a dressed source holds is left out -----------------------------------

    @Test
    public void cloneView_shouldCopyEveryAttributeAndContainedValue_whenEveryObjectOnTheViewIsFullyDressed() {
        dressEverything();

        IArchimateDiagramModel clone = clone(cloneView());

        List<EObject> sources = components(view);
        List<EObject> copies = components(clone);
        assertEquals("every object and connection is copied", sources.size(), copies.size());
        Set<String> classes = new TreeSet<>();
        List<String> differences = new ArrayList<>();
        Map<String, String> copyIdOf = new LinkedHashMap<>();
        for (int i = 0; i < sources.size(); i++) {
            copyIdOf.put(id(sources.get(i)), id(copies.get(i)));
        }
        for (int i = 0; i < sources.size(); i++) {
            classes.add(sources.get(i).eClass().getName());
            differences.addAll(differences(sources.get(i), copies.get(i), copyIdOf));
        }

        assertEquals("every kind of object and connection was dressed", Set.of("DiagramModelArchimateConnection",
                "DiagramModelArchimateObject", "DiagramModelConnection", "DiagramModelGroup", "DiagramModelImage",
                "DiagramModelNote", "DiagramModelReference"), classes);
        assertEquals("attributes or contained values the copy does not hold as the source does", List.of(),
                differences);
    }

    // ---- anchors point into the copy ----------------------------------------------------------------

    @Test
    public void cloneView_shouldAnchorTheCopyToTheCopiedTarget_whenTheSourceObjectIsAnchored() {
        IDiagramModelArchimateObject c = object(view, "vo-c", element("el-c"), 700, 320, 120, 60);
        accessor.updateViewObject(SESSION, c.getId(), null, null, null, null, null, null, null, null,
                b.getId(), "right", 5, 7);
        assertEquals("the anchor placed the source object", 825, c.getBounds().getX());

        IArchimateDiagramModel clone = clone(cloneView());

        IDiagramModelArchimateObject copiedB = objectFor(clone, elB);
        IDiagramModelArchimateObject copiedC = objectFor(clone, c.getArchimateElement());
        assertEquals(copiedB.getId(), copiedC.getFeatures().getString("anchorTarget", null));
        assertNotEquals(b.getId(), copiedC.getFeatures().getString("anchorTarget", null));
        assertEquals("right", copiedC.getFeatures().getString("anchorEdge", null));
        assertEquals("5", copiedC.getFeatures().getString("anchorDx", null));
        assertEquals("7", copiedC.getFeatures().getString("anchorDy", null));
        assertEquals("the source keeps its own", b.getId(), c.getFeatures().getString("anchorTarget", null));

        accessor.updateViewObject(SESSION, copiedB.getId(), null, null, 300, null, null, null, null, null);

        assertEquals("the copy follows the copied target", 700 + 300 + 5, copiedC.getBounds().getX());
        assertEquals("the source does not move", 825, c.getBounds().getX());
    }

    @Test
    public void cloneView_shouldAnchorTheCopyToTheCopiedTarget_whenBothAreInAGroup() {
        accessor.addGroupToView(SESSION, view.getId(), "G", 10, 500, 400, 300, null, null, null);
        IDiagramModelGroup group = only(view, IDiagramModelGroup.class);
        IDiagramModelArchimateObject t = object(group, "vo-gt", element("el-gt"), 20, 30, 100, 50);
        IDiagramModelArchimateObject c = object(group, "vo-gc", element("el-gc"), 20, 150, 100, 50);
        accessor.updateViewObject(SESSION, c.getId(), null, null, null, null, null, null, null, null,
                t.getId(), "below", 0, 40);
        assertEquals("the anchor placed the source object", 30 + 50 + 40, c.getBounds().getY());

        IArchimateDiagramModel clone = clone(cloneView());

        IDiagramModelGroup copiedGroup = only(clone, IDiagramModelGroup.class);
        IDiagramModelArchimateObject copiedT = objectFor(copiedGroup, t.getArchimateElement());
        IDiagramModelArchimateObject copiedC = objectFor(copiedGroup, c.getArchimateElement());
        assertEquals(copiedT.getId(), copiedC.getFeatures().getString("anchorTarget", null));
        assertNotEquals(t.getId(), copiedC.getFeatures().getString("anchorTarget", null));

        accessor.updateViewObject(SESSION, copiedT.getId(), null, null, null, 90, null, null, null, null);

        assertEquals("the copy follows the copied target", 30 + 90 + 40, copiedC.getBounds().getY());
        assertEquals("the source does not move", 30 + 50 + 40, c.getBounds().getY());
    }

    @Test
    public void cloneView_shouldCopyAStaleAnchorAsItIs_whenItsTargetIsOnNoView() {
        b.getFeatures().putString("anchorTarget", "id-on-no-view");
        b.getFeatures().putString("anchorEdge", "below");

        IArchimateDiagramModel clone = clone(cloneView());

        IDiagramModelArchimateObject copiedB = objectFor(clone, elB);
        assertEquals("id-on-no-view", copiedB.getFeatures().getString("anchorTarget", null));
        assertEquals("below", copiedB.getFeatures().getString("anchorEdge", null));
    }

    // ---- the model's concepts are referenced, never written -----------------------------------------

    @Test
    public void cloneView_shouldNotRenameTheConceptsOrTheReferencedView_whenTheCopyIsMade() {
        IDiagramModelReference reference = factory.createDiagramModelReference();
        reference.setReferencedModel(w);
        reference.setBounds(900, 400, 120, 55);
        view.getChildren().add(reference);
        List<String> renames = new ArrayList<>();
        AdapterImpl spy = new AdapterImpl() {
            @Override
            public void notifyChanged(Notification n) {
                if (n.getFeature() == IArchimatePackage.Literals.NAMEABLE__NAME) {
                    renames.add(((EObject) n.getNotifier()).eClass().getName());
                }
            }
        };
        elA.eAdapters().add(spy);
        ab.eAdapters().add(spy);
        w.eAdapters().add(spy);

        IArchimateDiagramModel clone = clone(cloneView());

        assertEquals("no concept or referenced view was renamed", List.of(), renames);
        assertEquals("name el-a", elA.getName());
        assertEquals("a to b", ab.getName());
        assertEquals("W", w.getName());
        assertEquals("name el-a", objectFor(clone, elA).getName());
        assertEquals("a to b", connectionFor(objectFor(clone, elA), ab).getName());
        assertEquals("W", only(clone, IDiagramModelReference.class).getName());
    }

    // ---- in a batch, undo and redo, and a save that reopens -----------------------------------------

    @Test
    public void cloneView_shouldCopyTheStyleSetEarlierInTheBatch_whenTheBatchStylesTheSourceFirst() {
        dispatcher.beginBatch(SESSION, "style then clone");
        StylingParams styling = new StylingParams(null, null, null, null, null, null, null, null, null, null, null,
                null, "left-right", null, null, null);
        accessor.updateViewObject(SESSION, a.getId(), null, null, null, null, null, styling, null, "${name} X");
        String id = cloneView();
        dispatcher.endBatch(SESSION, true);

        IDiagramModelArchimateObject copied = objectFor(clone(id), elA);
        assertEquals("${name} X", copied.getFeatures().getString("labelExpression", null));
        assertEquals(1, copied.getGradient());
    }

    @Test
    public void cloneView_shouldUndoAndRedoTheSameCopiedFeatures_whenTheSourceHasThem() {
        styleA();
        view.getFeatures().putString("viewFeature", "vf");
        IArchimateDiagramModel clone = clone(cloneView());
        IDiagramModelArchimateObject copied = objectFor(clone, elA);
        List<IFeature> objectFeatures = List.copyOf(copied.getFeatures());
        List<IFeature> viewFeatures = List.copyOf(clone.getFeatures());
        assertEquals(6, objectFeatures.size());

        stack.undo();
        assertNull(ArchimateModelUtils.getObjectByID(model, clone.getId()));
        // The clone goes back to what it held before the copy ran: the preview's metadata, made when the
        // call was prepared, not the features the copy added.
        for (IFeature feature : viewFeatures) {
            for (IFeature held : clone.getFeatures()) {
                assertNotSame("undo takes the copied view feature off the clone", feature, held);
            }
        }

        stack.redo();
        assertSame(clone, ArchimateModelUtils.getObjectByID(model, clone.getId()));
        assertSame(copied, objectFor(clone, elA));
        assertSameObjects("the same feature objects", objectFeatures, copied.getFeatures());
        assertSameObjects("the same view features", viewFeatures, clone.getFeatures());
        assertEquals("vf", clone.getFeatures().getString("viewFeature", null));
    }

    @Test
    public void cloneView_shouldReopenWithTheSameAttributesAndFeatures_whenTheModelIsSavedAndReloaded()
            throws Exception {
        dressEverything();
        String id = cloneView();

        File file = temp.newFile("clone.archimate");
        Resource saved = ArchimateResourceFactory.createNewResource(file);
        saved.getContents().add(model);
        saved.save(null);
        Resource reloaded = ArchimateResourceFactory.createResourceSet()
                .getResource(URI.createFileURI(file.getAbsolutePath()), true);

        assertEquals("reload errors", List.of(), reloaded.getErrors());
        IArchimateModel reopened = (IArchimateModel) reloaded.getContents().get(0);
        List<EObject> sources = components((IArchimateDiagramModel) ArchimateModelUtils.getObjectByID(reopened,
                view.getId()));
        List<EObject> copies = components((IArchimateDiagramModel) ArchimateModelUtils.getObjectByID(reopened, id));
        assertEquals(sources.size(), copies.size());
        Map<String, String> copyIdOf = new LinkedHashMap<>();
        for (int i = 0; i < sources.size(); i++) {
            copyIdOf.put(id(sources.get(i)), id(copies.get(i)));
        }
        List<String> differences = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            assertEquals("the same attributes are set", setAttributes(sources.get(i)), setAttributes(copies.get(i)));
            differences.addAll(differences(sources.get(i), copies.get(i), copyIdOf));
        }
        assertEquals(List.of(), differences);
    }

    // ---- fixtures -----------------------------------------------------------------------------------

    /** Styles {@code a} the way an agent would, and sets the one style no tool sets. */
    private void styleA() {
        StylingParams styling = new StylingParams(null, null, null, null, null, null, null, null, null, null, null,
                "dashed", "left-right", null, Boolean.FALSE, 128);
        accessor.updateViewObject(SESSION, a.getId(), null, null, null, null, null, styling, null, "${name} X");
        a.setIconColor("#123456");
    }

    private IDiagramModelNote dressedNote() {
        accessor.addNoteToView(SESSION, view.getId(), "note text", null, null, 10, 300, 185, 80, null, null, null);
        IDiagramModelNote note = only(view, IDiagramModelNote.class);
        StylingParams styling = new StylingParams(null, null, null, null, null, null, null, null, null, null, null,
                null, null, "rectangle", null, null);
        accessor.updateViewObject(SESSION, note.getId(), null, null, null, null, null, styling, null, null);
        note.getProperties().add(property("nk", "nv"));
        note.setName("note name");
        note.setIsLegend(true);
        return note;
    }

    private void dressConnectionAb() {
        connectionAb.setDocumentation("conn doc");
        connectionAb.getProperties().add(property("ck", "cv"));
        connectionAb.setTextAlignment(ITextAlignment.TEXT_ALIGNMENT_LEFT);
        connectionAb.getFeatures().putString("labelExpression", "${name} C");
        connectionAb.setNameVisible(false);
    }

    /**
     * Every kind of object and connection a clone copies, with every attribute it has set to a value
     * other than its default, plus a feature, a property, a bendpoint and an anchor. The attributes are
     * found at run time, so one Archi adds later is dressed too.
     */
    private void dressEverything() {
        IDiagramModelGroup group = factory.createDiagramModelGroup();
        group.setBounds(10, 500, 400, 300);
        view.getChildren().add(group);
        IDiagramModelArchimateObject nested = object(group, "vo-nested", element("el-nested"), 20, 30, 100, 50);
        IDiagramModelNote note = note(view, 10, 300);
        IDiagramModelReference reference = factory.createDiagramModelReference();
        reference.setReferencedModel(w);
        reference.setBounds(900, 400, 120, 55);
        view.getChildren().add(reference);
        IDiagramModelImage image = factory.createDiagramModelImage();
        image.setBounds(900, 600, 64, 48);
        view.getChildren().add(image);
        IDiagramModelConnection generic = genericConnection(note, a);
        IDiagramModelBendpoint bendpoint = factory.createDiagramModelBendpoint();
        bendpoint.setStartX(11);
        bendpoint.setStartY(12);
        bendpoint.setEndX(13);
        bendpoint.setEndY(14);
        connectionAb.getBendpoints().add(bendpoint);

        for (EObject component : List.of(a, b, group, nested, note, reference, image, connectionAb, generic)) {
            dressEveryAttribute(component);
            ((com.archimatetool.model.IFeatures) component).getFeatures().putString("dressed", "yes");
            EStructuralFeature properties = component.eClass().getEStructuralFeature("properties");
            if (properties != null) {
                @SuppressWarnings("unchecked")
                List<IProperty> list = (List<IProperty>) component.eGet(properties);
                list.add(property("pk", "pv"));
            }
        }
        b.getFeatures().putString("anchorTarget", a.getId());
        b.getFeatures().putString("anchorEdge", "below");
    }

    private static void dressEveryAttribute(EObject component) {
        for (EAttribute attribute : component.eClass().getEAllAttributes()) {
            if (!attribute.isChangeable() || attribute.isDerived() || notCopiedAsStored(component, attribute)) {
                continue;
            }
            Object initial = component.eGet(attribute);
            Class<?> type = attribute.getEAttributeType().getInstanceClass();
            if (type == String.class) {
                component.eSet(attribute, "dressed " + attribute.getName());
            } else if (type == int.class) {
                component.eSet(attribute, (Integer) initial + 1);
            } else if (type == boolean.class) {
                component.eSet(attribute, !(Boolean) initial);
            } else {
                fail("no non-default value to dress " + component.eClass().getName() + "." + attribute.getName()
                        + " of type " + type + " with; add one");
            }
            assertTrue(component.eClass().getName() + "." + attribute.getName() + " is set",
                    component.eIsSet(attribute));
        }
    }

    // ---- the property's comparison ------------------------------------------------------------------

    /**
     * What a copy does not hold exactly as stored, and why. Every other attribute and contained value
     * must be equal.
     *
     * <ul>
     * <li>{@code id}: every copy gets a new one (asserted different).</li>
     * <li>{@code name} on an ArchiMate object or connection, or a view reference: Archi reads it from
     * the concept or the referenced view and writes it there too (asserted equal through it).</li>
     * <li>{@code archimateElement}, {@code archimateRelationship}, {@code referencedModel}: the copy
     * points at the same concept or view (asserted the same).</li>
     * <li>{@code source}, {@code target}, {@code targetConnections}, {@code children},
     * {@code sourceConnections}: the copy's own structure, compared by walking it.</li>
     * <li>the {@code anchorTarget} feature: it names the copy of its target (asserted).</li>
     * </ul>
     */
    private static boolean notCopiedAsStored(EObject component, EAttribute attribute) {
        return attribute == IArchimatePackage.Literals.IDENTIFIER__ID
                || (attribute == IArchimatePackage.Literals.NAMEABLE__NAME
                        && (component instanceof IDiagramModelArchimateComponent
                                || component instanceof IDiagramModelReference));
    }

    private static final Set<String> STRUCTURE = Set.of("source", "target", "targetConnections", "children",
            "sourceConnections");

    private static List<String> differences(EObject source, EObject copy, Map<String, String> copyIdOf) {
        List<String> found = new ArrayList<>();
        String where = source.eClass().getName() + " " + id(source) + ".";
        assertNotSame(source, copy);
        for (EStructuralFeature feature : source.eClass().getEAllStructuralFeatures()) {
            if (STRUCTURE.contains(feature.getName())) {
                continue;
            }
            if (feature instanceof EAttribute attribute) {
                if (attribute == IArchimatePackage.Literals.IDENTIFIER__ID) {
                    assertNotEquals(where + "id", source.eGet(attribute), copy.eGet(attribute));
                } else if (notCopiedAsStored(source, attribute)) {
                    assertEquals(where + "name, read through", ((com.archimatetool.model.INameable) source).getName(),
                            ((com.archimatetool.model.INameable) copy).getName());
                } else if (source.eIsSet(attribute) != copy.eIsSet(attribute)
                        || !String.valueOf(source.eGet(attribute)).equals(String.valueOf(copy.eGet(attribute)))) {
                    found.add(where + attribute.getName() + ": " + source.eGet(attribute) + " -> "
                            + (copy.eIsSet(attribute) ? copy.eGet(attribute) : "(unset)"));
                }
            } else if (feature instanceof EReference reference && !reference.isContainment()) {
                assertSame(where + reference.getName(), source.eGet(reference), copy.eGet(reference));
            } else {
                String expected = contained(source.eGet(feature), copyIdOf, null);
                String actual = contained(copy.eGet(feature), copyIdOf, copyIdOf);
                if (!expected.equals(actual)) {
                    found.add(where + feature.getName() + ": " + expected + " -> " + actual);
                }
                assertNoSharedObject(where + feature.getName(), source.eGet(feature), copy.eGet(feature));
            }
        }
        return found;
    }

    /**
     * A contained value as text. An anchor target is written as the id the copy should hold: the
     * source's is mapped to its copy's, and the copy's is taken as it is.
     */
    private static String contained(Object value, Map<String, String> copyIdOf, Map<String, String> isCopy) {
        if (value instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object item : list) {
                parts.add(contained(item, copyIdOf, isCopy));
            }
            return parts.toString();
        }
        if (value instanceof IFeature feature && "anchorTarget".equals(feature.getName()) && isCopy == null) {
            return "Feature{anchorTarget=" + copyIdOf.getOrDefault(feature.getValue(), feature.getValue()) + "}";
        }
        if (value instanceof IFeature feature) {
            return "Feature{" + feature.getName() + "=" + feature.getValue() + "}";
        }
        if (value instanceof EObject object) {
            StringBuilder text = new StringBuilder(object.eClass().getName()).append('{');
            for (EAttribute attribute : object.eClass().getEAllAttributes()) {
                text.append(attribute.getName()).append('=').append(object.eGet(attribute)).append(',');
            }
            return text.append('}').toString();
        }
        return String.valueOf(value);
    }

    private static void assertNoSharedObject(String where, Object source, Object copy) {
        if (source instanceof List<?> sources && copy instanceof List<?> copies) {
            for (Object item : sources) {
                for (Object other : copies) {
                    assertNotSame(where + " shares a contained object", item, other);
                }
            }
        } else if (source != null) {
            assertNotSame(where + " shares a contained object", source, copy);
        }
    }

    private static Set<String> setAttributes(EObject component) {
        Set<String> names = new TreeSet<>();
        for (EAttribute attribute : component.eClass().getEAllAttributes()) {
            if (component.eIsSet(attribute)) {
                names.add(attribute.getName());
            }
        }
        return names;
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private String cloneView() {
        return accessor.cloneView(SESSION, view.getId(), "Clone", null).entity().id();
    }

    private IArchimateDiagramModel clone(String id) {
        EObject found = ArchimateModelUtils.getObjectByID(model, id);
        assertTrue("the clone is in the model: " + id, found instanceof IArchimateDiagramModel);
        return (IArchimateDiagramModel) found;
    }

    /** Every object and connection on the view, in containment order. */
    private static List<EObject> components(IArchimateDiagramModel diagram) {
        List<EObject> found = new ArrayList<>();
        for (var it = diagram.eAllContents(); it.hasNext();) {
            EObject next = it.next();
            if (next instanceof IDiagramModelObject || next instanceof IDiagramModelConnection) {
                found.add(next);
            }
        }
        return found;
    }

    private static String id(EObject component) {
        return ((com.archimatetool.model.IIdentifier) component).getId();
    }

    private static void assertFeaturesAreNewObjects(List<IFeature> source, List<IFeature> copy) {
        assertEquals("the same features", names(source), names(copy));
        for (int i = 0; i < source.size(); i++) {
            assertNotSame("a new feature", source.get(i), copy.get(i));
        }
    }

    private static void assertSameObjects(String message, List<IFeature> expected, List<IFeature> actual) {
        assertEquals(message, expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertSame(message, expected.get(i), actual.get(i));
        }
    }

    private static List<String> names(List<IFeature> features) {
        List<String> found = new ArrayList<>();
        for (IFeature feature : features) {
            found.add(feature.getName() + "=" + feature.getValue());
        }
        return found;
    }

    private static void assertProperty(List<IProperty> source, List<IProperty> copy, String key, String value) {
        assertEquals(1, copy.size());
        assertEquals(key, copy.get(0).getKey());
        assertEquals(value, copy.get(0).getValue());
        assertNotSame("a new property", source.get(0), copy.get(0));
    }

    private static <T extends IDiagramModelObject> T only(IDiagramModelContainer container, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (IDiagramModelObject child : container.getChildren()) {
            if (type.isInstance(child)) {
                found.add(type.cast(child));
            }
        }
        assertEquals("one " + type.getSimpleName() + " directly under " + container, 1, found.size());
        return found.get(0);
    }

    private static IDiagramModelArchimateObject objectFor(IDiagramModelContainer container, IArchimateElement el) {
        for (IDiagramModelObject child : container.getChildren()) {
            if (child instanceof IDiagramModelArchimateObject obj && obj.getArchimateElement() == el) {
                return obj;
            }
        }
        fail("an object for " + el.getId());
        return null;
    }

    private static IDiagramModelArchimateConnection connectionFor(IConnectable source, IArchimateRelationship rel) {
        for (IDiagramModelConnection conn : source.getSourceConnections()) {
            if (conn instanceof IDiagramModelArchimateConnection ac && ac.getArchimateRelationship() == rel) {
                return ac;
            }
        }
        fail("a connection for " + rel.getId());
        return null;
    }

    private IDiagramModelNote note(IDiagramModelContainer parent, int x, int y) {
        IDiagramModelNote note = factory.createDiagramModelNote();
        note.setContent("note text");
        note.setBounds(x, y, 185, 80);
        parent.getChildren().add(note);
        return note;
    }

    private IDiagramModelConnection genericConnection(IConnectable source, IConnectable target) {
        IDiagramModelConnection generic = factory.createDiagramModelConnection();
        generic.setName("generic label");
        generic.setTextAlignment(ITextAlignment.TEXT_ALIGNMENT_RIGHT);
        generic.setNameVisible(false);
        generic.connect(source, target);
        assertNotNull(generic.getSource());
        return generic;
    }

    private IProperty property(String key, String value) {
        IProperty property = factory.createProperty();
        property.setKey(key);
        property.setValue(value);
        return property;
    }

    private IArchimateElement element(String id) {
        IArchimateElement el = factory.createApplicationComponent();
        el.setId(id);
        el.setName("name " + id);
        model.getFolder(FolderType.APPLICATION).getElements().add(el);
        return el;
    }

    private IDiagramModelArchimateObject object(IDiagramModelContainer parent, String id,
            IArchimateElement el, int x, int y, int width, int height) {
        IDiagramModelArchimateObject obj = factory.createDiagramModelArchimateObject();
        obj.setId(id);
        obj.setArchimateElement(el);
        obj.setBounds(x, y, width, height);
        parent.getChildren().add(obj);
        return obj;
    }

    private static Command toPlainCompound(Command command) {
        if (command instanceof RequireAttachedContainerCommand guard) {
            return guard.withGuarded(toPlainCompound(guard.getGuarded()));
        }
        if (command instanceof CompoundCommand compound) {
            CompoundCommand plain = new CompoundCommand(compound.getLabel());
            for (Object child : compound.getCommands()) {
                plain.add(toPlainCompound((Command) child));
            }
            return plain;
        }
        return command;
    }
}
