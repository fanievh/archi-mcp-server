package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.gef.commands.CompoundCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelNote;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.response.dto.BendpointDto;
import net.vheerden.archi.mcp.response.dto.ProposalDto;

/**
 * Pins the approval card's two prose fields end to end — through a real
 * {@link ArchiModelAccessorImpl} in approval mode, read back off the same {@link ProposalDto} the
 * {@code list-pending-approvals} tool returns.
 *
 * <p>{@link UpdateViewObjectCardTextTest} and {@link UpdateViewConnectionCardTextTest} pin the
 * sentences against a hand-built map. This class pins that the accessor actually reaches those
 * collaborators with the map the card carries — a collaborator can be perfectly correct and never
 * be called, or be called with something other than the disclosure.</p>
 *
 * <p>Deliberately headless (no {@code Display}): the fixture is built with
 * {@link IArchimateFactory} and a plain GEF {@link CommandStack}, and every call passes explicit
 * geometry so nothing reaches a sizer that would need one. That keeps these pins in the wide
 * default test pass rather than the narrow display-gated one, where a card assertion is only as
 * reachable as the lane that runs it.</p>
 */
public class UpdateApprovalCardProseTest {

    private static final String SESSION = "default";

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private CommandStack stack;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private IDiagramModelArchimateObject sourceObj;
    private IDiagramModelNote note;
    private IDiagramModelArchimateConnection connection;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Approval Card Prose Fixture");
        model.setId("model-approval-card-prose");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Prose View");
        diagrams.getElements().add(view);

        IFolder business = model.getFolder(FolderType.BUSINESS);
        IBusinessActor source = actor("actor-src", "Source Actor", business);
        IBusinessActor target = actor("actor-tgt", "Target Actor", business);

        IArchimateRelationship relationship = factory.createAssociationRelationship();
        relationship.setId("rel-1");
        relationship.setName("associates");
        relationship.setSource(source);
        relationship.setTarget(target);
        model.getFolder(FolderType.RELATIONS).getElements().add(relationship);

        sourceObj = liveObject("vo-src", source, 0, 0);
        IDiagramModelArchimateObject targetObj = liveObject("vo-tgt", target, 400, 0);

        // A committed connection, built directly rather than through add-connection-to-view, so the
        // only proposal on the queue is the one the test under way stores.
        connection = factory.createDiagramModelArchimateConnection();
        connection.setId("conn-1");
        connection.setArchimateRelationship(relationship);
        connection.connect(sourceObj, targetObj);

        // A note, because text is a note/group property — an ArchiMate element view object
        // rejects it, and the text-only call is the sharpest instance of the defect.
        note = factory.createDiagramModelNote();
        note.setId("note-1");
        note.setContent("Original caption");
        note.setBounds(0, 200, 185, 80);
        view.getChildren().add(note);

        stubModelManager.setModels(List.of(model));

