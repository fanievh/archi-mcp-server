package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.util.ArchimateResourceFactory;

import net.vheerden.archi.mcp.response.dto.AddToViewResultDto;
import net.vheerden.archi.mcp.response.dto.BatchSummaryDto;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;

/**
 * A placement, a relationship or an update queued after an operation that deletes the concept it names,
 * or after the operation that was to create that concept declined, declines at commit instead of writing
 * a diagram object, a connection or a relationship that points at something not in the model.
 *
 * <p>Before, the queued operation ran against the detached concept: the saved file held a reference to
 * an id it did not contain and failed to reload, and the commit reported success. An update wrote into
 * the detached object and reported success too. Each case asserts the whole decline sentence, that the
 * committed model holds no reference to anything outside it and reloads, and that the rest of the batch
 * committed. The immediate arms are the control: every guard is inert while the concept is in the
 * model.</p>
 */
public class ConceptGoneBeforeCommitTest {

    private static final String SESSION = "concept-gone-before-commit";
    private static final String REMOVED = "It was removed after this change was prepared (by an earlier operation "
            + "in the same request, or an edit in Archi), or the operation that was to create it did not.";

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private final IArchimateFactory factory = IArchimateFactory.eINSTANCE;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private IArchimateElement e;
    private IArchimateElement t;
    private IArchimateElement g;
    private IDiagramModelArchimateObject voE;
    private IDiagramModelArchimateObject voT;
    private IDiagramModelArchimateObject voG;
    private IArchimateRelationship r;
    private IDiagramModelArchimateConnection drawnR;
    private MutationDispatcher dispatcher;
    private ArchiModelAccessorImpl accessor;
    private CommandStack stack;

    @Before
    public void setUp() {
        model = factory.createArchimateModel();
        model.setId("model-concept-gone");
        model.setDefaults();
        view = factory.createArchimateDiagramModel();
        view.setId("view");
        view.setName("View");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        e = element("E");
        t = element("T");
        g = element("G");
        voE = object(view, "vo-e", e, 0);
        voT = object(view, "vo-t", t, 300);
        voG = object(view, "vo-g", g, 600);
        r = factory.createAssociationRelationship();
        r.setId("R");
        r.setName("R");
        r.connect(e, t);
        model.getFolder(FolderType.RELATIONS).getElements().add(r);
        drawnR = factory.createDiagramModelArchimateConnection();
        drawnR.setId("conn-r");
        drawnR.setArchimateRelationship(r);
        drawnR.connect(voE, voT);

        AutoRouteArmFixture.StubEditorModelManager models = new AutoRouteArmFixture.StubEditorModelManager();
        models.setModels(List.of(model));
        stack = new CommandStack();
        model.setAdapter(CommandStack.class, stack);
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                stack.execute(new AgentAuthoredCompoundCommand(plain(command)));
            }

