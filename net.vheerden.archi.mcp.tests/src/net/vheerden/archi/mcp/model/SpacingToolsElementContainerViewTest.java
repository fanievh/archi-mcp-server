package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IBounds;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;

import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.ApplyElementSpacingRecommendationsResultDto;
import net.vheerden.archi.mcp.response.dto.ApplyGroupSpacingRecommendationsResultDto;
import net.vheerden.archi.mcp.response.dto.ApplySpacingRecommendationsResultDto;
import net.vheerden.archi.mcp.response.dto.SkippedContainerDto;

/**
 * The spacing tools, driven through the accessor, on a view whose containers are ArchiMate
 * elements holding children: they say what the view holds, list the containers they did not
 * measure, and change nothing they did not change before.
 *
 * <p>Display lane: every call runs an assessment over named elements, which measures text.</p>
 */
public class SpacingToolsElementContainerViewTest {

    private static final String SESSION = "spacing-element-container-session";

    private IArchimateFactory factory;
    private ArchiModelAccessorImpl accessor;
    private MutationDispatcher dispatcher;
    private CommandStack stack;
    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private int nextRel;

    @Before
    public void setUp() {
        factory = IArchimateFactory.eINSTANCE;
        StubEditorModelManager manager = new StubEditorModelManager();
        model = factory.createArchimateModel();
        model.setName("Element containers");
        model.setId("model-element-containers");
        model.setDefaults();
        view = factory.createArchimateDiagramModel();
        view.setId("view-lanes");
        view.setName("Lanes");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);
        manager.setModels(List.of(model));

