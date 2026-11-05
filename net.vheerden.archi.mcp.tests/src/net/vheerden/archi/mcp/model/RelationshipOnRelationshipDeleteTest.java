package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.util.ArchimateResourceFactory;

import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.DeleteResultDto;

/**
 * Pins that deleting an element or a relationship removes every relationship attached to a removed
 * relationship, and every connection drawn from or to a removed connection, as Archi's own delete
 * does.
 *
 * <p>The fixture chains three relationships: Ra joins E to F, Rb joins Ra to G, Rc joins Rb to H.
 * One view draws them as a chain of connections: c1 from E's box to F's, c2 from c1 to G's box, c3
 * from c2 to H's. X joins F to G and cX draws it; nothing of X is attached to the chain, so it is the
 * control. Before the fix, deleting E or Ra left Rb with an end no longer in the model, and the saved
 * file did not open again; c2 and c3 left with c1, but G's and H's boxes still listed them.</p>
 */
public class RelationshipOnRelationshipDeleteTest {

    private static final String SESSION = "relationship-on-relationship-delete-session";

    /** What Archi's own delete of E removes from this fixture, measured in Archi. */
    private static final List<String> ARCHIS_DELETE_OF_E =
            List.of("E", "Ra", "Rb", "Rc", "c1", "c2", "c3", "voE");

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private IFolder relations;
    private IArchimateElement e;
    private IArchimateRelationship ra;
    private IArchimateRelationship rb;
    private IArchimateRelationship rc;
    private IDiagramModelArchimateObject voG;
    private IDiagramModelArchimateObject voH;
    private MutationDispatcher dispatcher;
    private ArchiModelAccessorImpl accessor;
    private CommandStack stack;

    @Before
    public void setUp() {
        model = factory.createArchimateModel();
        model.setId("model-relationship-on-relationship");
        model.setDefaults();
        relations = model.getFolder(FolderType.RELATIONS);
        view = factory.createArchimateDiagramModel();
        view.setId("V");
        view.setName("V");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        e = element("E");
        IArchimateElement f = element("F");
        IArchimateElement g = element("G");
        IArchimateElement h = element("H");
        ra = association("Ra", e, f);
        IArchimateRelationship x = association("X", f, g);
        rb = association("Rb", ra, g);
        rc = association("Rc", rb, h);
        IDiagramModelArchimateObject voE = object("voE", e, 0, 0);
        IDiagramModelArchimateObject voF = object("voF", f, 400, 0);
        voG = object("voG", g, 200, 200);
        voH = object("voH", h, 500, 300);
        IDiagramModelArchimateConnection c1 = connection("c1", ra, voE, voF);
        IDiagramModelArchimateConnection c2 = connection("c2", rb, c1, voG);
        connection("c3", rc, c2, voH);
        connection("cX", x, voF, voG);

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

    // ---- deleting an element ------------------------------------------------------------------------

    @Test
    public void immediate_shouldRemoveWhatArchisOwnDeleteRemoves_whenAnElementIsDeleted() throws Exception {
        TreeSet<String> before = ids();
        List<String> order = order();
        DeleteResultDto dto = accessor.deleteElement(SESSION, "E").entity();

        assertEquals("removed exactly what Archi's delete removes", ARCHIS_DELETE_OF_E, removedSince(before));
        assertEquals("every relationship of the chain is counted", 3, dto.relationshipsRemoved());
        assertEquals("every connection of the chain is counted", 3, dto.viewConnectionsRemoved());
        assertNoEndOutsideTheModel();
        assertUndoRestoresOrder_andRedoRemovesTheSameSet(order, before);
        assertReopens();
    }

    @Test
    public void batch_shouldRemoveTheWholeChain_whenAnElementIsDeleted() throws Exception {
        TreeSet<String> before = ids();
        List<String> order = order();
        dispatcher.beginBatch(SESSION, "delete E");
        accessor.deleteElement(SESSION, "E");
        dispatcher.endBatch(SESSION, true);

        assertEquals(ARCHIS_DELETE_OF_E, removedSince(before));
        assertNoEndOutsideTheModel();
        assertUndoRestoresOrder_andRedoRemovesTheSameSet(order, before);
        assertReopens();
    }

    @Test
    public void bulk_shouldRemoveTheWholeChain_whenAnElementIsDeleted() throws Exception {
        TreeSet<String> before = ids();
        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("delete-element", Map.of("elementId", "E"))), "delete E", false, null);

