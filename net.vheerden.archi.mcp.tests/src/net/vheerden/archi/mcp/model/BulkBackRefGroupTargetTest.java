package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
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

import net.vheerden.archi.mcp.response.dto.BulkMutationResult;
import net.vheerden.archi.mcp.response.dto.BulkOperation;
import net.vheerden.archi.mcp.response.dto.BulkOperationResult;

/**
 * A group an earlier operation in the same bulk call created must be addressable as an
 * {@code update-view-object} target, not merely as a parent.
 *
 * <p>The two questions were answered by two different finders, and only one of them looked in the
 * map that groups are tracked in. So the identical {@code $N.id} resolved as a
 * {@code parentViewObjectId} and failed as a {@code viewObjectId} in the same call — an asymmetry
 * an agent could only discover by trying it, since nothing in the error said the id was fine and
 * the mechanism was not.</p>
 */
public class BulkBackRefGroupTargetTest {

    private static final String SESSION = "bulk-backref-group-target";

    private IArchimateFactory factory;
    private ArchiModelAccessorImpl accessor;
    private CommandStack stack;
    private IArchimateModel model;
    private IArchimateDiagramModel view;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        StubEditorModelManager stubModelManager = new StubEditorModelManager();

        model = factory.createArchimateModel();
        model.setName("Bulk BackRef Group Target Fixture");
        model.setId("model-bulk-backref-group");
        model.setDefaults();

        IFolder diagrams = model.getFolder(FolderType.DIAGRAMS);
        view = factory.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Target");
        diagrams.getElements().add(view);

        IFolder business = model.getFolder(FolderType.BUSINESS);
        IBusinessActor actor = factory.createBusinessActor();
        actor.setId("actor-1");
        actor.setName("Target Actor");
        business.getElements().add(actor);

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

