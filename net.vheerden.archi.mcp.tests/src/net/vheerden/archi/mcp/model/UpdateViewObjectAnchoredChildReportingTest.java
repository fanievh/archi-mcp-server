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
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IBusinessActor;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.BulkOperationResult;
import net.vheerden.archi.mcp.response.dto.MovedViewObjectDto;
import net.vheerden.archi.mcp.response.dto.ViewObjectDto;

/**
 * Pins what {@code update-view-object} says about the objects it moves and grows that the caller
 * never named.
 *
 * <p>Resizing one object displaces every object anchored to it, and pushes the group around it
 * wider if the new rectangle no longer fits. The caller named one id; two other objects change
 * shape. Before this the response described only the object in the request, at a tool whose
 * response is the agent's sole ground truth for a canvas it cannot see.</p>
 *
 * <h2>The movement pin comes first, deliberately</h2>
 *
 * <p>{@code AnchorResolver.wrapAnchoredChildren} takes a move map whose contract is that the
 * <em>caller</em> commits what it records — passing one is not an observation, it stops the moves
 * riding inside the returned compound. A report-only test would therefore pass on a build where
 * {@code update-view-object} silently stopped moving anchored children at all, which is strictly
 * worse than the silence being fixed. Every scenario here asserts the landed position in the model
 * before it asserts anything about the response.</p>
 *
 * <h2>Execution model</h2>
 *
 * <p>The headless idiom the other batch classes use: a real GEF {@link CommandStack} driven over an
 * ordered compound, with the production {@code NonNotifyingCompoundCommand} rebuilt as a plain
 * {@link CompoundCommand} because its {@code execute()} dereferences
 * {@code IEditorModelManager.INSTANCE}. Every add passes explicit bounds so nothing reaches
 * {@code ElementSizer}'s display-bound measurement.</p>
 */
public class UpdateViewObjectAnchoredChildReportingTest {

    private static final String SESSION = "update-view-object-anchored-report-session";

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
        model.setName("Anchored Child Reporting Fixture");
        model.setId("model-anchored-child-report");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Reporting");
        diagrams.getElements().add(view);