        stack = new CommandStack();
        dispatcher = new MutationDispatcher(() -> model) {
            @Override
            public void dispatchImmediate(Command command) {
                stack.execute(new AgentAuthoredCompoundCommand(plain(command)));
            }
            @Override
            protected void dispatchCommand(Command command) {
                stack.execute(new AgentAuthoredCompoundCommand(plain(command)));
            }
            private Command plain(Command command) {
                if (command instanceof CompoundCommand compound) {
                    CompoundCommand copy = new CompoundCommand(compound.getLabel());
                    for (Object child : compound.getCommands()) {
                        copy.add(plain((Command) child));
                    }
                    return copy;
                }
                return command;
            }
        };
        dispatcher.setApprovalModeProvider(() -> false);
        accessor = new ArchiModelAccessorImpl(manager, dispatcher);
    }

    @After
    public void tearDown() {
        if (accessor != null) {
            accessor.dispose();
        }
    }

    // ---- four roles as lanes, no group at all --------------------------------------------

    @Test
    public void elementTool_shouldSayWhatTheViewHolds_listTheLanes_andChangeNothing() {
        List<String> lanes = fourRoleLanes();
        Map<String, int[]> before = snapshot();

        ApplyElementSpacingRecommendationsResultDto dto = accessor.applyElementSpacingRecommendations(
                SESSION, view.getId(), false, null, null).entity();

        assertElementContainerAnswer(dto.noChangeReason(), dto.terminationReason(), 1);
        assertLanes(lanes, dto.skippedContainers());
        assertEquals("nothing was measured; the reason says so", 0, dto.currentSpacingPx());
        assertUnchanged(before);
    }

    @Test
    public void elementTool_shouldGiveTheSameAnswerOnADryRun() {
        List<String> lanes = fourRoleLanes();
        ApplyElementSpacingRecommendationsResultDto dto = accessor.applyElementSpacingRecommendations(
                SESSION, view.getId(), true, null, null).entity();
        assertElementContainerAnswer(dto.noChangeReason(), dto.terminationReason(), 1);
        assertLanes(lanes, dto.skippedContainers());
    }

    @Test
    public void composer_shouldAnswerForBothArms_listTheLanes_andChangeNothing() {
        List<String> lanes = fourRoleLanes();
        Map<String, int[]> before = snapshot();

        ApplySpacingRecommendationsResultDto dto = accessor.applySpacingRecommendations(
                SESSION, view.getId(), "both", false, null, null).entity();

        assertElementContainerAnswer(dto.noChangeReason(), dto.elementTerminationReason(), 2);
        assertTrue(dto.noChangeReason(), dto.noChangeReason().startsWith("element: View "));
        assertTrue(dto.noChangeReason(), dto.noChangeReason().contains("; group: View "));
        assertLanes(lanes, dto.skippedContainers());
        assertUnchanged(before);
    }

    @Test
    public void groupTool_shouldSayWhatTheViewHolds_listTheLanes_andChangeNothing() {
        List<String> lanes = fourRoleLanes();
        Map<String, int[]> before = snapshot();

        ApplyGroupSpacingRecommendationsResultDto dto = accessor.applyGroupSpacingRecommendations(
                SESSION, view.getId(), false, null, null).entity();

        assertElementContainerAnswer(dto.noChangeReason(), dto.terminationReason(), 1);
        assertLanes(lanes, dto.skippedContainers());
        assertUnchanged(before);
    }

    @Test
    public void refusingTools_shouldNameTheElementContainers_andLayoutWithinGroup() {
        List<String> lanes = fourRoleLanes();
        Map<String, int[]> before = snapshot();

        assertRefusal(lanes, () -> accessor.adjustViewSpacing(SESSION, view.getId(), 20, null, null,
                false));
        assertRefusal(lanes, () -> accessor.optimizeGroupOrder(SESSION, view.getId(), null, null,
                null, null, null, false, null, null));
        assertRefusal(lanes, () -> accessor.autoLayoutAndRoute(SESSION, view.getId(), "grouped",
                null, 0, null, null));
        assertUnchanged(before);
    }

    // ---- a mixed view: one Grouping zone, two role lanes ---------------------------------

    @Test
    public void mixedView_shouldListTheLanes_andNeverMoveThem() {
        List<String> lanes = mixedView();
        Map<String, int[]> lanesBefore = geometryUnder(lanes);

        ApplyElementSpacingRecommendationsResultDto element = accessor
                .applyElementSpacingRecommendations(SESSION, view.getId(), false, 120, 3).entity();
        assertLanes(lanes, element.skippedContainers());
        assertFalse("the element step has a zone to work in here", SpacingEntryGuardTermination
                .isElementContainersReason(element.noChangeReason()));

        // Whatever the element step did, the two dry runs after it must change nothing at all —
        // no box and no bendpoint, lanes, zone and connections alike.
        String afterElement = wholeView();

        ApplyGroupSpacingRecommendationsResultDto group = accessor
                .applyGroupSpacingRecommendations(SESSION, view.getId(), true, null, null).entity();
        assertLanes(lanes, group.skippedContainers());
        assertTrue("the group step is short of two zones, and says what the view holds: "
                + group.noChangeReason(), group.noChangeReason().contains(
                        "holds 2 element containers (ArchiMate elements that hold children) and 1 "
                                + "native group or Grouping element."));

        ApplySpacingRecommendationsResultDto composed = accessor.applySpacingRecommendations(
                SESSION, view.getId(), "both", true, null, null).entity();
        assertLanes(lanes, composed.skippedContainers());

        assertEquals("the lanes and everything in them are where they were", toString(lanesBefore),
                toString(geometryUnder(lanes)));
        assertEquals("the dry runs moved nothing on the view", afterElement, wholeView());
    }

    // ---- a zone drawn inside a host, beside a role lane ----------------------------------

    @Test
    public void nestedZone_shouldKeepTheNotPositionedAnswer_andStillListTheHosts() {
        String host = add(element(factory.createNode(), "Host"), 40, 40, 700, 400, null);
        String zone = add(element(factory.createGrouping(), "Zone"), 40, 60, 500, 250, host);
        String z1 = add(element(factory.createBusinessProcess(), "Z1"), 20, 60, zone);
        String z2 = add(element(factory.createBusinessProcess(), "Z2"), 200, 60, zone);
        connect(z1, z2);
        String lane = add(element(factory.createBusinessRole(), "Lane 1"), 40, 500, 600, 220, null);
        String a = add(element(factory.createBusinessProcess(), "La"), 40, 70, lane);
        String b = add(element(factory.createBusinessProcess(), "Lb"), 260, 70, lane);
        connect(a, b);
        String before = wholeView();

        ApplyElementSpacingRecommendationsResultDto dto = accessor.applyElementSpacingRecommendations(
                SESSION, view.getId(), false, null, null).entity();

        assertEquals("the drawn-inside-a-host answer outranks the element-container one",
                SpacingEntryGuardTermination.CONTAINERS_NOT_POSITIONED_BY_THIS_TOOL,
                dto.terminationReason());
        assertFalse(dto.noChangeReason(),
                SpacingEntryGuardTermination.isElementContainersReason(dto.noChangeReason()));
        assertEquals(List.of(host, lane),
                dto.skippedContainers().stream().map(SkippedContainerDto::viewObjectId).toList());
        assertEquals(before, wholeView());
    }

    @Test
    public void mixedView_shouldListTheLanes_onTheQueuedArm() throws Exception {
        List<String> lanes = mixedView();
        dispatcher.beginBatch(SESSION, "queued spacing");
        try {
            var result = accessor.applyElementSpacingRecommendations(SESSION, view.getId(), false,
                    120, 3);
            assertLanes(lanes, result.entity().skippedContainers());
        } finally {
            dispatcher.endBatch(SESSION, false);
        }
    }

    // ---- a genuinely flat view ------------------------------------------------------------

    @Test
    public void flatView_shouldKeepItsAnswerByteForByte() {
        String a = add(element(factory.createBusinessProcess(), "A"), 40, 40, null);
        String b = add(element(factory.createBusinessProcess(), "B"), 300, 40, null);
        connect(a, b);

        ApplyElementSpacingRecommendationsResultDto dto = accessor.applyElementSpacingRecommendations(
                SESSION, view.getId(), false, null, null).entity();

        assertEquals("view has no groups; spacing recommendation not applicable on flat views — use "
                + "update-view-object for surgical edits or arrange-groups to introduce groups first",
                dto.noChangeReason());
        assertTrue(dto.skippedContainers().isEmpty());
        assertTrue(dto.terminationReason().startsWith("structural_no_change_view_has_no_groups"));

        ApplyGroupSpacingRecommendationsResultDto group = accessor.applyGroupSpacingRecommendations(
                SESSION, view.getId(), false, null, null).entity();
        assertTrue(group.skippedContainers().isEmpty());
        assertTrue(group.terminationReason(), group.terminationReason().startsWith(
                "structural_no_change_view_has_fewer_than_2_top_level_groups"));
        assertFalse(SpacingEntryGuardTermination.isElementContainersReason(group.noChangeReason()));

        ApplySpacingRecommendationsResultDto composed = accessor.applySpacingRecommendations(
                SESSION, view.getId(), "both", false, null, null).entity();
        assertTrue(composed.skippedContainers().isEmpty());
        assertTrue(composed.noChangeReason(), composed.noChangeReason().contains("view has no groups"));
        assertFalse(SpacingEntryGuardTermination.isElementContainersReason(composed.noChangeReason()));
        assertFalse(composed.elementTerminationReason(), composed.elementTerminationReason()
                .contains(SpacingEntryGuardTermination.CONTAINERS_NOT_SPACED_BY_THIS_TOOL));
    }

    // ---- fixtures and assertions ----------------------------------------------------------

    private List<String> fourRoleLanes() {
        List<String> lanes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String lane = add(element(factory.createBusinessRole(), "Lane " + (i + 1)),
                    40, 40 + i * 240, 700, 200, null);
            String previous = null;
            for (int s = 0; s < 2 + (i % 2); s++) {
                String step = add(element(factory.createBusinessProcess(), "Step " + i + "." + s),
                        40 + s * 200, 70, lane);
                if (previous != null) {
                    connect(previous, step);
                }
                previous = step;
            }
            lanes.add(lane);
        }
        connect(childOf(lanes.get(0)), childOf(lanes.get(1)));
        return lanes;
    }

    private List<String> mixedView() {
        String zone = add(element(factory.createGrouping(), "Zone"), 40, 40, 520, 220, null);
        String z1 = add(element(factory.createBusinessProcess(), "Z1"), 20, 60, zone);
        String z2 = add(element(factory.createBusinessProcess(), "Z2"), 160, 60, zone);
        String z3 = add(element(factory.createBusinessProcess(), "Z3"), 300, 60, zone);
        connect(z1, z2);
        connect(z2, z3);
        List<String> lanes = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String lane = add(element(factory.createBusinessRole(), "Lane " + (i + 1)),
                    700, 40 + i * 260, 600, 220, null);
            String a = add(element(factory.createBusinessProcess(), "L" + i + "a"), 40, 70, lane);
            String b = add(element(factory.createBusinessProcess(), "L" + i + "b"), 260, 70, lane);
            connect(a, b);
            lanes.add(lane);
        }
        connect(z3, childOf(lanes.get(0)));
        return lanes;
    }

    private static void assertElementContainerAnswer(String reason, String code, int arms) {
        assertNotNull(reason);
        assertEquals(SpacingEntryGuardTermination.CONTAINERS_NOT_SPACED_BY_THIS_TOOL, code);
        assertEquals(arms, reason.split(SpacingEntryGuardTermination.NOT_SPACED_MARKER, -1).length - 1);
        assertTrue(reason, reason.contains("holds 4 element containers"));
        assertTrue(reason, reason.contains("layout-within-group"));
        String lower = reason.toLowerCase(java.util.Locale.ROOT);
        assertFalse(reason, lower.contains("no groups"));
        assertFalse(reason, lower.contains("flat view"));
    }

    private void assertLanes(List<String> lanes, List<SkippedContainerDto> skipped) {
        assertEquals(lanes, skipped.stream().map(SkippedContainerDto::viewObjectId).toList());
        for (SkippedContainerDto entry : skipped) {
            assertEquals("BusinessRole", entry.elementType());
            assertTrue(entry.name().startsWith("Lane "));
            assertEquals(TopLevelGroupTargets.SPACING_SKIP_REASON, entry.reason());
        }
    }

    private void assertRefusal(List<String> lanes, Runnable call) {
        try {
            call.run();
            fail("the tool acts on grouping containers only and must still refuse this view");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
            String message = e.getMessage();
            assertTrue(message, message.contains("4 ArchiMate elements drawn on it hold children"));
            for (String lane : lanes) {
                assertTrue(message, message.contains(lane));
            }
            assertFalse(message, message.toLowerCase(java.util.Locale.ROOT).contains("no groups"));
            assertTrue(e.getSuggestedCorrection().contains("layout-within-group"));
        }
    }

    private Command topBefore;

    /** The view's geometry, remembering the command on top of the stack beside it. */
    private Map<String, int[]> snapshot() {
        topBefore = stack.getUndoCommand();
        return geometry();
    }

    private void assertUnchanged(Map<String, int[]> before) {
        assertTrue("no command reached the stack", stack.getUndoCommand() == topBefore);
        assertEquals(toString(before), toString(geometry()));
    }

    /** Every box and every connection's bendpoints, in order. */
    private String wholeView() {
        StringBuilder sb = new StringBuilder(toString(geometry()));
        appendBendpoints(view, sb);
        return sb.toString();
    }

    private static void appendBendpoints(IDiagramModelContainer container, StringBuilder sb) {
        for (IDiagramModelObject child : container.getChildren()) {
            for (var c : child.getSourceConnections()) {
                sb.append(c.getId()).append(':');
                for (var bp : c.getBendpoints()) {
                    sb.append(' ').append(bp.getStartX()).append(',').append(bp.getStartY())
                            .append('/').append(bp.getEndX()).append(',').append(bp.getEndY());
                }
                sb.append('\n');
            }
            if (child instanceof IDiagramModelContainer nested) {
                appendBendpoints(nested, sb);
            }
        }
    }

    private Map<String, int[]> geometry() {
        Map<String, int[]> map = new LinkedHashMap<>();
        collect(view, map);
        return map;
    }

    private Map<String, int[]> geometryUnder(List<String> ids) {
        Map<String, int[]> map = new LinkedHashMap<>();
        for (String id : ids) {
            IDiagramModelObject obj = find(view, id);
            map.put(id, rect(obj));
            collect((IDiagramModelContainer) obj, map);
        }
        return map;
    }

    private static void collect(IDiagramModelContainer container, Map<String, int[]> map) {
        for (IDiagramModelObject child : container.getChildren()) {
            map.put(child.getId(), rect(child));
            if (child instanceof IDiagramModelContainer nested) {
                collect(nested, map);
            }
        }
    }

    private static String toString(Map<String, int[]> map) {
        StringBuilder sb = new StringBuilder();
        map.forEach((k, v) -> sb.append(k).append('=').append(java.util.Arrays.toString(v)).append('\n'));
        return sb.toString();
    }

    private static int[] rect(IDiagramModelObject o) {
        IBounds b = o.getBounds();
        return new int[]{b.getX(), b.getY(), b.getWidth(), b.getHeight()};
    }

    private String childOf(String containerId) {
        return ((IDiagramModelContainer) find(view, containerId)).getChildren().get(0).getId();
    }

    private IArchimateElement element(IArchimateElement element, String name) {
        element.setId("el-" + name.replace(' ', '-').replace('.', '-'));
        element.setName(name);
        IFolder folder = model.getDefaultFolderForObject(element);
        folder.getElements().add(element);
        return element;
    }

    private String add(IArchimateElement element, int x, int y, String parentId) {
        return add(element, x, y, 120, 55, parentId);
    }

    private String add(IArchimateElement element, int x, int y, int w, int h, String parentId) {
        return accessor.addToView(SESSION, view.getId(), element.getId(), x, y, w, h, false, parentId,
                null, null).entity().viewObject().viewObjectId();
    }

    private void connect(String sourceViewObject, String targetViewObject) {
        IArchimateElement source = ((com.archimatetool.model.IDiagramModelArchimateObject)
                find(view, sourceViewObject)).getArchimateElement();
        IArchimateElement target = ((com.archimatetool.model.IDiagramModelArchimateObject)
                find(view, targetViewObject)).getArchimateElement();
        IArchimateRelationship rel = factory.createFlowRelationship();
        rel.setId("rel-" + (++nextRel));
        rel.setSource(source);
        rel.setTarget(target);
        model.getFolder(FolderType.RELATIONS).getElements().add(rel);
        accessor.addConnectionToView(SESSION, view.getId(), rel.getId(), sourceViewObject,
                targetViewObject, null, null, null, null, null);
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
