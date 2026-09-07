package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.vheerden.archi.mcp.response.dto.AddToViewResultDto;
import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.BulkOperationResult;
import net.vheerden.archi.mcp.response.dto.MovedViewObjectDto;

/**
 * Pins what {@code add-to-view} says about the objects its icon-band reservation grows.
 *
 * <p>Dropping a child into the corner where its container's icon renders grows that container, and
 * a container that grows must still fit inside the group above it. Neither object is named in the
 * request, so before this the caller placed one element and two others silently changed size — at a
 * tool whose only ground truth for the agent is its own response. Measured before the report
 * existed: container 300 → 324, group 340 → 344, and a response carrying nothing but the created
 * view object.</p>
 *
 * <p>Every scenario asserts the reported rectangle <em>field by field against the model</em> and
 * against the serialized JSON: a value that exists in Java but never reaches the wire would satisfy
 * a typed assertion while leaving the client just as blind. Each also guards its own fixture by
 * asserting the growth actually happened, so a scenario cannot pass by proving nothing.</p>
 *
 * <h2>Execution model</h2>
 *
 * <p>Same headless idiom as the other batch classes: a real GEF {@link CommandStack} driven over an
 * ordered compound, with the production {@code NonNotifyingCompoundCommand} rebuilt as a plain
 * {@link CompoundCommand} because its {@code execute()} dereferences
 * {@code IEditorModelManager.INSTANCE}. Every add passes explicit bounds so nothing reaches
 * {@code ElementSizer}'s display-bound measurement.</p>
 */
public class AddToViewIconBandReportingTest {

    private static final String SESSION = "add-to-view-icon-band-report-session";

    /** Bottom-left is one of the two corners the reservation fires for. */
    private static final String CORNER = "bottom-left";

    private IArchimateFactory factory;
    private StubEditorModelManager stubModelManager;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private CommandStack stack;
    private IArchimateModel model;
    private IArchimateDiagramModel view;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Icon Band Reporting Fixture");
        model.setId("model-icon-band-report");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Reporting");
        diagrams.getElements().add(view);

        IFolder business = model.getFolder(FolderType.BUSINESS);
        for (int i = 1; i <= 3; i++) {
            IBusinessActor a = factory.createBusinessActor();
            a.setId("actor-" + i);
            a.setName("Actor " + i);
            business.getElements().add(a);
        }

        stubModelManager.setModels(List.of(model));

