package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IGrouping;
import com.archimatetool.model.ITextAlignment;

import net.vheerden.archi.mcp.response.dto.ViewGroupDto;
import net.vheerden.archi.mcp.response.dto.ViewNoteDto;
import net.vheerden.archi.mcp.response.dto.ViewObjectDto;

/**
 * Pins that an object created through this server carries the same title alignment Archi's own
 * palette would have stamped on it — and, just as importantly, that the stamp reaches nothing else.
 *
 * <p>Archi's diagram factory writes the type's UI-provider default onto every object it creates.
 * This server builds objects straight from the EMF factory, so before this behaviour existed a
 * {@code Grouping}, group or note came out CENTRE where a hand-drawn one is LEFT — a visible
 * difference, since on a 400 px box a LEFT title's ink begins a few pixels from the edge and a
 * centred one sits roughly 170 px away.</p>
 *
 * <p>Three types diverge and only three. Every other kind this server creates is born holding the
 * value its provider would have written, which {@code CreationSiteStampNoOpTest} establishes. So the
 * tests below come in matched pairs: what must change, and what must not. A fixture proving the
 * stamp fires says nothing about whether it leaked, and the leak is the more expensive failure —
 * it would silently restyle every element an agent ever places.</p>
 */
public class TypeDefaultTextAlignmentStampTest {

    private static final String SESSION = "stamp-session";

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private CommandStack stack;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private IBusinessActor plainActor;
    private IGrouping grouping;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Stamp Fixture");
        model.setId("model-stamp");
        model.setDefaults();

        plainActor = factory.createBusinessActor();
        plainActor.setId("actor-a");
        plainActor.setName("Actor A");
        model.getFolder(FolderType.BUSINESS).getElements().add(plainActor);

        grouping = factory.createGrouping();
        grouping.setId("grouping-a");
        grouping.setName("Grouping A");
        model.getFolder(FolderType.OTHER).getElements().add(grouping);

        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Target View");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        stubModelManager.setModels(List.of(model));