    /** The capability itself, asserted against a fresh read of the model rather than the response. */
    @Test
    public void shouldResizeTheGroup_whenTheTargetIsABackReferenceToASameCallAddGroup() {
        BulkMutationResult bulk = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("add-group-to-view", op(
                        "viewId", view.getId(), "label", "Same Call Group",
                        "x", 0, "y", 0, "width", 200, "height", 200)),
                new BulkOperation("update-view-object", op(
                        "viewObjectId", "$0.id", "height", 400))),
                "create a group then resize it by back-reference", false);

        assertTrue("both operations must succeed", bulk.allSucceeded());
        String groupId = bulk.operations().get(0).entityId();
        IDiagramModelObject live = find(view, groupId);
        assertNotNull("the group must exist in the model", live);
        assertEquals("the group must hold the height the second operation asked for",
                400, live.getBounds().getHeight());
    }

    /**
     * The symmetry that was broken. One id, one call, both roles — a parent and a target.
     * Asserting only the target half would let the two finders drift apart again unnoticed.
     */
    @Test
    public void shouldAcceptTheSameBackReference_asBothAParentAndATarget() {
        BulkMutationResult bulk = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("add-group-to-view", op(
                        "viewId", view.getId(), "label", "Dual Role",
                        "x", 0, "y", 0, "width", 300, "height", 300)),
                new BulkOperation("add-to-view", op(
                        "viewId", view.getId(), "elementId", "actor-1",
                        "parentViewObjectId", "$0.id",
                        "x", 10, "y", 10, "width", 120, "height", 55)),
                new BulkOperation("update-view-object", op(
                        "viewObjectId", "$0.id", "width", 500))),
                "the same id as a parent and as a target", false);

        assertTrue("all three operations must succeed", bulk.allSucceeded());
        String groupId = bulk.operations().get(0).entityId();
        assertEquals("the target role must have applied", 500,
                find(view, groupId).getBounds().getWidth());
        assertNotNull("the parent role must have applied",
                find((IDiagramModelContainer) find(view, groupId), bulk.operations().get(1).entityId()));
    }

    /**
     * A group has no associated element, so the widened prepare must fall back to the object's own
     * name and type rather than dereferencing an element that is not there.
     */
    @Test
    public void shouldNameAndTypeTheGroupItself_whenTheTargetHasNoAssociatedElement() {
        BulkMutationResult bulk = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("add-group-to-view", op(
                        "viewId", view.getId(), "label", "Self Naming",
                        "x", 0, "y", 0, "width", 200, "height", 200)),
                new BulkOperation("update-view-object", op(
                        "viewObjectId", "$0.id", "height", 250))),
                "a group names itself", false);

        BulkOperationResult update = bulk.operations().get(1);
        assertEquals("DiagramModelGroup", update.entityType());
        assertEquals("Self Naming", update.entityName());
    }

    /**
     * Non-regression for the case that already worked: an element view object created in the same
     * call is still resolved, and still reports the containers its growth grew.
     */
    @Test
    public void shouldStillReportResizedAncestors_whenTheTargetIsASameCallElement() {
        BulkMutationResult bulk = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("add-group-to-view", op(
                        "viewId", view.getId(), "label", "Holder",
                        "x", 0, "y", 0, "width", 200, "height", 200)),
                new BulkOperation("add-to-view", op(
                        "viewId", view.getId(), "elementId", "actor-1",
                        "parentViewObjectId", "$0.id",
                        "x", 10, "y", 10, "width", 120, "height", 55)),
                new BulkOperation("update-view-object", op(
                        "viewObjectId", "$1.id", "height", 400))),
                "grow a same-call element past its same-call group", false);

        assertTrue("all three operations must succeed", bulk.allSucceeded());
        BulkOperationResult update = bulk.operations().get(2);
        assertEquals("the element target still resolves through the widened finder",
                "BusinessActor", update.entityType());
        assertFalse("growing the child past the group must still name the group it grew: " + update,
                update.resizedAncestors().isEmpty());
        assertEquals(bulk.operations().get(0).entityId(),
                update.resizedAncestors().get(0).viewObjectId());
    }

    /**
     * A group's {@code text} is its label — the thing an agent is most likely to want to change
     * about a group it just created. Widening this path to accept groups without carrying text
     * through meant the rename was accepted and silently discarded.
     */
    @Test
    public void shouldRenameTheGroup_whenTextIsSetOnASameCallBackReference() {
        BulkMutationResult bulk = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("add-group-to-view", op(
                        "viewId", view.getId(), "label", "Original",
                        "x", 0, "y", 0, "width", 200, "height", 200)),
                new BulkOperation("update-view-object", op(
                        "viewObjectId", "$0.id", "text", "Renamed", "width", 260))),
                "create a group then rename it by back-reference", false);

        assertTrue("both operations must succeed", bulk.allSucceeded());
        String groupId = bulk.operations().get(0).entityId();
        assertEquals("the model must hold the new label, not the old one",
                "Renamed", find(view, groupId).getName());
        assertEquals("and the geometry alongside it", 260,
                find(view, groupId).getBounds().getWidth());
    }

    /**
     * Text on its own used to be rejected with a message that listed every acceptable field except
     * the one that had been supplied.
     */
    @Test
    public void shouldAcceptTextAsTheOnlyField_whenTargetingASameCallGroup() {
        BulkMutationResult bulk = accessor.executeBulk(SESSION, List.of(
                new BulkOperation("add-group-to-view", op(
                        "viewId", view.getId(), "label", "Before",
                        "x", 0, "y", 0, "width", 200, "height", 200)),
                new BulkOperation("update-view-object", op(
                        "viewObjectId", "$0.id", "text", "After"))),
                "text is the only field", false);

        assertTrue("text alone must be a sufficient field", bulk.allSucceeded());
        assertEquals("After", find(view, bulk.operations().get(0).entityId()).getName());
    }

    /**
     * The other half of the parity: an element view object shows its element's name, so text has
     * no meaning there and must be rejected on this path exactly as on the live one — not dropped,
     * and not applied to a box whose name is derived elsewhere.
     */
    @Test
    public void shouldRejectText_whenTheSameCallTargetIsAnElementViewObject() {
        try {
            accessor.executeBulk(SESSION, List.of(
                    new BulkOperation("add-to-view", op(
                            "viewId", view.getId(), "elementId", "actor-1",
                            "x", 0, "y", 0, "width", 120, "height", 55)),
                    new BulkOperation("update-view-object", op(
                            "viewObjectId", "$0.id", "text", "not allowed"))),
                    "text on an element", false);
            throw new AssertionError("expected text on an element view object to be rejected");
        } catch (ModelAccessException e) {
            assertTrue("the message must say why, not merely that it failed: " + e.getMessage(),
                    e.getMessage().contains("Cannot set text on an ArchiMate element view object"));
        }
    }

    private static Map<String, Object> op(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static IDiagramModelObject find(IDiagramModelContainer container, String id) {
        for (Object child : container.getChildren()) {
            IDiagramModelObject obj = (IDiagramModelObject) child;
            if (id.equals(obj.getId())) {
                return obj;
            }
            if (obj instanceof IDiagramModelContainer nested) {
                IDiagramModelObject hit = find(nested, id);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
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