        IFolder business = model.getFolder(FolderType.BUSINESS);
        for (int i = 1; i <= 4; i++) {
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

    private String add(String actorId, int x, int y, int w, int h, String parentId) {
        return accessor.addToView(SESSION, view.getId(), actorId, x, y, w, h, false, parentId,
                null, null).entity().viewObject().viewObjectId();
    }

    private String group(String label, int x, int y, int w, int h, String parentId) {
        return accessor.addGroupToView(SESSION, view.getId(), label, x, y, w, h, parentId, null, null)
                .entity().viewObjectId();
    }

    private ViewObjectDto resize(String viewObjectId, Integer w, Integer h) {
        return accessor.updateViewObject(SESSION, viewObjectId, null, null, w, h,
                null, null, null, null).entity();
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

    private static String json(Object dto) throws Exception {
        return new ObjectMapper().writeValueAsString(dto);
    }

    // ---- scenarios -----------------------------------------------------------------------------

    /**
     * The movement pin, standing alone and asserting nothing about the response: growing an anchor
     * target moves the object anchored below it, in the model. This is the guard the report tests
     * cannot provide for themselves — it fails on any build that trades the move for the report.
     */
    @Test
    public void shouldStillMoveTheAnchoredChild_whenTheAnchorTargetGrows() {
        String targetId = add("actor-1", 100, 100, 60, 30, null);
        String childId = add("actor-2", 100, 140, 60, 30, null);
        accessor.updateViewObject(SESSION, childId, null, null, null, null,
                null, null, null, null, targetId, "below", 0, 10);

        int childYBefore = find(view, childId).getBounds().getY();
        resize(targetId, null, 90);

        IBounds after = find(view, childId).getBounds();
        assertEquals("the anchored child must land directly below the grown target",
                100 + 90 + 10, after.getY());
        assertTrue("fixture must actually displace the child, or the pin proves nothing: "
                + childYBefore + " -> " + after.getY(), after.getY() != childYBefore);
    }

    /**
     * The silence itself: the caller resized one object, a second object moved, and the response
     * named only the first. Asserted on the serialized response because that is what the agent
     * receives — a value that exists in Java but never reaches the wire leaves the client just as
     * blind.
     */
    @Test
    public void shouldNameTheAnchoredChildItMoved_whenTheAnchorTargetGrows() throws Exception {
        String targetId = add("actor-1", 100, 100, 60, 30, null);
        String childId = add("actor-2", 100, 140, 60, 30, null);
        accessor.updateViewObject(SESSION, childId, null, null, null, null,
                null, null, null, null, targetId, "below", 0, 10);

        int childYBefore = find(view, childId).getBounds().getY();
        ViewObjectDto dto = resize(targetId, null, 90);
        IBounds childAfter = find(view, childId).getBounds();

        assertTrue("fixture must actually displace the child, or this proves nothing: "
                + childYBefore + " -> " + childAfter.getY(), childAfter.getY() != childYBefore);

        String body = json(dto);
        assertTrue("the response must name the object it displaced — the caller never mentioned it "
                + "and cannot see the canvas. Response was: " + body, body.contains(childId));
        assertTrue("the response must carry where the displaced object landed (y="
                + childAfter.getY() + "). Response was: " + body,
                body.contains("\"newY\":" + childAfter.getY()));

        assertFalse("movedObjects must not be empty once an anchored object was displaced",
                dto.movedObjects().isEmpty());
        assertReportedMove(dto, childId);
    }

    /**
     * Two children anchored to one target: both move, so both must be named. A response that
     * reported only the first would be a count's worth of information wearing a list's clothing.
     */
    @Test
    public void shouldNameEveryAnchoredChild_whenTwoTrackTheSameTarget() throws Exception {
        String targetId = add("actor-1", 100, 100, 60, 30, null);
        String belowId = add("actor-2", 100, 140, 60, 30, null);
        String rightId = add("actor-3", 170, 100, 60, 30, null);
        accessor.updateViewObject(SESSION, belowId, null, null, null, null,
                null, null, null, null, targetId, "below", 0, 10);
        accessor.updateViewObject(SESSION, rightId, null, null, null, null,
                null, null, null, null, targetId, "right", 10, 0);

        ViewObjectDto dto = resize(targetId, 120, 90);

        assertEquals("both anchored children must actually move", 200,
                find(view, belowId).getBounds().getY());
        assertEquals("both anchored children must actually move", 230,
                find(view, rightId).getBounds().getX());

        assertEquals("both displaced objects must be named", 2, dto.movedObjects().size());
        assertReportedMove(dto, belowId);
        assertReportedMove(dto, rightId);
    }

    /**
     * The other thing the same call changes without being asked: an object grown past its group's
     * edge pushes the group out. The group is a second object the request never named, and the
     * parent-fit cascade already holds the rectangle it grew to.
     */
    @Test
    public void shouldNameTheGroupItGrew_whenTheUpdatedObjectOutgrowsIt() throws Exception {
        String groupId = group("Enclosing", 0, 0, 200, 200, null);
        String childId = add("actor-1", 10, 10, 60, 30, groupId);

        int groupHeightBefore = find(view, groupId).getBounds().getHeight();
        ViewObjectDto dto = resize(childId, null, 300);
        IBounds groupAfter = find(view, groupId).getBounds();

        assertTrue("fixture must actually grow the group, or this proves nothing: "
                + groupHeightBefore + " -> " + groupAfter.getHeight(),
                groupAfter.getHeight() > groupHeightBefore);

        String body = json(dto);
        assertTrue("the response must name the group it grew. Response was: " + body,
                body.contains(groupId));
        assertFalse("resizedAncestors must not be empty once the group grew",
                dto.resizedAncestors().isEmpty());
        assertReportedAncestor(dto, groupId);
    }

    /**
     * The negative that keeps the report honest: an update that displaced nothing must say nothing.
     * An empty list is omitted from JSON, so the response of an ordinary resize is byte-identical to
     * what it was before either field existed.
     */
    @Test
    public void shouldStaySilent_whenNothingElseMoved() throws Exception {
        String loneId = add("actor-1", 100, 100, 60, 30, null);

        ViewObjectDto dto = resize(loneId, 80, 40);

        assertTrue("nothing was anchored to it, so nothing moved", dto.movedObjects().isEmpty());
        assertTrue("it is not inside a group, so nothing grew", dto.resizedAncestors().isEmpty());
        String body = json(dto);
        assertFalse("an empty list must be omitted from the wire, not serialized as []",
                body.contains("movedObjects"));
        assertFalse("an empty list must be omitted from the wire, not serialized as []",
                body.contains("resizedAncestors"));
    }

    /**
     * The detached case, which the first version of this change crashed on. Inside a batch the
     * object being updated may itself still be queued — its add command has not executed — so it
     * belongs to no view yet and there is nothing to resolve names from. The report must still name
     * what grew, falling back to the id, rather than failing the whole call.
     */
    @Test
    public void shouldStillReportTheGrownParent_whenTheUpdatedObjectIsItselfQueued() {
        dispatcher.beginBatch(SESSION, "create a nest, then grow a child inside it");
        String groupId = group("Queued", 0, 0, 200, 200, null);
        String childId = add("actor-1", 10, 10, 60, 30, groupId);

        ViewObjectDto dto = resize(childId, null, 300);
        dispatcher.endBatch(SESSION, true);

        assertTrue("fixture must actually grow the queued group, or this proves nothing: "
                + find(view, groupId).getBounds().getHeight(),
                find(view, groupId).getBounds().getHeight() > 200);
        assertFalse("the queued parent the cascade grew must still be named",
                dto.resizedAncestors().isEmpty());
        assertEquals("named by id, since a detached object belongs to no view to be named from",
                groupId, dto.resizedAncestors().get(0).viewObjectId());
        assertEquals("the reported height is the one the batch commits",
                find(view, groupId).getBounds().getHeight(),
                dto.resizedAncestors().get(0).newHeight());
    }

    // ---- bulk-mutate: the same tool, the other call path -----------------------------------------

    /**
     * {@code update-view-object} reaches the model through two prepares, and the bulk one is not the
     * one the primary pins exercise. A bulk operation that grows an object past its group's edge
     * resizes that group for real, so the per-operation result must name it — otherwise the fix
     * lands on one call path and the agent driving the other stays exactly as blind.
     */
    @Test
    public void shouldNameTheGrownGroup_whenUpdateViewObjectRunsInsideBulkMutate() {
        String groupId = group("Enclosing", 0, 0, 200, 200, null);
        String childId = add("actor-1", 10, 10, 60, 30, groupId);
        int before = find(view, groupId).getBounds().getHeight();

        BulkMutationResult bulk = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("update-view-object", op(
                        "viewObjectId", childId, "height", 300))),
                "bulk grow", false);

        assertTrue("fixture must actually grow the group, or this proves nothing: " + before
                + " -> " + find(view, groupId).getBounds().getHeight(),
                find(view, groupId).getBounds().getHeight() > before);

        BulkOperationResult op = bulk.operations().get(0);
        assertFalse("the per-operation result must name the group the update grew. Result was: "
                + op, op.resizedAncestors().isEmpty());
        assertEquals(groupId, op.resizedAncestors().get(0).viewObjectId());
        assertEquals("and carry the height the model holds",
                find(view, groupId).getBounds().getHeight(),
                op.resizedAncestors().get(0).newHeight());
    }