        stack = new CommandStack();
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                stack.execute(new AgentAuthoredCompoundCommand(toPlainCompound(command)));
            }
            @Override
            protected void dispatchCommand(Command command) {
                stack.execute(new AgentAuthoredCompoundCommand(toPlainCompound(command)));
            }
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

    private String group(String label, int x, int y, int w, int h, String parentId) {
        return accessor.addGroupToView(SESSION, view.getId(), label, x, y, w, h, parentId, null, null)
                .entity().viewObjectId();
    }

    private AddToViewResultDto add(String actorId, Integer x, Integer y, Integer w, Integer h, String parentId,
            ImageParams img) {
        return accessor.addToView(SESSION, view.getId(), actorId, x, y, w, h, false, parentId,
                null, img).entity();
    }

    private static IDiagramModelObject find(IDiagramModelContainer container, String id) {
        for (IDiagramModelObject child : container.getChildren()) {
            if (id.equals(child.getId())) {
                return child;
            }
            if (child instanceof IDiagramModelContainer nested) {
                IDiagramModelObject hit = find(nested, id);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static int height(IDiagramModelObject o) {
        return o.getBounds().getHeight();
    }

    private static Map<String, Object> op(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static MovedViewObjectDto entryFor(AddToViewResultDto dto, String viewObjectId) {
        for (MovedViewObjectDto entry : dto.resizedAncestors()) {
            if (viewObjectId.equals(entry.viewObjectId())) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Asserts the reported rectangle for {@code viewObjectId} equals what the model holds, field by
     * field, and that the id and effective height both survive serialization.
     */
    private void assertReportedAgainstModel(AddToViewResultDto dto, String viewObjectId,
            String what) throws Exception {
        MovedViewObjectDto reported = entryFor(dto, viewObjectId);
        assertNotNull("add-to-view must name the " + what + " it grew — the caller never mentioned "
                + "it and cannot see the canvas. resizedAncestors was: " + dto.resizedAncestors(),
                reported);

        IDiagramModelObject live = find(view, viewObjectId);
        assertNotNull("the reported " + what + " must exist in the view: " + viewObjectId, live);
        assertEquals(what + ": x", live.getBounds().getX(), reported.newX());
        assertEquals(what + ": y", live.getBounds().getY(), reported.newY());
        assertEquals(what + ": width", live.getBounds().getWidth(), reported.newWidth());
        assertEquals(what + ": height", live.getBounds().getHeight(), reported.newHeight());

        String json = new ObjectMapper().writeValueAsString(dto);
        assertTrue("the " + what + "'s id must reach the wire, not just the Java object. JSON: "
                + json, json.contains(viewObjectId));
        assertTrue("the " + what + "'s effective height (" + live.getBounds().getHeight()
                + ") must reach the wire. JSON: " + json,
                json.contains(String.valueOf(live.getBounds().getHeight())));
    }

    // ---- outside any unit of work --------------------------------------------------------------

    /**
     * The headline: the corner child's arrival grows the container and the group above it, and the
     * response names both with the bounds each ended at.
     *
     * <p>Geometry: the group is declared {@code 340} tall and the container {@code 300} tall at
     * {@code y = 10}, so before the reservation the container fits with the {@code 10} padding
     * exactly. The corner child takes the container to {@code 324} and its required extent to
     * {@code 10 + 324 + 10 = 344}.</p>
     */
    @Test
    public void shouldReportBothTheContainerAndTheGroupAboveIt_whenAnIconBandIsReserved()
            throws Exception {
        String outer = group("Outermost", 0, 0, 340, 340, null);
        String parent = add("actor-1", 10, 10, 300, 300, outer,
                new ImageParams(null, CORNER, null)).viewObject().viewObjectId();

        AddToViewResultDto dto = add("actor-2", 0, 270, 50, 30, parent, null);

        assertEquals("fixture guard: the container must actually grow", 324, height(find(view, parent)));
        assertEquals("fixture guard: the group above must actually grow", 344, height(find(view, outer)));

        assertEquals("exactly the container and the one group above it", 2, dto.resizedAncestors().size());
        assertReportedAgainstModel(dto, parent, "container");
        assertReportedAgainstModel(dto, outer, "group above");
    }

    /** The container is reported first: the growth that provoked the cascade precedes its effects. */
    @Test
    public void shouldReportTheContainerBeforeTheGroupItPushedOut_whenBothGrew() throws Exception {
        String outer = group("Outermost", 0, 0, 340, 340, null);
        String parent = add("actor-1", 10, 10, 300, 300, outer,
                new ImageParams(null, CORNER, null)).viewObject().viewObjectId();

        AddToViewResultDto dto = add("actor-2", 0, 270, 50, 30, parent, null);

        assertEquals("the container that grew comes first",
                parent, dto.resizedAncestors().get(0).viewObjectId());
        assertEquals("then the group its growth pushed out",
                outer, dto.resizedAncestors().get(1).viewObjectId());
    }

    /**
     * The commonest shape — an icon-bearing container with no group above it. The cascade has
     * nothing to do, but the container still grew, so the report must still name it. Wiring only
     * the cascade branch would leave this case exactly as silent as before.
     */
    @Test
    public void shouldStillReportTheContainer_whenThereIsNoGroupAboveToCascadeTo() throws Exception {
        String parent = add("actor-1", 0, 0, 300, 300, null,
                new ImageParams(null, CORNER, null)).viewObject().viewObjectId();

        AddToViewResultDto dto = add("actor-2", 0, 270, 50, 30, parent, null);

        assertEquals("fixture guard: the container must actually grow", 324, height(find(view, parent)));
        assertEquals("only the container — there is no group above it", 1, dto.resizedAncestors().size());
        assertReportedAgainstModel(dto, parent, "container");
    }

    // ---- queue mode ----------------------------------------------------------------------------

    /**
     * Inside a batch the growth is queued rather than applied, so what the response carries is a
     * projection — which is why the envelope nests it under {@code preview}. The projection must
     * still be the geometry the batch will land, asserted after the commit.
     */
    @Test
    public void shouldReportTheQueuedGrowth_whenTheContainerAndGroupAreBothQueued() throws Exception {
        dispatcher.beginBatch(SESSION, "queued group, queued icon-bearing container, corner child");
        String outer = group("QOutermost", 0, 0, 340, 340, null);
        String parent = add("actor-1", 10, 10, 300, 300, outer,
                new ImageParams(null, CORNER, null)).viewObject().viewObjectId();
        AddToViewResultDto dto = add("actor-2", 0, 270, 50, 30, parent, null);
        dispatcher.endBatch(SESSION, true);

        assertEquals("fixture guard: the queued container must actually grow", 324, height(find(view, parent)));
        assertEquals("fixture guard: the queued group must actually grow", 344, height(find(view, outer)));

        assertEquals("both queued ancestors reported", 2, dto.resizedAncestors().size());
        assertReportedAgainstModel(dto, parent, "queued container");
        assertReportedAgainstModel(dto, outer, "queued group above");
    }

    /**
     * The band is reserved on top of what the batch has already decided for the container, so the
     * reported height must be the queued 400 plus the band — not the created 300 plus the band.
     * A report sourced from the pre-batch bounds would be a confident wrong number.
     */
    @Test
    public void shouldReportTheHeightTheBatchWillLand_whenTheBatchAlreadyResizedTheContainer()
            throws Exception {
        dispatcher.beginBatch(SESSION, "resize the icon-bearing container, then fill its corner");
        String parent = add("actor-1", 0, 0, 300, 300, null,
                new ImageParams(null, CORNER, null)).viewObject().viewObjectId();
        accessor.updateViewObject(SESSION, parent, null, null, null, 400,
                null, null, null, null, null, null, null, null);
        AddToViewResultDto dto = add("actor-2", 0, 370, 50, 30, parent, null);
        dispatcher.endBatch(SESSION, true);

        assertEquals("fixture guard: the band lands on the queued 400", 424, height(find(view, parent)));
        assertReportedAgainstModel(dto, parent, "re-sized queued container");
        assertEquals("reported against the queued height, not the created one",
                424, entryFor(dto, parent).newHeight());
    }

    /**
     * A group the batch merely measured against must not be reported as one this placement grew.
     * The cascade's working bounds map is seeded with what the batch already established, so a
     * report taken from that map would name every group the batch had touched. The command map the
     * report is built from receives an entry only where the walk actually resized.
     */
    @Test
    public void shouldNotNameAGroupItOnlyMeasuredAgainst_whenTheGroupWasAlreadyBigEnough()
            throws Exception {
        dispatcher.beginBatch(SESSION, "roomy group: the container grows, the group need not");
        String outer = group("Roomy", 0, 0, 900, 900, null);
        String parent = add("actor-1", 10, 10, 300, 300, outer,
                new ImageParams(null, CORNER, null)).viewObject().viewObjectId();
        AddToViewResultDto dto = add("actor-2", 0, 270, 50, 30, parent, null);
        dispatcher.endBatch(SESSION, true);

        assertEquals("fixture guard: the container still grows", 324, height(find(view, parent)));
        assertEquals("fixture guard: the roomy group does NOT grow", 900, height(find(view, outer)));

        assertEquals("only the container grew, so only the container is reported",
                1, dto.resizedAncestors().size());
        assertEquals(parent, dto.resizedAncestors().get(0).viewObjectId());

        String json = new ObjectMapper().writeValueAsString(dto);
        assertFalse("naming a group this placement did not resize would be a false statement about "
                + "the model dressed as a measurement. JSON: " + json, json.contains(outer));
    }

    // ---- bulk mode -----------------------------------------------------------------------------

    /**
     * The same property on the bulk path, which reaches the reservation through the same prepare but
     * carries its pending containment in a pass-scoped map rather than the queue. Asserted on the
     * accessor's own result rather than the bulk envelope: what {@code bulk-mutate} forwards to its
     * per-operation results is a separate contract.
     */
    @Test
    public void shouldReportTheGrowth_whenTheContainerAndGroupAreBulkCreated() throws Exception {
        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("add-group-to-view", op(
                        "viewId", view.getId(), "label", "BOutermost",
                        "x", 0, "y", 0, "width", 340, "height", 340)),
                new BulkOperation("create-element", op(
                        "name", "Parent", "type", "BusinessActor")),
                new BulkOperation("add-to-view", op(
                        "viewId", view.getId(), "elementId", "$1.id",
                        "parentViewObjectId", "$0.id",
                        "x", 10, "y", 10, "width", 300, "height", 300,
                        "imagePosition", CORNER)),
                new BulkOperation("create-element", op(
                        "name", "Corner", "type", "BusinessActor")),
                new BulkOperation("add-to-view", op(
                        "viewId", view.getId(), "elementId", "$3.id",
                        "parentViewObjectId", "$2.id",
                        "x", 0, "y", 270, "width", 50, "height", 30))),
                "bulk icon-band reporting", false);

        IDiagramModelObject outer = view.getChildren().get(0);
        IDiagramModelObject parent = ((IDiagramModelContainer) outer).getChildren().get(0);
        assertEquals("fixture guard: the bulk-created container grew", 324, height(parent));
        assertEquals("fixture guard: the bulk-created group grew", 344, height(outer));

        // Asserting the model alone would leave the REPORTED field unmeasured in exactly the case
        // the sibling pre-existing-container pin covers. The two differ in whether the grown objects
        // are also some operation's own entity, which is a different branch of the projection —
        // so proving one says nothing about the other.
        BulkOperationResult placed = result.operations().get(4);
        String json = new ObjectMapper().writeValueAsString(placed);
        assertEquals("the placing operation must name both objects it grew. Result was: " + json,
                2, placed.resizedAncestors().size());
        for (MovedViewObjectDto grown : placed.resizedAncestors()) {
            IDiagramModelObject live = find(view, grown.viewObjectId());
            assertNotNull("reported an object not in the view: " + grown.viewObjectId(), live);
            assertEquals(grown.viewObjectId() + ": height",
                    live.getBounds().getHeight(), grown.newHeight());
        }
        assertTrue("the container's landed height must reach the wire. Result was: " + json,
                json.contains("\"newHeight\":324"));
        assertTrue("and the group's. Result was: " + json, json.contains("\"newHeight\":344"));
    }

    /**
     * The bulk case that was genuinely blind. When the container is created inside the same bulk
     * array it is some operation's own entity, so the post-dispatch bounds read already described
     * it. When it pre-exists the call it is nobody's entity, and the growth went unreported — the
     * per-operation result carried only the new child's own 50x30.
     *
     * <p>The reported values are re-read from the model after dispatch rather than carried from the
     * prepare, for the same reason the operation's own geometry is: a later operation in the same
     * call can move it again.</p>
     */
    @Test
    public void shouldReportTheGrowth_whenTheBulkCallLandsInAPreExistingContainer() throws Exception {
        String outer = group("PreOuter", 0, 0, 340, 340, null);
        String container = add("actor-1", 10, 10, 300, 300, outer,
                new ImageParams(null, CORNER, null)).viewObject().viewObjectId();

        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("add-to-view", op(
                        "viewId", view.getId(), "elementId", "actor-2",
                        "parentViewObjectId", container,
                        "x", 0, "y", 270, "width", 50, "height", 30))),
                "land in a pre-existing icon-bearing container", false);

        assertEquals("fixture guard: the pre-existing container grew", 324, height(find(view, container)));
        assertEquals("fixture guard: the pre-existing group grew", 344, height(find(view, outer)));

        BulkOperationResult placed = result.operations().get(0);
        String json = new ObjectMapper().writeValueAsString(placed);
        assertEquals("the operation must name both objects it grew, neither of which is its own "
                + "entity. Result was: " + json, 2, placed.resizedAncestors().size());

        for (MovedViewObjectDto grown : placed.resizedAncestors()) {
            IDiagramModelObject live = find(view, grown.viewObjectId());
            assertNotNull("reported an object not in the view: " + grown.viewObjectId(), live);
            assertEquals(grown.viewObjectId() + ": height",
                    live.getBounds().getHeight(), grown.newHeight());
            assertEquals(grown.viewObjectId() + ": width",
                    live.getBounds().getWidth(), grown.newWidth());
        }
        assertTrue("the container's landed height must reach the wire. Result was: " + json,
                json.contains("\"newHeight\":324"));
        assertTrue("and the group's. Result was: " + json, json.contains("\"newHeight\":344"));
    }

    /** An operation that grew no ancestor keeps its previous per-operation shape exactly. */
    @Test
    public void shouldOmitTheField_whenABulkOperationGrewNoAncestor() throws Exception {
        BulkMutationResult result = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("add-to-view", op(
                        "viewId", view.getId(), "elementId", "actor-1",
                        "x", 0, "y", 0, "width", 120, "height", 55))),
                "ordinary placement", false);

        BulkOperationResult placed = result.operations().get(0);
        assertTrue("nothing grew", placed.resizedAncestors().isEmpty());
        String json = new ObjectMapper().writeValueAsString(placed);
        assertFalse("an empty list must be omitted, not emitted as []. Result was: " + json,
                json.contains("resizedAncestors"));
    }

    // ---- non-regression ------------------------------------------------------------------------

    /**
     * A placement that reserves no band must say nothing about ancestors it did not grow. Asserted
     * on the serialized response rather than by reading the annotation: an empty list that reached
     * the wire as {@code "resizedAncestors":[]} would be a visible change to every caller for a
     * case where nothing happened.
     *
     * <p>The exact-serialization assertion below is the strong form of that, and it is deliberately
     * exact rather than a containment check, so it also fails on any <em>other</em> field this
     * response starts or stops carrying. It does carry one more than it used to:
     * {@code parentViewObjectId}, because this fixture nests its subject and a nested object's
     * {@code x} and {@code y} are relative to that container. A top-level placement is the case
     * that stayed byte-identical, and it is pinned as such by
     * {@code PlacementParentReportingTest}.</p>
     */
    @Test
    public void shouldSayNothing_whenThePlacementReservedNoBand() throws Exception {
        String plain = add("actor-1", 0, 0, 300, 300, null, null).viewObject().viewObjectId();
        AddToViewResultDto dto = add("actor-2", 0, 270, 50, 30, plain, null);

        assertEquals("control: nothing grows", 300, height(find(view, plain)));
        assertTrue("nothing grew, so nothing is reported", dto.resizedAncestors().isEmpty());

        String json = new ObjectMapper().writeValueAsString(dto);
        assertFalse("an empty list must be omitted, not emitted as [] — a placement that grew "
                + "nothing should say nothing rather than say 'none' in a way that reads as "
                + "measured. JSON: " + json, json.contains("resizedAncestors"));
        assertEquals("the exact serialization: nothing about ancestors, and the container this "
                + "placement nested into",
                "{\"viewObject\":{\"viewObjectId\":\"" + dto.viewObject().viewObjectId() + "\","
                        + "\"elementId\":\"actor-2\",\"elementName\":\"Actor 2\","
                        + "\"elementType\":\"BusinessActor\",\"x\":0,\"y\":270,"
                        + "\"width\":50,\"height\":30,"
                        + "\"parentViewObjectId\":\"" + plain + "\"}}",
                json);
    }

    /**
     * The tool's other source of geometry the caller did not supply: omitted bounds. Width and
     * height fall back to defaults and position is auto-placed, and the created object's own report
     * must carry what was resolved rather than the nulls that were asked for.
     *
     * <p>Pinned because the audit that closed this tool's gap-registry entry has to cover every way
     * it produces geometry, not only the reservation. All three substitutions — default width,
     * default height, auto-placed position — reach {@code setBounds} and the reported DTO from the
     * same locals, so one measurement covers them; this asserts that shared origin rather than
     * assuming it.</p>
     */
    @Test
    public void shouldReportTheBoundsItResolved_whenTheCallerSuppliedNone() throws Exception {
        AddToViewResultDto dto = add("actor-1", null, null, null, null, null, null);

        IDiagramModelObject live = find(view, dto.viewObject().viewObjectId());
        assertNotNull("the placed object must be in the view", live);
        assertEquals("reported x must be the auto-placed one", live.getBounds().getX(), (int) dto.viewObject().x());
        assertEquals("reported y must be the auto-placed one", live.getBounds().getY(), (int) dto.viewObject().y());
        assertEquals("reported width must be the resolved default",
                live.getBounds().getWidth(), (int) dto.viewObject().width());
        assertEquals("reported height must be the resolved default",
                live.getBounds().getHeight(), (int) dto.viewObject().height());
        assertTrue("nothing was grown, so nothing is reported as grown",
                dto.resizedAncestors().isEmpty());
    }

    /** A container whose icon sits in Archi's default corner is untouched, and says so by silence. */
    @Test
    public void shouldSayNothing_whenTheContainersIconIsInTheDefaultCorner() throws Exception {
        String parent = add("actor-1", 0, 0, 300, 300, null,
                new ImageParams(null, "top-right", null)).viewObject().viewObjectId();

        AddToViewResultDto dto = add("actor-2", 0, 270, 50, 30, parent, null);

        assertEquals("the default corner never fires the reservation", 300, height(find(view, parent)));
        assertTrue("so there is nothing to report", dto.resizedAncestors().isEmpty());
    }

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