        assertTrue(result.allSucceeded());
        assertEquals(ARCHIS_DELETE_OF_E, removedSince(before));
        assertNoEndOutsideTheModel();
        assertReopens();
    }

    // ---- deleting a relationship --------------------------------------------------------------------

    @Test
    public void immediate_shouldRemoveTheRelationshipsAttachedToIt_whenARelationshipIsDeleted() throws Exception {
        TreeSet<String> before = ids();
        List<String> order = order();
        DeleteResultDto dto = accessor.deleteRelationship(SESSION, "Ra").entity();

        assertEquals(List.of("Ra", "Rb", "Rc", "c1", "c2", "c3"), removedSince(before));
        assertEquals("the attached relationships are counted", 2, dto.relationshipsRemoved());
        assertEquals("every connection of the chain is counted", 3, dto.viewConnectionsRemoved());
        assertNoEndOutsideTheModel();
        assertUndoRestoresOrder_andRedoRemovesTheSameSet(order, before);
        assertReopens();
    }

    @Test
    public void batch_shouldRemoveTheRelationshipsAttachedToIt_whenARelationshipIsDeleted() throws Exception {
        TreeSet<String> before = ids();
        List<String> order = order();
        dispatcher.beginBatch(SESSION, "delete Ra");
        accessor.deleteRelationship(SESSION, "Ra");
        dispatcher.endBatch(SESSION, true);

        assertEquals(List.of("Ra", "Rb", "Rc", "c1", "c2", "c3"), removedSince(before));
        assertNoEndOutsideTheModel();
        assertUndoRestoresOrder_andRedoRemovesTheSameSet(order, before);
        assertReopens();
    }

    @Test
    public void bulk_shouldRemoveTheRelationshipsAttachedToIt_whenARelationshipIsDeleted() throws Exception {
        TreeSet<String> before = ids();
        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("delete-relationship", Map.of("relationshipId", "Ra"))), "delete Ra", false, null);

        assertTrue(result.allSucceeded());
        assertEquals(List.of("Ra", "Rb", "Rc", "c1", "c2", "c3"), removedSince(before));
        assertNoEndOutsideTheModel();
        assertReopens();
    }

    @Test
    public void shouldRemoveTheConnectionDrawnFromAnotherConnection_whenTheLastRelationshipIsDeleted()
            throws Exception {
        TreeSet<String> before = ids();
        List<String> order = order();
        DeleteResultDto dto = accessor.deleteRelationship(SESSION, "Rc").entity();

        assertEquals("c3 is drawn from c2, not from a box, and goes with Rc", List.of("Rc", "c3"),
                removedSince(before));
        assertEquals(0, dto.relationshipsRemoved());
        assertEquals(1, dto.viewConnectionsRemoved());
        assertNoEndOutsideTheModel();
        assertUndoRestoresOrder_andRedoRemovesTheSameSet(order, before);
        assertReopens();
    }

    @Test
    public void shouldRemoveOnlyItsOwnConnection_whenNothingIsAttachedToTheRelationship() throws Exception {
        TreeSet<String> before = ids();
        DeleteResultDto dto = accessor.deleteRelationship(SESSION, "X").entity();

        assertEquals(List.of("X", "cX"), removedSince(before));
        assertEquals(0, dto.relationshipsRemoved());
        assertEquals(1, dto.viewConnectionsRemoved());
        assertReopens();
    }

    // ---- deleting a folder of relationships ---------------------------------------------------------

    @Test
    public void folder_shouldCountEachRelationshipAndConnectionOnce_whateverTheOrder() throws Exception {
        IFolder doomed = factory.createFolder();
        doomed.setId("doomed");
        doomed.setName("doomed");
        relations.getFolders().add(doomed);
        doomed.getElements().add(rb); // Rb first: Ra's delete takes Rb too
        doomed.getElements().add(ra);
        TreeSet<String> before = ids();

        DeleteResultDto dto = accessor.deleteFolder(SESSION, "doomed", true).entity();

        assertEquals(List.of("Ra", "Rb", "Rc", "c1", "c2", "c3", "doomed"), removedSince(before));
        assertEquals("Ra, Rb and Rc, each once", 3, dto.relationshipsRemoved());
        assertEquals("c1, c2 and c3, each once", 3, dto.viewConnectionsRemoved());
        assertNoEndOutsideTheModel();
        assertReopens();
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /** No box and no connection lists a connection, and no relationship has an end, outside the model. */
    @Test
    public void shouldRemoveANoteLineDrawnToARemovedConnection_whenARelationshipIsDeleted() throws Exception {
        IDiagramModelNote note = note("N", view);
        plain("nc2", note, (IConnectable) find("c2"));
        TreeSet<String> before = ids();
        List<String> order = order();

        DeleteResultDto dto = accessor.deleteRelationship(SESSION, "Ra").entity();

        assertEquals("the note stays; its line to c2 goes with c2", List.of("Ra", "Rb", "Rc", "c1", "c2", "c3", "nc2"),
                removedSince(before));
        assertEquals(4, dto.viewConnectionsRemoved());
        assertNoEndOutsideTheModel();
        assertUndoRestoresOrder_andRedoRemovesTheSameSet(order, before);
        assertReopens();
    }

    @Test
    public void shouldRemoveNoteLinesToTheRemovedBoxAndToWhatIsNestedInIt_whenAnElementIsDeleted() throws Exception {
        IDiagramModelArchimateObject voE = (IDiagramModelArchimateObject) find("voE");
        IDiagramModelNote outside = note("N", view);
        IDiagramModelNote inside = note("Nin", voE);
        plain("nE", outside, voE);
        plain("nIn", inside, voG);
        plain("nOut", voH, inside);
        TreeSet<String> before = ids();
        List<String> order = order();

        accessor.deleteElement(SESSION, "E").entity();

        List<String> expected = new ArrayList<>(ARCHIS_DELETE_OF_E);
        expected.addAll(List.of("Nin", "nE", "nIn", "nOut"));
        expected.sort(null);
        assertEquals("the lines to the box and to the note inside it go with the box", expected,
                removedSince(before));
        assertNoEndOutsideTheModel();
        assertUndoRestoresOrder_andRedoRemovesTheSameSet(order, before);
        assertReopens();
    }

    private void assertNoEndOutsideTheModel() {
        for (Iterator<EObject> it = model.eAllContents(); it.hasNext();) {
            EObject o = it.next();
            if (o instanceof IConnectable connectable) {
                for (IDiagramModelConnection conn : connectable.getTargetConnections()) {
                    assertTrue(((IIdentifier) o).getId() + " lists " + conn.getId(), inModel(conn));
                }
                for (IDiagramModelConnection conn : connectable.getSourceConnections()) {
                    assertTrue(((IIdentifier) o).getId() + " lists " + conn.getId(), inModel(conn));
                    assertTrue(conn.getId() + " target", inModel(conn.getTarget()));
                }
            }
            if (o instanceof IArchimateRelationship rel) {
                assertTrue(rel.getId() + " source", inModel(rel.getSource()));
                assertTrue(rel.getId() + " target", inModel(rel.getTarget()));
            }
        }
    }

    private boolean inModel(EObject object) {
        for (EObject current = object; current != null; current = current.eContainer()) {
            if (current == model) {
                return true;
            }
        }
        return false;
    }

    private List<String> removedSince(TreeSet<String> before) {
        TreeSet<String> removed = new TreeSet<>(before);
        removed.removeAll(ids());
        return List.copyOf(removed);
    }

    private void assertUndoRestoresOrder_andRedoRemovesTheSameSet(List<String> order, TreeSet<String> before) {
        TreeSet<String> after = ids();
        stack.undo();
        assertEquals("undo restores membership and order", order, order());
        assertEquals("undo restores every id", before, ids());
        stack.redo();
        assertEquals("redo removes exactly the same set", after, ids());
        assertNoEndOutsideTheModel();
    }

    private List<String> order() {
        List<String> lines = new ArrayList<>();
        for (Iterator<EObject> it = model.eAllContents(); it.hasNext();) {
            EObject o = it.next();
            if (o instanceof IFolder folder) {
                lines.add(folder.getName() + ": " + folder.getElements().stream()
                        .map(el -> ((IIdentifier) el).getId()).toList());
            } else if (o instanceof IArchimateDiagramModel diagram) {
                lines.add(diagram.getId() + ": " + diagram.getChildren().stream().map(IIdentifier::getId).toList());
            } else if (o instanceof IArchimateRelationship r) {
                lines.add(r.getId() + ": " + r.getSource().getId() + " -> " + r.getTarget().getId());
            }
            if (o instanceof IArchimateConcept concept) {
                lines.add(concept.getId() + " relationships out: " + concept.getSourceRelationships().stream()
                        .map(IIdentifier::getId).sorted().toList() + " in: " + concept.getTargetRelationships().stream()
                        .map(IIdentifier::getId).sorted().toList());
            }
            if (o instanceof IConnectable connectable) {
                lines.add(((IIdentifier) o).getId() + " out: " + connectable.getSourceConnections().stream()
                        .map(IIdentifier::getId).toList() + " in: " + connectable.getTargetConnections().stream()
                        .map(IIdentifier::getId).sorted().toList());
            }
        }
        return lines;
    }

    private TreeSet<String> ids() {
        TreeSet<String> ids = new TreeSet<>();
        for (Iterator<EObject> it = model.eAllContents(); it.hasNext();) {
            if (it.next() instanceof IIdentifier id) {
                ids.add(id.getId());
            }
        }
        return ids;
    }

    /** The model saves and reopens with no unresolved reference. */
    private void assertReopens() throws Exception {
        File file = temp.newFile();
        Resource saved = ArchimateResourceFactory.createNewResource(file);
        saved.getContents().add(model);
        saved.save(null);
        Resource reloaded = ArchimateResourceFactory.createResourceSet()
                .getResource(URI.createFileURI(file.getAbsolutePath()), true);
        assertEquals("reload errors", List.of(), reloaded.getErrors());
    }

    private EObject find(String id) {
        for (Iterator<EObject> it = model.eAllContents(); it.hasNext();) {
            EObject o = it.next();
            if (o instanceof IIdentifier identified && id.equals(identified.getId())) {
                return o;
            }
        }
        throw new AssertionError("fixture check: no " + id);
    }

    private IDiagramModelNote note(String id, IDiagramModelContainer in) {
        IDiagramModelNote note = factory.createDiagramModelNote();
        note.setId(id);
        note.setBounds(10, 10, 80, 40);
        in.getChildren().add(note);
        return note;
    }

    private void plain(String id, IConnectable source, IConnectable target) {
        IDiagramModelConnection conn = factory.createDiagramModelConnection();
        conn.setId(id);
        conn.connect(source, target);
    }

    private IArchimateElement element(String id) {
        IArchimateElement element = factory.createApplicationComponent();
        element.setId(id);
        element.setName(id);
        model.getFolder(FolderType.APPLICATION).getElements().add(element);
        return element;
    }

    private IArchimateRelationship association(String id, IArchimateConcept source, IArchimateConcept target) {
        IArchimateRelationship rel = factory.createAssociationRelationship();
        rel.setId(id);
        rel.setName(id);
        rel.connect(source, target);
        relations.getElements().add(rel);
        return rel;
    }

    private IDiagramModelArchimateObject object(String id, IArchimateElement element, int x, int y) {
        IDiagramModelArchimateObject object = factory.createDiagramModelArchimateObject();
        object.setId(id);
        object.setArchimateElement(element);
        object.setBounds(x, y, 120, 60);
        view.getChildren().add(object);
        return object;
    }

    private IDiagramModelArchimateConnection connection(String id, IArchimateRelationship rel, IConnectable source,
            IConnectable target) {
        IDiagramModelArchimateConnection conn = factory.createDiagramModelArchimateConnection();
        conn.setId(id);
        conn.setArchimateRelationship(rel);
        conn.connect(source, target);
        return conn;
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