    /**
     * The back-reference branch: an object created earlier in the same bulk call is updated through
     * a different prepare entirely. It cascades into its parent group exactly as the other branch
     * does, so it owes the same report.
     */
    @Test
    public void shouldNameTheGrownGroup_whenBulkUpdatesAnObjectItCreatedInTheSameCall() {
        String groupId = group("Enclosing", 0, 0, 200, 200, null);
        int before = find(view, groupId).getBounds().getHeight();

        BulkMutationResult bulk = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("add-to-view", op(
                        "viewId", view.getId(), "elementId", "actor-1",
                        "parentViewObjectId", groupId,
                        "x", 10, "y", 10, "width", 60, "height", 30)),
                new BulkOperation("update-view-object", op(
                        "viewObjectId", "$0.id", "height", 300))),
                "bulk create then grow", false);

        assertTrue("fixture must actually grow the group, or this proves nothing: " + before
                + " -> " + find(view, groupId).getBounds().getHeight(),
                find(view, groupId).getBounds().getHeight() > before);

        BulkOperationResult op = bulk.operations().get(1);
        assertFalse("the back-reference branch must name the group it grew too. Result was: " + op,
                op.resizedAncestors().isEmpty());
        assertEquals(groupId, op.resizedAncestors().get(0).viewObjectId());
    }

    /**
     * The other list, on the same path. A bulk update that grows an anchor target displaces the
     * object anchored to it exactly as the single-tool call does, so the per-operation result owes
     * the same report — and a copier that rebuilds the result to attach post-dispatch geometry must
     * carry it through rather than defaulting it away.
     */
    @Test
    public void shouldNameTheDisplacedChild_whenUpdateViewObjectRunsInsideBulkMutate() {
        String targetId = add("actor-1", 100, 100, 60, 30, null);
        String childId = add("actor-2", 100, 140, 60, 30, null);
        accessor.updateViewObject(SESSION, childId, null, null, null, null,
                null, null, null, null, targetId, "below", 0, 10);
        int childYBefore = find(view, childId).getBounds().getY();

        BulkMutationResult bulk = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("update-view-object", op(
                        "viewObjectId", targetId, "height", 200))),
                "bulk grow an anchor target", false);

        int childYAfter = find(view, childId).getBounds().getY();
        assertTrue("fixture must actually displace the anchored child, or this proves nothing: "
                + childYBefore + " -> " + childYAfter, childYAfter != childYBefore);

        BulkOperationResult op = bulk.operations().get(0);
        assertFalse("the per-operation result must name the object it displaced. Result was: " + op,
                op.movedObjects().isEmpty());
        assertEquals(childId, op.movedObjects().get(0).viewObjectId());
        assertEquals("and carry where it landed", childYAfter, op.movedObjects().get(0).newY());
    }

    private static Map<String, Object> op(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // ---- assertions ----------------------------------------------------------------------------

    private void assertReportedMove(ViewObjectDto dto, String viewObjectId) {
        MovedViewObjectDto reported = null;
        for (MovedViewObjectDto m : dto.movedObjects()) {
            if (viewObjectId.equals(m.viewObjectId())) {
                reported = m;
            }
        }
        assertNotNull("update-view-object must name the anchored object it moved: " + viewObjectId
                + ". movedObjects was: " + dto.movedObjects(), reported);
        IDiagramModelObject live = find(view, viewObjectId);
        assertNotNull("the reported object must exist in the view: " + viewObjectId, live);
        IBounds actual = live.getBounds();
        assertEquals("moved " + viewObjectId + ": x", actual.getX(), reported.newX());
        assertEquals("moved " + viewObjectId + ": y", actual.getY(), reported.newY());
        assertEquals("moved " + viewObjectId + ": width", actual.getWidth(), reported.newWidth());
        assertEquals("moved " + viewObjectId + ": height", actual.getHeight(), reported.newHeight());
    }

    private void assertReportedAncestor(ViewObjectDto dto, String viewObjectId) {
        MovedViewObjectDto reported = null;
        for (MovedViewObjectDto m : dto.resizedAncestors()) {
            if (viewObjectId.equals(m.viewObjectId())) {
                reported = m;
            }
        }
        assertNotNull("update-view-object must name the container it grew: " + viewObjectId
                + ". resizedAncestors was: " + dto.resizedAncestors(), reported);
        IDiagramModelObject live = find(view, viewObjectId);
        assertNotNull("the reported container must exist in the view: " + viewObjectId, live);
        IBounds actual = live.getBounds();
        assertEquals("grew " + viewObjectId + ": x", actual.getX(), reported.newX());
        assertEquals("grew " + viewObjectId + ": y", actual.getY(), reported.newY());
        assertEquals("grew " + viewObjectId + ": width", actual.getWidth(), reported.newWidth());
        assertEquals("grew " + viewObjectId + ": height", actual.getHeight(), reported.newHeight());
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