        stack = new CommandStack();
        MutationDispatcher dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                stack.execute(toPlainCompound(command));
            }
            @Override
            protected void dispatchCommand(Command command) {
                stack.execute(toPlainCompound(command));
            }
            // NonNotifyingCompoundCommand static-initialises IEditorModelManager, which cannot
            // initialise outside OSGi; rebuilding into a plain compound keeps the child commands.
            private Command toPlainCompound(Command command) {
                if (command instanceof CompoundCommand compound) {
                    CompoundCommand plain = new CompoundCommand(compound.getLabel());
                    for (Object child : compound.getCommands()) {
                        plain.add(toPlainCompound((Command) child));
                    }
                    return plain;
                }
                return command;
            }
        };
        dispatcher.setApprovalModeProvider(() -> false);
        accessor = new ArchiModelAccessorImpl(stubModelManager, dispatcher);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Styling carrying only an explicit horizontal alignment. */
    private static StylingParams alignedStyling(String textAlignment) {
        return new StylingParams(null, null, null, null, null, null, textAlignment, null);
    }

    private ViewObjectDto addElement(String elementId, StylingParams styling) {
        return accessor.addToView(SESSION, view.getId(), elementId, 10, 10, 400, 140,
                false, null, styling, null).entity().viewObject();
    }

    private int modelAlignmentOf(String viewObjectId) {
        for (IDiagramModelObject child : view.getChildren()) {
            if (child.getId().equals(viewObjectId)) {
                return ((ITextAlignment) child).getTextAlignment();
            }
        }
        throw new AssertionError("view object not found on the view: " + viewObjectId);
    }

    // ---- what must change ------------------------------------------------------------------------

    @Test
    public void shouldReportLeftAlignment_whenAGroupingIsAddedToAView() {
        ViewObjectDto dto = addElement(grouping.getId(), null);

        assertEquals("a Grouping must carry the alignment Archi's palette would have stamped",
                "left", dto.textAlignment());
        assertEquals("and the model must actually hold it, not merely the response",
                ITextAlignment.TEXT_ALIGNMENT_LEFT, modelAlignmentOf(dto.viewObjectId()));
    }

    @Test
    public void shouldReportLeftAlignment_whenAGroupIsAddedToAView() {
        ViewGroupDto dto = accessor.addGroupToView(SESSION, view.getId(), "Holder",
                10, 200, 400, 140, null, null, null).entity();

        assertEquals("a native group must carry its provider's LEFT default",
                "left", dto.textAlignment());
        assertEquals(ITextAlignment.TEXT_ALIGNMENT_LEFT, modelAlignmentOf(dto.viewObjectId()));
    }

    @Test
    public void shouldReportLeftAlignment_whenANoteIsAddedToAView() {
        ViewNoteDto dto = accessor.addNoteToView(SESSION, view.getId(), "A note", null, null,
                10, 400, 180, 80, null, null, null).entity();

        assertEquals("a note must carry its provider's LEFT default", "left", dto.textAlignment());
        assertEquals(ITextAlignment.TEXT_ALIGNMENT_LEFT, modelAlignmentOf(dto.viewObjectId()));
    }

    /**
     * The ordering control. The lookup that decides whether an ArchiMate object is a
     * {@code Grouping} can only answer once the concept is attached to the diagram object, so this
     * passing at all is what proves the stamp runs after {@code setArchimateElement} rather than
     * before it. Reading LEFT here and CENTRE on the plain element below cannot both happen unless
     * the stamp saw a real concept.
     */
    @Test
    public void shouldDecideFromTheAttachedConcept_whenTwoElementTypesArePlaced() {
        ViewObjectDto stamped = addElement(grouping.getId(), null);
        ViewObjectDto untouched = addElement(plainActor.getId(), null);

        assertEquals("left", stamped.textAlignment());
        assertNull("the same creation path must leave a non-Grouping element alone",
                untouched.textAlignment());
    }

    // ---- what must NOT change --------------------------------------------------------------------

    /**
     * The negative control. A plain element's provider default is CENTRE, which is what the object
     * is already born holding, so nothing may be written and the response must keep omitting the
     * field. If this ever fails, the stamp has leaked to every element an agent places.
     */
    @Test
    public void shouldLeaveAlignmentUnset_whenAPlainElementIsAddedToAView() {
        ViewObjectDto dto = addElement(plainActor.getId(), null);

        assertNull("a plain element must still read back with no alignment",
                dto.textAlignment());
        assertEquals("and must still hold the CENTRE default in the model",
                ITextAlignment.TEXT_ALIGNMENT_CENTER, modelAlignmentOf(dto.viewObjectId()));
    }

    /**
     * The positive control. A type default is a default, so an explicit request must win — on the
     * very types that carry a non-CENTRE default, where the two actually disagree.
     */
    @Test
    public void shouldKeepTheRequestedAlignment_whenACallerAsksForOneOnAGrouping() {
        ViewObjectDto dto = addElement(grouping.getId(), alignedStyling("right"));

        assertEquals("an explicit request must beat the type default",
                "right", dto.textAlignment());
        assertEquals(ITextAlignment.TEXT_ALIGNMENT_RIGHT, modelAlignmentOf(dto.viewObjectId()));
    }

    @Test
    public void shouldKeepTheRequestedAlignment_whenACallerAsksForOneOnAGroup() {
        ViewGroupDto dto = accessor.addGroupToView(SESSION, view.getId(), "Holder",
                10, 200, 400, 140, null, alignedStyling("centre"), null).entity();

        assertNull("an explicit CENTRE request must win over the LEFT type default, and CENTRE "
                + "is the value the read-back omits", dto.textAlignment());
        assertEquals(ITextAlignment.TEXT_ALIGNMENT_CENTER, modelAlignmentOf(dto.viewObjectId()));
    }

    @Test
    public void shouldKeepTheRequestedAlignment_whenACallerAsksForOneOnANote() {
        ViewNoteDto dto = accessor.addNoteToView(SESSION, view.getId(), "A note", null, null,
                10, 400, 180, 80, null, alignedStyling("right"), null).entity();

        assertEquals("an explicit request must beat the type default",
                "right", dto.textAlignment());
        assertEquals(ITextAlignment.TEXT_ALIGNMENT_RIGHT, modelAlignmentOf(dto.viewObjectId()));
    }

    /**
     * The vertical axis is not part of this behaviour: every provider reachable here returns TOP,
     * which is what the object is already born holding. Pinned so that a later change to the
     * vertical default is deliberate rather than a side effect of touching the horizontal one.
     */
    @Test
    public void shouldLeaveVerticalAlignmentUnset_whenAnyObjectIsAddedToAView() {
        assertNull(addElement(grouping.getId(), null).verticalTextAlignment());
        assertNull(accessor.addGroupToView(SESSION, view.getId(), "Holder",
                10, 200, 400, 140, null, null, null).entity().verticalTextAlignment());
        assertNull(accessor.addNoteToView(SESSION, view.getId(), "A note", null, null,
                10, 400, 180, 80, null, null, null).entity().verticalTextAlignment());
    }

    private static class StubEditorModelManager implements IEditorModelManager {
        private List<IArchimateModel> models = new ArrayList<>();
        private final List<PropertyChangeListener> listeners = new ArrayList<>();

        void setModels(List<IArchimateModel> models) { this.models = models; }

        @Override public List<IArchimateModel> getModels() { return models; }
        @Override public void addPropertyChangeListener(PropertyChangeListener listener) {
            listeners.add(listener);
        }
        @Override public void removePropertyChangeListener(PropertyChangeListener listener) {
            listeners.remove(listener);
        }
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