        stack = new CommandStack();
        model.setAdapter(CommandStack.class, stack);
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                executeDecomposed(command);
            }
            @Override
            protected void dispatchCommand(Command command) {
                executeDecomposed(command);
            }
            private void executeDecomposed(Command command) {
                if (command instanceof CompoundCommand compound) {
                    for (Object cmd : compound.getCommands()) {
                        executeDecomposed((Command) cmd);
                    }
                } else {
                    command.execute();
                }
            }
        };
        dispatcher.setApprovalModeProvider(() -> true);
        accessor = new ArchiModelAccessorImpl(stubModelManager, dispatcher);
        dispatcher.onModelActive(model);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ---- update-view-object ---------------------------------------------------------------------

    @Test
    public void shouldValidateTheTextChange_whenAViewObjectCallOnlySetsText() {
        // The defect: this card validated "View object bounds" for a call that moves nothing.
        accessor.updateViewObject(SESSION, note.getId(), null, null, null, null,
                "Renamed", null, null, null, null, null, null, null);

        ProposalDto card = card();
        assertEquals("Text ready for update.", card.validationSummary());
        assertFalse("a text-only call must not be validated as a move",
                card.validationSummary().contains("bounds"));
        assertTrue("the two prose fields must agree", card.description().startsWith("Update text "));
    }

    @Test
    public void shouldKeepTheEstablishedSummary_whenAViewObjectCallOnlyMoves() {
        // The negative end: the bounds-only call must read exactly as it always did.
        accessor.updateViewObject(SESSION, sourceObj.getId(), 40, 60, 120, 55, null,
                null, null, null, null, null, null, null);

        assertEquals("View object bounds ready for update.", card().validationSummary());
    }

    // ---- update-view-connection ------------------------------------------------------------------

    @Test
    public void shouldNameStyling_whenAConnectionCallOnlyRestyles() {
        // The defect, in both prose fields at once: a restyle announced and validated as a reroute.
        accessor.updateViewConnection(SESSION, connection.getId(), null, null,
                new StylingParams(null, "#FF0000", null, null, null), null, null);

        ProposalDto card = card();
        assertEquals("Update styling for connection (AssociationRelationship) in view 'Prose View'",
                card.description());
        assertEquals("Connection styling ready for update.", card.validationSummary());
        assertFalse("a styling-only call must not claim a bendpoint change",
                (card.description() + card.validationSummary()).contains("bendpoint"));
    }

    @Test
    public void shouldNameLabelVisibility_whenAConnectionCallOnlyHidesTheLabel() {
        accessor.updateViewConnection(SESSION, connection.getId(), null, null, null,
                Boolean.FALSE, null);

        ProposalDto card = card();
        assertEquals("Update label visibility for connection (AssociationRelationship) "
                + "in view 'Prose View'", card.description());
        assertEquals("Connection label visibility ready for update.", card.validationSummary());
    }

    @Test
    public void shouldKeepTheEstablishedWording_whenAConnectionCallOnlySetsBendpoints() {
        // The negative end: the bendpoint-only call keeps both of its previous sentences, byte for
        // byte, so this fix re-describes only the calls that were being described wrongly.
        accessor.updateViewConnection(SESSION, connection.getId(),
                List.of(new BendpointDto(10, 20, 30, 40)), null, null, null, null);

        ProposalDto card = card();
        assertEquals("Update bendpoints for connection (AssociationRelationship) in view 'Prose View'",
                card.description());
        assertEquals("Connection bendpoints ready for update.", card.validationSummary());
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** The single pending card, read off the DTO the list-pending-approvals tool returns. */
    private ProposalDto card() {
        List<ProposalDto> pending = dispatcher.getPendingProposalDtos(SESSION);
        assertEquals("the call must have stored exactly one proposal", 1, pending.size());
        return pending.get(0);
    }

    private IBusinessActor actor(String id, String name, IFolder folder) {
        IBusinessActor a = factory.createBusinessActor();
        a.setId(id);
        a.setName(name);
        folder.getElements().add(a);
        return a;
    }

    private IDiagramModelArchimateObject liveObject(String id, IBusinessActor element, int x, int y) {
        IDiagramModelArchimateObject obj = factory.createDiagramModelArchimateObject();
        obj.setId(id);
        obj.setArchimateElement(element);
        obj.setBounds(x, y, 120, 55);
        view.getChildren().add(obj);
        return obj;
    }

    /** Same headless stub every accessor fixture in this package carries. */
    private static class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final List<PropertyChangeListener> listeners = new ArrayList<>();

        void setModels(List<IArchimateModel> models) { this.models = models; }

        @Override public List<IArchimateModel> getModels() { return models; }
        @Override public void addPropertyChangeListener(PropertyChangeListener l) { listeners.add(l); }
        @Override public void removePropertyChangeListener(PropertyChangeListener l) { listeners.remove(l); }
        @Override public IArchimateModel createNewModel() { return null; }
        @Override public void registerModel(IArchimateModel m) {}
        @Override public IArchimateModel openModel(File file) { return null; }
        @Override public void openModel(IArchimateModel m) {}
        @Override public IArchimateModel loadModel(File file) { return null; }
        @Override public IArchimateModel load(File file) throws IOException { return null; }
        @Override public boolean closeModel(IArchimateModel m) throws IOException { return false; }
        @Override public boolean closeModel(IArchimateModel m, boolean askSave) throws IOException { return false; }
        @Override public boolean isModelLoaded(File file) { return false; }
        @Override public boolean isModelDirty(IArchimateModel m) { return false; }
        @Override public boolean saveModel(IArchimateModel m) throws IOException { return false; }
        @Override public boolean saveModelAs(IArchimateModel m) throws IOException { return false; }
        @Override public void saveState() throws IOException {}
        @Override public void firePropertyChange(Object src, String p, Object oldV, Object newV) {}
    }
}