            @Override
            protected void dispatchCommand(Command command) {
                stack.execute(new AgentAuthoredCompoundCommand(plain(command)));
            }
        };
        dispatcher.setApprovalModeProvider(() -> false);
        accessor = new ArchiModelAccessorImpl(models, dispatcher);
        dispatcher.onModelActive(model);
    }

    @After
    public void tearDown() {
        accessor.dispose();
    }

    // ---- a placement of a concept an earlier operation deleted ------------------------------------------

    @Test
    public void batch_shouldDeclineAnAddToView_whenAnEarlierOperationDeletedItsElement() throws Exception {
        dispatcher.beginBatch(SESSION, "delete E, then draw it");
        accessor.deleteElement(SESSION, "E");
        accessor.addToView(SESSION, "view", "E", 0, 200, 120, 60, false, null, null, null);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals(1, summary.skippedOperations().size());
        assertTrue(summary.skippedOperations().get(0), summary.skippedOperations().get(0).endsWith(
                drawnSentence("element", "E")));
        assertNoReferenceOutsideTheModel();
        assertReopens();
    }

    @Test
    public void batch_shouldDeclineAnAddConnectionToView_whenAnEarlierOperationDeletedItsRelationship()
            throws Exception {
        drawnR.disconnect();
        dispatcher.beginBatch(SESSION, "delete R, then draw it");
        accessor.deleteRelationship(SESSION, "R");
        accessor.addConnectionToView(SESSION, "view", "R", "vo-e", "vo-t", null, null, null, null, null);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals(1, summary.skippedOperations().size());
        assertTrue(summary.skippedOperations().get(0), summary.skippedOperations().get(0).endsWith(
                drawnSentence("relationship", "R")));
        assertNoReferenceOutsideTheModel();
        assertReopens();
    }

    /**
     * The auto-connect scan reads the relationships as the batch leaves them, so it does not propose a
     * line for one the batch deleted earlier, and nothing is left to decline at commit. Each line it
     * does propose still carries the guard the other pins here exercise.
     */
    @Test
    public void batch_shouldNotProposeAnAutoConnection_whenAnEarlierOperationDeletedItsRelationship() throws Exception {
        drawnR.disconnect();
        view.getChildren().remove(voT);
        dispatcher.beginBatch(SESSION, "delete R, then draw T with its connections");
        accessor.deleteRelationship(SESSION, "R");
        AddToViewResultDto placed = accessor.addToView(SESSION, "view", "T", 300, 0, 120, 60, true, null, null, null)
                .entity();
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals("no line for the relationship the batch deletes", List.of(), placed.autoConnections());
        assertNull("so nothing declines at commit", summary.skippedOperations());
        assertTrue("T is drawn", drawsElement("T"));
        assertNoReferenceOutsideTheModel();
        assertReopens();
    }

    @Test
    public void batch_shouldDeclineAnAutoConnectView_whenAnEarlierOperationDeletedItsRelationship() throws Exception {
        drawnR.disconnect();
        dispatcher.beginBatch(SESSION, "delete R, then connect the view");
        accessor.deleteRelationship(SESSION, "R");
        accessor.autoConnectView(SESSION, "view", null, null, null, null, null);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertTrue(String.valueOf(summary.skippedOperations()), summary.skippedOperations().stream()
                .anyMatch(skip -> skip.endsWith(drawnSentence("relationship", "R"))));
        assertNoReferenceOutsideTheModel();
        assertReopens();
    }

    @Test
    public void bulk_shouldDeclineAnAddToView_whenAnEarlierOperationDeletedItsElement() throws Exception {
        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("delete-element", Map.of("elementId", "G")),
                new BulkOperation("add-to-view", Map.of("viewId", "view", "elementId", "G", "x", 0, "y", 300))),
                "delete G, then draw it", true);

        assertEquals(1, result.skippedOperations().size());
        assertTrue(result.skippedOperations().get(0), result.skippedOperations().get(0).endsWith(
                drawnSentence("element", "G")));
        assertNoReferenceOutsideTheModel();
        assertReopens();
    }

    @Test
    public void bulk_shouldDeclineAnAddConnectionToView_whenAnEarlierOperationDeletedItsRelationship()
            throws Exception {
        drawnR.disconnect();
        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("delete-relationship", Map.of("relationshipId", "R")),
                new BulkOperation("add-connection-to-view", Map.of("viewId", "view", "relationshipId", "R",
                        "sourceViewObjectId", "vo-e", "targetViewObjectId", "vo-t"))),
                "delete R, then draw it", true);

        assertEquals(1, result.skippedOperations().size());
        assertTrue(result.skippedOperations().get(0), result.skippedOperations().get(0).endsWith(
                drawnSentence("relationship", "R")));
        assertNoReferenceOutsideTheModel();
        assertReopens();
    }

    @Test
    public void bulk_shouldDeclineAnAddToView_whenTheElementsOwnCreateDeclined() throws Exception {
        IFolder doomed = factory.createFolder();
        doomed.setId("doomed");
        doomed.setName("Doomed");
        model.getFolder(FolderType.APPLICATION).getFolders().add(doomed);

        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("delete-folder", Map.of("folderId", "doomed")),
                new BulkOperation("create-element", Map.of("type", "ApplicationComponent", "name", "Never",
                        "folderId", "doomed")),
                new BulkOperation("add-to-view", Map.of("viewId", "view", "elementId", "$1.id", "x", 0, "y", 300))),
                "a create that declines, then a placement of it", true);

        assertEquals(result.skippedOperations().toString(), 2, result.skippedOperations().size());
        assertTrue(result.skippedOperations().get(1), result.skippedOperations().get(1).endsWith(
                drawnSentence("element", "Never")));
        assertNoReferenceOutsideTheModel();
        assertReopens();
    }

    // ---- a relationship to an element an earlier operation deleted --------------------------------------

    /**
     * Through the accessor, create-relationship cannot run headless (Archi's relationship matrix loads from
     * the running platform), so the guard prepare wraps around its command is run here as prepare builds it,
     * after a delete of the source in the same entry.
     */
    @Test
    public void shouldDeclineARelationship_whenAnEarlierOperationDeletedAnEnd() throws Exception {
        IArchimateRelationship created = factory.createAssociationRelationship();
        created.setId("NR");
        CompoundCommand both = new CompoundCommand();
        both.add(new DeleteElementCommand(g, DeleteCascade.ofElement(g)));
        RequireAttachedContainerCommand joined = (RequireAttachedContainerCommand) RequireAttachedContainerCommand
                .joining(new CreateRelationshipCommand(created, model.getFolder(FolderType.RELATIONS), g, t), g, t,
                        model, "NR");
        both.add(joined);
        stack.execute(new AgentAuthoredCompoundCommand(both));

        assertEquals("'NR' was not created: its source element 'G' is not in the model. " + REMOVED
                + " A relationship to an element the model does not hold would have pointed at nothing on reload, "
                + "so nothing was created and nothing else was affected. Create it in a separate request, or "
                + "connect elements the request does not delete.", CommitSkippableCommand.collectSkipReasons(joined)
                        .get(0));
        assertNull("nothing was created", created.eContainer());
        assertNoReferenceOutsideTheModel();
        assertReopens();
    }

    @Test
    public void shouldNameTheTarget_whenOnlyTheTargetIsGone() {
        IArchimateRelationship created = factory.createAssociationRelationship();
        CompoundCommand both = new CompoundCommand();
        both.add(new DeleteElementCommand(g, DeleteCascade.ofElement(g)));
        Command joined = RequireAttachedContainerCommand.joining(
                new CreateRelationshipCommand(created, model.getFolder(FolderType.RELATIONS), t, g), t, g, model,
                "Association relationship");
        both.add(joined);
        stack.execute(new AgentAuthoredCompoundCommand(both));

        assertTrue(CommitSkippableCommand.collectSkipReasons(joined).get(0).startsWith(
                "'Association relationship' was not created: its target element 'G' is not in the model."));
    }

    /** Every create-relationship arm (the tool, get-or-create, bulk) returns through the one shared prepare. */
    @Test
    public void shouldGuardBothEnds_inTheSharedCreateRelationshipPrepare() throws Exception {
        String source = Files.readString(repoFile(
                "net.vheerden.archi.mcp/src/net/vheerden/archi/mcp/model/ArchiModelAccessorImpl.java"));
        int shared = source.indexOf("private PreparedMutation<RelationshipDto> prepareCreateRelationship(\n"
                + "            String sessionId, String type, EClass relClass,");
        assertTrue("the shared prepare is where it was", shared > 0);
        int end = source.indexOf("\n    }\n", shared);
        String body = source.substring(shared, end);
        assertTrue(body, body.contains("RequireAttachedContainerCommand.joining(cmd, sourceElement, targetElement, "
                + "model,"));
    }

    // ---- an update of a concept or object an earlier operation deleted ---------------------------------

    @Test
    public void batch_shouldDeclineAnUpdateElement_whenAnEarlierOperationDeletedIt() throws Exception {
        dispatcher.beginBatch(SESSION, "delete G, then rename it");
        accessor.deleteElement(SESSION, "G");
        accessor.updateElement(SESSION, "G", "renamed", null, null, null);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals(List.of(), skippedTails(summary.skippedOperations(), updatedSentence("element", "G")));
        assertEquals("the detached element is not written", "G", g.getName());
        assertReopens();
    }

    @Test
    public void batch_shouldDeclineAnUpdateRelationship_whenAnEarlierOperationDeletedIt() throws Exception {
        dispatcher.beginBatch(SESSION, "delete R, then rename it");
        accessor.deleteRelationship(SESSION, "R");
        accessor.updateRelationship(SESSION, "R", "renamed", null, null, null);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals(List.of(), skippedTails(summary.skippedOperations(), updatedSentence("relationship", "R")));
        assertEquals("R", r.getName());
        assertReopens();
    }

    @Test
    public void batch_shouldDeclineAnUpdateViewObject_whenAnEarlierOperationRemovedIt() throws Exception {
        voG.setName("vo-g");
        dispatcher.beginBatch(SESSION, "delete G, then move its box");
        accessor.deleteElement(SESSION, "G");
        accessor.updateViewObject(SESSION, "vo-g", 5, 5, null, null, null, null, null, null);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals(1, summary.skippedOperations().size());
        assertTrue(summary.skippedOperations().get(0), summary.skippedOperations().get(0).contains(
                "was not updated: it is not in the model."));
        assertEquals("the detached box is not moved", 600, voG.getBounds().getX());
        assertReopens();
    }

    @Test
    public void batch_shouldDeclineAnUpdateViewConnection_whenAnEarlierOperationRemovedIt() throws Exception {
        dispatcher.beginBatch(SESSION, "delete R, then restyle its connection");
        accessor.deleteRelationship(SESSION, "R");
        accessor.updateViewConnection(SESSION, "conn-r", null, null, null, Boolean.FALSE, null);
        BatchSummaryDto summary = dispatcher.endBatch(SESSION, true);

        assertEquals(1, summary.skippedOperations().size());
        assertTrue(summary.skippedOperations().get(0), summary.skippedOperations().get(0).contains(
                "was not updated: it is not in the model."));
        assertReopens();
    }

    // ---- control: immediate calls, with the concept in the model -----------------------------------------

    @Test
    public void immediate_shouldDrawUpdateAndConnect_asBefore() throws Exception {
        drawnR.disconnect();
        view.getChildren().remove(voG);

        assertNotNull(accessor.addToView(SESSION, "view", "G", 600, 0, 120, 60, false, null, null, null).entity());
        assertNotNull(accessor.addConnectionToView(SESSION, "view", "R", "vo-e", "vo-t", null, null, null, null,
                null).entity());
        accessor.updateElement(SESSION, "G", "renamed", null, null, null);
        accessor.updateRelationship(SESSION, "R", "renamed", null, null, null);

        assertTrue(drawsElement("G"));
        assertEquals("renamed", g.getName());
        assertEquals("renamed", r.getName());
        assertSame(r, ((IDiagramModelArchimateConnection) voE.getSourceConnections().get(0)).getArchimateRelationship());
        assertNoReferenceOutsideTheModel();
        assertReopens();
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private static String drawnSentence(String kind, String name) {
        return "The " + kind + " '" + name + "' was not added to the view: it is not in the model. " + REMOVED
                + " A view object or connection for a concept the model does not hold would have pointed at "
                + "nothing on reload, so nothing was added and nothing else was affected. Add it in a separate "
                + "request, or draw a concept the request does not delete.";
    }

    private static String updatedSentence(String kind, String name) {
        return "The " + kind + " '" + name + "' was not updated: it is not in the model. " + REMOVED
                + " Nothing was changed.";
    }

    /** The skipped lines that do not end with {@code sentence}, and a line for an empty list. */
    private static List<String> skippedTails(List<String> skipped, String sentence) {
        List<String> wrong = new ArrayList<>();
        if (skipped == null || skipped.size() != 1) {
            wrong.add("expected one skipped operation, got " + skipped);
            return wrong;
        }
        if (!skipped.get(0).endsWith(sentence)) {
            wrong.add(skipped.get(0));
        }
        return wrong;
    }

    private boolean drawsElement(String id) {
        for (Iterator<EObject> it = model.eAllContents(); it.hasNext();) {
            if (it.next() instanceof IDiagramModelArchimateObject object
                    && id.equals(object.getArchimateElement().getId())) {
                return true;
            }
        }
        return false;
    }

    /** No diagram object, connection or relationship refers to a concept or object outside the model. */
    private void assertNoReferenceOutsideTheModel() {
        List<String> outside = new ArrayList<>();
        for (Iterator<EObject> it = model.eAllContents(); it.hasNext();) {
            EObject o = it.next();
            if (o instanceof IDiagramModelArchimateObject object && !inModel(object.getArchimateElement())) {
                outside.add("object " + object.getId() + " draws " + object.getArchimateElement().getId());
            }
            if (o instanceof IDiagramModelArchimateConnection c && (!inModel(c.getArchimateRelationship())
                    || !inModel(c.getSource()) || !inModel(c.getTarget()))) {
                outside.add("connection " + c.getId());
            }
            if (o instanceof IArchimateRelationship rel && (!inModel(rel.getSource()) || !inModel(rel.getTarget()))) {
                outside.add("relationship " + rel.getId());
            }
        }
        assertEquals(List.of(), outside);
    }

    private boolean inModel(EObject object) {
        for (EObject current = object; current != null; current = current.eContainer()) {
            if (current == model) {
                return true;
            }
        }
        return false;
    }

    private void assertReopens() throws Exception {
        File file = temp.newFile();
        Resource saved = ArchimateResourceFactory.createNewResource(file);
        saved.getContents().add(model);
        saved.save(null);
        Resource reloaded = ArchimateResourceFactory.createResourceSet()
                .getResource(URI.createFileURI(file.getAbsolutePath()), true);
        assertEquals("reload errors", List.of(), reloaded.getErrors());
        saved.getContents().clear();
    }

    private IArchimateElement element(String id) {
        IArchimateElement element = factory.createApplicationComponent();
        element.setId(id);
        element.setName(id);
        model.getFolder(FolderType.APPLICATION).getElements().add(element);
        return element;
    }

    private IDiagramModelArchimateObject object(IArchimateDiagramModel container, String id,
            IArchimateConcept element, int x) {
        IDiagramModelArchimateObject object = factory.createDiagramModelArchimateObject();
        object.setId(id);
        object.setArchimateElement((IArchimateElement) element);
        object.setBounds(x, 0, 120, 60);
        container.getChildren().add(object);
        return object;
    }

    private static Path repoFile(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve(relative))) {
            dir = dir.getParent();
        }
        assertNotNull("repository root not found above the working directory", dir);
        return dir.resolve(relative);
    }

    private static Command plain(Command command) {
        if (command instanceof RequireAttachedContainerCommand guard) {
            return guard.withGuarded(plain(guard.getGuarded()));
        }
        if (command instanceof CompoundCommand compound) {
            CompoundCommand plain = new CompoundCommand(compound.getLabel());
            for (Object child : compound.getCommands()) {
                plain.add(plain((Command) child));
            }
            return plain;
        }
        return command;
    }

    @SuppressWarnings("unused")
    private static IIdentifier unusedIdentifier(IConnectable c) {
        return c;
    }
}
